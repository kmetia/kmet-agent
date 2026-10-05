(ns kmet.app.test-run-code
  "The run_code tool: per-call SCI sandbox, async tools bridge, capture and
   deadline behavior (design: run_code.md)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.core :as tools]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.run-code :as run-code]
            [kmet.app.tools.util :as tool-util]))

(defn- run
  "Execute the run_code tool the way the registry does."
  [code & [opts on-update]]
  (tools/execute-tool "run_code" (merge {:code code} opts) {:on-update on-update}))

(defn- with-custom-tool [tool f]
  (registry/register-tool! tool)
  (try (f)
       (finally (registry/unregister-tool! (:name tool)))))

(defn- with-tool-source [id tools-fn f]
  (registry/register-tool-source! id tools-fn)
  (try (f)
       (finally (registry/unregister-tool-source! id))))

(defn- temp-dir []
  (let [dir (str (fs/absolutize (str "target/test-run-code-" (System/nanoTime))))]
    (fs/create-dirs dir)
    dir))

(t/deftest test-run-code-return-value
  (let [r (run "(+ 1 2)")]
    (t/is (not (:is-error r)))
    (t/is (= "3" (:content r)))
    (t/is (nil? (get-in r [:details :timeout])) "no deadline unless asked")))

(t/deftest test-run-code-print-and-return
  (let [r (run "(println \"hello\") 42")]
    (t/is (not (:is-error r)))
    (t/is (str/includes? (:content r) "hello"))
    (t/is (str/includes? (:content r) "42")))
  (t/is (= "(no output)" (:content (run "nil")))))

(t/deftest test-run-code-emit-compact-result
  (let [r (run "(sandbox/emit {:count 2 :items [1 2]})")]
    (t/is (not (:is-error r)))
    (t/is (= "{:count 2, :items [1 2]}" (str/trim (:content r))))
    (t/is (nil? (:truncation r)))))

(t/deftest test-run-code-error
  (let [r (run "(this-does-not-exist 1)")]
    (t/is (:is-error r))
    (t/is (= :error (get-in r [:details :error])))
    (t/is (str/includes? (:content r) "this-does-not-exist"))))

(t/deftest ^:slow test-run-code-timeout
  (let [r (run "(loop [] (recur))" {:timeout 0.3})]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) "timed out"))
    (t/is (= :timeout (get-in r [:details :error])))
    (t/is (= 0.3 (get-in r [:details :timeout])))))

(t/deftest ^:slow test-run-code-await-all-honors-timeout
  (let [release (promise)
        finished (atom false)]
    (with-custom-tool {:name "run-code-test-block"
                       :label "Block"
                       :description "Blocks until released"
                       :execute (fn [_]
                                  @release
                                  (reset! finished true)
                                  {:content "late"})}
      (fn []
        (try
          (let [r (run (str "(tools/await-all (tools/call-many "
                            "[{:name \"run-code-test-block\" :args {}}]))")
                       {:timeout 0.2})]
            (t/is (:is-error r))
            (t/is (= :timeout (get-in r [:details :error])))
            ;; The blocked call is still parked on RELEASE: the run observed
            ;; its deadline instead of waiting for the tool to settle. (A
            ;; wall-clock bound flakes under load; this is the mechanism.)
            (t/is (false? @finished)
                  "await-all observes the deadline without waiting for the tool"))
          (finally
            (deliver release true)
            (Thread/sleep 50)))))))

(t/deftest ^:slow test-run-code-await-all-honors-escape
  (let [signal (atom false)
        release (promise)]
    (with-custom-tool {:name "run-code-test-block"
                       :label "Block"
                       :description "Blocks until released"
                       :execute (fn [_] @release {:content "late"})}
      (fn []
        (try
          (let [started-at (System/currentTimeMillis)
                f (future
                    (tools/execute-tool
                     "run_code"
                     {:code (str "(tools/await-all (tools/call-many "
                                 "[{:name \"run-code-test-block\" :args {}}]))")}
                     {:signal signal}))]
            (Thread/sleep 100)
            (reset! signal true)
            (let [r (deref f 3000 nil)]
              (t/is (some? r))
              (t/is (:is-error r))
              (t/is (= :aborted (get-in r [:details :error])))
              (t/is (< (- (System/currentTimeMillis) started-at) 1000))))
          (finally
            (deliver release true)
            (Thread/sleep 50)))))))

