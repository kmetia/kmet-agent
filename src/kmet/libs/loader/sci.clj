(ns kmet.libs.loader.sci
  "SCI code backend for kmet.libs.loader: a Loader that locates namespace
   sources, reads and evaluates them in a SCI context, and routes SCI's own
   `require` back through the loader.

   The context is built here; the caller injects the share list
   (`:namespaces`, e.g. the kmet.extension contract plus the shared
   kmet.tui.* / kmet.libs.* layers copied with `sci/copy-var*`), the
   sci/init knobs (`:sci-opts` — :classes, :imports, :features, …) and the
   source provider:

     (sci-loader
       {:id         \"ext:foo\"
        :sources    (fn [ns-sym] {:file display :source source-or-thunk})
        :namespaces shared-ns-map
        :parent     (loader/allow (loader/root) #{'kmet.tui})})

   `:sources` is consulted by `find` (where the source is — :source may be
   a 0-arg thunk and stays unforced) and again by `load` (what it is), so a
   hit stays data and the read happens at load. A map of sources is
   accepted as a convenience; when the provider answers, it owns the
   validation error for a namespace it cannot serve.

   SCI's `require` inside evaluated code calls back into the loader
   (`load`), so nested requires get the same linkage, delegate walk,
   policies and in-flight claims as any other load — a circular require is
   detected by the generic body and a require after `unload!` fails like
   any new load. A namespace that resolves through the delegate by
   reference (a loaded host namespace, no source) is rejected with an
   actionable error: shared namespaces must be injected through
   `:namespaces`, not evaluated from nothing. A namespace already in the
   context (loaded by a SCI-internal require before the link table knew)
   is re-used, not re-evaluated.

   Hosts: babashka and Jolt bundle `sci.core`; a plain JVM needs
   org.borkdude/sci on the classpath.

   Host boundary: inside an evaluation, babashka's namespace-registry
   functions (`find-ns`, `ns-resolve`, `find-var`, `all-ns`) are scoped to
   the SCI context being evaluated, so a delegate that resolves *host*
   namespaces (the root loader) is invisible to the require path there,
   while on Jolt it is visible. Correct use must not depend on that
   difference: shared host layers are injected through `:namespaces` (the
   caller's share list). A require the provider cannot serve fails with
   `:loader/unreadable` on both hosts.

   A SCI context is not thread-safe: same-key loads are serialized by the
   generic in-flight claim, but callers must not evaluate different keys
   in one context concurrently."
  (:require [kmet.libs.loader :as loader]
            [sci.core :as sci]))

;; ─── Source provider ───────────────────────────────────────────────────────

(defn- source-provider
  "Normalize :sources — nil, a provider fn, or a convenience map of ns-sym
   (or ns-string) → source string or source map — to a provider fn."
  [sources]
  (cond
    (nil? sources) nil
    (fn? sources) sources
    (map? sources) (fn [ns-sym]
                     (let [v (or (get sources ns-sym)
                                 (get sources (str ns-sym)))]
                       (cond
                         (string? v) {:file (str ns-sym) :source v}
                         (map? v) v
                         :else nil)))
    :else (throw (ex-info "sci loader :sources must be a map or a fn"
                          {:type :loader/bad-sources :value sources}))))

(defn- source-ref
  "The provider's answer for NS-SYM, UNFORCED: {:file string :source
   string-or-thunk} or nil. `find` uses only :file; `load` forces :source
   through `read-source`."
  [provider ns-sym]
  (when-let [m (when provider (provider ns-sym))]
    (cond
      (string? m) {:file (str ns-sym) :source m}
      (fn? m) {:file (str ns-sym) :source m}
      (map? m) {:file (or (:file m) (str ns-sym)) :source (:source m)}
      :else nil)))

(defn- read-source
  [ref]
  (let [s (:source ref)]
    (if (fn? s) (s) s)))

(defn- unreadable!
  [l ns-sym msg]
  (throw (ex-info (str "loader " (:id l) " cannot read namespace " ns-sym ": " msg)
                  {:type :loader/unreadable :kind :ns :name (str ns-sym)})))

;; ─── Locate / read ─────────────────────────────────────────────────────────

(defn- locate
  "The context's own world as hits: namespaces already in the context, the
   vars and classes it can resolve, and resources the optional resource
   provider knows. The source provider answers where a not-yet-loaded
   namespace lives, not what it contains."
  [ctx provider resource-fn]
  (fn [req]
    (let [nm (:name req)
          sym (symbol nm)]
      (case (:kind req)
        :ns (or (when-let [n (sci/find-ns ctx sym)]
                  {:kind :ns :handle n})
                (when-let [ref (source-ref provider sym)]
                  {:kind :ns :file (:file ref)}))
        :var (let [v (sci/resolve ctx sym)]
               (when (instance? sci.lang.Var v)
                 {:kind :var :cell v}))
        :class (let [c (sci/resolve ctx sym)]
                 (when (class? c) {:kind :class :class c}))
        :resource (when-let [r (and resource-fn (resource-fn nm))]
                    (cond
                      (string? r) {:kind :resource :url r}
                      (map? r) (assoc r :kind :resource)
                      :else {:kind :resource :url (str r)}))
        nil))))

(defn- read-ns
  "The generic body's reader for :ns hits: evaluate the namespace source in
   CTX and answer with the SCI namespace. A namespace already in the
   context is re-used — a SCI-internal `require` may have loaded it without
   the link table knowing."
  [ctx provider eval-fn]
  (fn [home hit req]
    (let [ns-sym (symbol (:name hit))]
      (or (sci/find-ns ctx ns-sym)
          (let [ref (source-ref provider ns-sym)
                source (some-> ref read-source)]
            (when-not (string? source)
              (unreadable! home ns-sym "no source available"))
            (eval-fn ctx {:namespace ns-sym
                          :file (:file ref)
                          :source source
                          :load? (:load? req)})
            (or (sci/find-ns ctx ns-sym)
                (unreadable! home ns-sym "the source did not define it")))))))

;; ─── SCI require → loader ──────────────────────────────────────────────────

(defn- require-load-fn
  "SCI's :load-fn — a `require` (or an (ns … (:require …)) clause in
   evaluated source) is routed through the loader itself, so a nested
   require gets the same linkage, delegate walk, policies and in-flight
   claims as any other load. `load` reads and evaluates the source; SCI is
   handed an empty source and only records the namespace as loaded.

   A miss becomes the actionable :loader/unreadable — SCI needs a source,
   and a name the loader cannot serve is not a source it can evaluate
   (the same error a delegate-resolved, source-less namespace gets below,
   so the diagnostic does not depend on which host could see a host
   namespace from inside the evaluation)."
  [ctx-holder l-holder]
  (fn [{:keys [namespace]}]
    (let [l @l-holder
          ctx @ctx-holder
          nm (str namespace)
          _ (try
              (loader/load l {:kind :ns :name nm :load? true})
              (catch Throwable e
                (if (and (instance? clojure.lang.ExceptionInfo e)
                         (= :loader/miss (:type (ex-data e))))
                  (throw (ex-info (str "loader " (:id l) " has no source for namespace " nm
                                       "; shared host namespaces must be injected at "
                                       "construction (:namespaces)")
                                  {:type :loader/unreadable :kind :ns :name nm}
                                  e))
                  (throw e))))]
      (when-not (sci/find-ns ctx (symbol nm))
        (throw (ex-info (str "loader " (:id l) " resolved namespace " nm
                             " by reference, without a source in this context; "
                             "shared namespaces are injected at construction (:namespaces)")
                        {:type :loader/unreadable :kind :ns :name nm})))
      {:file nm :source ""})))

;; ─── Constructor ───────────────────────────────────────────────────────────

(defn- default-eval
  [ctx {:keys [source]}]
  (sci/eval-string* ctx source))

(defn sci-loader
  "Build a Loader over a SCI context. OPTS:

   - :id         diagnostics id
   - :parent     delegate loader (consulted before this loader's sources)
   - :sources    (fn [ns-sym]) → {:file … :source string-or-thunk} | a
                 source string | nil; or a map of ns-sym/ns-string → source
   - :resources  (fn [name]) → url | {:url …} | nil for resource requests
   - :namespaces SCI share list, injected by reference (sci/init :namespaces)
   - :sci-opts   extra sci/init opts (:classes, :imports, :features, …)
   - :eval-fn    (fn [ctx {:keys [namespace file source load?]}] …) —
                 override the default `sci/eval-string*` (e.g. to register
                 classes missed during analysis and retry)
   - :open-fn    resource opener override
   - :release-fn teardown hook called by `unload!`

   `:load?` is passed through to the eval opts; SCI evaluates a namespace
   source whole (its ns form pulls its dependencies in), so v1 makes no
   distinction between require semantics and load-one.

   The context is the loader's `context` (the escape hatch); `unload!`
   leaves SCI objects already handed out alive and just closes the loader."
  [{:keys [id parent sources resources namespaces sci-opts eval-fn open-fn release-fn]}]
  (let [provider (source-provider sources)
        eval-fn (or eval-fn default-eval)
        ctx-holder (atom nil)
        l-holder (atom nil)
        sci-opts (merge sci-opts
                        {:load-fn (require-load-fn ctx-holder l-holder)}
                        (when (some? namespaces) {:namespaces namespaces}))
        ctx (sci/init sci-opts)
        l (loader/make-loader
           {:id id
            :parent parent
            :context ctx
            :locate-fn (locate ctx provider resources)
            :ns-load-fn (read-ns ctx provider eval-fn)
            :open-fn open-fn
            :release-fn release-fn})]
    (reset! ctx-holder ctx)
    (reset! l-holder l)
    l))
