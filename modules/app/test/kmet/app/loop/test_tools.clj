(ns kmet.app.loop.test-tools
  "Tool execution: before/after hooks, parallel tool calls, bash result recording,
   active tools, and thinking-level events."
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
            [kmet.app.loop.test-support :as support]))

(t/use-fixtures
  :each
  ;; A test that needs a tool table should not boot the app root: the loop
  ;; reads the table from its ctx's `:tools` service, so `with-registry` gives
  ;; this namespace its own (see `test-support/with-registry`).
  (fn [f] (support/with-registry f)))

;; ─── Tool hooks (before/after tool-call) ─────────────────────────────────

(t/deftest test-loop-before-tool-call-blocks
  (let [events (atom [])
        executed (atom false)
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:before-tool-call agent)
            (fn [_] {:block true :reason "Permission denied"}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] (reset! executed true)
                                           {:content "should not run" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (false? @executed) "blocked tool must not execute")
    (t/is (not-any? #(= :tool-execution-prepared (:type %)) @events)
          "a blocked call never reaches execution, so it is never prepared")
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "tc1" (:tool-call-id end)))
      (t/is (true? (:is-error end)) "blocked tool result is an error")
      (t/is (= "Permission denied" (:content (:result end)))))))

(t/deftest test-loop-prepared-event-follows-the-before-hook
  ;; :tool-execution-start fires before the before-tool-call hook (pi), so a
  ;; gated call's block exists while an approval `:ask` is still up;
  ;; :tool-execution-prepared is emitted once the hook and the permission gate
  ;; passed, just before the body runs — the moment a front end starts timing
  ;; the tool.
  (let [order (atom [])
        agent (support/make-agent-state
               :on-event (fn [e]
                           (when (#{:tool-execution-start :tool-execution-prepared
                                    :tool-execution-end} (:type e))
                             (swap! order conj (:type e)))))]
    (reset! (:before-tool-call agent)
            (fn [_] (swap! order conj :gate) nil))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] (swap! order conj :exec)
                                           {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= [:tool-execution-start :gate :tool-execution-prepared :exec
              :tool-execution-end]
             @order)
          "start, the gate, prepared, the body, end — in that order")))

(t/deftest test-loop-before-tool-call-terminate
  (let [events (atom [])
        executed (atom false)
        llm-calls (atom 0)
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:before-tool-call agent)
            (fn [_] {:block true :reason "Policy" :terminate true}))
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
    (t/is (false? @executed) "blocked tool must not execute")
    (t/is (= 1 @llm-calls) "terminate stops the run — no follow-up LLM call")
    (t/is (= :idle @(:status agent)))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "tc1" (:tool-call-id end)))
      (t/is (= "Policy" (:content (:result end))) "blocked result still in transcript")
      (t/is (true? (:is-error end))))))

(t/deftest test-loop-before-tool-call-terminate-mixed-batch
  (let [events (atom [])
        executed (atom 0)
        llm-calls (atom 0)
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    ;; pi: terminate only when EVERY finalized call in the batch carries
    ;; the hint — a single non-terminated call keeps the run going
    (reset! (:before-tool-call agent)
            (fn [ctx]
              (when (= "tc1" (:tool-call-id ctx))
                {:block true :reason "Policy" :terminate true})))
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
    (t/is (= 1 @executed) "non-blocked call in the batch executed")
    (t/is (= 2 @llm-calls) "mixed batch does not terminate — follow-up LLM call happened")
    (t/is (= :idle @(:status agent)))
    (t/is (some #(and (= :tool-execution-end (:type %))
                      (= "tc1" (:tool-call-id %))
                      (true? (:is-error %)))
                @events)
          "blocked call still reports its error result")))

(t/deftest test-loop-before-tool-call-hook-throws
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:before-tool-call agent)
            (fn [_] (throw (ex-info "hook boom" {}))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (true? (:is-error end)))
      (t/is (.contains (:content (:result end)) "before-tool-call hook error")))))

(t/deftest test-loop-undeclared-tool-call-settles-as-error
  ;; pi: prepareToolCall resolves the call against the tools declared for the
  ;; request — a call to a tool the model was not given (disabled built-in,
  ;; hallucinated name) settles as an error and never executes
  (let [events (atom [])
        executed (atom [])
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               ;; bash is left out of the built-in selection
               :default-tools ["read" "write" "edit" "run_code"])]
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
               :default-tools ["read"])]
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
               :default-tools ["bash"])]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ name _ _] (swap! executed conj name)
                                           {:content "ran" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= ["bash"] @executed))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (false? (:is-error end)))
      (t/is (= "ran" (:content (:result end)))))))

(t/deftest test-loop-after-tool-call-rewrites
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:after-tool-call agent)
            (fn [{:keys [result]}]
              {:content (str (:content result) " [sanitized]")}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] {:content "secret-key=abc" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "secret-key=abc [sanitized]" (:content (:result end))))
      (t/is (false? (:is-error end)) "rewrite preserves is-error unless overridden"))))

(t/deftest test-loop-after-tool-call-sets-error
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:after-tool-call agent)
            (fn [{:keys [result]}]
              {:content (:content result) :is-error true}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (true? (:is-error end)) "after hook can mark a result as error"))))

(t/deftest test-loop-after-tool-call-hook-throws
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:after-tool-call agent)
            (fn [_] (throw (ex-info "hook boom" {}))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (true? (:is-error end)))
      (t/is (.contains (:content (:result end)) "after-tool-call hook error")))))

