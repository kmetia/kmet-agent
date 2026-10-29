(ns kmet.extensions.mcp-adapter.client-test
  "§12.3 transport integration against the fake servers: the stdio,
   streamable-HTTP and legacy SSE paths, protocol-version negotiation, the
   modern (2026-07-28) era and its subscriptions, and the process/conn
   lifecycle. Every test here spawns a fixture (^:slow); the fixtures are
   `scripts/fake-mcp-server.bb` and `scripts/fake-http-mcp-server.bb`."
  (:require [babashka.process :as proc]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.json :as json]
            [kmet.libs.mcp.client :as mcp]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport.http :as mcp-http]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

(def ^:private stdio-tools
  #{"echo" "add" "slow" "boom" "ping-mid" "who-asks" "add-tool" "storm"
    "list-count"})

(defn- stdio-def
  ([] {:command "bb" :lifecycle :lazy
       :args [(tu/fixture "fake-mcp-server.bb")]})
  ([& args]
   {:command "bb" :lifecycle :lazy
    :args (into [(tu/fixture "fake-mcp-server.bb")] args)}))

(defn- http-def [port & [query]]
  {:url (str "http://127.0.0.1:" port "/mcp" (or query ""))
   :http-transport :streamable-http})

(defn- text [result] (:text (protocol/format-result result)))

;; ─── stdio ────────────────────────────────────────────────────────────────

(deftest ^:slow stdio-handshake-and-catalog
  (tu/with-connect [{:keys [conn tools protocol-version server-info]} (stdio-def)]
    (testing "the legacy handshake negotiates the revision and identity"
      (is (= "2025-11-25" protocol-version))
      (is (= "fake-mcp-server" (:name server-info))))
    (testing "tools/list pagination collects every tool"
      (is (= 9 (count tools)))
      (is (= stdio-tools (set (map :name tools)))))
    (testing "tools/call returns the server's text"
      (is (str/includes? (text (mcp/request! conn "tools/call"
                                             {:name "echo" :arguments {:message "hi"}}))
                         "echo: hi"))
      (is (= "5" (text (mcp/request! conn "tools/call"
                                     {:name "add" :arguments {:a 2 :b 3}})))))
    (testing "prompts/get maps the declared arguments"
      (is (str/includes? (get-in (mcp/get-prompt conn "brief" {"topic" "clojure"})
                                 [:messages 0 :content :text])
                         "Briefly summarize: clojure")))
    (testing "resources/read returns the text content"
      (is (str/includes? (get-in (mcp/read-resource conn "file:///README.md")
                                 [:contents 0 :text])
                         "# Fake README")))))

