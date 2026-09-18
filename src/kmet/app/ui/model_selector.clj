(ns kmet.app.ui.model-selector
  "Model selection UI (pi: modes/interactive/components/model-selector.ts +
   model-search.ts): the /model selector (pi ModelSelectorComponent —
   visible search filter, wrap-around navigation, current-model ✓, all/scoped
   Tab toggle), and the model-switch helpers shared with cycling and the
   footer sync."
  (:require [clojure.string :as str]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.loop :as agent]
            [kmet.ai.models :as models]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.app.ui.model-catalog :as model-catalog]
            [kmet.config :as cfg]
            [kmet.libs.reakt :as r]
            [kmet.tui.hiccup :as h]
            [kmet.tui.components.input :as input]
            [kmet.tui.core :as tui]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.protocols :as protocols]
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
  (let [ag @(:agent-state cs)
        fdp (:footer-provider cs)
        m (models/get-model @(:provider ag) @(:model ag))
        window (or (:context-window m)
                   (:context-window (:config cs)))]
    (fdp/fdp-set-model! fdp @(:model ag))
    (fdp/fdp-set-provider! fdp @(:provider ag))
    (fdp/fdp-set-thinking! fdp @(:thinking ag))
    (fdp/fdp-set-reasoning! fdp (boolean (:reasoning m)))
    (fdp/fdp-set-context-window! fdp window)
    (swap! (:cfg ag) assoc :context-window window)
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
  (let [ag @(:agent-state cs)
        old-model (models/get-model @(:provider ag) @(:model ag))
        clamped (agent/switch-thinking-level old-model model @(:thinking ag) thinking-level)]
    (reset! (:provider ag) (:provider model))
    (agent/set-model! ag (:id model))
    (cfg/set-default-model! (:provider model) (:id model))
    (agent/set-thinking-level! ag clamped)
    (chat-history/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant
                                             :content (str "Switched to " (fmt-model (:provider model) (:id model))
                                                           (when (not= clamped :off)
                                                             (str " (thinking " (name clamped) ")")))})
    (sync-footer-model! cs)))

;; ─── ModelSelector component (pi ModelSelectorComponent) ───────────────────

(declare filtered-items models-equal?)

(defcomponent ModelSelector nil
              [root search-input state-atom
               on-select-atom on-cancel-atom focused? cache-atom]

  (render [this width] (protocols/render (:root this) width))

  (handle-input [_this data]
    (let [kmgr (kb/get-global-keybindings)
          st @state-atom
          filtered (filtered-items st)
          n (count filtered)]
      (cond
        ;; Tab — toggle the all/scoped scope (pi tui.input.tab; consumed
        ;; even when no scoped models exist, pi parity)
        (kb/matches-key kmgr data "tui.input.tab")
        (do (when (seq (:scoped-models st))
              (let [new-scope (if (= :all (:scope st)) :scoped :all)
                    ;; pi setScope: reset selection to current model position
                    new-active (if (= :scoped new-scope)
                                 (:scoped-models st)
                                 (:all-models st))
                    cur (:current st)
                    idx (or (first (keep-indexed
                                    (fn [i m] (when (models-equal? cur (:model m)) i))
                                    new-active))
                            0)]
                (swap! state-atom assoc :scope new-scope :selected-idx idx)))
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

        ;; Enter — select the highlighted model (pi tui.select.confirm; no-op
        ;; when nothing is filtered — pi guards on selectedModel)
        (kb/matches-key kmgr data "tui.select.confirm")
        (let [item (when (pos? n) (nth filtered (min (:selected-idx st) (dec n))))]
          (when (and item (:model item))
            (when-let [cb @on-select-atom]
              (cb (:model item))))
          nil)

        ;; Escape / Ctrl+C — cancel (pi tui.select.cancel)
        (kb/matches-key kmgr data "tui.select.cancel")
        (do (when-let [cb @on-cancel-atom] (cb)) nil)

        ;; Everything else — the search input (the visible filter, pi)
        :else
        (do (protocols/handle-input search-input data)
            (let [value (input/input-get-value search-input)]
              (when (not= value (:search st))
                (swap! state-atom assoc :search value :selected-idx 0))
              nil)))))

  ;; the root's reaction and the foreign search input are the selector's
  ;; lifecycle: show-model-selector unwinds both when the panel closes
  (dispose [this]
    (protocols/dispose (:search-input this))
    (protocols/dispose (:root this))))

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
  (str (app-kb/key-hint "tui.input.tab" "scope")
       (let [th (theme/get-current-theme)]
         (theme/fg th :muted " (all/scoped)"))))

