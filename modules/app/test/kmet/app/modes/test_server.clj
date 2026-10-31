(ns kmet.app.modes.test-server
  "Server mode end to end: `--profile server` boots the same composition as rpc
   mode, opens a socket, and gives **each connection its own agent**. The
   command language is rpc's; what these tests add is what the socket changes —
   two agents at once, a client that stalls holding nothing but its own parked
   thread, a closed connection releasing its agent, every record badged with the
   id of the agent it belongs to, and the transport's rules: a bearer token
   before an agent exists, a session cap, a per-wire queue that shows its gaps,
   an idle connection that is reaped, and the world a connection brings.

   The model is stubbed and the session log lives under target/
   (`kmet.app.test-support`), as for every mode test."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.app.modes.server :as server-mode]
            [kmet.app.session.core :as session]
            [kmet.app.test-support :as support :refer [with-mode-run]]
            [kmet.libs.concurrent :as concurrent])
  (:import [java.net InetSocketAddress Socket]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

;; ── the client ────────────────────────────────────────────────────────────

(defn- last-user-text
  "The text of the last user message the stub was asked to complete."
  [opts]
  (some->> (:messages opts)
           (filter #(= :user (:role %)))
           last
           :content
           (filter #(= :text (:type %)))
           (map :text)
           (str/join " ")))

(defn- replying
  "A `send-message` stub that streams back what it was asked, except for a prompt
   containing \"stall\": that one parks until the test releases RELEASE, which is
   what a run in flight looks like from the outside."
  [release]
  (fn [opts]
    (let [text (last-user-text opts)]
      (future
        (if (str/includes? (str text) "stall")
          (do (deref release 30000 :timeout)
              (when-let [on-done (:on-done opts)] (on-done :stop)))
          (do (when-let [on-text (:on-text opts)] (on-text (str "echo: " text)))
              (when-let [on-done (:on-done opts)] (on-done :stop))))
        :done))))

(defn- flooding
  "A `send-message` stub that streams CHUNKS text deltas and then settles,
   delivering DONE once it has emitted them all. A wire that keeps up with this
   would need the client reading; a client that does not must not slow the run."
  [done chunks]
  (fn [opts]
    (future
      (dotimes [i chunks]
        (when-let [on-text (:on-text opts)]
          (on-text (str "chunk-" i " " (apply str (repeat 40 "x"))))))
      (when-let [on-done (:on-done opts)] (on-done :stop))
      (deliver done :done)
      :done)))

(defn- start-reading!
  "The reader thread: collect every record the server writes until the socket
   closes, and mark the client closed when it does."
  [socket seen closed]
  (concurrent/spawn
   (fn []
     (try
       (with-open [rdr (io/reader (.getInputStream socket))]
         (loop []
           (when-let [line (.readLine rdr)]
             (swap! seen conj (edn/read-string line))
             (recur))))
       (catch Throwable _))
     (reset! closed true))))

(defn- client
  "A test client over a transport's plumbing: SEND! writes one record, READ!
   starts the reader over SEEN (the collected records) and CLOSED (set when the
   socket ends), and CLOSE! ends it. Every helper a test asserts with lives
   here, so the two transports differ only in their three functions.

   `:agent-id` is the first badged record — normally `:ready`, but a test that
   runs a one-deep lane may lose `:ready` to a gap, and every record after it
   carries the same id."
  [send! read! close!]
  (let [seen (atom [])
        closed (atom false)]
    {:send! send!
     :read! (fn [] (read! seen closed))
     :records (fn [] @seen)
     :of-type (fn [type] (filter #(= type (:type %)) @seen))
     :agent-id (fn [] (:agent-id (first (filter :agent-id @seen))))
     :closed? (fn [] @closed)
     :close! close!}))

(defn- narrow-receive-buffer!
  "Ask for a small receive buffer, so a client that stops reading backs the
   server's writes up in a few records rather than a few hundred. Jolt's
   java.net.Socket shim has no setReceiveBufferSize (jolt-bugs.md), so this is
   best-effort there: the flood the stalled-wire test sends is far larger than
   any default receive buffer, so the lane behind it still fills."
  [socket n]
  (try (.setReceiveBufferSize socket (int n))
       (catch IllegalArgumentException _ nil)))

(defn- connect-tcp
  "A client socket to PORT with its reader, writer and the facts a client needs.
   OPTS is `{:token :auth :auth? :read? :receive-buffer}`: the server's token,
   extra fields on the auth record, whether to send one at all, whether to read
   from the start, and the receive buffer a stalled-wire test wants small.

   The auth record is sent by the client, never by the test, so every connection
   starts the way the protocol says one does."
  [port {:keys [token auth auth? read? receive-buffer]
         :or {auth? true read? true}}]
  (let [socket (java.net.Socket.)]
    (when receive-buffer (narrow-receive-buffer! socket receive-buffer))
    (.connect socket (InetSocketAddress. "127.0.0.1" (int port)))
    (let [writer (io/writer (.getOutputStream socket))
          conn (assoc (client (fn [record]
                                (locking writer
                                  (.write writer (str (pr-str record) \newline))
                                  (.flush writer)))
                              (fn [seen closed] (start-reading! socket seen closed))
                              (fn [] (.close socket)))
                      :socket socket)]
      (when read? ((:read! conn)))
      (when auth? ((:send! conn) (merge {:type :auth :token token} auth)))
      conn)))

(defn- connect-unix
  "CONNECT-TCP over a unix-domain socket: the same client, the same handshake."
  [path {:keys [token auth]}]
  (let [addr (UnixDomainSocketAddress/of (str path))
        channel (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect channel addr)
    (let [out (io/writer (Channels/newOutputStream channel))
          conn (client (fn [record]
                         (locking out
                           (.write out (str (pr-str record) \newline))
                           (.flush out)))
                       (fn [seen closed]
                         (concurrent/spawn
                          (fn []
                            (try
                              (with-open [rdr (io/reader (Channels/newInputStream channel))]
                                (loop []
                                  (when-let [line (.readLine rdr)]
                                    (swap! seen conj (edn/read-string line))
                                    (recur))))
                              (catch Throwable _))
                            (reset! closed true))))
                       (fn [] (.close channel)))]
      ((:read! conn))
      ((:send! conn) (merge {:type :auth :token token} auth))
      conn)))

(defn- listening-record
  "The mode's `:listening` announcement from its own stdout, or nil until the
   announcement line is whole. OUT belongs to the mode's thread and is
   snapshotted at whatever point the test reads it, so a line can be half
   written: a line that does not parse is skipped rather than thrown, and the
   announcement is read only once it is complete."
  [out]
  (first (keep (fn [line]
                 (try
                   (let [record (edn/read-string line)]
                     (when (= :listening (:type record)) record))
                   (catch Throwable _ nil)))
               (remove str/blank? (str/split-lines out)))))

(def ^:private boot-wait-ms
  "How long a mode boot may take to announce `:listening`. A cold Jolt process
   pays several seconds of one-time cost (the composition's first require, the
   extension host's first mount), so a tighter bound fails whichever server test
   happens to run first."
  60000)

(defn- start!
  "Boot `--profile server` with ARGS and return the announcement plus `:stop!`.
   The mode's stdin is a loopback socket, so the test owns the supervisor
   signal: `:stop!` closes it and answers the mode's exit code. `:records` are
   the mode's own — the protocol goes on the socket."
  [args]
  (let [signal (java.net.ServerSocket. 0)
        accepted (future (.accept signal))
        stdin-client (Socket. "127.0.0.1" (.getLocalPort signal))
        stdin-peer @accepted
        out (java.io.StringWriter.)
        result (future (binding [*in* (io/reader (.getInputStream stdin-peer))]
                         (support/run-main-into (into ["--profile" "server"] args) out)))
        ;; wait for the announcement to *parse*, not for the substring: the mode
        ;; writes the line from its own thread, so a snapshot can catch it half
        ;; written (and a parse there would fail the whole namespace)
        announced (do (when-not (support/wait-until #(listening-record (str out)) boot-wait-ms)
                        (throw (ex-info "the server never announced a port"
                                        {:out (str out)})))
                      (listening-record (str out)))]
    {:port (:port announced)
     :socket (:socket announced)
     :transport (:transport announced)
     :token (:token announced)
     :max-sessions (:max-sessions announced)
     :records (fn [] (keep (fn [line]
                             (try (edn/read-string line) (catch Throwable _ nil)))
                           (remove str/blank? (str/split-lines (str out)))))
     :stop! (fn []
              (.close stdin-client)
              (:code (deref result 60000 {:code ::timeout})))
     :result result}))

(defn- with-server
  "Run F with a served socket. F gets `{:port :socket :token :conn! :stop!}`, and
   every connection it opened with `conn!` is closed on the way out, even when F
   throws — a test never has to close what it opened, which is also what keeps
   its body one nesting deep.

   OPTIONS override the three things a test may want different: `:send-message`
   (the model stub — by default one that answers the prompt), `:patch` (a
   composition layer file, for a profile that mounts more than the built-in
   rows), and `:args` (further server flags)."
  ([f] (with-server {} f))
  ([{:keys [send-message patch args]} f]
   (with-mode-run
     (or send-message (replying (promise)))
     (fn []
       (let [server (start! (cond-> (vec (or args []))
                              patch (conj "--patch" patch)))
             opened (atom [])
             conn! (fn conn!
                     ([] (conn! {}))
                     ([opts]
                      (let [c (connect-tcp (:port server)
                                           (merge {:token (:token server)} opts))]
                        (swap! opened conj c)
                        c)))]
         (try
           (f (assoc server :conn! conn!))
           (finally
             (doseq [c @opened] ((:close! c)))
             ((:stop! server)))))))))

(defn- ready
  "Wait until CONN's handshake has finished — the first badged record, which is
   `:ready` unless a one-deep lane dropped it for a gap — and answer the
   `:ready` record when it is still there."
  [conn]
  (support/wait-until #(some? ((:agent-id conn))))
  (first ((:of-type conn) :ready)))

(defn- answered?
  "Has CONN received the response for request ID, within TIMEOUT-MS? The
   default is the shared 5s; a test that first waits out a long run passes its
   own ceiling instead."
  ([conn id] (answered? conn id 5000))
  ([conn id timeout-ms]
   (support/wait-until #(some? (first (filter (fn [r] (= id (:id r)))
                                              ((:records conn)))))
                       timeout-ms)))

(defn- resync!
  "Ask for `get-state` until CONN is answered, up to MAX-ASKS asks of
   ANSWER-WAIT-MS each.

   A one-deep lane drops its oldest record under pressure and announces the
   loss only with the next record it keeps, so a record the run emits while it
   settles can eat one ask — and when the dropped record was the last one, the
   announcement never comes. A client that saw a stream gap asks again, which is
   what the gap is for."
  ([conn] (resync! conn 10 5000))
  ([conn max-asks answer-wait-ms]
   (loop [n 1]
     (let [id (str "resync-" n)]
       ((:send! conn) {:type :get-state :id id})
       (cond
         (answered? conn id answer-wait-ms) true
         (< n max-asks) (recur (inc n))
         :else false)))))

(defn- verdict
  "Wait until CONN has been answered `:ready` or refused with `:error`, then say
   which."
  [conn]
  (support/wait-until #(or (some? ((:agent-id conn)))
                           (seq ((:of-type conn) :error)))
                      5000)
  (if ((:agent-id conn)) :ready :refused))

(defn- connect-when-accepted
  "Open connections until one is accepted, within TIMEOUT-MS. The seat a closed
   connection frees is released on the server thread, so a test that closes one
   and connects again waits for the release rather than assuming it happened."
  [conn! timeout-ms]
  (let [deadline (+ (concurrent/monotonic-ms) (long timeout-ms))]
    (loop []
      (let [c (conn!)]
        (cond
          (= :ready (verdict c)) c
          (< (concurrent/monotonic-ms) deadline)
          (do ((:close! c)) (Thread/sleep 25) (recur))
          :else nil)))))

;; ── the gate: an approval answered by a client ────────────────────────

(def ^:private ask-all-patch
  "A composition layer that mounts the gate for this process: every tool call is
   asked about. The *answer* is the server's business — its approvals row sends
   the question to the client — so the layer only names the policy."
  "target/test-server-ask-all.edn")

(defn- ask-all!
  "Write the gate's patch layer and answer its path."
  []
  (spit ask-all-patch
        (pr-str {:patch [{:id :permissions
                          :plugin 'kmet.app.permissions/permissions
                          :config {:policy :ask-all}}]}))
  ask-all-patch)

(defn- tool-calling
  "A `send-message` stub whose first call asks for a bash tool call and whose
   second streams a closing answer, so an approved call runs and the run settles."
  []
  (let [n (atom 0)]
    (fn [opts]
      (future
        (if (= 1 (swap! n inc))
          (do (when-let [f (:on-tool-call opts)]
                (f {:id "call-1" :name "bash" :arguments {:command "echo approved"}}))
              (when-let [f (:on-done opts)] (f "tool_use")))
          (do (when-let [f (:on-text opts)] (f "done"))
              (when-let [f (:on-done opts)] (f "end_turn"))))
        :done))))

;; ── tests: the socket ─────────────────────────────────────────────────────

(deftest each-connection-is-an-agent
  ;; The socket is the boundary: two clients, two agents, and no record a client
  ;; reads after `:ready` can be mistaken for its neighbour's.
  (with-server
    (fn [{:keys [conn!]}]
      (let [a (conn!)
            b (conn!)]
        (ready a)
        (ready b)
        (is (= :tcp (:transport (first ((:of-type a) :hello))))
            "the greeting says which transport the client is on")
        (is (not= ((:agent-id a)) ((:agent-id b)))
            "two connections hold two agents")
        ((:send! a) {:type :get-state :id "a1"})
        ((:send! b) {:type :get-state :id "b1"})
        (is (answered? a "a1"))
        (is (answered? b "b1"))
        (let [state-a (first (filter #(= "a1" (:id %)) ((:records a))))
              state-b (first (filter #(= "b1" (:id %)) ((:records b))))]
          (is (= ((:agent-id a)) (:agent-id state-a))
              "the answer carries the id of the agent that answered it")
          (is (= ((:agent-id b)) (:agent-id state-b)))
          (is (not= (get-in state-a [:data :session :id])
                    (get-in state-b [:data :session :id]))
              "and the two agents have sessions of their own"))
        (is (every? #(= ((:agent-id a)) (:agent-id %))
                    (drop-while #(not= :ready (:type %)) ((:records a))))
            "every record after :ready is badged with its agent's id")
        (is (every? #(= ((:agent-id b)) (:agent-id %))
                    (drop-while #(not= :ready (:type %)) ((:records b)))))
        ;; the command engine is rpc mode's, unshared state included: a refusal
        ;; travels the socket with the same name and the same badge
        ((:send! a) {:type :prompt :id "a2"})
        (is (answered? a "a2"))
        (let [refused (first (filter #(= "a2" (:id %)) ((:records a))))]
          (is (false? (:success refused)))
          (is (str/includes? (str (:error refused)) "requires a non-empty :message"))
          (is (= ((:agent-id a)) (:agent-id refused))))))))

(deftest a-command-needs-the-surface-a-connection-lacks
  ;; The command language is rpc's, but a connection has no command surface: a
  ;; slash command is refused by name, never run with its reply dropped.
  (with-server
    (fn [{:keys [conn!]}]
      (let [c (conn!)]
        (ready c)
        ((:send! c) {:type :prompt :id "cmd" :message "/name gates"})
        (is (answered? c "cmd"))
        (let [refused (first (filter #(= "cmd" (:id %)) ((:records c))))]
          (is (false? (:success refused)) "the command is refused, not answered handled")
          (is (str/includes? (str (:error refused)) "not available on this connection")
              "and the refusal names what the connection lacks"))))))

(deftest a-client-that-never-finishes-a-line-holds-nothing
  ;; The reader thread parks in `readLine`, so a half-written record costs one
  ;; parked thread and nothing else: the server thread is not reading sockets,
  ;; it is serving the queue.
  (with-server
    (fn [{:keys [conn!]}]
      (let [stalled (conn!)
            working (conn!)]
        (ready stalled)
        (ready working)
        (let [w (io/writer (.getOutputStream (:socket stalled)))]
          (.write w "{:type :get-state")
          (.flush w))
        ((:send! working) {:type :get-state :id "w1"})
        (is (answered? working "w1")
            "the other client is served while the first is mid-record")
        (is (= [:hello :ready] (mapv :type ((:records stalled))))
            "and the stalled client was told nothing but its own greeting and id, because
             it asked nothing else")))))

(deftest a-stalled-run-does-not-hold-the-other-agent
  ;; The gate's shape: one agent mid-run, another answering. A run's events reach
  ;; only the connection that owns it, which is what makes the two wires
  ;; independent.
  (with-server
    (fn [{:keys [conn!]}]
      (let [slow (conn!)
            fast (conn!)]
        (ready slow)
        (ready fast)
        ((:send! slow) {:type :prompt :id "s1" :message "stall please"})
        (is (answered? slow "s1")
            "the stalling prompt is answered when it is accepted")
        ((:send! fast) {:type :prompt :id "f1" :message "hello"})
        (is (support/wait-until #(seq ((:of-type fast) :agent-settled)) 10000)
            "the second agent runs to its end while the first is still running")
        (is (empty? ((:of-type slow) :agent-settled))
            "and the first agent's run has not settled")
        (is (not (contains? (set (map :agent-id ((:records fast))))
                            ((:agent-id slow))))
            "no event of the stalled run reached the other wire")))))

(deftest closing-a-connection-leaves-the-server-serving
  ;; A closed socket is one client fewer, not an outage: the connections that are
  ;; still there keep being served, and a later client gets an agent of its own.
  ;; That the closed connection's agent is *disposed* is the registry's own
  ;; contract (`test-agents`), exercised here through the only thing a wire
  ;; client can see: the server still answering.
  (with-server
    (fn [{:keys [conn!]}]
      (let [a (conn!)
            b (conn!)]
        (ready a)
        (ready b)
        ((:close! a))
        ((:send! b) {:type :get-state :id "b1"})
        (is (answered? b "b1")
            "the surviving connection still answers")
        (let [c (conn!)]
          (ready c)
          (is (not= ((:agent-id b)) ((:agent-id c)))
              "and a later connection gets an agent of its own"))))))

(deftest server-mode-is-inert-under-a-diagnostic-mount
  ;; `--dump-state` mounts every row of the profile, this one included: a
  ;; diagnostic mount hands the mode no `:on-result`, so it stays inert and the
  ;; dump proves the row composes without opening a socket.
  (let [{:keys [code out]} (support/run-main-isolated ["--profile" "server" "--dump-state"])]
    (is (= 0 code))
    (is (some #(and (str/includes? % ":mode-server") (str/includes? % ":active"))
              (str/split-lines out))
        "the mode row mounts and activates in a diagnostic boot")
    (is (not (str/includes? out ":listening"))
        "and nothing listened")))

(deftest an-approval-is-answered-by-the-client
  ;; The gate's third leg. The policy asks (`:ask-all`), the question travels the
  ;; socket as a `:request`, the client answers `:allow`, and the call the
  ;; question was about runs — its output, not a block reason, is what the wire
  ;; reports back.
  (with-server
    {:send-message (tool-calling) :patch (ask-all!)}
    (fn [{:keys [conn!]}]
      (let [c (conn!)]
        (ready c)
        ((:send! c) {:type :prompt :id "p1" :message "run a tool"})
        (is (answered? c "p1"))
        (is (support/wait-until #(seq ((:of-type c) :request)) 20000)
            "the policy asked, and the question reached the client")
        (let [request (first ((:of-type c) :request))]
          (is (= :ask (:method request)))
          (is (= "bash" (:tool-name request)) "the question names the call")
          (is (= ((:agent-id c)) (:agent-id request)) "and belongs to its agent")
          ((:send! c) {:type :response :id (:id request) :data {:decision :allow}})
          (is (support/wait-until #(seq ((:of-type c) :agent-settled)) 30000)
              "an approved call runs and its run settles")
          (let [end (first ((:of-type c) :tool-execution-end))]
            (is (some? end) "the call reached its end")
            (is (false? (:is-error end)) "approved, so it is not a block")
            (is (str/includes? (pr-str (:result end)) "approved")
                "and the result is the tool's own output")))))))

(deftest a-session-move-is-a-connections-own
  ;; The session commands are the shared table's; what the socket changes is
  ;; whose agent they move. Two clients, two agents: `new-session` on one is
  ;; invisible on the other, every answer carries the agent it belongs to, and
  ;; the move lands on the connection that asked.
  (with-server
    (fn [{:keys [conn!]}]
      (let [a (conn!) b (conn!)
            state (fn [conn id]
                    (let [r (first (filter #(= id (:id %)) ((:records conn))))]
                      [(:data r) (:agent-id r)]))]
        (ready a) (ready b)
        ((:send! a) {:type :get-state :id "a1"})
        ((:send! b) {:type :get-state :id "b1"})
        (answered? a "a1") (answered? b "b1")
        (let [[a-before _] (state a "a1")
              [b-before _] (state b "b1")]
          (is (not= (get-in a-before [:session :id]) (get-in b-before [:session :id]))
              "each connection created its own session")
          ((:send! a) {:type :new-session :id "a2"})
          (is (answered? a "a2"))
          ((:send! a) {:type :get-state :id "a3"})
          ((:send! b) {:type :get-state :id "b2"})
          (answered? a "a3") (answered? b "b2")
          (let [[moved badge] (state a "a2")
                [a-after _] (state a "a3")
                [b-after _] (state b "b2")]
            (is (false? (:cancelled moved)))
            (is (= (:session-id moved) (get-in a-after [:session :id]))
                "the connection that asked moved to the session it was told")
            (is (= ((:agent-id a)) badge)
                "and the answer is badged with that connection's agent")
            (is (= (get-in b-before [:session :id]) (get-in b-after [:session :id]))
                "the other connection's agent did not move")))))))

(deftest a-silent-command-writes-nothing
  ;; `respond` is the protocol's one silent command (pi writes no record for an
  ;; answer). A connection that sends one gets none back, and keeps being served
  ;; — the get-state after it is the proof.
  (with-server
    (fn [{:keys [conn!]}]
      (let [c (conn!)]
        (ready c)
        ((:send! c) {:type :respond :id "r"})
        ((:send! c) {:type :get-state :id "s"})
        (is (answered? c "s") "the connection is still served after the silence")
        (is (nil? (first (filter #(= "r" (:id %)) ((:records c)))))
            "and nothing came back for it")))))

;; ── tests: the transport's rules ──────────────────────────────────────────

(deftest a-connection-must-authenticate-before-it-gets-an-agent
  ;; The token is the door: a command before an `:auth` is refused and the socket
  ;; closed, a wrong token is refused the same way, and only the right one gets
  ;; `:ready` — the point being that no agent exists for a connection that has
  ;; not authenticated.
  (with-server
    (fn [{:keys [conn!]}]
      (let [unauthed (conn! {:auth? false})]
        ((:send! unauthed) {:type :get-state :id "x"})
        (is (support/wait-until #(seq ((:of-type unauthed) :error)))
            "a command before authentication is answered with an error")
        (is (str/includes? (str (:error (first ((:of-type unauthed) :error))))
                           "authenticate first"))
        (is (nil? ((:agent-id unauthed))) "and no agent was created")
        (is (support/wait-until (:closed? unauthed) 5000) "the socket is closed"))
      (let [wrong (conn! {:token "not-the-token"})]
        (is (support/wait-until #(seq ((:of-type wrong) :error))))
        (is (str/includes? (str (:error (first ((:of-type wrong) :error))))
                           "authentication failed"))
        (is (nil? ((:agent-id wrong))))
        (is (support/wait-until (:closed? wrong) 5000)))
      (let [right (conn!)]
        (ready right)
        (is (some? ((:agent-id right))) "the server's own token is the way in")))))

(deftest a-supplied-token-is-the-way-in-and-is-not-echoed
  ;; `--token` names the secret instead of the server generating one. The
  ;; announcement carries a token only when it generated it: a secret the caller
  ;; supplied must not be repeated into the server's log.
  (with-server
    {:args ["--token" "secret-token"]}
    (fn [{:keys [conn! records]}]
      (is (nil? (:token (first (filter #(= :listening (:type %)) (records)))))
          "the listening record does not echo the supplied token")
      (let [ok (conn! {:token "secret-token"})]
        (ready ok)
        (is (some? ((:agent-id ok))) "the supplied token authenticates")
        (let [bad (conn! {:token "wrong-token"})]
          (is (support/wait-until #(seq ((:of-type bad) :error))))
          (is (str/includes? (str (:error (first ((:of-type bad) :error))))
                             "authentication failed")
              "and only it does"))))))

(deftest a-unix-socket-never-replaces-a-file
  ;; `--socket` removes a stale socket left by a crash, and never a regular
  ;; file: naming somebody's file must not delete it.
  (when (server-mode/unix-sockets?)
    (let [path "target/test-server-occupied.sock"]
      (spit path "not a socket")
      (try
        (let [{:keys [code]} (support/run-main ["--profile" "server" "--socket" path])]
          (is (= 1 code) "the boot refuses the path")
          (is (fs/regular-file? path) "and the file is still there")
          (is (= "not a socket" (slurp path))))
        (finally
          (fs/delete-if-exists path))))))

(deftest a-server-caps-its-sessions
  ;; `--max-sessions` is a live cap on connections: the second client is refused
  ;; while the first holds the seat, and a freed seat serves the next one.
  (with-server
    {:args ["--max-sessions" "1"]}
    (fn [{:keys [conn!]}]
      (let [a (conn!)]
        (ready a)
        (let [b (conn! {:auth? false})]
          (is (support/wait-until #(seq ((:of-type b) :error)))
              "the second connection is refused at the cap")
          (is (str/includes? (str (:error (first ((:of-type b) :error)))) "session")
              "and the refusal says so")
          (is (support/wait-until (:closed? b) 5000)))
        ((:close! a))
        ;; the seat is released on the server thread, so the next connection may
        ;; have to wait a moment for it
        (is (some? (connect-when-accepted conn! 5000))
            "a closed connection frees its seat")))))

(deftest an-idle-connection-is-reaped
  ;; `--idle-timeout` releases a connection that says nothing and has no work in
  ;; flight. The reaper only queues the decision; the server thread closes it, so
  ;; the client sees its socket end and the server keeps serving.
  (with-server
    {:args ["--idle-timeout" "1"]}
    (fn [{:keys [conn!]}]
      (let [c (conn!)]
        (ready c)
        (is (support/wait-until (:closed? c) 8000)
            "the reaper closed a connection that stayed silent past the timeout")
        (is (some? (connect-when-accepted conn! 5000))
            "and the server still serves a new one")))))

(deftest the-listening-announcement-is-read-once-it-is-whole
  ;; The mode writes its announcement from its own thread; a snapshot of its
  ;; stdout can catch the line half written, and parsing that line would fail
  ;; the namespace instead of the test (the 16-error cascade this guards).
  (is (nil? (listening-record "")))
  (is (nil? (listening-record "{:type :listening :port 4123")))
  (is (nil? (listening-record "{:type :ready :port 1}\n{:type :listenin")))
  (is (= {:type :listening :port 4123 :token "t"}
         (listening-record
          "{:type :listening :port 4123 :token \"t\"}\n{:type :rea"))))

(deftest a-client-that-stops-reading-gets-a-gap-and-resyncs
  ;; The fan-out's rule: a run streams to its end whether or not the client is
  ;; reading, and a client that fell behind learns its stream has a hole
  ;; (`:stream-gap`) instead of silently losing the middle of a message — then
  ;; resyncs with `get-state`. The lane is one record deep and the socket's
  ;; receive buffer is tiny, so the gap is not a matter of timing; the resync
  ;; is retried because the same lane can eat the reply while the run settles
  ;; (see resync!).
  (let [done (promise)]
    (with-server
      {:send-message (flooding done 4000)
       :args ["--queue-depth" "1"]}
      (fn [{:keys [conn!]}]
        (let [c (conn! {:read? false :receive-buffer 1024})]
          ((:send! c) {:type :prompt :id "p1" :message "flood"})
          (is (= :done (deref done 60000 :timeout))
              "the run streamed to its end without waiting for the client")
          ((:read! c))
          (is (support/wait-until #(seq ((:of-type c) :stream-gap)) 60000)
              "the client learns its stream has a hole")
          (let [gap (first ((:of-type c) :stream-gap))]
            (is (pos? (:dropped gap)) "the gap counts what it lost")
            (is (= ((:agent-id c)) (:agent-id gap)) "and belongs to its agent"))
          (is (resync! c)
              "and the client resyncs with get-state"))))))

(deftest a-connection-brings-its-own-world
  ;; The config half of landing 9: the auth record's `:cwd` places the agent's
  ;; session, and its `:config` overrides the settings the agent runs on. Two
  ;; connections of one server, two cwds and two models — no process cell moved.
  (let [world (fn [name]
                (let [dir (fs/absolutize (str "target/test-server-worlds/" name))]
                  (fs/create-dirs dir)
                  (str dir)))]
    (with-server
      (fn [{:keys [conn!]}]
        (let [dir-a (world "a")
              dir-b (world "b")
              a (conn! {:auth {:cwd dir-a :config {:model "claude-haiku-5-5"}}})
              b (conn! {:auth {:cwd dir-b}})]
          (ready a) (ready b)
          ((:send! a) {:type :get-state :id "a1"})
          ((:send! b) {:type :get-state :id "b1"})
          (answered? a "a1") (answered? b "b1")
          (let [data (fn [conn id]
                       (:data (first (filter #(= id (:id %)) ((:records conn))))))
                a-state (data a "a1")
                b-state (data b "b1")]
            (is (= "claude-haiku-5-5" (:model a-state))
                "the connection's :config is the settings its agent runs on")
            (is (= "deepseek-v4-flash" (:model b-state))
                "and the other connection kept the process's")
            (is (str/starts-with? (get-in a-state [:session :file])
                                  (str (fs/absolutize
                                        (session/session-dir-for-cwd (support/sessions-dir) dir-a))))
                "the connection's :cwd placed its session")
            (is (str/starts-with? (get-in b-state [:session :file])
                                  (str (fs/absolutize
                                        (session/session-dir-for-cwd (support/sessions-dir) dir-b)))))
            (is (not= (get-in a-state [:session :file])
                      (get-in b-state [:session :file])))))))))

(deftest a-connections-cwd-must-exist
  ;; A cwd the client names has to be a directory: an agent whose `pwd` does not
  ;; exist would fail every tool call with a filesystem error about a path the
  ;; client never meant.
  (with-server
    (fn [{:keys [conn!]}]
      (let [c (conn! {:auth {:cwd "target/test-server-worlds/does-not-exist"}})]
        (is (support/wait-until #(seq ((:of-type c) :error)))
            "a cwd that is not a directory never gets an agent")
        (is (str/includes? (str (:error (first ((:of-type c) :error)))) "cwd"))))))

(deftest a-unix-socket-serves-the-same-protocol
  ;; `--socket` is the second transport: the same hello, the same handshake, the
  ;; same command language. On a host with no unix-domain sockets (Jolt) the
  ;; server falls back to TCP port loopback and says so on stderr, so the same
  ;; invocation is portable.
  (let [path "target/test-server.sock"]
    (fs/delete-if-exists path)
    (try
      (with-server
        {:args ["--socket" path]}
        (fn [{:keys [port socket transport token]}]
          (if (server-mode/unix-sockets?)
            (do
              (is (= :unix transport) "the announcement says which transport it bound")
              (is (= (str (fs/absolutize path)) socket))
              (let [c (connect-unix socket {:token token})]
                (ready c)
                ((:send! c) {:type :get-state :id "u1"})
                (is (answered? c "u1") "the protocol is the same over a unix socket")
                ((:close! c))))
            (do
              (is (= :tcp transport) "a host without unix sockets falls back to TCP")
              (is (some? port))))))
      (finally
        (fs/delete-if-exists path)))))

(deftest a-port-that-is-not-a-number-is-a-usage-error
  (let [{:keys [code err]} (support/run-main ["--profile" "server" "--port" "abc"])]
    (is (= 1 code))
    (is (str/includes? err "--port needs a number"))))

(deftest a-session-limit-that-is-not-a-number-is-a-usage-error
  (let [{:keys [code err]} (support/run-main ["--profile" "server" "--max-sessions" "many"])]
    (is (= 1 code))
    (is (str/includes? err "--max-sessions needs a number"))))
