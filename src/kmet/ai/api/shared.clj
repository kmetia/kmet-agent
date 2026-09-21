(ns kmet.ai.api.shared
  "Shared LLM wire machinery (pi: api/transform-messages.ts, simple-options.ts,
   openai-prompt-cache.ts + the shared thinking/usage/event helpers): URL construction,
   request headers, thinking levels, message transformers, cost and stream-event handling."
  (:require
   [kmet.ai.hooks :as hooks]
   [kmet.ai.attribution :as attribution]
   [kmet.ai.auth :as auth]
   [kmet.libs.dynamic-value :as dynamic-value]
   [kmet.libs.json :as json]
   [kmet.libs.http :as lib-http]
   [kmet.ai.models :as models]
   [kmet.libs.usage :as usage]
   [kmet.ai.constrained-sampling :as cs]
   [kmet.libs.hash :as hash]
   [clojure.string :as str]))

;; ─── Provider-event hooks (pi: context / before_provider_request) ────────
;; Re-exported from kmet.ai.hooks (the slots live there so the provider
;; HTTP decorator kmet.ai.http can reach them without a require cycle); the
;; context hook fires here (applied by kmet.ai.llm per call) and the
;; request hook in each api builder.

(def set-context-hook! hooks/set-context-hook!)
(def apply-context-hook hooks/apply-context-hook)
(def set-before-provider-request-hook! hooks/set-before-provider-request-hook!)
(def apply-before-provider-request-hook hooks/apply-before-provider-request-hook)
(def set-before-provider-headers-hook! hooks/set-before-provider-headers-hook!)
(def apply-before-provider-headers-hook hooks/apply-before-provider-headers-hook)
(def set-after-provider-response-hook! hooks/set-after-provider-response-hook!)
(def apply-after-provider-response-hook hooks/apply-after-provider-response-hook)

;; ─── Transport error classification ───────────────────────────────────────
;; Moved into kmet.libs.http (the single HTTP boundary owns the transport
;; error contract); re-exported here so every caller gets the stable
;; retryable error tokens regardless of transport.

(def network-exception-classes lib-http/network-exception-classes)
(def http2-stream-reset-regex lib-http/http2-stream-reset-regex)
(def transport-error-message lib-http/transport-error-message)

(def getenv
  "Process env lookup (System/getenv returns nil for unset vars)."
  (fn [k] (System/getenv k)))

(defn ambient-auth-available?
  "True when a provider that resolves its own ambient auth (no api key)
   is configured — google-vertex ADC or amazon-bedrock AWS credentials
   (auth/ambient-configured?)."
  [provider]
  (auth/ambient-configured? provider))

(defn interpolate-base-url
  "Substitute the {CLOUDFLARE_ACCOUNT_ID} / {CLOUDFLARE_GATEWAY_ID}
   placeholders in a cloudflare base URL from the env (pi cloudflare-auth
   resolve — the account/gateway ids are ambient). A missing id is a
   configuration error, not a transport failure: pi's resolveCloudflareEnv
   returns undefined (not configured) unless all of api key + account id
   (+ gateway id for the gateway) are present."
  [base]
  (if (str/includes? base "{CLOUDFLARE_")
    (let [account (getenv "CLOUDFLARE_ACCOUNT_ID")
          gateway (getenv "CLOUDFLARE_GATEWAY_ID")
          missing (cond-> []
                    (and (str/includes? base "{CLOUDFLARE_ACCOUNT_ID}") (nil? account))
                    (conj "CLOUDFLARE_ACCOUNT_ID")
                    (and (str/includes? base "{CLOUDFLARE_GATEWAY_ID}") (nil? gateway))
                    (conj "CLOUDFLARE_GATEWAY_ID"))]
      (when (seq missing)
        (throw (ex-info (str "Cloudflare requires the env vars: "
                             (str/join ", " missing))
                        {:type :cloudflare-config-missing})))
      (-> base
          (str/replace "{CLOUDFLARE_ACCOUNT_ID}" account)
          (str/replace "{CLOUDFLARE_GATEWAY_ID}" gateway)))
    base))

