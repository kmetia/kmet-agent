(ns kmet.libs.test-edit-diff
  "The fuzzy-match normalization's trailing-whitespace strip: str/trimr
   replaced a per-line #\"\\s+$\" regex (an anchored-regex scan costs ~0.26 ms
   per line on jolt — jolt-bugs.md #1062), so the regex form stays here as the
   equivalence oracle."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.edit-diff :as ed]))

(defn- normalize-for-fuzzy-match-regex
  "The pre-jolt#1062 implementation (per-line `\\s+$` replace)."
  [text]
  (-> (java.text.Normalizer/normalize text java.text.Normalizer$Form/NFKC)
      (as-> s (->> (str/split-lines s)
                   (map #(str/replace % #"\s+$" ""))
                   (str/join "\n")))
      (str/replace #"[\u2018\u2019\u201A\u201B]" "'")
      (str/replace #"[\u201C\u201D\u201E\u201F]" "\"")
      (str/replace #"[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]" "-")
      (str/replace #"[\u00A0\u2002-\u200A\u202F\u205F\u3000]" " ")))

(deftest test-normalize-for-fuzzy-match-equivalent-to-the-regex
  (testing "the str/trimr strip is output-equivalent to the per-line \\s+$
            regex it replaced on a source-like corpus (ASCII whitespace,
            smart quotes/dashes, Unicode spaces the later replace handles)"
    (let [corpus ["(defn f [x]  "
                  "  (let [a 1]   \t"
                  "plain line"
                  ""
                  "trailing tab\t"
                  "trailing VT\u000B"
                  "trailing FF\u000C"
                  "nbsp-trailing\u00A0"
                  "em-space\u2003"
                  "ideographic\u3000"
                  "smart \u201Cquotes\u201D and \u2018apos\u2019"
                  "dash \u2014 dash"
                  "  mixed \t  "
                  "no-trail"]
          text (str/join "\n" corpus)]
      (is (= (normalize-for-fuzzy-match-regex text)
             (#'ed/normalize-for-fuzzy-match text))))))

(deftest test-normalize-for-fuzzy-match-unicode-trailing-whitespace
  (testing "trimr also strips the Unicode trailing whitespace Java's regex \\s
            misses — pi's JS \\s covers it, and the old regex left it in"
    (is (= "x" (#'ed/normalize-for-fuzzy-match "x\u1680")))
    (is (= "x" (#'ed/normalize-for-fuzzy-match "x\u2028")))
    (is (= "x\u2028" (normalize-for-fuzzy-match-regex "x\u2028")))))

(deftest test-fuzzy-find-text-ignores-trailing-whitespace
  (testing "the user-facing contract the normalization exists for: a file
            whose lines picked up trailing spaces still matches the exact
            oldText through the fuzzy pass"
    (let [content "alpha   \nbeta\t\ngamma"
          match (ed/fuzzy-find-text content "alpha\nbeta\ngamma")]
      (is (true? (:found match)))
      (is (true? (:used-fuzzy? match))))))
