(ns kmet.ai.api.shared
  "Shared LLM wire machinery (pi: api/transform-messages.ts, simple-options.ts,
   openai-prompt-cache.ts + the shared thinking-param/usage/event helpers): URL
   construction, request headers, thinking params, message transformers, cost and
   stream-event handling."
  (:require
   [kmet.ai.attribution :as attribution]
   [kmet.ai.auth :as auth]
   [kmet.libs.dynamic-value :as dynamic-value]
   [kmet.libs.json :as json]
   [kmet.ai.message :as message]
   [kmet.ai.models :as models]
   [kmet.ai.usage :as usage]
   [kmet.ai.constrained-sampling :as cs]
   [kmet.ai.hash :as hash]
   [clojure.string :as str]))

;; ─── Provider-event hooks (pi: context / before_provider_request) ────────
;; The hooks are `kmet.ai.llm/send-message` options (:on-context /
;; :on-payload / :on-headers / :on-response), not registrations: the context
;; hook fires in kmet.ai.llm, the payload hook here (each api builder applies
;; it), and the header/response hooks in the kmet.ai.http decorator, which
;; receives them as an argument.

(defn on-payload
  "Run the request's :on-payload hook over PAYLOAD (pi:
   before_provider_request); nil keeps the payload unchanged."
  [opts payload]
  (if-let [f (:on-payload opts)]
    (or (f payload) payload)
    payload))

;; ─── Transport error classification ───────────────────────────────────────
;; Moved into kmet.libs.http (the single HTTP boundary owns the transport
;; error contract); re-exported here so every caller gets the stable
;; retryable error tokens regardless of transport.

(defn effective-total-timeout-ms
  "Resolve the total transport timeout: an explicit non-positive value
   disables it; only nil falls back to a positive idle timeout."
  [total-timeout-ms idle-timeout-ms]
  (if (some? total-timeout-ms)
    (when (pos? total-timeout-ms) total-timeout-ms)
    (when (pos? (or idle-timeout-ms 0)) idle-timeout-ms)))

(defn headers-timeout-ms
  "The response-start deadline for a streaming LLM request (pi: undici
   headersTimeout = httpIdleTimeoutMs): the idle deadline that bounds the
   SSE body also bounds the wait for the response headers, so a server that
   accepts the request and never responds fails after ~idle instead of
   hanging the turn forever. nil or non-positive disables it (the explicit
   \"no deadline\" user choice)."
  [idle-timeout-ms]
  (when (pos? (or idle-timeout-ms 0)) idle-timeout-ms))

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

(defn copilot-vision-input?
  "pi hasCopilotVisionInput: any user or tool-result message carries image
   content (Copilot-Vision-Request header)."
  [messages]
  (boolean
   (some (fn [m]
           (case (name (:role m))
             "user" (some message/image-block? (:content m))
             "tool" (seq (:images m))
             false))
         messages)))

(defn copilot-dynamic-headers
  "pi buildCopilotDynamicHeaders: per-request Copilot headers — X-Initiator
   (user vs agent, from the last message role) and Openai-Intent, plus
   Copilot-Vision-Request when any input has images."
  [messages]
  (let [last-role (some-> (peek (vec messages)) :role name)
        ;; pi inferCopilotInitiator: no messages → "user"
        initiator (if (= "user" last-role) "user" "agent")
        headers (array-map "X-Initiator" initiator
                           "Openai-Intent" "conversation-edits")]
    (cond-> headers
      (copilot-vision-input? messages) (assoc "Copilot-Vision-Request" "true"))))

(defn openai-content
  "Convert kmet content blocks to OpenAI content. Returns the plain text
   string when there are no image blocks (backward compat); with image blocks
   returns an array of text/image_url blocks (OpenAI vision format)."
  [content]
  (if (some message/image-block? content)
    (into []
          (for [b content]
            (if (message/image-block? b)
              {:type "image_url"
               :image_url {:url (str "data:" (:mime-type b) ";base64," (:data b))}}
              {:type "text" :text (or (:text b) "")})))
    (message/content-text content)))

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

