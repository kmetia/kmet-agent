(ns kmet.extensions.mcp-adapter.status
  "mcp proxy read-only views: §9.5 status, §9.4 describe, and the list /
   list-all cache views. Reads configuration and the metadata cache only
   (no spawn); connections and tool calls stay in tool_proxy.clj."
  (:require [clojure.string :as str]
            [kmet.libs.mcp.client :as mcp]
            [kmet.extensions.mcp-adapter.auth :as auth]
            [kmet.extensions.mcp-adapter.catalog :as catalog]
            [kmet.extensions.mcp-adapter.metadata :as metadata]
            [kmet.extensions.mcp-adapter.names :as names]))

(def ^:private desc-truncate-length 50)

;; ─── Status (§9.5) ─────────────────────────────────────────────────────

(defn- lifecycle-label
  [definition]
  (name (or (:lifecycle definition) :lazy)))

(defn- state-label
  "Per-server runtime state: idle/connecting/connected/failed/disabled/
   misconfigured/unsupported-transport."
  [state name definition]
  (cond
    (true? (:disabled definition)) :disabled
    (catalog/misconfigured? definition) :misconfigured
    :else
    (let [{:keys [conn]} (catalog/server-state state name)]
      (cond
        (and @conn (mcp/alive? @conn)) :connected
        (catalog/failure-age-seconds state name) :failed
        :else :idle))))

(defn- auth-state-label
  [name definition]
  (let [status (auth/auth-status name definition)]
    (case status
      :bearer "bearer"
      :logged-in "oauth logged-in"
      :expired "oauth expired"
      :none "oauth none"
      :client-credentials "client-credentials"
      :jwt-bearer "jwt-bearer"
      nil)))

(defn status-text
  "§9.5 status text: per server — name, lifecycle, state, auth state when
   configured, tool count (from cache or live), negotiated protocol
   revision when connected, error tail when failed, cache age. Plus the
   global settings line and cache file age."
  [state]
  (let [config (:config state)
        lines (atom [])]
    (doseq [[server-name definition] (sort-by key (:mcp-servers config))]
      (let [slabel (state-label state server-name definition)
            failed-ago (catalog/failure-age-seconds state server-name)
            error (:error (catalog/server-state state server-name))
            tool-count (count (or (catalog/cached-tools state server-name) []))
            auth-label (auth-state-label server-name definition)
            ;; the revision the server selected at handshake, live conn only
            proto (some-> @(:conn (catalog/server-state state server-name))
                          :protocol-version
                          deref)
            age (when-let [entry (get-in (:cache state) [:servers server-name])]
                  (quot (- (System/currentTimeMillis) (:fetched-at entry)) 60000))
            state-part (if failed-ago
                         (str "failed " failed-ago "s ago"
                              (when (seq @error)
                                (str " — " (catalog/truncate-at-word @error 120))))
                         (name slabel))]
        (swap! lines conj
               (str server-name " (" (lifecycle-label definition) ", " state-part
                    (when (and tool-count (not= :connected slabel)) (str ", " tool-count " tools"))
                    (when auth-label (str ", " auth-label))
                    (when proto (str ", proto " proto))
                    (when age (str ", cache " age "m old"))
                    ")"))))
    (let [s (catalog/settings state)]
      (swap! lines conj
             (str "settings: direct-tools=" (if (:direct-tools s) "on" "off")
                  " tool-prefix=" (or (:tool-prefix s) :server)
                  " proxy-tool=" (if (:disable-proxy-tool s) "disabled" "enabled"))))
    (if (seq (:mcp-servers config))
      (str (str/join "\n" @lines)
           "\n\nmcp({ server: \"name\" }) to list tools, mcp({ search: \"...\" }) to search")
      "No MCP servers configured.")))

;; ─── Describe (§9.4) ───────────────────────────────────────────────────

