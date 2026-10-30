(ns kmet.app.loop
  "Agent conversation loop — orchestrates user input, LLM calls, and tool execution.
   State machine: IDLE → THINKING → EXECUTING → THINKING → ... → IDLE

   Queues (pi: steeringQueue / followUpQueue) live in
   kmet.app.loop.state (steer!, follow-up!, the queue modes, and the
   injection of drained messages into the context).

   Emits lifecycle events (see kmet.app.events) to the :on-event callback
   (UI) and to the one listener table, resolved from the run's own ctx upward;
   the extension ABI registers at the app root and hears the run like any
   other listener.

   Auto-retry (pi: agent-session auto-retry): transient LLM errors are retried
   with exponential backoff (base-delay-ms * 2^(attempt-1)), up to max-retries.
   Quota/billing errors and context overflow are never retried. Retry emits
   :auto-retry-start / :auto-retry-end events; cancellation during backoff
   aborts the run quietly.

   Repeat-loop guard (kmet-specific circuit breaker): a model stuck issuing
   identical tool calls (or repeating its reasoning) is detected via a
   sliding window of canonical (tool, args) signatures — the threshold-th
   identical call is suppressed, and an all-suppressed batch (or cumulative
   suppressions) stops the run with a :loop-guard event and a plain
   explanation. Read-only tools (read) never trip it. Tunable via
   settings.edn :loop-guard {:enabled bool :threshold n} and
   :thinking-loop-guard-enabled — or by the `:loop-guard`/`:retry-policy` rows'
   `:config` (`kmet.app.loop.stages`); a run's own `:loop-stages` map still wins
   whole.

   Tool hooks and execution mode (pi: beforeToolCall / afterToolCall,
   toolExecution) live in kmet.app.loop.tool-exec.

   Per-turn hooks (pi: prepareNextTurn / prepareRequest / finishTurn /
   transformContext): the :prepare-next-turn and :finish-turn hooks run after
   each completed turn (in pi order; :finish-turn alone also runs for errored
   and aborted turns, whose decision is ignored); :prepare-request runs
   before every provider request (pi: config.prepareRequest) and
   :transform-context rewrites the conversation before each LLM call. The
   :system-prompt-override atom holds a per-run system prompt override.

   Compaction (pi: auto-compaction) is the `:compaction` service
   (`kmet.app.compaction`; the builtin is `kmet.app.compaction.stage`).

   Model requests go through the `:llm` service (kmet.app.llm), the `:llm` row
   providing kmet.ai.llm/send-message behind it; model management lives in
   kmet.app.loop.state (set-scoped-models!,
   cycle-model!, the thinking-level setters).

   Input hooks (pi: input extension event): applied at the interactive input
   path (modes.interactive handle-submit) via extensions/apply-input-hooks —
   extensions can consume ({:action :handled}) or rewrite
   ({:action :transform :text ...}) user input before the agent runs.

   before-agent-start hooks (pi: before_agent_start extension event): applied
   by run-agent-turn after the user message is added, before the first LLM
   call. Extensions can override the system prompt for the run
   ({:system-prompt s}) and inject context messages ({:message m}); injected
   messages default to role :info — display-only (rendered in the UI, excluded
   from the LLM context). Other roles pass through to the provider.

   Image attachments (pi: prompt(text, images)): run-agent-turn accepts
   :images — a vector of {:type :image :data base64 :mime-type str} blocks
   attached to the initial user message; they flow to the provider as OpenAI
   image_url / Anthropic image blocks. The wire filter that honors
   :block-images lives in kmet.app.loop.message."
  (:require [kmet.app.permissions :as permissions]
            [kmet.app.projections :as projections]
            [kmet.app.compaction :as compact]
            [kmet.app.loop.completion :as completion]
            [kmet.app.loop.tool-exec :as tool-exec]
            [kmet.app.loop.state :as state]
            [kmet.app.loop.message :as message]
            [kmet.app.loop-guard :as loop-guard]
            [kmet.app.retry :as retry]
            [kmet.app.runtime :as runtime]
            [kmet.app.loop.stages :as loop-stages]
            [kmet.app.tools.util :as tools-util]
            [kmet.app.skills :as skills]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.read :as read-tool]
            [kmet.app.tools.run-code :as run-code-tool]
            [kmet.ai.auth :as auth]
            [kmet.app.session.core :as session]
            [kmet.ai.models :as models]
            [kmet.app.extensions.core :as extensions]
            [kmet.srce.core :as srce]
            [kmet.app.debug :as debug]))
;; ─── Agent state ───────────────────────────────────────────────────────────

(defn make-agent-state
  "Create a new agent state; see kmet.app.loop.state/make-agent-state for
   the options."
  [& opts]
  (apply state/make-agent-state opts))

;; ─── Repeat-loop guard: trip handling ──────────────────────────────────────
;; Detection (signatures, sliding window, thinking-loop) lives in
;; kmet.app.loop-guard; settling the run stays here because it emits the
;; :loop-guard UI event.

(defn- loop-guard-give-up!
  "Settle the run after the repeat-loop guard trips: set the explanation as
   the final text (delivered via on-done) and reset the per-run guard state
   for the next run. Emits the :loop-guard event (the UI shows a warning
   line). Pure state mutation on the agent."
  [agent text-buf reason details]
  (reset! text-buf details)
  (reset! (:loop-guard agent) {:window [] :suppressed 0})
  (state/emit agent {:type :loop-guard :reason reason :details details}))

;; ─── Agent run ─────────────────────────────────────────────────────────────

(defn- apply-next-turn-update!
  "Apply a prepare-next-turn update map to the agent state.
   Supported keys: :model (a Model record, pi AgentLoopTurnUpdate.model —
   the provider follows the record), :system, :thinking,
   :system-prompt-override, :context (:context replaces the conversation and
   rebuilds the session to match)."
  [agent update]
  (when update
    (when-let [m (:model update)]
      (let [previous (models/get-model @(:provider agent) @(:model agent))]
        (when-not (and (= (:provider m) @(:provider agent))
                       (= (:id m) @(:model agent)))
          (reset! (:provider agent) (:provider m))
          (reset! (:model agent) (:id m))
          (state/emit agent {:type :model-select :model m :previous-model previous :source :set}))))
    (when-let [s (:system update)] (reset! (:system agent) s))
    (when-let [th (:thinking update)]
      (let [model-rec (models/get-model @(:provider agent) @(:model agent))
            clamped (if (:reasoning model-rec)
                      (models/clamp-thinking-level model-rec th)
                      th)]
        (reset! (:thinking agent) clamped)))
    (when-let [o (:system-prompt-override update)]
      (reset! (:system-prompt-override agent) o))
    (when-let [c (:context update)] (state/replace-context! agent c))))

(defn- apply-request-update!
  "Apply a :prepare-request update map to the agent state (pi: prepareRequest
   returning {context, model, thinkingLevel}). :context replaces the
   in-flight conversation only — pi assigns it to the loop's currentContext
   and never writes the session, so the replacement is not persisted; the
   other keys apply as for prepare-next-turn."
  [agent update]
  (when update
    (when-let [c (:context update)]
      (reset! (:messages agent) (vec c)))
    (apply-next-turn-update! agent (dissoc update :context))))

