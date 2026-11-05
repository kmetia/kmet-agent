(ns kmet.app.eval.definitions
  "Pure definition tracking for the eval tool: parsing a call's top-level
   defining forms, folding the session's `:eval-definitions` entries, and the
   address rules a kept definition uses (name, namespace, sanitized file
   name).

   A definition's source is the verbatim rewrite-clj slice of the form that
   defined it, so a kept file shows the bytes the model wrote. Only top-level
   defining forms count: a `def` inside `do`/`when`/`comment` has no stable
   address and is deliberately not kept."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(def eval-definitions-type
  "The `:custom-type` a session entry carrying eval definitions uses."
  :eval-definitions)

(def ^:private defining-heads
  "The top-level defining forms the eval tool tracks. `defmethod` is
   addressed by multimethod + dispatch (see `method-address`)."
  #{"def" "defn" "defn-" "defonce" "defmulti" "defprotocol" "defrecord"
    "deftype" "defmacro" "declare" "defmethod"})

(defn- form-head [form]
  (let [h (first form)]
    (when (symbol? h) (name h))))

(defn- ns-form? [form]
  (= "ns" (form-head form)))

(defn- ns-of [form fallback]
  (let [n (second form)]
    (if (symbol? n) (str n) fallback)))

(defn- def-name [form]
  (let [n (second form)]
    (when (symbol? n) n)))

(defn- name-key
  "The session address of NAMED in NS: a qualified name keeps its own
   namespace, an unqualified one is qualified by the current NS."
  [named ns]
  (if (namespace named) (str named) (str ns "/" named)))

(defn- def-record [node form ns kind]
  (let [named (def-name form)]
    (when named
      {:name (name-key named ns)
       :ns (or (namespace named) ns)
       :kind kind
       :source (n/string node)})))

(defn- method-address
  "The address of a `defmethod` form: `<ns>/<multi> <dispatch>`, with the
   dispatch printed, so two methods of one multimethod stay distinct. A
   qualified multimethod keeps its own namespace."
  [form ns]
  (let [multi (second form)
        dispatch (nth form 2 nil)]
    (when (symbol? multi)
      (let [address (if (namespace multi) (str multi) (str ns "/" multi))]
        (str address " " (pr-str dispatch))))))

(defn- method-record [node form ns]
  (when-let [address (method-address form ns)]
    {:name address
     :ns ns
     :kind :defmethod
     :source (n/string node)}))

(defn- declare-records [node form ns]
  (keep (fn [named]
          (when (symbol? named)
            {:name (name-key named ns)
             :ns (or (namespace named) ns)
             :kind :declare
             :source (n/string node)}))
        (rest form)))

(defn- form-records [node form ns]
  (let [head (form-head form)]
    (cond
      (= head "declare") (declare-records node form ns)
      (= head "defmethod") (when-let [r (method-record node form ns)] [r])
      (contains? defining-heads head)
      (when-let [r (def-record node form ns (keyword head))] [r])
      :else nil)))

(defn parse
  "Parse CODE as top-level forms starting in CURRENT-NS (a string, `user`
   when nil). Returns `{:ns <the namespace the forms end in> :defs
   [record …]}` with one record per defining form in source order. A form
   that is not a top-level definition — `do`, `comment`, a reader conditional —
   contributes nothing.

   Transferring a reader error is the caller's job: a call whose code cannot
   be read fails at evaluation, and the `:keep` validation wants the error
   before anything runs."
  [code current-ns]
  (let [ns (or current-ns "user")]
    (if (str/blank? code)
      {:ns ns :defs []}
      (let [nodes (n/children (p/parse-string-all code))]
        (loop [nodes nodes
               current ns
               defs []]
          (if-let [node (first nodes)]
            (let [form (try (n/sexpr node) (catch Throwable _ nil))]
              (cond
                (not (sequential? form))
                (recur (rest nodes) current defs)

                (ns-form? form)
                (recur (rest nodes) (ns-of form current) defs)

                :else
                (recur (rest nodes) current
                       (into defs (form-records node form current)))))
            {:ns current :defs defs}))))))

