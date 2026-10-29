(ns kmet.extensions.mcp-adapter.protocol-test
  "Wire-shape validation for the modern (2026-07-28) fake servers: raw
   JSON-RPC over stdio and raw HTTP POSTs pin what the fakes and the lib
   client put on the wire, and prove the legacy modes are untouched.
   Spawns fixtures (^:slow)."
  (:require [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.http :as http]
            [kmet.libs.json :as json]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

(def ^:private modern-version "2026-07-28")

(defn- client-meta []
  {"io.modelcontextprotocol/protocolVersion" modern-version
   "io.modelcontextprotocol/clientInfo" {"name" "kmet-protocol-test"
                                         "version" "0.1.0"}
   "io.modelcontextprotocol/clientCapabilities" {}})

;; ─── Raw stdio JSON-RPC ───────────────────────────────────────────────────

(defn- start-line-reader
  "A daemon thread feeding lines from R into a queue; next-line! polls it
   with a deadline so a silent server fails the test instead of hanging it."
  [r]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        t (Thread. (fn []
                     (try
                       (loop []
                         (when-let [line (.readLine r)]
                           (.put q line)
                           (recur)))
                       (.put q ::eof)
                       (catch Exception e
                         (.put q (ex-info "stream read failed"
                                          {:type :stream-error} e))))))]
    (.setDaemon t true)
    (.start t)
    q))

(defn- next-line! [q timeout-ms]
  (let [v (.poll q timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
    (cond
      (nil? v) (throw (ex-info "timed out waiting for a wire message"
                               {:type :timeout}))
      (= v ::eof) (throw (ex-info "server closed the stream" {:type :eof}))
      (instance? Exception v) (throw ^Exception v)
      :else v)))

(defn- start-stdio [& args]
  (let [p (proc/process (into ["bb" (tu/fixture "fake-mcp-server.bb")] args)
                        {:in :stream :out :stream :err :inherit})]
    {:proc p
     :writer (io/writer (:in p))
     :queue (start-line-reader (io/reader (:out p)))
     :notifications (atom [])}))

(defn- send! [session msg]
  (.write ^java.io.Writer (:writer session) (str (json/generate-string msg) "\n"))
  (.flush ^java.io.Writer (:writer session)))

(defn- next-message! [session]
  (json/parse-string (next-line! (:queue session) 10000) true))

(defn- await-response!
  "Read until the response for ID arrives; anything else goes to
   :notifications for later assertions."
  [session id]
  (loop []
    (let [msg (next-message! session)]
      (if (and (contains? msg :id) (= id (:id msg)))
        msg
        (do (swap! (:notifications session) conj msg)
            (recur))))))

(defn- stop-stdio! [session]
  (try (proc/destroy-tree (:proc session)) (catch Exception _ nil)))

(defn- list-all-tools!
  "tools/list with ID, following the fake's cursor pages to the end.
   Returns {:tools [...] :last-r <last page result>}."
  [session meta id]
  (loop [cursor nil acc {:tools [] :last-r nil}]
    (send! session {:jsonrpc "2.0" :id id :method "tools/list"
                    :params (cond-> {:_meta meta} cursor (assoc :cursor cursor))})
    (let [r (:result (await-response! session id))
          acc (-> acc
                  (update :tools into (:tools r))
                  (assoc :last-r r))]
      (if-let [next (:nextCursor r)]
        (recur next acc)
        acc))))

;; ─── Raw HTTP ─────────────────────────────────────────────────────────────

(defn- header [resp name]
  (some (fn [[k v]] (when (= (str/lower-case (str k)) name) v)) (:headers resp)))

(defn- decode-body [resp]
  (when (seq (str (:body resp)))
    (json/parse-string (:body resp) true)))

(defn- post-mcp
  "POST one JSON-RPC message to URL with HEADERS (Content-Type and a
   JSON-only Accept are added)."
  [url msg headers]
  (http/request {:url url
                 :method :post
                 :headers (merge {"Content-Type" "application/json"
                                  "Accept" "application/json"}
                                 headers)
                 :body (json/generate-string msg)
                 :throw? false}))

(defn- request-headers
  "The standard modern request headers for MSG (version + method, plus the
   name/uri when the method carries one)."
  [msg]
  (cond-> {"MCP-Protocol-Version" modern-version
           "Mcp-Method" (:method msg)}
    (get-in msg [:params :name]) (assoc "Mcp-Name" (get-in msg [:params :name]))
    (get-in msg [:params :uri]) (assoc "Mcp-Name" (get-in msg [:params :uri]))))

