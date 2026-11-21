(ns kmet.tui.components.dynamic-border
  "DynamicBorder — a border line that adjusts to viewport width.
   Port of pi modes/interactive/components/dynamic-border.ts. Renders a
   single horizontal rule spanning the full render width, its glyph taken
   from a kmet.tui.border set (tui.md §2.8) and colored via the provided
   color function. Used to frame dialogs (pi: preset.ts pick dialog,
   BorderedLoader)."
  (:require
   [kmet.tui.border :as border]
   [kmet.tui.macros :refer [defcomponent]]
   [kmet.tui.theme :as theme]))

(defcomponent DynamicBorder nil [color-fn border]
  (render [_this width]
    ;; :none resolves to nil — the dialog asked for no rule, so render no
    ;; line at all (rather than a blank one, which would still cost a row).
    (if-let [b (:border _this)]
      [(color-fn (border/rule-line b (max 1 width)))]
      [])))

(defn make-dynamic-border
  "Create a DynamicBorder. COLOR-FN receives the border string and returns
   it styled (pi: DynamicBorder constructor); nil uses the dark theme's
   :border token. BORDER is a kmet.tui.border style — keywords (:normal
   :rounded :thick :double :block :ascii :hidden), a partial map, or :none
   for no rule; nil means :normal. An unknown style throws here, at
   construction."
  ([] (make-dynamic-border nil nil))
  ([color-fn] (make-dynamic-border color-fn nil))
  ([color-fn border]
   (map->DynamicBorder {:color-fn (or color-fn #(theme/fg theme/dark-theme :border %))
                        :border (border/resolve border)})))
