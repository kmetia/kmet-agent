(ns kmet.ui.tui.test-tools-selector
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.config :as cfg]
            [kmet.app.session.runtime :as runtime]
            [kmet.tui.core :as core]
            [kmet.ui.tui.dock :as dock]
            [kmet.ui.tui.tools-selector :as sut]))

(deftest rows-reflect-the-live-selection
  (testing "one sorted row per model-facing tool, marked enabled by the set"
    (is (= [{:id "bash"
             :label "bash"
             :value "disabled"
             :values ["enabled" "disabled"]
             :description "Run a command"}
            {:id "read"
             :label "read"
             :value "enabled"
             :values ["enabled" "disabled"]
             :description "Read a file"}]
           (#'sut/tool-rows [{:name "read" :description "Read a file"}
                             {:name "bash" :description "Run a command"}]
                            #{"read"})))))

(deftest selector-mounts-the-live-table
  (let [cs {:engine ::engine :dock-stack (atom []) :tui nil}
        focused (atom nil)
        calls (atom [])
        writes (atom [])]
    (with-redefs [runtime/tool-table
                  (fn [_] {"read" {:name "read" :description "Read a file"}
                           "bash" {:name "bash" :description "Run a command"}})
                  runtime/enabled-tool-names (fn [_] #{"read"})
                  runtime/set-active-tools! (fn [_ names] (swap! calls conj names))
                  cfg/save-setting! (fn [& args] (swap! writes conj args))
                  cfg/save-project-setting! (fn [& args] (swap! writes conj args))
                  core/tui-set-focus (fn [_ component] (reset! focused component))
                  core/tui-request-render (fn [_])]
      (sut/show-tools-selector cs)
      (let [frame (dock/top-component @(:dock-stack cs))
            sl @focused
            rendered (mapv #(str/replace % #"\u001b\[[0-9;]*[a-zA-Z]" "")
                           (core/render frame 80))]
        (is (some? frame) "the selector is docked")
        (is (some? sl) "the settings list takes focus")
        (is (some #(str/includes? % "Tools") rendered))
        (is (some #(str/includes? % "read") rendered))
        (testing "Enter toggles the selected row through the engine"
          (core/handle-input sl "\r")
          (is (= [["bash" "read"]] @calls))
          (core/handle-input sl "\r")
          (is (= [["bash" "read"] ["read"]] @calls)
              "a second toggle removes the row again")
          (is (empty? @writes) "toggling writes no settings"))
        (testing "Esc closes the dock"
          (core/handle-input sl "\u001b")
          (is (empty? @(:dock-stack cs))))))))

(deftest long-description-is-capped-at-three-lines
  (testing "a model-facing tool description cannot grow the dialog past three
            description lines"
    (let [cs {:engine ::engine :dock-stack (atom []) :tui nil}
          focused (atom nil)
          long-desc "line one\nline two\nline three\nline four\nline five"]
      (with-redefs [runtime/tool-table
                    (fn [_] {"bash" {:name "bash" :description long-desc}})
                    runtime/enabled-tool-names (fn [_] #{"bash"})
                    core/tui-set-focus (fn [_ component] (reset! focused component))
                    core/tui-request-render (fn [_])]
        (sut/show-tools-selector cs)
        (let [frame (dock/top-component @(:dock-stack cs))
              rendered (mapv #(str/replace % #"\u001b\[[0-9;]*[a-zA-Z]" "")
                             (core/render frame 80))]
          (is (some #(str/includes? % "line three") rendered))
          (is (not-any? #(str/includes? % "line four") rendered)))))))
