(ns kmet.libs.test-ranking
  "The shared weighted ranker: the order `mcp search` and `tool_search` both
   get — a name match outranks a description match at the field weights, the
   limit takes the best matches, and a sparse document never throws."
  (:require [clojure.test :as t]
            [kmet.libs.ranking :as ranking]))

(defn- names [ranked]
  (mapv :name ranked))

(t/deftest test-a-name-match-outranks-a-description-match
  ;; :name weighs 12, :description 5 — the asymmetry every caller relies on, so
  ;; a tool named for the query beats one that merely mentions it
  (t/is (= ["screenshot" "browser"]
           (names (ranking/rank [{:name "browser" :description "take a screenshot"}
                                 {:name "screenshot" :description "capture the screen"}]
                                "screenshot")))))

(t/deftest test-the-server-field-ranks-between-name-and-description
  ;; the namespace/server text groups a source's tools; it is not as strong as
  ;; the tool's own name and stronger than a description mention
  (t/is (= ["dev-radius-a" "b" "c"]
           (names (ranking/rank [{:name "c" :description "notes about dev-radius"}
                                 {:name "b" :description "unrelated" :server "dev-radius"}
                                 {:name "dev-radius-a"}]
                                "dev-radius")))))

(t/deftest test-limit-takes-the-best-matches
  (let [documents [{:name "alpha" :description "search"}
                   {:name "beta" :description "search"}
                   {:name "gamma" :description "search"}]]
    (t/is (= ["alpha" "beta"] (names (ranking/rank documents "search" 2))))
    (t/is (= 3 (count (ranking/rank documents "search"))))))

(t/deftest test-equal-scores-come-back-in-name-order
  ;; a stable order matters to a caller that records what it loaded
  (t/is (= ["a" "b" "c"]
           (names (ranking/rank [{:name "c" :description "same"}
                                 {:name "a" :description "same"}
                                 {:name "b" :description "same"}]
                                "same")))))

(t/deftest test-a-sparse-document-does-not-throw
  (t/is (= ["bare"] (names (ranking/rank [{:name "bare"}] "bare"))))
  (t/is (empty? (ranking/rank [{:description "no name"}] "anything")))
  (t/is (empty? (ranking/rank [] "anything"))))

(t/deftest test-no-match-is-not-a-zero-score
  ;; a document with no matching term is dropped, not ranked last
  (t/is (empty? (ranking/rank [{:name "read" :description "read a file"}] "screenshot"))))

(t/deftest test-configured-keywords-raise-a-match
  ;; searchKeywords are discrete phrases a server author chose, so they beat an
  ;; incidental description word
  (let [documents [{:name "web" :description "fetch a page"}
                   {:name "net" :description "unrelated" :keywords ["fetch a page"]}]]
    (t/is (= ["net" "web"] (names (ranking/rank documents "fetch a page"))))))
