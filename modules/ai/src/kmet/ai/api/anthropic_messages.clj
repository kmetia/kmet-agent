(ns kmet.ai.api.anthropic-messages
  "Anthropic messages wire API (pi: api/anthropic-messages.ts)."
  (:require
   [kmet.ai.auth :as auth]
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [clojure.string :as str]
   [kmet.ai.models :as models]
   [kmet.libs.http :refer [transport-error-message]]
   [kmet.ai.message :refer [image-block?]]
   [kmet.ai.api.shared :as shared :refer [anthropic-thinking copilot-dynamic-headers endpoint-url on-payload request-headers tool->anthropic-schema effective-total-timeout-ms headers-timeout-ms usage-with-cost]]))

(def default-anthropic-version "2023-06-01")

(def ^:private inline-tools-beta "inline-tools-2026-09-15")

(def ^:private deferred-tool-placeholder
  "Stable placeholder declared whenever native tool changes are in use.
   Anthropic adds hidden prompt scaffolding for mid-conversation changes;
   declaring this from the first request keeps that scaffolding in the cached
   prefix, so the first change does not pay a full miss (pi:
   DEFERRED_TOOL_PLACEHOLDER). Never activated; the model cannot see it."
  {:name "__kmet_deferred_placeholder__"
   :description "Reserved placeholder. Never available. Never call this."
   :input_schema {:type "object" :properties {} :required []}
   :defer_loading true})

