(ns kmet.libs.loader.memory
  "In-memory Loader backend: a loader over a plain source map. Used by the
   loader conformance suite and as the demo/backend reference — it models
   the full contract (locate, read-at-load, resources, unload) without a
   code backend, so `load` of a namespace returns a handle describing what
   was read rather than evaluating it.

   SOURCES is a map (or an atom of one, so callers can mutate it — the
   vanished-source case in the conformance suite):

     {:vars       {'my.lib/foo (atom …)}          ; :var hits → the cell
      :namespaces {\"my.lib\" {:source \"(ns my.lib)\"}} ; :ns hits → read at load
      :handles    {\"other.lib\" handle}             ; :ns hits, pre-linked
      :classes    {\"My.Class\" {:registration …}}   ; :class hits
      :resources  {\"my/lib/cfg.edn\" \"content\"}}  ; :resource hits → opened at load

   Namespace keys may be strings or symbols; a `:namespaces` entry is a
   source string, `{:source …}` (read at load into a
   `{:namespace :source :load? :loader}` handle) or `{:handle …}`
   (already-linked, returned as-is). Resource values are strings, byte
   arrays or thunks; `open-hit` turns them into streams."
  (:require [clojure.java.io :as io]
            [kmet.libs.loader :as loader]))

(defn- source-atom
  "SOURCES as the internal atom: maps are wrapped, atoms pass through."
  [sources]
  (cond
    (map? sources) (atom sources)
    (instance? clojure.lang.Atom sources) sources
    :else (throw (ex-info "memory sources must be a map or an atom of a map"
                          {:type :loader/bad-sources :value sources}))))

(defn- lookup
  "Look NM up in the map at KEY of SOURCES, trying the string and the
   symbol spelling."
  [sources key nm]
  (let [m (get @sources key {})]
    (or (get m nm) (get m (symbol nm)))))

(defn- ns-entry
  [sources nm]
  (or (lookup sources :namespaces nm)
      (get (:handles @sources) nm)))

(defn- locate
  [sources]
  (fn [req]
    (let [nm (:name req)]
      (case (:kind req)
        :var (when-let [cell (lookup sources :vars nm)]
               [{:kind :var :cell cell}])
        :ns (or (when-let [h (get (:handles @sources) nm)]
                  [{:kind :ns :handle h}])
                (when-let [entry (lookup sources :namespaces nm)]
                  [(if (and (map? entry) (contains? entry :handle))
                     {:kind :ns :handle (:handle entry)}
                     {:kind :ns :file (str "memory://" nm)})]))
        :class (when-let [entry (lookup sources :classes nm)]
                 [(cond
                    (and (map? entry) (contains? entry :class)) {:kind :class :class (:class entry)}
                    (and (map? entry) (contains? entry :registration))
                    {:kind :class :registration (:registration entry)}
                    :else {:kind :class :registration entry})])
        :resource (when (contains? (:resources @sources) nm)
                    [{:kind :resource :url (str "memory://" nm)}])
        nil))))

(defn- read-ns
  [sources]
  (fn [home hit req]
    (let [entry (ns-entry sources (:name hit))
          source (cond
                   (string? entry) entry
                   (map? entry) (:source entry)
                   :else nil)]
      (if source
        {:namespace (symbol (:name hit))
         :source source
         :load? (:load? req true)
         :loader home}
        (throw (ex-info (str "in-memory source for namespace " (:name hit) " not found")
                        {:type :loader/unreadable :kind :ns :name (:name hit)}))))))

(defn- open-resource
  [sources]
  (fn [_l hit]
    (let [v (get (:resources @sources) (:name hit))
          v (if (fn? v) (v) v)]
      (if (some? v)
        (io/input-stream (if (string? v) (.getBytes v "UTF-8") v))
        (throw (ex-info (str "in-memory resource " (:name hit) " not found")
                        {:type :loader/unreadable :kind :resource :name (:name hit)}))))))

(defn memory
  "An in-memory Loader over SOURCES (see the namespace docstring). OPTS:
   :id (diagnostics id), :parent (delegate loader), :release-fn (teardown
   called by `unload!`)."
  ([sources] (memory sources nil))
  ([sources {:keys [id parent release-fn]}]
   (let [at (source-atom sources)]
     (loader/make-loader
      (cond-> {:parent parent
               :locate-fn (locate at)
               :ns-load-fn (read-ns at)
               :open-fn (open-resource at)}
        id (assoc :id id)
        release-fn (assoc :release-fn release-fn))))))
