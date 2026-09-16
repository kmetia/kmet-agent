(ns kmet.ai.llm
  "LLM API dispatcher (pi: the per-wire api/* files dispatched from the
   Model record): resolves auth/model, clamps thinking effort, and routes to
   the per-API request builders in kmet.ai.api.*. The request builders and
   their shared machinery live in kmet.ai.api.{shared,openai-completions,
   openai-responses,openai-codex-responses,azure-openai-responses,
   anthropic-messages,google-generative-ai,mistral-conversations,
   google-vertex,bedrock-converse-stream}."
  (:require
   [kmet.ai.auth :as auth]
   [kmet.ai.models :as models]
   [kmet.ai.api.anthropic-messages :refer [anthropic-request]]
   [kmet.ai.api.azure-openai-responses :refer [azure-request]]
   [kmet.ai.api.bedrock-converse-stream :refer [bedrock-request]]
   [kmet.ai.api.google-generative-ai :refer [google-request]]
   [kmet.ai.api.google-vertex :refer [vertex-request]]
   [kmet.ai.api.mistral-conversations :refer [mistral-request]]
   [kmet.ai.api.openai-codex-responses :refer [codex-request]]
   [kmet.ai.api.openai-completions :refer [openai-request]]
   [kmet.ai.api.openai-responses :refer [responses-request]]
   [kmet.ai.api.shared :refer [ambient-auth-available? apply-context-hook effective-effort]]))

(def ^:private compaction-summary-prefix
  "pi: COMPACTION_SUMMARY_PREFIX — the compacted-history preamble and
   <summary> tag a compactionSummary message is wrapped in at the wire."
  "The conversation history before this point was compacted into the following summary:\n\n<summary>\n")

(def ^:private compaction-summary-suffix "\n</summary>")

(def ^:private branch-summary-prefix
  "pi: BRANCH_SUMMARY_PREFIX."
  "The following is a summary of a branch that this conversation came back from:\n\n<summary>\n")

(def ^:private branch-summary-suffix "\n</summary>")

(defn- convert-summary-messages
  "pi: convertToLlm — compaction/branch-summary AgentMessages become user
   messages carrying their wrapped summary text. The conversion happens per
   request, so the agent context (and the TUI) keeps the summary roles and
   the extension context hook still sees them; only the wire payload is
   flattened."
  [messages]
  (mapv (fn [m]
          (case (:role m)
            :compaction {:role :user
                         :content [{:type :text
                                    :text (str compaction-summary-prefix
                                               (:summary m "")
                                               compaction-summary-suffix)}]}
            :branch-summary {:role :user
                             :content [{:type :text
                                        :text (str branch-summary-prefix
                                                   (:summary m "")
                                                   branch-summary-suffix)}]}
            m))
        messages))

