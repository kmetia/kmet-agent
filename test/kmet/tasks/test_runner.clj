(ns kmet.tasks.test-runner
  "The runner's own gate behavior: a namespace that could not load fails the
   run — it is reported and skipped per namespace, but its tests never ran, so
   a green result would silently shrink the gate. The same holds for a test
   that writes the host agent dir: it edits the developer's machine state."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tasks.runner :as runner]
            [kmet.test-tmp :as test-tmp]))

(defn- summarize!
  "Run run-and-summarize with a synthetic SELECTION, capturing its stdout and
   intercepting the exit (the real one would kill the test process).
   Returns {:exit code :out captured-stdout}."
  [selection mark-validated?]
  (let [code (atom nil)
        out (java.io.StringWriter.)]
    (with-redefs-fn {(var runner/exit!) (fn [c] (reset! code c))}
      #(binding [*out* out]
         (#'runner/run-and-summarize selection mark-validated?)))
    {:exit @code :out (str out)}))

(defn- selection [unloaded]
  {:vars [] :unloaded unloaded :filters []})

(defn- temp-agent-dir
  "A temp dir F receives and that is removed afterwards."
  [f]
  (let [dir (str (fs/absolutize (test-tmp/temp-dir! "test-runner-agent")))]
    (fs/create-dirs dir)
    (try (f dir) (finally (fs/delete-tree dir)))))

(t/deftest agent-dir-stamp-sees-created-rewritten-and-untouched
  ;; the guard is only as good as its change detection: a created file and a
  ;; rewritten one must both show up, and a quiet run must show nothing
  (temp-agent-dir
   (fn [dir]
     (let [stamp #(#'runner/agent-dir-stamp dir)
           changes #(#'runner/agent-dir-changes %1 %2)
           before (stamp)]
       (t/is (= [] (changes before (stamp))) "nothing written: no change")
       (spit (str (fs/path dir "mcp.edn")) "{}")
       (let [created (stamp)]
         (t/is (= ["mcp.edn"] (changes before created)) "a created file is seen")
         (Thread/sleep 5)
         (spit (str (fs/path dir "mcp.edn")) "{:mcp-servers {}}")
         (t/is (= ["mcp.edn"] (changes created (stamp))) "a rewrite is seen")
         (fs/delete (str (fs/path dir "mcp.edn")))
         (t/is (= ["mcp.edn"] (changes created (stamp))) "a deletion is seen"))))))

(t/deftest agent-dir-stamp-ignores-files-the-run-does-not-own
  (temp-agent-dir
   (fn [dir]
     (let [before (#'runner/agent-dir-stamp dir)]
       (spit (str (fs/path dir "sessions.edn")) "{}")
       (t/is (= [] (#'runner/agent-dir-changes before (#'runner/agent-dir-stamp dir)))
             "a file outside the watch list is not the run's business")))))

(t/deftest a-test-that-writes-the-agent-dir-fails-the-run
  ;; Regression: the mcp adapter's config was left holding a test fixture, so a
  ;; green run edited the developer's own machine. The runner now fails any run
  ;; whose namespaces touch the agent dir, and names the namespace.
  (with-redefs [runner/run-selected
                (fn [_] {:counters {:test 0 :pass 0 :fail 0 :error 0}
                         :agent-dir-writes [['fixtures.leaky "mcp.edn"]]})]
    (let [{:keys [exit out]} (summarize! (selection []) false)]
      (t/is (= 1 exit) out)
      (t/is (str/includes? out "wrote the host agent dir") out)
      (t/is (str/includes? out "fixtures.leaky") out)
      (t/is (str/includes? out "mcp.edn") out)
      (t/is (str/includes? out "1 agent-dir write") out))))

(t/deftest ^:bb-only agent-dir-writes-do-not-mark-the-baseline
  (let [marked (atom false)
        mark-validated! (requiring-resolve 'kmet.tasks.changed/mark-validated!)]
    (with-redefs-fn {mark-validated! (fn [] (reset! marked true))
                     (var runner/run-selected)
                     (fn [_] {:counters {:test 0 :pass 0 :fail 0 :error 0}
                              :agent-dir-writes [['fixtures.leaky "mcp.edn"]]})}
      #(summarize! (selection []) true))
    (t/is (false? @marked))))

(t/deftest unloadable-namespace-fails-the-run
  ;; Regression: an unloadable namespace was printed and skipped while the run
  ;; still exited 0 — a missing require silently dropped a namespace's tests
  ;; from the gate.
  (let [{:keys [exit out]} (summarize! (selection [['fixtures.broken-ns "boom"]]) false)]
    (t/is (= 1 exit))
    (t/is (str/includes? out "could not load") out)
    (t/is (str/includes? out "fixtures.broken-ns") out)
    (t/is (str/includes? out "1 namespace failed to load") out)))

(t/deftest clean-run-exits-zero
  (let [{:keys [exit out]} (summarize! (selection []) false)]
    (t/is (= 0 exit))
    (t/is (str/includes? out "0 passed") out)
    (t/is (not (str/includes? out "failed to load")) out)))

(t/deftest ^:bb-only unloadable-namespace-does-not-mark-the-baseline
  ;; the changed-files baseline is only recorded for a complete green run
  ;; (bb-only: kmet.tasks.changed is a bb task, and the runner's mark gate is
  ;; itself bb-gated)
  (let [marked (atom false)
        mark-validated! (requiring-resolve 'kmet.tasks.changed/mark-validated!)]
    (with-redefs-fn {mark-validated! (fn [] (reset! marked true))}
      #(summarize! (selection [['fixtures.broken-ns "boom"]]) true))
    (t/is (false? @marked))))
