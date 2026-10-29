(ns kmet.app.loop.test-guard
  "Repeat-loop guard: the circuit breaker that ends a turn whose tool calls repeat."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.ai.llm :as llm]
            [kmet.app.loop.state :as state]
            [kmet.app.loop :as loop]
            [kmet.app.loop-guard :as guard]
            [kmet.app.loop.stages :as loop-stages]
            [kmet.app.retry :as retry]
            [kmet.app.config :as cfg]
            [kmet.app.loop.test-support :as support]))

(t/use-fixtures :each support/with-host)

;; ─── Repeat-loop guard (circuit breaker) ────────────────────────────────

(t/deftest test-loop-guard-filter-same-call
  ;; The 3rd identical call (threshold 3) is suppressed.
  (let [r (reduce (fn [st _]
                    (:state (guard/loop-guard-filter st 3 [(support/loop-guard-tc "bash" {:command "ls"})])))
                  {:window [] :suppressed 0} (range 3))]
    (t/is (= 1 (:suppressed r)) "3rd identical call suppressed")))

(t/deftest test-loop-guard-filter-alternating
  ;; 1,2,1,2,1,2 → the 3rd "1" (call 5) is suppressed — alternation is
  ;; caught by the per-signature window count, not just consecutive runs.
  (let [r (reduce (fn [st i]
                    (:state (guard/loop-guard-filter
                             st 3 [(support/loop-guard-tc "bash" {:command (str "cmd" (inc (mod i 2)))})])))
                  {:window [] :suppressed 0} (range 6))]
    (t/is (= 2 (:suppressed r)) "1,2,1,2,1,2 trips at the 3rd occurrence of each sig")))

(t/deftest test-loop-guard-filter-cycle
  ;; 1,2,3,1,2,3 → the 3rd "1" (call 7) is suppressed.
  (let [r (reduce (fn [st i]
                    (:state (guard/loop-guard-filter
                             st 3 [(support/loop-guard-tc "bash" {:command (str "c" (inc (mod i 3)))})])))
                  {:window [] :suppressed 0} (range 7))]
    (t/is (= 1 (:suppressed r)) "1,2,3,1,2,3 trips at call 7"))
  ;; 1,2,3,4 ×3 → window 13 catches the 3rd "1" at call 9.
  (let [r (reduce (fn [st i]
                    (:state (guard/loop-guard-filter
                             st 3 [(support/loop-guard-tc "bash" {:command (str "c" (inc (mod i 4)))})])))
                  {:window [] :suppressed 0} (range 9))]
    (t/is (= 1 (:suppressed r)) "4-cycle trips at call 9")))

(t/deftest test-loop-guard-filter-read-exempt
  ;; read never trips the guard — re-reading a file is legitimate work.
  (let [r (reduce (fn [st _]
                    (:state (guard/loop-guard-filter st 3 [(support/loop-guard-tc "read" {:path "a"})])))
                  {:window [] :suppressed 0} (range 10))]
    (t/is (= 0 (:suppressed r)) "read is exempt")
    (t/is (= 0 (count (:window r))) "read never enters the window")))

(t/deftest test-loop-guard-filter-key-order-canonical
  ;; Argument maps differing only in key order count as identical.
  (t/is (= (guard/loop-guard-signature "edit" {:path "x" :edits [{:oldText "a" :newText "b"}]})
           (guard/loop-guard-signature "edit" {:edits [{:newText "b" :oldText "a"}] :path "x"}))
        "key-sorted canonical JSON ignores key order"))

(t/deftest test-loop-thinking-loop-detection
  (t/is (guard/thinking-loop? "Let me analyze this problem carefully. Let me analyze this problem carefully. Let me analyze this problem carefully."))
  (t/is (not (guard/thinking-loop? "First check the file. Then read imports. Then run tests.")))
  (t/is (not (guard/thinking-loop? "ok. ok. ok. ok."))
        "segments below the min span don't trip")
  (t/is (not (guard/thinking-loop? nil)) "nil is total — no NPE")
  (t/is (not (guard/thinking-loop? "")) "empty is false")
  (t/is (guard/thinking-loop? (str (apply str (repeat 3 "我们需要继续深入分析这个问题的根本原因和潜在影响。"))))
        "CJK full-stop delimiters trip — multilingual reasoning loops are caught"))

