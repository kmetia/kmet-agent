(ns kmet.ui.tui.test-config-selector
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.config :as cfg]
            [kmet.app.registry :as registry]
            [kmet.app.resources :as resources]
            [kmet.app.session.runtime :as runtime]
            [kmet.tui.core :as core]
            [kmet.tui.protocols :as protocols]
            [kmet.ui.tui.config-selector :as sut]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.grouped-selector :as grouped]))

(def ^:private base-rows
  "The resources the fake catalog serves, with whether each is selected when
   its type has no global `:resources` entry."
  {:tool [{:type :tool :name "bash"
           :provider {:kind :builtin :id :tool/bash} :default-on? false}
          {:type :tool :name "read"
           :provider {:kind :builtin :id :tool/read} :default-on? true}]
   :skill [{:type :skill :name "review"
            :provider {:kind :extension :id :ext/review} :default-on? true}]
   :prompt []})

(defn- settings-key
  "The `:resources` settings key for KIND."
  [kind]
  (resources/settings-key kind))

(defn- entry-name
  "The resource NAME an entry targets, plain or modifier."
  [entry]
  (when (string? entry)
    (if (and (seq entry) (re-find #"^[+-]" entry))
      (subs entry 1)
      entry)))

(defn- global-selected?
  "The fake global selection for KIND's NAME: a plain list is absolute, then
   modifier entries apply in order."
  [settings kind name]
  (let [entries (get-in @settings [:global (settings-key kind)])]
    (when (sequential? entries)
      (let [modifier? #(and (string? %) (re-find #"^[+-]" %))
            plain (filterv (complement modifier?) entries)
            base (boolean (some #(= name (entry-name %)) plain))]
        (reduce (fn [selected entry]
                  (if (= name (entry-name entry))
                    (case (first entry) \+ true \- false selected)
                    selected))
                base
                entries)))))

(defn- project-state
  "KIND's project override for NAME: the last `+name`/`-name` entry wins."
  [settings kind name]
  (reduce (fn [state entry]
            (if (and (string? entry)
                     (<= 2 (count entry))
                     (= name (subs entry 1)))
              (if (= \+ (first entry)) :enabled :disabled)
              state))
          :inherit
          (get-in @settings [:project (settings-key kind)])))

(defn- catalog-row
  "One row read back from SETTINGS, so a write changes the rows the selector
   rebuilds."
  [settings {:keys [type name provider default-on?]}]
  (let [global-entries (get-in @settings [:global (settings-key type)])
        global? (if (nil? global-entries)
                  default-on?
                  (boolean (some #{name} global-entries)))
        project (project-state settings type name)
        selected? (case project :enabled true :disabled false global?)]
    {:kind :resource
     :type type
     :name name
     :available? true
     :provider provider
     :global? global?
     :project-state project
     :selected? selected?
     :enabled? selected?}))

(defn- unavailable-row
  "One stored-but-unavailable row read back from SETTINGS."
  [settings kind name]
  (let [project (project-state settings kind name)
        global? (boolean (global-selected? settings kind name))
        selected? (case project :enabled true :disabled false global?)]
    {:kind :resource
     :type kind
     :name name
     :available? false
     :provider {:kind :unavailable :id nil}
     :global? global?
     :project-state project
     :selected? selected?
     :enabled? false}))

(defn- stored-unavailable
  "The fake service's stored-but-unavailable rows in SCOPE. Like the real
   service, an invalid project list contributes none."
  [settings kind scope]
  (let [entries (get-in @settings [scope (settings-key kind)])
        project-valid? (and (sequential? entries)
                            (every? #(and (string? %)
                                          (re-find #"^[+-].+" %))
                                    entries))
        names (case scope
                :global (keep entry-name entries)
                :project (when project-valid? (keep entry-name entries)))
        available (set (map :name (get base-rows kind)))]
    (->> names
         (remove nil?)
         distinct
         (remove available)
         (mapv #(unavailable-row settings kind %))
         (sort-by :name)
         vec)))

(defn- fake-service
  "The resources service shape the selector reads, over SETTINGS in
   `cfg/read-resource-settings`' shape. The writers store plain global names
   and `+name`/`-name` project deltas."
  [settings]
  {:catalog (fn [kind] (mapv #(catalog-row settings %) (get base-rows kind)))
   :unavailable (fn [kind scope] (stored-unavailable settings kind scope))
   :set-global!
   (fn [kind name enabled?]
     (let [k (settings-key kind)
           current (vec (get-in @settings [:global k]))
           updated (if (= :inherit enabled?)
                     (filterv #(not= name (entry-name %)) current)
                     (if enabled?
                       (if (some #{name} current) current (conj current name))
                       (filterv #(not= name %) current)))]
       (swap! settings assoc-in [:global k] updated)))
   :set-project!
   (fn [kind name state]
     (let [k (settings-key kind)
           current (vec (get-in @settings [:project k]))
           kept (filterv #(not= name (subs % 1)) current)
           updated (case state
                     :inherit kept
                     :enabled (conj kept (str "+" name))
                     :disabled (conj kept (str "-" name)))
           updated (not-empty updated)]
       (if updated
         (swap! settings assoc-in [:project k] updated)
         (swap! settings update :project dissoc k))))})

(defn- registry-handle
  "A registry handle with `[name owner]` SPECS registered under TAG."
  [tag specs]
  (let [handle (registry/atom-registry)]
    (doseq [[name owner] specs]
      (registry/register! handle tag name {:name name} owner))
    handle))

(defn- dissoc-in
  "Remove PATH's leaf from M, pruning maps left empty by the removal."
  [m [k & ks]]
  (if ks
    (let [child (get m k)]
      (if (map? child)
        (let [child' (dissoc-in child ks)]
          (if (empty? child') (dissoc m k) (assoc m k child')))
        m))
    (dissoc m k)))

(defn- render-lines [screen width]
  (protocols/render screen width))

(defn- item-rows [screen]
  (mapv :item (filter #(= :item (:kind %)) (grouped/screen-rows screen))))

(defn- item-by-id [screen id]
  (first (filter #(= id (:id %)) (item-rows screen))))

(deftest resource-items-form-provider-groups
  (let [service (fake-service (atom {}))
        items (#'sut/items service :project)]
    (testing "the catalog items carry their [type name] identity"
      (is (= [[:tool "bash"] [:tool "read"] [:skill "review"]]
             (mapv :id items)))
      (is (= "tool/bash" (:label (first items))))
      (is (= [:tool "bash"] (:id (#'sut/row (first (:tool base-rows))))))))
  (testing "groups are providers with type/name rows inside"
    (let [groups (#'sut/groups (#'sut/items (fake-service (atom {})) :project))]
      (is (= ["Built-in tools" "ext/review"] (mapv :label groups)))
      (is (= [["tool/bash" "tool/read"] ["skill/review"]]
             (mapv #(mapv :label (:items %)) groups))))))

(deftest groups-split-providers
  (let [items [{:type :tool :name "mcp"
                :provider {:kind :extension :id :ext/mcp-adapter}}
               {:type :skill :name "mcp"
                :provider {:kind :extension :id :ext/mcp-adapter}}
               {:type :skill :name "review"
                :provider {:kind :extension :id :ext/review}}
               {:type :prompt :name "summary"
                :provider {:kind :app :id [:kmet/prompts]}}
               {:type :tool :name "bash"
                :provider {:kind :builtin :id :tool/bash}}]
        groups (#'sut/groups (mapv #(#'sut/row %) items))]
    (is (= ["Built-in tools" "ext/mcp-adapter" "ext/review" "kmet app"]
           (mapv :label groups))
        "one group per extension, extensions alphabetical, built-ins first")
    (is (= [["tool/mcp" "skill/mcp"]
            ["skill/review"]
            ["prompt/summary"]]
           (mapv #(mapv :label (:items %)) (rest groups))))))

(deftest groups-sort-unavailable-last
  (let [items (mapv #(#'sut/row %)
                    [{:type :tool :name "bash"
                      :available? true
                      :provider {:kind :builtin :id :tool/bash}}
                     {:type :skill :name "review"
                      :available? true
                      :provider {:kind :extension :id :ext/review}}
                     {:type :prompt :name "summary"
                      :available? true
                      :provider {:kind :app :id nil}}
                     {:type :tool :name "ghost"
                      :available? false
                      :provider {:kind :unavailable :id nil}}])]
    (is (= ["Built-in tools" "ext/review" "kmet app" "Stored, unavailable"]
           (mapv :label (#'sut/groups items))))))

(deftest screen-shows-groups-and-switches-scope
  (let [settings (atom {})
        service (fake-service settings)
        focused (atom nil)
        cs {:engine ::engine :dock-stack (atom []) :tui nil}]
    (with-redefs [cfg/read-resource-settings (fn [] @settings)
                  runtime/resources (fn [_] service)
                  runtime/refresh-resources! (fn [_ _] nil)
                  core/tui-set-focus (fn [_ component] (reset! focused component))
                  core/tui-request-render (fn [_] nil)]
      (sut/show-config cs)
      (let [screen (dock/top-component @(:dock-stack cs))]
        (is (identical? screen @focused)
            "the grouped screen itself takes focus")
        (testing "the project view is the default and groups by provider"
          (let [lines (render-lines screen 120)]
            (is (= :project (grouped/screen-write-scope screen)))
            (is (some #(str/includes? % "Project Local Resources") lines))
            (is (some #(str/includes? % ".kmet/settings.edn") lines))
            (is (some #(str/includes? % "inherited global (enabled)") lines))
            (is (some #(str/includes? % "inherited global (disabled)") lines))
            (is (some #(str/includes? % "space cycle inherit/+/-") lines))
            (is (some #(str/includes? % "Built-in tools") lines))
            (is (some #(str/includes? % "ext/review") lines))
            (is (some #(str/includes? % "tool/bash") lines))
            (is (some #(str/includes? % "skill/review") lines))
            (is (not-any? #(str/includes? % "Scope") lines)
                "there is no scope row any more")))
        (testing "Tab switches to the global view"
          (protocols/handle-input screen "\t")
          (is (= :global (grouped/screen-write-scope screen)))
          (let [lines (render-lines screen 120)]
            (is (some #(str/includes? % "Global Resources") lines))
            (is (some #(str/includes? % "space toggle") lines))))
        (testing "Tab switches back"
          (protocols/handle-input screen "\t")
          (is (= :project (grouped/screen-write-scope screen))))))))

(deftest navigation-skips-group-headings
  (let [settings (atom {})
        service (fake-service settings)
        cs {:engine ::engine :dock-stack (atom []) :tui nil}]
    (with-redefs [cfg/read-resource-settings (fn [] @settings)
                  runtime/resources (fn [_] service)
                  runtime/refresh-resources! (fn [_ _] nil)
                  core/tui-set-focus (fn [_ _] nil)
                  core/tui-request-render (fn [_] nil)]
      (sut/show-config cs)
      (let [screen (dock/top-component @(:dock-stack cs))
            selected-id #(-> (nth (grouped/screen-rows screen)
                                  (grouped/screen-selected screen))
                             :item :id)]
        (is (= [:tool "bash"] (selected-id)))
        (protocols/handle-input screen "\u001b[B")
        (is (= [:tool "read"] (selected-id)))
        (protocols/handle-input screen "\u001b[B")
        (is (= [:skill "review"] (selected-id))
            "the ext/review group heading is skipped")
        (protocols/handle-input screen "\u001b[B")
        (is (= [:skill "review"] (selected-id)))))))

(deftest search-filters-items-and-groups
  (let [settings (atom {})
        service (fake-service settings)
        cs {:engine ::engine :dock-stack (atom []) :tui nil}]
    (with-redefs [cfg/read-resource-settings (fn [] @settings)
                  runtime/resources (fn [_] service)
                  runtime/refresh-resources! (fn [_ _] nil)
                  core/tui-set-focus (fn [_ _] nil)
                  core/tui-request-render (fn [_] nil)]
      (sut/show-config cs)
      (let [screen (dock/top-component @(:dock-stack cs))]
        (grouped/screen-set-query! screen "review")
        (is (= [[:skill "review"]] (mapv :id (item-rows screen))))
        (let [lines (render-lines screen 120)]
          (is (some #(str/includes? % "ext/review") lines))
          (is (some #(str/includes? % "skill/review") lines))
          (is (not-any? #(str/includes? % "Built-in tools") lines))
          (is (not-any? #(str/includes? % "tool/bash") lines)))
        (grouped/screen-set-query! screen "ext/review")
        (is (= [[:skill "review"]] (mapv :id (item-rows screen)))
            "the provider group is searchable")
        (grouped/screen-set-query! screen "built-in tools")
        (is (= [[:tool "bash"] [:tool "read"]] (mapv :id (item-rows screen)))
            "the built-in group heading is searchable")
        (grouped/screen-set-query! screen "nothing")
        (is (empty? (item-rows screen)))))))

(deftest global-scope-toggles-and-refreshes
  (let [settings (atom {})
        service (fake-service settings)
        refreshed (atom [])
        cs {:engine ::engine :dock-stack (atom []) :tui nil}]
    (with-redefs [cfg/read-resource-settings (fn [] @settings)
                  runtime/resources (fn [_] service)
                  runtime/refresh-resources! (fn [_ kinds] (swap! refreshed conj kinds))
                  core/tui-set-focus (fn [_ _] nil)
                  core/tui-request-render (fn [_] nil)]
      (sut/show-config cs)
      (let [screen (dock/top-component @(:dock-stack cs))]
        (protocols/handle-input screen "\t")
        (grouped/screen-set-query! screen "bash")
        (testing "space enables the default-off tool"
          (protocols/handle-input screen " ")
          (is (= ["bash"] (get-in @settings [:global :tools])))
          (is (= [[:tool]] @refreshed))
          (is (true? (:global? (item-by-id screen [:tool "bash"])))))
        (testing "space again disables it"
          (protocols/handle-input screen " ")
          (is (= [] (get-in @settings [:global :tools])))
          (is (= [[:tool] [:tool]] @refreshed))
          (is (false? (:global? (item-by-id screen [:tool "bash"])))))
        (testing "a skill write refreshes only that type"
          (grouped/screen-set-query! screen "review")
          (protocols/handle-input screen " ")
          (is (= [] (get-in @settings [:global :skills])))
          (is (false? (:global? (item-by-id screen [:skill "review"]))))
          (is (= [[:tool] [:tool] [:skill]] @refreshed)))))))

(deftest project-scope-cycles-inherit-enabled-disabled
  (let [settings (atom {})
        service (fake-service settings)
        refreshed (atom [])
        cs {:engine ::engine :dock-stack (atom []) :tui nil}]
    (with-redefs [cfg/read-resource-settings (fn [] @settings)
                  runtime/resources (fn [_] service)
                  runtime/refresh-resources! (fn [_ kinds] (swap! refreshed conj kinds))
                  core/tui-set-focus (fn [_ _] nil)
                  core/tui-request-render (fn [_] nil)]
      (sut/show-config cs)
      (let [screen (dock/top-component @(:dock-stack cs))]
        (is (= :project (grouped/screen-write-scope screen))
            "the screen starts in project scope")
        (grouped/screen-set-query! screen "bash")
        (testing "inherit -> enabled writes a +name delta"
          (protocols/handle-input screen " ")
          (is (= ["+bash"] (get-in @settings [:project :tools])))
          (is (= :enabled (:project-state (item-by-id screen [:tool "bash"]))))
          (is (= [[:tool]] @refreshed)))
        (testing "enabled -> disabled writes a -name delta"
          (protocols/handle-input screen " ")
          (is (= ["-bash"] (get-in @settings [:project :tools])))
          (is (= :disabled (:project-state (item-by-id screen [:tool "bash"])))))
        (testing "disabled -> inherit removes the delta"
          (protocols/handle-input screen " ")
          (is (nil? (get-in @settings [:project :tools])))
          (is (= :inherit (:project-state (item-by-id screen [:tool "bash"]))))
          (is (= [[:tool] [:tool] [:tool]] @refreshed)))))))

(deftest esc-closes-the-docked-selector
  (let [settings (atom {})
        service (fake-service settings)
        cs {:engine ::engine :dock-stack (atom []) :tui nil}]
    (with-redefs [cfg/read-resource-settings (fn [] @settings)
                  runtime/resources (fn [_] service)
                  runtime/refresh-resources! (fn [_ _] nil)
                  core/tui-set-focus (fn [_ _] nil)
                  core/tui-request-render (fn [_] nil)]
      (sut/show-config cs)
      (let [screen (dock/top-component @(:dock-stack cs))]
        (protocols/handle-input screen "\u001b")
        (is (empty? @(:dock-stack cs)))))))

(deftest stored-unavailable-rows-show-and-forget
  (testing "global scope"
    (let [settings (atom {:global {:tools ["read" "ghost"]} :project {}})
          service (fake-service settings)
          refreshed (atom [])
          cs {:engine ::engine :dock-stack (atom []) :tui nil}]
      (with-redefs [cfg/read-resource-settings (fn [] @settings)
                    runtime/resources (fn [_] service)
                    runtime/refresh-resources! (fn [_ kinds]
                                                 (swap! refreshed conj kinds))
                    core/tui-set-focus (fn [_ _] nil)
                    core/tui-request-render (fn [_] nil)]
        (sut/show-config cs)
        (let [screen (dock/top-component @(:dock-stack cs))]
          (protocols/handle-input screen "\t")
          (grouped/screen-set-query! screen "ghost")
          (let [item (item-by-id screen [:tool "ghost"])]
            (is (some? item))
            (is (false? (:available? item)))
            (is (= "Stored, unavailable"
                   (->> (grouped/screen-rows screen)
                        (filter #(= :group (:kind %)))
                        first :group :label)))
            (is (some #(str/includes? % "unavailable")
                      (render-lines screen 120))))
          (protocols/handle-input screen " ")
          (is (nil? (item-by-id screen [:tool "ghost"])))
          (is (= ["read"] (get-in @settings [:global :tools])))
          (is (empty? @refreshed)
              "forgetting an unavailable row refreshes no consumer")))))
  (testing "project scope"
    (let [settings (atom {:global {} :project {:skills ["+ghost"]}})
          service (fake-service settings)
          refreshed (atom [])
          cs {:engine ::engine :dock-stack (atom []) :tui nil}]
      (with-redefs [cfg/read-resource-settings (fn [] @settings)
                    runtime/resources (fn [_] service)
                    runtime/refresh-resources! (fn [_ kinds]
                                                 (swap! refreshed conj kinds))
                    core/tui-set-focus (fn [_ _] nil)
                    core/tui-request-render (fn [_] nil)]
        (sut/show-config cs)
        (let [screen (dock/top-component @(:dock-stack cs))]
          (grouped/screen-set-query! screen "ghost")
          (is (some? (item-by-id screen [:skill "ghost"])))
          (protocols/handle-input screen " ")
          (is (nil? (item-by-id screen [:skill "ghost"])))
          (is (empty? (get-in @settings [:project :skills])))
          (is (empty? @refreshed)))))))

(deftest a-normalized-no-op-write-is-not-a-change
  (let [settings (atom {:global {:tools ["bash"]} :project {}})
        service (fake-service settings)]
    (with-redefs [cfg/read-resource-settings (fn [] @settings)]
      (is (nil? (#'sut/with-scope service :global [:tool "bash"] "enabled"))
          "enabling an already-enabled global resource stores nothing")
      (is (= ["bash"] (get-in @settings [:global :tools])))
      (is (= [:tool] (#'sut/with-scope service :global [:tool "bash"] "disabled"))
          "a real write answers its affected type"))))

(deftest a-no-op-write-does-not-refresh
  (let [settings (atom {})
        service (assoc (fake-service settings) :set-global! (fn [& _] nil))
        refreshed (atom [])
        cs {:engine ::engine :dock-stack (atom []) :tui nil}]
    (with-redefs [cfg/read-resource-settings (fn [] @settings)
                  runtime/resources (fn [_] service)
                  runtime/refresh-resources! (fn [_ kinds] (swap! refreshed conj kinds))
                  core/tui-set-focus (fn [_ _] nil)
                  core/tui-request-render (fn [_] nil)]
      (sut/show-config cs)
      (let [screen (dock/top-component @(:dock-stack cs))]
        (protocols/handle-input screen "\t")
        (grouped/screen-set-query! screen "bash")
        (protocols/handle-input screen " ")
        (is (empty? @refreshed)
            "a no-op write does not refresh consumers")
        (is (false? (:global? (item-by-id screen [:tool "bash"])))
            "the row keeps the stored value, not the unpersisted toggle")))))

(deftest selector-writes-through-the-real-resources-service
  (let [global (atom nil)
        project (atom nil)
        service (resources/service
                 {:tools (registry-handle :kmet/tool
                                          [["bash" :tool/bash] ["read" :tool/read]])
                  :skills (registry-handle :kmet/skill [])
                  :prompts (registry-handle :kmet/prompt [])})]
    (with-redefs [cfg/read-global-settings-map (fn [] @global)
                  cfg/read-project-settings-map (fn [] @project)
                  cfg/save-setting! (fn [path value]
                                      (swap! global assoc-in path value)
                                      value)
                  cfg/save-project-setting! (fn [path value]
                                              (swap! project assoc-in path value)
                                              value)
                  cfg/remove-setting! (fn [path]
                                        (swap! global dissoc-in path)
                                        nil)
                  cfg/remove-project-setting! (fn [path]
                                                (swap! project dissoc-in path)
                                                nil)]
      (testing "a global write persists and reports the type"
        (is (= [:tool] (#'sut/with-scope service :global [:tool "read"] "disabled")))
        (is (= ["-read"] (get-in @global [:resources :tools]))))
      (testing "the same write again is a no-op"
        (is (nil? (#'sut/with-scope service :global [:tool "read"] "disabled")))
        (is (= ["-read"] (get-in @global [:resources :tools]))))
      (testing "a project write persists the delta"
        (is (= [:tool] (#'sut/with-scope service :project [:tool "read"] "disabled")))
        (is (= ["-read"] (get-in @project [:resources :tools])))))))
