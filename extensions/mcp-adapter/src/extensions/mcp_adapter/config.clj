(ns extensions.mcp-adapter.config
  "EDN MCP config loading/merging for the mcp-adapter extension (§6 of the
   design contract, mcp-adapter.md at the repo root (§15.21) — pi:
   config.ts loadMcpConfig / writeProjectServerDisabledOverride, adapted to
   kmet: EDN only, exactly two sources, no imports/host discovery).

   Sources & precedence:
     - global  <agent-dir>/mcp.edn     (lower; the host agent dir —
                                       KMET_CODING_AGENT_DIR-aware)
     - project <cwd>/.kmet/mcp.edn     (higher — the only file this
                                       extension writes)

   Project wins with a per-field server merge + per-key settings merge.
   Credential-bearing fields are bound to the url that supplied them: when
   a higher-precedence source repoints a server at a different url, the
   lower entry's :headers/:bearer-token/:bearer-token-env/:oauth are
   dropped before merging (pi mergeServerMaps SECURITY note).

   Keys are read in camel or kebab form (see normalize-key) so a file
   copied from a pi-style JSON config works with light edits; unknown keys
   pass through unmodified. :lifecycle / :tool-prefix values accept string
   or keyword.

   Phase 2 additions: :include-tools/:exclude-tools/:search-keywords globs,
   :idle-timeout reaping, :output-guard tuning, :script-mode, :token-storage,
   :expose-resources, and host-config adoption (pi config.ts IMPORT_PATHS
   discovery + extractServers) — JSON host files only (codex config.toml has
   no TOML reader in bb; its config.json path is covered)."
  (:require [babashka.fs :as fs]
            [kmet.libs.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- read-text
  "The text of PATH, or nil when the file does not exist."
  [path]
  (when (fs/exists? path)
    (slurp path)))

(defn- write-text
  "Write TEXT to PATH (UTF-8), replacing any existing content."
  [path text]
  (spit path (str text)))

(def ^:private agent-dir-atom (atom nil))

(defn set-agent-dir!
  "Set the agent state dir (the host agent dir — ext/get-agent-dir; call
   first in init). The global config, cache and token files live under it."
  [dir]
  (reset! agent-dir-atom (str dir)))

(defn agent-dir
  "The agent state dir, or the host default ~/.kmet/agent when init has not
   set it (KMET_CODING_AGENT_DIR-aware via init)."
  []
  (or @agent-dir-atom (str (fs/path (fs/home) ".kmet" "agent"))))

(defn global-config-path
  "Global config file (<agent-dir>/mcp.edn)."
  []
  (str (fs/path (agent-dir) "mcp.edn")))

(defn project-config-path
  "Project config file (<cwd>/.kmet/mcp.edn) — the only file the extension
   writes."
  [& [cwd]]
  (str (or cwd (System/getProperty "user.dir")) "/.kmet/mcp.edn"))

;; ─── Key normalization (§6.3) ─────────────────────────────────────────────

(def ^:private key-aliases
  "Camel → kebab aliases for known keys. Unknown keys pass through
   unmodified."
  {:mcpServers :mcp-servers
   :directTools :direct-tools
   :toolPrefix :tool-prefix
   :requestTimeoutMs :request-timeout-ms
   :bearerToken :bearer-token
   :bearerTokenEnv :bearer-token-env
   :httpTransport :http-transport
   :disableProxyTool :disable-proxy-tool
   :clientId :client-id
   :clientSecret :client-secret
   :redirectUri :redirect-uri
   :authorizationServerUrl :authorization-server-url
   :skipIssuerMetadataValidation :skip-issuer-metadata-validation
   :grantType :grant
   :privateKeyFile :private-key-file
   :privateKeyJwk :private-key-jwk
   :tokenEndpoint :token-endpoint
   :tokenEndpointAuthMethod :token-endpoint-auth-method
   :includeTools :include-tools
   :excludeTools :exclude-tools
   :searchKeywords :search-keywords
   :idleTimeout :idle-timeout
   :exposeResources :expose-resources
   :outputGuard :output-guard
   :scriptMode :script-mode
   :tokenStorage :token-storage
   :hostConfigDiscovery :host-config-discovery
   :maxBytes :max-bytes
   :maxLines :max-lines
   :detailsMaxBytes :details-max-bytes
   :maxOutputBytes :max-output-bytes})

