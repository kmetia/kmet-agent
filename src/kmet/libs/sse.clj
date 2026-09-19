(ns kmet.libs.sse
  "Server-Sent Events parsing and stream processing for LLM responses.
   Generic — no app concepts. Handles the OpenAI chat-completions / Mistral
   chat-completions / Google streamGenerateContent SSE formats, the
   Anthropic messages stream format, the OpenAI Responses event format, and
   the AWS Bedrock ConverseStream binary event-stream framing. In the JS
   world this
   is the eventsource-parser npm package; kept in-house because no
   Babashka-compatible Clojure equivalent exists."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [kmet.libs.hash :as hash]
            [kmet.libs.json :as json]))

(defn parse-sse-line
  "Parse one SSE line. Returns [event-name data] — exactly one is non-nil
   (:event or :data), both nil for blank/comment lines."
  [line]
  (cond
    (str/starts-with? line "event:") [(str/trim (subs line 6)) nil]
    (str/starts-with? line "data:")  [nil (str/trim (subs line 5))]
    :else [nil nil]))

(defn- openai-stop-reason
  "pi mapStopReason (openai-completions.ts): stop/end → :stop,
   length → :length, tool_calls/function_call → :tool-use, and
   content_filter/network_error/any unknown reason → :error with an
   error message. An odd finish_reason must not look like a normal empty
   completion — pi classifies it as an error so the retry machinery and
   the user-visible error path engage instead of silently recording an
   empty assistant turn."
  [reason]
  (cond
    (or (nil? reason) (= reason "stop") (= reason "end")) {:stop-reason :stop}
    (= reason "length") {:stop-reason :length}
    (or (= reason "tool_calls") (= reason "function_call")) {:stop-reason :tool-use}
    (= reason "content_filter") {:stop-reason :error :error-message "Provider finish_reason: content_filter"}
    (= reason "network_error") {:stop-reason :error :error-message "Provider finish_reason: network_error"}
    :else {:stop-reason :error :error-message (str "Provider finish_reason: " reason)}))

(defn parse-openai-event
  "Parse an OpenAI chat-completions SSE data payload into a vector of kmet
   events: :text, :thinking, :tool-call, :tool-call-args, :done (with
   :stop-reason), :delta, :usage, or :error.
   A single chunk can carry a content delta AND a reasoning delta — both are
   emitted (pi emits text_delta and thinking_delta for the same chunk; never
   an else-branch that assumes one field per chunk). Reasoning is read from
   the first non-empty of reasoning_content / reasoning / reasoning_text
   (pi: iterateOpenAiEvents reasoningFields — llama.cpp uses
   reasoning_content, other OpenAI-compatible endpoints use reasoning)."
  [data]
  (if (or (nil? data) (= data "[DONE]"))
    [{:type :done :stop-reason :stop}]
    (try
      (let [chunk (json/parse-string data true)
            choices (get chunk :choices [])
            first-choice (first choices)
            delta (get-in first-choice [:delta] {})
            finish (get-in first-choice [:finish_reason])
            tc-delta (get delta :tool_calls)
            events (volatile! [])]
        ;; Tool call deltas — a chunk can carry several (pi iterates all)
        (doseq [tc (seq tc-delta)]
          (let [fname (get-in tc [:function :name])
                fargs (get-in tc [:function :arguments])]
            (cond
              (some? fname)
              (vswap! events conj {:type :tool-call :id (:id tc)
                                   :name fname
                                   :arguments (or fargs "")
                                   :index (:index tc)})
              (some? fargs)
              (vswap! events conj {:type :tool-call-args :id (:id tc)
                                   :arguments fargs
                                   :index (:index tc)}))))
        ;; Content delta
        (let [content (get delta :content)]
          (when (and (string? content) (pos? (count content)))
            (vswap! events conj {:type :text :content content})))
        ;; Reasoning delta — first non-empty reasoning field (pi)
        (let [reasoning (some (fn [f]
                                (let [v (get delta f)]
                                  (when (and (string? v) (pos? (count v))) v)))
                              [:reasoning_content :reasoning :reasoning_text])]
          (when reasoning
            (vswap! events conj {:type :thinking :content reasoning})))
        ;; Final chunk with include_usage: no choices, only usage totals
        (when-let [usage (get chunk :usage)]
          (vswap! events conj {:type :usage :usage usage}))
        ;; finish_reason — terminal event, emitted LAST so any tool-call /
        ;; content deltas in the same chunk are processed before the run's
        ;; on-done flushes the tool-call accumulator (pi pushes one done
        ;; event after the whole stream is consumed)
        (when finish
          (vswap! events conj (merge {:type :done} (openai-stop-reason finish))))
        (if (seq @events)
          @events
          [{:type :delta :chunk chunk}]))
      (catch Exception e
        [{:type :error :message (str "Parse error: " (ex-message e))}]))))

