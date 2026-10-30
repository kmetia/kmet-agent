(ns kmet.ai.models
  "Provider/model registry (pi: packages/ai types.ts + models.ts Models
   collection, adapted to EDN).

   The Model record is the unit of truth: all request shaping (URL, api
   dispatch, thinking, max tokens, cost) derives from the resolved Model,
   never from ad-hoc provider switches. Providers are data — an EDN blob
   registered in an atom — which is what later makes extension
   registerProvider trivial (pi: MutableModels.setProvider).

   Account aliases (models.edn :accounts) are registered here as providers
   too: an alias copies its base provider's models with :provider set to the
   alias id and :base-provider set to the base, so selection resolves per
   account while wire shaping and attribution still see the base provider
   (models/provider-family). It inherits no credential — env vars, :api-key
   and credential-carrying headers stay with the base account."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.ai.auth :as auth]
            [kmet.libs.dynamic-value :as dynamic-value]
            [kmet.ai.model-config :as model-config]
            [kmet.ai.registry :as registry]
            [kmet.ai.oauth :as oauth]
            [kmet.ai.provider-composer :as composer]
            [kmet.libs.catalog-cache :as catalog-cache]))

;; ─── Records (pi: types.ts Model / models.ts Provider) ─────────────────────

(defrecord Model [id            ;; string, e.g. "deepseek-v4-flash"
                  name          ;; string
                  provider      ;; keyword, e.g. :deepseek
                  api           ;; :openai-completions | :openai-responses | :openai-codex-responses
                                ;; | :azure-openai-responses | :anthropic-messages | :google-generative-ai
                                ;; | :mistral-conversations | :google-vertex | :bedrock-converse-stream
                  base-url      ;; string API base, e.g. "https://api.deepseek.com"
                  reasoning     ;; boolean
                  thinking-level-map ;; {level -> string|nil}, pi ThinkingLevelMap; optional
                  input         ;; [:text] | [:text :image]
                  cost          ;; {:input $/M :output $/M :cache-read $/M :cache-write $/M
                                ;;  :tiers [{:input-tokens-above N + 4 rates}] (pi ModelCost.tiers)}
                  context-window
                  max-tokens
                  sampling-params ;; optional map merged verbatim into openai-completions request bodies
                  headers       ;; optional static headers map (copilot)
                  compat        ;; map, api-specific; see models.md
                  base-provider ;; keyword: the catalog provider an account alias mirrors
                  ])

(defrecord Provider [id          ;; keyword
                     name        ;; string
                     api-types   ;; set of apis used by its models
                     models      ;; vector of Model (loaded from catalog)
                     env-vars    ;; [env-var-name ...], pi env-api-keys order
                     default-model ;; string id
                     base-url    ;; optional provider-level fallback
                     headers     ;; optional static headers (copilot)
                     api-key     ;; raw models.edn/extension api-key config value (Phase 6)
                     auth-header ;; bool: send Authorization: Bearer <key> (models.edn)
                     configured-headers ;; raw provider-level config headers (models.edn)
                     oauth       ;; OAuthAuth record (Phase 10; github-copilot)
                     base-provider ;; keyword: set on account aliases (see model-config :accounts)
                     ])

(defn provider-family
  "The catalog provider a Provider/Model record belongs to: an account
   alias's base provider, or its own provider id. Keywords pass through.
   Wire and attribution checks use this so an alias keeps its base provider's
   request shaping; provenance stamps keep using the alias id itself."
  [record-or-id]
  (if (keyword? record-or-id)
    record-or-id
    (or (:base-provider record-or-id) (:provider record-or-id) (:id record-or-id))))

;; ─── Cost (pi: models.ts calculateCost, minus cacheWrite1h) ─────────────────

(defn calculate-cost
  "USD cost of a normalized usage map ({:input :output :cache-read
   :cache-write} — e.g. usage/entry-usage (kmet.ai.usage) output (whose :input already
   excludes cache tokens, so cached tokens are never double-priced) at the
   model's $/M rates. Returns the pi-shaped breakdown map
   {:input :output :cache-read :cache-write :total}. Models with zero rates
   yield zero cost. Called at response time (llm) so each message is priced
   with the model that produced it, keeping totals correct across model
   switches.

   Cost tiers (pi ModelCost.tiers): a tier supplies a complete alternate
   rate set applied to the whole request when total input usage
   (input + cacheRead + cacheWrite) exceeds :input-tokens-above; the
   highest matching threshold wins (openai gpt-5.x long-context pricing)."
  [model usage]
  (let [{tokens-in :input tokens-out :output
         tokens-cr :cache-read tokens-cw :cache-write} usage
        total-in (+ (long (or tokens-in 0))
                    (long (or tokens-cr 0))
                    (long (or tokens-cw 0)))
        rates (or (->> (:tiers (:cost model))
                       (filter #(< (:input-tokens-above %) total-in))
                       (sort-by :input-tokens-above)
                       last)
                  (:cost model))
        {rate-in :input rate-out :output
         rate-cr :cache-read rate-cw :cache-write} rates
        f (fn [tokens rate]
            (/ (* (double (long (or tokens 0))) (double (or rate 0))) 1000000.0))
        cost-in (f tokens-in rate-in)
        cost-out (f tokens-out rate-out)
        cost-cr (f tokens-cr rate-cr)
        cost-cw (f tokens-cw rate-cw)]
    {:input cost-in :output cost-out :cache-read cost-cr :cache-write cost-cw
     :total (+ cost-in cost-out cost-cr cost-cw)}))

