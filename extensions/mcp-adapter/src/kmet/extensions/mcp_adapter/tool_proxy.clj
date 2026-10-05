(ns kmet.extensions.mcp-adapter.tool-proxy
  "The mcp proxy tool executor's invocation surface (§9.2, pi
   proxy-modes.ts — the execution half): param coercion and validation,
   the live tool call (call-mcp-tool), resource reads, the run_code
   sandbox records (script-tool-records), and the dispatch itself. The
   read-only modes live in search.clj (search) and status.clj
   (status/describe/list); the shared state view in catalog.clj.
   Search/describe read the metadata cache only (no spawn); call/connect
   ensure a live connection. Every mode returns the kmet tool result
   shape {:content str :is-error bool}."
  (:require [clojure.string :as str]
            [kmet.libs.json :as json]
            [kmet.libs.mcp.client :as mcp]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport.http :as mcp-http]
            [kmet.extensions.mcp-adapter.catalog :as catalog]
            [kmet.extensions.mcp-adapter.output-guard :as guard]
            [kmet.extensions.mcp-adapter.search :as search]
            [kmet.extensions.mcp-adapter.status :as status]))

;; ─── Proxy params (§9.1) ───────────────────────────────────────────────

(defn flag
  "Coerce a boolean proxy param (§9.1): booleans pass through; the strings
   \"true\"/\"false\" (LLMs commonly send them as strings) coerce; anything
   else passes through unchanged (truthy for the dispatch's `when`)."
  [v]
  (cond
    (boolean? v) v
    (= "true" v) true
    (= "false" v) false
    :else v))

(defn- flag-value?
  "True when v is a boolean or its string form (see flag)."
  [v]
  (or (boolean? v) (= "true" v) (= "false" v)))

(defn- validate-params
  "nil when every present param has its documented §9.1 type, else an error
   result naming the offending param. The model can put anything in the
   JSON args, and a non-string in a string param would surface as a raw
   ClassCastException from str/lower-case & co — rejected here with a
   readable message the model can act on."
  [params]
  (let [bad (or (first (for [k [:search :describe :tool :server :connect
                                :disconnect :list]
                             :when (and (some? (get params k))
                                        (not (string? (get params k))))]
                         [k "a string"]))
                (first (for [k [:limit :offset]
                             :when (and (some? (get params k))
                                        (not (number? (get params k))))]
                         [k "a number"]))
                (first (for [k [:regex :includeSchemas]
                             :when (and (some? (get params k))
                                        (not (flag-value? (get params k))))]
                         [k "a boolean"])))]
    (when bad
      (let [[k expected] bad]
        (catalog/return-error (str "mcp({ " (name k) ": " (pr-str (get params k))
                                   " }) — expected " expected))))))

(defn- normalize-args
  "§9.2/§10.4: args is a JSON object; a JSON string is also accepted —
   parse it (unparseable → {}). Anything else (numbers, vectors) is not a
   valid tool input."
  [args]
  (cond
    (map? args) args
    (string? args) (try (let [parsed (json/parse-string args true)]
                          (if (map? parsed) parsed {}))
                        (catch Exception _ {}))
    :else {}))

;; ─── Call (§9.2 tool mode) ─────────────────────────────────────────────

(defn ensure-lazy-connected
  "Connect a server honoring the 60s failure backoff window (pi
   lazyConnect): inside the window returns nil (the caller reports 'not
   available') instead of retrying; explicit connects bypass the window.
   Returns the live conn or nil — the server's :error atom holds the
   connect failure, so the caller can report it."
  [state server]
  (when-not (catalog/failure-age-seconds state server)
    (try
      ((:ensure-connected-fn state) server)
      (catch Exception _ nil))))

(defn- format-progress
  "One progress notification as a partial-content line (client-side
   streaming tool-call progress)."
  [notification]
  (let [params (or (:params notification) {})
        progress (:progress params)
        total (:total params)
        message (str/trim (or (:message params) ""))
        amount (cond
                 (and progress total) (str progress "/" total)
                 progress (str progress)
                 :else nil)]
    (str "[progress" (when amount (str " " amount))
         (when (seq message) (str " — " (catalog/truncate-at-word message 100)))
         "]")))

(defn- on-update-progress!
  "Wrap ON-UPDATE so progress notifications stream as partial content
   while the call runs (the final result replaces the partials)."
  [on-update]
  (when on-update
    (fn [notification]
      (on-update {:content (format-progress notification)
                  :is-partial true}))))

