(ns kmet.ai.model-config
  "models.edn loading + schema validation (pi: model-config.ts ModelConfig).

   models.edn is the user-config layer over the provider registry:
   ~/.kmet/agent/models.edn (global) + .kmet/models.edn (project overrides,
   merged like settings.edn — kmet deviation from pi's single global
   models.json). One immutable load per startup/reload; a parse or schema
   failure yields an error string and the registry keeps the built-ins.
   AGENT-DIR is the host's state root (kmet.app.config/get-agent-dir): the
   global file sits under it.

   Validation is a manual walker (no TypeBox) producing path-style messages
   ('providers.my-provider.models[0].cost: expected number'), collected
   rather than thrown. Unknown keys pass (pi's TypeBox schemas allow
   additional properties).

   :accounts declares account aliases — provider ids that mirror a catalog
   provider so one provider can be used with several credentials. An entry
   is {:provider <base> :name <label> :api-key <key>}; a `base#label` id may
   omit :provider. models/load-models-config! composes each alias into its
   own Provider record; credentials stay in auth.edn under the alias id."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.ai.auth :as auth]
            [kmet.libs.edn-store :as eds]))

(defonce config-atom (atom {:providers {} :accounts {} :error nil}))

;; ─── Schema validation (pi validateModelsConfig, manual walker) ────────────

(def ^:private thinking-levels
  #{:off :minimal :low :medium :high :xhigh :max})

(def ^:private cost-rate-keys
  [:input :output :cache-read :cache-write])

(defn- valid-string?
  "Non-empty string (pi Type.String({ minLength: 1 }))."
  [x]
  (and (string? x) (pos? (count x))))

(defn- api-value?
  "Accept a keyword or non-empty string; normalized to keywords later."
  [x]
  (or (keyword? x) (valid-string? x)))

(defn- check
  "Conjoin a PATH: MESSAGE validation error onto ERRORS when TEST fails."
  [errors test path message]
  (if test errors (conj errors (str path ": " message))))

(defn- when-present
  "Conjoin a PATH: MESSAGE error when KEY is present in M and fails TEST."
  [errors m key path test message]
  (if (and (contains? m key) (not (test (get m key))))
    (conj errors (str path ": " message))
    errors))

(defn- validate-string-map
  "Every value a string (pi Record(Type.String(), Type.String()) — header
   values have no minLength)."
  [errors path m]
  (reduce (fn [errors [k v]]
            (check errors (string? v) (str path "." (name k)) "expected string"))
          errors m))

(defn- validate-provider-compat
  "Flat kmet compat map: known boolean keys type-checked, :thinking-format /
   :max-tokens-field keyword-or-string; unknown keys pass (pi TypeBox
   allows additional properties)."
  [errors path compat]
  (reduce (fn [errors [k v]]
            (let [p (str path "." (name k))]
              (cond
                (contains? #{:supports-reasoning-effort :supports-store
                             :supports-developer-role
                             :requires-reasoning-content-on-assistant-messages
                             :supports-usage-in-streaming :supports-finish-reason
                             :requires-tool-result-name
                             :requires-assistant-after-tool-result
                             :requires-thinking-as-text :supports-strict-mode
                             :supports-strict-tools} k)
                (check errors (boolean? v) p "expected boolean")

                (contains? #{:thinking-format :max-tokens-field} k)
                (check errors (api-value? v) p "expected keyword or string")

                :else errors)))
          errors compat))

(defn- validate-thinking-level-map
  "Map of thinking levels to string-or-nil (pi ThinkingLevelMapSchema)."
  [errors path m]
  (reduce (fn [errors [k v]]
            (let [p (str path "." (name k))]
              (cond-> errors
                (not (contains? thinking-levels k))
                (conj (str p ": unknown thinking level"))
                (and (some? v) (not (string? v)))
                (conj (str p ": expected string or nil")))))
          errors m))

