(ns kmet.libs.concurrent
  "Small concurrency primitives shared across layers:

   - `spawn` — the extension-SCI daemon-thread replacement for `future`
   - `or-signal` — a read-only OR-view over cancel signals (the provider
     stream's guard trip, the run_code bridge's abort), used wherever a
     consumer must poll two triggers as one
   - `queue`/`put!`/`take!`/`close!` — the hand-off between a blocking reader
     thread and the owner thread that consumes what it read
   - `bounded-queue`/`offer!`/`take-dropped!` — the same hand-off when the
     producer may not wait: a wire's fan-out drops its oldest record under
     pressure and tells the consumer how many it lost
   - `after`/`await-all` — the awaitable pair: chain work behind a promise a
     caller will not block on, and spend one bounded deadline waiting for a
     batch of them (a teardown that hands its last step back)

   Extensions run in isolated SCI contexts where `future`/`pmap`/`pcalls`
   are not available — SCI is a pure interpreter with no bundled
   Executor, while babashka injects `future` only into its host context
   (see `kmet.app.extensions.core/build-context-namespaces`). `kmet` therefore
   exposes this tiny helper instead of whitelisting `future`:

   - host: `babashka` embeds `sci` + injects `future` via `:namespaces`
   - extensions: second `sci` context built by `kmet.app.extensions.core` —
     only `slurp`/`spit`/`file-seq` from `clojure.core` plus the shared
     `kmet.tui.*` / `kmet.libs.*` layers are injected; `future` is
     deliberately omitted.

   Whitelisting `future` would pull in an implicit `Executor`, make
   extension work survive `unload-extension!`/`reload-extensions!`
   (non-daemon futures keep kmet alive), enable unbounded submission
   with no backpressure, and require the rest of the `future` family
   (`future-call`, `future-cancel`, …) plus `pmap`/`pcalls` for
   consistency. Keeping extensions on an explicit daemon `Thread` makes
   the lifecycle obvious and keeps unload/reload clean. If a nicer
   primitive is needed later, expose exactly this `spawn` — not `future`."
  (:require [kmet.libs.log :as log]))

(defn spawn
  "Start a daemon thread running `(f)` — the extension-SCI replacement
   for `future` (which is not available in extension contexts).

   Exceptions in `f` are logged through `kmet.libs.log` (installed by the
   host's diagnostics switch) and otherwise swallowed (extension background
   work must never kill the host).
   Returns the `Thread` (already started, daemon=true)
   so callers that need to `.interrupt` / `.join` it can."
  [f]
  (let [t (Thread. (fn [] (try (f) (catch Throwable e
                                     (log/log! "spawn failed" e)))))]
    (.setDaemon t true)
    (.start t)
    t))

