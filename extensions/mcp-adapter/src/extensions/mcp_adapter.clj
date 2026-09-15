(ns extensions.mcp-adapter
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
            [extensions.mcp-adapter.auth :as auth]
            [extensions.mcp-adapter.client :as client]
            [extensions.mcp-adapter.config :as config]
            [extensions.mcp-adapter.metadata :as metadata]
            [extensions.mcp-adapter.panel :as panel]
            [extensions.mcp-adapter.prompts :as prompts]
            [extensions.mcp-adapter.tool-proxy :as proxy]
            [extensions.mcp-adapter.script :as script]
            [extensions.mcp-adapter.setup :as setup]
            [kmet.extension :as ext]
            [kmet.libs.concurrent :as concurrent]
            [kmet.tui.theme :as theme]))

(def ^:private state-atom (atom nil))

(def ^:private spawn concurrent/spawn)

(declare ensure-connected! disconnect-server! sync-direct-tools!
         sync-prompt-commands! register-proxy-tool!
         handle-import open-setup-panel!
         update-status-bar!)

(def ^:private builtin-tool-names
  #{"read" "bash" "edit" "write" "grep" "find" "ls" "mcp"})

;; ─── State (§10.1) ────────────────────────────────────────────────────────

(defn- build-servers
  "Build the :servers map from config: {name {:definition .. :conn atom
   :error atom :failed-at atom :lock Object}} — :failed-at records the
   last connect failure (60s backoff window, pi FAILURE_BACKOFF_MS)."
  [config]
  (into {} (map (fn [[name definition]]
                  [name {:definition definition
                         :conn (atom nil)
                         :error (atom nil)
                         :failed-at (atom nil)
                         :lock (Object.)}]))
        (:mcp-servers config)))

(defn- rebuild-servers
  "Rebuild :servers from a fresh config, preserving conn/error/lock for
   servers that persist (used by /mcp refresh)."
  [state config]
  (let [old (:servers @state)]
    (into {} (map (fn [[name definition]]
                    [name (assoc (or (get old name)
                                     {:conn (atom nil)
                                      :error (atom nil)
                                      :failed-at (atom nil)
                                      :lock (Object.)})
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
                   :registered-direct (atom {})
                   :registered-prompts (atom {})
                   :reaper-stop (atom false)})
    (swap! state assoc
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
    (when failed-at (reset! failed-at (System/currentTimeMillis)))
    (update-status-bar! state)))

(defn- clear-failure!
  [state name]
  (let [{:keys [error failed-at]} (get-in @state [:servers name])]
    (when error (reset! error nil))
    (when failed-at (reset! failed-at nil))))

;; ─── Connection lifecycle (§10.3) ─────────────────────────────────────────

(defn- refresh-after-connect!
  "On successful connect: refresh the metadata cache + save, resync direct
   tools, resync prompt commands, rebuild the proxy description (§10.3)."
  [state name tools prompts resources]
  (when-let [definition (get-in @state [:config :mcp-servers name])]
    (swap! state (fn [st]
                   (assoc st :cache
                          (metadata/update-entry! (:cache st) name definition
                                                  (:settings (:config st))
                                                  tools prompts resources))))
    (sync-direct-tools! state)
    (prompts/sync-prompt-commands! state)
    (register-proxy-tool! state)
    (update-status-bar! state)))

(defn- connect-with-auth
  "Connect a server, wiring the HTTP auth fns (§7.8.5) when configured."
  [state name]
  (let [definition (get-in @state [:config :mcp-servers name])]
    (client/connect! definition (auth/make-auth-fns name definition))))

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
          (and current (client/alive? current)) current

          :else
          (do
            (when current (try (client/close! current) (catch Exception _ nil)))
            (reset! conn nil)
            (try
              ;; the connect result also carries :conn — bind it under a
              ;; different name so the outer conn atom is not shadowed
              (let [result (connect-with-auth state name)
                    new-conn (:conn result)
                    tools (:tools result)
                    prompts (:prompts result)
                    resources (:resources result)]
                (reset! conn new-conn)
                (clear-failure! state name)
                (refresh-after-connect! state name tools prompts resources)
                new-conn)
              (catch Exception e
                (record-failure! state name (ex-message e))
                (throw e)))))))))

(defn- disconnect-server!
  "Close + kill a server connection (§10.3)."
  [state name]
  (when-let [{:keys [conn]} (get-in @state [:servers name])]
    (when-let [c @conn]
      (try (client/close! c) (catch Exception _ nil)))
    (reset! conn nil))
  (update-status-bar! state)
  nil)

