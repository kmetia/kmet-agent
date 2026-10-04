(ns kmet.libs.mcp.test-transport-http
  "Streamable-HTTP transport without sockets: responses are injected with
   :request-fn, so parsing, header negotiation, retry and timeout paths run
   as plain unit tests (the socket-level cases stay in the extension's
   scripts/validate-client.bb)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.json :as json]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport :as transport]
            [kmet.libs.mcp.transport.http :as http]))

(defn- modern-conn
  "A streamable-HTTP conn whose era atom records the modern revision."
  ([] (modern-conn "http://server"))
  ([url]
   (assoc (http/connect! url {})
          :era (atom {:era :modern :version protocol/modern-protocol-version}))))

(defn- response
  ([status content-type body] (response status content-type body {}))
  ([status content-type body headers]
   {:status status
    :headers (into {"content-type" content-type} headers)
    :body (io/input-stream (.getBytes (str body) "UTF-8"))}))

(deftest header-value-is-case-insensitive
  (is (= "application/json" (transport/header-value {"Content-Type" "application/json"} "content-type")))
  (is (= "s1" (transport/header-value {:mcp-session-id "s1"} "Mcp-Session-Id")))
  (is (nil? (transport/header-value {} "missing"))))

(deftest request-headers-negotiate-the-session
  (let [conn (http/connect! "http://server" {})]
    (is (= "application/json" (get (http/http-request-headers conn) "Content-Type")))
    (is (nil? (get (http/http-request-headers conn) "Mcp-Session-Id"))
        "no session header before initialize")
    (reset! (:session-id conn) "s1")
    (reset! (:protocol-version conn) "2025-11-25")
    (let [headers (http/http-request-headers conn)]
      (is (= "s1" (get headers "Mcp-Session-Id")))
      (is (= "2025-11-25" (get headers "MCP-Protocol-Version"))))
    (let [conn (http/connect! "http://server"
                              {:auth-headers (fn [] {"Authorization" "Bearer t"})})]
      (is (= "Bearer t" (get (http/http-request-headers conn) "Authorization"))))))

(deftest encode-header-value-cases
  (testing "header-safe values pass through"
    (is (= "http-echo" (http/encode-header-value "http-echo")))
    (is (= "a b" (http/encode-header-value "a b")))
    (is (= "doc://x/y?z=1" (http/encode-header-value "doc://x/y?z=1")))
    (is (= "" (http/encode-header-value ""))))
  (testing "values that cannot ride a header unchanged are Base64-sent"
    (doseq [unsafe ["=?base64?literal" " leading" "trailing " "café" "a\nb" "a\tb"]]
      (let [encoded (http/encode-header-value unsafe)]
        (is (str/starts-with? encoded "=?base64?") unsafe)
        (is (str/ends-with? encoded "?=") unsafe)
        (is (= unsafe
               (String. (.decode (java.util.Base64/getDecoder)
                                 (subs encoded 9 (- (count encoded) 2)))
                        "UTF-8"))
            (str (pr-str unsafe) " round-trips"))))))

(deftest modern-routing-headers
  (let [conn (modern-conn)]
    (is (= "tools/call" (get (http/http-request-headers conn "tools/call" {:name "t"})
                             "Mcp-Method")))
    (is (= "t" (get (http/http-request-headers conn "tools/call" {:name "t"}) "Mcp-Name")))
    (is (= "doc://x" (get (http/http-request-headers conn "resources/read" {:uri "doc://x"})
                          "Mcp-Name")))
    (is (= "brief" (get (http/http-request-headers conn "prompts/get" {:name "brief"})
                        "Mcp-Name")))
    (is (str/starts-with? (get (http/http-request-headers conn "tools/call" {:name " read"})
                               "Mcp-Name")
                          "=?base64?")
        "an unsafe name is mirrored as a Base64 sentinel")
    (is (nil? (get (http/http-request-headers conn "tools/list" {}) "Mcp-Name"))
        "only the named methods mirror a name")
    (is (nil? (get (http/http-request-headers conn) "Mcp-Method"))
        "the 1-arity (SSE, session DELETE) carries no routing headers"))
  (let [conn (http/connect! "http://server" {})]
    (is (nil? (get (http/http-request-headers conn "tools/list" {}) "Mcp-Method"))
        "a legacy conn mirrors nothing")))

(deftest modern-conns-never-mirror-a-session
  (let [requests (atom [])
        conn (assoc (modern-conn)
                    :request-fn (fn [url opts]
                                  (swap! requests conj [url opts])
                                  (response 200 "application/json"
                                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"
                                            {"Mcp-Session-Id" "stale"})))]
    (is (= {} (http/request! conn "tools/list" {} 5000 nil)))
    (let [[_ opts] (first @requests)]
      (is (= "tools/list" (get (:headers opts) "Mcp-Method")))
      (is (nil? (get (:headers opts) "Mcp-Session-Id"))
          "a modern POST never echoes a session header"))
    (is (nil? @(:session-id conn)) "a modern conn never captures a session id")
    (reset! (:session-id conn) "stale")
    (is (false? (http/terminate-http-session! conn))
        "a modern conn never DELETEs a session")))

