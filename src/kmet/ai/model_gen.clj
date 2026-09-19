;; Provider catalog generator (pi: packages/ai/scripts/generate-models.ts,
;; ported to kmet's 40 providers) — fetches models.dev + the live provider
;; catalogs and writes the per-provider EDN files + manifest. The pipeline is
;; target-agnostic: `bb generate-models` (via kmet.tasks.generate-models, a
;; shim over this namespace) regenerates the committed src/kmet/ai/model_data;
;; `kmet --generate-models` runs the same pipeline into the user-level cache
;; (~/.kmet/agent/models-cache, kmet.ai.models/default-models-cache-dir) without
;; touching the repo.
;;
;; Radius is deliberately dropped (pi-messages API + dynamic gateway
;; catalog, both out of kmet's scope); every other pi provider is
;; generated. The generated EDN + manifest.edn are committed; the offline
;; half (validate-committed!) also runs as test/kmet/ai/test_model_data.clj
;; so drift is caught without network.
;;
;; Run via: bb generate-models   (network, committed catalogs)
;;          kmet --generate-models (network, user-level cache)
;; Check via: bb check-model-data (offline)

(ns kmet.ai.model-gen
  (:require [babashka.fs :as fs]
            [kmet.libs.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.libs.edn-writer :as edn-w]
            [kmet.libs.http :as http]))

;; ─── Provider metadata (kmet decisions, not derivable from models.dev) ─────

(def providers
  "Target providers, in catalog file order: id → info block (name, env vars
   in pi env-api-keys order, catalog default model)."
  (array-map
   :opencode-go {:name "OpenCode Go"
                 :env-vars ["OPENCODE_API_KEY"]
                 :default-model "deepseek-v4-flash"}
   :opencode {:name "OpenCode (Zen)"
              :env-vars ["OPENCODE_API_KEY"]
              :default-model "kimi-k2.6"}
   :deepseek {:name "DeepSeek"
              :env-vars ["DEEPSEEK_API_KEY"]
              :default-model "deepseek-v4-pro"}
   :github-copilot {:name "GitHub Copilot"
                    :env-vars ["COPILOT_GITHUB_TOKEN"]
                    :default-model "claude-sonnet-4.5"}
   :openai {:name "OpenAI"
            :env-vars ["OPENAI_API_KEY"]
            :default-model "gpt-5.5"}
   :xai {:name "xAI"
         :env-vars ["XAI_API_KEY"]
         :default-model "grok-4.5"}
   :openai-codex {:name "OpenAI Codex"
                  :env-vars []            ;; OAuth (ChatGPT) — no env var
                  :default-model "gpt-5.5"}
   :azure-openai-responses {:name "Azure OpenAI"
                            :env-vars ["AZURE_OPENAI_API_KEY"]
                            :default-model "gpt-5.4"}
   :anthropic {:name "Anthropic"
               :env-vars ["ANTHROPIC_AUTH_TOKEN" "ANTHROPIC_OAUTH_TOKEN" "ANTHROPIC_API_KEY"]
               :default-model "claude-opus-4-8"}
   :google {:name "Google"
            :env-vars ["GEMINI_API_KEY"]
            :default-model "gemini-3.1-pro-preview"}
   :groq {:name "Groq"
          :env-vars ["GROQ_API_KEY"]
          :default-model "openai/gpt-oss-120b"}
   :cerebras {:name "Cerebras"
              :env-vars ["CEREBRAS_API_KEY"]
              :default-model "zai-glm-4.7"}
   :huggingface {:name "Hugging Face"
                 :env-vars ["HF_TOKEN"]
                 :default-model "moonshotai/Kimi-K2.6"}
   :moonshotai {:name "Moonshot AI"
                :env-vars ["MOONSHOT_API_KEY"]
                :default-model "kimi-k2.6"}
   :moonshotai-cn {:name "Moonshot AI (CN)"
                   :env-vars ["MOONSHOT_API_KEY"]
                   :default-model "kimi-k2.6"}
   :xiaomi {:name "Xiaomi MiMo"
            :env-vars ["XIAOMI_API_KEY"]
            :default-model "mimo-v2.5-pro"}
   :xiaomi-token-plan-cn {:name "Xiaomi MiMo Token Plan (CN)"
                          :env-vars ["XIAOMI_TOKEN_PLAN_CN_API_KEY"]
                          :default-model "mimo-v2.5-pro"}
   :xiaomi-token-plan-ams {:name "Xiaomi MiMo Token Plan (AMS)"
                           :env-vars ["XIAOMI_TOKEN_PLAN_AMS_API_KEY"]
                           :default-model "mimo-v2.5-pro"}
   :xiaomi-token-plan-sgp {:name "Xiaomi MiMo Token Plan (SGP)"
                           :env-vars ["XIAOMI_TOKEN_PLAN_SGP_API_KEY"]
                           :default-model "mimo-v2.5-pro"}
   :qwen-token-plan {:name "Qwen Token Plan"
                     :env-vars ["QWEN_TOKEN_PLAN_API_KEY"]
                     :default-model "qwen3.7-max"}
   :qwen-token-plan-cn {:name "Qwen Token Plan (CN)"
                        :env-vars ["QWEN_TOKEN_PLAN_CN_API_KEY"]
                        :default-model "qwen3.7-max"}
   :qwen-token-plan-individual {:name "Qwen Token Plan (Individual)"
                                :env-vars ["QWEN_TOKEN_PLAN_API_KEY"]
                                :default-model "qwen3.8-max"}
   :minimax {:name "MiniMax"
             :env-vars ["MINIMAX_API_KEY"]
             :default-model "MiniMax-M2.7"}
   :minimax-cn {:name "MiniMax (CN)"
                :env-vars ["MINIMAX_CN_API_KEY"]
                :default-model "MiniMax-M2.7"}
   :nvidia {:name "NVIDIA NIM"
            :env-vars ["NVIDIA_API_KEY"]
            :default-model "nvidia/nemotron-3-super-120b-a12b"}
   :openrouter {:name "OpenRouter"
                :env-vars ["OPENROUTER_API_KEY"]
                :default-model "moonshotai/kimi-k2.6"}
   :fireworks {:name "Fireworks AI"
               :env-vars ["FIREWORKS_API_KEY"]
               :default-model "accounts/fireworks/models/kimi-k2p6"}
   :vercel-ai-gateway {:name "Vercel AI Gateway"
                       :env-vars ["AI_GATEWAY_API_KEY"]
                       :default-model "zai/glm-5.1"}
   :zai {:name "Z.AI"
         :env-vars ["ZAI_API_KEY"]
         :default-model "glm-5.2"}
   :zai-coding-cn {:name "Z.AI (CN)"
                   :env-vars ["ZAI_CODING_CN_API_KEY"]
                   :default-model "glm-5.2"}
   :together {:name "Together AI"
              :env-vars ["TOGETHER_API_KEY"]
              :default-model "moonshotai/Kimi-K2.6"}
   :baseten {:name "Baseten"
             :env-vars ["BASETEN_API_KEY"]
             :default-model "zai-org/GLM-5.2"}
   :ant-ling {:name "Ant Ling"
              :env-vars ["ANT_LING_API_KEY"]
              :default-model "Ring-2.6-1T"}
   :kimi-coding {:name "Kimi For Coding"
                 :env-vars ["KIMI_API_KEY"]
                 :default-model "kimi-for-coding"}
   :cloudflare-workers-ai {:name "Cloudflare Workers AI"
                           :env-vars ["CLOUDFLARE_API_KEY" "CLOUDFLARE_ACCOUNT_ID"]
                           :default-model "@cf/moonshotai/kimi-k2.6"}
   :cloudflare-ai-gateway {:name "Cloudflare AI Gateway"
                           :env-vars ["CLOUDFLARE_API_KEY" "CLOUDFLARE_ACCOUNT_ID"
                                      "CLOUDFLARE_GATEWAY_ID"]
                           :default-model "workers-ai/@cf/moonshotai/kimi-k2.6"}
   :commandcode {:name "Command Code"
                 :env-vars ["CMD_API_KEY"]
                 :default-model "deepseek/deepseek-v4-flash"}
   :mistral {:name "Mistral"
             :env-vars ["MISTRAL_API_KEY"]
             :default-model "devstral-medium-latest"}
   :google-vertex {:name "Google Vertex AI"
                   :env-vars ["GOOGLE_CLOUD_API_KEY"]
                   :default-model "gemini-3.1-pro-preview"}
   :amazon-bedrock {:name "Amazon Bedrock"
                    :env-vars []          ;; ambient AWS credentials (no api-key var)
                    :default-model "us.anthropic.claude-opus-4-6-v1"}))

(def opencode-variants
  "pi opencodeVariants: models.dev key → kmet provider id + API base path."
  [{:provider :opencode :key "opencode" :base-path "https://opencode.ai/zen"}
   {:provider :opencode-go :key "opencode-go" :base-path "https://opencode.ai/zen/go"}])

(def copilot-static-headers
  "pi COPILOT_STATIC_HEADERS."
  (array-map "User-Agent" "GitHubCopilotChat/0.35.0"
             "Editor-Version" "vscode/1.107.0"
             "Editor-Plugin-Version" "copilot-chat/0.35.0"
             "Copilot-Integration-Id" "vscode-chat"))

(def deepseek-compat
  {:requires-reasoning-content-on-assistant-messages true
   :thinking-format :deepseek})

;; ─── OpenAI / xAI constants (pi generate-models.ts, ported verbatim) ───────

(def ^:private openai-long-context-input-threshold 272000)

(def ^:private models-dev-openai-unsupported-model-ids
  "models.dev lists this alias, but it is not accepted by OpenAI APIs."
  #{"gpt-5.6"})

(def ^:private openai-tool-search-model-ids
  #{"gpt-5.4" "gpt-5.4-mini" "gpt-5.4-pro" "gpt-5.5"
    "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna" "gpt-6-astra"})

;; Public OpenAI documents additional_tools for applications that load tools
;; outside the normal tool-search flow; the openai provider supports both.
(def ^:private openai-additional-tools-model-ids openai-tool-search-model-ids)

(def ^:private openai-short-context-capped-model-ids
  #{"gpt-5.4" "gpt-5.5" "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"})

(def ^:private openai-long-context-pricing-model-ids
  #{"gpt-5.4" "gpt-5.4-pro" "gpt-5.5" "gpt-5.5-pro"
    "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna" "gpt-6-astra"})

;; OpenAI reduced GPT-5.6 Terra and Luna prices on 2026-07-30. Keep these
;; authoritative values until models.dev and passthrough catalogs catch up.
;; https://developers.openai.com/api/docs/pricing
(def ^:private openai-gpt-56-standard-costs
  {"gpt-5.6-luna" {:input 0.2 :output 1.2 :cache-read 0.02 :cache-write 0.25}
   "gpt-5.6-terra" {:input 2 :output 12 :cache-read 0.2 :cache-write 2.5}})

;; Models that accept `reasoning: {effort: "none"}` to disable thinking;
;; every other gpt-5* responses model pins :off to null (always-thinking).
(def ^:private openai-responses-none-reasoning-models
  #{"gpt-5.1" "gpt-5.2" "gpt-5.3-codex" "gpt-5.4" "gpt-5.4-mini"
    "gpt-5.4-nano" "gpt-5.5" "gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"})

(def ^:private xai-responses-model-id "grok-4.5")

(def ^:private xai-builtin-excluded-model-ids
  #{"grok-3" "grok-3-fast" "grok-4.20-0309-non-reasoning"
    "grok-4.20-0309-reasoning" "grok-code-fast-1"})

(def ^:private xai-responses-effort-level-map
  {:off nil :minimal nil})

(def ^:private xai-responses-compat
  {:supports-long-cache-retention false})

;; Copilot models served with an extended 1M context (pi override).
(def ^:private github-copilot-extended-context-models
  #{"claude-fable-5" "claude-opus-4.6" "claude-opus-4.7" "claude-opus-4.8"
    "claude-opus-5" "claude-sonnet-4.6" "claude-sonnet-5"
    "gpt-5.3-codex" "gpt-5.4" "gpt-5.5"})

(def deepseek-models
  "Hardcoded DeepSeek catalog — models.dev lags the DeepSeek API, pi carries
   these as deepseekModels. Ported verbatim; deepseek is the one provider pi
   does not process from models.dev. `deepseek-flash` is the canonical V4.1
   Flash id (pi 12f59336a replaced the retired deepseek-v4-flash /
   deepseek-v4-flash-vision-exp aliases with it). Pricing is standard —
   DeepSeek's time-based off-peak rates are not representable in the cost
   schema."
  [(array-map :id "deepseek-flash" :name "DeepSeek V4.1 Flash"
              :provider :deepseek :api :openai-completions
              :base-url "https://api.deepseek.com"
              :reasoning true :input [:text :image]
              ;; pi sets this explicitly: the metadata rule keys on ids
              ;; containing "deepseek-v4", which "deepseek-flash" does not
              :thinking-level-map (array-map :minimal nil :low "low" :medium nil
                                             :high "high" :max "max")
              :cost (array-map :input 0.3 :output 1.2 :cache-read 0.006 :cache-write 0)
              :context-window 1000000 :max-tokens 384000
              :compat deepseek-compat)
   (array-map :id "deepseek-v4-pro" :name "DeepSeek V4 Pro"
              :provider :deepseek :api :openai-completions
              :base-url "https://api.deepseek.com"
              :reasoning true :input [:text]
              :cost (array-map :input 1.32 :output 3.96 :cache-read 0.044 :cache-write 0)
              :context-window 1000000 :max-tokens 384000
              :compat deepseek-compat)])

(def data-dir
  "Committed catalog directory, relative to the project root (bb tasks run
   from there, matching kmet.app.models/model-data-dir)."
  "src/kmet/ai/model_data")

;; ─── Small helpers ─────────────────────────────────────────────────────────

(defn- sha256-hex
  "sha256 hex digest (pi createHash('sha256'))."
  [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest md (.getBytes s "UTF-8"))))))

(defn- generated-at
  "UTC timestamp, second precision (ISO-8601)."
  []
  (str (subs (str (java.time.Instant/now)) 0 19) "Z"))

(defn- input-modalities
  "pi: modalities.input includes image ? [text image] : [text] (kmet ignores
   pdf/audio/video modalities)."
  [m]
  (if (some #{"image"} (get-in m ["modalities" "input"]))
    [:text :image]
    [:text]))

(defn- cost-map
  "pi getModelsDevCost: the flat 4-field form plus context tiers
   (tier.tier.type === 'context' with a size → inputTokensAbove; each tier
   supplies a complete alternate rate set for the whole request)."
  [m]
  (let [tiers (->> (get-in m ["cost" "tiers"])
                   (keep (fn [tier]
                           (let [ctx (get tier "tier")]
                             (when (and (= "context" (get ctx "type"))
                                        (some? (get ctx "size")))
                               (array-map :input-tokens-above (long (get ctx "size"))
                                          :input (or (get tier "input") 0)
                                          :output (or (get tier "output") 0)
                                          :cache-read (or (get tier "cache_read") 0)
                                          :cache-write (or (get tier "cache_write") 0))))))
                   vec)]
    (cond-> (array-map :input (or (get-in m ["cost" "input"]) 0)
                       :output (or (get-in m ["cost" "output"]) 0)
                       :cache-read (or (get-in m ["cost" "cache_read"]) 0)
                       :cache-write (or (get-in m ["cost" "cache_write"]) 0))
      (seq tiers) (assoc :tiers tiers))))

