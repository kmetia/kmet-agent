(ns kmet.ai.api.openai-completions
  "OpenAI Completions wire API (pi: api/openai-completions.ts)."
  (:require
   [clojure.string :as str]
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [kmet.ai.models :as models]
   [kmet.libs.http :refer [transport-error-message]]
   [kmet.ai.api.shared :as shared :refer [apply-anthropic-cache-control bridge-tool-results clamp-prompt-cache-key copilot-dynamic-headers endpoint-url max-tokens-key openai-compat-cache-control openai-messages openai-messages-with-reasoning openai-thinking-params resolved-openai-compat on-payload request-headers responses-events-handler tool->openai-schema effective-total-timeout-ms headers-timeout-ms]]))

(defn openai-tool-support
  "How this completions model expresses a declared-set change after the first
   request. `:additions` is pi's Kimi-shape: the model accepts a tool-bearing
   system message mid-conversation (pi getOpenAICompletionsToolSupport). The
   generated catalog already names the Kimi rows with
   :deferred-tools-mode :kimi — the same fact under the spelling kmet
   committed — so the explicit compat keys and that marker both open the
   mode."
  [compat]
  (if (or (= :kimi (:deferred-tools-mode compat))
          (and (:supports-mid-convo-system-messages compat)
               (:supports-mid-convo-tool-additions compat)))
    :additions
    :none))

(defn- api-openai-url?
  "pi buildParams' baseUrl.includes(\"api.openai.com\"): the one host that
   takes the cache key under short retention. Lowercased like the rest of the
   compat detection."
  [model-record]
  (str/includes? (str/lower-case (or (:base-url model-record) "")) "api.openai.com"))

(defn- convert-messages-with-kimi-changes
  "Convert MESSAGES in runs around the declared-set markers, inserting a
   tool-bearing system message for an anchored addition (pi:
   openai-completions convertMessages). The first FOLDED markers are already
   in the top-level array and are skipped; CONVERT maps a marker-free run to
   chat messages."
  [messages folded convert kimi-message]
  (loop [msgs messages
         marker-index 0
         segment []
         acc []]
    (if-let [m (first msgs)]
      (if (= :tool-change (:role m))
        (let [kimi (when (>= marker-index folded) (kimi-message m))]
          (recur (rest msgs) (inc marker-index) []
                 (cond-> (into acc (convert segment))
                   kimi (conj kimi))))
        (recur (rest msgs) marker-index (conj segment m) acc))
      (into acc (convert segment)))))

