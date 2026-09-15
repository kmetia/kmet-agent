(ns kmet.libs.reakt
  "Standalone reactive engine: a Babashka port of reagent.ratom's semantics
   — auto-dependency-discovering reactions, cursors, batching — with plain
   clojure.lang.Atoms as first-class inputs. Generic by design: no terminal,
   rendering, or component concepts (the TUI render-cache layer that rides
   on it lives in kmet.tui.macros/track!; kmet.tui.core drains flush! on
   every render-loop wake and installs set-enqueue-hook! so a dirty reaction
   wakes the parked loop instead of waiting for its idle heartbeat).

   There is no custom atom type: Babashka seals IWatchable/IReset/IRef away
   from pure-source implementations, so a drop-in RAtom cannot exist.
   Dependency capture rides the deref funnel instead — tracked-deref, which
   records into BOTH capture frames: the render-body tracking scope
   (*tracking-scope*, bound by track-render) and any running reaction
   (*reaction-frame*). Reactions and cursors are reify'd IDeref refs carrying
   their own watcher registries; they record themselves into an enclosing
   reaction from their own deref, and are watched/disposed through
   watch-ref/unwatch-ref (core add-watch cannot take them — they are not
   IRefs).

   Coverage contract: tracked reads are explicit tracked-deref calls and
   nested reaction/cursor derefs (automatic); component render bodies get
   the same via track!'s lexical rewrite. A bare @plain-atom inside a
   hand-written body is untracked — correct under the batched fallback,
   just not narrow.

   Scheduling: a reaction whose deps change (by =) is marked dirty and
   ENQUEUED; flush! drains the queue and runs each dirty reaction exactly
   once per pass. Deref OUTSIDE any reaction settles the queue first and
   always answers with the CURRENT value (Reagent's non-reactive deref:
   flush, then inline-run when dirty) — the queued path exists purely for
   dep-change re-runs between frames.

   Examined against reagent.ratom (2.0.1) and rum's derived-atom/cursor
   (battle-tested references, ~/src/cvstree/). Adopted from them: sticky
   caught errors (a failed body's exception is rethrown by derefs without
   re-execution; force-run! retries; the next dep change clears it), callback
   scheduling (:auto-run? fn → ComponentFn's invalidate hook), manual-track
   value caching, last-watcher auto-dispose for manual reactions, and force-run!
   flushing the queue first. One deliberate deviation from current Reagent:
   plain reactions re-run QUEUED at the loop's flush, not synchronously in the
   watch handler — coalescing matters at streaming write rates.

   Writable cursors (writable-cursor) are read-only cursors plus a write
   path: reads track exactly like a cursor's, cursor-reset!/cursor-swap!
   write back through the source (=-gated, nested lenses composing), and a
   disposed lens refuses writes instead of writing back from the dead."
  (:refer-clojure :exclude [derive]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Context
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:dynamic *tracking-scope*
  "During a tracking scope, a map of the refs deref'd so far, keyed by
   ref → value as read. Bound by kmet.tui.macros/track-render around render
   bodies; nil outside one."
  nil)

(def ^:dynamic *reaction-frame*
  "The running reaction's capture frame ({:pending (atom #{}) :self ref}),
   bound around reaction bodies below. nil outside one."
  nil)

(defn in-reaction?
  "True when called inside a running reaction body (derefs here become
   tracked dependencies of that reaction)."
  []
  (some? *reaction-frame*))

;; ═══════════════════════════════════════════════════════════════════════════
;; Universal watching — plain atoms take the core path, library refs the
;; internal registry (SCI exposes no IWatchable implementation to hook)
;; ═══════════════════════════════════════════════════════════════════════════

(defprotocol RXRef
  "Internal surface of this library's refs (reactions, cursors).
   Plain atoms are watched through core add-watch; these refs are not IRefs,
   so they carry their own watcher registry. Not for external use — go
   through watch-ref/unwatch-ref, force-run!, dispose!."
  (-add-watch [this key f] "Register watcher F under KEY.")
  (-remove-watch [this key] "Drop the watcher under KEY.")
  (-cell [this] "The internal state atom.")
  (-dispose [this] "Kill the ref: unwatch deps, purge from the queue.")
  (-force-run [this] "Bring the ref current without going through deref."))

(defn reaction?
  "True for the library's own refs (reactions, cursors)."
  [ref]
  (satisfies? RXRef ref))

(defn trackable-ref?
  "True when REF can be a tracked dependency: IRef instances (plain atoms,
   vars — core add-watch works) or library reactive refs (reactions,
   cursors — watched through RXRef). Volatiles and delays can't take
   watches and are never tracked."
  [ref]
  (or (instance? clojure.lang.IRef ref) (satisfies? RXRef ref)))

