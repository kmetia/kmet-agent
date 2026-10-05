(ns kmet.libs.mcp.transport.sse
  "MCP legacy HTTP+SSE transport (§7.4), deprecated by the 2025-03-26
   streamable-HTTP revision. A GET stream carries the `endpoint` event and
   server messages; client requests POST to the endpoint URL and wait for
   the id-matched message on the stream. A dropped stream is reopened once
   and re-established via (:reconnect-fn conn) — the client's initialize —
   so this namespace never requires kmet.libs.mcp.client.

   Conn keys: :url :endpoint-atom :endpoint-ready :ch :response
   :stream-open :session-id :closed :reconnect-fn, plus the common
   contract keys documented in kmet.libs.mcp.transport.

   Kept as the supported legacy HTTP+SSE binding (deprecated by the
   2025-03-26 streamable-HTTP revision but still used in the wild): it
   speaks the handshake era only, no 2026-07-28 behavior."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.http :as http]
            [kmet.libs.json :as json]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport :as transport]
            [kmet.libs.mcp.transport.http :as http-transport]
            [kmet.libs.sse :as sse]))

(def ^:private spawn concurrent/spawn)

;; ─── GET stream ───────────────────────────────────────────────────────────

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
   POST URL and the endpoint-ready signal; `message` event → JSON-RPC →
   channel; ::eof + close on EOF."
  [body ch endpoint-atom get-url endpoint-ready]
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
                data (recur event-name (sse/append-data buf data))
                (and (str/blank? line) (seq buf))
                (do
                  (if (= "endpoint" event-name)
                    (do (reset! endpoint-atom (sse-endpoint-url get-url buf))
                        (deliver endpoint-ready true))
                    (try
                      (let [parsed (json/parse-string buf true)]
                        (when (map? parsed)
                          (async/put! ch parsed)))
                      (catch Exception _ nil)))
                  (recur nil ""))
                :else (recur event-name buf)))))))
    (catch Exception _ nil)
    (finally
      (async/put! ch transport/eof-marker)
      (async/close! ch))))

(defn open-stream!
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
      (throw (protocol/mcp-error (str "MCP connect failed: HTTP " (:status response)
                                      " opening SSE stream")
                                 {:status (:status response)})))
    ;; an earlier stream (reconnect) is abandoned — the reader thread is
    ;; gone with the old channel; a mid-stream transport failure there is
    ;; stale noise, not the reconnect's problem
    (when-let [prev @(:response conn)]
      (try (http/close! prev) (catch Exception _ nil)))
    (let [ch (async/chan 128)
          ready (promise)]
      ;; every (re)open gets its own endpoint-ready signal, so a request
      ;; waits for this stream's `endpoint` event rather than a stale one
      (reset! (:endpoint-ready conn) ready)
      (spawn #(drain-sse-stream (:body response) ch (:endpoint-atom conn)
                                (:url conn) ready))
      (reset! (:ch conn) ch)
      (reset! (:response conn) response)
      (reset! (:stream-open conn) true))))

(defn connect!
  "Legacy SSE conn: a GET stream + POSTs to the endpoint URL."
  [url opts]
  {:transport :sse
   :url url
   :endpoint-atom (atom nil)
   :endpoint-ready (atom nil)
   :ch (atom nil)
   :response (atom nil)
   :stream-open (atom false)
   :session-id (atom nil)
   :id-counter (atom 0)
   :closed (atom false)
   :last-used (atom (concurrent/monotonic-ms))
   :auth-headers (:auth-headers opts)
   :on-401 (:on-401 opts)
   :on-notification (:on-notification opts)
   :reconnect-fn (:reconnect-fn opts)
   :req-lock (Object.)})

;; ─── Messaging ────────────────────────────────────────────────────────────

(defn send-async!
  "Deliver a JSON-RPC message expecting no answer (POST, body unread)."
  [conn msg]
  (http-transport/send-async! conn msg))

(def ^:private endpoint-wait-ms
  "How long a request waits for the stream's `endpoint` event before
   failing — the event normally arrives within a round trip."
  5000)

(defn- endpoint!
  "The POST endpoint for an SSE conn — waits up to endpoint-wait-ms for
   the stream's `endpoint` event (the reader thread delivers it
   asynchronously); an endpoint already resolved by an earlier stream
   returns immediately."
  [conn]
  (or @(:endpoint-atom conn)
      (do
        (when-let [ready (some-> (:endpoint-ready conn) deref)]
          (deref ready endpoint-wait-ms nil))
        (or @(:endpoint-atom conn)
            (throw (protocol/mcp-error "MCP connect failed: no SSE endpoint received"
                                       {:transport :sse}))))))

(defn request!
  "POST a request and wait for its id-matched message on the stream. A
   dropped stream reopens once and re-establishes the session. OPTS is
   the transport-neutral request options map — the legacy SSE binding
   consumes none of it (:http-headers is streamable-HTTP
   only)."
  [conn method params timeout-ms on-notification _opts]
  (transport/touch! conn)
  (let [id (swap! (:id-counter conn) inc)]
    (loop [attempts 0]
      (let [ch @(:ch conn)]
        (when (nil? ch)
          (throw (protocol/mcp-error "MCP connect failed: SSE stream not open"
                                     {:transport :sse})))
        (let [endpoint (endpoint! conn)
              body (json/generate-string {:jsonrpc "2.0" :id id :method method :params params})
              response (http-transport/http-post! conn endpoint
                                                  (http-transport/http-request-headers conn)
                                                  body timeout-ms)
              parsed (http-transport/parse-http-response conn response id on-notification)]
          (if (and (map? parsed) (contains? parsed :id) (:error parsed))
            (throw (protocol/mcp-error (str "MCP error " (:code (:error parsed)) ": "
                                            (:message (:error parsed)))
                                       {:code (:code (:error parsed))
                                        :message (:message (:error parsed))}))
            (let [wait-result (if (and (map? parsed) (contains? parsed :id))
                                parsed
                                (transport/wait-for-response conn ch id method timeout-ms
                                                             on-notification send-async!))]
              (if (= transport/eof-marker wait-result)
              ;; Stream dropped — reopen + re-initialize (bounded retry).
                (if (< attempts 1)
                  (do (reset! (:stream-open conn) false)
                      (open-stream! conn)
                      (when-let [reconnect (:reconnect-fn conn)] (reconnect conn))
                      (recur (inc attempts)))
                  (throw (protocol/mcp-error
                          (str "MCP connect failed: SSE stream dropped while waiting for "
                               method)
                          {:transport :sse})))
                (if (:error wait-result)
                  (throw (protocol/mcp-error (str "MCP error " (:code (:error wait-result)) ": "
                                                  (:message (:error wait-result)))
                                             {:code (:code (:error wait-result))
                                              :message (:message (:error wait-result))}))
                  (:result wait-result))))))))))

;; ─── Teardown ─────────────────────────────────────────────────────────────

(defn close!
  "Mark the conn closed and abort the active stream (releases the blocked
   reader + reaps the transport)."
  [conn]
  (reset! (:closed conn) true)
  (reset! (:stream-open conn) false)
  (when-let [r @(:response conn)]
    (try (http/close! r) (catch Exception _ nil)))
  nil)

(defn alive?
  "True while the stream is open and the conn has not been closed."
  [conn]
  (boolean (and @(:stream-open conn) (not @(:closed conn)))))
