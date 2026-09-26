(ns kmet.ai.api.google-vertex
  "Google Vertex AI wire API (pi: api/google-vertex.ts — gemini-2.5+ style
   streamGenerateContent via the regional endpoint)."
  (:require
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.google-adc :as google-adc]
   [kmet.ai.auth :as auth]
   [kmet.ai.api.sse :as sse]
   [clojure.string :as str]
   [kmet.ai.constrained-sampling :as cs]
   [kmet.ai.api.google-generative-ai :refer [google-messages google-thinking-config]]
   [kmet.ai.api.shared :refer [getenv google-supports-strict-tool-sampling? apply-before-provider-request-hook request-headers responses-events-handler tool->google-schema transport-error-message]]))

(def vertex-base-url
  "The Vertex endpoint template (pi VERTEX_BASE_URL — the SDK substitutes
   project/location; kmet constructs the URL itself)."
  "https://{location}-aiplatform.googleapis.com")

(defn vertex-endpoint-url
  "pi: the Vertex streamGenerateContent URL with project/location from the
   env (GOOGLE_CLOUD_PROJECT / GCLOUD_PROJECT / GOOGLE_CLOUD_LOCATION). A
   model base-url containing {location} (or empty) resolves the location
   from the env; any other base-url is used verbatim (custom endpoints)."
  [model-base-url model-id]
  (let [project (or (getenv "GOOGLE_CLOUD_PROJECT") (getenv "GCLOUD_PROJECT"))
        location (getenv "GOOGLE_CLOUD_LOCATION")]
    (when-not (seq project)
      (throw (ex-info "Vertex AI requires a project ID. Set GOOGLE_CLOUD_PROJECT/GCLOUD_PROJECT."
                      {:type :vertex-config-missing})))
    (when-not (seq location)
      (throw (ex-info "Vertex AI requires a location. Set GOOGLE_CLOUD_LOCATION."
                      {:type :vertex-config-missing})))
    (let [base (if (str/includes? (or model-base-url "") "{location}")
                 (str/replace vertex-base-url "{location}" location)
                 model-base-url)]
      (str base "/v1/projects/" project "/locations/" location
           "/publishers/google/models/" model-id ":streamGenerateContent?alt=sse"))))

(defn vertex-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms total-timeout-ms session-id on-error]
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
                     (assoc :toolConfig {:functionCallingConfig {:mode "VALIDATED"}})))
          ;; auth: GOOGLE_CLOUD_API_KEY (x-goog-api-key) or ADC
          ;; (Authorization: Bearer — the token is fetched + cached here)
          api-key (or api-key (auth/resolve-api-key :google-vertex))
          auth-header (if api-key "x-goog-api-key" "Authorization")
          auth-value (or api-key (google-adc/access-token!))]
      (if-not auth-value
        (when on-error
          (on-error (str "No API key for google-vertex. Set GOOGLE_CLOUD_API_KEY "
                         "or configure Application Default Credentials.")))
        (try
          (let [response (ai-http/request (or base-url (vertex-endpoint-url (:base-url model-record) model-id))
                                          {:headers (request-headers
                                                     {auth-header (str (when-not api-key "Bearer ") auth-value)
                                                      "Content-Type" "application/json"}
                                                     model-record provider-record api-key session-id)
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
              (sse/process-google-stream response
                                         dispatch
                                         signal
                                         idle-timeout-ms
                                         (fn [] (ai-http/abort! response)))
              ;; the stream is fully consumed — a trailing usage chunk (if
              ;; any) is dispatched; emit the deferred terminal done now
              (finalize (some-> signal deref)))
            (ai-http/close! response))
          (catch Exception e
            (when on-error (on-error (transport-error-message e)))))))))
