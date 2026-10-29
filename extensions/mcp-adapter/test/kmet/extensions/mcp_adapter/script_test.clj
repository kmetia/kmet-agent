(ns kmet.extensions.mcp-adapter.script-test
  "Scripted-MCP end-to-end (§12.6 Phase 2, run_code.md T2): the extension
   contributes its cached catalog as a run_code tool source, and the real
   `run_code` tool drives it — discovery, calls, error results, the
   unknown-name gate, timeout, streaming progress and the call trace.
   Spawns the fake stdio server (^:slow)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.tools.registry :as registry]
            [kmet.extension :as ext]
            [kmet.extensions.mcp-adapter.config :as config]
            [kmet.extensions.mcp-adapter.core :as mcp]
            [kmet.extensions.mcp-adapter.test-util :as tu]
            [kmet.test-utils :as test-utils]))

(defn- script-exec
  "Run CODE through the real run_code tool; returns the tool result map."
  [reg code & [{:keys [on-update timeout]}]]
  (registry/execute-tool reg "run_code"
                         (cond-> {:code code}
                           timeout (assoc :timeout timeout))
                         (cond-> {}
                           on-update (assoc :on-update on-update))))

(defn- fake-config [fake-stdio & [settings]]
  {:settings (or settings {})
   :mcp-servers {"fake" {:command "bb" :args [fake-stdio]
                         :env {"BABASHKA_CLASSPATH"
                               (System/getProperty "java.class.path")}}}})

(deftest ^:slow run-code-sandbox-bridge
  (tu/with-agent-dir
    (fn [_]
      ;; the extension spawns the stdio fake itself from the config's :args
      (tu/write-edn! (config/global-config-path)
                     (fake-config (tu/fixture "fake-mcp-server.bb")))
      ;; the built-in tool table, so `run_code` is the real tool
      (let [reg (test-utils/tool-registry)
            {:keys [api state]} (ext/create-nullable-api)]
        (try
          (mcp/init api)
          (let [source-fn (get-in @state [:tool-sources :mcp])]
            (testing "the extension registers a tool source, not a tool"
              (is (nil? (get-in @state [:tools "mcpScript"])))
              (is (fn? source-fn)))
            (registry/register-tool-source! reg :mcp source-fn))

          (testing "connecting fills the cache the source reads"
            (let [proxy-tool (get-in @state [:tools "mcp"])
                  r ((:execute proxy-tool) {:connect "fake"})]
              (is (false? (:is-error r)) (:content r))))

          (testing "the sandbox sees the MCP catalog and never the script tool"
            (let [r (script-exec reg "(emit (let [names (tools/list)] [(boolean (some #{\"fake_echo\"} names)) (boolean (some #{\"script\"} names))]))")]
              (is (= "[true false]" (:content r)))))

          (testing "tools/describe carries the schema without :execute"
            (let [r (script-exec reg "(emit (let [d (tools/describe \"fake_echo\")] [(:name d) (:label d) (contains? d :execute) (str/includes? (pr-str (:parameters d)) \"message\")]))")]
              (is (= "[\"fake_echo\" \"MCP: echo\" false true]" (:content r)))))

          (testing "tools/call reaches the server"
            (let [r (script-exec reg "(emit @(tools/call \"fake_echo\" {:message \"hi from script\"}))")]
              (is (not (:is-error r)))
              (is (str/includes? (:content r) "echo: hi from script"))))

          (testing "an MCP error result reaches the script as data"
            (let [r (script-exec reg "(emit @(tools/call \"fake_boom\" {}))")]
              (is (not (:is-error r)))
              (is (str/includes? (:content r) ":is-error true"))))

          (testing "an unknown name is gated, not dispatched"
            (let [r (script-exec reg "(emit @(tools/call \"nope\" {}))")]
              (is (not (:is-error r)))
              (is (str/includes? (:content r) "not active"))))

          (testing "the host registry wins a name collision"
            (let [r (script-exec reg "(emit (:label (tools/describe \"read\")))")]
              (is (= "Read file" (:content r)))))

          (testing "only emit is reported, not stdout or the return value"
            (let [r (script-exec reg "(println \"captured\") (+ 1 2) (emit :emitted)")]
              (is (not (:is-error r)))
              (is (= ":emitted" (:content r)))
              (is (not (str/includes? (:content r) "captured")))
              (is (not (str/includes? (:content r) "3")))))

          (testing "a script timeout is reported in seconds"
            (let [r (script-exec reg "(loop [] (recur))" {:timeout 1.5})]
              (is (true? (:is-error r)))
              (is (= :timeout (get-in r [:details :error])))
              (is (str/includes? (:content r) "timed out after 1.5s"))))

          (testing "the call trace records the tool and its outcome"
            (let [r (script-exec reg "(emit @(tools/call \"fake_add\" {:a 1 :b 2}))")]
              (is (some (fn [c] (and (= "fake_add" (:tool c)) (true? (:ok c))))
                        (get-in r [:details :calls])))))

          (testing "progress notifications stream through on-update"
            (let [partials (atom [])
                  r (script-exec reg "(emit @(tools/call \"fake_slow\" {:ms 600}))"
                                 {:on-update (fn [partial]
                                               (swap! partials conj (:content partial)))})]
              (is (seq @partials))
              (is (str/includes? (:content r) "slept"))))

          (testing "unregistering the source removes the catalog"
            (registry/unregister-tool-source! reg :mcp)
            (let [r (script-exec reg "(emit (tools/list))")]
              (is (not (str/includes? (:content r) "fake_echo")))))

          (finally (mcp/shutdown api)))))))

(deftest ^:slow script-mode-off-contributes-nothing
  (tu/with-agent-dir
    (fn [_]
      (tu/write-edn! (config/global-config-path)
                     (fake-config (tu/fixture "fake-mcp-server.bb")
                                  {:script-mode false}))
      (let [{:keys [api state]} (ext/create-nullable-api)]
        (try
          (mcp/init api)
          (testing ":script-mode false drops the tool source"
            (is (nil? (get-in @state [:tool-sources :mcp]))))
          (finally (mcp/shutdown api)))))))
