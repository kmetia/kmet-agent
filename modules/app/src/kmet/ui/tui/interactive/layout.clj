(ns kmet.ui.tui.interactive.layout
  "TUI layout construction for the interactive mode: the agent event
   handler and build-layout's widget tree, session wiring, and input
   listeners."
  (:require [kmet.app.debug :as debug]
            [kmet.libs.concurrent :as concurrent]
            [kmet.app.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.tui.hikap :as hiccup]
            [kmet.libs.theme :as th]
            [kmet.tui.terminal :as term]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.autocomplete :as ac]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.ai.models :as models]
            [kmet.app.session.core :as session]
            [kmet.app.session.runtime :as runtime]
            [kmet.app.commands :as commands]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.packages :as packages]
            [kmet.app.events :as events]
            [kmet.app.presets :as presets]
            [kmet.app.prompts :as prompts]
            [kmet.app.skills :as skills]
            [kmet.ui.tui.theme-controller :as theme-ctrl]
            [kmet.app.tools.registry :as tool-registry]
            [kmet.srce.core :as srce]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.external-editor :refer [handle-external-editor]]
            [kmet.ui.tui.footer :as footer]
            [kmet.ui.tui.footer-data-provider :as fdp]
            [kmet.ui.tui.image-block :as image-block]
            [kmet.ui.tui.loaded-resources :as loaded-resources]
            [kmet.ui.tui.model-catalog :as model-catalog]
            [kmet.ui.tui.model-selector :refer [show-model-selector sync-footer-model!]]
            [kmet.ui.tui.pending-messages :as pending-messages]
            [kmet.ui.tui.status-indicator :as status-indicator]
            [kmet.ui.tui.subs :as subs]
            [kmet.ui.tui.suspend :refer [handle-suspend]]
            [kmet.ui.tui.tool-execution :as tool-execution]
            [kmet.ui.tui.interactive.builtins :as builtins]
            [kmet.ui.tui.interactive.resources :as resources]
            [kmet.ui.tui.interactive.session-admin :as session-admin]
            [kmet.ui.tui.interactive.state :as state]
            [kmet.ui.tui.interactive.status :as status]
            [kmet.ui.tui.interactive.turn :as turn]
            [kmet.ui.tui.interactive.ui-registry :as ui-registry]))

(defn- config-theme
  "The theme record for CONFIG's :theme name — config owns the name, the TUI
   owns the theme store, so the front end resolves it here."
  [config]
  (th/get-theme (cfg/get-theme-name config)))

