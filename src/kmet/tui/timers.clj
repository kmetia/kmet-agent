(ns kmet.tui.timers
  "Loop-owned timer registry (tui.md §6.1): the one place time-driven
   UI work is scheduled, instead of every component parking its own
   `future` + `Thread/sleep` loop and re-inventing the zombie defense.

   The frame loop calls `pump!` on every wake (a render request, the next
   timer due, or the idle heartbeat) and fires whatever is due, so a timer
   thunk runs on the LOOP THREAD — the same thread that renders — and may
   touch widgets and component state directly. A thunk
   that wants a repaint either mutates tracked state (the reactive chain
   schedules the frame) or calls `kmet.tui.macros/schedule-frame!`.

   Repeating timers reschedule from *now*, not from the missed due time:
   a UI spinner that fell behind must not fire a burst to catch up. A
   throwing thunk is logged and swallowed (the loop must survive it), and
   a repeating timer keeps its next tick — the same policy as
   `macros/schedule-frame!`.

   The registry is process-global (like the frame hook) because components
   reach it without a reference to the TUI instance; `tui.core` pumps it
   each tick and calls `cancel-all!` on stop, so no timer outlives the
   session that created it. Headless tests drive `pump!` by hand.")

(defonce ^:private registry
  (atom {:next-id 0 :entries {}}))

(defn- now-ms [] (System/currentTimeMillis))

(defn- add!
  "Register F under a fresh id, FIRST due in MS from now (EVERY repeats).
   Returns the id for cancel!."
  [ms every f]
  (let [id (:next-id (swap! registry update :next-id inc))]
    (swap! registry assoc-in [:entries id]
           {:due (+ (now-ms) ms) :every every :f f})
    id))

(defn after!
  "Run F on the loop thread once, about MS milliseconds from now. Returns
   the id for cancel!. Resolution: the loop parks until the earlier of the
   next timer due and its idle heartbeat, so a timer armed on the loop
   thread fires on time; one armed from another thread while the loop is
   parked waits for the next wake — at most the heartbeat (100ms)."
  [ms f]
  (add! (max 0 ms) nil f))

(defn every!
  "Run F on the loop thread about every MS milliseconds until cancelled —
   what drives a spinner, an elapsed counter or a debounce that must keep
   re-arming. Returns the id for cancel!."
  [ms f]
  (add! (max 1 ms) (max 1 ms) f))

(defn cancel!
  "Stop the timer ID. Idempotent — cancelling an id that already fired
   (a one-shot) or was cleared by cancel-all! is a no-op, so a component
   may cancel unconditionally in dispose."
  [id]
  (swap! registry update :entries dissoc id)
  nil)

(defn cancel-all!
  "Stop every timer. tui.core calls this on stop, so nothing outlives the
   session; tests call it to isolate cases."
  []
  (swap! registry assoc :entries {})
  nil)

(defn pump!
  "Fire every timer that is due, on the CALLING thread (the loop thread).
   Returns true when anything fired — informational; a thunk wanting a
   repaint schedules one itself. A throwing thunk is logged and swallowed
   without cancelling its repeating timer."
  []
  (let [now (now-ms)
        due (filter (fn [[_ e]] (<= (:due e) now)) (:entries @registry))]
    (doseq [[id e] due]
      ;; reschedule/remove BEFORE running the thunk: a thunk that cancels
      ;; itself (or re-arms a new timer under the same site) must not be
      ;; resurrected by a later write here
      (if-let [period (:every e)]
        (swap! registry assoc-in [:entries id :due] (+ now period))
        (swap! registry update :entries dissoc id))
      (try
        ((:f e))
        (catch Throwable t
          (binding [*out* *err*]
            (println "kmet.tui.timers: timer thunk error:" (ex-message t))))))
    (boolean (seq due))))

(defn next-due-ms
  "Milliseconds until the earliest armed timer is due, 0 when one is due
   now, nil when no timer is armed — what the render loop caps its idle
   park by, so a parked loop still fires timers on time."
  []
  (when-let [entries (seq (:entries @registry))]
    (max 0 (- (reduce min (map :due (vals entries))) (now-ms)))))

(defn scheduled
  "The live timers as {id {:due :every}} — for tests and --debug
   observability (`:f` is omitted so the map prints)."
  []
  (update-vals (:entries @registry) #(dissoc % :f)))
