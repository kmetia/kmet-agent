#!/usr/bin/env bb
;; Fake streamable-HTTP / legacy-SSE MCP server for validating the
;; mcp-adapter client (extensions/mcp-adapter/scripts/fake-http-mcp-server.bb
;; — §12.2). Babashka has no HTTP server, so this is a plain
;; java.net.ServerSocket loop speaking HTTP/1.1.
;;
;; Endpoints (single port, path routing):
;;   POST /mcp          — streamable HTTP: JSON or SSE responses; captures
;;                         Mcp-Session-Id on initialize and echoes it back
;;   POST /mcp?slow=1   — sleeps before answering (exercises the timeout
;;                         path when the client timeout is short)
;;   POST /mcp?version=X — initialize answers with protocol revision X
;;                         (version negotiation / rejection)
;;   GET  /sse          — legacy SSE stream (endpoint + message events)
;;   POST /sse          — request endpoint for the legacy SSE transport
;;                         (answers 202; the result arrives on the stream)
;;
;; Additive modern mode (`?era=modern`, revision 2026-07-28): every request
;; is validated against its MCP-Protocol-Version / Mcp-Method / Mcp-Name
;; headers (400 + -32020 on mismatch), no Mcp-Session-Id is minted or
;; echoed, GET/DELETE answer 405, server/discover and subscriptions/listen
;; (an SSE stream with an ack-first frame and subscriptionId-stamped
;; notifications) exist — and every result carries resultType. Legacy mode
;; is unchanged.
;;
;; Usage: bb fake-http-mcp-server.bb [port]
;; Prints "PORT <n>" on stdout so the caller can read the assigned port.
;; Locate the kmet source tree (the fakes run as bare bb children with no
;; -cp) so JSON can go through kmet.libs.json like everywhere else.
(require '[babashka.fs :as fs]
         '[babashka.classpath :as bcp])
(let [src (str (fs/normalize (fs/path (fs/parent *file*) ".." ".." ".." "src")))]
  (when (fs/directory? src)
    (bcp/add-classpath src)))
(require '[kmet.libs.json :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.core.async :as async])

(def port (Long/parseLong (or (first *command-line-args*) "0")))

(def modern-version "2026-07-28")
(def modern-supported-versions [modern-version])

(def tools
  [{:name "http-echo" :description "Echo the message back over HTTP"
    :inputSchema {:type "object"
                  :properties {"message" {:type "string"}}
                  :required ["message"]}}
   {:name "http-add" :description "Add two numbers over HTTP"
    :inputSchema {:type "object"
                  :properties {"a" {:type "number"} "b" {:type "number"}}
                  :required ["a" "b"]}}
   {:name "http-slow" :description "Sleeeeps (2s)"
    :inputSchema {:type "object" :properties {} :required []}}
   {:name "http-headers"
    :description "Echo the MCP-Protocol-Version header of the last request"
    :inputSchema {:type "object" :properties {} :required []}}])

(def state (atom {:session-id nil
                  :sse-chan nil
                  :listens {}
                  :last-headers nil}))

;; modern mode: the catalog change trigger and the tool it adds
(def modern-extra-tool? (atom false))
(def modern-trigger-tool
  {:name "http-add-tool"
   :description "Adds a tool and notifies open listen subscriptions"
   :inputSchema {:type "object" :properties {} :required []}})
(def modern-extra-tool
  {:name "http-echo2" :description "Second echo over HTTP"
   :inputSchema {:type "object" :properties {} :required []}})

;; SEP-2243: a validly annotated tool (the client must mirror its
;; parameters into Mcp-Param-* headers) and one whose annotation violates
;; the spec (a client using streamable HTTP must drop it from tools/list)
(def modern-region-tool
  {:name "http-region"
   :description "Echo the Mcp-Param-* headers mirrored from its annotated parameters"
   :inputSchema {:type "object"
                 :properties {"region" {:type "string" :x-mcp-header "Region"}
                              "priority" {:type "integer" :x-mcp-header "Priority"}
                              "dryRun" {:type "boolean" :x-mcp-header "DryRun"}}
                 :required ["region"]}})
(def modern-bad-header-tool
  {:name "http-bad-header"
   :description "A tool whose x-mcp-header annotation violates the spec"
   :inputSchema {:type "object"
                 :properties {"q" {:type "string" :x-mcp-header "Bad Header"}}
                 :required ["q"]}})

(defn- modern-tools
  "The tool catalog of a modern server: the legacy tools plus the
   modern-only ones (the trigger, the annotated tool, the invalid one and,
   after the trigger call, the added tool)."
  []
  (cond-> (conj tools modern-trigger-tool modern-region-tool modern-bad-header-tool)
    @modern-extra-tool? (conj modern-extra-tool)))

(defn- http-response
  ([status body] (http-response status body {"Content-Type" "application/json"}))
  ([status body headers]
   (let [body (str body)
         head (str "HTTP/1.1 " status " "
                   ({200 "OK" 202 "Accepted" 400 "Bad Request" 401 "Unauthorized"
                     404 "Not Found" 405 "Method Not Allowed"} status "OK")
                   "\r\n"
                   (str/join "" (map (fn [[k v]] (str k ": " v "\r\n")) headers))
                   "Content-Length: " (count (.getBytes body "UTF-8")) "\r\n"
                   "Connection: close\r\n\r\n")]
     (str head body))))

(defn- json-error-response [status id error]
  (http-response status
                 (json/generate-string {:jsonrpc "2.0" :id id :error error})
                 {"Content-Type" "application/json"}))

(defn- decode-header-value
  "Decode the spec's Base64 sentinel (=?base64?…?=); plain values (and
   malformed sentinels, which then fail the comparison) pass through."
  [v]
  (if (and (str/starts-with? v "=?base64?") (str/ends-with? v "?="))
    (try
      (String. (.decode (java.util.Base64/getDecoder)
                        (subs v 9 (- (count v) 2)))
               "UTF-8")
      (catch Exception _ v))
    v))

(defn- received-param
  "The decoded value of the last request's Mcp-Param-* header NAME (the
   request headers are kept in :last-headers)."
  [name]
  (some-> (get-in @state [:last-headers (str "mcp-param-" (str/lower-case name))])
          decode-header-value))

