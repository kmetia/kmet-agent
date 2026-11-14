(ns kmet.app.loop.completion
  "LLM request construction and the provider call.

   call-llm streams the provider response into the message buffers and
   normalizes the result; resolve-api-key prefers the dynamic :get-api-key
   hook (pi: config.getApiKey) over the provider key."
  (:require [clojure.string :as str]
            [kmet.libs.json :as json]
            [kmet.ai.message :as ai-message]
            [kmet.app.llm :as llm]
            [kmet.app.loop-guard :as loop-guard]
            [kmet.app.retry :as retry]
            [kmet.libs.concurrent :as concurrent]
            [kmet.app.loop.message :as message]
            [kmet.app.loop.state :as state]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.config :as cfg]))

(defn- append-tool-arguments
  "OLD plus DELTA. A map DELTA is a complete input block (Anthropic's
   content_block_start seed, Google's whole input) and replaces the
   accumulator. Otherwise a map OLD must start the string accumulator fresh:
   input_json_delta streams the partial JSON as strings, and pi re-parses it at
   content_block_stop (pi: block.arguments = parseStreamingJson(partialJson)),
   never concatenating onto the seed map."
  [old delta]
  (cond
    (map? delta) delta
    (map? old) delta
    :else (str (or old "") delta)))

(defn- add-tool-delta!
  "Merge one streamed tool-call delta into PENDING by index. A named delta
   fills a missing name; a repeated name keeps the first and appends
   arguments (pi: ensureToolCallBlock sets block.name only when empty and
   always appends). A blank name is a continuation, like pi's falsy
   `!block.name` guard."
  [pending {:keys [index id name arguments]}]
  (let [delta (or arguments "")
        named? (ai-message/tool-name? name)]
    (swap! pending
           (fn [calls]
             (let [call (or (get calls index)
                            {:id nil :name nil :arguments nil})
                   call (assoc call :id (or (:id call) id))
                   call (if (and named? (nil? (:name call)))
                          (assoc call :name name)
                          call)]
               (assoc calls index
                      (update call :arguments append-tool-arguments delta)))))))

