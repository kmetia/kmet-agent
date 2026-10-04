#!/usr/bin/env bb
;; Wire-shape validation for the modern (2026-07-28) fake MCP servers
;; (mcp.md §3.1). Raw JSON-RPC over stdio and raw HTTP POSTs — the lib
;; client speaks only the legacy handshake until §3.3, so this script pins
;; what the fakes put on the wire and proves the legacy modes are untouched.
;; Client-side assertions accumulate here as later landings land.
;;
;; Usage: bb validate-protocol.bb <fake-stdio.bb> <fake-http.bb>

(require '[babashka.process :as proc]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[kmet.libs.http :as http]
         '[kmet.libs.json :as json])

(def failures (atom 0))

(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(def modern-version "2026-07-28")

(defn- client-meta []
  {"io.modelcontextprotocol/protocolVersion" modern-version
   "io.modelcontextprotocol/clientInfo" {"name" "kmet-validate-protocol"
                                         "version" "0.1.0"}
   "io.modelcontextprotocol/clientCapabilities" {}})

;; ─── raw stdio JSON-RPC ──────────────────────────────────────────────────

(defn- start-line-reader
  "A daemon thread feeding lines from R into a queue; next-line! polls it
   with a deadline so a silent server fails the script instead of hanging it."
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
      (instance? Exception v) (throw v)
      :else v)))

(defn- start-stdio [script & args]
  (let [p (proc/process (into ["bb" script] args)
                        {:in :stream :out :stream :err :inherit})]
    {:proc p
     :writer (io/writer (:in p))
     :queue (start-line-reader (io/reader (:out p)))
     :notifications (atom [])}))

(defn- send! [session msg]
  (.write (:writer session) (str (json/generate-string msg) "\n"))
  (.flush (:writer session)))

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

;; ─── raw HTTP ────────────────────────────────────────────────────────────

(defn- spawn-server!
  "Start the HTTP fake; it prints 'PORT <n>' on stdout (captured to a file
   so the caller can poll it while the process runs)."
  [script]
  (let [out-file (str (System/getProperty "user.dir") "/.mcp-protocol-"
                      (System/nanoTime) ".out")
        p (proc/process ["bb" script]
                        {:in :discard :out out-file :err :discard})]
    (loop [waits 0]
      (let [out (try (slurp out-file) (catch Exception _ ""))]
        (if-let [m (re-find #"PORT (\d+)" out)]
          {:proc p :port (Long/parseLong (second m)) :out-file out-file}
          (do (Thread/sleep 100)
              (if (< waits 50)
                (recur (inc waits))
                (throw (ex-info (str "server did not start: " out)
                                {:type :server-start-failed})))))))))

(defn- stop-server! [{:keys [proc out-file]}]
  (try (proc/destroy-tree proc) (catch Exception _ nil))
  (when out-file (io/delete-file out-file true)))

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
    (check label
           (and (= 400 (:status resp))
                (= code (get-in body [:error :code]))))))

(defn- read-sse-data!
  "The next data: payload from an SSE line queue (event:/comment/blank
   lines are skipped)."
  [q]
  (loop []
    (let [line (next-line! q 10000)]
      (if (str/starts-with? line "data:")
        (str/trim (subs line 5))
        (recur)))))

;; ─── stdio: modern ───────────────────────────────────────────────────────

