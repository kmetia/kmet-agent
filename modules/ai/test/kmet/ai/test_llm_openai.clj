(ns kmet.ai.test-llm-openai
  "The OpenAI family: completions, responses (payload and message shapes, caching,
   copilot headers), and the Codex/Azure responses variants, each with its mock-
   server end-to-end."
  (:require [clojure.test :as t]
            [kmet.libs.json :as json]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.ai.auth :as auth]
            [kmet.ai.llm :as llm]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.api.openai-completions :as completions]
            [kmet.ai.api.openai-responses :as responses]
            [kmet.ai.api.openai-codex-responses :as codex]
            [kmet.ai.api.azure-openai-responses :as azure]
            [kmet.ai.api.anthropic-messages :as anthropic]
            [kmet.ai.api.google-generative-ai :as google]
            [kmet.ai.google-adc :as adc]
            [kmet.app.loop :as loop]
            [kmet.app.loop.test-support :as loop-support]
            [kmet.app.session.core :as session]
            [kmet.app.config :as cfg]
            [kmet.ai.model-gen :as model-gen]
            [kmet.ai.models :as m]
            [kmet.app.tools.tool :as tools]))

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
  (m/load-catalogs! nil)
  (let [errors (atom [])]
    @(llm/send-message {:provider :openai-codex
                        :api-key "not-a-jwt"
                        :model "gpt-5.4"
                        :on-error (fn [e] (swap! errors conj e))})
    (t/is (= ["Failed to extract accountId from token"] @errors)
          "an invalid codex token reports via on-error (never hangs the loop)")))

