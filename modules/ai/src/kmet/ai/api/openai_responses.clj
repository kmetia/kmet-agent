(ns kmet.ai.api.openai-responses
  "OpenAI Responses wire API (pi: api/openai-responses.ts + openai-responses-shared.ts)."
  (:require
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [clojure.string :as str]
   [kmet.ai.constrained-sampling :as cs]
   [kmet.ai.models :as models]
   [kmet.libs.http :refer [transport-error-message]]
   [kmet.ai.hash :as hash]
   [kmet.ai.message :refer [content-text image-block?]]
   [kmet.ai.api.shared :as shared :refer [clamp-prompt-cache-key copilot-dynamic-headers endpoint-url on-payload request-headers resolved-openai-compat responses-events-handler effective-total-timeout-ms headers-timeout-ms]]))

(declare responses-tools)

(defn normalize-id-part
  "pi normalizeIdPart: sanitize a tool-call id to [a-zA-Z0-9_-], cap at 64
   chars, strip trailing underscores; nil for a blank id (omitted from the
   wire, like pi's `id: undefined`)."
  [s]
  (when (seq s)
    (let [sanitized (str/replace s #"[^a-zA-Z0-9_-]" "_")]
      (-> (if (> (count sanitized) 64) (subs sanitized 0 64) sanitized)
          (str/replace #"_+$" "")))))

(defn- responses-instruction-role
  "pi's instructionRole: developer when the model reasons and accepts the
   developer role, otherwise system."
  [model]
  (if (and (:reasoning model)
           (not= false (:supports-developer-role (:compat model))))
    "developer" "system"))

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
                                         (models/provider-family model) item-id)]
                            (cond-> {:type "function_call"
                                     :call_id (normalize-id-part call-id)
                                     :name (:name tc)
                                     :arguments (json/generate-string (:arguments tc))}
                              item-id (assoc :id item-id))))))]
      (when (seq blocks) blocks))

    [{:role "user" :content (responses-user-content (:content m))}]))

(defn responses-tool-support
  "pi getResponsesToolSupport: how this model expresses a declared-set change
   after the first request — `:changes` (mid-conversation developer messages
   plus additional_tools items), `:additions` (synthetic tool search) or
   `:none` (the current list at the top level)."
  [compat]
  (cond
    (and (:supports-mid-convo-system-messages compat)
         (:supports-additional-tools compat)) :changes
    (:supports-tool-search compat) :additions
    :else :none))

(defn- incremental-tools-notice
  "Precedes every additional_tools item after the first, so a model does not
   read the latest item as the complete set; still-unavailable tools are
   restated (pi: renderIncrementalToolsNotice)."
  [unavailable]
  (let [notice (str "This is an incremental tools update. Previously declared tools remain"
                    " available unless explicitly marked unavailable. A tool declared again"
                    " here is available again, and its latest definition replaces the earlier one.")]
    (if (seq unavailable)
      (str notice "\nStill unavailable: " (str/join ", " unavailable) ".")
      notice)))

