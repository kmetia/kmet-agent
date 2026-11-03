(ns kmet.ai.provider-composer
  "Provider composition (pi: provider-composer.ts): merge the builtin
   catalog, models.edn config, and extension layers into provider/model
   maps. Pure data — the caller (models.clj) converts to records and
   registers them.

   Layer order (bottom → top): builtin < models.edn < extension, with
   model-overrides applied last as the topmost user-config layer (pi
   composeModelProvider.getModels).

   Header deviation from pi: pi keeps model-level config headers separate
   and merges them at request time (model.headers < provider configured <
   model raw); kmet bakes the model-level raw headers into the model's
   :headers at compose time and llm merges provider-level configured
   headers after them, so a provider-level header wins over a colliding
   model-level one (pi lets the model win). /reload recomposes, so the
   observable behavior is equivalent."
  (:require [kmet.libs.dynamic-value :as dynamic-value]
            [kmet.ai.oauth :as oauth]))

(declare adapt-oauth)

(defn- as-keyword
  "Keyword for a keyword or string value."
  [x]
  (if (keyword? x) x (keyword x)))

(defn- normalize-compat
  "Keyword the enum-valued compat keys (kmet dispatch is keyword-based; pi
   dispatches on strings)."
  [compat]
  (cond-> compat
    (contains? compat :thinking-format) (update :thinking-format as-keyword)
    (contains? compat :max-tokens-field) (update :max-tokens-field as-keyword)))

(defn- merge-compat
  "Flat compat merge, override wins per key (pi mergeCompat — kmet's compat
   has no nested routing maps yet). An explicit nil never erases the base
   value: nil means \"leave it to the detected default\", the same rule
   resolved-openai-compat applies to a model's own :compat, so a key cannot
   disappear by being written out empty."
  [base override]
  (normalize-compat (if override
                      (merge base (into {} (remove (comp nil? val)) override))
                      base)))

(defn apply-model-override
  "Apply a models.edn model-override onto a model map (pi applyModelOverride):
   field-wise ?? merge; thinking-level-map merges key-wise; cost merges
   field-wise; compat merges key-wise. Returns the updated model map."
  [model override]
  (cond-> model
    (contains? override :name) (assoc :name (:name override))
    (contains? override :reasoning) (assoc :reasoning (:reasoning override))
    (contains? override :thinking-level-map)
    (assoc :thinking-level-map (merge (:thinking-level-map model) (:thinking-level-map override)))
    (contains? override :input) (assoc :input (:input override))
    (contains? override :cost) (assoc :cost (merge (:cost model) (:cost override)))
    (contains? override :context-window) (assoc :context-window (:context-window override))
    (contains? override :max-tokens) (assoc :max-tokens (:max-tokens override))
    (contains? override :sampling-params)
    (assoc :sampling-params (merge (:sampling-params model) (:sampling-params override)))
    (contains? override :compat) (assoc :compat (merge-compat (:compat model) (:compat override)))))

