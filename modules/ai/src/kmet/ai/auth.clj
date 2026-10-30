(ns kmet.ai.auth
  "Provider credential resolution (pi: packages/ai credential-store +
   env-api-keys.ts, adapted to EDN).

   auth.edn shape: {provider-or-account-alias {:key \"...\"}} —
   kmet-internal (pi's auth.json shape not required for parity). Account
   aliases are provider ids declared in models.edn :accounts (see
   kmet.ai.model-config), so one provider can hold several credentials.
   Loaded at startup (config/load-config); /login and /logout write it under
   a file lock.

   Resolution order (pi composeApiKeyAuth.resolve): auth.edn credential →
   models.edn/extension :api-key config value (registered via
   set-config-key-source! by models/load-models-config!) → env vars in pi
   order."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.libs.aws-sigv4 :as aws-sigv4]
            [kmet.libs.edn-store :as cred]
            [kmet.libs.dynamic-value :as dynamic-value]
            [kmet.ai.slots :as slots]
            [kmet.ai.google-adc :as google-adc]))

;; ─── Env var table (pi env-api-keys.ts) ────────────────────────────────────

(def ^:private getenv
  "Env lookup — indirected so tests can redef it without touching the real
   environment (babashka cannot set env vars)."
  (fn [k] (System/getenv k)))

(def env-vars-by-provider
  "Provider → env var names, in pi's order (pi: env-api-keys.ts).
   :anthropic participates in all three (pi findEnvKeys): AUTH_TOKEN is the
   Authorization: Bearer path (skipped by resolve-api-key, pi getEnvApiKey),
   OAUTH_TOKEN / API_KEY are the x-api-key paths. :google is reserved for a
   later provider set."
  {:opencode-go ["OPENCODE_API_KEY"]
   :opencode ["OPENCODE_API_KEY"]
   :deepseek ["DEEPSEEK_API_KEY"]
   :github-copilot ["COPILOT_GITHUB_TOKEN"]
   :openai ["OPENAI_API_KEY"]
   :xai ["XAI_API_KEY"]
   :openai-codex []                          ;; OAuth only (no env var)
   :azure-openai-responses ["AZURE_OPENAI_API_KEY"]
   :anthropic ["ANTHROPIC_AUTH_TOKEN" "ANTHROPIC_OAUTH_TOKEN" "ANTHROPIC_API_KEY"]
   :google ["GEMINI_API_KEY"]
   :groq ["GROQ_API_KEY"]
   :cerebras ["CEREBRAS_API_KEY"]
   :huggingface ["HF_TOKEN"]
   :moonshotai ["MOONSHOT_API_KEY"]
   :moonshotai-cn ["MOONSHOT_API_KEY"]
   :xiaomi ["XIAOMI_API_KEY"]
   :xiaomi-token-plan-cn ["XIAOMI_TOKEN_PLAN_CN_API_KEY"]
   :xiaomi-token-plan-ams ["XIAOMI_TOKEN_PLAN_AMS_API_KEY"]
   :xiaomi-token-plan-sgp ["XIAOMI_TOKEN_PLAN_SGP_API_KEY"]
   :qwen-token-plan ["QWEN_TOKEN_PLAN_API_KEY"]
   :qwen-token-plan-cn ["QWEN_TOKEN_PLAN_CN_API_KEY"]
   :qwen-token-plan-individual ["QWEN_TOKEN_PLAN_API_KEY"]
   :minimax ["MINIMAX_API_KEY"]
   :minimax-cn ["MINIMAX_CN_API_KEY"]
   :nvidia ["NVIDIA_API_KEY"]
   :openrouter ["OPENROUTER_API_KEY"]
   :fireworks ["FIREWORKS_API_KEY"]
   :vercel-ai-gateway ["AI_GATEWAY_API_KEY"]
   :open-design ["OPEN_DESIGN_API_KEY"]
   :zai ["ZAI_API_KEY"]
   :zai-coding-cn ["ZAI_CODING_CN_API_KEY"]
   :together ["TOGETHER_API_KEY"]
   :baseten ["BASETEN_API_KEY"]
   :ant-ling ["ANT_LING_API_KEY"]
   :kimi-coding ["KIMI_API_KEY"]
   :cloudflare-workers-ai ["CLOUDFLARE_API_KEY" "CLOUDFLARE_ACCOUNT_ID"]
   :cloudflare-ai-gateway ["CLOUDFLARE_API_KEY" "CLOUDFLARE_ACCOUNT_ID"
                           "CLOUDFLARE_GATEWAY_ID"]
   :mistral ["MISTRAL_API_KEY"]
   :google-vertex ["GOOGLE_CLOUD_API_KEY"]
   ;; amazon-bedrock has no api-key env var (pi getApiKeyEnvVars): auth is
   ;; ambient — AWS keys/profile/bearer token/role vars (ambient-configured?)
   :amazon-bedrock []})

(defn provider-env-vars
  "Env var names for a provider, in pi's order (empty vector when unknown)."
  [provider]
  (get env-vars-by-provider provider []))

(defn anthropic-auth-token
  "The ANTHROPIC_AUTH_TOKEN value for the :anthropic provider (nil otherwise)
   — the Authorization: Bearer credential path (pi anthropic provider
   resolve: auth-token → auth.headers). AUTH_TOKEN is skipped by
   resolve-api-key, so requests that use it must branch on this instead."
  [provider]
  (when (= :anthropic provider)
    (getenv "ANTHROPIC_AUTH_TOKEN")))

(defn- api-key-env-vars
  "Env vars that provide an x-api-key: for :anthropic, the trio minus
   ANTHROPIC_AUTH_TOKEN (pi getEnvApiKey: first env key ≠ AUTH_TOKEN — the
   token travels as Authorization: Bearer instead)."
  [provider]
  (if (= :anthropic provider)
    (remove #(= "ANTHROPIC_AUTH_TOKEN" %) (provider-env-vars provider))
    (provider-env-vars provider)))

;; ─── auth.edn state ────────────────────────────────────────────────────────

(def ^:private default-auth {})

(defonce ^:private auth-atom (atom default-auth))

;; The agent dir this namespace's store lives in. Set by load-auth! — the host
;; names the root (`kmet.app.config/get-agent-dir`); this library never resolves
;; one. The writers below persist to it, so they need it to have been loaded.
(defonce ^:private auth-root (atom nil))

(defn agent-dir-path
  "SEGMENTS under the host's agent dir — the state root kmet.ai reads and
   writes; the host names it (`kmet.app.config/get-agent-dir`), the library
   invents none. Throws on a nil AGENT-DIR."
  [agent-dir & segments]
  (when-not agent-dir
    (throw (ex-info (str "kmet.ai: the agent dir is required —"
                         " pass the host's (kmet.app.config/get-agent-dir)")
                    {:type :kmet.ai/no-agent-dir})))
  (str (apply fs/path agent-dir segments)))

(defn auth-file-path
  "Global auth file (<agent-dir>/auth.edn) under the host's agent dir."
  [agent-dir]
  (agent-dir-path agent-dir "auth.edn"))

(defn- store-path
  "The auth file the loaded store lives in; throws when no `load-auth!` ran,
   so a write can never invent a path."
  []
  (if-let [root @auth-root]
    (auth-file-path root)
    (throw (ex-info "kmet.ai.auth: no auth file — call (load-auth! <agent-dir>) first"
                    {:type :kmet.ai/no-auth-root}))))

(defn valid-credential?
  "Validate an auth.edn entry (pi auth-storage parse: non-object entries
   throw): the api-key shape {:key string?} (key optional — pi
   ApiKeyCredential.key is optional) or the oauth shape {:type :oauth with
   string :access/:refresh and a finite number :expires}. Non-map entries
   are invalid — a stored entry is always a map."
  [credential]
  (if (not (map? credential))
    false
    (let [oauth-shape? (or (contains? credential :type)
                           (contains? credential :access)
                           (contains? credential :refresh)
                           (contains? credential :expires))]
      (if oauth-shape?
        (and (= :oauth (:type credential))
             (string? (:access credential))
             (string? (:refresh credential))
             (number? (:expires credential))
             (let [x (double (:expires credential))]
               (and (not (Double/isNaN x))
                    (not (Double/isInfinite x)))))
        (or (nil? (:key credential)) (string? (:key credential)))))))

(defn load-auth!
  "Load <agent-dir>/auth.edn into the auth atom and remember AGENT-DIR as the
   store's root (the writers persist to it); returns the auth map. Called at
   startup (config/load-config); /login and /logout refresh the atom directly
   after their own write. AGENT-DIR is the host's (cfg/get-agent-dir); it is
   validated before it is recorded, so a rejected root leaves the store as it
   was."
  [agent-dir]
  (let [path (auth-file-path agent-dir)
        auth (cred/read-edn-map path valid-credential?)]
    (reset! auth-root (str agent-dir))
    (reset! auth-atom (or auth {}))
    (or auth {})))

(defn get-credentials
  "The current auth map (auth.edn content, as loaded)."
  []
  @auth-atom)

(defn- update-auth!
  "Apply F to the current auth.edn content and persist the result under the
   file lock — read-modify-write is serialized, so concurrent writers can't
   lose each other's updates (pi: SettingsStorage.withLock)."
  [f]
  (let [updated (cred/update-edn-map! (store-path) f valid-credential?)]
    (reset! auth-atom updated)
    updated))

(defn set-credential!
  "Store an API key for PROVIDER in auth.edn and refresh the auth atom.
   Returns the new auth map."
  [provider key]
  (let [auth (update-auth! #(assoc % provider {:key key}))]
    (reset! auth-atom auth)
    auth))

(defn stored-credential
  "The raw auth.edn entry for a provider — the api-key shape {:key ...} or
   the oauth shape {:type :oauth ...} — or nil."
  [provider]
  (get @auth-atom provider))

(defn stored-oauth-credential
  "The stored oauth credential map for a provider, or nil."
  [provider]
  (let [c (stored-credential provider)]
    (when (and (map? c) (= :oauth (:type c))) c)))

(defn set-oauth-credential!
  "Store an OAuth credential (map with :type :oauth, string :access/:refresh,
   finite number :expires — pi OAuthCredential) for PROVIDER in auth.edn and
   refresh the auth atom. Throws on an invalid shape. Returns the new auth
   map."
  [provider credential]
  (when-not (valid-credential? credential)
    (throw (ex-info (str "Invalid OAuth credential for " (name provider))
                    {:type :oauth-credential-invalid})))
  (update-auth! #(assoc % provider credential)))

(defn remove-credential!
  "Remove PROVIDER's auth.edn entry (no-op when absent) and refresh the auth
   atom. Returns the new auth map."
  [provider]
  (update-auth! #(dissoc % provider)))

;; ─── Resolution (pi: credential-store resolveApiKey) ──────────────────────

(defonce ^:private config-key-source (slots/make-slot))

(defn set-config-key-source!
  "Register the provider → raw models.edn/extension :api-key config value
   source (set by models/load-catalogs!; nil returns no configured
   key). Kept behind a hook so auth stays dependency-free of the registry
   (no require cycle)."
  [f]
  (slots/set-slot! config-key-source f))

(defn config-key-source-installed?
  "True when a config-key source is registered (models/load-catalogs! or
   extension registration)."
  []
  (some? @config-key-source))

(defn- configured-api-key
  "Raw models.edn/extension :api-key config value for a provider (nil when
   none configured)."
  [provider]
  (slots/apply-slot config-key-source provider))

;; ─── OAuth (Phase 10) ─────────────────────────────────────────────────────
;; The OAuthAuth record for a provider lives on the Provider record; auth
;; reads it through a hook (like config-key-source) to stay dependency-free
;; of the registry.

(defonce ^:private oauth-source (slots/make-slot))

(defn set-oauth-source!
  "Register the provider → OAuthAuth record source (set by
   models/load-catalogs!; nil returns no oauth). Kept behind a hook so auth
   stays dependency-free of the registry."
  [f]
  (slots/set-slot! oauth-source f))

(defn- provider-oauth
  "The OAuthAuth record for a provider, or nil."
  [provider]
  (slots/apply-slot oauth-source provider))

(def ^:private oauth-min-validity-ms
  "Refresh when the token has less than this much validity left (pi
   DEFAULT_OAUTH_MINIMUM_VALIDITY_MS = 5 min)."
  (* 5 60 1000))

(defn- oauth-fresh?
  "True when an oauth credential needs no refresh (pi expiresSoon: now +
   5 min < expires)."
  [credential]
  (and (number? (:expires credential))
       (< (+ (System/currentTimeMillis) oauth-min-validity-ms)
          (:expires credential))))

(defonce ^:private credential-op-tails (atom {}))

(defn run-credential-op!
  "Serialize credential ops per provider (pi ModelRuntime
   enqueueCredentialOperation): TASK runs after previously queued ops for
   PROVIDER-ID settle. Returns a future delivering TASK's result — deref
   rethrows on failure; the stored tail swallows errors so the chain
   survives a failed op. Used by the oauth refresh path (double-checked
   locking) and available to extensions for credential mutations."
  [provider-id task]
  (let [prev (get @credential-op-tails provider-id)
        op (future
             (when prev @prev)
             (task))]
    (swap! credential-op-tails assoc provider-id
           (future (try @op (catch Exception _ nil))))
    op))

