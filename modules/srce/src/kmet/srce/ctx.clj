(ns kmet.srce.ctx
  "The ctx value, the tree, the service store, and the effect list.

   A ctx is a plain map. The root-owned atoms live in one shared :kernel map
   that every child inherits, so no ctx carries a pointer back to the root. A
   fiber is an atom holding a plain map; `@fiber` is the record.

   The effect primitive lives here rather than in fiber.clj: a plugin mount
   registers listeners (event -> effect) and a listener must be reversible, so
   `effect` has to sit below both or the three namespaces form a require
   cycle. Nothing in this namespace requires a sibling."
  (:refer-clojure :exclude [get extend]))

(defn ctx?
  "Is X a ctx? A marker key, replacing Cordis's Context.is brand."
  [x]
  (boolean (and (map? x) (:srce/ctx x))))

(defn- kernel
  "The shared root state: one map of atoms per root, inherited by every child."
  []
  {:runtimes (atom [])
   :mounted (atom [])
   :counter (atom 0)
   :pass (atom nil)
   :changes (atom [])})

(defn- base
  "A child ctx: the parent's realm, fiber and kernel, plus META. The parent is
   not mutated; the callers attach the child to :children."
  [parent meta]
  (merge {:srce/ctx true
          :parent parent
          :children (atom [])
          :name nil
          ;; Only the names this ctx *declares*: a realm boundary is the ctx
          ;; itself, so an inherited copy would stop the walk one level early.
          :isolate #{}
          :store (atom {})
          :listeners (atom {})
          :kernel (if parent (:kernel parent) (kernel))
          :fiber (when parent (:fiber parent))}
         meta))

(defn- attach
  "Record CTX as PARENT's child (creation order) and return it."
  [parent ctx]
  (swap! (:children parent) conj ctx)
  ctx)

(defn extend
  "A child ctx with META merged in. The parent is not mutated (beyond the child
   entry); a key META carries shadows the inherited value."
  [ctx meta]
  (attach ctx (base ctx meta)))

(defn isolate
  "A child ctx that is a realm for NAMES: lookups and providers for a name stop
   here (its own store included). Repeated calls nest deeper boundaries."
  [ctx & names]
  (attach ctx (base ctx {:isolate (set names)})))

(defn mount-ctx
  "The child ctx a plugin mount creates: NAME is the mount's name and FIBER its
   atom. The kernel never derives a name itself."
  [parent name fiber]
  (attach parent (assoc (base parent {:name name}) :fiber fiber)))

(defn root
  "A root ctx. It is a value, not a process singleton: everything root-owned
   lives in its :kernel map, and two roots never see each other. META is merged
   as-is (:base-url, :resolver, …); the kernel reads the resolver only when a
   plugin source is a keyword."
  ([] (root {}))
  ([meta]
   (let [fiber (atom nil)
         ctx (assoc (base nil meta) :fiber fiber)]
     ;; The fiber holds the completed ctx; the placeholder atom breaks the
     ;; cycle, so the root ctx is an ordinary value.
     (reset! fiber {:uid 0
                    :source ::root
                    :plugin nil
                    :config nil
                    :inject {}
                    :provides #{}
                    :epoch {}
                    :waiting-for #{}
                    :name "root"
                    :state :active
                    :error nil
                    :effects []
                    :parent nil
                    :runtime nil
                    :ctx ctx})
     ctx)))

(defn children
  "This ctx's child ctxs, creation order."
  [ctx]
  @(:children ctx))

