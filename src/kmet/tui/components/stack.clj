(ns kmet.tui.components.stack
  "Stack layout support: the render-loop vertical layout (render-stack) and
   the flexbox-style sizing allocation shared by HStack/VStack (pi: stack.ts).
   render-stack renders every component at its natural height — the layout
   is a single flat document, like pi's main-screen model: when the total
   exceeds the terminal height the render loop scrolls it natively into the
   terminal scrollback, keeping the viewport pinned to the document end."
  (:require [kmet.tui.protocols :as protocols]
            [kmet.libs.host :as host]))

;; ─── Stack entries ─────────────────────────────────────────────────────────

(defn stack-entry?
  "True when CHILD is a stack entry map {:component c ...} rather than a
   bare component."
  [child]
  (and (map? child) (contains? child :component)))

(defn entry-component
  "The component of a stack child (entry map or bare component)."
  [child]
  (if (stack-entry? child) (:component child) child))

(defn visible-stack-entries
  "Entries whose :visible predicate accepts the viewport (pi: visibleStackEntries)."
  [entries viewport]
  (filterv #(if-let [f (:visible %)] (f viewport) true) entries))

;; ─── Flex sizing (pi: allocateStackSizes) ──────────────────────────────────

(defn- normalize-size
  "Coerce a size option to a non-negative integer, FALLBACK when nil/NaN/
   infinite (pi: Number.isFinite)."
  [v fallback]
  (cond
    (nil? v) fallback
    (not (number? v)) fallback
    (not (host/finite? v)) fallback
    :else (max 0 (long (Math/floor (double v))))))

(defn- clamp-size
  "Clamp SIZE within ENTRY's :min-size/:max-size bounds."
  [size entry]
  (let [mn (normalize-size (:min-size entry) 0)
        mx (max mn (normalize-size (:max-size entry) Long/MAX_VALUE))
        sz (max 0 (long (Math/floor (double size))))]
    (max mn (min mx sz))))

(defn- distribute
  "Grow/shrink SIZES among ENTRIES by AMOUNT (pi: distribute in stack.ts).
   MODE :grow gives extra space to entries with :grow > 0, weighted by grow;
   MODE :shrink takes space from entries with :shrink > 0, weighted by
   shrink × current size. REMAINING decrements within a pass — each
   candidate's proposed share is computed against the leftover, exactly
   like pi (this makes unequal splits round-robin instead of evenly)."
  [sizes entries amount mode]
  (loop [sizes (vec sizes) remaining amount]
    (if (<= remaining 0)
      sizes
      (let [candidates (->> (map-indexed vector entries)
                            (filter (fn [[idx entry]]
                                      (if (= mode :grow)
                                        (and (pos? (or (:grow entry) 0))
                                             (< (nth sizes idx)
                                                (or (:max-size entry) Long/MAX_VALUE)))
                                        (and (pos? (or (:shrink entry) 1))
                                             (> (nth sizes idx)
                                                (or (:min-size entry) 0)))))))
            total-weight (reduce (fn [sum [idx entry]]
                                   (+ sum (if (= mode :grow)
                                            (or (:grow entry) 0)
                                            (* (or (:shrink entry) 1)
                                               (max 1 (nth sizes idx))))))
                                 0 candidates)]
        (if (empty? candidates)
          sizes
          (let [state (reduce (fn [{:keys [sizes distributed remaining]} [idx entry]]
                                (if (<= remaining 0)
                                  {:sizes sizes :distributed distributed :remaining remaining}
                                  (let [weight (if (= mode :grow)
                                                 (or (:grow entry) 0)
                                                 (* (or (:shrink entry) 1)
                                                    (max 1 (nth sizes idx))))
                                        proposed (max 1 (long (Math/floor
                                                               (* (/ (double remaining) total-weight)
                                                                  weight))))
                                        capacity (if (= mode :grow)
                                                   (- (or (:max-size entry) Long/MAX_VALUE)
                                                      (nth sizes idx))
                                                   (- (nth sizes idx)
                                                      (or (:min-size entry) 0)))
                                        delta (min remaining proposed capacity)]
                                    (if (<= delta 0)
                                      {:sizes sizes :distributed distributed :remaining remaining}
                                      {:sizes (assoc sizes idx
                                                     (+ (nth sizes idx)
                                                        (if (= mode :grow) delta (- delta))))
                                       :distributed (+ distributed delta)
                                       :remaining (- remaining delta)}))))
                              {:sizes sizes :distributed 0 :remaining remaining}
                              candidates)
                distributed (:distributed state)]
            (if (zero? distributed)
              sizes
              (recur (:sizes state) (:remaining state)))))))))

(defn allocate-stack-sizes
  "Allocate widths for HStack children (pi: allocateStackSizes).
   ENTRIES are stack entry maps, INTRINSIC-SIZES their natural widths.
   With AVAILABLE-SIZE nil the clamped intrinsic sizes are returned;
   otherwise the total is grown/shrunk to fit content-size (available minus
   GAP spaces between entries). :grow/:shrink are normalized like pi's
   addChild (floored, clamped to ≥ 0)."
  [entries intrinsic-sizes available-size gap]
  (let [;; pi normalizes grow/shrink/min/max at addChild: normalizeSize
        ;; floors and clamps to ≥ 0 (a fractional grow 1.5 → 1; shrink
        ;; 0.5 → 0), so distribute's weights and capacities stay integral
        entries (mapv (fn [entry]
                        (cond-> entry
                          (contains? entry :grow) (assoc :grow (normalize-size (:grow entry) 0))
                          (contains? entry :shrink) (assoc :shrink (normalize-size (:shrink entry) 1))
                          (contains? entry :min-size) (assoc :min-size (normalize-size (:min-size entry) 0))
                          (contains? entry :max-size) (assoc :max-size (normalize-size (:max-size entry) Long/MAX_VALUE))))
                      entries)
        sizes (mapv (fn [entry intrinsic]
                      (let [basis (:basis entry)]
                        (clamp-size (if (or (nil? basis) (= basis :auto))
                                      intrinsic
                                      basis)
                                    entry)))
                    entries intrinsic-sizes)]
    (if (nil? available-size)
      sizes
      (let [content-size (max 0 (- (long (Math/floor (double available-size)))
                                   (* (max 0 (dec (count entries))) gap)))
            total (reduce + sizes)]
        (cond
          (< total content-size) (distribute sizes entries (- content-size total) :grow)
          (> total content-size) (distribute sizes entries (- total content-size) :shrink)
          :else sizes)))))

;; ─── Render-loop vertical layout ───────────────────────────────────────────

(defn render-stack
  "Render a vertical stack of COMPONENTS at WIDTH: every component renders
   at its natural height (pi: Container render). Returns a flat vector of
   lines — the document may exceed the terminal height; the render loop
   scrolls the overflow into the native terminal scrollback."
  [components width]
  ;; Transient reduce instead of (vec (mapcat …)): this concatenates the whole
  ;; document (tens of thousands of lines) every frame, so the lazy seqs were
  ;; pure allocation/GC churn.
  (persistent!
   (reduce (fn [acc c] (reduce conj! acc (protocols/render c width)))
           (transient [])
           components)))