(t/deftest ^:slow test-run-code-signal-abort
  (let [signal (atom false)
        f (future (tools/execute-tool "run_code" {:code "(loop [] (recur))"} {:signal signal}))]
    (Thread/sleep 100)
    (reset! signal true)
    (let [r (deref f 10000 nil)]
      (t/is (some? r))
      (t/is (:is-error r))
      (t/is (str/includes? (:content r) "aborted"))
      (t/is (= :aborted (get-in r [:details :error]))))))

(t/deftest ^:slow test-run-code-caught-interrupt-still-aborts
  ;; Throwable is not resolvable in the sandbox (no class access), but
  ;; Exception is — and the interrupt exception is catchable, so the abort
  ;; reason must win over whatever the script returns.
  (let [r (run "(try (loop [] (recur)) (catch Exception e :caught))" {:timeout 0.3})]
    (t/is (:is-error r))
    (t/is (= :timeout (get-in r [:details :error])))))

(t/deftest test-run-code-spawn-output-captured
  (let [r (run "@(sandbox/spawn (fn [] (println \"from-future\"))) :done")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "from-future"))))

(t/deftest test-run-code-emit-from-spawn
  (let [r (run "@(sandbox/spawn (fn [] (sandbox/emit :from-future))) :done")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) ":from-future"))
    (t/is (str/includes? (:content r) ":done"))))

(t/deftest test-run-code-capabilities
  (let [r (run (str "[(fs/exists? \"deps.edn\")"
                    "  (> (count (slurp \"deps.edn\")) 0)"
                    "  (json/generate-string {:a 1})"
                    "  (vec (pmap inc [1 2 3]))"
                    "  @(sandbox/spawn (fn [] 42))"
                    "  (pos? (sandbox/now))"
                    "  (string? (sandbox/env \"HOME\"))"
                    "  (pos? (count (sandbox/cwd)))"
                    "  (str/upper-case \"x\")"
                    "  (set/union #{1} #{2})"
                    "  (edn/read-string \"1\")"
                    "  (walk/postwalk identity [[1]])]"))]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= (str "[true true \"{\\\"a\\\":1}\" [2 3 4] 42 true true true "
                  "\"X\" #{1 2} 1 [[1]]]")
             (:content r)))))

(t/deftest test-run-code-explicit-require
  (let [r (run "(require '[clojure.string :as cs]) (cs/upper-case \"y\")")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= "Y" (:content r)))))

(t/deftest test-run-code-reload-is-noop
  ;; SCI consults the sandbox :load-fn even for a :reload; a known namespace
  ;; stays available (the pre-simplification loader returned its handle)
  (let [r (run "(require 'clojure.set :reload) (require '[babashka.fs :as fs] :reload) :ok")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= ":ok" (:content r)))))

