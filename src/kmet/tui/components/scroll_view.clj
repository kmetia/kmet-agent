(ns kmet.tui.components.scroll-view
  "ScrollView — a bounded viewport over a single child component.
   Port of pi's ScrollView (packages/tui/src/components/scroll-view.ts)
   with the scrollbar painting from layout.ts's paintScrollbar. Self-
   contained library component: callers render the child's full content
   (render), feed it to update-layout! with the desired viewport height,
   and window it through render-window. Not used by the interactive layout
   (the main screen scrolls natively); exercised by its own unit tests."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.timers :as timers]
            [kmet.tui.utils :as u]))

(defprotocol IScrollView
  (update-layout! [this content-height viewport-height request-render])
  (get-content-width [this width])
  (render-window [this full-lines])
  (scroll-by! [this lines])
  (scroll-to! [this scroll-top])
  (scroll-to-start! [this])
  (scroll-to-end! [this])
  (scroll-top [this])
  (follows-end? [this])
  (viewport-height [this])
  (set-scrollbar! [this mode])
  (set-scrollbar-active! [this active?])
  (is-scrollbar-visible? [this]))

(def default-scrollbar-style
  "Pi default: bright-black background (\\u001b[100m ... \\u001b[49m) — the
   :scrollbar-style default of make-scroll-view, public so the hiccup
   :scroll-view tag can apply it explicitly."
  (fn [text] (str "\u001b[100m" text "\u001b[49m")))

(defcomponent ScrollView nil [child-atom follow-end?-atom primary?-atom overscroll-atom
                              scrollbar-atom scrollbar-style-atom scrollbar-hide-delay-ms-atom
                              last-width-atom scroll-top-atom content-height-atom viewport-height-atom
                              following-end-atom request-render-fn-atom
                              transient-scrollbar-visible-atom scrollbar-active-atom
                              scrollbar-hide-timer-id-atom]
  (render [this width]
    ;; Render the child's FULL content — the caller windows it via
    ;; update-layout! + render-window (render itself never clips).
    (if-let [child @child-atom]
      (protocols/render child (get-content-width this width))
      []))
  (invalidate [_this]
    (when-let [child @child-atom]
      (protocols/invalidate child)))
  (dispose [this]
    ;; cancel our own transient-scrollbar timer BEFORE delegating — a
    ;; disposed scroll-view must not fire a zombie render-request when the
    ;; pending hide-timer wakes (timers/cancel! is idempotent)
    (timers/cancel-atom! (:scrollbar-hide-timer-id-atom this))
    (when-let [child @child-atom]
      (protocols/dispose child))))

;; ─── Scrollbar state (pi: markScrollbarActivity / hideTransientScrollbar) ──

(defn- mark-scrollbar-activity!
  [this]
  (when (and (= :auto @(:scrollbar-atom this))
             (> @(:content-height-atom this) @(:viewport-height-atom this)))
    (reset! (:transient-scrollbar-visible-atom this) true)
    ;; a fresh activity tick re-arms the debounce: cancel the pending
    ;; hide, then arm one (§6.1 — the loop fires it on its next pump)
    (when-let [id @(:scrollbar-hide-timer-id-atom this)]
      (timers/cancel! id))
    (if @(:scrollbar-active-atom this)
      (reset! (:scrollbar-hide-timer-id-atom this) nil)
      (let [delay @(:scrollbar-hide-delay-ms-atom this)]
        (reset! (:scrollbar-hide-timer-id-atom this)
                (timers/after! delay
                               (fn []
                                 (reset! (:transient-scrollbar-visible-atom this) false)
                                 (when-let [f @(:request-render-fn-atom this)] (f)))))))))

(defn- hide-transient-scrollbar!
  [this]
  (reset! (:transient-scrollbar-visible-atom this) false)
  (timers/cancel-atom! (:scrollbar-hide-timer-id-atom this)))

;; ─── Scrollbar geometry (pi: getScrollbarGeometry, window-local rows) ─────

