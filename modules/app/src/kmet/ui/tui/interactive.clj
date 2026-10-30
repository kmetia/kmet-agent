(ns kmet.ui.tui.interactive
  "Interactive TUI mode entry — resolves the session, builds the layout,
   starts the render loop, and emits the session-start event.
   pi: modes/interactive/interactive-mode.ts."
  (:require [clojure.string :as str]
            [kmet.tui.core :as tui]
            [kmet.hikap.component :as component]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as term]
            [kmet.libs.theme :as th]
            [kmet.tui.timers :as timers]
            [kmet.app.config :as cfg]
            [babashka.fs :as fs]
            [kmet.app.debug :as debug]
            [kmet.app.events :as events]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.permissions :as permissions]
            [kmet.app.session.core :as session]
            [kmet.app.session.runtime :as runtime]
            [kmet.app.tools.registry :as tool-registry]
            [kmet.app.tools.util :as tool-util]
            [kmet.srce.core :as srce]
            [kmet.ui.tui.theme-controller :as theme-ctrl]
            [kmet.ui.tui.bash-execution :as bash-execution]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.dialogs :as dialogs]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.session-selector :refer [show-session-selector]]
            [kmet.libs.process :as process]
            [kmet.reakt.core :as reakt]
            [kmet.ui.tui.interactive.layout :as layout]
            [kmet.ui.tui.interactive.session-admin :as session-admin]
            [kmet.ui.tui.interactive.state :as state]))

;; CoreState lives in kmet.ui.tui.interactive.state; the constructors
;; stay re-exported here for consumers of this namespace (tests).
(def ->CoreState state/->CoreState)
(def map->CoreState state/map->CoreState)

;; ─── Mode teardown ─────────────────────────────────────────────────────────

(defn- dispose-mode-tree!
  "Release the layout tree's components after the TUI stopped (finding 2).
   Pending bash components and open overlays live outside the TUI's child
   list, so they are disposed explicitly: pending bash first, then
   dock/clear! (leave-before-dispose for dock entries), then the remaining
   overlays, then the TUI's direct children (document container, pending
   messages, status, widget strips, footer) via tui-clear; the chat-history
   cascade runs through ChatHistoryComponent.dispose. Each step is isolated
   and idempotent."
  [cs]
  (try (bash-execution/dispose-pending-bash! @(:pending-bash-components cs))
       (catch Throwable _))
  (try (reset! (:pending-bash-components cs) []) (catch Throwable _))
  (try (dock/clear! cs) (catch Throwable _))
  (try (doseq [ov @(:overlays (:tui cs))]
         (protocols/dispose-component! (:component ov)))
       (catch Throwable _))
  (try (tui/tui-clear (:tui cs)) (catch Throwable _))
  nil)

(defn teardown-mode!
  "Best-effort release of everything that roots the mode after the TUI
   stopped (findings 2 and 10). Each step is isolated: a throw here must
   not mask the original exception on the finally path. Order is the agent
   (cancel, wait, dispose) → its subscription → the bash signal → the
   extension surfaces → the tree → process-global roots. Idempotent, so a
   second call is a no-op."
  [cs]
  (try (when-let [engine (:engine cs)]
         ;; cancel an in-flight run and wait for it to settle before the
         ;; listener goes: the registry's :dispose! releases the agent (and its
         ;; child ctx), it does not wait for a run
         (let [running? (runtime/turn-running? engine)]
           (when (or running? (runtime/compacting? engine))
             ((:cancel! engine) nil))
           ;; the cancel drops the turn flag synchronously, so the run's future
           ;; is what is waited on, not the flag
           (when running?
             ;; bounded like the old dispose!: a run whose tool future ignores
             ;; the cancel must not park teardown forever
             (deref (future ((:when-idle engine))) 2000 nil)))
         (when-let [agents (:agents cs)]
           ((:dispose! agents) engine)))
       (catch Throwable _))
  (try (when-let [subscription (:event-subscription cs)]
         (events/unsubscribe! subscription))
       (catch Throwable _))
  (try (reset! (:bash-signal cs) true) (catch Throwable _))
  (try (extensions/ui-reset!) (catch Throwable _))
  (try (dispose-mode-tree! cs) (catch Throwable _))
  (try (extensions/clear-runtime!) (catch Throwable _))
  (try (extensions/unbind-extensions!) (catch Throwable _))
  (try (theme-ctrl/shutdown! (:theme-controller cs)) (catch Throwable _))
  (try (reakt/discard-queued!) (catch Throwable _))
  nil)

