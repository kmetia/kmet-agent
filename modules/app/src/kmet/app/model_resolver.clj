(ns kmet.app.model-resolver
  "Model resolution and selection (pi: packages/coding-agent/src/core/
   model-resolver.ts essentials): pattern parsing with optional :thinking
   suffixes, exact-reference matching with ambiguity rejection, alias/dated
   preference, --models scope resolution, and CLI --provider/--model
   resolution. The per-provider default model comes from the catalog
   (models/default-model-for) — kmet's generator encodes pi's
   defaultModelPerProvider, with the github-copilot deviation (gpt-5.4 needs
   openai-responses; the catalog default is the first anthropic claude
   model)."
  (:require [clojure.string :as str]
            [kmet.ai.models :as models]
            [kmet.ai.auth :as auth]))

;; ─── Alias / date matching (pi isAlias) ────────────────────────────────────

(defn- is-alias?
  "True when ID is an alias: ends with -latest, or has no -YYYYMMDD date
   suffix (pi isAlias)."
  [id]
  (or (str/ends-with? id "-latest")
      (not (re-find #"-\d{8}$" id))))

;; ─── Exact reference matching (pi findExactModelReferenceMatch) ───────────

(defn find-exact-model-reference-match
  "Canonical provider/id or bare-id match against MODELS (pi
   findExactModelReferenceMatch). A bare id must be unambiguous across
   providers — ambiguity returns nil. Case-insensitive. Returns the Model or
   nil."
  [model-ref models]
  (let [trimmed (str/trim model-ref)]
    (when (seq trimmed)
      (let [normalized (str/lower-case trimmed)
            canonical (filter #(= (str/lower-case (str (name (:provider %)) "/" (:id %)))
                                  normalized)
                              models)]
        (cond
          (= 1 (count canonical)) (first canonical)
          (< 1 (count canonical)) nil
          :else
          (if-let [slash (str/index-of trimmed "/")]
            (let [prov (subs trimmed 0 slash)
                  mid (subs trimmed (inc slash))
                  pm (when (and (seq prov) (seq mid))
                       (filter #(and (= (str/lower-case (name (:provider %)))
                                        (str/lower-case prov))
                                     (= (str/lower-case (:id %))
                                        (str/lower-case mid)))
                               models))]
              (when (= 1 (count pm)) (first pm)))
            (let [ids (filter #(= (str/lower-case (:id %)) normalized) models)]
              (when (= 1 (count ids)) (first ids)))))))))

;; ─── Pattern matching (pi tryMatchModel / parseModelPattern) ──────────────

(defn- try-match-model
  "Exact reference match, else partial id/name match preferring aliases over
   dated versions, then the lexicographically highest id (pi tryMatchModel)."
  [pattern models]
  (if-let [m (find-exact-model-reference-match pattern models)]
    m
    (let [lower (str/lower-case pattern)
          matches (filter #(or (str/includes? (str/lower-case (:id %)) lower)
                               (str/includes? (str/lower-case (or (:name %) "")) lower))
                          models)]
      (when (seq matches)
        (let [aliases (filter #(is-alias? (:id %)) matches)]
          (first (sort-by :id (fn [a b] (compare b a))
                          (if (seq aliases) aliases matches))))))))

(defn parse-model-pattern
  "Parse PATTERN against MODELS (pi parseModelPattern): exact/partial match
   with alias preference, then an optional :thinking suffix split off the
   last colon. In scope mode (STRICT? false) an invalid suffix is ignored
   with a warning; in strict mode (CLI) it fails. Returns
   {:model Model|nil :thinking-level keyword|nil :warning str|nil}."
  ([pattern models]
   (parse-model-pattern pattern models nil))
  ([pattern models {:keys [strict?]}]
   (let [trimmed (str/trim pattern)]
     (if-let [m (try-match-model trimmed models)]
       {:model m :thinking-level nil :warning nil}
       (if-let [colon (str/last-index-of trimmed ":")]
         (let [prefix (subs trimmed 0 colon)
               suffix (subs trimmed (inc colon))
               level (keyword suffix)]
           (if (models/valid-thinking-level? level)
             (let [r (parse-model-pattern prefix models {:strict? strict?})]
               (if (:model r)
                 (assoc r :thinking-level (when-not (:warning r) level))
                 r))
             (if strict?
               {:model nil :thinking-level nil :warning nil}
               (let [r (parse-model-pattern prefix models {:strict? strict?})]
                 (if (:model r)
                   (assoc r
                          :warning (str "Invalid thinking level \"" suffix
                                        "\" in pattern \"" pattern
                                        "\". Using default instead."))
                   r)))))
         {:model nil :thinking-level nil :warning nil})))))

;; ─── Scoped models (pi resolveModelScopeFromModels essentials) ────────────

(defn resolve-scoped-model
  "Resolve a scoped-list entry (full \"provider/id\" or bare id) to a Model
   record. Bare ids (the legacy --models/`:models` form) prefer the current
   provider, then an unambiguous cross-provider match (ambiguity → nil, like
   find-exact-model-reference-match)."
  [entry current-provider]
  (let [s (str entry)
        slash (str/index-of s "/")]
    (if slash
      (models/get-model (keyword (subs s 0 slash)) (subs s (inc slash)))
      (or (models/get-model current-provider s)
          (find-exact-model-reference-match s (models/get-models))))))

(defn scoped-models-for-context
  "The extension context's :scoped-models value (pi: ExtensionContext
   .scopedModels): scoped ENTRIES resolved to {:model Model} maps against the
   registered catalogue (not the auth-filtered available view cycling uses),
   dropping entries that no longer resolve and deduplicating by model. kmet
   drops scoped thinking levels, so the maps never carry :thinking-level."
  [entries current-provider]
  (into []
        (comp (keep (fn [entry]
                      (when-let [m (resolve-scoped-model entry current-provider)]
                        {:model m})))
              (distinct))
        entries))

(defn resolve-model-scope-models
  "Resolve --models PATTERNS to Model records (pi resolveModelScopeFromModels
   — the scoped list carries provider refs, unlike resolve-model-scope's bare
   ids). Each pattern via parse-model-pattern; patterns carrying a thinking
   level resolve to the model only (kmet drops scoped thinking levels);
   duplicate models collapse to the first occurrence (pi modelsAreEqual
   dedupe). Returns {:models [Model] :warnings [str]}."
  [patterns models]
  (loop [ps patterns acc [] warnings []]
    (if-let [p (first ps)]
      (let [{:keys [model warning]} (parse-model-pattern p models)]
        (if model
          (recur (rest ps)
                 (if (some #(= model %) acc) acc (conj acc model))
                 (cond-> warnings warning (conj warning)))
          (recur (rest ps) acc
                 (conj warnings (str "No models match pattern \"" p "\"")))))
      {:models (vec acc) :warnings (vec warnings)})))

(defn resolve-model-scope
  "Resolve --models PATTERNS to model ids (pi resolveModelScopeFromModels
   essentials, no glob support): each pattern via parse-model-pattern;
   patterns carrying a thinking level resolve to the model only. Returns
   {:models [ids] :warnings [str]}."
  [patterns models]
  (let [{:keys [models warnings]} (resolve-model-scope-models patterns models)]
    {:models (mapv :id models) :warnings warnings}))

;; ─── /model reference (pi handleModelCommand — exact only) ────────────────

(defn resolve-model-reference
  "Resolve a /model reference: exact provider/id or bare id with an optional
   :thinking suffix (pi handleModelCommand/findExactModelMatch — exact only,
   no fuzzy fallback; ambiguous bare ids and unknown refs yield nil). Returns
   {:model Model|nil :thinking-level keyword|nil}."
  [model-ref models]
  (let [trimmed (str/trim model-ref)
        colon (str/last-index-of trimmed ":")
        suffix (when colon (subs trimmed (inc colon)))]
    (if (and colon (models/valid-thinking-level? (keyword suffix)))
      (let [m (find-exact-model-reference-match (subs trimmed 0 colon) models)]
        {:model m :thinking-level (when m (keyword suffix))})
      (let [m (find-exact-model-reference-match trimmed models)]
        {:model m :thinking-level nil}))))

;; ─── CLI resolution (pi resolveCliModel essentials) ───────────────────────

(defn resolve-cli-model
  "Resolve CLI --provider/--model (pi resolveCliModel essentials). MODELS —
   all catalog models. Without a provider, exact matches are checked first:
   a bare id present in several providers is ambiguous unless exactly one
   matching provider is authenticated. \"provider/model\" infers the
   provider; a :thinking suffix on the pattern is returned for the caller to
   apply. kmet has no custom-model fallback (catalog-only), so an unmatched
   pattern fails fast instead of erroring at request time. Returns
   {:provider :model-id :thinking-level :warning :error} — error cases have
   nil model-id."
  ([cli-provider cli-model models]
   (if (nil? cli-model)
     ;; No --model: the caller falls back to the config/registry defaults
     {:provider cli-provider :model-id nil :thinking-level nil :warning nil :error nil}
     (let [pattern (str/trim cli-model)
           provider-known (when cli-provider (models/get-provider cli-provider))]
       (cond
         (and cli-provider (nil? provider-known))
         {:provider nil :model-id nil :thinking-level nil :warning nil
          :error (str "Unknown provider \"" (name cli-provider) "\".")}

         :else
         (let [slash-idx (str/index-of pattern "/")
               inferred (when (and (nil? cli-provider) slash-idx)
                          (let [maybe (keyword (subs pattern 0 slash-idx))]
                            (when (models/get-provider maybe) maybe)))
               provider-id (or cli-provider inferred)
               pat (if inferred (subs pattern (inc slash-idx)) pattern)]
           (if (nil? provider-id)
             ;; No provider context — pi checks exact matches first and
             ;; rejects cross-provider ambiguity
             (let [lower (str/lower-case pattern)
                   exact (filter #(or (= (str/lower-case (:id %)) lower)
                                      (= (str/lower-case
                                          (str (name (:provider %)) "/" (:id %)))
                                         lower))
                                 models)]
               (cond
                 (= 1 (count exact))
                 {:provider (:provider (first exact))
                  :model-id (:id (first exact))
                  :thinking-level nil :warning nil :error nil}

                 (< 1 (count exact))
                 (let [authed (filter #(auth/configured? (:provider %)) exact)]
                   (if (= 1 (count authed))
                     {:provider (:provider (first authed))
                      :model-id (:id (first authed))
                      :thinking-level nil :warning nil :error nil}
                     {:provider nil :model-id nil :thinking-level nil :warning nil
                      :error (str "Model \"" cli-model "\" is ambiguous across providers: "
                                  (str/join ", " (sort (map #(str (name (:provider %)) "/" (:id %))
                                                            exact)))
                                  ". Use --provider or provider/model.")}))

                 :else
                 (let [result (parse-model-pattern pattern models {:strict? true})]
                   (if-let [m (:model result)]
                     {:provider (:provider m)
                      :model-id (:id m)
                      :thinking-level (:thinking-level result)
                      :warning (:warning result)
                      :error nil}
                     {:provider nil :model-id nil :thinking-level nil :warning nil
                      :error (str "Model \"" cli-model "\" not found.")}))))
             ;; Provider context — an exact provider/id lookup comes first: a
             ;; catalog model id may itself contain a slash (CommandCode's
             ;; deepseek/deepseek-v4.1-flash), and parse-model-pattern's slash
             ;; branch would misread that id as provider/id and fall through to
             ;; the fuzzy match (which picked the -fast sibling).
             (let [candidates (models/get-models provider-id)
                   exact (models/get-model provider-id pat)
                   result (if exact
                            {:model exact :thinking-level nil :warning nil}
                            (parse-model-pattern pat candidates {:strict? true}))]
               (if-let [m (:model result)]
                 {:provider (:provider m)
                  :model-id (:id m)
                  :thinking-level (:thinking-level result)
                  :warning (:warning result)
                  :error nil}
                 {:provider provider-id
                  :model-id nil :thinking-level nil :warning nil
                  :error (str "Model \"" cli-model "\" not found"
                              (when provider-id
                                (str " for provider \"" (name provider-id) "\""))
                              ".")})))))))))