(defn- round-cost
  "pi roundCost — toFixed(6)."
  [v]
  (Double/parseDouble (format "%.6f" (double v))))

(defn- with-openai-long-context-pricing
  "pi withOpenAiLongContextPricing: keep the short-context tier as the base
   rates, add the long-context tier at the 272000-token threshold."
  [cost]
  (assoc cost
         :tiers [(array-map :input-tokens-above openai-long-context-input-threshold
                            :input (round-cost (* 2 (or (:input cost) 0)))
                            :output (round-cost (* 1.5 (or (:output cost) 0)))
                            :cache-read (round-cost (* 2 (or (:cache-read cost) 0)))
                            :cache-write (round-cost (* 2 (or (:cache-write cost) 0))))]))

(defn- model-map
  "One Model in canonical EDN field order (pi Model shape, kmet key names).
   OPT: :compat (ordered), :headers (static headers), :cost — a fallback
   rate map used per-field when models.dev reports none (pi: m.cost?.x ||
   fallback)."
  [provider api base-url m mid {:keys [compat headers context-default max-default cost
                                       thinking-level-map]}]
  (cond-> (array-map
           :id mid
           :name (or (get m "name") mid)
           :provider provider
           :api api
           :base-url base-url
           :reasoning (true? (get m "reasoning"))
           :input (input-modalities m)
           :cost (if cost
                   (array-map :input (let [v (get-in m ["cost" "input"])]
                                       (if (pos? (or v 0)) v (or (:input cost) 0)))
                              :output (let [v (get-in m ["cost" "output"])]
                                        (if (pos? (or v 0)) v (or (:output cost) 0)))
                              :cache-read (let [v (get-in m ["cost" "cache_read"])]
                                            (if (pos? (or v 0)) v (or (:cache-read cost) 0)))
                              :cache-write (let [v (get-in m ["cost" "cache_write"])]
                                             (if (pos? (or v 0)) v (or (:cache-write cost) 0))))
                   (cost-map m))
           :context-window (or (get-in m ["limit" "context"]) context-default 4096)
           :max-tokens (or (get-in m ["limit" "output"]) max-default 4096))
    (seq compat) (assoc :compat compat)
    thinking-level-map (assoc :thinking-level-map thinking-level-map)
    headers (assoc :headers headers)))

(declare apply-thinking-maps)

;; ─── opencode / opencode-go (pi opencodeVariants) ─────────────────────────

