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
            (is (fn? (:execute tool)))
            (is (str/includes? (:description tool) "codemode"))
            (is (str/includes? (:description tool) "eval"))))
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
          (is (fn? (get-in s [:tool-sources :mcp]))))
        (testing "eval mode contributes the ext.mcp source"
          (is (fn? (get-in s [:eval-sources :mcp])))
          (let [source ((get-in s [:eval-sources :mcp]) {})]
            (is (contains? (get-in source [:ns 'ext.mcp]) 'call))))))))

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

(deftest refresh-applies-a-changed-server-definition
  ;; /mcp refresh rebuilt :servers by name alone, so an edited
  ;; :command/:url/:args kept the connection the old definition opened.
  (let [config {:mcp-servers {"srv" {:command ["node" "old.js"]}}}
        state (atom {:servers (#'mcp/build-servers config)})
        old (get-in @state [:servers "srv"])]
    (reset! (:conn old) ::connected)
    (reset! (:failed-at old) 12345)
    (testing "an unchanged definition keeps the live connection and its windows"
      (let [rebuilt (#'mcp/rebuild-servers state config)]
        (is (identical? old (get rebuilt "srv")))
        (is (= ::connected @(get-in rebuilt ["srv" :conn])))))
    (testing "an edited definition drops the old connection and the failure window"
      (let [changed (assoc-in config [:mcp-servers "srv" :command] ["node" "new.js"])
            rebuilt (#'mcp/rebuild-servers state changed)]
        (is (not (identical? old (get rebuilt "srv"))))
        (is (nil? @(get-in rebuilt ["srv" :conn])) "the old connection is not reused")
        (is (nil? @(get-in rebuilt ["srv" :failed-at]))
            "a fixed server does not inherit the old failure's backoff")
        (is (= ["node" "new.js"] (get-in rebuilt ["srv" :definition :command])))))))

(deftest refresh-and-the-panel-reload-share-one-helper
  ;; /mcp refresh and the setup panel's reload kept two copies of the same
  ;; reload body (load config, disconnect dropped servers, rebuild-servers,
  ;; auth storage, tools, prompts, proxy, status bar); reload-servers! owns
  ;; it now and each caller adds its own tail.
  (with-extension
    {:mcp-servers {"srv" {:command ["node" "old.js"]}}}
    (fn [_api api-state]
      (let [ext-state @(var-get #'mcp/state-atom)
            config-path (config/global-config-path)]
        (testing "the shared reload applies the file and hands the config back"
          (tu/write-edn! config-path
                         {:mcp-servers {"srv" {:command ["node" "new.js"]}
                                        "added" {:command ["node" "added.js"]}}})
          (let [config (#'mcp/reload-servers! ext-state)]
            (is (= config (:config @ext-state)))
            (is (= ["node" "new.js"]
                   (get-in @ext-state [:config :mcp-servers "srv" :command])))
            (is (= #{"srv" "added"} (set (keys (:servers @ext-state)))))))
        (testing "/mcp refresh runs it and adds its own tail"
          (tu/write-edn! config-path
                         {:mcp-servers {"srv" {:command ["node" "third.js"]}}})
          (let [out (with-out-str ((:handler (get-in @api-state [:commands "mcp"]))
                                   {:has-ui false} "refresh"))]
            (is (str/includes? out "MCP config reloaded."))
            (is (= ["node" "third.js"]
                   (get-in @ext-state [:config :mcp-servers "srv" :command])))
            (is (= #{"srv"} (set (keys (:servers @ext-state))))
                "a server dropped from the file leaves the live map")))))))

(deftest eval-source-can-be-disabled
  (with-extension
    {:settings {:eval-source false}}
    (fn [_api state]
      (is (nil? (get-in @state [:eval-sources :mcp]))))))

(deftest eval-source-can-be-disabled-on-reload
  (with-extension
    nil
    (fn [_api state]
      (is (fn? (get-in @state [:eval-sources :mcp])))
      (tu/write-edn! (config/global-config-path) {:settings {:eval-source false}})
      (@#'mcp/reload-servers! @@#'mcp/state-atom)
      (is (nil? (get-in @state [:eval-sources :mcp]))))))
