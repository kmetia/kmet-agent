(ns kmet.libs.mcp.client
  "Transport-neutral MCP client: the request core, the initialize
   handshake, capability-gated catalog discovery and URI-template
   expansion. Transports live in kmet.libs.mcp.transport.{stdio,http,sse};
   protocol constants in kmet.libs.mcp.protocol.

   Public protocol: request! / notify! / close! / alive? / last-used /
   modern?.
   request! throws ex-info with :type :mcp-error on a JSON-RPC error,
   timeout, or transport death (§7.7 message patterns). Notifications
   received mid-request are dispatched — notifications/progress goes to
   the request's :on-notification callback (streaming tool-call
   progress), everything else to the conn-level handler. Stale responses
   (non-matching :id) are dropped and the wait continues. Every
   request!/notify! touches :last-used so the idle reaper can disconnect
   unused servers."
  (:require [clojure.string :as str]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport :as transport]
            [kmet.libs.mcp.transport.http :as http]
            [kmet.libs.mcp.transport.sse :as sse]
            [kmet.libs.mcp.transport.stdio :as stdio]))

;; ─── request!/notify!/close!/alive? (§7.1) ────────────────────────────────

(defn last-used
  "The last activity timestamp (ms) for a connection — the idle reaper
   disconnects servers whose conn has been idle past the configured
   :idle-timeout."
  [conn]
  (transport/last-used conn))

(defn modern?
  "True when the connection negotiated the 2026-07-28 era: no initialize
   handshake, per-request _meta, resultType on every result."
  [conn]
  (protocol/modern-conn? conn))

