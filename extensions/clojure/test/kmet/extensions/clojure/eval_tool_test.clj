(ns kmet.extensions.clojure.eval-tool-test
  "Tests for the clojure_eval tool (kmet.extensions.clojure.eval-tool):
   argument validation, discovery failure, result formatting, and the
   repair/timeout notices."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.extensions.clojure.eval-tool :as eval-tool]
            [kmet.extensions.clojure.nrepl :as nrepl]
            [kmet.tui.core :as core]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as utils]))

(defn- plain
  "Render a renderer result headlessly, ANSI-stripped."
  [comp width]
  (when comp
    (mapv utils/strip-ansi-codes (core/render comp width))))

(defn- execute-with
  "Run eval-tool/execute with nrepl/eval-code stubbed to RESULT (or throwing
   THROWABLE). Returns [result captured-opts]."
  [args result & [throwable]]
  (let [captured (atom nil)]
    (with-redefs [nrepl/eval-code (fn [opts]
                                    (reset! captured opts)
                                    (if throwable (throw throwable) result))]
      [(eval-tool/execute args) @captured])))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Argument validation / discovery
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-missing-code
  (let [result (eval-tool/execute {})]
    (is (:is-error result))
    (is (str/includes? (:content result) "code"))))

(deftest test-no-server-found
  (with-redefs [nrepl/discover-port (fn [& _] nil)]
    (let [result (eval-tool/execute {:code "(+ 1 2)"})]
      (is (:is-error result))
      (is (str/includes? (:content result) "No nREPL server found"))
      (is (str/includes? (:content result) ".nrepl-port"))
      (is (str/includes? (:content result) "common nREPL ports")))))

(deftest test-explicit-port-skips-discovery
  (let [discover-called (atom false)]
    (with-redefs [nrepl/discover-port (fn [& _] (reset! discover-called true) nil)
                  nrepl/eval-code (fn [_] {:ok? true :values ["3"] :out "" :err ""
                                           :events [[:value "3"]] :env :clj})]
      (eval-tool/execute {:code "(+ 1 2)" :port 7888})
      (is (false? @discover-called)))))

(deftest test-cwd-is-passed-to-discovery
  (let [captured (atom nil)]
    (with-redefs [nrepl/discover-port (fn [host dir]
                                        (reset! captured [host dir])
                                        nil)]
      (eval-tool/execute {:code "(+ 1 2)"} nil nil {:cwd "/project"})
      (is (= ["127.0.0.1" "/project"] @captured)))))

