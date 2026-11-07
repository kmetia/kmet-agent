(ns kmet.ui.tui.config-selector
  "The builtin `/config` live resource selector over `kmet.app.resources`.

   Rows are the available resources of the current project run: global scope
   toggles a resource's persistent selection, project scope cycles its delta
   state (inherit -> enabled -> disabled -> inherit). A write persists through
   the resources service, refreshes only the affected consumers and emits
   `:resources-change` with the affected types; it never edits composition,
   remounts providers or re-runs discovery."
  (:require [kmet.app.config :as cfg]
            [kmet.app.resources :as resources]
            [kmet.app.session.runtime :as runtime]
            [kmet.hikap.core :as engine]
            [kmet.libs.theme :as th]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.core :as tui]
            [kmet.tui.hikap :as h]
            [kmet.ui.tui.dock :as dock]))

(def ^:private kinds
  "The selectable resource types, in display order."
  [:tool :skill :prompt])

(defn- provider-label
  "A short description of the provider that made a resource available."
  [{:keys [kind id]}]
  (case kind
    :extension (str "extension " id)
    :builtin (str "built-in " (name id))
    :app "app"
    (str kind)))

(defn- scope-row
  "The first row: the global/project scope switch."
  [scope]
  {:id ::scope
   :label "Scope"
   :description "Global selection applies everywhere; project writes a delta over it"
   :value (name scope)
   :values ["global" "project"]})

(defn- effective-suffix
  "The per-row marker for an effective state the value column does not
   already show: a project override in global scope, or the inherited global
   state in project scope. nil when the value itself is the answer."
  [scope {:keys [global? project-state selected?]}]
  (cond
    (and (= scope :global) (not= global? selected?))
    (if selected? " (project: on)" " (project: off)")

    (and (= scope :project) (= project-state :inherit))
    (if selected? " (global: on)" " (global: off)")))

(defn- row
  "One catalog row in SCOPE: a global enable/disable or a project tri-state,
   with the effective state in the description and, when the value column
   cannot show it, in the label."
  [scope {:keys [type provider project-state global? selected?] resource-name :name :as resource}]
  (merge {:id [type resource-name]
          :label (str (name type) "/" resource-name
                      (effective-suffix scope resource))
          :description (str (provider-label provider)
                            " · effective: "
                            (if selected? "enabled" "disabled"))}
         (if (= scope :global)
           {:value (if global? "enabled" "disabled")
            :values ["enabled" "disabled"]}
           {:value (name project-state)
            :values ["inherit" "enabled" "disabled"]})))

(defn- items
  "The selector's rows: the scope switch, then every available resource of
   every type."
  [service scope]
  (into [(scope-row scope)]
        (mapcat (fn [kind]
                  (map #(row scope %) ((:catalog service) kind))))
        kinds))

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

(defn- refresh-items!
  "Rebuild the rows from the stored state, keeping the search query and
   selection."
  [service scope-atom sl-ref]
  (settings-list/settings-list-set-items!
   @sl-ref (items service @scope-atom) {:preserve-state? true}))

(defn- apply-change!
  "Apply one row change: switch scope, or write the resource and refresh the
   selection-only consumers."
  [cs service scope-atom sl-ref id value]
  (if (= id ::scope)
    (reset! scope-atom (keyword value))
    (when-let [changed (with-scope service @scope-atom id value)]
      (runtime/refresh-resources! (:engine cs) changed)))
  (refresh-items! service scope-atom sl-ref)
  nil)

(defn show-config
  "Open the builtin `/config` resource selector over the engine's live
   `:resources` service.

   Space/Enter cycles the selected row: global toggles enabled/disabled,
   project cycles inherit -> enabled -> disabled -> inherit. Every write takes
   effect immediately and persists; Esc closes."
  [cs]
  (let [engine (:engine cs)
        service (or (runtime/resources engine)
                    (throw (ex-info "the composition mounted no :resources service"
                                    {:type ::no-resources-service})))
        scope-atom (atom :global)
        frame-atom (atom nil)
        close! (fn []
                 (dock/close-atom! cs frame-atom)
                 (when (:tui cs)
                   (tui/tui-request-render (:tui cs))))
        sl-ref (engine/ref)
        frame (h/compile-tree
               [:container {}
                [:text {:padding-x 1 :padding-y 0
                        :text (str (th/bold "Resources") "  "
                                   (th/dim "global/project selection · Enter/Space change · Esc close"))}]
                [:dynamic-border {:color-fn #(th/fg (th/get-current-theme) :accent %)}]
                [:settings-list {:ref sl-ref
                                 :items (items service @scope-atom)
                                 :enable-search true
                                 :max-visible 15
                                 :on-change (fn [id value]
                                              (apply-change! cs service scope-atom sl-ref id value))
                                 :on-escape close!}]
                [:dynamic-border {:color-fn #(th/fg (th/get-current-theme) :accent %)}]])
        sl @sl-ref]
    (reset! frame-atom frame)
    (dock/mount! cs frame {:focus-target sl})))