(defn- message-target
  "Split a message-conversion target into its provenance provider (the id
   stamped into recorded messages) and its provider family (the catalog
   provider whose request shaping applies). TARGET is a Model or Provider
   record — the normal call, which keeps provenance and family together — or a
   bare provider keyword for alias-free callers and tests."
  [target]
  (if (map? target)
    {:provider (or (:provider target) (:id target))
     :family (models/provider-family target)}
    {:provider target
     :family target}))

(defn- openai-text-parts
  "A message's non-blank text blocks as OpenAI text parts (pi's
   assistantTextParts) — the parts :requires-thinking-as-text puts the
   thinking in front of."
  [content]
  (into []
        (keep (fn [b]
                (when (and (contains? #{:text "text"} (:type b))
                           (not (str/blank? (:text b))))
                  {:type "text" :text (:text b)})))
        content))

(defn- message-context
  "The per-request values the chat-completion conversion reads: the target's
   provenance and provider family (see message-target) plus OPTS' reasoning
   mode and the resolved compat's message-shape keys."
  [target model-id {:keys [reasoning? fill compat]}]
  (let [{:keys [provider family]} (message-target target)]
    {:provider provider
     :family family
     :model-id model-id
     :reasoning? reasoning?
     :fill fill
     :tool-result-name? (:requires-tool-result-name compat)
     :after-tool-result? (:requires-assistant-after-tool-result compat)
     :thinking-as-text? (:requires-thinking-as-text compat)}))

(defn- openai-tool-message
  "A tool result as a chat-completion tool message. pi requiresToolResultName:
   the tool's name rides the message when the provider asks for it."
  [{:keys [family tool-result-name?]} m]
  (cond-> {:role "tool"
           :tool_call_id (normalize-openai-tool-call-id
                          (-> m :content first :tool_use_id) family)
           :content (tool-result-content m)}
    (and tool-result-name? (:tool-name m)) (assoc :name (:tool-name m))))

(defn- openai-assistant-content
  "pi's content decision for an assistant turn: the thinking-as-text parts,
   the message's own text, the empty string an after-tool-result provider
   needs in place of null, or nil (the field stays absent)."
  [content text as-text after-tool-result?]
  (cond
    as-text (into [{:type "text" :text as-text}] (openai-text-parts content))
    (seq text) text
    after-tool-result? ""
    :else nil))

(defn- openai-assistant-message
  "One assistant turn as a chat-completion message, or nil when it is skipped
   (pi: content or tool_calls, but never neither). The two message-shape keys
   are decided here: under :requires-thinking-as-text the message's own
   thinking rides in the content as a leading text part, and under
   :requires-assistant-after-tool-result an absent content becomes \"\". Only
   the request model's own thinking is ever replayed (the provenance gate
   openai-messages documents); FILL is what a provider that requires
   reasoning_content gets when the message holds no same-model reasoning."
  [{:keys [provider model-id family reasoning? fill thinking-as-text?
           after-tool-result?]} m]
  (let [;; provenance-gated: another model's chain-of-thought is never replayed
        thinking (when (same-origin? m provider model-id)
                   (not-empty (str/trim (or (:thinking m) ""))))
        as-text (and thinking-as-text? thinking)
        text (message/content-text (:content m))
        has-tc (seq (:tool-calls m))
        content (openai-assistant-content (:content m) text as-text after-tool-result?)
        reasoning (cond
                    ;; the thinking is text now, so the field takes the fill
                    as-text (when reasoning? (or fill ""))
                    reasoning? (or thinking fill "")
                    :else thinking)]
    (when (or (seq text) has-tc)
      (cond-> {:role "assistant"}
        (some? content) (assoc :content content)
        has-tc (assoc :tool_calls
                      (mapv (fn [tc]
                              {:id (normalize-openai-tool-call-id (:id tc) family)
                               :type "function"
                               :function {:name (:name tc)
                                          :arguments (json/generate-string
                                                      (:arguments tc))}})
                            (:tool-calls m)))
        (some? reasoning) (assoc :reasoning_content reasoning)))))

(defn- openai-chat-message
  "One kmet wire message as the chat-completion message it becomes (nil when
   it is dropped)."
  [ctx m]
  (case (name (:role m))
    "tool" (openai-tool-message ctx m)
    "assistant" (openai-assistant-message ctx m)
    {:role (name (:role m)) :content (openai-content (:content m))}))

