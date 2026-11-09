;; kmet.libs.nrepl — nREPL client shared by the clojure extension tool and
;; eval's clj/nrepl-eval verb.
;;
;; Ported from clojure-mcp-light's nrepl-client / nrepl-eval (EPL-2.0, the
;; client behind clj-nrepl-eval), with two differences kmet's portable
;; surface forces:
;;
;;   * the bencode codec is pure Clojure over byte arrays. Babashka's bundled
;;     bencode.core is not part of kmet's portable surface and jolt
;;     has no copy, so the wire format is spoken directly.
;;   * no session files — sessions persist in memory for the life of the kmet
;;     process, keyed by host:port.
;;
;; Everything stays loadable on both hosts: java.net.Socket and its
;; streams exist on babashka and on jolt's jolt.socket library. The connect
;; budget and the read deadline use Socket.connect's timeout and
;; Socket.setSoTimeout, both honoured on Jolt (its Windows sockets are
;; blocking, so the read timeout is stored but not enforced there and the
;; deadline stays best-effort). A cancel request is host-independent: a
;; watcher thread closes the socket, which unblocks a read even where the
;; read deadline cannot fire.

(ns kmet.libs.nrepl
  "nREPL client: bencode codec, port discovery, persistent in-memory sessions,
   and code evaluation with an interrupt-on-timeout deadline and an optional
   cancel predicate that closes the connection."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; bencode: encode
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- utf8-bytes ^bytes [s]
  (.getBytes ^String (str s) "UTF-8"))

(defn- utf8-str ^String [^bytes bs]
  (String. bs "UTF-8"))

(defn- concat-bytes ^bytes [parts]
  (let [parts (vec parts)
        total (loop [i 0 n 0]
                (if (< i (count parts))
                  (recur (inc i) (+ n (count (nth parts i))))
                  n))
        out (byte-array total)]
    (loop [i 0 offset 0]
      (when (< i (count parts))
        (let [part (nth parts i)
              len (count part)]
          (System/arraycopy part 0 out offset len)
          (recur (inc i) (+ offset len)))))
    out))

(defn- bencode-bytes ^bytes [v]
  (cond
    (integer? v) (utf8-bytes (str "i" v "e"))
    (string? v) (let [bs (utf8-bytes v)]
                  (concat-bytes [(utf8-bytes (str (count bs) ":")) bs]))
    (keyword? v) (bencode-bytes (name v))
    (symbol? v) (bencode-bytes (str v))
    (map? v) (concat-bytes
              (into [(utf8-bytes "d")]
                    (concat (mapcat (fn [[k val]]
                                      [(bencode-bytes (if (keyword? k) (name k) k))
                                       (bencode-bytes val)])
                                    (sort-by (comp str key) v))
                            [(utf8-bytes "e")])))
    (sequential? v) (concat-bytes
                     (into [(utf8-bytes "l")]
                           (concat (map bencode-bytes v) [(utf8-bytes "e")])))
    (nil? v) (utf8-bytes "0:")
    :else (bencode-bytes (str v))))

(defn encode-message
  "Bencode a message map into a byte array. Keyword keys become the plain
   names nREPL expects (`:op` → `\"op\"`); map keys are sorted so equal
   messages encode to equal bytes."
  ^bytes [msg]
  (bencode-bytes msg))

;; ═══════════════════════════════════════════════════════════════════════════════
;; bencode: decode
;; ═══════════════════════════════════════════════════════════════════════════════

(def ^:private eof ::eof)
(def ^:private end ::end)

(defn byte-source
  "A 0-arg fn returning the next unsigned byte (0-255) from IN, or nil at
   EOF. Reads are buffered in 16 KiB chunks; the source is stateful and
   meant to be read sequentially."
  [in]
  (let [buf (byte-array 16384)
        pos (volatile! 0)
        len (volatile! 0)]
    (fn []
      (loop []
        (if (< @pos @len)
          (let [b (bit-and (aget buf @pos) 0xff)]
            (vswap! pos inc)
            b)
          (let [n (.read in buf)]
            (if (or (nil? n) (neg? n))
              nil
              (if (zero? n)
                (recur)
                (do (vreset! pos 0) (vreset! len n) (recur))))))))))

