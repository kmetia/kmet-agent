(ns kmet.tui.components.select-list
  "Interactive selection list with keyboard navigation and fuzzy filtering.
   Port of @earendil-works/pi-tui SelectList — same rendering: no header,
   two-column item layout with aligned descriptions, `→ ` selected prefix,
   `  (N/M)` scroll info, `  No matching commands` empty state."
  (:require [clojure.string :as str]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.keys :as keys]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.utils :as u]
            [kmet.tui.macros :refer [track! defcomponent]]))

;; ─── Key dispatch ─────────────────────────────────────────────────────────

;; ─── Default theme ──────────────────────────────────────────────────────────
;; Matches pi's SelectListTheme interface.

(defrecord SelectListTheme [selected-prefix selected-text description
                            scroll-info no-match])

(def default-theme
  (map->SelectListTheme
   {:selected-prefix (fn [s] (str "\u001b[36m" s "\u001b[39m"))  ;; accent cyan
    :selected-text (fn [s] (str "\u001b[36m" s "\u001b[39m"))   ;; accent cyan
    :description (fn [s] (str "\u001b[2m" s "\u001b[22m"))
    :scroll-info (fn [s] (str "\u001b[2m" s "\u001b[22m"))
    :no-match (fn [s] (str "\u001b[31m" s "\u001b[0m"))}))

;; ─── Layout constants (pi: select-list.ts) ─────────────────────────────────

(def ^:private DEFAULT-PRIMARY-COLUMN-WIDTH 32)
(def ^:private PRIMARY-COLUMN-GAP 2)
(def ^:private MIN-DESCRIPTION-WIDTH 10)

;; ─── Fuzzy matching ─────────────────────────────────────────────────────────

(defn- score-match
  "Simple score: prefer matches at word boundaries and start of string."
  [pattern text]
  (if (empty? pattern) 0
      (let [n (count text)
            idx (clojure.string/index-of (clojure.string/lower-case text) (clojure.string/lower-case pattern))]
        (if (and idx (>= idx 0))
          (- n idx)  ;; Prefer matches earlier in string
          ;; Fuzzy: count contiguous/recent matches
          (let [pl (count pattern)]
            (loop [pi 0 ti 0 score 0]
              (if (or (>= pi pl) (>= ti n)) score
                  (if (= (nth pattern pi) (nth text ti))
                    (recur (inc pi) (inc ti) (+ score (- n ti)))
                    (recur pi (inc ti) score)))))))))

(defn- display-value [item]
  (or (:label item) (:value item) ""))

