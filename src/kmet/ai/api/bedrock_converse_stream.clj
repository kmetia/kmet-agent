(ns kmet.ai.api.bedrock-converse-stream
  "AWS Bedrock ConverseStream wire API (pi: api/bedrock-converse-stream.ts)."
  (:require
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [clojure.string :as str]
   [kmet.libs.aws-sigv4 :as aws-sigv4]
   [kmet.ai.constrained-sampling :as cs]
   [kmet.ai.api.shared :refer [anthropic-adaptive-effort bash-execution-text content-text getenv image-block? min-answer-tokens apply-before-provider-request-hook request-headers responses-events-handler thinking-budgets transport-error-message]]))

(defn bedrock-is-claude?
  "pi isAnthropicClaudeModel: id/name mention Anthropic Claude (also matches
   application inference profiles whose ARNs don't contain the name)."
  [model]
  (let [id (str/lower-case (:id model))
        name (str/lower-case (or (:name model) ""))]
    (or (str/includes? id "anthropic.claude")
        (str/includes? id "anthropic/claude")
        (str/includes? name "anthropic.claude")
        (str/includes? name "anthropic/claude")
        (str/includes? name "claude"))))

(defn bedrock-model-candidates
  "id and normalized name (pi getModelMatchCandidates — application
   inference profiles may carry the model name only in :name)."
  [model]
  (let [id (str/lower-case (:id model))
        name (str/lower-case (or (:name model) ""))]
    (into [id] (when (seq name) [(str/replace name #"[\s_.:]+" "-")]))))

(defn bedrock-supports-adaptive-thinking?
  "pi supportsAdaptiveThinking: Opus 4.6+/Sonnet 4.6/Fable 5 (id AND name)."
  [model]
  (let [adaptive? #(or (str/includes? % "opus-4-6") (str/includes? % "opus-4-7")
                       (str/includes? % "opus-4-8") (str/includes? % "opus-5")
                       (str/includes? % "sonnet-4-6") (str/includes? % "sonnet-5")
                       (str/includes? % "fable-5"))]
    (boolean (some adaptive? (bedrock-model-candidates model)))))

(defn bedrock-supports-prompt-caching?
  "pi supportsPromptCaching: Claude 3.5 Haiku / 3.7 Sonnet / 4.x / 5 (or
   AWS_BEDROCK_FORCE_CACHE=1 for application inference profiles)."
  [model]
  (let [candidates (bedrock-model-candidates model)]
    (cond
      (not (some #(str/includes? % "claude") candidates))
      (= "1" (getenv "AWS_BEDROCK_FORCE_CACHE"))
      (some #(or (str/includes? % "fable-5") (str/includes? % "opus-5")
                 (str/includes? % "sonnet-5"))
            candidates)
      true
      (some #(str/includes? % "-4-") candidates)
      true
      (some #(str/includes? % "claude-3-7-sonnet") candidates)
      true
      (some #(str/includes? % "claude-3-5-haiku") candidates)
      true
      :else false)))

(defn bedrock-image-block
  "pi createImageBlock: the Converse image block (source.bytes carries the
   base64 string — the wire format's bytes field is base64-encoded text)."
  [mime-type data]
  {:image {:format (case mime-type
                     "image/jpeg" "jpeg"
                     "image/jpg" "jpeg"
                     "image/png" "png"
                     "image/gif" "gif"
                     "image/webp" "webp"
                     (throw (ex-info (str "Unknown image type: " mime-type)
                                     {:type :bedrock-image-type})))
           :source {:bytes data}}})

(def bedrock-empty-text-placeholder "<empty>")

(defn bedrock-normalize-tool-call-id
  "Pi normalizeToolCallId: Bedrock tool-use ids allow only alphanumeric
   characters, underscores, and hyphens, up to 64 characters."
  [id]
  (let [sanitized (str/replace (or id "") #"[^a-zA-Z0-9_-]" "_")]
    (subs sanitized 0 (min 64 (count sanitized)))))

(defn bedrock-text-block
  "A non-blank text block; nil when the text is blank (pi
   createNonBlankTextBlock)."
  [text]
  (when (seq (str/trim (or text "")))
    {:text text}))

(defn bedrock-tool-result-content
  "Tool result content blocks (pi convertToolResultContent): text blocks
   plus image blocks; an empty result degrades to the <empty> placeholder."
  [text images]
  (let [blocks (into (if (seq (str/trim (or text ""))) [{:text text}] [])
                     (for [i images]
                       (bedrock-image-block (:mime-type i) (:data i))))]
    (if (seq blocks) blocks [{:text bedrock-empty-text-placeholder}])))

(defn bedrock-messages
  "ConverseStream messages (pi convertMessages): user content with
   text/image blocks (blank text degrades to the <empty> placeholder),
   assistant content with text/toolUse/reasoningContent blocks, consecutive
   tool results merged into one user message with toolResult blocks. A
   cache point is appended to the last user message for cache-capable
   Claude models when caching is enabled.

   Returns [messages system-blocks] — the system prompt (pi
   buildSystemPrompt) carries its own cache point."
  ([messages model cache-retention]
   (bedrock-messages messages model cache-retention nil))
  ([messages model cache-retention {:keys [provider]}]
   (let [system (first (for [m messages
                             :when (= :system (:role m))]
                         (content-text (:content m))))
         caching? (and (not= :none cache-retention)
                       (bedrock-supports-prompt-caching? model))
         cache-block {:cachePoint (cond-> {:type "default"}
                                    (= :long cache-retention) (assoc :ttl "1h"))}
         system-blocks (when (seq system)
                         (cond-> [{:text system}]
                           caching? (conj cache-block)))
         result (loop [msgs (remove #(= :system (:role %)) messages)
                       out []]
                  (if-let [m (first msgs)]
                    (let [role (name (:role m))]
                      (cond
                        (= role "bash")
                        (if (:exclude-from-context? m)
                          (recur (rest msgs) out)
                          (recur (rest msgs)
                                 (conj out {:role "user"
                                            :content [{:text (bash-execution-text m)}]})))
                        (= role "tool")
                       ;; merge consecutive tool results into one user message
                        (let [tool-results (take-while #(= :tool (:role %)) msgs)
                              blocks (mapv (fn [tr]
                                             {:toolResult {:toolUseId (bedrock-normalize-tool-call-id
                                                                       (-> tr :content first :tool_use_id))
                                                           :content (bedrock-tool-result-content
                                                                     (or (-> tr :content first :content) "")
                                                                     (:images tr))
                                                           :status (if (:is-error tr) "error" "success")}})
                                           tool-results)]
                          (recur (drop (count tool-results) msgs)
                                 (conj out {:role "user" :content blocks})))
                        (= role "assistant")
                        (let [content-blocks (into []
                                                   (concat
                                                    (keep (fn [b]
                                                            (when (= :text (:type b))
                                                              (bedrock-text-block (:text b))))
                                                          (:content m))
                                                    (for [tc (:tool-calls m)]
                                                      {:toolUse {:toolUseId (bedrock-normalize-tool-call-id (:id tc))
                                                                 :name (:name tc)
                                                                 :input (:arguments tc {})}})
                                                   ;; pi supportsThinkingSignature: only Claude
                                                   ;; accepts the signature field. Signed
                                                   ;; same-model reasoning replays inside
                                                   ;; reasoningContent with the signature;
                                                   ;; unsigned or cross-provider reasoning falls
                                                   ;; back to plain text for Claude (Bedrock
                                                   ;; rejects a reasoningContent block without a
                                                   ;; signature); non-Claude models take
                                                   ;; reasoningContent without a signature (pi)
                                                    (when-let [th (not-empty (str/trim (or (:thinking m) "")))]
                                                      (let [same-model? (and (= (:provider m) provider)
                                                                             (= (:model m) (:id model)))
                                                            sig (:thinking-signature m)]
                                                        (if (bedrock-is-claude? model)
                                                          (if (and same-model? (seq sig))
                                                            [{:reasoningContent {:reasoningText {:text th
                                                                                                 :signature sig}}}]
                                                            [{:text th}])
                                                          [{:reasoningContent {:reasoningText {:text th}}}])))))]
                          (if (seq content-blocks)
                            (recur (rest msgs) (conj out {:role "assistant" :content content-blocks}))
                            (recur (rest msgs) out)))
                        :else
                       ;; user
                        (let [content (:content m)
                              blocks (if (string? content)
                                       [{:text content}]
                                       (let [blocks (into []
                                                          (keep (fn [b]
                                                                  (cond
                                                                    (= :text (:type b)) (bedrock-text-block (:text b))
                                                                    (image-block? b) (bedrock-image-block (:mime-type b) (:data b))
                                                                    :else nil)))
                                                          content)]
                                         (if (seq blocks) blocks [{:text bedrock-empty-text-placeholder}])))]
                          (recur (rest msgs) (conj out {:role "user" :content blocks})))))
                    out))]
     [(cond-> result
        (and caching? (seq result)
             (= "user" (get-in result [(dec (count result)) :role])))
        (update (dec (count result)) update :content conj cache-block))
      system-blocks])))

(defn bedrock-tool-config
  "ConverseStream toolConfig (pi convertToolConfig): toolSpec blocks with
   the JSON input schema; strict-resolving tools get the strictified schema
   and :strict true; toolChoice auto when tools are present."
  [tools supports-strict?]
  (when (seq tools)
    {:tools (mapv (fn [tool]
                    (let [strict (cs/resolve-json-schema-strict-sampling tool supports-strict?)]
                      (cond-> {:toolSpec {:name (:name tool)
                                          :description (:description tool)
                                          :inputSchema {:json (cs/get-json-schema-tool-parameters tool strict)}}}
                        (true? strict) (assoc-in [:toolSpec :strict] true))))
                  tools)
     :toolChoice {:auto {}}}))

(defn bedrock-additional-fields
  "pi buildAdditionalModelRequestFields: the thinking config for Claude
   models — adaptive (opus-4.6+/sonnet-4.6+/fable-5) or budget-based with
   the interleaved-thinking beta; non-Claude models get no thinking.
   EFFORT is the clamped level, nil when off."
  [model effort]
  (when (and (:reasoning model) effort (bedrock-is-claude? model))
    (let [display "summarized"]
      (if (bedrock-supports-adaptive-thinking? model)
        {:thinking {:type "adaptive" :display display}
         :output_config {:effort (anthropic-adaptive-effort model effort)}}
        (let [level (if (contains? #{:xhigh :max} effort) :high effort)
              budget (min (get thinking-budgets level 0)
                          (max 0 (- (or (:max-tokens model) 4096) min-answer-tokens)))]
          {:thinking {:type "enabled" :budget_tokens budget :display display}
           :anthropic_beta ["interleaved-thinking-2025-05-14"]})))))

(defn bedrock-endpoint-url
  "pi: the ConverseStream URL. Standard bedrock-runtime endpoints are used
   as-is unless a region or ambient AWS_PROFILE overrides them (then the
   regional endpoint wins); custom endpoints always win."
  [model-base-url model-id]
  (let [endpoint-region (when-let [host (some-> (java.net.URI. model-base-url) .getHost)]
                          (second (re-find #"(?i)^bedrock-runtime(?:-fips)?\.([a-z0-9-]+)\.amazonaws\.com(?:\.cn)?$"
                                           host)))
        configured-region (or (getenv "AWS_REGION") (getenv "AWS_DEFAULT_REGION"))
        ambient-profile? (boolean (getenv "AWS_PROFILE"))
        explicit? (or (nil? endpoint-region)
                      (and (nil? configured-region) (not ambient-profile?)))
        region (if explicit?
                 (or endpoint-region configured-region "us-east-1")
                 (or configured-region "us-east-1"))]
    {:url (if explicit?
            (str model-base-url "/model/" model-id "/converse-stream")
            (str "https://bedrock-runtime." region ".amazonaws.com/model/" model-id "/converse-stream"))
     :region region}))

(defn bedrock-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms total-timeout-ms session-id cache-retention on-error]
    :as opts}]
  (future
    (try
      (let [model-id (or (:model opts) (:id model-record))
            retention (or cache-retention :short)
            {:keys [url region]} (if base-url
                                   {:url (str base-url "/model/" model-id "/converse-stream")
                                    :region (or (getenv "AWS_REGION") "us-east-1")}
                                   (bedrock-endpoint-url (:base-url model-record) model-id))
            bearer (or api-key (getenv "AWS_BEARER_TOKEN_BEDROCK"))
            skip-auth? (= "1" (getenv "AWS_BEDROCK_SKIP_AUTH"))
            creds (when-not (or bearer skip-auth?)
                    (aws-sigv4/resolve-credentials))]
        (if-not (or bearer creds skip-auth?)
          (when on-error
            (on-error (str "No API key for amazon-bedrock. Set AWS_ACCESS_KEY_ID + "
                           "AWS_SECRET_ACCESS_KEY, AWS_PROFILE, AWS_BEARER_TOKEN_BEDROCK, "
                           "or configure AWS credentials.")))
          (let [additional (bedrock-additional-fields model-record effort)
                [msgs system-blocks] (bedrock-messages messages model-record retention
                                                       {:provider (:id provider-record)})
                payload (json/generate-string
                         (apply-before-provider-request-hook
                          (cond-> {:modelId model-id
                                   :messages msgs
                                   :inferenceConfig (cond-> {}
                                                      (and (bedrock-is-claude? model-record)
                                                           (:max-tokens model-record))
                                                      (assoc :maxTokens (:max-tokens model-record)))}
                            (seq system-blocks) (assoc :system system-blocks)
                            (seq tools) (assoc :toolConfig (bedrock-tool-config tools
                                                                                (:supports-strict-mode (:compat model-record))))
                            (seq additional) (assoc :additionalModelRequestFields additional))))
                sha (aws-sigv4/sha256-hex payload)
                ;; the request's own headers (attribution + configured) first,
                ;; then the SigV4/bearer headers — AWS requires content-type
                ;; in the signature and reserved headers (authorization,
                ;; x-amz-*) must never be overridden by caller headers (pi
                ;; addCustomHeadersMiddleware skips them)
                headers (merge (request-headers {"Content-Type" "application/json"}
                                                model-record provider-record api-key session-id)
                               (if (and bearer (not skip-auth?))
                                 {"Authorization" (str "Bearer " bearer)}
                                 (aws-sigv4/sign-request {:method "POST"
                                                          :url url
                                                          :region region
                                                          :service "bedrock"
                                                          :access-key (or (:access-key creds) "dummy-access-key")
                                                          :secret-key (or (:secret-key creds) "dummy-secret-key")
                                                          :session-token (:session-token creds)
                                                          :headers {"Content-Type" "application/json"}
                                                          :payload-hash sha})))
                response (ai-http/request url
                                          {:headers headers
                                           :body payload
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
              (sse/process-bedrock-stream response
                                          dispatch
                                          signal
                                          idle-timeout-ms
                                          (fn [] (ai-http/abort! response)))
              ;; the stream is fully consumed — the usage metadata frame (if
              ;; any) is dispatched; emit the deferred terminal done now
              (finalize (some-> signal deref)))
            (ai-http/close! response))))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))
