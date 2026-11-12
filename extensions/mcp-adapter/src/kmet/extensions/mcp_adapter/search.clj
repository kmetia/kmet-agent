(ns kmet.extensions.mcp-adapter.search
  "mcp proxy search (§9.3): the metadata cache read through the shared ranker
   (`kmet.libs.ranking`, the pi search-ranking.ts port — weighted
   name/description/server/keyword scoring with searchKeywords boosts), plus
   regex mode and the paged result rendering. Reads the metadata cache only (no
   spawn); `tool_search` ranks the same way over the tools that are not
   declared yet."
  (:require [clojure.string :as str]
            [kmet.extensions.mcp-adapter.catalog :as catalog]
            [kmet.extensions.mcp-adapter.names :as names]
            [kmet.libs.ranking :as ranking]))

(def ^:private max-regex-query-length 256)

(defn- resolve-search-keywords
  "The configured :search-keywords values whose pattern matches the tool
   (by raw or prefixed name, pi resolveSearchKeywords)."
  [definition tool-name server-name]
  (let [map (:search-keywords definition)]
    (when (map? map)
      (let [candidates (names/tool-name-candidates server-name tool-name)
            out (atom [])
            seen (atom #{})]
        (doseq [[pattern values] map
                :when (and (vector? values)
                           (names/matches-tool-pattern candidates [pattern]))]
          (doseq [value values
                  :let [value (str/trim (str value))]
                  :when (and (seq value) (not (contains? @seen value)))]
            (swap! seen conj value)
            (swap! out conj value)))
        @out))))

(defn- rank-tool-matches
  "The cached tools of the enabled servers as ranker documents — the tool
   record under `:tool` (its `:inputSchema` renders the param lines), the
   server under `:server`, the configured keywords resolved per tool — ranked
   by the shared scorer (pi rankToolMatches)."
  [state query server]
  (ranking/rank
   (into []
         (mapcat (fn [[server-name definition]]
                   (when (and (not (catalog/disabled? state server-name))
                              (or (nil? server) (= server server-name)))
                     (map (fn [tool]
                            {:server server-name
                             :tool tool
                             :name (:name tool)
                             :original-name (:name tool)
                             :description (:description tool)
                             :keywords (resolve-search-keywords
                                        definition (:name tool) server-name)})
                          (or (catalog/cached-tools state server-name) [])))))
         (:mcp-servers (:config state)))
   query))

;; ─── Search (§9.3) ─────────────────────────────────────────────────────

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
                                (str " — " (catalog/truncate-at-word description 80))))))
          (sort-by key properties))))

(defn- search-tools
  "Search cached tools (§9.3): ranked by the shared ranker when the query is
   non-empty; regex mode tests name/description/keywords; an empty query with a
   SERVER lists that server's tools sorted by name. include/exclude do not
   filter the proxy search (pi parity — they gate direct-tool registration).
   Returns {:total :items} honoring :limit/:offset."
  [state query compiled server limit offset]
  (let [all (if (or compiled (str/blank? query))
              (let [items (atom [])]
                (doseq [[name _definition] (:mcp-servers (:config state))
                        :when (and (not (catalog/disabled? state name))
                                   (or (nil? server) (= server name)))]
                  (doseq [tool (or (catalog/cached-tools state name) [])]
                    (let [keywords (resolve-search-keywords
                                    (catalog/server-definition state name) (:name tool) name)
                          name-match? (if compiled
                                        (boolean (re-find compiled (:name tool)))
                                        true)
                          desc-match? (if compiled
                                        (boolean (re-find compiled (or (:description tool) "")))
                                        true)
                          kw-match? (if compiled
                                      (boolean (some #(re-find compiled %) keywords))
                                      true)]
                      ;; regex mode matches when the pattern hits ANY of the
                      ;; three fields (all are true when no regex is given)
                      (when (or name-match? desc-match? kw-match?)
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
   indented param lines (omitted when include-schemas? is false). OPTS:
   `:surface :eval` appends a copyable `mcp/call` line per hit. Invalid
   regex → error message."
  [state query regex? server include-schemas? limit offset & [opts]]
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
      (catalog/return-error (str "Invalid regex: " query))

      (and (str/blank? query) (nil? server))
      (catalog/return-error "Search query cannot be empty")

      (zero? total)
      {:content (str "No tools matching \"" query "\""
                     (when server (str " in \"" server "\"")))
       :is-error false}

      :else
      (let [out (atom [(str "Found " total " tool" (when (not= 1 total) "s")
                            " matching \"" query "\":\n")])]
        (doseq [{:keys [server tool]} items]
          (swap! out conj (str server ": "
                               (catalog/display-name state server :tool (:name tool) (:name tool))
                               " — " (or (:description tool) "(no description)")))
          (when (not= false include-schemas?)
            (doseq [line (schema-param-lines (:inputSchema tool))]
              (swap! out conj line)))
          (when (= :eval (:surface opts))
            (swap! out conj
                   (str "  call: "
                        (catalog/call-example
                         (catalog/display-name state server :tool (:name tool)
                                               (:name tool))
                         (:inputSchema tool))))))
        (when (< (+ (or offset 0) (count items)) total)
          (swap! out conj (str "\n" (count items) " of " total
                               " — offset: " (+ (or offset 0) (count items)) " for more")))
        {:content (str/join "\n" @out) :is-error false}))))
