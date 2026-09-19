(ns kmet.libs.test-sse
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.libs.hash :as hash]
            [kmet.libs.sse :as sse]))

;; ─── Event parsing ─────────────────────────────────────────────────────────

(t/deftest test-parse-sse-line
  (t/is (= [nil "hi"] (sse/parse-sse-line "data: hi")))
  (t/is (= ["event" nil] (sse/parse-sse-line "event: event")))
  (t/is (= [nil nil] (sse/parse-sse-line "")))
  (t/is (= [nil "hi"] (sse/parse-sse-line "data:   hi")))
  (t/is (= [nil nil] (sse/parse-sse-line ":comment"))))

(t/deftest test-parse-openai-event-done
  (t/is (= [{:type :done :stop-reason :stop}]
           (sse/parse-openai-event "[DONE]")))
  (t/is (= [{:type :done :stop-reason :stop}]
           (sse/parse-openai-event nil))))

(t/deftest test-parse-openai-event-text
  (t/is (= [{:type :text :content "hi"}]
           (sse/parse-openai-event "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"))))

(t/deftest test-parse-openai-event-thinking
  ;; reasoning_content streams as a :thinking event (deepseek thinking mode)
  (t/is (= [{:type :thinking :content "let me think"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"reasoning_content\":\"let me think\"}}]}")))
  ;; other OpenAI-compatible endpoints use `reasoning` (pi: reasoningFields)
  (t/is (= [{:type :thinking :content "via reasoning"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"reasoning\":\"via reasoning\"}}]}")))
  (t/is (= [{:type :thinking :content "via reasoning_text"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"reasoning_text\":\"via reasoning_text\"}}]}")))
  ;; first non-empty reasoning field wins when several are present (pi:
  ;; reasoning_content has priority over reasoning)
  (t/is (= [{:type :thinking :content "b"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"reasoning\":\"a\",\"reasoning_content\":\"b\"}}]}"))))

(t/deftest test-parse-openai-event-finish
  ;; pi mapStopReason: tool_calls → :tool-use (not the raw keyword)
  (t/is (= [{:type :done :stop-reason :tool-use}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")))
  (t/is (= [{:type :done :stop-reason :stop}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")))
  (t/is (= [{:type :done :stop-reason :length}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}")))
  ;; pi mapStopReason: content_filter / network_error / unknown → :error,
  ;; never a silent normal completion
  (t/is (= [{:type :done :stop-reason :error
             :error-message "Provider finish_reason: content_filter"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}")))
  (t/is (= [{:type :done :stop-reason :error
             :error-message "Provider finish_reason: network_error"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"network_error\"}]}")))
  (t/is (= [{:type :done :stop-reason :error
             :error-message "Provider finish_reason: weird"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"weird\"}]}"))))

(t/deftest test-parse-openai-event-both-text-and-thinking
  ;; A chunk can carry a content delta AND a reasoning delta — both events
  ;; fire (pi never uses an else-branch assuming one field per chunk)
  (t/is (= [{:type :text :content "answer"}
            {:type :thinking :content "reason"}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"content\":\"answer\",\"reasoning_content\":\"reason\"}}]}")))
  ;; empty-string content must not produce a spurious text event
  (t/is (= [{:type :delta :chunk {:choices [{:delta {:content ""}}]}}]
           (sse/parse-openai-event
            "{\"choices\":[{\"delta\":{\"content\":\"\"}}]}"))))

(t/deftest test-parse-openai-event-multi-tool-calls
  ;; pi iterates ALL tool call deltas in a chunk, not just the first
  (t/is (= [{:type :tool-call :id "a" :name "read" :arguments "" :index 0}
            {:type :tool-call :id "b" :name "bash" :arguments "" :index 1}]
           (sse/parse-openai-event
            (str "{\"choices\":[{\"delta\":{\"tool_calls\":["
                 "{\"id\":\"a\",\"index\":0,\"function\":{\"name\":\"read\"}},"
                 "{\"id\":\"b\",\"index\":1,\"function\":{\"name\":\"bash\"}}]}}]}")))))

;; ─── OpenAI Responses event parsing (pi processResponsesStream) ────────────

(declare make-pipe)

(defn- responses-state [] (atom {:event-name nil :buf "" :slots {} :saw-terminal? false}))

(t/deftest test-parse-responses-event-text-and-thinking
  (t/is (= [{:type :text :content "hel"}]
           (sse/parse-responses-event
            "response.output_text.delta"
            "{\"output_index\":0,\"delta\":\"hel\"}"
            (responses-state))))
  (t/is (= [{:type :thinking :content "let me think"}]
           (sse/parse-responses-event
            "response.reasoning_text.delta"
            "{\"output_index\":0,\"delta\":\"let me think\"}"
            (responses-state))))
  (t/is (= [{:type :thinking :content "summary"}]
           (sse/parse-responses-event
            "response.reasoning_summary_text.delta"
            "{\"output_index\":0,\"delta\":\"summary\"}"
            (responses-state))))
  ;; refusal deltas stream as text (pi appends them to the text block)
  (t/is (= [{:type :text :content "I cannot"}]
           (sse/parse-responses-event
            "response.refusal.delta"
            "{\"output_index\":0,\"delta\":\"I cannot\"}"
            (responses-state)))))

(t/deftest test-parse-responses-event-tool-call
  (let [st (responses-state)
        start (sse/parse-responses-event
               "response.output_item.added"
               "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_123\",\"call_id\":\"call_abc\",\"name\":\"read\",\"arguments\":\"\"}}"
               st)]
    (t/is (= [{:type :tool-call :id "call_abc|fc_123" :name "read"
               :arguments "" :index 0}]
             start)
          "function_call items create a :tool-call with the pi id format")
    (t/is (= [{:type :tool-call-args :arguments "{\"path\":\"" :index 0}]
             (sse/parse-responses-event
              "response.function_call_arguments.delta"
              "{\"output_index\":0,\"delta\":\"{\\\"path\\\":\\\"\"}"
              st))
          "argument deltas stream as :tool-call-args")
    (t/is (= [{:type :tool-call-args :arguments "a.txt\"}" :index 0}]
             (sse/parse-responses-event
              "response.function_call_arguments.done"
              "{\"output_index\":0,\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}"
              st))
          "the final arguments emit only the delta past the accumulated partial")
    (t/is (= {:kind :tool-call :id "call_abc|fc_123" :name "read"
              :partial-json "{\"path\":\"a.txt\"}"}
             (get-in @st [:slots 0]))
          "the slot accumulates the full partial JSON")))

(t/deftest test-parse-responses-event-terminal
  (t/testing "completed → usage + :done :stop"
    (let [st (responses-state)
          evts (sse/parse-responses-event
                "response.completed"
                (str "{\"response\":{\"id\":\"resp_1\",\"status\":\"completed\","
                     "\"usage\":{\"input_tokens\":100,\"output_tokens\":50,\"total_tokens\":150,"
                     "\"input_tokens_details\":{\"cached_tokens\":10,\"cache_write_tokens\":5},"
                     "\"output_tokens_details\":{\"reasoning_tokens\":20}}}}")
                st)]
      (t/is (= [{:type :usage
                 :usage {:input_tokens 100 :output_tokens 50 :total_tokens 150
                         :input_tokens_details {:cached_tokens 10 :cache_write_tokens 5}
                         :output_tokens_details {:reasoning_tokens 20}}}
                {:type :done :stop-reason :stop}]
               evts))
      (t/is (:saw-terminal? @st))))
  (t/testing "completed with a tool-call slot → :done :tool-use (pi remap)"
    (let [st (responses-state)
          _ (sse/parse-responses-event
             "response.output_item.added"
             "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\",\"arguments\":\"\"}}"
             st)
          evts (sse/parse-responses-event
                "response.completed"
                "{\"response\":{\"status\":\"completed\"}}"
                st)]
      (t/is (= [{:type :done :stop-reason :tool-use}] evts))))
  (t/testing "incomplete + max_output_tokens → :done :length"
    (let [evts (sse/parse-responses-event
                "response.incomplete"
                "{\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}"
                (responses-state))]
      (t/is (= [{:type :done :stop-reason :length}] evts))))
  (t/testing "incomplete + other reason → error (pi stopReason 'error')"
    (let [evts (sse/parse-responses-event
                "response.incomplete"
                "{\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"}}}"
                (responses-state))]
      (t/is (= [{:type :error :message "Response incomplete: content_filter"}] evts))))
  (t/testing "failed → error with the provider code+message"
    (let [evts (sse/parse-responses-event
                "response.failed"
                "{\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}"
                (responses-state))]
      (t/is (= [{:type :error :message "server_error: boom"}] evts))))
  (t/testing "codex response.done → the same terminal handling (pi mapCodexEvents)"
    (let [st (responses-state)
          evts (sse/parse-responses-event
                "response.done"
                "{\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}"
                st)]
      (t/is (= [{:type :usage :usage {:input_tokens 10 :output_tokens 5 :total_tokens 15}}
                {:type :done :stop-reason :stop}]
               evts))
      (t/is (:saw-terminal? @st)))
    (let [st (responses-state)
          _ (sse/parse-responses-event
             "response.output_item.added"
             "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\",\"arguments\":\"\"}}"
             st)
          evts (sse/parse-responses-event
                "response.done"
                "{\"response\":{\"status\":\"completed\"}}"
                st)]
      (t/is (= [{:type :done :stop-reason :tool-use}] evts)
            "codex response.done with a tool-call slot → :tool-use")))
  (t/testing "unknown/missing status → error, not a silent end (codex normalizeCodexStatus)"
    (let [evts (sse/parse-responses-event
                "response.done"
                "{\"response\":{\"status\":\"queued\"}}"
                (responses-state))]
      (t/is (= [{:type :error :message "Response ended with status queued"}] evts)))
    (let [evts (sse/parse-responses-event
                "response.done"
                "{\"response\":{}}"
                (responses-state))]
      (t/is (= [{:type :error :message "Response ended without a status"}] evts)))))

(t/deftest test-responses-stream-completes
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-responses-stream {:body in}
                                          (fn [e] (swap! events conj e))
                                          nil)
            :done)]
    (.write out (.getBytes "event: response.output_text.delta\ndata: {\"output_index\":0,\"delta\":\"hi\"}\n\n"))
    (.write out (.getBytes "event: response.completed\ndata: {\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n"))
    (.flush out)
    ;; pipes don't unblock on close-while-reading — let the reader consume first
    (Thread/sleep 50)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :text :content "hi"}
              {:type :usage :usage {:input_tokens 10 :output_tokens 5 :total_tokens 15}}
              {:type :done :stop-reason :stop}]
             @events))
    (.close in)))

(t/deftest test-responses-stream-premature-end
  ;; no terminal event before EOF → error (pi: 'OpenAI Responses stream
  ;; ended before a terminal response event'). signal is always an atom in
  ;; production (the loop passes :signal), so pass one here — SCI derefs nil
  ;; as an NPE.
  (let [events (atom [])
        body (java.io.ByteArrayInputStream.
              (.getBytes "event: response.output_text.delta\ndata: {\"output_index\":0,\"delta\":\"hi\"}\n\n"))]
    (sse/process-responses-stream {:body body}
                                  (fn [e] (swap! events conj e))
                                  (atom false))
    (t/is (= [{:type :text :content "hi"}
              {:type :error
               :message "OpenAI Responses stream ended before a terminal response event"}]
             @events))))

;; ─── Idle timeout (pi: httpIdleTimeoutMs / undici bodyTimeout) ────────────

(defn- make-pipe
  "A connected PipedInputStream/PipedOutputStream pair."
  []
  (let [in (java.io.PipedInputStream.)
        out (java.io.PipedOutputStream. in)]
    [in out]))

(t/deftest ^:slow test-openai-stream-idle-timeout
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-openai-stream {:body in}
                                       (fn [e] (swap! events conj e))
                                       nil
                                       100)
            :done)]
    (.write out (.getBytes "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"))
    (.flush out)
    ;; Do not write or close further — the stream must error via the idle
    ;; timeout instead of hanging. Closing the write side afterwards lets the
    ;; daemon reader thread see EOF (tests use pipes, which don't unblock on
    ;; close-while-reading).
    (Thread/sleep 300)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= 2 (count @events)))
    (t/is (= :text (:type (first @events))))
    (t/is (= "hi" (:content (first @events))))
    (t/is (= :error (:type (second @events))))
    (t/is (str/includes? (:message (second @events)) "idle timeout"))
    (.close in)))

(t/deftest ^:slow test-openai-stream-completes-without-timeout
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-openai-stream {:body in}
                                       (fn [e] (swap! events conj e))
                                       nil
                                       300)
            :done)]
    (.write out (.getBytes "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"))
    (.flush out)
    (Thread/sleep 50)
    (.write out (.getBytes "data: [DONE]\n\n"))
    (.flush out)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :text :content "hi"}
              {:type :done :stop-reason :stop}]
             @events))
    (.close in)))

