(ns kmet.extensions.mcp-adapter.eval-source-test
  "The eval source: only direct tools appear in the help listing; the
   gateway reaches the rest, and every call carries the eval call's cancel
   signal."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.eval.sources :as eval-sources]
            [kmet.extensions.mcp-adapter.eval-source :as eval-source]
            [kmet.extensions.mcp-adapter.test-util :as tu]
            [kmet.extensions.mcp-adapter.tool-proxy :as proxy]
            [kmet.extensions.mcp-adapter.tool-surface :as surface]))

(def ^:private config
  {:settings {:tool-prefix :server}
   :mcp-servers {"fs" {:command "bb" :direct-tools ["read_file"]}
                 "web" {:command "bb"}}})

(def ^:private cached
  {"fs" {:tools [{:name "read_file" :description "Read a file"
                  :inputSchema {:type "object"
                                :properties {"path" {:type "string"}}}}
                 {:name "hidden" :description "Not direct"}]}
   "web" {:tools [{:name "web_only" :description "Web only"}]}})

(defn- state [& [cfg]]
  (atom (tu/cached-state (or cfg config) cached (:settings (or cfg config)))))

(deftest only-direct-tools-appear-in-the-help-listing
  (tu/with-agent-dir
    (fn [_]
      (let [st (state)
            source (eval-source/source st (atom false))
            ns-map (get-in source [:ns 'ext.mcp])
            normalized (eval-sources/normalize-source :mcp source)
            names (set (map :name (:verbs normalized)))]
        (testing "the gateway is present"
          (is (contains? ns-map 'call))
          (is (contains? ns-map 'call!))
          (is (contains? ns-map 'search))
          (is (contains? ns-map 'describe))
          (is (contains? names "ext.mcp/call"))
          (is (contains? names "ext.mcp/search")))
        (testing "only the direct tool becomes a verb"
          (is (contains? ns-map 'fs_read_file))
          (is (contains? names "ext.mcp/fs_read_file"))
          (is (not (contains? ns-map 'fs_hidden)))
          (is (not (contains? names "ext.mcp/fs_hidden"))))
        (testing "a non-direct server's tools stay out of the listing"
          (is (not (contains? ns-map 'web_web_only)))
          (is (not (contains? names "ext.mcp/web_web_only"))))))))

(deftest direct-verbs-return-the-call-result-and-forward-the-signal
  (tu/with-agent-dir
    (fn [_]
      (let [signal (atom false)
            st (state)
            source (eval-source/source st signal)
            verb (get-in source [:ns 'ext.mcp 'fs_read_file])
            seen (atom nil)]
        (with-redefs [surface/execute-spec
                      (fn [state-atom spec args opts]
                        (reset! seen {:state state-atom :spec spec :args args :opts opts})
                        {:content "read" :is-error false})]
          (is (= {:content "read" :is-error false} (verb {:path "x"})))
          (is (= {:path "x"} (:args @seen)))
          (is (= signal (get-in @seen [:opts :signal])))
          (is (= "fs_read_file" (:prefixed (:spec @seen)))))))))

(deftest call-delegates-to-the-proxy-and-forwards-the-signal
  (tu/with-agent-dir
    (fn [_]
      (let [signal (atom false)
            st (state)
            source (eval-source/source st signal)
            call (get-in source [:ns 'ext.mcp 'call])
            call! (get-in source [:ns 'ext.mcp 'call!])
            seen (atom nil)]
        (with-redefs [proxy/execute
                      (fn [state-atom params on-update opts]
                        (reset! seen {:state state-atom :params params
                                      :on-update on-update :opts opts})
                        {:content "ok" :is-error false})]
          (testing "a plain call passes the tool, args and signal"
            (is (= {:content "ok" :is-error false}
                   (call "web_search" {:query "x"})))
            (is (= {:tool "web_search" :args {:query "x"}} (:params @seen)))
            (is (= signal (get-in @seen [:opts :signal])))
            (is (= :eval (get-in @seen [:opts :surface]))))
          (testing "the server option disambiguates"
            (call "web_search" {:query "x"} {:server "web"})
            (is (= "web" (get-in @seen [:params :server]))))
          (testing "a single map form is unambiguous"
            (is (= {:content "ok" :is-error false}
                   (call {:tool "web_search" :args {:query "m"}
                          :server "web"})))
            (is (= "web" (get-in @seen [:params :server])))
            (is (= {:query "m"} (get-in @seen [:params :args]))))
          (testing "call! returns the content"
            (is (= "ok" (call! "web_search" {:query "x"}))))
          (testing "call! throws on an error result"
            (with-redefs [proxy/execute (fn [& _] {:content "nope" :is-error true})]
              (let [e (try (call! "web_search" {}) nil (catch Exception e e))]
                (is (some? e))
                (is (= "nope" (ex-message e)))
                (is (= {:content "nope" :is-error true}
                       (get-in (ex-data e) [:result])))))))))))

(deftest search-describe-and-status-return-text
  (tu/with-agent-dir
    (fn [_]
      (let [st (state)
            source (eval-source/source st (atom false))
            ns-map (get-in source [:ns 'ext.mcp])
            seen (atom nil)]
        (with-redefs [proxy/execute
                      (fn [_ params _ opts]
                        (reset! seen {:params params :opts opts})
                        {:content "text" :is-error false})]
          (is (= "text" ((get ns-map 'search) "shot")))
          (is (= {:search "shot"} (:params @seen)))
          (is (= :eval (get-in @seen [:opts :surface])))
          (is (= "text" ((get ns-map 'search) "shot" {:regex true
                                                      :include-schemas false
                                                      :limit 5
                                                      :offset 2})))
          (is (= {:search "shot" :regex true :includeSchemas false
                  :limit 5 :offset 2}
                 (:params @seen)))
          (is (= :eval (get-in @seen [:opts :surface])))
          (is (= "text" ((get ns-map 'describe) "web_search")))
          (is (= {:describe "web_search"} (:params @seen)))
          (is (= :eval (get-in @seen [:opts :surface])))
          (is (= "text" ((get ns-map 'status))))
          (is (= {} (:params @seen)))
          (is (= :eval (get-in @seen [:opts :surface]))))))))

(deftest a-name-that-is-not-a-readable-symbol-is-skipped
  (tu/with-agent-dir
    (fn [_]
      (let [cfg {:settings {:tool-prefix :none
                            :direct-tools true}
                 :mcp-servers {"digits" {:command "bb"}}}
            st (atom (tu/cached-state cfg
                                      {"digits" {:tools [{:name "9lives"
                                                          :description "Numeric"}]}}
                                      (:settings cfg)))
            source (eval-source/source st (atom false))
            ns-map (get-in source [:ns 'ext.mcp])]
        (is (not (contains? ns-map (symbol "9lives"))))
        (is (contains? ns-map 'call))
        (is (some? (get ns-map 'call)))))))

(deftest connect-forwards-the-cancel-signal
  (tu/with-agent-dir
    (fn [_]
      (let [signal (atom false)
            source (eval-source/source (state) signal)
            connect (get-in source [:ns 'ext.mcp 'connect])
            seen (atom nil)]
        (with-redefs [proxy/execute
                      (fn [_ params _ opts]
                        (reset! seen {:params params :opts opts})
                        {:content "ok" :is-error false})]
          (is (= "ok" (connect "fs")))
          (is (= {:connect "fs"} (:params @seen)))
          (is (= signal (get-in @seen [:opts :signal])))
          (is (= :eval (get-in @seen [:opts :surface]))))))))