;; ─── Registry (pi: MutableModels) ──────────────────────────────────────────

(defonce providers-atom (atom {}))

;; Pristine catalog providers — compose-model-provider's base layer (pi:
;; builtins are never mutated; composed providers live in providers-atom).
(defonce ^:private builtins-atom (atom {}))

(defn register-provider!
  "Upsert a Provider by :id (pi: MutableModels.setProvider — provider ids are
   unique; registering again replaces the existing entry)."
  [provider]
  (registry/register-entity! providers-atom provider))

(defn unregister-provider!
  "Remove a provider by :id (pi: MutableModels.deleteProvider)."
  [id]
  (registry/unregister-entity! providers-atom id))

(defn clear-providers!
  "Remove all providers (pi: MutableModels.clearProviders)."
  []
  (registry/clear-entities! providers-atom))

(defn get-providers
  "All registered providers, in registration order."
  []
  (vec (vals @providers-atom)))

(defn get-provider
  "Provider record by keyword id, or nil when unknown."
  [id]
  (registry/get-entity providers-atom id))

(defn family-provider
  "The Provider record whose request config and login methods a Model/Provider
   record uses: an account alias's base provider, or the provider itself. Nil
   when the id is unknown. (Keywords pass through provider-family, so a bare id
   resolves to its own record.)"
  [record-or-id]
  (get-provider (provider-family record-or-id)))

(defn get-models
  "All models of one provider (pi: Models.getModels), or of every provider
   when PROV-ID is omitted."
  ([]
   (into [] (mapcat :models) (get-providers)))
  ([prov-id]
   (or (:models (get-provider prov-id)) [])))

(defn get-model
  "Model record for PROV-ID + model id, or nil (pi: Models.getModel)."
  [prov-id model-id]
  (registry/get-model providers-atom prov-id model-id))

(defn default-model-for
  "Default Model record for a provider: its :default-model id, or the first
   catalog model when unset (pi: defaultModelPerProvider)."
  [prov-id]
  (let [p (get-provider prov-id)]
    (or (get-model prov-id (:default-model p))
        (first (:models p)))))

(defn- oauth-available-model-ids
  "Available model ids from the provider's stored oauth credential (pi
   filterModels: credential.availableModelIds must be a string array), nil
   when absent or malformed — the full model list passes through."
  [provider-id]
  (let [cred (get-in (auth/get-credentials) [provider-id])]
    (when (and (map? cred) (= :oauth (:type cred))
               (vector? (:available-model-ids cred))
               (every? string? (:available-model-ids cred)))
      (:available-model-ids cred))))

