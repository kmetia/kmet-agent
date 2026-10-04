(ns kmet.libs.test-jsonrpc
  "Tests for kmet.libs.jsonrpc: framing edge cases (byte counts, split
   reads, header tolerance) and connection semantics (correlation,
   server-request auto-replies, EOF vs timeout, graceful close) exercised
   over in-process piped streams. One ^:slow case spawns a subprocess to
   pin the default kill path of close!."
  (:require [babashka.process :as proc]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.json :as json]
            [kmet.libs.jsonrpc :as jsonrpc]
            [kmet.libs.process :as process]))

;; ─── Helpers ──────────────────────────────────────────────────────────────

(defn- pipe-pair
  "Two connected pipe ends {:in :out} — writes to :out are readable from
   :in across threads. (The buffer-size ctor arg is JVM-only — Jolt's
   PipedInputStream takes no size, so connect the no-arg pair.)"
  []
  (let [in (java.io.PipedInputStream.)
        out (java.io.PipedOutputStream. in)]
    {:in in :out out}))

(defn- chunked-input
  "InputStream over BYTES delivering at most N bytes per read call."
  [bytes n]
  (let [ba (java.io.ByteArrayInputStream. bytes)]
    (proxy [java.io.InputStream] []
      (read
        ([] (.read ba))
        ([b] (.read ba b))
        ([b off len] (.read ba b off (min len n)))))))

(defn- start-fake!
  "Runs FA-HANDLER on a daemon thread against SERVER-IN/SERVER-OUT using
   the same framing fns the client uses. FA-HANDLER receives each parsed
   message plus a send! fn (map → frame)."
  [server-in server-out framing fa-handler]
  (let [reader (if (= :line-delimited framing)
                 (java.io.BufferedReader.
                  (java.io.InputStreamReader. server-in "UTF-8"))
                 nil)
        read-msg (fn []
                   (if (= :line-delimited framing)
                     (jsonrpc/read-line-msg reader)
                     (jsonrpc/read-content-length server-in)))
        write-msg (fn [msg]
                    (let [payload (json/generate-string msg)
                          bytes (if (= :line-delimited framing)
                                  (jsonrpc/encode-line-msg payload)
                                  (jsonrpc/encode-content-length payload))]
                      (doto server-out
                        (.write bytes 0 (alength bytes))
                        (.flush))))]
    (doto (Thread.
           (fn []
             (loop []
               (let [payload (try (read-msg) (catch Exception _ ::done))]
                 ;; nil payload = EOF (line mode): stop, don't busy-spin
                 (when (and payload (not= payload ::done))
                   (when-let [parsed (try
                                       (json/parse-string payload true)
                                       (catch Exception _ nil))]
                     (try (fa-handler parsed write-msg)
                          (catch Exception _ nil))))
                 (when (and payload (not= payload ::done))
                   (recur))))))
      (.setDaemon true)
      (.start))))

(defn- run-with-conn
  "THUNK runs against a fresh client conn whose peer is a fake server
   speaking FRAMING. FA-HANDLER sees every incoming message plus a send!
   fn. CONN-OPTS merge into connect-streams options."
  ([framing fa-handler thunk] (run-with-conn framing fa-handler {} thunk))
  ([framing fa-handler conn-opts thunk]
   (let [c2s (pipe-pair)   ;; client → server
         s2c (pipe-pair)]  ;; server → client
     (start-fake! (:in c2s) (:out s2c) framing fa-handler)
     (let [conn (jsonrpc/connect-streams
                 (merge {:in (:in s2c) :out (:out c2s) :framing framing}
                        conn-opts))]
       (try (thunk conn)
            (finally (jsonrpc/close! conn)))))))

;; ─── Framing ──────────────────────────────────────────────────────────────