(defn bridge-tool-results
  "pi requiresAssistantAfterToolResult: a synthetic assistant turn between a
   tool result and the user turn that follows it, for providers that reject
   the pair. Runs on the converted messages, so the previous message is the
   last one actually emitted — an assistant turn dropped in between does not
   break the bridge (pi: lastRole survives a skipped turn). Idempotent: a list
   that already carries the bridge passes through unchanged."
  [messages]
  (into []
        (mapcat (fn [prev m]
                  (if (and (= "tool" (:role prev)) (= "user" (:role m)))
                    [{:role "assistant"
                      :content "I have processed the tool results."} m]
                    [m]))
                (cons nil messages) messages)))

(defn- convert-openai-messages
  "The one converter behind openai-messages / openai-messages-with-reasoning.
   OPTS carries :reasoning? (always emit reasoning_content, with :fill as the
   placeholder when there is no same-model reasoning) and the resolved
   compat's message-shape keys (see message-context and
   openai-assistant-message); nil compat keeps the plain shape.

   One turn can become two messages: the compat's tool-result bridge
   (bridge-tool-results) runs over the converted list."
  [messages target model-id opts]
  (let [ctx (message-context target model-id opts)
        converted (into [] (keep #(when-not (= :tool-change (:role %))
                                    (openai-chat-message ctx %))) messages)]
    (if (:after-tool-result? ctx)
      (bridge-tool-results converted)
      converted)))

