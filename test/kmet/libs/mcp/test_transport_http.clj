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
    (is (= {} (http/request! conn "tools/list" {} 5000 nil nil)))
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
    (is (= {:ok true} (http/request! conn "tools/list" {:cursor "c"} 5000 nil nil)))
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
    (is (= {} (http/request! conn "tools/list" {} 5000 nil nil)))
    (is (= 2 @attempts) "one retry with the refreshed headers")))

(deftest retry-on-401-keeps-routing-and-custom-headers
  ;; the retry swaps in fresh auth but must keep the request's own
  ;; headers: a modern tools/call's Mcp-Method/Mcp-Name routing headers
  ;; and its Mcp-Param-* custom headers (SEP-2243)
  (let [requests (atom [])
        attempts (atom 0)
        conn (assoc (modern-conn)
                    :request-fn (fn [_ opts]
                                  (swap! requests conj opts)
                                  (if (= 1 (swap! attempts inc))
                                    (response 401 "application/json" "{}")
                                    (response 200 "application/json"
                                              "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")))
                    :on-401 (fn [_] {"Authorization" "Bearer fresh"}))]
    (is (= {} (http/request! conn "tools/call" {:name "t"} 5000 nil
                             {:http-headers {"Mcp-Param-Region" "us-west1"}})))
    (is (= 2 (count @requests)))
    (doseq [opts @requests]
      (is (= "tools/call" (get (:headers opts) "Mcp-Method")))
      (is (= "t" (get (:headers opts) "Mcp-Name")))
      (is (= "us-west1" (get (:headers opts) "Mcp-Param-Region"))))
    (is (= "Bearer fresh" (get (:headers (second @requests)) "Authorization")))))

