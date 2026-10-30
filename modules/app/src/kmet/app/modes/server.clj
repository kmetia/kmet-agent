(ns kmet.app.modes.server
  "Server mode as a row: the rpc protocol over a socket, where **a connection is
   an agent**.

   Accepting a socket creates an agent, and every record that connection reads
   carries its `:agent-id`, so two clients cannot be confused about whose answer
   they hold. Closing the socket disposes the agent it was. The command language
   is `kmet.app.modes.rpc`'s — the same records, the same answers, the same named
   refusals — with one state per connection instead of one stream per process.

   **A connection authenticates before it gets an agent.** The server holds one
   bearer token per process (phase 1 is single-principal: many clients, one
   identity): `--token` names it, and when the flag is absent one is generated,
   then announced — with the port — in the `:listening` record on the server's
   own stdout, the supervisor's channel. The first record a client sends is
   `{:type :auth :token \"…\"}`; only its answer creates the agent. Everything
   before that is a refusal and a close, and a connection that never
   authenticates is reaped like any other idle one.

   **A connection brings its world.** The auth record may also carry the agent's
   `:cwd` and `:config` (settings over it) plus the run's own overrides
   (`:model`, `:provider`, `:thinking`, …), so two connections of one server run
   in two worlds. That is the config half of landing 9: the config a connection
   brings belongs to the agent that connection created, while the credentials
   stay the process's — one principal by decision, not an omission.

   **One waiter, many readers — and one writer per wire.** Each connection has a
   reader thread parked in `readLine`, and that thread may not touch the kernel:
   it hands the line to the server's `kmet.libs.concurrent` queue and goes back
   to reading. One server thread takes from that queue, so it is the only thread
   that creates, serves or disposes an agent. The queue's single consumer is the
   contract, not a courtesy — two takers would race its handed-out slot — and
   the cost of the design is one parked thread per connection, which is what a
   client that stalls (a half-written line, a frozen peer) is allowed to hold
   and nothing else.

   The other direction is bounded instead. A run's records are written from the
   run's own thread, so `send!` only offers them to a per-connection lane and a
   single writer thread puts them on the socket: a client that stops reading
   costs its own buffer, never the turn. A full lane drops its **oldest** record
   and counts it (`kmet.libs.concurrent/bounded-queue`), and the writer announces
   what it lost with `{:type :stream-gap :dropped n}` before the next record —
   dropping the newest would corrupt a message silently, and a visible gap is
   what tells the client to resync with `:get-state`. A drop is announced only
   by a record the lane keeps afterwards, so an ask that went unanswered is
   asked again: the reply may be the record the lane dropped, and a run settling
   into a one-deep lane can lose the last records without an announcement.

   **An event belongs to the agent that emitted it.** The listener registers on
   the agent's own ctx, so the kernel's subtree scoping routes a run's events to
   its connection: the loop emits from the agent's ctx, and a second agent's
   events never reach the first wire. This is what per-agent event scoping means
   in practice, and it is why the registry hands out `:ctx` on the handle.

   **A server is supervised.** It serves until its stdin closes — how a parent
   process stops one — and then disposes the connections it still holds and
   exits 0. `--port 0` (the default) asks the OS for a free port, which the mode
   announces as a `:listening` record before it accepts anything; a test reads
   that record, a client reads that record, and neither has to guess. `--socket
   <path>` serves the same protocol on a unix-domain socket where the host has
   one (`bb`, the JVM) and falls back to TCP loopback with a warning where it
   does not (Jolt).

   `--max-sessions` caps how many connections one server serves (0 is no cap),
   `--queue-depth` is each wire's lane capacity, and `--idle-timeout <seconds>`
   disposes a connection that has sent nothing for that long while its agent is
   idle (0 disables the reaper). The reaper only *queues* its decision; the
   server thread closes the connection, so no second thread ever touches the
   kernel.

   What this row does not have yet is a per-connection UI port: a run's extension
   UI calls still answer the host's defaults (approvals are the wire's, 6c), and
   a per-connection port needs landing 8's per-agent surface — the host's
   front-end binding is one process atom."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [kmet.app.config :as cfg]
            [kmet.app.wire :as wire]
            [kmet.app.events :as events]
            [kmet.app.invocation :as invocation]
            [kmet.app.modes.out :as out]
            [kmet.app.modes.rpc :as rpc]
            [kmet.app.permissions :as permissions]
            [kmet.app.session.runtime :as runtime]
            [kmet.libs.concurrent :as concurrent]
            [kmet.srce.core :as srce]))

