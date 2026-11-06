(ns kmet.app.test-tool-selection
  "The shared selection grammar: entry lists resolve over a base set (the
   built-ins for the retired :default-tools path, the live names for
   :resources)."
  (:require [clojure.test :as t]
            [kmet.app.tools.registry :as tools]))

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