(deftest ^:slow stdio-prompts-and-resources-catalog
  (tu/with-connect [{:keys [prompts resources resource-templates]} (stdio-def)]
    (testing "prompts and resources are cached from the wire"
      (is (= #{"brief" "review"} (set (map :name prompts))))
      (is (= #{"README" "schema"} (set (map :name resources))))
      (is (= #{"file:///{path}" "file:///issues/{state}"
               "github://repo/{owner}/{repo}/issues/{number}"}
             (set (map :uriTemplate resource-templates)))))))

(deftest ^:slow stdio-uri-templates
  (tu/with-connect [{:keys [conn]} (stdio-def)]
    (testing "a template variable expands into a concrete uri"
      (is (= "file:///src/main.clj"
             (mcp/expand-uri-template "file:///{path}" {:path "src/main.clj"}))))
    (testing "unsafe characters are percent-encoded"
      (is (= "file:///a%20b%3F.txt"
             (mcp/expand-uri-template "file:///{path}" {:path "a b?.txt"}))))
    (testing "an unknown variable is left alone"
      (is (= "file:///{path}" (mcp/expand-uri-template "file:///{path}" {}))))
    (testing "a read through an expanded template reaches the resource"
      (is (= "(ns main)"
             (get-in (mcp/read-resource conn
                                        (mcp/expand-uri-template
                                         "file:///{path}" {:path "src/main.clj"}))
                     [:contents 0 :text]))))))

(deftest ^:slow stdio-progress-notifications
  (tu/with-connect [{:keys [conn]} (stdio-def)]
    (testing "progress notifications stream while a call runs"
      (let [progress (atom [])
            result (mcp/request! conn "tools/call"
                                 {:name "slow" :arguments {:ms 400}
                                  :_meta {:progressToken "t"}}
                                 {:timeout-ms 5000
                                  :on-notification
                                  (fn [n] (swap! progress conj
                                                 (get-in n [:params :progress])))})]
        (is (= [25 50 75] @progress))
        (is (= "slept" (text result)))))
    (testing "without a progress token the server sends nothing"
      (let [progress (atom [])]
        (mcp/request! conn "tools/call"
                      {:name "slow" :arguments {:ms 100}}
                      {:timeout-ms 5000
                       :on-notification (fn [n] (swap! progress conj n))})
        (is (empty? @progress))))
    (testing "a notification mid-request is dropped, the response still arrives"
      (is (= "pong" (text (mcp/request! conn "tools/call"
                                        {:name "ping-mid" :arguments {}
                                         :_meta {:progressToken "t"}})))))))

(deftest ^:slow stdio-server-requests-and-concurrency
  (tu/with-connect [{:keys [conn]} (stdio-def)]
    (testing "the client answers a server ping and refuses an optional feature"
      (let [answers (json/parse-string
                     (text (mcp/request! conn "tools/call"
                                         {:name "who-asks" :arguments {}}
                                         {:timeout-ms 5000}))
                     true)]
        (is (= {} (get-in answers [:9001 :result])))
        (is (= -32601 (get-in answers [:9002 :error :code])))))
    (testing "two live requests on one conn both complete"
      (let [a (future (try (mcp/request! conn "tools/call"
                                         {:name "who-asks" :arguments {}}
                                         {:timeout-ms 5000})
                           :ok
                           (catch Exception e (ex-message e))))
            _ (Thread/sleep 150)
            b (future (try (mcp/request! conn "tools/call"
                                         {:name "echo" :arguments {:message "hi"}}
                                         {:timeout-ms 5000})
                           :ok
                           (catch Exception e (ex-message e))))]
        (is (= [:ok :ok] [@a @b]))))))

(deftest ^:slow stdio-error-and-timeout-paths
  (tu/with-connect [{:keys [conn]} (stdio-def)]
    (testing "a tool error result surfaces as :is-error"
      (let [formatted (protocol/format-result
                       (mcp/request! conn "tools/call" {:name "boom" :arguments {}}))]
        (is (true? (:is-error formatted)))
        (is (= "kaboom" (:text formatted)))))
    (testing "a JSON-RPC error surfaces as an exception"
      (is (try (mcp/request! conn "tools/call" {:name "nope" :arguments {}})
               false
               (catch Exception e (str/includes? (ex-message e) "MCP error -32602")))))
    (testing "a request timeout names the budget"
      (is (try (mcp/request! conn "tools/call" {:name "slow" :arguments {:ms 5000}}
                             {:timeout-ms 300})
               false
               (catch Exception e (str/includes? (ex-message e)
                                                 "timed out after 300ms")))))))

(deftest ^:slow stdio-list-changed-reaches-the-handler
  (let [seen (atom [])
        definition (stdio-def)
        {:keys [conn]} (mcp/connect! definition
                                     {:on-notification (fn [_ msg]
                                                         (swap! seen conj (:method msg)))})]
    (try
      (mcp/request! conn "tools/call" {:name "add-tool" :arguments {}})
      (testing "the list_changed notification reaches the conn handler"
        (is (tu/wait-until #(some #{"notifications/tools/list_changed"} @seen))))
      (testing "re-listing sees the added tool"
        (is (some #(= "echo2" (:name %)) (mcp/list-all-tools conn))))
      (finally (mcp/close! conn)))))

(deftest ^:slow stdio-abandoned-request-cancels
  (let [dir (tu/temp-dir! "mcp-cancel")
        log-file (str dir "/fake.log")]
    (try
      (let [{:keys [conn]} (mcp/connect!
                            (assoc (stdio-def) :env {"FAKE_LOG" log-file})
                            {})]
        (try
          (try (mcp/request! conn "tools/call" {:name "slow" :arguments {:ms 1200}}
                             {:timeout-ms 200})
               (catch Exception _ nil))
          ;; the fake reads stdin inside the slow call, so the cancel lands
          ;; once that call returns
          (Thread/sleep 1600)
          (let [logged (try (slurp log-file) (catch Exception _ ""))]
            (testing "an abandoned request is cancelled, not just dropped"
              (is (str/includes? logged "notifications/cancelled"))
              (is (str/includes? logged "tools/call"))))
          (finally (mcp/close! conn))))
      (finally (tu/delete-tree! dir)))))

(deftest ^:slow stdio-process-lifecycle
  (let [{:keys [conn]} (mcp/connect! (stdio-def) {})]
    (try
      (is (true? (mcp/alive? conn)))
      (proc/destroy-tree (:proc conn))
      (Thread/sleep 300)
      (testing "a killed server is detected"
        (is (not (mcp/alive? conn))))
      (testing "the next request reports the process exit with its stderr"
        (is (try (mcp/request! conn "tools/list" {})
                 false
                 (catch Exception e (str/includes? (ex-message e) "process exited")))))
      (finally (mcp/close! conn))))
  (testing "close kills the child process tree"
    (let [{:keys [conn]} (mcp/connect! (stdio-def) {})
          p (:proc conn)]
      (mcp/close! conn)
      (Thread/sleep 300)
      (is (not (proc/alive? p))))))

;; ─── streamable HTTP ──────────────────────────────────────────────────────

(deftest ^:slow http-streamable-catalog
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (tu/with-connect [{:keys [conn tools protocol-version]} (http-def (:port server))]
      (testing "the handshake negotiates the revision and mints a session"
        (is (= "2025-11-25" protocol-version))
        (is (= 4 (count tools)))
        (is (string? @(:session-id conn)))
        (is (= "2025-11-25" @(:protocol-version conn))))
      (testing "the negotiated revision rides on every POST"
        (is (= "2025-11-25"
               (text (mcp/request! conn "tools/call"
                                   {:name "http-headers" :arguments {}})))))
      (testing "tools/call reaches the server"
        (is (str/includes? (text (mcp/request! conn "tools/call"
                                               {:name "http-echo"
                                                :arguments {:message "hey"}}))
                           "http-echo: hey"))
        (is (= "42" (text (mcp/request! conn "tools/call"
                                        {:name "http-add"
                                         :arguments {:a 40 :b 2}})))))
      (testing "prompts/get maps arguments"
        (is (str/includes? (get-in (mcp/get-prompt conn "http-brief" {"topic" "x"})
                                   [:messages 0 :content :text])
                           "http brief: x")))
      (testing "SSE response bodies carry progress before the result"
        (let [progress (atom [])
              result (mcp/request! conn "tools/call"
                                   {:name "http-slow" :arguments {}
                                    :_meta {:progressToken "t"}}
                                   {:timeout-ms 5000
                                    :on-notification
                                    (fn [n] (swap! progress conj
                                                   (get-in n [:params :progress])))})]
          (is (= [10 50] @progress))
          (is (= "finally" (text result)))))
      (testing "a timeout kills the conn"
        (is (try (mcp/request! conn "tools/call" {:name "http-slow" :arguments {}}
                               {:timeout-ms 300})
                 false
                 (catch Exception e (str/includes? (ex-message e)
                                                   "timed out after 300ms"))))
        (is (not (mcp/alive? conn)))))))

(deftest ^:slow http-prompts-and-resources
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (tu/with-connect [{:keys [prompts resources resource-templates]}
                      (http-def (:port server))]
      (testing "prompts and resources come back over HTTP"
        (is (= #{"http-brief"} (set (map :name prompts))))
        (is (= #{"HTTP doc"} (set (map :name resources))))
        (is (= #{"http://fake/page/{id}"}
               (set (map :uriTemplate resource-templates))))))))

(deftest ^:slow http-session-is-deleted
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (let [{:keys [conn]} (mcp/connect! (http-def (:port server)) {})]
      (mcp/close! conn)
      (testing "a healthy session is released with an HTTP DELETE"
        (is (tu/wait-until #(str/includes? (tu/server-output server) "SESSION DELETED")
                           5000))))))

(deftest ^:slow http-sse-response-bodies
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (testing "an Accept override forces the server's SSE response path"
      (tu/with-connect-opts (http-def (:port server))
        {:auth-headers (fn [] {"Accept" "text/event-stream"})}
        (fn [{:keys [conn]}]
          (is (str/includes? (text (mcp/request! conn "tools/call"
                                                 {:name "http-echo"
                                                  :arguments {:message "sse"}}))
                             "http-echo: sse")))))))

(deftest ^:slow http-modern-era
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (tu/with-connect [{:keys [conn tools protocol-version server-info]}
                      (http-def (:port server) "?era=modern")]
      (testing "the modern era is discovered and recorded"
        (is (= "2026-07-28" protocol-version))
        (is (true? (mcp/modern? conn)))
        (is (= "fake-http-mcp-server" (:name server-info))))
      (testing "the catalog drops the invalid x-mcp-header definition"
        (is (= #{"http-echo" "http-add" "http-slow" "http-headers"
                 "http-add-tool" "http-region"}
               (set (map :name tools)))))
      (testing "no session is minted"
        (is (nil? @(:session-id conn))))
      (testing "the routing headers are sent"
        (is (= "2026-07-28"
               (text (mcp/request! conn "tools/call"
                                   {:name "http-headers" :arguments {}})))))
      (testing "tools/call and resources/read carry Mcp-Name"
        (is (= "http-echo: hey"
               (text (mcp/request! conn "tools/call"
                                   {:name "http-echo" :arguments {:message "hey"}}))))
        (is (= "http resource content"
               (get-in (mcp/request! conn "resources/read" {:uri "http://fake/doc"})
                       [:contents 0 :text])))))))

(deftest ^:slow http-modern-mcp-param-headers
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (tu/with-connect [{:keys [conn tools]} (http-def (:port server) "?era=modern")]
      (let [region-schema (:inputSchema (first (filter #(= "http-region" (:name %))
                                                       tools)))]
        (testing "x-mcp-header parameters are mirrored into Mcp-Param-*"
          (let [args {:region "us-west1" :priority 42 :dryRun true}]
            (is (= "region=us-west1 priority=42 dryRun=true"
                   (text (mcp/request! conn "tools/call"
                                       {:name "http-region" :arguments args}
                                       {:http-headers
                                        (mcp-http/x-mcp-param-headers
                                         region-schema args)}))))))
        (testing "a missing Mcp-Param-* is rejected by the server"
          (is (try (mcp/request! conn "tools/call"
                                 {:name "http-region" :arguments {:region "x"}})
                   false
                   (catch Exception e (str/includes? (ex-message e) "-32020")))))
        (testing "unsafe Mcp-Param values round-trip through Base64"
          (let [args {:region "Héllo, 世界"}]
            (is (= "region=Héllo, 世界 priority= dryRun="
                   (text (mcp/request! conn "tools/call"
                                       {:name "http-region" :arguments args}
                                       {:http-headers
                                        (mcp-http/x-mcp-param-headers
                                         region-schema args)}))))))))))

;; ─── era hints ────────────────────────────────────────────────────────────

(deftest ^:slow era-hints
  (testing "a stale legacy hint on stdio re-probes to modern"
    (tu/with-connect-opts (stdio-def "--era" "modern")
      {:protocol-era {:era :legacy :version "2025-11-25"}}
      (fn [{:keys [conn protocol-version protocol-era]}]
        (is (= "2026-07-28" protocol-version))
        (is (= {:era :modern :version "2026-07-28"} protocol-era))
        (is (true? (mcp/modern? conn))))))
  (testing "a consistent modern hint skips detection"
    (tu/with-connect-opts (stdio-def "--era" "modern")
      {:protocol-era {:era :modern :version "2026-07-28"}}
      (fn [{:keys [protocol-era]}]
        (is (= {:era :modern :version "2026-07-28"} protocol-era)))))
  (testing "the HTTP twin discards a stale legacy hint and finds modern"
    (tu/with-server [server "fake-http-mcp-server.bb"]
      (tu/with-connect-opts (http-def (:port server) "?era=modern")
        {:protocol-era {:era :legacy :version "2025-11-25"}}
        (fn [{:keys [protocol-era]}]
          (is (= {:era :modern :version "2026-07-28"} protocol-era)))))))

;; ─── subscriptions (2026-07-28) ───────────────────────────────────────────

(defn- listen-and-trigger
  "One modern subscription: the ack is the first frame, the agreed filter
   is visible, a trigger call's list_changed reaches the handler, and the
   catalog re-listed afterwards contains ADDED."
  [label definition trigger added]
  (let [seen (atom [])
        {:keys [conn]} (mcp/connect! definition
                                     {:on-notification (fn [_ msg]
                                                         (swap! seen conj msg))})]
    (try
      (let [listener (mcp/listen! conn {:toolsListChanged true})]
        (Thread/sleep 800)
        (testing (str label " subscription is live")
          (is (true? (mcp/listening? conn))))
        (testing (str label " ack is the first frame")
          (is (true? (:first-ack? @listener))))
        (testing (str label " agreed filter")
          (is (= {:toolsListChanged true} (:agreed @listener))))
        (mcp/request! conn "tools/call" {:name trigger :arguments {}})
        (testing (str label " list_changed reached the handler")
          (is (tu/wait-until #(some (fn [m]
                                      (str/includes? (str (:method m)) "list_changed"))
                                    @seen))))
        (testing (str label " catalog re-listed after the change")
          (is (contains? (set (map :name (mcp/list-all-tools conn))) added))))
      (finally (mcp/close! conn)))
    (testing (str label " close stops the subscription")
      (is (false? (mcp/listening? conn))))))

(deftest ^:slow subscriptions
  (listen-and-trigger "stdio" (stdio-def "--era" "modern") "add-tool" "echo2")
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (listen-and-trigger "streamable-http"
                        (http-def (:port server) "?era=modern")
                        "http-add-tool" "http-echo2")))

;; ─── legacy SSE ───────────────────────────────────────────────────────────

(deftest ^:slow legacy-sse-transport
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (tu/with-connect [{:keys [conn tools]}
                      {:url (str "http://127.0.0.1:" (:port server) "/sse")
                       :http-transport :sse}]
      (testing "the SSE transport lists the catalog"
        (is (= 4 (count tools))))
      (testing "the endpoint is resolved from the stream"
        (is (str/ends-with? @(:endpoint-atom conn) "/sse")))
      (testing "tools/call works over the SSE transport"
        (is (str/includes? (text (mcp/request! conn "tools/call"
                                               {:name "http-echo"
                                                :arguments {:message "stream"}}))
                           "http-echo: stream"))))))

;; ─── protocol version negotiation ─────────────────────────────────────────

(deftest ^:slow protocol-version-negotiation
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (let [base (str "http://127.0.0.1:" (:port server) "/mcp")]
      (testing "an older server revision stays usable and is stored on the conn"
        (tu/with-connect [{:keys [conn protocol-version]}
                          {:url (str base "?version=2025-06-18")
                           :http-transport :streamable-http}]
          (is (= "2025-06-18" protocol-version))
          (is (= "2025-06-18" @(:protocol-version conn)))
          (is (= "2025-06-18"
                 (text (mcp/request! conn "tools/call"
                                     {:name "http-headers" :arguments {}}))))))
      (testing "the oldest supported revision is accepted"
        (tu/with-connect [result {:url (str base "?version=2024-11-05")
                                  :http-transport :streamable-http}]
          (is (= "2024-11-05" (:protocol-version result)))))
      (testing "an unsupported revision is rejected outright"
        (is (try (mcp/connect! {:url (str base "?version=2026-07-28")
                                :http-transport :streamable-http}
                               {})
                 false
                 (catch Exception e
                   (str/includes? (ex-message e)
                                  "unsupported protocol version 2026-07-28"))))))))
