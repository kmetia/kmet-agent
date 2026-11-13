(ns kmet.app.loop.test-tools
  "Tool execution: the around tool hook, parallel tool calls, bash result
   recording, active tools, and thinking-level events."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.ai.llm :as llm]
            [kmet.libs.json :as json]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.registry :as tools-registry]
            [kmet.app.session.core :as session]
            [kmet.app.loop.tool-exec :as tool-exec]
            [kmet.app.loop.state :as state]
            [kmet.app.loop :as loop]
            [kmet.app.config :as cfg]
            [kmet.ui.tui.chat-history :as ui]
            [kmet.libs.theme :as th]
            [kmet.app.loop.test-support :as support]
            [kmet.test-tmp :as test-tmp]))

(t/use-fixtures
  :each
  ;; A test that needs a tool table should not boot the app root: the loop
  ;; reads the table from its ctx's `:tools` service, so `with-registry` gives
  ;; this namespace its own (see `test-support/with-registry`).
  (fn [f] (support/with-registry f)))

;; ─── Tool hooks (before/after tool-call) ─────────────────────────────────

(t/deftest test-loop-tool-hooks-compose-first-registered-outermost
  ;; kmet.app.hooks/around: the first registered hook wraps every later one, so
  ;; it sees the op first and the result last — the order the docs promise
  (let [trace (atom [])
        hook (fn [tag]
               (fn [op next]
                 (swap! trace conj [tag :in])
                 (let [r (next op)]
                   (swap! trace conj [tag :out])
                   r)))
        agent (support/make-agent-state)]
    (reset! (:tool-hook agent) (support/tool-hook (hook :outer) (hook :inner)))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _]
                                           (swap! trace conj [:body])
                                           {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= [[:outer :in] [:inner :in] [:body] [:inner :out] [:outer :out]]
             @trace)
          "outermost first, the body innermost, results unwound in reverse")))

(t/deftest test-loop-tool-hook-settles-without-executing
  ;; an around hook that does not descend settles the call: the body never
  ;; runs, the batch still records a result, and nothing is prepared
  (let [events (atom [])
        executed (atom false)
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [_op _next] {:block true :reason "Permission denied"})))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] (reset! executed true)
                                           {:content "should not run" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (false? @executed) "settled tool must not execute")
    (t/is (not-any? #(= :tool-execution-prepared (:type %)) @events)
          "a settled call never reaches execution, so it is never prepared")
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "tc1" (:tool-call-id end)))
      (t/is (true? (:is-error end)) "a settled call's result is an error")
      (t/is (= "Permission denied" (:content (:result end)))))))

(t/deftest test-loop-tool-hook-settles-success-without-executing
  ;; the capability the before/after pair cannot express: a hook may settle a
  ;; call SUCCESSFULLY without the body running (a cache hit, a stub, a dry run)
  (let [events (atom [])
        executed (atom false)
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [_op _next] {:content "cache hit" :is-error false})))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] (reset! executed true)
                                           {:content "ran" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (false? @executed) "the body did not run")
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "cache hit" (:content (:result end))))
      (t/is (false? (:is-error end)) "a settlement need not be an error"))))

(t/deftest test-loop-tool-hook-throws-before-next
  ;; fail-closed: a hook that crashes before descending settles the call
  ;; unexecuted instead of letting it through — and a failure is not a veto, so
  ;; the run continues and nothing is marked :terminate
  (let [events (atom [])
        executed (atom false)
        llm-calls (atom 0)
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [_op _next] (throw (ex-info "hook boom" {})))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! llm-calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)] (on-text "ok"))
                            (when-let [on-done (:on-done opts)] (on-done :stop))))
                      :done))
                  state/execute-tool-for (fn [_ _ _ _] (reset! executed true)
                                           {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (false? @executed) "a crashing hook must not let the call through")
    (t/is (= 2 @llm-calls) "the run continues past a hook failure")
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (true? (:is-error end)))
      (t/is (.contains (:content (:result end)) "tool hook error"))
      (t/is (.contains (:content (:result end)) "hook boom"))
      (t/is (nil? (:terminate (:result end))))
      (t/is (not (:terminate end)) "a failure never terminates the run"))))

(t/deftest test-loop-tool-hook-throws-after-next
  ;; fail-closed, the other position: the body ran, the hook then threw, so the
  ;; side effects stand and the model is told the call failed — one result, the
  ;; hook not run again on the loop thread
  (let [hook-calls (atom 0)
        executed (atom false)
        events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [op next]
                                 (let [result (next op)]
                                   (swap! hook-calls inc)
                                   (throw (AssertionError. (str "hook boom after "
                                                                (:content result))))))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] (reset! executed true)
                                           {:content "ran" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (true? @executed) "the body ran before the hook threw")
    (t/is (= 1 @hook-calls) "the failing hook runs exactly once")
    (let [ends (filter #(= :tool-execution-end (:type %)) @events)]
      (t/is (= 1 (count ends)) "one result per call")
      (t/is (true? (:is-error (first ends))))
      (t/is (.contains (:content (:result (first ends))) "tool hook error"))
      (t/is (.contains (:content (:result (first ends))) "hook boom after ran")
            "the hook's own message survives, so a failure after the body is diagnosable"))
    (t/is (= :idle @(:status agent)) "the turn completes")))

(t/deftest test-loop-tool-error-is-not-a-hook-error
  ;; an exception from the body is the operation's own: it must surface as the
  ;; ordinary execution error, never as a hook failure
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [op next] (next op))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _]
                                           (throw (ex-info "tool boom" {})))]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))
          content (:content (:result end))]
      (t/is (true? (:is-error end)))
      (t/is (.contains content "Error executing bash"))
      (t/is (not (.contains content "tool hook error"))))))

