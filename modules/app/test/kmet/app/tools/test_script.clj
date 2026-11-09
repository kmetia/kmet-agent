(ns kmet.app.tools.test-script
  "The shared script layer's kept-context seams: a definition survives a call,
   `seed!` replaces the tools namespace's key set per call, a timeout
   leaves the next call clean, and cancellation ends a host-blocked call."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
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
                               "(core/emit (contains? (ns-publics 'tools) 'a))"))))
    (t/is (= "[false true true]"
             (:content (run-in s {"b" (tool-def "b")}
                               (str "(core/emit [(contains? (ns-publics 'tools) 'a)"
                                    " (contains? (ns-publics 'tools) 'b)"
                                    " (contains? (ns-publics 'tools) 'call)])")))))))

(t/deftest test-a-run-needs-a-tool-name
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :tool"
                          (script/prepare {})))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :tool"
                          (script/run {:code "1"}))))

(defn- write-cancel-fixture!
  "Write a parent/child fixture in DIR whose child writes MARKER after about
   a second while the parent blocks; return the argv that starts the parent.
   The child is a descendant, so a leftover marker means the tree survived,
   not just the direct child."
  [dir marker]
  (if kprocess/windows-os?
    (let [child (str (fs/path dir "child.cmd"))
          parent (str (fs/path dir "parent.cmd"))]
      (spit child (str "@echo off\r\n"
                       "ping -n 2 127.0.0.1 >NUL\r\n"
                       "echo alive>\"" marker "\"\r\n"))
      (spit parent (str "@echo off\r\n"
                        "start /b \"\" \"" child "\"\r\n"
                        "ping -n 30 127.0.0.1 >NUL\r\n"))
      ["cmd.exe" "/c" parent])
    (let [script (str (fs/path dir "cancel.sh"))]
      (spit script (str "#!/bin/sh\n"
                        "(sleep 1; echo alive > " (pr-str marker) ") &\n"
                        "sleep 30\n"))
      ["sh" script])))

(defn- run-cancelled-process
  "Run WRAP's code with the fixture's argv — a parent that blocks while a
   child writes a marker after a short sleep — firing the call's signal after
   100ms. Returns {:returned? bool :marker-exists? bool}, the marker checked
   after the write would have landed."
  [wrap]
  (let [dir (test-tmp/temp-dir! "script-cancel-")
        marker (str (fs/path dir "marker"))
        argv (write-cancel-fixture! dir marker)
        s (kept-context)
        signal (atom false)
        fut (future (script/run (assoc (run-opts {} (wrap argv))
                                       :timeout 10
                                       :signal signal
                                       :sci-ctx (:ctx s)
                                       :interrupt (:holder s))))]
    (Thread/sleep 100)
    (reset! signal true)
    (let [returned? (not= ::timeout (deref fut 5000 ::timeout))]
      (Thread/sleep 2000)
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
        fut (future (script/run (assoc (run-opts {} "(core/sleep 5000)")
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
  (let [{:keys [returned? marker-exists?]}
        (run-cancelled-process
         (fn [argv]
           (str "(core/sh " (str/join " " (map pr-str argv)) ")")))]
    (t/is returned? "the call returns on Escape")
    (t/is (not marker-exists?)
          "the spawned shell was killed, not left writing after the call")))

(t/deftest ^:slow test-cancellation-kills-a-bare-shell-process
  (let [{:keys [returned? marker-exists?]}
        (run-cancelled-process
         (fn [argv]
           (str "(p/shell {:out :string :err :string} " (pr-str argv) ")")))]
    (t/is returned? "the call returns on Escape")
    (t/is (not marker-exists?)
          "the spawned shell was killed, not left writing after the call")))

(t/deftest ^:slow test-shell-keeps-babashka-process-semantics
  (when-not kprocess/windows-os?
    (let [s (kept-context)
          run #(run-in s {} %)]
      (t/is (= "hi\n"
               (:content (run "(core/emit (:out (p/shell {:out :string :err :string} [\"echo\" \"hi\"])))"))))
      (t/is (= ":exit-3"
               (:content (run "(core/emit (try (p/shell {:out :string :err :string} [\"sh\" \"-c\" \"exit 3\"]) :ok (catch Exception _ :exit-3)))"))))
      (t/is (= "3"
               (:content (run "(core/emit (:exit (p/shell {:out :string :err :string :continue true} [\"sh\" \"-c\" \"exit 3\"])))")))))))

(t/deftest ^:slow test-cancellation-kills-a-background-process
  (let [{:keys [marker-exists?]}
        (run-cancelled-process
         (fn [argv]
           (str "(p/process " (pr-str argv) ")")))]
    (t/is (not marker-exists?)
          "Escape kills a process spawned by an already-finished script")))

(t/deftest test-bare-sh-accepts-trailing-keyword-options
  (let [s (kept-context)]
    (t/is (= "hi\n"
             (:content (run-in s {}
                               "(core/emit (:out (core/sh \"sh\" \"-c\" \"read x; echo $x\" :in \"hi\\n\")))"))))))

(defn- run-with-dispatch
  "Run CODE with TOOLS dispatched through the bridge, so the bare tool wrappers
   settle a real tool result."
  [s tools code]
  (script/run (assoc (run-opts tools code)
                     :sci-ctx (:ctx s)
                     :interrupt (:holder s)
                     :execute-tool (fn [name args _]
                                     (if-let [tool (get tools name)]
                                       ((:execute tool) args)
                                       {:content (str "Unknown tool: " name)
                                        :is-error true})))))

(t/deftest test-core-verbs-use-the-implementations-not-the-tool-bridge (let [s (kept-context) read-tools {"read" (tool-def "read")} bash-tools {"bash" (tool-def "bash")} path (str "target/test-core-verbs-" (random-uuid) ".txt")] (t/is (str/includes? (:content (run-with-dispatch s read-tools "(core/emit (core/read \"no-such-file\"))")) "File not found") "core/read calls the read implementation, not the tool stub") (t/is (= "hi\n" (:content (run-with-dispatch s bash-tools "(core/emit (core/bash \"echo hi\"))"))) "core/bash calls the bash implementation, not the tool stub") (try (run-with-dispatch s {"write" (tool-def "write")} (str "(core/write " (pr-str path) " \"old\")")) (t/is (= "old" (slurp path))) (run-with-dispatch s {"edit" (tool-def "edit")} (str "(core/edit " (pr-str path) " \"old\" \"new\")")) (t/is (= "new" (slurp path))) (finally (fs/delete-if-exists path)))))
