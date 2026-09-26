(ns kmet.modes.interactive.session-admin
  "Session administration for the interactive mode: the message/entry
   renderers, resume + import, the session tree, and fork/clone."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.debug :as debug]
            [kmet.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as th]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.container :as container]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.bash-execution :as be]
            [kmet.app.ui.dialogs :as dialogs]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.footer :as footer]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.app.ui.image-block :as image-block]
            [kmet.app.ui.status-indicator :as status-indicator]
            [kmet.app.ui.tool-execution :as tool-execution]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.extensions :as extensions]
            [kmet.app.ui.external-editor :refer [editor-text-get editor-text-set!]]
            [kmet.app.ui.model-selector :refer [sync-footer-model!]]
            [kmet.app.ui.tree-selector :refer [show-session-tree]]
            [kmet.app.skills :as skills]
            [kmet.ai.usage :as usage]
            [kmet.modes.interactive.state :as state]
            [kmet.modes.interactive.status :as status]))

;; Forward reference inside this namespace (the tree's custom-summary path).
(declare ask-branch-summary)

(defn parse-path-argument
  "Pi: getPathCommandArgument — strip surrounding quotes, else take the
   first whitespace-delimited token. Returns nil when there is no argument —
   a quoted but empty argument (`\"\"`) counts as none, so /export falls back
   to its default path and /import to its usage line. Shared by /export and
   /import."
  [args]
  (let [args (str/trim args)]
    (when (seq args)
      (let [first-char (first args)
            token (if (contains? #{\" \'} first-char)
                    (when-let [end (str/index-of args first-char 1)]
                      (subs args 1 end))
                    (let [ws (str/index-of args " ")]
                      (if ws (subs args 0 ws) args)))]
        (when (seq (str/trim (str token))) token)))))

;; ─── Resume session ────────────────────────────────────────────────────────

(defn custom-message-text
  "Plain text of a :custom message's content (string or text blocks) — the
   display text for custom_message entries/messages in the TUI (pi renders
   custom messages as labeled info boxes)."
  [m]
  (if (string? (:content m))
    (:content m)
    (str/join (for [b (:content m)
                    :when (= :text (:type b))]
                (:text b)))))

(defn run-message-renderer
  "Run a registered extension message renderer for M. Returns the renderer's
   result (a chat message map, a bare component, or nil). A throwing or
   nil-returning renderer falls through to the default labeled box (pi:
   CustomMessageComponent rebuild catches renderer errors and keeps the
   default rendering when the renderer produces nothing)."
  [renderer m]
  (when renderer
    (try (renderer m) (catch Exception _ nil))))

(defn run-entry-renderer
  "Run a registered extension entry renderer for ENTRY. Returns the
   renderer's result (a chat message map, a bare component, or nil). A
   throwing renderer yields a visible error line naming the custom type
   (pi: CustomEntryComponent rebuild catches renderer errors and renders
   `[type] renderer failed: message`)."
  [renderer entry]
  (when renderer
    (try (renderer entry)
         (catch Exception e
           (let [ct (or (:custom-type entry) "custom")]
             {:role :notice
              :style :error
              :content (str "[" (if (keyword? ct) (name ct) (str ct))
                            "] renderer failed: "
                            (or (ex-message e) (str (class e))))})))))

(defn renderer-result->message
  "Normalize an extension renderer result for chat-history-add-message!:
   a plain message map passes through, a bare component is wrapped as
   {:component c}. Components are records and records satisfy map?, so a
   plain map? test would misclassify every component as a message map."
  [rendered]
  (if (and (map? rendered) (not (record? rendered)))
    rendered
    {:component rendered}))

(defn compaction-cost-notice
  "pi: addCompactionCostNotice — the warning line appended after a
   compaction / branch summary whose summarization call recorded usage
   (entry :usage). Gated on :show-cache-miss-notices, like the assistant
   cache-miss notice. Returns the message map to append, or nil."
  [config variant usage]
  (when (and (cfg/get-show-cache-miss-notices config) usage)
    (when-let [u (usage/entry-usage usage)]
      (let [tokens (+ (long (or (:input u) 0)) (long (or (:output u) 0))
                      (long (or (:cache-read u) 0)) (long (or (:cache-write u) 0)))
            cost (:cost u)]
        (when (pos? tokens)
          {:role :notice
           :style :warning
           :content (str (case variant
                           :compaction "Compaction"
                           :branch-summary "Branch summary"
                           "Summary")
                         ": " (footer/format-tokens tokens) " tokens billed"
                         (when (and cost (>= (double cost) 0.01))
                           (str " (~$" (format "%.2f" (double cost)) ")")))})))))

(defn- replay-branch!
  "Replay a session's compaction-aware context into the chat history (pi:
   renderInitialMessages — buildContextEntries, so summarized history is
   not re-rendered; only message entries; session_info, label and
   model/thinking change entries are metadata and never rendered; custom
   (extension state) entries render when an extension registered a renderer
   for their custom-type; compaction and branch_summary entries render as
   dedicated collapsible summary boxes; custom_message entries render as
   labeled info boxes when their display flag is set). Tool results replay
   as ToolExecutionComponents created from the assistant entry's
   :tool-calls (name + args) and filled by the matching :tool result entry
   by tool-call id (pi: renderedPendingTools); a result without a matching
   call renders standalone from its own :tool-name; !/!! bash entries replay
   as completed BashExecutionComponents (pi: addMessageToChat case
   bashExecution)."
  [cs sess]
  (chat-history/chat-history-clear! (:chat-history cs))
  (let [content-of
        (fn [e]
          (str/join
           (keep (fn [b]
                   (case (:type b)
                     :text (:text b)
                     :tool_result (:content b)
                     nil))
                 (:content e))))
        ;; Pi: renderedPendingTools — tool-call id → ToolExecutionComponent
        ;; created from the assistant message's tool calls, filled by the
        ;; matching tool-result entry (results can arrive out of order with
        ;; parallel tools).
        pending-tools (atom {})]
    (doseq [e (session/build-context sess)
            :when (not (contains? #{:session_info :label :model-change
                                    :thinking-level-change} (:role e)))]
      (let [role (:role e)]
        (cond
          (= role :custom)
          ;; extension state entries render only with a registered renderer
          ;; (pi: registerEntryRenderer + CustomEntryComponent); a renderer
          ;; failure renders the pi error line instead of crashing the replay
          (when-let [rendered (run-entry-renderer
                               (extensions/get-entry-renderer (:custom-type e)) e)]
            (chat-history/chat-history-add-message! (:chat-history cs)
                                                    (renderer-result->message rendered)))

          (= role :custom-message)
          ;; extension custom messages render only when display is set (pi:
          ;; the display flag controls TUI rendering); content may be a string
          ;; or a block vector (pi CustomMessageEntry). A registered message
          ;; renderer overrides the default labeled info box.
          (when (:display e)
            (chat-history/chat-history-add-message!
             (:chat-history cs)
             (if-let [rendered (run-message-renderer
                                (extensions/get-message-renderer (:custom-type e)) e)]
               (renderer-result->message rendered)
               {:role :info
                :content (custom-message-text e)
                :images (image-block/content-images (:content e))
                :label (:custom-type e)})))

          (= role :assistant)
          (do
            (chat-history/chat-history-add-message! (:chat-history cs)
                                                    (cond-> {:role role :content (content-of e)}
                                                      (= role :assistant) (assoc :thinking (:thinking e)
                                                                       ;; replayed tool-call-only
                                                                       ;; messages render no
                                                                       ;; '(no response)' bubble
                                                                                 :tool-calls (:tool-calls e))))
            ;; Pi: create a ToolExecutionComponent per tool call declared in
            ;; the assistant message (name + args from the call — the same
            ;; fields the live :tool-execution-start event carries), then
            ;; match the following tool-result entries by tool-call id. The
            ;; tool entries themselves only store the pi-faithful
            ;; :tool-name/:content — the call line (args) lives here.
            ;; Tool calls inside an errored/aborted message get the failure
            ;; text as their result instead of waiting for a result that
            ;; never came (pi: renderInitialMessages updateResult error).
            (let [errored? (contains? #{:error :aborted} (:stop-reason e))]
              (doseq [tc (:tool-calls e)]
                (when-let [comp (chat-history/chat-history-add-message!
                                 (:chat-history cs)
                                 {:role :tool
                                  :name (:name tc)
                                  :args (:arguments tc)
                                  :content ""
                                  :is-error false})]
                  (reset! (:tool-call-id-atom comp) (:id tc))
                  (tool-execution/tool-execution-set-args-complete! comp)
                  (if errored?
                    (do (reset! (:content-atom comp)
                                (or (:error-message e)
                                    (if (= :aborted (:stop-reason e))
                                      "Aborted"
                                      "Error")))
                        (tool-execution/tool-execution-set-error! comp true))
                    (swap! pending-tools assoc (:id tc) comp))))))

          (= role :tool)
          (let [tc-id (some (fn [b] (when (= :tool_result (:type b))
                                      (:tool_use_id b)))
                            (:content e))]
            (if-let [comp (get @pending-tools tc-id)]
              ;; matched result — fill the pending call component (pi:
              ;; updateResult by toolCallId)
              (do (reset! (:content-atom comp) (content-of e))
                  (tool-execution/tool-execution-set-error! comp (:is-error e false))
                  (when-let [truncation (:truncation e)]
                    (reset! (:truncation-atom comp) truncation))
                  (when-let [details (:details e)]
                    (reset! (:details-atom comp) details))
                  (when-let [images (:images e)]
                    (tool-execution/tool-execution-set-images! comp images))
                  (swap! pending-tools dissoc tc-id))
              ;; unpaired result (no matching tool call in the branch —
              ;; legacy sessions, extension tools) — standalone component
              ;; from the entry's own fields
              (chat-history/chat-history-add-message!
               (:chat-history cs)
               (cond-> {:role :tool
                        :content (content-of e)
                        :name (or (:tool-name e) (:name e) "tool")
                        :is-error (:is-error e false)
                        :truncation (:truncation e)
                        :details (:details e)}
                 ;; image results carry their blocks on the entry (pi:
                 ;; toolResult content blocks) — the callback component
                 ;; renders them like a matched result's would
                 (seq (:images e)) (assoc :images (:images e))))))

          (= role :bash)
          ;; pi: addMessageToChat case "bashExecution" — !/!! replays as a
          ;; COMPLETED BashExecutionComponent (no spinner, no duration)
          (let [comp (be/make-bash-execution
                      :command (:command e)
                      :exclude-from-context? (:exclude-from-context? e)
                      :replayed? true
                      :tools-expanded-atom (:tools-expanded-atom (:chat-history cs)))]
            (when (seq (:output e))
              (be/bash-execution-append-output! comp (:output e)))
            (be/bash-execution-set-complete! comp (:exit-code e) (:cancelled e false)
                                             :truncation (when (:truncated e) {:truncated true})
                                             :full-output-path (:full-output-path e))
            (chat-history/chat-history-add-message! (:chat-history cs)
                                                    {:role :bash :command (:command e) :component comp}))

          ;; pi: CompactionSummaryMessageComponent / BranchSummary — dedicated
          ;; collapsible summary boxes; a usage-carrying summary appends the
          ;; billing notice when :show-cache-miss-notices is on
          (contains? #{:compaction :branch-summary} role)
          (do
            (chat-history/chat-history-add-message!
             (:chat-history cs)
             (cond-> {:role role :summary (:summary e) :tokens-before (:tokens-before e)}
               (:usage e) (assoc :usage (:usage e))))
            (when-let [notice (compaction-cost-notice (:config cs) role (:usage e))]
              (chat-history/chat-history-add-message! (:chat-history cs) notice)))

          :else
          (chat-history/chat-history-add-message!
           (:chat-history cs)
           (cond-> {:role role :content (content-of e)}
             ;; user-attached images replay too — content-of keeps only the
             ;; text, so the image blocks must ride the message's :images
             ;; (the live path carries them inside :content)
             (= role :user) (assoc :images (image-block/content-images (:content e)))
             (= role :info) (assoc :label (:label e)))))))))

(defn- refresh-prompt-cwd!
  "Point the system prompt's `Current working directory` line at CWD (the
   session's runtime cwd). Project context files are NOT re-read: everything
   project-scoped — settings.edn, context files (AGENTS.md/CLAUDE.md),
   project extensions/skills/prompts/themes, project packages — stays with
   the launch directory, whatever session is imported; only the transcript
   (and the working directory its tools run in) crosses the switch."
  [cs cwd]
  (let [ag @(:agent-state cs)
        opts (assoc @(:system-prompt-opts ag) :cwd cwd)]
    (reset! (:system-prompt-opts ag) opts)
    (reset! (:system ag) (apply skills/build-system-prompt (mapcat identity opts)))))

(defn- apply-session-cwd!
  "Point the runtime at SESS's working directory (pi: createRuntime's cwd +
   footerDataProvider.setCwd — a resumed or imported session brings its
   recorded cwd): the footer pwd, the extension ctx.cwd, tool components and
   the tools' relative-path resolution all read the footer provider's cwd,
   and the system prompt is rebuilt for the new directory. A recorded cwd
   that no longer exists keeps the current one (pi asks via
   MissingSessionCwdError; kmet says so and continues). Returns the cwd in
   effect.

   The session's *configuration* does not move with it: settings.edn,
   project context files, project-scoped extensions/skills/prompts/themes and
   project packages stay resolved against the launch directory — a switch
   imports the session's transcript and working directory only."
  [cs sess]
  (let [fdp* (:footer-provider cs)
        recorded (get-in sess [:header :cwd])
        cwd (session/session-cwd sess)
        current (when fdp* (fdp/fdp-get-cwd fdp*))
        ;; the comparison is spelling-insensitive: session-cwd is normalized
        ;; (expand-home → absolutize → normalize) while the atom may hold the
        ;; process cwd's spelling, so a symlinked launch dir is not a switch
        same? (and cwd current
                   (= cwd (str (fs/normalize (str current)))))]
    (cond
      (or (nil? fdp*) same?)
      current

      cwd
      (do (fdp/fdp-set-cwd! fdp* cwd)
          (refresh-prompt-cwd! cs cwd)
          cwd)

      :else
      (do (chat-history/chat-history-show-status!
           (:chat-history cs)
           (str "Session cwd " recorded " no longer exists — continuing in " current))
          current))))

(defn restore-session!
  "Restore a session into the UI and the agent: swap the active session,
   rebuild the agent's in-memory context from the session branch (pi: the
   session is the source of truth — buildContextEntries; steered and
   follow-up user messages live in the branch and must come back for the
   next LLM call), and replay the branch into the chat history. session_info
   entries are metadata — never rendered (pi: only message entries are
   replayed on resume). When APPLY-SETTINGS? is true (startup resume,
   /resume — pi: createAgentSession), the session-derived model/thinking are
   applied to the agent and the footer refreshes; fork and clone pass false
   (pi: navigateTree keeps the current agent state)."
  [cs sess apply-settings?]
  (reset! (:session-atom cs) sess)
  (extensions/set-session! sess)
  ;; pi: renderCurrentSessionState — a session switch drops the compaction
  ;; queue (compactionQueuedMessages = [])
  (reset! (:compaction-queued cs) [])
  (let [new-ag (assoc @(:agent-state cs) :session sess)]
    (reset! (:agent-state cs) new-ag))
  (agent/restore-session-context! @(:agent-state cs))
  (when apply-settings?
    (agent/apply-session-settings! @(:agent-state cs))
    (sync-footer-model! cs))
  (replay-branch! cs sess)
  ;; pi: renderInitialMessages — "Session compacted N times" status when
  ;; the session file (any branch) contains compactions
  (let [n (count (filter #(= :compaction (:role %)) @(:entries sess)))]
    (when (pos? n)
      (chat-history/chat-history-show-status!
       (:chat-history cs)
       (str "Session compacted " n (if (= n 1) " time" " times")))))
  ;; pi: createRuntime's cwd + footerDataProvider.setCwd — the session's
  ;; working directory becomes the runtime's (tools, the prompt's cwd line,
  ;; the footer pwd and ctx.cwd all follow it)
  (apply-session-cwd! cs sess)
  ;; Repopulate the editor's prompt history only on the resume paths
  ;; (startup --continue, /resume — pi: renderInitialMessages with
  ;; populateHistory). Fork/clone keep the shared editor's existing history
  ;; (pi: navigateTree/switchSession reuse the same editor instance).
  (when apply-settings?
    (editor/editor-set-history! (:editor cs) (session/get-prompt-history sess)))
  (state/update-footer! cs)
  (state/update-terminal-title! cs))

(defn handle-new-session
  "Pi: handleClearCommand → runtimeHost.newSession. Fully reset the
   conversation: settle the in-flight run and compaction first so the
   aborted turn (incl. pending bash results) persists to the OUTGOING
   session (pi: teardownCurrent awaits session.abort), then swap in a fresh
   session, rebuild the agent's in-memory context from it — empty (pi:
   createRuntime; the session is the source of truth) — reset per-run
   state, and clear the chat, pending container, and editor (pi:
   editor.setText(\"\")). Emits :session-before-switch (extensions may
   cancel), :session-shutdown (reason :new, target-session-file) before the
   swap and :session-start (reason :new, previous-session-file) after it
   for extensions (pi: teardownCurrent → session_start on every switch)."
  [cs]
  (let [previous-file (:file @(:session-atom cs))
        ;; pi: emitBeforeSwitch — extensions may cancel the switch; the
        ;; conversation stays completely untouched
        switch-result (event-bus/emit-event! {:type :session-before-switch
                                              :reason :new
                                              :target-session-file previous-file})]
    (when-not (:cancel switch-result)
      (let [ag @(:agent-state cs)
            was-running @(:running-turn? cs)]
        ;; Settle in-flight work so it lands in the outgoing session and cannot
        ;; race the swap. Queued steering/follow-up are dropped — a new session
        ;; discards them (unlike cancel, which restores them to the editor).
        (when was-running
          (agent/cancel-turn ag))
        (when @(:compacting? ag)
          (reset! (:signal ag) true))
        (when @(:bash-running? cs)
          (reset! (:bash-signal cs) true)
          (reset! (:bash-running? cs) false))
        ;; Wait (bounded) for the cancelled run's finally to drain pending bash
        ;; results and for an in-flight compaction to settle — its context sync
        ;; must not run after the swap.
        (let [deadline (+ (System/currentTimeMillis) 3000)]
          (loop []
            (when (and (or (seq @(:pending-bash ag)) @(:compacting? ag))
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 10)
              (recur))))
        (when (and (not @(:compacting? ag))
                   (empty? @(:pending-bash ag)))
          (reset! (:signal ag) false))
        (when was-running
          (reset! (:running-turn? cs) false)
          (status/stop-anim-timer! cs)
          (status/clear-status-indicator! cs))
        ;; pi: teardownCurrent — after the run is settled, tell extensions the
        ;; runtime is being torn down (reason :new, destination session file)
        ;; so they can persist state before the swap.
        (event-bus/emit-event! {:type :session-shutdown :reason :new
                                :target-session-file previous-file})
        (let [new-session (session/create-session (state/ensure-cwd-session-dir (state/runtime-cwd cs))
                                                  ;; the new session belongs to the runtime's
                                                  ;; project (pi: newSession keeps this.cwd)
                                                  {:cwd (state/runtime-cwd cs)})]
          (debug/log "new session created: " (:id new-session))
          (chat-history/chat-history-clear! (:chat-history cs))
          (be/dispose-pending-bash! @(:pending-bash-components cs))
          (container/container-clear (:pending-messages-container cs))
          ;; pi: a session switch drops the compaction queue
          ;; (compactionQueuedMessages = [])
          (reset! (:compaction-queued cs) [])
          ;; Re-attach the PendingMessages component — container-clear removes
          ;; every child (queued steering/follow-up display included), and the
          ;; display must keep rendering for messages queued after /new
          (when-let [pm (:pending-messages-comp cs)]
            (container/container-add-child (:pending-messages-container cs) pm))
          (reset! (:pending-bash-components cs) [])
          (editor-text-set! @(:current-editor-atom cs) "")
          (reset! (:session-atom cs) new-session)
          (extensions/set-session! new-session)
          (let [new-ag (assoc ag :session new-session)]
            (reset! (:agent-state cs) new-ag)
            ;; Rebuild the in-memory context from the new session — empty — and
            ;; reset per-run state (pi: createRuntime builds a fresh agent
            ;; state; the hook/config atoms are kept — not session state).
            (agent/restore-session-context! new-ag)
            (reset! (:status ag) :idle)
            (reset! (:steering ag) [])
            (reset! (:follow-up ag) [])
            (reset! (:pending-bash ag) [])
            (reset! (:overflow-recovered ag) false)
            (reset! (:retry-count ag) 0))
      ;; pi: newSession emits session_start (reason "new",
      ;; previousSessionFile) — on a future, handlers may block on dialog
      ;; promises (same as /reload).
          (future
            (try
              (event-bus/emit-event!
               (cond-> {:type :session-start :reason :new}
                 previous-file (assoc :previous-session-file previous-file)))
              ;; pi: resources_discover fires after session_start (reason
              ;; startup for non-reload session starts)
              (extensions/discover-resources! :startup)
              (catch Exception e (debug/log "session-start: " e))))
          (state/update-footer! cs)
          (state/update-terminal-title! cs)
          (tui/tui-request-render (:tui cs))
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant :content "Started a new session."}))))))

;; ─── Import (/import — pi: handleImportCommand) ───────────────────────────

(defn- import-error!
  "pi: showError — the Error: line in the transcript."
  [chat message]
  (chat-history/chat-history-add-message! chat {:role :error :content message}))

(defn- import-failed!
  "pi: the catch in handleImportCommand — showError with pi's prefix."
  [chat e]
  (import-error! chat (str "Failed to import session: "
                           (or (ex-message e) (str e)))))

(defn- show-import-confirm!
  "pi: showExtensionConfirm — ask through a dock-mounted Yes/No selector
   (pi: showExtensionSelector of [\"Yes\" \"No\"]): TITLE with MESSAGE as
   its second header line. ON-CONFIRM runs on Yes; No or escape shows pi's
   \"Import cancelled\" status."
  [cs title message on-confirm]
  (let [tui (:tui cs)
        chat (:chat-history cs)
        ;; late binding: the callbacks reach the mount's done through this
        ;; atom (pi: done() is created by showSelector)
        sel-atom (atom nil)
        ;; late binding: close! disposes the dialog it was built for
        dlg-atom (atom nil)
        close! (fn []
                 ((:done @sel-atom))
                 ;; the dock leaves foreign records alone — the owner disposes
                 ;; (the frame's dispose cascades to its chrome + the list)
                 (when-let [dlg @dlg-atom]
                   (protocols/dispose dlg))
                 (tui/tui-request-render tui))
        cancelled! (fn []
                     (chat-history/chat-history-show-status! chat "Import cancelled")
                     (tui/tui-request-render tui))
        dlg (dialogs/make-selector-dialog
             (str title "\n" message)
             ["Yes" "No"]
             (fn [choice]
               (close!)
               (if (= "Yes" choice) (on-confirm) (cancelled!)))
             (fn [] (close!) (cancelled!))
             (th/get-current-theme))]
    (reset! dlg-atom dlg)
    ;; pi: showSelector — the selector replaces the editor dock
    (reset! sel-atom {:done (dock/mount! cs dlg)})
    (tui/tui-request-render tui)))

(defn handle-import-command
  "pi: handleImportCommand — copy a session file into the active session's
   directory and resume it. The path may be quoted, trailing arguments are
   ignored (pi: getPathCommandArgument, shared with /export). kmet sessions
   are EDN and pi imports its JSONL, so the file must open with a :session
   header. Written against kmet's session-switch path rather than pi's
   teardownCurrent: an in-flight turn refuses the import (like /fork and
   /clone — the switch path does not abort) and the switch emits
   :session-before-switch, exactly as /resume does. The session's recorded
   cwd becomes the runtime cwd (restore-session! → apply-session-cwd!), so
   the tools, the prompt's cwd line and the footer follow it, as in pi's
   createRuntime."
  [cs args]
  (let [chat (:chat-history cs)
        path-arg (parse-path-argument args)]
    (cond
      (nil? path-arg)
      (import-error! chat "Usage: /import <path>")

      (state/turn-running? cs)
      (chat-history/chat-history-add-message! chat
                                              {:role :assistant
                                               :content "Wait for the current response to finish before importing."})

      :else
      (let [sess @(:session-atom cs)
            ;; pi: this.session.sessionManager.getSessionDir() — the active
            ;; session's directory; a not-yet-persisted session still carries
            ;; its computed :file (lazy creation)
            dest-dir (or (some-> (:file sess) fs/parent str)
                         (state/ensure-cwd-session-dir))]
        (try
          (let [plan (session/plan-session-import path-arg dest-dir)]
            (show-import-confirm!
             cs "Import session"
             (str "Replace current session with " (:source plan) "?")
             (fn []
               (try
                 ;; pi: emitBeforeSwitch (reason :resume) — extensions may
                 ;; cancel; the copy is still pending there, so a cancelled
                 ;; import leaves no trace
                 (if (:cancel (event-bus/emit-event!
                               {:type :session-before-switch
                                :reason :resume
                                :target-session-file (:path plan)}))
                   (chat-history/chat-history-show-status! chat "Import cancelled")
                   (do (session/copy-imported-session! plan)
                       (restore-session! cs (session/load-session (:path plan)) true)
                       (chat-history/chat-history-show-status!
                        chat (str "Session imported from: " (:source plan)))
                       (tui/tui-request-render (:tui cs))))
                 (catch Exception e
                   (import-failed! chat e))))))
          (catch Exception e
            (import-failed! chat e)))))))

;; ─── Session tree navigation (pi: TreeSelectorComponent) ──────────────────

(defn- session-entry-text
  "Plain trimmed text of a session entry's content blocks (pi:
   extractUserMessageText — used for fork/tree editor restore)."
  [e]
  (let [content (:content e)]
    (if (string? content)
      (str/trim content)
      (str/trim (str/join (map :text (filter #(= :text (:type %)) content)))))))

(defn- complete-tree-navigation!
  "Apply a tree navigation (pi: navigateTree tail): branch the session leaf
   (with an optional branch summary), rebuild the agent context and chat
   history from the new branch, restore USER-MSG-TEXT into the editor when
   navigating to a user message (only when the editor is empty), attach
   LABEL to the summary/target entry when given, and emit :session-tree.
   SUMMARY-RESULT is nil or {:summary str :usage usage-map} (the usage of
   the summarization call, recorded on the branch-summary entry — pi:
   BranchSummaryEntry.usage)."
  [cs sess old-leaf target-leaf summary-result user-msg-text from-extension? label]
  (try
    ;; pi: renderCurrentSessionState on session rebind — the compaction
    ;; queue does not survive navigation
    (reset! (:compaction-queued cs) [])
    (let [summary-entry (if summary-result
                          (session/branch-with-summary!
                           sess target-leaf (:summary summary-result)
                           (when (:usage summary-result)
                             {:usage (:usage summary-result)}))
                          (do (if (nil? target-leaf)
                                (session/reset-leaf! sess)
                                (session/branch! sess target-leaf))
                              nil))
          label-target (if summary-entry (:id summary-entry) target-leaf)]
      (when (and label label-target)
        (session/set-label! sess label-target label))
      (agent/restore-session-context! @(:agent-state cs))
      (replay-branch! cs sess)
      ;; pi: restore the user message only when the editor is empty — a draft
      ;; the user is composing is not clobbered by navigation
      (when (and user-msg-text
                 (str/blank? (editor-text-get (:editor cs))))
        (editor-text-set! (:editor cs) user-msg-text))
      (state/update-footer! cs)
      (event-bus/emit-event!
       (cond-> {:type :session-tree
                :new-leaf-id @(:leaf-id sess)
                :old-leaf-id old-leaf
                :from-extension? (boolean from-extension?)}
         summary-entry (assoc :summary-entry summary-entry)))
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant
                                               :content (if summary-result
                                                          "Navigated to the selected point (branch summarized)."
                                                          "Navigated to the selected point.")})
      (tui/tui-request-render (:tui cs)))
    (catch Exception e
      (debug/log "tree navigation failed: " e)
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :info :label "Tree"
                                               :content (str "Navigation failed: " (ex-message e))})
      (tui/tui-request-render (:tui cs)))))

(defn- branch-summarize-and-apply!
  "Run the LLM branch summarization (pi: navigateTree summarize) with the
   BranchSummaryStatusIndicator and editor-escape abort, then branch with
   the summary. On abort/failure the branch is unchanged. PREP is the
   :session-before-tree preparation map (now carrying the effective :label
   and :replace-instructions after :session-before-tree overrides); ABORT-ATOM
   cancels the call."
  [cs sess old-leaf target-leaf user-msg-text prep abort-atom
   custom-instructions replace-instructions?]
  (let [ag @(:agent-state cs)
        ed (:editor cs)
        prev-interrupt (get @(:action-handlers ed) "app.interrupt")
        indicator (status-indicator/make-branch-summary-status-indicator)
        done (promise)]
    ;; escape → abort (pi: defaultEditor.onEscape = abortBranchSummary)
    (editor/editor-set-on-action! ed "app.interrupt"
                                  (fn [] (reset! abort-atom true)))
    (status/show-status-indicator! cs :branch-summary indicator)
    (tui/tui-request-render (:tui cs))
    ;; render driver: tick the indicator while the summarization runs
    (future
      (while (not (realized? done))
        (Thread/sleep 100)
        (tui/tui-request-render (:tui cs))))
    (future
      (try
        (deliver done (agent/generate-branch-summary
                       ag (:entries-to-summarize prep) custom-instructions
                       abort-atom replace-instructions?))
        (catch Exception e
          (debug/log "branch summarization failed: " e)
          (deliver done nil))))
    (future
      (let [result (deref done 120000 :timeout)]
        (editor/editor-set-on-action! ed "app.interrupt" prev-interrupt)
        (status/release-background-status! cs :branch-summary indicator)
        (cond
          (= result :timeout)
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :info :label "Tree"
                                                   :content "Branch summarization timed out — branch unchanged."})

          (nil? result)
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :info :label "Tree"
                                                   :content "Branch summarization failed — branch unchanged."})

          (:aborted result)
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :info :label "Tree"
                                                   :content "Branch summarization cancelled — branch unchanged."})

          :else
          (complete-tree-navigation! cs sess old-leaf target-leaf
                                     result user-msg-text false
                                     (:label prep)))
        (tui/tui-request-render (:tui cs))))))

