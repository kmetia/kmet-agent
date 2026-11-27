(ns kmet.mcp.transport
  "The MCP wire constants/pure protocol helpers and the connection contract
   shared by the MCP transports.

   The constants and pure helpers (the revisions this client speaks, the
   client identity, error construction and result formatting) are used by
   the transports (kmet.mcp.transport.{stdio,http}) and the client
   (kmet.mcp.client). No I/O.

   A conn is a plain map. Every transport provides the same operations:

     (request! conn method params timeout-ms on-notification opts)
                                   send a request (the transport allocates
                                   the JSON-RPC id), wait for its result.
                                   OPTS is a transport-neutral options map:
                                   :http-headers carries per-request
                                   custom headers (a tools/call's
                                   Mcp-Param-*, SEP-2243) and is consumed
                                   only by :streamable-http — stdio and
                                   the legacy SSE binding ignore it;
                                   :signal is a cancel signal (a
                                   non-blocking derefable) polled while the
                                   request waits
     (listen! conn filter on-frame)  2026-07-28 subscriptions/listen: open
                                   the conn's long-lived subscription and
                                   return {:stop! f :ended promise} (the
                                   stream ends via STOP! or the server)
     (send-async! conn msg)        deliver a message expecting no answer
                                   (notification, or a reply to a
                                   server->client request)
     (close! conn ...)             kill/abort/DELETE the transport
     (alive? conn)                 transport still usable

   Common keys:
     :transport        :stdio | :streamable-http | :sse
     :id-counter       atom of the next JSON-RPC id
     :last-used        atom of the last activity monotonic-ms (idle reaper —
                       compare against concurrent/monotonic-ms, never the
                       wall clock)
     :req-lock         monitor serializing dispatch per conn — the legacy
                       SSE transport matches responses on one shared
                       channel, so two waiters would consume each other's
                       responses; stdio correlates per request in jsonrpc
                       but routes progress through one conn-level
                       callback, so serializing keeps it unambiguous
                       (streamable-HTTP answers each request on its own
                       response body and stays concurrent)
     :on-notification  (fn [conn msg]) for server notifications
     :listen           the live subscription's state (an atom holding
                       {:id :subscription-id :on-frame :stop!}, nil when
                       none is open) — subscriptions keep the conn busy,
                       so the idle reaper must skip a listening conn
     :conn-ref         atom holding the conn as the client sees it after
                       derived keys (:capabilities) are attached — the
                       transports' internal callbacks (stdio's
                       notification handler) capture the pre-assoc map,
                       so the client repoints this atom; the extension's
                       list_changed handler needs those keys
     :auth-headers     (fn [] -> headers map) — HTTP transports
     :on-401           (fn [response] -> fresh headers) — HTTP transports

   Transport keys are documented beside each conn constructor in
   kmet.mcp.transport.{stdio,http}; the legacy HTTP+SSE binding lives
   in kmet.mcp.transport.http.

   The legacy-SSE helpers here are header lookup, last-used bookkeeping,
   and the channel wait loop that matches a response id while dispatching
   everything else. Calling the transport-specific send-async! is passed
   in, so this namespace never requires a transport."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.json :as json]))

(def default-request-timeout-ms
  "Per-request timeout when the caller passes none."
  120000)

(def initialize-timeout-ms
  "Timeout for the initialize handshake (60s — servers may be cold-started)."
  60000)

(def list-page-timeout-ms
  "Timeout for each catalog list page (tools/prompts/resources/templates)."
  30000)

(def probe-timeout-ms
  "Timeout for the 2026-07-28 era probe (server/discover before any
   handshake). Shorter than initialize-timeout-ms: a legacy server that
   answers unknown methods resolves immediately, and a silent one stalls
   seconds instead of a minute before the legacy fallback."
  10000)

(def protocol-version
  "The revision this client requests in initialize."
  "2025-11-25")

(def supported-protocol-versions
  "Legacy (handshake) revisions this client speaks. A server that answers
   `initialize` with a version outside this list is rejected — servers on
   older SDKs answer with their own latest revision, so all handshake-based
   revisions stay usable. The stateless 2026-07-28 revision and later are
   negotiated through modern-supported-versions instead."
  ["2025-11-25" "2025-06-18" "2025-03-26" "2024-11-05"])

(def client-info
  "The clientInfo sent in the initialize handshake."
  {:name "kmet-mcp-adapter" :version "0.1.0"})

(def modern-protocol-version
  "The 2026-07-28 revision: stateless (no initialize handshake), with
   per-request _meta and a resultType on every result."
  "2026-07-28")

(def modern-supported-versions
  "Modern revisions this client can send, newest first."
  [modern-protocol-version])