(defn endpoint-url
  "Full request URL from a wire api + API base + model id (pi: the SDK
   appends the endpoint path)."
  [api base model-id]
  (let [base (interpolate-base-url base)]
    (case api
      :openai-completions (str base "/chat/completions")
      :openai-responses (str base "/responses")
      :anthropic-messages (str base "/v1/messages")
      :google-generative-ai (str base "/models/" model-id ":streamGenerateContent?alt=sse")
      :mistral-conversations (str base "/v1/chat/completions"))))

(defn request-headers
  "Request headers for one request: the provider-attribution layer (session
   headers + origin attribution, pi mergeProviderAttributionHeaders), then
   BASE (api-specific), the model's :headers (static builtin + models.edn
   model-level config values) and the provider's :configured-headers, all
   resolved as config values (literals pass through, $ENV interpolates,
   !command executes — pi resolveConfiguredModelHeaders; unresolvable values
   throw, reported via the builder's on-error). A provider with :auth-header
   adds Authorization: Bearer <api-key> last (pi withConfiguredAuth)."
  [base model provider api-key session-id]
  (let [attribution-hdrs (attribution/merge-provider-attribution-headers
                          model session-id)
        merged (merge attribution-hdrs
                      base
                      (dynamic-value/resolve-headers-or-throw
                       (:headers model)
                       (str "model \"" (name (:provider model)) "/" (:id model) "\""))
                      (dynamic-value/resolve-headers-or-throw
                       (:configured-headers provider)
                       (str "provider \"" (name (:id provider)) "\"")))]
    (cond-> merged
      (:auth-header provider) (assoc "Authorization" (str "Bearer " api-key)))))

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

(defn bash-execution-text
  "Bash result entry → LLM text (pi: bashExecutionToText)."
  [{:keys [command output exit-code cancelled truncated full-output-path]}]
  (let [output (or output "")
        base (str "Ran `" command "`\n"
                  (if (seq output)
                    (str "```\n" output "\n```")
                    "(no output)"))]
    (str base
         (when cancelled "\n\n(command cancelled)")
         (when (and (not cancelled) (some? exit-code) (not (zero? exit-code)))
           (str "\n\nCommand exited with code " exit-code))
         (when (and truncated full-output-path)
           (str "\n\n[Output truncated. Full output: " full-output-path "]")))))

