(ns kmet.app.event-bus
  "Agent loop event vocabulary + extension event bus.

   Defines the canonical set of event types emitted by the agent loop
   (kmet.app.loop/run-agent-turn) and routed to the UI (:on-event callback)
   and the extension system (emit-event!).

   Mirrors pi's extension event types — see
   src/kmet/development/pi-alignment.md, Appendix: Event
   Type Vocabulary — and pi's core/event-bus.js.")

(def event-types
  "Map of event type keyword → description. The canonical vocabulary.
   Every listed event is emitted by the loop, the interactive mode, or the
   app — see each entry's description for the emitting path."
  {:agent-start
   "Fired at the start of each attempt: once per user submission, and once
    more for every auto-retry, overflow-recovery, or agent-end-queued
    attempt (pi: agent_start, emitted by agent.prompt and by every
    agent.continue)."

   :agent-end
   "Fired when an attempt finishes (success or error) — one per attempt
    (pi: agent_end). Payload: :messages (the messages added during this
    attempt, accumulated as they are appended — pi: newMessages — so a
    mid-run context replacement does not shrink the list, and an aborted or
    errored attempt is included), :error (optional), :will-retry (true when
    this attempt's transient error will be retried; pi: willRetry, added for
    public listeners only — extensions never see the key). A handler that
    queues a message here (steer! / follow-up!) starts a fresh attempt in
    the same prompt, with the queued messages as its starting context (pi:
    _handlePostAgentRun's hasQueuedMessages → agent.continue); messages
    queued by :agent-settled handlers stay queued for the next submission."

   :turn-start
   "Fired before each LLM call.
    Payload: :turn-index."

   :turn-end
   "Actionable: fired after a turn completes — the LLM call plus any tool
    execution — including a response that errored or was aborted, where it
    precedes :agent-end (pi: turn_end, emitted by the loop's finishTurn
    call). Payload: :turn-index, :message, :tool-results, :outcome
    (:completed | :error | :aborted), plus the boundary state
    :pending-messages (the queued batches the next attempt would start
    from, plus custom messages deferred while streaming — pi:
    _getPendingBoundaryMessages), :can-continue, :entries [] and :continue
    false. Handlers may
    return {:entries [entry ...] :continue bool} (pi: emitBoundary): entries
    are session entry maps appended in order (pi: SessionBoundaryDraft — a
    :custom-message entry also enters the context) and :continue asks for
    one more request, honored once the entries are committed when the
    context can back it, and reported then dropped otherwise (:can-continue
    is that check as the boundary stood). Each handler sees the previous
    handlers' :entries/:continue merged in, as pi does."

   :message-start
   "Fired when a message is added to the context, or when assistant
    streaming begins.
    Payload: :message."

   :message-update
   "Fired for each streaming delta (assistant only).
    Payload: :message (partial), :delta
    {:type :text | :thinking | :tool-call ...}."

   :message-end
   "Fired when an assistant message is finalized — added to context on
    success, or recorded in the session history only when the LLM call
    failed or was aborted (:stop-reason :error with :error-message, or
    :aborted; pi: message_end for a stopReason-error/aborted partial that
    _prepareRetry then drops from state). Payload: :message."

   :tool-execution-start
   "Fired when a tool execution begins.
    Payload: :tool-call-id, :tool-name, :args."

   :tool-execution-update
   "Fired when a streaming tool emits partial output (e.g. bash live output).
    Payload: :tool-call-id, :content, :is-partial."

   :tool-execution-end
   "Fired when a tool execution completes.
    Payload: :tool-call-id, :tool-name, :args, :result, :is-error."

   :status
   "UI status change.
    Payload: :status (:idle :thinking :executing :error)."

   :error
   "Unhandled error inside the agent loop.
    Payload: :message."

   :loop-guard
   "Repeat-loop circuit breaker tripped: the model kept issuing identical
    tool calls (or repeating its reasoning), so the run was stopped early
    to avoid burning tokens on a dead end (kmet-specific — no pi
    counterpart). Payload: :reason (:tool-calls | :thinking),
    :details (human-readable explanation)."

   ;; ─── App-level events (emitted by the interactive mode, not the loop) ──
   :session-start
   "Fired once after the interactive TUI is built and the extension UI
    registry is live, before the render loop starts (pi: session_start).
    Payload: :reason (:startup | :reload | :new | :resume | :fork),
    :previous-session-file (optional). Extensions use this to set up
    widgets, statuses, footers, and custom editors."

   :session-shutdown
   "Fired before the extension runtime is torn down — quit, reload, or
    session replacement (pi: session_shutdown, emitted by teardownCurrent /
    session.reload BEFORE the extensions are unloaded, so handlers can
    persist state while still alive). Payload: :reason (:reload | :new |
    :resume | :fork | :quit), :target-session-file (optional; the
    destination session on replacement). Extensions restore their state on
    the following :session-start."

   :user-bash
   "Fired when the user runs a bash command (!/!!).
    Payload: :command, :exclude-from-context?, :cwd."

   :session-before-tree
   "Fired before a session-tree navigation branches (pi: session_before_tree).
    Payload: :preparation {:target-id :old-leaf-id :common-ancestor-id
    :entries-to-summarize :user-wants-summary :custom-instructions
    :replace-instructions :label}, :signal (abort atom). Handlers may return
    a map: {:cancel true} aborts the navigation, {:summary str :details …}
    (when :user-wants-summary) supplies the branch summary, and
    :custom-instructions/:label override the inputs."

   :session-tree
   "Fired after a session-tree navigation branched (pi: session_tree).
    Payload: :new-leaf-id, :old-leaf-id, :summary-entry (optional),
    :from-extension? (when the summary came from an extension)."

   ;; ─── Queue events (emitted by loop.clj) ──────────────────────────────
   :queue-update
   "Fired when steering/follow-up queues change.
    Payload: :steering, :follow-up."

   :model-select
   "Model was changed (pi: model_select).
    Payload: :model, :previous-model (resolved Model records; :previous-model
    nil when the previous model is not registered), :source (:set | :cycle)."

   :thinking-level-select
   "Thinking level changed (pi: thinking_level_select, emitted by
    loop/set-thinking-level! on an actual change).
    Payload: :level, :previous-level."

   :session-info-changed
   "Session display name changed (pi: session_info_changed, emitted by the
    /name command).
    Payload: :session-file, :name."

   :context-replaced
   "Fired when the conversation context is replaced: prepareNextTurn's
    :context update, or a compaction (auto or manual) rebuilding the context
    from the compacted session (pi: compaction_end re-renders the chat).
    Payload: :messages — the new conversation messages."

   :auto-retry-start
   "Fired before a retry attempt's backoff sleep when a transient LLM error
    is detected (pi: auto_retry_start).
    Payload: :attempt, :max-attempts, :delay-ms, :error-message."

   :auto-retry-end
   "Fired when retries finish — success, exhausted, or cancelled.
    Payload: :success, :attempt, :final-error (on failure)."

   :summarization-retry-scheduled
   "Fired before a summarization retry's backoff sleep — a compaction or
    branch-summary call failed with a transient error (pi:
    summarization_retry_scheduled; drives the UI's retry countdown).
    Payload: :attempt, :max-attempts, :delay-ms, :error-message."

   :summarization-retry-attempt-start
   "Fired after a summarization retry's backoff, before the retried call
    (pi: summarization_retry_attempt_start). Payload: :source
    (:compaction | :branch-summary) and, for a compaction, :reason."

   :summarization-retry-finished
   "Fired once when a summarization retry loop ends — the retried call
    succeeded, failed terminally, or the backoff was aborted (pi:
    summarization_retry_finished). No payload."

   :compaction-start
   "Fired before session compaction begins (pi: compaction_start).
    Payload: :reason (:manual | :threshold | :overflow | :auto)."

   :compaction-end
   "Fired when compaction finishes (pi: compaction_end).
    Payload: :reason, :result (the compaction result map — pi:
    CompactionResult, kmet keys: :summary, :first-kept-id, :tokens-before,
    :estimated-tokens-after, :usage, :details — or nil when the compaction
    failed or was cancelled), :aborted
    (true when the user cancelled mid-compaction — session untouched),
    :from-extension (true when the summary came from a
    :session-before-compact handler's :compaction result), :error-message
    (set when the summarization call failed — pi:
    `Compaction failed: …` manual, `Auto-compaction failed: …` threshold,
    `Context overflow recovery failed: …` overflow, each carrying the
    summarization failure's cause), :will-retry
    (true for a successful overflow compaction — the interrupted turn
    retries; pi: compaction_end willRetry)."

   :session-compact
   "Fired after a successful compaction, carrying the appended entry (pi:
    session_compact). Payload: :compaction-entry (the :role :compaction
    session entry), :from-extension (true when a :session-before-compact
    handler supplied the summary — pi: fromExtension), :reason,
    :will-retry (true for overflow recovery)."

   :session-compact-failed
   "Fired after context compaction fails or is aborted (pi:
    session_compact_failed). Payload: :reason (:manual | :threshold |
    :overflow), :error-message (when a non-abort failure — same text as
    :compaction-end), :aborted, :from-extension, :will-retry (false — a
    failed compaction never retries the turn; pi:
    _emitSessionCompactFailed)."

   :agent-before-settle
   "Actionable: fired once before the prompt settles, after the queued-message
    continuation check, and before :agent-settled (pi: agent_before_settle,
    emitted by _runBeforeSettleBoundary). Payload: :outcome — the last turn's
    outcome (:completed | :error | :aborted) — plus the boundary state
    :pending-messages (see :turn-end), :can-continue, :entries [] and
    :continue false.
    Handlers return {:entries [...] :continue bool} as for :turn-end; one
    that merely queues a message also runs one more attempt before the
    prompt settles (pi: shouldContinue includes hasQueuedMessages). A
    cancelled prompt never reaches this event."

   :agent-settled
   "Fired when the prompt is fully settled — after the last attempt's
    :agent-end, once per prompt, after any retries, overflow recovery,
    post-run compaction, error, timeout, or cancel. The agent is idle and no
    further events for this prompt will be emitted (pi: agent_settled,
    emitted from a finally block after the whole prompt loop)."

   ;; ─── Provider events (pi: context / context_with_system /
   ;; ─── before_provider_request / before_provider_headers /
   ;; ─── after_provider_response) ──────────────────────────────────────────
   :context
   "Fired before each LLM call with the conversation the request would send,
    without the system prompt (pi: context — emitContext). Payload: :messages.
    Handlers may return {:messages [...]} to replace the conversation; the
    leading system message is re-attached, so a handler cannot drop the prompt
    (pi: restoreSystemMessages). The last non-nil result wins (pi chains
    handler results; kmet's bus keeps the last)."

   :context-with-system
   "Fired after :context with the full transcript — system prompt included — and
    the result is sent verbatim (pi: context_with_system). Payload: :messages.
    Handlers may return {:messages [...]}; a result that drops the leading
    system message is reported as an extension error and honored, as pi does.
    The last non-nil result wins."

   :before-provider-request
   "Fired with the assembled request payload before the provider HTTP call
    (pi: before_provider_request). Payload: :payload. The last non-nil
    handler result replaces the payload."

   :before-provider-headers
   "Fired with the final request headers before the provider HTTP call (pi:
    before_provider_headers — handlers mutate the map in place there; kmet's
    maps are immutable, so handlers RETURN the replacement map and a nil
    header value deletes that header). Payload: :headers. The last non-nil
    map result replaces the headers."

   :after-provider-response
   "Fired after the provider response is received, before its body is
    consumed (pi: after_provider_response). Payload: :status, :headers."

   :resources-discover
   "Fired after :session-start to collect extension-contributed resource
    paths (pi: resources_discover — fired after session_start). Payload:
    :cwd, :reason (:startup | :reload). Handlers return
    {:skill-paths [...] :prompt-paths [...] :theme-paths [...]} (paths are
    directories); ALL handler results are collected, not reduced
    (emit-event-collect!)."})

(def app-event-types
  "Event types emitted outside the agent loop's emit/:on-event path — the
   interactive mode, extensions, and the provider-event bridges — routed
   straight to the event bus (emit-event!), never through the loop's
   :on-event UI callback. The UI handler only needs to consume
   loop-event-types."
  #{:session-start
    :session-shutdown
    :user-bash
    :session-before-tree
    :session-before-switch
    :session-before-fork
    :session-before-compact
    :session-tree
    :session-info-changed
    :resources-discover
    ;; provider events (fired by the ai layer's hook bridges, not the loop)
    :context
    :context-with-system
    :before-provider-request
    :before-provider-headers
    :after-provider-response})

(def loop-event-types
  "The subset of event-types emitted by the agent loop (kmet.app.loop/emit —
   routed to the UI :on-event callback and the extension bus). The UI event
   handler must consume every one of these."
  (apply dissoc event-types app-event-types))

(defn known-event-type?
  "True if the keyword is part of the documented vocabulary."
  [type]
  (contains? event-types type))

;; ─── Extension event bus ──────────────────────────────────────────────────
;; Global listener registry. Extensions register callbacks with on-event;
;; the agent loop and app routes events through emit-event!.

(defonce ^:private event-listeners (atom {}))

(defn on-event
  "Register a callback for an event type.
   event-type — keyword from kmet.app.event-bus/event-types
                (e.g. :agent-start, :turn-start, :message-update,
                 :tool-execution-start, :user-bash, :status)
   callback   — (fn [event-map])
   Returns a deregister function."
  [event-type callback]
  (let [id (random-uuid)]
    (swap! event-listeners update event-type assoc id callback)
    (fn [] (swap! event-listeners update event-type dissoc id))))

(defn clear-event-listeners!
  "Remove all event listeners (for testing)."
  []
  (reset! event-listeners {}))

(defn- invoke-handler
  "Call CB with EVENT, returning nil and warning on a throw — a broken
   extension never breaks the bus (pi: the runner reports the handler error
   and carries on)."
  [cb event]
  (try
    (cb event)
    (catch Exception e
      (binding [*out* *err*]
        (println "Warning: extension event handler error:" (ex-message e)))
      nil)))

(defn emit-event!
  "Emit an event to all registered listeners.
   event — map with :type keyword and any additional data.
   Returns the last non-nil handler result (pi: runner.emit — the tree
   navigation reads :session-before-tree results: :cancel, :summary, ...)."
  [event]
  (let [type (:type event)
        listeners (get @event-listeners type)]
    (when listeners
      (reduce (fn [acc [_ cb]]
                (let [result (invoke-handler cb event)]
                  (if (some? result) result acc)))
              nil
              listeners))))

(defn emit-event-collect!
  "Emit an event and return EVERY non-nil handler result as a vector
   (pi: emitResourcesDiscover collects each handler's contribution — unlike
   emit-event!, results are not reduced to the last non-nil). Throwing
   handlers are skipped and warned, same as emit-event!."
  [event]
  (let [type (:type event)
        listeners (get @event-listeners type)]
    (when listeners
      (into [] (keep (fn [[_ cb]] (invoke-handler cb event))) listeners))))

(defn emit-boundary!
  "Emit an actionable boundary event (a :turn-end or :agent-before-settle
   boundary), threading each handler's result into the next handler's event
   (pi: runner.emitBoundary): a handler receives the event merged with the
   accumulated result so far and returns a map ({:entries [...] :continue
   bool}) whose keys replace the accumulated ones; nil (or a non-map) keeps
   them. Returns the accumulated map — nil when no handler returned one.
   Throwing handlers are skipped and warned, same as emit-event!."
  [event]
  (let [type (:type event)
        listeners (get @event-listeners type)]
    (when listeners
      (reduce (fn [acc [_ cb]]
                (let [result (invoke-handler cb (if acc (merge event acc) event))]
                  (if (map? result) (merge acc result) acc)))
              nil
              listeners))))

(defn get-event-types
  "List all registered event types."
  []
  (keys @event-listeners))