;; ── limits ────────────────────────────────────────────────────────────────

(def default-max-sessions
  "How many connections one server serves when `--max-sessions` names none.
   A cap rather than unbounded growth: every connection owns an agent, and an
   agent is a child ctx, a session and a loop."
  8)

(def default-queue-depth
  "Each wire's lane capacity when `--queue-depth` names none: about 256 records
   of headroom for a client that reads slowly, and a gap before a stalled one
   costs memory."
  256)

(def default-idle-timeout
  "Seconds a connection may sit silent — authenticated or not — before the
   reaper releases it, when `--idle-timeout` names none."
  1800)

(defn- limits
  "CONFIG's transport limits, normalized: the connection cap (`0` is no cap),
   each wire's lane capacity, and the idle timeout in milliseconds (`0` disables
   the reaper)."
  [{:keys [max-sessions queue-depth idle-timeout]}]
  {:cap (let [n (long (or max-sessions default-max-sessions))]
          (when (pos? n) n))
   :depth (max 1 (long (or queue-depth default-queue-depth)))
   :idle-ms (long (* 1000 (max 0 (long (or idle-timeout default-idle-timeout)))))})

;; ── the wire ──────────────────────────────────────────────────────────────

(defn- badged
  "RECORD carrying CONN's agent id, once it has one. Every record after
   `:ready` says whose it is: the server's other agents write to their own wires
   at the same time, and a log that collects both must tell them apart."
  [conn record]
  (if-let [id @(:agent-id conn)]
    (assoc record :agent-id id)
    record))

(defn- send!
  "RECORD on CONN's wire. The write is an **offer**, never a write: the lane is
   bounded and the writer thread is the only thread that touches the socket, so
   a run's thread can hand over a record for a client that stopped reading and
   go on with its turn. What the lane dropped is announced by the writer as a
   `:stream-gap` before the next record."
  [conn record]
  (when-not @(:dead? conn)
    (concurrent/offer! (:outq conn) (badged conn record)))
  nil)

(defn- write-record!
  "CONN's writer puts RECORD on the socket; false when the socket is gone. A
   failed write is not raised — the connection is dead and the server lives on —
   and the reader's close announces the release."
  [conn record]
  (try
    (out/record! (:out conn) record)
    true
    (catch Throwable _
      (reset! (:dead? conn) true)
      false)))

(defn- write-gap!
  "Announce the records the lane dropped since the previous take, as
   `:stream-gap`, so the client learns its stream has a hole instead of silently
   losing the middle of a message. True when there is nothing to announce or the
   announcement was written."
  [conn dropped]
  (or (zero? dropped)
      (write-record! conn (badged conn {:type :stream-gap :dropped dropped}))))

(defn- write-records!
  "The one thread that writes CONN's socket, taking from its lane until the lane
   closes. The socket closes when the lane is closed and drained — a refusal's
   last record therefore reaches a client that is still reading — and the loop
   ends early on a write failure."
  [conn]
  (loop []
    (let [{:keys [value dropped]} (concurrent/take-dropped! (:outq conn))]
      (if (= concurrent/closed value)
        (do (try ((:close! conn)) (catch Throwable _)) nil)
        (when (and (write-gap! conn dropped)
                   (write-record! conn value))
          (recur))))))

(defn- state-for
  "The command state `kmet.app.modes.rpc/handle-line` serves CONN through: the
   registry, the app scope, and the one handle this connection is. No
   `:command-ui`: a connection has no wire surface for a command's reply, so a
   slash command is refused by name rather than run with its answer dropped. No
   `:sessions` either — the log is read through the handle."
  [conn {:keys [agents scope]}]
  {:agents agents :scope scope :handle (:handle conn)})

;; ── dialogs ───────────────────────────────────────────────────────────────

(defn- settle-dialog!
  "Deliver VERDICT to the run waiting on dialog ID. True when a run was waiting:
   an id nobody holds is a client answering twice, or answering after its run
   went away, and it changed nothing."
  [server id verdict]
  (let [pending (get @(:pending server) id)]
    (swap! (:pending server) dissoc id)
    (when pending (deliver (:answer pending) verdict))
    (some? pending)))