(t/deftest test-openai-stream-no-idle-timeout-when-disabled
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-openai-stream {:body in}
                                       (fn [e] (swap! events conj e))
                                       nil
                                       nil)
            :done)]
    (.write out (.getBytes "data: [DONE]\n\n"))
    (.flush out)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :done :stop-reason :stop}] @events))
    (.close in)))

(t/deftest test-parse-anthropic-thinking-signature-deltas
  ;; thinking replay capture: reasoning and its opaque signature arrive as
  ;; content_block_delta variants (pi: iterateAnthropicEvents)
  (t/is (= {:type :thinking :content "hmm"}
           (sse/parse-anthropic-event
            "content_block_delta"
            "{\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}")))
  (t/is (= {:type :signature :content "SIG"}
           (sse/parse-anthropic-event
            "content_block_delta"
            "{\"delta\":{\"type\":\"signature_delta\",\"signature\":\"SIG\"}}"))))

(t/deftest test-parse-anthropic-parallel-tool-calls
  ;; pi: parallel tool_use blocks are keyed by their block index (event.index)
  ;; so sibling calls survive the tool-call accumulator instead of collapsing.
  (t/is (= {:type :tool-call :id "toolu_1" :name "read" :arguments {} :index 0}
           (sse/parse-anthropic-event
            "content_block_start"
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read\",\"input\":{}}}"))
        "content_block_start carries the block index")
  (t/is (= {:type :tool-call :id "toolu_2" :name "bash" :arguments {} :index 1}
           (sse/parse-anthropic-event
            "content_block_start"
            "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"bash\",\"input\":{}}}"))
        "second sibling block gets its own index")
  (t/is (= {:type :tool-call-args :arguments "{\"path\":\"" :index 0}
           (sse/parse-anthropic-event
            "content_block_delta"
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"\"}}"))
        "input_json_delta carries the block index for correlation")
  (t/is (= {:type :tool-call-args :arguments "a.txt\"}" :index 1}
           (sse/parse-anthropic-event
            "content_block_delta"
            "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"a.txt\\\"}\"}}"))
        "second block's args delta correlates by its own index"))

