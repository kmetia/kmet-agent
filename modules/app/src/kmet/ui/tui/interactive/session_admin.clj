(ns kmet.ui.tui.interactive.session-admin
  "Session administration for the interactive mode: the message/entry
   renderers, resume + import, the session tree, and fork/clone."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.debug :as debug]
            [kmet.app.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.libs.theme :as th]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.container :as container]
            [kmet.app.session.core :as session]
            [kmet.app.session.export :as session-export]
            [kmet.app.session.lifecycle :as lifecycle]
            [kmet.app.session.runtime :as runtime]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.bash-execution :as be]
            [kmet.ui.tui.dialogs :as dialogs]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.footer :as footer]
            [kmet.ui.tui.image-block :as image-block]
            [kmet.ui.tui.status-indicator :as status-indicator]
            [kmet.ui.tui.tool-execution :as tool-execution]
            [kmet.app.events :as events]
            [kmet.app.extensions.core :as extensions]
            [kmet.ui.tui.model-selector :refer [sync-footer-model!]]
            [kmet.ui.tui.tree-selector :refer [show-session-tree]]
            [kmet.ai.usage :as usage]
            [kmet.ui.tui.interactive.state :as state]
            [kmet.ui.tui.interactive.status :as status]))

;; Forward reference inside this namespace (the tree's custom-summary path).
(declare ask-branch-summary)

;; ─── Resume session ────────────────────────────────────────────────────────

(defn message-text
  "A message's content as plain text: a string passes through, a block vector
   contributes its text blocks. The display text for custom messages and
   injected info entries (pi renders both as plain text)."
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

(defn custom-message-display
  "A custom message's display form: its registered message renderer's
   result, else pi's labeled info box. nil when the :display flag is off —
   such an entry stays in the LLM context only (pi: display controls TUI
   rendering), so callers render exactly the nil-checked result."
  [e]
  (when (:display e)
    (if-let [rendered (run-message-renderer
                       (extensions/get-message-renderer (:custom-type e)) e)]
      (renderer-result->message rendered)
      {:role :info
       :content (message-text e)
       :images (image-block/content-images (:content e))
       :label (:custom-type e)})))

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

(defn- replay-tool-results!
  "Return a seq of tool message maps for the current assistant entry's
   tool calls, paired with their results by tool-call id through the shared
   `tool-call-message` projection (the same one the context-replaced
   rebuild uses). RESULTS is a map of tool-call-id → :tool entry. A call
   whose result is missing is kept, carrying the assistant's :stop-reason
   failure text when the turn was errored or aborted (pi:
   renderInitialMessages updateResult error)."
  [tool-calls results assistant-entry]
  (let [errored? (contains? #{:error :aborted} (:stop-reason assistant-entry))
        error-text (when errored?
                     (or (:error-message assistant-entry)
                         (if (= :aborted (:stop-reason assistant-entry))
                           "Aborted"
                           "Error")))
        errored? (and errored? (some? error-text))]
    (for [tc tool-calls]
      (cond-> (chat-history/tool-call-message tc (get results (:id tc)))
        errored? (assoc :content error-text :is-error true)))))

(defn- entry-text
  "An entry's text content: its text blocks joined, tool_result content
   included (pi: contentText). Non-text blocks contribute nothing."
  [e]
  (str/join
   (keep (fn [b]
           (case (:type b)
             :text (:text b)
             :tool_result (:content b)
             nil))
         (:content e))))

(defn- index-tool-results
  "Index ENTRIES for the replay: the tool-call ids that actually appear in
   assistant messages, and the :tool entries by tool-call id. The id set
   detects orphan results (legacy sessions, extension tools) with no
   matching call."
  [entries]
  (reduce (fn [[ids results] e]
            (case (:role e)
              :assistant
              [(into ids (keep :id) (:tool-calls e)) results]
              :tool
              (if-let [id (chat-history/tool-result-call-id e)]
                [ids (assoc results id e)]
                [ids results])
              [ids results]))
          [#{} {}]
          entries))

(defn- replay-custom-entry!
  "Extension state entries render only with a registered renderer (pi:
   registerEntryRenderer + CustomEntryComponent); a renderer failure renders
   the pi error line instead of crashing the replay."
  [cs e]
  (when-let [rendered (run-entry-renderer
                       (extensions/get-entry-renderer (:custom-type e)) e)]
    (chat-history/chat-history-add-message! (:chat-history cs)
                                            (renderer-result->message rendered))))

(defn- replay-custom-message!
  "Extension custom messages render only when display is set (pi: the display
   flag controls TUI rendering); a registered message renderer overrides the
   default labeled info box (see custom-message-display)."
  [cs e]
  (when-let [msg (custom-message-display e)]
    (chat-history/chat-history-add-message! (:chat-history cs) msg)))

(defn- replay-assistant!
  "An assistant entry plus the tool results of its tool calls: each call
   renders from the entry's :tool-calls (name + args) and the matching :tool
   result fills the component (pi: renderedPendingTools)."
  [cs e tool-results-by-id]
  (chat-history/chat-history-add-message!
   (:chat-history cs)
   {:role :assistant
    :content (entry-text e)
    :thinking (:thinking e)
    ;; replayed tool-call-only messages render no '(no response)' bubble
    :tool-calls (:tool-calls e)})
  (doseq [tm (replay-tool-results! (:tool-calls e) tool-results-by-id e)]
    (when-let [comp (chat-history/chat-history-add-message! (:chat-history cs) tm)]
      (reset! (:tool-call-id-atom comp) (:tool-call-id tm))
      (tool-execution/tool-execution-set-args-complete! comp))))

(defn- replay-orphan-tool!
  "Tool entries whose call was in the same assistant batch are replayed with
   it. Any remaining tool entry is an orphan result (legacy sessions,
   extension tools) — render it standalone from its own :tool-name."
  [cs e tool-call-ids]
  (when-not (contains? tool-call-ids
                       (chat-history/tool-result-call-id e))
    (chat-history/chat-history-add-message!
     (:chat-history cs)
     (cond-> {:role :tool
              :content (entry-text e)
              :tool-name (or (:tool-name e) (:name e) "tool")
              :is-error (:is-error e false)
              :truncation (:truncation e)
              :details (:details e)}
       ;; image results carry their blocks on the entry (pi:
       ;; toolResult content blocks) — the callback component
       ;; renders them like a matched result's would
       (seq (:images e)) (assoc :images (:images e))))))

(defn- replay-bash!
  "pi: addMessageToChat case \"bashExecution\" — !/!! replays as a COMPLETED
   BashExecutionComponent (no spinner, no duration)."
  [cs e]
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
                                            {:role :bash :command (:command e) :component comp})))