(t/deftest test-loop-prepared-event-follows-the-hook
  ;; :tool-execution-start fires before the hook (pi), so a gated call's block
  ;; exists while an approval `:ask` is still up; :tool-execution-prepared is
  ;; emitted once the chain descended past the permission check, just before
  ;; the body runs — the moment a front end starts timing the tool.
  (let [order (atom [])
        agent (support/make-agent-state
               :on-event (fn [e]
                           (when (#{:tool-execution-start :tool-execution-prepared
                                    :tool-execution-end} (:type e))
                             (swap! order conj (:type e)))))]
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [op next] (swap! order conj :hook) (next op))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] (swap! order conj :exec)
                                           {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= [:tool-execution-start :hook :tool-execution-prepared :exec
              :tool-execution-end]
             @order)
          "start, the hook, prepared, the body, end — in that order")))

(t/deftest test-loop-tool-hook-terminate
  (let [events (atom [])
        executed (atom false)
        llm-calls (atom 0)
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [_op _next] {:block true :reason "Policy" :terminate true})))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (fn [opts]
                                     (future
                                       (swap! llm-calls inc)
                                       (when-let [on-tc (:on-tool-call opts)]
                                         (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                                       (when-let [on-done (:on-done opts)]
                                         (on-done :tool-calls))
                                       :done))
                  state/execute-tool-for (fn [_ _ _ _] (reset! executed true)
                                           {:content "should not run" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (false? @executed) "settled tool must not execute")
    (t/is (= 1 @llm-calls) "terminate stops the run — no follow-up LLM call")
    (t/is (= :idle @(:status agent)))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "tc1" (:tool-call-id end)))
      (t/is (= "Policy" (:content (:result end))) "the settled result is in the transcript")
      (t/is (true? (:is-error end))))))

(t/deftest test-loop-tool-hook-terminate-mixed-batch
  (let [events (atom [])
        executed (atom 0)
        llm-calls (atom 0)
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    ;; pi: terminate only when EVERY finalized call in the batch carries
    ;; the hint — a single non-terminated call keeps the run going
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [op next]
                                 (if (= "tc1" (:tool-call-id op))
                                   {:block true :reason "Policy" :terminate true}
                                   (next op)))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (fn [opts]
                                     (future
                                       (if (= 1 (swap! llm-calls inc))
                                         (do (when-let [on-tc (:on-tool-call opts)]
                                               (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0})
                                               (on-tc {:id "tc2" :name "bash" :arguments "{}" :index 1}))
                                             (when-let [on-done (:on-done opts)]
                                               (on-done :tool-calls)))
                                         (do (when-let [on-text (:on-text opts)]
                                               (on-text "ok"))
                                             (when-let [on-done (:on-done opts)]
                                               (on-done :stop))))
                                       :done))
                  state/execute-tool-for (fn [_ _ _ _] (swap! executed inc)
                                           {:content "ran" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 1 @executed) "the call the hook descended into executed")
    (t/is (= 2 @llm-calls) "mixed batch does not terminate — follow-up LLM call happened")
    (t/is (= :idle @(:status agent)))
    (t/is (some #(and (= :tool-execution-end (:type %))
                      (= "tc1" (:tool-call-id %))
                      (true? (:is-error %)))
                @events)
          "the settled call still reports its error result")))

(t/deftest test-loop-undeclared-tool-call-settles-as-error
  ;; pi: prepareToolCall resolves the call against the tools declared for the
  ;; request — a call to a tool the model was not given (disabled built-in,
  ;; hallucinated name) settles as an error and never executes
  (let [events (atom [])
        executed (atom [])
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               ;; bash is left out of the selection
               :enabled-tool-names (support/tool-names ["read" "write" "edit" "codemode"]))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ name _ _] (swap! executed conj name)
                                           {:content "should not run" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (empty? @executed) "an undeclared call must not execute")
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "bash" (:tool-name end)))
      (t/is (true? (:is-error end)))
      (t/is (= "Tool bash not found" (:content (:result end)))))))

