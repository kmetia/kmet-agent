(ns kmet.app.test-tools-config
  "S2: the generic :enabled-tools writer and the /tools selection summary."
  (:require [babashka.fs :as fs]
            [clojure.test :as t]
            [kmet.app.config :as cfg]
            [kmet.app.tools.config :as tools-config]))

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
