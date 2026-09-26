(ns kmet.tasks.slop
  "SCBench-style sloppiness metrics for a Clojure tree - the `bb slop` task.

   Measures the SlopCodeBench (arXiv:2603.24755) trajectory metrics:

     verbosity = |clone lines union rule-flagged lines| / SLOC
       Clone lines come from kmet.tasks.slop.clones, a Clojure port of
       scb-check's normalized-AST clone detection: candidate blocks
       (function definitions, conditionals, loops, try and
       binding-with-resource forms) are compared by their normalized AST -
       identifiers positionally renamed, literal values erased - and every
       block with a duplicate instance contributes its SLOC lines.
       Rule-flagged lines come from kmet.tasks.slop.rules, a Clojure
       adaptation of SCBench's Python ast-grep pack (boolean/comparison,
       conditionals, defensive, dict, loop/comprehension and abstraction
       rules plus the structural trivial-wrapper). SCBench's exact 137
       rules are Python-only, so this pack is the Clojure leg of the
       union.

     erosion = high-CC mass share
       mass(f) = CC(f) * sqrt(SLOC(f)); erosion is the mass of functions
       with CC > 10 divided by the mass of all functions. Cyclomatic
       complexity is a Clojure adaptation: +1 per `if`/`if-let`/`when`/
       `and`/`or`/`loop`/`doseq`/`dotimes`/`for`/`some->` family head and
       per `cond`/`condp`/`case` clause, per `cond->` pair and per `catch`
       clause. Nested functions (`fn`, `defn`, `defmacro`, `defmethod`,
       `deftype`/`defrecord`/`reify` method bodies) are counted as their
       own functions; multi-arity definitions count as one function.
       Reader conditionals are read with the #{:clj :bb} feature view.

   Reference rows for verbosity/erosion are the paper's calibration panel:
   473 maintained human Python repositories and 2,869 agent checkpoints.
   Lower is better.

   Usage: bb slop [path] [--top N]"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [edamame.core :as e]
            [kmet.tasks.slop.clones :as clones]
            [kmet.tasks.slop.rules :as rules]))

;; ------------------------------------------------------------------ options

(def ^:private usage
  (str "bb slop [path] [--top N]\n\n"
       "  path  directory (or single source file) to scan; default: .\n"
       "  --top number of outliers to list per metric (default 15)"))

(defn- parse-args [args]
  (loop [as (seq args) opts {:path "." :top 15}]
    (if (empty? as)
      opts
      (let [a (first as)]
        (cond
          (or (= a "--help") (= a "-h")) (assoc opts :help true)
          (str/starts-with? a "--top=") (recur (rest as) (assoc opts :top (parse-long (subs a 6))))
          (= a "--top") (recur (drop 2 as) (assoc opts :top (parse-long (second as))))
          :else (recur (rest as) (assoc opts :path a)))))))

;; ---------------------------------------------------------------- tokenizer

(def ^:private BS (char 92))
(def ^:private DQ (char 34))
(def ^:private SEMI (char 59))
(def ^:private NL (char 10))
(def ^:private DASH (char 45))
(def ^:private delims (set (map char [40 41 91 93 123 125])))

(defn- blank-char? [c] (str/blank? (str c)))

(defn- scan-string
  "Returns [end-index line] with end-index just past the closing quote."
  [text n i line]
  (loop [j (inc i), ln line]
    (if (>= j n)
      [n ln]
      (let [d (nth text j)]
        (cond
          (= d BS) (recur (+ j 2) ln)
          (= d DQ) [(inc j) ln]
          (= d NL) (recur (inc j) (inc ln))
          :else (recur (inc j) ln))))))

(defn- scan-char-lit
  "Returns the index just past a char literal."
  [text n i]
  (loop [j (inc i)]
    (if (>= j n)
      j
      (let [d (nth text j)]
        (if (or (Character/isLetterOrDigit d) (= d DASH))
          (recur (inc j))
          j)))))

(defn- scan-atom [text n i]
  (loop [j i]
    (if (>= j n)
      j
      (let [d (nth text j)]
        (if (or (blank-char? d) (delims d) (= d SEMI) (= d DQ) (= d BS))
          j
          (recur (inc j)))))))

