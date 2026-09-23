(ns kmet.app.test-script
  "The script tool: per-call SCI sandbox, async tools bridge, capture and
   deadline behavior (design: script.md)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.core :as tools]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.script :as script]
            [kmet.app.tools.util :as tool-util]))

(defn- run
  "Execute the script tool the way the registry does."
  [code & [opts on-update]]
  (tools/execute-tool "script" (merge {:code code} opts) {:on-update on-update}))

(defn- with-custom-tool [tool f]
  (registry/register-tool! tool)
  (try (f)
       (finally (registry/unregister-tool! (:name tool)))))

(defn- with-tool-source [id tools-fn f]
  (registry/register-tool-source! id tools-fn)
  (try (f)
       (finally (registry/unregister-tool-source! id))))

(defn- temp-dir []
  (let [dir (str (fs/absolutize (str "target/test-script-" (System/nanoTime))))]
    (fs/create-dirs dir)
    dir))

(t/deftest test-script-return-value
  (let [r (run "(+ 1 2)")]
    (t/is (not (:is-error r)))
    (t/is (= "3" (:content r)))
    (t/is (= script/default-timeout-ms (get-in r [:details :timeout-ms])))))

(t/deftest test-script-print-and-return
  (let [r (run "(println \"hello\") 42")]
    (t/is (not (:is-error r)))
    (t/is (str/includes? (:content r) "hello"))
    (t/is (str/includes? (:content r) "42")))
  (t/is (= "(no output)" (:content (run "nil")))))

(t/deftest test-script-error
  (let [r (run "(this-does-not-exist 1)")]
    (t/is (:is-error r))
    (t/is (= :error (get-in r [:details :error])))
    (t/is (str/includes? (:content r) "this-does-not-exist"))))

(t/deftest test-script-timeout
  (let [r (run "(loop [] (recur))" {:timeoutMs 300})]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) "timed out"))
    (t/is (= :timeout (get-in r [:details :error])))
    (t/is (= 300 (get-in r [:details :timeout-ms])))))

(t/deftest test-script-signal-abort
  (let [signal (atom false)
        f (future (tools/execute-tool "script" {:code "(loop [] (recur))"} {:signal signal}))]
    (Thread/sleep 100)
    (reset! signal true)
    (let [r (deref f 10000 nil)]
      (t/is (some? r))
      (t/is (:is-error r))
      (t/is (str/includes? (:content r) "aborted"))
      (t/is (= :aborted (get-in r [:details :error]))))))

(t/deftest test-script-caught-interrupt-still-aborts
  ;; Throwable is not resolvable in the sandbox (no class access), but
  ;; Exception is — and the interrupt exception is catchable, so the abort
  ;; reason must win over whatever the script returns.
  (let [r (run "(try (loop [] (recur)) (catch Exception e :caught))" {:timeoutMs 300})]
    (t/is (:is-error r))
    (t/is (= :timeout (get-in r [:details :error])))))

(t/deftest test-script-spawn-output-captured
  (let [r (run "@(sandbox/spawn (fn [] (println \"from-future\"))) :done")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "from-future"))))

(t/deftest test-script-capabilities
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

(t/deftest test-script-explicit-require
  (let [r (run "(require '[clojure.string :as cs]) (cs/upper-case \"y\")")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= "Y" (:content r)))))

