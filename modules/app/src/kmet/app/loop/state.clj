(ns kmet.app.loop.state
  "The agent's mutable state and the operations that write it.

   AgentState holds the conversation (:messages), the session, the run's
   message accumulator, the steering/follow-up queues, the pending custom/
   bash/next-turn messages, the active tools, and the model/thinking
   settings. Every mutation goes through a function here: the driver
   (kmet.app.loop), completion, tool execution and compaction call these
   operations instead of touching an atom directly.

   Queues (pi: steeringQueue / followUpQueue):
     :steering  — user messages injected mid-run. Polled between turns (after
                   tool results, before the next LLM call). Use steer!.
     :follow-up — user messages processed after the current run settles.
                   Polled when the inner loop drains; the run continues with
                   them. Use follow-up!.
   :steering-mode / :follow-up-mode control how many queued messages are
   drained per poll cycle: :all takes everything, :one-at-a-time takes one.
   Defaults are :all for steering and :one-at-a-time for follow-up.
   Both queues are also drained at the prompt's attempt boundary when an
   :agent-end handler queued a message: the next attempt starts from it (pi:
   agent.continue's continue() — steering first, then follow-up).

   Model management (pi: setModel / cycleModel): set-scoped-models! sets the
   session scoped model list; cycle-model! moves through it emitting
   :model-select. The :get-api-key atom holds a dynamic API key resolver."
  (:require [kmet.app.loop.message :as message]
            [kmet.app.config :as cfg]
            [kmet.app.events :as events]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.system-prompt :as system-prompt]
            [kmet.app.retry :as retry]
            [kmet.app.session.core :as session]
            [kmet.app.tools.registry :as tools]
            [kmet.ai.models :as models]
            [kmet.srce.core :as srce]))

(defrecord AgentState [status        ;; :idle :thinking :executing :done :error
                       messages      ;; atom of conversation message vectors
                       session       ;; Session record or nil
                       model         ;; model identifier
                       provider      ;; provider keyword (registry id, e.g. :opencode-go)
                       system        ;; system prompt string
                       signal        ;; atom for cancellation
                       thinking      ;; :off :low :medium :high :max
                       on-event      ;; callback for state updates
                       base-url      ;; full endpoint URL override (default: derived from the Model record)
                       api-type      ;; wire api override (:openai-completions | :openai-responses | :anthropic-messages | :google-generative-ai)
                       steering      ;; atom of vector of queued steer messages
                       follow-up     ;; atom of vector of queued follow-up messages
                       pending-custom ;; atom of vector of custom messages deferred while the agent
                                      ;; is streaming — flushed at the end of the turn so one never
                                      ;; lands between a tool call and its result (pi:
                                      ;; _pendingCustomMessages)
                       pending-next-turn ;; atom of vector of custom messages injected with the next
                                         ;; prompt, alongside its user message (pi:
                                         ;; _pendingNextTurnMessages)
                       active-call      ;; atom of {:promise p :partials f} while an LLM call is in flight (for cancel)
                       cfg              ;; atom of a config MAP — runtime-tunable knobs, replaced wholesale:
                                        ;;   {:max-retries int :base-delay-ms int :max-agent-delay-ms int
                                        ;;    :http-idle-timeout-ms int
                                        ;;    :steering-mode :all|:one-at-a-time
                                        ;;    :follow-up-mode :all|:one-at-a-time
                                        ;;    :auto-compact bool :context-window int-or-nil}
                       retry-count      ;; atom of int: retries performed for the in-flight LLM call
                       before-tool-call ;; atom of (fn [ctx]) → {:block true :reason} | nil
                       after-tool-call  ;; atom of (fn [ctx]) → override map | nil
                       system-prompt-override ;; atom of string or nil (per-run override, pi: _systemPromptOverride)
                       transform-context      ;; atom of (fn [messages]) → messages (pi: transformContext)
                       prepare-next-turn      ;; atom of (fn [ctx]) → update map | nil (pi: prepareNextTurn)
                       prepare-request        ;; atom of (fn [request]) → update map | nil (pi: config.prepareRequest —
                                              ;; runs before every provider request, after prepareNextTurn)
                       finish-turn            ;; atom of (fn [ctx]) → :end | :continue | nil — the loop's
                                              ;; per-turn decision (pi: finishTurn)
                       last-outcome           ;; atom of :completed | :error | :aborted: the last turn's
                                              ;; outcome, reported by :agent-before-settle (pi: _lastActivityOutcome)
                       get-api-key            ;; atom of (fn [provider]) → key | nil (dynamic auth)
                       scoped-models          ;; atom of vector of "provider/id" full ids (scoped list for cycling; pi: session.scopedModels)
                       overflow-recovered     ;; atom of bool: context-overflow compacted once this run
                       compact-token-threshold ;; int or nil: compact when estimated tokens exceed this
                       keep-recent-tokens     ;; int: cut-point budget in tokens (pi: keepRecentTokens, default 20000)
                       compacting?           ;; atom of bool: a compaction is in progress (escape cancels it)
                       compaction-signal     ;; atom of bool: abort the in-flight compaction (pi: AbortController —
                                             ;; escape aborts only the compaction; the run continues)
                       compaction-blocked    ;; atom of bool: an automatic compaction in this prompt aborted
                                             ;; or failed — automatic checks stand down until the next prompt
                                             ;; (pi: skipAbortedCheck / _overflowRecoveryAttempted, extended)
                       compaction-error      ;; atom of string or nil: the last compaction failure message
                                             ;; (pi: the Error compact() rejects with) — read by the
                                             ;; /compact and extension error paths
                       pending-bash          ;; atom of vector of bash entries queued while streaming
                       run-messages          ;; atom of {run-token [message...]}: the messages
                                             ;; appended during an in-flight run (pi: newMessages,
                                             ;; agent-loop), keyed by the run that appended them so
                                             ;; an older run's unwind can never report (or clear) a
                                             ;; newer run's list. A mid-run context replacement
                                             ;; (compaction, prepareNextTurn :context) does not
                                             ;; touch it, so agent-end still reports the messages
                                             ;; the replacement removed from the context
                       run-token             ;; atom of the current run's token, or nil outside a
                                             ;; run — nil makes appends context-only
                       system-prompt-opts    ;; atom of the build-system-prompt options map (pi: _baseSystemPromptOptions)
                       system-prompt-fn      ;; the builder the opts map is applied to (see the
                                             ;; :system-prompt-fn option); a plain value, because a
                                             ;; state's builder never changes mid-run
                       loop-guard            ;; atom of per-run repeat-loop guard state:
                                             ;;   {:window [signature...] :suppressed n}
                       ctx                   ;; the ctx this run reads its loop stages and tool
                                             ;; table from and scopes its events to: a child ctx
                                             ;; for an agent's run, and the app root when a process
                                             ;; runs one (docs/architecture.md § Multi-session server mode).
                                             ;; There is no default: a state with no ctx has no tool table
                       ])

