(ns kmet.tasks.slop.rules
  "Clojure adaptation of the SlopCodeBench AST rule pack used by verbosity.
   Each rule matches a form that could be semantically condensed and flags
   the form's start line; `ast-findings` returns every (rule, node) hit.
   SCBench's own pack is Python-only, so this is the Clojure leg of the
   union the metric counts.

   Categories follow scb-check: boolean_and_comparison, conditionals,
   defensive, dict_patterns, loops_and_comprehensions, abstractions, misc -
   plus the structural trivial-wrapper rule (a defn that only forwards its
   params to another scanned defn).")

;; ---------------------------------------------------------------- helpers

(defn- sym-named? [x s]
  (and (symbol? x) (= (name x) s)))

(defn- head-named? [form s]
  (and (seq? form) (sym-named? (first form) s)))

(defn- line-of [node] (:row (meta node)))

(defn- call? [form]
  (and (seq? form) (symbol? (first form))))

(defn- not-form? [x]
  (and (seq? x) (sym-named? (first x) "not") (= 2 (count x))))

(defn- count-form? [x]
  (and (head-named? x "count") (= 2 (count x))))

(defn- nil-test? [x]
  (and (head-named? x "nil?") (= 2 (count x)) (symbol? (second x))))

(defn- some-test? [x]
  (and (head-named? x "some?") (= 2 (count x)) (symbol? (second x))))

(defn- forward-single?
  "True when CALL forwards exactly parameter X: (f x)."
  [call x]
  (and (call? call) (= 2 (count call)) (= x (second call))))

(defn- count-occurrences
  "Occurrences of symbol SYM in FORM, skipping quoted data."
  [sym form]
  (cond
    (and (seq? form) (sym-named? (first form) "quote")) 0
    (= sym form) 1
    (map? form) (reduce + 0
                        (map (fn [[k v]]
                               (+ (count-occurrences sym k)
                                  (count-occurrences sym v)))
                             form))
    (coll? form) (reduce + 0 (map #(count-occurrences sym %) form))
    :else 0))

(defn- empty-vector? [x]
  (and (vector? x) (empty? x)))

(defn- empty-map? [x]
  (and (map? x) (empty? x)))

;; ------------------------------------------------------------------ rules

(def ^:private rules
  [{:id "if-not" :category "conditionals" :head "if"
    :match (fn [f] (and (= 4 (count f)) (not-form? (second f))))}

   {:id "when-not" :category "conditionals" :head "when"
    :match (fn [f] (and (= 3 (count f)) (not-form? (second f))))}

   {:id "if-true-false" :category "conditionals" :head "if"
    :match (fn [f]
             (and (= 4 (count f))
                  (or (and (= true (nth f 2)) (= false (nth f 3)))
                      (and (= false (nth f 2)) (= true (nth f 3))))))}

   {:id "if-nil-branch" :category "conditionals" :head "if"
    :match (fn [f]
             (and (= 4 (count f))
                  (or (nil? (nth f 2)) (nil? (nth f 3)))))}

   {:id "if-let-nil-branch" :category "conditionals" :head "if-let"
    :match (fn [f] (and (= 4 (count f)) (nil? (nth f 3))))}

   {:id "if-some-nil-branch" :category "conditionals" :head "if-some"
    :match (fn [f] (and (= 4 (count f)) (nil? (nth f 3))))}

   {:id "when-do" :category "conditionals"
    :head #{"when" "when-not" "when-let" "when-some" "when-first"}
    :match (fn [f]
             (let [body (drop 2 f)]
               (and (= 1 (count body))
                    (seq? (first body))
                    (sym-named? (first (first body)) "do"))))}

   {:id "not-nil" :category "boolean_and_comparison" :head #{"=" "not="}
    :match (fn [f]
             (and (= 3 (count f))
                  (or (nil? (second f)) (nil? (nth f 2)))))}

   {:id "empty-count" :category "boolean_and_comparison" :head #{"=" "zero?" "<"}
    :match (fn [f]
             (let [c (count f)]
               (or (and (= 2 c) (head-named? f "zero?") (count-form? (second f)))
                   (and (= 3 c)
                        (let [a (second f) b (nth f 2)]
                          (or (and (head-named? f "=") (= 0 a) (count-form? b))
                              (and (head-named? f "=") (count-form? a) (= 0 b))
                              (and (head-named? f "<") (count-form? a) (= 1 b))))))))}

   {:id "pos-count" :category "boolean_and_comparison" :head #{"pos?" ">"}
    :match (fn [f]
             (let [c (count f)]
               (or (and (= 2 c) (head-named? f "pos?") (count-form? (second f)))
                   (and (= 3 c) (head-named? f ">") (count-form? (second f))
                        (= 0 (nth f 2))))))}

   {:id "not-empty" :category "boolean_and_comparison" :head "not"
    :match (fn [f]
             (and (= 2 (count f)) (head-named? (second f) "empty?")))}

   {:id "nil-guard" :category "defensive" :head "if"
    :match (fn [f]
             (and (= 4 (count f))
                  (nil-test? (second f))
                  (nil? (nth f 2))
                  (forward-single? (nth f 3) (second (second f)))))}

   {:id "some-guard" :category "defensive" :head "when"
    :match (fn [f]
             (and (= 3 (count f))
                  (some-test? (second f))
                  (forward-single? (nth f 2) (second (second f)))))}

   {:id "nil-guard-when-not" :category "defensive" :head "when-not"
    :match (fn [f]
             (and (= 3 (count f))
                  (nil-test? (second f))
                  (forward-single? (nth f 2) (second (second f)))))}

   {:id "get-nil-default" :category "dict_patterns" :head "get"
    :match (fn [f] (and (= 4 (count f)) (nil? (nth f 3))))}

   {:id "contains-get" :category "dict_patterns" :head "if"
    :match (fn [f]
             (and (= 4 (count f))
                  (head-named? (second f) "contains?")
                  (= 3 (count (second f)))
                  (head-named? (nth f 2) "get")
                  (= 3 (count (nth f 2)))
                  (= (second (second f)) (second (nth f 2)))
                  (= (nth (second f) 2) (nth (nth f 2) 2))))}

   {:id "merge-empty" :category "dict_patterns" :head "merge"
    :match (fn [f]
             (and (= 3 (count f))
                  (or (empty-map? (second f)) (empty-map? (nth f 2)))))}

   {:id "into-mapv" :category "loops_and_comprehensions" :head "into"
    :match (fn [f]
             (and (= 3 (count f))
                  (empty-vector? (second f))
                  (head-named? (nth f 2) "map")))}

   {:id "into-filterv" :category "loops_and_comprehensions" :head "into"
    :match (fn [f]
             (and (= 3 (count f))
                  (empty-vector? (second f))
                  (or (head-named? (nth f 2) "filter")
                      (head-named? (nth f 2) "remove"))))}

   {:id "reduce-conj-vec" :category "loops_and_comprehensions" :head "reduce"
    :match (fn [f]
             (and (= 4 (count f))
                  (sym-named? (second f) "conj")
                  (empty-vector? (nth f 2))))}

   {:id "apply-str-interpose" :category "loops_and_comprehensions" :head "apply"
    :match (fn [f]
             (and (= 3 (count f))
                  (sym-named? (second f) "str")
                  (head-named? (nth f 2) "interpose")))}

   {:id "redundant-lambda" :category "abstractions" :head #{"fn" "fn*"}
    :match (fn [f]
             (let [params (second f)
                   body (drop 2 f)]
               (and (vector? params)
                    (seq params)
                    (every? symbol? params)
                    (= 1 (count body))
                    (let [call (first body)]
                      (and (call? call)
                           (not (contains? (set params) (first call)))
                           (= (vec (rest call)) params))))))}

   {:id "redundant-let" :category "abstractions" :head "let"
    :match (fn [f]
             (and (= 3 (count f))
                  (vector? (second f))
                  (= 2 (count (second f)))
                  (symbol? (first (second f)))
                  (not= '_ (first (second f)))
                  (= 1 (count-occurrences (first (second f)) (nth f 2)))))}

   {:id "cons-nil" :category "misc" :head "cons"
    :match (fn [f] (and (= 3 (count f)) (nil? (nth f 2))))}

   {:id "thread-noop" :category "misc" :head #{"->" "->>"}
    :match (fn [f] (= 2 (count f)))}

   {:id "do-single" :category "misc" :head "do"
    :match (fn [f] (= 2 (count f)))}])

(def ^:private head-rules
  (reduce (fn [m r]
            (reduce (fn [m h] (update m h (fnil conj []) r))
                    m
                    (if (coll? (:head r)) (:head r) [(:head r)])))
          {}
          rules))

(defn ast-findings
  "Every rule hit in FORMS as {:rule :category :line} maps - one per rule
   and matching node; callers dedupe the flagged lines."
  [forms]
  (vec
   (mapcat (fn [form]
             (for [node (tree-seq coll? seq form)
                   :when (seq? node)
                   :let [line (line-of node)
                         h (when (symbol? (first node)) (name (first node)))
                         rs (get head-rules h)]
                   :when line
                   r rs
                   :when ((:match r) node)]
               {:rule (:id r) :category (:category r) :line line}))
           forms)))

;; ------------------------------------------------------------ structural

(defn definitions
  "defn/defn- forms as {:name :private? :row :params :body} maps."
  [forms]
  (vec
   (keep (fn [form]
           (when (and (seq? form)
                      (or (sym-named? (first form) "defn")
                          (sym-named? (first form) "defn-")))
             (let [nm (second form)
                   more (drop 2 form)
                   more (if (string? (first more)) (rest more) more)
                   more (if (map? (first more)) (rest more) more)
                   params (first more)
                   body (rest more)]
               (when (and (symbol? nm)
                          (vector? params)
                          (every? symbol? params))
                 {:name nm
                  :private? (sym-named? (first form) "defn-")
                  :row (:row (meta form))
                  :params (vec params)
                  :body (vec body)}))))
         forms)))

(defn trivial-wrappers
  "Defns whose whole body forwards their params unchanged to another scanned
   defn (scb-check's trivial-wrapper structural rule)."
  [defs]
  (let [names (set (map :name defs))]
    (vec
     (keep (fn [{:keys [name row params body]}]
             (let [sole (first body)]
               (when (and (= 1 (count body))
                          (call? sole)
                          (not= name (first sole))
                          (contains? names (first sole))
                          (= (vec (rest sole)) params))
                 {:name name :row row :target (first sole)})))
           defs))))