(defn- resolve-oauth-auth
  "Resolved request auth from a stored oauth credential (pi
   resolveStoredOAuth): {:api-key access :base-url str?} without a refresh
   when the token is fresh; otherwise refresh under the per-provider lock
   (double-checked: another request may have rotated it meanwhile), persist
   the rotated credential, and derive auth from it. nil when the provider
   has no stored oauth credential or no registered OAuthAuth. A refresh
   failure resolves nil (the request reports the standard no-auth error; pi
   surfaces 'OAuth refresh failed' as a stream error)."
  [provider-id]
  (when-let [cred (stored-oauth-credential provider-id)]
    (when-let [oauth (provider-oauth provider-id)]
      (if (oauth-fresh? cred)
        ((:to-auth oauth) cred)
        (let [op (run-credential-op!
                  provider-id
                  (fn []
                    (let [current (stored-oauth-credential provider-id)]
                      (if (oauth-fresh? current)
                        ((:to-auth oauth) current)
                        (let [rotated ((:refresh oauth) current (atom false))]
                          (set-oauth-credential! provider-id rotated)
                          ((:to-auth oauth) rotated))))))]
          (try @op
               (catch Exception _ nil)))))))

(defn resolve-provider-auth
  "Resolved request auth for a provider (pi composeApiKeyAuth.resolve +
   resolveStoredOAuth + the anthropic provider resolve): {:api-key str} for
   the x-api-key paths, {:bearer str} when ANTHROPIC_AUTH_TOKEN wins
   (anthropic), or {:api-key str :base-url str} for an oauth credential (the
   base-url is the per-credential endpoint, e.g. Copilot's proxy-ep). Exact
   pi order: stored oauth credential (refreshed when expiring; a stored
   oauth credential without a registered OAuthAuth resolves nil and blocks
   the env fallback) → auth.edn api-key → models.edn/extension configured
   key (a configured key present but unresolvable blocks the rest, pi
   resolveConfigValueOrThrow) → ANTHROPIC_AUTH_TOKEN → oauth/api env vars
   (api-key-env-vars, AUTH_TOKEN skipped). nil when nothing is configured."
  [provider]
  (or (resolve-oauth-auth provider)
      (when-let [k (get-in @auth-atom [provider :key])]
        {:api-key k})
      ;; pi: a stored credential owns the provider — no env fallback when the
      ;; stored oauth has no registered auth handler
      (when-not (stored-oauth-credential provider)
        (let [raw (configured-api-key provider)]
          (if raw
            (when-let [k (dynamic-value/resolve-config-value raw)]
              {:api-key k})
            (or (when-let [t (anthropic-auth-token provider)]
                  {:bearer t})
                (when-let [k (some getenv (api-key-env-vars provider))]
                  {:api-key k})))))))

