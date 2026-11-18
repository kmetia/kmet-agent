(ns kmet.app.eval.seed
  "SCI seeding for mounted eval sources: build the vars a source
   contributes, restore a source value after its model shadow is dropped,
   and expose the source layer already resolved for a rebuilt environment."
  (:require [clojure.string :as str]
            [kmet.app.tools.script :as script]
            [sci.core :as sci]))

(defn source-var
  "The SCI var for one source ENTRY, with DISABLED names bound to the
   error stub so a namespace alias still requires."
  [ns-name name {:keys [value doc arglists] :as entry} disabled]
  (let [ns (sci/create-ns ns-name)
        name-string (str ns-name "/" name)
        value (if (contains? disabled name-string)
                (script/disabled-verb-stub name-string)
                value)]
    (sci/new-var name value
                 (cond-> {:ns ns :name name}
                   (:macro? entry) (assoc :macro true)
                   (some? doc) (assoc :doc doc)
                   (some? arglists) (assoc :arglists arglists)))))

(defn source-namespaces
  "The SCI vars for VALUES not excluded by SKIP?, with DISABLED keys bound to
   the error stub so a namespace alias still requires. A source value and a
   fixed namespace (the core `user` helpers) merge in one map."
  ([values skip?] (source-namespaces values skip? #{}))
  ([values skip? disabled]
   (reduce-kv
    (fn [acc key entry]
      (if (skip? key)
        acc
        (let [i (str/index-of key "/")
              ns-name (symbol (subs key 0 i))
              name (symbol (subs key (inc i)))]
          (assoc-in acc [ns-name name]
                    (source-var ns-name name entry disabled)))))
    {}
    values)))

(defn source-known?
  "Does the model own NAME in this environment (a library definition, an
   unwritten live definition, or a live shadow of a source key)? A source
   never overwrites it."
  [env name]
  (or (get @(:library env) name)
      (contains? @(:unwritten env) name)
      (contains? @(:model-shadows env) name)))

(defn seed-source-value!
  "Restore one source value after a model shadow is dropped. Returns an error
   string or nil."
  [env name entry]
  (try
    (doseq [[ns-name vars] (source-namespaces {name entry} (constantly false)
                                              @(:disabled-verbs env))]
      (sci/add-namespace! (:sci-ctx env) ns-name vars))
    nil
    (catch Throwable t (ex-message t))))

(defn current-source-seed
  "The source layer already resolved for ENV, without invoking its fns again.
   Used by the first call after a rebuild: the build read the source layer to
   compile the library, so re-reading it immediately would double every
   source's work (and its side effects)."
  [env]
  {:namespaces (source-namespaces @(:source-values env)
                                  #(source-known? env %)
                                  @(:disabled-verbs env))
   :info @(:source-info env)
   :errors @(:source-errors env)
   :commands {}})
