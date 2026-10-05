(ns kmet.extensions.mcp-adapter.core
  "mcp-adapter entry (pi: index.ts installMcpAdapter + init.ts, adapted to
   kmet's extension contract and babashka — sync, no SDK).

   Init/shutdown (§10.2), state (§10.1), proxy + direct tool registration
   (§10.4/§10.5), connection lifecycle (§10.3), the /mcp command (§10.6),
   events (§10.7: :session-start eager/keep-alive connects,
   :session-shutdown disconnect-all, self-registered skill)."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.extensions.mcp-adapter.auth :as auth]
            [kmet.extensions.mcp-adapter.config :as config]
            [kmet.extensions.mcp-adapter.metadata :as metadata]
            [kmet.extensions.mcp-adapter.names :as names]
            [kmet.extensions.mcp-adapter.panel :as panel]
            [kmet.extensions.mcp-adapter.prompts :as prompts]
            [kmet.extensions.mcp-adapter.search :as search]
            [kmet.extensions.mcp-adapter.status :as status]
            [kmet.extensions.mcp-adapter.tool-proxy :as proxy]
            [kmet.extensions.mcp-adapter.tool-source :as script-source]
            [kmet.extensions.mcp-adapter.setup :as setup]
            [kmet.extension :as ext]
            [kmet.libs.concurrent :as concurrent]
            [kmet.libs.mcp.client :as mcp]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.tui.theme :as theme]))

(def ^:private state-atom (atom nil))

(def ^:private spawn concurrent/spawn)

(declare ensure-connected! disconnect-server! sync-direct-tools!
         sync-prompt-commands! register-proxy-tool!
         handle-import open-setup-panel!
         update-status-bar!)

;; ─── State (§10.1) ────────────────────────────────────────────────────────

(defn- build-servers
  "Build the :servers map from config: {name {:definition .. :conn atom
   :error atom :failed-at atom :lock Object :resyncing atom
   :resync-changes atom :resync-covered atom}} — :failed-at records the
   last connect failure (60s backoff window, pi FAILURE_BACKOFF_MS),
   :resyncing is the list_changed resync gate, :resync-changes counts
   notifications (each one gets a generation at dispatch) and
   :resync-covered is the newest generation a completed resync has
   listed."
  [config]
  (into {} (map (fn [[name definition]]
                  [name {:definition definition
                         :conn (atom nil)
                         :error (atom nil)
                         :failed-at (atom nil)
                         :lock (Object.)
                         :resyncing (atom false)
                         :resync-changes (atom 0)
                         :resync-covered (atom 0)}]))
        (:mcp-servers config)))

(defn- rebuild-servers
  "Rebuild :servers from a fresh config, preserving conn/error/lock/
   resync state for servers that persist (used by /mcp refresh)."
  [state config]
  (let [old (:servers @state)]
    (into {} (map (fn [[name definition]]
                    [name (assoc (or (get old name)
                                     {:conn (atom nil)
                                      :error (atom nil)
                                      :failed-at (atom nil)
                                      :lock (Object.)
                                      :resyncing (atom false)
                                      :resync-changes (atom 0)
                                      :resync-covered (atom 0)})
                                 :definition definition)]))
          (:mcp-servers config))))

(defn- init-state
  "Fresh §10.1 state map over API + CONFIG."
  [api config]
  (let [state (atom nil)]
    (reset! state {:api api
                   :config config
                   :cache (metadata/load-cache)
                   :servers (build-servers config)
                   :tool-names {}
                   :registered-direct (atom {})
                   :registered-prompts (atom {})
                   :reaper-stop (atom false)})
    (swap! state assoc
           ;; a state snapshot is immutable, but a connect mutates the
           ;; atom (cache, catalog): callers holding a snapshot read the
           ;; fresh map back through this getter
           :read-state-fn (fn [] @state)
           :ensure-connected-fn (fn [name] (ensure-connected! state name))
           :disconnect-fn (fn [name] (disconnect-server! state name)))
    state))

;; ─── Failure backoff (pi init.ts: FAILURE_BACKOFF_MS) ─────────────────────
;; A failed connect records :failed-at + :error; within the 60s window the
;; LAZY connect path (tool calls) reports "not available (last failed Ns
;; ago)" instead of retrying; explicit connects (/mcp connect,
;; mcp({connect})) bypass the window and clear the failure on success.

(defn- record-failure!
  [state name message]
  (let [{:keys [error failed-at]} (get-in @state [:servers name])]
    (when error (reset! error message))
    (when failed-at (reset! failed-at (concurrent/monotonic-ms)))
    (update-status-bar! state)))

(defn- clear-failure!
  [state name]
  (let [{:keys [error failed-at]} (get-in @state [:servers name])]
    (when error (reset! error nil))
    (when failed-at (reset! failed-at nil))))

;; ─── Connection lifecycle (§10.3) ─────────────────────────────────────────