(defn parse-anthropic-event
  "Parse an Anthropic messages SSE (event-name, data) pair into a kmet event
   map: :text, :thinking, :signature, :tool-call, :tool-call-args,
   :message-delta, :done, :ping, :content-block-start, or :unknown."
  [event data]
  (case event
    "message_start"
    (when data
      (let [d (json/parse-string data true)]
        {:type :usage :usage (get-in d [:message :usage])}))
    "content_block_start"
    (when data
      (let [block (json/parse-string data true)
            cb (:content_block block)]
        (case (:type cb)
          "tool_use"
          {:type :tool-call :id (:id cb) :name (:name cb)
           :arguments (:input cb {})
           ;; parallel tool_use blocks each carry their block index — the
           ;; tool-call accumulator keys on it (pi: blocks.findIndex by
           ;; event.index); without it sibling calls collapse into one
           :index (:index block)}
          {:type :content-block-start})))
    "content_block_delta"
    (when data
      (let [delta (json/parse-string data true)]
        (case (get-in delta [:delta :type])
          "text_delta"
          {:type :text :content (get-in delta [:delta :text])}
          "thinking_delta"
          {:type :thinking :content (get-in delta [:delta :thinking])}
          "signature_delta"
          {:type :signature :content (get-in delta [:delta :signature])}
          "input_json_delta"
          {:type :tool-call-args
           :arguments (get-in delta [:delta :partial_json])
           ;; the block index correlates args deltas to their tool_use
           ;; block (pi: input_json_delta → findIndex by event.index)
           :index (:index delta)}
          {:type :delta :delta delta})))
    "message_delta"
    (when data
      (let [d (json/parse-string data true)
            ;; pi mapStopReason: end_turn/stop_sequence/pause_turn → :stop;
            ;; max_tokens → :length; tool_use → :tool-use; refusal/sensitive
            ;; → :error (with the stop_details explanation, pi refusal).
            ;; message_stop alone only says "ended" — the real reason rides
            ;; on this event.
            reason (get-in d [:delta :stop_reason])
            refusal-explanation (get-in d [:delta :stop_details :explanation])]
        (cond-> {:type :message-delta}
          reason (assoc :stop-reason
                        (case reason
                          ("end_turn" "stop_sequence" "pause_turn") :stop
                          "max_tokens" :length
                          "tool_use" :tool-use
                          ("refusal" "sensitive") :error
                          reason))
          (and (= reason "refusal") refusal-explanation)
          (assoc :error-message refusal-explanation))))
    "error"
    {:type :error :message (or data "Anthropic stream error")}
    "message_stop"
    {:type :done :stop-reason :end-turn}
    "ping"
    {:type :ping}
    {:type :unknown :event event :data data}))

;; ─── OpenAI Responses stream (pi: api/openai-responses-shared.ts           ──
;;    processResponsesStream — ported to kmet's event vocabulary; the
;;    output-slot state (per output_index tool-call partial JSON) lives in
;;    the state atom owned by process-responses-stream) ─────────────────────

(declare stream-loop)

(defn- responses-tool-call-id
  "The stored tool call id for a responses function_call item: pi's
   `call_id|item_id` format so replay can split it back into the
   function_call_output call_id."
  [item]
  (str (or (:call_id item) "") "|" (or (:id item) "")))