(t/deftest test-parse-anthropic-message-delta-stop-reasons
  ;; pi mapStopReason: the real stop reason rides on message_delta, not
  ;; message_stop (which only says "ended").
  (t/is (= {:type :message-delta :stop-reason :stop}
           (sse/parse-anthropic-event
            "message_delta"
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}")))
  (t/is (= {:type :message-delta :stop-reason :stop}
           (sse/parse-anthropic-event
            "message_delta"
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\"}}")))
  (t/is (= {:type :message-delta :stop-reason :length}
           (sse/parse-anthropic-event
            "message_delta"
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}")))
  (t/is (= {:type :message-delta :stop-reason :tool-use}
           (sse/parse-anthropic-event
            "message_delta"
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}")))
  (t/is (= {:type :message-delta :stop-reason :error
            :error-message "I won't do that"}
           (sse/parse-anthropic-event
            "message_delta"
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\",\"stop_details\":{\"explanation\":\"I won't do that\"}}}"))
        "refusal carries the stop_details explanation (pi refusal → errorMessage)")
  (t/is (= {:type :message-delta :stop-reason :error}
           (sse/parse-anthropic-event
            "message_delta"
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"sensitive\"}}")))
  (t/is (= {:type :message-delta}
           (sse/parse-anthropic-event
            "message_delta"
            "{\"type\":\"message_delta\",\"delta\":{}}"))
        "no stop_reason → no :stop-reason key"))

(t/deftest test-anthropic-stream-folds-message-delta-stop-reason
  ;; The terminal :done (message_stop) reports the accurate stop reason
  ;; captured from message_delta — pi sets output.stopReason from
  ;; message_delta, mapStopReason. A refusal surfaces as :error instead of
  ;; :done (pi pushes {type: "error"} for error stop-reasons).
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-anthropic-stream {:body in}
                                          (fn [e] (swap! events conj e))
                                          nil)
            :done)]
    (.write out (.getBytes (str "event: content_block_delta\n"
                                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n")))
    (.write out (.getBytes "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n"))
    (.write out (.getBytes "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
    (.flush out)
    (Thread/sleep 50)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :text :content "hi"}
              {:type :message-delta :stop-reason :tool-use}
              {:type :done :stop-reason :tool-use}]
             @events)
          "message_delta tool_use folds into the terminal done")
    (.close in)))

(t/deftest test-anthropic-stream-refusal-surfaces-as-error
  ;; pi: a refusal message_delta maps to stopReason "error" with the
  ;; explanation — the stream must surface it via :error (the caller's
  ;; on-error path), never as a normal :done.
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-anthropic-stream {:body in}
                                          (fn [e] (swap! events conj e))
                                          nil)
            :done)]
    (.write out (.getBytes (str "event: content_block_delta\n"
                                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"no\"}}\n\n")))
    (.write out (.getBytes (str "event: message_delta\n"
                                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\",\"stop_details\":{\"explanation\":\"I refuse\"}}}\n\n")))
    (.write out (.getBytes "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
    (.flush out)
    (Thread/sleep 50)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :text :content "no"}
              {:type :message-delta :stop-reason :error :error-message "I refuse"}
              {:type :error :message "I refuse"}]
             @events)
          "refusal becomes an :error event with the explanation")
    (.close in)))

