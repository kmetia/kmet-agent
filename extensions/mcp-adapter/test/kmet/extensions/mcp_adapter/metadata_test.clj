(ns kmet.extensions.mcp-adapter.metadata-test
  "The persistent metadata cache (§8): fingerprint freshness, the 7-day
   age limit, the merged atomic write, and the cross-server views the
   adapter's read paths use."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.mcp-adapter.metadata :as metadata]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

(def ^:private definition {:command "npx" :args ["-y" "x"]})
(def ^:private settings {:direct-tools false :tool-prefix :server})

(deftest cache-round-trip
  (tu/with-agent-dir
    (fn [{:keys [agent]}]
      (testing "the cache lives under the agent dir"
        (is (= (str (fs/path agent "mcp-cache.edn")) (metadata/cache-path))))
      (let [cache (metadata/update-entry!
                   nil "srv" definition settings
                   :tools [{:name "tool-a" :description "d" :inputSchema {}}]
                   :prompts [{:name "brief" :description "b"
                              :arguments [{:name "topic" :required true}]}]
                   :resources [{:name "README" :uri "file:///r"}]
                   :protocol-era {:era :modern :version "2026-07-28"})]
        (testing "a written entry reads back fresh"
          (is (some? (metadata/server-entry cache "srv" definition settings)))
          (is (= :modern (get-in cache [:servers "srv" :protocol-era :era]))))
        (testing "tools, prompts and resources survive the EDN round trip"
          (let [loaded (metadata/load-cache)]
            (is (= "tool-a" (get-in loaded [:servers "srv" :tools 0 :name])))
            (is (= "brief" (get-in loaded [:servers "srv" :prompts 0 :name])))
            (is (= "README" (get-in loaded [:servers "srv" :resources 0 :name])))
            (is (= {:era :modern :version "2026-07-28"}
                   (get-in loaded [:servers "srv" :protocol-era])))))))))

(deftest freshness-rules
  (tu/with-agent-dir
    (fn [_]
      (metadata/update-entry! nil "srv" definition settings
                              :tools [{:name "tool-a"}]
                              :protocol-era {:era :modern :version "2026-07-28"})
      (testing "a config change invalidates the entry"
        (is (nil? (metadata/server-entry (metadata/load-cache) "srv"
                                         {:command "other"} settings))))
      (testing "a tool-prefix change invalidates the entry"
        (is (nil? (metadata/server-entry (metadata/load-cache) "srv"
                                         definition {:tool-prefix :none}))))
      (testing "an include-tools change invalidates the entry"
        (is (nil? (metadata/server-entry (metadata/load-cache) "srv"
                                         (assoc definition :include-tools ["a*"])
                                         settings))))
      (testing "an entry written without a protocol era is still fresh"
        (metadata/update-entry! nil "no-era" definition settings :tools [])
        (is (some? (metadata/server-entry (metadata/load-cache) "no-era"
                                          definition settings))))
      (testing "the fingerprint ignores keys that cannot change the catalog"
        (is (= (metadata/config-fingerprint "srv" definition settings)
               (metadata/config-fingerprint "srv"
                                            (assoc definition :lifecycle :keep-alive)
                                            settings))))
      (testing "an entry older than the 7-day window is stale"
        (let [old (- (System/currentTimeMillis) (* 8 24 60 60 1000))]
          (spit (metadata/cache-path)
                (pr-str {:version 1
                         :servers {"srv" {:config-fingerprint
                                          (metadata/config-fingerprint "srv" definition settings)
                                          :fetched-at old
                                          :tools [{:name "tool-a"}]}}}))
          (is (nil? (metadata/server-entry (metadata/load-cache) "srv"
                                           definition settings))))))))

(deftest cache-file-shape
  (tu/with-agent-dir
    (fn [_]
      (testing "a missing cache file loads as nil"
        (is (nil? (metadata/load-cache))))
      (testing "a wrong version loads as nil"
        (spit (metadata/cache-path) (pr-str {:version 99 :servers {}}))
        (is (nil? (metadata/load-cache))))
      (testing "an unparsable file loads as nil instead of throwing"
        (spit (metadata/cache-path) "{:version 1 :servers")
        (is (nil? (metadata/load-cache))))
      (testing "a save merges with the servers already in the file"
        (spit (metadata/cache-path) (pr-str {:version 1 :servers {}}))
        (metadata/update-entry! nil "a" definition settings :tools [])
        (metadata/update-entry! nil "b" definition settings :tools [])
        (is (= #{"a" "b"} (set (keys (:servers (metadata/load-cache))))))))))

(deftest cross-server-views
  (tu/with-agent-dir
    (fn [_]
      (let [config {:mcp-servers {"on" {:command "bb"}
                                  "off" {:command "bb" :disabled true}
                                  "stale" {:command "other"}}}]
        (metadata/update-entry! nil "on" {:command "bb"} settings
                                :tools [{:name "tool-a" :description "A"}]
                                :prompts [{:name "brief" :description "B"}])
        (metadata/update-entry! nil "off" {:command "bb" :disabled true} settings
                                :tools [{:name "hidden"}]
                                :prompts [{:name "secret"}])
        (metadata/update-entry! nil "stale" {:command "fresh"} settings
                                :tools [{:name "gone"}])
        (let [cache (metadata/load-cache)]
          (testing "all-tools spans fresh, enabled servers only"
            (is (= [{:server "on" :tool {:name "tool-a" :description "A"}}]
                   (mapv #(update % :tool select-keys [:name :description])
                         (metadata/all-tools cache config settings)))))
          (testing "all-prompts applies the same gate"
            (is (= [{:server "on" :prompt {:name "brief" :description "B"}}]
                   (mapv #(update % :prompt select-keys [:name :description])
                         (metadata/all-prompts cache config settings)))))
          (testing "a stale-fingerprint server is not listed"
            (is (not-any? #(= "stale" (:server %))
                          (metadata/all-tools cache config settings)))))))))
