(ns kmet.loader.jolt
  "Jolt's own loader (`jolt.loader`) behind kmet's Loader protocol.

   This is the native backend for the Jolt host: contexts are real Jolt
   namespaces with real cells, the host's namespaces come back by reference,
   and the classloader facade is the loader's own. Protocols do not unify —
   `jolt.loader` lives in the Jolt runtime, `kmet.loader.core` is the portable
   library — so this namespace forwards each method to the Jolt loader behind
   it instead of aliasing it, and its constructors accept either an adapter or
   a raw Jolt loader (`allow` over a `classpath` needs one object to wrap).

   The whole implementation sits in the file's single `#?(:jolt ...)` branch:
   Jolt reads reader conditionals in `.clj` sources (its own sources rely on
   it), and babashka and the JVM skip the branch — those hosts load this
   namespace empty and never touch `jolt.loader`. `kmet.app.extensions`
   chooses the backend at runtime (`(host/jolt?)`), so nothing else needs to
   know this namespace exists.

   `restrict` is the one behavior that is not a plain forward: the extension
   contract exposes a *set* of host namespaces, and a name outside it must
   stay invisible to a context's own roots instead of denying the request
   (see its docstring)."
  #?(:jolt (:require [clojure.string :as str]
                     [jolt.loader :as jl]
                     [kmet.loader.core :as loader])))

#?(:jolt
   (do
     (defrecord JoltLoader [loader]
       loader/Loader
       (find [_ req] (jl/find loader req))
       (resolve [_ req] (jl/resolve loader req))
       (load [_ x] (jl/load loader x))
       (parent [_] (some-> (jl/parent loader) ->JoltLoader))
       (unload! [_] (jl/unload! loader)))

     (defn- jolt-of
       "The raw Jolt loader behind L: the adapter's wrapped loader, or L itself
        (constructors take either, so a chain never double-wraps)."
       [l]
       (if (instance? JoltLoader l) (:loader l) l))

     (defn- name-allowed?
       "Does NM fall under one of NAMES — exact, or boundary-prefixed with \".\"
        (a namespace) or \"/\" (a resource directory). The same rule jolt's
        policies use."
       [names nm]
       (boolean
        (some (fn [p]
                (let [p (str p)]
                  (or (= p nm)
                      (str/starts-with? nm (str p "."))
                      (str/starts-with? nm (str p "/")))))
              names)))

     (defn root
       "The Jolt host as kmet's root loader: host namespaces, classes and
        classpath resources by reference, and the host's own loader for
        namespaces it has not loaded yet."
       []
       (->JoltLoader (jl/root)))

     (defn isolated
       "A context that delegates to nothing and locates nothing."
       []
       (->JoltLoader (jl/isolated)))

     (defn classpath
       "ROOTS searched in order, with OPTS:

          :id     diagnostics id
          :parent a delegate (adapter or raw Jolt loader), consulted first

        Eager: an unreadable root throws here, not at the first load."
       ([roots] (classpath roots nil))
       ([roots {:keys [id parent]}]
        (->JoltLoader (jl/classpath roots (cond-> {}
                                            id (assoc :id id)
                                            parent (assoc :parent (jolt-of parent)))))))

     (defn ->loader
       "A delegate over RESOLVE-FN — (fn [req]) answering hits, a hit or nil."
       [resolve-fn]
       (->JoltLoader (jl/->loader resolve-fn)))

     (defn allow
       "`l` restricted to NAMES; a request outside the whitelist is denied."
       [l names]
       (->JoltLoader (jl/allow (jolt-of l) names)))

     (defn deny
       "`l` minus NAMES; a request inside the blacklist is denied."
       [l names]
       (->JoltLoader (jl/deny (jolt-of l) names)))

     (defn host-view
       "The host root as an extension context sees it: the names in NAMES (and
        their vars) pass through, as do the host's resources and classes; any
        other :ns/:var name is a MISS, not a denial, so the context's own roots
        still get their turn; and the loader's own namespaces are rejected
        outright — `kmet.loader.*` is host machinery, never part of the
        extension contract, and a plain filter would answer it with the
        misleading miss a typo gets.

        `allow` is the policy combinator (a denial propagates, by design); this
        is a view, which is what a caller needs when its own roots may serve a
        name the host also has."
       [l names]
       (let [inner (jolt-of l)]
         (->JoltLoader
          (jl/->loader
           (fn [req]
             (if (not (contains? #{:ns :var} (:kind req)))
               (jl/find inner req)
               (let [nm (:name req)]
                 (when (name-allowed? #{"kmet.loader"} nm)
                   (throw (ex-info (str "extension requires " nm
                                        " — the loader is host machinery and is not"
                                        " part of the extension contract")
                                   {:type :loader/denied :kind (:kind req) :name nm})))
                 (when (name-allowed? names nm)
                   (jl/find inner req)))))))))

     (defn with-loader*
       "Run THUNK with L ambient — `clojure.java.io/resource` (1-arity) and
        `clojure.lang.RT/baseLoader` resolve through L inside it."
       [l thunk]
       (jl/with-loader* (jolt-of l) thunk))

     (defn current-loader
       "The ambient loader, or nil — wrapped, so it composes with the kmet
        constructors."
       []
       (some-> (jl/current-loader) ->JoltLoader))

     (defn unloaded?
       "Has L been unloaded? Jolt's predicate (kmet's `unloaded?` reads the
        portable record's state and is not meaningful for this adapter)."
       [l]
       (jl/unloaded? (jolt-of l)))

     (defn status
       "Jolt's diagnostics map for L (kmet's `status` is the portable view)."
       [l]
       (jl/status (jolt-of l)))))
