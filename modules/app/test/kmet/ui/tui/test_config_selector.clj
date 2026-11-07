(ns kmet.ui.tui.test-config-selector
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.config :as cfg]
            [kmet.app.registry :as registry]
            [kmet.app.resources :as resources]
            [kmet.app.session.runtime :as runtime]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.core :as core]
            [kmet.ui.tui.config-selector :as sut]
            [kmet.ui.tui.dock :as dock]))

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
     :provider provider
     :global? global?
     :project-state project
     :selected? selected?
     :enabled? selected?}))

(defn- fake-service
  "The resources service shape the selector reads, over SETTINGS in
   `cfg/read-resource-settings`' shape. The writers store plain global names
   and `+name`/`-name` project deltas."
  [settings]
  {:catalog (fn [kind] (mapv #(catalog-row settings %) (get base-rows kind)))
   :set-global!
   (fn [kind name enabled?]
     (let [k (settings-key kind)
           current (vec (get-in @settings [:global k]))
           updated (if enabled?
                     (if (some #{name} current) current (conj current name))
                     (filterv #(not= name %) current))]
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

(deftest rows-carry-scope-state
  (let [items (#'sut/items (fake-service (atom {})) :global)]
    (testing "the scope switch comes first"
      (is (= ::sut/scope (:id (first items))))
      (is (= "global" (:value (first items))))
      (is (= ["global" "project"] (:values (first items)))))
    (testing "a global row is an enable/disable checkbox"
      (is (= {:id [:tool "bash"]
              :label "tool/bash"
              :description "built-in bash · effective: disabled"
              :value "disabled"
              :values ["enabled" "disabled"]}
             (second items))))
    (testing "one row per catalog resource"
      (is (= [[:tool "bash"] [:tool "read"] [:skill "review"]]
             (mapv :id (rest items))))))
  (testing "a project row is the inherit/enable/disable tri-state"
    (let [items (#'sut/items (fake-service (atom {})) :project)
          bash (some #(when (= [:tool "bash"] (:id %)) %) items)]
      (is (= "tool/bash (global: off)" (:label bash))
          "the inherited global state is visible without focusing the row")
      (is (= "inherit" (:value bash)))
      (is (= ["inherit" "enabled" "disabled"] (:values bash)))
      (is (= "built-in bash · effective: disabled" (:description bash))))))

(deftest rows-show-the-effective-state-when-the-value-cannot
  (testing "a project override in global scope is marked"
    (is (= "tool/bash (project: off)"
           (:label (#'sut/row :global {:type :tool :name "bash"
                                       :provider {:kind :builtin :id :tool/bash}
                                       :global? true :project-state :disabled
                                       :selected? false})))))
  (testing "a value already equal to the effective state carries no marker"
    (is (= "tool/bash"
           (:label (#'sut/row :global {:type :tool :name "bash"
                                       :provider {:kind :builtin :id :tool/bash}
                                       :global? true :project-state :enabled
                                       :selected? true})))))
  (testing "an inherited project row names its global state"
    (is (= "tool/bash (global: on)"
           (:label (#'sut/row :project {:type :tool :name "bash"
                                        :provider {:kind :builtin :id :tool/bash}
                                        :global? true :project-state :inherit
                                        :selected? true}))))))

(deftest a-normalized-no-op-write-is-not-a-change
  (let [settings (atom {:global {:tools ["bash"]} :project {}})
        service (fake-service settings)]
    (with-redefs [cfg/read-resource-settings (fn [] @settings)]
      (is (nil? (#'sut/with-scope service :global [:tool "bash"] "enabled"))
          "enabling an already-enabled global resource stores nothing")
      (is (= ["bash"] (get-in @settings [:global :tools])))
      (is (= [:tool] (#'sut/with-scope service :global [:tool "bash"] "disabled"))
          "a real write answers its affected type"))))

(deftest selector-writes-and-refreshes
  (let [settings (atom {})
        service (fake-service settings)
        refreshed (atom [])
        focused (atom nil)
        cs {:engine ::engine :dock-stack (atom []) :tui nil}]
    (with-redefs [cfg/read-resource-settings (fn [] @settings)
                  runtime/resources (fn [_] service)
                  runtime/refresh-resources! (fn [_ kinds] (swap! refreshed conj kinds))
                  core/tui-set-focus (fn [_ component] (reset! focused component))
                  core/tui-request-render (fn [_] nil)]
      (sut/show-config cs)
      (let [sl @focused
            frame (dock/top-component @(:dock-stack cs))]
        (is (some? frame) "the selector is docked")
        (is (some? sl) "the settings list takes focus")
        (testing "the scope switch rebuilds the rows as project deltas"
          (core/handle-input sl "\r")
          (is (= "project" (:value (settings-list/settings-list-get-item
                                    sl ::sut/scope))))
          (is (= "inherit" (:value (settings-list/settings-list-get-item
                                    sl [:tool "bash"])))))
        (testing "a project write stores the delta and refreshes tools"
          (settings-list/settings-list-select-item! sl [:tool "bash"])
          (core/handle-input sl "\r")
          (is (= ["+bash"] (get-in @settings [:project :tools])))
          (is (= "enabled" (:value (settings-list/settings-list-get-item
                                    sl [:tool "bash"]))))
          (is (= [[:tool]] @refreshed)))
        (testing "a skill write refreshes only that type"
          (settings-list/settings-list-select-item! sl [:skill "review"])
          (core/handle-input sl "\r")
          (is (= ["+review"] (get-in @settings [:project :skills])))
          (is (= [[:tool] [:skill]] @refreshed)))
        (testing "a global write stores the absolute selection"
          (settings-list/settings-list-select-item! sl ::sut/scope)
          (core/handle-input sl "\r")
          (is (= "global" (:value (settings-list/settings-list-get-item
                                   sl ::sut/scope))))
          (settings-list/settings-list-select-item! sl [:tool "bash"])
          (core/handle-input sl "\r")
          (is (= ["bash"] (get-in @settings [:global :tools])))
          (is (= [[:tool] [:skill] [:tool]] @refreshed)))
        (testing "Esc closes the dock"
          (core/handle-input sl "\u001b")
          (is (empty? @(:dock-stack cs))))))))

(deftest a-no-op-write-does-not-refresh
  (let [settings (atom {})
        service (assoc (fake-service settings) :set-global! (fn [& _] nil))
        refreshed (atom [])
        focused (atom nil)
        cs {:engine ::engine :dock-stack (atom []) :tui nil}]
    (with-redefs [cfg/read-resource-settings (fn [] @settings)
                  runtime/resources (fn [_] service)
                  runtime/refresh-resources! (fn [_ kinds] (swap! refreshed conj kinds))
                  core/tui-set-focus (fn [_ component] (reset! focused component))
                  core/tui-request-render (fn [_] nil)]
      (sut/show-config cs)
      (let [sl @focused]
        (settings-list/settings-list-select-item! sl [:tool "bash"])
        (core/handle-input sl "\r")
        (is (empty? @refreshed) "a no-op write does not refresh consumers")
        (is (= "disabled" (:value (settings-list/settings-list-get-item
                                   sl [:tool "bash"])))
            "the row keeps the stored value, not the unpersisted cycle")))))

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