(defn- validate-input
  "Vector of :text / :image (pi Array(Union(Literal('text'), Literal('image'))))."
  [errors path input]
  (reduce (fn [errors x]
            (check errors (contains? #{:text :image} x) path "expected :text or :image"))
          errors input))

(defn- validate-cost-rates
  "All four rate fields required numbers; optional :tiers (pi ModelCostSchema)
   — each tier an array entry with a positive :input-tokens-above and the
   four rate fields."
  [errors path cost]
  (let [errors (reduce (fn [errors k]
                         (check errors (number? (get cost k)) (str path "." (name k)) "expected number"))
                       errors cost-rate-keys)
        tiers (:tiers cost)]
    (if (nil? tiers)
      errors
      (if-not (vector? tiers)
        (conj errors (str path ".tiers: expected array"))
        (reduce (fn [errors [i tier]]
                  (if-not (map? tier)
                    (conj errors (str path ".tiers[" i "]: expected map"))
                    (let [tp (str path ".tiers[" i "]")]
                      (reduce (fn [errors k]
                                (check errors (number? (get tier k))
                                       (str tp "." (name k)) "expected number"))
                              (check errors (number? (:input-tokens-above tier))
                                     (str tp ".input-tokens-above") "expected number")
                              cost-rate-keys))))
                errors
                (map-indexed vector tiers))))))

(defn- validate-optional-cost-rates
  "Override cost: present fields must be numbers; :tiers, when present, is
   the full tier array (pi ModelOverrideSchema — cost tiers replace)."
  [errors path cost]
  (let [errors (reduce (fn [errors [k v]]
                         (check errors (number? v) (str path "." (name k)) "expected number"))
                       errors (dissoc cost :tiers))
        tiers (:tiers cost)]
    (if (nil? tiers)
      errors
      (if-not (vector? tiers)
        (conj errors (str path ".tiers: expected array"))
        (reduce (fn [errors [i tier]]
                  (if-not (map? tier)
                    (conj errors (str path ".tiers[" i "]: expected map"))
                    (let [tp (str path ".tiers[" i "]")]
                      (reduce (fn [errors k]
                                (check errors (number? (get tier k))
                                       (str tp "." (name k)) "expected number"))
                              (check errors (number? (:input-tokens-above tier))
                                     (str tp ".input-tokens-above") "expected number")
                              cost-rate-keys))))
                errors
                (map-indexed vector tiers))))))

(defn- validate-model-definition
  "models.edn model definition (pi ModelDefinitionSchema): id required;
   optional fields type-checked when present."
  [errors path m]
  (let [errors (-> errors
                   (check (valid-string? (:id m)) (str path ".id") "required non-empty string")
                   (when-present m :name (str path ".name") valid-string? "expected non-empty string")
                   (when-present m :api (str path ".api") api-value? "expected keyword or string")
                   (when-present m :base-url (str path ".base-url") valid-string? "expected non-empty string")
                   (when-present m :reasoning (str path ".reasoning") boolean? "expected boolean")
                   (when-present m :thinking-level-map (str path ".thinking-level-map") map? "expected map")
                   (when-present m :input (str path ".input") vector? "expected array")
                   (when-present m :cost (str path ".cost") map? "expected map")
                   (when-present m :context-window (str path ".context-window") number? "expected number")
                   (when-present m :max-tokens (str path ".max-tokens") number? "expected number")
                   (when-present m :sampling-params (str path ".sampling-params") map? "expected map")
                   (when-present m :headers (str path ".headers") map? "expected map")
                   (when-present m :compat (str path ".compat") map? "expected map"))]
    (cond-> errors
      (map? (:thinking-level-map m)) (validate-thinking-level-map (str path ".thinking-level-map") (:thinking-level-map m))
      (vector? (:input m)) (validate-input (str path ".input") (:input m))
      (map? (:cost m)) (validate-cost-rates (str path ".cost") (:cost m))
      (map? (:headers m)) (validate-string-map (str path ".headers") (:headers m))
      (map? (:compat m)) (validate-provider-compat (str path ".compat") (:compat m)))))

(defn- validate-model-override
  "Model override (pi ModelOverrideSchema): same as a definition minus
   id/api/base-url; cost rates are individually optional."
  [errors path m]
  (let [errors (-> errors
                   (when-present m :name (str path ".name") valid-string? "expected non-empty string")
                   (when-present m :reasoning (str path ".reasoning") boolean? "expected boolean")
                   (when-present m :thinking-level-map (str path ".thinking-level-map") map? "expected map")
                   (when-present m :input (str path ".input") vector? "expected array")
                   (when-present m :cost (str path ".cost") map? "expected map")
                   (when-present m :context-window (str path ".context-window") number? "expected number")
                   (when-present m :max-tokens (str path ".max-tokens") number? "expected number")
                   (when-present m :sampling-params (str path ".sampling-params") map? "expected map")
                   (when-present m :headers (str path ".headers") map? "expected map")
                   (when-present m :compat (str path ".compat") map? "expected map"))]
    (cond-> errors
      (map? (:thinking-level-map m)) (validate-thinking-level-map (str path ".thinking-level-map") (:thinking-level-map m))
      (vector? (:input m)) (validate-input (str path ".input") (:input m))
      (map? (:cost m)) (validate-optional-cost-rates (str path ".cost") (:cost m))
      (map? (:headers m)) (validate-string-map (str path ".headers") (:headers m))
      (map? (:compat m)) (validate-provider-compat (str path ".compat") (:compat m)))))

(defn- validate-models
  "Validate a provider's :models vector (pi Array(ModelDefinitionSchema)).
   PATH is the provider path; entries are reported as PATH.models[i]."
  [errors path models]
  (reduce (fn [errors [i dm]]
            (if-not (map? dm)
              (conj errors (str path ".models[" i "]: expected map"))
              (validate-model-definition errors (str path ".models[" i "]") dm)))
          errors (map-indexed vector models)))

(defn- validate-model-overrides
  "Validate a provider's :model-overrides map (pi Record(ModelOverrideSchema)).
   PATH is the provider path; entries are reported as PATH.model-overrides.<id>."
  [errors path overrides]
  (reduce (fn [errors [mid override]]
            (if-not (map? override)
              (conj errors (str path ".model-overrides." (name mid) ": expected map"))
              (validate-model-override errors (str path ".model-overrides." (name mid)) override)))
          errors overrides))

(defn- validate-provider
  "Provider config (pi ProviderConfigSchema)."
  [errors path m]
  (let [errors (-> errors
                   (when-present m :name (str path ".name") valid-string? "expected non-empty string")
                   (when-present m :base-url (str path ".base-url") valid-string? "expected non-empty string")
                   (when-present m :api-key (str path ".api-key") valid-string? "expected non-empty string")
                   (when-present m :api (str path ".api") api-value? "expected keyword or string")
                   (when-present m :headers (str path ".headers") map? "expected map")
                   (when-present m :compat (str path ".compat") map? "expected map")
                   (when-present m :auth-header (str path ".auth-header") boolean? "expected boolean")
                   (when-present m :models (str path ".models") vector? "expected array")
                   (when-present m :model-overrides (str path ".model-overrides") map? "expected map"))]
    (cond-> errors
      (map? (:headers m)) (validate-string-map (str path ".headers") (:headers m))
      (map? (:compat m)) (validate-provider-compat (str path ".compat") (:compat m))
      (map? (:model-overrides m))
      (validate-model-overrides path (:model-overrides m))
      (vector? (:models m))
      (validate-models path (:models m)))))

(declare account-base writable-account-id?)

(defn validate-config
  "Manual schema validation of a models.edn map (pi validateModelsConfig).
   Returns a vector of path-style error strings
   (\"providers.my-provider.models[0].cost: expected number\"); empty when
   valid. A file may declare providers, accounts, or both."
  [config]
  (if-not (map? config)
    ["root: expected object"]
    (let [providers (:providers config)
          accounts (:accounts config)]
      (cond-> []
        (and (nil? providers) (nil? accounts)) (conj "providers: expected object")
        (and (some? providers) (not (map? providers))) (conj "providers: expected object")
        (and (some? accounts) (not (map? accounts))) (conj "accounts: expected object")
        (map? providers)
        (into (reduce (fn [errors [pid m]]
                        (if-not (map? m)
                          (conj errors (str "providers." (name pid) ": expected map"))
                          (validate-provider errors (str "providers." (name pid)) m)))
                      [] providers))
        (map? accounts)
        (into (reduce (fn [errors [aid m]]
                        (let [path (str "accounts." (if (keyword? aid) (subs (str aid) 1) (str aid)))]
                          (cond
                            (not (or (keyword? aid) (string? aid)))
                            (conj errors (str path ": provider id must be a keyword or string"))

                            (or (and (keyword? aid) (namespace aid))
                                (and (string? aid) (str/includes? aid "/")))
                            (conj errors (str path ": account alias id must not contain '/'"))

                            (not (writable-account-id? aid))
                            (conj errors (str path ": account alias id must be ASCII"
                                              " letters, digits, '-', '_' or '.'"))

                            (not (map? m))
                            (conj errors (str path ": expected map"))

                            :else
                            (let [errors (-> errors
                                             (when-present m :provider (str path ".provider")
                                                           api-value? "expected keyword or string")
                                             (when-present m :name (str path ".name")
                                                           valid-string? "expected non-empty string")
                                             (when-present m :api-key (str path ".api-key")
                                                           valid-string? "expected non-empty string"))]
                              (if (account-base aid m)
                                errors
                                (conj errors (str path ".provider: required unless the account id has the form base#label")))))))
                      [] accounts))))))

;; ─── Account aliases ───────────────────────────────────────────────────────

(defn account-base
  "The base provider an account entry mirrors: its explicit :provider, else
   the part before '#' in an id like github-copilot#work. Nil when neither."
  [alias-id account]
  (if-let [p (:provider account)]
    (keyword p)
    (let [s (name alias-id)
          i (str/index-of s "#")]
      (when (and i (pos? i) (< (inc i) (count s)))
        (keyword (subs s 0 i))))))

(defn account-label
  "The short label of an account alias id: the part after '#', else the id."
  [alias-id]
  (let [s (name alias-id)
        i (str/index-of s "#")]
    (if i (subs s (inc i)) s)))

(def ^:private account-id-part-re
  "One `#`-separated part of an account alias id: ASCII letters, digits, '-',
   '_' and '.'. The id travels into `provider/model` references, `/login` and
   `--provider` arguments, terminal output and persisted session state, so it
   stays ASCII — no whitespace, no unicode-normalization ambiguity, no
   characters that would need escaping."
  #"[A-Za-z0-9._-]+")

(defn- account-id-parts
  "The `#`-separated parts of ALIAS-ID's name, empty parts included so an
   empty or trailing-# id fails the charset."
  [alias-id]
  (str/split (if (keyword? alias-id) (name alias-id) (str alias-id)) #"#" -1))

(defn writable-account-id?
  "True when ALIAS-ID can be an :accounts key: a plain (non-namespaced)
   keyword or string whose `#`-separated parts are ASCII [A-Za-z0-9._-].
   Such an id survives an EDN round-trip and every surface that carries it."
  [alias-id]
  (let [s (if (keyword? alias-id) (name alias-id) (str alias-id))]
    (and (or (keyword? alias-id) (string? alias-id))
         (not (and (keyword? alias-id) (namespace alias-id)))
         (not (str/includes? s "/"))
         (every? #(re-matches account-id-part-re %) (account-id-parts alias-id)))))

(defn parse-account-ref
  "Parse a /login reference of the form `base#label` into {:id :provider};
   nil when REF does not have both halves. Whitespace around the halves is
   trimmed, so `deepseek # work` reads as `deepseek#work`."
  [ref]
  (let [s (str/trim (str ref))]
    (when-let [i (str/index-of s "#")]
      (let [base (str/trim (subs s 0 i))
            label (str/trim (subs s (inc i)))]
        (when (and (seq base) (seq label))
          {:id (keyword (str base "#" label))
           :provider (keyword base)})))))

;; ─── File loading (pi ModelConfig.load) ────────────────────────────────────

(defn models-edn-paths
  "Global then project models.edn paths (project overrides global, like
   settings.edn). AGENT-DIR is the host's root
   (kmet.app.config/get-agent-dir): the global file sits under it."
  [agent-dir]
  [(auth/agent-dir-path agent-dir "models.edn")
   (str (fs/path (fs/cwd) ".kmet" "models.edn"))])

(defn- load-config-file
  "Read + parse one models.edn file. Returns {:data map} (missing file →
   {:data nil}) or {:error str} on parse failure or a non-map root."
  [path]
  (if-not (fs/exists? path)
    {:data nil}
    (if-let [content (try (slurp path) (catch Exception _ nil))]
      (try
        (let [parsed (edn/read-string content)]
          (if (map? parsed)
            {:data parsed}
            {:error (str "Invalid models.edn schema:\n  - root: expected object\n\nFile: " path)}))
        (catch Exception e
          {:error (str "Failed to parse models.edn: " (ex-message e) "\n\nFile: " path)}))
      {:error (str "Failed to load models.edn\n\nFile: " path)})))

(defn- normalize-provider-ids
  "models.edn provider keys may be keywords or strings (pi models.json uses
   string ids); kmet's registry is keyword-keyed, so ids normalize to
   keywords (a string key would otherwise compose a provider that
   (get-provider :kw) can never find)."
  [providers]
  (into {} (map (fn [[k v]] [(keyword k) v])) providers))

(defn- normalize-account-ids
  "models.edn account keys may be keywords or strings; normalize them to
   keywords like provider ids. An explicit :provider normalizes too."
  [accounts]
  (into {}
        (map (fn [[k v]]
               [(keyword k) (cond-> v (:provider v) (update :provider keyword))]))
        accounts))

(defn load-config
  "One immutable load of models.edn (global + project merged, project wins —
   pi loads only the global file). Returns {:providers {provider-id config}
   :accounts {alias-id config} :error str-or-nil}; a parse or schema failure
   yields :error with empty maps (the registry keeps the built-ins).
   AGENT-DIR is the host's root (kmet.app.config/get-agent-dir)."
  [agent-dir]
  (let [paths (models-edn-paths agent-dir)
        results (mapv load-config-file paths)
        errors (keep :error results)]
    (if (seq errors)
      {:providers {} :accounts {} :error (str/join "\n\n" errors)}
      (if (not-any? (fn [{:keys [data error]}] (or data error)) results)
        {:providers {} :accounts {} :error nil}
        (let [config (reduce (fn [acc {:keys [data]}] (eds/deep-merge acc data)) {} results)]
          (if-let [errors (seq (validate-config config))]
            {:providers {}
             :accounts {}
             :error (str "Invalid models.edn schema:\n"
                         (str/join "\n" (map #(str "  - " %) errors))
                         "\n\nFile: " (str/join ", " paths))}
            {:providers (normalize-provider-ids (or (:providers config) {}))
             :accounts (normalize-account-ids (or (:accounts config) {}))
             :error nil}))))))

(defn load-config!
  "Load models.edn into the config atom; returns the config map. Called at
   startup (models/load-models-config!) and from /reload. AGENT-DIR is the
   host's root (kmet.app.config/get-agent-dir)."
  [agent-dir]
  (let [config (load-config agent-dir)]
    (reset! config-atom config)
    config))

(defn get-providers
  "The loaded models.edn providers map (empty when none or on load error)."
  []
  (:providers @config-atom))

(defn get-provider
  "models.edn provider config for PROVIDER-ID, or nil."
  [provider-id]
  (get (:providers @config-atom) provider-id))

(defn get-provider-ids
  "Provider ids defined in models.edn."
  []
  (keys (:providers @config-atom)))

(defn get-accounts
  "The loaded models.edn account-alias map (empty when none or on load error)."
  []
  (:accounts @config-atom))

(defn get-account
  "models.edn account config for ALIAS-ID, or nil."
  [alias-id]
  (get (:accounts @config-atom) alias-id))

(defn get-account-ids
  "Account alias ids defined in models.edn."
  []
  (keys (:accounts @config-atom)))

(defn add-account!
  "Declare ALIAS-ID in the user's models.edn under :accounts. ACCOUNT is the
   account config (e.g. {:provider :github-copilot}); an existing entry for
   ALIAS-ID is replaced. The id must be a plain provider id
   (writable-account-id?) so the written file stays readable. Refuses to
   touch a models.edn that is not a valid EDN map, so a broken file can never
   be erased. Only the global file is
   written (the project .kmet/models.edn stays hand-managed). Returns the
   file path. AGENT-DIR is the host's root (kmet.app.config/get-agent-dir)."
  [alias-id account agent-dir]
  (when-not (writable-account-id? alias-id)
    (throw (ex-info (str "Account alias id " (pr-str alias-id)
                         " must be a plain ASCII provider id"
                         " (letters, digits, '-', '_' or '.', no '/' or whitespace)")
                    {:type :model-config-invalid :alias alias-id})))
  (let [path (first (models-edn-paths agent-dir))
        text (when (fs/exists? path)
               (try (slurp path) (catch Exception _ nil)))
        blank? (and text (str/blank? text))
        base (cond
               (nil? text) nil
               blank? {}
               :else (eds/safe-parse-edn-map text))]
    (when (and text (not blank?) (nil? base))
      (throw (ex-info "models.edn is not a valid EDN map; not rewriting it"
                      {:type :model-config-invalid :file path})))
    (when (and base (not (or (nil? (:accounts base)) (map? (:accounts base)))))
      (throw (ex-info ":accounts in models.edn is not a map"
                      {:type :model-config-invalid :file path})))
    ;; Write the whole top-level :accounts map: save-edn-setting!'s line
    ;; surgery preserves comments when the key is appended or on one line.
    (eds/save-edn-setting! path [:accounts]
                           (assoc (or (:accounts base) {}) (keyword alias-id) account))
    path))

(defn get-error
  "models.edn load error string (parse/schema), nil when clean."
  []
  (:error @config-atom))
