(ns kmet.libs.http
  "The single outbound HTTP boundary for kmet and its shipped extensions
   (pi fetch with proxy support). All internal HTTP must go through this
   namespace — `babashka.http-client` requires and raw `curl` invocations
   are forbidden in consumers (enforced by an architecture test).

   Transport-neutral request API close to babashka.http-client's:

     (http/request {:url ... :method :get :headers {...} :body ...
                    :as :string          ;; :string | :bytes | :stream
                    :timeout 30000       ;; ms (nil disables, default 120s on curl path)
                    :throw? true         ;; throw on HTTP >= 400
                    :follow-redirects :normal
                    :signal cancel-atom  ;; curl path only
                    :proxy :env})        ;; :env | :none | explicit proxy map

     (http/get url opts) (http/post url opts)
     (http/request-json url opts)
     (http/abort! response) (http/close! response)

   Responses are always {:status n :headers {...} :body ...} — :headers
   lowercased string keys (babashka's convention) — regardless of
   transport. Errors are structured ex-info:

     {:type :http-error      :status :headers :body}    ;; HTTP >= 400
     {:type :transport-error :cause ...}                ;; connect/DNS/timeout/...

   The transport itself is a process-wide user knob (set-transport!):
   :platform (default) — babashka.http-client wherever it can serve, curl
   only for the fallback cases (SOCKS/https-scheme proxies); :curl — every
   request through curl.

   `transport-error-message` classifies raw JVM exceptions (the retry
   classifier's stable \"network error\" token), so callers never depend on
   transport-specific exception types.

   Proxy handling (curl semantics, ported from the retired kmet.libs.proxy):
   HTTPS_PROXY / HTTP_PROXY / ALL_PROXY / SOCKS_PROXY with upper- and
   lowercase env vars and NO_PROXY host/subdomain/port/CIDR/IPv4/IPv6
   matching. Plain http-scheme proxies route through java.net.http (the
   babashka engine); SOCKS and https-scheme (TLS-speaking) proxies route
   through curl with full status/headers parity. Proxy selection is
   transparent — callers never see the transport. `:proxy :none` forces a
   direct connection; the env seam is testable via a map (proxy-for-url)."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [kmet.libs.json :as json]
            [clojure.string :as str]
            [kmet.libs.process :as process]))

;; ─── Proxy parsing (ported from kmet.libs.proxy) ──────────────────────────

(defn- parse-proxy-url
  "Parse a proxy URL string into {:scheme :host :port :url :user :pass} or
   nil when unparsable. A missing scheme defaults to http; a missing port to
   the scheme default (1080 for SOCKS)."
  [s]
  (try
    (let [s (str/trim s)
          s (if (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://" s) s (str "http://" s))
          uri (java.net.URI. s)
          scheme (or (.getScheme uri) "http")
          ;; bare "socks" normalizes to socks5h (local DNS resolution), not
          ;; socks5: socks5 sends the target hostname to the proxy for remote
          ;; resolution, which many SOCKS servers refuse (curl 97 "Failed to
          ;; receive SOCKS response") — socks5h resolves locally and connects
          ;; by IP, so it works with any resolver, local or remote.
          scheme (if (= scheme "socks") "socks5h" scheme)
          host (.getHost uri)
          port (let [p (.getPort uri)]
                 (or (when (pos? p) p)
                     (case scheme
                       "http" 80
                       "https" 443
                       ("socks4" "socks4a" "socks5" "socks5h") 1080
                       8080)))
          userinfo (.getUserInfo uri)
          [user pass] (when (and userinfo (seq userinfo))
                        (str/split userinfo #":" 2))
          host-str (if (str/includes? host ":") (str "[" host "]") host)
          url (str scheme "://"
                   (when user (str user (when pass (str ":" pass)) "@"))
                   host-str ":" port)]
      (when (seq host)
        {:scheme scheme :host host :port port :user user :pass pass :url url}))
    (catch Exception _ nil)))

(defn- env-first
  "First non-blank value of the given env var names (upper- then lowercase,
   like curl)."
  [env & names]
  (some (fn [n] (let [v (get env n)] (when (seq v) v))) names))

(defn- parse-socks-proxy-url
  "Parse a SOCKS proxy URL; a missing scheme defaults to socks5h — the
   SOCKS_PROXY var usually carries a bare host:port (unlike the http(s)
   vars' scheme-less http default)."
  [s]
  (if (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://" (str/trim s))
    (parse-proxy-url s)
    (parse-proxy-url (str "socks5h://" s))))

(defn- proxy-config
  [env]
  {:https (some-> (env-first env "HTTPS_PROXY" "https_proxy") parse-proxy-url)
   :http (some-> (env-first env "HTTP_PROXY" "http_proxy") parse-proxy-url)
   :all (some-> (env-first env "ALL_PROXY" "all_proxy") parse-proxy-url)
   :socks (some-> (env-first env "SOCKS_PROXY" "socks_proxy") parse-socks-proxy-url)
   :no-proxy (when-let [v (env-first env "NO_PROXY" "no_proxy")]
               (mapv str/trim (str/split v #",")))})

;; ─── no_proxy matching (curl semantics) ───────────────────────────────────

(defn- host-match?
  "curl-style hostname matching: `*` matches everything; an entry matches the
   host and its subdomains; a leading dot is ignored (.example.com matches
   example.com and www.example.com)."
  [entry host]
  (let [entry (str/lower-case (if (str/starts-with? entry ".")
                                (subs entry 1)
                                entry))
        host (str/lower-case host)]
    (or (= entry "*")
        (= host entry)
        (str/ends-with? host (str "." entry)))))

(defn- ipv4->int
  "IPv4 dotted string to 32-bit integer."
  [ip]
  (reduce (fn [acc o] (+ (* acc 256) o)) 0
          (map #(Integer/parseInt %) (str/split ip #"\."))))

(defn- cidr-match?
  "True when ip is inside the CIDR network (e.g. 172.30.208.0/20)."
  [ip cidr]
  (try
    (let [[net prefix] (str/split cidr #"/" 2)
          prefix (Integer/parseInt prefix)
          mask (cond
                 (>= prefix 32) -1
                 (zero? prefix) 0
                 :else (bit-shift-left -1 (- 32 prefix)))]
      (and (<= prefix 32)
           (>= prefix 0)
           (== (bit-and (ipv4->int ip) mask)
               (bit-and (ipv4->int net) mask))))
    (catch Exception _ false)))

(defn- entry-matches?
  "True when a NO_PROXY entry matches host:port. An entry with a port matches
   only that port; CIDR entries match by IP; IPv6 hosts must be bracketed
   ([::1] or [::1]:8080 — curl's no_proxy syntax)."
  [entry host port]
  (if (str/starts-with? entry "[")
    ;; bracketed IPv6: [addr] or [addr]:port
    (let [[host-part port-part] (str/split entry #"\]" 2)
          host-part (subs host-part 1)
          port-part (some-> port-part (str/replace #"^:" "") not-empty)]
      (and (host-match? host-part host)
           (or (nil? port-part) (= (str port) port-part))))
    (let [[host-part port-part] (str/split entry #":" 2)
          host-part (str/replace host-part #"^\[|\]$" "")]
      (and (if (str/includes? host-part "/")
             (cidr-match? host host-part)
             (host-match? host-part host))
           (or (nil? port-part) (= (str port) port-part))))))

(defn no-proxy-match?
  "True when host:port is exempted from proxying by NO_PROXY entries."
  [entries host port]
  (boolean (some #(entry-matches? % host port) entries)))

(defn proxy-for-url
  "The proxy to use for a URL, from the environment (curl semantics):
   HTTPS_PROXY / HTTP_PROXY, falling back to SOCKS_PROXY then ALL_PROXY,
   honoring NO_PROXY. Returns a parsed proxy map
   {:scheme :host :port :url :user :pass} or nil.
   env — an env map (defaults to System/getenv; pass a map in tests)."
  ([url] (proxy-for-url url (System/getenv)))
  ([url env]
   (let [cfg (proxy-config env)
         uri (java.net.URI. url)
         scheme (.getScheme uri)
         host (.getHost uri)
         port (let [p (.getPort uri)]
                (if (pos? p) p (if (= "https" scheme) 443 80)))]
     (when (and host (not (no-proxy-match? (:no-proxy cfg) host port)))
       (if (= "https" scheme)
         (or (:https cfg) (:socks cfg) (:all cfg))
         (or (:http cfg) (:socks cfg) (:all cfg)))))))

(defn curl-proxy?
  "True when the proxy needs curl: java.net.http supports only plain HTTP
   proxies, so SOCKS (any socks* scheme) and https-scheme (TLS-speaking)
   proxies are transported via curl."
  [p]
  (not= "http" (:scheme p)))

;; ─── Error classification (moved from kmet.ai.api.shared) ─────────────────

(def network-exception-classes
  "JVM exception classes indicating a transport/network failure (connect,
   DNS, timeout, reset). java.net.http can throw these with a nil message
   (e.g. ConnectException on this JDK), so they are classified by class."
  #{"ConnectException" "UnknownHostException" "NoRouteToHostException"
    "UnresolvedAddressException" "SocketTimeoutException"
    "HttpTimeoutException" "SocketException"})

(def http2-stream-reset-regex
  "Message pattern for HTTP/2 stream resets. java.net.http surfaces a server
   RST_STREAM frame as a plain java.io.IOException whose message is
   'Received RST_STREAM: <code>' (e.g. 'Protocol error', 'CANCEL') — the
   class is too broad to add to network-exception-classes, so these are
   classified by message."
  (re-pattern "(?i)received rst_stream"))

(def ^:private max-error-body-chars 2000)

(defn- error-body-text
  "The response body as a bounded string for error messages: a string body
   passes through (capped); an unconsumed InputStream (babashka :as :stream
   responses) is slurped."
  [body]
  (cond
    (string? body) body
    (instance? java.io.InputStream body)
    (try (slurp body) (catch Exception _ nil))
    :else nil))

(defn http-error-message
  "Best-effort extraction of a provider's error message from an exception
   with :status/:body ex-data (babashka.http-client's exceptional-status
   shape, or this lib's http-error). OpenAI-compatible providers send
   {\"error\": {\"message\": ...}} (Anthropic and Gemini use the same shape);
   the :msg alias covers the rest. A plain text body passes through trimmed
   (capped); nil keeps the caller's fallback.

   429/5xx messages are prefixed with 'HTTP <status>: ' — opaque bodies rely
   on the retry classifier's '429'/'500'/... patterns to auto-retry
   transient failures. 4xx stays unprefixed so overflow/quota classification
   is untouched."
  [e]
  (let [d (ex-data e)
        status (:status d)
        body (:body d)]
    (when (and (integer? status) (>= status 400))
      (let [text (some-> (error-body-text body) str/trim)
            parsed (try (json/parse-string text true) (catch Exception _ nil))
            pick (fn [s] (let [t (some-> s str str/trim)] (when (seq t) t)))
            msg (or (pick (some-> parsed :error :message))
                    (pick (some-> parsed :error :msg))
                    (when (seq text)
                      (subs text 0 (min max-error-body-chars (count text)))))]
        (when msg
          (if (or (= status 429) (>= status 500))
            (str "HTTP " status ": " msg)
            msg))))))

(defn transport-error-message
  "Message for a transport-layer exception. Network failures carry a stable
   'network error' token so the loop's retry classifier (retryable-error?)
   recognizes them even when the JVM message is nil — 'Request failed:
   ConnectException' matches no retryable pattern, which silently kills
   auto-retry on connect/DNS failures (pi's undici always reports transport
   failures as 'fetch failed'). HTTP error responses surface the provider's
   message from the response body so overflow/throttle classifiers see the
   real error — prefixed with 'HTTP <status>: ' on 429/5xx so the
   classifier's status-code patterns match even opaque bodies. Errors
   already wrapped by this lib (their message was classified at wrap time)
   pass through unchanged. Non-network exceptions keep their message."
  [e]
  (let [msg (ex-message e)
        cls (some-> (class e) .getSimpleName)
        http-msg (http-error-message e)]
    (cond
      (= :transport-error (:type (ex-data e))) msg
      http-msg http-msg
      (contains? network-exception-classes cls)
      (str "network error: " (if (str/blank? msg) cls msg))
      (re-find http2-stream-reset-regex (or msg ""))
      (str "network error: " (or msg cls))
      (str/blank? msg)
      (str "Request failed: " cls)
      :else msg)))

(defn- transport-error
  "Wrap a transport failure in the lib's structured ex-info (the classified
   message is the ex-message; the original is the ex-info cause)."
  [e]
  (ex-info (transport-error-message e) {:type :transport-error :cause e} e))

(defn- http-error
  "Structured ex-info for an HTTP >= 400 response (message extracted from
   the body via http-error-message, full body in ex-data)."
  [status headers body]
  (ex-info (or (http-error-message {:type :http-error :status status :body body})
               (str "HTTP " status))
           {:type :http-error :status status :headers headers :body body}))

;; ─── Transport: java.net.http (babashka.http-client) ─────────────────────
;; In :platform mode both hosts use babashka.http-client — natively on
;; bb/JVM, unmodified over the jolt-lang/http-client shims on Jolt — and
;; only the SOCKS/stream carve-outs route through the curl path below
;; (:curl mode routes everything there).
(def ^:private client-cache
  "babashka.http-client clients keyed by [proxy mode], so repeated
   requests reuse connections instead of rebuilding a client per call.
   Private — callers never see clients."
  (atom {}))

(defn- java-client
  "A babashka.http-client client routing through an HTTP proxy (used
   for http/https proxies; SOCKS goes through curl instead)."
  [p mode]
  (http/client (cond-> {:proxy {:type :http :host (:host p) :port (:port p)}
                        :follow-redirects mode}
                 (:user p) (assoc :authenticator
                                  (cond-> {:user (:user p)}
                                    (:pass p) (assoc :pass (:pass p)))))))

(defn- client-for
  "The cached client for a proxy map (nil = direct connection) and
   follow mode. Direct clients start from http/default-client-opts so
   they keep the implicit default's headers (accept, gzip, user-agent)."
  [p mode]
  (let [key [(when p [(:url p) (:scheme p) (:user p) (:pass p)]) mode]]
    (or (get @client-cache key)
        (let [c (if p
                  (java-client p mode)
                  (http/client (assoc http/default-client-opts
                                      :follow-redirects mode)))]
          (swap! client-cache assoc key c)
          c))))

(defn- native-request
  "One request over java.net.http. THROW? is applied here so both
   transports raise the same http-error shape; transport exceptions are
   wrapped (babashka surfaces them as raw JVM exceptions with no
   ex-data). P is the resolved proxy (nil = direct) — the request
   routes through the cached client for that proxy."
  [opts throw? p]
  (let [native-opts (cond-> (dissoc opts :url :throw? :signal :proxy :follow-redirects)
                      true (assoc :throw false))]
    (try
      (let [resp (http/request (assoc native-opts
                                      :url (:url opts)
                                      :client (client-for p (:follow-redirects opts))))
            status (:status resp)]
        (if (and throw? (>= status 400))
          (throw (http-error status (:headers resp) (:body resp)))
          {:status status :headers (:headers resp) :body (:body resp)}))
    ;; an http-error thrown above must pass through unwrapped
      (catch clojure.lang.ExceptionInfo e
        (if (= :http-error (:type (ex-data e)))
          (throw e)
          (throw (transport-error e))))
      (catch Exception e
        (throw (transport-error e))))))

;; ─── Transport: curl (SOCKS / https-scheme proxies) ───────────────────────

(def ^:private curl-timeout-seconds 120)

(defn- path-dirs
  "PATH entries split on the OS separator. Jolt answers
   java.io.File/pathSeparator as \":\" even on Windows (its File surface
   is POSIX-shaped), so babashka.fs/exec-paths — and the default `which`
   lookup — are wrong there; key off the OS instead."
  []
  (remove str/blank? (str/split (or (System/getenv "PATH") "")
                                (if process/windows-os? #";" #":"))))

(def curl-available?
  "Resolved once: true when curl is on PATH (the curl transport needs it
   — every request in :curl mode, SOCKS/https-scheme proxies and Jolt
   streams in :platform mode)."
  (delay
    (try
      ;; fs/which honors Windows' PATHEXT (exe/com/bat/cmd) and the
      ;; executable bit elsewhere; explicit :paths because exec-paths is
      ;; jolt-broken on Windows (see path-dirs). No shell: Jolt on Windows
      ;; cannot spawn one either.
      (some? (fs/which "curl" {:paths (path-dirs)}))
      (catch Exception _ false))))

(defn- watch-cancel!
  "Kill a process tree within ~200ms of the cancel signal firing mid-stream
   (the bash tool's signal-poller pattern — the sse read loop itself only
   stops *processing* lines on signal and keeps reading until EOF). Exits
   without killing when the process has already finished. A plain daemon
   thread (not future) so the curl path also works from extension SCI
   contexts."
  [{:keys [proc pid]} signal]
  (when (and signal pid)
    (let [alive? #(-> proc :proc .isAlive)]
      (doto (Thread.
             (fn []
               (loop []
                 (when (and (not @signal) (alive?))
                   (Thread/sleep 200)
                   (recur)))
               (when (and @signal (alive?))
                 (process/kill-process-tree! pid))))
        (.setDaemon true)
        (.start)))))

(defn- temp-dir
  "A writable temp directory for curl's config/header files: $TMPDIR when
   set, else java.io.tmpdir. createTempFile without an explicit dir uses
   java.io.tmpdir, which this babashka hardcodes to /tmp even when TMPDIR
   is set — and there is no /tmp on Termux (AGENTS.md), so the curl path
   would fail there without the explicit dir."
  []
  (let [dir (or (System/getenv "TMPDIR")
                (System/getProperty "java.io.tmpdir")
                "/data/data/com.termux/files/usr/tmp")
        f (java.io.File. dir)]
    (when-not (.exists f)
      (.mkdirs f))
    f))

(defn- curl-config-file
  "A temp curl --config file carrying the sensitive request parts — headers
   (Authorization, proxy credentials) must never appear in process argv
   (visible via ps on shared hosts). The file is owner-only (Java's
   createTempFile default) and deleted by close!/the sync path. The proxy
   itself rides --proxy on argv (see curl-argv); only proxy-user lives here."
  [headers p]
  (let [f (java.io.File/createTempFile "kmet-curl-" ".conf" (temp-dir))
        escape (fn [s] (str/replace s #"([\\\"])" "\\\\$1"))
        lines (concat
               (map (fn [[k v]] (str "header = \"" (escape (str k ": " v)) "\"")) headers)
               (when (:user p)
                 [(str "proxy-user = \"" (escape (str (:user p) (when (:pass p) (str ":" (:pass p))))) "\"")]))]
    (.deleteOnExit f)
    (spit f (str/join "\n" lines))
    f))

(defn- follow-redirects?
  "True unless the caller explicitly disabled following (:never or false).
   The default is to follow, so an absent key follows too. Single source
   of truth for curl-argv (-L) and the header-wait loop."
  [opts]
  (not (contains? #{false :never} (:follow-redirects opts))))

(defn- normalize-follow-redirects
  "Canonical :follow-redirects for the transports: true follows by default
   (:normal), false never follows (:never), an absent key defaults to
   :normal. Keywords pass through. Normalized once in request so both
   transports see the same mode."
  [opts]
  (update opts :follow-redirects
          (fn [fr] (cond (true? fr) :normal
                         (false? fr) :never
                         (nil? fr) :normal
                         :else fr))))

(defn- proxy-clean-url
  "The proxy URL without credentials (scheme://host:port, IPv6 bracketed)
   for curl's --proxy argv switch: set explicitly on the command line so
   the user environment's proxy variables can never leak in. Credentials
   travel via proxy-user in the config file, never argv (see
   curl-config-file and test-curl-no-credentials-in-argv)."
  [p]
  (let [bare (str/replace (or (:host p) "") #"^\[|\]$" "")
        host-str (if (str/includes? bare ":") (str "[" bare "]") bare)]
    (str (:scheme p) "://" host-str ":" (:port p))))

(defn- curl-argv
  "Full curl argv. Prefixed with setsid when available so the process is its
   own group leader — kill-process-tree!'s group kill then works reliably.
   --max-time follows :timeout (fractional s, min 0.1) when set; an explicitly
   nil timeout (disabled) omits it; absent gets the curl-timeout-seconds
   default. -L unless :never (default :normal); --compressed for
   transparent gzip (babashka parity); --fail-with-body so HTTP >= 400 exits
   22 with the error body on stdout. Proxy control is explicit on argv so
   the user environment can never leak in: --proxy \"\" for direct
   requests, --proxy <clean-url> for proxied ones; --noproxy \"\" always
   (curl must not consult its own env no_proxy either). Proxy credentials
   travel via proxy-user in the config file, never argv.

   A non-GET carries --data-binary @- ONLY when :body is present: curl
   reads stdin to EOF for @-, and a nil :in never EOFs on the jolt host
   (inherited stdin), so a bodiless POST would hang forever. Without any
   data option curl sends no body and never touches stdin (and adds no
   Content-Type — --data would force the form-urlencoded default, which
   the native transport does not send for a bodyless request)."
  [url opts p config-file header-file]
  (let [timeout-ms (:timeout opts)
        max-time (cond
                   (nil? timeout-ms) nil
                   (pos? timeout-ms) (max 0.1 (/ timeout-ms 1000.0))
                   :else curl-timeout-seconds)
        get? (= :get (:method opts))
        args (into (cond-> ["curl" "-sS" "-N" "--fail-with-body"
                            "--proxy" (if p (proxy-clean-url p) "")
                            "--noproxy" ""
                            "-K" (.getPath config-file)
                            "--dump-header" (.getPath header-file)]
                     max-time (conj "--max-time" (str max-time))
                     ;; -L unless explicitly disabled (see follow-redirects?).
                     (follow-redirects? opts) (conj "-L")
                     true (conj "--compressed"))
                   (concat
                    (when-not get?
                      (let [m (str/upper-case (name (:method opts)))]
                        (if (nil? (:body opts))
                          ["-X" m]
                          ["-X" m "--data-binary" "@-"])))
                    [url]))]
    (if-let [setsid @process/setsid-path]
      (into [setsid] args)
      args)))

(defn- parse-dump-header
  "Parse curl's --dump-header output into [status headers]: the LAST
   HTTP/1.x or HTTP/2 status line (after -L redirect hops) and the header
   block that follows it, keys lowercased, duplicates joined with ', '
   (babashka's convention)."
  [f]
  (let [lines (str/split-lines (slurp f))
        status-idxs (keep-indexed (fn [i l] (when (re-find #"^HTTP/\S+\s+\d{3}" l) i))
                                  lines)
        status (when-let [i (last status-idxs)]
                 (some-> (second (re-find #"^HTTP/\S+\s+(\d{3})" (nth lines i)))
                         Long/parseLong))
        headers (if (seq status-idxs)
                  (reduce (fn [m l]
                            (if-let [[_ k v] (re-matches #"^([^:]+):\s*(.*)" l)]
                              (let [k (str/lower-case k)]
                                (if (contains? m k)
                                  (update m k str ", " v)
                                  (assoc m k v)))
                              m))
                          {}
                          (take-while #(not (str/blank? %))
                                      (drop (inc (last status-idxs)) lines)))
                  {})]
    [status headers]))

(defn- redirect-so-far?
  "True when the headers parsed so far end in a redirect with a Location:
   with -L curl will still follow it, so these are not the final headers."
  [status headers]
  (and (integer? status) (<= 300 status 399) (contains? headers "location")))

(defn- wait-for-final-headers
  "Re-parse the dump-header file until its trailing status is usable: a nil
   status (torn first write) or a redirect curl -L would still follow (a
   slow second hop otherwise surfaces as the first hop's 3xx — e.g. GitHub
   release downloads, where hop 2 can lag hop 1 by minutes behind a proxy).
   Gives up when the curl process died or the timeout budget ran out, and
   returns [status headers] as-is. The budget follows :timeout (an explicitly
   nil timeout still caps this loop at the curl default so a stalled redirect
   cannot hang the caller forever — curl itself stays unlimited)."
  [header-file proc-map opts]
  (let [t (:timeout opts)
        budget-ms (if (and (number? t) (pos? t)) t (* curl-timeout-seconds 1000))
        deadline (+ (System/currentTimeMillis) budget-ms)]
    (loop []
      (let [[status headers] (parse-dump-header header-file)
            pending? (or (nil? status)
                         (and (redirect-so-far? status headers)
                              (follow-redirects? opts)))
            alive? (try (-> proc-map :proc .isAlive) (catch Exception _ true))]
        (if (and pending? alive? (< (System/currentTimeMillis) deadline))
          (do (Thread/sleep 100) (recur))
          [status headers])))))

(defn- curl-header-file
  "A temp file for curl's --dump-header output; deleted by close!/the sync
   path. curl creates it (empty) at startup, so 'has content' is the
   headers-arrived signal."
  []
  (let [f (java.io.File/createTempFile "kmet-curl-" ".hdrs" (temp-dir))]
    (.deleteOnExit f)
    f))

(defn- wait-for-headers
  "Block until curl has written the response headers (the dump-header file
   is non-empty) or the process died (connect failure, --max-time). Returns
   true on headers; the caller then reports the transport failure."
  [header-file proc-map]
  (loop []
    (cond
      (pos? (.length header-file)) true
      (not (-> proc-map :proc .isAlive)) false
      :else (do (Thread/sleep 50) (recur)))))

(defn- curl-transport-error
  "Structured transport error for a failed curl run (connect refused, DNS,
   timeout, mid-stream cut, ...): ex-data carries :exit; the message
   carries curl's stderr, prefixed with the stable 'network error' token
   so the retry classifier recognizes it (the native path classifies by
   JVM exception class; curl failures all arrive as process exits)."
  [proc-map]
  (let [result @proc-map
        err (str/trim (or (:err result) ""))]
    (ex-info (str "network error: Proxy request failed: " err)
             {:type :transport-error :exit (:exit result) :cause err})))

(defn- cleanup-curl!
  "Untrack the pid and delete the temp files for a curl run."
  [pid header-file config-file]
  (when pid (process/untrack-pid! pid))
  (doseq [f [header-file config-file]]
    (try (.delete f) (catch Exception _ nil))))

(defn- curl-request
  "One request over curl (SOCKS / https-scheme proxies), with status and
   header parity to the native path. For :stream responses the body
   InputStream is returned with private state (:http/curl in the response
   map) — the caller reads it and calls http/close! (reaps the process,
   deletes temp files, reports transport failures) or http/abort! (kills
   the tree on cancel)."
  [url opts p throw?]
  (when-not @curl-available?
    (throw (ex-info "curl not found on PATH — required for the curl transport and SOCKS/https-scheme proxies (install curl, or switch back to the platform HTTP transport)"
                    {:type :curl-not-found})))
  (let [header-file (curl-header-file)
        config-file (curl-config-file (:headers opts) p)
        argv (curl-argv url opts p config-file header-file)
        proc-map (proc/process argv {:in (:body opts) :out :stream :err :string})
        pid (process/process-pid proc-map)
        _ (when pid (process/track-pid! pid))
        _ (watch-cancel! {:proc proc-map :pid pid} (:signal opts))
        st {:status nil :headers {} :body (:out proc-map)
            :http/curl {:proc proc-map :pid pid
                        :header-file header-file :config-file config-file
                        :signal (:signal opts)}}]
    (if (wait-for-headers header-file proc-map)
      (let [[status headers] (wait-for-final-headers header-file proc-map opts)
            st (assoc st :status status :headers headers)
            as (or (:as opts) :string)]
        (if (and throw? (>= status 400))
          ;; HTTP error up front (native parity): read the error body
          ;; (--fail-with-body put it on stdout) and throw. The slurp can
          ;; throw on a mid-body I/O failure — cleanup must still run.
          (let [text (try (slurp (:body st))
                          (finally (cleanup-curl! pid header-file config-file)))
                result @proc-map
                exit (:exit result)]
            (if (and (not (zero? exit)) (not= 22 exit))
              (throw (curl-transport-error proc-map))
              (throw (http-error status headers text))))
          (if (= :stream as)
            st
            (let [text (try (slurp (:body st))
                            (finally (cleanup-curl! pid header-file config-file)))
                  result @proc-map
                  exit (:exit result)]
              (cond
                (zero? exit)
                {:status status :headers headers
                 :body (if (= :bytes as) (.getBytes text "UTF-8") text)}

                (= 22 exit)
                (if throw?
                  (throw (http-error status headers text))
                  {:status status :headers headers :body text})

                :else (throw (curl-transport-error proc-map)))))))
      ;; Process died before headers: transport failure.
      (let [e (curl-transport-error proc-map)]
        (cleanup-curl! pid header-file config-file)
        (throw e)))))

;; ─── Transport selection (user setting) ──────────────────────────────────

(def ^:private transport-atom (atom :platform))

(def transport-modes
  "The selectable HTTP transport modes (see set-transport!): :platform and
   :curl."
  [:platform :curl])

(defn get-transport
  "The active transport mode (see set-transport!)."
  []
  @transport-atom)

(defn set-transport!
  "Choose the transport for all subsequent requests (the /settings HTTP
   transport row and the config's :http-transport key call this):
   :platform (default) — babashka.http-client wherever it can serve, curl
   fallback where it cannot (SOCKS/https-scheme proxies); :curl — every
   request through the curl transport (requires curl on PATH). Anything
   else falls back to :platform."
  [t]
  (reset! transport-atom (if (some #{t} transport-modes) t :platform)))

;; ─── Public API ───────────────────────────────────────────────────────────

(defn- resolve-proxy
  "The proxy map for a request: :none → nil (direct); :env or absent →
   proxy-for-url; an explicit parsed-proxy map → as-is."
  [url proxy-opt]
  (case proxy-opt
    :none nil
    :env (proxy-for-url url)
    nil (proxy-for-url url)
    proxy-opt))

(defn request
  "One HTTP request through the unified transport (see the ns docstring
   for the full contract). OPTS:

     :url              — required
     :method           — :get (default) | :post | :put | :delete | ...
     :headers          — map (string or keyword keys)
     :body             — string / bytes / InputStream; a map is JSON-encoded
     :as               — :string (default) | :bytes | :stream
     :timeout          — total timeout in ms (nil disables, default 120s on the curl path)
     :throw?           — true (default): HTTP >= 400 throws
                          {:type :http-error}; false returns the response
     :follow-redirects — :normal (default, follow) | :always | :never
                          (true follows, false never follows)
     :signal           — cancel atom (curl path only: kills the process
                          tree mid-stream)
     :proxy            — :env (default) | :none | explicit proxy map

   The transport is selected process-wide (set-transport!): :platform
   (default) or :curl (everything through curl). Returns {:status n
   :headers {...} :body ...}. Transport failures throw
   {:type :transport-error} (wrapping the cause). Stream responses (:as
   :stream) must be finished with http/close! (after reading, or to
   abandon) or http/abort! (cancel)."
  [opts]
  (let [opts (cond-> opts
               (and (map? (:body opts)) (not (instance? java.io.InputStream (:body opts))))
               (update :body json/generate-string))
        throw? (if (contains? opts :throw?) (:throw? opts) true)
        opts (-> opts
                 (assoc :throw? nil) ;; strip, normalized below
                 (normalize-follow-redirects))
        p (resolve-proxy (:url opts) (:proxy opts))
        ;; :curl mode routes every request through curl-request (the
        ;; user's explicit choice); platform mode uses the native
        ;; babashka.http-client transport unless the proxy needs curl.
        curl? (or (= :curl @transport-atom) (and p (curl-proxy? p)))]
        ;; babashka.http-client runs unmodified over the jolt-lang/http-client
        ;; java.net.http shims (RFC 0014) — the native transport works for
        ;; direct and http-proxy traffic on both hosts. SOCKS and https-scheme
        ;; proxies go through curl on both hosts (curl-proxy? above).
    (if curl?
      (curl-request (:url opts) opts p throw?)
      (native-request opts throw? p))))

#_{:clj-kondo/ignore [:redefined-var]}
(defn get
  "GET url (see request for OPTS)."
  [url opts]
  (request (assoc opts :url url :method :get)))

(defn post
  "POST url (see request for OPTS)."
  [url opts]
  (request (assoc opts :url url :method :post)))

(defn request-json
  "request expecting a JSON response: a map :body is JSON-encoded
   (Content-Type is not set — callers add it), the response body is parsed
   (a nil/empty body parses to nil). :method defaults to :post (the OAuth
   flows' convention). Throws http-error on non-2xx with the raw body in
   ex-data."
  ([url] (request-json url {}))
  ([url opts]
   (let [opts (assoc opts :url url :method (or (:method opts) :post))
         resp (request opts)]
     (assoc resp :body (when (seq (:body resp))
                         (json/parse-string (:body resp) true))))))

(defn abort!
  "Cancel a stream response: kills the curl process tree (the sse read
   loop's abort-fn, so a blocked read releases). No-op for native
   (java.net.http) responses — close! aborts those."
  [response]
  (when-let [pid (:pid (:http/curl response))]
    (process/kill-process-tree! pid)))

(defn close!
  "Reap a response's transport. For curl-backed responses: kills the
   process tree (abandon semantics — the caller is done with the body),
   closes the body stream, waits for the process, untracks the pid and
   deletes the temp files, then reports transport failures (curl exit not
   in 0/22, unless the cancel signal fired) by throwing
   {:type :transport-error} — callers that consumed a stream must call
   this before reporting success so a mid-stream cut surfaces as an
   error instead of a false clean EOF. For native responses: closes the
   body stream if closeable. Idempotent."
  [response]
  (if-let [st (:http/curl response)]
    (let [{:keys [proc pid header-file config-file signal]} st]
      ;; kill first: curl may be blocked reading the socket (server never
      ;; finishes the body), so closing the pipe alone would not make it
      ;; exit and @proc would block forever
      (when pid (process/kill-process-tree! pid))
      (when-let [b (:body response)]
        (try (.close ^java.io.InputStream b) (catch Exception _ nil)))
      (let [result @proc
            exit (:exit result)]
        (cleanup-curl! pid header-file config-file)
        (when (and (not (and signal @signal))
                   (not (contains? #{0 22} exit)))
          (throw (ex-info (str "network error: Proxy request failed: " (str/trim (or (:err result) "")))
                          {:type :transport-error :exit exit :cause (:err result)})))))
    (when-let [b (:body response)]
      (when (instance? java.io.InputStream b)
        (try (.close b) (catch Exception _ nil)))))
  nil)
