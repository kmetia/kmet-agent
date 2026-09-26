(ns kmet.tasks.slop.clones
  "Normalized-AST clone detection - the Clojure leg of SCBench's clone
   metric (SlopCodeBench, arXiv:2603.24755), mirroring scb-check's
   analysis/clones.py.

   Every candidate block is rendered as a normalized string: identifiers
   become positional placeholders ($VAR1, ... in first-appearance order),
   literal values are erased by type (string/char -> $STR, integer ->
   $INT, float -> $FLOAT, boolean -> $BOOL, nil -> $NONE, keyword/other ->
   $LIT), and syntax symbols (special forms, core control macros and
   operator symbols) stay as node types the way tree-sitter keeps
   keywords. Blocks two or more of whose normalized strings are equal are
   clones; each instance contributes its SLOC lines to the score.

   Clojure heads mapped to scb-check's per-language clone node types:

     function   defn defn- defmacro defmethod deftest fn fn* plus methods
                inside deftype/defrecord/reify/proxy
     branch     if if-not if-let if-some when when-not when-let when-some
                cond condp case
     loop       loop while doseq dotimes for
     block      try with-open with-redefs binding with-bindings

   A candidate must have at least two statements in one of its body
   containers, where a statement is a body form and do/let wrappers count
   their contents (scb-check's Haskell do/let_in handling) - so
   one-statement bodies never clone. Docstrings and attribute maps are
   elided like scb-check elides docstrings. `let` is not itself a
   candidate: the reference node set has no plain-block type."
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------------ heads

(def ^:private function-heads
  '#{defn defn- defmacro defmethod deftest fn fn*})

(def ^:private if-heads '#{if if-not if-let if-some})
(def ^:private when-heads '#{when when-not when-let when-some})
(def ^:private cond-heads '#{cond condp case})
(def ^:private loop-heads '#{loop while doseq dotimes for})
(def ^:private block-heads '#{try with-open with-redefs binding})

(def ^:private heads
  (into function-heads
        (concat if-heads when-heads cond-heads loop-heads block-heads)))

(def ^:private type-heads '#{deftype defrecord reify proxy})

(def ^:private syntax-symbols
  '#{def do fn fn* if let let* letfn letfn* loop loop* recur quote var set! new . throw
     try catch finally monitor-enter monitor-exit case* deftype* defrecord* reify* &
     defn defn- defmacro defmethod defmulti defprotocol deftest deftype defrecord reify proxy
     if-not if-let if-some when when-not when-let when-some when-first cond condp case
     and or not not= -> ->> some-> some->> cond-> cond->> as-> doto .. comment declare
     binding with-open with-redefs with-bindings})

;; ------------------------------------------------------------- node walk

(def ^:private opaque-heads '#{quote syntax-quote var})

(defn- form-children [form]
  (cond
    (map? form) (mapcat (fn [[k v]] [k v]) form)
    (coll? form) (seq form)
    :else nil))

(defn- node-seq [form]
  (cons form
        (when (and (coll? form)
                   (not (and (seq? form) (contains? opaque-heads (first form)))))
          (mapcat node-seq (form-children form)))))

(defn- file-forms [forms]
  (mapcat node-seq forms))

;; ---------------------------------------------------------- body containers

(defn- strip-doc-attrs
  "Drop a leading docstring and/or attribute map from FORMS."
  [forms]
  (cond-> forms
    (string? (first forms)) rest
    (map? (first forms)) rest))

(defn- function-bodies
  "One body container per arity in a defn/fn/method TAIL."
  [tail]
  (let [tail (vec (strip-doc-attrs tail))]
    (if (vector? (first tail))
      [(subvec tail 1)]
      (into []
            (keep (fn [arity]
                    (when (and (seq? arity) (vector? (second arity)))
                      (vec (strip-doc-attrs (drop 2 arity))))))
            tail))))

(defn- fn-bodies [tail]
  (function-bodies (if (symbol? (first tail)) (rest tail) tail)))

(defn- if-containers [node]
  (let [args (vec (rest node))]
    (if (>= (count args) 3)
      [[(nth args 1)] [(nth args 2)]]
      [[(nth args 1)]])))

(defn- cond-containers [node]
  (let [h (first node) args (vec (rest node))]
    (case h
      cond (mapv (fn [[_ e]] [e]) (partition 2 args))
      condp (mapv (fn [[_ e]] [e]) (partition 2 (subvec args (min 2 (count args)))))
      case (let [cs (subvec args (min 1 (count args)))]
             (vec (concat (map (fn [[_ e]] [e]) (partition 2 cs))
                          (when (odd? (count cs)) [[(peek cs)]])))))))

(defn- try-containers [node]
  (let [clause? #(and (seq? %) (contains? '#{catch finally} (first %)))
        tail (rest node)
        body (vec (take-while (complement clause?) tail))]
    (into [body]
          (keep (fn [c]
                  (when (clause? c)
                    (case (first c)
                      catch (vec (drop 3 c))
                      finally (vec (rest c))
                      nil))))
          (drop (count body) tail))))

(defn- containers
  "Statement containers of NODE, or nil when NODE is not a candidate head."
  [node method?]
  (if method?
    (function-bodies (rest node))
    (let [h (first node)]
      (cond
        (contains? function-heads h)
        (case h
          (defn defn- defmacro) (function-bodies (drop 2 node))
          defmethod (function-bodies (drop 3 node))
          deftest [(vec (drop 2 node))]
          (fn fn*) (fn-bodies (rest node)))

        (contains? if-heads h) (if-containers node)
        (contains? when-heads h) [(vec (drop 2 node))]
        (contains? cond-heads h) (cond-containers node)
        (contains? loop-heads h) [(vec (drop 2 node))]
        (= 'try h) (try-containers node)
        (contains? block-heads h) [(vec (drop 2 node))]
        :else nil))))

(def ^:private statement-wrappers '#{do let let* letfn letfn*})

(defn- wrapper-forms [form]
  (if (= 'do (first form))
    (rest form)
    (drop 2 form)))

(defn- statement-weight [form]
  (if (and (seq? form) (contains? statement-wrappers (first form)))
    (reduce + 0 (map statement-weight (wrapper-forms form)))
    1))

(defn- two-statements? [containers]
  (boolean (some #(>= (reduce + 0 (map statement-weight %)) 2) containers)))

;; ------------------------------------------------------------ normalization

(defn- structural-symbol?
  "Syntax symbols and punctuation operators stay distinct; everything else
   is an identifier."
  [sym]
  (or (contains? syntax-symbols sym)
      (not (re-find #"[A-Za-z]" (name sym)))))

(defn- ident-token [vars sym]
  (or (get @vars sym)
      (let [token (str "$VAR" (inc (count @vars)))]
        (vswap! vars assoc sym token)
        token)))

(declare normalize)

(defn- normalize-seq [xs vars]
  (str/join "," (map #(normalize % vars) xs)))

(defn- normalize [form vars]
  (cond
    (symbol? form) (if (structural-symbol? form)
                     (str "#" (name form))
                     (ident-token vars form))

    (keyword? form) "$LIT"
    (string? form) "$STR"
    (char? form) "$STR"
    (number? form) (if (float? form) "$FLOAT" "$INT")
    (boolean? form) "$BOOL"
    (nil? form) "$NONE"

    (vector? form) (str "[" (normalize-seq form vars) "]")
    (set? form) (str "#{" (normalize-seq form vars) "}")
    (map? form) (str "{" (normalize-seq (mapcat identity form) vars) "}")
    (seq? form) (let [h (first form)]
                  (if (and (symbol? h) (contains? syntax-symbols h))
                    (str (name h) "(" (normalize-seq (rest form) vars) ")")
                    (str "(" (normalize-seq form vars) ")")))
    :else "$LIT"))

(defn- canonical
  "Candidate form with docstrings and attribute maps elided."
  [node method?]
  (if method?
    (cons (first node) (strip-doc-attrs (rest node)))
    (let [h (first node)]
      (cond
        (contains? '#{defn defn- defmacro} h)
        (list* h (second node) (strip-doc-attrs (drop 2 node)))

        (= 'defmethod h)
        (list* h (second node) (nth node 2) (strip-doc-attrs (drop 3 node)))

        :else node))))

;; -------------------------------------------------------------- candidates

(defn- method-form?
  "A method definition list inside a deftype/defrecord/reify/proxy form."
  [m]
  (and (seq? m)
       (let [params (second m)]
         (or (vector? params)
             (and (seq? params)
                  (every? #(and (seq? %) (vector? (second %))) (rest m)))))))

(defn- method-forms [forms]
  (into #{}
        (comp (filter #(and (seq? %) (contains? type-heads (first %))))
              (mapcat #(drop (if (= 'reify (first %)) 2 3) %))
              (filter method-form?))
        (file-forms forms)))

(defn- candidate [node method?]
  (when-let [cs (containers node method?)]
    (when (two-statements? cs)
      (let [{:keys [row end-row]} (meta node)]
        (when (and row end-row)
          {:row row :end-row end-row
           :hash (normalize (canonical node method?) (volatile! {}))})))))

(defn candidates
  "Clone candidates in FORMS as {:row :end-row :hash} maps; candidates with
   equal hashes are clone instances."
  [forms]
  (let [methods (method-forms forms)]
    (into []
          (keep (fn [node]
                  (when (seq? node)
                    (let [method? (contains? methods node)]
                      (when (or method? (contains? heads (first node)))
                        (candidate node method?))))))
          (file-forms forms))))

(defn clone-lines-by-file
  "SLOC lines covered by clone instances, as {file #{line}}.
   CANDIDATES are {:file :row :end-row :hash} maps; SLOC-BY-FILE is
   {file #{line}}."
  [candidates sloc-by-file]
  (reduce (fn [acc group]
            (reduce (fn [acc {:keys [file row end-row]}]
                      (update acc file (fnil into #{})
                              (filter (get sloc-by-file file #{})
                                      (range row (inc end-row)))))
                    acc
                    group))
          {}
          (filter #(>= (count %) 2)
                  (vals (group-by :hash candidates)))))