(defn- replay-summary!
  "pi: CompactionSummaryMessageComponent / BranchSummary — dedicated
   collapsible summary boxes; a usage-carrying summary appends the billing
   notice when :show-cache-miss-notices is on."
  [cs e]
  (let [role (:role e)]
    (chat-history/chat-history-add-message!
     (:chat-history cs)
     (cond-> {:role role :summary (:summary e) :tokens-before (:tokens-before e)}
       (:usage e) (assoc :usage (:usage e))))
    (when-let [notice (compaction-cost-notice (runtime/config (:engine cs)) role (:usage e))]
      (chat-history/chat-history-add-message! (:chat-history cs) notice))))

(defn- replay-plain!
  "Every other role (user / info / warning / notice …): the entry's text,
   with a user message's attached images and an info entry's label."
  [cs e]
  (let [role (:role e)]
    (chat-history/chat-history-add-message!
     (:chat-history cs)
     (cond-> {:role role :content (entry-text e)}
       ;; user-attached images replay too — entry-text keeps only the
       ;; text, so the image blocks must ride the message's :images
       ;; (the live path carries them inside :content)
       (= role :user) (assoc :images (image-block/content-images (:content e)))
       (= role :info) (assoc :label (:label e))))))

(defn- replay-entry!
  "Replay one context entry into the chat history, by role."
  [cs e tool-call-ids tool-results-by-id]
  (case (:role e)
    :custom (replay-custom-entry! cs e)
    :custom-message (replay-custom-message! cs e)
    :assistant (replay-assistant! cs e tool-results-by-id)
    :tool (replay-orphan-tool! cs e tool-call-ids)
    :bash (replay-bash! cs e)
    (:compaction :branch-summary) (replay-summary! cs e)
    (replay-plain! cs e)))

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
   bashExecution).

   The entry shapes themselves are replayed by the replay-*! fns above; this
   fn owns the order: clear, index, then each entry once."
  [cs sess]
  (chat-history/chat-history-clear! (:chat-history cs))
  (let [entries (session/build-context sess)
        [tool-call-ids tool-results-by-id] (index-tool-results entries)]
    (doseq [e entries
            :when (not (contains? #{:session_info :label :model-change
                                    :thinking-level-change :context-edit} (:role e)))]
      (replay-entry! cs e tool-call-ids tool-results-by-id))))

