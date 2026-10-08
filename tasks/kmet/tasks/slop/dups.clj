(ns kmet.tasks.slop.dups
  "Duplicate listing for `bb slop --dups`.

   Two lenses over the same parsed tree:

   clone groups - the normalized-AST candidates `kmet.tasks.slop.clones`
     scores (function, branch, loop and block bodies with identifiers
     positionally renamed, literals erased). Lists the instances behind the
     metric's clone-line count, cross-file groups first.

   shape groups - defn bodies identical after alpha-renaming the parameters
     and bindings only, with every other symbol kept verbatim (namespace
     included). This is the floor the clone metric misses twice over: a
     candidate needs two statements in a body container, so a
     one-expression accessor or protocol-dispatch copy never clones, and
     positional renaming alone cannot tell two one-arg pass-through helpers
     apart. Keeping the callee names means a copy that still calls the same
     fns collides (`protocols/editor-set-text!` stays itself) while helpers
     forwarding to different fns do not."
  (:require [clojure.string :as str]
            [kmet.tasks.slop.clones :as clones]))

;; ─── clone groups ──────────────────────────────────────────────────────────

(defn clone-groups
  "CLONES ({:file :row :end-row :hash} maps, as scored by the metric)
   grouped by hash. Cross-file groups come first, then the widest, as
   {:instances :cross? :files :lines} maps."
  [clones]
  (->> (vals (group-by :hash clones))
       (filter #(>= (count %) 2))
       (map (fn [instances]
              (let [files (distinct (map :file instances))]
                {:instances (mapv #(select-keys % [:file :row :end-row]) instances)
                 :cross? (> (count files) 1)
                 :files (count files)
                 :lines (count (distinct (mapcat (fn [{:keys [row end-row]}]
                                                   (range row (inc end-row)))
                                                 instances)))})))
       (sort-by (fn [g] [(if (:cross? g) 0 1) (- (:lines g)) (- (count (:instances g)))]))
       vec))

;; ─── shape groups ──────────────────────────────────────────────────────────

(def ^:private binding-heads
  '#{let let* loop loop* if-let if-some when-let when-some doseq for dotimes
     binding with-open with-local-vars with-redefs as-> when-first})

(def ^:private first-vector-binding-heads
  "Heads whose binding vector is the first vector argument."
  '#{if-let if-some when-let when-some when-first})

(def ^:private fn-heads '#{fn fn* defn defn-})

(def ^:private min-shape-nodes
  "Shape groups below this node count are noise: a body that is one literal
   or one bare symbol is not worth aligning."
  5)

(defn- method-list?
  "An arity list — (params body…) — inside a multi-arity fn/defn."
  [x]
  (and (seq? x) (vector? (first x))))

(defn- binding-vectors
  "The binding vectors of FORM when FORM's head introduces bindings."
  [[head & args]]
  (cond
    (contains? first-vector-binding-heads head) [(first (filter vector? args))]
    (contains? binding-heads head) (take 2 (filter vector? args))
    (contains? fn-heads head) (cond
                                (vector? (first args)) [(first args)]
                                (every? method-list? args) (map first args)
                                :else nil)
    :else nil))

(defn- bound-symbols
  "Every symbol FORM binds — parameters and binding-vector names."
  [form]
  (let [bound (atom #{})]
    (letfn [(scan [f]
              (when (seq? f)
                (doseq [v (binding-vectors f), s v :when (symbol? s)]
                  (swap! bound conj s)))
              (when (coll? f)
                (doseq [child f :when (coll? child)]
                  (scan child))))]
      (scan form)
      @bound)))

(defn- alpha-shape
  "FORM with bound symbols replaced by positional placeholders ($1, $2, …)
   and every other symbol kept verbatim. EXTRA-BOUND is a set of symbols
   bound outside FORM's own binding heads (an arity's parameter vector,
   which the synthetic arity head hides)."
  [form extra-bound]
  (let [bound (into (bound-symbols form) extra-bound)
        renamed (atom {})]
    (letfn [(go [f]
              (cond
                (symbol? f) (if (contains? bound f)
                              (or (get @renamed f)
                                  (let [placeholder (symbol (str "$" (inc (count @renamed))))]
                                    (swap! renamed assoc f placeholder)
                                    placeholder))
                              f)
                (vector? f) (mapv go f)
                (set? f) (into #{} (map go f))
                (map? f) (into {} (map (fn [[k v]] [(go k) (go v)])) f)
                (seq? f) (apply list (map go f))
                :else f))]
      (go form))))

(defn- node-count [form]
  (if (coll? form)
    (reduce + 1 (map node-count form))
    1))

(defn- strip-trailing-nils
  "Drop trailing nil statements — the explicit `nil` return a setter body
   adds after its work, which is not a behavioural difference."
  [forms]
  (vec (reverse (drop-while nil? (reverse forms)))))

(defn- arity-shape
  "The shape of one arity: (params body…) with a stable head."
  [params body]
  (alpha-shape (list* 'arity params (strip-trailing-nils body))
               (into #{} (filter symbol?) params)))

(defn- defn-shapes
  "One {:label :shape} entry per arity of a defn/defn-/def-with-fn form."
  [form]
  (when (and (seq? form) (symbol? (first form)))
    (let [head (first form)
          nm (second form)
          arities (fn [tail]
                    (cond
                      (vector? (first tail)) [{:label (str nm)
                                               :shape (arity-shape (first tail) (rest tail))}]
                      (every? method-list? tail) (mapv (fn [m]
                                                         {:label (str nm "/" (count (first m)))
                                                          :shape (arity-shape (first m) (rest m))})
                                                       tail)
                      :else nil))]
      (case head
        (defn defn-) (arities (clones/strip-doc-attrs (drop 2 form)))
        def (when-let [value (first (rest form))]
              (when (and (seq? value) (contains? '#{fn fn*} (first value)))
                (arities (clones/strip-doc-attrs (rest value)))))
        nil))))

(defn shape-groups
  "PARSED (the scan's [{:file :forms} …]) grouped by defn body shape, as
   {:instances :files :nodes :body} maps, widest body first — the copies the
   clone metric's statement floor and positional renaming hide."
  [parsed]
  (->> (for [{:keys [file forms]} parsed
             form (tree-seq coll? seq forms)
             entry (defn-shapes form)]
         (assoc entry :file file :row (:row (meta form))))
       (group-by :shape)
       vals
       (filter #(>= (count %) 2))
       (map (fn [entries]
              {:instances (mapv #(select-keys % [:file :row :label]) entries)
               :files (count (distinct (map :file entries)))
               :nodes (node-count (:shape (first entries)))
               :body (pr-str (:shape (first entries)))}))
       (filter #(>= (:nodes %) min-shape-nodes))
       (sort-by (fn [g] [(- (:nodes g)) (- (:files g))]))
       vec))

;; ─── report ────────────────────────────────────────────────────────────────

(defn- excerpt
  "One display line for the instance starting at ROW: the next N source
   lines trimmed and joined."
  [lines row n]
  (when (seq lines)
    (->> (subvec (vec lines) (dec row) (min (count lines) (+ (dec row) n)))
         (map str/trim)
         (remove str/blank?)
         (str/join " | "))))

(defn- clip [s width]
  (if (> (count s) width) (str (subs s 0 (- width 3)) "...") s))

(defn- clone-instance-lines [rel-path lines-by-file instances]
  (map (fn [{:keys [file row end-row]}]
         (format "      %s:%d-%d  %s" (rel-path file) row end-row
                 (clip (or (excerpt (get lines-by-file file) row 2) "") 88)))
       instances))

(defn- shape-instance-lines [rel-path instances]
  (map (fn [{:keys [file row label]}]
         (format "      %s:%d  %s" (rel-path file) row label))
       instances))

(defn- clone-section
  "One labelled clone-group listing; GROUPS is the vector to take TOP from."
  [title top groups total rel-path lines-by-file]
  (concat [(format "  %s (top %d of %d):" title (min top (count groups)) total)]
          (mapcat (fn [{:keys [instances files lines]}]
                    (cons (format "    n=%d files=%d lines=%d" (count instances) files lines)
                          (clone-instance-lines rel-path lines-by-file instances)))
                  (take top groups))))

(defn- shape-section [top groups rel-path]
  (concat
   [(format "  shape groups — defns identical up to parameter names (top %d of %d):"
            (min top (count groups)) (count groups))]
   (mapcat (fn [{:keys [instances files nodes body]}]
             (cons (format "    n=%d files=%d nodes=%d  %s" (count instances) files nodes (clip body 96))
                   (shape-instance-lines rel-path instances)))
           (take top groups))))

(defn format-duplicates
  "Render the --dups section as report lines.
   OPTS: :clones / :shapes (group vectors), :lines-by-file {file lines},
   :rel-path (file → display path), :top (groups listed per lens),
   :clone-lines (the metric total)."
  [{:keys [clones shapes lines-by-file rel-path top clone-lines]}]
  (let [top (or top 15)
        cross (filter :cross? clones)
        same (remove :cross? clones)]
    (concat
     [""
      "DUPLICATES  (--dups: clone groups + shape-identical defns)"]
     (if (seq clones)
       (concat
        [(format "  clone groups: %d cross-file, %d same-file (%d clone lines)"
                 (count cross) (count same) clone-lines)]
        (when (seq cross)
          (clone-section "cross-file clone groups" top cross (count cross) rel-path lines-by-file))
        (when (seq same)
          (clone-section "same-file clone groups" top same (count same) rel-path lines-by-file)))
       ["  clone groups: none"])
     (if (seq shapes)
       (shape-section top shapes rel-path)
       ["  shape groups: none"]))))
