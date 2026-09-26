(ns kmet.ai.api.anthropic-messages
  "Anthropic messages wire API (pi: api/anthropic-messages.ts)."
  (:require
   [kmet.ai.auth :as auth]
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [clojure.string :as str]
   [kmet.ai.api.shared :refer [anthropic-thinking bash-execution-text endpoint-url image-block? apply-before-provider-request-hook request-headers tool->anthropic-schema transport-error-message usage-with-cost]]))

(def default-anthropic-version "2023-06-01")

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
   current request's provider id and model id — and :allow-empty-signature
   (pi compat.allowEmptySignature). An assistant message whose provenance
   matches replays its reasoning as a leading thinking block carrying the
   captured signature; without a signature the reasoning degrades to plain
   text (or an empty-signature thinking block on allow-listed compat models),
   mirroring pi convertMessages. Messages without provenance (legacy
   sessions) degrade like unsigned ones.

   The :system message is NOT converted — Anthropic carries the system
   prompt in the request-level `system` field (pi params.system), never as a
   message with role \"system\" (the API rejects it)."
  ([messages]
   (anthropic-messages messages nil))
  ([messages {:keys [provider model allow-empty-signature]}]
   (into []
         (keep (fn [m]
                 (let [role (name (:role m))]
                   (case role
                     "system" nil
                     "bash"
                     (when-not (:exclude-from-context? m)
                       {:role "user" :content (bash-execution-text m)})
                     "tool"
                     (let [tool-use-id (anthropic-normalize-tool-call-id
                                        (-> m :content first :tool_use_id))]
                       {:role "user"
                        :content [(cond-> {:type "tool_result"
                                           :tool_use_id tool-use-id
                                           :content (anthropic-tool-result-content m)}
                                    (:is-error m) (assoc :is_error true))]})
                     ;; custom messages (pi: convertToLlm custom→user)
                     "custom"
                     (let [content (anthropic-content (:content m))]
                       (when-not (blank-content? content)
                         {:role "user" :content content}))
                     ;; pi convertMessages: blank text blocks are dropped from
                     ;; outgoing requests and a message left without sendable
                     ;; content is skipped entirely — Anthropic rejects empty
                     ;; content arrays, so a recorded empty completion must not
                     ;; poison every later turn with a 400.
                     (let [raw (anthropic-content (:content m))
                           same-model? (and (= (:provider m) provider)
                                            (= (:model m) model))
                           sig (:thinking-signature m)
                           ;; pi: signed same-model reasoning replays as a
                           ;; leading thinking block (required before tool_use
                           ;; when thinking is on); unsigned or cross-provider
                           ;; reasoning degrades to plain text — unless the
                           ;; model is allow-listed for empty signatures
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
                                     ;; string content keeps its wire shape unless
                                     ;; reasoning blocks precede it
                                     (seq thinking-blocks)
                                     (vec (concat thinking-blocks
                                                  (when (seq raw)
                                                    [{:type "text" :text raw}])))
                                     :else raw)]
                       (when-not (blank-content? content)
                         {:role role :content content})))))
               messages))))

(defn anthropic-auth-headers
  "Base auth headers for an anthropic-messages request (pi anthropic provider
   resolve): the resolved provider auth — when ANTHROPIC_AUTH_TOKEN wins the
   resolution order (no credential, no configured key) → Authorization:
   Bearer, else x-api-key with the resolved API key."
  [provider api-key]
  (if-let [t (:bearer (auth/resolve-provider-auth provider))]
    {"Authorization" (str "Bearer " t)}
    {"x-api-key" api-key}))

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

(defn anthropic-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms total-timeout-ms session-id
           on-text on-thinking on-signature on-tool-call on-done on-error on-usage]
    :as opts}]
  (future
    (let [model-id (or (:model opts) (:id model-record))
          thinking (anthropic-thinking model-record effort)
          system (system-prompt-text messages)
          payload (apply-before-provider-request-hook
                   (cond-> {:model model-id
                            :max_tokens (:max-tokens thinking (or (:max-tokens model-record) 4096))
                            ;; replay context: signatures echo only when message provenance matches
                            :messages (anthropic-messages
                                       messages
                                       {:provider (:id provider-record)
                                        :model model-id
                                        :allow-empty-signature
                                        (-> model-record :compat :allow-empty-signature)})
                            :stream true}
                     (seq system) (assoc :system [{:type "text" :text system}])
                     (seq tools) (assoc :tools (mapv #(tool->anthropic-schema %
                                                                              (:supports-strict-tools (:compat model-record)))
                                                     tools))
                     (:thinking thinking) (assoc :thinking (:thinking thinking))
                    ;; adaptive thinking (pi forceAdaptiveThinking): the
                    ;; output_config effort rides alongside the thinking block
                     (:output_config thinking) (assoc :output_config (:output_config thinking))))]
      (try
        (let [response (ai-http/request (or base-url (endpoint-url :anthropic-messages (:base-url model-record) model-id))
                                        {:headers (request-headers
                                                   (merge {"anthropic-version" default-anthropic-version
                                                           "Content-Type" "application/json"}
                                                          (anthropic-auth-headers (:id provider-record) api-key))
                                                   model-record provider-record api-key
                                                   session-id)
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
          (sse/process-anthropic-stream response
                                        (fn [event]
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
                                            :usage (when on-usage (on-usage (usage-with-cost model-record (:usage event))))
                                            :error (when on-error (on-error (:message event)))
                                            nil))
                                        signal
                                        idle-timeout-ms
                                        (fn [] (ai-http/abort! response)))
          (ai-http/close! response))
        (catch Exception e
          (when on-error (on-error (transport-error-message e))))))))