(defn- normalize-key
  "CamelCase known keys → kebab; everything else passes through (a kebab
   key that is also a known alias stays kebab)."
  [k]
  (if (keyword? k)
    (or (key-aliases k) k)
    k))

(defn- normalize-value
  "Keyword/string keyword-valued options (:lifecycle, :tool-prefix,
   :http-transport, :auth, :flow) → keyword."
  [v]
  (cond
    (keyword? v) v
    (string? v) (keyword v)
    :else v))

(def ^:private keyword-valued-keys
  #{:lifecycle :tool-prefix :http-transport :auth :flow :grant :algorithm
    :token-endpoint-auth-method :token-storage :host-config-discovery
    :mcp-footer-status})

(defn- normalize-map-value
  "Normalize a nested option map (:oauth, :output-guard): known camel keys
   → kebab, keyword-valued options → keywords, nil values dropped."
  [m]
  (into {}
        (keep (fn [[k v]]
                (let [k (normalize-key k)]
                  (when (some? v)
                    [k (if (keyword-valued-keys k)
                         (normalize-value v)
                         v)]))))
        m))

(defn- normalize-oauth
  "Normalize an :oauth map (nil → nil, false → false)."
  [oauth]
  (cond
    (nil? oauth) nil
    (false? oauth) false
    (map? oauth) (normalize-map-value oauth)
    :else oauth))

(defn- normalize-output-guard
  "Normalize an :output-guard value: false passes through (disables), a map
   gets key normalization (pi McpOutputGuardSettings)."
  [v]
  (cond
    (false? v) false
    (map? v) (normalize-map-value v)
    :else v))

(defn- normalize-server
  "Normalize one server entry: known camel keys → kebab, keyword-valued
   options → keywords, :oauth map normalization. Unknown keys pass through."
  [entry]
  (if (map? entry)
    (into {}
          (keep (fn [[k v]]
                  (let [k (normalize-key k)]
                    (when (some? v)
                      (cond
                        (= k :oauth) [k (normalize-oauth v)]
                        (= k :output-guard) [k (normalize-output-guard v)]
                        (keyword-valued-keys k) [k (normalize-value v)]
                        :else [k v])))))
          entry)
    entry))

(defn- normalize-settings
  [settings]
  (when (map? settings)
    (into {} (map (fn [[k v]]
                    (let [k (normalize-key k)]
                      [k (if (keyword-valued-keys k) (normalize-value v) v)])))
          settings)))

(defn- normalize-config
  "Normalize a raw config map: keys at the top level (:mcp-servers /
   :settings), server entries, and the :oauth sub-map."
  [raw]
  (when (map? raw)
    (let [raw (into {} (map (fn [[k v]] [(normalize-key k) v])) raw)
          settings (normalize-settings (:settings raw))]
      (cond-> {}
        (:mcp-servers raw) (assoc :mcp-servers
                                  (into {} (map (fn [[name entry]]
                                                  [name (normalize-server entry)]))
                                        (:mcp-servers raw)))
        settings (assoc :settings settings)))))

;; ─── Reading ──────────────────────────────────────────────────────────────

(defn read-config-file
  "Read + normalize one EDN config file. Returns nil when the file does
   not exist; throws ex-info (with the path) on a parse error or a
   non-map root."
  [path]
  (when (fs/exists? path)
    (let [raw (try
                (edn/read-string {:default (fn [_ _] nil)} (read-text path))
                (catch Exception e
                  (throw (ex-info (str "Failed to read MCP config at " path
                                       ": " (ex-message e))
                                  {:type :mcp-config-parse-error :path path}
                                  e))))]
      (when-not (map? raw)
        (throw (ex-info (str "Failed to read MCP config at " path
                             ": root value must be a map")
                        {:type :mcp-config-parse-error :path path})))
      (normalize-config raw))))

;; URL-bound auth fields: when a higher-precedence source repoints a server
;; at a different url, these MUST NOT be inherited from the lower entry —
;; otherwise the original endpoint's credentials would ship to the new url.
(def ^:private url-bound-auth-fields
  [:headers :bearer-token :bearer-token-env :oauth])

