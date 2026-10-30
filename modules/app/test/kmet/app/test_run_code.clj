(ns kmet.app.test-run-code
  "The run_code tool: per-call SCI sandbox with the full babashka namespace/
   class surface, curated output (emit, or a silent success's value), async
   tools bridge and deadline behavior (design: docs/architecture.md § Tools and
   the script sandbox)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.read :as read-tool]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.run-code :as run-code]
            [kmet.app.tools.util :as tool-util]
            [kmet.libs.host :as host]
            [kmet.test-utils :as tu]))

(def ^:private reg
  "The tool registry these tests drive: one handle for the namespace, since the
   tool layer takes its registry as the first argument and the run_code tool
   resolves its sibling tools through the same one."
  (tu/tool-registry))

(defn- run
  "Execute the run_code tool the way the registry does."
  [code & [opts on-update]]
  (tools/execute-tool reg "run_code" (merge {:code code} opts) {:on-update on-update}))

(defn- with-custom-tool [tool f]
  (tools/register-tool! reg tool)
  (try (f)
       (finally (tools/unregister-tool! reg (:name tool)))))

(defn- with-tool-source [id tools-fn f]
  (tools/register-tool-source! reg id tools-fn)
  (try (f)
       (finally (tools/unregister-tool-source! reg id))))

(defn- temp-dir []
  (let [dir (str (fs/absolutize (str "target/test-run-code-" (System/nanoTime))))]
    (fs/create-dirs dir)
    dir))