(defn content-text
  "Extract plain text from a message content block vector.
   A block has {:type :text :text \"...\"} or {:type \"text\" :text \"...\"}."
  [content]
  (str/join (for [b content
                  :when (or (= (:type b) :text)
                            (= (:type b) "text"))]
              (:text b))))

(defn image-block?
  "True if a content block is an image block (kmet canonical
   {:type :image :data base64 :mime-type str}, matching pi's read tool
   {type: \"image\", data, mimeType} format)."
  [b]
  (or (= (:type b) :image) (= (:type b) "image")))

(defn openai-content
  "Convert kmet content blocks to OpenAI content. Returns the plain text
   string when there are no image blocks (backward compat); with image blocks
   returns an array of text/image_url blocks (OpenAI vision format)."
  [content]
  (if (some image-block? content)
    (into []
          (for [b content]
            (if (image-block? b)
              {:type "image_url"
               :image_url {:url (str "data:" (:mime-type b) ";base64," (:data b))}}
              {:type "text" :text (or (:text b) "")})))
    (content-text content)))

(defn tool-result-content
  "OpenAI tool result content: the text from the tool_result block, plus
   image_url blocks for any :images on the message (pi: the read tool returns
   image blocks inside the tool result content for vision models)."
  [m]
  (let [text (-> m :content first :content)
        images (:images m)]
    (if (seq images)
      (into [{:type "text" :text (or text "")}]
            (for [i images]
              {:type "image_url"
               :image_url {:url (str "data:" (:mime-type i) ";base64," (:data i))}}))
      text)))

(defn normalize-openai-tool-call-id
  "Pi normalizeToolCallId for Chat Completions: Responses ids use
   `call_id|item_id`, while Chat Completions requires one distinct id of at
   most 40 characters. Preserve the item distinction and hash only when the
   sanitized combined id is too long."
  [id & [provider]]
  (let [id (or id "")]
    (if (str/includes? id "|")
      (let [[call-id item-id] (str/split id #"\|" 2)
            call-id (str/replace call-id #"[^a-zA-Z0-9_-]" "_")
            item-id (str/replace (or item-id "") #"[^a-zA-Z0-9_-]" "_")
            combined (str call-id (when (seq item-id) (str "_" item-id)))]
        (if (<= (count combined) 40)
          combined
          (let [digest (subs (hash/short-hash id) 0 8)
                prefix (subs call-id 0 (min (count call-id)
                                            (max 1 (- 40 (count digest) 1))))]
            (str prefix "_" digest))))
      (if (and (= :openai provider) (> (count id) 40))
        (subs id 0 40)
        id))))

(defn- same-origin?
  "True when a recorded message was produced by the current request's
   provider+model — the provenance stamps every recorded assistant message
   carries (add-assistant-message!/record-abandoned-attempt!). Replay-gating
   predicate: only reasoning the target model produced itself round-trips;
   messages without stamps (legacy sessions) count as foreign."
  [m provider model-id]
  (and (some? provider) (some? model-id)
       (= (:provider m) provider)
       (= (:model m) model-id)))

(defn openai-messages
  "Map agent messages to OpenAI chat-completion messages.
   Bash entries become user messages (pi: convertToLlm bashExecution);
   excluded ones are dropped. PROVIDER and MODEL-ID identify the request's
   target; an assistant message's :thinking is echoed back as
   reasoning_content only when the message was produced by that same
   provider+model (pi: converters replay thinking only for same-model
   messages and degrade everything else — feeding another model's
   chain-of-thought back as if it were the target's own derails DeepSeek-class
   thinking models into re-running their last tool call after a mid-session
   /model switch). Tested directly by test_llm, hence public."
  [messages & [provider model-id]]
  (into []
        (keep (fn [m]
                (let [role (name (:role m))
                      ;; provenance-gated: nil unless this model said it
                      thinking (when (same-origin? m provider model-id)
                                 (str/trim (or (:thinking m) "")))]
                  (case role
                    "bash"
                    (when-not (:exclude-from-context? m)
                      {:role "user" :content (bash-execution-text m)})
                    "tool"
                    {:role "tool"
                     :tool_call_id (normalize-openai-tool-call-id
                                    (-> m :content first :tool_use_id) provider)
                     :content (tool-result-content m)}
                    "assistant"
                    (let [text (content-text (:content m))
                          has-tc (seq (:tool-calls m))
                          msg (cond-> {:role "assistant"}
                                (seq text) (assoc :content text)
                                has-tc
                                (assoc :tool_calls
                                       (mapv (fn [tc]
                                               {:id (normalize-openai-tool-call-id (:id tc) provider)
                                                :type "function"
                                                :function {:name (:name tc)
                                                           :arguments (json/generate-string
                                                                       (:arguments tc))}})
                                             (:tool-calls m))))]
                      ;; pi: some providers require "either content or
                      ;; tool_calls, but not none" — skip empty assistant
                      ;; messages (a turn that ended with only reasoning, or
                      ;; an aborted response that got no content) instead of
                      ;; sending content ""
                      (when (or (seq text) has-tc)
                        (cond-> msg
                          (seq thinking) (assoc :reasoning_content thinking))))
                    ;; custom messages (pi: convertToLlm custom→user)
                    "custom"
                    {:role "user" :content (openai-content (:content m))}
                    {:role role
                     :content (openai-content (:content m))}))))
        messages))

(defn openai-messages-with-reasoning
  "Like openai-messages but every kept assistant message carries a
   reasoning_content field. Some providers (e.g., opencode-go/deepseek-v4-flash)
   require the field on assistant messages even when empty — DeepSeek
   thinking-mode rejects requests that carry tools but omit reasoning_content
   on any assistant message (400 \"must be passed back to the API\").
   PROVIDER and MODEL-ID identify the request's target; a message's own
   :thinking is sent back verbatim only when it was produced by that same
   provider+model (see openai-messages — cross-model CoT is never replayed);
   everything else gets the empty-string fill."
  [messages & [provider model-id]]
  (into []
        (keep (fn [m]
                (let [role (name (:role m))
                      ;; provenance-gated: "" unless this model said it
                      thinking (or (when (same-origin? m provider model-id)
                                     (str/trim (or (:thinking m) "")))
                                   "")]
                  (case role
                    "bash"
                    (when-not (:exclude-from-context? m)
                      {:role "user" :content (bash-execution-text m)})
                    "tool"
                    {:role "tool"
                     :tool_call_id (normalize-openai-tool-call-id
                                    (-> m :content first :tool_use_id) provider)
                     :content (tool-result-content m)}
                    "assistant"
                    (let [text (content-text (:content m))
                          has-tc (seq (:tool-calls m))
                          msg (cond-> {:role "assistant"}
                                (seq text) (assoc :content text)
                                has-tc (assoc :tool_calls
                                              (mapv (fn [tc]
                                                      {:id (normalize-openai-tool-call-id (:id tc) provider)
                                                       :type "function"
                                                       :function {:name (:name tc)
                                                                  :arguments (json/generate-string
                                                                              (:arguments tc))}})
                                                    (:tool-calls m))))]
                      ;; skip empty assistant messages (pi: providers
                      ;; require content or tool_calls) — an empty
                      ;; reasoning_content-only message is rejected too
                      (when (or (seq text) has-tc)
                        ;; deepseek thinking mode requires reasoning_content on assistant messages
                        (assoc msg :reasoning_content thinking)))
                    ;; custom messages (pi: convertToLlm custom→user)
                    "custom"
                    {:role "user"
                     :content (openai-content (:content m))}
                    {:role role
                     :content (openai-content (:content m))})))
              messages)))

(defn resolve-template-values
  "pi buildChatTemplateValues + resolveChatTemplateKwargValue: resolve the
   compat :chat-template-args/:chat-template-kwargs spec for one request —
   literals pass through, {$var: \"thinking.enabled\"} → the on/off boolean,
   other vars → the thinking-level-map value for the effort (or the :off
   value when off); a value with :omit-when-off is dropped when thinking is
   off. Returns nil when nothing resolves."
  [model effort values]
  (let [resolved (into {}
                       (keep (fn [[k v]]
                               (let [v (if (and (map? v) (:omit-when-off v) (nil? effort))
                                         nil
                                         (cond
                                           (and (map? v) (= "thinking.enabled" (:var v)))
                                           (boolean effort)

                                           (map? v)
                                           (let [mapped (get-in model [:thinking-level-map
                                                                       (or effort :off)])]
                                             (cond
                                               (nil? mapped) effort
                                               (string? mapped) mapped
                                               :else nil))

                                           :else v))]
                                 (when (some? v) [k v]))))
                       values)]
    (when (seq resolved) resolved)))

(defn detect-openai-compat
  "pi detectCompat (packages/ai/src/api/openai-completions.ts): auto-detected
   openai-completions compatibility defaults for a model from its provider id
   and base URL — the base layer wherever the model's explicit :compat leaves
   a key unset (resolved-openai-compat applies it). Explicit compat entries
   always override these values.

   Carries pi's full detected key set for parity; keys without a kmet
   consumer yet (:supports-store, :supports-developer-role,
   :requires-tool-result-name, ...) are inert data. Routing maps
   (openRouterRouting/vercelGatewayRouting) and feature keys with no kmet
   implementation (zaiToolStream, deferredToolsMode,
   supportsOpenAIGrammarTools) are not ported. Unlike pi, URL matching is
   case-insensitive across all providers."
  [{:keys [provider id base-url]}]
  (let [provider (some-> provider name)
        url (str/lower-case (or base-url ""))
        id-str (or id "")
        in-url? (fn [s] (str/includes? url s))
        zai? (or (contains? #{"zai" "zai-coding-cn"} provider)
                 (in-url? "api.z.ai")
                 (in-url? "open.bigmodel.cn"))
        together? (or (= provider "together")
                      (in-url? "api.together.ai")
                      (in-url? "api.together.xyz"))
        moonshot? (or (contains? #{"moonshotai" "moonshotai-cn"} provider)
                      (in-url? "api.moonshot."))
        openrouter? (or (= provider "openrouter") (in-url? "openrouter.ai"))
        cf-workers? (or (= provider "cloudflare-workers-ai") (in-url? "api.cloudflare.com"))
        cf-gateway? (or (= provider "cloudflare-ai-gateway") (in-url? "gateway.ai.cloudflare.com"))
        nvidia? (or (= provider "nvidia") (in-url? "integrate.api.nvidia.com"))
        ant-ling? (or (= provider "ant-ling") (in-url? "api.ant-ling.com"))
        deepseek? (or (= provider "deepseek") (in-url? "deepseek.com"))
        non-standard? (or nvidia?
                          (= provider "cerebras")
                          (in-url? "cerebras.ai")
                          (= provider "xai")
                          (in-url? "api.x.ai")
                          together?
                          (in-url? "chutes.ai")
                          deepseek?
                          zai?
                          moonshot?
                          (= provider "opencode")
                          (in-url? "opencode.ai")
                          cf-workers?
                          cf-gateway?
                          ant-ling?)
        use-max-tokens? (or (in-url? "chutes.ai")
                            deepseek?
                            moonshot?
                            cf-gateway?
                            together?
                            nvidia?
                            ant-ling?
                            zai?)
        grok? (or (= provider "xai") (in-url? "api.x.ai"))
        openrouter-dev-role? (and openrouter?
                                  (some #(str/starts-with? id-str %)
                                        ["anthropic/" "openai/"]))]
    {:supports-store (not non-standard?)
     :supports-developer-role (or openrouter-dev-role?
                                  (and (not non-standard?) (not openrouter?)))
     :supports-reasoning-effort (and (not grok?) (not zai?) (not moonshot?)
                                     (not together?) (not cf-gateway?) (not nvidia?)
                                     (not ant-ling?))
     :supports-usage-in-streaming true
     :supports-finish-reason true
     :max-tokens-field (if use-max-tokens? :max-tokens :max-completion-tokens)
     :requires-tool-result-name false
     :requires-assistant-after-tool-result false
     :requires-thinking-as-text false
     :requires-reasoning-content-on-assistant-messages deepseek?
     :thinking-format (cond
                        deepseek? :deepseek
                        zai? :zai
                        together? :together
                        ant-ling? :ant-ling
                        openrouter? :openrouter
                        :else :openai)
     :chat-template-kwargs {}
     :chat-template-args {}
     :supports-thinking-token-budget false
     ;; openai-completions compatibility alone does not imply strict JSON-schema
     ;; tool support (pi 890f92088): the generated catalog carries an explicit
     ;; true for capable models, and that explicit compat wins over this default.
     :supports-strict-mode false
     :cache-control-format (when (and openrouter? (str/starts-with? id-str "anthropic/"))
                             :anthropic)
     :send-session-affinity-headers false
     :session-affinity-format (if openrouter? :openrouter :openai)
     :supports-long-cache-retention (not (or together? cf-workers? cf-gateway? nvidia? ant-ling?))}))

(defn resolved-openai-compat
  "pi getCompat: the model's explicit :compat overlaid key-wise on
   detect-openai-compat — an explicit non-nil value wins, nil/absent falls
   back to the detected default (pi's ?? merge). The single source of compat
   truth for the openai-completions request builders.

   One deliberate exception to pure key-wise merge: an explicit
   :thinking-format :deepseek pins a model to the DeepSeek thinking-mode
   protocol, which requires reasoning_content on every assistant message once
   tools are carried. Proxies detect-openai-compat can't recognize (custom
   provider ids / non-deepseek.com base urls) would otherwise leave the
   requirement undetected and 400 mid-session — so the requirement is derived
   from the format unless the compat sets the flag explicitly."
  [model]
  (let [explicit (:compat model)
        merged (into {}
                     (map (fn [[k detected]]
                            [k (let [v (get explicit k)] (if (nil? v) detected v))]))
                     (detect-openai-compat model))]
    (cond-> merged
      (and (= :deepseek (:thinking-format merged))
           (nil? (get explicit :requires-reasoning-content-on-assistant-messages)))
      (assoc :requires-reasoning-content-on-assistant-messages true))))

(defn openai-thinking-params
  "Thinking params for an openai-completions payload (pi buildParams thinking
   section; kmet's formats: default/openai, deepseek, qwen, openrouter,
   zai, together, baseten, ant-ling, string-thinking, chat-template,
   qwen-chat-template). EFFORT is the clamped level, nil when off. Compat is
   resolved against URL detection (resolved-openai-compat), so a model whose
   explicit :compat omits a key still gets its provider's format."
  [model effort]
  (let [reasoning? (:reasoning model)
        compat (resolved-openai-compat model)
        fmt (:thinking-format compat)
        effort? (not= false (:supports-reasoning-effort compat))]
    (cond
      (and reasoning? (= fmt :openrouter))
      ;; OpenRouter normalizes reasoning across providers via a nested
      ;; reasoning object (pi thinkingFormat "openrouter"); off →
      ;; reasoning: {effort: "none"} unless the model pins :off to null.
      (cond-> {}
        effort (assoc :reasoning {:effort (effort-value model effort)})
        (and (nil? effort) (not (off-explicitly-null? model)))
        (assoc :reasoning {:effort (or (get-in model [:thinking-level-map :off]) "none")}))

      (and reasoning? (= fmt :deepseek))
      (cond-> {}
        effort (assoc :thinking {:type "enabled"})
        (and (nil? effort) (not (off-explicitly-null? model)))
        (assoc :thinking {:type "disabled"})
        (and effort effort?)
        (assoc :reasoning_effort (effort-value model effort)))

      (and reasoning? (= fmt :qwen))
      (cond-> {:enable_thinking (boolean effort)}
        (and effort effort?)
        (assoc :reasoning_effort (effort-value model effort)))

      (and reasoning? (= fmt :zai))
      ;; pi thinkingFormat "zai": thinking {type} + reasoning_effort when
      ;; the provider supports it (zai models always send the thinking
      ;; object — no off:null gate, matching pi).
      (cond-> {:thinking (if effort
                           {:type "enabled" :clear_thinking false}
                           {:type "disabled"})}
        (and effort effort?)
        (assoc :reasoning_effort (effort-value model effort)))

      (and reasoning? (= fmt :together))
      ;; pi thinkingFormat "together": nested reasoning: {enabled} +
      ;; reasoning_effort when supported.
      (cond-> {:reasoning {:enabled (boolean effort)}}
        (and effort effort?)
        (assoc :reasoning_effort (effort-value model effort)))

      (and reasoning? (= fmt :qwen-chat-template))
      {:chat_template_kwargs {:enable_thinking (boolean effort)
                              :preserve_thinking true}}

      (and reasoning? (= fmt :chat-template))
      (cond-> {}
        (seq (:chat-template-kwargs compat))
        (assoc :chat_template_kwargs
               (resolve-template-values model effort
                                        (:chat-template-kwargs compat))))

      (and reasoning? (= fmt :baseten))
      ;; pi thinkingFormat "baseten": chat_template_args from the compat
      ;; spec ($var thinking.enabled) + reasoning_effort when supported
      ;; (the off value from the thinking-level-map).
      (cond-> {}
        (seq (:chat-template-args compat))
        (assoc :chat_template_args
               (resolve-template-values model effort
                                        (:chat-template-args compat)))
        (and effort effort?)
        (assoc :reasoning_effort (effort-value model effort))
        (and (nil? effort) (string? (get-in model [:thinking-level-map :off])))
        (assoc :reasoning_effort (get-in model [:thinking-level-map :off])))

      (and reasoning? (= fmt :ant-ling))
      ;; pi thinkingFormat "ant-ling": the branch is empty — Ring reasons
      ;; by default and ignores explicit effort; the trailing default
      ;; branches must not fire.
      {}

      (and reasoning? (= fmt :string-thinking))
      (cond-> {}
        effort (assoc :thinking (effort-value model effort))
        (and (nil? effort) (not (off-explicitly-null? model)))
        (assoc :thinking (or (get-in model [:thinking-level-map :off]) "none")))

      (and reasoning? effort effort?)
      {:reasoning_effort (effort-value model effort)}

      (and reasoning? (nil? effort) effort?)
      (let [off (get-in model [:thinking-level-map :off])]
        (if (string? off) {:reasoning_effort off} {}))

      :else {})))

(def thinking-budgets
  "pi adjustMaxTokensForThinking defaultBudgets (minimal/low/medium/high)."
  {:minimal 1024 :low 2048 :medium 8192 :high 16384})

(def min-answer-tokens 1024)

(defn anthropic-adaptive-effort
  "pi mapThinkingLevelToEffort: the adaptive output_config effort — the
   model's thinking-level-map value when mapped, else the level collapsed to
   low/medium/high (xhigh/max → high)."
  [model effort]
  (let [mapped (get-in model [:thinking-level-map effort])]
    (cond
      (string? mapped) mapped
      (contains? #{:minimal :low} effort) "low"
      (= :medium effort) "medium"
      :else "high")))

(defn anthropic-thinking
  "pi streamSimple thinking: the thinking config + max_tokens for an
   anthropic payload. EFFORT is the clamped level; nil when off.
   forceAdaptiveThinking models (kimi-coding, claude 4.6+ per pi) send
   thinking {type adaptive} + output_config {effort} instead of a budget.
   Returns nil when thinking is off and the model allows disabling."
  [model effort]
  (cond
    (not (:reasoning model)) nil
    effort
    (if (:force-adaptive-thinking (:compat model))
      {:thinking {:type "adaptive" :display "summarized"}
       :output_config {:effort (anthropic-adaptive-effort model effort)}
       :max-tokens (or (:max-tokens model) 4096)}
      (let [level (if (contains? #{:xhigh :max} effort) :high effort)
            budget (get thinking-budgets level 0)
            max-tokens (or (:max-tokens model) 4096)
            budget (min budget (max 0 (- max-tokens min-answer-tokens)))]
        {:thinking {:type "enabled" :budget_tokens budget :display "summarized"}
         :max-tokens max-tokens}))
    (off-explicitly-null? model) nil
    :else {:thinking {:type "disabled"}
           :max-tokens (or (:max-tokens model) 4096)}))

(defn max-tokens-key
  "Payload key for the model's max tokens (pi: compat.maxTokensField —
   :max_tokens when set or detected for a max-tokens provider like
   deepseek/moonshot/together/zai, :max_completion_tokens by default).
   Reads the URL-resolved compat (resolved-openai-compat)."
  [model]
  (if (= :max-tokens (:max-tokens-field (resolved-openai-compat model)))
    :max_tokens
    :max_completion_tokens))

(defn usage-with-cost
  "Attach the per-message USD cost (pi: calculateCost runs in the wire API)
   to a provider-native usage map, computed from the Model record that
   produced the response. Usage maps without recognizable tokens pass
   through unchanged."
  [model-record usage]
  (if-let [norm (usage/entry-usage usage)]
    (assoc usage :cost (models/calculate-cost model-record norm))
    usage))

(defn responses-events-handler
  "The event dispatch shared by the responses-family request builders
   (openai-responses / azure / codex) and the other stream wires (mistral,
   bedrock, google-vertex): text/thinking/tool-call deltas, done/usage/error.

   Returns [dispatch finalize]. DISPATCH handles one stream event but
   DEFERS the terminal :done — it only captures the first stop-reason. The
   chat-format streams (mistral / bedrock) send their usage-only chunk AFTER
   the finish_reason / messageStop chunk, so emitting on-done at the first
   :done would fire before the final :usage and drop the provider usage
   (footer token counts / cost never update; pi resolves its stream only
   after the final chunk, so usage is always captured). The responses family
   and google-vertex emit usage with the terminal event already, where the
   deferral is a no-op. FINALIZE emits the deferred on-done once the stream
   processor consumed the whole body (skipped on cancel / error / no
   stop-reason)."
  [{:keys [on-text on-thinking on-tool-call on-done on-error on-usage]} model-record]
  (let [stop-reason (atom nil)
        stop-reason-error (atom nil) ;; pi output.errorMessage — the
                                     ;; provider's specific finish-reason
                                     ;; error text, kept alongside an :error
                                     ;; stop-reason
        errored? (atom false)]
    [(fn [event]
       (case (:type event)
         :text (when on-text (on-text (:content event)))
         :thinking (when on-thinking (on-thinking (:content event)))
         :tool-call (when on-tool-call
                      (on-tool-call {:id (:id event)
                                     :name (:name event)
                                     :arguments (:arguments event)
                                     :index (:index event)}))
         :tool-call-args (when on-tool-call
                           (on-tool-call {:arguments (:arguments event)
                                          :index (:index event)}))
         :done (let [sr (:stop-reason event)]
                 (when (compare-and-set! stop-reason nil sr)
                   (when (= :error sr)
                     (reset! stop-reason-error (:error-message event)))))
         :usage (when on-usage (on-usage (usage-with-cost model-record (:usage event))))
         :error (do (reset! errored? true)
                    (when on-error (on-error (:message event))))
         nil))
     (fn [cancelled?]
       (let [sr @stop-reason]
         (when (and (= :error sr) (not @errored?) on-error)
           (on-error (or @stop-reason-error
                         (str "Provider stopped with: " (name sr)))))
         (when (and on-done
                    (some? sr)
                    (not= :error sr)
                    (not @errored?)
                    (not cancelled?))
           (on-done sr))))]))

;; ─── Tool schema conversion (pi convertTools — moved from app.tools.registry) ──

(defn tool->anthropic-schema
  "Convert a tool map to Anthropic tool schema format. With SUPPORTS-STRICT?
   (pi: supportsStrictTools compat), a tool whose :constrained-sampling
   resolves strict gets a strictified input_schema and :strict true."
  [tool & [supports-strict?]]
  (let [strict (cs/resolve-json-schema-strict-sampling tool supports-strict?)]
    (cond-> {:name (:name tool)
             :description (:description tool)
             :input_schema (cs/get-json-schema-tool-parameters tool strict)}
      (true? strict) (assoc :strict true))))

(defn tool->openai-schema
  "Convert a tool map to OpenAI tool schema format. With SUPPORTS-STRICT?,
   a strict-resolving tool gets strictified parameters and :strict true on
   the function; other tools get :strict false (pi: strict ?? false)."
  [tool & [supports-strict?]]
  (let [strict (cs/resolve-json-schema-strict-sampling tool supports-strict?)]
    {:type "function"
     :function (cond-> {:name (:name tool)
                        :description (:description tool)
                        :parameters (cs/get-json-schema-tool-parameters tool strict)}
                 supports-strict? (assoc :strict (true? strict)))}))

(defn tool->google-schema
  "Convert a tool map to a Google functionDeclaration (pi convertTools).
   With SUPPORTS-STRICT?, a strict-resolving tool gets the strictified
   schema (pi: parametersJsonSchema)."
  [tool & [supports-strict?]]
  (let [strict (cs/resolve-json-schema-strict-sampling tool supports-strict?)]
    {:name (:name tool)
     :description (:description tool)
     :parameters (cs/get-json-schema-tool-parameters tool strict)}))

(defn google-supports-strict-tool-sampling?
  "pi supportsGoogleStrictToolSampling: Gemini 3+ enforces required function
   parameters in validated tool-calling modes."
  [model-id]
  (let [major (second (re-find #"(?i)^gemini(?:-live)?-(\d+)" (or model-id "")))]
    (boolean (and major (>= (Long/parseLong major) 3)))))
