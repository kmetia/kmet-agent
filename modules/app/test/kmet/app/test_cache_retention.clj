(ns kmet.app.test-cache-retention
  "The host's prompt-cache retention knob (pi resolveCacheRetention): the
   environment decides it, and kmet.app.loop.completion hands the result to
   every provider request, where a per-call value (compaction's summaries) can
   still override it."
  (:require [clojure.test :as t]
            [kmet.app.config :as cfg]))

(t/deftest test-get-cache-retention
  (t/testing "no setting → pi's :short default"
    (with-redefs [cfg/getenv (fn [_] nil)]
      (t/is (= :short (cfg/get-cache-retention)))))
  (t/testing "KMET_CACHE_RETENTION=long switches the 1h TTL on"
    (with-redefs [cfg/getenv (fn [k] (when (= k "KMET_CACHE_RETENTION") "long"))]
      (t/is (= :long (cfg/get-cache-retention)))))
  (t/testing ":none disables caching host-wide"
    (with-redefs [cfg/getenv (fn [k] (when (= k "KMET_CACHE_RETENTION") "none"))]
      (t/is (= :none (cfg/get-cache-retention)))))
  (t/testing "an unknown spelling keeps caching on"
    (with-redefs [cfg/getenv (fn [_] "sometimes")]
      (t/is (= :short (cfg/get-cache-retention))))))
