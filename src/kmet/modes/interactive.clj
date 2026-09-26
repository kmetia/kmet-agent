(ns kmet.modes.interactive
  "Interactive TUI mode — main layout, agent integration, command handling,
   session browsing, bash commands, external editor.
   pi: modes/interactive/interactive-mode.ts."
  (:require [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as term]
            [kmet.tui.theme :as th]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.tui.components.container :as container]
            [kmet.tui.hiccup :as hiccup]
            [kmet.libs.reakt :as r]
            [kmet.modes.interactive.state :as state]
            [kmet.modes.interactive.status :as status]
            [kmet.modes.interactive.turn :as turn]
            [kmet.modes.interactive.session-admin :as session-admin]
            [kmet.modes.interactive.commands :as builtins]
            [kmet.modes.interactive.resources :as resources]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.loaded-resources :as loaded-resources]
            [kmet.app.ui.pending-messages :as pending-messages]
            [kmet.app.ui.status-indicator :as status-indicator]
            [kmet.app.ui.tool-execution :as tool-execution]
            [kmet.app.ui.subs :as subs]
            [kmet.app.ui.image-block :as image-block]
            [kmet.app.ui.custom-dialog-adapter :as cda]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.external-editor :refer [editor-text-get editor-text-get-expanded
                                                 editor-text-set! handle-external-editor]]
            [kmet.app.ui.model-catalog :as model-catalog]
            [kmet.app.ui.model-selector :refer [show-model-selector sync-footer-model!]]
            [kmet.app.ui.session-selector :refer [show-session-selector]]
            [kmet.app.ui.footer :as footer]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.app.loop :as agent]
            [kmet.ai.models :as models]
            [kmet.app.session :as session]
            [kmet.app.tools.core :as tools]
            [kmet.app.keybindings :as app-kb]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.config :as cfg]
            [kmet.ai.api.shared :as shared]
            [kmet.app.skills :as skills]
            [kmet.libs.context :as context]
            [kmet.app.prompts :as prompts]
            [kmet.app.commands :as commands]
            [kmet.app.extensions :as extensions]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.packages :as packages]
            [kmet.tui.autocomplete :as ac]
            [kmet.debug :as debug]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.tui.components.spinner :as spinner]
            [kmet.libs.process :as process]))

(declare build-extension-ui-registry
         maybe-show-cache-miss-notice!
         make-widget-area-above make-widget-area-below)

;; CoreState lives in kmet.modes.interactive.state; the constructors
;; stay re-exported here for consumers of this namespace (tests).
(def ->CoreState state/->CoreState)
(def map->CoreState state/map->CoreState)

;; ─── External editor (pi: handleOpenExternalEditor) ────────────────────────

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

(defn- build-layout
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
                              (make-widget-area-above widgets-above-atom))
          widgets-below-root (hiccup/root
                              (make-widget-area-below widgets-below-atom))
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
      (build-extension-ui-registry {:tui t :cs cs}
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

;; ─── Extension UI registry (pi: ExtensionUIContext) ────────────────────────
;; build-layout installs this registry after the layout is live; extensions
;; call the kmet.app.extensions ui-* fns, which dispatch through it. All
;; closures capture the layout pieces they mutate.

(defn- make-widget-area-above
  "The above-editor widget strip as a fn component (dsl.md stage 4, pi:
   renderWidgets): a leading spacer plus the registered widgets. The widget
   map is read tracked — a :set-widget swap re-derives exactly once; the
   spacer is a tree element reused via the equal-props fast-path, widgets
   splice as foreign records (owned by the extension flow)."
  [widgets-atom]
  (fn [_props]
    ;; SEQ of sibling roots — widgets must be SIBLINGS of the spacer, not
    ;; its children (:spacer is a leaf tag; children on a leaf throws)
    (concat [[:spacer {:lines 1}]]
            (vals (r/tracked-deref widgets-atom)))))

(defn- make-widget-area-below
  "Below-editor widget area (pi: renderWidgets) — the registered widgets
   only, no leading spacer."
  [widgets-atom]
  (fn [_props]
    (vals (r/tracked-deref widgets-atom))))

(defn- dispose-dialog-component!
  "Dispose an extension dialog/widget value (kmet.app.ui.custom-dialog-adapter/
   dispose-component! — duck-typed :dispose, else the protocol multimethod)."
  [component]
  (cda/dispose-component! component))

(defn- make-extension-widget-component
  "Widget content forms (pi: renderWidgets' map values):
   - hiccup element tree → compiled once to a stamped component (spliceable
     into the widget strips; its dispose unwinds owned cleanups)
   - factory fn → (content t theme); a duck-typed render map result is
     adapted to a CustomDialogAdapter record so it splices into the strips'
     trees — component/record results pass through untouched"
  [t content]
  (if (fn? content)
    (let [c (content t (th/get-current-theme))]
      ;; a duck-typed map cannot splice into the widget strips' trees —
      ;; adapt it to a record; components/trees pass through untouched
      (if (and (map? c) (not (record? c)) (fn? (:render c)))
        (cda/map->CustomDialogAdapter
         {:render-fn (:render c)
          :handle-input-fn (:handle-input c)
          :invalidate-fn (:invalidate c)
          :dispose-fn (:dispose c)})
        c))
    (hiccup/compile-tree content)))

