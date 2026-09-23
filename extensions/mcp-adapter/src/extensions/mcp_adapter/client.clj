(ns extensions.mcp-adapter.client
  "MCP client transports + JSON-RPC for the mcp-adapter extension (§7 of
   the design contract — pi: server-manager.ts adapted to babashka: sync,
   no SDK; JSON-RPC is spoken directly).

   Transports:
     :stdio           — spawn (babashka.process), line-delimited JSON over
                         stdout/stdin, core.async response channel, bounded
                         stderr tail for diagnostics, process-tree kill on
                         close (kmet.libs.process).
     :streamable-http — per-request POST (kmet.libs.http, :as :stream);
                         initialize captures Mcp-Session-Id;
                         responses parsed by content type (application/json
                         or text/event-stream).
     :sse             — GET stream with a background reader (endpoint +
                         message events), POST to the endpoint URL,
                         id-matched waits, reconnect-on-drop re-runs
                         initialize (bounded retry).

   Public protocol: request! / notify! / close! / alive? (§7.1).
   request! throws ex-info on JSON-RPC error, timeout, or transport death
   (§7.7 message patterns). Notifications received mid-request are
   consumed/dropped — except notifications/progress, which are forwarded
   to the :on-notification callback when one is given (streaming
   tool-call progress); stale responses (non-matching :id) are dropped and
   the wait loop continues. Every request!/notify! touches :last-used so
   the idle reaper can disconnect unused servers."
  (:require [babashka.process :as proc]
            [kmet.libs.json :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.http :as http]
            [kmet.libs.process :as process]
            [kmet.libs.sse :as sse]))

(def default-request-timeout-ms 120000)
(def initialize-timeout-ms 60000)
(def list-page-timeout-ms 30000)
(def protocol-version "2025-06-18")
(def client-info {:name "kmet-mcp-adapter" :version "0.1.0"})

(defn- mcp-error
  "ex-info with the §7.7 message patterns."
  ([message] (ex-info message {:type :mcp-error}))
  ([message data] (ex-info message (assoc data :type :mcp-error))))

(def ^:private eof-marker ::eof)

(def ^:private spawn concurrent/spawn)

(defn- read-stream
  "Read an InputStream fully as text."
  [in]
  (slurp in))

;; ─── stdio transport (§7.2) ───────────────────────────────────────────────

(def ^:private stderr-tail-lines 20)

(defn- stdio-argv
  "Full argv for a stdio server: :command may be a string or a vector
   (vector = full argv, merged with :args)."
  [definition]
  (let [{:keys [command args]} definition]
    (if (vector? command)
      (into (vec command) args)
      (into [(str command)] args))))

(defn- drain-stdout
  "Background reader: stdout lines → JSON parse → response channel.
   Non-JSON lines (banners on stdout) are dropped. ::eof + close on EOF."
  [out ch]
  (try
    (with-open [rdr (io/reader out)]
      (doseq [line (line-seq rdr)]
        (when (seq (str/trim line))
          (try
            (let [parsed (json/parse-string line true)]
              (when (map? parsed)
                (async/put! ch parsed)))
            (catch Exception _ nil)))))
    (catch Exception _ nil)
    (finally
      (async/put! ch eof-marker)
      (async/close! ch))))

(defn- drain-stderr
  "Background reader: keep the last 20 stderr lines for diagnostics."
  [err tail]
  (try
    (with-open [rdr (io/reader err)]
      (doseq [line (line-seq rdr)]
        (swap! tail (fn [lines]
                      (vec (take-last stderr-tail-lines
                                      (conj (vec lines) line)))))))
    (catch Exception _ nil)))