(defn collapse
  "One record per name, the last occurrence winning and keeping that
   occurrence's position — a call that defines a name twice records its final
   source once."
  [defs]
  (let [defs (vec defs)
        last-index (reduce-kv (fn [m i d] (assoc m (:name d) i)) {} defs)]
    (vec (keep-indexed (fn [i d]
                         (when (= i (get last-index (:name d))) d))
                       defs))))

(defn- normalize-def [d]
  (when (and (map? d)
             (string? (:name d))
             (string? (:ns d))
             (string? (:source d)))
    (select-keys d [:name :ns :kind :source])))

(defn- remove-names
  "DEFS without the names in NAMES, order preserved."
  [defs names]
  (let [names (set names)]
    (vec (remove #(contains? names (:name %)) defs))))

(defn- add-def [defs d]
  (let [i (.indexOf ^java.util.List defs (:name d))]
    (if (neg? i)
      (conj defs d)
      (assoc defs i d))))

(defn- valid-def? [d]
  (and (map? d)
       (string? (:name d))
       (string? (:ns d))
       (string? (:source d))))

(defn- valid-removed? [removed]
  (or (nil? removed)
      (and (sequential? removed) (every? string? removed))))

(defn- valid-data?
  "A session entry's `:data`: `:defs` is a sequence of definition maps and,
   outside a snapshot, `:removed` a sequence of names."
  [data]
  (let [snapshot? (true? (:snapshot data))]
    (and (map? data)
         (or (nil? (:defs data))
             (and (sequential? (:defs data))
                  (every? valid-def? (:defs data))))
         (or snapshot? (valid-removed? (:removed data))))))

(defn fold-entries
  "Fold the session's `:eval-definitions` ENTRIES (branch order) into the
   effective definition vector. A `:snapshot` entry resets the fold to its
   `:defs`; a normal entry adds/replaces its `:defs` and deletes its
   `:removed`. A malformed entry is reported under `:failures` and skipped,
   so one broken line never takes the session's other definitions down."
  [entries]
  (loop [entries (seq entries)
         defs []
         failures []]
    (if-let [entry (first entries)]
      (let [data (:data entry)]
        (if-not (valid-data? data)
          (recur (rest entries) defs
                 (conj failures {:entry (:id entry)
                                 :error "malformed :eval-definitions entry"}))
          (let [snapshot? (true? (:snapshot data))
                normalized (keep normalize-def (:defs data))
                defs (if snapshot? (vec normalized) (reduce add-def defs normalized))
                defs (if snapshot? defs (remove-names defs (:removed data)))]
            (recur (rest entries) defs failures))))
      {:defs defs :failures failures})))

(defn definition-ids
  "The ids of the eval-definition entries along a branch, in branch order —
   the fingerprint an environment is reused against."
  [branch]
  (into [] (keep #(when (= eval-definitions-type (:custom-type %)) (:id %)))
        branch))

(defn sanitize
  "A name part safe for one file name: everything outside the Clojure symbol
   alphabet becomes `_`, collapsed. The empty result is `def`."
  [s]
  (let [s (-> (str s)
              (str/replace #"[^A-Za-z0-9_.-]+" "_")
              (str/replace #"^_+|_+$" ""))]
    (if (seq s) s "def")))

(defn name-part
  "The part of a definition address after its namespace — the file name
   before sanitization (`area :rectangle` for a multimethod)."
  [record]
  (let [name (:name record)
        ns (:ns record)]
    (if (and ns (str/starts-with? name (str ns "/")))
      (subs name (inc (count ns)))
      name)))

(defn ns-dir
  "The directory name for NS inside an eval root. A namespace cannot carry a
   path separator or be `.`/`..`; both would escape the root."
  [ns]
  (let [dir (str/replace (str (or ns "user")) "/" "_")]
    (if (or (str/blank? dir) (= dir ".") (= dir "..")) "_" dir)))
