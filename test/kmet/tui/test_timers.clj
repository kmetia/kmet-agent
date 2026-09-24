(ns kmet.tui.test-timers
  "Tests for kmet.tui.timers — the loop-owned timer registry (tui.md
   §6.1). Headless: pump! is driven by hand instead of by the frame loop."
  (:require [clojure.test :as t]
            [kmet.tui.macros :as macros]
            [kmet.tui.timers :as timers]))

(t/use-fixtures :each (fn [f] (timers/cancel-all!) (f) (timers/cancel-all!)))

(t/deftest ^:slow after-fires-once-when-due
  (let [fired (atom 0)]
    (timers/after! 50 #(swap! fired inc))
    (t/is (false? (timers/pump!)) "not due yet")
    (t/is (zero? @fired))
    (Thread/sleep 60)
    (t/is (true? (timers/pump!)) "due now")
    (t/is (= 1 @fired))
    (t/testing "a one-shot is gone after firing — never a second call"
      (t/is (false? (timers/pump!)))
      (t/is (= 1 @fired)))))

(t/deftest ^:slow every-repeats-and-reschedules-from-now
  (let [fired (atom 0)
        id (timers/every! 20 #(swap! fired inc))]
    (t/is (= 1 (count (timers/scheduled))))
    (Thread/sleep 30)
    (timers/pump!)
    (t/is (= 1 @fired))
    ;; a pump BEFORE the next tick fires nothing: the repeat rescheduled
    ;; from now rather than firing a burst to catch up
    (t/is (false? (timers/pump!)))
    (t/is (= 1 @fired))
    (Thread/sleep 25)
    (timers/pump!)
    (t/is (= 2 @fired))
    (timers/cancel! id)
    (t/is (empty? (timers/scheduled)))))

(t/deftest ^:slow cancel-is-idempotent-and-stops-firing
  (let [fired (atom 0)
        id (timers/every! 10 #(swap! fired inc))]
    (timers/cancel! id)
    (t/is (nil? (timers/cancel! id)) "cancelling twice is a no-op")
    (t/is (nil? (timers/cancel! 999999)) "an unknown id is a no-op")
    (Thread/sleep 20)
    (t/is (false? (timers/pump!)))
    (t/is (zero? @fired))))

(t/deftest ^:slow cancel-all-clears-every-timer
  (timers/after! 10 identity)
  (timers/every! 10 identity)
  (t/is (= 2 (count (timers/scheduled))))
  (timers/cancel-all!)
  (t/is (empty? (timers/scheduled)))
  (Thread/sleep 15)
  (t/is (false? (timers/pump!))))

(t/deftest ^:slow a-throwing-thunk-is-swallowed-and-its-timer-survives
  ;; the loop must not die on a bad thunk, and a repeating timer keeps its
  ;; next tick (same policy as macros/schedule-frame!)
  (let [fired (atom 0)
        _ (timers/every! 10 (fn [] (swap! fired inc) (throw (ex-info "boom" {}))))]
    (Thread/sleep 15)
    (t/is (true? (timers/pump!)) "fired despite the throw")
    (t/is (= 1 @fired))
    (t/is (= 1 (count (timers/scheduled))) "still armed for the next tick")
    (Thread/sleep 15)
    (timers/pump!)
    (t/is (= 2 @fired))))

(t/deftest ^:slow a-timer-that-cancels-itself-stays-cancelled
  ;; rescheduling happens before the thunk runs, so a thunk cancelling its
  ;; own id must win — the re-arm must not resurrect it
  (let [fired (atom 0)
        id (atom nil)]
    (reset! id (timers/every! 10 (fn [] (swap! fired inc) (timers/cancel! @id))))
    (Thread/sleep 15)
    (timers/pump!)
    (t/is (= 1 @fired))
    (t/is (empty? (timers/scheduled)))
    (Thread/sleep 20)
    (t/is (false? (timers/pump!)))
    (t/is (= 1 @fired))))

(t/deftest timer-thunks-run-on-the-pumping-thread
  ;; the contract that makes widgets safe to touch from a thunk
  (let [pump-thread (atom nil)]
    (timers/after! 0 #(reset! pump-thread (Thread/currentThread)))
    (timers/pump!)
    (t/is (identical? (Thread/currentThread) @pump-thread))))

(t/deftest a-thunk-can-schedule-a-frame
  ;; the documented way a timer asks for a repaint
  (let [frames (atom 0)]
    (macros/set-frame-hook! #(swap! frames inc))
    (try
      (timers/after! 0 macros/schedule-frame!)
      (timers/pump!)
      (t/is (= 1 @frames) "schedule-frame! was wired to the hook from a thunk")
      (finally (macros/set-frame-hook! nil)))))

(t/deftest next-due-ms-tracks-the-earliest-armed-timer
  ;; the render loop caps its idle park by this, so a parked loop fires
  ;; timers on time (kmet.tui.core/idle-park-ms)
  (t/is (nil? (timers/next-due-ms)) "nothing armed")
  (timers/after! 500 identity)
  (timers/every! 50 identity)
  (let [due (timers/next-due-ms)]
    (t/is (integer? due))
    (t/is (<= 0 due 50) "the earliest due time wins"))
  (timers/after! 0 identity)
  (t/is (zero? (timers/next-due-ms)) "a timer due now reports 0")
  (timers/cancel-all!)
  (t/is (nil? (timers/next-due-ms)) "cancel-all clears it"))