(defn make-agent-state
  "Create a new agent state.
   opts: :model, :provider, :system, :session, :on-event,
         :thinking, :base-url, :api-type, :steering-mode, :follow-up-mode,
         :max-retries (default 3), :base-delay-ms (default 2000),
     :max-agent-delay-ms (default 60000 — pi maxAgentDelayMs, the backoff cap),
     :branch-summary-reserve-tokens (default 16384 — pi
     branchSummary.reserveTokens, the branch-summary input budget),
         :before-tool-call, :after-tool-call, :system-prompt-override,
         :transform-context, :prepare-next-turn, :prepare-request, :finish-turn,
         :get-api-key, :scoped-models (default []),
         :steering-mode (default :all), :follow-up-mode (default
         :one-at-a-time),
         :system-prompt-fn (fn the state calls to rebuild the prompt when it must
         — an active-tools change — with :system-prompt-opts as its keyword
         arguments; default kmet.app.system-prompt/build-system-prompt, and a
         caller that builds its own passes that instead —
         :system-prompt-opts (build-system-prompt options map, pi:
         _baseSystemPromptOptions),
         :compact-token-threshold, :auto-compact (default true, pi:
         autoCompact), :context-window, :compact-reserve-tokens (default 16384,
         pi: reserveTokens), :keep-recent-tokens (default 20000, pi:
         keepRecentTokens), :compact-model-overrides (default {} — pi
         compaction.modelOverrides, exact \"provider/modelId\" keys),
         :http-idle-timeout-ms (default 120000, pi:
         httpIdleTimeoutMs; 0 disables), :http-total-timeout-ms (default 0
         disables the total deadline; nil follows the idle timeout),
         :block-images (default false, pi: images.blockImages — strip image
         blocks from provider calls; transcript and session untouched),
         :default-tools (resolved :default-tools selection — the built-in
         tools active at startup, pi: defaultTools; nil = every built-in
         active, see kmet.app.tools.registry/resolve-default-tools),
         :ctx (the ctx this run reads its loop stages and tool table from and
         scopes its events to — a run names one, because a state without a ctx
         has no tool table; an agent's run passes its own child ctx, which
         carries its own :loop-guard and :retry-policy stages, and a process
         run passes the app root)"
  [& {:keys [model provider system session on-event thinking base-url api-type steering-mode follow-up-mode max-retries base-delay-ms max-agent-delay-ms branch-summary-reserve-tokens before-tool-call after-tool-call system-prompt-override transform-context prepare-next-turn prepare-request finish-turn get-api-key scoped-models system-prompt-opts system-prompt-fn compact-token-threshold context-window compact-reserve-tokens keep-recent-tokens compact-model-overrides http-idle-timeout-ms http-total-timeout-ms auto-compact loop-guard-enabled loop-guard-threshold thinking-loop-guard-enabled block-images default-tools ctx]
      :or {provider :opencode-go
           thinking :off
           steering-mode :all
           follow-up-mode :one-at-a-time
           auto-compact true
           max-retries 3
           base-delay-ms 2000
           max-agent-delay-ms retry/default-max-agent-delay-ms
           scoped-models []
           compact-reserve-tokens 16384
           keep-recent-tokens 20000
           compact-model-overrides {}
           http-idle-timeout-ms 120000
           http-total-timeout-ms 0
           block-images false
           loop-guard-enabled true
           loop-guard-threshold 3
           thinking-loop-guard-enabled true
           system-prompt-fn system-prompt/build-system-prompt
           system "You are kmet, a minimal coding agent. Help the user with their tasks.
Use the available tools to read, write, edit files, and execute commands.
Be precise and concise in your responses."}}]
  (map->AgentState {:status (atom :idle)
                    :messages (atom [])
                    :session session
                    :model (atom model)
                    :provider (atom provider)
                    :system (atom system)
                    :signal (atom false)
                    :thinking (atom thinking)
                    :enabled-tools (atom nil)
                    :default-tools (atom default-tools)
                    :on-event on-event
                    :base-url base-url
                    :api-type api-type
                    :steering (atom [])
                    :follow-up (atom [])
                    :pending-custom (atom [])
                    :pending-next-turn (atom [])
                    :active-call (atom nil)
                    :cfg (atom {:max-retries max-retries
                                :base-delay-ms base-delay-ms
                                :max-agent-delay-ms max-agent-delay-ms
                                :http-idle-timeout-ms http-idle-timeout-ms
                                :http-total-timeout-ms http-total-timeout-ms
                                :steering-mode steering-mode
                                :follow-up-mode follow-up-mode
                                :auto-compact (boolean auto-compact)
                                :block-images (boolean block-images)
                                :context-window context-window
                                :loop-guard-enabled (boolean loop-guard-enabled)
                                :loop-guard-threshold (max 2 (long loop-guard-threshold))
                                :thinking-loop-guard-enabled (boolean thinking-loop-guard-enabled)})
                    :retry-count (atom 0)
                    :before-tool-call (atom before-tool-call)
                    :after-tool-call (atom after-tool-call)
                    :system-prompt-override (atom system-prompt-override)
                    :transform-context (atom transform-context)
                    :prepare-next-turn (atom prepare-next-turn)
                    :prepare-request (atom prepare-request)
                    :finish-turn (atom finish-turn)
                    :last-outcome (atom :completed)
                    :get-api-key (atom get-api-key)
                    :scoped-models (atom (vec (distinct scoped-models)))
                    :overflow-recovered (atom false)
                    :compact-token-threshold compact-token-threshold
                    :compact-reserve-tokens compact-reserve-tokens
                    :keep-recent-tokens keep-recent-tokens
                    :compact-model-overrides compact-model-overrides
                    :branch-summary-reserve-tokens branch-summary-reserve-tokens
                    :compacting? (atom false)
                    :compaction-signal (atom false)
                    :compaction-blocked (atom false)
                    :compaction-error (atom nil)
                    :pending-bash (atom [])
                    :run-messages (atom {})
                    :run-token (atom nil)
                    :system-prompt-fn system-prompt-fn
                    :system-prompt-opts (atom system-prompt-opts)
                    :loop-guard (atom {:window [] :suppressed 0})
                    :ctx ctx}))

(defn emit
  "Route an event to the UI callback (:on-event) and to the run's listeners.

   One table: the composition's kernel listener table, resolved from the run's
   own ctx upward, so a listener registered at the app's scope hears what any
   agent under it did, and the extension ABI registers at the app root like any
   other listener. `events/emit!` dispatches by the event's declared mode and
   returns what the mode answers; a broken listener is logged and reads as nil,
   so neither sink can kill the loop.

   The event is one map: pi's agent_end split (`_emit` vs
   `_emitExtensionEvent`) emitted a bare copy to extensions and a decorated one
   to the front end. With one table there is one copy, and it carries the
   decoration (`:will-retry`)."
  [agent event]
  (when-let [cb (:on-event agent)]
    (cb event))
  (events/emit! (:ctx agent) event))

(defn set-agent-status!
  "Update the run's :status atom and emit the matching :status event. EXTRA, when
   given, is merged into the event (e.g. {:tool-calls ...}) so the call site
   does not repeat the reset/emit pair."
  [agent status & [extra]]
  (reset! (:status agent) status)
  (emit agent (merge {:type :status :status status} (or extra {})))
  nil)

(defn- queue-batch
  "The messages MODE selects from QUEUE: :all takes everything,
   :one-at-a-time the oldest."
  [queue mode]
  (if (= mode :all)
    (vec queue)
    (vec (take 1 queue))))

(defn drain-queue!
  "Atomically remove and return the batch MODE selects from QUEUE-ATOM.
   Public for the queue tests; the loop drains through inject-queued-messages!."
  [queue-atom mode]
  (loop []
    (let [v @queue-atom
          taken (queue-batch v mode)]
      (if (empty? taken)
        []
        (if (compare-and-set! queue-atom v (subvec v (count taken)))
          taken
          (recur))))))

(def ^:dynamic *run-token*
  "The run token of the attempt executing on this thread, or nil outside a
   run. Appends record against this token (see record-run-message!) so a late
   append from an already-cancelled attempt can never land in a newer
   prompt's accumulator after the agent's :run-token atom was republished.
   `run-attempt!` binds it for the attempt's whole extent."
  nil)

(defn- record-run-message!
  "Record MSG in the in-flight run's message list (pi: newMessages.push —
   agent-loop). No-op outside a run, so an idle `!` bash result stays
   context-only. The attempt's own token (the *run-token* binding) wins over
   the agent's :run-token atom, which a newer prompt may already own. A
   mid-run context replacement (compaction, prepareNextTurn :context) does
   not touch the list, so agent-end still reports the run's messages once the
   context no longer holds them. A `fnil` start tolerates the token being
   published before its empty list."
  [agent msg]
  (when-some [token (or *run-token* @(:run-token agent))]
    (swap! (:run-messages agent) update token (fnil conj []) msg))
  nil)

(defn append-context-message!
  "Append MSG to the live context only — never the run's message list. Used
   for appends that must not enter `agent-end :messages` (the post-settle
   pending-bash flush, pi: _flushPendingBashMessages runs after agent_end).
   Returns MSG."
  [agent msg]
  (swap! (:messages agent) conj msg)
  msg)

(defn- append-message!
  "Append MSG to the live context and to the run's message list. Every
   context append that belongs to a run goes through here so the two cannot
   diverge. Returns MSG."
  [agent msg]
  (append-context-message! agent msg)
  (record-run-message! agent msg)
  msg)

(defn add-user-message!
  "Add a user message to context and session, emitting :message-start.
   images — optional vector of image content blocks (pi: image attachments)."
  [agent text & [images]]
  (let [user-msg (message/user-message text images)]
    (append-message! agent user-msg)
    (when (:session agent)
      (session/append-entry (:session agent)
                            {:role :user :content (:content user-msg)}))
    (emit agent {:type :message-start :message user-msg})))

(defn add-assistant-message!
  "Add a final assistant message to context and session, emitting
   :message-end. RESULT is call-llm's delivered map. Stamps provider/model
   provenance and the captured thinking signature (pi: AssistantMessage
   carries provider/model/api plus per-block signatures — converters replay
   signatures only for same-provider-same-model messages and degrade
   everything else to plain text). Returns the persisted session entry —
   its :id is the turn boundary's :message-entry-id — when the agent has a
   session, else the message."
  [agent {:keys [text thinking tool-calls usage thinking-signature]}]
  (let [assistant-msg (cond-> (assoc (message/assistant-message text thinking tool-calls usage)
                                     :provider @(:provider agent)
                                     :model @(:model agent))
                        (seq thinking-signature) (assoc :thinking-signature thinking-signature))]
    (append-message! agent assistant-msg)
    (let [entry (when (:session agent)
                  (session/append-entry (:session agent) assistant-msg))]
      (emit agent {:type :message-end :message assistant-msg})
      (or entry assistant-msg))))

(defn record-abandoned-attempt!
  "Record an LLM attempt that never completed — stream error or user abort —
   in the session history without adding it to the live context, emitting
   :message-end (pi: a failed stream finalizes the partial as an
   AssistantMessage with stopReason \"error\"/\"aborted\" — message_end
   persists it to the session file; for errors _prepareRetry then durably
   omits it from the model projection (see omit-recovery-attempt!) so the
   retried request never sees it, resume included; a terminal error is not
   omitted, so a resumed session shows it as a plain assistant message
   carrying its partial text). RESULT is call-llm's delivered map with
   whatever partials arrived before the abandonment.
   Returns the persisted session entry — its :id is the recovery omission's
   target — when the agent has a session, else the bare message."
  [agent {:keys [text thinking tool-calls usage thinking-signature error]}
   stop-reason]
  (let [msg (cond-> (assoc (message/assistant-message text thinking tool-calls usage)
                           :provider @(:provider agent)
                           :model @(:model agent)
                           :stop-reason stop-reason)
              (seq thinking-signature) (assoc :thinking-signature thinking-signature)
              error (assoc :error-message error))]
    ;; pi pushes the aborted/errored message into newMessages before the
    ;; stopReason check, so agent_end reports it even though it never enters
    ;; the live context (_prepareRetry drops it from agent state).
    (record-run-message! agent msg)
    (let [entry (when (:session agent)
                  (session/append-entry (:session agent) msg))]
      (emit agent {:type :message-end :message msg})
      (or entry msg))))

(defn add-custom-message!
  "Add a before-agent-start injected message to context and session, emitting
   :message-start. Role defaults to :info (display-only — rendered in the UI,
   excluded from the LLM context); other roles pass through to the provider.
   String :content is normalized to a text block so the message matches the
   canonical kmet message format (session persistence and resume display)."
  [agent m]
  (let [msg (cond-> (assoc m :content (message/normalize-content (:content m)))
              (not (contains? m :role)) (assoc :role :info))]
    (append-message! agent msg)
    (when (:session agent)
      (session/append-entry (:session agent) msg))
    (emit agent {:type :message-start :message msg})))

(defn append-custom-message!
  "Append a custom message outside a tool call/result pair: persist it as a
   custom_message session entry, inject its context projection, and emit
   :message-start (pi: _appendCustomMessage). The context append is
   context-only — pi keeps these out of the loop's newMessages — so an idle
   send and the pending-custom flush share this path. MSG is a canonical
   {:role :custom :custom-type :content :display :details} map. Returns MSG."
  [agent msg]
  ;; the session keeps the raw content (its projection normalizes strings —
  ;; pi: appendCustomMessageEntry stores the message as given); the context
  ;; message is normalized here
  (when-let [sess (:session agent)]
    (session/append-custom-message-entry! sess (:custom-type msg) (:content msg)
                                          (:display msg) (:details msg)))
  (let [msg (assoc msg :content (message/normalize-content (:content msg)))]
    (append-context-message! agent msg)
    (emit agent {:type :message-start :message msg})
    msg))

(defn append-persisted-message!
  "Append MSG to both the live context and the session (pi: append + append-entry).
   Returns MSG. Centralizes the two-step append so context/session stay in sync."
  [agent msg]
  (append-message! agent msg)
  (when (:session agent)
    (session/append-entry (:session agent) msg))
  msg)

;; ─── The agent's tool table ──────────────────────────────────────────────────
;; The table is read from the `:tools` handle the agent's ctx provides, never
;; from a process default: a child ctx may provide its own, which is what makes
;; two agents two tables (docs/architecture.md § Service catalog). The display
;; path takes the same table as a `:tool-fn` from its caller, never a process
;; default.

(defn ctx-tool-table
  "The tool table a ctx provides: name → Tool record from its `:tools` handle.
   A ctx that provides none is a programming error, not an empty table — the
   app root always provides one and a child inherits it, so the only ctxs
   without one are hand-built (a test's), and those must be given one too
   rather than silently reading a process default."
  [ctx]
  (let [handle (srce/get ctx :tools)]
    (when-not handle
      (throw (ex-info "the agent's ctx provides no :tools service"
                      {:type :kmet/no-tool-service
                       :ctx-name (srce/attr ctx :name)})))
    (tools/get-all-tools handle)))

(defn tool-table
  "The tool table the agent runs on (see `ctx-tool-table`)."
  [agent]
  (ctx-tool-table (:ctx agent)))

(defn execute-tool-for
  "Dispatch one call through HANDLE — the loop's single hand-down to the tool
   layer, and the seam a test redefs to stub execution without a registry."
  [handle tool-name args opts]
  (tools/execute-tool handle tool-name args opts))

(defn tool-executor
  "The agent's dispatcher in `invoke/execute-tool-call`'s shape — the handle
   bound once, so a call resolves in the agent's own table."
  [agent]
  (let [handle (srce/get (:ctx agent) :tools)]
    (fn [tool-name args opts]
      (execute-tool-for handle tool-name args opts))))

(defn active-tools-for
  "The tool set a selection resolves to, in TABLE order (name → Tool, see
   `tool-table`; `registry/select-tools` does the filtering): ENABLED (the
   runtime allowlist, nil = all) wins; otherwise DEFAULT-TOOLS (a resolved
   :default-tools selection, nil = every built-in) excludes the built-ins it
   leaves out — extension tools stay enabled (pi: defaultTools selects the
   built-in tools; extension/custom tools remain enabled). The wire :tools and
   the run_code sandbox surface both resolve through this."
  [table {:keys [enabled default-tools]}]
  (vals (tools/select-tools table
                            {:enabled enabled
                             :exclude (when (nil? enabled)
                                        (tools/default-tool-exclusions default-tools))})))

(defn active-tools
  "The tools sent to the LLM (pi: AgentSession.getActiveToolNames → the
   declared set): the registry filtered by the state's :enabled-tools and
   :default-tools (see active-tools-for). Read once per request and threaded
   through it — the wire list, the declared set and dispatch all resolve
   against that one capture, so a tool unloaded mid-request still executes for
   the request that was offered it."
  [agent]
  (active-tools-for (tool-table agent)
                    {:enabled @(:enabled-tools agent)
                     :default-tools @(:default-tools agent)}))

(defn set-active-tools!
  "Restrict the tools sent to the LLM to NAMES (a set or seq of tool
   names); nil restores the configured default surface — every tool, minus
   the built-ins a :default-tools selection leaves out (pi: setActiveTools).
   Also rebuilds the
   system prompt to reflect the new tool set (pi: setActiveToolsByName —
   only tools in the registry can be enabled; unknown names are ignored).
   Validation runs against the LIVE tool registry, and the base prompt
   options captured at build time (:system-prompt-opts, pi:
   _baseSystemPromptOptions) are re-applied with the filtered set, so the
   prompt's Available tools list and tool-derived guidelines stay in sync
   with what the model can actually call. The updated options are stored
   back, so a later rebuild from them — refresh-prompt-cwd! on a session
   switch — keeps the same tool set (pi: _rebuildSystemPrompt).
   Changes take effect on the next agent turn."
  [agent names]
  (let [names (cond
                (nil? names) nil
                (sequential? names) names
                ;; a bare string is a single tool name (extensions pass
                ;; through untyped; pi's string[] is enforced by TS)
                (string? names) [names]
                :else names)
        enabled (when names (set names))
        ;; validation source: the LIVE registry (pi: setActiveToolsByName
        ;; validates against the tool registry) — captures extension tools
        ;; registered after the system prompt was built
        table (tool-table agent)
        valid (if enabled
                (filterv #(contains? enabled (:name %))
                         (vals table))
                ;; nil restores the configured default surface — a
                ;; :default-tools selection stays in force
                (active-tools-for table {:default-tools @(:default-tools agent)}))
        ;; pi filters unknown names out of the enabled set itself (only
        ;; registry tools are stored); the prompt is built from VALID, so
        ;; the two can never diverge
        enabled (when enabled (into #{} (map :name) valid))
        opts (assoc @(:system-prompt-opts agent) :tools valid)]
    (reset! (:enabled-tools agent) enabled)
    (when (seq @(:system-prompt-opts agent))
      (reset! (:system-prompt-opts agent) opts)
      (reset! (:system agent)
              (apply (:system-prompt-fn agent) (mapcat identity opts))))
    nil))

(defn apply-default-tools!
  "Re-apply the :default-tools selection after a settings reload
   (pi: AgentSession.reload — the new selection replaces the
   accumulated one; tools it names are active, tools it omits are
   not; extension tools in :enabled-tools are unaffected). An
   explicit set-active-tools! selection wins over it. Returns the
   new selection."
  [agent selection]
  (reset! (:default-tools agent) (vec (or selection tools/builtin-tool-names))))

(defn add-bash-result!
  "Record a !/!! bash execution result in the agent context and session.
   While the agent is streaming the entry is deferred (pi: recordBashResult
   queues pending bash messages to preserve tool_use/tool_result ordering)
   and flushed by run-agent-turn's finally once the run settles."
  [agent command result exclude-from-context?]
  (let [entry (session/make-bash-entry command result exclude-from-context?)]
    (if (contains? #{:thinking :executing} @(:status agent))
      (swap! (:pending-bash agent) conj entry)
      (do (append-message! agent entry)
          (when-let [sess (:session agent)]
            (session/append-entry sess entry))))
    nil))

(defn flush-pending-bash-messages!
  "Add queued bash results (recorded while the agent was streaming) to the
   agent context and session (pi: _flushPendingBashMessages). Called by
   run-agent-turn's finally so every exit path — settle, error, timeout, or
   cancel — flushes them. Drains entries queued mid-flush via CAS. The
   context-only append keeps them out of `agent-end :messages` (pi flushes
   after agent_end)."
  [agent]
  (loop []
    (let [pending @(:pending-bash agent)]
      (when (seq pending)
        (doseq [entry pending]
          (append-context-message! agent entry)
          (when-let [sess (:session agent)]
            (session/append-entry sess entry)))
        (when-not (compare-and-set! (:pending-bash agent) pending [])
          (recur))))))

(defn add-context-message!
  "Inject a message into the agent's in-memory context without persisting it
   (pi: custom messages flow through the agent loop — kmet extensions
   persist a :custom-message entry via the session and inject the projection
   here). Emits :message-start so the TUI renders it (the display flag is
   honored by the TUI handler). String :content is normalized to a text
   block, matching the canonical kmet message format."
  [agent msg]
  (let [msg (assoc msg :content (message/normalize-content (:content msg)))]
    (append-message! agent msg)
    (emit agent {:type :message-start :message msg})))

(defn flush-pending-custom-messages!
  "Append custom messages deferred while the agent was streaming, once the
   current turn's tool results are in the context (pi:
   _flushPendingCustomMessages — called at every turn end, before a new
   prompt, at the pre-settle boundary, and by the prompt's settle flush).
   Drains in a CAS loop, so a defer that lands mid-flush is still included
   instead of being lost."
  [agent]
  (loop []
    (when-let [msgs (seq (drain-queue! (:pending-custom agent) :all))]
      (doseq [msg msgs]
        (append-custom-message! agent msg))
      (recur))))

(defn inject-next-turn-messages!
  "Inject the custom messages queued for the next prompt alongside its user
   message, then clear the queue (pi: prompt() pushes _pendingNextTurnMessages
   after the user message and clears them). Each is persisted and recorded in
   this attempt's messages."
  [agent]
  (loop []
    (when-let [msgs (seq (drain-queue! (:pending-next-turn agent) :all))]
      (doseq [msg msgs]
        (record-run-message! agent (append-custom-message! agent msg)))
      (recur))))

(defn inject-queued-messages!
  "Drain one batch from QUEUE (per MODE) and inject it into the live
   context: strings become user messages; maps are custom messages (pi: the
   queues carry full messages — sendMessage queues custom ones there),
   persisted and recorded in this attempt's messages here, as the loop
   consumes them (pi: message_end persistence; the loop pushes a consumed
   queue message into newMessages). Emits :queue-update when anything was
   consumed; returns the number of messages injected."
  [agent queue mode]
  (let [msgs (drain-queue! queue mode)]
    (doseq [m msgs]
      (if (map? m)
        (record-run-message! agent (append-custom-message! agent m))
        (add-user-message! agent m)))
    (when (seq msgs)
      (emit agent {:type :queue-update
                   :steering @(:steering agent)
                   :follow-up @(:follow-up agent)}))
    (count msgs)))

(defn drain-next-queue!
  "Drain the queue the next low-level run should start from and inject its
   messages into the live context (pi: Agent.continue — with an assistant
   message last it drains the steering queue first and, when that is empty,
   the follow-up queue, running the drained messages as the next run's
   prompt). Each queue drains per its mode; returns the number of messages
   injected (0 when both queues are empty)."
  [agent]
  (let [cfg @(:cfg agent)
        [queue mode] (if (seq @(:steering agent))
                       [(:steering agent) (:steering-mode cfg)]
                       [(:follow-up agent) (:follow-up-mode cfg)])]
    (inject-queued-messages! agent queue mode)))

(defn peek-queued-messages
  "Preview the batch the loop would consume next, without draining anything:
   the steering queue's while it has messages, otherwise the follow-up
   queue's (pi: Agent.peekQueuedMessages — steering wins because the inner
   loop polls it first). Each batch honors that queue's mode, so
   :one-at-a-time previews a single message."
  [agent]
  (let [cfg @(:cfg agent)
        steering (queue-batch @(:steering agent) (:steering-mode cfg))]
    (if (seq steering)
      steering
      (queue-batch @(:follow-up agent) (:follow-up-mode cfg)))))

(defn refresh-context-from-session!
  "Rebuild the live context from the session projection — the canonical
   context every request is built from (pi: _refreshFinalizedContext). Both
   compaction and the append-only context edits land here, so the session
   stays the source of truth: raw history, usage totals and the UI replay
   are untouched. In-memory-only messages (add-context-message!, which never
   persists) are not in the session and are dropped by the rebuild."
  [agent]
  (when-let [sess (:session agent)]
    (reset! (:messages agent)
            (message/drop-incomplete-tool-calls (session/build-context-messages sess)))))

(defn replace-context!
  "Replace the in-memory conversation, rebuild the session file to match, and
   emit :context-replaced so the UI can mirror the new context
   (pi: prepareNextTurnWithContext context replacement)."
  [agent messages]
  (let [msgs (message/drop-incomplete-tool-calls (vec messages))]
    (reset! (:messages agent) msgs)
    (when-let [sess (:session agent)]
      ;; Rebuild the session as a fresh linear branch mirroring the new
      ;; context — atomic (temp file + rename) so a crash mid-write can't
      ;; corrupt the file; serialized so a concurrent append (bash result)
      ;; can't interleave with the rewrite.
      (session/replace-entries! sess msgs))
    (emit agent {:type :context-replaced :messages msgs})))

(defn get-context [agent]
  @(:messages agent))

(defn restore-session-context!
  "Rebuild the agent's in-memory context from the session projection (pi: the
   session is the source of truth — buildSessionContext). build-context walks
   root→leaf through the latest compaction; compaction entries keep their roles
   in the rebuilt messages (pi: compactionSummary AgentMessage; convertToLlm
   wraps them at the wire), :info/:session_info are metadata and excluded, and
   the append-only context edits (append-context-edit!) apply — an omitted
   message is dropped, a rewritten one carries its replacement. Messages only —
   like pi's buildSessionContext consumers, the agent's model/thinking are left
   untouched here; the session-derived settings are applied on session load via
   apply-session-settings! (pi: sdk.ts createAgentSession restore logic). No-op
   without a session. Used when a session is resumed/continued (and on tree
   navigation / fork / clone, which rebuild the context without touching the
   model/thinking)."
  [agent]
  (refresh-context-from-session! agent))

(defn append-context-edit!
  "Append an append-only edit to one earlier entry's contribution to model
   context (pi: appendContextEdit): a nil REPLACEMENT omits TARGET-ID from
   every future provider context, {:content string-or-blocks} rewrites its
   content. The session keeps the raw entry — usage totals, replay and the UI
   are untouched — while the live context is rebuilt from the projection so the
   next request already sees the change (pi: _refreshFinalizedContext). Returns
   the appended :context-edit entry.

   TARGET-ID must exist, lie on the active branch, and be a message entry
   contributing editable model content (:user, :assistant, :tool, :bash,
   :custom-message); otherwise this throws (see
   kmet.app.session.core/append-context-edit!). Throws {:type :no-session} without a
   session. Nothing is emitted: the edit changes the model projection only,
   and the UI deliberately keeps rendering the raw conversation (unlike
   :context-replaced, which makes the UI mirror a new message list)."
  [agent target-id replacement]
  (if-let [sess (:session agent)]
    (let [entry (session/append-context-edit! sess target-id replacement)]
      (refresh-context-from-session! agent)
      entry)
    (throw (ex-info "No session to append a context edit to"
                    {:type :no-session :target-id target-id}))))

(defn apply-session-settings!
  "Apply the session-derived model/thinking to the agent state (pi: sdk.ts
   createAgentSession — the session restores its settings when loaded via
   resume/continue; tree navigation, fork and clone never touch them). Only
   recorded settings are applied:
   - model: only when it resolves to an authenticated model (pi: getModel +
     hasConfiguredAuth); otherwise the current model stays (pi falls back to
     the settings default — kmet's current model IS that default).
   - thinking: only when a :thinking-level-change entry is on the branch;
     without one the current level stays (pi: hasThinkingEntry guard — the
     settings default is kept, not pi's unrecorded \"off\" default).
   Returns true when anything changed."
  [agent]
  (when-let [sess (:session agent)]
    (let [{:keys [thinking-level model provider]}
          (session/derive-context-settings sess)
          authenticated-model? (some #(and (= provider (:provider %))
                                           (= model (:id %)))
                                     (models/get-available))
          thinking-recorded? (some #(= :thinking-level-change (:role %))
                                   (session/get-branch sess))
          changed (volatile! false)]
      (when authenticated-model?
        (reset! (:model agent) model)
        (reset! (:provider agent) provider)
        (vreset! changed true))
      (when thinking-recorded?
        ;; Clamp the restored thinking level to the current model's capabilities
        ;; (only for reasoning models; non-reasoning models keep the value as-is)
        (let [model-rec (models/get-model @(:provider agent) @(:model agent))
              clamped (if (:reasoning model-rec)
                        (models/clamp-thinking-level model-rec thinking-level)
                        thinking-level)]
          (reset! (:thinking agent) clamped))
        (vreset! changed true))
      @changed)))

(defn set-model!
  "Set the active model from a Model record (the provider follows the
   record), emitting :model-select with the resolved records and persisting
   a :model-change session entry (pi: setModel → appendModelChange). A call
   selecting the current provider/id is a no-op (no entry, no event); nil is
   ignored."
  [agent model]
  (when model
    (let [previous (models/get-model @(:provider agent) @(:model agent))]
      (when-not (and (= (:provider model) @(:provider agent))
                     (= (:id model) @(:model agent)))
        (reset! (:provider agent) (:provider model))
        (reset! (:model agent) (:id model))
        (when-let [sess (:session agent)]
          (session/append-model-change! sess (:provider model) (:id model)))
        (emit agent {:type :model-select
                     :model model
                     :previous-model previous
                     :source :set})))))

(defn set-scoped-models!
  "Set the session scoped model list used by cycle-model! (pi:
   session.setScopedModels — the list holds \"provider/id\" full ids; empty
   = no scoping, cycle over all available models). Duplicate entries are
   dropped (order-preserving): cycling steps one entry at a time, so a
   duplicate would stall it."
  [agent models]
  (reset! (:scoped-models agent) (vec (distinct models))))

(defn init-scoped-models!
  "Seed the session scoped model list from the config at startup (pi:
   parsed.models ?? settingsManager.getEnabledModels → resolveModelScope at
   runtime creation): CLI/config :models patterns when set, else the settings
   :enabled-models patterns. Patterns resolve against the catalog; unresolved
   ones print a warning to stderr and are skipped (pi: no-match diagnostics).
   Nothing set leaves the list empty — cycling falls back to all available
   models and /model shows no scope toggle. Returns AGENT."
  [agent config]
  (when-let [patterns (or (seq (:models config))
                          (seq (cfg/get-enabled-models config)))]
    (let [{:keys [models warnings]}
          (resolver/resolve-model-scope-models patterns (models/get-models))]
      (doseq [w warnings]
        (binding [*out* *err*] (println "Warning:" w)))
      (set-scoped-models!
       agent (mapv (fn [m] (str (name (:provider m)) "/" (:id m))) models))))
  agent)