(t/deftest test-parse-google-thought-signature
  ;; pi google-shared: thoughtSignature rides on thought parts and is
  ;; captured for same-model replay
  (t/is (= [{:type :thinking :content "t" :signature "G"}]
           (sse/parse-google-event
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"t\",\"thought\":true,\"thoughtSignature\":\"G\"}]}}]}")))
  (t/is (= [{:type :thinking :content "plain"}]
           (sse/parse-google-event
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"plain\",\"thought\":true}]}}]}"))
        "no signature — no key"))

(t/deftest ^:slow test-anthropic-stream-idle-timeout
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-anthropic-stream {:body in}
                                          (fn [e] (swap! events conj e))
                                          nil
                                          100)
            :done)]
    ;; Nothing is ever written — the stream must error via the idle timeout.
    ;; Close the write side to let the daemon reader thread see EOF.
    (Thread/sleep 300)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= :error (:type (first @events))))
    (t/is (str/includes? (:message (first @events)) "idle timeout"))
    (.close in)))

(t/deftest test-anthropic-stream-completes
  ;; Regression: the event-name from an `event:` line must survive the
  ;; buffering (the cond previously stored the data value, so every event
  ;; parsed as :unknown and the stream produced no text).
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-anthropic-stream {:body in}
                                          (fn [e] (swap! events conj e))
                                          nil)
            :done)]
    (.write out (.getBytes (str "event: content_block_delta\n"
                                "data: {\"type\":\"content_block_delta\",\"index\":0,"
                                "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n")))
    (.write out (.getBytes "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
    (.flush out)
    (Thread/sleep 50)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :text :content "hi"}
              {:type :done :stop-reason :end-turn}]
             @events))
    (.close in)))

