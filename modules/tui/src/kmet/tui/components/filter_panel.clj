(ns kmet.tui.components.filter-panel
  "A filterable list panel: search state, selection navigation, the visible
   window, focus, and the bordered frame around a caller-supplied body.

   The panel provides the mechanics only — item shape, filtering, row
   rendering, chrome text and extra keys stay with the caller (see
   make-panel). The panel owns the `{:search :selected-idx}` keys of the
   caller's state atom; everything else in it is the caller's."
  (:require [clojure.string :as str]
            [kmet.hikap.core :as engine]
            [kmet.reakt.core :as r]
            [kmet.tui.components.input :as input]
            [kmet.tui.hikap :as h]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.keys :as keys]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.theme :as theme]))

;; ─── Pure window/selection helpers ─────────────────────────────────────────

(defn clamp-selected
  "Clamp SELECTED into [0, max(0, n-1)]."
  [selected n]
  (min (max 0 (or selected 0)) (max 0 (dec (or n 0)))))

(defn stepped-selected
  "The selection after one navigation step of DELTA (-1 up, 1 down) from
   SELECTED among N items: wrapping (:wrap) or clamped (:clamp) at the ends.
   The stored selection is clamped to the current range first, so a stale
   index from a shrunk filter cannot escape the list."
  [nav selected delta n]
  (let [idx (clamp-selected selected n)]
    (cond
      (zero? n) 0
      (= :clamp nav) (max 0 (min (dec n) (+ idx delta)))
      :else (mod (+ idx delta) n))))

