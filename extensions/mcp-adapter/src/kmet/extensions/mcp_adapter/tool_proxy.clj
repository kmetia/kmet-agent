(ns kmet.extensions.mcp-adapter.tool-proxy
  "The mcp proxy tool executor (§9 of the design contract — pi:
   proxy-modes.ts, Phase-1 subset: status/search/describe/call/connect/
   disconnect/list; no instructions/ui-messages/auth actions).

   Phase 2 additions: ranked search (pi search-ranking.ts — weighted
   name/description/server/keyword scoring with searchKeywords boost),
   the output guard (output_guard.clj) on call/resource results,
   streaming tool-call progress (:on-update receives notifications/
   progress events as partial content), resource reads
   (read-mcp-resource — the read_<resource> direct-tool executor),
   and script-tool-records — the cached catalog the mcp-adapter
   contributes to kmet's `script` sandbox (script.md T2; replaces the
   retired mcpScript runtime and its search/describe/TS-shape envelopes).

   Dispatch precedence (§9.2): search → describe → tool → connect →
   disconnect → list → server (list that server's tools) → status.
   Search/describe read the metadata cache only (no spawn); call/connect
   ensure a live connection. Every mode returns the kmet tool result shape
   {:content str :is-error bool}."
  (:require [kmet.libs.json :as json]
            [clojure.string :as str]
            [kmet.extensions.mcp-adapter.auth :as auth]
            [kmet.extensions.mcp-adapter.client :as client]
            [kmet.extensions.mcp-adapter.metadata :as metadata]
            [kmet.extensions.mcp-adapter.output-guard :as guard]))

(def ^:private desc-truncate-length 50)
(def ^:private max-regex-query-length 256)
(def ^:private failure-backoff-ms 60000)

;; ─── Tool/state helpers ───────────────────────────────────────────────────

(defn- server-definition
  "The effective definition for a server (per-server merge over settings)."
  [state name]
  (get-in state [:config :mcp-servers name]))

(defn- settings
  [state]
  (:settings (:config state)))

(defn- server-state
  [state name]
  (get-in state [:servers name]))

(defn- failure-age-seconds
  "Seconds since the server's last recorded connect failure, or nil
   outside the 60s backoff window (pi getFailureAgeSeconds — a failed
   lazy use does not retry inside the window)."
  [state name]
  (when-let [failed-at @(get-in state [:servers name :failed-at])]
    (let [age (- (System/currentTimeMillis) failed-at)]
      (when (< age failure-backoff-ms)
        (quot age 1000)))))

(defn- disabled?
  [state name]
  (true? (:disabled (server-definition state name))))

(defn- misconfigured?
  "A server with neither :command nor :url (§6.2)."
  [definition]
  (and (not (:command definition)) (not (:url definition))))

(defn truncate-at-word
  "Truncate S to LENGTH chars at a word boundary (pi truncateAtWord)."
  [s length]
  (let [s (or s "")]
    (if (<= (count s) length)
      s
      (let [cut (subs s 0 length)
            space (str/last-index-of cut " ")]
        (str (if space (subs cut 0 space) cut) "…")))))

(defn- cached-tools
  "The cached tools for a server (fresh + fingerprint-valid), or nil."
  [state name]
  (when-let [entry (metadata/server-entry (:cache state)
                                          name
                                          (server-definition state name)
                                          (settings state))]
    (:tools entry)))