(defn tokenize
  "Tokenize Clojure text. Delimiters are tokens; comments are dropped;
   string and char literals are single tokens. The token line numbers are
   the scan's SLOC lines (no blank or comment-only lines).
   Returns {:tokens [str ...] :lines [int ...]}."
  [text]
  (let [n (count text)]
    (loop [i 0, line 1, toks (transient []), tls (transient [])]
      (if (>= i n)
        {:tokens (persistent! toks) :lines (persistent! tls)}
        (let [c (nth text i)]
          (cond
            (blank-char? c)
            (recur (inc i) (if (= c NL) (inc line) line) toks tls)

            (= c SEMI)
            (let [nl (str/index-of text "\n" i)]
              (if nl
                (recur (inc nl) (inc line) toks tls)
                (recur n line toks tls)))

            (= c DQ)
            (let [[j ln] (scan-string text n i line)]
              (recur j ln (conj! toks (subs text i j)) (conj! tls line)))

            (= c BS)
            (let [j (scan-char-lit text n i)]
              (recur j line (conj! toks (subs text i j)) (conj! tls line)))

            (delims c)
            (recur (inc i) line (conj! toks (str c)) (conj! tls line))

            :else
            (let [j (scan-atom text n i)]
              (recur j line (conj! toks (subs text i j)) (conj! tls line)))))))))

(defn- file-sloc
  "Per-file SLOC line set and count."
  [path]
  (let [lines (set (:lines (tokenize (slurp (str path)))))]
    {:file (str path) :sloc-lines lines :sloc (count lines)}))

;; -------------------------------------------------------------- complexity

