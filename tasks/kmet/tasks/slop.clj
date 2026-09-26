(ns kmet.tasks.slop
  "SCBench-style sloppiness metrics for a Clojure tree - the `bb slop` task.

   Measures the two SlopCodeBench (arXiv:2603.24755) trajectory metrics:

     verbosity = clone lines / SLOC
       Clone lines are the distinct lines covered by a duplicated window
       of K tokens (K = 50 by default) over an exact token stream - no
       identifier normalization. SCBench also folds in lines flagged by
       its 137 ast-grep rules; that rule pack is Python-only, so Clojure
       contributes clone lines only (the same treatment scb-check gives
       every non-Python language).

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

   Reference rows are the paper's calibration panel: 473 maintained human
   Python repositories and 2,869 agent checkpoints. Lower is better.

   Usage: bb slop [path] [--k N] [--top N]"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [edamame.core :as e]))

;; ------------------------------------------------------------------ options

(def ^:private usage
  (str "bb slop [path] [--k N] [--top N]\n\n"
       "  path  directory (or single source file) to scan; default: .\n"
       "  --k   token window size for clone detection (default 50)\n"
       "  --top number of outliers to list per metric (default 15)"))

(defn- parse-args [args]
  (loop [as (seq args) opts {:path "." :k 50 :top 15}]
    (if (empty? as)
      opts
      (let [a (first as)]
        (cond
          (or (= a "--help") (= a "-h")) (assoc opts :help true)
          (str/starts-with? a "--k=") (recur (rest as) (assoc opts :k (parse-long (subs a 4))))
          (= a "--k") (recur (drop 2 as) (assoc opts :k (parse-long (second as))))
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
  "Tokenize Clojure text for clone detection. Delimiters are tokens;
   comments are dropped; string and char literals are single tokens.
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

;; ------------------------------------------------------------ clone windows

(def ^:private M1 1000000007)
(def ^:private M2 1000000009)
(def ^:private P1 131)
(def ^:private P2 137)
(def ^:private KEY-SCALE 2000000011)

(defn- p-pow [p m k]
  (reduce (fn [a _] (mod (* a p) m)) 1 (range (dec k))))

(defn- init-hash [ids k p m]
  (reduce (fn [h tid] (mod (+ (* h p) tid) m)) 0 (subvec ids 0 k)))

(defn token-ids [tokens]
  (loop [ts (seq tokens), m (transient {}), next-id 1, out (transient [])]
    (if ts
      (let [t (first ts)]
        (if-some [id (get m t)]
          (recur (next ts) m next-id (conj! out id))
          (recur (next ts) (assoc! m t next-id) (inc next-id) (conj! out next-id))))
      (persistent! out))))

(defn- kgram-counts
  "Map window hash -> number of occurrences of that K-token window."
  [ids k]
  (let [n (count ids)]
    (if (< n k)
      {}
      (let [pk1 (p-pow P1 M1 k) pk2 (p-pow P2 M2 k)]
        (loop [s 0
               h1 (init-hash ids k P1 M1)
               h2 (init-hash ids k P2 M2)
               acc (transient {})]
          (let [key (+ (* h1 KEY-SCALE) h2)
                acc (assoc! acc key (inc (get acc key 0)))]
            (if (= s (- n k))
              (persistent! acc)
              (let [old (nth ids s)
                    new (nth ids (+ s k))
                    h1 (mod (+ (* (mod (- h1 (* old pk1)) M1) P1) new) M1)
                    h2 (mod (+ (* (mod (- h2 (* old pk2)) M2) P2) new) M2)]
                (recur (inc s) h1 h2 acc)))))))))

(defn- clone-lines
  "Set of line numbers participating in a duplicated K-token window."
  [ids lines counts k]
  (let [n (count ids) nw (- n k -1)]
    (if (or (< n k) (<= nw 0))
      #{}
      (let [pk1 (p-pow P1 M1 k) pk2 (p-pow P2 M2 k)
            marked (loop [s 0
                          h1 (init-hash ids k P1 M1)
                          h2 (init-hash ids k P2 M2)
                          mv (transient (vec (repeat nw false)))]
                     (let [key (+ (* h1 KEY-SCALE) h2)
                           mv (if (>= (get counts key 0) 2) (assoc! mv s true) mv)]
                       (if (= s (dec nw))
                         (persistent! mv)
                         (let [old (nth ids s)
                               new (nth ids (+ s k))
                               h1 (mod (+ (* (mod (- h1 (* old pk1)) M1) P1) new) M1)
                               h2 (mod (+ (* (mod (- h2 (* old pk2)) M2) P2) new) M2)]
                           (recur (inc s) h1 h2 mv)))))]
        (loop [s 0, res (transient #{})]
          (if (>= s nw)
            (persistent! res)
            (if (nth marked s)
              (let [e (loop [e s]
                        (if (and (< (inc e) nw) (nth marked (inc e)))
                          (recur (inc e))
                          e))]
                (recur (inc e)
                       (reduce (fn [r ti] (conj! r (nth lines ti)))
                               res
                               (range s (+ e k)))))
              (recur (inc s) res))))))))

(defn- file-token-stats
  "Per-file token stream and SLOC for the global clone pass."
  [path]
  (let [text (slurp (str path))
        {:keys [tokens lines]} (tokenize text)]
    {:file (str path)
     :sloc (count (set lines))
     :ids (token-ids tokens)
     :lines lines}))

(defn- merge-counts!
  "Fold per-file window counts into the global transient counts map."
  [acc counts]
  (reduce-kv (fn [a key n] (assoc! a key (+ (get a key 0) n))) acc counts))

;; -------------------------------------------------------------- erosion/CC

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

(def ^:private parse-opts
  {:all true
   :read-cond :allow
   :features #{:clj :bb}
   :auto-resolve (fn [alias] (or alias 'kmet.slop))})

(defn file-fns
  "Functions under PATH as {:file :row :cc :sloc :label :mass} maps."
  [path]
  (let [text (slurp (str path))
        lines (vec (str/split-lines text))
        forms (e/parse-string-all text parse-opts)]
    (for [form (mapcat collect-fns forms)
          :let [m (meta form) row (:row m) end-row (:end-row m)]
          :when (and row end-row)]
      (let [c (own-cc form)
            sloc (max 1 (form-sloc lines row end-row))]
        {:file (str path) :row row :cc c :sloc sloc
         :label (fn-label form)
         :mass (* c (Math/sqrt (double sloc)))}))))

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

(defn scan
  "Analyze every Clojure source under ROOT.
   OPTS: :k clone window size (default 50), :top outlier count (default 15)."
  [root {:keys [k top] :or {k 50 top 15}}]
  (let [files (source-files root)
        token-stats (mapv file-token-stats files)
        counts (persistent! (reduce (fn [acc {:keys [ids]}]
                                      (merge-counts! acc (kgram-counts ids k)))
                                    (transient {})
                                    token-stats))
        clone-stats (mapv (fn [{:keys [file sloc ids lines]}]
                            (let [cl (count (clone-lines ids lines counts k))]
                              {:file file
                               :sloc sloc
                               :clone-lines cl
                               :clone-fraction (if (pos? sloc)
                                                 (/ (double cl) sloc)
                                                 0.0)}))
                          token-stats)
        failures (atom [])
        fns (vec (mapcat (fn [f]
                           (try (file-fns f)
                                (catch Exception ex
                                  (swap! failures conj {:file f :error (ex-message ex)})
                                  [])))
                         files))
        sloc (reduce + 0 (map :sloc clone-stats))
        clone-lines (reduce + 0 (map :clone-lines clone-stats))
        hi (filter #(> (:cc %) 10) fns)
        total-mass (reduce + 0.0 (map :mass fns))
        hi-mass (reduce + 0.0 (map :mass hi))]
    {:root (str root)
     :k k
     :files (count files)
     :sloc sloc
     :clone-lines clone-lines
     :verbosity (if (pos? sloc) (/ (double clone-lines) sloc) 0.0)
     :clone-files (vec (take top (sort-by :clone-lines >
                                          (filter #(pos? (:clone-lines %)) clone-stats))))
     :functions (count fns)
     :high-cc (count hi)
     :max-cc (reduce max 0 (map :cc fns))
     :total-mass total-mass
     :hi-mass hi-mass
     :erosion (if (pos? total-mass) (/ hi-mass total-mass) 0.0)
     :outliers (vec (take top (sort-by :mass > hi)))
     :failures @failures}))

;; --------------------------------------------------------------- references

(def references
  "SCBench calibration rows (arXiv:2603.24755): 473 maintained human Python
   repositories and 2,869 agent checkpoints, mean +/- one standard deviation.
   Lower is better."
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

(defn- ref-line [v metric]
  (let [{:keys [human agent]} (get references metric)
        z (/ (- v (:mean human)) (:sd human))]
    (format "  reference: human %.2f +/- %.2f | agent %.2f +/- %.2f | verdict: %s (z=%+.2f vs human)"
            (:mean human) (:sd human) (:mean agent) (:sd agent)
            (verdict-labels (verdict v human agent)) z)))

(defn- rel-path [root f]
  (str (fs/relativize (fs/path root) (fs/path f))))

;; ----------------------------------------------------------------- report

(defn format-report
  "Render a `scan` report as plain text: summary, reference comparison and
   outliers only."
  [{:keys [root files sloc clone-lines clone-files functions high-cc max-cc
           total-mass hi-mass verbosity erosion outliers failures k]}]
  (str/join
   "\n"
   (concat
    [(str "SCBench slop - " root)
     (format "  %d files | %d SLOC | %d functions | %d parse failures"
             files sloc functions (count failures))
     ""
     (format "VERBOSITY  %.3f  (clone-only: SCBench's ast-grep rule pack is Python-only)"
             verbosity)
     (format "  %d clone lines / %d SLOC in duplicated %d-token windows"
             clone-lines sloc k)
     (ref-line verbosity :verbosity)]
    (if (seq clone-files)
      (cons (format "  outlier files (top %d by clone lines):" (count clone-files))
            (map (fn [c]
                   (format "    clone=%-6d %5.1f%% of file  %s"
                           (:clone-lines c) (* 100.0 (:clone-fraction c))
                           (rel-path root (:file c))))
                 clone-files))
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
  (let [{:keys [path k top help] :as opts} (parse-args args)]
    (when help
      (println usage)
      (System/exit 0))
    (when-not (fs/exists? path)
      (println (str "bb slop: path not found: " path))
      (System/exit 1))
    (when (or (nil? k) (nil? top) (< k 2) (< top 1))
      (println (str "bb slop: --k and --top must be positive integers\n\n" usage))
      (System/exit 1))
    (println (format-report (scan path (select-keys opts [:k :top]))))))
