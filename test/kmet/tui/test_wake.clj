(ns kmet.tui.test-wake
  "Tests for kmet.tui.wake — the render loop's park/wake primitive. The
   contract is host-independent: park! blocks until wake! or the timeout,
   and the recheck consumes a wakeup that races the park instead of losing
   it (jolt has no Object.wait — tracked in jolt-bugs.md)."
  (:require [clojure.test :as t]
            [kmet.tui.wake :as wake]))

(t/deftest wake-releases-a-parked-thread
  (let [w (wake/make-waker)
        parked? (atom false)
        fired (atom false)
        parker (Thread. (fn []
                          (wake/park! w (fn [] (reset! parked? true) false) 10000)
                          (reset! fired true)))]
    (.start parker)
    (let [deadline (+ (System/currentTimeMillis) 2000)]
      (while (and (not @parked?) (< (System/currentTimeMillis) deadline))
        (Thread/sleep 5)))
    (t/is (true? @parked?) "parker reached park!")
    ;; wake until it takes: a monitor wake landing before the wait begins is
    ;; dropped by design (the recheck is the production guard — the next
    ;; test), so this pins the wake path itself with retries instead of a
    ;; fixed sleep racing the parker's last two instructions
    (let [deadline (+ (System/currentTimeMillis) 2000)]
      (while (and (not @fired) (< (System/currentTimeMillis) deadline))
        (wake/wake! w)
        (.join parker 25)))
    (t/is (true? @fired) "wake! released the parked thread")))

(t/deftest park-times-out
  (let [w (wake/make-waker)
        t0 (System/currentTimeMillis)]
    (wake/park! w (fn [] false) 50)
    (let [elapsed (- (System/currentTimeMillis) t0)]
      (t/is (>= elapsed 40) "parked for roughly the timeout")
      (t/is (< elapsed 3000) "returned in bounded time"))))

(t/deftest park-skips-waiting-when-the-recheck-is-truthy
  ;; the race contract: a wakeup that arrives before or while the parker
  ;; enters park! is observed by the recheck, so the parker never blocks
  ;; for the full timeout on work it already has
  (let [w (wake/make-waker)
        t0 (System/currentTimeMillis)]
    (wake/park! w (fn [] true) 5000)
    (t/is (< (- (System/currentTimeMillis) t0) 1000)
          "a truthy recheck returns without parking")))

(t/deftest wake-and-flag-release-the-parker
  ;; the exact pattern kmet.tui.core uses: set the request flag, then wake.
  ;; parker checks the flag in the recheck, so the release does not depend
  ;; on the wakeup being remembered
  (let [w (wake/make-waker)
        requested? (atom false)
        seen (atom nil)
        parker (Thread. (fn []
                          (wake/park! w #(boolean @requested?) 5000)
                          (reset! seen @requested?)))]
    (.start parker)
    (Thread/sleep 50)
    (reset! requested? true)
    (wake/wake! w)
    (.join parker 2000)
    (t/is (true? @seen) "the parker saw the request and returned")))

(t/deftest wake-with-nobody-parked-is-harmless
  (let [w (wake/make-waker)]
    (dotimes [_ 5]
      (wake/wake! w))
    (t/is true)))