(defn navigate-tree!
  "Branch the session to the selected tree entry (pi: navigateTree):
   selecting a user message re-opens it in the editor (leaf = its parent),
   any other entry becomes the new leaf. Emits :session-before-tree
   (extensions may cancel, supply the summary, or override
   custom-instructions/replace-instructions/label), optionally summarizes
   the abandoned path, branches, and emits :session-tree."
  [cs sess entry wants-summary custom-instructions replace-instructions? label]
  (let [old-leaf @(:leaf-id sess)
        target-leaf (if (= :user (:role entry)) (:parent-id entry) (:id entry))
        entries (session/branch-summary-entries sess old-leaf (:id entry))
        user-msg-text (when (= :user (:role entry)) (session/session-entry-text entry))
        abort-atom (atom false)
        prep {:target-id (:id entry)
              :old-leaf-id old-leaf
              :common-ancestor-id (session/common-ancestor-id sess old-leaf (:id entry))
              :entries-to-summarize entries
              :user-wants-summary (boolean wants-summary)
              :custom-instructions custom-instructions
              :replace-instructions (boolean replace-instructions?)
              :label label}
        ext-result (event-bus/emit-event! {:type :session-before-tree
                                           :preparation prep
                                           :signal abort-atom})
        custom-instructions (or (:custom-instructions ext-result) custom-instructions)
        replace-instructions? (if (contains? (or ext-result {}) :replace-instructions)
                                (:replace-instructions ext-result)
                                replace-instructions?)
        effective-label (if (contains? (or ext-result {}) :label)
                          (:label ext-result)
                          label)
        prep (assoc prep
                    :custom-instructions custom-instructions
                    :replace-instructions replace-instructions?
                    :label effective-label)]
    (cond
      (and wants-summary (empty? entries))
      ;; nothing abandoned to summarize — branch without a summary
      (complete-tree-navigation! cs sess old-leaf target-leaf nil user-msg-text
                                 false effective-label)

      (:cancel ext-result)
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :info :label "Tree"
                                               :content "Navigation cancelled by an extension."})

      (and wants-summary (:summary ext-result))
      (complete-tree-navigation! cs sess old-leaf target-leaf
                                 (when-let [s (:summary ext-result)]
                                   {:summary s :usage (:usage ext-result)})
                                 user-msg-text true
                                 effective-label)

      (not wants-summary)
      (complete-tree-navigation! cs sess old-leaf target-leaf nil user-msg-text
                                 false effective-label)

      :else
      (branch-summarize-and-apply! cs sess old-leaf target-leaf user-msg-text
                                   prep abort-atom custom-instructions
                                   replace-instructions?))))