(def meta-ns
  "The namespace reserved for protocol-reserved _meta keys."
  "io.modelcontextprotocol")

(defn meta-key
  "A protocol-reserved _meta key: (meta-key \"clientInfo\") →
   :io.modelcontextprotocol/clientInfo."
  [k]
  (keyword meta-ns (name k)))

(def listen-method
  "The 2026-07-28 replacement for the broadcast list_changed
   notifications: one long-lived subscriptions/listen request per conn."
  "subscriptions/listen")

(def ack-method
  "The first frame of a subscription stream: the server's acknowledgment
   names the subscription id and the filter subset it honors."
  "notifications/subscriptions/acknowledged")

(defn subscription-id
  "The subscription id a server stamps on a subscription's notifications
   (_meta.io.modelcontextprotocol/subscriptionId), or nil for an unscoped
   message."
  [msg]
  (get-in msg [:params :_meta (meta-key "subscriptionId")]))

(defn modern-request-meta
  "The _meta map a 2026-07-28 request or notification carries inside its
   params: VERSION (the negotiated revision), the client identity, and
   empty client capabilities — kmet implements no elicitation, sampling or
   roots, so a conforming server never sends an inputRequests entry."
  [version]
  {(meta-key "protocolVersion") version
   (meta-key "clientInfo") client-info
   (meta-key "clientCapabilities") {}})

(defn modern-conn?
  "True when CONN's :era atom records the 2026-07-28 era (the atom and its
   shape are documented in kmet.mcp.client); a conn without one — a
   bare transport map, a test fake — is legacy."
  [conn]
  (boolean (when-let [era (:era conn)]
             (= :modern (:era @era)))))

(defn era-version
  "The revision recorded on CONN's :era atom, or nil while the era is
   still unknown."
  [conn]
  (:version (some-> (:era conn) deref)))

(defn conn-meta
  "The _meta map to merge into a modern CONN's request/notification
   params: the revision recorded on its :era atom, else the base modern
   revision (before negotiation has recorded one)."
  [conn]
  (modern-request-meta (or (era-version conn) modern-protocol-version)))