(defn set-block-images!
  "Block images from provider calls live (pi: setBlockImages) — the next
   request strips every image block (see
   kmet.app.loop.message/block-message-images); the transcript and the
   stored session keep theirs."
  [agent blocked?]
  (swap! (:cfg agent) assoc :block-images (boolean blocked?)))

(defn set-auto-compact!
  "Toggle proactive compaction live (pi: setAutoCompact); overflow recovery
   is unaffected."
  [agent enabled?]
  (swap! (:cfg agent) assoc :auto-compact (boolean enabled?)))

(defn set-http-idle-timeout-ms!
  "Set the SSE idle timeout live (pi: setHttpIdleTimeoutMs); 0 disables."
  [agent ms]
  (swap! (:cfg agent) assoc :http-idle-timeout-ms (long ms)))

(defn set-http-total-timeout-ms!
  "Set the whole-request total deadline live; nil follows the idle timeout,
   while zero disables the total deadline without changing the idle timeout."
  [agent ms]
  (swap! (:cfg agent) assoc :http-total-timeout-ms (some-> ms long)))

(declare switch-thinking-level set-thinking-level!)

(defn- scoped-model-entries
  "Resolve scoped-list entries to available Model records (pi
   _cycleScopedModel — filtered to the available snapshot; entries with no
   auth drop out). Bare ids resolve against CURRENT-PROVIDER first."
  [entries current-provider]
  (let [available (set (map (fn [m] [(name (:provider m)) (:id m)])
                            (models/get-available)))]
    (into []
          (keep (fn [entry]
                  (let [m (resolver/resolve-scoped-model entry current-provider)]
                    (when (and m (contains? available [(name (:provider m)) (:id m)])) m))))
          entries)))