(defn- prepare-request!
  "The per-request checkpoint (pi: the session's _installAgentRequestProjection
   wrapping config.prepareRequest). The canonical session projection is
   installed as the request context first — an append-only context edit
   reaches the request even when no rebuild followed it — then the
   :prepare-request hook receives that atomic view of {:context :model
   :thinking :system} and may return an update map with
   apply-next-turn-update!'s keys. A hook :context replacement applies to this
   request (and the rest of the attempt after it) without a turn boundary
   between; the next request re-installs the projection, so a replacement is
   not durable unless the hook re-applies it (pi re-canonicalizes the same
   way)."
  [agent]
  (state/refresh-context-from-session! agent)
  (when-let [f @(:prepare-request agent)]
    (apply-request-update!
     agent
     (f {:context @(:messages agent)
         :model (models/get-model @(:provider agent) @(:model agent))
         :thinking @(:thinking agent)
         :system (or @(:system-prompt-override agent) @(:system agent))}))))

(defn- turn-context
  "What a per-turn hook receives (pi: PrepareNextTurnContext /
   finishTurn's turn): the turn's message and tool results, and the live
   messages. TURN-INDEX counts turns within the current attempt (pi's hooks
   get no index): a retry or continuation attempt starts again at 0."
  [agent turn-index assistant-msg tool-results]
  {:turn-index turn-index
   :message assistant-msg
   :tool-results tool-results
   :messages @(:messages agent)})

(defn- prepare-next-turn!
  "Run the :prepare-next-turn hook for a completed turn and apply its update
   (pi: prepareNextTurn — model/system/context for the turn that follows)."
  [agent turn-index assistant-msg tool-results]
  (when-let [f @(:prepare-next-turn agent)]
    (apply-next-turn-update! agent (f (turn-context agent turn-index
                                                    assistant-msg tool-results)))))