(deftest request!-empty-response-is-an-error
  (let [conn (http/connect! "http://server"
                            {:request-fn (fn [_ _] (response 202 "application/json" ""))})
        e (try (http/request! conn "tools/list" {} 5000 nil nil) nil
               (catch Exception e e))]
    (is (some? e))
    (is (re-find #"empty response to tools/list" (ex-message e)))))

;; ─── Custom headers from tool parameters (SEP-2243) ──────────────────────

(def ^:private annotated-schema
  {:type "object"
   :properties {"region" {:type "string" :x-mcp-header "Region"}
                "priority" {:type "integer" :x-mcp-header "Priority"}
                "dryRun" {:type "boolean" :x-mcp-header "DryRun"}
                "tenant" {:type "object"
                          :properties {"id" {:type "integer" :x-mcp-header "TenantId"}}}
                "query" {:type "string"}}})

(deftest x-mcp-param-headers-derivation
  (testing "string, integer and boolean mirror per the spec"
    (is (= {"Mcp-Param-Region" "us-west1"
            "Mcp-Param-Priority" "42"
            "Mcp-Param-DryRun" "false"}
           (http/x-mcp-param-headers annotated-schema
                                     {:region "us-west1" :priority 42 :dryRun false}))))
  (testing "nested properties mirror their instance path"
    (is (= {"Mcp-Param-TenantId" "7"}
           (http/x-mcp-param-headers annotated-schema {:tenant {:id 7}}))))
  (testing "an absent, null or non-mirrored parameter is omitted"
    (is (= {"Mcp-Param-Region" "r"}
           (http/x-mcp-param-headers annotated-schema {:region "r"})))
    (is (= {} (http/x-mcp-param-headers annotated-schema
                                        {:region nil :priority nil :dryRun nil :query "q"})))
    (is (= {} (http/x-mcp-param-headers nil {:region "r"})))
    (is (= {} (http/x-mcp-param-headers {} {:region "r"}))))
  (testing "string keys work too (a tool call whose arguments were JSON)"
    (is (= {"Mcp-Param-Region" "r"}
           (http/x-mcp-param-headers annotated-schema {"region" "r"}))))
  (testing "unsafe values ride the Base64 sentinel and round-trip"
    (doseq [v ["Héllo, 世界" " padded " "line1\nline2" "=?base64?literal?="]]
      (let [encoded (get (http/x-mcp-param-headers annotated-schema {:region v})
                         "Mcp-Param-Region")]
        (is (str/starts-with? encoded "=?base64?") v)
        (is (= v (String. (.decode (java.util.Base64/getDecoder)
                                   (subs encoded 9 (- (count encoded) 2)))
                          "UTF-8"))))))
  (testing "conversion follows the value's JSON type, not the declared type"
    (is (= {"Mcp-Param-Region" "42"}
           (http/x-mcp-param-headers annotated-schema {:region 42})))
    (is (= {"Mcp-Param-Priority" "42"}
           (http/x-mcp-param-headers annotated-schema {:priority 42.0})))
    (is (= {"Mcp-Param-DryRun" "1"}
           (http/x-mcp-param-headers annotated-schema {:dryRun 1}))))
  (testing "integer values outside the JS safe range (or not integral) are omitted"
    (is (= {} (http/x-mcp-param-headers annotated-schema {:priority 9007199254740992})))
    (is (= {} (http/x-mcp-param-headers annotated-schema {:priority 42.5})))))

(deftest valid-x-mcp-header-cases
  (testing "no annotation is valid"
    (is (true? (http/valid-x-mcp-header? nil)))
    (is (true? (http/valid-x-mcp-header? {})))
    (is (true? (http/valid-x-mcp-header? {:type "object"
                                          :properties {"q" {:type "string"}}}))))
  (testing "a malformed schema value is not an annotation and does not throw"
    (is (true? (http/valid-x-mcp-header? {:type "object" :properties [1 2]})))
    (is (true? (http/valid-x-mcp-header? {:type "object" :$defs 5})))
    (is (= {} (http/x-mcp-param-headers {:type "object" :properties [1 2]} {:a 1}))))
  (testing "conforming annotations are valid"
    (is (true? (http/valid-x-mcp-header? annotated-schema)))
    (is (true? (http/valid-x-mcp-header? {:type "object"
                                          :properties {"q" {:type ["string" "null"]
                                                            :x-mcp-header "Q"}}}))))
  (testing "an invalid annotation rejects the defining tool"
    (doseq [bad [{:type "object" :properties {"q" {:type "string" :x-mcp-header ""}}}
                 {:type "object" :properties {"q" {:type "string" :x-mcp-header "Bad Name"}}}
                 {:type "object" :properties {"q" {:type "string" :x-mcp-header "a:b"}}}
                 {:type "object" :properties {"q" {:type "number" :x-mcp-header "Q"}}}
                 {:type "object" :properties {"q" {:x-mcp-header "Q"}}}
                 ;; duplicates compare case-insensitively, across depths
                 {:type "object"
                  :properties {"q" {:type "string" :x-mcp-header "Q"}
                               "r" {:type "object"
                                    :properties {"n" {:type "string"
                                                      :x-mcp-header "q"}}}}}
                 ;; unreachable: items / oneOf / $defs / the schema root
                 {:type "object" :properties {"q" {:type "string"}}
                  :$defs {"Q" {:type "string" :x-mcp-header "Q"}}}
                 {:type "object" :properties {"q" {:type "array"
                                                   :items {:type "string"
                                                           :x-mcp-header "Q"}}}}
                 ;; an unreachable chain stays unreachable at every depth
                 {:type "object" :properties {"q" {:type "array"
                                                   :items {:type "object"
                                                           :properties {"n" {:type "string"
                                                                             :x-mcp-header "N"}}}}}}
                 {:type "object" :properties {"q" {:type "string"}}
                  :$defs {"X" {:type "object"
                               :properties {"n" {:type "string"
                                                 :x-mcp-header "N"}}}}}
                 {:type "object" :properties {"q" {:oneOf [{:type "string"
                                                            :x-mcp-header "Q"}]}}}
                 {:type "object" :x-mcp-header "Q"}]]
      (is (false? (http/valid-x-mcp-header? bad)) (pr-str bad))))
  (testing "an invalid definition emits no headers"
    (is (= {} (http/x-mcp-param-headers
               {:type "object" :properties {"q" {:type "string" :x-mcp-header ""}}}
               {:q "v"})))))

(deftest request!-carries-custom-headers
  (let [requests (atom [])
        conn (assoc (modern-conn)
                    :request-fn (fn [url opts]
                                  (swap! requests conj [url opts])
                                  (response 200 "application/json"
                                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")))]
    (is (= {} (http/request! conn "tools/call" {:name "t"} 5000 nil
                             {:http-headers {"Mcp-Param-Region" "us-west1"
                                             "Mcp-Param-DryRun" "true"}})))
    (let [[_ opts] (first @requests)]
      (is (= "us-west1" (get (:headers opts) "Mcp-Param-Region")))
      (is (= "true" (get (:headers opts) "Mcp-Param-DryRun")))
      (is (= "tools/call" (get (:headers opts) "Mcp-Method"))))
    (reset! requests [])
    (http/request! conn "tools/list" {} 5000 nil nil)
    (is (nil? (get (:headers (first @requests)) "Mcp-Param-Region"))
        "a request without custom headers carries none")))

;; ─── Subscriptions (2026-07-28) ───────────────────────────────────────────

(defn- sse-frame
  "One SSE message frame carrying MSG."
  [msg]
  (str "event: message\ndata: " (json/generate-string msg) "\n\n"))

(defn- wait-for
  "Poll PRED for up to a second: the listen reader's cleanup runs on its
   own thread after the promise is delivered."
  [pred]
  (loop [n 0]
    (cond
      (pred) true
      (< n 100) (do (Thread/sleep 10) (recur (inc n)))
      :else false)))

(defn- subscription-meta
  [id]
  {:_meta {(protocol/meta-key "subscriptionId") id}})

(defn- ack-frame
  [id agreed]
  {:jsonrpc "2.0"
   :method protocol/ack-method
   :params (merge {:notifications agreed} (subscription-meta id))})

(defn- list-changed-frame
  [id]
  {:jsonrpc "2.0"
   :method "notifications/tools/list_changed"
   :params (subscription-meta id)})

(deftest listen!-reads-the-subscription-stream
  (let [conn-level (atom [])
        observed (atom [])
        requests (atom [])
        capture (fn [_url opts]
                  (let [sent (json/parse-string (:body opts) true)]
                    (swap! requests conj sent)
                    (if (= protocol/listen-method (:method sent))
                      (do
                        (is (nil? (:timeout opts))
                            "no deadline: curl gets no --max-time")
                        (is (= protocol/listen-method
                               (get (:headers opts) "Mcp-Method")))
                        (response 200 "text/event-stream"
                                  (str ":\r\n"   ;; keepalive comment
                                       (sse-frame (ack-frame 1 {:toolsListChanged true}))
                                       (sse-frame (list-changed-frame 1))
                                       (sse-frame (list-changed-frame 999))
                                       ;; a server->client request on the
                                       ;; subscription stream is answered
                                       (sse-frame {:jsonrpc "2.0" :id 9001
                                                   :method "ping" :params {}}))))
                      (response 202 "application/json" ""))))
        conn (assoc (modern-conn)
                    :on-notification (fn [_ msg] (swap! conn-level conj msg))
                    :request-fn capture)]
    (reset! (:last-used conn) 0)
    (let [{:keys [ended]} (http/listen! conn {:toolsListChanged true}
                                        (fn [msg] (swap! observed conj msg)))]
      (is (= :ended (deref ended 2000 ::timeout))
          "the stream's EOF ends the subscription")
      (is (= [protocol/ack-method "notifications/tools/list_changed"]
             (mapv :method @observed))
          "the ack and our own stamped frame reach the observer; the stale id is dropped")
      (is (= [protocol/ack-method "notifications/tools/list_changed"]
             (mapv :method @conn-level))
          "both frames are delivered to the conn-level handler too")
      (is (= {:jsonrpc "2.0" :id 9001 :result {}} (second @requests))
          "a server->client request on the subscription stream is answered")
      (is (pos? (transport/last-used conn))
          "a keepalive comment touched the conn (idle-reaper exemption)")
      (is (wait-for #(nil? @(:listen conn)))
          "the listen slot is cleared when the stream ends"))))

(deftest listen!-stop-aborts-an-endless-stream
  ;; a connected but silent pipe: reads block until the subscription is
  ;; stopped (OUT must stay referenced — a collected writer breaks it)
  (let [in (java.io.PipedInputStream.)
        out (java.io.PipedOutputStream. in)
        conn (assoc (modern-conn)
                    :request-fn (fn [_ _]
                                  {:status 200
                                   :headers {"content-type" "text/event-stream"}
                                   :body in}))
        {:keys [stop! ended]} (http/listen! conn {:toolsListChanged true} nil)]
    (try
      (Thread/sleep 50)
      (is (= ::pending (deref ended 0 ::pending)) "the stream is still open")
      (stop!)
      (is (= :stopped (deref ended 2000 ::timeout)) "stop! ends the subscription")
      (is (wait-for #(nil? @(:listen conn))))
      (finally (.close out)))))

(deftest listen!-rejects-a-non-stream-answer
  (let [conn (assoc (modern-conn)
                    :request-fn (fn [_ _] (response 405 "application/json" "{}")))
        e (try (http/listen! conn {:toolsListChanged true} nil)
               nil (catch Exception e e))]
    (is (some? e))
    (is (re-find #"answered 405" (ex-message e)))
    (is (= :mcp-error (:type (ex-data e))))
    (is (nil? @(:listen conn)) "nothing is left open"))
  (let [conn (assoc (modern-conn)
                    :request-fn (fn [_ _] (response 200 "application/json" "{}")))]
    (is (thrown-with-msg? Exception #"answered 200 application/json"
                          (http/listen! conn {:toolsListChanged true} nil)))))
