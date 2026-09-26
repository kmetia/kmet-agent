(ns kmet.modes.interactive.layout
  "TUI layout construction for the interactive mode: the agent event
   handler and build-layout's widget tree, session wiring, and input
   listeners."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.debug :as debug]
            [kmet.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.theme :as th]
            [kmet.tui.terminal :as term]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.autocomplete :as ac]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.ai.models :as models]
            [kmet.ai.api.shared :as shared]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.commands :as commands]
            [kmet.app.extensions :as extensions]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.packages :as packages]
            [kmet.app.prompts :as prompts]
            [kmet.app.skills :as skills]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.app.tools.core :as tools]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.external-editor :refer [handle-external-editor]]
            [kmet.app.ui.footer :as footer]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.app.ui.image-block :as image-block]
            [kmet.app.ui.loaded-resources :as loaded-resources]
            [kmet.app.ui.model-catalog :as model-catalog]
            [kmet.app.ui.model-selector :refer [show-model-selector sync-footer-model!]]
            [kmet.app.ui.pending-messages :as pending-messages]
            [kmet.app.ui.status-indicator :as status-indicator]
            [kmet.app.ui.subs :as subs]
            [kmet.app.ui.tool-execution :as tool-execution]
            [kmet.app.context :as context]
            [kmet.modes.interactive.commands :as builtins]
            [kmet.modes.interactive.resources :as resources]
            [kmet.modes.interactive.session-admin :as session-admin]
            [kmet.modes.interactive.state :as state]
            [kmet.modes.interactive.status :as status]
            [kmet.modes.interactive.turn :as turn]
            [kmet.modes.interactive.ui-registry :as ui-registry]))

