(ns kmet.app.tools.test-script
  "The shared script layer's kept-context seams: a definition survives a call,
   `seed!` replaces the tools namespace's key set per call, a timeout
   leaves the next call clean, and cancellation ends a host-blocked call."
  (:require [babashka.fs :as fs]
            [clojure.test :as t]
            [kmet.app.tools.script :as script]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.process :as kprocess]
            [kmet.test-tmp :as test-tmp]))

(defn- tool-def [name]
  {:name name
   :description (str name " test tool")
   :parameters {:type "object" :properties {}}
   :execute (fn [_] {:content name})})

(defn- kept-context []
  (let [holder (script/interrupt-holder)
        ctx (script/context (script/base-namespaces)
              (script/interrupt-fn holder "eval")
              "eval")]
    {:ctx ctx :holder holder}))

(defn- run-opts
  "The script tool's seams over TOOLS, the only callable tools."
  [tools code]
  {:tool "eval"
   :code code
   :get-all-tools (constantly tools)
   :get-contributed-tools (constantly {})
   :select-tools (fn [all _] all)
   :execute-tool (fn [& _] {:content "not dispatched"})})

(defn- run-in [{:keys [ctx holder]} tools code]
  (script/run (assoc (run-opts tools code)
                     :sci-ctx ctx
                     :interrupt holder)))

(t/deftest test-a-kept-context-keeps-a-definition
  (let [s (kept-context)]
    (t/is (= "42" (:content (run-in s {} "(do (def kept 42) kept)"))))
    (t/is (= "43" (:content (run-in s {} "(+ kept 1)"))))))

(t/deftest test-seed-replaces-the-tools-namespace
  (let [s (kept-context)]
    (t/is (= "true"
             (:content (run-in s {"a" (tool-def "a")}
                               "(emit (contains? (ns-publics 'tools) 'a))"))))
    (t/is (= "[false true true]"
             (:content (run-in s {"b" (tool-def "b")}
                               (str "(emit [(contains? (ns-publics 'tools) 'a)"
                                    " (contains? (ns-publics 'tools) 'b)"
                                    " (contains? (ns-publics 'tools) 'call)])")))))))

(t/deftest test-a-run-needs-a-tool-name
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :tool"
                          (script/prepare {})))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :tool"
                          (script/run {:code "1"}))))

(defn- run-cancelled-process
  "Run WRAP's code — it receives a shell command that writes a marker after a
   short sleep — firing the call's signal after 100ms. Returns {:returned?
   bool :marker-exists? bool}, the marker checked after the write would have
   landed."
  [wrap]
  (let [marker (str (fs/path (test-tmp/temp-dir! "script-cancel-") "marker"))
        command (str "sleep 1; echo alive > " (pr-str marker))
        s (kept-context)
        signal (atom false)
        fut (future (script/run (assoc (run-opts {} (wrap command))
                                       :timeout 10
                                       :signal signal
                                       :sci-ctx (:ctx s)
                                       :interrupt (:holder s))))]
    (Thread/sleep 100)
    (reset! signal true)
    (let [returned? (not= ::timeout (deref fut 5000 ::timeout))]
      (Thread/sleep 1500)
      {:returned? returned? :marker-exists? (fs/exists? marker)})))

(t/deftest ^:slow test-a-timeout-leaves-the-next-call-clean
  (let [s (kept-context)
        r (script/run (assoc (run-opts {} "(loop [] (recur))")
                             :timeout 0.1
                             :sci-ctx (:ctx s)
                             :interrupt (:holder s)))]
    (t/is (:is-error r))
    (t/is (= :timeout (get-in r [:details :error])))
    (t/is (= "42" (:content (run-in s {} "(+ 40 2)"))))))

(t/deftest ^:slow test-cancellation-ends-a-host-blocked-call-with-a-deadline
  ;; Regression: the deadline branch only waited for the clock, so Escape
  ;; during a host-blocked call (SCI's interrupt cannot reach it) ran until
  ;; the model's timeout, with the tool box's Elapsed ticking the whole time.
  (let [s (kept-context)
        signal (atom false)
        started (concurrent/monotonic-ms)
        fut (future (script/run (assoc (run-opts {} "(sandbox/sleep 5000)")
                                       :timeout 10
                                       :signal signal
                                       :sci-ctx (:ctx s)
                                       :interrupt (:holder s))))]
    (Thread/sleep 100)
    (reset! signal true)
    (let [r (deref fut 7000 ::timeout)]
      (t/is (not= ::timeout r) "the call returns on Escape, not at the deadline")
      (t/is (< (- (concurrent/monotonic-ms) started) 4000)
            "abandoned after the grace, well before the 10s deadline")
      (t/is (= :aborted (get-in r [:details :error]))))))

(t/deftest test-a-finished-script-with-a-fired-signal-is-cancelled
  ;; A signal that lands as the script finishes must still report aborted:
  ;; abort-reason wins over the script's own success.
  (let [done (doto (promise) (deliver true))]
    (t/is (= :done (#'script/await-script done (atom false) nil)))
    (t/is (= :cancelled (#'script/await-script done (atom true) nil)))
    (t/is (= :done (#'script/await-script done (atom false) 0)))
    (t/is (= :timeout (#'script/await-script (promise) (atom false) 1)))))

(t/deftest ^:slow test-cancellation-kills-a-bare-sh-process
  (when-not kprocess/windows-os?
    (let [{:keys [returned? marker-exists?]}
          (run-cancelled-process (fn [command]
                                   (str "(sh \"sh\" \"-c\" " (pr-str command) ")")))]
      (t/is returned? "the call returns on Escape")
      (t/is (not marker-exists?)
            "the spawned shell was killed, not left writing after the call"))))

(t/deftest ^:slow test-cancellation-kills-a-bare-shell-process
  (when-not kprocess/windows-os?
    (let [{:keys [returned? marker-exists?]}
          (run-cancelled-process
           (fn [command]
             (str "(p/shell {:out :string :err :string} [\"sh\" \"-c\" "
                  (pr-str command) "])")))]
      (t/is returned? "the call returns on Escape")
      (t/is (not marker-exists?)
            "the spawned shell was killed, not left writing after the call"))))

(t/deftest ^:slow test-shell-keeps-babashka-process-semantics
  (when-not kprocess/windows-os?
    (let [s (kept-context)
          run #(run-in s {} %)]
      (t/is (= "hi\n"
               (:content (run "(emit (:out (p/shell {:out :string :err :string} [\"echo\" \"hi\"])))"))))
      (t/is (= ":exit-3"
               (:content (run "(emit (try (p/shell {:out :string :err :string} [\"sh\" \"-c\" \"exit 3\"]) :ok (catch Exception _ :exit-3)))"))))
      (t/is (= "3"
               (:content (run "(emit (:exit (p/shell {:out :string :err :string :continue true} [\"sh\" \"-c\" \"exit 3\"])))")))))))

(t/deftest ^:slow test-cancellation-kills-a-background-process
  (when-not kprocess/windows-os?
    (let [{:keys [marker-exists?]}
          (run-cancelled-process
           (fn [command]
             (str "(p/process [\"sh\" \"-c\" " (pr-str command) "])")))]
      (t/is (not marker-exists?)
            "Escape kills a process spawned by an already-finished script"))))

(t/deftest test-bare-sh-accepts-trailing-keyword-options
  (let [s (kept-context)]
    (t/is (= "hi\n"
             (:content (run-in s {}
                               "(emit (:out (sh \"sh\" \"-c\" \"read x; echo $x\" :in \"hi\\n\")))"))))))
