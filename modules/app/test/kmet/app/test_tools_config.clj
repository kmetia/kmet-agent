(ns kmet.app.test-tools-config
  "The legacy `kmet config` tool rows and their settings writers."
  (:require [clojure.test :as t]
            [kmet.app.config :as cfg]
            [kmet.app.tools.config :as tools-config]
            [kmet.app.tools.registry :as registry]))

(defn- with-settings
  "Run F with the settings maps replaced by atoms, so a writer test inspects
   exactly what it saved."
  [global project f]
  (let [g (atom global)
        p (atom project)]
    (with-redefs [cfg/read-global-settings-map (fn [] (when (some? @g) @g))
                  cfg/read-project-settings-map (fn [] (when (some? @p) @p))
                  cfg/save-setting! (fn [path value]
                                      (swap! g assoc (first path) value))
                  cfg/save-project-setting! (fn [path value]
                                              (swap! p assoc (first path) value))]
      (f g p))))

;; ─── Static tool rows beyond the built-ins ─────────────────────────────────

(defn- row-of
  [items name]
  (first (filter #(= name (get-in % [:metadata :display-name])) items)))

(defn- tool-item
  "The minimal tool row the toggle writers read: a display name and the
   setting the row writes."
  [name setting]
  {:resource-type :tools
   :metadata {:display-name name :setting setting}})

(t/deftest test-tool-items-builtins-keep-default-tools
  (let [items (tools-config/tool-items nil nil)]
    (t/is (= (vec registry/builtin-tool-names)
             (mapv #(get-in % [:metadata :display-name]) items)))
    (t/is (every? #(= :default-tools (get-in % [:metadata :setting])) items))))

(t/deftest test-tool-items-names-non-builtins
  (let [items (tools-config/tool-items {:enabled-tools ["-mcp_tool" "+bash"]}
                                       {:enabled-tools ["+mcp_other"]}
                                       nil nil)
        mcp (row-of items "mcp_tool")
        other (row-of items "mcp_other")]
    (t/is (some? mcp) "a non-built-in :enabled-tools name gets a row")
    (t/is (= :enabled-tools (get-in mcp [:metadata :setting])))
    (t/is (false? (:enabled mcp)))
    (t/is (true? (:enabled other)))
    (t/is (= :project (get-in other [:metadata :scope]))
          "a project-only name is grouped as a project setting")))

(t/deftest test-tool-items-extension-meta-projection
  (let [metadata {:origin :package :scope :user :source "pkg" :base-dir "/pkg"}
        items (tools-config/tool-items
               nil nil nil
               [{:meta {:tools [{:name "ext_tool" :doc "x"}
                                {:name ""}
                                {:name 42}
                                "junk"
                                {:no-name true}]}
                 :metadata metadata}
                {:meta {:tools "not-a-list"} :metadata metadata}
                {:meta "opaque" :metadata metadata}
                {:meta nil :metadata metadata}])
        row (row-of items "ext_tool")]
    (t/is (some? row) "only the properly filled entry produces a row")
    (t/is (= :package (get-in row [:metadata :origin]))
          "the row carries the extension's group metadata")
    (t/is (= :enabled-tools (get-in row [:metadata :setting])))
    (t/is (true? (:enabled row))
          "no setting means the projected tool is on (deltas, not snapshots)")
    (t/is (nil? (row-of items "")))
    (t/is (nil? (row-of items "junk")))
    (t/is (= 1 (count (filter #(= :enabled-tools (get-in % [:metadata :setting]))
                              items)))
          "malformed/absent :meta contributes no rows")))

(t/deftest test-tool-items-plain-enabled-list-is-absolute
  (let [items (tools-config/tool-items
               {:enabled-tools ["one"]} nil nil
               [{:meta {:tools [{:name "one"} {:name "two"}]} :metadata {}}])]
    (t/is (true? (:enabled (row-of items "one"))))
    (t/is (false? (:enabled (row-of items "two")))
          "a plain :enabled-tools list selects absolutely")))

(t/deftest test-enabled-tool-writers
  (with-settings nil nil
    (fn [global project]
      (let [item (tool-item "mcp_tool" :enabled-tools)]
        (t/is (true? (tools-config/apply-tool-toggle! item false)))
        (t/is (= ["-mcp_tool"] (:enabled-tools @global)))
        (t/is (= :inherit (tools-config/tool-override-state-of item)))
        (t/is (true? (tools-config/apply-tool-toggle! item true)))
        (t/is (nil? (:enabled-tools @global)))
        (t/is (true? (tools-config/apply-tool-project-override! item :unload)))
        (t/is (= ["-mcp_tool"] (:enabled-tools @project)))
        (t/is (= :unload (tools-config/tool-override-state-of item)))
        (t/is (true? (tools-config/apply-tool-project-override! item :load)))
        (t/is (= ["+mcp_tool"] (:enabled-tools @project)))
        (t/is (= :load (tools-config/tool-override-state-of item)))
        (t/is (true? (tools-config/apply-tool-project-override! item :inherit)))
        (t/is (nil? (:enabled-tools @project)))
        (t/is (= :inherit (tools-config/tool-override-state-of item)))))))

(t/deftest test-plain-global-enabled-list-is-rewritten
  (with-settings {:enabled-tools ["read" "bash"]} nil
    (fn [global _]
      (let [item (tool-item "bash" :enabled-tools)]
        (t/is (true? (tools-config/apply-tool-toggle! item false)))
        (t/is (= ["read"] (:enabled-tools @global))
              "a plain global list is rewritten as the effective selection")))))

;; ─── The opt-in tool's saved selection ─────────────────────────────────────

(t/deftest test-opt-in-toggle-writes-plus-name
  (with-settings nil nil
    (fn [global _]
      (let [item (tool-item "run_code" :default-tools)]
        (t/is (true? (tools-config/apply-tool-toggle! item true)))
        (t/is (= ["+run_code"] (:default-tools @global)))
        (t/is (true? (tools-config/apply-tool-toggle! item false)))
        (t/is (nil? (:default-tools @global)))))))

(t/deftest test-opt-in-tools-default-off-in-the-config-model
  (let [items (tools-config/tool-items nil nil)
        row (row-of items "run_code")]
    (t/is (false? (:enabled row)))
    (t/is (false? (:enabled (row-of items "eval"))))
    (t/is (true? (:enabled (row-of items "read"))))))