(defn- responses-terminal-events
  "Events for the terminal response.completed / response.incomplete events
   (pi finalizeResponse): the provider-native usage (input_tokens includes
   cached/cache-write tokens — usage/entry-usage (kmet.ai.usage) subtracts them) and the
   stop reason. Completed responses with tool-call slots map :stop to
   :tool-use (pi: output.content has a toolCall). Non-max-output incomplete
   responses surface as an error (pi: stopReason 'error' + errorMessage).
   response.done is codex's terminal event (pi mapCodexEvents maps it to
   response.completed, status normalized — known statuses pass through);
   unknown/missing statuses error rather than silently ending the stream."
  [d state]
  (let [response (:response d)
        status (:status response)
        incomplete-reason (get-in response [:incomplete_details :reason])
        usage (:usage response)
        tool-calls? (seq (:slots @state))]
    (swap! state assoc :saw-terminal? true)
    (cond-> []
      usage (conj {:type :usage :usage usage})
      (and (= "completed" status) tool-calls?) (conj {:type :done :stop-reason :tool-use})
      (and (= "completed" status) (not tool-calls?)) (conj {:type :done :stop-reason :stop})
      (and (= "incomplete" status) (= "max_output_tokens" incomplete-reason))
      (conj {:type :done :stop-reason :length})
      (and (= "incomplete" status) (not= "max_output_tokens" incomplete-reason))
      (conj {:type :error
             :message (str "Response incomplete: " (or incomplete-reason "unknown reason"))})
      ;; unknown status (codex normalizeCodexStatus can strip values)
      (and (not (#{"completed" "incomplete"} status)) (seq status))
      (conj {:type :error :message (str "Response ended with status " status)})
      (and (not (#{"completed" "incomplete"} status)) (nil? status))
      (conj {:type :error :message "Response ended without a status"}))))

(defn- responses-failed-message
  "pi response.failed handling: the error code+message, else the
   incomplete_details reason."
  [d]
  (let [response (:response d)
        error (:error response)
        details (:incomplete_details response)]
    (cond
      error (str (or (:code error) "unknown") ": " (or (:message error) "no message"))
      details (str "incomplete: " (:reason details))
      :else "Unknown error (no error details in response)")))

(defn parse-responses-event
  "Parse one OpenAI Responses SSE (event-name, data) pair into a vector of
   kmet events: :text, :thinking, :tool-call (start, with the id/name),
   :tool-call-args (argument deltas), :usage, :done (with :stop-reason), or
   :error. STATE is the processor's atom — it owns the per-output-index
   tool-call slots (partial JSON accumulation, pi processResponsesStream's
   outputSlots) and the terminal-event flag.

   Text/thinking content arrives as deltas (response.output_text.delta,
   response.reasoning_*.delta); tool arguments stream via
   response.function_call_arguments.delta with a final .done that may
   extend the accumulated partial. function_call items are created on
   response.output_item.added; the id is `call_id|item_id` (pi), so the
   tool executor and session replay treat it opaquely."
  [event data state]
  (if (nil? data)
    []
    (try
      (let [d (json/parse-string data true)
            slots (:slots @state)
            add-slot! (fn [idx slot] (swap! state assoc-in [:slots idx] slot))]
        (case event
          "response.output_item.added"
          (let [item (:item d) idx (:output_index d)]
            (case (:type item)
              "function_call"
              (let [slot {:kind :tool-call
                          :id (responses-tool-call-id item)
                          :name (:name item)
                          :partial-json (or (:arguments item) "")}]
                (add-slot! idx slot)
                [{:type :tool-call :id (:id slot) :name (:name slot)
                  :arguments (or (:arguments item) "") :index idx}])
              []))

          "response.reasoning_summary_text.delta"
          [{:type :thinking :content (:delta d)}]
          "response.reasoning_summary_part.done"
          [{:type :thinking :content "\n\n"}]
          "response.reasoning_text.delta"
          [{:type :thinking :content (:delta d)}]
          "response.output_text.delta"
          [{:type :text :content (:delta d)}]
          "response.refusal.delta"
          [{:type :text :content (:delta d)}]

          "response.function_call_arguments.delta"
          (let [slot (get slots (:output_index d))]
            (if (and slot (= :tool-call (:kind slot)))
              (do (swap! state assoc-in [:slots (:output_index d) :partial-json]
                         (str (:partial-json slot) (:delta d)))
                  [{:type :tool-call-args :arguments (:delta d)
                    :index (:output_index d)}])
              []))

          "response.function_call_arguments.done"
          (let [slot (get slots (:output_index d))
                final (or (:arguments d) "")]
            (if (and slot (= :tool-call (:kind slot)))
              (let [previous (:partial-json slot)]
                (swap! state assoc-in [:slots (:output_index d) :partial-json] final)
                ;; The final arguments normally extend the accumulated partial
                ;; (pi: emit only the extension as a delta); when they don't
                ;; (complete arguments arrived in the item), nothing to add.
                (if (and (str/starts-with? final previous)
                         (pos? (- (count final) (count previous))))
                  [{:type :tool-call-args :arguments (subs final (count previous))
                    :index (:output_index d)}]
                  []))
              []))

          "response.output_item.done"
          ;; Text/thinking/tool-call content already streamed as deltas; the
          ;; final item only carries the same values (no signature replay in
          ;; kmet — sessions don't persist signatures).
          []

          "response.completed" (responses-terminal-events d state)
          "response.incomplete" (responses-terminal-events d state)
          "response.done" (responses-terminal-events d state)
          "response.failed"
          (do (swap! state assoc :saw-terminal? true)
              [{:type :error :message (responses-failed-message d)}])
          "error"
          [{:type :error :message (str "Error Code " (:code d) ": " (:message d))}]
          []))
      (catch Exception e
        [{:type :error :message (str "Parse error: " (ex-message e))}]))))

(defn- body->reader
  "Response :body to a java.io.Reader. io/reader wraps a user-implemented
   Reader in a delegating BufferedReader that drives its .read (and
   forwards .close), so both production InputStreams and proxy Readers
   (tests fail the read on demand) end up with .read/.readLine/.close."
  [body]
  (io/reader body))

(defn process-responses-stream
  "Read an OpenAI Responses stream response body, buffering multi-line event
   data (like process-anthropic-stream). Calls handler with each parsed
   event; the processor's output-slot state lives in an internal atom.
   signal (an atom) cancels the loop; errors via {:type :error} events;
   idle-timeout-ms is the per-byte idle timeout; abort-fn kills the transport
   on stall.

   Detects premature stream end: when the stream ends before a terminal
   event (response.completed / response.incomplete / response.failed) is
   received, reports {:type :error} (pi: 'OpenAI Responses stream ended
   before a terminal response event')."
  [response handler signal & [idle-timeout-ms abort-fn]]
  (try
    (let [rdr (body->reader (:body response))
          state (atom {:event-name nil :buf "" :slots {} :saw-terminal? false})
          saw-done (atom false)
          end-reason (stream-loop rdr idle-timeout-ms signal abort-fn
                                  (fn [line]
                                    (let [[ev data] (parse-sse-line line)
                                          {:keys [event-name buf]} @state]
                                      (cond
                                        ev (swap! state assoc :event-name ev)
                                        data (swap! state assoc :buf (str buf data))
                                        (and (empty? line) (seq buf))
                                        (do (doseq [event (parse-responses-event event-name buf state)]
                                              (when (= :done (:type event))
                                                (reset! saw-done true))
                                              (handler event))
                                            (swap! state assoc :event-name nil :buf ""))
                                        :else nil)))
                                  (fn
                                    ([] (handler {:type :error
                                                  :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                                                " ms (no data received)")}))
                                    ([e] (handler {:type :error
                                                   :message (str "Stream error: " (ex-message e))}))))]
      (when (and (= :eof end-reason) (not @saw-done) (not (:saw-terminal? @state))
                 (not (and signal @signal)))
        (handler {:type :error
                  :message "OpenAI Responses stream ended before a terminal response event"})))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (ex-message e))}))))

(defn- google-stop-reason
  "pi mapStopReasonString: STOP → :stop, MAX_TOKENS → :length, rest :error."
  [reason]
  (case reason
    "STOP" :stop
    "MAX_TOKENS" :length
    :error))

(defn- google-usage
  "pi: usage from GenerateContentResponse.usageMetadata (camelCase wire
   fields; cache read is the cached-content token count)."
  [u]
  {:input (- (or (:promptTokenCount u) 0) (or (:cachedContentTokenCount u) 0))
   :output (+ (or (:candidatesTokenCount u) 0) (or (:thoughtsTokenCount u) 0))
   :cache-read (or (:cachedContentTokenCount u) 0)
   :cache-write 0
   :reasoning (or (:thoughtsTokenCount u) 0)
   :total-tokens (or (:totalTokenCount u) 0)})

(defn parse-google-event
  "Parse a Google Generative AI streamGenerateContent SSE data payload (the
   REST wire format — camelCase fields) into a vector of kmet events:
   :text, :thinking (thought parts), :tool-call (functionCall parts with the
   full args object), :usage (per-chunk usageMetadata), :done (finishReason),
   or :error. A single chunk can yield several events (usage + parts +
   finish — pi reads all parts and usageMetadata per chunk).

   Google streams have no terminal sentinel: the last chunk carries
   finishReason, which process-google-stream uses for premature-end
   detection."
  [data]
  (if (or (nil? data) (str/blank? data))
    [{:type :delta :chunk {}}]
    (try
      (let [chunk (json/parse-string data true)
            candidate (first (:candidates chunk))
            parts (get-in candidate [:content :parts])
            finish (:finishReason candidate)
            usage (:usageMetadata chunk)
            part-events (keep (fn [[i p]]
                                (cond
                                  (:functionCall p)
                                  (let [fc (:functionCall p)]
                                    {:type :tool-call
                                     :id (or (:id fc) (str (:name fc) "_" (System/currentTimeMillis) "_" i))
                                     :name (:name fc)
                                     :arguments (:args fc {})
                                     :index i})
                                  (:thought p) (cond-> {:type :thinking :content (:text p)}
                                                 ;; pi google-shared: thoughtSignature rides on
                                                 ;; thought parts and is echoed back for same-model
                                                 ;; replay
                                                 (:thoughtSignature p)
                                                 (assoc :signature (:thoughtSignature p)))
                                  (contains? p :text) {:type :text :content (:text p)}))
                              (map-indexed vector parts))
            events (concat (when usage [{:type :usage :usage (google-usage usage)}])
                           part-events
                           (when finish [{:type :done :stop-reason (google-stop-reason finish)}]))]
        (if (seq events) (vec events) [{:type :delta :chunk chunk}]))
      (catch Exception e
        [{:type :error :message (str "Parse error: " (ex-message e))}]))))

