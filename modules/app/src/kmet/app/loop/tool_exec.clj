(ns kmet.app.loop.tool-exec
  "Tool-call preparation, hooks, and batch execution.

   Tool hooks (pi: beforeToolCall / afterToolCall): registered via
   set-before-tool-call! / set-after-tool-call!. The before hook can block
   execution ({:block true :reason ...}); the after hook can rewrite the
   result (:content / :is-error).

   Tool execution mode (pi: toolExecution): tools default to :parallel; if
   any tool call targets a :sequential tool the whole batch runs
   sequentially (see :execution-mode on the Tool record)."
  (:require [kmet.app.permissions :as permissions]
            [kmet.app.loop.state :as state]
            [kmet.app.loop.message :as message]
            [kmet.app.tools.invoke :as invoke]
            [kmet.app.tools.run-code :as run-code-tool]
            [kmet.app.extensions.core :as extensions]))

(defn has-sequential-tool-call?
  "True if any tool call targets a :sequential tool — the whole batch then runs
   sequentially (pi: hasSequentialToolCall). Public for the batch-mode test;
   execute-tool-calls! is the only production caller. Resolved against the
   request's
   captured tool map, like the dispatch, so a tool unloaded between the request
   and the batch keeps its mode; an unregistered call defaults to :parallel
   (pi default)."
  [tool-calls declared-tools]
  (boolean (some #(= :sequential (:execution-mode (get declared-tools (:name %))))
                 tool-calls)))

(defn- await-all-tool-results!
  "Block until every tool future completes; returns tool-call-id → result.
   A failed future turns into an error result so the batch can still settle
   a result per tool call (pi: failToolCallsFromTruncatedMessage) instead
   of leaving the assistant tool calls dangling in the saved context."
  [futures]
  (into {}
        (map (fn [[tc-id f]]
               [tc-id (try @f
                           (catch Exception e
                             {:content (str "Error executing tool: " (ex-message e))
                              :is-error true}))]))
        futures))

(defn- tool-on-update
  "Streaming callback for a tool execution (pi: tool onUpdate) — emits
   :tool-execution-update with partial content so the UI can show live
   output (e.g. bash) while the tool is still running."
  [agent tc-id]
  (fn [partial]
    (when-let [content (:content partial)]
      (state/emit agent {:type :tool-execution-update
                         :tool-call-id tc-id
                         :content content
                         :is-partial true}))))

(defn- tool-ctx
  "The context a tool call executes with: the extension context (pi:
   ToolExecuteContext) plus `:srce-ctx` — the ctx the run executes in, the
   agent's own.

   Rebuilt per call, so a tool sees the run it is part of rather than a
   captured one; the loop hands it over because only the loop knows which
   agent the call belongs to. A tool that reads a service reads it from here
   (a process default is not a fallback), and `run_code` replaces the value
   with its sandbox realm before a script's inner call goes out, so what a
   script can reach through a tool is the boundary and not the composition
   (`kmet.app.sandbox`, docs/architecture.md § Service catalog). Key absent —
   a caller outside a run — means no ctx, not the process's."
  [agent]
  (cond-> (extensions/build-extension-context)
    ;; absent when the state has no ctx, so "no :srce-ctx" reads as "no ctx"
    (:ctx agent) (assoc :srce-ctx (:ctx agent))))

(defn- prepare-tool-call!
  "Run one tool call's preparation: emit :tool-execution-start, then either settle
   it immediately when the tool is not in the request's captured tool map (pi:
   prepareToolCall's tools.find) or run the before-tool-call hook and return TC
   with any rewritten :arguments. Scheduling of the execution itself stays with
   the caller (parallel vs sequential)."
  [agent assistant-msg tc declared-tools]
  (let [tc-id (:id tc)
        tc-name (:name tc)
        tc-args (:arguments tc)]
    (state/emit agent {:type :tool-execution-start
                       :tool-call-id tc-id
                       :tool-name tc-name
                       :args tc-args})
    (if-not (contains? declared-tools tc-name)
      ;; undeclared call settles as an error without executing, but the
      ;; after hook still runs in finish-tool-call!
      (assoc tc :kmet/blocked
             {:content (str "Tool " tc-name " not found")
              :is-error true})
      ;; the hook chain is the extension hooks followed by the permission
      ;; gate: a rewrite is judged by the policy, and a deny settles the call
      ;; with the reason the loop already reports
      (let [prep (invoke/prepare-tool-call
                  {:tool-name tc-name
                   :args tc-args
                   :tool-call-id tc-id
                   :assistant-message assistant-msg
                   :before-hook (permissions/gate @(:before-tool-call agent)
                                                  (:ctx agent)
                                                  (:signal agent))})]
        (cond
          (:block prep)
          (assoc tc :kmet/blocked (:block prep))

          (contains? prep :args)
          (assoc tc :arguments (:args prep))

          :else tc)))))