(defn cycle-model!
  "Cycle to the next/previous model in the scoped model list (pi: cycleModel
   → _cycleScopedModel / _cycleAvailableModel; kmet deviation — thinking
   follows the switch-thinking-level rank rule instead of pi's keep-with-
   clamp). The session scoped list (set via /scoped-models, seeded from
   config :models / settings :enabled-models) is used when non-empty;
   otherwise all available models cycle. Entries are filtered to
   authenticated models; a scoped entry may switch the provider. Returns the
   new model id, or nil when fewer than two models are available."
  [agent direction]
  (let [current-provider @(:provider agent)
        scoped (or (seq @(:scoped-models agent))
                   (mapv (fn [m] (str (name (:provider m)) "/" (:id m)))
                         (models/get-available)))
        models (scoped-model-entries scoped current-provider)]
    (when (> (count models) 1)
      (let [current [(name @(:provider agent)) @(:model agent)]
            idx (or (first (keep-indexed (fn [i m]
                                           (when (= current [(name (:provider m)) (:id m)])
                                             i))
                                         models))
                    -1)
            previous @(:model agent)
            next (nth models (mod (+ idx direction) (count models)))
            old-model (models/get-model current-provider previous)
            new-thinking (switch-thinking-level old-model next @(:thinking agent) nil)]
        (reset! (:provider agent) (:provider next))
        (reset! (:model agent) (:id next))
        (when-let [sess (:session agent)]
          (session/append-model-change! sess (:provider next) (:id next)))
        (set-thinking-level! agent new-thinking)
        (emit agent {:type :model-select
                     :model next
                     :previous-model old-model
                     :source :cycle})
        (:id next)))))