(defn- check-http-error [label resp code]
  (let [body (decode-body resp)]
    (is (and (= 400 (:status resp))
             (= code (get-in body [:error :code])))
        label)))

(defn- read-sse-data!
  "The next data: payload from an SSE line queue (event:/comment/blank
   lines are skipped)."
  [q]
  (loop []
    (let [line (next-line! q 10000)]
      (if (str/starts-with? line "data:")
        (str/trim (subs line 5))
        (recur)))))

;; ─── stdio: modern ────────────────────────────────────────────────────────

(deftest ^:slow stdio-modern-wire-shape
  (let [s (start-stdio "--era" "modern")]
    (try
      (testing "server/discover reports the modern era"
        (send! s {:jsonrpc "2.0" :id 1 :method "server/discover"
                  :params {:_meta (client-meta)}})
        (let [r (:result (await-response! s 1))]
          (is (= "complete" (:resultType r)))
          (is (= [modern-version] (:supportedVersions r)))
          (is (true? (get-in r [:capabilities :tools :listChanged])))
          (is (= "fake-mcp-server"
                 (get-in r [:_meta :io.modelcontextprotocol/serverInfo :name])))
          (is (and (number? (:ttlMs r)) (string? (:cacheScope r))))))
      (testing "tools/list carries resultType, cache hints and every tool"
        (let [{:keys [tools last-r]} (list-all-tools! s (client-meta) 2)]
          (is (= "complete" (:resultType last-r)))
          (is (number? (:ttlMs last-r)))
          (is (= 9 (count tools)))))
      (testing "a request without _meta is refused"
        (send! s {:jsonrpc "2.0" :id 3 :method "tools/list" :params {}})
        (is (= -32602 (get-in (await-response! s 3) [:error :code]))))
      (testing "an unsupported revision names what is supported"
        (send! s {:jsonrpc "2.0" :id 4 :method "tools/list"
                  :params {:_meta (assoc (client-meta)
                                         "io.modelcontextprotocol/protocolVersion"
                                         "1900-01-01")}})
        (let [e (:error (await-response! s 4))]
          (is (= -32022 (:code e)))
          (is (= [modern-version] (get-in e [:data :supported])))
          (is (= "1900-01-01" (get-in e [:data :requested])))))
      (testing "a modern server rejects initialize"
        (send! s {:jsonrpc "2.0" :id 5 :method "initialize"
                  :params {:protocolVersion "2025-11-25" :capabilities {}
                           :clientInfo {}}})
        (let [e (:error (await-response! s 5))]
          (is (= -32601 (:code e)))
          (is (str/includes? (:message e) modern-version))))
      (testing "subscriptions/listen acknowledges first and only the supported filter"
        (send! s {:jsonrpc "2.0" :id 6 :method "subscriptions/listen"
                  :params {:_meta (client-meta)
                           :notifications {:toolsListChanged true
                                           :promptsListChanged true
                                           :resourcesListChanged true}}})
        (let [ack (next-message! s)]
          (is (= "notifications/subscriptions/acknowledged" (:method ack)))
          (is (= 6 (get-in ack [:params :_meta
                                :io.modelcontextprotocol/subscriptionId])))
          (is (= {:toolsListChanged true} (get-in ack [:params :notifications])))))
      (testing "a tool call emits a subscription-scoped list_changed"
        (send! s {:jsonrpc "2.0" :id 7 :method "tools/call"
                  :params {:_meta (client-meta) :name "add-tool" :arguments {}}})
        (let [n (next-message! s)]
          (is (= "notifications/tools/list_changed" (:method n)))
          (is (= 6 (get-in n [:params :_meta
                              :io.modelcontextprotocol/subscriptionId]))))
        (is (= "complete" (:resultType (:result (await-response! s 7))))))
      (testing "re-listing sees the added tool"
        (is (some #(= "echo2" (:name %))
                  (:tools (list-all-tools! s (client-meta) 8)))))
      (testing "a cancelled subscription stops receiving notifications"
        (send! s {:jsonrpc "2.0" :method "notifications/cancelled"
                  :params {:requestId 6 :reason "test"}})
        (send! s {:jsonrpc "2.0" :id 9 :method "tools/call"
                  :params {:_meta (client-meta) :name "add-tool" :arguments {}}})
        (await-response! s 9)
        (is (empty? @(:notifications s))))
      (finally (stop-stdio! s)))))

;; ─── stdio: legacy untouched ─────────────────────────────────────────────