(defn mount-fiber
  "The fiber whose mount created CTX, or nil when CTX is a plain child
   (`extend`/`isolate`, which inherits its parent's) or a root. A mount installs
   its own atom and the fiber holds the ctx back, so the identity check
   separates the two without a marker key."
  [ctx]
  (let [fiber (:fiber ctx)]
    (when (and fiber (:parent ctx) (identical? (:ctx @fiber) ctx))
      fiber)))

(defn detach!
  "Remove CTX from its parent's children — the lifetime a plain child ctx
   (`extend`, `isolate`) has and a fiber mount already has. Refuses
   (`:srce/ctx-busy`) while child ctxs remain, mounts included: a fiber's
   disposal removes its own mount ctx, so the caller disposes what hangs under
   CTX first.

   The ctx keeps its `:parent` chain, so a value still holding it resolves
   services exactly as before; what changes is the tree, and the tree is what
   the unit listing renders. Idempotent; a root has no parent and needs none.
   Returns nil."
  [ctx]
  (when-let [parent (:parent ctx)]
    (let [n (count (children ctx))]
      (when (pos? n)
        (throw (ex-info (str "cannot detach a ctx with " n " child ctx(s)")
                        {:type :srce/ctx-busy :children n :name (:name ctx)}))))
    (swap! (:children parent) (fn [v] (filterv #(not (identical? % ctx)) v))))
  nil)

(defn inside?
  "Is CTX in ROOT's subtree? Inclusive."
  [ctx root]
  (loop [c ctx]
    (cond
      (nil? c) false
      (identical? c root) true
      :else (recur (:parent c)))))

(defn attr
  "The nearest non-nil value of K on the ctx chain, with no realm stop — the
   walk for keys the app defines and the kernel carries (:base-url, :resolver)."
  [ctx k]
  (loop [c ctx]
    (when c
      (if-some [v (clojure.core/get c k)]
        v
        (recur (:parent c))))))

(defn provider
  "The provider record for NAME visible from CTX, or nil. Walks up the tree and
   stops at a realm boundary. Includes a provider whose fiber is not active."
  [ctx name]
  (loop [c ctx]
    (when c
      (or (clojure.core/get @(:store c) name)
          (when-not (contains? (:isolate c) name)
            (recur (:parent c)))))))

(defn available?
  "Is this provider record usable now? Its fiber must be :active and its :check
   must pass; a throwing check reads as unavailable."
  [provider]
  (boolean
   (when provider
     (and (= :active (:state @(:fiber provider)))
          (try
            (if-let [check (:check provider)]
              (boolean (check))
              true)
            (catch Throwable _ false))))))

(defn get
  "The provider value for NAME, or nil when it is absent, outside this realm,
   or not currently available."
  [ctx name]
  (when-let [p (provider ctx name)]
    (when (available? p)
      (:value p))))

(defn services
  "The realm-aware merge of what CTX can see: {name provider-record}. Includes
   records whose fiber is not active (inspection); `get` filters availability."
  [ctx]
  (loop [c ctx acc {} blocked #{}]
    (if-not c
      acc
      (let [acc (reduce-kv (fn [m k v]
                             (if (or (contains? blocked k) (contains? m k))
                               m
                               (assoc m k v)))
                           acc
                           @(:store c))]
        (recur (:parent c) acc (into blocked (:isolate c)))))))

(defn record-change!
  "Record NAMES as changed in the shared kernel, for the next pass. Appending is
   per-operation safe, so a foreign thread may call this."
  [ctx names]
  (let [names (if (coll? names) names [names])]
    (when (seq names)
      (swap! (:changes (:kernel ctx)) into names)))
  nil)

(defn next-uid!
  "The next fiber uid for this kernel."
  [ctx]
  (swap! (:counter (:kernel ctx)) inc))

(defn log!
  "Log through the :logger service; absent means silent. The kernel writes
   nothing to stdout and never lets a logger bug escape."
  [ctx level & args]
  (when-let [logger (get ctx :logger)]
    (try
      (apply logger level args)
      (catch Throwable _ nil)))
  nil)

(def ^:dynamic *dispose-failures*
  "The collector of the unwind in progress: a volatile holding
   `[{:label … :error …}]`, or nil outside `unwind-effects!`. A throwing
   disposer is logged either way; this is what makes a teardown reportable
   instead of only written to the log."
  nil)

(defn- dispose-failed!
  "Record and log a disposer that threw, without stopping the unwind. The entry
   carries the throwable for a log and its message for a report."
  [ctx label error]
  (when *dispose-failures*
    (vswap! *dispose-failures* conj {:label label
                                     :error error
                                     :message (or (ex-message error) (str error))}))
  (log! ctx :error "effect disposer threw" error))

(def ^:dynamic *dispose-undoing*
  "The collector of work a disposer handed back instead of waiting for it: a
   volatile holding `[{:label … :handle p}]` of unrealized `IPending` values, or
   nil outside `unwind-effects!`. Reporting it is the kernel's half; awaiting it
   belongs to the caller that owns the lifetime."
  nil)

(defn in-flight?
  "Is V work that is still running — an unrealized `IPending`, i.e. a promise
   nobody has delivered yet? This is the marker a plugin body returns to say its
   setup is asynchronous, and a disposer returns to say its teardown is.

   `IPending` is the guard, not `IDeref`: an atom is a derefable, but
   `realized?` is not defined for it on every host (babashka throws, Jolt
   answers true), so an unguarded `realized?` would turn a disposer that returns
   an atom into an error. A promise passes the guard on both hosts."
  [v]
  (and (instance? clojure.lang.IPending v)
       (not (realized? v))))

(defn- collect-undoing!
  "Record the promise a disposer returned, when one is in flight and a run is
   collecting."
  [label v]
  (when (and *dispose-undoing* (in-flight? v))
    (vswap! *dispose-undoing* conj {:label label :handle v})))

(defn assert-active!
  "Throw :srce/inactive-effect unless FIBER may register: `:active`, or
   `:starting` — a setup body registers while its own setup is still running.
   This is the closed-store guard: a disposer is never in either state."
  [fiber]
  (let [state (:state @fiber)]
    (when-not (contains? #{:active :starting} state)
      (throw (ex-info "fiber is not active"
                      {:type :srce/inactive-effect :state state}))))
  nil)

(defn effect
  "Register a reversible effect on the current fiber: F runs now and the
   disposer(s) it returns — one, a seq, or nil — unwind LIFO, on the returned
   single-shot disposer or when the fiber unloads. Requires a registering fiber
   (`assert-active!`: `:active`, or `:starting` while its own setup runs);
   the disposer reports its last result (the removal status `on` needs).

   An effect can hand work back instead of finishing (a promise nobody delivered
   — `unwind-effects!`), and then at most one handle per effect travels: the
   last-result rule applies to that too, so an effect registering several
   disposers names the one that runs last.

   A disposer run early (a registration deregistered before its fiber unloads)
   also drops its entry from the fiber's `:effects`, so `effects` lists what is
   installed rather than what was ever installed."
  [ctx f & [label]]
  (let [fiber (:fiber ctx)]
    (assert-active! fiber)
    (let [undos (atom [])
          live? (atom true)]
      (letfn [(undo []
                (when (compare-and-set! live? true false)
                  ;; the entry's :undo is this fn, so it finds its own entry
                  (swap! fiber update :effects
                         (fn [effects]
                           (into [] (remove #(identical? undo (:undo %))) effects)))
                  (reduce (fn [_ d]
                            (try
                              (d)
                              (catch Throwable t
                                (dispose-failed! ctx (or label :effect) t))))
                          nil
                          (rseq @undos))))]
        ;; The entry is pushed before F runs, so a throwing body still leaves
        ;; cleanup behind for the failure path to run.
        (swap! fiber update :effects conj {:label (or label :effect) :undo undo})
        (let [result (f)]
          (swap! undos into (cond
                              (nil? result) []
                              (sequential? result) result
                              :else [result])))
        undo))))

(defn- drain-effects!
  "Run FIBER's effects LIFO and empty the list; the state is already non-:active.
   A disposer's return value is its report: an unrealized `IPending` is work
   still running, which the run collects for its caller instead of waiting."
  [fiber]
  (doseq [{:keys [label undo]} (rseq (:effects @fiber))]
    (collect-undoing! label (undo)))
  (swap! fiber assoc :effects []))

(defn rollback-effects!
  "Unwind the effects FIBER gained after MARK (LIFO) and drop them: the undo of
   a body that threw part-way. A throwing disposer is logged and the rest still
   run. Answers nil."
  [fiber mark]
  (let [effects (:effects @fiber)
        mark (min mark (count effects))]
    (swap! fiber assoc :effects (vec (subvec effects 0 mark)))
    (doseq [{:keys [label undo]} (rseq (subvec effects mark))]
      (collect-undoing! label (undo)))
    nil))

(defn unwind-effects!
  "Run every effect LIFO and empty the list. The caller must already have set a
   non-:active state, so a disposer cannot register. Answers

     {:failed  [{:label … :error … :message …}]  ; what threw, unwind order
      :undoing [{:label … :handle p}]}           ; work still running

   — each failure was logged as it ran and the remaining disposers still ran,
   and an `:undoing` handle is an unrealized `IPending` a disposer returned
   instead of blocking. The kernel never derefs one.

   The outermost unwind collects: a `dispose!` performed *by* a disposer
   contributes its failures and its in-flight work to that caller's report, and
   the nested call answers empty ones (they are already in the outer run's)."
  [fiber]
  (if *dispose-failures*
    (do (drain-effects! fiber) {:failed [] :undoing []})
    (binding [*dispose-failures* (volatile! [])
              *dispose-undoing* (volatile! [])]
      (drain-effects! fiber)
      {:failed @*dispose-failures*
       :undoing @*dispose-undoing*})))