(defn- scrollbar-thumb-rows
  [this]
  (let [track @(:viewport-height-atom this)
        content @(:content-height-atom this)]
    (when (and (pos? track) (pos? content))
      (let [min-thumb (min 2 track)
            thumb (max min-thumb (min track (long (Math/round (double (/ (* track track) content))))))
            max-scroll (max 0 (- content track))
            max-thumb-top (- track thumb)
            thumb-offset (if (zero? max-scroll)
                           0
                           (long (Math/round (double (* (/ @(:scroll-top-atom this) max-scroll) max-thumb-top)))))]
        (range thumb-offset (min track (+ thumb-offset thumb)))))))

(defn scrollbar-geometry
  "Viewport-local scrollbar geometry (pi: getScrollbarGeometry): the thumb
   row range within the viewport, the scrollbar column (last column of the
   viewport width), and the track/thumb heights. Returns nil when the
   scrollbar is not visible or the viewport is empty."
  [this width]
  (let [track @(:viewport-height-atom this)
        content @(:content-height-atom this)
        mode @(:scrollbar-atom this)]
    (when (and (pos? track) (pos? content) (is-scrollbar-visible? this))
      (let [min-thumb (min 2 track)
            thumb (max min-thumb (min track (long (Math/round (double (/ (* track track) content))))))
            max-scroll (max 0 (- content track))
            max-thumb-top (- track thumb)
            thumb-offset (if (zero? max-scroll)
                           0
                           (long (Math/round (double (* (/ @(:scroll-top-atom this) max-scroll) max-thumb-top)))))
            thumb-rows (range thumb-offset (min track (+ thumb-offset thumb)))]
        {:column (max 0 (dec (int width)))
         :track-height track
         :thumb-height thumb
         :thumb-rows (set thumb-rows)
         :thumb-top thumb-offset
         :max-scroll-top max-scroll
         :mode mode}))))

(defn- apply-scrollbar
  "Paint the scrollbar column onto WINDOW (pi: paintScrollbar). :always mode
   reserves a column (child rendered at width-1); :auto overlays the last
   column while transiently visible."
  [this window]
  (let [width @(:last-width-atom this)
        mode @(:scrollbar-atom this)
        thumb-rows (set (scrollbar-thumb-rows this))
        cell (@(:scrollbar-style-atom this) " ")]
    (if (= mode :always)
      (mapv (fn [i line] (str line (if (contains? thumb-rows i) cell " ")))
            (range) window)
      (mapv (fn [i line]
              (if (contains? thumb-rows i)
                (str (u/truncate-to-width line (max 0 (dec width))) cell)
                line))
            (range) window))))

;; ─── IScrollView ───────────────────────────────────────────────────────────

