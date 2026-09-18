(ns kmet.tui.components.box
  "Box component - a container that applies padding and background to all children.
   Port of @earendil-works/pi-tui Box. Like pi, a Box does not receive input:
   the TUI dispatches keys to the focused leaf component only."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.utils :as u]))

;; ─── Internal helpers (defined before record to be visible in method bodies) ─

(defn- apply-bg [{:keys [bg-fn]} line width]
  (let [vis (u/visible-width line)
        pad (max 0 (- width vis))
        padded (str line (apply str (repeat pad \space)))]
    (if-let [f @bg-fn] (f padded) padded)))

;; ─── Box record ─────────────────────────────────────────────────────────────

(defcomponent Box nil [children padding-x-atom padding-y-atom bg-fn cache]
  (render [this width]
    (if (empty? @children)
      []
      (let [padding-x @padding-x-atom
            padding-y @padding-y-atom
            content-width (max 1 (- width (* 2 padding-x)))
            left-pad (apply str (repeat padding-x \space))
            child-lines (mapcat (fn [c]
                                  (map #(str left-pad %) (protocols/render c content-width)))
                                @children)
            bg-fn-val (when-let [f @bg-fn] f)
            bg-sample (when bg-fn-val (bg-fn-val "test"))
            cached @cache]
        (if (and cached
                 (= (:width cached) width)
                 (= (:bg-sample cached) bg-sample)
                 (= (:child-lines cached) child-lines))
          (:lines cached)
          (let [result (into []
                             (concat
                              (repeat padding-y (apply-bg this "" width))
                              (map #(apply-bg this % width) child-lines)
                              (repeat padding-y (apply-bg this "" width))))]
            (reset! cache {:width width :bg-sample bg-sample
                           :child-lines child-lines :lines result})
            result)))))
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

;; Padding is render input (the cache key does not include it — the setters
;; reset the cache), live so the hiccup :box tag can patch a changed
;; :padding-x/:padding-y prop in place instead of ignoring it (§2.3).
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
