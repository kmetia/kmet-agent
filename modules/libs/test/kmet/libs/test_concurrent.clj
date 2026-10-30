(ns kmet.libs.test-concurrent
  "kmet.libs.concurrent — the extension-safe spawn/or-signal primitives, the
   hand-off queue a blocking reader fills, and monotonic-ms, the clock every
   timeout, backoff and idle reaper measures with."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.libs.concurrent :as concurrent]))

(deftest monotonic-ms-measures-elapsed-time
  (let [t0 (concurrent/monotonic-ms)]
    (Thread/sleep 10)
    (let [elapsed (- (concurrent/monotonic-ms) t0)]
      (is (<= 5 elapsed) "the clock advances with real time")
      (is (< elapsed 10000) "and by about the time that actually passed"))))

(deftest spawn-runs-f-on-a-daemon-thread
  (let [p (promise)
        t (concurrent/spawn #(deliver p :done))]
    (is (= :done (deref p 2000 :timeout)))
    (is (.isDaemon t))))

(deftest or-signal-is-true-when-any-signal-fires
  (let [a (atom false)
        b (atom false)]
    (is (false? @(concurrent/or-signal a b)))
    (reset! a true)
    (is (true? @(concurrent/or-signal a b)))
    (reset! a false)
    (reset! b true)
    (is (true? @(concurrent/or-signal a b)))
    (is (false? @(concurrent/or-signal a nil)) "a nil signal never fires")))

;; ─── queue: the crossing between a reader thread and the owner ───────────────

(defn- parked?
  "True once a taker is waiting on Q's signal — the loop reads the queue's own
   state rather than sleeping a fixed time, so the assertion is about the queue,
   not about the scheduler."
  [q]
  (loop [n 0]
    (cond
      (some? (:signal @q)) true
      (< 500 n) false
      :else (do (Thread/sleep 1) (recur (inc n))))))

(deftest queue-hands-items-over-in-order
  (let [q (concurrent/queue)]
    (doseq [v [1 2 3]] (is (true? (concurrent/put! q v)) "put reports the hand-over"))
    (is (= [1 2 3] [(concurrent/take! q) (concurrent/take! q) (concurrent/take! q)])
        "FIFO, and a take with items waiting does not park")))

(deftest take-blocks-until-a-put-arrives
  (let [q (concurrent/queue)
        taken (promise)]
    (concurrent/spawn #(deliver taken [(concurrent/take! q) (concurrent/take! q)]))
    (is (parked? q) "the taker parked on the queue's signal")
    (concurrent/put! q :first)
    (concurrent/put! q :second)
    (is (= [:first :second] (deref taken 2000 :timeout))
        "the parked taker woke, took one item, parked again, and took the next")))

(deftest a-put-that-races-a-taker-is-not-lost
  (let [q (concurrent/queue)]
    (dotimes [i 200]
      (let [taken (promise)]
        (concurrent/spawn #(deliver taken (concurrent/take! q)))
        (is (true? (concurrent/put! q i)))
        (is (= i (deref taken 2000 :timeout))
            (str "iteration " i ": the item reached the taker exactly once"))))))

(deftest a-handed-out-item-leaves-nothing-behind
  (testing "the take clears the item it handed out, so the next take on an empty
            queue parks instead of re-delivering it — the mutation a missing
            cleanup would hide, because a parking take cannot overwrite the slot"
    (let [q (concurrent/queue)]
      (concurrent/put! q :only)
      (is (= :only (concurrent/take! q)))
      (let [taken (promise)]
        (concurrent/spawn #(deliver taken (concurrent/take! q)))
        (is (parked? q) "the queue parked: :only was not handed over twice")
        (concurrent/put! q :next)
        (is (= :next (deref taken 2000 :timeout)))))))

(deftest close-lets-the-consumer-drain-then-ends-it
  (let [q (concurrent/queue)]
    (concurrent/put! q :buffered)
    (is (true? (concurrent/close! q)) "the first close reports the change")
    (is (false? (concurrent/close! q)) "a second close changes nothing")
    (is (= :buffered (concurrent/take! q))
        "an item already queued is still delivered — close means no more will come")
    (is (= concurrent/closed (concurrent/take! q)) "then the take answers the marker")
    (is (= concurrent/closed (concurrent/take! q)) "and keeps answering it")
    (is (false? (concurrent/put! q :late)) "a put after close is refused, by name")
    (is (= concurrent/closed (concurrent/take! q)) "and the refused item was never queued")))

(deftest close-wakes-a-parked-taker
  (let [q (concurrent/queue)
        taken (promise)]
    (concurrent/spawn #(deliver taken (concurrent/take! q)))
    (is (parked? q))
    (concurrent/close! q)
    (is (= concurrent/closed (deref taken 2000 :timeout))
        "closing is what unblocks a reader whose producer is gone")))

(deftest nil-is-a-value-not-an-end-of-input
  (testing "the reader's read-line returns nil at EOF, and a queued nil means an
            empty line rather than the end — hence the keyword marker"
    (let [q (concurrent/queue)]
      (concurrent/put! q nil)
      (is (nil? (concurrent/take! q)))
      (concurrent/close! q)
      (is (= concurrent/closed (concurrent/take! q))))))

;; ─── bounded-queue: the wire's fan-out lane ─────────────────────────────────

(deftest a-bounded-lane-drops-its-oldest-record-and-counts-it
  (let [q (concurrent/bounded-queue 2)]
    (is (true? (concurrent/offer! q :a)))
    (is (true? (concurrent/offer! q :b)))
    (is (true? (concurrent/offer! q :c))
        "a full lane still accepts: it drops rather than refuses")
    (is (= {:value :b :dropped 1} (concurrent/take-dropped! q))
        "the oldest record is gone and the take reports one drop")
    (is (= {:value :c :dropped 0} (concurrent/take-dropped! q))
        "the count is per take: the gap is announced once, not on every record")))

(deftest a-bounded-lane-counts-drops-across-takes
  (let [q (concurrent/bounded-queue 1)]
    (dotimes [i 5] (concurrent/offer! q i))
    (is (= {:value 4 :dropped 4} (concurrent/take-dropped! q))
        "four of five records were dropped, and the survivor is the newest")))

(deftest a-bounded-lane-wakes-a-parked-taker-and-ends-with-close
  (let [q (concurrent/bounded-queue 4)
        taken (promise)]
    (concurrent/spawn #(deliver taken (concurrent/take-dropped! q)))
    (is (parked? q) "the taker parked on the lane's own signal")
    (concurrent/offer! q :only)
    (is (= {:value :only :dropped 0} (deref taken 2000 :timeout)))
    (concurrent/close! q)
    (is (false? (concurrent/offer! q :late)) "an offer after close is refused")
    (is (= {:value concurrent/closed :dropped 0}
           (concurrent/take-dropped! q))
        "and the closed marker is the value, as on the unbounded queue")))

(deftest after-runs-f-now-when-there-is-nothing-to-wait-for
  (let [ran (atom false)]
    (is (= :done (concurrent/after nil (fn [] (reset! ran true) :done))))
    (is (true? @ran) "a nil handle runs F on this thread, unchanged")
    (is (= :now (concurrent/after :a-value (fn [] :now)))
        "a value that is not a promise is not waited for either")))

(deftest after-chains-behind-a-promise
  (let [settled (promise)
        ran (atom false)
        chained (concurrent/after settled (fn [] (reset! ran true) :released))]
    (is (not (realized? chained)) "the answer is a new handle")
    (is (false? @ran) "F has not run before the handle settles")
    (deliver settled :stopped)
    (is (= :released (deref chained 2000 :timeout)))
    (is (true? @ran))))

(deftest after-answers-nil-when-the-chained-step-throws
  ;; a chained teardown is cleanup: an undelivered handle would hang its waiter
  (let [settled (promise)
        chained (concurrent/after settled (fn [] (throw (ex-info "boom" {}))))]
    (deliver settled :stopped)
    (is (nil? (deref chained 2000 :timeout)))))

(deftest await-all-spends-one-deadline-on-the-batch
  (let [a (promise)
        b (promise)
        c (promise)]
    (deliver a :a)
    (deliver c :c)
    (is (= [b] (concurrent/await-all [a b c] 0))
        "a zero budget settles only what is already delivered")
    (is (= [] (concurrent/await-all [a c] 100)))
    (deliver b :b)
    (is (= [] (concurrent/await-all [a b c] 100)) "and everything once it settles")))