(t/deftest test-llm-azure-missing-config-reports-error
  (m/load-catalogs! nil)
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

(t/deftest test-llm-anthropic-payload-error-reports
  ;; the payload is built inside the request future, before its try: a
  ;; conversion error must still reach on-error (the future completes) instead
  ;; of being swallowed and hanging the caller. The deref stays inside the
  ;; redef scope — the future's payload build runs after send-message returns.
  (m/load-catalogs! nil)
  (let [errors (atom [])]
    (with-redefs [anthropic/anthropic-messages
                  (fn [& _] (throw (ex-info "bad anthropic message shape" {})))]
      (t/is (not= ::timeout
                  (deref (llm/send-message {:provider :anthropic
                                            :api-key "sk"
                                            :model "claude-opus-4-8"
                                            :messages [{:role :user
                                                        :content [{:type :text :text "hi"}]}]
                                            :on-error (fn [e] (swap! errors conj e))})
                         3000 ::timeout))
            "the request future completes"))
    (t/is (= ["bad anthropic message shape"] @errors)
          "a payload-build error reports via on-error (never hangs the loop)")))

(t/deftest test-llm-google-payload-error-reports
  (m/load-catalogs! nil)
  (let [errors (atom [])]
    (with-redefs [google/google-messages
                  (fn [& _] (throw (ex-info "bad google message shape" {})))]
      (t/is (not= ::timeout
                  (deref (llm/send-message {:provider :google
                                            :api-key "sk"
                                            :model "gemini-3.1-pro-preview"
                                            :messages [{:role :user
                                                        :content [{:type :text :text "hi"}]}]
                                            :on-error (fn [e] (swap! errors conj e))})
                         3000 ::timeout))
            "the request future completes"))
    (t/is (= ["bad google message shape"] @errors)
          "a payload-build error reports via on-error (never hangs the loop)")))

(t/deftest test-llm-vertex-adc-token-error-reports
  ;; the ADC token fetch happens while the request future builds the request:
  ;; a token-endpoint failure must reach on-error, not hang the caller
  (m/load-catalogs! nil)
  (let [errors (atom [])]
    (with-redefs [auth/resolve-provider-auth (fn [_] {})
                  auth/ambient-configured? (fn [_] true)
                  auth/resolve-api-key (fn [_] nil)
                  adc/access-token! (fn [] (throw (ex-info "ADC token request failed" {})))]
      (t/is (not= ::timeout
                  (deref (llm/send-message {:provider :google-vertex
                                            :model "gemini-3.1-pro-preview"
                                            :messages [{:role :user
                                                        :content [{:type :text :text "hi"}]}]
                                            :on-error (fn [e] (swap! errors conj e))})
                         3000 ::timeout))
            "the request future completes"))
    (t/is (= ["ADC token request failed"] @errors)
          "an ADC token failure reports via on-error (never hangs the loop)")))

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
  (m/load-catalogs! nil)
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
                                  :ctx (:ctx (loop-support/test-ctx))
                                  :base-url (str "http://localhost:" port "/v1/chat/completions"))]
    (try
      (with-redefs [cfg/get-api-key (fn [_] "sk-test")]
        (loop-support/with-host
          (fn []
            @(loop/run-agent-turn ag {:message "hi" :on-done (fn [_]) :on-error (fn [_])}))))
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

;; ─── OpenAI Responses end-to-end (mock server) ────────────────────────────

(t/deftest ^:slow test-llm-responses-stream-end-to-end
  (m/load-catalogs! nil)
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
             (@#'shared/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}])))
    (t/is (= {"X-Initiator" "agent" "Openai-Intent" "conversation-edits"}
             (@#'shared/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}
               {:role :assistant :content [{:type :text :text "ok"}]}])))
    (t/is (= {"X-Initiator" "agent" "Openai-Intent" "conversation-edits"}
             (@#'shared/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}
               {:role :tool :content [{:type :tool-result :tool_use_id "t" :content "out"}]}]))))
  (t/testing "Copilot-Vision-Request when any user/tool-result message has images"
    (t/is (= {"X-Initiator" "user" "Openai-Intent" "conversation-edits"
              "Copilot-Vision-Request" "true"}
             (@#'shared/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "look"}
                                      {:type :image :data "AA" :mime-type "image/png"}]}])))
    (t/is (= {"X-Initiator" "agent" "Openai-Intent" "conversation-edits"
              "Copilot-Vision-Request" "true"}
             (@#'shared/copilot-dynamic-headers
              [{:role :user :content [{:type :text :text "hi"}]}
               {:role :tool :content [{:type :tool-result :tool_use_id "t" :content "out"}]
                :images [{:type :image :data "AA" :mime-type "image/png"}]}]))))
  (t/testing "no images → no vision header"
    (t/is (nil? (get (@#'shared/copilot-dynamic-headers
                      [{:role :user :content [{:type :text :text "hi"}]}])
                     "Copilot-Vision-Request")))))

