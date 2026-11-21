(ns kmet.ui.tui.thinking-selector
  "Thinking-level selector panel (pi: modes/interactive/components/
   thinking-selector.ts): a bordered panel with a visible search filter and
   one row per level the current model can express — the active level marked
   with a ✓ and the settings default noted \"· default\". Enter applies the
   selected level to the session (pi selectThinkingLevel without persist),
   Ctrl+S also saves it as the default thinking level (pi persist: true),
   Esc cancels. Mounted in place of the editor (pi: showSelector)."
  (:require [clojure.string :as str]
            [kmet.ai.models :as models]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.session.runtime :as runtime]
            [kmet.ui.tui.dock :as dock]
            [kmet.app.config :as cfg]
            [kmet.tui.components.filter-panel :as fp]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.tui.keybindings :as kb]
            [kmet.tui.theme :as theme]))

;; ─── Level metadata (pi THINKING_DESCRIPTIONS) ────────────────────────────

(def level-descriptions
  "One-line description per thinking level (pi: thinking-selector.ts +
   settings-selector.ts THINKING_DESCRIPTIONS)."
  {:off "No reasoning"
   :minimal "Very brief reasoning (~1k tokens)"
   :low "Light reasoning (~2k tokens)"
   :medium "Moderate reasoning (~8k tokens)"
   :high "Deep reasoning (~16k tokens)"
   :xhigh "Extra-high reasoning (~32k tokens)"
   :max "Maximum reasoning"})

(defn level-description
  "Description of LEVEL, or \"\" for unknown levels."
  [level]
  (get level-descriptions level ""))

(defn- filtered-levels
  "LEVELS matching the search query, fuzzy over level name + description."
  [st]
  (let [query (str/lower-case (:search st))]
    (if (str/blank? query)
      (:levels st)
      (vec (filter (fn [level]
                     (fuzzy/subsequence-match? query
                                               (str/lower-case
                                                (str (name level) " " (level-description level)))))
                   (:levels st))))))

(defn- think-row
  "One level row: selection arrow, ✓ mark for the ACTIVE level (pi label
   prefix \"✓ \"), the level name, and the muted description (pi second
   column; the settings default gets a \"· default\" note)."
  [th level selected? current? default? name-width]
  (let [prefix (if selected? (theme/fg th :accent "→ ") "  ")
        mark (if current? (theme/fg th :success "✓ ") "  ")
        name (str (name level)
                  (apply str (repeat (max 0 (- name-width (count (name level))))
                                     \space)))
        name-text (if selected? (theme/fg th :accent name) name)
        desc (theme/fg th :muted
                       (str " " (level-description level)
                            (when default? " · default")))]
    (str prefix mark name-text desc)))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-thinking-selector
  "Create the thinking-level selector (pi ThinkingSelectorComponent).
   LEVELS — the levels the current model can express; CURRENT — the active
   level (marked ✓ and selected initially); DEFAULT — the settings default
   (its description gains a \"· default\" note, pi defaultThinkingLevel).
   Callbacks: :on-select (fn [level]) — Enter, session-level change;
   :on-persist (fn [level]) — app.thinking.save (Ctrl+S), also the
   settings default;
   :on-cancel."
  [levels current default & {:keys [on-select on-persist on-cancel]}]
  (let [levels (vec levels)
        idx (fp/index-of #(= % current) levels)
        name-width (reduce (fn [w l] (max w (count (name l)))) 0 levels)
        render-row-fn (fn [th st level _ selected?]
                        [:text {:key level :padding-x 1 :padding-y 0
                                :text (think-row th level selected?
                                                 (= level (:current st))
                                                 (= level (:default st))
                                                 name-width)}])
        chrome-fn (fn [th _st]
                    {:header [[:text {:padding-x 1 :padding-y 0}
                               (theme/fg th :accent (theme/bold "Thinking Level"))]
                              [:text {:padding-x 1 :padding-y 0}
                               (theme/fg th :muted
                                         (str (app-kb/key-label-or "app.thinking.cycle" "Shift+Tab")
                                              " cycles thinking levels in-session"))]]
                     :footer [[:text {:padding-x 1 :padding-y 0}
                               (theme/dim (str "  " (app-kb/key-label-or "tui.select.confirm" "Enter") " to select · "
                                               (app-kb/key-label-or "app.thinking.save" "Ctrl+S") " to set as default · "
                                               (app-kb/key-label-or "tui.select.cancel" "Esc") " to cancel"))]]})
        extra-key-fn (fn [state-atom data]
                       (when (kb/matches-key (kb/get-global-keybindings) data
                                             "app.thinking.save")
                         (let [st @state-atom]
                           (when-let [level (fp/item-at (filtered-levels st)
                                                        (:selected-idx st))]
                             (when on-persist (on-persist level))))
                         true))]
    (fp/make-panel filtered-levels
                   render-row-fn
                   :chrome chrome-fn
                   :extra-key extra-key-fn
                   :state {:levels levels
                           :current current
                           :default default
                           :selected-idx (or idx 0)}
                   :max-visible 10
                   :empty-text "No matching levels"
                   :clear-on-ctrl-c? true
                   :on-select on-select
                   :on-cancel on-cancel)))

;; ─── Public helpers ────────────────────────────────────────────────────────

(defn thinking-selector-get-levels
  "The selector's level list (tests + callers inspecting a mounted selector)."
  [sel]
  (:levels @(:state-atom sel)))

(defn available-levels
  "Levels the agent's current model can express (pi getAvailableThinkingLevels):
   the model's supported levels, or [:off] when the model is unknown."
  [cs]
  (let [model (runtime/model-record (:engine cs))]
    (if model
      (models/get-supported-thinking-levels model)
      [:off])))

(defn default-thinking-level
  "The settings default thinking level (pi SettingsManager
   getDefaultThinkingLevel): the live settings.edn :thinking, else the
   config snapshot value. Invalid values fall back to :off."
  [cs]
  (let [v (cfg/get-setting-live (runtime/config (:engine cs)) :thinking :off)]
    (if (keyword? v) v :off)))

(defn show-thinking-selector
  "pi showThinkingSelector — mount the thinking-level selector for the
   current model in place of the editor. ON-SELECT (fn [level]) applies a
   session-level change (pi selectThinkingLevel(level, false)); ON-PERSIST
   additionally records the level as the settings default (pi Ctrl+S →
   selectThinkingLevel(level, true)). The caller guards models that only
   express :off (pi opens the selector regardless; kmet's cycle parity —
   \"Current model does not support thinking\")."
  [cs & {:keys [on-select on-persist]}]
  (let [sel-atom (atom nil)
        close! (fn [] (dock/close-atom! cs sel-atom))
        sel (make-thinking-selector
             (available-levels cs)
             (runtime/thinking-level (:engine cs))
             (default-thinking-level cs)
             :on-select (fn [level]
                          (close!)
                          (on-select level))
             :on-persist (fn [level]
                           (close!)
                           (on-persist level))
             :on-cancel close!)]
    (dock/show-panel! cs sel-atom sel)))