(defn- connect-stdio
  "Spawn a stdio server process. Returns the conn map."
  [definition]
  (let [argv (stdio-argv definition)
        env (merge (into {} (System/getenv)) (:env definition))
        ;; the vector form: babashka.process's variadic form silently drops
        ;; the opts map, so `(apply proc/process argv opts)` never passed
        ;; :env (per-server env was broken)
        p (proc/process (vec argv)
                        {:in :stream :out :stream :err :stream
                         :dir (:cwd definition)
                         :env env})
        pid (process/process-pid p)
        ch (async/chan 128)
        tail (atom [])]
    (when pid (process/track-pid! pid))
    (spawn #(drain-stdout (:out p) ch))
    (spawn #(drain-stderr (:err p) tail))
    {:transport :stdio
     :proc p
     :pid pid
     :ch ch
     :stderr-tail tail
     :id-counter (atom 0)
     :last-used (atom (System/currentTimeMillis))}))

;; ─── streamable-http transport (§7.3) ────────────────────────────────────

(defn- header-value
  "Case-insensitive header lookup."
  [headers name]
  (some (fn [[k v]]
          (when (= (str/lower-case (str k)) (str/lower-case name)) v))
        headers))

(defn- connect-streamable-http
  "HTTP conn: per-request POSTs. OPTS: :auth-headers (fn [] → headers map
   or nil, called per request), :on-401 (fn [] → fresh headers; called once
   per request when the first attempt answers 401)."
  [url opts]
  {:transport :streamable-http
   :url url
   :session-id (atom nil)
   :id-counter (atom 0)
   :closed (atom false)
   :last-used (atom (System/currentTimeMillis))
   :auth-headers (:auth-headers opts)
   :on-401 (:on-401 opts)})

(defn- base-http-headers
  "Static headers for an MCP POST (Mcp-Session-Id after initialize)."
  [conn]
  (cond-> {"Content-Type" "application/json"
           "Accept" "application/json, text/event-stream"}
    @(:session-id conn) (assoc "Mcp-Session-Id" @(:session-id conn))))

(defn- http-request-headers
  [conn]
  (let [headers (base-http-headers conn)]
    (if-let [auth (:auth-headers conn)]
      (merge headers (or (auth) {}))
      headers)))

(defn- read-sse-response
  "Read an SSE response body: collect data: payloads (event: lines set the
   event name), keep the one whose parsed JSON matches our id; progress
   notifications (no :id, method notifications/progress) are forwarded to
   ON-NOTIFICATION when given; the server closes the stream after the
   result."
  [body id on-notification]
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
                (cond
                  (and (map? parsed) (= id (:id parsed))) parsed
                  (and on-notification (map? parsed) (nil? (:id parsed))
                       (= "notifications/progress" (:method parsed)))
                  (do (on-notification parsed) (recur nil ""))
                  :else (recur nil "")))
              :else (recur event-name buf))))))))

(defn- http-post!
  "POST one JSON-RPC message; retries once with fresh headers on 401 when
   the conn has an :on-401 hook."
  [conn url headers body timeout-ms]
  (let [attempt (fn [hs]
                  (http/post url
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
        (attempt (merge (base-http-headers conn) (or ((:on-401 conn)) {}))))
      response)))

(defn- parse-http-response
  "Parse an HTTP response by content type into the JSON-RPC response map
   (or nil when the body is empty — a 202-accepted without a direct
   response). SSE bodies are read until the message with :id = ID arrives.
   The transport is closed on every path — by the time this returns (or
   throws) the body has been fully read or abandoned, so the curl process
   is reaped and its temp files deleted (a stream that must stay open
   beyond this call is only used by the sse GET, which manages it
   separately)."
  [response id on-notification]
  (try
    (let [status (:status response)
          content-type (or (header-value (:headers response) "Content-Type") "")]
      (cond
        (<= 200 status 299)
        (cond
          (str/includes? content-type "text/event-stream")
          (read-sse-response (:body response) id on-notification)

          (str/includes? content-type "application/json")
          (let [text (read-stream (:body response))]
            (when (seq (str/trim text))
              (json/parse-string text true)))

          :else
          (throw (mcp-error (str "MCP connect failed: unexpected response content type "
                                 content-type " (status " status ")")
                            {:status status})))

        :else
        (throw (mcp-error (str "MCP connect failed: HTTP " status
                               (when-let [b (:body response)]
                                 (let [text (str/trim (read-stream b))]
                                   (when (seq text) (str ": " text)))))
                          {:status status}))))
    (finally
      (try (http/close! response) (catch Exception _ nil)))))

;; ─── legacy SSE transport (§7.4) ──────────────────────────────────────────

(defn- sse-endpoint-url
  "Resolve the POST endpoint from an SSE `endpoint` event: assume the data
   is a bare path unless it parses as JSON; append relative to the GET url."
  [get-url data]
  (let [data (str/trim (or data ""))]
    (if (str/starts-with? data "{")
      (let [parsed (try (json/parse-string data true) (catch Exception _ nil))]
        (if (and (map? parsed) (string? (:uri parsed)))
          (:uri parsed)
          get-url))
      (if (str/starts-with? data "/")
        (let [uri (java.net.URI. get-url)]
          (str (.getScheme uri) "://" (.getAuthority uri) data))
        get-url))))

(defn- drain-sse-stream
  "Background reader for the legacy SSE GET stream: `endpoint` event → the
   POST URL; `message` event → JSON-RPC → channel; ::eof + close on EOF."
  [body ch endpoint-atom get-url]
  (try
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
                (do
                  (if (= "endpoint" event-name)
                    (reset! endpoint-atom (sse-endpoint-url get-url buf))
                    (try
                      (let [parsed (json/parse-string buf true)]
                        (when (map? parsed)
                          (async/put! ch parsed)))
                      (catch Exception _ nil)))
                  (recur nil ""))
                :else (recur event-name buf)))))))
    (catch Exception _ nil)
    (finally
      (async/put! ch eof-marker)
      (async/close! ch))))