(defn- make-idle-reader
  "Idle-timeout int reader over a no-arg read fn (undici bodyTimeout
   semantics — the clock measures time between received values and resets on
   every value). A daemon thread performs the blocking reads and hands ints
   to a queue; the caller polls with the idle deadline, so a stalled stream
   yields :timeout while clean EOF still yields -1 (EOF is only observable
   through a blocking read — ready()/available() stay 0 at EOF on
   java.net.http streams).

   read-fn — no-arg fn returning the next int (char or byte, -1 at EOF) or
   throwing.

   Returns [read stop thread]:
     read      — no-arg fn: next int, -1 at EOF, the read exception when the
                 underlying stream failed, :timeout on stall, :aborted when
                 the cancel signal fired mid-poll
     stop      — interrupts the daemon so a blocked read releases (required
                  before closing the reader, which deadlocks while a read is
                  in flight on java.net.http streams)
     thread    — the daemon thread, for join-before-close"
  [read-fn idle-ms signal]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        t (Thread.
           (fn []
             (try
               (loop []
                 (let [c (try (read-fn) (catch Exception e e))]
                   (.put q c)
                   ;; A read failure (e.g. HTTP/2 RST_STREAM) is put on the
                   ;; queue like data so the caller reports the real error
                   ;; instead of stalling to the idle deadline.
                   (when-not (or (neg? c) (instance? Exception c))
                     (recur))))
               (catch Exception _ nil))))]
    (.setDaemon t true)
    (.start t)
    [(fn []
       (let [deadline (+ (System/currentTimeMillis) idle-ms)]
         (loop []
           (let [v (.poll q 100 java.util.concurrent.TimeUnit/MILLISECONDS)]
             (cond
               (instance? Exception v) v
               (some? v) (int v)
               (and signal @signal) :aborted
               (>= (System/currentTimeMillis) deadline) :timeout
               :else (recur))))))
     (fn [] (.interrupt t))
     t]))

(defn- strip-trailing-cr
  "Drop a single trailing CR so the idle arm's .read loop matches .readLine
   on CRLF input: the loop splits on \\n only, so a kept CR would stop a
   blank separator line from reading empty and stall Anthropic/Responses
   event buffering. (The non-idle arm calls .readLine, which ends a line
   on \\r, \\n or \\r\\n itself.)"
  [s]
  (if (str/ends-with? s "\r") (subs s 0 (dec (count s))) s))

(defn- read-line-from
  "Assemble one line from an idle-reader read fn. Returns the line string,
   nil at EOF, the read exception when the stream failed, or the reader's
   :timeout/:aborted sentinel."
  [read-char]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (read-char)]
        (cond
          (instance? Exception c) c
          (or (= :timeout c) (= :aborted c)) c
          (neg? c) (when (pos? (.length sb)) (strip-trailing-cr (str sb))) ;; EOF mid-line
          (= c (int \newline)) (strip-trailing-cr (str sb))
          :else (do (.append sb (char c)) (recur)))))))

(defn- make-idle-line-reader
  "Line reader with a per-byte idle timeout (undici bodyTimeout semantics).
   Returns {:read-line f :stop f :thread t} — f produces lines, nil at EOF,
   the read exception when the stream failed, :timeout on stall, :aborted on
   cancel; stop releases a blocked read; thread is the daemon thread (nil
   when the idle timeout is disabled)."
  [rdr idle-ms signal]
  (if (and idle-ms (pos? idle-ms))
    (let [[read-char stop thread] (make-idle-reader #(.read rdr) idle-ms signal)]
      {:read-line #(read-line-from read-char)
       :stop stop
       :thread thread})
    ;; A real .readLine: io/reader's BufferedReader ends a line on \n, \r
    ;; or \r\n, so no char-assembly loop (and no CR strip) is needed when
    ;; the idle timeout is off.
    {:read-line #(try (.readLine rdr) (catch Exception e e))
     :stop (fn [])
     :thread nil}))

(defn- stream-loop
  "Shared driver for both stream processors. Reads lines via the idle-aware
   line reader and calls handle-line per line. On a stall the abort-fn (if
   any) kills the transport (curl) so the blocked read releases, then the
   error is reported via error-fn. Cleanup on every exit path: the daemon is
   interrupted and joined before the reader is closed — closing while a read
   is in flight deadlocks java.net.http streams, and process pipes (curl)
   need the abort-fn since interrupts don't unblock them.

   Returns :aborted when cancelled, :timeout when idle limit reached,
   :error when the underlying read failed (the exception is reported via
   error-fn), or :eof when the stream ended cleanly (nil read without
   cancel/timeout). The caller can distinguish clean EOF from premature
   termination."
  [rdr idle-timeout-ms signal abort-fn handle-line error-fn]
  (let [idle (make-idle-line-reader rdr idle-timeout-ms signal)]
    (try
      (loop []
        (let [line ((:read-line idle))]
          (cond
            (= :aborted line)
            (do ((:stop idle)) :aborted)
            (= :timeout line)
            (do ((:stop idle))
                (when abort-fn (abort-fn))
                (error-fn)
                :timeout)
            (nil? line) :eof
            (and signal @signal)
            (do ((:stop idle)) :aborted)
            ;; A transport read failure (RST_STREAM, connection reset, ...)
            ;; surfaces immediately — cancel wins if it raced with the error.
            ;; abort-fn kills a possibly-still-alive curl transport so
            ;; close!'s process deref doesn't block on it.
            (instance? Exception line)
            (do ((:stop idle))
                (when abort-fn (abort-fn))
                (error-fn line)
                :error)
            :else
            (do (handle-line line)
                (recur)))))
      (finally
        ((:stop idle))
        (when-let [t (:thread idle)]
          (.join t 2000))
        ;; close can fail on a transport that already errored — the error
        ;; path owns the resource, so it must not surface as a second event
        ;; behind the real error.
        (try (.close rdr) (catch Exception _ nil))))))

(defn- process-data-stream
  "Shared driver for SSE streams whose events arrive on data: lines (OpenAI
   chat-completions, Google streamGenerateContent, Mistral chat-completions):
   parses each payload via parse-fn and dispatches events to handler.
   terminal? marks the event that proves the stream completed (premature-end
   detection); end-message is reported when the stream ends without one.
   signal, idle-timeout-ms, abort-fn as in process-openai-stream."
  [response handler signal parse-fn terminal? end-message idle-timeout-ms abort-fn]
  (try
    (let [rdr (body->reader (:body response))
          saw-terminal (atom false)
          end-reason (stream-loop rdr idle-timeout-ms signal abort-fn
                                  (fn [line]
                                    (let [[_ data] (parse-sse-line line)]
                                      (when data
                                        (doseq [event (parse-fn data)]
                                          (when (terminal? event) (reset! saw-terminal true))
                                          (handler event)))))
                                  (fn
                                    ([] (handler {:type :error
                                                  :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                                                " ms (no data received)")}))
                                    ;; Transport read failure (RST_STREAM, connection
                                    ;; reset, ...) — the accurate error surfaces instead
                                    ;; of a misleading idle timeout.
                                    ([e] (handler {:type :error
                                                   :message (str "Stream error: " (ex-message e))}))))]
      (when (and (= :eof end-reason) (not @saw-terminal) (not (and signal @signal)))
        (handler {:type :error :message end-message})))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (ex-message e))}))))