(defn openai-payload
  "Request body for an openai-completions request (pi buildParams):
   model/messages/stream, stream_options and store when the compat allows
   them, tools, prompt-cache params, thinking params, the max-tokens field,
   then the model's :sampling-params merged last so their keys win over the
   named request fields (pi: Object.assign(params, samplingParams) after
   everything else — samplingParams is the single source of sampling truth
   for a model). CACHE-RETENTION (:short by default, :long or :none) and
   SESSION-ID drive the prompt-cache half: a prompt_cache_key clamped to 64
   chars on api.openai.com or under long retention, a 24h retention request
   when long is supported, and the anthropic-format cache_control breakpoints
   when the compat declares :cache-control-format :anthropic. Compat is
   URL-resolved (resolved-openai-compat, pi getCompat). The declared-set
   markers in MESSAGES drive the top-level array (the folded prefix) and a
   Kimi-shape model anchors later additions as tool-bearing system messages at
   their transcript positions."
  [model-record effort messages tools model-id cache-retention session-id]
  (let [compat (resolved-openai-compat model-record)
        retention (or cache-retention :short)
        caching? (not= :none retention)
        long? (and (= :long retention) (:supports-long-cache-retention compat))
        thinking-params (openai-thinking-params model-record effort)
        max-tokens-field (max-tokens-key model-record)
        ;; pi: requiresReasoningContentOnAssistantMessages gates the
        ;; reasoning_content field (deepseek/opencode-go only; also derived
        ;; from an explicit :thinking-format :deepseek — resolved-openai-compat).
        ;; The compat's :reasoning-content-fill is the value used when the
        ;; message carries no same-model reasoning: "" for DeepSeek, a
        ;; non-empty placeholder for endpoints that turn the field into a
        ;; thinking block (Vercel stealth models).
        requires-reasoning? (:requires-reasoning-content-on-assistant-messages
                             compat)
        tool-support (openai-tool-support compat)
        {:keys [request-tools folded]} (shared/resolve-request-tools messages tool-support tools)
        strict? (not= false (:supports-strict-mode compat))
        convert (fn [ms]
                  (if requires-reasoning?
                    (openai-messages-with-reasoning ms model-record model-id
                                                    (:reasoning-content-fill compat)
                                                    compat)
                    (openai-messages ms model-record model-id compat)))
        ;; pi openai-completions convertMessages: an addition after the
        ;; top-level array's folded prefix rides at the marker's position as a
        ;; tool-bearing system message (the Kimi K3 shape), never as text
        kimi-message (fn [marker]
                       (when-let [added (seq (:added marker))]
                         {:role "system"
                          :tools (mapv #(tool->openai-schema % strict?) added)}))
        converted (if (= :additions tool-support)
                    (convert-messages-with-kimi-changes messages folded convert kimi-message)
                    (convert messages))
        ;; a marker can split a tool result from its following user message;
        ;; the bridge is idempotent, so run it over the joined list again when
        ;; the compat asks for it.
        converted (if (and (= :additions tool-support)
                           (:requires-assistant-after-tool-result compat))
                    (bridge-tool-results converted)
                    converted)
        tools (when (seq request-tools)
                (mapv #(tool->openai-schema % strict?) request-tools))
        cache-control (openai-compat-cache-control compat retention)
        ;; pi applyAnthropicCacheControl runs after the messages and tools
        ;; vectors are built and before :sampling-params is merged
        marked (if cache-control
                 (apply-anthropic-cache-control converted tools cache-control)
                 {:messages converted :tools tools})
        messages (:messages marked)
        tools (:tools marked)]
    (cond-> {:model model-id
             ;; the model record + model-id flow to the converters as the
             ;; replay target: a message's thinking echoes back as
             ;; reasoning_content only when the message was produced by this
             ;; same model (mid-session /model switches must not leak foreign
             ;; CoT into the request), and the record carries the provider
             ;; family (an account alias's base) for tool-call id shaping
             :messages messages
             :stream true}
      ;; pi buildParams: stream_options only where the wire reports usage in
      ;; the stream
      (not= false (:supports-usage-in-streaming compat))
      (assoc :stream_options {:include_usage true})
      ;; pi: opt out of server-side storage where the provider has the field
      (:supports-store compat) (assoc :store false)
      (seq tools) (assoc :tools tools)
      ;; pi: the key on api.openai.com (caching on) or whenever long
      ;; retention is supported; the session id is clamped, and no id means
      ;; no key
      (and session-id (or (and caching? (api-openai-url? model-record)) long?))
      (assoc :prompt_cache_key (clamp-prompt-cache-key session-id))
      long? (assoc :prompt_cache_retention "24h")
      (seq thinking-params) (merge thinking-params)
      (:max-tokens model-record) (assoc max-tokens-field (:max-tokens model-record))
      (seq (:sampling-params model-record)) (merge (:sampling-params model-record)))))

(defn completions-affinity-headers
  "pi createClient session-affinity headers on the completions wire
   (openai-completions.ts:759-770) — a different set from the responses
   wire's: x-session-id alone for the openrouter format, otherwise
   x-client-request-id + x-session-affinity, plus session_id for the openai
   format. Sent only when the model's compat enables them and caching is on
   (pi hands the client a nil session id under :none)."
  [model-record session-id cache-retention]
  (let [compat (resolved-openai-compat model-record)]
    (when (and session-id
               (not= :none (or cache-retention :short))
               (:send-session-affinity-headers compat))
      (case (:session-affinity-format compat)
        :openrouter {"x-session-id" session-id}
        :openai {"session_id" session-id
                 "x-client-request-id" session-id
                 "x-session-affinity" session-id}
        {"x-client-request-id" session-id
         "x-session-affinity" session-id}))))

(defn openai-request-headers
  "The full header map for an openai-completions request: Bearer auth +
   content type, the session-affinity headers (pi createClient), the
   per-request Copilot dynamic headers on github-copilot requests, then the
   request-headers merge."
  [model-record provider-record api-key session-id cache-retention messages]
  (request-headers
   (merge {"Authorization" (str "Bearer " api-key)
           "Content-Type" "application/json"}
          (completions-affinity-headers model-record session-id cache-retention)
          (when (= :github-copilot (models/provider-family model-record))
            (copilot-dynamic-headers messages)))
   model-record provider-record api-key session-id))

(defn openai-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms total-timeout-ms session-id cache-retention on-error] :as opts}]
  (future
    (try
      (let [model-id (or (:model opts) (:id model-record))
            url (or base-url (endpoint-url :openai-completions (:base-url model-record) model-id))
            retention (or cache-retention :short)
            payload (on-payload opts
                                (openai-payload model-record effort messages tools model-id
                                                retention session-id))
            response (ai-http/request url
                                      {:headers (openai-request-headers model-record provider-record api-key
                                                                        session-id retention messages)
                                       :body (json/generate-string payload)
                                       :as :stream
                                         ;; Shared resolution keeps nil (follow idle)
                                         ;; distinct from an explicit disabled total.
                                       :timeout (effective-total-timeout-ms total-timeout-ms idle-timeout-ms)
                                       :headers-timeout (headers-timeout-ms idle-timeout-ms)}
                                      signal (:hooks opts))
            ;; The shared handler defers the terminal :done until the whole
            ;; stream is consumed (see its docstring): openai-completions
            ;; sends the usage-only chunk AFTER the finish_reason chunk, so
            ;; an on-done fired at the first :done would drop the provider
            ;; usage (footer token counts / cost never update).
            [dispatch finalize] (responses-events-handler opts model-record)]
        (sse/process-openai-stream response
                                   dispatch
                                   signal
                                   idle-timeout-ms
                                   (fn [] (ai-http/abort! response))
                                   ;; pi supportsFinishReason: a provider that
                                   ;; never sends one ends its streams
                                   ;; without it on purpose
                                   {:supports-finish-reason?
                                    (not= false (:supports-finish-reason
                                                 (resolved-openai-compat model-record)))})
        ;; stream fully consumed — the trailing usage chunk (if any) was
        ;; dispatched; emit the deferred terminal done now
        (finalize (some-> signal deref))
        (ai-http/close! response))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))
