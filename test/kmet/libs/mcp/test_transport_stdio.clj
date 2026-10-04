(ns kmet.libs.mcp.test-transport-stdio
  "The stdio transport is MCP policy over kmet.libs.jsonrpc: these tests
   pin the policy (ping replies, progress routing, error translation) and
   one ^:slow end-to-end run against a real subprocess."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.jsonrpc :as jrpc]
            [kmet.libs.mcp.client :as mcp]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport.stdio :as stdio]))

(def ^:private ping-handler @#'stdio/ping-handler)
(def ^:private notification-handler @#'stdio/notification-handler)
(def ^:private translate-exception @#'stdio/translate-exception)
(def ^:private dead-message @#'stdio/dead-message)

;; ─── Policy ───────────────────────────────────────────────────────────────

(deftest ping-handler-policy
  (let [handle (ping-handler)]
    (is (= {} (handle "ping" nil)) "ping answers with an empty result")
    (is (nil? (handle "sampling/createMessage" {}))
        "unimplemented server requests return nil → -32601 in jsonrpc")))

(deftest notification-routing
  (let [progress (atom [])
        conn-level (atom [])
        callback (atom (fn [msg] (swap! progress conj msg)))
        handler (notification-handler (atom :conn) callback
                                      (fn [c msg] (swap! conn-level conj [c msg])))]
    (handler {:method "notifications/progress" :params {:progress 1}})
    (is (= 1 (count @progress)) "progress reaches the in-flight callback")
    (is (= [[:conn {:method "notifications/progress" :params {:progress 1}}]]
           @conn-level)
        "the conn-level handler sees every notification")
    (handler {:method "notifications/tools/list_changed"})
    (is (= 1 (count @progress)) "only progress reaches the request callback")
    (is (= 2 (count @conn-level)))
    (reset! callback nil)
    (handler {:method "notifications/progress"})
    (is (= 1 (count @progress)) "no in-flight callback → progress is dropped")))

(deftest translate-exception-maps-jsonrpc-failures
  (let [conn {:stderr-tail (atom ["bad interpreter"])}]
    (testing "timeout"
      (let [e (translate-exception conn
                                   (ex-info "timed out" {:type :kmet.libs.jsonrpc/timeout
                                                         :id 9 :method "tools/call"})
                                   "tools/call" 120000)]
        (is (= :mcp-error (:type (ex-data e))))
        (is (= 120000 (:timeout-ms (ex-data e))))
        (is (re-find #"timed out after 120000ms: tools/call" (ex-message e)))))
    (testing "server error"
      (let [e (translate-exception conn
                                   (ex-info "jsonrpc error"
                                            {:type :kmet.libs.jsonrpc/request-error
                                             :error {:code -32000 :message "nope"}})
                                   "tools/call" 1000)]
        (is (= "MCP error -32000: nope" (ex-message e)))
        (is (= -32000 (:code (ex-data e))))
        (is (= "nope" (:message (ex-data e))))))
    (testing "transport death carries the stderr tail"
      (let [e (translate-exception conn
                                   (ex-info "dead" {:type :kmet.libs.jsonrpc/transport-dead})
                                   "tools/call" 1000)]
        (is (= :mcp-error (:type (ex-data e))))
        (is (re-find #"process exited while waiting for tools/call" (ex-message e)))
        (is (re-find #"bad interpreter" (ex-message e)))))
    (testing "other exceptions pass through unchanged"
      (let [other (ex-info "boom" {:type :something-else})]
        (is (identical? other (translate-exception conn other "x" 1)))))))

(deftest timeout-cancels-the-abandoned-request
  ;; a dropped connection is not a cancellation: the timeout path must tell
  ;; the server to stop working on the request we stopped waiting for, on
  ;; the id jsonrpc allocated for it
  (testing "a legacy conn sends the bare cancellation"
    (let [sent (atom [])
          conn {:stderr-tail (atom [])}
          e (with-redefs [jrpc/notify! (fn [_ method params]
                                         (swap! sent conj [method params]))]
              (translate-exception conn
                                   (ex-info "timed out" {:type :kmet.libs.jsonrpc/timeout
                                                         :id 7 :method "tools/call"})
                                   "tools/call" 300))]
      (is (= [["notifications/cancelled" {:requestId 7
                                          :reason "kmet: tools/call — timed out"}]]
             @sent))
      (is (= :mcp-error (:type (ex-data e))))
      (is (= 300 (:timeout-ms (ex-data e))))))
  (testing "a modern conn carries the era _meta (this path bypasses client/notify!)"
    (let [sent (atom [])
          conn {:stderr-tail (atom [])
                :era (atom {:era :modern :version "2026-07-28"})}]
      (with-redefs [jrpc/notify! (fn [_ method params]
                                   (swap! sent conj [method params]))]
        (translate-exception conn
                             (ex-info "timed out" {:type :kmet.libs.jsonrpc/timeout
                                                   :id 8 :method "tools/call"})
                             "tools/call" 300))
      (is (= [["notifications/cancelled"
               {:requestId 8
                :reason "kmet: tools/call — timed out"
                :_meta {:io.modelcontextprotocol/protocolVersion "2026-07-28"
                        :io.modelcontextprotocol/clientInfo protocol/client-info
                        :io.modelcontextprotocol/clientCapabilities {}}}]]
             @sent)))))

(deftest dead-message-shape
  (is (= "MCP connect failed: process exited"
         (dead-message {:stderr-tail (atom [])} nil)))
  (is (= "MCP connect failed: process exited while waiting for tools/call (stderr: a — b)"
         (dead-message {:stderr-tail (atom ["a" "b"])} "tools/call"))))

;; ─── End to end over a real subprocess ────────────────────────────────────

(def ^:private fake-server-code
  ;; A minimal MCP stdio server: answers initialize, sends a server->client
  ;; ping after initialized, sends a progress notification before each
  ;; tools/list result, and reports whether the ping auto reply arrived.
  ;; clojure.data.json is built into babashka.
  (str "(require '[clojure.data.json :as json])"
       "(defn send! [m] (println (json/write-str m)) (flush))"
       "(def ping-replied (atom false))"
       "(loop []"
       "  (when-let [line (read-line)]"
       "    (let [msg (json/read-str line :key-fn keyword)]"
       "      (cond"
       "        (= \"initialize\" (:method msg))"
       "        (send! {:jsonrpc \"2.0\" :id (:id msg)"
       "                :result {:protocolVersion \"2025-11-25\" :capabilities {}}})"
       "        (= \"notifications/initialized\" (:method msg))"
       "        (send! {:jsonrpc \"2.0\" :id 900 :method \"ping\"})"
       "        (= 900 (:id msg))"
       "        (reset! ping-replied true)"
       "        (= \"tools/list\" (:method msg))"
       "        (do (send! {:jsonrpc \"2.0\" :method \"notifications/progress\""
       "                    :params {:progress 50 :total 100 :message \"half\"}})"
       "            (send! {:jsonrpc \"2.0\" :id (:id msg)"
       "                    :result {:tools [{:name \"echo\"}] :pingReplied @ping-replied}}))"
       "        :else nil))"
       "    (recur)))"))

(deftest ^:slow jsonrpc-session-serves-mcp-requests
  (let [conn (stdio/connect! {:command "bb" :args ["-e" fake-server-code]} {})]
    (try
      (is (stdio/alive? conn))
      (is (= "2025-11-25" (:protocol-version (mcp/initialize! conn))))
      (let [seen (atom [])
            result (mcp/request! conn "tools/list" {}
                                 {:on-notification #(swap! seen conj %)})]
        (is (= [{:name "echo"}] (:tools result)))
        (is (= [{:jsonrpc "2.0" :method "notifications/progress"
                 :params {:progress 50 :total 100 :message "half"}}]
               @seen)
            "progress routes to the in-flight request's callback"))
      ;; the reader dispatches the server's ping before it delivers the
      ;; first tools/list response, so by the second round trip the
      ;; auto reply is guaranteed to have run; with no callback the
      ;; progress notification is dropped
      (is (true? (:pingReplied (mcp/request! conn "tools/list" {}))))
      (finally
        (stdio/close! conn)))))
