(ns kmet.libs.jsonrpc
  "JSON-RPC 2.0 plumbing for stdio-speaking protocols (LSP, MCP), shared by
   extension adapters via the libs layer.

   Two framings:
     :content-length  LSP base protocol — `Content-Length: N\r\n\r\n` header
                      block followed by exactly N UTF-8 bytes. Byte counts are
                      computed on the encoded byte array, never on chars; reads
                      take exactly N bytes before decoding.
     :line-delimited  newline-separated JSON (MCP stdio style); non-JSON lines
                      (banners) are dropped.

   One connection = one child process (or injected streams, for tests) plus a
   daemon reader thread that classifies every incoming message:
     response     (:id matching a pending request) -> delivers its promise;
                  stale/unknown ids are dropped
     request      (:method + :id) -> answered from :on-request — its return
                  value becomes :result, nil becomes -32601 MethodNotFound,
                  a throwing handler -32603
     notification (:method, no :id) -> :on-notification, exceptions swallowed

   EOF/process death fails every pending request with ::transport-dead so
   callers can distinguish timeout from death. Writes are serialized under a
   lock; requests pipeline safely after registration. Known limitation:
   writes block (no async writer thread) — a server wedged with a full
   input pipe stalls writers indefinitely; bounded by close! closing the
   streams underneath.

   Self-contained: no requires outside kmet.libs.* — JSON via kmet.libs.json,
   the monotonic duration clock via kmet.libs.concurrent
   (kmet.libs.test-self-contained)."
  (:require [kmet.libs.json :as json]
            [kmet.libs.concurrent :as concurrent]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as proc]))

(def default-timeout-ms 30000)

(def no-deadline
  "TIMEOUT-MS sentinel for request!: wait for the response with no
   deadline at all. The caller owns the exit — the server answers when it
   is done, and close! releases the waiter (its pending correlation fails
   with ::transport-dead). For a request whose result arrives only at
   graceful close, like MCP's subscriptions/listen over stdio."
  ::no-deadline)

(def ^:private graceful-close-timeout-ms 2000)
;; sanity bound on announced Content-Length — a corrupt/garbage length must
;; not turn into a giant upfront allocation
(def ^:private max-frame-bytes (* 64 1024 1024))

;; ─── Framing ───────────────────────────────────────────────────────────────

(defn encode-content-length
  "The bytes to write for PAYLOAD under LSP base framing: Content-Length
   header counted on the UTF-8 body bytes (never chars), then the body."
  [payload]
  (let [body (.getBytes (str payload) "UTF-8")
        header (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n") "UTF-8")
        out (byte-array (+ (alength header) (alength body)))]
    (System/arraycopy header 0 out 0 (alength header))
    (System/arraycopy body 0 out (alength header) (alength body))
    out))

(defn- read-line-bytes!
  "One \\n-terminated line off IN as text (terminator excluded). Blocks;
   throws at stream end."
  [in]
  (let [buf (java.io.ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) (if (pos? (.size buf))
                     (.toString buf "UTF-8")
                     (throw (java.io.EOFException. "eof in jsonrpc stream")))
          (= b 10) (.toString buf "UTF-8")
          :else (do (.write buf b) (recur)))))))