(defn maybe-show-cache-miss-notice!
  "pi: maybeShowCacheMissNotice — when :show-cache-miss-notices is on and
   the completed turn (RUN-MESSAGES) paid for a significant prompt-cache
   miss, add a transcript notice. Display floor: >= 20k tokens re-billed
   (pi's `missedTokens < 20_000 && missedCost < 0.1` is simplified to the
   token arm — kmet has no per-message price lookup here)."
  [cs run-messages]
  (when (and (cfg/get-show-cache-miss-notices (runtime/config (:engine cs)))
             ;; the run must have produced an assistant message with usage
             (some #(and (= :assistant (:role %)) (:usage %)) run-messages))
    (when-let [sess (runtime/session (:engine cs))]
      (when-let [miss (session/detect-cache-miss (session/get-branch sess))]
        (when (>= (:missed-tokens miss) 20000)
          (chat-history/chat-history-add-message!
           (:chat-history cs)
           {:role :info
            :label (if (:model-changed miss)
                     "Cache miss after model switch"
                     "Cache miss")
            :content (str (:missed-tokens miss) " tokens re-billed")}))))))

;; ─── Layout setup ──────────────────────────────────────────────────────────

(defn- on-tool-execution
  "Tool-execution events: the transcript's tool components. Parallel tool
   calls each own a component in PENDING-TOOL-COMPS keyed by tool-call id,
   so updates and ends correlate by id."
  [{:keys [chat-history tui pending-tool-comps]} evt]
  (case (:type evt)
    :tool-execution-start
    ;; Pi: create pending component once, update in place.
    ;; The streaming placeholder finalizes here (the tool box lands after
    ;; it) — mark the message as tool-call-bearing FIRST so a tool-call-only
    ;; assistant message stays invisible instead of bubbling '(no response)'
    ;; (pi: hasToolCalls; loop-continue turns are usually tool-call-only).
    (let [msg {:role :tool
               :tool-name (:tool-name evt)
               :arguments (:args evt {})
               :content ""
               :is-error false}]
      (chat-history/chat-history-mark-streaming-tool-calls! chat-history)
      (chat-history/chat-history-finalize-streaming! chat-history)
      (let [comp (chat-history/chat-history-add-message! chat-history msg)]
        ;; Store tool call ID for correlation
        (reset! (:tool-call-id-atom comp) (:tool-call-id evt))
        ;; Args are complete when received (kmet: no streaming args)
        (tool-execution/tool-execution-set-args-complete! comp)
        ;; Pi: pendingTools.set(toolCallId, component) — parallel tool
        ;; calls each own a component; updates/ends correlate by id
        (swap! pending-tool-comps assoc (:tool-call-id evt) comp))
      ;; mount poke stays: the new message lands in the untracked
      ;; messages-atom, so no watch exists for it yet
      (tui/tui-request-render tui))

    :tool-execution-prepared
    ;; The call cleared its hooks and the permission gate (a permission
    ;; `:ask` was answered): the tool is about to run, so the elapsed timer
    ;; starts here — not at :tool-execution-start, which fires while the
    ;; dialog is still waiting for a human and would count their thinking
    ;; time as the tool's.
    (when-let [comp (get @pending-tool-comps (:tool-call-id evt))]
      (tool-execution/tool-execution-mark-execution-started! comp)
      ;; a pre-mount prepared event has no track! watch yet
      (when (nil? @(:cache-atom comp))
        (tui/tui-request-render tui)))

    :tool-execution-update
    ;; Pi: live partial content from streaming tools (bash). For a
    ;; :status tool the elapsed counter ticks via the tool component's own
    ;; 1s ticker (pi: setInterval → context.invalidate), so a silent
    ;; long-running tool still updates Elapsed steadily — this event
    ;; only pushes the new output chunks.
    ;; No manual render request: set-content! swaps a track!-watched
    ;; atom, which schedules the frame itself (§3.4).
    (when-let [comp (get @pending-tool-comps (:tool-call-id evt))]
      (when-let [content (:content evt)]
        (reset! (:content-atom comp) content)
        ;; A newly-created component has no watch until its first render.
        ;; Keep the normal reactive path after that, but cover an update
        ;; racing the mount frame as well.
        (when (nil? @(:cache-atom comp))
          (tui/tui-request-render tui))))

    :tool-execution-end
    ;; Pi: update the component by id and remove it from pendingTools.
    ;; The explicit request covers a tool that finishes before its first
    ;; frame, when no track! watch exists yet to schedule completion.
    (when-let [comp (get @pending-tool-comps (:tool-call-id evt))]
      (let [result (:result evt)]
        (reset! (:content-atom comp) (:content result))
        (tool-execution/tool-execution-set-error! comp (:is-error result false))
        (when-let [truncation (:truncation result)]
          (reset! (:truncation-atom comp) truncation))
        (when-let [details (:details result)]
          (reset! (:details-atom comp) details))
        (when-let [images (:images result)]
          (tool-execution/tool-execution-set-images! comp images))
        (swap! pending-tool-comps dissoc (:tool-call-id evt))
        (tui/tui-request-render tui)))))

(defn- on-message
  "Message lifecycle events: the transcript entry for each message role.
   Streaming text/thinking itself arrives through the on-text/on-thinking
   callbacks, so :message-update needs no UI action."
  [{:keys [chat-history tui cs-ref]} evt]
  (case (:type evt)
    :message-start
    ;; Pi: message_start → user messages (the initial
    ;; prompt and consumed steering/follow-up messages)
    ;; land in the chat here; assistant message starts
    ;; finalize the previous turn's streaming placeholder
    ;; and open a fresh one, so a follow-up continuation
    ;; never merges into the prior response; before-
    ;; agent-start injected messages (role :info) display
    ;; as labeled info boxes above the response. Content
    ;; is normalized from text blocks to a string for
    ;; the info box.
    (case (:role (:message evt))
      :user (do (chat-history/chat-history-add-message! chat-history (:message evt))
                (when-let [cs @cs-ref]
                  (status/update-pending-messages! cs))
                (tui/tui-request-render tui))
      :assistant (do (chat-history/chat-history-finalize-streaming! chat-history)
                     (chat-history/chat-history-finalize-thinking! chat-history)
                     (chat-history/chat-history-start-streaming! chat-history)
                     (tui/tui-request-render tui))
      :info (let [m (:message evt)]
              (chat-history/chat-history-insert-before-streaming!
               chat-history
               (assoc m
                      :content (session-admin/message-text m)
                      :images (image-block/content-images (:content m))))
              (tui/tui-request-render tui))
      ;; extension custom messages (pi: custom messages render when
      ;; display=true — same rule as the session-restore path)
      :custom (do (when-let [msg (session-admin/custom-message-display (:message evt))]
                    (chat-history/chat-history-insert-before-streaming! chat-history msg))
                  (tui/tui-request-render tui))
      nil)

    :message-update
    ;; the streaming delta the run callbacks used to deliver (pi:
    ;; text_delta / thinking_delta). The delta's type picks the sink; a tool
    ;; call's has no UI of its own (the tool-execution events carry it).
    (when-let [cs @cs-ref]
      (case (:type (:delta evt))
        :text (turn/on-agent-text cs (:content (:delta evt)))
        :thinking (turn/on-agent-thinking cs (:content (:delta evt)))
        nil))

    :message-end
    ;; A failed attempt finalizes empty when the stream delivered
    ;; nothing before the deadline: drop that placeholder instead of
    ;; letting it settle into a bare "(no response)" bubble. Any
    ;; partial text/thinking stays (record-abandoned-attempt! kept
    ;; it), and the reason surfaces once per attempt with the retry
    ;; line — or, for a terminal failure, through on-agent-error.
    (when (and (:error-message (:message evt))
               (chat-history/chat-history-streaming-empty? chat-history))
      (chat-history/chat-history-remove-streaming-placeholder! chat-history)
      (tui/tui-request-render tui))))

(defn- on-run-lifecycle
  "Run-level events: the spinner/animation presentation, the footer, and the
   transcript notices that belong to a whole run (the repeat-loop guard)."
  [{:keys [chat-history tui cs-ref]} evt]
  (case (:type evt)
    :agent-start
    ;; Pi: agent_start → setProgress(true) (showTerminalProgress gated).
    ;; Every run presents here — the editor's, an extension's send, the
    ;; compaction-queue flush — because the front end only sends. The anim
    ;; timer is nil exactly while no presentation is up, so this is
    ;; idempotent across retry attempts.
    (when-let [cs @cs-ref]
      (turn/set-terminal-progress! cs true)
      (when (and (runtime/turn-running? (:engine cs))
                 (nil? @(:anim-timer cs)))
        (status/start-anim-timer! cs)
        (when-not (= :working (:kind @(:status-current cs)))
          (status/activate-working-indicator! cs))
        (state/update-footer! cs)))

    :agent-end
    ;; Pi: maybeShowCacheMissNotice — a significant
    ;; prompt-cache miss on the completed turn (only
    ;; when the run actually produced an assistant
    ;; message — a failed run must not re-show the
    ;; previous turn's miss)
    (do (when-let [cs @cs-ref]
          (turn/set-terminal-progress! cs false)
          (maybe-show-cache-miss-notice! cs (:messages evt)))
        (tui/tui-request-render tui))

    :turn-start
    ;; A new LLM call is starting. After a retry backoff
    ;; or compaction the status container holds a
    ;; transient indicator (or the stopped working
    ;; indicator); revive the working spinner so the
    ;; call streams under "Working" (pi: the session
    ;; emits a fresh agent_start after retry/compaction
    ;; via agent.continue(), re-showing the
    ;; WorkingStatusIndicator — kmet's loop recurs
    ;; in-turn, so turn-start is the equivalent signal).
    ;; The guarded nil-swap inside activate-working-indicator!
    ;; schedules the frame when a real change happens.
    (when-let [cs @cs-ref]
      (when (and (runtime/turn-running? (:engine cs))
                 (not= :working (:kind @(:status-current cs))))
        (status/activate-working-indicator! cs)))

    :turn-end nil

    :agent-before-settle nil

    :agent-settled
    ;; The run ended. A completed prompt runs the done teardown here — the
    ;; callbacks' :on-done used to — while an error was torn down by its own
    ;; event (on-agent-error) and a cancel by handle-cancel, which is what
    ;; pi's split between agent_end and abort() gives. An abort that came from
    ;; outside this front end (an extension's cancel, a handle op, a teardown)
    ;; ran neither, so an :aborted settle runs the same idempotent teardown —
    ;; the spinner would otherwise keep working on an idle agent.
    (when-let [cs @cs-ref]
      (when (#{:completed :aborted} (:outcome evt))
        (turn/on-agent-done cs)))

    :status
    ;; Pi: agent status changes keep the footer/status
    ;; layer in sync via the :status event (update-footer!'s invalidate
    ;; schedules the frame)
    (when-let [cs @cs-ref]
      (state/update-footer! cs))

    :queue-update
    ;; Queued steering/follow-up messages changed (pi:
    ;; queue_update → updatePendingMessagesDisplay; the tracked-atom
    ;; swap schedules the frame)
    (when-let [cs @cs-ref]
      (status/update-pending-messages! cs))

    :loop-guard
    ;; Repeat-loop guard tripped (kmet-specific): show a warning line in
    ;; the transcript — the run has already settled (the final text
    ;; carries the explanation)
    (do
      (chat-history/chat-history-add-message!
       chat-history
       {:role :warning
        :content (or (:details evt) "Stopped: repeat-loop guard tripped")})
      (tui/tui-request-render tui))))

(defn- on-retry
  "Auto-retry and summarization-retry events: the retry indicator's lifecycle
   and the error line each failed attempt leaves in the transcript."
  [{:keys [chat-history tui cs-ref]} evt]
  (case (:type evt)
    :auto-retry-start
    ;; Show the retry countdown; the failed attempt's partial
    ;; text stays visible (pi: auto_retry_start only swaps in a
    ;; RetryStatusIndicator — the errored block remains in the
    ;; chat and the retried stream opens a fresh message below it)
    ;; (the :status-current swap schedules its own frame)
    ;;
    ;; The reason is recorded here, not at :message-end: this event
    ;; fires only when a retry actually follows, so a terminal failure
    ;; still reports exactly once through on-agent-error. Without it a
    ;; stalled attempt leaves nothing behind — the retried stream opens
    ;; a fresh message below it — and an unattended hang is
    ;; indistinguishable from slow work.
    (do
      (when-let [err (:error-message evt)]
        (chat-history/show-error! chat-history err))
      (when-let [cs @cs-ref]
        (status/show-status-indicator!
         cs :retry
         (status-indicator/make-retry-status-indicator
          (:attempt evt) (:max-attempts evt) (:delay-ms evt)
          :cancel-hint (status/fmt-key-display
                        (app-kb/key-text "app.interrupt")))))
      (tui/tui-request-render tui))

    :auto-retry-end
    ;; Retry finished (pi: auto_retry_end →
    ;; clearStatusIndicator("retry")). Kind-gated: when
    ;; the retried call already started (turn-start
    ;; revived the working indicator) this no-ops and
    ;; the working spinner keeps spinning.) The kind-gated nil-swap
    ;; schedules its own frame.
    (when-let [cs @cs-ref]
      (status/clear-status-indicator! cs :retry))

    :summarization-retry-scheduled
    ;; A summarization call failed transiently (pi:
    ;; summarization_retry_scheduled → showError + RetryStatusIndicator);
    ;; the error is surfaced once, before the countdown
    (do
      (when-let [err (:error-message evt)]
        (chat-history/show-error! chat-history err))
      (when-let [cs @cs-ref]
        (status/show-status-indicator!
         cs :retry
         (status-indicator/make-retry-status-indicator
          (:attempt evt) (:max-attempts evt) (:delay-ms evt)
          :cancel-hint
          (status/fmt-key-display (app-kb/key-text "app.interrupt")))))
      (tui/tui-request-render tui))

    :summarization-retry-attempt-start
    ;; The retried summarization starts (pi:
    ;; clearStatusIndicator("retry") → the compaction or branch-summary
    ;; indicator again, matching the call that is running)
    (do (when-let [cs @cs-ref]
          (status/clear-status-indicator! cs :retry)
          (if (= :branch-summary (:source evt))
            (status/show-status-indicator!
             cs :branch-summary
             (status-indicator/make-branch-summary-status-indicator))
            (status/show-status-indicator!
             cs :compaction
             (status-indicator/make-compaction-status-indicator
              :message (turn/compaction-status-message (:reason evt))))))
        (tui/tui-request-render tui))

    :summarization-retry-finished
    ;; Retry loop over (pi: summarization_retry_finished →
    ;; clearStatusIndicator("retry"))
    (when-let [cs @cs-ref]
      (status/clear-status-indicator! cs :retry))))

(defn- rebuild-transcript!
  "Rebuild the chat history to mirror a replaced context (pi:
   context_replaced). Custom messages honor the display flag — hidden ones
   stay in the LLM context only — and a registered message renderer
   overrides the labeled info box; a compaction / branch-summary message is
   followed by its billing notice (pi: addCompactionCostNotice)."
  [chat-history cs-ref evt]
  (chat-history/chat-history-rebuild!
   chat-history
   (mapcat (fn [m]
             (let [m (if (and (= :custom (:role m)) (:display m))
                       (session-admin/custom-message-display m)
                       m)]
               (if-let [notice (session-admin/compaction-cost-notice (:config @cs-ref)
                                                                     (:role m) (:usage m))]
                 [m notice]
                 [m])))
           (remove #(and (= :custom (:role %))
                         (not (:display %)))
                   (:messages evt)))))

(defn- on-compaction
  "Compaction events: the terminal progress flag, the compaction indicator,
   the rebuilt transcript after a context replacement, and the queued-message
   flush a finished compaction unblocks."
  [{:keys [chat-history tui cs-ref]} evt]
  (case (:type evt)
    :compaction-start
    ;; Session compaction in progress (pi:
    ;; compaction_start → CompactionStatusIndicator + terminal progress);
    ;; the hint is truthful — escape aborts it
    (do (when-let [cs @cs-ref]
          (turn/set-terminal-progress! cs true)
          (status/show-status-indicator!
           cs :compaction
           (status-indicator/make-compaction-status-indicator
            :message (turn/compaction-status-message
                      (:reason evt)))))
        (tui/tui-request-render tui))

    :compaction-end
    ;; Compaction done — restore the idle status (pi:
    ;; compaction_end → clearStatusIndicator). For the
    ;; manual path the /compact future skips its reply
    ;; on abort, so the status is the only feedback;
    ;; an in-loop abort is already reported by the
    ;; full turn cancel ("(cancelled)") — no double
    ;; report.
    (do (when-let [cs @cs-ref]
          (turn/set-terminal-progress! cs false)
          (status/clear-status-indicator! cs :compaction)
          (when (:aborted evt)
            ;; pi: compaction_end aborted — manual: error line; auto:
            ;; dim status
            (if (= :manual (:reason evt))
              (chat-history/chat-history-show-status!
               chat-history "Compaction cancelled")
              (chat-history/chat-history-show-status!
               chat-history "Auto-compaction cancelled")))
          ;; pi: compaction_end errorMessage — a failed summarization is
          ;; surfaced BEFORE the queue flush (manual: error line; auto:
          ;; dim status), so the error precedes any run the flush starts.
          (when-let [err (:error-message evt)]
            (if (= :manual (:reason evt))
              (chat-history/chat-history-add-message!
               chat-history {:role :error :content err})
              (chat-history/chat-history-show-status! chat-history err)))
          ;; pi: compaction_end → flushCompactionQueue — messages queued
          ;; during compaction are delivered now (queued into the retrying
          ;; turn when will-retry, else the first prompts a fresh run).
          (turn/flush-compaction-queue! cs (:will-retry evt)))
        (tui/tui-request-render tui))

    :context-replaced
    ;; Rebuild the chat history to mirror the replaced context (see
    ;; rebuild-transcript!)
    (do (rebuild-transcript! chat-history cs-ref evt)
        (tui/tui-request-render tui))

    :session-compact nil

    :session-compact-failed nil

    :session-before-compact nil))

(defn- on-model-change
  "The runtime owns model and thinking switching (extension set-model,
   cycling, compaction overrides); the footer and the editor border follow
   the event — :thinking-level-select carries the new level, :model-select
   reads the thinking the switch left in place. /model and the selector sync
   on their own path too — sync-footer-model! is idempotent."
  [{:keys [cs-ref]} evt]
  (case (:type evt)
    (:model-select :thinking-level-select)
    (when-let [cs @cs-ref]
      (state/update-editor-border-color!
       cs (or (:level evt) (runtime/thinking-level (:engine cs))))
      (sync-footer-model! cs))))

(defn- make-agent-event-handler
  "Build the agent-loop :on-event callback for the interactive UI: routes
   lifecycle events to the chat history, pending-messages display, footer,
   and status-indicator layers (pi: the TUI session's event handlers).
   DEPS — map of the layout's mutable refs:
     :chat-history        — ChatHistoryComponent (messages, streaming state)
     :tui                 — TUI record (render requests)
     :cs-ref              — atom holding the CoreState (footer/status updates;
                             nil until the layout is assembled)
     :pending-tool-comps   — atom map tool-call-id → in-flight tool component
                            (pi: pendingTools Map — parallel tool calls each
                            get their own component; end events correlate by
                            id and remove the entry, so every component's
                            elapsed ticker is cleared by its own end).
   The case is the routing table, one branch per event family (see the
   on-* fns above); its trailing nil is the safe default for events outside
   the loop vocabulary. A vocabulary event with no branch here throws — that
   is the point: the exception is swallowed by the run future and would
   leave the UI stuck on \"Working\" forever, so
   kmet.ui.tui.interactive.test-interactive walks every loop event type through
   this handler."
  [deps]
  (fn [evt]
    (case (:type evt)
      (:tool-execution-start :tool-execution-prepared :tool-execution-update
                             :tool-execution-end)
      (on-tool-execution deps evt)

      (:message-start :message-update :message-end)
      (on-message deps evt)

      (:agent-start :agent-end :turn-start :turn-end :agent-before-settle
                    :agent-settled :status :queue-update :loop-guard)
      (on-run-lifecycle deps evt)

      (:auto-retry-start :auto-retry-end :summarization-retry-scheduled
                         :summarization-retry-attempt-start :summarization-retry-finished)
      (on-retry deps evt)

      (:compaction-start :compaction-end :context-replaced :session-compact
                         :session-compact-failed :session-before-compact)
      (on-compaction deps evt)

      (:model-select :thinking-level-select)
      (on-model-change deps evt)

      :error
      ;; a terminal error (the loop reports it once, before the settle); the
      ;; teardown clears the working state and writes the transcript line
      (when-let [cs @(:cs-ref deps)]
        (turn/on-agent-error cs (:message evt)))

      ;; Remaining vocabulary events need no UI action: streaming deltas are
      ;; :message-update (on-message), message finalization + the idle
      ;; transition happen on :agent-settled (on-run-lifecycle), errors
      ;; surface through :error, and the /model + Ctrl+P cycling paths sync
      ;; the footer themselves. Every event must still be consumed — a case
      ;; with no matching clause throws, and the exception is swallowed by
      ;; the run future, leaving the UI stuck on "Working" forever.
      nil)))

(defn- make-editor-submit-handler
  "The app editor's on-submit callback (pi: setupEditorSubmitHandler).

   The active editor is cleared BEFORE the text is dispatched. pi's editor
   clears itself in submitValue before it calls onSubmit, and kmet must
   clear first for the same observable contract: an extension dialog
   mounted over the editor saves the editor text when it opens and restores
   it when it closes (ui-custom, see
   kmet.ui.tui.interactive.ui-registry), so clearing afterwards would make
   closing the /tools dialog put the submitted '/tools' line back into the
   editor. Clearing first also lets a command handler write to the editor
   while it runs — the app does not wipe it afterwards.

   TEXT nil is the editor's escape/cancel callback, not a submit."
  [cs]
  (fn [text]
    (when text
      (tui/editor-set-text! @(:current-editor-atom cs) "")
      (turn/handle-submit cs text)
      (tui/tui-request-render (:tui cs)))))

(defn- cycle-tool-display!
  "Cycle the transcript's tool display (collapsed → quiet → expanded): one
   reset! on the chat history's shared mode atom re-derives every tool and
   skill block, the header and loaded-resources sections follow (quiet reads
   as collapsed, pi's getStartupExpansionState), the setting persists, a
   status line names the new mode, and the reflow repaints the scrollback.
   The editor's app.tools.expand binding and the ask dialog's listener share
   this one action."
  [cs]
  (let [ch (:chat-history cs)
        mode (chat-history/chat-history-cycle-tool-display! ch)
        expanded? (= :expanded mode)]
    (try (cfg/set-tool-display-mode! mode)
         (catch Exception e
           (debug/log "Failed to persist tool-display-mode: " e)))
    (when-let [hdr (:header-comp cs)]
      (expandable-text/expandable-text-set-expanded! hdr expanded?))
    (when-let [lr (:loaded-resources-comp cs)]
      (loaded-resources/loaded-resources-set-expanded! lr expanded?))
    (chat-history/chat-history-show-status! ch (str "Tool display: " (name mode)))
    (turn/request-global-reflow-render! cs)))

(defn tool-display-listener
  "Build the TUI input listener for the tool-display cycle (app.tools.expand).
   Installed while the permission ask blocks on a human: the dialog holds
   every key, so the editor's own binding never runs, and the transcript the
   human is deciding about could not be expanded, collapsed or quieted. The
   listener chain runs before focus dispatch, the binding resolves per event
   through the global manager (keybindings.edn overrides apply), and the event
   is consumed so the dialog does not also act on it. Scoped to the ask on
   purpose: a selector that owns the same chord itself (/tree's filter cycle)
   keeps it, because nothing is installed while it is up."
  [cs]
  (fn [data]
    (when (tui-kb/matches-key (tui-kb/get-global-keybindings) data "app.tools.expand")
      (cycle-tool-display! cs)
      {:consume true})))

(defn- cycle-model-action!
  "Cycle the session model in DIRECTION (-1/1) and persist the new default
   (pi: onAction cycleModelForward/cycleModelBackward). Reports why nothing
   moved when the session has a single model in scope."
  [cs direction]
  (let [engine (:engine cs)]
    (if (runtime/cycle-model! engine direction)
      (do (cfg/set-default-model! (runtime/provider engine) (runtime/model engine))
          (sync-footer-model! cs))
      (chat-history/chat-history-show-status!
       (:chat-history cs)
       (if (seq (runtime/scoped-models engine))
         "Only one model in scope"
         "Only one model available")))))

(defn make-app-chat-history
  "The chat history the interactive layout mounts, wired to the app's state:
   the session's cwd (not the process's) and TOOL-FN, a 1-arg name → tool map
   lookup over the run's registry. The per-tool renderers are the reason the
   UI layer takes the lookup from its caller instead of reaching for a
   registry itself. Kept as one fn so the wiring can be tested without booting
   a terminal."
  [config cwd-atom tool-fn]
  (chat-history/make-chat-history
   :cwd-fn #(deref cwd-atom)
   ;; a tool's own renderers are the registry's, not the UI layer's: the
   ;; lookup is supplied here so a tool result renders exactly as its live
   ;; call did
   :tool-fn tool-fn
   :thinking-hidden (cfg/get-hide-thinking-block config)
   :tool-display-mode (cfg/get-tool-display-mode config)
   :output-pad (cfg/get-output-pad config)))

(defn create-opts
  "The `:agents/create!` opts a TUI run hands its factory: the session the run
   resolved, the run's config, and — when the mode row named any — the presets
   mounted under the agent's child ctx before the agent exists (7b-ii's
   `:setup`). A preset is named by the row's config (`{:presets
   [:bundle/gated]}`, or one name without the vector), so a profile mounts a
   per-agent world without code."
  [config session opts]
  (let [presets (:presets opts)
        ;; a profile author's EDN may name one preset or a list; an empty list
        ;; is "none". Anything else is a name `presets/setup` will mount (or
        ;; fail on, named) — a raw `(seq :bundle/gated)` here would be a boot
        ;; crash saying nothing.
        presets (if (coll? presets) (seq presets) presets)]
    (cond-> {:session session :config config}
      presets (assoc :setup (presets/setup presets)))))

(defn build-layout
  "Create TUI layout and return CoreState.

   CTX is the ctx the agent runs on — the one whose `:tools`, `:sessions` and
   `:systemPrompt` the run reads, and the scope `:agents` serves. It is the
   required argument: the entry point passes the composition root it booted
   for the `:profile tui` run. The tool lookup the chat history
   renders with comes from that ctx, so the layout reaches for no registry of
   its own.

   OPTS is the run's (`interactive/run`'s): the preset names a mode row gave
   its agent are the only part this construction reads."
  ([config session ctx] (build-layout config session ctx nil))
  ([config session ctx opts]
   (let [tui-term (term/create-terminal)
         t (tui/create-tui tui-term)

        ;; Resolve model and provider from config
         provider (cfg/get-provider config)
         model (models/resolve-config-model config)

        ;; The agent is the registry's: created through `:agents` like any
        ;; other, so a TUI run's agent is a live handle with an id (landing 8)
        ;; rather than an engine the layout owns. The run reads its tool table
        ;; and system prompt from CTX, which is why the layout needs no registry
        ;; of its own. The process's skills and prompt templates load once,
        ;; before the prompt is built from them (kmet1 loads them in the
        ;; driver; here the front end does, for the agent it is about to make).
         _ (packages/load-resources!)
         agents (or (srce/get ctx :agents)
                    (throw (ex-info "the TUI needs an :agents service on its ctx: mount a loop driver (`:agentLoop`)"
                                    {:type :kmet.app/no-agents
                                     :ctx (:name ctx)})))
         engine ((:create! agents) ctx (create-opts config session opts))
         cwd-atom (:cwd engine)

        ;; Migrate legacy keybinding ids in keybindings.edn (pi: migrations
        ;; runner calls migrateKeybindingsConfigFile at startup)
         _ (app-kb/migrate-keybindings-config-file!)

        ;; Initialize keybindings (global singleton for key-hint + input
        ;; handling); persisted overrides load from keybindings.edn
        ;; (pi: KeybindingsManager.create)
         _ (let [kmgr (app-kb/create-agent-keybindings-manager)]
             (tui-kb/set-global-keybindings! kmgr))

        ;; Components (define before agent state so on-event can reference them)
         sp1 (spacer/make-spacer 1)
        ;; Inline image display settings (pi: terminal.showImages /
        ;; terminal.imageWidthCells) — seeded here, then read live by every
        ;; image (tool results, message attachments) through the shared sub
         _ (reset! subs/image-settings-atom
                   {:show-images (cfg/get-show-images config)
                    :image-width-cells (cfg/get-image-width-cells config)})
        ;; no :theme — message components subscribe to ui.subs/theme-sub
        ;; themselves (Stage 5)
         tool-fn (let [tools (srce/get ctx :tools)]
                   (fn [name] (tool-registry/get-tool tools name)))
         ch (make-app-chat-history config cwd-atom tool-fn)
         pending-tool-comps (atom {})  ;; Pi: pendingTools Map (tool-call-id → comp)
         cs-ref (atom nil)             ;; CoreState, filled after layout (for :status events)

        ;; Context window of the active model — drives the footer % and the
        ;; proactive auto-compaction check (pi: state.model.contextWindow)
         ctx-window (or (:context-window (models/get-model provider model))
                        (:context-window config))
        ;; B.1: welcome header — ExpandableText with compact/full variants
        ;; (pi: builtInHeader), toggled by app.tools.expand
         hdr (let [mode (cfg/get-tool-display-mode config)]
               (expandable-text/make-expandable-text
                state/fmt-header-compact state/fmt-header-full
                :expanded? (= :expanded mode) :padding-x 1 :padding-y 0))
        ;; B.2: loaded resources between header and chat (pi: showLoadedResources)
         lr (loaded-resources/make-loaded-resources :theme (config-theme config)
                                                    :expanded? (= :expanded (cfg/get-tool-display-mode config)))
        ;; B.3: queued steering/follow-up display (pi: updatePendingMessagesDisplay)
         pm (pending-messages/make-pending-messages
             :hint (status/fmt-key-display (app-kb/key-text "app.message.dequeue")))
        ;; B.5: editor dynamic height — max(5, rows*0.3) via :terminal-rows;
        ;; the fixed :height fallback stays at the default 12;
        ;; border color reflects the current thinking level (pi: updateEditorBorderColor)
         ed (editor/make-editor :padding-x (cfg/get-editor-padding-x config)
                                :autocomplete-max-visible (cfg/get-autocomplete-max-visible config)
                                :terminal-rows (fn [] (term/rows @(:terminal t)))
                                :border-fn (th/get-thinking-border-color
                                            (config-theme config)
                                            (or (runtime/thinking-level engine) :off)))
        ;; B.6: footer data provider + footer (pi: FooterComponent; the
        ;; model line wraps to its own line when the stats line is too narrow)
         fdp (fdp/make-footer-data-provider
              :cwd-atom cwd-atom
              :session session
              :provider-count (count (distinct (map :provider (model-catalog/scoped-or-available-models engine))))
             ;; Phase 2: context window from the resolved Model record, falling
             ;; back to the settings value when the model is unknown (pi footer
             ;; contextPercentDisplay)
              :context-window ctx-window
              :model (runtime/model engine) :provider (runtime/provider engine) :thinking (runtime/thinking-level engine)
             ;; pi: the thinking suffix renders only for reasoning models
              :reasoning (boolean (:reasoning (models/get-model provider model))))
         ftr (footer/make-footer :theme (config-theme config)
                                 :provider fdp
                            ;; pi: the "(auto)" badge reflects the autoCompact
                            ;; setting; overflow-only recovery doesn't count
                                 :auto-compact (get config :auto-compact true))

        ;; Core state (status-indicator/status-root filled in after layout)
        ;; the active editor lives behind an atom so custom editors can swap
        ;; in; the focus home reads both atoms at restore time so a mounted
        ;; dock selector outranks the editor (tui-set-focus-home!)
         current-editor-atom (atom ed)
         dock-stack (atom [])
         cs (state/map->CoreState {:tui t
                                   :ctx ctx
                                   :engine engine
                                   :agents agents
                                  ;; the mode's one subscription to the agent's
                                  ;; events; teardown disposes it
                                   :event-subscription (atom nil)
                                   :chat-history ch
                                   :editor ed
                                   :current-editor-atom current-editor-atom
                                   :header-comp hdr
                                   :loaded-resources-comp lr
                                   :anim-timer (atom nil)
                                   :footer-comp ftr
                                   :footer-provider fdp
                                   :status-indicator nil
                                   :status-current (atom nil)
                                   :status-root nil
                                   :pending-messages-comp pm
                                   :pending-tool-comps pending-tool-comps
                                   :bash-running? (atom false)
                                   :bash-signal (atom false)
                                   :pending-bash-components (atom [])
                                   :pending-messages-container (container/make-container [pm])
                                   :dock-stack dock-stack})]

    ;; Initial loaded-resources sections (rebuilt on /reload)
     (loaded-resources/loaded-resources-set-sections! lr (resources/build-loaded-resource-sections))

    ;; Focus editor
     (tui/tui-set-focus t ed)
    ;; Terminal focus fallback: when nothing capturing holds input, keys
    ;; return to the dock's top focus target if one is mounted, else the
    ;; ACTIVE editor - resolved through atoms so swaps stay live
    ;; (tui-set-focus-home!)
     (tui/tui-set-focus-home! t #(or (dock/top-focus-target (deref dock-stack))
                                     (deref current-editor-atom)))

    ;; Hardware cursor: the setting wins over the KMET_HARDWARE_CURSOR env
    ;; default (pi: showHardwareCursor)
     (tui/tui-set-show-hardware-cursor! t (cfg/get-show-hardware-cursor config))

    ;; clear-on-shrink: the setting wins over the KMET_CLEAR_ON_SHRINK env
    ;; default (pi: terminal.clearOnShrink)
     (tui/tui-set-clear-on-shrink! t (cfg/get-clear-on-shrink config))

    ;; Register builtin slash commands (autocomplete dropdown + dispatch)
     (builtins/register-builtin-commands! config (constantly ctx))

    ;; Autocomplete provider: slash commands + prompt templates + skill
    ;; commands + file paths
     (editor/editor-set-autocomplete-provider! ed
                                               (ac/make-combined-provider
                                                :commands-fn #(vec (concat (commands/get-commands :interactive)
                                                                           (prompts/as-command-maps (prompts/get-prompt-templates))
                                                                          ;; pi: enableSkillCommands — read live,
                                                                          ;; so a /settings toggle takes effect on
                                                                          ;; the next autocomplete open
                                                                           (when (cfg/get-enable-skill-commands config)
                                                                             (skills/as-command-maps (skills/get-skills)))))
                                               ;; a fn: path completion follows a session switch's cwd
                                                :base-path #(fdp/fdp-get-cwd fdp)))
     (editor/editor-set-autocomplete-theme! ed (th/get-select-list-theme (config-theme config)))

    ;; Status indicator: the default editor embeds the active status in its
    ;; own top border (pi: embedWorkingStatus); the standalone layer above
    ;; the editor only appears for a custom editor without a top-border fn.
     (let [si (status-indicator/make-status-indicator
               :theme (config-theme config)
              ;; Embedded color (pi: showWorkingStatusIndicator's colorFn):
              ;; while the ACTIVE editor carries the status, the spinner and
              ;; message take the editor border color — the thinking level —
              ;; resolved per call, so a level change recolors without
              ;; reinstalling; standalone keeps the accent/muted pair.
               :border-color-fn
               (fn []
                 (let [active @current-editor-atom]
                   (when (status-indicator/editor-embeds-status? active)
                     (when-some [bf (:border-fn active)] @bf)))))
          ;; Theme controller (pi: InteractiveThemeController) — created in
          ;; the layout so CoreState carries it for all handlers (slash
          ;; commands, /reload, extension registry); applies the configured
          ;; theme, drives auto light/dark sync via color-scheme
          ;; notifications, and re-themes the app components live on change
          ;; (the on-changed callback — pi: notifyChanged → onChanged). The
          ;; callback runs only after the layout is fully bound.
           tc (theme-ctrl/make-theme-controller
               t config
               (fn [msg] (tui/tui-flash! t msg :duration-ms 3000))
               (fn []
                 (let [current-theme (th/get-current-theme)]
                  ;; key hints read the live theme themselves (kmet.app.keybindings)
                  ;; transcript components subscribe to ui.subs/theme-sub —
                  ;; no walk needed (Stage 5); footer/indicator/resources
                  ;; keep their setter paths
                   (footer/footer-set-theme! ftr current-theme)
                   (status-indicator/status-indicator-set-theme! si current-theme)
                   (loaded-resources/loaded-resources-set-theme! lr current-theme)
                   (expandable-text/expandable-text-rebuild! hdr)
                   (editor/editor-set-autocomplete-theme!
                    ed (th/get-select-list-theme current-theme))
                  ;; the editor's dynamic border is a border-fn baked at
                  ;; construction — re-style it from the active theme so the
                  ;; most prominent themed element changes with the palette
                   (reset! (:border-fn ed)
                           (th/get-thinking-border-color
                            current-theme (or (runtime/thinking-level engine) :off)))
                   (tui/tui-request-render t))))
           cs (assoc cs :status-indicator si :theme-controller tc)
          ;; The default editor renders the session status in its first
          ;; line — its top border (pi: CustomEditor renderTopBorder). The
          ;; hook reads the live status per render, so swaps and spinner
          ;; ticks repaint with the normal frame.
           _ (editor/editor-set-top-border-fn!
              ed
              (fn [{:keys [width hidden-line-count rule border-fn]}]
                (status-indicator/editor-top-border
                 {:indicator (status/current-status-indicator cs)
                  :width width
                  :hidden-line-count hidden-line-count
                  :rule rule
                  :border-fn border-fn})))
          ;; Pi layout (interactive-mode.ts setupUiLayout): the TUI root is a
          ;; single flat document — the transcript (header, loaded resources,
          ;; chat) followed top-to-bottom by pending messages, status, widgets
          ;; above the editor, editor, widgets below, footer. The render loop
          ;; keeps the viewport pinned to the document end: when the document
          ;; grows past the screen height it scrolls natively into the
          ;; terminal scrollback, so the whole interface scrolls together and
          ;; the editor (document end) stays visible at the bottom.
           header-container (container/make-container [sp1 hdr sp1])
           loaded-resources-container (container/make-container [lr])
           chat-container (container/make-container [ch])
           document-container (container/make-container [header-container
                                                         loaded-resources-container
                                                         chat-container])
           pending-messages-container (:pending-messages-container cs)
          ;; extension widget registries (pi: renderWidgets' maps) — read
          ;; tracked by the widget-area roots below, mutated by :set-widget
           widgets-above-atom (atom {})
           widgets-below-atom (atom {})
          ;; The status layer as a mounted DSL tree (dsl.md stage 4): the
          ;; root's reaction re-derives when :status-current swaps, and
          ;; reconcile swaps the child record — no clear/add dance. It
          ;; renders nothing while the active editor embeds the status in
          ;; its top border (the default editor), and the standalone
          ;; indicator otherwise (custom editors).
           status-root (hiccup/root (status-indicator/make-status-area (:status-current cs)
                                                                       si
                                                                       (:current-editor-atom cs)))
          ;; Widget areas as mounted DSL trees (dsl.md stage 4, pi:
          ;; renderWidgets): the widget maps are read tracked, so a
          ;; :set-widget swap re-derives exactly once; the leading spacer is
          ;; a tree element reused across passes via the equal-props
          ;; fast-path (the hand-built default Spacer retires).
           widgets-above-root (hiccup/root
                               (ui-registry/make-widget-area-above widgets-above-atom))
           widgets-below-root (hiccup/root
                               (ui-registry/make-widget-area-below widgets-below-atom))
          ;; The editor dock as a mounted DSL tree (dsl.md stage 4): the
          ;; root re-derives when :dock-stack or the active editor swaps —
          ;; selectors mount/unmount through pure atom writes.
           dock-root (hiccup/root (dock/make-dock-area (:dock-stack cs)
                                                       (:current-editor-atom cs)))
           cs (assoc cs :status-root status-root
                     :dock-root dock-root)]

      ;; Add components in pi's layout-root order: the transcript document
      ;; first, then the dock children top-to-bottom (pending messages,
      ;; status, widgets above, editor, widgets below, footer)
       (tui/tui-add-child t document-container)
       (tui/tui-add-child t pending-messages-container)
       (tui/tui-add-child t status-root)
       (tui/tui-add-child t widgets-above-root)
       (tui/tui-add-child t dock-root)
       (tui/tui-add-child t widgets-below-root)
       (tui/tui-add-child t ftr)

      ;; Global quit (app.quit, ctrl+q by default): the input-listener chain
      ;; runs before the overlay modality guard and focus dispatch, so the
      ;; shortcut quits from anywhere — editor, selector, dialog included.
      ;; Registered before the heal listener so a quit key skips it.
       (tui/tui-add-input-listener t (turn/global-quit-listener t))

      ;; Heal a stale scrollback on the next keystroke when idle (see
      ;; heal-stale-scrollback-when-idle!): short of a scroll event, input is
      ;; the only signal that the user is back at the bottom, and any render
      ;; already pulls the inline viewport there anyway.
       (tui/tui-add-input-listener
        t
        (fn [_data] (turn/heal-stale-scrollback-when-idle! cs) nil))

      ;; Wire editor submit (pi: setupEditorSubmitHandler — the clear
      ;; order is part of the contract, see make-editor-submit-handler)
       (editor/editor-set-on-submit! ed (make-editor-submit-handler cs))

      ;; Editor actions (pi: CustomEditor.onAction) — app keybindings dispatched
      ;; through the editor's action system, which also checks the autocomplete
      ;; dropdown state (e.g. escape closes the dropdown instead of cancelling)
       (editor/editor-set-on-action! ed "app.interrupt"
        ;; pi: onEscape — abort the running agent turn or bash command
                                     (fn [] (turn/handle-cancel cs)))
       (editor/editor-set-on-action! ed "app.exit"
                                     (fn [] (tui/tui-stop t)))
      ;; pi: handleCtrlZ — hand the terminal back, stop the process group
      ;; (SIGTSTP), and restart the TUI when the shell resumes us (SIGCONT)
       (editor/editor-set-on-action! ed "app.suspend"
                                     (fn [] (handle-suspend cs)))
      ;; Force a clearing full redraw on demand — the escape hatch for a
      ;; corrupted or stale screen/scrollback, and the same heuristic rebuild
      ;; the suspend/resume path uses (tui-request-render with force).
       (editor/editor-set-on-action! ed "app.view.forceRedraw"
                                     (fn [] (tui/tui-request-render t true)))
      ;; pi: handleCtrlC — single ctrl+c clears the editor, double within
      ;; 500ms quits
       (let [last-ctrl-c (atom 0)]
         (editor/editor-set-on-action! ed "app.clear"
                                       (fn []
                                         (let [now (concurrent/monotonic-ms)]
                                           (if (< (- now @last-ctrl-c) 500)
                                             (tui/tui-stop t)
                                             (do (reset! last-ctrl-c now)
                                                 (editor/editor-set-text! ed "")
                                                 (tui/tui-request-render t)))))))
       (editor/editor-set-on-action! ed "app.tools.expand"
                                     (fn []
          ;; pi: the same toggle drives tool expansion, the builtInHeader and
          ;; the loaded-resources sections; extended: ctrl+o cycles collapsed →
          ;; quiet → expanded (the ask dialog's listener calls the same cycle).
                                       (cycle-tool-display! cs)))
       (editor/editor-set-on-action! ed "app.thinking.toggle"
                                     (fn []
          ;; pi: showStatus feedback on toggle + persist hideThinkingBlock to
          ;; settings so the state survives restarts (SettingsManager.save;
          ;; write errors are recorded — the toggle still applies)
                                       (let [hidden? (chat-history/chat-history-toggle-thinking-hidden! ch)]
                                         (try (cfg/set-hide-thinking-block! hidden?)
                                              (catch Exception e
                                                (debug/log "Failed to persist hide-thinking-block: " e)))
                                         (chat-history/chat-history-show-status! ch
                                                                                 (str "Thinking blocks: " (if hidden? "hidden" "visible")))
                                         (turn/request-global-reflow-render! cs))))
      ;; pi: cycleThinkingLevel — Shift+Tab cycles through available levels
       (editor/editor-set-on-action! ed "app.thinking.cycle"
                                     (fn []
                                       (if-let [next-level (runtime/cycle-thinking-level! (:engine cs))]
                                         (do (cfg/save-setting! [:thinking] next-level)
                                             (sync-footer-model! cs)
                                             (state/update-editor-border-color! cs next-level)
                                             (chat-history/chat-history-show-status! ch (str "Thinking level: " (name next-level)))
                                             (tui/tui-request-render t))
                                         (chat-history/chat-history-show-status! ch "Current model does not support thinking"))))
       (editor/editor-set-on-action! ed "app.editor.external"
                                     (fn [] (handle-external-editor cs)))
      ;; B.3: Alt+Enter queues a follow-up (pi: handleFollowUp); Alt+Up
      ;; restores queued messages to the editor (pi: handleDequeue)
       (editor/editor-set-on-action! ed "app.message.followUp"
                                     (fn [] (turn/handle-follow-up cs)))
       (editor/editor-set-on-action! ed "app.message.dequeue"
                                     (fn [] (turn/handle-dequeue cs)))
      ;; Model selection/cycling (pi: onAction selectModel/cycleModelForward/
      ;; cycleModelBackward — the session scoped list feeds cycling (set via
      ;; /scoped-models, seeded from --models / settings :models); without
      ;; scoped models all available models cycle; a scoped entry may switch
      ;; the provider)
       (editor/editor-set-on-action! ed "app.model.select"
                                     (fn [] (show-model-selector cs)))
       (editor/editor-set-on-action! ed "app.model.cycleForward"
                                     #(cycle-model-action! cs 1))
       (editor/editor-set-on-action! ed "app.model.cycleBackward"
                                     #(cycle-model-action! cs -1))

      ;; Initialize footer (header content is produced lazily by the
      ;; ExpandableText fns on first render)
       (state/update-footer! cs)

      ;; Extension UI registry (pi: ExtensionUIContext) — installed after the
      ;; layout is live so extensions can drive the UI from event handlers
       (ui-registry/build-extension-ui-registry {:tui t :cs cs}
                                                {:ed ed :ftr ftr :hdr hdr :ch ch
                                                 :sp1 sp1 :fdp fdp
                                                 :header-container header-container
                                                 :widgets-above-atom widgets-above-atom
                                                 :widgets-below-atom widgets-below-atom}
                                                tc)

      ;; Expose the fully-built CoreState to the agent on-event handler (for
      ;; :status events) — after the layout assocs so the status
      ;; indicator/container and theme controller are present
       (reset! cs-ref cs)

      ;; The loop's events on this agent's ctx are the front end's stream
      ;; (pi: the TUI session's event handlers): streaming deltas, the run's
      ;; lifecycle, compaction and the settle that ends the turn. One
      ;; subscription for the whole vocabulary, after cs-ref so a handler
      ;; resolves the built CoreState.
       (reset! (:event-subscription cs)
               (events/on-every (:ctx engine)
                                (make-agent-event-handler
                                 {:chat-history ch :tui t :cs-ref cs-ref
                                  :pending-tool-comps pending-tool-comps})))

      ;; Restore a --continue session into the chat history AND the agent
      ;; context (pi: renderInitialMessages from buildContextEntries) — the
      ;; agent's in-memory messages must mirror the session branch or the
      ;; next LLM call loses the whole restored conversation (steered and
      ;; follow-up messages included)
       (when (and session (seq @(:entries session)))
         (session-admin/restore-session! cs session true))

       cs))))