(defn- open-sse-stream!
  "(Re)open the SSE GET stream; resets the response channel and stores the
   active response on the conn (:response) so close! can abort it (the
   reader thread releases on disconnect)."
  [conn]
  (let [headers (merge {"Accept" "text/event-stream"}
                       (or (when-let [auth (:auth-headers conn)] (auth)) {}))
        response (http/get (:url conn)
                           {:headers headers
                            :as :stream
                            :throw? false
                            :timeout 30000})]
    (when-not (<= 200 (:status response) 299)
      ;; close the failed stream (reap curl / release the body) before
      ;; surfacing the MCP error
      (http/close! response)
      (throw (mcp-error (str "MCP connect failed: HTTP " (:status response)
                             " opening SSE stream")
                        {:status (:status response)})))
    ;; an earlier stream (reconnect) is abandoned — the reader thread is
    ;; gone with the old channel; a mid-stream transport failure there is
    ;; stale noise, not the reconnect's problem
    (when-let [prev @(:response conn)]
      (try (http/close! prev) (catch Exception _ nil)))
    (let [ch (async/chan 128)]
      (spawn #(drain-sse-stream (:body response) ch (:endpoint-atom conn) (:url conn)))
      (reset! (:ch conn) ch)
      (reset! (:response conn) response)
      (reset! (:stream-open conn) true))))

(defn- connect-sse
  "Legacy SSE conn: a GET stream + POSTs to the endpoint URL."
  [url opts]
  {:transport :sse
   :url url
   :endpoint-atom (atom nil)
   :ch (atom nil)
   :response (atom nil)
   :stream-open (atom false)
   :session-id (atom nil)
   :id-counter (atom 0)
   :closed (atom false)
   :last-used (atom (System/currentTimeMillis))
   :auth-headers (:auth-headers opts)
   :on-401 (:on-401 opts)})

;; ─── request!/notify!/close!/alive? (§7.1) ────────────────────────────────

(declare initialize!)

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

(defn- write-stdio-msg!
  [conn msg]
  ;; io/copy instead of .write — direct stream methods are not callable
  ;; from the extension sci context
  (let [line (str (json/generate-string msg) "\n")]
    (io/copy line (:in (:proc conn))))
  nil)

(defn- wait-for-response
  "Wait on CH for the message with :id = ID; notifications and stale
   responses are dropped and the loop continues. Returns the response map
   (with :result or :error), or ::eof when the channel closed (transport
   death) before the response arrived. The timeout is an overall deadline —
   notifications do not extend it."
  [ch id method timeout-ms on-notification]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [remaining (- deadline (System/currentTimeMillis))
            timeout-ch (async/timeout (max 1 remaining))
            [value port] (async/alts!! [ch timeout-ch])]
        (cond
          (identical? port timeout-ch)
          (throw (mcp-error (str "MCP request timed out after " timeout-ms "ms: " method)
                            {:timeout-ms timeout-ms :method method}))

          (or (nil? value) (= eof-marker value)) eof-marker

          :else
          (let [msg value]
            (cond
              (not (map? msg)) (recur)
              (nil? (:id msg))
              (do
                (when (and on-notification
                           (= "notifications/progress" (:method msg)))
                  (on-notification msg))
                (recur))
              (not= id (:id msg)) (recur)         ;; stale response
              (contains? msg :error)
              (throw (mcp-error (str "MCP error " (:code (:error msg)) ": "
                                     (:message (:error msg)))
                                {:code (:code (:error msg))
                                 :message (:message (:error msg))}))
              :else msg)))))))

