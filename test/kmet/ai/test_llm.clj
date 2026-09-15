(ns kmet.ai.test-llm
  (:require [clojure.test :as t]
            [kmet.libs.hash :as hash]
            [kmet.libs.json :as json]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.libs.sse :as sse]
            [kmet.ai.auth :as auth]
            [kmet.libs.aws-sigv4 :as aws-sigv4]
            [kmet.libs.dynamic-value :as dynamic-value]
            [kmet.ai.llm :as llm]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.api.openai-completions :as completions]
            [kmet.ai.api.openai-responses :as responses]
            [kmet.ai.api.openai-codex-responses :as codex]
            [kmet.ai.api.azure-openai-responses :as azure]
            [kmet.ai.api.anthropic-messages :as anthropic]
            [kmet.ai.api.google-generative-ai :as google]
            [kmet.ai.api.mistral-conversations :as mistral]
            [kmet.ai.api.google-vertex :as vertex]
            [kmet.ai.api.bedrock-converse-stream :as bedrock]
            [kmet.app.loop :as loop]
            [kmet.app.session :as session]
            [kmet.config :as cfg]
            [kmet.ai.models :as m]
            [kmet.app.tools.core :as tools]))

;; ─── Module loads ─────────────────────────────────────────────────────────

(t/deftest test-llm-loaded
  (t/is (fn? llm/send-message))
  (m/load-catalogs!)
  (t/is (= 40 (count (m/get-providers))))
  (t/is (fn? m/get-model)))

;; ─── Model resolution & dispatch ───────────────────────────────────────────

(t/deftest test-llm-unknown-model
  ;; a catalog provider with an unknown model id → error (pi: unknown model)
  (m/load-catalogs!)
  (let [errors (atom [])]
    @(llm/send-message {:provider :opencode-go
                        :model "no-such-model"
                        :api-key "test"
                        :on-error (fn [e] (swap! errors conj e))})
    (t/is (= ["Unknown model: opencode-go/no-such-model"] @errors))))

