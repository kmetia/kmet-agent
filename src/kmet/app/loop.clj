(ns kmet.app.loop
  "Agent conversation loop — orchestrates user input, LLM calls, and tool execution.
   State machine: IDLE → THINKING → EXECUTING → THINKING → ... → IDLE

   Queues (pi: steeringQueue / followUpQueue):
     :steering  — user messages injected mid-run. Polled between turns (after
                   tool results, before the next LLM call). Use steer!.
     :follow-up — user messages processed after the current run settles.
                   Polled when the inner loop drains; the run continues with
                   them. Use follow-up!.
   :steering-mode / :follow-up-mode (:all default) control how many queued
   messages are drained per poll cycle: :all takes everything,
   :one-at-a-time takes one. Applied per queue.

   Emits lifecycle events (see kmet.app.event-bus) to the :on-event callback
   (UI) and to kmet.app.event-bus/emit-event! (extension system).

   Auto-retry (pi: agent-session auto-retry): transient LLM errors are retried
   with exponential backoff (base-delay-ms * 2^(attempt-1)), up to max-retries.
   Quota/billing errors and context overflow are never retried. Retry emits
   :auto-retry-start / :auto-retry-end events; cancellation during backoff
   aborts the run quietly.

   Repeat-loop guard (kmet-specific circuit breaker): a model stuck issuing
   identical tool calls (or repeating its reasoning) is detected via a
   sliding window of canonical (tool, args) signatures — the threshold-th
   identical call is suppressed, and an all-suppressed batch (or cumulative
   suppressions) stops the run with a :loop-guard event and a plain
   explanation. Read-only tools (read) never trip it. Tunable via
   settings.edn :loop-guard {:enabled bool :threshold n} and
   :thinking-loop-guard-enabled.

   Tool hooks (pi: beforeToolCall / afterToolCall): registered via
   set-before-tool-call! / set-after-tool-call!. The before hook can block
   execution ({:block true :reason ...}); the after hook can rewrite the
   result (:content / :is-error).

   Tool execution mode (pi: toolExecution): tools default to :parallel;
   if any tool call targets a :sequential tool the whole batch runs
   sequentially (see :execution-mode on the Tool record).

   Per-turn hooks (pi: prepareNextTurn / shouldStopAfterTurn / transformContext):
   the :prepare-next-turn / :should-stop-after-turn hooks run after each turn
   (in pi order); :transform-context rewrites the conversation before each
   LLM call. The :system-prompt-override atom holds a per-run system prompt
   override.

   Compaction (pi: auto-compaction): the session is compacted proactively
   (measured usage vs. the model's context window) and reactively after a
   context-overflow error (compact once, then retry). Compaction summarizes
   the pre-cut conversation via the LLM (kmet.app.compaction, pi:
   core/compaction) and replaces it with a summary entry.

   Model management (pi: setModel / cycleModel): set-scoped-models! sets the
   session scoped model list; cycle-model! moves through it emitting
   :model-select. The :get-api-key atom holds a dynamic API key resolver.

   Input hooks (pi: input extension event): applied at the interactive input
   path (modes.interactive handle-submit) via extensions/apply-input-hooks —
   extensions can consume ({:action :handled}) or rewrite
   ({:action :transform :text ...}) user input before the agent runs.

   before-agent-start hooks (pi: before_agent_start extension event): applied
   by run-agent-turn after the user message is added, before the first LLM
   call. Extensions can override the system prompt for the run
   ({:system-prompt s}) and inject context messages ({:message m}); injected
   messages default to role :info — display-only (rendered in the UI, excluded
   from the LLM context). Other roles pass through to the provider.

   Image attachments (pi: prompt(text, images)): run-agent-turn accepts
   :images — a vector of {:type :image :data base64 :mime-type str} blocks
   attached to the initial user message; they flow to the provider as OpenAI
   image_url / Anthropic image blocks. Input hooks receive and can transform
   :images (extensions/apply-input-hooks). The :block-images setting (pi:
   images.blockImages) strips every image from the wire messages in call-llm,
   leaving the transcript and session intact."
  (:require [kmet.libs.json :as json]
            [clojure.string :as str]
            [kmet.ai.llm :as llm]
            [kmet.app.compaction :as compaction]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.ai.auth :as auth]
            [kmet.app.session :as session]
            [kmet.ai.models :as models]
            [kmet.ai.api.shared :as shared]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.extensions :as extensions]
            [kmet.app.event-bus :as event-bus]
            [kmet.debug :as debug]
            [kmet.config :as cfg]))

;; ─── Agent state ───────────────────────────────────────────────────────────

(defrecord AgentState [status        ;; :idle :thinking :executing :done :error
                       messages      ;; atom of conversation message vectors
                       session       ;; Session record or nil
                       model         ;; model identifier
                       provider      ;; provider keyword (registry id, e.g. :opencode-go)
                       system        ;; system prompt string
                       signal        ;; atom for cancellation
                       thinking      ;; :off :low :medium :high :max
                       on-event      ;; callback for state updates
                       base-url      ;; full endpoint URL override (default: derived from the Model record)
                       api-type      ;; wire api override (:openai-completions | :openai-responses | :anthropic-messages | :google-generative-ai)
                       steering      ;; atom of vector of queued steer messages
                       follow-up     ;; atom of vector of queued follow-up messages
                       active-call      ;; atom of {:promise p :partials f} while an LLM call is in flight (for cancel)
                       cfg              ;; atom of a config MAP — runtime-tunable knobs, replaced wholesale:
                                        ;;   {:max-retries int :base-delay-ms int :http-idle-timeout-ms int
                                        ;;    :steering-mode :all|:one-at-a-time :follow-up-mode :all|:one-at-a-time
                                        ;;    :auto-compact bool :context-window int-or-nil}
                       retry-count      ;; atom of int: retries performed for the in-flight LLM call
                       before-tool-call ;; atom of (fn [ctx]) → {:block true :reason} | nil
                       after-tool-call  ;; atom of (fn [ctx]) → override map | nil
                       system-prompt-override ;; atom of string or nil (per-run override, pi: _systemPromptOverride)
                       transform-context      ;; atom of (fn [messages]) → messages (pi: transformContext)
                       prepare-next-turn      ;; atom of (fn [ctx]) → update map | nil (pi: prepareNextTurn)
                       should-stop-after-turn ;; atom of (fn [ctx]) → boolean (pi: shouldStopAfterTurn)
                       get-api-key            ;; atom of (fn [provider]) → key | nil (dynamic auth)
                       scoped-models          ;; atom of vector of "provider/id" full ids (scoped list for cycling; pi: session.scopedModels)
                       overflow-recovered     ;; atom of bool: context-overflow compacted once this run
                       compact-token-threshold ;; int or nil: compact when estimated tokens exceed this
                       keep-recent-tokens     ;; int: cut-point budget in tokens (pi: keepRecentTokens, default 20000)
                       compacting?           ;; atom of bool: a compaction is in progress (escape cancels it)
                       pending-bash          ;; atom of vector of bash entries queued while streaming
                       system-prompt-opts    ;; atom of the build-system-prompt options map (pi: _baseSystemPromptOptions)
                       loop-guard            ;; atom of per-run repeat-loop guard state:
                                             ;;   {:window [signature...] :suppressed n}
                       ])