(t/deftest test-loop-before-tool-call-rewrites-args
  (let [seen (atom [])
        agent (support/make-agent-state)]
    (reset! (:before-tool-call agent)
            (fn [_] {:args {:command "rewritten"}}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ args _]
                                           (swap! seen conj args)
                                           {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= [{:command "rewritten"}] @seen)
          "the parallel path executes with the rewritten args")))

(t/deftest test-loop-before-tool-call-rewrites-args-sequential
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
        (reset! (:before-tool-call agent)
                (fn [_] {:args {:x "rewritten"}}))
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
  ;; the run's agent-level hooks reach calls made inside a script: the loop
  ;; binds run-code/*tool-hooks* and run-code/*assistant-message* around the batch,
  ;; the bridge captures them on the tool thread and hands them to its pool
  ;; workers (raw threads convey no bindings)
  (let [hook-calls (atom [])
        calls (atom 0)
        agent (support/make-agent-state)]
    (reset! (:before-tool-call agent)
            (fn [ctx] (swap! hook-calls conj ctx) nil))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "run_code"
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
          "the scripted read call fired the run's before-tool-call hook")
    (t/is (some #(str/starts-with? (str (:tool-call-id %)) "run_code-") @hook-calls)
          "the inner call carries a synthetic script-N tool-call-id")
    (t/is (some #(= "tc1" (:tool-call-id %)) @hook-calls)
          "the outer run_code call went through the same hook")
    (t/is (= (:assistant-message (first (filter #(= "run_code" (:tool-name %)) @hook-calls)))
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

(t/deftest test-loop-parallel-after-tool-call-error-settles-once
  ;; An after hook throwing a non-Exception kills the tool's future before
  ;; it settles; the batch settles await-all's error fallback as-is instead
  ;; of re-running the failed hook (which would throw again on the loop
  ;; thread).
  (let [hook-calls (atom 0)
        events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:after-tool-call agent)
            (fn [_]
              (swap! hook-calls inc)
              (throw (AssertionError. "after hook boom"))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 1 @hook-calls) "the failed after hook runs exactly once")
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "tc1" (:tool-call-id end)))
      (t/is (true? (:is-error end)) "the future's failure is reported as an error result")
      (t/is (str/includes? (:content (:result end)) "Error executing tool")))
    (t/is (= :idle @(:status agent)) "the turn completes")))

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
  (let [dir (str (fs/create-temp-dir {:dir (System/getProperty "user.home")}))
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
  (let [dir (str (fs/create-temp-dir {:dir (System/getProperty "user.home")}))
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
      (t/is (nil? @(:enabled-tools ag)))
      (state/set-active-tools! ag ["read" "write"])
      (t/is (= #{"read" "write"} @(:enabled-tools ag)))
      (state/set-active-tools! ag nil)
      (t/is (nil? @(:enabled-tools ag))))))

(t/deftest test-active-tools-default-tools
  (t/testing "a :default-tools selection filters built-ins; extension tools
              stay enabled; a runtime selection wins; nil restores the default"
    (let [builtins (set tools-registry/builtin-tool-names)
          builtin-names (fn [ag] (into #{} (filter builtins) (map :name (state/active-tools ag))))
          all-names (fn [ag] (set (map :name (state/active-tools ag))))
          ag (support/make-agent-state :provider :opencode-go :model "deepseek-v4-flash"
                                       :default-tools ["read"])]
      (t/is (= #{"read"} (builtin-names ag)))
      (t/testing "extension tools registered later stay active"
        (try
          (tools-registry/register-tool! (support/tool-table) {:name "default-tools-ext"
                                                               :description "d"
                                                               :execute (fn [_] {:content ""})})
          (t/is (contains? (all-names ag) "default-tools-ext"))
          (finally (tools-registry/unregister-tool! (support/tool-table) "default-tools-ext"))))
      (t/testing "an explicit set-active-tools! selection replaces the default"
        (state/set-active-tools! ag ["bash"])
        (t/is (= #{"bash"} (all-names ag))))
      (t/testing "nil restores the configured default surface"
        (state/set-active-tools! ag nil)
        (t/is (= #{"read"} (builtin-names ag))))
      (t/testing "no setting → every tool active"
        (let [fal (support/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")]
          (t/is (= (set (keys (tools-registry/get-all-tools (support/tool-table)))) (all-names fal))))))))

(t/deftest test-apply-default-tools-reload
  (t/testing "reload replaces the selection: tools newly in
              :default-tools become active; tools removed from it
              go inactive (pi: defaultTools controls the built-in
              surface)"
    (let [builtins (set tools-registry/builtin-tool-names)
          builtin-names (fn [ag] (into #{} (filter builtins) (map :name (state/active-tools ag))))
          ag (support/make-agent-state :provider :opencode-go :model "deepseek-v4-flash"
                                       :default-tools ["read"])]
      (t/is (= #{"read"} (builtin-names ag)))
      (state/apply-default-tools! ag ["read" "bash"])
      (t/is (= #{"read" "bash"} (builtin-names ag)))
      (state/apply-default-tools! ag ["read"])
      (t/is (= #{"read"} (builtin-names ag))
            "a tool removed from the setting goes inactive")
      (state/apply-default-tools! ag nil)
      (t/is (= builtins (builtin-names ag))
            "a removed setting (nil) re-enables every built-in"))))

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
                    agent (support/make-agent-state :ctx ctx
                                                    :on-event (fn [e] (swap! events conj e)))]
                (with-redefs [cfg/get-api-key (fn [_] "test-key")
                              llm/send-message
                              (fn [opts]
                                (future
                                  (when-let [on-tc (:on-tool-call opts)]
                                    (on-tc {:id "tc1" :name "probe"
                                            :arguments "{}" :index 0}))
                                  (when-let [on-done (:on-done opts)]
                                    (on-done :tool-calls))
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