(defn process-openai-stream
  "Read an OpenAI stream response body line by line, calling handler with
   each parsed event. handler receives one arg per event. signal (an atom)
   cancels the loop when set to true. Errors are reported via
   {:type :error} events. idle-timeout-ms — per-byte idle timeout (undici
   bodyTimeout semantics); nil or non-positive disables it. abort-fn (optional)
   — called on idle timeout to kill the transport so a blocked read releases.

   Detects premature stream end: when the stream ends before [DONE] is
   received, reports {:type :error} (pi: iterateOpenAiEvents throws
   'Stream ended without finish_reason')."
  [response handler signal & [idle-timeout-ms abort-fn]]
  (process-data-stream response handler signal parse-openai-event
                       #(= :done (:type %))
                       "Stream ended before a terminal response event"
                       idle-timeout-ms abort-fn))

(defn process-anthropic-stream
  "Read an Anthropic stream response body, buffering multi-line event data.
   Calls handler with each parsed event. signal (an atom) cancels the loop.
   Errors are reported via {:type :error} events. idle-timeout-ms — per-byte
   idle timeout (undici bodyTimeout semantics); nil or non-positive disables
   it. abort-fn (optional) — called on idle timeout to kill the transport so
   a blocked read releases.

   Detects premature stream end: when the stream ends before message_stop is
   received, reports {:type :error} (pi: iterateAnthropicEvents throws
   'Anthropic stream ended before message_stop')."
  [response handler signal & [idle-timeout-ms abort-fn]]
  (try
    (let [rdr (body->reader (:body response))
          state (atom {:event-name nil :buf ""})
          ;; pi: message_delta carries the real stop_reason (tool_use /
          ;; max_tokens / ...); message_stop only says the stream ended.
          ;; Captured here and folded into the terminal :done so the
          ;; accurate reason reaches the caller (pi sets output.stopReason
          ;; from message_delta, mapStopReason).
          delta-stop-reason (atom nil)
          ;; pi refusal: the message_delta stop_details.explanation becomes the
          ;; error message on the terminal :error result
          delta-error-message (atom nil)
          saw-message-stop (atom false)
          end-reason (stream-loop rdr idle-timeout-ms signal abort-fn
                                  (fn [line]
                                    (let [[ev data] (parse-sse-line line)
                                          {:keys [event-name buf]} @state]
                                      (cond
                                        ev (reset! state {:event-name ev :buf buf})
                                        data (reset! state {:event-name event-name :buf (str buf data)})
                                        (and (empty? line) (seq buf))
                                        (do (when-let [evt (parse-anthropic-event event-name buf)]
                                              ;; capture the message_delta stop reason before
                                              ;; the terminal message_stop arrives
                                              (when (= :message-delta (:type evt))
                                                (when-let [sr (:stop-reason evt)]
                                                  (reset! delta-stop-reason sr))
                                                (when-let [em (:error-message evt)]
                                                  (reset! delta-error-message em)))
                                              (when (= :done (:type evt))
                                                (reset! saw-message-stop true))
                                              ;; the terminal :done reports the accurate
                                              ;; stop reason from message_delta, falling
                                              ;; back to message_stop's :end-turn. An
                                              ;; :error stop-reason (refusal/sensitive — pi
                                              ;; mapStopReason) is NOT a normal completion:
                                              ;; surface it as :error so the caller routes it
                                              ;; through on-error like the other wires (pi
                                              ;; pushes {type: "error"} instead of done).
                                              (let [evt (cond-> evt
                                                          (and (= :done (:type evt))
                                                               @delta-stop-reason)
                                                          (assoc :stop-reason @delta-stop-reason)

                                                          (and (= :done (:type evt))
                                                               @delta-error-message)
                                                          (assoc :error-message @delta-error-message))]
                                                (handler (if (and (= :done (:type evt))
                                                                  (= :error (:stop-reason evt)))
                                                           {:type :error
                                                            :message (or @delta-error-message
                                                                         "Provider stopped with: error")}
                                                           evt))))
                                            (reset! state {:event-name nil :buf ""}))
                                        :else nil)))
                                  (fn
                                    ([] (handler {:type :error
                                                  :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                                                " ms (no data received)")}))
                                    ;; Transport read failure (RST_STREAM, connection
                                    ;; reset, ...) — the accurate error surfaces instead
                                    ;; of a misleading idle timeout.
                                    ([e] (handler {:type :error
                                                   :message (str "Stream error: " (ex-message e))}))))]
      ;; pi: sawMessageStart && !sawMessageEnd → error. kmet is stricter:
      ;; the stream is always expected to end with message_stop regardless
      ;; of whether message_start was seen (the envelope event is always
      ;; present for a valid Anthropic SSE stream).
      (when (and (= :eof end-reason) (not @saw-message-stop) (not (and signal @signal)))
        (handler {:type :error
                  :message "Anthropic stream ended before message_stop"})))
    (catch Exception e
      (handler {:type :error :message (str "Stream error: " (ex-message e))}))))

