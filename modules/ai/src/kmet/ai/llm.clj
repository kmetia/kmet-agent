(ns kmet.ai.llm
  "LLM API dispatcher (pi: the per-wire api/* files dispatched from the
   Model record): resolves auth/model, clamps thinking effort, and routes to
   the per-API request builders through kmet.ai.wires — the one place that
   names them. The builders and their shared machinery live in
   kmet.ai.api.{shared,openai-completions,openai-responses,
   openai-codex-responses,azure-openai-responses,anthropic-messages,
   google-generative-ai,mistral-conversations,google-vertex,
   bedrock-converse-stream}.

   Message conversion is the caller's: the app hands pi's convertToLlm in as
   the :convert-to-llm option (kmet.app.loop.message/to-llm-messages), so this
   namespace never learns what an agent-context role is."
  (:require
   [kmet.ai.auth :as auth]
   [kmet.ai.models :as models]
   [kmet.ai.wires :as wires]
   [kmet.ai.api.shared :refer [ambient-auth-available?]]))

(defn request-opts
  "Normalize a `send-message` opts map for one call.

   Provider-event hooks are options, not installed globals (pi: StreamOptions
   carry their hooks per call): the attempt's `:hooks` map — read once by the
   runtime — is merged under any flat `:on-*` key, `:on-context` rewrites
   `:messages` before the caller's conversion (the last non-nil handler result
   wins, pi: emitContext), `:convert-to-llm` then maps the resulting messages
   (pi: convertToLlm — the app supplies it; absent means the messages are
   already wire-shaped), and the two request-time hooks are re-packed into the
   `:hooks` map kmet.ai.http takes. Returns a new map; OPTS is untouched."
  [opts]
  (let [hooks (merge (:hooks opts)
                     (select-keys opts [:on-context :on-payload :on-headers :on-response]))]
    (-> opts
        (assoc :on-payload (:on-payload hooks)
               ;; the request-time pair the HTTP decorator applies
               :hooks (select-keys hooks [:on-headers :on-response]))
        (update :messages #(if-let [f (:on-context hooks)] (or (f %) %) %))
        ;; pi's order: emitContext, then convertToLlm
        (update :messages #(if-let [f (:convert-to-llm opts)] (f %) %)))))

(defn send-message
  "Send messages to LLM and receive streaming events via callbacks.

   This is the builtin behind the app's `:llm` service (`kmet.app.llm`): a run
   makes its requests through that row, and this function is what the row
   provides unless a composition replaces it.

   opts:
     :provider    — provider keyword (:opencode-go, :opencode, :deepseek,
                    :github-copilot, :openai, :xai, :openai-codex,
                    :azure-openai-responses, :mistral, :google-vertex,
                    :amazon-bedrock, ...) or an account alias declared in
                    models.edn :accounts (e.g. :github-copilot#work)
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
     :total-timeout-ms — whole-request deadline in ms (HttpRequest.timeout /
                     curl --max-time semantics); nil follows the idle timeout,
                     non-positive disables the total deadline
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
     :hooks       — the provider-event bridge for this call, as the four
                     hook keys below (the runtime reads it once per attempt
                     and merges it in; a flat key overrides it).
     :on-context  — (fn [messages]) → replacement messages, nil keeps them
                     (pi: context event; applied before `:convert-to-llm`).
     :convert-to-llm — (fn [messages]) → the provider-neutral messages for
                     the request (pi: convertToLlm), applied after
                     `:on-context`; the app supplies
                     kmet.app.loop.message/to-llm-messages. Absent means the
                     caller's messages are already wire-shaped.
     :on-payload  — (fn [payload]) → replacement request payload, nil keeps
                     it (pi: before_provider_request; each api builder
                     applies it).
     :on-headers  — (fn [headers]) → replacement header map (pi:
                     before_provider_headers; applied by kmet.ai.http).
     :on-response — (fn [{:keys [status headers]}]) — fire and forget (pi:
                     after_provider_response).

   Returns: future that completes when the stream ends."
  [{:keys [provider model api-key] :or {provider :opencode-go} :as opts}]
  (let [opts (request-opts opts)
        auth (auth/resolve-provider-auth provider)
        ;; Resolve the key here when the caller didn't provide one (pi:
        ;; prepareRequest resolves auth per request) — so a direct call with
        ;; auth.edn / env credentials works, and the builders never see a nil
        ;; key that would produce an empty Authorization header.
        api-key (or api-key (:api-key auth))]
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
            ;; An oauth credential's to-auth carries a per-credential base-url
            ;; (pi applyAuth: auth.baseUrl overrides the model's — Copilot's
            ;; proxy-ep endpoint). Keep it on the resolved model so each API
            ;; builder still appends its endpoint path; an explicit
            ;; agent-level :base-url remains a complete endpoint override.
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
                effort (models/effective-effort m (:thinking opts))]
            (try
              ((wires/request-fn api)
               (assoc opts :model-record m :provider-record p
                      :effort effort :api-key api-key))
              (catch Throwable e
                (if (= ::wires/unknown-api-type (:cause (ex-data e)))
                  ;; An unknown wire reports through the async error channel
                  ;; like every other pre-request failure (pi: the stream fn
                  ;; reports, never throws into its caller).
                  (future
                    (when-let [on-error (:on-error opts)]
                      (on-error (ex-message e))))
                  (throw e))))))))))