(defn replay-session!
  "Replay a session the runtime has already switched to into the UI: the
   branch into the chat history (session_info entries are metadata — never
   rendered; pi: only message entries are replayed on resume), the
   compaction-count status, the footer/title refresh, and — on the resume
   paths (startup --continue, /resume) — the applied model/thinking
   (APPLY-SETTINGS?, pi: createAgentSession) and the editor's prompt
   history (fork/clone keep the shared editor's existing history; pi:
   navigateTree/switchSession reuse the same editor instance). A recorded
   cwd that no longer exists is reported here (the runtime kept the current
   one)."
  [cs sess apply-settings?]
  (replay-branch! cs sess)
  ;; pi: renderInitialMessages — "Session compacted N times" status when
  ;; the session file (any branch) contains compactions
  (let [n (count (filter #(= :compaction (:role %)) @(:entries sess)))]
    (when (pos? n)
      (chat-history/chat-history-show-status!
       (:chat-history cs)
       (str "Session compacted " n (if (= n 1) " time" " times")))))
  (when-let [recorded (get-in sess [:header :cwd])]
    (when (nil? (session/session-cwd sess))
      (chat-history/chat-history-show-status!
       (:chat-history cs)
       (str "Session cwd " recorded
            " no longer exists — continuing in " (state/runtime-cwd cs)))))
  (when apply-settings?
    (sync-footer-model! cs)
    (editor/editor-set-history! (:editor cs) (session/get-prompt-history sess)))
  (state/update-footer! cs)
  (state/update-terminal-title! cs))

(defn install-live-session!
  "Point the process-wide extension session slot at the agent's live session.
   The handle's session ops move the agent in place and install nothing (a
   run's own binding already covers its handlers), so this front end
   re-installs after each move: an extension command handler runs outside a run
   and must act on the session the user just switched to. Returns nil."
  [cs]
  (extensions/install-session! (runtime/session (:engine cs))))

(defn restore-session!
  "Restore a session into the UI and the agent: the runtime owns the swap
   (switch-session! — the session atom, the agent's in-memory context
   rebuilt from the session branch, and the session-derived settings when
   APPLY-SETTINGS?); this replays the branch into the chat history.
   session_info entries are metadata — never rendered (pi: only message
   entries are replayed on resume). When APPLY-SETTINGS? is true (startup
   resume, /resume — pi: createAgentSession), the footer refreshes with the
   applied model/thinking; fork and clone pass false (pi: navigateTree
   keeps the current agent state)."
  [cs sess apply-settings?]
  ((:switch-session! (:engine cs)) sess {:apply-settings? apply-settings?})
  (install-live-session! cs)
  (replay-session! cs sess apply-settings?))

(defn handle-new-session
  "Pi: handleClearCommand → runtimeHost.newSession. The runtime owns the
   switch (the before/shutdown/start events, settling the in-flight turn
   and compaction, and creating the fresh session in the runtime cwd); the
   mode cancels its bash command before the runtime's settle wait so the
   bash result still lands in the OUTGOING session, then clears the chat,
   pending container, bash widgets, compaction queue, and editor (pi:
   editor.setText(\"\"))."
  [cs]
  (let [result ((:new-session! (:engine cs))
                {:before-settle #(state/cancel-bash! cs)})]
    (when-not (:cancelled result)
      (install-live-session! cs)
      (when (:was-running result)
        (status/stop-anim-timer! cs)
        (status/clear-status-indicator! cs))
      (chat-history/chat-history-clear! (:chat-history cs))
      (be/dispose-pending-bash! @(:pending-bash-components cs))
      (container/container-clear (:pending-messages-container cs))
      ;; the runtime's switch-session! already dropped the compaction queue
      ;; (pi: compactionQueuedMessages = [])
      ;; Re-attach the PendingMessages component — container-clear removes
      ;; every child (queued steering/follow-up display included), and the
      ;; display must keep rendering for messages queued after /new
      (when-let [pm (:pending-messages-comp cs)]
        (container/container-add-child (:pending-messages-container cs) pm))
      (reset! (:pending-bash-components cs) [])
      (tui/editor-set-text! @(:current-editor-atom cs) "")
      (state/update-footer! cs)
      (state/update-terminal-title! cs)
      (tui/tui-request-render (:tui cs))
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant :content "Started a new session."}))))

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
        ;; late binding: close! disposes the dialog it was built for
        dlg-atom (atom nil)
        close! (fn []
                 ;; leave, then dispose (the invariant dispose! checks):
                 ;; a late close cannot touch a newer occupant, and the
                 ;; frame's dispose cascades to its chrome + the list
                 (dock/close-atom! cs dlg-atom)
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
    (dock/mount! cs dlg)
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
   cwd becomes the runtime cwd (the handle's :switch-session! →
   apply-session-cwd!), so
   the tools, the prompt's cwd line and the footer follow it, as in pi's
   createRuntime."
  [cs args]
  (let [chat (:chat-history cs)
        path-arg (session-export/parse-path-argument args)]
    (cond
      (nil? path-arg)
      (import-error! chat "Usage: /import <path>")

      (state/turn-running? cs)
      (chat-history/chat-history-add-message! chat
                                              {:role :assistant
                                               :content "Wait for the current response to finish before importing."})

      :else
      (let [sess (runtime/session (:engine cs))
            ;; pi: this.session.sessionManager.getSessionDir() — the active
            ;; session's directory; a not-yet-persisted session still carries
            ;; its computed :file (lazy creation)
            dest-dir (or (some-> (:file sess) fs/parent str)
                         (state/ensure-cwd-session-dir (runtime/config (:engine cs)) (str (fs/cwd))))]
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
                 (if (:cancel (events/emit!
                               (runtime/agent-ctx (:engine cs))
                               {:type :session-before-switch
                                :reason :resume
                                :target-session-file (:path plan)}))
                   (chat-history/chat-history-show-status! chat "Import cancelled")
                   (do (session/copy-imported-session! plan)
                       (let [sess (session/load-session (:path plan))]
                         ((:switch-session! (:engine cs)) sess {:apply-settings? true})
                         (install-live-session! cs)
                         (sync-footer-model! cs)
                         (replay-session! cs sess true))
                       ;; pi: the import switch emits session_start (reason
                       ;; resume) like /resume — extensions restore from the
                       ;; imported branch
                       ;; the shared lifecycle below both drivers: the
                       ;; handle installs nothing, and this front end's own
                       ;; install already ran above
                       (lifecycle/emit-session-start-async! (:engine cs)
                                                            {:type :session-start :reason :resume})
                       (chat-history/chat-history-show-status!
                        chat (str "Session imported from: " (:source plan)))
                       (tui/tui-request-render (:tui cs))))
                 (catch Exception e
                   (import-failed! chat e))))))
          (catch Exception e
            (import-failed! chat e)))))))

;; ─── Session tree navigation (pi: TreeSelectorComponent) ──────────────────

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
    ;; the runtime owns the branch, the context rebuild, the compaction
    ;; queue drop and the :session-tree event
    ((:branch-session! (:engine cs))
     {:old-leaf old-leaf
      :target-leaf target-leaf
      :summary-result summary-result
      :label label
      :from-extension? from-extension?})
    (install-live-session! cs)
    (replay-branch! cs sess)
    ;; pi: restore the user message only when the editor is empty — a draft
    ;; the user is composing is not clobbered by navigation
    (when (and user-msg-text
               (str/blank? (tui/editor-get-text (:editor cs))))
      (tui/editor-set-text! (:editor cs) user-msg-text))
    (state/update-footer! cs)
    (chat-history/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant
                                             :content (if summary-result
                                                        "Navigated to the selected point (branch summarized)."
                                                        "Navigated to the selected point.")})
    (tui/tui-request-render (:tui cs))
    (catch Exception e
      (debug/log "tree navigation failed: " e)
      (chat-history/chat-history-add-message! (:chat-history cs)
                                              {:role :info :label "Tree"
                                               :content (str "Navigation failed: " (ex-message e))})
      (tui/tui-request-render (:tui cs)))))