(defn- prompt-custom-summary!
  "Ask for custom summarization instructions, then navigate with them
   (pi: showExtensionEditor — a dock-mounted editor, not an overlay).
   Escape loops back to the summarize choice."
  [cs sess entry]
  (let [sel-atom (atom nil)
        dlg (dialogs/make-input-dialog
             "Custom branch summarization instructions"
             (fn [instructions]
               (state/close-selector! sel-atom)
               (tui/tui-request-render (:tui cs))
               (navigate-tree! cs sess entry true (str/trim instructions) false nil))
             (fn []
               (state/close-selector! sel-atom)
               (ask-branch-summary cs sess entry))
             (th/get-current-theme))]
    (state/mount-selector! cs sel-atom dlg)
    (tui/tui-request-render (:tui cs))))

(defn ask-branch-summary
  "Ask whether to summarize the abandoned branch before branching (pi:
   showExtensionSelector of the Summarize branch? options — mounted in the
   editor dock like pi, not an overlay, so the framed dialog renders over
   clean chrome). Escape re-opens the tree with the entry still selected."
  [cs sess entry]
  (let [sel-atom (atom nil)
        on-select (fn [choice]
                    (state/close-selector! sel-atom)
                    (tui/tui-request-render (:tui cs))
                    (case choice
                      "No summary" (navigate-tree! cs sess entry false nil false nil)
                      "Summarize" (navigate-tree! cs sess entry true nil false nil)
                      "Summarize with custom prompt" (prompt-custom-summary! cs sess entry)))
        on-escape (fn []
                    (state/close-selector! sel-atom)
                    ;; re-open with the highlight on the entry being
                    ;; navigated to (pi showTreeSelector initialSelectedId)
                    (show-session-tree cs
                                       (fn [entry]
                                         (ask-branch-summary cs @(:session-atom cs) entry))
                                       (:id entry)))
        dlg (dialogs/make-selector-dialog
             "Summarize branch?"
             ["No summary" "Summarize" "Summarize with custom prompt"]
             on-select
             on-escape
             (th/get-current-theme))]
    (state/mount-selector! cs sel-atom dlg)
    (tui/tui-request-render (:tui cs))))

