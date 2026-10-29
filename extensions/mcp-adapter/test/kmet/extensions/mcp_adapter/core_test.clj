(ns kmet.extensions.mcp-adapter.core-test
  "The extension entry point (§10.2): what init registers against a
   nullable api, what the /mcp command answers, and that shutdown is
   idempotent. No server connects — the subprocess suites cover that."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extension :as ext]
            [kmet.extensions.mcp-adapter.config :as config]
            [kmet.extensions.mcp-adapter.core :as mcp]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

(defn- with-extension
  "Run F with a fresh extension init against the nullable api and an empty
   config, restoring the process agent dir afterwards."
  [config-form f]
  (let [orig @#'config/agent-dir-atom]
    (tu/with-agent-dir
      (fn [{:keys [agent]}]
        (when config-form
          (tu/write-edn! (str (java.io.File. ^String agent "mcp.edn")) config-form))
        (let [{:keys [api state]} (ext/create-nullable-api {:agent-dir agent})]
          (try
            (mcp/init api)
            (f api state)
            (finally
              (mcp/shutdown api)
              (if (some? orig)
                (config/set-agent-dir! orig)
                (reset! @#'config/agent-dir-atom nil)))))))))

(deftest init-registers-the-extension-surface
  (with-extension
    nil
    (fn [_api state]
      (let [s @state]
        (testing "the proxy tool is registered with its schema"
          (let [tool (get-in s [:tools "mcp"])]
            (is (some? tool))
            (is (= "MCP" (:label tool)))
            (is (true? (:streams? tool)))
            (is (contains? (get-in tool [:parameters :properties]) "search"))
            (is (fn? (:execute tool)))))
        (testing "the retired mcpScript tool stays retired"
          (is (nil? (get-in s [:tools "mcpScript"]))))
        (testing "the /mcp command is registered"
          (let [cmd (get-in s [:commands "mcp"])]
            (is (some? cmd))
            (is (str/includes? (:description cmd) "MCP server status"))
            (is (fn? (:handler cmd)))
            (is (fn? (:get-argument-completions cmd)))))
        (testing "session start and shutdown handlers are registered"
          (is (seq (get-in s [:handlers :session-start])))
          (is (seq (get-in s [:handlers :session-shutdown])))
          (is (empty? (get-in s [:handlers :resources-discover]))))
        (testing "the mcp skill self-registers from the bundled content"
          (let [skill (first (:skills s))]
            (is (some? skill))
            (is (= "mcp-adapter:skills/mcp/SKILL.md" (get-in skill [:opts :location])))
            (is (str/includes? (str (:content skill)) "name: mcp"))))
        (testing "script mode contributes the sandbox tool source"
          (is (fn? (get-in s [:tool-sources :mcp]))))))))

(deftest mcp-command-completions-and-status
  (with-extension
    {:settings {:direct-tools false}
     :mcp-servers {"fs" {:command "bb"}
                   "web" {:url "https://h/mcp"}}}
    (fn [_api state]
      (let [cmd (get-in @state [:commands "mcp"])
            completions (:get-argument-completions cmd)]
        (testing "a bare prefix completes subcommands"
          (is (= "connect" (:value (first (completions "con")))))
          (is (some #(= "status" (:value %)) (completions ""))))
        (testing "a server-taking subcommand completes server names"
          (is (= [{:value "connect fs" :label "fs"}]
                 (completions "connect f"))))
        (testing "an unknown subcommand completes nothing"
          (is (empty? (completions "status f")))))
      (testing "the status handler prints the settings and server rows"
        (let [out (with-out-str ((:handler (get-in @state [:commands "mcp"]))
                                 {:has-ui false} ""))]
          (is (str/includes? out "settings:"))
          (is (str/includes? out "fs ("))
          (is (str/includes? out "web (")))))))

(deftest mcp-command-rejects-an-unknown-subcommand
  (with-extension
    nil
    (fn [_api state]
      (testing "an unknown subcommand is named back to the user"
        (let [out (with-out-str ((:handler (get-in @state [:commands "mcp"]))
                                 {:has-ui false} "nonsense"))]
          (is (str/includes? out "Unknown /mcp subcommand: nonsense")))))))

(deftest shutdown-is-idempotent
  (let [orig @#'config/agent-dir-atom]
    (try
      (tu/with-agent-dir
        (fn [{:keys [agent]}]
          (let [{:keys [api]} (ext/create-nullable-api {:agent-dir agent})]
            (mcp/init api)
            (mcp/shutdown api)
            (mcp/shutdown api)
            (is true "a second shutdown does not throw"))))
      (finally
        (if (some? orig)
          (config/set-agent-dir! orig)
          (reset! @#'config/agent-dir-atom nil))))))

(deftest template-is-created-on-first-init
  (with-extension
    nil
    (fn [_api _state]
      (is (some? (config/read-config-file (config/global-config-path)))
          "init writes the global template when it is missing"))))