(deftest test-cancel-is-passed-to-eval-code
  (let [captured (atom nil)
        signal (atom false)]
    (with-redefs [nrepl/eval-code (fn [opts]
                                    (reset! captured opts)
                                    {:ok? true :values ["1"] :out "" :err ""
                                     :events [[:value "1"]] :env :clj})]
      (eval-tool/execute {:code "1" :port 7888} nil signal {:cwd "/project"})
      (is (fn? (:cancel @captured)))
      (is (false? ((:cancel @captured))))
      (reset! signal true)
      (is (true? ((:cancel @captured)))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Formatting
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-success-content-interleaves-output-and-values
  (let [result {:ok? true
                :values ["3"]
                :out "hello\n"
                :err ""
                :ns "user"
                :env :clj
                :session "sess-1"
                :events [[:out "hello\n"] [:value "3"]]}
        [r opts] (execute-with {:code "(println :hello) (+ 1 2)" :port 7888} result)]
    (is (not (:is-error r)))
    (is (= "hello\n=> 3" (:content r)))
    (is (= 7888 (:port opts)))
    (is (= "(println :hello) (+ 1 2)" (:code opts)))
    (is (= {:values ["3"] :out "hello\n" :err "" :ns "user" :env "clj"
            :session "sess-1" :repaired? false :timed-out? false
            :cancelled? false :error? false}
           (dissoc (:details r) :code)))))

(deftest test-eval-error-is-flagged-and-named
  (let [result {:ok? false
                :ex "class java.lang.ArithmeticException"
                :values ["#error {:cause \"Divide by zero\"}"]
                :out ""
                :err "  at clojure.lang..."
                :events [[:value "#error {:cause \"Divide by zero\"}"]
                         [:err "  at clojure.lang..."]]}
        [r _] (execute-with {:code "(/ 1 0)" :port 7888} result)]
    (is (:is-error r))
    (is (str/starts-with? (:content r) "Eval error (class java.lang.ArithmeticException)"))
    (is (str/includes? (:content r) "=> #error"))
    (is (str/includes? (:content r) "at clojure.lang"))))

(deftest test-repaired-delimiters-are-reported
  (let [result {:ok? true :values ["#'user/foo"] :out "" :err ""
                :events [[:value "#'user/foo"]] :env :clj}
        [r opts] (execute-with {:code "(defn foo [x]\n  (+ x 1" :port 7888} result)]
    (is (str/includes? (:content r) "note: repaired unbalanced delimiters"))
    (is (= "(defn foo [x]\n  (+ x 1))" (:code opts))
        "evaluation receives the repaired code")
    (is (true? (get-in r [:details :repaired?])))))

(deftest test-timeout-is-reported
  (let [result {:ok? false :timeout? true :values [] :out "" :err ""
                :events [] :env :clj}
        [r _] (execute-with {:code "(Thread/sleep 10000)" :port 7888 :timeout 500} result)]
    (is (:is-error r))
    (is (str/includes? (:content r) "timed out after 500 ms"))
    (is (true? (get-in r [:details :timed-out?])))))

(deftest test-cancelled-is-reported
  (let [result {:ok? false :cancelled? true :values [] :out "" :err ""
                :events [] :env :clj}
        [r _] (execute-with {:code "(Thread/sleep 100000)" :port 7888} result)]
    (is (:is-error r))
    (is (str/includes? (:content r) "cancelled"))
    (is (true? (get-in r [:details :cancelled?])))
    (is (not (str/includes? (:content r) "Eval error")))))

(deftest test-connection-error-is-reported
  (let [[r _] (execute-with {:code "(+ 1 2)" :port 7888} nil
                            (ex-info "Could not connect to nREPL at 127.0.0.1:7888 — Connection refused"
                                     {:type :nrepl-connect}))]
    (is (:is-error r))
    (is (str/starts-with? (:content r) "nREPL error: Could not connect"))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Renderers
;; ═══════════════════════════════════════════════════════════════════════════════

(deftest test-call-renderer-and-title
  (testing "label, code and port render on the call line"
    (let [lines (plain (eval-tool/render-eval-call "clojure_eval"
                                                   {:code "(+ 1 2)" :port 7888}
                                                   theme/dark-theme 60 {}) 60)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "clojure> (+ 1 2)"))
      (is (str/includes? (first lines) ":7888"))))
  (testing "a large multi-line payload is capped when collapsed"
    (let [code (str/join "\n" (mapv #(str "(println " % ")") (range 20)))
          lines (plain (eval-tool/render-eval-call "clojure_eval" {:code code}
                                                   theme/dark-theme 60 {:expanded false}) 60)]
      (is (= 9 (count lines)) "8 visual head lines + the ctrl+o hint")
      (is (str/starts-with? (first lines) "clojure> (println 0)"))
      (is (not-any? #(str/includes? % "(println 19)") lines) "the tail is hidden")
      (is (some #(str/includes? % "(+12 lines,") lines) "20 - 8 hidden")))
  (testing "expanded renders the payload verbatim"
    (let [code (str/join "\n" (mapv #(str "line " %) (range 12)))
          lines (plain (eval-tool/render-eval-call "clojure_eval" {:code code}
                                                   theme/dark-theme 60 {:expanded true}) 60)]
      (is (= 12 (count lines)))
      (is (str/includes? (peek lines) "line 11"))))
  (testing "the quiet title keeps the first line, shortened"
    (is (= "clj $ (+ 1 2)" (eval-tool/title {:code "(+ 1 2)\n(+ 3 4)"})))
    (let [long-title (eval-tool/title {:code (apply str (repeat 200 "x"))})]
      (is (str/ends-with? long-title "…"))
      (is (= (+ (count "clj $ ") 81) (count long-title))))
    (is (nil? (eval-tool/title {})))))
