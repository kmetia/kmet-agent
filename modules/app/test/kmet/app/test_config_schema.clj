(ns kmet.app.test-config-schema
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.config-schema :as cs]))

(deftest default-provider-config-validates
  (testing "minimal merged config validates"
    (is (cs/validate-config? {:provider :opencode-go
                              :theme "dark"
                              :loop-guard {:enabled true :threshold 3}
                              :images {:block-images false}
                              :http-idle-timeout-ms 120000
                              :http-total-timeout-ms 0}))))

(deftest closed-under-debug-still-open-at-boundary
  (testing "unknown keys survive (forward compatible)"
    (is (cs/validate-config? {:provider :opencode-go :future-key 1}))))

(deftest compactor-is-one-of-the-builtins
  (testing "the compactor selector accepts only the builtin strategies"
    (is (cs/validate-config? {:compactor :llm}))
    (is (cs/validate-config? {:compactor :vcc}))
    (is (not (cs/validate-config? {:compactor :other})))))