(defn process-google-stream
  "Read a Google streamGenerateContent response body line by line, calling
   handler with each parsed event (mirrors process-openai-stream). signal (an
   atom) cancels the loop; errors via {:type :error}; idle-timeout-ms is the
   per-byte idle timeout; abort-fn kills the transport on stall.

   Detects premature stream end: when the stream ends before a finishReason
   chunk is received, reports {:type :error}."
  [response handler signal & [idle-timeout-ms abort-fn]]
  (process-data-stream response handler signal parse-google-event
                       #(= :done (:type %))
                       "Stream ended before a terminal response event"
                       idle-timeout-ms abort-fn))

;; ─── Mistral chat-completions stream (SSE; OpenAI-compatible chunk shape) ──

;; pi shortHash, inlined so the lib stays self-contained (no kmet.* deps —
;; kmet.libs.hash is the canonical home for app-side use).
(defn- short-hash
  [s]
  (let [imul (fn [a b]
               (unchecked-int
                (bit-and (unchecked-multiply (bit-and a 0xFFFFFFFF)
                                             (bit-and b 0xFFFFFFFF))
                         0xFFFFFFFF)))
        ushr32 (fn [x n] (unsigned-bit-shift-right (bit-and x 0xFFFFFFFF) n))
        [h1 h2] (reduce (fn [[h1 h2] ch]
                          [(imul (bit-xor h1 ch) 2654435761)
                           (imul (bit-xor h2 ch) 1597334677)])
                        [0xDEADBEEF 0x41C6CE57]
                        (map int s))
        h1 (bit-xor (imul (bit-xor h1 (ushr32 h1 16)) 2246822507)
                    (imul (bit-xor h2 (ushr32 h2 13)) 3266489909))
        h2 (bit-xor (imul (bit-xor h2 (ushr32 h2 16)) 2246822507)
                    (imul (bit-xor h1 (ushr32 h1 13)) 3266489909))]
    (str (Long/toString (bit-and h2 0xFFFFFFFF) 36)
         (Long/toString (bit-and h1 0xFFFFFFFF) 36))))

(def ^:private mistral-tool-call-id-length 9)