(defn test-stdio-modern [fake-stdio]
  (println "\n── stdio: modern (2026-07-28) ──")
  (let [s (start-stdio fake-stdio "--era" "modern")]
    (try
      (send! s {:jsonrpc "2.0" :id 1 :method "server/discover"
                :params {:_meta (client-meta)}})
      (let [r (:result (await-response! s 1))]
        (check "discover resultType" (= "complete" (:resultType r)))
        (check "discover supportedVersions" (= [modern-version] (:supportedVersions r)))
        (check "discover tool capabilities"
               (true? (get-in r [:capabilities :tools :listChanged])))
        (check "discover serverInfo in _meta"
               (= "fake-mcp-server"
                  (get-in r [:_meta :io.modelcontextprotocol/serverInfo :name])))
        (check "discover cache hints"
               (and (number? (:ttlMs r)) (string? (:cacheScope r)))))

      (let [{:keys [tools last-r]} (list-all-tools! s (client-meta) 2)]
        (check "tools/list resultType" (= "complete" (:resultType last-r)))
        (check "tools/list cache hints" (number? (:ttlMs last-r)))
        (check "tools/list tools" (= 9 (count tools))))

      (send! s {:jsonrpc "2.0" :id 3 :method "tools/list" :params {}})
      (let [e (:error (await-response! s 3))]
        (check "missing _meta → -32602" (= -32602 (:code e))))

      (send! s {:jsonrpc "2.0" :id 4 :method "tools/list"
                :params {:_meta (assoc (client-meta)
                                       "io.modelcontextprotocol/protocolVersion"
                                       "1900-01-01")}})
      (let [e (:error (await-response! s 4))]
        (check "unsupported version → -32022" (= -32022 (:code e)))
        (check "-32022 data.supported" (= [modern-version] (get-in e [:data :supported])))
        (check "-32022 data.requested" (= "1900-01-01" (get-in e [:data :requested]))))

      (send! s {:jsonrpc "2.0" :id 5 :method "initialize"
                :params {:protocolVersion "2025-11-25" :capabilities {}
                         :clientInfo {}}})
      (let [e (:error (await-response! s 5))]
        (check "modern server rejects initialize" (= -32601 (:code e)))
        (check "initialize error names the version"
               (str/includes? (:message e) modern-version)))

      ;; subscriptions/listen: the ack is the first frame, its subscriptionId
      ;; is the request id, and it reflects the subset the server honors
      (send! s {:jsonrpc "2.0" :id 6 :method "subscriptions/listen"
                :params {:_meta (client-meta)
                         :notifications {:toolsListChanged true
                                         :promptsListChanged true
                                         :resourcesListChanged true}}})
      (let [ack (next-message! s)]
        (check "listen ack first"
               (= "notifications/subscriptions/acknowledged" (:method ack)))
        (check "ack subscriptionId"
               (= 6 (get-in ack [:params :_meta
                                 :io.modelcontextprotocol/subscriptionId])))
        (check "ack keeps only supported filters"
               (= {:toolsListChanged true} (get-in ack [:params :notifications]))))

      ;; the add-tool call triggers a subscription-scoped list_changed
      (send! s {:jsonrpc "2.0" :id 7 :method "tools/call"
                :params {:_meta (client-meta) :name "add-tool" :arguments {}}})
      (let [n (next-message! s)]
        (check "listen tools/list_changed"
               (= "notifications/tools/list_changed" (:method n)))
        (check "notification subscriptionId"
               (= 6 (get-in n [:params :_meta
                               :io.modelcontextprotocol/subscriptionId]))))
      (check "add-tool resultType"
             (= "complete" (:resultType (:result (await-response! s 7)))))

      (check "re-list sees the added tool"
             (some #(= "echo2" (:name %))
                   (:tools (list-all-tools! s (client-meta) 8))))

      ;; cancelled subscriptions stop receiving notifications
      (send! s {:jsonrpc "2.0" :method "notifications/cancelled"
                :params {:requestId 6 :reason "test"}})
      (send! s {:jsonrpc "2.0" :id 9 :method "tools/call"
                :params {:_meta (client-meta) :name "add-tool" :arguments {}}})
      (await-response! s 9)
      (check "cancelled subscription gets no notification"
             (empty? @(:notifications s)))
      (finally
        (stop-stdio! s)))))

;; ─── stdio: legacy untouched ─────────────────────────────────────────────

