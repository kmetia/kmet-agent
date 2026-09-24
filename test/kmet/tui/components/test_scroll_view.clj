(ns kmet.tui.components.test-scroll-view
  "Tests for the ScrollView: follow-end windowing, scroll clamping, the
   following-end re-engagement rule, and render gating (no-op scrolls don't
   request a render) — matching pi's scroll-view.ts semantics."
  (:require [clojure.test :as t]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.timers :as timers]
            [kmet.tui.components.scroll-view :as sv]))

;; the timer registry is process-global: isolate each case so an armed
;; timer another namespace left behind can't make pump! report true here
(t/use-fixtures :each (fn [f] (timers/cancel-all!) (f) (timers/cancel-all!)))

(defn- fake-child
  "A component whose render returns LINES at width."
  [lines]
  (reify
    protocols/IComponent
    (render [_this _width] lines)
    (handle-input [_this _data] nil)
    (invalidate [_this] nil)
    (dispose [_this] nil)))

(defn- make-sv
  [lines & {:keys [follow-end viewport]}]
  (let [sv (sv/make-scroll-view (fake-child lines) :follow-end follow-end)]
    (sv/update-layout! sv (count lines) viewport (fn [] nil))
    sv))

(t/deftest test-window-follows-end
  ;; Content taller than the viewport shows the LAST viewport lines.
  (let [lines (mapv #(str "line" %) (range 10))
        sv (make-sv lines :follow-end true :viewport 4)]
    (t/is (= ["line6" "line7" "line8" "line9"] (sv/render-window sv lines)))))

(t/deftest test-window-pads-when-content-fits
  ;; Content shorter than the viewport is top-aligned and padded so the
  ;; layout below stays pinned to the bottom.
  (let [lines ["a" "b"]
        sv (make-sv lines :follow-end true :viewport 4)]
    (t/is (= ["a" "b" "" ""] (sv/render-window sv lines)))))

(t/deftest test-scroll-by-clamps-and-renders
  (let [lines (mapv #(str "line" %) (range 10))
        renders (atom 0)
        sv (sv/make-scroll-view (fake-child lines) :follow-end true)
        _ (sv/update-layout! sv 10 4 #(swap! renders inc))]
    ;; scroll up from the end (maxScrollTop 6 -> 3)
    (t/is (= 0 (sv/scroll-by! sv -3)))
    (t/is (= 3 (sv/scroll-top sv)))
    (t/is (not (sv/follows-end? sv)))
    (t/is (= 1 @renders))
    ;; no-op scroll at the top does not render
    (sv/scroll-to-start! sv)
    (t/is (= 2 @renders))
    (t/is (= -1 (sv/scroll-by! sv -1)))
    (t/is (= 2 @renders))
    ;; scroll back to the end re-engages following
    (sv/scroll-by! sv 100)
    (t/is (sv/follows-end? sv))
    (t/is (= 6 (sv/scroll-top sv)))))

(t/deftest test-scroll-returns-remainder
  ;; Pi: scrollBy returns the unscrolled remainder (overscroll chaining).
  (let [lines (mapv #(str "line" %) (range 10))
        sv (make-sv lines :follow-end true :viewport 4)]
    ;; only 6 lines scrollable above the end
    (t/is (= -4 (sv/scroll-by! sv -10)))
    (t/is (= 0 (sv/scroll-by! sv 3)))))

(t/deftest test-scroll-to-end-clamps-and-re-engages
  (let [lines (mapv #(str "line" %) (range 10))
        sv (make-sv lines :follow-end true :viewport 4)]
    (sv/scroll-to-start! sv)
    (t/is (= 0 (sv/scroll-top sv)))
    (t/is (not (sv/follows-end? sv)))
    (sv/scroll-to-end! sv)
    (t/is (= 6 (sv/scroll-top sv)))
    (t/is (sv/follows-end? sv))))

(t/deftest test-update-layout-re-engages-following
  ;; Pi: when content grows and the viewport was already at the bottom,
  ;; following re-engages so new content stays pinned.
  (let [sv (sv/make-scroll-view (fake-child ["x"]) :follow-end true)]
    (sv/update-layout! sv 10 4 (fn [] nil))
    (sv/scroll-by! sv -2)
    (t/is (not (sv/follows-end? sv)))
    (sv/scroll-by! sv 2)
    (t/is (sv/follows-end? sv))
    ;; content grows while at the bottom — stays following
    (sv/update-layout! sv 20 4 (fn [] nil))
    (t/is (sv/follows-end? sv))
    (t/is (= 16 (sv/scroll-top sv)))))

(t/deftest test-no-follow-end-keeps-position
  ;; Without follow-end, scroll position is retained on layout updates.
  (let [sv (sv/make-scroll-view (fake-child ["x"]) :follow-end false)]
    (sv/update-layout! sv 10 4 (fn [] nil))
    (sv/scroll-by! sv 3)
    (t/is (= 3 (sv/scroll-top sv)))
    (sv/update-layout! sv 20 4 (fn [] nil))
    (t/is (= 3 (sv/scroll-top sv)))))

(t/deftest test-zero-viewport-renders-empty
  (let [lines (mapv #(str "line" %) (range 10))
        sv (make-sv lines :follow-end true :viewport 0)]
    (t/is (= [] (sv/render-window sv lines)))))

(t/deftest test-get-content-width-reserves-column-for-always-scrollbar
  (let [sv (sv/make-scroll-view (fake-child ["x"]) :scrollbar :always)]
    (t/is (= 79 (sv/get-content-width sv 80)))
    (sv/set-scrollbar! sv :hidden)
    (t/is (= 80 (sv/get-content-width sv 80)))))

(t/deftest ^:slow test-auto-scrollbar-transient-visible
  ;; transient scrollbar: shown on activity, hidden when its debounce
  ;; fires. The debounce rides the loop-owned timer registry (§6.1), so
  ;; a pump stands in for the frame loop — deterministic, no wall-clock
  ;; race.
  (let [lines (mapv #(str "line" %) (range 10))
        sv (sv/make-scroll-view (fake-child lines) :scrollbar :auto
                                :scrollbar-hide-delay-ms 50)]
    (sv/update-layout! sv 10 4 (fn [] nil))
    (t/is (not (sv/is-scrollbar-visible? sv)))
    (sv/scroll-by! sv -2)
    (t/is (sv/is-scrollbar-visible? sv))
    (t/is (false? (timers/pump!)) "not due yet")
    (t/is (sv/is-scrollbar-visible? sv) "still visible before the delay elapses")
    (t/is (contains? (timers/scheduled) @(:scrollbar-hide-timer-id-atom sv))
          "the hide timer is armed")
    (Thread/sleep 60)
    (timers/pump!)
    (t/is (not (sv/is-scrollbar-visible? sv)) "hidden once the debounce fires")))

(t/deftest ^:slow test-transient-scrollbar-debounce-rearms-and-dispose-cancels
  ;; repeated activity re-arms the debounce (the component holds at most one
  ;; id), and dispose cancels it — no zombie render-request
  (let [lines (mapv #(str "line" %) (range 10))
        renders (atom 0)
        sv (sv/make-scroll-view (fake-child lines) :scrollbar :auto
                                :scrollbar-hide-delay-ms 50)]
    (sv/update-layout! sv 10 4 #(swap! renders inc))
    (let [first-id @(:scrollbar-hide-timer-id-atom sv)]
      (sv/scroll-by! sv -1)
      (sv/scroll-by! sv -1)
      (t/is (not= first-id @(:scrollbar-hide-timer-id-atom sv))
            "each activity tick re-arms under a fresh id")
      (t/is (not (contains? (timers/scheduled) first-id))
            "the superseded timer is gone — no debounce pile-up")
      (t/is (contains? (timers/scheduled) @(:scrollbar-hide-timer-id-atom sv))))
    (let [pending @(:scrollbar-hide-timer-id-atom sv)
          before @renders]
      (protocols/dispose sv)
      (t/is (not (contains? (timers/scheduled) pending))
            "dispose cancelled the pending timer")
      (t/is (nil? @(:scrollbar-hide-timer-id-atom sv)))
      (Thread/sleep 60)
      ;; other scroll-views in earlier cases may still hold timers, so the
      ;; pump's return value says nothing here — what matters is that THIS
      ;; component's callback is never called again
      (timers/pump!)
      (t/is (= before @renders) "a disposed tree is never asked to re-render"))))

(t/deftest test-scrollbar-geometry
  ;; The thumb tracks the scroll position (pi: getScrollbarGeometry).
  (let [lines (mapv #(str "line" %) (range 20))
        sv (sv/make-scroll-view (fake-child lines) :scrollbar :auto)]
    (sv/update-layout! sv 20 5 (fn [] nil))
    (t/is (nil? (sv/scrollbar-geometry sv 80))
          "no geometry while the transient scrollbar is hidden")
    (sv/scroll-by! sv -5)
    (let [g (sv/scrollbar-geometry sv 80)]
      (t/is (= 79 (:column g)) "thumb sits in the last column")
      (t/is (= 5 (:track-height g)))
      (t/is (= 15 (:max-scroll-top g)))
      (t/is (pos? (:thumb-top g)) "scrolled up → thumb moved down"))))

(t/deftest test-scroll-view-live-prop-setters
  ;; the setters behind the hiccup :scroll-view apply path (tui.md §2.3):
  ;; every structural prop is an atom, so a patch is visible immediately
  (let [sv (sv/make-scroll-view (fake-child ["x"]))]
    (t/is (true? @(:follow-end?-atom sv)) "constructor default")
    (sv/scroll-view-set-follow-end! sv false)
    (t/is (false? @(:follow-end?-atom sv)))
    (sv/scroll-view-set-primary! sv true)
    (t/is (true? @(:primary?-atom sv)))
    (sv/scroll-view-set-overscroll! sv :contain)
    (t/is (= :contain @(:overscroll-atom sv)))
    (sv/scroll-view-set-scrollbar-style! sv nil)
    (t/is (= sv/default-scrollbar-style @(:scrollbar-style-atom sv))
          "nil style falls back to the default, like construction")
    (sv/scroll-view-set-scrollbar-hide-delay-ms! sv 250)
    (t/is (= 250 @(:scrollbar-hide-delay-ms-atom sv)))
    (sv/scroll-view-set-scrollbar-hide-delay-ms! sv -5)
    (t/is (zero? @(:scrollbar-hide-delay-ms-atom sv)) "clamped like construction")))

(t/deftest test-scroll-view-follow-end-false-keeps-position
  ;; an explicit false follow-end (patched in live) must actually stop the
  ;; end-following: a layout update then keeps the scroll position
  (let [lines (mapv #(str "line" %) (range 20))
        sv (sv/make-scroll-view (fake-child lines))]
    (sv/update-layout! sv 20 5 (fn [] nil))
    (sv/scroll-to! sv 3)
    (sv/scroll-view-set-follow-end! sv false)
    (sv/update-layout! sv 25 5 (fn [] nil))
    (t/is (= 3 (sv/scroll-top sv)) "the viewport stayed put")))

(t/deftest test-scroll-view-follow-end-flip-stops-following
  ;; the invariant following-end ⇒ follow-end holds across a live patch:
  ;; following (pinned to the end) then follow-end → false stops it, so the
  ;; next layout update keeps the position instead of re-pinning; turning
  ;; it back on re-engages only when the viewport is already at the end
  (let [lines (mapv #(str "line" %) (range 20))
        sv (sv/make-scroll-view (fake-child lines))]
    (sv/update-layout! sv 20 5 (fn [] nil))
    (t/is (true? (sv/follows-end? sv)) "following the end after layout")
    (sv/scroll-view-set-follow-end! sv false)
    (t/is (false? (sv/follows-end? sv)) "the flip stopped the following")
    (sv/update-layout! sv 30 5 (fn [] nil))
    (t/is (= 15 (sv/scroll-top sv)) "not re-pinned to the new end")
    ;; at the end again → turning it on re-engages
    (sv/scroll-to-end! sv)
    (sv/scroll-view-set-follow-end! sv true)
    (t/is (true? (sv/follows-end? sv)))
    ;; not at the end → no re-engage
    (sv/scroll-to! sv 2)
    (sv/scroll-view-set-follow-end! sv false)
    (sv/scroll-view-set-follow-end! sv true)
    (t/is (false? (sv/follows-end? sv)))))