(defn- merge-server-maps
  "Per-field server merge (pi mergeServerMaps): later sources win per field.
   When the higher entry supplies a different :url, the lower entry's
   url-bound auth material is dropped first."
  [base next]
  (reduce (fn [merged [name definition]]
            (let [existing (get merged name)
                  base-entry (cond
                               (and existing (string? (:url definition))
                                    (not= (:url definition) (:url existing)))
                               (apply dissoc existing url-bound-auth-fields)

                               :else existing)]
              (assoc merged name
                     (merge base-entry definition))))
          base
          next))

(defn- merge-settings
  "Per-key settings merge (later wins)."
  [base next]
  (if (or base next) (merge base next) nil))

(defn- merge-configs
  "Merge two normalized configs (pi mergeConfigs)."
  [base next]
  (cond-> {}
    (or (:mcp-servers base) (:mcp-servers next))
    (assoc :mcp-servers (merge-server-maps (:mcp-servers base) (:mcp-servers next)))
    (or (:settings base) (:settings next))
    (assoc :settings (merge-settings (:settings base) (:settings next)))))

;; ─── Template (§6.4) ──────────────────────────────────────────────────────

(def ^:private template-edn
  "Schema-commented starter config written to the global file on first
   init."
  (str "{:settings {:direct-tools false           ;; global default for direct tools\n"
       "            :tool-prefix :server          ;; :server | :none | :short | :mcp\n"
       "            :disable-proxy-tool false}\n"
       "            ;; :script-mode true            ;; gates the mcpScript tool\n"
       "            ;; :mcp-footer-status :full     ;; :full | :compact | :off (footer)\n"
       "            ;; :show-status-icon true      ;; show the 🔌 prefix (footer)\n"
       "            ;; :idle-timeout 10             ;; minutes; 0 disables reaping\n"
       "            ;; :output-guard {}             ;; false disables; {\":max-bytes\" ..}\n"
       "            ;; :token-storage :auto         ;; :auto | :keyring | :file\n"
       "            ;; :host-config-discovery :off  ;; :off | :on — merge host mcp.json\n"
       " :mcp-servers\n"
       " {\"filesystem\" {:command \"npx\"\n"
       "                 :args [\"-y\" \"@modelcontextprotocol/server-filesystem\" \"/tmp\"]\n"
       "                 :lifecycle :lazy}        ;; :lazy | :eager | :keep-alive\n"
       "  ;; \"remote\" {:url \"https://mcp.example.com/mcp\"\n"
       "  ;;            :auth :bearer               ;; :bearer | :oauth | false\n"
       "  ;;            :bearer-token-env \"MY_TOKEN\" ;; or :bearer-token / :headers\n"
       "  ;;            :http-transport :streamable-http ;; :streamable-http | :sse\n"
       "  ;;            :lifecycle :lazy}\n"
       "  ;; \"notion\" {:url \"https://mcp.notion.com/mcp\"\n"
       "  ;;            :auth :oauth\n"
       "  ;;            :oauth {:flow :auto        ;; :auto | :pkce | :device\n"
       "  ;;                    :scopes [\"read\"]}}\n"
       "  ;; \"service\" {:url \"https://mcp.example.com/mcp\"\n"
       "  ;;            :auth :oauth\n"
       "  ;;            :oauth {:grant :client-credentials ;; machine grant, no browser\n"
       "  ;;                    :client-id \"svc\"          ;; auth: :client-secret-basic\n"
       "  ;;                    :client-secret \"...\"}}   ;; | :client-secret-post | :none\n"
       "  ;; \"svc-jwt\" {:url \"https://mcp.example.com/mcp\"\n"
       "  ;;            :auth :oauth\n"
       "  ;;            :oauth {:grant :jwt-bearer        ;; RFC 7523 signed assertion\n"
       "  ;;                    :private-key-file \"svc.pem\" ;; PKCS#8/PKCS#1 PEM, or\n"
       "  ;;                    :issuer \"kmet\"            ;; :private-key-jwk {..}\n"
       "  ;;                    :audience \"https://as.example/token\"}}\n"
       " }}\n"))

(defn ensure-global-template!
  "Write the schema-commented starter template to the global mcp.edn when
   it does not exist yet (§6.4). Returns the path when written, nil when
   the file already exists."
  []
  (let [path (global-config-path)]
    (when-not (fs/exists? path)
      (fs/create-dirs (fs/parent path))
      (write-text path template-edn)
      path)))

