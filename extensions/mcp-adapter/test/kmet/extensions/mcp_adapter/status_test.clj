(ns kmet.extensions.mcp-adapter.status-test
  "The read-only views (§9.2-9.5): status, describe, list and list-all.
   Every view reads config plus the metadata cache — no connection, no
   spawn — so a fresh cached state drives each assertion."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.concurrent :as concurrent]
            [kmet.extensions.mcp-adapter.status :as status]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

(defn- lines [text] (str/split-lines text))

;; ─── status (§9.5) ────────────────────────────────────────────────────────

(deftest status-empty
  (is (= "No MCP servers configured."
         (status/status-text (tu/relay-state {:settings {} :mcp-servers {}})))))

(deftest status-rows
  (tu/with-agent-dir
    (fn [_]
      (let [config {:settings {:direct-tools true :tool-prefix :mcp}
                    :mcp-servers {"fs" {:command "npx" :lifecycle :lazy}
                                  "off" {:command "npx" :disabled true}
                                  "broken" {}
                                  "remote" {:url "https://x/mcp"
                                            :lifecycle :eager
                                            :auth :bearer
                                            :bearer-token "tok"}}}
            state (tu/cached-state config
                                   {"fs" {:tools [{:name "read_file" :description "Read"}]}}
                                   {:direct-tools true :tool-prefix :mcp})
            text (status/status-text state)
            rows (lines text)]
        (testing "every configured server gets a row"
          (is (some #(str/starts-with? % "fs (lazy, idle") rows))
          (is (some #(str/starts-with? % "off (lazy, disabled, 0 tools)") rows))
          (is (some #(str/starts-with? % "broken (lazy, misconfigured, 0 tools)") rows)))
        (testing "a cached server reports its tool count and cache age"
          (is (some #(re-find #"^fs \(lazy, idle, 1 tools, cache \d+m old\)$" %) rows)))
        (testing "a configured auth mode is labeled"
          (is (some #(str/includes? % "remote (eager, idle, 0 tools, bearer)") rows)))
        (testing "the settings line reports the adapter's switches"
          (is (some #(str/includes? % "settings: direct-tools=on") rows))
          (is (some #(str/includes? % "tool-prefix=:mcp") rows))
          (is (some #(str/includes? % "proxy-tool=enabled") rows)))
        (testing "the hint line points at the proxy tool"
          (is (str/includes? text "mcp({ server: \"name\" })")))))))

(deftest status-failed-and-connected
  (tu/with-agent-dir
    (fn [_]
      (let [config {:settings {}
                    :mcp-servers {"bad" {:command "nope"}
                                  "live" {:url "https://x/mcp"}}}
            state (tu/cached-state config {} {})
            failed-at (get-in state [:servers "bad" :failed-at])
            error (get-in state [:servers "bad" :error])]
        (reset! failed-at (- (concurrent/monotonic-ms) 12000))
        (reset! error "spawn failed: no such file")
        (reset! (get-in state [:servers "live" :conn])
                {:transport :streamable-http
                 :closed (atom false)
                 :protocol-version (atom "2025-11-25")})
        (let [text (status/status-text state)]
          (testing "a failure row reports the age and the error tail"
            (is (str/includes? text "bad (lazy, failed 12s ago — spawn failed: no such file, 0 tools)")))
          (testing "a live connection reports the negotiated revision"
            (is (str/includes? text "live (lazy, connected, proto 2025-11-25)"))))))))

(deftest status-truncates-a-long-error
  (tu/with-agent-dir
    (fn [_]
      (let [config {:settings {} :mcp-servers {"bad" {:command "nope"}}}
            state (tu/cached-state config {} {})]
        (reset! (get-in state [:servers "bad" :failed-at])
                (- (concurrent/monotonic-ms) 3000))
        (reset! (get-in state [:servers "bad" :error])
                (apply str (repeat 300 "x")))
        (let [row (first (lines (status/status-text state)))]
          (is (str/includes? row "failed 3s ago"))
          (is (str/includes? row "…"))
          (is (< (count row) 220)))))))

;; ─── describe (§9.4) ──────────────────────────────────────────────────────

(deftest describe-unknown-tool
  (tu/with-agent-dir
    (fn [_]
      (let [state (tu/cached-state {:settings {} :mcp-servers {"a" {:command "bb"}}} {} {})]
        (is (= {:content "Tool \"nope\" not found. Use mcp({ search: \"...\" }) to search."
                :is-error true}
               (status/describe-text state "nope")))))))

(deftest describe-parameters
  (tu/with-agent-dir
    (fn [_]
      (let [schema {:type "object"
                    :properties {"query" {:type "string" :description "Search text"}
                                 "limit" {:type "integer" :default 10}
                                 "mode" {:enum ["fast" "slow"]}}
                    :required ["query"]}
            state (tu/cached-state
                   {:settings {} :mcp-servers {"a" {:command "bb"}}}
                   {"a" {:tools [{:name "echo" :description "Echo it"
                                  :inputSchema schema}]}}
                   {})
            out (:content (status/describe-text state "echo"))]
        (testing "the header carries the display name, server and description"
          (is (str/starts-with? out "a_echo\nServer: a"))
          (is (str/includes? out "Echo it")))
        (testing "one line per parameter with type and required/optional"
          (is (str/includes? out "limit (type: integer, optional, default: 10)"))
          (is (str/includes? out "query (type: string, required)"))
          (is (str/includes? out "mode (type: any, optional, enum: [\"fast\" \"slow\"])")))
        (testing "a parameter description is indented under its line"
          (is (str/includes? out "      Search text")))))))

(deftest describe-without-parameters
  (tu/with-agent-dir
    (fn [_]
      (let [state (tu/cached-state
                   {:settings {} :mcp-servers {"a" {:command "bb"}}}
                   {"a" {:tools [{:name "ping" :description "Ping"}]}}
                   {})]
        (is (str/includes? (:content (status/describe-text state "ping"))
                           "No parameters defined."))))))

;; ─── list (§9.2) ──────────────────────────────────────────────────────────

(deftest list-errors
  (tu/with-agent-dir
    (fn [_]
      (let [config {:settings {} :mcp-servers {"off" {:command "bb" :disabled true}
                                               "on" {:command "bb"}}}
            state (tu/cached-state config {} {})]
        (testing "an unknown server names the status call"
          (is (= {:content "Server \"nope\" not found. Use mcp({}) to see available servers."
                  :is-error true}
                 (status/list-text state "nope"))))
        (testing "a disabled server points at /mcp enable"
          (is (str/includes? (:content (status/list-text state "off"))
                             "is disabled. Run /mcp enable off")))
        (testing "a configured-but-unconnected server invites a connect"
          (is (str/includes? (:content (status/list-text state "on"))
                             "is configured but not connected. Use mcp({ connect: \"on\" })")))))))

(deftest list-cached-tools-and-templates
  (tu/with-agent-dir
    (fn [_]
      (let [state (tu/cached-state
                   {:settings {} :mcp-servers {"fs" {:command "bb"}}}
                   {"fs" {:tools [{:name "read_file" :description "Read a file"}
                                  {:name "write_file"
                                   :description (apply str (repeat 80 "long "))}]
                          :resource-templates [{:name "Issues (all)"
                                                :uriTemplate "file:///issues/{state}"}]}}
                   {})
            out (:content (status/list-text state "fs"))
            rows (lines out)]
        (testing "the header counts the tools and says the connection is cold"
          (is (str/starts-with? (first rows) "fs (2 tools, not connected, cached):")))
        (testing "tools are listed with their display name and a cut description"
          (is (some #(str/starts-with? % "- fs_read_file - Read a file") rows))
          (is (some #(and (str/starts-with? % "- fs_write_file - long")
                          (str/ends-with? % "…")) rows)))
        (testing "a resource template lists under its read tool name"
          (is (some #(str/includes? % "fs_read_issues_all — file:///issues/{state}")
                    rows)))))))

(deftest list-all
  (tu/with-agent-dir
    (fn [_]
      (let [config {:settings {}
                    :mcp-servers {"fs" {:command "bb"}
                                  "off" {:command "bb" :disabled true}
                                  "broken" {}}}
            state (tu/cached-state config
                                   {"fs" {:tools [{:name "read_file"}]}}
                                   {})
            out (:content (status/list-all-text state))]
        (is (str/starts-with? out "MCP servers:"))
        (is (str/includes? out "  fs (1 tools)"))
        (is (str/includes? out "  off (disabled)"))
        (is (str/includes? out "  broken (misconfigured)"))))))