(defn- read-byte! [next-byte what]
  (or (next-byte)
      (throw (ex-info (str "Unexpected end of nREPL response while reading " what)
                      {:type :nrepl-decode}))))

(defn- read-n-bytes ^bytes [next-byte n what]
  (let [out (byte-array n)]
    (dotimes [i n]
      (aset out i (unchecked-byte (read-byte! next-byte what))))
    out))

(defn- digit? [b] (and (<= 0x30 b) (<= b 0x39)))

(defn- read-int [next-byte]
  (loop [acc 0 negative? false first? true]
    (let [b (read-byte! next-byte "an integer")]
      (cond
        (= b 0x65) (if negative? (- acc) acc)          ;; 'e'
        (and (= b 0x2d) first?) (recur acc true false) ;; '-'
        (digit? b) (recur (+ (* acc 10) (- b 0x30)) negative? false)
        :else (throw (ex-info (str "Malformed bencode integer byte: " b)
                              {:type :nrepl-decode}))))))

(defn- read-string-value [next-byte first-digit]
  (let [n (loop [acc (- first-digit 0x30)]
            (let [b (read-byte! next-byte "a string length")]
              (if (digit? b)
                (recur (+ (* acc 10) (- b 0x30)))
                (if (= b 0x3a)                            ;; ':'
                  acc
                  (throw (ex-info (str "Malformed bencode string length byte: " b)
                                  {:type :nrepl-decode}))))))]
    (utf8-str (read-n-bytes next-byte n "a string"))))

(declare read-value)

(defn- read-list [next-byte]
  (loop [acc []]
    (let [v (read-value next-byte)]
      (cond
        (= v end) acc
        (= v eof) (throw (ex-info "Unexpected end of nREPL response in a list"
                                  {:type :nrepl-decode}))
        :else (recur (conj acc v))))))

(defn- read-dict [next-byte]
  (loop [acc {}]
    (let [k (read-value next-byte)]
      (cond
        (= k end) acc
        (= k eof) (throw (ex-info "Unexpected end of nREPL response in a dict"
                                  {:type :nrepl-decode}))
        :else (let [v (read-value next-byte)]
                (if (= v eof)
                  (throw (ex-info "Unexpected end of nREPL response in a dict value"
                                  {:type :nrepl-decode}))
                  (recur (assoc acc (if (string? k) k (str k)) v))))))))

(defn- read-value [next-byte]
  (let [b (next-byte)]
    (cond
      (nil? b) eof
      (= b 0x69) (read-int next-byte)          ;; 'i'
      (= b 0x6c) (read-list next-byte)         ;; 'l'
      (= b 0x64) (read-dict next-byte)         ;; 'd'
      (digit? b) (read-string-value next-byte b)
      (= b 0x65) end                            ;; 'e'
      :else (throw (ex-info (str "Malformed bencode byte: " b)
                            {:type :nrepl-decode})))))