(defn- survivor-report
  "One line naming what teardown could not release: live
   track! components, live reactions, armed timers. Timers are included
   because an armed timer roots a mode on its own — both counters can read
   zero while a ticker still holds it. nil when everything released."
  []
  (let [components (component/live-watch-count)
        reactions (reakt/live-reaction-count)
        timer-ids (vec (keys (timers/scheduled)))]
    (when (or (pos? components) (pos? reactions) (seq timer-ids))
      (str "leaks: track! components=" components
           " reactions=" reactions
           " armed timers=" timer-ids))))

(defn- log-survivors!
  "Best-effort survivor report on the --debug exit path. It must never
   throw (or mask) on teardown's finally."
  []
  (try
    (when-some [line (survivor-report)]
      (debug/log line))
    (catch Throwable _)))

;; ─── The :ui port's dialogs ────────────────────────────────────────────────
;;
;; The TUI is the process's `:ui` and `:approvals` provider (`kmet.ui.tui`):
;; a dialog is drawn by the terminal that is actually on screen, and there is
;; at most one of those per process, so one atom is the whole state the
;; provider needs. Both answers block the caller — a tool call, an extension's
;; dialog request — until a human answers, which is what an unanswered dialog
;; means (kmet.app.permissions: the ask owns its timeout).

;; the running TUI's CoreState, or nil outside a run
(defonce ^:private live-mode (atom nil))