(defn make-agent-state
  "Create a new agent state.
   opts: :model, :provider, :system, :session, :on-event,
         :thinking, :base-url, :api-type, :steering-mode, :follow-up-mode,
         :max-retries (default 3), :base-delay-ms (default 2000),
         :before-tool-call, :after-tool-call, :system-prompt-override,
         :transform-context, :prepare-next-turn, :should-stop-after-turn,
         :get-api-key, :scoped-models (default []),
         :system-prompt-opts (build-system-prompt options map, pi:
         _baseSystemPromptOptions),
         :compact-token-threshold, :auto-compact (default true, pi:
         autoCompact), :context-window, :compact-reserve-tokens (default 16384,
         pi: reserveTokens), :keep-recent-tokens (default 20000, pi:
         keepRecentTokens), :http-idle-timeout-ms (default 300000, pi:
         httpIdleTimeoutMs; 0 disables), :http-total-timeout-ms (default nil
         = the idle timeout, pi: timeoutMs ?? httpIdleTimeoutMs — an explicit
         positive number overrides the total request deadline; 0 disables it
         (falls back to idle); nil uses idle),
         :block-images (default false, pi: images.blockImages — strip image
         blocks from provider calls; transcript and session untouched)"
  [& {:keys [model provider system session on-event thinking base-url api-type steering-mode follow-up-mode max-retries base-delay-ms before-tool-call after-tool-call system-prompt-override transform-context prepare-next-turn should-stop-after-turn get-api-key scoped-models system-prompt-opts compact-token-threshold context-window compact-reserve-tokens keep-recent-tokens http-idle-timeout-ms http-total-timeout-ms auto-compact loop-guard-enabled loop-guard-threshold thinking-loop-guard-enabled block-images]
      :or {provider :opencode-go
           thinking :off
           steering-mode :all
           follow-up-mode :all
           auto-compact true
           max-retries 3
           base-delay-ms 2000
           scoped-models []
           compact-reserve-tokens 16384
           keep-recent-tokens 20000
           http-idle-timeout-ms 300000
           http-total-timeout-ms nil
           block-images false
           loop-guard-enabled true
           loop-guard-threshold 3
           thinking-loop-guard-enabled true
           system "You are kmet, a minimal coding agent. Help the user with their tasks.
Use the available tools to read, write, edit files, and execute commands.
Be precise and concise in your responses."}}]
  (map->AgentState {:status (atom :idle)
                    :messages (atom [])
                    :session session
                    :model (atom model)
                    :provider (atom provider)
                    :system (atom system)
                    :signal (atom false)
                    :thinking (atom thinking)
                    :enabled-tools (atom nil)
                    :on-event on-event
                    :base-url base-url
                    :api-type api-type
                    :steering (atom [])
                    :follow-up (atom [])
                    :active-call (atom nil)
                    :cfg (atom {:max-retries max-retries
                                :base-delay-ms base-delay-ms
                                :http-idle-timeout-ms http-idle-timeout-ms
                                :http-total-timeout-ms http-total-timeout-ms
                                :steering-mode steering-mode
                                :follow-up-mode follow-up-mode
                                :auto-compact (boolean auto-compact)
                                :block-images (boolean block-images)
                                :context-window context-window
                                :loop-guard-enabled (boolean loop-guard-enabled)
                                :loop-guard-threshold (max 2 (long loop-guard-threshold))
                                :thinking-loop-guard-enabled (boolean thinking-loop-guard-enabled)})
                    :retry-count (atom 0)
                    :before-tool-call (atom before-tool-call)
                    :after-tool-call (atom after-tool-call)
                    :system-prompt-override (atom system-prompt-override)
                    :transform-context (atom transform-context)
                    :prepare-next-turn (atom prepare-next-turn)
                    :should-stop-after-turn (atom should-stop-after-turn)
                    :get-api-key (atom get-api-key)
                    :scoped-models (atom scoped-models)
                    :overflow-recovered (atom false)
                    :compact-token-threshold compact-token-threshold
                    :compact-reserve-tokens compact-reserve-tokens
                    :keep-recent-tokens keep-recent-tokens
                    :compacting? (atom false)
                    :pending-bash (atom [])
                    :system-prompt-opts (atom system-prompt-opts)
                    :loop-guard (atom {:window [] :suppressed 0})}))

;; ─── Active tools (pi: ctx.setActiveTools) ──────────────────────

;; Forward declaration — call-llm (the LLM call wrapper below) computes the
;; transport total deadline via llm-total-timeout-ms, defined later with the
;; other timeout helpers.
(declare llm-total-timeout-ms)

(defn- active-tools
  "The tools sent to the LLM: the registry filtered to the :enabled-tools
   set (nil = all), preserving registry order."
  [agent]
  (if-let [enabled @(:enabled-tools agent)]
    (filterv #(contains? enabled (:name %)) (vals (tools/get-all-tools)))
    (vals (tools/get-all-tools))))

(defn set-active-tools!
  "Restrict the tools sent to the LLM to NAMES (a set or seq of tool
   names); nil restores all tools (pi: setActiveTools). Also rebuilds the
   system prompt to reflect the new tool set (pi: setActiveToolsByName —
   only tools in the registry can be enabled; unknown names are ignored).
   Validation runs against the LIVE tool registry, and the base prompt
   options captured at build time (:system-prompt-opts, pi:
   _baseSystemPromptOptions) are re-applied with the filtered set, so the
   prompt's Available tools list and tool-derived guidelines stay in sync
   with what the model can actually call.
   Changes take effect on the next agent turn."
  [agent names]
  (let [names (cond
                (nil? names) nil
                (sequential? names) names
                ;; a bare string is a single tool name (extensions pass
                ;; through untyped; pi's string[] is enforced by TS)
                (string? names) [names]
                :else names)
        enabled (when names (set names))
        ;; validation source: the LIVE registry (pi: setActiveToolsByName
        ;; validates against the tool registry) — captures extension tools
        ;; registered after the system prompt was built
        valid (if enabled
                (filterv #(contains? enabled (:name %))
                         (vals (tools/get-all-tools)))
                (vals (tools/get-all-tools)))
        ;; pi filters unknown names out of the enabled set itself (only
        ;; registry tools are stored); the prompt is built from VALID, so
        ;; the two can never diverge
        enabled (when enabled (into #{} (map :name) valid))
        opts (assoc @(:system-prompt-opts agent) :tools valid)]
    (reset! (:enabled-tools agent) enabled)
    (when (seq @(:system-prompt-opts agent))
      (reset! (:system agent)
              (apply skills/build-system-prompt (mapcat identity opts))))
    nil))
;; ─── Helpers ───────────────────────────────────────────────────────────────

(defn- emit
  "Route an event to the UI callback (:on-event) and the extension system
   (event-bus/emit-event!). Extension listeners run inside emit-event!, which
   catches per-listener exceptions so a broken extension can't kill the loop."
  [agent event]
  (when-let [cb (:on-event agent)]
    (cb event))
  (event-bus/emit-event! event))

(defn- user-message
  "Build a user message map. text — string; images — optional vector of
   {:type :image :data base64 :mime-type str} blocks appended after the
   text block (pi: user message content with image attachments)."
  [text & [images]]
  {:role :user
   :content (into [{:type :text :text text}]
                  (or images []))})

(defn- assistant-message [text thinking tool-calls usage]
  (let [content (if (seq text) [{:type :text :text text}] [])]
    (cond-> {:role :assistant :content content}
      (seq thinking) (assoc :thinking thinking)
      (seq tool-calls) (assoc :tool-calls tool-calls)
      usage (assoc :usage usage))))

(defn- tool-result-message [tc-id tc-name result]
  (cond-> {:role :tool
           :content [{:type :tool_result
                      :tool_use_id tc-id
                      :content (:content result)}]
           :tool-name tc-name
           :is-error (:is-error result false)}
    (:images result) (assoc :images (:images result))
    (:truncation result) (assoc :truncation (:truncation result))
    (:details result) (assoc :details (:details result))))

(def ^:private blocked-image-placeholder
  "Text that replaces a blocked image in a provider request (pi:
   convertToLlmWithBlockImages — \"Image reading is disabled.\")."
  "Image reading is disabled.")

(defn- append-blocked-placeholder
  "Append the blocked-image placeholder to a tool result's own content
   (string or block vector). One placeholder stands for any number of
   images, matching pi's consecutive-placeholder dedupe in
   convertToLlmWithBlockImages."
  [content]
  (cond
    (nil? content) blocked-image-placeholder
    (string? content) (if (str/blank? content)
                        blocked-image-placeholder
                        (str content "\n" blocked-image-placeholder))
    (vector? content) (conj (vec content) {:type :text :text blocked-image-placeholder})
    :else blocked-image-placeholder))

(defn- block-images-content
  "Replace every image block in a content vector with the blocked-image
   placeholder text block, collapsing consecutive placeholders (pi:
   convertToLlmWithBlockImages)."
  [content]
  (let [placeholder {:type :text :text blocked-image-placeholder}]
    (reduce (fn [acc b]
              (let [b (if (shared/image-block? b) placeholder b)]
                (if (and (= b placeholder) (= (peek acc) placeholder))
                  acc
                  (conj acc b))))
            []
            content)))

(defn- block-message-images
  "pi: convertToLlmWithBlockImages — with :block-images on, strip every image
   from the wire messages, replacing it with a placeholder text (kmet carries
   user/custom images as content blocks and tool-result images in the
   message's :images; pi reaches the same set — its custom→user conversion
   happens before the filter, so custom images are caught too). Applied to
   the LLM request only: the stored context and the session keep their
   images, and the transcript still renders them. Like pi (whose
   convertToLlmWithBlockImages wrapper sits in the agent loop while
   compaction calls the plain convertToLlm), the compaction/branch-summary
   calls are NOT filtered.

   Defensive on shape: :images is dropped whatever the content (pi filters
   any toolResult image), but the placeholder is only woven into the
   canonical message shape — an extension-injected tool message carrying
   string content is stripped without a content rewrite."
  [messages]
  (mapv (fn [m]
          (case (:role m)
            (:user :custom) (if (vector? (:content m))
                              (update m :content block-images-content)
                              m)
            :tool (if (seq (:images m))
                    (cond-> (dissoc m :images)
                      (map? (first (:content m)))
                      (assoc-in [:content 0 :content]
                                (append-blocked-placeholder
                                 (-> m :content first :content))))
                    m)
            m))
        messages))

(defn drop-incomplete-tool-calls
  "Defensive repair of a corrupted conversation: strict providers (OpenAI-style,
   Mistral, Anthropic, Gemini) reject an assistant message whose tool calls are
   not all answered by tool result messages (and orphaned tool results with no
   matching tool call). Sessions can end up that way when the process dies
   between recording an assistant tool-call message and its results, or when a
   tool batch is interrupted mid-run. Drops the unmatched tool calls from their
   assistant message (keeping any text/thinking) and drops orphaned tool
   results; an assistant message left with no content at all is dropped.
   Healthy pi-style conversations never need this — pi always records a tool
   result per call (failToolCallsFromTruncatedMessage attaches error results)
   — so it only triggers on corruption.

   Returns a new message vector; the input is not mutated."
  [messages]
  (let [answered (volatile! #{})
        ;; Pass 1: which assistant tool-call ids actually have a following tool
        ;; result. Non-tool messages close the current batch; an assistant tool
        ;; message opens a new one.
        _ (loop [pending #{} ;; tool-call ids of the open batch still awaiting a result
                 msgs (seq messages)]
            (when-let [m (first msgs)]
              (let [role (:role m)]
                (cond
                  (= role :tool)
                  (let [id (-> m :content first :tool_use_id)]
                    (if (and id (contains? pending id))
                      (do (vswap! answered conj id)
                          (recur (disj pending id) (next msgs)))
                      ;; orphaned tool result — not answered
                      (recur pending (next msgs))))
                  (and (= role :assistant) (seq (:tool-calls m)))
                  ;; new batch; the previous batch's leftover pending ids die
                  (recur (into #{} (keep :id) (:tool-calls m)) (next msgs))
                  :else
                  ;; closes the current batch
                  (recur #{} (next msgs))))))]
    (into []
          (keep (fn [m]
                  (let [role (:role m)]
                    (cond
                      (= role :tool)
                      (when (contains? @answered (-> m :content first :tool_use_id))
                        m)
                      (= role :assistant)
                      (if-let [tcs (seq (:tool-calls m))]
                        (let [kept (filterv #(contains? @answered (:id %)) tcs)]
                          (if (seq kept)
                            (assoc m :tool-calls kept)
                            ;; no tool call survived — keep the message only when
                            ;; it still carries text or thinking (empty assistant
                            ;; messages are rejected by providers)
                            (when (or (seq (str/trim (or (:thinking m) "")))
                                      (some (fn [b] (seq (or (:text b) "")))
                                            (:content m)))
                              (dissoc m :tool-calls))))
                        m)
                      :else m))))
          messages)))

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

;; ─── Repeat-loop guard (circuit breaker) ────────────────────────────────────
;; Detects a model stuck emitting identical tool calls (or repeating its
;; reasoning) and stops the run instead of burning tokens on a dead end.
;; kmet-specific — no pi counterpart (dirge has a StormBreaker; research:
;; MukundaKatta/tool-loop-guard, isr4el-silv4/loop-guard).

(def ^:private loop-guard-exempt-tools
  "Tools that never trip the repeat-loop guard regardless of repetition
   count. Read-only inspectors are cheap and harmless — re-reading the same
   file (or read → edit → read verify patterns) is legitimate work."
  #{"read"})

(def ^:private loop-guard-reset-tools
  "Tools whose successful execution resets the repeat-loop guard's memory of
   other calls. Writes change the world, so a repeated call afterwards (e.g.
   re-running a test after an edit) observes new state and is not a loop.
   Only successful (non-error) executions reset; failed or blocked writes
   change nothing and keep the history. Each writer's own signature is kept,
   so repeating the identical write itself still trips. Accepted evasion:
   alternating a repeat with a novel successful write never trips — each
   successful write counts as progress."
  #{"write" "edit"})

(declare canonical-json)

(defn loop-guard-signature
  "Canonical repeat-loop key for a tool call: `name \\0 key-sorted JSON
   args`. Argument maps differing only in key order count as identical
   (deep key-sort, pi/dirge canonical_json). Returns nil for exempt tools
   (read) — they never enter the window."
  [tool-name args]
  (when-not (contains? loop-guard-exempt-tools tool-name)
    (let [args (if (string? args)
                 ;; OpenAI wire format: the whole args arrive as a JSON string
                 (let [parsed (try (json/parse-string args true) (catch Exception _ nil))]
                   (if (map? parsed) parsed args))
                 args)]
      (str tool-name "\u0000" (canonical-json args)))))

(defn- canonical-json
  "Canonical JSON for repeat-loop comparison: keys recursively sorted, so
   {:a 1 :b 2} and {:b 2 :a 1} compare equal. Args may be a map (Anthropic/
   Google), a string (OpenAI raw JSON — parsed ONLY at the top level, since
   only the whole args string arrives as JSON from the provider), or a
   vector (nested arrays — key-sorted recursively so edit batches with
   reordered maps still match). Values keep their JSON types (a string
   \"5\" never collides with the number 5; a nested string containing JSON
   text stays a string and never collides with a real nested map)."
  [args]
  (cond
    (map? args)
    (json/generate-string
     (into (sorted-map)
           (map (fn [[k v]] [(name k) (canonical-json v)]))
           args))
    (vector? args)
    (json/generate-string (mapv canonical-json args))
    (string? args)
    (pr-str args)
    :else (pr-str args)))

(def ^:private thinking-loop-error
  "Error message used when the thinking-loop guard cuts a stream. A
   distinct sentinel so the loop's error path can recognize it without
   string-matching a provider error."
  "thinking-loop guard: repeated reasoning detected")

(def ^:private thinking-loop-min-span
  "Minimum length (chars) of a repeated thinking segment before the guard
   considers it a loop. Below this, short repeated phrases in normal
   reasoning pass. Mirrors ollama-loop-guard's repeat-span-min (24)."
  24)

(def ^:private thinking-loop-delimiters
  "Chars that end a thinking-loop segment: ASCII sentence/line ends plus
   CJK full stops (。！？) — a multilingual reasoning loop (e.g. Chinese)
   repeats with 。-terminated segments and would otherwise be invisible
   (ollama-loop-guard / llama.cpp use \".!?\\n\"; the CJK additions cover
   the other major script)."
  #{\. \! \? \newline \。 \！ \？})

(def ^:private thinking-loop-ws
  "Whitespace consumed after a segment delimiter (Java \\s, ASCII six)."
  #{\space \tab \newline \u000B \formfeed \return})

(defn- split-thinking-segments
  "Split TAIL on thinking-loop delimiters without regex: jolt's irregex is
   pathological on the lookbehind this replaces (~1 s for a 4000-char
   buffer there vs ~1.5 ms here). Each segment keeps its delimiter plus
   following whitespace; thinking-loop? trims anyway, so this is
   downstream-identical to the old str/split (pinned across a 38-case
   corpus incl. CJK, CRLF and blank runs on both hosts)."
  [tail]
  (let [n (count tail)]
    (if (zero? n)
      []
      (loop [i 0 start 0 out []]
        (if (>= i n)
          (let [seg (subs tail start n)]
            (if (and (seq out) (= "" seg)) out (conj out seg)))
          (if (contains? thinking-loop-delimiters (nth tail i))
            (let [j (loop [k (inc i)]
                      (if (and (< k n) (contains? thinking-loop-ws (nth tail k)))
                        (recur (inc k))
                        k))]
              (recur j j (conj out (subs tail start j))))
            (recur (inc i) start out)))))))

(defn thinking-loop?
  "True when the recent THINKING text contains a repeated segment: the same
   segment (split on sentence/line delimiters, incl. CJK full stops)
   appearing >= 3 times within the trailing MAX-CHARS window. Catches
   degenerate reasoning loops where the model repeats the same analysis
   without producing content (research: ollama-loop-guard, llama.cpp
   line-level repetition). Pure and total (nil/empty → false)."
  [thinking & {:keys [max-chars] :or {max-chars 4000}}]
  (when (string? thinking)
    (let [tail (subs thinking (max 0 (- (count thinking) max-chars)))
          segments (->> (split-thinking-segments tail)
                        (map str/trim)
                        (remove #(or (empty? %) (< (count %) thinking-loop-min-span))))
          counts (frequencies segments)]
      (boolean (some #(>= % 3) (vals counts))))))

(defn loop-guard-filter
  "Pure repeat-loop detection for one tool-call batch.
   STATE — {:window [signature...] :suppressed n} (window is newest-last,
   capped at 4×threshold+1).
   Returns {:suppressed [tool-call...] :survivors [tool-call...]
            :state next-state}.
   A call is suppressed when its signature already appears (threshold-1)
   times in the window — i.e. the threshold-th identical call. The window
   is big enough (4×threshold+1) that alternating/cyclic patterns
   (1,2,1,2,1,2 / 1,2,3,1,2,3 / up to 2×threshold distinct calls) also
   trip: each signature's occurrences accumulate in the window until one
   reaches threshold. Exempt tools (read) never count."
  [{:keys [window suppressed]} threshold tool-calls]
  (let [window-size (inc (* 4 threshold))
        result (reduce (fn [{:keys [window suppressed-calls] :as acc} tc]
                         (if-let [sig (loop-guard-signature (:name tc) (:arguments tc))]
                           (let [count-in-window (count (filter #(= sig %) window))]
                             (if (>= count-in-window (dec threshold))
                               (assoc acc :suppressed-calls (conj suppressed-calls tc))
                               (assoc acc :window (conj window sig))))
                           acc))
                       {:window window :suppressed-calls []}
                       tool-calls)
        ;; window trimmed to window-size (drop oldest)
        window (let [w (:window result)]
                 (if (> (count w) window-size) (subvec w (- (count w) window-size)) w))
        ;; survivors are the non-suppressed calls in source order — rebuild
        ;; from the input so :survivors stays in original order
        suppressed-calls (:suppressed-calls result)
        suppressed-set (set (map :id suppressed-calls))
        survivors (into [] (remove #(contains? suppressed-set (:id %))) tool-calls)]
    {:suppressed suppressed-calls
     :survivors survivors
     :state {:window window
             :suppressed (+ suppressed (count suppressed-calls))}}))

(defn loop-guard-give-up!
  "Settle the run after the repeat-loop guard trips: set the explanation as
   the final text (delivered via on-done) and reset the per-run guard state
   for the next run. Emits the :loop-guard event (the UI shows a warning
   line). Pure state mutation on the agent."
  [agent text-buf reason details]
  (reset! text-buf details)
  (reset! (:loop-guard agent) {:window [] :suppressed 0})
  (emit agent {:type :loop-guard :reason reason :details details}))

;; ─── Queue helpers ─────────────────────────────────────────────────────────

(defn- drain-queue!
  "Atomically remove and return queued messages.
   mode :all drains everything; :one-at-a-time drains at most one."
  [queue-atom mode]
  (loop []
    (let [v @queue-atom]
      (if (empty? v)
        []
        (let [taken (if (= mode :all) v (subvec v 0 1))
              rest-v (if (= mode :all) [] (subvec v 1))]
          (if (compare-and-set! queue-atom v rest-v)
            taken
            (recur)))))))

(defn- add-user-message!
  "Add a user message to context and session, emitting :message-start.
   images — optional vector of image content blocks (pi: image attachments)."
  [agent text & [images]]
  (let [user-msg (user-message text images)]
    (swap! (:messages agent) conj user-msg)
    (when (:session agent)
      (session/append-entry (:session agent)
                            {:role :user :content (:content user-msg)}))
    (emit agent {:type :message-start :message user-msg})))

(defn- add-assistant-message!
  "Add a final assistant message to context and session, emitting
   :message-end. RESULT is call-llm's delivered map. Stamps provider/model
   provenance and the captured thinking signature (pi: AssistantMessage
   carries provider/model/api plus per-block signatures — converters replay
   signatures only for same-provider-same-model messages and degrade
   everything else to plain text). Returns the message."
  [agent {:keys [text thinking tool-calls usage thinking-signature]}]
  (let [assistant-msg (cond-> (assoc (assistant-message text thinking tool-calls usage)
                                     :provider @(:provider agent)
                                     :model @(:model agent))
                        (seq thinking-signature) (assoc :thinking-signature thinking-signature))]
    (swap! (:messages agent) conj assistant-msg)
    (when (:session agent)
      (session/append-entry (:session agent) assistant-msg))
    (emit agent {:type :message-end :message assistant-msg})
    assistant-msg))

(defn- record-abandoned-attempt!
  "Record an LLM attempt that never completed — stream error or user abort —
   in the session history without adding it to the live context, emitting
   :message-end (pi: a failed stream finalizes the partial as an
   AssistantMessage with stopReason \"error\"/\"aborted\" — message_end
   persists it to the session file; for errors _prepareRetry then removes it
   from agent state so the retried request never sees it; on resume it comes
   back as a plain assistant message carrying its partial text). RESULT is
   call-llm's delivered map with whatever partials arrived before the
   abandonment."
  [agent {:keys [text thinking tool-calls usage thinking-signature error]}
   stop-reason]
  (let [msg (cond-> (assoc (assistant-message text thinking tool-calls usage)
                           :provider @(:provider agent)
                           :model @(:model agent)
                           :stop-reason stop-reason)
              (seq thinking-signature) (assoc :thinking-signature thinking-signature)
              error (assoc :error-message error))]
    (when (:session agent)
      (session/append-entry (:session agent) msg))
    (emit agent {:type :message-end :message msg})
    msg))

(defn- add-custom-message!
  "Add a before-agent-start injected message to context and session, emitting
   :message-start. Role defaults to :info (display-only — rendered in the UI,
   excluded from the LLM context); other roles pass through to the provider.
   String :content is normalized to a text block so the message matches the
   canonical kmet message format (session persistence and resume display)."
  [agent m]
  (let [content (:content m)
        content (cond
                  (string? content) [{:type :text :text content}]
                  (nil? content) []
                  :else content)
        msg (cond-> (assoc m :content content)
              (not (contains? m :role)) (assoc :role :info))]
    (swap! (:messages agent) conj msg)
    (when (:session agent)
      (session/append-entry (:session agent) msg))
    (emit agent {:type :message-start :message msg})))

(defn- tool-execution-mode
  "Execution mode for a tool name: :sequential or :parallel.
   Unregistered/unknown tools default to :parallel (pi default)."
  [tool-name]
  (or (:execution-mode (tools/get-tool tool-name)) :parallel))

(defn- has-sequential-tool-call?
  "True if any tool call targets a :sequential tool — the whole batch then runs
   sequentially (pi: hasSequentialToolCall)."
  [tool-calls]
  (boolean (some #(= :sequential (tool-execution-mode (:name %))) tool-calls)))

(defn- run-tool-call!
  "Await a tool future, returning its result map. The UI updates only on
   actual tool output events (pi: tool_execution_update carries partial
   content) — no periodic progress pings, so a long-running tool does not
   force constant screen updates."
  [f]
  @f)

(defn- await-all-tool-results!
  "Poll all pending tool futures concurrently until every future completes.
   Returns a map tool-call-id → result in approximate completion order
   (newest completions discovered per 100ms poll batch). Each cycle blocks
   on the next pending future (max 100ms) instead of a fixed sleep, so fast
   tools finish without artificial delay. No progress pings are emitted —
   the UI updates only on real tool output (pi: agent-loop
   executePreparedToolCall). A failed future turns into an error result so
   the batch still records a result per tool call (pi:
   failToolCallsFromTruncatedMessage) instead of leaving the assistant
   tool calls dangling in the saved context."
  [futures]
  (let [results (atom {})]
    (loop []
      (let [remaining (remove (fn [[id _]] (contains? @results id)) futures)]
        (if (empty? remaining)
          @results
          (do (doseq [[tc-id f] remaining]
                (when-not (= :pending (deref f 0 :pending))
                  (swap! results assoc tc-id
                         (try @f
                              (catch Exception e
                                {:content (str "Error executing tool: " (ex-message e))
                                 :is-error true})))))
              (when-let [[_ f] (first (filter (fn [[_ f]] (= :pending (deref f 0 :pending)))
                                              remaining))]
                (deref f 100 :pending))
              (recur)))))))

(defn- before-tool-hook-result
  "Run the before-tool-call hook if registered (pi: beforeToolCall).
   Returns nil to allow, {:block true :reason ...} to block, or
   {:args transformed-args} to rewrite the call's arguments. A throwing
   hook blocks with the error message as reason. A blocked result may
   carry :terminate true — the run stops after this batch when EVERY
   finalized call in it carries the hint (pi: ToolCallEventResult.terminate)."
  [agent tc-id tc-name tc-args assistant-msg]
  (when-let [hook @(:before-tool-call agent)]
    (try
      (hook {:assistant-message assistant-msg
             :tool-call-id tc-id
             :tool-name tc-name
             :args tc-args})
      (catch Exception e
        {:block true
         :reason (str "before-tool-call hook error: " (ex-message e))}))))

(defn- after-tool-hook-result
  "Run the after-tool-call hook if registered (pi: afterToolCall), merging any
   returned :content / :is-error overrides into the result. A throwing hook
   turns the result into an error."
  [agent tc-id tc-name tc-args result assistant-msg]
  (if-let [hook @(:after-tool-call agent)]
    (try
      (let [hook-result (hook {:assistant-message assistant-msg
                               :tool-call-id tc-id
                               :tool-name tc-name
                               :args tc-args
                               :result result
                               :is-error (:is-error result false)})]
        (if hook-result
          (cond-> result
            (:content hook-result) (assoc :content (:content hook-result))
            (contains? hook-result :is-error) (assoc :is-error (:is-error hook-result)))
          result))
      (catch Exception e
        {:content (str "after-tool-call hook error: " (ex-message e))
         :is-error true}))
    result))

(defn- tool-on-update
  "Streaming callback for a tool execution (pi: tool onUpdate) — emits
   :tool-execution-update with partial content so the UI can show live
   output (e.g. bash) while the tool is still running."
  [agent tc-id]
  (fn [partial]
    (when-let [content (:content partial)]
      (emit agent {:type :tool-execution-update
                   :tool-call-id tc-id
                   :content content
                   :is-partial true}))))

(defn- execute-tool-calls-parallel!
  "Execute tool calls concurrently (pi: executeToolCallsParallel).
   Preparation (start events + before hooks) is sequential; execution is
   concurrent; tool-execution-end events fire in completion order; results
   are appended to context in source order. Returns results in source order."
  [agent tool-calls assistant-msg & [append?]]
  (let [prepared (mapv (fn [tc]
                         (let [tc-id (:id tc)
                               tc-name (:name tc)
                               tc-args (:arguments tc)]
                           (emit agent {:type :tool-execution-start
                                        :tool-call-id tc-id
                                        :tool-name tc-name
                                        :args tc-args})
                           (let [before (before-tool-hook-result agent tc-id tc-name tc-args assistant-msg)]
                             (cond
                               (:block before)
                               (assoc tc :kmet/blocked
                                      (cond-> {:content (or (:reason before) "Tool execution was blocked")
                                               :is-error true}
                                        (:terminate before) (assoc :terminate true)))

                               (contains? before :args)
                               (assoc tc :arguments (:args before))

                               :else tc))))
                       tool-calls)
        pending (filterv #(not (contains? % :kmet/blocked)) prepared)
        ;; pi: tools receive the session AbortSignal — Escape during a tool
        ;; call must kill the child process, not just abandon the future.
        ;; Each future records its own completion (the swap! runs before the
        ;; future is realized), so `completion-order` holds true completion
        ;; order — a hash-map can't express it, and iterating one gives
        ;; arbitrary tool-execution-end event order.
        completion-order (atom [])
        ;; The cancel signal comes from the run-level binding in run-agent-turn
        ;; (conveyed into these futures), so no per-future binding needed.
        futures (into {} (map (fn [tc]
                                [(:id tc)
                                 (future (let [tc-id (:id tc)
                                               result (tools/execute-tool (:name tc) (:arguments tc)
                                                                          {:on-update (tool-on-update agent tc-id)
                                                                           :signal (:signal agent)
                                                                           :ctx (extensions/build-extension-context)})]
                                           (swap! completion-order conj [tc-id result])
                                           result))])
                              pending))
        raw-results (await-all-tool-results! futures)
        finalized (into {}
                        (map (fn [tc]
                               (let [tc-id (:id tc)
                                     raw (or (:kmet/blocked tc) (get raw-results tc-id))
                                     result (after-tool-hook-result agent tc-id (:name tc) (:arguments tc) raw assistant-msg)]
                                 [tc-id result])))
                        prepared)]
    ;; tool-execution-end in completion order: blocked first (prep order), then completion order
    (doseq [tc prepared :when (contains? tc :kmet/blocked)]
      (let [result (get finalized (:id tc))]
        (emit agent {:type :tool-execution-end
                     :tool-call-id (:id tc)
                     :tool-name (:name tc)
                     :args (:arguments tc)
                     :result result
                     :is-error (:is-error result false)})))
    (doseq [[tc-id _] @completion-order]
      (let [tc (first (filter #(= (:id %) tc-id) prepared))
            result (get finalized tc-id)]
        (emit agent {:type :tool-execution-end
                     :tool-call-id tc-id
                     :tool-name (:name tc)
                     :args (:arguments tc)
                     :result result
                     :is-error (:is-error result false)})))
    ;; context + session in source order (skipped when the caller appends —
    ;; the repeat-loop guard path appends everything itself in source order)
    (when (not= false append?)
      (doseq [tc prepared]
        (let [result (get finalized (:id tc))
              result-msg (tool-result-message (:id tc) (:name tc) result)]
          (swap! (:messages agent) conj result-msg)
          (when (:session agent)
            (session/append-entry (:session agent) result-msg)))))
    ;; pi: terminate stops the run after this batch — only when EVERY
    ;; finalized call (blocked ones carry the hint, executed ones don't)
    {:results (mapv #(get finalized (:id %)) tool-calls)
     :terminate (and (seq finalized)
                     (every? #(true? (:terminate %)) (vals finalized)))}))

(defn- execute-tool-calls-sequential!
  "Execute tool calls one at a time (pi: executeToolCallsSequential).
   Same hooks and events as the parallel path; results in source order."
  [agent tool-calls assistant-msg & [append?]]
  (let [tool-results (atom [])]
    (doseq [tc tool-calls]
      (let [tc-id (:id tc)
            tc-name (:name tc)
            tc-args (:arguments tc)]
        (emit agent {:type :tool-execution-start
                     :tool-call-id tc-id
                     :tool-name tc-name
                     :args tc-args})
        (let [before (before-tool-hook-result agent tc-id tc-name tc-args assistant-msg)
              result (if (:block before)
                       (cond-> {:content (or (:reason before) "Tool execution was blocked")
                                :is-error true}
                         (:terminate before) (assoc :terminate true))
                       (run-tool-call!
                        (future (tools/execute-tool tc-name tc-args
                                                    {:on-update (tool-on-update agent tc-id)
                                                     :signal (:signal agent)
                                                     :ctx (extensions/build-extension-context)}))))
              result (after-tool-hook-result agent tc-id tc-name tc-args result assistant-msg)
              result-msg (tool-result-message tc-id tc-name result)]
          (when (not= false append?)
            (swap! (:messages agent) conj result-msg)
            (when (:session agent)
              (session/append-entry (:session agent) result-msg)))
          (swap! tool-results conj result)
          (emit agent {:type :tool-execution-end
                       :tool-call-id tc-id
                       :tool-name tc-name
                       :args tc-args
                       :result result
                       :is-error (:is-error result false)}))))
    ;; pi: terminate rides on blocked results — the batch stops the run
    ;; only when every call in it was blocked with :terminate
    {:results @tool-results
     :terminate (and (seq @tool-results)
                     (every? #(true? (:terminate %)) @tool-results))}))

(defn- execute-tool-calls!
  "Execute tool calls. If any target tool is :sequential the whole batch runs
   sequentially; otherwise tools run in parallel (pi: toolExecution mode).
   Runs before/after-tool-call hooks and returns results in source order.
   APPEND? — whether the executor appends results to the context/session
   itself (true by default). The repeat-loop guard path passes false and
   appends ALL results (suppressed + survivor) in source order itself, so a
   batch with suppressed calls keeps tool_use/tool_result ordering (strict
   providers reject misordered results)."
  [agent tool-calls assistant-msg & [append?]]
  (if (has-sequential-tool-call? tool-calls)
    (execute-tool-calls-sequential! agent tool-calls assistant-msg append?)
    (execute-tool-calls-parallel! agent tool-calls assistant-msg append?)))

;; ─── Tool call accumulator ─────────────────────────────────────────────────

(defn- make-tc-accumulator []
  (let [pending (atom {})]
    [(fn [tc]
       (let [idx (:index tc)
             name (:name tc)]
         (if name
           (swap! pending assoc idx
                  {:id (:id tc) :name name :arguments (or (:arguments tc) "")})
           (swap! pending update-in [idx :arguments]
                  (fn [old]
                    (let [delta (or (:arguments tc) "")]
                      (cond
                        ;; Anthropic content_block_start seeds the tool's input as a
                        ;; map ({}); input_json_delta then streams the partial JSON as
                        ;; strings. pi keeps partialJson separate from arguments and
                        ;; re-parses at content_block_stop — the deltas REPLACE the
                        ;; map, never concat to it (pi: block.arguments =
                        ;; parseStreamingJson(partialJson)). A map old must therefore
                        ;; start the string accumulator fresh.
                        (map? old) delta
                        (nil? old) delta
                        :else (str old delta))))))))
     (fn []
       (let [result (into []
                          (for [[_idx {:keys [id name arguments]}] @pending]
                            {:id id :name name
                             :arguments (cond
                                          ;; Already-parsed map (Anthropic/Google input blocks)
                                          (map? arguments) arguments
                                          ;; OpenAI-style JSON string: pi's parseStreamingJson never
                                          ;; lets a raw string through — malformed/truncated JSON
                                          ;; (or JSON that parses to a non-map) degrades to {} so
                                          ;; tools fail validation instead of throwing
                                          ;; ClassCastException on assoc/merge of a string.
                                          (string? arguments)
                                          (let [parsed (try (json/parse-string arguments true)
                                                            (catch Exception _ nil))]
                                            (if (map? parsed) parsed {}))
                                          :else {})}))]
         (reset! pending {})
         result))]))

;; ─── LLM call wrapper ─────────────────────────────────────────────────────

(defn- resolve-endpoint
  "api-type + base-url overrides for the next LLM call (Phase 2: llm resolves
   the Model itself and derives api/base-url/thinking from it; only the
   agent-level overrides flow through here)."
  [agent]
  {:api-type (:api-type agent)
   :base-url (:base-url agent)})

(defn- call-llm
  "Send messages to LLM, return a promise that delivers {:text str :tool-calls [...] :stop-reason kw}.
   On failure delivers {:error ex :text str :thinking str :tool-calls [...] :usage map
   :thinking-signature str} — whatever partials arrived before the failure
   (persisted with the errored attempt; pi: a failed stream's partial becomes
   the final message with stopReason \"error\").
   Returns {:promise p :partials f}: P resolves with the delivered map;
   PARTIALS snapshots the buffers for cancel-turn so an aborted stream keeps
   what arrived before the abort.
   Calls on-text for text deltas during streaming.
   Applies the transform-context hook (pi: transformContext) to the conversation
   before the system prompt is prepended, and prefers the per-run
   system-prompt-override over the base system prompt."
  [agent api-key text-buf on-text on-thinking]
  (let [done-promise (promise)
        thinking-buf (atom "")
        ;; opaque provider signature over the reasoning block (anthropic
        ;; signature_delta / gemini thoughtSignature) — captured for same-model
        ;; replay; pi keeps it on the message's thinking content block
        sig-buf (atom nil)
        usage-buf (atom nil)
        [tc-add tc-flush] (make-tc-accumulator)
        ;; The thinking-loop guard's per-call stream cut. Tripping the
        ;; run-level cancel signal would abort the HTTP stream, but it
        ;; leaks into follow-up turns (they share the agent signal) — so
        ;; the guard's cancel rides on call-signal, an OR-view the stream
        ;; transport derefs each line (SSE) / before each poll (curl),
        ;; while tool futures keep the run-level agent signal untouched.
        ;; Read-only — the transport only derefs it, never reset!/add-watch.
        guard-trip (atom false)
        call-signal (reify clojure.lang.IDeref
                      (deref [_] (boolean (or @(:signal agent) @guard-trip))))
        provider @(:provider agent)
        ep (resolve-endpoint agent)
        system (or @(:system-prompt-override agent) @(:system agent))
        messages (if-let [tf @(:transform-context agent)]
                   (tf @(:messages agent))
                   @(:messages agent))
        ;; Display-only :info messages (injected by before-agent-start hooks)
        ;; never reach the provider — they exist for the UI/session only.
        messages (into [] (remove #(= :info (:role %))) messages)
        ;; Defensive: drop tool calls/results left dangling by a corrupted
        ;; session (process death between recording an assistant tool-call
        ;; message and its results) — strict providers reject those.
        messages (drop-incomplete-tool-calls messages)
        ;; pi: convertToLlmWithBlockImages — with :block-images on, strip
        ;; every image from the wire messages (the transcript and session
        ;; keep theirs). Read per request, so a mid-session toggle applies
        ;; to the next call.
        messages (if (:block-images @(:cfg agent))
                   (block-message-images messages)
                   messages)
        messages (if system
                   (into [{:role :system :content [{:type :text :text system}]}]
                         (vec messages))
                   (vec messages))]
    ;; Assistant message lifecycle: streaming begins
    (emit agent {:type :message-start
                 :message {:role :assistant :content []}})
    (llm/send-message
     {:provider provider
      :api-type (:api-type ep)
      :model @(:model agent)
      :api-key api-key
      :base-url (:base-url ep)
      :messages messages
      :tools (active-tools agent)
      :signal call-signal
      :idle-timeout-ms (:http-idle-timeout-ms @(:cfg agent))
      ;; Whole-request deadline enforced by the transport (HttpRequest.timeout
      ;; / curl --max-time) — pi: timeoutMs ?? httpIdleTimeoutMs. The idle
      ;; timeout above is the separate per-byte read deadline (undici
      ;; bodyTimeout) that resets on every received byte.
      :total-timeout-ms (llm-total-timeout-ms agent)
      :thinking @(:thinking agent)
      :session-id (some-> (:session agent) :id)
      :on-text (fn [t]
                 ;; Suppress stragglers after the promise resolved (e.g. the
                 ;; thinking-loop guard already settled the call — the stream
                 ;; aborts on the next line, but same-chunk remainders still
                 ;; dispatch). Without this they would append into a fresh
                 ;; streaming bubble after on-done finalized the turn.
                 (when-not (realized? done-promise)
                   (swap! text-buf str t)
                   (when on-text (on-text t))
                   (emit agent {:type :message-update
                                :message {:role :assistant
                                          :content [{:type :text :text @text-buf}]}
                                :delta {:type :text :content t}})))
      :on-thinking (fn [t]
                     ;; Same straggler suppression as :on-text (see above).
                     (when-not (realized? done-promise)
                       (swap! thinking-buf str t)
                       (when on-thinking (on-thinking t))
                       (emit agent {:type :message-update
                                    :message {:role :assistant
                                              :content []
                                              :thinking @thinking-buf}
                                    :delta {:type :thinking :content t}}))
                     ;; Thinking-loop guard: a repeated reasoning segment
                     ;; (>= 3× within the recent buffer) means the model is
                     ;; stuck in a degenerate reasoning loop — cut the stream
                     ;; instead of burning tokens. The per-call trip flag aborts
                     ;; the HTTP/SSE read on the next line; the guard delivers
                     ;; as an error so the loop's retry classifier sees it; it
                     ;; must NOT be retryable (terminal-error! path).
                     (when (and (:thinking-loop-guard-enabled @(:cfg agent))
                                (not (realized? done-promise))
                                (thinking-loop? @thinking-buf))
                       (reset! guard-trip true)
                       (deliver done-promise
                                {:error thinking-loop-error
                                 :text @text-buf
                                 :thinking (str/trim @thinking-buf)
                                 :tool-calls (tc-flush)
                                 :usage @usage-buf
                                 :thinking-signature @sig-buf})))
      ;; signatures arrive as whole blobs (possibly repeated across deltas) —
      ;; last-wins, never concatenated
      :on-signature (fn [sig]
                      (when (and sig (not (realized? done-promise)))
                        (reset! sig-buf sig)))
      :on-tool-call (fn [tc]
                      (when-not (realized? done-promise)
                        (tc-add tc)
                        (emit agent {:type :message-update
                                     :message {:role :assistant :content []}
                                     :delta (assoc (select-keys tc [:id :name :arguments :index])
                                                   :type :tool-call)})))
      :on-usage (fn [usage]
                  ;; Provider-native usage map — stored on the assistant
                  ;; message for session persistence (pi: message.usage).
                  (when-not (realized? done-promise)
                    (reset! usage-buf usage)))
      :on-done (fn [reason]
                 (let [tool-calls (tc-flush)]
                   (deliver done-promise
                            {:text @text-buf
                             :thinking (str/trim @thinking-buf)
                             :tool-calls tool-calls
                             :usage @usage-buf
                             :thinking-signature @sig-buf
                             :stop-reason reason})))
      :on-error (fn [e]
                  ;; Guard against double delivery: on the curl path the
                  ;; stream error and close!'s transport-failure report can
                  ;; both fire for one failure (e.g. abort! on idle timeout)
                  (when-not (realized? done-promise)
                    (deliver done-promise
                             {:error e
                              ;; Partials captured before the failure — recorded
                              ;; on the errored attempt (pi: the failed stream's
                              ;; partial becomes the final message)
                              :text @text-buf
                              :thinking (str/trim @thinking-buf)
                              :tool-calls (tc-flush)
                              :usage @usage-buf
                              :thinking-signature @sig-buf})))})
    ;; Snapshot closure — evaluated by cancel-turn at abort time
    {:promise done-promise
     :partials (fn []
                 {:text @text-buf
                  :thinking (str/trim @thinking-buf)
                  :tool-calls (tc-flush)
                  :usage @usage-buf
                  :thinking-signature @sig-buf})}))

;; ─── Queues ────────────────────────────────────────────────────────────────

(defn steer!
  "Queue a user message for mid-turn injection.
   The agent loop polls the steering queue between turns (after tool results,
   before the next LLM call) and injects queued messages into the context."
  [agent text]
  (swap! (:steering agent) conj text)
  (emit agent {:type :queue-update
               :steering @(:steering agent)
               :follow-up @(:follow-up agent)})
  nil)

(defn follow-up!
  "Queue a user message to be processed after the current run settles.
   The outer loop drains the follow-up queue when the inner loop finishes and
   continues the run with the queued messages."
  [agent text]
  (swap! (:follow-up agent) conj text)
  (emit agent {:type :queue-update
               :steering @(:steering agent)
               :follow-up @(:follow-up agent)})
  nil)

(defn clear-queues!
  "Drop all pending steering and follow-up messages."
  [agent]
  (reset! (:steering agent) [])
  (reset! (:follow-up agent) [])
  (emit agent {:type :queue-update :steering [] :follow-up []})
  nil)

(defn queued-messages
  "Return {:steering [...] :follow-up [...]} of currently queued messages."
  [agent]
  {:steering @(:steering agent)
   :follow-up @(:follow-up agent)})

(defn has-queued-messages?
  "True if either queue has pending messages."
  [agent]
  (or (seq @(:steering agent))
      (seq @(:follow-up agent))))

;; ─── Bash result recording ────────────────────────────────────────────────

(defn add-bash-result!
  "Record a !/!! bash execution result in the agent context and session.
   While the agent is streaming the entry is deferred (pi: recordBashResult
   queues pending bash messages to preserve tool_use/tool_result ordering)
   and flushed by run-agent-turn's finally once the run settles."
  [agent command result exclude-from-context?]
  (let [entry (session/make-bash-entry command result exclude-from-context?)]
    (if (contains? #{:thinking :executing} @(:status agent))
      (swap! (:pending-bash agent) conj entry)
      (do (swap! (:messages agent) conj entry)
          (when-let [sess (:session agent)]
            (session/append-entry sess entry))))
    nil))

(defn flush-pending-bash-messages!
  "Add queued bash results (recorded while the agent was streaming) to the
   agent context and session (pi: _flushPendingBashMessages). Called by
   run-agent-turn's finally so every exit path — settle, error, timeout, or
   cancel — flushes them. Drains entries queued mid-flush via CAS."
  [agent]
  (loop []
    (let [pending @(:pending-bash agent)]
      (when (seq pending)
        (doseq [entry pending]
          (swap! (:messages agent) conj entry)
          (when-let [sess (:session agent)]
            (session/append-entry sess entry)))
        (when-not (compare-and-set! (:pending-bash agent) pending [])
          (recur))))))

(defn add-context-message!
  "Inject a message into the agent's in-memory context without persisting it
   (pi: custom messages flow through the agent loop — kmet extensions
   persist a :custom-message entry via the session and inject the projection
   here). Emits :message-start so the TUI renders it (the display flag is
   honored by the TUI handler). String :content is normalized to a text
   block, matching the canonical kmet message format."
  [agent msg]
  (let [content (:content msg)
        content (cond
                  (string? content) [{:type :text :text content}]
                  (nil? content) []
                  :else content)
        msg (assoc msg :content content)]
    (swap! (:messages agent) conj msg)
    (emit agent {:type :message-start :message msg})))

;; ─── Agent run ─────────────────────────────────────────────────────────────

(declare resolve-api-key)

(defn- summarize!
  "LLM summarization of the pre-cut entries (pi: generateSummaryWithUsage).
   Returns {:summary str :usage usage-map} — usage is the summarization
   call's cost-attached provider usage, recorded on the compaction entry so
   the footer totals include it (pi: CompactionEntry.usage) — or nil when no
   API key is available, the call fails/times out/returns empty, or the run's
   cancel signal fired mid-call (the signal watcher delivers nil so
   cancellation doesn't wait for the stream to die)."
  [agent prep & [custom-instructions]]
  (let [provider @(:provider agent)
        ep (resolve-endpoint agent)
        api-key (resolve-api-key agent)
        ;; pi: the summarization call caps its output at
        ;; min(floor(0.8 * reserveTokens), model.maxTokens) — the summary
        ;; must never consume the context budget or overflow the window
        max-tokens (let [reserve (or (:compact-reserve-tokens agent) 16384)]
                     (when-let [mrec (models/get-model provider @(:model agent))]
                       (min (long (Math/floor (* 0.8 reserve)))
                            (or (:max-tokens mrec) (long (Math/floor (* 0.8 reserve)))))))]
    ;; ambient-auth providers (google-vertex ADC, amazon-bedrock AWS
    ;; credentials) resolve no api-key — configured? covers them
    (when (or api-key (auth/configured? provider))
      (let [done (promise)
            text-buf (atom "")
            usage-buf (atom nil)
            signal (:signal agent)
            msgs (compaction/summarization-messages
                  (:messages prep) (:previous-summary prep) custom-instructions)]
        (llm/send-message
         {:provider provider
          :api-type (:api-type ep)
          :model @(:model agent)
          :api-key api-key
          :base-url (:base-url ep)
          :messages msgs
          :signal signal
          :idle-timeout-ms (:http-idle-timeout-ms @(:cfg agent))
          :session-id (some-> (:session agent) :id)
          :cache-retention :none
          :max-tokens max-tokens
          :on-text (fn [t] (swap! text-buf str t))
          :on-usage (fn [u] (reset! usage-buf u))
          :on-done (fn [_] (deliver done @text-buf))
          :on-error (fn [_] (when-not (realized? done) (deliver done nil)))})
        ;; Cancel watch: abort the deref the moment the signal fires. The
        ;; stream may not deliver an event on cancel (killed curl), so this
        ;; is what makes escape abort compaction promptly.
        (add-watch signal :kmet/summarize-cancel
                   (fn [_ _ _ v] (when v (deliver done nil))))
        (when @signal (deliver done nil))
        (let [result (try (deref done 120000 :timeout)
                          (finally (remove-watch signal :kmet/summarize-cancel)))]
          (when (and (string? result) (seq result))
            {:summary result :usage @usage-buf}))))))

(defn generate-branch-summary
  "LLM summary of abandoned branch entries for tree navigation (pi:
   generateBranchSummary). Returns {:summary str :usage usage-map} with the
   branch preamble applied (usage = the summarization call's cost-attached
   provider usage, recorded on the branch-summary entry so the footer totals
   include it — pi: BranchSummaryEntry.usage), {:aborted true} when the
   signal fired mid-call, or nil when no API key is available or the call
   fails/times out/returns empty. SIGNAL (optional, defaults to the agent's
   cancel signal) aborts the call. REPLACE-INSTRUCTIONS? (pi:
   replaceInstructions) makes CUSTOM-INSTRUCTIONS replace the builtin
   branch-summary prompt instead of being appended."
  [agent entries & [custom-instructions signal replace-instructions?]]
  (let [provider @(:provider agent)
        ep (resolve-endpoint agent)
        api-key (resolve-api-key agent)
        ;; pi: the branch/turn-prefix summarization caps its output at
        ;; min(floor(0.5 * reserveTokens), model.maxTokens) — a smaller
        ;; budget than context compaction
        max-tokens (let [reserve (or (:compact-reserve-tokens agent) 16384)]
                     (when-let [mrec (models/get-model provider @(:model agent))]
                       (min (long (Math/floor (* 0.5 reserve)))
                            (or (:max-tokens mrec) (long (Math/floor (* 0.5 reserve)))))))]
    ;; ambient-auth providers (google-vertex ADC, amazon-bedrock AWS
    ;; credentials) resolve no api-key — configured? covers them
    (when (or api-key (auth/configured? provider))
      (let [done (promise)
            text-buf (atom "")
            usage-buf (atom nil)
            signal (or signal (:signal agent))
            msgs (compaction/branch-summary-messages
                  (vec (mapcat session/context-messages entries))
                  custom-instructions
                  replace-instructions?)]
        (llm/send-message
         {:provider provider
          :api-type (:api-type ep)
          :model @(:model agent)
          :api-key api-key
          :base-url (:base-url ep)
          :messages msgs
          :signal signal
          :idle-timeout-ms (:http-idle-timeout-ms @(:cfg agent))
          :session-id (some-> (:session agent) :id)
          :cache-retention :none
          :max-tokens max-tokens
          :on-text (fn [t] (swap! text-buf str t))
          :on-usage (fn [u] (reset! usage-buf u))
          :on-done (fn [_] (deliver done @text-buf))
          :on-error (fn [_] (when-not (realized? done) (deliver done nil)))})
        (add-watch signal :kmet/branch-summarize-cancel
                   (fn [_ _ _ v] (when v (deliver done nil))))
        (when @signal (deliver done nil))
        (let [result (try (deref done 120000 :timeout)
                          (finally (remove-watch signal :kmet/branch-summarize-cancel)))]
          (cond
            (and (nil? result) @signal) {:aborted true}
            (string? result) (when (seq result)
                               {:summary (str compaction/branch-summary-preamble result)
                                :usage @usage-buf})
            :else nil))))))

(defn- sync-context-after-compaction!
  "Rebuild the in-memory context from the compacted session (pi: the agent
   context is rebuilt from the session after compaction — buildContextEntries
   → [compaction, ...from first-kept-id])."
  [agent]
  (when-let [sess (:session agent)]
    (reset! (:messages agent)
            (drop-incomplete-tool-calls
             (vec (mapcat session/context-messages (session/build-context sess)))))))

(defn compact-context!
  "LLM-based compaction (pi: prepareCompaction → compact): summarize the
   pre-cut entries, append a compaction entry (append-only — summarized
   entries stay in the file, build-context excludes them), and rebuild the
   in-memory context to mirror it. Also the manual /compact path
   (pi: session.compact) — custom-instructions are appended to the
   summarization prompt, and a manual compaction (reason :manual) starts
   with a fresh abort state: Escape's run-cancel signal is cleared, so a
   stale one cannot abort it.

   Emits :compaction-start/:compaction-end around the work (pi:
   compaction_start/compaction_end); the end event carries :aborted true
   when the run's cancel signal fired mid-compaction (escape) or an
   extension's :session-before-compact handler returned {:cancel true}
   (pi: aborted compaction_end), in which cases the session is left
   untouched.

   Returns true when a compaction happened, false when there was nothing to
   compact (or compaction is already in progress, or an extension
   cancelled it), and :aborted when the user cancelled mid-compaction."
  [agent & [custom-instructions reason]]
  (if @(:compacting? agent)
    false
    (let [reason (if custom-instructions :manual (or reason :auto))]
      ;; A manual compaction is a fresh operation (pi: session.compact aborts
      ;; the current operation, then creates a new AbortController): clear the
      ;; run's cancel signal, which a previous Escape leaves set until the
      ;; next run — a stale true would abort the compaction immediately. The
      ;; auto paths keep it: an Escape at run start must still abort them.
      (when (= :manual reason)
        (reset! (:signal agent) false))
      (emit agent {:type :compaction-start :reason reason})
      (reset! (:compacting? agent) true)
      (try
        (let [result
              (if-let [sess (:session agent)]
                (if @(:signal agent)
                  :aborted
                  (let [entries (session/get-branch sess)
                        prep (compaction/prepare entries (or (:keep-recent-tokens agent) 20000))]
                    (if (or (nil? prep) (empty? (:messages prep)))
                      false
                      ;; pi: session_before_compact — extensions may cancel
                      ;; the compaction (a {:cancel true} result skips the
                      ;; summarization; compaction_start/compaction_end still
                      ;; fire, the end carrying :aborted true — pi parity)
                      (if (:cancel (emit agent {:type :session-before-compact
                                                :preparation prep
                                                :branch-entries entries
                                                :reason reason
                                                :will-retry false
                                                :signal (:signal agent)}))
                        ::cancelled
                        (if-let [summary-result (summarize! agent prep custom-instructions)]
                          (if @(:signal agent)
                          ;; cancelled during summarization — session unchanged
                            :aborted
                            (do (session/compact-with-summary! sess (:summary summary-result)
                                                               (:first-kept-id prep)
                                                               (cond-> {:tokens-before (:tokens-before prep)}
                                                                 (:usage summary-result)
                                                                 (assoc :usage (:usage summary-result))))
                                (sync-context-after-compaction! agent)
                                ;; Mirror the new context into the UI (pi: compaction_end → the
                                ;; interactive mode clears the chat and re-renders the compacted
                                ;; context, showing the compaction summary entry). Without this the
                                ;; transcript keeps the pre-compaction messages and the compaction is
                                ;; invisible until the session is reloaded.
                                (emit agent {:type :context-replaced :messages @(:messages agent)})
                                (debug/log "compacted session with LLM summary")
                                true))
                          (if @(:signal agent)
                            :aborted
                            (do (debug/log "Warning: summarization failed; compaction skipped")
                                :failed)))))))
                false)]
          (emit agent {:type :compaction-end
                       :reason reason
                       :aborted (or (= result :aborted)
                                    (= result ::cancelled))
                       :result (and (not= result :aborted)
                                    (not= result ::cancelled)
                                    (not= result :failed)
                                    result)
                       :error-message (when (= result :failed)
                                        "Context compaction failed: the summarization call did not return a summary.")
                       ;; Overflow compaction retries the interrupted turn
                       ;; (pi: _runAutoCompaction willRetry — compaction_end
                       ;; carries whether the run continues).
                       :will-retry (= reason :overflow)})
          ;; pi: session_compact_failed — fired when compaction fails or is
          ;; aborted (the summarization errored, or the run's cancel signal
          ;; fired mid-compaction).
          (when (or (= result :failed)
                    (= result :aborted)
                    (= result ::cancelled))
            (emit agent {:type :session-compact-failed
                         :reason reason
                         :error-message (when (= result :failed)
                                          "Context compaction failed: the summarization call did not return a summary.")
                         :aborted (or (= result :aborted)
                                      (= result ::cancelled))}))
          (if (= result ::cancelled) false result))
        (finally
          (reset! (:compacting? agent) false))))))

(defn maybe-compact!
  "Proactively compact before a run (pi: _checkCompaction — threshold case).
   Triggers when the measured context usage comes within reserve tokens of
   the model's context window (pi: contextTokens > contextWindow -
   reserveTokens) — the default trigger — or when an explicit
   :compact-token-threshold (estimated tokens) is configured. Gated by the
   :auto-compact flag (pi: autoCompact); overflow recovery is separate and
   always available. Returns true when compaction happened."
  [agent]
  ;; nil session OR auto-compact off → no proactive compaction
  (if-let [sess (and (:auto-compact @(:cfg agent)) (:session agent))]
    (let [context (session/build-context sess)
          branch (session/get-branch sess)
          token-threshold (:compact-token-threshold agent)
          window (:context-window @(:cfg agent))
          reserve (:compact-reserve-tokens agent)
          window-reserve (when window (- window reserve))
          ;; Measure the context that would be sent, not the whole file:
          ;; compaction is append-only, so the full branch never shrinks —
          ;; counting it would re-trigger compaction every turn after the
          ;; first one (pi: the threshold is evaluated against the context).
          measured (compaction/context-tokens branch)
          has-usage? (boolean (some compaction/assistant-usage-tokens branch))
          tokens (or measured (reduce + 0 (map compaction/estimate-tokens context)))]
      (if (or (and token-threshold (>= tokens token-threshold))
              ;; the window check requires a positive window-reserve and fresh
              ;; measured usage: right after a compaction the last measured
              ;; usage reflects the old context (context-tokens is nil), and
              ;; without any usage data the estimate is too unreliable (pi:
              ;; estimate with lastUsageIndex null → no compaction)
              (and window-reserve (pos? window-reserve)
                   has-usage? measured (>= measured window-reserve)))
        (let [result (compact-context! agent nil :threshold)]
          (and (not= :aborted result) result))
        false))
    false))

(defn- compact-for-overflow!
  "Force-compact after a context-overflow error so the retried call fits
   (pi: _checkCompaction — overflow case). Returns true when compaction
   happened."
  [agent]
  (when-let [sess (:session agent)]
    (when (pos? (count @(:entries sess)))
      (compact-context! agent nil :overflow))))

(defn- replace-context!
  "Replace the in-memory conversation, rebuild the session file to match, and
   emit :context-replaced so the UI can mirror the new context
   (pi: prepareNextTurnWithContext context replacement)."
  [agent messages]
  (let [msgs (drop-incomplete-tool-calls (vec messages))]
    (reset! (:messages agent) msgs)
    (when-let [sess (:session agent)]
      ;; Rebuild the session as a fresh linear branch mirroring the new
      ;; context — atomic (temp file + rename) so a crash mid-write can't
      ;; corrupt the file; serialized so a concurrent append (bash result)
      ;; can't interleave with the rewrite.
      (session/replace-entries! sess msgs))
    (emit agent {:type :context-replaced :messages msgs})))

(defn- apply-next-turn-update!
  "Apply a prepare-next-turn update map to the agent state.
   Supported keys: :model, :system, :thinking, :system-prompt-override, :context
   (:context replaces the conversation and rebuilds the session to match)."
  [agent update]
  (when update
    (when-let [m (:model update)]
      (let [previous @(:model agent)]
        (when-not (= previous m)
          (reset! (:model agent) m)
          (emit agent {:type :model-select :model m :previous-model previous :source :set}))))
    (when-let [s (:system update)] (reset! (:system agent) s))
    (when-let [th (:thinking update)]
      (let [model-rec (models/get-model @(:provider agent) @(:model agent))
            clamped (if (:reasoning model-rec)
                      (shared/clamp-thinking-level model-rec th)
                      th)]
        (reset! (:thinking agent) clamped)))
    (when-let [o (:system-prompt-override update)]
      (reset! (:system-prompt-override agent) o))
    (when-let [c (:context update)] (replace-context! agent c))))

(defn- after-turn!
  "Run post-turn hooks in pi order: prepareNextTurn then shouldStopAfterTurn.
   Returns true if the loop should stop."
  [agent turn-index assistant-msg tool-results]
  (when-let [f @(:prepare-next-turn agent)]
    (let [update (f {:turn-index turn-index
                     :message assistant-msg
                     :tool-results tool-results
                     :messages @(:messages agent)})]
      (apply-next-turn-update! agent update)))
  (boolean
   (when-let [f @(:should-stop-after-turn agent)]
     (f {:turn-index turn-index
         :message assistant-msg
         :tool-results tool-results
         :messages @(:messages agent)}))))

(defn- resolve-api-key
  "Resolve the API key for the agent's provider, preferring the dynamic
   get-api-key hook (pi: config.getApiKey, resolved per LLM call)."
  [agent]
  (if-let [kf @(:get-api-key agent)]
    (kf @(:provider agent))
    (cfg/get-api-key @(:provider agent))))

(defn- backoff-sleep!
  "Sleep delay-ms in 100ms increments, aborting early if the cancel signal is
   set. Returns true if the full delay elapsed, false if cancelled."
  [agent delay-ms]
  (let [end-ms (+ (System/currentTimeMillis) delay-ms)]
    (loop []
      (if @(:signal agent)
        false
        (let [remaining (- end-ms (System/currentTimeMillis))]
          (if (<= remaining 0)
            true
            (do (Thread/sleep (min 100 remaining))
                (recur))))))))

(defn- llm-total-timeout-ms
  "Total request deadline for one LLM call (pi: SDK timeoutMs ??
   httpIdleTimeoutMs — the whole-request wall-clock the transport enforces;
   the per-byte idle timeout is separate and resets on every received byte).
   Mirrors the transport's own resolution (call-llm → api builders): the
   configured :http-total-timeout-ms wins when positive, else the idle
   timeout, else no deadline (MAX_VALUE so the deref never fires early — the
   transport gets nil and waits forever, pi: httpIdleTimeoutMs 0 →
   effectively disabled)."
  [agent]
  (let [total (:http-total-timeout-ms @(:cfg agent))
        idle (or (:http-idle-timeout-ms @(:cfg agent)) 0)]
    (cond
      (and total (pos? total)) total
      (pos? idle) idle
      :else Integer/MAX_VALUE)))

(defn- normalize-llm-result
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

(defn- retry-decision
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

(defn- prepare-run!
  "Per-run setup (pi: _systemPromptOverride / _overflowRecoveryAttempted
   resets, the submitted user message, before-agent-start hook overrides +
   injected messages, proactive pre-run compaction)."
  [agent message images]
  (reset! (:system-prompt-override agent) nil)
  (reset! (:overflow-recovered agent) false)
  ;; Repeat-loop guard is per-run: a legit bash ls x2 in run 1 must not
  ;; count toward a trip in run 2
  (reset! (:loop-guard agent) {:window [] :suppressed 0})
  (when message
    (add-user-message! agent message images))
  (let [bas (extensions/apply-before-agent-start-hooks
             message @(:system agent))]
    (when (:system-prompt bas)
      (reset! (:system-prompt-override agent) (:system-prompt bas)))
    (doseq [m (:messages bas)]
      (add-custom-message! agent m)))
  (maybe-compact! agent))

(defn- terminal-error!
  "Non-retryable error or exhausted budget: close out any open retry span,
   surface to UI + bus, mark errored, end the run. The status atom returns
   to :idle — the run is fully over (pi: agent_settled fires in a finally
   after every run, success or error, and the session is idle); a sticky
   :error would make /compact, /reload and the extension :is-idle check
   refuse until the next successful run."
  [agent err on-error agent-end]
  (when (pos? @(:retry-count agent))
    (let [n @(:retry-count agent)]
      (emit agent {:type :auto-retry-end
                   :success false
                   :attempt n
                   :final-error err})
      (reset! (:retry-count agent) 0)))
  (when on-error (on-error err))
  (reset! (:status agent) :error)
  (emit agent {:type :error :message err})
  (agent-end err)
  (reset! (:status agent) :idle)
  (emit agent {:type :status :status :idle})
  {:aborted true})

(defn- tools-phase!
  "Execute a turn's tool calls (pi: assistant message → executing status →
   results → thinking → turn-end → mid-run compaction). Returns:
     false      — continue the inner loop
     :terminate — stop after this batch (every call blocked with :terminate)
     :loop-guard— repeat-loop guard tripped (caller settles the run)
     true       — an after-turn hook stopped the continuation

   Repeat-loop guard (kmet-specific): the batch is filtered through
   loop-guard-filter first. Suppressed calls get a synthetic guard result
   (transcript stays coherent — no dangling tool ids, and the user sees why
   in the tool box); survivors execute normally. When a batch is ALL
   suppressed — or cumulative suppressions reach 2×threshold (mixed
   alternating batches) — the guard trips. A successful write/edit resets
   the guard's memory of other calls (the world changed, so a repeated
   call observes new state); failed, blocked, or suppressed writes reset
   nothing, and each writer's own signature is kept."
  [agent t result]
  (let [assistant-msg (add-assistant-message! agent result)
        tool-calls (:tool-calls result)]
    (reset! (:status agent) :executing)
    (emit agent {:type :status :status :executing
                 :tool-calls tool-calls})
    (let [threshold (:loop-guard-threshold @(:cfg agent))
          guard-enabled? (:loop-guard-enabled @(:cfg agent))
          {:keys [suppressed survivors state]}
          (if guard-enabled?
            (loop-guard-filter @(:loop-guard agent) threshold tool-calls)
            {:suppressed [] :survivors tool-calls :state @(:loop-guard agent)})
          suppressed? (seq suppressed)
          guard-result (fn [tc]
                         {:content (str "Repeat-loop guard: " (:name tc)
                                        " called with identical args "
                                        threshold " times — suppressed.")
                          :is-error false})
          ;; Suppressed calls become synthetic results; survivors run through
          ;; the normal execution path (parallel/sequential). Results are
          ;; returned in source order: walk the original tool-calls and pick
          ;; the guard result for suppressed ones, the exec result for the
          ;; rest (exec-results is in the survivors' source order).
          ;; Executor does NOT append to context (append? false) — the guard
          ;; path appends ALL results (suppressed + survivor) in source order
          ;; below so tool_use/tool_result ordering stays strict-provider-safe
          exec-result (when (seq survivors)
                        (execute-tool-calls! agent survivors assistant-msg false))
          exec-results (or (:results exec-result) [])
          terminate? (:terminate exec-result)
          ;; Successful writes change the world, so earlier repeats observed
          ;; stale state: keep only the writers' own signatures (repeating
          ;; the identical write still trips) and clear the cumulative
          ;; count, so a test -> edit(ok) -> test cycle doesn't false-positive.
          ;; Failed, blocked, or suppressed writes change nothing.
          reset-sigs (if guard-enabled?
                       (into #{}
                             (keep (fn [[tc res]]
                                     (when (and (contains? loop-guard-reset-tools (:name tc))
                                                (not (:is-error res)))
                                       (loop-guard-signature (:name tc) (:arguments tc)))))
                             (map vector survivors exec-results))
                       #{})
          ;; Single state write: the pre-exec intermediate is unobservable
          ;; (nothing during execution reads the guard atom), so persist the
          ;; pruned-or-original state once.
          _ (reset! (:loop-guard agent)
                    (if (seq reset-sigs)
                      {:window (filterv reset-sigs (:window state))
                       :suppressed 0}
                      state))
          give-up? (and (empty? reset-sigs)
                        (or (and suppressed? (empty? survivors))
                            (>= (:suppressed state) (* 2 threshold))))
          suppressed-by-id (into {} (map (fn [tc] [(:id tc) (guard-result tc)]))
                                 suppressed)
          ;; exec-results corresponds to SURVIVORS positionally (the
          ;; executor returns results in source order)
          results-by-id (merge suppressed-by-id
                               (into {} (map (fn [tc res] [(:id tc) res])
                                             survivors exec-results)))
          results (mapv #(get results-by-id (:id %)) tool-calls)
          ;; Append ALL results to context/session in source order (the
          ;; executor skipped appends). Suppressed calls also get their
          ;; tool-execution start/end events here (the executor never saw
          ;; them); survivor events were already emitted by the executor —
          ;; only the context append is repeated here, in the right order.
          _ (doseq [tc tool-calls]
              (let [tc-id (:id tc) tc-name (:name tc) tc-args (:arguments tc)
                    res (get results-by-id (:id tc))
                    suppressed? (contains? suppressed-by-id tc-id)
                    result-msg (tool-result-message tc-id tc-name res)]
                (when suppressed?
                  (emit agent {:type :tool-execution-start
                               :tool-call-id tc-id :tool-name tc-name :args tc-args})
                  (emit agent {:type :tool-execution-end
                               :tool-call-id tc-id :tool-name tc-name :args tc-args
                               :result res
                               :is-error (:is-error res false)}))
                (swap! (:messages agent) conj result-msg)
                (when (:session agent)
                  (session/append-entry (:session agent) result-msg))))]
      (reset! (:status agent) :thinking)
      (emit agent {:type :status :status :thinking})
      (emit agent {:type :turn-end
                   :message assistant-msg
                   :tool-results results})
      ;; Proactive mid-run compaction (pi: after tool results, before next call)
      (maybe-compact! agent)
      ;; pi: after-turn hooks (prepareNextTurn/shouldStopAfterTurn) run
      ;; unconditionally — even when terminate stops the tool-call continuation
      (let [stop? (after-turn! agent t assistant-msg results)]
        (cond
          give-up? :loop-guard
          terminate? :terminate
          stop? true
          :else false)))))

(defn- final-phase!
  "Settle a final response (no tool calls): assistant message + turn-end.
   Returns whether an after-turn hook stopped the loop."
  [agent t result]
  (let [assistant-msg (add-assistant-message! agent result)
        tool-results []]
    (emit agent {:type :turn-end
                 :message assistant-msg
                 :tool-results tool-results})
    (after-turn! agent t assistant-msg tool-results)))

(defn run-agent-turn
  "Run the agent loop until it settles.
   agent    — AgentState record
   opts:
     :message  — optional initial user message string
     :images   — optional vector of image content blocks
                 ({:type :image :data base64 :mime-type str}) attached to
                 the initial user message (pi: image attachments)
     :on-text  — (fn [text-delta]) streaming text callback
     :on-done  — (fn [response-text]) final response callback
     :on-error — (fn [error]) error callback

   Loop structure (mirrors pi):
     outer: drain follow-up queue → continue inner
     inner: LLM call → tool execution → drain steering queue → repeat
            until no tool calls, no steering messages, and at least one
            turn has run.

   Cancellation: cancel-turn sets the signal and delivers {:cancelled true}
   to the in-flight LLM promise (active-call); the loop exits quietly
   (no on-error, no on-done).

   Emits lifecycle events (see kmet.app.event-bus) to the UI callback and the
   extension system via emit.
   Returns: future that completes when the agent run is done."
  [agent {:keys [message images on-text on-thinking on-done on-error]}]
  (reset! (:signal agent) false)
  (let [provider @(:provider agent)
        api-key (resolve-api-key agent)]
    (if (not (or api-key (auth/configured? provider)))
      (do (when message
            ;; The run cannot start, but the submitted message is still shown
            ;; in the chat (pi emits message_start for prompt messages before
            ;; running the loop). Display-only: not added to context or
            ;; session since no LLM call will consume it.
            (emit agent {:type :message-start
                         :message (user-message message images)}))
          (when on-error
            (on-error (str "No API key for " (name provider)
                           ". Set the key in ~/.kmet/agent/auth.edn or the appropriate environment variable.")))
          (future))
      ;; Bind the run's cancel signal for the whole run: it conveys into the
      ;; tool futures AND into synchronous extension code (custom tool
      ;; executes, event handlers) that calls tools/execute-tool, so Escape
      ;; cancels bash everywhere, not just in the loop's own tool futures.
      ;; The session-env thunk resolves per bash execution (pi: the execute
      ;; ctx), so a mid-run model/thinking change is reflected.
      (binding [bash-tool/*cancel-signal* (:signal agent)
                bash-tool/*session-env-fn*
                (fn []
                  (bash-tool/session-env {:session (:session agent)
                                          :provider @(:provider agent)
                                          :model @(:model agent)
                                          :thinking-level @(:thinking agent)}))]
        (future
          (try
            (let [msg-count-before (count @(:messages agent))
                  text-buf (atom "")
                  agent-end (fn [& [error]]
                              (emit agent {:type :agent-end
                                           :messages (subvec @(:messages agent) msg-count-before)
                                           :error error})
                              ;; pi: agent_settled fires in a finally block after
                              ;; every run — success, error, timeout, or abort —
                              ;; the agent is fully idle (agent-session.js).
                              (emit agent {:type :agent-settled}))]
              (prepare-run! agent message images)

              ;; Agent lifecycle: start
              (emit agent {:type :agent-start})
              (reset! (:status agent) :thinking)
              (emit agent {:type :status :status :thinking})

              ;; Outer loop: follow-up. No turn limit — the inner loop settles
              ;; only when the model stops calling tools and no messages are
              ;; queued (pi: runLoop while(true)).
              (loop [turn 0]
                ;; Inner loop: LLM → tools → steer → ... → settle
                (let [inner (loop [t turn prev-tool-calls [] must-run true]
                              (if (or @(:signal agent)
                                      (and (not must-run)
                                           (empty? prev-tool-calls)
                                           (empty? @(:steering agent))))
                                (if @(:signal agent)
                                  (do (reset! (:status agent) :idle)
                                      (emit agent {:type :status :status :idle})
                                      (agent-end)
                                      {:aborted true}) ;; cancelled — exit quietly
                                  {:settled t})
                                (let [steer-msgs (drain-queue! (:steering agent)
                                                               (:steering-mode @(:cfg agent)))]
                                  (doseq [m steer-msgs]
                                    (add-user-message! agent m))
                                  ;; Consumed messages left the queue — refresh the
                                  ;; pending display (pi: message_start → queue_update)
                                  (when (seq steer-msgs)
                                    (emit agent {:type :queue-update
                                                 :steering @(:steering agent)
                                                 :follow-up @(:follow-up agent)}))
                                  (emit agent {:type :turn-start :turn-index t})
                                  (let [{:keys [promise] :as call}
                                        (do (reset! text-buf "")
                                            (call-llm agent (resolve-api-key agent) text-buf on-text on-thinking))]
                                    (reset! (:active-call agent) call)
                                    (let [result (normalize-llm-result
                                                  (deref promise (llm-total-timeout-ms agent) :timeout))]
                                      (reset! (:active-call agent) nil)
                                      (cond
                                        (:cancelled result)
                                        (do (record-abandoned-attempt! agent result :aborted)
                                            (agent-end)
                                            {:aborted true})

                                        (= :timeout result)
                                        ;; The transport's own total deadline
                                        ;; (HttpRequest.timeout / curl --max-time)
                                        ;; normally fires first and delivers a
                                        ;; retryable :error; this is the deref
                                        ;; fallback when it was disabled or didn't
                                        ;; fire. Treat it as the same retryable
                                        ;; timeout error (pi: a timeout is a
                                        ;; stopReason-error that retries, then
                                        ;; surfaces after exhaustion) — never a
                                        ;; silent hard abort.
                                        (let [err (str "LLM call timed out after "
                                                       (llm-total-timeout-ms agent) "ms")]
                                          ;; The deref sentinel (:timeout) carries no
                                          ;; partials — synthesize an errored result so
                                          ;; the abandoned-attempt recording and the
                                          ;; retry classifier see a normal error map.
                                          (record-abandoned-attempt!
                                           agent (assoc {} :error err) :error)
                                          (let [{:keys [kind] :as action}
                                                (retry-decision
                                                 {:err err
                                                  :retry-count @(:retry-count agent)
                                                  :max-retries (:max-retries @(:cfg agent))
                                                  :base-delay-ms (:base-delay-ms @(:cfg agent))
                                                  :overflow-recovered @(:overflow-recovered agent)
                                                  :has-session (some? (:session agent))})]
                                            (if (= :backoff kind)
                                              (let [{:keys [attempt delay-ms max-attempts]} action]
                                                (reset! (:retry-count agent) attempt)
                                                (emit agent {:type :auto-retry-start
                                                             :attempt attempt
                                                             :max-attempts max-attempts
                                                             :delay-ms delay-ms
                                                             :error-message err})
                                                (if (backoff-sleep! agent delay-ms)
                                                  ;; Same turn, same context — no new user message
                                                  (recur t prev-tool-calls must-run)
                                                  ;; Cancelled during backoff
                                                  (do (emit agent {:type :auto-retry-end
                                                                   :success false
                                                                   :attempt attempt
                                                                   :final-error "Retry cancelled"})
                                                      (reset! (:retry-count agent) 0)
                                                      (reset! (:status agent) :idle)
                                                      (emit agent {:type :status :status :idle})
                                                      (agent-end)
                                                      {:aborted true})))
                                              (terminal-error! agent err on-error agent-end))))

                                        (:error result)
                                        (if (= thinking-loop-error (:error result))
                                          ;; Thinking-loop guard tripped: not a transient
                                          ;; error — settle the run with the explanation
                                          ;; (no auto-retry, no red error line; the
                                          ;; :loop-guard event shows a warning)
                                          (do (record-abandoned-attempt! agent result :error)
                                              (loop-guard-give-up!
                                               agent text-buf :thinking
                                               "Stopped: repeated reasoning detected (thinking-loop guard)")
                                              {:settled (inc t)})
                                          (let [err (:error result)
                                              ;; Persist the failed attempt before
                                              ;; classifying — session history only,
                                              ;; never the live context (pi:
                                              ;; _prepareRetry drops the errored
                                              ;; message from agent state while
                                              ;; keeping it in the file)
                                                _ (record-abandoned-attempt! agent result :error)
                                                {:keys [kind] :as action}
                                                (retry-decision
                                                 {:err err
                                                  :retry-count @(:retry-count agent)
                                                  :max-retries (:max-retries @(:cfg agent))
                                                  :base-delay-ms (:base-delay-ms @(:cfg agent))
                                                  :overflow-recovered @(:overflow-recovered agent)
                                                  :has-session (some? (:session agent))})]
                                            (case kind
                                            ;; Context overflow → compact once, then retry
                                            ;; (pi: overflow is compaction territory, not auto-retry)
                                              :overflow-recover
                                              (do (reset! (:overflow-recovered agent) true)
                                                  (compact-for-overflow! agent)
                                                  (recur t prev-tool-calls must-run))

                                            ;; Auto-retry with exponential backoff (pi: _prepareRetry)
                                              :backoff
                                              (let [{:keys [attempt delay-ms max-attempts]} action]
                                                (reset! (:retry-count agent) attempt)
                                                (emit agent {:type :auto-retry-start
                                                             :attempt attempt
                                                             :max-attempts max-attempts
                                                             :delay-ms delay-ms
                                                             :error-message err})
                                                (if (backoff-sleep! agent delay-ms)
                                                ;; Same turn, same context — no new user message
                                                  (recur t prev-tool-calls must-run)
                                                ;; Cancelled during backoff
                                                  (do (emit agent {:type :auto-retry-end
                                                                   :success false
                                                                   :attempt attempt
                                                                   :final-error "Retry cancelled"})
                                                      (reset! (:retry-count agent) 0)
                                                      (reset! (:status agent) :idle)
                                                      (emit agent {:type :status :status :idle})
                                                      (agent-end)
                                                      {:aborted true})))

                                            ;; Terminal error (non-retryable or retries exhausted)
                                              :terminal (terminal-error! agent err on-error agent-end))))

                                        :else
                                        (let [retried @(:retry-count agent)]
                                          ;; A retried LLM call succeeded — reset the budget
                                          (when (pos? retried)
                                            (reset! (:retry-count agent) 0)
                                            (emit agent {:type :auto-retry-end
                                                         :success true
                                                         :attempt retried}))
                                          (when @(:overflow-recovered agent)
                                            ;; A non-error message ends overflow recovery (pi resets on success)
                                            (reset! (:overflow-recovered agent) false))
                                          (let [tool-calls (:tool-calls result)]
                                            (if (seq tool-calls)
                                              (let [phase (tools-phase! agent t result)]
                                                (cond
                                                  (= :loop-guard phase)
                                                  ;; Repeat-loop guard tripped: settle the run
                                                  ;; with the explanation as the final text
                                                  (do (loop-guard-give-up!
                                                       agent text-buf :tool-calls
                                                       (str "Stopped: repeated identical tool calls "
                                                            "(threshold " (:loop-guard-threshold @(:cfg agent)) ")"))
                                                      {:settled (inc t)})
                                                  phase
                                                  {:settled (inc t)}
                                                  :else
                                                  (recur (inc t) tool-calls false)))
                                              (let [stop? (final-phase! agent t result)]
                                                (if stop?
                                                  {:settled (inc t)}
                                                  (recur (inc t) [] false))))))))))))]
                  (if (:aborted inner)
                    nil ;; aborted (error or cancel) — exit without follow-ups
                    (let [turn' (:settled inner)
                          ;; Outer: poll follow-up queue
                          follow-ups (drain-queue! (:follow-up agent)
                                                   (:follow-up-mode @(:cfg agent)))]
                      (if (seq follow-ups)
                        (do (doseq [m follow-ups]
                              ;; strings are user messages; maps are
                              ;; pre-injected custom messages (pi: the
                              ;; follow-up queue carries full messages,
                              ;; sendMessage queues custom messages there)
                              (if (map? m)
                                (add-context-message! agent m)
                                (add-user-message! agent m)))
                            (emit agent {:type :queue-update
                                         :steering @(:steering agent)
                                         :follow-up @(:follow-up agent)})
                            (reset! (:status agent) :thinking)
                            (emit agent {:type :status :status :thinking})
                            (recur turn'))
                        (do (reset! (:status agent) :idle)
                            (emit agent {:type :status :status :idle})
                            (agent-end)
                            (when on-done (on-done @text-buf)))))))))
            (catch Exception e
              (reset! (:status agent) :error)
              ;; Report to the UI first — the :error emit goes to the
              ;; extension event bus, which must not be able to skip the
              ;; user-visible error path.
              (when on-error (on-error (ex-message e)))
              (emit agent {:type :error :message (ex-message e)})
              ;; Same settle rule as terminal-error!: an unhandled exception
              ;; ends the run, and the agent is idle afterwards (pi:
              ;; agent_settled).
              (reset! (:status agent) :idle)
              (emit agent {:type :status :status :idle}))
            (finally
              ;; pi: _flushPendingBashMessages — bash results recorded while
              ;; streaming are queued to preserve tool_use/tool_result ordering
              ;; and land in the context/session once the run settles
              (flush-pending-bash-messages! agent))))))))

(defn cancel-turn
  "Cancel the current agent run: signal the LLM stream, drop queued messages,
   deliver the in-flight LLM call ({:cancelled true} plus the partials
   snapshot so the abandoned attempt keeps what arrived), return status to
   :idle."
  [agent]
  (reset! (:signal agent) true)
  (clear-queues! agent)
  (when-let [{:keys [promise partials]} @(:active-call agent)]
    (when-not (realized? promise)
      (deliver promise (merge {:cancelled true} (partials)))))
  (reset! (:status agent) :idle)
  (emit agent {:type :status :status :idle})
  nil)

;; ─── State helpers ─────────────────────────────────────────────────────────

(defn get-context [agent]
  @(:messages agent))

(defn restore-session-context!
  "Rebuild the agent's in-memory context from the session (pi: the session is
   the source of truth — buildSessionContext). build-context walks root→leaf
   through the latest compaction; compaction entries project to a :user
   summary message, :info/:session_info are metadata and excluded. Messages
   only — like pi's buildSessionContext consumers, the agent's model/thinking
   are left untouched here; the session-derived settings are applied on
   session load via apply-session-settings! (pi: sdk.ts createAgentSession
   restore logic). No-op without a session. Used when a session is
   resumed/continued (and on tree navigation / fork / clone, which rebuild
   the context without touching the model/thinking)."
  [agent]
  (when-let [sess (:session agent)]
    (reset! (:messages agent)
            (drop-incomplete-tool-calls
             (vec (mapcat session/context-messages (session/build-context sess)))))))

(defn apply-session-settings!
  "Apply the session-derived model/thinking to the agent state (pi: sdk.ts
   createAgentSession — the session restores its settings when loaded via
   resume/continue; tree navigation, fork and clone never touch them). Only
   recorded settings are applied:
   - model: only when it resolves to an authenticated model (pi: getModel +
     hasConfiguredAuth); otherwise the current model stays (pi falls back to
     the settings default — kmet's current model IS that default).
   - thinking: only when a :thinking-level-change entry is on the branch;
     without one the current level stays (pi: hasThinkingEntry guard — the
     settings default is kept, not pi's unrecorded \"off\" default).
   Returns true when anything changed."
  [agent]
  (when-let [sess (:session agent)]
    (let [{:keys [thinking-level model provider]}
          (session/derive-context-settings sess)
          authenticated-model? (some #(and (= provider (:provider %))
                                           (= model (:id %)))
                                     (models/get-available))
          thinking-recorded? (some #(= :thinking-level-change (:role %))
                                   (session/get-branch sess))
          changed (volatile! false)]
      (when authenticated-model?
        (reset! (:model agent) model)
        (reset! (:provider agent) provider)
        (vreset! changed true))
      (when thinking-recorded?
        ;; Clamp the restored thinking level to the current model's capabilities
        ;; (only for reasoning models; non-reasoning models keep the value as-is)
        (let [model-rec (models/get-model @(:provider agent) @(:model agent))
              clamped (if (:reasoning model-rec)
                        (shared/clamp-thinking-level model-rec thinking-level)
                        thinking-level)]
          (reset! (:thinking agent) clamped))
        (vreset! changed true))
      @changed)))

(defn set-model!
  "Set the active model, emitting :model-select and persisting a
   :model-change session entry (pi: setModel → appendModelChange)."
  [agent model]
  (let [previous @(:model agent)]
    (when-not (= previous model)
      (reset! (:model agent) model)
      (when-let [sess (:session agent)]
        (session/append-model-change! sess @(:provider agent) model))
      (emit agent {:type :model-select
                   :model model
                   :previous-model previous
                   :source :set}))))

(defn set-scoped-models!
  "Set the session scoped model list used by cycle-model! (pi:
   session.setScopedModels — the list holds \"provider/id\" full ids; empty
   = no scoping, cycle over all available models)."
  [agent models]
  (reset! (:scoped-models agent) (vec models)))

(defn init-scoped-models!
  "Seed the session scoped model list from the config at startup (pi:
   parsed.models ?? settingsManager.getEnabledModels → resolveModelScope at
   runtime creation): CLI/config :models patterns when set, else the settings
   :enabled-models patterns. Patterns resolve against the catalog; unresolved
   ones print a warning to stderr and are skipped (pi: no-match diagnostics).
   Nothing set leaves the list empty — cycling falls back to all available
   models and /model shows no scope toggle. Returns AGENT."
  [agent config]
  (when-let [patterns (or (seq (:models config))
                          (seq (cfg/get-enabled-models config)))]
    (let [{:keys [models warnings]}
          (resolver/resolve-model-scope-models patterns (models/get-models))]
      (doseq [w warnings]
        (binding [*out* *err*] (println "Warning:" w)))
      (set-scoped-models!
       agent (mapv (fn [m] (str (name (:provider m)) "/" (:id m))) models))))
  agent)

(defn set-block-images!
  "Block images from provider calls live (pi: setBlockImages) — the next
   request strips every image block (see block-message-images); the
   transcript and the stored session keep theirs."
  [agent blocked?]
  (swap! (:cfg agent) assoc :block-images (boolean blocked?)))

(defn set-auto-compact!
  "Toggle proactive compaction live (pi: setAutoCompact); overflow recovery
   is unaffected."
  [agent enabled?]
  (swap! (:cfg agent) assoc :auto-compact (boolean enabled?)))

(defn set-http-idle-timeout-ms!
  "Set the SSE idle timeout live (pi: setHttpIdleTimeoutMs); 0 disables."
  [agent ms]
  (swap! (:cfg agent) assoc :http-idle-timeout-ms (long ms)))

(defn set-http-total-timeout-ms!
  "Set the whole-request total deadline live (pi: setHttpIdleTimeoutMs — the
   transport's HttpRequest.timeout / curl --max-time); nil resets to 'use
   idle', 0 also falls back to idle (pi: timeoutMs ?? httpIdleTimeoutMs)."
  [agent ms]
  (swap! (:cfg agent) assoc :http-total-timeout-ms (some-> ms long)))

(declare switch-thinking-level set-thinking-level!)

(defn- resolve-scoped-model
  "Resolve a scoped-list entry (full \"provider/id\" or bare id) to a Model
   record. Bare ids (the legacy --models/`:models` form) prefer the current
   provider, then the unique cross-provider match."
  [entry current-provider]
  (let [s (str entry)
        slash (str/index-of s "/")]
    (if slash
      (models/get-model (keyword (subs s 0 slash)) (subs s (inc slash)))
      (or (models/get-model current-provider s)
          (first (filter #(= s (:id %)) (models/get-models)))))))

(defn- scoped-model-entries
  "Resolve scoped-list entries to available Model records (pi
   _cycleScopedModel — filtered to the available snapshot; entries with no
   auth drop out). Bare ids resolve against CURRENT-PROVIDER first."
  [entries current-provider]
  (let [available (set (map (fn [m] [(name (:provider m)) (:id m)])
                            (models/get-available)))]
    (into []
          (keep (fn [entry]
                  (let [m (resolve-scoped-model entry current-provider)]
                    (when (and m (contains? available [(name (:provider m)) (:id m)])) m))))
          entries)))

(defn cycle-model!
  "Cycle to the next/previous model in the scoped model list (pi: cycleModel
   → _cycleScopedModel / _cycleAvailableModel; kmet deviation — thinking
   follows the switch-thinking-level rank rule instead of pi's keep-with-
   clamp). The session scoped list (set via /scoped-models, seeded from
   config :models / settings :enabled-models) is used when non-empty;
   otherwise all available models cycle. Entries are filtered to
   authenticated models; a scoped entry may switch the provider. Returns the
   new model id, or nil when fewer than two models are available."
  [agent direction]
  (let [current-provider @(:provider agent)
        scoped (or (seq @(:scoped-models agent))
                   (mapv (fn [m] (str (name (:provider m)) "/" (:id m)))
                         (models/get-available)))
        models (scoped-model-entries scoped current-provider)]
    (when (> (count models) 1)
      (let [current [(name @(:provider agent)) @(:model agent)]
            idx (or (first (keep-indexed (fn [i m]
                                           (when (= current [(name (:provider m)) (:id m)])
                                             i))
                                         models))
                    -1)
            previous @(:model agent)
            next (nth models (mod (+ idx direction) (count models)))
            old-model (models/get-model current-provider previous)
            new-thinking (switch-thinking-level old-model next @(:thinking agent) nil)]
        (reset! (:provider agent) (:provider next))
        (reset! (:model agent) (:id next))
        (when-let [sess (:session agent)]
          (session/append-model-change! sess (:provider next) (:id next)))
        (set-thinking-level! agent new-thinking)
        (emit agent {:type :model-select
                     :model (:id next)
                     :previous-model previous
                     :source :cycle})
        (:id next)))))

(defn set-thinking-level!
  "Set the thinking level, persisting a :thinking-level-change session entry
   only when the level actually changes (pi: setThinkingLevel — only
   persisted on change)."
  [agent level]
  (when-not (= level @(:thinking agent))
    (reset! (:thinking agent) level)
    (when-let [sess (:session agent)]
      (session/append-thinking-level-change! sess level))
    (emit agent {:type :thinking-level-select :level level})))

(defn switch-thinking-level
  "Thinking level when switching OLD-MODEL → NEW-MODEL (kmet-specific rule;
   the rank mapping is a deliberate deviation from pi's keep-with-clamp):
   the current level's rank among the old model's supported thinking levels
   (highest = 1) maps to the same rank on the new model, clamped to the new
   model's level count — an old highest stays the new highest, the old
   second-highest becomes the new second-highest, and so on. With thinking
   :off, a reasoning NEW-MODEL jumps to its highest level (pi parity); a
   non-reasoning one stays :off. EXPLICIT-LEVEL wins when given (clamped to
   the new model). Levels the old model can't express are clamped to its
   range first; when there is no position to preserve (non-reasoning/unknown
   old model), falls back to clamping CURRENT-LEVEL to the new model."
  [old-model new-model current-level explicit-level]
  (cond
    explicit-level (shared/clamp-thinking-level new-model explicit-level)

    (= current-level :off)
    (if (:reasoning new-model)
      (last (shared/get-supported-thinking-levels new-model))
      :off)

    :else
    (let [old-think (vec (remove #{:off} (shared/get-supported-thinking-levels old-model)))
          new-think (vec (remove #{:off} (shared/get-supported-thinking-levels new-model)))
          old-effective (when (seq old-think)
                          (shared/clamp-thinking-level old-model current-level))
          i (when old-effective
              (first (keep-indexed (fn [j l] (when (= l old-effective) j)) old-think)))]
      (if (and i (seq new-think))
        (let [rank (- (count old-think) i)                ;; 1 = highest
              new-i (max 0 (min (dec (count new-think))
                                (- (count new-think) rank)))]
          (nth new-think new-i))
        (shared/clamp-thinking-level new-model current-level)))))


