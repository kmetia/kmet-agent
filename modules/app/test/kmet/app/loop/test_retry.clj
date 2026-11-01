(ns kmet.app.loop.test-retry
  "Auto-retry: exhausted attempts, backoff, and the events that report them."
  (:require [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.ai.llm :as llm]
            [kmet.app.session.core :as session]
            [kmet.app.loop.state :as state]
            [kmet.app.loop :as loop]
            [kmet.app.loop.test-support :as support]
            [kmet.app.config :as cfg]
            [kmet.test-tmp :as test-tmp]))

(t/use-fixtures :each support/with-host)

;; ─── Auto-retry ───────────────────────────────────────────────────────────

(t/deftest test-loop-auto-retry-succeeds
  (let [events (atom [])
        call-count (atom 0)
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 2
               :base-delay-ms 1)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (case (swap! call-count inc)
                        1 (when-let [on-error (:on-error opts)]
                            (on-error "upstream connect error"))
                        2 (when-let [on-error (:on-error opts)]
                            (on-error "502 Bad Gateway"))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "recovered"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-done (fn [_]) :on-error (fn [_])}))
    (let [starts (filter #(= :auto-retry-start (:type %)) @events)
          ends (filter #(= :auto-retry-end (:type %)) @events)]
      (t/is (= 2 (count starts)) "one retry start per transient failure")
      (t/is (= 1 (:attempt (first starts))))
      (t/is (= 2 (:attempt (second starts))))
      (t/is (= 2 (:max-attempts (first starts))))
      (t/is (pos? (:delay-ms (first starts))) "backoff delay reported")
      (t/is (= 1 (count ends)))
      (t/is (true? (:success (first ends))))
      (t/is (= 2 (:attempt (first ends)))))
    (t/is (= :idle @(:status agent)))
    ;; pi parity: one :agent-end per attempt (agent.prompt then
    ;; agent.continue), each reporting its own messages and whether it will
    ;; be retried; exactly one :agent-settled for the whole prompt.
    (t/is (= [true true false]
             (mapv :will-retry (filter #(= :agent-end (:type %)) @events)))
          "one :agent-end per attempt; only the transient failures will retry")
    (t/is (= 1 (count (filter #(= :agent-settled (:type %)) @events)))
          "exactly one :agent-settled per prompt")
    (t/is (= [:user :assistant]
             (mapv :role (:messages (first (filter #(= :agent-end (:type %)) @events)))))
          "the first attempt's :agent-end carries the submitted user message")
    (t/is (= [:assistant]
             (mapv :role (:messages (last (filter #(= :agent-end (:type %)) @events)))))
          "the retried attempt's :agent-end carries only that attempt's messages (pi: continue adds no prompt)")
    ;; The retried stream's text is the final assistant message
    (t/is (some #(and (= :message-end (:type %))
                      (= "recovered" (get-in % [:message :content 0 :text])))
                @events))))

(t/deftest test-loop-auto-retry-exhausted
  (let [events (atom [])
        errors (atom [])
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 1
               :base-delay-ms 1)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "503 service unavailable"))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= 1 (count @errors)) "only the terminal error surfaces via on-error")
    (let [ends (filter #(= :auto-retry-end (:type %)) @events)]
      (t/is (= 1 (count ends)))
      (t/is (false? (:success (first ends))))
      (t/is (= 1 (:attempt (first ends))))
      (t/is (= "503 service unavailable" (:final-error (first ends)))))
    (t/is (= :idle @(:status agent))
          "status returns to :idle after retries are exhausted (pi: agent_settled)")
    (t/is (some #(= :error (:type %)) @events) "terminal :error event emitted")
    ;; pi parity: the initial failure's :agent-end says a retry will follow;
    ;; the exhausted one does not and carries the terminal error.
    (let [aes (filter #(= :agent-end (:type %)) @events)]
      (t/is (= [true false] (mapv :will-retry aes))
            "the initial failure retries; the exhausted attempt does not")
      (t/is (= "503 service unavailable" (:error (last aes)))
            "the terminal attempt's :agent-end carries the error"))
    (t/is (= 1 (count (filter #(= :agent-settled (:type %)) @events)))
          "exactly one :agent-settled per prompt")
    ;; Every failed attempt is recorded — one :message-end per LLM call that
    ;; errored (pi: message_end persists each stopReason-error partial, even
    ;; when no retry budget remains) — and none enter the live context
    (let [err-ends (filter #(and (= :message-end (:type %))
                                 (= :error (:stop-reason (:message %))))
                           @events)]
      (t/is (= 2 (count err-ends)) "initial failure + exhausted retry")
      (t/is (every? #(= "503 service unavailable" (:error-message %))
                    (map :message err-ends)))
      (t/is (empty? (filter #(= :assistant (:role %)) (state/get-context agent)))
            "no assistant message entered the context"))))

(t/deftest test-loop-agent-end-carries-will-retry-to-every-listener
  ;; pi decorates the public agent_end with :will-retry and emits a bare copy to
  ;; the extension runner. One table carries one copy, so the callback and the
  ;; listeners see the same decoration (the split's only difference was the key).
  (let [ui (atom [])
        ext (atom [])
        dereg (support/on-event :agent-end #(swap! ext conj %))
        call-count (atom 0)
        agent (support/make-agent-state
               :on-event (fn [e] (swap! ui conj e))
               :max-retries 1
               :base-delay-ms 1)]
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (if (= 1 (swap! call-count inc))
                          (when-let [oe (:on-error opts)]
                            (oe "503 service unavailable"))
                          (do (when-let [ot (:on-text opts)] (ot "ok"))
                              (when-let [od (:on-done opts)] (od :stop))))
                        :done))]
        @(loop/run-agent-turn agent {:message "hi"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (let [ui-ae (filter #(= :agent-end (:type %)) @ui)
            ext-ae (filter #(= :agent-end (:type %)) @ext)]
        (t/is (some true? (map :will-retry ui-ae))
              "the callback carries :will-retry on a retryable attempt")
        (t/is (some true? (map :will-retry ext-ae))
              "and so does the listener table")
        (t/is (= (mapv :will-retry ui-ae) (mapv :will-retry ext-ae))
              "the two sinks see the same agent_end events"))
      (finally (dereg)))))

(t/deftest test-loop-appends-keyed-to-attempt-not-agent-token
  ;; Regression: a newer prompt republishes the agent's :run-token while an
  ;; older attempt is still running (cancel clears running-turn? before the
  ;; run settles). The old attempt's appends must still land in its own
  ;; accumulator via the *run-token* binding, never in the newer token's list.
  (let [events (atom [])
        clobbered (atom nil)
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    ;; Simulate a new prompt taking over mid-attempt.
                    (let [tok (Object.)]
                      (reset! clobbered tok)
                      (reset! (:run-token agent) tok)
                      (swap! (:run-messages agent) assoc tok []))
                    (future
                      (when-let [ot (:on-text opts)] (ot "answer"))
                      (when-let [od (:on-done opts)] (od :stop))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
    (let [ae (first (filter #(= :agent-end (:type %)) @events))]
      (t/is (= [:user :assistant] (mapv :role (:messages ae)))
            "the attempt's appends stay in its own accumulator")
      (t/is (empty? (get @(:run-messages agent) @clobbered))
            "the newer token's accumulator is untouched"))))

(t/deftest test-loop-agent-settled-on-unhandled-exception
  ;; pi: agent_settled is emitted from a finally, so it fires even when the
  ;; run throws an unhandled exception (agent-session.js). A throwing UI
  ;; callback on :turn-start stands in for any unexpected failure.
  (let [events (atom [])
        agent (support/make-agent-state
               :on-event (fn [e]
                           (swap! events conj e)
                           (when (= :turn-start (:type e))
                             (throw (ex-info "ui boom" {})))))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (fn [_] (future :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
    (t/is (= 1 (count (filter #(= :agent-settled (:type %)) @events)))
          ":agent-settled fires exactly once from the finally")
    (t/is (= :idle @(:status agent)) "the agent is idle afterwards")))

(t/deftest test-loop-retry-records-failed-attempt
  ;; pi parity: a transient failure's partial is persisted to the session
  ;; (message_end → appendMessage) but removed from agent state before the
  ;; retried call (_prepareRetry) — history keeps it, the request doesn't.
  (let [dir (test-tmp/temp-dir! "test-loop-failed-attempt-")
        events (atom [])
        call-count (atom 0)
        sess (session/create-session dir)
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :session sess
               :max-retries 2
               :base-delay-ms 1)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (case (swap! call-count inc)
                        1 (do (when-let [ot (:on-text opts)] (ot "partial answer"))
                              (when-let [oe (:on-error opts)] (oe "upstream connect error")))
                        (do (when-let [ot (:on-text opts)] (ot "recovered"))
                            (when-let [od (:on-done opts)] (od :stop))))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-done (fn [_]) :on-error (fn [_])}))
    ;; The errored attempt finalizes as :message-end before the backoff starts
    (let [end-idx (first (keep-indexed (fn [i e]
                                         (when (and (= :message-end (:type e))
                                                    (= :error (:stop-reason (:message e))))
                                           i))
                                       @events))
          start-idx (first (keep-indexed (fn [i e]
                                           (when (= :auto-retry-start (:type e)) i))
                                         @events))]
      (t/is (and end-idx start-idx))
      (t/is (< end-idx start-idx) "failed attempt finalizes before the retry countdown")
      (let [msg (:message (nth @events end-idx))]
        (t/is (= "partial answer" (get-in msg [:content 0 :text])))
        (t/is (= "upstream connect error" (:error-message msg)))))
    ;; Session history keeps the failed entry…
    (let [errored (->> (session/get-branch sess)
                       (filterv #(= :error (:stop-reason %))))]
      (t/is (= 1 (count errored)))
      (t/is (= "partial answer" (get-in (first errored) [:content 0 :text])))
      (t/is (= "upstream connect error" (:error-message (first errored)))))
    ;; …and agent-end reports the run's messages, the failed attempt included
    ;; (pi: the errored message is pushed into newMessages before the
    ;; stopReason check, so agent_end carries it even though agent state drops
    ;; it — _prepareRetry).
    (let [ae (first (filter #(= :agent-end (:type %)) @events))]
      (t/is (some #(= "partial answer" (get-in % [:content 0 :text]))
                  (:messages ae))
            ":agent-end reports the failed attempt"))
    ;; …but the live context only carries the user prompt and the recovery
    (let [ctx (state/get-context agent)]
      (t/is (empty? (filter #(= :error (:stop-reason %)) ctx))
            "errored attempt excluded from the live context")
      (t/is (some #(and (= :assistant (:role %))
                        (= "recovered" (get-in % [:content 0 :text])))
                  ctx)))))

(t/deftest test-loop-retry-omits-failed-attempt-from-projection
  ;; pi parity (_prepareRetry → _omitRecoveryAttempt): the failed attempt
  ;; stays in raw history but is durably omitted from the model projection —
  ;; the retried request does not re-send it, and a later rebuild cannot
  ;; resurrect it.
  (let [dir (test-tmp/temp-dir! "test-loop-retry-omits-")
        requests (atom [])
        call-count (atom 0)
        sess (session/create-session dir)
        agent (support/make-agent-state
               :session sess
               :max-retries 2
               :base-delay-ms 1)]
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (swap! requests conj opts)
                      (future
                        (case (swap! call-count inc)
                          1 (do (when-let [ot (:on-text opts)] (ot "partial answer"))
                                (when-let [oe (:on-error opts)] (oe "upstream connect error")))
                          (do (when-let [ot (:on-text opts)] (ot "recovered"))
                              (when-let [od (:on-done opts)] (od :stop))))
                        :done))]
        @(loop/run-agent-turn agent {:message "hi"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (let [branch (session/get-branch sess)
            errored (first (filter #(= :error (:stop-reason %)) branch))
            edit (first (filter #(= :context-edit (:role %)) branch))]
        (t/is (= 2 @call-count) "one retry")
        (t/is (some? errored) "the failed attempt stays in raw history")
        (t/is (= (:id errored) (:target-id edit))
              "a nil context edit targets the persisted failed entry")
        (t/is (nil? (:replacement edit)))
        (t/is (not-any? #(= "partial answer" (get-in % [:content 0 :text]))
                        (session/build-context-messages sess))
              "the projection omits the failed attempt")
        (t/is (not-any? #(= "partial answer" (get-in % [:content 0 :text]))
                        (:messages (second @requests)))
              "the retried request does not re-send it"))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-retry-omits-failed-attempt-after-a-context-rebuild
  ;; a turn-end boundary's committed context-edit draft rebuilds the live
  ;; context from the session and thereby pulls the persisted failed attempt
  ;; in; the recovery omission's own rebuild must drop it again *before* the
  ;; retry attempt starts (pi: _omitRecoveryAttempt → _refreshFinalizedContext),
  ;; not merely have the next request's install clean it up
  (let [dir (test-tmp/temp-dir! "test-loop-retry-omit-rebuild-")
        requests (atom [])
        call-count (atom 0)
        boundaries (atom 0)
        starts (atom [])
        agent-ref (atom nil)
        sess (session/create-session dir)
        agent (support/make-agent-state
               :session sess
               :max-retries 2
               :base-delay-ms 1
               :on-event (fn [e]
                           (when (= :agent-start (:type e))
                             (swap! starts conj (state/get-context @agent-ref)))))]
    (try
      (reset! agent-ref agent)
      (support/on-event
       :turn-end
       (fn [_]
         (when (= 1 (swap! boundaries inc))
           {:entries [{:role :context-edit
                       :target-id (:id (first (session/get-branch sess)))
                       :replacement {:content [{:type :text :text "rewritten"}]}}]})))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (swap! requests conj opts)
                      (future
                        (case (swap! call-count inc)
                          1 (do (when-let [ot (:on-text opts)] (ot "partial answer"))
                                (when-let [oe (:on-error opts)] (oe "upstream connect error")))
                          (do (when-let [ot (:on-text opts)] (ot "recovered"))
                              (when-let [od (:on-done opts)] (od :stop))))
                        :done))]
        @(loop/run-agent-turn agent {:message "hi"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (t/is (= 2 @boundaries)
            "the boundary ran for the failed turn and the successful retry turn")
      (t/is (= 2 (count @starts)) "one :agent-start per attempt")
      (t/is (not-any? #(= "partial answer" (get-in % [:content 0 :text]))
                      (second @starts))
            "the omission's rebuild dropped the failed attempt before the retry
             attempt started — no request install involved yet")
      (let [retried (:messages (second @requests))
            texts (mapv #(get-in % [:content 0 :text]) retried)]
        (t/is (some #{"rewritten"} texts)
              "the boundary's rebuild reached the retried request")
        (t/is (not-any? #{"partial answer"} texts)
              "the retried request does not re-send the failed attempt"))
      (t/is (not-any? #(= "partial answer" (get-in % [:content 0 :text]))
                      (state/get-context agent))
            "the live context excludes the failed attempt")
      (finally
        (support/clear-listeners!)
        (fs/delete-tree dir)))))

(t/deftest test-loop-cancel-records-aborted-attempt
  ;; pi parity: an abort mid-stream finalizes the partial as a
  ;; stopReason-aborted assistant message persisted to the session (never
  ;; the live context) — history keeps what the model managed to say.
  (let [dir (test-tmp/temp-dir! "test-loop-aborted-attempt-")
        events (atom [])
        started (promise)
        sess (session/create-session dir)
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :session sess)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  ;; Stream a little, then leave the call unresolved. The
                  ;; start signal replaces a timing sleep.
                  (fn [opts]
                    (when-let [ot (:on-text opts)]
                      (ot "half a thought"))
                    (deliver started true)
                    (promise))]
      (let [fut (loop/run-agent-turn agent
                                     {:message "hi"
                                      :on-done (fn [_])
                                      :on-error (fn [_])})]
        (t/is (true? (deref started 2000 false)) "the partial stream landed")
        (loop/cancel-turn agent)
        (t/is (not= ::timeout (deref fut 2000 ::timeout)) "cancel settles the run")
        (let [ends (filter #(and (= :message-end (:type %))
                                 (= :aborted (:stop-reason (:message %))))
                           @events)]
          (t/is (= 1 (count ends)) "aborted attempt finalizes as :message-end")
          (t/is (= "half a thought" (get-in (:message (first ends)) [:content 0 :text]))))
        (let [aborted (->> (session/get-branch sess)
                           (filterv #(= :aborted (:stop-reason %))))]
          (t/is (= 1 (count aborted)))
          (t/is (= :assistant (:role (first aborted))))
          (t/is (= "half a thought" (get-in (first aborted) [:content 0 :text]))))
        (t/is (empty? (filterv #(= :assistant (:role %)) (state/get-context agent)))
              "aborted attempt excluded from the live context")
        (let [ae (first (filter #(= :agent-end (:type %)) @events))]
          (t/is (some #(= "half a thought" (get-in % [:content 0 :text]))
                      (:messages ae))
                ":agent-end reports the aborted partial"))
        (t/is (= :idle @(:status agent)))
        (try (fs/delete-tree dir) (catch Exception _ nil))))))

(t/deftest test-loop-thinking-signature-captured
  ;; pi parity: the assistant message records provider/model provenance plus
  ;; the opaque reasoning signature (anthropic signature_delta / gemini
  ;; thoughtSignature) — converters replay it only for same-provider-same-model
  ;; messages and degrade everything else to plain text.
  (let [agent (support/make-agent-state :provider :anthropic :model "claude-test")]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [ot (:on-thinking opts)] (ot "reasoning"))
                      (when-let [os (:on-signature opts)] (os "SIG123"))
                      (when-let [od (:on-done opts)] (od :end-turn))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi"
                                   :on-done (fn [_])
                                   :on-error (fn [_])}))
    (let [a (last (state/get-context agent))]
      (t/is (= :assistant (:role a)))
      (t/is (= "reasoning" (:thinking a)))
      (t/is (= "SIG123" (:thinking-signature a)))
      (t/is (= :anthropic (:provider a)))
      (t/is (= "claude-test" (:model a))))))

(t/deftest test-loop-empty-completion-settles-quietly
  ;; pi parity (agent-loop runLoop): a clean stream with zero content is a
  ;; normal final response — the run settles without an error. The empty
  ;; assistant entry is recorded like any other; request builders drop it
  ;; from outgoing calls so it can't poison later turns (see kmet.ai api
  ;; converters). No auto-retry for empties — that is kmet-specific behavior
  ;; pi does not have and we do not want.
  (let [calls (atom 0)
        errors (atom [])
        agent (support/make-agent-state)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! calls inc)
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi"
                                   :on-done (fn [_])
                                   :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= 1 @calls) "no auto-retry is scheduled")
    (t/is (empty? @errors) "no error surfaces")
    (t/is (= :idle @(:status agent)) "the run settles quietly")
    (t/is (= [:user :assistant] (mapv :role (state/get-context agent)))
          "the empty assistant entry is recorded like any other")))

(t/deftest test-loop-error-stop-reason-folds-to-error
  ;; pi mapStopReason parity: a provider-delivered :error stop-reason
  ;; (content_filter / network_error / unknown) must NOT be recorded as a
  ;; normal empty completion. The loop folds it into :error so the retry
  ;; machinery and the user-visible error path engage. Here it arrives via
  ;; on-done (defensive — openai-completions already routes it to
  ;; on-error); the same fold applies.
  (let [calls (atom 0)
        errors (atom [])
        agent (support/make-agent-state :max-retries 0)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! calls inc)
                      (when-let [on-done (:on-done opts)]
                        (on-done :error))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi"
                                   :on-done (fn [_])
                                   :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= 1 @calls) "no auto-retry (max-retries 0)")
    (t/is (= 1 (count @errors)) "the error surfaces to the UI")
    (t/is (= "Provider stopped with: error" (first @errors)))
    (t/is (= :idle @(:status agent))
          "run ends idle — a terminal error settles the agent (pi: agent_settled)")
    (t/is (empty? (filterv #(= :assistant (:role %)) (state/get-context agent)))
          "the errored attempt is excluded from the live context")))

(t/deftest test-loop-auto-retry-on-network-error
  ;; Regression: a connect-time failure used to surface as "Request failed:
  ;; ConnectException" (nil JVM message), which matched no retryable pattern —
  ;; auto-retry silently died. The llm layer now reports it as "network error:
  ;; ConnectException", which must be retried like pi's "fetch failed".
  (let [events (atom [])]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "network error: ConnectException"))
                      :done))]
      (let [agent (support/make-agent-state
                   :on-event (fn [e] (swap! events conj e))
                   :max-retries 2
                   :base-delay-ms 1)]
        @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])})))
    (let [starts (filter #(= :auto-retry-start (:type %)) @events)]
      (t/is (= 2 (count starts)) "network errors retry with backoff")
      (t/is (= "network error: ConnectException" (:error-message (first starts)))))
    (t/is (some #(and (= :auto-retry-end (:type %)) (false? (:success %))
                      (= "network error: ConnectException" (:final-error %)))
                @events) "terminal error after retries exhausted")))

(t/deftest test-loop-no-retry-on-quota-error
  (let [events (atom [])
        errors (atom [])
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 3
               :base-delay-ms 1)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "insufficient_quota"))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= 1 (count @errors)) "quota errors are never retried")
    (t/is (not-any? #(= :auto-retry-start (:type %)) @events))))

(t/deftest test-loop-no-retry-on-context-overflow
  (let [events (atom [])
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 3
               :base-delay-ms 1)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "prompt is too long: 213462 tokens > 200000 maximum"))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
    (t/is (not-any? #(= :auto-retry-start (:type %)) @events)
          "overflow is not retried (compaction handles it)")))

(t/deftest test-loop-retry-cancel-during-backoff
  (let [events (atom [])
        retry-started (promise)
        agent (support/make-agent-state
               :on-event (fn [e]
                           (swap! events conj e)
                           (when (= :auto-retry-start (:type e))
                             (deliver retry-started true)))
               :max-retries 3
               :base-delay-ms 5000)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "connection lost"))
                      :done))]
      (let [fut (loop/run-agent-turn agent {:message "hi" :on-error (fn [_])})]
        (t/is (true? (deref retry-started 2000 false)) "retry backoff started")
        (loop/cancel-turn agent)
        (t/is (not= ::timeout (deref fut 2000 ::timeout))
              "cancel interrupts the backoff")))
    (let [ends (filter #(= :auto-retry-end (:type %)) @events)]
      (t/is (= 1 (count ends)))
      (t/is (false? (:success (first ends))))
      (t/is (= "Retry cancelled" (:final-error (first ends)))))
    (t/is (= :idle @(:status agent)) "run settles idle after cancel during backoff")))

(t/deftest ^:slow test-loop-retry-resets-count-on-success-then-error
  (let [events (atom [])
        call-count (atom 0)
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 1
               :base-delay-ms 500)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (case (swap! call-count inc)
                        1 (when-let [on-error (:on-error opts)]
                            (on-error "timeout"))
                        2 (do (when-let [on-text (:on-text opts)]
                                (on-text "first success"))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop)))
                        3 (when-let [on-error (:on-error opts)]
                            (on-error "503 service unavailable"))
                        (when-let [on-error (:on-error opts)]
                          (on-error "503 service unavailable")))
                      :done))]
      ;; Turn 1 fails once, retries after 500ms backoff, succeeds.
      ;; Queue a follow-up during the backoff so the run continues into turn 2.
      (let [fut (loop/run-agent-turn agent {:message "hi" :on-error (fn [_])})]
        (Thread/sleep 100)
        (state/follow-up! agent "second")
        @fut))
    (let [ends (filter #(= :auto-retry-end (:type %)) @events)]
      ;; First turn: retry succeeded (1 end, success). Second turn: 503 is
      ;; retryable but budget is fresh — one more retry → exhausted.
      (t/is (= 2 (count ends)))
      (t/is (true? (:success (first ends))))
      (t/is (false? (:success (second ends)))))))