;; promises callers are parked on while their dialog waits; teardown denies
;; them, so a stopped TUI never leaves a tool call waiting on a dialog that can
;; never be answered
(defonce ^:private pending-answers (atom #{}))

(defn- abandon-on-cancel!
  "Arm the watcher that answers an open dialog with OPTS' `:abandon` answer
   once its `:signal` — the run's cancel flag — is set: a question about a run
   that is gone can never be answered, so it must not park its caller. The poll
   is a timer thunk, which runs on the loop thread, because closing the dialog
   disposes a component. Returns the timer id for the caller to cancel when the
   dialog closes first, nil when the dialog names no run."
  [{:keys [signal abandon]} answer!]
  (when (and signal abandon)
    (timers/every! 100 (fn [] (when @signal (answer! abandon))))))

(defn- wait-for-dialog!
  "Mount the dialog MAKE builds — given ANSWER!, which closes the dialog and
   delivers its value — on CS's dock, and block until one of its callbacks
   answers. Returns that value, or nil when the mode tears down first (the
   promise joins `pending-answers` before the mount, and run's teardown denies
   every one of them). OPTS may carry the run's `:signal` and the `:abandon`
   answer it implies — see abandon-on-cancel!."
  [cs make opts]
  (let [dlg-atom (atom nil)
        result (promise)
        close! (fn []
                 (dock/close-atom! cs dlg-atom)
                 (tui/tui-request-render (:tui cs)))
        answer! (fn [value]
                  (close!)
                  (deliver result value))
        dlg (make answer!)
        ;; armed only once the dialog is on the dock (below): the poll closes
        ;; it, and a close racing the mount could dispose a panel the stack
        ;; publishes afterwards. The `finally` disarms it, so a mount that
        ;; throws cannot leave the poll answering a dialog nobody will show.
        watcher (atom nil)]
    (reset! dlg-atom dlg)
    (swap! pending-answers conj result)
    (try
      (dock/mount! cs dlg)
      (tui/tui-request-render (:tui cs))
      (reset! watcher (abandon-on-cancel! opts answer!))
      (deref result)
      (finally
        (some-> @watcher timers/cancel!)
        (swap! pending-answers disj result)))))

(defn- choose!
  "Mount a selector over OPTIONS on CS's dock and return the chosen label, nil
   when it is cancelled or the mode tears down first. Blocks. OPTS as in
   wait-for-dialog!."
  ([cs title options] (choose! cs title options nil))
  ([cs title options opts]
   (wait-for-dialog!
    cs (fn [answer!]
         (dialogs/make-selector-dialog title options
                                       answer! #(answer! nil)
                                       (th/get-current-theme)))
    opts)))

(defn- type-in!
  "Mount a one-line input dialog on CS's dock and return the submitted text,
   nil when it is cancelled or the mode tears down first. Blocks."
  [cs title prefill]
  (wait-for-dialog!
   cs (fn [answer!]
        (dialogs/make-input-dialog title answer! #(answer! nil)
                                   (th/get-current-theme) prefill))
   nil))

(defn- selected-value
  "The value CHOSEN names among OPTIONS: a map option's `:value`, a plain
   option itself. nil when nothing was chosen."
  [options chosen]
  (when chosen
    (if (every? map? options)
      (:value (some #(when (= chosen (str (:label %))) %) options))
      chosen)))

(defn- dialog-title
  "TITLE and MESSAGE as the selector's one title block (a newline keeps the
   dialog's chrome one line high)."
  [request]
  (let [title (or (:title request) "Confirm")
        message (str (:message request))]
    (if (str/blank? message) title (str title "\n" message))))

(defn request!
  "Answer a UI request through the live TUI — the `:ui` provider's `:request!`.
   `:confirm`, `:select` and `:input` mount the dialog and block until it is
   answered; every other kind takes the ABI's default answer, because no other
   kind has a terminal answer yet. Outside a run every kind degrades the same
   way (`kmet.app.extensions.core/ui-request-default`).

   Blocks the caller until the dialog is answered, so it must not run on the
   TUI's own threads: the permission gate calls it from the agent's run thread,
   and a future front-end surface that answers extension requests from a render
   or input thread must use the ABI's asynchronous `request-pending`/`respond!`
   pair instead."
  [_ctx request]
  (let [default (extensions/ui-request-default (:kind request))]
    (if-let [cs @live-mode]
      (case (:kind request)
        :confirm (= "Yes" (choose! cs (dialog-title request) ["Yes" "No"]))
        :select (let [options (vec (:options request))]
                  (selected-value options
                                  (choose! cs (dialog-title request)
                                           (mapv #(str (if (map? %) (:label %) %))
                                                 options))))
        :input (type-in! cs (dialog-title request) (or (:prefill request) (:value request)))
        default)
      default)))

(def ask-choices
  "What a permission `:ask` can be answered with at the terminal. The labels are
   the list order a human reads: the narrow answer first, then the two that are
   remembered (`kmet.app.permissions/remembering`), then no."
  ["Yes" "Always for this agent" "Always for this workspace" "No"])

(defn ask-answer
  "The approver answer CHOICE names: `:allow` for this call, `:allow` scoped to
   the agent or the workspace, or a deny — `:once` is what an unscoped `:allow`
   already means, so `Yes` carries no scope rather than a redundant one. A
   cancelled dialog (nil) is the No answer: it denies this one call, remembers
   nothing, and is not a verdict of its own. A ready answer map — the deny a
   cancelled run answers its question with — passes through."
  [choice]
  (if (map? choice)
    choice
    (case choice
      "Yes" :allow
      "Always for this agent" {:decision :allow :scope :agent}
      "Always for this workspace" {:decision :allow :scope :workspace}
      ;; "No", and a cancelled dialog (nil): deny this call, remember nothing
      {:decision :deny :reason "denied at the terminal"})))

(defn ask-title
  "The dialog title a permission `:ask` puts to the human: the tool's name, the
   call's one-line title when its definition has one (`$ <command>` for bash,
   the path for read/write/edit — `kmet.app.tools.util/title-body`, the same
   plain-text body the quiet transcript line falls back to), then the policy's
   reason. Plain text: the dialog styles it. The call belongs in the question
   because the transcript block is behind the dialog and may be scrolled out of
   the visible window — a human approves what they can see."
  [ctx request]
  ;; stringified: a broken policy reason must degrade this line, not throw it
  (let [reason (str (:reason request))
        tool (when-let [tools (srce/get ctx :tools)]
               (tool-registry/get-tool tools (:tool-name request)))
        call (when tool (tool-util/title-body (:title tool) (:args request)))]
    (str "Allow " (or (:tool-name request) "this tool") "?"
         (when call (str "\n" call))
         (when-not (str/blank? reason) (str "\n" reason)))))

(defn ask!
  "Answer a permission `:ask` through the live TUI: a dialog naming the tool,
   the call it will run (`ask-title`), and, when the policy gave one, its
   reason — yes once, yes for this agent, yes for this workspace, or no. The
   two `Always …` answers are what the permission row's memory records, so a
   gated tool is asked once rather than per call. A deny otherwise, including
   outside a run, because nobody being able to ask is not a yes. Blocks while
   the dialog is up, so the caller must be a run thread (the gate's) and never
   a TUI render/input thread; a future front-end surface answers extension
   requests asynchronously for the same reason. A run cancelled while the
   question waits answers it with a deny (`abandon-on-cancel!`), so the dialog
   cannot hold a run that is already gone."
  [ctx request]
  (if-let [cs @live-mode]
    (let [tui (:tui cs)
          listener (layout/tool-display-listener cs)]
      (tui/tui-add-input-listener tui listener)
      (try
        (ask-answer
         (choose! cs (ask-title ctx request) ask-choices
                  {:signal (:signal request)
                   :abandon permissions/cancelled-answer}))
        (finally (tui/tui-remove-input-listener tui listener))))
    {:decision :deny :reason "no terminal is running — nobody can ask"}))

;; ─── Run ───────────────────────────────────────────────────────────────────

(defn run
  "Start the interactive TUI with the given config and CLI opts.
   Loads extensions, resolves the session (:resume/:continue/new), builds the
   layout, and runs the TUI loop until quit. Releases the mode when it
   ends: tracked child processes, the TUI on error, and (finally, on every
   path) the mode teardown — tree disposal, runtime sinks, theme
   callback/watcher, reakt queue — then the extension UI registry, which
   closes over the whole mode.
   Error paths rethrow for the top-level handler.

   CTX is the ctx the engine runs on (see `build-layout`) and is required: an
   entry names the tree the run reads its tool table and system prompt from —
   kmet1's entry its process root, `kmet --profile tui` the
   composition it booted. There is no process-root default: the decision
   belongs to the entry, not to a library that would have to reach up for it.
   pi: cli.js dispatch to interactive mode."
  ([config opts ctx]
   (let [tui-ref (atom nil)
         cs-ref (atom nil)]
     (try
      ;; Extensions were loaded before dispatch (core/-main, pi: extension
      ;; discovery before model resolution); /reload re-loads them.

      ;; Apply command-line overrides
       (let [config (cfg/apply-cli-overrides config opts)
             session (cond
                       (:session opts)
                       (let [base-dir (cfg/get-session-dir config)
                             path (session/resolve-session-path (:session opts) base-dir)]
                         (if path
                           (session/load-session path)
                           (throw (ex-info (str "No session found matching '" (:session opts) "'")
                                           {:type :kmet.app/unknown-session
                                            :session (:session opts)}))))
                       (:resume opts) nil
                       (:continue opts) (if-let [path (state/find-session config)]
                                         ;; find-session returns the session
                                         ;; file path — load it into a Session
                                         ;; record so the context and chat can
                                         ;; be restored below
                                          (session/load-session path)
                                         ;; pi: continueRecent — no session to
                                         ;; continue → start a fresh one
                                          (session/create-session (state/ensure-cwd-session-dir config (str (fs/cwd)))))
                       :else (session/create-session (state/ensure-cwd-session-dir config (str (fs/cwd)))))
             cs (layout/build-layout config session ctx opts)]
         (reset! cs-ref cs)
         (reset! live-mode cs)
         (reset! tui-ref (:tui cs))
         (when (:resume opts)
           (show-session-selector cs (fn [] (state/ensure-session-dir (runtime/config (:engine cs))))
                                  (fn [path]
                                   ;; pi: emitBeforeSwitch (reason :resume) —
                                   ;; extensions may cancel the switch
                                    (let [result ((:resume-session! (:engine cs)) path)]
                                      (when-not (:cancelled result)
                                        (let [sess (:session result)
                                              short-id (subs (:id sess) 0 (min 8 (count (:id sess))))]
                                          (session-admin/install-live-session! cs)
                                          (session-admin/replay-session! cs sess true)
                                          (chat-history/chat-history-add-message! (:chat-history cs)
                                                                                  {:role :assistant
                                                                                   :content (str "Resumed session " short-id ".")})
                                          (tui/tui-request-render (:tui cs))))))))
        ;; start the UI before initializing extensions so session_start
        ;; handlers can use interactive dialogs — kmet loads extensions
        ;; earlier, so the event fires once the layout + UI registry are
        ;; live and the render loop is running (the future waits for it).
         (future
           (try
             (loop []
               (when-not (or @(:running? (:tui cs))
                             @(:stopped? (:tui cs)))
                 (Thread/sleep 20)
                 (recur)))
            ;; skipped entirely when the TUI already stopped (immediate quit)
             (when @(:running? (:tui cs))
               (events/emit! (runtime/agent-ctx (:engine cs))
                             {:type :session-start
                              :reason (cond (:resume opts) :resume
                                            (:continue opts) :continue
                                            :else :new)})
              ;; pi: resources_discover fires after session_start (reason
              ;; startup for non-reload session starts)
               (extensions/discover-resources! (runtime/agent-ctx (:engine cs)) :startup))
             (catch Exception e
               (debug/log "session-start: " e))))
        ;; Theme detection + application (pi: startup applyFromSettings) —
        ;; waits for the render loop so OSC 11 / color-scheme responses are
        ;; consumed by the input path.
         (future
           (try
             (loop []
               (when-not (or @(:running? (:tui cs))
                             @(:stopped? (:tui cs)))
                 (Thread/sleep 20)
                 (recur)))
             (when @(:running? (:tui cs))
               (theme-ctrl/apply-from-settings! (:theme-controller cs)))
             (catch Exception e
               (debug/log "theme detection: " e))))
        ;; Set the initial terminal title (pi: updateTerminalTitle in init —
        ;; after ui.start). Waits until the backend reports started? (raw mode
        ;; entered; a write before that could interleave with the pre-TUI
        ;; terminal state); --continue/--resume sessions restored in
        ;; build-layout get their display name reflected here.
         (future
           (try
             (loop []
               (when-not (or @(:running? (:tui cs))
                             @(:stopped? (:tui cs)))
                 (Thread/sleep 20)
                 (recur)))
             (when @(:running? (:tui cs))
               (loop []
                 (when (and @(:running? (:tui cs))
                            (not (term/started? @(:terminal (:tui cs)))))
                   (Thread/sleep 20)
                   (recur)))
               (when @(:running? (:tui cs))
                 (state/update-terminal-title! cs)))
             (catch Exception e
               (debug/log "terminal title: " e))))
         (tui/tui-start (:tui cs))
         (process/kill-tracked-children!)
         (when-let [resume (state/format-resume-command (runtime/session (:engine cs)) config)]
           (println (str (th/dim "To resume this session:") " " resume)))
         (:tui cs))
       (catch Exception e
        ;; Restore terminal if TUI was started, then rethrow for -main
         (process/kill-tracked-children!)
         (when-let [t @tui-ref]
           (try (tui/tui-stop t) (catch Exception _)))
         (throw e))
       (finally
         (reset! live-mode nil)
        ;; an approval or dialog that is still up can never be answered once the
        ;; terminal is gone: deny it rather than park its caller forever
         (doseq [p @pending-answers]
           (deliver p nil))
        ;; A stopped mode must be fully released: the tree, the runtime
        ;; sinks, the theme callback/watcher and the reakt queue
        ;; (teardown-mode! — findings 2 and 10), then the extension UI
        ;; binding, which closes over the whole mode. The CLI exits right
        ;; after this, but embedded hosts and tests keep going.
         (when-let [cs @cs-ref]
           (teardown-mode! cs)
           (when (:debug opts)
             (log-survivors!)))
         (extensions/unbind-extensions!))))))