(defn- tool-removal-notice
  "pi: renderToolRemovalNotice — a removal cannot be native on this wire, so
   it is announced as text."
  [names]
  (str "The following tools are no longer available. Do not call them:\n"
       (str/join "\n" (map #(str "- " %) names))))

(defn- additional-tools-item
  [tools strict?]
  {:type "additional_tools"
   :role "developer"
   :tools (responses-tools tools strict?)})

(defn- responses-change-items
  "The input items for the changes after the initial declaration: an optional
   incremental notice before each later addition, the additional_tools item,
   and the removal text a wire that cannot remove natively needs (pi
   convertResponsesMessages)."
  [deltas declared? strict? role]
  (loop [ds deltas
         declared? declared?
         unavailable []
         acc []]
    (if-let [{:keys [added removed]} (first ds)]
      (let [redefined (into #{} (map :name) added)
            gone (vec (remove redefined removed))
            ;; pi announces an addition, not a bare removal
            notice (when (and declared? (seq added))
                     {:role role
                      :content (incremental-tools-notice unavailable)})
            additional (when (seq added)
                         (additional-tools-item added strict?))
            removal-text (when (seq gone)
                           {:role role
                            :content (tool-removal-notice gone)})]
        (recur (next ds)
               (or declared? (boolean additional))
               (into unavailable gone)
               (cond-> acc
                 notice (conj notice)
                 additional (conj additional)
                 removal-text (conj removal-text))))
      acc)))

(defn- tool-search-pair
  "pi's synthetic load for an addition-only transport: a completed client-side
   tool_search call and its output carrying the definitions with
   defer_loading."
  [tools strict? seed]
  (let [names (mapv :name tools)
        call-id (str "kmet_tool_load_" (hash/short-hash (str seed ":" (str/join "," names))))]
    [{:type "tool_search_call"
      :call_id call-id
      :execution "client"
      :status "completed"
      :arguments {:query (str/join " " names) :limit (count names)}}
     {:type "tool_search_output"
      :call_id call-id
      :execution "client"
      :status "completed"
      :tools (responses-tools tools strict? true)}]))

(defn responses-messages
  "Map agent messages to OpenAI Responses input items (pi
   convertResponsesMessages): the system prompt becomes the first developer
   message (system when the model doesn't support the developer role) —
   unless INCLUDE-SYSTEM? is false, when the caller carries the system
   prompt itself (codex: the instructions field) — followed by the
   converted messages. Declared-set deltas are added by the caller
   (`responses-payload`), not encoded as transcript messages."
  ([model messages]
   (responses-messages model messages true))
  ([model messages include-system?]
   (let [system (first (for [m messages :when (= :system (:role m))]
                         (content-text (:content m))))
         ;; raw compat, not resolved-openai-compat: pi's responses compat
         ;; defaults supportsDeveloperRole to true, the completions detection
         ;; derives it per family — resolving here would change the role
         role (responses-instruction-role model)]
     (loop [msgs (remove #(= :system (:role %)) messages)
            idx 0
            acc []]
       (if-let [m (first msgs)]
         (recur (rest msgs) (inc idx) (into acc (responses-message-items model m idx)))
         (into (if (and system (not= false include-system?))
                 [{:role role :content system}]
                 [])
               acc))))))

(defn responses-tools
  "pi convertResponsesTools: flat function tools with the JSON schema. A
   tool whose :constrained-sampling resolves strict gets strictified
   parameters and :strict true; other tools get :strict false (pi: strict
   ?? defaultStrict — emitted whenever the provider accepts strict
   schemas). DEFER-LOADING? marks a tool search result's tools
   (`defer_loading: true`)."
  ([tools strict?]
   (responses-tools tools strict? false))
  ([tools strict? defer-loading?]
   (mapv (fn [tool]
           (let [strict (cs/resolve-json-schema-strict-sampling tool strict?)]
             (cond-> {:type "function"
                      :name (:name tool)
                      :description (:description tool)
                      :parameters (cs/get-json-schema-tool-parameters tool strict)}
               strict? (assoc :strict (true? strict))
               defer-loading? (assoc :defer_loading true))))
         tools)))

(defn responses-affinity-headers
  "pi createClient session-affinity headers from the session id (prompt
   caching): the responses wire's own set — x-session-id for the openrouter
   format, otherwise session_id + x-client-request-id (the completions wire
   adds x-session-affinity; this one never does). The format comes from the
   resolved compat (pi getCompat :session-affinity-format ?? detection), so an
   explicit models.edn value wins over OpenRouter detection. A nil session-id
   → none."
  [model session-id]
  (when session-id
    (case (:session-affinity-format (resolved-openai-compat model))
      :openrouter {"x-session-id" session-id}
      :openai {"session_id" session-id "x-client-request-id" session-id}
      ;; :openai-nosession (opencode zen) — no session_id header
      {"x-client-request-id" session-id})))

(defn responses-payload
  "Request body for an openai-responses request (pi buildParams):
   model/input/stream/store, tools, thinking (reasoning: {effort, summary}
   for a clamped level; reasoning: {effort: off-map-value} when thinking is
   disabled but the model allows it — :xhigh/:max and gpt-5* off:null
   models omit the param and think by default), include for the reasoning
   content (effort requests + xai always), max_output_tokens (min 16),
   prompt-cache params (key from the session id; a 24h retention when long is
   supported, unless the model's compat takes explicit-mode options — then the
   options carry the window instead: explicit mode for :none, a 30m ttl for
   long), then :sampling-params merged last so their keys win. TOOL-CHANGES is
   the recorded declared-set history: a mid-conversation-capable model puts the
   initial declaration in an additional_tools item at the head and anchors
   later deltas; otherwise the current list goes top-level."
  ([model-record effort messages tools model-id cache-retention session-id]
   (responses-payload model-record effort messages tools model-id cache-retention session-id nil))
  ([model-record effort messages tools model-id cache-retention session-id tool-changes]
   (let [effort-reasoning (when (and (:reasoning model-record) effort)
                            {:effort (models/effort-value model-record effort)
                             :summary "auto"})
         off-reasoning (when (and (:reasoning model-record) (nil? effort)
                                  (not (models/off-explicitly-null? model-record)))
                         {:effort (or (get-in model-record [:thinking-level-map :off]) "none")})
         reasoning (or effort-reasoning off-reasoning)
         retention (or cache-retention :short)
         caching? (not= :none retention)
         ;; the shared compat resolution (pi getCompat): detection defaults,
         ;; every explicit non-nil key survives
         compat (resolved-openai-compat model-record)
         explicit? (:supports-explicit-prompt-cache-mode compat)
         long? (and (= :long retention) (:supports-long-cache-retention compat))
         ;; pi getPromptCacheOptions: on an explicit-mode model the options carry
         ;; the cache window and the retention param is omitted instead
         cache-options (when explicit?
                         (cond
                           (= :none retention) {:mode "explicit"}
                           long? {:ttl "30m"}))
         tool-support (responses-tool-support compat)
         strict? (:supports-strict-mode compat)
         {:keys [request-tools anchored changes]}
         (shared/resolve-request-tools tool-changes tool-support tools)
         base-input (responses-messages model-record messages)
         instruction-role (responses-instruction-role model-record)
         head-item (when (and (= tool-support :changes)
                              (seq (:added (first changes))))
                     (additional-tools-item (:added (first changes)) strict?))
         delta-items (when (= tool-support :changes)
                       (responses-change-items (rest anchored) (boolean head-item)
                                               strict? instruction-role))
         anchored-items (when (= tool-support :additions)
                          (into []
                                (mapcat (fn [change]
                                          (when (seq (:added change))
                                            (tool-search-pair (:added change) strict?
                                                              (map :name (:added change)))))
                                        anchored)))
         input (into [] (concat (when head-item [head-item])
                                base-input delta-items anchored-items))]
     (cond-> {:model model-id
              :input input
              :stream true
              :store false}
       (seq request-tools) (assoc :tools (responses-tools request-tools strict?))
       (:max-tokens model-record) (assoc :max_output_tokens (max (:max-tokens model-record) 16))
       reasoning (assoc :reasoning reasoning)
       (or effort-reasoning
           (and (= :xai (models/provider-family model-record)) (:reasoning model-record)))
       (assoc :include ["reasoning.encrypted_content"])
       (and caching? session-id) (assoc :prompt_cache_key (clamp-prompt-cache-key session-id))
       (and long? (not explicit?)) (assoc :prompt_cache_retention "24h")
       cache-options (assoc :prompt_cache_options cache-options)
       (seq (:sampling-params model-record)) (merge (:sampling-params model-record))))))

(defn responses-dynamic-headers
  "The per-request headers beyond the base: session-affinity (gated on
   caching like pi's cacheSessionId — :none sends neither the cache key nor
   the affinity headers) + the per-request Copilot dynamic headers."
  [model-record session-id retention messages]
  (merge (when (not= :none retention)
           (responses-affinity-headers model-record session-id))
         (when (= :github-copilot (models/provider-family model-record))
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
            payload (on-payload opts
                                (responses-payload model-record effort messages tools model-id
                                                   retention session-id (:tool-changes opts)))
            headers (responses-request-headers model-record provider-record api-key
                                               session-id retention messages)
            response (ai-http/request url
                                      {:headers headers
                                       :body (json/generate-string payload)
                                       :as :stream
                                         ;; Shared resolution keeps nil (follow idle)
                                         ;; distinct from an explicit disabled total.
                                       :timeout (effective-total-timeout-ms total-timeout-ms idle-timeout-ms)
                                       :headers-timeout (headers-timeout-ms idle-timeout-ms)}
                                      signal (:hooks opts))]
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