(extend-type ScrollView
  IScrollView
  (update-layout! [this content-height viewport-height request-render]
    (let [content-height (max 0 (int content-height))
          viewport-height (max 0 (int viewport-height))
          max-scroll (max 0 (- content-height viewport-height))]
      (reset! (:content-height-atom this) content-height)
      (reset! (:viewport-height-atom this) viewport-height)
      (reset! (:request-render-fn-atom this) request-render)
      (if @(:following-end-atom this)
        (reset! (:scroll-top-atom this) max-scroll)
        (reset! (:scroll-top-atom this) (max 0 (min @(:scroll-top-atom this) max-scroll))))
      ;; Pi: re-engage following when the viewport is already at the bottom.
      (when (and @(:follow-end?-atom this) (= @(:scroll-top-atom this) max-scroll))
        (reset! (:following-end-atom this) true))
      (when (<= content-height viewport-height)
        (hide-transient-scrollbar! this))
      nil))

  (get-content-width [this width]
    (let [width (int width)]
      (reset! (:last-width-atom this) width)
      (if (and (= :always @(:scrollbar-atom this)) (> width 1))
        (dec width)
        width)))

  (render-window [this full-lines]
    (let [viewport-height @(:viewport-height-atom this)]
      (if (zero? viewport-height)
        []
        (let [content-height (count full-lines)
              max-scroll (max 0 (- content-height viewport-height))
              scroll-top (if @(:following-end-atom this)
                           max-scroll
                           (max 0 (min @(:scroll-top-atom this) max-scroll)))]
          (reset! (:scroll-top-atom this) scroll-top)
          (let [window (if (<= content-height viewport-height)
                         ;; Content fits: top-align it and pad the rest so the
                         ;; layout below (the dock) stays pinned to the bottom.
                         (into (vec full-lines) (repeat (- viewport-height content-height) ""))
                         ;; Content overflows: the window at scroll-top, padded
                         ;; to the viewport height (safe on a partial last row).
                         (let [end (+ scroll-top viewport-height)
                               shown (min end content-height)]
                           (into (subvec full-lines scroll-top shown)
                                 (repeat (- end shown) ""))))]
            (if (is-scrollbar-visible? this)
              (apply-scrollbar this window)
              window))))))

  (scroll-by! [this lines]
    (let [requested (int lines)]
      (if (zero? requested)
        0
        (let [max-scroll (max 0 (- @(:content-height-atom this) @(:viewport-height-atom this)))
              start (if @(:following-end-atom this) max-scroll @(:scroll-top-atom this))
              next (max 0 (min max-scroll (+ start requested)))
              moved (- next start)]
          (reset! (:scroll-top-atom this) next)
          (reset! (:following-end-atom this) (and @(:follow-end?-atom this) (= next max-scroll)))
          (when-not (zero? moved)
            (mark-scrollbar-activity! this)
            (when-let [f @(:request-render-fn-atom this)] (f)))
          ;; Pi: return the unscrolled remainder (overscroll chaining).
          (- requested moved)))))

  (scroll-to! [this scroll-top]
    (let [max-scroll (max 0 (- @(:content-height-atom this) @(:viewport-height-atom this)))
          next (max 0 (min max-scroll (int scroll-top)))]
      (when-not (= next @(:scroll-top-atom this))
        (reset! (:scroll-top-atom this) next)
        (reset! (:following-end-atom this) (and @(:follow-end?-atom this) (= next max-scroll)))
        (mark-scrollbar-activity! this)
        (when-let [f @(:request-render-fn-atom this)] (f)))
      nil))

  (scroll-to-start! [this]
    (let [following (and @(:follow-end?-atom this) (<= @(:content-height-atom this) @(:viewport-height-atom this)))
          changed (or (not= 0 @(:scroll-top-atom this))
                      (not= following @(:following-end-atom this)))]
      (reset! (:scroll-top-atom this) 0)
      (reset! (:following-end-atom this) following)
      (when changed
        (mark-scrollbar-activity! this)
        (when-let [f @(:request-render-fn-atom this)] (f)))
      nil))

  (scroll-to-end! [this]
    (let [next (max 0 (- @(:content-height-atom this) @(:viewport-height-atom this)))
          changed (or (not= next @(:scroll-top-atom this))
                      (not= @(:follow-end?-atom this) @(:following-end-atom this)))]
      (reset! (:scroll-top-atom this) next)
      (reset! (:following-end-atom this) @(:follow-end?-atom this))
      (when changed
        (mark-scrollbar-activity! this)
        (when-let [f @(:request-render-fn-atom this)] (f)))
      nil))

  (scroll-top [this] @(:scroll-top-atom this))

  (follows-end? [this] @(:following-end-atom this))

  (viewport-height [this] @(:viewport-height-atom this))

  (set-scrollbar! [this mode]
    (when-not (= mode @(:scrollbar-atom this))
      (reset! (:scrollbar-atom this) mode)
      (if (not= mode :auto)
        (hide-transient-scrollbar! this)
        (when @(:scrollbar-active-atom this)
          (mark-scrollbar-activity! this)))
      (when-let [f @(:request-render-fn-atom this)] (f))
      nil))

  (set-scrollbar-active! [this active?]
    (when-not (= active? @(:scrollbar-active-atom this))
      (reset! (:scrollbar-active-atom this) active?)
      (mark-scrollbar-activity! this)
      nil))

  (is-scrollbar-visible? [this]
    (if (= :always @(:scrollbar-atom this))
      (pos? @(:viewport-height-atom this))
      (and (= :auto @(:scrollbar-atom this))
           (> @(:content-height-atom this) @(:viewport-height-atom this))
           @(:transient-scrollbar-visible-atom this)))))

