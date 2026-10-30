(ns kmet.srce.fiber
  "Mounting, the refresh pass, the disposal axes, and plugin runtimes.

   Every mutation that runs plugin code stays on the thread that owns the root;
   the one concurrency primitive is the optimistic pass flag in the shared
   :kernel map. A mutation that only appends (provide!, on, effect) is safe but
   late from a foreign thread; it records and the owner's next `settle!` applies
   it."
  (:require [kmet.srce.ctx :as ctx]
            [kmet.srce.event :as event]))

(def ^:private max-rounds
  "The pass budget. A provider chain that has not settled after eight rounds is
   a churn: warn, clear the change set, stop."
  8)

;; ── inject ────────────────────────────────────────────────────────────────

(defn normalize-inject
  "Normalize a plugin's :inject declaration to `{name nil|:optional}`. A vector
   is all-required; a map's values are nil (required) or :optional. Any other
   value throws :srce/bad-inject: the object form's values are reserved for the
   intercept config a future landing may bring back."
  [deps]
  (cond
    (nil? deps) {}
    (sequential? deps) (into {} (map (fn [n] [n nil])) deps)
    (map? deps) (reduce-kv (fn [m k v]
                             (cond
                               (nil? v) (assoc m k nil)
                               (= :optional v) (assoc m k :optional)
                               :else
                               (throw (ex-info (str "reserved :inject value: " (pr-str [k v]))
                                               {:type :srce/bad-inject
                                                :name k
                                                :value v
                                                :problems [k]}))))
                           {} deps)
    :else (throw (ex-info (str "invalid :inject: " (pr-str deps))
                          {:type :srce/bad-inject :value deps :problems [deps]}))))

;; ── plugins ───────────────────────────────────────────────────────────────

(defn plugin-source
  "Resolve a plugin source: a var passes through; a keyword is looked up by
   RESOLVER. A miss or an unsupported source throws :srce/bad-source. The kernel
   never decides what a keyword means."
  [source resolver]
  (cond
    (var? source) source
    (keyword? source) (if-let [value (and resolver (resolver source))]
                        value
                        (throw (ex-info (str "unresolved plugin source: " (pr-str source))
                                        {:type :srce/bad-source :source source})))
    :else (throw (ex-info (str "invalid plugin source: " (pr-str source))
                          {:type :srce/bad-source :source source}))))

(defn- event-problems
  "The shape problems of PLUGIN's `:events` declaration, or nil."
  [plugin]
  (let [events (:events plugin)]
    (cond
      (nil? events) nil
      (not (map? events)) [:events]
      :else (some (fn [[event spec]]
                    (when-not (and (keyword? event)
                                   (map? spec)
                                   (keyword? (:mode spec))
                                   (string? (:doc spec)))
                      [:events event]))
                  events))))

(defn plugin-problems
  "The shape problems of PLUGIN as a vector (empty when valid). The bad :inject
   and :events problems are included, so `plugin!` reports one map-level error."
  [plugin]
  (if-not (map? plugin)
    [:map]
    (let [inject-problems (try
                            (normalize-inject (:inject plugin))
                            nil
                            (catch Throwable t
                              (if (= :srce/bad-inject (:type (ex-data t)))
                                (vec (:problems (ex-data t)))
                                (throw t))))
          shape (cond-> []
                  (not (keyword? (:name plugin))) (conj :name)
                  (not (fn? (:apply plugin))) (conj :apply)
                  (not (or (nil? (:inject plugin))
                           (map? (:inject plugin))
                           (sequential? (:inject plugin)))) (conj :inject)
                  (not (or (nil? (:config plugin)) (fn? (:config plugin)))) (conj :config)
                  (not (or (nil? (:update plugin)) (fn? (:update plugin)))) (conj :update)
                  (not (or (nil? (:provide plugin))
                           (set? (:provide plugin))
                           (sequential? (:provide plugin)))) (conj :provide))]
      (into (into shape inject-problems)
            (or (event-problems plugin) [])))))

(defn validate-plugin!
  "Throw :srce/bad-plugin with :problems unless PLUGIN is a valid plugin map.
   Returns PLUGIN."
  [plugin]
  (let [problems (plugin-problems plugin)]
    (when (seq problems)
      (throw (ex-info (str "invalid plugin: " (pr-str plugin) " " (pr-str problems))
                      {:type :srce/bad-plugin :problems problems})))
    plugin))

(defn- resolve-plugin
  "The validated plugin map for SOURCE: a map as-is, a var dereferenced, a
   keyword through the :resolver."
  [ctx source]
  (let [resolved (if (map? source)
                   source
                   (let [value (plugin-source source (ctx/attr ctx :resolver))]
                     (if (var? value) @value value)))]
    (validate-plugin! resolved)))