(t/deftest test-llm-copilot-wire-headers
  ;; openai-completions + anthropic wires carry the per-request Copilot
  ;; dynamic headers too (pi createClient buildCopilotDynamicHeaders — the
  ;; responses wire had them; the other copilot wires did not).
  (let [model {:id "kimi-k2.7-code" :provider :github-copilot :api :openai-completions
               :base-url "https://api.individual.githubcopilot.com"}
        provider {:id :github-copilot :name "Copilot"}]
    (t/testing "openai-completions: X-Initiator/Openai-Intent on the wire"
      (let [h (@#'completions/openai-request-headers
               model provider "sk-test" nil
               [{:role :user :content [{:type :text :text "hi"}]}])]
        (t/is (= "user" (get h "X-Initiator")))
        (t/is (= "conversation-edits" (get h "Openai-Intent")))
        (t/is (= "Bearer sk-test" (get h "Authorization")))))
    (t/testing "openai-completions: agent + vision follow-up"
      (let [h (@#'completions/openai-request-headers
               model provider "sk-test" nil
               [{:role :user :content [{:type :text :text "hi"}]}
                {:role :tool :content [{:type :tool-result :tool_use_id "t" :content "out"}]
                 :images [{:type :image :data "AA" :mime-type "image/png"}]}])]
        (t/is (= "agent" (get h "X-Initiator")))
        (t/is (= "true" (get h "Copilot-Vision-Request")))))
    (t/testing "openai-completions: non-copilot providers get no dynamic headers"
      (let [h (@#'completions/openai-request-headers
               (assoc model :provider :opencode-go) provider "sk-test" nil
               [{:role :user :content [{:type :text :text "hi"}]}])]
        (t/is (nil? (get h "X-Initiator")))))
    (t/testing "anthropic: copilot claude requests carry the dynamic headers"
      (let [h (@#'anthropic/anthropic-request-headers
               (assoc model :id "claude-sonnet-4.6" :api :anthropic-messages)
               provider "sk-test" nil
               [{:role :user :content [{:type :text :text "hi"}]}])]
        (t/is (= "user" (get h "X-Initiator")))
        (t/is (= "conversation-edits" (get h "Openai-Intent")))
        (t/is (= "2023-06-01" (get h "anthropic-version")))
        (t/is (= "sk-test" (get h "x-api-key")))))
    (t/testing "anthropic: non-copilot providers get no dynamic headers"
      (let [h (@#'anthropic/anthropic-request-headers
               {:id "claude-sonnet-4.6" :provider :anthropic :api :anthropic-messages}
               {:id :anthropic :name "Anthropic"} "sk-test" nil
               [{:role :user :content [{:type :text :text "hi"}]}])]
        (t/is (nil? (get h "X-Initiator")))))
    (t/testing "an account alias keeps its base provider's copilot shaping"
      (let [alias (assoc model :provider :github-copilot#work
                         :base-provider :github-copilot)
            h (@#'completions/openai-request-headers
               alias (assoc provider :id :github-copilot#work) "sk-test" nil
               [{:role :user :content [{:type :text :text "hi"}]}])]
        (t/is (= :github-copilot (m/provider-family alias)))
        (t/is (= "user" (get h "X-Initiator")))
        (t/is (= "conversation-edits" (get h "Openai-Intent")))))))

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

(defn- copilot-responses-run
  "The copilot responses round trip against a mock server: per-request dynamic
   headers (X-Initiator / Openai-Intent / Copilot-Vision-Request) + the static
   COPILOT headers + the session-affinity headers, on the real send-message
   flow. The caller names the model: the shipped catalog moves with models.dev,
   and a rename there must not empty this wire test (it did once)."
  []
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

(t/deftest ^:slow test-llm-copilot-responses-end-to-end
  (m/load-catalogs! nil)
  (let [shipped (m/get-provider :github-copilot)
        _ (t/is (some? shipped) "the catalog still has the copilot provider")
        model (m/map->Model
               {:id "gpt-5.4" :name "GPT-5.4" :provider :github-copilot
                :api :openai-responses
                :base-url "https://api.individual.githubcopilot.com"
                :reasoning true :input [:text :image]
                ;; the generated copilot models carry pi's static headers;
                ;; the responses wire merges the model's, not the provider's
                :headers model-gen/copilot-static-headers
                :cost {:input 2.5 :output 15 :cache-read 0.25 :cache-write 0}
                :context-window 272000 :max-tokens 128000
                :thinking-level-map {:minimal "low" :low "low" :medium "medium"
                                     :high "high" :xhigh "xhigh" :max "max"}})
        provider (assoc shipped
                        :models (conj (into [] (remove #(= "gpt-5.4" (:id %)))
                                            (:models shipped))
                                      model)
                        :default-model "gpt-5.4"
                        :api-types (conj (set (:api-types shipped)) :openai-responses))]
    (m/register-provider! provider)
    (try
      (copilot-responses-run)
      (finally
        (if shipped
          (m/register-provider! shipped)
          (m/unregister-provider! :github-copilot))))))

;; ─── Codex + Azure responses end-to-end (mock server) ─────────────────────

(t/deftest ^:slow test-llm-codex-responses-end-to-end
  (m/load-catalogs! nil)
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
  (m/load-catalogs! nil)
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