(defn anthropic-normalize-tool-call-id
  "Pi normalizeToolCallId: Anthropic tool-use ids allow only alphanumeric
   characters, underscores, and hyphens, up to 64 characters."
  [id]
  (let [sanitized (str/replace (or id "") #"[^a-zA-Z0-9_-]" "_")]
    (subs sanitized 0 (min 64 (count sanitized)))))

(defn anthropic-content-text
  "Extract plain text from Anthropic message content.
   Returns the content as-is if it is a string, otherwise joins text blocks."
  [content]
  (if (string? content)
    content
    (str/join (for [b content
                    :when (or (= (:type b) :text)
                              (= (:type b) "text"))]
                (:text b)))))

(defn anthropic-content
  "Convert kmet content blocks to Anthropic content. Returns the plain text
   string when there are no image blocks (backward compat); with image blocks
   returns an array of text/image blocks (Anthropic vision format)."
  [content]
  (if (some image-block? content)
    (into []
          (for [b content]
            (if (image-block? b)
              {:type "image"
               :source {:type "base64"
                        :media_type (:mime-type b)
                        :data (:data b)}}
              {:type "text" :text (or (:text b) "")})))
    (anthropic-content-text content)))

(defn anthropic-tool-result-content
  "Anthropic tool result content: the text from the tool_result block, plus
   image blocks for any :images on the message."
  [m]
  (let [text (-> m :content first :content)
        images (:images m)]
    (if (seq images)
      (into [{:type "text" :text (or text "")}]
            (for [i images]
              {:type "image"
               :source {:type "base64"
                        :media_type (:mime-type i)
                        :data (:data i)}}))
      text)))

(defn anthropic-compat
  "pi getAnthropicCompat (anthropic-messages.ts:224-236): the anthropic wire's
   compat keys under pi's ?? defaults — an explicit value in the model's
   :compat wins, an absent or nil one falls back to the detected default.
   OpenRouter detection reads the provider family id and the base URL, so an
   account alias keeps its base provider's detection. :allow-empty-signature
   and :supports-strict-tools default to false, as in pi."
  [{:keys [compat] :as model-record}]
  (let [openrouter? (shared/openrouter-provider?
                     (some-> (models/provider-family model-record) name)
                     (:base-url model-record))
        with-default (fn [k default]
                       (let [v (get compat k)]
                         (if (nil? v) default v)))]
    {:supports-cache-control-on-tools
     (with-default :supports-cache-control-on-tools true)
     :supports-long-cache-retention
     (with-default :supports-long-cache-retention true)
     :send-session-affinity-headers
     (with-default :send-session-affinity-headers openrouter?)
     :session-affinity-format
     (with-default :session-affinity-format (when openrouter? :openrouter))
     :supports-strict-tools (with-default :supports-strict-tools false)
     :allow-empty-signature (with-default :allow-empty-signature false)
     :supports-mid-convo-system-messages
     (with-default :supports-mid-convo-system-messages false)
     :supports-mid-convo-tool-changes
     (with-default :supports-mid-convo-tool-changes false)}))

(defn anthropic-cache-control
  "pi getCacheControl (anthropic-messages.ts:83-98): the cache_control marker
   for CACHE-RETENTION (:short by default, :long, or :none) — nil for :none,
   otherwise {:type \"ephemeral\"}, plus :ttl \"1h\" on :long when the model
   supports long retention. pi sends no extended-cache-ttl beta header with
   it, and neither do we."
  [model-record cache-retention]
  (shared/cache-control-marker
   cache-retention
   (:supports-long-cache-retention (anthropic-compat model-record))))

(def ^:private cache-control-block-types
  "Anthropic content block types that can carry cache_control on the last user
   or system message (pi convertMessages)."
  #{"text" "image" "tool_result" "tool_addition" "tool_removal"})

(defn- mark-last-user-block
  "Put CACHE-CONTROL on the last user or system message's last content block —
   the conversation-history breakpoint (pi convertMessages). A later system
   message is the tool-change anchor, so its last block takes the marker too. A
   string content is wrapped into a one-block text vector, since the marker
   rides a block. Messages ending in an assistant turn, or in a block type
   Anthropic does not take a marker on, are returned unchanged."
  [messages cache-control]
  (let [n (count messages)
        {:keys [role content]} (when (pos? n) (nth messages (dec n)))
        last-i (when (vector? content) (dec (count content)))]
    (cond
      (or (nil? cache-control) (not (contains? #{"user" "system"} role)))
      messages

      (string? content)
      (assoc messages (dec n)
             {:role role
              :content [{:type "text" :text content :cache_control cache-control}]})

      (and last-i (>= last-i 0)
           (contains? cache-control-block-types (:type (nth content last-i))))
      (update-in messages [(dec n) :content last-i] assoc :cache_control cache-control)

      :else messages)))

(defn- tool-change-blocks
  "The Anthropic content blocks for one recorded change: a removal per name
   not redefined here, then an addition carrying the definition by value.
   pi convertMessages (anthropic-messages.ts:1382-1394)."
  [change convert-definition]
  (let [added (:added change)
        redefined (into #{} (map :name) added)
        removed (remove #(contains? redefined %) (:removed change))]
    (cond-> []
      (seq removed)
      (into (map (fn [name]
                   {:type "tool_removal"
                    :tool {:type "tool_reference" :name name}})
                 removed))
      (seq added)
      (into (map (fn [declaration]
                   {:type "tool_addition"
                    :tool {:type "tool_definition"
                           :definition (convert-definition declaration)}})
                 added)))))

(defn- blank-text-block?
  "True for an Anthropic text block carrying only whitespace — pi drops such
   blocks from outgoing requests (they carry nothing and strict endpoints
   reject them)."
  [{:keys [type text]}]
  (and (= type "text") (str/blank? text)))

(defn- blank-content?
  "True when Anthropic message content carries nothing sendable: an empty or
   whitespace-only string, or a block vector whose entries are all blank
   text blocks. An empty vector counts — pi skips empty assistant messages,
   whose replay would otherwise trip Anthropic's non-empty-content rule on
   every later turn after one was recorded."
  [content]
  (if (string? content)
    (str/blank? content)
    (every? blank-text-block? content)))

(defn anthropic-messages
  "Map agent messages to Anthropic API messages.

   OPTS (optional map) carries the replay context: :provider/:model — the
   current request's provider id and model id — :allow-empty-signature
   (pi compat.allowEmptySignature), :cache-control, the marker to place on
   the last user/system block, :native-tool-changes?, whether the recorded
   declared-set deltas become inline tool_addition/tool_removal blocks, and
   :tool-change-deltas, those deltas (the initial declaration is the request's
   top-level tools array, so it is not repeated here). An assistant message
   whose provenance matches replays its reasoning as a leading thinking block
   carrying the captured signature; without a signature the reasoning degrades
   to plain text (or an empty-signature thinking block on allow-listed compat
   models), mirroring pi convertMessages. Messages without provenance (legacy
   sessions) degrade like unsigned ones.

   The :system message is NOT converted — Anthropic carries the system
   prompt in the request-level `system` field (pi params.system), never as a
   message with role \"system\" (the API rejects it). The deltas ride as one
   trailing system message: a change is inserted after the conversation that
   existed when it was recorded, so this request's prefix — and the cache
   breakpoint on it — survives."
  ([messages]
   (anthropic-messages messages nil))
  ([messages {:keys [provider model allow-empty-signature cache-control
                     native-tool-changes? tool-change-deltas convert-definition]}]
   (let [converted
         (into []
               (keep (fn [m]
                       (let [role (name (:role m))]
                         (case role
                           "system" nil
                           "tool"
                           (let [tool-use-id (anthropic-normalize-tool-call-id
                                              (-> m :content first :tool_use_id))]
                             {:role "user"
                              :content [(cond-> {:type "tool_result"
                                                 :tool_use_id tool-use-id
                                                 :content (anthropic-tool-result-content m)}
                                          (:is-error m) (assoc :is_error true))]})
                           ;; pi convertMessages: blank text blocks are
                           ;; dropped from outgoing requests and a message left
                           ;; without sendable content is skipped entirely —
                           ;; Anthropic rejects empty content arrays, so a
                           ;; recorded empty completion must not poison every
                           ;; later turn with a 400.
                           (let [raw (anthropic-content (:content m))
                                 same-model? (and (= (:provider m) provider)
                                                  (= (:model m) model))
                                 sig (:thinking-signature m)
                                 ;; pi: signed same-model reasoning replays as a
                                 ;; leading thinking block (required before
                                 ;; tool_use when thinking is on); unsigned or
                                 ;; cross-provider reasoning degrades to plain
                                 ;; text — unless the model is allow-listed for
                                 ;; empty signatures
                                 thinking-blocks (when-let [th (not-empty (str/trim (or (:thinking m) "")))]
                                                   [(cond
                                                      (and same-model? (seq sig))
                                                      {:type "thinking" :thinking th :signature sig}
                                                      allow-empty-signature
                                                      {:type "thinking" :thinking th :signature ""}
                                                      :else
                                                      {:type "text" :text th})])
                                 tool-uses (when (and (= role "assistant") (:tool-calls m))
                                             (mapv (fn [tc]
                                                     {:type "tool_use"
                                                      :id (anthropic-normalize-tool-call-id (:id tc))
                                                      :name (:name tc)
                                                      :input (:arguments tc)})
                                                   (:tool-calls m)))
                                 content (cond
                                           (seq tool-uses)
                                           (vec (concat thinking-blocks
                                                        (cond
                                                          (string? raw) []
                                                          :else (remove blank-text-block? raw))
                                                        tool-uses))
                                           (vector? raw)
                                           (vec (concat thinking-blocks
                                                        (remove blank-text-block? raw)))
                                           ;; string content keeps its wire
                                           ;; shape unless reasoning blocks
                                           ;; precede it
                                           (seq thinking-blocks)
                                           (vec (concat thinking-blocks
                                                        (when (seq raw)
                                                          [{:type "text" :text raw}])))
                                           :else raw)]
                             (when-not (blank-content? content)
                               {:role role :content content}))))))
               messages)
         delta-blocks (when (and native-tool-changes? (seq tool-change-deltas))
                        (into []
                              (mapcat #(tool-change-blocks % convert-definition)
                                      tool-change-deltas)))
         converted (cond-> converted
                     (seq delta-blocks)
                     (conj {:role "system" :content delta-blocks}))]
     (mark-last-user-block converted cache-control))))

(defn anthropic-auth-headers
  "Base auth headers for an anthropic-messages request (pi anthropic provider
   resolve): a bearer token — ANTHROPIC_AUTH_TOKEN winning the resolution, or
   an oauth-derived credential (:oauth, a subscription token) → Authorization:
   Bearer; otherwise x-api-key with the resolved API key. Copilot's
   /v1/messages is bearer-only, which is why the oauth marker, not the
   provider id, decides."
  [provider api-key]
  (let [resolved (auth/resolve-provider-auth provider)
        bearer (or (:bearer resolved)
                   (when (:oauth resolved) (:api-key resolved)))]
    (if bearer
      {"Authorization" (str "Bearer " bearer)}
      {"x-api-key" api-key})))

(defn- system-prompt-text
  "Extract the system prompt from a kmet message vector. kmet prepends the
   system prompt as a :role :system message (loop.clj call-llm); Anthropic
   carries it in the request-level `system` field instead (pi params.system),
   so it is pulled out here and dropped from the message list."
  [messages]
  (some (fn [m]
          (when (= :system (:role m))
            (let [content (:content m)]
              (if (string? content)
                content
                (or (-> content first :text) "")))))
        messages))

(defn- initial-tools
  "The tools the request's top-level array carries: the recorded initial
   declaration when the session has a history, else the current list."
  [tool-changes tools]
  (if tool-changes (or (:initial tool-changes) []) tools))

(defn- native-tool-changes?
  "Whether COMPAT supports inline changes and INITIAL-TOOLS can anchor the
   placeholder — Anthropic rejects a list where every tool is deferred."
  [compat initial-tools]
  (boolean (and (:supports-mid-convo-system-messages compat)
                (:supports-mid-convo-tool-changes compat)
                (seq initial-tools))))

(defn anthropic-payload
  "Request body for an anthropic-messages request (pi buildParams,
   anthropic-messages.ts:1165-1266): model/max_tokens/messages/stream, the
   system block, tools, thinking and output_config, and the cache breakpoints
   Anthropic caches at — the system block, the last tool (only when the model
   supports a marker there) and the last user block. Anthropic honours at most
   four breakpoints per request and three are placed here: a fourth is a
   deliberate budget change, not a spare slot."
  ([model-record effort messages tools model-id cache-retention]
   (anthropic-payload model-record effort messages tools model-id cache-retention nil))
  ([model-record effort messages tools model-id cache-retention tool-changes]
   (let [thinking (anthropic-thinking model-record effort)
         system (system-prompt-text messages)
         compat (anthropic-compat model-record)
         cache-control (anthropic-cache-control model-record cache-retention)
         tool-cache-control (when (:supports-cache-control-on-tools compat)
                              cache-control)
         initial (initial-tools tool-changes tools)
         deltas (:changes tool-changes)
         native? (native-tool-changes? compat initial)
         convert-definition #(tool->anthropic-schema % (:supports-strict-tools compat))
         ;; native: the initial array stays fixed; otherwise the current list
         declared-tools (if native? initial tools)
         ;; the cache breakpoint stays on the last initial tool; the
         ;; placeholder follows it, so the scaffolding it reserves is cached
         ;; from the first request on
         schemas (cond-> (vec (or (when (seq declared-tools)
                                    (mapv convert-definition declared-tools))
                                  []))
                   tool-cache-control (shared/mark-last-tool tool-cache-control)
                   native? (conj deferred-tool-placeholder))]
     (cond-> {:model model-id
              :max_tokens (:max-tokens thinking (or (:max-tokens model-record) 4096))
             ;; replay context: signatures echo only when message provenance matches
              :messages (anthropic-messages
                         messages
                         {:provider (:provider model-record)
                          :model model-id
                          :allow-empty-signature (:allow-empty-signature compat)
                          :cache-control cache-control
                          :native-tool-changes? native?
                          :tool-change-deltas deltas
                          :convert-definition convert-definition})
              :stream true}
       (seq system)
       (assoc :system [(cond-> {:type "text" :text system}
                         cache-control (assoc :cache_control cache-control))])
       (seq schemas) (assoc :tools schemas)
       (:thinking thinking) (assoc :thinking (:thinking thinking))
      ;; adaptive thinking (pi forceAdaptiveThinking): the
      ;; output_config effort rides alongside the thinking block
       (:output_config thinking) (assoc :output_config (:output_config thinking))))))

(defn anthropic-native-tool-changes?
  "Whether a request over TOOL-CHANGES and TOOLS uses Anthropic's inline tool
   changes (see `anthropic-payload`); the request headers need the beta flag."
  [model-record tool-changes tools]
  (native-tool-changes? (anthropic-compat model-record)
                        (initial-tools tool-changes tools)))

(defn anthropic-affinity-headers
  "pi createClient session-affinity headers (prompt caching,
   anthropic-messages.ts:1067-1070): x-session-id on OpenRouter,
   x-session-affinity elsewhere, when the model's compat enables them (the
   default is OpenRouter-only) and caching is on — :none sends none, as pi's
   cacheSessionId does."
  [model-record session-id cache-retention]
  (let [{:keys [send-session-affinity-headers session-affinity-format]}
        (anthropic-compat model-record)]
    (when (and session-id
               send-session-affinity-headers
               (not= :none (or cache-retention :short)))
      {(if (= :openrouter session-affinity-format) "x-session-id" "x-session-affinity")
       session-id})))

(defn anthropic-request-headers
  "The full header map for an anthropic request: version + auth headers, the
   session-affinity headers, the per-request Copilot dynamic headers on
   github-copilot requests (pi createClient buildCopilotDynamicHeaders), then
   the request-headers merge."
  ([model-record provider-record api-key session-id messages cache-retention]
   (anthropic-request-headers model-record provider-record api-key session-id
                              messages cache-retention false))
  ([model-record provider-record api-key session-id messages cache-retention
    native-tool-changes?]
   (request-headers
    (merge {"anthropic-version" default-anthropic-version
            "Content-Type" "application/json"}
           (anthropic-auth-headers (:id provider-record) api-key)
           (anthropic-affinity-headers model-record session-id cache-retention)
           (when native-tool-changes?
             {"anthropic-beta" inline-tools-beta})
           (when (= :github-copilot (models/provider-family model-record))
             (copilot-dynamic-headers messages)))
    model-record provider-record api-key session-id)))

(defn- merge-anthropic-usage
  "pi's usage accumulation across one stream (anthropic-messages.ts:717-726,
   851-877): message_start reports the initial counts, message_delta the
   final ones. A field the delta reports (non-nil) replaces the start's; a
   field it omits is preserved — proxies drop input_tokens from the delta,
   and a gateway may report cache usage only there. Returns ACC unchanged for
   a delta with no usage."
  [acc delta]
  (if (nil? delta)
    acc
    (let [merge-fields (fn [old new ks]
                         (reduce (fn [m k]
                                   (let [v (get new k)]
                                     (if (nil? v) m (assoc m k v))))
                                 (or old {})
                                 ks))
          merged (merge-fields acc delta [:input_tokens :output_tokens
                                          :cache_read_input_tokens
                                          :cache_creation_input_tokens])]
      (if-let [cc (:cache_creation delta)]
        (assoc merged :cache_creation
               (merge-fields (:cache_creation acc) cc
                             [:ephemeral_1h_input_tokens :ephemeral_5m_input_tokens]))
        merged))))

(defn anthropic-events-handler
  "The per-event dispatch for an anthropic stream: text/thinking/signature/
   tool-call deltas, done, error, and usage. Usage is accumulated across the
   stream (merge-anthropic-usage) and reported as it changes, so the caller's
   last-wins buffer ends with the merged totals — and a stream that dies after
   message_start still leaves the counts it did report."
  [{:keys [model-record on-text on-thinking on-signature on-tool-call on-done
           on-error on-usage]}]
  (let [usage (atom nil)]
    (fn [event]
      (when-let [u (:usage event)]
        (let [merged (swap! usage merge-anthropic-usage u)]
          (when (and on-usage (seq merged))
            (on-usage (usage-with-cost model-record merged)))))
      (case (:type event)
        :text (when on-text (on-text (:content event)))
        :thinking (when on-thinking (on-thinking (:content event)))
        :signature (when on-signature (on-signature (:content event)))
        :tool-call (when on-tool-call
                     (on-tool-call {:id (:id event)
                                    :name (:name event)
                                    :arguments (:arguments event)
                                    :index (:index event)}))
        :tool-call-args (when on-tool-call
                          (on-tool-call {:id (:id event)
                                         :arguments (:arguments event)
                                         :index (:index event)}))
        :done (when on-done (on-done (:stop-reason event)))
        :error (when on-error (on-error (:message event)))
        nil))))

(defn anthropic-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms total-timeout-ms session-id cache-retention on-error]
    :as opts}]
  (future
    ;; the payload is built before the request — a conversion or hook error
    ;; must report via on-error like a transport failure (pi's streamFn throws
    ;; into its caller), never die inside the future and hang the caller
    (try
      (let [model-id (or (:model opts) (:id model-record))
            tool-changes (:tool-changes opts)
            native? (anthropic-native-tool-changes? model-record tool-changes tools)
            payload (on-payload opts (anthropic-payload model-record effort messages
                                                        tools model-id cache-retention
                                                        tool-changes))
            response (ai-http/request (or base-url (endpoint-url :anthropic-messages (:base-url model-record) model-id))
                                      {:headers (anthropic-request-headers model-record provider-record api-key session-id
                                                                           messages cache-retention native?)
                                       :body (json/generate-string payload)
                                       :as :stream
                                       ;; Shared resolution keeps nil (follow idle)
                                       ;; distinct from an explicit disabled total.
                                       :timeout (effective-total-timeout-ms total-timeout-ms idle-timeout-ms)
                                       :headers-timeout (headers-timeout-ms idle-timeout-ms)}
                                      signal (:hooks opts))]
        (sse/process-anthropic-stream response
                                      (anthropic-events-handler opts)
                                      signal
                                      idle-timeout-ms
                                      (fn [] (ai-http/abort! response)))
        (ai-http/close! response))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))