(defn- schema-x-mcp-headers
  "The statically reachable x-mcp-header annotations of a tool schema (the
   fake's schemas use only `properties` chains):
   [{:path [property ..] :header name :type declared-type}]"
  [schema]
  (letfn [(walk [node path]
            (when (map? node)
              (concat
               (when-let [h (:x-mcp-header node)]
                 [{:path path :header h :type (:type node)}])
               (mapcat (fn [[k sub]] (walk sub (conj path k)))
                       (:properties node)))))]
    (walk schema [])))

(defn- expected-param-value
  "The header value the body value maps to (spec, Value Encoding)."
  [type v]
  (case type
    "integer" (str (long v))
    "boolean" (if v "true" "false")
    (str v)))

(defn- param-header-problems
  "The Mcp-Param-* validation failures for a tools/call MSG of TOOL against
   HEADERS: every annotated parameter's header must be present and decode
   to the body value; a null/absent value must have no header (spec, Server
   Validation)."
  [headers msg tool]
  (for [{:keys [path header type]} (schema-x-mcp-headers (:inputSchema tool))
        :let [v (get-in msg (into [:params :arguments] (map keyword path)))
              received (get headers (str "mcp-param-" (str/lower-case header)))
              decoded (when received (decode-header-value received))
              expected (when (some? v) (expected-param-value type v))]
        :when (not= expected decoded)]
    (str "Mcp-Param-" header
         (if (some? v)
           (str " does not match body value " (pr-str v))
           " must be omitted for a null/absent value"))))

