(ns kmet.tasks.test-bench
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.tasks.bench.metrics :as metrics]
            [kmet.tasks.bench.report :as report]))

(defn- session [entries]
  {:id "test-session" :file "test.ednl" :entries (atom entries)})

(defn- metrics [tokens cost turns calls result-tokens]
  {:tokens {:input tokens :output 0 :cache-read 0 :cache-write 0 :total tokens}
   :cost cost
   :turns turns
   :tool-calls calls
   :tool-result-tokens result-tokens
   :first-prompt-tokens 100
   :errors 0
   :error-kinds {}
   :by-tool {"bash" {:calls calls :tokens result-tokens}}
   :declared-tools ["bash"]
   :eval {:calls 0 :code-chars 0 :actions {}}})

(def ^:private entries
  [{:role :tool-change
    :added [{:name "eval" :description "..." :parameters {:type "object"}}]
    :removed []}
   {:role :assistant
    :content []
    :tool-calls [{:name "eval"
                  :arguments {:code "(do (slurp \"a\") (read \"b\" 1 2) (emit 1))"}}]
    :usage {:input_tokens 100
            :output_tokens 20
            :cache_read_input_tokens 10
            :cache_creation_input_tokens 5
            :cost {:total 0.1}}}
   {:role :tool
    :tool-name "eval"
    :is-error true
    :content [{:type :tool_result :content "Unable to resolve symbol: x"}]
    :result-tokens 2}
   {:role :assistant
    :content []
    :tool-calls []
    :usage {:prompt_tokens 50 :completion_tokens 10}}])

(deftest test-session-metrics
  (let [m (metrics/session-metrics (session entries))]
    (testing "usage, cost and turns come from the session stats"
      (is (= 2 (:turns m)))
      (is (= 1 (:tool-calls m)))
      (is (= {:input 150 :output 30 :cache-read 10 :cache-write 5 :total 195}
             (:tokens m)))
      (is (= 0.1 (:cost m))))
    (testing "the first request is the fixed-prefix measurement"
      (is (= 115 (:first-prompt-tokens m)))
      (is (= 20 (:first-output-tokens m))))
    (testing "tool results and declared tools are preserved"
      (is (= 2 (:tool-result-tokens m)))
      (is (= ["eval"] (:declared-tools m)))
      (is (pos? (:tool-schema-chars m)))
      (is (= {"eval" {:calls 1 :tokens 2}} (:by-tool m))))
    (testing "errors and eval actions are classified"
      (is (= 1 (:errors m)))
      (is (= {:unresolved-symbol 1} (:error-kinds m)))
      (is (= 1 (get-in m [:eval :actions :slurp])))
      (is (= 1 (get-in m [:eval :actions :read])))
      (is (= 1 (get-in m [:eval :actions :emit]))))))

(deftest test-initial-declared-tools
  (is (= #{"a"}
         (set (keys (metrics/initial-declared-tools
                     [{:role :tool-change
                       :added [{:name "a"}]
                       :removed []}
                      {:role :assistant}
                      {:role :tool-change
                       :added [{:name "b"}]
                       :removed []}]))))))

(deftest test-declared-tools-fold
  (is (= #{"a"}
         (set (keys (metrics/declared-tools
                     [{:role :tool-change
                       :added [{:name "a"} {:name "b"}]
                       :removed []}
                      {:role :tool-change
                       :added []
                       :removed ["b"]}])))))
  (is (= ["a"]
         (:declared-tools
          (metrics/session-metrics
           (session [{:role :tool-change
                      :added [{:name "a"} {:name "b"}]
                      :removed []}
                     {:role :tool-change
                      :added []
                      :removed ["b"]}]))))))

(deftest test-error-kind
  (is (= :unresolved-symbol
         (metrics/error-kind "Unable to resolve symbol: x")))
  (is (= :process
         (metrics/error-kind "Cannot run program \"cd /tmp && ls\"")))
  (is (= :read-args
         (metrics/error-kind "read line range expects (read path start end)")))
  (is (= :other (metrics/error-kind "something else"))))

(defn- run [mode tokens cost turns calls result-tokens ok?]
  {:task "edit"
   :mode mode
   :repeat 1
   :ok? ok?
   :duration-ms 1200
   :metrics (metrics tokens cost turns calls result-tokens)})

(deftest test-aggregate
  (let [runs [(run "bash" 1000 0.01 2 1 10 true)
              (run "bash" 2000 0.02 4 3 30 true)
              (run "eval" 3000 0.03 5 4 40 false)]
        a (report/aggregate runs)]
    (is (= 3 (:runs a)))
    (is (= 2 (:verified a)))
    (is (= 1 (:failed a)))
    (is (zero? (get-in a [:tokens :cache-read])))
    (is (= ["bash"] (:declared-tools a)))))

(deftest test-compare-and-diagnostics
  (let [base [(run "bash" 1000 0.01 2 1 10 true)]
        cand [(run "bash" 1500 0.02 3 2 20 false)]
        cmp (report/compare-runs base cand)
        diag (report/diagnostics base cand)]
    (is (= 1 (:pairs cmp)))
    (is (= 0 (get-in cmp [:missing :baseline])))
    (is (= 1000 (get-in cmp [:baseline :tokens :total])))
    (is (some #(re-find #"Correctness" %) diag))
    (is (some #(re-find #"tokens" %) diag))))

(deftest test-render
  (let [runs [(run "bash" 1000 0.01 2 1 10 true)
              (run "eval" 1500 0.02 3 2 20 true)]
        result {:label "test"
                :created-at "now"
                :model "test/model"
                :thinking "off"
                :suite "suite"
                :kmet "kmet.sh"
                :modes [{:label "bash" :tools "bash"}
                        {:label "eval" :tools "eval"}]
                :tasks [{:id "edit" :prompt "p"}]
                :runs runs
                :environment {:git-sha "abc" :git-dirty? false}}
        md (report/render-run-report result)]
    (is (re-find #"Mode summary" md))
    (is (re-find #"eval vs bash" md))
    (is (re-find #"Diagnostics" md))
    (is (re-find #"1.50x" md)))
  (let [base {:label "base" :created-at "now" :model "m" :runs [(run "bash" 1000 0.01 2 1 10 true)]}
        cand {:label "cand" :created-at "now" :model "m" :runs [(run "bash" 1500 0.02 3 2 20 true)]}
        md (report/render-compare-report base cand)]
    (is (re-find #"Paired totals" md))
    (is (re-find #"1.50x" md))))
