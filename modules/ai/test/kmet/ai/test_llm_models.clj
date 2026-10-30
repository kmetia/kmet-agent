(ns kmet.ai.test-llm-models
  "Model resolution and capabilities: dispatch by api, endpoint URLs, thinking
   levels and request shaping, header merging, and compat auto-detection."
  (:require [clojure.test :as t]
            [kmet.ai.auth :as auth]
            [kmet.libs.dynamic-value :as dynamic-value]
            [kmet.ai.llm :as llm]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.api.openai-completions :as completions]
            [kmet.ai.api.openai-responses :as responses]
            [kmet.ai.api.google-generative-ai :as google]
            [kmet.ai.models :as m]))

;; ─── Model resolution & dispatch ───────────────────────────────────────────

(t/deftest test-llm-unknown-model
  ;; a catalog provider with an unknown model id → error (pi: unknown model)
  (m/load-catalogs! nil)
  (let [errors (atom [])]
    @(llm/send-message {:provider :opencode-go
                        :model "no-such-model"
                        :api-key "test"
                        :on-error (fn [e] (swap! errors conj e))})
    (t/is (= ["Unknown model: opencode-go/no-such-model"] @errors))))

(t/deftest test-llm-unknown-provider
  (m/load-catalogs! nil)
  (let [errors (atom [])]
    @(llm/send-message {:provider :unknown
                        :api-key "test"
                        :on-error (fn [e] (swap! errors conj e))})
    (t/is (= ["Unknown provider: unknown"] @errors)))
  (t/testing "providers without a catalog entry are unknown (no legacy fallback)"
    (let [errors (atom [])]
      @(llm/send-message {:provider :nosuch :model "gpt-4o"
                          :api-key "test"
                          :on-error (fn [e] (swap! errors conj e))})
      (t/is (= ["Unknown provider: nosuch"] @errors)))))

(t/deftest test-llm-auth-base-url-keeps-api-endpoint
  ;; pi auth.baseUrl replaces the model base URL, not the complete endpoint.
  ;; The API builder must therefore remain responsible for appending /responses.
  (m/load-catalogs! nil)
  (let [request (atom nil)]
    (with-redefs [auth/resolve-provider-auth
                  (fn [_] {:api-key "test-key"
                           :base-url "https://copilot.example"})
                  responses/responses-request
                  (fn [opts]
                    (reset! request opts)
                    (future nil))]
      @(llm/send-message {:provider :openai
                          :model "gpt-5.4"
                          :messages []}))
    (t/is (nil? (:base-url @request))
          "an explicit endpoint override is not synthesized in the dispatcher")
    (t/is (= "https://copilot.example"
             (:base-url (:model-record @request))))
    (t/is (= "https://copilot.example/responses"
             (shared/endpoint-url :openai-responses
                                  (:base-url (:model-record @request))
                                  "gpt-5.4")))))

;; ─── Endpoint URL construction (each api owns it) ──────────────────────────

