(ns kmet.ai.api.openai-responses
  "OpenAI Responses wire API (pi: api/openai-responses.ts + openai-responses-shared.ts)."
  (:require
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [clojure.string :as str]
   [kmet.ai.constrained-sampling :as cs]
   [kmet.ai.api.shared :refer [bash-execution-text content-text effort-value endpoint-url image-block? off-explicitly-null? apply-before-provider-request-hook request-headers responses-events-handler transport-error-message]]))

(defn normalize-id-part
  "pi normalizeIdPart: sanitize a tool-call id to [a-zA-Z0-9_-], cap at 64
   chars, strip trailing underscores; nil for a blank id (omitted from the
   wire, like pi's `id: undefined`)."
  [s]
  (when (seq s)
    (let [sanitized (str/replace s #"[^a-zA-Z0-9_-]" "_")]
      (-> (if (> (count sanitized) 64) (subs sanitized 0 64) sanitized)
          (str/replace #"_+$" "")))))

(def ^:private responses-tool-call-providers
  "Providers whose Responses API can replay a function-call item id. Other
   providers omit the id when switching into Responses, matching pi's
   cross-provider conversion path."
  #{:openai :openai-codex :opencode})

(defn- responses-function-call-item-id
  "Normalize a replayed Responses function-call item id. The Responses API
   requires ids to begin with `fc_`; ids from another wire API are omitted
   instead of being fabricated for a provider that cannot pair them."
  [provider item-id]
  (when (and (contains? responses-tool-call-providers provider)
             (seq item-id))
    (let [normalized (normalize-id-part item-id)]
      (when (seq normalized)
        (if (str/starts-with? normalized "fc_")
          normalized
          (normalize-id-part (str "fc_" normalized)))))))

(defn responses-user-content
  "User content blocks → responses input content (input_text / input_image
   items; pi convertResponsesMessages)."
  [content]
  (if (some image-block? content)
    (into []
          (for [b content]
            (if (image-block? b)
              {:type "input_image" :detail "auto"
               :image_url (str "data:" (:mime-type b) ";base64," (:data b))}
              {:type "input_text" :text (or (:text b) "")})))
    [{:type "input_text" :text (content-text content)}]))

(defn responses-tool-result-output
  "pi convertToolResultOutput: the joined tool-result text, plus
   input_image items when the model accepts images; otherwise the plain text
   with pi's image/output placeholders."
  [model m]
  (let [text (or (-> m :content first :content) "")
        images (:images m)]
    (if (and (seq images) (some #{:image} (:input model)))
      (into (if (seq text) [{:type "input_text" :text text}] [])
            (for [i images]
              {:type "input_image" :detail "auto"
               :image_url (str "data:" (:mime-type i) ";base64," (:data i))}))
      (cond
        (seq text) text
        (seq images) "(see attached image)"
        :else "(no tool output)"))))

(defn responses-message-items
  "One agent message → responses input items (possibly none). pi
   convertResponsesMessages, simplified: no thinking-signature replay (kmet
   sessions don't persist signatures — omitted reasoning is legal with
   store: false) and no deferred/grammar tools (kmet always sends its tools
   up front). Assistant text blocks become message items (stable fallback
   ids), tool calls become function_call items whose call_id matches the
   tool result's; cross-provider ids (no `|` separator) replay as call_id
   only, like pi's different-model path (id omitted avoids the fc_/rs_
   pairing validation)."
  [model m msg-idx]
  (case (name (:role m))
    "bash"
    (when-not (:exclude-from-context? m)
      [{:role "user"
        :content [{:type "input_text" :text (bash-execution-text m)}]}])

    "tool"
    [{:type "function_call_output"
      :call_id (normalize-id-part (first (str/split (-> m :content first :tool_use_id) #"\|")))
      :output (responses-tool-result-output model m)}]

    "assistant"
    (let [blocks (into []
                       (concat
                        (for [[i b] (map-indexed vector (:content m))
                              :when (= :text (:type b))]
                          {:type "message" :role "assistant"
                           :content [{:type "output_text" :text (or (:text b) "")
                                      :annotations []}]
                           :status "completed"
                           :id (str "msg_pi_" msg-idx (when (pos? i) (str "_" i)))})
                        (for [tc (:tool-calls m)]
                          (let [[call-id item-id] (str/split (str (:id tc)) #"\|")
                                item-id (responses-function-call-item-id
                                         (:provider model) item-id)]
                            (cond-> {:type "function_call"
                                     :call_id (normalize-id-part call-id)
                                     :name (:name tc)
                                     :arguments (json/generate-string (:arguments tc))}
                              item-id (assoc :id item-id))))))]
      (when (seq blocks) blocks))

    ;; custom messages (pi: convertToLlm custom→user)
    "custom"
    [{:role "user"
      :content [{:type "input_text" :text (content-text (:content m))}]}]

    [{:role "user" :content (responses-user-content (:content m))}]))

(defn responses-messages
  "Map agent messages to OpenAI Responses input items (pi
   convertResponsesMessages): the system prompt becomes the first developer
   message (system when the model doesn't support the developer role) —
   unless INCLUDE-SYSTEM? is false, when the caller carries the system
   prompt itself (codex: the instructions field) — followed by the
   converted messages."
  [model messages & [include-system?]]
  (let [system (first (for [m messages :when (= :system (:role m))]
                        (content-text (:content m))))
        role (if (and (:reasoning model)
                      (not= false (:supports-developer-role (:compat model))))
               "developer" "system")]
    (loop [msgs (remove #(= :system (:role %)) messages)
           idx 0
           acc []]
      (if-let [m (first msgs)]
        (recur (rest msgs) (inc idx) (into acc (responses-message-items model m idx)))
        (into (if (and system (not= false include-system?))
                [{:role role :content system}]
                [])
              acc)))))

(defn responses-tools
  "pi convertResponsesTools: flat function tools with the JSON schema. A
   tool whose :constrained-sampling resolves strict gets strictified
   parameters and :strict true; other tools get :strict false (pi: strict
   ?? defaultStrict — emitted whenever the provider accepts strict
   schemas)."
  [tools strict?]
  (mapv (fn [tool]
          (let [strict (cs/resolve-json-schema-strict-sampling tool strict?)]
            (cond-> {:type "function"
                     :name (:name tool)
                     :description (:description tool)
                     :parameters (cs/get-json-schema-tool-parameters tool strict)}
              strict? (assoc :strict (true? strict)))))
        tools))

(defn copilot-vision-input?
  "pi hasCopilotVisionInput: any user or tool-result message carries image
   content (Copilot-Vision-Request header)."
  [messages]
  (boolean
   (some (fn [m]
           (case (name (:role m))
             "user" (some image-block? (:content m))
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

(defn responses-affinity-headers
  "pi createClient session-affinity headers from the session id (prompt
   caching): openai format sends session_id + x-client-request-id,
   openai-nosession (opencode zen) only x-client-request-id, openrouter
   x-session-id. A nil session-id (or cache-retention :none) → none."
  [model session-id]
  (when session-id
    (let [fmt (or (:session-affinity-format (:compat model))
                  (if (or (= :openrouter (:provider model))
                          (str/includes? (:base-url model) "openrouter.ai"))
                    :openrouter
                    :openai))]
      (case fmt
        :openrouter {"x-session-id" session-id}
        :openai {"session_id" session-id "x-client-request-id" session-id}
        ;; :openai-nosession — no session_id header (opencode zen)
        {"x-client-request-id" session-id}))))

(defn clamp-prompt-cache-key
  "pi clampOpenAIPromptCacheKey: session ids longer than 64 chars are
   truncated (OpenAI's prompt_cache_key limit)."
  [session-id]
  (when session-id
    (let [chars (count session-id)]
      (if (<= chars 64) session-id (subs session-id 0 64)))))

(defn responses-payload
  "Request body for an openai-responses request (pi buildParams):
   model/input/stream/store, tools, thinking (reasoning: {effort, summary}
   for a clamped level; reasoning: {effort: off-map-value} when thinking is
   disabled but the model allows it — :xhigh/:max and gpt-5* off:null
   models omit the param and think by default), include for the reasoning
   content (effort requests + xai always), max_output_tokens (min 16),
   prompt-cache params (key from the session id, a 24h retention when long
   and supported, explicit mode when caching is off on a cache-enabled
   model), then :sampling-params merged last so their keys win."
  [model-record effort messages tools model-id cache-retention session-id]
  (let [effort-reasoning (when (and (:reasoning model-record) effort)
                           {:effort (effort-value model-record effort)
                            :summary "auto"})
        off-reasoning (when (and (:reasoning model-record) (nil? effort)
                                 (not (off-explicitly-null? model-record)))
                        {:effort (or (get-in model-record [:thinking-level-map :off]) "none")})
        reasoning (or effort-reasoning off-reasoning)
        retention (or cache-retention :short)
        caching? (not= :none retention)
        compat (:compat model-record)]
    (cond-> {:model model-id
             :input (responses-messages model-record messages)
             :stream true
             :store false}
      (seq tools) (assoc :tools (responses-tools tools
                                                 (:supports-strict-mode compat)))
      (:max-tokens model-record) (assoc :max_output_tokens (max (:max-tokens model-record) 16))
      reasoning (assoc :reasoning reasoning)
      (or effort-reasoning
          (and (= :xai (:provider model-record)) (:reasoning model-record)))
      (assoc :include ["reasoning.encrypted_content"])
      (and caching? session-id) (assoc :prompt_cache_key (clamp-prompt-cache-key session-id))
      ;; pi getCompat: supportsLongCacheRetention defaults to true when absent
      (and (= :long retention) (not= false (:supports-long-cache-retention compat)))
      (assoc :prompt_cache_retention "24h")
      (and (= :none retention) (:supports-explicit-prompt-cache-mode compat))
      (assoc :prompt_cache_options {:mode "explicit"})
      (seq (:sampling-params model-record)) (merge (:sampling-params model-record)))))

(defn responses-dynamic-headers
  "The per-request headers beyond the base: session-affinity (gated on
   caching like pi's cacheSessionId — :none sends neither the cache key nor
   the affinity headers) + the per-request Copilot dynamic headers."
  [model-record session-id retention messages]
  (merge (when (not= :none retention)
           (responses-affinity-headers model-record session-id))
         (when (= :github-copilot (:provider model-record))
           (copilot-dynamic-headers messages))))

(defn responses-request-headers
  "The full header map for a responses request (pi createClient + the
   request-headers merge): responses-dynamic-headers, then request-headers'
   standard merge so the request's own headers win collisions."
  [model-record provider-record api-key session-id retention messages]
  (request-headers
   (merge (responses-dynamic-headers model-record session-id retention messages)
          {"Authorization" (str "Bearer " api-key)
           "Content-Type" "application/json"})
   model-record provider-record api-key session-id))

(defn responses-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms total-timeout-ms session-id cache-retention on-error]
    :as opts}]
  (future
    ;; the URL interpolation (cloudflare placeholders) can throw for a
    ;; missing env var — report it via on-error, never hang the caller
    (try
      (let [model-id (or (:model opts) (:id model-record))
            url (or base-url (endpoint-url :openai-responses (:base-url model-record) model-id))
            retention (or cache-retention :short)
            payload (apply-before-provider-request-hook
                     (responses-payload model-record effort messages tools model-id
                                        retention session-id))
            headers (responses-request-headers model-record provider-record api-key
                                               session-id retention messages)
            response (ai-http/request url
                                      {:headers headers
                                       :body (json/generate-string payload)
                                       :as :stream
                                         ;; Total request deadline (pi: SDK timeoutMs ??
                                         ;; httpIdleTimeoutMs); explicit total wins, else
                                         ;; the idle timeout (compaction/summarization), nil
                                         ;; when both disabled.
                                       :timeout (when-let [t (or (when (and total-timeout-ms (pos? total-timeout-ms))
                                                                   total-timeout-ms)
                                                                 (when (pos? (or idle-timeout-ms 0))
                                                                   idle-timeout-ms))]
                                                  t)}
                                      signal)]
        (let [[dispatch finalize] (responses-events-handler opts model-record)]
          (sse/process-responses-stream response
                                        dispatch
                                        signal
                                        idle-timeout-ms
                                        (fn [] (ai-http/abort! response)))
          ;; the stream is fully consumed — a trailing usage chunk (if any)
          ;; is dispatched; emit the deferred terminal done now
          (finalize (some-> signal deref)))
        (ai-http/close! response))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))
