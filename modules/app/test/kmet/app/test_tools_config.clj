(ns kmet.app.test-tools-config
  "S2: the generic :enabled-tools writer and the /tools selection summary."
  (:require [babashka.fs :as fs]
            [clojure.test :as t]
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

(t/deftest test-global-modifier-list-keeps-its-shape
  (with-settings nil nil
    (fn [global _]
      (t/is (= ["-bash"] (tools-config/set-tool-enabled!
                          "bash" false {:scope :global})))
      (t/is (= ["-bash"] (:enabled-tools @global)))
      (t/is (nil? (tools-config/set-tool-enabled!
                   "bash" true {:scope :global})))
      (t/is (nil? (:enabled-tools @global)))
      (t/is (= ["-read"] (tools-config/set-tool-enabled!
                          "read" false {:scope :global})))
      (t/is (nil? (tools-config/set-tool-enabled!
                   "read" nil {:scope :global})))
      (t/is (nil? (:enabled-tools @global))))))

(t/deftest test-global-plain-list-is-rewritten
  (with-settings {:enabled-tools ["read" "bash"]} nil
    (fn [global _]
      (t/is (= ["read"] (tools-config/set-tool-enabled!
                         "bash" false {:scope :global})))
      (t/is (= ["read"] (:enabled-tools @global)))
      (t/is (= ["read" "write"]
               (tools-config/set-tool-enabled!
                "write" true {:scope :global :live-names ["read" "bash" "write"]})))
      (t/is (= ["write"]
               (tools-config/set-tool-enabled!
                "read" nil {:scope :global :live-names ["read" "bash" "write"]}))))))

(t/deftest test-project-writes-a-delta
  (with-settings {:enabled-tools ["-read"]} nil
    (fn [_ project]
      (t/is (= ["-read" "+bash"]
               (tools-config/set-tool-enabled!
                "bash" true {:scope :project})))
      (t/is (= ["+bash"] (:enabled-tools @project)))
      (t/is (= ["-read" "-bash"]
               (tools-config/set-tool-enabled!
                "bash" false {:scope :project})))
      (t/is (= ["-bash"] (:enabled-tools @project)))
      (t/is (= ["-read"] (tools-config/set-tool-enabled!
                          "bash" nil {:scope :project})))
      (t/is (nil? (:enabled-tools @project)))
      (t/is (= ["-read"] (tools-config/set-tool-enabled!
                          "read" nil {:scope :project})))
      (t/is (nil? (:enabled-tools @project))))))

(t/deftest test-set-tool-enabled-rejects-a-bad-scope
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"scope"
                          (tools-config/set-tool-enabled!
                           "read" true {:scope :session})))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"enabled"
                          (tools-config/set-tool-enabled!
                           "read" :yes {:scope :global}))))

(t/deftest test-default-scope-follows-dot-kmet
  (with-redefs [fs/exists? (constantly true)]
    (t/is (= :project (tools-config/default-scope))))
  (with-redefs [fs/exists? (constantly false)]
    (t/is (= :global (tools-config/default-scope)))))