(t/deftest test-endpoint-urls
  (t/is (= "https://api.deepseek.com/chat/completions"
           (@#'shared/endpoint-url :openai-completions "https://api.deepseek.com" "deepseek-v4-flash")))
  (t/is (= "https://opencode.ai/zen/go/v1/chat/completions"
           (@#'shared/endpoint-url :openai-completions "https://opencode.ai/zen/go/v1" "qwen3.6-plus")))
  (t/is (= "https://api.individual.githubcopilot.com/v1/messages"
           (@#'shared/endpoint-url :anthropic-messages "https://api.individual.githubcopilot.com" "claude-sonnet-4.5")))
  (t/is (= "https://opencode.ai/zen/v1/models/gemini-3.1-pro:streamGenerateContent?alt=sse"
           (@#'shared/endpoint-url :google-generative-ai "https://opencode.ai/zen/v1" "gemini-3.1-pro")))
  (t/is (= "https://api.openai.com/v1/responses"
           (@#'shared/endpoint-url :openai-responses "https://api.openai.com/v1" "gpt-5.4"))))

(t/deftest test-interpolate-base-url
  (let [workers "https://api.cloudflare.com/client/v4/accounts/{CLOUDFLARE_ACCOUNT_ID}/ai/v1"
        gateway "https://gateway.ai.cloudflare.com/v1/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/openai"]
    (t/testing "placeholders substitute from the env"
      (with-redefs [shared/getenv (fn [k]
                                    (case k
                                      "CLOUDFLARE_ACCOUNT_ID" "acc-1"
                                      "CLOUDFLARE_GATEWAY_ID" "gw-1"
                                      nil))]
        (t/is (= "https://gateway.ai.cloudflare.com/v1/acc-1/gw-1/openai"
                 (@#'shared/interpolate-base-url gateway)))
        (t/is (= "https://api.cloudflare.com/client/v4/accounts/acc-1/ai/v1"
                 (@#'shared/interpolate-base-url workers)))
        (t/is (= "https://api.deepseek.com"
                 (@#'shared/interpolate-base-url "https://api.deepseek.com"))
              "non-cloudflare bases pass through")))
    (t/testing "missing ids are a clear config error (pi resolveCloudflareEnv)"
      (with-redefs [shared/getenv (fn [_] nil)]
        (t/is (thrown-with-msg? Exception #"CLOUDFLARE_ACCOUNT_ID"
                                (@#'shared/interpolate-base-url workers))))
      (with-redefs [shared/getenv (fn [k] (when (= k "CLOUDFLARE_ACCOUNT_ID") "acc"))]
        (t/is (thrown-with-msg? Exception #"CLOUDFLARE_GATEWAY_ID"
                                (@#'shared/interpolate-base-url gateway)))))))

;; ─── Thinking level machinery (pi: clampThinkingLevel) ─────────────────────

(defn- tmodel
  "Model map for thinking tests."
  [& {:keys [id reasoning tlm compat max-tokens]
      :or {id "m1" reasoning true compat nil max-tokens 32000}}]
  {:id id :name "M1" :reasoning reasoning
   :thinking-level-map tlm :compat compat :max-tokens max-tokens})

(t/deftest test-supported-thinking-levels
  (t/is (= [:off] (m/get-supported-thinking-levels (tmodel :reasoning false)))
        "non-reasoning models only support :off")
  (t/is (= [:off :minimal :low :medium :high]
           (m/get-supported-thinking-levels (tmodel :tlm nil)))
        "no map → everything except :xhigh/:max")
  (t/is (= [:off :high :max]
           (m/get-supported-thinking-levels
            (tmodel :tlm {:minimal nil :low nil :medium nil :high "high" :max "max"})))
        "null map values mark levels unsupported")
  (t/is (= [:high]
           (m/get-supported-thinking-levels
            (tmodel :tlm {:off nil :minimal nil :low nil :medium nil})))
        "a null :off means thinking cannot be disabled"))

(t/deftest test-clamp-thinking-level
  (let [deepseek (tmodel :tlm {:minimal nil :low nil :medium nil :high "high" :max "max"})]
    (t/is (= :max (m/clamp-thinking-level deepseek :low))
          "unsupported levels clamp to the highest supported")
    (t/is (= :max (m/clamp-thinking-level deepseek :max)))
    (t/is (= :off (m/clamp-thinking-level deepseek :off)))
    (t/is (= :max (m/clamp-thinking-level deepseek :xhigh))
          "xhigh unsupported → clamps to :max"))
  (t/is (= :high (m/clamp-thinking-level (tmodel :tlm nil) :max))
        "no map → :max clamps to :high")
  (t/is (= :high (m/clamp-thinking-level (tmodel :tlm nil) nil))
        "nil → clamps to highest supported")
  (t/is (= :off (m/clamp-thinking-level (tmodel :reasoning false) :high))
        "non-reasoning model → everything clamps to :off"))

(t/deftest test-effective-effort
  (t/is (nil? (m/effective-effort (tmodel) :off)))
  (t/is (= :low (m/effective-effort (tmodel) :low)))
  (t/is (= :high (m/effective-effort (tmodel) :max))
        "no map → :max clamps to :high"))

;; ─── Thinking request shaping (pi per-api) ─────────────────────────────────

(t/deftest test-openai-thinking-params
  (let [default-model (tmodel :compat nil)
        deepseek (tmodel :compat {:thinking-format :deepseek})
        deepseek-no-off (tmodel :compat {:thinking-format :deepseek}
                                :tlm {:off nil})
        qwen (tmodel :compat {:thinking-format :qwen})
        no-effort (tmodel :compat {:supports-reasoning-effort false})]
    (t/is (= {:reasoning_effort "high"} (@#'shared/openai-thinking-params default-model :high))
          "default format: reasoning_effort = level name")
    (t/is (= {:reasoning_effort "none"}
             (@#'shared/openai-thinking-params
              (tmodel :tlm {:off "none"}) nil))
          "default format off: map :off value when present")
    (t/is (= {} (@#'shared/openai-thinking-params default-model nil))
          "default format off without map :off → no params")
    (t/is (= {:thinking {:type "enabled"}
              :reasoning_effort "high"}
             (@#'shared/openai-thinking-params deepseek :high))
          "deepseek on: thinking enabled + reasoning_effort")
    (t/is (= {:thinking {:type "disabled"}}
             (@#'shared/openai-thinking-params deepseek nil))
          "deepseek off: thinking disabled")
    (t/is (= {} (@#'shared/openai-thinking-params deepseek-no-off nil))
          "deepseek off with :off null → no disabled param")
    (t/is (= {:enable_thinking true :reasoning_effort "high"}
             (@#'shared/openai-thinking-params qwen :high))
          "qwen on: enable_thinking + reasoning_effort")
    (t/is (= {:enable_thinking false} (@#'shared/openai-thinking-params qwen nil))
          "qwen off: enable_thinking false")
    (t/is (= {:reasoning {:effort "high"}}
             (@#'shared/openai-thinking-params
              (tmodel :compat {:thinking-format :openrouter}) :high))
          "openrouter on: nested reasoning: {effort}")
    (t/is (= {:reasoning {:effort "none"}}
             (@#'shared/openai-thinking-params
              (tmodel :compat {:thinking-format :openrouter}) nil))
          "openrouter off: reasoning: {effort: none} (pi)")
    (t/is (= {}
             (@#'shared/openai-thinking-params
              (tmodel :compat {:thinking-format :openrouter} :tlm {:off nil}) nil))
          "openrouter off pinned to null → no params (always thinking)")
    (t/is (= {} (@#'shared/openai-thinking-params no-effort :high))
          "supports-reasoning-effort false → no effort params")
    (t/is (= {} (@#'shared/openai-thinking-params (tmodel :reasoning false) :high))
          "non-reasoning model → no thinking params")
    (t/testing "A.3 formats (pi buildParams thinking)"
      (let [zai (tmodel :compat {:thinking-format :zai :supports-reasoning-effort true})
            zai-no-effort (tmodel :compat {:thinking-format :zai})
            together (tmodel :compat {:thinking-format :together :supports-reasoning-effort true})
            together-no-effort (tmodel :compat {:thinking-format :together})
            baseten (tmodel :compat {:thinking-format :baseten
                                     :supports-reasoning-effort false
                                     :chat-template-args {:enable_thinking {:var "thinking.enabled"}}})
            chat-template (tmodel :compat {:thinking-format :chat-template
                                           :chat-template-kwargs {:enable_thinking {:var "thinking.enabled"}}})
            adaptive (tmodel :compat {:force-adaptive-thinking true})
            adaptive-mapped (tmodel :compat {:force-adaptive-thinking true}
                                    :tlm {:minimal "low"})]
        (t/is (= {:thinking {:type "enabled" :clear_thinking false}
                  :reasoning_effort "high"}
                 (@#'shared/openai-thinking-params zai :high))
              "zai on: thinking enabled + reasoning_effort")
        (t/is (= {:thinking {:type "disabled"}}
                 (@#'shared/openai-thinking-params zai-no-effort nil))
              "zai off: thinking disabled")
        (t/is (= {:reasoning {:enabled true} :reasoning_effort "high"}
                 (@#'shared/openai-thinking-params together :high))
              "together on: reasoning enabled + reasoning_effort")
        (t/is (= {:reasoning {:enabled false}}
                 (@#'shared/openai-thinking-params together-no-effort nil))
              "together off: reasoning disabled")
        (t/is (= {:chat_template_args {:enable_thinking true}}
                 (@#'shared/openai-thinking-params baseten :high))
              "baseten on: chat_template_args enable_thinking true")
        (t/is (= {:chat_template_args {:enable_thinking false}}
                 (@#'shared/openai-thinking-params baseten nil))
              "baseten off: chat_template_args enable_thinking false")
        (t/is (= {} (@#'shared/openai-thinking-params
                     (tmodel :compat {:thinking-format :ant-ling}) :high))
              "ant-ling: no params (Ring reasons by default, pi's empty branch)")
        (t/is (= {:thinking "high"}
                 (@#'shared/openai-thinking-params
                  (tmodel :compat {:thinking-format :string-thinking}) :high))
              "string-thinking on")
        (t/is (= {:chat_template_kwargs {:enable_thinking true :preserve_thinking true}}
                 (@#'shared/openai-thinking-params
                  (tmodel :compat {:thinking-format :qwen-chat-template}) :high))
              "qwen-chat-template on")
        (t/is (= {:chat_template_kwargs {:enable_thinking false}}
                 (@#'shared/openai-thinking-params chat-template nil))
              "chat-template off: enable_thinking false")
        (t/is (= {:thinking {:type "adaptive" :display "summarized"}
                  :output_config {:effort "high"}
                  :max-tokens 32000}
                 (@#'shared/anthropic-thinking adaptive :high))
              "adaptive thinking: output_config effort (pi forceAdaptiveThinking)")
        (t/is (= {:thinking {:type "adaptive" :display "summarized"}
                  :output_config {:effort "low"}
                  :max-tokens 32000}
                 (@#'shared/anthropic-thinking adaptive-mapped :minimal))
              "adaptive effort uses the thinking-level-map mapping when present")
        (t/is (= {:thinking {:type "disabled"} :max-tokens 32000}
                 (@#'shared/anthropic-thinking adaptive nil))
              "adaptive models still disable thinking when off")))))

(t/deftest test-anthropic-thinking
  (let [model (tmodel :max-tokens 32000)]
    (t/is (= {:thinking {:type "enabled" :budget_tokens 2048 :display "summarized"}
              :max-tokens 32000}
             (@#'shared/anthropic-thinking model :low))
          "budget-based thinking, max_tokens from the model")
    (t/is (= {:thinking {:type "enabled" :budget_tokens 16384 :display "summarized"}
              :max-tokens 32000}
             (@#'shared/anthropic-thinking model :high)))
    (t/is (= {:thinking {:type "enabled" :budget_tokens 16384 :display "summarized"}
              :max-tokens 32000}
             (@#'shared/anthropic-thinking model :max))
          ":max clamps to :high budget")
    (t/is (= {:thinking {:type "disabled"} :max-tokens 32000}
             (@#'shared/anthropic-thinking model nil))
          "off → thinking disabled")
    (t/is (nil? (@#'shared/anthropic-thinking
                 (tmodel :tlm {:off nil}) nil))
          "off with :off null → no thinking param")
    (t/is (nil? (@#'shared/anthropic-thinking (tmodel :reasoning false) :high)))
    (t/is (= {:thinking {:type "enabled" :budget_tokens 2048 :display "summarized"} :max-tokens 4096}
             (@#'shared/anthropic-thinking (tmodel :max-tokens nil) :low))
          "legacy anthropic (no max-tokens) falls back to 4096")))

(t/deftest test-thinking-budget-for
  (let [model (tmodel)
        extended (tmodel :tlm {:xhigh "xhigh" :max "max"})]
    (t/is (= 1024 (m/thinking-budget-for model :minimal)))
    (t/is (= 2048 (m/thinking-budget-for model :low)))
    (t/is (= 8192 (m/thinking-budget-for model :medium)))
    (t/is (= 16384 (m/thinking-budget-for model :high)))
    (t/is (= 16384 (m/thinking-budget-for extended :xhigh))
          "xhigh collapses to the high budget (pi clampReasoning)")
    (t/is (= 16384 (m/thinking-budget-for extended :max))
          "max collapses to the high budget (pi clampReasoning)")
    (t/is (= 16384 (m/thinking-budget-for model :xhigh))
          "an unsupported level first clamps to the highest supported one")
    (t/is (= 0 (m/thinking-budget-for model :off)))
    (t/is (= 0 (m/thinking-budget-for model nil)))
    (t/is (= 0 (m/thinking-budget-for (tmodel :reasoning false) :high))
          "a non-reasoning model has no thinking budget")))

(t/deftest test-clamp-reasoning
  (t/is (= :high (m/clamp-reasoning :xhigh)))
  (t/is (= :high (m/clamp-reasoning :max)))
  (t/is (= :medium (m/clamp-reasoning :medium)))
  (t/is (= :off (m/clamp-reasoning :off)))
  (t/is (nil? (m/clamp-reasoning nil))
        "nil passes through (pi clampReasoning)"))

(t/deftest test-google-thinking-config
  (let [gemini-pro (tmodel :id "gemini-3.1-pro"
                           :tlm {:off nil :minimal nil :low "LOW" :medium nil :high "HIGH"})]
    (t/is (= {:includeThoughts true :thinkingLevel "LOW"}
             (@#'google/google-thinking-config gemini-pro :low)))
    (t/is (= {:includeThoughts true :thinkingLevel "HIGH"}
             (@#'google/google-thinking-config gemini-pro :high)))
    (t/is (= {:thinkingLevel "LOW"}
             (@#'google/google-thinking-config gemini-pro nil))
          "gemini-3 pro off → lowest thinking level, no includeThoughts"))
  (t/is (= {:thinkingLevel "MINIMAL"}
           (@#'google/google-thinking-config (tmodel :id "gemini-3.5-flash") nil))
        "gemini-3 flash off → MINIMAL"))

;; ─── Model headers merge ───────────────────────────────────────────────────

(t/deftest test-model-headers-merge
  (let [model {:headers {"User-Agent" "GitHubCopilotChat/0.35.0"}
               :provider :github-copilot :id "claude-sonnet-4.5"}
        provider {:id :github-copilot
                  :configured-headers {"X-Custom" "literal"}
                  :auth-header false}
        merged (@#'shared/request-headers {"Content-Type" "application/json"} model provider "k" nil)]
    (t/is (= "application/json" (get merged "Content-Type")))
    (t/is (= "GitHubCopilotChat/0.35.0" (get merged "User-Agent")))
    (t/is (= "literal" (get merged "X-Custom")))))

(t/deftest test-request-headers-auth-header
  (t/testing "auth-header true adds Authorization: Bearer <key> last"
    (let [merged (@#'shared/request-headers {"x-api-key" "k"}
                                            {:provider :p :id "m"}
                                            {:id :p :auth-header true}
                                            "secret" nil)]
      (t/is (= "Bearer secret" (get merged "Authorization")))
      (t/is (= "k" (get merged "x-api-key")))))
  (t/testing "no auth-header → no Authorization header"
    (let [merged (@#'shared/request-headers {"x-api-key" "k"}
                                            {:provider :p :id "m"}
                                            {:id :p}
                                            "secret" nil)]
      (t/is (nil? (get merged "Authorization")))))
  (t/testing "configured header values resolve as config values ($ENV)"
    (with-redefs [dynamic-value/getenv (fn [k] (when (= k "TEST_LLM_HEADER") "hdr"))]
      (let [merged (@#'shared/request-headers {}
                                              {:provider :p :id "m"}
                                              {:id :p :configured-headers {"X-Custom" "$TEST_LLM_HEADER"}}
                                              "k" nil)]
        (t/is (= "hdr" (get merged "X-Custom")))))))

(t/deftest test-max-tokens-key
  (t/is (= :max_tokens (@#'shared/max-tokens-key (tmodel :compat {:max-tokens-field :max-tokens})))
        "max-tokens-field :max-tokens → :max_tokens (opencode/deepseek)")
  (t/is (= :max_completion_tokens (@#'shared/max-tokens-key (tmodel :compat nil)))
        "default → :max_completion_tokens"))

;; ─── Compat auto-detection (pi detectCompat/getCompat) ─────────────────────

(defn- cmodel
  "Model map for compat-detection tests: a models.edn-style custom entry
   (provider id + base-url) with optional explicit compat."
  [& {:keys [provider id base-url compat]
      :or {provider :custom id "vendor/model-x" base-url "https://api.example.com/v1"}}]
  {:id id :name "M" :provider provider :base-url base-url
   :reasoning true :compat compat})

(t/deftest test-detect-openai-compat
  (t/testing "generic custom provider → plain OpenAI defaults"
    (let [d (shared/detect-openai-compat (cmodel))]
      (t/is (= :openai (:thinking-format d)))
      (t/is (= :max-completion-tokens (:max-tokens-field d)))
      (t/is (false? (:requires-reasoning-content-on-assistant-messages d)))
      (t/is (false? (:supports-strict-mode d))
            "strict tools are opt-in: capable models carry an explicit catalog compat")
      (t/is (true? (:supports-store d)))
      (t/is (true? (:supports-reasoning-effort d)))
      (t/is (= :openai (:session-affinity-format d)))))
  (t/testing "deepseek detected by base URL or provider id"
    (doseq [m [(cmodel :base-url "https://api.DEEPSEEK.com")
               (cmodel :provider :deepseek)]]
      (let [d (shared/detect-openai-compat m)]
        (t/is (= :deepseek (:thinking-format d)))
        (t/is (true? (:requires-reasoning-content-on-assistant-messages d)))
        (t/is (= :max-tokens (:max-tokens-field d)))
        (t/is (false? (:supports-store d))))))
  (t/testing "openrouter: nested reasoning format + affinity; anthropic/* keeps developer role"
    (let [d (shared/detect-openai-compat
             (cmodel :provider :openrouter :id "anthropic/claude-x"
                     :base-url "https://openrouter.ai/api/v1"))]
      (t/is (= :openrouter (:thinking-format d)))
      (t/is (true? (:supports-developer-role d)))
      (t/is (= :openrouter (:session-affinity-format d)))
      (t/is (= :anthropic (:cache-control-format d)))))
  (t/testing "zai/moonshot: effort unsupported, max_tokens; moonshot drops strict mode"
    (let [zai (shared/detect-openai-compat
               (cmodel :provider :zai :base-url "https://api.z.ai/api/paas/v4"))]
      (t/is (= :zai (:thinking-format zai)))
      (t/is (false? (:supports-reasoning-effort zai)))
      (t/is (= :max-tokens (:max-tokens-field zai))))
    (let [moonshot (shared/detect-openai-compat
                    (cmodel :provider :moonshotai :base-url "https://api.moonshot.cn/v1"))]
      (t/is (false? (:supports-reasoning-effort moonshot)))
      (t/is (false? (:supports-strict-mode moonshot)))
      (t/is (= :max-tokens (:max-tokens-field moonshot))))))

(t/deftest test-resolved-openai-compat
  (t/testing "explicit compat wins key-wise over detection (models.edn partial entries)"
    (let [r (shared/resolved-openai-compat
             (cmodel :base-url "https://api.commandcode.ai/provider/v1"
                     :compat {:thinking-format :deepseek}))]
      (t/is (= :deepseek (:thinking-format r)) "explicit format kept")
      (t/is (true? (:requires-reasoning-content-on-assistant-messages r))
            "deepseek thinking format implies the reasoning_content echo-back
             requirement (undetectable proxies — DeepSeek 400s without it)")
      (t/is (= :max-completion-tokens (:max-tokens-field r)))))
  (t/testing "an explicit flag beats the deepseek-format derivation"
    (t/is (false? (:requires-reasoning-content-on-assistant-messages
                   (shared/resolved-openai-compat
                    (cmodel :compat {:thinking-format :deepseek
                                     :requires-reasoning-content-on-assistant-messages false})))))
    (t/is (true? (:requires-reasoning-content-on-assistant-messages
                  (shared/resolved-openai-compat
                   (cmodel :compat {:thinking-format :deepseek
                                    :requires-reasoning-content-on-assistant-messages true}))))))
  (t/testing "non-deepseek formats don't derive the requirement"
    (t/is (false? (:requires-reasoning-content-on-assistant-messages
                   (shared/resolved-openai-compat
                    (cmodel :compat {:thinking-format :openrouter}))))))
  (t/testing "explicit false beats a detected true (pi ?? merge semantics)"
    (t/is (false? (:supports-store
                   (shared/resolved-openai-compat
                    (cmodel :compat {:supports-store false}))))))
  (t/testing "no compat at all → pure detection"
    (t/is (= :openai (:thinking-format (shared/resolved-openai-compat (cmodel)))))))

(t/deftest test-openai-payload-sampling-params
  (t/testing "sampling-params merged verbatim into the payload, keys win (pi: Object.assign last)"
    (let [model (assoc (tmodel) :sampling-params {:temperature 1.0 :min_p 0.0})
          payload (@#'completions/openai-payload model nil [] [] "m1")]
      (t/is (= 1.0 (:temperature payload)))
      (t/is (= 0.0 (:min_p payload)))
      (t/is (= "m1" (:model payload)))
      (t/is (= true (:stream payload)))))
  (t/testing "sampling-params override pi-named fields (temperature, max tokens)"
    (let [model (assoc (tmodel) :sampling-params {:max_completion_tokens 5})
          payload (@#'completions/openai-payload model nil [] [] "m1")]
      (t/is (= 5 (:max_completion_tokens payload)) "sampling key beats the model :max-tokens")))
  (t/testing "no sampling-params → payload unchanged"
    (let [payload (@#'completions/openai-payload (tmodel) nil [] [] "m1")]
      (t/is (= 32000 (:max_completion_tokens payload)))
      (t/is (nil? (:temperature payload))))))

(t/deftest test-openai-payload-detected-compat
  ;; pi getCompat: a model whose :compat is unset gets detectCompat defaults —
  ;; pointing a custom provider at api.deepseek.com yields deepseek thinking
  ;; params, max_tokens and reasoning_content round-trip without any config.
  (let [model {:id "deepseek-v4-flash" :name "DS" :provider :custom
               :base-url "https://api.deepseek.com" :reasoning true
               :thinking-level-map {:high "high"} :max-tokens 32768
               :compat nil}
        msgs [{:role :user :content [{:type :text :text "hi"}]}
              {:role :assistant :content [{:type :text :text "done"}]
               ;; provenance stamps match the request target (the model that
               ;; produced this message IS deepseek-v4-flash)
               :thinking "prior CoT" :provider :custom :model "deepseek-v4-flash"
               :tool-calls [{:id "c1" :name "bash" :arguments {}}]}]
        payload (@#'completions/openai-payload model :high msgs [] "deepseek-v4-flash")]
    (t/is (= {:thinking {:type "enabled"} :reasoning_effort "high"}
             (select-keys payload [:thinking :reasoning_effort])))
    (t/is (= 32768 (:max_tokens payload)))
    (t/is (= "prior CoT" (:reasoning_content (second (:messages payload)))))
    ;; the gate itself: detection selected openai-messages-with-reasoning —
    ;; a thinking-less assistant message gets an empty reasoning_content fill
    ;; (plain openai-messages would omit the key entirely)
    (let [payload (@#'completions/openai-payload model nil
                                                 [{:role :assistant
                                                   :content [{:type :text :text "hi"}]}]
                                                 [] "deepseek-v4-flash")]
      (t/is (= "" (:reasoning_content (first (:messages payload))))))))

(t/deftest test-openai-payload-model-switch-foreign-thinking
  ;; Regression for the mid-session /model switch tool-loop: history recorded
  ;; by another model (here hetzner Qwen) replayed against a DeepSeek-class
  ;; target. The foreign chain-of-thought must never ride along as
  ;; reasoning_content — DeepSeek thinking-mode treats the field as ITS OWN
  ;; prior reasoning, and a transcript full of someone else's CoT derails it
  ;; into re-running its last tool call forever. The strict-mode requirement
  ;; is still satisfied: every assistant message carries the field (empty
  ;; fill where there is no own-model reasoning).
  (let [qwen-era {:id "Qwen3.8-27B" :name "Q" :provider :hetzner
                  :base-url "https://inference.hetzner.com/api/v1"
                  :reasoning true :input [:text] :max-tokens 32768
                  :compat {:thinking-format :qwen-chat-template}}
        msgs [{:role :user :content [{:type :text :text "list files"}]}
              {:role :assistant :content [{:type :text :text "Checking."}]
               :thinking "Qwen's own CoT about running ls"
               :provider :hetzner :model "Qwen3.8-27B"
               :tool-calls [{:id "call_1" :name "bash" :arguments {:command "ls"}}]}
              {:role :tool
               :content [{:type :tool_result :tool_use_id "call_1" :content "a.txt b.txt"}]
               :tool-name "bash"}
              {:role :assistant :content [{:type :text :text "Found a.txt and b.txt."}]
               :thinking "Qwen's summary CoT"
               :provider :hetzner :model "Qwen3.8-27B"}]]
    (let [payload (@#'completions/openai-payload qwen-era :high msgs [] "Qwen3.8-27B")
          [_ a1 _ a2] (:messages payload)]
      ;; pre-switch sanity: same-model replay still works on the old model
      (t/is (= "Qwen's own CoT about running ls" (:reasoning_content a1)))
      (t/is (= "Qwen's summary CoT" (:reasoning_content a2))))
    ;; after the switch the SAME history goes to deepseek-v4-flash
    (let [ds (assoc (tmodel) :compat {:thinking-format :deepseek})
          payload (@#'completions/openai-payload ds :high msgs [] "deepseek/deepseek-v4-flash")
          [_ a1 _ a2] (:messages payload)]
      (t/is (= {:thinking {:type "enabled"}} (select-keys payload [:thinking])))
      ;; the :deepseek format derives the reasoning_content echo-back
      ;; requirement, so the field is present on every assistant message —
      ;; filled with the empty string, never with Qwen's CoT
      (t/is (= "" (:reasoning_content a1))
            "Qwen CoT does not leak into the deepseek request")
      (t/is (= "" (:reasoning_content a2)))
      (t/is (= "a.txt b.txt" (:content (nth (:messages payload) 2)))
            "tool results still convert normally")
      ;; with-reasoning path (derived from the explicit :deepseek format):
      ;; field present everywhere, but filled only with own-model reasoning
      (let [ds-flag (assoc ds :compat {:thinking-format :deepseek
                                       :requires-reasoning-content-on-assistant-messages true})
            payload (@#'completions/openai-payload ds-flag :high msgs [] "deepseek/deepseek-v4-flash")
            [_ a1 _ a2] (:messages payload)]
        (t/is (= "" (:reasoning_content a1))
              "foreign turn carries the required empty fill")
        (t/is (= "" (:reasoning_content a2))
              "no foreign CoT rides along on any assistant message")))))

(t/deftest test-openai-payload-stealth-reasoning-fill
  ;; CommandCode's stealth endpoints (Cat Jam, Beam) convert reasoning_content
  ;; into a thinking block and reject an assistant message that carries an
  ;; empty one — 400 "The stealth model could not complete the request." —
  ;; which broke every request after a mid-session /model switch (the foreign
  ;; reasoning is dropped by design, leaving no reasoning_content at all).
  ;; The model's compat supplies a non-empty placeholder; same-model reasoning
  ;; still takes precedence.
  (let [stealth {:id "stealth/cat-jam:free" :name "Cat Jam" :provider :commandcode
                 :base-url "https://api.commandcode.ai/provider/v1"
                 :reasoning true :input [:text] :max-tokens 131072
                 :compat {:requires-reasoning-content-on-assistant-messages true
                          :reasoning-content-fill " "}}
        msgs [{:role :user :content [{:type :text :text "hi"}]}
              {:role :assistant :content [{:type :text :text "done"}]
               :thinking "deepseek CoT"
               :provider :commandcode :model "deepseek/deepseek-v4.1-flash"}
              {:role :assistant :content [{:type :text :text "mine"}]
               :thinking "own CoT"
               :provider :commandcode :model "stealth/cat-jam:free"}]
        payload (@#'completions/openai-payload stealth :high msgs [] "stealth/cat-jam:free")
        [_ foreign own] (:messages payload)]
    (t/is (= " " (:reasoning_content foreign))
          "a pre-switch turn carries the non-empty fill, never the foreign CoT")
    (t/is (= "own CoT" (:reasoning_content own))
          "same-model reasoning still round-trips"))
  ;; the shipped commandcode catalog carries the compat (commandcode-overrides
  ;; must survive a regeneration)
  (m/load-catalogs! nil)
  ;; Only the live stealth id can be asserted here: the endpoint retired the
  ;; cat-jam alias when Beam was unveiled, so the generated catalog drops it
  ;; (process-commandcode ships what the endpoint lists). The ref and the
  ;; override stay in the generator, so a reappearance transfers both again.
  (let [mid "reflection/beam:free"
        compat (:compat (m/get-model :commandcode mid))]
    (t/is (true? (:requires-reasoning-content-on-assistant-messages compat))
          (str mid " must send reasoning_content on every assistant message"))
    (t/is (pos? (count (:reasoning-content-fill compat)))
          (str mid " needs a non-empty fill — the endpoint rejects \"\""))))

(t/deftest test-reasoning-content-gating-data
  ;; pi: requiresReasoningContentOnAssistantMessages gates reasoning_content
  ;; on assistant messages — carried by deepseek/opencode-go deepseek-v4
  ;; models, absent elsewhere.
  (m/load-catalogs! nil)
  (t/is (true? (:requires-reasoning-content-on-assistant-messages
                (:compat (m/get-model :deepseek "deepseek-flash")))))
  (t/is (true? (:requires-reasoning-content-on-assistant-messages
                (:compat (m/get-model :opencode-go "deepseek-v4.1-flash")))))
  (t/is (nil? (:requires-reasoning-content-on-assistant-messages
               (:compat (m/get-model :opencode "glm-5.2")))))
  (t/is (nil? (:requires-reasoning-content-on-assistant-messages
               (:compat (first (m/get-models :github-copilot)))))))