(defn or-signal
  "A read-only derefable that is true when any of SIGNALS fires — the OR-view
   of a run's cancel atom and a local trigger. A nil signal never fires
   (a script run outside the loop's cancel plumbing). The view is deref-only:
   the underlying atoms stay the single source of truth, never reset or
   watched through it."
  [& signals]
  (reify clojure.lang.IDeref
    (deref [_] (boolean (some (fn [s] (when s (boolean @s))) signals)))))

(def closed
  "What `take!` answers once the queue is closed and drained. A keyword rather
   than nil because a reader's line can be nil at end of input: a consumer must
   be able to tell \"nothing more will come\" from \"an empty line\"."
  ::closed)

(defn- hand-out
  "One atomic take decision: the next item, the closed marker, or the signal to
   park on. Keeping the decision in one `swap!` is what makes a `put!` that races
   a taker impossible to lose."
  [{:keys [items closed?] :as state}]
  (cond
    (seq items) (assoc state :handed (first items) :items (subvec items 1))
    (true? closed?) (assoc state :handed closed)
    :else (update state :signal #(or % (promise)))))

(defn queue
  "A hand-off queue: any number of producers, **one consumer** (`take!`), and
   `close!` to end it.

   It exists for the crossing a blocking reader needs: a socket reader thread
   parks on `read-line` and hands each line to the owner thread, which is the
   only thread allowed to touch the agent. A queue rather than a callback keeps
   the owner a plain loop; an atom rather than a blocking Java collection keeps
   it usable on both hosts and in extension contexts.

   The state is one atom — `:items` (a vector), `:closed?`, a `:signal` promise a
   waiting taker parks on, and the `:handed` slot a take holds its value in for the
   length of one swap — so every take is a single deciding swap. A vector rather
   than a `PersistentQueue` because extensions get this namespace through SCI, and
   SCI resolves static *methods* but not static *fields*:
   `clojure.lang.PersistentQueue/EMPTY` is `Unable to resolve symbol` there. `close!` leaves buffered items readable and then answers
   `closed`, so a consumer drains what it was sent before it learns why it
   stopped.

   One consumer is the contract, not a courtesy: `take!` records what it handed
   out in the queue's own state for the duration of a swap, so two takers would
   race that slot. Two consumers need two queues."
  []
  (atom {:items [] :closed? false :signal nil}))

(defn take!
  "The next value, blocking until one arrives; `closed` once the queue is closed
   and drained. Single consumer (see `queue`)."
  [q]
  (loop []
    (let [state (swap! q hand-out)]
      (if-let [handed (find state :handed)]
        (let [value (val handed)]
          (swap! q dissoc :handed)
          value)
        (do (deref (:signal state))
            (recur))))))

(defn put!
  "Hand V to the consumer. False when the queue is closed: the item is dropped
   and the caller learns it, rather than believing it was queued."
  [q v]
  (let [[old _] (swap-vals! q (fn [{:keys [closed?] :as state}]
                                (if closed?
                                  state
                                  (-> state
                                      (update :items conj v)
                                      (assoc :signal nil)))))]
    (if (:closed? old)
      false
      (do (when-let [signal (:signal old)] (deliver signal true))
          true))))

(defn close!
  "End the queue: buffered items stay readable, then `take!` answers `closed`, and
   later `put!`s return false. True the first time, false after."
  [q]
  (let [[old _] (swap-vals! q #(if (:closed? %) % (assoc % :closed? true)))]
    (if (:closed? old)
      false
      (do (when-let [signal (:signal old)] (deliver signal true))
          true))))

;; ─── a bounded lane: many producers, one consumer, drop-oldest ──────────────
;;
;; The hand-off queue above is unbounded because its producers are readers that
;; are allowed to wait — a socket reader parking on a line costs one thread and
;; nothing else. A wire's fan-out is the opposite case: a record written from a
;; run's own thread must never park the run behind a client that stopped
;; reading, and it must not grow without bound either. So the lane drops the
;; oldest record, counts what it dropped, and lets the consumer report the loss.

(defn bounded-queue
  "A `queue` with a CAPACITY and the drop-oldest policy: `offer!` never blocks,
   and a full lane drops its oldest record and counts it, so the consumer
   learns what it missed (`take-dropped!`) instead of the producer learning
   nothing and stalling.

   It exists for a wire fan-out: a run's events are written from the run's own
   thread, and a client that stops reading must cost its own buffer, not the
   turn. Dropping the *oldest* is the point — dropping the newest would lose the
   end of a message, and a gap the consumer can see is what lets it resync. One
   consumer, like `queue`; `close!` is shared."
  [capacity]
  (atom {:items [] :capacity (max 1 (long capacity)) :dropped 0
         :closed? false :signal nil}))

(defn- hand-out-dropped
  "One atomic take decision for a bounded lane: the next record, and how many
   were dropped since the previous take. The count travels with the item, so a
   consumer that fell behind can report the gap before it writes what survived."
  [{:keys [items dropped closed?] :as state}]
  (cond
    (seq items) (assoc state
                       :items (subvec items 1)
                       :handed {:value (first items) :dropped dropped}
                       :dropped 0)
    (true? closed?) (assoc state :handed {:value closed :dropped dropped} :dropped 0)
    :else (update state :signal #(or % (promise)))))

(defn offer!
  "Hand V to the consumer without blocking; when the lane is at capacity the
   oldest record is dropped and counted for `take-dropped!`. False when the
   lane is closed: the record is dropped and the caller learns it."
  [q v]
  (let [[old _] (swap-vals! q (fn [{:keys [closed? items capacity] :as state}]
                                (if closed?
                                  state
                                  (let [items (conj items v)
                                        full? (> (count items) capacity)]
                                    (if full?
                                      (-> state
                                          (assoc :items (subvec items 1))
                                          (update :dropped inc)
                                          (assoc :signal nil))
                                      (-> state
                                          (assoc :items items)
                                          (assoc :signal nil)))))))]
    (if (:closed? old)
      false
      (do (when-let [signal (:signal old)] (deliver signal true))
          true))))

(defn take-dropped!
  "The next value and how many records were dropped since the previous take:
   `{:value v :dropped n}`, where `:value` is `closed` once the lane is closed
   and drained. Single consumer (see `queue`)."
  [q]
  (loop []
    (let [state (swap! q hand-out-dropped)]
      (if-let [handed (find state :handed)]
        (let [value (val handed)]
          (swap! q dissoc :handed)
          value)
        (do (deref (:signal state))
            (recur))))))

(defn after
  "Run F once V settles, and answer what to wait on. V is nil (or any value that
   is not a promise): run F now, on this thread, and answer its value — the
   synchronous path, unchanged. V is an `IPending`: run F on a daemon thread
   after V delivers and answer a new promise of F's value.

   This is the chain an asynchronous teardown needs: the step that must not run
   under a running shutdown goes in F, and the value it answers is the handle
   the caller waits on. A throw inside F is logged and answers nil — this is
   cleanup, not a failure path, and an undelivered handle would hang the
   waiter. A promise nobody ever delivers parks its thread; it is a daemon, and
   the waiters have their own deadlines (`await-all`)."
  [v f]
  (if (instance? clojure.lang.IPending v)
    (let [done (promise)]
      (spawn (fn []
               (deref v)
               (deliver done (try (f)
                                  (catch Throwable t
                                    (log/log! "chained teardown failed" t)
                                    nil)))))
      done)
    (f)))

(defn monotonic-ms
  "Elapsed-time milliseconds — the only clock for durations and deadlines:
   timeouts, sleep slices, backoff, idle reaping, elapsed counters.

   `System/currentTimeMillis` steps (NTP corrections, Android/Termux radio
   time, VM resume), and a duration computed on a stepping clock is simply
   wrong: a forward step fires timeouts early (killing work that has just
   started) and lets backoff expire instantly, a backward step stalls
   deadlines — a TUI timer armed for 50ms can park for the step's length.

   The wall clock stays right where the value is an absolute time or is
   compared against externally-produced wall-clock values: token and JWT
   expiries, epoch ids/timestamps, file mtimes, persisted cross-process
   cache TTLs.

   `nanoTime` has no epoch and cannot step. It also excludes suspend/doze
   time (CLOCK_MONOTONIC), so a deadline does not count the time the host
   spent asleep — the trade-off Go timers, `deref` timeouts and core.async
   already make, and the safe direction: never kill work because the clock
   moved."
  []
  (quot (System/nanoTime) 1000000))

(defn await-all
  "Deref every awaitable in HANDLES against one deadline of TIMEOUT-MS from now,
   spent on `monotonic-ms`. Answers the handles that did not settle in time —
   empty when they all did. A non-positive TIMEOUT-MS waits for nothing: only
   handles that are already delivered settle.

   One deadline for the batch, not one per handle: a teardown's patience is the
   whole teardown's, so a stuck first handle cannot starve the rest and silently
   extend the budget."
  [handles timeout-ms]
  (let [deadline (+ (monotonic-ms) timeout-ms)]
    (reduce (fn [expired handle]
              ;; a zero slice still reads a delivered handle: the deadline bounds
              ;; the wait, not the answer
              (let [left (max 0 (- deadline (monotonic-ms)))]
                (if (identical? ::timeout (deref handle left ::timeout))
                  (conj expired handle)
                  expired)))
            []
            handles)))