(t/deftest test-loop-guard-mixed-batch-source-order
  ;; A batch with one suppressed + one surviving call returns results in
  ;; source order — the suppressed call's synthetic guard result first, the
  ;; survivor's real result second (regression: map-over-map produced
  ;; garbage entries).
  (let [events (atom [])
        executed (atom [])
        calls (atom 0)
        agent (support/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (cond
                          (= 1 n)
                          (do (when-let [on-tc (:on-tool-call opts)]
                                (on-tc {:id "s1" :name "bash" :arguments "{\"command\":\"ls\"}" :index 0}))
                              (when-let [on-done (:on-done opts)] (on-done :tool-calls)))
                          (= 2 n)
                          (do (when-let [on-tc (:on-tool-call opts)]
                                (on-tc {:id "s2" :name "bash" :arguments "{\"command\":\"ls\"}" :index 0})
                                (on-tc {:id "s3" :name "bash" :arguments "{\"command\":\"ls\"}" :index 1})
                                (on-tc {:id "w1" :name "bash" :arguments "{\"command\":\"pwd\"}" :index 2}))
                              (when-let [on-done (:on-done opts)] (on-done :tool-calls)))
                          :else
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "done"))
                              (when-let [on-done (:on-done opts)] (on-done :stop)))))
                      :done))
                  state/execute-tool-for
                  (fn [_ _ args _] (swap! executed conj (:command args))
                    {:content "ran" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    ;; turn 2: s2 (2nd identical) runs, s3 (3rd identical) suppressed, w1 runs
    (t/is (= 3 (count @executed)) "three tool executions total (turn 1 + turn 2)")
    ;; the turn-2 turn-end is the one with 3 tool results (turn 1 has 1,
    ;; the "done" reply has 0); results are in SOURCE order [s2 s3 w1] →
    ;; [ran guard ran]
    (let [te (first (filter #(and (= :turn-end (:type %))
                                  (= 3 (count (:tool-results %))))
                            @events))]
      (t/is (= ["ran" "Repeat-loop guard: bash called with identical args 3 times — suppressed." "ran"]
               (mapv :content (:tool-results te)))
            "turn-end carries results in source order, suppressed call carries guard text"))))

(t/deftest test-loop-run-agent-turn-loop-guard-terminates
  ;; End-to-end: the model keeps issuing the same bash call; the guard
  ;; suppresses the 3rd, then the run gives up (all-suppressed batch →
  ;; :loop-guard), settles idle, and delivers the explanation via on-done.
  (let [events (atom [])
        calls (atom 0)
        done-text (atom nil)
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! calls inc)
                      (when-let [on-tc (:on-tool-call opts)]
                        (on-tc {:id (str "tc" @calls) :name "bash" :arguments "{\"command\":\"ls\"}" :index 0}))
                      (when-let [on-done (:on-done opts)]
                        (on-done :tool-calls))
                      :done))
                  state/execute-tool-for
                  (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "run"
                             :on-done (fn [t] (reset! done-text t))
                             :on-error (fn [_])}))
    (t/is (>= @calls 3) "the guard gave up after the 3rd identical call")
    (t/is (some #(= :loop-guard (:type %)) @events)
          ":loop-guard event emitted")
    (t/is (= :idle @(:status agent)) "run settles idle")
    (t/is (str/includes? (or @done-text "") "repeated identical tool calls")
          "on-done carries the guard explanation")))

(t/deftest test-loop-state-names-its-ctx
  ;; A state names the ctx it reads its stages and tool table from — there is no
  ;; process default left — and a run can be pointed at its own
  ;; child ctx.
  (let [mine (:ctx (support/test-ctx))]
    (t/is (identical? mine (:ctx (support/make-agent-state :ctx mine)))
          "a run names the ctx it reads")
    (t/is (= :kmet/no-tool-service
             (try (state/tool-table (support/make-agent-state :ctx nil)) nil
                  (catch Exception e (:type (ex-data e)))))
          "a state with no ctx has no tool table — the throw names it")))

(t/deftest test-loop-runs-the-guard-in-its-ctx
  ;; The guard is the ctx's stage, not a namespace read: a spy in the run's
  ;; own ctx filters the batch (and trips it on the second, so the run
  ;; settles), and the stage config supplies the threshold (docs/architecture.md,
  ;; "Reload and the turn boundary").
  (let [calls (atom [])
        spy (fn [state threshold tool-calls]
              (swap! calls conj threshold)
              ;; Suppress the batch: the run trips the guard and settles, and
              ;; the stage config's threshold is what the batch was judged by.
              {:suppressed tool-calls :survivors [] :state state})
        ;; the ctx carries the guard stage: the spy replaces the handle and
        ;; its config supplies the threshold
        mine (:ctx (support/test-ctx {:loop-guard {:call spy
                                                   :config {:threshold 7}}}))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  state/execute-tool-for
                  (fn [_ _ _ _] {:content "ok" :is-error false})
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-tc (:on-tool-call opts)]
                        (on-tc {:id "tc1" :name "bash"
                                :arguments "{\"command\":\"ls\"}" :index 0}))
                      (when-let [on-done (:on-done opts)]
                        (on-done :tool-calls))
                      :done))]
      (let [agent (support/make-agent-state :ctx mine :on-event (fn [_]))]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-done (fn [_])
                                     :on-error (fn [_])})
        (t/is (seq @calls) "the ctx's handle filtered the batch")
        (t/is (= [7] (distinct @calls))
              "the stage config reached the ctx's handle")
        (t/is (= :idle @(:status agent)) "the run settled through the guard")))))