(defn- provider-models-with-oauth
  "A provider's models filtered to what the account can use: when a stored
   oauth credential carries :available-model-ids, only those models are
   returned (pi getAvailable → provider.filterModels — Copilot login
   shrinks the registry to the account's plans)."
  [p]
  (if-let [ids (oauth-available-model-ids (:id p))]
    (let [available (set ids)]
      (filterv #(contains? available (:id %)) (:models p)))
    (:models p)))

(defn get-available
  "Models whose provider has complete auth (pi: Models.getAvailable) —
   auth.edn credential (api-key or oauth), configured key, or env var per
   the kmet.ai.auth table; oauth credentials additionally filter by the
   account's :available-model-ids. With PROV-ID, only that provider's
   available models."
  ([]
   (get-available nil))
  ([prov-id]
   (into []
         (for [p (if prov-id
                   (when-let [p (get-provider prov-id)] [p])
                   (get-providers))
               :when (and p (auth/configured? (:id p)))
               m (provider-models-with-oauth p)]
           m))))

;; ─── Catalog loading (pi: generated providers/data/*.json) ─────────────────

(defn- catalog-resource
  "Bundled catalog content by file name (e.g. \"deepseek.edn\",
   \"manifest.edn\"), read through io/resource so the same code serves a dev
   checkout (src/ on the classpath), the packaged uberjar (zipped entries),
   and a built image (embedded resources) — slurp opens file:, jar: and
   embedded URLs alike. Throws ex-info when the resource is missing."
  [fname]
  (let [res (str "kmet/ai/model_data/" fname)]
    (if-let [r (io/resource res)]
      (try (slurp r)
           (catch Exception e
             (throw (ex-info (str "Catalog " fname " is unreadable")
                             {:type :catalog-invalid :file fname} e))))
      (throw (ex-info (str "Catalog " fname " is missing from resources")
                      {:type :catalog-invalid :file fname})))))

(defonce ^:private catalog-cache (atom {}))

(declare validate-catalog!)

(defn- load-catalog-resource
  "Read + validate one bundled catalog file (parsed result cached — the
   bundled catalogs are static at runtime, and tests reload them many
   times, so a fresh EDN parse per load-catalogs! is pure waste)."
  [fname]
  (let [cached (get @catalog-cache fname)]
    (if (some? cached)
      cached
      (let [data (edn/read-string (catalog-resource fname))]
        (validate-catalog! fname data)
        (swap! catalog-cache assoc fname data)
        data))))

(def ^:dynamic *models-cache-dir*
  "User-level provider catalog cache, written by `kmet --generate-models`
   (the same pipeline as `bb generate-models`). load-catalogs! prefers it
   over the bundled catalogs when it is strictly newer (fresh-model-cache).
   Bindable for tests. nil (the default) defers to the agent-dir cache
   load-catalogs! is given; with no agent dir there is no cache."
  nil)

(def ^:dynamic *use-models-cache*
  "When false, load-catalogs! ignores *models-cache-dir* even when fresh.
   The test runner binds it false so suites always exercise the committed
   catalogs regardless of the local machine's cache state."
  true)

(def ^:private required-model-keys
  [:id :name :provider :api :base-url :reasoning :input :cost
   :context-window :max-tokens])

(defn- validate-catalog!
  "Structural sanity of a catalog blob: :provider info block, non-empty
   :models groups, required Model fields, no model id in two api groups.
   Throws ex-info naming the file (strict per-model validation is Phase 1's
   generator job)."
  [file data]
  (when-not (map? data)
    (throw (ex-info (str "Catalog " file " is not a map") {:type :catalog-invalid :file file})))
  (let [prov (:provider data)
        groups (:models data)]
    (when-not (and (map? prov) (:id prov))
      (throw (ex-info (str "Catalog " file " has no :provider info block")
                      {:type :catalog-invalid :file file})))
    (when-not (and (map? groups) (seq groups))
      (throw (ex-info (str "Catalog " file " has no :models groups")
                      {:type :catalog-invalid :file file})))
    (doseq [[api models] groups
            [mid mm] models]
      (when-not (and (map? mm)
                     (every? #(contains? mm %) required-model-keys))
        (throw (ex-info (str "Catalog " file " model " mid " is missing required fields")
                        {:type :catalog-invalid :file file :model mid})))
      (when (not= (name api) (name (:api mm)))
        (throw (ex-info (str "Catalog " file " model " mid " is in the " api
                             " group but declares :api " (:api mm))
                        {:type :catalog-invalid :file file :model mid}))))
    (doseq [[api models] groups]
      (when-let [dup (first (filter #(< 1 (count %))
                                    (vals (group-by key models))))]
        (throw (ex-info (str "Catalog " file " has duplicate model id in group " api)
                        {:type :catalog-invalid :file file :model (ffirst dup)}))))
    (let [all-ids (mapcat keys (vals groups))]
      (when-let [dup (first (filter #(< 1 (count %)) (vals (group-by identity all-ids))))]
        (throw (ex-info (str "Catalog " file " has model " (first dup) " in more than one api group")
                        {:type :catalog-invalid :file file :model (first dup)}))))))

(defn- builtin-oauth
  "The OAuthAuth record for a builtin catalog provider (pi: the provider
   factories declare auth.oauth — kmet attaches them at catalog load).
   model-ids is a thunk over the provider's loaded models so the enable-all
   login step has the catalog list (pi imports GITHUB_COPILOT_MODELS
   statically)."
  [provider]
  (case (:id provider)
    :github-copilot
    (oauth/make-github-copilot-oauth
     (fn [] (mapv :id (:models provider))))
    :openai-codex
    (oauth/make-openai-codex-oauth)
    :anthropic
    (oauth/make-anthropic-oauth)
    :openrouter
    (oauth/make-open-router-oauth)
    nil))

(defn- catalog-files
  "Bundled catalog file names from the manifest's :files map (excludes
   manifest.edn itself), sorted."
  []
  (let [manifest (edn/read-string (catalog-resource "manifest.edn"))]
    (sort (remove #(= "manifest.edn" %) (keys (:files manifest))))))

(defn- load-catalog-file
  "Read + validate one catalog file (parsed result cached by load-catalog-resource)."
  [file]
  (load-catalog-resource (str file)))

(defn- catalog->provider
  "Build a Provider record from a catalog blob: provider info from the
   :provider block, Model records from the :models api groups."
  [{:keys [provider models]}]
  (let [model-records (into []
                            (for [[_api group] models
                                  [_id mm] group]
                              (map->Model mm)))
        api-types (set (keys models))]
    (map->Provider (assoc provider
                          :api-types api-types
                          :models model-records))))

(defn- catalog-generation
  "The :generated-at timestamp of the bundled manifest (nil when unreadable)
   — the freshness marker of the bundled catalogs."
  []
  (try (:generated-at (edn/read-string (catalog-resource "manifest.edn")))
       (catch Exception _ nil)))

(defn- dir-generation
  "The :generated-at timestamp of the manifest.edn in DIR (nil when the
   directory holds no complete generation)."
  [dir]
  (let [f (fs/file dir "manifest.edn")]
    (when (fs/exists? f)
      (try (:generated-at (edn/read-string (slurp f)))
           (catch Exception _ nil)))))

(defn default-models-cache-dir
  "The user-level model catalog cache dir (<agent-dir>/models-cache).
   `kmet --generate-models` writes it; load-catalogs! reads it. AGENT-DIR is
   the host's root (kmet.app.config/get-agent-dir); kmet.ai resolves none."
  [agent-dir]
  (auth/agent-dir-path agent-dir "models-cache"))

(defn fresh-model-cache
  "*models-cache-dir (defaulting to the agent-dir cache) when usable: it
   must hold a complete generation (a manifest.edn with a :generated-at
   timestamp) strictly newer than the bundled catalogs' — so an upgrade
   that ships newer model data wins over a stale cache until
   `kmet --generate-models` refreshes it (pi remote-catalog semantics: the
   persisted overlay applies only over older locally generated data).
   AGENT-DIR is the host's root; nil means no user root, so no cache. nil
   otherwise."
  [agent-dir]
  (when *use-models-cache*
    (when-let [dir (or *models-cache-dir*
                       (some-> agent-dir default-models-cache-dir))]
      (when-let [cache-gen (dir-generation dir)]
        (let [bundled (catalog-generation)]
          (when (and bundled (pos? (compare cache-gen bundled)))
            dir))))))

(defn- load-dir-providers
  "Provider map from catalog EDN files directly under DIR (throws on an
   unreadable/invalid catalog — callers fall back to the bundled data)."
  [dir]
  (into {}
        (for [fname (->> (fs/list-dir dir)
                         (filter fs/regular-file?)
                         (map fs/file)
                         (filter #(str/ends-with? (str %) ".edn"))
                         (remove #(= "manifest.edn" (fs/file-name %)))
                         (sort-by fs/file-name)
                         (map str))
              :let [data (try (edn/read-string (slurp fname))
                              (catch Exception _
                                (throw (ex-info (str "Catalog " fname " is unreadable")
                                                {:type :catalog-invalid :file fname}))))]]
          (do (validate-catalog! fname data)
              [(:id (:provider data)) (catalog->provider data)]))))

;; Startup calls load-catalogs! more than once (directly, then through
;; load-models-config!) — announce a newly-adopted cache source only on the
;; transition, not on every reload.
(defonce ^:private announced-cache (atom nil))

(defn- load-bundled-providers
  "The pristine built-in providers, read from the bundled catalog resources."
  []
  (into {}
        (for [fname (catalog-files)
              :let [data (load-catalog-file fname)]]
          [(:id (:provider data)) (catalog->provider data)])))

(defn load-catalogs!
  "Load all provider catalogs into the registry, replacing whatever was
   registered before: the user-level cache (*models-cache-dir*, written by
   `kmet --generate-models`) when strictly newer than the bundled data,
   else the bundled model_data/ — falling back to the bundled data with a
   warning when a present-but-unusable cache fails to load. Also snapshots
   the pristine builtins (compose-model-provider's base layer — pi never
   mutates its builtins map) and installs the auth config-key source
   (models.edn / extension :api-key), so auth resolution reflects composed
   providers. Returns the providers map. Call once at startup (pi registers
   its generated providers at creation). AGENT-DIR is the host's root
   (kmet.app.config/get-agent-dir); nil reads the bundled catalogs only."
  [agent-dir]
  (auth/set-config-key-source! (fn [provider-id] (:api-key (get-provider provider-id))))
  (auth/set-oauth-source! (fn [provider-id] (:oauth (get-provider provider-id))))
  (let [providers
        (or (when-let [cache (fresh-model-cache agent-dir)]
              (catalog-cache/try-cached announced-cache cache
                                        "Models: using cached catalogs from"
                                        load-dir-providers
                                        "Warning: ignoring unusable model catalog cache:"))
            (do (reset! announced-cache nil)
                (load-bundled-providers)))
        providers (into {}
                        (for [[pid p] providers]
                          [pid (assoc p :oauth (builtin-oauth p))]))]
    (reset! builtins-atom providers)
    (reset! providers-atom providers)
    providers))

;; ─── Provider composition (pi: ModelRuntime rebuildProviders /           ──
;;    recomposeProvider / registerProvider / registerNativeProvider /        ──
;;    unregisterProvider)                                                    ──

(def ^:private composition-errors (atom {}))
(defonce ^:private extension-providers (atom {}))
(defonce ^:private native-extension-providers (atom {}))

(defn- recompose-provider!
  "Compose ONE provider from its layers (pi ModelRuntime.recomposeProvider):
   base = native extension provider ?? pristine builtin; extension = the
   registered extension config; config = models.edn. No layers at all → the
   provider is removed. Base with no overlays → the pristine builtin
   registers untouched (exact auth/stream behavior, pi). Composition errors
   fall back to the base (or drop) and are recorded for
   get-model-config-error."
  [provider-id]
  (let [base (or (get @native-extension-providers provider-id)
                 (get @builtins-atom provider-id))
        extension (get @extension-providers provider-id)
        config (model-config/get-provider provider-id)]
    (cond
      (and (nil? base) (nil? config) (nil? extension))
      (do (unregister-provider! provider-id)
          (swap! composition-errors dissoc provider-id))

      (and base (nil? config) (nil? extension))
      (do (register-provider! base)
          (swap! composition-errors dissoc provider-id))

      :else
      (try
        (register-provider!
         (-> (composer/compose-model-provider provider-id base config extension)
             (update :models #(mapv map->Model %))
             map->Provider))
        (swap! composition-errors dissoc provider-id)
        (catch Exception e
          (swap! composition-errors assoc provider-id (ex-message e))
          (when base (register-provider! base)))))))

(defn- credential-header?
  "True for a header name that carries a credential (Authorization, cookie,
   *-api-key, *-token, *-secret), case-insensitive."
  [header-name]
  (let [n (str/lower-case (str header-name))]
    (or (contains? #{"authorization" "proxy-authorization" "cookie"} n)
        (str/includes? n "api-key")
        (str/ends-with? n "token")
        (str/ends-with? n "-secret"))))

(defn- without-credential-headers
  "Drop the credential-carrying entries of a raw header map (nil-safe), so an
   account alias never inherits the base account's static auth."
  [headers]
  (when (map? headers)
    (into {} (remove (fn [[k _]] (credential-header? k))) headers)))

(defn- compose-account-provider
  "Compose an account alias from the provider it mirrors: identical models
   and provider-level request config, its own raw :api-key (from the account
   entry), and no inherited credential — the base provider's env vars, static
   auth headers and model-level auth headers authenticate the base account,
   not the alias. Non-auth headers (integration ids, betas, gateway routing)
   are inherited; a `#label` alias is the same provider with another login."
  [alias-id config]
  (let [base-id (or (model-config/account-base alias-id config)
                    (throw (ex-info (str "Account " (name alias-id) " has no provider")
                                    {:type :model-config-invalid})))
        base (get-provider base-id)]
    (when-not base
      (throw (ex-info (str "Account " (name alias-id) " mirrors unknown provider "
                           (name base-id))
                      {:type :model-config-invalid})))
    (when (:base-provider base)
      (throw (ex-info (str "Account " (name alias-id) " mirrors another account alias "
                           (name base-id))
                      {:type :model-config-invalid})))
    (-> base
        (assoc :id alias-id
               :base-provider base-id
               :name (or (:name config)
                         (str (or (:name base) (name base-id))
                              " (" (model-config/account-label alias-id) ")"))
               :api-key (:api-key config)
               :configured-headers (without-credential-headers (:configured-headers base))
               :models (mapv (fn [m]
                               (-> m
                                   (assoc :provider alias-id :base-provider base-id)
                                   (update :headers without-credential-headers)
                                   map->Model))
                             (:models base)))
        (dissoc :env-vars)
        map->Provider)))

(defn- base-provider-ids
  "Every provider id composed from a base layer (builtin, models.edn or
   extension), before account aliases are added."
  []
  (into (sorted-set)
        (concat (keys @builtins-atom)
                (model-config/get-provider-ids)
                (keys @extension-providers)
                (keys @native-extension-providers))))

(defn- recompose-account-provider!
  "Compose one account alias, recording a composition error (and dropping the
   alias) when a real provider already owns its id — the provider wins — or
   when its base is missing or invalid."
  [alias-id config]
  (let [alias-id (keyword alias-id)]
    (if (contains? (base-provider-ids) alias-id)
      (swap! composition-errors assoc alias-id
             (str "Account alias id \"" (name alias-id) "\" collides with a provider id"))
      (try
        (register-provider! (compose-account-provider alias-id config))
        (swap! composition-errors dissoc alias-id)
        (catch Exception e
          (unregister-provider! alias-id)
          (swap! composition-errors assoc alias-id (ex-message e)))))))

(defn- recompose-account-providers!
  "Compose every declared account alias."
  []
  (doseq [[alias-id config] (model-config/get-accounts)]
    (recompose-account-provider! alias-id config)))

(defn- recompose-accounts-of!
  "Refresh the account aliases affected by BASE-ID's own composition change
   (extension registration/unregistration): the aliases that mirror it, and an
   alias whose own id BASE-ID shadows (it comes back when that provider goes)."
  [base-id]
  (doseq [[alias-id config] (model-config/get-accounts)
          :let [alias-id (keyword alias-id)]
          :when (or (= base-id alias-id)
                    (= base-id (model-config/account-base alias-id config)))]
    (recompose-account-provider! alias-id config)))

(defn- recompose-all!
  "Clear the registry and compose every base provider followed by the
   account aliases (load-models-config! and extension clearing)."
  []
  (clear-providers!)
  (reset! composition-errors {})
  (doseq [pid (base-provider-ids)]
    (recompose-provider! pid))
  (recompose-account-providers!)
  @providers-atom)

(defn- merge-defined
  "pi: re-registration merges defined values over the previous registration
   and preserves undefined (nil) ones."
  [previous config]
  (merge previous (into {} (remove (comp nil? val)) config)))

(defn- ensure-auth-hook!
  "Install the auth config-key source if missing (load-catalogs! sets it;
   extensions may register providers without going through a catalog load)."
  []
  (when-not (auth/config-key-source-installed?)
    (auth/set-config-key-source! (fn [provider-id] (:api-key (get-provider provider-id))))))

(defn register-provider-config!
  "Register/replace an extension provider by config map (pi
   ModelRuntime.registerProvider + ctx.registerProvider(name, config)):
   validates the incoming registration eagerly (a broken config throws
   without touching stored state), merges it over any previous registration
   preserving unset fields, then recomposes the provider (builtin + models.edn
   + extension layers). Returns the effective config."
  [provider-id config]
  (let [provider-id (keyword provider-id)
        base (or (get @native-extension-providers provider-id)
                 (get @builtins-atom provider-id))
        models-config (model-config/get-provider provider-id)
        effective (merge-defined (get @extension-providers provider-id) config)]
    (ensure-auth-hook!)
    (composer/validate-extension-provider provider-id base models-config config)
    (swap! extension-providers assoc provider-id effective)
    (swap! native-extension-providers dissoc provider-id)
    (recompose-provider! provider-id)
    (recompose-accounts-of! provider-id)
    effective))

(defn unregister-provider-config!
  "Remove an extension provider registration (pi ModelRuntime.unregisterProvider
   + ctx.unregisterProvider): the provider falls back to its builtin (or
   disappears when it had none), keeping the models.edn layer."
  [provider-id]
  (let [provider-id (keyword provider-id)]
    (swap! extension-providers dissoc provider-id)
    (swap! native-extension-providers dissoc provider-id)
    (recompose-provider! provider-id)
    (recompose-accounts-of! provider-id)
    nil))

(defn register-native-provider!
  "Register a complete Provider record from an extension (pi
   ModelRuntime.registerNativeProvider + ctx.registerProvider(provider)):
   clears any config registration for the id, stores the provider as the
   base, recomposes. Throws on an empty provider id."
  [provider]
  (when (str/blank? (str (:id provider)))
    (throw (ex-info "Provider id must not be empty." {:type :model-config-invalid})))
  (ensure-auth-hook!)
  (swap! extension-providers dissoc (:id provider))
  (swap! native-extension-providers assoc (:id provider) provider)
  (recompose-provider! (:id provider))
  (recompose-accounts-of! (:id provider))
  provider)

(defn clear-extension-providers!
  "Remove all extension provider registrations (native + config) and
   recompose back to builtins + models.edn. Exported for tests."
  []
  (reset! extension-providers {})
  (reset! native-extension-providers {})
  (recompose-all!)
  nil)

(defn get-registered-provider-config
  "The registered extension config for a provider (pi
   ModelRegistry.getRegisteredProviderConfig), or nil."
  [provider-id]
  (get @extension-providers (keyword provider-id)))

(defn get-registered-native-provider
  "The registered native extension Provider for a provider (pi
   ModelRegistry.getRegisteredNativeProvider), or nil."
  [provider-id]
  (get @native-extension-providers (keyword provider-id)))

(defn get-registered-provider-ids
  "Provider ids with an extension registration (config or native, pi
   ModelRegistry.getRegisteredProviderIds)."
  []
  (vec (into (sorted-set)
             (concat (keys @extension-providers) (keys @native-extension-providers)))))

;; ─── Extension facade (pi ModelRegistry) ───────────────────────────────────

(defn has-configured-auth
  "True when a model's (or provider id's) provider has complete auth (pi
   ModelRegistry.hasConfiguredAuth)."
  [model-or-provider]
  (auth/configured? (if (map? model-or-provider)
                      (:provider model-or-provider)
                      model-or-provider)))

(defn get-provider-auth-status
  "Auth status for a provider (pi ModelRegistry.getProviderAuthStatus):
   {:configured bool :source kw} — a stored oauth credential, then the
   auth.edn api-key, then the models.edn/extension api-key config
   (configuredRequestAuthStatus — a configured-but-unresolvable key reports
   {:configured false}, blocking the env fallback like resolve), then any
   other configured auth (env vars, native provider keys — pi's snapshot
   auth check). :source ∈ :oauth | :stored | :models-json-key |
   :models-json-command | :environment | :fallback."
  [provider-id]
  (let [stored (get-in (auth/get-credentials) [provider-id])
        provider (get-provider provider-id)
        configured (when provider
                     (or (composer/configured-request-auth-status
                          (model-config/get-provider provider-id)
                          (get @extension-providers provider-id))
                         ;; an account alias keeps its :api-key in the
                         ;; composed provider, not in a models.edn provider
                         ;; entry
                         (when (:base-provider provider)
                           (composer/configured-request-auth-status
                            {:api-key (:api-key provider)} nil))))]
    (cond
      ;; a stored oauth credential needs a registered OAuthAuth, like
      ;; auth/configured? (pi checkAuth); without one it is not configured
      (and (map? stored) (= :oauth (:type stored)))
      (if (some? (:oauth provider))
        {:configured true :source :oauth}
        {:configured false})
      stored {:configured true :source :stored}
      configured configured
      (auth/configured? provider-id) {:configured true :source :environment}
      :else {:configured false})))

(defn get-api-key-and-headers
  "Resolved request auth for a model (pi ModelRegistry.getApiKeyAndHeaders):
   {:ok true :api-key str? :headers map?} — the resolved key (or the
   Authorization bearer for an anthropic AUTH_TOKEN resolution) plus the
   model/provider configured headers resolved as config values — or
   {:ok false :error str}: unknown provider, no key when the provider
   requires one (:auth-header), or an unresolvable configured header (pi:
   getAuth throws → ok:false with the message)."
  [model]
  (let [provider-id (:provider model)
        provider (get-provider provider-id)]
    (if (nil? provider)
      {:ok false :error (str "Unknown provider: " (name (or provider-id :unknown)))}
      (try
        (let [auth (auth/resolve-provider-auth provider-id)
              api-key (:api-key auth)
              bearer (:bearer auth)
              headers (dynamic-value/resolve-headers-or-throw
                       (merge (:headers model) (:configured-headers provider))
                       (str "model \"" (name provider-id) "/" (:id model) "\""))
              headers (if bearer
                        (assoc headers "Authorization" (str "Bearer " bearer))
                        headers)]
          (if (and (nil? api-key) (nil? bearer) (:auth-header provider))
            {:ok false :error (str "No API key found for \"" (name provider-id) "\"")}
            {:ok true :api-key api-key :headers headers}))
        (catch Exception e
          {:ok false :error (ex-message e)})))))

(defn load-models-config!
  "Load models.edn and recompose every provider (pi ModelRuntime.refresh →
   rebuildProviders): builtin ∪ models.edn ∪ extension provider ids, with
   the models.edn and extension layers applied. Restores the pristine
   builtin catalogs first so repeated calls (startup, /reload) never stack
   the layers onto already-composed providers (pi: composed providers live
   in the runtime collection, the builtins map is never mutated — removed
   providers/models disappear on reload). A models.edn parse/schema failure
   keeps the built-ins and is surfaced via get-model-config-error;
   per-provider composition errors fall back to the built-in for that
   provider (a config-only provider that fails to compose is dropped).
   Registers the auth config-key source (models.edn/extension :api-key), so
   auth resolution and availability reflect configured keys. AGENT-DIR is
   the host's root (kmet.app.config/get-agent-dir): the global models.edn's
   scope dir and the models-cache default."
  [agent-dir]
  (load-catalogs! agent-dir)
  (model-config/load-config! agent-dir)
  (recompose-all!))

(defn get-model-config-error
  "models.edn load + composition error string (pi ModelRuntime.getError):
   the config parse/schema error plus each per-provider composition failure
   ('Provider \"X\": <error>'). nil when clean."
  []
  (let [config-error (model-config/get-error)
        comp-errors (->> @composition-errors
                         (map (fn [[pid err]]
                                (str "Provider \"" (name pid) "\": " err)))
                         sort
                         vec)]
    (when (or config-error (seq comp-errors))
      (str (when config-error config-error)
           (when (and config-error (seq comp-errors)) "\n\n")
           (str/join "\n\n" comp-errors)))))

;; ─── Config interop ────────────────────────────────────────────────────────

(defn resolve-config-model
  "Model id for a config: the :model setting, else the config provider's
   default model from the registry (pi: resolveModelConfig — settings.edn
   semantics unchanged, provider defaults now come from the catalog instead
   of default-config's :providers map)."
  [config]
  (or (:model config)
      (:id (default-model-for (:provider config)))))

;; ─── Thinking levels (pi ThinkingLevel) ───────────────────────────────────

(def thinking-levels
  "All thinking levels in order (pi ThinkingLevel)."
  [:off :minimal :low :medium :high :xhigh :max])

(defn valid-thinking-level?
  "True when LEVEL is one of the thinking levels (pi isValidThinkingLevel)."
  [level]
  (some #{level} thinking-levels))

(defn get-supported-thinking-levels
  "Levels a model can express (pi getSupportedThinkingLevels — public for
   the /settings selector): non-reasoning models only :off; :xhigh/:max
   require an entry in the model's thinking-level-map; a null map value
   marks a level unsupported (absent entries are supported)."
  [model]
  (if-not (:reasoning model)
    [:off]
    (let [tlm (:thinking-level-map model)
          has-entry? #(and (map? tlm) (contains? tlm %))]
      (into []
            (for [level thinking-levels
                  :when (if (contains? #{:xhigh :max} level)
                          (and (has-entry? level) (some? (get tlm level)))
                          (not (and (has-entry? level) (nil? (get tlm level)))))]
              level)))))

(defn clamp-thinking-level
  "The requested level when supported; :off stays :off; nil or unsupported
   levels clamp to the highest supported level."
  [model level]
  (let [available (get-supported-thinking-levels model)]
    (cond
      ;; Explicit :off — respect it
      (= level :off) :off
      ;; Level is supported — use it
      (some #{level} available) level
      ;; Nil or unsupported — highest supported (or :off if nothing else)
      :else (or (last available) :off))))

(defn effort-value
  "Wire reasoning_effort string for a level: the model's mapped value, else
   the level name (pi: model.thinkingLevelMap?.[level] ?? level)."
  [model level]
  (let [mapped (get-in model [:thinking-level-map level])]
    (if (nil? mapped) (name level) mapped)))

(defn off-explicitly-null?
  "True when the model's thinking-level-map pins :off to null — the provider
   cannot disable thinking (pi: thinkingLevelMap?.off !== null gates the
   disabled-thinking params)."
  [model]
  (let [tlm (:thinking-level-map model)]
    (and (map? tlm) (contains? tlm :off) (nil? (:off tlm)))))

(defn effective-effort
  "Clamped thinking level for the resolved model; nil when off (pi
   agent.ts: thinkingLevel === 'off' ? undefined : thinkingLevel, clamped by
   the model's capability)."
  [model thinking]
  (let [clamped (clamp-thinking-level model (or thinking :off))]
    (when-not (= :off clamped) clamped)))

;; ─── Thinking budgets (pi: adjustMaxTokensForThinking) ────────────────────
;; Model metadata: the wire caps max_tokens with these and the compaction stage
;; sizes its summary request with the same number.

(def thinking-budgets
  "pi adjustMaxTokensForThinking defaultBudgets (minimal/low/medium/high)."
  {:minimal 1024 :low 2048 :medium 8192 :high 16384})

(defn clamp-reasoning
  "pi clampReasoning: xhigh/max collapse to high, the levels a budget-based
   thinking config knows; every other level (and nil) passes through."
  [effort]
  (if (contains? #{:xhigh :max} effort) :high effort))

(defn thinking-budget-for
  "Thinking-token budget of session LEVEL on MODEL (pi
   thinkingBudgetForLevel): 0 when thinking is off or the model does not
   reason; an unsupported level clamps to the model's highest, and
   xhigh/max use the high budget (pi clampReasoning)."
  [model level]
  (get thinking-budgets (clamp-reasoning (effective-effort model level)) 0))