(defn- stdio-dead-message
  "Diagnostic message for a dead stdio process (stderr tail appended)."
  [conn method]
  (str "MCP connect failed: process exited"
       (when method (str " while waiting for " method))
       (let [tail (str/join " — " @(:stderr-tail conn))]
         (when (seq tail) (str " (stderr: " tail ")")))))

(defn- request-stdio!
  [conn method params id timeout-ms on-notification]
  (when-not (proc/alive? (:proc conn))
    (throw (mcp-error (stdio-dead-message conn nil) {:transport :stdio})))
  (write-stdio-msg! conn {:jsonrpc "2.0" :id id :method method :params params})
  (let [response (wait-for-response (:ch conn) id method timeout-ms on-notification)]
    (if (= eof-marker response)
      (throw (mcp-error (stdio-dead-message conn method) {:transport :stdio}))
      (:result response))))

(defn- request-http!
  [conn method params id timeout-ms on-notification]
  (let [result-p (promise)
        _ (spawn
           (fn []
             (let [value (try
                           (let [body (json/generate-string
                                       {:jsonrpc "2.0" :id id :method method :params params})
                                 response (http-post! conn (:url conn)
                                                      (http-request-headers conn)
                                                      body (+ timeout-ms 5000))]
                             (when-let [session-id (header-value (:headers response)
                                                                 "Mcp-Session-Id")]
                               (reset! (:session-id conn) session-id))
                             (parse-http-response response id on-notification))
                           (catch Exception e
                             (if (timeout-exception? e)
                               (mcp-error (str "MCP request timed out after " timeout-ms "ms: " method)
                                          {:timeout-ms timeout-ms :method method})
                               e)))]
               (deliver result-p value))))
        response (deref result-p timeout-ms ::timeout)]
    (if (= ::timeout response)
      (do
        ;; The server may still complete the call; the result is lost (no
        ;; abort transport in bb) — documented limitation (§7.3). The conn
        ;; is dead after this: the client was closed.
        (reset! (:closed conn) true)
        (throw (mcp-error (str "MCP request timed out after " timeout-ms "ms: " method)
                          {:timeout-ms timeout-ms :method method})))
      (if (instance? Exception response)
        (throw response)
        (if (:error response)
          (throw (mcp-error (str "MCP error " (:code (:error response)) ": "
                                 (:message (:error response)))
                            {:code (:code (:error response))
                             :message (:message (:error response))}))
          (if (nil? response)
            (throw (mcp-error (str "MCP connect failed: empty response to " method)
                              {:method method}))
            (:result response)))))))

(defn- sse-endpoint!
  "The POST endpoint for an SSE conn — waits up to 5s for the stream's
   `endpoint` event (the reader thread delivers it asynchronously)."
  [conn]
  (or (some (fn [_]
              (or @(:endpoint-atom conn)
                  (do (Thread/sleep 100) nil)))
            (range 50))
      (throw (mcp-error "MCP connect failed: no SSE endpoint received"
                        {:transport :sse}))))

(defn- request-sse!
  [conn method params id timeout-ms on-notification]
  (loop [attempts 0]
    (let [ch @(:ch conn)]
      (when (nil? ch)
        (throw (mcp-error "MCP connect failed: SSE stream not open"
                          {:transport :sse})))
      (let [endpoint (sse-endpoint! conn)
            body (json/generate-string {:jsonrpc "2.0" :id id :method method :params params})
            response (http-post! conn endpoint (http-request-headers conn)
                                 body timeout-ms)
            parsed (parse-http-response response id on-notification)]
        (if (and (map? parsed) (contains? parsed :id) (:error parsed))
          (throw (mcp-error (str "MCP error " (:code (:error parsed)) ": "
                                 (:message (:error parsed)))
                            {:code (:code (:error parsed))
                             :message (:message (:error parsed))}))
          (let [wait-result (if (and (map? parsed) (contains? parsed :id))
                              parsed
                              (wait-for-response ch id method timeout-ms on-notification))]
            (if (= eof-marker wait-result)
              ;; Stream dropped — reopen + re-initialize (bounded retry).
              (if (< attempts 1)
                (do (reset! (:stream-open conn) false)
                    (open-sse-stream! conn)
                    (initialize! conn)
                    (recur (inc attempts)))
                (throw (mcp-error (str "MCP connect failed: SSE stream dropped while waiting for "
                                       method)
                                  {:transport :sse})))
              (if (:error wait-result)
                (throw (mcp-error (str "MCP error " (:code (:error wait-result)) ": "
                                       (:message (:error wait-result)))
                                  {:code (:code (:error wait-result))
                                   :message (:message (:error wait-result))}))
                (:result wait-result)))))))))

