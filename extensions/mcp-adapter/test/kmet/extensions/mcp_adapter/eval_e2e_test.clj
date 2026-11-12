(ns kmet.extensions.mcp-adapter.eval-e2e-test
  "Eval-only MCP end to end: the extension's ext.mcp source drives the fake
   stdio server through the real eval service. Spawns the fixture (^:slow)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as registry]
            [kmet.extension :as ext]
            [kmet.extensions.mcp-adapter.config :as config]
            [kmet.extensions.mcp-adapter.core :as mcp]
            [kmet.extensions.mcp-adapter.test-util :as tu]
            [kmet.srce.core :as srce]))

(deftest ^:slow eval-only-mcp
  (tu/with-agent-dir
    (fn [_]
      (tu/write-edn! (config/global-config-path)
                     {:settings {:direct-tools true}
                      :mcp-servers {"fake" {:command "bb"
                                            :args [(tu/fixture "fake-mcp-server.bb")]
                                            :direct-tools true}}})
      (let [{:keys [api state]} (ext/create-nullable-api)
            env (support/eval-ctx)]
        (try
          (mcp/init api)
          ;; the extension's source closes over its own adapter state; register
          ;; that same fn on the real eval service's tools handle
          (registry/register-eval-source! (:tools env) :mcp
                                          (get-in @state [:eval-sources :mcp])
                                          {:owner [:test :mcp]})
          (let [connect ((get-in @state [:tools "mcp" :execute]) {:connect "fake"})]
            (is (not (:is-error connect)) (:content connect)))
          (testing "call reaches a cached tool"
            (let [r (support/eval! env "(mcp/call \"fake_echo\" {:message \"hi\"})")]
              (is (not (:is-error r)) (:content r))
              (is (str/includes? (:content r) "echo: hi"))))
          (testing "call! returns the content string"
            (let [r (support/eval! env "(mcp/call! \"fake_echo\" {:message \"hi\"})")]
              (is (= "echo: hi" (:content r)))))
          (testing "a direct tool is a listed eval verb"
            (let [r (support/eval! env "(mcp/fake_echo {:message \"direct\"})")]
              (is (not (:is-error r)) (:content r))
              (is (str/includes? (:content r) "echo: direct"))))
          (testing "the rest of the catalog is searchable and describeable"
            (is (str/includes? (:content (support/eval! env "(mcp/search \"echo\")"))
                               "fake_echo"))
            (is (str/includes? (:content (support/eval! env "(mcp/describe \"fake_echo\")"))
                               "fake_echo")))
          (finally
            (mcp/shutdown api)
            (srce/dispose-subtree! (:ctx env))))))))