;; ─── enable/disable write (§6.5) ──────────────────────────────────────────

(defn- read-project-raw
  "The raw (un-normalized) project config map; {} when missing. Throws with
   the path on a parse error (pi writeProjectServerDisabledOverride)."
  []
  (let [path (project-config-path)]
    (if-not (fs/exists? path)
      {}
      (let [raw (try
                  (edn/read-string {:default (fn [_ _] nil)} (read-text path))
                  (catch Exception e
                    (throw (ex-info (str "Failed to read project MCP override at "
                                         path ": " (ex-message e))
                                    {:type :mcp-config-parse-error :path path}
                                    e))))]
        (when-not (map? raw)
          (throw (ex-info (str "Failed to read project MCP override at " path
                               ": root value must be a map")
                          {:type :mcp-config-parse-error :path path})))
        raw))))

(defn- lower-config-for
  "The merged config from every source EXCEPT the project file (pi: the
   lower-precedence sources)."
  []
  (or (read-config-file (global-config-path)) {:mcp-servers {}}))

(defn set-server-disabled!
  "Port of pi's writeProjectServerDisabledOverride, onto EDN (§6.5):

     Disable: set :disabled true on the server entry (create entry if
     absent).
     Enable:  remove the :disabled key; if the merged *lower* config
     (global file) has the server disabled, write :disabled false instead;
     if the entry is now empty, remove the server key.

   Writes pretty EDN (pr-str, trailing newline). Returns
   {:path str :changed bool} — :changed false when the write would be a
   no-op (entry already in the target state). The command tells the user to
   run /reload to apply."
  [server-name disabled]
  (let [path (project-config-path)
        raw (read-project-raw)
        servers-key (if (contains? raw :mcp-servers)
                      :mcp-servers
                      (if (contains? raw :mcpServers) :mcpServers :mcp-servers))
        servers (get raw servers-key {})
        existing (get servers server-name)
        _ (when (and (some? existing) (not (map? existing)))
            (throw (ex-info (str "Failed to update project MCP override at " path
                                 ": server \"" server-name "\" must be a map")
                            {:type :mcp-config-parse-error :path path})))
        next (if disabled
               (assoc (or existing {}) :disabled true)
               (let [cleaned (dissoc (or existing {}) :disabled)]
                 (if (:disabled (get-in (lower-config-for) [:mcp-servers server-name]))
                   (assoc cleaned :disabled false)
                   cleaned)))
        changed? (not= (or existing {}) next)]
    (when changed?
      ;; the project .kmet/ dir may not exist yet — create it before writing
      (fs/create-dirs (fs/parent path)))
    (if (and changed? (not (seq next)))
      (do (write-text path (str (pr-str (update raw servers-key dissoc server-name)) "\n"))
          {:path path :changed true})
      (if changed?
        (do (write-text path (str (pr-str (assoc-in raw [servers-key server-name] next)) "\n"))
            {:path path :changed true})
        {:path path :changed false}))))

;; ─── direct-tools write (§6.6 — pi writeDirectToolsConfig) ────────────────

(defn write-direct-tools!
  "Persist the McpPanel's direct-tools CHANGES into the project config
   (the only file the extension writes, like §6.5): true → :direct-tools
   true, false → explicit :direct-tools false, name list → :direct-tools
   [names]. Servers not in CHANGES keep their existing project entry.
   Returns {:path str :changed bool}. The in-memory config is updated by
   the caller (apply-direct-tools-changes!) — this only persists."
  [changes]
  (let [path (project-config-path)
        raw (read-project-raw)
        servers-key (if (contains? raw :mcp-servers)
                      :mcp-servers
                      (if (contains? raw :mcpServers) :mcpServers :mcp-servers))
        next (reduce-kv (fn [acc name v]
                          (assoc-in acc [servers-key name :direct-tools]
                                    (cond
                                      (true? v) true
                                      (false? v) false
                                      :else (vec v))))
                        raw changes)]
    (when (seq changes)
      (fs/create-dirs (fs/parent path))
      (write-text path (str (pr-str next) "\n")))
    {:path path :changed (boolean (seq changes))}))