(t/deftest test-loop-runs-the-retry-policy-in-its-ctx
  ;; The retry policy is a ctx stage too: a spy replacing it decides the
  ;; errored call's next move, and its config overrides the run's own option
  ;; (docs/architecture.md, "Reload and the turn boundary").
  (let [seen (atom [])
        spy (fn [state]
              (swap! seen conj state)
              (retry/retry-decision state))
        mine (:ctx (support/test-ctx {:retry-policy {:call spy
                                                     :config {:max-retries 1}}}))
        calls (atom 0)
        ;; the run's own budget would end the error immediately; the mount's
        ;; config is the one that counts
        agent (support/make-agent-state :ctx mine :max-retries 0
                                        :base-delay-ms 1 :on-event (fn [_]))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (when-let [on-error (:on-error opts)]
                          (on-error "connection lost"))
                        (when-let [on-done (:on-done opts)]
                          (on-done :stop)))
                      :done))]
      @(loop/run-agent-turn agent {:message "go" :on-done (fn [_]) :on-error (fn [_])}))
    (t/is (= 1 (count @seen)) "the errored call reached the ctx's handle")
    (t/is (= 1 (:max-retries (first @seen)))
          "the entry config's budget reached the policy")
    (t/is (= 1 (:base-delay-ms (first @seen)))
          "the run's backoff option rode along")
    (t/is (= 2 @calls) "the policy's retry ran a second attempt")
    (t/is (= :idle @(:status agent)) "the retried run settled")))

(t/deftest test-loop-stages-survive-a-mid-attempt-change
  ;; The attempt reads its stages once. A change to the ctx's stages during
  ;; the attempt (here a refresh) is invisible to it, and the
  ;; next attempt reads the new map — the tool-unload race the rule closes.
  (let [used (atom [])
        guard-a (fn [state _threshold tool-calls]
                  (swap! used conj :a)
                  {:suppressed [] :survivors tool-calls :state state})
        guard-b (fn [state _threshold tool-calls]
                  (swap! used conj :b)
                  {:suppressed tool-calls :survivors [] :state state})
        run-ctx (:ctx (support/test-ctx {:loop-guard {:call guard-a}}))
        run! (fn [on-first-call]
               (let [calls (atom 0)]
                 (with-redefs [cfg/get-api-key (fn [_] "test-key")
                               state/execute-tool-for
                               (fn [_ _ _ _] {:content "ok" :is-error false})
                               llm/send-message
                               (fn [opts]
                                 (future
                                   (when (= 1 (swap! calls inc))
                                     (on-first-call)
                                     (when-let [on-tc (:on-tool-call opts)]
                                       (on-tc {:id "tc1" :name "bash"
                                               :arguments "{\"command\":\"ls\"}"
                                               :index 0})))
                                   (when-let [on-done (:on-done opts)]
                                     (on-done :tool-calls))
                                   :done))]
                   @(loop/run-agent-turn
                     (support/make-agent-state :ctx run-ctx :on-event (fn [_]))
                     {:message "run" :on-done (fn [_]) :on-error (fn [_])}))))]
    ;; The ctx's stages change while the first attempt is in flight.
    (run! (fn []
            (with-redefs [loop-stages/defaults
                          (assoc loop-stages/defaults
                                 :loop-guard {:call guard-b})]
              (loop-stages/refresh! run-ctx))))
    (t/is (= [:a] @used) "the attempt kept the handle it started with")
    (t/is (= guard-b (get-in (loop-stages/for-ctx run-ctx) [:loop-guard :call]))
          "and the ctx really did change under it")
    ;; The next attempt reads the new tree: guard-b suppresses its only call,
    ;; which trips the guard and settles the run (one handle call).
    (run! (fn []))
    (t/is (= [:a :b] @used) "the next attempt read the refreshed handle")))