(defn call-mcp-tool
  "Call one MCP tool on a server (direct-tool executor + proxy tool mode).
   Ensures the connection first (reconnect-on-use). OPTS:
   {:on-update (fn [partial]) — progress streaming; :input-schema — the
   tool's inputSchema, used to derive the Mcp-Param-* headers a
   streamable-HTTP tools/call must mirror (SEP-2243). Without it the
   call falls back to the catalog record — read back after the connect,
   so a lazy first call by raw name still finds its schema; when even
   that is missing the call degrades to no custom headers)}. Returns the
   kmet tool result shape; the output guard (§settings :output-guard)
   bounds oversized text and rides details (:output-guard / :mcp-result)."
  [state server tool-name args & [opts]]
  (let [definition (catalog/server-definition state server)]
    (cond
      (nil? definition)
      (catalog/return-error (str "Server \"" server "\" not found. Use mcp({}) to see available servers."))

      (catalog/disabled? state server)
      (catalog/return-error (str "Server \"" server "\" is disabled. Run /mcp enable " server
                                 " and /reload to enable it."))

      :else
      (if-let [failed-ago (catalog/failure-age-seconds state server)]
        ;; pi executeCall: inside the 60s backoff window a lazy use does
        ;; not retry — explicit mcp({connect}) bypasses this
        (catalog/return-error (str "Server \"" server "\" not available (last failed "
                                   failed-ago "s ago)"))
        (try
          (let [conn (ensure-lazy-connected state server)]
            (if conn
              (let [timeout-ms (or (:request-timeout-ms definition) 120000)
                    arguments (normalize-args args)
                    fresh-state (if-let [fresh (:read-state-fn state)] (fresh) state)
                    schema (or (:input-schema opts)
                               (:inputSchema (first (filter #(= tool-name (:name %))
                                                            (or (catalog/cached-tools fresh-state server)
                                                                [])))))
                    ;; _meta.progressToken is what makes a server emit
                    ;; notifications/progress for this call — without it the
                    ;; streaming path below never fires. The custom headers
                    ;; mirror x-mcp-header-annotated parameters into
                    ;; Mcp-Param-* (SEP-2243).
                    result (mcp/request! conn "tools/call"
                                         {:name tool-name
                                          :arguments arguments
                                          :_meta {:progressToken (protocol/progress-token)}}
                                         {:timeout-ms timeout-ms
                                          :on-notification (on-update-progress! (:on-update opts))
                                          :http-headers (when (= :streamable-http
                                                                 (:transport conn))
                                                          (mcp-http/x-mcp-param-headers
                                                           schema arguments))})
                    formatted (protocol/format-result result)
                    guard-options (guard/resolve-options (catalog/settings state))
                    guarded (guard/guard-text (:text formatted) guard-options)
                    details (guard/guarded-details (:guard guarded)
                                                   (guard/bound-mcp-result result
                                                                           (:details-max-bytes guard-options)))]
                (if (:is-error formatted)
                  (catalog/return-error (:text guarded))
                  (cond-> {:content (:text guarded) :is-error false}
                    (seq details) (assoc :details details))))
              (catalog/return-error (str "MCP call failed: "
                                         (or (some-> (get-in state [:servers server :error]) deref)
                                             "not connected")))))
          (catch Exception e
            (catalog/return-error (str "MCP call failed: " (ex-message e)))))))))

(defn script-tool-records
  "The cached MCP catalog as run_code sandbox tool maps, keyed by the
   prefixed name (run_code.md T2 — replaces the retired mcpScript tool):
   {name → {:name :label :description :parameters :streams? :execute}}.
   Every enabled, configured server's cached tools are listed (no spawn —
   the call connects lazily), so the sandbox sees the catalog the `mcp`
   proxy searches; calls go through call-mcp-tool, so failure backoff,
   auth and the output guard apply unchanged. A name claimed by more than
   one tool (possible under :tool-prefix :none) is dropped — silently
   calling the wrong server would be worse than the name being absent."
  [state]
  (let [candidates (for [[server definition] (sort-by key (:mcp-servers (:config state)))
                         :when (not (or (true? (:disabled definition))
                                        (catalog/misconfigured? definition)))
                         tool (or (catalog/cached-tools state server) [])]
                     [(catalog/display-name state server :tool (:name tool) (:name tool)) server tool])]
    (into {}
          (keep (fn [[name entries]]
                  (when (= 1 (count entries))
                    (let [[_ server tool] (first entries)]
                      [name
                       {:name name
                        :label (str "MCP: " (:name tool))
                        :description (or (:description tool) "(no description)")
                        :parameters (or (:inputSchema tool)
                                        {:type "object" :properties {}})
                        :streams? true
                        :execute (fn [args & [on-update]]
                                   (call-mcp-tool state server (:name tool) args
                                                  {:on-update on-update
                                                   :input-schema (:inputSchema tool)}))}]))))
          (group-by first candidates))))