(defn- start-branch-summary-render-driver!
  "Request a render every 100ms while the branch summarization runs and the
   TUI is still live; returns the future. `done` is only delivered by the
   summarization future, so the running check is what keeps a mode stop (or
   an Error in that future) from leaving the poller behind forever."
  [tui done]
  (future
    (while (and (not (realized? done))
                (some-> (:running? tui) deref))
      (Thread/sleep 100)
      (tui/tui-request-render tui))))

(defn- branch-summarize-and-apply!
  "Run the LLM branch summarization (pi: navigateTree summarize) with the
   BranchSummaryStatusIndicator and editor-escape abort, then branch with
   the summary. On abort/failure the branch is unchanged. PREP is the
   :session-before-tree preparation map (now carrying the effective :label
   and :replace-instructions after :session-before-tree overrides); ABORT-ATOM
   cancels the call."
  [cs sess old-leaf target-leaf user-msg-text prep abort-atom
   custom-instructions replace-instructions?]
  (let [ed (:editor cs)
        prev-interrupt (get @(:action-handlers ed) "app.interrupt")
        indicator (status-indicator/make-branch-summary-status-indicator)
        done (promise)]
    ;; escape → abort (pi: defaultEditor.onEscape = abortBranchSummary)
    (editor/editor-set-on-action! ed "app.interrupt"
                                  (fn [] (reset! abort-atom true)))
    (status/show-status-indicator! cs :branch-summary indicator)
    (tui/tui-request-render (:tui cs))
    ;; render driver: tick the indicator while the summarization runs; the
    ;; driver stops with the TUI or when `done` lands (finding 8)
    (start-branch-summary-render-driver! (:tui cs) done)
    (future
      (try
        (deliver done (runtime/generate-branch-summary
                       (:engine cs) (:entries-to-summarize prep) custom-instructions
                       abort-atom replace-instructions?))
        (catch Throwable e
          (debug/log "branch summarization failed: " e)
          (deliver done {:error (str "Branch summarization failed: "
                                     (or (ex-message e) (str (class e))))}))))
    (future
      (let [result (deref done 120000 :timeout)]
        (editor/editor-set-on-action! ed "app.interrupt" prev-interrupt)
        (status/release-background-status! cs :branch-summary)
        (cond
          (= result :timeout)
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :info :label "Tree"
                                                   :content "Branch summarization timed out — branch unchanged."})

          (:error result)
          ;; pi: navigateTree throws the branch-summary error — the caller
          ;; shows its message (the branch stays unchanged)
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :error
                                                   :content (:error result)})

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
        ext-result (events/emit! (runtime/agent-ctx (:engine cs))
                                 {:type :session-before-tree
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
               (state/close-selector! cs sel-atom)
               (tui/tui-request-render (:tui cs))
               (navigate-tree! cs sess entry true (str/trim instructions) false nil))
             (fn []
               (state/close-selector! cs sel-atom)
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
  ;; pi: branchSummary.skipPrompt — skip the question and default to no
  ;; summary
  (if (:skip-prompt (cfg/get-branch-summary-settings-live (runtime/config (:engine cs))))
    (navigate-tree! cs sess entry false nil false nil)
    (let [sel-atom (atom nil)
          on-select (fn [choice]
                      (state/close-selector! cs sel-atom)
                      (tui/tui-request-render (:tui cs))
                      (case choice
                        "No summary" (navigate-tree! cs sess entry false nil false nil)
                        "Summarize" (navigate-tree! cs sess entry true nil false nil)
                        "Summarize with custom prompt" (prompt-custom-summary! cs sess entry)))
          on-escape (fn []
                      (state/close-selector! cs sel-atom)
                      ;; re-open with the highlight on the entry being
                      ;; navigated to (pi showTreeSelector initialSelectedId)
                      (show-session-tree cs
                                         (fn [entry]
                                           (ask-branch-summary cs (runtime/session (:engine cs)) entry))
                                         (:id entry)))
          dlg (dialogs/make-selector-dialog
               "Summarize branch?"
               ["No summary" "Summarize" "Summarize with custom prompt"]
               on-select
               on-escape
               (th/get-current-theme))]
      (state/mount-selector! cs sel-atom dlg)
      (tui/tui-request-render (:tui cs)))))

;; ─── Fork / clone (pi: /fork, /clone) ─────────────────────────────────────

(defn fork-at!
  "Fork the session before the given user message and switch to the fork
   (pi: runtimeHost.fork — the runtime owns the switch; the new session
   starts at the message's parent) and restore the message text to the
   editor for re-editing. Forking the first user message (no parent) starts
   an empty session linked to this one."
  [cs entry-id]
  (if (state/turn-running? cs)
    (chat-history/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant
                                             :content "Wait for the current response to finish before forking."})
    (try
      (let [result ((:fork! (:engine cs)) entry-id)]
        (cond
          (= :unknown-entry (:error result))
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant :content "Invalid entry for forking."})

          (= :fork-failed (:error result))
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant :content "Failed to create forked session."})

          (:cancelled result)
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant
                                                   :content "Fork cancelled by an extension."})

          :else
          (let [{:keys [session entry]} result]
            (install-live-session! cs)
            (replay-session! cs session false)
            (tui/editor-set-text! (:editor cs) (session/session-entry-text entry))
            (chat-history/chat-history-add-message! (:chat-history cs)
                                                    {:role :assistant
                                                     :content (str "Forked to new session " (subs (:id session) 0 8) ".")})
            (tui/tui-request-render (:tui cs)))))
      (catch Exception e
        (debug/log "fork failed: " e)
        (chat-history/chat-history-add-message! (:chat-history cs)
                                                {:role :info :label "Fork"
                                                 :content (str "Fork failed: " (ex-message e))})
        (tui/tui-request-render (:tui cs))))))

