(ns kmet.ai.api.azure-openai-responses
  "Azure OpenAI responses wire API (pi: api/azure-openai-responses.ts)."
  (:require
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [clojure.string :as str]
   [kmet.ai.api.openai-responses :refer [responses-payload]]
   [kmet.ai.api.shared :refer [getenv apply-before-provider-request-hook request-headers responses-events-handler transport-error-message]]))

(defn normalize-azure-base-url
  "pi normalizeAzureBaseUrl: Azure hosts (.openai.azure.com /
   .cognitiveservices.azure.com / .ai.azure.com) with a bare or /openai
   path are forced to /openai/v1 so the deployments path appends correctly."
  [base-url]
  (let [trimmed (str/replace (str/trim base-url) #"/+$" "")]
    (try
      (let [u (java.net.URI. trimmed)
            host (some-> u .getHost)
            path (some-> u .getPath (str/replace #"/+$" ""))]
        (if (and host
                 (or (str/ends-with? host ".openai.azure.com")
                     (str/ends-with? host ".cognitiveservices.azure.com")
                     (str/ends-with? host ".ai.azure.com"))
                 (contains? #{"" "/" "/openai" "/openai/v1/responses"} path))
          ;; Re-derive scheme://[userinfo@]host[:port]/openai/v1 through the
          ;; component ctor from the single-arg parse above.
          (str (java.net.URI. (or (some-> u .getScheme) "https")
                              (some-> u .getUserInfo)
                              host
                              (.getPort u)
                              "/openai/v1"
                              nil
                              nil))
          trimmed))
      (catch Exception _ trimmed))))

(def azure-default-api-version "v1")

(defn azure-deployment-name
  "pi resolveDeploymentName (no per-request override in kmet): the model's
   deployment from the AZURE_OPENAI_DEPLOYMENT_NAME_MAP env var
   (modelId=deploymentName, comma-separated), else the model id."
  [model-id]
  (let [mapped (when-let [env (getenv "AZURE_OPENAI_DEPLOYMENT_NAME_MAP")]
                 (into {}
                       (keep (fn [entry]
                               (let [[mid dep] (str/split (str/trim entry) #"=" 2)]
                                 (when (and (seq mid) (seq dep))
                                   [(str/trim mid) (str/trim dep)]))))
                       (str/split env #",")))]
    (or (get mapped model-id) model-id)))

(defn azure-resolved-config
  "pi resolveAzureConfig: base URL + api version for an azure request.
   Base precedence: AZURE_OPENAI_BASE_URL → AZURE_OPENAI_RESOURCE_NAME
   (https://<name>.openai.azure.com/openai/v1) → the model's base-url;
   api version: AZURE_OPENAI_API_VERSION → \"v1\". Throws when no base is
   configurable (the request reports it as a stream error)."
  [model-base-url]
  (let [api-version (or (getenv "AZURE_OPENAI_API_VERSION") azure-default-api-version)
        env-base (some-> (getenv "AZURE_OPENAI_BASE_URL") str/trim)
        resource (getenv "AZURE_OPENAI_RESOURCE_NAME")
        resolved (or (not-empty env-base)
                     (when (seq resource)
                       (str "https://" resource ".openai.azure.com/openai/v1"))
                     (when (seq model-base-url) model-base-url))]
    (when-not resolved
      (throw (ex-info "Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or AZURE_OPENAI_RESOURCE_NAME."
                      {:type :azure-config-missing})))
    {:base-url (normalize-azure-base-url resolved)
     :api-version api-version}))

(defn azure-endpoint-url
  "pi: base + /deployments/<deployment>/responses?api-version=<v> (the
   AzureOpenAI SDK appends the deployment path)."
  [base deployment api-version]
  (str base "/deployments/" deployment "/responses?api-version=" api-version))

(defn azure-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms total-timeout-ms session-id cache-retention on-error]
    :as opts}]
  (future
    ;; the config resolution (env base, deployment name) can throw when no
    ;; base is configurable — report it via on-error like a transport failure
    ;; (pi surfaces it as a stream error), never hang the caller
    (try
      (let [model-id (or (:model opts) (:id model-record))
            deployment (azure-deployment-name model-id)
            config (azure-resolved-config (:base-url model-record))
            retention (or cache-retention :short)
            url (or base-url
                    (azure-endpoint-url (:base-url config) deployment
                                        (:api-version config)))
            payload (apply-before-provider-request-hook
                     (responses-payload model-record effort messages tools deployment
                                        retention session-id))
            ;; azure sends no session-affinity headers (pi: the Azure client
            ;; sets none) — just the bearer + JSON content type
            headers (request-headers
                     {"Authorization" (str "Bearer " api-key)
                      "Content-Type" "application/json"}
                     model-record provider-record api-key session-id)
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
