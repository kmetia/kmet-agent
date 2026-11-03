(ns kmet.libs.ranking
  "Weighted field ranking for tool-like documents — the port of pi's
   `search-ranking.ts` that `mcp search` and `tool_search` share.

   A document is a map with the fields the scorer weighs (`field-weights`):
   `:name` outranks `:original-name`, a `:server`/namespace match, the
   `:description` and configured `:keywords`. Extra keys pass through
   untouched, so a caller can carry its own payload (the MCP adapter keeps
   `:server` and `:tool` on the same map).

   The scorer is deliberately a weighted keyword match rather than pi's BM25:
   `mcp search`'s output and arguments must not change, so its contract — the
   candidates, the limit, the document fields — is pi's, and the arithmetic is
   the one kmet already shipped."
  (:require [clojure.string :as str]))

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

(defn- score-keywords
  "Bonus for configured keyword phrases (pi: per-phrase bonus). Keywords are
   discrete phrases, not document text: an exact phrase match dominates."
  [score flags phrases normalized-query query-tokens]
  (let [weight (field-weights :keywords)]
    (doseq [phrase phrases]
      (cond
        (= phrase normalized-query)
        (do (swap! score + (* weight 14))
            (swap! flags assoc :phrase true :exact true))
        (str/starts-with? phrase normalized-query)
        (do (swap! score + (* weight 9))
            (swap! flags assoc :phrase true))
        (str/includes? phrase normalized-query)
        (do (swap! score + (* weight 6))
            (swap! flags assoc :phrase true))))
    (let [keyword-tokens (vec (mapcat tokenize phrases))]
      (doseq [token query-tokens]
        (cond
          (some #{token} keyword-tokens)
          (do (swap! score + (* weight 4))
              (swap! flags update :matched conj token))
          (some (fn [keyword-token]
                  (or (str/starts-with? keyword-token token)
                      (and (>= (count keyword-token) min-stem-length)
                           (str/starts-with? token keyword-token))))
                keyword-tokens)
          (do (swap! score + (* weight 2))
              (swap! flags update :matched conj token))
          (some #(str/includes? % token) phrases)
          (do (swap! score + weight)
              (swap! flags update :matched conj token)))))))

(defn- score
  "Weighted match score for DOCUMENT against QUERY, or nil when it does not
   match (pi scoreToolMatch: phrase matches dominate, token coverage gates
   short queries, and a first-query-token-in-name or whole-field-exact match
   earns a bonus)."
  [{:keys [name original-name server description keywords]} query]
  (let [normalized-query (str/trim (normalize-search-text query))
        query-tokens (tokenize query)]
    (when (seq query-tokens)
      (let [fields {:name (normalize-search-text name)
                    :original-name (normalize-search-text original-name)
                    :server (normalize-search-text server)
                    :description (normalize-search-text (or description ""))}
            flags (atom {:matched #{} :phrase false :exact false})
            score (atom 0)]
        (doseq [[field value] fields]
          (let [weight (field-weights field)
                field-tokens (tokenize value)]
            (cond
              (= value normalized-query)
              (do (swap! score + (* weight 14))
                  (swap! flags assoc :phrase true :exact true))
              (str/starts-with? value normalized-query)
              (do (swap! score + (* weight 9))
                  (swap! flags assoc :phrase true))
              (str/includes? value normalized-query)
              (do (swap! score + (* weight 6))
                  (swap! flags assoc :phrase true)))
            (doseq [token query-tokens]
              (cond
                (some #{token} field-tokens)
                (do (swap! score + (* weight 4))
                    (swap! flags update :matched conj token))
                (some (fn [field-token]
                        (or (str/starts-with? field-token token)
                            (and (>= (count field-token) min-stem-length)
                                 (str/starts-with? token field-token))))
                      field-tokens)
                (do (swap! score + (* weight 2))
                    (swap! flags update :matched conj token))
                (str/includes? value token)
                (do (swap! score + weight)
                    (swap! flags update :matched conj token))))))
        (when (seq keywords)
          (let [phrases (->> keywords
                             (map #(str/trim (normalize-search-text %)))
                             (remove str/blank?))]
            (score-keywords score flags phrases normalized-query query-tokens)))
        (let [{:keys [matched phrase exact]} @flags
              coverage (/ (count matched) (count query-tokens))
              coverage-ok? (if (<= (count query-tokens) 2)
                             (= coverage 1)
                             (>= coverage 0.6))]
          (when (or phrase coverage-ok?)
            (swap! score + (if (= coverage 1) 25 (Math/round (double (* coverage 10)))))
            (when (some #{(first query-tokens)} (tokenize (:name fields)))
              (swap! score + 8))
            (when exact (swap! score + 20))
            @score))))))

(defn rank
  "DOCUMENTS matching QUERY, best first, at most LIMIT of them: each match is
   its document with `:score` added, sorted by score descending and then by
   `:name`, so equally good matches come back in a stable order. A document
   with no `:description` (or none of the weighed fields) scores on whatever it
   does have and never throws."
  ([documents query] (rank documents query nil))
  ([documents query limit]
   (let [matches (->> documents
                      (keep (fn [document]
                              (when-let [s (score document query)]
                                (assoc document :score s))))
                      (sort-by (juxt (comp - :score) :name))
                      vec)]
     (if limit (vec (take limit matches)) matches))))
