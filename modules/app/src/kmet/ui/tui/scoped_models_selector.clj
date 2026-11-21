(ns kmet.ui.tui.scoped-models-selector
  "Scoped-models selector panel — enable/disable/reorder the models Ctrl+P
   cycles through (pi: ScopedModelsSelectorComponent). Changes are session-
   only until Ctrl+S persists them as settings :enabled-models patterns.
   Enabled-ids semantics: nil = all enabled; vector = explicit ordered list
   of \"provider/id\" full ids (may include ids absent from the catalog —
   they render as [unavailable] rows)."
  (:require [clojure.string :as str]
            [kmet.ai.models :as models]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.session.runtime :as runtime]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.model-catalog :as model-catalog]
            [kmet.app.config :as cfg]
            [kmet.tui.components.filter-panel :as fp]
            [kmet.tui.core :as tui]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.theme :as theme]))

(defn- full-id [m]
  (model-catalog/model-full-id m))

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
                     (fuzzy/subsequence-match? query (str/lower-case
                                                      (str full-id " " (or (:name model) "")))))
                   items)))))

;; ─── Component ─────────────────────────────────────────────────────────────

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
        parts [(str (app-kb/key-label-or "tui.select.confirm" "enter") " toggle")
               (str (app-kb/key-label-or "app.models.enableAll" "ctrl+a") " all")
               (str (app-kb/key-label-or "app.models.clearAll" "ctrl+x") " clear")
               (str (app-kb/key-label-or "app.models.toggleProvider" "ctrl+p") " provider")
               (str (app-kb/key-label-or "app.models.reorderUp" "alt+up") "/"
                    (app-kb/key-label-or "app.models.reorderDown" "alt+down") " reorder")
               (str (app-kb/key-label-or "app.models.save" "ctrl+s") " save")
               count-text]
        base (str "  " (str/join " · " parts))]
    (if (:dirty st)
      (str (theme/dim base) (theme/fg (theme/get-current-theme) :warning " (unsaved)"))
      (theme/dim base))))

(defn- change-enabled!
  "Record IDS as the enabled list (plus EXTRA state), mark the list dirty,
   and hand IDS to ON-CHANGE."
  [st-atom on-change ids & [extra]]
  (swap! st-atom merge {:enabled-ids ids :dirty true} extra)
  (when on-change (on-change ids)))

