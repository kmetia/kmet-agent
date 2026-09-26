(ns kmet.ai.api.openai-completions
  "OpenAI Completions wire API (pi: api/openai-completions.ts)."
  (:require
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [kmet.ai.api.shared :refer [endpoint-url max-tokens-key openai-messages openai-messages-with-reasoning openai-thinking-params resolved-openai-compat apply-before-provider-request-hook request-headers tool->openai-schema transport-error-message usage-with-cost]]))

(defn openai-payload
  "Request body for an openai-completions request (pi buildParams):
   model/messages/stream/stream_options, tools, thinking params, the
   max-tokens field, then the model's :sampling-params merged last so their
   keys win over the named request fields (pi: Object.assign(params,
   samplingParams) after everything else — samplingParams is the single
   source of sampling truth for a model). Compat is URL-resolved
   (resolved-openai-compat, pi getCompat)."
  [model-record effort messages tools model-id]
  (let [compat (resolved-openai-compat model-record)
        thinking-params (openai-thinking-params model-record effort)
        max-tokens-field (max-tokens-key model-record)
        ;; pi: requiresReasoningContentOnAssistantMessages gates the
        ;; reasoning_content field (deepseek/opencode-go only; also derived
        ;; from an explicit :thinking-format :deepseek — resolved-openai-compat)
        messages-fn (if (:requires-reasoning-content-on-assistant-messages
                         compat)
                      openai-messages-with-reasoning
                      openai-messages)]
    (cond-> {:model model-id
             ;; provider+model-id flow to the converters as the replay target:
             ;; a message's thinking echoes back as reasoning_content only
             ;; when the message was produced by this same model (mid-session
             ;; /model switches must not leak foreign CoT into the request)
             :messages (messages-fn messages (:provider model-record) model-id)
             :stream true
             :stream_options {:include_usage true}}
      (seq tools) (assoc :tools (mapv #(tool->openai-schema %
                                                            (not= false (:supports-strict-mode compat)))
                                      tools))
      (seq thinking-params) (merge thinking-params)
      (:max-tokens model-record) (assoc max-tokens-field (:max-tokens model-record))
      (seq (:sampling-params model-record)) (merge (:sampling-params model-record)))))

(defn openai-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms total-timeout-ms session-id
           on-text on-thinking on-tool-call on-done on-error
           on-usage] :as opts}]
  (future
    (try
      (let [model-id (or (:model opts) (:id model-record))
            url (or base-url (endpoint-url :openai-completions (:base-url model-record) model-id))
            payload (apply-before-provider-request-hook
                     (openai-payload model-record effort messages tools model-id))
            response (ai-http/request url
                                      {:headers (request-headers
                                                 {"Authorization" (str "Bearer " api-key)
                                                  "Content-Type" "application/json"}
                                                 model-record provider-record api-key
                                                 session-id)
                                       :body (json/generate-string payload)
                                       :as :stream
                                         ;; Total request deadline (pi: SDK timeoutMs ??
                                         ;; httpIdleTimeoutMs — the whole-request wall-clock
                                         ;; the transport enforces). Explicit total wins;
                                         ;; fall back to the idle timeout for callers that
                                         ;; only set that (compaction/summarization); nil
                                         ;; when both disabled.
                                       :timeout (when-let [t (or (when (and total-timeout-ms (pos? total-timeout-ms))
                                                                   total-timeout-ms)
                                                                 (when (pos? (or idle-timeout-ms 0))
                                                                   idle-timeout-ms))]
                                                  t)}
                                      signal)
            ;; The terminal :done is deferred until the whole stream is
            ;; consumed: openai-completions sends the usage-only chunk AFTER
            ;; the finish_reason chunk, so an on-done fired at the first
            ;; :done would capture an empty usage buffer and drop the
            ;; provider usage (footer token counts / cost never update — pi
            ;; resolves its stream only after the final chunk).
            stop-reason (atom nil)
            stop-reason-error (atom nil) ;; pi output.errorMessage — the
                                        ;; provider's specific finish_reason
                                        ;; error text (content_filter /
                                        ;; network_error / unknown), kept
                                        ;; alongside the :error stop-reason
            errored? (atom false)]
        (sse/process-openai-stream response
                                   (fn [event]
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
                                   signal
                                   idle-timeout-ms
                                   (fn [] (ai-http/abort! response)))
        ;; stream fully consumed — the trailing usage chunk (if any) was
        ;; dispatched; emit the deferred terminal done now (unless the run
        ;; was cancelled or an error already surfaced). An :error
        ;; stop-reason (content_filter / network_error / unknown — pi
        ;; mapStopReason) is NOT a normal completion: surface it via
        ;; on-error so the loop's retry/error path engages (pi pushes
        ;; {type: "error"} for these instead of done).
        (let [sr @stop-reason]
          (when (and (= :error sr) (not @errored?) on-error)
            (on-error (or @stop-reason-error
                          (str "Provider stopped with: " (name sr)))))
          (when (and on-done
                     (some? sr)
                     (not= :error sr)
                     (not @errored?)
                     (not (and signal @signal)))
            (on-done sr)))
        (ai-http/close! response))
      (catch Exception e
        (when on-error (on-error (transport-error-message e)))))))