;; ─── Construction (pi: ScrollView constructor) ─────────────────────────────

(defn make-scroll-view
  "Create a ScrollView wrapping CHILD.
   Options (pi: ScrollViewOptions):
     :follow-end             — true pins the viewport to the newest content
                               (pi: follow \"end\"; default true here, pi defaults to \"none\")
     :primary                — mark as the primary scroll view (default false)
     :overscroll             — :chain | :contain (default :chain)
     :scrollbar              — :hidden | :auto | :always (default :hidden)
     :scrollbar-style        — (fn [text]) thumb styling (default bright-black bg)
     :scrollbar-hide-delay-ms — transient scrollbar lifetime (default 1000)"
  [child & {:keys [follow-end primary overscroll scrollbar scrollbar-style scrollbar-hide-delay-ms]
            :or {follow-end true primary false overscroll :chain
                 scrollbar :hidden scrollbar-hide-delay-ms 1000}}]
  (map->ScrollView {:child-atom (atom child)
                    :follow-end?-atom (atom follow-end)
                    :primary?-atom (atom primary)
                    :overscroll-atom (atom overscroll)
                    :scrollbar-atom (atom scrollbar)
                    :scrollbar-style-atom (atom (or scrollbar-style default-scrollbar-style))
                    :scrollbar-hide-delay-ms-atom (atom (max 0 (int scrollbar-hide-delay-ms)))
                    :last-width-atom (atom 0)
                    :scroll-top-atom (atom 0)
                    :content-height-atom (atom 0)
                    :viewport-height-atom (atom 0)
                    :following-end-atom (atom follow-end)
                    :request-render-fn-atom (atom nil)
                    :transient-scrollbar-visible-atom (atom false)
                    :scrollbar-active-atom (atom false)
                    :scrollbar-hide-timer-id-atom (atom nil)}))

;; ─── Live prop setters (the hiccup :scroll-view apply path, tui.md §2.3) ───
;; Structural props are atoms, so a changed prop patches the live view
;; instead of being ignored. Each setter coerces like construction.

(defn scroll-view-set-follow-end!
  "Set the end-following behavior, keeping the invariant following-end ⇒
   follow-end (pi: followEnd is immutable there; a declarative patch can
   flip it mid-follow): turning following off stops it now, turning it on
   re-engages only when the viewport is already at the end."
  [sv follow-end]
  (let [follow-end (boolean follow-end)
        max-scroll (max 0 (- @(:content-height-atom sv)
                             @(:viewport-height-atom sv)))]
    (reset! (:follow-end?-atom sv) follow-end)
    (reset! (:following-end-atom sv)
            (and follow-end (= max-scroll @(:scroll-top-atom sv))))))

(defn scroll-view-set-primary!
  [sv primary]
  (reset! (:primary?-atom sv) (boolean primary)))

(defn scroll-view-set-overscroll!
  "Set the overscroll policy (:chain | :contain)."
  [sv overscroll]
  (reset! (:overscroll-atom sv) (or overscroll :chain)))

(defn scroll-view-set-scrollbar-style! [sv style]
  (reset! (:scrollbar-style-atom sv) (or style default-scrollbar-style)))

(defn scroll-view-set-scrollbar-hide-delay-ms! [sv ms]
  (reset! (:scrollbar-hide-delay-ms-atom sv) (max 0 (int ms))))