(defn- disconnect-all!
  "Close every connection (session shutdown / extension shutdown)."
  [state]
  (doseq [name (keys (:servers @state))]
    (disconnect-server! state name)))

;; ─── Direct tools (§10.5) ─────────────────────────────────────────────────

(defn- sanitize-tool-name
  "Lowercase; [^a-z0-9_] → _ (§10.5)."
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9_]" "_")))

(defn- server-prefix
  "Pi getServerPrefix (§10.5): :server default → sanitized server name;
   :mcp → \"mcp\"; :none/:short → bare (collision fallback by the caller)."
  [server-name mode]
  (case mode
    :none ""
    :short (let [short (sanitize-tool-name (str/replace server-name #"-?mcp$" ""))]
             (if (seq short) short "mcp"))
    :mcp "mcp"
    (sanitize-tool-name server-name)))

(defn- prefixed-tool-name
  "Pi formatToolName (§10.5): prefix + '_' + sanitized tool name; bare
   names with a collision (another server's tool or a builtin) fall back to
   the server prefix."
  [server-name tool-name mode seen]
  (let [sanitized (sanitize-tool-name tool-name)
        prefix (server-prefix server-name mode)
        name (if (seq prefix) (str prefix "_" sanitized) sanitized)]
    (if (or (contains? seen name)
            (and (empty? prefix) (contains? builtin-tool-names name)))
      (str (sanitize-tool-name server-name) "_" sanitized)
      name)))

(defn- resource-tool-name
  "Pi resourceNameToToolName: [^a-zA-Z0-9] → _, collapse runs, trim
   leading/trailing _, lowercase; empty or digit-start → prefixed with
   'resource'."
  [name]
  (let [result (-> (str name)
                   (str/replace #"[^a-zA-Z0-9]" "_")
                   (str/replace #"_+" "_")
                   (str/replace #"^_+|_+$" "")
                   str/lower-case)]
    (if (or (str/blank? result) (re-matches #"^[0-9].*" result))
      (str "resource" (when (seq result) (str "_" result)))
      result)))

(defn- direct-tools-specs
  "Resolve direct-tool specs from the metadata cache only (never spawns;
   §10.5.1): MCP_DIRECT_TOOLS env → only listed servers (config ignored),
   __none__ → none; else server :direct-tools (bool | name list), else
   settings :direct-tools, else false. Skips disabled and misconfigured
   servers. Naming per §10.5 with collision fallback. Phase 2: server
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
        prefix-mode (or (:tool-prefix settings) :server)
        specs (atom [])
        seen (atom #{})]
    (doseq [[name definition] (sort-by key (:mcp-servers config))]
      (when-not (or (true? (:disabled definition))
                    (and (not (:command definition)) (not (:url definition))))
        (let [filter (cond
                       env-none? false
                       env-servers (contains? env-servers name)
                       :else (if (contains? definition :direct-tools)
                               (:direct-tools definition)
                               (if (:direct-tools settings) true false)))
              mode (or (:tool-prefix definition) prefix-mode)
              include (:include-tools definition)
              exclude (:exclude-tools definition)]
          (when filter
            (let [entry (metadata/server-entry (:cache @state) name definition settings)
                  add-spec! (fn [tool-name description schema & [resource-uri]]
                              (when (proxy/tool-allowed? name tool-name include exclude)
                                (let [prefixed (prefixed-tool-name name tool-name mode @seen)]
                                  (swap! seen conj prefixed)
                                  (swap! specs conj
                                         (cond-> {:server name
                                                  :original tool-name
                                                  :prefixed prefixed
                                                  :description description
                                                  :input-schema schema}
                                           resource-uri (assoc :resource-uri resource-uri))))))]
              (doseq [tool (:tools entry)]
                (when (or (true? filter) (some #{(:name tool)} filter))
                  (add-spec! (:name tool)
                             (or (:description tool) "")
                             (:inputSchema tool))))
              (when (not= false (:expose-resources definition))
                (doseq [resource (:resources entry)]
                  (let [base-name (str "read_" (resource-tool-name (:name resource)))]
                    (when (or (true? filter) (some #{base-name} filter))
                      (add-spec! base-name
                                 (or (:description resource)
                                     (str "Read resource: " (:uri resource)))
                                 nil
                                 (:uri resource)))))))))))
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

(defn- title-preview
  [s n]
  (let [pv (when (string? s)
             (first (remove str/blank? (map str/trim (str/split-lines s)))))]
    (when (seq pv)
      (if (> (count pv) n) (str (subs pv 0 n) "…") pv))))

(defn- title-mcpscript
  [args]
  (if-let [pv (title-preview (title-arg args :code) 80)]
    (str "mcpScript " pv)
    "mcpScript"))

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
   stream as partial content while a call runs."
  [state]
  (let [specs (direct-tools-specs state)
        next-names (set (map :prefixed specs))
        registered @(:registered-direct @state)
        fingerprint (fn [spec]
                      (pr-str (select-keys spec
                                           [:server :original :prefixed
                                            :description :input-schema
                                            :resource-uri])))
        make-execute (fn [spec]
                       (fn [args & [on-update]]
                         (if (:resource-uri spec)
                           (proxy/read-mcp-resource @state (:server spec)
                                                    (:resource-uri spec))
                           (proxy/call-mcp-tool @state (:server spec)
                                                (:original spec) args
                                                {:on-update on-update}))))]
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
      (swap! (:registered-direct @state) dissoc name))))

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
                  :overlay-options {:anchor :center :width 82}}))

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
          (notify-or-print state ctx (:content (proxy/list-text @state server-name))
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
      (and conn @conn (client/alive? @conn)) :connected
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
    :overlay-options {:anchor :center :width 82}}))

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
        (notify-or-print state ctx (proxy/status-text @state) "MCP servers"))

      "search"
      (let [[q regex] (str/split rest-args #"\s+" 2)]
        (notify-or-print state ctx (:content (proxy/search-text @state (or q "") (proxy/flag regex)
                                                                nil true 12 0))
                         "MCP search"))

      "list"
      (notify-or-print state ctx (:content (if (seq rest-args)
                                             (proxy/list-text @state rest-args)
                                             (proxy/list-all-text @state)))
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
    :overlay-options {:anchor :center :width 82}}))

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
  "Disconnect every connected server idle past its timeout."
  [state]
  (doseq [[name {:keys [conn]}] (:servers @state)]
    (when-let [c @conn]
      (let [timeout-min (idle-timeout-minutes state name)
            idle-ms (- (System/currentTimeMillis) (client/last-used c))]
        (when (and (pos? timeout-min)
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

(defn- register-script-tool!
  "Register the mcpScript tool (pi index.ts — gated by settings
   :script-mode, default on)."
  [state]
  (ext/register-tool! (:api @state)
                      {:name "mcpScript"
                       :label "MCP Script"
                       :description (str "Run trusted Clojure that makes multiple MCP tool calls in one "
                                         "request — loop, filter, chain, or fan out between calls. "
                                         "For a single MCP call, search, describe, status check, or auth "
                                         "action, use the mcp tool instead. "
                                         "Discover with (tools/search {:query \"...\"}) — resolves to "
                                         "{:items [{:path :name :server :description :score}] :total "
                                         ":has-more :next-offset}, not an {:ok :data} envelope. "
                                         "Inspect with (tools/describe {:path \"...\"}) — the tool "
                                         "descriptor, or {:path :error {:code :message}}. "
                                         "Then call (tools/call path args) — resolves to {:ok true :data} "
                                         "or {:ok false :error {:code :message}} — or use direct flat "
                                         "calls when the name is already known; use (emit value) for "
                                         "user-visible output.")
                       :prompt-snippet "Batch multiple MCP tool calls in one Clojure request (loop, filter, chain)"
                       :parameters {:type "object"
                                    :properties
                                    {"code" {:type "string"
                                             :description "Trusted Clojure MCP script. Use (tools/call \"server_tool\" args) and (emit value)."}
                                     "timeoutMs" {:type "number"
                                                  :description "Execution timeout in milliseconds (default: 30000)"}}
                                    :required ["code"]}
                       :streams? true
                       :execute (fn [params & [on-update]]
                                  (let [params (or params {})
                                        code (or (:code params) "")
                                        timeout-ms (when (number? (:timeoutMs params))
                                                     (:timeoutMs params))]
                                    (script/run-script state code
                                                       {:timeout-ms timeout-ms
                                                        :on-update on-update})))
                       :title title-mcpscript}))

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
    ;; 2b. mcpScript tool (pi: settings.scriptMode !== false)
    (when (not= false (:script-mode (:settings config)))
      (register-script-tool! state))
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
