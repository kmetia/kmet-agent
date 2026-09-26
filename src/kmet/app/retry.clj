(ns kmet.app.retry
  "Provider-error retry policy: error classification and the recovery
   decision for a failed LLM call. Pure — the loop (kmet.app.loop)
   performs the side effects (status, events, session writes).

   Retry classification mirrors pi's packages/ai/src/utils/retry.ts +
   overflow.ts; backoff and timeout resolution follow pi: agent-session
   auto-retry (base-delay-ms * 2^(attempt-1)) and the SDK's
   timeoutMs ?? httpIdleTimeoutMs deadline rule."
  (:require [clojure.string :as str]))

;; ─── Error classification (pi: retry.ts / overflow.ts) ────────────────────

(def ^:private non-retryable-error-regex
  "Combined regex for quota/billing/account-limit error messages — never retried.
   Mirrors pi's NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN
   (packages/ai/src/utils/retry.ts)."
  (re-pattern (str "(?i)"
                   (str/join "|"
                             ["GoUsageLimitError"
                              "FreeUsageLimitError"
                              "Monthly usage limit reached"
                              "available balance"
                              "insufficient_quota"
                              "out of budget"
                              "quota exceeded"
                              "billing"]))))

(def ^:private retryable-error-regex
  "Combined regex for transient provider/transport error messages — retryable.
   Mirrors pi's RETRYABLE_PROVIDER_ERROR_PATTERN (packages/ai/src/utils/retry.ts)."
  (re-pattern (str "(?i)"
                   (str/join "|"
                             ["overloaded"
                              "rate.?limit"
                              "too many requests"
                              "429" "500" "502" "503" "504" "524"
                              "service.?unavailable"
                              "server.?error"
                              "internal.?error"
                              "provider.?returned.?error"
                              ;; OpenRouter buffer-limit wrapper failures mid-request
                              ;; (pi RETRYABLE_PROVIDER_ERROR_PATTERN)
                              "exceeded request buffer limit while retrying upstream"
                              "network.?error"
                              "connection.?error"
                              "connection.?refused"
                              "connection.?lost"
                              "connection.?reset"
                              "connection.?abort"
                              "broken pipe"
                              "forcibly closed"
                              "other side closed"
                              "fetch failed"
                              "getaddrinfo"
                              "ENOTFOUND"
                              "EAI_AGAIN"
                              "upstream.?connect"
                              ;; OpenRouter upstream-routing failures without a status
                              ;; token in the body ('Upstream request failed: Endpoint
                              ;; <name> is unavailable.')
                              "upstream.*unavailable"
                              "reset before headers"
                              "socket hang up"
                              "socket connection was closed"
                              ;; kmet's SSE wrapper over a mid-stream close (java.net.http
                              ;; surfaces a dropped connection as a bare "closed")
                              "stream error: .*closed"
                              ;; premature end of the response stream (e.g. the JDK
                              ;; HTTP client's 'EOF reached while reading')
                              "eof"
                              "timed? out"
                              "timeout"
                              "terminated"
                              "websocket.?closed"
                              "websocket.?error"
                              "ended without"
                              "header parser received no bytes"
                              "stream ended before message_stop"
                              "stream ended before a terminal response event"
                              "http2 request did not get a response"
                              "rst.?stream"
                              "retry delay"
                              "you can retry your request"
                              "try your request again"
                              "please retry your request"
                              "ResourceExhausted"]))))

(def ^:private overflow-error-regex
  "Combined regex for context-window overflow error messages.
   Mirrors pi's OVERFLOW_PATTERNS (packages/ai/src/utils/overflow.ts)."
  (re-pattern (str "(?i)"
                   (str/join "|"
                             ["prompt is too long"
                              "request_too_large"
                              "input is too long for requested model"
                              "exceeds the context window"
                              "exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))"
                              "input token count.*exceeds the maximum"
                              "maximum prompt length is \\d+"
                              "reduce the length of the messages"
                              "maximum context length is \\d+ tokens"
                              "exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?"
                              "input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)"
                              "exceeds the limit of \\d+"
                              "exceeds the available context size"
                              "greater than the context length"
                              "context window exceeds limit"
                              "exceeded model token limit"
                              "too large for model with \\d+ maximum context length"
                              "prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?"
                              "model_context_window_exceeded"
                              "prompt too long; exceeded (?:max )?context length"
                              "range of input length should be"
                              "context[_ ]length[_ ]exceeded"
                              "too many tokens"
                              "token limit exceeded"
                              "^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)"]))))

(def ^:private non-overflow-error-regex
  "Combined regex for errors that look like overflow but are actually throttling
   (e.g. Bedrock 'Throttling error: Too many tokens'). Mirrors pi's
   NON_OVERFLOW_PATTERNS."
  (re-pattern (str "(?i)"
                   (str/join "|"
                             ["^(Throttling error|Service unavailable):"
                              "rate limit"
                              "too many requests"]))))

(defn retryable-error?
  "True if an error message looks like a transient provider/transport failure
   worth retrying (rate limit, 5xx, connection loss, timeout, ...).
   Quota/billing/account-limit errors are never retried.
   Mirrors pi's isRetryableAssistantError."
  [error-message]
  (and (string? error-message)
       (not (re-find non-retryable-error-regex error-message))
       (re-find retryable-error-regex error-message)))

(defn context-overflow?
  "True if an error message indicates a context-window overflow. Overflow is
   NOT auto-retried (it needs compaction) — mirrors pi's isContextOverflow
   error-message case."
  [error-message]
  (and (string? error-message)
       (not (re-find non-overflow-error-regex error-message))
       (re-find overflow-error-regex error-message)))

;; ─── Backoff + deadline + recovery decision ────────────────────────────────

(defn backoff-sleep!
  "Sleep DELAY-MS in 100ms increments, aborting early when the cancel SIGNAL
   (an atom) turns truthy. Returns true if the full delay elapsed, false if
   cancelled."
  [signal delay-ms]
  (let [end-ms (+ (System/currentTimeMillis) delay-ms)]
    (loop []
      (if @signal
        false
        (let [remaining (- end-ms (System/currentTimeMillis))]
          (if (<= remaining 0)
            true
            (do (Thread/sleep (min 100 remaining))
                (recur))))))))

(defn llm-total-timeout-ms
  "Total request deadline for one LLM call (pi: SDK timeoutMs ??
   httpIdleTimeoutMs — the whole-request wall-clock the transport enforces;
   the per-byte idle timeout is separate and resets on every received byte).
   Mirrors the transport's own resolution (call-llm → api builders): the
   configured :http-total-timeout-ms wins when positive, else the idle
   timeout, else no deadline (MAX_VALUE so the deref never fires early — the
   transport gets nil and waits forever, pi: httpIdleTimeoutMs 0 →
   effectively disabled)."
  [cfg]
  (let [total (:http-total-timeout-ms cfg)
        idle (or (:http-idle-timeout-ms cfg) 0)]
    (cond
      (and total (pos? total)) total
      (pos? idle) idle
      :else Integer/MAX_VALUE)))

