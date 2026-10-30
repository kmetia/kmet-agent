(ns kmet.app.loop.test-compact
  "Compaction orchestration: threshold and window triggers, summarization and its
   failures, extension-supplied compaction, manual runs, and branch summaries."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.ai.llm :as llm]
            [kmet.ai.models :as models]
            [kmet.ai.usage :as usage]
            [kmet.app.session.core :as session]
            [kmet.app.compaction :as compact]
            [kmet.app.loop.state :as state]
            [kmet.app.loop :as loop]
            [kmet.app.config :as cfg]
            [kmet.app.debug :as debug]
            [kmet.app.tools.registry :as tools-registry]
            [kmet.ui.tui.chat-history :as ui]
            [kmet.tui.protocols :as protocols]
            [kmet.test-utils :as tu]
            [kmet.app.loop.test-support :as support]))

(t/use-fixtures :each support/with-host)

;; ─── Phase 3: compaction ─────────────────────────────────────────────────

(t/deftest test-loop-overflow-compact-retry
  (let [events (atom [])
        main-call-count (atom 0)
        dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :session sess
               :keep-recent-tokens 5)]
    (try
      (dotimes [i 12]
        (session/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (if (seq (:tools opts))
                          ;; main agent call
                          (case (swap! main-call-count inc)
                            1 (when-let [on-error (:on-error opts)]
                                (on-error "prompt is too long: 500000 tokens > 200000 maximum"))
                            (do (when-let [on-text (:on-text opts)]
                                  (on-text "recovered"))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :stop))))
                          ;; summarization call (no tools)
                          (do (t/is (= :none (:cache-retention opts))
                                    "compaction summaries disable prompt caching (pi)")
                              (when-let [on-text (:on-text opts)]
                                (on-text "summary of the old conversation"))
                              (when-let [on-usage (:on-usage opts)]
                                (on-usage {:prompt_tokens 100 :completion_tokens 10
                                           :prompt_tokens_details {:cached_tokens 20}
                                           :cost {:total 0.001}}))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop))))
                        :done))]
        (binding [*err* (java.io.StringWriter.)]
          @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])})))
      (t/is (= 2 @main-call-count) "overflow triggers one compaction then a retry")
      (t/is (some #(and (= :compaction (:role %))
                        (= {:input 80 :output 10 :cache-read 20 :cache-write 0 :cost 0.001}
                           (usage/entry-usage (:usage %))))
                  @(:entries sess))
            "summarization usage is recorded on the compaction entry (pi: CompactionEntry.usage)")
      (t/is (= 0.001 (:cost (session/usage-totals sess)))
            "compaction cost lands in the session totals (footer $)")
      (t/is (some #(= :compaction (:role %)) @(:entries sess))
            "overflow appends a compaction entry (append-only)")
      (t/is (< (count (session/build-context sess)) 12)
            "compaction excludes the summarized history from context")
      (let [edit (first (filter #(= :context-edit (:role %)) @(:entries sess)))
            target (session/get-entry sess (:target-id edit))]
        (t/is (some? edit)
              "the failed overflow attempt is durably omitted (pi: _omitRecoveryAttempt)")
        (t/is (nil? (:replacement edit)))
        (t/is (= :assistant (:role target)))
        (t/is (= :error (:stop-reason target)))
        (t/is (not-any? #(= (:id target) (:id %)) (session/build-context-messages sess))
              "the omitted attempt is not in the projection"))
      (t/is (some #(and (= :message-end (:type %))
                        (= "recovered" (get-in % [:message :content 0 :text])))
                  @events)
            "retried call succeeds after compaction")
      (t/is (some #(= :context-replaced (:type %)) @events)
            "overflow compaction mirrors the new context to the UI (pi: compaction_end re-renders)")
      (t/is (= :idle @(:status agent)) "run settles idle after the overflow recovery")
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-compaction-input-follows-the-projection
  ;; pi: findProjectedCutPoint / getMessagesFromProjectedEntryForCompaction —
  ;; a compaction summarizes the projected context: an omitted message is not
  ;; summarized and does not inflate :tokens-before, and a rewritten one is
  ;; summarized with its replacement text
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-compaction-projection")))
        prompts (atom [])
        sess (session/create-session dir)
        agent (support/make-agent-state :session sess :keep-recent-tokens 30)]
    (try
      (let [user! (fn [c]
                    (session/append-entry
                     sess {:role :user
                           :content [{:type :text
                                      :text (apply str (repeat 100 c))}]}))
            u0 (user! "0")
            u1 (user! "1")]
        (user! "2")
        (user! "3")
        (user! "4")
        (session/append-context-edit! sess (:id u0) nil)
        (session/append-context-edit! sess (:id u1)
                                      {:content [{:type :text :text "rewritten"}]}))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (swap! prompts conj opts)
                      (future
                        (when-let [ot (:on-text opts)] (ot "summary"))
                        (when-let [od (:on-done opts)] (od :stop))
                        :done))]
        (t/is (true? (compact/compact-context! agent nil :manual))))
      (let [text (-> @prompts first :messages second :content first :text)
            compaction-entry (last (session/get-branch sess))]
        (t/is (str/includes? text "rewritten")
              "the rewritten message is summarized with its replacement")
        (t/is (not (str/includes? text (apply str (repeat 100 "0"))))
              "the omitted message is not summarized")
        (t/is (not (str/includes? text (apply str (repeat 100 "1"))))
              "the rewritten message's original text is not summarized")
        (t/is (str/includes? text (apply str (repeat 100 "2"))))
        (t/is (= 78 (:tokens-before compaction-entry))
              "tokens-before counts the projection (replacement + three kept)"))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-overflow-compaction-failure-settles-terminal
  ;; pi: when the overflow-recovery compaction cannot run (the summarization
  ;; failed or was aborted), _checkCompaction returns false and the prompt
  ;; settles instead of retrying into the same overflow. kmet surfaces the
  ;; overflow so the turn is not silent.
  (let [events (atom [])
        errors (atom [])
        main-call-count (atom 0)
        dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :session sess
               :keep-recent-tokens 5)]
    (try
      (dotimes [i 12]
        (session/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (if (seq (:tools opts))
                          (do (swap! main-call-count inc)
                              (when-let [on-error (:on-error opts)]
                                (on-error "prompt is too long: 500000 tokens > 200000 maximum")))
                          ;; summarization fails
                          (when-let [on-error (:on-error opts)]
                            (on-error "summary stream failed")))
                        :done))]
        (binding [*err* (java.io.StringWriter.)]
          @(loop/run-agent-turn agent {:message "hi"
                                       :on-error (fn [e] (swap! errors conj e))})))
      (t/is (= 1 @main-call-count) "no retry after the compaction could not run")
      (t/is (= 1 (count @errors)) "the overflow surfaces to the UI")
      (t/is (some #(and (= :error (:type %))
                        (str/includes? (:message %) "prompt is too long"))
                  @events)
            "the terminal error is the overflow, not a compaction error")
      (t/is (= 1 (count (filter #(= :agent-settled (:type %)) @events))))
      (t/is (= :idle @(:status agent)))
      (finally (fs/delete-tree dir)))))

;; ─── Threshold, summarization and manual compaction ─────────────────────

(t/deftest test-loop-token-threshold-compaction
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        events (atom [])
        sess (session/create-session (str dir))
        agent (support/make-agent-state
               :session sess
               :on-event (fn [e] (swap! events conj e))
               :compact-token-threshold 10
               :keep-recent-tokens 40)]
    (try
      (doseq [i (range 10)]
        (let [m {:role :user
                 :content [{:type :text :text
                            (str "This is message body number " i
                                 " with plenty of words so the estimated token count "
                                 "easily exceeds the small test threshold.")}]}]
          (swap! (:messages agent) conj m)
          (session/append-entry sess m)))
      (t/is (true? (binding [*err* (java.io.StringWriter.)]
                     (support/with-summarization-stub #(compact/maybe-compact! agent))))
            "token estimate above threshold triggers compaction")
      (t/is (some #(and (= :compaction (:role %))
                        (= "summary of the old conversation" (:summary %)))
                  @(:entries sess))
            "LLM summary lands on the compaction entry")
      (t/is (= 11 (count @(:entries sess)))
            "append-only: all 10 entries stay, compaction entry added")
      (t/is (< (count @(:messages agent)) 10) "in-memory context aligned with session")
      (t/is (= (count @(:messages agent)) (count (session/build-context sess)))
            "messages mirror the compacted session context (compaction + kept tail)")
      (t/is (some #(and (= :context-replaced (:type %))
                        (= (count @(:messages agent)) (count (:messages %))))
                  @events)
            "auto-compaction mirrors the new context to the UI (pi: compaction_end re-renders the chat)")
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-agent-end-after-mid-run-compaction
  ;; Regression (the live "subvec index out of range: 300 62" report):
  ;; agent-end used to slice the context with the message count captured at
  ;; run start. A mid-run auto-compaction (tools-phase! → maybe-compact!)
  ;; replaces the context vector with a much shorter one, so the stale index
  ;; ran past its end and the run's catch settled an otherwise completed turn
  ;; as an error.
  ;;
  ;; pi parity: agent_end reports the run's accumulated messages (newMessages
  ;; — agent-loop.ts), not a slice of the live context, so a message the
  ;; compaction dropped is still in the event.
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        events (atom [])
        errors (atom [])
        llm-calls (atom 0)
        compact-calls (atom 0)
        real-compact compact/maybe-compact!
        sess (session/create-session (str dir))
        agent (support/make-agent-state
               :session sess
               :on-event (fn [e] (swap! events conj e))
               :compact-token-threshold 10
               :keep-recent-tokens 40)]
    (try
      ;; Seed a context well over the threshold (the reported session had
      ;; ~300 messages before its compaction).
      (doseq [i (range 10)]
        (let [m {:role :user
                 :content [{:type :text :text
                            (str "This is message body number " i
                                 " with plenty of words so the estimated token count "
                                 "easily exceeds the small test threshold.")}]}]
          (swap! (:messages agent) conj m)
          (session/append-entry sess m)))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    ;; Run only the mid-run check (after the tool batch) for
                    ;; real — the trigger the reported session hit; the pre-run
                    ;; and post-run (D) checks are skipped.
                    compact/maybe-compact! (fn [a]
                                             (when (= 2 (swap! compact-calls inc))
                                               (real-compact a)))
                    llm/send-message
                    (fn [opts]
                      (future
                        (if (empty? (:tools opts))
                          ;; compaction summarization (no tools)
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "summary of the old conversation"))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop)))
                          ;; the run: one tool call, then a final text
                          (if (= 1 (swap! llm-calls inc))
                            (do (when-let [on-tc (:on-tool-call opts)]
                                  ;; The sizes put the mid-run cut point at the
                                  ;; tool-call assistant message (budget 40:
                                  ;; result ~30, result + args ~54), so the
                                  ;; compaction demonstrably drops the run's
                                  ;; user message from the live context.
                                  (on-tc {:id "tc1" :name "bash" :index 0
                                          :arguments (str "{\"command\": \""
                                                          (apply str (repeat 76 "x"))
                                                          "\"}")}))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :tool-calls)))
                            (do (when-let [on-text (:on-text opts)]
                                  (on-text "final answer"))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :stop)))))
                        :done))
                    state/execute-tool-for (fn [_ _ _ _]
                                             {:content (apply str (repeat 10 "tool output "))
                                              :is-error false})]
        ;; deref inside with-redefs keeps the rebinding until the turn completes
        @(loop/run-agent-turn agent {:message "final question"
                                     :on-error (fn [e] (swap! errors conj e))}))
      (t/is (empty? @errors) "a context replacement must not fail the run")
      (t/is (= 3 @compact-calls)
            "compaction checked pre-run, mid-run, and post-run (D)")
      (t/is (some #(= :compaction (:role %)) @(:entries sess))
            "the mid-run compaction happened")
      (let [ae (first (filter #(= :agent-end (:type %)) @events))
            run-msgs (:messages ae)]
        (t/is (some? ae) ":agent-end emitted")
        (t/is (= [:user :assistant :tool :assistant] (mapv :role run-msgs))
              ":agent-end carries exactly the run's messages, in order")
        (t/is (= 1 (count (filter #(and (= :user (:role %))
                                        (= "final question" (get-in % [:content 0 :text])))
                                  run-msgs)))
              "the run's user message is reported exactly once")
        (t/is (some #(and (= :assistant (:role %)) (seq (:tool-calls %))) run-msgs)
              "the tool-call assistant message is reported")
        (t/is (some #(= (apply str (repeat 10 "tool output "))
                        (get-in % [:content 0 :content]))
                    (filter #(= :tool (:role %)) run-msgs))
              "the tool result is reported")
        (t/is (some #(= "final answer" (get-in % [:content 0 :text])) run-msgs)
              ":agent-end carries the message added after the compaction")
        (t/is (not (some #(= "final question" (get-in % [:content 0 :text]))
                         (state/get-context agent)))
              "the mid-run compaction dropped the user message from the live
               context — agent-end still reports it (pi: newMessages)"))
      (t/is (= :idle @(:status agent)))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-post-run-threshold-compaction
  ;; pi: _handlePostAgentRun runs the compaction check after a settled
  ;; attempt, before the queued-message continuation and the pre-settle
  ;; boundary. A threshold compaction never retries the turn.
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        events (atom [])
        agent (support/make-agent-state
               :session sess
               :compact-token-threshold 100
               :keep-recent-tokens 40
               :on-event (fn [e] (swap! events conj e)))]
    (try
      ;; The session starts below the threshold; the run's long final answer
      ;; pushes the estimate over it, so only the post-run check fires.
      (session/append-entry sess {:role :user
                                  :content [{:type :text :text "hi"}]})
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (if (:tools opts)
                          ;; the run: one long final text response
                          (do (when-let [on-text (:on-text opts)]
                                (on-text (apply str (repeat 2000 "x"))))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop)))
                          ;; the summarization call
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "summary of the old conversation"))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop))))
                        :done))]
        (binding [*err* (java.io.StringWriter.)]
          @(loop/run-agent-turn agent {:message "go" :on-error (fn [_])})))
      (let [types (mapv :type @events)
            idx (fn [t] (.indexOf types t))]
        (t/is (some #(and (= :compaction-start (:type %))
                          (= :threshold (:reason %)))
                    @events)
              "the post-run check compacts the context")
        (t/is (= 1 (count (filter #(= :compaction-start (:type %)) @events)))
              "only the post-run check ran (the pre-run context is below the
               threshold)")
        (t/is (some #(= :compaction (:role %)) @(:entries sess))
              "a compaction entry was appended")
        (t/is (< (idx :agent-end) (idx :compaction-start))
              ":agent-end precedes the post-run compaction")
        (t/is (< (idx :compaction-start) (idx :compaction-end))
              "compaction-start precedes compaction-end")
        (t/is (< (idx :compaction-end) (idx :agent-settled))
              "compaction-end precedes the prompt's settle")
        (t/is (= 1 (count (filter #(= :agent-start (:type %)) @events)))
              "no extra run (pi: a threshold compaction does not retry)")
        (t/is (= 1 (count (filter #(= :agent-settled (:type %)) @events))))
        (t/is (= :idle @(:status agent))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-loop-threshold-compaction-measures-the-projection
  ;; K: with a context edit present the threshold check measures the
  ;; projection (pi: estimateProjectedContextTokens) — a message the session
  ;; still stores but the provider no longer sees must not count
  (let [stub (fn [opts]
               (future
                 (when-let [on-text (:on-text opts)] (on-text "ok"))
                 (when-let [on-done (:on-done opts)] (on-done :stop))
                 :done))
        compactions (fn [omit?]
                      (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
                            sess (session/create-session (str dir))
                            events (atom [])
                            agent (support/make-agent-state
                                   :session sess
                                   :compact-token-threshold 100
                                   :on-event (fn [e] (swap! events conj e)))]
                        (try
                          ;; ~1000 estimated tokens on the raw branch
                          (let [u (session/append-entry
                                   sess {:role :user
                                         :content [{:type :text
                                                    :text (apply str (repeat 4000 "x"))}]})]
                            (when omit?
                              (session/append-context-edit! sess (:id u) nil))
                            (with-redefs [cfg/get-api-key (fn [_] "test-key")
                                          llm/send-message stub]
                              (binding [*err* (java.io.StringWriter.)]
                                @(loop/run-agent-turn agent {:message "go"
                                                             :on-error (fn [_])}))))
                          (count (filter #(= :compaction-start (:type %)) @events))
                          (finally (fs/delete-tree dir)))))]
    (t/is (pos? (compactions false))
          "without an edit the raw context drives the check")
    (t/is (zero? (compactions true))
          "the omitted message leaves the projection below the threshold")))

(t/deftest test-loop-post-run-compaction-not-retriggered-after-failure
  ;; An auto compaction that failed (or was aborted) stands down for the rest
  ;; of the prompt: the context is unchanged, so a later check would retry
  ;; the same summarization. Regression for D — without the guard the
  ;; post-run check runs the failing compaction again.
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        events (atom [])
        agent (support/make-agent-state
               :session sess
               :compact-token-threshold 10
               :keep-recent-tokens 40
               :on-event (fn [e] (swap! events conj e)))]
    (try
      (dotimes [i 6]
        (session/append-entry sess
                              {:role :user
                               :content [{:type :text :text
                                          (str "This is message body number " i
                                               " with plenty of words so the estimated token count "
                                               "easily exceeds the small test threshold.")}]}))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    ;; the run answers normally; the summarization call fails
                    llm/send-message
                    (fn [opts]
                      (future
                        (if (:tools opts)
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "answer"))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop)))
                          (when-let [on-error (:on-error opts)]
                            (on-error "summary stream failed")))
                        :done))]
        (binding [*err* (java.io.StringWriter.)]
          @(loop/run-agent-turn agent {:message "go" :on-error (fn [_])})))
      (t/is (= 1 (count (filter #(= :compaction-start (:type %)) @events)))
            "the failed pre-run compaction is not retried by the post-run check")
      (t/is (some #(and (= :compaction-end (:type %)) (:error-message %)) @events)
            "the failure was reported")
      (t/is (not-any? #(= :error (:type %)) @events)
            "the run itself completed")
      (t/is (= 1 (count (filter #(= :agent-start (:type %)) @events))))
      (t/is (some #(= :agent-settled (:type %)) @events)
            "the prompt settled")
      (finally (fs/delete-tree dir)))))

(t/deftest test-loop-compaction-failure-reports-the-cause
  (t/testing "a failed compaction reports the summarization failure with pi's
              per-reason prefix (threshold: `Auto-compaction failed: …`)"
    (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
          sess (session/create-session (str dir))
          events (atom [])
          agent (support/make-agent-state
                 :session sess
                 :compact-token-threshold 10
                 :keep-recent-tokens 40
                 :on-event (fn [e] (swap! events conj e)))]
      (try
        (support/seed-context! sess 6)
        ;; no retries: the cause must surface after a single attempt
        (swap! (:cfg agent) assoc :max-retries 0)
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (future
                          (if (:tools opts)
                            (do (when-let [on-text (:on-text opts)] (on-text "answer"))
                                (when-let [on-done (:on-done opts)] (on-done :stop)))
                            (when-let [on-error (:on-error opts)]
                              (on-error "Stream idle timeout after 60000ms")))
                          :done))]
          (binding [*err* (java.io.StringWriter.)]
            @(loop/run-agent-turn agent {:message "go" :on-error (fn [_])})))
        (let [end (first (filter #(= :compaction-end (:type %)) @events))
              failed (first (filter #(= :session-compact-failed (:type %)) @events))]
          (t/is (= "Auto-compaction failed: Summarization failed: Stream idle timeout after 60000ms"
                   (:error-message end))
                "the cause rides pi's reason-specific prefix")
          (t/is (= (:error-message end) (:error-message failed))
                "session-compact-failed repeats the same message (pi)")
          (t/is (false? (:will-retry failed))
                "a failed compaction never retries the turn (pi)"))
        (t/is (not-any? #(= :summarization-retry-scheduled (:type %)) @events)
              "a spent retry budget means one attempt")
        (finally (fs/delete-tree dir)))))
  (t/testing "a manual compaction reports `Compaction failed: …`"
    (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
          sess (session/create-session (str dir))
          events (atom [])
          agent (support/make-agent-state
                 :session sess
                 :compact-token-threshold 10
                 :keep-recent-tokens 40
                 :on-event (fn [e] (swap! events conj e)))]
      (try
        (support/seed-context! sess 6)
        (swap! (:cfg agent) assoc :max-retries 0)
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (future
                          (when-let [on-error (:on-error opts)]
                            (on-error "provider exploded"))
                          :done))]
          (t/is (= :failed
                   (binding [*err* (java.io.StringWriter.)]
                     (compact/compact-context! agent "focus on tests" :manual))))
          (t/is (= "Compaction failed: Summarization failed: provider exploded"
                   (:error-message (first (filter #(= :compaction-end (:type %))
                                                  @events)))))
          (t/is (= "Compaction failed: Summarization failed: provider exploded"
                   @(:compaction-error agent))
                "the message /compact's error path reports (pi: compact() rejects)"))
        (finally (fs/delete-tree dir))))))

(t/deftest test-loop-summarization-call-exception
  ;; A summarization call that throws synchronously (auth, request setup, a
  ;; payload hook) is reported like any other failure: the retry driver sees
  ;; an :error result and the compaction still ends with its failure events,
  ;; so the UI never sticks on the compaction indicator (pi: the stream fn
  ;; reports failures, it never throws into the summarization driver).
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        events (atom [])
        agent (support/make-agent-state
               :session sess
               :compact-token-threshold 10
               :keep-recent-tokens 40
               :on-event (fn [e] (swap! events conj e)))]
    (try
      (support/seed-context! sess 6)
      (swap! (:cfg agent) assoc :max-retries 0)
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [_opts]
                      (throw (ex-info (str "network error: Proxy request failed: curl: (97) "
                                           "Failed to receive SOCKS response, proxy closed connection")
                                      {:type :transport-error})))]
        (let [result (binding [*err* (java.io.StringWriter.)]
                       (compact/compact-context! agent nil :manual))]
          (t/is (= :failed result) "the throw is a compaction failure")
          (t/is (= (str "Compaction failed: Summarization failed: network error: "
                        "Proxy request failed: curl: (97) Failed to receive SOCKS response, "
                        "proxy closed connection")
                   (:error-message (support/event-of events :compaction-end)))
                "the thrown cause rides pi's failure report")
          (t/is (some #(= :session-compact-failed (:type %)) @events)
                "the failure event still fires (the UI clears on it)")
          (t/is (false? @(:compacting? agent)) "the compaction flag is cleared")))
      (finally (fs/delete-tree dir))))
  (t/testing "a throw after the cancel signal fired reports as aborted"
    (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
          sess (session/create-session (str dir))
          events (atom [])
          agent (support/make-agent-state
                 :session sess
                 :compact-token-threshold 10
                 :keep-recent-tokens 40
                 :on-event (fn [e] (swap! events conj e)))]
      (try
        (support/seed-context! sess 6)
        (swap! (:cfg agent) assoc :max-retries 0)
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [_opts]
                        (reset! (:compaction-signal agent) true)
                        (throw (ex-info "aborted transport" {:type :transport-error})))]
          (t/is (= :aborted
                   (binding [*err* (java.io.StringWriter.)]
                     (compact/compact-context! agent nil :manual))))
          (t/is (true? (:aborted (support/event-of events :compaction-end)))
                "an aborted call reports :aborted, not a failure")
          (t/is (nil? (:error-message (support/event-of events :compaction-end)))
                "pi: an aborted compaction carries no error message"))
        (finally (fs/delete-tree dir))))))

(t/deftest test-loop-compaction-unexpected-exception-still-ends
  ;; pi: compact()/_runAutoCompaction catch any error and still emit
  ;; compaction_end + session_compact_failed. Without the catch the exception
  ;; escapes the caller (an interactive /compact runs it on a future that
  ;; swallows it) and the UI stays on the compaction indicator forever.
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        events (atom [])
        err-logged (atom nil)
        agent (support/make-agent-state
               :session sess
               :compact-token-threshold 10
               :keep-recent-tokens 40
               :on-event (fn [e] (swap! events conj e)))]
    (try
      (support/seed-context! sess 6)
      (with-redefs [session/get-branch (fn [_] (throw (ex-info "session read failed" {})))
                    debug/log-error (fn [& parts] (reset! err-logged (apply str parts)))]
        (t/is (= :failed
                 (binding [*err* (java.io.StringWriter.)]
                   (compact/compact-context! agent nil :manual))))
        (t/is (= "Compaction failed: session read failed"
                 (:error-message (support/event-of events :compaction-end)))
              "the unexpected cause rides the reason-prefixed failure report")
        (t/is (some #(= :session-compact-failed (:type %)) @events)
              "the failure event still fires")
        (t/is (true? @(:compaction-blocked agent))
              "the prompt's automatic checks stand down")
        (t/is (and @err-logged
                   (str/includes? @err-logged "compaction failed unexpectedly"))
              "the unexpected error is logged"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-loop-summarization-retry
  (t/testing "a transient summarization failure is retried with the
              configured backoff, reporting pi's summarization_retry_* events"
    (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
          sess (session/create-session (str dir))
          events (atom [])
          attempts (atom 0)
          agent (support/make-agent-state
                 :session sess
                 :compact-token-threshold 10
                 :keep-recent-tokens 40
                 :on-event (fn [e] (swap! events conj e)))]
      (try
        (support/seed-context! sess 6)
        ;; tiny backoff so the retry is instant
        (swap! (:cfg agent) assoc :max-retries 3
               :base-delay-ms 1 :max-agent-delay-ms 5)
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (future
                          (if (:tools opts)
                            (do (when-let [on-text (:on-text opts)] (on-text "answer"))
                                (when-let [on-done (:on-done opts)] (on-done :stop)))
                            (if (= 1 (swap! attempts inc))
                              (when-let [on-error (:on-error opts)]
                                (on-error "rate limit exceeded"))
                              (do (when-let [on-text (:on-text opts)] (on-text "the summary"))
                                  (when-let [on-done (:on-done opts)] (on-done :stop)))))
                          :done))]
          (binding [*err* (java.io.StringWriter.)]
            @(loop/run-agent-turn agent {:message "go" :on-error (fn [_])})))
        (t/is (= 2 @attempts) "the summarization was attempted twice")
        (t/is (some #(and (= :summarization-retry-scheduled (:type %))
                          (= 1 (:attempt %))
                          (= 3 (:max-attempts %))
                          (= 1 (:delay-ms %))
                          (= "rate limit exceeded" (:error-message %)))
                    @events)
              "the retry countdown reports pi's payload (delay from the policy)")
        (t/is (some #(and (= :summarization-retry-attempt-start (:type %))
                          (= :compaction (:source %))
                          (= :threshold (:reason %)))
                    @events)
              "the retried call reports its source (pi)")
        (t/is (some #(= :summarization-retry-finished (:type %)) @events)
              "the retry loop reports its end (pi)")
        (t/is (some #(and (= :compaction (:role %)) (= "the summary" (:summary %)))
                    @(:entries sess))
              "the retried call's summary lands on the compaction entry")
        (finally (fs/delete-tree dir))))))

(t/deftest test-loop-summarization-honors-the-ctx-retry-policy
  ;; The summarization retries spend the ctx's retry policy too: the stage
  ;; config's budget wins over the run's option — here the option would
  ;; suppress every retry and the ctx allows one (docs/architecture.md, "Reload and the
  ;; turn boundary").
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        events (atom [])
        attempts (atom 0)
        run-ctx (:ctx (support/test-ctx
                       {:retry-policy {:config {:max-retries 1
                                                :base-delay-ms 3
                                                :max-agent-delay-ms 5}}}))
        agent (support/make-agent-state
               :ctx run-ctx
               :session sess
               :compact-token-threshold 10
               :keep-recent-tokens 40
               :max-retries 0
               :base-delay-ms 1
               :max-agent-delay-ms 5
               :on-event (fn [e] (swap! events conj e)))]
    (try
      (support/seed-context! sess 6)
      ;; tiny backoff so the retry is instant
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (if (:tools opts)
                          (do (when-let [on-text (:on-text opts)] (on-text "answer"))
                              (when-let [on-done (:on-done opts)] (on-done :stop)))
                          (if (= 1 (swap! attempts inc))
                            (when-let [on-error (:on-error opts)]
                              (on-error "rate limit exceeded"))
                            (do (when-let [on-text (:on-text opts)] (on-text "the summary"))
                                (when-let [on-done (:on-done opts)] (on-done :stop)))))
                        :done))]
        (binding [*err* (java.io.StringWriter.)]
          @(loop/run-agent-turn agent {:message "go" :on-error (fn [_])})))
      (t/is (= 2 @attempts) "the mount's budget ran the summarization retry")
      (t/is (some #(and (= :summarization-retry-scheduled (:type %))
                        (= 1 (:attempt %))
                        (= 1 (:max-attempts %))
                        (= 3 (:delay-ms %)))
                  @events)
            "the countdown carries the mount's budget and backoff, not the
             run's options")
      (t/is (some #(and (= :compaction (:role %)) (= "the summary" (:summary %)))
                  @(:entries sess))
            "the retried call's summary lands on the compaction entry")
      (finally (fs/delete-tree dir)))))