(def modern-error-codes
  "JSON-RPC codes the 2026-07-28 revision defines for era negotiation:
   -32020 header mismatch, -32021 missing client capability, -32022
   unsupported protocol version."
  #{-32020 -32021 -32022})

(defn modern-error?
  "True when EX-DATA carries an era-negotiation code."
  [data]
  (contains? modern-error-codes (:code data)))

(defn negotiate-version
  "The era and revision to use with a server advertising the revisions in
   SUPPORTED: the newest modern revision in common, else the newest legacy
   revision, else nil (callers decide whether an overlap of only unknown
   revisions is an error or a legacy fallback). The server's ordering is
   ignored."
  [supported]
  (let [supported (set supported)]
    (or (when-let [v (some #(when (supported %) %) modern-supported-versions)]
          {:era :modern :version v})
        (when-let [v (some #(when (supported %) %) supported-protocol-versions)]
          {:era :legacy :version v}))))

(defn result-type
  "The 2026-07-28 resultType of RESULT: an absent value is the default
   \"complete\"."
  [result]
  (or (:resultType result) "complete"))

(def ^:private progress-counter (atom 0))

(defn progress-token
  "A correlation id for the _meta.progressToken of one request.
   Progress is opt-in per request — a server only emits
   notifications/progress for requests carrying the token, so every call
   the client wants streamed must send one (spec, progress). Monotonic:
   the id only has to be unique within the session."
  []
  (str "kmet-" (swap! progress-counter inc)))

(defn mcp-error
  "ex-info with the §7.7 message patterns (extensions match on :mcp-error,
   the data map carries :status/:code/:timeout-ms where applicable)."
  ([message] (ex-info message {:type :mcp-error}))
  ([message data] (ex-info message (assoc data :type :mcp-error))))

(defn cancelled-error
  "ex-info for a request stopped by the caller's cancel signal. The
   transports carry :cancelled true so callers can distinguish it from a
   timeout or a transport failure."
  [method]
  (mcp-error (str "MCP request cancelled: " method)
             {:cancelled true :method method}))

;; ─── Result formatting (§7.6) ─────────────────────────────────────────────

(defn- base64-byte-size
  "Decoded byte length of a padded base64 string, or nil when S is not a
   well-padded base64 string (a malformed payload is summarized by its
   character count instead)."
  [s]
  (when (string? s)
    (let [n (count s)
          pad (cond (str/ends-with? s "==") 2
                    (str/ends-with? s "=") 1
                    :else 0)]
      (when (and (pos? n) (zero? (mod n 4)))
        (- (/ (* 3 n) 4) pad)))))

(defn- byte-label
  "The '<n> bytes' label of a base64 payload — the decoded size when the
   payload is well-formed, else its character count."
  [data]
  (str (or (base64-byte-size data) (count (str data))) " bytes"))

(defn- resource-block
  "The text of an embedded resource content block: the resource's text,
   else a summary of its blob; nil when the block carries neither."
  [b]
  (let [r (:resource b)
        uri (or (:uri r) "?")]
    (cond
      (string? (:text r)) (:text r)
      (some? (:blob r)) (str "[resource " uri ": " (or (:mimeType r) "?") ", "
                             (byte-label (:blob r)) " — not rendered]")
      :else nil)))

(defn format-result
  "Flatten a tools/call result into text (§7.6): text/error content blocks
   joined with newlines; image/audio → '[<kind>: <mimeType>, <n> bytes —
   not rendered]' (a base64 payload reports its decoded size); an embedded
   resource → its text, else a blob summary; a resource link → its name or
   uri; empty content with structuredContent → pretty JSON; fallback
   '(no text content)'. Returns {:text str :is-error bool}."
  [result]
  (let [blocks (or (:content result) [])
        texts (keep (fn [b]
                      (case (:type b)
                        ("text" "error") (:text b)
                        "image" (str "[image: " (or (:mimeType b) "?") ", "
                                     (byte-label (:data b)) " — not rendered]")
                        "audio" (str "[audio: " (or (:mimeType b) "?") ", "
                                     (byte-label (:data b)) " — not rendered]")
                        "resource" (resource-block b)
                        "resource_link" (str "[resource link: "
                                             (or (:name b) (:uri b) "?") "]")
                        nil))
                    blocks)
        text (cond
               (seq texts) (str/join "\n" texts)
               (seq (:structuredContent result))
               (json/generate-string (:structuredContent result) {:pretty true})
               :else "(no text content)")]
    {:text text :is-error (true? (:isError result))}))

;; ─── Connection contract ──────────────────────────────────────────────────

(def eof-marker
  "Internal value a channel transport delivers when it dies while a
   request is in flight; never returned to callers."
  ::eof)

(defn header-value
  "Case-insensitive header lookup (HTTP response headers, string or
   keyword keys)."
  [headers header-name]
  (let [norm (fn [x] (str/lower-case (if (keyword? x) (name x) (str x))))]
    (some (fn [[k v]]
            (when (= (norm k) (norm header-name)) v))
          headers)))

(defn last-used
  "The last activity monotonic-ms for a connection — the idle reaper
   disconnects servers whose conn has been idle past the configured
   :idle-timeout. Compare it against concurrent/monotonic-ms."
  [conn]
  (or (when-let [lu (:last-used conn)] @lu) 0))

(defn touch!
  "Mark the connection active now."
  [conn]
  (when-let [lu (:last-used conn)] (reset! lu (concurrent/monotonic-ms)))
  nil)

;; ─── Subscriptions (2026-07-28) ───────────────────────────────────────────

(defn route-listen-frame!
  "Feed one incoming notification to the conn's live subscription.
   LISTEN-ATOM is the transport's :listen slot — it holds
   {:id :subscription-id :on-frame :stop!} while a subscription is open
   and nil otherwise. A frame stamped with a subscription id (the
   acknowledgment that establishes it, or a notification carrying it)
   reaches the subscription's :on-frame observer; a frame stamped with a
   subscription id other than the live one's is a stale subscription's
   and is dropped. Returns true when MSG should still be delivered to the
   conn-level handler, false when the caller must drop it.

   One subscription per conn (the client opens at most one), so a stamped
   frame either belongs to it or is stale; an unstamped notification
   always belongs to the ordinary path."
  [listen-atom msg]
  (let [listen (some-> listen-atom deref)]
    (if (nil? listen)
      true
      (let [sid (subscription-id msg)]
        (cond
          (nil? sid) true

          ;; ours: the acknowledgment (which names the id), a notification
          ;; stamped with it, or anything stamped before the ack arrived
          ;; (a server streaming ahead of its own acknowledgment)
          (or (nil? (:subscription-id listen))
              (= sid (:subscription-id listen)))
          (do
            (when (nil? (:subscription-id listen))
              (swap! listen-atom assoc :subscription-id sid))
            (when-let [f (:on-frame listen)]
              (try (f msg) (catch Exception _ nil)))
            true)

          :else false)))))

;; ─── Server->client dispatch (channel transports) ─────────────────────────

(defn- reply!
  "Answer a server->client request. `ping` is answered with an empty
   result — a receiver MUST respond promptly. The client features we do
   not implement (sampling/createMessage, elicitation/create,
   roots/list) are refused with -32601 Method not found: dropping the
   message would leave the server waiting out its own timeout and
   logging a protocol error. We declare no such capabilities, so a
   conforming server never sends them."
  [conn msg send-async!]
  (let [id (:id msg)
        method (:method msg)]
    (send-async! conn
                 (if (= "ping" method)
                   {:jsonrpc "2.0" :id id :result {}}
                   {:jsonrpc "2.0" :id id
                    :error {:code -32601
                            :message (str "Method not found: " method)}})))
  nil)

(defn cancel!
  "Send notifications/cancelled for a request we stop waiting for. A
   dropped connection is not a cancellation (transports), so a tool call
   that timed out would otherwise keep running on the server. Best-effort:
   the HTTP transports deliver it from a background thread so a server
   busy with the abandoned call cannot delay the timeout error itself.
   Nothing is sent for a modern streamable-HTTP conn: closing the response
   stream is the cancellation signal there, and the 2026-07-28 revision
   defines no client→server notification over HTTP (stdio keeps it)."
  [conn id method reason send-async!]
  (when-not (and (= :streamable-http (:transport conn))
                 (modern-conn? conn))
    (let [msg {:jsonrpc "2.0"
               :method "notifications/cancelled"
               :params {:requestId id
                        :reason (str "kmet: " method " — " reason)}}]
      (if (= :stdio (:transport conn))
        (send-async! conn msg)
        (concurrent/spawn (fn [] (send-async! conn msg))))))
  nil)

(defn dispatch-server-message!
  "Route a message that is not the response being awaited: a
   server->client request (an :id plus a :method) gets an answer, a
   notification goes to the in-flight call's ON-NOTIFICATION hook
   (notifications/progress) and to the conn-level :on-notification
   handler (fn [conn msg] — list_changed and the like). Anything else is
   dropped."
  [conn msg on-notification send-async!]
  (cond
    (and (contains? msg :id) (contains? msg :method))
    (reply! conn msg send-async!)

    (:method msg)
    (do (when (and on-notification
                   (= "notifications/progress" (:method msg)))
          (on-notification msg))
        (when-let [f (:on-notification conn)]
          (try (f conn msg) (catch Exception _ nil))))

    :else nil))

(def ^:private signal-poll-ms
  "How often a signal-aware wait re-checks the cancel signal."
  100)

(defn wait-for-response
  "Wait on CH for the message with :id = ID. Anything else that arrives
   (notifications, server->client requests, stale responses) is
   dispatched and the wait continues. Returns the response map (with
   :result or :error), or ::eof when the channel closed (transport
   death) before the response arrived. The timeout is an overall deadline
   — notifications do not extend it; on expiry the request is cancelled.
   SIGNAL, when present, is a cancel signal polled in short slices; on
   cancellation the request is cancelled and a :cancelled MCP error is
   thrown."
  [conn ch id method timeout-ms on-notification send-async! & [signal]]
  (let [deadline (+ (concurrent/monotonic-ms) timeout-ms)]
    (loop []
      (if (and signal @signal)
        (do (cancel! conn id method "cancelled" send-async!)
            (throw (cancelled-error method)))
        (let [remaining (- deadline (concurrent/monotonic-ms))]
          (if (<= remaining 0)
            (do (cancel! conn id method "timed out" send-async!)
                (throw (mcp-error
                        (str "MCP request timed out after " timeout-ms "ms: " method)
                        {:timeout-ms timeout-ms :method method})))
            (let [wait-ms (if signal (min remaining signal-poll-ms) remaining)
                  timeout-ch (async/timeout (max 1 wait-ms))
                  [value port] (async/alts!! [ch timeout-ch])]
              (cond
                (identical? port timeout-ch)
                (if (and signal (< (concurrent/monotonic-ms) deadline))
                  (recur)
                  (do (cancel! conn id method "timed out" send-async!)
                      (throw (mcp-error
                              (str "MCP request timed out after " timeout-ms "ms: " method)
                              {:timeout-ms timeout-ms :method method}))))

                (or (nil? value) (= eof-marker value)) eof-marker

                :else
                (let [msg value]
                  (cond
                    (not (map? msg)) (recur)
                    (= id (:id msg))
                    (cond
                      (contains? msg :error)
                      (throw (mcp-error (str "MCP error " (:code (:error msg)) ": "
                                             (:message (:error msg)))
                                        {:code (:code (:error msg))
                                         :message (:message (:error msg))}))
                      :else msg)

                    ;; not our response: a server->client request or notification
                    (or (contains? msg :id) (:method msg))
                    (do (dispatch-server-message! conn msg on-notification send-async!)
                        (recur))

                    :else (recur)))))))))))