(defn- normalize-single-line [text]
  (clojure.string/trim (clojure.string/replace text #"[\r\n]+" " ")))

(defn- non-blank-desc
  "Pi: description only takes the two-column layout when non-blank — an
   empty (or whitespace-only) description renders no second column, so
   the label takes the full width instead of reserving padding."
  [desc]
  (when (seq desc)
    (let [s (normalize-single-line desc)]
      (when (seq s) s))))

(defn- primary-column-width
  "Pi: getPrimaryColumnWidth — widest primary value + gap, clamped to the
   configured bounds (default 32)."
  [filtered min-col max-col]
  (let [widest (reduce (fn [w item] (max w (u/visible-width (display-value item))))
                       0 filtered)
        target (+ widest PRIMARY-COLUMN-GAP)
        lo (max 1 (min min-col max-col))
        hi (max 1 min-col max-col)]
    (max lo (min target hi))))

(defn- truncate-primary
  "Pi: truncatePrimary — apply the layout's truncatePrimary callback (or the
   default truncate-to-width) with the SelectListTruncatePrimaryContext, then
   clamp the result to max-width (pi re-truncates the callback output)."
  [item is-selected? max-width column-width truncate-fn]
  (let [text (display-value item)
        truncated (if truncate-fn
                    (truncate-fn {:text text
                                  :max-width max-width
                                  :column-width column-width
                                  :item item
                                  :is-selected is-selected?})
                    (u/truncate-to-width text max-width))]
    (u/truncate-to-width truncated max-width)))

(defn- render-item
  "Pi: renderItem — `→ ` prefix for the selection, primary value truncated via
   truncate-primary to the column width, description aligned in a second
   column when it fits."
  [item selected? width desc theme primary-width truncate-fn]
  (let [prefix (if selected? "→ " "  ")
        prefix-width (u/visible-width prefix)]
    (if (and desc (> width 40))
      (let [effective-col (max 1 (min primary-width (- width prefix-width 4)))
            max-primary (max 1 (- effective-col PRIMARY-COLUMN-GAP))
            truncated-value (truncate-primary item selected? max-primary
                                              effective-col truncate-fn)
            truncated-width (u/visible-width truncated-value)
            spacing (apply str (repeat (max 1 (- effective-col truncated-width)) \space))
            desc-start (+ prefix-width truncated-width (count spacing))
            remaining (- width desc-start 2)]
        (if (> remaining MIN-DESCRIPTION-WIDTH)
          (let [truncated-desc (u/truncate-to-width desc remaining)]
            (if selected?
              ((:selected-text theme) (str prefix truncated-value spacing truncated-desc))
              (str prefix truncated-value
                   ((:description theme) (str spacing truncated-desc)))))
          (if selected?
            ((:selected-text theme) (str prefix truncated-value))
            (str prefix truncated-value))))
      (let [max-width (- width prefix-width 2)
            truncated-value (truncate-primary item selected? max-width
                                              max-width truncate-fn)]
        (if selected?
          ((:selected-text theme) (str prefix truncated-value))
          (str prefix truncated-value))))))

(defn- notify-selection-change!
  "Pi: notifySelectionChange — fire :on-selection-change with the item now
   selected after a navigation key moves the selection."
  [sl filtered n]
  (when-let [cb @(:on-selection-change sl)]
    (when (pos? n)
      (cb (nth filtered (min @(:selected-idx-atom sl) (dec n)))))))

;; ─── SelectList component ───────────────────────────────────────────────────

(defcomponent SelectList nil [items-atom selected-idx-atom filter-atom
                              on-select on-escape on-selection-change
                              focused? theme-atom height-atom cache-atom
                              header-atom no-match-text-atom
                              min-primary-column-atom max-primary-column-atom
                              truncate-primary-atom on-key]

  (render [this width]
    (track! this width
      (let [items @items-atom
            filter-str @filter-atom
            theme @theme-atom
            height @height-atom
            header @header-atom
            no-match-text @no-match-text-atom
            min-col @min-primary-column-atom
            max-col @max-primary-column-atom
            truncate-fn @truncate-primary-atom
            filtered (if (empty? filter-str)
                       items
                       (vec (->> items
                                 (clojure.core/filter #(fuzzy/subsequence-match? filter-str (:label %)))
                                 (sort-by #(- (score-match filter-str (:label %)))))))
            n (count filtered)
            selected (min @selected-idx-atom (max 0 (dec n)))
            _ (reset! selected-idx-atom selected)
            ;; Pi: viewport is centered on the selection
            ;; (startIndex = max(0, min(selected - floor(height/2), n - height)))
            start-idx (max 0 (min (- selected (quot height 2)) (- n height)))
            visible (subvec filtered start-idx
                            (min (+ start-idx height) n))
            lines (volatile! [])]
        ;; Optional header (kmet extension — pi's SelectList renders items only)
        (when (seq header)
          (vswap! lines conj (str " " (u/truncate-to-width header (- width 2)))))
        ;; Items
        (if (empty? filtered)
          (vswap! lines conj ((:no-match theme) no-match-text))
          (let [col-width (primary-column-width filtered min-col max-col)]
            (doseq [[idx item] (map-indexed vector visible)]
              (let [global-idx (+ idx start-idx)
                    desc (non-blank-desc (:description item))]
                (vswap! lines conj
                        (render-item item (= global-idx selected) width desc theme
                                     col-width truncate-fn))))))
        ;; Scroll info — pi: shown when the viewport is clipped at either end,
        ;; truncated to the terminal width (pi: truncateToWidth(text, width-2))
        (when (or (pos? start-idx) (< (+ start-idx height) n))
          (let [scroll-text (str "  (" (inc selected) "/" n ")")]
            (vswap! lines conj
                    ((:scroll-info theme)
                     (u/truncate-to-width scroll-text (max 1 (- width 2)))))))
        @lines)))

  (handle-input [this data]
    (if (and @on-key (@on-key this data))
      nil
      (let [items @items-atom
            filter-str @filter-atom
            filtered (if (empty? filter-str)
                       items
                       (vec (clojure.core/filter #(fuzzy/subsequence-match? filter-str (:label %)) items)))
            n (count filtered)
            selected @selected-idx-atom]
        (cond
        ;; Enter — select
          (and (kb/global-match? data "tui.select.confirm") (pos? n))
          (do (when-let [cb @on-select]
                (cb (nth filtered selected)))
              nil)

        ;; Escape — cancel
          (kb/global-match? data "tui.select.cancel")
          (do (when-let [cb @on-escape] (cb))
              nil)

        ;; Down — pi wraps to the top at the bottom (ctrl+n rides the id)
          (kb/global-match? data "tui.select.down")
          (do (when (pos? n)
                (if (= selected (dec n))
                  (reset! selected-idx-atom 0)
                  (swap! selected-idx-atom inc))
                (notify-selection-change! this filtered n))
              nil)

        ;; Up — pi wraps to the bottom at the top (ctrl+p rides the id)
          (kb/global-match? data "tui.select.up")
          (do (when (pos? n)
                (if (zero? selected)
                  (reset! selected-idx-atom (dec n))
                  (swap! selected-idx-atom dec))
                (notify-selection-change! this filtered n))
              nil)

        ;; Page down — shift+pageDown (the select-list's own key; plain
        ;; pageDown is the viewport transcript scroll, pi parity)
          (keys/matches-key? data (keys/shift "pageDown"))
          (do (when (pos? n)
                (swap! selected-idx-atom #(min (+ % @height-atom) (max 0 (dec n))))
                (notify-selection-change! this filtered n))
              nil)

        ;; Page up
          (keys/matches-key? data (keys/shift "pageUp"))
          (do (swap! selected-idx-atom #(max 0 (- % @height-atom)))
              (notify-selection-change! this filtered n)
              nil)

        ;; Home (kmet: tui.select.first)
          (kb/global-match? data "tui.select.first")
          (do (reset! selected-idx-atom 0)
              (notify-selection-change! this filtered n)
              nil)

        ;; End (kmet: tui.select.last)
          (kb/global-match? data "tui.select.last")
          (do (when (pos? n)
                (reset! selected-idx-atom (dec n))
                (notify-selection-change! this filtered n))
              nil)

        ;; Backspace — remove last filter char (the editor's delete id, so a
        ;; rebind moves filter editing with it)
          (kb/global-match? data "tui.editor.deleteCharBackward")
          (do (swap! filter-atom #(subs % 0 (max 0 (dec (count %)))))
              (reset! selected-idx-atom 0)
              nil)

        ;; Regular character — add to filter
          :else
          (let [has-ctrl? (some #(let [c (int %)]
                                   (or (< c 32) (== c 127)
                                       (and (>= c 128) (<= c 159))))
                                data)]
            (when-not has-ctrl?
              (swap! filter-atom str data)
              (reset! selected-idx-atom 0)
              nil)))))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn- column-bounds
  "pi: getPrimaryColumnBounds — a single provided bound applies to both
   sides (min ?? max ?? 32); neither defaults to 32."
  [min-primary-column-width max-primary-column-width]
  [(or min-primary-column-width max-primary-column-width
       DEFAULT-PRIMARY-COLUMN-WIDTH)
   (or max-primary-column-width min-primary-column-width
       DEFAULT-PRIMARY-COLUMN-WIDTH)])

(defn make-select-list
  "Create a new SelectList component.
   Items are maps with :label, optional :value and :description.
   Options:
     :height                  — max visible items (default 10)
     :theme                   — SelectListTheme map (default default-theme)
     :header                  — optional title line above the items
     :no-match-text           — text shown when the filter matches nothing
                               (default \"  No matching commands\")
     :min-primary-column-width / :max-primary-column-width — description
                               column bounds (pi defaults: 32)
     :truncate-primary        — fn of {:text :max-width :column-width :item
                               :is-selected} returning the (possibly
                               truncated) primary value (pi
                               SelectListLayoutOptions.truncatePrimary)
     :on-selection-change     — fn called with the newly selected item after
                               a navigation key moves the selection (pi
                               SelectList.onSelectionChange)
     :on-key                  — fn (fn [sl data]) called before built-in key
                               handling; return truthy when the key was
                               consumed (pi SelectList onAction)
     :on-escape               — fn called when the user cancels"
  [items & {:keys [height theme header no-match-text
                   min-primary-column-width max-primary-column-width
                   truncate-primary
                   on-key on-select on-escape on-selection-change]
            :or {height 10 theme default-theme
                 no-match-text "  No matching commands"}}]
  (let [[min-w max-w] (column-bounds min-primary-column-width
                                     max-primary-column-width)]
    (map->SelectList {:items-atom (atom items)
                      :selected-idx-atom (atom 0)
                      :filter-atom (atom "")
                      :on-select (atom on-select)
                      :on-escape (atom on-escape)
                      :on-selection-change (atom on-selection-change)
                      :focused? (atom false)
                      :theme-atom (atom theme)
                      :height-atom (atom height)
                      :cache-atom (atom nil)
                      :header-atom (atom header)
                      :no-match-text-atom (atom no-match-text)
                      :min-primary-column-atom (atom min-w)
                      :max-primary-column-atom (atom max-w)
                      :truncate-primary-atom (atom truncate-primary)
                      :on-key (atom on-key)})))

;; ─── Public helpers ─────────────────────────────────────────────────────────

(defn select-list-set-header!
  "Set the header line (pi: SelectList header updates dynamically)."
  [sl header]
  (reset! (:header-atom sl) header))

(defn select-list-set-items!
  "Replace the item list. By default the selection and the filter text are
   reset too — a wholesale replacement is a new question. Pass
   :preserve-state? true to keep them, the declarative :items patch path:
   there an items change is a refresh of the SAME question, so it must not
   eat the user's typed filter (a selection left out of range is clamped by
   render)."
  ([sl items] (select-list-set-items! sl items nil))
  ([sl items {:keys [preserve-state?]}]
   (reset! (:items-atom sl) items)
   (when-not preserve-state?
     (reset! (:selected-idx-atom sl) 0)
     (reset! (:filter-atom sl) ""))))

(defn select-list-set-selected!
  "Move the selection to IDX, clamped to the current item count."
  [sl idx]
  (let [n (count @(:items-atom sl))]
    (reset! (:selected-idx-atom sl) (max 0 (min (max 0 (dec n)) idx)))))

(defn select-list-set-filter!
  "Set the initial filter text (pi: ModelSelectorComponent initialSearch —
   /model opens the selector with the failed search term pre-filled)."
  [sl filter]
  (reset! (:filter-atom sl) filter)
  (reset! (:selected-idx-atom sl) 0))

(defn select-list-get-selected [sl]
  (let [items @(:items-atom sl)
        filter-str @(:filter-atom sl)
        filtered (if (empty? filter-str)
                   items
                   (vec (clojure.core/filter #(fuzzy/subsequence-match? filter-str (:label %)) items)))
        idx @(:selected-idx-atom sl)]
    (when (and (seq filtered) (>= idx 0) (< idx (count filtered)))
      (nth filtered idx))))

(defn select-list-set-theme! [sl theme]
  (reset! (:theme-atom sl) theme))

(defn select-list-set-height! [sl height]
  (reset! (:height-atom sl) height))

(defn select-list-set-no-match-text! [sl text]
  (reset! (:no-match-text-atom sl) text))

(defn select-list-set-column-bounds!
  "Set the description column bounds from the optional min/max primary
   column widths (the same single-bound-applies-to-both defaulting as
   make-select-list)."
  [sl min-primary-column-width max-primary-column-width]
  (let [[min-w max-w] (column-bounds min-primary-column-width
                                     max-primary-column-width)]
    (reset! (:min-primary-column-atom sl) min-w)
    (reset! (:max-primary-column-atom sl) max-w)))

(defn select-list-set-truncate-primary! [sl f]
  (reset! (:truncate-primary-atom sl) f))

(defn select-list-set-on-select! [sl f]
  (reset! (:on-select sl) f))

(defn select-list-set-on-escape! [sl f]
  (reset! (:on-escape sl) f))

(defn select-list-set-on-selection-change! [sl f]
  (reset! (:on-selection-change sl) f))

(defn select-list-set-on-key! [sl f]
  (reset! (:on-key sl) f))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type SelectList
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))