(defn- validate-modern-request
  "Body/header validation for a modern POST (spec, Server Validation):
   required mirrored headers, Base64-aware Mcp-Name comparison, the
   supported version, and the mandatory _meta fields. Returns nil or
   {:status 400 :error {...}}."
  [req msg]
  (let [headers (:headers req)
        meta (get-in msg [:params :_meta])
        body-version (get meta :io.modelcontextprotocol/protocolVersion)
        method (:method msg)
        named (case method
                ("tools/call" "prompts/get") (get-in msg [:params :name])
                "resources/read" (get-in msg [:params :uri])
                nil)
        version-header (get headers "mcp-protocol-version")
        method-header (get headers "mcp-method")
        name-header (get headers "mcp-name")
        param-problems (when (= "tools/call" method)
                         (when-let [tool (some #(when (= named (:name %)) %)
                                               (modern-tools))]
                           (seq (param-header-problems headers msg tool))))
        mismatch (fn [m] {:status 400
                          :error {:code -32020
                                  :message (str "Header mismatch: " m)}})]
    (cond
      (nil? version-header) (mismatch "missing required header MCP-Protocol-Version")
      (not= version-header body-version)
      (mismatch (str "MCP-Protocol-Version header '" version-header
                     "' does not match body value '" body-version "'"))
      (nil? method-header) (mismatch "missing required header Mcp-Method")
      (not= method-header method)
      (mismatch (str "Mcp-Method header '" method-header
                     "' does not match body value '" method "'"))
      (and named (nil? name-header))
      (mismatch (str "missing required header Mcp-Name for " method))
      (and named (not= (decode-header-value name-header) named))
      (mismatch (str "Mcp-Name header value '" name-header
                     "' does not match body value '" named "'"))
      param-problems
      {:status 400 :error {:code -32020
                           :message (str "Header mismatch: "
                                         (str/join "; " param-problems))}}
      (not (some #{version-header} modern-supported-versions))
      {:status 400 :error {:code -32022 :message "Unsupported protocol version"
                           :data {:supported modern-supported-versions
                                  :requested version-header}}}
      (not (contains? meta :io.modelcontextprotocol/clientInfo))
      {:status 400 :error {:code -32602
                           :message "Missing io.modelcontextprotocol/clientInfo in _meta"}}
      (not (contains? meta :io.modelcontextprotocol/clientCapabilities))
      {:status 400 :error {:code -32602
                           :message "Missing io.modelcontextprotocol/clientCapabilities in _meta"}}
      :else nil)))

(defn- with-cache-fields [modern? result]
  (cond-> result modern? (assoc :ttlMs 60000 :cacheScope "private")))

(defn- notify-listeners!
  "Queue a tools/list_changed notification on every open listen stream that
   asked for one (a modern server never broadcasts a list_changed)."
  [method]
  (doseq [[sid {:keys [chan agreed]}] (:listens @state)
          :when (:toolsListChanged agreed)]
    (async/put! chan
                {:jsonrpc "2.0" :method method
                 :params {:_meta {:io.modelcontextprotocol/subscriptionId sid}}})))

(defn- read-request-from
  "Read the headers + body after the request line."
  [reader line]
  (let [headers (loop [headers {}]
                  (let [h (.readLine reader)]
                    (if (and h (seq h))
                      (let [[k v] (str/split h #":" 2)]
                        (recur (assoc headers (str/lower-case (or k ""))
                                      (str/trim (or v "")))))
                      headers)))
        [method target] (str/split line #"\s+" 3)
        [path query] (str/split (or target "") #"\?" 2)
        content-length (Long/parseLong (or (get headers "content-length") "0"))
        body (when (pos? content-length)
               (let [buf (char-array content-length)]
                 (.read reader buf 0 content-length)
                 (String. buf)))]
    {:method method :path path :query query :headers headers :body body}))

(defn- read-request
  "Read one HTTP request from IN (request line + headers + body)."
  [in]
  (let [reader (io/reader in)
        line (.readLine reader)]
    (when line
      (read-request-from reader line))))

(defn- handle-json-rpc
  "Handle one JSON-RPC message; returns the response map or nil for
   notifications. VERSION-OVERRIDE, when set, is answered to initialize
   instead of the revision the client requested. MODERN? selects the
   2026-07-28 behavior: no initialize, server/discover available, and
   resultType \"complete\" on every result."
  ([body session-id] (handle-json-rpc body session-id nil false))
  ([body session-id version-override] (handle-json-rpc body session-id version-override false))
  ([body session-id version-override modern?]
   (let [msg (json/parse-string body true)
         id (:id msg)
         method (:method msg)
         respond (fn [response]
                   (cond-> response
                     (and modern? (:result response))
                     (assoc :result (assoc (:result response) :resultType "complete"))))]
     (cond
       ;; modern-only: the legacy handshake is not a method here; naming the
       ;; supported versions is the diagnostic a legacy client can surface
       (and modern? (= method "initialize"))
       {:jsonrpc "2.0" :id id
        :error {:code -32601
                :message (str "Method not found: initialize (this server speaks only "
                              (str/join ", " modern-supported-versions) ")")}}

       :else
       (respond
        (case method
          "initialize"
          (do (reset! state (assoc @state :session-id (or session-id "sess-1")))
              {:jsonrpc "2.0" :id id
               :result {:protocolVersion (or version-override
                                             (get-in msg [:params :protocolVersion]))
                        :capabilities {:tools {}
                                       :prompts {:listChanged false}
                                       :resources {:listChanged false}}
                        :serverInfo {:name "fake-http-mcp-server" :version "1.0.0"}}})
          "notifications/initialized" nil
       ;; modern discovery (spec, server/discover)
          "server/discover"
          (if modern?
            {:jsonrpc "2.0" :id id
             :result {:supportedVersions modern-supported-versions
                      :capabilities {:tools {:listChanged true}
                                     :prompts {:listChanged true}
                                     :resources {}}
                      :instructions "Fake modern HTTP MCP server (kmet validation)"
                      :ttlMs 3600000
                      :cacheScope "public"
                      :_meta {:io.modelcontextprotocol/serverInfo
                              {:name "fake-http-mcp-server" :version "1.0.0"}}}}
            {:jsonrpc "2.0" :id id
             :error {:code -32601 :message (str "Method not found: " method)}})
          "tools/list"
          {:jsonrpc "2.0" :id id
           :result (if modern?
                     (with-cache-fields true {:tools (modern-tools)})
                     {:tools tools})}
          "tools/call"
          (let [name (get-in msg [:params :name])
                args (get-in msg [:params :arguments])]
            (case name
              "http-echo" {:jsonrpc "2.0" :id id
                           :result {:content [{:type "text"
                                               :text (str "http-echo: " (:message args))}]}}
              "http-add" {:jsonrpc "2.0" :id id
                          :result {:content [{:type "text"
                                              :text (str (+ (:a args) (:b args)))}]}}
              "http-slow" (do (Thread/sleep 2000)
                              {:jsonrpc "2.0" :id id
                               :result {:content [{:type "text" :text "finally"}]}})
              "http-headers"
              {:jsonrpc "2.0" :id id
               :result {:content [{:type "text"
                                   :text (str (get-in @state
                                                      [:last-headers
                                                       "mcp-protocol-version"]))}]}}
              "http-add-tool"
              (do (reset! modern-extra-tool? true)
                  (notify-listeners! "notifications/tools/list_changed")
                  {:jsonrpc "2.0" :id id
                   :result {:content [{:type "text" :text "added http-echo2"}]}})
              "http-region"
              {:jsonrpc "2.0" :id id
               :result {:content [{:type "text"
                                   :text (str "region=" (received-param "Region")
                                              " priority=" (received-param "Priority")
                                              " dryRun=" (received-param "DryRun"))}]}}
              {:jsonrpc "2.0" :id id
               :result {:content [{:type "text" :text "unknown"}]
                        :isError true}}))
          "prompts/list"
          {:jsonrpc "2.0" :id id
           :result {:prompts [{:name "http-brief"
                               :description "Summarize a topic briefly"
                               :arguments [{:name "topic" :required true}]}]}}
          "prompts/get"
          {:jsonrpc "2.0" :id id
           :result {:messages [{:role "user"
                                :content {:type "text"
                                          :text (str "http brief: "
                                                     (get-in msg [:params :arguments :topic]))}}]}}
          "resources/list"
          {:jsonrpc "2.0" :id id
           :result {:resources [{:name "HTTP doc" :uri "http://fake/doc"
                                 :description "A fake http resource"}]}}
          "resources/templates/list"
          {:jsonrpc "2.0" :id id
           :result {:resourceTemplates [{:name "http pages"
                                         :uriTemplate "http://fake/page/{id}"
                                         :description "A fake http resource template"}]}}
          "resources/read"
          {:jsonrpc "2.0" :id id
           :result {:contents [{:type "text" :uri (get-in msg [:params :uri])
                                :text "http resource content"}]}}
          {:jsonrpc "2.0" :id id
           :error {:code -32601 :message (str "Method not found: " method)}}))))))

(defn- write-sse! [out msg]
  (.write out (.getBytes (str "event: message\ndata: "
                              (json/generate-string msg) "\n\n")
                         "UTF-8"))
  (.flush out))

(defn- handle-listen
  "The response to a modern subscriptions/listen request: a long-lived SSE
   stream. The ack is the first frame (subscriptionId = the request id) and
   reflects the subset this server honors; notifications queued by another
   request (see notify-listeners!) follow. A keepalive comment every 2s
   detects a client that dropped the stream."
  [out msg]
  (let [id (:id msg)
        filter (or (:notifications (:params msg)) {})
        agreed (select-keys filter [:toolsListChanged])
        ch (async/chan 64)]
    (swap! state assoc-in [:listens id] {:chan ch :agreed agreed})
    (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                "Content-Type: text/event-stream\r\n"
                                "Cache-Control: no-cache\r\n"
                                "X-Accel-Buffering: no\r\n\r\n")
                           "UTF-8"))
    (.flush out)
    (write-sse! out {:jsonrpc "2.0"
                     :method "notifications/subscriptions/acknowledged"
                     :params {:_meta {:io.modelcontextprotocol/subscriptionId id}
                              :notifications agreed}})
    (try
      (loop []
        (let [t (async/timeout 2000)
              [msg* port] (async/alts!! [ch t])]
          (cond
            (some? msg*) (do (write-sse! out msg*) (recur))
            (identical? port t) (do (.write out (.getBytes ":\r\n" "UTF-8"))
                                    (.flush out)
                                    (recur))
            :else nil)))
      (catch Exception _ nil)
      (finally
        (swap! state update :listens dissoc id)))))

(defn- handle-streamable
  "POST /mcp — JSON or SSE response per the Accept header. The last
   request's headers are kept so the http-headers tool can echo them.
   `?era=modern` selects the 2026-07-28 behavior (header validation, no
   sessions, server/discover, subscriptions/listen). OUT is the socket
   stream: a listen request owns it for the life of its SSE stream and
   returns nil (nothing is left to write)."
  [out req]
  (reset! state (assoc @state :last-headers (:headers req)))
  (let [query (or (:query req) "")
        modern? (str/includes? query "era=modern")
        session-id (get-in req [:headers "mcp-session-id"])
        slow? (str/includes? query "slow")
        ;; ?version=<rev> makes initialize answer with that revision
        ;; instead of the one the client requested
        version-override (when-let [[_ v] (re-find #"version=([^&]+)" query)]
                           v)
        body-msg (json/parse-string (:body req) true)]
    (if modern?
      (cond
        ;; header requirements for notification POSTs are not defined by
        ;; this revision; an accepted notification answers 202 with no body
        (nil? (:id body-msg))
        (http-response 202 "" {})

        :else
        (if-let [invalid (validate-modern-request req body-msg)]
          (json-error-response (:status invalid) (:id body-msg) (:error invalid))
          (if (= "subscriptions/listen" (:method body-msg))
            (do (handle-listen out body-msg) nil)
            (let [response (handle-json-rpc (:body req) nil nil true)]
              (if (= -32601 (get-in response [:error :code]))
                ;; an unimplemented method is a 404 with a JSON-RPC body
                ;; (spec, Protocol Version Header)
                (json-error-response 404 (:id body-msg) (:error response))
                (if (str/includes? (str (get-in req [:headers "accept"])) "text/event-stream")
                  (http-response 200
                                 (str "event: message\ndata: "
                                      (json/generate-string response) "\n\n")
                                 {"Content-Type" "text/event-stream"})
                  (http-response 200 (json/generate-string response)
                                 {"Content-Type" "application/json"})))))))

      ;; ─── legacy path, unchanged ───
      (let [is-initialize? (= "initialize" (:method body-msg))
            is-slow-call? (and (= "tools/call" (:method body-msg))
                               (= "http-slow" (get-in body-msg [:params :name])))
            ;; like a real SDK server: progress only for requests carrying
            ;; _meta.progressToken
            progress-token (get-in body-msg [:params :_meta :progressToken])
            response (handle-json-rpc (:body req) session-id version-override)]
        ;; notifications get an empty 200 (never close without a response —
        ;; java.net.http reports that as an error)
        (if (nil? response)
          (http-response 200 "" {"Content-Type" "application/json"})
          (do
            (when slow? (Thread/sleep 2000))
            (let [session-header (when is-initialize?
                                   {"Mcp-Session-Id" (or session-id "sess-1")})]
              (if (str/includes? (str (get-in req [:headers "accept"])) "text/event-stream")
                (let [sse-parts (if (and is-slow-call? progress-token)
                                  ;; progress notifications before the result
                                  (apply str
                                         (map (fn [p]
                                                (str "event: message\ndata: "
                                                     (json/generate-string
                                                      {:jsonrpc "2.0"
                                                       :method "notifications/progress"
                                                       :params {:progress p :total 100
                                                                :progressToken progress-token
                                                                :message (str "p" p)}})
                                                     "\n\n"))
                                              [10 50]))
                                  "")
                      body (str sse-parts
                                "event: message\ndata: "
                                (json/generate-string response) "\n\n")]
                  (http-response 200 body
                                 (merge {"Content-Type" "text/event-stream"} session-header)))
                (http-response 200 (json/generate-string response)
                               (merge {"Content-Type" "application/json"} session-header))))))))))

(defn- sse-handler
  "The GET /sse stream: endpoint event, then every POSTed result as a
   message event, until the client disconnects."
  [socket]
  (try
    (with-open [in (.getInputStream socket)
                out (.getOutputStream socket)]
      (let [writer (io/writer out)
            ch (async/chan 64)]
        (.write writer (str "HTTP/1.1 200 OK\r\n"
                            "Content-Type: text/event-stream\r\n"
                            "Cache-Control: no-cache\r\n\r\n"
                            "event: endpoint\ndata: /sse\n\n"))
        (.flush writer)
        (reset! state (assoc @state :sse-chan ch))
        (loop []
          (let [result (async/<!! ch)]
            (when result
              (.write writer (str "event: message\ndata: "
                                  (json/generate-string result) "\n\n"))
              (.flush writer)
              (recur))))))
    (catch Exception _ nil)
    (finally
      (swap! state dissoc :sse-chan))))

(defn- handle-conn
  "Handle one accepted connection: SSE streams go to sse-handler (with the
   already-read request line); everything else is a plain request/response
   read through READER (the buffered reader that consumed the request
   line)."
  [socket line reader]
  (try
    (if (and line (str/includes? line "GET /sse"))
      (sse-handler socket)
      (with-open [out (.getOutputStream socket)]
        (let [req (read-request-from reader line)]
          (when req
            (let [path (:path req)
                  method (:method req)
                  response (cond
                             (and (= path "/sse") (= method "POST"))
                             (let [result (handle-json-rpc (:body req) nil)]
                               (when-let [ch (:sse-chan @state)]
                                 (async/>!! ch result))
                               (http-response 202 "" {"Content-Type" "application/json"}))

                             ;; modern: the session-era GET stream and
                             ;; DELETE are gone (spec, Earlier Streamable
                             ;; HTTP Revisions)
                             (and (= path "/mcp")
                                  (str/includes? (or (:query req) "") "era=modern")
                                  (#{"GET" "DELETE"} method))
                             (http-response 405 "method not allowed" {})

                             (= path "/mcp")
                             (if (= method "DELETE")
                               ;; session termination — the marker line
                               ;; lets a test assert the client released
                               ;; the session on close!
                               (do (println "SESSION DELETED")
                                   (flush)
                                   (http-response 200 "" {}))
                               (handle-streamable out req))

                             :else (http-response 404 "not found"))]
              (when response
                (.write out (.getBytes response "UTF-8"))
                (.flush out)))))))
    (catch Exception e
      (binding [*out* *err*] (println "DBG handle-conn-error:" (ex-message e))))))

(defn -main [& _]
  (let [server (java.net.ServerSocket. port 10
                                       (java.net.InetAddress/getByName "127.0.0.1"))]
    (reset! state (assoc @state :port (.getLocalPort server)))
    (println "PORT" (.getLocalPort server))
    (flush)
    (loop []
      (try
        (let [socket (.accept server)]
          (future
            (try
              (let [reader (io/reader (.getInputStream socket))
                    line (.readLine reader)]
                (handle-conn socket line reader))
              (catch Exception e
                (binding [*out* *err*] (println "DBG conn-error:" (ex-message e)))))))
        (catch Exception e
          (binding [*out* *err*] (println "DBG accept-error:" (ex-message e)))))
      (recur))))

(-main)
