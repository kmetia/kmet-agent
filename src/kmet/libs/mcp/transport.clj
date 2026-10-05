(ns kmet.libs.mcp.transport
  "The connection contract shared by the MCP transports.

   A conn is a plain map. Every transport provides the same operations:

     (request! conn method params timeout-ms on-notification opts)
                                   send a request (the transport allocates
                                   the JSON-RPC id), wait for its result.
                                   OPTS is a transport-neutral options map:
                                   :http-headers carries per-request
                                   custom headers (a tools/call's
                                   Mcp-Param-*, SEP-2243) and is consumed
                                   only by :streamable-http — stdio and
                                   the frozen SSE binding ignore it
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
   kmet.libs.mcp.transport.{stdio,http,sse}.

   This namespace holds what the legacy SSE transport needs on top of
   the transport-specific send-async!: header lookup, last-used
   bookkeeping, and the channel wait loop that matches a response id
   while dispatching everything else. Calling the transport-specific
   send-async! is passed in, so this namespace never requires a
   transport."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.mcp.protocol :as protocol]))

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
      (let [sid (protocol/subscription-id msg)]
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
                 (protocol/modern-conn? conn))
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

(defn wait-for-response
  "Wait on CH for the message with :id = ID. Anything else that arrives
   (notifications, server->client requests, stale responses) is
   dispatched and the wait continues. Returns the response map (with
   :result or :error), or ::eof when the channel closed (transport
   death) before the response arrived. The timeout is an overall deadline
   — notifications do not extend it; on expiry the request is cancelled."
  [conn ch id method timeout-ms on-notification send-async!]
  (let [deadline (+ (concurrent/monotonic-ms) timeout-ms)]
    (loop []
      (let [remaining (- deadline (concurrent/monotonic-ms))
            timeout-ch (async/timeout (max 1 remaining))
            [value port] (async/alts!! [ch timeout-ch])]
        (cond
          (identical? port timeout-ch)
          (do (cancel! conn id method "timed out" send-async!)
              (throw (protocol/mcp-error (str "MCP request timed out after " timeout-ms "ms: " method)
                                         {:timeout-ms timeout-ms :method method})))

          (or (nil? value) (= eof-marker value)) eof-marker

          :else
          (let [msg value]
            (cond
              (not (map? msg)) (recur)
              (= id (:id msg))
              (cond
                (contains? msg :error)
                (throw (protocol/mcp-error (str "MCP error " (:code (:error msg)) ": "
                                                (:message (:error msg)))
                                           {:code (:code (:error msg))
                                            :message (:message (:error msg))}))
                :else msg)

              ;; not our response: a server->client request or notification
              (or (contains? msg :id) (:method msg))
              (do (dispatch-server-message! conn msg on-notification send-async!)
                  (recur))

              :else (recur))))))))