(defn select-step!
  "Apply one navigation step of DELTA (-1 up, 1 down) to the selection in
   SELECTED-ATOM among N items under NAV (:wrap, or :clamp at the ends) — the
   shared list navigation for the selectors, so a stale index and an empty
   list behave the same everywhere (both land in range). Returns nil."
  [selected-atom nav delta n]
  (swap! selected-atom #(stepped-selected nav % delta n))
  nil)

(defn index-of
  "Index of the first item satisfying PRED, or nil."
  [pred items]
  (first (keep-indexed (fn [i item] (when (pred item) i)) items)))

(defn item-at
  "The item at SELECTED in ITEMS, clamped into range; nil when ITEMS is
   empty."
  [items selected]
  (when (seq items)
    (nth (vec items) (clamp-selected selected (count items)))))

(defn visible-window
  "Return [start-idx end-idx] for a window of MAX-VISIBLE rows centred on
   SELECTED among N items."
  [selected n max-visible]
  (let [mv (max 1 (or max-visible 10))
        n (max 0 (or n 0))
        sel (clamp-selected selected n)
        start (max 0 (min (- sel (quot mv 2)) (max 0 (- n mv))))
        end (min (+ start mv) n)]
    [start end]))

;; ─── Component ─────────────────────────────────────────────────────────────

(defcomponent FilterPanel nil
              [root search-ref state-atom focused? opts]

  (render [this width] (protocols/render (:root this) width))

  (handle-input [_this data]
    (let [kmgr (kb/get-global-keybindings)
          st @state-atom
          filtered ((:items-fn opts) st)
          n (count filtered)
          selected (clamp-selected (:selected-idx st) n)
          extra (:extra-key opts)]
      (cond
        ;; Caller keys win first: an extra key may alias a generic
        ;; tui.select binding, so the specific action must win.
        (and extra (extra state-atom data)) nil

        ;; Ctrl+C — clear a non-empty search before cancelling (:clear-on-ctrl-c?
        ;; panels); otherwise it falls through to tui.select.cancel below
        (and (:clear-on-ctrl-c? opts) (keys/matches-key? data (keys/ctrl "c")))
        (if (str/blank? (:search st))
          (do (when-let [cb (:on-cancel opts)] (cb)) nil)
          (do (swap! state-atom assoc :search "" :selected-idx 0) nil))

        ;; Navigation (pi tui.select.up/down; rebuilds the rows so the
        ;; selection arrow moves)
        (kb/matches-key kmgr data "tui.select.up")
        (do (when (pos? n)
              (swap! state-atom assoc :selected-idx
                     (stepped-selected (:nav opts) selected -1 n)))
            nil)

        (kb/matches-key kmgr data "tui.select.down")
        (do (when (pos? n)
              (swap! state-atom assoc :selected-idx
                     (stepped-selected (:nav opts) selected 1 n)))
            nil)

        ;; Enter — hand the highlighted item to :on-select (a no-op when the
        ;; filter matches nothing, pi's guard on selectedModel)
        (kb/matches-key kmgr data "tui.select.confirm")
        (do (when-let [item (item-at filtered (:selected-idx st))]
              (when-let [cb (:on-select opts)]
                (cb item)))
            nil)

        ;; Escape / Ctrl+C — cancel (pi tui.select.cancel)
        (kb/matches-key kmgr data "tui.select.cancel")
        (do (when-let [cb (:on-cancel opts)] (cb)) nil)

        ;; Everything else — the search input
        :else
        (let [i (h/materialize-ref! _this (:search-ref _this))]
          (protocols/handle-input i data)
          (let [value (input/input-get-value i)]
            (when (not= value (:search st))
              (swap! state-atom (fn [s]
                                  (cond-> (assoc s :search value)
                                    (:search-resets-selection? opts)
                                    (assoc :selected-idx 0)))))
            nil)))))

  (dispose [this]
    (protocols/dispose (:root this))))

;; ─── IFocusable ────────────────────────────────────────────────────────────

(extend-type FilterPanel
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))

;; ─── Rendering helpers ─────────────────────────────────────────────────────

(defn- ->nodes
  "Nodes for a chrome section: nil is empty, a sequence stays a sequence,
   one node becomes a one-element list."
  [x]
  (cond (nil? x) '()
        (sequential? x) (seq x)
        :else (list x)))

(defn- empty-row
  [th text]
  [:text {:key ::empty :padding-x 1 :padding-y 0
          :text (theme/fg th :muted (str "  " text))}])

(defn- panel-body
  [state-atom search-ref focused? border-fn opts]
  (fn [_props]
    (let [th (r/tracked-deref theme/theme-atom)
          st (r/tracked-deref state-atom)
          filtered ((:items-fn opts) st)
          n (count filtered)
          selected (clamp-selected (:selected-idx st) n)
          [start-idx end-idx] (visible-window selected n (:max-visible opts))
          chrome (when-let [f (:chrome opts)] (f th st))
          header (->nodes (:header chrome))
          footer (->nodes (:footer chrome))]
      [:container {}
       [:dynamic-border {:color-fn border-fn}]
       [:spacer {:lines 1}]
       header
       (when (seq header) [:spacer {:lines 1}])
       [:input {:ref search-ref
                :value (:search st)
                :focused? (r/tracked-deref focused?)}]
       [:spacer {:lines 1}]
       (if (zero? n)
         (if-let [render-empty (:render-empty opts)]
           (render-empty th st)
           (empty-row th (:empty-text opts)))
         (map (fn [i]
                ((:render-row opts) th st (nth filtered i) i (= i selected)))
              (range start-idx end-idx)))
       (when (or (pos? start-idx) (< end-idx n))
         [:text {:key ::position :padding-x 1 :padding-y 0
                 :text (theme/fg th :muted
                                 (str "  (" (inc selected) "/" n ")"))}])
       (when (seq footer) [:spacer {:lines 1}])
       footer
       [:spacer {:lines 1}]
       [:dynamic-border {:color-fn border-fn}]])))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-panel
  "Build a mechanics-only filter panel. The caller's state map supplies the
   caller's own keys; the panel adds and owns `:search` and
   `:selected-idx`.

   ITEMS-FN     (fn [state] -> filtered items, display order)
   RENDER-ROW   (fn [theme state item index selected?] -> hiccup node)
   CHROME       (fn [theme state] -> {:header [nodes] :footer [nodes]}),
                optional
   EXTRA-KEY    (fn [state-atom data] -> truthy when it consumed DATA),
                optional; runs before the generic keys
   :on-select   (fn [item]) — Enter over a match
   :on-cancel   (fn []) — Escape / Ctrl+C
   :empty-text  string rendered in the default empty row
   :render-empty (fn [theme state] -> node), overrides :empty-text
   :max-visible viewport height (default 10)
   :nav         :wrap (default) or :clamp
   :clear-on-ctrl-c? Ctrl+C clears a non-empty search before it cancels
                (default false: Ctrl+C cancels via tui.select.cancel)
   :state       initial caller state map (panel keys are added)
   :state-atom  an existing state atom to use instead of a new one
   :search-text initial filter text
   :search-resets-selection? move the selection to the top on a filter
                change (default true; pass false to keep the index and let
                it clamp)"
  [items-fn render-row & {:keys [chrome extra-key on-select on-cancel
                                 empty-text render-empty max-visible nav
                                 state state-atom search-text
                                 search-resets-selection? clear-on-ctrl-c?]}]
  (let [opts {:items-fn items-fn
              :render-row render-row
              :chrome chrome
              :extra-key extra-key
              :on-select on-select
              :on-cancel on-cancel
              :empty-text (or empty-text "No matches")
              :render-empty render-empty
              :max-visible (or max-visible 10)
              :nav (or nav :wrap)
              :search-resets-selection? (not (false? search-resets-selection?))
              :clear-on-ctrl-c? (boolean clear-on-ctrl-c?)}
        base {:search (or search-text "") :selected-idx 0}
        st (if state-atom
             ;; an existing atom still gets the panel-owned keys
             (do (swap! state-atom #(merge base %)) state-atom)
             (atom (merge base state)))
        border-fn (fn [s] (theme/fg (theme/get-current-theme) :accent s))
        focused? (atom false)
        sel (map->FilterPanel
             {:root nil
              :search-ref (engine/ref)
              :state-atom st
              :focused? focused?
              :opts opts})]
    (assoc sel :root (h/root (panel-body st (:search-ref sel) focused? border-fn opts)))))