(deftest parse-json-response
  (let [conn (http/connect! "http://server" {})]
    (is (= {:jsonrpc "2.0" :id 7 :result {:ok true}}
           (http/parse-http-response
            conn (response 200 "application/json"
                           "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}")
            7 nil)))))

(deftest parse-sse-response
  (let [conn (http/connect! "http://server" {})
        body (str "event: message\n"
                  "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}\n\n")]
    (is (= {:jsonrpc "2.0" :id 7 :result {:ok true}}
           (http/parse-http-response
            conn (response 200 "text/event-stream; charset=utf-8" body) 7 nil)))))

(deftest parse-sse-response-dispatches-foreign-messages
  (let [progress (atom [])
        conn-level (atom [])
        conn (http/connect! "http://server"
                            {:on-notification (fn [_ msg] (swap! conn-level conj msg))})
        body (str "event: message\n"
                  "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\","
                  "\"params\":{\"progressToken\":\"p\",\"progress\":1}}\n\n"
                  "event: message\n"
                  "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}\n\n")]
    (is (= {:jsonrpc "2.0" :id 7 :result {:ok true}}
           (http/parse-http-response conn (response 200 "text/event-stream" body) 7
                                     (fn [msg] (swap! progress conj msg)))))
    (is (= 1 (count @progress)) "notifications/progress reaches the per-request hook")
    (is (= 1 (count @conn-level)) "every notification reaches the conn-level handler")))

(deftest parse-response-errors
  (let [conn (http/connect! "http://server" {})]
    (testing "unexpected content type"
      (let [e (try (http/parse-http-response conn (response 200 "text/plain" "hi") 1 nil)
                   nil (catch Exception e e))]
        (is (some? e))
        (is (re-find #"unexpected response content type" (ex-message e)))
        (is (= :mcp-error (:type (ex-data e))))
        (is (= 200 (:status (ex-data e))))))
    (testing "HTTP error status carries the body"
      (let [e (try (http/parse-http-response conn (response 500 "application/json" "boom") 1 nil)
                   nil (catch Exception e e))]
        (is (some? e))
        (is (re-find #"HTTP 500" (ex-message e)))
        (is (re-find #"boom" (ex-message e)))))
    (testing "a JSON-RPC error body lands in the ex-data"
      (let [e (try (http/parse-http-response
                    conn
                    (response 400 "application/json"
                              (str "{\"jsonrpc\":\"2.0\",\"id\":1,"
                                   "\"error\":{\"code\":-32020,"
                                   "\"message\":\"Header mismatch\","
                                   "\"data\":{\"supported\":[\"2026-07-28\"]}}}"))
                    1 nil)
                   nil (catch Exception e e))]
        (is (= 400 (:status (ex-data e))))
        (is (= -32020 (:code (ex-data e))))
        (is (= "Header mismatch" (:message (ex-data e))))
        (is (= {:supported ["2026-07-28"]} (:data (ex-data e))))))
    (testing "a non-JSON error body keeps the status only"
      (let [e (try (http/parse-http-response conn (response 500 "text/plain" "boom") 1 nil)
                   nil (catch Exception e e))]
        (is (= 500 (:status (ex-data e))))
        (is (nil? (:code (ex-data e))))))))

(deftest request!-posts-and-parses
  (let [requests (atom [])
        conn (http/connect! "http://server"
                            {:request-fn (fn [url opts]
                                           (swap! requests conj [url opts])
                                           (response 200 "application/json"
                                                     "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"
                                                     {"Mcp-Session-Id" "s1"}))})]
    (is (= {:ok true} (http/request! conn "tools/list" {:cursor "c"} 5000 nil)))
    (is (= 1 (count @requests)))
    (is (= "http://server" (ffirst @requests)))
    (let [[_ opts] (first @requests)
          sent (json/parse-string (:body opts) true)]
      (is (= "tools/list" (:method sent)))
      (is (= {:cursor "c"} (:params sent)))
      (is (= 1 (:id sent))))
    (is (= "s1" @(:session-id conn)) "the session id is captured for later POSTs")))

(deftest request!-retries-once-on-401
  (let [attempts (atom 0)
        conn (http/connect! "http://server"
                            {:request-fn (fn [_ _]
                                           (if (= 1 (swap! attempts inc))
                                             (response 401 "application/json" "{}")
                                             (response 200 "application/json"
                                                       "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")))
                             :on-401 (fn [response]
                                       (is (= 401 (:status response)))
                                       {"Authorization" "Bearer refreshed"})})]
    (is (= {} (http/request! conn "tools/list" {} 5000 nil)))
    (is (= 2 @attempts) "one retry with the refreshed headers")))

(deftest request!-empty-response-is-an-error
  (let [conn (http/connect! "http://server"
                            {:request-fn (fn [_ _] (response 202 "application/json" ""))})
        e (try (http/request! conn "tools/list" {} 5000 nil) nil
               (catch Exception e e))]
    (is (some? e))
    (is (re-find #"empty response to tools/list" (ex-message e)))))
