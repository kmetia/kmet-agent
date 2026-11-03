(ns kmet.app.loop.tool-exec
  "Tool-call preparation and batch execution.

   Every call runs one tool point (`kmet.app.tools.invoke/tool-point`): the
   run's around hooks outside, the permission check at their innermost `next`,
   the body beneath it. A hook that does not descend settles the call, so a
   veto, an argument rewrite, a result rewrite and a successful settlement
   without execution are one shape (extension api: `on-tool`).

   Preparation — the `:tool-execution-start` event — stays sequential and
   source-ordered; the chain and the body run inside each call's future on the
   parallel path, so a hook that asks a human is answered from its own call
   rather than blocking the batch's preparation.

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

(defn- execute-call!
  "THE place a tool body starts, on the parallel and the sequential path alike:
   emit :tool-execution-prepared — the chain above this point descended, so the
   call cleared its hooks and the permission gate and a front end starts its
   clock here — then run the body through the registry's executor with the op's
   effective arguments (whatever the hooks settled on). One entry point, so the
   event and the body cannot drift and a third path cannot forget one of them."
  [agent assistant-msg op declared-tools]
  (state/emit agent {:type :tool-execution-prepared
                     :tool-call-id (:tool-call-id op)
                     :tool-name (:tool-name op)
                     :args (:args op)})
  (invoke/execute-tool-call
   (state/tool-executor agent)
   {:tool-name (:tool-name op)
    :args (:args op)
    :tool-call-id (:tool-call-id op)
    :assistant-message assistant-msg
    :signal (:signal agent)
    :ctx (tool-ctx agent)
    :tools declared-tools
    :on-update (tool-on-update agent (:tool-call-id op))}))

(defn- begin-call!
  "Emit :tool-execution-start for TC — the call is offered, before any hook or
   gate runs (pi), so a blocked call is still shown. Sequential and
   source-ordered, on both schedulers; the rest of the call comes after."
  [agent tc]
  (state/emit agent {:type :tool-execution-start
                     :tool-call-id (:id tc)
                     :tool-name (:name tc)
                     :args (:arguments tc)}))

(defn- call-point!
  "One tool call through the run's tool point: the around hooks, the permission
   check and the body (chain → check → body). Returns {:result … :args …}, ARGS
   being the effective arguments the chain settled on — what the call actually
   ran with, and what the end event reports.

   An undeclared call (pi: prepareToolCall's tools.find) never reaches a body:
   its `next` settles the call as an error, so the hooks still see it — and may
   rewrite or block it — while nothing executes."
  [agent assistant-msg declared-tools tc]
  (let [declared? (contains? declared-tools (:name tc))
        effective (atom (:arguments tc))
        op (invoke/tool-op {:tool-name (:name tc)
                            :args (:arguments tc)
                            :tool-call-id (:id tc)
                            :assistant-message assistant-msg
                            :signal (:signal agent)})
        body (if declared?
               (fn [op] (execute-call! agent assistant-msg op declared-tools))
               (fn [_] {:content (str "Tool " (:name tc) " not found")
                        :is-error true}))]
    {:result (invoke/tool-point
              {:chain @(:tool-hook agent)
               ;; the policy is read from the run's own ctx at call time, so a
               ;; child ctx's policy decides for that agent and a state built
               ;; without the driver still gets the gate
               :check (fn [op] (permissions/check (:ctx agent) op))
               :execute (fn [op]
                          (reset! effective (:args op))
                          (body op))}
              op)
     :args @effective}))

(defn- execute-tool-calls-parallel!
  "Execute tool calls concurrently (pi: executeToolCallsParallel).
   The start events fire sequentially in source order; each call's chain and
   body run in its own future, so a call flips to done — with its own Took
   duration — the moment it finishes (pi: emitToolExecutionEnd in the
   concurrent closure) instead of when the slowest sibling does. Context/session
   results are appended afterwards, in source order. Returns results in source
   order.
   DECLARED-TOOLS — the request's captured tool map (name → tool): the
   declared set and the dispatch of every call resolve against that one
   capture, so a tool unloaded after the request was built still runs for the
   request that was offered it."
  [agent tool-calls assistant-msg declared-tools & [append?]]
  (let [finalized (atom {})
        emit-end! (fn [tc args result]
                    (state/emit agent {:type :tool-execution-end
                                       :tool-call-id (:id tc)
                                       :tool-name (:name tc)
                                       :args args
                                       :result result
                                       :is-error (:is-error result false)}))
        ;; Always record before emitting: the source-order appends below
        ;; read the map after the futures finish, so a result is never lost
        ;; to a failed emit.
        settle! (fn [tc args result]
                  (swap! finalized assoc (:id tc) result)
                  (emit-end! tc args result))
        _ (doseq [tc tool-calls] (begin-call! agent tc))
        ;; pi: tools receive the session AbortSignal — Escape during a tool
        ;; call must kill the child process, not just abandon the future.
        ;; The cancel signal and the session env/cwd bindings come from the
        ;; run-level binding in run-agent-turn (conveyed into these futures),
        ;; so no per-future binding is needed (invoke/execute-tool-call's
        ;; :bindings is the pool-worker path).
        futures (into {} (map (fn [tc]
                                [(:id tc)
                                 (future
                                   (let [{:keys [result args]}
                                         (call-point! agent assistant-msg
                                                      declared-tools tc)]
                                     (settle! tc args result)
                                     result))])
                              tool-calls))
        raw-results (await-all-tool-results! futures)
        ;; A future body that threw before settling (await-all already turns
        ;; it into an error result) still records a result and emits its end
        ;; event here — the fallback is settled as-is, never by re-running
        ;; the failed chain.
        _ (doseq [tc tool-calls :when (not (contains? @finalized (:id tc)))]
            (settle! tc (:arguments tc) (get raw-results (:id tc))))]
    ;; context + session in source order (skipped when the caller appends —
    ;; the repeat-loop guard path appends everything itself in source order)
    (when (not= false append?)
      (doseq [tc tool-calls]
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
   Same chain, events and captured DECLARED-TOOLS map as the parallel path;
   results in source order."
  [agent tool-calls assistant-msg declared-tools & [append?]]
  (let [tool-results (atom [])]
    (doseq [tc tool-calls]
      (begin-call! agent tc)
      (let [{:keys [result args]} (call-point! agent assistant-msg declared-tools tc)
            result-msg (message/tool-result-message (:id tc) (:name tc) result)]
        (when (not= false append?)
          (state/append-persisted-message! agent result-msg))
        (swap! tool-results conj result)
        (state/emit agent {:type :tool-execution-end
                           :tool-call-id (:id tc)
                           :tool-name (:name tc)
                           :args args
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
   Runs the tool point for every call and returns results in source order.
   Calls outside the declared (active) tool set settle as errors without
   executing — pi: prepareToolCall resolves against the request's tools.
   APPEND? — whether the executor appends results to the context/session
   itself (true by default). The repeat-loop guard path passes false and
   appends ALL results (suppressed + survivor) in source order itself, so a
   batch with suppressed calls keeps tool_use/tool_result ordering (strict
   providers reject misordered results).

   The batch runs under run-code-tool/*assistant-message* so a scripted inner
   call carries the same assistant message in its op as the outer run_code
   call (futures convey the binding; the bridge captures it on the tool thread
   and hands it to its pool workers, which do not).

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
