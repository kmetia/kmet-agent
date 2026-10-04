(ns kmet.libs.mcp.transport.http
  "MCP streamable-HTTP transport (§7.3): per-request POST (kmet.libs.http,
   :as :stream); initialize captures Mcp-Session-Id, and later POSTs carry
   the negotiated MCP-Protocol-Version; responses are parsed by content
   type (application/json or text/event-stream). A modern conn sends no
   session header and mirrors the method (and the tool/prompt/resource
   name) in Mcp-Method/Mcp-Name routing headers.

   Conn keys: :url :session-id :protocol-version :closed, plus the common
   contract keys documented in kmet.libs.mcp.transport.

   This namespace also owns the HTTP POST/parse helpers and send-async!
   that the legacy SSE transport (kmet.libs.mcp.transport.sse) reuses."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.http :as http]
            [kmet.libs.json :as json]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport :as transport]
            [kmet.libs.sse :as sse]))

(def ^:private spawn concurrent/spawn)

(declare send-async!)

;; ─── Conn + headers ───────────────────────────────────────────────────────

(defn connect!
  "HTTP conn: per-request POSTs. OPTS: :auth-headers (fn [] → headers map
   or nil, called per request), :on-401 (fn [] → fresh headers; called once
   per request when the first attempt answers 401), :on-notification.
   :protocol-version is filled in by the initialize handshake (nil until
   then) — see http-request-headers."
  [url opts]
  {:transport :streamable-http
   :url url
   :session-id (atom nil)
   :protocol-version (atom nil)
   :id-counter (atom 0)
   :closed (atom false)
   :last-used (atom (concurrent/monotonic-ms))
   :auth-headers (:auth-headers opts)
   :on-401 (:on-401 opts)
   :on-notification (:on-notification opts)
   :request-fn (:request-fn opts)
   :req-lock (Object.)})

(defn- base-http-headers
  "Static headers for an MCP POST: Mcp-Session-Id and the negotiated
   MCP-Protocol-Version (both present only after a handshake). A modern
   conn has no session — the header is never mirrored, even when a
   response carried one."
  [conn]
  (cond-> {"Content-Type" "application/json"
           "Accept" "application/json, text/event-stream"}
    (and (not (protocol/modern-conn? conn)) @(:session-id conn))
    (assoc "Mcp-Session-Id" @(:session-id conn))
    (and (:protocol-version conn) @(:protocol-version conn))
    (assoc "MCP-Protocol-Version" @(:protocol-version conn))))

(defn encode-header-value
  "Encode a mirrored header value (Mcp-Name) for the wire (spec, Header
   Mirroring): a value that cannot ride in a header field value
   unchanged — non-ASCII or control characters, a leading or trailing
   space (parsers trim it) — or that itself begins with the Base64
   sentinel is sent as a Base64 sentinel, so it round-trips exactly."
  [v]
  (let [s (str v)]
    (if (and (not (str/starts-with? s "=?base64?"))
             (not (str/starts-with? s " "))
             (not (str/ends-with? s " "))
             (every? (fn [ch] (let [c (int ch)] (or (= c 32) (<= 33 c 126))))
                     s))
      s
      (str "=?base64?"
           (.encodeToString (java.util.Base64/getEncoder)
                            (.getBytes s "UTF-8"))
           "?="))))

(defn- name-header-value
  "The Mcp-Name value of METHOD's params: the tool or prompt name, or the
   resource URI."
  [method params]
  (case method
    ("tools/call" "prompts/get") (:name params)
    "resources/read" (:uri params)
    nil))

(defn routing-headers
  "The 2026-07-28 per-request routing headers (spec, Streamable HTTP):
   Mcp-Method on every request, Mcp-Name for the methods that name a
   tool, resource or prompt. Empty for a legacy conn."
  [conn method params]
  (if (protocol/modern-conn? conn)
    (let [name (name-header-value method params)]
      (cond-> {"Mcp-Method" method}
        name (assoc "Mcp-Name" (encode-header-value name))))
    {}))

(defn http-request-headers
  "The headers for one MCP POST. The 1-arity is the base set (base +
   auth) for callers without a JSON-RPC message (the SSE transport, a
   session DELETE); the 2-arity adds the modern routing headers."
  ([conn]
   (let [headers (base-http-headers conn)]
     (if-let [auth (:auth-headers conn)]
       (merge headers (or (auth) {}))
       headers)))
  ([conn method params]
   (merge (http-request-headers conn) (routing-headers conn method params))))

;; ─── POST + response parsing ──────────────────────────────────────────────