(t/deftest test-openai-stream-read-error-surfaces-immediately
  ;; A transport read failure (e.g. HTTP/2 RST_STREAM thrown by
  ;; java.net.http mid-stream) must surface as an accurate error right away
  ;; — not stall to the idle deadline and report a misleading idle timeout.
  ;; Regression for "No retry after: Received RST_STREAM: Protocol error":
  ;; the daemon reader used to swallow read exceptions, so the classifier
  ;; never saw the real message.
  (let [events (atom [])
        throwing-reader
        (proxy [java.io.Reader] []
          (read
            ([] (throw (java.io.IOException. "Received RST_STREAM: Protocol error")))
            ([cbuf off len] (throw (java.io.IOException. "Received RST_STREAM: Protocol error")))))
        f (future
            (sse/process-openai-stream {:body throwing-reader}
                                       (fn [e] (swap! events conj e))
                                       nil
                                       300000) ;; 5-min idle — must not be reached
            :done)]
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= [{:type :error :message "Stream error: Received RST_STREAM: Protocol error"}]
             @events))))

(t/deftest ^:slow test-openai-stream-idle-resets-on-flow
  ;; Data arriving well within the idle window over a total duration longer
  ;; than the timeout must not stall — the clock resets per byte (undici
  ;; bodyTimeout semantics, not a total deadline). Idle 200ms with 50ms gaps
  ;; (4x headroom) over a 400ms total (2x the idle).
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-openai-stream {:body in}
                                       (fn [e] (swap! events conj e))
                                       nil
                                       200)
            :done)]
    (dotimes [_ 8]
      (.write out (.getBytes "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"))
      (.flush out)
      (Thread/sleep 50))
    (.write out (.getBytes "data: [DONE]\n\n"))
    (.flush out)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= 8 (count (filter #(= :text (:type %)) @events))))
    (t/is (= :done (:type (last @events))))
    (.close in)))

