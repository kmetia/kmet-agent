(ns kmet.tui.wake
  "Render-loop park/wake primitive (tui.md §6): one parking thread (the
   render loop) and any number of wakers (input, timers, reactive
   invalidations), with a timeout so the loop also wakes as a heartbeat.

   bb/JVM park on the object monitor (`wait`/`notifyAll`). jolt supplies no
   Object.wait/notify/notifyAll at all, so its branch is a capacity-1
   LinkedBlockingQueue used as a binary semaphore — a workaround documented
   with its ticket in jolt-bugs.md; delete that branch (keep the monitor
   bodies as plain code) when jolt grows the monitor pair.")

(defn make-waker
  "Create a waker: the object the render loop parks on and everything else
   wakes. One per TUI session (kmet.tui.core/create-tui)."
  []
  #?(:jolt (java.util.concurrent.LinkedBlockingQueue. 1)
     :default (Object.)))

(defn wake!
  "Wake a thread parked in park!. Callable from any thread; on the monitor
   host it takes the waker's lock (momentary — the parked thread releases it
   inside its wait), on jolt it is a plain offer. A wakeup with nobody
   parked is either remembered (jolt's queue) or dropped (the monitor) —
   both are correct, because park! re-checks its own state under the same
   primitive before blocking (see park!)."
  [w]
  #?(:jolt (.offer w :wake)
     :default (locking w (.notifyAll w)))
  nil)

(defn park!
  "Block the calling thread until wake! is called or TIMEOUT-MS elapses.
   RECHECK runs before blocking — under the waker's lock on the monitor
   host — and a truthy result returns without parking: that is how a wakeup
   racing the park is consumed instead of lost (the monitor cannot remember
   one; the jolt queue can, but the recheck keeps both hosts on one
   contract). TIMEOUT-MS doubles as the heartbeat for wakeup paths that
   bypass wake!. Interrupting the parking thread propagates an
   InterruptedException (like the Thread/sleep this replaces). Returns nil."
  [w recheck timeout-ms]
  #?(:jolt (when-not (recheck)
             (.poll w (long timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS))
     :default (locking w
                (when-not (recheck)
                  (.wait w (long timeout-ms)))))
  nil)
