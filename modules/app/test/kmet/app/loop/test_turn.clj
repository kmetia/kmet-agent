(ns kmet.app.loop.test-turn
  "Turn execution: run-agent-turn without a key, with a scripted provider, continue,
   successive turns, signal cleanup, event routing, start hooks, and images."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.ai.llm :as llm]
            [kmet.ai.auth :as auth]
            [kmet.ai.aws-sigv4 :as aws-sigv4]
            [kmet.ai.google-adc :as google-adc]
            [kmet.app.tools.util :as tool-util]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.session.core :as session]
            [kmet.app.loop.state :as state]
            [kmet.app.loop :as loop]
            [kmet.app.config :as cfg]
            [kmet.app.loop.test-support :as support]
            [kmet.test-utils :refer [slash]]))

(t/use-fixtures :each support/with-host)

(t/deftest run-binds-the-session-cwd-for-tools
  (t/testing "an agent run binds the tool cwd from the session (pi: the
              runtime cwd the tool definitions were created with) — tools of a
              session resumed/imported from another project resolve their
              relative paths there, not in the process cwd"
    (let [dir (-> (fs/create-dirs (fs/path "target" (str "test-loop-cwd-" (System/currentTimeMillis))))
                  fs/absolutize str)
          sessions (str dir "-sessions")
          sess (session/create-session sessions {:cwd dir})
          seen (atom nil)
          call-count (atom 0)
          agent (support/make-agent-state :session sess)]
      (try
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (future
                          (if (= 1 (swap! call-count inc))
                            (do (when-let [on-tc (:on-tool-call opts)]
                                  (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :tool-calls)))
                            (do (when-let [on-text (:on-text opts)]
                                  (on-text "done"))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :stop))))
                          :done))
                      state/execute-tool-for
                      (fn [_ _ _ _]
                        (reset! seen (tool-util/cwd))
                        {:content "ok" :is-error false})]
          @(loop/run-agent-turn agent {:message "run"
                                       :on-done (fn [_])
                                       :on-error (fn [_])}))
        (t/is (= (slash dir) (slash @seen)) "the tool call ran with the session's cwd bound")
        (t/is (= (System/getProperty "user.dir") (tool-util/cwd))
              "the binding does not leak past the run")
        (finally (fs/delete-tree dir) (fs/delete-tree sessions))))))

;; ─── run-agent-turn with no API key ──────────────────────────────────────