(defn clone-current-session!
  "Duplicate the session at its current position (pi: /clone → fork at the
   current leaf) and switch to the clone (the runtime owns the switch)."
  [cs]
  (if (state/turn-running? cs)
    (chat-history/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant
                                             :content "Wait for the current response to finish before cloning."})
    (try
      (let [result ((:clone! (:engine cs)))]
        (case (:error result)
          :no-session
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant :content "No active session."})

          :no-leaf
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant :content "Nothing to clone yet."})

          :not-persisted
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant
                                                   :content "Wait for the first assistant response before cloning."})

          :clone-failed
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant :content "Failed to clone session."})

          (if (:cancelled result)
            (chat-history/chat-history-add-message! (:chat-history cs)
                                                    {:role :assistant
                                                     :content "Clone cancelled by an extension."})
            (let [clone (:session result)]
              (install-live-session! cs)
              (replay-session! cs clone false)
              (chat-history/chat-history-add-message! (:chat-history cs)
                                                      {:role :assistant
                                                       :content (str "Cloned to new session " (subs (:id clone) 0 8) ".")})
              (tui/tui-request-render (:tui cs))))))
      (catch Exception e
        (debug/log "clone failed: " e)
        (chat-history/chat-history-add-message! (:chat-history cs)
                                                {:role :info :label "Clone"
                                                 :content (str "Clone failed: " (ex-message e))})
        (tui/tui-request-render (:tui cs))))))
