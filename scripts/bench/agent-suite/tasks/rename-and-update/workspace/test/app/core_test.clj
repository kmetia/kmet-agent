(ns app.core-test
  (:require [clojure.test :refer [deftest is]]
            [app.core :as core]))

(deftest run-test
  (is (= "a, b" (core/run ["a" "b"]))))
