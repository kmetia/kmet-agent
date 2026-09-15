(ns kmet.tui.components.test-alt-screen-flash
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.timers :as timers]
            [kmet.tui.utils :as u]
            [kmet.tui.components.alt-screen-flash :as asf]))

;; the timer registry is process-global: isolate each case so an armed
;; timer another namespace left behind can't make pump! report true here
(t/use-fixtures :each (fn [f] (timers/cancel-all!) (f) (timers/cancel-all!)))

(defn- plain [lines]
  (mapv #(u/strip-ansi-codes %) lines))

(t/deftest test-create
  (let [c (asf/make-alt-screen-flash (fn []))]
    (t/is (satisfies? core/IComponent c))
    (t/is (= [] (core/render c 10)) "no flashes initially")))

(t/deftest test-flash-renders-inverse-line
  (let [c (asf/make-alt-screen-flash (fn []))]
    (asf/alt-screen-flash! c "Copied!")
    (let [lines (core/render c 20)]
      (t/is (= 1 (count lines)))
      (t/is (re-find #"\u001b\[7m" (first lines)) "inverse video")
      (t/is (re-find #"Copied!" (first lines)))))
  ;; flashes expire after their duration — the loop pumps; headless we
  ;; pump by hand, so the assertion is deterministic (no settle window)
  (let [c (asf/make-alt-screen-flash (fn []))]
    (asf/alt-screen-flash! c "x" :duration-ms 20)
    (t/is (= 1 (count (core/render c 20))))
    (t/is (false? (timers/pump!)) "not due yet")
    (t/is (= 1 (count (core/render c 20))) "still visible before the duration elapses")
    (Thread/sleep 30)
    (timers/pump!)
    (t/is (= [] (core/render c 20)) "expired after duration")))

(t/deftest test-multiple-flashes-stack
  (let [c (asf/make-alt-screen-flash (fn []))]
    (asf/alt-screen-flash! c "one" :duration-ms 60000)
    (asf/alt-screen-flash! c "two" :duration-ms 60000)
    (let [lines (core/render c 20)]
      (t/is (= 2 (count lines)))
      (t/is (re-find #"one" (first lines)))
      (t/is (re-find #"two" (second lines))))))

(t/deftest test-flash-truncates
  (let [c (asf/make-alt-screen-flash (fn []))]
    (asf/alt-screen-flash! c "a very long message that cannot fit" :duration-ms 60000)
    (let [lines (plain (core/render c 12))]
      (t/is (<= (u/visible-width (first lines)) 12)))))

(t/deftest test-dispose
  (let [c (asf/make-alt-screen-flash (fn []))]
    (asf/alt-screen-flash! c "x" :duration-ms 60000)
    (let [id (:timer-id (first @(:entries-atom c)))]
      (asf/alt-screen-flash-dispose! c)
      (t/is (= [] (core/render c 20)) "dispose clears pending flashes")
      (t/is (not (contains? (timers/scheduled) id))
            "…and cancels the pending expiry — no zombie render-request"))))

(t/deftest test-request-render-called
  (let [renders (atom 0)
        c (asf/make-alt-screen-flash #(swap! renders inc))]
    (asf/alt-screen-flash! c "x" :duration-ms 20)
    (t/is (pos? @renders) "flash triggers a render request")
    (let [n @renders]
      (Thread/sleep 30)
      (timers/pump!)
      (t/is (> @renders n) "expiry triggers another render request"))))

(t/deftest test-set-request-render
  ;; the callback swaps in place — entries and timers survive (the hiccup
  ;; :apply path patches a changed :request-render through this)
  (let [a (atom 0)
        b (atom 0)
        c (asf/make-alt-screen-flash #(swap! a inc))]
    (asf/alt-screen-flash-set-request-render! c #(swap! b inc))
    (asf/alt-screen-flash! c "x" :duration-ms 60000)
    (t/is (zero? @a) "the replaced callback is no longer called")
    (t/is (pos? @b) "the new callback is called")
    (t/is (= 1 (count (core/render c 20))))
    ;; nil is a legal callback — a flash with nowhere to repaint still records
    (asf/alt-screen-flash-set-request-render! c nil)
    (asf/alt-screen-flash! c "y" :duration-ms 60000)
    (t/is (= 2 (count (core/render c 20))))))