;; ─── Host-config discovery + adoption (pi config.ts IMPORT_PATHS) ─────────
;; Known host MCP config files (JSON only — codex config.toml has no TOML
;; reader in bb; its config.json path is covered). Each entry lists the
;; candidate paths in precedence order; relative paths resolve against the
;; project cwd.

(def ^:private host-config-paths
  {:cursor [(str (fs/home) "/.cursor/mcp.json")]
   :claude-code [(str (fs/home) "/.claude/mcp.json")
                 (str (fs/home) "/.claude.json")
                 (str (fs/home) "/.claude/claude_desktop_config.json")]
   :claude-desktop [(str (fs/home) "/Library/Application Support/Claude/claude_desktop_config.json")]
   :codex [(str (fs/home) "/.codex/config.json")]
   :opencode [(str (fs/home) "/.config/opencode/opencode.json")
              "./opencode.json"]
   :windsurf [(str (fs/home) "/.windsurf/mcp.json")]
   :vscode [".vscode/mcp.json"]})

(defn- host-servers-map
  "The raw mcpServers map from a host JSON file, or nil. pi extractServers:
   claude.json-family files carry :mcpServers at the top level; a string
   value (e.g. \"npx -y pkg\") is split on whitespace into command+args."
  [path]
  (try
    (let [raw (json/parse-string (slurp path) true)]
      (when (map? raw)
        (let [servers (or (:mcpServers raw)
                          (get raw "mcpServers")
                          (:mcp-servers raw)
                          (get raw "mcp-servers"))]
          (when (map? servers) servers))))
    (catch Exception _ nil)))

(defn- host-entry->edn
  "Convert one host server entry to a kmet EDN entry. String commands are
   split on whitespace (command + args); entries without :command/:url are
   dropped (misconfigured). :env passes through as-is."
  [entry]
  (when (map? entry)
    (let [entry (normalize-server entry)
          command (:command entry)
          url (:url entry)]
      (cond
        (and (string? command) (seq (str/trim command)))
        (let [parts (str/split (str/trim command) #"\s+")]
          (if (seq (rest parts))
            (assoc (dissoc entry :command)
                   :command (first parts)
                   :args (vec (rest parts)))
            (assoc (dissoc entry :command) :command (first parts))))

        (and (vector? command) (seq command)) entry
        (and (string? url) (seq (str/trim url))) entry
        :else nil))))

(defn extract-host-servers
  "Every usable server from a host config file: {name edn-entry} (pi
   extractServers). Entries that do not normalize to a command or url are
   skipped. Returns {} for missing/unparsable files."
  [path]
  (into {}
        (keep (fn [[name entry]]
                (when-let [edn-entry (host-entry->edn entry)]
                  [name edn-entry])))
        (or (host-servers-map path) {})))

(defn host-config-discoveries
  "The host config files that exist and carry at least one server:
   [{:kind :cursor :path str :server-count n}] sorted by kind (pi
   IMPORT_PATHS order is deterministic)."
  [& [cwd]]
  (let [cwd (or cwd (System/getProperty "user.dir"))]
    (->> host-config-paths
         (sort-by key)
         (keep (fn [[kind paths]]
                 (some (fn [path]
                         (let [path (if (str/starts-with? path "./")
                                      (str cwd "/" (subs path 2))
                                      path)]
                           (when (fs/exists? path)
                             (let [servers (extract-host-servers path)]
                               (when (seq servers)
                                 {:kind kind :path path :server-count (count servers)})))))
                       paths)))
         vec)))

(defn host-configs-config
  "The merged config from ALL discovered host files (lowest precedence,
   first kind wins per server — pi loadDiscoveredHostConfigs)."
  [& [cwd]]
  (reduce (fn [acc discovery]
            (merge-configs acc {:mcp-servers (extract-host-servers (:path discovery))}))
          {:mcp-servers {}}
          (host-config-discoveries cwd)))

(defn adopt-host-configs!
  "Adopt the servers from the given host config DISCOVERIES (or all
   discovered when nil) into the project .kmet/mcp.edn: existing project
   entries win (the project file is the highest precedence source — pi
   skips conflicts), new servers are added. Returns {:path str :added
   [names] :skipped [names]}."
  [& [discoveries]]
  (let [discoveries (or discoveries (host-config-discoveries))
        adopted (reduce (fn [acc discovery]
                          ;; string keys — the project file convention
                          ;; (EDN written by enable/disable uses strings)
                          (into acc (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]))
                                (extract-host-servers (:path discovery))))
                        {}
                        discoveries)
        path (project-config-path)
        raw (read-project-raw)
        servers-key (if (contains? raw :mcp-servers)
                      :mcp-servers
                      (if (contains? raw :mcpServers) :mcpServers :mcp-servers))
        existing (get raw servers-key {})
        existing-names (set (map (fn [k] (if (keyword? k) (name k) (str k)))
                                 (keys existing)))
        name-of (fn [k] (if (keyword? k) (name k) (str k)))
        added (remove #(contains? existing-names (name-of %)) (keys adopted))
        skipped (filter #(contains? existing-names (name-of %)) (keys adopted))]
    (when (seq added)
      (fs/create-dirs (fs/parent path))
      (write-text path (str (pr-str (assoc raw servers-key
                                           (merge existing
                                                  (select-keys adopted added))))
                            "\n")))
    {:path path :added (vec added) :skipped (vec skipped)}))

;; ─── Known server presets (pi config.ts KNOWN_SERVER_PRESETS) ─────────────

(def known-server-presets
  "One-click server entries for the setup panel."
  [{:id "deepwiki" :name "DeepWiki"
    :summary "Ask questions about public GitHub repositories."
    :entry {:url "https://mcp.deepwiki.com/mcp"}}
   {:id "context7" :name "Context7"
    :summary "Look up current library documentation and examples."
    :entry {:url "https://mcp.context7.com/mcp"}}
   {:id "notion" :name "Notion"
    :summary "Search and work with your Notion workspace."
    :entry {:url "https://mcp.notion.com/mcp" :auth :oauth}}
   {:id "github" :name "GitHub"
    :summary "Work with GitHub through your Copilot account."
    :entry {:url "https://api.githubcopilot.com/mcp" :auth :oauth}}
   {:id "chrome-devtools" :name "Chrome DevTools"
    :summary "Inspect and automate a local Chrome browser."
    :entry {:command "npx" :args ["-y" "chrome-devtools-mcp@1.6.0"]}}])

(defn- write-project-servers!
  "Persist SERVERS (a name → entry map) into the project config: existing
   entries are REPLACED wholesale (the setup form specifies the full
   entry; the merge happens at config load against the global file).
   Returns {:path str :changed bool}."
  [servers]
  (let [path (project-config-path)
        raw (read-project-raw)
        servers-key (if (contains? raw :mcp-servers)
                      :mcp-servers
                      (if (contains? raw :mcpServers) :mcpServers :mcp-servers))
        existing (get raw servers-key {})
        changed? (boolean (some (fn [[name entry]]
                                  (not= entry (get existing name)))
                                servers))]
    (when (and changed? (seq servers))
      (fs/create-dirs (fs/parent path))
      (write-text path (str (pr-str (assoc raw servers-key
                                           (merge existing servers)))
                            "\n")))
    {:path path :changed changed?}))

(defn write-server-entry!
  "Add or replace one server entry in the project config (the setup
   panel's save path — like enable/disable, the project file is the only
   file the extension writes). Returns {:path str :changed bool}."
  [server-name entry]
  (write-project-servers! {server-name entry}))

;; ─── load-config — defined LAST (sci resolves symbols at analysis time;
;; it references host-configs-config above) ─────────────────────────────────

(defn load-config
  "Load + merge the global and project EDN configs (§6.1). Returns
   {:mcp-servers {name entry} :settings {...}} — empty maps when neither
   file exists. A parse error in either file throws (with the path).

   With :host-config-discovery :on in the merged settings, discovered host
   config files (Cursor/Claude/Codex/opencode/windsurf/vscode mcp.json) are
   merged at the LOWEST precedence — below the global file, exactly like pi
   (loadDiscoveredHostConfigs): an explicit EDN definition always wins over
   a discovered one, and the URL-bound credential stripping applies."
  []
  (let [global (read-config-file (global-config-path))
        project (read-config-file (project-config-path))
        merged (merge-configs (or global {:mcp-servers {}}) project)]
    (if (= :on (:host-config-discovery (:settings merged)))
      (merge-configs (host-configs-config) merged)
      merged)))