(t/deftest test-loop-summarization-request-options
  (t/testing "the summarization request carries pi's options: a compaction
              reasons at the session's thinking level, a branch summary sends
              none and caps its output at 4096 tokens (pi:
              createSummarizationOptions / branch-summarization.ts)"
    (models/load-catalogs! nil)
    (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
          sess (session/create-session (str dir))
          seen (atom [])
          agent (support/make-agent-state
                 :session sess
                 :provider :opencode-go
                 :model "deepseek-v4.1-flash"
                 :thinking :xhigh
                 :compact-token-threshold 10
                 :compact-reserve-tokens 1000
                 :keep-recent-tokens 40)]
      (try
        (support/seed-context! sess 6)
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (swap! seen conj (select-keys opts [:thinking :tools :max-tokens]))
                        (future
                          (when-let [on-text (:on-text opts)] (on-text "summary"))
                          (when-let [on-done (:on-done opts)] (on-done :stop))
                          :done))]
          (binding [*err* (java.io.StringWriter.)]
            (compact/maybe-compact! agent)))
        (t/is (= 1 (count @seen)) "one summarization call")
        (t/is (= :xhigh (:thinking (first @seen)))
              "the compaction summary reasons at the session level (pi)")
        (t/is (= 17184 (:max-tokens (first @seen)))
              (str "floor(0.8 * the reserve) plus the :max-effort thinking budget "
                   "(16384) caps the compaction summary (pi: "
                   "adjustMaxTokensForThinking)"))
        (t/is (empty? (:tools (first @seen))) "summarization carries no tools")
        (reset! seen [])
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (swap! seen conj (select-keys opts [:thinking :max-tokens]))
                        (future
                          (when-let [on-text (:on-text opts)] (on-text "branch"))
                          (when-let [on-done (:on-done opts)] (on-done :stop))
                          :done))]
          (let [result (compact/generate-branch-summary
                        agent [{:role :user :content [{:type :text :text "x"}]}])]
            (t/is (str/starts-with? (:summary result)
                                    "The user explored a different conversation branch")
                  "the branch preamble is applied")
            (t/is (nil? (:thinking (first @seen)))
                  "branch summaries send no reasoning (pi)")
            (t/is (= 4096 (:max-tokens (first @seen)))
                  "a flat 4096-token cap (pi)")))
        (finally (fs/delete-tree dir))))))