(defn read-content-length
  "Reads one full content-length-framed message off IN and returns the
   payload string. Header parsing is case-insensitive, tolerates bare \\n
   line ends and ignores other headers (Content-Type et al). Reads exactly
   N body bytes — a short body (EOF first) throws."
  [in]
  (let [headers (loop [acc []]
                  (let [line (str/trim (read-line-bytes! in))]
                    (if (str/blank? line) acc (recur (conj acc line)))))
        len (some (fn [h]
                    (when-let [[_ v] (re-find #"(?i)^content-length:\s*(\d+)" h)]
                      (parse-long v)))
                  headers)]
    (when-not len
      (throw (ex-info "jsonrpc frame without Content-Length header"
                      {:type ::protocol-error :headers headers})))
    (when (> len max-frame-bytes)
      (throw (ex-info "jsonrpc Content-Length exceeds frame limit"
                      {:type ::protocol-error :length len
                       :limit max-frame-bytes})))
    (let [buf (byte-array len)]
      (loop [off 0]
        (when (< off len)
          (let [n (.read in buf off (- len off))]
            (when (neg? n)
              (throw (java.io.EOFException. "eof inside jsonrpc body")))
            (recur (+ off n)))))
      (String. buf "UTF-8"))))

(defn encode-line-msg
  "The bytes to write for PAYLOAD under newline-delimited framing."
  [payload]
  (.getBytes (str payload "\n") "UTF-8"))

(defn read-line-msg
  "Reads one newline-delimited message off READER; nil at EOF."
  [reader]
  (.readLine reader))

(defn- read-msg-payload
  "One framed payload string off CONN's input, per its :framing."
  [{:keys [in reader framing]}]
  (case framing
    :content-length (read-content-length in)
    :line-delimited (read-line-msg reader)))

;; ─── Connection internals ────────────────────────────────────────────────

(def eof-marker ::transport-eof)

(defn- ex-info*
  ([type msg] (ex-info msg {:type type}))
  ([type msg data] (ex-info msg (assoc data :type type))))

(defn- bump-last-used! [conn]
  (reset! (:last-used conn) (concurrent/monotonic-ms)))

(defn- write-frame!
  "Serializes MSG to JSON and writes it under CONN's framing + write
   lock — serialization happens outside, only the stream I/O is locked."
  [conn msg]
  (let [wl (:write-lock conn)
        bytes (case (:framing conn)
                :content-length (encode-content-length (json/generate-string msg))
                :line-delimited (encode-line-msg (json/generate-string msg)))]
    (locking wl
      (doto (:out conn)
        (.write bytes 0 (alength bytes))
        (.flush)))))

(defn- tail-log! [conn line]
  (swap! (:stderr-tail conn)
         (fn [lines]
           (vec (take-last (:tail-lines conn) (conj (vec lines) line))))))

(defn- fail-pending!
  "Delivers the EOF marker to every pending request and empties the map.
   Pending-map mutations share :write-lock — swap-vals! is unreliable
   under sci (the reset value is lost), so this is lock-guarded instead."
  [conn]
  (let [wl (:write-lock conn)
        ps (locking wl
             (let [ps (vals (deref (:pending conn)))]
               (reset! (:pending conn) {})
               ps))]
    (doseq [p ps] (deliver p eof-marker))))

(defn- mark-dead!
  "Marks the connection closed and fails everything in flight. Idempotent."
  [conn]
  (when (compare-and-set! (:closed conn) false true)
    (fail-pending! conn)))

(defn- reply-to-server-request!
  [conn id result-fn]
  (let [[code result]
        (try
          (let [v (result-fn)]
            (if (nil? v)
              [-32601 "Method not found"]
              [nil v]))
          (catch Throwable e
            [-32603 (str "Internal error: " (or (ex-message e) (str e)))]))]
    (try
      (write-frame! conn
                    (if code
                      {:jsonrpc "2.0" :id id
                       :error {:code code :message result}}
                      {:jsonrpc "2.0" :id id :result result}))
      (catch Exception _ nil))))

(defn- dispatch-message!
  "Classifies and routes one parsed incoming message."
  [conn msg]
  (when (map? msg)
    (cond
      (contains? msg :method)
      (if (contains? msg :id)
        (reply-to-server-request! conn (:id msg)
                                  #((:on-request conn) (:method msg) (:params msg)))
        (try
          ((:on-notification conn) msg)
          (catch Throwable e
            (tail-log! conn (str "notification handler error: "
                                 (or (ex-message e) (str e)))))))

      ;; else: a response — deliver to its waiter, drop stale ids.
      ;; Lock-guarded get+remove: swap!'s return value is the NEW map,
      ;; which by definition no longer holds the promise we need.
      :else
      (let [wl (:write-lock conn)
            p (locking wl
                (let [p (get (deref (:pending conn)) (:id msg))]
                  (swap! (:pending conn) dissoc (:id msg))
                  p))]
        (when p (deliver p msg))))))

(defn- start-reader!
  [conn]
  (let [t (Thread. (fn []
                     (loop []
                       (let [payload (try
                                       (read-msg-payload conn)
                                       (catch Exception e
                       ;; record WHY before failing everything in flight —
                       ;; a silent mark-dead! is undiagnosable from outside
                                         (tail-log! conn (str "reader error: "
                                                              (.getName (class e)) " "
                                                              (or (ex-message e) "")))
                                         ::read-error))]
                         (if (= payload ::read-error)
                           (mark-dead! conn)
                           (do (try
                                 (dispatch-message! conn (json/parse-string payload true))
                                 (catch Throwable e
                                   (tail-log! conn (str "dispatch error: "
                                                        (or (ex-message e) (str e))))))
                               (recur)))))))]
    (.setDaemon t true)
    (.start t)
    t))

(defn- start-stderr-drain!
  "Keeps the last N lines of ERR in CONN's tail for diagnostics. No-op when
   ERR is nil (stream-injected connections without stderr)."
  [conn err]
  (when err
    (let [t (Thread. (fn []
                       (try
                         (with-open [rdr (io/reader err)]
                           (doseq [line (line-seq rdr)]
                             (tail-log! conn line)))
                         (catch Exception _ nil))))]
      (.setDaemon t true)
      (.start t)
      t)))

(defn connect-streams
  "Connection over already-built streams — the seam tests use instead of a
   real subprocess. OPTS: :in (InputStream we read from), :out (OutputStream
   we write to), :framing, :on-notification, :on-request, :err (optional,
   drained into the tail), :proc (optional), :kill-fn (optional 0-arg fn,
   default destroys the tree of :proc), :tail-lines."
  [{:keys [in out framing on-notification on-request proc kill-fn tail-lines err]
    :or {framing :content-length
         on-notification (constantly nil)
         on-request (constantly nil)}}]
  (let [conn {:framing framing
              :in in
              :reader (io/reader in)
              :out out
              :proc proc
              :pid (when proc (try (-> proc :proc .pid) (catch Exception _ nil)))
              :kill-fn (or kill-fn (when proc (fn [] (proc/destroy-tree proc))))
              :on-notification on-notification
              :on-request on-request
              :pending (atom {})
              :id-counter (atom 0)
              :closed (atom false)
              :write-lock (Object.)
              :last-used (atom (concurrent/monotonic-ms))
              :stderr-tail (atom [])
              :tail-lines (or tail-lines 20)}]
    (start-reader! conn)
    (start-stderr-drain! conn err)
    conn))

(defn connect-stdio
  "Spawns COMMAND (+ARGS) and connects JSON-RPC over its stdio. OPTS are the
   connect-streams options minus the streams, plus :command (string or full-
   argv vector), :args, :env (merged over System/getenv), :cwd. Returns the
   conn map."
  [{:keys [command args env cwd] :as opts}]
  (let [argv (into (if (vector? command) command [command]) args)
        p;; NB: never (apply proc/process argv opts-map) here — apply spreads the
                 ;; map's entries into the child's argv; servers die on the
                 ;; junk args, tolerant ones mask the bug
        (proc/process argv
                      {:in :stream :out :stream :err :stream
                       :dir cwd
                       :env (merge (into {} (System/getenv)) env)})]
    (connect-streams (assoc (dissoc opts :command :args :env :cwd)
                            :in (:out p) :out (:in p) :err (:err p) :proc p))))

;; ─── Public protocol ──────────────────────────────────────────────────────

(defn alive?
  "True while the connection has neither been closed nor seen process
   death. For stream-injected conns (no :proc) this means only \"not
   explicitly closed\"."
  [conn]
  (and (not @(:closed conn))
       (or (nil? (:proc conn))
           (try (proc/alive? (:proc conn)) (catch Exception _ false)))))

(defn pid
  "The child process id, or nil for stream-injected connections."
  [conn]
  (:pid conn))

(defn last-used
  "Monotonic-ms of the last request!/notify! — the idle reaper's feed.
   Compare it against concurrent/monotonic-ms, never the wall clock."
  [conn]
  @(:last-used conn))

(defn stderr-tail
  "The last N captured stderr/log lines (oldest first)."
  [conn]
  @(:stderr-tail conn))

(defn request!
  "Sends METHOD/PARAMS and blocks for the matching response (up to
   TIMEOUT-MS, default 30000; no-deadline waits without one). Returns the
   response's :result; throws ex-info on a JSON-RPC error
   (::request-error), timeout (::timeout) or transport death
   (::transport-dead); every failure carries the request :id and :method
   in ex-data (MCP correlates a notifications/cancelled on the abandoned
   id). Late responses after a timeout are dropped as stale.

   OPTS: :timeout-ms as above, :id to use a caller-allocated id instead
   of allocating one. A caller that must reference the request later (a
   cancelled subscriptions/listen) allocates it with (swap! (:id-counter
   conn) inc) so it stays unique."
  ([conn method params] (request! conn method params {}))
  ([conn method params {:keys [timeout-ms id]}]
   (bump-last-used! conn)
   (when @(:closed conn)
     (throw (ex-info* ::transport-dead "jsonrpc connection is closed")))
   (let [id (or id (swap! (:id-counter conn) inc))
         p (promise)
         wl (:write-lock conn)]
     (locking wl (swap! (:pending conn) assoc id p))
     (try
       (write-frame! conn {:jsonrpc "2.0" :id id :method method :params params})
       (catch Exception e
         (locking wl (swap! (:pending conn) dissoc id))
         (mark-dead! conn)
         (throw (ex-info* ::transport-dead
                          (str "jsonrpc write failed: "
                               (or (ex-message e) (str e)))
                          {:id id :method method}))))
     (let [res (if (= no-deadline timeout-ms)
                 (deref p)
                 (deref p (or timeout-ms default-timeout-ms) ::timeout))]
       (cond
         (= res ::timeout)
         (do (locking wl (swap! (:pending conn) dissoc id))
             (throw (ex-info* ::timeout
                              (str "jsonrpc request timed out after "
                                   (or timeout-ms default-timeout-ms)
                                   "ms: " method)
                              {:id id :method method})))

         (= res eof-marker)
         (throw (ex-info* ::transport-dead
                          (str "jsonrpc connection closed before response: "
                               method)
                          {:id id :method method}))

         (:error res)
         (throw (ex-info (str "jsonrpc error " (-> res :error :code) ": "
                              (-> res :error :message))
                         {:type ::request-error :id id :method method
                          :error (:error res)}))

         :else (:result res))))))

(defn notify!
  "Sends a notification (no id, no reply expected). A failed write marks
   the connection dead and throws ::transport-dead, like request!."
  [conn method params]
  (bump-last-used! conn)
  (when-not @(:closed conn)
    (try
      (write-frame! conn {:jsonrpc "2.0" :method method :params params})
      (catch Exception e
        (mark-dead! conn)
        (throw (ex-info* ::transport-dead
                         (str "jsonrpc write failed: "
                              (or (ex-message e) (str e))))))))
  nil)

(defn close!
  "Closes CONN. With GRACEFUL ({:request \"shutdown\" :notification \"exit\"})
   the polite dance runs first — a bounded shutdown request, then the exit
   notification — before the process tree is killed regardless. Without it,
   kills immediately (MCP-style). Cleanup (fail-pending, kill, stream
   close) always runs, even when the transport already died on its own —
   only the graceful dance is skipped in that case (a reader-death mark
   alone would otherwise orphan the child process). Idempotent; safe
   mid-flight."
  [conn & [{:keys [graceful]}]]
  ;; the dance needs a live transport — whoever wins the flag performs it;
  ;; everything below runs unconditionally so an EOF'd connection still
  ;; gets its child killed and its streams closed exactly once per caller
  (let [dance? (compare-and-set! (:closed conn) false true)]
    (when (and graceful dance?)
      (try
        (let [id (swap! (:id-counter conn) inc)
              p (promise)
              wl (:write-lock conn)]
          (locking wl (swap! (:pending conn) assoc id p))
          (write-frame! conn {:jsonrpc "2.0" :id id
                              :method (:request graceful) :params {}})
          (deref p graceful-close-timeout-ms nil))
        (catch Exception _ nil))
      (try
        (write-frame! conn {:jsonrpc "2.0" :method (:notification graceful)})
        (catch Exception _ nil)))
    (fail-pending! conn)
    (when-let [kill (:kill-fn conn)] (try (kill) (catch Exception _ nil)))
    (doseq [k [:in :out]]
      (when-let [s (get conn k)] (try (.close s) (catch Exception _ nil))))
    nil))
