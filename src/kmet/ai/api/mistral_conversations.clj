(ns kmet.ai.api.mistral-conversations
  "Mistral Conversations wire API (pi: api/mistral-conversations.ts)."
  (:require
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [clojure.string :as str]
   [kmet.ai.hash :as hash]
   [kmet.ai.constrained-sampling :as cs]
   [kmet.ai.api.shared :refer [bash-execution-text content-text endpoint-url image-block? apply-before-provider-request-hook request-headers responses-events-handler transport-error-message]]))

(def mistral-tool-call-id-length 9)

(defn derive-mistral-tool-call-id
  "pi deriveMistralToolCallId: attempt 0 returns the id when it is already 9
   alphanumeric chars; otherwise a 9-char shortHash of the seed (attempts
   append :N to break collisions)."
  [id attempt]
  (let [normalized (str/replace id #"[^a-zA-Z0-9]" "")]
    (if (and (zero? attempt) (= mistral-tool-call-id-length (count normalized)))
      normalized
      (let [seed-base (if (seq normalized) normalized id)
            seed (if (zero? attempt) seed-base (str seed-base ":" attempt))]
        (-> (hash/short-hash seed)
            (str/replace #"[^a-zA-Z0-9]" "")
            (subs 0 mistral-tool-call-id-length))))))

(defn make-mistral-tool-call-id-normalizer
  "pi createMistralToolCallIdNormalizer: per-request map from session tool
   call ids to 9-char alphanumeric ids (collisions resolved by appending
   attempt counters)."
  []
  (let [id-map (atom {})
        reverse-map (atom {})]
    (fn [id]
      (if-let [existing (get @id-map id)]
        existing
        (loop [attempt 0]
          (let [candidate (derive-mistral-tool-call-id id attempt)
                owner (get @reverse-map candidate)]
            (if (and owner (not= owner id))
              (recur (inc attempt))
              (do (swap! id-map assoc id candidate)
                  (swap! reverse-map assoc candidate id)
                  candidate))))))))

(defn mistral-tool-result-text
  "pi buildToolResultText: the tool text with the error prefix; image-only
   results degrade to pi's placeholder strings."
  [text has-images? supports-images? is-error?]
  (let [trimmed (str/trim text)
        error-prefix (when is-error? "[tool error] ")]
    (cond
      (seq trimmed)
      (str error-prefix trimmed
           (when (and has-images? (not supports-images?))
             "\n[tool image omitted: model does not support images]"))
      has-images?
      (if supports-images?
        (if is-error? "[tool error] (see attached image)" "(see attached image)")
        (if is-error?
          "[tool error] (image omitted: model does not support images)"
          "(image omitted: model does not support images)"))
      :else (if is-error? "[tool error] (no tool output)" "(no tool output)"))))

(defn mistral-messages
  "Map agent messages to Mistral chat messages (pi toChatMessages): user
   content with text/image_url parts, assistant content with thinking +
   tool_calls (ids normalized to 9-char alphanumeric), tool results with
   toolCallId + text/image parts. Bash entries become user messages; custom
   messages map to user (pi convertToLlm)."
  [messages model normalize-id]
  (let [supports-images? (some #{:image} (:input model))]
    (into []
          (keep (fn [m]
                  (let [role (name (:role m))]
                    (case role
                      "bash"
                      (when-not (:exclude-from-context? m)
                        {:role "user" :content (bash-execution-text m)})
                      "system"
                      {:role "system" :content (content-text (:content m))}
                      "tool"
                      (let [text (or (-> m :content first :content) "")
                            images (:images m)
                            parts (into [{:type "text"
                                          :text (mistral-tool-result-text text (seq images)
                                                                          supports-images? (:is-error m))}]
                                        (when (and supports-images? (seq images))
                                          (for [i images]
                                            {:type "image_url"
                                             :image_url (str "data:" (:mime-type i) ";base64," (:data i))})))]
                        {:role "tool"
                         :tool_call_id (normalize-id (or (-> m :content first :tool_use_id) ""))
                         :name (:tool-name m)
                         :content parts})
                      "assistant"
                      (let [parts (into []
                                        (concat
                                         (when-let [t (not-empty (content-text (:content m)))]
                                           [{:type "text" :text t}])
                                         (when-let [th (not-empty (str/trim (or (:thinking m) "")))]
                                           [{:type "thinking"
                                             :thinking [{:type "text" :text th}]}])))
                            tool-calls (mapv (fn [tc]
                                               {:id (normalize-id (:id tc))
                                                :type "function"
                                                :function {:name (:name tc)
                                                           :arguments (json/generate-string (:arguments tc))}
                                                :index 0})
                                             (:tool-calls m))]
                        (when (or (seq parts) (seq tool-calls))
                          (cond-> {:role "assistant"}
                            (seq parts) (assoc :content parts)
                            (seq tool-calls) (assoc :tool_calls tool-calls))))
                      ;; custom + user
                      (let [had-images? (some image-block? (:content m))
                            parts (remove nil?
                                          (for [b (:content m)]
                                            (cond
                                              (= :text (:type b)) {:type "text" :text (:text b)}
                                              (and (image-block? b) supports-images?)
                                              {:type "image_url"
                                               :image_url (str "data:" (:mime-type b) ";base64," (:data b))}
                                              :else nil)))]
                        (cond
                          (seq parts) {:role "user" :content parts}
                          had-images? {:role "user"
                                       :content [{:type "text"
                                                  :text "(image omitted: model does not support images)"}]}
                          :else nil))))))
          messages)))

(defn mistral-uses-reasoning-effort?
  "pi usesReasoningEffort: models that expose the reasoning_effort option
   (the others use prompt_mode: \"reasoning\")."
  [model]
  (contains? #{"mistral-small-2603" "mistral-small-latest" "mistral-medium-3.5"}
             (:id model)))

(defn mistral-thinking
  "pi streamSimple thinking: prompt_mode \"reasoning\" for models without a
   reasoning_effort option; reasoning_effort (tlm-mapped ?? \"high\") for
   the effort models. EFFORT is the clamped level, nil when off."
  [model effort]
  (cond
    (not (and (:reasoning model) effort)) {}
    (mistral-uses-reasoning-effort? model)
    {:reasoning_effort (or (get-in model [:thinking-level-map effort]) "high")}
    :else {:prompt_mode "reasoning"}))

(defn mistral-tool
  "pi toFunctionTools: Mistral function tool. Strict-resolving tools get
   strictified parameters and :strict true (pi resolves with true — Mistral
   always supports strict); other tools get :strict false."
  [tool]
  (let [strict (cs/resolve-json-schema-strict-sampling tool true)]
    {:type "function"
     :function {:name (:name tool)
                :description (:description tool)
                :parameters (cs/get-json-schema-tool-parameters tool strict)
                :strict (true? strict)}}))

(defn mistral-payload
  "Mistral chat payload (pi buildChatPayload + toMistralWirePayload — the
   camelCase options are remapped to their snake_case wire names: maxTokens
   → max_tokens, reasoningEffort → reasoning_effort, promptMode →
   prompt_mode, promptCacheKey → prompt_cache_key)."
  [model-record effort messages tools model-id session-id cache-retention]
  (let [thinking (mistral-thinking model-record effort)]
    (cond-> {:model model-id
             :stream true
             :messages messages}
      (seq tools) (assoc :tools (mapv mistral-tool tools))
      (seq thinking) (merge thinking)
      (:max-tokens model-record) (assoc :max_tokens (:max-tokens model-record))
      (and (not= :none cache-retention) (seq session-id))
      (assoc :prompt_cache_key session-id))))

(defn mistral-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms total-timeout-ms session-id cache-retention on-error]
    :as opts}]
  (future
    (try
      (let [model-id (or (:model opts) (:id model-record))
            url (or base-url (endpoint-url :mistral-conversations (:base-url model-record) model-id))
            payload (apply-before-provider-request-hook
                     (mistral-payload model-record effort
                                      (mistral-messages messages model-record
                                                        (make-mistral-tool-call-id-normalizer))
                                      tools model-id session-id cache-retention))
            base-headers (request-headers
                          {"Authorization" (str "Bearer " api-key)
                           "Content-Type" "application/json"
                           "Accept" "text/event-stream"}
                          model-record provider-record api-key session-id)
            ;; pi: x-affinity session header when caching, unless the model
            ;; or request headers already set it explicitly
            headers (if (and (not= :none cache-retention) (seq session-id)
                             (not-any? #(str/includes? (str/lower-case (name %)) "x-affinity")
                                       (keys base-headers)))
                      (assoc base-headers "x-affinity" session-id)
                      base-headers)
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
          (sse/process-mistral-stream response
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