(deftest content-length-round-trip-multi-byte
  (testing "byte count is computed on UTF-8 bytes, never chars"
    (doseq [payload ["{}"
                     (json/generate-string {:value "héllo 🎉 ümläut"})
                     (json/generate-string {:emoji "🇯🇵🎉" :text "日本語テキスト"})]]
      (let [bytes (jsonrpc/encode-content-length payload)
            text (String. bytes "UTF-8")
            declared (parse-long (second (re-find #"Content-Length: (\d+)" text)))]
        (is (= (count (.getBytes payload "UTF-8")) declared)
            (str "declared length matches body bytes for " payload))
        (is (= payload (jsonrpc/read-content-length
                        (java.io.ByteArrayInputStream. bytes))))))))

(deftest header-tolerance
  (testing "case-insensitive header, extra headers, bare \\n terminators"
    (let [body "{\"ok\":1}"
          n (alength (.getBytes body "UTF-8"))
          variants [(str "content-length: " n "\n\n" body)
                    (str "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n"
                         "CONTENT-LENGTH: " n "\r\n\r\n" body)
                    (str "Content-Length: " n "\r\n\n" body)]]
      (doseq [v variants]
        (is (= body (jsonrpc/read-content-length
                     (java.io.ByteArrayInputStream. (.getBytes v "UTF-8")))))))))

(deftest chunked-stream-reads
  (testing "frames arriving byte-by-byte still decode"
    (let [payload (jsonrpc/encode-content-length "{\"n\":42}")]
      (doseq [chunk [1 3 7]]
        (is (= "{\"n\":42}"
               (jsonrpc/read-content-length (chunked-input payload chunk))))))))

(deftest missing-content-length-throws
  (is (thrown? Exception
               (jsonrpc/read-content-length
                (java.io.ByteArrayInputStream.
                 (.getBytes "Garbage: nope\r\n\r\nnot-json"))))))

;; ─── Connection: correlation & dispatch ──────────────────────────────────

(deftest request-round-trip-and-error-path
  (let [seen (atom {})]
    (run-with-conn
     :content-length
     (fn [{:keys [id method params]} send!]
       (swap! seen assoc method id)
       (case method
         "echo" (send! {:jsonrpc "2.0" :id id :result params})
         "ping" (send! {:jsonrpc "2.0" :id id :result {:pong true}})
         "boom" (send! {:jsonrpc "2.0" :id id
                        :error {:code -32000 :message "nope"}})
         nil))
     (fn [conn]
       (is (= {:x 1} (jsonrpc/request! conn "echo" {:x 1})))
       (is (= {:pong true} (jsonrpc/request! conn "ping" {} {:timeout-ms 2000})))
       (let [e (try (jsonrpc/request! conn "boom" {}) nil
                    (catch Exception e e))
             data (ex-data e)]
         (is (some? e) "json-rpc error surfaces as exception")
         (is (= :kmet.libs.jsonrpc/request-error (:type data)))
         (is (= (get @seen "boom") (:id data)) "ex-data carries the request id")
         (is (= "boom" (:method data)))
         (is (re-find #"-32000" (ex-message e))))))))

(deftest out-of-order-responses-correlate-by-id
  (let [received (atom [])
        a-landed (promise)]
    (run-with-conn
     :content-length
     (fn [{:keys [id method]} send!]
       (swap! received conj {:id id :send! send!})
       (when (= "a" method)
         (deliver a-landed true))
       ;; once both requests landed, answer them in reverse order
       (when (= 2 (count @received))
         (let [[a b] @received]
           ((:send! b) {:jsonrpc "2.0" :id (:id b) :result {:which "b"}})
           ((:send! a) {:jsonrpc "2.0" :id (:id a) :result {:which "a"}}))))
     (fn [conn]
       (let [pa (future (jsonrpc/request! conn "a" {} {:timeout-ms 3000}))]
         (is (true? (deref a-landed 2000 false)) "request A landed")
         (let [pb (future (jsonrpc/request! conn "b" {} {:timeout-ms 3000}))
               result-a (deref pa 5000 ::timeout)
               result-b (deref pb 5000 ::timeout)]
           (is (= {:which "a"} result-a))
           (is (= {:which "b"} result-b))))))))

(deftest stale-response-dropped-no-crash
  (run-with-conn
   :content-length
   (fn [_msg send!]
     (send! {:jsonrpc "2.0" :id 424242 :result {:stale true}})
     (send! {:jsonrpc "2.0" :id 1 :result {:fresh true}}))
   (fn [conn]
     (is (= {:fresh true}
            (jsonrpc/request! conn "anything" {} {:timeout-ms 2000}))
         "stale id-424242 reply ignored, matching id-1 delivered"))))

(deftest notification-reaches-callback
  (let [seen (atom [])
        notified (promise)]
    (run-with-conn
     :content-length
     (fn [{:keys [id method]} send!]
       (when (= method "trigger")
         ;; push arrives before the reply; both cross the same pipe
         (send! {:jsonrpc "2.0" :method "upd" :params {:p 1}})
         (send! {:jsonrpc "2.0" :id id :result "done"})))
     {:on-notification (fn [m]
                         (swap! seen conj m)
                         (deliver notified true))}
     (fn [conn]
       (is (= "done" (jsonrpc/request! conn "trigger" {} {:timeout-ms 2000})))
       (is (= true (deref notified 2000 false)) "notification callback ran")
       (is (= [{:jsonrpc "2.0" :method "upd" :params {:p 1}}] @seen))))))

;; Server→client request handling is observed from the fake side: after the
;; client's "trigger" arrives, the fake sends a server-request (id 900); the
;; lib's auto-reply travels back toward the server and is captured here.
(defn- capture-server-request-reply
  "Returns the reply frame the fake received for its server-request, given
   an ON-REQUEST handler installed on the client."
  [on-request]
  (let [reply (promise)
        handled (promise)
        c2s (pipe-pair)
        s2c (pipe-pair)]
    (start-fake! (:in c2s) (:out s2c) :content-length
                 (fn [{:keys [id method] :as msg} send!]
                   (cond
                     (= method "trigger")
                     (do (send! {:jsonrpc "2.0" :id 900
                                 :method "workspace/configuration"
                                 :params {:items ["a"]}})
                         (deref handled 2000 :timeout)
                         (send! {:jsonrpc "2.0" :id id :result :done}))

                     (not method)
                     (deliver reply (dissoc msg :jsonrpc)))))
    (let [conn (jsonrpc/connect-streams
                {:in (:in s2c)
                 :out (:out c2s)
                 :on-request (fn [method params]
                               (deliver handled true)
                               (on-request method params))})]
      (try
        (jsonrpc/request! conn "trigger" {} {:timeout-ms 3000})
        (deref reply 2000 ::no-reply)
        (finally (jsonrpc/close! conn))))))

(deftest server-request-replied-with-handler-value
  (is (= {:id 900 :result {:answer 42}}
         (capture-server-request-reply
          (fn [_method _params] {:answer 42})))))

(deftest unknown-server-request-answered-32601
  (let [reply (capture-server-request-reply (constantly nil))]
    (is (= 900 (:id reply)))
    (is (= -32601 (-> reply :error :code))
        "nil handler return ⇒ MethodNotFound")))

(deftest throwing-server-request-handler-answered-32603
  (let [reply (capture-server-request-reply
               (fn [_method _params] (throw (RuntimeException. "kaboom"))))]
    (is (= -32603 (-> reply :error :code)))
    (is (re-find #"kaboom" (-> reply :error :message)))))

;; ─── Connection: lifecycle ───────────────────────────────────────────────

(deftest ^:slow timeout-vs-death-distinction
  (let [seen (atom nil)]
    (run-with-conn
     :content-length
     (fn [{:keys [id]} _send!] (reset! seen id))
     (fn [conn]
       (let [e (try (jsonrpc/request! conn "never" {} {:timeout-ms 80})
                    nil (catch Exception e e))
             data (ex-data e)]
         (is (some? e))
         (is (= :kmet.libs.jsonrpc/timeout (:type data)))
         (is (= @seen (:id data)) "the id identifies the abandoned request")
         (is (= "never" (:method data))))))))

(deftest ^:slow eof-fails-pending-as-transport-dead-not-timeout
  (let [c2s (pipe-pair)
        s2c (pipe-pair)
        conn (jsonrpc/connect-streams {:in (:in s2c) :out (:out c2s)})]
    (try
      (let [f (future
                (try (jsonrpc/request! conn "slow" {} {:timeout-ms 8000})
                     (catch Exception e e)))
            t0 (System/currentTimeMillis)]
        (Thread/sleep 50)
        (.close (:out s2c))                ;; peer dies mid-request
        (let [res (deref f 4000 ::still-blocked)
              elapsed (- (System/currentTimeMillis) t0)]
          (is (instance? Exception res))
          (is (= :kmet.libs.jsonrpc/transport-dead (:type (ex-data res))))
          (is (= "slow" (:method (ex-data res))))
          (is (integer? (:id (ex-data res))))
          (is (< elapsed 3500) "fails promptly, not at the 8s timeout")))
      (finally (jsonrpc/close! conn)))))

(deftest request-after-close-throws-immediately
  (let [c2s (pipe-pair)
        s2c (pipe-pair)
        conn (jsonrpc/connect-streams {:in (:in s2c) :out (:out c2s)})]
    (jsonrpc/close! conn)
    (let [e (try (jsonrpc/request! conn "x" {} {:timeout-ms 500})
                 nil (catch Exception e e))]
      (is (some? e))
      (is (= :kmet.libs.jsonrpc/transport-dead (:type (ex-data e)))))))

(deftest ^:slow last-used-bumps-on-traffic
  (run-with-conn
   :content-length
   (fn [{:keys [id]} send!] (send! {:jsonrpc "2.0" :id id :result nil}))
   (fn [conn]
     (let [t0 (jsonrpc/last-used conn)]
       (Thread/sleep 5)
       (jsonrpc/request! conn "x" {} {:timeout-ms 2000})
       (is (> (jsonrpc/last-used conn) t0))
       (is (empty? (jsonrpc/stderr-tail conn)) "stderr tail starts empty")))))

(deftest line-delimited-end-to-end
  (run-with-conn
   :line-delimited
   (fn [{:keys [id]} send!] (send! {:jsonrpc "2.0" :id id :result {:mode "line"}}))
   (fn [conn]
     (is (= {:mode "line"} (jsonrpc/request! conn "x" {} {:timeout-ms 2000}))))))

(deftest graceful-close-sends-shutdown-then-exit-and-kills
  (let [frames (atom [])
        killed (atom false)
        exit-seen (promise)
        killed-seen (promise)
        c2s (pipe-pair)
        s2c (pipe-pair)]
    (start-fake! (:in c2s) (:out s2c) :content-length
                 (fn [{:keys [id method]} send!]
                   (swap! frames conj method)
                   (when (= method "shutdown")
                     (send! {:jsonrpc "2.0" :id id :result nil}))
                   (when (= method "exit")
                     (deliver exit-seen true))))
    (let [conn (jsonrpc/connect-streams
                {:in (:in s2c) :out (:out c2s)
                 :kill-fn (fn []
                            (reset! killed true)
                            (deliver killed-seen true))})]
      (jsonrpc/close! conn {:graceful {:request "shutdown"
                                       :notification "exit"}})
      (is (= true (deref exit-seen 2000 false)) "exit notification sent")
      (is (= true (deref killed-seen 2000 false)) "kill hook ran")
      (is (= ["shutdown" "exit"] @frames)
          "shutdown request then exit notification, in order")
      (is (true? @killed) "kill-fn invoked after the dance")
      (is (false? (jsonrpc/alive? conn))))))

;; ─── Default close! kill path (subprocess) ────────────────────────────────

(defn- wait-for-death
  "Poll until the process behind CONN is gone — kill delivery is async.
   True when dead within ~3s."
  [conn]
  (loop [waits 0]
    (cond
      (not (proc/alive? (:proc conn))) true
      (> waits 30) false
      :else (do (Thread/sleep 100) (recur (inc waits))))))

(deftest ^:slow close!-kills-the-child-by-default
  ;; Regression: the default :kill-fn used to be 1-arity while close!
  ;; calls it with no arguments; the ArityException was swallowed and the
  ;; child leaked. A child that never reads stdin cannot exit on EOF, so
  ;; only a real kill ends it.
  (let [conn (jsonrpc/connect-stdio
              {:command "bb"
               :args ["-e" "(Thread/sleep 30000)"]
               :framing :line-delimited})]
    (try
      (is (jsonrpc/alive? conn) "child is alive before close!")
      (jsonrpc/close! conn)
      (is (wait-for-death conn) "default close! kills the child")
      (finally
        (when (and (:pid conn) (proc/alive? (:proc conn)))
          (process/kill-process-tree! (:pid conn)))))))

(deftest request!-accepts-a-caller-allocated-id
  (let [seen (atom nil)]
    (run-with-conn
     :line-delimited
     (fn [msg send!]
       (reset! seen msg)
       (send! {:jsonrpc "2.0" :id (:id msg) :result :ok}))
     (fn [conn]
       (is (= "ok" (jsonrpc/request! conn "x" {} {:id 42}))
           "a caller that must cancel the request later allocates its id")
       (is (= 42 (:id @seen)))
       (is (= "ok" (jsonrpc/request! conn "x" {})))
       (is (= 1 (:id @seen))
           "later requests allocate from the conn's own counter")))))

(deftest ^:slow no-deadline-request-waits-for-the-answer
  ;; the sentinel must bypass the deadline entirely: a plain request with
  ;; the same timing times out, while no-deadline waits for the late answer
  (run-with-conn
   :line-delimited
   (fn [{:keys [id]} send!]
     ;; answer from the fake's own reader thread: a second writer thread
     ;; would break the pipe the moment it dies
     (Thread/sleep 150)
     (send! {:jsonrpc "2.0" :id id :result {:late id}}))
   (fn [conn]
     (is (thrown-with-msg? Exception #"timed out"
                           (jsonrpc/request! conn "slow" {} {:timeout-ms 50})))
     (is (= {:late 2} (jsonrpc/request! conn "slow" {}
                                        {:timeout-ms jsonrpc/no-deadline}))
         "no-deadline waits instead of dropping the pending entry"))))
