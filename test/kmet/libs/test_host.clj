(ns kmet.libs.test-host
  "The host detector must match the runtime the tests actually run on —
   the same suite executes under `bb test` and `jolt test`."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.host :as host]))

(deftest detects-the-running-host
  (let [jolt? (some? (find-var 'clojure.core/*jolt-version*))]
    (testing "jolt? follows the *jolt-version* marker this test reads directly"
      (is (= jolt? (host/jolt?))))
    (testing "runtime-name and mark name the host the tests run on"
      (is (= (if jolt? "jolt" "babashka") (host/runtime-name)))
      (is (= (if jolt? "j" "b") (host/mark)))))
  (testing "the mark is the single-letter badge of the runtime name"
    (is (contains? #{"b" "j"} (host/mark))
        "only babashka and jolt have badges")
    (is (= (subs (host/runtime-name) 0 1) (host/mark)))))

(deftest finite?-parity
  (is (true? (host/finite? 1)))
  (is (true? (host/finite? 1.5)))
  (is (true? (host/finite? 0)))
  (is (true? (host/finite? -3.25)))
  (is (true? (host/finite? 9e9)))
  (is (false? (host/finite? Double/NaN)) "NaN is not finite")
  (is (false? (host/finite? Double/POSITIVE_INFINITY)) "+Inf is not finite")
  (is (false? (host/finite? Double/NEGATIVE_INFINITY)) "-Inf is not finite")
  (is (false? (host/finite? nil)) "nil is not finite")
  (is (false? (host/finite? "1")) "strings are not finite")
  (when-not (some? (find-var 'clojure.core/*jolt-version*))
    (testing "parity with Double/isFinite on bb"
      (doseq [v [0 1 -1 1.5 -3.25 9e9 Double/NaN
                 Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY]]
        (is (= (Double/isFinite (double v)) (host/finite? v))
            (str "parity for " v))))))