(defn maybe-show-cache-miss-notice!
  "pi: maybeShowCacheMissNotice — when :show-cache-miss-notices is on and
   the completed turn (RUN-MESSAGES) paid for a significant prompt-cache
   miss, add a transcript notice. Display floor: >= 20k tokens re-billed
   (pi's `missedTokens < 20_000 && missedCost < 0.1` is simplified to the
   token arm — kmet has no per-message price lookup here)."
  [cs run-messages]
  (when (and (cfg/get-show-cache-miss-notices (:config cs))
             ;; the run must have produced an assistant message with usage
             (some #(and (= :assistant (:role %)) (:usage %)) run-messages))
    (when-let [sess @(:session-atom cs)]
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
   Extracted from build-layout so the contract that every vocabulary event
   is consumed is testable (kmet.modes.test-interactive)."
  [{:keys [chat-history tui cs-ref pending-tool-comps]}]
  (fn [evt]
    (case (:type evt)
      :tool-execution-start
      ;; Pi: create pending component once, update in place.
      ;; The streaming placeholder finalizes here (the tool box lands after
      ;; it) — mark the message as tool-call-bearing FIRST so a tool-call-only
      ;; assistant message stays invisible instead of bubbling '(no response)'
      ;; (pi: hasToolCalls; loop-continue turns are usually tool-call-only).
      (let [msg {:role :tool
                 :name (:tool-name evt)
                 :args (:args evt {})
                 :content ""
                 :is-error false}]
        (chat-history/chat-history-mark-streaming-tool-calls! chat-history)
        (chat-history/chat-history-finalize-streaming! chat-history)
        (let [comp (chat-history/chat-history-add-message! chat-history msg)]
          ;; Store tool call ID for correlation
          (reset! (:tool-call-id-atom comp) (:tool-call-id evt))
          ;; Args are complete when received (kmet: no streaming args)
          (tool-execution/tool-execution-set-args-complete! comp)
          ;; Mark execution started so pending bg + timer activate now
          (tool-execution/tool-execution-mark-execution-started! comp)
          ;; Pi: pendingTools.set(toolCallId, component) — parallel tool
          ;; calls each own a component; updates/ends correlate by id
          (swap! pending-tool-comps assoc (:tool-call-id evt) comp))
        ;; mount poke stays: the new message lands in the untracked
        ;; messages-atom, so no watch exists for it yet
        (tui/tui-request-render tui))
      :tool-execution-update
      ;; Pi: live partial content from streaming tools (bash). The
      ;; elapsed counter itself ticks via the bash render-result's own
      ;; 1s interval (pi: setInterval → context.invalidate), so a
      ;; silent long-running tool still updates Elapsed steadily — this
      ;; event only pushes the new output chunks.
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
          (tui/tui-request-render tui)))
      :status
      ;; Pi: agent status changes keep the footer/status
      ;; layer in sync via the :status event (update-footer!'s invalidate
      ;; schedules the frame)
      (when-let [cs @cs-ref]
        (state/update-footer! cs))
      :loop-guard
      ;; Repeat-loop guard tripped (kmet-specific): show a warning line in
      ;; the transcript — the run has already settled (the final text
      ;; carries the explanation)
      (do
        (chat-history/chat-history-add-message!
         chat-history
         {:role :warning
          :content (or (:details evt) "Stopped: repeat-loop guard tripped")})
        (tui/tui-request-render tui))
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
      :queue-update
      ;; Queued steering/follow-up messages changed (pi:
      ;; queue_update → updatePendingMessagesDisplay; the tracked-atom
      ;; swap schedules the frame)
      (when-let [cs @cs-ref]
        (status/update-pending-messages! cs))
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
        (when (and @(:running-turn? cs)
                   (not= :working (:kind @(:status-current cs))))
          (status/activate-working-indicator! cs)))
      :auto-retry-start
      ;; Show the retry countdown; the failed attempt's partial
      ;; text stays visible (pi: auto_retry_start only swaps in a
      ;; RetryStatusIndicator — the errored block remains in the
      ;; chat and the retried stream opens a fresh message below it)
      ;; (the :status-current swap schedules its own frame)
      (when-let [cs @cs-ref]
        (status/show-status-indicator!
         cs :retry
         (status-indicator/make-retry-status-indicator
          (:attempt evt) (:max-attempts evt) (:delay-ms evt)
          :cancel-hint (status/fmt-key-display
                        (app-kb/key-text "app.interrupt")))))
      :auto-retry-end
      ;; Retry finished (pi: auto_retry_end →
      ;; clearStatusIndicator("retry")). Kind-gated: when
      ;; the retried call already started (turn-start
      ;; revived the working indicator) this no-ops and
      ;; the working spinner keeps spinning.) The kind-gated nil-swap
      ;; schedules its own frame.
      (when-let [cs @cs-ref]
        (status/clear-status-indicator! cs :retry))
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
      ;; Rebuild the chat history to mirror the replaced
      ;; context; custom messages honor the display flag
      ;; (pi: display controls TUI rendering — hidden
      ;; ones stay in the LLM context only)
      (do (chat-history/chat-history-rebuild!
           chat-history
           (mapcat (fn [m]
                     (let [m (if (and (= :custom (:role m)) (:display m))
                               (if-let [rendered (session-admin/run-message-renderer
                                                  (extensions/get-message-renderer
                                                   (:custom-type m)) m)]
                                 (session-admin/renderer-result->message rendered)
                                 (assoc m :role :info
                                        :content (session-admin/custom-message-text m)
                                        :images (image-block/content-images (:content m))
                                        :label (:custom-type m)))
                               m)]
                       ;; pi: addCompactionCostNotice — the billing line
                       ;; follows its summary message
                       (if-let [notice (session-admin/compaction-cost-notice (:config @cs-ref)
                                                                             (:role m) (:usage m))]
                         [m notice]
                         [m])))
                   (remove #(and (= :custom (:role %))
                                 (not (:display %)))
                           (:messages evt))))
          (tui/tui-request-render tui))
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
        :info (let [m (:message evt)
                    text (if (string? (:content m))
                           (:content m)
                           (str/join
                            (for [b (:content m)
                                  :when (= :text (:type b))]
                              (:text b))))]
                (chat-history/chat-history-insert-before-streaming! chat-history
                                                                    (assoc m
                                                                           :content text
                                                                           :images (image-block/content-images (:content m))))
                (tui/tui-request-render tui))
                            ;; extension custom messages (pi: custom messages
                            ;; render when display=true — a registered message
                            ;; renderer overrides the labeled info box; same
                            ;; rule as the session-restore path)
        :custom (do (when (:display (:message evt))
                      (let [m (:message evt)]
                        (chat-history/chat-history-insert-before-streaming!
                         chat-history
                         (if-let [rendered (session-admin/run-message-renderer
                                            (extensions/get-message-renderer
                                             (:custom-type m)) m)]
                           (session-admin/renderer-result->message rendered)
                           (assoc m :role :info
                                  :content (session-admin/custom-message-text m)
                                  :images (image-block/content-images (:content m))
                                  :label (:custom-type m))))))
                    (tui/tui-request-render tui))
        nil)
      ;; Remaining vocabulary events need no UI action in
      ;; kmet's architecture: streaming text/thinking
      ;; arrives via the on-text/on-thinking callbacks,
      ;; message finalization + the idle transition happen
      ;; in on-agent-done, errors surface via on-error, and
      ;; the /model + Ctrl+P cycling paths sync the footer
      ;; themselves. Every event must still be consumed — a
      ;; case with no matching clause throws, and the
      ;; exception is swallowed by the run future, leaving
      ;; the UI stuck on "Working" forever.

      :agent-start
      ;; Pi: agent_start → setProgress(true) (showTerminalProgress gated)
      (when-let [cs @cs-ref]
        (turn/set-terminal-progress! cs true))
      :message-update nil
      :message-end nil
      :turn-end nil
      :agent-settled nil
      :session-compact-failed nil
      :error nil
      :model-select nil
      :thinking-level-select nil
      ;; Trailing default expression (SCI's case rejects the :default keyword)
      nil)))