(t/deftest test-loop-undeclared-tool-call-settles-as-error-in-sequential-batch
  ;; the guard lives in both executors — the scheduling redef puts this batch
  ;; on the sequential path, and the event order proves it: the sequential
  ;; executor prepares and runs one call at a time, while the parallel path
  ;; prepares the whole batch first
  (let [events (atom [])
        executed (atom [])
        calls (atom 0)
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :enabled-tool-names (support/tool-names ["read"]))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  ;; force the sequential executor for this batch
                  tool-exec/has-sequential-tool-call? (constantly true)
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (doseq [tc [{:id "tc1" :name "read" :arguments "{}" :index 0}
                                        {:id "tc2" :name "bash" :arguments "{}" :index 1}]]
                              (when-let [on-tc (:on-tool-call opts)] (on-tc tc)))
                            (when-let [on-done (:on-done opts)] (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)] (on-text "ok"))
                            (when-let [on-done (:on-done opts)] (on-done :stop))))
                      :done))
                  state/execute-tool-for (fn [_ name _ _] (swap! executed conj name)
                                           {:content "should not run" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= ["read"] @executed)
          "the declared call executed; the undeclared one never did")
    (t/is (= [[:tool-execution-start "read"] [:tool-execution-end "read"]
              [:tool-execution-start "bash"] [:tool-execution-end "bash"]]
             (into []
                   (comp (filter #(#{:tool-execution-start :tool-execution-end} (:type %)))
                         (map (juxt :type :tool-name)))
                   @events))
          "one call prepared and run at a time — the sequential executor")
    (let [end (first (filter #(and (= :tool-execution-end (:type %)) (= "bash" (:tool-name %)))
                             @events))]
      (t/is (true? (:is-error end)))
      (t/is (= "Tool bash not found" (:content (:result end)))))))

(t/deftest test-loop-declared-tool-call-still-executes
  ;; the guard is the declared set, not a fixed name list: a call to a tool
  ;; inside the selection executes as before
  (let [events (atom [])
        executed (atom [])
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :enabled-tool-names (support/tool-names ["bash"]))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ name _ _] (swap! executed conj name)
                                           {:content "ran" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= ["bash"] @executed))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (false? (:is-error end)))
      (t/is (= "ran" (:content (:result end)))))))

(t/deftest test-loop-tool-hook-replaces-the-result
  ;; the post side of an around hook: transform what the body returned
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [op next]
                                 (let [result (next op)]
                                   (assoc result :content (str (:content result) " [sanitized]"))))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] {:content "secret-key=abc" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "secret-key=abc [sanitized]" (:content (:result end))))
      (t/is (false? (:is-error end)) "the result keeps is-error unless the hook changes it"))))

(t/deftest test-loop-tool-hook-sets-error
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [op next] (assoc (next op) :is-error true))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (true? (:is-error end)) "a hook can mark a result as error"))))

(t/deftest test-loop-tool-hook-rewrites-args
  (let [seen (atom [])
        agent (support/make-agent-state)]
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [op next] (next (assoc op :args {:command "rewritten"})))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ args _]
                                           (swap! seen conj args)
                                           {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= [{:command "rewritten"}] @seen)
          "the parallel path executes with the rewritten args")))

(t/deftest test-loop-tool-hook-rewrites-args-sequential
  ;; the sequential path honors the rewrite too (it ignored one before the
  ;; pipeline refactor)
  (let [seen (atom nil)
        calls (atom 0)]
    (tools-registry/register-tool! (support/tool-table)
                                   (tool/make-tool :name "rewrite-seq-tool" :label "Seq"
                                                   :description "sequential rewrite test tool"
                                                   :parameters {:x (tool/param :x :string "x")}
                                                   :execute (fn [_] {:content "seq" :is-error false})
                                                   :execution-mode :sequential))
    (try
      (let [agent (support/make-agent-state)]
        (reset! (:tool-hook agent)
                (support/tool-hook (fn [op next] (next (assoc op :args {:x "rewritten"})))))
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (future
                          (if (= 1 (swap! calls inc))
                            (do (when-let [on-tc (:on-tool-call opts)]
                                  (on-tc {:id "t1" :name "rewrite-seq-tool"
                                          :arguments "{}" :index 0}))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :tool-calls)))
                            (do (when-let [on-text (:on-text opts)]
                                  (on-text "ok"))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :stop))))
                          :done))
                      state/execute-tool-for (fn [_ _ args _]
                                               (reset! seen args)
                                               {:content "seq" :is-error false})]
          @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])})))
      (t/is (= {:x "rewritten"} @seen))
      (finally
        (tools-registry/unregister-tool! (support/tool-table)  "rewrite-seq-tool")))))

(t/deftest test-a-tool-dropped-after-the-request-still-runs
  ;; the request's tool map is captured once and threaded through the turn
  ;; (docs/architecture.md, "Reload and the turn boundary"): the wire list, the declared
  ;; set and the dispatch all resolve against that capture, so a tool unloaded
  ;; between the model's tool_use and its execution still runs — the race the
  ;; one-read discipline claims closed
  (let [events (atom [])
        offered (atom nil)
        calls (atom 0)]
    (tools-registry/register-tool! (support/tool-table)
                                   (tool/make-tool :name "capture-probe" :label "Probe"
                                                   :description "captured-dispatch probe"
                                                   :parameters {:x (tool/param :x :string "x")}
                                                   :execute (fn [_] {:content "captured-ran" :is-error false}))
                                   [:probe :test])
    (try
      (let [agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (when (nil? @offered)
                          (reset! offered (:tools opts)))
                        ;; gone by the time the model's call comes back
                        (tools-registry/unregister-tool! (support/tool-table) "capture-probe" [:probe :test])
                        (future
                          (if (= 1 (swap! calls inc))
                            (do (when-let [on-tc (:on-tool-call opts)]
                                  (on-tc {:id "tc1" :name "capture-probe"
                                          :arguments "{}" :index 0}))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :tool-calls)))
                            (do (when-let [on-text (:on-text opts)]
                                  (on-text "done"))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :stop))))
                          :done))]
          @(loop/run-agent-turn agent {:message "run tool"
                                       :on-done (fn [_])
                                       :on-error (fn [_])})))
      (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
        (t/is (some #(= "capture-probe" (:name %)) @offered)
              "the request offered the tool that was dropped")
        (t/is (= "captured-ran" (:content (:result end)))
              "dispatch resolves against the request's capture, not the live registry")
        (t/is (false? (:is-error end))))
      (finally
        (tools-registry/unregister-tool! (support/tool-table) "capture-probe" [:probe :test])))))

