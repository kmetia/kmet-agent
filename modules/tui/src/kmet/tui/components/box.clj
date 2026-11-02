(ns kmet.tui.components.box
  "Box component - a container that applies padding and background to all children.
   Port of @earendil-works/pi-tui Box. Like pi, a Box does not receive input:
   the TUI dispatches keys to the focused leaf component only."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.utils :as u]))

;; ─── Internal helpers (defined before record to be visible in method bodies) ─

(defn- apply-bg [f line width]
  (let [vis (u/visible-width line)
        pad (max 0 (- width vis))
        padded (str line (apply str (repeat pad \space)))]
    (if f (f padded) padded)))

(defn- box-lines
  "The box composition of PARTS: PADDING-Y blank rows, then every child line
   with LEFT-PAD in front, all padded/colored to WIDTH through BG-FN (nil
   leaves the padding plain)."
  [parts left-pad bg-fn padding-y width]
  (let [blank (apply-bg bg-fn "" width)
        pad (repeat padding-y blank)
        content (mapcat (fn [part]
                          (map (fn [line] (apply-bg bg-fn (str left-pad line) width))
                               part))
                        parts)]
    (into [] (concat pad content pad))))

;; ─── Box record ─────────────────────────────────────────────────────────────

(defcomponent Box nil [children padding-x-atom padding-y-atom bg-fn cache]
  (render [_this width]
    (let [children @children]
      (if (empty? children)
        (do (reset! cache nil) [])
        (let [padding-x @padding-x-atom
              padding-y @padding-y-atom
              content-width (max 1 (- width (* 2 padding-x)))
              left-pad (apply str (repeat padding-x \space))
              bg-fn-val (when-let [f @bg-fn] f)
              bg-sample (when bg-fn-val (bg-fn-val "test"))
              r (protocols/concat-cached
                 @cache
                 ;; every input of the composition: the geometry and the
                 ;; background sample (the padding setters no longer need to
                 ;; drop the memo — a changed value is a changed key)
                 [width content-width padding-x padding-y bg-sample]
                 (mapv (fn [c] (protocols/render c content-width)) children)
                 (fn [parts] (box-lines parts left-pad bg-fn-val padding-y width)))]
          (reset! cache (:cache r))
          (:lines r)))))
  (invalidate [this]
    (reset! (:cache this) nil)
    (doseq [c @children] (protocols/invalidate c)))
  (dispose [_this]
    (doseq [c @children] (protocols/dispose c))))

;; ─── Constructors & helpers ─────────────────────────────────────────────────

(defn make-box
  ([] (make-box 1 1 nil))
  ([padding-x padding-y] (make-box padding-x padding-y nil))
  ([padding-x padding-y bg-fn]
   (map->Box {:children (atom [])
              :padding-x-atom (atom padding-x)
              :padding-y-atom (atom padding-y)
              :bg-fn (atom bg-fn) :cache (atom nil)})))

(defn box-add-child [box child]
  (swap! (:children box) conj child)
  (reset! (:cache box) nil))

(defn box-remove-child [box child]
  (swap! (:children box) (fn [v] (vec (remove #(identical? % child) v))))
  (reset! (:cache box) nil))

(defn box-clear [box]
  (reset! (:children box) [])
  (reset! (:cache box) nil))

(defn box-set-bg-fn [box bg-fn]
  (reset! (:bg-fn box) bg-fn)
  (reset! (:cache box) nil))

;; Padding and background are render inputs and live, so the hiccup :box tag
;; can patch a changed :padding-x/:padding-y prop in place (§2.3). Both are
;; part of the composition's cache key, so a change is a miss on its own; the
;; setters still drop the memo, like every other mutation on the box.
(defn box-set-padding-x!
  "No-op when unchanged, so a caller may sync the padding every pass (the
   chat history's output pad follows one shared atom) without dropping the
   memoized composition each frame."
  [box n]
  (when (not= n @(:padding-x-atom box))
    (reset! (:padding-x-atom box) n)
    (reset! (:cache box) nil)))

(defn box-set-padding-y!
  "No-op when unchanged — see box-set-padding-x!."
  [box n]
  (when (not= n @(:padding-y-atom box))
    (reset! (:padding-y-atom box) n)
    (reset! (:cache box) nil)))