(t/deftest test-loop-run-agent-turn-thinking-loop-guard-terminates
  ;; End-to-end: the model streams the same reasoning segment repeatedly;
  ;; the thinking-loop guard cuts the stream and settles the run with the
  ;; explanation (no auto-retry, no red error line).
  (let [events (atom [])
        done-text (atom nil)
        llm-signal (atom nil)
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    ;; The LLM-call signal is the per-call stream cut — the only
                    ;; transport the SSE loop derefs — so capture it to assert
                    ;; the guard actually aborts the HTTP stream below.
                    (reset! llm-signal (:signal opts))
                    (future
                      (when-let [on-thinking (:on-thinking opts)]
                        (dotimes [_ 4]
                          (on-thinking "Let me analyze this problem carefully. ")))
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent
                            {:message "run"
                             :on-done (fn [t] (reset! done-text t))
                             :on-error (fn [e] (reset! done-text (str "ERR:" e)))}))
    (t/is (some #(= :loop-guard (:type %)) @events)
          ":loop-guard event emitted")
    (t/is (= :idle @(:status agent)) "run settles idle")
    (t/is (str/includes? (or @done-text "") "repeated reasoning detected")
          "on-done carries the thinking-guard explanation")
    (t/is (not (str/starts-with? (or @done-text "") "ERR:"))
          "not routed through the red error path")
    (t/is (true? (boolean @llm-signal))
          "the guard trips its per-call stream cut — the passed signal derefs true")
    (t/is (false? @(:signal agent))
          "the run-level cancel signal stays false — follow-up turns share it and must not inherit a stray cancel")))

(t/deftest test-loop-run-agent-turn-loop-guard-followup-retrips
  ;; A follow-up queued before the run gives the model a fresh guard window:
  ;; after the first trip the follow-up's turn re-runs, and a still-stuck
  ;; model trips again — each segment gets its own threshold (the follow-up
  ;; is a new directive, not a continuation of the loop).
  (let [events (atom [])
        calls (atom 0)
        agent (support/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (when-let [on-tc (:on-tool-call opts)]
                          (on-tc {:id (str "t" n) :name "bash"
                                  :arguments "{\"command\":\"ls\"}" :index 0}))
                        (when-let [on-done (:on-done opts)]
                          (on-done :tool-calls))
                        :done)))
                  state/execute-tool-for
                  (fn [_ _ _ _] {:content "ok" :is-error false})]
      (state/follow-up! agent "continue please")
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 2 (count (filter #(= :loop-guard (:type %)) @events)))
          "each loop segment (initial run + follow-up) trips once")
    (t/is (= 6 (count (filter #(= :tool (:role %)) (state/get-context agent))))
          "two segments x 3 tool entries (2 real + 1 suppressed each)")
    (t/is (= :idle @(:status agent)) "run settles idle")))

(t/deftest test-loop-run-agent-turn-loop-guard-disabled
  ;; Guard off → the loop runs without suppression: same calls repeat past
  ;; the threshold without any :loop-guard event, and the run settles
  ;; normally once the stub produces a plain reply.
  (let [events (atom [])
        calls (atom 0)
        agent (support/make-agent-state
               :loop-guard-enabled false
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (when (<= n 3)
                          (when-let [on-tc (:on-tool-call opts)]
                            (on-tc {:id (str "tc" n) :name "bash" :arguments "{}" :index 0})))
                        (when-let [on-done (:on-done opts)]
                          (on-done (if (<= n 3) :tool-calls :stop)))
                        (when (and (> n 3) (:on-text opts))
                          ((:on-text opts) "done")))
                      :done))
                  state/execute-tool-for
                  (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (nil? (some #(= :loop-guard (:type %)) @events))
          "no :loop-guard event when disabled")
    (t/is (>= @calls 3) "the same call repeated past the threshold")
    (t/is (= :idle @(:status agent)))))

(t/deftest test-loop-run-agent-turn-write-resets-guard
  ;; bash -> edit(ok) -> bash -> edit(ok) -> ... never trips: each successful
  ;; edit clears the guard's memory of other calls (the world changed, so a
  ;; repeated bash observes new state). Without the reset the 3rd bash (call
  ;; 5) would be suppressed and the run would give up.
  (let [events (atom [])
        calls (atom 0)
        done-text (atom nil)
        agent (support/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (if (> n 7)
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "done"))
                              (when-let [on-done (:on-done opts)] (on-done :stop)))
                          (do (when-let [on-tc (:on-tool-call opts)]
                                (if (odd? n)
                                  (on-tc {:id (str "b" n) :name "bash"
                                          :arguments "{\"command\":\"ls\"}" :index 0})
                                  ;; Distinct edit args per turn — required:
                                  ;; identical edits would trip on their own
                                  ;; signature (see identical-write-trips).
                                  (on-tc {:id (str "e" n) :name "edit"
                                          :arguments (str "{\"path\":\"f.clj\",\"edits\":[{\"oldText\":\"a" n "\",\"newText\":\"b" n "\"}]}")
                                          :index 0})))
                              (when-let [on-done (:on-done opts)]
                                (on-done :tool-calls)))))
                      :done))
                  state/execute-tool-for
                  (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "run"
                             :on-done (fn [t] (reset! done-text t))
                             :on-error (fn [_])}))
    (t/is (>= @calls 8) "run continued past the 3rd bash without tripping")
    (t/is (nil? (some #(= :loop-guard (:type %)) @events))
          "no :loop-guard event — successful edits reset the guard")
    (t/is (= "done" @done-text) "run settles normally")))