;; ─── Mistral chat-completions parsing ─────────────────────────────────────

(t/deftest test-parse-mistral-event
  (t/is (= [{:type :done :stop-reason :stop}]
           (sse/parse-mistral-event "[DONE]")))
  (t/is (= [{:type :text :content "hi"} {:type :done :stop-reason :stop}]
           (sse/parse-mistral-event
            "{\"data\": {\"choices\": [{\"delta\": {\"content\": \"hi\"}, \"finish_reason\": \"stop\"}]}}")))
  (t/is (= [{:type :text :content "answer"}
            {:type :thinking :content "reason1reason2"}
            {:type :done :stop-reason :tool-use}]
           (sse/parse-mistral-event
            (str "{\"data\": {\"choices\": [{\"delta\": {\"content\": ["
                 "{\"type\": \"text\", \"text\": \"answer\"}, "
                 "{\"type\": \"thinking\", \"thinking\": ["
                 "{\"type\": \"text\", \"text\": \"reason1\"},"
                 "{\"type\": \"text\", \"text\": \"reason2\"}]}]}, "
                 "\"finish_reason\": \"tool_calls\"}]}}"))))
  (t/is (= [{:type :tool-call :id "t1" :name "read" :arguments "" :index 0}]
           (sse/parse-mistral-event
            (str "{\"data\": {\"choices\": [{\"delta\": {\"tool_calls\": ["
                 "{\"id\": \"t1\", \"index\": 0, \"function\": {\"name\": \"read\"}}]}}]}}"))))
  (t/is (= [{:type :tool-call-args :id "t1" :arguments "{\"path\":\"/x\"}" :index 0}
            {:type :done :stop-reason :length}]
           (sse/parse-mistral-event
            (str "{\"data\": {\"choices\": [{\"delta\": {\"tool_calls\": ["
                 "{\"id\": \"t1\", \"index\": 0, \"function\": {\"arguments\": {\"path\": \"/x\"}}}]},"
                 "\"finish_reason\": \"length\"}]}}"))))
  (let [events (sse/parse-mistral-event
                (str "{\"data\": {\"choices\": [{\"delta\": {\"tool_calls\": ["
                     "{\"index\": 3, \"function\": {\"name\": \"read\"}}]}}]}}"))]
    (t/is (= 9 (count (:id (first events))))))
  (t/is (= [{:type :usage
             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}]
           (sse/parse-mistral-event
            "{\"data\": {\"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 5, \"total_tokens\": 15}}}")))
  (t/is (= [{:type :done :stop-reason :error
             :error-message "Provider stopped with: weird"}]
           (sse/parse-mistral-event
            "{\"data\": {\"choices\": [{\"delta\": {}, \"finish_reason\": \"weird\"}]}}"))))
