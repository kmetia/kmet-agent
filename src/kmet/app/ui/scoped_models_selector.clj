(ns kmet.app.ui.scoped-models-selector
  "Scoped-models selector panel — enable/disable/reorder the models Ctrl+P
   cycles through (pi: ScopedModelsSelectorComponent). Changes are session-
   only until Ctrl+S persists them as settings :enabled-models patterns.
   Enabled-ids semantics: nil = all enabled; vector = explicit ordered list
   of \"provider/id\" full ids (may include ids absent from the catalog —
   they render as [unavailable] rows)."
  (:require [clojure.string :as str]
            [kmet.ai.models :as models]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.loop :as agent]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.model-catalog :as model-catalog]
            [kmet.config :as cfg]
            [kmet.libs.reakt :as r]
            [kmet.tui.hiccup :as h]
            [kmet.tui.components.input :as input]
            [kmet.tui.core :as tui]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.keys :as keys]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]))

(defn- full-id [m]
  (model-catalog/model-full-id m))

(defn- key-or
  "The resolved key text for a keybinding id, or FALLBACK when unbound."
  [id fallback]
  (let [t (app-kb/key-text id)]
    (if (seq t) t fallback)))

;; ─── Enabled-ids helpers (pi scoped-models-selector.ts) ───────────────────