(defn- make-tc-accumulator []
  (let [pending (atom {})]
    [(fn [tc] (add-tool-delta! pending tc))
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

(defn resolve-endpoint
  "api-type + base-url overrides for the next LLM call (Phase 2: llm resolves
   the Model itself and derives api/base-url/thinking from it; only the
   agent-level overrides flow through here)."
  [agent]
  {:api-type (:api-type agent)
   :base-url (:base-url agent)})

(defn resolve-api-key
  "Resolve the API key for the agent's provider, preferring the dynamic
   get-api-key hook (pi: config.getApiKey, resolved per LLM call)."
  [agent]
  (if-let [kf @(:get-api-key agent)]
    (kf @(:provider agent))
    (cfg/get-api-key @(:provider agent))))

(defn call-llm
  "Send messages to LLM, return a promise that delivers {:text str :tool-calls [...] :stop-reason kw}.
   On failure delivers {:error ex :text str :thinking str :tool-calls [...] :usage map
   :thinking-signature str} — whatever partials arrived before the failure
   (persisted with the errored attempt; pi: a failed stream's partial becomes
   the final message with stopReason \"error\").
   Returns {:promise p :partials f :abort g}: P resolves with the delivered map;
   PARTIALS snapshots the buffers for cancel-turn so an aborted stream keeps
   what arrived before the abort; ABORT cuts the stream for a caller that
   stopped waiting (the loop's deref deadline).
   Calls on-text for text deltas during streaming.
   Applies the transform-context hook (pi: transformContext) to the conversation
   before the system prompt is prepended, and prefers the per-run
   system-prompt-override over the base system prompt.
   REQUEST-TOOLS is the request's captured tool list (see
   execute-tool-calls!): the wire list and the dispatch that follows resolve
   against the same capture."
  [agent api-key text-buf on-text on-thinking request-tools]
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
        ;; Read-only (kmet.libs.concurrent/or-signal) — the transport only
        ;; derefs it, never reset!/add-watch.
        guard-trip (atom false)
        call-signal (concurrent/or-signal (:signal agent) guard-trip)
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
        messages (message/drop-incomplete-tool-calls messages)
        ;; pi: convertToLlmWithBlockImages — with :block-images on, strip
        ;; every image from the wire messages (the transcript and session
        ;; keep theirs). Read per request, so a mid-session toggle applies
        ;; to the next call.
        messages (if (:block-images @(:cfg agent))
                   (message/block-message-images messages)
                   messages)
        messages (if system
                   (into [{:role :system :content [{:type :text :text system}]}]
                         (vec messages))
                   (vec messages))]
    ;; Assistant message lifecycle: streaming begins
    (state/emit agent {:type :message-start
                       :message {:role :assistant :content []}})
    (llm/send-message agent
                      {:provider provider
                       :api-type (:api-type ep)
                       :model @(:model agent)
                       :api-key api-key
                       :base-url (:base-url ep)
                       :messages messages
                       :tools request-tools
                       :signal call-signal
      ;; Provider-event bridges (pi: context / before_provider_request / …):
      ;; read once for this attempt, so a handler installed mid-run reaches
      ;; the next request and this one keeps the hooks it started with.
                       :hooks (extensions/provider-hooks (:ctx agent))
                       :idle-timeout-ms (:http-idle-timeout-ms @(:cfg agent))
      ;; Whole-request deadline enforced by the transport (HttpRequest.timeout
      ;; / curl --max-time); the idle timeout above is separate and resets
      ;; on every received byte.
                       :total-timeout-ms (retry/llm-total-timeout-ms @(:cfg agent))
                       :thinking @(:thinking agent)
                       :cache-retention (cfg/get-cache-retention)
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
                                    (state/emit agent {:type :message-update
                                                       :message {:role :assistant
                                                                 :content [{:type :text :text @text-buf}]}
                                                       :delta {:type :text :content t}})))
                       :on-thinking (fn [t]
                     ;; Same straggler suppression as :on-text (see above).
                                      (when-not (realized? done-promise)
                                        (swap! thinking-buf str t)
                                        (when on-thinking (on-thinking t))
                                        (state/emit agent {:type :message-update
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
                                                 (loop-guard/thinking-loop? @thinking-buf))
                                        (reset! guard-trip true)
                                        (deliver done-promise
                                                 {:error loop-guard/thinking-loop-error
                                                  :text @text-buf
                                                  :thinking (str/trim @thinking-buf)
                                                  :tool-calls (tc-flush)
                                                  :usage @usage-buf
                                                  :thinking-signature @sig-buf})))
      ;; signatures arrive as whole blobs (possibly repeated across deltas) —
      ;; last-wins, never concatenated. A new signature also closes the
      ;; model's thinking block: it is the one explicit end marker a provider
      ;; streams (Anthropic), so the UI hides the live :stream-last tail at
      ;; the block boundary; models without one end the phase at their first
      ;; text/tool-call delta.
                       :on-signature (fn [sig]
                                       (when (and sig (not (realized? done-promise)))
                                         (when (and (seq sig) (not= sig @sig-buf))
                                           (state/emit agent {:type :message-update
                                                              :message {:role :assistant
                                                                        :content []
                                                                        :thinking @thinking-buf}
                                                              :delta {:type :signature}}))
                                         (reset! sig-buf sig)))
                       :on-tool-call (fn [tc]
                                       (when-not (realized? done-promise)
                                         (tc-add tc)
                                         (state/emit agent {:type :message-update
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
                  :thinking-signature @sig-buf})
     ;; the caller's cut for a call it abandons (the loop's deref deadline):
     ;; the stream transport derefs CALL-SIGNAL on every line (SSE) / before
     ;; each poll (curl), so tripping it stops the read on the next event
     :abort (fn [] (reset! guard-trip true))}))