(defn- with-request-meta
  "Merge the era's _meta into PARAMS for a modern CONN; a legacy conn's
   params pass through untouched. Caller-supplied keys (a progress token)
   win."
  [conn params]
  (if (protocol/modern-conn? conn)
    (update (or params {}) :_meta #(merge (protocol/conn-meta conn) %))
    params))

(def ^:private mrtr-max-retries
  "How many requestState-only MRTR retries request! sends before giving
   up: a server that repeatedly demands input the client cannot provide
   must fail loudly rather than loop."
  2)

(defn- input-required-error
  "MRTR result the client cannot serve: the server asked for an
   inputRequests entry (elicitation/sampling/roots, none of which kmet
   declares in protocol/modern-request-meta)."
  [result]
  (protocol/mcp-error
   (str "MCP server requested client input (MRTR): "
        (str/join ", " (map (fn [[k v]] (str (name k) " → " (:method v)))
                            (:inputRequests result))))
   {:result-type :input-required
    :input-requests (:inputRequests result)}))

(defn- mrtr-exhausted-error
  "MRTR result abandoned after mrtr-max-retries requestState-only rounds."
  [method]
  (protocol/mcp-error
   (str "MCP server kept requesting input (MRTR) after "
        mrtr-max-retries " retries: " method)
   {:result-type :input-required
    :retries mrtr-max-retries}))

(defn request!
  "Send a JSON-RPC request and return its :result. OPTS:
   {:timeout-ms n (default 120000) :on-notification (fn [notification])
   — receives notifications/progress events arriving mid-request
   (streaming tool-call progress)}. Throws ex-info on JSON-RPC error,
   timeout, or transport death (§7.7).

   On a modern conn the era _meta is merged into params, and an
   input_required result (MRTR) is retried with its requestState echoed
   while the server asked for no client input; an inputRequests entry
   throws — kmet declares no elicitation/sampling/roots capabilities."
  [conn method params & [{:keys [timeout-ms on-notification]}]]
  (let [timeout (or timeout-ms protocol/default-request-timeout-ms)
        modern? (protocol/modern-conn? conn)
        dispatch (fn [params]
                   (case (:transport conn)
                     :stdio (stdio/request! conn method params timeout on-notification)
                     :streamable-http (http/request! conn method params timeout on-notification)
                     :sse (sse/request! conn method params timeout on-notification)))]
    ;; one request at a time per stdio/sse conn: SSE matches responses on
    ;; one shared channel, so two waiters would consume (and drop) each
    ;; other's responses; stdio correlates per request in jsonrpc but
    ;; routes progress through a single conn-level callback, so
    ;; serializing keeps the in-flight callback unambiguous. A background
    ;; list_changed resync queues behind an in-flight tool call instead of
    ;; racing it (streamable-http answers each request on its own response
    ;; body and stays concurrent).
    (loop [params (with-request-meta conn params)
           retries 0]
      (let [result (if (= :streamable-http (:transport conn))
                     (dispatch params)
                     ;; clj-kondo flags the map lookup as locally created;
                     ;; the lock object is created once per conn
                     ;; (stdio/http/sse) and shared by every caller of that
                     ;; conn
                     #_{:clj-kondo/ignore [:locking-suspicious-lock]}
                     (locking (:req-lock conn) (dispatch params)))]
        (if (and modern? (= "input_required" (protocol/result-type result)))
          (cond
            (seq (:inputRequests result)) (throw (input-required-error result))
            (< retries mrtr-max-retries)
            (recur (assoc params :requestState (:requestState result))
                   (inc retries))
            :else (throw (mrtr-exhausted-error method)))
          result)))))

(defn- send-async!
  "Deliver a message that expects no answer (a notification)."
  [conn msg]
  (case (:transport conn)
    :stdio (stdio/send-async! conn msg)
    (:streamable-http :sse) (http/send-async! conn msg)))

(defn notify!
  "Send a JSON-RPC notification (no response expected). A modern conn
   carries the era _meta in its params."
  [conn method params]
  (send-async! conn {:jsonrpc "2.0" :method method
                     :params (with-request-meta conn params)}))

(defn close!
  "Close a connection: kill the stdio process tree, abort the active SSE
   stream (releases the blocked reader + reaps the transport), or DELETE
   a streamable-HTTP session (sent from a background thread, so the
   caller never waits on the server). OPTS: :terminate-http-session?
   false skips the DELETE entirely — teardown paths (session shutdown,
   a retry after a request timeout) rely on the session expiring on its
   own. Idempotent."
  ([conn] (close! conn {}))
  ([conn opts]
   (case (:transport conn)
     :stdio (stdio/close! conn)
     :streamable-http (http/close! conn opts)
     :sse (sse/close! conn))
   nil))

(defn alive?
  "True when the connection is still usable."
  [conn]
  (case (:transport conn)
    :stdio (stdio/alive? conn)
    :streamable-http (http/alive? conn)
    :sse (sse/alive? conn)))

;; ─── Handshake + discovery (§7.5) ─────────────────────────────────────────

(defn initialize!
  "Run the MCP handshake: initialize (60s timeout) → notifications/
   initialized. The revision the server selects is validated against
   kmet.libs.mcp.protocol/supported-protocol-versions (an answer outside
   that list is an error) and recorded in the conn :protocol-version atom,
   so streamable-HTTP requests after the handshake carry the negotiated
   MCP-Protocol-Version header. Returns {:protocol-version str
   :server-info map :capabilities map}."
  [conn]
  (let [result (request! conn "initialize"
                         {:protocolVersion protocol/protocol-version
                          :capabilities {}
                          :clientInfo protocol/client-info}
                         {:timeout-ms protocol/initialize-timeout-ms})
        version (or (:protocolVersion result) protocol/protocol-version)]
    (when-not (some #{version} protocol/supported-protocol-versions)
      (throw (protocol/mcp-error (str "MCP server selected unsupported protocol version "
                                      version)
                                 {:protocol-version version
                                  :supported protocol/supported-protocol-versions})))
    (when-let [pv (:protocol-version conn)]
      (reset! pv version))
    (notify! conn "notifications/initialized" {})
    {:protocol-version version
     :server-info (:serverInfo result)
     :capabilities (or (:capabilities result) {})}))

(defn list-all-tools
  "tools/list with cursor pagination (nextCursor loop, 30s per page)."
  [conn]
  (loop [cursor nil tools []]
    (let [result (request! conn "tools/list"
                           (if cursor {:cursor cursor} {})
                           {:timeout-ms protocol/list-page-timeout-ms})
          tools (into tools (:tools result))]
      (if-let [next-cursor (:nextCursor result)]
        (recur next-cursor tools)
        tools))))

(defn list-all-prompts
  "prompts/list with cursor pagination (30s per page)."
  [conn]
  (loop [cursor nil prompts []]
    (let [result (request! conn "prompts/list"
                           (if cursor {:cursor cursor} {})
                           {:timeout-ms protocol/list-page-timeout-ms})
          prompts (into prompts (:prompts result))]
      (if-let [next-cursor (:nextCursor result)]
        (recur next-cursor prompts)
        prompts))))

(defn get-prompt
  "prompts/get — result contains :messages; :arguments is a string map
   (omitted when empty)."
  [conn name arguments & [{:keys [timeout-ms]}]]
  (request! conn "prompts/get"
            (cond-> {:name name}
              (seq arguments) (assoc :arguments arguments))
            {:timeout-ms (or timeout-ms protocol/default-request-timeout-ms)}))

(defn list-all-resources
  "resources/list with cursor pagination (30s per page)."
  [conn]
  (loop [cursor nil resources []]
    (let [result (request! conn "resources/list"
                           (if cursor {:cursor cursor} {})
                           {:timeout-ms protocol/list-page-timeout-ms})
          resources (into resources (:resources result))]
      (if-let [next-cursor (:nextCursor result)]
        (recur next-cursor resources)
        resources))))

(defn list-all-resource-templates
  "resources/templates/list with cursor pagination (30s per page) —
   parameterized resources (file:///{path} and friends). A server whose
   resources are all templates exposes nothing through resources/list.
   Tolerates a server that advertises the resources capability but
   answers -32601 (templates are a sub-feature of resource discovery —
   failing the whole connect or resync over them would cost us the plain
   resources/tools too). Any other error propagates."
  [conn]
  (try
    (loop [cursor nil templates []]
      (let [result (request! conn "resources/templates/list"
                             (if cursor {:cursor cursor} {})
                             {:timeout-ms protocol/list-page-timeout-ms})
            templates (into templates (:resourceTemplates result))]
        (if-let [next-cursor (:nextCursor result)]
          (recur next-cursor templates)
          templates)))
    (catch Exception e
      (when-not (= -32601 (:code (ex-data e))) (throw e))
      [])))

(defn read-resource
  "resources/read — result contains :contents (text or blob blocks)."
  [conn uri & [{:keys [timeout-ms]}]]
  (request! conn "resources/read" {:uri uri}
            {:timeout-ms (or timeout-ms protocol/default-request-timeout-ms)}))

(defn- uri-escape
  "Percent-escape a template variable's value: everything outside the
   RFC 3986 unreserved / sub-delims / ':@/' set, plus control
   characters — so a space, ?, # or % cannot change the URI's meaning.
   Deliberately minimal: '/' survives inside a {path} variable (servers
   expect the raw path), and non-ASCII is left as-is rather than
   percent-encoded per byte."
  [s]
  (apply str
         (map (fn [c]
                (if (or (> (int c) 126)
                        (re-matches #"[A-Za-z0-9\-._~!$&'()*+,;=:@/]" (str c)))
                  (str c)
                  (format "%%%02X" (int c))))
              (str s))))

(defn expand-uri-template
  "Expand a level-1 URI template (RFC 6570 {var} placeholders) with
   ARGS, turning a resources/templates/list entry into a concrete
   resources/read URI. Argument keys may be strings or keywords. A
   variable with no matching argument is left in place, so the server
   answers with its own error instead of us guessing a value."
  [uri-template args]
  (let [args (or args {})]
    (str/replace (str uri-template)
                 #"\{([^{}]+)\}"
                 (fn [match]
                   (let [var-name (second match)
                         value (get args var-name (get args (keyword var-name)))]
                     (if (some? value)
                       (uri-escape (str value))
                       (first match)))))))

;; ─── Connect ──────────────────────────────────────────────────────────────

;; ─── Era establishment (2026-07-28) ──────────────────────────────────────

(defn- record-era!
  "Record the resolved era on the conn: {:era :modern|:legacy :version
   rev} on its :era atom and the revision on its :protocol-version atom
   (the transports' MCP-Protocol-Version header reads that one)."
  [conn era version]
  (when-let [a (:era conn)] (reset! a {:era era :version version}))
  (when-let [pv (:protocol-version conn)] (reset! pv version))
  nil)

(defn- discover-request!
  "One server/discover round trip with TIMEOUT-MS; its era _meta is forced
   (the conn is not marked modern yet, and the probe runs before the era
   is known). A streamable-HTTP conn additionally takes the modern shape
   for the duration of the probe: the era atom records VERSION so the
   POST carries MCP-Protocol-Version and the Mcp-Method routing header
   (a hybrid legacy server still answers the unknown method), any session
   the legacy handshake captured is dropped, and any session header in
   the answer is ignored. establish-legacy! undoes it before the
   handshake."
  [conn version timeout-ms]
  (when (= :streamable-http (:transport conn))
    (when-let [era (:era conn)] (reset! era {:era :modern :version version}))
    (reset! (:protocol-version conn) version)
    (when-let [session (:session-id conn)] (reset! session nil)))
  (request! conn "server/discover"
            {:_meta (protocol/modern-request-meta version)}
            {:timeout-ms timeout-ms}))

(defn- discover-handshake
  "A raw DiscoverResult → the handshake shape establish! works with."
  [version result]
  {:protocol-version version
   :server-info (get-in result [:_meta (protocol/meta-key "serverInfo")])
   :capabilities (or (:capabilities result) {})})

(defn discover!
  "server/discover — the 2026-07-28 replacement for the initialize
   handshake. Returns {:protocol-version :server-info :capabilities
   :supported-versions}. VERSION defaults to the conn's recorded
   revision."
  ([conn] (discover! conn (or (protocol/era-version conn)
                              protocol/modern-protocol-version)))
  ([conn version]
   (let [result (discover-request! conn version protocol/initialize-timeout-ms)]
     (assoc (discover-handshake version result)
            :supported-versions (vec (:supportedVersions result))))))

(defn- no-overlap-error
  "The error for a server whose advertised revisions share none with the
   client's."
  [advertised]
  (protocol/mcp-error
   (str "MCP server advertises no protocol revision this client speaks: "
        (str/join ", " (or (seq advertised) ["none"])))
   {:supported (vec advertised)
    :client-supported (into protocol/modern-supported-versions
                            protocol/supported-protocol-versions)}))

(defn- detect-stdio-era!
  "Classify a stdio conn's era with a server/discover probe:
     - a DiscoverResult negotiates over :supportedVersions — a modern
       overlap continues modern (the result is carried along), a legacy
       overlap falls back to the handshake, no overlap errors;
     - -32022 negotiates over data.supported — a different modern revision
       retries the probe, a legacy overlap falls back, no overlap errors;
     - -32020/-32021 surface (the server rejected our _meta);
     - any other error, process death or timeout means legacy — the
       handshake decides (a legacy server answers unknown methods with
       -32601 immediately).
   Returns {:era :modern :version rev :discover result} or
   {:era :legacy :probe-error e-or-nil}."
  [conn]
  (loop [version protocol/modern-protocol-version]
    (let [outcome (try {:result (discover-request! conn version
                                                   protocol/probe-timeout-ms)}
                       (catch Exception e {:error e}))]
      (if-let [result (:result outcome)]
        (if-let [negotiated (protocol/negotiate-version (:supportedVersions result))]
          (case (:era negotiated)
            :modern {:era :modern
                     :version (:version negotiated)
                     :discover result}
            :legacy {:era :legacy})
          (throw (no-overlap-error (:supportedVersions result))))
        (let [e (:error outcome)
              code (:code (ex-data e))]
          (cond
            (= -32022 code)
            (if-let [negotiated (protocol/negotiate-version
                                 (:supported (:data (ex-data e))))]
              (case (:era negotiated)
                :modern (if (= (:version negotiated) version)
                          {:era :modern :version version}
                          (recur (:version negotiated)))
                :legacy {:era :legacy})
              (throw (no-overlap-error (:supported (:data (ex-data e))))))
            (protocol/modern-error? (ex-data e)) (throw e)
            :else {:era :legacy :probe-error e}))))))

(defn- detect-http-era!
  "Classify a streamable-HTTP conn's era with a server/discover POST:
     - a DiscoverResult negotiates over :supportedVersions — a modern
       overlap continues modern (the result is carried along), a legacy
       overlap falls back to the handshake, no overlap errors;
     - a recognized modern error (a 400 whose JSON-RPC body parsed into
       the ex-data) negotiates over data.supported on -32022, surfaces
       -32020/-32021;
     - an auth failure (401/403 after the transport's own retry) aborts —
       authentication is not an era signal;
     - anything else (404/405, an empty or non-modern body, a non-modern
       JSON-RPC error, another status) means legacy — the handshake
       decides, and a legacy server answers unknown methods immediately.
   Returns {:era :modern :version rev :discover result} or
   {:era :legacy :probe-error e-or-nil}."
  [conn]
  (loop [version protocol/modern-protocol-version]
    (let [outcome (try {:result (discover-request! conn version
                                                   protocol/probe-timeout-ms)}
                       (catch Exception e {:error e}))]
      (if-let [result (:result outcome)]
        (if-let [negotiated (protocol/negotiate-version (:supportedVersions result))]
          (case (:era negotiated)
            :modern {:era :modern
                     :version (:version negotiated)
                     :discover result}
            :legacy {:era :legacy})
          (throw (no-overlap-error (:supportedVersions result))))
        (let [e (:error outcome)
              data (ex-data e)
              code (:code data)]
          (cond
            (and code (protocol/modern-error? data))
            (if (= -32022 code)
              (if-let [negotiated (protocol/negotiate-version (:supported (:data data)))]
                (case (:era negotiated)
                  :modern (if (= (:version negotiated) version)
                            {:era :modern :version version}
                            (recur (:version negotiated)))
                  :legacy {:era :legacy})
                (throw (no-overlap-error (:supported (:data data)))))
              (throw e))
            (or (= 401 (:status data)) (= 403 (:status data))) (throw e)
            :else {:era :legacy :probe-error e}))))))

(defn- probe-timed-out?
  "True when the era probe gave up on a timeout rather than a server
   answer — the era-ambiguous case that earns a recovery re-probe."
  [e]
  (some? (:timeout-ms (ex-data e))))

(defn- establish-modern!
  "The modern era-establish result, reusing the detection round trip when
   it produced a DiscoverResult and running server/discover with the full
   establish timeout otherwise."
  [conn version discover]
  {:era :modern
   :version version
   :handshake (if discover
                (discover-handshake version discover)
                (discover! conn version))})

(defn- recover-modern!
  "The era-ambiguous failure path: the probe timed out and the handshake
   then failed with a JSON-RPC error (a modern server that was still
   booting answers the unknown initialize with an error). Probe
   server/discover once more with the full establish timeout — a modern
   answer is adopted, anything else leaves the handshake failure to
   surface. Returns the modern establish map or nil."
  [conn]
  (try
    (let [version protocol/modern-protocol-version
          result (discover-request! conn version protocol/initialize-timeout-ms)]
      (when-let [negotiated (protocol/negotiate-version (:supportedVersions result))]
        (when (= :modern (:era negotiated))
          (establish-modern! conn (:version negotiated) result))))
    (catch Exception _ nil)))

(defn- establish-legacy!
  "Run the handshake — the era fallback whenever the probe established no
   modern server. A streamable-HTTP conn drops the probe's transient
   modern shape first: a legacy initialize POST must carry no version
   header and no _meta, take its own session, and mirror no routing
   headers. A timed-out probe plus a handshake JSON-RPC error is
   ambiguous, so the modern discovery gets one more full-timeout try
   before the handshake error surfaces."
  [conn probe-error]
  (when (= :streamable-http (:transport conn))
    (when-let [era (:era conn)] (reset! era nil))
    (reset! (:protocol-version conn) nil))
  (try
    (let [initialized (initialize! conn)]
      {:era :legacy
       :version (:protocol-version initialized)
       :handshake initialized})
    (catch Exception e
      (if (and (probe-timed-out? probe-error) (:code (ex-data e)))
        (or (recover-modern! conn)
            (throw e))
        (throw e)))))

(defn- establish-era!
  "Resolve the conn's era before the catalog work. A :protocol-era hint
   (the metadata cache, 3.7) skips detection; a stdio conn is probed
   with server/discover and a streamable-HTTP conn with a server/discover
   POST, each falling back to the handshake; the legacy SSE transport
   runs the handshake. Returns {:era :modern|:legacy :version rev
   :handshake {:protocol-version :server-info :capabilities}}."
  [conn {:keys [protocol-era]}]
  (cond
    (= :modern protocol-era)
    (establish-modern! conn (or (protocol/era-version conn)
                                protocol/modern-protocol-version)
                       nil)

    (= :legacy protocol-era)
    (establish-legacy! conn nil)

    (= :stdio (:transport conn))
    (let [detected (detect-stdio-era! conn)]
      (if (= :modern (:era detected))
        (establish-modern! conn (:version detected) (:discover detected))
        (establish-legacy! conn (:probe-error detected))))

    (= :streamable-http (:transport conn))
    (let [detected (detect-http-era! conn)]
      (if (= :modern (:era detected))
        (establish-modern! conn (:version detected) (:discover detected))
        (establish-legacy! conn (:probe-error detected))))

    :else (establish-legacy! conn nil)))

(defn establish!
  "Handshake (legacy) or server/discover (modern) + capability-gated
   catalog discovery on an existing CONN. OPTS: :protocol-era — a
   :modern/:legacy hint (the metadata cache) skips era detection.
   Returns {:protocol-version :server-info :capabilities :tools :prompts
   :resources :resource-templates} — no :conn."
  ([conn] (establish! conn {}))
  ([conn opts]
   (let [{:keys [era version handshake]} (establish-era! conn opts)
         {:keys [protocol-version server-info capabilities]} handshake
         capabilities (or capabilities {})]
     ;; the era is recorded before the catalog fetches so their requests
     ;; carry the era _meta
     (record-era! conn era version)
     {:protocol-version protocol-version
      :server-info server-info
      :capabilities capabilities
      :tools (if (:tools capabilities) (list-all-tools conn) [])
      :prompts (if (:prompts capabilities) (list-all-prompts conn) [])
      :resources (if (:resources capabilities) (list-all-resources conn) [])
      :resource-templates (if (:resources capabilities)
                            (list-all-resource-templates conn)
                            [])})))

(defn connect!
  "Full connect for a DEFINITION: build the transport, establish the
   era (handshake or 2026-07-28 discovery), catalog discovery. OPTS:
   :auth-headers / :on-401 (HTTP transports, §7.8 — :on-401 is called as
   (fn [response]) with the 401 response so it can read the
   WWW-Authenticate challenge, and returns fresh headers) /
   :on-notification (fn [conn msg] — server->client notifications other
   than progress, e.g. list_changed) / :protocol-era — a :modern or
   :legacy cache hint that skips era detection (3.7).
   Returns {:conn conn :tools [..] :prompts [..] :resources [..]
   :resource-templates [..] :protocol-version str :server-info map}.
   On any failure the transport is closed and the ex-info rethrown."
  [definition opts]
  (let [url (:url definition)
        transport-conn (if url
                         (case (:http-transport definition)
                           :sse (sse/connect! url (assoc opts :reconnect-fn initialize!))
                           (http/connect! url opts))
                         (stdio/connect! definition opts))
        conn (assoc transport-conn
                    ;; nil until negotiation records {:era :modern|:legacy
                    ;; :version rev}; every conn carries the slot so the
                    ;; transports' captured maps stay consistent after the
                    ;; :conn-ref repoint below
                    :era (atom nil)
                    ;; the handshake/discovery records the negotiated
                    ;; revision here (the HTTP transport brings its own)
                    :protocol-version (or (:protocol-version transport-conn)
                                          (atom nil)))]
    (try
      (when (and url (= :sse (:transport conn)))
        (sse/open-stream! conn))
      (let [established (establish! conn opts)
            conn (assoc conn :capabilities (:capabilities established))]
        ;; the transport's internal callbacks captured the pre-assoc map;
        ;; repoint them at the conn the caller will store
        (when-let [r (:conn-ref conn)] (reset! r conn))
        (assoc established :conn conn))
      (catch Exception e
        (close! conn)
        (throw e)))))