(defn resolve-api-key
  "API key for a provider — the api-key view of resolve-provider-auth
   (nil when the anthropic AUTH_TOKEN bearer provides auth instead)."
  [provider]
  (:api-key (resolve-provider-auth provider)))

(defn env-key-present?
  "True when any of the provider's env vars is present in the process env
   (pi findEnvKeys — feeds get-provider-auth-status)."
  [provider]
  (boolean (some getenv (provider-env-vars provider))))

(defn ambient-configured?
  "True when a provider that resolves its own ambient auth (no api key) is
   configured. google-vertex: ADC credentials file + project + location (pi
   getEnvApiKey hasVertexAdcCredentials + resolveProject/Location).
   amazon-bedrock: any AWS credential source (profile, access keys, bearer
   token, ECS/IRSA vars) or AWS_BEDROCK_SKIP_AUTH=1 (pi getEnvApiKey
   amazon-bedrock)."
  [provider]
  (case provider
    :google-vertex (and (google-adc/configured?)
                        (or (getenv "GOOGLE_CLOUD_PROJECT") (getenv "GCLOUD_PROJECT"))
                        (getenv "GOOGLE_CLOUD_LOCATION"))
    :amazon-bedrock (or (aws-sigv4/ambient-configured?)
                        (= "1" (getenv "AWS_BEDROCK_SKIP_AUTH")))
    false))

