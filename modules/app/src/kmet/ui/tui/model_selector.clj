(ns kmet.ui.tui.model-selector
  "Model selection UI (pi: modes/interactive/components/model-selector.ts +
   model-search.ts): the /model selector (pi ModelSelectorComponent —
   visible search filter, wrap-around navigation, current-model ✓, all/scoped
   Tab toggle), and the model-switch helpers shared with cycling and the
   footer sync."
  (:require [clojure.string :as str]
            [kmet.ui.tui.key-hint :as key-hint]
            [kmet.ai.models :as models]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.session.runtime :as runtime]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.footer-data-provider :as fdp]
            [kmet.ui.tui.model-catalog :as model-catalog]
            [kmet.tui.components.filter-panel :as fp]
            [kmet.tui.core :as tui]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.theme :as theme]))

(defn- fmt-model [provider model]
  (str (name provider) ":" model))

(defn sync-footer-model!
  "Push the agent's current model/provider/thinking/reasoning into the footer
   data provider and re-render (the fdp atoms are set once at startup; /model,
   the selector, and cycling must refresh them). The context window follows
   the resolved Model record, falling back to the settings value — synced to
   the agent too, so the proactive compaction check tracks model switches."
  [cs]
  (let [engine (:engine cs)
        fdp (:footer-provider cs)
        model (runtime/model engine)
        provider (runtime/provider engine)
        m (models/get-model provider model)
        window (or (:context-window m)
                   (:context-window (runtime/config engine)))]
    (fdp/fdp-set-model! fdp model)
    (fdp/fdp-set-provider! fdp provider)
    (fdp/fdp-set-thinking! fdp (runtime/thinking-level engine))
    (fdp/fdp-set-reasoning! fdp (boolean (:reasoning m)))
    (fdp/fdp-set-context-window! fdp window)
    (runtime/set-run-options! engine {:context-window window})
    ;; No explicit footer invalidation: the footer's track-deps cover the
    ;; fdp atoms — the setters above schedule the frame reactively.
    (tui/tui-request-render (:tui cs))
    nil))

(defn apply-model-switch!
  "Switch the agent's model (and optional explicit thinking level) with
   position-preserving thinking (the kmet rank rule — agent/switch-thinking-
   level: an old model's highest stays the new model's highest, second-
   highest → second-highest, ...; pi diverges here by keeping the level
   name clamped). Persists the selection as the new default (pi
   settingsManager.setDefaultModelAndProvider), reports the switch in the
   chat, and syncs the footer (pi setModel + showStatus — shared by /model,
   the selector, and cycling)."
  [cs model thinking-level]
  (let [clamped (runtime/switch-model! (:engine cs) model thinking-level)]
    (chat-history/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant
                                             :content (str "Switched to " (fmt-model (:provider model) (:id model))
                                                           (when (not= clamped :off)
                                                             (str " (thinking " (name clamped) ")")))})
    (sync-footer-model! cs)))

;; ─── Helpers (pi sortModels / filterModels / updateList / getScopeText) ────

(defn- models-equal?
  "Same provider + id (pi modelsAreEqual)."
  [a b]
  (and a b (= (:provider a) (:provider b)) (= (:id a) (:id b))))

(defn- sort-models
  "Current model first, then by provider name (pi sortModels)."
  [current models]
  (sort-by (juxt (complement (partial models-equal? current))
                 (comp str name :provider))
           models))

(defn- filtered-items
  "Active-scope models matching the search (pi filterModels — fuzzy over
   \"provider/id\" + model name)."
  [st]
  (let [active (if (= :scoped (:scope st)) (:scoped-models st) (:all-models st))
        query (str/lower-case (:search st))]
    (if (str/blank? query)
      (mapv (fn [m] {:model m}) active)
      (mapv (fn [m] {:model m})
            (fuzzy/fuzzy-filter active query
                                (fn [m] (let [nm (:name m)
                                              name-part (if nm (str " " nm) "")]
                                          (str (name (:provider m)) " "
                                               (name (:provider m)) "/" (:id m) " "
                                               (name (:provider m)) " " (:id m)
                                               name-part))))))))

(defn- scope-text-str
  "Pi getScopeText — the active scope accented."
  [st]
  (let [th (theme/get-current-theme)
        scope (fn [s] (if (= s (:scope st))
                        (theme/fg th :accent (name s))
                        (theme/fg th :muted (name s))))]
    (str (theme/fg th :muted "Scope: ")
         (scope :all) (theme/fg th :muted " | ") (scope :scoped))))