(defn- model-from-json
  "Build a model map from a models.edn model definition (pi modelFromJson):
   api/base-url fall back to the provider config then DEFAULTS (the existing
   model when upserting, else the provider's first model); both are
   required. Throws ex-info on missing api/base-url or non-positive
   context-window/max-tokens."
  [provider-id definition provider-config defaults]
  (let [raw-api (or (:api definition) (:api provider-config) (:api defaults))
        api (when raw-api (as-keyword raw-api))
        base-url (or (:base-url definition) (:base-url provider-config) (:base-url defaults))
        context-window (:context-window definition)
        max-tokens (:max-tokens definition)]
    (when-not api
      (throw (ex-info (str "Provider " (name provider-id) ", model " (:id definition)
                           ": no \"api\" specified. Set at provider or model level.")
                      {:type :model-config-invalid})))
    (when-not base-url
      (throw (ex-info (str "Provider " (name provider-id) ": \"base-url\" is required when defining custom models.")
                      {:type :model-config-invalid})))
    (when (and (some? context-window) (not (pos? context-window)))
      (throw (ex-info (str "Provider " (name provider-id) ", model " (:id definition) ": invalid contextWindow")
                      {:type :model-config-invalid})))
    (when (and (some? max-tokens) (not (pos? max-tokens)))
      (throw (ex-info (str "Provider " (name provider-id) ", model " (:id definition) ": invalid maxTokens")
                      {:type :model-config-invalid})))
    {:id (:id definition)
     :name (or (:name definition) (:id definition))
     :provider provider-id
     :api api
     :base-url base-url
     :reasoning (or (:reasoning definition) false)
     :thinking-level-map (:thinking-level-map definition)
     :input (mapv as-keyword (or (:input definition) [:text]))
     :cost (or (:cost definition) {:input 0 :output 0 :cache-read 0 :cache-write 0})
     :context-window (or context-window 128000)
     :max-tokens (or max-tokens 16384)
     :sampling-params (:sampling-params definition)
     :headers nil
     :compat (merge-compat (:compat provider-config) (:compat definition))}))

(defn apply-models-json
  "Apply the models.edn provider config onto the builtin models (pi
   applyModelsJson): base-url + compat overrides on every base model, then
   config.models upserted (an existing id is replaced in place, a new id
   appended). Defaults for a new model come from the first existing model.
   Throws when the config defines nothing at all (pi: 'must specify...')."
  [provider-id base-models config]
  (when config
    (when (and (empty? (:models config))
               (nil? (:base-url config))
               (empty? (:headers config))
               (empty? (:compat config))
               (empty? (:model-overrides config))
               (nil? (:api-key config))
               (nil? (:auth-header config)))
      (throw (ex-info (str "Provider " (name provider-id)
                           ": must specify \"base-url\", \"headers\", \"compat\", \"model-overrides\", or \"models\".")
                      {:type :model-config-invalid}))))
  (let [models (mapv (fn [model]
                       (-> model
                           (assoc :base-url (or (:base-url config) (:base-url model)))
                           (update :compat merge-compat (:compat config))))
                     base-models)]
    (reduce (fn [models definition]
              (let [existing-index (first (keep-indexed (fn [i m] (when (= (:id definition) (:id m)) i)) models))
                    defaults (if existing-index (nth models existing-index) (first models))
                    model (model-from-json provider-id definition config defaults)]
                (if existing-index
                  (assoc models existing-index model)
                  (conj models model))))
            models
            (:models config))))

