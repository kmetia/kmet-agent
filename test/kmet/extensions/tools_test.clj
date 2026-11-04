(ns kmet.extensions.tools-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.keybindings :as app-kb]
            [kmet.libs.theme :as theme]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.keybindings :as kb]))

(defonce ^:private extension
  (delay
    (load-file (str (fs/path (fs/cwd) "extensions/tools.clj")))
    (find-ns 'kmet.extensions.tools)))

(defn- ext-fn
  "The extension's private var SYM, invoked with ARGS."
  [sym & args]
  (apply (ns-resolve @extension sym) args))

(t/deftest test-selection-rows-carry-provenance
  (t/is (= [{:id "bash"
             :label "bash [global]"
             :value "disabled"
             :values ["enabled" "disabled"]}
            {:id "read"
             :label "read [project]"
             :value "enabled"
             :values ["enabled" "disabled"]}]
           (ext-fn 'selection-items
                   {:all-tools [{:name "read"} {:name "bash"}]
                    :enabled-tools #{"read"}
                    :selection {:origin {"bash" :global "read" :project}}}))))

(t/deftest test-scope-line-names-the-target-and-counts
  (let [state (atom {:scope :project
                     :selection {:entries {:global ["read"]
                                           :project ["+bash" "-write"]}
                                 :paths {:global "/home/me/.kmet/agent/settings.edn"
                                         :project "/repo/.kmet/settings.edn"}}})]
    (t/is (= "Project · /repo/.kmet/settings.edn · global 1 · project 2"
             (ext-fn 'scope-line state)))
    (swap! state assoc :scope :global)
    (t/is (= "Global · /home/me/.kmet/agent/settings.edn · global 1 · project 2"
             (ext-fn 'scope-line state)))))

(t/deftest test-toggle-marks-the-session-origin
  (let [state (atom {:all-tools [{:name "read"} {:name "bash"}]
                     :selection {:origin {"read" :default
                                          "bash" :global}}})]
    (ext-fn 'mark-session-origin! state)
    (t/is (= {"read" :session "bash" :session}
             (get-in @state [:selection :origin])))))

(t/deftest test-tab-switches-the-write-scope
  (let [state (atom {:all-tools [{:name "read"}]
                     :enabled-tools #{"read"}
                     :scope :global
                     :selection {:entries {:global [] :project []}
                                 :paths {:global "/g/settings.edn"
                                         :project "/p/settings.edn"}}})
        dialog (ext-fn 'tools-dialog
                       {} state nil (theme/get-theme "dark")
                       (kb/make-keybindings-manager app-kb/all-keybinding-defs {})
                       (fn [_]))]
    (t/is (some #(str/includes? % "tab scope") ((:render dialog) 80)))
    ((:handle-input dialog) "	")
    (t/is (= :project (:scope @state)))
    (t/is (some #(str/includes? % "Project · /p/settings.edn")
                ((:render dialog) 80)))
    ((:dispose dialog))))

(t/deftest test-save-selection-writes-the-scope-then-clears-the-runtime
  (let [writes (atom [])
        state (atom {:all-tools [{:name "bash"} {:name "read"}]
                     :enabled-tools #{"read"}
                     :scope :project
                     :selection {:origin {"bash" :default "read" :default}}})
        settings (settings-list/make-settings-list
                  (ext-fn 'selection-items @state))
        api {:session {:append-entry! (fn [type data]
                                        (swap! writes conj [:branch type data]))}
             :set-tool-enabled! (fn [name enabled opts]
                                  (swap! writes conj [:setting name enabled opts]))
             :set-active-tools (fn [names]
                                 (swap! writes conj [:active names]))
             :tool-selection (fn []
                               {:scope :project
                                :entries {:global [] :project ["-bash" "+read"]}
                                :enabled #{"read"}
                                :origin {"bash" :project "read" :project}})
             :ui {:notify (fn [message type]
                            (swap! writes conj [:notify message type]))}}]
    (ext-fn 'save-selection! api state settings)
    (t/is (= [[:setting "bash" false {:scope :project}]
              [:setting "read" true {:scope :project}]
              [:active nil]
              [:branch "tools-config" {:enabled-tools ["read"]}]
              [:notify "Saved project tool selection" :info]]
             @writes))
    (t/is (= #{"read"} (:enabled-tools @state)))
    (t/is (= "project" (name (get-in @state [:selection :origin "bash"])))
          "the dialog re-reads the database after saving")
    (t/is (= "enabled" (:value (settings-list/settings-list-get-item settings "read"))))))
