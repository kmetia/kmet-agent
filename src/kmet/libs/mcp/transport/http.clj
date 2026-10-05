(ns kmet.libs.mcp.transport.http
  "MCP streamable-HTTP transport (§7.3): per-request POST (kmet.libs.http,
   :as :stream); initialize captures Mcp-Session-Id, and later POSTs carry
   the negotiated MCP-Protocol-Version; responses are parsed by content
   type (application/json or text/event-stream). A modern conn sends no
   session header and mirrors the method (and the tool/prompt/resource
   name) in Mcp-Method/Mcp-Name routing headers.

   Conn keys: :url :session-id :protocol-version :closed :listen, plus
   the common contract keys documented in kmet.libs.mcp.transport.

   This namespace also owns the HTTP POST/parse helpers and send-async!
   that the legacy SSE transport (kmet.libs.mcp.transport.sse) reuses,
   plus the SEP-2243 custom-header helpers (x-mcp-param-headers /
   valid-x-mcp-header?) for tool parameters annotated x-mcp-header."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.http :as http]
            [kmet.libs.json :as json]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport :as transport]
            [kmet.libs.sse :as sse]))

(def ^:private spawn concurrent/spawn)

(declare send-async!)

;; ─── Conn + headers ───────────────────────────────────────────────────────

(defn connect!
  "HTTP conn: per-request POSTs. OPTS: :auth-headers (fn [] → headers map
   or nil, called per request), :on-401 (fn [] → fresh headers; called once
   per request when the first attempt answers 401), :on-notification.
   :protocol-version is filled in by the initialize handshake (nil until
   then) — see http-request-headers. :listen is the live subscription
   slot http/listen! fills in."
  [url opts]
  {:transport :streamable-http
   :url url
   :session-id (atom nil)
   :protocol-version (atom nil)
   :id-counter (atom 0)
   :closed (atom false)
   :last-used (atom (concurrent/monotonic-ms))
   :listen (atom nil)
   :auth-headers (:auth-headers opts)
   :on-401 (:on-401 opts)
   :on-notification (:on-notification opts)
   :request-fn (:request-fn opts)
   :req-lock (Object.)})

(defn- base-http-headers
  "Static headers for an MCP POST: Mcp-Session-Id and the negotiated
   MCP-Protocol-Version (both present only after a handshake). A modern
   conn has no session — the header is never mirrored, even when a
   response carried one."
  [conn]
  (cond-> {"Content-Type" "application/json"
           "Accept" "application/json, text/event-stream"}
    (and (not (protocol/modern-conn? conn)) @(:session-id conn))
    (assoc "Mcp-Session-Id" @(:session-id conn))
    (and (:protocol-version conn) @(:protocol-version conn))
    (assoc "MCP-Protocol-Version" @(:protocol-version conn))))

(defn encode-header-value
  "Encode a mirrored header value (Mcp-Name, Mcp-Param-*) for the wire
   (spec, Header Mirroring): a value that cannot ride in a header field
   value unchanged — non-ASCII or control characters, a leading or trailing
   space (parsers trim it) — or that itself begins with the Base64
   sentinel is sent as a Base64 sentinel, so it round-trips exactly."
  [v]
  (let [s (str v)]
    (if (and (not (str/starts-with? s "=?base64?"))
             (not (str/starts-with? s " "))
             (not (str/ends-with? s " "))
             (every? (fn [ch] (let [c (int ch)] (or (= c 32) (<= 33 c 126))))
                     s))
      s
      (str "=?base64?"
           (.encodeToString (java.util.Base64/getEncoder)
                            (.getBytes s "UTF-8"))
           "?="))))

(defn- name-header-value
  "The Mcp-Name value of METHOD's params: the tool or prompt name, or the
   resource URI."
  [method params]
  (case method
    ("tools/call" "prompts/get") (:name params)
    "resources/read" (:uri params)
    nil))

(defn routing-headers
  "The 2026-07-28 per-request routing headers (spec, Streamable HTTP):
   Mcp-Method on every request, Mcp-Name for the methods that name a
   tool, resource or prompt. Empty for a legacy conn."
  [conn method params]
  (if (protocol/modern-conn? conn)
    (let [name (name-header-value method params)]
      (cond-> {"Mcp-Method" method}
        name (assoc "Mcp-Name" (encode-header-value name))))
    {}))