(defn- answer-dialog!
  "ANSWER as a client's reply to a request the server made. A reply that reached
   no run is a protocol bug worth a line in the log: the client believes it
   answered something."
  [server answer]
  (or (settle-dialog! server (:id answer)
                      (select-keys (or (:data answer) answer) [:decision :reason :scope]))
      (srce/log! (:scope server) :warn "a dialog answer named no open request"
                 {:id (:id answer)}))
  nil)

(defn- deny-dialogs!
  "Answer every dialog CONN still owes with a deny: a client that leaves a
   question open must not leave the run that asked it parked forever."
  [server conn-id]
  (doseq [[id pending] @(:pending server)
          :when (= conn-id (:conn-id pending))]
    (settle-dialog! server id {:decision :deny
                               :reason "the client that was asked has gone away"}))
  nil)

(defn- ask-over-the-wire
  "Ask the client that owns CTX and wait for its answer. This runs on the run's
   own thread — the one thread that may wait for a human, exactly as
   `kmet.app.permissions` says an `:ask` does — and the gate imposes no deadline:
   an unanswered question blocks the call it is about, which is what a question
   means. A connection that closes answers its outstanding questions with a
   deny."
  [server ctx request]
  (if-let [conn (first (filter #(and (:handle %)
                                     (srce/inside? ctx (:ctx (:handle %))))
                               (vals @(:conns server))))]
    (let [id (str "req-" (random-uuid))
          answer (promise)
          ;; the run's cancel flag (an atom, `concurrent/or-signal`'s view): a
          ;; run that is aborted must not stay parked on a question nobody will
          ;; answer, so the wait watches it as well as the answer
          signal (:signal request)]
      (swap! (:pending server) assoc id {:conn-id (:id conn) :answer answer})
      (send! conn (cond-> {:type :request
                           :id id
                           :method :ask
                           :tool-name (:tool-name request)
                           :args (:args request)
                           :tool-call-id (:tool-call-id request)}
                    (:reason request) (assoc :reason (:reason request))))
      (let [verdict (loop []
                      (cond
                        (realized? answer) @answer
                        (and signal @signal) permissions/cancelled-answer
                        :else (do (Thread/sleep 20) (recur))))]
        (swap! (:pending server) dissoc id)
        verdict))
    ;; no connection owns this ctx — a run nobody can be asked about, which is
    ;; the headless deny, not a hang
    (permissions/no-approver ctx request)))

;; The router the receiving run installed: CTX to the client that may answer for
;; it. Process-wide because the `:approvals` row it serves is — `serve` installs
;; it for its run and puts back what was there when the run ends, the same
;; install/restore pair `kmet.app.front-end` uses for the extension surface.
(defonce ^:private ask-route (atom nil))

(defn ask!
  "The `:approvals` seam the server profile mounts: a run's `:ask` becomes a
   `:request` on the connection that owns its agent, and the client's
   `:response` is the answer. Falls back to the headless deny when no run is
   serving."
  [ctx request]
  (if-let [route @ask-route]
    (route ctx request)
    (permissions/no-approver ctx request)))

(defn- serve-approvals
  "The `:mode-server-approvals` row — what `--profile server` patches into the
   bundle's `:approvals` row. The bundle's default denies because nobody can be
   asked; on a server somebody can."
  [ctx _config]
  (srce/provide! ctx :approvals (permissions/approver ask!) {:in (:parent ctx)}))

(srce/defplugin approvals
  {:name :mode-server-approvals
   :provide #{:approvals}
   :apply serve-approvals})

;; ── transports ────────────────────────────────────────────────────────────

(defn- warn!
  "A line on stderr: the mode's own diagnostic. stdout carries protocol records
   only (`kmet.app.modes.out`)."
  [message]
  (binding [*out* *err*]
    (println "Warning:" message)
    (flush)))