(t/deftest test-loop-run-agent-turn-no-api-key
  (support/with-no-credentials
    (fn []
      (let [errors (atom [])
            events (atom [])
            agent (support/make-agent-state {:on-event #(swap! events conj %)})
            done (promise)]
        (loop/run-agent-turn agent
                             {:message "hello"
                              :on-error (fn [e] (swap! errors conj e) (deliver done true))})
        (t/is (true? (deref done 2000 :timeout)) "error callback fires")
        (t/is (pos? (count @errors)))
        (t/is (.contains (first @errors) "No API key"))
        (t/is (= :error (:outcome (last (filter #(= :agent-settled (:type %)) @events))))
              "the settle reports :error")
        (t/is (some #(and (= :error (:type %))
                          (str/includes? (str (:message %)) "No API key"))
                    @events)
              "the event stream carries the refusal: a front end that only reads
               events (print, the TUI) shows it instead of a silent settle")
        (t/is (= :error @(:last-outcome agent))
              "the outcome is recorded: a state reader (get-state, a client's
               wait-idle) sees the failure, not the initial :completed")))))

(t/deftest test-loop-run-agent-turn-ambient-auth
  ;; google-vertex (ADC) and amazon-bedrock (AWS credentials) resolve no
  ;; api-key — the run guard must accept them as configured (the request
  ;; path resolves ambient auth itself), i.e. send-message is called
  ;; instead of the "No API key" refusal.
  (t/testing "amazon-bedrock with ambient AWS credentials"
    (let [agent (support/make-agent-state)
          sent (atom nil)
          errors (atom [])]
      (with-redefs [aws-sigv4/getenv (fn [k] (case k "AWS_ACCESS_KEY_ID" "AKID"
                                                   "AWS_SECRET_ACCESS_KEY" "SECRET" nil))
                    auth/auth-atom (atom {})
                    llm/send-message
                    (fn [opts]
                      (reset! sent opts)
                      (future
                        (when-let [on-text (:on-text opts)] (on-text "ok"))
                        (when-let [on-done (:on-done opts)] (on-done :stop))
                        :done))]
        ;; deref inside the redef — the run's future reads the env at runtime
        @(loop/run-agent-turn (assoc agent :provider (atom :amazon-bedrock))
                              {:message "hello"
                               :on-error (fn [e] (swap! errors conj e))}))
      (t/is (= [] @errors))
      (t/is (some? @sent) "send-message ran — the ambient guard passed")
      (t/is (= #{:on-context :on-payload :on-headers :on-response}
               (set (keys (:hooks @sent))))
            "the attempt hands send-message the provider-event bridges (pi: StreamOptions)")))
  (t/testing "google-vertex with ADC configured"
    (let [agent (support/make-agent-state)
          sent (atom nil)
          errors (atom [])]
      (with-redefs [google-adc/configured? (constantly true)
                    auth/getenv (fn [k] (case k "GOOGLE_CLOUD_PROJECT" "p"
                                              "GCLOUD_PROJECT" nil
                                              "GOOGLE_CLOUD_LOCATION" "loc" nil))
                    llm/send-message
                    (fn [opts]
                      (reset! sent opts)
                      (future
                        (when-let [on-text (:on-text opts)] (on-text "ok"))
                        (when-let [on-done (:on-done opts)] (on-done :stop))
                        :done))]
        @(loop/run-agent-turn (assoc agent :provider (atom :google-vertex))
                              {:message "hello"
                               :on-error (fn [e] (swap! errors conj e))}))
      (t/is (= [] @errors))
      (t/is (some? @sent) "send-message ran — the ambient guard passed")))
  (t/testing "unconfigured provider still refuses with No API key"
    (let [agent (support/make-agent-state)
          errors (atom [])
          done (promise)]
      (with-redefs [auth/getenv (fn [_] nil)
                    google-adc/configured? (constantly false)
                    aws-sigv4/getenv (fn [_] nil)]
        (loop/run-agent-turn (assoc agent :provider (atom :deepseek))
                             {:message "hello"
                              :on-error (fn [e] (swap! errors conj e) (deliver done true))}))
      (t/is (true? (deref done 2000 :timeout)))
      (t/is (.contains (first @errors) "No API key")))))

;; ─── run-agent-turn with valid state ─────────────────────────────────────

(t/deftest test-loop-run-agent-turn-structure
  (let [agent (support/make-agent-state)
        called (atom false)
        fut (loop/run-agent-turn agent
                                 {:message "hello"
                                  :on-text (fn [_] (reset! called true))
                                  :on-done (fn [_] (reset! called true))
                                  :on-error (fn [_] (reset! called true))})]
    (t/is (future? fut))
    @fut
    (t/is @called)))

;; ─── Continue (no initial message) ────────────────────────────────────────

(t/deftest test-loop-continue-without-message
  ;; /continue runs the agent on the existing context without adding a new
  ;; user message — after a network error the last entry is an unanswered
  ;; user message (or a dangling tool result), and the model picks up where
  ;; the interrupted turn left off.
  (let [sent (atom nil)
        agent (support/make-agent-state)
        ;; Simulate an interrupted turn: user message with no response yet
        _ (swap! (:messages agent) conj
                 {:role :user :content [{:type :text :text "fix the bug"}]})]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (reset! sent opts)
                    (future
                      (when-let [on-text (:on-text opts)]
                        (on-text "will do"))
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop))
                      :done))]
      ;; deref inside with-redefs keeps the rebinding until the run completes
      @(loop/run-agent-turn agent
                            {:on-done (fn [_])
                             :on-error (fn [_])}))
    (t/is (= 1 (count (filter #(= :user (:role %)) (state/get-context agent))))
          "no new user message is added")
    (let [llm-msgs (:messages @sent)
          users (filter #(= :user (:role %)) llm-msgs)]
      (t/is (= 1 (count users)) "the LLM sees exactly one user message")
      (t/is (= "fix the bug" (get-in (first users) [:content 0 :text]))
            "the interrupted user message reaches the LLM")
      (t/is (= :system (-> llm-msgs first :role)) "system prompt prepended"))
    (t/is (= :assistant (-> (state/get-context agent) last :role))
          "the continued turn lands as the final assistant message")
    (t/is (= :idle @(:status agent)))))

(t/deftest test-loop-continue-after-error
  ;; End-to-end /continue flow: a run dies with a network error (the user
  ;; message is persisted, the assistant never responded), then a no-message
  ;; run picks up where it left off — exactly one user message in context,
  ;; and it reaches the LLM.
  (let [calls (atom 0)
        sent (atom nil)
        ;; :max-retries 0 — the network failure is terminal immediately,
        ;; exactly the state /continue is for (retries exhausted)
        agent (support/make-agent-state :max-retries 0)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (when-let [on-error (:on-error opts)]
                          (on-error "connection lost"))
                        (do (reset! sent opts)
                            (when-let [on-text (:on-text opts)]
                              (on-text "fixed"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))]
      ;; Turn 1: network error — retries exhausted, run dies
      @(loop/run-agent-turn agent
                            {:message "fix the bug"
                             :on-error (fn [_])})
      (t/is (= :idle @(:status agent))
            "run settles idle after the terminal error — /continue and /compact stay usable")
      (t/is (= 1 (count (filter #(= :user (:role %)) (state/get-context agent))))
            "the user message stays in context after the error")
      ;; Turn 2: /continue — no new user message, the model picks up
      @(loop/run-agent-turn agent
                            {:on-done (fn [_])
                             :on-error (fn [_])}))
    (t/is (= 2 @calls) "second run makes exactly one LLM call")
    (let [users (filter #(= :user (:role %)) (:messages @sent))]
      (t/is (= 1 (count users)) "continue sends the context with one user message")
      (t/is (= "fix the bug" (get-in (first users) [:content 0 :text]))))
    (t/is (= "fixed" (get-in (last (state/get-context agent)) [:content 0 :text]))
          "the continued run completes the response")
    (t/is (= :idle @(:status agent)) "run settles idle after continue")))

;; ─── Multiple turns ──────────────────────────────────────────────────────

(t/deftest test-loop-messages-accumulate
  (let [agent (support/make-agent-state)]
    (t/is (empty? @(:messages agent)))
    (swap! (:messages agent) conj {:role :user :content [{:type :text :text "hi"}]})
    (t/is (= 1 (count @(:messages agent))))))

;; ─── Regression: run-agent-turn signals cleanup ─────────────────────────

(t/deftest test-loop-run-agent-turn-resets-signal
  (support/with-no-credentials
    (fn []
      (let [agent (support/make-agent-state)]
        (reset! (:signal agent) true)
        (reset! (:status agent) :thinking)
        ;; run with no API key — should exercise the error path
        (let [errors (atom [])
              done (promise)]
          (loop/run-agent-turn agent
                               {:message "test"
                                :on-error (fn [e]
                                            (swap! errors conj e)
                                            (deliver done true))})
          (t/is (true? (deref done 2000 false)) "error callback fires")
          (t/is (false? @(:signal agent)) "Signal reset at start of turn")
          (t/is (pos? (count @errors)) "Error callback called")
          (t/is (.contains (first @errors) "No API key")
                "Error message is about API key"))))))

(t/deftest test-loop-signal-clean-after-turn
  (let [agent (support/make-agent-state)
        done (promise)]
    (loop/run-agent-turn agent
                         {:message "hi"
                          :on-text (fn [_])
                          :on-done (fn [_] (deliver done true))
                          :on-error (fn [_] (deliver done true))})
    (deref done 2000 :timeout)
    (t/is (false? @(:signal agent)) "Signal should be false after turn ends")))

(t/deftest test-loop-status-after-error
  (let [agent (support/make-agent-state)
        done (promise)]
    (reset! (:status agent) :idle)
    (loop/run-agent-turn agent
                         {:message "hi"
                          :on-error (fn [_] (deliver done true))})
    (t/is (true? (deref done 2000 false)) "error callback fires")
    (t/is (= :idle @(:status agent)) "Status should be idle after error turn")
    (t/is (false? @(:signal agent)) "Signal should be false after error turn")))

(t/deftest test-loop-multiple-cancel
  (let [agent (support/make-agent-state)]
    (loop/cancel-turn agent)
    (t/is (true? @(:signal agent)) "cancel-turn sets signal to true")
    ;; calling cancel-turn again is idempotent
    (loop/cancel-turn agent)
    (t/is (true? @(:signal agent)) "Signal remains true after second cancel")))

;; ─── Event routing ───────────────────────────────────────────────────────

(t/deftest test-loop-events-routed-to-extension-system
  (let [received (atom [])
        dereg (support/on-event :status (fn [e] (swap! received conj e)))
        agent (support/make-agent-state :on-event (fn [_]))]
    (loop/cancel-turn agent)
    (dereg)
    ;; Events reach extension listeners even though the UI callback ignores them
    (t/is (some #(= :idle (:status %)) @received)
          "Extension listener should receive the :status event from cancel-turn")))

(t/deftest test-loop-lifecycle-events
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-text (:on-text opts)]
                        (on-text "hello"))
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop))
                      :done))]
      ;; deref inside with-redefs keeps the rebinding until the turn completes
      @(loop/run-agent-turn agent
                            {:message "hi"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (let [types (mapv :type @events)]
      (doseq [t [:agent-start :turn-start :message-start
                 :message-update :message-end :turn-end
                 :agent-end :agent-settled :status]]
        (t/is (some #{t} types) (str "expected event " t " to be emitted")))
      (let [ae-idx (first (keep-indexed #(when (= :agent-end (:type %2)) %1) @events))
            as-idx (first (keep-indexed #(when (= :agent-settled (:type %2)) %1) @events))]
        (t/is (some? ae-idx) ":agent-end emitted")
        (t/is (some? as-idx) ":agent-settled emitted")
        (t/is (< ae-idx as-idx) ":agent-settled follows :agent-end"))
      (t/is (some #(= :idle (:status %))
                  (filter #(= :status (:type %)) @events))
            "final status should be :idle")
      (t/is (= :idle @(:status agent)) "Agent status should be idle after turn")
      ;; message-update carries the streaming delta
      (let [mu (first (filter #(= :message-update (:type %)) @events))]
        (t/is (= :text (:type (:delta mu))) "delta type should be :text")
        (t/is (= "hello" (:content (:delta mu))) "delta content should carry the text")))
    (t/is (false? @(:signal agent)) "Signal should be false after turn")))

(t/deftest test-loop-tool-execution-events
  (let [events (atom [])
        call-count (atom 0)
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  ;; First call streams a tool call, second call a plain response
                  (fn [opts]
                    (future
                      (if (= 1 (swap! call-count inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "done"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  state/execute-tool-for
                  ;; P4 contract: opts map {:on-update .. :signal .. :ctx ..}
                  ;; (pi: executeTool passes signal/onUpdate/ctx unconditionally).
                  (fn [_ _ _ opts]
                    (when-let [on-update (:on-update opts)]
                      (on-update {:content "partial output"}))
                    {:content "ok" :is-error false})]
      ;; deref inside with-redefs keeps the rebinding until the turn completes
      @(loop/run-agent-turn agent
                            {:message "run tool"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (let [types (mapv :type @events)]
      (doseq [t [:tool-execution-start :tool-execution-update
                 :tool-execution-end :turn-end]]
        (t/is (some #{t} types) (str "expected event " t " to be emitted")))
      ;; :tool-execution-update carries the tool's live output (pi: updates
      ;; flow only from on-update, no periodic pings)
      (let [upd (first (filter #(= :tool-execution-update (:type %)) @events))]
        (t/is (= "partial output" (:content upd))
              "update carries the partial content from on-update"))
      (let [start (first (filter #(= :tool-execution-start (:type %)) @events))
            end (first (filter #(= :tool-execution-end (:type %)) @events))]
        (t/is (= "tc1" (:tool-call-id start)) "start carries the tool call id")
        (t/is (= "bash" (:tool-name start)) "start carries the tool name")
        (t/is (= "tc1" (:tool-call-id end)) "end carries the tool id")
        (t/is (= "ok" (:content (:result end))) "end carries the result")
        (t/is (false? (:is-error end)) "end carries the error flag"))
      ;; agent-end includes the new messages from this loop
      (let [ae (first (filter #(= :agent-end (:type %)) @events))]
        (t/is (some #(and (= :user (:role %)) (= "run tool" (get-in % [:content 0 :text])))
                    (:messages ae))
              "agent-end :messages includes the user message")
        (t/is (= :idle @(:status agent)) "Agent status should be idle after tool turn")))))

(t/deftest ^:slow test-loop-bash-session-env
  (t/testing "bash tool calls run with the run's KMET_* session env (pi: resolveSpawnContext)"
    (let [events (atom [])
          call-count (atom 0)
          dir (str (fs/create-temp-dir {:dir "target" :prefix "loop-bash-env-"}))
          sess (session/create-session dir)
          agent (support/make-agent-state :on-event (fn [e] (swap! events conj e))
                                          :session sess
                                          :provider :deepseek
                                          :model "m-9"
                                          :thinking :off)]
      (try
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [opts]
                        (future
                          (if (= 1 (swap! call-count inc))
                            (do (when-let [on-tc (:on-tool-call opts)]
                                  (on-tc {:id "tc1" :name "bash"
                                          :arguments (str "{\"command\":\"echo id=$KMET_SESSION_ID"
                                                          " m=$KMET_MODEL t=$KMET_REASONING_LEVEL\"}")
                                          :index 0}))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :tool-calls)))
                            (do (when-let [on-text (:on-text opts)]
                                  (on-text "done"))
                                (when-let [on-done (:on-done opts)]
                                  (on-done :stop))))
                          :done))]
          @(loop/run-agent-turn agent
                                {:message "run tool"
                                 :on-done (fn [_])
                                 :on-error (fn [_])}))
        (let [end (first (filter #(= :tool-execution-end (:type %)) @events))
              content (:content (:result end))]
          (t/is (str/includes? content (str "id=" (:id sess))))
          (t/is (str/includes? content "m=m-9"))
          (t/is (str/includes? content "t=off")))
        (finally
          (fs/delete-tree dir))))))

(t/deftest test-loop-malformed-tool-args-degrade-to-map
  ;; Malformed tool-call arguments (raw JSON string that fails to parse) must
  ;; degrade to a map before reaching the tool (pi: parseStreamingJson) — the
  ;; old fallback passed the raw string through, crashing edit's
  ;; normalize-edits with "java.lang.String cannot be cast to
  ;; clojure.lang.Associative".
  (let [captured (atom nil)
        call-count (atom 0)
        agent (support/make-agent-state)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! call-count inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "edit" :arguments "{malformed-json" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "done"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  state/execute-tool-for
                  (fn [_ name args _]
                    (reset! captured {:name name :args args})
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "run tool"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (t/is (= "edit" (:name @captured)) "tool name reaches execute-tool")
    (t/is (map? (:args @captured))
          "malformed args JSON degrades to a map, never a raw string")
    (t/is (empty? (:args @captured)) "degraded args are the empty map")))

;; ─── before-agent-start hooks ─────────────────────────────────────────────

(t/deftest test-loop-before-agent-start-hooks
  (let [events (atom [])
        sent (atom nil)
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))
        ;; one test-owned owner, so the hook cannot outlive the test (P2:
        ;; hooks live on the app tree under their owner, not in an engine)
        dereg (extensions/register-before-agent-start-hook! [:app :test-loop-hooks]
                                                            (fn [_]
                                                              {:system-prompt "EXTRA SYSTEM PROMPT"
                                                               :message {:role :info :label "ext" :content "injected note"}}))]
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (reset! sent opts)
                      (future
                        (when-let [on-done (:on-done opts)]
                          (on-done :stop))
                        :done))]
        ;; deref inside with-redefs keeps the rebinding until the turn completes
        @(loop/run-agent-turn agent
                              {:message "hi"
                               :on-done (fn [_])
                               :on-error (fn [_])}))
      (finally (dereg)))
    (t/is (str/includes? (get-in @sent [:messages 0 :content 0 :text])
                         "EXTRA SYSTEM PROMPT")
          "before-agent-start system prompt override reaches the LLM call")
    (t/is (some #(and (= :message-start (:type %))
                      (= :info (:role (:message %))))
                @events)
          ":message-start is emitted for the injected message")
    (t/is (= 1 (count (filter #(= :info (:role %)) @(:messages agent))))
          "injected :info message stays in context")
    (t/is (= [{:type :text :text "injected note"}]
             (:content (first (filter #(= :info (:role %)) @(:messages agent)))))
          "string content is normalized to text blocks")
    (t/is (not-any? #(= :info (:role %)) (:messages @sent))
          "display-only :info messages are excluded from the LLM context")))

;; ─── User message images ──────────────────────────────────────────────────

(t/deftest test-loop-user-message-images
  (let [sent (atom nil)
        agent (support/make-agent-state)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (reset! sent opts)
                    (future
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent
                            {:message "look at this"
                             :images [{:type :image :data "AA" :mime-type "image/png"}]
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (let [user-msg (first (filter #(= :user (:role %)) @(:messages agent)))]
      (t/is (= [{:type :text :text "look at this"}
                {:type :image :data "AA" :mime-type "image/png"}]
               (:content user-msg))
            "user message carries text + image blocks"))
    (let [llm-user (first (filter #(= :user (:role %)) (:messages @sent)))]
      (t/is (= [{:type :text :text "look at this"}
                {:type :image :data "AA" :mime-type "image/png"}]
               (:content llm-user))
            "image blocks reach the LLM call in kmet message format"))))

;; ─── Block images (pi: images.blockImages / convertToLlmWithBlockImages) ──

(t/deftest test-loop-block-images-strips-wire-images
  (let [agent (support/make-agent-state :block-images true)
        ;; a valid tool batch (assistant call + matching result) — an orphaned
        ;; result would be dropped by drop-incomplete-tool-calls before the
        ;; block-images filter runs
        _ (swap! (:messages agent) conj
                 {:role :assistant
                  :content [{:type :text :text "reading"}]
                  :tool-calls [{:id "t1" :name "read" :arguments {}}]})
        _ (swap! (:messages agent) conj
                 {:role :tool
                  :content [{:type :tool_result
                             :tool_use_id "t1"
                             :content "Read image file [image/png]"}]
                  :tool-name "read"
                  :is-error false
                  :images [{:data "AA" :mime-type "image/png"}]})
        _ (swap! (:messages agent) conj
                 {:role :custom
                  :content [{:type :text :text "note"}
                            {:type :image :data "BB" :mime-type "image/png"}]})
        sent (support/run-turn-capturing-llm agent
                                             {:message "look at this"
                                              :images [{:type :image :data "CC" :mime-type "image/png"}]})
        wire (:messages sent)
        wire-user (first (filter #(= :user (:role %)) wire))
        wire-tool (first (filter #(= :tool (:role %)) wire))
        wire-custom (first (filter #(= :custom (:role %)) wire))]
    (t/is (= [{:type :text :text "look at this"}
              {:type :text :text "Image reading is disabled."}]
             (:content wire-user))
          "user image blocks become the disabled placeholder")
    (t/is (nil? (:images wire-tool)) "tool-result images are dropped from the wire")
    (t/is (= "Read image file [image/png]\nImage reading is disabled."
             (-> wire-tool :content first :content))
          "the tool result text carries the placeholder")
    (t/is (= [{:type :text :text "note"}
              {:type :text :text "Image reading is disabled."}]
             (:content wire-custom))
          "custom message images are stripped too (pi: custom→user then filter)")
    (t/is (some #(seq (:images %)) @(:messages agent))
          "the stored context keeps its images")
    (t/is (= [{:type :text :text "look at this"}
              {:type :image :data "CC" :mime-type "image/png"}]
             (:content (first (filter #(= :user (:role %)) (state/get-context agent)))))
          "session/transcript user content is untouched")))

(t/deftest test-loop-block-images-consecutive-dedupe
  (let [agent (support/make-agent-state :block-images true)
        sent (support/run-turn-capturing-llm agent
                                             {:message "two"
                                              :images [{:type :image :data "AA" :mime-type "image/png"}
                                                       {:type :image :data "BB" :mime-type "image/png"}]})]
    (t/is (= [{:type :text :text "two"}
              {:type :text :text "Image reading is disabled."}]
             (:content (first (filter #(= :user (:role %)) (:messages sent)))))
          "consecutive placeholders collapse to one (pi dedupe)")))

(t/deftest test-loop-block-images-off-passes-images-through
  (let [agent (support/make-agent-state)  ;; block-images defaults false
        sent (support/run-turn-capturing-llm agent
                                             {:message "look"
                                              :images [{:type :image :data "AA" :mime-type "image/png"}]})]
    (t/is (= [{:type :text :text "look"}
              {:type :image :data "AA" :mime-type "image/png"}]
             (:content (first (filter #(= :user (:role %)) (:messages sent)))))
          "images pass through when the setting is off")))

(t/deftest test-loop-set-block-images-live
  (let [agent (support/make-agent-state)]
    (t/is (false? (:block-images @(:cfg agent))))
    (state/set-block-images! agent true)
    (t/is (true? (:block-images @(:cfg agent))))
    (let [sent (support/run-turn-capturing-llm agent
                                               {:message "look"
                                                :images [{:type :image :data "AA" :mime-type "image/png"}]})]
      (t/is (not-any? #(= :image (:type %))
                      (:content (first (filter #(= :user (:role %)) (:messages sent)))))
            "a mid-session toggle applies to the next call (pi reads it per request)"))))