(defn record-dep!
  "Record REF in the active reaction's pending-dependency set, if a reaction
   is running. Called from tracked-deref; reactions and cursors additionally
   record themselves from their own deref implementations."
  [ref]
  (when-some [{:keys [pending self]} *reaction-frame*]
    (when-not (identical? self ref)
      (swap! pending conj ref)))
  nil)

(defn tracked-deref
  "Deref wrapper recording REF in every active capture frame: the tracking
   scope (*tracking-scope*, when one is bound — with the value just read) and the
   running reaction (*reaction-frame*). Trackable refs (plain atoms/vars and
   library reactions/cursors, see trackable-ref?) are registered as
   dependencies; volatiles and delays can't take watches and are skipped
   (read but never registered, here or in a running reaction). This is the
   entry point for reactive reads outside component render bodies (which
   route through the track! rewrite)."
  [ref]
  (let [v (deref ref)]
    (when (and *tracking-scope* (trackable-ref? ref))
      (swap! *tracking-scope* assoc ref v))
    (when (trackable-ref? ref)
      (record-dep! ref))
    v))

(defn add-on-dispose!
  "Register F to run when R is disposed; F receives R (Reagent's
   add-on-dispose!). Runs after R is inert — deps unwatched, queue purged."
  [r f]
  (when (reaction? r)
    (swap! (-cell r) update :on-dispose (fnil conj []) f))
  r)

(defn watch-ref
  "add-watch for any reactive ref: plain atoms go through
   clojure.core/add-watch, library refs (reactions, cursors) through their
   internal registry. F receives (key ref old-val new-val) either way."
  [ref key f]
  (if (reaction? ref)
    (-add-watch ref key f)
    (add-watch ref key f)))

(defn unwatch-ref
  "remove-watch counterpart of watch-ref."
  [ref key]
  (if (reaction? ref)
    (-remove-watch ref key)
    (remove-watch ref key)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Batch queue — drained by flush!
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private max-rounds 100)

(defonce ^:private queue (atom []))

(defonce ^:private flushing? (atom false))

(defonce ^:private enqueue-hook (atom nil))

(defn set-enqueue-hook!
  "Install F as the batch-queue wake hook: called (on the mutating thread)
   when a reaction is newly ENQUEUED, so an event-driven host can wake its
   frame loop and drain the batch — without it, a derived ref's re-run waits
   for the host's own flush cadence. nil restores the no-op default. Runs
   inside a dep watch: a failure is logged and swallowed, never thrown into
   the write that dirtied the dep (the macros/set-frame-hook! policy).
   Process-global and host-owned: the TUI installs and clears its own hook
   (kmet.tui.core), so other consumers must not replace it."
  [f]
  (reset! enqueue-hook f)
  nil)

(defn- notify-enqueued!
  "Run the enqueue hook, isolated: it fires on the MUTATOR's thread, inside
   a watch, and must not disturb the write that dirtied a dep."
  []
  (when-some [f @enqueue-hook]
    (try
      (f)
      (catch Throwable e
        (binding [*out* *err*]
          (println "kmet.libs.reakt enqueue hook error:" (ex-message e))))))
  nil)

(defn- enqueue!
  "Add R to the batch queue unless already present (N invalidations between
   frames collapse into one run) and wake the host once per newly queued
   reaction — a reaction already waiting needs no second wake."
  [r]
  (let [added? (volatile! false)
        add (fn [q]
              (if (some (fn [x] (identical? x r)) q)
                q
                (do (vreset! added? true)
                    (conj q r))))]
    (swap! queue add)
    (when @added?
      (notify-enqueued!)))
  nil)

