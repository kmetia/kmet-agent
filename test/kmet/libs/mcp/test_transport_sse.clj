(ns kmet.libs.mcp.test-transport-sse
  "Legacy SSE transport internals against an injected stream: endpoint
   resolution, event assembly and stream death. The real-server path is
   covered by the extension's scripts/validate-client.bb."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.mcp.transport :as transport]
            [kmet.libs.mcp.transport.sse :as sse]))

(def ^:private endpoint-url @#'sse/sse-endpoint-url)
(def ^:private drain-stream! @#'sse/drain-sse-stream)
(def ^:private endpoint! @#'sse/endpoint!)

(deftest endpoint-resolution
  (testing "a bare path resolves against the GET url"
    (is (= "http://h:1/msg" (endpoint-url "http://h:1/base" "/msg")))
    (is (= "http://h:1/msg" (endpoint-url "http://h:1/base" "  /msg  "))))
  (testing "a JSON endpoint event carries the uri"
    (is (= "http://other/x" (endpoint-url "http://h:1/base" "{\"uri\":\"http://other/x\"}")))
    (is (= "http://h:1/base" (endpoint-url "http://h:1/base" "{}"))
        "JSON without a uri falls back to the GET url"))
  (testing "anything else falls back to the GET url"
    (is (= "http://h:1/base" (endpoint-url "http://h:1/base" "relative/path")))
    (is (= "http://h:1/base" (endpoint-url "http://h:1/base" "")))))

(deftest drain-reads-endpoint-and-messages
  (let [body (str "event: endpoint\ndata: /messages\n\n"
                  "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}\n\n")
        ch (async/chan 8)
        endpoint (atom nil)
        ready (promise)]
    (drain-stream! (io/input-stream (.getBytes body "UTF-8")) ch endpoint
                   "http://h:1/sse" ready)
    (is (= "http://h:1/messages" @endpoint))
    (is (realized? ready) "the endpoint event releases the wait")
    (is (= {:jsonrpc "2.0" :id 7 :result {}} (async/<!! ch)))
    (testing "stream end delivers the eof marker"
      (is (= transport/eof-marker (async/<!! ch))))))

(deftest drain-drops-non-json-payloads
  (let [body (str "event: message\ndata: not-json\n\n"
                  "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n")
        ch (async/chan 8)]
    (drain-stream! (io/input-stream (.getBytes body "UTF-8")) ch (atom nil)
                   "http://h/sse" (promise))
    (is (= {:jsonrpc "2.0" :id 1 :result {}} (async/<!! ch)))))

(deftest endpoint-wait
  (let [conn (sse/connect! "http://h/sse" {})]
    (testing "an already-resolved endpoint returns immediately"
      (reset! (:endpoint-atom conn) "http://h/messages")
      (is (= "http://h/messages" (endpoint! conn))))
    (testing "a not-yet-arrived endpoint event is awaited, not polled"
      (reset! (:endpoint-atom conn) nil)
      (let [ready (promise)]
        (reset! (:endpoint-ready conn) ready)
        (future (Thread/sleep 20)
                (reset! (:endpoint-atom conn) "http://h/messages")
                (deliver ready true))
        (is (= "http://h/messages" (endpoint! conn)))))
    (testing "no endpoint event fails with the MCP error after the wait"
      (reset! (:endpoint-atom conn) nil)
      (reset! (:endpoint-ready conn) (promise))
      (with-redefs-fn {#'sse/endpoint-wait-ms 10}
        (fn []
          (is (thrown-with-msg? Exception #"no SSE endpoint received"
                                (endpoint! conn))))))))

(deftest connection-lifecycle
  (let [conn (sse/connect! "http://h/sse" {})]
    (is (false? (sse/alive? conn)) "no stream open yet")
    (reset! (:stream-open conn) true)
    (is (true? (sse/alive? conn)))
    (sse/close! conn)
    (is (false? (sse/alive? conn)))
    (is (false? @(:stream-open conn)))
    (is (true? @(:closed conn)) "close! marks the conn closed"))
  (let [conn (sse/connect! "http://h/sse" {:reconnect-fn (fn [_] nil)})]
    (is (fn? (:reconnect-fn conn)) "the client's reconnect hook is captured")))