(t/deftest test-llm-unknown-provider
  (m/load-catalogs!)
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
  (m/load-catalogs!)
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

;; ─── OpenAI Codex + Azure responses (shared processor, new URL + auth) ────

(defn- test-jwt
  "A fake JWT carrying the chatgpt_account_id claim (codex request auth)."
  [account-id]
  (let [enc (fn [s] (.encodeToString (java.util.Base64/getUrlEncoder)
                                     (.getBytes s "UTF-8")))
        payload (str "{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\""
                     account-id "\"}}")]
    (str (enc "{\"alg\":\"none\"}") "." (enc payload) ".sig")))

(defn- codex-model
  "A minimal codex Model record for payload tests."
  [& [overrides]]
  (m/map->Model
   (merge {:id "gpt-5.4" :name "GPT-5.4" :provider :openai-codex
           :api :openai-codex-responses :base-url "https://chatgpt.com/backend-api"
           :reasoning true :input [:text :image]
           :cost {:input 2.5 :output 15 :cache-read 0.25 :cache-write 0}
           :context-window 272000 :max-tokens 128000
           :thinking-level-map {:minimal "low" :low "low" :medium "medium"
                                :high "high" :xhigh "xhigh" :max "max"}}
          overrides)))

(t/deftest test-llm-codex-url
  (t/is (= "https://chatgpt.com/backend-api/codex/responses"
           (@#'codex/codex-endpoint-url "https://chatgpt.com/backend-api")))
  (t/is (= "https://chatgpt.com/backend-api/codex/responses"
           (@#'codex/codex-endpoint-url nil))
        "blank base → the default codex base")
  (t/is (= "https://custom.example/codex/responses"
           (@#'codex/codex-endpoint-url "https://custom.example/codex"))
        "base ending in /codex gains /responses")
  (t/is (= "https://custom.example/codex/responses"
           (@#'codex/codex-endpoint-url "https://custom.example/codex/responses/"))
        "the full path passes through (trailing slash trimmed)"))

(t/deftest test-llm-codex-account-id
  (t/is (= "acc-1" (@#'codex/codex-account-id (test-jwt "acc-1")))
        "chatgpt_account_id claim from the JWT payload")
  (t/is (thrown-with-msg? Exception #"Failed to extract accountId"
                          (@#'codex/codex-account-id "not-a-jwt")))
  (t/is (thrown-with-msg? Exception #"Failed to extract accountId"
                          (@#'codex/codex-account-id
                           (test-jwt ""))) "no claim in the payload"))

(t/deftest test-llm-codex-payload
  (let [msgs [{:role :system :content [{:type :text :text "S"}]}
              {:role :user :content [{:type :text :text "hi"}]}]
        payload (@#'codex/codex-payload (codex-model) :high msgs [] "gpt-5.4"
                                        "sess-123")]
    (t/is (= "gpt-5.4" (:model payload)))
    (t/is (= "S" (:instructions payload)) "the system prompt goes to instructions")
    (t/is (false? (:store payload)))
    (t/is (= "low" (get-in payload [:text :verbosity])))
    (t/is (= ["reasoning.encrypted_content"] (:include payload)))
    (t/is (= "auto" (:tool_choice payload)))
    (t/is (true? (:parallel_tool_calls payload)))
    (t/is (= "sess-123" (:prompt_cache_key payload)))
    (t/is (= {:effort "high" :summary "auto"} (:reasoning payload)))
    (t/is (not-any? #(= "developer" (:role %)) (:input payload))
          "no developer message — the codex envelope uses instructions")
    (t/is (= [{:role "user"
               :content [{:type "input_text" :text "hi"}]}]
             (:input payload))
          "converted messages without the system prompt"))
  (t/testing "no prompt-cache key when caching is off"
    (let [payload (@#'codex/codex-payload (codex-model) nil [] [] "gpt-5.4" nil)]
      (t/is (nil? (:prompt_cache_key payload)))
      (t/is (nil? (:reasoning payload)) "off → no reasoning field (codex thinks by default)")))
  (t/testing "tools + sampling-params merged last"
    (let [tool (tools/make-tool :name "bash" :description "run"
                                :parameters {:type "object"
                                             :properties {:cmd {:type "string"}}}
                                :execute (fn [_] nil))
          payload (@#'codex/codex-payload (codex-model {:sampling-params {:temperature 0.2}})
                                          :high [] [tool] "gpt-5.4" nil)]
      (t/is (= "bash" (get-in payload [:tools 0 :name])))
      (t/is (= 0.2 (:temperature payload)) "sampling-params win over the envelope"))))

(t/deftest test-llm-codex-request-headers
  (let [token (test-jwt "acc-9")
        headers (@#'codex/codex-request-headers token "sess-123")]
    (t/is (= (str "Bearer " token) (get headers "Authorization")))
    (t/is (= "acc-9" (get headers "chatgpt-account-id")))
    (t/is (= "kmet" (get headers "originator")))
    (t/is (str/starts-with? (get headers "User-Agent") "kmet (") "kmet User-Agent (pi buildBaseCodexHeaders)")
    (t/is (= "responses=experimental" (get headers "OpenAI-Beta")))
    (t/is (= "text/event-stream" (get headers "Accept")))
    (t/is (= "sess-123" (get headers "session-id")))
    (t/is (= "sess-123" (get headers "x-client-request-id"))))
  (t/testing "no session headers when caching is off"
    (let [headers (@#'codex/codex-request-headers (test-jwt "acc-9") nil)]
      (t/is (nil? (get headers "session-id")))
      (t/is (nil? (get headers "x-client-request-id"))))))

(t/deftest test-llm-azure-url
  (t/testing "normalizeAzureBaseUrl forces azure hosts to /openai/v1"
    (t/is (= "https://res.openai.azure.com/openai/v1"
             (@#'azure/normalize-azure-base-url "https://res.openai.azure.com")))
    (t/is (= "https://res.openai.azure.com/openai/v1"
             (@#'azure/normalize-azure-base-url "https://res.openai.azure.com/openai/v1/responses")))
    (t/is (= "https://res.cognitiveservices.azure.com/openai/v1"
             (@#'azure/normalize-azure-base-url "https://res.cognitiveservices.azure.com/openai/")))
    (t/is (= "https://res.ai.azure.com/openai/v1"
             (@#'azure/normalize-azure-base-url "https://res.ai.azure.com")))
    (t/is (= "https://res.openai.azure.com/custom/path"
             (@#'azure/normalize-azure-base-url "https://res.openai.azure.com/custom/path"))
          "other paths pass through"))
  (t/testing "azureEndpointUrl appends the deployment + api version"
    (t/is (= "https://res.openai.azure.com/openai/v1/deployments/gpt-5.4/responses?api-version=v1"
             (@#'azure/azure-endpoint-url "https://res.openai.azure.com/openai/v1"
                                          "gpt-5.4" "v1"))))
  (t/testing "deployment name resolution (AZURE_OPENAI_DEPLOYMENT_NAME_MAP)"
    (with-redefs [shared/getenv (fn [k]
                                  (when (= k "AZURE_OPENAI_DEPLOYMENT_NAME_MAP")
                                    "gpt-5.4=my-gpt-54, gpt-5.5 = my-gpt-55"))]
      (t/is (= "my-gpt-54" (@#'azure/azure-deployment-name "gpt-5.4")))
      (t/is (= "my-gpt-55" (@#'azure/azure-deployment-name "gpt-5.5")))
      (t/is (= "gpt-4o" (@#'azure/azure-deployment-name "gpt-4o"))
            "unmapped models use the model id"))
    (with-redefs [shared/getenv (fn [_] nil)]
      (t/is (= "gpt-5.4" (@#'azure/azure-deployment-name "gpt-5.4")))))
  (t/testing "resolved config: env base wins, resource name derives, api version"
    (with-redefs [shared/getenv (fn [k]
                                  (case k
                                    "AZURE_OPENAI_BASE_URL" "https://res.openai.azure.com"
                                    "AZURE_OPENAI_API_VERSION" "2024-02-01"
                                    nil))]
      (t/is (= {:base-url "https://res.openai.azure.com/openai/v1" :api-version "2024-02-01"}
               (@#'azure/azure-resolved-config ""))))
    (with-redefs [shared/getenv (fn [k]
                                  (when (= k "AZURE_OPENAI_RESOURCE_NAME") "myres"))]
      (t/is (= {:base-url "https://myres.openai.azure.com/openai/v1" :api-version "v1"}
               (@#'azure/azure-resolved-config ""))))
    (with-redefs [shared/getenv (fn [_] nil)]
      (t/is (= {:base-url "https://model.example/v1" :api-version "v1"}
               (@#'azure/azure-resolved-config "https://model.example/v1"))
            "model base-url fallback")
      (t/is (thrown-with-msg? Exception #"Azure OpenAI base URL is required"
                              (@#'azure/azure-resolved-config ""))))))

(t/deftest test-llm-azure-payload-uses-deployment
  (let [payload (@#'responses/responses-payload (codex-model) :high [] [] "my-deployment"
                                                :short "sess-123")]
    (t/is (= "my-deployment" (:model payload))
          "the deployment name is the model field on the wire")
    (t/is (= "sess-123" (:prompt_cache_key payload)))
    (t/is (= {:effort "high" :summary "auto"} (:reasoning payload)))))

(t/deftest test-llm-codex-invalid-token-reports-error
  (m/load-catalogs!)
  (let [errors (atom [])]
    @(llm/send-message {:provider :openai-codex
                        :api-key "not-a-jwt"
                        :model "gpt-5.4"
                        :on-error (fn [e] (swap! errors conj e))})
    (t/is (= ["Failed to extract accountId from token"] @errors)
          "an invalid codex token reports via on-error (never hangs the loop)")))

(t/deftest test-llm-azure-missing-config-reports-error
  (m/load-catalogs!)
  (let [errors (atom [])]
    ;; the config is resolved inside the request future, so the env redef
    ;; must stay active until the future completes
    (with-redefs [shared/getenv (fn [_] nil)]
      @(llm/send-message {:provider :azure-openai-responses
                          :api-key "sk"
                          :model "gpt-5.4"
                          :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= ["Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or AZURE_OPENAI_RESOURCE_NAME."]
             @errors)
          "a missing azure base config reports via on-error (never hangs the loop)")))

;; ─── OpenAI Responses messages + payload ──────────────────────────────────

(defn- responses-model
  "A minimal gpt-5-style Model record for payload tests."
  [& [overrides]]
  (m/map->Model
   (merge {:id "gpt-5.4" :name "GPT-5.4" :provider :openai
           :api :openai-responses :base-url "https://api.openai.com/v1"
           :reasoning true :input [:text]
           :cost {:input 2.5 :output 15 :cache-read 0.25 :cache-write 0}
           :context-window 272000 :max-tokens 128000
           :compat {:supports-strict-mode true}
           :thinking-level-map {:off "none" :minimal nil :low "low" :medium "medium"
                                :high "high" :xhigh "xhigh" :max nil}}
          overrides)))

(t/deftest test-llm-responses-messages
  (let [model (responses-model)
        msgs [{:role :system :content [{:type :text :text "You are helpful"}]}
              {:role :user :content [{:type :text :text "hi"}]}
              {:role :assistant :content [{:type :text :text "answer"}]
               :tool-calls [{:id "call_abc|fc_123" :name "read" :arguments {:path "x"}}]}
              {:role :tool :content [{:type :tool-result :tool_use_id "call_abc|fc_123" :content "file"}]}]
        items (@#'responses/responses-messages model msgs)]
    (t/is (= {:role "developer" :content "You are helpful"} (first items))
          "reasoning models get the system prompt as a developer message")
    (t/is (= {:role "user" :content [{:type "input_text" :text "hi"}]}
             (second items)))
    (let [assistant (nth items 2)
          tool-call (nth items 3)]
      (t/is (= {:type "message" :role "assistant"
                :content [{:type "output_text" :text "answer" :annotations []}]
                :status "completed" :id "msg_pi_1"}
               assistant))
      (t/is (= {:type "function_call" :call_id "call_abc" :id "fc_123"
                :name "read" :arguments "{\"path\":\"x\"}"}
               tool-call)
            "tool calls replay with the split call_id and the stored item id"))
    (t/is (= {:type "function_call_output" :call_id "call_abc" :output "file"}
             (nth items 4))
          "tool results reference the call_id part")))

(t/deftest test-llm-responses-messages-non-reasoning-system-role
  (let [model (responses-model {:reasoning false})
        items (@#'responses/responses-messages
               model
               [{:role :system :content [{:type :text :text "S"}]}
                {:role :user :content [{:type :text :text "u"}]}])]
    (t/is (= {:role "system" :content "S"} (first items))
          "non-reasoning models get the system prompt as a system message")
    (t/is (= [{:role "user" :content [{:type "input_text" :text "u"}]}]
             (rest items)))))

(t/deftest test-llm-responses-messages-cross-provider-tool-call
  ;; a tool call id without the `|` separator replays call_id-only (pi's
  ;; different-model path — the id is omitted to avoid fc_/rs_ pairing
  ;; validation)
  (let [model (responses-model)
        items (@#'responses/responses-messages
               model
               [{:role :user :content [{:type :text :text "u"}]}
                {:role :assistant :content []
                 :tool-calls [{:id "toolu_01ABC" :name "bash" :arguments {:cmd "ls"}}]}
                {:role :tool :content [{:type :tool-result :tool_use_id "toolu_01ABC" :content "out"}]}])
        tool-call (nth items 1)]
    (t/is (= {:type "function_call" :call_id "toolu_01ABC"
              :name "bash" :arguments "{\"cmd\":\"ls\"}"}
             tool-call))
    (t/is (= {:type "function_call_output" :call_id "toolu_01ABC" :output "out"}
             (nth items 2))))
  (t/testing "foreign Responses item ids are omitted for Copilot"
    (let [model (responses-model {:provider :github-copilot})
          items (@#'responses/responses-messages
                 model
                 [{:role :assistant :content []
                   :tool-calls [{:id "call_qwen|V7tZ6kGdR7aeKl14cUq9F_cRTzluuKfD1RULCNcPpxaaOuVPvzVouaxmjWg0G9H"
                                 :name "bash" :arguments {}}]}])]
      (t/is (= {:type "function_call" :call_id "call_qwen"
                :name "bash" :arguments "{}"}
               (first items)))))
  (t/testing "non-fc ids are prefixed for Responses-native providers"
    (let [items (@#'responses/responses-messages
                 (responses-model)
                 [{:role :assistant :content []
                   :tool-calls [{:id "call_openai|opaque-id" :name "bash" :arguments {}}]}])]
      (t/is (= "fc_opaque-id" (:id (first items)))))))

(t/deftest test-llm-responses-payload
  (t/testing "thinking on → reasoning {effort, summary} + include"
    (let [payload (@#'responses/responses-payload (responses-model) :high [] [] "gpt-5.4" nil nil)]
      (t/is (= {:effort "high" :summary "auto"} (:reasoning payload)))
      (t/is (= ["reasoning.encrypted_content"] (:include payload)))
      (t/is (= 128000 (:max_output_tokens payload)))
      (t/is (false? (:store payload)))
      (t/is (true? (:stream payload)))))
  (t/testing "thinking off with an explicit off value → reasoning {effort 'none'}"
    (let [payload (@#'responses/responses-payload (responses-model) nil [] [] "gpt-5.4" nil nil)]
      (t/is (= {:effort "none"} (:reasoning payload)))
      (t/is (nil? (:include payload)))))
  (t/testing "off pinned to null (always-thinking) → no reasoning param"
    (let [model (responses-model {:thinking-level-map {:off nil :low "low" :medium "medium"
                                                       :high "high" :xhigh "xhigh" :max "max"}})
          payload (@#'responses/responses-payload model nil [] [] "gpt-5" nil nil)]
      (t/is (nil? (:reasoning payload)))
      (t/is (nil? (:include payload)))))
  (t/testing "xai always includes the reasoning content; off:null means
             always-thinking (no reasoning param)"
    (let [model (responses-model {:provider :xai :thinking-level-map {:off nil :minimal nil
                                                                      :low "low" :medium "medium" :high "high"}})
          payload (@#'responses/responses-payload model nil [] [] "grok-4.5" nil nil)]
      (t/is (nil? (:reasoning payload)))
      (t/is (= ["reasoning.encrypted_content"] (:include payload)))))
  (t/testing "max_output_tokens floors at 16 (pi #6265)"
    (let [payload (@#'responses/responses-payload (responses-model {:max-tokens 10}) :high [] [] "gpt-5.4" nil nil)]
      (t/is (= 16 (:max_output_tokens payload)))))
  (t/testing "tools carry the JSON schema + strict flag when supported"
    (let [tool (tools/map->Tool {:name "read" :label "Read" :description "d"
                                 :parameters {:type "object" :properties {:path {:type "string"}}}})
          payload (@#'responses/responses-payload (responses-model) :high [] [tool] "gpt-5.4" nil nil)]
      (t/is (= [{:type "function" :name "read" :description "d"
                 :parameters {:type "object" :properties {:path {:type "string"}}}
                 :strict false}]
               (:tools payload)))))
  (t/testing "no strict flag when the provider doesn't support strict mode"
    (let [tool (tools/map->Tool {:name "read" :label "Read" :description "d"
                                 :parameters {:type "object"}})
          payload (@#'responses/responses-payload
                   (responses-model {:compat {}}) :high [] [tool] "grok-4.5" nil nil)]
      (t/is (= [{:type "function" :name "read" :description "d"
                 :parameters {:type "object"}}]
               (:tools payload))))))

;; ─── send-message with no API key ─────────────────────────────────────────

(t/deftest test-llm-no-api-key
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :opencode-go
              :on-error (fn [e] (swap! errors conj e))})]
    @fut  ;; wait for future
    (t/is (pos? (count @errors)))
    (t/is (.contains (first @errors) "No API key"))))

(t/deftest test-llm-no-api-key-anthropic
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :deepseek
              :on-error (fn [e] (swap! errors conj e))})]
    @fut
    (t/is (pos? (count @errors)))
    (t/is (.contains (first @errors) "No API key"))))

;; ─── Tool schema consistency ──────────────────────────────────────────────

(t/deftest test-llm-tool-schemas
  (let [tools (vals (tools/get-all-tools))]
    (doseq [t tools]
      (let [openai-schema (shared/tool->openai-schema t)
            anthropic-schema (shared/tool->anthropic-schema t)]
        (t/is (= "function" (:type openai-schema)))
        (t/is (= (:name t) (get-in openai-schema [:function :name])))
        (t/is (= (:name t) (:name anthropic-schema)))
        (t/is (map? (get-in openai-schema [:function :parameters])))
        (t/is (map? (:input_schema anthropic-schema)))))))

;; ─── SSE parsing helpers ──────────────────────────────────────────────────

(t/deftest test-llm-send-message-returns-future
  (m/load-catalogs!)
  (let [fut (llm/send-message {:provider :opencode-go :model "deepseek-v4-flash"
                               :api-key "test" :on-error (fn [_])})]
    (t/is (future? fut))))

;; ─── Edge: empty tools list ───────────────────────────────────────────────

(t/deftest test-llm-no-tools
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :opencode-go
              :model "deepseek-v4-flash"
              :tools []
              :on-error (fn [e] (swap! errors conj e))})]
    @fut
    (t/is (pos? (count @errors)))))

;; ─── Multiple providers ──────────────────────────────────────────────────

(t/deftest test-llm-all-providers-resolve
  (m/load-catalogs!)
  (t/is (= 40 (count (m/get-providers))))
  (doseq [p [:opencode-go :opencode :deepseek :github-copilot :openai :xai
             :openai-codex :azure-openai-responses :anthropic :google :groq
             :cerebras :huggingface :moonshotai :moonshotai-cn :xiaomi
             :xiaomi-token-plan-cn :xiaomi-token-plan-ams :xiaomi-token-plan-sgp
             :qwen-token-plan :qwen-token-plan-cn :qwen-token-plan-individual
             :minimax :minimax-cn :nvidia :openrouter :fireworks
             :vercel-ai-gateway :zai :zai-coding-cn :together :baseten
             :ant-ling :kimi-coding :cloudflare-workers-ai :cloudflare-ai-gateway
             :mistral :google-vertex :amazon-bedrock :commandcode]]
    (t/is (some? (m/get-provider p)) (str p " has a catalog entry"))))

;; ─── Image block conversion ───────────────────────────────────────────────

(t/deftest test-llm-openai-image-conversion
  (let [msgs [{:role :user
               :content [{:type :text :text "look"}
                         {:type :image :data "AA" :mime-type "image/png"}]}]
        converted (@#'shared/openai-messages msgs)]
    (t/is (= [{:type "text" :text "look"}
              {:type "image_url"
               :image_url {:url "data:image/png;base64,AA"}}]
             (:content (first converted)))
          "image blocks convert to OpenAI image_url blocks")))

(t/deftest test-llm-openai-completions-cross-provider-tool-id
  (let [id "call_qwen|V7tZ6kGdR7aeKl14cUq9F_cRTzluuKfD1RULCNcPpxaaOuVPvzVouaxmjWg0G9H"
        msgs [{:role :assistant :content []
               :tool-calls [{:id id :name "bash" :arguments {}}]}
              {:role :tool :content [{:type :tool_result :tool_use_id id :content "done"}]}]
        converted (@#'shared/openai-messages msgs :opencode)
        assistant-id (-> converted first :tool_calls first :id)
        result-id (-> converted second :tool_call_id)]
    (t/is (= assistant-id result-id))
    (t/is (<= (count assistant-id) 40))
    (t/is (re-matches #"[a-zA-Z0-9_-]+" assistant-id))))

(t/deftest test-llm-anthropic-tool-id-normalization
  (let [id "call_qwen|opaque/tool-id"
        converted (@#'anthropic/anthropic-messages
                   [{:role :assistant :content []
                     :tool-calls [{:id id :name "bash" :arguments {}}]}])]
    (t/is (= "call_qwen_opaque_tool-id"
             (-> converted first :content first :id)))
    (t/is (<= (count (-> converted first :content first :id)) 64))))

(t/deftest test-llm-anthropic-image-conversion
  (let [msgs [{:role :user
               :content [{:type :text :text "look"}
                         {:type :image :data "AA" :mime-type "image/png"}]}]
        converted (@#'anthropic/anthropic-messages msgs)]
    (t/is (= [{:type "text" :text "look"}
              {:type "image"
               :source {:type "base64" :media_type "image/png" :data "AA"}}]
             (:content (first converted)))
          "image blocks convert to Anthropic image blocks")))

(t/deftest test-llm-anthropic-empty-messages-dropped
  ;; pi convertMessages: blank text blocks are dropped and a message left
  ;; without sendable content is skipped — Anthropic rejects empty content
  ;; arrays, so a recorded empty completion (clean stream, zero blocks) must
  ;; not 400 every later turn of a resumed session.
  (let [converted (@#'anthropic/anthropic-messages
                   [;; empty assistant completion (the recorded failure mode)
                    {:role :assistant :content []}
                    ;; whitespace-only assistant message
                    {:role :assistant :content [{:type :text :text "   "}]}
                    ;; whitespace-only user text is dropped, but the image survives
                    {:role :user
                     :content [{:type :text :text "  "}
                               {:type :image :data "AA" :mime-type "image/png"}]}
                    ;; real messages pass through untouched
                    {:role :user :content [{:type :text :text "hi"}]}
                    {:role :assistant :content []
                     :tool-calls [{:id "tc1" :name "bash" :arguments {}}]}])]
    (t/is (= [{:role "user"
               :content [{:type "image"
                          :source {:type "base64" :media_type "image/png" :data "AA"}}]}
              {:role "user" :content "hi"}
              {:role "assistant"
               :content [{:type "tool_use"
                          :id "tc1"
                          :name "bash"
                          :input {}}]}]
             converted)
          "empty/blank assistant entries are dropped, images survive, tool calls keep the message")))

(t/deftest test-llm-custom-role-maps-to-user
  ;; G10: custom messages (from custom_message entries) are sent as user
  ;; messages (pi: convertToLlm custom→user)
  (let [msgs [{:role :custom
               :custom-type :note
               :content [{:type :text :text "hello from an extension"}]}]
        openai (@#'shared/openai-messages msgs)
        openai-reasoning (@#'shared/openai-messages-with-reasoning msgs)
        anthropic (@#'anthropic/anthropic-messages msgs)
        [google _] (@#'google/google-messages msgs {:id "m" :name "M"})]
    (t/is (= "user" (:role (first openai))))
    (t/is (= "user" (:role (first openai-reasoning))))
    (t/is (= "user" (:role (first anthropic))))
    (t/is (= "user" (:role (first google))))
    (t/is (= "hello from an extension" (:content (first openai)))
          "content preserved (openai-content returns plain text without images)")
    (t/is (= "hello from an extension" (:text (first (:parts (first google)))))
          "google content preserved"))
  ;; string content is normalized by the context projection (context-messages),
  ;; but the converters accept string content via the shared content helpers
  (let [msgs [{:role :custom :custom-type :note :content "plain string"}]
        openai (@#'shared/openai-messages msgs)]
    (t/is (= "user" (:role (first openai))))))

(t/deftest test-llm-tool-result-images-conversion
  (let [msgs [{:role :tool
               :content [{:type :tool_result :tool_use_id "t1" :content "saw it"}]
               :images [{:data "AA" :mime-type "image/png"}]}]
        openai (@#'shared/openai-messages msgs)
        anthropic (@#'anthropic/anthropic-messages msgs)]
    (t/is (= [{:type "text" :text "saw it"}
              {:type "image_url"
               :image_url {:url "data:image/png;base64,AA"}}]
             (:content (first openai)))
          "tool-result :images convert to OpenAI image_url blocks")
    (t/is (= [{:type "tool_result"
               :tool_use_id "t1"
               :content [{:type "text" :text "saw it"}
                         {:type "image"
                          :source {:type "base64" :media_type "image/png" :data "AA"}}]}]
             (:content (first anthropic)))
          "tool-result :images convert to Anthropic image blocks")))

(t/deftest test-llm-no-images-backward-compat
  (let [msgs [{:role :user :content [{:type :text :text "hi"}]}]
        openai (@#'shared/openai-messages msgs)
        anthropic (@#'anthropic/anthropic-messages msgs)]
    (t/is (= "hi" (:content (first openai)))
          "text-only messages keep string content for OpenAI")
    (t/is (= "hi" (:content (first anthropic)))
          "text-only messages keep string content for Anthropic")))

(t/deftest test-llm-assistant-thinking-roundtrip
  ;; pi round-trips thinking only for same-provider-same-model messages and
  ;; degrades everything else: assistant messages with :thinking send it back
  ;; as reasoning_content (DeepSeek thinking mode) only when the recorded
  ;; provenance matches the request's target model — a mid-session /model
  ;; switch must not feed another model's chain-of-thought back as if it were
  ;; the new model's own reasoning (DeepSeek-class models derail into
  ;; re-running their last tool call on such transcripts).
  (let [own [{:role :assistant
              :content [{:type :text :text "answer"}]
              :thinking "let me think\nabout it"
              :provider :commandcode :model "deepseek/deepseek-v4-flash"}]
        openai (@#'shared/openai-messages own :commandcode "deepseek/deepseek-v4-flash")
        reasoning (@#'shared/openai-messages-with-reasoning own :commandcode "deepseek/deepseek-v4-flash")]
    (t/is (= "let me think\nabout it" (:reasoning_content (first openai)))
          "same-model thinking round-trips")
    (t/is (= "let me think\nabout it" (:reasoning_content (first reasoning)))
          "with-reasoning variant uses same-model thinking"))
  ;; cross-model / legacy-unstamped messages never leak their CoT; the
  ;; with-reasoning variant pads the required empty field instead
  (doseq [foreign [{:role :assistant
                    :content [{:type :text :text "answer"}]
                    :thinking "CoT produced by hetzner Qwen"
                    :provider :hetzner :model "Qwen3.8-27B"}
                   {:role :assistant
                    :content [{:type :text :text "answer"}]
                    :thinking "recorded before kmet stamped provenance"}]
          :let [openai (@#'shared/openai-messages [foreign] :commandcode "deepseek/deepseek-v4-flash")
                reasoning (@#'shared/openai-messages-with-reasoning [foreign] :commandcode "deepseek/deepseek-v4-flash")]]
    (t/is (nil? (:reasoning_content (first openai)))
          "foreign/unstamped thinking is not replayed")
    (t/is (= "" (:reasoning_content (first reasoning)))
          "with-reasoning pads the empty field for deepseek thinking mode"))
  ;; no target identity given (legacy call shape): nothing is replayed
  (let [msgs [{:role :assistant
               :content [{:type :text :text "answer"}]
               :thinking "orphan CoT"}]]
    (t/is (nil? (:reasoning_content (first (@#'shared/openai-messages msgs)))))
    (t/is (= "" (:reasoning_content
                 (first (@#'shared/openai-messages-with-reasoning msgs))))))
  ;; messages without thinking keep the empty-field compat for
  ;; requires-reasoning-content-on-assistant-messages providers
  (let [msgs [{:role :assistant :content [{:type :text :text "answer"}]}]]
    (t/is (nil? (:reasoning_content (first (@#'shared/openai-messages msgs)))))
    (t/is (= "" (:reasoning_content (first (@#'shared/openai-messages-with-reasoning msgs)))))))

(t/deftest test-llm-empty-assistant-dropped
  ;; pi: "some providers require either content or tool_calls, but not none" —
  ;; an empty assistant message (e.g. a resumed session whose last turn ended
  ;; with only reasoning, or an aborted response that got no content) must be
  ;; skipped, not sent as content "" ("Invalid assistant message: content or
  ;; tool_calls must be set")
  (let [msgs [{:role :user :content [{:type :text :text "hi"}]}
              {:role :assistant :content []}
              {:role :assistant :content [] :thinking "only reasoning"}
              {:role :assistant :content [{:type :text :text "answer"}]}]
        openai (@#'shared/openai-messages msgs)
        reasoning (@#'shared/openai-messages-with-reasoning msgs)
        roles (mapv :role openai)
        roles-reasoning (mapv :role reasoning)]
    (t/is (= ["user" "assistant"] roles)
          "empty assistant messages are dropped")
    (t/is (= "answer" (:content (last openai)))
          "non-empty assistant messages are kept")
    (t/is (= ["user" "assistant"] roles-reasoning)
          "with-reasoning variant drops empty assistant messages too")
    (t/is (= "" (:reasoning_content (last reasoning)))
          "with-reasoning variant keeps the empty-field compat on kept messages"))
  ;; a tool-call assistant message with no text is kept (content stays unset)
  (let [msgs [{:role :assistant
               :content []
               :tool-calls [{:id "t1" :name "bash" :arguments {}}]}]
        openai (@#'shared/openai-messages msgs)
        reasoning (@#'shared/openai-messages-with-reasoning msgs)]
    (t/is (= "t1" (-> openai first :tool_calls first :id)))
    (t/is (nil? (:content (first openai)))
          "content is not set to empty string")
    (t/is (= "t1" (-> reasoning first :tool_calls first :id)))
    (t/is (= "" (:reasoning_content (first reasoning))))))

;; ─── Bash result conversion (pi: convertToLlm bashExecution) ──────────────

(t/deftest test-llm-bash-conversion
  (let [msgs [{:role :bash :command "git st" :output "clean\n" :exit-code 0
               :exclude-from-context? false}
              {:role :bash :command "git st" :output "clean\n" :exit-code 0
               :exclude-from-context? true}]
        openai (@#'shared/openai-messages msgs)
        reasoning (@#'shared/openai-messages-with-reasoning msgs)
        anthropic (@#'anthropic/anthropic-messages msgs)]
    (doseq [converted [openai reasoning anthropic]]
      (t/is (= 1 (count converted)) "excluded bash entries are dropped")
      (t/is (= "user" (:role (first converted))) "bash entries become user messages")
      (let [text (:content (first converted))]
        (t/is (str/includes? text "Ran `git st`"))
        (t/is (str/includes? text "clean"))))))

(t/deftest test-llm-bash-conversion-format
  ;; pi: bashExecutionToText shape — output block, exit code, truncation note
  (let [msgs [{:role :bash :command "false" :output "" :exit-code 1
               :exclude-from-context? false
               :truncated true :full-output-path "/tmp/out"}]
        openai (@#'shared/openai-messages msgs)
        text (:content (first openai))]
    (t/is (str/includes? text "Ran `false`"))
    (t/is (str/includes? text "(no output)"))
    (t/is (str/includes? text "Command exited with code 1"))
    (t/is (str/includes? text "[Output truncated. Full output: /tmp/out]"))))

(t/deftest test-llm-bash-cancelled-no-exit-code
  (let [msgs [{:role :bash :command "sleep 10" :output "" :exit-code nil
               :cancelled true :exclude-from-context? false}]
        openai (@#'shared/openai-messages msgs)
        text (:content (first openai))]
    (t/is (str/includes? text "(command cancelled)"))
    (t/is (not (str/includes? text "Command exited with code")))))

(t/deftest test-llm-transport-error-message
  ;; A connect-time failure on this JDK surfaces as a ConnectException with a
  ;; nil message — transport-error-message must still mark it retryable
  ;; (pi: undici always reports transport failures as "fetch failed"), or
  ;; auto-retry on network errors silently dies.
  (let [te @#'shared/transport-error-message]
    (t/is (= "network error: ConnectException"
             (te (java.net.ConnectException. nil))))
    (t/is (= "network error: request timed out"
             (te (java.net.http.HttpTimeoutException. "request timed out"))))
    (t/is (= "network error: Connection reset"
             (te (java.net.SocketException. "Connection reset"))))
    (t/is (= "network error: UnknownHostException"
             (te (java.net.UnknownHostException. nil))))
    ;; HTTP/2 RST_STREAM surfaces as a plain IOException (java.net.http
    ;; builds the message from the frame's error code) — same class of
    ;; transport reset as SocketException "Connection reset", so it gets
    ;; the same stable retryable token.
    (t/is (= "network error: Received RST_STREAM: Protocol error"
             (te (java.io.IOException. "Received RST_STREAM: Protocol error"))))
    (t/is (= "network error: Received RST_STREAM: CANCEL"
             (te (java.io.IOException. "Received RST_STREAM: CANCEL"))))
    ;; Other IOExceptions are not network errors
    (t/is (= "Broken pipe" (te (java.io.IOException. "Broken pipe"))))
    ;; Non-network exceptions keep their message / legacy fallback
    (t/is (= "Invalid API key" (te (ex-info "Invalid API key" {}))))
    (t/is (= "Request failed: ExceptionInfo" (te (ex-info nil {}))))
    ;; HTTP error responses: 'Exceptional status code: N' with
    ;; the full response in ex-data — the provider's error message must
    ;; surface so overflow/throttle classifiers see the real error
    (let [overflow "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"This model's maximum context length is 1048576 tokens. However, you requested 1048586 tokens. Please reduce the length of the messages or completion.\"}}"
          pairing "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'.\"}}"
          http-err (fn [body] (ex-info "Exceptional status code: 400" {:status 400 :body body}))]
      (t/is (= "This model's maximum context length is 1048576 tokens. However, you requested 1048586 tokens. Please reduce the length of the messages or completion."
               (te (http-err overflow)))
            "HTTP error bodies surface the provider's :error :message")
      (t/is (= "An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'."
               (te (http-err pairing)))
            "non-overflow provider messages pass through")
      (t/is (= "rate limit exceeded" (te (http-err "rate limit exceeded")))
            "plain-text bodies pass through trimmed")
      (t/is (= "Exceptional status code: 400" (te (http-err nil)))
            "missing body keeps the original message")
      ;; the surfaced overflow message must classify as a context overflow
      (t/is (loop/context-overflow? (te (http-err overflow)))
            "the surfaced body feeds the overflow classifier")
      (t/is (not (loop/context-overflow? (te (http-err pairing))))
            "other provider errors are not misclassified as overflow"))
    ;; 429/5xx get an 'HTTP <status>: ' prefix — the retry classifier's
    ;; status-code patterns must match even opaque gateway bodies that carry
    ;; no status token of their own (e.g. agentgateway's 500 'ext_proc
    ;; failed: no more response messages'); 4xx above stays unprefixed
    (let [http-status (fn [status body]
                        (ex-info (str "Exceptional status code: " status)
                                 {:status status :body body}))]
      (t/is (= "HTTP 500: ext_proc failed: no more response messages"
               (te (http-status 500 "ext_proc failed: no more response messages"))))
      (t/is (loop/retryable-error?
             (te (http-status 500 "ext_proc failed: no more response messages")))
            "opaque 500 bodies classify as retryable via the status token")
      (t/is (= "HTTP 502: Bad Gateway" (te (http-status 502 "Bad Gateway"))))
      (t/is (= "HTTP 503: upstream unavailable" (te (http-status 503 "upstream unavailable"))))
      (t/is (= "HTTP 504: Gateway Timeout" (te (http-status 504 "Gateway Timeout"))))
      (t/is (= "HTTP 429: slow down" (te (http-status 429 "slow down")))
            "429 is prefixed too — the regex's '429' token now always matches")
      (t/is (loop/retryable-error? (te (http-status 429 "slow down"))))
      (t/is (loop/retryable-error?
             "Proxy request failed: exceeded request buffer limit while retrying upstream")
            "OpenRouter buffer-limit wrapper failures retry (pi RETRYABLE pattern)")
      ;; JSON error bodies keep the provider message, prefixed
      (t/is (= "HTTP 500: something exploded"
               (te (http-status 500 "{\"error\":{\"message\":\"something exploded\"}}"))))
      ;; quota/billing bodies stay non-retryable even on 5xx (the
      ;; non-retryable quota patterns take precedence over the status token)
      (t/is (not (loop/retryable-error? (te (http-status 500 "billing suspended"))))))))

;; ─── Thinking level machinery (pi: clampThinkingLevel) ─────────────────────

(defn- tmodel
  "Model map for thinking tests."
  [& {:keys [id reasoning tlm compat max-tokens]
      :or {id "m1" reasoning true compat nil max-tokens 32000}}]
  {:id id :name "M1" :reasoning reasoning
   :thinking-level-map tlm :compat compat :max-tokens max-tokens})

(t/deftest test-supported-thinking-levels
  (t/is (= [:off] (shared/get-supported-thinking-levels (tmodel :reasoning false)))
        "non-reasoning models only support :off")
  (t/is (= [:off :minimal :low :medium :high]
           (shared/get-supported-thinking-levels (tmodel :tlm nil)))
        "no map → everything except :xhigh/:max")
  (t/is (= [:off :high :max]
           (shared/get-supported-thinking-levels
            (tmodel :tlm {:minimal nil :low nil :medium nil :high "high" :max "max"})))
        "null map values mark levels unsupported")
  (t/is (= [:high]
           (shared/get-supported-thinking-levels
            (tmodel :tlm {:off nil :minimal nil :low nil :medium nil})))
        "a null :off means thinking cannot be disabled"))

(t/deftest test-clamp-thinking-level
  (let [deepseek (tmodel :tlm {:minimal nil :low nil :medium nil :high "high" :max "max"})]
    (t/is (= :max (@#'shared/clamp-thinking-level deepseek :low))
          "unsupported levels clamp to the highest supported")
    (t/is (= :max (@#'shared/clamp-thinking-level deepseek :max)))
    (t/is (= :off (@#'shared/clamp-thinking-level deepseek :off)))
    (t/is (= :max (@#'shared/clamp-thinking-level deepseek :xhigh))
          "xhigh unsupported → clamps to :max"))
  (t/is (= :high (@#'shared/clamp-thinking-level (tmodel :tlm nil) :max))
        "no map → :max clamps to :high")
  (t/is (= :high (@#'shared/clamp-thinking-level (tmodel :tlm nil) nil))
        "nil → clamps to highest supported")
  (t/is (= :off (@#'shared/clamp-thinking-level (tmodel :reasoning false) :high))
        "non-reasoning model → everything clamps to :off"))

(t/deftest test-effective-effort
  (t/is (nil? (@#'shared/effective-effort (tmodel) :off)))
  (t/is (= :low (@#'shared/effective-effort (tmodel) :low)))
  (t/is (= :high (@#'shared/effective-effort (tmodel) :max))
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

;; ─── Google message conversion ─────────────────────────────────────────────

(t/deftest test-google-messages
  (let [model {:id "gemini-2.5-pro" :name "Gemini"}
        [contents system] (@#'google/google-messages
                           [{:role :system :content [{:type :text :text "sys"}]}
                            {:role :user :content [{:type :text :text "hi"}]}
                            {:role :assistant :content [{:type :text :text "ok"}]
                             :tool-calls [{:id "t1" :name "read" :arguments {:path "a"}}]}
                            {:role :tool :tool-name "read" :is-error false
                             :content [{:type :tool_result :tool_use_id "t1" :content "saw it"}]}]
                           model)]
    (t/is (= "sys" system))
    (t/is (= [{:role "user" :parts [{:text "hi"}]}
              {:role "model" :parts [{:text "ok"}
                                     {:functionCall {:name "read" :args {:path "a"}}}]}
              {:role "user" :parts [{:functionResponse {:name "read" :response {:output "saw it"}}}]}]
             contents)
          "gemini-2.5 does not require tool-call ids (pi requiresToolCallId)"))
  (t/testing "gemini-3 requires explicit ids on functionCall/functionResponse"
    (let [model {:id "gemini-3-pro-preview" :name "Gemini 3"}
          [contents _] (@#'google/google-messages
                        [{:role :assistant :content [{:type :text :text "ok"}]
                          :tool-calls [{:id "t1/x" :name "read" :arguments {:path "a"}}]}
                         {:role :tool :tool-name "read" :is-error false
                          :content [{:type :tool_result :tool_use_id "t1/x" :content "saw it"}]}]
                        model)]
      (t/is (= {:functionCall {:name "read" :args {:path "a"} :id "t1_x"}}
               (second (:parts (first contents))))
            "ids are echoed, sanitized to [a-zA-Z0-9_-] (pi)")
      (t/is (= {:functionResponse {:name "read" :response {:output "saw it"} :id "t1_x"}}
               (first (:parts (second contents))))))))

(t/deftest test-google-empty-messages-dropped
  ;; pi google-shared: empty text parts are dropped and a message whose parts
  ;; come out empty is skipped ("the model intermittently ends mid-task turns
  ;; with a thought-only STOP (empty completion, no tool call)") — a recorded
  ;; empty/blank assistant entry must not reach Gemini as {:text ""}.
  (let [model {:id "gemini-2.5-pro" :name "Gemini"}
        [contents _] (@#'google/google-messages
                      [{:role :assistant :content []}
                       {:role :assistant :content [{:type :text :text "   "}]}
                       {:role :user :content []}
                       {:role :assistant :content []
                        :tool-calls [{:id "t1" :name "bash" :arguments {}}]}
                       {:role :assistant :content [{:type :text :text "real"}]}
                       {:role :user :content [{:type :text :text "  "}]}]
                      model)]
    (t/is (= [{:role "model" :parts [{:functionCall {:name "bash" :args {}}}]}]
             (take 1 contents))
          "empty and blank-only assistants are dropped; a tool call keeps the message")
    (t/is (= [{:role "model" :parts [{:text "real"}]}
              {:role "user" :parts [{:text "  "}]}]
             (drop 1 contents))
          "non-blank content passes through; whitespace-only USER text is kept (pi parity)")))

(t/deftest test-anthropic-thinking-replay
  ;; pi convertMessages thinking matrix: signed same-model reasoning replays
  ;; as a leading thinking block (required before tool_use with thinking on);
  ;; unsigned or cross-provider reasoning degrades to plain text; legacy
  ;; entries without provenance degrade like unsigned ones.
  (let [msg {:role :assistant :content []
             :thinking "r" :thinking-signature "SIG"
             :provider :anthropic :model "claude"
             :tool-calls [{:id "t" :name "bash" :arguments {}}]}
        ctx {:provider :anthropic :model "claude"}]
    (let [blocks (:content (first (anthropic/anthropic-messages [msg] ctx)))]
      (t/is (= [{:type "thinking" :thinking "r" :signature "SIG"}
                {:type "tool_use" :id "t" :name "bash" :input {}}]
               blocks)
            "signed same-model replay: thinking block precedes tool_use"))
    (let [blocks (:content (first (anthropic/anthropic-messages
                                   [(dissoc msg :thinking-signature)] ctx)))]
      (t/is (= [{:type "text" :text "r"}
                {:type "tool_use" :id "t" :name "bash" :input {}}] blocks)
            "unsigned same-model reasoning degrades to plain text before tool_use"))
    (let [blocks (:content (first (anthropic/anthropic-messages
                                   [(assoc msg :provider :opencode :model "other")] ctx)))]
      (t/is (= [{:type "text" :text "r"}
                {:type "tool_use" :id "t" :name "bash" :input {}}] blocks)
            "cross-provider reasoning degrades to plain text"))
    (let [blocks (:content (first (anthropic/anthropic-messages
                                   [(assoc msg :model "other")]
                                   {:provider :anthropic :model "claude"
                                    :allow-empty-signature true})))]
      (t/is (= [{:type "thinking" :thinking "r" :signature ""}
                {:type "tool_use" :id "t" :name "bash" :input {}}] blocks)
            "allow-empty-signature compat keeps unsigned reasoning as a block"))
    (let [blocks (:content (first (anthropic/anthropic-messages
                                   [(dissoc msg :thinking-signature :provider :model)] ctx)))]
      (t/is (= [{:type "text" :text "r"}
                {:type "tool_use" :id "t" :name "bash" :input {}}] blocks)
            "legacy entries without provenance degrade like unsigned"))))

(t/deftest test-google-thinking-replay
  ;; pi google-shared: same-provider-same-model reasoning replays as a
  ;; thought part (thoughtSignature echoed when captured); cross-provider
  ;; reasoning degrades to a plain text part; empty assistants stay dropped.
  (let [model {:id "gemini-2.5-pro" :name "Gemini"}
        convert #(@#'google/google-messages % model {:provider :google})
        [contents _] (convert
                      [{:role :assistant :content []}
                       {:role :assistant :content []
                        :thinking "h" :thinking-signature "GSIG"
                        :provider :google :model "gemini-2.5-pro"}
                       {:role :assistant :content []
                        :thinking "h" :provider :google :model "gemini-2.5-pro"}
                       {:role :assistant :content []
                        :thinking "x" :provider :other :model "m"}
                       {:role :user :content [{:type :text :text "q"}]}])]
    (t/is (= [{:role "model" :parts [{:text "h" :thought true :thoughtSignature "GSIG"}]}
              {:role "model" :parts [{:text "h" :thought true}]}
              {:role "model" :parts [{:text "x"}]}
              {:role "user" :parts [{:text "q"}]}]
             contents)
          "thought parts carry the signature only for same-model messages")))

(t/deftest test-bedrock-thinking-signature-replay
  ;; pi supportsThinkingSignature: only Claude accepts the signature field —
  ;; signed same-model reasoning carries it, unsigned Claude reasoning falls
  ;; back to plain text, non-Claude models keep signature-less reasoningContent.
  (let [model {:id "anthropic.claude-sonnet-4-5-20250929-v1:0"
               :name "Claude Sonnet 4.5" :provider :amazon-bedrock
               :api :bedrock-converse-stream}
        convert (fn [m] (first (@#'bedrock/bedrock-messages [m] model :short
                                                            {:provider :amazon-bedrock})))
        msg {:role :assistant :content []
             :thinking "r" :thinking-signature "BSIG"
             :provider :amazon-bedrock :model (:id model)}
        signed (:content (first (convert msg)))
        unsigned (:content (first (convert (dissoc msg :thinking-signature))))
        nc-model {:id "amazon.nova-pro-v1" :name "Nova" :provider :amazon-bedrock}]
    (t/is (= [{:reasoningContent {:reasoningText {:text "r" :signature "BSIG"}}}] signed))
    (t/is (= [{:text "r"}] unsigned) "unsigned Claude reasoning degrades to text")
    (let [nc-msg (assoc msg :thinking-signature "X")
          nc-result (@#'bedrock/bedrock-messages
                     [nc-msg] nc-model :short {:provider :amazon-bedrock})
          nc-block (-> nc-result ffirst :content first)]
      (t/is (= {:reasoningContent {:reasoningText {:text "r"}}} nc-block)
            "non-Claude models reject signatures — omitted (pi)"))))

(t/deftest test-google-event-parsing
  (t/is (= [{:type :text :content "Hi"}]
           (sse/parse-google-event
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}")))
  (t/is (= [{:type :thinking :content "think"}]
           (sse/parse-google-event
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"think\",\"thought\":true}]}}]}")))
  (let [evts (sse/parse-google-event
              "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"read\",\"args\":{\"path\":\"a\"}}}]}}]}")]
    (t/is (= :tool-call (:type (first evts))))
    (t/is (= "read" (:name (first evts))))
    (t/is (= {:path "a"} (:arguments (first evts)))))
  (t/is (= [{:type :done :stop-reason :stop}]
           (sse/parse-google-event
            "{\"candidates\":[{\"finishReason\":\"STOP\"}]}")))
  (t/is (= :length (:stop-reason (first (sse/parse-google-event
                                         "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}")))))
  (t/testing "a chunk can carry several events (pi reads all parts per chunk)"
    (let [evts (sse/parse-google-event
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"},{\"functionCall\":{\"name\":\"read\",\"args\":{}}}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":20,\"totalTokenCount\":30}}")]
      (t/is (= [:usage :text :tool-call :done] (mapv :type evts))
            "usage, all parts, and finish are all emitted from one chunk")))
  (t/is (= [{:type :usage :usage {:input 10 :output 20 :cache-read 0 :cache-write 0
                                  :reasoning 0 :total-tokens 30}}]
           (sse/parse-google-event
            "{\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":20,\"totalTokenCount\":30}}"))))

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
      (t/is (true? (:supports-strict-mode d)))
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

(t/deftest test-reasoning-content-gating-data
  ;; pi: requiresReasoningContentOnAssistantMessages gates reasoning_content
  ;; on assistant messages — carried by deepseek/opencode-go deepseek-v4
  ;; models, absent elsewhere.
  (m/load-catalogs!)
  (t/is (true? (:requires-reasoning-content-on-assistant-messages
                (:compat (m/get-model :deepseek "deepseek-flash")))))
  (t/is (true? (:requires-reasoning-content-on-assistant-messages
                (:compat (m/get-model :opencode-go "deepseek-v4-flash")))))
  (t/is (nil? (:requires-reasoning-content-on-assistant-messages
               (:compat (m/get-model :opencode "glm-5.2")))))
  (t/is (nil? (:requires-reasoning-content-on-assistant-messages
               (:compat (first (m/get-models :github-copilot)))))))

;; ─── Regression: openai-completions trailing usage chunk (footer tokens/cost) ──

(t/deftest test-openai-completions-trailing-usage-chunk
  ;; Regression: the openai-completions wire must deliver the provider usage
  ;; from the FINAL usage-only chunk. Standard OpenAI-compatible servers
  ;; (opencode-go mimo, hetzner, opencode, ...) stream: content chunks → a
  ;; finish_reason chunk → a usage-only chunk → [DONE]. Emitting on-done at
  ;; the first :done (the finish_reason chunk) let call-llm deliver the run
  ;; result before the usage chunk was processed — the assistant message was
  ;; persisted without :usage, so the footer showed no token counts and no
  ;; price while the context % (chars-based fallback estimate) kept
  ;; updating. The terminal done must be deferred until the whole stream is
  ;; consumed (pi resolves its stream only after the final chunk), so the
  ;; trailing usage chunk is dispatched before on-done fires.
  (m/load-catalogs!)
  (let [dir (str "target/test-llm-trailing-usage-" (System/currentTimeMillis))
        sess (session/create-session dir)
        ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader.
                                (java.io.InputStreamReader. (.getInputStream s)))]
                       ;; drain request headers
                       (while (seq (or (.readLine rdr) "")) nil)
                       (let [out (.getOutputStream s)
                             stream-body (str "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n"
                                              "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                                              "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":200,\"total_tokens\":1200,\"prompt_tokens_details\":{\"cached_tokens\":300}}}\n\n"
                                              "data: [DONE]\n\n")]
                         (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                     "Content-Type: text/event-stream\r\n"
                                                     "Content-Length: " (count stream-body) "\r\n\r\n"
                                                     stream-body)))
                         (.flush out)
                         (.close s)))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        ag (loop/make-agent-state :session sess :provider :opencode-go :model "mimo-v2.5"
                                  :base-url (str "http://localhost:" port "/v1/chat/completions"))]
    (try
      (with-redefs [cfg/get-api-key (fn [_] "sk-test")]
        @(loop/run-agent-turn ag {:message "hi" :on-done (fn [_]) :on-error (fn [_])}))
      (let [assistant (last (filter #(= :assistant (:role %)) (session/get-branch sess)))]
        (t/is (= :idle @(:status ag)))
        (t/is (= {:prompt_tokens 1000 :completion_tokens 200 :total_tokens 1200
                  :prompt_tokens_details {:cached_tokens 300}
                  :cost {:input 9.800000000000001E-5 :output 5.6000000000000006E-5
                         :cache-read 8.4E-7 :cache-write 0.0 :total 1.5484E-4}}
                 (:usage assistant))
              "the trailing usage chunk reaches the persisted assistant message (mimo-v2.5 rates: 700 input = 0.14/1M, 200 output = 0.28/1M, 300 cache-read = 0.0028/1M)"))
      (finally
        (.close ss)
        (fs/delete-tree dir)))))

;; ─── Transport total timeout follows the configured idle timeout ──────────

(t/deftest ^:slow test-llm-request-timeout-follows-idle
  (m/load-catalogs!)
  ;; A server that accepts the request but never responds: the request must
  ;; error via the configured total timeout (~idle-timeout-ms), not the old
  ;; hardcoded 120s (pi: SDK timeoutMs ?? httpIdleTimeoutMs).
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader.
                                (java.io.InputStreamReader. (.getInputStream s)))]
                       ;; drain request headers, then stall — never respond
                       (while (seq (or (.readLine rdr) "")) nil)
                       (Thread/sleep 5000)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        t0 (System/currentTimeMillis)
        errors (atom [])
        fut (llm/send-message {:provider :opencode-go
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/v1/chat/completions")
                               :model "deepseek-v4-flash"
                               :messages [{:role "user" :content "hi"}]
                               :idle-timeout-ms 1500
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= 1 (count @errors)))
      (let [elapsed (- (System/currentTimeMillis) t0)]
        ;; Window 20s: the error message assertion below guards the mechanism
        ;; (must contain "timed out" — a broken total timeout yields the EOF
        ;; error instead); this window only allows for scheduling delay on
        ;; loaded hosts while still rejecting the old 120s hardcoded timeout.
        (t/is (< elapsed 20000)
              (str "errored via the configured timeout (took " elapsed "ms)"))
        (t/is (str/includes? (first @errors) "timed out")))
      (finally
        (.close ss)))))

;; guards the java.net.http body-stream deadlock — ^:bb-only: jolt's
;; provider streams ride curl (jolt-port.md B1), where the total timeout
;; reports its own transport error instead of the SSE idle message
(t/deftest ^:slow ^:bb-only test-llm-body-stall-idle-timeout-completes
  (m/load-catalogs!)
  ;; A server that sends response headers then stalls the body: the SSE idle
  ;; timeout must fire and the request future must complete promptly. Before
  ;; the interrupt/join-before-close fix, closing the java.net.http body
  ;; stream while the daemon read was in flight deadlocked the future.
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader.
                                (java.io.InputStreamReader. (.getInputStream s)))]
                       (while (seq (or (.readLine rdr) "")) nil)
                       (let [out (.getOutputStream s)]
                         (.write out (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n"))
                         (.flush out)
                         (Thread/sleep 30000))
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        errors (atom [])
        fut (llm/send-message {:provider :opencode-go
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/v1/chat/completions")
                               :model "deepseek-v4-flash"
                               :messages [{:role "user" :content [{:type :text :text "hi"}]}]
                               :idle-timeout-ms 1000
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      (t/is (not= :timeout (deref fut 8000 :timeout))
            "request future completes instead of deadlocking on close")
      (t/is (= ["Stream idle timeout after 1000 ms (no data received)"]
               @errors))
      (finally
        (.close ss)))))

;; ─── Per-message cost attachment (Phase 5) ────────────────────────────────

(t/deftest test-usage-with-cost
  (m/load-catalogs!)
  (let [model (m/get-model :deepseek "deepseek-v4-pro")
        norm {:input 800 :output 500 :cache-read 200 :cache-write 0}]
    (t/testing "provider-native usage gains the pi-shaped :cost breakdown"
      (let [usage (@#'shared/usage-with-cost model
                                             {:prompt_tokens 1000 :completion_tokens 500
                                              :prompt_tokens_details {:cached_tokens 200}})]
        (t/is (= 1000 (:prompt_tokens usage)) "provider-native keys preserved")
        (t/is (= (m/calculate-cost model norm) (:cost usage))
              "cost equals calculate-cost over the normalized tokens")))
    (t/testing "google's already-normalized usage is priced too"
      (let [usage (@#'shared/usage-with-cost model {:input 10 :output 20
                                                    :cache-read 0 :cache-write 0})]
        (t/is (= 10 (:input usage)))
        (t/is (= (m/calculate-cost model {:input 10 :output 20
                                          :cache-read 0 :cache-write 0})
                 (:cost usage)))))
    (t/testing "unrecognized usage passes through unchanged (no :cost)"
      (t/is (= {:foo 1} (@#'shared/usage-with-cost model {:foo 1}))))))

(t/deftest test-request-headers-attribution
  (t/testing "opencode session headers flow through request-headers"
    (let [model {:provider :opencode :id "qwen3.6-plus"
                 :base-url "https://opencode.ai/zen/v1"}
          provider {:id :opencode}
          merged (@#'shared/request-headers {"Authorization" "Bearer k"} model provider "k" "sess-42")]
      (t/is (= "sess-42" (get merged "x-opencode-session")))
      (t/is (= "kmet" (get merged "x-opencode-client")))))
  (t/testing "no session id → no session headers"
    (let [model {:provider :opencode :id "qwen3.6-plus"}
          provider {:id :opencode}
          merged (@#'shared/request-headers {} model provider "k" nil)]
      (t/is (nil? (get merged "x-opencode-session"))))))

;; ─── Phase 9: anthropic auth-token (Authorization: Bearer) ─────────────────

(t/deftest test-anthropic-auth-headers
  (t/testing "the :anthropic provider with AUTH_TOKEN → Authorization: Bearer (pi anthropic resolve)"
    (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
      (t/is (= {"Authorization" "Bearer tok"}
               (@#'anthropic/anthropic-auth-headers :anthropic "ignored-key")))))
  (t/testing "no AUTH_TOKEN → x-api-key with the resolved key"
    (with-redefs [auth/getenv (fn [_] nil)]
      (t/is (= {"x-api-key" "api-key"}
               (@#'anthropic/anthropic-auth-headers :anthropic "api-key")))))
  (t/testing "other providers never take the anthropic bearer path"
    (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
      (t/is (= {"x-api-key" "k"}
               (@#'anthropic/anthropic-auth-headers :github-copilot "k"))))))

(t/deftest ^:slow test-llm-anthropic-bearer-only
  (m/load-catalogs!)
  (m/clear-extension-providers!)
  (try
    (m/register-provider-config! :anthropic
                                 {:base-url "https://api.anthropic.com" :api :anthropic-messages
                                  :models [{:id "claude-sonnet-4.5"}]})
    (t/testing "non-anthropic providers still reject a missing api key"
      (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
        (let [errors (atom [])]
          @(llm/send-message {:provider :deepseek
                              :on-error (fn [e] (swap! errors conj e))})
          (t/is (pos? (count @errors)))
          (t/is (str/includes? (first @errors) "No API key")))))
    (t/testing "the :anthropic provider with AUTH_TOKEN: proceeds past the key check"
      (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
        (let [errors (atom [])]
          @(llm/send-message {:provider :anthropic
                              :model "claude-sonnet-4.5"
                              :on-error (fn [e] (swap! errors conj e))})
          (t/is (not-any? #(str/includes? (or % "") "No API key") @errors)
                "reaches the request (fails on the network, not on auth)"))))
    (finally
      (m/clear-extension-providers!)
      (m/load-catalogs!))))

(t/deftest test-llm-anthropic-broken-configured-key-blocks-bearer
  ;; A configured-but-unresolvable models.edn key blocks the whole resolution
  ;; (pi resolveConfigValueOrThrow: rawKey present → inherited env never
  ;; reached) — even with AUTH_TOKEN set, the request must NOT proceed with a
  ;; nil x-api-key.
  (m/load-catalogs!)
  (m/clear-extension-providers!)
  (try
    (m/register-provider-config! :anthropic
                                 {:base-url "https://api.anthropic.com" :api :anthropic-messages
                                  :api-key "$KMT_MISSING_ANTHROPIC_KEY"
                                  :models [{:id "claude-sonnet-4.5"}]})
    (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))
                  dynamic-value/getenv (fn [_] nil)]
      (let [errors (atom [])]
        @(llm/send-message {:provider :anthropic
                            :model "claude-sonnet-4.5"
                            :on-error (fn [e] (swap! errors conj e))})
        (t/is (pos? (count @errors)))
        (t/is (str/includes? (first @errors) "No API key")
              "unresolvable configured key wins over AUTH_TOKEN (pi)")))
    (finally
      (m/clear-extension-providers!)
      (m/load-catalogs!))))

(t/deftest ^:slow test-llm-resolves-key-from-auth
  ;; send-message resolves the key itself when the caller passes none (pi
  ;; prepareRequest) — an auth.edn credential lets a bare call proceed
  ;; instead of erroring with an empty Authorization header.
  (m/load-catalogs!)
  (with-redefs [auth/auth-atom (atom {:opencode-go {:key "file-key"}})
                auth/getenv (fn [_] nil)]
    (let [errors (atom [])]
      @(llm/send-message {:provider :opencode-go
                          :model "deepseek-v4-flash"
                          :on-error (fn [e] (swap! errors conj e))})
      (t/is (not-any? #(str/includes? (or % "") "No API key") @errors)
            "proceeds with the auth.edn key (fails on the network, not on auth)"))))

;; ─── OpenAI Responses end-to-end (mock server) ────────────────────────────

(t/deftest ^:slow test-llm-responses-stream-end-to-end
  (m/load-catalogs!)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-body (atom nil)
        request-headers (atom {})
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           ;; capture the request headers + body: read the
                           ;; headers to the blank line, then the
                           ;; Content-Length body (single-line JSON)
                           clen (atom 0)
                           req-headers (atom {})
                           _ (loop []
                               (let [line (.readLine rdr)]
                                 (when (seq (or line ""))
                                   (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                   (when-let [colon (str/index-of line ":")]
                                     (reset! req-headers
                                             (assoc @req-headers
                                                    (str/trim (subs line 0 colon))
                                                    (str/trim (subs line (inc colon))))))
                                   (recur))))
                           _ (reset! request-headers @req-headers)
                           ;; Read the body from the SAME reader: the
                           ;; BufferedReader may already hold body bytes past
                           ;; the header terminator in its buffer — reading
                           ;; the raw stream directly would deadlock on those.
                           body-sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n))
                                       m (.read rdr buf)]
                                   (when (pos? m)
                                     (.append body-sb (String. buf 0 m))
                                     (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str body-sb))
                           out (.getOutputStream s)
                           stream-body (str "event: response.output_item.added\n"
                                            "data: {\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\",\"arguments\":\"\"}}\n\n"
                                            "event: response.function_call_arguments.delta\n"
                                            "data: {\"output_index\":0,\"delta\":\"{\\\"cmd\\\":\\\"ls\\\"}\"}\n\n"
                                            "event: response.output_text.delta\n"
                                            "data: {\"output_index\":0,\"delta\":\"hello\"}\n\n"
                                            "event: response.completed\n"
                                            "data: {\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":100,\"output_tokens\":20,\"total_tokens\":120,\"input_tokens_details\":{\"cached_tokens\":10},\"output_tokens_details\":{\"reasoning_tokens\":5}}}}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        text (atom "")
        tool-calls (atom [])
        done-reason (atom nil)
        usage (atom nil)
        errors (atom [])
        fut (llm/send-message {:provider :openai
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/responses")
                               :model "gpt-5.4"
                               :messages [{:role :system :content [{:type :text :text "S"}]}
                                          {:role :user :content [{:type :text :text "hi"}]}]
                               :session-id "sess-123"
                               :thinking :high
                               :on-text (fn [t] (swap! text str t))
                               :on-tool-call (fn [tc] (swap! tool-calls conj tc))
                               :on-done (fn [r] (reset! done-reason r))
                               :on-usage (fn [u] (reset! usage u))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hello" @text))
      (t/is (= [{:id "call_1|fc_1" :name "bash" :arguments "" :index 0}
                {:arguments "{\"cmd\":\"ls\"}" :index 0}]
               @tool-calls)
            "tool-call start + argument deltas (the loop's accumulator merges them)")
      (t/is (= :tool-use @done-reason)
            "completed with a tool call maps to :tool-use (pi remap)")
      (t/is (= {:input_tokens 100 :output_tokens 20 :total_tokens 120
                :input_tokens_details {:cached_tokens 10}
                :output_tokens_details {:reasoning_tokens 5}
                :cost {:input 2.25E-4 :output 3.0E-4 :cache-read 2.5E-6
                       :cache-write 0.0 :total 5.275E-4}}
               @usage)
            "usage gains the per-message cost (gpt-5.4 rates: 90 input = 2.5/1M)")
      (t/testing "the wire payload is responses-shaped"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= "sess-123" (:prompt_cache_key payload))
                "short retention sends the prompt cache key from the session id")
          (t/is (= "sess-123" (get @request-headers "session_id"))
                "openai affinity headers carry the session id")
          (t/is (= "sess-123" (get @request-headers "x-client-request-id")))
          (t/is (= "gpt-5.4" (:model payload)))
          (t/is (= {:role "developer" :content "S"} (first (:input payload))))
          (t/is (= {:effort "high" :summary "auto"} (:reasoning payload)))
          (t/is (= ["reasoning.encrypted_content"] (:include payload)))
          (t/is (= 128000 (:max_output_tokens payload)))
          (t/is (false? (:store payload)))))
      (finally
        (.close ss)))))

;; ─── Anthropic parallel tool calls (pi parity) ─────────────────────────────

(t/deftest ^:slow test-llm-anthropic-parallel-tool-calls
  ;; Regression: parallel tool_use blocks streamed by Claude must survive the
  ;; tool-call accumulator as SEPARATE calls. Before the :index fix, kmet's
  ;; anthropic wire dropped the block index (and the input_json_delta events
  ;; entirely), so sibling calls collapsed into one and args stayed {} — the
  ;; model saw one-at-a-time execution and adapted to serial tool calls.
  (m/load-catalogs!)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-body (atom nil)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           ;; capture the request body (single-line JSON)
                           clen (atom 0)
                           _ (loop []
                               (let [line (.readLine rdr)]
                                 (when (seq (or line ""))
                                   (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                   (recur))))
                           body-sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n))
                                       m (.read rdr buf)]
                                   (when (pos? m)
                                     (.append body-sb (String. buf 0 m))
                                     (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str body-sb))
                           out (.getOutputStream s)
                           ;; Two parallel tool_use blocks: read (index 0) and
                           ;; bash (index 1), each with input_json_delta streams.
                           stream-body (str "event: content_block_start\n"
                                            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_read\",\"name\":\"read\",\"input\":{}}}\n\n"
                                            "event: content_block_delta\n"
                                            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"\"}}\n\n"
                                            "event: content_block_delta\n"
                                            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"/etc/hosts\\\"\"}}\n\n"
                                            "event: content_block_start\n"
                                            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_bash\",\"name\":\"bash\",\"input\":{}}}\n\n"
                                            "event: content_block_delta\n"
                                            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\\\"ls\\\"}\"}}\n\n"
                                            "event: content_block_delta\n"
                                            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"}\"}}\n\n"
                                            "event: message_delta\n"
                                            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n"
                                            "event: message_stop\n"
                                            "data: {\"type\":\"message_stop\"}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        tool-calls (atom [])
        done-reason (atom nil)
        errors (atom [])
        fut (llm/send-message {:provider :anthropic
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/v1/messages")
                               :model "claude-haiku-4-5"
                               :messages [{:role :system :content [{:type :text :text "S"}]}
                                          {:role :user :content [{:type :text :text "hi"}]}]
                               :tools [(tools/make-tool :name "read" :description "Read a file"
                                                        :parameters {:type "object" :properties {}
                                                                     :required []})]
                               :on-tool-call (fn [tc] (swap! tool-calls conj tc))
                               :on-done (fn [r] (reset! done-reason r))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= :tool-use @done-reason))
      (t/is (= [{:id "toolu_read" :name "read" :arguments {} :index 0}
                {:id nil :arguments "{\"path\":\"" :index 0}
                {:id nil :arguments "/etc/hosts\"" :index 0}
                {:id "toolu_bash" :name "bash" :arguments {} :index 1}
                {:id nil :arguments "{\"command\":\"ls\"}" :index 1}
                {:id nil :arguments "\"}" :index 1}]
               @tool-calls)
            "both parallel tool calls stream through with their own index and merged args deltas")
      (t/testing "the wire payload puts the system prompt in `system`, not a message"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= [{:type "text" :text "S"}] (:system payload))
                "system prompt goes to params.system (pi semantics)")
          (t/is (not-any? #(= "system" (:role %)) (:messages payload))
                "no illegal \"system\" message")
          (t/is (= "claude-haiku-4-5" (:model payload)))
          (t/is (seq (:tools payload)) "tools are included")))
      (finally
        (.close ss)))))

;; ─── OpenAI Responses caching + copilot headers (pi parity) ───────────────

(t/deftest test-llm-responses-cache-params
  (t/testing "short retention (default) sends the prompt cache key from the session id"
    (let [payload (@#'responses/responses-payload (responses-model) :high [] [] "gpt-5.4"
                                                  :short "sess-123")]
      (t/is (= "sess-123" (:prompt_cache_key payload)))
      (t/is (nil? (:prompt_cache_retention payload)))))
  (t/testing "long retention → prompt_cache_retention 24h (absent compat defaults
             to true, pi getCompat)"
    (let [payload (@#'responses/responses-payload (responses-model) :high [] [] "gpt-5.4"
                                                  :long "sess-123")]
      (t/is (= "24h" (:prompt_cache_retention payload))))
    (let [payload (@#'responses/responses-payload
                   (responses-model {:compat {:supports-long-cache-retention false}})
                   :high [] [] "gpt-5.4" :long "sess-123")]
      (t/is (nil? (:prompt_cache_retention payload)) "unsupported models don't get the retention param")))
  (t/testing "none → no key; explicit prompt-cache mode on cache-enabled models"
    (let [payload (@#'responses/responses-payload (responses-model) :high [] [] "gpt-5.4" :none nil)]
      (t/is (nil? (:prompt_cache_key payload)))
      (t/is (nil? (:prompt_cache_options payload)) "gpt-5.4 has no explicit-prompt-cache compat"))
    (let [model (responses-model {:compat {:supports-explicit-prompt-cache-mode true}})
          payload (@#'responses/responses-payload model :high [] [] "gpt-5.4" :none nil)]
      (t/is (= {:mode "explicit"} (:prompt_cache_options payload)))))
  (t/testing "session ids over 64 chars are clamped (pi clampOpenAIPromptCacheKey)"
    (let [long-id (apply str (repeat 80 "x"))
          payload (@#'responses/responses-payload (responses-model) :high [] [] "gpt-5.4" :short long-id)]
      (t/is (= 64 (count (:prompt_cache_key payload))))
      (t/is (str/starts-with? long-id (:prompt_cache_key payload))
            "the clamped key is the first 64 chars of the session id"))))

(t/deftest test-llm-responses-affinity-headers
  (t/testing "openai format → session_id + x-client-request-id"
    (t/is (= {"session_id" "s1" "x-client-request-id" "s1"}
             (@#'responses/responses-affinity-headers (responses-model) "s1"))))
  (t/testing "opencode zen (openai-nosession compat) → x-client-request-id only"
    (t/is (= {"x-client-request-id" "s1"}
             (@#'responses/responses-affinity-headers
              (responses-model {:compat {:session-affinity-format :openai-nosession}}) "s1"))))
  (t/testing "openrouter detection (provider or base-url) → x-session-id"
    (t/is (= {"x-session-id" "s1"}
             (@#'responses/responses-affinity-headers
              (responses-model {:provider :openrouter}) "s1")))
    (t/is (= {"x-session-id" "s1"}
             (@#'responses/responses-affinity-headers
              (responses-model {:base-url "https://openrouter.ai/api/v1"}) "s1"))))
  (t/testing "no session id → no headers"
    (t/is (nil? (@#'responses/responses-affinity-headers (responses-model) nil)))))

(t/deftest test-llm-copilot-dynamic-headers
  (t/testing "X-Initiator from the last message role (pi inferCopilotInitiator)"
    (t/is (= {"X-Initiator" "user" "Openai-Intent" "conversation-edits"}
             (@#'responses/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}])))
    (t/is (= {"X-Initiator" "agent" "Openai-Intent" "conversation-edits"}
             (@#'responses/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}
               {:role :assistant :content [{:type :text :text "ok"}]}])))
    (t/is (= {"X-Initiator" "agent" "Openai-Intent" "conversation-edits"}
             (@#'responses/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}
               {:role :tool :content [{:type :tool-result :tool_use_id "t" :content "out"}]}]))))
  (t/testing "Copilot-Vision-Request when any user/tool-result message has images"
    (t/is (= {"X-Initiator" "user" "Openai-Intent" "conversation-edits"
              "Copilot-Vision-Request" "true"}
             (@#'responses/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "look"}
                                      {:type :image :data "AA" :mime-type "image/png"}]}])))
    (t/is (= {"X-Initiator" "agent" "Openai-Intent" "conversation-edits"
              "Copilot-Vision-Request" "true"}
             (@#'responses/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}
               {:role :tool :content [{:type :tool-result :tool_use_id "t" :content "out"}]
                :images [{:type :image :data "AA" :mime-type "image/png"}]}]))))
  (t/testing "no images → no vision header"
    (t/is (nil? (get (@#'responses/copilot-dynamic-headers
                      [{:role :user :content [{:type :text :text "hi"}]}])
                     "Copilot-Vision-Request")))))

(t/deftest test-llm-responses-tool-result-placeholder
  (let [model (responses-model {:input [:text]})]
    (t/testing "images on a text-only model → pi's (see attached image)"
      (t/is (= "(see attached image)"
               (@#'responses/responses-tool-result-output
                model {:content [{:type :tool-result :tool_use_id "t" :content ""}]
                       :images [{:type :image :data "AA" :mime-type "image/png"}]}))))
    (t/testing "no text and no images → (no tool output)"
      (t/is (= "(no tool output)"
               (@#'responses/responses-tool-result-output
                model {:content [{:type :tool-result :tool_use_id "t" :content ""}]}))))
    (t/testing "plain text passes through"
      (t/is (= "out"
               (@#'responses/responses-tool-result-output
                model {:content [{:type :tool-result :tool_use_id "t" :content "out"}]}))))))

(t/deftest test-llm-responses-request-headers
  ;; the affinity headers are gated on caching (pi cacheSessionId): :none
  ;; sends neither the key nor the affinity headers
  (let [model (responses-model)
        provider (m/map->Provider {:id :openai :name "OpenAI" :api-types #{:openai-responses}
                                   :models [model] :env-vars [] :default-model nil})]
    (t/testing "short (default) with a session id → affinity headers"
      (let [h (#'responses/responses-request-headers model provider "sk" "sess-1" :short [])]
        (t/is (= "sess-1" (get h "session_id")))
        (t/is (= "sess-1" (get h "x-client-request-id")))))
    (t/testing ":none → no affinity headers even with a session id"
      (let [h (#'responses/responses-request-headers model provider "sk" "sess-1" :none [])]
        (t/is (nil? (get h "session_id")))
        (t/is (nil? (get h "x-client-request-id")))))
    (t/testing "no session id → no affinity headers"
      (let [h (#'responses/responses-request-headers model provider "sk" nil :short [])]
        (t/is (nil? (get h "session_id")))))
    (t/testing "copilot requests carry the dynamic headers (incl. over :none)"
      (let [copilot (responses-model {:provider :github-copilot})
            p2 (m/map->Provider {:id :github-copilot :name "Copilot"
                                 :api-types #{:openai-responses} :models [copilot]
                                 :env-vars [] :default-model nil})
            h (#'responses/responses-request-headers
               copilot p2 "sk" "sess-1" :none
               [{:role :user :content [{:type :text :text "hi"}]}])]
        (t/is (= "user" (get h "X-Initiator")))
        (t/is (= "conversation-edits" (get h "Openai-Intent")))
        (t/is (nil? (get h "session_id")) "copilot still skips affinity headers under :none")))))

(t/deftest ^:slow test-llm-copilot-responses-end-to-end
  ;; the copilot responses path: per-request dynamic headers (X-Initiator /
  ;; Openai-Intent / Copilot-Vision-Request) + the static COPILOT headers +
  ;; the session-affinity headers, on the real send-message flow
  (m/load-catalogs!)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-body (atom nil)
        request-headers (atom {})
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           clen (atom 0)
                           req-headers (atom {})
                           _ (loop []
                               (let [line (.readLine rdr)]
                                 (when (seq (or line ""))
                                   (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                   (when-let [colon (str/index-of line ":")]
                                     (reset! req-headers
                                             (assoc @req-headers
                                                    (str/trim (subs line 0 colon))
                                                    (str/trim (subs line (inc colon))))))
                                   (recur))))
                           _ (reset! request-headers @req-headers)
                           ;; Read the body from the SAME reader: the
                           ;; BufferedReader may already hold body bytes past
                           ;; the header terminator in its buffer — reading
                           ;; the raw stream directly would deadlock on those.
                           body-sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n))
                                       m (.read rdr buf)]
                                   (when (pos? m)
                                     (.append body-sb (String. buf 0 m))
                                     (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str body-sb))
                           out (.getOutputStream s)
                           stream-body (str "event: response.output_text.delta\n"
                                            "data: {\"output_index\":0,\"delta\":\"hello\"}\n\n"
                                            "event: response.completed\n"
                                            "data: {\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        text (atom "")
        done-reason (atom nil)
        errors (atom [])
        fut (llm/send-message {:provider :github-copilot
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/responses")
                               :model "gpt-5.4"
                               :messages [{:role :system :content [{:type :text :text "S"}]}
                                          {:role :user :content [{:type :text :text "hi"}
                                                                 {:type :image :data "AA" :mime-type "image/png"}]}]
                               :session-id "sess-copilot"
                               :on-text (fn [t] (swap! text str t))
                               :on-done (fn [r] (reset! done-reason r))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hello" @text))
      (t/is (= :stop @done-reason))
      (t/testing "copilot dynamic + affinity headers on the wire"
        (t/is (= "user" (get @request-headers "X-Initiator"))
              "last message is a user message with an image")
        (t/is (= "conversation-edits" (get @request-headers "Openai-Intent")))
        (t/is (= "true" (get @request-headers "Copilot-Vision-Request"))
              "user message has image content")
        (t/is (= "sess-copilot" (get @request-headers "session_id")))
        (t/is (= "sess-copilot" (get @request-headers "x-client-request-id")))
        (t/is (= "GitHubCopilotChat/0.35.0" (get @request-headers "User-Agent"))
              "static COPILOT_STATIC_HEADERS merge in"))
      (t/testing "the payload is responses-shaped with the cache key"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= "sess-copilot" (:prompt_cache_key payload)))
          (t/is (= {:role "developer" :content "S"} (first (:input payload))))))
      (finally
        (.close ss)))))

;; ─── Codex + Azure responses end-to-end (mock server) ─────────────────────

(t/deftest ^:slow test-llm-codex-responses-end-to-end
  (m/load-catalogs!)
  (let [token (test-jwt "acc-1")
        ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-line (atom nil)
        request-body (atom nil)
        request-headers (atom {})
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           line1 (.readLine rdr)
                           _ (reset! request-line line1)
                           clen (atom 0)
                           req-headers (atom {})
                           _ (loop []
                               (let [line (.readLine rdr)]
                                 (when (seq (or line ""))
                                   (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                   (when-let [colon (str/index-of line ":")]
                                     (reset! req-headers
                                             (assoc @req-headers
                                                    (str/trim (subs line 0 colon))
                                                    (str/trim (subs line (inc colon))))))
                                   (recur))))
                           _ (reset! request-headers @req-headers)
                           body-sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n))
                                       m (.read rdr buf)]
                                   (when (pos? m)
                                     (.append body-sb (String. buf 0 m))
                                     (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str body-sb))
                           out (.getOutputStream s)
                           stream-body (str "event: response.output_text.delta\n"
                                            "data: {\"output_index\":0,\"delta\":\"hello\"}\n\n"
                                            "event: response.done\n"
                                            "data: {\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        text (atom "")
        done-reason (atom nil)
        errors (atom [])
        fut (llm/send-message {:provider :openai-codex
                               :api-key token
                               :base-url (str "http://localhost:" port)
                               :model "gpt-5.4"
                               :messages [{:role :system :content [{:type :text :text "S"}]}
                                          {:role :user :content [{:type :text :text "hi"}]}]
                               :session-id "sess-codex"
                               :thinking :high
                               :on-text (fn [t] (swap! text str t))
                               :on-done (fn [r] (reset! done-reason r))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hello" @text))
      (t/is (= :stop @done-reason) "codex response.done → :stop")
      (t/testing "codex headers on the wire"
        (t/is (= (str "Bearer " token) (get @request-headers "Authorization")))
        (t/is (= "acc-1" (get @request-headers "chatgpt-account-id")))
        (t/is (= "kmet" (get @request-headers "originator")))
        (t/is (= "responses=experimental" (get @request-headers "OpenAI-Beta")))
        (t/is (= "sess-codex" (get @request-headers "session-id")))
        (t/is (= "sess-codex" (get @request-headers "x-client-request-id"))))
      (t/testing "the codex envelope is on the wire"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= "S" (:instructions payload)))
          (t/is (= "sess-codex" (:prompt_cache_key payload))
                "default :short retention sends the cache key")
          (t/is (= {:effort "high" :summary "auto"} (:reasoning payload)))
          (t/is (= "low" (get-in payload [:text :verbosity])))))
      (finally
        (.close ss)))))

(t/deftest ^:slow test-llm-azure-responses-end-to-end
  (m/load-catalogs!)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-line (atom nil)
        request-body (atom nil)
        request-headers (atom {})
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           line1 (.readLine rdr)
                           _ (reset! request-line line1)
                           clen (atom 0)
                           req-headers (atom {})
                           _ (loop []
                               (let [line (.readLine rdr)]
                                 (when (seq (or line ""))
                                   (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                   (when-let [colon (str/index-of line ":")]
                                     (reset! req-headers
                                             (assoc @req-headers
                                                    (str/trim (subs line 0 colon))
                                                    (str/trim (subs line (inc colon))))))
                                   (recur))))
                           _ (reset! request-headers @req-headers)
                           body-sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n))
                                       m (.read rdr buf)]
                                   (when (pos? m)
                                     (.append body-sb (String. buf 0 m))
                                     (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str body-sb))
                           out (.getOutputStream s)
                           stream-body (str "event: response.output_text.delta\n"
                                            "data: {\"output_index\":0,\"delta\":\"hi\"}\n\n"
                                            "event: response.completed\n"
                                            "data: {\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        text (atom "")
        done-reason (atom nil)
        errors (atom [])
        ;; the azure config is resolved inside the request future, so the
        ;; env redef must stay active until the future completes
        _ (with-redefs [shared/getenv (fn [k]
                                        (case k
                                          "AZURE_OPENAI_BASE_URL" (str "http://localhost:" port)
                                          "AZURE_OPENAI_DEPLOYMENT_NAME_MAP" "gpt-5.4=my-deploy"
                                          nil))]
            (let [f (llm/send-message {:provider :azure-openai-responses
                                       :api-key "sk-azure"
                                       :model "gpt-5.4"
                                       :messages [{:role :system :content [{:type :text :text "S"}]}
                                                  {:role :user :content [{:type :text :text "hi"}]}]
                                       :session-id "sess-azure"
                                       :thinking :high
                                       :on-text (fn [t] (swap! text str t))
                                       :on-done (fn [r] (reset! done-reason r))
                                       :on-error (fn [e] (swap! errors conj e))})]
              @f))]
    (try
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hi" @text))
      (t/is (= :stop @done-reason))
      (t/testing "the deployment path + api version are on the wire"
        (t/is (str/includes? @request-line "/deployments/my-deploy/responses?api-version=v1")
              (str "request line: " @request-line)))
      (t/testing "no session-affinity headers for azure (pi)"
        (t/is (= (str "Bearer " "sk-azure") (get @request-headers "Authorization")))
        (t/is (nil? (get @request-headers "session_id")))
        (t/is (nil? (get @request-headers "x-client-request-id"))))
      (t/testing "the deployment name is the model field, the cache key is sent"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= "my-deploy" (:model payload)))
          (t/is (= "sess-azure" (:prompt_cache_key payload)))))
      (finally
        (.close ss)))))

;; ─── Kimi adaptive thinking e2e (anthropic-messages, forceAdaptiveThinking) ─

(t/deftest ^:slow test-llm-kimi-adaptive-thinking-end-to-end
  (m/load-catalogs!)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-body (atom nil)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           _ (.readLine rdr)
                           clen (atom 0)
                           _ (loop []
                               (let [l (.readLine rdr)]
                                 (when (seq (or l ""))
                                   (when (str/starts-with? (str/lower-case (or l "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs l 15)))))
                                   (recur))))
                           sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n)) m (.read rdr buf)]
                                   (when (pos? m) (.append sb (String. buf 0 m)) (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str sb))
                           out (.getOutputStream s)
                           stream-body (str "event: content_block_delta\n"
                                            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n"
                                            "event: message_stop\n"
                                            "data: {\"type\":\"message_stop\"}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        errors (atom [])
        text (atom "")
        fut (llm/send-message {:provider :kimi-coding
                               :model "kimi-for-coding"
                               :api-key "k"
                               :base-url (str "http://localhost:" port)
                               :thinking :medium
                               :messages [{:role :user :content [{:type :text :text "hi"}]}]
                               :on-text (fn [t] (swap! text str t))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hi" @text))
      (t/testing "the adaptive thinking payload is on the wire (pi forceAdaptiveThinking)"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= {:type "adaptive" :display "summarized"} (:thinking payload)))
          (t/is (= {:effort "medium"} (:output_config payload)))
          (t/is (= 32768 (:max_tokens payload)))))
      (finally
        (.close ss)))))

(t/deftest test-llm-cloudflare-missing-env-reports-error
  (m/load-catalogs!)
  (let [errors (atom [])]
    ;; the interpolation runs inside the request future, so the env redef
    ;; must stay active until the future completes
    (with-redefs [shared/getenv (fn [_] nil)]
      @(llm/send-message {:provider :cloudflare-workers-ai
                          :model "@cf/moonshotai/kimi-k2.6"
                          :api-key "k"
                          :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= ["Cloudflare requires the env vars: CLOUDFLARE_ACCOUNT_ID"] @errors)
          "a missing cloudflare account id reports via on-error (never hangs)")))

;; ─── Mistral conversations ────────────────────────────────────────────────

(defn- mistral-model
  "Mistral model map for the payload/messages tests."
  [& {:keys [id reasoning tlm input max-tokens]
      :or {id "mistral-large" reasoning true input [:text] max-tokens 32000}}]
  {:id id :name "Mistral" :provider :mistral :api :mistral-conversations
   :base-url "https://api.mistral.ai" :reasoning reasoning
   :thinking-level-map tlm :input input :max-tokens max-tokens})

(t/deftest test-mistral-url
  (t/is (= "https://api.mistral.ai/v1/chat/completions"
           (@#'shared/endpoint-url :mistral-conversations "https://api.mistral.ai" "x"))))

(t/deftest test-mistral-tool-call-id-normalizer
  ;; 9-char ids pass through; longer/messy ids get a 9-char shortHash
  (let [normalize (@#'mistral/make-mistral-tool-call-id-normalizer)]
    (t/is (= "abc123xyz" (normalize "abc123xyz")))
    (t/is (= 9 (count (normalize "call_very-long-tool-call-id-12345"))))
    (t/is (re-matches #"[a-zA-Z0-9]{9}" (normalize "call_very-long-tool-call-id-12345")))
    (t/is (= (normalize "call-1") (normalize "call-1")) "deterministic per request")
    (t/is (not= (normalize "call-1") (normalize "call-2")) "distinct ids stay distinct")))

(t/deftest test-mistral-messages
  (let [normalize (fn [id] (if (= id "tool-1") "abc123xyz" id))
        msgs [{:role :system :content [{:type :text :text "sys"}]}
              {:role :user :content [{:type :text :text "hi"}]}
              {:role :assistant :content [{:type :text :text "answer"}]
               :thinking "reasoning here"
               :tool-calls [{:id "tool-1" :name "read" :arguments {:path "/x"}}]}
              {:role :tool :content [{:content "done" :tool_use_id "tool-1"}]
               :tool-name "read"}
              {:role :bash :content [{:content ""}] :command "ls" :output "a\nb" :exit-code 0}]]
    (t/is (= [{:role "system" :content "sys"}
              {:role "user" :content [{:type "text" :text "hi"}]}
              {:role "assistant"
               :content [{:type "text" :text "answer"}
                         {:type "thinking" :thinking [{:type "text" :text "reasoning here"}]}]
               :tool_calls [{:id "abc123xyz" :type "function"
                             :function {:name "read" :arguments "{\"path\":\"/x\"}"} :index 0}]}
              {:role "tool" :tool_call_id "abc123xyz" :name "read"
               :content [{:type "text" :text "done"}]}
              {:role "user" :content "Ran `ls`\n```\na\nb\n```"}]
             (@#'mistral/mistral-messages msgs (mistral-model) normalize)))))

(t/deftest test-mistral-messages-images
  (let [msgs [{:role :user :content [{:type :text :text "see"}
                                     {:type :image :mime-type "image/png" :data "AAAA"}]}
              {:role :tool :content [{:content "" :tool_use_id "t1"}]
               :tool-name "read" :images [{:mime-type "image/png" :data "BBBB"}]}]
        normalize identity]
    (t/testing "image-capable model keeps image_url parts"
      (t/is (= [{:role "user"
                 :content [{:type "text" :text "see"}
                           {:type "image_url" :image_url "data:image/png;base64,AAAA"}]}
                {:role "tool" :tool_call_id "t1" :name "read"
                 :content [{:type "text" :text "(see attached image)"}
                           {:type "image_url" :image_url "data:image/png;base64,BBBB"}]}]
               (@#'mistral/mistral-messages msgs (mistral-model :input [:text :image]) normalize))))
    (t/testing "text-only model drops the image parts (keeps text)"
      (t/is (= [{:role "user" :content [{:type "text" :text "see"}]}
                {:role "tool" :tool_call_id "t1" :name "read"
                 :content [{:type "text" :text "(image omitted: model does not support images)"}]}]
               (@#'mistral/mistral-messages msgs (mistral-model :input [:text]) normalize))))
    (t/testing "image-only user content degrades to pi's placeholder"
      (t/is (= [{:role "user"
                 :content [{:type "text" :text "(image omitted: model does not support images)"}]}]
               (@#'mistral/mistral-messages
                [{:role "user" :content [{:type :image :mime-type "image/png" :data "AAAA"}]}]
                (mistral-model :input [:text]) normalize))))))

(t/deftest test-mistral-thinking
  (t/is (= {:prompt_mode "reasoning"}
           (@#'mistral/mistral-thinking (mistral-model) :high))
        "default models use prompt_mode")
  (t/is (= {:reasoning_effort "high"}
           (@#'mistral/mistral-thinking (mistral-model :id "mistral-medium-3.5") :high))
        "effort models use reasoning_effort (default high)")
  (t/is (= {:reasoning_effort "medium"}
           (@#'mistral/mistral-thinking (mistral-model :id "mistral-medium-3.5"
                                                       :tlm {:medium "medium"}) :medium))
        "tlm value wins over the default")
  (t/is (= {} (@#'mistral/mistral-thinking (mistral-model) nil)) "off → no thinking params")
  (t/is (= {} (@#'mistral/mistral-thinking (mistral-model :reasoning false) :high))
        "non-reasoning model → no thinking params"))

(t/deftest test-mistral-payload
  (let [tools [(tools/make-tool :name "read" :label "Read" :description "Read a file"
                                :params {:path {:type :string :description "path"}})]]
    (t/is (= {:model "mistral-large" :stream true
              :messages [{:role "user" :content [{:type "text" :text "hi"}]}]
              :tools [{:type "function"
                       :function {:name "read" :description "Read a file"
                                  :parameters (:parameters (first tools)) :strict false}}]
              :prompt_mode "reasoning"
              :max_tokens 32000
              :prompt_cache_key "sess-1"}
             (@#'mistral/mistral-payload (mistral-model) :high
                                         [{:role "user" :content [{:type "text" :text "hi"}]}]
                                         tools "mistral-large" "sess-1" :short)))
    (t/testing "cache-retention :none drops the cache key"
      (t/is (nil? (:prompt_cache_key (@#'mistral/mistral-payload (mistral-model) nil
                                                                 [{:role "user" :content []}]
                                                                 nil "m" "s" :none)))))))

;; ─── Google Vertex ─────────────────────────────────────────────────────────

(t/deftest test-vertex-endpoint-url
  (with-redefs [shared/getenv (fn [k]
                                (case k
                                  "GOOGLE_CLOUD_PROJECT" "my-project"
                                  "GCLOUD_PROJECT" nil
                                  "GOOGLE_CLOUD_LOCATION" "us-central1"
                                  nil))]
    (t/is (= "https://us-central1-aiplatform.googleapis.com/v1/projects/my-project/locations/us-central1/publishers/google/models/gemini-3-pro:streamGenerateContent?alt=sse"
             (@#'vertex/vertex-endpoint-url "https://{location}-aiplatform.googleapis.com" "gemini-3-pro")))
    (t/testing "custom base-url used verbatim"
      (t/is (= "https://my-proxy.example/v1/projects/my-project/locations/us-central1/publishers/google/models/gemini-3-pro:streamGenerateContent?alt=sse"
               (@#'vertex/vertex-endpoint-url "https://my-proxy.example" "gemini-3-pro")))))
  (t/testing "missing project → config error"
    (with-redefs [shared/getenv (fn [k] (when (= k "GOOGLE_CLOUD_LOCATION") "us-central1"))]
      (t/is (thrown? Exception (@#'vertex/vertex-endpoint-url "" "m")))))
  (t/testing "GCLOUD_PROJECT fallback"
    (with-redefs [shared/getenv (fn [k]
                                  (case k
                                    "GCLOUD_PROJECT" "alt-project"
                                    "GOOGLE_CLOUD_LOCATION" "europe-west1"
                                    nil))]
      (t/is (str/includes? (@#'vertex/vertex-endpoint-url "" "m") "alt-project")))))

;; ─── AWS Bedrock ConverseStream ───────────────────────────────────────────

(defn- bedrock-model
  [& {:keys [id name reasoning max-tokens compat]
      :or {id "anthropic.claude-sonnet-4-5-20250929-v1:0" name "Claude Sonnet 4.5"
           reasoning true max-tokens 64000 compat nil}}]
  {:id id :name name :provider :amazon-bedrock :api :bedrock-converse-stream
   :base-url "https://bedrock-runtime.us-east-1.amazonaws.com"
   :reasoning reasoning :max-tokens max-tokens :compat compat})

(t/deftest test-bedrock-model-classification
  (t/is (true? (@#'bedrock/bedrock-is-claude? (bedrock-model))))
  (t/is (true? (@#'bedrock/bedrock-is-claude? (bedrock-model :id "arn:aws:bedrock:us-east-1:123:inference-profile/xyz"
                                                             :name "Claude Opus 4.8"))))
  (t/is (false? (@#'bedrock/bedrock-is-claude? (bedrock-model :id "amazon.nova-lite-v1:0"
                                                              :name "Nova Lite"))))
  (t/is (true? (@#'bedrock/bedrock-supports-adaptive-thinking? (bedrock-model :id "anthropic.claude-opus-4-6-v1:0"))))
  (t/is (false? (@#'bedrock/bedrock-supports-adaptive-thinking? (bedrock-model))))
  (t/is (true? (@#'bedrock/bedrock-supports-prompt-caching? (bedrock-model))))
  (t/is (false? (@#'bedrock/bedrock-supports-prompt-caching?
                 (bedrock-model :id "amazon.nova-lite-v1:0" :name "Nova Lite")))
        "non-claude models have automatic caching — no explicit cache points"))

(t/deftest test-bedrock-messages
  (let [msgs [{:role :system :content [{:type :text :text "sys"}]}
              {:role :user :content [{:type :text :text "hi"}]}
              {:role :assistant :content [{:type :text :text "answer"}]
               :thinking "let me think"
               :tool-calls [{:id "t1" :name "read" :arguments {:path "/x"}}]}
              {:role :tool :content [{:content "done" :tool_use_id "t1"}]
               :tool-name "read"}
              {:role :user :content [{:type :text :text "more"}]}]]
    (t/testing "claude model: system block with cache point, thinking as plain
                text (kmet stores no reasoning signatures — pi's no-signature
                fallback; Bedrock rejects a signature-less reasoningContent),
                cache point on last user"
      (let [[result system] (@#'bedrock/bedrock-messages msgs (bedrock-model) :short)]
        (t/is (= [{:text "sys"} {:cachePoint {:type "default"}}] system))
        (t/is (= [{:role "user" :content [{:text "hi"}]}
                  {:role "assistant"
                   :content [{:text "answer"}
                             {:toolUse {:toolUseId "t1" :name "read" :input {:path "/x"}}}
                             {:text "let me think"}]}
                  {:role "user"
                   :content [{:toolResult {:toolUseId "t1"
                                           :content [{:text "done"}]
                                           :status "success"}}]}
                  {:role "user"
                   :content [{:text "more"}
                             {:cachePoint {:type "default"}}]}]
                 result))))
    (t/testing "non-claude model: thinking as reasoningContent (no signature),
                no cache points"
      (let [[result system] (@#'bedrock/bedrock-messages
                             msgs (bedrock-model :id "amazon.nova-lite-v1:0" :name "Nova") :short)]
        (t/is (= [{:text "sys"}] system) "no cache point on the system block")
        (t/is (= [{:role "user" :content [{:text "hi"}]}
                  {:role "assistant"
                   :content [{:text "answer"}
                             {:toolUse {:toolUseId "t1" :name "read" :input {:path "/x"}}}
                             {:reasoningContent {:reasoningText {:text "let me think"}}}]}
                  {:role "user"
                   :content [{:toolResult {:toolUseId "t1"
                                           :content [{:text "done"}]
                                           :status "success"}}]}
                  {:role "user" :content [{:text "more"}]}]
                 result))))
    (t/testing "no system prompt → nil system blocks"
      (let [[result system] (@#'bedrock/bedrock-messages
                             (remove #(= :system (:role %)) msgs) (bedrock-model) :short)]
        (t/is (nil? system))
        (t/is (= 4 (count result)))))))

(t/deftest test-llm-bedrock-tool-id-normalization
  (let [id "call_qwen|opaque/tool-id"
        [messages _] (@#'bedrock/bedrock-messages
                      [{:role :assistant :content []
                        :tool-calls [{:id id :name "bash" :arguments {}}]}
                       {:role :tool
                        :content [{:content "done" :tool_use_id id}]}]
                      (bedrock-model) :none)]
    (t/is (= "call_qwen_opaque_tool-id"
             (get-in messages [0 :content 0 :toolUse :toolUseId])))
    (t/is (= "call_qwen_opaque_tool-id"
             (get-in messages [1 :content 0 :toolResult :toolUseId])))))

(t/deftest test-bedrock-messages-images
  (let [msgs [{:role :user :content [{:type :image :mime-type "image/png" :data "AAAA"}]}
              {:role :tool :content [{:content "" :tool_use_id "t1"}]
               :tool-name "read" :images [{:mime-type "image/jpeg" :data "BBBB"}]}]]
    (t/is (= [{:role "user"
               :content [{:image {:format "png" :source {:bytes "AAAA"}}}]}
              {:role "user"
               :content [{:toolResult {:toolUseId "t1"
                                       :content [{:image {:format "jpeg" :source {:bytes "BBBB"}}}]
                                       :status "success"}}]}]
             (first (@#'bedrock/bedrock-messages msgs (bedrock-model) :none))))
    (t/testing "unknown image type throws"
      (t/is (thrown? Exception
                     (@#'bedrock/bedrock-messages
                      [{:role :user :content [{:type :image :mime-type "image/tiff" :data "x"}]}]
                      (bedrock-model) :none))))))

(t/deftest test-bedrock-endpoint-url
  (with-redefs [shared/getenv (fn [_] nil)]
    (t/is (= {:url "https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-sonnet-4-5-20250929-v1:0/converse-stream"
              :region "us-east-1"}
             (@#'bedrock/bedrock-endpoint-url "https://bedrock-runtime.us-east-1.amazonaws.com" "anthropic.claude-sonnet-4-5-20250929-v1:0")))
    (t/is (= {:url "https://bedrock-runtime.eu-central-1.amazonaws.com/model/eu.claude-opus/converse-stream"
              :region "eu-central-1"}
             (@#'bedrock/bedrock-endpoint-url "https://bedrock-runtime.eu-central-1.amazonaws.com" "eu.claude-opus"))))
  (t/testing "configured region overrides the endpoint region"
    (with-redefs [shared/getenv (fn [k] (when (= k "AWS_REGION") "eu-west-1"))]
      (t/is (= {:url "https://bedrock-runtime.eu-west-1.amazonaws.com/model/m/converse-stream"
                :region "eu-west-1"}
               (@#'bedrock/bedrock-endpoint-url "https://bedrock-runtime.us-east-1.amazonaws.com" "m")))))
  (t/testing "custom endpoints always win"
    (with-redefs [shared/getenv (fn [_] nil)]
      (t/is (= {:url "https://my-vpc.example/model/m/converse-stream" :region "us-east-1"}
               (@#'bedrock/bedrock-endpoint-url "https://my-vpc.example" "m"))))))

(t/deftest test-bedrock-additional-fields
  (t/is (= {:thinking {:type "adaptive" :display "summarized"}
            :output_config {:effort "high"}}
           (@#'bedrock/bedrock-additional-fields (bedrock-model :id "anthropic.claude-opus-4-6-v1:0") :high)))
  (t/is (= {:thinking {:type "enabled" :budget_tokens 8192 :display "summarized"}
            :anthropic_beta ["interleaved-thinking-2025-05-14"]}
           (@#'bedrock/bedrock-additional-fields (bedrock-model) :medium)))
  (t/is (nil? (@#'bedrock/bedrock-additional-fields (bedrock-model) nil)) "off → no thinking")
  (t/is (nil? (@#'bedrock/bedrock-additional-fields (bedrock-model :id "amazon.nova-lite-v1:0" :name "Nova") :high))
        "non-claude model → no thinking"))

(t/deftest test-bedrock-tool-config
  (let [tools [(tools/make-tool :name "read" :label "Read" :description "Read a file"
                                :params {:path {:type :string :description "path"}})]
        config (@#'bedrock/bedrock-tool-config tools false)]
    (t/is (= {:tools [{:toolSpec {:name "read" :description "Read a file"
                                  :inputSchema {:json (:parameters (first tools))}}}]
              :toolChoice {:auto {}}}
             config))
    (t/is (nil? (@#'bedrock/bedrock-tool-config nil false)))
    (t/testing "strict-resolving tools get the strictified schema and :strict true"
      (let [strict-tool (assoc (first tools)
                               :constrained-sampling {:type :json-schema :strict :prefer})
            config (@#'bedrock/bedrock-tool-config [strict-tool] true)]
        (t/is (= true (get-in config [:tools 0 :toolSpec :strict])))
        (t/is (= ["path"] (get-in config [:tools 0 :toolSpec :inputSchema :json "required"])))))))

;; bedrock e2e frame builder (the sse test's private helpers are not
;; importable — kept here, mirroring the AWS event-stream framing)
(defn- bedrock-e2e-u32 [n]
  [(bit-and (bit-shift-right n 24) 0xFF) (bit-and (bit-shift-right n 16) 0xFF)
   (bit-and (bit-shift-right n 8) 0xFF) (bit-and n 0xFF)])
(defn- bedrock-e2e-u16 [n] [(bit-and (bit-shift-right n 8) 0xFF) (bit-and n 0xFF)])
(defn- bedrock-e2e-crc [bytes]
  (hash/crc32 bytes))
(defn- bedrock-e2e-ba [ints] (byte-array (map #(bit-and (long %) 0xFF) ints)))
(defn- bedrock-e2e-header [name value]
  (let [nb (map int (.getBytes name "UTF-8"))
        vb (map int (.getBytes value "UTF-8"))]
    (concat [(count nb)] nb [7] (bedrock-e2e-u16 (count vb)) vb)))
(defn- bedrock-e2e-frame [event-type payload]
  (let [payload-bytes (map int (.getBytes payload "UTF-8"))
        hdrs (concat (bedrock-e2e-header ":message-type" "event")
                     (bedrock-e2e-header ":event-type" event-type))
        total (+ 12 (count hdrs) (count payload-bytes) 4)
        prelude (bedrock-e2e-ba (concat (bedrock-e2e-u32 total) (bedrock-e2e-u32 (count hdrs))
                                        (bedrock-e2e-u32 0)))
        prelude-crc (bedrock-e2e-crc (java.util.Arrays/copyOfRange prelude 0 8))
        prelude-full (bedrock-e2e-ba (concat (bedrock-e2e-u32 total) (bedrock-e2e-u32 (count hdrs))
                                             (bedrock-e2e-u32 prelude-crc)))
        msg-body (mapv #(bit-and (long %) 0xFF)
                       (concat (mapv int prelude-full) hdrs payload-bytes))
        msg-crc (bedrock-e2e-crc (bedrock-e2e-ba msg-body))]
    (bedrock-e2e-ba (concat msg-body (bedrock-e2e-u32 msg-crc)))))
(defn- bedrock-e2e-frames [& frames]
  (bedrock-e2e-ba (mapcat #(mapv int %) frames)))

(t/deftest ^:slow test-llm-bedrock-stream-end-to-end
  ;; Full send-message → SigV4-signed ConverseStream request → binary
  ;; event-stream response → events/usage path, over a local socket (the
  ;; AWS example keys make the signature deterministic).
  (m/load-catalogs!)
  (with-redefs [aws-sigv4/getenv (fn [k] (case k "AWS_ACCESS_KEY_ID" "AKIDEXAMPLE"
                                               "AWS_SECRET_ACCESS_KEY" "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
                                               nil))]
    (let [ss (java.net.ServerSocket. 0)
          port (.getLocalPort ss)
          request-body (atom nil)
          request-headers (atom {})
          _ (doto (Thread.
                   (fn []
                     (try
                       (let [s (.accept ss)
                             rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                             clen (atom 0)
                             req-headers (atom {})
                             _ (loop []
                                 (let [line (.readLine rdr)]
                                   (when (seq (or line ""))
                                     (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                       (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                     (when-let [colon (str/index-of line ":")]
                                       (reset! req-headers
                                               (assoc @req-headers
                                                      (str/trim (subs line 0 colon))
                                                      (str/trim (subs line (inc colon))))))
                                     (recur))))
                             _ (reset! request-headers @req-headers)
                             body-sb (StringBuilder.)
                             _ (loop [n 0]
                                 (if (< n @clen)
                                   (let [buf (char-array (- @clen n))
                                         m (.read rdr buf)]
                                     (when (pos? m)
                                       (.append body-sb (String. buf 0 m))
                                       (recur (+ n m))))
                                   nil))
                             _ (reset! request-body (str body-sb))
                             out (.getOutputStream s)
                             frames (bedrock-e2e-frames
                                     (bedrock-e2e-frame "contentBlockDelta"
                                                        "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"hello\"}}")
                                     (bedrock-e2e-frame "messageStop" "{\"stopReason\":\"end_turn\"}")
                                     ;; real ConverseStream sends the usage metadata frame
                                     ;; AFTER messageStop (the trailing-usage ordering the
                                     ;; deferred-done fix in responses-events-handler exists
                                     ;; for)
                                     (bedrock-e2e-frame "metadata"
                                                        "{\"usage\":{\"inputTokens\":10,\"outputTokens\":5,\"totalTokens\":15,\"cacheReadInputTokens\":2,\"cacheWriteInputTokens\":1}}"))]
                         (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                     "Content-Type: application/vnd.amazon.eventstream\r\n"
                                                     "Content-Length: " (count frames) "\r\n\r\n")))
                         (.write out frames)
                         (.flush out)
                         (.close s))
                       (catch Exception _ nil))))
              (.setDaemon true)
              (.start))
          text (atom "")
          done-reason (atom nil)
          usage (atom nil)
          errors (atom [])
          fut (llm/send-message {:provider :amazon-bedrock
                                 :model "anthropic.claude-sonnet-4-5-20250929-v1:0"
                                 :base-url (str "http://localhost:" port)
                                 :messages [{:role :system :content [{:type :text :text "SYSTEM"}]}
                                            {:role :user :content [{:type :text :text "hi"}]}]
                                 :session-id "sess-1"
                                 :thinking :medium
                                 :on-text (fn [t] (swap! text str t))
                                 :on-done (fn [r] (reset! done-reason r))
                                 :on-usage (fn [u] (reset! usage u))
                                 :on-error (fn [e] (swap! errors conj e))})]
      (try
        @fut
        (t/is (= [] @errors) (str "no stream errors: " @errors))
        (t/is (= "hello" @text))
        (t/is (= :stop @done-reason))
        (t/is (= {:input_tokens 10 :output_tokens 5 :total_tokens 15
                  :cache_read_input_tokens 2 :cache_write_input_tokens 1
                  :cost {:input 2.1E-5 :output 7.5E-5 :cache-read 6.0E-7
                         :cache-write 3.75E-6 :total 1.0034999999999999E-4}}
                 @usage)
              "bedrock usage gains the per-message cost (sonnet 4.5 rates)")
        (t/testing "the wire payload is ConverseStream-shaped with the system prompt"
          (let [payload (json/parse-string @request-body true)]
            (t/is (= "anthropic.claude-sonnet-4-5-20250929-v1:0" (:modelId payload)))
            (t/is (= [{:text "SYSTEM"} {:cachePoint {:type "default"}}] (:system payload))
                  "system prompt + cache point ride in :system")
            (t/is (= 64000 (get-in payload [:inferenceConfig :maxTokens])))
            (t/is (= {:type "enabled" :budget_tokens 8192 :display "summarized"}
                     (get-in payload [:additionalModelRequestFields :thinking]))
                  "budget-based thinking for a non-adaptive claude")
            (t/is (= ["interleaved-thinking-2025-05-14"]
                     (:anthropic_beta (:additionalModelRequestFields payload))))))
        (t/testing "the request is SigV4-signed with content-type in the signature"
          (t/is (str/starts-with? (get @request-headers "Authorization")
                                  "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"))
          (t/is (= "application/json" (get @request-headers "Content-Type")))
          (t/is (str/includes? (get @request-headers "Authorization")
                               "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date")))
        (finally
          (.close ss))))))

(t/deftest ^:slow test-llm-mistral-stream-end-to-end
  ;; Full send-message → Mistral chat-completions request (normalized
  ;; tool-call ids, x-affinity, prompt_cache_key) → SSE response → events.
  (m/load-catalogs!)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-body (atom nil)
        request-headers (atom {})
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           clen (atom 0)
                           req-headers (atom {})
                           _ (loop []
                               (let [l (.readLine rdr)]
                                 (when (seq (or l ""))
                                   (when (str/starts-with? (str/lower-case (or l "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs l 15)))))
                                   (when-let [colon (str/index-of l ":")]
                                     (reset! req-headers (assoc @req-headers
                                                                (str/trim (subs l 0 colon))
                                                                (str/trim (subs l (inc colon))))))
                                   (recur))))
                           _ (reset! request-headers @req-headers)
                           sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n)) m (.read rdr buf)]
                                   (when (pos? m) (.append sb (String. buf 0 m)) (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str sb))
                           out (.getOutputStream s)
                           stream-body (str "data: {\"data\":{\"choices\":[{\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"},{\"type\":\"thinking\",\"thinking\":[{\"type\":\"text\",\"text\":\"hmm\"}]}]}}]}}\n\n"
                                            "data: {\"data\":{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15},\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}}\n\n"
                                            "data: [DONE]\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        errors (atom [])
        text (atom "")
        thinking (atom "")
        done-reason (atom nil)
        usage (atom nil)
        fut (llm/send-message {:provider :mistral
                               :model "mistral-medium-3.5"
                               :api-key "k"
                               :base-url (str "http://localhost:" port)
                               :session-id "sess-9"
                               :thinking :medium
                               :messages [{:role :assistant
                                           :content [{:type :text :text "prev"}]
                                           :tool-calls [{:id "call-abc-123" :name "read"
                                                         :arguments {:path "/x"}}]}
                                          {:role :tool :content [{:content "done"
                                                                  :tool_use_id "call-abc-123"}]
                                           :tool-name "read"}
                                          {:role :user :content [{:type :text :text "hi"}]}]
                               :on-text (fn [t] (swap! text str t))
                               :on-thinking (fn [t] (swap! thinking str t))
                               :on-done (fn [r] (reset! done-reason r))
                               :on-usage (fn [u] (reset! usage u))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hi" @text))
      (t/is (= "hmm" @thinking))
      (t/is (= :stop @done-reason))
      (t/is (= 15 (:total_tokens @usage)) "usage rides through")
      (t/testing "the wire request is Mistral-shaped"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= "mistral-medium-3.5" (:model payload)))
          (t/is (= "sess-9" (:prompt_cache_key payload)))
          (t/is (= "sess-9" (get @request-headers "x-affinity")))
          (t/is (= "high" (:reasoning_effort payload))
                "mistral-medium-3.5 uses reasoning_effort (tlm ?? high)")
          (t/is (= 262144 (:max_tokens payload)))
          ;; the session tool-call id is normalized to 9 alphanumeric chars
          ;; on the wire, and the tool result carries the SAME normalized id
          (let [tool-msgs (filter #(= "tool" (:role %)) (:messages payload))
                assistant-msg (first (filter #(= "assistant" (:role %)) (:messages payload)))]
            (t/is (= 9 (count (get-in assistant-msg [:tool_calls 0 :id]))))
            (t/is (re-matches #"[a-zA-Z0-9]{9}" (get-in assistant-msg [:tool_calls 0 :id])))
            (t/is (= (get-in assistant-msg [:tool_calls 0 :id])
                     (get-in (first tool-msgs) [:tool_call_id]))
                  "tool result correlates via the same normalized id"))))
      (finally
        (.close ss)))))

(t/deftest ^:slow test-llm-vertex-stream-end-to-end
  ;; Full send-message → Vertex request (project/location URL, x-goog-api-key)
  ;; → Google SSE response → events.
  (m/load-catalogs!)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-url (atom nil)
        request-body (atom nil)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           first-line (.readLine rdr)
                           clen (atom 0)
                           _ (loop []
                               (let [l (.readLine rdr)]
                                 (when (seq (or l ""))
                                   (when (str/starts-with? (str/lower-case (or l "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs l 15)))))
                                   (recur))))
                           sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n)) m (.read rdr buf)]
                                   (when (pos? m) (.append sb (String. buf 0 m)) (recur (+ n m))))
                                 nil))
                           _ (reset! request-url first-line)
                           _ (reset! request-body (str sb))
                           out (.getOutputStream s)
                           stream-body (str "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}\n\n"
                                            "data: {\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5,\"totalTokenCount\":15,\"cachedContentTokenCount\":2},\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        errors (atom [])
        text (atom "")
        done-reason (atom nil)
        usage (atom nil)
        fut (with-redefs [shared/getenv (fn [k] (case k "GOOGLE_CLOUD_PROJECT" "proj"
                                                      "GCLOUD_PROJECT" nil
                                                      "GOOGLE_CLOUD_LOCATION" "us-central1"
                                                      nil))]
              (llm/send-message {:provider :google-vertex
                                 :model "gemini-3.1-pro-preview"
                                 :api-key "vk"
                                 :base-url (str "http://localhost:" port)
                                 :thinking :high
                                 :messages [{:role :user :content [{:type :text :text "hi"}]}]
                                 :on-text (fn [t] (swap! text str t))
                                 :on-done (fn [r] (reset! done-reason r))
                                 :on-usage (fn [u] (reset! usage u))
                                 :on-error (fn [e] (swap! errors conj e))}))]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hi" @text))
      (t/is (= :stop @done-reason))
      (t/is (= 8 (:input @usage)) "usage: input excludes cached tokens")
      (t/testing "the wire request is Vertex-shaped"
        ;; the :base-url override wins (project/location URL construction is
        ;; unit-tested separately); the request still sends the body + the
        ;; x-goog-api-key auth (the mock captures headers implicitly)
        (t/is (str/starts-with? @request-url "POST / HTTP"))
        (let [payload (json/parse-string @request-body true)]
          (t/is (= [{:parts [{:text "hi"}] :role "user"}] (:contents payload)))
          (t/is (= {:maxOutputTokens 65536
                    :thinkingConfig {:includeThoughts true :thinkingLevel "HIGH"}}
                   (:generationConfig payload))
                "gemini-3.1-pro at :high → includeThoughts + thinkingLevel HIGH")))
      (finally
        (.close ss)))))

(t/deftest test-llm-max-tokens-override
  ;; pi: compaction caps the summarization output at 0.8 * reserveTokens —
  ;; the per-call :max-tokens opt must reach every API builder via the
  ;; model record.
  (m/load-catalogs!)
  (let [request (atom nil)]
    (with-redefs [auth/resolve-provider-auth (fn [_] {:api-key "test-key"})
                  responses/responses-request
                  (fn [opts]
                    (reset! request opts)
                    (future nil))]
      @(llm/send-message {:provider :openai
                          :model "gpt-5.4"
                          :messages []
                          :max-tokens 13107}))
    (t/is (= 13107 (:max-tokens (:model-record @request)))
          "per-call max-tokens overrides the model's configured value")
    (t/is (not= 13107 (:max-tokens (m/get-model :openai "gpt-5.4")))
          "the catalog model record is untouched")))

(t/deftest test-llm-max-tokens-absent-keeps-model-value
  (m/load-catalogs!)
  (let [request (atom nil)]
    (with-redefs [auth/resolve-provider-auth (fn [_] {:api-key "test-key"})
                  responses/responses-request
                  (fn [opts]
                    (reset! request opts)
                    (future nil))]
      @(llm/send-message {:provider :openai
                          :model "gpt-5.4"
                          :messages []}))
    (t/is (= (:max-tokens (m/get-model :openai "gpt-5.4"))
             (:max-tokens (:model-record @request)))
          "no override → the model's configured max-tokens")))
