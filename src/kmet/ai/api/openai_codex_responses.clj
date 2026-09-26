(ns kmet.ai.api.openai-codex-responses
  "OpenAI Codex Responses wire API (pi: api/openai-codex-responses.ts)."
  (:require
   [kmet.libs.json :as json]
   [kmet.ai.http :as ai-http]
   [kmet.ai.api.sse :as sse]
   [clojure.string :as str]
   [kmet.ai.api.openai-responses :refer [clamp-prompt-cache-key responses-messages responses-tools]]
   [kmet.ai.api.shared :refer [content-text effort-value apply-before-provider-request-hook request-headers responses-events-handler transport-error-message]]))

(def codex-default-base-url
  "pi DEFAULT_CODEX_BASE_URL: the ChatGPT backend the codex endpoint
   appends /codex/responses to."
  "https://chatgpt.com/backend-api")

(defn codex-endpoint-url
  "pi resolveCodexUrl: the default codex base
   https://chatgpt.com/backend-api; the endpoint is base + /codex/responses
   (bases already ending in /codex or the full path pass through)."
  [base]
  (let [raw (if (str/blank? base) codex-default-base-url base)
        normalized (str/replace raw #"/+$" "")]
    (cond
      (str/ends-with? normalized "/codex/responses") normalized
      (str/ends-with? normalized "/codex") (str normalized "/responses")
      :else (str normalized "/codex/responses"))))

(defn codex-account-id
  "pi extractAccountId: the chatgpt_account_id claim from the access token's
   JWT payload (base64url decode of the middle segment). Throws when the
   token is not a JWT or carries no claim — the Codex backend requires the
   chatgpt-account-id header."
  [token]
  (let [[_ payload] (str/split token #"\.")
        decoded (try (-> (java.util.Base64/getUrlDecoder)
                         (.decode (or payload ""))
                         (String. "UTF-8"))
                     (catch Exception _ nil))
        claim (try (when decoded
                     (get (json/parse-string decoded)
                          "https://api.openai.com/auth"))
                   (catch Exception _ nil))
        account-id (when (map? claim)
                     (get claim "chatgpt_account_id"))]
    (when-not (and (string? decoded)
                   (string? account-id)
                   (seq account-id))
      (throw (ex-info "Failed to extract accountId from token"
                      {:type :codex-account-id})))
    account-id))

(defn codex-request-headers
  "pi buildSSEHeaders + buildBaseCodexHeaders: the token as Authorization:
   Bearer, the chatgpt-account-id decoded from the token, originator + the
   kmet User-Agent, OpenAI-Beta responses=experimental, plus session-id +
   x-client-request-id when prompt caching is on. SESSION-ID is the
   already-clamped cache key."
  [api-key session-id]
  (cond-> {"Authorization" (str "Bearer " api-key)
           "chatgpt-account-id" (codex-account-id api-key)
           "originator" "kmet"
           "User-Agent" (str "kmet (" (System/getProperty "os.name")
                             "; " (System/getProperty "os.arch") ")")
           "OpenAI-Beta" "responses=experimental"
           "Content-Type" "application/json"
           "Accept" "text/event-stream"}
    session-id (assoc "session-id" session-id
                      "x-client-request-id" session-id)))

(defn codex-payload
  "pi buildRequestBody: the codex envelope over the shared responses
   messages/tools — the system prompt goes to the instructions field (not a
   developer message), text verbosity low, reasoning content always
   requested, tool_choice auto + parallel_tool_calls, the prompt-cache key
   when caching. Tools/reasoning/sampling-params merged after the envelope."
  [model-record effort messages tools model-id codex-session-id]
  (let [system (first (for [m messages :when (= :system (:role m))]
                        (content-text (:content m))))
        reasoning (when (and (:reasoning model-record) effort)
                    {:effort (effort-value model-record effort) :summary "auto"})]
    (cond-> {:model model-id
             :store false
             :stream true
             :instructions (or system "You are a helpful assistant.")
             :input (responses-messages model-record messages false)
             :text {:verbosity "low"}
             :include ["reasoning.encrypted_content"]
             :tool_choice "auto"
             :parallel_tool_calls true}
      codex-session-id (assoc :prompt_cache_key codex-session-id)
      (seq tools) (assoc :tools (responses-tools tools
                                                 (:supports-strict-mode (:compat model-record))))
      reasoning (assoc :reasoning reasoning)
      (seq (:sampling-params model-record)) (merge (:sampling-params model-record)))))

(defn codex-request
  [{:keys [model-record provider-record effort api-key messages tools signal base-url
           idle-timeout-ms total-timeout-ms session-id cache-retention on-error]
    :as opts}]
  (future
    ;; the envelope computation (account-id decode, cache key) can throw for
    ;; a bad credential — report it via on-error like a transport failure
    ;; (pi surfaces it as a stream error), never hang the caller
    (try
      (let [model-id (or (:model opts) (:id model-record))
            retention (or cache-retention :short)
            codex-session-id (when (and (not= :none retention) session-id)
                               (clamp-prompt-cache-key session-id))
            payload (apply-before-provider-request-hook
                     (codex-payload model-record effort messages tools model-id
                                    codex-session-id))
            headers (request-headers
                     (codex-request-headers api-key codex-session-id)
                     model-record provider-record api-key session-id)
            response (ai-http/request (or base-url
                                          (codex-endpoint-url (:base-url model-record)))
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