(defn set-thinking-level!
  "Set the thinking level, persisting a :thinking-level-change session entry
   only when the level actually changes (pi: setThinkingLevel — only
   persisted on change) and emitting :thinking-level-select with both
   levels (pi ThinkingLevelSelectEvent)."
  [agent level]
  (when-not (= level @(:thinking agent))
    (let [previous @(:thinking agent)]
      (reset! (:thinking agent) level)
      (when-let [sess (:session agent)]
        (session/append-thinking-level-change! sess level))
      (emit agent {:type :thinking-level-select
                   :level level
                   :previous-level previous}))))

(defn cycle-thinking-level!
  "Advance the thinking level through the current model's supported levels
   (pi: session.cycleThinkingLevel), applying it via set-thinking-level! and
   returning the new level. Returns nil when the model offers fewer than
   two levels (pi: the command/action surfaces a status instead)."
  [agent]
  (let [model (models/get-model @(:provider agent) @(:model agent))
        levels (if model (models/get-supported-thinking-levels model) [:off])]
    (when (> (count levels) 1)
      (let [current @(:thinking agent)
            idx (or (first (keep-indexed (fn [i l] (when (= l current) i)) levels))
                    0)
            next-level (nth levels (mod (inc idx) (count levels)))]
        (set-thinking-level! agent next-level)
        next-level))))

