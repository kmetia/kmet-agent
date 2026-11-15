(ns kmet.ui.tui.grouped-selector
  "The mounted screen shared by the grouped selectors (`kmet plugins` and the
   builtin `/config` resource selector).

   The screen owns the state machine both screens had duplicated: the
   terminal-height-aware frame, the search field, grouped section rows, item
   navigation and paging, the global/project scope switch on Tab, and the
   checkbox row layout. The owner supplies the items, their grouping, the
   group/item line renderers and the activate action (the spec map accepted
   by `make-screen`).

   State lives in `:state-atom` as `{:write-scope :global|:project :query
   string :selected index :rows [flat rows] :items [owner items] :expanded
   #{group-key}}`. A flat row is `{:kind :group :group g :collapsed? bool}` or
   `{:kind :item :group g :item i}`. A group's key is its `:key`, falling back
   to `:label`. With `:collapsible?` the `:expanded` set starts empty (every
   group is folded) and group rows are selectable so Space/Enter can fold or
   unfold one; a non-blank query unfolds every matching group, so search never
   hides a result. Without `:collapsible?` groups are static headings and only
   item rows are selectable. The search field is a tag-owned `[:input]` whose
   text is mirrored into `:query` as it forwards, so the tree feeds the value
   straight back and an unchanged value never disturbs the caret.

   The frame is a mounted hiccup root (tui.md §14.1): the two borders are
   `[:dynamic-border]` elements, the header/hint/scope/row lines are
   `[:truncated-text]` leaves over already-styled strings, and the search
   field is a tag-owned `[:input]`. `screen-refresh!` re-reads the items for
   the current scope; `screen-refilter!` re-filters the cached items; a row
   toggle can patch one cached item in place with `screen-update-item!`.

   Spec keys:
     :write-scope        initial scope, `:global` (default) or `:project`
     :can-switch-scope?  whether Tab switches scope (default false)
     :collapsible?       fold group rows; groups start collapsed (default false)
     :rows               initial terminal height (default 24)
     :size-ref           the TUI's live terminal-size ref (optional)
     :request-render!    0-arg forced repaint (with :size-ref)
     :on-close           called on escape/ctrl+c
     :read               `(fn [scope]) -> owner items`
     :groups             `(fn [items]) -> [{:key ... :label ... :items [...]} ...]`
     :filter-match       `(fn [item lower-case-query]) -> truthy`
     :item-identity      `(fn [item]) -> stable identity`
     :render-group       `(fn [{:keys [group collapsed? selected? scope theme
                          width]}]) -> string`
     :render-item        `(fn [{:keys [item scope theme width selected?]}])
                          -> string`
     :on-activate        `(fn [{:keys [screen item scope]}])`
     :empty-text         line shown when no item matches (optional)
     :title              `(fn [scope]) -> plain title`
     :action-hint        `(fn [scope]) -> plain hint text`
     :scope-line         `(fn [scope]) -> plain path/scope note`"
  (:require [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.hikap.core :as engine]
            [kmet.libs.theme :as th]
            [kmet.reakt.core :as r]
            [kmet.tui.components.input :as input]
            [kmet.tui.hikap :as hikap]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.keys :as keys]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.ui.tui.list-screen :as ls]
            [kmet.ui.tui.subs :as s]))

(def ^:private chrome-lines
  "Fixed lines around the list rows: top spacer, top border, spacer, two
   header lines, spacer, search input line, spacer, the bottom spacer and
   bottom border, plus one line reserved for the scroll indicator."
  11)

;; ─── Rendering helpers ────────────────────────────────────────────────────

(defn checkbox
  "The checkbox token for a row in SCOPE. In project scope STATE `:load` is
   `[+]` and `:unload` `[-]`; any other state (inherit) shows ENABLED dimly.
   Global scope shows ENABLED as `[x]` or `[ ]`."
  [theme scope state enabled]
  (if (= scope :project)
    (case state
      :load (th/fg theme :success "[+]")
      :unload (th/fg theme :warning "[-]")
      (th/fg theme :dim (if enabled "[x]" "[ ]")))
    (if enabled (th/fg theme :success "[x]") (th/fg theme :dim "[ ]"))))