(t/deftest test-loop-scripted-calls-fire-tool-hooks
  ;; the run's tool chain reaches calls made inside a script: the loop
  ;; binds script/*tool-hook* and script/*assistant-message* around the batch,
  ;; the bridge captures them on the tool thread and hands them to its pool
  ;; workers (raw threads convey no bindings)
  (let [hook-calls (atom [])
        calls (atom 0)
        agent (support/make-agent-state
               :enabled-tool-names (support/tool-names
                                    (conj (vec tools-registry/default-tool-names)
                                          "codemode")))]
    (reset! (:tool-hook agent)
            (support/tool-hook (fn [op next] (swap! hook-calls conj op) (next op))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "codemode"
                                      :arguments (json/generate-string
                                                  {:code "(deref (tools/call \"read\" {:path \"deps.edn\"}))"})
                                      :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "ok"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (some #(= "read" (:tool-name %)) @hook-calls)
          "the scripted read call ran through the run's tool hook")
    (t/is (some #(str/starts-with? (str (:tool-call-id %)) "codemode-") @hook-calls)
          "the inner call carries a synthetic script-N tool-call-id")
    (t/is (some #(= "tc1" (:tool-call-id %)) @hook-calls)
          "the outer codemode call went through the same hook")
    (t/is (= (:assistant-message (first (filter #(= "codemode" (:tool-name %)) @hook-calls)))
             (:assistant-message (first (filter #(= "read" (:tool-name %)) @hook-calls))))
          "the inner call carries the same assistant message as the outer call")))

;; ─── Phase 3: parallel tool execution ────────────────────────────────────

(t/deftest test-loop-parallel-tool-execution
  (let [events (atom [])
        started-count (atom 0)
        active-tools (atom 0)
        both-started (promise)
        release-tools (promise)
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-two-tool-calls-then-text (atom 0))
                  state/execute-tool-for
                  (fn [_ _ _ _]
                    (swap! started-count inc)
                    (swap! active-tools inc)
                    (when (= 2 @started-count)
                      (deliver both-started true))
                    (deref release-tools 2000 :timeout)
                    (swap! active-tools dec)
                    {:content "ok" :is-error false})]
      (let [run (future
                  (deref (loop/run-agent-turn agent
                                              {:message "run" :on-error (fn [_])})
                         2000
                         ::timeout))]
        (try
          (t/is (true? (deref both-started 2000 false))
                "both tool executions are in flight together")
          (t/is (= 2 @active-tools) "both tool bodies are active concurrently")
          (finally
            (deliver release-tools true)))
        (t/is (not= ::timeout @run) "the turn completes")
        (t/is (= 2 @started-count) "both tools execute")
        (t/is (= 0 @active-tools) "both tool bodies exited")
        (t/is (= 2 (count (filter #(= :tool-execution-start (:type %)) @events))))
        (t/is (= 2 (count (filter #(= :tool-execution-end (:type %)) @events))))
        (let [te (first (filter #(= :turn-end (:type %)) @events))]
          (t/is (= 2 (count (:tool-results te))) "turn-end carries both results"))
        (t/is (= 2 (count (filter #(= :tool (:role %)) (state/get-context agent))))
              "both tool results appended to context")))))

(t/deftest test-loop-parallel-end-events-fire-per-tool
  ;; Regression: end events for a parallel batch used to wait for every
  ;; future, so each tool showed the slowest sibling's Took duration and
  ;; stayed running until the whole batch ended.
  (let [events (atom [])
        slow-running (promise)
        release-slow (promise)
        fast-ended (promise)
        agent (support/make-agent-state
               :on-event (fn [e]
                           (swap! events conj e)
                           (when (and (= :tool-execution-end (:type e))
                                      (= "tc1" (:tool-call-id e)))
                             (deliver fast-ended true))))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (support/stub-llm-two-tool-calls-then-text
                   (atom 0)
                   "{\"command\":\"fast\"}"
                   "{\"command\":\"slow\"}")
                  state/execute-tool-for
                  (fn [_ _ args _]
                    (if (= "fast" (:command args))
                      {:content "fast done" :is-error false}
                      (do (deliver slow-running true)
                          (deref release-slow 2000 :timeout)
                          {:content "slow done" :is-error false})))]
      (let [run (future
                  (deref (loop/run-agent-turn agent
                                              {:message "run" :on-error (fn [_])})
                         3000
                         ::timeout))]
        (try
          (t/is (true? (deref slow-running 2000 false)) "the slow tool is in flight")
          (t/is (true? (deref fast-ended 2000 false))
                "the fast tool's end event fires while its slow sibling still runs")
          (finally
            (deliver release-slow true)))
        (t/is (not= ::timeout (deref run 3000 ::timeout)) "the turn completes")
        (t/is (= 2 (count (filter #(= :tool-execution-end (:type %)) @events))))
        (t/is (= ["fast done" "slow done"]
                 (mapv #(get-in % [:content 0 :content])
                       (filter #(= :tool (:role %)) (state/get-context agent))))
              "results are still appended to context in source order")))))

(t/deftest test-loop-runs-a-multi-eval-batch
  ;; Two eval calls in one assistant message both settle and append results;
  ;; the prompt, not a result nudge, teaches when to batch.
  (let [call-count (atom 0)
        agent (support/make-agent-state
               :enabled-tool-names (support/tool-names ["eval"]))
        send-message
        (fn [opts]
          (future
            (if (= 1 (swap! call-count inc))
              (do (when-let [on-tc (:on-tool-call opts)]
                    (on-tc {:id "tc1" :name "eval"
                            :arguments "{\"code\":\"(+ 1 2)\"}" :index 0})
                    (on-tc {:id "tc2" :name "eval"
                            :arguments "{\"code\":\"(+ 3 4)\"}" :index 1}))
                  (when-let [on-done (:on-done opts)] (on-done :tool-calls)))
              (do (when-let [on-text (:on-text opts)] (on-text "ok"))
                  (when-let [on-done (:on-done opts)] (on-done :stop))))
            :done))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message send-message
                  state/execute-tool-for
                  (fn [_ _ _ _] {:content "ok" :is-error false})]
      (let [run (future
                  (deref (loop/run-agent-turn agent {:message "run"
                                                     :on-error (fn [_])})
                         2000 ::timeout))]
        (t/is (not= ::timeout @run) "the turn completes")
        (let [tools (filter #(= :tool (:role %)) (state/get-context agent))
              contents (mapcat #(map :content (:content %)) tools)]
          (t/is (= 2 (count tools)) "both eval results are appended")
          (t/is (not-any? #(str/includes? (str %) "serialize") contents)
                "no serialize nudge is added"))))))

(t/deftest test-loop-serial-group-queues-in-source-order
  ;; A tool with :serial-group runs its calls in source order inside a
  ;; parallel batch, while unrelated calls stay concurrent. A queued call is
  ;; not prepared until its predecessor ended — its execution clock starts
  ;; then, not while it waits (eval groups its calls on the session lock).
  (let [events (atom [])
        started (atom [])
        first-running (promise)
        free-done (promise)
        release-first (promise)
        agent (support/make-agent-state
               :on-event (fn [e]
                           (swap! events conj e)
                           (when (and (= :tool-execution-end (:type e))
                                      (= "c" (:tool-call-id e)))
                             (deliver free-done true))))
        serial-tool (tool/make-tool
                     :name "serial"
                     :parameters {:type "object" :properties {}}
                     :serial-group :g
                     :execute (fn [args]
                                (let [n (:n args)]
                                  (swap! started conj n)
                                  (when (= 1 n)
                                    (deliver first-running true)
                                    (deref release-first 2000 :timeout))
                                  {:content (str n) :is-error false})))
        free-tool (tool/make-tool
                   :name "free"
                   :parameters {:type "object" :properties {}}
                   :execute (fn [_] {:content "free" :is-error false}))
        calls [{:id "a" :name "serial" :arguments {:n 1}}
               {:id "b" :name "serial" :arguments {:n 2}}
               {:id "c" :name "free" :arguments {}}]
        run (future (tool-exec/execute-tool-calls!
                     agent calls nil [serial-tool free-tool] false))]
    (try
      (t/is (true? (deref first-running 2000 false)))
      (t/is (= [1] @started)
            "the second group member waits for the first")
      (t/is (true? (deref free-done 2000 false))
            "an unrelated call still runs while the group queues")
      (t/is (not-any? #(and (= :tool-execution-prepared (:type %))
                            (= "b" (:tool-call-id %)))
                      @events)
            "the queued call is not prepared while it waits")
      (finally
        (deliver release-first true)))
    (t/is (not= ::timeout (deref run 3000 ::timeout)))
    (t/is (= [1 2] @started) "group members run in source order")
    (let [trace (mapv (juxt :type :tool-call-id) @events)
          end-a (.indexOf trace [:tool-execution-end "a"])
          prepared-b (.indexOf trace [:tool-execution-prepared "b"])]
      (t/is (<= 0 end-a))
      (t/is (< end-a prepared-b)
            "the queued call's clock starts only after its predecessor ended"))))

(t/deftest test-loop-sequential-tool-execution
  (let [llm-call-count (atom 0)
        tool-count (atom 0)
        completed (atom [])
        first-started (promise)
        second-started (promise)
        release-first (promise)
        release-second (promise)
        agent (support/make-agent-state)]
    (tools-registry/register-tool! (support/tool-table)
                                   (tool/make-tool :name "seq-tool" :label "Seq"
                                                   :description "sequential test tool"
                                                   :parameters {:x (tool/param :x :string "x")}
                                                   :execute (fn [_] {:content "seq" :is-error false})
                                                   :execution-mode :sequential))
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (if (= 1 (swap! llm-call-count inc))
                          (do (when-let [on-tc (:on-tool-call opts)]
                                (on-tc {:id "t1" :name "seq-tool" :arguments "{}" :index 0})
                                (on-tc {:id "t2" :name "seq-tool" :arguments "{}" :index 1}))
                              (when-let [on-done (:on-done opts)]
                                (on-done :tool-calls)))
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "ok"))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop))))
                        :done))
                    state/execute-tool-for
                    (fn [_ _ _ _]
                      (let [n (swap! tool-count inc)]
                        (deliver (if (= 1 n) first-started second-started) true)
                        (deref (if (= 1 n) release-first release-second)
                               2000
                               :timeout)
                        (swap! completed conj n)
                        {:content "seq" :is-error false}))]
        (let [run (future (deref (loop/run-agent-turn agent {:message "run" :on-error (fn [_])}) 2000 ::timeout))]
          (try
            (t/is (true? (deref first-started 2000 false)) "first tool starts")
            (t/is (false? (realized? second-started)) "second tool has not started")
            (t/is (empty? @completed) "the first tool is still in flight")
            (deliver release-first true)
            (t/is (true? (deref second-started 2000 false)) "second tool starts after release")
            (t/is (= [1] @completed) "only the first tool completed")
            (deliver release-second true)
            (t/is (not= ::timeout (deref run 2000 ::timeout)) "the turn completes")
            (t/is (= [1 2] @completed) "tools completed in order")
            (finally
              (deliver release-first true)
              (deliver release-second true)))))
      (finally
        (tools-registry/unregister-tool! (support/tool-table)  "seq-tool")))))

;; ─── Bash result recording (pi: recordBashResult + _flushPendingBashMessages)

(t/deftest test-loop-add-bash-result-when-idle
  (let [dir (test-tmp/temp-dir! "sess-")
        sess (session/create-session (str (fs/path dir "s")))
        agent (support/make-agent-state :session sess)]
    (try
      (state/add-bash-result! agent "git st" {:output "clean\n" :exit-code 0} false)
      (t/is (= 1 (count (state/get-context agent))))
      (t/is (= :bash (:role (first (state/get-context agent)))))
      (t/is (= 1 (count @(:entries sess))))
      (t/is (empty? @(:pending-bash agent)))
      ;; No run in flight: the append is context-only (pi: newMessages starts
      ;; empty per run), so the run accumulator stays untouched.
      (t/is (empty? @(:run-messages agent)))
      (t/is (nil? @(:run-token agent)))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-add-bash-result-queued-while-streaming
  (let [dir (test-tmp/temp-dir! "sess-")
        sess (session/create-session (str (fs/path dir "s")))
        agent (support/make-agent-state :session sess)]
    (try
      (reset! (:status agent) :thinking)
      (state/add-bash-result! agent "git st" {:output "clean\n" :exit-code 0} false)
      (t/is (empty? (state/get-context agent)) "streaming: not added to context yet")
      (t/is (empty? @(:entries sess)) "streaming: not appended to session yet")
      (t/is (= 1 (count @(:pending-bash agent))) "streaming: queued for later flush")
      (state/flush-pending-bash-messages! agent)
      (t/is (= 1 (count (state/get-context agent))))
      (t/is (= 1 (count @(:entries sess))))
      (t/is (empty? @(:pending-bash agent)))
      ;; The flush appends context-only — a settled run's payload must not
      ;; grow after agent-end (pi: _flushPendingBashMessages runs after
      ;; agent_end and never touches newMessages).
      (t/is (empty? @(:run-messages agent)))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-flush-pending-bash-appends-each-entry-once
  ;; A result recorded while the flush drains must not make the batch already
  ;; appended land a second time — the CAS recur re-read the atom holding that
  ;; batch plus the new entry, so the whole batch was appended again.
  (let [dir (test-tmp/temp-dir! "sess-")
        sess (session/create-session (str (fs/path dir "s")))
        agent (support/make-agent-state :session sess)
        append! state/append-context-message!
        injected? (atom false)]
    (try
      (reset! (:status agent) :thinking)
      (state/add-bash-result! agent "first" {:output "one\n" :exit-code 0} false)
      (with-redefs [state/append-context-message!
                    (fn [ag entry]
                      (append! ag entry)
                      ;; a second result arrives mid-flush: still streaming, so
                      ;; it queues behind the batch being drained
                      (when (compare-and-set! injected? false true)
                        (state/add-bash-result! ag "second" {:output "two\n" :exit-code 0} false)))]
        (state/flush-pending-bash-messages! agent))
      (t/is (= ["first" "second"]
               (mapv :command (state/get-context agent)))
            "each queued result appears exactly once, in order")
      (t/is (= 2 (count @(:entries sess))) "and is persisted once")
      (t/is (empty? @(:pending-bash agent)))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-add-bash-result-excluded
  (let [agent (support/make-agent-state)]
    (state/add-bash-result! agent "ls" {:output "x" :exit-code 0} true)
    (t/is (= true (:exclude-from-context? (first (state/get-context agent)))))))

(t/deftest test-loop-add-bash-result-after-error
  ;; pi: isStreaming is false once a run has errored — the result lands
  ;; immediately instead of queueing for a flush that never comes
  (let [agent (support/make-agent-state)]
    (reset! (:status agent) :error)
    (state/add-bash-result! agent "git st" {:output "clean\n" :exit-code 0} false)
    (t/is (= 1 (count (state/get-context agent))))
    (t/is (empty? @(:pending-bash agent)))))

(t/deftest test-loop-interactive-chat-flow-with-steer-and-followup
  ;; End-to-end: the interactive UI mirrors the loop via :message-start
  ;; (:user → addMessageToChat, :assistant → new streaming component) and
  ;; streams text via :on-text. A steered message lands in the chat when the
  ;; loop consumes it (before the response to it); a follow-up lands after
  ;; the run settles. Tool-only turns finalize an empty assistant placeholder
  ;; (:tool-execution-start), matching pi.
  (let [ch (ui/make-chat-history
            :theme th/dark-theme)
        calls (atom 0)
        events (atom [])
        agent (support/make-agent-state
               :on-event (fn [evt]
                           (swap! events conj evt)
                           (case (:type evt)
                             :message-start
                             (case (:role (:message evt))
                               :user (ui/chat-history-add-message!
                                      ch (:message evt))
                               :assistant (do (ui/chat-history-finalize-streaming! ch)
                                              (ui/chat-history-finalize-thinking! ch)
                                              (ui/chat-history-start-streaming! ch))
                               nil)
                             :tool-execution-start
                             (let [msg {:role :tool :name (:tool-name evt) :args {} :content "" :is-error false}]
                               (ui/chat-history-finalize-streaming! ch)
                               (ui/chat-history-add-message! ch msg))
                             nil)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "resp"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  state/execute-tool-for
                  (fn [_ _ _ _]
                    (state/steer! agent "steered")
                    (state/follow-up! agent "followup")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "start"
                             :on-text #(ui/chat-history-append-streaming-text! ch %)
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (ui/chat-history-finalize-streaming! ch)
    (let [chat (mapv (fn [m] {:role (:role m) :content (:content m) :streaming? (:streaming? m)})
                     @(:messages-atom ch))
          text (fn [m] (if (string? (:content m)) (:content m) (get-in m [:content 0 :text])))
          roles (mapv :role chat)
          texts (mapv text chat)]
      (t/is (= [:user :assistant :tool :user :assistant :user :assistant] roles)
            "prompt, tool-turn, tool, steered, response, follow-up, response")
      (t/is (= ["start" "" "" "steered" "resp" "followup" "resp"] texts)
            "steered/follow-up land at the right position; responses stream into place")
      (t/is (every? #(not (:streaming? %)) chat)
            "all streaming placeholders finalized by run end")
      (t/is (= ["start" "steered" "followup"]
               (mapv #(get-in % [:message :content 0 :text])
                     (filter #(and (= :message-start (:type %))
                                   (= :user (:role (:message %))))
                             @events)))
            "one :message-start :user per consumed message, in order"))))

;; ─── Active tools + thinking-level event (pi: setActiveTools / ───────────
;; ─── thinking_level_select) ──────────────────────────────────────────────

(t/deftest test-active-tools
  (t/testing "nil = all tools; set restricts; nil restores"
    (let [ag (support/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")]
      (t/is (nil? @(:active-tools ag)))
      (state/set-active-tools! ag ["read" "write"])
      (t/is (= #{"read" "write"} @(:active-tools ag)))
      (state/set-active-tools! ag nil)
      (t/is (nil? @(:active-tools ag))))))

(t/deftest test-active-tools-resources-selection
  (t/testing "a resources selection filters the live tools; a runtime
              selection wins; nil restores the current selection"
    (let [;; tool_search is a built-in row the run declares only while a
          ;; deferred tool exists to find, so this table (no sources) has none
          builtins (disj (set tools-registry/builtin-tool-names) "tool_search")
          builtin-names (fn [ag] (into #{} (filter builtins) (map :name (state/active-tools ag))))
          all-names (fn [ag] (set (map :name (state/active-tools ag))))
          ag (support/make-agent-state :provider :opencode-go :model "deepseek-v4-flash"
                                       :enabled-tool-names (support/tool-names ["-bash"]))]
      (t/is (not (contains? (builtin-names ag) "bash")))
      (t/is (contains? (builtin-names ag) "read"))
      (t/testing "extension tools are on under a modifier-only selection"
        (try
          (tools-registry/register-tool! (support/tool-table) {:name "resources-ext"
                                                               :description "d"
                                                               :execute (fn [_] {:content ""})})
          (t/is (contains? (all-names ag) "resources-ext"))
          (finally (tools-registry/unregister-tool! (support/tool-table) "resources-ext"))))
      (t/testing "an explicit set-active-tools! selection replaces the selection"
        (state/set-active-tools! ag ["bash"])
        (t/is (= #{"bash"} (all-names ag))))
      (t/testing "nil restores the configured selection"
        (state/set-active-tools! ag nil)
        (t/is (= (disj builtins "bash") (builtin-names ag))))
      (t/testing "no source → the default built-in set, opt-ins off"
        (let [fal (support/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")
              all (tools-registry/get-all-tools (support/tool-table))]
          (t/is (= (disj (set tools-registry/default-tool-names) "tool_search")
                   (all-names fal)))
          (t/is (contains? (set (keys all)) "codemode"))
          (t/is (contains? (set (keys all)) "eval"))
          (t/is (not (contains? (all-names fal) "codemode"))
                "the opt-ins are mounted but absent from the active set")
          (t/is (not (contains? (all-names fal) "eval"))))))))

(t/deftest test-resources-selection-updates-live
  (t/testing "a settings change reaches the selection without a reload call"
    (let [enabled (atom #{"read"})
          builtins (disj (set tools-registry/builtin-tool-names) "tool_search")
          builtin-names (fn [ag] (into #{} (filter builtins) (map :name (state/active-tools ag))))
          ag (support/make-agent-state :provider :opencode-go :model "deepseek-v4-flash"
                                       :enabled-tool-names (fn [] @enabled))]
      (t/is (= #{"read"} (builtin-names ag)))
      (reset! enabled #{"read" "bash"})
      (t/is (= #{"read" "bash"} (builtin-names ag)))
      (reset! enabled #{"read"})
      (t/is (= #{"read"} (builtin-names ag))
            "a tool removed from the selection goes inactive")
      (state/set-active-tools! ag ["write"])
      (t/is (= #{"write"} (builtin-names ag)))
      (state/set-active-tools! ag nil)
      (t/is (= #{"read"} (builtin-names ag))
            "nil restores the current selection"))))

(t/deftest test-resource-selection-live-base-overrides-the-opt-in-default
  ;; a modifier-only resources list starts from the live set, so the mounted
  ;; opt-ins stay on
  (let [ag (support/make-agent-state :provider :opencode-go :model "deepseek-v4-flash"
                                     :enabled-tool-names (support/tool-names ["-bash"]))
        names (set (map :name (state/active-tools ag)))]
    (t/is (contains? names "codemode"))
    (t/is (contains? names "eval"))
    (t/is (not (contains? names "bash")))))

(t/deftest test-thinking-level-select-event
  (let [events (atom [])
        ag (support/make-agent-state :provider :opencode-go :model "deepseek-v4-flash"
                                     :thinking :off
                                     :on-event (fn [evt] (swap! events conj evt)))]
    (state/set-thinking-level! ag :high)
    (state/set-thinking-level! ag :high) ;; no change → no event
    (t/is (= 1 (count @events)))
    (t/is (= :thinking-level-select (:type (first @events))))
    (t/is (= :high (:level (first @events))))
    (t/is (= :off (:previous-level (first @events))))))

(t/deftest test-two-agents-hold-two-tool-tables
  ;; 4c-iii-c's gate, from the loop's side: the table is the agent ctx's
  ;; `:tools` service, so one name resolves to each agent's own record — and a
  ;; name only one agent's table holds is unknown to the other, instead of
  ;; falling back to whatever the process registered.
  (let [probe (fn [tag]
                {:name "probe" :label "Probe"
                 :description "Says where it came from"
                 :params {}
                 :execute (fn [_] {:content tag :is-error false})})
        run (fn [ctx]
              (let [events (atom [])
                    calls (atom 0)
                    agent (support/make-agent-state :ctx ctx
                                                    :on-event (fn [e] (swap! events conj e)))]
                (with-redefs [cfg/get-api-key (fn [_] "test-key")
                              llm/send-message
                              (fn [opts]
                                (future
                                  (if (= 1 (swap! calls inc))
                                    (do (when-let [on-tc (:on-tool-call opts)]
                                          (on-tc {:id "tc1" :name "probe"
                                                  :arguments "{}" :index 0}))
                                        (when-let [on-done (:on-done opts)]
                                          (on-done :tool-calls)))
                                    (when-let [on-done (:on-done opts)]
                                      (on-done :stop)))
                                  :done))]
                  @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
                (->> @events
                     (filter #(= :tool-execution-end (:type %)))
                     first
                     :result)))
        a (support/test-ctx)
        b (support/test-ctx)
        c (support/test-ctx)]
    (tools-registry/register-tool! (:tools a) (probe "from-a"))
    (tools-registry/register-tool! (:tools b) (probe "from-b"))
    (t/is (= "from-a" (:content (run (:ctx a)))) "A's run reaches A's record")
    (t/is (= "from-b" (:content (run (:ctx b)))) "B's run reaches B's record")
    (let [result (run (:ctx c))]
      (t/is (true? (:is-error result))
            "a ctx with no probe settles the call as an error")
      (t/is (= "Tool probe not found" (:content result))
            "rather than dispatching to another agent's table"))))
