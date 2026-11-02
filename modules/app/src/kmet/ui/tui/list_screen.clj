(ns kmet.ui.tui.list-screen
  "Pure helpers shared by the list selectors (session, tree, fork, resource).

   The four selectors re-implemented the same viewport window, centering and
   scroll indicator; these are the extracted, unit-testable versions. A
   selector keeps its own row rendering and key handling — this namespace
   owns only the geometry.")

(defn window
  "Return [start end] row indices for a viewport of `visible` rows over `n`
   items, centered on `selected` (clamped to the valid range). Matches the
   (max 0 (min (- selected (quot visible 2)) (- n visible))) formula the
   selectors already used, so callers see no behavioural change."
  [selected visible n]
  (if (pos? n)
    (let [v (max 1 (min visible n))
          sel (max 0 (min (dec n) (or selected 0)))
          start (max 0 (min (- sel (quot v 2)) (- n v)))
          end (min (+ start v) n)]
      [start end])
    [0 0]))

(defn scroll-indicator
  "The '(N/M)' string shown when the viewport is clipped, else nil. `start`
   and `end` are the window bounds, `selected` the (0-based) selected index,
   `n` the item count."
  [start end selected n]
  (when (and (pos? n) (or (pos? start) (< end n)))
    (str "  (" (inc (max 0 (min (dec n) (or selected 0)))) "/" n ")")))

(defn clamp
  "Clamp X to [lo hi]."
  [x lo hi]
  (max lo (min hi x)))