(defn- can-continue?
  "True when the live context can back one more provider request: at least
   one message that reaches the provider, and no assistant message last (pi:
   _buildBoundaryContext's canContinue — a boundary continuation needs
   something to send). Queued messages can also back one; see
   boundary-can-continue?."
  [agent]
  (let [msgs (into []
                   (remove #(or (= :info (:role %)) (:exclude-from-context? %)))
                   @(:messages agent))]
    (and (seq msgs)
         (not= :assistant (:role (last msgs))))))

(defn- boundary-can-continue?
  "pi's canContinue for a boundary: the context can back one more request,
   messages are queued, or custom messages are deferred — the boundary's
   flush lands those before the next request (pi: _buildBoundaryContext's
   pendingCustomContext). Reported to handlers as :can-continue (the value
   before a handler's entries are committed — a runnable batch enables it)."
  [agent]
  (boolean (or (can-continue? agent)
               (state/has-queued-messages? agent)
               (seq @(:pending-custom agent)))))

(defn- honor-continuation?
  "Whether a boundary's requested continuation can run, reporting the
   rejected request for EVENT (pi: canContinue gating
   _dispatchTurnEndBoundary / _runBeforeSettleBoundary, and
   _reportInvalidBoundaryContinuation)."
  [agent event]
  (if (boundary-can-continue? agent)
    true
    (do (binding [*out* *err*]
          (println "Warning:" (name event)
                   "handler requested a continuation without a runnable context"))
        false)))

(defn- boundary-entries
  "Validate a boundary handler's :entries: a sequential collection of session
   entry maps (each with a :role). Returns them as a vector, or nil when the
   batch is invalid — pi drops an unapplicable batch wholesale."
  [entries]
  (cond
    (nil? entries) []
    (and (sequential? entries)
         (every? #(and (map? %) (keyword? (:role %))) entries))
    (vec entries)
    :else nil))

(defn- commit-boundary-entry!
  "Commit one boundary ENTRY through the session (pi: _applyBoundaryDrafts):
   a :context-edit entry goes through the validating session API, any other
   entry is appended as given (the session assigns id/parent/timestamp).
   Returns the committed entry (nil when nothing was committed — no session,
   or an invalid context edit, which is reported and skipped rather than
   taking the boundary down)."
  [agent entry event]
  (when-let [sess (:session agent)]
    (if (= :context-edit (:role entry))
      (try (session/append-context-edit! sess (:target-id entry) (:replacement entry))
           (catch Exception e
             (binding [*out* *err*]
               (println "Warning: invalid" (name event) "context edit:"
                        (ex-message e)))
             nil))
      (session/append-entry sess entry))))

(defn- apply-boundary-entries!
  "Commit a boundary's ENTRIES for EVENT (pi: _commitBoundaryDrafts): each is
   appended through the session, in order, and its context projection (pi:
   convertToLlm — :custom-message entries become :custom messages) is
   appended to the live context so the next request sees it. A :context-edit
   entry ({:role :context-edit :target-id id :replacement nil|{:content ...}})
   is committed through the validating session API (pi: appendContextEdit)
   and rebuilds the live context from the projection, so the targeted message
   is omitted or rewritten there while the raw history keeps it. The context
   append is context-only: committed entries are not part of the attempt's
   :agent-end :messages (pi: newMessages is the loop's own list). Returns
   true when the batch was valid."
  [agent entries event]
  (if-some [valid (boundary-entries entries)]
    (do (let [edited? (volatile! false)]
          (doseq [entry valid]
            (when (and (commit-boundary-entry! agent entry event)
                       (= :context-edit (:role entry)))
              (vreset! edited? true))
            (doseq [msg (session/context-messages entry)]
              (state/append-context-message! agent msg)
              (state/emit agent {:type :message-start :message msg})))
          ;; an edit changes what the target projects into, so the live context
          ;; is rebuilt from the projection (pi: _commitBoundaryDrafts →
          ;; _refreshFinalizedContext)
          (when @edited? (state/refresh-context-from-session! agent)))
        true)
    (do (binding [*out* *err*]
          (println "Warning: invalid" (name event)
                   "boundary :entries (expected session entry maps):"
                   (pr-str entries)))
        false)))

(defn- requested-continuation?
  "Commit a boundary's entries and report whether it asked for one more
   request. An invalid batch is dropped wholesale, and with it the request
   (pi: valid=false → {entries: [], continue: false})."
  [agent boundary event]
  (and (apply-boundary-entries! agent (:entries boundary) event)
       (true? (:continue boundary))))

(defn- boundary-context
  "pi's BoundaryContextPreview (pi: _buildBoundaryContext): the projected
   context entries and messages, and the provider-agnostic llm view — the
   agent's messages through the app's convertToLlm
   (kmet.app.loop.message/to-llm-messages), the same function the `:llm`
   request applies after the context hook. Computed
   once per boundary dispatch: a handler's own drafted entries are not
   applied to it (pi rebuilds the preview per handler with the accumulated
   drafts)."
  [agent]
  (when-let [sess (:session agent)]
    (let [entries (session/project-context sess)
          messages (vec (mapcat :messages entries))]
      {:context-entries entries
       :context-messages messages
       :llm-messages (message/to-llm-messages messages)})))

(defn- tool-result-entry-ids
  "Session entry ids of the persisted results for TOOL-CALL-IDS, in call
   order (pi: turn_end's toolResultEntryIds). The branch walk is
   root→leaf, so a tool call executed twice resolves to its newest entry."
  [agent tool-call-ids]
  (if-let [sess (:session agent)]
    (let [by-call (reduce (fn [m e]
                            ;; kmet carries the id inside the :tool_result
                            ;; block (pi: ToolResultMessage.toolCallId)
                            (if-let [id (some #(when (= :tool_result (:type %))
                                                 (:tool_use_id %))
                                              (:content e))]
                              (assoc m id (:id e))
                              m))
                          {}
                          (session/get-branch sess))]
      (into [] (keep by-call) tool-call-ids))
    []))

(defn- boundary-state
  "The accumulated state an actionable boundary hands its handlers, merged
   into the event each one sees (pi: emitBoundary's {entries, continue} over
   the boundary context's pendingMessages/canContinue/context preview).
   Pending messages include the deferred custom queue (pi:
   _getPendingBoundaryMessages)."
  [agent]
  (merge {:pending-messages (into (state/peek-queued-messages agent) @(:pending-custom agent))
          :can-continue (boundary-can-continue? agent)
          :entries []
          :continue false}
         (boundary-context agent)))

(defn- emit-boundary
  "Route an actionable boundary event: the UI callback gets it as a plain
   notification and the listeners dispatch it as a boundary, where each handler
   sees the accumulated {:entries [] :continue false} state merged in (pi:
   emitBoundary). One table, so one dispatch threads one decision. Returns the
   accumulated handler result, or nil."
  [agent event]
  (state/emit agent event))

(defn- finish-turn!
  "Finish a turn (pi: the session's wrapped finishTurn, installed by
   _installAgentBoundaryHooks): run the loop's :finish-turn decision hook and
   dispatch the actionable :turn-end boundary, committing the entries its
   handlers return. The hook's :end wins (pi checks it first); :continue asks
   for one more request and is honored when the context can back it. Runs for
   errored and aborted turns too, whose decision the caller ignores. Returns
   the merged decision :end | :continue | nil."
  [agent turn-index assistant-msg tool-results]
  (let [;; the turn's outcome (pi: _dispatchTurnEndBoundary's lastActivityOutcome)
        ;; is `kmet.app.projections/outcome` — the same rule a projected turn
        ;; boundary reports, so the loop and the log cannot disagree about one turn
        outcome (projections/outcome assistant-msg)
        _ (reset! (:last-outcome agent) outcome)
        boundary (emit-boundary agent (assoc (boundary-state agent)
                                             :type :turn-end
                                             :turn-index turn-index
                                             :message assistant-msg
                                             :message-entry-id (:id assistant-msg)
                                             :tool-result-entry-ids
                                             (tool-result-entry-ids
                                              agent (map :id (:tool-calls assistant-msg)))
                                             :tool-results tool-results
                                             :outcome outcome))
        boundary-continues? (and (requested-continuation? agent boundary :turn-end)
                                 (honor-continuation? agent :turn-end))
        decision (when-let [f @(:finish-turn agent)]
                   (f (turn-context agent turn-index assistant-msg tool-results)))]
    ;; pi: _handleAgentEvent flushes deferred custom messages after every
    ;; turn's boundary dispatch — the first point in the run where a context
    ;; append cannot land between a tool call and its result
    (state/flush-pending-custom-messages! agent)
    (cond
      (= :end decision) :end
      (or boundary-continues? (= :continue decision)) :continue
      :else nil)))

(defn- complete-turn!
  "Complete a turn — pi's hook order: prepareNextTurn, then finishTurn (see
   finish-turn!). Returns the merged decision :end | :continue | nil."
  [agent turn-index assistant-msg tool-results]
  (prepare-next-turn! agent turn-index assistant-msg tool-results)
  (finish-turn! agent turn-index assistant-msg tool-results))

(defn- prepare-run!
  "Per-run setup (pi: _systemPromptOverride / _overflowRecoveryAttempted
   resets, the per-prompt compaction-blocked reset, the submitted user
   message, before-agent-start hook overrides + injected messages,
   proactive pre-run compaction)."
  [agent message images]
  (reset! (:system-prompt-override agent) nil)
  (reset! (:overflow-recovered agent) false)
  (compact/reset-compaction-blocked! agent)
  ;; pi: prompt() flushes messages a previous run deferred before building
  ;; the new prompt
  (state/flush-pending-custom-messages! agent)
  ;; :agent-before-settle reports this run's last turn outcome
  (reset! (:last-outcome agent) :completed)
  ;; Repeat-loop guard is per-run: a legit bash ls x2 in run 1 must not
  ;; count toward a trip in run 2
  (reset! (:loop-guard agent) {:window [] :suppressed 0})
  (when message
    (state/add-user-message! agent message images))
  ;; pi: pending nextTurn messages ride with the prompt, after the user
  ;; message and before before_agent_start's injected messages
  (state/inject-next-turn-messages! agent)
  (let [bas (extensions/apply-before-agent-start-hooks
             message @(:system agent))]
    (when (:system-prompt bas)
      (reset! (:system-prompt-override agent) (:system-prompt bas)))
    (doseq [m (:messages bas)]
      (state/add-custom-message! agent m)))
  (compact/maybe-compact! agent))

(defn- close-retry-and-surface-error!
  "Close any open retry span and surface ERR to the UI + extension bus: the
   failure :auto-retry-end when a retry was in flight, on-error, and the
   :error event. Marks the status errored; the caller returns it to idle (and
   emits the attempt's :agent-end when it has not fired yet)."
  [agent err on-error]
  (when (pos? @(:retry-count agent))
    (let [n @(:retry-count agent)]
      (state/emit agent {:type :auto-retry-end
                         :success false
                         :attempt n
                         :final-error err})
      (reset! (:retry-count agent) 0)))
  (when on-error (on-error err))
  (reset! (:status agent) :error)
  (state/emit agent {:type :error :message err}))

(defn- terminal-error!
  "Non-retryable error or exhausted budget: close out any open retry span,
   surface to UI + bus, emit the attempt's :agent-end, mark errored, end the
   run. The status atom returns to :idle — the run is fully over (pi:
   agent_settled fires in a finally after every run, success or error, and
   the session is idle); a sticky :error would make /compact, /reload and the
   extension :is-idle check refuse until the next successful run. The attempt
   outcome is :error — the prompt still runs the pre-settle boundary (pi: a
   terminal error reaches _runBeforeSettleBoundary), while on-done stays
   unsent: the error was surfaced instead."
  [agent err on-error agent-end]
  (close-retry-and-surface-error! agent err on-error)
  ;; Will not be retried — pi: _willRetryAfterAgentEnd is false here.
  (agent-end err false)
  (state/set-agent-status! agent :idle)
  {:status :error})

(defn- tools-phase!
  "Execute a turn's tool calls (pi: assistant message → executing status →
   results → thinking → turn-end → mid-run compaction). Returns:
     nil         — continue the inner loop with this batch's tool results
     :settled    — settle after this batch (a :finish-turn / :turn-end :end
                   decision, or every call blocked with :terminate)
     :continue   — run one more turn even though nothing else would
     :loop-guard — repeat-loop guard tripped (caller settles the run)

   Repeat-loop guard (kmet-specific): the batch is filtered through the
   `:loop-guard` stage's handle, read from the attempt's stage map — the
   agent's ctx carries the stage. Its config supplies
   the threshold when the node carries one; cfg's value is the default.
   Suppressed calls get a synthetic
   guard result (transcript stays coherent — no dangling tool ids, and the
   user sees why in the tool box); survivors execute normally. When a batch is
   ALL suppressed — or cumulative suppressions reach 2×threshold (mixed
   alternating batches) — the guard trips. A successful write/edit resets the
   guard's memory of other calls (the world changed, so a repeated call
   observes new state); failed, blocked, or suppressed writes reset nothing,
   and each writer's own signature is kept.

   REQUEST-TOOLS is the request's captured tool list, in registry order: the
   guard's survivors execute against it, so a tool unloaded after the request
   was built still runs."
  [agent stages t result request-tools]
  (let [assistant-msg (state/add-assistant-message! agent result)
        tool-calls (:tool-calls result)]
    (state/set-agent-status! agent :executing {:tool-calls tool-calls})
    (let [threshold (or (get-in stages [:loop-guard :config :threshold])
                        (:loop-guard-threshold @(:cfg agent)))
          guard-enabled? (:loop-guard-enabled @(:cfg agent))
          guard-filter (when guard-enabled?
                         (get-in stages [:loop-guard :call]))
          {:keys [suppressed survivors state]}
          (if guard-enabled?
            (guard-filter @(:loop-guard agent) threshold tool-calls)
            {:suppressed [] :survivors tool-calls :state @(:loop-guard agent)})
          suppressed? (seq suppressed)
          guard-result (fn [tc]
                         {:content (str "Repeat-loop guard: " (:name tc)
                                        " called with identical args "
                                        threshold " times — suppressed.")
                          :is-error false})
          ;; Suppressed calls become synthetic results; survivors run through
          ;; the normal execution path (parallel/sequential). Results are
          ;; returned in source order: walk the original tool-calls and pick
          ;; the guard result for suppressed ones, the exec result for the
          ;; rest (exec-results is in the survivors' source order).
          ;; Executor does NOT append to context (append? false) — the guard
          ;; path appends ALL results (suppressed + survivor) in source order
          ;; below so tool_use/tool_result ordering stays strict-provider-safe
          exec-result (when (seq survivors)
                        (tool-exec/execute-tool-calls! agent survivors assistant-msg request-tools false))
          exec-results (or (:results exec-result) [])
          terminate? (:terminate exec-result)
          ;; Successful writes change the world, so earlier repeats observed
          ;; stale state: keep only the writers' own signatures (repeating
          ;; the identical write still trips) and clear the cumulative
          ;; count, so a test -> edit(ok) -> test cycle doesn't false-positive.
          ;; Failed, blocked, or suppressed writes change nothing.
          reset-sigs (if guard-enabled?
                       (into #{}
                             (keep (fn [[tc res]]
                                     (when (and (contains? loop-guard/loop-guard-reset-tools (:name tc))
                                                (not (:is-error res)))
                                       (loop-guard/loop-guard-signature (:name tc) (:arguments tc)))))
                             (map vector survivors exec-results))
                       #{})
          ;; Single state write: the pre-exec intermediate is unobservable
          ;; (nothing during execution reads the guard atom), so persist the
          ;; pruned-or-original state once.
          _ (reset! (:loop-guard agent)
                    (if (seq reset-sigs)
                      {:window (filterv reset-sigs (:window state))
                       :suppressed 0}
                      state))
          give-up? (and (empty? reset-sigs)
                        (or (and suppressed? (empty? survivors))
                            (>= (:suppressed state) (* 2 threshold))))
          suppressed-by-id (into {} (map (fn [tc] [(:id tc) (guard-result tc)]))
                                 suppressed)
          ;; exec-results corresponds to SURVIVORS positionally (the
          ;; executor returns results in source order)
          results-by-id (merge suppressed-by-id
                               (into {} (map (fn [tc res] [(:id tc) res])
                                             survivors exec-results)))
          results (mapv #(get results-by-id (:id %)) tool-calls)
          ;; Append ALL results to context/session in source order (the
          ;; executor skipped appends). Suppressed calls also get their
          ;; tool-execution start/end events here (the executor never saw
          ;; them); survivor events were already emitted by the executor —
          ;; only the context append is repeated here, in the right order.
          _ (doseq [tc tool-calls]
              (let [tc-id (:id tc) tc-name (:name tc) tc-args (:arguments tc)
                    res (get results-by-id (:id tc))
                    suppressed? (contains? suppressed-by-id tc-id)
                    result-msg (message/tool-result-message tc-id tc-name res)]
                (when suppressed?
                  (state/emit agent {:type :tool-execution-start
                                     :tool-call-id tc-id :tool-name tc-name :args tc-args})
                  (state/emit agent {:type :tool-execution-end
                                     :tool-call-id tc-id :tool-name tc-name :args tc-args
                                     :result res
                                     :is-error (:is-error res false)}))
                (state/append-persisted-message! agent result-msg)))]
      (state/set-agent-status! agent :thinking)
      ;; pi: the post-turn hooks and the :turn-end boundary run
      ;; unconditionally — even when terminate stops the tool-call continuation
      (let [decision (complete-turn! agent t assistant-msg results)]
        ;; Proactive mid-run compaction, after the turn boundary and before
        ;; the next request (pi: the next turn's preparation compacts)
        (compact/maybe-compact! agent)
        (cond
          give-up? :loop-guard
          (= :end decision) :settled
          (= :continue decision) :continue
          terminate? :settled
          :else nil)))))

(defn- final-phase!
  "Complete a final response (no tool calls): assistant message + the turn
   boundary. Returns the merged decision — :end (settle), :continue (one more
   turn), or nil (the loop settles naturally)."
  [agent t result]
  (complete-turn! agent t (state/add-assistant-message! agent result) []))

(defn- attempt-call-provider!
  "Issue one request and collect its normalized result (deref fallback
   deadline included). Returns {:request-tools ts :result r}, plus :call —
   the promise and its partials snapshot — only for the timeout sentinel,
   the one outcome whose partials are not in the result map."
  [{:keys [agent text-buf on-text on-thinking]}]
  (let [;; one read per request: the wire list, the declared set and the
        ;; dispatch below all resolve against it
        request-tools (state/active-tools agent)
        {:keys [promise] :as call}
        (do (reset! text-buf "")
            (completion/call-llm agent (completion/resolve-api-key agent) text-buf on-text on-thinking request-tools))]
    (reset! (:active-call agent) call)
    ;; A cancel that landed while call-llm was still executing saw no
    ;; active call to deliver (the promise is installed only now, and a
    ;; test's/send-message's very first callback can precede it). Deliver
    ;; here too so the abort cannot be lost: deliver is a no-op once
    ;; realized, so an already-arrived normal result is never overridden.
    (when @(:signal agent)
      (deliver promise (merge {:cancelled true} ((:partials call)))))
    (let [result (let [deadline (retry/llm-total-timeout-ms @(:cfg agent))]
                   (retry/normalize-llm-result
                    (if (pos? deadline)
                      (deref promise deadline :timeout)
                      @promise)))]
      (reset! (:active-call agent) nil)
      (cond-> {:request-tools request-tools :result result}
        (= :timeout result) (assoc :call call)))))

(defn- attempt-retry-action
  "The retry policy's verdict for a failed request (pi: shouldRetry) — the
   policy, the current budget, and whether a persisted session can take the
   recovery entry."
  [{:keys [agent retry-decide retry-policy]} err]
  (retry-decide
   (merge retry-policy
          {:err err
           :retry-count @(:retry-count agent)
           :overflow-recovered @(:overflow-recovered agent)
           :has-session (some? (:session agent))})))

(defn- attempt-cancelled!
  "The run signal fired during the request: persist the abandoned attempt,
   close its turn, and report the aborted outcome."
  [{:keys [agent agent-end]} t result]
  (finish-turn! agent t (state/record-abandoned-attempt!
                         agent result :aborted) [])
  (agent-end nil false)
  {:status :aborted})

(defn- attempt-timed-out!
  "The deref fallback deadline fired (the transport's own total deadline
   normally fires first and delivers a retryable :error). Treated as the same
   retryable timeout error — never a silent hard abort."
  [{:keys [agent agent-end on-error] :as attempt} t call]
  (let [err (str "LLM call timed out after "
                 (retry/llm-total-timeout-ms @(:cfg agent)) "ms")
        ;; The deref sentinel (:timeout) carries no partials, but the
        ;; in-flight call's partials snapshot does — keep whatever streamed
        ;; before the deadline (pi: a failed stream's partial becomes the
        ;; final message) so a stalled attempt never loses its
        ;; text/thinking, while the retry classifier still sees a normal
        ;; error map.
        msg (state/record-abandoned-attempt!
             agent (merge ((:partials call)) {:error err})
             :error)]
    (finish-turn! agent t msg [])
    (let [{:keys [kind] :as action} (attempt-retry-action attempt err)]
      (if (= :backoff kind)
        (do (agent-end err true)
            {:status :retry :error err :action action
             :attempt-entry msg})
        (terminal-error! agent err on-error agent-end)))))

(defn- attempt-failed!
  "The request returned an error: classify it. The thinking-loop guard
   settles the attempt with its explanation, context overflow and backoff
   return the retry verdict to the caller, and everything else is terminal."
  [{:keys [agent agent-end text-buf on-error] :as attempt} t result]
  (if (= loop-guard/thinking-loop-error (:error result))
    ;; Thinking-loop guard tripped: not a transient error — settle the run
    ;; with the explanation (no auto-retry, no red error line; the
    ;; :loop-guard event shows a warning)
    (do (finish-turn! agent t (state/record-abandoned-attempt!
                               agent result :error) [])
        (loop-guard-give-up!
         agent text-buf :thinking
         "Stopped: repeated reasoning detected (thinking-loop guard)")
        {:status :settled :turn (inc t)})
    (let [err (:error result)
          ;; Persist the failed attempt before classifying — session history
          ;; only, never the live context (pi: _prepareRetry drops the errored
          ;; message from agent state while keeping it in the file). pi runs
          ;; finishTurn/turn_end for an errored response too (the decision is
          ;; dropped).
          msg (state/record-abandoned-attempt! agent result :error)
          _ (finish-turn! agent t msg [])
          {:keys [kind] :as action} (attempt-retry-action attempt err)]
      (case kind
        ;; Context overflow → the caller compacts once, then retries (pi:
        ;; overflow is compaction territory, not auto-retry)
        :overflow-recover
        (do (agent-end err false)
            {:status :overflow :error err
             :attempt-entry msg})

        ;; Auto-retry with exponential backoff (pi: _prepareRetry); the
        ;; caller sleeps and starts the next attempt
        :backoff
        (do (agent-end err true)
            {:status :retry :error err :action action
             :attempt-entry msg})

        ;; Terminal error (non-retryable or retries exhausted)
        :terminal
        (terminal-error! agent err on-error agent-end)))))

(defn- attempt-succeeded!
  "The request succeeded: reset the retry/overflow-recovery bookkeeping, then
   turn the response into the next turn step — a tool batch or a final
   response. A step is {:step :continue :turn t :tool-calls ts :must-run b};
   every other return is a terminal attempt outcome."
  [{:keys [agent stages text-buf]} t result request-tools]
  (let [retried @(:retry-count agent)]
    ;; A retried LLM call succeeded — reset the budget
    (when (pos? retried)
      (reset! (:retry-count agent) 0)
      (state/emit agent {:type :auto-retry-end
                         :success true
                         :attempt retried}))
    (when @(:overflow-recovered agent)
      ;; A non-error message ends overflow recovery (pi resets on success)
      (reset! (:overflow-recovered agent) false))
    (let [tool-calls (:tool-calls result)
          turn' (inc t)
          settled {:status :settled :turn turn'}
          continue-step (fn [calls must-run]
                          {:step :continue :turn turn'
                           :tool-calls calls :must-run must-run})]
      (if (seq tool-calls)
        (case (tools-phase! agent stages t result request-tools)
          ;; the guard settles the run with the explanation as the final text
          :loop-guard
          (do (loop-guard-give-up!
               agent text-buf :tool-calls
               (str "Stopped: repeated identical tool calls "
                    "(threshold " (:loop-guard-threshold @(:cfg agent)) ")"))
              settled)
          :settled settled
          ;; a :finish-turn / :turn-end decision asked for one more request
          :continue (continue-step tool-calls true)
          (continue-step tool-calls false))
        (case (final-phase! agent t result)
          :end settled
          :continue (continue-step [] true)
          (continue-step [] false))))))

(defn- run-turn!
  "One turn of an attempt: drain the steering queue, run the request, and
   interpret the outcome. Returns {:step :continue :turn t :tool-calls ts
   :must-run b} for the next turn of the attempt, or a terminal attempt
   outcome map — {:status :settled | :aborted | :retry | :overflow | :error
   …}, the step's absence of :step telling the two apart."
  [{:keys [agent] :as attempt} t]
  ;; The injection drains the steering queue and refreshes the pending
  ;; display (pi: message_start → queue_update)
  (state/inject-queued-messages! agent (:steering agent)
                                 (:steering-mode @(:cfg agent)))
  (state/emit agent {:type :turn-start :turn-index t})
  ;; pi: config.prepareRequest — the per-request checkpoint, after the queued
  ;; messages joined the context and before the request is built
  (prepare-request! agent)
  (let [{:keys [call request-tools result]} (attempt-call-provider! attempt)]
    (cond
      (:cancelled result) (attempt-cancelled! attempt t result)
      (= :timeout result) (attempt-timed-out! attempt t call)
      (:error result) (attempt-failed! attempt t result)
      :else (attempt-succeeded! attempt t result request-tools))))

(defn- run-attempt!
  "Run one low-level agent attempt — one :agent-start/:agent-end pair
   (pi: agent.prompt for the first attempt, agent.continue for retries).
   Returns the attempt's outcome for the caller's state machine:
     {:status :settled :text t} — settled normally; t is the attempt's final
                                  response text. The caller owns on-done:
                                  a message an :agent-end handler queues can
                                  start another attempt in the same prompt,
                                  and on-done is the prompt's teardown.
     {:status :aborted}    — cancelled (the run signal fired)
     {:status :error}      — terminal error: non-retryable, or the retry
                             budget is spent. The error was surfaced; on-done
                             stays unsent
     {:status :retry   :error err :action action} — transient error, budget left
     {:status :overflow :error err}               — context overflow
   :retry and :overflow carry :attempt-entry — the persisted session entry
   of the failed assistant message record-abandoned-attempt! returned — which
   the caller durably omits from the model projection before retrying (pi:
   _prepareRetry / the overflow case both call _omitRecoveryAttempt).
   :agent-end is emitted here with this attempt's accumulated messages
   (RUN-TOKEN keys the accumulator) and :will-retry."
  [agent run-token {:keys [on-text on-thinking on-error]}]
  (let [stage-map (loop-stages/for-ctx (:ctx agent)) ; the agent ctx's stages, one read per attempt
        agent-end (fn [error will-retry]
                    (when-let [s (:session agent)]
                      (when (pos? (get-in (session/tool-usage s)
                                          [:total :calls] 0))
                        (debug/log "tool usage (estimated result tokens):\n"
                                   (session/tool-usage-report s))))
                    (let [event {:type :agent-end
                                 :messages (get @(:run-messages agent) run-token)
                                 :error error
                                 :will-retry (boolean will-retry)}]
                      ;; one table carries one copy, and it is the decorated
                      ;; one (:will-retry) the front end reads
                      (state/emit agent event)))
        attempt {:agent agent
                 :stages stage-map
                 :retry-decide (get-in stage-map [:retry-policy :call])
                 :retry-policy (compact/stage-retry-policy agent stage-map)
                 :text-buf (atom "")
                 :on-text on-text
                 :on-thinking on-thinking
                 :on-error on-error
                 :agent-end agent-end}]
    ;; Bind this attempt's token for its whole extent so every append it makes
    ;; records against it even if a newer prompt replaces the agent's
    ;; :run-token (see *run-token*).
    (binding [state/*run-token* run-token]
      ;; Outer loop: follow-up. No turn limit — the inner loop settles
      ;; only when the model stops calling tools and no messages are
      ;; queued (pi: runLoop while(true)).
      (loop [turn 0]
      ;; Inner loop: LLM → tools → steer → ... → settle
        (let [inner (loop [t turn prev-tool-calls [] must-run true]
                      (if (or @(:signal agent)
                              (and (not must-run)
                                   (empty? prev-tool-calls)
                                   (empty? @(:steering agent))))
                        (if @(:signal agent)
                          (do (state/set-agent-status! agent :idle)
                              (agent-end nil false)
                              {:status :aborted}) ;; cancelled — exit quietly
                          {:status :settled :turn t})
                        (let [step (run-turn! attempt t)]
                          (if (= :continue (:step step))
                            (recur (:turn step) (:tool-calls step) (:must-run step))
                            step))))]
          (if (contains? #{:retry :overflow :aborted :error} (:status inner))
            ;; RETRY/OVERFLOW/ABORTED/ERROR are already fully-tagged outcomes
            ;; (they emitted their :agent-end); only a settled attempt
            ;; continues into the follow-up drain.
            inner
            (let [turn' (:turn inner)]
              ;; Outer: poll the follow-up queue (pi: runLoop's outer poll)
              (if (pos? (state/inject-queued-messages! agent (:follow-up agent)
                                                       (:follow-up-mode @(:cfg agent))))
                (do (state/set-agent-status! agent :thinking)
                    (recur turn'))
                (do (state/set-agent-status! agent :idle)
                    (agent-end nil false)
                    {:status :settled :text @(:text-buf attempt)})))))))))

(defn- before-settle!
  "Run the actionable :agent-before-settle boundary (pi:
   _runBeforeSettleBoundary): handlers may commit entries and/or ask for one
   more request. Returns true when the prompt should run another attempt —
   the continuation is runnable and the prompt was not cancelled while the
   handlers ran (pi: _abortDuringBeforeSettle)."
  [agent]
  (let [boundary (emit-boundary agent (assoc (boundary-state agent)
                                             :type :agent-before-settle
                                             :outcome @(:last-outcome agent)))
        requested? (requested-continuation? agent boundary :agent-before-settle)]
    ;; pi: _runBeforeSettleBoundary flushes deferred custom messages after
    ;; the boundary's entries are committed, before the continuation check
    (state/flush-pending-custom-messages! agent)
    (and (not @(:signal agent))
         (or requested? (state/has-queued-messages? agent))
         (honor-continuation? agent :agent-before-settle))))

(defn- continue-prompt?
  "Whether the prompt runs another attempt instead of settling: a message
   queued while the attempt ended (pi: _handlePostAgentRun's
   hasQueuedMessages), or the actionable :agent-before-settle boundary (pi:
   _runBeforeSettleBoundary). The caller drains the next queue batch into that
   attempt's prompt (pi: agent.continue, which runs the drained messages as
   the continuation prompt). A cancelled prompt never continues."
  [agent]
  (and (not @(:signal agent))
       (or (state/has-queued-messages? agent)
           (before-settle! agent))))

(def ^:private attempt-routes
  "The prompt state machine's routing table: one handler per `run-attempt!`
   outcome. A handler receives the driver context — `:agent`, `:on-done`,
   `:on-error`, `:begin-attempt!` (fresh accumulator) and
   `:continue-attempt!` (drain the queue, then begin) — and the attempt's
   outcome, and returns `:again` to run another attempt or `:stop` to settle
   the prompt. `run-agent-turn` owns the `recur`."
  {:settled
   (fn [{:keys [agent on-done continue-attempt!]} outcome]
     ;; pi: _handlePostAgentRun's post-run compaction check, then
     ;; queued-message continuation, then _runBeforeSettleBoundary. A
     ;; threshold compaction never retries the turn (pi: _checkCompaction
     ;; returns hasQueuedMessages). on-done stays the prompt's teardown
     ;; (the UI's running-turn? hinges on it) and fires only once no
     ;; attempt is pending.
     (compact/maybe-compact! agent)
     (if (continue-prompt? agent)
       (do (continue-attempt!) :again)
       (do (when on-done (on-done (:text outcome)))
           :stop)))

   :error
   (fn [{:keys [agent continue-attempt!]} _outcome]
     ;; pi: a terminal error ends the attempt, but _handlePostAgentRun still
     ;; runs the compaction check (an error response has no usable usage, so
     ;; the estimate decides) before the pre-settle boundary — a handler can
     ;; revive the prompt. on-done stays unsent: the error was surfaced
     ;; instead.
     (compact/maybe-compact! agent)
     (if (continue-prompt? agent)
       (do (continue-attempt!) :again)
       :stop))

   :aborted
   (fn [_driver _outcome]
     ;; Cancelled: the prompt ends here, without the pre-settle boundary
     ;; (pi: the prompt loop's abort exits before _runBeforeSettleBoundary;
     ;; the :turn-end boundary of an aborted turn has already run).
     :stop)

   :retry
   (fn [{:keys [agent begin-attempt!]} outcome]
     (let [{:keys [error action]} outcome
           {:keys [attempt delay-ms max-attempts]} action]
       (reset! (:retry-count agent) attempt)
       (state/emit agent {:type :auto-retry-start
                          :attempt attempt
                          :max-attempts max-attempts
                          :delay-ms delay-ms
                          :error-message error})
       ;; pi: _prepareRetry omits the failed attempt from the model
       ;; projection before the backoff, so the retried request (and every
       ;; later rebuild) cannot re-send it
       (compact/omit-recovery-attempt! agent (:attempt-entry outcome))
       (if (retry/backoff-sleep! (:signal agent) delay-ms)
         ;; Same context — a new low-level run with a fresh accumulator
         ;; (pi: agent.continue, one agent_end per attempt)
         (do (begin-attempt!) :again)
         ;; Cancelled during backoff
         (do (state/emit agent {:type :auto-retry-end
                                :success false
                                :attempt attempt
                                :final-error "Retry cancelled"})
             (reset! (:retry-count agent) 0)
             (state/set-agent-status! agent :idle)
             :stop))))

   :overflow
   (fn [{:keys [agent on-error begin-attempt! continue-attempt!]} outcome]
     (let [error (:error outcome)]
       (reset! (:overflow-recovered agent) true)
       ;; pi: _checkCompaction's overflow case omits the failed attempt
       ;; before the recovery compaction, so neither the compaction input
       ;; nor the retry re-sends it
       (compact/omit-recovery-attempt! agent (:attempt-entry outcome))
       (if (= true (compact/compact-for-overflow! agent))
         ;; The compaction shrank the context — retry the interrupted turn
         ;; (pi: _checkCompaction → continue).
         (do (begin-attempt!) :again)
         ;; Nothing to compact, or the compaction failed/was aborted: a
         ;; retry would overflow again. pi settles here (_checkCompaction
         ;; returns false); kmet surfaces the overflow so the turn does not
         ;; end silently and the UI leaves "Working". The pre-settle
         ;; boundary still runs, as it does after any non-retry attempt.
         (do (close-retry-and-surface-error! agent error on-error)
             (if (continue-prompt? agent)
               (do (continue-attempt!) :again)
               (do (state/set-agent-status! agent :idle)
                   :stop))))))})

(defn- settle-boundary!
  "The turn's closing boundary: apply what other threads submitted while the
   prompt ran, then let the kernel settle.

   The kernel has no lock, and `plugin!`/`restart!`/`dispose!` run plugin code,
   so a tool thread that must mount or unmount **submits**, and this is the one
   drain point a kmet2 process has (`srce.md` §8). It runs *before*
   `:agent-settled` is emitted — that event means both that no turn is in flight
   and that no refresh is pending, so a front end that hears it and reads the
   tree sees the applied change.

   A failure is logged rather than raised: every front end waits for
   `:agent-settled`, and a drain that threw past this point would hang the
   conversation it was closing. A ctx outside a booted composition has no queue
   and nothing to drain."
  [agent]
  (when-let [ctx (:ctx agent)]
    (when (runtime/submitting? ctx)
      (try
        (runtime/boundary! ctx)
        (catch Throwable t
          (srce/log! ctx :error "the turn boundary failed" t))))))

(defn run-agent-turn
  "Run the agent loop until it settles, owning the attempt/retry state
   machine (pi: _runAgentPrompt). Each attempt is a low-level run with its
   own :agent-start/:agent-end pair and its own accumulated messages
   (pi: agent.prompt then agent.continue), and exactly one :agent-settled
   fires once the prompt is fully settled. A transient error retries with
   exponential backoff (pi: _prepareRetry) and a context overflow compacts
   once and retries; every settled or terminally-errored attempt then runs
   the post-run compaction check (pi: _handlePostAgentRun → _checkCompaction)
   and the prompt-level continuation checks — queued messages and the
   actionable :agent-before-settle boundary — and a cancel ends the prompt.

   agent    — AgentState record
   opts:
     :message  — optional initial user message string
     :images   — optional vector of image content blocks
                 ({:type :image :data base64 :mime-type str}) attached to
                 the initial user message (pi: image attachments)
     :on-text  — (fn [text-delta]) streaming text callback
     :on-done  — (fn [response-text]) final response callback; runs once per
                 prompt, when it settles (not on cancel or terminal error,
                 and not between the attempts of one prompt)
     :on-error — (fn [error]) error callback

   Loop structure (mirrors pi):
     attempt: drain follow-up queue → continue inner
     inner:   LLM call → tool execution → drain steering queue → repeat
              until no tool calls, no steering messages, and at least one
              turn has run.
     settle:  the post-run compaction check first (pi: _handlePostAgentRun →
              _checkCompaction — threshold only; a threshold compaction
              never retries the turn), then a message queued by an
              :agent-end handler starts another attempt (pi: hasQueuedMessages
              → agent.continue, which drains the queues into the new attempt's
              prompt), then the actionable :agent-before-settle boundary may
              ask for one (pi: _runBeforeSettleBoundary); with no attempt
              pending the prompt settles — on-done runs and :agent-settled
              fires from the finally.
   The attempt/retry state machine dispatches each `run-attempt!` outcome
   through `attempt-routes`; a handler returns :again (another attempt) or
   :stop (settle), and this function owns the `recur`.

   Cancellation: cancel-turn sets the signal and delivers {:cancelled true}
   to the in-flight LLM promise (active-call); the attempt exits quietly
   (no on-error, no on-done) and the prompt settles.

   Emits lifecycle events (see kmet.app.events) to the UI callback and the
   listeners via emit. The prompt settles exactly once with
   :agent-settled, which carries :outcome (:completed | :error | :aborted —
   :aborted when the cancel signal fired; a prompt that cannot start also
   settles, with :error).
   Returns: future that completes when the prompt is done."
  [agent {:keys [message images on-text on-thinking on-done on-error]}]
  (state/set-run-signal! agent false)
  (let [provider @(:provider agent)
        api-key (completion/resolve-api-key agent)
        opts {:on-text on-text :on-thinking on-thinking
              :on-error on-error}]
    (if (not (or api-key (auth/configured? provider)))
      (do (when message
            ;; The run cannot start, but the submitted message is still shown
            ;; in the chat (pi emits message_start for prompt messages before
            ;; running the loop). Display-only: not added to context or
            ;; session since no LLM call will consume it.
            (state/emit agent {:type :message-start
                               :message (message/user-message message images)}))
          (let [reason (str "No API key for " (name provider)
                            ". Set the key in ~/.kmet/agent/auth.edn or the appropriate environment variable.")]
            (when on-error (on-error reason))
            ;; The prompt cannot start, but a streaming consumer waits for
            ;; :agent-settled — settle it like any other failed prompt. Record
            ;; the outcome too: the settle event carries it, and a state
            ;; consumer (get-state / a client's wait-idle) reads it back from
            ;; :last-outcome, which no finally touches on this early-return
            ;; path. The :error event is the terminal/catch paths' too: a
            ;; front end that only reads the event stream (print mode, the
            ;; TUI) must see the failure, not a silent settle.
            (reset! (:last-outcome agent) :error)
            (state/emit agent {:type :error :message reason})
            (settle-boundary! agent)
            (state/emit agent {:type :agent-settled :outcome :error}))
          (future))
      ;; Bind the run's cancel signal for the whole prompt: it conveys into the
      ;; tool futures AND into synchronous extension code (custom tool
      ;; executes, event handlers) that calls tools/execute-tool, so Escape
      ;; cancels bash everywhere, not just in the loop's own tool futures.
      ;; The session-env thunk resolves per bash execution (pi: the execute
      ;; ctx), so a mid-run model/thinking change is reflected.
      ;; Relative tool paths resolve against the session's cwd for the whole
      ;; run (pi: the runtime cwd the tool definitions were created with) —
      ;; a session resumed or imported from another project.
      (binding [bash-tool/*cancel-signal* (:signal agent)
                ;; the agent's own :shell value (the row's, or one a preset
                ;; shadowed on its ctx) — two agents run two shells
                bash-tool/*shell* (some-> (srce/get (:ctx agent) :shell) deref)
                ;; and its own skills: an extension skill's <location> is
                ;; served through read from this agent's registry, not the
                ;; process's (landing 9b)
                read-tool/*skill-resolver*
                (some-> (srce/get (:ctx agent) :skills) skills/content-resolver)
                bash-tool/*session-env-fn*
                (fn []
                  (bash-tool/session-env {:session (:session agent)
                                          :provider @(:provider agent)
                                          :model @(:model agent)
                                          :thinking-level @(:thinking agent)}))
                tools-util/*cwd* (or (session/session-cwd (:session agent))
                                     (tools-util/cwd))
                ;; a thunk, not a snapshot: set-active-tools! takes effect on
                ;; the next turn, and the run_code sandbox keys its context on
                ;; the set it resolves at call time. The effective active set
                ;; is resolved (not just :enabled-tools) so a :default-tools
                ;; selection hides the built-ins it disables from scripts too
                ;; (pi: the codemode callable set is the active direct tools)
                run-code-tool/*enabled-tools-fn*
                (fn []
                  (when (or @(:enabled-tools agent) @(:default-tools agent))
                    (into #{} (map :name) (state/active-tools agent))))
                ;; the run's agent-level tool hooks as thunks — a hook set
                ;; mid-run is picked up, and scripted inner calls go through
                ;; the same before/after semantics as the loop's own batches
                run-code-tool/*tool-hooks*
                {:before (permissions/gate
                          (fn [ctx] (when-let [h @(:before-tool-call agent)] (h ctx)))
                          (:ctx agent) (:signal agent))
                 :after (fn [ctx] (when-let [h @(:after-tool-call agent)] (h ctx)))}]
        (future
          (let [attempt-token (atom nil)
                begin-attempt! (fn []
                                 (let [tok (Object.)
                                       old @attempt-token]
                                   (reset! attempt-token tok)
                                   (reset! (:run-token agent) tok)
                                   (swap! (:run-messages agent) assoc tok [])
                                   (when old
                                     (swap! (:run-messages agent) dissoc old))))
                ;; pi: agent.continue() — the queued batch becomes the next
                ;; attempt's prompt (steering first, then follow-up, per mode)
                continue-attempt! (fn [] (state/drain-next-queue! agent) (begin-attempt!))
                driver {:agent agent
                        :on-done on-done
                        :on-error on-error
                        :begin-attempt! begin-attempt!
                        :continue-attempt! continue-attempt!}]
            (try
              (begin-attempt!)
              ;; Prompt-level setup — the user message, before-agent-start
              ;; hooks, and the pre-run compaction check run once per prompt,
              ;; not once per attempt (pi: prompt() vs continue()).
              (prepare-run! agent message images)
              (loop []
                (state/emit agent {:type :agent-start})
                (state/set-agent-status! agent :thinking)
                (let [outcome (run-attempt! agent @attempt-token opts)
                      ;; Unreachable: run-attempt! returns one of the five
                      ;; outcomes. Throwing (rather than ignoring a failed
                      ;; look-up) names the culprit.
                      handler (or (attempt-routes (:status outcome))
                                  (throw (ex-info "Unknown attempt outcome"
                                                  {:outcome outcome})))]
                  (when (= :again (handler driver outcome))
                    (recur))))
              (catch Exception e
                (reset! (:status agent) :error)
                ;; The settle event reports the run's outcome; an exception
                ;; bypasses the turn-end bookkeeping that normally sets it.
                (reset! (:last-outcome agent) :error)
                ;; Report to the UI first — the :error emit goes to the
                ;; extension event bus, which must not be able to skip the
                ;; user-visible error path.
                (when on-error (on-error (ex-message e)))
                (state/emit agent {:type :error :message (ex-message e)})
                ;; Same settle rule as terminal-error!: an unhandled exception
                ;; ends the run, and the agent is idle afterwards.
                (state/set-agent-status! agent :idle))
              (finally
                ;; pi: _flushPendingBashMessages — bash results recorded while
                ;; streaming are queued to preserve tool_use/tool_result ordering
                ;; and land in the context/session once the prompt settles. The
                ;; flush appends context-only, so those entries stay out of
                ;; `agent-end :messages` (pi flushes after agent_end too).
                (state/flush-pending-bash-messages! agent)
                ;; pi: _flushPendingCustomMessages — custom messages deferred
                ;; while streaming land before the prompt settles
                (state/flush-pending-custom-messages! agent)
                ;; pi: agent_settled fires once in a finally, after the
                ;; pending-bash flush — success, retry, error, timeout, or
                ;; abort; the prompt is fully settled (agent-session.js).
                ;; :outcome lets a headless consumer tell how the prompt
                ;; ended: a fired cancel signal is :aborted, otherwise the
                ;; last turn's outcome.
                (settle-boundary! agent)
                (state/emit agent {:type :agent-settled
                                   :outcome (if @(:signal agent)
                                              :aborted
                                              @(:last-outcome agent))})
                ;; Drop this prompt's accumulator entries: a newer prompt may
                ;; already own the map (cancel clears the running flag before
                ;; this finally runs).
                (when-some [tok @attempt-token]
                  (when (identical? @(:run-token agent) tok)
                    (reset! (:run-token agent) nil))
                  (swap! (:run-messages agent) dissoc tok))))))))))

(defn cancel-turn
  "Cancel the current agent run: signal the LLM stream, drop queued messages
   (the resulting :queue-update carries :reason :aborted, so a headless
   client can tell the drop from a drain), deliver the in-flight LLM call
   ({:cancelled true} plus the partials snapshot so the abandoned attempt
   keeps what arrived), return status to :idle. A live compaction is aborted
   too (pi: abort() calls abortCompaction())."
  [agent]
  (state/set-run-signal! agent true)
  (compact/abort-compaction! agent)
  (state/clear-queues! agent :aborted)
  (when-let [{:keys [promise partials]} @(:active-call agent)]
    (when-not (realized? promise)
      (deliver promise (merge {:cancelled true} (partials)))))
  (state/set-agent-status! agent :idle)
  nil)