(defn read-message
  "Read one bencode message from NEXT-BYTE (a byte-source). Returns a map
   whose top-level keys are keywords; nested maps keep string keys, as the
   wire carries them. Returns nil at a clean EOF."
  [next-byte]
  (let [v (read-value next-byte)]
    (cond
      (= v eof) nil
      (= v end) (throw (ex-info "Unexpected bencode terminator outside a container"
                                {:type :nrepl-decode}))
      (map? v) (into {} (map (fn [[k val]] [(keyword k) val])) v)
      :else (throw (ex-info "nREPL message is not a bencode dict" {:type :nrepl-decode})))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Message accumulation
;; ═══════════════════════════════════════════════════════════════════════════════

(defn merge-messages
  "Merge response messages the way nREPL clients do: string values (`:out`,
   `:err`) concatenate, `:value` accumulates in order, `:status` becomes a
   set, and the last `:ns`/`:session`/`:id` wins. `:new-session`, `:versions`,
   `:sessions`, `:ex` and `:root-ex` are kept as received."
  [msgs]
  (reduce (fn [acc m]
            (cond-> acc
              (some? (:value m)) (update :values conj (:value m))
              (some? (:out m)) (update :out str (:out m))
              (some? (:err m)) (update :err str (:err m))
              (some? (:new-session m)) (assoc :new-session (:new-session m))
              (some? (:versions m)) (assoc :versions (:versions m))
              (some? (:sessions m)) (assoc :sessions (:sessions m))
              (some? (:ex m)) (assoc :ex (:ex m))
              (some? (:root-ex m)) (assoc :root-ex (:root-ex m))
              (some? (:ns m)) (assoc :ns (:ns m))
              (some? (:session m)) (assoc :session (:session m))
              (seq (:status m)) (update :status into (:status m))))
          {:values [] :out "" :err "" :status #{}}
          msgs))

(defn message-events
  "The recognized parts of MSGS in arrival order, as [kind text] pairs —
   `[:out s]`, `[:err s]`, `[:value s]` — so the caller can render output
   interleaved with values the way the REPL produced it."
  [msgs]
  (vec (mapcat (fn [m]
                 (cond-> []
                   (some? (:out m)) (conj [:out (:out m)])
                   (some? (:err m)) (conj [:err (:err m)])
                   (some? (:value m)) (conj [:value (:value m)])))
               msgs)))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Sockets and operations
;; ═══════════════════════════════════════════════════════════════════════════════

(def default-timeout-ms
  "Default read/eval deadline in milliseconds (the clojure_eval and
   clj/nrepl-eval default)."
  120000)

(def ^:private max-int 2147483647)
(defonce ^:private id-counter (atom 0))

(defn- next-id
  "A unique-for-this-process nREPL message id. Not random-uuid: SCI's
   clojure.core has no random-uuid."
  []
  (str (concurrent/monotonic-ms) "-" (swap! id-counter inc)))

(defn parse-long*
  "parse-long for both hosts: SCI's clojure.core has no parse-long. Returns
   nil for blank or non-numeric input."
  [x]
  (try
    (Long/parseLong (str/trim (str x)))
    (catch Exception _ nil)))

(defn- coerce-port [port]
  (cond
    (number? port) (long port)
    (string? port) (parse-long* port)
    :else nil))

(defn- connect-socket [host port timeout-ms]
  (let [socket (java.net.Socket.)]
    (try
      (.connect socket (java.net.InetSocketAddress. (str host) (int port))
                (int (min (max 1 (long timeout-ms)) max-int)))
      ;; Bound the reads with the same budget before the first one;
      ;; renew-read-timeout! refreshes it against the eval deadline.
      (.setSoTimeout socket (int (min (max 1 (long timeout-ms)) max-int)))
      socket
      (catch Exception e
        (try (.close socket) (catch Exception _ nil))
        (throw (ex-info (str "Could not connect to nREPL at " host ":" port
                             " — " (or (ex-message e) (str e)))
                        {:type :nrepl-connect :host host :port port}
                        e))))))

(defn- with-connection*
  "Open a connection to HOST:PORT, call (F socket in out byte-source), and
   close the socket afterwards."
  [host port timeout-ms f]
  (let [socket (connect-socket host port timeout-ms)]
    (try
      (let [in (.getInputStream socket)
            out (.getOutputStream socket)]
        (f socket in out (byte-source in)))
      (finally
        (try (.close socket) (catch Exception _ nil))))))

(defn- send-msg! [out msg]
  (.write out (encode-message msg))
  (.flush out))

(defn- renew-read-timeout!
  "A fn setting the socket's read timeout to the time remaining before
   DEADLINE."
  [socket deadline]
  (fn []
    (let [remaining (- deadline (concurrent/monotonic-ms))]
      (.setSoTimeout socket (int (max 1 (min remaining max-int)))))))

(defn- send-op!
  "Send OP (keyword keys) with a fresh id and collect the response messages
   for it up to and including `status: done`.

   OPTS:
     :renew-timeout! — 0-arg fn run before every read (eval deadline)
     :on-timeout     — (fn [id]) invoked when a read times out (interrupt)
   Returns the message vector, or ::timeout when the read timed out."
  [out next-byte op {:keys [renew-timeout! on-timeout]}]
  (let [id (next-id)]
    (send-msg! out (assoc op :id id))
    (try
      (loop [msgs []]
        (when renew-timeout! (renew-timeout!))
        (let [m (read-message next-byte)]
          (cond
            (nil? m) (throw (ex-info "nREPL connection closed before the response finished"
                                     {:type :nrepl-closed :id id}))
            (not= (:id m) id) (recur msgs)
            (some #{"done"} (:status m)) (conj msgs m)
            :else (recur (conj msgs m)))))
      (catch java.net.SocketTimeoutException _
        (when on-timeout (on-timeout id))
        ::timeout))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Environment detection
;; ═══════════════════════════════════════════════════════════════════════════════

(defn env-from-describe
  "Best-effort nREPL environment type from a merged describe response:
   :bb, :basilisp, :shadow, or :clj."
  [describe]
  (let [versions (or (:versions describe) {})
        keys (map str (keys versions))]
    (cond
      (some #(str/includes? (str/lower-case %) "babashka") keys) :bb
      (some #(str/includes? (str/lower-case %) "basilisp") keys) :basilisp
      (some #(str/includes? (str/lower-case %) "shadow") keys) :shadow
      :else :clj)))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Port discovery
;; ═══════════════════════════════════════════════════════════════════════════════

(def port-files
  "Port files probed by port discovery, relative to the discovery directory."
  [".nrepl-port" ".shadow-cljs/nrepl.port" ".shadow-cljs/.nrepl-port"
   ".cider-nrepl.port" "nrepl-port"])

(def common-ports
  "Fallback ports probed when no port file identifies a live server, in
   probe order: kmet's task ports (7801 `bb nrepl`, 7802
   `jolt nrepl-server`, 7803 `bb nrepl-jvm`), then the conventional nREPL
   port (7888 — nREPL's documented example and jolt's built-in default) and
   the babashka nREPL default (1667 — `bb nrepl-server`)."
  [7801 7802 7803 7888 1667])

(defn- port-file-candidates [dir]
  (keep (fn [file]
          (try
            (let [path (if dir (fs/path dir file) file)]
              (when (fs/exists? path)
                (when-let [port (some->> (slurp (str path) :encoding "UTF-8")
                                         str/trim
                                         str/split-lines
                                         first
                                         coerce-port)]
                  [port file])))
            (catch Exception _ nil)))
        port-files))

(defn- responding?
  "True when HOST:PORT answers a describe op with a bencode message."
  [host port]
  (try
    (with-connection* host port 500
      (fn [_socket _in out next-byte]
        (let [msgs (send-op! out next-byte {:op "describe"} {})]
          (and (not= ::timeout msgs) (seq msgs)))))
    (catch Exception _ false)))

(defn- distinct-port-candidates
  "CANDIDATES — pairs of [port source] — with duplicate ports removed, first
   occurrence (and its source) kept."
  [candidates]
  (loop [seen #{} out [] cs (seq candidates)]
    (if cs
      (let [[port source] (first cs)]
        (if (contains? seen port)
          (recur seen out (next cs))
          (recur (conj seen port) (conj out [port source]) (next cs))))
      out)))

(defn discover-port
  "Find a running nREPL server for HOST (default 127.0.0.1): the well-known
   port files (see PORT-FILES) in DIR (default: the process working
   directory) first, then COMMON-PORTS, each candidate deduped by port and
   validated with a describe op. Returns {:host :port :source} — :source is
   the port file name or :common — or nil."
  ([] (discover-port "127.0.0.1" nil))
  ([host] (discover-port host nil))
  ([host dir]
   (let [host (or host "127.0.0.1")
         candidates (distinct-port-candidates
                     (concat (port-file-candidates dir)
                             (map (fn [port] [port :common]) common-ports)))]
     (some (fn [[port source]]
             (when (and port (responding? host port))
               {:host host :port port :source source}))
           candidates))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Sessions
;; ═══════════════════════════════════════════════════════════════════════════════

(defonce ^:private session-store
  ;; {[host port] {:session id :env kw}}
  (atom {}))

(defn- store-session! [host port session env]
  (swap! session-store assoc [host port] {:session session :env env}))

(defn- forget-session! [host port]
  (swap! session-store dissoc [host port]))

(defn- clone-session! [out next-byte renew-timeout!]
  (let [msgs (send-op! out next-byte {:op "clone"} {:renew-timeout! renew-timeout!})]
    (if (= ::timeout msgs)
      (throw (ex-info "Timed out creating an nREPL session" {:type :nrepl-timeout}))
      (or (:new-session (merge-messages msgs))
          (throw (ex-info "nREPL clone returned no new session" {:type :nrepl-error}))))))

(defn- detect-env! [out next-byte renew-timeout!]
  (try
    (let [msgs (send-op! out next-byte {:op "describe"} {:renew-timeout! renew-timeout!})]
      (if (= ::timeout msgs)
        :unknown
        (env-from-describe (merge-messages msgs))))
    (catch Exception _ :unknown)))

(defn- ensure-session!
  "The cached session for HOST:PORT, cloning one (and detecting the
   environment) on first use. Returns {:session id :env kw}."
  [host port out next-byte renew-timeout!]
  (if-let [entry (get @session-store [host port])]
    entry
    (let [session (clone-session! out next-byte renew-timeout!)
          env (detect-env! out next-byte renew-timeout!)]
      (store-session! host port session env)
      {:session session :env env})))

(defn close-sessions!
  "Best-effort: send the nREPL close op for every cached session and clear
   the cache. The clojure extension calls it on unload/reload; sessions
   created by eval's clj/nrepl-eval verb otherwise live for the process."
  []
  (doseq [[[host port] {:keys [session]}] @session-store]
    (when session
      (try
        (with-connection* host port 1000
          (fn [_socket _in out next-byte]
            (send-op! out next-byte {:op "close" :session session} {})))
        (catch Exception _ nil))))
  (reset! session-store {}))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Evaluation
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- eval-result
  [{:keys [msgs merged session env]}]
  (let [status (or (:status merged) #{})]
    {:ok? (not (contains? status "eval-error"))
     :values (:values merged)
     :out (:out merged)
     :err (:err merged)
     :ex (:ex merged)
     :root-ex (:root-ex merged)
     :ns (:ns merged)
     :status status
     :events (message-events msgs)
     :session session
     :env env}))

(defn- cancelled-result
  "The shape returned when the run's abort signal stops an evaluation."
  []
  {:ok? false
   :cancelled? true
   :values []
   :out ""
   :err ""
   :status #{}
   :events []})

(defn- watch-cancel!
  "Start a daemon thread that closes SOCKET as soon as (CANCEL) returns true,
   so a read blocked on a silent server can be cancelled on either host.
   Returns a fn that asks the watcher to stop; it exits within its poll
   interval."
  [socket cancel]
  (let [done (atom false)]
    (concurrent/spawn
     (fn []
       (while (not @done)
         (when (cancel)
           (reset! done true)
           (.close socket))
         (Thread/sleep 100))))
    (fn [] (reset! done true))))

(defn- ns-op-unsupported?
  "True when an ns op status means the switch did not happen and the
   (in-ns …) fallback should run instead."
  [status]
  (boolean (some #{"unknown-op" "error" "namespace-not-found"} status)))

(defn- fallback-ns-code [ns]
  (str "(do (clojure.core/in-ns '" ns ") nil)"))

(defn- set-session-ns!
  "Point the session at NS. The nREPL `ns` op is the fast path; when the
   server does not implement it (babashka answers unknown-op) or cannot find
   the namespace, the same switch is evaluated as `(in-ns 'NS)`, which every
   server supports.

   Returns nil on success, ::timeout on a read timeout, or the fallback
   response messages when the fallback itself failed (the caller renders
   that eval error)."
  [out next-byte session ns opts]
  (when ns
    (let [ns-msgs (send-op! out next-byte {:op "ns" :ns (str ns) :session session} opts)]
      (cond
        (= ::timeout ns-msgs) ::timeout

        (not (ns-op-unsupported? (:status (merge-messages ns-msgs)))) nil

        :else
        (let [fb-msgs (send-op! out next-byte
                                {:op "eval"
                                 :code (fallback-ns-code ns)
                                 :session session}
                                opts)]
          (cond
            (= ::timeout fb-msgs) ::timeout
            (contains? (:status (merge-messages fb-msgs)) "eval-error") fb-msgs
            :else nil))))))

(defn eval-code
  "Evaluate CODE in a persistent per-host:port nREPL session.

   OPTS: :host (default 127.0.0.1), :port (required), :ns (optional target
   namespace for the eval), :timeout-ms (default 120000), :cancel (optional
   0-arg predicate; true stops waiting for the evaluation by closing the
   connection).

   Returns {:ok? :values :out :err :ex :root-ex :ns :status :events :session
   :env}; :ok? is false for `eval-error` responses. Read timeouts send an
   nREPL interrupt and return {:timeout? true ...} merged into the shape. A
   cancel request returns {:cancelled? true ...}. Connection and protocol
   failures throw ex-info with :type :nrepl-connect, :nrepl-closed,
   :nrepl-decode, :nrepl-timeout or :nrepl-error."
  [{:keys [host port ns timeout-ms cancel code]}]
  (let [host (or host "127.0.0.1")
        port (or (coerce-port port)
                 (throw (ex-info "No nREPL port given" {:type :nrepl-error})))
        timeout-ms (or (coerce-port timeout-ms) default-timeout-ms)
        deadline (+ (concurrent/monotonic-ms) (max 1 timeout-ms))]
    (if (and cancel (cancel))
      (cancelled-result)
      (try
        (with-connection* host port timeout-ms
          (fn [socket _in out next-byte]
            (let [stop-watch! (when cancel (watch-cancel! socket cancel))
                  renew-timeout! (renew-read-timeout! socket deadline)]
              (try
                (loop [retried? false]
                  (when (and cancel (cancel))
                    (throw (ex-info "nREPL evaluation cancelled"
                                    {:type :nrepl-cancelled})))
                  (let [{:keys [session env]} (ensure-session! host port out next-byte renew-timeout!)
                        on-timeout (fn [id]
                                     (try
                                       (send-msg! out {:op "interrupt"
                                                       :session session
                                                       :interrupt-id id})
                                       (catch Exception _ nil)))
                        opts {:renew-timeout! renew-timeout! :on-timeout on-timeout}
                        ns-result (set-session-ns! out next-byte session ns opts)]
                    (cond
                      (= ::timeout ns-result)
                      (assoc (eval-result {:msgs [] :merged {} :session session :env env})
                             :ok? false :timeout? true)

                      (sequential? ns-result)
                      (assoc (eval-result {:msgs ns-result
                                           :merged (merge-messages ns-result)
                                           :session session :env env})
                             :ok? false)

                      :else
                      (let [msgs (send-op! out next-byte
                                           {:op "eval" :code (str code) :session session}
                                           opts)]
                        (cond
                          (= ::timeout msgs)
                          (assoc (eval-result {:msgs [] :merged {} :session session :env env})
                                 :ok? false :timeout? true)

                          (contains? (:status (merge-messages msgs)) "unknown-session")
                          (if retried?
                            (assoc (eval-result {:msgs msgs
                                                 :merged (merge-messages msgs)
                                                 :session session :env env})
                                   :ok? false)
                            (do (forget-session! host port)
                                (recur true)))

                          :else
                          (eval-result {:msgs msgs
                                        :merged (merge-messages msgs)
                                        :session session :env env}))))))
                (finally
                  (when stop-watch! (stop-watch!)))))))
        (catch Exception e
          (if (and cancel (cancel))
            (cancelled-result)
            (throw e)))))))
