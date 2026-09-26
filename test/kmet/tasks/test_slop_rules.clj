(ns kmet.tasks.test-slop-rules
  "Rule-pack tests for kmet.tasks.slop.rules: one positive case per rule,
   plus the false-positive guards that keep verbosity honest."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.tasks.slop :as slop]
            [kmet.tasks.slop.rules :as rules]))

(defn- rule-set [src]
  (into #{} (map :rule) (rules/ast-findings (slop/parse-text src))))

(deftest conditional-rules
  (testing "flagged"
    (is (contains? (rule-set "(if (not x) 1 2)") "if-not"))
    (is (contains? (rule-set "(when (not x) 1)") "when-not"))
    (is (contains? (rule-set "(if x true false)") "if-true-false"))
    (is (contains? (rule-set "(if x 1 nil)") "if-nil-branch"))
    (is (contains? (rule-set "(if-let [x e] 1 nil)") "if-let-nil-branch"))
    (is (contains? (rule-set "(when x (do 1 2))") "when-do")))
  (testing "not flagged"
    (is (empty? (rule-set "(if x 1 2)")))
    (is (empty? (rule-set "(when x 1 2)")))))

(deftest boolean-comparison-rules
  (is (contains? (rule-set "(= nil x)") "not-nil"))
  (is (contains? (rule-set "(not= nil x)") "not-nil"))
  (is (contains? (rule-set "(= 0 (count xs))") "empty-count"))
  (is (contains? (rule-set "(zero? (count xs))") "empty-count"))
  (is (contains? (rule-set "(> (count xs) 0)") "pos-count"))
  (is (contains? (rule-set "(pos? (count xs))") "pos-count"))
  (is (contains? (rule-set "(not (empty? xs))") "not-empty"))
  (is (empty? (rule-set "(= x y)")))
  (is (empty? (rule-set "(count xs)"))))

(deftest defensive-rules
  (is (contains? (rule-set "(if (nil? x) nil (f x))") "nil-guard"))
  (is (contains? (rule-set "(when (some? x) (f x))") "some-guard"))
  (is (contains? (rule-set "(when-not (nil? x) (f x))") "nil-guard-when-not"))
  (is (empty? (rule-set "(when (some? x) (f y))"))))

(deftest dict-rules
  (is (contains? (rule-set "(get m k nil)") "get-nil-default"))
  (is (contains? (rule-set "(if (contains? m k) (get m k) 0)") "contains-get"))
  (is (contains? (rule-set "(merge {} m)") "merge-empty"))
  (is (empty? (rule-set "(get m k)")))
  (is (empty? (rule-set "(assoc m k 1)"))))

(deftest loop-rules
  (is (contains? (rule-set "(into [] (map f xs))") "into-mapv"))
  (is (contains? (rule-set "(into [] (filter p xs))") "into-filterv"))
  (is (contains? (rule-set "(into [] (remove p xs))") "into-filterv"))
  (is (contains? (rule-set "(reduce conj [] xs)") "reduce-conj-vec"))
  (is (contains? (rule-set "(apply str (interpose :sep xs))") "apply-str-interpose"))
  (is (empty? (rule-set "(into [] xs)"))))

(deftest abstraction-rules
  (is (contains? (rule-set "(fn [x] (f x))") "redundant-lambda"))
  (is (contains? (rule-set "#(f %)") "redundant-lambda"))
  (is (contains? (rule-set "(let [x (f)] (g x))") "redundant-let"))
  (is (empty? (rule-set "(fn [x y] (f y x))")))
  (is (empty? (rule-set "(let [x (f)] (g x) (h x))"))))

(deftest misc-rules
  (is (contains? (rule-set "(cons x nil)") "cons-nil"))
  (is (contains? (rule-set "(-> x)") "thread-noop"))
  (is (contains? (rule-set "(do x)") "do-single"))
  (is (empty? (rule-set "(do x y)"))))

(deftest trivial-wrapper-structural-rule
  (let [defs (rules/definitions
               (slop/parse-text (str "(defn g [x] (inc x))\n"
                                     "(defn f [x] (g x))\n"
                                     "(defn h [x] (+ x 1))")))]
    (is (= ["f"] (mapv (comp str :name) (rules/trivial-wrappers defs)))))
  (testing "forwarding to a non-scanned fn is not flagged"
    (let [defs (rules/definitions (slop/parse-text "(defn f [x] (external x))"))]
      (is (empty? (rules/trivial-wrappers defs))))))
