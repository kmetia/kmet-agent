(ns kmet.modes.interactive
  "Interactive TUI mode entry — resolves the session, builds the layout,
   starts the render loop, and emits the session-start event.
   pi: modes/interactive/interactive-mode.ts."
  (:require [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as term]
            [kmet.tui.theme :as th]
            [kmet.config :as cfg]
            [kmet.debug :as debug]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.extensions :as extensions]
            [kmet.app.session :as session]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.app.ui.bash-execution :as bash-execution]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.session-selector :refer [show-session-selector]]
            [kmet.libs.process :as process]
            [kmet.libs.reakt :as reakt]
            [kmet.modes.interactive.layout :as layout]
            [kmet.modes.interactive.session-admin :as session-admin]
            [kmet.modes.interactive.state :as state]))

;; CoreState lives in kmet.modes.interactive.state; the constructors
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
   not mask the original exception on the finally path. Order is signal →
   extension surfaces → tree → process-global roots. Idempotent, so a
   second call is a no-op."
  [cs]
  (try (when-let [ag (some-> (:agent-state cs) deref)]
         (reset! (:signal ag) true))
       (catch Throwable _))
  (try (reset! (:bash-signal cs) true) (catch Throwable _))
  (try (extensions/ui-reset!) (catch Throwable _))
  (try (dispose-mode-tree! cs) (catch Throwable _))
  (try (extensions/clear-runtime!) (catch Throwable _))
  (try (theme-ctrl/shutdown! (:theme-controller cs)) (catch Throwable _))
  (try (reakt/discard-queued!) (catch Throwable _))
  nil)

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
   pi: cli.js dispatch to interactive mode."
  [config opts]
  (let [tui-ref (atom nil)
        cs-ref (atom nil)]
    (try
      ;; Extensions were loaded before dispatch (core/-main, pi: extension
      ;; discovery before model resolution); /reload re-loads them.

      ;; Apply command-line overrides
      (let [config (cfg/apply-cli-overrides config opts)
            _ (state/set-global-config! config)
            session (cond
                      (:session opts)
                      (let [base-dir (cfg/get-session-dir config)
                            path (state/resolve-session-arg (:session opts) base-dir)]
                        (if path
                          (session/load-session path)
                          (do (binding [*out* *err*]
                                (println (str "No session found matching '" (:session opts) "'")))
                              (System/exit 1))))
                      (:resume opts) nil
                      (:continue opts) (if-let [path (state/find-session)]
                                         ;; find-session returns the session
                                         ;; file path — load it into a Session
                                         ;; record so the context and chat can
                                         ;; be restored below
                                         (session/load-session path)
                                         ;; pi: continueRecent — no session to
                                         ;; continue → start a fresh one
                                         (session/create-session (state/ensure-cwd-session-dir)))
                      :else (session/create-session (state/ensure-cwd-session-dir)))
            cs (layout/build-layout config session)]
        (reset! cs-ref cs)
        (reset! tui-ref (:tui cs))
        (when (:resume opts)
          (show-session-selector cs state/ensure-session-dir
                                 (fn [path]
                                   ;; pi: emitBeforeSwitch (reason :resume) —
                                   ;; extensions may cancel the switch
                                   (when-not (:cancel (event-bus/emit-event!
                                                       {:type :session-before-switch
                                                        :reason :resume
                                                        :target-session-file path}))
                                     (let [sess (session/load-session path)
                                           short-id (subs (:id sess) 0 (min 8 (count (:id sess))))]
                                       (session-admin/restore-session! cs sess true)
                                       (chat-history/chat-history-add-message! (:chat-history cs)
                                                                               {:role :assistant
                                                                                :content (str "Resumed session " short-id ".")})
                                       (tui/tui-request-render (:tui cs)))))))
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
              (event-bus/emit-event!
               {:type :session-start
                :reason (cond (:resume opts) :resume
                              (:continue opts) :continue
                              :else :new)})
              ;; pi: resources_discover fires after session_start (reason
              ;; startup for non-reload session starts)
              (extensions/discover-resources! :startup))
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
        (when-let [resume (state/format-resume-command @(:session-atom cs) config)]
          (println (str (th/dim "To resume this session:") " " resume)))
        (:tui cs))
      (catch Exception e
        ;; Restore terminal if TUI was started, then rethrow for -main
        (process/kill-tracked-children!)
        (when-let [t @tui-ref]
          (try (tui/tui-stop t) (catch Exception _)))
        (throw e))
      (finally
        ;; A stopped mode must be fully released: the tree, the runtime
        ;; sinks, the theme callback/watcher and the reakt queue
        ;; (teardown-mode! — findings 2 and 10), then the extension UI
        ;; registry, which closes over the whole mode. The CLI exits right
        ;; after this, but embedded hosts and tests keep going.
        (when-let [cs @cs-ref]
          (teardown-mode! cs))
        (extensions/clear-ui-registry!)))))