(t/deftest test-tool-selection-reports-origins
  (with-redefs [fs/exists? (constantly false)]
    (t/testing "no entries: every enabled name is a default"
      (with-settings nil nil
        (fn [_ _]
          (let [s (tools-config/tool-selection
                   ["read" "write" "bash"] #{"read" "write"} {})]
            (t/is (= :global (:scope s)))
            (t/is (= {:global [] :project []} (:entries s)))
            (t/is (every? string? (vals (:paths s))))
            (t/is (= #{"read" "write"} (:enabled s)))
            (t/is (= {"read" :default "write" :default "bash" :default}
                     (:origin s)))))))
    (t/testing "a global modifier is global provenance"
      (with-settings {:enabled-tools ["-bash"]} nil
        (fn [_ _]
          (let [s (tools-config/tool-selection ["read" "bash"] #{"read"} {})]
            (t/is (= #{"read"} (:enabled s)))
            (t/is (= {"read" :default "bash" :global} (:origin s)))
            (t/is (= ["-bash"] (get-in s [:entries :global])))))))
    (t/testing "a project modifier beats the global source"
      (with-settings {:enabled-tools ["-read"]}
        {:enabled-tools ["+bash"]}
        (fn [_ _]
          (let [s (tools-config/tool-selection
                   ["bash" "write" "read"] #{"bash" "write"} {})]
            (t/is (= {"bash" :project "write" :default "read" :global}
                     (:origin s)))))))
    (t/testing "a project plain list is absolute"
      (with-settings {:enabled-tools ["read" "bash"]}
        {:enabled-tools ["read"]}
        (fn [_ _]
          (let [s (tools-config/tool-selection ["read" "bash"] #{"read"} {})]
            (t/is (= {"read" :project "bash" :project} (:origin s)))))))
    (t/testing "a global plain list is global provenance"
      (with-settings {:enabled-tools ["read" "bash"]} nil
        (fn [_ _]
          (let [s (tools-config/tool-selection
                   ["read" "bash"] #{"read" "bash"} {})]
            (t/is (= {"read" :global "bash" :global} (:origin s)))))))
    (t/testing "a runtime selection sources every enabled name from the session"
      (with-settings nil nil
        (fn [_ _]
          (let [s (tools-config/tool-selection
                   ["read" "write" "bash"]
                   #{"read" "write"}
                   {:session #{"read" "write"}})]
            (t/is (= {"read" :session "write" :session "bash" :session}
                     (:origin s)))))))))

;; ─── S4: the static tool rows beyond the built-ins ─────────────────────────

(defn- row-of
  [items name]
  (first (filter #(= name (get-in % [:metadata :display-name])) items)))

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
      (let [item {:resource-type :tools
                  :metadata {:display-name "mcp_tool"
                             :setting :enabled-tools}}]
        (t/is (true? (tools-config/apply-enabled-tool-toggle! item false)))
        (t/is (= ["-mcp_tool"] (:enabled-tools @global)))
        (t/is (= :inherit (tools-config/tool-override-state-of item)))
        (t/is (true? (tools-config/apply-enabled-tool-toggle! item true)))
        (t/is (nil? (:enabled-tools @global)))
        (t/is (true? (tools-config/apply-enabled-tool-project-override! item :unload)))
        (t/is (= ["-mcp_tool"] (:enabled-tools @project)))
        (t/is (= :unload (tools-config/tool-override-state-of item)))
        (t/is (true? (tools-config/apply-enabled-tool-project-override! item :load)))
        (t/is (= ["+mcp_tool"] (:enabled-tools @project)))
        (t/is (= :load (tools-config/tool-override-state-of item)))
        (t/is (true? (tools-config/apply-enabled-tool-project-override! item :inherit)))
        (t/is (nil? (:enabled-tools @project)))
        (t/is (= :inherit (tools-config/tool-override-state-of item)))))))

;; ─── S4.5: the opt-in tool's saved selection ──────────────────────────────

(t/deftest test-opt-in-toggle-writes-plus-name
  (with-settings nil nil
    (fn [global _]
      (let [item {:resource-type :tools
                  :metadata {:display-name "run_code"
                             :setting :default-tools}}]
        (t/is (true? (tools-config/apply-builtin-tool-toggle! item true)))
        (t/is (= ["+run_code"] (:default-tools @global)))
        (t/is (true? (tools-config/apply-builtin-tool-toggle! item false)))
        (t/is (nil? (:default-tools @global)))))))

(t/deftest test-opt-in-enable-keeps-an-all-enabled-save-non-empty
  ;; /tools save writes one state per live tool: the default-on tools remove
  ;; their entries, so the opt-ins must leave a +name behind or the whole
  ;; setting collapses to nil and falls back to the opt-out default
  (with-settings nil nil
    (fn [global _]
      (let [live ["read" "write" "edit" "bash" "tool_search" "run_code" "eval"]]
        (doseq [name live]
          (tools-config/set-tool-enabled! name true {:scope :global
                                                     :live-names live}))
        (t/is (= ["+run_code" "+eval"] (:enabled-tools @global))
              "the opt-ins keep the selection non-empty")
        (t/is (= (set live)
                 (set (registry/resolve-enabled-tools (:enabled-tools @global)
                                                      live)))
              "and the saved selection resolves to every live tool")))))

(t/deftest test-opt-in-tools-default-off-in-the-config-model
  (let [items (tools-config/tool-items nil nil)
        row (row-of items "run_code")]
    (t/is (false? (:enabled row)))
    (t/is (false? (:enabled (row-of items "eval"))))
    (t/is (true? (:enabled (row-of items "read"))))))