(defn configured?
  "True when the provider has a credential: auth.edn entry — an api-key
   always, an oauth credential only when the provider has a registered
   OAuthAuth (pi checkAuth: a stored oauth credential without auth.oauth is
   not configured; the stored credential blocks env discovery) — a
   configured models.edn/extension api-key (literal or !command always;
   $ENV needs the var present), or any env var present (feeds
   models/get-available). Never refreshes or executes commands."
  [provider]
  (let [stored (stored-credential provider)]
    (boolean
     (cond
       (stored-oauth-credential provider) (some? (provider-oauth provider))
       stored true
       :else (or (when-let [raw (configured-api-key provider)]
                   (dynamic-value/is-config-value-configured? raw))
                 (env-key-present? provider)
                 (ambient-configured? provider))))))

(defn provider-auth-status
  "Auth status for the /login //logout selectors (pi
   getProviderAuthStatus): {:configured? true :type :oauth|:api-key
   :source string :label string?} — :label carries the env-var names for
   environment-sourced credentials (the selector renders \"✓ env: VARS\").
   Order: stored credential (\"stored\") → models.edn/extension configured
   key (!command → \"models_json_command\", $ENV → \"environment\" + var
   names, literal → \"models_json_key\") → env vars (\"environment\" + the
   first present var name) → ambient (google-vertex ADC / bedrock AWS,
   \"ambient\"). {:configured? false} when nothing is configured. Never
   refreshes or executes commands."
  [provider]
  (let [api-key-status (fn [source label]
                         {:configured? true :type :api-key
                          :source source :label label})
        configured (configured-api-key provider)
        stored (stored-credential provider)]
    (cond
      (stored-oauth-credential provider)
      {:configured? true :type :oauth :source "stored"}

      (and (map? stored) (not= :oauth (:type stored)))
      (api-key-status "stored" nil)

      (some? configured)
      (cond
        (dynamic-value/is-command-config-value? configured)
        (api-key-status "models_json_command" nil)

        (seq (dynamic-value/get-config-value-env-var-names configured))
        (if (dynamic-value/is-config-value-configured? configured)
          (api-key-status "environment"
                          (str/join ", "
                                    (dynamic-value/get-config-value-env-var-names configured)))
          {:configured? false})

        :else (api-key-status "models_json_key" nil))

      ;; env vars in pi order; the label is the first present var (pi
      ;; resolve's `source: envVar` — a single name, not the whole list)
      (env-key-present? provider)
      (api-key-status "environment"
                      (first (filter #(getenv %) (provider-env-vars provider))))

      (ambient-configured? provider)
      (api-key-status "ambient" nil)

      :else {:configured? false})))