(defn http-request-headers
  "The headers for one MCP POST. The 1-arity is the base set (base +
   auth) for callers without a JSON-RPC message (the SSE transport, a
   session DELETE); the 2-arity adds the modern routing headers; the
   3-arity additionally merges EXTRA-HEADERS, the per-request custom
   headers a tools/call derives from its x-mcp-header annotations
   (Mcp-Param-*, SEP-2243)."
  ([conn]
   (let [headers (base-http-headers conn)]
     (if-let [auth (:auth-headers conn)]
       (merge headers (or (auth) {}))
       headers)))
  ([conn method params]
   (http-request-headers conn method params nil))
  ([conn method params extra-headers]
   (merge (http-request-headers conn) (routing-headers conn method params) extra-headers)))

;; ─── Custom headers from tool parameters (SEP-2243) ──────────────────────

(def ^:private js-safe-integer-max
  "2^53 - 1 — the largest integer an IEEE-754 double represents exactly;
   the spec's safe range for a mirrored integer value."
  9007199254740991)

(def ^:private tchar-pattern
  "RFC 9110 field-name token characters (tchar)."
  #"[!#$%&'*+\-.^_`|~0-9A-Za-z]")

(def ^:private non-reachable-schema-keywords
  "JSON Schema keywords whose values are schemas but never part of a
   statically reachable property path. They are walked only to find an
   x-mcp-header annotation placed where the spec forbids it."
  [:items :prefixItems :contains :additionalItems :additionalProperties
   :propertyNames :not :if :then :else :unevaluatedItems
   :unevaluatedProperties :contentSchema :allOf :anyOf :oneOf])

(def ^:private non-reachable-schema-map-keywords
  "The same, for keywords whose values are maps of schemas."
  [:$defs :definitions :dependentSchemas :patternProperties :dependencies])

(defn- walk-x-mcp-headers
  "Every x-mcp-header annotation in SCHEMA, in traversal order, as
   {:path [property-name ...] :reachable? bool :header v :schema sub}.
   A node is REACHABLE when every edge on its path from the schema root is
   a `properties` key; only such a node is a mirrorable parameter, so its
   annotation counts and its own `properties` children stay reachable.
   Every other schema-bearing keyword (items, composition/conditional
   keywords, $ref targets under $defs/definitions) is walked as
   unreachable — its own annotation and any below it invalidate the
   definition, and its `properties` children are not instance paths. The
   root itself is not a property: an annotation there is unreachable too.
   Non-schema positions (default/enum/examples) are not walked."
  [schema]
  (letfn [(walk [node path property? chain?]
            (if-not (map? node)
              []
              (let [here (when (contains? node :x-mcp-header)
                           [{:path path :reachable? property?
                             :header (:x-mcp-header node)
                             :schema node}])
                    props (if (map? (:properties node))
                            (mapcat (fn [[k sub]] (walk sub (conj path k) chain? chain?))
                                    (:properties node))
                            [])
                    others (mapcat (fn [k]
                                     (let [v (get node k)]
                                       (cond
                                         (map? v) (walk v path false false)
                                         (sequential? v) (mapcat #(walk % path false false) v)
                                         :else [])))
                                   non-reachable-schema-keywords)
                    defs (mapcat (fn [k]
                                   (let [v (get node k)]
                                     (if (map? v)
                                       (mapcat (fn [[_ sub]] (walk sub path false false)) v)
                                       [])))
                                 non-reachable-schema-map-keywords)]
                (concat here props others defs))))]
    ;; the root is on the chain but is not itself a property subschema
    (walk schema [] false true)))

(defn- primitive-declared-type?
  "True when a schema's :type declares one or more of the spec's primitive
   header types (a vector may add null — a nullable primitive)."
  [type]
  (let [types (if (sequential? type) type [type])]
    (and (seq types)
         (every? #{"string" "integer" "boolean" "null"} types)
         (some #{"string" "integer" "boolean"} types))))

(defn- tchar-value?
  "True when V is a non-empty string of RFC 9110 tchar characters."
  [v]
  (and (string? v)
       (pos? (count v))
       (every? (fn [ch] (re-matches tchar-pattern (str ch))) (seq v))))

(defn- ascii-fold-down
  "ASCII-only case fold for the spec's case-insensitive header-name
   comparison. The names are token-validated ASCII, and folding only A-Z
   keeps the comparison locale-independent (str/lower-case follows the
   JVM default locale, where e.g. Turkish maps I to a dotless i and would
   miss a duplicate)."
  [s]
  (str/replace s #"[A-Z]" (fn [c] (str (char (+ 32 (int (first c))))))))

(defn- x-mcp-annotation-reasons
  "The constraint violations in ANNOTATIONS (SEP-2243) as messages; empty
   when the whole definition conforms."
  [annotations]
  (let [names (keep #(when (string? (:header %)) (ascii-fold-down (:header %)))
                    annotations)
        duplicates (->> names frequencies
                        (keep (fn [[n c]] (when (> c 1) n)))
                        seq)]
    (cond-> []
      (some #(not (:reachable? %)) annotations)
      (conj "an x-mcp-header is not statically reachable through properties")

      (some #(not (tchar-value? (:header %))) annotations)
      (conj "an x-mcp-header value is not an HTTP field-name token")

      (some #(not (primitive-declared-type? (:type (:schema %)))) annotations)
      (conj "an x-mcp-header is on a parameter that is not a declared primitive")

      duplicates
      (conj (str "x-mcp-header values are not case-insensitively unique: "
                 (str/join ", " duplicates))))))

(defn valid-x-mcp-header?
  "True when every x-mcp-header annotation in INPUT-SCHEMA satisfies the
   2026-07-28 constraints (SEP-2243): a non-empty HTTP token name, unique
   case-insensitively, on a property with a declared primitive type, and
   statically reachable through `properties` keys from the schema root.
   A vector :type may add null (a nullable primitive); a property with no
   declared type, or one typed number/object/array, is invalid. A
   streamable-HTTP client MUST exclude a tool whose definition violates
   them from the tools/list result; the other transports ignore the
   annotation. INPUT-SCHEMA nil or annotation-free is valid."
  [input-schema]
  (empty? (x-mcp-annotation-reasons (walk-x-mcp-headers input-schema))))

(defn- argument-value
  "The value at PATH in the call ARGUMENTS, accepting string or keyword
   keys at each step; nil when absent."
  [arguments path]
  (reduce (fn [m k]
            (if (map? m)
              (if (contains? m (keyword k))
                (get m (keyword k))
                (get m k))
              (reduced nil)))
          arguments
          path))

(defn- mirrored-value
  "The spec's string form of a mirrored parameter value, keyed on the
   value's own JSON type (the declared type is the server's constraint;
   a non-conforming caller gets its value converted, not silently
   dropped): string as-is, boolean lower-case, integer decimal. nil (the
   header is omitted) for null/absent, containers and numbers outside the
   integer safe range — an unrepresentable value cannot ride a header."
  [v]
  (cond
    (nil? v) nil
    (string? v) v
    (true? v) "true"
    (false? v) "false"
    (number? v) (when (and (== v (long v))
                           (<= (- js-safe-integer-max) v js-safe-integer-max))
                  (str (long v)))
    :else nil))

(defn x-mcp-param-headers
  "The Mcp-Param-* headers for one tools/call (SEP-2243): one header per
   statically reachable x-mcp-header annotation whose argument value is
   present and non-null, converted per the spec (string as-is, integer
   decimal, boolean lower-case) and encoded like Mcp-Name (Base64
   sentinel when unsafe). Returns {} when INPUT-SCHEMA is absent, has no
   annotations, or violates the constraints — the tool should have been
   excluded from tools/list, and emitting headers from an invalid
   definition would be guessing at its property paths."
  [input-schema arguments]
  (let [annotations (walk-x-mcp-headers input-schema)]
    (if (seq (x-mcp-annotation-reasons annotations))
      {}
      (into {}
            (keep (fn [{:keys [path header]}]
                    (when-let [value (mirrored-value (argument-value arguments path))]
                      [(str "Mcp-Param-" header) (encode-header-value value)])))
            annotations))))

;; ─── POST + response parsing ──────────────────────────────────────────────

(defn http-post!
  "POST one JSON-RPC message; retries once with fresh headers on 401 when
   the conn has an :on-401 hook. The hook receives the 401 response so it
   can read the WWW-Authenticate challenge (RFC 9728 resource metadata /
   scope) before answering."
  [conn url headers body timeout-ms]
  (let [post (or (:request-fn conn) http/post)
        attempt (fn [hs]
                  (post url
                        {:headers hs
                         :body body
                         :as :stream
                         :throw? false
                         :timeout timeout-ms}))
        response (attempt headers)]
    (if (and (= 401 (:status response)) (:on-401 conn))
      (do
        ;; the discarded 401 body is never read — reap its transport
        (http/close! response)
        ;; fresh auth over the same base/routing/custom headers: the
        ;; retry must not lose Mcp-Method/Mcp-Name or the Mcp-Param-*
        ;; headers a modern tools/call carries (SEP-2243)
        (attempt (merge headers (or ((:on-401 conn) response) {}))))
      response)))

(defn- read-sse-response
  "Read an SSE response body: collect data: payloads (event: lines set the
   event name) and return the one whose parsed JSON matches our id.
   Anything else on the stream is a server->client request or
   notification and is dispatched (§7.7); progress notifications
   stream to ON-NOTIFICATION. The server closes the stream after the
   result."
  [conn body id on-notification]
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
              (let [parsed (try (json/parse-string buf true)
                                (catch Exception _ nil))]
                (if (and (map? parsed) (= id (:id parsed)))
                  parsed
                  (do (when (map? parsed)
                        (transport/dispatch-server-message!
                         conn parsed on-notification send-async!))
                      (recur nil ""))))
              :else (recur event-name buf))))))))

(defn parse-http-response
  "Parse an HTTP response by content type into the JSON-RPC response map
   (or nil when the body is empty — a 202-accepted without a direct
   response). SSE bodies are read until the message with :id = ID arrives.
   The transport is closed on every path — by the time this returns (or
   throws) the body has been fully read or abandoned, so the curl process
   is reaped and its temp files deleted (a stream that must stay open
   beyond this call is only used by the sse GET, which manages it
   separately)."
  [conn response id on-notification]
  (try
    (let [status (:status response)
          content-type (or (transport/header-value (:headers response) "Content-Type") "")]
      (cond
        (<= 200 status 299)
        (cond
          (str/includes? content-type "text/event-stream")
          (read-sse-response conn (:body response) id on-notification)

          (str/includes? content-type "application/json")
          (let [text (slurp (:body response))]
            (when (seq (str/trim text))
              (json/parse-string text true)))

          :else
          (throw (protocol/mcp-error (str "MCP connect failed: unexpected response content type "
                                          content-type " (status " status ")")
                                     {:status status})))

        :else
        (let [text (when-let [b (:body response)]
                     (try (str/trim (slurp b)) (catch Exception _ nil)))
              error (:error (when (seq text)
                              (try (json/parse-string text true)
                                   (catch Exception _ nil))))]
          (throw (protocol/mcp-error
                  (str "MCP connect failed: HTTP " status
                       (when (seq text) (str ": " text)))
                  (cond-> {:status status}
                    ;; a JSON-RPC error body lands in the ex-data — the
                    ;; HTTP era detection inspects :code/:data
                    (map? error) (assoc :code (:code error)
                                        :message (:message error)
                                        :data (:data error))))))))
    (finally
      (try (http/close! response) (catch Exception _ nil)))))

;; ─── Request + async send ─────────────────────────────────────────────────

(def ^:private timeout-exception-classes
  "JVM exception class names that mean the transport timed out. Classified
   by name like kmet.libs.http's network-exception-classes, so the check
   stays portable across hosts."
  #{"HttpTimeoutException" "SocketTimeoutException"})

(defn- timeout-exception?
  "True when E (or a cause) is a transport timeout, or carries a 'timed
   out' message (a host may wrap the failure without preserving a
   recognizable class)."
  [e]
  (loop [e e]
    (cond
      (nil? e) false
      (contains? timeout-exception-classes (some-> (class e) .getSimpleName)) true
      (str/includes? (str (ex-message e)) "timed out") true
      :else (recur (ex-cause e)))))

(defn request!
  "POST a request and wait for its response (a background thread reads the
   body; the caller's deadline bounds the wait). OPTS is the request
   options map: :http-headers — per-request custom headers for HTTP
   transports (a tools/call's Mcp-Param-*, SEP-2243; stdio and SSE ignore
   them). On timeout the request is cancelled and the conn is marked
   closed — the abandoned response has no transport to read it."
  [conn method params timeout-ms on-notification {:keys [http-headers]}]
  (transport/touch! conn)
  (let [id (swap! (:id-counter conn) inc)
        result-p (promise)
        _ (spawn
           (fn []
             (let [value (try
                           (let [body (json/generate-string
                                       {:jsonrpc "2.0" :id id :method method :params params})
                                 response (http-post! conn (:url conn)
                                                      (http-request-headers conn method params
                                                                            http-headers)
                                                      body (+ timeout-ms 5000))]
                             ;; a modern conn has no session: a header a
                             ;; probe or discovery response carried is
                             ;; never captured (nor mirrored later)
                             (when-let [session-id (and (not (protocol/modern-conn? conn))
                                                        (transport/header-value
                                                         (:headers response)
                                                         "Mcp-Session-Id"))]
                               (reset! (:session-id conn) session-id))
                             (parse-http-response conn response id on-notification))
                           (catch Exception e
                             (if (timeout-exception? e)
                               (protocol/mcp-error
                                (str "MCP request timed out after " timeout-ms "ms: " method)
                                {:timeout-ms timeout-ms :method method})
                               e)))]
               (deliver result-p value))))
        response (deref result-p timeout-ms ::timeout)]
    (if (= ::timeout response)
      (do
        ;; The server may still complete the call; the result is lost (no
        ;; abort transport in bb) — documented limitation (§7.3). The conn
        ;; is dead after this: the client was closed. Tell the server to
        ;; stop working on the abandoned request.
        (reset! (:closed conn) true)
        (transport/cancel! conn id method "timed out" send-async!)
        (throw (protocol/mcp-error (str "MCP request timed out after " timeout-ms "ms: " method)
                                   {:timeout-ms timeout-ms :method method})))
      (if (instance? Exception response)
        (throw response)
        (if (:error response)
          (throw (protocol/mcp-error (str "MCP error " (:code (:error response)) ": "
                                          (:message (:error response)))
                                     {:code (:code (:error response))
                                      :message (:message (:error response))}))
          (if (nil? response)
            (throw (protocol/mcp-error (str "MCP connect failed: empty response to " method)
                                       {:method method}))
            (:result response)))))))

(defn send-async!
  "Deliver a JSON-RPC message that expects no answer over HTTP
   (:streamable-http POSTs to :url, :sse to its endpoint atom). The body
   is never read — the transport is reaped right away. A delivery failure
   is dropped, never surfaced (§7.7: notifications are best-effort)."
  [conn msg]
  (transport/touch! conn)
  (let [body (json/generate-string msg)]
    (try
      (let [endpoint (if (= :sse (:transport conn))
                       (or @(:endpoint-atom conn)
                           (throw (protocol/mcp-error "MCP connect failed: no SSE endpoint received"
                                                      {:transport :sse})))
                       (:url conn))
            response (http-post! conn endpoint
                                 (http-request-headers conn (:method msg) (:params msg))
                                 body 30000)]
        ;; fire-and-forget: the body is never read — reap the transport
        ;; (curl: untrack pid + delete temp files) right away
        (http/close! response))
      (catch Exception _ nil)))
  nil)

;; ─── Subscriptions (2026-07-28) ──────────────────────────────────────────

(defn- read-listen-stream!
  "Read a subscription's SSE response body until the stream ends. Every
   line read — keepalive comment lines included, which carry no frame —
   touches the conn so the idle reaper leaves a live subscription alone.
   Each parsed message goes through the subscription demux (a stale
   subscription's frame is dropped) and then through the ordinary
   server-message dispatch, so a server->client request arriving on the
   subscription stream is answered like one on a request body."
  [conn listen-atom body]
  (with-open [rdr (io/reader body)]
    (loop [event-name nil buf ""]
      (let [line (.readLine rdr)]
        (cond
          (nil? line) nil
          :else
          (do
            (transport/touch! conn)
            (let [[ev data] (sse/parse-sse-line line)]
              (cond
                ev (recur ev buf)
                data (recur event-name (sse/append-data buf data))
                (and (str/blank? line) (seq buf))
                (let [parsed (try (json/parse-string buf true)
                                  (catch Exception _ nil))]
                  (when (and (map? parsed)
                             (transport/route-listen-frame! listen-atom parsed))
                    (transport/dispatch-server-message! conn parsed nil send-async!))
                  (recur nil ""))
                :else (recur event-name buf)))))))))

(defn listen!
  "Open CONN's 2026-07-28 subscription: one POST whose SSE response body
   stays open on a background reader. The POST carries no deadline
   (:timeout nil omits curl's --max-time) and is only retried for auth
   once at open; a drop is not resumable, so re-establishing the
   subscription is the client's job. A non-2xx or non-SSE answer throws
   the MCP error contract with nothing left open. FILTER is the
   :notifications filter object; every frame (the acknowledgment first)
   is routed to ON-FRAME and to the conn-level handler — see
   transport/route-listen-frame!.

   Returns {:stop! f :ended promise}. stop! aborts the stream — closing
   it IS the modern cancellation (no notifications/cancelled over HTTP) —
   and :ended delivers :stopped, :ended (the server closed the stream) or
   {:error e}."
  [conn filter on-frame]
  (let [id (swap! (:id-counter conn) inc)
        params (cond-> {:notifications filter}
                 (protocol/modern-conn? conn)
                 (update :_meta merge (protocol/conn-meta conn)))
        body (json/generate-string {:jsonrpc "2.0" :id id
                                    :method protocol/listen-method
                                    :params params})
        response (http-post! conn (:url conn)
                             (http-request-headers conn protocol/listen-method params)
                             body nil)
        status (:status response)
        content-type (or (transport/header-value (:headers response) "Content-Type")
                         "")]
    (if-not (and (<= 200 status 299)
                 (str/includes? content-type "text/event-stream"))
      (do
        (http/close! response)
        (throw (protocol/mcp-error
                (str "MCP connect failed: " protocol/listen-method " answered "
                     status " " content-type)
                {:status status})))
      (let [listen-atom (:listen conn)
            stopped (atom false)
            ended (promise)
            reader (atom nil)
            stop! (fn []
                    (reset! stopped true)
                    (when-let [t @reader] (.interrupt t))
                    (http/close! response)
                    (deliver ended :stopped))]
        ;; the subscription state is installed before the reader starts, so
        ;; no frame can arrive without it (the ack observer and the
        ;; subscription-id demux read it): spawning first would race the
        ;; reset on a body that is already buffered
        (reset! listen-atom {:id id :subscription-id nil :on-frame on-frame
                             :stop! stop!})
        (reset! reader
                (spawn (fn []
                         (try
                           (read-listen-stream! conn listen-atom (:body response))
                           (deliver ended (if @stopped :stopped :ended))
                           (catch Exception e
                             (deliver ended (if @stopped :stopped {:error e})))
                           (finally
                             (http/close! response)
                             ;; clear only our own entry: a re-listen may
                             ;; have installed a fresh one already
                             (swap! listen-atom
                                    (fn [l] (when-not (= id (:id l)) l))))))))
        {:stop! stop! :ended ended}))))

;; ─── Teardown ─────────────────────────────────────────────────────────────

(defn terminate-http-session!
  "Release a streamable-HTTP session: an HTTP DELETE to the MCP endpoint
   ends the server-side session (transports — a client that no longer
   needs a session SHOULD delete it). Delivered from a background thread
   — a DELETE (and the auth-header refresh it can trigger) must never
   block the caller; /mcp disconnect runs on the TUI thread. Failures
   are ignored; the session expires on its own. No-op for a modern conn
   (the 2026-07-28 revision has no sessions) or without a negotiated
   Mcp-Session-Id; returns true when a DELETE was scheduled."
  [conn]
  (if (and (= :streamable-http (:transport conn))
           (not (protocol/modern-conn? conn))
           @(:session-id conn))
    (do
      (spawn
       (fn []
         (try
           (http/request {:url (:url conn)
                          :method :delete
                          :headers (http-request-headers conn)
                          :as :string
                          :throw? false
                          :timeout 10000})
           (catch Exception _ nil))
         (reset! (:session-id conn) nil)))
      true)
    false))

(defn close!
  "Mark the conn closed and (unless skipped) DELETE the session. OPTS:
   :terminate-http-session? false skips the DELETE entirely — teardown
   paths (session shutdown, a retry after a request timeout) rely on the
   session expiring on its own. A conn already marked closed (request
   timeout) skips the DELETE too. Idempotent."
  ([conn] (close! conn {}))
  ([conn {:keys [terminate-http-session?] :or {terminate-http-session? true}}]
   (let [was-closed @(:closed conn)]
     (reset! (:closed conn) true)
     (when (and terminate-http-session? (not was-closed))
       (terminate-http-session! conn)))
   nil))

(defn alive?
  "True while the conn has not been closed."
  [conn]
  (not @(:closed conn)))
