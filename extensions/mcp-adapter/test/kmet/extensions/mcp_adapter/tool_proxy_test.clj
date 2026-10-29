(ns kmet.extensions.mcp-adapter.tool-proxy-test
  "The proxy tool's invocation surface (§9.1-9.2): param coercion and
   validation, the sandbox tool records, and dispatch over a state atom
   with no live connections."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.concurrent]
            [kmet.extensions.mcp-adapter.test-util :as tu]
            [kmet.extensions.mcp-adapter.tool-proxy :as proxy]))

;; ─── Param coercion ───────────────────────────────────────────────────────

(deftest flag-coercion
  (is (true? (proxy/flag true)))
  (is (false? (proxy/flag false)))
  (is (true? (proxy/flag "true")))
  (is (false? (proxy/flag "false")))
  (testing "anything else passes through (truthy for the dispatch)"
    (is (= "yes" (proxy/flag "yes")))
    (is (= 1 (proxy/flag 1)))))

(defn- state-atom
  "A state atom whose lazy connect fails (no spawn): the server exists but
   never connects."
  [config cached]
  (atom (assoc (if cached
                 (tu/cached-state config cached (:settings config))
                 (tu/relay-state config))
               :ensure-connected-fn (fn [_] (throw (ex-info "no connect" {})))
               :disconnect-fn (fn [_] nil))))

(def ^:private config
  {:settings {:tool-prefix :server}
   :mcp-servers {"fs" {:command "bb"}
                 "web" {:command "bb"}}})

(def ^:private cached
  {"fs" {:tools [{:name "read_file" :description "Read a file"
                  :inputSchema {:type "object"
                                :properties {"path" {:type "string"}}}}
                 {:name "amb" :description "Also on web"}]
         :resource-templates [{:name "project" :uriTemplate "file:///{p}"}]}
   "web" {:tools [{:name "amb" :description "Also on fs"}]}})

;; ─── Param validation ─────────────────────────────────────────────────────

(deftest validate-params
  (tu/with-agent-dir
    (fn [_]
      (let [st (state-atom config cached)
            run #(proxy/execute st %)]
        (testing "a non-string search is rejected with the expected type"
          (let [r (run {:search {"query" "echo"}})]
            (is (true? (:is-error r)))
            (is (str/includes? (:content r) "mcp({ search: {\"query\" \"echo\"} })"))
            (is (str/includes? (:content r) "expected a string"))))
        (testing "a non-number limit is rejected"
          (let [r (run {:limit "5" :search "x"})]
            (is (str/includes? (:content r) "mcp({ limit: \"5\" })"))
            (is (str/includes? (:content r) "expected a number"))))
        (testing "a non-boolean flag is rejected"
          (let [r (run {:regex [:x] :search "x"})]
            (is (str/includes? (:content r) "expected a boolean"))))
        (testing "the string spellings of a flag are accepted"
          (is (false? (:is-error (run {:search "file" :regex "false"})))))
        (testing "valid params pass validation into the dispatch"
          (is (false? (:is-error (run {})))))))))

;; ─── Dispatch with no live connection ─────────────────────────────────────

(deftest dispatch-read-only-modes
  (tu/with-agent-dir
    (fn [_]
      (let [st (state-atom config cached)]
        (testing "no params prints the status"
          (is (str/starts-with? (:content (proxy/execute st {})) "fs (")))
        (testing "search reads the cache"
          (is (str/includes? (:content (proxy/execute st {:search "read_file"}))
                             "fs_read_file")))
        (testing "describe reads the cache"
          (is (str/includes? (:content (proxy/execute st {:describe "read_file"}))
                             "Server: fs")))
        (testing "list reads the cache"
          (is (str/includes? (:content (proxy/execute st {:list "fs"}))
                             "fs_read_file")))
        (testing "server: lists that server"
          (is (str/includes? (:content (proxy/execute st {:server "web"}))
                             "web_amb")))
        (testing "disconnect answers without a connection"
          (is (= {:content "Disconnected \"fs\"." :is-error false}
                 (proxy/execute st {:disconnect "fs"}))))))))

(deftest dispatch-tool-resolution
  (tu/with-agent-dir
    (fn [_]
      (let [st (state-atom config cached)]
        (testing "an unknown tool names the search mode"
          (is (= {:content "Tool \"nope\" not found. Use mcp({ search: \"...\" }) to search."
                  :is-error true}
                 (proxy/execute st {:tool "nope"}))))
        (testing "a name on two servers must be disambiguated"
          (let [r (proxy/execute st {:tool "amb"})]
            (is (true? (:is-error r)))
            (is (str/includes? (:content r) "matches multiple servers"))))
        (testing "a resolved tool with a dead connection reports the connect error"
          (let [r (proxy/execute st {:tool "read_file"})]
            (is (true? (:is-error r)))
            (is (str/includes? (:content r) "not connected"))))
        (testing "connect reports a failed connect"
          (let [r (proxy/execute st {:connect "fs"})]
            (is (true? (:is-error r)))
            (is (str/includes? (:content r) "Failed to connect to \"fs\""))))))))

(deftest dispatch-honors-the-failure-backoff
  (tu/with-agent-dir
    (fn [_]
      (let [attempts (atom 0)
            state (tu/cached-state config cached (:settings config))
            st (atom (assoc state
                            :ensure-connected-fn (fn [_] (swap! attempts inc) nil)
                            :disconnect-fn (fn [_] nil)))]
        (reset! (get-in @st [:servers "fs" :failed-at])
                (- (kmet.libs.concurrent/monotonic-ms) 5000))
        (let [r (proxy/execute st {:tool "read_file"})]
          (testing "a lazy use inside the backoff window fails without a retry"
            (is (true? (:is-error r)))
            (is (zero? @attempts))))
        (testing "outside the window the connect is attempted again"
          (reset! (get-in @st [:servers "fs" :failed-at])
                  (- (kmet.libs.concurrent/monotonic-ms) 90000))
          (proxy/execute st {:tool "read_file"})
          (is (= 1 @attempts)))))))

;; ─── Sandbox tool records ─────────────────────────────────────────────────

(deftest script-tool-records
  (tu/with-agent-dir
    (fn [_]
      (let [st (state-atom config cached)
            records (proxy/script-tool-records @st)]
        (testing "every cached tool becomes one record under its display name"
          (is (= #{"fs_read_file" "fs_amb" "web_amb"} (set (keys records)))))
        (testing "a record carries the proxy-facing tool shape"
          (let [record (get records "fs_read_file")]
            (is (= "MCP: read_file" (:label record)))
            (is (= "Read a file" (:description record)))
            (is (= "string" (get-in record [:parameters :properties "path" :type])))
            (is (true? (:streams? record)))
            (is (fn? (:execute record)))))
        (testing "under :none a name claimed by two servers is dropped, not guessed"
          (let [none-config (assoc config :settings {:tool-prefix :none})
                none-state (state-atom none-config cached)
                none-records (proxy/script-tool-records @none-state)]
            (is (not (contains? none-records "amb")))
            (is (contains? none-records "read_file"))))
        (testing "a disabled server contributes no records"
          (let [disabled (state-atom
                          (assoc config :mcp-servers
                                 {"fs" {:command "bb" :disabled true}})
                          cached)]
            (is (empty? (proxy/script-tool-records @disabled)))))))))