(defn- row-elements
  "Visible list rows + scroll counter + the selected model's info block (pi
   updateList). Rows are keyed by full id, so a scrolling window reuses the
   rows whose model stays visible."
  [th st filtered selected start-idx end-idx]
  (let [n (count filtered)]
    (concat
     (if (zero? n)
       (list [:text {:key ::empty :padding-x 1 :padding-y 0
                     :text (theme/fg th :muted "  No matching models")}])
       (map (fn [i]
              (let [{:keys [model]} (nth filtered i)
                    is-selected (= i selected)
                    is-current (models-equal? (:current st) model)
                    prefix (if is-selected (theme/fg th :accent "→ ") "  ")
                    id-text (if is-selected
                              (theme/fg th :accent (:id model))
                              (:id model))
                    badge (theme/fg th :muted (str " [" (name (:provider model)) "]"))
                    check (when is-current (theme/fg th :success " ✓"))]
                [:text {:key (model-catalog/model-full-id model)
                        :padding-x 1 :padding-y 0
                        :text (str prefix id-text badge check)}]))
            (range start-idx end-idx)))
     (when (or (pos? start-idx) (< end-idx n))
       (list [:text {:key ::position :padding-x 1 :padding-y 0
                     :text (theme/fg th :muted
                                     (str "  (" (inc selected) "/" n ")"))}]))
     (when (pos? n)
       (let [selected-model (:model (nth filtered selected))]
         (concat
          (list [:spacer {:key ::gap :lines 1}])
          (map-indexed (fn [i line]
                         [:text {:key [:info i] :padding-x 1 :padding-y 0
                                 :text line}])
                       (model-catalog/model-info-lines selected-model))))))))

(defn- model-body
  "The selector tree as a reactive body (dsl.md): the list window, the live
   scope/hint labels and the selected model's info block re-derive from the
   state atom; the search input splices foreign. BORDER-FN is created once
   per selector so the border's :color-fn keeps identity across passes."
  [state-atom search-input border-fn]
  (fn [_props]
    (let [th (theme/get-current-theme)
          st (r/tracked-deref state-atom)
          filtered (filtered-items st)
          n (count filtered)
          selected (min (:selected-idx st) (max 0 (dec n)))
          max-visible 10
          start-idx (max 0 (min (- selected (quot max-visible 2))
                                (- n max-visible)))
          end-idx (min (+ start-idx max-visible) n)]
      [:container {}
       [:dynamic-border {:color-fn border-fn}]
       [:spacer {:lines 1}]
       (when-not (seq (:scoped-models st))
         [:text {:padding-x 1 :padding-y 0}
          (theme/fg th :warning
                    "Only showing models from configured providers. Use /login to add providers.")])
       (when (seq (:scoped-models st))
         [:text {:padding-x 1 :padding-y 0 :text (scope-text-str st)}])
       (when (seq (:scoped-models st))
         [:text {:padding-x 1 :padding-y 0 :text (scope-hint-str)}])
       [:spacer {:lines 1}]
       search-input
       [:spacer {:lines 1}]
       (row-elements th st filtered selected start-idx end-idx)
       [:spacer {:lines 1}]
       [:dynamic-border {:color-fn border-fn}]])))

