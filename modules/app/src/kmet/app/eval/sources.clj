(ns kmet.app.eval.sources
  "The `:core` eval source and the validation rules for every source.

   A source is data plus values, read when an eval environment is built and on
   later calls. Validation happens here so `kmet.app.eval` only wires the
   values into SCI: a bad shape, a reserved namespace or key, or a reserved
   `/eval` verb refuses the whole source, never the eval call."
  (:require [clojure.string :as str]
            [kmet.libs.clj-edit :as clj-edit]))

(def core-source-id
  "The one source allowed to contribute to the `user` namespace."
  :core)

(def reserved-source-namespaces
  "Namespaces an extension source may not contribute to: the core source's
   `user` and `clj`, the eval namespace itself, and the fixed namespaces the
   sandbox installs around it."
  #{"user" "clj" "eval" "clojure" "clojure.core"
    "sandbox" "tools" "babashka.process" "kmet.libs.json"})

(def reserved-source-keys
  "Individual built-in keys outside the reserved namespaces. A source that
   contributes one is refused as a whole."
  #{"eval/library" "eval/forget" "eval/sources" "eval/help"
    "user/edit" "user/write-file"
    "clj/edit-form" "clj/replace-sexp" "clj/paren-repair"})

(def ^:private reserved-command-names
  "The unqualified `/eval` verbs a source may not replace."
  #{"list" "search" "drop" "sources" "help" "--help" "-h"})

(defn core-source
  "The `:core` source's contribution: the two file verbs under `user` and the
   Clojure editing verbs under `clj`. CWD is the call's source cwd, so a
   relative path in a verb resolves like the tool it mirrors."
  [{:keys [verbs cwd]}]
  (let [with-cwd #(assoc % :cwd cwd)]
    {:doc (str "Core file verbs: (edit path old new), (write-file path content); "
               "Clojure editing: (clj/edit-form args), (clj/replace-sexp args), "
               "(clj/paren-repair args).")
     :ns {'user {'edit (:edit verbs)
                 'write-file (:write-file verbs)}
          'clj {'edit-form (fn [args] (clj-edit/edit-form (with-cwd args)))
                'replace-sexp (fn [args] (clj-edit/replace-sexp (with-cwd args)))
                'paren-repair (fn [args] (clj-edit/paren-repair (with-cwd args)))}}}))

(defn source-key
  "The address of one contributed value — `user/edit`, `license/add!`."
  [namespace name]
  (str namespace "/" name))

(defn reserved-namespace?
  "Is NAMESPACE reserved for a built-in source?"
  [namespace]
  (contains? reserved-source-namespaces (str namespace)))

(defn reserved-key?
  "Is KEY a built-in key an extension source may not contribute?"
  [key]
  (contains? reserved-source-keys key))

(defn core-source?
  "Is SOURCE-ID the core source?"
  [source-id]
  (= core-source-id source-id))

(defn source-doc
  "A source's one-line description, when it is a string."
  [doc]
  (when (and (string? doc) (not (str/blank? doc)))
    (str/trim doc)))

(defn- bad-source! [id message]
  (throw (ex-info message {:type :eval/bad-source :source id})))

(defn- source-symbol
  [id what x]
  (cond
    (symbol? x) x
    (keyword? x) (symbol (name x))
    (string? x) (let [x (str/trim x)]
                  (if (str/blank? x)
                    (bad-source! id (str "eval source " (pr-str id)
                                         " has a blank " what))
                    (symbol x)))
    :else (bad-source! id (str "eval source " (pr-str id) " has a bad "
                               what ": " (pr-str x)))))

(defn- source-verb [id x]
  (cond
    (keyword? x) x
    (symbol? x) (keyword (name x))
    (string? x) (keyword x)
    :else (bad-source! id (str "eval source " (pr-str id)
                               " has a bad command verb: " (pr-str x)))))

(defn- normalize-namespaces
  "Validate one `:ns`/`:macros` map into `{namespace {name value}}`."
  [id what ns-map]
  (when-not (map? ns-map)
    (bad-source! id (str "eval source " (pr-str id) " :" what
                         " must be a namespace map")))
  (into {}
        (map (fn [[ns-key vars]]
               (let [ns-name (source-symbol id "namespace" ns-key)]
                 (when-not (map? vars)
                   (bad-source! id (str "eval source " (pr-str id)
                                        " namespace " ns-name " must be a map")))
                 [ns-name
                  (into {}
                        (map (fn [[name value]]
                               (when (and (= what "macros") (not (fn? value)))
                                 (bad-source! id (str "eval source " (pr-str id)
                                                      " macro " ns-name "/" name
                                                      " must be a function")))
                               [(source-symbol id "name" name) value]))
                        vars)])))
        ns-map))

(defn- normalize-commands
  [id commands]
  (when-not (map? commands)
    (bad-source! id (str "eval source " (pr-str id) " :commands must be a map")))
  (into {}
        (map (fn [[verb command]]
               (let [verb (source-verb id verb)]
                 (when (and (nil? (namespace verb))
                            (contains? reserved-command-names (name verb)))
                   (bad-source! id (str "eval source " (pr-str id)
                                        " may not replace /eval " (name verb))))
                 (when-not (and (map? command) (fn? (:run command)))
                   (bad-source! id (str "eval source " (pr-str id) " command "
                                        verb " needs :run")))
                 (when (and (some? (:description command))
                            (not (string? (:description command))))
                   (bad-source! id (str "eval source " (pr-str id) " command "
                                        verb " :description must be a string")))
                 [verb (select-keys command [:description :run])])))
        commands))

(defn- check-reserved!
  [id ns-map macros contributed]
  (when-not (core-source? id)
    (when-let [namespace (first (filter reserved-namespace?
                                        (concat (keys ns-map) (keys macros))))]
      (bad-source! id (str "eval source " (pr-str id)
                           " may not contribute to " namespace)))
    (when-let [key (first (filter reserved-key? contributed))]
      (bad-source! id (str "eval source " (pr-str id)
                           " may not contribute the built-in key " key)))))

(defn- contribution-values
  [ns-map macros]
  (into {}
        (concat
         (for [[ns vars] ns-map, [name value] vars]
           [(source-key ns name) {:value value :macro? false}])
         (for [[ns vars] macros, [name value] vars]
           [(source-key ns name) {:value value :macro? true}]))))

(defn normalize-source
  "Validate one `sources-fn` return. Returns the source's values, commands
   and keys, or throws `:eval/bad-source` for a bad shape, a reserved
   namespace/key, or a reserved command verb."
  [id value]
  (let [value (or value {})]
    (when-not (map? value)
      (bad-source! id (str "eval source " (pr-str id) " must return a map")))
    (when (and (some? (:doc value)) (not (string? (:doc value))))
      (bad-source! id (str "eval source " (pr-str id) " :doc must be a string")))
    (let [ns-map (normalize-namespaces id "ns" (or (:ns value) {}))
          macros (normalize-namespaces id "macros" (or (:macros value) {}))
          commands (normalize-commands id (or (:commands value) {}))
          values (contribution-values ns-map macros)
          keys (set (keys values))]
      (check-reserved! id ns-map macros keys)
      {:id id
       :doc (source-doc (:doc value))
       :values values
       :keys keys
       :commands commands})))