(defn- opencode-model
  "One models.dev opencode variant model, or nil when it needs a wire API
   kmet does not implement yet (@ai-sdk/openai → openai-responses; skipped
   and logged by the caller)."
  [variant mid m]
  (let [npm (get-in m ["provider" "npm"])
        base-path (:base-path variant)
        api (cond
              (= npm "@ai-sdk/openai") :openai-responses
              (= npm "@ai-sdk/anthropic") :anthropic-messages
              (= npm "@ai-sdk/google") :google-generative-ai
              :else :openai-completions)
        base (cond
               (nil? api) nil
               (= api :anthropic-messages) base-path
               :else (str base-path "/v1"))
        ;; Known npm mismatches: models.dev reports these as @ai-sdk/anthropic
        ;; but OpenCode Go serves them through the OpenAI-compatible path.
        go-override? (and (= :opencode-go (:provider variant))
                          (contains? #{"minimax-m2.7" "qwen3.5-plus" "qwen3.6-plus"} mid))
        api (if go-override? :openai-completions api)
        base (if go-override? (str base-path "/v1") base)
        compat (cond-> {}
                 (= api :openai-responses)
                 ;; opencode zen's responses path has no session affinity
                 ;; (pi: sessionAffinityFormat "openai-nosession")
                 (assoc :session-affinity-format :openai-nosession)
                 (and (= :opencode (:provider variant)) (= mid "grok-build-0.1"))
                 (assoc :supports-reasoning-effort false)
                 (and (#{:opencode :opencode-go} (:provider variant)) (= mid "kimi-k2.6"))
                 ;; OpenCode Kimi K2.6 accepts Anthropic-style thinking objects
                 ;; and rejects string thinking values / combined reasoning_effort.
                 (assoc :thinking-format :deepseek :supports-reasoning-effort false)
                 (and (= :opencode-go (:provider variant))
                      (contains? #{"qwen3.5-plus" "qwen3.6-plus"} mid))
                 ;; Qwen/DashScope uses enable_thinking at the top level.
                 (assoc :thinking-format :qwen)
                 (= api :openai-completions)
                 (assoc :max-tokens-field :max-tokens))]
    (apply-thinking-maps (model-map (:provider variant) api base m mid {:compat compat}) m)))

(defn- process-opencode
  [data]
  (doall
   (for [variant opencode-variants
         [mid m] (or (get-in data [(:key variant) "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (not= "deprecated" (get m "status"))
                    ;; pi drops this alias from the opencode variants
                    (not= mid "gpt-5.3-codex-spark"))]
     (opencode-model variant mid m))))

;; ─── github-copilot ────────────────────────────────────────────────────────

(defn- process-copilot
  [data]
  (doall
   (for [[mid m] (or (get-in data ["github-copilot" "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (not= "deprecated" (get m "status")))]
     (let [claude? (boolean (re-find #"^claude-(haiku|sonnet|opus)-[45]([.\-]|$)" mid))
           needs-responses? (or (= mid "grok-4.5")
                                (str/starts-with? mid "gpt-5")
                                (str/starts-with? mid "oswe")
                                (str/starts-with? mid "mai-"))]
       (cond
         claude? (apply-thinking-maps
                  (model-map :github-copilot :anthropic-messages
                             "https://api.individual.githubcopilot.com" m mid
                             {:headers copilot-static-headers
                              :context-default 128000 :max-default 8192})
                  m)
         needs-responses? (apply-thinking-maps
                           ;; Grok 4.5 / gpt-5 / oswe / MAI-Code models are only
                           ;; served through the Copilot /responses endpoint
                           ;; (pi: needsResponsesApi). No explicit compat — the
                           ;; strict/tool-search/explicit-cache passes don't
                           ;; apply to copilot (pi), only grammar tools do.
                           (model-map :github-copilot :openai-responses
                                      "https://api.individual.githubcopilot.com" m mid
                                      {:headers copilot-static-headers
                                       :context-default 128000 :max-default 8192})
                           m)
         :else (apply-thinking-maps
                (model-map :github-copilot :openai-completions
                           "https://api.individual.githubcopilot.com" m mid
                           {:headers copilot-static-headers
                            :context-default 128000 :max-default 8192
                            :compat {:supports-store false
                                     :supports-developer-role false
                                     :supports-reasoning-effort false}})
                m))))))

;; ─── openai (pi: all models.dev openai models → :openai-responses) ─────────

(def ^:private openai-base-url "https://api.openai.com/v1")

(defn- process-openai
  [data]
  (doall
   (for [[mid m] (or (get-in data ["openai" "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (not= "deprecated" (get m "status"))
                    (not (contains? models-dev-openai-unsupported-model-ids mid)))]
     (apply-thinking-maps
      (model-map :openai :openai-responses openai-base-url m mid {})
      m))))

;; ─── xai (pi: grok-4.5 → :openai-responses, the rest openai-completions) ──

(def ^:private xai-base-url "https://api.x.ai/v1")

(defn- process-xai
  [data]
  (doall
   (for [[mid m] (or (get-in data ["xai" "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (not= "deprecated" (get m "status"))
                    (not (contains? xai-builtin-excluded-model-ids mid)))]
     (let [responses? (= mid xai-responses-model-id)]
       (apply-thinking-maps
        (model-map :xai (if responses? :openai-responses :openai-completions)
                   xai-base-url m mid
                   {:compat (if responses?
                              xai-responses-compat
                              {:supports-store false
                               :supports-developer-role false
                               :supports-reasoning-effort false})})
        m)))))

;; ─── Missing openai models (pi: added when models.dev lags) ───────────────

(defn- missing-openai-models
  [existing-ids]
  (remove (fn [mm] (contains? existing-ids (:id mm)))
          [(array-map :id "gpt-6-astra" :name "GPT-6 Astra"
                      :provider :openai :api :openai-responses
                      :base-url openai-base-url
                      :reasoning true :input [:text :image]
                      :cost (with-openai-long-context-pricing
                              {:input 10 :output 50 :cache-read 1 :cache-write 12.5})
                      :context-window 1050000
                      :max-tokens 128000)
           (array-map :id "gpt-5.6-sol" :name "GPT-5.6 Sol"
                      :provider :openai :api :openai-responses
                      :base-url openai-base-url
                      :reasoning true :input [:text :image]
                      :cost (with-openai-long-context-pricing
                              {:input 5 :output 30 :cache-read 0.5 :cache-write 6.25})
                      :context-window openai-long-context-input-threshold
                      :max-tokens 128000)
           (array-map :id "gpt-5.6-terra" :name "GPT-5.6 Terra"
                      :provider :openai :api :openai-responses
                      :base-url openai-base-url
                      :reasoning true :input [:text :image]
                      :cost (with-openai-long-context-pricing
                              (get openai-gpt-56-standard-costs "gpt-5.6-terra"))
                      :context-window openai-long-context-input-threshold
                      :max-tokens 128000)
           (array-map :id "gpt-5.6-luna" :name "GPT-5.6 Luna"
                      :provider :openai :api :openai-responses
                      :base-url openai-base-url
                      :reasoning true :input [:text :image]
                      :cost (with-openai-long-context-pricing
                              (get openai-gpt-56-standard-costs "gpt-5.6-luna"))
                      :context-window openai-long-context-input-threshold
                      :max-tokens 128000)
           (array-map :id "gpt-5-chat-latest" :name "GPT-5 Chat Latest"
                      :provider :openai :api :openai-responses
                      :base-url openai-base-url
                      :reasoning false :input [:text :image]
                      :cost {:input 1.25 :output 10 :cache-read 0.125 :cache-write 0}
                      :context-window 128000 :max-tokens 16384)]))

;; ─── openai-codex (pi: hardcoded codexModels — ChatGPT OAuth models; the
;;     list is kept small to avoid aliases) ─────────────────────────────────

(def ^:private codex-base-url "https://chatgpt.com/backend-api")
(def ^:private codex-context 272000)
(def ^:private codex-spark-context 128000)
(def ^:private codex-max-tokens 128000)

(defn- codex-model
  "One hardcoded codex model map (pi codexModels entry)."
  [id name input cost context]
  (array-map :id id :name name
             :provider :openai-codex :api :openai-codex-responses
             :base-url codex-base-url
             :reasoning true :input input
             :cost cost
             :context-window context :max-tokens codex-max-tokens))

(defn- process-codex
  "The hardcoded Codex catalog (pi codexModels; GPT-5.4/5.5/5.6 and GPT-6
   Astra carry the long-context pricing tier; GPT-5.6 uses standard OpenAI
   costs)."
  []
  [(codex-model "gpt-6-astra" "GPT-6 Astra" [:text :image]
                (with-openai-long-context-pricing
                  (array-map :input 10 :output 50 :cache-read 1 :cache-write 12.5))
                codex-context)
   (codex-model "gpt-5.3-codex-spark" "GPT-5.3 Codex Spark" [:text]
                (array-map :input 1.75 :output 14 :cache-read 0.175 :cache-write 0)
                codex-spark-context)
   (codex-model "gpt-5.4" "GPT-5.4" [:text :image]
                (with-openai-long-context-pricing
                  (array-map :input 2.5 :output 15 :cache-read 0.25 :cache-write 0))
                codex-context)
   (codex-model "gpt-5.4-mini" "GPT-5.4 mini" [:text :image]
                (array-map :input 0.75 :output 4.5 :cache-read 0.075 :cache-write 0)
                codex-context)
   (codex-model "gpt-5.5" "GPT-5.5" [:text :image]
                (with-openai-long-context-pricing
                  (array-map :input 5 :output 30 :cache-read 0.5 :cache-write 0))
                codex-context)
   (codex-model "gpt-5.6-luna" "GPT-5.6 Luna" [:text :image]
                (with-openai-long-context-pricing
                  (get openai-gpt-56-standard-costs "gpt-5.6-luna"))
                codex-context)
   (codex-model "gpt-5.6-sol" "GPT-5.6 Sol" [:text :image]
                (with-openai-long-context-pricing
                  (array-map :input 5 :output 30 :cache-read 0.5 :cache-write 6.25))
                codex-context)
   (codex-model "gpt-5.6-terra" "GPT-5.6 Terra" [:text :image]
                (with-openai-long-context-pricing
                  (get openai-gpt-56-standard-costs "gpt-5.6-terra"))
                codex-context)])

;; ─── azure-openai-responses (pi: azureOpenAiModels — the mirror of the
;;     openai responses models; Azure Foundry deploys them with larger
;;     context windows, cost tiers are dropped) ─────────────────────────────

(def ^:private azure-context-window-overrides
  {"gpt-5.4" 1050000 "gpt-5.5" 1050000
   "gpt-5.6-luna" 1050000 "gpt-5.6-sol" 1050000 "gpt-5.6-terra" 1050000})

(defn- process-azure
  "The azure mirror (pi azureOpenAiModels): every openai-responses openai
   model becomes an azure-openai-responses model with an empty base-url
   (the deployment config comes from AZURE_OPENAI_* env vars at request
   time) and the context override; cost keeps the flat rates only."
  [openai-models]
  (for [mm openai-models
        :when (and (= :openai (:provider mm))
                   (= :openai-responses (:api mm)))]
    (assoc mm
           :api :azure-openai-responses
           :provider :azure-openai-responses
           :base-url ""
           :cost (select-keys (:cost mm) [:input :output :cache-read :cache-write])
           :context-window (get azure-context-window-overrides (:id mm)
                                (:context-window mm)))))

(def ^:private thinking-levels
  [:minimal :low :medium :high :xhigh :max])

(defn- effort-thinking-level-map
  "pi getEffortThinkingLevelMap: reasoning_options effort values →
   {level -> wire string | nil}. :off ← \"none\" (nil when unsupported);
   levels without a verified value are nil (unsupported). Returns nil when
   there are no usable effort values."
  [reasoning-options]
  (let [effort-values (into []
                            (comp (filter #(= "effort" (get % "type")))
                                  (mapcat #(get % "values")))
                            reasoning-options)
        supported (set effort-values)]
    (when (and (seq effort-values)
               (or (some supported (map name thinking-levels))
                   (contains? supported "none")))
      (into (array-map :off (if (contains? supported "none") "none" nil))
            (for [level thinking-levels]
              [level (if (contains? supported (name level)) (name level) nil)])))))

(defn- openrouter-thinking-level-map
  "pi getOpenRouterThinkingLevelMap: OpenRouter's live reasoning metadata
   (supported_efforts + mandatory) → {level -> wire string | nil}. Shares
   the models.dev effort conversion; :off is \"none\" unless the model
   mandates reasoning (then nil). Returns nil for optional models without
   effort controls."
  [reasoning]
  (when (map? reasoning)
    (let [supported (get reasoning "supported_efforts")
          mandatory? (true? (get reasoning "mandatory"))]
      (if (seq supported)
        (if-let [tlm (effort-thinking-level-map
                      [{"type" "effort" "values" supported}])]
          (assoc tlm :off (if mandatory? nil "none"))
          (when mandatory? {:off nil}))
        (when mandatory? {:off nil})))))
;; ─── Batch 2: providers on the existing wire APIs (pi loadModelsDevData  ──
;;     sections; A.3 thinking formats — zai/together/baseten/ant-ling — and
;;     kimi-coding's adaptive thinking stay deferred) ───────────────────────

(def ^:private anthropic-base-url "https://api.anthropic.com")
(def ^:private google-base-url "https://generativelanguage.googleapis.com/v1beta")
(def ^:private groq-base-url "https://api.groq.com/openai/v1")
(def ^:private cerebras-base-url "https://api.cerebras.ai/v1")
(def ^:private huggingface-base-url "https://router.huggingface.co/v1")
(def ^:private nvidia-base-url "https://integrate.api.nvidia.com/v1")
(def ^:private nvidia-headers (array-map "NVCF-POLL-SECONDS" "3600"))
(def ^:private nvidia-openai-compat
  (array-map :supports-store false :supports-developer-role false
             :supports-reasoning-effort false :max-tokens-field :max-tokens
             :supports-strict-mode false :supports-long-cache-retention false))
(def ^:private nvidia-nim-unsupported-models
  #{"abacusai/dracarys-llama-3.1-70b-instruct" "bytedance/seed-oss-36b-instruct"
    "deepseek-ai/deepseek-v4-flash" "deepseek-ai/deepseek-v4-pro"
    "google/gemma-2-2b-it" "google/gemma-3n-e2b-it" "google/gemma-3n-e4b-it"
    "google/gemma-4-31b-it" "meta/llama-3.2-1b-instruct"
    "meta/llama-4-maverick-17b-128e-instruct" "microsoft/phi-4-mini-instruct"
    "minimaxai/minimax-m2.7" "mistralai/mistral-nemotron"
    "nvidia/nemotron-mini-4b-instruct" "qwen/qwen3-next-80b-a3b-instruct"
    "qwen/qwen3.5-397b-a17b" "sarvamai/sarvam-m" "upstage/solar-10.7b-instruct"})
(def ^:private moonshot-compat
  (array-map :supports-store false :supports-developer-role false
             :supports-reasoning-effort false :max-tokens-field :max-tokens
             :supports-strict-mode false :thinking-format :deepseek))
(def ^:private kimi-k3-cost (array-map :input 3 :output 15 :cache-read 0.3 :cache-write 0))
(def ^:private kimi-k3-max-tokens 131072)
(def ^:private xiaomi-compat
  (array-map :requires-reasoning-content-on-assistant-messages true
             :thinking-format :deepseek))
(def ^:private qwen-token-plan-compat
  (array-map :thinking-format :qwen :supports-developer-role false
             :supports-store false :supports-reasoning-effort true))
(def ^:private qwen-token-plan-high-max-thinking-level-map
  (array-map :minimal nil :low nil :medium nil :high "high" :xhigh nil :max "max"))
(def ^:private qwen-token-plan-qwen38-thinking-level-map
  (array-map :minimal nil :low "low" :medium "medium" :high nil :xhigh "xhigh" :max nil))
(def ^:private qwen-token-plan-reasoning-effort-unsupported-model-ids
  #{"MiniMax-M2.5" "deepseek-v3.2" "kimi-k2.5" "kimi-k2.6" "kimi-k2.7-code"
    "qwen3.6-flash" "qwen3.6-plus" "qwen3.7-max" "qwen3.7-plus"})
(def ^:private qwen-token-plan-excluded-model-ids #{"qwen3.8-max-preview"})
(def ^:private qwen-token-plan-provider-ids
  #{:qwen-token-plan :qwen-token-plan-cn :qwen-token-plan-individual})
;; QwenCloud Token Plan Individual text-model allowlist (pi, verified 2026-08-05).
(def ^:private qwen-token-plan-individual-model-ids
  #{"deepseek-v4-flash-0731" "deepseek-v4-pro" "glm-5.2" "qwen3.6-flash"
    "qwen3.7-max" "qwen3.7-plus" "qwen3.8-max"})
(def ^:private ai-gateway-models-url "https://ai-gateway.vercel.sh/v1")
(def ^:private ai-gateway-base-url "https://ai-gateway.vercel.sh")
(def ^:private openrouter-base-url "https://openrouter.ai/api/v1")
(def ^:private openrouter-kimi-k3-model-ids #{"moonshotai/kimi-k3" "~moonshotai/kimi-latest"})
(def ^:private minimax-direct-supported-ids
  #{"MiniMax-M2.7" "MiniMax-M2.7-highspeed" "MiniMax-M3"})

(defn- parse-cost
  "Parse a pricing string to a number, nil-safe (pi parseFloat)."
  [v]
  (when (string? v)
    (try (Double/parseDouble v) (catch Exception _ 0))))

(defn- cost-per-million
  "Pricing value → $/M tokens, clamped to 0 — OpenRouter uses -1 as the
   'unknown pricing' sentinel, which would otherwise produce negative rates."
  [v]
  (round-cost (* (max 0 (or (parse-cost v) 0)) 1000000)))

(defn- process-anthropic
  "pi: all models.dev anthropic models → :anthropic-messages
   (https://api.anthropic.com). Adaptive-thinking level maps come from the
   shared metadata rules; kmet's builder sends budget-based thinking
   (classic claude format) regardless."
  [data]
  (doall
   (for [[mid m] (or (get-in data ["anthropic" "models"]) {})
         :when (true? (get m "tool_call"))]
     (apply-thinking-maps
      (model-map :anthropic :anthropic-messages anthropic-base-url m mid {}) m))))

(defn- process-google
  "pi: all models.dev google models → :google-generative-ai
   (https://generativelanguage.googleapis.com/v1beta). The -latest aliases
   take their capabilities from the named source model (pi)."
  [data]
  (doall
   (for [[mid m] (or (get-in data ["google" "models"]) {})
         :when (true? (get m "tool_call"))]
     (let [source (cond
                    (= mid "gemini-flash-latest")
                    (get-in data ["google" "models" "gemini-3.5-flash"])
                    (= mid "gemini-flash-lite-latest")
                    (get-in data ["google" "models" "gemini-3.1-flash-lite"])
                    :else m)
           merged (merge m (select-keys source ["reasoning" "modalities" "cost" "limit"]))]
       (apply-thinking-maps
        (model-map :google :google-generative-ai google-base-url merged mid {}) m)))))

(defn- process-groq
  [data]
  (doall
   (for [[mid m] (or (get-in data ["groq" "models"]) {})
         :when (true? (get m "tool_call"))]
     (apply-thinking-maps
      (model-map :groq :openai-completions groq-base-url m mid {}) m))))

(defn- process-cerebras
  [data]
  (doall
   (for [[mid m] (or (get-in data ["cerebras" "models"]) {})
         :when (true? (get m "tool_call"))]
     (apply-thinking-maps
      (model-map :cerebras :openai-completions cerebras-base-url m mid
                 {:compat (array-map :supports-store false
                                     :supports-developer-role false
                                     :max-tokens-field :max-tokens)})
      m))))

(defn- process-huggingface
  [data]
  (doall
   (for [[mid m] (or (get-in data ["huggingface" "models"]) {})
         :when (true? (get m "tool_call"))]
     (apply-thinking-maps
      (model-map :huggingface :openai-completions huggingface-base-url m mid
                 {:compat (array-map :supports-developer-role false)})
      m))))

(def ^:private moonshot-variants
  [{:key "moonshotai" :provider :moonshotai :base-url "https://api.moonshot.ai/v1"}
   {:key "moonshotai-cn" :provider :moonshotai-cn :base-url "https://api.moonshot.cn/v1"}])

(defn- process-moonshot
  "pi: moonshot models → openai-completions with the deepseek thinking
   format; kimi-k3 gets native reasoning effort + the KIMI_K3 cost fallback."
  [data]
  (doall
   (for [{:keys [key provider base-url]} moonshot-variants
         [mid m] (or (get-in data [key "models"]) {})
         :when (true? (get m "tool_call"))]
     (let [k3? (= mid "kimi-k3")
           compat (if k3?
                    (assoc moonshot-compat
                           :requires-reasoning-content-on-assistant-messages true
                           :deferred-tools-mode :kimi
                           :thinking-format :openai
                           :supports-reasoning-effort true)
                    moonshot-compat)]
       (apply-thinking-maps
        (model-map provider :openai-completions base-url m mid
                   {:compat compat :cost (when k3? kimi-k3-cost)})
        m)))))

(def ^:private xiaomi-variants
  [{:key "xiaomi" :provider :xiaomi :base-url "https://api.xiaomimimo.com/v1"}
   {:key "xiaomi-token-plan-cn" :provider :xiaomi-token-plan-cn
    :base-url "https://token-plan-cn.xiaomimimo.com/v1"}
   {:key "xiaomi-token-plan-ams" :provider :xiaomi-token-plan-ams
    :base-url "https://token-plan-ams.xiaomimimo.com/v1"}
   {:key "xiaomi-token-plan-sgp" :provider :xiaomi-token-plan-sgp
    :base-url "https://token-plan-sgp.xiaomimimo.com/v1"}])

(defn- process-xiaomi
  [data]
  (doall
   (for [{:keys [key provider base-url]} xiaomi-variants
         [mid m] (or (get-in data [key "models"]) {})
         :when (true? (get m "tool_call"))]
     (apply-thinking-maps
      (model-map provider :openai-completions base-url m mid
                 {:compat xiaomi-compat})
      m))))

(def ^:private qwen-token-plan-variants
  [{:key "alibaba-token-plan" :provider :qwen-token-plan
    :base-url "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"
    :model-ids nil}
   {:key "alibaba-token-plan" :provider :qwen-token-plan-individual
    :base-url "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"
    :model-ids qwen-token-plan-individual-model-ids}
   {:key "alibaba-token-plan-cn" :provider :qwen-token-plan-cn
    :base-url "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
    :model-ids nil}])

(defn- process-qwen-token-plan
  "pi: the Alibaba Cloud Token Plan catalogs → openai-completions with the
   qwen thinking format; individual is the international allowlist view."
  [data]
  (doall
   (for [{:keys [key provider base-url model-ids]} qwen-token-plan-variants
         [mid m] (or (get-in data [key "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (not (contains? qwen-token-plan-excluded-model-ids mid))
                    (or (nil? model-ids) (contains? model-ids mid)))]
     (let [effort? (not (contains? qwen-token-plan-reasoning-effort-unsupported-model-ids mid))]
       (apply-thinking-maps
        (model-map provider :openai-completions base-url m mid
                   {:compat (if effort?
                              qwen-token-plan-compat
                              (assoc qwen-token-plan-compat
                                     :supports-reasoning-effort false))
                    :thinking-level-map (when effort?
                                          (if (= mid "qwen3.8-max")
                                            qwen-token-plan-qwen38-thinking-level-map
                                            qwen-token-plan-high-max-thinking-level-map))})
        m)))))

(def ^:private minimax-variants
  [{:key "minimax" :provider :minimax :base-url "https://api.minimax.io/anthropic"}
   {:key "minimax-cn" :provider :minimax-cn :base-url "https://api.minimaxi.com/anthropic"}])

(defn- process-minimax
  "pi: minimax models → anthropic-messages (the Anthropic-compatible API);
   the direct-supported-ids filter runs after all sources (pi)."
  [data]
  (doall
   (for [{:keys [key provider base-url]} minimax-variants
         [mid m] (or (get-in data [key "models"]) {})
         :when (true? (get m "tool_call"))]
     (apply-thinking-maps
      (model-map provider :anthropic-messages base-url m mid {}) m))))

(defn- normalize-nvidia-model-id
  "pi normalizeNvidiaModelId: lower-case, underscores → dots."
  [model-id]
  (-> model-id str/lower-case (str/replace "_" ".")))

(defn- fetch-nvidia-nim-model-ids
  "pi fetchNvidiaNimModelIds: the live NVIDIA NIM /models list — the
   catalog ids that actually exist on the endpoint (throws on failure, like
   fetch-models-dev)."
  []
  (let [resp (http/get (str nvidia-base-url "/models")
                       {:headers {"User-Agent" "kmet-generate-models"}
                        :timeout 60000})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "NVIDIA NIM API returned HTTP " (:status resp))
                      {:type :http-error :status (:status resp)})))
    (let [data (json/parse-string (:body resp) false)]
      (into {}
            (for [model (get data "data")]
              [(get model "id") (get model "id")])))))

(defn- process-nvidia
  "pi: nvidia models whose id exists on the live NIM endpoint (unsupported
   ids dropped), openai-completions with the NIM headers + compat."
  [data nim-ids]
  (doall
   (for [[mid m] (or (get-in data ["nvidia" "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (some #{"text"} (get-in m ["modalities" "input"]))
                    (some #{"text"} (get-in m ["modalities" "output"])))
         :let [live-id (or (get nim-ids mid)
                           (get nim-ids (normalize-nvidia-model-id mid)))]
         :when (and live-id
                    (not (contains? nvidia-nim-unsupported-models live-id)))]
     (apply-thinking-maps
      (model-map :nvidia :openai-completions nvidia-base-url m live-id
                 {:compat nvidia-openai-compat :headers nvidia-headers})
      m))))

(defn- fetch-openrouter-models
  "pi fetchOpenRouterModels: the live OpenRouter catalog — tool-capable
   models only, pricing converted to $/M. Returns kmet-shaped model maps
   with the openrouter thinking-format compat (nested reasoning: {effort}, pi
   detectOpenAICompletionsCompat) plus the thinking-level-map from the live
   reasoning metadata (pi getOpenRouterThinkingLevelMap)."
  []
  (let [resp (http/get "https://openrouter.ai/api/v1/models"
                       {:headers {"User-Agent" "kmet-generate-models"}
                        :timeout 60000})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "OpenRouter API returned HTTP " (:status resp))
                      {:type :http-error :status (:status resp)})))
    (let [data (json/parse-string (:body resp) false)
          models (get data "data")]
      (when-not (vector? models)
        (throw (ex-info "Invalid OpenRouter models response" {:type :http-error})))
      (for [model models
            :when (some #(= "tools" %) (get model "supported_parameters"))]
        (let [cost (fn [k] (cost-per-million (get-in model ["pricing" k])))
              tlm (openrouter-thinking-level-map (get model "reasoning"))]
          (cond-> (array-map :id (get model "id")
                             :name (or (get model "name") (get model "id"))
                             :provider :openrouter
                             :api :openai-completions
                             :base-url openrouter-base-url
                             :reasoning (boolean (some #(= "reasoning" %)
                                                       (get model "supported_parameters")))
                             :input (if (str/includes? (or (get-in model ["architecture" "modality"]) "")
                                                       "image")
                                      [:text :image] [:text])
                             :cost (array-map :input (cost "prompt")
                                              :output (cost "completion")
                                              :cache-read (cost "input_cache_read")
                                              :cache-write (cost "input_cache_write"))
                             :context-window (or (get-in model ["top_provider" "context_length"])
                                                 (get model "context_length") 4096)
                             :max-tokens (or (get-in model ["top_provider" "max_completion_tokens"]) 4096)
                             :compat (array-map :thinking-format :openrouter))
            tlm (assoc :thinking-level-map tlm)))))))

(defn- process-openrouter
  "pi fetchOpenRouterModels + the post-merge openrouter additions/overrides:
   the auto/fusion aliases, Kimi K3's canonical max tokens, and the kimi-k2.5
   cost override."
  [fetched]
  (let [with-k3 (map (fn [mm]
                       (cond-> mm
                         (contains? openrouter-kimi-k3-model-ids (:id mm))
                         (assoc :max-tokens kimi-k3-max-tokens)
                         (= "moonshotai/kimi-k2.5" (:id mm))
                         (assoc :cost (array-map :input 0.41 :output 2.06
                                                 :cache-read 0.07 :cache-write 0)
                                :max-tokens 4096)))
                     fetched)
        ids (set (map :id with-k3))]
    (concat with-k3
            (remove #(contains? ids (:id %))
                    [(array-map :id "auto" :name "Auto"
                                :provider :openrouter :api :openai-completions
                                :base-url openrouter-base-url
                                :reasoning true :input [:text :image]
                                :cost (array-map :input 0 :output 0 :cache-read 0 :cache-write 0)
                                :context-window 2000000 :max-tokens 30000
                                :compat (array-map :thinking-format :openrouter))
                     (array-map :id "openrouter/fusion" :name "OpenRouter: Fusion"
                                :provider :openrouter :api :openai-completions
                                :base-url openrouter-base-url
                                :reasoning true :input [:text]
                                :cost (array-map :input 0 :output 0 :cache-read 0 :cache-write 0)
                                :context-window 1000000 :max-tokens 30000
                                :compat (array-map :thinking-format :openrouter))]))))

(defn- process-fireworks
  "pi processFireworksModels: glm-5p2 + kimi-k3 → openai-completions, the
   rest → anthropic-messages (the Anthropic-compatible API; the
   session-affinity/eager-streaming compat keys are data-only in kmet)."
  [data]
  (let [anthropic-compat (array-map :send-session-affinity-headers true
                                    :supports-eager-tool-input-streaming false
                                    :supports-cache-control-on-tools false
                                    :supports-long-cache-retention false)
        openai-compat (array-map :supports-store false :supports-developer-role false
                                 :send-session-affinity-headers true
                                 :supports-long-cache-retention false)
        kimi-k3-compat (assoc openai-compat
                              :requires-reasoning-content-on-assistant-messages true
                              :thinking-format :openai
                              :deferred-tools-mode :kimi)]
    (doall
     (for [[mid m] (or (get-in data ["fireworks-ai" "models"]) {})
           :when (true? (get m "tool_call"))]
       (cond
         (str/includes? mid "glm-5p2")
         (apply-thinking-maps
          (model-map :fireworks :openai-completions
                     "https://api.fireworks.ai/inference/v1" m mid
                     {:compat openai-compat})
          m)
         (str/includes? mid "kimi-k3")
         (apply-thinking-maps
          (model-map :fireworks :openai-completions
                     "https://api.fireworks.ai/inference/v1" m mid
                     {:compat kimi-k3-compat})
          m)
         :else
         (apply-thinking-maps
          (model-map :fireworks :anthropic-messages
                     "https://api.fireworks.ai/inference" m mid
                     {:compat anthropic-compat})
          m))))))

(defn- fetch-ai-gateway-models
  "pi fetchAiGatewayModels: the live Vercel AI Gateway catalog — tool-use
   tagged models, pricing converted to $/M (throws on failure)."
  []
  (let [resp (http/get (str ai-gateway-models-url "/models")
                       {:headers {"User-Agent" "kmet-generate-models"}
                        :timeout 60000})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "Vercel AI Gateway API returned HTTP " (:status resp))
                      {:type :http-error :status (:status resp)})))
    (let [data (json/parse-string (:body resp) false)
          items (get data "data")]
      (when-not (vector? items)
        (throw (ex-info "Invalid Vercel AI Gateway models response" {:type :http-error})))
      (for [model items
            :when (some #(= "tool-use" %) (get model "tags"))]
        (let [cost (fn [k] (cost-per-million (get-in model ["pricing" k])))
              input (if (some #(= "vision" %) (get model "tags")) [:text :image] [:text])]
          (array-map :id (get model "id")
                     :name (or (get model "name") (get model "id"))
                     :provider :vercel-ai-gateway
                     :api :anthropic-messages
                     :base-url ai-gateway-base-url
                     :reasoning (boolean (some #(= "reasoning" %) (get model "tags")))
                     :input input
                     :cost (array-map :input (cost "input")
                                      :output (cost "output")
                                      :cache-read (cost "input_cache_read")
                                      :cache-write (cost "input_cache_write"))
                     :context-window (or (get model "context_window") 4096)
                     :max-tokens (or (get model "max_tokens") 4096)))))))

(defn- process-vercel-ai-gateway
  "pi fetchAiGatewayModels + the kimi-k3 max-tokens override."
  [fetched]
  (map (fn [mm]
         (if (= "moonshotai/kimi-k3" (:id mm))
           (assoc mm :max-tokens kimi-k3-max-tokens)
           mm))
       fetched))

;; ─── A.3 slice: zai, together, baseten, ant-ling, kimi-coding, cloudflare  ──
;;     (pi loadModelsDevData sections + detectOpenAICompletionsCompat) ───────

(def ^:private zai-base-urls
  [{:provider :zai :base-url "https://api.z.ai/api/coding/paas/v4"}
   {:provider :zai-coding-cn :base-url "https://open.bigmodel.cn/api/coding/paas/v4"}])
(def ^:private zai-tool-stream-unsupported-models
  #{"glm-4.5" "glm-4.5-air" "glm-4.5-flash" "glm-4.5v"})
(def ^:private zai-glm52-thinking-level-map
  (array-map :minimal nil :low "high" :medium "high" :high "high" :max "max"))

(defn- process-zai
  "pi: the zai-coding-plan catalog → zai / zai-coding-cn with the zai
   thinking format (glm-5.2 gets native effort + the level map; the
   tool-stream models exclude the zaiToolStream flag)."
  [data]
  (doall
   (for [{:keys [provider base-url]} zai-base-urls
         [mid m] (or (get-in data ["zai-coding-plan" "models"]) {})
         :when (true? (get m "tool_call"))]
     (let [glm52? (= mid "glm-5.2")
           compat (cond-> (array-map :supports-developer-role false
                                     :thinking-format :zai
                                     :max-tokens-field :max-tokens)
                    glm52? (assoc :supports-reasoning-effort true)
                    (not (contains? zai-tool-stream-unsupported-models mid))
                    (assoc :zai-tool-stream true))]
       (apply-thinking-maps
        (model-map provider :openai-completions base-url m mid
                   {:compat compat
                    :thinking-level-map (when glm52? zai-glm52-thinking-level-map)})
        m)))))

;; ─── together (pi getTogetherCompat / getTogetherThinkingLevelMap) ────────

(def ^:private together-base-url "https://api.together.ai/v1")
(def ^:private together-base-compat
  (array-map :supports-store false :supports-developer-role false
             :supports-reasoning-effort false :max-tokens-field :max-tokens
             :supports-strict-mode false :supports-long-cache-retention false))
(def ^:private together-toggle-reasoning-compat
  (assoc together-base-compat :thinking-format :together))
(def ^:private together-reasoning-effort-compat
  (assoc together-base-compat :supports-reasoning-effort true
         :thinking-format :openai))
(def ^:private together-toggle-reasoning-effort-compat
  (assoc together-toggle-reasoning-compat :supports-reasoning-effort true))
(def ^:private together-reasoning-only-models
  #{"deepseek-ai/DeepSeek-R1" "MiniMaxAI/MiniMax-M2.7"})
(def ^:private together-reasoning-effort-models
  #{"openai/gpt-oss-20b" "openai/gpt-oss-120b"})
(def ^:private together-toggle-reasoning-effort-models
  #{"deepseek-ai/DeepSeek-V4-Pro"})
(def ^:private together-fixed-reasoning-level-map
  (array-map :off nil :minimal nil :low nil :medium nil))
(def ^:private together-reasoning-effort-level-map
  (array-map :off nil :minimal nil))
(def ^:private together-deepseek-v4-thinking-level-map
  (array-map :minimal nil :low nil :medium nil :high "high" :xhigh nil))
(def ^:private together-toggle-reasoning-level-map
  (array-map :minimal nil :low nil :medium nil))

(defn- together-compat
  [mid reasoning?]
  (cond
    (not reasoning?) together-base-compat
    (contains? together-reasoning-effort-models mid) together-reasoning-effort-compat
    (contains? together-toggle-reasoning-effort-models mid) together-toggle-reasoning-effort-compat
    (contains? together-reasoning-only-models mid) together-base-compat
    :else together-toggle-reasoning-compat))

(defn- together-thinking-level-map
  [mid reasoning?]
  (when reasoning?
    (cond
      (contains? together-reasoning-effort-models mid) together-reasoning-effort-level-map
      (contains? together-toggle-reasoning-effort-models mid) together-deepseek-v4-thinking-level-map
      (contains? together-reasoning-only-models mid) together-fixed-reasoning-level-map
      :else together-toggle-reasoning-level-map)))

(defn- process-together
  "pi: the together catalog (models.dev keys together/togetherai/together-ai)
   → openai-completions with the per-model reasoning compat + level map."
  [data]
  (doall
   (for [[mid m] (or (get-in data ["together" "models"])
                     (get-in data ["togetherai" "models"])
                     (get-in data ["together-ai" "models"])
                     {})
         :when (and (true? (get m "tool_call"))
                    (not= "deprecated" (get m "status")))]
     (let [reasoning? (true? (get m "reasoning"))]
       (apply-thinking-maps
        (model-map :together :openai-completions together-base-url m mid
                   {:compat (together-compat mid reasoning?)
                    :thinking-level-map (together-thinking-level-map mid reasoning?)})
        m)))))

;; ─── baseten (pi processBasetenModels) ────────────────────────────────────

(def ^:private baseten-base-url "https://inference.baseten.co/v1")
(def ^:private baseten-base-compat
  (array-map :supports-store false :supports-developer-role false
             :supports-reasoning-effort false :supports-strict-mode true
             :max-tokens-field :max-tokens :supports-long-cache-retention false))
(def ^:private baseten-reasoning-effort-compat
  (assoc baseten-base-compat :supports-reasoning-effort true :thinking-format :openai))
(def ^:private baseten-toggle-reasoning-compat
  (assoc baseten-base-compat :thinking-format :baseten
         :chat-template-args (array-map :enable_thinking (array-map :var "thinking.enabled"))))
(def ^:private baseten-toggle-reasoning-effort-compat
  (assoc baseten-reasoning-effort-compat :thinking-format :baseten
         :chat-template-args (array-map :enable_thinking (array-map :var "thinking.enabled"))))
(def ^:private baseten-toggle-thinking-level-map
  (array-map :off "off" :minimal nil :low nil :medium nil :high "high" :xhigh nil :max nil))
(def ^:private baseten-glm52-thinking-level-map
  (array-map :off "none" :minimal nil :low nil :medium nil :high "high" :xhigh nil :max "max"))

(defn- process-baseten
  "pi processBasetenModels: toggle/effort compat from the reasoning
   options; glm-5.2 is always both."
  [data]
  (doall
   (for [[mid m] (or (get-in data ["baseten" "models"]) {})
         :when (not= "deprecated" (get m "status"))]
     (let [reasoning-options (get m "reasoning_options")
           glm52? (or (= mid "zai-org/GLM-5.2") (= mid "zai-org/GLM-5.2-Fast"))
           toggle? (or glm52? (some #(= "toggle" (get % "type")) reasoning-options))
           effort? (or glm52? (some #(= "effort" (get % "type")) reasoning-options))
           compat (cond
                    (and toggle? effort?) baseten-toggle-reasoning-effort-compat
                    toggle? baseten-toggle-reasoning-compat
                    effort? baseten-reasoning-effort-compat
                    :else baseten-base-compat)
           tlm (cond
                 glm52? baseten-glm52-thinking-level-map
                 toggle? baseten-toggle-thinking-level-map
                 :else (effort-thinking-level-map reasoning-options))]
       (apply-thinking-maps
        (model-map :baseten :openai-completions baseten-base-url m mid
                   {:compat compat :thinking-level-map tlm})
        m)))))

;; ─── ant-ling (pi antLingModels — hardcoded, kept small) ──────────────────

(def ^:private ant-ling-base-url "https://api.ant-ling.com/v1")
(def ^:private ant-ling-compat
  (array-map :supports-store false :supports-developer-role false
             :supports-reasoning-effort false :max-tokens-field :max-tokens
             :supports-long-cache-retention false))
(def ^:private ant-ling-ring-thinking-level-map
  (array-map :off nil :minimal nil :low nil :medium nil
             :high "high" :xhigh "xhigh"))

(defn- process-ant-ling
  "pi antLingModels: Ling-2.6-flash/-1T (non-reasoning) + Ring-2.6-1T
   (reasoning, ant-ling thinking format — thinks by default, ignores
   effort)."
  []
  [(array-map :id "Ling-2.6-flash" :name "Ling 2.6 Flash"
              :provider :ant-ling :api :openai-completions
              :base-url ant-ling-base-url
              :reasoning false :input [:text]
              :cost (array-map :input 0.01 :output 0.02 :cache-read 0 :cache-write 0)
              :context-window 262144 :max-tokens 65536
              :compat ant-ling-compat)
   (array-map :id "Ling-2.6-1T" :name "Ling 2.6 1T"
              :provider :ant-ling :api :openai-completions
              :base-url ant-ling-base-url
              :reasoning false :input [:text]
              :cost (array-map :input 0.06 :output 0.25 :cache-read 0 :cache-write 0)
              :context-window 262144 :max-tokens 65536
              :compat ant-ling-compat)
   (apply-thinking-maps
    (array-map :id "Ring-2.6-1T" :name "Ring 2.6 1T"
               :provider :ant-ling :api :openai-completions
               :base-url ant-ling-base-url
               :reasoning true :input [:text]
               :cost (array-map :input 0.06 :output 0.25 :cache-read 0 :cache-write 0)
               :context-window 262144 :max-tokens 65536
               :compat (assoc ant-ling-compat :thinking-format :ant-ling)
               :thinking-level-map ant-ling-ring-thinking-level-map)
    nil)])

;; ─── kimi-coding (pi: the kimi-code-plan-global catalog, Anthropic-compatible) ─

(def ^:private kimi-static-headers (array-map "User-Agent" "KimiCLI/1.5"))
(def ^:private kimi-coding-implied-costs
  {"k3" (array-map :input 3 :output 15 :cache-read 0.3 :cache-write 0)
   "kimi-for-coding" (array-map :input 0.95 :output 4 :cache-read 0.19 :cache-write 0)
   "kimi-for-coding-highspeed" (array-map :input 1.9 :output 8 :cache-read 0.38 :cache-write 0)
   "kimi-k2-thinking" (array-map :input 0.6 :output 2.5 :cache-read 0.15 :cache-write 0)})
(def ^:private kimi-aliases #{"k2p5" "k2p6" "k2p7"})

(defn- process-kimi-coding
  "pi: the kimi-code-plan-global catalog → anthropic-messages with adaptive
   thinking compat, static headers and the subscription implied costs;
   versioned aliases normalize to the canonical id."
  [data]
  (let [models (get-in data ["kimi-code-plan-global" "models"] {})
        canonical? (contains? models "kimi-for-coding")]
    (doall
     (for [[mid m] models
           :when (and (true? (get m "tool_call"))
                      (not (and (contains? kimi-aliases mid) canonical?)))
           :let [normalized (if (contains? kimi-aliases mid) "kimi-for-coding" mid)
                 k3? (= normalized "k3")
                 implied (get kimi-coding-implied-costs normalized)
                 compat (cond-> {:force-adaptive-thinking true}
                          (or k3? (= normalized "kimi-for-coding"))
                          (assoc :allow-empty-signature true))]]
       (apply-thinking-maps
        (model-map :kimi-coding :anthropic-messages
                   "https://api.kimi.com/coding" m normalized
                   {:compat compat :headers kimi-static-headers :cost implied})
        m)))))

;; ─── cloudflare (pi cloudflare-workers-ai / cloudflare-ai-gateway; the   ──
;;     base URLs carry {CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}
;;     placeholders interpolated at request time) ───────────────────────────

(def ^:private cloudflare-workers-ai-base-url
  "https://api.cloudflare.com/client/v4/accounts/{CLOUDFLARE_ACCOUNT_ID}/ai/v1")
(def ^:private cloudflare-ai-gateway-compat-base-url
  "https://gateway.ai.cloudflare.com/v1/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/compat")
(def ^:private cloudflare-ai-gateway-openai-base-url
  "https://gateway.ai.cloudflare.com/v1/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/openai")
(def ^:private cloudflare-ai-gateway-anthropic-base-url
  "https://gateway.ai.cloudflare.com/v1/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/anthropic")
(def ^:private cloudflare-session-affinity-compat
  (array-map :send-session-affinity-headers true))

(defn- process-cloudflare-workers-ai
  [data]
  (doall
   (for [[mid m] (or (get-in data ["cloudflare-workers-ai" "models"]) {})
         :when (true? (get m "tool_call"))]
     (apply-thinking-maps
      (model-map :cloudflare-workers-ai :openai-completions
                 cloudflare-workers-ai-base-url m mid
                 {:compat cloudflare-session-affinity-compat})
      m))))

(defn- process-cloudflare-ai-gateway
  "pi: the prefixed gateway catalog — openai/ → openai-responses,
   anthropic/ → anthropic-messages, workers-ai/ → openai-completions (the
   prefixed id kept for the compat route)."
  [data]
  (doall
   (for [[prefixed m] (or (get-in data ["cloudflare-ai-gateway" "models"]) {})
         :when (true? (get m "tool_call"))
         :let [slash (str/index-of prefixed "/")
               upstream (when slash (subs prefixed 0 slash))
               native (when slash (subs prefixed (inc slash)))]
         :when (contains? #{"openai" "anthropic" "workers-ai"} upstream)]
     (let [[api base id compat]
           (case upstream
             "openai" [:openai-responses cloudflare-ai-gateway-openai-base-url native nil]
             "anthropic" [:anthropic-messages cloudflare-ai-gateway-anthropic-base-url
                          native cloudflare-session-affinity-compat]
             "workers-ai" [:openai-completions cloudflare-ai-gateway-compat-base-url
                           prefixed cloudflare-session-affinity-compat])]
       (apply-thinking-maps
        (model-map :cloudflare-ai-gateway api base m id {:compat compat})
        m)))))

;; ─── Deferred A.2: mistral / google-vertex / amazon-bedrock (pi            ──
;;    loadModelsDevData sections) ──────────────────────────────────────────

(def ^:private mistral-base-url "https://api.mistral.ai")
(def ^:private vertex-base-url "https://{location}-aiplatform.googleapis.com")
(def ^:private bedrock-inference-profile-only-model-ids #{"anthropic.claude-opus-5"})

(defn- process-mistral
  "pi: all models.dev mistral models → :mistral-conversations
   (https://api.mistral.ai). The cache-read rate falls back to 10% of the
   input rate when models.dev reports none (pi)."
  [data]
  (doall
   (for [[mid m] (or (get-in data ["mistral" "models"]) {})
         :when (true? (get m "tool_call"))]
     (let [input (get-in m ["cost" "input"])
           cache-read (get-in m ["cost" "cache_read"])
           cost (array-map :input (or input 0)
                           :output (or (get-in m ["cost" "output"]) 0)
                           :cache-read (if (pos? (or cache-read 0))
                                         cache-read
                                         (round-cost (* (or input 0) 0.1)))
                           :cache-write (or (get-in m ["cost" "cache_write"]) 0))]
       (apply-thinking-maps
        (assoc (model-map :mistral :mistral-conversations mistral-base-url m mid {})
               :cost cost)
        m)))))

;; mistral-medium-3.5 (pi: hardcoded until models.dev includes it)
(def ^:private mistral-medium-35-model
  (array-map :id "mistral-medium-3.5" :name "Mistral Medium 3.5"
             :provider :mistral :api :mistral-conversations
             :base-url mistral-base-url :reasoning true :input [:text :image]
             :cost (array-map :input 1.5 :output 7.5 :cache-read 0 :cache-write 0)
             :context-window 262144 :max-tokens 262144))

(defn- process-google-vertex
  "pi: the google-vertex models.dev catalog (gemini models only — the MaaS
   claude/openai entries use other APIs). The -latest aliases take their
   capabilities from the named source model; gemini-2.5-flash's models.dev
   cache rate doesn't match the official pricing, so it is pinned (pi)."
  [data]
  (doall
   (for [[mid m] (or (get-in data ["google-vertex" "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (str/starts-with? mid "gemini-")
                    (not= mid "gemini-3.1-flash-lite-preview"))
         :let [source (cond
                        (= mid "gemini-flash-latest")
                        (get-in data ["google-vertex" "models" "gemini-3.5-flash"])
                        (= mid "gemini-flash-lite-latest")
                        (get-in data ["google-vertex" "models" "gemini-3.1-flash-lite"])
                        :else m)
               merged (merge m (select-keys source ["reasoning" "modalities" "cost" "limit"]))
               cost (assoc (cost-map merged)
                           :cache-read (if (= mid "gemini-2.5-flash")
                                         0.03
                                         (or (get-in merged ["cost" "cache_read"]) 0))
                           :cache-write 0)]]
     (apply-thinking-maps
      (assoc (model-map :google-vertex :google-vertex vertex-base-url merged mid {})
             :cost cost)
      m))))

(defn- bedrock-base-url
  "pi getBedrockBaseUrl: eu.* ids hit the eu-central-1 endpoint."
  [mid]
  (if (str/starts-with? mid "eu.")
    "https://bedrock-runtime.eu-central-1.amazonaws.com"
    "https://bedrock-runtime.us-east-1.amazonaws.com"))

(defn- process-amazon-bedrock
  "pi: all models.dev amazon-bedrock models → :bedrock-converse-stream.
   Excluded: inference-profile-only ids, ai21.jamba* (no tool-use
   streaming), mistral.mistral-7b-instruct-v0* (no system messages).
   structured_output models get supportsStrictMode."
  [data]
  (doall
   (for [[mid m] (or (get-in data ["amazon-bedrock" "models"]) {})
         :when (and (true? (get m "tool_call"))
                    (not (contains? bedrock-inference-profile-only-model-ids mid))
                    (not (str/starts-with? mid "ai21.jamba"))
                    (not (str/starts-with? mid "mistral.mistral-7b-instruct-v0")))
         :let [compat (when (true? (get m "structured_output"))
                        (array-map :supports-strict-mode true))]]
     (apply-thinking-maps
      (model-map :amazon-bedrock :bedrock-converse-stream (bedrock-base-url mid) m mid
                 {:compat compat})
      m))))

;; ─── applyOpenAICompletionsCompatMetadata (pi detectOpenAICompletionsCompat ──
;;     + openAICompletionsCompatDelta): URL-based compat auto-detection
;;     merged under the model's explicit compat — fills the gaps the section
;;     compat leaves (data parity with pi's catalogs) ────────────────────────

(def ^:private openai-completions-default-compat
  {:supports-store true :supports-developer-role true
   :supports-reasoning-effort true :max-tokens-field :max-completion-tokens
   :requires-reasoning-content-on-assistant-messages false
   :thinking-format :openai :zai-tool-stream false
   :supports-strict-mode true :send-session-affinity-headers false
   :supports-long-cache-retention true})

(defn- detect-openai-completions-compat
  "pi detectOpenAICompletionsCompat: the URL/provider-based detection for an
   openai-completions model."
  [mm]
  (let [provider (:provider mm)
        base-url (:base-url mm)
        id (:id mm)
        is-zai? (or (= :zai provider) (= :zai-coding-cn provider)
                    (str/includes? base-url "api.z.ai")
                    (str/includes? base-url "open.bigmodel.cn"))
        is-together? (or (= :together provider)
                         (str/includes? base-url "api.together.ai")
                         (str/includes? base-url "api.together.xyz"))
        is-moonshot? (or (= :moonshotai provider) (= :moonshotai-cn provider)
                         (str/includes? base-url "api.moonshot."))
        is-openrouter? (or (= :openrouter provider) (str/includes? base-url "openrouter.ai"))
        is-cloudflare-workers-ai? (or (= :cloudflare-workers-ai provider)
                                      (str/includes? base-url "api.cloudflare.com"))
        is-cloudflare-ai-gateway? (or (= :cloudflare-ai-gateway provider)
                                      (str/includes? base-url "gateway.ai.cloudflare.com"))
        is-nvidia? (or (= :nvidia provider) (str/includes? base-url "integrate.api.nvidia.com"))
        is-ant-ling? (or (= :ant-ling provider) (str/includes? base-url "api.ant-ling.com"))
        together-reasoning-only? (and is-together?
                                      (contains? together-reasoning-only-models id))
        is-deepseek? (or (= :deepseek provider)
                         (str/includes? (str/lower-case base-url) "deepseek.com"))
        is-non-standard? (or is-nvidia? (= :cerebras provider)
                             (str/includes? base-url "cerebras.ai")
                             (= :xai provider) (str/includes? base-url "api.x.ai")
                             is-together? (str/includes? base-url "chutes.ai")
                             is-deepseek? is-zai? is-moonshot?
                             (= :opencode provider) (str/includes? base-url "opencode.ai")
                             is-cloudflare-workers-ai? is-cloudflare-ai-gateway? is-ant-ling?)
        use-max-tokens? (or (str/includes? base-url "chutes.ai") is-deepseek?
                            is-moonshot? is-cloudflare-ai-gateway? is-together?
                            is-nvidia? is-ant-ling? is-zai?)
        is-grok? (or (= :xai provider) (str/includes? base-url "api.x.ai"))
        openrouter-dev-role? (and is-openrouter?
                                  (or (str/starts-with? id "anthropic/")
                                      (str/starts-with? id "openai/")))
        thinking-format (cond
                          is-deepseek? :deepseek
                          is-zai? :zai
                          (and is-together? (not together-reasoning-only?)) :together
                          is-ant-ling? :ant-ling
                          is-openrouter? :openrouter
                          :else :openai)]
    {:supports-store (not is-non-standard?)
     :supports-developer-role (or openrouter-dev-role?
                                  (and (not is-non-standard?) (not is-openrouter?)))
     :supports-reasoning-effort (not (or is-grok? is-zai? is-moonshot? is-together?
                                         is-cloudflare-ai-gateway? is-nvidia? is-ant-ling?))
     :max-tokens-field (if use-max-tokens? :max-tokens :max-completion-tokens)
     :requires-reasoning-content-on-assistant-messages is-deepseek?
     :thinking-format thinking-format
     :zai-tool-stream false
     :supports-strict-mode (not (or is-moonshot? is-together?
                                    is-cloudflare-ai-gateway? is-nvidia?))
     :send-session-affinity-headers false
     :supports-long-cache-retention (not (or is-together? is-cloudflare-workers-ai?
                                             is-cloudflare-ai-gateway? is-nvidia? is-ant-ling?))}))

(defn- compat-delta
  "pi openAICompletionsCompatDelta: only keys differing from the defaults."
  [compat]
  (into {}
        (remove (fn [[k v]] (= v (get openai-completions-default-compat k))))
        compat))

(defn- apply-openai-completions-compat-metadata
  "pi applyOpenAICompletionsCompatMetadata: merge the detected compat under
   the model's explicit compat (explicit wins), emitting only the
   non-default keys."
  [mm]
  (if (= :openai-completions (:api mm))
    (let [detected (compat-delta (detect-openai-completions-compat mm))
          merged (merge detected (:compat mm))]
      (cond-> mm
        (seq merged) (assoc :compat merged)
        (empty? merged) (dissoc :compat)))
    mm))

;; ─── deepseek-v4 compat normalization (pi, after all providers) ────────────

(defn- normalize-deepseek-v4
  "Every openai-completions deepseek-v4 model gets DeepSeek's thinking compat
   (pi, after all providers): opencode/zen and openrouter preserve native
   reasoning effort, so they only gain
   requires-reasoning-content-on-assistant-messages; the qwen-token-plan
   catalogs keep their qwen thinking format entirely."
  [models]
  (map (fn [mm]
         (if (and (= :openai-completions (:api mm))
                  (str/includes? (:id mm) "deepseek-v4")
                  (not (contains? qwen-token-plan-provider-ids (:provider mm))))
           (update mm :compat
                   merge (if (contains? #{:opencode :openrouter} (:provider mm))
                           {:requires-reasoning-content-on-assistant-messages true}
                           deepseek-compat))
           mm))
       models))

;; ─── Thinking level maps (pi applyModelsDevReasoningOptionMetadata +
;;     applyThinkingLevelMetadata, kmet's providers only) ─────────────────────

(defn- merge-thinking-level-map
  [mm extra]
  (update mm :thinking-level-map merge extra))

(defn- merge-compat
  [mm ks]
  (update mm :compat merge ks))

(defn- apply-effort-thinking-map
  "pi applyModelsDevReasoningOptionMetadata: reasoning_options effort values
   → thinking-level-map for openai-completions models using the default
   (openai) thinking format and supporting reasoning_effort (kmet: no
   explicit :thinking-format, :supports-reasoning-effort not false), and
   unconditionally for openai-responses models (pi supportsDirectReasoningEffort
   — the responses API has native reasoning.effort). google/anthropic models
   get their maps from the explicit metadata rules only."
  [mm m]
  (if (and (contains? #{:openai-completions :openai-responses} (:api mm))
           (:reasoning mm)
           ;; pi supportsDirectReasoningEffort: the default (openai) thinking
           ;; format still takes effort maps — :openai counts as unset
           (or (nil? (:thinking-format (:compat mm)))
               (= :openai (:thinking-format (:compat mm))))
           (not= false (:supports-reasoning-effort (:compat mm)))
           (get m "reasoning_options"))
    (if-let [tlm (effort-thinking-level-map (get m "reasoning_options"))]
      (assoc mm :thinking-level-map tlm)
      mm)
    mm))

(defn- supports-openai-xhigh?
  "pi supportsOpenAiXhigh: GPT-5.2+ and GPT-6 Astra expose xhigh."
  [id]
  (or (str/includes? id "gpt-5.2")
      (str/includes? id "gpt-5.3")
      (str/includes? id "gpt-5.4")
      (str/includes? id "gpt-5.5")
      (str/includes? id "gpt-5.6")
      (str/includes? id "gpt-6-astra")))

(defn- supports-openai-max?
  "pi supportsOpenAiMax: GPT-5.6 and GPT-6 Astra on the
   responses/completions family."
  [mm]
  (and (or (str/includes? (:id mm) "gpt-5.6")
           (str/includes? (:id mm) "gpt-6-astra"))
       (contains? #{:openai-responses :azure-openai-responses
                    :openai-codex-responses :openai-completions} (:api mm))))

(defn- apply-thinking-level-metadata
  "pi applyThinkingLevelMetadata rules for kmet's providers (deepseek-v4
   maps, opencode-go glm-5.2/kimi-k2.6, opencode grok-build-0.1, copilot
   claude overrides + adaptive thinking maps, gemini-3 maps)."
  [mm]
  (let [id (:id mm)
        provider (:provider mm)
        deepseek-v4? (and (= :openai-completions (:api mm))
                          (str/includes? id "deepseek-v4"))
        adaptive-high? (or (str/includes? id "opus-4-7")
                           (str/includes? id "opus-4.7")
                           (str/includes? id "opus-4-8")
                           (str/includes? id "opus-4.8")
                           (str/includes? id "opus-5")
                           (str/includes? id "opus.5")
                           (str/includes? id "sonnet-5")
                           (str/includes? id "sonnet.5"))
        adaptive-max? (or (str/includes? id "opus-4-6")
                          (str/includes? id "opus-4.6")
                          (str/includes? id "sonnet-4-6")
                          (str/includes? id "sonnet-4.6")
                          adaptive-high?)
        gemini3-pro? (re-matches #"(?i).*gemini-3(?:\.\d+)?-pro.*" id)
        gemini3-flash? (or (re-matches #"(?i).*gemini-3(?:\.\d+)?-flash.*" id)
                           (= id "gemini-flash-latest")
                           (= id "gemini-flash-lite-latest"))
        gemma4? (re-matches #"(?i).*gemma-?4.*" id)
        copilot-override (get {"claude-opus-4.7" {:minimal "low"}
                               "claude-opus-4.8" {:minimal "low"}
                               "claude-opus-5" {:minimal "low"}
                               "claude-sonnet-4.6" {:minimal "low" :max "max"}}
                              id)]
    (cond-> mm
      ;; openai-responses gpt-5* cannot disable thinking (pi: off: null);
      ;; the explicit none-reasoning set below overrides back to "none".
      (and (contains? #{:openai-responses :azure-openai-responses} (:api mm))
           (str/starts-with? id "gpt-5"))
      (merge-thinking-level-map {:off nil})
      (and (= id "gpt-6-astra")
           (contains? #{:openai-responses :azure-openai-responses
                        :openai-codex-responses}
                      (:api mm)))
      (merge-thinking-level-map {:off nil :minimal nil :low "low" :medium "medium"
                                 :high "high" :xhigh "xhigh" :max "max"})
      (and (= :github-copilot provider) (str/starts-with? id "gpt-5"))
      (merge-thinking-level-map {:minimal "low"})
      (and (= :openai-responses (:api mm))
           (= :openai provider)
           (contains? openai-responses-none-reasoning-models id))
      (merge-thinking-level-map {:off "none"})
      (and (= :xai provider)
           (= :openai-responses (:api mm))
           (= id xai-responses-model-id))
      (merge-thinking-level-map xai-responses-effort-level-map)
      (supports-openai-xhigh? id)
      (merge-thinking-level-map {:xhigh "xhigh"})
      (supports-openai-max? mm)
      (merge-thinking-level-map {:max "max"})
      (and (= :openai-codex provider) (supports-openai-xhigh? id))
      (merge-thinking-level-map {:minimal "low"})
      (and (= :openai provider) (= id "gpt-5.5"))
      (merge-thinking-level-map {:minimal nil})
      (str/ends-with? id "gpt-5.5-pro")
      (merge-thinking-level-map {:off nil :minimal nil :low nil})
      deepseek-v4?
      (merge-thinking-level-map {:minimal nil :low nil :medium nil
                                 :high "high" :max "max"})
      (and (= :opencode-go provider) (= id "glm-5.2"))
      (merge-thinking-level-map {:off nil :minimal nil :low nil :medium nil
                                 :high "high" :max "max"})
      (and (contains? #{:zai :zai-coding-cn} provider) (= id "glm-5.2"))
      (merge-thinking-level-map {:xhigh "xhigh"})
      ;; pi: adaptive thinking models carry the compat (kimi-coding always;
      ;; claude 4.6+ on anthropic-messages) — the xhigh/max maps already
      ;; merge above
      (and (= :anthropic-messages (:api mm)) (or adaptive-max?
                                                 (str/includes? id "fable-5")))
      (merge-compat {:force-adaptive-thinking true})
      (and (= :opencode-go provider) (= id "kimi-k2.6"))
      (merge-thinking-level-map {:minimal nil :low nil :medium nil})
      (and (= :opencode provider) (= id "grok-build-0.1"))
      (merge-thinking-level-map {:off nil :minimal nil :low nil :medium nil})
      adaptive-max?
      (merge-thinking-level-map {:max "max"})
      adaptive-high?
      (merge-thinking-level-map {:xhigh "xhigh" :max "max"})
      (str/includes? id "fable-5")
      (merge-thinking-level-map {:off nil :xhigh "xhigh" :max "max"})
      copilot-override
      (merge-thinking-level-map copilot-override)
      gemini3-pro?
      (merge-thinking-level-map {:off nil :minimal nil :low "LOW"
                                 :medium nil :high "HIGH"})
      gemini3-flash?
      (merge-thinking-level-map {:off nil})
      gemma4?
      (merge-thinking-level-map {:off nil :minimal "MINIMAL" :low nil
                                 :medium nil :high "HIGH"})
      ;; Batch 2 rules (pi applyThinkingLevelMetadata): groq's qwen
      ;; reasoning toggle, fireworks glm-5p2 effort levels, openrouter
      ;; mercury-2 (always-thinking) + z-ai/glm-5.2, the openrouter
      ;; deepseek-v4 variant (native effort + xhigh), and moonshot
      ;; kimi-k2.7-code (always-thinking)
      (and (= :groq provider) (= id "qwen/qwen3.6-27b"))
      (merge-thinking-level-map {:minimal nil :low nil :medium nil
                                 :high "default"})
      (and (= :fireworks provider) (str/includes? id "glm-5p2"))
      (merge-thinking-level-map {:off "none" :minimal nil :low "high"
                                 :medium "high" :max "max"})
      (and (= :openrouter provider) (str/starts-with? id "inception/mercury-2"))
      (merge-thinking-level-map {:off nil})
      (and (= :openrouter provider) (= id "z-ai/glm-5.2"))
      (merge-thinking-level-map {:xhigh "xhigh"})
      (and (= :openrouter provider) deepseek-v4?)
      (merge-thinking-level-map {:xhigh "xhigh" :max nil})
      (and (contains? #{:moonshotai :moonshotai-cn} provider)
           (contains? #{"kimi-k2.7-code" "kimi-k2.7-code-highspeed"} id))
      (merge-thinking-level-map {:off nil}))))

(defn- apply-thinking-maps
  "thinking-level-map pipeline (pi order): effort maps from the models.dev
   reasoning options, then the explicit metadata rules (later merges win)."
  [mm m]
  (-> mm (apply-effort-thinking-map m) apply-thinking-level-metadata))

;; ─── Responses-family compat metadata (pi apply*CompatMetadata passes) ────

(defn- apply-strict-tool-compat
  "pi applyStrictToolCompatMetadata: openai responses models accept strict
   JSON-schema tool definitions."
  [mm]
  (if (and (contains? #{:openai :cloudflare-ai-gateway} (:provider mm))
           (= :openai-responses (:api mm)))
    (merge-compat mm {:supports-strict-mode true})
    mm))

(defn- apply-openai-grammar-tool-compat
  "pi applyOpenAIGrammarToolCompatMetadata: responses endpoints verified to
   pass OpenAI custom grammar tools through — gpt-5+ only (OpenAI rejects
   type: 'custom' tools for pre-GPT-5 models)."
  [mm]
  (if (and (contains? #{:openai-responses :azure-openai-responses
                        :openai-codex-responses}
                      (:api mm))
           (contains? #{:openai :openai-codex :azure-openai-responses
                        :github-copilot :opencode :cloudflare-ai-gateway}
                      (:provider mm))
           (let [match (re-matches #"gpt-(\d+).*" (:id mm))]
             (and match (<= 5 (Long/parseLong (second match))))))
    (merge-compat mm {:supports-openai-grammar-tools true})
    mm))

(def ^:private openai-codex-additional-tools-model-ids
  #{"gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna" "gpt-6-astra"})

(defn- apply-openai-tool-search-metadata
  "pi applyOpenAIToolSearchMetadata: newer OpenAI Responses models can load
   tools at a specific point in the input (tool search) and accept
   additional_tools items (the Codex Responses family has its own GPT-5.6
   and GPT-6 Astra additional-tools set)."
  [mm]
  (let [is-openai? (and (= :openai (:provider mm)) (= :openai-responses (:api mm)))
        is-codex? (and (= :openai-codex (:provider mm))
                       (= :openai-codex-responses (:api mm)))]
    (if (and (or is-openai? is-codex?)
             (contains? openai-tool-search-model-ids (:id mm)))
      (merge-compat mm
                    (cond-> {:supports-tool-search true}
                      (or (and is-openai?
                               (contains? openai-additional-tools-model-ids (:id mm)))
                          (and is-codex?
                               (contains? openai-codex-additional-tools-model-ids (:id mm))))
                      (assoc :supports-additional-tools true)))
      mm)))

(defn- apply-openai-explicit-prompt-cache-metadata
  "pi applyOpenAIExplicitPromptCacheMetadata: OpenAI charges prompt-cache
   writes starting with the GPT-5.6 family, and exactly those models accept
   prompt_cache_options (older models reject the parameter)."
  [mm]
  (if (and (= :openai (:provider mm)) (= :openai-responses (:api mm))
           (pos? (or (get-in mm [:cost :cache-write]) 0)))
    (merge-compat mm {:supports-explicit-prompt-cache-mode true})
    mm))

(defn- apply-compat-metadata
  "The post-processing compat passes (pi order: URL detection, strict tools,
   grammar tools, tool search, explicit prompt cache)."
  [mm]
  (-> mm apply-openai-completions-compat-metadata
      apply-strict-tool-compat
      apply-openai-grammar-tool-compat
      apply-openai-tool-search-metadata
      apply-openai-explicit-prompt-cache-metadata))

;; ─── Post-merge overrides (pi generateModels temporary overrides) ──────────

(defn- normalize-openai
  "Keep direct OpenAI requests in the short-context pricing tier by default
   (users can opt into the larger context through model overrides, so the
   long-context cost metadata stays on the capped models); apply the
   long-context pricing tiers and the reduced GPT-5.6 standard costs."
  [mm]
  (let [id (:id mm)]
    (cond-> mm
      (and (= :openai (:provider mm))
           (contains? openai-short-context-capped-model-ids id))
      (assoc :context-window openai-long-context-input-threshold
             :max-tokens 128000)

      (and (= :openai (:provider mm))
           (contains? openai-long-context-pricing-model-ids id))
      (assoc :cost (with-openai-long-context-pricing
                     (or (get openai-gpt-56-standard-costs id)
                         (:cost mm))))

      ;; models.dev reports gpt-5-pro output as 272000 (a duplicate of the
      ;; input sub-limit); the actual max output is 128000.
      (and (= :openai (:provider mm)) (= id "gpt-5-pro"))
      (assoc :max-tokens 128000))))

(defn- normalize-context-overrides
  "pi generateModels override loop for kmet's providers: the opencode
   variants' claude/gpt-5.4 limits, copilot's extended-context models, the
   opencode 1M-context claude pair, and the azure deployment context
   windows."
  [mm]
  (let [id (:id mm)
        prov (:provider mm)
        variant? (contains? #{:opencode :opencode-go} prov)]
    (cond-> mm
      (and (= :azure-openai-responses prov)
           (contains? azure-context-window-overrides id))
      (assoc :context-window (get azure-context-window-overrides id))

      (and (= :github-copilot prov)
           (contains? github-copilot-extended-context-models id))
      (assoc :context-window 1000000)

      (and variant? (contains? #{"claude-opus-4-6" "claude-sonnet-4-6"
                                 "claude-opus-4.6" "claude-sonnet-4.6"} id))
      (assoc :context-window 1000000)

      ;; OpenCode variants list Claude Sonnet 4/4.5 with 1M context, actual
      ;; limit is 200K.
      (and variant? (contains? #{"claude-sonnet-4-5" "claude-sonnet-4"} id))
      (assoc :context-window 200000)

      (and variant? (= id "gpt-5.4"))
      (assoc :context-window 272000 :max-tokens 128000))))

;; ─── Generation ────────────────────────────────────────────────────────────

;; ─── commandcode (Command Code Provider API — not on models.dev) ──────────
;;     The live /models list decides inclusion and supplies id/name/context;
;;     every other capability transfers from kmet's canonical catalogs via
;;     the identity refs below, so nothing is guessed. Pricing is ignored:
;;     CommandCode bills through plans and credits, not token rates.

(def ^:private commandcode-base-url "https://api.commandcode.ai/provider/v1")

(def ^:private commandcode-anthropic-base-url
  "Anthropic Messages base (endpoint-url appends /v1/messages); claude-*
   models route here, everything else through the OpenAI-compatible base."
  "https://api.commandcode.ai/provider")

(def ^:private commandcode-free-cost
  "Zero rates across the board — see the section banner."
  (array-map :input 0 :output 0 :cache-read 0 :cache-write 0))

(defn- fetch-commandcode-models
  "The live CommandCode /models list ({id name context_length} maps;
   throws on failure, like fetch-models-dev). Authenticated with CMD_API_KEY
   when the env var is set (richer fields may arrive for authenticated
   callers later), anonymous otherwise."
  []
  (let [headers (merge {"User-Agent" "kmet-generate-models"}
                       (when-some [key (not-empty (System/getenv "CMD_API_KEY"))]
                         {"Authorization" (str "Bearer " key)}))
        resp (http/get (str commandcode-base-url "/models")
                       {:headers headers :timeout 60000})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "CommandCode API returned HTTP " (:status resp))
                      {:type :http-error :status (:status resp)})))
    (let [models (get (json/parse-string (:body resp) false) "data")]
      (when-not (vector? models)
        (throw (ex-info "Invalid CommandCode models response" {:type :http-error})))
      models)))

(defn- fetch-commandcode-prices*
  "CommandCode's published per-model rates ($/M), decoded from the
   commandcode.ai /models page payload — the provider API itself carries no
   rate fields (billing goes through plans and credits). The page embeds a
   React Router turbo-stream graph; we resolve it generically and keep the
   four rate fields per id. Pricing is optional data: any failure (HTTP,
   payload shape, decode) prints a warning and returns nil so generation
   degrades to zero rates instead of aborting."
  []
  (try
    (let [resp (http/get "https://commandcode.ai/models"
                         {:headers {"User-Agent" "kmet-generate-models"}
                          :timeout 60000})]
      (when-not (= 200 (:status resp))
        (throw (ex-info (str "commandcode.ai/models returned HTTP " (:status resp))
                        {:type :http-error :status (:status resp)})))
      ;; payload sits in streamController.enqueue("<escaped>")
      (let [[_ raw] (re-find #"(?s)streamController\.enqueue\(\"(.*?)\"\);" (:body resp))]
        (when-not raw
          (throw (ex-info "no stream payload found" {:type :http-error})))
        (let [arr (json/parse-string (json/parse-string (str "\"" raw "\"")))
              node (fn node [i]
                     (cond
                       (not (int? i)) nil
                       (neg? i) nil
                       :else (let [x (nth arr i)]
                               (cond
                                 (vector? x) (mapv #(node (if (int? %) % nil)) x)
                                 (map? x) (into {}
                                                (for [[k v] x]
                                                  [(node (parse-long (subs k 1)))
                                                   (if (int? v) (node v) v)]))
                                 :else x))))
              root (node 0)
              models (get-in root ["loaderData" "routes/models/index" "models"])
              rate (fn [m k] (let [v (get m k)] (if (number? v) v 0)))]
          (when-not (vector? models)
            (throw (ex-info "unexpected payload shape" {:type :http-error})))
          (into {}
                (for [m models
                      :when (string? (get m "id"))]
                  [(get m "id")
                   (array-map :input (rate m "inputCost")
                              :output (rate m "outputCost")
                              :cache-read (rate m "cacheReadCost")
                              :cache-write (rate m "cacheWriteCost"))])))))
    (catch Exception e
      (println (str "Warning: CommandCode price fetch failed ("
                    (ex-message e) ") — models will carry zero rates."))
      nil)))

(def ^:private commandcode-prices-timeout-ms
  "Wall-clock cap (ms) for the CommandCode price fetch. On timeout
   generation proceeds with zero rates (like any other price-fetch
   failure)."
  90000)

(defn fetch-commandcode-prices
  "Bounded wrapper over fetch-commandcode-prices*: the rates map, or nil
   when the fetch fails or exceeds commandcode-prices-timeout-ms —
   generation then proceeds with zero rates instead of blocking."
  []
  (let [f (future (fetch-commandcode-prices*))
        ;; :timeout bounds the wait for response HEADERS only — the body
        ;; read below it is unbounded, so this deref cap is the backstop
        ;; that keeps a stalled body from blocking generation forever.
        res (deref f commandcode-prices-timeout-ms ::timeout)]
    (if (= ::timeout res)
      (do (future-cancel f)
          (println (str "Warning: CommandCode price fetch timed out after "
                        (quot commandcode-prices-timeout-ms 1000)
                        "s — models will carry zero rates."))
          nil)
      res)))

(def ^:private commandcode-canonical-refs
  "CommandCode id -> [provider-id canonical-model-id]: the SAME WEIGHTS as
   kmet's curated catalogs list them, so :reasoning/:input/
   :thinking-level-map/:max-tokens transfer from canonical data instead of
   guesses. Identity mapping only -- wire api is NOT transferred (canonical
   :openai is responses-api; CommandCode speaks completions/messages).
   tencent/hy3-paid has no canonical entry anywhere -> conservative defaults."
  (array-map
   "claude-fable-5"                    [:anthropic "claude-fable-5"]
   "claude-haiku-4-5-20251001"         [:anthropic "claude-haiku-4-5-20251001"]
   "claude-opus-4-7"                   [:anthropic "claude-opus-4-7"]
   "claude-opus-4-8"                   [:anthropic "claude-opus-4-8"]
   "claude-opus-5"                     [:anthropic "claude-opus-5"]
   "claude-sonnet-4-6"                 [:anthropic "claude-sonnet-4-6"]
   "claude-sonnet-5"                   [:anthropic "claude-sonnet-5"]
   "deepseek/deepseek-v4-flash"        [:deepseek "deepseek-flash"]
   "deepseek/deepseek-v4-flash-fast"   [:deepseek "deepseek-flash"]
   "deepseek/deepseek-v4-flash-vision-exp" [:opencode-go "deepseek-v4-flash-vision-exp"]
   "deepseek/deepseek-v4-pro"          [:deepseek "deepseek-v4-pro"]
   ;; pi 12f59336a: the retired v4-flash aliases collapse into DeepSeek's
   ;; canonical `deepseek-flash` (V4.1 Flash, text+image); clamping to
   ;; :deepseek keeps the ref off rename-prone aggregator ids (opencode-go
   ;; renamed its copy to "deepseek-v4.1-flash" and broke the old ref).
   "deepseek/deepseek-v4.1-flash"      [:deepseek "deepseek-flash"]
   "google/gemini-3.1-flash-lite"      [:google "gemini-3.1-flash-lite"]
   "google/gemini-3.5-flash"           [:google "gemini-3.5-flash"]
   "google/gemini-3.5-flash-lite"      [:google "gemini-3.5-flash-lite"]
   "google/gemini-3.6-flash"           [:google "gemini-3.6-flash"]
   "google/gemini-3.7-flash"           [:google "gemini-3.7-flash"]
   "gpt-5.3-codex"                     [:openai "gpt-5.3-codex"]
   "gpt-5.4"                           [:openai "gpt-5.4"]
   "gpt-5.4-mini"                      [:openai "gpt-5.4-mini"]
   "gpt-5.5"                           [:openai "gpt-5.5"]
   "gpt-5.6-luna"                      [:openai "gpt-5.6-luna"]
   "gpt-5.6-sol"                       [:openai "gpt-5.6-sol"]
   "gpt-5.6-terra"                     [:openai "gpt-5.6-terra"]
   "meta/muse-spark-1.1"               [:openrouter "meta/muse-spark-1.1"]
   "meta/muse-spark-1.2"               [:openrouter "meta/muse-spark-1.2"]
   "meta/muse-spark-1.2-contributor"   [:opencode-go "muse-spark-1.2-contributor"]
   "meta/muse-spark-1.3"               [:openrouter "meta/muse-spark-1.3"]
   "meta/muse-spark-1.3-contributor"   [:opencode-go "muse-spark-1.3-contributor"]
   "MiniMaxAI/MiniMax-M2.5"            [:qwen-token-plan "MiniMax-M2.5"]
   "MiniMaxAI/MiniMax-M2.7"            [:minimax "MiniMax-M2.7"]
   "MiniMaxAI/MiniMax-M3"              [:minimax "MiniMax-M3"]
   "moonshotai/Kimi-K2.5"              [:baseten "moonshotai/Kimi-K2.5"]
   "moonshotai/Kimi-K2.6"              [:moonshotai "kimi-k2.6"]
   "moonshotai/Kimi-K2.7-Code"         [:moonshotai "kimi-k2.7-code"]
   "moonshotai/Kimi-K2.7-Code-Highspeed" [:moonshotai "kimi-k2.7-code-highspeed"]
   "moonshotai/Kimi-K3"                [:moonshotai "kimi-k3"]
   "nvidia/nemotron-3-ultra-550b-a55b" [:nvidia "nvidia/nemotron-3-ultra-550b-a55b"]
   "poolside/laguna-s-2.1-free"        [:openrouter "poolside/laguna-s-2.1:free"]
   "Qwen/Qwen3.6-Max-Preview"          [:openrouter "qwen/qwen3.6-max-preview"]
   "Qwen/Qwen3.6-Plus"                 [:qwen-token-plan "qwen3.6-plus"]
   "Qwen/Qwen3.7-Flash"                [:openrouter "qwen/qwen3.7-flash"]
   "Qwen/Qwen3.7-Max"                  [:qwen-token-plan "qwen3.7-max"]
   "Qwen/Qwen3.7-Plus"                 [:qwen-token-plan "qwen3.7-plus"]
   "Qwen/Qwen3.8-27B"                  [:openrouter "qwen/qwen3.8-27b"]
   "Qwen/Qwen3.8-Max"                  [:qwen-token-plan "qwen3.8-max"]
   "sakana/fugu-ultra"                 [:openrouter "sakana/fugu-ultra"]
   "stepfun/Step-3.5-Flash"            [:openrouter "stepfun/step-3.5-flash"]
   "stepfun/Step-3.7-Flash"            [:openrouter "stepfun/step-3.7-flash"]
   "thinkingmachines/inkling"          [:baseten "thinkingmachines/inkling"]
   "thinkingmachines/inkling-small"    [:openrouter "thinkingmachines/inkling-small"]
   "xai/grok-4.5"                      [:xai "grok-4.5"]
   "xai/grok-4.6"                      [:xai "grok-4.6"]
   "xiaomi/mimo-v2.5"                  [:xiaomi "mimo-v2.5"]
   "xiaomi/mimo-v2.5-pro"              [:xiaomi "mimo-v2.5-pro"]
   "zai-org/GLM-5"                     [:baseten "zai-org/GLM-5"]
   "zai-org/GLM-5.1"                   [:baseten "zai-org/GLM-5.1"]
   "zai-org/GLM-5.2"                   [:zai "glm-5.2"]
   "zai-org/GLM-5.2-Fast"              [:baseten "zai-org/GLM-5.2-Fast"]
   "zai-org/GLM-5.3"                   [:zai "glm-5.3"]))

(def ^:private commandcode-no-compat-refs
  "Ref entries whose :thinking-format is endpoint-bound rather than
   model-bound (baseten's GLM/Kimi handling; the qwen-token-plan catalog hosts
   non-Qwen models whose qwen format does not follow the weights)."
  #{"MiniMaxAI/MiniMax-M2.5"
    "moonshotai/Kimi-K2.5"
    "zai-org/GLM-5"
    "zai-org/GLM-5.1"
    "zai-org/GLM-5.2-Fast"})

(def ^:private commandcode-price-aliases
  "Endpoint ids whose pricing lives under a different id on the /models
   page (the API lists Anthropic's dated alias, the page the bare family
   name). Consulted when a direct price lookup misses."
  {"claude-haiku-4-5-20251001" "claude-haiku-4-5"})

(def ^:private commandcode-overrides
  "Explicit capability wins over refs. Empty today — stealth/ox-alpha, the
   only entry, is no longer served by the CommandCode endpoint. Kept as the
   hook for models whose capabilities are endpoint-bound rather than
   model-bound."
  {})

(defn- warn-unresolved-commandcode-ref!
  "Warn when a LISTED canonical ref resolves to nil in this run's catalogs:
   the endpoint entry then silently falls back to conservative defaults
   (:reasoning false, text-only, 32768 max-tokens). The deepseek-v4.1-flash
   regression shipped exactly this way — the opencode-go canonical id was
   renamed and the ref kept pointing at the old one."
  [cid rp rid]
  (println (str "Warning: CommandCode canonical ref " cid " -> " rp " " rid
                " did not resolve — falling back to conservative defaults.")))

(defn- process-commandcode
  "Live CommandCode entries × the canonical groups built this run → kmet
   model maps. Capabilities resolve per id: explicit override > canonical
   ref (model-bound compat keys only) > conservative defaults (no reasoning,
   text-only). Unlisted endpoint ids still ship with defaults; listed ids
   missing from the live list disappear (the endpoint decides inclusion)."
  [fetched grouped prices]
  (for [m fetched
        :let [cid (get m "id")
              [rp rid] (get commandcode-canonical-refs cid)
              ref (when rp (get-in grouped [rp rid]))
              _ (when (and rp (nil? ref))
                  (warn-unresolved-commandcode-ref! cid rp rid))
              ov (get commandcode-overrides cid)
              api (if (str/starts-with? (or cid "") "claude")
                    :anthropic-messages
                    :openai-completions)]
        :when cid]
    (let [{ref-reasoning :reasoning
           ref-input :input
           ref-tlm :thinking-level-map
           ref-mt :max-tokens
           ref-compat :compat} ref
          compat (when-not (contains? commandcode-no-compat-refs cid)
                   (select-keys ref-compat [:thinking-format :force-adaptive-thinking]))
          cap (merge {:api api
                      :reasoning (boolean ref-reasoning)
                      :input (or ref-input [:text])
                      :max-tokens (or ref-mt 32768)}
                     (when (seq compat) {:compat compat})
                     (when ref-tlm {:thinking-level-map ref-tlm})
                     ov)
          cost (or (get prices cid)
                   (get prices (get commandcode-price-aliases cid))
                   commandcode-free-cost)]
      (cond-> (array-map
               :id cid
               :name (or (get m "name") cid)
               :provider :commandcode
               :api api
               :base-url (if (= :anthropic-messages api)
                           commandcode-anthropic-base-url
                           commandcode-base-url)
               :reasoning (:reasoning cap)
               :input (:input cap)
               :cost cost
               :context-window (long (or (get m "context_length") 32768))
               :max-tokens (:max-tokens cap))
        (:thinking-level-map cap) (assoc :thinking-level-map (:thinking-level-map cap))
        (:compat cap) (assoc :compat (:compat cap))))))

(defn- fetch-models-dev
  []
  (let [resp (http/get "https://models.dev/api.json"
                       {:headers {"User-Agent" "kmet-generate-models"}
                        :timeout 60000})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "models.dev returned HTTP " (:status resp))
                      {:type :http-error :status (:status resp)})))
    (json/parse-string (:body resp) false)))

(defn- models-by-id
  "Vector of model maps → {model-id -> model}."
  [models]
  (into {} (map (juxt :id identity)) models))

(defn- group-models
  "Vector of model maps → {api -> {model-id -> model}} (the validate-groups!
   shape, matching what read-catalogs produces for the offline check)."
  [models]
  (into {} (for [[api ms] (group-by :api models)]
             [api (models-by-id ms)])))

(defn- provider-model-index
  "Vector of model maps → {provider -> {model-id -> model}} — the shape
   process-commandcode's canonical-ref lookup (get-in grouped [provider
   model-id]) needs. A bare group-by :provider leaves vectors under each
   provider, and every ref silently resolves to nil (regression: all
   commandcode models lost their :reasoning/:thinking-level-map/:max-tokens
   and fell back to conservative defaults)."
  [models]
  (into {} (for [[pid ms] (group-by :provider models)]
             [pid (models-by-id ms)])))

(defn- generate-models-data
  "Build {provider-id -> [model-map ...]} from a models.dev payload + the
   live fetch results (pi generateModels order: all model sources, the
   minimax direct-supported filter, context/cost overrides, missing gpt
   models, codex + the azure mirror, deepseek-v4 compat normalization, then
   the metadata passes)."
  [data nim-ids openrouter-models ai-gateway-models
   commandcode-fetched commandcode-prices]
  (let [all (->> (concat (process-opencode data)
                         (process-copilot data)
                         (process-xai data)
                         (process-openai data)
                         (process-anthropic data)
                         (process-google data)
                         (process-groq data)
                         (process-cerebras data)
                         (process-huggingface data)
                         (process-moonshot data)
                         (process-xiaomi data)
                         (process-qwen-token-plan data)
                         (process-minimax data)
                         (process-nvidia data nim-ids)
                         (process-fireworks data)
                         (process-zai data)
                         (process-together data)
                         (process-baseten data)
                         (process-kimi-coding data)
                         (process-cloudflare-workers-ai data)
                         (process-cloudflare-ai-gateway data)
                         (process-mistral data)
                         (process-google-vertex data)
                         (process-amazon-bedrock data)
                         (map #(apply-thinking-maps % nil) (process-openrouter openrouter-models))
                         (map #(apply-thinking-maps % nil)
                              (process-vercel-ai-gateway ai-gateway-models))
                         (map #(apply-thinking-maps % nil) (process-ant-ling))
                         (map #(apply-thinking-maps % nil) deepseek-models)
                         (map #(apply-thinking-maps % nil) (process-codex)))
                 (remove nil?))
        ;; pi: mistral-medium-3.5 is hardcoded until models.dev includes it
        all (if (some #(and (= :mistral (:provider %))
                            (= "mistral-medium-3.5" (:id %)))
                      all)
              all
              (concat all (map #(apply-thinking-maps % nil)
                               [mistral-medium-35-model])))
        ;; pi: minimax models are filtered to the ids the direct API serves
        ;; (MiniMax-M2.7/-highspeed/M3) after all sources
        minimax-supported (set minimax-direct-supported-ids)
        all (remove (fn [mm]
                      (and (contains? #{:minimax :minimax-cn} (:provider mm))
                           (not (contains? minimax-supported (:id mm)))))
                    all)
        ;; Hardcoded fallbacks still get the thinking metadata rules (pi runs
        ;; applyThinkingLevelMetadata over allModels — the missing models
        ;; carry no models.dev reasoning_options, so no effort map).
        with-missing (concat all (map #(apply-thinking-maps % nil)
                                      (missing-openai-models (set (map :id all)))))
        normalized (map #(-> % normalize-openai normalize-context-overrides)
                        with-missing)
        ;; the azure mirror derives from the normalized openai models (pi:
        ;; after the section overrides, before the metadata passes) and gets
        ;; the thinking metadata rules on top (off:null for gpt-5* etc.)
        azure (map #(apply-thinking-maps % nil) (process-azure normalized))
        ;; commandcode resolves against the canonical groups built above (its
        ;; refs point into them), then joins the pipeline so the shared
        ;; passes (deepseek-v4 compat normalization) still apply. The ref
        ;; lookup (get-in grouped [rp rid]) needs the {provider ->
        ;; {model-id -> model}} shape (provider-model-index) — a bare
        ;; group-by :provider leaves vectors and every ref silently resolves
        ;; to nil (regression: all commandcode models lost their
        ;; :reasoning/:thinking-level-map/:max-tokens and fell back to
        ;; conservative defaults).
        base-models (->> (concat normalized azure)
                         (map apply-compat-metadata)
                         normalize-deepseek-v4)
        base-grouped (group-by :provider base-models)
        ref-groups (provider-model-index base-models)
        grouped (assoc base-grouped :commandcode
                       (->> (process-commandcode commandcode-fetched ref-groups
                                                 commandcode-prices)
                            normalize-deepseek-v4))]
    (into (array-map)
          (for [[pid _] providers]
            [pid (get grouped pid [])]))))

;; ─── Strict validation (pi validateModelValue / validateModelDataDirectory) ─

(defn- validate-groups!
  "Strict per-model validation of one provider's {api -> {model-id -> model}}.
   Returns the error strings (pi collects and reports all of them)."
  [pid groups]
  (let [errors (volatile! [])
        fail! (fn [label msg] (vswap! errors conj (str label ": " msg)))]
    (doseq [[api models] groups
            [mid mm] models]
      (let [label (str pid "/" mid)]
        (when-not (= mid (:id mm))
          (fail! label (str "has id " (pr-str (:id mm)) ", expected " (pr-str mid))))
        (when-not (= pid (:provider mm))
          (fail! label (str "has provider " (pr-str (:provider mm)) ", expected " (pr-str pid))))
        (when-not (= api (:api mm))
          (fail! label (str "has api " (pr-str (:api mm)) ", expected " (pr-str api))))
        (when-not (and (string? (:name mm)) (seq (:name mm)))
          (fail! label "has no model name"))
        (when-not (string? (:base-url mm))
          (fail! label "has no base-url string"))
        (when-not (boolean? (:reasoning mm))
          (fail! label "has no reasoning boolean"))
        (when-not (and (vector? (:input mm)) (seq (:input mm))
                       (every? #{:text :image} (:input mm)))
          (fail! label "has invalid input modalities"))
        (when-not (and (number? (:context-window mm)) (pos? (:context-window mm)))
          (fail! label "has invalid context-window"))
        (when-not (and (number? (:max-tokens mm)) (pos? (:max-tokens mm)))
          (fail! label "has invalid max-tokens"))
        (when-not (and (map? (:cost mm))
                       (every? (fn [k] (let [v (get-in mm [:cost k])]
                                         (and (number? v) (not (neg? v)))))
                               [:input :output :cache-read :cache-write])
                       (let [tiers (get-in mm [:cost :tiers])]
                         (or (nil? tiers)
                             (and (vector? tiers)
                                  (every? (fn [tier]
                                            (and (map? tier)
                                                 (number? (get tier :input-tokens-above))
                                                 (pos? (get tier :input-tokens-above))
                                                 (every? (fn [k] (let [v (get tier k)]
                                                                   (and (number? v) (not (neg? v)))))
                                                         [:input :output :cache-read :cache-write])))
                                          tiers)))))
          (fail! label "has invalid cost (4 non-negative numeric fields + optional context tiers)"))))
    (doseq [[api models] groups]
      (doseq [dup (->> (vals (group-by key models))
                       (filter #(< 1 (count %))))]
        (fail! (str pid "/" (ffirst dup))
               (str "duplicate model id in api group " (name api)))))
    (let [all-ids (mapcat keys (vals groups))]
      (doseq [dup (->> (group-by identity all-ids) vals (filter #(< 1 (count %))))]
        (fail! (str pid "/" (first dup)) "model id appears in more than one api group")))
    @errors))

(defn- structure-hash
  "sha256 over the canonical sorted {provider -> {model-id -> api}} (pi
   modelDataStructureHash; identical to kmet.app.models/catalog-structure so
   the offline manifest check agrees)."
  [catalogs]
  (let [structure (into (sorted-map)
                        (for [[pid models] catalogs]
                          [(name pid)
                           (into (sorted-map)
                                 (for [[api ms] (group-by :api models)
                                       mm ms]
                                   [(name (:id mm)) (name api)]))]))]
    (sha256-hex (pr-str structure))))

;; ─── Catalog assembly + writing ────────────────────────────────────────────

(defn- catalog-blob
  [pid models generated]
  (array-map :schema-version 1
             :generated-at generated
             :provider (array-map :id pid
                                  :name (get-in providers [pid :name])
                                  :env-vars (get-in providers [pid :env-vars])
                                  :default-model (get-in providers [pid :default-model]))
             :models (into (array-map)
                           (for [[api ms] (sort-by (comp name key) (group-by :api models))]
                             [api (into (array-map)
                                        (for [mm (sort-by :id ms)]
                                          [(:id mm) mm]))]))))

(defn- read-catalogs
  "Catalogs in DIR as {provider-id -> {api -> {model-id -> model}}},
   sorted by filename (manifest.edn excluded)."
  [dir]
  (into (array-map)
        (for [f (->> (fs/list-dir dir)
                     (filter fs/regular-file?)
                     (map fs/file)
                     (filter #(str/ends-with? (str %) ".edn"))
                     (remove #(= "manifest.edn" (fs/file-name %)))
                     (sort-by fs/file-name))
              :let [data (edn/read-string (slurp f))]]
          [(get-in data [:provider :id]) (:models data)])))

(defn- catalog-file-contents
  "Catalog filenames + raw content in DIR, sorted by filename (manifest.edn
   excluded)."
  [dir]
  (for [f (->> (fs/list-dir dir)
               (filter fs/regular-file?)
               (map fs/file)
               (filter #(str/ends-with? (str %) ".edn"))
               (remove #(= "manifest.edn" (fs/file-name %)))
               (sort-by fs/file-name))]
    [(fs/file-name f) (slurp f)]))

(defn- flatten-catalogs
  "{provider-id -> {api -> {model-id -> model}}} → {provider-id -> [model ...]}
   (the generate-models-data shape, for structure-hash)."
  [catalogs]
  (into {} (for [[pid groups] catalogs]
             [pid (vec (mapcat (fn [[_ ms]] (vals ms)) groups))])))

(defn validate-committed!
  "Offline strict validation of the committed catalog files + manifest
   consistency (pi check-model-data.ts). Returns the error strings. With
   DIR, validates that directory instead (tests)."
  ([] (validate-committed! data-dir))
  ([dir]
   (let [catalogs (read-catalogs dir)
         errors (volatile! (into []
                                 (mapcat (fn [[pid groups]]
                                           (if (seq groups)
                                             (validate-groups! pid groups)
                                             [(str pid ": catalog has no :models groups")]))
                                         catalogs)))
         manifest (try (edn/read-string (slurp (str dir "/manifest.edn")))
                       (catch Exception _ nil))]
     (if (and (map? manifest) (:structure-hash manifest) (:files manifest))
       (do (when-not (= (:structure-hash manifest) (structure-hash (flatten-catalogs catalogs)))
             (vswap! errors conj "manifest structure-hash does not match the catalog structure"))
           (doseq [[fname content] (catalog-file-contents dir)]
             (when-not (= (get-in manifest [:files fname]) (sha256-hex content))
               (vswap! errors conj (str fname " does not match its manifest hash")))))
       (vswap! errors conj "manifest.edn is missing or incomplete (needs :structure-hash and :files)"))
     @errors)))

(defn- read-edn-file
  "Parse the EDN file at PATH, nil when unreadable."
  [path]
  (try (edn/read-string (slurp path))
       (catch Exception _ nil)))

(defn write-catalogs!
  "Write DIR's per-provider catalog files + manifest.edn, then run the same
   offline validation gate as CI over what was written (throws on failure).
   Content-sensitive: each catalog is first rendered with its OWN on-disk
   :generated-at, so a catalog whose data didn't change renders
   byte-identical to the file on disk and is not rewritten — rerunning the
   generator without upstream changes leaves every file (and mtime)
   untouched instead of stamping timestamp-only diffs into git. Only the
   catalogs whose data really changed are re-rendered with the fresh
   timestamp; the manifest follows suit. Returns the number of files written
   (0 = nothing changed)."
  [dir catalogs]
  (fs/create-dirs dir)
  (let [existing (into {} (catalog-file-contents dir))
        ;; catalog-file-contents excludes the manifest — read it raw for
        ;; the no-op comparison
        manifest-on-disk (try (slurp (str dir "/manifest.edn"))
                              (catch Exception _ nil))
        generated (generated-at)
        prev-manifest-generated (or (:generated-at (read-edn-file (str dir "/manifest.edn")))
                                    generated)
        ;; Per-file decision: render with the file's own timestamp so an
        ;; unchanged catalog compares equal. A single global timestamp (e.g.
        ;; the manifest's) would diverge from unchanged files after any
        ;; partial update and flag every file as changed on the next run.
        file-contents (into (array-map)
                            (for [[pid models] catalogs]
                              (let [fname (str (name pid) ".edn")
                                    old-content (get existing fname)
                                    old-generated (or (:generated-at
                                                       (when old-content
                                                         (try (edn/read-string old-content)
                                                              (catch Exception _ nil))))
                                                      prev-manifest-generated)
                                    unchanged? (and old-content
                                                    (= (str (edn-w/render
                                                             (catalog-blob pid models old-generated))
                                                            "\n")
                                                       old-content))]
                                [fname (if unchanged?
                                         old-content
                                         (str (edn-w/render
                                               (catalog-blob pid models generated))
                                              "\n"))])))
        changed-names (vec (for [[fname content] file-contents
                                 :when (not= content (get existing fname))]
                             fname))
        manifest (array-map :schema-version 1
                            :generated-at (if (seq changed-names) generated prev-manifest-generated)
                            :structure-hash (structure-hash catalogs)
                            :files (into (sorted-map)
                                         (for [[fname content] file-contents]
                                           [fname (sha256-hex content)])))
        manifest-content (str (edn-w/render manifest) "\n")
        to-write (cond-> (into (array-map)
                               (for [[fname content] file-contents
                                     :when (not= content (get existing fname))]
                                 [fname content]))
                   (not= manifest-content manifest-on-disk)
                   (assoc "manifest.edn" manifest-content))]
    (doseq [[fname content] to-write]
      (spit (str dir "/" fname) content))
    ;; Safety: the regenerated files must pass the same offline gate as CI.
    (let [errors (validate-committed! dir)]
      (when (seq errors)
        (throw (ex-info (str "Regenerated catalogs failed validation:\n"
                             (str/join "\n" errors))
                        {:type :catalog-invalid}))))
    (count to-write)))

(defn generate-and-write!
  "Full generation pipeline into DIR: fetch models.dev + the live provider
   catalogs, build + strictly validate every catalog, then write the EDN
   files + manifest. Prints progress and a per-provider summary; on invalid
   or empty generations nothing is written and the errors are printed.
   Returns {:ok true :catalogs catalogs} or {:ok false :errors errors} —
   callers decide the exit code (`bb generate-models` and
   `kmet --generate-models` exit 1 on failure)."
  [dir]
  (println "Fetching https://models.dev/api.json ...")
  (let [data (fetch-models-dev)
        nim-ids (do (println "Fetching NVIDIA NIM model ids ...")
                    (fetch-nvidia-nim-model-ids))
        openrouter-models (do (println "Fetching OpenRouter models ...")
                              (fetch-openrouter-models))
        ai-gateway-models (do (println "Fetching Vercel AI Gateway models ...")
                              (fetch-ai-gateway-models))
        commandcode-fetched (do (println "Fetching CommandCode models ...")
                                (fetch-commandcode-models))
        commandcode-prices (do (println "Fetching CommandCode prices ...")
                               (fetch-commandcode-prices))
        catalogs (generate-models-data data nim-ids openrouter-models
                                       ai-gateway-models commandcode-fetched
                                       commandcode-prices)
        errors (into []
                     (mapcat (fn [[pid models]]
                               (validate-groups! pid (group-models models)))
                             catalogs))
        empties (for [[pid models] catalogs :when (empty? models)] (name pid))]
    (cond
      (seq errors)
      (do (println "Invalid generated model data:")
          (run! #(println "  -" %) errors)
          {:ok false :errors errors})

      (seq empties)
      (do (println (str "Provider(s) generated no models: " (str/join ", " empties)
                        " — models.dev may have dropped or restructured the provider;"))
          (println "  - fix the script before writing.")
          {:ok false :errors empties})

      :else
      (let [written (write-catalogs! dir catalogs)]
        (if (zero? written)
          (println "Provider catalogs under" dir "/ already up to date — nothing written.")
          (println "Generated provider catalogs under" dir "/"))
        (doseq [[pid models] catalogs]
          (println (format "  %-16s %d models" (name pid) (count models))))
        (when (pos? written)
          (println (str "Manifest written. " written " file(s) changed.")))
        {:ok true :catalogs catalogs}))))

(defn -main
  "Fetch models.dev + the live catalogs, regenerate the committed catalogs
   + manifest, validate. Exits 1 when generation fails validation."
  [& _]
  (when-not (:ok (generate-and-write! data-dir))
    (System/exit 1)))