(t/deftest test-loop-run-agent-turn-write-tool-resets-guard
  ;; Same contract as the edit reset, through the write tool: bash ->
  ;; write(ok, novel content) -> ... never trips.
  (let [events (atom [])
        calls (atom 0)
        done-text (atom nil)
        agent (support/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (if (> n 7)
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "done"))
                              (when-let [on-done (:on-done opts)] (on-done :stop)))
                          (do (when-let [on-tc (:on-tool-call opts)]
                                (if (odd? n)
                                  (on-tc {:id (str "b" n) :name "bash"
                                          :arguments "{\"command\":\"ls\"}" :index 0})
                                  ;; Distinct content per turn — required:
                                  ;; identical writes trip on their own
                                  ;; signature (see identical-write-trips).
                                  (on-tc {:id (str "w" n) :name "write"
                                          :arguments (str "{\"path\":\"f.clj\",\"content\":\"v" n "\"}")
                                          :index 0})))
                              (when-let [on-done (:on-done opts)]
                                (on-done :tool-calls)))))
                      :done))
                  state/execute-tool-for
                  (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "run"
                             :on-done (fn [t] (reset! done-text t))
                             :on-error (fn [_])}))
    (t/is (>= @calls 8) "run continued past the 3rd bash without tripping")
    (t/is (nil? (some #(= :loop-guard (:type %)) @events))
          "no :loop-guard event — successful writes reset the guard")
    (t/is (= "done" @done-text) "run settles normally")))

(t/deftest test-loop-run-agent-turn-failed-write-keeps-guard
  ;; edit failures change nothing: bash -> edit(fail) -> bash ... still trips
  ;; on the 3rd identical bash.
  (let [events (atom [])
        calls (atom 0)
        agent (support/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (when-let [on-tc (:on-tool-call opts)]
                          (if (odd? n)
                            (on-tc {:id (str "b" n) :name "bash"
                                    :arguments "{\"command\":\"ls\"}" :index 0})
                            (on-tc {:id (str "e" n) :name "edit"
                                    :arguments "{\"path\":\"f.clj\",\"edits\":[{\"oldText\":\"a\",\"newText\":\"b\"}]}"
                                    :index 0})))
                        (when-let [on-done (:on-done opts)]
                          (on-done :tool-calls)))
                      :done))
                  state/execute-tool-for
                  (fn [_ tool-name _ _]
                    (if (= "edit" tool-name)
                      {:content "edit failed" :is-error true}
                      {:content "ok" :is-error false}))]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 5 @calls) "the guard gave up on the 3rd identical bash")
    (t/is (some #(= :loop-guard (:type %)) @events)
          ":loop-guard event emitted — failed edits don't reset")))

(t/deftest test-loop-run-agent-turn-identical-write-trips
  ;; Repeating the identical edit itself still trips — the reset keeps each
  ;; writer's own signature.
  (let [events (atom [])
        calls (atom 0)
        executed (atom 0)
        agent (support/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! calls inc)
                      (when-let [on-tc (:on-tool-call opts)]
                        (on-tc {:id (str "e" @calls) :name "edit"
                                :arguments "{\"path\":\"f.clj\",\"edits\":[{\"oldText\":\"a\",\"newText\":\"b\"}]}"
                                :index 0}))
                      (when-let [on-done (:on-done opts)]
                        (on-done :tool-calls))
                      :done))
                  state/execute-tool-for
                  (fn [_ _ _ _] (swap! executed inc) {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 3 @calls) "the guard gave up on the 3rd identical edit")
    (t/is (= 2 @executed) "only the first two edits executed")
    (t/is (some #(= :loop-guard (:type %)) @events)
          ":loop-guard event emitted")))
