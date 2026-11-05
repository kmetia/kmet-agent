(ns kmet.app.eval.definitions
  "Pure definition tracking for the eval tool: parsing a call's top-level
   defining forms and the address rules a library definition uses (name,
   namespace, sanitized file name).

   A definition's source is the verbatim rewrite-clj slice of the form that
   defined it, so a library file shows the bytes the model wrote. Only
   top-level defining forms count: a `def` inside `do`/`when`/`comment` has no
   stable address and is deliberately not written."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

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
  "The definition address of NAMED in NS: a qualified name keeps its own
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
   be read fails at evaluation."
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

(defn split-ns
  "Split CONTENT into its first top-level `(ns …)` form string and the rest
   of the file. A generated library file has one ns form; the evaluation
   replays the form, installs the standard aliases after it, then evaluates
   BODY, so aliases survive the ns form."
  [content]
  (let [nodes (n/children (p/parse-string-all content))]
    (loop [nodes nodes
           ns-form nil
           body []]
      (if-let [node (first nodes)]
        (let [form (try (n/sexpr node) (catch Throwable _ nil))]
          (if (and (nil? ns-form) (sequential? form) (ns-form? form))
            (recur (rest nodes) (n/string node) body)
            (recur (rest nodes) ns-form (conj body (n/string node)))))
        {:ns-form ns-form
         :body (apply str body)}))))

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
