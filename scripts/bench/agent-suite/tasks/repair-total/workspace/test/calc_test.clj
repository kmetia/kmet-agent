(ns calc-test
  (:require [clojure.test :refer [deftest is]]
            [calc :as calc]))

(deftest total-test
  (is (= 15 (calc/total [1 2 3 4 5])))
  (is (= 0 (calc/total []))))

(deftest mean-test
  (is (= 3 (calc/mean [1 2 3 4 5]))))