;; ─── Fork / clone (pi: /fork, /clone) ─────────────────────────────────────

(defn fork-at!
  "Fork the session before the given user message and switch to the fork
   (pi: runtimeHost.fork — the new session starts at the message's parent;
   the message text is restored to the editor for re-editing). Forking the
   first user message (no parent) starts an empty session linked to this
   one."
  [cs entry-id]
  (if (state/turn-running? cs)
    (chat-history/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant
                                             :content "Wait for the current response to finish before forking."})
    (let [sess @(:session-atom cs)
          entry (session/get-entry sess entry-id)]
      (if (nil? entry)
        (chat-history/chat-history-add-message! (:chat-history cs)
                                                {:role :assistant :content "Invalid entry for forking."})
        (try
          ;; pi: emitBeforeFork — extensions may cancel the fork; the
          ;; conversation stays untouched
          (if (:cancel (event-bus/emit-event! {:type :session-before-fork
                                               :entry-id entry-id
                                               :position :at}))
            (chat-history/chat-history-add-message! (:chat-history cs)
                                                    {:role :assistant
                                                     :content "Fork cancelled by an extension."})
            (let [fork (if (:parent-id entry)
                         (session/fork-session sess (:parent-id entry))
                         (session/create-session (state/ensure-cwd-session-dir (state/runtime-cwd cs))
                                                 {:parent-session (:file sess)
                                                  :cwd (state/runtime-cwd cs)}))]
              (if (nil? fork)
                (chat-history/chat-history-add-message! (:chat-history cs)
                                                        {:role :assistant :content "Failed to create forked session."})
                (do
                  (debug/log "forked session " (:id fork) " from " (:id sess))
                  (restore-session! cs fork false)
                  (editor-text-set! (:editor cs) (session/session-entry-text entry))
                  (chat-history/chat-history-add-message! (:chat-history cs)
                                                          {:role :assistant
                                                           :content (str "Forked to new session " (subs (:id fork) 0 8) ".")})
                  (tui/tui-request-render (:tui cs))))))
          (catch Exception e
            (debug/log "fork failed: " e)
            (chat-history/chat-history-add-message! (:chat-history cs)
                                                    {:role :info :label "Fork"
                                                     :content (str "Fork failed: " (ex-message e))})
            (tui/tui-request-render (:tui cs))))))))

