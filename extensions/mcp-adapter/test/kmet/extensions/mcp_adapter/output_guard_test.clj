(ns kmet.extensions.mcp-adapter.output-guard-test
  "The model-facing output bound (pi mcp-output-guard.ts): option
   resolution, the head-preview plus spill notice, the UTF-8 safe cut, and
   the bound kept (or summarized) in the tool's :details."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.json :as json]
            [kmet.extensions.mcp-adapter.output-guard :as guard]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

(defn- spill
  "Delete a spill file a guard result pointed at."
  [result]
  (when-let [path (get-in result [:guard :full-output-path])]
    (tu/delete-tree! (fs/parent path))))

(defn- lines-text [n]
  (str/join "\n" (map #(str "line-" %) (range n))))

;; ─── Options ──────────────────────────────────────────────────────────────

(deftest resolve-options
  (testing "no settings means the pi defaults"
    (is (= {:enabled true
            :max-bytes guard/default-max-bytes
            :max-lines guard/default-max-lines
            :details-max-bytes guard/default-details-max-bytes}
           (guard/resolve-options {}))))
  (testing "false disables the guard"
    (is (false? (:enabled (guard/resolve-options {:output-guard false})))))
  (testing "a tuning map overrides the numbers"
    (let [opts (guard/resolve-options {:output-guard {:max-bytes 100
                                                      :max-lines 5
                                                      :details-max-bytes 1000}})]
      (is (true? (:enabled opts)))
      (is (= 100 (:max-bytes opts)))
      (is (= 5 (:max-lines opts)))
      (is (= 1000 (:details-max-bytes opts)))))
  (testing "non-positive and non-numeric tunings fall back to the defaults"
    (let [opts (guard/resolve-options {:output-guard {:max-bytes 0
                                                      :max-lines "many"
                                                      :details-max-bytes -3}})]
      (is (= guard/default-max-bytes (:max-bytes opts)))
      (is (= guard/default-max-lines (:max-lines opts)))
      (is (= guard/default-details-max-bytes (:details-max-bytes opts)))))
  (testing "nothing in the environment disables the guard by accident"
    (is (contains? #{"0" "1" "false" "true" "no" "yes" "off" "on" nil}
                   (some-> (System/getenv "MCP_OUTPUT_GUARD") str/trim str/lower-case)))))

(deftest ^:slow env-kill-switch-in-a-child-process
  ;; The switch reads the process environment, so it needs a fresh process:
  ;; each row is [MCP_OUTPUT_GUARD settings-form expected :enabled].
  (let [run (fn [env-value settings-form]
              (let [result (p/sh {:out :string :err :string
                                  :extra-env {"MCP_OUTPUT_GUARD" env-value}}
                                 "bb" "-cp" (System/getProperty "java.class.path")
                                 "-e" (str "(require '[kmet.extensions.mcp-adapter.output-guard :as g])"
                                           "(prn (:enabled (g/resolve-options " settings-form ")))"))]
                (when-not (zero? (:exit result))
                  (throw (ex-info (str "child failed: " (:err result))
                                  {:type ::child-failed})))
                (read-string (:out result))))]
    (doseq [[env-value settings-form expected]
            [["0" "{}" false]
             ["off" "{}" false]
             ["1" "{:output-guard false}" true]
             ["yes" "{}" true]]]
      (is (= expected (run env-value settings-form))
          (str "MCP_OUTPUT_GUARD=" env-value " with " settings-form)))))

;; ─── Text guard ───────────────────────────────────────────────────────────

(deftest small-text-passes-through
  (let [result (guard/guard-text "hello" {:max-bytes 1000 :max-lines 10})]
    (is (= "hello" (:text result)))
    (is (nil? (:guard result)))))

(deftest empty-text-is-labelled
  (is (= "(empty result)"
         (:text (guard/guard-text "" {:max-bytes 100 :max-lines 10}))))
  (is (= "(empty result)"
         (:text (guard/guard-text "   \n " {:max-bytes 100 :max-lines 10})))))

(deftest line-overflow-is-truncated-and-spilled
  (let [result (guard/guard-text (lines-text 50) {:max-bytes 100000 :max-lines 5})
        guard (:guard result)]
    (testing "the guard reports the truncation"
      (is (true? (:truncated guard)))
      (is (= 50 (:original-lines guard)))
      (is (< (:returned-lines guard) 50)))
    (testing "the text ends with the spill notice"
      (is (str/includes? (:text result) "[MCP text output truncated: original 50 lines"))
      (is (str/includes? (:text result) "Full text saved to: "))
      (is (str/includes? (:text result) "use read with offset/limit or grep to inspect")))
    (testing "the spilled file holds the full text"
      (let [path (:full-output-path guard)]
        (is (fs/exists? path))
        (is (= (lines-text 50) (slurp path)))))
    (spill result)))

(deftest byte-overflow-is-truncated
  (let [result (guard/guard-text (apply str (repeat 500 "abcdef"))
                                 {:max-bytes 400 :max-lines 10000})
        guard (:guard result)]
    (is (true? (:truncated guard)))
    (is (<= (:returned-bytes guard) 400))
    (is (< (:returned-bytes guard) (:original-bytes guard)))
    (spill result)))

(deftest a-budget-below-the-notice-still-reports-why
  (let [result (guard/guard-text (apply str (repeat 400 "x"))
                                 {:max-bytes 150 :max-lines 10})]
    (testing "the notice survives even when it alone exceeds the budget"
      (is (str/includes? (:text result) "truncated"))
      (is (str/includes? (:text result) "Full text saved to: ")))
    (spill result)))

(deftest multibyte-text-is-cut-on-a-character-boundary
  (let [text (apply str (repeat 300 "é"))
        result (guard/guard-text text {:max-bytes 400 :max-lines 10000})
        preview (first (str/split (:text result) #"\n\n" 2))]
    (testing "the preview is a whole-character prefix of the original"
      (is (seq preview))
      (is (str/starts-with? text preview))
      (is (= (count preview) (count (str/replace preview #"[^é]" "")))))
    (spill result)))

(deftest disabled-guard-passes-everything
  (let [result (guard/guard-text (lines-text 5000) {:enabled false})]
    (is (nil? (:guard result)))
    (is (= (lines-text 5000) (:text result)))))

;; ─── Details bound ────────────────────────────────────────────────────────

(deftest bound-mcp-result-keeps-a-small-result
  (let [raw {:content [{:type "text" :text "hi"}] :isError false}]
    (is (= raw (guard/bound-mcp-result raw 10000)))))

(deftest bound-mcp-result-summarizes-a-large-result
  (let [raw {:content (vec (repeat 40 {:type "text" :text (apply str (repeat 500 "x"))}))
             :isError true}
        result (guard/bound-mcp-result raw 1000)]
    (testing "the raw payload is replaced by a summary"
      (is (true? (:omitted result)))
      (is (true? (:is-error result)))
      (is (= 40 (:content-blocks result)))
      (is (<= 20 (count (:content-summary result))))
      (is (> (:raw-result-bytes result) 1000)))
    (testing "the raw JSON is spilled with a path"
      (let [path (:full-result-path result)]
        (is (fs/exists? path))
        (is (= raw (json/parse-string (slurp path) true)))
        (tu/delete-tree! (fs/parent path))))
    (testing "text blocks are described by size, not content"
      (let [block (first (:content-summary result))]
        (is (= "text" (:type block)))
        (is (true? (:text-omitted block)))
        (is (pos? (:bytes block)))))))

(deftest guarded-details-carries-only-present-parts
  (is (= {} (guard/guarded-details nil nil)))
  (is (= {:output-guard {:truncated true}}
         (guard/guarded-details {:truncated true} nil)))
  (is (= {:mcp-result {:a 1}}
         (guard/guarded-details nil {:a 1})))
  (is (= {:output-guard {:truncated true} :mcp-result {:a 1}}
         (guard/guarded-details {:truncated true} {:a 1}))))
