(ns kmet.tui.components.settings-list
  "Toggle/cycle through setting values.
   Port of @earendil-works/pi-tui SettingsList — same rendering as pi:
   no header line, aligned two-column layout (`→ ` cursor on the selected
   item, values right-aligned to the widest label), scroll window with
   (N/M) indicator, selected-item description, and the Enter/Space-to-change
   hint at the bottom. Enter or Space cycles the selected item's value
   (pi: activateItem); left/right are kmet extras cycling backward/forward.
   Search (pi: enableSearch) renders a `> ` input line and filters as you
   type."
  (:require [clojure.string :as str]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.keys :as keys]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.utils :as u]
            [kmet.tui.macros :refer [track! track-deps defcomponent schedule-frame!]]
            [kmet.tui.components.input :as input]))

;; ─── Key dispatch ─────────────────────────────────────────────────────────

(defn- match?
  "Resolve DATA against keybinding ID through the global manager (pi:
   getKeybindings().matches) so user overrides apply to the list's keys."
  [data keybinding-id]
  (kb/global-match? data keybinding-id))

;; ─── Theme ──────────────────────────────────────────────────────────────────

(defrecord SettingsListTheme [label value description cursor hint])

(def default-theme
  (map->SettingsListTheme
   {:label (fn [s sel] (if sel (str "\u001b[36m" s "\u001b[39m") s))
    :value (fn [s sel] (if sel
                         (str "\u001b[36m" s "\u001b[39m")
                         (str "\u001b[2m" s "\u001b[22m")))
    :description (fn [s] (str "\u001b[2m" s "\u001b[22m"))
    :cursor "\u001b[36m→ \u001b[39m"
    :hint (fn [s] (str "\u001b[2m" s "\u001b[22m"))}))

;; ─── Helpers (pi: renderMainList / activateItem) ───────────────────────────

(def ^:private max-label-width 30)

(defn- display-items
  "The items to render/navigate: all items, or the query-filtered subset
   when search is enabled and a query is present (pi: filteredItems)."
  [items filter-text search?]
  (if (and search? (seq filter-text))
    (filterv (fn [item] (str/includes? (str/lower-case (str (:label item)))
                                       (str/lower-case filter-text)))
             items)
    items))

(defn- visible-window
  "The [start end) item index window to render (pi: renderMainList scroll
   window): the selection centered within MAX-VISIBLE, clamped to the item
   count."
  [selected max-visible n]
  (let [start (max 0 (min (- selected (quot max-visible 2))
                          (- n max-visible)))
        end (min (+ start max-visible) n)]
    [start end]))

(defn- add-hint!
  "Push the blank line + usage hint (pi: addHintLine)."
  [lines theme width search?]
  (vswap! lines conj "")
  (vswap! lines conj
          (u/truncate-to-width
           ((:hint theme) (if search?
                            "  Type to search · Enter/Space to change · Esc to cancel"
                            "  Enter/Space to change · Esc to cancel"))
           width)))

