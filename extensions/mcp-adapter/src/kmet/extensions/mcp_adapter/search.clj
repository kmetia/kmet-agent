(ns kmet.extensions.mcp-adapter.search
  "mcp proxy search (§9.3): the pi search-ranking.ts port — weighted
   name/description/server/keyword scoring with searchKeywords boosts,
   regex mode, and the paged result rendering. Reads the metadata cache
   only (no spawn)."
  (:require [clojure.string :as str]
            [kmet.extensions.mcp-adapter.catalog :as catalog]
            [kmet.extensions.mcp-adapter.names :as names]))

(def ^:private max-regex-query-length 256)

;; ─── Search ranking (pi search-ranking.ts) ─────────────────────────────

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
            :when (and (not (catalog/disabled? state server-name))
                       (or (nil? server) (= server server-name)))]
      (doseq [tool (or (catalog/cached-tools state server-name) [])]
        (let [tool {:name (:name tool)
                    :original-name (:name tool)
                    :description (:description tool)}
              keywords (resolve-search-keywords definition (:name tool) server-name)
              score (score-tool-match tool server-name query keywords)]
          (when score
            (swap! matches conj {:server server-name :tool tool :score score})))))
    (vec (sort-by (juxt (comp - :score) (comp :name :tool)) @matches))))

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
              (swap! out conj line))))
        (when (< (+ (or offset 0) (count items)) total)
          (swap! out conj (str "\n" (count items) " of " total
                               " — offset: " (+ (or offset 0) (count items)) " for more")))
        {:content (str/join "\n" @out) :is-error false}))))
