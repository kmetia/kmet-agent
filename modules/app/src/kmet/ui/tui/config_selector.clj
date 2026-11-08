(ns kmet.ui.tui.config-selector
  "The builtin `/config` live resource selector over `kmet.app.resources`.

   Rows are the available resources of the current project run, plus the
   stored entries no provider offers any more (a dimmed `Stored, unavailable`
   group; Space forgets one), grouped by the extension that provides them
   (built-in tools and app resources have their own groups) and labelled
   `type/name`. The screen starts in project scope: project scope cycles a
   resource's delta state (inherit -> enabled -> disabled -> inherit) and dims
   inherited rows; Tab switches to global scope, which toggles the persistent
   selection, the same layout as `kmet plugins`. A write persists through the
   resources service, refreshes only the affected consumers and emits
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

(defn- row
  "One catalog resource as a screen item: its `[type name]` identity, the
   `type/name` label shown inside its provider group, and the catalog's
   provider/scope fields."
  [{:keys [type] resource-name :name :as resource}]
  (assoc resource :id [type resource-name]
         :label (str (name type) "/" resource-name)))

(defn- items
  "Every available resource of every type plus the stored-but-unavailable
   entries for SCOPE, in display order."
  [service scope]
  (vec (mapcat (fn [kind]
                 (concat (map row ((:catalog service) kind))
                         (map row ((:unavailable service) kind scope))))
               [:tool :skill :prompt])))

(defn- provider-group
  "The group heading for a resource's provider: its extension id, the
   built-in tool set, the app, or the stored entries no provider offers."
  [{:keys [provider]}]
  (case (:kind provider)
    :extension (subs (str (:id provider)) 1)
    :builtin "Built-in tools"
    :app "kmet app"
    :unavailable "Stored, unavailable"
    (str (:kind provider))))

(defn- group-rank
  "The heading sort rank: built-ins first, provider extensions next, then the
   app, then stored-unavailable entries."
  [label]
  (case label
    "Built-in tools" 0
    "kmet app" 2
    "Stored, unavailable" 3
    1))

(defn- groups
  "The screen groups: one per provider extension, with built-in, app and
   stored-unavailable resources in their own groups. The headings sort
   built-ins first, the app and unavailable entries last."
  [items]
  (->> (group-by provider-group items)
       (sort-by (fn [[label _]] [(group-rank label) label]))
       (mapv (fn [[label items]]
               {:key label :label label :items (vec items)}))))

(defn- query-match?
  "True when the lower-cased QUERY matches a resource's `type/name` label or
   its provider group heading."
  [item query]
  (or (str/blank? query)
      (str/includes? (str/lower-case (:label item)) query)
      (str/includes? (str/lower-case (provider-group item)) query)))

(defn- group-line
  "Render a provider group heading."
  [theme width group]
  (u/truncate-to-width
   (str "  " (th/fg theme :accent (th/bold (:label group))))
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
  "Render one resource row: cursor, checkbox, name, project annotation and
   the unavailable marker."
  [theme scope width item selected?]
  (let [project? (= scope :project)
        unavailable? (false? (:available? item))
        state (when project? (checkbox-state (:project-state item)))
        enabled (if project? (:selected? item) (:global? item))
        dimmed? (or unavailable?
                    (and project? (= :inherit (:project-state item))))
        cursor (if selected? (th/fg theme :accent "> ") "  ")
        name (:label item)
        name (cond
               dimmed? (th/fg theme :dim name)
               selected? (th/bold name)
               :else name)
        suffix (str (item-suffix theme scope item)
                    (when unavailable?
                      (th/fg theme :dim "  unavailable")))]
    (u/truncate-to-width
     (str cursor "    "
          (grouped/checkbox theme scope state enabled)
          " " name suffix)
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

(defn- forget!
  "Drop ITEM's stored entry in SCOPE — the action for an unavailable row."
  [service scope item]
  (if (= scope :global)
    ((:set-global! service) (:type item) (:name item) :inherit)
    ((:set-project! service) (:type item) (:name item) :inherit)))

(defn- activate!
  "Apply one activation: an available row writes its next selection and
   refreshes the affected consumers; an unavailable row forgets its stored
   entry. Rebuild the rows from the stored state either way."
  [cs service scope screen item]
  (if (false? (:available? item))
    (forget! service scope item)
    (when-let [changed (with-scope service scope (:id item) (next-value scope item))]
      (runtime/refresh-resources! (:engine cs) changed)))
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
    {:write-scope :project
     :can-switch-scope? true
     :rows (or (:rows size) 24)
     :size-ref (when tui (tui/tui-terminal-size-ref tui))
     :request-render! (when tui #(tui/tui-request-render tui true))
     :on-close #(close! cs frame-atom)
     :read (fn [scope] (items service scope))
     :groups groups
     :filter-match query-match?
     :item-identity :id
     :render-group (fn [{:keys [group theme width]}]
                     (group-line theme width group))
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
                      "space cycle inherit/+/- · unavailable forgets"
                      "space toggle · unavailable forgets"))
     :scope-line (fn [scope]
                   (str (if (= scope :global)
                          (cfg/global-settings-path)
                          ".kmet/settings.edn")
                        (when (= scope :project)
                          " · inherited global resources are dimmed")))}))

(defn show-config
  "Open the builtin `/config` resource selector over the engine's live
   `:resources` service, starting in project scope.

   Tab switches global/project scope. Space/Enter cycles the selected row:
   global toggles enabled/disabled, project cycles
   inherit -> enabled -> disabled -> inherit. A stored row whose provider is
   unavailable this run is dimmed; Space/Enter forgets it from the current
   scope. Every write takes effect immediately and persists; Esc closes."
  [cs]
  (let [engine (:engine cs)
        service (or (runtime/resources engine)
                    (throw (ex-info "the composition mounted no :resources service"
                                    {:type ::no-resources-service})))
        frame-atom (atom nil)
        screen (grouped/make-screen (screen-spec cs service frame-atom))]
    (reset! frame-atom screen)
    (dock/mount! cs screen {:focus-target screen})))