(defn- tcp-transport
  "A listener on TCP loopback — phase 1's remote scope is one host, so the
   socket is bound to `127.0.0.1` and never to every interface. PORT 0 asks the
   OS for a free one, which the mode announces."
  [port]
  (let [socket (java.net.ServerSocket.)]
    (.bind socket (java.net.InetSocketAddress. "127.0.0.1" (int (or port 0))) 50)
    {:kind :tcp
     :port (.getLocalPort socket)
     :accept (fn []
               (when-let [client (try (.accept socket) (catch Throwable _ nil))]
                 {:in (io/reader (.getInputStream client))
                  :out (io/writer (.getOutputStream client))
                  :close! (fn [] (try (.close client) (catch Throwable _)))}))
     :close! (fn [] (try (.close socket) (catch Throwable _)))}))

(defn unix-sockets?
  "Can this host open a unix-domain socket? The check *is* the load: the
   capability lives in `kmet.app.modes.server-unix`, whose imports fail on a
   host without the NIO unix API (Jolt), and the server falls back to TCP
   loopback there rather than taking the row down."
  []
  (try
    (boolean ((requiring-resolve 'kmet.app.modes.server-unix/available?)))
    (catch Throwable _ false)))

(defn- unix-transport
  [path]
  ((requiring-resolve 'kmet.app.modes.server-unix/open) path))

(defn- open-transport
  "The listening transport the invocation asks for: `--socket` where the host
   can serve one, TCP loopback otherwise (with the fallback said out loud)."
  [{:keys [socket port]}]
  (cond
    (nil? socket) (tcp-transport port)
    (unix-sockets?) (unix-transport socket)
    :else (do (warn! (str "--socket needs a host with unix-domain sockets; serving on TCP loopback instead"
                          " (--port " (or port 0) ")"))
              (tcp-transport port))))

;; ── a connection ──────────────────────────────────────────────────────────
(defn- read-lines!
  "Hand CONN's lines to the server's queue until the socket closes or the hand-off
   refuses them. This is the thread the queue exists for: it parks in `readLine`
   and never touches the kernel. A refusal means the server stopped, and then
   the socket is done too — the close that follows is still announced, so the
   server thread releases whatever it had already created."
  [q conn]
  (try
    (with-open [rdr (:in conn)]
      (loop []
        (when-let [line (.readLine rdr)]
          (when (concurrent/put! q {:op :line :conn conn :line line})
            (recur)))))
    (catch Throwable _))
  (concurrent/put! q {:op :close :conn conn})
  nil)

(defn- connection
  "The server's view of one accepted client: the transport's streams and close
   (the accepted map) plus its lane, the writer thread that drains it, and the
   clock the reaper reads. The agent comes later — at authentication — so
   `:handle` is absent until then and `:agent-id` is an atom that is nil until
   the agent exists (the writer thread and the event listener hold this map from
   before authentication, so the badge has to reach them through a value they
   share)."
  [accepted depth]
  (let [conn (assoc accepted
                    :id (str (random-uuid))
                    :dead? (atom false)
                    :agent-id (atom nil)
                    :outq (concurrent/bounded-queue depth)
                    :last-seen (atom (concurrent/monotonic-ms)))]
    (concurrent/spawn (fn [] (write-records! conn)))
    conn))

(defn- refuse!
  "Answer RECORD on CONN's wire and end it: the lane closes, so the writer
   finishes what it has — a refusal a client that is still reading gets to see —
   and closes the socket. The connection leaves the table here: a refused
   connection is not a session, and the cap must not count it."
  [server conn record]
  (send! conn record)
  (concurrent/close! (:outq conn))
  (swap! (:conns server) dissoc (:id conn))
  nil)

(defn- absolute-cwd!
  "AUTH's `:cwd`, absolutized, or nil when it named none. A cwd that is not a
   directory is refused by name: an agent whose `pwd` does not exist answers
   every tool call with a filesystem error about a path the client never meant."
  [auth]
  (when-let [cwd (:cwd auth)]
    (when-not (and (string? cwd)
                   (fs/directory? cwd))
      (throw (ex-info (str "a connection's :cwd must be an existing directory: "
                           (pr-str cwd))
                      {:type :kmet.app/bad-connection-cwd :cwd cwd})))
    (str (fs/absolutize cwd))))

(defn- connection-create-opts
  "The `create!` options CONN's AUTH record brings.

   Three layers, narrowest last: the invocation's own run overrides (the flags
   the server was started with), the connection's overrides (`:model`,
   `:provider`, `:thinking`, …), and the settings it brings (`:config`, merged
   over the process's loaded settings). The credentials are not here — `:auth`
   is the token cache's, and one principal per process is the phase-1 decision.

   The `:cwd` becomes a create option the driver places the new session in, so
   two connections of one server run two worlds with no process cell moving."
  [server auth]
  (let [config (:config auth)]
    (when (and (some? config) (not (map? config)))
      (throw (ex-info "a connection's :config is a map of settings overrides"
                      {:type :kmet.app/bad-connection-config :config config})))
    (let [cwd (absolute-cwd! auth)]
      (cond-> (merge (:create-opts server) (invocation/create-opts auth))
        config (assoc :config (merge (:settings server) config))
        cwd (assoc :cwd cwd)))))

(defn- create-agent-for!
  "CONN's agent, created from its auth record and listened to: the conn carrying
   its handle plus the listener's disposer. A listener that cannot register
   would leave the agent unmanaged (no teardown events, no way to reach the
   conn), so the agent is disposed here and the failure rethrown for the caller's
   refusal."
  [server conn command]
  (let [handle ((:create! (:agents server)) (:scope server)
                                            (connection-create-opts server command))
        conn (do (reset! (:agent-id conn) (:id handle))
                 (assoc conn :handle handle))]
    (try
      (assoc conn :dispose
             (events/on-every (:ctx handle)
                              (fn [event]
                                (send! conn (wire/wire-event event)))))
      (catch Throwable t
        (try ((:dispose! (:agents server)) handle) (catch Throwable _ nil))
        (throw t)))))

(defn- authenticate!
  "CONN's first record: an `:auth` carrying the process's token creates the
   agent and answers `:ready`, and every other first record is a refusal and a
   close. The connection is replaced in the table so the event listener closes
   over a conn that carries its handle; its agent id travels through the shared
   atom instead, because the writer thread and the line reader hold the map from
   before authentication."
  [server conn command]
  (cond
    (not= :auth (:type command))
    (refuse! server conn
             {:type :error
              :error (str "authenticate first: send {:type :auth :token …}"
                          " as this connection's first record")})

    (not= (:token server) (:token command))
    (do
      (srce/log! (:scope server) :warn "a connection presented an unknown token"
                 {:connection (:id conn)})
      (refuse! server conn {:type :error :error "authentication failed"}))

    :else
    (try
      (let [conn (create-agent-for! server conn command)]
        (swap! (:conns server) assoc (:id conn) conn)
        (let [session ((:session (:handle conn)))]
          (send! conn (cond-> {:type :ready}
                        session (assoc :session-id (:id session)))))
        conn)
      (catch Throwable t
        (refuse! server conn
                 {:type :error
                  :error (str "cannot create an agent for this connection: "
                              (or (ex-message t) (str t)))})))))

(defn- open-connection!
  "A freshly accepted socket: refused when the server is at its session cap,
   otherwise announced with its `:hello` and left to authenticate. No agent
   exists yet — an unauthenticated connection costs a lane and a reader thread,
   and the reaper is what bounds even that."
  [server conn]
  (if (and (:max-sessions server)
           (>= (count @(:conns server)) (:max-sessions server)))
    (refuse! server conn
             {:type :error
              :error (str "this server serves " (:max-sessions server)
                          " session" (when (not= 1 (:max-sessions server)) "s")
                          "; all of them are in use")})
    (do
      (swap! (:conns server) assoc (:id conn) conn)
      (send! conn (cond-> {:type :hello :auth-required true}
                    (:transport server) (assoc :transport (:transport server))
                    (:port server) (assoc :port (:port server))
                    (:socket server) (assoc :socket (:socket server))))
      nil)))

(defn- serve-line!
  "One line from a known connection. Before `:ready` only an `:auth` is served;
   after it, a `:response` answers a dialog the server asked and every other
   record is a command."
  [server conn line]
  (let [{:keys [command error]} (rpc/parse-line line)]
    (cond
      error (send! conn (rpc/parse-failure error))

      (nil? (:handle conn)) (authenticate! server conn command)

      (= :response (:type command)) (answer-dialog! server command)

      ;; a silent command (`:respond`) answers nothing: pi writes no record for
      ;; one, and a client that sent one gets none
      :else (when-let [response (rpc/handle-record (state-for conn server) command)]
              (send! conn response))))
  nil)

(defn- close-connection!
  "Release and forget CONN: deny the dialogs it leaves open (a run must not stay
   parked on a question the client can no longer answer), stop listening (so
   teardown events are not written to a socket that is going away), dispose the
   agent, close the lane and the socket. A failure is reported on the agent's
   own terms — the registry logs a failed teardown and withdraws the capability
   anyway — so this releases what it can and returns. The table entry goes first,
   so no failure below can leak a connection the cap still counts."
  [conn server]
  (reset! (:dead? conn) true)
  (swap! (:conns server) dissoc (:id conn))
  (deny-dialogs! server (:id conn))
  (when-let [dispose (:dispose conn)] (dispose))
  (when-let [handle (:handle conn)]
    ;; a handle that is no longer the registry's (disposed elsewhere) throws
    ;; `:kmet.app/unknown-agent`; the connection still has a wire and a lane to
    ;; release, and the docstring above is the rule that wins
    (try ((:dispose! (:agents server)) handle)
         (catch Throwable t
           (srce/log! (:scope server) :error "connection agent teardown failed" t))))
  (concurrent/close! (:outq conn))
  ;; the lane's writer closes the socket when it drains, but a writer parked in
  ;; a write to a client that stopped reading would never get there: closing
  ;; the raw socket is what unblocks it
  (try ((:close! conn)) (catch Throwable _))
  nil)

;; ── the reaper ────────────────────────────────────────────────────────────

(defn- idle?
  "Has CONN been silent past the idle timeout, with no work of its own that a
   human is waiting on? A run (or a compaction) keeps its connection alive no
   matter how long the client is quiet: reaping mid-turn would cancel work
   nobody asked to cancel."
  [server conn now]
  (let [handle (:handle conn)]
    (and (pos? (:idle-timeout-ms server))
         (>= (- now @(:last-seen conn)) (:idle-timeout-ms server))
         (or (nil? handle)
             (not (or (runtime/turn-running? handle)
                      (runtime/compacting? handle)))))))

(defn- reap-idle!
  "Close every connection that has been silent past the timeout. The reaper
   thread only queues the request; this runs on the server thread, which is the
   only thread that disposes an agent."
  [server]
  (let [now (concurrent/monotonic-ms)]
    (doseq [conn (vals @(:conns server))
            :when (idle? server conn now)]
      (srce/log! (:scope server) :info "reaping an idle connection"
                 {:connection (:id conn) :idle-ms (- now @(:last-seen conn))})
      (close-connection! conn server))
    nil))

(defn- reap-idle-connections!
  "One thread that asks the server thread to reap, every slice. It exits when
   the server's queue closes: a `put!` that refuses is the shutdown."
  [q idle-ms]
  (let [slice (max 50 (min 1000 (quot idle-ms 4)))]
    (loop []
      (Thread/sleep (long slice))
      (when (concurrent/put! q {:op :reap})
        (recur)))))

;; ── the server thread ─────────────────────────────────────────────────────

(defn- serve-item!
  "One work item, answered. A connection whose creation failed is answered with
   the failure and forgotten: the server lives on, which is the one thing a
   server must do."
  [server {:keys [op conn line]}]
  (let [known (get @(:conns server) (:id conn))]
    (case op
      :open (open-connection! server conn)

      ;; a line from a connection the server already released is dropped: its
      ;; reader thread can still be parked when the socket goes away
      :line (when known
              (reset! (:last-seen known) (concurrent/monotonic-ms))
              (serve-line! server known line))

      :close (when known (close-connection! known server))

      :reap (reap-idle! server))
    nil))

(defn- serve-connections!
  "The server thread: the queue's one consumer, and the only thread that touches
   the kernel on a connection's behalf. Runs until the queue closes, then
   disposes what it still holds. A failing item is reported and the loop
   continues: one broken connection is not the end of the server."
  [server]
  (loop []
    (let [item (concurrent/take! (:q server))]
      (if (= concurrent/closed item)
        (doseq [conn (vals @(:conns server))] (close-connection! conn server))
        (do (try
              (serve-item! server item)
              (catch Throwable t
                (srce/log! (:scope server) :error "server connection work failed" t)))
            (recur))))))

;; ── the accept loop ───────────────────────────────────────────────────────

(defn- accept-connections!
  "Accept until the socket closes. Each connection is announced to the server
   thread and given a writer and a reader thread of its own; accepting never
   waits for an agent to be built. A refused announcement means the server is
   shutting down: the socket is closed here rather than left for a reader thread
   nothing will serve."
  [transport q depth]
  (loop []
    (when-let [accepted (try ((:accept transport)) (catch Throwable _ nil))]
      (let [conn (connection accepted depth)]
        (if (concurrent/put! q {:op :open :conn conn})
          (do (concurrent/spawn (fn [] (read-lines! q conn)))
              (recur))
          (try ((:close! conn)) (catch Throwable _)))))))

;; ── supervision ───────────────────────────────────────────────────────────

(defn- supervise
  "Serve until IN closes — how a parent process stops a server — then end the
   queue (the server thread releases the connections it still holds) and close
   the socket. Returns the exit code."
  [in transport q server-thread]
  (try
    (with-open [rdr (io/reader in)]
      (loop [] (when (.readLine rdr) (recur))))
    (catch Throwable _))
  (concurrent/close! q)
  (try ((:close! transport)) (catch Throwable _))
  ;; the server thread's releases touch the kernel, so the run does not end
  ;; while it is still doing them
  (try (.join ^Thread server-thread 10000) (catch Throwable _))
  0)

;; ── the row ───────────────────────────────────────────────────────────────

(defn- serve
  "The `:mode-server` row's apply: open the socket, announce it with the token,
   install the approvals route, start the server, accept and reaper threads,
   then serve until stdin closes.

   A mode runs only when the entry point hands it somewhere to report
   (`:on-result`): `--dump-state` mounts this row without one, and a diagnostic
   mount must prove the row composes without opening a socket. The port comes
   from the invocation (`--port`); 0 asks the OS for a free one."
  [ctx config]
  (let [scope (:parent ctx)
        agents (srce/get ctx :agents)
        {:keys [on-result port socket token]} config]
    (when on-result
      (let [generated-token (nil? token)
            token (or token (str (random-uuid)))
            {:keys [cap depth idle-ms]} (limits config)
            transport (open-transport {:socket socket :port port})
            q (concurrent/queue)
            server {:q q
                    :agents agents
                    :scope scope
                    :transport (:kind transport)
                    :port (:port transport)
                    :socket (:socket transport)
                    :token token
                    :max-sessions cap
                    :idle-timeout-ms idle-ms
                    ;; the create options every connection starts from, and the
                    ;; settings a connection's own :config is merged over
                    :create-opts (invocation/create-opts config)
                    :settings (or (:config config) (cfg/load-config))
                    ;; the connection table and the dialogs it owes live here,
                    ;; not on the server thread's stack: the approvals route
                    ;; runs on a *run's* thread and must read the same table
                    :conns (atom {})
                    :pending (atom {})}
            ;; the command language is rpc's, so its builtins register here
            ;; too: `state-for` refuses them by name until a connection has a
            ;; command surface, so a client's slash command never falls through
            ;; to the model
            _ (rpc/register-commands! scope)
            server-thread (concurrent/spawn (fn [] (serve-connections! server)))
            previous-route @ask-route
            ;; read here, inside the entry point's bindings: the supervisor runs
            ;; on another thread, where `*in*` is not this one
            in *in*]
        (out/record! *out* (cond-> {:type :listening
                                    :transport (:kind transport)
                                    :max-sessions (or cap 0)
                                    :idle-timeout (quot idle-ms 1000)}
                             ;; a token the invocation supplied is not echoed: it
                             ;; is already the caller's secret and stdout is a
                             ;; log. The generated one has to travel here, or a
                             ;; hand-started server could not be used at all.
                             generated-token (assoc :token token)
                             (:port transport) (assoc :port (:port transport))
                             (:socket transport) (assoc :socket (:socket transport))))
        (reset! ask-route (fn [ctx request] (ask-over-the-wire server ctx request)))
        (concurrent/spawn (fn [] (accept-connections! transport q depth)))
        (when (pos? idle-ms)
          (concurrent/spawn (fn [] (reap-idle-connections! q idle-ms))))
        (future
          (on-result {:exit (supervise in transport q server-thread)}))
        (fn []
          (reset! ask-route previous-route)
          (concurrent/close! q)
          (try ((:close! transport)) (catch Throwable _))
          nil)))))

(srce/defplugin plugin
  {:name :mode-server
   :inject [:agents]
   :apply serve})