(t/deftest test-mistral-stream-completes
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-mistral-stream {:body in}
                                        (fn [e] (swap! events conj e))
                                        nil)
            :done)]
    (.write out (.getBytes "data: {\"data\": {\"choices\": [{\"delta\": {\"content\": \"hi\"}}]}}\n\n"))
    (.write out (.getBytes "data: {\"data\": {\"choices\": [{\"delta\": {}, \"finish_reason\": \"stop\"}]}}\n\n"))
    (.write out (.getBytes "data: [DONE]\n\n"))
    (.flush out)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= :text (:type (first @events))))
    (t/is (= :done (:type (last @events))))
    (.close in)))

(t/deftest test-mistral-stream-premature-end
  (let [[in out] (make-pipe)
        events (atom [])
        f (future
            (sse/process-mistral-stream {:body in}
                                        (fn [e] (swap! events conj e))
                                        nil)
            :done)]
    (.write out (.getBytes "data: {\"data\": {\"choices\": [{\"delta\": {\"content\": \"hi\"}}]}}\n\n"))
    (.flush out)
    (.close out)
    (t/is (= :done (deref f 3000 :timeout)))
    (t/is (= :error (:type (last @events))))
    (t/is (= "Mistral stream ended without a finish reason" (:message (last @events))))
    (.close in)))

;; ─── AWS Bedrock ConverseStream frame parsing ─────────────────────────────

(defn- bedrock-u32-be [n]
  [(bit-and (bit-shift-right n 24) 0xFF) (bit-and (bit-shift-right n 16) 0xFF)
   (bit-and (bit-shift-right n 8) 0xFF) (bit-and n 0xFF)])
(defn- bedrock-u16-be [n] [(bit-and (bit-shift-right n 8) 0xFF) (bit-and n 0xFF)])
(defn- bedrock-crc [bytes]
  (hash/crc32 bytes))
