(ns kmet.ai.api.openai-completions
  "OpenAI Completions wire API (pi: api/openai-completions.ts)."
  (:require
   [clojure.string :as str]
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [kmet.ai.models :as models]
   [kmet.libs.http :refer [transport-error-message]]
   [kmet.ai.api.shared :refer [apply-anthropic-cache-control clamp-prompt-cache-key copilot-dynamic-headers endpoint-url max-tokens-key openai-compat-cache-control openai-messages openai-messages-with-reasoning openai-thinking-params resolved-openai-compat on-payload request-headers responses-events-handler tool->openai-schema effective-total-timeout-ms headers-timeout-ms]]))

(defn- api-openai-url?
  "pi buildParams' baseUrl.includes(\"api.openai.com\"): the one host that
   takes the cache key under short retention. Lowercased like the rest of the
   compat detection."
  [model-record]
  (str/includes? (str/lower-case (or (:base-url model-record) "")) "api.openai.com"))

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
   URL-resolved (resolved-openai-compat, pi getCompat)."
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
        messages (if requires-reasoning?
                   (openai-messages-with-reasoning messages model-record model-id
                                                   (:reasoning-content-fill compat))
                   (openai-messages messages model-record model-id))
        tools (when (seq tools)
                (mapv #(tool->openai-schema %
                                            (not= false (:supports-strict-mode compat)))
                      tools))
        cache-control (openai-compat-cache-control compat retention)
        ;; pi applyAnthropicCacheControl runs after the messages and tools
        ;; vectors are built and before :sampling-params is merged
        marked (if cache-control
                 (apply-anthropic-cache-control messages tools cache-control)
                 {:messages messages :tools tools})
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
                                   (fn [] (ai-http/abort! response)))
        ;; stream fully consumed — the trailing usage chunk (if any) was
        ;; dispatched; emit the deferred terminal done now
        (finalize (some-> signal deref))
        (ai-http/close! response))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))