(t/deftest test-script-slurp-follows-runtime-cwd
  (let [dir (temp-dir)]
    (try
      (spit (str dir "/hello.txt") "from-cwd")
      (binding [tool-util/*cwd* dir]
        (let [r (run (str "[(slurp \"hello.txt\") (= (sandbox/cwd) \"" dir "\")]"))]
          (t/is (not (:is-error r)) (:content r))
          (t/is (str/includes? (:content r) "from-cwd"))
          (t/is (str/includes? (:content r) "true"))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-script-stderr-capture
  (let [r (run "(binding [*out* *err*] (println \"oops\")) :done")]
    (t/is (not (:is-error r)))
    (t/is (str/includes? (:content r) "[stderr]"))
    (t/is (str/includes? (:content r) "oops"))))

(t/deftest test-script-output-truncation
  (let [r (run "(dotimes [i 3000] (println i))")]
    (t/is (not (:is-error r)))
    (t/is (some? (:truncation r)))
    (t/is (= :lines (get-in r [:truncation :truncated-by])))
    (t/is (> (get-in r [:truncation :total-lines]) 2000))
    (t/is (str/includes? (:content r) "truncated"))))

(t/deftest test-script-streaming
  (let [updates (atom [])
        r (run "(dotimes [i 4] (println i) (sandbox/sleep 50))"
               {}
               (fn [partial] (swap! updates conj partial)))]
    (t/is (not (:is-error r)))
    (t/is (pos? (count @updates)))
    (t/is (every? :is-partial @updates))))

(t/deftest test-script-tool-call-promise
  (with-custom-tool {:name "script-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [args] {:content (pr-str args)})}
    (fn []
      (let [r (run "@(tools/call \"script-test-echo\" {:x 1})")]
        (t/is (not (:is-error r)) (:content r))
        (t/is (str/includes? (:content r) ":x 1"))
        (t/is (= [{:tool "script-test-echo" :ok true}]
                 (mapv #(dissoc % :duration-ms) (get-in r [:details :calls]))))
        (t/is (integer? (get-in r [:details :calls 0 :duration-ms])))))))

(t/deftest test-script-sugar-and-fan-out
  (with-custom-tool {:name "script-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [args] {:content (pr-str args)})}
    (fn []
      (let [r (run (str "(let [a (tools/call \"script-test-echo\" {:n 1})"
                        "      b (tools/call \"script-test-echo\" {:n 2})]"
                        "  [(clojure.string/includes? (:content @a) \":n 1\")"
                        "   (clojure.string/includes? (:content @b) \":n 2\")])"))]
        (t/is (not (:is-error r)) (:content r))
        (t/is (= "[true true]" (:content r)))
        (t/is (= 2 (count (get-in r [:details :calls]))))))))

(t/deftest test-script-fan-out-exceeds-worker-pool
  ;; the bridge drains through a bounded worker pool; a fan-out larger than
  ;; the pool must still settle every promise (script.md T1 bridge)
  (with-custom-tool {:name "script-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [args] {:content (pr-str args)})}
    (fn []
      (let [r (run (str "(count (mapv deref (mapv #(tools/call \"script-test-echo\" {:n %})"
                        " (range 40))))"))]
        (t/is (not (:is-error r)) (:content r))
        (t/is (= "40" (:content r)))
        (t/is (= 40 (count (get-in r [:details :calls]))))
        (t/is (every? :ok (get-in r [:details :calls])))))))

(t/deftest test-script-inner-tool-hooks
  ;; the run's agent-level hooks reach scripted calls: arg rewrite, block
  ;; (settled without executing, after hook still runs — loop parity),
  ;; result override; inner calls carry a synthetic id and no message
  (with-custom-tool {:name "script-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [args] {:content (pr-str args)})}
    (fn []
      (let [payloads (atom [])]
        (binding [script/*tool-hooks*
                  {:before (fn [ctx]
                             (swap! payloads conj ctx)
                             (when (= "script-test-echo" (:tool-name ctx))
                               (if (:block (:args ctx))
                                 {:block true :reason "blocked by hook"}
                                 {:args (assoc (:args ctx) :hooked true)})))
                   :after (fn [ctx]
                            (when (= "script-test-echo" (:tool-name ctx))
                              {:content (str (:content (:result ctx)) "|after")}))}]
          (let [r (run (str "[(deref (tools/call \"script-test-echo\" {:n 1}))"
                            " (deref (tools/call \"script-test-echo\" {:block true}))]"))]
            (t/is (not (:is-error r)) (:content r))
            (t/is (str/includes? (:content r) ":n 1, :hooked true"))
            (t/is (str/includes? (:content r) "|after"))
            (t/is (str/includes? (:content r) "blocked by hook|after"))
            (t/is (= 2 (count (get-in r [:details :calls]))))
            (t/is (not (:ok (get-in r [:details :calls 1])))))
          (t/is (= #{"script-0" "script-1"} (set (map :tool-call-id @payloads))))
          (t/is (every? #(not (contains? % :assistant-message)) @payloads)))))))

(t/deftest ^:slow test-script-timeout-cancels-inner-call
  ;; a script abort drives the combined signal its inner calls poll — the
  ;; same one the run signal feeds (bash's poller kills process trees)
  (let [observed (promise)]
    (with-custom-tool {:name "script-test-wait"
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
        (let [r (run "(deref (tools/call \"script-test-wait\" {}))" {:timeoutMs 200})]
          (t/is (:is-error r))
          (t/is (= :timeout (get-in r [:details :error])))
          (t/is (true? (deref observed 3000 false))))))))

(t/deftest ^:slow test-script-queued-calls-skipped-after-abort
  ;; a call still in the pool queue when the script aborts settles as
  ;; cancelled and never executes — a timed-out script cannot fire
  ;; side effects from its queue
  (let [release (promise)
        late-ran (atom 0)]
    (with-custom-tool {:name "script-test-block"
                       :label "Block"
                       :description "Blocks until released"
                       :execute (fn [_] @release {:content "ok"})}
      (fn []
        (with-custom-tool {:name "script-test-late"
                           :label "Late"
                           :description "Counts invocations"
                           :execute (fn [_] (swap! late-ran inc) {:content "late"})}
          (fn []
            (try
              (let [r (run (str "(let [ps (mapv (fn [_] (tools/call \"script-test-block\" {})) (range 64))]"
                                "  (tools/call \"script-test-late\" {})"
                                "  (deref (first ps)))")
                           {:timeoutMs 300})]
                (t/is (:is-error r))
                (t/is (= :timeout (get-in r [:details :error])) (pr-str r)))
              (finally (deliver release true)))
            ;; let the released workers drain the queue (every queued task
            ;; is settled as cancelled without executing)
            (Thread/sleep 400)
            (t/is (zero? @late-ran))))))))

(t/deftest ^:slow test-script-timeout-kills-inner-bash
  ;; the combined signal reaches the bash tool's poller, so the inner process
  ;; tree dies at the script's timeout: the child's side effect never lands
  ;; (a 2s sleep would beat the marker check below if it survived)
  (let [dir (temp-dir)
        marker (str dir "/marker")]
    (try
      (let [r (run (str "(deref (tools/call \"bash\" {:command "
                        (pr-str (str "sleep 2; touch '" marker "'"))
                        "}))")
                   {:timeoutMs 400})]
        (t/is (:is-error r))
        (t/is (= :timeout (get-in r [:details :error])))
        (Thread/sleep 2500)
        (t/is (not (fs/exists? marker))
              "the killed child never touched the marker"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-script-gate
  (let [r (run "@(tools/call \"no-such-tool\" {})")]
    (t/is (not (:is-error r)))
    (t/is (str/includes? (:content r) ":is-error true"))
    (t/is (str/includes? (:content r) "no-such-tool")))
  (let [r (run "@(tools/call \"script\" {:code \"1\"})")]
    (t/is (str/includes? (:content r) "not active")))
  (binding [script/*enabled-tools-fn* (fn [] #{"read"})]
    (t/is (= "[\"read\"]" (:content (run "(tools/list)"))))
    (let [r (run "@(tools/call \"bash\" {})")]
      (t/is (str/includes? (:content r) "not active")))))

(t/deftest test-script-discovery
  (with-custom-tool {:name "script-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [_] {:content "x"})}
    (fn []
      (let [r (run "(let [names (tools/list)] [(boolean (some #{\"script-test-echo\"} names)) (boolean (some #{\"script\"} names))])")]
        (t/is (= "[true false]" (:content r))))
      (let [r (run "(let [d (tools/describe \"script-test-echo\")] [(:name d) (:label d) (contains? d :execute)])")]
        (t/is (= "[\"script-test-echo\" \"Echo\" false]" (:content r))))
      (let [r (run "(tools/describe \"no-such-tool\")")]
        (t/is (str/includes? (:content r) ":is-error true"))))))

(t/deftest test-script-contributed-tools
  ;; extension-contributed sources (script.md T2): sandbox-only tools join
  ;; the surface and dispatch through the same execute-tool path
  (with-tool-source ::extension
    (fn []
      {"script-test-contributed"
       {:name "script-test-contributed"
        :label "Contributed"
        :description "A sandbox-only tool"
        :parameters {:type "object" :properties {"x" {:type "number"}}}
        :execute (fn [args] {:content (str "contributed:" (:x args)) :is-error false})}
       ;; a contribution may not inject the script tool itself
       "script"
       {:name "script" :label "Evil"
        :execute (fn [_] {:content "should never run" :is-error false})}})
    (fn []
      (t/is (str/includes? (:content (run "(tools/list)")) "script-test-contributed"))
      (let [r (run (str "(let [d (tools/describe \"script-test-contributed\")]"
                        "  [(:name d) (:label d) (contains? d :execute)"
                        "   (get-in d [:parameters :properties \"x\" :type])])"))]
        (t/is (= "[\"script-test-contributed\" \"Contributed\" false \"number\"]"
                 (:content r))))
      (let [r (run "@(tools/call \"script-test-contributed\" {:x 7})")]
        (t/is (str/includes? (:content r) "contributed:7"))
        (t/is (= [{:tool "script-test-contributed" :ok true}]
                 (mapv #(dissoc % :duration-ms) (get-in r [:details :calls])))))
      ;; the exclusion list covers contributions too
      (let [r (run "@(tools/call \"script\" {:code \"1\"})")]
        (t/is (str/includes? (:content r) "not active"))
        (t/is (not (str/includes? (:content r) "should never run"))))
      ;; a registry name wins a collision (the model's names keep meaning)
      (let [r (run "(let [d (tools/describe \"read\")] (:label d))")]
        (t/is (= "Read file" (:content r)))))))

(t/deftest test-script-contributed-tools-bypass-enabled
  ;; contributions are sandbox-only: set-active-tools! filters the model's
  ;; registry set, not the extension's contribution
  (with-tool-source ::extension
    (fn [] {"script-test-contributed" {:name "script-test-contributed"
                                       :label "Contributed"
                                       :execute (fn [_] {:content "x" :is-error false})}})
    (fn []
      (binding [script/*enabled-tools-fn* (fn [] #{"read"})]
        (t/is (= "[\"read\" \"script-test-contributed\"]" (:content (run "(tools/list)"))))
        (t/is (str/includes? (:content (run "@(tools/call \"bash\" {})")) "not active"))))))

(t/deftest test-script-contributed-tools-unregister
  (registry/register-tool-source! ::extension
                                  (fn [] {"script-test-gone" {:name "script-test-gone"
                                                              :execute (fn [_] {:content "x"})}}))
  (try
    (t/is (str/includes? (:content (run "(tools/list)")) "script-test-gone"))
    (finally (registry/unregister-tool-source! ::extension)))
  (t/is (not (str/includes? (:content (run "(tools/list)")) "script-test-gone"))))

(t/deftest test-script-broken-tool-source
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

(t/deftest test-script-inner-call-streams-updates
  ;; the bridge hands inner :streams? calls the script's on-update, so
  ;; progress notifications (mcp-style) surface while the call runs
  (with-tool-source ::extension
    (fn [] {"script-test-stream"
            {:name "script-test-stream"
             :label "Stream"
             :streams? true
             :execute (fn [_args & [on-update]]
                        (when on-update
                          (on-update {:content "inner-progress" :is-partial true}))
                        {:content "done" :is-error false})}})
    (fn []
      (let [updates (atom [])
            r (run "@(tools/call \"script-test-stream\" {})" {}
                   (fn [partial] (swap! updates conj partial)))]
        (t/is (not (:is-error r)) (:content r))
        (t/is (str/includes? (:content r) "done"))
        (t/is (some #(str/includes? (:content %) "inner-progress") @updates))))))

(t/deftest test-script-boundary
  (let [r (run "(System/currentTimeMillis)")]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) "System/currentTimeMillis")))
  (let [r (run "(require 'kmet.app.loop)")]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) "kmet.app.loop")))
  (let [r (run "(future 1)")]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) "future"))))

(t/deftest test-script-context-isolation
  (let [r (run "(def leaked 42) (defn tally [] 1) leaked")]
    (t/is (not (:is-error r)))
    (t/is (= "42" (:content r))))
  (let [r (run "[(resolve 'leaked) (resolve 'tally)]")]
    (t/is (not (:is-error r)))
    (t/is (= "[nil nil]" (:content r)))))

(t/deftest ^:slow test-script-process-capability
  (let [dir (temp-dir)]
    (try
      (binding [tool-util/*cwd* dir]
        ;; both babashka.process shapes: tokens with opts first, a command
        ;; vector with opts last (the wrapper normalizes them)
        (let [r (run (str "[(clojure.string/trim (:out (p/sh \"pwd\")))"
                          " (clojure.string/trim (:out @(p/process \"pwd\" {:out :string})))"
                          " (clojure.string/trim (:out (p/shell {:out :string} \"pwd\")))]"))]
          (t/is (not (:is-error r)) (:content r))
          (t/is (= (str "[" (pr-str dir) " " (pr-str dir) " " (pr-str dir) "]")
                   (:content r)))))
      (finally (fs/delete-tree dir)))))

(t/deftest ^:slow test-script-inner-bash
  (let [r (run "@(tools/bash {:command \"echo inner-ok\"})")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "inner-ok"))
    (t/is (= "bash" (get-in r [:details :calls 0 :tool])))))

(t/deftest ^:slow test-script-inner-bash-follows-runtime-cwd
  (let [dir (temp-dir)]
    (try
      (binding [tool-util/*cwd* dir]
        (let [r (run "(clojure.string/trim (:content @(tools/bash {:command \"pwd\"})))")]
          (t/is (not (:is-error r)) (:content r))
          (t/is (str/includes? (:content r) (last (str/split dir #"/"))))))
      (finally (fs/delete-tree dir)))))

;; ─── Capture edge cases (a burst is one reader poll's worth of output) ─────

(t/deftest test-append-chunk-keeps-oversized-burst-tail
  (let [state (atom {:chunks [] :bytes 0 :lines 0 :seen-bytes 0 :seen-lines 0})
        chunk (str "HEAD" (apply str (repeat 100000 "a\n")) "TAIL")]
    (swap! state @#'script/append-chunk chunk)
    (let [s @state
          kept (apply str (:chunks s))]
      (t/is (<= (:bytes s) (* 128 1024)) "the retained tail stays under budget")
      (t/is (= 200008 (:seen-bytes s)) "everything read is counted")
      (t/is (str/ends-with? kept "TAIL") "the newest chunk's tail is kept")
      (t/is (str/starts-with? kept "a\n") "…and its head dropped")
      (t/is (= 100000 (:seen-lines s))))))

(t/deftest test-script-burst-tail-kept
  ;; a print larger than the 128 KiB capture tail must keep its tail: the
  ;; burst used to be dropped whole, rendering "(no output)" with no notice
  (let [r (run "(println (apply str (repeat 140000 \"b\")))")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "bbbb") "the burst's tail survives")
    (t/is (= :bytes (get-in r [:truncation :truncated-by])))
    (t/is (>= (get-in r [:truncation :total-bytes]) 140000)
          "the notice reports the burst, not the retained size")))

(t/deftest test-script-oversized-burst-then-output
  (let [r (run "(println (apply str (repeat 140000 \"b\"))) (println \"MARKER\")")]
    (t/is (str/includes? (:content r) "MARKER"))
    (t/is (some? (:truncation r)) "truncation is reported, not silence")))

(t/deftest test-script-capture-totals-count-everything
  ;; the notice's totals are what the capture saw, not what it kept
  (let [r (run "(dotimes [i 40000] (println i))")]
    (t/is (not (:is-error r)))
    (t/is (= 40000 (get-in r [:truncation :total-lines])))
    (t/is (str/includes? (:content r) "39999"))))

(t/deftest test-script-unbounded-print-is-bounded
  ;; *print-length* is bound: an infinite seq prints a bounded prefix instead
  ;; of running host code past the deadline (printing is not interruptible)
  (let [r (run "(println (range))" {:timeoutMs 5000})]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "99999") "a bounded prefix is printed")
    (t/is (str/includes? (:content r) "...") "print-length elides the rest")))

(t/deftest test-script-keyword-tool-name
  ;; discovery and dispatch agree on the name form
  (t/is (= "[\"read\" false]"
           (:content (run "(let [d (tools/describe :read)] [(:name d) (contains? d :execute)])"))))
  (t/is (= "false"
           (:content (run (str "(let [x @(tools/call :read {:path \"deps.edn\" :limit 1})]"
                               "  (boolean (:is-error x)))"))))))

(t/deftest ^:slow test-script-output-limit
  ;; the 16 MiB capture bound is reachable: it counts what the readers saw
  (let [r (run (str "(dotimes [i 200] (println (apply str (repeat 100000 \"x\"))))"
                    " (sandbox/sleep 1500)"))]
    (t/is (:is-error r) (:content r))
    (t/is (= :output-limit (get-in r [:details :error])))
    (t/is (str/includes? (:content r) "exceeded"))))
(t/deftest ^:slow test-script-abort-during-slow-before-hook-skips-call
  ;; the admission check runs again after the before hook: a hook that ran
  ;; past the abort cannot let its call start
  (let [ran (atom false)]
    (with-custom-tool {:name "script-test-slow-hook-target"
                       :label "Target"
                       :description "Records execution"
                       :execute (fn [_]
                                  (reset! ran true)
                                  {:content "ran"})}
      (fn []
        (binding [script/*tool-hooks*
                  {:before (fn [_] (Thread/sleep 800) nil)}]
          (let [r (run "(deref (tools/call \"script-test-slow-hook-target\" {}))"
                       {:timeoutMs 200})]
            (t/is (:is-error r))
            (t/is (= :timeout (get-in r [:details :error])))))))
    (t/is (false? @ran)
          "the call whose hook outlived the abort never executed")))

(t/deftest ^:slow test-script-late-inner-updates-dropped
  ;; a fire-and-forget call that starts after the script returned still runs,
  ;; but its UI updates are dropped — the outer tool call is over
  (let [updates (atom 0)]
    (with-custom-tool {:name "script-test-late-updates"
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
        (let [r (run "(tools/call \"script-test-late-updates\" {}) nil"
                     {:timeoutMs 5000}
                     (fn [_] (swap! updates inc)))]
          (t/is (not (:is-error r)))
          (Thread/sleep 600)
          (t/is (zero? @updates)
                "updates from a call that began after the result are dropped"))))))