(defn- normalize-custom-component
  "Accept an IComponent, a plain render map {:render :handle-input
   :invalidate :dispose} (pi: custom() accepts both a Component and a
   duck-typed object), or a hiccup element tree — maps and trees are
   wrapped in a CustomDialogAdapter RECORD so the result always splices
   into hiccup trees by record? (reify wrappers would trip reconcile:
   bb's satisfies? misses reifies from other evaluation contexts even
   though dispatch works on them). Trees compile once here and the
   adapter's dispose unwinds them."
  [x]
  (cond
    ;; structural branches first: maps/trees are recognized reliably,
    ;; satisfies? only decides for foreign component objects (records
    ;; satisfy robustly; a hostile reify failing it fails LOUD at the
    ;; ui-custom call site instead of crashing a render pass)
    (and (map? x) (not (record? x)) (fn? (:render x)))
    (cda/map->CustomDialogAdapter
     {:render-fn (:render x)
      :handle-input-fn (:handle-input x)
      :invalidate-fn (:invalidate x)
      :dispose-fn (:dispose x)})
    (vector? x)
    (let [comp (hiccup/compile-tree x)]
      ;; static trees take no input; invalidate clears the compiled caches
      (cda/map->CustomDialogAdapter
       {:render-fn #(protocols/render comp %)
        :invalidate-fn #(protocols/invalidate comp)
        :dispose-fn #(hiccup/dispose-tree! comp)}))
    (satisfies? tui/IComponent x) x))

(defn- transfer-editor!
  "Copy the app editor's wiring onto a custom editor component (pi:
   setCustomEditorComponent). Components implementing IEditorComponent get
   the method-based transfer (pi: setText/setPaddingX/setAutocomplete…);
   others get pi's duck-typed property copy of the record fields, plus the
   CustomEditor action-handler/keybinding extras in both cases."
  [app-ed custom-ed keybindings]
  (if (satisfies? protocols/IEditorComponent custom-ed)
    (do (protocols/editor-set-on-submit! custom-ed @(:on-submit app-ed))
        (protocols/editor-set-on-change! custom-ed @(:on-change app-ed))
        (protocols/editor-set-padding-x! custom-ed @(:padding-x app-ed))
        (protocols/editor-set-autocomplete-max-visible!
         custom-ed @(:autocomplete-max-visible app-ed))
        (when-let [p @(:autocomplete-provider app-ed)]
          (protocols/editor-set-autocomplete-provider! custom-ed p)))
    (doseq [field [:on-submit :on-change :padding-x
                   :autocomplete-provider :terminal-rows-atom]]
      (when (contains? custom-ed field)
        (reset! (get custom-ed field) @(get app-ed field)))))
  ;; pi: appearance properties are assigned whenever the target has them,
  ;; regardless of protocol (borderColor, kmet's dynamic-height source)
  (doseq [field [:border-fn :terminal-rows-atom]]
    (when (contains? custom-ed field)
      (reset! (get custom-ed field) @(get app-ed field))))
  (when (contains? custom-ed :action-handlers)
    (doseq [[action-id f] @(:action-handlers app-ed)]
      (swap! (:action-handlers custom-ed) assoc action-id f)))
  (when (contains? custom-ed :keybindings)
    (reset! (:keybindings custom-ed) keybindings))
  nil)

(defn- normalize-autocomplete-provider
  "Accept either an AutocompleteProvider or a duck-typed map with
   :get-suggestions (fn [state]) and optional :apply-completion,
   :should-trigger-file-completion, :get-trigger-characters (pi-style
   object). Returns a provider or nil for anything else."
  [x]
  (cond
    (satisfies? ac/AutocompleteProvider x) x
    (map? x) (reify ac/AutocompleteProvider
               (get-suggestions [_ lines cursor-line cursor-col opts]
                 (when-let [f (:get-suggestions x)]
                   (f {:lines lines :cursor-line cursor-line
                       :cursor-col cursor-col :opts opts})))
               (apply-completion [_ lines cursor-line cursor-col item prefix]
                 (if-let [f (:apply-completion x)]
                   (f {:lines lines :cursor-line cursor-line
                       :cursor-col cursor-col :item item :prefix prefix})
                   ;; default: replace the prefix with the item value
                   (let [line (nth lines cursor-line "")
                         start (max 0 (- cursor-col (count prefix)))
                         new-line (str (subs line 0 start) (:value item)
                                       (subs line cursor-col))]
                     {:lines (assoc lines cursor-line new-line)
                      :cursor-line cursor-line
                      :cursor-col (+ start (count (:value item)))})))
               (should-trigger-file-completion [_ lines cursor-line cursor-col]
                 (boolean (and (:should-trigger-file-completion x)
                               ((:should-trigger-file-completion x)
                                {:lines lines :cursor-line cursor-line
                                 :cursor-col cursor-col}))))
               (get-trigger-characters [_]
                 (vec (:get-trigger-characters x []))))
    :else nil))

(defn- build-extension-ui-registry
  "Create the ExtensionUIContext implementation for the live layout
   (pi: createExtensionUIContext). Returns the capability map installed via
   extensions/set-ui-registry!."
  [{:keys [tui cs]}
   {:keys [ed ftr hdr ch sp1 fdp header-container
           widgets-above-atom widgets-below-atom]}
   theme-controller]
  (let [t tui
        custom-footer-atom (atom nil)
        custom-header-atom (atom nil)
        custom-dialog-comp (atom nil)
        ;; the ACTIVE editor — the default or a swapped-in custom editor
        ;; (pi: this.editor is rebound by setCustomEditorComponent); the atom
        ;; lives on CoreState so action handlers outside this closure (e.g.
        ;; the external-editor flow) see the active editor too
        current-editor-atom (:current-editor-atom cs)
        editor-factory-atom (atom nil)
        extension-autocomplete-factories (atom [])
        terminal-input-unsubscribers (atom [])
        hide-dialog (fn []
                      ;; the dialog's own close disposes it (its custody) —
                      ;; clear! only unwinds what a selector mode-switch left.
                      ;; Focus needs no restore here: the dock's
                      ;; ::focus-guard watch hands input back to the active
                      ;; editor the moment the occupant leaves, and the
                      ;; tracked dock atom re-derives the area
                      (dock/clear! cs)
                      (tui/tui-request-render t))
        rebuild-autocomplete-provider! (fn []
                                         ;; pi: setupAutocompleteProvider — each
                                         ;; extension factory wraps the provider
                                         ;; chain; nil results keep the base
                                         (let [base (ac/make-combined-provider
                                                     :commands-fn #(vec (concat
                                                                         (commands/get-commands)
                                                                         (prompts/as-command-maps (prompts/get-prompt-templates))
                                                                         ;; pi: enableSkillCommands — read live at
                                                                         ;; autocomplete open
                                                                         (when (cfg/get-enable-skill-commands (:config cs))
                                                                           (skills/as-command-maps (skills/get-skills)))))
                                                     :base-path #(fdp/fdp-get-cwd fdp))
                                               provider (reduce (fn [prov factory]
                                                                  (or (normalize-autocomplete-provider
                                                                       (factory prov))
                                                                      prov))
                                                                base
                                                                @extension-autocomplete-factories)]
                                           (when (contains? @current-editor-atom
                                                            :autocomplete-provider)
                                             (editor/editor-set-autocomplete-provider!
                                              @current-editor-atom provider))
                                           nil))
        footer-data {:get-git-branch (fn [] (fdp/fdp-get-git-branch fdp))
                     :get-extension-statuses (fn []
                                               @(:extension-statuses-atom ftr))
                     :on-branch-change (fn [_f] (fn []))}
        registry
        {:notify (fn [message _type]
                   (tui/tui-flash! t message)
                   nil)
         ;; /session-style output for extension commands: append an :info
         ;; message to the chat history — part of the live transcript, no
         ;; overlay and nothing to dismiss (never sent to the LLM, never
         ;; persisted to the session; pi has no equivalent — kmet-specific).
         :chat-info (fn [label content]
                      (chat-history/chat-history-add-message!
                       ch {:role :info :label label :content (str content)})
                      (tui/tui-request-render t)
                      nil)
         ;; pi: ctx.ui.custom — mount an extension-built component (built
         ;; with kmet.tui.*) as an overlay or in the editor dock; the
         ;; factory gets (tui theme keybindings close) and close resolves
         ;; the returned promise
         :custom (fn [factory {:keys [overlay overlay-options on-handle]}]
                   (let [p (promise)
                         saved-text (editor-text-get @current-editor-atom)
                         closed (atom false)
                         close (fn [result]
                                 (when-not @closed
                                   (reset! closed true)
                                   ;; pi: dispose?() runs when the dialog
                                   ;; closes, before the component is
                                   ;; removed — a map/record :dispose fn
                                   (when-let [component @custom-dialog-comp]
                                     (dispose-dialog-component! component))
                                   (reset! custom-dialog-comp nil)
                                   (if overlay
                                     (tui/tui-hide-overlay t)
                                     (do (hide-dialog)
                                         (editor-text-set!
                                          @current-editor-atom saved-text)))
                                   (deliver p result)))]
                     (try
                       ;; pi: custom() accepts a Promise<Component> — deref
                       ;; with a timeout; a timeout or nil factory result
                       ;; hits the same error path as a throwing factory
                       (let [raw (factory t (th/get-current-theme) (tui-kb/get-global-keybindings) close)
                             raw (if (instance? clojure.lang.IDeref raw)
                                   (deref raw 5000 ::timeout)
                                   raw)
                             component (normalize-custom-component raw)]
                         (when (or (nil? component) (= ::timeout raw))
                           (when-not @closed
                             (throw (ex-info "ui-custom factory returned no component (or timed out)" {}))))
                         (when-not @closed
                           ;; a previous live dialog (defensive — normal flow
                           ;; closes first) unwinds exactly like widget replace
                           (when-let [prev @custom-dialog-comp]
                             (dispose-dialog-component! prev))
                           (reset! custom-dialog-comp component)
                           (if overlay
                             (let [opts (if (fn? overlay-options)
                                          (overlay-options)
                                          overlay-options)
                                   handle (tui/tui-show-overlay t component opts)]
                               (when on-handle (on-handle handle)))
                             (dock/mount! cs component nil {:borrowed? true}))))
                       (catch Exception e
                         (when-not @closed
                           (reset! closed true)
                           (when-not overlay (hide-dialog))
                           (tui/tui-flash! t (str "Extension UI error: " (ex-message e)))
                           (deliver p nil))))
                     p))
         ;; footer-set-extension-status! swaps a track!-watched atom —
         ;; the watch schedules the frame (§3.4), no manual poke
         :set-status (fn [key text]
                       (footer/footer-set-extension-status! ftr key text))
         :set-widget (fn [key content options]
                       (let [placement (or (:placement options) :above-editor)
                             m (if (= :below-editor placement) widgets-below-atom widgets-above-atom)
                             existing (get @m key)]
                         ;; pi: removeExisting disposes the old widget on
                         ;; replace AND remove — skipping :remove would leak
                         ;; its cleanups. Duck-typed maps carry :dispose;
                         ;; compiled trees are IComponents.
                         (when existing
                           (dispose-dialog-component! existing))
                         (swap! m dissoc key)
                         (when content
                           (swap! m assoc key
                                  (make-extension-widget-component t content)))
                         ;; the area roots track the widget maps — the swap
                         ;; alone re-derives them and schedules the frame
                         ;; (§3.4); pre-first-frame registrations land in the
                         ;; roots' first render anyway
                         nil))
         :set-footer (fn [factory]
                       (when-let [cf @custom-footer-atom]
                         (when-let [dispose (:dispose cf)]
                           (try (dispose) (catch Exception _)))
                         (tui/tui-remove-child t cf))
                       (tui/tui-remove-child t ftr)
                       (if factory
                         (let [cf (factory t (th/get-current-theme) footer-data)]
                           (reset! custom-footer-atom cf)
                           (tui/tui-add-child t cf))
                         (do (reset! custom-footer-atom nil)
                             (tui/tui-add-child t ftr)))
                       (tui/tui-request-render t))
         :set-header (fn [factory]
                       (when @custom-header-atom
                         (when-let [dispose (:dispose @custom-header-atom)]
                           (try (dispose) (catch Exception _)))
                         (reset! custom-header-atom nil))
                       (let [child (if factory (factory t (th/get-current-theme)) hdr)]
                         (container/container-clear header-container)
                         (container/container-add-child header-container sp1)
                         (container/container-add-child header-container child)
                         (container/container-add-child header-container sp1)
                         (when-not factory
                           (expandable-text/expandable-text-rebuild! hdr))
                         (tui/tui-request-render t)))
         :set-title (fn [title]
                      (when-let [term @(:terminal t)] (term/set-title! term title)))
         :on-terminal-input (fn [handler]
                              (tui/tui-add-input-listener t handler)
                              (let [unsub (fn [] (tui/tui-remove-input-listener t handler))]
                                (swap! terminal-input-unsubscribers conj unsub)
                                unsub))
         :set-editor-text (fn [text]
                            (editor-text-set! @current-editor-atom text)
                            (tui/tui-request-render t))
         :get-editor-text (fn [] (editor-text-get-expanded @current-editor-atom))
         :paste-to-editor (fn [text]
                            (tui/handle-input @current-editor-atom
                                              (str "\u001b[200~" text "\u001b[201~")))
         :set-working-indicator (fn [options]
                                  (spinner/spinner-set-indicator!
                                   (:spinner (:status-indicator cs)) options)
                                  (tui/tui-request-render t))
         :set-working-message (fn [message]
                                (status-indicator/status-indicator-set-text! (:status-indicator cs)
                                                                             (or message "Working"))
                                (tui/tui-request-render t))
         :set-working-visible (fn [visible?]
                                ;; Pi: setWorkingVisible — clearStatusIndicator("working")
                                ;; when hiding (kind-gated: a transient retry/
                                ;; compaction/share indicator stays), re-show the
                                ;; working indicator when showing (only while the
                                ;; turn runs, and only when it isn't already the
                                ;; active status — pi checks the active kind, so
                                ;; re-showing never restarts a spinning clock).
                                ;; both branches schedule through the
                                ;; guarded :status-current swap when real
                                (if visible?
                                  (when (and @(:running-turn? cs)
                                             (not (status-indicator/status-indicator-active?
                                                   (:status-indicator cs))))
                                    (status/activate-working-indicator! cs))
                                  (status/clear-status-indicator! cs :working)))
         :set-hidden-thinking-label (fn [label]
                                      ;; one reset! on the shared label atom;
                                      ;; assistant messages' watches schedule
                                      (chat-history/chat-history-set-hidden-thinking-label!
                                       ch label))
         :set-editor-component (fn [factory]
                                 (let [current-text (editor-text-get @current-editor-atom)]
                                   ;; pi parity: setCustomEditorComponent runs
                                   ;; disposeActiveSelector() then clears the dock —
                                   ;; the swap disposes whatever it held, and a
                                   ;; displaced selector's done() goes inert
                                   (dock/clear! cs)
                                   (if factory
                                     (let [new-ed (factory t (th/get-current-theme) (tui-kb/get-global-keybindings))]
                                       (transfer-editor! ed new-ed (tui-kb/get-global-keybindings))
                                       (editor-text-set! new-ed current-text)
                                       (tui/tui-set-focus t new-ed)
                                       ;; tracked by the dock area: the swap alone re-derives
                                       (reset! current-editor-atom new-ed))
                                     (do (editor-text-set! ed current-text)
                                         (tui/tui-set-focus t ed)
                                         (reset! current-editor-atom ed)))
                                   (reset! editor-factory-atom factory)
                                   (tui/tui-request-render t)))
         :add-autocomplete-provider (fn [factory]
                                      (swap! extension-autocomplete-factories conj factory)
                                      (rebuild-autocomplete-provider!))
         :set-theme (fn [theme-or-name]
                      ;; pi: setTheme — Theme instances go through
                      ;; setThemeInstance; names through setThemeName (which
                      ;; disables auto-sync). kmet has no settings write
                      ;; path — the switch is applied live only.
                      (if (instance? kmet.tui.theme.Theme theme-or-name)
                        (theme-ctrl/set-theme-instance! theme-controller theme-or-name)
                        (theme-ctrl/set-theme-name! theme-controller theme-or-name true)))
         :get-tools-expanded (fn [] (chat-history/chat-history-get-tool-expanded ch))
         :set-tools-expanded (fn [expanded?]
                               ;; the flag swap invalidates every tool
                               ;; component's watch, which schedules the frame
                               (let [current? (chat-history/chat-history-get-tool-expanded ch)]
                                 (when (not= current? expanded?)
                                   (chat-history/chat-history-toggle-tool-expanded! ch)
                                   (turn/request-global-reflow-render! cs))))
         :get-tool-display-mode (fn [] (chat-history/chat-history-get-tool-display-mode ch))
         :set-tool-display-mode (fn [mode]
                                  (when (not= mode (chat-history/chat-history-get-tool-display-mode ch))
                                    (chat-history/chat-history-set-tool-display-mode! ch mode)
                                    (try (cfg/set-tool-display-mode! mode)
                                         (catch Exception e
                                           (debug/log "Failed to persist tool-display-mode: " e)))
                                    (expandable-text/expandable-text-set-expanded!
                                     (:header-comp cs) (= :expanded mode))
                                    (loaded-resources/loaded-resources-set-expanded!
                                     (:loaded-resources-comp cs) (= :expanded mode))
                                    (turn/request-global-reflow-render! cs)))
         ;; pi: registerShortcut — a raw key-id bound as a priority editor
         ;; action, checked before every builtin app binding (escape
         ;; included). The keybinding definition is registered on the global
         ;; manager so key-hints and user overrides resolve. Last
         ;; registration of a key wins; deregistration removes only its own.
         :register-shortcut! (fn [key-id {:keys [description handler]}]
                               (let [kmgr (tui-kb/get-global-keybindings)
                                     key-id (str/lower-case (str key-id))]
                                 (tui-kb/register-definition!
                                  kmgr key-id
                                  {:default-keys [key-id]
                                   :description (or description "Extension shortcut")})
                                 (editor/editor-set-priority-action!
                                  ed key-id
                                  (fn []
                                    (try
                                      (handler (extensions/build-extension-context))
                                      (catch Exception e
                                        (tui/tui-flash!
                                         t (str "Extension shortcut error: " (ex-message e)))))))
                                 (fn []
                                   (tui-kb/unregister-definition! kmgr key-id)
                                   (editor/editor-set-priority-action! ed key-id nil))))
         ;; Agent control (pi: ctx.setModel / getThinkingLevel /
         ;; setThinkingLevel / sendUserMessage / getActiveTools /
         ;; setActiveTools)
         :set-model (fn [model]
                      (if (and model (models/has-configured-auth model))
                        (let [ag @(:agent-state cs)
                              old-model (models/get-model @(:provider ag) @(:model ag))]
                          (reset! (:provider ag) (:provider model))
                          (agent/set-model! ag (:id model))
                          (let [new-thinking (agent/switch-thinking-level old-model model @(:thinking ag) nil)]
                            (agent/set-thinking-level! ag new-thinking)
                            (cfg/set-default-model! (:provider model) (:id model))
                            (sync-footer-model! cs)
                            (state/update-editor-border-color! cs new-thinking)
                            (tui/tui-request-render (:tui cs)))
                          true)
                        false))
         :set-thinking-level (fn [level]
                               (when (contains? #{:off :minimal :low :medium
                                                  :high :xhigh :max} level)
                                 (agent/set-thinking-level! @(:agent-state cs) level)
                                 (sync-footer-model! cs)
                                 (state/update-editor-border-color! cs level)
                                 (tui/tui-request-render (:tui cs)))
                               nil)
         :get-thinking-level (fn []
                               @(:thinking @(:agent-state cs)))
         :send-user-message (fn [text & [{:keys [deliver-as expand-prompt-templates?]}]]
                              (let [ag @(:agent-state cs)
                                    ;; pi: prompt() throws while compaction is
                                    ;; in progress — extension messages cannot
                                    ;; queue into the UI compaction queue.
                                    _ (when @(:compacting? ag)
                                        (throw (ex-info "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."
                                                        {:type :compaction-in-progress})))
                                    ;; pi: prompt() with expandPromptTemplates —
                                    ;; extension commands execute immediately
                                    ;; (consuming the message), then skill
                                    ;; commands + prompt templates expand.
                                    ;; kmet defaults to NO expansion (the
                                    ;; existing behavior); opt in explicitly.
                                    text (if expand-prompt-templates?
                                           (turn/expand-user-message-text cs text)
                                           text)]
                                (when text
                                  (if (= :idle @(:status ag))
                                    ;; pi: sendUserMessage always triggers a
                                    ;; turn when idle
                                    (turn/start-agent-run! cs text)
                                    (if (= :steer deliver-as)
                                      (agent/steer! ag text)
                                      (agent/follow-up! ag text))))
                                ;; both updates schedule their own frames
                                (status/update-pending-messages! cs)
                                (state/update-footer! cs)
                                nil))
         ;; pi: sendMessage — a custom message: persisted as a custom_message
         ;; session entry, injected into the agent context (sent to the LLM
         ;; as a user message; rendered when :display) and optionally
         ;; triggering a turn. Idle + trigger-turn starts the run (the
         ;; message is already in context); busy queues via deliver-as
         ;; (:steer injects immediately — the next LLM call sees it;
         ;; anything else defers to the next turn).
         :send-message! (fn [message & [opts]]
                          (let [ag @(:agent-state cs)
                                ;; pi: sendMessage → prompt — custom messages
                                ;; cannot be submitted while compaction is in
                                ;; progress (the compaction queue is UI-only).
                                _ (when @(:compacting? ag)
                                    (throw (ex-info "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."
                                                    {:type :compaction-in-progress})))
                                custom-type (or (:custom-type message) :custom)
                                display (if (nil? (:display message)) true (:display message))
                                msg {:role :custom
                                     :custom-type custom-type
                                     :content (:content message)
                                     :display display
                                     :details (:details message)}]
                            (when (:session ag)
                              (session/append-custom-message-entry!
                               (:session ag) custom-type (:content message)
                               display (:details message)))
                            (agent/add-context-message! ag msg)
                            (when (:trigger-turn opts)
                              (if (= :idle @(:status ag))
                                (turn/start-agent-run! cs)
                                (if (= :steer (:deliver-as opts))
                                  ;; already in context — the next LLM call
                                  ;; sees it (pi: steer into the current run)
                                  nil
                                  (agent/follow-up! ag msg))))
                            ;; both updates schedule their own frames
                            (status/update-pending-messages! cs)
                            (state/update-footer! cs)
                            true))
         :get-active-tools (fn []
                             @(:enabled-tools @(:agent-state cs)))
         :set-active-tools (fn [names]
                             ;; agent state only — no display depends on
                             ;; this synchronously
                             (agent/set-active-tools! @(:agent-state cs) names)
                             nil)
         ;; Extension context (pi: ExtensionContext) — captures the live
         ;; layout/agent state per call; the headless default in
         ;; extensions.clj covers everything else (mode/has-ui/…)
         :build-context (fn []
                          ;; capture the agent-state ATOM: session swaps
                          ;; assoc a NEW record onto the atom, so the old
                          ;; record's :session field goes stale (compact);
                          ;; the atom fields are shared and always current
                          (let [ag-atom (:agent-state cs)
                                ag @ag-atom]
                            {:mode :interactive
                             :has-ui true
                             :cwd (fdp/fdp-get-cwd fdp)
                             :model @(:model ag)
                             :scoped-models @(:scoped-models ag)
                             :thinking-level @(:thinking ag)
                             :is-idle (fn [] (= :idle @(:status @ag-atom)))
                             :has-pending-messages (fn []
                                                     (boolean
                                                      (agent/has-queued-messages?
                                                       @ag-atom)))
                             :signal (fn [] @(:signal @ag-atom))
                             :abort (fn []
                                      (when-not (= :idle @(:status @ag-atom))
                                        ;; pi: ctx.abort() restores queued
                                        ;; steering/follow-up messages to the
                                        ;; editor before aborting — a message
                                        ;; only reaches the chat once the loop
                                        ;; consumes it, so clearing the queues
                                        ;; without restoring would drop it
                                        ;; entirely (restoreQueuedMessagesToEditor
                                        ;; {abort:true})
                                        (turn/restore-queued-messages! cs)
                                        (agent/cancel-turn @ag-atom)))
                             :shutdown (fn [] (tui/tui-stop t))
                             :get-context-usage (fn []
                                                  ;; pi: null without an
                                                  ;; active session
                                                  (when-let [_ (fdp/fdp-get-session fdp)]
                                                    (let [tokens (fdp/fdp-context-tokens fdp)
                                                          window (fdp/fdp-get-context-window fdp)]
                                                      {:tokens tokens
                                                       :context-window window
                                                       :percent (when (and tokens window
                                                                           (pos? window))
                                                                  (int (* 100.0 (/ tokens window))))})))
                             :compact (fn [& [{:keys [custom-instructions
                                                      on-complete on-error]}]]
                                        (future
                                          (try
                                            (let [r (agent/compact-context! @ag-atom custom-instructions :manual)]
                                              (if (and (= :failed r) on-error)
                                                ;; pi: compact() throws on
                                                ;; summarization failure →
                                                ;; onError fires
                                                (on-error (ex-info "Context compaction failed: the summarization call did not return a summary."
                                                                   {:type :compaction-failed}))
                                                (when on-complete (on-complete {:result r}))))
                                            (catch Exception e
                                              (when on-error (on-error e))))))
                             :get-system-prompt (fn [] @(:system @ag-atom))
                             :get-system-prompt-options (fn []
                                                          (let [config (:config cs)]
                                                            ;; pi: getSystemPromptOptions — the prompt's build
                                                            ;; inputs; cwd is the runtime one (a switched
                                                            ;; session's), context files stay the launch dir's
                                                            {:custom-prompt (cfg/get-custom-prompt config)
                                                             :append-prompt (cfg/get-append-system-prompt config)
                                                             :cwd (state/runtime-cwd cs)
                                                             :context-files (context/load-project-context-files
                                                                             (cfg/get-agent-dir)
                                                                             (str (fs/cwd)))}))
                             :wait-for-idle (fn []
                                              (if (= :idle @(:status @ag-atom))
                                                nil
                                                (let [p (promise)]
                                                  (future
                                                    (loop []
                                                      (if (= :idle @(:status @ag-atom))
                                                        (deliver p true)
                                                        (do (Thread/sleep 100) (recur)))))
                                                  p)))
                             :reload (fn [] (builtins/handle-reload cs nil))
                             :new-session (fn [& _]
                                            (session-admin/handle-new-session cs)
                                            {:cancelled false})
                             :fork (fn [entry-id & _]
                                     (if entry-id
                                       (do (session-admin/fork-at! cs entry-id) {:cancelled false})
                                       {:cancelled true}))
                             :navigate-tree (fn [target-id & [{:keys [summarize
                                                                      custom-instructions
                                                                      replace-instructions
                                                                      label]}]]
                                              ;; a run in flight would render its events into
                                              ;; the new branch (the /tree command refuses too)
                                              (if (state/turn-running? cs)
                                                {:cancelled true}
                                                (if-let [sess @(:session-atom cs)]
                                                  (if-let [entry (session/get-entry sess
                                                                                    target-id)]
                                                    (do (session-admin/navigate-tree! cs sess entry
                                                                                      (boolean summarize)
                                                                                      custom-instructions
                                                                                      (boolean replace-instructions)
                                                                                      label)
                                                        {:cancelled false})
                                                    {:cancelled true})
                                                  {:cancelled true})))
                             :switch-session (fn [session-path & _]
                                               ;; a run in flight would render its events into the
                                               ;; switched session (the /resume command refuses too)
                                               (if (state/turn-running? cs)
                                                 {:cancelled true}
                                                 (try
                                                   (let [sess (session/load-session session-path)
                                                         ;; pi: emitBeforeSwitch (reason :resume) —
                                                         ;; extensions may cancel the switch
                                                         result (event-bus/emit-event!
                                                                 {:type :session-before-switch
                                                                  :reason :resume
                                                                  :target-session-file session-path})]
                                                     (if (:cancel result)
                                                       {:cancelled true}
                                                       (do (session-admin/restore-session! cs sess true)
                                                           {:cancelled false})))
                                                   (catch Exception _ {:cancelled true}))))
                             :is-project-trusted (fn [] false)}))
         :reset (fn []
                  ;; pi: resetExtensionUI — dispose widgets, restore
                  ;; footer/header/editor, clear statuses + working
                  ;; customization, drop terminal input listeners
                  (doseq [m [widgets-above-atom widgets-below-atom]]
                    (doseq [w (vals @m)]
                      (when-let [dispose (:dispose w)]
                        (try (dispose) (catch Exception _)))))
                  (reset! widgets-above-atom {})
                  (reset! widgets-below-atom {})
                  (when @custom-footer-atom
                    (when-let [dispose (:dispose @custom-footer-atom)]
                      (try (dispose) (catch Exception _)))
                    (tui/tui-remove-child t @custom-footer-atom)
                    (reset! custom-footer-atom nil)
                    (tui/tui-add-child t ftr))
                  (when @custom-header-atom
                    (when-let [dispose (:dispose @custom-header-atom)]
                      (try (dispose) (catch Exception _)))
                    (reset! custom-header-atom nil)
                    (container/container-clear header-container)
                    (container/container-add-child header-container sp1)
                    (container/container-add-child header-container hdr)
                    (container/container-add-child header-container sp1)
                    (expandable-text/expandable-text-rebuild! hdr))
                  (when-let [component @custom-dialog-comp]
                    (dispose-dialog-component! component)
                    (reset! custom-dialog-comp nil))
                  (doseq [unsub @terminal-input-unsubscribers]
                    (try (unsub) (catch Exception _)))
                  (reset! terminal-input-unsubscribers [])
                  (doseq [key (keys @(:extension-statuses-atom ftr))]
                    (footer/footer-set-extension-status! ftr key nil))
                  (spinner/spinner-set-indicator! (:spinner (:status-indicator cs)) nil)
                  (status-indicator/status-indicator-set-text! (:status-indicator cs) "Working")
                  (chat-history/chat-history-set-hidden-thinking-label! ch nil)
                  (reset! extension-autocomplete-factories [])
                  (rebuild-autocomplete-provider!)
                  (when @editor-factory-atom
                    (let [current-text (editor-text-get @current-editor-atom)]
                      (editor-text-set! ed current-text)
                      (tui/tui-set-focus t ed)
                      (reset! current-editor-atom ed))
                    (reset! editor-factory-atom nil))
                  ;; restore any open dialog
                  (dock/clear! cs)
                  (tui/tui-set-focus t ed)
                  (reset! current-editor-atom ed)
                  (when (tui/tui-has-overlay? t) (tui/tui-hide-overlay t))
                  (tui/tui-request-render t))}]
    (extensions/set-ui-registry! registry)
    ;; Live session + context injection for extensions (pi:
    ;; ctx.sessionManager / custom messages flowing through the agent loop).
    ;; Installed once with the registry; reload mutates the same CoreState
    ;; (agent, session atom), so the wiring stays valid across reloads —
    ;; only /new, /resume, fork and clone re-register the session.
    (extensions/set-session! @(:session-atom cs))
    (extensions/set-context-sink!
     (fn [msg] (agent/add-context-message! @(:agent-state cs) msg)))
    (extensions/set-entry-sink!
     (fn [entry]
       (when-let [rendered (session-admin/run-entry-renderer
                            (extensions/get-entry-renderer (:custom-type entry)) entry)]
         (chat-history/chat-history-add-message!
          (:chat-history cs)
          (session-admin/renderer-result->message rendered))
         ;; The sink appends to the untracked chat message vector; request
         ;; a frame after the append rather than relying on an older watch.
         (when t
           (tui/tui-request-render t)))))
    registry))

;; ─── Run ───────────────────────────────────────────────────────────────────

(defn- maybe-show-cache-miss-notice!
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

(defn run
  "Start the interactive TUI with the given config and CLI opts.
   Loads extensions, resolves the session (:resume/:continue/new), builds the
   layout, and runs the TUI loop until quit. Cleans up the TUI and tracked
   child processes on error, then rethrows for the top-level handler.
   pi: cli.js dispatch to interactive mode."
  [config opts]
  (let [tui-ref (atom nil)]
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
            cs (build-layout config session)]
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
        (throw e)))))