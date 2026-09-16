(ns kmet.tui.wake
  "Render-loop park/wake primitive (tui.md §6): one parking thread (the
   render loop) and any number of wakers (input, timers, reactive
   invalidations), with a timeout so the loop also wakes as a heartbeat.

   Parks on the object monitor (`wait`/`notifyAll`).")

(defn make-waker
  "Create a waker: the object the render loop parks on and everything else
   wakes. One per TUI session (kmet.tui.core/create-tui)."
  []
  (Object.))

(defn wake!
  "Wake a thread parked in park!. Callable from any thread; it takes the
   waker's lock (momentary — the parked thread releases it inside its wait).
   A wakeup with nobody parked is dropped — correct, because park! re-checks
   its own state under the same primitive before blocking (see park!)."
  [w]
  (locking w (.notifyAll w))
  nil)

(defn park!
  "Block the calling thread until wake! is called or TIMEOUT-MS elapses.
   RECHECK runs before blocking under the waker's lock, and a truthy result
   returns without parking: that is how a wakeup racing the park is consumed
   instead of lost. TIMEOUT-MS doubles as the heartbeat for wakeup paths that
   bypass wake!. Interrupting the parking thread propagates an
   InterruptedException (like the Thread/sleep this replaces). Returns nil."
  [w recheck timeout-ms]
  (locking w
    (when-not (recheck)
      (.wait w (long timeout-ms))))
  nil)
