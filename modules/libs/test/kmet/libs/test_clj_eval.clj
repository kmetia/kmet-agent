(ns kmet.libs.test-clj-eval
  "Tests for the shared nREPL evaluator (kmet.libs.clj-eval):
   argument validation, discovery failure, result formatting, and the
   repair/timeout notices."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [kmet.libs.clj-eval :as clj-eval]
            [kmet.libs.nrepl :as nrepl]))

(defn- run
  "Evaluate ARGS through the shared evaluator."
  [args] (clj-eval/evaluate args nil))

(defn- evaluate-with
  "Run clj-eval/evaluate with nrepl/eval-code stubbed to RESULT (or
   throwing THROWABLE). Returns [result captured-opts]."
  [args result & [throwable]]
  (let [captured (atom nil)]
    (with-redefs [nrepl/eval-code (fn [opts]
                                    (reset! captured opts)
                                    (if throwable (throw throwable) result))]
      [(clj-eval/evaluate args nil) @captured])))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Argument validation / discovery
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest ^:bb-only test-missing-code
  (let [result (run {})]
    (is (:is-error result))
    (is (str/includes? (:content result) "code"))))

(deftest ^:bb-only test-no-server-found
  (with-redefs [nrepl/discover-port (fn [& _] nil)]
    (let [result (run {:code "(+ 1 2)"})]
      (is (:is-error result))
      (is (str/includes? (:content result) "No nREPL server found"))
      (is (str/includes? (:content result) ".nrepl-port"))
      (is (str/includes? (:content result) "common nREPL ports")))))

(deftest ^:bb-only test-explicit-port-skips-discovery
  (let [discover-called (atom false)]
    (with-redefs [nrepl/discover-port (fn [& _] (reset! discover-called true) nil)
                  nrepl/eval-code (fn [_] {:ok? true :values ["3"] :out "" :err ""
                                           :events [[:value "3"]] :env :clj})]
      (run {:code "(+ 1 2)" :port 7888})
      (is (false? @discover-called)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Formatting
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest ^:bb-only test-success-content-interleaves-output-and-values
  (let [result {:ok? true
                :values ["3"]
                :out "hello\n"
                :err ""
                :ns "user"
                :env :clj
                :session "sess-1"
                :events [[:out "hello\n"] [:value "3"]]}
        [r opts] (evaluate-with {:code "(println :hello) (+ 1 2)" :port 7888} result)]
    (is (not (:is-error r)))
    (is (= "hello\n=> 3" (:content r)))
    (is (= 7888 (:port opts)))
    (is (= "(println :hello) (+ 1 2)" (:code opts)))
    (is (= {:values ["3"] :out "hello\n" :err "" :ns "user" :env "clj"
            :session "sess-1" :repaired? false :timed-out? false
            :cancelled? false :error? false}
           (dissoc (:details r) :code)))))

(deftest ^:bb-only test-eval-error-is-flagged-and-named
  (let [result {:ok? false
                :ex "class java.lang.ArithmeticException"
                :values ["#error {:cause \"Divide by zero\"}"]
                :out ""
                :err "  at clojure.lang..."
                :events [[:value "#error {:cause \"Divide by zero\"}"]
                         [:err "  at clojure.lang..."]]}
        [r _] (evaluate-with {:code "(/ 1 0)" :port 7888} result)]
    (is (:is-error r))
    (is (str/starts-with? (:content r) "Eval error (class java.lang.ArithmeticException)"))
    (is (str/includes? (:content r) "=> #error"))
    (is (str/includes? (:content r) "at clojure.lang"))))

(deftest ^:bb-only test-repaired-delimiters-are-reported
  (let [result {:ok? true :values ["#'user/foo"] :out "" :err ""
                :events [[:value "#'user/foo"]] :env :clj}
        [r opts] (evaluate-with {:code "(defn foo [x]\n  (+ x 1" :port 7888} result)]
    (is (str/includes? (:content r) "note: repaired unbalanced delimiters"))
    (is (= "(defn foo [x]\n  (+ x 1))" (:code opts))
        "evaluation receives the repaired code")
    (is (true? (get-in r [:details :repaired?])))))

(deftest ^:bb-only test-timeout-is-reported
  (let [result {:ok? false :timeout? true :values [] :out "" :err ""
                :events [] :env :clj}
        [r _] (evaluate-with {:code "(Thread/sleep 10000)" :port 7888 :timeout 500} result)]
    (is (:is-error r))
    (is (str/includes? (:content r) "timed out after 500 ms"))
    (is (true? (get-in r [:details :timed-out?])))))

(deftest ^:bb-only test-cancelled-is-reported
  (let [result {:ok? false :cancelled? true :values [] :out "" :err ""
                :events [] :env :clj}
        [r _] (evaluate-with {:code "(Thread/sleep 100000)" :port 7888} result)]
    (is (:is-error r))
    (is (str/includes? (:content r) "cancelled"))
    (is (true? (get-in r [:details :cancelled?])))
    (is (not (str/includes? (:content r) "Eval error")))))

(deftest ^:bb-only test-connection-error-is-reported
  (let [[r _] (evaluate-with {:code "(+ 1 2)" :port 7888} nil
                             (ex-info "Could not connect to nREPL at 127.0.0.1:7888 — Connection refused"
                                      {:type :nrepl-connect}))]
    (is (:is-error r))
    (is (str/starts-with? (:content r) "nREPL error: Could not connect"))))

;; ═══════════════════════════════════════════════════════════════════════════════