(defn read-mcp-resource
  "Read a resource by URI on a server (the read_<resource> direct-tool
   executor, pi executeCall resourceUri path). Ensures the connection;
   text/string contents are joined, blobs summarized. Returns the kmet
   tool result shape with output-guard details."
  [state server uri]
  (let [definition (catalog/server-definition state server)]
    (cond
      (nil? definition)
      (catalog/return-error (str "Server \"" server "\" not found. Use mcp({}) to see available servers."))

      (catalog/disabled? state server)
      (catalog/return-error (str "Server \"" server "\" is disabled. Run /mcp enable " server
                                 " and /reload to enable it."))

      :else
      (if-let [failed-ago (catalog/failure-age-seconds state server)]
        (catalog/return-error (str "Server \"" server "\" not available (last failed "
                                   failed-ago "s ago)"))
        (try
          (let [conn (ensure-lazy-connected state server)]
            (if conn
              (let [result (mcp/read-resource conn uri)
                    contents (or (:contents result) [])
                    texts (keep (fn [c]
                                  (case (:type c)
                                    "text" (:text c)
                                    "string" (:text c)
                                    "blob" (str "[resource " uri ": "
                                                (or (:mimeType c) "?")
                                                ", " (count (or (:data c) ""))
                                                " bytes — not rendered]")
                                    nil))
                                contents)
                    text (cond
                           (seq texts) (str/join "\n" texts)
                           (seq contents) (pr-str contents)
                           :else "(empty resource)")
                    guard-options (guard/resolve-options (catalog/settings state))
                    guarded (guard/guard-text text guard-options)
                    details (guard/guarded-details (:guard guarded)
                                                   (guard/bound-mcp-result result
                                                                           (:details-max-bytes guard-options)))]
                (cond-> {:content (:text guarded) :is-error false}
                  (seq details) (assoc :details details)))
              (catalog/return-error (str "MCP resource read failed: "
                                         (or (some-> (get-in state [:servers server :error]) deref)
                                             "not connected")))))
          (catch Exception e
            (catalog/return-error (str "MCP resource read failed: " (ex-message e)))))))))

;; ─── Dispatch (§9.2) ───────────────────────────────────────────────────

(defn execute
  "Proxy tool dispatch over STATE-ATOM (the §10.1 state atom) and PARAMS
   (§9.1). The atom is deref'd per dispatch so connect (which refreshes the
   cache and tools) is followed by a fresh view. ON-UPDATE (streaming
   tools) receives progress notifications as partial content while a call
   runs."
  [state-atom params & [on-update]]
  (let [params (or params {})
        state @state-atom]
    (or (validate-params params)
        (let [regex? (flag (:regex params))
              include-schemas? (flag (:includeSchemas params))]
          (cond
            (some? (:search params))
            (search/search-text state (:search params) regex? (:server params)
                                include-schemas? (:limit params) (:offset params))

            (some? (:describe params))
            (status/describe-text state (:describe params))

            (some? (:tool params))
            (let [match (catalog/find-tool state (:tool params))
                  server (or (:server params)
                             (when (and match (not= :ambiguous match))
                               (:server match)))]
              (if (nil? server)
                (if (= :ambiguous match)
                  (catalog/return-error (str "Tool \"" (:tool params) "\" matches multiple servers. "
                                             "Specify a server with mcp({ tool: ..., server: \"...\" })."))
                  (catalog/return-error (str "Tool \"" (:tool params) "\" not found. "
                                             "Use mcp({ search: \"...\" }) to search.")))
                ;; the wire call uses the RAW tool name — prefixed spellings
                ;; (plan §10.4: tool: "server_tool") resolve through the cache
                (call-mcp-tool state server
                               (if (and match (not= :ambiguous match))
                                 (:name (:tool match))
                                 (:tool params))
                               (:args params)
                               {:on-update on-update
                                ;; the resolved record carries the schema the
                                ;; SEP-2243 Mcp-Param-* headers derive from
                                :input-schema (when (and match (not= :ambiguous match))
                                                (:inputSchema (:tool match)))})))

            (some? (:connect params))
            (try
              (let [conn ((:ensure-connected-fn state) (:connect params))
                    ;; fresh view after the connect refreshed the cache
                    fresh @state-atom]
                (if conn
                  (status/list-text fresh (:connect params))
                  (catalog/return-error (str "Failed to connect to \"" (:connect params) "\""))))
              (catch Exception e
                (catalog/return-error (str "Failed to connect to \"" (:connect params) "\": "
                                           (ex-message e)))))

            (some? (:disconnect params))
            (do ((:disconnect-fn state) (:disconnect params))
                {:content (str "Disconnected \"" (:disconnect params) "\".") :is-error false})

            (some? (:list params))
            (status/list-text state (:list params))

            (some? (:server params))
            (status/list-text state (:server params))

            :else
            {:content (status/status-text state) :is-error false})))))