(defn request!
  "Send a JSON-RPC request and return its :result. OPTS:
   {:timeout-ms n (default 120000) :on-notification (fn [notification])
   — receives notifications/progress events arriving mid-request
   (streaming tool-call progress)}. Throws ex-info on JSON-RPC error,
   timeout, or transport death (§7.7)."
  [conn method params & [{:keys [timeout-ms on-notification]}]]
  (let [id (swap! (:id-counter conn) inc)
        timeout (or timeout-ms default-request-timeout-ms)]
    (when-let [lu (:last-used conn)] (reset! lu (System/currentTimeMillis)))
    (case (:transport conn)
      :stdio (request-stdio! conn method params id timeout on-notification)
      :streamable-http (request-http! conn method params id timeout on-notification)
      :sse (request-sse! conn method params id timeout on-notification))))

(defn notify!
  "Send a JSON-RPC notification (no response expected)."
  [conn method params]
  (let [msg {:jsonrpc "2.0" :method method :params params}
        body (json/generate-string msg)]
    (when-let [lu (:last-used conn)] (reset! lu (System/currentTimeMillis)))
    (case (:transport conn)
      :stdio (write-stdio-msg! conn msg)
      (:streamable-http :sse)
      (try
        (let [endpoint (if (= :sse (:transport conn))
                         (or @(:endpoint-atom conn)
                             (throw (mcp-error "MCP connect failed: no SSE endpoint received"
                                               {:transport :sse})))
                         (:url conn))
              response (http-post! conn endpoint (http-request-headers conn) body 30000)]
          ;; fire-and-forget: the body is never read — reap the transport
          ;; (curl: untrack pid + delete temp files) right away
          (http/close! response))
        ;; a notification that cannot be delivered is dropped, never
        ;; surfaced (pi parity — notifications are best-effort)
        (catch Exception _ nil)))
    nil))

(defn close!
  "Close a connection: kill the stdio process tree, abort the active SSE
   stream (releases the blocked reader + reaps the transport). Idempotent."
  [conn]
  (case (:transport conn)
    :stdio
    (do
      (when (:pid conn) (process/kill-process-tree! (:pid conn)))
      (try (async/close! (:ch conn)) (catch Exception _ nil)))

    (:streamable-http :sse)
    (do
      (reset! (:closed conn) true)
      (when (= :sse (:transport conn))
        (reset! (:stream-open conn) false)
        (when-let [r @(:response conn)]
          (try (http/close! r) (catch Exception _ nil))))))
  nil)

(defn alive?
  "True when the connection is still usable."
  [conn]
  (case (:transport conn)
    :stdio (boolean (and (:proc conn) (proc/alive? (:proc conn))))
    :streamable-http (not @(:closed conn))
    :sse (boolean (and @(:stream-open conn) (not @(:closed conn))))))

(defn last-used
  "The last activity timestamp (ms) for a connection — the idle reaper
   disconnects servers whose conn has been idle past the configured
   :idle-timeout."
  [conn]
  (or (when-let [lu (:last-used conn)] @lu) 0))

;; ─── Handshake + discovery (§7.5) ─────────────────────────────────────────

(defn initialize!
  "Run the MCP handshake: initialize (60s timeout) → notifications/
   initialized. Returns {:protocol-version str :server-info map
   :capabilities map}."
  [conn]
  (let [result (request! conn "initialize"
                         {:protocolVersion protocol-version
                          :capabilities {}
                          :clientInfo client-info}
                         {:timeout-ms initialize-timeout-ms})]
    (notify! conn "notifications/initialized" {})
    {:protocol-version (or (:protocolVersion result) protocol-version)
     :server-info (:serverInfo result)
     :capabilities (or (:capabilities result) {})}))