(defn- scope-hint-str
  "Pi getScopeHintText — the Tab scope hint, styled with dim key + muted
   description (pi keyHint + theme.fg muted)."
  []
  (str (key-hint/key-hint "tui.input.tab" "scope")
       (let [th (theme/get-current-theme)]
         (theme/fg th :muted " (all/scoped)"))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-model-selector
  "Create the model selector component (pi ModelSelectorComponent).
   MODELS — all available models (sorted current-first); SCOPED-MODELS — the
   session scoped models (may be empty); CURRENT-MODEL — the model in use
   (marked with ✓ and selected initially). Options: :search (pre-filled
   filter), :on-select (fn [model]), :on-cancel (fn)."
  [models scoped-models current-model & {:keys [search on-select on-cancel]}]
  (let [sorted (vec (sort-models current-model models))
        active (if (seq scoped-models) (vec scoped-models) sorted)
        idx (fp/index-of #(models-equal? current-model %) active)
        selected-idx (if (seq search) 0 (or idx 0))
        render-row-fn (fn [th st item _ selected?]
                        (let [{:keys [model]} item
                              is-current (models-equal? (:current st) model)
                              prefix (if selected? (theme/fg th :accent "→ ") "  ")
                              id-text (if selected?
                                        (theme/fg th :accent (:id model))
                                        (:id model))
                              badge (theme/fg th :muted (str " [" (name (:provider model)) "]"))
                              check (when is-current (theme/fg th :success " ✓"))]
                          [:text {:key (model-catalog/model-full-id model)
                                  :padding-x 1 :padding-y 0
                                  :text (str prefix id-text badge check)}]))
        chrome-fn (fn [th st]
                    (let [scoped? (seq (:scoped-models st))]
                      {:header (keep identity
                                     [(when-not scoped?
                                        [:text {:padding-x 1 :padding-y 0}
                                         (theme/fg th :warning
                                                   "Only showing models from configured providers. Use /login to add providers.")])
                                      (when scoped?
                                        [:text {:padding-x 1 :padding-y 0 :text (scope-text-str st)}])
                                      (when scoped?
                                        [:text {:padding-x 1 :padding-y 0 :text (scope-hint-str)}])])
                       :footer (when-let [item (fp/item-at (filtered-items st)
                                                           (:selected-idx st))]
                                 (model-catalog/model-info-rows (:model item)))}))
        extra-key-fn (fn [state-atom data]
                       ;; Tab toggles the scope; consumed even when no scoped
                       ;; models exist (pi)
                       (when (kb/matches-key (kb/get-global-keybindings) data
                                             "tui.input.tab")
                         (let [st @state-atom]
                           (when (seq (:scoped-models st))
                             (let [new-scope (if (= :all (:scope st)) :scoped :all)
                                   new-active (if (= :scoped new-scope)
                                                (:scoped-models st)
                                                (:all-models st))
                                   idx (or (fp/index-of #(models-equal? (:current st) (:model %))
                                                        new-active)
                                           0)]
                               (swap! state-atom assoc :scope new-scope :selected-idx idx))))
                         true))]
    (fp/make-panel filtered-items
                   render-row-fn
                   :chrome chrome-fn
                   :extra-key extra-key-fn
                   :state {:all-models sorted
                           :scoped-models (vec scoped-models)
                           :scope (if (seq scoped-models) :scoped :all)
                           :current current-model
                           :selected-idx selected-idx}
                   :search-text (or search "")
                   :empty-text "No matching models"
                   :on-select (when on-select
                                (fn [item] (when (:model item) (on-select (:model item)))))
                   :on-cancel on-cancel)))

;; ─── Public helpers ────────────────────────────────────────────────────────

(defn resolve-model-ref
  "/model reference resolution against the cached snapshot (pi
   findExactModelMatch — session scoped models when set, else available)."
  [cs term]
  (resolver/resolve-model-reference
   term
   (model-catalog/scoped-or-available-models (:engine cs))))

(defn show-model-selector
  "Model selector (pi ModelSelectorComponent, mounted via showSelector —
   replaces the editor dock): a visible search filter, wrap-around arrow
   navigation, the current model marked with ✓ — bound to Ctrl+L, bare
   /model, and the /model resolution-failure path with SEARCH-TERM
   pre-filled. When session scoped models are set the selector opens
   scoped (Tab toggles all/scoped)."
  ([cs] (show-model-selector cs nil))
  ([cs search-term]
   (let [available (models/get-available)]
     (if (empty? available)
       (chat-history/chat-history-add-message! (:chat-history cs)
                                               {:role :assistant
                                                :content "No models available. Configure a provider first (/login)."})
       (let [scoped (vec (keep (fn [id]
                                 (let [slash (str/index-of id "/")]
                                   (when slash
                                     (models/get-model (keyword (subs id 0 slash))
                                                       (subs id (inc slash))))))
                               (runtime/scoped-models (:engine cs))))
             current (runtime/model-record (:engine cs))
             sel-atom (atom nil)
             close! (fn [] (dock/close-atom! cs sel-atom))
             sel (make-model-selector
                  available scoped current
                  :search search-term
                  :on-select (fn [m]
                               (close!)
                               (apply-model-switch! cs m nil))
                  :on-cancel close!)]
         (dock/show-panel! cs sel-atom sel))))))