(defn apply-extension
  "Apply an extension provider config onto the composed models (pi
   applyExtension — Phase 7's register-provider!): extension models replace
   wholesale; a base-url-only extension overrides every model's base-url.
   NIL-CONFIG passes through unchanged."
  [provider-id models config]
  (if-not config
    (vec models)
    (if-not (seq (:models config))
      (if (:base-url config)
        (mapv #(assoc % :base-url (:base-url config)) models)
        (vec models))
      (mapv (fn [definition]
              (let [defaults (or (first (filter #(= (:id definition) (:id %)) models))
                                 (first models))
                    raw-api (or (:api definition) (:api config) (:api defaults))
                    api (when raw-api (as-keyword raw-api))]
                (when-not api
                  (throw (ex-info (str "Provider " (name provider-id) ", model " (:id definition)
                                       ": no \"api\" specified. Set at provider or model level.")
                                  {:type :model-config-invalid})))
                (let [base-url (or (:base-url definition) (:base-url config) (:base-url defaults))]
                  (when-not base-url
                    (throw (ex-info (str "Provider " (name provider-id) ": \"base-url\" is required when defining custom models.")
                                    {:type :model-config-invalid})))
                  (-> definition
                      (assoc :api api :provider provider-id :base-url base-url)
                      (assoc :headers nil)))))
            (:models config)))))

(defn- raw-model-headers
  "Raw (unresolved) model-level header config: modelOverrides.headers <
   definition.headers < extension-model.headers (pi rawModelHeaders —
   resolved at request time by llm)."
  [model-id config extension]
  (let [definition (first (for [m (:models config) :when (= model-id (:id m))] m))
        ext-model (first (for [m (:models extension) :when (= model-id (:id m))] m))
        headers (merge (get-in config [:model-overrides model-id :headers])
                       (:headers definition)
                       (:headers ext-model))]
    (when (seq headers) headers)))

(defn validate-extension-provider
  "Run the extension layer composition eagerly (pi validateExtensionProvider):
   applies models.edn + the extension onto BASE's models so structural errors
   (missing api/base-url, invalid model fields) throw before a broken
   registration touches stored state, and adapts the extension :oauth config
   (a missing :login/:to-auth fn throws here too). kmet has no streamSimple,
   so there is no separate api-requirement check."
  [provider-id base models-config extension]
  (apply-extension provider-id (apply-models-json provider-id (:models base) models-config) extension)
  (adapt-oauth (:oauth extension))
  nil)

(defn adapt-oauth
  "Adapt an extension :oauth config map into an OAuthAuth record (pi
   adaptOAuth): the extension declares :name/:login/:to-auth (fns) and
   optionally :is-subscription?/:login-label/:refresh-token. kmet's
   OAuthAuth is a record; the extension config is plain data. Throws on a
   config missing the required fns — validate-extension-provider runs the
   composition eagerly, so a broken registration fails at register time
   instead of an NPE at login time."
  [config]
  (when (map? config)
    (when-not (and (fn? (:login config)) (fn? (:to-auth config)))
      (throw (ex-info "Provider oauth config requires :login and :to-auth functions"
                      {:type :model-config-invalid})))
    (oauth/map->OAuthAuth
     {:name (or (:name config) "OAuth")
      :is-subscription? (:is-subscription? config)
      :login-label (:login-label config)
      :login (:login config)
      :refresh (or (:refresh-token config)
                   (fn [credential _signal] credential))
      :to-auth (:to-auth config)})))

(defn compose-model-provider
  "Compose the builtin + models.edn + extension layers into a provider map
   (pi composeModelProvider). BASE is the builtin provider record (or nil
   for a config-defined provider), CONFIG the models.edn provider config,
   EXTENSION the extension config (Phase 7). Returns a provider map with
   :models (model maps), :api-key (raw config value), :auth-header,
   :configured-headers (raw provider-level config headers), plus the
   builtin :env-vars / :default-model. The composition runs eagerly so
   structural errors throw immediately (pi: getModels() called for its side
   effects)."
  [provider-id base config extension]
  (let [models (mapv (fn [model]
                       (let [overridden (apply-model-override model
                                                              (get-in config [:model-overrides (:id model)]))]
                         (assoc overridden :headers (merge (:headers overridden)
                                                           (raw-model-headers (:id model) config extension)))))
                     (apply-extension provider-id
                                      (apply-models-json provider-id (:models base) config)
                                      extension))
        api-key (or (:api-key extension) (:api-key config))
        auth-header (or (:auth-header extension) (:auth-header config) false)
        configured-headers (let [headers (merge (:headers config) (:headers extension))]
                             (when (seq headers) headers))]
    (merge {:id provider-id
            :name (or (:name extension) (:name config) (:name base) (name provider-id))
            :base-url (or (:base-url extension) (:base-url config) (:base-url base))
            :headers (:headers base)
            :configured-headers configured-headers
            :api-key api-key
            :auth-header auth-header
            ;; pi composeOAuthAuth: extension oauth adapted, else the builtin's
            ;; OAuthAuth (models.edn cannot express fns — config :oauth is not
            ;; a schema field and stays inert)
            :oauth (or (adapt-oauth (:oauth extension)) (:oauth base))
            :api-types (set (map :api models))
            :models models}
           (select-keys base [:env-vars :default-model]))))

(defn configured-request-auth-status
  "Auth status from the models.edn/extension apiKey config alone (pi
   configuredRequestAuthStatus): nil when no key is configured;
   {:configured bool :source kw} otherwise — a literal or !command key is
   always configured, a $ENV key needs the var present."
  [config extension]
  (when-let [value (or (:api-key extension) (:api-key config))]
    (cond
      (dynamic-value/is-command-config-value? value)
      {:configured true :source :models-json-command}

      (seq (dynamic-value/get-config-value-env-var-names value))
      (if (dynamic-value/is-config-value-configured? value)
        {:configured true :source :environment}
        {:configured false})

      :else {:configured true :source (if (:api-key extension) :fallback :models-json-key)})))