(defn switch-thinking-level
  "Thinking level when switching OLD-MODEL → NEW-MODEL (kmet-specific rule;
   the rank mapping is a deliberate deviation from pi's keep-with-clamp):
   the current level's rank among the old model's supported thinking levels
   (highest = 1) maps to the same rank on the new model, clamped to the new
   model's level count — an old highest stays the new highest, the old
   second-highest becomes the new second-highest, and so on. With thinking
   :off, a reasoning NEW-MODEL jumps to its highest level (pi parity); a
   non-reasoning one stays :off. EXPLICIT-LEVEL wins when given (clamped to
   the new model). Levels the old model can't express are clamped to its
   range first; when there is no position to preserve (non-reasoning/unknown
   old model), falls back to clamping CURRENT-LEVEL to the new model."
  [old-model new-model current-level explicit-level]
  (cond
    explicit-level (models/clamp-thinking-level new-model explicit-level)

    (= current-level :off)
    (if (:reasoning new-model)
      (last (models/get-supported-thinking-levels new-model))
      :off)

    :else
    (let [old-think (vec (remove #{:off} (models/get-supported-thinking-levels old-model)))
          new-think (vec (remove #{:off} (models/get-supported-thinking-levels new-model)))
          old-effective (when (seq old-think)
                          (models/clamp-thinking-level old-model current-level))
          i (when old-effective
              (first (keep-indexed (fn [j l] (when (= l old-effective) j)) old-think)))]
      (if (and i (seq new-think))
        (let [rank (- (count old-think) i)                ;; 1 = highest
              new-i (max 0 (min (dec (count new-think))
                                (- (count new-think) rank)))]
          (nth new-think new-i))
        (models/clamp-thinking-level new-model current-level)))))

(defn set-run-signal!
  "Set the run's cancel signal (true cancels; the next prompt resets it).
   The driver and a manual compaction share this setter so no module writes
   the field directly."
  [agent signal?]
  (reset! (:signal agent) (boolean signal?)))

(defn- enqueue!
  "Queue TEXT on AGENT's :steering / :follow-up queue (QUEUE-KEY) and emit
   the queue-update snapshot — both queues ride every emit. Returns nil."
  [agent queue-key text]
  (swap! (get agent queue-key) conj text)
  (emit agent {:type :queue-update
               :steering @(:steering agent)
               :follow-up @(:follow-up agent)})
  nil)

(defn steer!
  "Queue a user message for mid-turn injection.
   The agent loop polls the steering queue between turns (after tool results,
   before the next LLM call) and injects queued messages into the context.
   A message map is injected as a custom message (pi: the queues carry full
   messages — sendMessage queues custom ones there)."
  [agent text]
  (enqueue! agent :steering text))

(defn follow-up!
  "Queue a user message to be processed after the current run settles.
   The outer loop drains the follow-up queue when the inner loop finishes and
   continues the run with the queued messages. A message map is injected as a
   custom message (see steer!)."
  [agent text]
  (enqueue! agent :follow-up text))

(defn clear-queues!
  "Drop all pending steering and follow-up messages. REASON, when given, is
   carried on the :queue-update as :reason — cancel-turn passes :aborted so
   a headless consumer can tell a dropped queue from one the loop drained
   (a drain that delivered everything also reports empty queues)."
  ([agent] (clear-queues! agent nil))
  ([agent reason]
   (reset! (:steering agent) [])
   (reset! (:follow-up agent) [])
   (emit agent (cond-> {:type :queue-update :steering [] :follow-up []}
                 reason (assoc :reason reason)))
   nil))

(defn queued-messages
  "Return {:steering [...] :follow-up [...]} of currently queued messages."
  [agent]
  {:steering @(:steering agent)
   :follow-up @(:follow-up agent)})

(defn has-queued-messages?
  "True if either queue has pending messages."
  [agent]
  (or (seq @(:steering agent))
      (seq @(:follow-up agent))))

(defn defer-custom-message!
  "Defer a custom message sent while the agent is streaming: appending now
   would put it between an assistant tool call and its result (pi:
   _pendingCustomMessages). Nothing is persisted or emitted until the flush,
   which lands the message once the turn's tool results are in the context
   (see flush-pending-custom-messages!)."
  [agent msg]
  (swap! (:pending-custom agent) conj msg)
  nil)

(defn queue-next-turn-message!
  "Queue a custom message that is injected with the next prompt rather than
   the current turn (pi: deliverAs \"nextTurn\" → _pendingNextTurnMessages).
   It never triggers a turn and never joins the steering/follow-up queues."
  [agent msg]
  (swap! (:pending-next-turn agent) conj msg)
  nil)

(def queue-batch-modes
  "The modes :steering-mode / :follow-up-mode accept (pi: queue batch
   modes)."
  #{:all :one-at-a-time})

(defn- set-queue-batch-mode!
  [agent k mode]
  (when-not (contains? queue-batch-modes mode)
    (throw (ex-info (str "Unknown queue mode: " (pr-str mode))
                    {:type :invalid-mode :mode mode})))
  (swap! (:cfg agent) assoc k mode))

(defn set-steering-mode!
  "Set how many queued steering messages one injection delivers (pi:
   setSteeringMode): :all drains the queue, :one-at-a-time delivers the
   oldest. Live — the next drain reads the new value. Persisting belongs to
   the caller (the /settings row saves it; the RPC command is
   session-scoped)."
  [agent mode]
  (set-queue-batch-mode! agent :steering-mode mode))

(defn set-follow-up-mode!
  "Set how many queued follow-up messages one drain delivers (pi:
   setFollowUpMode): :all drains, :one-at-a-time delivers the oldest.
   Live, like set-steering-mode!."
  [agent mode]
  (set-queue-batch-mode! agent :follow-up-mode mode))