(defn- with-cwd-dir
  "Run F with a fresh temp dir as the runtime cwd (tool-util/*cwd*), then
   delete it."
  [f]
  (let [dir (temp-dir)]
    (try (binding [tool-util/*cwd* dir] (f dir))
         (finally (fs/delete-tree dir)))))

(t/deftest test-run-code-emit-image
  ;; images travel beside the emitted text as the result's :images, from a
  ;; supported file path or an MCP-shaped block
  (with-cwd-dir
    (fn [dir]
      (spit (str dir "/shot.gif") "GIF89a")
      (let [r (run "(emit :shot) (emit-image \"shot.gif\")")]
        (t/is (not (:is-error r)) (:content r))
        (t/is (= ":shot" (:content r)) "text and images stay separate channels")
        (t/is (= [{:data "R0lGODlh" :mime-type "image/gif"}] (:images r)))
        (t/is (= 1 (get-in r [:details :images]))))))
  (let [r (run "(sandbox/emit-image {:data \"R0lGODlh\" :mime-type \"image/gif\"})")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= "(no output)" (:content r)))
    (t/is (= [{:data "R0lGODlh" :mime-type "image/gif"}] (:images r))))
  (let [r (run "(emit-image {:type :image :data \"AA==\" :mime-type \"image/png\"})")]
    (t/is (= [{:data "AA==" :mime-type "image/png"}] (:images r))
          "an MCP image block passes through")))

(t/deftest test-run-code-emit-image-errors
  ;; a mistyped path fails the script loudly instead of attaching nothing
  (with-cwd-dir
    (fn [dir]
      (spit (str dir "/plain.txt") "not an image")
      (let [r (run "(emit-image \"missing.png\")")]
        (t/is (:is-error r))
        (t/is (str/includes? (:content r) "not a supported image file"))
        (t/is (nil? (:images r))))
      (let [r (run "(emit-image \"plain.txt\")")]
        (t/is (:is-error r) (:content r))
        (t/is (nil? (:images r))))))
  (let [r (run "(emit-image {:data 42})")]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) ":data <base64>")))
  (let [r (run "(emit-image {:data \"AA==\" :mime-type \"image/svg+xml\"})")]
    (t/is (:is-error r) (:content r))
    (t/is (str/includes? (:content r) "unsupported image type"))
    (t/is (nil? (:images r)))
    (t/is (str/includes? (:content r) "image/png") "the error lists the accepted types"))
  (t/is (:is-error (run "(emit-image 42)"))))

(t/deftest test-run-code-emit-image-file-cap
  ;; the raw-byte cap is checked before the read: a file too large to become a
  ;; 5 MiB base64 payload is refused without being loaded
  (with-cwd-dir
    (fn [dir]
      (fs/write-bytes (str dir "/huge.gif") (byte-array (inc (* 4 1024 1024))))
      (let [r (run "(emit-image \"huge.gif\")")]
        (t/is (:is-error r) (:content r))
        (t/is (str/includes? (:content r) "the cap is"))
        (t/is (nil? (:images r)))))))

(t/deftest test-run-code-emit-image-count-bound
  ;; at most 8 images per call: the ninth throw aborts the script
  (let [r (run "(dotimes [i 9] (emit-image {:data \"AA==\" :mime-type \"image/png\"}))")]
    (t/is (:is-error r) (:content r))
    (t/is (str/includes? (:content r) "at most 8 images"))))

(t/deftest test-run-code-emit-output
  (let [r (run "(emit (+ 1 2))")]
    (t/is (not (:is-error r)))
    (t/is (= "3" (:content r)))
    (t/is (nil? (get-in r [:details :timeout])) "no deadline unless asked")))

(t/deftest test-run-code-print-not-reported
  (t/is (= "(no output)" (:content (run "(println \"hello\")")))
        "println output is captured but not reported, and println returns nil")
  (t/is (= "(no output)" (:content (run "nil")))))

(t/deftest test-run-code-final-value-fallback
  ;; a silent success reports its final value; emit wins when used
  (t/is (= "42" (:content (run "(+ 40 2)"))))
  (t/is (= "plain" (:content (run "\"plain\"")))
        "a string value prints raw, like emit")
  (t/is (= "{:a 1}" (:content (run "{:a 1}"))))
  (t/is (= "42" (:content (run "(println \"hello\") 42")))
        "stdout stays out while the final value is reported")
  (t/is (= ":emitted" (:content (run "(emit :emitted) (+ 1 2)")))
        "emit wins over the final value")
  (let [r (run "(range)")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "...")
          "printing bounds a lazy value"))
  (let [r (run "(apply str (repeat 20000 \"x\"))")]
    (t/is (= :bytes (get-in r [:truncation :truncated-by]))
          "a large value meets the same output budget as emit")))

(t/deftest test-run-code-emit-compact-result
  (let [r (run "(sandbox/emit {:count 2 :items [1 2]})")]
    (t/is (not (:is-error r)))
    (t/is (= "{:count 2, :items [1 2]}" (str/trim (:content r))))
    (t/is (nil? (:truncation r))))
  (let [r (run "(emit {:count 2 :items [1 2]})")]
    (t/is (= "{:count 2, :items [1 2]}" (str/trim (:content r)))
          "bare emit works too"))
  (let [r (run "(emit \"a\") (emit 1) (emit :k)")]
    (t/is (= "a\n1\n:k" (:content r)) "emits join in order")))

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

(t/deftest ^:slow test-run-code-signal-abort
  (let [signal (atom false)
        f (future (tools/execute-tool reg "run_code" {:code "(loop [] (recur))"} {:signal signal}))]
    (Thread/sleep 100)
    (reset! signal true)
    (let [r (deref f 10000 nil)]
      (t/is (some? r))
      (t/is (:is-error r))
      (t/is (str/includes? (:content r) "aborted"))
      (t/is (= :aborted (get-in r [:details :error]))))))

(t/deftest ^:slow test-run-code-caught-interrupt-still-aborts
  ;; the interrupt exception is catchable, so the abort reason must win over
  ;; whatever the script returns (the full surface exposes Throwable too, but
  ;; a catch of Exception is the realistic case).
  (let [r (run "(try (loop [] (recur)) (catch Exception e :caught))" {:timeout 0.3})]
    (t/is (:is-error r))
    (t/is (= :timeout (get-in r [:details :error])))))

(t/deftest test-run-code-spawn-emit-reported
  (let [r (run "@(sandbox/spawn (fn [] (emit :from-future)))")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= ":from-future" (:content r)))))

(t/deftest test-run-code-emit-from-spawn
  (let [r (run "@(sandbox/spawn (fn [] (sandbox/emit :from-future)))")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= ":from-future" (:content r)))))

(t/deftest test-run-code-capabilities
  (let [r (run (str "(emit [(fs/exists? \"deps.edn\")"
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
                    "  (walk/postwalk identity [[1]])])"))]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= (str "[true true \"{\\\"a\\\":1}\" [2 3 4] 42 true true true "
                  "\"X\" #{1 2} 1 [[1]]]")
             (:content r)))))

(t/deftest test-run-code-explicit-require
  (let [r (run "(require '[clojure.string :as cs]) (emit (cs/upper-case \"y\"))")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= "Y" (:content r)))))

(t/deftest test-run-code-bundled-extension-libraries
  ;; the fixed bundled extension set (kmet.app.extensions.libs) is available
  ;; to scripts as to extension contexts — rewrite-clj on both hosts
  (t/is (= "(c d)"
           (:content (run (str "(require '[rewrite-clj.zip :as z])"
                               " (emit (z/root-string (z/replace (z/of-string \"(a b)\") '(c d))))")))))
  (t/is (= "(defn  g [x] x)"
           (:content (run "(require '[cljfmt.core :as f]) (emit (f/reformat-string \"(defn  g [x]x)\"))"))))
  (t/is (= "[1 2 3]"
           (:content (run "(require '[edamame.core :as e]) (emit (e/parse-string \"[1 2 3]\"))")))))

(t/deftest test-run-code-reload-is-noop
  ;; SCI consults the sandbox :load-fn even for a :reload; a known namespace
  ;; stays available (the pre-simplification loader returned its handle)
  (let [r (run "(require 'clojure.set :reload) (require '[babashka.fs :as fs] :reload) (emit :ok)")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= ":ok" (:content r)))))

(t/deftest test-run-code-slurp-follows-runtime-cwd
  (with-cwd-dir
    (fn [dir]
      (spit (str dir "/hello.txt") "from-cwd")
      (let [r (run (str "(emit [(slurp \"hello.txt\") (= (sandbox/cwd) " (pr-str dir) ")])"))]
        (t/is (not (:is-error r)) (:content r))
        (t/is (str/includes? (:content r) "from-cwd"))
        (t/is (str/includes? (:content r) "true"))))))

(t/deftest test-run-code-stderr-not-reported
  (let [r (run "(binding [*out* *err*] (println \"oops\")) (emit :done)")]
    (t/is (not (:is-error r)))
    (t/is (= ":done" (:content r)))
    (t/is (not (str/includes? (:content r) "oops"))
          "stderr is captured but not reported")))

(t/deftest test-run-code-output-truncation
  (let [r (run "(dotimes [i 3000] (emit i))")]
    (t/is (not (:is-error r)))
    (t/is (some? (:truncation r)))
    (t/is (= :lines (get-in r [:truncation :truncated-by])))
    (t/is (> (get-in r [:truncation :total-lines]) 2000))
    (t/is (str/includes? (:content r) "truncated"))))

(t/deftest test-run-code-output-budget
  (let [r (run "(dotimes [i 1000] (emit (apply str (repeat 100 \"x\"))))"
               {:max-output-bytes 1024})]
    (t/is (not (:is-error r)))
    (t/is (= 1024 (get-in r [:truncation :max-bytes])))
    (t/is (= :bytes (get-in r [:truncation :truncated-by])))
    (t/is (str/includes? (:content r) "[run_code output truncated")))
  (let [r (run "(dotimes [i 1000] (emit (apply str (repeat 100 \"x\"))))")]
    (t/is (= (* 16 1024) (get-in r [:truncation :max-bytes]))
          "the script default is smaller than bash's shared cap")))

(t/deftest test-run-code-output-limit-normalization
  (let [r (run "(dotimes [i 1000] (emit (apply str (repeat 100 \"x\"))))"
               {:max-output-bytes 999999})]
    (t/is (= (* 50 1024) (get-in r [:truncation :max-bytes]))
          "limits clamp to the tool-wide byte cap"))
  (let [r (run "(dotimes [i 1000] (emit (apply str (repeat 100 \"x\"))))"
               {:max-output-bytes 0})]
    (t/is (= (* 16 1024) (get-in r [:truncation :max-bytes])))
    (t/is (= 2000 (get-in r [:truncation :max-lines]))
          "invalid limits fall back to the defaults")))

(t/deftest test-run-code-streaming
  (let [updates (atom [])
        r (run "(dotimes [i 4] (emit i) (sandbox/sleep 50))"
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
      (let [r (run "(emit @(tools/call \"run-code-test-echo\" {:x 1}))")]
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
      (let [r (run (str "(emit (let [a (tools/call \"run-code-test-echo\" {:n 1})"
                        "      b (tools/call \"run-code-test-echo\" {:n 2})]"
                        "  [(clojure.string/includes? (:content @a) \":n 1\")"
                        "   (clojure.string/includes? (:content @b) \":n 2\")]))"))]
        (t/is (not (:is-error r)) (:content r))
        (t/is (= "[true true]" (:content r)))
        (t/is (= 2 (count (get-in r [:details :calls]))))))))

(t/deftest test-run-code-fan-out-exceeds-worker-pool
  ;; the bridge drains through a bounded worker pool; a fan-out larger than
  ;; the pool must still settle every promise
  (with-custom-tool {:name "run-code-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :execute (fn [args] {:content (pr-str args)})}
    (fn []
      (let [r (run (str "(emit (count (mapv deref (mapv #(tools/call \"run-code-test-echo\" {:n %})"
                        " (range 40)))))"))]
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
                                 {:block true :reason "blocked by hook" :terminate true}
                                 {:args (assoc (:args ctx) :hooked true)})))
                   :after (fn [ctx]
                            (when (= "run-code-test-echo" (:tool-name ctx))
                              {:content (str (:content (:result ctx)) "|after")}))}]
          (let [r (run (str "(emit [(deref (tools/call \"run-code-test-echo\" {:n 1}))"
                            " (deref (tools/call \"run-code-test-echo\" {:block true}))])"))]
            (t/is (not (:is-error r)) (:content r))
            (t/is (str/includes? (:content r) ":n 1, :hooked true"))
            (t/is (str/includes? (:content r) "|after"))
            (t/is (str/includes? (:content r) "blocked by hook|after"))
            (t/is (str/includes? (:content r) ":terminate true")
                  "a blocked inner call reports the hook's :terminate to the script")
            (t/is (not (contains? r :terminate))
                  "the inner hint stays inner: the outer call's result is the script's")
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
  ;; (a 1s sleep would beat the marker check below if it survived)
  (let [dir (temp-dir)
        marker (str dir "/marker")]
    (try
      (let [r (run (str "(deref (tools/call \"bash\" {:command "
                        (pr-str (str "sleep 1; touch '" marker "'"))
                        "}))")
                   {:timeout 0.4})]
        (t/is (:is-error r))
        (t/is (= :timeout (get-in r [:details :error])))
        (Thread/sleep 1200)
        (t/is (not (fs/exists? marker))
              "the killed child never touched the marker"))
      (finally (fs/delete-tree dir)))))

(t/deftest ^:slow test-run-code-parallel-calls-still-overlap
  ;; the pool must not serialize calls that do not wait on each other: both
  ;; calls are in flight before either settles
  (let [log (atom [])]
    (with-custom-tool {:name "run-code-test-par"
                       :label "Parallel"
                       :description "A parallel-safe tool"
                       :execute (fn [args]
                                  (swap! log conj [:start (:id args)])
                                  (Thread/sleep 100)
                                  (swap! log conj [:end (:id args)])
                                  {:content "ok"})}
      (fn []
        (let [r (run (str "(let [a (tools/call \"run-code-test-par\" {:id :a})"
                          "      b (tools/call \"run-code-test-par\" {:id :b})]"
                          "  @a @b (emit :done))"))]
          (t/is (= ":done" (:content r)) (:content r))
          (t/is (= #{[:start :a] [:start :b]} (set (take 2 @log)))
                "both calls start before either settles"))))))

(t/deftest ^:slow test-run-code-bridge-ignores-sequential
  ;; the loop honours :execution-mode; a script has no batch and no barrier, so
  ;; a tool that declares :sequential must not serialize the calls around it
  (let [log (atom [])]
    (with-custom-tool {:name "run-code-test-seq"
                       :label "Sequential"
                       :description "Declares the loop's exclusive classifier"
                       :execution-mode :sequential
                       :execute (fn [args]
                                  (swap! log conj [:start (:id args)])
                                  (Thread/sleep 100)
                                  (swap! log conj [:end (:id args)])
                                  {:content "ok"})}
      (fn []
        (let [r (run (str "(let [a (tools/call \"run-code-test-seq\" {:id :a})"
                          "      b (tools/call \"run-code-test-seq\" {:id :b})]"
                          "  @a @b (emit :done))"))]
          (t/is (= ":done" (:content r)) (:content r))
          (t/is (= #{[:start :a] [:start :b]} (set (take 2 @log)))
                "both calls start before either settles: the script has no barrier"))))))

(t/deftest test-run-code-gate
  (let [r (run "(emit @(tools/call \"no-such-tool\" {}))")]
    (t/is (not (:is-error r)))
    (t/is (str/includes? (:content r) ":is-error true"))
    (t/is (str/includes? (:content r) "no-such-tool")))
  (let [r (run "(emit @(tools/call \"script\" {:code \"1\"}))")]
    (t/is (str/includes? (:content r) "not active")))
  (binding [run-code/*enabled-tools-fn* (fn [] #{"read"})]
    (t/is (= "[\"read\"]" (:content (run "(emit (mapv :name (tools/list)))"))))
    (let [r (run "(emit @(tools/call \"bash\" {}))")]
      (t/is (str/includes? (:content r) "not active")))))

(t/deftest test-run-code-discovery
  (with-custom-tool {:name "run-code-test-echo"
                     :label "Echo"
                     :description "Echo args"
                     :params {:x {:type :number}}
                     :execute (fn [_] {:content "x"})}
    (fn []
      (let [r (run (str "(emit (let [ts (tools/list)"
                        "            names (mapv :name ts)"
                        "            t (first (filter #(= \"run-code-test-echo\" (:name %)) ts))]"
                        "        [(boolean (some #{\"run-code-test-echo\"} names))"
                        "         (boolean (some #{\"run_code\"} names))"
                        "         (:description t)"
                        "         (get-in t [:parameters :properties \"x\" :type])"
                        "         (contains? t :execute) (contains? t :label)]))"))]
        (t/is (= "[true false \"Echo args\" \"number\" false false]" (:content r))))
      (let [r (run "(emit (let [d (tools/describe \"run-code-test-echo\")] [(:name d) (:description d) (contains? d :execute)]))")]
        (t/is (= "[\"run-code-test-echo\" \"Echo args\" false]" (:content r))))
      (let [r (run "(emit (tools/describe \"no-such-tool\"))")]
        (t/is (str/includes? (:content r) ":is-error true"))))))

(t/deftest test-run-code-contributed-tools
  ;; extension-contributed sources (the contributed-source seam): sandbox-only tools join
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
      (t/is (str/includes? (:content (run "(emit (tools/list))")) "run-code-test-contributed"))
      (let [r (run (str "(emit (let [d (tools/describe \"run-code-test-contributed\")]"
                        "  [(:name d) (:description d) (contains? d :execute)"
                        "   (get-in d [:parameters :properties \"x\" :type])]))"))]
        (t/is (= "[\"run-code-test-contributed\" \"A sandbox-only tool\" false \"number\"]"
                 (:content r))))
      (let [r (run "(emit @(tools/call \"run-code-test-contributed\" {:x 7}))")]
        (t/is (str/includes? (:content r) "contributed:7"))
        (t/is (= [{:tool "run-code-test-contributed" :ok true}]
                 (get-in r [:details :calls]))))
      ;; the exclusion list covers contributions too
      (let [r (run "(emit @(tools/call \"script\" {:code \"1\"}))")]
        (t/is (str/includes? (:content r) "not active"))
        (t/is (not (str/includes? (:content r) "should never run"))))
      ;; a registry name wins a collision (the model's names keep meaning)
      (let [r (run "(emit (let [d (tools/describe \"read\")] (:name d)))")]
        (t/is (= "read" (:content r)))))))

(t/deftest test-run-code-contributed-tools-bypass-enabled
  ;; contributions are sandbox-only: set-active-tools! filters the model's
  ;; registry set, not the extension's contribution
  (with-tool-source ::extension
    (fn [] {"run-code-test-contributed" {:name "run-code-test-contributed"
                                         :label "Contributed"
                                         :execute (fn [_] {:content "x" :is-error false})}})
    (fn []
      (binding [run-code/*enabled-tools-fn* (fn [] #{"read"})]
        (t/is (= "[\"read\" \"run-code-test-contributed\"]" (:content (run "(emit (mapv :name (tools/list)))"))))
        (t/is (str/includes? (:content (run "(emit @(tools/call \"bash\" {}))")) "not active"))))))

(t/deftest test-run-code-contributed-tools-unregister
  (tools/register-tool-source! reg ::extension
                               (fn [] {"run-code-test-gone" {:name "run-code-test-gone"
                                                             :execute (fn [_] {:content "x"})}}))
  (try
    (t/is (str/includes? (:content (run "(emit (tools/list))")) "run-code-test-gone"))
    (finally (tools/unregister-tool-source! reg ::extension)))
  (t/is (not (str/includes? (:content (run "(emit (tools/list))")) "run-code-test-gone"))))

(t/deftest test-run-code-broken-tool-source
  ;; a source that throws (or returns a non-map) contributes nothing and
  ;; cannot take the sandbox down
  (tools/register-tool-source! reg ::broken (fn [] (throw (ex-info "boom" {}))))
  (tools/register-tool-source! reg ::bad-shape (fn [] [1 2 3]))
  (try
    (let [r (run "(emit (+ 1 2))")]
      (t/is (not (:is-error r)) (:content r))
      (t/is (= "3" (:content r))))
    (finally
      (tools/unregister-tool-source! reg ::broken)
      (tools/unregister-tool-source! reg ::bad-shape))))

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
            r (run "(emit @(tools/call \"run-code-test-stream\" {}))" {}
                   (fn [partial] (swap! updates conj partial)))]
        (t/is (not (:is-error r)) (:content r))
        (t/is (str/includes? (:content r) "done"))
        (t/is (some #(str/includes? (:content %) "inner-progress") @updates))))))

(t/deftest test-run-code-full-script-surface
  ;; the sandbox carries the host's clojure.*/babashka.* namespaces; on
  ;; babashka it also carries the class table (jolt's SCI exposes none), so a
  ;; normal script resolves System/Throwable and uses future
  (t/is (= "x" (:content (run "(require '[clojure.java.io :as io]) (emit (str (io/file \"x\")))"))))
  (t/is (= "true" (:content (run "(emit (boolean (resolve 'clojure.pprint/pprint)))"))))
  (t/is (= "3" (:content (run "(emit @(future 3))"))))
  (when-not (host/jolt?)
    (t/is (= "true" (:content (run "(emit (pos? (System/currentTimeMillis)))"))))
    (t/is (= ":caught" (:content (run "(emit (try (throw (Throwable. \"x\")) (catch Throwable t :caught)))"))))))

(t/deftest test-run-code-boundary
  ;; kmet's own namespaces stay out of the sandbox
  (let [r (run "(require 'kmet.app.loop)")]
    (t/is (:is-error r))
    (t/is (str/includes? (:content r) "kmet.app.loop"))
    (t/is (str/includes? (:content r) "cannot load namespace")
          "the sandbox's own boundary error, not SCI's generic one")))

(t/deftest test-run-code-context-isolation
  (let [r (run "(def leaked 42) (defn tally [] 1) (emit leaked)")]
    (t/is (not (:is-error r)))
    (t/is (= "42" (:content r))))
  (let [r (run "(emit [(resolve 'leaked) (resolve 'tally)])")]
    (t/is (not (:is-error r)))
    (t/is (= "[nil nil]" (:content r)))))

(t/deftest ^:slow test-run-code-process-capability
  (let [dir (temp-dir)]
    (try
      (binding [tool-util/*cwd* dir]
        ;; both babashka.process shapes: tokens with opts first, a command
        ;; vector with opts last (the wrapper normalizes them)
        (let [r (run (str "(emit [(clojure.string/trim (:out (p/sh \"pwd\")))"
                          " (clojure.string/trim (:out @(p/process \"pwd\" {:out :string})))"
                          " (clojure.string/trim (:out (p/shell {:out :string} \"pwd\")))])"))]
          (t/is (not (:is-error r)) (:content r))
          (t/is (every? #(str/includes? (str %) (fs/file-name dir))
                        (read-string (:content r)))
                "all three process shapes report the runtime cwd's leaf")))
      (finally (fs/delete-tree dir)))))

(t/deftest ^:slow test-run-code-preloaded-sh
  ;; sh is preloaded bare (cwd-defaulting), like fs/p
  (let [r (run "(emit (clojure.string/trim (:out (sh \"echo\" \"preloaded\"))))")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (= "preloaded" (:content r)))))

(t/deftest ^:slow test-run-code-inner-bash
  (let [r (run "(emit @(tools/call \"bash\" {:command \"echo inner-ok\"}))")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "inner-ok"))
    (t/is (= "bash" (get-in r [:details :calls 0 :tool])))))

(t/deftest ^:slow test-run-code-inner-bash-follows-runtime-cwd
  (let [dir (temp-dir)]
    (try
      (binding [tool-util/*cwd* dir]
        (let [r (run "(emit (clojure.string/trim (:content @(tools/call \"bash\" {:command \"pwd\"}))))")]
          (t/is (not (:is-error r)) (:content r))
          (t/is (str/includes? (:content r) (fs/file-name dir)))))
      (finally (fs/delete-tree dir)))))

;; ─── Capture edge cases ───────────────────────────────────────────────────

(t/deftest test-run-code-large-emit-tail-kept
  ;; an emit larger than the result budget must keep its tail (the end holds
  ;; the distilled result) and report the full size in the notice
  (let [r (run "(emit (apply str (repeat 140000 \"b\")))")]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "bbbb") "the burst's tail survives")
    (t/is (= :bytes (get-in r [:truncation :truncated-by])))
    (t/is (>= (get-in r [:truncation :total-bytes]) 140000)
          "the notice reports the burst, not the retained size")))

(t/deftest test-run-code-oversized-emit-then-marker
  (let [r (run "(emit (apply str (repeat 140000 \"b\"))) (emit \"MARKER\")")]
    (t/is (str/includes? (:content r) "MARKER"))
    (t/is (some? (:truncation r)) "truncation is reported, not silence")))

(t/deftest test-run-code-multibyte-emit-tail
  ;; a multi-byte emit cut at the result budget must not leak a replacement
  ;; char from a mid-character boundary
  (let [r (run "(emit (apply str (repeat 43700 \"€\")))")]
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

(t/deftest test-run-code-emit-totals-count-everything
  ;; the notice's totals describe the whole emitted output, not the tail kept
  (let [r (run "(dotimes [i 40000] (emit i))")]
    (t/is (not (:is-error r)))
    (t/is (= 40000 (get-in r [:truncation :total-lines])))
    (t/is (str/includes? (:content r) "39999"))))

(t/deftest test-run-code-unbounded-emit-is-bounded
  ;; *print-length* is bound: an infinite seq emitted is a bounded prefix
  ;; instead of running host code past the deadline (printing is not
  ;; interruptible)
  (let [r (run "(emit (range))" {:timeout 5})]
    (t/is (not (:is-error r)) (:content r))
    (t/is (str/includes? (:content r) "99999") "a bounded prefix is printed")
    (t/is (str/includes? (:content r) "...") "print-length elides the rest")))

(t/deftest test-run-code-keyword-tool-name
  ;; discovery and dispatch agree on the name form
  (t/is (= "[\"read\" false]"
           (:content (run "(emit (let [d (tools/describe :read)] [(:name d) (contains? d :execute)]))"))))
  (t/is (= "false"
           (:content (run (str "(emit (let [x @(tools/call :read {:path \"deps.edn\" :limit 1})]"
                               "  (boolean (:is-error x))))"))))))

(t/deftest test-run-code-emit-output-limit
  ;; emit is bounded too: past the 1 MiB capture budget it sets :output-limit
  ;; and throws, so the buffer cannot grow without bound
  (let [r (run "(dotimes [i 40000] (emit (apply str (repeat 100 \"x\"))))")]
    (t/is (:is-error r) (:content r))
    (t/is (= :output-limit (get-in r [:details :error])))
    (t/is (str/includes? (:content r) "exceeded"))))

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
        f (future (tools/execute-tool reg "run_code"
                                      {:code "(sandbox/sleep 5000) :ok"}
                                      {:signal signal}))]
    (Thread/sleep 200)
    (reset! signal true)
    (let [r (deref f 5000 nil)]
      (t/is (some? r) "the tool call returns instead of blocking on the sleep")
      (t/is (:is-error r))
      (t/is (= :aborted (get-in r [:details :error]))))))

(t/deftest test-run-code-inner-read-conveys-the-skill-resolver
  ;; A bridge worker is a raw platform thread: it conveys no dynamic bindings,
  ;; so the run's skill resolver travels to it explicitly as the pipeline's
  ;; :bindings (landing 9b). Without that, an extension skill's <location>
  ;; would read as a missing file from inside a script while the model's own
  ;; read served it — the seam working everywhere except where scripts live.
  (binding [read-tool/*skill-resolver*
            (fn [p] (when (= p "my-ext:skills/foo/SKILL.md") "# memory body"))]
    (let [r (run "(emit (:content @(tools/call \"read\" {:path \"my-ext:skills/foo/SKILL.md\"})))")]
      (t/is (not (:is-error r)) (:content r))
      (t/is (= "# memory body" (:content r))
            "the worker answered from the run's resolver"))))