(defn clone-current-session!
  "Duplicate the session at its current position (pi: /clone → fork at the
   current leaf) and switch to the clone."
  [cs]
  (let [sess @(:session-atom cs)]
    (cond
      (state/turn-running? cs)
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant
                                               :content "Wait for the current response to finish before cloning."})

      (nil? sess)
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant :content "No active session."})

      (nil? @(:leaf-id sess))
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant :content "Nothing to clone yet."})

      (not (fs/exists? (:file sess)))
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant
                                               :content "Wait for the first assistant response before cloning."})

      :else
      (try
        ;; pi: /clone is a fork at the current leaf — session_before_fork
        ;; applies; extensions may cancel
        (if (:cancel (event-bus/emit-event! {:type :session-before-fork
                                             :entry-id @(:leaf-id sess)
                                             :position :at}))
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant
                                                   :content "Clone cancelled by an extension."})
          (let [fork (session/clone-session sess)]
            (if (nil? fork)
              (chat-history/chat-history-add-message! (:chat-history cs)
                                                      {:role :assistant :content "Failed to clone session."})
              (do
                (debug/log "cloned session " (:id fork) " from " (:id sess))
                (restore-session! cs fork false)
                (chat-history/chat-history-add-message! (:chat-history cs)
                                                        {:role :assistant
                                                         :content (str "Cloned to new session " (subs (:id fork) 0 8) ".")})
                (tui/tui-request-render (:tui cs))))))
        (catch Exception e
          (debug/log "clone failed: " e)
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :info :label "Clone"
                                                   :content (str "Clone failed: " (ex-message e))})
          (tui/tui-request-render (:tui cs)))))))