(defn http-post!
  "POST one JSON-RPC message; retries once with fresh headers on 401 when
   the conn has an :on-401 hook. The hook receives the 401 response so it
   can read the WWW-Authenticate challenge (RFC 9728 resource metadata /
   scope) before answering."
  [conn url headers body timeout-ms]
  (let [post (or (:request-fn conn) http/post)
        attempt (fn [hs]
                  (post url
                        {:headers hs
                         :body body
                         :as :stream
                         :throw? false
                         :timeout timeout-ms}))
        response (attempt headers)]
    (if (and (= 401 (:status response)) (:on-401 conn))
      (do
        ;; the discarded 401 body is never read — reap its transport
        (http/close! response)
        (attempt (merge (base-http-headers conn)
                        (or ((:on-401 conn) response) {}))))
      response)))

(defn- read-sse-response
  "Read an SSE response body: collect data: payloads (event: lines set the
   event name) and return the one whose parsed JSON matches our id.
   Anything else on the stream is a server->client request or
   notification and is dispatched (§7.7); progress notifications
   stream to ON-NOTIFICATION. The server closes the stream after the
   result."
  [conn body id on-notification]
  (with-open [rdr (io/reader body)]
    (loop [event-name nil buf ""]
      (let [line (.readLine rdr)]
        (cond
          (nil? line) nil
          :else
          (let [[ev data] (sse/parse-sse-line line)]
            (cond
              ev (recur ev buf)
              data (recur event-name (str buf data))
              (and (str/blank? line) (seq buf))
              (let [parsed (try (json/parse-string buf true)
                                (catch Exception _ nil))]
                (if (and (map? parsed) (= id (:id parsed)))
                  parsed
                  (do (when (map? parsed)
                        (transport/dispatch-server-message!
                         conn parsed on-notification send-async!))
                      (recur nil ""))))
              :else (recur event-name buf))))))))

(defn parse-http-response
  "Parse an HTTP response by content type into the JSON-RPC response map
   (or nil when the body is empty — a 202-accepted without a direct
   response). SSE bodies are read until the message with :id = ID arrives.
   The transport is closed on every path — by the time this returns (or
   throws) the body has been fully read or abandoned, so the curl process
   is reaped and its temp files deleted (a stream that must stay open
   beyond this call is only used by the sse GET, which manages it
   separately)."
  [conn response id on-notification]
  (try
    (let [status (:status response)
          content-type (or (transport/header-value (:headers response) "Content-Type") "")]
      (cond
        (<= 200 status 299)
        (cond
          (str/includes? content-type "text/event-stream")
          (read-sse-response conn (:body response) id on-notification)

          (str/includes? content-type "application/json")
          (let [text (slurp (:body response))]
            (when (seq (str/trim text))
              (json/parse-string text true)))

          :else
          (throw (protocol/mcp-error (str "MCP connect failed: unexpected response content type "
                                          content-type " (status " status ")")
                                     {:status status})))

        :else
        (let [text (when-let [b (:body response)]
                     (try (str/trim (slurp b)) (catch Exception _ nil)))
              error (:error (when (seq text)
                              (try (json/parse-string text true)
                                   (catch Exception _ nil))))]
          (throw (protocol/mcp-error
                  (str "MCP connect failed: HTTP " status
                       (when (seq text) (str ": " text)))
                  (cond-> {:status status}
                    ;; a JSON-RPC error body lands in the ex-data — the
                    ;; HTTP era detection inspects :code/:data
                    (map? error) (assoc :code (:code error)
                                        :message (:message error)
                                        :data (:data error))))))))
    (finally
      (try (http/close! response) (catch Exception _ nil)))))

;; ─── Request + async send ─────────────────────────────────────────────────

(defn- timeout-exception?
  "True when E (or a cause) is a java.net.http.HttpTimeoutException or
   carries a 'timed out' message."
  [e]
  (loop [e e]
    (cond
      (nil? e) false
      (instance? java.net.http.HttpTimeoutException e) true
      (str/includes? (str (ex-message e)) "timed out") true
      :else (recur (ex-cause e)))))