(defn- hint
  "A key hint: the current binding text for BINDING-ID (or FALLBACK) plus
   the action description."
  [binding-id fallback desc]
  (let [key-text (app-kb/key-text binding-id)]
    (str (if (seq key-text) key-text fallback) " " desc)))

(defn- line-leaf
  "One already-styled line as an element. `[:truncated-text]`, not `[:text]`:
   a line wider than the frame must be clipped, never re-wrapped."
  [line]
  [:truncated-text {:text line :padding-x 0 :padding-y 0}])

;; ─── State and row rebuilding ─────────────────────────────────────────────

(defn- item-id
  [screen item]
  ((:item-identity (:spec screen)) item))

(defn- group-key
  "The stable identity of GROUP across rebuilds and scope switches."
  [group]
  (or (:key group) (:label group)))

(defn- group-expanded?
  "Whether GROUP's items are visible: non-collapsible screens always show
   them, a non-blank search query unfolds every matching group, and otherwise
   the user controls membership in the `:expanded` set."
  [screen group query]
  (or (not (:collapsible? (:spec screen)))
      (not (str/blank? query))
      (contains? (:expanded @(:state-atom screen)) (group-key group))))

(defn- build-rows
  "The filtered flat rows of ITEMS: group rows and their item rows. A group
   keeps only matching items and disappears when none match. QUERY is already
   trimmed and lower-cased; a non-blank query unfolds matching groups."
  [screen items query]
  (let [match? (:filter-match (:spec screen))]
    (vec (mapcat (fn [group]
                   (let [items (filterv #(match? % query) (:items group))
                         group (assoc group :items items)]
                     (when (seq items)
                       (let [collapsed? (not (group-expanded? screen group query))]
                         (cons {:kind :group :group group :collapsed? collapsed?}
                               (when-not collapsed?
                                 (map (fn [item]
                                        {:kind :item :group group :item item})
                                      items)))))))
                 ((:groups (:spec screen)) items)))))

(defn- selectable-row?
  "Whether ROW takes the cursor: every item row, plus a group row on a
   collapsible screen (so the group can be folded and unfolded)."
  [screen row]
  (or (= :item (:kind row))
      (and (:collapsible? (:spec screen)) (= :group (:kind row)))))

(defn- selectable-indices
  "The indices of rows that may take the cursor."
  [screen rows]
  (keep-indexed (fn [i row] (when (selectable-row? screen row) i)) rows))

(defn- item-indices
  "The indices of item rows."
  [rows]
  (keep-indexed (fn [i row] (when (= :item (:kind row)) i)) rows))

(defn- row-identity
  "The identity a row's selection keeps across a rebuild."
  [screen row]
  (case (:kind row)
    :item [:item (item-id screen (:item row))]
    :group [:group (group-key (:group row))]))

(defn- rebuild-rows!
  "Rebuild the flat rows from the cached items and the current query. With
   PRESERVE-SELECTED? the row under the cursor keeps its identity when it
   survives the rebuild; otherwise a non-blank query selects the first
   matching item and an unfiltered screen selects the first selectable row."
  [screen preserve-selected?]
  (let [st @(:state-atom screen)
        keep-id (when preserve-selected?
                  (when-let [row (get (:rows st) (:selected st))]
                    (row-identity screen row)))
        query (str/lower-case (str/trim (:query st)))
        rows (build-rows screen (:items st) query)
        indices (vec (selectable-indices screen rows))
        items (vec (item-indices rows))
        selected (or (when keep-id
                       (first (filter (fn [i]
                                        (= keep-id
                                           (row-identity screen (nth rows i))))
                                      indices)))
                     (when (and (not (str/blank? query)) (seq items))
                       (first items))
                     (first indices)
                     0)]
    (swap! (:state-atom screen)
           assoc :rows rows :selected selected)))

(defn screen-refresh!
  "Re-read the owner items for the current scope and rebuild the rows,
   keeping the selected row when it still exists."
  [screen]
  (let [st @(:state-atom screen)]
    (swap! (:state-atom screen) assoc
           :items ((:read (:spec screen)) (:write-scope st)))
    (rebuild-rows! screen true)))

(defn screen-refilter!
  "Re-filter the cached items (a search change does not change the source):
   a non-blank query selects its first match, an unfiltered screen its first
   selectable row."
  [screen]
  (rebuild-rows! screen false))

(defn screen-update-item!
  "Merge CHANGES into the cached item with ITEM's identity and rebuild the
   rows from the cached items, without re-reading the source."
  [screen item changes]
  (let [id (item-id screen item)]
    (swap! (:state-atom screen) update :items
           (fn [items]
             (mapv #(if (= id (item-id screen %)) (merge % changes) %) items)))
    (rebuild-rows! screen true)))

;; ─── Input actions ────────────────────────────────────────────────────────

(defn- move-selection!
  [screen direction]
  (swap! (:state-atom screen)
         (fn [{:keys [rows selected] :as st}]
           (let [indices (vec (selectable-indices screen rows))]
             (if (seq indices)
               (let [pos (count (take-while #(< % selected) indices))
                     pos (max 0 (min (dec (count indices)) (+ pos direction)))]
                 (assoc st :selected (nth indices pos)))
               st)))))

(defn- page-target
  "The nearest selectable row at or beyond TARGET in DIRECTION, or nil when
   the scan leaves the rows."
  [indices target direction]
  (let [candidates (if (pos? direction)
                     (filter #(>= % target) indices)
                     (filter #(<= % target) indices))]
    (if (pos? direction) (first candidates) (last candidates))))

(defn- page-selection!
  "Jump by the visible row count, then to the nearest selectable row in
   DIRECTION (+1 forward, -1 backward)."
  [screen direction]
  (swap! (:state-atom screen)
         (fn [st]
           (let [rows (:rows st)
                 indices (vec (selectable-indices screen rows))
                 max-visible (max 5 (- @(:rows-count screen) chrome-lines))
                 sel (:selected st)
                 n (count rows)
                 target (if (= -1 direction)
                          (max 0 (- sel max-visible))
                          (min (max 0 (dec n)) (+ sel max-visible)))
                 found (page-target indices target direction)]
             (if found
               (assoc st :selected found)
               st)))))

(defn- switch-scope!
  [screen]
  (swap! (:state-atom screen) update :write-scope
         (fn [scope] (if (= scope :global) :project :global)))
  (screen-refresh! screen))

(defn- close!
  [screen]
  (when-let [f (:on-close (:spec screen))] (f)))

(defn- refresh-filter!
  "Mirror the search field's live text into `:query` and re-filter (the
   field is tag-owned, so the panel reads its value after forwarding)."
  [screen]
  (swap! (:state-atom screen)
         assoc :query (input/input-get-value
                       (hikap/materialize-ref! screen (:search-ref screen))))
  (screen-refilter! screen))

(defn- toggle-group!
  "Fold or unfold GROUP-KEY, keeping the group's row under the cursor."
  [screen group-key]
  (swap! (:state-atom screen) update :expanded
         (fn [expanded]
           (if (contains? expanded group-key)
             (disj expanded group-key)
             (conj expanded group-key))))
  (rebuild-rows! screen true))

(defn- activate-selected!
  [screen]
  (let [{:keys [rows selected write-scope]} @(:state-atom screen)
        row (get rows selected)
        spec (:spec screen)]
    (if (= :item (:kind row))
      ((:on-activate spec)
       {:screen screen :item (:item row) :scope write-scope})
      (when (and (:collapsible? spec) (= :group (:kind row)))
        (toggle-group! screen (group-key (:group row)))))))

;; ─── The frame ────────────────────────────────────────────────────────────

(defn- header-line
  "The title and key hints, padded to the frame width. A selected group row
   gets the fold hint instead of the owner's item action hint."
  [screen theme width]
  (let [spec (:spec screen)
        st @(:state-atom screen)
        scope (:write-scope st)
        selected-row (get (:rows st) (:selected st))
        group-row? (and (:collapsible? spec) (= :group (:kind selected-row)))
        action-hint (if group-row?
                      "space expand/collapse"
                      ((:action-hint spec) scope))
        title (th/bold ((:title spec) scope))
        sep (th/fg theme :muted " · ")
        switch-hint (when (:can-switch-scope? spec)
                      (str (hint "tui.input.tab" "tab" "switch mode") sep))
        hints (str switch-hint action-hint sep "esc close")]
    (u/truncate-to-width
     (str title (apply str (repeat (max 1 (- width (u/visible-width title)
                                             (u/visible-width hints)))
                                   " "))
          hints)
     width "")))

(defn- scope-line
  [screen theme width]
  (u/truncate-to-width
   (th/fg theme :muted ((:scope-line (:spec screen))
                        (:write-scope @(:state-atom screen))))
   width ""))

(defn- render-row
  [screen theme scope width row selected?]
  (let [spec (:spec screen)]
    (if (= :group (:kind row))
      ((:render-group spec)
       {:group (:group row) :collapsed? (:collapsed? row) :scope scope
        :theme theme :width width :selected? selected?})
      ((:render-item spec)
       {:item (:item row) :scope scope :theme theme :width width
        :selected? selected?}))))

(defn- frame-tree
  [screen]
  (let [width hikap/*width*
        theme (r/tracked-deref s/theme-sub)
        st (r/tracked-deref (:state-atom screen))
        rows-count (r/tracked-deref (:rows-count screen))
        scope (:write-scope st)
        rows (:rows st)
        n (count rows)
        row-total (count (selectable-indices screen rows))
        max-visible (max 5 (- rows-count chrome-lines))
        sel (min (or (:selected st) 0) (max 0 (dec n)))
        [start-idx end-idx] (ls/window sel max-visible n)
        visible (subvec rows start-idx end-idx)
        border-fn #(th/fg theme :accent %)
        row-lines (map-indexed (fn [rel row]
                                 (render-row screen theme scope width row
                                             (and (selectable-row? screen row)
                                                  (= (+ start-idx rel) sel))))
                               visible)
        clipped? (or (pos? start-idx) (< end-idx n))
        scroll-line (when clipped?
                      (let [cur (count (filterv #(selectable-row? screen %)
                                                (subvec rows 0 (inc sel))))]
                        (u/truncate-to-width
                         (th/fg theme :dim (str "  (" cur "/" row-total ")"))
                         width "")))
        empty-line (when (zero? row-total)
                     (when-let [text (:empty-text (:spec screen))]
                       (u/truncate-to-width
                        (th/fg theme :muted (str "  " text)) width "")))]
    [:container {}
     [:spacer {:lines 1}]
     [:dynamic-border {:color-fn border-fn}]
     [:spacer {:lines 1}]
     (line-leaf (header-line screen theme width))
     (line-leaf (scope-line screen theme width))
     [:spacer {:lines 1}]
     [:input {:ref (:search-ref screen)
              :value (:query st)
              :focused? (r/tracked-deref (:focused? screen))}]
     [:spacer {:lines 1}]
     (map line-leaf row-lines)
     (when empty-line (line-leaf empty-line))
     (when scroll-line (line-leaf scroll-line))
     [:spacer {:lines 1}]
     [:dynamic-border {:color-fn border-fn}]]))

;; ─── The component ────────────────────────────────────────────────────────

(defcomponent GroupedSelector nil
              [state-atom search-ref rows-count focused? spec root]

  (render [this width] (protocols/render (:root this) width))

  (handle-input [this data]
    (let [kmgr (kb/get-global-keybindings)]
      (cond
        (kb/matches-key kmgr data "tui.select.up")
        (do (move-selection! this -1) nil)

        (kb/matches-key kmgr data "tui.select.down")
        (do (move-selection! this 1) nil)

        (kb/matches-key kmgr data "tui.select.pageUp")
        (do (page-selection! this -1) nil)

        (kb/matches-key kmgr data "tui.select.pageDown")
        (do (page-selection! this 1) nil)

        (kb/matches-key kmgr data "tui.select.cancel")
        (do (close! this) nil)

        (keys/matches-key? data "ctrl+c")
        (do (close! this) nil)

        (kb/matches-key kmgr data "tui.input.tab")
        (do (when (:can-switch-scope? (:spec this)) (switch-scope! this)) nil)

        (or (= data " ") (kb/matches-key kmgr data "tui.select.confirm"))
        (do (activate-selected! this) nil)

        :else
        (do (protocols/handle-input
             (hikap/materialize-ref! this (:search-ref this)) data)
            (refresh-filter! this)
            nil))))

  (dispose [this]
    (when-some [unwatch (:rows-unwatch this)]
      (unwatch))
    (protocols/dispose (:root this))))

;; ─── IFocusable — the panel's flag; the tree derives the field emphasis ────

(extend-type GroupedSelector
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))

;; ─── Terminal resize ──────────────────────────────────────────────────────

(defn- watch-terminal-rows!
  "Keep SCREEN's layout height in step with SIZE-REF and force a repaint
   through REQUEST-RENDER! whenever the height changes. Returns a cancel fn
   for the screen's dispose."
  [screen size-ref request-render!]
  (let [k (keyword (gensym "grouped-selector-rows-"))
        last-rows (atom @(:rows-count screen))]
    (add-watch size-ref k
               (fn [_ _ _ size]
                 (let [rows (:rows size)]
                   (when (not= rows @last-rows)
                     (reset! last-rows rows)
                     (reset! (:rows-count screen) rows)
                     (request-render!)))))
    (fn [] (remove-watch size-ref k))))

;; ─── Construction and test helpers ────────────────────────────────────────

(defn make-screen
  "Build a grouped selector from SPEC (see the namespace docstring). The
   owner items for the initial scope are read immediately."
  [spec]
  (let [state-atom (atom {:write-scope (or (:write-scope spec) :global)
                          :query ""
                          :selected 0
                          :rows []
                          :items []
                          :expanded #{}})
        screen (map->GroupedSelector
                {:state-atom state-atom
                 :search-ref (engine/ref)
                 :rows-count (atom (or (:rows spec) 24))
                 :focused? (atom false)
                 :spec spec})
        root (hikap/root (fn [_props] (frame-tree screen)))
        screen (assoc screen :root root)]
    (screen-refresh! screen)
    (cond-> screen
      (and (:size-ref spec) (:request-render! spec))
      (assoc :rows-unwatch
             (watch-terminal-rows! screen (:size-ref spec)
                                   (:request-render! spec))))))

(defn screen-write-scope
  "The screen's current write scope (tests)."
  [screen]
  (:write-scope @(:state-atom screen)))

(defn screen-rows
  "The screen's current flat rows (tests)."
  [screen]
  (:rows @(:state-atom screen)))

(defn screen-selected
  "The screen's selected row index (tests)."
  [screen]
  (:selected @(:state-atom screen)))

(defn screen-set-query!
  "Set the search query and re-filter (tests; typing drives the same path
   through the tag-owned field)."
  [screen query]
  (swap! (:state-atom screen) assoc :query (str query))
  (screen-refilter! screen))