(defn- finish-tool-call!
  "Apply the after-tool-call hook to RAW for the given TC (pi: after hooks),
   preserving the rewritten ARGS. Returns the final result map."
  [agent assistant-msg tc args raw]
  (invoke/finish-tool-call
   {:tool-name (:name tc)
    :args args
    :tool-call-id (:id tc)
    :assistant-message assistant-msg
    :after-hook @(:after-tool-call agent)}
   raw))

(defn- execute-tool-calls-parallel!
  "Execute tool calls concurrently (pi: executeToolCallsParallel).
   Preparation (start events + before hooks) is sequential; execution is
   concurrent. Each call finalizes (after hook) and emits its
   tool-execution-end inside its own future, so a tool flips to done — with
   its own Took duration — the moment it finishes (pi: emitToolExecutionEnd
   in the concurrent closure) instead of when the slowest sibling does;
   blocked and undeclared calls settle during preparation. Context/session
   results are appended afterwards, in source order. Returns results in
   source order.
   DECLARED-TOOLS — the request's captured tool map (name → tool): the
   declared set and the dispatch of every call resolve against that one
   capture, so a tool unloaded after the request was built still runs for the
   request that was offered it."
  [agent tool-calls assistant-msg declared-tools & [append?]]
  (let [finalized (atom {})
        ;; the names declared for this request — a call outside the map is
        ;; an immediate error, never executed; the map is also the dispatch
        ;; override, so execution agrees with what the model was offered
        emit-end! (fn [tc result]
                    (state/emit agent {:type :tool-execution-end
                                       :tool-call-id (:id tc)
                                       :tool-name (:name tc)
                                       :args (:arguments tc)
                                       :result result
                                       :is-error (:is-error result false)}))
        finish-call (fn [tc raw]
                      (finish-tool-call! agent assistant-msg tc (:arguments tc) raw))
        ;; Always record before emitting: the source-order appends below
        ;; read the map after the futures finish, so a result is never lost
        ;; to a failed emit.
        settle! (fn [tc result]
                  (swap! finalized assoc (:id tc) result)
                  (emit-end! tc result))
        prepared (mapv #(prepare-tool-call! agent assistant-msg % declared-tools)
                       tool-calls)
        ;; Blocked calls settle during preparation (pi: an immediate
        ;; outcome's end fires right away), not after the batch.
        _ (doseq [tc prepared :when (contains? tc :kmet/blocked)]
            (settle! tc (finish-call tc (:kmet/blocked tc))))
        pending (filterv #(not (contains? % :kmet/blocked)) prepared)
        ;; pi: tools receive the session AbortSignal — Escape during a tool
        ;; call must kill the child process, not just abandon the future.
        ;; The cancel signal and the session env/cwd bindings come from the
        ;; run-level binding in run-agent-turn (conveyed into these futures),
        ;; so no per-future binding is needed (invoke/execute-tool-call's
        ;; :bindings is the pool-worker path).
        futures (into {} (map (fn [tc]
                                [(:id tc)
                                 (future
                                   (let [tc-id (:id tc)
                                         result (finish-call
                                                 tc
                                                 (invoke/execute-tool-call
                                                  (state/tool-executor agent)
                                                  {:tool-name (:name tc)
                                                   :args (:arguments tc)
                                                   :tool-call-id tc-id
                                                   :assistant-message assistant-msg
                                                   :signal (:signal agent)
                                                   :ctx (tool-ctx agent)
                                                   :tools declared-tools
                                                   :on-update (tool-on-update agent tc-id)}))]
                                     (settle! tc result)
                                     result))])
                              pending))
        raw-results (await-all-tool-results! futures)
        ;; A future body that threw before settling (await-all already turns
        ;; it into an error result) still records a result and emits its end
        ;; event here — the fallback is settled as-is, never by re-running
        ;; the failed after hook.
        _ (doseq [tc pending :when (not (contains? @finalized (:id tc)))]
            (settle! tc (get raw-results (:id tc))))]
    ;; context + session in source order (skipped when the caller appends —
    ;; the repeat-loop guard path appends everything itself in source order)
    (when (not= false append?)
      (doseq [tc prepared]
        (let [result (get @finalized (:id tc))
              result-msg (message/tool-result-message (:id tc) (:name tc) result)]
          (state/append-persisted-message! agent result-msg))))
    ;; pi: terminate stops the run after this batch — only when EVERY
    ;; finalized call (blocked ones carry the hint, executed ones don't)
    {:results (mapv #(get @finalized (:id %)) tool-calls)
     :terminate (and (seq @finalized)
                     (every? #(true? (:terminate %)) (vals @finalized)))}))

(defn- execute-tool-calls-sequential!
  "Execute tool calls one at a time (pi: executeToolCallsSequential).
   Same hooks and events as the parallel path, and the same captured
   DECLARED-TOOLS map decides the declared set and the dispatch; results in
   source order."
  [agent tool-calls assistant-msg declared-tools & [append?]]
  (let [tool-results (atom [])]
    (doseq [tc tool-calls]
      (let [tc-id (:id tc)
            tc-name (:name tc)
            tc-args (:arguments tc)
            ;; shared preparation: emits the start event and runs the
            ;; before-tool-call hook (+ undeclared handling)
            prepared (prepare-tool-call! agent assistant-msg tc declared-tools)
            args (:arguments prepared)
            raw (if (contains? prepared :kmet/blocked)
                  (:kmet/blocked prepared)
                  (invoke/execute-tool-call
                   (state/tool-executor agent)
                   {:tool-name tc-name
                    :args args
                    :tool-call-id tc-id
                    :assistant-message assistant-msg
                    :signal (:signal agent)
                    :ctx (tool-ctx agent)
                    :tools declared-tools
                    :on-update (tool-on-update agent tc-id)}))
            result (finish-tool-call! agent assistant-msg prepared args raw)
            result-msg (message/tool-result-message tc-id tc-name result)]
        (when (not= false append?)
          (state/append-persisted-message! agent result-msg))
        (swap! tool-results conj result)
        (state/emit agent {:type :tool-execution-end
                           :tool-call-id tc-id
                           :tool-name tc-name
                           :args tc-args
                           :result result
                           :is-error (:is-error result false)})))
    ;; pi: terminate rides on blocked results — the batch stops the run
    ;; only when every call in it was blocked with :terminate
    {:results @tool-results
     :terminate (and (seq @tool-results)
                     (every? #(true? (:terminate %)) @tool-results))}))

(defn execute-tool-calls!
  "Execute tool calls. If any target tool is :sequential the whole batch runs
   sequentially; otherwise tools run in parallel (pi: toolExecution mode).
   Runs before/after-tool-call hooks and returns results in source order.
   Calls outside the declared (active) tool set settle as errors without
   executing — pi: prepareToolCall resolves against the request's tools.
   APPEND? — whether the executor appends results to the context/session
   itself (true by default). The repeat-loop guard path passes false and
   appends ALL results (suppressed + survivor) in source order itself, so a
   batch with suppressed calls keeps tool_use/tool_result ordering (strict
   providers reject misordered results).

   The batch runs under run-code-tool/*assistant-message* so a scripted inner
   call carries the same assistant message in its hook payload as the outer
   run_code call (futures convey the binding; the bridge captures it on the
   tool thread and hands it to its pool workers, which do not).

   REQUEST-TOOLS is the tool list captured when the request was built, in
   registry order; the declared set and the dispatch of every call in the
   batch resolve against it, so a tool unloaded between the model's tool_use
   and its execution still runs."
  [agent tool-calls assistant-msg request-tools & [append?]]
  (let [declared-tools (into {} (map (juxt :name identity)) request-tools)]
    (binding [run-code-tool/*assistant-message* assistant-msg]
      (if (has-sequential-tool-call? tool-calls declared-tools)
        (execute-tool-calls-sequential! agent tool-calls assistant-msg declared-tools append?)
        (execute-tool-calls-parallel! agent tool-calls assistant-msg declared-tools append?)))))
