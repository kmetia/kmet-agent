(ns kmet.ai.api.google-generative-ai
  "Google Generative AI (Gemini) wire API (pi: api/google-generative-ai.ts)."
  (:require
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [clojure.string :as str]
   [kmet.ai.constrained-sampling :as cs]
   [kmet.ai.api.shared :refer [bash-execution-text content-text endpoint-url google-supports-strict-tool-sampling? image-block? apply-before-provider-request-hook request-headers tool->google-schema transport-error-message usage-with-cost]]))

(defn google-requires-tool-call-id?
  [model-id]
  (let [major (second (re-find #"(?i)^gemini(?:-live)?-(\d+)" model-id))]
    (or (str/starts-with? model-id "claude-")
        (str/starts-with? model-id "gpt-oss-")
        (and major (<= 3 (Long/parseLong major))))))

(defn google-normalize-tool-call-id
  [id]
  (let [sanitized (str/replace (or id "") #"[^a-zA-Z0-9_-]" "_")]
    (if (> (count sanitized) 64) (subs sanitized 0 64) sanitized)))

(defn google-messages
  ([messages model]
   (google-messages messages model nil))
  ([messages model {:keys [provider]}]

   (let [requires-id? (google-requires-tool-call-id? (:id model))
         system (first (for [m messages
                             :when (= :system (:role m))]
                         (content-text (:content m))))
         msgs (remove #(= :system (:role %)) messages)]
     [(into []
            (keep (fn [m]
                    (case (name (:role m))
                      "bash"
                      (when-not (:exclude-from-context? m)
                        {:role "user" :parts [{:text (bash-execution-text m)}]})
                      "tool"
                      (let [r (first (:content m))
                            text (or (:content r) "")
                            resp (if (:is-error m) {:error text} {:output text})
                            fc (cond-> {:name (:tool-name m)
                                        :response resp}
                                 requires-id? (assoc :id (google-normalize-tool-call-id
                                                          (-> m :content first :tool_use_id))))]
                        {:role "user" :parts [{:functionResponse fc}]})
                      "assistant"
                      (let [;; pi google-shared: reasoning replays as a thought
                           ;; part for same-provider-same-model messages
                           ;; (signature echoed when captured); cross-provider
                           ;; reasoning degrades to a plain text part
                            same-model? (and (= (:provider m) provider)
                                             (= (:model m) (:id model)))
                            thinking-part (when-let [th (not-empty (str/trim (or (:thinking m) "")))]
                                            (cond
                                              (and same-model? (:thinking-signature m))
                                              {:text th :thought true
                                               :thoughtSignature (:thinking-signature m)}
                                              same-model?
                                              {:text th :thought true}
                                              :else {:text th}))
                            parts (into []
                                        (concat
                                         (when thinking-part [thinking-part])
                                        ;; pi google-shared: empty text parts are dropped
                                        ;; (Gemini can attach a thought signature to a blank
                                        ;; part; kmet stores thinking separately, so the drop
                                        ;; is unconditional here)
                                         (for [b (:content m)
                                               :when (and (= :text (:type b))
                                                          (seq (str/trim (or (:text b) ""))))]
                                           {:text (:text b)})
                                         (for [tc (:tool-calls m)]
                                           {:functionCall (cond-> {:name (:name tc)
                                                                   :args (:arguments tc)}
                                                            requires-id? (assoc :id (google-normalize-tool-call-id (:id tc))))})))]
                        (when (seq parts) {:role "model" :parts parts}))
                     ;; user
                      (let [parts (if (some image-block? (:content m))
                                    (for [b (:content m)]
                                      (if (image-block? b)
                                        {:inlineData {:mime-type (:mime-type b)
                                                      :data (:data b)}}
                                        {:text (or (:text b) "")}))
                                    (when (seq (:content m))
                                      [{:text (content-text (:content m))}]))]
                        (when (seq parts) {:role "user" :parts parts})))))
            msgs)
      system])))

(defn google-thinking-level
  "pi getThinkingLevel: gemini-3 pro collapses to LOW/HIGH; gemma4 to
   MINIMAL/HIGH; the default maps each level to its own wire value."
  [model effort]
  (let [id (:id model)
        pro? (re-matches #"(?i).*gemini-?3(?:\.\d+)?-pro.*" id)
        gemma? (re-matches #"(?i).*gemma-?4.*" id)]
    (cond
      (and pro? (contains? #{:minimal :low} effort)) "LOW"
      (and pro? (contains? #{:medium :high} effort)) "HIGH"
      (and gemma? (contains? #{:minimal :low} effort)) "MINIMAL"
      (and gemma? (contains? #{:medium :high} effort)) "HIGH"
      (= effort :minimal) "MINIMAL"
      (= effort :low) "LOW"
      (= effort :medium) "MEDIUM"
      :else "HIGH")))

(defn google-thinking-budget
  "pi getGoogleBudget: 2.5-pro / 2.5-flash(-lite) budgets, -1 (dynamic)
   for other models. Only reached for non-gemini3/gemma4 models."
  [model effort]
  (let [id (:id model)]
    (cond
      (and (str/includes? id "2.5-pro") (= effort :minimal)) 128
      (and (str/includes? id "2.5-pro") (= effort :low)) 2048
      (and (str/includes? id "2.5-pro") (= effort :medium)) 8192
      (and (str/includes? id "2.5-pro") (= effort :high)) 32768
      (and (str/includes? id "2.5-flash-lite") (= effort :minimal)) 512
      (and (str/includes? id "2.5-flash-lite") (= effort :low)) 2048
      (and (str/includes? id "2.5-flash-lite") (= effort :medium)) 8192
      (and (str/includes? id "2.5-flash-lite") (= effort :high)) 24576
      (and (str/includes? id "2.5-flash") (= effort :minimal)) 128
      (and (str/includes? id "2.5-flash") (= effort :low)) 2048
      (and (str/includes? id "2.5-flash") (= effort :medium)) 8192
      (and (str/includes? id "2.5-flash") (= effort :high)) 24576
      :else -1)))

(defn google-thinking-config
  "pi: gemini-3 pro/flash + gemma4 use thinkingLevel; other models use
   thinkingBudget (getGoogleBudget; -1 = dynamic). Returns nil when the
   model cannot think."
  [model effort]
  (when (:reasoning model)
    (if (or (re-matches #"(?i).*gemini-?3(?:\.\d+)?-pro.*" (:id model))
            (re-matches #"(?i).*gemini-?3(?:\.\d+)?-flash.*" (:id model))
            (re-matches #"(?i).*gemma-?4.*" (:id model)))
      (if effort
        {:includeThoughts true :thinkingLevel (google-thinking-level model effort)}
        {:thinkingLevel (if (re-matches #"(?i).*gemini-?3(?:\.\d+)?-pro.*" (:id model))
                          "LOW" "MINIMAL")})
      (if effort
        {:includeThoughts true :thinkingBudget (google-thinking-budget model effort)}
        {:thinkingBudget 0}))))

(defn google-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms total-timeout-ms session-id
           on-text on-thinking on-signature on-tool-call on-done on-error
           on-usage]
    :as opts}]
  (future
    (let [model-id (or (:model opts) (:id model-record))
          [contents system] (google-messages messages model-record
                                             {:provider (:id provider-record)})
          thinking-config (google-thinking-config model-record effort)
          payload (apply-before-provider-request-hook
                   (cond-> {:contents contents
                            :generationConfig (cond-> {}
                                                (:max-tokens model-record)
                                                (assoc :maxOutputTokens (:max-tokens model-record))
                                                thinking-config
                                                (assoc :thinkingConfig thinking-config))}
                     system (assoc :systemInstruction {:parts [{:text system}]})
                     (seq tools) (assoc :tools [{:functionDeclarations
                                                 (mapv #(tool->google-schema %
                                                                             (google-supports-strict-tool-sampling? model-id))
                                                       tools)}])
                     ;; pi resolveGoogleFunctionCallingMode: a strict tool
                     ;; forces the validated function-calling mode
                     (some #(cs/resolve-json-schema-strict-sampling %
                                                                    (google-supports-strict-tool-sampling? model-id))
                           tools)
                     (assoc :toolConfig {:functionCallingConfig {:mode "VALIDATED"}})))]
      (try
        (let [response (ai-http/request (or base-url (endpoint-url :google-generative-ai (:base-url model-record) model-id))
                                        {:headers (request-headers
                                                   {"x-goog-api-key" api-key
                                                    "Content-Type" "application/json"}
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
          (sse/process-google-stream response
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
