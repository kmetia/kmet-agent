(ns kmet.tui.components.container
  "Container component - groups child components vertically.
   Port of @earendil-works/pi-tui Container. Like pi, a Container does not
   receive input: the TUI dispatches keys to the focused leaf component
   only (pi: focusedComponent?.handleInput)."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.macros :refer [defcomponent]]))

(defcomponent Container nil [children lines-cache]
  (render [_this width]
    ;; Every child renders once per pass (its own cache decides whether that
    ;; is cheap); the concatenation is reused while the children return the
    ;; objects they returned last pass, so an unchanged container costs a
    ;; pass over the children and no line copying. LINES-CACHE keeps the
    ;; previous pass (protocols/concat-cached): the children's vectors plus
    ;; the flat result, both already referenced by the children's caches.
    (let [{:keys [lines cache]}
          (protocols/concat-cached
           (when lines-cache @lines-cache)
           [width]
           (mapv (fn [c] (protocols/render c width)) @children))]
      ;; a hit returns the stored memo itself — writing it back is a no-op
      (when (and lines-cache (not (identical? cache @lines-cache)))
        (reset! lines-cache cache))
      lines))
  ;; pi: no handleInput on containers — input routes via TUI focus
  (invalidate [_this] (doseq [c @children] (protocols/invalidate c)))
  (dispose [_this] (doseq [c @children] (protocols/dispose c))))

(defn make-container
  ([] (map->Container {:children (atom []) :lines-cache (atom nil)}))
  ([children] (map->Container {:children (atom (vec children))
                               :lines-cache (atom nil)})))

(defn- drop-lines-cache!
  "Forget the render memo, so the dropped children's line vectors are not
   retained until the next render (their components are gone)."
  [c]
  (when-let [cache (:lines-cache c)]
    (reset! cache nil)))

(defn container-add-child [c child] (swap! (:children c) conj child))
(defn container-remove-child [c child]
  (swap! (:children c) (fn [v] (vec (remove #(identical? % child) v)))))
(defn container-set-children!
  "Replace all children at once (used for ordered insertion)."
  [c children]
  (reset! (:children c) (vec children)))

(defn container-replace-children!
  "Replace all children, disposing the ones dropped (children-first
   lifecycle, tui.md §5.1) — a child removed from a container without
   disposal keeps its track! watches alive forever (zombie watchers). Use
   when the previous children are discarded for good; use
   container-set-children! when they are being moved or reused.
   Identity-based: a child re-added from the old list is left alone, a
   dropped instance listed twice is disposed once, and nil entries are
   skipped (they carry nothing to release)."
  [c children]
  (let [children (vec children)
        dropped (reduce (fn [acc old]
                          (if (or (nil? old)
                                  (some #(identical? % old) children)
                                  (some #(identical? % old) acc))
                            acc
                            (conj acc old)))
                        []
                        @(:children c))]
    (reset! (:children c) children)
    (drop-lines-cache! c)
    (doseq [old dropped]
      (protocols/dispose old))))

(defn container-clear [c]
  (reset! (:children c) [])
  (drop-lines-cache! c))