(defn- derive-mistral-tool-call-id
  "pi deriveMistralToolCallId (attempt 0): the id when it is already 9
   alphanumeric chars, else a 9-char shortHash of the seed."
  [id]
  (let [normalized (str/replace id #"[^a-zA-Z0-9]" "")]
    (if (and (= mistral-tool-call-id-length (count normalized)) (seq normalized))
      normalized
      (-> (short-hash (if (seq normalized) normalized id))
          (str/replace #"[^a-zA-Z0-9]" "")
          (subs 0 mistral-tool-call-id-length)))))

(defn- mistral-stop-reason
  "pi mapChatStopReason: stop → :stop, length/model_length → :length,
   tool_calls → :tool-use, error → :error; an unknown reason is an error."
  [reason]
  (cond
    (or (nil? reason) (= reason "stop")) {:stop-reason :stop}
    (or (= reason "length") (= reason "model_length")) {:stop-reason :length}
    (= reason "tool_calls") {:stop-reason :tool-use}
    (= reason "error") {:stop-reason :error :error-message "Provider stopped with: error"}
    :else {:stop-reason :error :error-message (str "Provider stopped with: " reason)}))

(defn parse-mistral-event
  "Parse a Mistral chat-completions SSE data payload into a vector of kmet
   events (pi readMistralEvents + consumeChatStream): :text, :thinking,
   :tool-call, :tool-call-args, :done (with :stop-reason), :usage, or
   :error. The streamed delta content is a plain string or an array of
   items ({type text} / {type thinking} with nested text parts);
   tool_calls carry the function name/arguments deltas (arguments as a
   string or a full object — stringified for the accumulator)."
  [data]
  (if (or (nil? data) (= data "[DONE]"))
    [{:type :done :stop-reason :stop}]
    (try
      (let [chunk (:data (json/parse-string data true))
            usage (:usage chunk)
            choice (first (:choices chunk))
            delta (or (:delta choice) {})
            finish (:finish_reason choice)
            events (volatile! [])]
        (when usage
          (vswap! events conj {:type :usage :usage usage}))
        (let [content (:content delta)]
          (cond
            (string? content)
            (when (pos? (count content))
              (vswap! events conj {:type :text :content content}))
            (sequential? content)
            (doseq [item content]
              (cond
                (string? item)
                (when (pos? (count item))
                  (vswap! events conj {:type :text :content item}))
                (= "text" (:type item))
                (let [t (or (:text item) "")]
                  (when (pos? (count t))
                    (vswap! events conj {:type :text :content t})))
                (= "thinking" (:type item))
                (let [t (str/join (keep :text (:thinking item)))]
                  (when (pos? (count t))
                    (vswap! events conj {:type :thinking :content t})))
                :else nil))))
        ;; Tool call deltas (pi: for-each; a missing/"null" id falls back to
        ;; a derived one like the SDK's).
        (doseq [tc (seq (:tool_calls delta))]
          (let [fname (get-in tc [:function :name])
                fargs (get-in tc [:function :arguments])
                args-str (cond
                           (string? fargs) fargs
                           (some? fargs) (json/generate-string fargs)
                           ;; name-only delta (call start): empty initial
                           :else "")
                raw-id (:id tc)]
            (cond
              (some? fname)
              (vswap! events conj {:type :tool-call
                                   :id (if (and raw-id (not= "null" raw-id))
                                         raw-id
                                         (derive-mistral-tool-call-id (str "toolcall:" (or (:index tc) 0))))
                                   :name fname :arguments args-str :index (:index tc)})
              (some? fargs)
              (vswap! events conj {:type :tool-call-args :id raw-id
                                   :arguments args-str :index (:index tc)}))))
        (when finish
          (vswap! events conj (merge {:type :done} (mistral-stop-reason finish))))
        (if (seq @events)
          @events
          [{:type :delta :chunk chunk}]))
      (catch Exception e
        [{:type :error :message (str "Parse error: " (ex-message e))}]))))

(defn process-mistral-stream
  "Read a Mistral chat-completions stream response body line by line,
   calling handler with each parsed event (the SSE wire format matches
   OpenAI's — data: lines with a [DONE] sentinel). signal (an atom) cancels
   the loop; errors via {:type :error}; idle-timeout-ms is the per-byte idle
   timeout; abort-fn kills the transport on stall.

   Detects premature stream end: when the stream ends before a finish_reason
   chunk (or [DONE]) is received, reports {:type :error} (pi: 'Mistral
   stream ended without a finish reason')."
  [response handler signal & [idle-timeout-ms abort-fn]]
  (process-data-stream response handler signal parse-mistral-event
                       #(= :done (:type %))
                       "Mistral stream ended without a finish reason"
                       idle-timeout-ms abort-fn))

;; ─── AWS Bedrock ConverseStream (binary event-stream frames) ──────────────

(def ^:private bedrock-crc-error
  "Message for a frame CRC mismatch (pi: the SDK validates both checksums)."
  "Bedrock stream frame CRC mismatch")

(defn- u32-at
  "Big-endian unsigned 32-bit int at offset in a byte array."
  [ba offset]
  (bit-or (bit-shift-left (bit-and (aget ba offset) 0xFF) 24)
          (bit-shift-left (bit-and (aget ba (inc offset)) 0xFF) 16)
          (bit-shift-left (bit-and (aget ba (+ offset 2)) 0xFF) 8)
          (bit-and (aget ba (+ offset 3)) 0xFF)))

(defn- u16-at
  "Big-endian unsigned 16-bit int at offset in a byte array."
  [ba offset]
  (bit-or (bit-shift-left (bit-and (aget ba offset) 0xFF) 8)
          (bit-and (aget ba (inc offset)) 0xFF)))

(defn- crc32
  "CRC-32 of a byte array (delegates to kmet.libs.hash/crc32 — pure
   Clojure, works on both babashka and Jolt)."
  [ba]
  (hash/crc32 ba))

(defn- slice
  "Byte-array copy of ba[from, to)."
  [ba from to]
  (java.util.Arrays/copyOfRange ba from to))

(defn- parse-bedrock-headers
  "Parse the headers section of an AWS event-stream frame (smithy header
   encoding): 1-byte name length + name + 1-byte value type + value. Only
   the standard ConverseStream headers are surfaced (strings/bools); other
   types are decoded but ignored."
  [ba]
  (loop [i 0
         result {}]
    (if (>= i (alength ba))
      result
      (let [name-len (bit-and (aget ba i) 0xFF)
            name (String. ba (inc i) name-len "UTF-8")
            t (bit-and (aget ba (+ i 1 name-len)) 0xFF)
            vstart (+ i 2 name-len)
            [vlen vnext] (if (or (= t 6) (= t 7))
                           [(u16-at ba vstart) (+ vstart 2)]
                           [0 vstart])
            value (case t
                    0 true
                    1 false
                    2 (bit-and (aget ba vnext) 0xFF)
                    3 (short (u16-at ba vnext))
                    4 (u32-at ba vnext)
                    5 (reduce (fn [acc j]
                                (unchecked-add (unchecked-multiply acc 256)
                                               (bit-and (aget ba (+ vnext j)) 0xFF)))
                              0 (range 8))
                    6 (slice ba vnext (+ vnext vlen))
                    7 (String. ba vnext vlen "UTF-8")
                    8 (reduce (fn [acc j]
                                (unchecked-add (unchecked-multiply acc 256)
                                               (bit-and (aget ba (+ vnext j)) 0xFF)))
                              0 (range 8))
                    9 (subs (String. ba vnext 16 "UTF-8") 0 16)
                    nil)]
        (recur (+ vnext vlen)
               ;; wire header names carry a leading colon (:message-type)
               ;; — strip it so the map keys are plain keywords
               (if (some? value) (assoc result (keyword (str/replace name #"^:" "")) value)
                   result))))))

(defn- read-exact
  "Read exactly n bytes from read-byte (int 0-255, -1 at EOF, or the
   idle-reader :timeout/:aborted sentinels) into a byte array. Returns the
   byte array, nil at a clean EOF before any byte, or the sentinel / read
   exception. A partial frame at EOF is an error (reported by the caller)."
  [read-byte n]
  (let [ba (byte-array n)]
    (loop [i 0]
      (if (>= i n)
        ba
        (let [b (read-byte)]
          (cond
            (instance? Exception b) b
            (or (= :timeout b) (= :aborted b)) b
            (neg? b) (if (zero? i)
                       nil
                       (ex-info "Bedrock stream ended mid-frame" {:type :bedrock-frame}))
            :else (do (aset-byte ba i (unchecked-byte b)) (recur (inc i)))))))))

(defn- read-bedrock-frame
  "Read one AWS event-stream frame from read-byte (an idle-reader read fn).
   Returns {:event-type str :headers {kw -> value} :payload byte[]} or nil
   at a clean frame-boundary EOF; the read exception / :timeout / :aborted
   sentinels pass through. Validates the prelude and message CRC-32
   checksums (smithy event-stream framing: prelude = total-length + headers-
   length + prelude CRC, then headers, payload, message CRC)."
  [read-byte]
  (let [prelude (read-exact read-byte 12)]
    (cond
      (nil? prelude) nil
      (instance? Exception prelude) prelude
      (or (= :timeout prelude) (= :aborted prelude)) prelude
      :else
      (let [total (u32-at prelude 0)
            headers-len (u32-at prelude 4)
            expected-prelude-crc (u32-at prelude 8)
            actual-prelude-crc (crc32 (slice prelude 0 8))]
        (if (not= expected-prelude-crc actual-prelude-crc)
          (ex-info bedrock-crc-error {:type :bedrock-crc})
          ;; sanity bound: a corrupt total-length would allocate a huge
          ;; buffer (16 = prelude + prelude-crc + message-crc; the smithy
          ;; spec caps frames at 16MB)
          (if (or (< total 16) (> total 16777216))
            (ex-info "Bedrock stream frame length out of range" {:type :bedrock-frame})
            (let [rest (read-exact read-byte (- total 12))]
              (cond
                (instance? Exception rest) rest
                (or (= :timeout rest) (= :aborted rest)) rest
                (nil? rest) (ex-info "Bedrock stream ended mid-frame" {:type :bedrock-frame})
                :else
                (let [headers (slice rest 0 headers-len)
                      payload (slice rest headers-len (- total 16))
                      ;; message CRC covers everything before it (total - 4)
                      crc-input (byte-array (- total 4))
                      _ (System/arraycopy prelude 0 crc-input 0 12)
                      _ (System/arraycopy rest 0 crc-input 12 (- total 16))
                      expected-msg-crc (u32-at rest (- total 16))
                      actual-msg-crc (crc32 crc-input)]
                  (if (not= expected-msg-crc actual-msg-crc)
                    (ex-info bedrock-crc-error {:type :bedrock-crc})
                    {:event-type (name (or (:event-type (parse-bedrock-headers headers)) :unknown))
                     :headers (parse-bedrock-headers headers)
                     :payload payload}))))))))))

(defn- bedrock-stop-reason
  "pi mapStopReason: end_turn/stop_sequence → :stop; max_tokens /
   model_context_window_exceeded → :length; tool_use → :tool-use; an
   unknown reason is an error."
  [reason]
  (cond
    (or (= reason "end_turn") (= reason "stop_sequence")) {:stop-reason :stop}
    (or (= reason "max_tokens") (= reason "model_context_window_exceeded")) {:stop-reason :length}
    (= reason "tool_use") {:stop-reason :tool-use}
    reason {:stop-reason :error :error-message (str "Provider stopped with: " reason)}
    :else {:stop-reason :error}))

(defn- bedrock-frame-event
  "One ConverseStream event frame → kmet event map, or nil for frames that
   carry no content (messageStart, contentBlockStop, ping). Exceptions and
   error messages become :error events."
  [{:keys [event-type headers payload]}]
  (let [message-type (:message-type headers)
        payload (when (seq payload) (json/parse-string (String. payload "UTF-8") true))]
    (cond
      (not= "event" message-type)
      {:type :error
       :message (if (= "exception" message-type)
                  (str "Bedrock " (name (or (:event-type headers) :error))
                       (when (:message payload) (str ": " (:message payload))))
                  (str "Bedrock stream error: " (or (:message payload) "unknown error")))}

      (= "messageStart" event-type) nil

      (= "contentBlockStart" event-type)
      (when-let [tool-use (get-in payload [:start :toolUse])]
        {:type :tool-call
         :index (:contentBlockIndex payload)
         :id (or (:toolUseId tool-use) "")
         :name (or (:name tool-use) "")
         :arguments ""})

      (= "contentBlockDelta" event-type)
      (let [delta (:delta payload)
            index (:contentBlockIndex payload)]
        (cond
          (contains? delta :text)
          (when (seq (:text delta))
            {:type :text :content (:text delta)})
          (contains? delta :toolUse)
          {:type :tool-call-args :index index
           :arguments (or (get-in delta [:toolUse :input]) "")}
          (:reasoningContent delta)
          (let [rt (:reasoningText (:reasoningContent delta))
                text (or (:text rt) "")]
            (when (seq text)
              {:type :thinking :content text}))
          :else nil))

      (= "contentBlockStop" event-type) nil

      (= "messageStop" event-type)
      (merge {:type :done} (bedrock-stop-reason (:stopReason payload)))

      (= "metadata" event-type)
      (when-let [usage (:usage payload)]
        {:type :usage
         :usage {:input_tokens (or (:inputTokens usage) 0)
                 :output_tokens (or (:outputTokens usage) 0)
                 :total_tokens (or (:totalTokens usage) 0)
                 :cache_read_input_tokens (or (:cacheReadInputTokens usage) 0)
                 :cache_write_input_tokens (or (:cacheWriteInputTokens usage) 0)}})

      :else nil)))

(defn process-bedrock-stream
  "Read an AWS Bedrock ConverseStream response body (binary event-stream
   frames — not SSE), calling handler with each parsed event. signal (an
   atom) cancels the loop; errors via {:type :error}; idle-timeout-ms is the
   per-byte idle timeout; abort-fn kills the transport on stall.

   Detects premature stream end: when the stream ends before a messageStop
   frame is received, reports {:type :error} (pi: 'Bedrock stream ended
   without a stop reason')."
  [response handler signal & [idle-timeout-ms abort-fn]]
  (let [is (io/input-stream (:body response))
        idle (if (and idle-timeout-ms (pos? idle-timeout-ms))
               (make-idle-reader #(.read is) idle-timeout-ms signal)
               {:read (fn [] (.read is)) :stop (fn []) :thread nil})
        saw-stop (atom false)]
    (try
      (let [end-reason
            (loop []
              (let [frame (read-bedrock-frame (:read idle))]
                (cond
                  (= :aborted frame) :aborted
                  (= :timeout frame)
                  (do (when abort-fn (abort-fn))
                      (handler {:type :error
                                :message (str "Stream idle timeout after " (or idle-timeout-ms 0)
                                              " ms (no data received)")})
                      :timeout)
                  (nil? frame) :eof
                  (instance? Exception frame)
                  (do (handler {:type :error :message (str "Stream error: " (ex-message frame))})
                      :error)
                  (and signal @signal) :aborted
                  :else
                  (let [event (bedrock-frame-event frame)]
                    (when event
                      (when (= :done (:type event)) (reset! saw-stop true))
                      (handler event))
                    (recur)))))]
        (when (and (= :eof end-reason) (not @saw-stop) (not (and signal @signal)))
          (handler {:type :error :message "Bedrock stream ended without a stop reason"})))
      (finally
        ((:stop idle))
        (when-let [t (:thread idle)] (.join t 2000))
        (.close is)))))