(defn build-layout
  "Create TUI layout and return CoreState."
  [config session]
  (let [tui-term (term/create-terminal)
        t (tui/create-tui tui-term)

        ;; Resolve model and provider from config
        provider (cfg/get-provider config)
        model (models/resolve-config-model config)

        ;; The runtime working directory (pi: AgentSession._cwd — the cwd
        ;; the resumed/continued session was recorded in, else the process
        ;; cwd). The atom is shared by the footer data provider (the footer
        ;; pwd, the extension ctx.cwd) and the chat history (tool-component
        ;; path displays), so apply-session-cwd! switches both with one
        ;; write.
        cwd (or (session/session-cwd session) (str (fs/cwd)))
        cwd-atom (atom cwd)

        ;; Load skills and prompt templates (pi: the unified resolution —
        ;; top-level entries, auto roots, then packages)
        _ (packages/load-skills!)
        _ (packages/load-prompts!)
        system-prompt-opts {:custom-prompt (cfg/get-custom-prompt config)
                            :append-prompt (cfg/get-append-system-prompt config)
                            ;; the prompt's cwd line is the runtime cwd (the
                            ;; resumed session's), but everything project-scoped —
                            ;; context files included — stays with the launch dir
                            :cwd cwd
                            :context-files (context/load-project-context-files
                                            (cfg/get-agent-dir) (str (fs/cwd)))
                            :tools (vals (tools/get-all-tools))}
        system-prompt (apply skills/build-system-prompt
                             (mapcat identity system-prompt-opts))

        ;; Migrate legacy keybinding ids in keybindings.edn (pi: migrations
        ;; runner calls migrateKeybindingsConfigFile at startup)
        _ (app-kb/migrate-keybindings-config-file!)

        ;; Initialize keybindings (global singleton for key-hint + input
        ;; handling); persisted overrides load from keybindings.edn
        ;; (pi: KeybindingsManager.create)
        _ (let [kmgr (app-kb/create-agent-keybindings-manager)]
            (tui-kb/set-global-keybindings! kmgr)
            (app-kb/set-key-hint-theme-fns!
             #(th/dim %)
             #(th/fg (cfg/get-theme config) :muted %)))

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
        ch (chat-history/make-chat-history
            :cwd-fn #(deref cwd-atom)
            :thinking-hidden (cfg/get-hide-thinking-block config)
            :tool-display-mode (cfg/get-tool-display-mode config)
            :output-pad (cfg/get-output-pad config))
        pending-tool-comps (atom {})  ;; Pi: pendingTools Map (tool-call-id → comp)
        cs-ref (atom nil)             ;; CoreState, filled after layout (for :status events)

        ;; Context window of the active model — drives the footer % and the
        ;; proactive auto-compaction check (pi: state.model.contextWindow)
        ctx-window (or (:context-window (models/get-model provider model))
                       (:context-window config))

        ;; Agent state
        ag (agent/make-agent-state
            :model model
            :provider provider
            :system system-prompt
            :system-prompt-opts system-prompt-opts
            :session session
            :context-window ctx-window
            :compact-reserve-tokens (or (:compact-reserve-tokens config) 16384)
            :compact-token-threshold (:compact-token-threshold config)
            ;; get (not :kw) — an absent key must hit make-agent-state's
            ;; :or default, not be overridden with nil
            :auto-compact (get config :auto-compact true)
            :steering-mode (or (:steering-mode config) :all)
            :follow-up-mode (or (:follow-up-mode config) :all)
            :keep-recent-tokens (or (:keep-recent-tokens config) 20000)
            :http-idle-timeout-ms (:http-idle-timeout-ms config)
            :http-total-timeout-ms (get config :http-total-timeout-ms)
            ;; pi: images.blockImages — strip image blocks from provider calls
            :block-images (cfg/get-block-images config)
            :thinking (let [model-rec (models/get-model provider model)
                            raw-level (:thinking config :off)]
                        (if (:reasoning model-rec)
                          (shared/clamp-thinking-level model-rec raw-level)
                          raw-level))
            ;; pi: retry settings (settings.edn :retry block — enabled gates
            ;; max-retries to 0)
            :max-retries (let [retry (cfg/get-retry-settings config)]
                           (if (:enabled retry) (:max-retries retry) 0))
            :base-delay-ms (:base-delay-ms (cfg/get-retry-settings config))
            ;; Repeat-loop guard (kmet-specific): settings.edn :loop-guard
            ;; block — enabled gates threshold to 0 (off)
            :loop-guard-enabled (:enabled (cfg/get-loop-guard-settings config))
            :loop-guard-threshold (:threshold (cfg/get-loop-guard-settings config))
            :thinking-loop-guard-enabled (get config :thinking-loop-guard-enabled true)
            ;; Extension tool hooks (pi: tool_call / tool_result transforms):
            ;; chained in registration order; later hooks see earlier
            ;; rewrites. Captured at layout build — extensions register at
            ;; load, before the agent state exists.
            :before-tool-call (fn [ctx]
                                (loop [hooks (extensions/get-tool-call-hooks)
                                       blocked nil
                                       args (:args ctx)]
                                  (if-let [hook (first hooks)]
                                    (let [r (try (hook (assoc ctx :args args))
                                                 (catch Exception e
                                                   {:block true
                                                    :reason (str "tool-call hook error: "
                                                                 (ex-message e))}))]
                                      (cond
                                        (:block r)
                                        (recur (next hooks)
                                               (or blocked {:block true :reason (:reason r)})
                                               args)
                                        (contains? r :args)
                                        (recur (next hooks) blocked (:args r))
                                        :else
                                        (recur (next hooks) blocked args)))
                                    (or blocked
                                        (when (not= args (:args ctx)) {:args args})))))
            :after-tool-call (fn [ctx]
                               (reduce (fn [result hook]
                                         (if-let [r (try (hook (assoc ctx :result result
                                                                      :is-error (:is-error result false)))
                                                         (catch Exception e
                                                           {:content (str "tool-result hook error: "
                                                                          (ex-message e))
                                                            :is-error true}))]
                                           (cond-> result
                                             (:content r) (assoc :content (:content r))
                                             (contains? r :is-error) (assoc :is-error (:is-error r)))
                                           result))
                                       (:result ctx)
                                       (extensions/get-tool-result-hooks)))
            :on-event (make-agent-event-handler
                       {:chat-history ch :tui t :cs-ref cs-ref
                        :pending-tool-comps pending-tool-comps}))
            ;; Session scoped model list for cycle-model! / the scoped-models
            ;; selector / the /model scope toggle (pi: resolveModelScope →
            ;; session.scopedModels at startup — parsed.models ?? settings
            ;; enabledModels, full "provider/id" refs so cycling can switch
            ;; providers)
        _ (agent/init-scoped-models! ag config)
        ;; B.1: welcome header — ExpandableText with compact/full variants
        ;; (pi: builtInHeader), toggled by app.tools.expand
        hdr (let [mode (cfg/get-tool-display-mode config)]
              (expandable-text/make-expandable-text
               state/fmt-header-compact state/fmt-header-full
               :expanded? (= :expanded mode) :padding-x 1 :padding-y 0))
        ;; B.2: loaded resources between header and chat (pi: showLoadedResources)
        lr (loaded-resources/make-loaded-resources :theme (cfg/get-theme config)
                                                   :expanded? (= :expanded (cfg/get-tool-display-mode config)))
        ;; B.3: queued steering/follow-up display (pi: updatePendingMessagesDisplay)
        pm (pending-messages/make-pending-messages
            :hint (status/fmt-key-display (app-kb/key-text "app.message.dequeue")))
        ;; B.5: editor dynamic height — max(5, rows*0.3) via :terminal-rows;
        ;; the fixed :height fallback stays at the default 12;
        ;; border color reflects the current thinking level (pi: updateEditorBorderColor)
        ed (tui/make-editor :padding-x (cfg/get-editor-padding-x config)
                            :autocomplete-max-visible (cfg/get-autocomplete-max-visible config)
                            :terminal-rows (fn [] (term/rows @(:terminal t)))
                            :border-fn (th/get-thinking-border-color
                                        (cfg/get-theme config)
                                        (or @(:thinking ag) :off)))
        ;; B.6: footer data provider + footer (pi: FooterComponent; the
        ;; model line wraps to its own line when the stats line is too narrow)
        fdp (fdp/make-footer-data-provider
             :cwd-atom cwd-atom
             :session session
             :provider-count (count (distinct (map :provider (model-catalog/scoped-or-available-models ag))))
             ;; Phase 2: context window from the resolved Model record, falling
             ;; back to the settings value when the model is unknown (pi footer
             ;; contextPercentDisplay)
             :context-window ctx-window
             :model @(:model ag) :provider @(:provider ag) :thinking @(:thinking ag)
             ;; pi: the thinking suffix renders only for reasoning models
             :reasoning (boolean (:reasoning (models/get-model provider model))))
        ftr (footer/make-footer :theme (cfg/get-theme config)
                                :provider fdp
                            ;; pi: the "(auto)" badge reflects the autoCompact
                            ;; setting; overflow-only recovery doesn't count
                                :auto-compact (get config :auto-compact true))

        ;; Core state (status-indicator/status-root filled in after layout)
        ;; the active editor lives behind an atom so custom editors can swap
        ;; in; the focus home reads both atoms at restore time so a mounted
        ;; dock selector outranks the editor (tui-set-focus-home!)
        current-editor-atom (atom ed)
        dock-current (atom nil)
        cs (state/map->CoreState {:tui t
                                  :agent-state (atom ag)
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
                                  :session-atom (atom session)
                                  :running-turn? (atom false)
                                  :compaction-queued (atom [])
                                  :config config
                                  :pending-tool-comps pending-tool-comps
                                  :bash-running? (atom false)
                                  :bash-signal (atom false)
                                  :pending-bash-components (atom [])
                                  :pending-messages-container (container/make-container [pm])
                                  :dock-current dock-current})]

    ;; Initial loaded-resources sections (rebuilt on /reload)
    (loaded-resources/loaded-resources-set-sections! lr (resources/build-loaded-resource-sections))

    ;; Focus editor
    (tui/tui-set-focus t ed)
    ;; Terminal focus fallback: when nothing capturing holds input, keys
    ;; return to the dock's selector if one is mounted, else the ACTIVE
    ;; editor - resolved through atoms so swaps stay live
    ;; (tui-set-focus-home!)
    (tui/tui-set-focus-home! t #(or (:component (deref dock-current))
                                    (deref current-editor-atom)))

    ;; Hardware cursor: the setting wins over the KMET_HARDWARE_CURSOR env
    ;; default (pi: showHardwareCursor)
    (tui/tui-set-show-hardware-cursor! t (cfg/get-show-hardware-cursor config))

    ;; clear-on-shrink: the setting wins over the KMET_CLEAR_ON_SHRINK env
    ;; default (pi: terminal.clearOnShrink)
    (tui/tui-set-clear-on-shrink! t (cfg/get-clear-on-shrink config))

    ;; Register builtin slash commands (autocomplete dropdown + dispatch)
    (builtins/register-builtin-commands! config)

    ;; Autocomplete provider: slash commands + prompt templates + skill
    ;; commands + file paths
    (editor/editor-set-autocomplete-provider! ed
                                              (ac/make-combined-provider
                                               :commands-fn #(vec (concat (commands/get-commands)
                                                                          (prompts/as-command-maps (prompts/get-prompt-templates))
                                                                          ;; pi: enableSkillCommands — read live,
                                                                          ;; so a /settings toggle takes effect on
                                                                          ;; the next autocomplete open
                                                                          (when (cfg/get-enable-skill-commands config)
                                                                            (skills/as-command-maps (skills/get-skills)))))
                                               ;; a fn: path completion follows a session switch's cwd
                                               :base-path #(fdp/fdp-get-cwd fdp)))
    (editor/editor-set-autocomplete-theme! ed (th/get-select-list-theme (cfg/get-theme config)))

    ;; Status indicator: the default editor embeds the active status in its
    ;; own top border (pi: embedWorkingStatus); the standalone layer above
    ;; the editor only appears for a custom editor without a top-border fn.
    (let [si (status-indicator/make-status-indicator
              :theme (cfg/get-theme config)
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
                  ;; key-hint theme fns first — the header rebuild re-runs
                  ;; the content fns that use them
                  (app-kb/set-key-hint-theme-fns!
                   #(th/dim %) #(th/fg current-theme :muted %))
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
                           current-theme (or @(:thinking ag) :off)))
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
          ;; root re-derives when :dock-current or the active editor swaps —
          ;; selectors mount/unmount through pure atom writes.
          dock-root (hiccup/root (dock/make-dock-area (:dock-current cs)
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

      ;; Wire editor submit
      (editor/editor-set-on-submit! ed
                                    (fn [text]
                                      (when text
                                        (turn/handle-submit cs text)
                                        (editor/editor-set-text! ed "")
                                        (tui/tui-request-render t))))

      ;; Editor actions (pi: CustomEditor.onAction) — app keybindings dispatched
      ;; through the editor's action system, which also checks the autocomplete
      ;; dropdown state (e.g. escape closes the dropdown instead of cancelling)
      (editor/editor-set-on-action! ed "app.interrupt"
        ;; pi: onEscape — abort the running agent turn or bash command
                                    (fn [] (turn/handle-cancel cs)))
      (editor/editor-set-on-action! ed "app.exit"
                                    (fn [] (tui/tui-stop t)))
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
                                        (let [now (System/currentTimeMillis)]
                                          (if (< (- now @last-ctrl-c) 500)
                                            (tui/tui-stop t)
                                            (do (reset! last-ctrl-c now)
                                                (editor/editor-set-text! ed "")
                                                (tui/tui-request-render t)))))))
      (editor/editor-set-on-action! ed "app.tools.expand"
                                    (fn []
          ;; pi: the same toggle drives tool expansion, the builtInHeader, and
          ;; the loaded-resources sections (getStartupExpansionState) —
          ;; extended: ctrl+o cycles collapsed → expanded → quiet. Header,
          ;; resources and the info banner treat quiet as collapsed.
                                      (let [mode (chat-history/chat-history-cycle-tool-display! ch)
                                            expanded? (= :expanded mode)]
                                        (try (cfg/set-tool-display-mode! mode)
                                             (catch Exception e
                                               (debug/log "Failed to persist tool-display-mode: " e)))
                                        (expandable-text/expandable-text-set-expanded! hdr expanded?)
                                        (loaded-resources/loaded-resources-set-expanded! lr expanded?)
                                        (chat-history/chat-history-show-status! ch
                                                                                (str "Tool display: " (name mode)))
                                        (turn/request-global-reflow-render! cs))))
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
                                      (let [ag @(:agent-state cs)
                                            model (models/get-model @(:provider ag) @(:model ag))
                                            levels (if model
                                                     (shared/get-supported-thinking-levels model)
                                                     [:off])]
                                        (if (<= (count levels) 1)
                                          (chat-history/chat-history-show-status! ch "Current model does not support thinking")
                                          (let [current @(:thinking ag)
                                                idx (or (first (keep-indexed (fn [i l] (when (= l current) i)) levels))
                                                        0)
                                                next-level (nth levels (mod (inc idx) (count levels)))]
                                            (agent/set-thinking-level! ag next-level)
                                            (cfg/save-setting! [:thinking] next-level)
                                            (sync-footer-model! cs)
                                            (state/update-editor-border-color! cs next-level)
                                            (chat-history/chat-history-show-status! ch (str "Thinking level: " (name next-level)))
                                            (tui/tui-request-render t))))))
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
                                    (fn []
                                      (if (agent/cycle-model! @(:agent-state cs) 1)
                                        (do (cfg/set-default-model! @(:provider @(:agent-state cs))
                                                                    @(:model @(:agent-state cs)))
                                            (sync-footer-model! cs))
                                        (chat-history/chat-history-show-status!
                                         (:chat-history cs)
                                         (if (seq @(:scoped-models @(:agent-state cs)))
                                           "Only one model in scope"
                                           "Only one model available")))))
      (editor/editor-set-on-action! ed "app.model.cycleBackward"
                                    (fn []
                                      (if (agent/cycle-model! @(:agent-state cs) -1)
                                        (do (cfg/set-default-model! @(:provider @(:agent-state cs))
                                                                    @(:model @(:agent-state cs)))
                                            (sync-footer-model! cs))
                                        (chat-history/chat-history-show-status!
                                         (:chat-history cs)
                                         (if (seq @(:scoped-models @(:agent-state cs)))
                                           "Only one model in scope"
                                           "Only one model available")))))

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

      ;; Restore a --continue session into the chat history AND the agent
      ;; context (pi: renderInitialMessages from buildContextEntries) — the
      ;; agent's in-memory messages must mirror the session branch or the
      ;; next LLM call loses the whole restored conversation (steered and
      ;; follow-up messages included)
      (when (and session (seq @(:entries session)))
        (session-admin/restore-session! cs session true))

      cs)))