(t/deftest test-loop-compaction-model-overrides
  ;; pi: compaction.modelOverrides — per-model reserveTokens/keepRecentTokens;
  ;; the exact "provider/modelId" entry resolves before the ordinary settings
  ;; and the resolved map rides the preparation (pi: preparation.settings)
  ;; into the summarization cap.
  (models/load-catalogs! nil)
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})]
    (try
      (t/testing "a matching entry drives the prep and the summarization cap"
        (let [events (atom [])
              seen (atom [])
              sess (session/create-session (str dir))
              agent (support/make-agent-state
                     :session sess
                     :provider :opencode-go
                     :model "deepseek-v4.1-flash"
                     :on-event (fn [e] (swap! events conj e))
                     :context-window 10000
                     :compact-reserve-tokens 1000
                     :keep-recent-tokens 40
                     :compact-model-overrides
                     {"opencode-go/deepseek-v4.1-flash"
                      {:reserve-tokens 8000 :keep-recent-tokens 40}})]
          (support/seed-context! sess 6)
          (with-redefs [cfg/get-api-key (fn [_] "test-key")
                        llm/send-message
                        (fn [opts]
                          (swap! seen conj (select-keys opts [:max-tokens :thinking]))
                          (future
                            (when-let [on-text (:on-text opts)] (on-text "summary"))
                            (when-let [on-done (:on-done opts)] (on-done :stop))
                            :done))]
            (binding [*err* (java.io.StringWriter.)]
              (t/is (true? (compact/compact-context! agent nil :manual)))))
          (t/is (= {:enabled true :reserve-tokens 8000 :keep-recent-tokens 40}
                   (get-in (support/event-of events :session-before-compact)
                           [:preparation :settings]))
                "preparation.settings carries the resolved entry (pi)")
          (t/is (= 6400 (:max-tokens (first @seen)))
                "the summary cap uses the override reserve (0.8 * 8000)")))
      (t/testing "a matching entry drives the window threshold"
        (let [sess (session/create-session (str dir))
              agent (support/make-agent-state
                     :session sess
                     :provider :opencode-go
                     :model "deepseek-v4.1-flash"
                     :context-window 10000
                     :compact-reserve-tokens 1000
                     :keep-recent-tokens 40
                     :compact-model-overrides
                     {"opencode-go/deepseek-v4.1-flash" {:reserve-tokens 8000}})]
          (dotimes [i 50]
            (session/append-entry sess
                                  {:role :user :content [{:type :text :text (str "msg " i)}]}))
          (session/append-entry sess {:role :assistant :content [{:type :text :text "ok"}]
                                      :usage {:prompt_tokens 2000 :completion_tokens 50}})
          (t/is (true? (binding [*err* (java.io.StringWriter.)]
                         (support/with-summarization-stub #(compact/maybe-compact! agent))))
                "measured 2050 >= window-reserve 2000 — the override triggers earlier")))
      (t/testing "a non-matching entry leaves the ordinary settings"
        (let [sess (session/create-session (str dir))
              agent (support/make-agent-state
                     :session sess
                     :provider :opencode-go
                     :model "deepseek-v4.1-flash"
                     :context-window 10000
                     :compact-reserve-tokens 1000
                     :keep-recent-tokens 40
                     :compact-model-overrides
                     {"other-provider/m" {:reserve-tokens 8000}})]
          (dotimes [i 50]
            (session/append-entry sess
                                  {:role :user :content [{:type :text :text (str "msg " i)}]}))
          (session/append-entry sess {:role :assistant :content [{:type :text :text "ok"}]
                                      :usage {:prompt_tokens 2000 :completion_tokens 50}})
          (t/is (false? (compact/maybe-compact! agent))
                "measured 2050 vs window-reserve 9000 — another model's entry must not apply")))
      (finally (fs/delete-tree dir)))))

(t/deftest test-loop-compaction-result-and-session-compact-event
  (t/testing "a successful compaction reports the pi-shaped result map on
              :compaction-end and the appended entry via :session-compact
              (pi: CompactionResult / session_compact)"
    (support/with-compaction-run
      (fn [{:keys [sess events agent]}]
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (future
                          (when-let [on-text (:on-text opts)] (on-text "the summary"))
                          (when-let [on-usage (:on-usage opts)]
                            (on-usage {:prompt_tokens 100 :completion_tokens 10
                                       :cost {:total 0.001}}))
                          (when-let [on-done (:on-done opts)] (on-done :stop))
                          :done))]
          (binding [*err* (java.io.StringWriter.)]
            (compact/maybe-compact! agent)))
        (let [end (support/event-of events :compaction-end)
              compact (support/event-of events :session-compact)
              result (:result end)
              entry (first (filter #(= :compaction (:role %)) @(:entries sess)))
              types (mapv :type @events)]
          (t/is (map? result) "the end carries the result map (pi: CompactionResult)")
          (t/is (= "the summary" (:summary result)))
          (t/is (= (:first-kept-id entry) (:first-kept-id result))
                "the result names the first kept entry")
          (t/is (pos? (:tokens-before result)))
          (t/is (pos? (:estimated-tokens-after result)))
          (t/is (= {:input 100 :output 10 :cache-read 0 :cache-write 0 :cost 0.001}
                   (usage/entry-usage (:usage result))))
          (t/is (false? (:from-extension end))
                "a kmet-generated summary is not from an extension")
          (t/is (some? compact) "session-compact fired (pi: session_compact)")
          (t/is (= entry (:compaction-entry compact))
                "it carries the appended session entry")
          (t/is (= :threshold (:reason compact)))
          (t/is (false? (:will-retry compact)))
          (t/is (< (.indexOf types :session-compact) (.indexOf types :compaction-end))
                "session-compact precedes compaction-end (pi)"))))))