(defn test-stdio-legacy [fake-stdio]
  (println "\n── stdio: legacy mode untouched ──")
  (let [s (start-stdio fake-stdio)]
    (try
      (send! s {:jsonrpc "2.0" :id 1 :method "initialize"
                :params {:protocolVersion "2025-11-25" :capabilities {}
                         :clientInfo {}}})
      (let [r (:result (await-response! s 1))]
        (check "legacy initialize answers" (= "2025-11-25" (:protocolVersion r)))
        (check "legacy result has no resultType" (not (contains? r :resultType))))
      (send! s {:jsonrpc "2.0" :id 2 :method "server/discover" :params {}})
      (let [e (:error (await-response! s 2))]
        (check "legacy server/discover unknown" (= -32601 (:code e))))
      (send! s {:jsonrpc "2.0" :id 3 :method "tools/list" :params {}})
      (let [r (:result (await-response! s 3))]
        (check "legacy tools/list has no resultType" (not (contains? r :resultType))))
      (finally
        (stop-stdio! s)))))

;; ─── streamable HTTP: modern ─────────────────────────────────────────────

(defn test-http-modern [fake-http]
  (println "\n── streamable HTTP: modern (2026-07-28) ──")
  (let [{:keys [port] :as server} (spawn-server! fake-http)
        url (str "http://127.0.0.1:" port "/mcp?era=modern")]
    (try
      (let [msg {:jsonrpc "2.0" :id 1 :method "server/discover"
                 :params {:_meta (client-meta)}}
            resp (post-mcp url msg (request-headers msg))
            r (:result (decode-body resp))]
        (check "http discover 200" (= 200 (:status resp)))
        (check "http discover content-type"
               (str/includes? (str (header resp "content-type")) "application/json"))
        (check "http discover resultType" (= "complete" (:resultType r)))
        (check "http discover supportedVersions"
               (= [modern-version] (:supportedVersions r)))
        (check "http discover serverInfo"
               (= "fake-http-mcp-server"
                  (get-in r [:_meta :io.modelcontextprotocol/serverInfo :name])))
        (check "http discover cache hints"
               (and (number? (:ttlMs r)) (string? (:cacheScope r))))
        (check "http response mints no session"
               (nil? (header resp "mcp-session-id"))))

      ;; header/body validation: every failure is 400 with the spec's codes
      (let [msg {:jsonrpc "2.0" :id 2 :method "tools/list"
                 :params {:_meta (client-meta)}}]
        (check-http-error "missing Mcp-Method → 400 -32020"
                          (post-mcp url msg (dissoc (request-headers msg) "Mcp-Method"))
                          -32020)
        (check-http-error "wrong Mcp-Method → 400 -32020"
                          (post-mcp url msg (assoc (request-headers msg)
                                                   "Mcp-Method" "tools/call"))
                          -32020)
        (check-http-error "missing MCP-Protocol-Version → 400 -32020"
                          (post-mcp url msg (dissoc (request-headers msg)
                                                    "MCP-Protocol-Version"))
                          -32020)
        (check-http-error "version header/body mismatch → 400 -32020"
                          (post-mcp url msg (assoc (request-headers msg)
                                                   "MCP-Protocol-Version" "2020-01-01"))
                          -32020)
        (check-http-error "missing _meta → 400 -32020"
                          (post-mcp url {:jsonrpc "2.0" :id 2 :method "tools/list"
                                         :params {}}
                                    (request-headers msg))
                          -32020))
      (let [msg {:jsonrpc "2.0" :id 3 :method "tools/call"
                 :params {:_meta (client-meta) :name "http-echo" :arguments {}}}]
        (check-http-error "missing Mcp-Name → 400 -32020"
                          (post-mcp url msg (dissoc (request-headers msg) "Mcp-Name"))
                          -32020)
        (check-http-error "wrong Mcp-Name → 400 -32020"
                          (post-mcp url msg (assoc (request-headers msg)
                                                   "Mcp-Name" "http-add"))
                          -32020))
      (let [msg {:jsonrpc "2.0" :id 4 :method "tools/list"
                 :params {:_meta (client-meta)}}
            resp (post-mcp url msg (dissoc (request-headers msg) "Mcp-Method"))]
        (check "header error echoes the id" (= 4 (get-in (decode-body resp) [:id]))))
      (let [msg {:jsonrpc "2.0" :id 5 :method "tools/list"
                 :params {:_meta (assoc (client-meta)
                                        "io.modelcontextprotocol/protocolVersion"
                                        "1900-01-01")}}
            resp (post-mcp url msg (assoc (request-headers msg)
                                          "MCP-Protocol-Version" "1900-01-01"))
            e (:error (decode-body resp))]
        (check "unsupported version → 400 -32022"
               (and (= 400 (:status resp)) (= -32022 (:code e))))
        (check "http -32022 data.supported"
               (= [modern-version] (get-in e [:data :supported])))
        (check "http -32022 data.requested"
               (= "1900-01-01" (get-in e [:data :requested]))))
      (let [msg {:jsonrpc "2.0" :id 6 :method "tools/list"
                 :params {:_meta (dissoc (client-meta)
                                         "io.modelcontextprotocol/clientInfo")}}
            resp (post-mcp url msg (request-headers msg))]
        (check-http-error "missing clientInfo → 400 -32602" resp -32602))

      ;; an unimplemented method is 404 with a JSON-RPC -32601 body
      (let [msg {:jsonrpc "2.0" :id 7 :method "ping"
                 :params {:_meta (client-meta)}}
            resp (post-mcp url msg (request-headers msg))]
        (check "unknown modern method → 404"
               (and (= 404 (:status resp))
                    (= -32601 (get-in (decode-body resp) [:error :code])))))

      ;; notifications are accepted with 202 and no body
      (let [msg {:jsonrpc "2.0" :method "notifications/initialized" :params {}}
            resp (post-mcp url msg {})]
        (check "modern notification → 202"
               (and (= 202 (:status resp)) (empty? (str (:body resp))))))

      ;; the Base64 sentinel in Mcp-Name is decoded before comparison
      (let [msg {:jsonrpc "2.0" :id 8 :method "tools/call"
                 :params {:_meta (client-meta) :name "http-echo"
                          :arguments {:message "b64"}}}
            resp (post-mcp url msg (assoc (request-headers msg)
                                          "Mcp-Name" "=?base64?aHR0cC1lY2hv?="))]
        (check "base64 Mcp-Name accepted" (= 200 (:status resp)))
        (check "base64 call result"
               (str/includes? (get-in (decode-body resp) [:result :content 0 :text])
                              "b64")))

      ;; tools/list carries the modern trigger tool and resultType
      (let [msg {:jsonrpc "2.0" :id 9 :method "tools/list"
                 :params {:_meta (client-meta)}}
            r (:result (decode-body (post-mcp url msg (request-headers msg))))]
        (check "http tools/list resultType" (= "complete" (:resultType r)))
        (check "http tools/list modern tool"
               (some #(= "http-add-tool" (:name %)) (:tools r))))

      ;; a stale session header is ignored, never echoed
      (let [msg {:jsonrpc "2.0" :id 10 :method "tools/list"
                 :params {:_meta (client-meta)}}
            resp (post-mcp url msg (assoc (request-headers msg)
                                          "Mcp-Session-Id" "stale-sess"))]
        (check "stale session header ignored"
               (and (= 200 (:status resp)) (some? (:result (decode-body resp)))))
        (check "no session in response" (nil? (header resp "mcp-session-id"))))

      ;; the session-era GET stream and DELETE are gone
      (check "modern GET /mcp → 405"
             (= 405 (:status (http/request {:url url :method :get :throw? false}))))
      (check "modern DELETE /mcp → 405"
             (= 405 (:status (http/request {:url url :method :delete :throw? false}))))

      ;; subscriptions/listen: a long-lived SSE stream, ack first
      (let [msg {:jsonrpc "2.0" :id 77 :method "subscriptions/listen"
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
        (check "listen 200" (= 200 (:status resp)))
        (check "listen content-type"
               (str/includes? (str (header resp "content-type")) "text/event-stream"))
        (let [q (start-line-reader (io/reader (:body resp)))
              ack (json/parse-string (read-sse-data! q) true)]
          (check "http listen ack first"
                 (= "notifications/subscriptions/acknowledged" (:method ack)))
          (check "http ack subscriptionId"
                 (= 77 (get-in ack [:params :_meta
                                    :io.modelcontextprotocol/subscriptionId])))
          (check "http ack keeps only supported filters"
                 (= {:toolsListChanged true} (get-in ack [:params :notifications])))
          (let [trigger {:jsonrpc "2.0" :id 78 :method "tools/call"
                         :params {:_meta (client-meta) :name "http-add-tool"
                                  :arguments {}}}]
            (post-mcp url trigger (request-headers trigger)))
          (let [n (json/parse-string (read-sse-data! q) true)]
            (check "http listen tools/list_changed"
                   (= "notifications/tools/list_changed" (:method n)))
            (check "http notification subscriptionId"
                   (= 77 (get-in n [:params :_meta
                                    :io.modelcontextprotocol/subscriptionId]))))
          (let [msg {:jsonrpc "2.0" :id 79 :method "tools/list"
                     :params {:_meta (client-meta)}}]
            (check "http re-list sees the added tool"
                   (some #(= "http-echo2" (:name %))
                         (get-in (decode-body (post-mcp url msg (request-headers msg)))
                                 [:result :tools])))))
        (http/close! resp))
      (finally
        (stop-server! server)))))

;; ─── streamable HTTP: legacy untouched ───────────────────────────────────

(defn test-http-legacy [fake-http]
  (println "\n── streamable HTTP: legacy mode untouched ──")
  (let [{:keys [port] :as server} (spawn-server! fake-http)
        base (str "http://127.0.0.1:" port "/mcp")]
    (try
      (let [msg {:jsonrpc "2.0" :id 1 :method "initialize"
                 :params {:protocolVersion "2025-11-25" :capabilities {}
                          :clientInfo {}}}
            resp (post-mcp base msg {})
            body (decode-body resp)]
        (check "legacy initialize" (= "2025-11-25" (get-in body [:result :protocolVersion])))
        (check "legacy initialize has no resultType"
               (not (contains? (:result body) :resultType)))
        (check "legacy session minted" (some? (header resp "mcp-session-id"))))
      (let [msg {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}}
            body (decode-body (post-mcp base msg {"Mcp-Session-Id" "sess-1"}))]
        (check "legacy tools/list 4 tools" (= 4 (count (get-in body [:result :tools]))))
        (check "legacy tools/list has no resultType"
               (not (contains? (:result body) :resultType))))
      ;; the version override still answers a modern revision verbatim — the
      ;; legacy client parser keeps refusing it (validate-client asserts that)
      (let [msg {:jsonrpc "2.0" :id 3 :method "initialize"
                 :params {:protocolVersion "2026-07-28" :capabilities {}
                          :clientInfo {}}}
            body (decode-body (post-mcp (str base "?version=2026-07-28") msg {}))]
        (check "legacy version override answers 2026-07-28"
               (= "2026-07-28" (get-in body [:result :protocolVersion]))))
      (finally
        (stop-server! server)))))

;; ─── main ────────────────────────────────────────────────────────────────

(let [[fake-stdio fake-http] *command-line-args*]
  (when-not (and fake-stdio fake-http)
    (println "Usage: bb validate-protocol.bb <fake-stdio.bb> <fake-http.bb>")
    (System/exit 1))
  (test-stdio-modern fake-stdio)
  (test-stdio-legacy fake-stdio)
  (test-http-modern fake-http)
  (test-http-legacy fake-http)
  (println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
  (System/exit (if (zero? @failures) 0 1)))
