(ns kmet.loader.core
  "Portable Loader abstraction: resolve, link, find and unload namespaces,
   vars, classes and resources — one contract for babashka, the JVM and Jolt.

   Vocabulary (loader.md §1):
   - loader      the abstraction — `Loader`, the fns of this namespace;
   - resolve-fn  one implementation of the delegate step (a plain fn or a
                 loader built by a combinator);
   - context     the host object underneath a loader (a sci ctx, Jolt's
                 tables) — `(context l)`, an escape hatch;
   - ClassLoader a real host classloader, where the host has one —
                 `(as-classloader l)`, a host view, never the protocol.

   Resolution (loader.md §2): already-linked → delegate → own locate →
   miss. `find` runs that walk and returns hits (data) without installing
   anything; `resolve` answers from the link table only; `load` adds READ +
   initialize and installs the link. `find` never reads, `load` is the only
   method that does, and `open-hit` owns resource opening. A caller holding
   a hit can pass it to `load` to skip re-resolution.

   Links are memoized, resources are not (JVM semantics): once a link is
   installed, this loader answers from the table; resources are re-walked
   on every call. Initialization runs in a hit's *home* loader — the loader
   that located it — while the link lands in the loader `load` was called
   on, so a definition created in one loader is shared by reference
   (`identical?` var cells) wherever it is loaded (loader.md §0.7).

   `unload!` follows the Java close / OSGi stop precedent: no new loads,
   already-resolved definitions stay live, acquired resources are released,
   teardown errors are reported (never thrown) and the call is idempotent.
   It never blocks: an in-flight load finishes into the closed loader and
   the report records the race (`:raced`), so the next find/resolve/load
   still throws (loader.md §5).

   Diagnostics: `unloaded?` is the one behavioral predicate; `status` is a
   map of implementation-visible keys (loader.md §4.5). The ambient tier —
   `with-loader` / `current-loader` — is the TCCL analogue for requests
   with no lexical home.

   Implementation status: this namespace is the protocol, the generic
   resolution body, the combinators and the host-root backend (loader.md
   Phase 0). The sci backend (Phase 1), the JVM ClassLoader backend
   (Phase 2) and Jolt's native backend (Phase 3) layer on the same
   protocol; `make-loader` is their constructor seam."
  (:refer-clojure :exclude [find resolve load])
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ─── Requests, hits, state ─────────────────────────────────────────────────

(def ^:private hit-payload-keys
  "Keys that make a map a hit rather than a request — a hit must carry at
   least one, a request none."
  [:cell :file :url :handle :class :registration])

(def ^:private request-kinds #{:ns :var :class :resource})

(defn- hit-map?
  [x]
  (and (map? x) (boolean (some #(contains? x %) hit-payload-keys))))

(defn- req-map
  "Validate and normalize X as a request; `:ns` requests get `:load?`
   defaulted to true (require semantics — load the namespace with its
   dependencies; false means load the one namespace alone)."
  [x]
  (when-not (map? x)
    (throw (ex-info "loader request must be a map"
                    {:type :loader/bad-request :request x})))
  (let [kind (:kind x)
        nm (:name x)]
    (when-not (contains? request-kinds kind)
      (throw (ex-info (str "invalid loader request kind: " (pr-str kind))
                      {:type :loader/bad-request :request x})))
    (when-not (string? nm)
      (throw (ex-info "loader request :name must be a string"
                      {:type :loader/bad-request :request x})))
    (cond-> {:kind kind :name nm}
      (= :ns kind) (assoc :load? (:load? x true)))))

(defn- req->k
  "The link-table key of a request or hit: [kind name]."
  [req]
  [(:kind req) (:name req)])

(defn- normalize-hit
  "Fill request-derived keys (:kind, :name, `:ns` :load?) and stamp
   `:loader` provenance on a resolver's HIT. A hit is data and must carry
   its request `:name` to be loadable; resolvers may omit it when the
   locating request supplies it. nil in, nil out."
  [l req hit]
  (when hit
    (let [hit (cond-> hit
                (and req (not (contains? hit :kind))) (assoc :kind (:kind req))
                (and req (not (contains? hit :name))) (assoc :name (:name req))
                (and req (= :ns (or (:kind hit) (:kind req))) (contains? req :load?))
                (assoc :load? (:load? req)))]
      (cond-> (if (symbol? (:name hit)) (update hit :name str) hit)
        (not (contains? hit :loader)) (assoc :loader l)))))

(defn- hits-seq
  "Normalize a resolver result — nil, one hit, or a seq of hits — to a seq
   of hits, or nil when empty."
  [x]
  (cond
    (nil? x) nil
    (map? x) (if (hit-map? x)
               [x]
               (throw (ex-info "resolver returned a map that is not a hit"
                               {:type :loader/bad-hit :value x})))
    (sequential? x) (seq x)
    :else (throw (ex-info "resolver must return a hit, a seq of hits or nil"
                          {:type :loader/bad-hit :value x}))))

(defn- link-of
  "The link entry {[kind name] {:hit … :value …}} of L, or nil."
  [l k]
  (when-let [state (:state l)]
    (get @(:links state) k)))

(defn- linked-entry [l req] (link-of l (req->k req)))

(defn unloaded?
  "True once `unload!` has been called on L — the loader's one behavioral
   state predicate. False for loaders that are not built by this namespace."
  [l]
  (boolean (some-> l :state :unloaded? deref)))

(defn- check-live!
  [l]
  (when (unloaded? l)
    (throw (ex-info (str "loader " (:id l) " is unloaded")
                    {:type :loader/unloaded :loader-id (:id l)}))))

(defn- denied!
  [l req reason]
  (throw (ex-info (str "loader " (:id l) " denied " (:kind req) " " (:name req))
                  (cond-> {:loader/denied true :kind (:kind req) :name (:name req)}
                    (:id l) (assoc :loader-id (:id l))
                    reason (assoc :reason reason)))))

(defn- gate!
  [l req]
  (when-let [f (:gate-fn l)]
    (f req))
  nil)

(defn- miss!
  [l req]
  (ex-info (str "loader " (:id l) " cannot resolve " (:kind req) " " (:name req))
           {:type :loader/miss :kind (:kind req) :name (:name req) :loader-id (:id l)}))

;; ─── In-flight marks ───────────────────────────────────────────────────────
;; Never call a resolver under a lock (it may require back into the same
;; loader). `*loading*` detects a nested load of the same key on this
;; thread/fiber; the per-loader `:in-flight` map makes a concurrent load of
;; the same key wait for the owner's promise (the JVM getClassLoadingLock
;; analogue, promise-based so nothing blocks while holding loader state).

(def ^:dynamic *loading*
  "Keys — [loader [kind name]] — of loads in flight on this thread/fiber.
   Nested loads of the same key raise instead of deadlocking. Inherited by
   bound-fn-conveying threads/futures spawned inside a load."
  #{})

(defn- claim!
  "Claim K for loading in L. Returns :circular when this thread is already
   loading K, :claimed when the caller owns the claim, else {:wait promise}
   for a concurrent load to wait on."
  [l k]
  (if (contains? *loading* [l k])
    :circular
    (let [p (promise)
          st (:in-flight (:state l))]
      (loop []
        (let [m @st]
          (if (contains? m k)
            {:wait (:promise (get m k))}
            (if (compare-and-set! st m (assoc m k {:promise p}))
              :claimed
              (recur))))))))

(defn- finish-claim!
  [l k]
  (let [st (:in-flight (:state l))
        p (:promise (get @st k))]
    (swap! st dissoc k)
    (when p (deliver p :done))))

;; ─── The protocol ──────────────────────────────────────────────────────────

(defprotocol Loader
  (find
    [l req]
    "Locate REQ without reading, compiling, opening or installing anything.
     Returns ordered hits — [] on miss — and throws on :denied. Hits are
     pure data; nothing is held between calls.")
  (resolve
    [l req]
    "The linked definition for REQ from L's link table, or nil. Never
     reads source and never installs.")
  (load
    [l x]
    "Resolve (or accept) a hit, READ + initialize + link it, and return the
     handle. X is a request or a hit from `find`; the only method that
     reads. Misses and denials throw.")
  (parent
    [l]
    "The delegate loader, or nil (bootstrap/root). For composite delegates
     this is the primary member; `status` lists the rest.")
  (unload!
    [l]
    "Close L: no new loads, already-resolved definitions stay live,
     acquired host resources are released. Idempotent and non-blocking;
     teardown errors are reported in the returned report map, never
     thrown."))

;; ─── The generic resolution body ───────────────────────────────────────────

(defn- delegate-hits
  [l req]
  (if-let [f (:delegate-fn l)]
    (hits-seq (f req))
    (when-let [p (:parent l)]
      (let [hs (find p req)]
        (when (seq hs) hs)))))

(defn- locate-hits
  [l req]
  (when-let [f (:locate-fn l)]
    (hits-seq (f req))))

(defn- find*
  [l req]
  (if-let [e (linked-entry l req)]
    [(:hit e)]
    (into []
          (keep #(normalize-hit l req %))
          (concat (delegate-hits l req) (locate-hits l req)))))

(defn- default-open
  [hit]
  (when-let [url (:url hit)]
    (io/input-stream (io/as-url url))))

(defn- open*
  [l hit]
  (let [home (:loader hit)]
    (cond
      (:open-fn home) ((:open-fn home) home hit)
      (:open-fn l) ((:open-fn l) l hit)
      :else (default-open hit))))

(defn open-hit
  "Open a resource HIT into a handle (a stream), or nil when no opener
   applies. Opening runs through the hit's home loader first — dispatch
   follows the value (loader.md §0.7) — then L. Hits stay data; this is the
   loader-owned opening step (`load` on a resource request uses it too)."
  [l hit]
  (check-live! l)
  (when (= :resource (:kind hit))
    (open* l hit)))

(defn- bad-hit!
  [hit msg]
  (throw (ex-info (str "invalid loader hit: " msg)
                  {:type :loader/bad-hit :hit hit})))

(defn- initialize
  "Produce the loaded value for HIT, READ + initialize running in HIT's
   home loader; L is the loader doing the loading."
  [l hit req]
  (case (:kind hit)
    :var (if (contains? hit :cell)
           (:cell hit)
           (bad-hit! hit "a :var hit needs :cell"))
    :class (cond
             (contains? hit :class) (:class hit)
             (contains? hit :registration) (:registration hit)
             :else (bad-hit! hit "a :class hit needs :class or :registration"))
    :ns (if (contains? hit :handle)
          (:handle hit)
          (let [home (or (:loader hit) l)
                f (or (:ns-load-fn home) (:ns-load-fn l))]
            (if f
              (f home hit (or req (select-keys hit [:kind :name :load?])))
              (throw (ex-info (str "no source reader for namespace " (:name hit)
                                   " in loader " (:id (or home l)))
                              {:type :loader/unreadable
                               :kind :ns :name (:name hit)})))))
    (bad-hit! hit (str "unknown :kind " (pr-str (:kind hit))))))

(defn- install-claim!
  "Run HIT's initialization under the claim for K and install the link."
  [l hit req k]
  (try
    (let [v (binding [*loading* (conj *loading* [l k])]
              (initialize l hit req))]
      (swap! (:links (:state l)) assoc k {:hit hit :value v})
      v)
    (finally
      (finish-claim! l k))))

(defn- load-hit
  "Link HIT into L and return its value; resources are opened, not linked.
   Initialization runs in HIT's home loader; closed loaders refuse new
   loads here too. Recursive loads of the same key raise; concurrent ones
   wait for the in-flight owner."
  [l hit req]
  (if (= :resource (:kind hit))
    (or (open* l hit)
        (throw (ex-info (str "loader " (:id l) " cannot open resource " (:name hit))
                        {:type :loader/unreadable :kind :resource :name (:name hit)})))
    (let [k (req->k hit)
          home (:loader hit)]
      (when (and home (not (identical? home l)) (unloaded? home))
        (throw (ex-info (str "home loader " (:id home) " of " (:name hit) " is unloaded")
                        {:type :loader/unloaded :loader-id (:id home)})))
      (loop []
        (if-let [e (link-of l k)]
          (:value e)
          (let [claim (claim! l k)]
            (cond
              (= :circular claim)
              (throw (ex-info (str "circular load of " (:kind hit) " " (:name hit)
                                   " in loader " (:id l))
                              {:type :loader/circular :kind (:kind hit)
                               :name (:name hit) :loader-id (:id l)}))

              (= :claimed claim)
              (install-claim! l hit req k)

              :else
              (do
                (deref (:wait claim))
                (recur)))))))))

;; ─── Loading values, teardown, the impl record ─────────────────────────────

(defn- teardown
  [l base]
  (if-let [f (:release-fn l)]
    (try
      (let [r (f l)]
        [(merge base (when (map? r) r)) []])
      (catch Throwable e
        [base [{:message (ex-message e) :exception e}]]))
    [base []]))

(defn- unload-run
  [l]
  (let [state (:state l)]
    (if (compare-and-set! (:unloaded? state) false true)
      (let [in-flight (count @(:in-flight state))
            links @(:links state)
            base {:namespaces (count (filter #(= :ns (ffirst %)) links))
                  :registrations (count (filter #(= :class (ffirst %)) links))}
            [released errors] (teardown l base)]
        {:unloaded true
         :already false
         :released released
         :in-flight in-flight
         :raced (pos? in-flight)
         :errors errors})
      {:unloaded true
       :already true
       :released {}
       :in-flight (count @(:in-flight state))
       :raced false
       :errors []})))

(defrecord LoaderImpl [id parent delegate-fn locate-fn gate-fn open-fn
                       release-fn ns-load-fn info state]
  Loader
  (find [l req]
    (check-live! l)
    (let [req (req-map req)]
      (gate! l req)
      (find* l req)))
  (resolve [l req]
    (check-live! l)
    (let [req (req-map req)]
      (gate! l req)
      (some-> (linked-entry l req) :value)))
  (load [l x]
    (check-live! l)
    (if (hit-map? x)
      (let [hit (normalize-hit l nil x)]
        (when-not (contains? request-kinds (:kind hit))
          (bad-hit! hit "missing or invalid :kind"))
        (when-not (string? (:name hit))
          (bad-hit! hit "missing :name — hits carry the request name"))
        (gate! l hit)
        (load-hit l hit nil))
      (let [req (req-map x)]
        (gate! l req)
        (if-let [e (linked-entry l req)]
          (:value e)
          (if-let [hit (first (find* l req))]
            (load-hit l hit req)
            (throw (miss! l req)))))))
  (parent [l] (:parent l))
  (unload! [l] (unload-run l)))

;; ─── Constructor seam for backends ─────────────────────────────────────────

(def ^:private id-counter (atom 0))

(defn- next-id [prefix] (str prefix "#" (swap! id-counter inc)))

(defn make-loader
  "Backend seam: build a loader from raw steps. The in-memory backend
   (`kmet.loader.memory`) and the host backends (sci, JVM, Jolt) are
   all built through this; application code uses the constructors and
   combinators below instead.

   Opts (all optional):
   - :id          diagnostics id (default generated)
   - :parent      delegate loader
   - :delegate-fn (fn [req]) → hits — overrides the parent walk
   - :locate-fn   (fn [req]) → hits — this loader's own roots
   - :gate-fn     (fn [req]) — throws :denied (`allow` / `deny` build on it)
   - :open-fn     (fn [loader hit]) → opened resource handle
   - :release-fn  (fn [loader]) → extra released counts for `unload!`
   - :ns-load-fn  (fn [home hit req]) → namespace handle (READ is allowed)
   - :roots       [string] for `status`
   - :delegates   [Loader] for `status`
   - :members     [Loader] for `status`
   - :context     host object for `(context l)`
   - :classloader host view — value or (fn [l]) — for `as-classloader`"
  [{:keys [id parent delegate-fn locate-fn gate-fn open-fn release-fn ns-load-fn
           roots delegates members context classloader]}]
  (->LoaderImpl
   (or id (next-id "loader"))
   parent delegate-fn locate-fn gate-fn open-fn release-fn ns-load-fn
   (cond-> {}
     roots (assoc :roots (vec roots))
     delegates (assoc :delegates (vec delegates))
     members (assoc :members (vec members))
     (some? context) (assoc :context context)
     (some? classloader) (assoc :classloader classloader))
   {:links (atom {}) :in-flight (atom {}) :unloaded? (atom false)}))

;; ─── Constructors and combinators ──────────────────────────────────────────

(defn ->loader
  "Lift a plain resolve-fn — (fn [req]) returning a hit, a seq of hits or
   nil — into a delegate loader. The generic body adds the link table,
   in-flight marks and unload for free, so policies compose."
  [f]
  (when-not (fn? f)
    (throw (ex-info "->loader needs a resolve-fn" {:type :loader/bad-resolve-fn :value f})))
  (make-loader {:delegate-fn f}))

(defn isolated
  "A hermetic loader: nothing delegates, nothing is located. `(constantly
   nil)` as a loader — the root is not visible."
  []
  (make-loader {:id (next-id "isolated")}))

(defn delegating
  "A loader that tries A then B (then any MORE) in order. The first hit
   wins; each member's denials propagate (a denial is never a miss). The
   result's `parent` is the first member and `status` lists them all."
  [a b & more]
  (let [members (into [a b] more)]
    (make-loader
     {:id (next-id "delegating")
      :parent a
      :delegates members
      :delegate-fn (fn [req]
                     (some (fn [m]
                             (let [hs (find m req)]
                               (when (seq hs) hs)))
                           members))})))

(defn pool
  "An ordered union of LS: the members are consulted in order and
   resolution sees the same hits through the pool. Used as a shared
   delegate bundle (loader.md §4.2) — members keep their own link
   tables."
  [ls]
  (when-not (seq ls)
    (throw (ex-info "pool needs at least one loader" {:type :loader/bad-pool})))
  (make-loader
   {:id (next-id "pool")
    :parent (first ls)
    :members (vec ls)
    :delegate-fn (fn [req]
                   (some (fn [m]
                           (let [hs (find m req)]
                             (when (seq hs) hs)))
                         ls))}))

(defn- name-matches?
  "Prefix match over a name: exact, or boundary-prefixed with \".\" (a
   namespace prefix like 'kmet.tui) or \"/\" (a resource directory)."
  [prefixes nm]
  (boolean
   (some (fn [p]
           (let [p (str p)]
             (or (= p nm)
                 (str/starts-with? nm (str p "."))
                 (str/starts-with? nm (str p "/")))))
         prefixes)))

(defn allow
  "Whitelist policy over delegate L: only names matching one of PREFIXES
   (or a boundary prefix of one) may be delegated; anything else raises
   `:denied` — it does not fall through to another tier or this loader's
   own roots (loader.md §0.5)."
  [l prefixes]
  (make-loader
   {:id (next-id "allow")
    :parent l
    :delegates [l]
    :gate-fn (fn [req]
               (when-not (name-matches? prefixes (:name req))
                 (denied! l req "not in allow list")))}))

(defn deny
  "Blacklist policy over delegate L: names matching one of NAMES (or a
   boundary prefix of one) raise `:denied` before anything is consulted;
   everything else behaves exactly like L."
  [l names]
  (make-loader
   {:id (next-id "deny")
    :parent l
    :delegates [l]
    :gate-fn (fn [req]
               (when (name-matches? names (:name req))
                 (denied! l req "denied by policy")))}))

(defn self-first
  "L with its own roots consulted before everything else for names matching
   one of PREFIXES (L's own already-linked resolutions included; the rest
   of L is still consulted on a locate miss); outside the prefixes L
   resolves exactly as it does alone. Hits located through L's roots carry
   L as their :loader, so opening still dispatches to L's opener."
  [l prefixes]
  (let [own-hits (fn [req]
                   (seq (keep #(normalize-hit l req %) (locate-hits l req))))
        full-hits (fn [req]
                    (let [hs (find l req)]
                      (when (seq hs) hs)))]
    (make-loader
     {:id (next-id "self-first")
      :parent l
      :delegates [l]
      :delegate-fn (fn [req]
                     (if (name-matches? prefixes (:name req))
                       (or (own-hits req) (full-hits req))
                       (full-hits req)))})))

;; ─── Host root ─────────────────────────────────────────────────────────────

(defn- host-locate
  "The host's global world as hits: loaded namespaces, resolved vars, JDK
   classes, classpath resources. Locating (reading/evaluating) sources is
   the code backends' job, not the root's."
  [req]
  (case (:kind req)
    :ns (when-let [n (find-ns (symbol (:name req)))]
          [{:kind :ns :handle n}])
    :var (let [[ns-name var-name] (str/split (:name req) #"/" 2)]
           (when (and ns-name var-name)
             (let [ns-sym (symbol ns-name)]
               (when (find-ns ns-sym)
                 (when-let [v (ns-resolve ns-sym (symbol var-name))]
                   [{:kind :var :cell v}])))))
    :class (try
             (when-let [c (Class/forName (:name req))]
               [{:kind :class :class c}])
             (catch Throwable _ nil))
    :resource (when-let [u (io/resource (:name req))]
                [{:kind :resource :url (str u)}])
    nil))

(defonce ^:private root-loader
  (delay (make-loader {:id "root" :locate-fn host-locate})))

(defn root
  "The host's global world as a loader — one shared instance. Loaded
   namespaces, resolved vars, JDK classes and classpath resources resolve;
   loading a namespace that is not loaded yet is a miss today (the code
   backends own READ + initialize)."
  []
  @root-loader)

(defn context
  "The host object underneath L (a sci ctx, Jolt's tables), or nil. The
   escape hatch for backends; nothing in the abstraction depends on it."
  [l]
  (get-in l [:info :context]))

(defn as-classloader
  "A real host ClassLoader view of L: loadClass → find + load;
   getResource(s)/getResourceAsStream → find + open-hit; close → unload!.
   Host backends supply it through `make-loader` (`:classloader`); hosts
   without a real ClassLoader (babashka, Jolt) throw :loader/no-classloader."
  [l]
  (let [v (get-in l [:info :classloader])]
    (cond
      (fn? v) (v l)
      (some? v) v
      :else (throw (ex-info (str "loader " (:id l) " has no host ClassLoader view on this host")
                            {:type :loader/no-classloader :loader-id (:id l)})))))

(defn status
  "Diagnostics map for L — implementation-visible: keys may be added
   freely and no behavior may depend on a key not documented in loader.md
   §4.5. `unloaded?` is the behavioral predicate. Only the delegate one
   level deep is included."
  ([l] (status l 1))
  ([l depth]
   (if-let [state (:state l)]
     (let [links @(:links state)]
       (cond-> {:loader-id (:id l)
                :roots (vec (:roots (:info l) []))
                :parent-loaded? (boolean (and (:parent l)
                                              (not (unloaded? (:parent l)))))
                :loaded-namespaces (count (filter #(= :ns (ffirst %)) links))
                :loaded (count links)
                :in-flight (count @(:in-flight state))
                :unloaded? (boolean @(:unloaded? state))}
         (and (:parent l) (pos? depth)) (assoc :delegate (status (:parent l) (dec depth)))
         (seq (:delegates (:info l))) (assoc :delegates (mapv :id (:delegates (:info l))))
         (seq (:members (:info l))) (assoc :members (mapv :id (:members (:info l))))))
     {:loader-id (or (:id l) "foreign") :unloaded? false})))

;; ─── Ambient loader (TCCL analogue) ────────────────────────────────────────

(def ^:dynamic *current-loader*
  "The ambient loader for requests with no lexical home. nil means the
   host root."
  nil)

(defn current-loader
  "The ambient loader: `*current-loader*` when bound, else the host root."
  []
  (or *current-loader* (root)))

(defmacro with-loader
  "Evaluate BODY with L as the ambient loader. Inherited by host features
   that convey dynamic bindings (futures, bound fns, fibers) — the weaker,
   best-effort tier (loader.md §3)."
  [l & body]
  `(binding [*current-loader* ~l] ~@body))

;; ─── URL search (the URLClassLoader analogue) ──────────────────────────────

(defn- ns-source-paths
  "Strict ns-path candidates for NS-NAME, in probe order."
  [ns-name]
  (let [base (str/replace ns-name "." "/")]
    [(str base ".clj") (str base ".cljc") (str base ".bb")]))

(defn- validate-root!
  "Construction is the eager-validation point: a root that is missing or
   not a readable directory fails here, not at first load. (Jar roots
   arrive with the host byte seam — loader.md §4.4.)"
  [root]
  (let [f (fs/file (str root))]
    (when-not (fs/exists? f)
      (throw (ex-info (str "loader root does not exist: " root)
                      {:type :loader/bad-root :root (str root)})))
    (when-not (fs/directory? f)
      (throw (ex-info (str "loader root is not a directory (jar roots are not supported yet): " root)
                      {:type :loader/bad-root :root (str root)})))
    (when-not (fs/readable? f)
      (throw (ex-info (str "loader root is not readable: " root)
                      {:type :loader/bad-root :root (str root)})))))

(defn- roots-locate
  "Locate ns sources and resources under ROOTS, in order, without reading
   them — ns hits carry a file path, resource hits a URL."
  [roots]
  (fn [req]
    (case (:kind req)
      :ns (into []
                (for [root roots
                      rel (ns-source-paths (:name req))
                      :let [f (fs/file root rel)]
                      :when (fs/exists? f)]
                  {:kind :ns :file (str f)}))
      :resource (into []
                      (for [root roots
                            :let [f (fs/file root (:name req))]
                            :when (fs/exists? f)]
                        {:kind :resource :url (str (io/as-url f))}))
      nil)))

(defn url-search
  "A loader over root directories, probed in order: namespace sources at
   strict ns paths and resources by name. Optional PARENT makes it a
   delegate-first loader with these own roots as the fallback — use
   `self-first` to shadow for a prefix instead."
  ([roots] (url-search roots nil))
  ([roots parent]
   (let [roots (mapv str roots)]
     (doseq [r roots] (validate-root! r))
     (make-loader
      {:id (next-id "url-search")
       :parent parent
       :roots roots
       :locate-fn (roots-locate roots)}))))

(defn classpath
  "The URLClassLoader analogue over ROOTS (loader.md §4.2) — `(url-search
   roots)` with no parent."
  [roots]
  (url-search roots))