(defn request!
  "POST a request and wait for its response (a background thread reads the
   body; the caller's deadline bounds the wait). On timeout the request is
   cancelled and the conn is marked closed — the abandoned response has no
   transport to read it."
  [conn method params timeout-ms on-notification]
  (transport/touch! conn)
  (let [id (swap! (:id-counter conn) inc)
        result-p (promise)
        _ (spawn
           (fn []
             (let [value (try
                           (let [body (json/generate-string
                                       {:jsonrpc "2.0" :id id :method method :params params})
                                 response (http-post! conn (:url conn)
                                                      (http-request-headers conn method params)
                                                      body (+ timeout-ms 5000))]
                             ;; a modern conn has no session: a header a
                             ;; probe or discovery response carried is
                             ;; never captured (nor mirrored later)
                             (when-let [session-id (and (not (protocol/modern-conn? conn))
                                                        (transport/header-value
                                                         (:headers response)
                                                         "Mcp-Session-Id"))]
                               (reset! (:session-id conn) session-id))
                             (parse-http-response conn response id on-notification))
                           (catch Exception e
                             (if (timeout-exception? e)
                               (protocol/mcp-error
                                (str "MCP request timed out after " timeout-ms "ms: " method)
                                {:timeout-ms timeout-ms :method method})
                               e)))]
               (deliver result-p value))))
        response (deref result-p timeout-ms ::timeout)]
    (if (= ::timeout response)
      (do
        ;; The server may still complete the call; the result is lost (no
        ;; abort transport in bb) — documented limitation (§7.3). The conn
        ;; is dead after this: the client was closed. Tell the server to
        ;; stop working on the abandoned request.
        (reset! (:closed conn) true)
        (transport/cancel! conn id method "timed out" send-async!)
        (throw (protocol/mcp-error (str "MCP request timed out after " timeout-ms "ms: " method)
                                   {:timeout-ms timeout-ms :method method})))
      (if (instance? Exception response)
        (throw response)
        (if (:error response)
          (throw (protocol/mcp-error (str "MCP error " (:code (:error response)) ": "
                                          (:message (:error response)))
                                     {:code (:code (:error response))
                                      :message (:message (:error response))}))
          (if (nil? response)
            (throw (protocol/mcp-error (str "MCP connect failed: empty response to " method)
                                       {:method method}))
            (:result response)))))))

(defn send-async!
  "Deliver a JSON-RPC message that expects no answer over HTTP
   (:streamable-http POSTs to :url, :sse to its endpoint atom). The body
   is never read — the transport is reaped right away. A delivery failure
   is dropped, never surfaced (§7.7: notifications are best-effort)."
  [conn msg]
  (transport/touch! conn)
  (let [body (json/generate-string msg)]
    (try
      (let [endpoint (if (= :sse (:transport conn))
                       (or @(:endpoint-atom conn)
                           (throw (protocol/mcp-error "MCP connect failed: no SSE endpoint received"
                                                      {:transport :sse})))
                       (:url conn))
            response (http-post! conn endpoint
                                 (http-request-headers conn (:method msg) (:params msg))
                                 body 30000)]
        ;; fire-and-forget: the body is never read — reap the transport
        ;; (curl: untrack pid + delete temp files) right away
        (http/close! response))
      (catch Exception _ nil)))
  nil)

;; ─── Teardown ─────────────────────────────────────────────────────────────

(defn terminate-http-session!
  "Release a streamable-HTTP session: an HTTP DELETE to the MCP endpoint
   ends the server-side session (transports — a client that no longer
   needs a session SHOULD delete it). Delivered from a background thread
   — a DELETE (and the auth-header refresh it can trigger) must never
   block the caller; /mcp disconnect runs on the TUI thread. Failures
   are ignored; the session expires on its own. No-op for a modern conn
   (the 2026-07-28 revision has no sessions) or without a negotiated
   Mcp-Session-Id; returns true when a DELETE was scheduled."
  [conn]
  (if (and (= :streamable-http (:transport conn))
           (not (protocol/modern-conn? conn))
           @(:session-id conn))
    (do
      (spawn
       (fn []
         (try
           (http/request {:url (:url conn)
                          :method :delete
                          :headers (http-request-headers conn)
                          :as :string
                          :throw? false
                          :timeout 10000})
           (catch Exception _ nil))
         (reset! (:session-id conn) nil)))
      true)
    false))

(defn close!
  "Mark the conn closed and (unless skipped) DELETE the session. OPTS:
   :terminate-http-session? false skips the DELETE entirely — teardown
   paths (session shutdown, a retry after a request timeout) rely on the
   session expiring on its own. A conn already marked closed (request
   timeout) skips the DELETE too. Idempotent."
  ([conn] (close! conn {}))
  ([conn {:keys [terminate-http-session?] :or {terminate-http-session? true}}]
   (let [was-closed @(:closed conn)]
     (reset! (:closed conn) true)
     (when (and terminate-http-session? (not was-closed))
       (terminate-http-session! conn)))
   nil))

(defn alive?
  "True while the conn has not been closed."
  [conn]
  (not @(:closed conn)))