(defn- refresh-after-connect!
  "On successful connect or resync: refresh the metadata cache + save
   (storing the era the conn negotiated as the next connect's hint),
   resync direct tools, resync prompt commands, rebuild the proxy
   description (§10.3)."
  [state name conn tools prompts resources & [resource-templates]]
  (when-let [definition (get-in @state [:config :mcp-servers name])]
    (let [protocol-era (some-> (:era conn) deref)]
      (swap! state (fn [st]
                     (assoc st :cache
                            (metadata/update-entry! (:cache st) name definition
                                                    (:settings (:config st))
                                                    :tools tools
                                                    :prompts prompts
                                                    :resources resources
                                                    :resource-templates resource-templates
                                                    :protocol-era protocol-era))))
      (sync-direct-tools! state)
      (prompts/sync-prompt-commands! state)
      (register-proxy-tool! state)
      (update-status-bar! state))))

(defn- resync-server-catalog!
  "One catalog re-list pass on the live conn: the capability-gated
   prompts/resources/templates lists included, then refresh-after-connect!."
  [state name conn]
  (let [capabilities (or (:capabilities conn) {})]
    (refresh-after-connect! state name conn
                            (if (:tools capabilities)
                              (mcp/list-all-tools conn)
                              [])
                            (if (:prompts capabilities)
                              (mcp/list-all-prompts conn)
                              [])
                            (if (:resources capabilities)
                              (mcp/list-all-resources conn)
                              [])
                            (if (:resources capabilities)
                              (mcp/list-all-resource-templates conn)
                              []))))

(defn- refresh-server-catalog!
  "Re-list a connected server's catalog on the live conn and push it
   through refresh-after-connect! (metadata cache, direct tools, prompt
   commands, proxy description). GEN is the notification generation
   (allocated at dispatch) this call answers. Runs on a background
   thread: the transports deliver notifications from inside a request
   wait.

   One resync session at a time, and at most two listing passes per
   session: the owning pass plus one deferred pass when further
   notifications arrived while it listed. A session records the newest
   generation it has covered, so a handler thread that runs after the
   catalog was already re-listed skips its session — that keeps a server
   that re-notifies on every list from chasing its own notifications,
   while a notification that lands after the last pass still gets one."
  [state name conn gen]
  (let [busy (get-in @state [:servers name :resyncing])
        changes (get-in @state [:servers name :resync-changes])
        covered (get-in @state [:servers name :resync-covered])]
    (when (and busy (mcp/alive? conn))
      (if (compare-and-set! busy false true)
        (try
          (when (> gen @covered)
            (loop [rerun? false]
              (let [seen @changes]
                (try
                  (resync-server-catalog! state name conn)
                  (catch Exception _ nil))
                ;; everything dispatched up to now is reflected in the
                ;; catalog this pass just read
                (reset! covered @changes)
                (when (and (not rerun?) (> @changes seen))
                  (recur true)))))
          (finally
            (reset! busy false)
            ;; a notification that landed after the last pass covered it
            ;; (or while this call held a no-op session) must not be
            ;; stranded — pick it up once the gate is free
            (when (> @changes @covered)
              (spawn (fn [] (refresh-server-catalog! state name conn @changes))))))
        nil))))

(defn- handle-list-changed!
  "Conn-level notification handler (client.clj dispatch): a
   list_changed notification means the server's catalog changed, so
   re-list it and resync — without this the cached tools, direct tools
   and prompt commands stay stale until a manual /mcp connect or
   /mcp refresh. Gated on the capability the server advertised:
   re-listing an unadvertised method would only draw -32601."
  [state name conn msg]
  (let [capabilities (or (:capabilities conn) {})
        changed? (case (:method msg)
                   "notifications/tools/list_changed" (contains? capabilities :tools)
                   "notifications/prompts/list_changed" (contains? capabilities :prompts)
                   "notifications/resources/list_changed" (contains? capabilities :resources)
                   false)]
    (when changed?
      ;; background: the notification arrives on a transport thread and
      ;; the resync must never block the request that delivered it. The
      ;; generation is allocated here, at dispatch, so a resync that has
      ;; already re-listed the catalog can tell a notification it covered
      ;; from one that arrived afterwards.
      (let [gen (swap! (get-in @state [:servers name :resync-changes]) inc)]
        (spawn
         (fn []
           (try (refresh-server-catalog! state name conn gen)
                (catch Exception _ nil))))))))

(defn- log-debug!
  "Best-effort opt-in debug logging. kmet.debug is not part of the shared
   extension context (only kmet.extension, kmet.tui.* and kmet.libs.* are
   injected there), so resolve it at runtime the way the review extension
   does, and stay silent where it is unreachable."
  [& parts]
  (try
    (when-let [log-fn (resolve 'kmet.debug/log)]
      (apply log-fn parts))
    (catch Throwable _ nil)))

(defn- subscription-filter
  "The spec subscription filter built from a server's advertised
   listChanged capabilities; nil when it advertises none (nothing to
   subscribe to, and asking would only draw an empty agreement)."
  [capabilities]
  (let [filter (cond-> {}
                 (get-in capabilities [:tools :listChanged])
                 (assoc :toolsListChanged true)
                 (get-in capabilities [:prompts :listChanged])
                 (assoc :promptsListChanged true)
                 (get-in capabilities [:resources :listChanged])
                 (assoc :resourcesListChanged true))]
    (when (seq filter) filter)))

(defn- log-subscription-ack!
  "Debug-log the subscription the server agreed to: a requested
   list_changed type it dropped from the acknowledgment leaves that
   catalog stale, and nothing else surfaces that."
  [name requested msg]
  (let [agreed (set (keys (:notifications (:params msg))))
        dropped (seq (remove agreed (keys requested)))]
    (when dropped
      (log-debug! "mcp: " name " refused subscription notifications: "
                  (str/join ", " (map name dropped))))))

(defn- start-subscription!
  "After a modern connect, open the server's one 2026-07-28 subscription
   from its advertised listChanged capabilities. A gap is re-listened to
   under the hood, and :on-restored runs the same generation-gated resync
   the list_changed handler uses, so a change missed while the stream was
   down is picked up. A legacy conn keeps receiving the server's
   broadcast list_changed notifications — nothing to do there."
  [state name result]
  (let [conn (:conn result)]
    (when-let [filter (and (mcp/modern? conn)
                           (subscription-filter (:capabilities result)))]
      (try
        (mcp/listen! conn filter
                     {:on-frame (fn [msg]
                                  (when (= protocol/ack-method (:method msg))
                                    (log-subscription-ack! name filter msg)))
                      :on-restored
                      (fn []
                        (let [gen (swap! (get-in @state [:servers name :resync-changes])
                                         inc)]
                          (spawn (fn []
                                   (refresh-server-catalog! state name conn gen)))))})
        (catch Exception e
          (log-debug! "mcp: " name " subscription failed: " (ex-message e)))))))

(defn- connect-with-auth
  "Connect a server, wiring the HTTP auth fns (§7.8.5), the conn-level
   notification handler (list_changed → resync) and, for a modern conn,
   the 2026-07-28 subscription. The metadata cache's :protocol-era — when
   the entry is fresh — is passed as the connect hint, so a known server
   skips era detection (3.7)."
  [state name]
  (let [definition (get-in @state [:config :mcp-servers name])
        settings (:settings (:config @state))
        cached-era (:protocol-era (metadata/server-entry (:cache @state) name
                                                         definition settings))
        result (mcp/connect! definition
                             (assoc (auth/make-auth-fns name definition)
                                    :protocol-era cached-era
                                    :on-notification
                                    (fn [conn msg]
                                      (handle-list-changed! state name conn msg))))]
    (start-subscription! state name result)
    result))

(defn- ensure-connected!
  "Locked, idempotent connect (§10.3): connected + alive? → return; dead →
   clear, retry; else connect (stdio/http by definition), handshake,
   tools/list. On success: record :conn, clear :error, refresh cache + save,
   resync direct tools, rebuild the proxy description. On failure: record
   :error, throw."
  [state name]
  (let [{:keys [conn lock]} (get-in @state [:servers name])]
    (when (nil? lock)
      (throw (ex-info (str "MCP server \"" name "\" not found")
                      {:type :mcp-error})))
    (locking lock
      (let [current @conn]
        (cond
          (and current (mcp/alive? current)) current

          :else
          (do
            (when current (try (mcp/close! current) (catch Exception _ nil)))
            (reset! conn nil)
            (try
              ;; the connect result also carries :conn — bind it under a
              ;; different name so the outer conn atom is not shadowed
              (let [result (connect-with-auth state name)
                    new-conn (:conn result)
                    tools (:tools result)
                    prompts (:prompts result)
                    resources (:resources result)
                    resource-templates (:resource-templates result)]
                (reset! conn new-conn)
                (clear-failure! state name)
                (refresh-after-connect! state name new-conn tools prompts resources
                                        resource-templates)
                new-conn)
              (catch Exception e
                (record-failure! state name (ex-message e))
                (throw e)))))))))

(defn- disconnect-server!
  "Close + kill a server connection (§10.3). OPTS are passed to
   mcp/close! — teardown paths pass :terminate-http-session? false so
   a synchronous :session-shutdown handler never waits on an
   unresponsive streamable-HTTP server."
  [state name & [opts]]
  (when-let [{:keys [conn]} (get-in @state [:servers name])]
    (when-let [c @conn]
      (try (mcp/close! c opts) (catch Exception _ nil)))
    (reset! conn nil))
  (update-status-bar! state)
  nil)

(defn- disconnect-all!
  "Close every connection (session shutdown / extension shutdown) — the
   shutdown event is synchronous, so the streamable-HTTP session DELETEs
   are skipped (the sessions expire on their own)."
  [state]
  (doseq [name (keys (:servers @state))]
    (disconnect-server! state name {:terminate-http-session? false})))

;; ─── Direct tools (§10.5) ─────────────────────────────────────────────────

(defn- template-vars
  "The {var} placeholders of a level-1 URI template, in order — the
   parameter names of a template read tool."
  [uri-template]
  (mapv second (re-seq #"\{([^{}]+)\}" (str uri-template))))

(defn- template-schema
  "Input schema for a template read tool: one required string per {var}."
  [vars]
  {:type "object"
   :properties (into {} (map (fn [v] [v {:type "string"
                                         :description (str "Value for " v)}])
                             vars))
   :required (vec vars)})

(defn- catalog-entries
  "Assignment input for names/assign-names: every cached tool plus its
   read_<resource> entries for enabled, configured servers. Independent of
   the direct-tools / include / exclude filters — which tools are
   registered must never rename the ones that are."
  [state]
  (let [config (:config @state)
        settings (:settings config)]
    (vec
     (mapcat
      (fn [[name definition]]
        (when-not (or (true? (:disabled definition))
                      (and (not (:command definition)) (not (:url definition))))
          (let [entry (metadata/server-entry (:cache @state) name definition settings)]
            (concat
             (for [tool (:tools entry)]
               {:server name :kind :tool :id (:name tool) :name (:name tool)})
             (when (not= false (:expose-resources definition))
               (concat
                (for [resource (:resources entry)]
                  {:server name :kind :resource :id (:uri resource)
                   :name (str "read_" (names/resource-tool-name (:name resource)))})
                (for [template (:resource-templates entry)]
                  {:server name :kind :resource :id (:uriTemplate template)
                   :name (str "read_" (names/resource-tool-name (:name template)))})))))))
      (sort-by key (:mcp-servers config))))))

(defn- direct-tools-specs
  "Resolve direct-tool specs from the metadata cache only (never spawns;
   §10.5.1): MCP_DIRECT_TOOLS env → only listed servers (config ignored),
   __none__ → none; else server :direct-tools (bool | name list), else
   settings :direct-tools, else false. Skips disabled and misconfigured
   servers. Names come from the deterministic catalog assignment
   (:tool-names, names/assign-names — computed by sync-direct-tools!) so
   display and registration always agree. Phase 2: server
   :include-tools/:exclude-tools globs filter the registration (pi
   isToolAllowed), and :expose-resources (default true) registers
   read_<resource> tools alongside the tool list (pi resource tools).
   STATE is the §10.1 atom."
  [state]
  (let [config (:config @state)
        settings (:settings config)
        env-raw (System/getenv "MCP_DIRECT_TOOLS")
        env-servers (when (and env-raw (not= "__none__" env-raw))
                      (set (map str/trim (str/split env-raw #","))))
        env-none? (= "__none__" env-raw)
        assigned (:tool-names @state)
        specs (atom [])]
    (doseq [[name definition] (sort-by key (:mcp-servers config))]
      (when-not (or (true? (:disabled definition))
                    (and (not (:command definition)) (not (:url definition))))
        (let [filter (cond
                       env-none? false
                       env-servers (contains? env-servers name)
                       :else (if (contains? definition :direct-tools)
                               (:direct-tools definition)
                               (if (:direct-tools settings) true false)))
              include (:include-tools definition)
              exclude (:exclude-tools definition)]
          (when filter
            (let [entry (metadata/server-entry (:cache @state) name definition settings)
                  name-for (fn [kind id raw]
                             (or (get assigned [name kind id])
                                 (names/prefixed-name
                                  name raw
                                  (names/effective-mode definition settings))))
                  add-spec! (fn [kind id tool-name description schema
                                 & [resource-uri resource-template]]
                              (when (names/tool-allowed? name tool-name include exclude)
                                (swap! specs conj
                                       (cond-> {:server name
                                                :original tool-name
                                                :prefixed (name-for kind id tool-name)
                                                :description description
                                                :input-schema schema}
                                         resource-uri (assoc :resource-uri resource-uri)
                                         resource-template
                                         (assoc :resource-template resource-template)))))]
              (doseq [tool (:tools entry)]
                (when (or (true? filter) (some #{(:name tool)} filter))
                  (add-spec! :tool (:name tool) (:name tool)
                             (or (:description tool) "")
                             (:inputSchema tool))))
              (when (not= false (:expose-resources definition))
                (doseq [resource (:resources entry)]
                  (let [base-name (str "read_" (names/resource-tool-name (:name resource)))]
                    (when (or (true? filter) (some #{base-name} filter))
                      (add-spec! :resource (:uri resource) base-name
                                 (or (:description resource)
                                     (str "Read resource: " (:uri resource)))
                                 nil
                                 (:uri resource)))))
                ;; a parameterized resource (file:///{path}) becomes a
                ;; read tool whose parameters are the template variables
                (doseq [template (:resource-templates entry)]
                  (let [base-name (str "read_" (names/resource-tool-name (:name template)))
                        vars (template-vars (:uriTemplate template))]
                    (when (or (true? filter) (some #{base-name} filter))
                      (add-spec! :resource (:uriTemplate template) base-name
                                 (str (or (:description template)
                                          "Read resource: ")
                                      " (" (:uriTemplate template) ")")
                                 (template-schema vars)
                                 nil
                                 (:uriTemplate template)))))))))))
    @specs))

(defn- title-str
  [v]
  (when (and (string? v) (seq v)) v))

(defn- title-arg
  [args k]
  (when (map? args)
    (or (get args k) (get args (name k)))))

(defn- title-mcp
  [args]
  (cond
    (title-str (title-arg args :search))
    (str "mcp search \"" (title-arg args :search) "\""
         (when-let [s (title-str (title-arg args :server))] (str " in " s)))
    (title-str (title-arg args :describe))
    (str "mcp describe " (title-arg args :describe))
    (title-str (title-arg args :tool))
    (str "mcp " (title-arg args :tool)
         (when-let [s (title-str (title-arg args :server))] (str " on " s)))
    (title-str (title-arg args :connect))
    (str "mcp connect " (title-arg args :connect))
    (title-str (title-arg args :disconnect))
    (str "mcp disconnect " (title-arg args :disconnect))
    (title-str (title-arg args :list))
    (str "mcp list " (title-arg args :list))
    (title-str (title-arg args :server))
    (str "mcp list " (title-arg args :server))
    :else "mcp status"))

(defn- title-direct-tool
  [prefixed]
  (fn [args]
    (let [pv (when (map? args)
               (some (fn [[_ v]]
                       (cond (title-str v) v
                             ;; numbers stringify (limits, counts); other
                             ;; types (maps, vectors, booleans) carry no
                             ;; one-line summary
                             (number? v) (str v)))
                     args))
          one (when (string? pv)
                (let [one (str/replace (str/trim pv) #"\s+" " ")]
                  (when (seq one)
                    (if (> (count one) 80) (str (subs one 0 80) "…") one))))]
      (if one
        (str prefixed " " one)
        prefixed))))

(defn- truncate
  "Truncate S to N chars with an ellipsis."
  [s n]
  (let [s (or s "")]
    (if (<= (count s) n) s (str (subs s 0 n) "…"))))

(defn- normalize-direct-schema
  "Ensure {:type \"object\" :properties {}} base; pass through
   properties/required as-is (§10.5)."
  [input-schema]
  (let [schema (or input-schema {})]
    {:type "object"
     :properties (or (:properties schema) {})
     :required (or (:required schema) [])}))

(defn- direct-tool-server-names
  "Configured servers whose tools are direct (pi
   getMissingConfiguredDirectToolServers hasDirectTools): MCP_DIRECT_TOOLS
   env → only the listed servers (config ignored); __none__ → none; else
   server :direct-tools (bool | name list), else settings :direct-tools,
   else false. Skips disabled and misconfigured servers."
  [state]
  (let [config (:config @state)
        settings (:settings config)
        env-raw (System/getenv "MCP_DIRECT_TOOLS")
        env-servers (when (and env-raw (not= "__none__" env-raw))
                      (set (map str/trim (str/split env-raw #","))))
        env-none? (= "__none__" env-raw)]
    (for [[name definition] (:mcp-servers config)
          :when (not (true? (:disabled definition)))
          :when (or (:command definition) (:url definition))
          :when (cond
                  env-none? false
                  env-servers (contains? env-servers name)
                  :else (if (contains? definition :direct-tools)
                          (boolean (:direct-tools definition))
                          (boolean (:direct-tools settings))))]
      name)))

(defn- missing-direct-tool-servers
  "Direct-tool servers without a fresh metadata cache entry — their direct
   tools are not registered (§10.5 registers from the cache only)."
  [state]
  (let [config (:config @state)
        settings (:settings config)]
    (filterv (fn [name]
               (nil? (metadata/server-entry (:cache @state) name
                                            (get-in config [:mcp-servers name])
                                            settings)))
             (direct-tool-server-names state))))

(defn- bootstrap-direct-tools!
  "Background-connect direct-tool servers missing from the metadata cache
   so their direct tools register without a manual first connect (pi
   init.ts direct-tools-bootstrap: after the startup connects, connect the
   still-missing configured direct-tool servers; each connect refreshes
   the cache and resyncs direct tools via refresh-after-connect!).
   Failures are recorded by ensure-connected! and swallowed here — the
   direct tools stay unregistered until a later connect, exactly like pi."
  [state]
  (when-let [names (seq (missing-direct-tool-servers state))]
    (spawn
     (fn []
       (doseq [name names]
         (try
           ((:ensure-connected-fn @state) name)
           (catch Exception _ nil)))))))

(defn- sync-direct-tools!
  "Diff-based resync (§10.5): register new/updated (by fingerprint),
   unregister removed. Runs at init and after every connect/refresh.
   Resource read_* tools execute via proxy/read-mcp-resource; tools via
   proxy/call-mcp-tool. All declare :streams? — progress notifications
   stream as partial content while a call runs. Also syncs the script
   sandbox's MCP tool source (run_code.md T2) — same trigger, same catalog."
  [state]
  (let [config (:config @state)
        mode-for (fn [server]
                   (names/effective-mode (get-in config [:mcp-servers server])
                                         (:settings config)))
        _ (swap! state assoc
                 :tool-names (names/assign-names (catalog-entries state) mode-for))
        specs (direct-tools-specs state)
        next-names (set (map :prefixed specs))
        registered @(:registered-direct @state)
        fingerprint (fn [spec]
                      (pr-str (select-keys spec
                                           [:server :original :prefixed
                                            :description :input-schema
                                            :resource-uri :resource-template])))
        make-execute (fn [spec]
                       (fn [args & [on-update]]
                         (cond
                           (:resource-uri spec)
                           (proxy/read-mcp-resource @state (:server spec)
                                                    (:resource-uri spec))

                           (:resource-template spec)
                           (proxy/read-mcp-resource @state (:server spec)
                                                    (mcp/expand-uri-template
                                                     (:resource-template spec)
                                                     (or args {})))

                           :else
                           (proxy/call-mcp-tool @state (:server spec)
                                                (:original spec) args
                                                {:on-update on-update
                                                 :input-schema (:input-schema spec)}))))]
    (doseq [spec specs]
      (let [fp (fingerprint spec)]
        (when (not= fp (get registered (:prefixed spec)))
          (ext/register-tool! (:api @state)
                              {:name (:prefixed spec)
                               :label (str "MCP: " (:original spec))
                               :description (or (:description spec) "(no description)")
                               :prompt-snippet (truncate (:description spec) 100)
                               :parameters (normalize-direct-schema (:input-schema spec))
                               :streams? true
                               :execute (make-execute spec)
                               :title (title-direct-tool (:prefixed spec))})
          (swap! (:registered-direct @state) assoc (:prefixed spec) fp))))
    (doseq [name (remove next-names (keys registered))]
      (ext/unregister-tool! (:api @state) name)
      (swap! (:registered-direct @state) dissoc name))
    (script-source/sync! (:api @state) state)))

;; ─── Proxy tool (§10.4) ───────────────────────────────────────────────────

(defn- build-proxy-description
  "Dynamic proxy description from config + cache (§10.4): server tool
   counts, lazy-connect note, usage examples."
  [state]
  (let [config (:config @state)
        settings (:settings config)
        summaries (for [[name definition] (sort-by key (:mcp-servers config))
                        :let [entry (metadata/server-entry (:cache @state) name
                                                           definition settings)
                              count (count (:tools entry))]
                        :when (and (not (true? (:disabled definition)))
                                   (pos? count))]
                    (str name " (" count " tools)"))
        prefix (or (:tool-prefix settings) :server)]
    (str "MCP gateway: status, search, describe, call. "
         (if (seq summaries)
           (str "Servers: " (str/join ", " summaries) ". ")
           "No servers with cached tools. ")
         "Servers connect lazily on first use. "
         "For several calls in one request use the run_code tool — MCP tools are in its surface by name. "
         "search: \"screenshot\" · tool: \"" (name prefix) "_tool\" args: {...}.")))

(defn- register-proxy-tool!
  "Register the mcp proxy tool (replaces by name — re-registered after
   every connect/refresh so the system prompt sees current availability).
   :streams? — progress notifications stream as partial content while a
   call runs."
  [state]
  (ext/register-tool! (:api @state)
                      {:name "mcp"
                       :label "MCP"
                       :description (build-proxy-description state)
                       :prompt-snippet "MCP gateway — status, search, describe, and single MCP tool calls"
                       :parameters {:type "object"
                                    :properties
                                    {"tool" {:type "string"
                                             :description "MCP tool name to call (e.g. 'server_toolname')"}
                                     "args" {:type "object"
                                             :description "Arguments as a JSON object (a JSON string is also accepted)"}
                                     "server" {:type "string"
                                               :description "Scope to / disambiguate a server"}
                                     "search" {:type "string"
                                               :description "Search tools by name/description"}
                                     "regex" {:type "boolean"
                                              :description "Treat search as regex (default false)"}
                                     "includeSchemas" {:type "boolean"
                                                       :description "Schemas in search output (default true)"}
                                     "describe" {:type "string"
                                                 :description "Show a tool's parameters"}
                                     "connect" {:type "string"
                                                :description "Connect a server now (+ refresh)"}
                                     "disconnect" {:type "string"
                                                   :description "Disconnect a server"}
                                     "list" {:type "string"
                                             :description "List a server's tools"}
                                     "limit" {:type "number"
                                              :description "Search limit (default 12)"}
                                     "offset" {:type "number"
                                               :description "Search offset (default 0)"}}
                                    :required []}
                       :streams? true
                       :execute (fn [params & [on-update]]
                                  (proxy/execute state params on-update))
                       :title title-mcp}))

;; ─── /mcp command (§10.6) ─────────────────────────────────────────────────

(defn- show-text-dialog!
  "Show the extension's scrollable TextDialog (panel.clj — built on
   kmet.tui, mounted via ui-custom) for multi-line command output."
  [state title text]
  (ext/ui-custom (:api @state)
                 (fn [_tui _th _kb close]
                   (panel/make-text-dialog title text close))
                 {:overlay true
                  ;; the dialog draws its own titled frame — skip the host
                  ;; overlay's default border
                  :overlay-options {:anchor :center :width 82 :border :none}}))

(defn- notify-or-print
  "Output a command result: the transient flash (ui-notify) for
   single-line messages, the extension's scrollable text dialog for
   multi-line ones — a flash is a single line and cannot display
   status/search/list output (pi shows the interactive panel instead).
   println headless."
  [state ctx message & [title]]
  (cond
    (not (:has-ui ctx)) (println message)
    (str/includes? (or message "") "\n")
    (show-text-dialog! state (or title "MCP") message)
    :else
    (ext/ui-notify (:api @state) message "info")))

(defn- handle-connect
  [state server-name ctx]
  (if (seq server-name)
    (try
      (let [conn ((:ensure-connected-fn @state) server-name)]
        (if conn
          (notify-or-print state ctx (:content (status/list-text @state server-name))
                           (str "MCP: " server-name))
          (notify-or-print state ctx (str "Failed to connect to \"" server-name "\""))))
      (catch Exception e
        (notify-or-print state ctx (str "Failed to connect to \"" server-name "\": "
                                        (ex-message e)))))
    (notify-or-print state ctx "Usage: /mcp connect <server>")))

(defn- handle-enable-disable
  [state server-name disabled ctx]
  (if (seq server-name)
    (let [{:keys [path changed]} (config/set-server-disabled! server-name disabled)]
      (notify-or-print state ctx
                       (if changed
                         (str (if disabled "Disabled" "Enabled") " server \"" server-name
                              "\" in " path " — run /reload to apply")
                         (str "Server \"" server-name "\" is already "
                              (if disabled "disabled" "enabled")))))
    (notify-or-print state ctx (str "Usage: /mcp " (if disabled "disable" "enable") " <server>"))))

(defn- handle-refresh
  "Reload the EDN config: add/remove servers (disconnecting dropped ones),
   resync tools + prompts, rebuild the description (§10.6)."
  [state ctx]
  (let [config (config/load-config)
        current-names (set (keys (:mcp-servers (:config @state))))]
    (doseq [name (remove (set (keys (:mcp-servers config))) current-names)]
      ((:disconnect-fn @state) name))
    (swap! state assoc :config config :servers (rebuild-servers state config))
    (auth/configure-storage! (:settings config))
    (sync-direct-tools! state)
    (prompts/sync-prompt-commands! state)
    (bootstrap-direct-tools! state)
    (register-proxy-tool! state)
    (notify-or-print state ctx "MCP config reloaded.")
    (update-status-bar! state)))

(defn- open-browser
  "Open a URL with the platform helper (xdg-open / open /
   termux-open-url)."
  [url]
  (let [cmd (cond
              (System/getenv "TERMUX_VERSION") ["termux-open-url" url]
              :else (if (fs/which "xdg-open") ["xdg-open" url] ["open" url]))]
    (try
      @(proc/process cmd {:out :discard :err :discard})
      (catch Exception _ nil))))

(defn- build-interaction
  "The §7.8 interaction map for the OAuth flow, from the extension ctx.
   The manual-paste prompt (pi onAuthorizationInput) is raced against the
   browser callback; :abort-prompt! (pi manualAbort.abort) dismisses the
   pending prompt dialog and unblocks the prompt when the callback wins —
   called by the flow's finally. The prompt is the extension's own
   kmet.tui-built dialog (panel.clj make-prompt-dialog) mounted via
   ui-custom — no host dialog capabilities."
  [state ctx]
  (let [has-ui (:has-ui ctx)
        signal (when-let [s (:signal ctx)]
                 (try (s) (catch Exception _ nil)))
        prompt-cancelled (atom false)
        prompt-pending (atom nil)
        prompt-close (atom nil)
        abort-prompt! (fn []
                        (reset! prompt-cancelled true)
                        (when-let [p @prompt-pending]
                          (deliver p ::cancelled))
                        (when-let [close @prompt-close]
                          (close)))]
    {:signal (or signal (atom false))
     :has-ui has-ui
     :open-url open-browser
     :abort-prompt! abort-prompt!
     :notify (fn [event]
               (case (:type event)
                 :auth-url
                 (notify-or-print state ctx
                                  (str "Open this URL to authenticate:\n\n"
                                       (:url event)
                                       "\n\nComplete login in your browser."))
                 :device-code
                 (notify-or-print state ctx
                                  (str "Open " (:verification-uri event)
                                       " and enter the code: " (:user-code event)))
                 (notify-or-print state ctx (str "MCP auth: " (:message event)))))
     :prompt (fn [prompt-map]
               (if has-ui
                 (let [p (promise)
                       dialog (atom nil)
                       finish (fn [v]
                                (when-let [close @dialog] (close))
                                (deliver p v))]
                   (reset! prompt-pending p)
                   (ext/ui-custom
                    (:api @state)
                    (fn [_tui th _kb host-close]
                      ;; the host close is (fn [result] ...) — finish and
                      ;; abort-prompt! call it with no args, so adapt here
                      ;; (same contract fix as make-text-dialog)
                      (let [close (fn [] (host-close nil))]
                        (reset! dialog close)
                        (reset! prompt-close close)
                        (panel/make-prompt-dialog th (:message prompt-map)
                                                  (fn [v] (finish v))
                                                  (fn [] (finish nil)))))
                    {:overlay true
                     :overlay-options {:anchor :center :width 60}})
                   (deref p))
                 ;; headless: wait for the browser callback without a prompt
                 (do (Thread/sleep 600000) nil)))}))

(defn- handle-auth
  "Run the OAuth flow for a server on a background future (§10.6 auth) —
   the flow never blocks the command handler."
  [state server-name ctx]
  (if (seq server-name)
    (let [definition (get-in @state [:config :mcp-servers server-name])]
      (cond
        (nil? definition)
        (notify-or-print state ctx (str "Server \"" server-name "\" not found in config"))

        (not (:url definition))
        (notify-or-print state ctx (str "Server \"" server-name
                                        "\" has no URL — OAuth requires HTTP transport"))

        (not= :oauth (:auth definition))
        (notify-or-print state ctx (str "Server \"" server-name
                                        "\" is not configured for OAuth "
                                        "(set :auth :oauth in mcp.edn)"))

        :else
        (do
          (notify-or-print state ctx (str "Starting OAuth flow for \"" server-name
                                          "\" — complete login in your browser."))
          (spawn
           (fn []
             (try
               (auth/run-flow! server-name definition
                               (build-interaction state ctx))
               (notify-or-print state ctx (str "OAuth authentication successful for \""
                                               server-name "\"."
                                               (when (:has-ui ctx) " The connection will use the new token on next use.")))
               (catch Exception e
                 (notify-or-print state ctx (str "Failed to authenticate \"" server-name
                                                 "\": " (ex-message e))))))))))
    (notify-or-print state ctx "Usage: /mcp auth <server>")))

;; ─── McpPanel (§10.6 — pi openMcpPanel) ───────────────────────────────────

(defn- panel-connection-status
  "McpPanel connection status for a server (pi getConnectionStatus):
   :disabled / :connected / :needs-auth / :failed / :idle. Safe when the
   server is missing from :servers (e.g. /mcp refresh while the panel is
   open) — nil connections fall through to :idle."
  [state name]
  (let [definition (get-in @state [:config :mcp-servers name])
        {:keys [conn failed-at]} (get-in @state [:servers name])]
    (cond
      (true? (:disabled definition)) :disabled
      (and conn @conn (mcp/alive? @conn)) :connected
      (and (= :oauth (:auth definition))
           (= :none (auth/auth-status name definition))) :needs-auth
      (and failed-at @failed-at) :failed
      :else :idle)))

(defn- mcp-status-text
  "Compact footer status: \"MCP <connected>/<enabled>\" - enabled counts
   every non-disabled server, connected counts servers with a live
   connection. nil clears the slot (no servers configured, none
   connected, or :mcp-footer-status :off). Rev 2 of the extension dropped
   the verbose
   :full/:compact modes and the icon prefix for a single terse form."
  [state]
  (let [config (:config @state)
        settings (:settings config)
        servers (:mcp-servers config)
        mode (if (string? (:mcp-footer-status settings))
               (keyword (:mcp-footer-status settings))
               (or (:mcp-footer-status settings) :compact))]
    (when (and (seq servers) (not= :off mode))
      (let [{:keys [enabled connected]}
            (reduce (fn [acc name]
                      (let [s (panel-connection-status state name)]
                        (cond
                          (= s :disabled) acc
                          (= s :connected) (update (update acc :enabled inc)
                                                   :connected inc)
                          :else (update acc :enabled inc))))
                    {:enabled 0 :connected 0}
                    (keys servers))]
        ;; idle fleets stay out of the footer entirely
        (when (pos? connected)
          (str "MCP " connected "/" enabled))))))

(defn- update-status-bar!
  "Refresh the footer's MCP status line (pi: init.ts updateStatusBar).
   Sets the keyed extension status \"mcp\" (footer line 3) to
   mcp-status-text, accent-colored to match pi
   (ui.theme.fg(\"accent\", …)). nil clears the key. Inert in
   headless/print mode — ext/ui-set-status dispatches through the
   runtime registry and no-ops before the interactive layout exists."
  [state]
  (when-let [api (:api @state)]
    (let [text (mcp-status-text state)]
      (ext/ui-set-status api "mcp"
                         (when text
                           (theme/fg (theme/get-current-theme) :accent text))))))

(defn- apply-direct-tools-changes!
  "Persist the panel's direct-tools CHANGES into the project config and
   apply them live (pi writeDirectToolsConfig + applyDirectToolConfigChanges
   + syncToolSurface): update the in-memory config, resync direct tools,
   rebuild the proxy description, notify."
  [state changes ctx]
  (config/write-direct-tools! changes)
  (swap! state update-in [:config :mcp-servers]
         (fn [servers]
           (reduce (fn [acc [name v]]
                     (if (contains? acc name)
                       (update acc name assoc :direct-tools v)
                       acc))
                   servers changes)))
  (sync-direct-tools! state)
  (register-proxy-tool! state)
  (notify-or-print state ctx "Direct tools updated for this session."))

(defn- panel-callbacks
  "McpPanel callbacks over the extension state (pi buildMcpPanelCallbacks)."
  [state ctx]
  {:reconnect (fn [name]
                (try
                  (boolean ((:ensure-connected-fn @state) name))
                  (catch Exception _ false)))
   :can-authenticate (fn [name]
                       (let [definition (get-in @state [:config :mcp-servers name])]
                         (and definition
                              (not (true? (:disabled definition)))
                              (= :oauth (:auth definition)))))
   :authenticate (fn [name]
                   (let [p (promise)]
                     (spawn
                      (fn []
                        (try
                          (auth/run-flow! name
                                          (get-in @state [:config :mcp-servers name])
                                          (build-interaction state ctx))
                          (deliver p {:ok true :message (str "OAuth finished for " name)})
                          (catch Exception e
                            (deliver p {:ok false :message (ex-message e)})))))
                     p))
   :get-connection-status (fn [name] (panel-connection-status state name))
   :get-failure-message
   (fn [name]
     (when-let [error (get-in @state [:servers name :error])]
       @error))
   :refresh-cache-after-reconnect
   (fn [name] (get-in @state [:cache :servers name]))})

(defn- open-panel!
  "Show the pi-style McpPanel over the TUI (§10.6) — ui-custom overlay,
   width 82 centered like pi's overlayOptions. Non-blocking: kmet command
   handlers run on the input thread, so the panel drives itself and
   reports through its done callback (which persists changes and closes)."
  [state ctx]
  (ext/ui-custom
   (:api @state)
   (fn [tui _th kb close]
     (panel/make-mcp-panel
      (:config @state) (:cache @state) (panel-callbacks state ctx) tui
      (fn [result]
        (when (and (not (:cancelled result)) (seq (:changes result)))
          (apply-direct-tools-changes! state (:changes result) ctx))
        (close result))
      kb))
   {:overlay true
    ;; the panel draws its own frame — skip the host overlay's default border
    :overlay-options {:anchor :center :width 82 :border :none}}))

(defn- handle-mcp-command
  [state args ctx]
  (let [trimmed (str/trim (or args ""))
        space (str/index-of trimmed " ")
        sub (if (nil? space) trimmed (subs trimmed 0 space))
        rest-args (if (nil? space) "" (str/trim (subs trimmed (inc space))))]
    (case sub
      ("status" "")
      (if (and (:has-ui ctx) (seq (:mcp-servers (:config @state))))
        (open-panel! state ctx)
        (notify-or-print state ctx (status/status-text @state) "MCP servers"))

      "search"
      (let [[q regex] (str/split rest-args #"\s+" 2)]
        (notify-or-print state ctx (:content (search/search-text @state (or q "") (proxy/flag regex)
                                                                 nil true 12 0))
                         "MCP search"))

      "list"
      (notify-or-print state ctx (:content (if (seq rest-args)
                                             (status/list-text @state rest-args)
                                             (status/list-all-text @state)))
                       (if (seq rest-args) (str "MCP: " rest-args) "MCP servers"))

      "connect"
      (handle-connect state rest-args ctx)

      "disconnect"
      (if (seq rest-args)
        (do ((:disconnect-fn @state) rest-args)
            (notify-or-print state ctx (str "Disconnected \"" rest-args "\".")))
        (notify-or-print state ctx "Usage: /mcp disconnect <server>"))

      "enable" (handle-enable-disable state rest-args false ctx)
      "disable" (handle-enable-disable state rest-args true ctx)
      "refresh" (handle-refresh state ctx)
      "auth" (handle-auth state rest-args ctx)

      "logout"
      (if (seq rest-args)
        (do (auth/logout! rest-args)
            (notify-or-print state ctx (str "OAuth credentials cleared for \"" rest-args "\".")))
        (notify-or-print state ctx "Usage: /mcp logout <server>"))

      "prompts"
      (notify-or-print state ctx (prompts/prompts-text state) "MCP prompts")

      "setup"
      (if (:has-ui ctx)
        (open-setup-panel! state ctx)
        (notify-or-print state ctx "The setup panel requires the interactive TUI (headless: edit mcp.edn directly)."))

      "import"
      (handle-import state ctx)

      (notify-or-print state ctx (str "Unknown /mcp subcommand: " sub)))))

(def ^:private mcp-subcommands
  ["status" "search" "list" "connect" "disconnect"
   "enable" "disable" "refresh" "auth" "logout"
   "prompts" "setup" "import"])

(defn- mcp-completions
  "Argument completions: subcommands, then server names for the
   server-taking subcommands."
  [state arg-prefix]
  (let [prefix (str/trim (or arg-prefix ""))
        space (str/index-of prefix " ")]
    (if (nil? space)
      (let [subs (filter #(str/starts-with? % prefix) mcp-subcommands)]
        (mapv (fn [s] {:value s :label s}) subs))
      (let [sub (subs prefix 0 space)
            server-prefix (str/trim (subs prefix (inc space)))]
        (when (contains? #{"connect" "disconnect" "enable" "disable" "auth" "logout"} sub)
          (let [servers (filter #(str/starts-with? % server-prefix)
                                (keys (:mcp-servers (:config @state))))]
            (mapv (fn [s] {:value (str sub " " s) :label s}) servers)))))))

;; ─── Setup panel + host-config import (§10.6 setup/import) ───────────────

(defn- reload-config!
  "Reload the EDN config into the live state (used after setup-panel
   writes) — servers added/removed, tools + prompts resynced."
  [state]
  (let [config (config/load-config)
        current-names (set (keys (:mcp-servers (:config @state))))]
    (doseq [name (remove (set (keys (:mcp-servers config))) current-names)]
      ((:disconnect-fn @state) name))
    (swap! state assoc :config config :servers (rebuild-servers state config))
    (auth/configure-storage! (:settings config))
    (sync-direct-tools! state)
    (prompts/sync-prompt-commands! state)
    (register-proxy-tool! state)
    (update-status-bar! state)))

(defn- setup-callbacks
  "The setup panel's callbacks over the extension state (pi
   buildSetupCallbacks): every write goes to the project config file, then
   the config is reloaded live; add-known/add-server test the connection."
  [state _ctx]
  {:presets (mapv (fn [p] (select-keys p [:id :name :summary]))
                  config/known-server-presets)
   :discover-imports (fn [] (config/host-config-discoveries))
   :add-known
   (fn [preset]
     (try
       (let [{:keys [path changed]} (config/write-server-entry!
                                     (:id preset) (:entry preset))]
         (reload-config! state)
         (spawn (fn []
                  (try ((:ensure-connected-fn @state) (:id preset))
                       (catch Exception _ nil))))
         {:ok true
          :message (str "Added " (:name preset) " to " path
                        (when changed " — connecting…"))})
       (catch Exception e
         {:ok false :message (str "Failed to add server: " (ex-message e))})))
   :add-server
   (fn [name entry]
     (try
       (let [{:keys [path]} (config/write-server-entry! name entry)]
         (reload-config! state)
         (let [conn (try ((:ensure-connected-fn @state) name)
                         (catch Exception _ nil))]
           {:ok true
            :message (str "Saved " name " to " path
                          (if conn " — connection OK." " — saved (connect failed, see /mcp status)."))}))
       (catch Exception e
         {:ok false :message (str "Failed to add server: " (ex-message e))})))
   :adopt-imports
   (fn [kinds]
     (try
       (let [discoveries (filter (fn [d] (contains? (set kinds) (:kind d)))
                                 (config/host-config-discoveries))
             {:keys [path added skipped]} (config/adopt-host-configs! discoveries)]
         (reload-config! state)
         {:ok true
          :message (str "Adopted " (count added) " server" (when (not= 1 (count added)) "s")
                        " into " path
                        (when (seq skipped) (str "; skipped " (count skipped) " already defined")))})
       (catch Exception e
         {:ok false :message (str "Failed to adopt host configs: " (ex-message e))})))
   :scaffold
   (fn []
     (let [path (config/project-config-path)]
       (if (fs/exists? path)
         {:ok false :message (str "Project config already exists at " path)}
         (do (fs/create-dirs (fs/parent path))
             (spit path "{:mcp-servers {}}\n")
             {:ok true :message (str "Created " path)}))))})

(defn- open-setup-panel!
  "Show the setup panel (setup.clj — built on kmet.tui, mounted via
   ui-custom like the McpPanel)."
  [state ctx]
  (ext/ui-custom
   (:api @state)
   (fn [_tui _th kb close]
     (setup/make-setup-panel (setup-callbacks state ctx)
                             (fn [_result] (close nil))
                             kb))
   {:overlay true
    ;; the panel draws its own frame — skip the host overlay's default border
    :overlay-options {:anchor :center :width 82 :border :none}}))

(defn- handle-import
  "/mcp import — adopt ALL discovered host configs into the project file
   (headless path; the setup panel does the interactive variant)."
  [state ctx]
  (let [discoveries (config/host-config-discoveries)]
    (if (seq discoveries)
      (let [{:keys [path added skipped]} (config/adopt-host-configs! discoveries)]
        (reload-config! state)
        (notify-or-print state ctx
                         (str "Adopted " (count added) " server" (when (not= 1 (count added)) "s")
                              " from host configs into " path
                              (when (seq skipped)
                                (str "; skipped " (count skipped) " already defined"))
                              ".")))
      (notify-or-print state ctx "No host MCP configs found (Cursor/Claude/Codex/opencode/windsurf/vscode)."))))

;; ─── Idle reaper (pi init.ts idle-timeout) ────────────────────────────────
;; settings :idle-timeout (minutes, default 10, 0 disables) disconnects
;; servers whose connections have been idle past the window; server
;; :idle-timeout overrides. :keep-alive servers default to no reaping
;; (pi: persistsAfterFirstSpawn → 0). A daemon thread checks every 30s.

(defn- idle-timeout-minutes
  "The effective idle timeout for a server (minutes; 0 = never reap)."
  [state name]
  (let [definition (get-in @state [:config :mcp-servers name])
        settings (:settings (:config @state))
        global (if (number? (:idle-timeout settings)) (:idle-timeout settings) 10)]
    (if (contains? definition :idle-timeout)
      (or (:idle-timeout definition) 0)
      (if (= :keep-alive (:lifecycle definition)) 0 global))))

(defn- reap-idle-servers!
  "Disconnect every connected server idle past its timeout. A conn with a
   live subscription is exempt: a quiet stdio subscription sees no traffic
   at all, and disconnecting would end it."
  [state]
  (doseq [[name {:keys [conn]}] (:servers @state)]
    (when-let [c @conn]
      (let [timeout-min (idle-timeout-minutes state name)
            idle-ms (- (concurrent/monotonic-ms) (mcp/last-used c))]
        (when (and (pos? timeout-min)
                   (not (mcp/listening? c))
                   (> idle-ms (* timeout-min 60000)))
          (disconnect-server! state name))))))

(defn- start-idle-reaper!
  "Background daemon: check every 30s until :reaper-stop is set (shutdown)."
  [state]
  (spawn
   (fn []
     (loop []
       (Thread/sleep 30000)
       (when-not @(:reaper-stop @state)
         (try (reap-idle-servers! state) (catch Exception _ nil))
         (recur))))))

;; ─── Events (§10.7) ───────────────────────────────────────────────────────

(defn- on-session-start
  "Background future connects of :eager/:keep-alive servers — never blocks
   session start (§10.3)."
  [state _event _ctx]
  (spawn
   (fn []
     (doseq [[name definition] (:mcp-servers (:config @state))]
       (when (and (not (true? (:disabled definition)))
                  (contains? #{:eager :keep-alive} (:lifecycle definition)))
         (try
           ((:ensure-connected-fn @state) name)
           (catch Exception _ nil)))))))

(defn- on-session-shutdown
  [state _event _ctx]
  (disconnect-all! state))

;; ─── Init / shutdown (§10.2) ──────────────────────────────────────────────

(defn init
  "Extension init (required by the loader)."
  [api]
  ;; the host agent dir (KMET_CODING_AGENT_DIR-aware) holds mcp.edn,
  ;; mcp-cache.edn and mcp-oauth.edn — set before any path is read
  (when-let [dir (ext/get-agent-dir api)]
    (config/set-agent-dir! dir))
  (let [config (config/load-config)
        _ (config/ensure-global-template!)
        state (init-state api config)]
    (reset! state-atom state)
    (auth/configure-storage! (:settings config))
    ;; 2. proxy tool (§10.4)
    (register-proxy-tool! state)
    ;; 2b. the MCP catalog joins the run_code sandbox as a tool source —
    ;; registered by sync-direct-tools! below (settings :script-mode
    ;; gates it; run_code.md T2 retired the separate mcpScript tool)
    ;; 3. direct tools from cache (§10.5)
    (sync-direct-tools! state)
    ;; 3b. prompt commands from cache (pi resolveCachedPrompts)
    (prompts/sync-prompt-commands! state)
    ;; 3c. direct-tools bootstrap (pi init.ts): background-connect servers
    ;; whose direct tools aren't in the metadata cache yet, so they
    ;; register without a manual first connect
    (bootstrap-direct-tools! state)
    ;; 4. /mcp command + completions (§10.6)
    (ext/register-command! api
                           {:name "mcp"
                            :description "MCP server status, search, connect, auth, setup"
                            :get-argument-completions (fn [arg-prefix]
                                                        (mcp-completions state arg-prefix))
                            ;; handlers receive (ctx args) — the extension
                            ;; context first, then the command args (pi passes
                            ;; (args ctx); kmet's dispatch and contract use
                            ;; (ctx args), see test-extensions "ctx dispatch")
                            :handler (fn [ctx args]
                                       (handle-mcp-command state args ctx))})
    ;; 4b. idle reaper (settings :idle-timeout)
    (start-idle-reaper! state)
    ;; 4c. footer status line (pi: updateStatusBar at init end) — initial
    ;; baseline; no-op until the interactive layout + UI registry exist.
    (update-status-bar! state)
    ;; 5. events (§10.7)
    (ext/on-event api :session-start (fn [event ctx]
                                       (update-status-bar! state)
                                       (on-session-start state event ctx)))
    (ext/on-event api :session-shutdown (fn [event ctx]
                                          (on-session-shutdown state event ctx)))
    ;; contribute the mcp skill (self-registered content — no host path
    ;; enumeration, so jar/zip artifacts work unexpanded)
    (ext/register-skill! api (slurp (io/resource "skills/mcp/SKILL.md"))
                         {:location "mcp-adapter:skills/mcp/SKILL.md"})))

(defn shutdown
  "Extension shutdown (optional): close all connections, kill process
   trees, stop the idle reaper, close the OAuth callback server.
   Idempotent."
  [_api]
  (when-let [state @state-atom]
    (reset! (:reaper-stop @state) true)
    (disconnect-all! state)
    (auth/shutdown!)
    (reset! state-atom nil)))