(defn list-all-tools
  "tools/list with cursor pagination (nextCursor loop, 30s per page)."
  [conn]
  (loop [cursor nil tools []]
    (let [result (request! conn "tools/list"
                           (if cursor {:cursor cursor} {})
                           {:timeout-ms list-page-timeout-ms})
          tools (into tools (:tools result))]
      (if-let [next-cursor (:nextCursor result)]
        (recur next-cursor tools)
        tools))))

(defn list-all-prompts
  "prompts/list with cursor pagination (30s per page)."
  [conn]
  (loop [cursor nil prompts []]
    (let [result (request! conn "prompts/list"
                           (if cursor {:cursor cursor} {})
                           {:timeout-ms list-page-timeout-ms})
          prompts (into prompts (:prompts result))]
      (if-let [next-cursor (:nextCursor result)]
        (recur next-cursor prompts)
        prompts))))

(defn get-prompt
  "prompts/get — result contains :messages; :arguments is a string map
   (omitted when empty)."
  [conn name arguments & [{:keys [timeout-ms]}]]
  (request! conn "prompts/get"
            (cond-> {:name name}
              (seq arguments) (assoc :arguments arguments))
            {:timeout-ms (or timeout-ms default-request-timeout-ms)}))

(defn list-all-resources
  "resources/list with cursor pagination (30s per page)."
  [conn]
  (loop [cursor nil resources []]
    (let [result (request! conn "resources/list"
                           (if cursor {:cursor cursor} {})
                           {:timeout-ms list-page-timeout-ms})
          resources (into resources (:resources result))]
      (if-let [next-cursor (:nextCursor result)]
        (recur next-cursor resources)
        resources))))

(defn read-resource
  "resources/read — result contains :contents (text or blob blocks)."
  [conn uri & [{:keys [timeout-ms]}]]
  (request! conn "resources/read" {:uri uri}
            {:timeout-ms (or timeout-ms default-request-timeout-ms)}))

(defn connect!
  "Full connect for a DEFINITION: build the transport, handshake,
   tools/list. OPTS: :auth-headers / :on-401 (HTTP transports, §7.8).
   Returns {:conn conn :tools [..] :protocol-version str :server-info map}.
   On any failure the transport is closed and the ex-info rethrown."
  [definition opts]
  (let [url (:url definition)
        conn (if url
               (case (:http-transport definition)
                 :sse (connect-sse url opts)
                 (connect-streamable-http url opts))
               (connect-stdio definition))]
    (try
      (when (and url (= :sse (:transport conn)))
        (open-sse-stream! conn))
      (let [{:keys [protocol-version server-info capabilities]} (initialize! conn)
            capabilities (or capabilities {})]
        {:conn conn
         ;; prompts/resources are only queried when the server advertises
         ;; the capability (an unadvertised method errors with -32601)
         :tools (if (:tools capabilities) (list-all-tools conn) [])
         :prompts (if (:prompts capabilities) (list-all-prompts conn) [])
         :resources (if (:resources capabilities) (list-all-resources conn) [])
         :protocol-version protocol-version
         :server-info server-info})
      (catch Exception e
        (close! conn)
        (throw e)))))

;; ─── Result formatting (§7.6) ─────────────────────────────────────────────

(defn format-result
  "Flatten a tools/call result into text (§7.6): text/error content blocks
   joined with newlines; image → '[image: <mimeType>, <n> bytes — not
   rendered]'; empty content with structuredContent → pretty JSON; fallback
   '(no text content)'. Returns {:text str :is-error bool}."
  [result]
  (let [blocks (or (:content result) [])
        texts (keep (fn [b]
                      (case (:type b)
                        "text" (:text b)
                        "error" (:text b)
                        "image" (str "[image: " (or (:mimeType b) "?") ", "
                                     (count (or (:data b) ""))
                                     " bytes — not rendered]")
                        nil))
                    blocks)
        text (cond
               (seq texts) (str/join "\n" texts)
               (seq (:structuredContent result))
               (json/generate-string (:structuredContent result) {:pretty true})
               :else "(no text content)")]
    {:text text :is-error (true? (:isError result))}))