(defn send-message
  "Send messages to LLM and receive streaming events via callbacks.

   opts:
     :provider    — provider keyword (:opencode-go, :opencode, :deepseek,
                    :github-copilot, :openai, :xai, :openai-codex,
                    :azure-openai-responses, :mistral, :google-vertex,
                    :amazon-bedrock, ...)
     :model       — model id string, resolved against the provider's catalog
     :api-type    — wire api override (:openai-completions,
                    :openai-responses, :openai-codex-responses,
                    :azure-openai-responses, :anthropic-messages,
                    :google-generative-ai, :mistral-conversations,
                    :google-vertex, :bedrock-converse-stream); wins over the
                    resolved model's :api
     :base-url    — full endpoint URL override (e.g. local test servers);
                    wins over the model-derived URL
     :api-key     — API key (required — resolved by caller via cfg/get-api-key)
     :thinking    — :off :minimal :low :medium :high :xhigh :max; clamped by
                    the resolved model's capability
     :messages    — vector of message maps
     :tools       — vector of Tool records
     :signal      — atom; set to true to cancel
     :idle-timeout-ms — per-byte idle timeout on the stream in ms (pi:
                     httpIdleTimeoutMs — undici bodyTimeout semantics); nil
                     or non-positive disables it
     :total-timeout-ms — whole-request deadline in ms (pi: SDK timeoutMs ??
                     httpIdleTimeoutMs — HttpRequest.timeout / curl
                     --max-time semantics); nil or non-positive disables it
     :cache-retention — :short (default) | :long | :none — prompt-cache
                     params for openai-responses (pi CacheRetention; :none
                     disables the cache key + affinity headers — compaction
                     summaries pass it); ignored by the other apis
     :max-tokens  — per-call output token cap; overrides the model's
                     configured :max-tokens for this request (pi caps the
                     compaction summary output at 0.8 * reserveTokens)
     :on-text     — (fn [text-delta])
     :on-tool-call — (fn [{:keys [id name arguments]}])
     :on-done     — (fn [stop-reason])
     :on-error    — (fn [message])
     :on-usage    — (fn [usage-map]) — provider-native usage from the final
                     stream chunk.

   Returns: future that completes when the stream ends."
  [{:keys [provider model api-key] :or {provider :opencode-go} :as opts}]
  (let [auth (auth/resolve-provider-auth provider)
        ;; Resolve the key here when the caller didn't provide one (pi:
        ;; prepareRequest resolves auth per request) — so a direct call with
        ;; auth.edn / env credentials works, and the builders never see a nil
        ;; key that would produce an empty Authorization header.
        api-key (or api-key (:api-key auth))
        ;; An oauth credential's to-auth carries a per-credential base-url
        ;; (pi applyAuth: auth.baseUrl overrides the model's — Copilot's
        ;; proxy-ep endpoint). Keep it on the resolved model so each API
        ;; builder still appends its endpoint path; an explicit agent-level
        ;; :base-url remains a complete endpoint override.
        ;; pi: emitContext — the context event fires before each LLM call;
        ;; the hook (installed by the extension bridge) may replace the
        ;; outgoing messages (first non-nil handler result wins)
        opts (update opts :messages apply-context-hook)
        ;; pi: convertToLlm — compaction/branch-summary roles become wrapped
        ;; user messages at the provider edge (after the context hook, which
        ;; still sees the role-preserving AgentMessages)
        opts (update opts :messages convert-summary-messages)]
    (cond
      ;; google-vertex (ADC) and amazon-bedrock (ambient AWS credentials)
      ;; resolve their own auth — the api-key check is per-request below
      (and (nil? api-key) (nil? (:bearer auth))
           (not (ambient-auth-available? provider)))
      (future
        (when-let [on-error (:on-error opts)]
          (on-error (str "No API key for " (name provider)
                         ". Set the key in ~/.kmet/agent/auth.edn."))))

      :else
      (let [m (models/get-model provider model)
            m (if (and m (:base-url auth) (nil? (:base-url opts)))
                (assoc m :base-url (:base-url auth))
                m)
            ;; per-call max-tokens override (pi: compaction caps the
            ;; summarization output at 0.8 * reserveTokens) — applied to the
            ;; model record so every API builder picks it up
            m (if-let [mt (:max-tokens opts)]
                (assoc m :max-tokens mt)
                m)]
        (cond
          ;; Catalog provider with an unknown model id → error
          (and (some? (models/get-provider provider)) (nil? m))
          (future
            (when-let [on-error (:on-error opts)]
              (on-error (str "Unknown model: " (name provider) "/" model))))

          ;; Unknown provider (no catalog entry) → error
          (nil? m)
          (future
            (when-let [on-error (:on-error opts)]
              (on-error (str "Unknown provider: " (name provider)))))

          :else
          (let [api (or (:api-type opts) (:api m))
                p (models/get-provider provider)
                effort (effective-effort m (:thinking opts))]
            (case api
              :openai-completions (openai-request (assoc opts :model-record m :provider-record p
                                                         :effort effort :api-key api-key))
              :openai-responses (responses-request (assoc opts :model-record m :provider-record p
                                                          :effort effort :api-key api-key))
              :openai-codex-responses (codex-request (assoc opts :model-record m :provider-record p
                                                            :effort effort :api-key api-key))
              :azure-openai-responses (azure-request (assoc opts :model-record m :provider-record p
                                                            :effort effort :api-key api-key))
              :anthropic-messages (anthropic-request (assoc opts :model-record m :provider-record p
                                                            :effort effort :api-key api-key))
              :google-generative-ai (google-request (assoc opts :model-record m :provider-record p
                                                           :effort effort :api-key api-key))
              :mistral-conversations (mistral-request (assoc opts :model-record m :provider-record p
                                                             :effort effort :api-key api-key))
              :google-vertex (vertex-request (assoc opts :model-record m :provider-record p
                                                    :effort effort :api-key api-key))
              :bedrock-converse-stream (bedrock-request (assoc opts :model-record m :provider-record p
                                                               :effort effort :api-key api-key))
              (future
                (when-let [on-error (:on-error opts)]
                  (on-error (str "Unknown api-type: " (name (:api-type opts)))))))))))))
