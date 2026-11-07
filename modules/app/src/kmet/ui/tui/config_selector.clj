(ns kmet.ui.tui.config-selector
  "The builtin `/config` live resource selector over `kmet.app.resources`.

   Rows are the available resources of the current project run, grouped into
   the Tools, Skills and Prompts sections. Global scope toggles a resource's
   persistent selection; project scope cycles its delta state
   (inherit -> enabled -> disabled -> inherit) and dims inherited rows, the
   same Tab-to-switch-scope layout as `kmet plugins`. A write persists through
   the resources service, refreshes only the affected consumers and emits
   `:resources-change` with the affected types; it never edits composition,
   remounts providers or re-runs discovery."
  (:require [clojure.string :as str]
            [kmet.app.config :as cfg]
            [kmet.app.resources :as resources]
            [kmet.app.session.runtime :as runtime]
            [kmet.libs.theme :as th]
            [kmet.tui.core :as tui]
            [kmet.tui.utils :as u]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.grouped-selector :as grouped]))

(def ^:private kinds
  "The selectable resource types, in section order."
  [:tool :skill :prompt])

(def ^:private section-labels
  "The heading for each resource type."
  {:tool "Tools" :skill "Skills" :prompt "Prompts"})

(defn- provider-label
  "A short description of the provider that made a resource available."
  [{:keys [kind id]}]
  (case kind
    :extension (str "extension " id)
    :builtin (str "built-in " (name id))
    :app "app"
    (str kind)))

(defn- row
  "One catalog resource as a screen item: its `[type name]` identity, the
   name shown in its section, and the catalog's provider/scope fields."
  [{:keys [type name] :as resource}]
  (assoc resource :id [type name] :label name))

(defn- items
  "Every available resource of every type, in section order."
  [service]
  (vec (mapcat (fn [kind]
                 (map row ((:catalog service) kind)))
               kinds)))

(defn- groups
  "The screen sections: one per resource type with at least one item."
  [items]
  (keep (fn [kind]
          (let [section-items (filterv #(= kind (:type %)) items)]
            (when (seq section-items)
              {:key kind
               :label (get section-labels kind (str/capitalize (name kind)))
               :items (vec (sort-by :name section-items))})))
        kinds))

(defn- query-match?
  "True when the lower-cased QUERY matches a resource's name, type or
   provider."
  [item query]
  (or (str/blank? query)
      (str/includes? (str/lower-case (:name item)) query)
      (str/includes? (name (:type item)) query)
      (str/includes? (str/lower-case (provider-label (:provider item))) query)))

(defn- section-line
  "Render a resource-type section heading (the indented type heading the old
   config screen used for its resource subgroups)."
  [theme width group]
  (u/truncate-to-width
   (str "    " (th/fg theme :muted (:label group)))
   width ""))

(defn- checkbox-state
  "Map a project delta state to the shared checkbox state; inherit keeps the
   effective global checkbox."
  [project-state]
  (case project-state :enabled :load :disabled :unload nil))

(defn- item-suffix
  "The project-scope override annotation after a resource name, worded the
   way the old config screen worded its load/unload deltas."
  [theme scope item]
  (when (= scope :project)
    (case (:project-state item)
      :enabled (th/fg theme :muted "  project load")
      :disabled (th/fg theme :muted "  project unload")
      (th/fg theme :dim "  inherited global"))))

(defn- item-line
  "Render one resource row: cursor, checkbox, name and project annotation."
  [theme scope width item selected?]
  (let [project? (= scope :project)
        state (when project? (checkbox-state (:project-state item)))
        enabled (if project? (:selected? item) (:global? item))
        dimmed? (and project? (= :inherit (:project-state item)))
        cursor (if selected? (th/fg theme :accent "> ") "  ")
        name (:label item)
        name (cond
               dimmed? (th/fg theme :dim name)
               selected? (th/bold name)
               :else name)]
    (u/truncate-to-width
     (str cursor "    "
          (grouped/checkbox theme scope state enabled)
          " " name (item-suffix theme scope item))
     width "...")))

(defn- stored-entries
  "The raw stored entry list for KIND in SCOPE, used to tell a real write from
   a normalized no-op."
  [scope kind]
  (get-in (cfg/read-resource-settings) [scope (resources/settings-key kind)]))

(defn- with-scope
  "Persist one row's change in SCOPE. Returns `[kind]` when the stored
   selection changed, nil when the write was a normalized no-op."
  [service scope [kind resource-name] value]
  (let [before (stored-entries scope kind)]
    (if (= scope :global)
      ((:set-global! service) kind resource-name (= "enabled" value))
      ((:set-project! service) kind resource-name (keyword value)))
    (when (not= before (stored-entries scope kind))
      [kind])))

(defn- next-value
  "The value one activation applies to ITEM in SCOPE: the opposite global
   toggle, or the next project delta state."
  [scope item]
  (if (= scope :global)
    (if (:global? item) "disabled" "enabled")
    (case (:project-state item)
      :inherit "enabled"
      :enabled "disabled"
      :disabled "inherit")))

(defn- activate!
  "Apply one activation: write the resource in SCOPE, refresh only the
   affected consumers, then rebuild the rows from the stored state."
  [cs service scope screen item]
  (when-let [changed (with-scope service scope (:id item) (next-value scope item))]
    (runtime/refresh-resources! (:engine cs) changed))
  (grouped/screen-refresh! screen))

(defn- close!
  "Close the docked selector and request a repaint."
  [cs frame-atom]
  (dock/close-atom! cs frame-atom)
  (when-let [tui (:tui cs)]
    (tui/tui-request-render tui)))

(defn- screen-spec
  "The grouped-selector spec for the `/config` screen over SERVICE."
  [cs service frame-atom]
  (let [tui (:tui cs)
        size (when tui (tui/tui-terminal-size tui))]
    {:write-scope :global
     :can-switch-scope? true
     :rows (or (:rows size) 24)
     :size-ref (when tui (tui/tui-terminal-size-ref tui))
     :request-render! (when tui #(tui/tui-request-render tui true))
     :on-close #(close! cs frame-atom)
     :read (fn [_scope] (items service))
     :groups groups
     :filter-match query-match?
     :item-identity :id
     :render-group (fn [{:keys [group theme width]}]
                     (section-line theme width group))
     :render-item (fn [{:keys [item scope theme width selected?]}]
                    (item-line theme scope width item selected?))
     :on-activate (fn [{:keys [screen item scope]}]
                    (activate! cs service scope screen item))
     :title (fn [scope]
              (if (= scope :project)
                "Project Local Resources"
                "Global Resources"))
     :action-hint (fn [scope]
                    (if (= scope :project)
                      "space cycle inherit/+/-"
                      "space toggle"))
     :scope-line (fn [scope]
                   (str (if (= scope :global)
                          (cfg/global-settings-path)
                          ".kmet/settings.edn")
                        (when (= scope :project)
                          " · inherited global resources are dimmed")))}))

(defn show-config
  "Open the builtin `/config` resource selector over the engine's live
   `:resources` service.

   Tab switches global/project scope. Space/Enter cycles the selected row:
   global toggles enabled/disabled, project cycles
   inherit -> enabled -> disabled -> inherit. Every write takes effect
   immediately and persists; Esc closes."
  [cs]
  (let [engine (:engine cs)
        service (or (runtime/resources engine)
                    (throw (ex-info "the composition mounted no :resources service"
                                    {:type ::no-resources-service})))
        frame-atom (atom nil)
        screen (grouped/make-screen (screen-spec cs service frame-atom))]
    (reset! frame-atom screen)
    (dock/mount! cs screen {:focus-target screen})))