(defn make-model-selector
  "Create the model selector component (pi ModelSelectorComponent).
   MODELS — all available models (sorted current-first); SCOPED-MODELS — the
   session scoped models (may be empty); CURRENT-MODEL — the model in use
   (marked with ✓ and selected initially). Options: :search (pre-filled
   filter), :on-select (fn [model]), :on-cancel (fn)."
  [models scoped-models current-model & {:keys [search on-select on-cancel]}]
  (let [sorted (vec (sort-models current-model models))
        st (atom {:all-models sorted
                  :scoped-models (vec scoped-models)
                  :scope (if (seq scoped-models) :scoped :all)
                  :current current-model
                  :selected-idx 0
                  :search (or search "")})
        search-input (input/make-input)
        ;; stable identity across passes — a fresh color-fn per body run
        ;; would decline the border patch and rebuild it every time
        border-fn (fn [s] (theme/fg (theme/get-current-theme) :accent s))
        sel (map->ModelSelector
             {:root nil
              :search-input search-input
              :state-atom st
              :on-select-atom (atom on-select)
              :on-cancel-atom (atom on-cancel)
              :focused? (atom false)
              :cache-atom (atom nil)})]
    (when (seq search)
      (input/input-set-value! search-input search))
    ;; initial selection: the current model when present, else the top row
    ;; (pi loadModelsFromSnapshot — the index comes from the ACTIVE list:
    ;; the scoped models when scoped, else all models); a pre-filled
    ;; search moves to the top. The rows are a tracked root body — no
    ;; refresh call, the state change alone re-derives.
    (let [active (if (seq scoped-models) (vec scoped-models) sorted)
          idx (first (keep-indexed (fn [i m] (when (models-equal? current-model m) i))
                                   active))]
      (swap! st assoc :selected-idx (if (seq search) 0 (or idx 0))))
    (assoc sel :root (h/root (model-body st search-input border-fn)))))

;; ─── IFocusable — forward to the search input (IME cursor positioning) ─────

(extend-type ModelSelector
  protocols/IFocusable
  (focused [this] @(:focused? this))
  (set-focused! [this val]
    (reset! (:focused? this) val)
    (protocols/set-focused! (:search-input this) val)))

(defn show-model-selector
  "Model selector (pi ModelSelectorComponent, mounted via showSelector —
   replaces the editor dock): a visible search filter, wrap-around arrow
   navigation, the current model marked with ✓ — bound to Ctrl+L, bare
   /model, and the /model resolution-failure path with SEARCH-TERM
   pre-filled. When session scoped models are set the selector opens
   scoped (Tab toggles all/scoped)."
  ([cs] (show-model-selector cs nil))
  ([cs search-term]
   (let [ag @(:agent-state cs)
         available (models/get-available)]
     (if (empty? available)
       (chat-history/chat-history-add-message! (:chat-history cs)
                                               {:role :assistant
                                                :content "No models available. Configure a provider first (/login)."})
       (let [scoped (vec (keep (fn [id]
                                 (let [slash (str/index-of id "/")]
                                   (when slash
                                     (models/get-model (keyword (subs id 0 slash))
                                                       (subs id (inc slash))))))
                               @(:scoped-models ag)))
             current (models/get-model @(:provider ag) @(:model ag))
             ;; late binding: the callbacks reach the mount's done through
             ;; this atom (pi: done() is created by showSelector)
             sel-atom (atom nil)
             ;; the dock's done restores the editor; dispose unwinds the
             ;; selector's root reaction and foreign input
             close! (fn []
                      ((:done @sel-atom))
                      (when-let [s (:sel @sel-atom)] (protocols/dispose s)))
             sel (make-model-selector
                  available scoped current
                  :search search-term
                  :on-select (fn [m]
                               (close!)
                               (apply-model-switch! cs m nil)
                               (tui/tui-request-render (:tui cs)))
                  :on-cancel (fn []
                               (close!)
                               (tui/tui-request-render (:tui cs))))]
         (reset! sel-atom {:done (dock/mount! cs sel) :sel sel})
         (tui/tui-request-render (:tui cs)))))))

(defn resolve-model-ref
  "/model reference resolution against the cached snapshot (pi
   findExactModelMatch — session scoped models when set, else available)."
  [cs term]
  (resolver/resolve-model-reference
   term
   (model-catalog/scoped-or-available-models @(:agent-state cs))))
