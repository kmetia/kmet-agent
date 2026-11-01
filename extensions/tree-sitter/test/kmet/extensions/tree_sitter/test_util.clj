(ns kmet.extensions.tree-sitter.test-util
  "Helpers shared by tree-sitter extension tests."
  (:require [kmet.test-tmp :as test-tmp]))

(defn temp-dir!
  "A fresh directory under the test run's scratch root (kmet.test-tmp), which
   the runner deletes when the run ends — tests never need to clean up."
  [prefix]
  (test-tmp/temp-dir! (str prefix)))