(t/deftest test-run-code-slurp-follows-runtime-cwd
  (let [dir (temp-dir)]
    (try
      (spit (str dir "/hello.txt") "from-cwd")
      (binding [tool-util/*cwd* dir]
        (let [r (run (str "[(slurp \"hello.txt\") (= (sandbox/cwd) " (pr-str dir) ")]"))]
          (t/is (not (:is-error r)) (:content r))
          (t/is (str/includes? (:content r) "from-cwd"))
          (t/is (str/includes? (:content r) "true"))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-run-code-stderr-capture
  (let [r (run "(binding [*out* *err*] (println \"oops\")) :done")]
    (t/is (not (:is-error r)))
    (t/is (str/includes? (:content r) "[stderr]"))
    (t/is (str/includes? (:content r) "oops"))))

(t/deftest test-run-code-output-truncation
  (let [r (run "(dotimes [i 3000] (println i))")]
    (t/is (not (:is-error r)))
    (t/is (some? (:truncation r)))
    (t/is (= :lines (get-in r [:truncation :truncated-by])))
    (t/is (> (get-in r [:truncation :total-lines]) 2000))
    (t/is (str/includes? (:content r) "truncated"))))

(t/deftest test-run-code-output-budget
  (let [r (run "(dotimes [i 1000] (println (apply str (repeat 100 \"x\"))))"
               {:max-output-bytes 1024})]
    (t/is (not (:is-error r)))
    (t/is (= 1024 (get-in r [:truncation :max-bytes])))
    (t/is (= :bytes (get-in r [:truncation :truncated-by])))
    (t/is (str/includes? (:content r) "[run_code output truncated")))
  (let [r (run "(dotimes [i 1000] (println (apply str (repeat 100 \"x\"))))")]
    (t/is (= (* 16 1024) (get-in r [:truncation :max-bytes]))
          "the script default is smaller than bash's shared cap")))

(t/deftest test-run-code-output-limit-normalization
  (let [r (run "(dotimes [i 1000] (println (apply str (repeat 100 \"x\"))))"
               {:max-output-bytes 999999})]
    (t/is (= (* 50 1024) (get-in r [:truncation :max-bytes]))
          "limits clamp to the tool-wide byte cap"))
  (let [r (run "(dotimes [i 1000] (println (apply str (repeat 100 \"x\"))))"
               {:max-output-bytes 0})]
    (t/is (= (* 16 1024) (get-in r [:truncation :max-bytes])))
    (t/is (= 2000 (get-in r [:truncation :max-lines]))
          "invalid limits fall back to the defaults")))

(t/deftest test-run-code-streaming
  (let [updates (atom [])
        r (run "(dotimes [i 4] (println i) (sandbox/sleep 50))"
               {}
               (fn [partial] (swap! updates conj partial)))]
    (t/is (not (:is-error r)))
    (t/is (pos? (count @updates)))
    (t/is (every? :is-partial @updates))))

(t/deftest test-run-code-tool-call-promise
  (with-custom-tool {:name "run-code-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [args] {:content (pr-str args)})}
    (fn []
      (let [r (run "@(tools/call \"run-code-test-echo\" {:x 1})")]
        (t/is (not (:is-error r)) (:content r))
        (t/is (str/includes? (:content r) ":x 1"))
        (t/is (= [{:tool "run-code-test-echo" :ok true}]
                 (get-in r [:details :calls])))))))

(t/deftest test-run-code-sugar-and-fan-out
  (with-custom-tool {:name "run-code-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [args] {:content (pr-str args)})}
    (fn []
      (let [r (run (str "(let [a (tools/call \"run-code-test-echo\" {:n 1})"
                        "      b (tools/call \"run-code-test-echo\" {:n 2})]"
                        "  [(clojure.string/includes? (:content @a) \":n 1\")"
                        "   (clojure.string/includes? (:content @b) \":n 2\")])"))]
        (t/is (not (:is-error r)) (:content r))
        (t/is (= "[true true]" (:content r)))
        (t/is (= 2 (count (get-in r [:details :calls]))))))))

(t/deftest test-run-code-call-many-and-await-all
  (with-custom-tool {:name "run-code-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [args] {:content (str "echo-" (:n args))})}
    (fn []
      (let [r (run (str "(let [ps (tools/call-many [{:name \"run-code-test-echo\""
                        " :args {:n 1}} [\"run-code-test-echo\" {:n 2}]])]"
                        "  (mapv :content (tools/await-all ps)))"))]
        (t/is (not (:is-error r)) (:content r))
        (t/is (= "[\"echo-1\" \"echo-2\"]" (:content r)))
        (t/is (= 2 (count (get-in r [:details :calls]))))
        (t/is (every? :ok (get-in r [:details :calls])))))))

(t/deftest test-run-code-call-many-validates-before-dispatch
  (let [ran (atom 0)]
    (with-custom-tool {:name "run-code-test-echo"
                       :label "Echo"
                       :description "Echo args"
                       :execute (fn [args]
                                  (swap! ran inc)
                                  {:content (pr-str args)})}
      (fn []
        (let [r (run (str "(tools/call-many [{:name \"run-code-test-echo\""
                          " :args {:n 1}} 42])"))]
          (t/is (:is-error r))
          (t/is (zero? @ran))
          (t/is (empty? (get-in r [:details :calls]))))))))

(t/deftest test-run-code-fan-out-exceeds-worker-pool
  ;; the bridge drains through a bounded worker pool; a fan-out larger than
  ;; the pool must still settle every promise (run_code.md T1 bridge)
  (with-custom-tool {:name "run-code-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [args] {:content (pr-str args)})}
    (fn []
      (let [r (run (str "(count (mapv deref (mapv #(tools/call \"run-code-test-echo\" {:n %})"
                        " (range 40))))"))]
        (t/is (not (:is-error r)) (:content r))
        (t/is (= "40" (:content r)))
        (t/is (= 40 (count (get-in r [:details :calls]))))
        (t/is (every? :ok (get-in r [:details :calls])))))))

(t/deftest test-run-code-inner-tool-hooks
  ;; the run's agent-level hooks reach scripted calls: arg rewrite, block
  ;; (settled without executing, after hook still runs — loop parity),
  ;; result override; inner calls carry a synthetic id and no message
  (with-custom-tool {:name "run-code-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [args] {:content (pr-str args)})}
    (fn []
      (let [payloads (atom [])]
        (binding [run-code/*tool-hooks*
                  {:before (fn [ctx]
                             (swap! payloads conj ctx)
                             (when (= "run-code-test-echo" (:tool-name ctx))
                               (if (:block (:args ctx))
                                 {:block true :reason "blocked by hook"}
                                 {:args (assoc (:args ctx) :hooked true)})))
                   :after (fn [ctx]
                            (when (= "run-code-test-echo" (:tool-name ctx))
                              {:content (str (:content (:result ctx)) "|after")}))}]
          (let [r (run (str "[(deref (tools/call \"run-code-test-echo\" {:n 1}))"
                            " (deref (tools/call \"run-code-test-echo\" {:block true}))]"))]
            (t/is (not (:is-error r)) (:content r))
            (t/is (str/includes? (:content r) ":n 1, :hooked true"))
            (t/is (str/includes? (:content r) "|after"))
            (t/is (str/includes? (:content r) "blocked by hook|after"))
            (t/is (= 2 (count (get-in r [:details :calls]))))
            (t/is (not (:ok (get-in r [:details :calls 1])))))
          (t/is (= #{"run_code-0" "run_code-1"} (set (map :tool-call-id @payloads))))
          (t/is (every? #(not (contains? % :assistant-message)) @payloads)))))))

(t/deftest ^:slow test-run-code-timeout-cancels-inner-call
  ;; a script abort drives the combined signal its inner calls poll — the
  ;; same one the run signal feeds (bash's poller kills process trees)
  (let [observed (promise)]
    (with-custom-tool {:name "run-code-test-wait"
                       :label "Wait"
                       :description "Waits for the cancel signal"
                       :execute (fn [_]
                                  (let [sig bash-tool/*cancel-signal*]
                                    (loop [n 0]
                                      (when (and (< n 300) (not @sig))
                                        (Thread/sleep 10)
                                        (recur (inc n))))
                                    (deliver observed (boolean @sig))
                                    {:content "done"}))}
      (fn []
        (let [r (run "(deref (tools/call \"run-code-test-wait\" {}))" {:timeout 0.2})]
          (t/is (:is-error r))
          (t/is (= :timeout (get-in r [:details :error])))
          (t/is (true? (deref observed 3000 false))))))))

(t/deftest ^:slow test-run-code-queued-calls-skipped-after-abort
  ;; a call still in the pool queue when the script aborts settles as
  ;; cancelled and never executes — a timed-out script cannot fire
  ;; side effects from its queue
  (let [release (promise)
        late-ran (atom 0)]
    (with-custom-tool {:name "run-code-test-block"
                       :label "Block"
                       :description "Blocks until released"
                       :execute (fn [_] @release {:content "ok"})}
      (fn []
        (with-custom-tool {:name "run-code-test-late"
                           :label "Late"
                           :description "Counts invocations"
                           :execute (fn [_] (swap! late-ran inc) {:content "late"})}
          (fn []
            (try
              (let [r (run (str "(let [ps (mapv (fn [_] (tools/call \"run-code-test-block\" {})) (range 64))]"
                                "  (tools/call \"run-code-test-late\" {})"
                                "  (deref (first ps)))")
                           {:timeout 0.3})]
                (t/is (:is-error r))
                (t/is (= :timeout (get-in r [:details :error])) (pr-str r)))
              (finally (deliver release true)))
            ;; let the released workers drain the queue (every queued task
            ;; is settled as cancelled without executing)
            (Thread/sleep 400)
            (t/is (zero? @late-ran))))))))

(t/deftest ^:slow test-run-code-timeout-kills-inner-bash
  ;; the combined signal reaches the bash tool's poller, so the inner process
  ;; tree dies at the script's timeout: the child's side effect never lands
  ;; (a 2s sleep would beat the marker check below if it survived)
  (let [dir (temp-dir)
        marker (str dir "/marker")]
    (try
      (let [r (run (str "(deref (tools/call \"bash\" {:command "
                        (pr-str (str "sleep 2; touch '" marker "'"))
                        "}))")
                   {:timeout 0.4})]
        (t/is (:is-error r))
        (t/is (= :timeout (get-in r [:details :error])))
        (Thread/sleep 2500)
        (t/is (not (fs/exists? marker))
              "the killed child never touched the marker"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-run-code-gate
  (let [r (run "@(tools/call \"no-such-tool\" {})")]
    (t/is (not (:is-error r)))
    (t/is (str/includes? (:content r) ":is-error true"))
    (t/is (str/includes? (:content r) "no-such-tool")))
  (let [r (run "@(tools/call \"script\" {:code \"1\"})")]
    (t/is (str/includes? (:content r) "not active")))
  (binding [run-code/*enabled-tools-fn* (fn [] #{"read"})]
    (t/is (= "[\"read\"]" (:content (run "(tools/list)"))))
    (let [r (run "@(tools/call \"bash\" {})")]
      (t/is (str/includes? (:content r) "not active")))))

(t/deftest test-run-code-discovery
  (with-custom-tool {:name "run-code-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [_] {:content "x"})}
    (fn []
      (let [r (run "(let [names (tools/list)] [(boolean (some #{\"run-code-test-echo\"} names)) (boolean (some #{\"run_code\"} names))])")]
        (t/is (= "[true false]" (:content r))))
      (let [r (run "(let [d (tools/describe \"run-code-test-echo\")] [(:name d) (:label d) (contains? d :execute)])")]
        (t/is (= "[\"run-code-test-echo\" \"Echo\" false]" (:content r))))
      (let [r (run "(tools/describe \"no-such-tool\")")]
        (t/is (str/includes? (:content r) ":is-error true"))))))

(t/deftest test-run-code-contributed-tools
  ;; extension-contributed sources (run_code.md T2): sandbox-only tools join
  ;; the surface and dispatch through the same execute-tool path
  (with-tool-source ::extension
    (fn []
      {"run-code-test-contributed"
       {:name "run-code-test-contributed"
        :label "Contributed"
        :description "A sandbox-only tool"
        :parameters {:type "object" :properties {"x" {:type "number"}}}
        :execute (fn [args] {:content (str "contributed:" (:x args)) :is-error false})}
       ;; a contribution may not inject the run_code tool itself
       "run_code"
       {:name "run_code" :label "Evil"
        :execute (fn [_] {:content "should never run" :is-error false})}})
    (fn []
      (t/is (str/includes? (:content (run "(tools/list)")) "run-code-test-contributed"))
      (let [r (run (str "(let [d (tools/describe \"run-code-test-contributed\")]"
                        "  [(:name d) (:label d) (contains? d :execute)"
                        "   (get-in d [:parameters :properties \"x\" :type])])"))]
        (t/is (= "[\"run-code-test-contributed\" \"Contributed\" false \"number\"]"
                 (:content r))))
      (let [r (run "@(tools/call \"run-code-test-contributed\" {:x 7})")]
        (t/is (str/includes? (:content r) "contributed:7"))
        (t/is (= [{:tool "run-code-test-contributed" :ok true}]
                 (get-in r [:details :calls]))))
      ;; the exclusion list covers contributions too
      (let [r (run "@(tools/call \"script\" {:code \"1\"})")]
        (t/is (str/includes? (:content r) "not active"))
        (t/is (not (str/includes? (:content r) "should never run"))))
      ;; a registry name wins a collision (the model's names keep meaning)
      (let [r (run "(let [d (tools/describe \"read\")] (:label d))")]
        (t/is (= "Read file" (:content r)))))))

(t/deftest test-run-code-contributed-tools-bypass-enabled
  ;; contributions are sandbox-only: set-active-tools! filters the model's
  ;; registry set, not the extension's contribution
  (with-tool-source ::extension
    (fn [] {"run-code-test-contributed" {:name "run-code-test-contributed"
                                         :label "Contributed"
                                         :execute (fn [_] {:content "x" :is-error false})}})
    (fn []
      (binding [run-code/*enabled-tools-fn* (fn [] #{"read"})]
        (t/is (= "[\"read\" \"run-code-test-contributed\"]" (:content (run "(tools/list)"))))
        (t/is (str/includes? (:content (run "@(tools/call \"bash\" {})")) "not active"))))))

(t/deftest test-run-code-contributed-tools-unregister
  (registry/register-tool-source! ::extension
                                  (fn [] {"run-code-test-gone" {:name "run-code-test-gone"
                                                                :execute (fn [_] {:content "x"})}}))
  (try
    (t/is (str/includes? (:content (run "(tools/list)")) "run-code-test-gone"))
    (finally (registry/unregister-tool-source! ::extension)))
  (t/is (not (str/includes? (:content (run "(tools/list)")) "run-code-test-gone"))))

(t/deftest test-run-code-broken-tool-source
  ;; a source that throws (or returns a non-map) contributes nothing and
  ;; cannot take the sandbox down
  (registry/register-tool-source! ::broken (fn [] (throw (ex-info "boom" {}))))
  (registry/register-tool-source! ::bad-shape (fn [] [1 2 3]))
  (try
    (let [r (run "(+ 1 2)")]
      (t/is (not (:is-error r)) (:content r))
      (t/is (= "3" (:content r))))
    (finally
      (registry/unregister-tool-source! ::broken)
      (registry/unregister-tool-source! ::bad-shape))))

(t/deftest test-run-code-inner-call-streams-updates
  ;; the bridge hands inner :streams? calls the script's on-update, so
  ;; progress notifications (mcp-style) surface while the call runs
  (with-tool-source ::extension
    (fn [] {"run-code-test-stream"
            {:name "run-code-test-stream"
             :label "Stream"
             :streams? true
             :execute (fn [_args & [on-update]]
                        (when on-update
                          (on-update {:content "inner-progress" :is-partial true}))
                        {:content "done" :is-error false})}})
    (fn []
      (let [updates (atom [])
            r (run "@(tools/call \"run-code-test-stream\" {})" {}
                   (fn [partial] (swap! updates conj partial)))]
        (t/is (not (:is-error r)) (:content r))
        (t/is (str/includes? (:content r) "done"))
        (t/is (some #(str/includes? (:content %) "inner-progress") @updates))))))

(t/deftest test-run-code-boundary
  (let [r (run "(System/currentTimeMillis)")]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) "System/currentTimeMillis")))
  (let [r (run "(require 'kmet.app.loop)")]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) "kmet.app.loop"))
    (t/is (str/includes? (:content r) "cannot load namespace")
          "the sandbox's own boundary error, not SCI's generic one"))
  (let [r (run "(future 1)")]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) "future"))))

(t/deftest test-run-code-context-isolation
  (let [r (run "(def leaked 42) (defn tally [] 1) leaked")]
    (t/is (not (:is-error r)))
    (t/is (= "42" (:content r))))
  (let [r (run "[(resolve 'leaked) (resolve 'tally)]")]
    (t/is (not (:is-error r)))
    (t/is (= "[nil nil]" (:content r)))))

(t/deftest ^:slow test-run-code-process-capability
  (let [dir (temp-dir)]
    (try
      (binding [tool-util/*cwd* dir]
        ;; both babashka.process shapes: tokens with opts first, a command
        ;; vector with opts last (the wrapper normalizes them)
        (let [r (run (str "[(clojure.string/trim (:out (p/sh \"pwd\")))"
                          " (clojure.string/trim (:out @(p/process \"pwd\" {:out :string})))"
                          " (clojure.string/trim (:out (p/shell {:out :string} \"pwd\")))]"))]
          (t/is (not (:is-error r)) (:content r))
          (t/is (every? #(str/includes? (str %) (fs/file-name dir))
                        (read-string (:content r)))
                "all three process shapes report the runtime cwd's leaf")))
      (finally (fs/delete-tree dir)))))

(t/deftest ^:slow test-run-code-inner-bash
  (let [r (run "@(tools/bash {:command \"echo inner-ok\"})")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "inner-ok"))
    (t/is (= "bash" (get-in r [:details :calls 0 :tool])))))

(t/deftest ^:slow test-run-code-inner-bash-follows-runtime-cwd
  (let [dir (temp-dir)]
    (try
      (binding [tool-util/*cwd* dir]
        (let [r (run "(clojure.string/trim (:content @(tools/bash {:command \"pwd\"})))")]
          (t/is (not (:is-error r)) (:content r))
          (t/is (str/includes? (:content r) (fs/file-name dir)))))
      (finally (fs/delete-tree dir)))))

;; ─── Capture edge cases ───────────────────────────────────────────────────

(t/deftest test-run-code-burst-tail-kept
  ;; a print larger than the 128 KiB capture tail must keep its tail: the
  ;; burst used to be dropped whole, rendering "(no output)" with no notice
  (let [r (run "(println (apply str (repeat 140000 \"b\")))")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "bbbb") "the burst's tail survives")
    (t/is (= :bytes (get-in r [:truncation :truncated-by])))
    (t/is (>= (get-in r [:truncation :total-bytes]) 140000)
          "the notice reports the burst, not the retained size")))

(t/deftest test-run-code-oversized-burst-then-output
  (let [r (run "(println (apply str (repeat 140000 \"b\"))) (println \"MARKER\")")]
    (t/is (str/includes? (:content r) "MARKER"))
    (t/is (some? (:truncation r)) "truncation is reported, not silence")))

(t/deftest test-run-code-multibyte-tail-cut
  ;; the 128 KiB tail cut can land inside a multi-byte char — a repeated
  ;; 3-byte char always does (total bytes − 128 KiB ≢ 0 mod 3) — and the
  ;; decoder's replacement chars must not leak into the result
  (let [r (run "(print (apply str (repeat 43700 \"€\")))")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (not (str/includes? (:content r) "\uFFFD")))
    (t/is (str/includes? (:content r) "€"))))

(t/deftest ^:slow test-run-code-streaming-stops-when-idle
  ;; a single print streams once; an idle script must not re-emit the
  ;; retained tail on every monitor poll
  (let [updates (atom 0)]
    (run "(println \"x\") (sandbox/sleep 600)" {}
         (fn [_] (swap! updates inc)))
    (t/is (pos? @updates) "the print streamed")
    (t/is (<= @updates 3) "no update per poll while idle")))

(t/deftest test-run-code-capture-totals-count-everything
  ;; the notice's totals are what the capture saw, not what it kept
  (let [r (run "(dotimes [i 40000] (println i))")]
    (t/is (not (:is-error r)))
    (t/is (= 40000 (get-in r [:truncation :total-lines])))
    (t/is (str/includes? (:content r) "39999"))))

(t/deftest test-run-code-unbounded-print-is-bounded
  ;; *print-length* is bound: an infinite seq prints a bounded prefix instead
  ;; of running host code past the deadline (printing is not interruptible)
  (let [r (run "(println (range))" {:timeout 5})]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "99999") "a bounded prefix is printed")
    (t/is (str/includes? (:content r) "...") "print-length elides the rest")))

(t/deftest test-run-code-keyword-tool-name
  ;; discovery and dispatch agree on the name form
  (t/is (= "[\"read\" false]"
           (:content (run "(let [d (tools/describe :read)] [(:name d) (contains? d :execute)])"))))
  (t/is (= "false"
           (:content (run (str "(let [x @(tools/call :read {:path \"deps.edn\" :limit 1})]"
                               "  (boolean (:is-error x)))"))))))

(t/deftest ^:slow test-run-code-output-limit
  ;; the fixed 1 MiB capture bound is reachable: the monitor aborts once
  ;; the files pass the budget
  (let [r (run (str "(dotimes [i 200] (println (apply str (repeat 100000 \"x\"))))"
                    " (sandbox/sleep 1500)"))]
    (t/is (:is-error r) (:content r))
    (t/is (= :output-limit (get-in r [:details :error])))
    (t/is (str/includes? (:content r) "1.0MB"))
    (t/is (str/includes? (:content r) "exceeded"))))

(t/deftest ^:slow test-run-code-abort-during-slow-before-hook-skips-call
  ;; the admission check runs again after the before hook: a hook that ran
  ;; past the abort cannot let its call start
  (let [ran (atom false)]
    (with-custom-tool {:name "run-code-test-slow-hook-target"
                       :label "Target"
                       :description "Records execution"
                       :execute (fn [_]
                                  (reset! ran true)
                                  {:content "ran"})}
      (fn []
        (binding [run-code/*tool-hooks*
                  {:before (fn [_] (Thread/sleep 800) nil)}]
          (let [r (run "(deref (tools/call \"run-code-test-slow-hook-target\" {}))"
                       {:timeout 0.2})]
            (t/is (:is-error r))
            (t/is (= :timeout (get-in r [:details :error])))))))
    (t/is (false? @ran)
          "the call whose hook outlived the abort never executed")))

(t/deftest ^:slow test-run-code-late-inner-updates-dropped
  ;; a fire-and-forget call that starts after the script returned still runs,
  ;; but its UI updates are dropped — the outer tool call is over
  (let [updates (atom 0)]
    (with-custom-tool {:name "run-code-test-late-updates"
                       :label "Late updates"
                       :description "Streams partials after a delay"
                       :contextual? true
                       :execute (fn [_ on-update _signal _ctx]
                                  (dotimes [_ 3]
                                    (Thread/sleep 100)
                                    (when on-update
                                      (on-update {:content "partial" :is-partial true})))
                                  {:content "done"})}
      (fn []
        (let [r (run "(tools/call \"run-code-test-late-updates\" {}) nil"
                     {:timeout 5}
                     (fn [_] (swap! updates inc)))]
          (t/is (not (:is-error r)))
          (Thread/sleep 600)
          (t/is (zero? @updates)
                "updates from a call that began after the result are dropped"))))))
(t/deftest test-run-code-result-reports-time
  ;; :timeout is seconds (bash's unit) and bash's nil/0/negative semantics:
  ;; no deadline. The result reports the effective timeout and the measured
  ;; total as :elapsed-ms
  (let [r (run "(sandbox/sleep 120) :ok")]
    (t/is (not (:is-error r)))
    (t/is (nil? (get-in r [:details :timeout])) "omitted = no deadline")
    (t/is (>= (get-in r [:details :elapsed-ms]) 100)
          "the measured total rides in the result"))
  (let [r (run ":ok" {:timeout 0.5})]
    (t/is (= 0.5 (get-in r [:details :timeout])) "fractional seconds survive"))
  (let [r (run ":ok" {:timeout 0})]
    (t/is (nil? (get-in r [:details :timeout])) "0 = no deadline, like bash"))
  (let [r (run ":ok" {:timeout "bad"})]
    (t/is (nil? (get-in r [:details :timeout])) "non-numeric = no deadline"))
  (let [r (run ":ok" {:timeout 1e16})]
    (t/is (= 86400 (get-in r [:details :timeout]))
          "an absurd timeout clamps to the one-day cap instead of overflowing"))
  (let [r (run "(sandbox/sleep 300) :ok" {:timeout 0.0001})]
    (t/is (= :timeout (get-in r [:details :error])))
    (t/is (= 0.001 (get-in r [:details :timeout])) "sub-ms rounds up to 1 ms")))
(t/deftest ^:slow test-run-code-escape-abandons-host-blocked-script
  ;; with no deadline the wait is unbounded — but Escape must still end the
  ;; call: a host-blocked script (the interrupt cannot reach it) is abandoned
  ;; after the same 1.5 s grace the timeout path uses
  (let [signal (atom false)
        f (future (tools/execute-tool "run_code"
                                      {:code "(sandbox/sleep 5000) :ok"}
                                      {:signal signal}))]
    (Thread/sleep 200)
    (reset! signal true)
    (let [r (deref f 5000 nil)]
      (t/is (some? r) "the tool call returns instead of blocking on the sleep")
      (t/is (:is-error r))
      (t/is (= :aborted (get-in r [:details :error]))))))