(deftest ^:slow stdio-legacy-wire-shape
  (let [s (start-stdio)]
    (try
      (testing "the legacy initialize handshake answers the revision"
        (send! s {:jsonrpc "2.0" :id 1 :method "initialize"
                  :params {:protocolVersion "2025-11-25" :capabilities {}
                           :clientInfo {}}})
        (let [r (:result (await-response! s 1))]
          (is (= "2025-11-25" (:protocolVersion r)))
          (is (not (contains? r :resultType)))))
      (testing "server/discover is unknown to a legacy server"
        (send! s {:jsonrpc "2.0" :id 2 :method "server/discover" :params {}})
        (is (= -32601 (:code (:error (await-response! s 2))))))
      (testing "a legacy tools/list has no resultType"
        (send! s {:jsonrpc "2.0" :id 3 :method "tools/list" :params {}})
        (is (not (contains? (:result (await-response! s 3)) :resultType))))
      (finally (stop-stdio! s)))))

;; ─── streamable HTTP: modern ─────────────────────────────────────────────

(deftest ^:slow http-modern-wire-shape
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (let [url (str "http://127.0.0.1:" (:port server) "/mcp?era=modern")]
      (testing "server/discover answers over HTTP with no session"
        (let [msg {:jsonrpc "2.0" :id 1 :method "server/discover"
                   :params {:_meta (client-meta)}}
              resp (post-mcp url msg (request-headers msg))
              r (:result (decode-body resp))]
          (is (= 200 (:status resp)))
          (is (str/includes? (str (header resp "content-type")) "application/json"))
          (is (= "complete" (:resultType r)))
          (is (= [modern-version] (:supportedVersions r)))
          (is (= "fake-http-mcp-server"
                 (get-in r [:_meta :io.modelcontextprotocol/serverInfo :name])))
          (is (and (number? (:ttlMs r)) (string? (:cacheScope r))))
          (is (nil? (header resp "mcp-session-id")))))
      (testing "the routing and version headers are validated"
        (let [msg {:jsonrpc "2.0" :id 2 :method "tools/list"
                   :params {:_meta (client-meta)}}]
          (check-http-error "missing Mcp-Method"
                            (post-mcp url msg (dissoc (request-headers msg) "Mcp-Method"))
                            -32020)
          (check-http-error "wrong Mcp-Method"
                            (post-mcp url msg (assoc (request-headers msg)
                                                     "Mcp-Method" "tools/call"))
                            -32020)
          (check-http-error "missing MCP-Protocol-Version"
                            (post-mcp url msg (dissoc (request-headers msg)
                                                      "MCP-Protocol-Version"))
                            -32020)
          (check-http-error "version header/body mismatch"
                            (post-mcp url msg (assoc (request-headers msg)
                                                     "MCP-Protocol-Version" "2020-01-01"))
                            -32020)
          (check-http-error "missing _meta"
                            (post-mcp url {:jsonrpc "2.0" :id 2 :method "tools/list"
                                           :params {}}
                                      (request-headers msg))
                            -32020)))
      (testing "Mcp-Name is validated against the body"
        (let [msg {:jsonrpc "2.0" :id 3 :method "tools/call"
                   :params {:_meta (client-meta) :name "http-echo" :arguments {}}}]
          (check-http-error "missing Mcp-Name"
                            (post-mcp url msg (dissoc (request-headers msg) "Mcp-Name"))
                            -32020)
          (check-http-error "wrong Mcp-Name"
                            (post-mcp url msg (assoc (request-headers msg)
                                                     "Mcp-Name" "http-add"))
                            -32020)))
      (testing "a header error echoes the request id"
        (let [msg {:jsonrpc "2.0" :id 4 :method "tools/list"
                   :params {:_meta (client-meta)}}]
          (is (= 4 (get-in (decode-body (post-mcp url msg
                                                  (dissoc (request-headers msg)
                                                          "Mcp-Method")))
                           [:id])))))
      (testing "an unsupported revision draws -32022"
        (let [msg {:jsonrpc "2.0" :id 5 :method "tools/list"
                   :params {:_meta (assoc (client-meta)
                                          "io.modelcontextprotocol/protocolVersion"
                                          "1900-01-01")}}
              resp (post-mcp url msg (assoc (request-headers msg)
                                            "MCP-Protocol-Version" "1900-01-01"))
              e (:error (decode-body resp))]
          (is (= 400 (:status resp)))
          (is (= -32022 (:code e)))
          (is (= [modern-version] (get-in e [:data :supported])))
          (is (= "1900-01-01" (get-in e [:data :requested])))))
      (testing "a missing clientInfo is refused"
        (let [msg {:jsonrpc "2.0" :id 6 :method "tools/list"
                   :params {:_meta (dissoc (client-meta)
                                           "io.modelcontextprotocol/clientInfo")}}]
          (check-http-error "missing clientInfo"
                            (post-mcp url msg (request-headers msg)) -32602)))
      (testing "an unimplemented method is 404 with a JSON-RPC body"
        (let [msg {:jsonrpc "2.0" :id 7 :method "ping"
                   :params {:_meta (client-meta)}}
              resp (post-mcp url msg (request-headers msg))]
          (is (= 404 (:status resp)))
          (is (= -32601 (get-in (decode-body resp) [:error :code])))))
      (testing "a notification is accepted with 202 and no body"
        (let [msg {:jsonrpc "2.0" :method "notifications/initialized" :params {}}
              resp (post-mcp url msg {})]
          (is (= 202 (:status resp)))
          (is (empty? (str (:body resp))))))
      (testing "a Base64 Mcp-Name sentinel is decoded before comparison"
        (let [msg {:jsonrpc "2.0" :id 8 :method "tools/call"
                   :params {:_meta (client-meta) :name "http-echo"
                            :arguments {:message "b64"}}}
              resp (post-mcp url msg (assoc (request-headers msg)
                                            "Mcp-Name" "=?base64?aHR0cC1lY2hv?="))]
          (is (= 200 (:status resp)))
          (is (str/includes? (get-in (decode-body resp) [:result :content 0 :text])
                             "b64"))))
      (testing "a sentinel that does not decode is treated as a plain value"
        (let [msg {:jsonrpc "2.0" :id 88 :method "tools/call"
                   :params {:_meta (client-meta) :name "http-echo"
                            :arguments {:message "b64"}}}
              resp (post-mcp url msg (assoc (request-headers msg)
                                            "Mcp-Name" "=?base64?not-base64?="))]
          (is (= 400 (:status resp)))
          (is (= -32020 (get-in (decode-body resp) [:error :code])))))
      (testing "tools/list marks results complete and keeps the invalid definition"
        (let [msg {:jsonrpc "2.0" :id 9 :method "tools/list"
                   :params {:_meta (client-meta)}}
              r (:result (decode-body (post-mcp url msg (request-headers msg))))]
          (is (= "complete" (:resultType r)))
          (is (some #(= "http-add-tool" (:name %)) (:tools r)))
          (is (some #(= "http-region" (:name %)) (:tools r)))
          (is (some #(= "http-bad-header" (:name %)) (:tools r)))))
      (testing "a stale session header is ignored and never echoed"
        (let [msg {:jsonrpc "2.0" :id 10 :method "tools/list"
                   :params {:_meta (client-meta)}}
              resp (post-mcp url msg (assoc (request-headers msg)
                                            "Mcp-Session-Id" "stale-sess"))]
          (is (= 200 (:status resp)))
          (is (some? (:result (decode-body resp))))
          (is (nil? (header resp "mcp-session-id")))))
      (testing "the session-era GET and DELETE are gone"
        (is (= 405 (:status (http/request {:url url :method :get :throw? false}))))
        (is (= 405 (:status (http/request {:url url :method :delete :throw? false}))))))))

(deftest ^:slow http-modern-mcp-param-wire-shape
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (let [url (str "http://127.0.0.1:" (:port server) "/mcp?era=modern")]
      (testing "Mcp-Param-* headers are validated against the body"
        (let [msg {:jsonrpc "2.0" :id 11 :method "tools/call"
                   :params {:_meta (client-meta) :name "http-region"
                            :arguments {:region "us-west1" :priority 42
                                        :dryRun true}}}
              headers (merge (request-headers msg)
                             {"Mcp-Param-Region" "us-west1"
                              "Mcp-Param-Priority" "42"
                              "Mcp-Param-DryRun" "true"})]
          (is (= "region=us-west1 priority=42 dryRun=true"
                 (get-in (decode-body (post-mcp url msg headers))
                         [:result :content 0 :text])))))
      (testing "a missing, absent or mismatched param header is refused"
        (let [msg {:jsonrpc "2.0" :id 12 :method "tools/call"
                   :params {:_meta (client-meta) :name "http-region"
                            :arguments {:region "x"}}}]
          (check-http-error "missing Mcp-Param-Region"
                            (post-mcp url msg (request-headers msg)) -32020)
          (check-http-error "a header for an absent value"
                            (post-mcp url msg (assoc (request-headers msg)
                                                     "Mcp-Param-DryRun" "true"))
                            -32020)
          (check-http-error "a value that does not match the body"
                            (post-mcp url msg (assoc (request-headers msg)
                                                     "Mcp-Param-Region" "y"))
                            -32020)))
      (testing "a Base64 Mcp-Param value round-trips"
        (let [region "Héllo, 世界"
              msg {:jsonrpc "2.0" :id 13 :method "tools/call"
                   :params {:_meta (client-meta) :name "http-region"
                            :arguments {:region region}}}
              encoded (str "=?base64?"
                           (.encodeToString (java.util.Base64/getEncoder)
                                            (.getBytes region "UTF-8"))
                           "?=")]
          (is (= (str "region=" region " priority= dryRun=")
                 (get-in (decode-body (post-mcp url msg
                                                (assoc (request-headers msg)
                                                       "Mcp-Param-Region" encoded)))
                         [:result :content 0 :text]))))))))

(deftest ^:slow http-modern-subscriptions-listen
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (let [url (str "http://127.0.0.1:" (:port server) "/mcp?era=modern")
          msg {:jsonrpc "2.0" :id 77 :method "subscriptions/listen"
               :params {:_meta (client-meta)
                        :notifications {:toolsListChanged true
                                        :promptsListChanged true
                                        :resourcesListChanged true}}}
          resp (http/request {:url url
                              :method :post
                              :headers (merge {"Content-Type" "application/json"
                                               "Accept" "text/event-stream"}
                                              (request-headers msg))
                              :body (json/generate-string msg)
                              :as :stream :timeout nil :throw? false})]
      (try
        (is (= 200 (:status resp)))
        (is (str/includes? (str (header resp "content-type")) "text/event-stream"))
        (let [q (start-line-reader (io/reader (:body resp)))
              ack (json/parse-string (read-sse-data! q) true)]
          (testing "the ack is the first frame and names the subscription"
            (is (= "notifications/subscriptions/acknowledged" (:method ack)))
            (is (= 77 (get-in ack [:params :_meta
                                   :io.modelcontextprotocol/subscriptionId])))
            (is (= {:toolsListChanged true} (get-in ack [:params :notifications]))))
          (testing "a trigger call delivers a subscription-scoped event"
            (let [trigger {:jsonrpc "2.0" :id 78 :method "tools/call"
                           :params {:_meta (client-meta) :name "http-add-tool"
                                    :arguments {}}}]
              (post-mcp url trigger (request-headers trigger)))
            (let [n (json/parse-string (read-sse-data! q) true)]
              (is (= "notifications/tools/list_changed" (:method n)))
              (is (= 77 (get-in n [:params :_meta
                                   :io.modelcontextprotocol/subscriptionId]))))))
        (testing "re-listing sees the added tool"
          (let [list-msg {:jsonrpc "2.0" :id 79 :method "tools/list"
                          :params {:_meta (client-meta)}}]
            (is (some #(= "http-echo2" (:name %))
                      (get-in (decode-body (post-mcp url list-msg
                                                     (request-headers list-msg)))
                              [:result :tools])))))
        (finally (http/close! resp))))))

;; ─── streamable HTTP: legacy untouched ───────────────────────────────────

(deftest ^:slow http-legacy-wire-shape
  (tu/with-server [server "fake-http-mcp-server.bb"]
    (let [base (str "http://127.0.0.1:" (:port server) "/mcp")]
      (testing "the legacy initialize mints a session and has no resultType"
        (let [msg {:jsonrpc "2.0" :id 1 :method "initialize"
                   :params {:protocolVersion "2025-11-25" :capabilities {}
                            :clientInfo {}}}
              resp (post-mcp base msg {})
              body (decode-body resp)]
          (is (= "2025-11-25" (get-in body [:result :protocolVersion])))
          (is (not (contains? (:result body) :resultType)))
          (is (some? (header resp "mcp-session-id")))))
      (testing "a legacy tools/list has four tools and no resultType"
        (let [msg {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}}
              body (decode-body (post-mcp base msg {"Mcp-Session-Id" "sess-1"}))]
          (is (= 4 (count (get-in body [:result :tools]))))
          (is (not (contains? (:result body) :resultType)))))
      (testing "the version override answers a modern revision verbatim"
        (let [msg {:jsonrpc "2.0" :id 3 :method "initialize"
                   :params {:protocolVersion "2026-07-28" :capabilities {}
                            :clientInfo {}}}
              body (decode-body (post-mcp (str base "?version=2026-07-28") msg {}))]
          (is (= "2026-07-28" (get-in body [:result :protocolVersion]))))))))
