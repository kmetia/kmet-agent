(ns kmet.tasks.test-runner
  "The runner's own gate behavior: a namespace that could not load fails the
   run — it is reported and skipped per namespace, but its tests never ran, so
   a green result would silently shrink the gate."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tasks.runner :as runner]))

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