(defn- take-batch!
  "Atomically remove and return everything currently queued."
  ;; swap! returns the NEW value, so capture the old one inside the swap fn.
  []
  (let [captured (volatile! nil)]
    (swap! queue (fn [q]
                   (vreset! captured q)
                   []))
    @captured))

(defn queued-count
  "Number of reactions waiting in the batch queue (tests, debug counters)."
  []
  (count @queue))

(defn flush!
  "Drain the batch queue: bring every queued reaction current, repeating
   until empty (one reaction's run may dirty another — chained updates
   settle within one flush). Throws when the queue does not settle within
   max-rounds passes (cyclic reactions). A throwing reaction surfaces its
   error AFTER the batch finished draining — the rest of the batch still
   runs, so no reaction is left dirty but dequeued. Reactions dirtied by
   batch entries running later stay queued and settle at the next flush.
   Reentrant calls are no-ops; the outer drain wins."
  []
  (when (compare-and-set! flushing? false true)
    (try
      (loop [round 0]
        (let [batch (take-batch!)]
          (when (seq batch)
            ;; Run every entry even if one throws: the batch was already
            ;; dequeued, so bailing early would strand the rest dirty with
            ;; nothing to re-enqueue them. Reagent's _queued-run skip-clean
            ;; check lives here: entries whose state is already current
            ;; (settled synchronously earlier in this drain) don't re-run.
            (let [error (volatile! nil)]
              (doseq [r batch]
                (try
                  (when-not (#{:idle :failed} (:state @(-cell r)))
                    (-force-run r))
                  (catch Throwable e
                    (when-not @error
                      (vreset! error e)))))
              (when-some [e @error]
                (throw e)))
            (when (> round max-rounds)
              (throw (ex-info "kmet.libs.reakt: reaction queue did not settle (cyclic reactions?)"
                              {:rounds round})))
            (recur (inc round)))))
      (finally
        (reset! flushing? false)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Reaction
;; ═══════════════════════════════════════════════════════════════════════════

;; Cell states: :unrun (created, never executed) → :idle (cached value
;; valid) → :dirty (a dep changed; queued) → :busy (body running right now)
;; → :failed (body threw; :caught holds the ex, sticky until next change or
;; explicit force-run!). :disposed is terminal. Watches on the reaction itself live in the cell;
;; so does the dependency set (:watching).

;; ═══════════════════════════════════════════════════════════════════════════
;; Identity plumbing
;; ═══════════════════════════════════════════════════════════════════════════

;; Babashka seals hashCode/equals on SCI-generated classes: `=` over our
;; reify'd reactions is UNRELIABLE (false negatives even against the object
;; itself via certain dispatch paths). Two consequences, handled here:
;; — reactions must never sit in hash-based collections: :watching is a
;;   VECTOR scanned with identical?, the batch queue likewise;
;; — watch KEYS installed on plain atoms go through a record wrapper
;;   (structural hash/equals on a unique uid): an atom's internal watch map
;;   upgrades array-map→hash-map past 8 watchers, and hashing a raw reify
;;   would detonate right at transcript scale (per-message subscriptions).
(defrecord RxKey [uid])

(defn- identical-member?
  "True when COLL contains REF by identity."
  [coll ref]
  (boolean (some #(identical? % ref) coll)))

(defn changed?
  "Reagent-style change gate: identical? fast path, structural = otherwise.
   Public because tests and engine extensions need the same gate."
  [a b]
  (not (or (identical? a b) (= a b))))

(defn make-reaction
  "Wrap F in a reaction: running it captures every tracked deref as a
   dependency; when a dependency changes by =, F is marked dirty and brought
   current at the next flush!. Watchers registered through watch-ref fire
   only when F's OUTPUT changes by =.
   Options:
     :auto-run? controls scheduling (Reagent's :auto-run):
       true  (default) — dep changes enqueue F for the next flush!;
               derefs outside a reaction always answer CURRENT (they
               settle the queue and sync-run unrun/dirty bodies)
       false — manual track: also enqueued on change (like Reagent), but
               caches its value between runs, runs lazily on first deref,
               and disposes itself when its last watcher is removed
       ifn   — callback scheduling (reagent.impl.component's shape): the fn
               receives the reaction instead of any queueing — the ComponentFn
               hook, where the callback invalidates the component and requests
               a frame
     :rerun-without-deps? (default false) — when a run collects NO tracked
       dependencies beyond :implicit-deps, leave the reaction :unrun so the
       next deref re-runs it (batched-mode fallback). This keeps bodies that
       read only UNTRACKED values (bare @plain-atom in a hand-written fn,
       static trees) never stale: they re-derive every pass exactly as
       pre-reaction code did, instead of caching once forever. A body with
       at least one real tracked dep is cached normally — mixed bodies must
       read their reactive inputs through tracked-deref/cursors/slices
       (coverage contract).
     :implicit-deps — collection of refs to ignore in the emptiness check
       (the framework's own seeded reads, e.g. ComponentFn's props/ctree)"
  ([f] (make-reaction f nil))
  ([f {:keys [auto-run? rerun-without-deps? implicit-deps]
       :or {auto-run? true} :as _opts}]
   ;; The closures below need the reaction VALUE they collectively
   ;; constitute, but r is only complete once the reify form evaluates;
   ;; every closure runs strictly later (watch callbacks, body runs), so
   ;; they read the finished value through this holder.
   (let [cell (atom {:f f :state :unrun :value nil :watching []
                     :watches {} :caught nil
                     :on-dispose (if-some [od (:on-dispose _opts)]
                                   [od]
                                   [])})
         ;; set of the framework's own reads to ignore; (set nil) = #{}
         exempt? (set implicit-deps)
         self (atom nil)
         watch-key (RxKey. (gensym "rx"))
         dep-handler
         (fn [_key _ref old new]
           ;; Reagent's _handle-change gate: identical fast path, structural
           ;; =, skip if already dirty or dead. A disposed reaction stays
           ;; dead: watches can still fire once after dispose (watch-fire
           ;; order vs the unwatch), and must not resurrect it. The body is
           ;; isolated — it runs on the MUTATOR's thread (any background
           ;; thread swapping app state), so a failure here must never
           ;; propagate into whoever wrote the dep.
           (try
             (when (and (changed? old new)
                        (not= :dirty (:state @cell))
                        (not= :disposed (:state @cell)))
               (swap! cell assoc :state :dirty :caught nil)
               (if (fn? auto-run?)
                 (auto-run? @self)
                 (enqueue! @self)))
             (catch Throwable e
               (binding [*out* *err*]
                 (println "kmet.libs.reakt dep-handler error:" (.getMessage e))))))
         update-watching!
         (fn [collected]
           ;; Set-diff (Reagent's _update-watching), identity-flavored:
           ;; watch newly-captured refs, unwatch refs this run stopped
           ;; reading. VECTOR + identical? — see Identity plumbing above.
           (let [{:keys [watching]} @cell
                 added (remove #(identical-member? watching %) collected)
                 dropped (filter #(and (identical-member? watching %)
                                       (not (identical-member? collected %)))
                                 watching)]
             (doseq [dep added]
               (watch-ref dep watch-key dep-handler))
             (doseq [dep dropped]
               (unwatch-ref dep watch-key))
             (swap! cell assoc :watching (vec collected))))
         run-sync!
         (fn []
           (loop [guard 0]
             (let [{:keys [state value] :as snap} @cell]
               (cond
                 (= :disposed state) nil
                 ;; Reentrant deref of R inside R's own body: hand back the
                 ;; value being replaced rather than recursing forever.
                 (= :busy state) value
                 (> guard max-rounds)
                 (throw (ex-info "kmet.libs.reakt: reaction did not converge after repeated mid-run invalidation"
                                 {:rounds guard}))
                 :else
                 ;; Fresh re-check: dispose may have landed between the
                 ;; loop-top read and here — never execute a dead body.
                 (if (= :disposed (:state @cell))
                   nil
                   (let [_ (swap! cell assoc :state :busy)
                         ;; Mark BUSY before the body runs: a :dirty seen AFTER
                         ;; the pass can then only mean a dep was written while
                         ;; the body executed (mid-run invalidation), never the
                         ;; stale flag this run was started for.
                         pending (atom #{})
                         result (try
                                  (binding [*reaction-frame* {:pending pending :self @self}]
                                    ((:f snap)))
                                  (catch Throwable e
                                    ;; Body threw: sticky failure (Reagent's
                                    ;; caught) — derefs rethrow it without
                                    ;; re-executing the body; an explicit
                                    ;; force-run! retries; the next dep change
                                    ;; clears it.
                                    (swap! cell assoc :state :failed :caught e)
                                    (throw e)))
                         collected @pending]
                     ;; Disposed while the body ran: keep it dead — no watch
                     ;; re-registration, no value write, nothing observable.
                     (if (= :disposed (:state @cell))
                       nil
                       (do (update-watching! collected)
                           (swap! cell assoc :value result :caught nil
                                  ;; A dep written while the body ran left :dirty
                                  ;; behind — loop to re-run against fresh state.
                                  :state (cond
                                           (= :dirty (:state @cell)) :dirty
                                           ;; Batched fallback: nothing tracked was
                                           ;; read beyond the framework's own seeded
                                           ;; deps, so this cached value could never
                                           ;; be invalidated — re-run on next deref
                                           ;; instead of caching stale output.
                                           (and rerun-without-deps?
                                                (empty? (remove exempt? collected))) :unrun
                                           :else :idle))
                           (when (changed? result value)
                             ;; Watcher isolation: a throwing watcher must
                             ;; not abort its siblings nor propagate into
                             ;; whatever thread triggered the change.
                             (doseq [[key fl] (:watches @cell)]
                               (try
                                 (fl key @self value result)
                                 (catch Throwable e
                                   (binding [*out* *err*]
                                     (println "kmet.libs.reakt watcher error:"
                                              (.getMessage e)))))))
                           (if (= :dirty (:state @cell))
                             (recur (inc guard))
                             result)))))))))
         deref-fn
         (fn []
           (let [{:keys [state value caught]} @cell]
             (cond
               (= :disposed state) nil
               ;; Sticky failure: rethrow the captured error without
               ;; re-executing the body (Reagent's caught) — a read must
               ;; never re-run side effects. force-run! is the explicit retry.
               (some? caught) (throw caught)
               (= :busy state) value
               :else (do
                       ;; Non-reactive reads settle the queue FIRST (Reagent
                       ;; flushes on every non-reactive deref): upstream
                       ;; reactions dirtied since the last frame must be
                       ;; current before this ref answers, or chained reads
                       ;; see a stale slice. The flush may run THIS reaction
                       ;; too (it can sit queued), so re-read after.
                       (when (nil? *reaction-frame*)
                         (flush!))
                       (let [{:keys [state value caught]} @cell]
                         (cond
                           (= :disposed state) nil
                           (some? caught) (throw caught)
                           (= :busy state) value
                           ;; Cached-valid — plain reactions AND manual tracks
                           ;; hand back without running (Reagent's Track caches
                           ;; its inner reaction); an in-context parent reading
                           ;; a clean child must not force a redundant run.
                           (= :idle state) value
                           ;; Everything else runs synchronously: first reads
                           ;; anywhere (unrun — Reagent births reactions dirty),
                           ;; manual tracks' first read, dirty reactions. An
                           ;; in-context parent captured SELF just before (reify
                           ;; deref → record-dep!), so it tracks this ref.
                           :else (run-sync!)))))))
         r (reify
             RXRef
             (-add-watch [_ key fl] (swap! cell assoc-in [:watches key] fl) _)
             (-remove-watch [_ key]
               ;; Reagent's GC hygiene: when a MANUAL reaction's last watcher
               ;; leaves, it disposes itself — its dep watches would otherwise
               ;; pin it (and them) forever. Plain/callback reactions keep
               ;; running; they are disposed explicitly. (Unlike Reagent,
               ;; dispose is TERMINAL — a dead reaction never resurrects on
               ;; deref — so anything driven by derefs or the auto-run
               ;; callback must not self-dispose here.)
               (let [was (:watches @cell)]
                 (swap! cell update :watches dissoc key)
                 (when (and (seq was)
                            (empty? (:watches @cell))
                            (false? auto-run?))
                   (-dispose @self)))
               _)
             (-cell [_] cell)
             (-dispose [_]
               (doseq [dep (:watching @cell)]
                 (unwatch-ref dep watch-key))
               ;; Purge from the batch queue too: a disposed reaction must be
               ;; fully inert immediately, not skipped-later (watch-fire order
               ;; relative to dispose is arbitrary — without the purge its own
               ;; dep-handler can enqueue it after it died).
               (swap! queue (fn [q]
                              (filterv (fn [x] (not (identical? x @self))) q)))
               (swap! cell assoc :state :disposed :watching #{}
                      :watches {} :value nil :caught nil)
               ;; Disposers fire last, once the reaction is fully inert;
               ;; each receives the reaction (Reagent contract).
               (doseq [f (:on-dispose @cell)]
                 (f @self))
               nil)
             (-force-run [_]
               ;; Explicit recompute: retries through sticky failures, always
               ;; runs unless disposed. The queue's clean-skip lives in
               ;; flush!, not here.
               (let [{:keys [state]} @cell]
                 (when-not (= :disposed state)
                   (run-sync!))))
             clojure.lang.IDeref
             (deref [_]
               ;; Record R as a dependency of the ENCLOSING reaction, if one
               ;; is running, then produce the value under our own frame.
               (record-dep! @self)
               (deref-fn)))]
     (reset! self r)
     r)))

(defn reaction-state
  "Debugging introspection: {:state :value :watching} of R."
  [r]
  (let [{:keys [state value watching]} @(-cell r)]
    {:state state :value value :watching watching}))

(defn force-run!
  "Flush the batch queue (so upstream reactions are current), then bring R
   current synchronously — Reagent's run. Retries through sticky failures;
   manual tracks always recompute here (deref is the cached path)."
  [r]
  (flush!)
  (-force-run r))

(defn invalidate!
  "Force R's next deref to re-run its body even though no dependency changed
   (normal caching hands back the value while every dep is unchanged).
   For inputs that shape output without being tracked deps — ComponentFn
   calls this when the render-pass width changes, because width is a
   dynamic var, not an atom. Marks R :dirty, so the next deref (or a flush,
   should one land in between) brings it current. Disposed refs are ignored."
  [r]
  (when (reaction? r)
    (swap! (-cell r)
           (fn [{:keys [state] :as c}]
             (if (= :disposed state)
               c
               (assoc c :state :dirty :caught nil)))))
  nil)

(defn dispose!
  "Kill R: unwatch every dependency, drop watchers, leave the queue.
   Later derefs return nil. Idempotent."
  [r]
  (when (reaction? r)
    (-dispose r))
  nil)

;; ═══════════════════════════════════════════════════════════════════════════
;; Public sugar
;; ═══════════════════════════════════════════════════════════════════════════

(defn derive
  "A derived reactive ref over DEPS: sugar over make-reaction whose body
   reads every dep through a tracked deref and applies F to their current
   values. The reaction re-derives when any dependency changes by = — the
   explicit DEPS list seeds tracking, and everything F itself reads through
   tracked channels (tracked-deref, other derives/reactions, cursors)
   joins the discovered set automatically. A recomputation to an equal
   value notifies nobody: watchers fire only on real output changes.

   F is applied to the CURRENT VALUES of the deps as arguments:
   (derive [theme-atom] identity) and (derive [a b] +) work directly; with
   an empty dep list F takes no arguments and may close over its own
   slices. Returns a reaction: derefable everywhere (@d), watchable via
   watch-ref, never reset!. Create once per owner — one built bare inside
   a re-running body leaks a reaction per pass."
  [deps f]
  (make-reaction (fn [] (apply f (mapv tracked-deref deps)))))

(defmacro reaction
  "Form-1 reactive body: (reaction body...) ≡ (make-reaction (fn [] body...)).
   Deps are discovered at deref time — read atoms via tracked-deref (or
   deref other reactions/cursors) inside."
  [& body]
  `(make-reaction (fn [] ~@body)))

(defn cursor
  "A derived view over SOURCE at PATH: (get-in @source path) with the source
   read captured, so reactions reading the cursor track the source. PATH is
   a seq, or varargs keys. Implemented as an auto-running reaction (the
   Reagent ≥0.10 model)."
  ([source path]
   ;; Accept a seq/vector or a bare key (Reagent-style (cur src k1 k2)):
   ;; normalize so get-in always gets a sequential path.
   (let [path (if (sequential? path) path [path])]
     (make-reaction #(get-in (tracked-deref source) path))))
  ([source k & ks]
   (cursor source (cons k ks))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Writable cursors — the lens's write half
;; ═══════════════════════════════════════════════════════════════════════════

(defprotocol IWritableCursor
  "Internal surface of writable cursors (see writable-cursor). Not for
   external use — go through cursor-reset!/cursor-swap!."
  (-cursor-set
    [this v]
    "Write V through to the source at this cursor's path. V on success (an
     already-equal value lands trivially); nil when the write could not
     land — this cursor, or a lens it writes through, is disposed.")
  (-cursor-apply
    [this f args]
    "Apply F to the current value and write the result back. The new value,
     or nil when the write could not land (see -cursor-set)."))

(defn writable-cursor?
  "True for refs created by writable-cursor — the write-capable lens over a
   source ref. Plain atoms, read-only cursors and reactions answer false."
  [ref]
  (satisfies? IWritableCursor ref))

(defn- write-through!
  "The lens's write half: put V into SOURCE at PATH, returning V when the
   value landed, nil when the write was refused. PATH is already normalized;
   empty means replace the whole value (assoc-in would assoc a nil key
   instead). SOURCE is a plain ref (swap! + assoc-in) or another writable
   cursor — written through its own setter, so nested lenses compose and a
   refusal anywhere up the chain (a disposed lens) propagates as nil."
  [source path v]
  (if (writable-cursor? source)
    (-cursor-set source (if (seq path) (assoc-in @source path v) v))
    (do (swap! source (fn [m] (if (seq path) (assoc-in m path v) v)))
        v)))

(defn- disposed-lens?
  "True when a cursor's inner reaction has been disposed. Such a cursor is
   inert: its `=`-gate can no longer read (deref answers nil), so writes
   must not sneak through it — a zombie write-back into a live source is
   exactly what disposal is supposed to end."
  [rx]
  (= :disposed (:state (reaction-state rx))))

(defn writable-cursor
  "A writable cursor (lens) over SOURCE at PATH — the read-write sibling of
   cursor. Reads are (get-in @source path), tracked exactly like a cursor's,
   so reactions reading it track the source; cursor-reset!/cursor-swap!
   write back through the source with assoc-in and return the new value, so
   a two-way binding is just @cur plus a setter call — no separate setter
   callback threaded through props.

   Writes are =-gated: an equal value touches neither the source nor its
   watchers (the differing check that keeps an on-change → write-back →
   re-render cycle from looping). Otherwise a write is an ordinary source
   change: the queued invalidation, watch-ref watchers and the :auto-run?
   callback all flow normally through the lens's reaction.

   Keep writable cursors for state the UI EDITS (a setting, a filter, a
   draft field); read-only derivation stays on cursor, whose subscribers
   cannot write. SOURCE is a plain atom (or another writable cursor, for a
   nested path); unlike the read-only cursor, a reaction cannot be a
   source — there is no write-back path — and that throws at construction.
   PATH is a seq or varargs keys, [] for the whole value.
   Create one ONCE per owner (a bare cursor built inside a re-running body
   leaks a reaction per pass, like any reaction), and dispose it when the
   owner goes away: a disposed cursor is inert — derefs answer nil and
   writes are ignored (nil), so nothing writes back through a dead lens.
   The cursor delegates its watch/dispose surface to that inner reaction:
   `watch-ref`, `add-on-dispose!`, `force-run!` and `dispose!` all reach it
   (so `add-on-dispose!` disposers receive the inner reaction). A cursor
   may be sourced from another writable cursor (a nested lens); a refusal
   anywhere up that chain — some ancestor disposed — refuses the write
   rather than writing through a dead lens."
  ([source path]
   (let [path (if (sequential? path) path [path])
         _ (when-not (or (writable-cursor? source)
                         (instance? clojure.lang.IAtom source))
             (throw (ex-info
                     (str "kmet.libs.reakt/writable-cursor: source must be "
                          "a plain atom or another writable cursor — "
                          (pr-str (class source))
                          " has no write-back path (a read-only cursor "
                          "and a reaction are read-only)")
                     {:source source})))
         rx (make-reaction #(get-in (tracked-deref source) path))
         self (atom nil)
         cur (reify
               IWritableCursor
               (-cursor-set [_ v]
                 (when-not (disposed-lens? rx)
                   (if (changed? @rx v)
                     (write-through! source path v)
                     v)))
               (-cursor-apply [_ f args]
                 (when-not (disposed-lens? rx)
                   (let [current @rx
                         v (apply f current args)]
                     (if (changed? current v)
                       (write-through! source path v)
                       v))))
               RXRef
               (-add-watch [_ key f] (-add-watch rx key f))
               (-remove-watch [_ key] (-remove-watch rx key))
               (-cell [_] (-cell rx))
               (-dispose [_] (-dispose rx))
               (-force-run [_] (-force-run rx))
               clojure.lang.IDeref
               (deref [_]
                 ;; Record the cursor (not only its inner reaction) as the
                 ;; enclosing reaction's dependency — the same contract
                 ;; reactions carry (see the RXRef docstring).
                 (record-dep! @self)
                 (deref rx)))]
     (reset! self cur)
     cur))
  ([source k & ks]
   (writable-cursor source (cons k ks))))

(defn- not-writable! [fname ref]
  (throw (ex-info
          (str "kmet.libs.reakt/" fname ": not a writable cursor — make one "
               "over the source with kmet.libs.reakt/writable-cursor "
               "(a read-only cursor has no path to write back to, and a "
               "plain atom is written with swap!/reset! directly)")
          {:ref ref})))

(defn cursor-reset!
  "Write V through writable cursor CUR into its source (assoc-in at CUR's
   path) and return V. =-gated: an equal value touches neither the source
   nor its watchers. Nil when the write could not land — CUR, or a lens it
   writes through, is disposed (a nil V that DID land is also nil; the
   refusal is for writes that stay out of the source). Throws when CUR is
   not a writable cursor."
  [cur v]
  (if (writable-cursor? cur)
    (-cursor-set cur v)
    (not-writable! "cursor-reset!" cur)))

(defn cursor-swap!
  "Apply F to CUR's current value plus ARGS and write the result back
   through the source (=-gated), returning the new value — the writable
   cursor's swap!. F's argument is the value read at call time (read and
   write are two steps; the write itself is a swap! on the source), so a
   concurrent writer of the same slice is last-write-wins, as with any
   read-then-swap. Nil when the write could not land — CUR, or a lens it
   writes through, is disposed. Throws when CUR is not a writable cursor."
  [cur f & args]
  (if (writable-cursor? cur)
    (-cursor-apply cur f args)
    (not-writable! "cursor-swap!" cur)))
