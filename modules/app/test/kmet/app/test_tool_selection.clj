(ns kmet.app.test-tool-selection
  "S1: the tool-selection keys merge by scope and resolve over a base set —
   built-ins for :default-tools, the live names for :enabled-tools."
  (:require [clojure.test :as t]
            [kmet.app.config :as cfg]
            [kmet.app.tools.registry :as tools]))

(t/deftest test-merge-tool-entries
  (t/is (= ["read"] (cfg/merge-tool-entries ["read"] nil))
        "a user list alone passes through")
  (t/is (= ["read" "+bash" "-read"]
           (cfg/merge-tool-entries ["read"] ["+bash" "-read"]))
        "project modifiers amend the inherited list")
  (t/is (= ["write"] (cfg/merge-tool-entries ["read"] ["write"]))
        "a plain project name replaces the inherited list")
  (t/is (= ["read"] (cfg/merge-tool-entries ["read"] []))
        "an empty project list is modifier-only")
  (t/is (= ["+grep"] (cfg/merge-tool-entries nil ["+grep"])))
  (t/is (= [] (cfg/merge-tool-entries nil [])))
  (t/is (= "+bash" (cfg/merge-tool-entries ["read"] "+bash"))
        "a malformed project value replaces the inherited list"))

(t/deftest test-resolve-selection
  (t/is (nil? (tools/resolve-selection nil ["a" "b"]))
        "nil means no setting")
  (t/is (= [] (tools/resolve-selection [] ["a" "b"]))
        "an empty list selects nothing")
  (t/is (= ["x"] (tools/resolve-selection ["x"] ["a" "b"]))
        "plain names replace the base set")
  (t/is (= ["a" "c"] (tools/resolve-selection ["+c" "-b"] ["a" "b"]))
        "modifiers apply to the base set in order")
  (t/is (= ["ghost"] (tools/resolve-selection ["ghost"] ["a"]))
        "plain names replace the base set; an unknown name selects nothing")
  (t/is (= [] (tools/resolve-selection "not-a-list" ["a"]))
        "a non-list value resolves empty")
  (t/is (= ["a"] (tools/resolve-selection ["a" 42 nil] ["a" "b"]))
        "non-string entries are dropped"))

(t/deftest test-resolve-default-tools-uses-the-builtin-base
  (t/is (contains? (set (tools/resolve-default-tools ["-bash"])) "read"))
  (t/is (not (contains? (set (tools/resolve-default-tools ["-bash"])) "bash")))
  (t/is (= ["read"] (tools/resolve-default-tools ["read"]))))

(t/deftest test-resolve-enabled-tools-uses-the-live-base
  (t/is (= ["a" "c"] (tools/resolve-enabled-tools ["-b" "+c"] ["a" "b"])))
  (t/is (= ["a" "b"] (tools/resolve-enabled-tools ["+a" "+b"] ["a"]))
        "a modifier-only list amends the live set")
  (t/is (= ["a"] (tools/resolve-enabled-tools ["a"] ["a" "b"]))
        "a plain list is the absolute live selection")
  (t/is (= [] (tools/resolve-enabled-tools [] ["a" "b"])))
  (t/is (= #{"a" "late"} (set (tools/resolve-enabled-tools ["-b"] #{"a" "b" "late"})))
        "a live tool the setting does not name stays selected"))

;; ─── S4.5: opt-in built-in tools ──────────────────────────────────────────

(t/deftest test-opt-in-defaults
  (t/is (= #{"run_code" "eval"}
           (set (remove (set tools/default-tool-names)
                        tools/builtin-tool-names)))
        "the opt-ins are the only difference from the full built-in list")
  (t/is (tools/default-off-tool? "run_code"))
  (t/is (tools/default-off-tool? "eval"))
  (t/is (not (tools/default-off-tool? "read")))
  (t/is (not (tools/default-off-tool? "extension_tool"))
        "a name no built-in registry answers to is on (the live-set default)"))

(t/deftest test-resolve-default-tools-uses-the-opt-in-base
  (t/is (not (contains? (set (tools/resolve-default-tools ["-bash"])) "run_code"))
        "a modifier-only list does not re-enable an opt-in")
  (t/is (not (contains? (set (tools/resolve-default-tools ["-bash"])) "eval")))
  (t/is (contains? (set (tools/resolve-default-tools ["+run_code"])) "run_code")
        "an explicit +name does")
  (t/is (= ["run_code"] (tools/resolve-default-tools ["run_code"]))
        "a plain list names the opt-in absolutely")
  (t/is (nil? (tools/resolve-default-tools nil))))

(t/deftest test-default-tool-exclusions-include-the-opt-ins
  (t/is (= #{"run_code" "eval"} (tools/default-tool-exclusions nil))
        "an unset selection excludes the opt-ins")
  (t/is (= #{"bash" "run_code" "eval"}
           (tools/default-tool-exclusions (tools/resolve-default-tools ["-bash"])))
        "and a modifier-only list keeps them excluded while dropping its target"))
