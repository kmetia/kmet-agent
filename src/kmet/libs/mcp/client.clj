(ns kmet.libs.mcp.client
  "Transport-neutral MCP client: the request core, the initialize
   handshake, capability-gated catalog discovery and URI-template
   expansion. Transports live in kmet.libs.mcp.transport.{stdio,http,sse};
   protocol constants in kmet.libs.mcp.protocol.

   Public protocol: request! / notify! / listen! / close! / alive? /
   last-used / modern?.
   request! throws ex-info with :type :mcp-error on a JSON-RPC error,
   timeout, or transport death (§7.7 message patterns). Notifications
   received mid-request are dispatched — notifications/progress goes to
   the request's :on-notification callback (streaming tool-call
   progress), everything else to the conn-level handler. Stale responses
   (non-matching :id) are dropped and the wait continues. Every
   request!/notify! touches :last-used so the idle reaper can disconnect
   unused servers."
  (:require [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]
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
   (streaming tool-call progress)}. :http-headers — per-request custom
   headers for HTTP transports (a tools/call's Mcp-Param-*, SEP-2243;
   stdio and SSE ignore them). Throws ex-info on JSON-RPC error,
   timeout, or transport death (§7.7).

   On a modern conn the era _meta is merged into params, and an
   input_required result (MRTR) is retried with its requestState echoed
   while the server asked for no client input; an inputRequests entry
   throws — kmet declares no elicitation/sampling/roots capabilities."
  [conn method params & [{:keys [timeout-ms on-notification http-headers]}]]
  (let [timeout (or timeout-ms protocol/default-request-timeout-ms)
        modern? (protocol/modern-conn? conn)
        request-opts {:http-headers http-headers}
        dispatch (fn [params]
                   (case (:transport conn)
                     :stdio (stdio/request! conn method params timeout on-notification request-opts)
                     :streamable-http (http/request! conn method params timeout on-notification request-opts)
                     :sse (sse/request! conn method params timeout on-notification request-opts)))]
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

(defn- listener-state
  "The live listener generation's state atom: CONN's :listener slot holds
   the current listener's atom (nil when none is open), and every
   generation keeps its own — a stale loop can never write into a fresh
   listener's state."
  [conn]
  (some-> (:listener conn) deref))

(defn- stop-listener!
  "Stop CONN's listener: the re-establish loop exits at its next check,
   the transport subscription is aborted (or cancelled), and a later
   listen! may open a fresh one. Idempotent — close! and listen! both
   run it."
  [conn]
  (when-let [state (listener-state conn)]
    (let [l @state]
      (when-let [stop-flag (:stop-flag l)] (reset! stop-flag true))
      (when-let [stop! (:stop! l)] (try (stop!) (catch Exception _ nil)))
      (swap! state assoc :stopped? true :stop! nil))))

(defn close!
  "Close a connection: stop its listener, then kill the stdio process
   tree, abort the active SSE stream (releases the blocked reader + reaps
   the transport), or DELETE a streamable-HTTP session (sent from a
   background thread, so the caller never waits on the server). OPTS:
   :terminate-http-session? false skips the DELETE entirely — teardown
   paths (session shutdown, a retry after a request timeout) rely on the
   session expiring on its own. Idempotent."
  ([conn] (close! conn {}))
  ([conn opts]
   (stop-listener! conn)
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

(defn- list-tools-for-conn
  "The tools of a tools/list result usable on CONN. A streamable-HTTP
   client MUST exclude a definition with an invalid x-mcp-header
   annotation (SEP-2243); the other transports ignore the annotation."
  [conn tools]
  (if (= :streamable-http (:transport conn))
    (remove #(not (http/valid-x-mcp-header? (:inputSchema %))) tools)
    tools))

(defn list-all-tools
  "tools/list with cursor pagination (nextCursor loop, 30s per page). On
   a streamable-HTTP conn a tool definition with an invalid x-mcp-header
   annotation is excluded (SEP-2243)."
  [conn]
  (loop [cursor nil tools []]
    (let [result (request! conn "tools/list"
                           (if cursor {:cursor cursor} {})
                           {:timeout-ms protocol/list-page-timeout-ms})
          tools (into tools (list-tools-for-conn conn (:tools result)))]
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
     - a JSON-RPC error body carrying one of the era's codes (servers
       answer them with 400) negotiates over data.supported on -32022 and
       surfaces -32020/-32021;
     - an auth failure (401/403 after the transport's own retry) aborts —
       authentication is not an era signal;
     - anything else (404/405, an empty or non-modern body, a non-modern
       JSON-RPC error, a plain HTTP failure) means legacy — the handshake
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

(defn- detect-and-establish!
  "Full era detection + establish — the normal path without a hint, and
   the recovery when a cached :protocol-era hint proves wrong (the shared
   probe-once fallback)."
  [conn]
  (case (:transport conn)
    :stdio
    (let [detected (detect-stdio-era! conn)]
      (if (= :modern (:era detected))
        (establish-modern! conn (:version detected) (:discover detected))
        (establish-legacy! conn (:probe-error detected))))

    :streamable-http
    (let [detected (detect-http-era! conn)]
      (if (= :modern (:era detected))
        (establish-modern! conn (:version detected) (:discover detected))
        (establish-legacy! conn (:probe-error detected))))

    ;; the legacy SSE transport never detects (frozen out of the era work)
    (establish-legacy! conn nil)))

(defn- era-hint
  "Normalize a :protocol-era connect option: the cached map
   {:era :modern|:legacy :version rev} (3.7), or a bare keyword; nil when
   absent or malformed (no :era)."
  [hint]
  (cond
    (map? hint) (when (contains? hint :era) (select-keys hint [:era :version]))
    (keyword? hint) {:era hint}
    :else nil))

(defn- hint-contradicted?
  "True when a hinted establish failed in a way that contradicts the
   cached HINT and earns the full detection instead of trusting the
   cache: a hinted modern connect that did not draw a recognized modern
   error (a legacy server answering server/discover) or drew -32022 (a
   revision the server dropped), and a hinted legacy connect that drew
   any JSON-RPC error (a modern server refusing the handshake). Timeouts,
   transport death, auth failures and -32020/-32021 are not era signals —
   they surface unchanged."
  [hint e]
  (let [data (ex-data e)
        code (:code data)]
    (and (some? code)
         (not (or (= 401 (:status data)) (= 403 (:status data))))
         (if (= :modern hint)
           (not (contains? #{-32020 -32021} code))
           true))))

(defn- establish-hinted!
  "Run the hinted era establishment. A hinted modern connect probes
   server/discover with the cached revision (or the one the conn already
   records); on an era-contradicting failure the hint is discarded and
   the full detection runs once — the shared re-probe — while any other
   failure surfaces."
  [conn {:keys [era version]}]
  (try
    (if (= :modern era)
      (establish-modern! conn (or version
                                  (protocol/era-version conn)
                                  protocol/modern-protocol-version)
                         nil)
      (establish-legacy! conn nil))
    (catch Exception e
      (if (hint-contradicted? era e)
        (detect-and-establish! conn)
        (throw e)))))

(defn- establish-era!
  "Resolve the conn's era before the catalog work. A :protocol-era hint
   (the metadata cache, 3.7) skips detection — with the cached revision
   for a modern hint, and a full re-probe when the hinted establish
   contradicts it; without one, a stdio conn is probed with
   server/discover and a streamable-HTTP conn with a server/discover
   POST, each falling back to the handshake; the legacy SSE transport
   runs the handshake. Returns {:era :modern|:legacy :version rev
   :handshake {:protocol-version :server-info :capabilities}}."
  [conn {:keys [protocol-era]}]
  (if-let [hint (era-hint protocol-era)]
    (establish-hinted! conn hint)
    (detect-and-establish! conn)))

(defn establish!
  "Handshake (legacy) or server/discover (modern) + capability-gated
   catalog discovery on an existing CONN. OPTS: :protocol-era — the
   cached {:era :modern|:legacy :version rev} hint (a bare keyword also
   works) skips era detection; a hint the server contradicts falls back
   to the full probe once. Returns {:protocol-version :server-info
   :capabilities :protocol-era {:era :version} :tools :prompts
   :resources :resource-templates} — :protocol-era is the era actually
   used (the metadata cache stores it), no :conn."
  ([conn] (establish! conn {}))
  ([conn opts]
   (let [{:keys [era version handshake]} (establish-era! conn opts)
         {:keys [protocol-version server-info capabilities]} handshake
         capabilities (or capabilities {})]
     ;; the era is recorded before the catalog fetches so their requests
     ;; carry the era _meta
     (record-era! conn era version)
     {:protocol-version protocol-version
      :protocol-era {:era era :version version}
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
   than progress, e.g. list_changed) / :protocol-era — the cached
   {:era :modern|:legacy :version rev} hint (a bare keyword also works)
   that skips era detection; a hinted establish the server contradicts
   falls back to the full probe once (3.7).
   Returns {:conn conn :tools [..] :prompts [..] :resources [..]
   :resource-templates [..] :protocol-version str :protocol-era
   {:era :version} :server-info map}. On any failure the transport is
   closed and the ex-info rethrown."
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
                                          (atom nil))
                    ;; the live 2026-07-28 subscription (listen!) — nil
                    ;; until one is opened
                    :listener (atom nil))]
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

;; ─── Subscriptions (2026-07-28) ───────────────────────────────────────────

(def ^:private listen-backoff-ms
  "Re-listen delays after a subscription stream ends on its own: the
   immediate re-open, then 1s/5s/15s. Four attempts, then the conn is
   closed so the next use rebuilds it."
  [0 1000 5000 15000])

(defn listening?
  "True while CONN has a live 2026-07-28 subscription. The idle reaper
   must not disconnect a listening conn: a quiet stdio subscription sees
   no traffic at all, and the subscription is gone with the conn."
  [conn]
  (let [l (some-> (listener-state conn) deref)]
    (boolean (and l (not (:stopped? l)) (not (:failed? l))))))

(defn- observe-listen-frame!
  "Record one frame of the live subscription: whether the server's
   acknowledgment actually came first (`:first-ack?`) and the filter
   subset it agreed to (`:agreed`). The listing too is the client's own
   bookkeeping — the frame itself is delivered to the conn-level handler
   by the transport like any other server message."
  [state msg]
  (swap! state
         (fn [l]
           (cond-> (update l :frames inc)
             (zero? (:frames l))
             (assoc :first-ack? (= protocol/ack-method (:method msg)))
             (and (= protocol/ack-method (:method msg))
                  (map? (:notifications (:params msg))))
             (assoc :agreed (:notifications (:params msg)))))))

(defn- listen-attempt!
  "One subscription attempt on the live conn; blocks until the stream
   stops or ends. Returns :stopped, :ended or :error (the exception lands
   in the listener's :last-error)."
  [conn state]
  (let [on-frame (:on-frame @state)
        {:keys [stop! ended]} (case (:transport conn)
                                :stdio (stdio/listen! conn (:filter @state) on-frame)
                                :streamable-http (http/listen! conn (:filter @state)
                                                               on-frame))]
    (swap! state assoc :stop! stop!)
    ;; close! may have set the stop flag before this attempt installed its
    ;; stop fn — then nobody else can end it, so end it here
    (when @(:stop-flag @state) (try (stop!) (catch Exception _ nil)))
    (let [outcome (deref ended)]
      (swap! state assoc :stop! nil)
      (cond
        (= :stopped outcome) :stopped
        (= :ended outcome) :ended
        :else (do (swap! state assoc :last-error (:error outcome))
                  :error)))))

(defn- sleep-listen!
  "Sleep MS in 100ms slices so a stop during the backoff is seen
   promptly (pi abortableSleep)."
  [ms stop-flag]
  (let [end (+ (concurrent/monotonic-ms) ms)]
    (loop []
      (when (and (not @stop-flag) (< (concurrent/monotonic-ms) end))
        (Thread/sleep (min 100 (- end (concurrent/monotonic-ms))))
        (recur)))))

(defn- listen-loop!
  "The listener's own thread: open an attempt, wait for it to end, then
   back off and re-open until the caller stops it or the budget runs out.
   OPTS :on-restored runs once, after the first gap — the resync a caller
   needs when the stream was down and notifications may have been
   missed."
  [conn state]
  (loop [attempt 0
         gapped? false
         restored? false]
    (let [{:keys [stop-flag on-restored]} @state
          restore? (boolean (and gapped? (not restored?) on-restored))]
      (when-not @stop-flag
        (when restore?
          (swap! state assoc :restored? true)
          (try (on-restored) (catch Exception _ nil)))
        (let [outcome (try (listen-attempt! conn state)
                           (catch Exception e {:error e}))]
          (when-not (or @stop-flag (= :stopped outcome))
            (if (< attempt (dec (count listen-backoff-ms)))
              (do (sleep-listen! (nth listen-backoff-ms (inc attempt)) stop-flag)
                  (recur (inc attempt) true (or restored? restore?)))
              ;; the subscription cannot be kept alive any more: close the
              ;; conn so the next use reconnects from scratch
              (do (swap! state assoc :failed? true)
                  (try (close! conn) (catch Exception _ nil))))))))))

(defn listen!
  "Open CONN's 2026-07-28 subscription (one per conn — a live listener is
   stopped first). FILTER is the spec filter: the :toolsListChanged /
   :promptsListChanged / :resourcesListChanged booleans and
   :resourceSubscriptions [uri ...] (kmet watches no individual
   resources). Every frame of the subscription is delivered to the conn's
   notification handler like any other server message; the listener also
   records the server's agreed filter (:agreed) and whether the
   acknowledgment really came first (:first-ack?).

   OPTS:
   :on-frame    (fn [msg]) — observes every frame of the subscription
                (the acknowledgment first) before delivery
   :on-restored (fn []) — runs once per listener, at the first re-listen
                attempt after a gap, so a caller can resync what the gap
                may have missed (a resync does not wait for the new
                stream's acknowledgment: the catalog is re-listed over
                ordinary requests)

   A stream that ends on its own is re-established with a bounded backoff
   (immediate, 1s, 5s, 15s); when the budget is spent the conn is closed
   so the next use reconnects. close! stops the listener. Returns the
   listener state atom; throws for a conn that did not negotiate the era
   or whose transport has no subscription stream."
  [conn filter & [{:keys [on-frame on-restored]}]]
  (when-not (protocol/modern-conn? conn)
    (throw (protocol/mcp-error
            (str "MCP " protocol/listen-method
                 " requires the 2026-07-28 era: no subscription opened")
            {:method protocol/listen-method})))
  (when-not (#{:stdio :streamable-http} (:transport conn))
    (throw (protocol/mcp-error
            (str "MCP " protocol/listen-method " is not available over "
                 (name (or (:transport conn) :unknown)) " (legacy transport)")
            {:method protocol/listen-method :transport (:transport conn)})))
  (stop-listener! conn)
  (let [state (atom {:filter filter
                     :on-restored on-restored
                     :stop-flag (atom false)
                     :frames 0})]
    (swap! state assoc :on-frame
           (fn [msg]
             (observe-listen-frame! state msg)
             (when on-frame
               (try (on-frame msg) (catch Exception _ nil)))))
    (reset! (:listener conn) state)
    (concurrent/spawn (fn [] (listen-loop! conn state)))
    state))
