(ns kmet.libs.weak
  "Queue/sweep weak-subscription registry: a subscription never keeps its
   subject alive. Entries hold their subject through a `WeakReference`; once
   the subject is collected the reference lands on a `ReferenceQueue`, the
   next `sweep!` claims every entry whose reference cleared, and the entry's
   `on-dead` fn unsubscribes (leaks.md 2.1).

   Consumers never close a watch handler over the subject: the handler
   captures the entry's key only, and the subject is looked up when the
   watch fires. `register!` is the per-pass call — it reuses the existing
   `WeakReference` while the subject is identical (a streaming render pass
   must not allocate one ref per pass) and sweeps opportunistically, which
   is one `.poll` when nothing died. `sweep!` runs at the render loop's wake
   and after `kmet.libs.reakt/flush!`, so headless consumers sweep too.

   This is the one namespace allowed to touch `java.lang.ref` (the
   platform-dependency exception, like the terminal backends); both hosts
   support `WeakReference`/`ReferenceQueue` natively."
  (:import [java.lang.ref ReferenceQueue WeakReference]))

(defonce ^:private registry
  ;; key -> {:ref (WeakReference subject queue)  ; the subject, weakly held
  ;;         :payload map                        ; unsubscribe data; NEVER the subject
  ;;         :on-dead (fn [key payload])}        ; unsubscribe + cleanup
  (atom {}))

(defonce ^:private queue (ReferenceQueue.))

(defn- live-subject
  "ENTRY's subject while it is still reachable, else nil."
  [entry]
  (when entry (.get (:ref entry))))

(defn- collected?
  "True when ENTRY's subject was collected."
  [entry]
  (nil? (live-subject entry)))

(defn- prune-dead
  "Registry map → map with the collected entries removed."
  [reg]
  (persistent!
   (reduce-kv (fn [m k entry]
                (if (collected? entry) m (assoc! m k entry)))
              (transient {})
              reg)))

(defn- run-on-dead!
  "Run each claimed entry's on-dead — outside the registry swap and isolated
   per entry, so one failure cannot stop the sweep or its siblings."
  [claimed]
  (doseq [[k entry] claimed]
    (try
      ((:on-dead entry) k (:payload entry))
      (catch Throwable t
        (binding [*out* *err*]
          (println "kmet.libs.weak on-dead error:" (ex-message t)))))))

(defn- register-entry
  "The registry map with KEY registered or refreshed for SUBJECT. Throws on
   a different live subject already holding KEY."
  [reg key subject payload on-dead]
  (let [entry (get reg key)
        live (live-subject entry)]
    (cond
      (and live (not (identical? live subject)))
      (throw (ex-info "key is held by a different live subject"
                      {:type ::subject-conflict :key key}))

      (and entry (identical? live subject))
      (assoc reg key (assoc entry :payload payload :on-dead on-dead))

      :else
      (assoc reg key {:ref (WeakReference. subject queue)
                      :payload payload
                      :on-dead on-dead}))))

(declare sweep!)

(defn register!
  "Create or refresh KEY's entry for SUBJECT. PAYLOAD is everything the sweep
   needs to unsubscribe (watched refs, watch keys); it must not reference
   SUBJECT, or the entry would root it. ON-DEAD receives `(key payload)`
   after the entry is removed and runs exception-isolated, outside the
   registry swap.

   Reuses the existing `WeakReference` while SUBJECT is identical, so a
   streaming render pass does not allocate one ref per pass; sweeps
   opportunistically first (one `.poll` on an empty queue).

   Throws when KEY is already held by a DIFFERENT live subject: two
   components sharing a cache atom (track!) or two reactions sharing a
   registry key would silently steal each other's watches, and loud beats
   corrupt."
  [key subject payload on-dead]
  (sweep!)
  (swap! registry register-entry key subject payload on-dead)
  nil)

(defn subject
  "The live subject for KEY, or nil when it was collected or never
   registered."
  [key]
  (live-subject (get @registry key)))

(defn payload
  "KEY's payload, or nil when KEY has no entry (never registered,
   unregistered, or claimed by a sweep)."
  [key]
  (:payload (get @registry key)))

(defn unregister!
  "Remove KEY's entry and return its payload. ON-DEAD does not run — the
   caller is disposing deterministically and unsubscribes itself. Idempotent;
   nil when KEY has no entry."
  [key]
  (let [[old _] (swap-vals! registry dissoc key)]
    (:payload (get old key))))

(defn- drain-queue!
  "Poll every enqueued reference off the queue; return how many drained."
  []
  (loop [n 0]
    (if (.poll queue) (recur (inc n)) n)))

(defn sweep!
  "Drain the queue; when anything was enqueued, claim the entries this swap
   removed (one `swap-vals!`, so concurrent sweeps cannot run an entry's
   `on-dead` twice), run the claimed `on-dead` fns exception-isolated, and
   return the count. An entry that clears after the swap waits for the next
   sweep rather than being claimed twice. Safe from any thread; cheap when
   nothing died (one `.poll`)."
  []
  (if (zero? (drain-queue!))
    0
    (let [[old new] (swap-vals! registry prune-dead)
          ;; claim exactly what this swap removed — an entry that clears
          ;; after the swap stays until the next sweep
          claimed (filterv (fn [[k _]] (not (contains? new k))) old)]
      (run-on-dead! claimed)
      (count claimed))))

(defn live-count
  "Entries whose subject is still alive (tests and guards). O(entries)."
  []
  (count (remove (fn [[_ entry]] (collected? entry)) @registry)))

(defn entry-count
  "All entries, including dead-until-swept (tests and guards)."
  []
  (count @registry))
