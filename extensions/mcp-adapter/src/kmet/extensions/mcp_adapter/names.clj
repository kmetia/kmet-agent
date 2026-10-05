(ns kmet.extensions.mcp-adapter.names
  "Tool naming for the mcp-adapter §10.5 — one source of truth for
   registration, display and call resolution.

   Pure: no state, config or metadata access. Sanitization, prefix modes,
   the builtin/collision fallback, include/exclude globs, and (Phase 0.2)
   the deterministic name assignment live here; core.clj registers what
   this namespace names and tool_proxy.clj displays and resolves by it."
  (:require [clojure.string :as str]))

(def builtin-tool-names
  "Host tool names a bare MCP tool name must not shadow."
  #{"read" "bash" "edit" "write" "grep" "find" "ls" "mcp"})

(defn sanitize-tool-name
  "Lowercase; [^a-z0-9_] → _ (§10.5)."
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9_]" "_")))

(defn sanitize-server-name
  "The sanitized server name used as a tool/command prefix (§10.5)."
  [server-name]
  (sanitize-tool-name server-name))

(defn resource-tool-name
  "Pi resourceNameToToolName: [^a-zA-Z0-9] → _, collapse runs, trim
   leading/trailing _, lowercase; empty or digit-start → prefixed with
   'resource'. Shared by the read_<resource> direct-tool registration
   (core.clj) and the /mcp list display so both spell a tool the same
   way."
  [name]
  (let [result (-> (str name)
                   (str/replace #"[^a-zA-Z0-9]" "_")
                   (str/replace #"_+" "_")
                   (str/replace #"^_+|_+$" "")
                   str/lower-case)]
    (if (or (str/blank? result) (re-matches #"^[0-9].*" result))
      (str "resource" (when (seq result) (str "_" result)))
      result)))

(defn prefix-for
  "Pi getServerPrefix (§10.5): :server default → sanitized server name;
   :mcp → \"mcp\"; :none/:short → bare (collision fallback by the caller)."
  [server-name mode]
  (case mode
    :none ""
    :short (let [short (sanitize-tool-name (str/replace server-name #"-?mcp$" ""))]
             (if (seq short) short "mcp"))
    :mcp "mcp"
    (sanitize-tool-name server-name)))

(defn effective-mode
  "The effective prefix mode for a server: per-server :tool-prefix over
   settings, default :server (was tool-proxy/tool-prefix-mode, now
   catalog/tool-prefix-mode)."
  [definition settings]
  (or (:tool-prefix definition) (:tool-prefix settings) :server))

(defn prefixed-name
  "Pi formatToolName (§10.5), collision-free composition:
   prefix + '_' + sanitized tool name."
  [server-name tool-name mode]
  (let [prefix (prefix-for server-name mode)
        sanitized (sanitize-tool-name tool-name)]
    (if (seq prefix) (str prefix "_" sanitized) sanitized)))

(defn- fallback-name
  "The readable collision fallback: sanitized server prefix + '_' +
   sanitized base name."
  [{:keys [server name]}]
  (str (sanitize-server-name server) "_" (sanitize-tool-name name)))

(defn- assignment-key
  "The lookup key of an assignment entry: server + kind + id (for tools the
   raw tool name, for resources the uri / uriTemplate)."
  [{:keys [server kind id]}]
  [server kind id])

(defn- builtin-collision?
  "True when a bare (prefix-less) candidate would shadow a host tool."
  [candidate {:keys [server]} mode-for]
  (and (empty? (prefix-for server (mode-for server)))
       (contains? builtin-tool-names candidate)))

(defn assign-names
  "Deterministic assigned name for every catalog entry (§10.5, Phase 0.2).

   ENTRIES — [{:server s :kind :tool|:resource :id x :name n}], where :name
   is the composed base name (resources: read_<resource-tool-name>).
   MODE-FOR — (fn [server] prefix-mode).

   A candidate shared by more than one entry — or a bare candidate that is
   a host tool name — sends every entry in the group to the readable
   fallback; a fallback shared by several entries (server names that
   sanitize the same) gets a short identity digest so no two entries ever
   get the same name. Order-independent: adding a colliding tool never
   renames an existing one.

   Returns {[server kind id] assigned-name}."
  [entries mode-for]
  (let [candidates (group-by (fn [{:keys [server name]}]
                               (prefixed-name server name (mode-for server)))
                             entries)
        collides? (fn [[candidate es]]
                    (or (> (count es) 1)
                        (some #(builtin-collision? candidate % mode-for) es)))
        direct (into {}
                     (keep (fn [[candidate es]]
                             (when-not (collides? [candidate es])
                               [(assignment-key (first es)) candidate])))
                     candidates)
        falling (mapcat val (filter collides? candidates))
        by-fallback (group-by fallback-name falling)]
    (merge direct
           (into {}
                 (mapcat (fn [[fallback es]]
                           (if (= 1 (count es))
                             [[(assignment-key (first es)) fallback]]
                             (map (fn [e]
                                    [(assignment-key e)
                                     (str fallback "_"
                                          (format "%08x" (hash (assignment-key e))))])
                                  es))))
                 by-fallback))))

(defn tool-name-candidates
  "Every name a tool can be addressed by: the raw name + the prefixed form
   under each prefix mode + legacy dash→underscore spellings."
  [server-name tool-name]
  (let [modes [:server :short :mcp]
        raw (str tool-name)
        legacy (str/replace raw #"-" "_")]
    ;; hash-set, not a literal: sci builds set literals as maps and throws
    ;; "Duplicate key" when raw == legacy (same value twice)
    (-> (hash-set raw legacy)
        (into (map #(prefixed-name server-name raw %) modes))
        (into (map (comp sanitize-tool-name #(str % "_" (sanitize-tool-name raw)))
                   [server-name (str/replace server-name #"-?mcp$" "") "mcp"])))))

(defn- glob->regex
  "A glob pattern (* = any run, ? = one char) as an anchored regex."
  [pattern]
  (re-pattern (str "^" (-> pattern
                           (str/replace #"[.+^${}()|\[\\\]\\]" "\\$&")
                           (str/replace #"\*" ".*")
                           (str/replace #"\?" "."))
                   "$")))

(defn matches-tool-pattern
  "True when any PATTERN matches any of the CANDIDATES (exact or glob).
   Used by tool-allowed? and the search-keyword patterns."
  [candidates patterns]
  (boolean
   (some (fn [pattern]
           (when (string? pattern)
             (if (or (str/includes? pattern "*")
                     (str/includes? pattern "?"))
               (some (fn [c] (boolean (re-find (glob->regex pattern) c)))
                     candidates)
               (contains? candidates pattern))))
         (or patterns []))))

(defn tool-allowed?
  "include/exclude gate (pi isToolAllowed): empty :include-tools allows
   everything; :exclude-tools always wins. Applied to direct-tool
   registration (tools and read_<resource> tools)."
  [server-name tool-name include-tools exclude-tools]
  (let [candidates (tool-name-candidates server-name tool-name)]
    (and (or (empty? include-tools)
             (matches-tool-pattern candidates include-tools))
         (not (matches-tool-pattern candidates exclude-tools)))))