(t/deftest test-loop-extension-supplied-compaction
  (t/testing "a :session-before-compact handler may supply the compaction
              content — no summarization call, fromExtension true on the
              events and the entry (pi: SessionBeforeCompactResult.compaction)"
    (support/with-compaction-run
      (fn [{:keys [sess events agent]}]
        (let [calls (atom 0)]
          (try
            (support/on-event :session-before-compact
                              (fn [ev]
                                (t/is (some? (:custom-instructions ev))
                                      "the handler sees pi's customInstructions")
                                {:compaction
                                 {:summary "extension summary"
                                  :first-kept-id (get-in ev [:preparation :first-kept-id])
                                  :tokens-before 123
                                  :usage {:prompt_tokens 7 :completion_tokens 2}
                                  :details {:artifact 42}}}))
            (with-redefs [cfg/get-api-key (fn [_] "test-key")
                          llm/send-message (fn [_] (swap! calls inc) (future :done))]
              (binding [*err* (java.io.StringWriter.)]
                (compact/compact-context! agent "focus" :manual)))
            (let [end (support/event-of events :compaction-end)
                  compact (support/event-of events :session-compact)
                  entry (first (filter #(= :compaction (:role %)) @(:entries sess)))]
              (t/is (zero? @calls) "the summarizer was never called")
              (t/is (= "extension summary" (get-in end [:result :summary])))
              (t/is (= 123 (get-in end [:result :tokens-before])))
              (t/is (= {:artifact 42} (get-in end [:result :details])))
              (t/is (true? (:from-extension end)))
              (t/is (true? (:from-extension compact)))
              (t/is (= "extension summary" (:summary entry)))
              (t/is (= 123 (:tokens-before entry)))
              (t/is (= {:artifact 42} (:details entry)))
              (t/is (true? (:from-hook entry))
                    "the entry records the extension origin (pi: fromHook)"))
            (finally (support/clear-listeners!))))))))

(t/deftest test-loop-extension-supplied-compaction-bad-target
  (t/testing "an extension-supplied compaction whose first-kept-id is not in
              the branch fails instead of reporting an empty success"
    (support/with-compaction-run
      (fn [{:keys [sess events agent]}]
        (try
          (support/on-event :session-before-compact
                            (fn [_] {:compaction {:summary "bogus"
                                                  :first-kept-id "no-such-entry"
                                                  :tokens-before 1}}))
          (with-redefs [cfg/get-api-key (fn [_] "test-key")
                        llm/send-message (fn [_] (future :done))]
            (t/is (= :failed (binding [*err* (java.io.StringWriter.)]
                               (compact/compact-context! agent "x" :manual)))))
          (t/is (str/includes? (:error-message (support/event-of events :compaction-end))
                               "no longer in the session"))
          (t/is (nil? (first (filter #(= :compaction (:role %)) @(:entries sess))))
                "nothing was appended")
          (t/is (nil? (support/event-of events :session-compact)) "no success event")
          (finally (support/clear-listeners!)))))))

(t/deftest test-loop-empty-summary-is-appended
  (t/testing "an empty summarization response is appended as-is (pi:
              contentText of an empty response) instead of failing the
              compaction"
    (support/with-compaction-run
      (fn [{:keys [sess events agent]}]
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (future
                          (when-let [on-done (:on-done opts)] (on-done :stop))
                          :done))]
          (binding [*err* (java.io.StringWriter.)]
            (t/is (true? (compact/maybe-compact! agent)) "the compaction ran")))
        (t/is (= "" (:summary (first (filter #(= :compaction (:role %))
                                             @(:entries sess))))))
        (t/is (some? (support/event-of events :session-compact))
              "a successful compaction, not a failure")
        (t/is (nil? (support/event-of events :session-compact-failed)))))))

(t/deftest test-loop-manual-compaction-already-compacted
  (t/testing "a manual /compact on an already-compacted session fails with
              pi's `Already compacted`; the automatic path stays silent"
    (support/with-compaction-run
      (fn [{:keys [events agent]}]
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (future
                          (when-let [on-text (:on-text opts)] (on-text "summary"))
                          (when-let [on-done (:on-done opts)] (on-done :stop))
                          :done))]
          (t/is (true? (binding [*err* (java.io.StringWriter.)]
                         (compact/compact-context! agent nil :manual)))
                "the first manual compaction runs")
          (reset! events [])
          (t/is (= :failed (binding [*err* (java.io.StringWriter.)]
                             (compact/compact-context! agent nil :manual)))
                "the second fails — the newest entry is the compaction")
          (t/is (= "Compaction failed: Already compacted"
                   (:error-message (support/event-of events :compaction-end))))
          (reset! events [])
          (t/is (false? (compact/maybe-compact! agent))
                "the automatic check stays silent (pi: nothing to compact)"))))))

