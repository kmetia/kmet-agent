(ns kmet.app.retry
  "Provider-error retry policy: error classification, the recovery
   decision for a failed LLM call, and the bounded retry driver used by the
   summarization calls (pi: packages/ai/src/utils/retry.ts).
   Classification and the driver are side-effect free apart from the
   abortable backoff sleep; the loop (kmet.app.loop) owns status, events,
   and session writes.

   Retry classification mirrors pi's packages/ai/src/utils/retry.ts +
   overflow.ts; backoff and timeout resolution follow pi: agent-session
   auto-retry (base-delay-ms * 2^(attempt-1), capped at max-agent-delay-ms)
   and the whole-request deadline's idle-fallback / explicit-disable rule."
  (:require [clojure.string :as str]
            [kmet.libs.concurrent :as concurrent]))

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
  (let [end-ms (+ (concurrent/monotonic-ms) delay-ms)]
    (loop []
      (if @signal
        false
        (let [remaining (- end-ms (concurrent/monotonic-ms))]
          (if (<= remaining 0)
            true
            (do (Thread/sleep (min 100 remaining))
                (recur))))))))

(defn llm-total-timeout-ms
  "Total deadline for one LLM call. A configured non-positive total timeout
   disables the total deadline; nil follows a positive idle timeout. Returns
   0 when no total deadline applies."
  [cfg]
  (let [total (:http-total-timeout-ms cfg)
        idle (or (:http-idle-timeout-ms cfg) 0)]
    (cond
      (some? total) (if (pos? total) total 0)
      (pos? idle) idle
      :else 0)))

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

(def default-max-agent-delay-ms
  "Cap on a computed agent-level backoff delay (pi:
   DEFAULT_MAX_AGENT_RETRY_DELAY_MS — `maxAgentDelayMs ?? 60s`)."
  60000)

(defn retry-delay-ms
  "Backoff delay before retry ATTEMPT (1-indexed) for a retry POLICY
   {:base-delay-ms n :max-agent-delay-ms n} (pi: retryDelayMs —
   baseDelayMs * 2^(attempt-1), clamped to the policy's maxAgentDelayMs,
   default 60s)."
  [policy attempt]
  (let [base (or (:base-delay-ms policy) 2000)
        delay (* base (long (Math/pow 2 (max 0 (dec attempt)))))]
    (min (if (and (pos? delay) (< delay Long/MAX_VALUE)) delay Long/MAX_VALUE)
         (or (:max-agent-delay-ms policy) default-max-agent-delay-ms))))

(defn retry-decision
  "Classify an errored LLM result into the recovery action (pure — no side
   effects; the caller performs them):
     {:kind :overflow-recover} — context overflow, not yet recovered:
       compact once, then retry the same turn
     {:kind :backoff :attempt n :delay-ms ms :max-attempts max-retries} —
       retryable within budget: exponential backoff, same turn
     {:kind :terminal} — non-retryable or retries exhausted"
  [{:keys [err retry-count max-retries base-delay-ms max-agent-delay-ms
           overflow-recovered has-session]}]
  (cond
    (and (not overflow-recovered)
         (context-overflow? err)
         has-session)
    {:kind :overflow-recover}

    (and (<= (inc retry-count) max-retries)
         (not (context-overflow? err))
         (retryable-error? err))
    (let [attempt (inc retry-count)]
      {:kind :backoff :attempt attempt
       :delay-ms (retry-delay-ms {:base-delay-ms base-delay-ms
                                  :max-agent-delay-ms max-agent-delay-ms}
                                 attempt)
       :max-attempts max-retries})

    :else {:kind :terminal}))

(defn retry-call!
  "Run PRODUCE — a thunk returning one LLM result map with :stop-reason —
   with bounded retry on transient errors (pi: retryAssistantCall):
     :stop-reason :aborted          terminal — never retried
     any other non-:error reason    returned as-is (success)
     :stop-reason :error            retried while retryable-error? holds for
                                    :error-message and retries remain
   Each retry sleeps retry-delay-ms of POLICY {:max-retries n
   :base-delay-ms n :max-agent-delay-ms n}; SIGNAL (an atom a transport
   polls) aborts the sleep, returning the last result as an aborted one
   (pi: an abort during the backoff normalizes to an aborted response).

   CALLBACKS (pi: RetryCallbacks, all optional):
     :on-retry-scheduled     (fn [attempt max-attempts delay-ms error-message])
                             — before each backoff sleep
     :on-retry-attempt-start (fn []) — after the sleep, before the call
     :on-retry-finished      (fn [success attempt final-error]) — once,
                             when a scheduled retry ended the loop (a
                             first-attempt result never calls it)"
  [produce policy signal callbacks]
  (let [max-attempts (max 0 (long (or (:max-retries policy) 0)))]
    (loop [attempt 0
           scheduled nil]
      (let [result (produce)
            finish! (fn [success attempt & [final-error]]
                      (when-let [f (:on-retry-finished callbacks)]
                        (f success attempt final-error)))]
        (cond
          (= :aborted (:stop-reason result))
          (do (when scheduled (finish! false (:attempt scheduled)))
              result)

          (not= :error (:stop-reason result))
          (do (when scheduled (finish! true (:attempt scheduled)))
              result)

          (or (>= attempt max-attempts)
              (not (retryable-error? (:error-message result))))
          (do (when scheduled
                (finish! false (:attempt scheduled) (:error-message result)))
              result)

          :else
          (let [attempt' (inc attempt)
                delay-ms (retry-delay-ms policy attempt')]
            (when-let [f (:on-retry-scheduled callbacks)]
              (f attempt' max-attempts delay-ms
                 (or (:error-message result) "Unknown error")))
            (if (backoff-sleep! signal delay-ms)
              (do (when-let [f (:on-retry-attempt-start callbacks)] (f))
                  (recur attempt' {:attempt attempt'}))
              (do (finish! false attempt' (:error-message result))
                  (-> result
                      (dissoc :error-message)
                      (assoc :stop-reason :aborted))))))))))

