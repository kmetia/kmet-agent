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
  "Built-in keys refused even when a namespace check would allow them. A
   source that contributes one is refused as a whole."
  #{"eval/library" "eval/forget" "eval/sources" "eval/help" "eval/describe"
    "eval/move" "user/edit" "user/write-file"
    "clj/edit-form" "clj/replace-sexp" "clj/paren-repair"})

(def ^:private reserved-command-names
  "The unqualified `/eval` verbs a source may not replace."
  #{"list" "search" "drop" "move" "sources" "help" "--help" "-h"})

(defn- described
  "Attach a verb's model-facing interface to its function value. The keys are
   the ones a var uses; `:source` is the wrapper's implementation text, since
   a source verb has no library file."
  [f doc arglists source]
  (with-meta f {:doc doc :arglists arglists :source source}))

(defn core-source
  "The `:core` source's contribution: the two file verbs under `user` and the
   Clojure editing verbs under `clj`. CWD is the call's source cwd, so a
   relative path in a verb resolves like the tool it mirrors."
  [{:keys [verbs cwd]}]
  (let [with-cwd #(assoc % :cwd cwd)]
    {:doc (str "Core file verbs: (edit path old new), (write-file path content); "
               "Clojure editing: (clj/edit-form args), (clj/replace-sexp args), "
               "(clj/paren-repair args).")
     :ns {'user {'edit (described
                        (:edit verbs)
                        "Replace OLD-TEXT with NEW-TEXT in PATH; returns the shared edit result."
                        '([path old-text new-text])
                        "(fn [path old-text new-text] (edit-tool/edit-file path [{:old-text (str old-text) :new-text (str new-text)}] signal))")
                 'write-file (described
                              (:write-file verbs)
                              "Write CONTENT to PATH; returns the shared write result."
                              '([path content])
                              "(fn [path content] (write-tool/write-file path (str content) signal))")}
          'clj {'edit-form (described
                            (fn [args] (clj-edit/edit-form (with-cwd args)))
                            "Apply a structured whole-form edit (same arguments as the clojure_edit tool)."
                            '([args])
                            "(fn [args] (clj-edit/edit-form (assoc args :cwd cwd)))")
                'replace-sexp (described
                               (fn [args] (clj-edit/replace-sexp (with-cwd args)))
                               "Replace one form with another (same arguments as clojure_edit_replace_sexp)."
                               '([args])
                               "(fn [args] (clj-edit/replace-sexp (assoc args :cwd cwd)))")
                'paren-repair (described
                               (fn [args] (clj-edit/paren-repair (with-cwd args)))
                               "Repair unbalanced delimiters (same arguments as clojure_paren_repair)."
                               '([args])
                               "(fn [args] (clj-edit/paren-repair (assoc args :cwd cwd)))")}}}))

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

(defn- check-meta!
  "Refuse an optional value-metadata field that is present but invalid."
  [id key field valid? expected value]
  (when (and (some? value) (not (valid? value)))
    (bad-source! id (str "eval source " (pr-str id) " value " key
                         " " field " metadata must be " expected))))

(defn- value-interface
  "Validate and keep a contributed value's optional `:doc`, `:arglists` and
   `:source` metadata. Other metadata is ignored."
  [id key value]
  (let [{:keys [doc arglists source]} (meta value)]
    (check-meta! id key ":doc" string? "a string" doc)
    (check-meta! id key ":arglists" sequential? "sequential" arglists)
    (check-meta! id key ":source" string? "a string" source)
    (cond-> {}
      (some? doc) (assoc :doc doc)
      (some? arglists) (assoc :arglists arglists)
      (some? source) (assoc :source source))))

(defn- verb-info
  "The listing entry for one contributed value: name, kind, doc and, when
   supplied, arglists."
  [[key entry]]
  (cond-> {:name key
           :kind (if (:macro? entry) :macro :verb)
           :doc (or (not-empty (:doc entry)) "(no docstring)")}
    (:arglists entry) (assoc :arglists (:arglists entry))))

(defn- contribution-values
  [id ns-map macros]
  (into {}
        (for [[source-maps macro?] [[ns-map false] [macros true]]
              [ns vars] source-maps
              [name value] vars
              :let [key (source-key ns name)]]
          [key (merge {:value value :macro? macro?}
                      (value-interface id key value))])))

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
          values (contribution-values id ns-map macros)
          keys (set (keys values))]
      (check-reserved! id ns-map macros keys)
      {:id id
       :doc (source-doc (:doc value))
       :values values
       :keys keys
       :verbs (mapv verb-info (sort-by key values))
       :commands commands})))