;; ── runtimes ──────────────────────────────────────────────────────────────

(defn runtimes
  "Every plugin runtime, mount order."
  [ctx]
  @(:runtimes (:kernel ctx)))

(defn runtime
  "The runtime for SOURCE, or nil."
  [ctx source]
  (some (fn [rt] (when (= source (:source rt)) rt)) (runtimes ctx)))

(defn runtime?
  "Is a runtime mounted for this source?"
  [ctx source]
  (some? (runtime ctx source)))

(defn- runtime-for!
  [ctx source plugin]
  (or (runtime ctx source)
      (let [rt {:name (:name plugin)
                :source source
                :config (:config plugin)
                :fibers (atom [])}]
        (swap! (:runtimes (:kernel ctx)) conj rt)
        rt)))

(defn- drop-runtime!
  "Drop FIBER from its runtime, and the runtime itself once its last fiber is
   gone. A later plugin! recreates it."
  [ctx fiber]
  (when-let [rt (:runtime @fiber)]
    (swap! (:fibers rt) (fn [v] (filterv #(not (identical? % fiber)) v)))
    (when (empty? @(:fibers rt))
      (swap! (:runtimes (:kernel ctx))
             (fn [v] (filterv #(not (identical? % rt)) v)))))
  nil)

;; ── the pass ──────────────────────────────────────────────────────────────

(defn- providers
  "The provider set (the epoch): {injected-name provider-uid | :srce/absent}. An
   optional dependency participates like a required one — absent contributes
   :srce/absent — which is what makes it part of the epoch and not a no-op."
  [ctx inject]
  (reduce-kv (fn [m name _]
               (assoc m name
                      (if-let [p (ctx/provider ctx name)]
                        (if (ctx/available? p)
                          (:uid @(:fiber p))
                          :srce/absent)
                        :srce/absent)))
             {} inject))

(defn- set-state!
  "Set FIBER's state and emit :srce/status with the previous one. Transitions
   run on the owner thread, so the read-then-swap needs no CAS."
  [fiber to]
  (let [from (:state @fiber)]
    (swap! fiber assoc :state to)
    (when (not= from to)
      (event/emit! (:ctx @fiber) :srce/status {:fiber fiber :from from :to to})))
  nil)

(defn- check-provides!
  "Compare the declared :provide with what the fiber actually provided. A
   mismatch is a warning, not a failure: a plugin may provide conditionally."
  [fiber]
  (let [{:keys [ctx plugin provides]} @fiber
        declared (set (:provide plugin))
        actual (set provides)]
    (when-not (= declared actual)
      (ctx/log! ctx :warn "plugin :provide does not match what it provided"
                {:plugin (:name @fiber)
                 :declared declared
                 :provided actual
                 :declared-not-provided (into #{} (remove actual) declared)
                 :provided-not-declared (into #{} (remove declared) actual)}))))

(defn- fail!
  "A throwing apply/config: mark :failed first (so its own disposers cannot
   register), unwind the partial effects — their provide! disposers free the
   names — then report. Work a disposer hands back here is dropped: a failed
   mount has no lifetime owner to give the handle to."
  [fiber error]
  (let [{:keys [ctx name]} @fiber]
    (swap! fiber assoc :error error)
    (set-state! fiber :failed)
    (ctx/unwind-effects! fiber)
    (event/emit! ctx :srce/failure {:fiber fiber :error error})
    (ctx/log! ctx :error "plugin failed" name error)
    nil))

(defn- activate!
  "Satisfied? run apply with the resolved config; otherwise stay :pending with
   the required names it waits for (an optional :inject never holds it). The
   state is set before the plugin body runs, so the body may provide and
   register. apply's return value is its first effect."
  [fiber]
  (let [{:keys [ctx inject plugin config]} @fiber
        epoch (providers ctx inject)
        missing (keep (fn [[name optional]]
                        (when (and (nil? optional)
                                   (= :srce/absent (get epoch name)))
                          name))
                      inject)]
    (if (seq missing)
      (do
        (swap! fiber assoc :epoch epoch :waiting-for (set missing))
        (set-state! fiber :pending))
      (do
        (swap! fiber assoc :epoch epoch :waiting-for #{})
        (try
          (let [cfg (if-let [validate (:config plugin)]
                      (validate config)
                      config)]
            (swap! fiber assoc :error nil :resolved cfg)
            (set-state! fiber :active)
            (let [result ((:apply plugin) ctx cfg)]
              (when (some? result)
                (ctx/effect ctx (constantly result) (str "apply " (:name @fiber)))))
            (check-provides! fiber))
          (catch Throwable t
            (fail! fiber t)))))
    fiber))

(defn- unload!
  "Unwind every effect LIFO; the state must already be non-:active. The provide!
   disposers delete their store entries and record their names as changes.
   Answers `unwind-effects!`'s report: what threw, and what is still running."
  [fiber]
  (let [{:keys [ctx provides]} @fiber
        unwound (ctx/unwind-effects! fiber)]
    (when (seq provides)
      (ctx/record-change! ctx provides))
    unwound))

(defn- reload!
  "The pass's remount: same fiber, same uid, config re-validated."
  [fiber epoch]
  (swap! fiber assoc :epoch epoch)
  (set-state! fiber :pending)
  (unload! fiber)
  (activate! fiber))

(defn- refresh!
  "One fiber, one decision: an unchanged provider set is a no-op (so a :failed
   fiber is never retried by the pass); a changed one reloads."
  [fiber]
  (let [{:keys [ctx inject state epoch]} @fiber]
    (when-not (= :disposed state)
      (let [next-epoch (providers ctx inject)]
        (when (not= next-epoch epoch)
          (reload! fiber next-epoch)))))
  nil)

(defn- take-changes!
  "Atomically drain the change set. A concurrent append of a name we just took
   is harmless (it is being processed now); a different name survives."
  [changes]
  (loop []
    (let [v @changes]
      (if (compare-and-set! changes v [])
        (set v)
        (recur)))))

(defn- run-pass!
  "Recompute the provider set of every fiber the change set can affect, remount
   what changed, and repeat to a fixpoint. Round 0 also re-checks pending
   fibers, which is how a :check that turns true is noticed. Answers the number
   of rounds it ran; hitting the budget with changes still pending logs
   :srce/churn and clears them."
  [ctx]
  (let [{:keys [mounted changes]} (:kernel ctx)]
    (loop [round 0]
      (let [names (take-changes! changes)
            candidates (filterv (fn [fiber]
                                  (let [{:keys [inject state]} @fiber]
                                    (and (not= :disposed state)
                                         (or (and (zero? round) (= :pending state))
                                             (some #(contains? inject %) names)))))
                                @mounted)]
        (doseq [fiber candidates]
          (refresh! fiber))
        (let [ran (inc round)]
          (cond
            (and (seq @changes) (< ran max-rounds))
            (recur ran)

            (seq @changes)
            (do
              (ctx/log! ctx :warn ":srce/churn" {:rounds ran
                                                 :pending (count @changes)})
              (reset! changes [])
              ran)

            :else
            ran))))))

(declare settle!)

(defn- run-owned!
  "Run F while holding the pass flag, so plugin code that mutates only records,
   then drain once at the end. Without the flag F runs as-is: its change is
   late, never corrupting."
  [ctx f]
  (let [pass (:pass (:kernel ctx))]
    (if (compare-and-set! pass nil :running)
      (try
        (f)
        (finally
          (reset! pass nil)
          (settle! ctx)))
      (f))))

(defn settle!
  "Run any pending provider change to a fixpoint. Idempotent; the app calls it
   at the turn boundary and after every cross-thread submission. When it returns
   and no other thread owns the pass, :changes is empty.

   Answers `{:rounds n}` for the fixpoint rounds *this* call ran; a call that
   loses the pass to another thread runs none and answers `{:rounds 0}` — its
   change is that owner's, not lost. A mutator's own drain is a settle of its
   own, so the rounds it needs are not in a later explicit call's count."
  [ctx]
  (let [{:keys [pass changes]} (:kernel ctx)]
    (loop [rounds 0]
      (let [ran (when (compare-and-set! pass nil :running)
                  (try
                    (run-pass! ctx)
                    (finally
                      (reset! pass nil))))
            rounds (+ rounds (or ran 0))]
        (if (and (seq @changes) (nil? @pass))
          (recur rounds)
          {:rounds rounds})))))

(defn- poke!
  "Apply a recorded change now unless a pass is already running; a nested or
   foreign change belongs to the running pass's owner."
  [ctx]
  (when (nil? @(:pass (:kernel ctx)))
    (settle! ctx))
  nil)

;; ── services ──────────────────────────────────────────────────────────────

(defn provide!
  "Register VALUE as NAME in CTX's scope: CTX's own store, or the store of
   `(:in opts)` — an ancestor, which is how a plugin mounted at the root
   shares a service with its siblings while the provider's lifetime stays the
   plugin's fiber (the entry is an effect of CTX, and the record names CTX's
   fiber). Throws :srce/duplicate-service when the name is already held on the
   way up from the target scope (an entry held by a non-active fiber still
   occupies its name). Returns a single-shot disposer."
  ([ctx name value]
   (provide! ctx name value nil))
  ([ctx name value opts]
   (let [fiber (:fiber ctx)
         target (or (:in opts) ctx)]
     (ctx/assert-active! fiber)
     (when (ctx/provider target name)
       (throw (ex-info (str "service " name " is already provided in this realm path")
                       {:type :srce/duplicate-service :name name})))
     (ctx/effect ctx
                 (fn []
                   (swap! (:store target) assoc name {:name name
                                                      :fiber fiber
                                                      :value value
                                                      :check (:check opts)})
                   (swap! fiber update :provides conj name)
                   (ctx/record-change! ctx [name])
                   (event/emit! ctx :srce/service {:name name :value value})
                   (poke! ctx)
                   (fn []
                     (swap! (:store target) dissoc name)
                     (swap! fiber update :provides disj name)
                     (ctx/record-change! ctx [name])
                     (event/emit! ctx :srce/service {:name name :value nil})
                     (poke! ctx)
                     true))
                 (str "provide " name)))))

;; ── mount / unmount ───────────────────────────────────────────────────────

(defn plugin!
  "Mount SOURCE (a var, a plugin map, or a keyword for the :resolver) as a new
   fiber under CTX with CONFIG. Returns the fiber atom. The plugin body runs
   only when its inject is satisfied; otherwise the fiber is :pending."
  ([ctx source]
   (plugin! ctx source nil))
  ([ctx source config]
   (let [plugin (resolve-plugin ctx source)]
     (ctx/assert-active! (:fiber ctx))
     (run-owned!
      ctx
      (fn []
        (let [fiber (atom nil)
              fctx (ctx/mount-ctx ctx (:name plugin) fiber)
              rt (runtime-for! ctx source plugin)]
          (reset! fiber {:uid (ctx/next-uid! ctx)
                         :source source
                         :plugin plugin
                         :config config
                         :inject (normalize-inject (:inject plugin))
                         :events (:events plugin)
                         :provides #{}
                         :epoch ::none
                         :waiting-for #{}
                         :name (:name plugin)
                         ;; nil until the :pending transition below emits it
                         :state nil
                         :error nil
                         :effects []
                         :parent (:fiber ctx)
                         :runtime rt
                         :ctx fctx})
          (swap! (:fibers rt) conj fiber)
          (swap! (:mounted (:kernel ctx)) conj fiber)
          (set-state! fiber :pending)
          (activate! fiber)
          fiber))))))

(defn inject!
  "Mount `{:name name :inject deps :apply apply-fn}` under CTX. The name is
   required: a Clojure fn carries no name to derive one from, and the mount's
   ctx path is built from it."
  [ctx name deps apply-fn]
  (plugin! ctx {:name name :inject deps :apply apply-fn} nil))

(defn dispose!
  "Unwind FIBER's effects LIFO, detach it, and drop its runtime when it was the
   last. Idempotent. The state flips to :disposed first, so a disposer cannot
   register and `get` already reads nil.

   Answers `{:disposed n :failed [{:label … :error …}]
   :undoing [{:label … :handle p}]}` — n is 0 for a fiber that was already
   disposed, and a throwing disposer is logged and listed without stopping the
   rest. `:undoing` is a disposer that returned an unrealized promise: work the
   kernel will not wait for, reported so the caller that owns the lifetime can.
   A dispose performed by one of FIBER's disposers reports into this call's run
   (`unwind-effects!`) and answers empty lists itself."
  [fiber]
  (let [{:keys [ctx]} @fiber]
    (run-owned!
     ctx
     (fn []
       (if (= :disposed (:state @fiber))
         {:disposed 0 :failed [] :undoing []}
         (let [fctx (:ctx @fiber)
               _ (set-state! fiber :disposed)
               {:keys [failed undoing]} (unload! fiber)]
           (swap! (:mounted (:kernel ctx))
                  (fn [v] (filterv #(not (identical? % fiber)) v)))
           (when-let [parent (:parent fctx)]
             (swap! (:children parent)
                    (fn [v] (filterv #(not (identical? % fctx)) v))))
           (drop-runtime! ctx fiber)
           {:disposed 1 :failed failed :undoing undoing}))))))

(defn- tally
  "The aggregate of DISPOSALS: `{:disposed n :failed [...] :undoing [...]}`."
  [disposals]
  {:disposed (reduce + (map :disposed disposals))
   :failed (into [] (mapcat :failed) disposals)
   :undoing (into [] (mapcat :undoing) disposals)})

(defn restart!
  "Dispose FIBER and mount the same source and config again; returns the new
   fiber. A restart allocates a new uid, so dependents remount; a plugin that
   can absorb a config change in place declares `:update` and is changed with
   `update!` instead. A :failed fiber is retried this way.

   The dispose's report is dropped — a restart mounts immediately — so a
   disposer that hands back in-flight work (`:undoing`) is not awaited here: a
   caller that restarts one must await it itself."
  [fiber]
  (let [mount-ctx (:ctx @fiber)
        {source :source config :config} @fiber]
    (run-owned!
     (:parent mount-ctx)
     (fn []
       (dispose! fiber)
       (plugin! (:parent mount-ctx) source config)))))

(defn- update-hook
  "FIBER's `:update` hook, or a throw naming why it cannot be updated in place."
  [{:keys [plugin state]}]
  (when-not (= :active state)
    (throw (ex-info "cannot update an inactive fiber"
                    {:type :srce/inactive-update
                     :name (:name plugin)
                     :state state})))
  (or (:update plugin)
      (throw (ex-info (str "plugin " (pr-str (:name plugin))
                           " has no :update; a config change it cannot absorb is restart!")
                      {:type :srce/no-update :name (:name plugin)}))))

(defn update!
  "Change FIBER's config in place. FIBER must be :active (`:srce/inactive-update`)
   and its plugin must declare `:update` (`:srce/no-update` otherwise) — a
   config change a plugin cannot absorb is `restart!`. The new config is
   validated by `:config` exactly as a mount would, the hook runs with the old
   and the new resolved config (the record's `:resolved`), and the raw config is
   stored last. A throw from either leaves the fiber as it was: whatever the
   hook registered before throwing is unwound.

   The fiber and its uid stay: no dependent remounts, and a dependent keeps the
   value it resolved until its own reload. A hook that provides a *new* name is
   a normal provider-set change, so the drain reloads dependents; the name the
   plugin already provides is not re-provided (that would be
   `:srce/duplicate-service`), so a hook updates the value it owns — replacing
   a service is `restart!`.

   The hook runs under `run-owned!`, so its registrations are drained once at
   the end, like any other mutator's."
  [fiber new-config]
  (let [{:keys [ctx plugin] :as record} @fiber
        hook (update-hook record)
        config (if-let [validate (:config plugin)]
                 (validate new-config)
                 new-config)]
    (run-owned!
     ctx
     (fn []
       (let [mark (count (:effects @fiber))]
         (try
           (hook ctx (:resolved record) config)
           (swap! fiber assoc :config new-config :resolved config)
           fiber
           (catch Throwable t
             (ctx/rollback-effects! fiber mark)
             (throw t))))))))

(defn dispose-plugin!
  "Dispose every fiber of SOURCE, in one pass; the last one drops the runtime.
   Answers the aggregate of the disposals (`dispose!`'s shape)."
  [ctx source]
  (run-owned!
   ctx
   (fn []
     (tally (if-let [rt (runtime ctx source)]
              (mapv dispose! (rseq @(:fibers rt)))
              [])))))

(defn dispose-subtree!
  "Dispose every fiber under CTX, children first and in one pass. Mount order is
   topological, so the reverse mount list is already children-first. Answers the
   aggregate of the disposals (`dispose!`'s shape)."
  [ctx]
  (run-owned!
   ctx
   (fn []
     (tally (mapv dispose!
                  (filter #(ctx/inside? (:ctx @%) ctx)
                          (rseq @(:mounted (:kernel ctx)))))))))

(defn effects
  "The effect labels in creation order (the reverse of unwinding)."
  [fiber]
  (mapv :label (:effects @fiber)))

(defn fiber-info
  "FIBER's record as a map: identity, lifecycle, declared and live facts, the
   effect labels and the mount's child ctx. What app code reads — `fibers` is
   the same for a whole root — so a record field the kernel adds stays the
   kernel's. `@fiber` remains the record itself, for tests and kernel code."
  [fiber]
  (let [record @fiber]
    (-> (select-keys record [:uid :name :source :config :state :error :inject
                             :events :provides :waiting-for :ctx])
        (assoc :effects (mapv :label (:effects record))))))

(defn fibers
  "Every mounted fiber of CTX's root as `fiber-info` data, mount order. The
   kernel's mount list is the root's, so a child ctx answers the same list."
  [ctx]
  (mapv fiber-info @(:mounted (:kernel ctx))))

(defn active?
  "Is FIBER `:active`?"
  [fiber]
  (= :active (:state @fiber)))
