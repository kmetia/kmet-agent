(ns kmet.tui.test-fuzzy
  "Tests for kmet.tui.fuzzy — port of pi's fuzzy.test.ts."
  (:require [clojure.test :as t]
            [kmet.tui.fuzzy :as f]))

(t/deftest fuzzy-match-empty-query
  (t/is (= {:matches true :score 0} (f/fuzzy-match "" "anything"))))

(t/deftest fuzzy-match-query-longer-than-text
  (t/is (false? (:matches (f/fuzzy-match "longquery" "short")))))

(t/deftest fuzzy-match-exact-match-good-score
  (let [r (f/fuzzy-match "test" "test")]
    (t/is (true? (:matches r)))
    (t/is (neg? (:score r)) "score should be negative due to consecutive bonuses")))

(t/deftest fuzzy-match-characters-in-order
  (t/is (true? (:matches (f/fuzzy-match "abc" "aXbXc"))))
  (t/is (false? (:matches (f/fuzzy-match "abc" "cba")))))

(t/deftest fuzzy-match-case-insensitive
  (t/is (true? (:matches (f/fuzzy-match "ABC" "abc"))))
  (t/is (true? (:matches (f/fuzzy-match "abc" "ABC")))))

(t/deftest fuzzy-match-consecutive-better-than-scattered
  (let [consecutive (f/fuzzy-match "foo" "foobar")
        scattered (f/fuzzy-match "foo" "f_o_o_bar")]
    (t/is (true? (:matches consecutive)))
    (t/is (true? (:matches scattered)))
    (t/is (< (:score consecutive) (:score scattered)))))

(t/deftest fuzzy-match-word-boundary-better
  (let [at-boundary (f/fuzzy-match "fb" "foo-bar")
        not-at-boundary (f/fuzzy-match "fb" "afbx")]
    (t/is (true? (:matches at-boundary)))
    (t/is (true? (:matches not-at-boundary)))
    (t/is (< (:score at-boundary) (:score not-at-boundary)))))

(t/deftest fuzzy-match-swapped-alpha-numeric
  (t/is (true? (:matches (f/fuzzy-match "codex52" "gpt-5.2-codex")))))

(t/deftest fuzzy-filter-empty-query-returns-all
  (t/is (= ["apple" "banana" "cherry"]
           (f/fuzzy-filter ["apple" "banana" "cherry"] "" identity))))

(t/deftest fuzzy-filter-filters-out-non-matching
  (let [result (f/fuzzy-filter ["apple" "banana" "cherry"] "an" identity)]
    (t/is (some #{"banana"} result))
    (t/is (not (some #{"apple"} result)))
    (t/is (not (some #{"cherry"} result)))))

(t/deftest fuzzy-filter-sorts-by-match-quality
  (t/is (= "app" (first (f/fuzzy-filter ["a_p_p" "app" "application"] "app" identity)))))

(t/deftest fuzzy-filter-prioritizes-exact-matches
  (t/is (= ["cl" "clone"] (f/fuzzy-filter ["clone" "cl"] "cl" identity))))

(t/deftest fuzzy-filter-custom-get-text
  (let [items [{:name "foo" :id 1}
               {:name "bar" :id 2}
               {:name "foobar" :id 3}]
        result (f/fuzzy-filter items "foo" :name)]
    (t/is (= 2 (count result)))
    (t/is (some #(contains? (set (map :name result)) %) ["foo" "foobar"]))))

(t/deftest fuzzy-filter-slash-separated-tokens
  (let [item {:id "gpt-5.5" :provider "openai-codex"}
        result (f/fuzzy-filter [item] "openai-codex/gpt-5.5"
                               (fn [m] (str (:id m) " " (:provider m))))]
    (t/is (= [item] result))))
