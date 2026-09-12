(ns kmet.tui.macros
  "TUI render-cache layer over the standalone reactive engine
   (kmet.libs.reakt — reactions, cursors, batching; no TUI concepts there).

   track! — reactive render cache: rewrites @atom reads in the render body
   to tracked derefs (kmet.libs.reakt/tracked-deref), so any atom change
   invalidates the cache automatically and setters need no manual
   invalidate call. It must be a macro because deref rewriting happens at
   compile time. Also hosts with-let — per-instance fn-component state —
   and defcomponent."
  (:require [kmet.libs.reakt :as reakt]))

(def ^:private deref-form?
  "Only the 1-arg (deref x) form — @x reads as (clojure.core/deref x).
   Multi-arg deref (timeout variants) is left untouched."
  (fn [f]
    (and (seq? f)
         (symbol? (first f))
         (or (= 'clojure.core/deref (first f))
             (= 'deref (first f)))
         (= 2 (count f)))))

(defn- rewrite-derefs
  "Replace every @atom / (deref atom) form in FORM with a tracked-deref call,
   so derefs during a track! render are recorded. Quoted forms are skipped
   (quoted deref symbols are data, not reads)."
  [form]
  (cond
    (or (symbol? form) (not (coll? form))) form
    (and (seq? form) (= 'quote (first form))) form
    (deref-form? form) (list 'kmet.libs.reakt/tracked-deref (rewrite-derefs (second form)))
    (seq? form) (apply list (map rewrite-derefs form))
    (vector? form) (mapv rewrite-derefs form)
    (map? form) (into (empty form) (map (fn [[k v]] [(rewrite-derefs k) (rewrite-derefs v)]) form))
    (set? form) (into (empty form) (map rewrite-derefs form))
    :else form))

(defn- tracker-key
  "Stable per-component watch key. add-watch replaces an existing watch with
   the same key, so re-registration after re-renders is idempotent."
  [component]
  (keyword (str "track!" (System/identityHashCode component))))

;; Watch key → {:component c :atoms #{...}} for every live track! scope
;; (the set mixes plain atoms and reactive refs — both watched per pass).
;; track-render keeps it current per pass (dropping watches for refs a
;; branch switch stopped reading); remove-track-watches! tears down from
;; it on dispose. Without it, watches would outlive their component —
;; zombie watchers pinning the component and firing invalidate-cache on
;; every write forever.
(defonce ^:private watch-registry (atom {}))

(declare invalidate-cache schedule-frame!)

(defn- component-cache-atom
  "The render cache field: :cache-atom (current) or legacy :cache (Text/Box)."
  [component]
  (or (:cache-atom component) (:cache component)))

(defn track-render
  "Runtime implementation of track!. Runs RENDER-FN within a tracking scope
   and caches the result under WIDTH. Returns the cached result while every
   tracked atom still holds the value it was read with — watches invalidate
   on actual value changes (equal-value resets are no-ops), and the cache
   records the values AS READ (not a post-body snapshot), so an atom that
   changed mid-render makes the stored values disagree with the current
   ones on the next hit check and forces a re-render.
   Requires COMPONENT to have a :cache-atom (or legacy :cache) field.
   A body that invalidates itself mid-run (a render fn calling :invalidate)
   is not cached — the next render re-runs it with the fresh state."
  [component width render-fn]
  (let [cache-atom (component-cache-atom component)
        cache @cache-atom]
    (if (and cache
             (= (:width cache) width)
             (every? (fn [[a v]]
                       (let [cur (deref a)]
                         ;; identical? first: O(1) positive filter — the
                         ;; common case is an atom untouched since caching,
                         ;; whose current value IS the recorded object.
                         ;; Structural = only runs for atoms actually
                         ;; written in between (persistent collections give
                         ;; fresh roots on every update).
                         (or (identical? cur v)
                             (= cur v))))
                     (:values cache)))
      (:result cache)
      (let [tracked (atom {})]
        (binding [reakt/*tracking-scope* tracked]
          (let [cache-watch-key (keyword (str "track!cache" (System/identityHashCode component)))
                invalidated? (atom false)
                ;; Watch the cache atom itself: an invalidate mid-body (a
                ;; render fn calling :invalidate, or a tracked atom changing
                ;; while the body runs) resets it — a signal that this result
                ;; was built from stale state, so it must not be cached; the
                ;; next render re-runs the body with the fresh state.
                _ (add-watch cache-atom cache-watch-key
                             (fn [_ _ _ _] (reset! invalidated? true)))]
            (try
              (let [result (render-fn)
                    tracked-map @tracked
                    watch-key (tracker-key component)
                    handler (fn [_ _ old new]
                              ;; Skip invalidation when the value didn't actually
                              ;; change: renders are pure functions of tracked
                              ;; values, so an equal value means the cached result
                              ;; is still valid. (Clojure fires watches even on
                              ;; equal-value reset!/swap!; reactions already gate
                              ;; their notifications on = — the check is shared
                              ;; anyway.) identical? is the O(1) fast path;
                              ;; structural = catches persistent copies (fresh
                              ;; object, equal content).
                              (when-not (or (identical? old new)
                                            (= old new))
                                (invalidate-cache component)))
                    atoms (set (keys tracked-map))
                    prev-atoms (:atoms (get @watch-registry watch-key))]
                (doseq [a atoms]
                  (reakt/watch-ref a watch-key handler))
                ;; A previous pass's refs this pass no longer reads (branch
                ;; switch) lose their watches here — otherwise they fire
                ;; invalidate-cache forever on writes nobody consumes.
                (doseq [a prev-atoms]
                  (when-not (contains? atoms a)
                    (reakt/unwatch-ref a watch-key)))
                (swap! watch-registry assoc watch-key
                       {:component component :atoms atoms})
                (when-not @invalidated?
                  (reset! cache-atom {:width width
                                      :values tracked-map
                                      :result result}))
                result)
              (finally
                (remove-watch cache-atom cache-watch-key)))))))))

(defmacro track!
  "Reactive cache wrapper for IComponent render methods.

   Usage:
     (render [this width]
       (track! this width
         ;; render body — every @atom read is tracked automatically
         ...))

   Runs BODY, recording every atom deref'd. When any recorded atom changes,
   the component's cache is invalidated automatically, so setters become
   plain (reset! atom val) / (swap! atom f) with no manual
   (protocols/invalidate comp) call. While all tracked values are unchanged,
   the cached result is returned without re-running BODY."
  [component width & body]
  `(track-render ~component ~width (fn [] ~@(rewrite-derefs body))))

(defn invalidate-cache
  "Invalidate a component's cache. Call from your invalidate method.
   Equivalent to (reset! (:cache-atom component) nil). Also requests a frame
   through the scheduler hook (tui.md §6) — the gate that closes the
   reactive loop: every invalidation funnels through here, and an atom
   change that invalidates a subscriber schedules the next render. The hook
   is a no-op by default (headless tests, library use); kmet.tui.core
   installs tui-request-render on start and clears it on stop."
  [component]
  (when-let [cache (component-cache-atom component)]
    (reset! cache nil))
  (schedule-frame!))

(defn remove-track-watches!
  "Remove every watch track-render installed on COMPONENT's behalf (its
   last pass's tracked refs — plain atoms and reactive refs alike). Called
   from the dispose defcomponent generates — without it the watches outlive
   the component and keep firing invalidate-cache on each source write.
   Idempotent; a no-op for components that never ran a track! body."
  [component]
  (let [k (tracker-key component)]
    (when-some [{:keys [atoms]} (get @watch-registry k)]
      (doseq [a atoms]
        (reakt/unwatch-ref a k))
      (swap! watch-registry dissoc k)))
  nil)

;; ═══════════════════════════════════════════════════════════════════════════
;; ═════════════════════════════════════════════════════════════════════
;; with-let store — generation-keyed locals + LIFO cleanups (Stage-1
;; primitive; the with-let macro lands here over this in Stage 3)
;; ═════════════════════════════════════════════════════════════════════

(def ^:dynamic *store*
  "The per-instance with-let store bound around a component body. Nil
   outside one (fetch-local/register-cleanup! throw)."
  nil)

(defn new-store
  "A fresh with-let store: holds once-initialized locals and LIFO cleanups.
   :pass-sites tracks which expansion sites ran in the CURRENT render pass,
   for the double-use guard (see fetch-local)."
  []
  (atom {:locals {} :cleanups () :cleanup-sites #{} :pass-sites #{}}))

(defn begin-pass!
  "Mark the start of a render pass over STORE: expansion sites consumed in
   the previous pass become usable again. Called by ComponentFn before the
   body runs; hand-written with-store users call it at the top of each
   simulated pass."
  [store]
  (swap! store assoc :pass-sites #{})
  nil)

(defn fetch-local
  "Value for SITE in the bound store, initialized by INIT-FN exactly once
   per store (per component instance — the body may re-run every pass, the
   init does not). A THROWING init stores nothing, so the next pass retries
   it and the body stays out until every binding is set (reagent issue 525).
   Using the SAME expansion site twice in one render pass throws — two
   instances silently sharing one state slot is always a bug; give each
   instance its own element instead."
  [site init-fn]
  (when (nil? *store*)
    (throw (ex-info "kmet.tui.macros/fetch-local called outside a with-let store" {:site site})))
  (when (contains? (:pass-sites @*store*) site)
    (throw (ex-info
            (str "kmet.tui.macros: the same with-let is being used more "
                 "than once in the same render pass (site " site ") — each "
                 "instance needs its own element/component")
            {:site site})))
  (if (contains? (:locals @*store*) site)
    (do (swap! *store* update :pass-sites conj site)
        (get (:locals @*store*) site))
    (let [v (init-fn)]
      (swap! *store* (fn [s]
                       (-> s
                           (assoc-in [:locals site] v)
                           (update :pass-sites conj site))))
      v)))

(defn register-cleanup!
  "Register F to run when the bound store is destroyed. Registration is
   once-per-SITE: the body re-runs every render pass, and a bare conj would
   grow the cleanup list per frame. Cleanups run LIFO at destroy-store!."
  [site f]
  (when (nil? *store*)
    (throw (ex-info "kmet.tui.macros/register-cleanup! called outside a with-let store" {:site site})))
  (when-not (contains? (:cleanup-sites @*store*) site)
    (swap! *store* (fn [s]
                     (-> s
                         (update :cleanups conj f)
                         (update :cleanup-sites conj site)))))
  nil)

(defn destroy-store!
  "Run STORE's cleanups in reverse registration order (each isolated — a
   throwing cleanup logs to stderr and lets the rest run), then empty it."
  [store]
  (doseq [f (:cleanups @store)]
    (try
      (f)
      (catch Throwable e
        (binding [*out* *err*]
          (println "kmet.tui.macros with-let cleanup error:" (ex-message e))))))
  (swap! store assoc :cleanups () :cleanup-sites #{} :locals {})
  nil)

(defmacro with-store
  "Bind STORE as the ambient with-let store for BODY (dynamic *store*)."
  [store & body]
  `(binding [*store* ~store]
     ~@body))

(defmacro with-let
  "Form-2 local state for fn components (Reagent's with-let, tui.md §5.2).

     (with-let [x (init) y (other-init)]
       body...
       (finally (cleanup x)))

   BINDINGS initialize exactly once per component instance; BODY re-runs
   on every render pass with the same bound values. A top-level
   (finally ...) form is stolen as the cleanup — it runs once when the
   component is disposed (LIFO across nested with-lets), never per pass.

   Only valid inside a component body (the wrapper binds the store;
   elsewhere fetch-local throws loudly). State keys are per-expansion-site
   gensyms, so sibling with-lets binding the same names cannot collide.
   Same footgun as Reagent's: a TOP-LEVEL (try ... (finally ...)) in the
   body will be captured by the finally extractor — nest the try inside
   a let when you need both."
  [bindings & body]
  (when-not (vector? bindings)
    (throw (ex-info "with-let requires a binding vector" {:bindings bindings})))
  (when (odd? (count bindings))
    (throw (ex-info "with-let bindings must be name/init pairs" {:bindings bindings})))
  (let [cleanup (first (filter #(and (seq? %) (= 'finally (first %))) body))
        body-forms (remove #(identical? cleanup %) body)
        ;; One gensym per expansion site: stable across render passes (the
        ;; symbol is baked into the compiled fn), unique per with-let —
        ;; sibling bindings of the same name get separate state slots.
        pairs (partition 2 bindings)
        binding-sites (map (fn [[s _]] (gensym (str "with-let-" s "-")))
                           pairs)
        cleanup-site (gensym "with-let-cleanup-")]
    `(let [~@(mapcat (fn [[s init] site]
                       [s `(kmet.tui.macros/fetch-local '~site (fn [] ~init))])
                     pairs binding-sites)]
       ~@(when cleanup
           [`(kmet.tui.macros/register-cleanup! '~cleanup-site
                                                (fn [] ~@(rest cleanup)))])
       ~@body-forms)))

;; ═════════════════════════════════════════════════════════════════
;; Frame scheduler hook — dependency changes schedule the render (tui.md §6)
;; ═════════════════════════════════════════════════════════════════

;; kmet.tui.core installs #(tui-request-render tui) on start and clears it
;; on stop; default nil keeps headless tests and library use pure. macros
;; never requires core — core pushes the callback down.
(defonce ^:private frame-hook (atom nil))

(defn set-frame-hook!
  "Install F as the frame scheduler (called when a reactive dep changes);
   nil restores the no-op default. Called from tui start/stop."
  [f]
  (reset! frame-hook f)
  nil)

(defn schedule-frame!
  "Request a render because a reactive dependency changed. Runs inside a
  watch on the MUTATING thread — must never throw: a failure is logged to
  stderr and swallowed, mirroring the render loop's crash policy.
  Coalescing is the caller's job (tui-request-render sets an idempotent
  flag the loop polls)."
  []
  (when-some [f @frame-hook]
    (try
      (f)
      (catch Throwable e
        (binding [*out* *err*]
          (println "kmet.tui.macros schedule-frame! error:" (.getMessage e))))))
  nil)

;; ═════════════════════════════════════════════════════════════════
;; defcomponent — record + IComponent + IComponentKind boilerplate
;; ═══════════════════════════════════════════════════════════════════════════

(defn track-deps
  "Declare tracked dependencies inside a track! body: deref atoms whose
   changes must invalidate the render cache even though their values don't
   otherwise appear in the body (wrapper components whose output comes from
   cached children, e.g. a message component re-rendering its Box). Call
   with @atom forms — the track! deref rewrite records them like any other
   read. Returns nil."
  [& _]
  nil)

(defn- track!-form?
  "True when FORM is a (track! ...) call (referred or fully qualified)."
  [form]
  (and (seq? form)
       (or (= 'track! (first form))
           (= 'kmet.tui.macros/track! (first form)))))

(defn- render-uses-track?
  "True when the render method calls track! lexically (the body must contain
   a (track! ...) form for the deref rewrite to apply)."
  [render-method]
  (boolean (some track!-form?
                 (tree-seq coll? seq render-method))))

(defn- cache-clearing-invalidate
  "An invalidate method that clears the component's track! cache (the
   reactive watches make it redundant, but explicit invalidation must stay
   correct)."
  []
  '(invalidate [this] (kmet.tui.macros/invalidate-cache this)))

(defn- prepend-cache-clear
  "Prepend the track! cache clear to a custom invalidate method body, using
   the method's own component binding (authors may name it _this when the
   body doesn't use it)."
  [invalidate-form]
  (let [[_ args & body] invalidate-form
        this-arg (first args)]
    (list* 'invalidate args
           (list 'kmet.tui.macros/invalidate-cache this-arg)
           body)))

(defn- body-method
  "First body form whose head symbol is SYM, or nil."
  [body sym]
  (first (filter #(and (seq? %) (= sym (first %))) body)))

(defn- prepend-dispose-cleanup
  "Prepend track-watch removal to a custom dispose method body, using the
   method's own component binding."
  [dispose-form]
  (let [[_ args & body] dispose-form
        this-arg (first args)]
    (list* 'dispose args
           (list 'kmet.tui.macros/remove-track-watches! this-arg)
           body)))

(defmacro defcomponent
  "Define a TUI component: a defrecord implementing protocols/IComponent.

     (defcomponent Name kind [field...]
       (render [this width] ...)          ; required
       (handle-input [this data] ...)     ; optional — defaults to no-op
       (invalidate [this] ...))           ; optional — cache clear prepended
       (dispose [this] ...))              ; optional — watch teardown prepended

   When the render body calls track!, an invalidate method is generated
   (or the cache clear prepended to a custom one) so explicit invalidation
   always clears the render cache — components only write an invalidate
   method for additional side effects (delegating to children, firing
   request-render).

   KIND is stamped as the record's FIRST FIELD (kind-as-data, tui.md §8):
   a keyword like :user / :assistant / :tool / :custom for message
   components, or nil for plain tui components (Text, Spacer, footer...).
   Dispatch reads (:kind component) — there is no IComponentKind
   protocol. Construct via map-> (kind defaults to nil when absent from
   the map). Components that need additional protocols (IFocusable) keep
   a separate extend-type form after the call. The expansion requires
   kmet.tui.protocols first, so component namespaces load standalone
   (their own ns need not require it — clj-kondo would flag that as
   unused); the symbol is inserted literally (`~'`) so a template bare
   `kmet.tui.protocols` cannot be resolved by syntax-quote at
   macro-definition time into `kmet.tui.macros/kmet.tui.protocols` when
   this namespace is compiled before protocols is loaded (jolt's all-ns
   exposes vendored namespaces before they are loaded)."
  [name _kind fields & body]
  (let [render (body-method body 'render)]
    (when-not render
      (throw (ex-info "defcomponent requires a render method" {:name name})))
    (let [track? (render-uses-track? render)
          handle-input (or (body-method body 'handle-input)
                           '(handle-input [_this _data] nil))
          ;; dispose: track-watch teardown prepended (custom bodies) or
          ;; synthesized around it — track! watches must never outlive the
          ;; component; containers override dispose to delegate to children,
          ;; and each child strips its own watches.
          custom-dispose (body-method body 'dispose)
          dispose (if custom-dispose
                    (prepend-dispose-cleanup custom-dispose)
                    '(dispose [_this]
                              (kmet.tui.macros/remove-track-watches! _this)))
          custom-invalidate (body-method body 'invalidate)
          invalidate (cond
                       custom-invalidate (if track?
                                           (prepend-cache-clear custom-invalidate)
                                           custom-invalidate)
                       track? (cache-clearing-invalidate)
                       :else '(invalidate [this] nil))]
      `(do
         (clojure.core/require '~'kmet.tui.protocols)
         (defrecord ~name ~(vec (cons 'kind fields))
           kmet.tui.protocols/IComponent
           ~render
           ~handle-input
           ~invalidate
           ~dispose)))))