(t/deftest test-loop-branch-summary-budget
  (t/testing "the branch entries are budgeted to the model's context window
              minus reserveTokens; nothing that fits means no summarization
              call at all (pi: prepareBranchEntries — \"No content to
              summarize\")"
    (let [entries [{:id "1" :role :user
                    :content [{:type :text :text (apply str (repeat 100 "a"))}]}
                   {:id "2" :role :user
                    :content [{:type :text :text (apply str (repeat 100 "b"))}]}]
          seen (atom [])
          agent (support/make-agent-state :provider :opencode-go
                                          :model "m"
                                          :branch-summary-reserve-tokens 100)
          stub (fn [opts]
                 (swap! seen conj opts)
                 (future
                   (when-let [on-text (:on-text opts)] (on-text "s"))
                   (when-let [on-done (:on-done opts)] (on-done :stop))
                   :done))]
      (with-redefs [models/get-model (fn [_ _] {:id "m" :context-window 120
                                                :max-tokens 5000})
                    cfg/get-api-key (fn [_] "test-key")
                    llm/send-message stub]
        ;; reserve 100 → budget 20 tokens; each entry estimates 25
        (t/is (= {:summary "No content to summarize"}
                 (compact/generate-branch-summary agent entries))
              "nothing fits the budget")
        (t/is (empty? @seen) "no summarization call"))
      (reset! seen [])
      (with-redefs [models/get-model (fn [_ _] {:id "m" :context-window 130
                                                :max-tokens 5000})
                    cfg/get-api-key (fn [_] "test-key")
                    llm/send-message stub]
        ;; budget 30 tokens → the newest fits (25), the older does not
        (t/is (str/starts-with? (:summary (compact/generate-branch-summary agent entries))
                                "The user explored a different conversation branch"))
        (let [text (get-in (first @seen) [:messages 1 :content 0 :text])]
          (t/is (str/includes? text "bbb") "the newest entry was summarized")
          (t/is (not (str/includes? text "aaa")) "the older entry was dropped"))))))

(t/deftest test-loop-branch-summary-failure-reports-the-cause
  (t/testing "a failed branch summarization returns pi's error shape (pi:
              BranchSummaryResult.error — `Branch summarization failed: …`)"
    (models/load-catalogs! nil)
    (let [agent (support/make-agent-state :provider :opencode-go
                                          :model "deepseek-v4-flash")]
      (swap! (:cfg agent) assoc :max-retries 0)
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (when-let [on-error (:on-error opts)]
                          (on-error "rate limit exceeded"))
                        :done))]
        (t/is (= {:error "Branch summarization failed: rate limit exceeded"}
                 (compact/generate-branch-summary
                  agent [{:role :user :content [{:type :text :text "x"}]}]))))
      (reset! (:signal agent) true)
      (let [sent? (atom false)]
        ;; Deterministic: no ambient auth lookup, and an already-aborted call
        ;; must not reach the provider at all (a send-message call would race
        ;; its own async no-key error against the abort).
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message (fn [_] (reset! sent? true) (future :done))]
          (t/is (= {:aborted true}
                   (compact/generate-branch-summary
                    agent [{:role :user :content [{:type :text :text "x"}]}]))
                "an aborted call reports :aborted (pi)")
          (t/is (not @sent?)
                "an already-aborted call never reaches the provider")))))
  (t/testing "an empty response yields the preamble alone (pi prepends it to
              contentText)"
    (models/load-catalogs! nil)
    (let [agent (support/make-agent-state :provider :opencode-go
                                          :model "deepseek-v4-flash")]
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (when-let [on-done (:on-done opts)] (on-done :stop))
                        :done))]
        (t/is (str/starts-with? (:summary (compact/generate-branch-summary
                                           agent [{:role :user
                                                   :content [{:type :text :text "x"}]}]))
                                "The user explored a different conversation branch"))))))

