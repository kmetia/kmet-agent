(ns kmet.app.test-tool-selection
  "The shared selection grammar: entry lists resolve over a base set (the
   built-ins for the runtime default, the live names for :resources)."
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