(defn make-scoped-models-selector
  "Create the scoped-models selector component (pi
   ScopedModelsSelectorComponent). MODELS — all available models; ENABLED-IDS
   — the initial enabled list (nil = all enabled). Callbacks:
   :on-change (fn [ids|nil]) — session-only edits; :on-persist
   (fn [ids|nil]) — Ctrl+S; :on-cancel."
  [models enabled-ids & {:keys [on-change on-persist on-cancel]}]
  (let [model-map (into {} (map (fn [m] [(full-id m) m])) models)
        all-ids (mapv full-id models)
        st-atom (atom {:all-ids all-ids
                       :model-map model-map
                       :enabled-ids enabled-ids
                       :selected-idx 0
                       :dirty false})
        change! (partial change-enabled! st-atom on-change)
        render-row-fn (fn [th st item _ selected?]
                        (let [{:keys [full-id model enabled]} item
                              prefix (if selected? (theme/fg th :accent "→ ") "  ")
                              id-text (if selected?
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
        chrome-fn (fn [th st]
                    (let [info (when-let [item (fp/item-at (filtered-items st)
                                                           (:selected-idx st))]
                                 (model-catalog/model-info-rows (:model item)))
                          footer-text [:text {:padding-x 1 :padding-y 0
                                              :text (footer-text-str st)}]]
                      {:header [[:text {:padding-x 1 :padding-y 0}
                                 (theme/fg th :accent (theme/bold "Model Configuration"))]
                                [:text {:padding-x 1 :padding-y 0}
                                 (theme/fg th :muted
                                           (str "Session-only. " (app-kb/key-label-or "app.models.save" "ctrl+s")
                                                " to save to settings."))]]
                       :footer (if (seq info)
                                 (concat info [[:spacer {:lines 1}] footer-text])
                                 [footer-text])}))
        extra-key-fn (fn [state-atom data]
                       (let [kmgr (kb/get-global-keybindings)
                             st @state-atom
                             filtered (filtered-items st)
                             item (fp/item-at filtered (:selected-idx st))]
                         (cond
                             ;; Provider toggle FIRST: app.models.toggleProvider
                             ;; is ctrl+p (pi) and tui.select.up carries ctrl+p
                             ;; too, so the specific app action must win
                           (kb/matches-key kmgr data "app.models.toggleProvider")
                           (do (when-let [m (:model item)]
                                 (let [all-ids (:all-ids st)
                                       provider (:provider m)
                                       provider-ids (filterv #(= provider (:provider (get (:model-map st) %)))
                                                             all-ids)
                                       all-on? (every? #(is-enabled? (:enabled-ids st) %) provider-ids)]
                                   (change! (if all-on?
                                              (clear-all (:enabled-ids st) all-ids provider-ids)
                                              (enable-all (:enabled-ids st) all-ids provider-ids)))))
                               true)

                             ;; Reorder enabled models (pi app.models.reorderUp/Down)
                           (or (kb/matches-key kmgr data "app.models.reorderUp")
                               (kb/matches-key kmgr data "app.models.reorderDown"))
                           (let [delta (if (kb/matches-key kmgr data "app.models.reorderUp") -1 1)]
                             (when (and item (:enabled item) (some? (:enabled-ids st)))
                               (let [ids (move (:enabled-ids st) (:full-id item) delta)]
                                   ;; pi only moves within bounds — an
                                   ;; out-of-bounds reorder is a no-op
                                 (when (not= ids (:enabled-ids st))
                                   (change! ids {:selected-idx
                                                 (max 0 (min (+ (:selected-idx st) delta)
                                                             (dec (count filtered))))}))))
                             true)

                             ;; Enable all — filtered to the search query when
                             ;; active (pi app.models.enableAll)
                           (kb/matches-key kmgr data "app.models.enableAll")
                           (do (change! (enable-all (:enabled-ids st) (:all-ids st)
                                                    (when (seq (:search st)) (mapv :full-id filtered))))
                               true)

                             ;; Clear all — filtered to the search query when active
                           (kb/matches-key kmgr data "app.models.clearAll")
                           (do (change! (clear-all (:enabled-ids st) (:all-ids st)
                                                   (when (seq (:search st)) (mapv :full-id filtered))))
                               true)

                             ;; Save to settings (pi app.models.save)
                           (kb/matches-key kmgr data "app.models.save")
                           (do (when on-persist (on-persist (:enabled-ids st)))
                               (swap! state-atom assoc :dirty false)
                               true)

                           :else nil)))
        toggle-fn (fn [item]
                    (when item
                      (change! (toggle (:enabled-ids @st-atom) (:full-id item)))))]
    (fp/make-panel filtered-items
                   render-row-fn
                   :chrome chrome-fn
                   :extra-key extra-key-fn
                   :state-atom st-atom
                   :max-visible 8
                   :empty-text "No matching models"
                   :clear-on-ctrl-c? true
                   :on-select toggle-fn
                   :on-cancel on-cancel)))

;; ─── Public helpers ────────────────────────────────────────────────────────

(defn show-scoped-models-selector
  "pi showModelsSelector — /scoped-models opens the enabled-models panel
   for Ctrl+P cycling. Initial enabled ids: session scoped models when set,
   else the settings :enabled-models patterns resolved through
   resolve-model-scope-models (unresolved patterns survive as [unavailable]
   rows), else nil (all enabled). Changes are session-only until Ctrl+S
   rechecks availability, omits unavailable ids, and writes :enabled-models;
   the footer provider count updates live."
  [cs]
  (let [sel-atom (atom nil)
        close! (fn [] (dock/close-atom! cs sel-atom))
        available (models/get-available)
        session-scoped (runtime/scoped-models (:engine cs))
        patterns (cfg/get-enabled-models-live (runtime/config (:engine cs)))
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
                                 (vec (distinct acc))))))
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
                                  (runtime/set-scoped-models! (:engine cs) enabled-ids)
                                  (runtime/set-scoped-models! (:engine cs) []))
                                (model-catalog/update-available-provider-count! cs)
                                (tui/tui-request-render (:tui cs)))
        sel (make-scoped-models-selector
             available initial
             :on-change update-session-models
             :on-persist (fn [enabled-ids]
                           (let [available-at-save (models/get-available)
                                 available-ids (set (map model-catalog/model-full-id available-at-save))
                                 save-ids (when enabled-ids
                                            (filterv available-ids enabled-ids))
                                 all-enabled? (or (nil? enabled-ids)
                                                  (= available-ids (set save-ids)))]
                             (cfg/set-enabled-models! (when-not all-enabled? save-ids))
                             (chat-history/chat-history-add-message!
                              (:chat-history cs)
                              {:role :assistant
                               :content "Model selection saved to settings."})))
             :on-cancel close!)]
    (dock/show-panel! cs sel-atom sel)))

(defn scoped-models-get-enabled-ids
  "The selector's current enabled ids (nil = all enabled)."
  [sel]
  (:enabled-ids @(:state-atom sel)))