(defn describe-text
  "§9.4 full listing: server, tool name, description, each param with
   type, required/optional, description, enum/default when present.
   Ambiguous (same tool name on multiple servers) → instruct to add
   server."
  [state tool-name]
  (let [match (catalog/find-tool state tool-name)]
    (cond
      (= :ambiguous match)
      (catalog/return-error (str "Tool \"" tool-name "\" matches multiple servers. "
                                 "Specify a server with mcp({ tool: ..., server: \"...\" })."))

      (nil? match)
      (catalog/return-error (str "Tool \"" tool-name "\" not found. Use mcp({ search: \"...\" }) to search."))

      :else
      (let [{:keys [server tool]} match
            schema (or (:inputSchema tool) {})
            properties (:properties schema)
            required (set (:required schema))
            out (atom [(str (catalog/display-name state server :tool (:name tool) (:name tool))
                            "\nServer: " server "\n\n"
                            (or (:description tool) "(no description)"))])]
        (if (seq properties)
          (do
            (swap! out conj "\nParameters:")
            (doseq [[pname spec] (sort-by key properties)]
              (let [spec (or spec {})
                    req? (required pname)
                    type (or (:type spec) "any")
                    enum (when (seq (:enum spec)) (str "enum: " (pr-str (:enum spec))))
                    default (when (contains? spec :default)
                              (str "default: " (pr-str (:default spec))))
                    parts (str/join ", " (remove nil? [(str "type: " type)
                                                       (if req? "required" "optional")
                                                       enum default]))]
                (swap! out conj (str "  " pname " (" parts ")")
                       (when (seq (:description spec))
                         (str "      " (:description spec)))))))
          (swap! out conj "\nNo parameters defined."))
        {:content (str/join "\n" @out) :is-error false}))))

;; ─── List (cache view) ─────────────────────────────────────────────────

(defn list-text
  "List a server's tools (cache; §9.2 `server` mode). Resource templates
   are listed after the tools — a server whose resources are all
   templates (`file:///{path}`) shows nothing in its tool list."
  [state server]
  (let [definition (catalog/server-definition state server)]
    (cond
      (nil? definition)
      (catalog/return-error (str "Server \"" server "\" not found. Use mcp({}) to see available servers."))

      (catalog/disabled? state server)
      (catalog/return-error (str "Server \"" server "\" is disabled. Run /mcp enable " server
                                 " and /reload to enable it."))

      :else
      (let [tools (catalog/cached-tools state server)
            templates (:resource-templates
                       (metadata/server-entry (:cache state)
                                              server
                                              definition
                                              (catalog/settings state)))]
        (if (or (seq tools) (seq templates))
          (let [out (atom [(str server " (" (count tools) " tools"
                                (when-not (= :connected (state-label state server definition))
                                  ", not connected, cached")
                                "):\n")])]
            (doseq [tool (sort-by :name tools)]
              (swap! out conj (str "- " (catalog/display-name state server :tool (:name tool) (:name tool))
                                   (when (seq (:description tool))
                                     (str " - " (catalog/truncate-at-word (:description tool)
                                                                          desc-truncate-length))))))
            (doseq [template (sort-by :name templates)]
              (swap! out conj (str "- " (catalog/display-name state server :resource (:uriTemplate template)
                                                              (str "read_"
                                                                   (names/resource-tool-name (:name template))))
                                   " — " (:uriTemplate template))))
            {:content (str/join "\n" @out) :is-error false})
          (if (= :connected (state-label state server definition))
            {:content (str "Server \"" server "\" has no tools.") :is-error false}
            {:content (str "Server \"" server "\" is configured but not connected. "
                           "Use mcp({ connect: \"" server "\" }) to retry.")
             :is-error false}))))))

(defn list-all-text
  "All servers with their tool counts (§10.6 list)."
  [state]
  (let [config (:config state)
        out (atom ["MCP servers:"])]
    (doseq [[name definition] (sort-by key (:mcp-servers config))]
      (let [tools (or (catalog/cached-tools state name) [])
            extra (cond
                    (true? (:disabled definition)) " (disabled)"
                    (catalog/misconfigured? definition) " (misconfigured)"
                    :else (str " (" (count tools) " tools)"))]
        (swap! out conj (str "  " name extra))))
    {:content (str/join "\n" @out) :is-error false}))
