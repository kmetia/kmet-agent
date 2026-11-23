(ns kmet.ai.attribution
  "Provider attribution request headers (pi: provider-attribution.ts).

   Attribution identifies the agent to providers that track usage by
   origin: OpenRouter (HTTP-Referer / X-OpenRouter-Title), NVIDIA NIM
   (X-BILLING-INVOKE-ORIGIN), Cloudflare (User-Agent), and OpenCode session
   headers. pi gates the default attribution on install telemetry (default
   true); kmet has no telemetry, so the headers are always sent and the
   gate is dropped. OpenCode session headers are not gated in pi either."
  (:require [clojure.string :as str]
            [kmet.ai.models :as models]))

(def ^:private openrouter-host "openrouter.ai")
(def ^:private nvidia-nim-host "integrate.api.nvidia.com")
(def ^:private cloudflare-api-host "api.cloudflare.com")
(def ^:private cloudflare-ai-gateway-host "gateway.ai.cloudflare.com")
(def ^:private opencode-host "opencode.ai")

(defn- host-of
  "Hostname of a base-url (pi: new URL(baseUrl).hostname — scheme stripped,
   port dropped, lowercased); nil when not an http(s) URL."
  [base-url]
  (when-let [m (re-matches #"(?i)^https?://([^/?#:]+).*" (or base-url ""))]
    (str/lower-case (second m))))

(defn openrouter-model?
  "pi isOpenRouterModel: provider id :openrouter, or the base-url contains
   'openrouter.ai' (pi uses a substring check here, not a hostname match)."
  [model]
  (or (= :openrouter (models/provider-family model))
      (str/includes? (or (:base-url model) "") openrouter-host)))

(defn- is-nvidia-nim-model?
  "pi isNvidiaNimModel: provider :nvidia or hostname integrate.api.nvidia.com."
  [model]
  (or (= :nvidia (models/provider-family model))
      (= nvidia-nim-host (host-of (:base-url model)))))

(defn- is-cloudflare-model?
  "pi isCloudflareModel: provider cloudflare-workers-ai / cloudflare-ai-gateway
   or hostname api.cloudflare.com / gateway.ai.cloudflare.com."
  [model]
  (or (contains? #{:cloudflare-workers-ai :cloudflare-ai-gateway} (models/provider-family model))
      (contains? #{cloudflare-api-host cloudflare-ai-gateway-host}
                 (host-of (:base-url model)))))

(defn- default-attribution-headers
  "Origin attribution for OpenRouter / NVIDIA NIM / Cloudflare models
   (nil for others). pi gates this on install telemetry; kmet has no
   telemetry so it is always sent."
  [model]
  (cond
    (openrouter-model? model)
    {"HTTP-Referer" "https://github.com/markokocic/kmet"
     "X-OpenRouter-Title" "kmet"
     "X-OpenRouter-Categories" "cli-agent"}

    (is-nvidia-nim-model? model)
    {"X-BILLING-INVOKE-ORIGIN" "Kmet"}

    (is-cloudflare-model? model)
    {"User-Agent" "kmet-coding-agent"}

    :else nil))

(defn- session-headers
  "pi getSessionHeaders — OpenCode session attribution (provider
   opencode/opencode-go or hostname opencode.ai); NOT telemetry-gated.
   Nil without a session id."
  [model session-id]
  (when session-id
    (when (or (contains? #{:opencode :opencode-go} (models/provider-family model))
              (= opencode-host (host-of (:base-url model))))
      {"x-opencode-session" session-id
       "x-opencode-client" "kmet"})))

(defn merge-provider-attribution-headers
  "pi mergeProviderAttributionHeaders (minus the telemetry gate, which kmet
   drops): session headers + default attribution, then HEADER-SOURCES
   override (the request's own headers win collisions — pi's
   transformHeaders passes the request headers as the last source).
   Returns nil when nothing to add."
  [model session-id & header-sources]
  (let [merged (into {}
                     (concat
                      (session-headers model session-id)
                      (default-attribution-headers model)
                      (keep identity header-sources)))]
    (when (seq merged) merged)))