(t/deftest test-loop-compaction-keeps-tool-call-pairing-in-ui
  "A live compaction whose kept tail includes an assistant tool-call + its
   result must emit a :context-replaced message vector that rebuilds with
   the tool's name/args and result. Regression: the rebuilt tool box lost
   its title and collapsed/expanded toggle."
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        events (atom [])
        agent (support/make-agent-state
               :session sess
               :on-event (fn [e] (swap! events conj e))
               :compact-token-threshold 10
               :keep-recent-tokens 40)
        ch (ui/make-chat-history :tool-display-mode :collapsed
                                 :tool-fn (partial tools-registry/get-tool
                                                   (support/tool-table)))

        render (fn [mode]
                 (ui/chat-history-set-tool-display-mode! ch mode)
                 (mapv tu/strip-ansi (protocols/render ch 80)))]
    (try
      ;; Seed enough context to cross the compact threshold.
      (dotimes [i 10]
        (session/append-entry sess
                              {:role :user
                               :content [{:type :text :text
                                          (str "This is message body number " i
                                               " with plenty of words so the estimated token count "
                                               "easily exceeds the small test threshold.")}]}))
      ;; The kept tail: an assistant tool call and its matching result.
      (session/append-entry sess
                            {:role :assistant
                             :content [{:type :text :text "running read"}]
                             :usage {:prompt_tokens 1000 :completion_tokens 50}
                             :tool-calls [{:id "tc1" :name "read"
                                           :arguments {:path "src/main.clj"}}]})
      (session/append-entry sess
                            {:role :tool
                             :content [{:type :tool_result :tool_use_id "tc1" :content "file body"}]
                             :tool-name "read" :is-error false})
      (t/is (true? (binding [*err* (java.io.StringWriter.)]
                     (support/with-summarization-stub #(compact/maybe-compact! agent))))
            "token threshold triggers compaction")
      (let [replaced (first (filter #(= :context-replaced (:type %)) @events))]
        (t/is (some? replaced) ":context-replaced emitted after compaction")
        (ui/chat-history-rebuild! ch (:messages replaced))
        (let [collapsed (render :collapsed)]
          (t/is (some #(re-find #"\[compaction\]" %) collapsed)
                "compaction summary renders")
          (t/is (some #(re-find #"Compacted from" %) collapsed)
                "collapsed summary line shows token count")
          (t/is (some #(re-find #"read src/main.clj" %) collapsed)
                "collapsed tool box keeps the call-line title"))
        (let [expanded (render :expanded)]
          (t/is (some #(re-find #"summary of the old conversation" %) expanded)
                "expanded summary box shows the summary text")
          (t/is (some #(re-find #"read src/main.clj" %) expanded)
                "expanded tool box keeps the call-line title")
          (t/is (some #(re-find #"file body" %) expanded)
                "expanded tool box shows the full result"))
        (let [quiet (render :quiet)]
          (t/is (some #(re-find #"read src/main.clj" %) quiet)
                "quiet tool box keeps the title")))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-window-based-compaction
  ;; pi: the default trigger is token-based against the model's context
  ;; window — contextTokens > contextWindow - reserveTokens, where
  ;; contextTokens is the measured usage of the latest assistant response.
  ;; Regression: the old entry-count default (400) compacted sessions at
  ;; ~13% context usage — the count trigger is removed entirely.
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})]
    (try
      (t/testing "small measured usage vs window → no compaction, no count trigger"
        (let [sess (session/create-session (str dir))
              agent (support/make-agent-state
                     :session sess
                     :context-window 10000
                     :compact-reserve-tokens 1000
                     :keep-recent-tokens 40)]
          (dotimes [i 50]
            (session/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
          (session/append-entry sess {:role :assistant :content [{:type :text :text "ok"}]
                                      :usage {:prompt_tokens 2000 :completion_tokens 50
                                              :prompt_tokens_details {:cached_tokens 500}}})
          (t/is (false? (compact/maybe-compact! agent))
                "measured 2050 tokens (1500 input + 50 output + 500 cached) vs window-reserve 9000 — no compaction")))
      (t/testing "measured usage within reserve of the window → compaction"
        (let [sess (session/create-session (str dir))
              agent (support/make-agent-state
                     :session sess
                     :context-window 10000
                     :compact-reserve-tokens 1000
                     :keep-recent-tokens 40)]
          (dotimes [i 50]
            (session/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
          (session/append-entry sess {:role :assistant :content [{:type :text :text "ok"}]
                                      :usage {:prompt_tokens 20000 :completion_tokens 50
                                              :prompt_tokens_details {:cached_tokens 500}}})
          (t/is (true? (binding [*err* (java.io.StringWriter.)]
                         (support/with-summarization-stub #(compact/maybe-compact! agent))))
                "measured 20050 tokens >= window-reserve 9000 → compaction")))
      (t/testing "no window and no explicit thresholds → never compacts"
        (let [sess (session/create-session (str dir))
              agent (support/make-agent-state
                     :session sess
                     :keep-recent-tokens 40)]
          (dotimes [i 50]
            (session/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
          (t/is (false? (compact/maybe-compact! agent))
                "no count trigger by default (pi: no entry-count compaction)")))
      (t/testing "degenerate windows never trigger the window check"
        (let [sess (session/create-session (str dir))
              agent (support/make-agent-state
                     :session sess
                     :context-window 0
                     :keep-recent-tokens 40)]
          (dotimes [i 50]
            (session/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
          (session/append-entry sess {:role :assistant :content [{:type :text :text "ok"}]
                                      :usage {:prompt_tokens 20000 :completion_tokens 50}})
          (t/is (false? (compact/maybe-compact! agent))
                "window 0 → no window check (pi: contextWindow <= 0 → undefined)")))
      (t/testing "stale kept-tail usage does not re-trigger right after a compaction"
        (let [sess (session/create-session (str dir))
              agent (support/make-agent-state
                     :session sess
                     :context-window 10000
                     :compact-reserve-tokens 1000
                     :keep-recent-tokens 40)]
          (dotimes [i 50]
            (session/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
          (session/append-entry sess {:role :assistant :content [{:type :text :text "ok"}]
                                      :usage {:prompt_tokens 20000 :completion_tokens 50
                                              :prompt_tokens_details {:cached_tokens 500}}})
          (t/is (true? (binding [*err* (java.io.StringWriter.)]
                         (support/with-summarization-stub #(compact/maybe-compact! agent))))
                "measured usage near the window compacts once")
          (t/is (false? (compact/maybe-compact! agent))
                "kept-tail assistant usage predates the compaction in the branch — no re-trigger until the next response (pi)")))
      (finally (fs/delete-tree dir)))))

(t/deftest test-loop-compaction-not-retriggered
  ;; Regression: compaction is append-only, so the full branch never shrinks —
  ;; maybe-compact! must measure the context (compaction + kept tail), not the
  ;; file. Otherwise the token threshold fires every turn after the first
  ;; compaction.
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})]
    (try
      (let [sess (session/create-session (str dir))
            agent (support/make-agent-state
                   :session sess
                   :compact-token-threshold 100
                   :keep-recent-tokens 40)]
        (dotimes [i 10]
          (let [m {:role :user :content [{:type :text :text
                                          (str "This is message body number " i
                                               " with plenty of words so the estimated token count "
                                               "easily exceeds the small test threshold.")}]}]
            (swap! (:messages agent) conj m)
            (session/append-entry sess m)))
        (t/is (true? (binding [*err* (java.io.StringWriter.)]
                       (support/with-summarization-stub #(compact/maybe-compact! agent))))
              "token threshold triggers the first compaction")
        (t/is (false? (compact/maybe-compact! agent))
              "context tokens dropped below the token threshold — no re-compaction"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-loop-compact-no-op-reports-false
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (support/make-agent-state :session sess)]
    (try
      (doseq [i (range 3)]
        (let [m {:role :user :content [{:type :text :text (str "msg " i)}]}]
          (swap! (:messages agent) conj m)
          (session/append-entry sess m)))
      (t/is (false? (compact/maybe-compact! agent))
            "below threshold → no compaction")
      (t/is (= 3 (count @(:entries sess))))
      (t/is (= 3 (count @(:messages agent))) "context untouched when no compaction")
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-compact-extension-cancel
  (t/testing "a {:cancel true} :session-before-compact handler skips the
              summarization; compaction_start/compaction_end still fire and
              the end carries :aborted true (pi parity); session + context
              untouched, no LLM call"
    (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
          sess (session/create-session (str dir))
          events (atom [])
          llm-called? (atom false)
          agent (support/make-agent-state
                 :session sess
                 :keep-recent-tokens 40
                 :on-event (fn [e] (swap! events conj e)))
          dereg (support/on-event
                 :session-before-compact
                 (fn [ev]
                   (t/is (= :threshold (:reason ev)) "reason rides the event")
                   (t/is (some? (:signal ev)) "the run signal rides the event")
                   {:cancel true}))]
      (try
        (doseq [i (range 6)]
          (let [m {:role :user :content [{:type :text :text
                                          (str "This is message body number " i
                                               " with plenty of words so the estimated token count "
                                               "easily exceeds the small test threshold.")}]}]
            (swap! (:messages agent) conj m)
            (session/append-entry sess m)))
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message (fn [_] (reset! llm-called? true) (promise))]
          (t/is (false? (compact/compact-context! agent nil :threshold))
                "cancel → compaction skipped, reports false"))
        (t/is (false? @llm-called?) "no LLM summarization call on cancel")
        (let [types (mapv :type @events)
              end (first (filter #(= :compaction-end (:type %)) @events))]
          (t/is (< (.indexOf types :compaction-start)
                   (.indexOf types :session-before-compact))
                "compaction-start fires before session-before-compact (pi)")
          (t/is (< (.indexOf types :session-before-compact)
                   (.indexOf types :compaction-end))
                "session-before-compact fires before compaction-end (pi)")
          (t/is (:aborted end) "compaction-end carries :aborted true on extension cancel")
          (t/is (nil? (:result end)) "compaction-end carries no result"))
        (t/is (= 6 (count @(:entries sess))) "session untouched")
        (t/is (= 6 (count @(:messages agent))) "in-memory context untouched")
        (finally
          (dereg)
          (fs/delete-tree dir))))))

(t/deftest test-loop-prepare-next-turn-context-replaces-session
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (support/make-agent-state :session sess)]
    (try
      (reset! (:prepare-next-turn agent)
              (fn [_] {:context [{:role :user :content [{:type :text :text "replacement"}]}]}))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-tool-then-text (atom 0))
                    state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})]
        @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
      (t/is (= [{:role :user :content [{:type :text :text "replacement"}]}]
               (state/get-context agent))
            "prepare-next-turn :context replaces the conversation")
      (t/is (= 1 (count @(:entries sess)))
            "session rebuilt to match the replaced context")
      (t/is (= "replacement" (get-in (first @(:entries sess)) [:content 0 :text]))
            "session entry mirrors the replacement message")
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-context-replaced-event
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:prepare-next-turn agent)
            (fn [_] {:context [{:role :user :content [{:type :text :text "replacement"}]}]}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [evs (filter #(= :context-replaced (:type %)) @events)]
      (t/is (seq evs) ":context-replaced emitted on context replacement")
      (t/is (= "replacement" (get-in (last evs) [:messages 0 :content 0 :text]))
            "event carries the new conversation"))))

(t/deftest test-loop-compaction-cancelled
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        events (atom [])
        started (promise)
        agent (support/make-agent-state
               :session sess
               :keep-recent-tokens 40
               :on-event (fn [e]
                           (swap! events conj e)
                           (when (= :compaction-start (:type e))
                             (deliver started true))))]
    (try
      (doseq [i (range 6)]
        (let [m {:role :user :content [{:type :text :text
                                        (str "This is message body number " i
                                             " with plenty of words so the estimated token count "
                                             "easily exceeds the small test threshold.")}]}]
          (swap! (:messages agent) conj m)
          (session/append-entry sess m)))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    ;; A summarization stream that never finishes on its own —
                    ;; only the cancel signal (via summarize!'s watch) ends it.
                    llm/send-message (fn [_] (promise))]
        (let [fut (future (compact/compact-context! agent nil :threshold))]
          (t/is (true? (deref started 2000 false)) "summarization started")
          (loop/cancel-turn agent)
          (t/is (= :aborted (deref fut 2000 ::timeout))
                "compact-context! reports :aborted on cancel")))
      (t/is (some #(and (= :compaction-start (:type %)) (= :threshold (:reason %))) @events)
            "compaction-start emitted with the threshold reason")
      (t/is (some #(and (= :compaction-end (:type %)) (:aborted %)) @events)
            "compaction-end carries :aborted")
      (t/is (= 6 (count @(:entries sess))) "session untouched by aborted compaction")
      (t/is (= 6 (count @(:messages agent))) "in-memory context untouched")
      (finally (fs/delete-tree dir)))))

(t/deftest test-loop-abort-compaction-keeps-run
  ;; Escape during an auto-compaction aborts only the summarization: the run
  ;; continues on the pre-compaction context (pi: abortCompaction touches
  ;; only the compaction controllers; only session.abort() aborts the run).
  ;; Regression: the shared run signal killed the turn and latched the UI on
  ;; running-turn?, so escape looked like it could not cancel the compaction.
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        events (atom [])
        started (promise)
        calls (atom 0)
        agent (support/make-agent-state
               :session sess
               :compact-token-threshold 10
               :keep-recent-tokens 40
               :on-event (fn [e]
                           (swap! events conj e)
                           (when (= :compaction-start (:type e))
                             (deliver started true))))]
    (try
      (doseq [i (range 6)]
        (let [m {:role :user :content [{:type :text :text
                                        (str "This is message body number " i
                                             " with plenty of words so the estimated token count "
                                             "easily exceeds the small test threshold.")}]}]
          (swap! (:messages agent) conj m)
          (session/append-entry sess m)))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    ;; The summarization stream never finishes on its own —
                    ;; only the compaction signal (via summarize!'s watch)
                    ;; ends it; the main call answers normally. :tools tells
                    ;; the two apart: the main call sends tools, the
                    ;; summarization call does not. Do NOT key on the call
                    ;; count — an escape that lands before summarize! starts
                    ;; skips the summarization call entirely, and the main
                    ;; call would then get the never-finishing stub.
                    llm/send-message
                    (fn [opts]
                      (if (:tools opts)
                        (do (swap! calls inc)
                            (future
                              (when-let [on-text (:on-text opts)]
                                (on-text "continued after abort"))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop))
                              :done))
                        (promise)))]
        (let [run (loop/run-agent-turn
                   agent
                   {:message "go"
                    :on-error (fn [e] (swap! events conj {:type :on-error
                                                          :message (str e)}))})]
          (t/is (true? (deref started 2000 false)) "threshold compaction started")
          (compact/abort-compaction! agent)
          (t/is (not= ::timeout (deref run 5000 ::timeout))
                "the run continues after the compaction abort")))
      (t/is (= 1 @calls) "the aborted compaction still let the main LLM call run")
      (t/is (false? @(:signal agent)) "the run's cancel signal never fired")
      (t/is (false? @(:compacting? agent)) "the compaction flag is cleared")
      (let [end (first (filter #(= :compaction-end (:type %)) @events))]
        (t/is (:aborted end) "compaction-end reports the abort"))
      (t/is (= 1 (count (filter #(= :compaction-start (:type %)) @events)))
            "the aborted compaction is not retried by the post-run check (D)")
      (t/is (not-any? #(contains? #{:error :on-error} (:type %)) @events)
            "no error event — the abort is not a failure")
      (t/is (some #(and (= :assistant (:role %))
                        (= "continued after abort"
                           (get-in % [:content 0 :text])))
                  @(:messages agent))
            "the main LLM response lands after the aborted compaction")
      (t/is (not-any? #(= :compaction (:role %)) @(:entries sess))
            "the aborted compaction appended no compaction entry")
      (t/is (true? @(:compaction-signal agent))
            "the abort signal stays set after the compaction ends")
      ;; The next compaction is a fresh operation (pi: a new AbortController)
      ;; — the stale abort must not cancel it.
      (support/with-summarization-stub
        (fn []
          (t/is (true? (binding [*err* (java.io.StringWriter.)]
                         (compact/compact-context! agent nil :threshold)))
                "the next compaction starts with a fresh abort state")))
      (t/is (false? @(:compaction-signal agent)) "the stale abort signal is cleared")
      (finally (fs/delete-tree dir)))))

(t/deftest test-loop-manual-compaction-clears-stale-cancel-signal
  ;; Regression (/compact after Escape): Escape leaves the run's cancel
  ;; signal set until the next run starts, which made every manual compaction
  ;; abort immediately with "compaction cancelled". A manual compaction is a
  ;; fresh operation (pi: session.compact aborts the current operation, then
  ;; creates a new AbortController), so it clears the stale signal — the auto
  ;; paths keep it, an Escape at run start must still abort them.
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (support/make-agent-state :session sess :keep-recent-tokens 40)]
    (try
      (doseq [i (range 6)]
        (let [m {:role :user :content [{:type :text :text
                                        (str "This is message body number " i
                                             " with plenty of words so the estimated token count "
                                             "easily exceeds the small test threshold.")}]}]
          (swap! (:messages agent) conj m)
          (session/append-entry sess m)))
      ;; ESC on a running turn: the run settles, its cancel signal stays set
      ;; (only the next run resets it) — the exact state /compact hits.
      (loop/cancel-turn agent)
      (t/is (true? @(:signal agent)) "precondition: Escape leaves the cancel signal set")
      (support/with-summarization-stub
        (fn []
          (binding [*err* (java.io.StringWriter.)]
            (t/is (= :aborted (compact/compact-context! agent nil :threshold))
                  "the auto path still aborts on the cancel signal")
            (t/is (true? @(:signal agent)) "the auto path keeps the signal")
            (t/is (true? (compact/compact-context! agent nil :manual))
                  "a manual compaction starts with a fresh abort state")
            (t/is (false? @(:signal agent)) "the stale cancel signal is cleared"))))
      (t/is (some #(= :compaction (:role %)) @(:entries sess))
            "the manual compaction appended its compaction entry")
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-compaction-refuses-when-active
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        started (promise)
        agent (support/make-agent-state :session sess
                                        :keep-recent-tokens 40)]
    (try
      (doseq [i (range 6)]
        (let [m {:role :user :content [{:type :text :text
                                        (str "This is message body number " i
                                             " with plenty of words so the estimated token count "
                                             "easily exceeds the small test threshold.")}]}]
          (swap! (:messages agent) conj m)
          (session/append-entry sess m)))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (fn [_]
                                       (deliver started true)
                                       (promise))]
        (let [fut (future (compact/compact-context! agent))]
          ;; The second call must see the first compaction in flight. Waiting
          ;; on its summarization entry avoids a polling sleep.
          (t/is (true? (deref started 2000 false)) "first compaction started")
          (t/is (false? (compact/compact-context! agent))
                "second compaction refused while one is in flight")
          (loop/cancel-turn agent)
          (t/is (= :aborted (deref fut 2000 ::timeout)))))
      (finally (fs/delete-tree dir)))))

(t/deftest test-loop-compaction-failure-reports-failed
  ;; pi: a summarization failure surfaces via compaction_end errorMessage and
  ;; session_compact_failed — kmet returns :failed (distinct from false =
  ;; nothing to compact) so the manual /compact path can show the real error
  ;; instead of "Nothing to compact".
  (support/with-compaction-run
    (fn [{:keys [sess events agent]}]
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    ;; summarization call errors out — no summary delivered
                    llm/send-message (fn [opts]
                                       (future
                                         (when-let [on-error (:on-error opts)]
                                           (on-error "upstream failure"))
                                         :done))]
        (let [result (binding [*err* (java.io.StringWriter.)]
                       (compact/compact-context! agent nil :threshold))]
          (t/is (= :failed result) "summarization failure reports :failed")
          (let [end (support/event-of events :compaction-end)
                failed (support/event-of events :session-compact-failed)]
            (t/is (some? end) "compaction-end emitted")
            (t/is (nil? (:result end)) "no compaction result")
            (t/is (some? (:error-message end)) "compaction-end carries the error")
            (t/is (= :threshold (:reason end)) "reason preserved")
            (t/is (some? failed) "session-compact-failed emitted (pi: session_compact_failed)")
            (t/is (= :threshold (:reason failed)) "failure event carries the reason")
            (t/is (some? (:error-message failed)) "failure event carries the error"))))
      (t/is (= 6 (count @(:entries sess))) "session untouched by failed compaction"))))