(defn openai-messages
  "Map wire messages to OpenAI chat-completion messages. The caller has
   already converted agent roles (pi: convertToLlm, kmet.app.loop.message/
   to-llm-messages), so every role here is a provider role. TARGET and
   MODEL-ID identify the request's
   target (see message-target: an account alias keeps its own provenance id
   while its base provider's request shaping applies); an assistant message's
   :thinking is echoed back as reasoning_content only when the message was
   produced by that same provider+model (pi: converters replay thinking only
   for same-model messages and degrade everything else — feeding another
   model's chain-of-thought back as if it were the target's own derails
   DeepSeek-class thinking models into re-running their last tool call after a
   mid-session /model switch). COMPAT is the request's resolved compat: its
   :requires-tool-result-name / :requires-assistant-after-tool-result /
   :requires-thinking-as-text reshape the messages as pi's buildParams does.
   Tested directly by test_llm, hence public."
  [messages & [target model-id compat]]
  (convert-openai-messages messages target model-id
                           {:reasoning? false :compat compat}))

(defn openai-messages-with-reasoning
  "Like openai-messages but every kept assistant message carries a
   reasoning_content field. Some providers (e.g., opencode-go/deepseek-v4-flash)
   require the field on assistant messages even when empty — DeepSeek
   thinking-mode rejects requests that carry tools but omit reasoning_content
   on any assistant message (400 \"must be passed back to the API\").
   TARGET and MODEL-ID identify the request's target (see message-target); a
   message's own :thinking is sent back verbatim only when it was produced by
   that same provider+model (see openai-messages — cross-model CoT is never
   replayed); everything else gets FILL (default \"\"). FILL must be non-empty
   for endpoints that turn the field into a thinking block and reject an empty
   one (the stealth models: compat :reasoning-content-fill). COMPAT is the
   resolved compat, as in openai-messages; with :requires-thinking-as-text the
   reasoning rides in the content and this field takes FILL (pi: the required
   empty field is filled after the thinking has been converted)."
  [messages & [target model-id fill compat]]
  (convert-openai-messages messages target model-id
                           {:reasoning? true :fill fill :compat compat}))

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

(defn- zai-provider? [provider url]
  (or (contains? #{"zai" "zai-coding-cn"} provider)
      (str/includes? url "api.z.ai")
      (str/includes? url "open.bigmodel.cn")))

(defn- together-provider? [provider url]
  (or (= provider "together")
      (str/includes? url "api.together.ai")
      (str/includes? url "api.together.xyz")))

(defn- moonshot-provider? [provider url]
  (or (contains? #{"moonshotai" "moonshotai-cn"} provider)
      (str/includes? url "api.moonshot.")))

(defn openrouter-provider?
  "OpenRouter by provider family id or base URL (pi: provider === \"openrouter\"
   || baseUrl.includes(\"openrouter.ai\")); shared by the compat detectors."
  [provider url]
  (or (= provider "openrouter")
      (str/includes? (or url "") "openrouter.ai")))

(defn- cloudflare-workers-provider? [provider url]
  (or (= provider "cloudflare-workers-ai")
      (str/includes? url "api.cloudflare.com")))

(defn- cloudflare-gateway-provider? [provider url]
  (or (= provider "cloudflare-ai-gateway")
      (str/includes? url "gateway.ai.cloudflare.com")))

(defn- nvidia-provider? [provider url]
  (or (= provider "nvidia")
      (str/includes? url "integrate.api.nvidia.com")))

(defn- ant-ling-provider? [provider url]
  (or (= provider "ant-ling")
      (str/includes? url "api.ant-ling.com")))

(defn- deepseek-provider? [provider url]
  (or (= provider "deepseek")
      (str/includes? url "deepseek.com")))

(defn- grok-provider? [provider url]
  (or (= provider "xai")
      (str/includes? url "api.x.ai")))

(defn detect-openai-compat
  "pi detectCompat (packages/ai/src/api/openai-completions.ts): auto-detected
   openai-completions compatibility defaults for a model from its provider
   family (models/provider-family — an account alias keeps its base provider's
   detection) and base URL — the base layer wherever the model's explicit
   :compat leaves a key unset (resolved-openai-compat applies it). Explicit
   compat entries always override these values.

   Carries pi's full detected key set for parity. The message-shape keys
   (:requires-tool-result-name, :requires-assistant-after-tool-result,
   :requires-thinking-as-text) are read by the message converters and
   :supports-finish-reason by the completions stream; keys without a kmet
   consumer yet (:supports-developer-role, ...) are inert data.
   :reasoning-content-fill is the one kmet-only key: the placeholder an
   endpoint needs on assistant messages without same-model reasoning (pi has
   no equivalent), defaulting to the empty string. Routing maps
   (openRouterRouting/vercelGatewayRouting) and feature keys with no kmet
   implementation (zaiToolStream, deferredToolsMode,
   supportsOpenAIGrammarTools) are not ported. Unlike pi, URL matching is
   case-insensitive across all providers."
  [{:keys [id base-url] :as model}]
  (let [provider (some-> (models/provider-family model) name)
        url (str/lower-case (or base-url ""))
        id-str (or id "")
        zai? (zai-provider? provider url)
        together? (together-provider? provider url)
        moonshot? (moonshot-provider? provider url)
        openrouter? (openrouter-provider? provider url)
        cf-workers? (cloudflare-workers-provider? provider url)
        cf-gateway? (cloudflare-gateway-provider? provider url)
        nvidia? (nvidia-provider? provider url)
        ant-ling? (ant-ling-provider? provider url)
        deepseek? (deepseek-provider? provider url)
        grok? (grok-provider? provider url)
        non-standard? (or nvidia?
                          (= provider "cerebras")
                          (str/includes? url "cerebras.ai")
                          (= provider "xai")
                          (str/includes? url "api.x.ai")
                          together?
                          (str/includes? url "chutes.ai")
                          deepseek?
                          zai?
                          moonshot?
                          (= provider "opencode")
                          (str/includes? url "opencode.ai")
                          cf-workers?
                          cf-gateway?
                          ant-ling?)
        use-max-tokens? (or (str/includes? url "chutes.ai")
                            deepseek?
                            moonshot?
                            cf-gateway?
                            together?
                            nvidia?
                            ant-ling?
                            zai?)
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
     ;; kmet extension (no pi counterpart): the value a provider needs on
     ;; every assistant message when no same-model reasoning exists. "" for
     ;; the DeepSeek/opencode-go fill; endpoints that turn the field into a
     ;; thinking block reject an empty one, so their models set a non-empty
     ;; placeholder here.
     :reasoning-content-fill ""
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
     :supports-strict-mode false
     :cache-control-format (when (and openrouter? (str/starts-with? id-str "anthropic/"))
                             :anthropic)
     ;; pi detectCompat: OpenRouter asks for the session-affinity headers
     :send-session-affinity-headers openrouter?
     :session-affinity-format (if openrouter? :openrouter :openai)
     :supports-long-cache-retention (not (or together? cf-workers? cf-gateway? nvidia? ant-ling?))}))

(defn resolved-openai-compat
  "pi getCompat: the model's explicit :compat overlaid on
   detect-openai-compat — an explicit non-nil value wins, nil/absent falls
   back to the detected default (pi's ?? merge). Every explicit key survives,
   including the ones detection does not name (a per-wire key such as
   :supports-explicit-prompt-cache-mode or :supports-cache-control-on-tools),
   so a wire can read its own compat key from here without a detection entry.
   The single source of compat truth for the wire request builders.

   One deliberate exception to pure key-wise merge: an explicit
   :thinking-format :deepseek pins a model to the DeepSeek thinking-mode
   protocol, which requires reasoning_content on every assistant message once
   tools are carried. Proxies detect-openai-compat can't recognize (custom
   provider ids / non-deepseek.com base urls) would otherwise leave the
   requirement undetected and 400 mid-session — so the requirement is derived
   from the format unless the compat sets the flag explicitly."
  [model]
  (let [explicit (:compat model)
        merged (merge (detect-openai-compat model)
                      (into {} (remove (comp nil? val)) explicit))]
    (cond-> merged
      (and (= :deepseek (:thinking-format merged))
           (nil? (get explicit :requires-reasoning-content-on-assistant-messages)))
      (assoc :requires-reasoning-content-on-assistant-messages true))))

;; ─── Prompt caching (pi: openai-prompt-cache.ts) ──────────────────────────

(defn clamp-prompt-cache-key
  "pi clampOpenAIPromptCacheKey: session ids longer than 64 characters are
   truncated (OpenAI's prompt_cache_key limit)."
  [session-id]
  (when session-id
    (let [chars (count session-id)]
      (if (<= chars 64) session-id (subs session-id 0 64)))))

(defn cache-control-marker
  "pi getCacheControl / getCompatCacheControl: the cache_control marker for
   CACHE-RETENTION (:short by default, :long or :none) — nil for :none,
   otherwise {:type \"ephemeral\"}, plus :ttl \"1h\" on :long when
   LONG-SUPPORTED?. The two wires differ in the gate (anthropic compat vs
   :cache-control-format), not in the marker."
  [cache-retention long-supported?]
  (let [retention (or cache-retention :short)]
    (when (not= :none retention)
      (cond-> {:type "ephemeral"}
        (and (= :long retention) long-supported?)
        (assoc :ttl "1h")))))

(defn openai-compat-cache-control
  "pi getCompatCacheControl (openai-completions.ts:1062): the marker an
   OpenAI-compatible endpoint takes when its compat declares
   :cache-control-format :anthropic — nil otherwise."
  [compat cache-retention]
  (when (= :anthropic (:cache-control-format compat))
    (cache-control-marker cache-retention (:supports-long-cache-retention compat))))

(def ^:private instruction-roles
  "Roles carrying the system prompt on an openai wire (pi marks the first of
   them: system or developer)."
  #{"system" "developer"})

(def ^:private conversation-roles
  "Roles carrying conversation history on an openai wire (pi marks the newest
   of them)."
  #{"user" "assistant" "tool"})

(defn- mark-text-content
  "pi addCacheControlToTextContent: CONTENT with CACHE-CONTROL on its text — a
   plain string is wrapped as a one-block text vector (the marker rides a
   block), an array's last text block is marked in place. nil when CONTENT
   cannot carry a marker (empty content, or no text block)."
  [content cache-control]
  (cond
    (string? content)
    (when (seq content)
      [{:type "text" :text content :cache_control cache-control}])

    (vector? content)
    (when-let [i (last (keep-indexed #(when (= "text" (:type %2)) %1) content))]
      (assoc-in content [i :cache_control] cache-control))))

(defn- mark-instruction-message
  "pi addCacheControlToSystemPrompt: mark the first system/developer message,
   whether or not it can carry a marker (pi stops at the first one either
   way)."
  [messages cache-control]
  (if-let [i (first (keep-indexed #(when (contains? instruction-roles (:role %2)) %1)
                                  messages))]
    (let [message (nth messages i)
          content (mark-text-content (:content message) cache-control)]
      (if content
        (assoc messages i (assoc message :content content))
        messages))
    messages))

(defn- mark-last-conversation-message
  "pi addCacheControlToLastConversationMessage: mark the newest message of a
   conversation role that can carry a marker."
  [messages cache-control]
  (loop [i (dec (count messages))]
    (if (neg? i)
      messages
      (let [{:keys [role content] :as m} (nth messages i)
            marked (when (contains? conversation-roles role)
                     (mark-text-content content cache-control))]
        (if marked
          (assoc messages i (assoc m :content marked))
          (recur (dec i)))))))

(defn mark-last-tool
  "pi addCacheControlToLastTool: place CACHE-CONTROL on the last tool
   declaration — both wires' tool objects carry the marker at the top level
   (the anthropic wire's schema and the openai function wrapper). TOOLS
   passes through when there are none."
  [tools cache-control]
  (if (seq tools)
    (update tools (dec (count tools)) assoc :cache_control cache-control)
    tools))

;; ─── Declared-set changes (pi: utils/transcript.ts tool state) ────────────
;; A recorded change is a transcript message at the point it happened: the app
;; projects each session :tool-change entry to a :role :tool-change marker
;; carrying :added/:removed, so the provider sees it where it was recorded.
;; These helpers are the provider-neutral half: which leading markers a
;; top-level tools array already holds, what the folded tool state is, and
;; where the request's top-level boundary falls (pi: resolveTranscriptTools).

(defn tool-change-messages
  "The declared-set markers in MESSAGES, in transcript order."
  [messages]
  (filterv #(= :tool-change (:role %)) messages))

(defn without-tool-change-messages
  "MESSAGES without the declared-set markers — what a wire that cannot express
   a mid-conversation change sends (it carries the current list in the
   request's top-level tools instead)."
  [messages]
  (filterv #(not= :tool-change (:role %)) messages))

(defn- fold-tool-changes
  "The declared tool state after the first N markers: removals first, then
   additions, so a redefinition wins (pi: getCurrentTools). REMOVED carries
   names."
  [changes n]
  (into (sorted-map)
        (reduce (fn [state {:keys [added removed]}]
                  (into (reduce dissoc state removed)
                        (map (juxt :name identity) added)))
                {}
                (take n changes))))

(defn- last-non-additive-change
  "Index of the last marker a top-level array cannot express — a removal or a
   same-name redeclaration (pi: findLastNonAdditiveToolChange); -1 when there
   is none."
  [changes]
  (loop [i 0 declared #{} last -1 cs (seq changes)]
    (if-let [{:keys [added removed]} (first cs)]
      (let [non-additive? (or (seq removed)
                              (some #(contains? declared (:name %)) added))]
        (recur (inc i)
               (into declared (map :name) added)
               (if non-additive? i last)
               (next cs)))
      last)))

(defn resolve-request-tools
  "The request's tool state over the wire MESSAGE stream: the declared-set
   markers in transcript order, the first being the initial declaration
   (pi: resolveTranscriptTools). SUPPORT is :none, :additions or :changes.
   Returns {:request-tools [...] :folded n}: the first FOLDED markers are
   already in the top-level array, the rest must be sent in place. No marker
   (a legacy session, a direct API caller) falls back to TOOLS."
  [messages support tools]
  (let [changes (tool-change-messages messages)]
    (if (empty? changes)
      {:request-tools tools :folded 0}
      (let [folded (case support
                     :changes 0
                     :additions (max 1 (inc (last-non-additive-change changes)))
                     (count changes))]
        {:request-tools (vals (fold-tool-changes changes folded))
         :folded folded}))))

(defn apply-anthropic-cache-control
  "pi applyAnthropicCacheControl (openai-completions.ts:1074-1103): the
   anthropic-format OpenAI-compatible endpoints (OpenRouter anthropic/*)
   accept the same cache_control breakpoints — the system prompt's text, the
   last tool, and the last user/assistant/tool message. Returns
   {:messages m :tools t}."
  [messages tools cache-control]
  {:messages (-> messages
                 (mark-instruction-message cache-control)
                 (mark-last-conversation-message cache-control))
   :tools (mark-last-tool tools cache-control)})

(defn- openai-thinking-params-openrouter [model effort]
  (cond-> {}
    effort (assoc :reasoning {:effort (models/effort-value model effort)})
    (and (nil? effort) (not (models/off-explicitly-null? model)))
    (assoc :reasoning {:effort (or (get-in model [:thinking-level-map :off]) "none")})))

(defn- openai-thinking-params-deepseek [model effort effort?]
  (cond-> {}
    effort (assoc :thinking {:type "enabled"})
    (and (nil? effort) (not (models/off-explicitly-null? model)))
    (assoc :thinking {:type "disabled"})
    (and effort effort?)
    (assoc :reasoning_effort (models/effort-value model effort))))

(defn- openai-thinking-params-qwen [model effort effort?]
  (cond-> {:enable_thinking (boolean effort)}
    (and effort effort?) (assoc :reasoning_effort (models/effort-value model effort))))

(defn- openai-thinking-params-zai [model effort effort?]
  (cond-> {:thinking (if effort
                       {:type "enabled" :clear_thinking false}
                       {:type "disabled"})}
    (and effort effort?) (assoc :reasoning_effort (models/effort-value model effort))))

(defn- openai-thinking-params-together [model effort effort?]
  (cond-> {:reasoning {:enabled (boolean effort)}}
    (and effort effort?) (assoc :reasoning_effort (models/effort-value model effort))))

(defn- openai-thinking-params-qwen-chat-template [effort]
  {:chat_template_kwargs {:enable_thinking (boolean effort)
                          :preserve_thinking true}})

(defn- openai-thinking-params-chat-template [model effort compat]
  (cond-> {}
    (seq (:chat-template-kwargs compat))
    (assoc :chat_template_kwargs
           (resolve-template-values model effort (:chat-template-kwargs compat)))))

(defn- openai-thinking-params-baseten [model effort effort? compat]
  (cond-> {}
    (seq (:chat-template-args compat))
    (assoc :chat_template_args
           (resolve-template-values model effort (:chat-template-args compat)))
    (and effort effort?)
    (assoc :reasoning_effort (models/effort-value model effort))
    (and (nil? effort) (string? (get-in model [:thinking-level-map :off])))
    (assoc :reasoning_effort (get-in model [:thinking-level-map :off]))))

(defn- openai-thinking-params-ant-ling []
  {})

(defn- openai-thinking-params-string-thinking [model effort]
  (cond-> {}
    effort (assoc :thinking (models/effort-value model effort))
    (and (nil? effort) (not (models/off-explicitly-null? model)))
    (assoc :thinking (or (get-in model [:thinking-level-map :off]) "none"))))

(defn- openai-thinking-params-generic [model effort effort?]
  (cond
    (and effort effort?) {:reasoning_effort (models/effort-value model effort)}
    (and (nil? effort) effort?)
    (let [off (get-in model [:thinking-level-map :off])]
      (if (string? off) {:reasoning_effort off} {}))
    :else {}))

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
    (if (not reasoning?)
      {}
      (case fmt
        :openrouter (openai-thinking-params-openrouter model effort)
        :deepseek (openai-thinking-params-deepseek model effort effort?)
        :qwen (openai-thinking-params-qwen model effort effort?)
        :zai (openai-thinking-params-zai model effort effort?)
        :together (openai-thinking-params-together model effort effort?)
        :qwen-chat-template (openai-thinking-params-qwen-chat-template effort)
        :chat-template (openai-thinking-params-chat-template model effort compat)
        :baseten (openai-thinking-params-baseten model effort effort? compat)
        :ant-ling (openai-thinking-params-ant-ling)
        :string-thinking (openai-thinking-params-string-thinking model effort)
        (openai-thinking-params-generic model effort effort?)))))

(def min-answer-tokens
  "Answer tokens kept outside the anthropic thinking budget (pi
   adjustMaxTokensForThinking's MIN_ANSWER_TOKENS)."
  1024)

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
      (let [level (models/clamp-reasoning effort)
            budget (get models/thinking-budgets level 0)
            max-tokens (or (:max-tokens model) 4096)
            budget (min budget (max 0 (- max-tokens min-answer-tokens)))]
        {:thinking {:type "enabled" :budget_tokens budget :display "summarized"}
         :max-tokens max-tokens}))
    (models/off-explicitly-null? model) nil
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