(defn- bedrock-ba [ints] (byte-array (map #(bit-and (long %) 0xFF) ints)))
(defn- bedrock-header [name value]
  (let [nb (map int (.getBytes name "UTF-8"))
        vb (map int (.getBytes value "UTF-8"))]
    (concat [(count nb)] nb [7] (bedrock-u16-be (count vb)) vb)))
(defn- bedrock-frame
  "Build one AWS event-stream frame with the standard message-type/event-type
   headers and a JSON payload (valid CRCs — the parser checks both)."
  ([event-type payload]
   (bedrock-frame "event" event-type payload))
  ([message-type event-type payload]
   (let [payload-bytes (map int (.getBytes payload "UTF-8"))
         hdrs (concat (bedrock-header ":message-type" message-type)
                      (bedrock-header ":event-type" event-type))
         total (+ 12 (count hdrs) (count payload-bytes) 4)
         prelude (bedrock-ba (concat (bedrock-u32-be total) (bedrock-u32-be (count hdrs))
                                     (bedrock-u32-be 0)))
         prelude-crc (bedrock-crc (java.util.Arrays/copyOfRange prelude 0 8))
         prelude-full (bedrock-ba (concat (bedrock-u32-be total) (bedrock-u32-be (count hdrs))
                                          (bedrock-u32-be prelude-crc)))
         msg-body (mapv #(bit-and (long %) 0xFF)
                        (concat (mapv int prelude-full) hdrs payload-bytes))
         msg-crc (bedrock-crc (bedrock-ba msg-body))]
     (bedrock-ba (concat msg-body (bedrock-u32-be msg-crc))))))

(defn- bedrock-frames [& frames]
  (bedrock-ba (mapcat #(mapv int %) frames)))

(t/deftest test-bedrock-stream-events
  (let [frames (bedrock-frames
                (bedrock-frame "messageStart" "{\"role\":\"assistant\"}")
                (bedrock-frame "contentBlockStart"
                               "{\"contentBlockIndex\":0,\"start\":{\"toolUse\":{\"toolUseId\":\"t1\",\"name\":\"read\"}}}")
                (bedrock-frame "contentBlockDelta"
                               "{\"contentBlockIndex\":0,\"delta\":{\"toolUse\":{\"input\":\"{\\\"path\\\":\\\"/x\\\"}\"}}}")
                (bedrock-frame "contentBlockDelta"
                               "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"Hello \"}}")
                (bedrock-frame "contentBlockDelta"
                               "{\"contentBlockIndex\":0,\"delta\":{\"reasoningContent\":{\"reasoningText\":{\"text\":\"hmm\"}}}}")
                (bedrock-frame "contentBlockStop" "{\"contentBlockIndex\":0}")
                (bedrock-frame "messageStop" "{\"stopReason\":\"tool_use\"}")
                (bedrock-frame "metadata"
                               "{\"usage\":{\"inputTokens\":10,\"outputTokens\":5,\"totalTokens\":15,\"cacheReadInputTokens\":2,\"cacheWriteInputTokens\":1}}"))
        events (atom [])]
    (sse/process-bedrock-stream {:body (java.io.ByteArrayInputStream. frames)}
                                (fn [e] (swap! events conj e))
                                nil)
    (t/is (= [{:type :tool-call :index 0 :id "t1" :name "read" :arguments ""}
              {:type :tool-call-args :index 0 :arguments "{\"path\":\"/x\"}"}
              {:type :text :content "Hello "}
              {:type :thinking :content "hmm"}
              {:type :done :stop-reason :tool-use}
              {:type :usage
               :usage {:input_tokens 10 :output_tokens 5 :total_tokens 15
                       :cache_read_input_tokens 2 :cache_write_input_tokens 1}}]
             @events))))

(t/deftest test-bedrock-stream-stop-reasons
  (doseq [[reason expected] [["end_turn" :stop] ["stop_sequence" :stop]
                             ["max_tokens" :length] ["model_context_window_exceeded" :length]
                             ["tool_use" :tool-use]]]
    (let [frames (bedrock-frames (bedrock-frame "messageStop" (str "{\"stopReason\":\"" reason "\"}")))
          events (atom [])]
      (sse/process-bedrock-stream {:body (java.io.ByteArrayInputStream. frames)}
                                  (fn [e] (swap! events conj e))
                                  nil)
      (t/is (= [{:type :done :stop-reason expected}] @events) (str reason)))))

(t/deftest test-bedrock-stream-corrupt-crc
  (let [frame (bedrock-frame "messageStart" "{\"role\":\"assistant\"}")
        corrupt (byte-array (alength frame))
        _ (System/arraycopy frame 0 corrupt 0 (alength frame))
        ;; flip one payload byte — the message CRC then mismatches
        _ (aset-byte corrupt (dec (alength corrupt)) (unchecked-byte 0))
        events (atom [])]
    (sse/process-bedrock-stream {:body (java.io.ByteArrayInputStream. corrupt)}
                                (fn [e] (swap! events conj e))
                                nil)
    (t/is (= 1 (count @events)))
    (t/is (= :error (:type (first @events))))
    (t/is (= "Stream error: Bedrock stream frame CRC mismatch" (:message (first @events))))))

(t/deftest test-bedrock-stream-exception-frame
  (let [frames (bedrock-frames
                (bedrock-frame "exception" "validationException"
                               "{\"message\":\"bad request\"}")
                (bedrock-frame "messageStop" "{\"stopReason\":\"end_turn\"}"))
        events (atom [])]
    (sse/process-bedrock-stream {:body (java.io.ByteArrayInputStream. frames)}
                                (fn [e] (swap! events conj e))
                                nil)
    (t/is (= :error (:type (first @events))))
    (t/is (= "Bedrock validationException: bad request" (:message (first @events))))))

(t/deftest test-bedrock-stream-premature-end
  (let [frames (bedrock-frames (bedrock-frame "messageStart" "{\"role\":\"assistant\"}"))
        events (atom [])]
    (sse/process-bedrock-stream {:body (java.io.ByteArrayInputStream. frames)}
                                (fn [e] (swap! events conj e))
                                nil)
    (t/is (= :error (:type (last @events))))
    (t/is (= "Bedrock stream ended without a stop reason" (:message (last @events))))))
