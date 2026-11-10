(ns kmet.extensions.mcp-adapter.tool-surface
  "The direct-tool catalog and executor shared by the model-facing direct
   tools and the eval source. One spec map describes a callable MCP tool or
   resource; `direct-tools-specs` resolves the current direct set from the
   metadata cache, and `execute-spec` runs one through the proxy call path
   (lazy connect, failure backoff, auth, output guard and cancellation)."
  (:require [clojure.string :as str]
            [kmet.extensions.mcp-adapter.metadata :as metadata]
            [kmet.extensions.mcp-adapter.names :as names]
            [kmet.extensions.mcp-adapter.tool-proxy :as proxy]
            [kmet.libs.mcp.client :as mcp]))

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

(defn catalog-entries
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

(defn- env-direct-selection
  "MCP_DIRECT_TOOLS → [allowed-server-names none?]."
  []
  (let [raw (System/getenv "MCP_DIRECT_TOOLS")]
    [(when (and raw (not= "__none__" raw))
       (set (map str/trim (str/split raw #","))))
     (= "__none__" raw)]))

(defn- direct-filter
  "The server's direct-tools selection: env override → server setting →
   global setting → false."
  [name definition settings env-servers env-none?]
  (cond
    env-none? false
    env-servers (contains? env-servers name)
    (contains? definition :direct-tools) (:direct-tools definition)
    :else (boolean (:direct-tools settings))))

(defn- direct-spec
  "Build one direct-tool spec, or nil when the server's selection or
   include/exclude globs omit it."
  [{:keys [server kind id tool-name description schema name-for
           include exclude included? resource-uri resource-template]}]
  (when (and (included? tool-name)
             (names/tool-allowed? server tool-name include exclude))
    (cond-> {:server server
             :original tool-name
             :prefixed (name-for kind id tool-name)
             :description description
             :input-schema schema}
      resource-uri (assoc :resource-uri resource-uri)
      resource-template (assoc :resource-template resource-template))))

(defn- server-direct-specs
  "Direct-tool specs for one enabled server, or nil when its selection is
   off."
  [state name definition settings assigned env-servers env-none?]
  (when-let [filter (direct-filter name definition settings env-servers env-none?)]
    (let [entry (metadata/server-entry (:cache @state) name definition settings)
          include (:include-tools definition)
          exclude (:exclude-tools definition)
          included? (fn [tool-name] (or (true? filter) (some #{tool-name} filter)))
          name-for (fn [kind id raw]
                     (or (get assigned [name kind id])
                         (names/prefixed-name
                          name raw
                          (names/effective-mode definition settings))))
          spec (fn [m]
                 (direct-spec (assoc m :server name :name-for name-for
                                     :include include :exclude exclude
                                     :included? included?)))]
      (concat
       (keep (fn [tool]
               (spec {:kind :tool
                      :id (:name tool)
                      :tool-name (:name tool)
                      :description (or (:description tool) "")
                      :schema (:inputSchema tool)}))
             (:tools entry))
       (when (not= false (:expose-resources definition))
         (concat
          ;; a resource's read_<name> is selected by its base name
          (keep (fn [resource]
                  (spec {:kind :resource
                         :id (:uri resource)
                         :tool-name (str "read_" (names/resource-tool-name (:name resource)))
                         :description (or (:description resource)
                                          (str "Read resource: " (:uri resource)))
                         :schema nil
                         :resource-uri (:uri resource)}))
                (:resources entry))
          ;; a parameterized resource becomes a read tool whose parameters
          ;; are the template variables
          (keep (fn [template]
                  (let [uri-template (:uriTemplate template)
                        vars (template-vars uri-template)]
                    (spec {:kind :resource
                           :id uri-template
                           :tool-name (str "read_" (names/resource-tool-name (:name template)))
                           :description (str (or (:description template)
                                                 "Read resource: ")
                                             " (" uri-template ")")
                           :schema (template-schema vars)
                           :resource-template uri-template})))
                (:resource-templates entry))))))))

(defn direct-tools-specs
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
        [env-servers env-none?] (env-direct-selection)
        assigned (:tool-names @state)]
    (vec
     (mapcat (fn [[name definition]]
               (when-not (or (true? (:disabled definition))
                             (and (not (:command definition)) (not (:url definition))))
                 (server-direct-specs state name definition settings assigned
                                      env-servers env-none?)))
             (sort-by key (:mcp-servers config))))))

(defn execute-spec
  "Run one direct-tool SPEC with ARGS. OPTS: `:on-update` receives progress
   notifications; `:signal` is the run's cancel signal. Returns the kmet tool
   result map (`:content`, `:is-error`, optional `:details`)."
  [state-atom {:keys [server original input-schema resource-uri resource-template]}
   args opts]
  (let [state @state-atom
        on-update (:on-update opts)
        signal (:signal opts)]
    (cond
      resource-uri
      (proxy/read-mcp-resource state server resource-uri {:signal signal})

      resource-template
      (proxy/read-mcp-resource state server
                               (mcp/expand-uri-template resource-template (or args {}))
                               {:signal signal})

      :else
      (proxy/call-mcp-tool state server original args
                           (cond-> {:input-schema input-schema}
                             on-update (assoc :on-update on-update)
                             signal (assoc :signal signal))))))
