(ns kmet.tui.components.v-stack
  "VStack — vertical stack layout component (pi: components/v-stack.ts).
   Renders children top-to-bottom, each at its natural height and the full
   viewport width, with an optional gap of blank lines between them.
   Children may be bare components or stack entry maps
   {:component c :visible (fn [viewport] bool) :basis/:grow/:shrink
   :min-size/:max-size} — the sizing options are accepted for parity with
   pi; the height distribution itself is done by the host layout (the
   render loop's document compose). Like pi, a VStack does not
   receive input — the TUI dispatches keys to the focused leaf component
   only."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.components.stack :as stack]))

(defn- render-entries
  "Render VISIBLE children at WIDTH, inserting GAP blank lines between them."
  [entries width gap]
  (let [viewport {:width (max 1 width) :height Long/MAX_VALUE}
        safe-width (max 1 width)
        entries (stack/visible-stack-entries entries viewport)]
    (loop [entries entries, first? true, acc []]
      (if-let [e (first entries)]
        (recur (rest entries) false
               (into acc
                     (concat (when (not first?) (repeat gap ""))
                             (protocols/render (stack/entry-component e) safe-width))))
        acc))))

(defcomponent VStack nil [entries-atom gap-atom]
  (render [_this width]
    (render-entries @entries-atom width @gap-atom))
  (invalidate [this]
    (stack/invalidate-children! this))
  (dispose [this]
    (stack/dispose-children! this)))

;; ─── Construction & API ────────────────────────────────────────────────────

(defn make-v-stack
  "Create a VStack. CHILDREN: components or stack entry maps. Options:
     :gap — blank lines between children (default 0, clamped to ≥ 0 like
            pi's normalizeSize)"
  [children & {:keys [gap] :or {gap 0}}]
  (map->VStack {:entries-atom (atom (vec children))
                :gap-atom (atom (stack/normalize-gap gap))}))

(defn v-stack-add-child! [vs child]
  (stack/add-child! vs child))

(defn v-stack-remove-child! [vs child]
  (stack/remove-child! vs child))

(defn v-stack-clear! [vs]
  (stack/clear-children! vs))

(defn v-stack-set-gap! [vs gap]
  (stack/set-gap! vs gap))