(def ^:private fn-heads '#{defn defn- defmacro defmethod fn fn*})
(def ^:private type-heads '#{deftype defrecord reify})
(def ^:private simple-decisions
  '#{if if-let if-some if-not when when-let when-some when-not when-first
     while and or loop doseq dotimes for some-> some->>})

(defn- fn-form? [f] (and (seq? f) (contains? fn-heads (first f))))
(defn- type-form? [f] (and (seq? f) (contains? type-heads (first f))))

(defn cc
  "Cyclomatic complexity increment of a form. Nested functions contribute 0
   (they are counted as their own function)."
  [form]
  (cond
    (or (fn-form? form) (type-form? form)) 0
    (seq? form)
    (let [h (first form) args (rest form)]
      (+ (cond
           (= h 'cond) (quot (count args) 2)
           (= h 'condp) (quot (count (drop 2 args)) 2)
           (= h 'case) (quot (count (rest args)) 2)
           (or (= h 'cond->) (= h 'cond->>)) (quot (count (rest args)) 2)
           (= h 'try) (count (filter #(and (seq? %) (= 'catch (first %))) args))
           (contains? simple-decisions h) 1
           :else 0)
         (reduce + 0 (map cc args))))
    (coll? form) (reduce + 0 (map cc form))
    :else 0))

(defn- collect-method-forms [form]
  (when (coll? form)
    (if (and (seq? form) (symbol? (first form)) (vector? (second form)))
      (cons form nil)
      (mapcat collect-method-forms form))))

(defn- collect-fns [form]
  (cond
    (fn-form? form) (cons form (mapcat collect-fns (rest form)))
    (type-form? form) (let [children (rest form)
                            methods (mapcat collect-method-forms children)]
                        (concat methods
                                (mapcat collect-fns methods)
                                (mapcat collect-fns children)))
    (coll? form) (mapcat collect-fns form)
    :else nil))

(defn- own-cc [form] (reduce (fn [a x] (+ a (cc x))) 1 (rest form)))

(defn- fn-label [form]
  (let [h (first form) nm (second form)]
    (cond
      (= h 'defmethod) (str nm " " (pr-str (nth form 2)))
      (symbol? nm) (str nm)
      :else (str (if (= h 'fn*) "fn#" "fn") "@" (or (:row (meta form)) "?")))))

(defn- form-sloc [lines row end-row]
  (let [lo (dec row)
        hi (min (count lines) (max row end-row))]
    (count (filter (fn [l]
                     (let [t (str/trim l)]
                       (and (seq t) (not (str/starts-with? t ";")))))
                   (subvec lines lo hi)))))

;; ------------------------------------------------------------------ parse

(def ^:private parse-opts
  {:all true
   :read-cond :allow
   :features #{:clj :bb}
   :auto-resolve (fn [alias] (or alias 'kmet.slop))})

(defn parse-text
  "Parse Clojure source TEXT with reader conditionals read as #{:clj :bb};
   keeps edamame's row/end-row metadata."
  [text]
  (e/parse-string-all text parse-opts))

(defn parse-file
  "Read PATH into {:file :lines :forms}; throws for unreadable or malformed
   input."
  [path]
  (let [text (slurp (str path))]
    {:file (str path)
     :lines (vec (str/split-lines text))
     :forms (parse-text text)}))

(defn file-fns
  "Functions in a parsed file ({:file :lines :forms}) as maps with :cc,
   :sloc and :mass."
  [{:keys [file lines forms]}]
  (for [form (mapcat collect-fns forms)
        :let [m (meta form) row (:row m) end-row (:end-row m)]
        :when (and row end-row)]
    (let [c (own-cc form)
          sloc (max 1 (form-sloc lines row end-row))]
      {:file file :row row :cc c :sloc sloc
       :label (fn-label form)
       :mass (* c (Math/sqrt (double sloc)))})))

;; ------------------------------------------------------------------- scan

(def ^:private source-exts ["clj" "cljc" "cljs" "bb"])
(def ^:private skip-segments #{"target" ".git" "node_modules" ".cpcache" "dist" ".jolt" ".lsp"})

(defn- source-path? [f]
  (let [name (str (fs/file-name f))]
    (boolean (some #(str/ends-with? name (str "." %)) source-exts))))

(defn- skipped-path? [root f]
  (let [parts (map str (fs/components (fs/relativize root f)))]
    (boolean (some skip-segments parts))))

(defn source-files
  "Every Clojure source (.clj/.cljc/.cljs/.bb) under ROOT, excluding build
   and VCS directories. ROOT may itself be a single source file."
  [root]
  (let [root-path (fs/path root)]
    (if (fs/regular-file? root-path)
      (if (source-path? root-path) [root-path] [])
      (->> (fs/glob root-path "**")
           (filter fs/regular-file?)
           (filter source-path?)
           (remove #(skipped-path? root-path %))
           (sort-by str)))))

(defn- parse-scan
  "Parse one file into {:file :fns :findings :defs}; failures are recorded
   and yield empty results instead of aborting the scan."
  [path failures]
  (try
    (let [{:keys [file lines forms]} (parse-file path)]
      {:file file
       :forms forms
       :fns (vec (file-fns {:file file :lines lines :forms forms}))
       :findings (mapv #(assoc % :file file) (rules/ast-findings forms))
       :defs (mapv #(assoc % :file file) (rules/definitions forms))})
    (catch Exception ex
      (swap! failures conj {:file (str path) :error (ex-message ex)})
      {:file (str path) :forms [] :fns [] :findings [] :defs []})))

(defn scan
  "Analyze every Clojure source under ROOT.
   OPTS: :top outlier count (default 15)."
  [root {:keys [top] :or {top 15}}]
  (let [files (source-files root)
        sloc-stats (mapv file-sloc files)
        sloc-by-file (into {} (map (juxt :file :sloc) sloc-stats))
        sloc-lines-by-file (into {} (map (juxt :file :sloc-lines) sloc-stats))
        failures (atom [])
        parsed (mapv #(parse-scan % failures) files)
        candidates (mapcat (fn [{:keys [file forms]}]
                             (map #(assoc % :file file) (clones/candidates forms)))
                           parsed)
        clone-by-file (clones/clone-lines-by-file candidates sloc-lines-by-file)
        fns (vec (mapcat :fns parsed))
        defs (mapcat :defs parsed)
        wrappers (mapv (fn [w]
                         {:file (:file w) :line (:row w)
                          :rule "trivial-wrapper" :category "abstractions"})
                       (rules/trivial-wrappers defs))
        findings (vec (concat (mapcat :findings parsed) wrappers))
        rule-by-file (reduce (fn [m {:keys [file line]}]
                               (update m file (fnil conj #{}) line))
                             {} findings)
        sloc (reduce + 0 (map :sloc sloc-stats))
        clone-lines (reduce + 0 (map count (vals clone-by-file)))
        rule-lines (count (distinct (map (juxt :file :line) findings)))
        union-by-file (into {} (map (fn [{:keys [file]}]
                                      (let [c (get clone-by-file file #{})
                                            r (get rule-by-file file #{})]
                                        [file (count (into c r))]))
                                    sloc-stats))
        union-lines (reduce + 0 (vals union-by-file))
        hi (filter #(> (:cc %) 10) fns)
        total-mass (reduce + 0.0 (map :mass fns))
        hi-mass (reduce + 0.0 (map :mass hi))
        outlier-files (->> union-by-file
                           (filter (fn [[_ n]] (pos? n)))
                           (map (fn [[file n]]
                                  {:file file
                                   :flagged n
                                   :sloc (get sloc-by-file file 1)
                                   :clone-lines (count (get clone-by-file file #{}))
                                   :rule-lines (count (get rule-by-file file #{}))}))
                           (sort-by :flagged >))
        rule-counts (->> findings
                         (group-by :rule)
                         (map (fn [[r fs]]
                                [r (count (distinct (map (juxt :file :line) fs)))]))
                         (sort-by second >))]
    {:root (str root)
     :files (count files)
     :sloc sloc
     :failures @failures
     :clone-lines clone-lines
     :rule-lines rule-lines
     :union-lines union-lines
     :overlap (- (+ clone-lines rule-lines) union-lines)
     :verbosity (if (pos? sloc) (/ (double union-lines) sloc) 0.0)
     :rule-counts (vec (take 8 rule-counts))
     :outlier-files (vec (take top outlier-files))
     :functions (count fns)
     :high-cc (count hi)
     :max-cc (reduce max 0 (map :cc fns))
     :total-mass total-mass
     :hi-mass hi-mass
     :erosion (if (pos? total-mass) (/ hi-mass total-mass) 0.0)
     :outliers (vec (take top (sort-by :mass > hi)))}))

;; --------------------------------------------------------------- references

(def references
  "SCBench calibration rows (arXiv:2603.24755): 473 maintained human Python
   repositories and 2,869 agent checkpoints. Ranges are mean - one standard
   deviation to mean + one standard deviation. Lower is better."
  {:verbosity {:human {:mean 0.19 :sd 0.11} :agent {:mean 0.44 :sd 0.18}}
   :erosion {:human {:mean 0.34 :sd 0.22} :agent {:mean 0.68 :sd 0.20}}})

(defn- verdict [v human agent]
  (cond
    (< v (:mean human)) :below-human
    (< v (+ (:mean human) (:sd human))) :human-typical
    (< v (:mean agent)) :elevated
    :else :agent-level))

(def ^:private verdict-labels
  {:below-human "below human mean"
   :human-typical "human-typical"
   :elevated "elevated vs human"
   :agent-level "agent-level"})

(defn- one-sigma-range [{:keys [mean sd]}] [(- mean sd) (+ mean sd)])

(defn- ref-line [v metric]
  (let [{:keys [human agent]} (get references metric)
        [human-lo human-hi] (one-sigma-range human)
        [agent-lo agent-hi] (one-sigma-range agent)
        z (/ (- v (:mean human)) (:sd human))]
    (format "  reference: human %.2f-%.2f | agent %.2f-%.2f | verdict: %s (z=%+.2f vs human)"
            human-lo human-hi agent-lo agent-hi
            (verdict-labels (verdict v human agent)) z)))

(defn- rel-path [root f]
  (str (fs/relativize (fs/path root) (fs/path f))))

;; ----------------------------------------------------------------- report

(defn format-report
  "Render a `scan` report as plain text: summary, reference comparison and
   outliers only."
  [{:keys [root files sloc clone-lines rule-lines union-lines overlap rule-counts
           outlier-files functions failures
           high-cc max-cc total-mass hi-mass verbosity erosion outliers]}]
  (str/join
   "\n"
   (concat
    [(str "SCBench slop - " root)
     (format "  %d files | %d SLOC | %d functions | %d parse failures"
             files sloc functions (count failures))
     ""
     (format "VERBOSITY  %.3f  (SCBench: clone + rule-flagged lines / SLOC)"
             verbosity)
     (format "  %d lines flagged: %d rules + %d clones (overlap %d) in %d SLOC"
             union-lines rule-lines clone-lines overlap sloc)
     (if (seq rule-counts)
       (str "  top rules: "
            (str/join " | " (map (fn [[r n]] (str r " " n)) rule-counts)))
       "  top rules: none")
     (ref-line verbosity :verbosity)]
    (if (seq outlier-files)
      (cons (format "  outlier files (top %d by flagged lines):" (count outlier-files))
            (map (fn [f]
                   (format "    flagged=%-5d clone=%-5d rule=%-5d %5.1f%% of file  %s"
                           (:flagged f) (:clone-lines f) (:rule-lines f)
                           (* 100.0 (/ (double (:flagged f)) (max 1 (:sloc f))))
                           (rel-path root (:file f))))
                 outlier-files))
      ["  outlier files: none"])
    [""
     (format "EROSION  %.3f  (mass share of CC>10 functions; mass = CC * sqrt(SLOC))"
             erosion)
     (format "  %d of %d functions CC>10 | max CC %d | mass %.1f of %.1f"
             high-cc functions max-cc hi-mass total-mass)
     (ref-line erosion :erosion)]
    (if (seq outliers)
      (cons (format "  outlier functions (top %d of %d by mass):" (count outliers) high-cc)
            (map (fn [f]
                   (format "    cc=%-3d sloc=%-4d mass=%6.1f  %s  (%s:%d)"
                           (:cc f) (:sloc f) (:mass f) (:label f)
                           (rel-path root (:file f)) (:row f)))
                 outliers))
      ["  outlier functions: none"])
    (when (seq failures)
      (cons (format "PARSE FAILURES  %d" (count failures))
            (map (fn [{:keys [file error]}]
                   (str "  " (rel-path root file) " - " error))
                 (take 5 failures)))))))

(defn -main [& args]
  (let [{:keys [path top help] :as opts} (parse-args args)]
    (when help
      (println usage)
      (System/exit 0))
    (when-not (fs/exists? path)
      (println (str "bb slop: path not found: " path))
      (System/exit 1))
    (when (or (nil? top) (< top 1))
      (println (str "bb slop: --top must be a positive integer\n\n" usage))
      (System/exit 1))
    (println (format-report (scan path (select-keys opts [:top]))))))