(defn- is-enabled? [enabled-ids id]
  (or (nil? enabled-ids) (boolean (some #{id} enabled-ids))))

(defn- toggle
  "First toggle from null starts with only this id (pi toggle)."
  [enabled-ids id]
  (if (nil? enabled-ids)
    [id]
    (if (some #{id} enabled-ids)
      (vec (remove #(= id %) enabled-ids))
      (conj enabled-ids id))))

(defn- enable-all
  "Enable TARGET-IDS (all when nil); null result when everything is enabled
   (pi enableAll — all-enabled collapses back to null)."
  [enabled-ids all-ids target-ids]
  (if (nil? enabled-ids)
    nil
    (let [targets (or target-ids all-ids)
          result (reduce (fn [acc id] (if (some #{id} acc) acc (conj acc id)))
                         (vec enabled-ids) targets)]
      (if (and (= (count result) (count all-ids))
               (every? (set result) all-ids))
        nil
        result))))

(defn- clear-all
  "Disable TARGET-IDS (enabled ones when nil); from the null state, keeps
   all but the targets (pi clearAll)."
  [enabled-ids all-ids target-ids]
  (if (nil? enabled-ids)
    (if target-ids (vec (remove (set target-ids) all-ids)) [])
    (let [targets (set (or target-ids enabled-ids))]
      (vec (remove targets enabled-ids)))))

(defn- move
  "Move ID by DELTA within the enabled list; unchanged when out of bounds."
  [enabled-ids id delta]
  (if (nil? enabled-ids)
    nil
    (let [idx (first (keep-indexed (fn [i x] (when (= x id) i)) enabled-ids))
          new-idx (when idx (+ idx delta))]
      (if (or (nil? idx) (nil? new-idx) (neg? new-idx)
              (>= new-idx (count enabled-ids)))
        enabled-ids
        (let [result (vec enabled-ids)]
          (assoc result idx (result new-idx) new-idx (result idx)))))))

(defn- sorted-ids
  "Enabled ids first (in order), then the remaining all-ids (pi
   getSortedIds — the unenabled tail keeps catalog order)."
  [enabled-ids all-ids]
  (if (nil? enabled-ids)
    all-ids
    (let [enabled-set (set enabled-ids)]
      (into [] (concat enabled-ids (remove enabled-set all-ids))))))

(defn- fuzzy-match?
  "Subsequence fuzzy match (same as the SelectList filter)."
  [pattern text]
  (let [pl (count pattern) tl (count text)]
    (if (zero? pl)
      true
      (loop [pi 0 ti 0]
        (if (>= pi pl)
          true
          (if (>= ti tl)
            false
            (if (= (nth pattern pi) (nth text ti))
              (recur (inc pi) (inc ti))
              (recur pi (inc ti)))))))))

(defn- filtered-items
  "Sorted visible items for the current search (pi refresh — fuzzy filter
   over \"provider/id\" + model name)."
  [st]
  (let [query (str/lower-case (:search st))
        items (mapv (fn [id]
                      (let [m (get (:model-map st) id)]
                        {:full-id id
                         :model m
                         :enabled (and m (is-enabled? (:enabled-ids st) id))}))
                    (sorted-ids (:enabled-ids st) (:all-ids st)))]
    (if (str/blank? query)
      items
      (vec (filter (fn [{:keys [full-id model]}]
                     (fuzzy-match? query (str/lower-case
                                          (str full-id " " (or (:name model) "")))))
                   items)))))

;; ─── Component ─────────────────────────────────────────────────────────────

(defcomponent ScopedModelsSelector nil
              [root search-ref state-atom
               on-change-atom on-persist-atom on-cancel-atom focused? cache-atom]

  (render [this width] (protocols/render (:root this) width))

  (handle-input [_this data]
    (let [kmgr (kb/get-global-keybindings)
          st @state-atom
          filtered (filtered-items st)
          n (count filtered)]
      (cond
        ;; Provider toggle FIRST: app.models.toggleProvider is ctrl+p (pi) and
        ;; kmet's tui.select.up carries ctrl+p too, so the specific app action
        ;; must win over the generic navigation leg (pi's order works only
        ;; because its select.up is plain "up")
        (kb/matches-key kmgr data "app.models.toggleProvider")
        (let [item (when (pos? n) (nth filtered (min (:selected-idx st) (dec n))))]
          (when-let [m (:model item)]
            (let [all-ids (:all-ids st)
                  provider (:provider m)
                  provider-ids (filterv #(= provider (:provider (get (:model-map st) %)))
                                        all-ids)
                  all-on? (every? #(is-enabled? (:enabled-ids st) %) provider-ids)
                  ids (if all-on?
                        (clear-all (:enabled-ids st) all-ids provider-ids)
                        (enable-all (:enabled-ids st) all-ids provider-ids))]
              (swap! state-atom assoc :enabled-ids ids :dirty true)
              (when-let [cb @on-change-atom] (cb ids))))
          nil)

        ;; Navigation (pi tui.select.up/down — wraps; rebuilds the rows so
        ;; the selection arrow moves, pi updateList)
        (kb/matches-key kmgr data "tui.select.up")
        (do (when (pos? n)
              (swap! state-atom assoc
                     :selected-idx (if (zero? (:selected-idx st))
                                     (dec n)
                                     (dec (:selected-idx st)))))
            nil)

        (kb/matches-key kmgr data "tui.select.down")
        (do (when (pos? n)
              (swap! state-atom assoc
                     :selected-idx (if (= (:selected-idx st) (dec n))
                                     0
                                     (inc (:selected-idx st)))))
            nil)

        ;; Reorder enabled models (pi app.models.reorderUp/Down)
        (or (kb/matches-key kmgr data "app.models.reorderUp")
            (kb/matches-key kmgr data "app.models.reorderDown"))
        (let [delta (if (kb/matches-key kmgr data "app.models.reorderUp") -1 1)
              item (when (pos? n) (nth filtered (min (:selected-idx st) (dec n))))]
          (when (and item (:enabled item) (some? (:enabled-ids st)))
            (let [ids (move (:enabled-ids st) (:full-id item) delta)]
              ;; pi only moves within bounds — an out-of-bounds reorder is a
              ;; no-op: it must not dirty the list or move the selection
              (when (not= ids (:enabled-ids st))
                (swap! state-atom assoc
                       :enabled-ids ids :dirty true
                       :selected-idx (max 0 (min (+ (:selected-idx st) delta) (dec n))))
                (when-let [cb @on-change-atom] (cb ids)))))
          nil)

        ;; Enter — toggle the selected model (pi tui.select.confirm)
        (kb/matches-key kmgr data "tui.select.confirm")
        (let [item (when (pos? n) (nth filtered (min (:selected-idx st) (dec n))))]
          (when item
            (let [ids (toggle (:enabled-ids st) (:full-id item))]
              (swap! state-atom assoc :enabled-ids ids :dirty true)
              (when-let [cb @on-change-atom] (cb ids))))
          nil)

        ;; Enable all — filtered to the search query when active (pi
        ;; app.models.enableAll)
        (kb/matches-key kmgr data "app.models.enableAll")
        (let [targets (when (seq (:search st)) (mapv :full-id filtered))
              ids (enable-all (:enabled-ids st) (:all-ids st) targets)]
          (swap! state-atom assoc :enabled-ids ids :dirty true)
          (when-let [cb @on-change-atom] (cb ids))
          nil)

        ;; Clear all — filtered to the search query when active
        (kb/matches-key kmgr data "app.models.clearAll")
        (let [targets (when (seq (:search st)) (mapv :full-id filtered))
              ids (clear-all (:enabled-ids st) (:all-ids st) targets)]
          (swap! state-atom assoc :enabled-ids ids :dirty true)
          (when-let [cb @on-change-atom] (cb ids))
          nil)

        ;; Save to settings (pi app.models.save)
        (kb/matches-key kmgr data "app.models.save")
        (do (when-let [cb @on-persist-atom] (cb (:enabled-ids st)))
            (swap! state-atom assoc :dirty false)
            nil)

        ;; Ctrl+C — clear the search, or cancel when already empty
        (keys/matches-key? data (keys/ctrl "c"))
        (if (str/blank? (:search st))
          (do (when-let [cb @on-cancel-atom] (cb)) nil)
          (do (swap! state-atom assoc :search "" :selected-idx 0)
              nil))

        ;; Escape — cancel
        (keys/matches-key? data "escape")
        (do (when-let [cb @on-cancel-atom] (cb)) nil)

        ;; Everything else — the search input
        :else
        (let [i (h/materialize-ref! _this (:search-ref _this))]
          (protocols/handle-input i data)
          (let [value (input/input-get-value i)]
            (when (not= value (:search st))
              (swap! state-atom assoc :search value :selected-idx 0))
            nil)))))

  ;; the root's reaction and the tag-owned search field are the selector's
  ;; lifecycle: show-scoped-models-selector unwinds the root on close
  (dispose [this]
    (protocols/dispose (:root this))))

;; ─── Rendering helpers (pi refresh / updateList / getFooterText) ──────────

(defn- footer-text-str
  [st]
  (let [all-ids (:all-ids st)
        enabled-ids (:enabled-ids st)
        all-set (set all-ids)
        enabled-count (if (nil? enabled-ids)
                        (count all-ids)
                        (count (filter all-set enabled-ids)))
        unavailable-count (if (nil? enabled-ids)
                            0
                            (count (remove all-set enabled-ids)))
        count-text (if (nil? enabled-ids)
                     "all enabled"
                     (str enabled-count "/" (count all-ids) " enabled"
                          (when (pos? unavailable-count)
                            (str " · " unavailable-count " unavailable"))))
        parts [(str (key-or "tui.select.confirm" "enter") " toggle")
               (str (key-or "app.models.enableAll" "ctrl+a") " all")
               (str (key-or "app.models.clearAll" "ctrl+x") " clear")
               (str (key-or "app.models.toggleProvider" "ctrl+p") " provider")
               (str (key-or "app.models.reorderUp" "alt+up") "/"
                    (key-or "app.models.reorderDown" "alt+down") " reorder")
               (str (key-or "app.models.save" "ctrl+s") " save")
               count-text]
        base (str "  " (str/join " · " parts))]
    (if (:dirty st)
      (str (theme/dim base) (theme/fg (theme/get-current-theme) :warning " (unsaved)"))
      (theme/dim base))))

(defn- row-elements
  "Visible rows + scroll counter + the selected model's info block (pi
   updateList). Rows are keyed by full id, so a scrolling window reuses the
   rows whose model stays visible."
  [th st filtered selected start-idx end-idx]
  (let [n (count filtered)]
    (concat
     (if (zero? n)
       (list [:text {:key ::empty :padding-x 1 :padding-y 0
                     :text (theme/fg th :muted "  No matching models")}])
       (map (fn [i]
              (let [{:keys [full-id model enabled]} (nth filtered i)
                    is-selected (= i selected)
                    prefix (if is-selected (theme/fg th :accent "→ ") "  ")
                    id-text (if is-selected
                              (theme/fg th :accent (:id model full-id))
                              (:id model full-id))
                    badge (theme/fg th :muted
                                    (if model (str " [" (name (:provider model)) "]")
                                        " [unavailable]"))
                    status (if model
                             (if (nil? (:enabled-ids st))
                               ""
                               (if enabled
                                 (theme/fg th :success " ✓")
                                 (theme/dim " ✗")))
                             (theme/dim " ✗"))]
                [:text {:key full-id :padding-x 1 :padding-y 0
                        :text (str prefix id-text badge status)}]))
            (range start-idx end-idx)))
     (when (or (pos? start-idx) (< end-idx n))
       (list [:text {:key ::position :padding-x 1 :padding-y 0
                     :text (theme/fg th :muted
                                     (str "  (" (inc selected) "/" n ")"))}]))
     (when (pos? n)
       (let [item (nth filtered selected)]
         (concat
          (list [:spacer {:key ::gap :lines 1}])
          (map-indexed (fn [i line]
                         [:text {:key [:info i] :padding-x 1 :padding-y 0
                                 :text line}])
                       (model-catalog/model-info-lines (:model item)))))))))

(defn- scoped-models-body
  "The selector tree as a reactive body (dsl.md): the list window, the
   footer and the selected model's info block re-derive from the state
   atom; the search field is a tag-owned `[:input]` whose text is the
   state's `:search` and whose emphasis is the panel's focus flag.
   BORDER-FN is created once per selector so the border's :color-fn keeps
   identity across passes."
  [state-atom search-ref focused? border-fn]
  (fn [_props]
    (let [th (theme/get-current-theme)
          st (r/tracked-deref state-atom)
          filtered (filtered-items st)
          n (count filtered)
          selected (min (:selected-idx st) (max 0 (dec n)))
          max-visible 8
          start-idx (max 0 (min (- selected (quot max-visible 2))
                                (- n max-visible)))
          end-idx (min (+ start-idx max-visible) n)]
      [:container {}
       [:dynamic-border {:color-fn border-fn}]
       [:spacer {:lines 1}]
       [:text {:padding-x 1 :padding-y 0}
        (theme/fg th :accent (theme/bold "Model Configuration"))]
       [:text {:padding-x 1 :padding-y 0}
        (theme/fg th :muted
                  (str "Session-only. " (key-or "app.models.save" "ctrl+s")
                       " to save to settings."))]
       [:spacer {:lines 1}]
       [:input {:ref search-ref
                :value (:search st)
                :focused? (r/tracked-deref focused?)}]
       [:spacer {:lines 1}]
       (row-elements th st filtered selected start-idx end-idx)
       [:spacer {:lines 1}]
       [:text {:padding-x 1 :padding-y 0 :text (footer-text-str st)}]
       [:spacer {:lines 1}]
       [:dynamic-border {:color-fn border-fn}]])))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-scoped-models-selector
  "Create the scoped-models selector component (pi
   ScopedModelsSelectorComponent). MODELS — all available models; ENABLED-IDS
   — the initial enabled list (nil = all enabled). Callbacks:
   :on-change (fn [ids|nil]) — session-only edits; :on-persist
   (fn [ids|nil]) — Ctrl+S; :on-cancel."
  [models enabled-ids & {:keys [on-change on-persist on-cancel]}]
  (let [model-map (into {} (map (fn [m] [(full-id m) m])) models)
        all-ids (mapv full-id models)
        st (atom {:all-ids all-ids
                  :model-map model-map
                  :enabled-ids enabled-ids
                  :selected-idx 0
                  :search ""
                  :dirty false})
        ;; stable identity across passes — a fresh color-fn per body run
        ;; would decline the border patch and rebuild it every time
        border-fn (fn [s] (theme/fg (theme/get-current-theme) :accent s))
        focused? (atom false)
        sel (map->ScopedModelsSelector
             {:root nil
              :search-ref (h/ref)
              :state-atom st
              :on-change-atom (atom on-change)
              :on-persist-atom (atom on-persist)
              :on-cancel-atom (atom on-cancel)
              :focused? focused?
              :cache-atom (atom nil)})]
    ;; rows/footer are a tracked root body — no refresh call, the state
    ;; change alone re-derives
    (assoc sel :root (h/root (scoped-models-body st (:search-ref sel) focused? border-fn)))))

;; ─── Public helpers ────────────────────────────────────────────────────────

(defn show-scoped-models-selector
  "pi showModelsSelector — /scoped-models opens the enabled-models panel
   for Ctrl+P cycling. Initial enabled ids: session scoped models when set,
   else the settings :enabled-models patterns resolved through
   resolve-model-scope-models (unresolved patterns survive as [unavailable]
   rows), else nil (all enabled). Changes are session-only until Ctrl+S
   writes :enabled-models; the footer provider count updates live."
  [cs]
  (let [sel-atom (atom nil)
        ;; the dock's done restores the editor; dispose unwinds the
        ;; selector's root reaction and foreign input
        close! (fn []
                 ((:done @sel-atom))
                 (when-let [s (:sel @sel-atom)] (protocols/dispose s)))
        available (models/get-available)
        ag @(:agent-state cs)
        session-scoped (vec @(:scoped-models ag))
        patterns (cfg/get-enabled-models-live (:config cs))
        configured-ids (fn []
                         ;; resolve each pattern; unresolved ones survive as
                         ;; [unavailable] rows (pi: no-match diagnostics are
                         ;; appended to the enabled ids)
                         (loop [ps patterns acc [] warnings []]
                           (if-let [p (first ps)]
                             (let [{:keys [model]}
                                   (resolver/parse-model-pattern p available)]
                               (if model
                                 (recur (rest ps) (conj acc (model-catalog/model-full-id model)) warnings)
                                 (recur (rest ps) (conj acc (str p))
                                        (conj warnings
                                              (str "No models match pattern \"" p "\"")))))
                             (do (doseq [w warnings]
                                   (chat-history/chat-history-add-message!
                                    (:chat-history cs) {:role :assistant :content w}))
                                 (vec acc)))))
        initial (cond
                  (seq session-scoped) session-scoped
                  (seq patterns) (configured-ids)
                  :else nil)
        available-ids (set (map model-catalog/model-full-id available))
        update-session-models (fn [enabled-ids]
                                ;; pi updateSessionModels: a non-null list with
                                ;; an enabled available model, not covering all
                                ;; available models, becomes the session scoped
                                ;; list; everything else (null = all enabled,
                                ;; nothing enabled, all enabled) clears it
                                (if (and enabled-ids
                                         (some available-ids enabled-ids)
                                         (not (every? (set enabled-ids)
                                                      (map model-catalog/model-full-id available))))
                                  (agent/set-scoped-models! ag enabled-ids)
                                  (agent/set-scoped-models! ag []))
                                (model-catalog/update-available-provider-count! cs)
                                (tui/tui-request-render (:tui cs)))
        sel (make-scoped-models-selector
             available initial
             :on-change update-session-models
             :on-persist (fn [enabled-ids]
                           (let [all-enabled? (or (nil? enabled-ids)
                                                  (and (= (count enabled-ids) (count available))
                                                       (every? available-ids enabled-ids)))]
                             (cfg/set-enabled-models! (when-not all-enabled? enabled-ids))
                             (chat-history/chat-history-add-message!
                              (:chat-history cs)
                              {:role :assistant
                               :content "Model selection saved to settings."})))
             :on-cancel (fn []
                          (close!)
                          (tui/tui-request-render (:tui cs))))]
    ;; pi: showSelector — the selector replaces the editor dock
    (reset! sel-atom {:done (dock/mount! cs sel) :sel sel})))

(defn scoped-models-get-enabled-ids
  "The selector's current enabled ids (nil = all enabled)."
  [sel]
  (:enabled-ids @(:state-atom sel)))

;; ─── IFocusable — the panel's flag; the tree derives the field's emphasis ──

(extend-type ScopedModelsSelector
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val] (reset! (:focused? this) val)))