;; ─── Tool name candidates + glob selectors (§10.5 include/exclude, pi
;; types.ts getToolNameCandidates / matchesToolSelector — simplified: the
;; candidate set covers every current prefix form plus the legacy
;; dash→underscore forms; pi's collision-aware legacy path is dropped)

(defn- sanitize-tool-name
  "Lowercase; [^a-z0-9_] → _ (§10.5)."
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9_]" "_")))

(defn sanitize-server-name
  "The sanitized server name used as a tool/command prefix (§10.5)."
  [server-name]
  (sanitize-tool-name server-name))

(defn tool-name-candidates
  "Every name a tool can be addressed by: the raw name + the prefixed form
   under each prefix mode + legacy dash→underscore spellings."
  [server-name tool-name]
  (let [modes [:server :short :mcp]
        raw (str tool-name)
        legacy (str/replace raw #"-" "_")
        prefixed (fn [mode]
                   (let [prefix (case mode
                                  :short (let [short (sanitize-tool-name
                                                      (str/replace server-name #"-?mcp$" ""))]
                                           (if (seq short) short "mcp"))
                                  :mcp "mcp"
                                  (sanitize-tool-name server-name))
                         sanitized (sanitize-tool-name raw)]
                     (if (seq prefix) (str prefix "_" sanitized) sanitized)))]
    ;; hash-set, not a literal: sci builds set literals as maps and throws
    ;; "Duplicate key" when raw == legacy (same value twice)
    (-> (hash-set raw legacy)
        (into (map prefixed modes))
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

(defn- matches-tool-pattern
  "True when any PATTERN matches any of the CANDIDATES (exact or glob)."
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

;; ─── Search ranking (pi search-ranking.ts) ────────────────────────────────

(def ^:private field-weights
  {:name 12 :original-name 10 :server 8 :description 5 :keywords 5})

(def ^:private min-stem-length 4)

(defn- normalize-search-text
  "camelCase → spaced, separators → spaces, lowercase (pi
   normalizeSearchText)."
  [s]
  (-> (str s)
      (str/replace #"([a-z0-9])([A-Z])" "$1 $2")
      (str/replace #"[_./:-]+" " ")
      str/lower-case))

(defn- tokenize
  [s]
  (->> (str/split (normalize-search-text s) #"[^a-z0-9]+")
       (remove str/blank?)
       vec))

(defn- resolve-search-keywords
  "The configured :search-keywords values whose pattern matches the tool
   (by raw or prefixed name, pi resolveSearchKeywords)."
  [definition tool-name server-name]
  (let [map (:search-keywords definition)]
    (when (map? map)
      (let [candidates (tool-name-candidates server-name tool-name)
            out (atom [])
            seen (atom #{})]
        (doseq [[pattern values] map
                :when (and (vector? values)
                           (matches-tool-pattern candidates [pattern]))]
          (doseq [value values
                  :let [value (str/trim (str value))]
                  :when (and (seq value) (not (contains? @seen value)))]
            (swap! seen conj value)
            (swap! out conj value)))
        @out))))

(defn- score-tool-match
  "Weighted match score for a tool against a query; nil when the tool does
   not match (pi scoreToolMatch: phrase matches dominate, token coverage
   gate for short queries, first-query-token-in-name and whole-field-exact
   bonuses)."
  [tool server query keywords]
  (let [normalized-query (str/trim (normalize-search-text query))
        query-tokens (tokenize query)]
    (when (seq query-tokens)
      (let [fields {:name (normalize-search-text (:name tool))
                    :original-name (normalize-search-text (:original-name tool))
                    :server (normalize-search-text server)
                    :description (normalize-search-text (or (:description tool) ""))}
            matched-tokens (atom #{})
            phrase-matched? (atom false)
            whole-field-exact? (atom false)
            score (atom 0)]
        (doseq [[field value] fields]
          (let [weight (field-weights field)
                field-tokens (tokenize value)]
            (cond
              (= value normalized-query)
              (do (swap! score + (* weight 14))
                  (reset! phrase-matched? true)
                  (reset! whole-field-exact? true))
              (str/starts-with? value normalized-query)
              (do (swap! score + (* weight 9))
                  (reset! phrase-matched? true))
              (str/includes? value normalized-query)
              (do (swap! score + (* weight 6))
                  (reset! phrase-matched? true)))
            (doseq [token query-tokens]
              (cond
                (some #{token} field-tokens)
                (do (swap! score + (* weight 4))
                    (swap! matched-tokens conj token))
                (some (fn [field-token]
                        (or (str/starts-with? field-token token)
                            (and (>= (count field-token) min-stem-length)
                                 (str/starts-with? token field-token))))
                      field-tokens)
                (do (swap! score + (* weight 2))
                    (swap! matched-tokens conj token))
                (str/includes? value token)
                (do (swap! score + weight)
                    (swap! matched-tokens conj token))))))
        ;; configured keywords are discrete phrases (pi: per-phrase bonus)
        (when (seq keywords)
          (let [weight (field-weights :keywords)
                phrases (->> keywords
                             (map #(str/trim (normalize-search-text %)))
                             (remove str/blank?))]
            (doseq [phrase phrases]
              (cond
                (= phrase normalized-query)
                (do (swap! score + (* weight 14))
                    (reset! phrase-matched? true)
                    (reset! whole-field-exact? true))
                (str/starts-with? phrase normalized-query)
                (do (swap! score + (* weight 9))
                    (reset! phrase-matched? true))
                (str/includes? phrase normalized-query)
                (do (swap! score + (* weight 6))
                    (reset! phrase-matched? true))))
            (let [keyword-tokens (vec (mapcat tokenize phrases))]
              (doseq [token query-tokens]
                (cond
                  (some #{token} keyword-tokens)
                  (do (swap! score + (* weight 4))
                      (swap! matched-tokens conj token))
                  (some (fn [keyword-token]
                          (or (str/starts-with? keyword-token token)
                              (and (>= (count keyword-token) min-stem-length)
                                   (str/starts-with? token keyword-token))))
                        keyword-tokens)
                  (do (swap! score + (* weight 2))
                      (swap! matched-tokens conj token))
                  (some #(str/includes? % token) phrases)
                  (do (swap! score + weight)
                      (swap! matched-tokens conj token)))))))
        (let [coverage (/ (count @matched-tokens) (count query-tokens))
              coverage-ok? (if (<= (count query-tokens) 2)
                             (= coverage 1)
                             (>= coverage 0.6))]
          (when (or @phrase-matched? coverage-ok?)
            (swap! score + (if (= coverage 1) 25 (Math/round (double (* coverage 10)))))
            (when (some #{(first query-tokens)} (tokenize (:name fields)))
              (swap! score + 8))
            (when @whole-field-exact? (swap! score + 20))
            @score))))))

(defn- rank-tool-matches
  "All matching cached tools across enabled servers, sorted by score desc
   then tool name (pi rankToolMatches). KEYWORDS resolved per tool."
  [state query server]
  (let [matches (atom [])]
    (doseq [[server-name definition] (:mcp-servers (:config state))
            :when (and (not (disabled? state server-name))
                       (or (nil? server) (= server server-name)))]
      (doseq [tool (or (cached-tools state server-name) [])]
        (let [tool {:name (:name tool)
                    :original-name (:name tool)
                    :description (:description tool)}
              keywords (resolve-search-keywords definition (:name tool) server-name)
              score (score-tool-match tool server-name query keywords)]
          (when score
            (swap! matches conj {:server server-name :tool tool :score score})))))
    (vec (sort-by (juxt (comp - :score) (comp :name :tool)) @matches))))

(defn- tool-prefix-mode
  "The effective prefix mode for a server (per-server over settings)."
  [state server-name]
  (or (get-in state [:config :mcp-servers server-name :tool-prefix])
      (get-in state [:config :settings :tool-prefix])
      :server))

(defn- format-tool-name
  "The prefixed display name for a raw tool name (§10.5)."
  [state server-name tool-name]
  (let [mode (tool-prefix-mode state server-name)
        prefix (case mode
                 :none ""
                 :short (let [short (sanitize-tool-name
                                     (str/replace server-name #"-?mcp$" ""))]
                          (if (seq short) short "mcp"))
                 :mcp "mcp"
                 (sanitize-tool-name server-name))
        sanitized (sanitize-tool-name tool-name)]
    (if (seq prefix) (str prefix "_" sanitized) sanitized)))

;; ─── Status (§9.5) ────────────────────────────────────────────────────────

(defn- lifecycle-label
  [definition]
  (name (or (:lifecycle definition) :lazy)))

(defn- state-label
  "Per-server runtime state: idle/connecting/connected/failed/disabled/
   misconfigured/unsupported-transport."
  [state name definition]
  (cond
    (true? (:disabled definition)) :disabled
    (misconfigured? definition) :misconfigured
    :else
    (let [{:keys [conn]} (server-state state name)]
      (cond
        (and @conn (client/alive? @conn)) :connected
        (failure-age-seconds state name) :failed
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
   configured, tool count (from cache or live), error tail when failed,
   cache age. Plus the global settings line and cache file age."
  [state]
  (let [config (:config state)
        lines (atom [])]
    (doseq [[server-name definition] (sort-by key (:mcp-servers config))]
      (let [slabel (state-label state server-name definition)
            failed-ago (failure-age-seconds state server-name)
            error (:error (server-state state server-name))
            tool-count (count (or (cached-tools state server-name) []))
            auth-label (auth-state-label server-name definition)
            age (when-let [entry (get-in (:cache state) [:servers server-name])]
                  (quot (- (System/currentTimeMillis) (:fetched-at entry)) 60000))
            state-part (if failed-ago
                         (str "failed " failed-ago "s ago"
                              (when (seq @error)
                                (str " — " (truncate-at-word @error 120))))
                         (name slabel))]
        (swap! lines conj
               (str server-name " (" (lifecycle-label definition) ", " state-part
                    (when (and tool-count (not= :connected slabel)) (str ", " tool-count " tools"))
                    (when auth-label (str ", " auth-label))
                    (when age (str ", cache " age "m old"))
                    ")"))))
    (let [s (settings state)]
      (swap! lines conj
             (str "settings: direct-tools=" (if (:direct-tools s) "on" "off")
                  " tool-prefix=" (or (:tool-prefix s) :server)
                  " proxy-tool=" (if (:disable-proxy-tool s) "disabled" "enabled"))))
    (if (seq (:mcp-servers config))
      (str (str/join "\n" @lines)
           "\n\nmcp({ server: \"name\" }) to list tools, mcp({ search: \"...\" }) to search")
      "No MCP servers configured.")))

;; ─── Search (§9.3) ────────────────────────────────────────────────────────

(defn- schema-param-lines
  "Compact param lines for one tool schema (indented, §9.3): one line per
   property with type, required/optional, and default when present."
  [input-schema]
  (let [schema (or input-schema {})
        properties (:properties schema)
        required (set (:required schema))]
    (mapv (fn [[name spec]]
            (let [spec (or spec {})
                  type (or (:type spec) "any")
                  default (when (contains? spec :default)
                            (str "default: " (pr-str (:default spec))))
                  parts (str name " (" type
                             ", " (if (required name) "required" "optional")
                             (when default (str ", " default)) ")")
                  description (str/trim (or (:description spec) ""))]
              (str "  " parts (when (seq description)
                                (str " — " (truncate-at-word description 80))))))
          (sort-by key properties))))

(defn- return-error
  [message]
  {:content message :is-error true})

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
        (return-error (str "mcp({ " (name k) ": " (pr-str (get params k))
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

(defn- search-tools
  "Search cached tools (§9.3): ranked by the search-ranking port when the
   query is non-empty (name/description/server/keyword weighted scoring);
   regex mode tests name/description/keywords; an empty query with a
   SERVER lists that server's tools sorted by name. include/exclude do not
   filter the proxy search (pi parity — they gate direct-tool
   registration). Returns {:total :items} honoring :limit/:offset."
  [state query compiled server limit offset]
  (let [all (if (or compiled (str/blank? query))
              (let [items (atom [])]
                (doseq [[name _definition] (:mcp-servers (:config state))
                        :when (and (not (disabled? state name))
                                   (or (nil? server) (= server name)))]
                  (doseq [tool (or (cached-tools state name) [])]
                    (let [keywords (resolve-search-keywords
                                    (server-definition state name) (:name tool) name)
                          name-match? (if compiled
                                        (boolean (re-find compiled (:name tool)))
                                        true)
                          desc-match? (if compiled
                                        (boolean (re-find compiled (or (:description tool) "")))
                                        true)
                          kw-match? (if compiled
                                      (boolean (some #(re-find compiled %) keywords))
                                      true)]
                      (when (and name-match? desc-match? kw-match?)
                        (swap! items conj {:server name
                                           :tool tool
                                           :score 0})))))
                (if compiled
                  (vec (sort-by (juxt :server (comp :name :tool)) @items))
                  (vec (sort-by (comp :name :tool) @items))))
              (rank-tool-matches state query server))
        total (count all)
        page (vec (take limit (drop offset all)))]
    {:total total :items page}))

(defn search-text
  "§9.3 output: one block per hit — name line, one-line description,
   indented param lines (omitted when include-schemas? is false). Invalid
   regex → error message."
  [state query regex? server include-schemas? limit offset]
  (let [compiled (when regex?
                   (try
                     (when (> (count query) max-regex-query-length)
                       (throw (ex-info "too long" {})))
                     (re-pattern (str "(?i)" query))
                     (catch Exception _ ::invalid)))
        {:keys [total items]} (if (= ::invalid compiled)
                                {:total 0 :items []}
                                (search-tools state query compiled server
                                              (or limit 12) (or offset 0)))]
    (cond
      (= ::invalid compiled)
      (return-error (str "Invalid regex: " query))

      (and (str/blank? query) (nil? server))
      (return-error "Search query cannot be empty")

      (zero? total)
      {:content (str "No tools matching \"" query "\""
                     (when server (str " in \"" server "\"")))
       :is-error false}

      :else
      (let [out (atom [(str "Found " total " tool" (when (not= 1 total) "s")
                            " matching \"" query "\":\n")])]
        (doseq [{:keys [server tool]} items]
          (swap! out conj (str server ": "
                               (format-tool-name state server (:name tool))
                               " — " (or (:description tool) "(no description)")))
          (when (not= false include-schemas?)
            (doseq [line (schema-param-lines (:inputSchema tool))]
              (swap! out conj line))))
        (when (< (+ (or offset 0) (count items)) total)
          (swap! out conj (str "\n" (count items) " of " total
                               " — offset: " (+ (or offset 0) (count items)) " for more")))
        {:content (str/join "\n" @out) :is-error false}))))

;; ─── Describe (§9.4) ──────────────────────────────────────────────────────
;; Find + render one cached tool for the proxy tool's describe mode.

(defn- find-tool
  "Find a tool by (prefixed) name across servers. Returns {:server :tool}
   or :ambiguous when the name matches multiple enabled servers."
  [state tool-name]
  (let [matches (for [[name _] (:mcp-servers (:config state))
                      :when (not (disabled? state name))
                      tool (or (cached-tools state name) [])
                      :when (or (= tool-name (:name tool))
                                (= tool-name (format-tool-name state name (:name tool))))]
                  {:server name :tool tool})]
    (cond
      (> (count matches) 1) :ambiguous
      (seq matches) (first matches)
      :else nil)))

(defn describe-text
  "§9.4 full listing: server, tool name, description, each param with
   type, required/optional, description, enum/default when present.
   Ambiguous (same tool name on multiple servers) → instruct to add
   server."
  [state tool-name]
  (let [match (find-tool state tool-name)]
    (cond
      (= :ambiguous match)
      (return-error (str "Tool \"" tool-name "\" matches multiple servers. "
                         "Specify a server with mcp({ tool: ..., server: \"...\" })."))

      (nil? match)
      (return-error (str "Tool \"" tool-name "\" not found. Use mcp({ search: \"...\" }) to search."))

      :else
      (let [{:keys [server tool]} match
            schema (or (:inputSchema tool) {})
            properties (:properties schema)
            required (set (:required schema))
            out (atom [(str (format-tool-name state server (:name tool))
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

;; ─── List / connect / disconnect ──────────────────────────────────────────

(defn list-text
  "List a server's tools (cache; §9.2 `server` mode)."
  [state server]
  (let [definition (server-definition state server)]
    (cond
      (nil? definition)
      (return-error (str "Server \"" server "\" not found. Use mcp({}) to see available servers."))

      (disabled? state server)
      (return-error (str "Server \"" server "\" is disabled. Run /mcp enable " server
                         " and /reload to enable it."))

      :else
      (let [tools (cached-tools state server)]
        (if (seq tools)
          (let [out (atom [(str server " (" (count tools) " tools"
                                (when-not (= :connected (state-label state server definition))
                                  ", not connected, cached")
                                "):\n")])]
            (doseq [tool (sort-by :name tools)]
              (swap! out conj (str "- " (format-tool-name state server (:name tool))
                                   (when (seq (:description tool))
                                     (str " - " (truncate-at-word (:description tool)
                                                                  desc-truncate-length))))))
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
      (let [tools (or (cached-tools state name) [])
            extra (cond
                    (true? (:disabled definition)) " (disabled)"
                    (misconfigured? definition) " (misconfigured)"
                    :else (str " (" (count tools) " tools)"))]
        (swap! out conj (str "  " name extra))))
    {:content (str/join "\n" @out) :is-error false}))

;; ─── Call (§9.2 tool mode) ────────────────────────────────────────────────

(defn ensure-lazy-connected
  "Connect a server honoring the 60s failure backoff window (pi
   lazyConnect): inside the window returns nil (the caller reports 'not
   available') instead of retrying; explicit connects bypass the window.
   Returns the live conn or nil."
  [state server]
  (when-not (failure-age-seconds state server)
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
         (when (seq message) (str " — " (truncate-at-word message 100)))
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
   {:on-update (fn [partial]) — progress streaming}. Returns the kmet
   tool result shape; the output guard (§settings :output-guard) bounds
   oversized text and rides details (:output-guard / :mcp-result)."
  [state server tool-name args & [opts]]
  (let [definition (server-definition state server)]
    (cond
      (nil? definition)
      (return-error (str "Server \"" server "\" not found. Use mcp({}) to see available servers."))

      (disabled? state server)
      (return-error (str "Server \"" server "\" is disabled. Run /mcp enable " server
                         " and /reload to enable it."))

      :else
      (if-let [failed-ago (failure-age-seconds state server)]
        ;; pi executeCall: inside the 60s backoff window a lazy use does
        ;; not retry — explicit mcp({connect}) bypasses this
        (return-error (str "Server \"" server "\" not available (last failed "
                           failed-ago "s ago)"))
        (try
          (let [conn ((:ensure-connected-fn state) server)]
            (if conn
              (let [timeout-ms (or (:request-timeout-ms definition) 120000)
                    result (client/request! conn "tools/call"
                                            {:name tool-name :arguments (normalize-args args)}
                                            {:timeout-ms timeout-ms
                                             :on-notification (on-update-progress! (:on-update opts))})
                    formatted (client/format-result result)
                    guard-options (guard/resolve-options (settings state))
                    guarded (guard/guard-text (:text formatted) guard-options)
                    details (guard/guarded-details (:guard guarded)
                                                   (guard/bound-mcp-result result
                                                                           (:details-max-bytes guard-options)))]
                (if (:is-error formatted)
                  (return-error (:text guarded))
                  (cond-> {:content (:text guarded) :is-error false}
                    (seq details) (assoc :details details))))
              (return-error (str "Server \"" server "\" not connected"))))
          (catch Exception e
            (return-error (str "MCP call failed: " (ex-message e)))))))))

(defn script-tool-records
  "The cached MCP catalog as script-sandbox tool maps, keyed by the
   prefixed name (script.md T2 — replaces the retired mcpScript tool):
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
                                        (misconfigured? definition)))
                         tool (or (cached-tools state server) [])]
                     [(format-tool-name state server (:name tool)) server tool])]
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
                                                  {:on-update on-update}))}]))))
          (group-by first candidates))))

(defn read-mcp-resource
  "Read a resource by URI on a server (the read_<resource> direct-tool
   executor, pi executeCall resourceUri path). Ensures the connection;
   text/string contents are joined, blobs summarized. Returns the kmet
   tool result shape with output-guard details."
  [state server uri]
  (let [definition (server-definition state server)]
    (cond
      (nil? definition)
      (return-error (str "Server \"" server "\" not found. Use mcp({}) to see available servers."))

      (disabled? state server)
      (return-error (str "Server \"" server "\" is disabled. Run /mcp enable " server
                         " and /reload to enable it."))

      :else
      (if-let [failed-ago (failure-age-seconds state server)]
        (return-error (str "Server \"" server "\" not available (last failed "
                           failed-ago "s ago)"))
        (try
          (let [conn ((:ensure-connected-fn state) server)]
            (if conn
              (let [result (client/read-resource conn uri)
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
                    guard-options (guard/resolve-options (settings state))
                    guarded (guard/guard-text text guard-options)
                    details (guard/guarded-details (:guard guarded)
                                                   (guard/bound-mcp-result result
                                                                           (:details-max-bytes guard-options)))]
                (cond-> {:content (:text guarded) :is-error false}
                  (seq details) (assoc :details details)))
              (return-error (str "Server \"" server "\" not connected"))))
          (catch Exception e
            (return-error (str "MCP resource read failed: " (ex-message e)))))))))

;; ─── Dispatch (§9.2) ──────────────────────────────────────────────────────

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
            (search-text state (:search params) regex? (:server params)
                         include-schemas? (:limit params) (:offset params))

            (some? (:describe params))
            (describe-text state (:describe params))

            (some? (:tool params))
            (let [match (find-tool state (:tool params))
                  server (or (:server params)
                             (when (and match (not= :ambiguous match))
                               (:server match)))]
              (if (nil? server)
                (if (= :ambiguous match)
                  (return-error (str "Tool \"" (:tool params) "\" matches multiple servers. "
                                     "Specify a server with mcp({ tool: ..., server: \"...\" })."))
                  (return-error (str "Tool \"" (:tool params) "\" not found. "
                                     "Use mcp({ search: \"...\" }) to search.")))
                ;; the wire call uses the RAW tool name — prefixed spellings
                ;; (plan §10.4: tool: "server_tool") resolve through the cache
                (call-mcp-tool state server
                               (if (and match (not= :ambiguous match))
                                 (:name (:tool match))
                                 (:tool params))
                               (:args params)
                               {:on-update on-update})))

            (some? (:connect params))
            (try
              (let [conn ((:ensure-connected-fn state) (:connect params))
                    ;; fresh view after the connect refreshed the cache
                    fresh @state-atom]
                (if conn
                  (list-text fresh (:connect params))
                  (return-error (str "Failed to connect to \"" (:connect params) "\""))))
              (catch Exception e
                (return-error (str "Failed to connect to \"" (:connect params) "\": "
                                   (ex-message e)))))

            (some? (:disconnect params))
            (do ((:disconnect-fn state) (:disconnect params))
                {:content (str "Disconnected \"" (:disconnect params) "\".") :is-error false})

            (some? (:list params))
            (list-text state (:list params))

            (some? (:server params))
            (list-text state (:server params))

            :else
            {:content (status-text state) :is-error false})))))
