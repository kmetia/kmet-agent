(ns kmet.libs.mcp.transport.stdio
  "MCP stdio transport (§7.2) on top of kmet.libs.jsonrpc. The shared
   JSON-RPC session owns the wire — line-delimited framing, id allocation,
   pending responses, server-request replies and the stderr tail — and this
   namespace adds only MCP policy and process management:

     - spawn from the server definition (:command/:args/:env/:cwd)
     - `ping` answered with an empty result; other server->client requests
       refused with -32601 (jsonrpc maps a nil handler return to it)
     - per-request progress routing to the in-flight call's callback
     - process-tree kill + host pid tracking on close
     - `subscriptions/listen` on its own thread with no deadline (the
       request's result arrives only at graceful close) and no request
       lock, demuxed from ordinary notifications by subscription id

   The conn is the jsonrpc conn map plus :transport :stdio, :req-lock,
   :progress-callback and :listen (the live subscription the notification
   demux consults); the common contract keys are documented in
   kmet.libs.mcp.transport.

   Notification handlers run on the jsonrpc reader thread (the
   streamable-HTTP transport delivers them on the response thread, so
   handlers must not block — the extension's list_changed resync spawns)."
  (:require [clojure.string :as str]
            [kmet.libs.jsonrpc :as jrpc]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport :as transport]
            [kmet.libs.process :as process]))

;; ─── MCP policy ───────────────────────────────────────────────────────────

(defn- ping-handler
  "Server->client requests: `ping` answers with an empty result — a
   receiver MUST respond promptly. The client features we do not
   implement (sampling/createMessage, elicitation/create, roots/list) are
   refused with -32601: jsonrpc turns a nil return into Method not found,
   so a conforming server never sees a silent drop. We declare no such
   capabilities."
  []
  (fn [method _params]
    (when (= "ping" method) {})))

(defn- notification-handler
  "Route one incoming notification: notifications/progress goes to the
   in-flight request's callback (the client serializes stdio requests, so
   at most one is live), a frame of the conn's live subscription follows
   the subscription demux (a stale subscription's frame is dropped), and
   everything else reaches the conn-level handler."
  [conn-atom callback listen-atom notify]
  (fn [msg]
    (when (and (= "notifications/progress" (:method msg)) @callback)
      (@callback msg))
    (when (and notify (transport/route-listen-frame! listen-atom msg))
      (try (notify @conn-atom msg) (catch Exception _ nil)))))

(defn- dead-message
  "Diagnostic message for a dead stdio process (stderr tail appended)."
  [conn method]
  (str "MCP connect failed: process exited"
       (when method (str " while waiting for " method))
       (let [tail (str/join " — " (jrpc/stderr-tail conn))]
         (when (seq tail) (str " (stderr: " tail ")")))))

(defn- translate-exception
  "Map a jsonrpc failure into the MCP error contract (§7.7). Anything
   else is returned unchanged for rethrow."
  [conn e method timeout-ms]
  (case (:type (ex-data e))
    ::jrpc/timeout
    (do
      ;; the request is abandoned — tell the server to stop working (this
      ;; path bypasses client/notify!, so a modern conn merges the era
      ;; _meta here itself)
      (try (jrpc/notify! conn "notifications/cancelled"
                         (cond-> {:requestId (:id (ex-data e))
                                  :reason (str "kmet: " method " — timed out")}
                           (protocol/modern-conn? conn)
                           (update :_meta merge (protocol/conn-meta conn))))
           (catch Exception _ nil))
      (protocol/mcp-error (str "MCP request timed out after " timeout-ms "ms: " method)
                          {:timeout-ms timeout-ms :method method}))

    ::jrpc/request-error
    (let [{:keys [code message]} (:error (ex-data e))]
      (protocol/mcp-error (str "MCP error " code ": " message)
                          {:code code :message message}))

    ::jrpc/transport-dead
    (protocol/mcp-error (dead-message conn method) {:transport :stdio})

    e))

;; ─── Connect / request / close ────────────────────────────────────────────

(defn connect!
  "Spawn a stdio server over a jsonrpc session. Returns the MCP conn."
  [definition opts]
  (let [conn-atom (atom nil)
        callback (atom nil)
        notify (:on-notification opts)
        ;; the conn's live subscription (transport/route-listen-frame!);
        ;; created here so the notification handler, which captures this
        ;; map, sees the same atom the client's listen! fills in
        listen (atom nil)
        pid (atom nil)
        kill! (fn []
                (when-let [p @pid]
                  (try (process/kill-process-tree! p)
                       (catch Exception _ nil))
                  (process/untrack-pid! p)
                  (reset! pid nil)))
        conn (jrpc/connect-stdio
              {:command (:command definition)
               :args (:args definition)
               :env (:env definition)
               :cwd (:cwd definition)
               :framing :line-delimited
               :kill-fn kill!
               :on-request (ping-handler)
               :on-notification (notification-handler conn-atom callback listen notify)})]
    (reset! conn-atom conn)
    (when-let [p (:pid conn)]
      (reset! pid p)
      (process/track-pid! p))
    (assoc conn
           :transport :stdio
           :listen listen
           :req-lock (Object.)
           :progress-callback callback
           :conn-ref conn-atom)))

(defn listen!
  "Open the conn's 2026-07-28 subscription (spec, subscriptions/listen).
   The request's result arrives only when the stream closes, so it runs on
   its own daemon thread with no deadline (jrpc/no-deadline) and never
   takes the per-conn request lock a tool call needs. FILTER is the
   :notifications filter object; every frame of the subscription (the
   acknowledgment first) is routed to ON-FRAME and to the conn-level
   handler — see transport/route-listen-frame!.

   Returns {:stop! f :ended promise}. stop! sends
   notifications/cancelled — the stdio cancellation mechanism — and
   :ended delivers :stopped (a caller stop), :ended (the server closed
   the stream) or {:error e}."
  [conn filter on-frame]
  (let [id (swap! (:id-counter conn) inc)
        listen-atom (:listen conn)
        stopped (atom false)
        ended (promise)
        params (cond-> {:notifications filter}
                 (protocol/modern-conn? conn)
                 (update :_meta merge (protocol/conn-meta conn)))
        stop! (fn []
                (reset! stopped true)
                (try
                  (jrpc/notify! conn "notifications/cancelled"
                                (cond-> {:requestId id
                                         :reason "kmet: subscription closed"}
                                  (protocol/modern-conn? conn)
                                  (update :_meta merge (protocol/conn-meta conn))))
                  (catch Exception _ nil))
                (deliver ended :stopped))
        t (Thread. (fn []
                     (try
                       (if @stopped
                         ;; stopped before the request went out: there is
                         ;; nothing to cancel, so don't subscribe at all
                         (deliver ended :stopped)
                         (do
                           (jrpc/request! conn protocol/listen-method params
                                          {:timeout-ms jrpc/no-deadline :id id})
                           (deliver ended (if @stopped :stopped :ended))))
                       (catch Exception e
                         (deliver ended (if @stopped :stopped {:error e})))
                       (finally
                         ;; clear only our own entry: a re-listen may have
                         ;; installed a fresh one already
                         (swap! listen-atom
                                (fn [l] (when-not (= id (:id l)) l)))))))]
    (reset! listen-atom {:id id :subscription-id nil :on-frame on-frame
                         :stop! stop!})
    ;; the parked request thread is released by close! (its pending
    ;; correlation fails with ::transport-dead)
    (.setDaemon t true)
    (.start t)
    {:stop! stop! :ended ended}))

(defn request!
  "Send one request and return its :result. The jsonrpc session owns the
   id and the response correlation; the timeout is translated into the MCP
   error contract (and the abandoned request cancelled). OPTS is the
   transport-neutral request options map — stdio consumes none of it
   (:http-headers is streamable-HTTP only)."
  [conn method params timeout-ms on-notification _opts]
  (transport/touch! conn)
  (reset! (:progress-callback conn) on-notification)
  (try
    (jrpc/request! conn method params {:timeout-ms timeout-ms})
    (catch Exception e
      (throw (translate-exception conn e method timeout-ms)))
    (finally
      (reset! (:progress-callback conn) nil))))

(defn send-async!
  "Deliver a JSON-RPC message that expects no answer (a notification)."
  [conn msg]
  (jrpc/notify! conn (:method msg) (:params msg)))

(defn close!
  "Close the jsonrpc session; its kill-fn destroys the process tree and
   untracks the pid."
  [conn]
  (jrpc/close! conn)
  nil)

(defn alive?
  "True while the child process runs and the session is not closed."
  [conn]
  (jrpc/alive? conn))