(defn normalize-llm-result
  "Fold a provider-delivered :error stop-reason (content_filter /
   network_error / unknown — pi mapStopReason) that reached the loop
   without an :error key (e.g. another wire delivered it via on-done)
   into :error so the retry/error path engages (pi pushes {type: \"error\"}
   for these instead of done). Pure."
  [raw-result]
  (if (and (nil? (:error raw-result))
           (= :error (:stop-reason raw-result)))
    (assoc raw-result :error
           (or (:error-message raw-result)
               (str "Provider stopped with: " (name (:stop-reason raw-result)))))
    raw-result))

(defn retry-decision
  "Classify an errored LLM result into the recovery action (pure — no side
   effects; the caller performs them):
     {:kind :overflow-recover} — context overflow, not yet recovered:
       compact once, then retry the same turn
     {:kind :backoff :attempt n :delay-ms ms :max-attempts max-retries} —
       retryable within budget: exponential backoff, same turn
     {:kind :terminal} — non-retryable or retries exhausted"
  [{:keys [err retry-count max-retries base-delay-ms overflow-recovered
           has-session]}]
  (cond
    (and (not overflow-recovered)
         (context-overflow? err)
         has-session)
    {:kind :overflow-recover}

    (and (<= (inc retry-count) max-retries)
         (not (context-overflow? err))
         (retryable-error? err))
    (let [attempt (inc retry-count)
          delay-ms (* base-delay-ms
                      (long (Math/pow 2 (dec attempt))))]
      {:kind :backoff :attempt attempt :delay-ms delay-ms
       :max-attempts max-retries})

    :else {:kind :terminal}))