(defn- cycle-value!
  "Advance the selected item's value by DELTA steps within :values, firing
   :on-change (pi: activateItem cycles to the next value on Enter/Space).
   A value not in :values starts at the first (delta 1) or last (delta -1)
   value. A submenu item is not cyclable in any direction — :values is
   ignored for it (the submenu owns the interaction)."
  [this display selected delta]
  (let [item (nth display selected)
        ;; .indexOf is a java.util.List method: on a lazy/array SEQUENCE
        ;; (e.g. the Theme row's (sort ...) — an ArraySeq) babashka's native
        ;; image refuses the reflective invocation, the dispatch aborts and
        ;; the value never changes. Coerce to a real vector first.
        possible (when-not (:submenu item) (some-> (:values item) vec))]
    (when (and possible (seq possible))
      (let [current (or (:value item) "")
            cur-idx (.indexOf possible current)
            base (if (neg? cur-idx) (if (neg? delta) 0 -1) cur-idx)
            next-idx (mod (+ base delta) (count possible))
            new-val (nth possible next-idx)]
        (when-let [cb @(:on-change-atom this)]
          (cb (:id item) new-val))
        (swap! (:items-atom this)
               (fn [items]
                 (mapv (fn [it]
                         (if (= (:id it) (:id item))
                           (assoc it :value new-val)
                           it))
                       items)))))))

(declare settings-list-set-value!)

(defn- open-submenu!
  "Open the item's submenu (pi: activateItem submenu branch) — OPEN is
   called with the item's current value and a DONE callback. DONE with a
   value updates the row and fires :on-change; either way it closes the
   submenu and restores the selection to the row that opened it (pi:
   closeSubmenu)."
  [this item idx]
  (when-let [open (:submenu item)]
    (let [id (:id item)
          done (fn [& [value]]
                 (when (some? value)
                   (settings-list-set-value! this id value)
                   (when-let [cb @(:on-change-atom this)]
                     (cb id value)))
                 ;; dispose the closed submenu — its track! watches must not
                 ;; outlive it (kmet deviation: pi relies on GC)
                 (let [sub (deref (:submenu-atom this))]
                   (reset! (:submenu-atom this) nil)
                   (when sub (protocols/dispose sub)))
                 (reset! (:selected-idx-atom this) idx)
                 (schedule-frame!))]
      (reset! (:submenu-atom this) (open (:value item) done))
      (schedule-frame!))))

;; ─── SettingsList component ─────────────────────────────────────────────────

(defcomponent SettingsList nil [items-atom selected-idx-atom filter-atom
                                focused? theme-atom cache-atom
                                on-change-atom on-escape-atom
                                search-enabled? max-visible search-input-atom
                                submenu-atom]

  (render [this width]
    ;; An open submenu renders instead of the list (pi: render). Deliberately
    ;; UNCACHED: the child owns its own render cache and invalidates it
    ;; independently, so baking its lines into the list's track! cache would
    ;; serve stale output; open/close runs inside input dispatch, which
    ;; requests a render.
    (if-some [sub (deref (:submenu-atom this))]
      (protocols/render sub width)
      (track! this width
        (let [items @items-atom
              flt @filter-atom
              selected @selected-idx-atom
              theme @theme-atom
              search? @search-enabled?
              search-input @search-input-atom
              max-visible @max-visible
              display (display-items items flt search?)
              n (count display)
              selected (min selected (max 0 (dec n)))
              _ (reset! selected-idx-atom selected)
              lines (volatile! [])]
        ;; pi: the search input renders at the top, followed by a blank line.
        ;; Its focused?/value atoms affect the baked lines — declare them so
        ;; focus changes and typing invalidate this cache (track-deps).
          (when (and search? search-input)
            (track-deps @(:focused? search-input) @(:value-atom search-input))
            (vswap! lines into (protocols/render search-input width))
            (vswap! lines conj ""))
          (cond
            (empty? items)
            (do (vswap! lines conj ((:hint theme) "  No settings available"))
                (when search?
                  (add-hint! lines theme width search?)))

            (zero? n)
            (do (vswap! lines conj ((:hint theme)
                                    (u/truncate-to-width "  No matching settings" width)))
                (add-hint! lines theme width search?))

            :else
            (let [[start end] (visible-window selected max-visible n)
                  max-label (min max-label-width
                                 (reduce (fn [w item]
                                           (max w (u/visible-width (str (:label item)))))
                                         0
                                         items))
                  cursor (:cursor theme)]
              (doseq [i (range start end)]
                (let [item (nth display i)
                      is-selected (= i selected)
                      prefix (if is-selected cursor "  ")
                      label-padded (str (:label item)
                                        (apply str (repeat (max 0 (- max-label
                                                                     (u/visible-width (str (:label item)))))
                                                           \space)))
                      label-text ((:label theme) label-padded is-selected)
                      value-max (- width (u/visible-width prefix) max-label 2 2)
                      value-text ((:value theme)
                                  (u/truncate-to-width (str (:value item)) value-max "")
                                  is-selected)]
                  (vswap! lines conj
                          (u/truncate-to-width (str prefix label-text "  " value-text)
                                               width))))
              (when (or (pos? start) (< end n))
                (vswap! lines conj
                        ((:hint theme)
                         (u/truncate-to-width (str "  (" (inc selected) "/" n ")")
                                              (max 1 (- width 2))))))
              (when-let [desc (:description (nth display selected))]
                (vswap! lines conj "")
                (doseq [line (u/wrap-text-with-ansi (str desc) (max 1 (- width 4)))]
                  (vswap! lines conj ((:description theme) (str "  " line)))))
              (add-hint! lines theme width search?)))
          @lines))))

  (handle-input [this data]
    (if-some [sub (deref (:submenu-atom this))]
      (do (protocols/handle-input sub data)
          nil)
      (let [items @items-atom
            flt @filter-atom
            search? @search-enabled?
            search-input @search-input-atom
            display (display-items items flt search?)
            n (count display)
            selected @selected-idx-atom]
        (cond
        ;; Escape — close
          (match? data "tui.select.cancel")
          (do (when-let [cb @on-escape-atom] (cb))
              nil)

        ;; Down / Ctrl+n — wrap around (pi; ctrl+n rides the id)
          (match? data "tui.select.down")
          (do (when (pos? n) (swap! selected-idx-atom #(mod (inc %) n)))
              nil)

        ;; Up / Ctrl+p — wrap around (pi; ctrl+p rides the id)
          (match? data "tui.select.up")
          (do (when (pos? n) (swap! selected-idx-atom #(mod (dec %) n)))
              nil)

        ;; Enter, or Space when not searching (or the query is empty) —
        ;; open the item's submenu, else cycle its value (pi: activateItem)
          (or (match? data "tui.select.confirm")
              (and (keys/matches-key? data "space")
                   (or (not search?) (empty? flt))))
          (do (when (pos? n)
                (let [item (nth display selected)]
                  (if (:submenu item)
                    (open-submenu! this item selected)
                    (cycle-value! this display selected 1))))
              nil)

        ;; Search enabled — everything else goes to the search input (pi)
          (and search? search-input)
          (do (protocols/handle-input search-input data)
              (let [q (input/input-get-value search-input)]
                (reset! filter-atom q)
                (reset! selected-idx-atom 0))
              nil)

        ;; kmet extras (pi ignores these keys): right/left cycle values
          (match? data "tui.settings.cycleForward")
          (do (when (pos? n) (cycle-value! this display selected 1))
              nil)

          (match? data "tui.settings.cycleBackward")
          (do (when (pos? n) (cycle-value! this display selected -1))
              nil)

          :else nil))))

  (invalidate [this]
    ;; propagate to the open submenu (pi: invalidate — submenuComponent)
    (when-some [sub (deref (:submenu-atom this))]
      (protocols/invalidate sub)))

  (dispose [this]
    (when-some [sub (deref (:submenu-atom this))]
      (protocols/dispose sub))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-settings-list
  "Create a new SettingsList component (pi: new SettingsList(items,
   maxVisible, theme, onChange, onCancel)).
   Each item: {:id :label :value current :values [possible ...]
   :description opt}. Options: :theme (default-theme), :on-change callback,
   :enable-search (default false — pi's tools dialog has no search), and
   :max-visible — the scroll window height (default 10; pi's tools dialog
   passes (min (+ (count items) 2) 15))."
  [items & {:keys [theme on-change enable-search max-visible]
            :or {theme default-theme
                 enable-search false
                 max-visible 10}}]
  (map->SettingsList {:items-atom (atom items)
                      :selected-idx-atom (atom 0)
                      :filter-atom (atom "")
                      :focused? (atom false)
                      :theme-atom (atom theme)
                      :cache-atom (atom nil)
                      :on-change-atom (atom on-change)
                      :on-escape-atom (atom nil)
                      :search-enabled? (atom enable-search)
                      :max-visible (atom max-visible)
                      :search-input-atom (atom (when enable-search
                                                 (input/make-input)))
                      :submenu-atom (atom nil)}))

(defn settings-list-set-on-escape! [sl f]
  (reset! (:on-escape-atom sl) f))

(defn settings-list-set-items!
  "Replace the item list. By default the search state is reset with it
   (query cleared in the search box and the filter atom, selection back to
   the first row) — a wholesale replacement is a new question. Pass
   :preserve-state? true to keep the current query and selection, the
   declarative :items patch path: an items refresh must not eat the user's
   query (a selection left out of range is clamped by render)."
  ([sl items] (settings-list-set-items! sl items nil))
  ([sl items {:keys [preserve-state?]}]
   (reset! (:items-atom sl) items)
   (when-not preserve-state?
     (reset! (:selected-idx-atom sl) 0)
     (reset! (:filter-atom sl) "")
     ;; the search box text is the visible half of the query — clearing
     ;; only the filter atom would show a query the list no longer applies
     (when-let [si @(:search-input-atom sl)]
       (input/input-set-value! si "")))))

(defn settings-list-set-theme! [sl theme]
  (reset! (:theme-atom sl) theme))

(defn settings-list-set-on-change! [sl f]
  (reset! (:on-change-atom sl) f))

(defn settings-list-set-max-visible! [sl n]
  (reset! (:max-visible sl) n))

;; ─── Public helpers ─────────────────────────────────────────────────────────

(defn settings-list-get-item [sl id]
  (some #(when (= (:id %) id) %) @(:items-atom sl)))

(defn settings-list-select-item!
  "Move the selection to the item with ID (pi: selectItem); a no-op when
   absent. Indexes the display list — the filtered one while searching."
  [sl id]
  (let [display (display-items @(:items-atom sl) @(:filter-atom sl)
                               @(:search-enabled? sl))
        idx (first (keep-indexed (fn [i item] (when (= id (:id item)) i))
                                 display))]
    (when idx
      (reset! (:selected-idx-atom sl) idx))))

(defn settings-list-set-value! [sl id value]
  (swap! (:items-atom sl)
         (fn [items]
           (mapv (fn [item]
                   (if (= (:id item) id)
                     (assoc item :value value)
                     item))
                 items))))

;; ─── IFocusable ─────────────────────────────────────────────────────────────

(extend-type SettingsList
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val]
    (reset! (:focused? this) val)
    ;; the search input's cursor follows the list's focus (pi renders the
    ;; search Input with its cursor regardless; kmet's Input needs focus)
    (when-let [si @(:search-input-atom this)]
      (protocols/set-focused! si val))))
