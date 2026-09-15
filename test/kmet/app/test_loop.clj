(ns kmet.app.test-loop
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.ai.llm :as llm]
            [kmet.ai.models :as models]
            [kmet.ai.auth :as auth]
            [kmet.libs.aws-sigv4 :as aws-sigv4]
            [kmet.ai.google-adc :as google-adc]
            [kmet.libs.usage :as usage]
            [kmet.app.tools.core :as tools]
            [kmet.app.skills :as skills]
            [kmet.app.tools.registry :as tools-registry]
            [kmet.app.extensions :as extensions]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.session :as session]
            [kmet.app.loop :as loop]
            [kmet.config :as cfg]
            [kmet.app.ui.chat-history :as ui]
            [kmet.tui.theme :as th]))

(declare make-test-provider)

;; ─── State construction ───────────────────────────────────────────────────

(t/deftest test-loop-make-agent-state-defaults
  (let [agent (loop/make-agent-state)]
    (t/is (= :idle @(:status agent)))
    (t/is (instance? clojure.lang.Atom (:messages agent)))
    (t/is (empty? @(:messages agent)))
    (t/is (= :opencode-go @(:provider agent)))
    (t/is (nil? @(:model agent)))
    (t/is (string? @(:system agent)))
    (t/is (instance? clojure.lang.Atom (:signal agent)))
    (t/is (false? @(:signal agent)))
    (t/is (nil? (:on-event agent)))
    (t/is (nil? (:session agent)))
    (t/is (= :off @(:thinking agent)))))

(t/deftest test-loop-make-agent-state-with-opts
  (let [session {:dummy :session}
        events (atom [])
        agent (loop/make-agent-state
               :model "gpt-4o"
               :provider :anthropic
               :system "custom prompt"
               :session session
               :on-event (fn [e] (swap! events conj e)))]
    (t/is (= "gpt-4o" @(:model agent)))
    (t/is (= :anthropic @(:provider agent)))
    (t/is (= "custom prompt" @(:system agent)))
    (t/is (= session (:session agent)))
    (t/is (fn? (:on-event agent)))))

(t/deftest test-loop-set-active-tools-rebuilds-system-prompt
  (let [all-tools (vals (tools-registry/get-all-tools))
        opts {:tools all-tools}
        prompt (skills/build-system-prompt :tools all-tools)
        agent (loop/make-agent-state
               :model "m"
               :system prompt
               :system-prompt-opts opts)]
    ;; initial prompt advertises bash
    (t/is (str/includes? @(:system agent) "- bash:"))
    (t/is (str/includes? @(:system agent) "Use bash for file operations"))
    ;; disable bash
    (loop/set-active-tools! agent (mapv :name (remove #(= "bash" (:name %)) all-tools)))
    (t/is (= #{"read" "edit" "write"} @(:enabled-tools agent)))
    (t/is (not (str/includes? @(:system agent) "- bash:")))
    (t/is (not (str/includes? @(:system agent) "Use bash for file operations")))
    ;; restore all
    (loop/set-active-tools! agent nil)
    (t/is (nil? @(:enabled-tools agent)))
    (t/is (str/includes? @(:system agent) "- bash:"))
    (t/is (str/includes? @(:system agent) "Use bash for file operations"))
    ;; unknown tool names are filtered out of the enabled set (pi)
    (loop/set-active-tools! agent ["read" "nonexistent-tool"])
    (t/is (= #{"read"} @(:enabled-tools agent)))
    (t/is (str/includes? @(:system agent) "- read:"))
    (t/is (not (str/includes? @(:system agent) "nonexistent-tool")))
    ;; a bare string is treated as a single tool name (untyped extensions)
    (loop/set-active-tools! agent "write")
    (t/is (= #{"write"} @(:enabled-tools agent)))
    (t/is (str/includes? @(:system agent) "- write:"))))

(t/deftest test-loop-reload-keeps-tools-reenableable
  ;; pi: reload rebuilds the prompt over the ACTIVE tool set — a tool
  ;; disabled before the reload must be re-enableable afterwards (the
  ;; reload handler in interactive.clj rebuilds opts over the active set;
  ;; set-active-tools! always validates against the live registry).
  (let [all-tools (vals (tools-registry/get-all-tools))
        opts {:tools all-tools}
        agent (loop/make-agent-state
               :model "m"
               :system (skills/build-system-prompt :tools all-tools)
               :system-prompt-opts opts)]
    ;; restrict to read (as an extension would)
    (loop/set-active-tools! agent ["read"])
    (t/is (= #{"read"} @(:enabled-tools agent)))
    ;; reload: prompt rebuilt over active set, opts updated
    (let [read-tool (first (filter #(= "read" (:name %)) all-tools))]
      (reset! (:system-prompt-opts agent) (assoc opts :tools [read-tool]))
      (reset! (:system agent) (skills/build-system-prompt :tools [read-tool]))
      (t/is (str/includes? @(:system agent) "- read:"))
      (t/is (not (str/includes? @(:system agent) "- bash:"))))
    ;; re-enable bash after the reload — must work (sources = full registry)
    (loop/set-active-tools! agent ["read" "bash"])
    (t/is (= #{"read" "bash"} @(:enabled-tools agent)))
    (t/is (str/includes? @(:system agent) "- bash:"))))

;; ─── State helpers ───────────────────────────────────────────────────────

(t/deftest test-loop-get-context
  (let [agent (loop/make-agent-state)]
    (t/is (empty? (loop/get-context agent)))
    (swap! (:messages agent) conj {:role :user :content "hi"})
    (t/is (= 1 (count (loop/get-context agent))))))

(t/deftest test-loop-drop-incomplete-tool-calls
  ;; A corrupted session (assistant tool calls without their results, or
  ;; orphaned tool results) must be repaired before it reaches a provider
  ;; that requires a tool message per tool_call_id.
  (let [tc-msg (fn [& ids]
                 {:role :assistant :content [{:type :text :text "hi"}]
                  :tool-calls (mapv (fn [id] {:id id :name "bash" :arguments {}})
                                    ids)})
        tr-msg (fn [id]
                 {:role :tool :content [{:type :tool_result :tool_use_id id :content "out"}]})
        user-msg {:role :user :content [{:type :text :text "q"}]}
        healthy [(tc-msg "a") (tr-msg "a") user-msg]]
    ;; healthy conversation passes through unchanged
    (t/is (= healthy (loop/drop-incomplete-tool-calls healthy)))
    ;; unanswered tool calls are dropped from their assistant message
    (t/is (= [(tc-msg "a") (tr-msg "a")]
             (loop/drop-incomplete-tool-calls [(tc-msg "a" "b") (tr-msg "a")])))
    ;; orphaned tool results (no matching tool call) are dropped
    (t/is (= [(tc-msg "a") (tr-msg "a")]
             (loop/drop-incomplete-tool-calls [(tr-msg "zzz") (tc-msg "a") (tr-msg "a")])))
    ;; an assistant message left without content and without tool calls is
    ;; dropped entirely
    (t/is (= []
             (loop/drop-incomplete-tool-calls
              [{:role :assistant :content []
                :tool-calls [{:id "a" :name "bash" :arguments {}}]}])))
    ;; text/thinking on the assistant message survives the tool-call drop
    (t/is (= [{:role :assistant :content [] :thinking "hmm"}]
             (loop/drop-incomplete-tool-calls
              [{:role :assistant :content [] :thinking "hmm"
                :tool-calls [{:id "a" :name "bash" :arguments {}}]}])))
    ;; a second batch's tool calls don't answer the first batch's orphans
    (t/is (= [(tc-msg "a") (tr-msg "a") user-msg
              {:role :assistant :content [{:type :text :text "hi"}]}]
             (loop/drop-incomplete-tool-calls
              [(tc-msg "a") (tr-msg "a") user-msg (tc-msg "b")])))))

(t/deftest test-loop-restore-session-context
  ;; Restoring a session rebuilds the agent context from the session branch —
  ;; steered and follow-up user messages come back for the next LLM call.
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-restore")))]
    (try
      (let [sess (session/create-session dir)]
        (session/append-entry sess {:role :user :content [{:type :text :text "hello"}]})
        (session/append-entry sess {:role :assistant :content [{:type :text :text "hi"}]})
        (session/append-entry sess {:role :user :content [{:type :text :text "steered"}]})
        (session/append-entry sess {:role :assistant :content [{:type :text :text "done"}]})
        (session/append-entry sess {:role :session_info :name "t"})
        (let [loaded (session/load-session (:file sess))
              agent (loop/make-agent-state :session loaded)]
          (loop/restore-session-context! agent)
          (t/is (= [:user :assistant :user :assistant]
                   (mapv :role (loop/get-context agent)))
                "session_info entries are metadata — not context")
          (t/is (= "steered" (-> (loop/get-context agent) (nth 2) :content first :text))
                "steered user message restored")))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-set-system-prompt
  (let [agent (loop/make-agent-state)]
    (reset! (:system agent) "new prompt")
    (t/is (= "new prompt" @(:system agent)))))

(t/deftest test-loop-set-model
  (let [agent (loop/make-agent-state)]
    (loop/set-model! agent "gpt-4")
    (t/is (= "gpt-4" @(:model agent)))))

(t/deftest test-loop-set-provider
  (let [agent (loop/make-agent-state)]
    (reset! (:provider agent) :anthropic)
    (t/is (= :anthropic @(:provider agent)))))

;; ─── Model/thinking change persistence (G6) ───────────────────────────────

(t/deftest test-loop-set-model-persists-change
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-model-change")))]
    (try
      (let [sess (session/create-session dir)
            agent (loop/make-agent-state :session sess
                                         :provider :anthropic
                                         :model "claude-sonnet")]
        (loop/set-model! agent "gpt-4o")
        (let [branch (session/get-branch sess)]
          (t/is (= [:model-change] (mapv :role branch)))
          (t/is (= "gpt-4o" (:model (last branch))))
          (t/is (= :anthropic (:provider (last branch)))
                "entry records the current provider"))
        (loop/set-model! agent "gpt-4o")
        (t/is (= 1 (count (session/get-branch sess)))
              "unchanged model: no duplicate entry"))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-cycle-model-persists-change
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-cycle-model")))
        saved (models/get-providers)]
    (models/clear-providers!)
    (models/register-provider! (make-test-provider ["a" "b"]))
    (try
      (with-redefs [auth/auth-atom (atom {:test-prov {:key "sk"}})]
        (let [sess (session/create-session dir)
              agent (loop/make-agent-state :session sess
                                           :provider :test-prov
                                           :model "a"
                                           :scoped-models ["test-prov/a" "test-prov/b"])]
          ;; lazy creation: file exists only after an assistant message
          (session/append-entry sess {:role :user :content "q"})
          (session/append-entry sess {:role :assistant :content "a"})
          (loop/cycle-model! agent 1)
          (t/is (= "b" @(:model agent)))
          (t/is (= :model-change (:role (last (session/get-branch sess)))))
          (let [loaded (session/load-session (:file sess))]
            (t/is (= "b" (:model (last (session/get-branch loaded))))
                  "model change persisted to disk"))))
      (finally
        (models/clear-providers!)
        (doseq [p saved] (models/register-provider! p))
        (fs/delete-tree dir)))))

(t/deftest test-loop-set-thinking-level-persists-change
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-thinking-change")))]
    (try
      (let [sess (session/create-session dir)
            agent (loop/make-agent-state :session sess :thinking :off)]
        (loop/set-thinking-level! agent :high)
        (t/is (= :high @(:thinking agent)))
        (t/is (= [:thinking-level-change] (mapv :role (session/get-branch sess))))
        (t/is (= :high (:thinking-level (last (session/get-branch sess)))))
        (loop/set-thinking-level! agent :high)
        (t/is (= 1 (count (session/get-branch sess)))
              "same level: no duplicate entry")
        (loop/set-thinking-level! agent :off)
        (t/is (= [:thinking-level-change :thinking-level-change]
                 (mapv :role (session/get-branch sess)))
              "explicit return to :off records a change"))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-restore-session-context-messages-only
  ;; pi alignment: buildSessionContext consumers use only :messages — tree
  ;; navigation/fork/clone never change the agent's model/thinking; the
  ;; session-derived settings are applied only on session load via
  ;; apply-session-settings!
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-restore-messages-only")))]
    (try
      (let [sess (session/create-session dir)]
        (session/append-entry sess {:role :user :content [{:type :text :text "hi"}]})
        (session/append-model-change! sess :anthropic "claude-sonnet")
        (session/append-thinking-level-change! sess :medium)
        (session/append-entry sess {:role :assistant :content [{:type :text :text "yo"}]})
        (let [loaded (session/load-session (:file sess))
              agent (loop/make-agent-state :session loaded
                                           :provider :opencode-go
                                           :model "gpt-4o"
                                           :thinking :off)]
          (loop/restore-session-context! agent)
          (t/is (= "gpt-4o" @(:model agent)))
          (t/is (= :opencode-go @(:provider agent)))
          (t/is (= :off @(:thinking agent)))
          (t/is (= [:user :assistant] (mapv :role (loop/get-context agent)))
                "change entries are metadata — not context")))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-restore-session-context-drops-incomplete-tool-calls
  ;; A session interrupted mid-tool-batch (process death between the assistant
  ;; tool-call message and its results) resumes without the dangling tool
  ;; calls — restore drops them so the next provider request stays valid.
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-restore-dangling")))]
    (try
      (let [sess (session/create-session dir)]
        (session/append-entry sess {:role :user :content [{:type :text :text "hello"}]})
        (session/append-entry sess {:role :assistant
                                    :content [{:type :text :text "running tools"}]
                                    :tool-calls [{:id "a" :name "bash" :arguments {}}
                                                 {:id "b" :name "bash" :arguments {}}]})
        ;; only one result was recorded before the crash
        (session/append-entry sess {:role :tool
                                    :content [{:type :tool_result :tool_use_id "a" :content "out"}]})
        (let [loaded (session/load-session (:file sess))
              agent (loop/make-agent-state :session loaded)]
          (loop/restore-session-context! agent)
          (let [ctx (loop/get-context agent)
                assistant (second ctx)]
            (t/is (= [:user :assistant :tool] (mapv :role ctx)))
            (t/is (= [{:id "a" :name "bash" :arguments {}}]
                     (:tool-calls assistant))
                  "the unanswered tool call is dropped on restore"))))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-apply-session-settings
  ;; pi: sdk.ts createAgentSession restore logic — the session-derived model
  ;; is applied only when it resolves to an authenticated model; thinking
  ;; only when a :thinking-level-change entry is on the branch (unrecorded
  ;; settings keep the current level). The auth atom is redirected so the
  ;; real auth.edn is never touched (test_auth pattern).
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-apply-settings")))
        saved-providers (models/get-providers)
        provider (models/map->Provider
                  {:id :test-prov :name "test"
                   :api-types #{:openai-completions}
                   :models [(models/map->Model {:id "m1" :name "M1" :provider :test-prov})
                            (models/map->Model {:id "m2" :name "M2" :provider :test-prov})]
                   :env-vars [] :default-model nil})
        make-session (fn []
                       (let [s (session/create-session dir)]
                         (session/append-entry s {:role :user :content [{:type :text :text "hi"}]})
                         (session/append-model-change! s :test-prov "m2")
                         (session/append-entry s {:role :assistant :content [{:type :text :text "yo"}]})
                         s))
        with-auth (fn [auth-map f]
                    (with-redefs [auth/auth-atom (atom auth-map)] (f)))]
    (try
      (models/clear-providers!)
      (models/register-provider! provider)
      (with-auth {:test-prov {:key "sk-test"}}
        (fn []
          ;; authenticated derived model + recorded thinking → both restored
          (let [s (make-session)
                _ (session/append-thinking-level-change! s :medium)
                agent (loop/make-agent-state :session (session/load-session (:file s))
                                             :provider :test-prov :model "m1" :thinking :off)]
            (t/is (loop/apply-session-settings! agent))
            (t/is (= "m2" @(:model agent)) "authenticated derived model restored")
            (t/is (= :medium @(:thinking agent)) "recorded thinking restored"))))
      ;; no auth → model not restored, thinking still is
      (with-auth {}
        (fn []
          (let [s (make-session)
                _ (session/append-thinking-level-change! s :high)
                agent (loop/make-agent-state :session (session/load-session (:file s))
                                             :provider :test-prov :model "m1" :thinking :off)]
            (t/is (loop/apply-session-settings! agent))
            (t/is (= "m1" @(:model agent)) "unauth'd model not restored (pi: hasConfiguredAuth)")
            (t/is (= :high @(:thinking agent)) "thinking has no auth guard"))))
      ;; unrecorded thinking → current level kept (pi: hasThinkingEntry guard)
      (with-auth {:test-prov {:key "sk-test"}}
        (fn []
          (let [agent (loop/make-agent-state :session (session/load-session (:file (make-session)))
                                             :provider :test-prov :model "m1" :thinking :high)]
            (t/is (loop/apply-session-settings! agent))
            (t/is (= "m2" @(:model agent)))
            (t/is (= :high @(:thinking agent)) "unrecorded thinking keeps the current level"))))
      (finally
        (models/clear-providers!)
        (doseq [p saved-providers] (models/register-provider! p))
        (fs/delete-tree dir)))))

;; ─── Cancellation ────────────────────────────────────────────────────────

(t/deftest test-loop-cancel-turn
  (let [agent (loop/make-agent-state)]
    (reset! (:status agent) :thinking)
    (reset! (:signal agent) false)
    (loop/cancel-turn agent)
    (t/is (= :idle @(:status agent)))
    (t/is (true? @(:signal agent)))))

(t/deftest test-loop-cancel-turn-when-idle
  (let [agent (loop/make-agent-state)]
    (loop/cancel-turn agent)
    (t/is (= :idle @(:status agent)))))

;; ─── Status transitions ──────────────────────────────────────────────────

(t/deftest test-loop-status-transitions
  (let [agent (loop/make-agent-state)]
    (t/is (= :idle @(:status agent)))
    (reset! (:status agent) :thinking)
    (t/is (= :thinking @(:status agent)))
    (reset! (:status agent) :executing)
    (t/is (= :executing @(:status agent)))
    (reset! (:status agent) :error)
    (t/is (= :error @(:status agent)))))

;; ─── Event dispatching ───────────────────────────────────────────────────

(t/deftest test-loop-on-event-fires-on-cancel
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/cancel-turn agent)
    (t/is (pos? (count @events)))
    (t/is (= :idle (:status (last @events))))))

(t/deftest test-loop-on-event-no-listener
  (let [agent (loop/make-agent-state)]
    (t/is (nil? (:on-event agent)))
    (loop/cancel-turn agent)
    (t/is (= :idle @(:status agent)))))

(t/deftest test-loop-make-agent-state-with-thinking
  (let [agent (loop/make-agent-state :thinking :high)]
    (t/is (= :high @(:thinking agent)))))

(t/deftest test-loop-thinking-default
  (let [agent (loop/make-agent-state)]
    (t/is (= :off @(:thinking agent)))))

;; ─── run-agent-turn with no API key ──────────────────────────────────────

(t/deftest test-loop-run-agent-turn-no-api-key
  (let [agent (loop/make-agent-state)
        errors (atom [])
        done (promise)]
    (loop/run-agent-turn agent
                         {:message "hello"
                          :on-error (fn [e] (swap! errors conj e) (deliver done true))})
    (t/is (true? (deref done 2000 :timeout)) "error callback fires")
    (t/is (pos? (count @errors)))
    (t/is (.contains (first @errors) "No API key"))))

(t/deftest test-loop-run-agent-turn-ambient-auth
  ;; google-vertex (ADC) and amazon-bedrock (AWS credentials) resolve no
  ;; api-key — the run guard must accept them as configured (the request
  ;; path resolves ambient auth itself), i.e. send-message is called
  ;; instead of the "No API key" refusal.
  (t/testing "amazon-bedrock with ambient AWS credentials"
    (let [agent (loop/make-agent-state)
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
      (t/is (some? @sent) "send-message ran — the ambient guard passed")))
  (t/testing "google-vertex with ADC configured"
    (let [agent (loop/make-agent-state)
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
    (let [agent (loop/make-agent-state)
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
  (let [agent (loop/make-agent-state)
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
        agent (loop/make-agent-state)
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
    (t/is (= 1 (count (filter #(= :user (:role %)) (loop/get-context agent))))
          "no new user message is added")
    (let [llm-msgs (:messages @sent)
          users (filter #(= :user (:role %)) llm-msgs)]
      (t/is (= 1 (count users)) "the LLM sees exactly one user message")
      (t/is (= "fix the bug" (get-in (first users) [:content 0 :text]))
            "the interrupted user message reaches the LLM")
      (t/is (= :system (-> llm-msgs first :role)) "system prompt prepended"))
    (t/is (= :assistant (-> (loop/get-context agent) last :role))
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
        agent (loop/make-agent-state :max-retries 0)]
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
      (t/is (= 1 (count (filter #(= :user (:role %)) (loop/get-context agent))))
            "the user message stays in context after the error")
      ;; Turn 2: /continue — no new user message, the model picks up
      @(loop/run-agent-turn agent
                            {:on-done (fn [_])
                             :on-error (fn [_])}))
    (t/is (= 2 @calls) "second run makes exactly one LLM call")
    (let [users (filter #(= :user (:role %)) (:messages @sent))]
      (t/is (= 1 (count users)) "continue sends the context with one user message")
      (t/is (= "fix the bug" (get-in (first users) [:content 0 :text]))))
    (t/is (= "fixed" (get-in (last (loop/get-context agent)) [:content 0 :text]))
          "the continued run completes the response")
    (t/is (= :idle @(:status agent)) "run settles idle after continue")))

;; ─── Multiple turns ──────────────────────────────────────────────────────

(t/deftest test-loop-messages-accumulate
  (let [agent (loop/make-agent-state)]
    (t/is (empty? @(:messages agent)))
    (swap! (:messages agent) conj {:role :user :content [{:type :text :text "hi"}]})
    (t/is (= 1 (count @(:messages agent))))))

;; ─── Regression: run-agent-turn signals cleanup ─────────────────────────

(t/deftest ^:slow test-loop-run-agent-turn-resets-signal
  (let [agent (loop/make-agent-state)]
    (reset! (:signal agent) true)
    (reset! (:status agent) :thinking)
    ;; run with no API key — should exercire error path
    (let [errors (atom [])]
      (loop/run-agent-turn agent
                           {:message "test"
                            :on-error (fn [e] (swap! errors conj e))})
      (Thread/sleep 200)
      ;; signal should have been reset by run-agent-turn
      (t/is (false? @(:signal agent)) "Signal reset at start of turn")
      (t/is (pos? (count @errors)) "Error callback called")
      (t/is (.contains (first @errors) "No API key") "Error message is about API key"))))

(t/deftest test-loop-signal-clean-after-turn
  (let [agent (loop/make-agent-state)
        done (promise)]
    (loop/run-agent-turn agent
                         {:message "hi"
                          :on-text (fn [_])
                          :on-done (fn [_] (deliver done true))
                          :on-error (fn [_] (deliver done true))})
    (deref done 2000 :timeout)
    (t/is (false? @(:signal agent)) "Signal should be false after turn ends")))

(t/deftest ^:slow test-loop-status-after-error
  (let [agent (loop/make-agent-state)]
    (reset! (:status agent) :idle)
    (loop/run-agent-turn agent
                         {:message "hi"
                          :on-error (fn [_])})
    (Thread/sleep 200)
    (t/is (= :idle @(:status agent)) "Status should be idle after error turn")
    (t/is (false? @(:signal agent)) "Signal should be false after error turn")))

(t/deftest test-loop-multiple-cancel
  (let [agent (loop/make-agent-state)]
    (loop/cancel-turn agent)
    (t/is (true? @(:signal agent)) "cancel-turn sets signal to true")
    ;; calling cancel-turn again is idempotent
    (loop/cancel-turn agent)
    (t/is (true? @(:signal agent)) "Signal remains true after second cancel")))

;; ─── Event routing ───────────────────────────────────────────────────────

(t/deftest test-loop-events-routed-to-extension-system
  (let [received (atom [])
        dereg (event-bus/on-event :status (fn [e] (swap! received conj e)))
        agent (loop/make-agent-state :on-event (fn [_]))]
    (loop/cancel-turn agent)
    (dereg)
    ;; Events reach extension listeners even though the UI callback ignores them
    (t/is (some #(= :idle (:status %)) @received)
          "Extension listener should receive the :status event from cancel-turn")))

(t/deftest test-loop-lifecycle-events
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
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

(t/deftest ^:slow test-loop-tool-execution-events
  (let [events (atom [])
        call-count (atom 0)
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
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
                  tools/execute-tool
                  ;; Streaming tool: emits one partial update via on-update
                  ;; (live output), then completes after 250ms. P4 contract:
                  ;; opts map {:on-update .. :signal .. :ctx ..} (pi:
                  ;; executeTool passes signal/onUpdate/ctx unconditionally)
                  (fn [_ _ opts]
                    (when-let [on-update (:on-update opts)]
                      (on-update {:content "partial output"}))
                    (Thread/sleep 250)
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
        (t/is (= "tc1" (:tool-call-id end)) "end carries the tool call id")
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
          agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e))
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
        agent (loop/make-agent-state)]
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
                  tools/execute-tool
                  (fn [name args _]
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
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (extensions/clear-before-agent-start-hooks!)
    (extensions/register-before-agent-start-hook!
     (fn [_]
       {:system-prompt "EXTRA SYSTEM PROMPT"
        :message {:role :info :label "ext" :content "injected note"}}))
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
    (extensions/clear-before-agent-start-hooks!)
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
        agent (loop/make-agent-state)]
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

(defn- run-turn-capturing-llm
  "Run one turn on AGENT with a stubbed LLM call; returns the captured
   send-message opts."
  [agent & [opts]]
  (let [sent (atom nil)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [o]
                    (reset! sent o)
                    (future
                      (when-let [on-done (:on-done o)]
                        (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent (merge {:on-done (fn [_])
                                          :on-error (fn [_])}
                                         opts)))
    @sent))

(t/deftest test-loop-block-images-strips-wire-images
  (let [agent (loop/make-agent-state :block-images true)
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
        sent (run-turn-capturing-llm agent
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
             (:content (first (filter #(= :user (:role %)) (loop/get-context agent)))))
          "session/transcript user content is untouched")))

(t/deftest test-loop-block-images-consecutive-dedupe
  (let [agent (loop/make-agent-state :block-images true)
        sent (run-turn-capturing-llm agent
                                     {:message "two"
                                      :images [{:type :image :data "AA" :mime-type "image/png"}
                                               {:type :image :data "BB" :mime-type "image/png"}]})]
    (t/is (= [{:type :text :text "two"}
              {:type :text :text "Image reading is disabled."}]
             (:content (first (filter #(= :user (:role %)) (:messages sent)))))
          "consecutive placeholders collapse to one (pi dedupe)")))

(t/deftest test-loop-block-images-off-passes-images-through
  (let [agent (loop/make-agent-state)  ;; block-images defaults false
        sent (run-turn-capturing-llm agent
                                     {:message "look"
                                      :images [{:type :image :data "AA" :mime-type "image/png"}]})]
    (t/is (= [{:type :text :text "look"}
              {:type :image :data "AA" :mime-type "image/png"}]
             (:content (first (filter #(= :user (:role %)) (:messages sent)))))
          "images pass through when the setting is off")))

(t/deftest test-loop-block-images-noncanonical-tool-message
  ;; Defensive shape (direct filter call — such a message is normally dropped
  ;; by drop-incomplete-tool-calls before this filter runs): string content
  ;; with :images still loses the images (nothing may reach the provider)
  ;; without the content rewrite blowing up.
  (let [f (deref #'kmet.app.loop/block-message-images)
        out (f [{:role :tool :content "plain" :images [{:data "AA"}]}])]
    (t/is (nil? (:images (first out))) "images dropped")
    (t/is (= "plain" (:content (first out))) "content left as-is")))

(t/deftest test-loop-set-block-images-live
  (let [agent (loop/make-agent-state)]
    (t/is (false? (:block-images @(:cfg agent))))
    (loop/set-block-images! agent true)
    (t/is (true? (:block-images @(:cfg agent))))
    (let [sent (run-turn-capturing-llm agent
                                       {:message "look"
                                        :images [{:type :image :data "AA" :mime-type "image/png"}]})]
      (t/is (not-any? #(= :image (:type %))
                      (:content (first (filter #(= :user (:role %)) (:messages sent)))))
            "a mid-session toggle applies to the next call (pi reads it per request)"))))

;; ─── Queues (steering / follow-up) ────────────────────────────────────────

(t/deftest test-loop-steer-queues-message
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/steer! agent "first")
    (loop/steer! agent "second")
    (t/is (= ["first" "second"] @(:steering agent)))
    (t/is (loop/has-queued-messages? agent))
    (t/is (= {:steering ["first" "second"] :follow-up []}
             (loop/queued-messages agent)))
    (t/is (= :queue-update (:type (last @events)))
          "steer! emits :queue-update")
    (loop/clear-queues! agent)
    (t/is (empty? @(:steering agent)))
    (t/is (empty? @(:follow-up agent)))
    (t/is (not (loop/has-queued-messages? agent)))
    (t/is (= :queue-update (:type (last @events)))
          "clear-queues! emits :queue-update")))

(t/deftest test-loop-followup-queues-message
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/follow-up! agent "later")
    (t/is (= ["later"] @(:follow-up agent)))
    (t/is (loop/has-queued-messages? agent))
    (t/is (= :queue-update (:type (last @events)))
          "follow-up! emits :queue-update")))

(t/deftest test-loop-cancel-clears-queues
  (let [agent (loop/make-agent-state)]
    (loop/steer! agent "x")
    (loop/follow-up! agent "y")
    (loop/cancel-turn agent)
    (t/is (empty? @(:steering agent)) "cancel drops steering messages")
    (t/is (empty? @(:follow-up agent)) "cancel drops follow-up messages")
    (t/is (= :idle @(:status agent)))))

(t/deftest test-loop-steer-injects-mid-run
  (let [calls (atom 0)
        events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        ;; second call: verify the steered message reached context
                        (do (when-let [on-text (:on-text opts)]
                              (on-text (if (some #(= "steered" (get-in % [:content 0 :text]))
                                                 (:messages opts))
                                         "ok" "MISSING")))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (loop/steer! agent "steered")
                    {:content "ok" :is-error false})]
      ;; deref inside with-redefs keeps the rebinding until the run completes
      @(loop/run-agent-turn agent
                            {:message "first"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (t/is (= 2 @calls) "exactly two LLM calls: initial + steered")
    (t/is (some #(= "steered" (get-in % [:content 0 :text])) (loop/get-context agent))
          "steered message should be in context")
    (t/is (= "ok" (get-in (last (loop/get-context agent)) [:content 0 :text]))
          "final response should see the steered message")
    (t/is (empty? @(:steering agent)) "steering queue drained")
    (let [queue-updates (filter #(= :queue-update (:type %)) @events)]
      (t/is (= 2 (count queue-updates))
            "one :queue-update from steer!, one from consumption")
      (t/is (= {:steering ["steered"] :follow-up []}
               (select-keys (first queue-updates) [:steering :follow-up]))
            "steer! reports the queued message")
      (t/is (= {:steering [] :follow-up []}
               (select-keys (last queue-updates) [:steering :follow-up]))
            "consumption reports the drained queue"))
    (t/is (= :idle @(:status agent)) "agent idle after run")))

(t/deftest test-loop-followup-continues-run
  (let [calls (atom 0)
        done-count (atom 0)
        events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text (if (= 2 @calls) "second" "third")))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (loop/follow-up! agent "followup")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "start"
                             :on-done (fn [_] (swap! done-count inc))
                             :on-error (fn [_])}))
    (t/is (= 3 @calls) "three LLM calls: initial, tool follow-up, queued follow-up")
    (t/is (= 1 @done-count) "on-done called once, at the very end")
    (let [ctx (loop/get-context agent)]
      (t/is (some #(= "followup" (get-in % [:content 0 :text])) ctx)
            "follow-up message should be in context")
      (t/is (some #(and (= :assistant (:role %))
                        (= "second" (get-in % [:content 0 :text]))) ctx))
      (t/is (some #(and (= :assistant (:role %))
                        (= "third" (get-in % [:content 0 :text]))) ctx)))
    (t/is (empty? @(:follow-up agent)) "follow-up queue drained")
    (let [queue-updates (filter #(= :queue-update (:type %)) @events)]
      (t/is (= 2 (count queue-updates))
            "one :queue-update from follow-up!, one from consumption")
      (t/is (= {:steering [] :follow-up ["followup"]}
               (select-keys (first queue-updates) [:steering :follow-up]))
            "follow-up! reports the queued message")
      (t/is (= {:steering [] :follow-up []}
               (select-keys (last queue-updates) [:steering :follow-up]))
            "consumption reports the drained queue"))
    (t/is (= :idle @(:status agent)) "agent idle after run")))

(t/deftest test-loop-steer-one-at-a-time
  (let [calls (atom 0)
        agent (loop/make-agent-state :steering-mode :one-at-a-time)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "resp"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (loop/steer! agent "s1")
                    (loop/steer! agent "s2")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "start"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (t/is (= 3 @calls) "three LLM calls: initial + one per steered message")
    (t/is (some #(= "s1" (get-in % [:content 0 :text])) (loop/get-context agent)))
    (t/is (some #(= "s2" (get-in % [:content 0 :text])) (loop/get-context agent)))
    (t/is (empty? @(:steering agent)) "steering queue drained")
    (t/is (= :idle @(:status agent)))))

(t/deftest test-loop-consumed-queued-messages-emit-user-message-start
  ;; The UI displays consumed steering/follow-up messages on :message-start
  ;; (pi: message_start → addMessageToChat), so the loop must emit it with
  ;; the user role and the queued text when a message is drained.
  (let [calls (atom 0)
        events (atom [])
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "resp"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (loop/steer! agent "steered")
                    (loop/follow-up! agent "followup")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "start"
                             :on-done (fn [_])
                             :on-error (fn [_])})
      (let [user-starts (filter #(and (= :message-start (:type %))
                                      (= :user (:role (:message %))))
                                @events)
            texts (mapv #(get-in % [:message :content 0 :text]) user-starts)]
        (t/is (some #(= "steered" %) texts)
              "steered message emits :message-start with role :user on consumption")
        (t/is (some #(= "followup" %) texts)
              "follow-up message emits :message-start with role :user on consumption")))))

(t/deftest test-loop-no-api-key-emits-user-message-start
  ;; The run cannot start without an API key, but the submitted message must
  ;; still be displayed (pi emits message_start for prompt messages before
  ;; running the loop) — the UI adds it to the chat on :message-start.
  (let [events (atom [])
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] nil)]
      @(loop/run-agent-turn agent
                            {:message "hello"
                             :on-error (fn [_])})
      (let [user-start (first (filter #(and (= :message-start (:type %))
                                            (= :user (:role (:message %))))
                                      @events))]
        (t/is (some? user-start) ":message-start emitted for the submitted message")
        (t/is (= "hello" (get-in user-start [:message :content 0 :text])))
        (t/is (empty? (loop/get-context agent))
              "message is display-only — not added to context without a run")))))

;; ─── Queue modes (per-queue) ───────────────────────────────────────────────

(t/deftest test-loop-queue-modes-default
  (let [agent (loop/make-agent-state)]
    (t/is (= :all (:steering-mode @(:cfg agent))))
    (t/is (= :all (:follow-up-mode @(:cfg agent))))))

(t/deftest test-loop-queue-mode-setters
  ;; /settings applies queue modes + idle timeout live (pi: setSteeringMode /
  ;; setFollowUpMode / setHttpIdleTimeoutMs)
  (let [agent (loop/make-agent-state)]
    (swap! (:cfg agent) assoc :steering-mode :one-at-a-time)
    (swap! (:cfg agent) assoc :follow-up-mode :one-at-a-time)
    (t/is (= :one-at-a-time (:steering-mode @(:cfg agent))))
    (t/is (= :one-at-a-time (:follow-up-mode @(:cfg agent))))
    (loop/set-http-idle-timeout-ms! agent 60000)
    (t/is (= 60000 (:http-idle-timeout-ms @(:cfg agent))))))

(t/deftest test-loop-auto-compact-flag
  ;; pi: autoCompact — the flag gates proactive compaction, defaults on,
  ;; and is settable live (/settings row)
  (let [agent (loop/make-agent-state)]
    (t/is (true? (:auto-compact @(:cfg agent))))
    (loop/set-auto-compact! agent false)
    (t/is (false? (:auto-compact @(:cfg agent))))
    (t/is (false? (loop/maybe-compact! agent))
          "no session → no proactive compaction regardless"))
  (let [agent (loop/make-agent-state :auto-compact false)]
    (t/is (false? (:auto-compact @(:cfg agent))))))

(t/deftest test-loop-followup-one-at-a-time
  (let [calls (atom 0)
        agent (loop/make-agent-state :follow-up-mode :one-at-a-time)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "resp"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (loop/follow-up! agent "f1")
                    (loop/follow-up! agent "f2")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "start"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (t/is (= 4 @calls) "four LLM calls: initial + tool + one per follow-up")
    (t/is (empty? @(:follow-up agent)) "follow-up queue drained")))

;; ─── Cancellation ─────────────────────────────────────────────────────────

(t/deftest ^:slow test-loop-cancel-delivers-promise
  (let [errors (atom [])
        dones (atom 0)
        events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  ;; hangs forever: on-done/on-error never fire
                  (fn [_] (future (Thread/sleep 10000) :never))]
      (let [fut (loop/run-agent-turn agent
                                     {:message "hi"
                                      :on-done (fn [_] (swap! dones inc))
                                      :on-error (fn [e] (swap! errors conj e))})]
        ;; wait until the LLM call is in flight
        (loop []
          (when (nil? @(:active-call agent))
            (Thread/sleep 20)
            (recur)))
        (let [start (System/currentTimeMillis)]
          (loop/cancel-turn agent)
          ;; Bounded wait: a working cancel settles in ms; if the cancel
          ;; delivery ever fails, fail promptly instead of stalling the
          ;; suite for the 330s LLM deref deadline.
          (deref fut 15000 :timeout)
          (t/is (< (- (System/currentTimeMillis) start) 5000)
                "run ends promptly after cancel (no 120s timeout wait)")
          (t/is (empty? @errors) "no error callback on cancel")
          (t/is (zero? @dones) "no done callback on cancel")
          (t/is (= :idle @(:status agent)) "status idle after cancel")
          ;; The cancelled call settles as a stopReason-aborted attempt
          ;; (pi: an aborted partial persists to the session history)
          (t/is (some #(and (= :message-end (:type %))
                            (= :aborted (:stop-reason (:message %))))
                      @events)
                "cancel delivers an aborted :message-end")
          (let [ae-idx (first (keep-indexed #(when (= :agent-end (:type %2)) %1) @events))
                as-idx (first (keep-indexed #(when (= :agent-settled (:type %2)) %1) @events))]
            (t/is (some? ae-idx) ":agent-end emitted on cancel")
            (t/is (some? as-idx) ":agent-settled emitted on cancel")
            (t/is (< ae-idx as-idx) ":agent-settled follows :agent-end on cancel")))))))

;; ─── Error events ─────────────────────────────────────────────────────────

(t/deftest test-loop-error-event-emitted
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-error (:on-error opts)]
                        (on-error "boom"))
                      :done))]
      @(loop/run-agent-turn agent
                            {:message "hi"
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (let [types (mapv :type @events)]
      (t/is (some #{:error} types) ":error event emitted on LLM error")
      (t/is (some #(and (= :agent-end (:type %)) (= "boom" (:error %))) @events)
            ":agent-end carries the error")
      (t/is (some #{:agent-settled} types)
            ":agent-settled emitted after an errored run"))))

(t/deftest ^:slow test-loop-cancel-during-tool-execution
  (let [errors (atom [])
        dones (atom 0)
        agent (loop/make-agent-state)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when-let [on-tc (:on-tool-call opts)]
                        (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                      (when-let [on-done (:on-done opts)]
                        (on-done :tool-calls))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (Thread/sleep 2000)
                    {:content "ok" :is-error false})]
      (let [fut (loop/run-agent-turn agent
                                     {:message "hi"
                                      :on-done (fn [_] (swap! dones inc))
                                      :on-error (fn [e] (swap! errors conj e))})]
        ;; wait until the tool is executing
        (loop []
          (when-not (= :executing @(:status agent))
            (Thread/sleep 20)
            (recur)))
        (let [start (System/currentTimeMillis)]
          (loop/cancel-turn agent)
          (deref fut 15000 :timeout)
          ;; Window 10s: cancel latency = time-to-tool-completion (~2s) +
          ;; loop response; a broken cancel hangs to the 15s deref timeout.
          (t/is (< (- (System/currentTimeMillis) start) 10000)
                "run ends promptly after cancel during tool execution")
          (t/is (empty? @errors) "no error callback on cancel")
          (t/is (zero? @dones) "no done callback on cancel")
          (t/is (= :idle @(:status agent)) "status idle after cancel"))))))

;; ─── Retry classification ─────────────────────────────────────────────────

(t/deftest test-loop-retryable-error?
  (t/is (loop/retryable-error? "rate limit exceeded"))
  (t/is (loop/retryable-error? "429 Too Many Requests"))
  (t/is (loop/retryable-error? "503 service unavailable"))
  (t/is (loop/retryable-error? "Internal Server Error"))
  (t/is (loop/retryable-error? "connection refused"))
  (t/is (loop/retryable-error? "ECONNRESET: socket hang up"))
  ;; Network transport failures — the llm layer reports these with a stable
  ;; 'network error' token (java.net.http ConnectExceptions carry a nil
  ;; message on this JDK; see kmet.ai.llm/transport-error-message)
  (t/is (loop/retryable-error? "network error: ConnectException"))
  (t/is (loop/retryable-error? "network error: Connection reset"))
  (t/is (loop/retryable-error? "network error: request timed out"))
  (t/is (loop/retryable-error? "Request timed out"))
  (t/is (loop/retryable-error? "HTTP/1.1 header parser received no bytes"))
  (t/is (loop/retryable-error? "Provider returned error: upstream connect"))
  ;; HTTP/2 RST_STREAM (java.net.http throws a plain IOException whose
  ;; message is "Received RST_STREAM: <code>") — same class of transport
  ;; reset as "Connection reset", so it must be retried too; both the raw
  ;; llm-layer message and the sse read-path "Stream error: ..." prefix.
  (t/is (loop/retryable-error? "Received RST_STREAM: Protocol error"))
  (t/is (loop/retryable-error? "Stream error: Received RST_STREAM: Protocol error"))
  (t/is (loop/retryable-error? "Received RST_STREAM: CANCEL"))
  (t/is (loop/retryable-error? "Stream error: Connection reset"))
  ;; Other JVM transport-reset phrasings (OS/JDK-specific) surfacing on the
  ;; sse read path without the 'network error' token.
  (t/is (loop/retryable-error? "Software caused connection abort: recv failed"))
  (t/is (loop/retryable-error? "Stream error: Software caused connection abort"))
  (t/is (loop/retryable-error? "An existing connection was forcibly closed by the remote host"))
  (t/is (loop/retryable-error? "Broken pipe"))
  ;; Mid-stream close on the sse read path — java.net.http surfaces a
  ;; dropped connection as a bare "closed" IOException, wrapped by kmet's
  ;; 'Stream error: ' prefix (no other token in the message)
  (t/is (loop/retryable-error? "Stream error: closed"))
  (t/is (loop/retryable-error? "Stream error: Connection is closed"))
  ;; premature EOF on the response stream — same mid-drop family as the
  ;; stream-close / connection-lost tokens ('EOF reached while reading' is
  ;; the JDK HTTP client's wording for a connection that ended mid-body)
  (t/is (loop/retryable-error? "Error: EOF reached while reading"))
  (t/is (loop/retryable-error? "Unexpected EOF"))
  (t/is (not (loop/retryable-error? "EOF Exception: quota exceeded")))
  ;; non-close stream errors stay non-retryable
  (t/is (not (loop/retryable-error? "Stream error: Bedrock stream frame CRC mismatch")))
  ;; OpenRouter upstream-routing failure — transient even without a status
  ;; token in the body (with one, the 'HTTP 5xx: ' prefix matches first)
  (t/is (loop/retryable-error? "Upstream request failed: Endpoint  is unavailable."))
  (t/is (loop/retryable-error? "HTTP 503: Upstream request failed: Endpoint  is unavailable."))
  (t/is (not (loop/retryable-error? "insufficient_quota")))
  (t/is (not (loop/retryable-error? "Monthly usage limit reached")))
  (t/is (not (loop/retryable-error? "GoUsageLimitError")))
  (t/is (not (loop/retryable-error? "Invalid API key provided")))
  (t/is (not (loop/retryable-error? nil)))
  (t/is (not (loop/retryable-error? ""))))

(t/deftest test-loop-context-overflow?
  (t/is (loop/context-overflow? "prompt is too long: 213462 tokens > 200000 maximum"))
  (t/is (loop/context-overflow? "This model's maximum context length is 128000 tokens"))
  (t/is (loop/context-overflow? "Your input exceeds the context window of this model"))
  (t/is (loop/context-overflow? "context_length_exceeded"))
  (t/is (loop/context-overflow? "exceeded model token limit: 100000 (requested: 200000)"))
  (t/is (not (loop/context-overflow? "rate limit exceeded")))
  (t/is (not (loop/context-overflow? "Throttling error: Too many tokens, please wait")))
  (t/is (not (loop/context-overflow? nil))))

;; ─── Repeat-loop guard (circuit breaker) ────────────────────────────────

(defn- loop-guard-tc
  "A tool-call map shaped like the loop's accumulator output."
  [name args]
  {:id (str (gensym "tc")) :name name :arguments args})

(t/deftest test-loop-guard-filter-same-call
  ;; The 3rd identical call (threshold 3) is suppressed.
  (let [r (reduce (fn [st _]
                    (:state (loop/loop-guard-filter st 3 [(loop-guard-tc "bash" {:command "ls"})])))
                  {:window [] :suppressed 0} (range 3))]
    (t/is (= 1 (:suppressed r)) "3rd identical call suppressed")))

(t/deftest test-loop-guard-filter-alternating
  ;; 1,2,1,2,1,2 → the 3rd "1" (call 5) is suppressed — alternation is
  ;; caught by the per-signature window count, not just consecutive runs.
  (let [r (reduce (fn [st i]
                    (:state (loop/loop-guard-filter
                             st 3 [(loop-guard-tc "bash" {:command (str "cmd" (inc (mod i 2)))})])))
                  {:window [] :suppressed 0} (range 6))]
    (t/is (= 2 (:suppressed r)) "1,2,1,2,1,2 trips at the 3rd occurrence of each sig")))

(t/deftest test-loop-guard-filter-cycle
  ;; 1,2,3,1,2,3 → the 3rd "1" (call 7) is suppressed.
  (let [r (reduce (fn [st i]
                    (:state (loop/loop-guard-filter
                             st 3 [(loop-guard-tc "bash" {:command (str "c" (inc (mod i 3)))})])))
                  {:window [] :suppressed 0} (range 7))]
    (t/is (= 1 (:suppressed r)) "1,2,3,1,2,3 trips at call 7"))
  ;; 1,2,3,4 ×3 → window 13 catches the 3rd "1" at call 9.
  (let [r (reduce (fn [st i]
                    (:state (loop/loop-guard-filter
                             st 3 [(loop-guard-tc "bash" {:command (str "c" (inc (mod i 4)))})])))
                  {:window [] :suppressed 0} (range 9))]
    (t/is (= 1 (:suppressed r)) "4-cycle trips at call 9")))

(t/deftest test-loop-guard-filter-read-exempt
  ;; read never trips the guard — re-reading a file is legitimate work.
  (let [r (reduce (fn [st _]
                    (:state (loop/loop-guard-filter st 3 [(loop-guard-tc "read" {:path "a"})])))
                  {:window [] :suppressed 0} (range 10))]
    (t/is (= 0 (:suppressed r)) "read is exempt")
    (t/is (= 0 (count (:window r))) "read never enters the window")))

(t/deftest test-loop-guard-filter-key-order-canonical
  ;; Argument maps differing only in key order count as identical.
  (t/is (= (loop/loop-guard-signature "edit" {:path "x" :edits [{:oldText "a" :newText "b"}]})
           (loop/loop-guard-signature "edit" {:edits [{:newText "b" :oldText "a"}] :path "x"}))
        "key-sorted canonical JSON ignores key order"))

(t/deftest test-loop-thinking-loop-detection
  (t/is (loop/thinking-loop? "Let me analyze this problem carefully. Let me analyze this problem carefully. Let me analyze this problem carefully."))
  (t/is (not (loop/thinking-loop? "First check the file. Then read imports. Then run tests.")))
  (t/is (not (loop/thinking-loop? "ok. ok. ok. ok."))
        "segments below the min span don't trip")
  (t/is (not (loop/thinking-loop? nil)) "nil is total — no NPE")
  (t/is (not (loop/thinking-loop? "")) "empty is false")
  (t/is (loop/thinking-loop? (str (apply str (repeat 3 "我们需要继续深入分析这个问题的根本原因和潜在影响。"))))
        "CJK full-stop delimiters trip — multilingual reasoning loops are caught"))

(t/deftest test-loop-guard-mixed-batch-source-order
  ;; A batch with one suppressed + one surviving call returns results in
  ;; source order — the suppressed call's synthetic guard result first, the
  ;; survivor's real result second (regression: map-over-map produced
  ;; garbage entries).
  (let [events (atom [])
        executed (atom [])
        calls (atom 0)
        agent (loop/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (cond
                          (= 1 n)
                          (do (when-let [on-tc (:on-tool-call opts)]
                                (on-tc {:id "s1" :name "bash" :arguments "{\"command\":\"ls\"}" :index 0}))
                              (when-let [on-done (:on-done opts)] (on-done :tool-calls)))
                          (= 2 n)
                          (do (when-let [on-tc (:on-tool-call opts)]
                                (on-tc {:id "s2" :name "bash" :arguments "{\"command\":\"ls\"}" :index 0})
                                (on-tc {:id "s3" :name "bash" :arguments "{\"command\":\"ls\"}" :index 1})
                                (on-tc {:id "w1" :name "bash" :arguments "{\"command\":\"pwd\"}" :index 2}))
                              (when-let [on-done (:on-done opts)] (on-done :tool-calls)))
                          :else
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "done"))
                              (when-let [on-done (:on-done opts)] (on-done :stop)))))
                      :done))
                  tools/execute-tool
                  (fn [_ args _] (swap! executed conj (:command args))
                    {:content "ran" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    ;; turn 2: s2 (2nd identical) runs, s3 (3rd identical) suppressed, w1 runs
    (t/is (= 3 (count @executed)) "three tool executions total (turn 1 + turn 2)")
    ;; the turn-2 turn-end is the one with 3 tool results (turn 1 has 1,
    ;; the "done" reply has 0); results are in SOURCE order [s2 s3 w1] →
    ;; [ran guard ran]
    (let [te (first (filter #(and (= :turn-end (:type %))
                                  (= 3 (count (:tool-results %))))
                            @events))]
      (t/is (= ["ran" "Repeat-loop guard: bash called with identical args 3 times — suppressed." "ran"]
               (mapv :content (:tool-results te)))
            "turn-end carries results in source order, suppressed call carries guard text"))))

(t/deftest test-loop-run-agent-turn-loop-guard-terminates
  ;; End-to-end: the model keeps issuing the same bash call; the guard
  ;; suppresses the 3rd, then the run gives up (all-suppressed batch →
  ;; :loop-guard), settles idle, and delivers the explanation via on-done.
  (let [events (atom [])
        calls (atom 0)
        done-text (atom nil)
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! calls inc)
                      (when-let [on-tc (:on-tool-call opts)]
                        (on-tc {:id (str "tc" @calls) :name "bash" :arguments "{\"command\":\"ls\"}" :index 0}))
                      (when-let [on-done (:on-done opts)]
                        (on-done :tool-calls))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "run"
                             :on-done (fn [t] (reset! done-text t))
                             :on-error (fn [_])}))
    (t/is (>= @calls 3) "the guard gave up after the 3rd identical call")
    (t/is (some #(= :loop-guard (:type %)) @events)
          ":loop-guard event emitted")
    (t/is (= :idle @(:status agent)) "run settles idle")
    (t/is (str/includes? (or @done-text "") "repeated identical tool calls")
          "on-done carries the guard explanation")))

(t/deftest test-loop-run-agent-turn-thinking-loop-guard-terminates
  ;; End-to-end: the model streams the same reasoning segment repeatedly;
  ;; the thinking-loop guard cuts the stream and settles the run with the
  ;; explanation (no auto-retry, no red error line).
  (let [events (atom [])
        done-text (atom nil)
        llm-signal (atom nil)
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    ;; The LLM-call signal is the per-call stream cut — the only
                    ;; transport the SSE loop derefs — so capture it to assert
                    ;; the guard actually aborts the HTTP stream below.
                    (reset! llm-signal (:signal opts))
                    (future
                      (when-let [on-thinking (:on-thinking opts)]
                        (dotimes [_ 4]
                          (on-thinking "Let me analyze this problem carefully. ")))
                      (Thread/sleep 30)
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent
                            {:message "run"
                             :on-done (fn [t] (reset! done-text t))
                             :on-error (fn [e] (reset! done-text (str "ERR:" e)))}))
    (t/is (some #(= :loop-guard (:type %)) @events)
          ":loop-guard event emitted")
    (t/is (= :idle @(:status agent)) "run settles idle")
    (t/is (str/includes? (or @done-text "") "repeated reasoning detected")
          "on-done carries the thinking-guard explanation")
    (t/is (not (str/starts-with? (or @done-text "") "ERR:"))
          "not routed through the red error path")
    (t/is (true? (boolean @llm-signal))
          "the guard trips its per-call stream cut — the passed signal derefs true")
    (t/is (false? @(:signal agent))
          "the run-level cancel signal stays false — follow-up turns share it and must not inherit a stray cancel")))

(t/deftest test-loop-run-agent-turn-loop-guard-followup-retrips
  ;; A follow-up queued before the run gives the model a fresh guard window:
  ;; after the first trip the follow-up's turn re-runs, and a still-stuck
  ;; model trips again — each segment gets its own threshold (the follow-up
  ;; is a new directive, not a continuation of the loop).
  (let [events (atom [])
        calls (atom 0)
        agent (loop/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (when-let [on-tc (:on-tool-call opts)]
                          (on-tc {:id (str "t" n) :name "bash"
                                  :arguments "{\"command\":\"ls\"}" :index 0}))
                        (when-let [on-done (:on-done opts)]
                          (on-done :tool-calls))
                        :done)))
                  tools/execute-tool
                  (fn [_ _ _] {:content "ok" :is-error false})]
      (loop/follow-up! agent "continue please")
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 2 (count (filter #(= :loop-guard (:type %)) @events)))
          "each loop segment (initial run + follow-up) trips once")
    (t/is (= 6 (count (filter #(= :tool (:role %)) (loop/get-context agent))))
          "two segments x 3 tool entries (2 real + 1 suppressed each)")
    (t/is (= :idle @(:status agent)) "run settles idle")))

(t/deftest test-loop-run-agent-turn-loop-guard-disabled
  ;; Guard off → the loop runs without suppression: same calls repeat past
  ;; the threshold without any :loop-guard event, and the run settles
  ;; normally once the stub produces a plain reply.
  (let [events (atom [])
        calls (atom 0)
        agent (loop/make-agent-state
               :loop-guard-enabled false
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (when (<= n 3)
                          (when-let [on-tc (:on-tool-call opts)]
                            (on-tc {:id (str "tc" n) :name "bash" :arguments "{}" :index 0})))
                        (when-let [on-done (:on-done opts)]
                          (on-done (if (<= n 3) :tool-calls :stop)))
                        (when (and (> n 3) (:on-text opts))
                          ((:on-text opts) "done")))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (nil? (some #(= :loop-guard (:type %)) @events))
          "no :loop-guard event when disabled")
    (t/is (>= @calls 3) "the same call repeated past the threshold")
    (t/is (= :idle @(:status agent)))))

(t/deftest test-loop-run-agent-turn-write-resets-guard
  ;; bash -> edit(ok) -> bash -> edit(ok) -> ... never trips: each successful
  ;; edit clears the guard's memory of other calls (the world changed, so a
  ;; repeated bash observes new state). Without the reset the 3rd bash (call
  ;; 5) would be suppressed and the run would give up.
  (let [events (atom [])
        calls (atom 0)
        done-text (atom nil)
        agent (loop/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (if (> n 7)
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "done"))
                              (when-let [on-done (:on-done opts)] (on-done :stop)))
                          (do (when-let [on-tc (:on-tool-call opts)]
                                (if (odd? n)
                                  (on-tc {:id (str "b" n) :name "bash"
                                          :arguments "{\"command\":\"ls\"}" :index 0})
                                  ;; Distinct edit args per turn — required:
                                  ;; identical edits would trip on their own
                                  ;; signature (see identical-write-trips).
                                  (on-tc {:id (str "e" n) :name "edit"
                                          :arguments (str "{\"path\":\"f.clj\",\"edits\":[{\"oldText\":\"a" n "\",\"newText\":\"b" n "\"}]}")
                                          :index 0})))
                              (when-let [on-done (:on-done opts)]
                                (on-done :tool-calls)))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "run"
                             :on-done (fn [t] (reset! done-text t))
                             :on-error (fn [_])}))
    (t/is (>= @calls 8) "run continued past the 3rd bash without tripping")
    (t/is (nil? (some #(= :loop-guard (:type %)) @events))
          "no :loop-guard event — successful edits reset the guard")
    (t/is (= "done" @done-text) "run settles normally")))

(t/deftest test-loop-run-agent-turn-write-tool-resets-guard
  ;; Same contract as the edit reset, through the write tool: bash ->
  ;; write(ok, novel content) -> ... never trips.
  (let [events (atom [])
        calls (atom 0)
        done-text (atom nil)
        agent (loop/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (if (> n 7)
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "done"))
                              (when-let [on-done (:on-done opts)] (on-done :stop)))
                          (do (when-let [on-tc (:on-tool-call opts)]
                                (if (odd? n)
                                  (on-tc {:id (str "b" n) :name "bash"
                                          :arguments "{\"command\":\"ls\"}" :index 0})
                                  ;; Distinct content per turn — required:
                                  ;; identical writes trip on their own
                                  ;; signature (see identical-write-trips).
                                  (on-tc {:id (str "w" n) :name "write"
                                          :arguments (str "{\"path\":\"f.clj\",\"content\":\"v" n "\"}")
                                          :index 0})))
                              (when-let [on-done (:on-done opts)]
                                (on-done :tool-calls)))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "run"
                             :on-done (fn [t] (reset! done-text t))
                             :on-error (fn [_])}))
    (t/is (>= @calls 8) "run continued past the 3rd bash without tripping")
    (t/is (nil? (some #(= :loop-guard (:type %)) @events))
          "no :loop-guard event — successful writes reset the guard")
    (t/is (= "done" @done-text) "run settles normally")))

(t/deftest test-loop-run-agent-turn-failed-write-keeps-guard
  ;; edit failures change nothing: bash -> edit(fail) -> bash ... still trips
  ;; on the 3rd identical bash.
  (let [events (atom [])
        calls (atom 0)
        agent (loop/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [n (swap! calls inc)]
                        (when-let [on-tc (:on-tool-call opts)]
                          (if (odd? n)
                            (on-tc {:id (str "b" n) :name "bash"
                                    :arguments "{\"command\":\"ls\"}" :index 0})
                            (on-tc {:id (str "e" n) :name "edit"
                                    :arguments "{\"path\":\"f.clj\",\"edits\":[{\"oldText\":\"a\",\"newText\":\"b\"}]}"
                                    :index 0})))
                        (when-let [on-done (:on-done opts)]
                          (on-done :tool-calls)))
                      :done))
                  tools/execute-tool
                  (fn [tool-name _ _]
                    (if (= "edit" tool-name)
                      {:content "edit failed" :is-error true}
                      {:content "ok" :is-error false}))]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 5 @calls) "the guard gave up on the 3rd identical bash")
    (t/is (some #(= :loop-guard (:type %)) @events)
          ":loop-guard event emitted — failed edits don't reset")))

(t/deftest test-loop-run-agent-turn-identical-write-trips
  ;; Repeating the identical edit itself still trips — the reset keeps each
  ;; writer's own signature.
  (let [events (atom [])
        calls (atom 0)
        executed (atom 0)
        agent (loop/make-agent-state
               :loop-guard-threshold 3
               :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! calls inc)
                      (when-let [on-tc (:on-tool-call opts)]
                        (on-tc {:id (str "e" @calls) :name "edit"
                                :arguments "{\"path\":\"f.clj\",\"edits\":[{\"oldText\":\"a\",\"newText\":\"b\"}]}"
                                :index 0}))
                      (when-let [on-done (:on-done opts)]
                        (on-done :tool-calls))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _] (swap! executed inc) {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 3 @calls) "the guard gave up on the 3rd identical edit")
    (t/is (= 2 @executed) "only the first two edits executed")
    (t/is (some #(= :loop-guard (:type %)) @events)
          ":loop-guard event emitted")))

;; ─── Tool hooks (before/after tool-call) ─────────────────────────────────

(defn- stub-llm-tool-then-text
  "send-message stub: first call streams one tool call, subsequent calls a plain text reply."
  [call-count]
  (fn [opts]
    (future
      (if (= 1 (swap! call-count inc))
        (do (when-let [on-tc (:on-tool-call opts)]
              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
            (when-let [on-done (:on-done opts)]
              (on-done :tool-calls)))
        (do (when-let [on-text (:on-text opts)]
              (on-text "ok"))
            (when-let [on-done (:on-done opts)]
              (on-done :stop))))
      :done)))

(t/deftest test-loop-before-tool-call-blocks
  (let [events (atom [])
        executed (atom false)
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:before-tool-call agent)
            (fn [_] {:block true :reason "Permission denied"}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] (reset! executed true)
                                       {:content "should not run" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (false? @executed) "blocked tool must not execute")
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "tc1" (:tool-call-id end)))
      (t/is (true? (:is-error end)) "blocked tool result is an error")
      (t/is (= "Permission denied" (:content (:result end)))))))

(t/deftest test-loop-before-tool-call-terminate
  (let [events (atom [])
        executed (atom false)
        llm-calls (atom 0)
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:before-tool-call agent)
            (fn [_] {:block true :reason "Policy" :terminate true}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (fn [opts]
                                     (future
                                       (swap! llm-calls inc)
                                       (when-let [on-tc (:on-tool-call opts)]
                                         (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                                       (when-let [on-done (:on-done opts)]
                                         (on-done :tool-calls))
                                       :done))
                  tools/execute-tool (fn [_ _ _] (reset! executed true)
                                       {:content "should not run" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (false? @executed) "blocked tool must not execute")
    (t/is (= 1 @llm-calls) "terminate stops the run — no follow-up LLM call")
    (t/is (= :idle @(:status agent)))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "tc1" (:tool-call-id end)))
      (t/is (= "Policy" (:content (:result end))) "blocked result still in transcript")
      (t/is (true? (:is-error end))))))

(t/deftest test-loop-before-tool-call-terminate-mixed-batch
  (let [events (atom [])
        executed (atom 0)
        llm-calls (atom 0)
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    ;; pi: terminate only when EVERY finalized call in the batch carries
    ;; the hint — a single non-terminated call keeps the run going
    (reset! (:before-tool-call agent)
            (fn [ctx]
              (when (= "tc1" (:tool-call-id ctx))
                {:block true :reason "Policy" :terminate true})))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (fn [opts]
                                     (future
                                       (if (= 1 (swap! llm-calls inc))
                                         (do (when-let [on-tc (:on-tool-call opts)]
                                               (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0})
                                               (on-tc {:id "tc2" :name "bash" :arguments "{}" :index 1}))
                                             (when-let [on-done (:on-done opts)]
                                               (on-done :tool-calls)))
                                         (do (when-let [on-text (:on-text opts)]
                                               (on-text "ok"))
                                             (when-let [on-done (:on-done opts)]
                                               (on-done :stop))))
                                       :done))
                  tools/execute-tool (fn [_ _ _] (swap! executed inc)
                                       {:content "ran" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 1 @executed) "non-blocked call in the batch executed")
    (t/is (= 2 @llm-calls) "mixed batch does not terminate — follow-up LLM call happened")
    (t/is (= :idle @(:status agent)))
    (t/is (some #(and (= :tool-execution-end (:type %))
                      (= "tc1" (:tool-call-id %))
                      (true? (:is-error %)))
                @events)
          "blocked call still reports its error result")))

(t/deftest test-loop-before-tool-call-hook-throws
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:before-tool-call agent)
            (fn [_] (throw (ex-info "hook boom" {}))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (true? (:is-error end)))
      (t/is (.contains (:content (:result end)) "before-tool-call hook error")))))

(t/deftest test-loop-after-tool-call-rewrites
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:after-tool-call agent)
            (fn [{:keys [result]}]
              {:content (str (:content result) " [sanitized]")}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "secret-key=abc" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (= "secret-key=abc [sanitized]" (:content (:result end))))
      (t/is (false? (:is-error end)) "rewrite preserves is-error unless overridden"))))

(t/deftest test-loop-after-tool-call-sets-error
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:after-tool-call agent)
            (fn [{:keys [result]}]
              {:content (:content result) :is-error true}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (true? (:is-error end)) "after hook can mark a result as error"))))

(t/deftest test-loop-after-tool-call-hook-throws
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:after-tool-call agent)
            (fn [_] (throw (ex-info "hook boom" {}))))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [end (first (filter #(= :tool-execution-end (:type %)) @events))]
      (t/is (true? (:is-error end)))
      (t/is (.contains (:content (:result end)) "after-tool-call hook error")))))

;; ─── Auto-retry ───────────────────────────────────────────────────────────

(t/deftest test-loop-auto-retry-succeeds
  (let [events (atom [])
        call-count (atom 0)
        agent (loop/make-agent-state
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
    ;; The retried stream's text is the final assistant message
    (t/is (some #(and (= :message-end (:type %))
                      (= "recovered" (get-in % [:message :content 0 :text])))
                @events))))

(t/deftest test-loop-auto-retry-exhausted
  (let [events (atom [])
        errors (atom [])
        agent (loop/make-agent-state
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
    ;; Every failed attempt is recorded — one :message-end per LLM call that
    ;; errored (pi: message_end persists each stopReason-error partial, even
    ;; when no retry budget remains) — and none enter the live context
    (let [err-ends (filter #(and (= :message-end (:type %))
                                 (= :error (:stop-reason (:message %))))
                           @events)]
      (t/is (= 2 (count err-ends)) "initial failure + exhausted retry")
      (t/is (every? #(= "503 service unavailable" (:error-message %))
                    (map :message err-ends)))
      (t/is (empty? (filter #(= :assistant (:role %)) (loop/get-context agent)))
            "no assistant message entered the context"))))

(t/deftest test-loop-retry-records-failed-attempt
  ;; pi parity: a transient failure's partial is persisted to the session
  ;; (message_end → appendMessage) but removed from agent state before the
  ;; retried call (_prepareRetry) — history keeps it, the request doesn't.
  (let [dir (str "target/test-loop-failed-attempt-" (System/currentTimeMillis))
        events (atom [])
        call-count (atom 0)
        sess (session/create-session dir)
        agent (loop/make-agent-state
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
    ;; …but the live context only carries the user prompt and the recovery
    (let [ctx (loop/get-context agent)]
      (t/is (empty? (filter #(= :error (:stop-reason %)) ctx))
            "errored attempt excluded from the live context")
      (t/is (some #(and (= :assistant (:role %))
                        (= "recovered" (get-in % [:content 0 :text])))
                  ctx)))))

(t/deftest ^:slow test-loop-cancel-records-aborted-attempt
  ;; pi parity: an abort mid-stream finalizes the partial as a
  ;; stopReason-aborted assistant message persisted to the session (never
  ;; the live context) — history keeps what the model managed to say.
  (let [dir (str "target/test-loop-aborted-attempt-" (System/currentTimeMillis))
        events (atom [])
        sess (session/create-session dir)
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :session sess)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  ;; Streams a little, then hangs forever: on-done/on-error
                  ;; never fire — cancel-turn must settle the call
                  (fn [opts]
                    (future
                      (when-let [ot (:on-text opts)] (ot "half a thought"))
                      (Thread/sleep 10000)
                      :never))]
      (let [fut (loop/run-agent-turn agent
                                     {:message "hi"
                                      :on-done (fn [_])
                                      :on-error (fn [_])})]
        ;; Wait until the LLM call is in flight and the partial landed
        (loop []
          (when (nil? @(:active-call agent))
            (Thread/sleep 20)
            (recur)))
        (Thread/sleep 100)
        (loop/cancel-turn agent)
        (deref fut 15000 :timeout)
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
        (t/is (empty? (filterv #(= :assistant (:role %)) (loop/get-context agent)))
              "aborted attempt excluded from the live context")
        (t/is (= :idle @(:status agent)))
        (try (fs/delete-tree dir) (catch Exception _ nil))))))

(t/deftest test-loop-thinking-signature-captured
  ;; pi parity: the assistant message records provider/model provenance plus
  ;; the opaque reasoning signature (anthropic signature_delta / gemini
  ;; thoughtSignature) — converters replay it only for same-provider-same-model
  ;; messages and degrade everything else to plain text.
  (let [agent (loop/make-agent-state :provider :anthropic :model "claude-test")]
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
    (let [a (last (loop/get-context agent))]
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
        agent (loop/make-agent-state)]
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
    (t/is (= [:user :assistant] (mapv :role (loop/get-context agent)))
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
        agent (loop/make-agent-state :max-retries 0)]
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
    (t/is (empty? (filterv #(= :assistant (:role %)) (loop/get-context agent)))
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
      (let [agent (loop/make-agent-state
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
        agent (loop/make-agent-state
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
        agent (loop/make-agent-state
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

(t/deftest ^:slow test-loop-retry-cancel-during-backoff
  (let [events (atom [])
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e))
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
        (Thread/sleep 200)
        (loop/cancel-turn agent)
        @fut))
    (let [ends (filter #(= :auto-retry-end (:type %)) @events)]
      (t/is (= 1 (count ends)))
      (t/is (false? (:success (first ends))))
      (t/is (= "Retry cancelled" (:final-error (first ends)))))
    (t/is (= :idle @(:status agent)) "run settles idle after cancel during backoff")))

(t/deftest ^:slow test-loop-retry-resets-count-on-success-then-error
  (let [events (atom [])
        call-count (atom 0)
        agent (loop/make-agent-state
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
        (loop/follow-up! agent "second")
        @fut))
    (let [ends (filter #(= :auto-retry-end (:type %)) @events)]
      ;; First turn: retry succeeded (1 end, success). Second turn: 503 is
      ;; retryable but budget is fresh — one more retry → exhausted.
      (t/is (= 2 (count ends)))
      (t/is (true? (:success (first ends))))
      (t/is (false? (:success (second ends)))))))

;; ─── Phase 3: model management ───────────────────────────────────────────

(t/deftest test-loop-make-agent-state-phase3-opts
  (let [agent (loop/make-agent-state)]
    (t/is (instance? clojure.lang.Atom (:system-prompt-override agent)))
    (t/is (instance? clojure.lang.Atom (:transform-context agent)))
    (t/is (instance? clojure.lang.Atom (:prepare-next-turn agent)))
    (t/is (instance? clojure.lang.Atom (:should-stop-after-turn agent)))
    (t/is (instance? clojure.lang.Atom (:get-api-key agent)))
    (t/is (= [] @(:scoped-models agent)))
    (t/is (false? @(:overflow-recovered agent)))
    (t/is (nil? (:compact-token-threshold agent)))
    (t/is (nil? (:context-window @(:cfg agent))))
    (t/is (= 16384 (:compact-reserve-tokens agent)) "pi default reserveTokens")
    (t/is (= 3 (:max-retries @(:cfg agent))) "pi default maxRetries")))

(defn- make-test-provider
  "A minimal test provider with models IDS (pi-shaped Model records)."
  [ids]
  (models/map->Provider
   {:id :test-prov :name "Test"
    :api-types #{:openai-completions}
    :models (mapv (fn [id]
                    (models/map->Model {:id id :name (str "Model " id)
                                        :provider :test-prov
                                        :api :openai-completions
                                        :base-url "https://test"
                                        :reasoning false :input [:text]
                                        :cost {:input 0 :output 0 :cache-read 0
                                               :cache-write 0}
                                        :context-window 1000 :max-tokens 100}))
                  ids)
    :env-vars [] :default-model nil}))

(defn- with-test-provider
  "Register the test provider + auth and restore the registry after."
  [f]
  (let [saved (models/get-providers)]
    (models/clear-providers!)
    (models/register-provider! (make-test-provider ["a" "b" "c"]))
    (try
      (with-redefs [auth/auth-atom (atom {:test-prov {:key "sk"}})]
        (f))
      (finally
        (models/clear-providers!)
        (doseq [p saved] (models/register-provider! p))))))

(t/deftest test-loop-cycle-model
  (with-test-provider
    (fn []
      (let [events (atom [])
            agent (loop/make-agent-state
                   :provider :test-prov :model "a"
                   :scoped-models ["test-prov/a" "test-prov/b" "test-prov/c"]
                   :on-event (fn [e] (swap! events conj e)))]
        (t/is (= "b" (loop/cycle-model! agent 1)))
        (t/is (= "c" (loop/cycle-model! agent 1)))
        (t/is (= "a" (loop/cycle-model! agent 1)) "wraps around at the end")
        (t/is (= "c" (loop/cycle-model! agent -1)) "cycles backward")
        (t/is (= "b" (loop/cycle-model! agent -1)))
        (let [ev (last @events)]
          (t/is (= :model-select (:type ev)) "cycle emits :model-select")
          (t/is (= :cycle (:source ev)))
          (t/is (= "b" (:model ev)))
          (t/is (= "c" (:previous-model ev))))))))

(t/deftest test-loop-cycle-model-falls-back-to-available
  ;; pi: no session scoped models → cycle over all available models
  (with-test-provider
    (fn []
      (let [agent (loop/make-agent-state :provider :test-prov :model "b")]
        (t/is (= "c" (loop/cycle-model! agent 1)))
        (t/is (= "a" (loop/cycle-model! agent 1)))))))

(t/deftest test-loop-cycle-model-switches-provider
  ;; a scoped entry carries its provider — cycling can switch providers
  (let [saved (models/get-providers)
        prov2 (models/map->Provider
               {:id :test-prov2 :name "Test2"
                :api-types #{:openai-completions}
                :models [(models/map->Model {:id "x" :name "X" :provider :test-prov2
                                             :api :openai-completions :base-url "https://t"
                                             :reasoning false :input [:text]
                                             :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                                             :context-window 1000 :max-tokens 100})]
                :env-vars [] :default-model nil})]
    (models/clear-providers!)
    (models/register-provider! (make-test-provider ["a" "b"]))
    (models/register-provider! prov2)
    (try
      (with-redefs [auth/auth-atom (atom {:test-prov {:key "sk"}
                                          :test-prov2 {:key "sk2"}})]
        (let [agent (loop/make-agent-state
                     :provider :test-prov :model "a"
                     :scoped-models ["test-prov/a" "test-prov2/x"])]
          (t/is (= "x" (loop/cycle-model! agent 1)))
          (t/is (= :test-prov2 @(:provider agent)))
          (t/is (= "a" (loop/cycle-model! agent 1)))
          (t/is (= :test-prov @(:provider agent)))))
      (finally
        (models/clear-providers!)
        (doseq [p saved] (models/register-provider! p))))))

(t/deftest test-loop-cycle-model-no-models
  (let [agent (loop/make-agent-state :model "a")]
    (t/is (nil? (loop/cycle-model! agent 1)) "no scoped models and no available models → nil")))

(t/deftest test-loop-init-scoped-models
  ;; pi: parsed.models ?? settingsManager.getEnabledModels — CLI/config
  ;; :models patterns win, else settings :enabled-models seed the session
  ;; scoped list at startup (the /model all/scoped toggle and Ctrl+P cycling
  ;; depend on it). Unresolved patterns are skipped with a stderr warning.
  (with-test-provider
    (fn []
      ;; config :models wins over :enabled-models (pi: parsed.models ?? …)
      (let [agent (loop/init-scoped-models!
                   (loop/make-agent-state)
                   {:models ["test-prov/b"] :enabled-models ["test-prov/a"]})]
        (t/is (= ["test-prov/b"] @(:scoped-models agent))))
      ;; :enabled-models falls back when :models is unset
      (let [agent (loop/init-scoped-models!
                   (loop/make-agent-state)
                   {:enabled-models ["test-prov/a" "test-prov/c"]})]
        (t/is (= ["test-prov/a" "test-prov/c"] @(:scoped-models agent))))
      ;; no patterns → list stays empty (no scoping)
      (let [agent (loop/init-scoped-models!
                   (loop/make-agent-state)
                   {:enabled-models nil})]
        (t/is (= [] @(:scoped-models agent))))
      ;; unresolved patterns are skipped with a stderr warning — suppress it
      ;; (hosts without per-var output capture, e.g. jolt, would leak it)
      (let [agent (binding [*err* (java.io.StringWriter.)]
                    (loop/init-scoped-models!
                     (loop/make-agent-state)
                     {:enabled-models ["test-prov/a" "ghost/model"]}))]
        (t/is (= ["test-prov/a"] @(:scoped-models agent)))))))

(defn- thinking-model
  "Reasoning model supporting exactly the non-off LEVELS (pi
   getSupportedThinkingLevels): levels absent from the set are pinned to nil
   in the thinking-level-map; :xhigh/:max need a non-nil entry to be
   supported."
  [id levels]
  (let [supported (set levels)]
    (models/map->Model
     {:id id :name (str "Model " id) :provider :test-prov
      :api :openai-completions :base-url "https://test"
      :reasoning true :input [:text]
      :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
      :context-window 1000 :max-tokens 100
      :thinking-level-map
      (into {}
            (concat
             ;; exclude non-:xhigh/:max levels not in the set
             (for [l [:minimal :low :medium :high]
                   :when (not (contains? supported l))]
               [l nil])
             ;; :xhigh/:max require a non-nil map entry to be enabled
             (for [l [:xhigh :max]
                   :when (contains? supported l)]
               [l (name l)])))})))

(t/deftest test-loop-switch-thinking-level
  ;; kmet rank rule (deliberately not pi's keep-with-clamp): the current
  ;; level's position among the old model's levels maps to the same position
  ;; on the new model, clamped to the new model's level count.
  (let [a (thinking-model "a" [:low :medium :high])     ;; 3 levels
        b (thinking-model "b" [:low :medium :high :max]) ;; 4 levels
        c (thinking-model "c" [:low :high])             ;; 2 levels
        d (thinking-model "d" [:max])]                  ;; 1 level
    (t/is (= :max (loop/switch-thinking-level a b :high nil))
          "old highest → new highest")
    (t/is (= :high (loop/switch-thinking-level a b :medium nil))
          "old second-highest → new second-highest")
    (t/is (= :medium (loop/switch-thinking-level b a :high nil))
          "old second-highest (4 levels) → new second-highest (3 levels)")
    (t/is (= :low (loop/switch-thinking-level b c :medium nil))
          "rank clamps to the new model's level count (3rd of 4 → last of 2)")
    (t/is (= :max (loop/switch-thinking-level b d :medium nil))
          "rank clamps to the single available level")
    (t/is (= :max (loop/switch-thinking-level a b :off nil))
          ":off jumps to the reasoning model's highest (pi parity)")
    (t/is (= :off (loop/switch-thinking-level a (models/map->Model
                                                 {:id "n" :reasoning false})
                                              :off nil))
          "non-reasoning new model keeps :off")
    (t/is (= :high
             (loop/switch-thinking-level
              a
              (models/map->Model {:id "no-off" :provider :test-prov
                                  :reasoning true :api :openai-completions
                                  :base-url "https://test"
                                  :cost {:input 0 :output 0 :cache-read 0
                                         :cache-write 0}
                                  :context-window 1000 :max-tokens 100
                                  :thinking-level-map {:off nil}})
              :off nil))
          "a model that can't disable thinking gets its highest")
    (t/is (= :low (loop/switch-thinking-level a b :medium :low))
          "explicit level wins (clamped)")
    (t/is (= :off (loop/switch-thinking-level a b :off :off))
          "explicit :off wins when given")))

(t/deftest test-loop-retry-setters
  (let [agent (loop/make-agent-state)]
    (t/is (= 3 (:max-retries @(:cfg agent))))
    (t/is (= 2000 (:base-delay-ms @(:cfg agent))))
    (swap! (:cfg agent) assoc :max-retries 0)
    (swap! (:cfg agent) assoc :base-delay-ms 500)
    (t/is (= 0 (:max-retries @(:cfg agent))) "0 disables auto-retry")
    (t/is (= 500 (:base-delay-ms @(:cfg agent))))))

(t/deftest test-loop-set-model-emits-model-select
  (let [events (atom [])
        agent (loop/make-agent-state :model "a" :on-event (fn [e] (swap! events conj e)))]
    (loop/set-model! agent "b")
    (let [ev (last @events)]
      (t/is (= :model-select (:type ev)))
      (t/is (= "b" (:model ev)))
      (t/is (= "a" (:previous-model ev)))
      (t/is (= :set (:source ev))))
    ;; setting the same model is a no-op (no event)
    (loop/set-model! agent "b")
    (t/is (= 1 (count (filter #(= :model-select (:type %)) @events))))))

;; ─── Phase 3: transform-context ──────────────────────────────────────────

(t/deftest test-loop-transform-context
  (let [seen (atom nil)
        agent (loop/make-agent-state)]
    (reset! (:transform-context agent)
            (fn [messages]
              (reset! seen messages)
              (conj messages {:role :user :content [{:type :text :text "injected"}]})))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (let [msgs (:messages opts)]
                        (t/is (= :system (:role (first msgs))) "system prompt prepended after transform")
                        (t/is (some #(= "injected" (get-in % [:content 0 :text])) (rest msgs))
                              "transformed message visible to the LLM"))
                      (when-let [on-text (:on-text opts)] (on-text "ok"))
                      (when-let [on-done (:on-done opts)] (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
    (t/is (some #(= "hi" (get-in % [:content 0 :text])) @seen)
          "hook receives the original conversation (no system prompt)")
    (t/is (not-any? #(= :system (:role %)) @seen)
          "transform-context sees only conversation messages")))

;; ─── Phase 3: system prompt override ─────────────────────────────────────

(t/deftest test-loop-system-prompt-override
  (let [override-msgs (atom [])
        holder (atom nil)
        ;; The extension sets the override on :agent-start (pi: before_agent_start)
        agent (loop/make-agent-state
               :system "base prompt"
               :on-event (fn [evt]
                           (when (= :agent-start (:type evt))
                             (reset! (:system-prompt-override @holder) "override prompt"))))]
    (reset! holder agent)
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! override-msgs conj (get-in opts [:messages 0 :content 0 :text]))
                      (when-let [on-text (:on-text opts)] (on-text "ok"))
                      (when-let [on-done (:on-done opts)] (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
    (t/is (= ["override prompt"] @override-msgs)
          "override preferred over base system prompt")
    (t/is (= "override prompt" @(:system-prompt-override agent))
          "override persists for the duration of the run")
    ;; Next run resets the override at start (pi: prompt() resets per run)
    (reset! (:system-prompt-override agent) "override prompt")
    (reset! (:system-prompt-override agent) nil)
    (t/is (nil? @(:system-prompt-override agent)))))

;; ─── Phase 3: prepareNextTurn / shouldStopAfterTurn ──────────────────────

(t/deftest test-loop-prepare-next-turn-updates-state
  (let [agent (loop/make-agent-state :model "model-a" :thinking :off)]
    (reset! (:prepare-next-turn agent)
            (fn [_] {:model "model-b" :thinking :high :system-prompt-override "custom"}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= "model-b" @(:model agent)) "prepare-next-turn swaps the model")
    (t/is (= :high @(:thinking agent)) "prepare-next-turn updates thinking level")
    (t/is (= "custom" @(:system-prompt-override agent))
          "prepare-next-turn sets the system prompt override for later turns")))

(t/deftest ^:slow test-loop-should-stop-after-turn
  (let [calls (atom 0)
        agent (loop/make-agent-state)]
    (reset! (:should-stop-after-turn agent) (fn [_] true))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! calls inc)
                      (when-let [on-tc (:on-tool-call opts)]
                        (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                      (when-let [on-done (:on-done opts)]
                        (on-done :tool-calls))
                      :done))]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 1 @calls) "loop stops after the first turn")
    (t/is (= :idle @(:status agent)))))

;; ─── Phase 3: parallel tool execution ────────────────────────────────────

(defn- stub-llm-two-tool-calls-then-text
  "send-message stub: first call streams two tool calls, subsequent calls a plain reply."
  [call-count]
  (fn [opts]
    (future
      (if (= 1 (swap! call-count inc))
        (do (when-let [on-tc (:on-tool-call opts)]
              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0})
              (on-tc {:id "tc2" :name "bash" :arguments "{}" :index 1}))
            (when-let [on-done (:on-done opts)]
              (on-done :tool-calls)))
        (do (when-let [on-text (:on-text opts)]
              (on-text "ok"))
            (when-let [on-done (:on-done opts)]
              (on-done :stop))))
      :done)))

(t/deftest ^:slow test-loop-parallel-tool-execution
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))
        ;; Wall-clock concurrency bounds are load-sensitive (a loaded host
        ;; stretches two 400ms sleeps past any fixed threshold) — assert the
        ;; actual property instead: the two tool executions overlap.
        intervals (atom [])]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-two-tool-calls-then-text (atom 0))
                  tools/execute-tool
                  (fn [_ _ _]
                    (let [s (System/currentTimeMillis)]
                      (Thread/sleep 400)
                      (swap! intervals conj [s (System/currentTimeMillis)])
                      {:content "ok" :is-error false}))]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 2 (count @intervals)))
    (t/is (let [[[s1 e1] [s2 e2]] @intervals]
            (< (max s1 s2) (min e1 e2)))
          "the two 400ms tools overlap — executed concurrently, not sequentially")
    (t/is (= 2 (count (filter #(= :tool-execution-start (:type %)) @events))))
    (t/is (= 2 (count (filter #(= :tool-execution-end (:type %)) @events))))
    (let [te (first (filter #(= :turn-end (:type %)) @events))]
      (t/is (= 2 (count (:tool-results te))) "turn-end carries both results"))
    (t/is (= 2 (count (filter #(= :tool (:role %)) (loop/get-context agent))))
          "both tool results appended to context")))

(t/deftest ^:slow test-loop-sequential-tool-execution
  (let [seq-calls (atom 0)
        agent (loop/make-agent-state)]
    (tools/register-tool!
     (tools/make-tool :name "seq-tool" :label "Seq"
                      :description "sequential test tool"
                      :parameters {:x (tools/param :x :string "x")}
                      :execute (fn [_] {:content "seq" :is-error false})
                      :execution-mode :sequential))
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (if (= 1 (swap! seq-calls inc))
                          (do (when-let [on-tc (:on-tool-call opts)]
                                (on-tc {:id "t1" :name "seq-tool" :arguments "{}" :index 0})
                                (on-tc {:id "t2" :name "seq-tool" :arguments "{}" :index 1}))
                              (when-let [on-done (:on-done opts)]
                                (on-done :tool-calls)))
                          (do (when-let [on-text (:on-text opts)]
                                (on-text "ok"))
                              (when-let [on-done (:on-done opts)]
                                (on-done :stop))))
                        :done))
                    tools/execute-tool
                    (fn [_ _ _]
                      (Thread/sleep 400)
                      {:content "seq" :is-error false})]
        (let [start (System/currentTimeMillis)]
          @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])})
          (t/is (>= (- (System/currentTimeMillis) start) 700)
                "a :sequential tool forces the whole batch sequential (~800ms for 2×400ms)")))
      (finally
        (tools/unregister-tool! "seq-tool")))))

;; ─── Phase 3: compaction ─────────────────────────────────────────────────

(t/deftest test-loop-overflow-compact-retry
  (let [events (atom [])
        main-call-count (atom 0)
        dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (loop/make-agent-state
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
      (t/is (some #(and (= :message-end (:type %))
                        (= "recovered" (get-in % [:message :content 0 :text])))
                  @events)
            "retried call succeeds after compaction")
      (t/is (some #(= :context-replaced (:type %)) @events)
            "overflow compaction mirrors the new context to the UI (pi: compaction_end re-renders)")
      (t/is (= :idle @(:status agent)) "run settles idle after the overflow recovery")
      (finally
        (fs/delete-tree dir)))))

;; ─── Phase 3: dynamic API key ────────────────────────────────────────────

(t/deftest test-loop-get-api-key-hook
  (let [agent (loop/make-agent-state)]
    (reset! (:get-api-key agent) (fn [_] "hook-key"))
    (with-redefs [cfg/get-api-key (fn [_] "cfg-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (t/is (= "hook-key" (:api-key opts))
                            "dynamic get-api-key hook preferred over cfg")
                      (when-let [on-text (:on-text opts)] (on-text "ok"))
                      (when-let [on-done (:on-done opts)] (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))))

(t/deftest test-loop-endpoint-from-model-registry
  ;; Phase 2: llm resolves the Model itself (registry is the unit of truth);
  ;; loop forwards provider/model plus only the agent-level overrides.
  ;; Providers without a catalog entry are unknown; overrides win over the
  ;; model.
  (models/load-catalogs!)
  (let [sent (atom nil)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (reset! sent (select-keys opts [:provider :model :api-type :base-url]))
                    (future
                      (when-let [on-text (:on-text opts)] (on-text "hi"))
                      (when-let [on-done (:on-done opts)] (on-done :stop))))]
      (t/testing "loop forwards provider + model; llm resolves the wire api"
        @(loop/run-agent-turn (loop/make-agent-state :provider :opencode-go
                                                     :model "deepseek-v4-flash")
                              {:message "hi" :on-error (fn [_])})
        (t/is (= {:provider :opencode-go :model "deepseek-v4-flash"
                  :api-type nil :base-url nil}
                 @sent)))
      (t/testing "agent-level overrides flow through to llm"
        @(loop/run-agent-turn (loop/make-agent-state :provider :opencode-go
                                                     :model "deepseek-v4-flash"
                                                     :api-type :anthropic-messages
                                                     :base-url "https://custom.example/v1/messages")
                              {:message "hi" :on-error (fn [_])})
        (t/is (= {:provider :opencode-go :model "deepseek-v4-flash"
                  :api-type :anthropic-messages
                  :base-url "https://custom.example/v1/messages"}
                 @sent))))))

(defn- with-summarization-stub
  "Run f with the LLM stubbed to complete one compaction summarization call
   (only the compaction path calls the LLM with an empty tool list). Delivers
   usage like a real provider so the compaction entry records it (pi:
   CompactionEntry.usage)."
  [f]
  (with-redefs [cfg/get-api-key (fn [_] "test-key")
                llm/send-message
                (fn [opts]
                  (future
                    (t/is (empty? (:tools opts)) "summarization call carries no tools")
                    (t/is (= :none (:cache-retention opts))
                          "compaction summaries disable prompt caching (pi)")
                    (when-let [on-text (:on-text opts)]
                      (on-text "summary of the old conversation"))
                    (when-let [on-usage (:on-usage opts)]
                      (on-usage {:prompt_tokens 100 :completion_tokens 10
                                 :prompt_tokens_details {:cached_tokens 20}
                                 :cost {:total 0.001}}))
                    (when-let [on-done (:on-done opts)]
                      (on-done :stop))
                    :done))]
    (f)))

(t/deftest test-loop-token-threshold-compaction
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        events (atom [])
        sess (session/create-session (str dir))
        agent (loop/make-agent-state
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
                     (with-summarization-stub #(loop/maybe-compact! agent))))
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
              agent (loop/make-agent-state
                     :session sess
                     :context-window 10000
                     :compact-reserve-tokens 1000
                     :keep-recent-tokens 40)]
          (dotimes [i 50]
            (session/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
          (session/append-entry sess {:role :assistant :content [{:type :text :text "ok"}]
                                      :usage {:prompt_tokens 2000 :completion_tokens 50
                                              :prompt_tokens_details {:cached_tokens 500}}})
          (t/is (false? (loop/maybe-compact! agent))
                "measured 2050 tokens (1500 input + 50 output + 500 cached) vs window-reserve 9000 — no compaction")))
      (t/testing "measured usage within reserve of the window → compaction"
        (let [sess (session/create-session (str dir))
              agent (loop/make-agent-state
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
                         (with-summarization-stub #(loop/maybe-compact! agent))))
                "measured 20050 tokens >= window-reserve 9000 → compaction")))
      (t/testing "no window and no explicit thresholds → never compacts"
        (let [sess (session/create-session (str dir))
              agent (loop/make-agent-state
                     :session sess
                     :keep-recent-tokens 40)]
          (dotimes [i 50]
            (session/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
          (t/is (false? (loop/maybe-compact! agent))
                "no count trigger by default (pi: no entry-count compaction)")))
      (t/testing "degenerate windows never trigger the window check"
        (let [sess (session/create-session (str dir))
              agent (loop/make-agent-state
                     :session sess
                     :context-window 0
                     :keep-recent-tokens 40)]
          (dotimes [i 50]
            (session/append-entry sess {:role :user :content [{:type :text :text (str "msg " i)}]}))
          (session/append-entry sess {:role :assistant :content [{:type :text :text "ok"}]
                                      :usage {:prompt_tokens 20000 :completion_tokens 50}})
          (t/is (false? (loop/maybe-compact! agent))
                "window 0 → no window check (pi: contextWindow <= 0 → undefined)")))
      (t/testing "stale kept-tail usage does not re-trigger right after a compaction"
        (let [sess (session/create-session (str dir))
              agent (loop/make-agent-state
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
                         (with-summarization-stub #(loop/maybe-compact! agent))))
                "measured usage near the window compacts once")
          (t/is (false? (loop/maybe-compact! agent))
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
            agent (loop/make-agent-state
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
                       (with-summarization-stub #(loop/maybe-compact! agent))))
              "token threshold triggers the first compaction")
        (t/is (false? (loop/maybe-compact! agent))
              "context tokens dropped below the token threshold — no re-compaction"))
      (finally (fs/delete-tree dir)))))

(t/deftest test-loop-compact-no-op-reports-false
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (loop/make-agent-state :session sess)]
    (try
      (doseq [i (range 3)]
        (let [m {:role :user :content [{:type :text :text (str "msg " i)}]}]
          (swap! (:messages agent) conj m)
          (session/append-entry sess m)))
      (t/is (false? (loop/maybe-compact! agent))
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
          agent (loop/make-agent-state
                 :session sess
                 :keep-recent-tokens 40
                 :on-event (fn [e] (swap! events conj e)))
          dereg (event-bus/on-event
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
          (t/is (false? (loop/compact-context! agent nil :threshold))
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
          (t/is (false? (:result end)) "compaction-end carries result false"))
        (t/is (= 6 (count @(:entries sess))) "session untouched")
        (t/is (= 6 (count @(:messages agent))) "in-memory context untouched")
        (finally
          (dereg)
          (fs/delete-tree dir))))))

(t/deftest test-loop-get-api-key-resolved-per-call
  (let [keys-seen (atom [])
        llm-keys (atom [])
        calls (atom 0)
        agent (loop/make-agent-state)]
    (reset! (:get-api-key agent)
            (fn [_] (swap! keys-seen conj (str "key-" (count @keys-seen)))))
    (with-redefs [cfg/get-api-key (fn [_] "cfg-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! llm-keys conj (:api-key opts))
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "ok"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 2 (count @llm-keys)) "two LLM calls in the run")
    (t/is (apply not= @llm-keys)
          "each LLM call gets a freshly resolved key")
    (t/is (>= (count @keys-seen) 3)
          "hook also resolves at run start for the nil check")))

(t/deftest test-loop-prepare-next-turn-context-replaces-session
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (loop/make-agent-state :session sess)]
    (try
      (reset! (:prepare-next-turn agent)
              (fn [_] {:context [{:role :user :content [{:type :text :text "replacement"}]}]}))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (stub-llm-tool-then-text (atom 0))
                    tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
        @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
      (t/is (= [{:role :user :content [{:type :text :text "replacement"}]}]
               (loop/get-context agent))
            "prepare-next-turn :context replaces the conversation")
      (t/is (= 1 (count @(:entries sess)))
            "session rebuilt to match the replaced context")
      (t/is (= "replacement" (get-in (first @(:entries sess)) [:content 0 :text]))
            "session entry mirrors the replacement message")
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-context-replaced-event
  (let [events (atom [])
        agent (loop/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:prepare-next-turn agent)
            (fn [_] {:context [{:role :user :content [{:type :text :text "replacement"}]}]}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (stub-llm-tool-then-text (atom 0))
                  tools/execute-tool (fn [_ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (let [evs (filter #(= :context-replaced (:type %)) @events)]
      (t/is (seq evs) ":context-replaced emitted on context replacement")
      (t/is (= "replacement" (get-in (last evs) [:messages 0 :content 0 :text]))
            "event carries the new conversation"))))

(t/deftest ^:slow test-loop-compaction-cancelled
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        events (atom [])
        agent (loop/make-agent-state
               :session sess
               :keep-recent-tokens 40
               :on-event (fn [e] (swap! events conj e)))]
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
        (let [fut (future (loop/compact-context! agent nil :threshold))]
          (Thread/sleep 300) ;; let summarization "start"
          (loop/cancel-turn agent)
          (t/is (= :aborted @fut) "compact-context! reports :aborted on cancel")))
      (t/is (some #(and (= :compaction-start (:type %)) (= :threshold (:reason %))) @events)
            "compaction-start emitted with the threshold reason")
      (t/is (some #(and (= :compaction-end (:type %)) (:aborted %)) @events)
            "compaction-end carries :aborted")
      (t/is (= 6 (count @(:entries sess))) "session untouched by aborted compaction")
      (t/is (= 6 (count @(:messages agent))) "in-memory context untouched")
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-manual-compaction-clears-stale-cancel-signal
  ;; Regression (/compact after Escape): Escape leaves the run's cancel
  ;; signal set until the next run starts, which made every manual compaction
  ;; abort immediately with "compaction cancelled". A manual compaction is a
  ;; fresh operation (pi: session.compact aborts the current operation, then
  ;; creates a new AbortController), so it clears the stale signal — the auto
  ;; paths keep it, an Escape at run start must still abort them.
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (loop/make-agent-state :session sess :keep-recent-tokens 40)]
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
      (with-summarization-stub
        (fn []
          (binding [*err* (java.io.StringWriter.)]
            (t/is (= :aborted (loop/compact-context! agent nil :threshold))
                  "the auto path still aborts on the cancel signal")
            (t/is (true? @(:signal agent)) "the auto path keeps the signal")
            (t/is (true? (loop/compact-context! agent nil :manual))
                  "a manual compaction starts with a fresh abort state")
            (t/is (false? @(:signal agent)) "the stale cancel signal is cleared"))))
      (t/is (some #(= :compaction (:role %)) @(:entries sess))
            "the manual compaction appended its compaction entry")
      (finally
        (fs/delete-tree dir)))))

(t/deftest ^:slow test-loop-compaction-refuses-when-active
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        agent (loop/make-agent-state :session sess
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
                    llm/send-message (fn [_] (promise))]
        (let [fut (future (loop/compact-context! agent))]
          ;; Wait until the first compaction is actually in flight — the
          ;; second call below must see :compacting? true, or it would start
          ;; its own compaction and block 120s on the never-resolving
          ;; summarize promise (llm/send-message is redefined to a bare
          ;; promise; only the cancel signal ends it).
          (loop []
            (when-not @(:compacting? agent)
              (Thread/sleep 20)
              (recur)))
          (t/is (false? (loop/compact-context! agent))
                "second compaction refused while one is in flight")
          (loop/cancel-turn agent)
          (t/is (= :aborted @fut))))
      (finally
        (fs/delete-tree dir)))))

;; ─── Bash result recording (pi: recordBashResult + _flushPendingBashMessages)

(t/deftest test-loop-add-bash-result-when-idle
  (let [dir (str (fs/create-temp-dir {:dir (System/getProperty "user.home")}))
        sess (session/create-session (str (fs/path dir "s")))
        agent (loop/make-agent-state :session sess)]
    (try
      (loop/add-bash-result! agent "git st" {:output "clean\n" :exit-code 0} false)
      (t/is (= 1 (count (loop/get-context agent))))
      (t/is (= :bash (:role (first (loop/get-context agent)))))
      (t/is (= 1 (count @(:entries sess))))
      (t/is (empty? @(:pending-bash agent)))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-add-bash-result-queued-while-streaming
  (let [dir (str (fs/create-temp-dir {:dir (System/getProperty "user.home")}))
        sess (session/create-session (str (fs/path dir "s")))
        agent (loop/make-agent-state :session sess)]
    (try
      (reset! (:status agent) :thinking)
      (loop/add-bash-result! agent "git st" {:output "clean\n" :exit-code 0} false)
      (t/is (empty? (loop/get-context agent)) "streaming: not added to context yet")
      (t/is (empty? @(:entries sess)) "streaming: not appended to session yet")
      (t/is (= 1 (count @(:pending-bash agent))) "streaming: queued for later flush")
      (loop/flush-pending-bash-messages! agent)
      (t/is (= 1 (count (loop/get-context agent))))
      (t/is (= 1 (count @(:entries sess))))
      (t/is (empty? @(:pending-bash agent)))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-add-bash-result-excluded
  (let [agent (loop/make-agent-state)]
    (loop/add-bash-result! agent "ls" {:output "x" :exit-code 0} true)
    (t/is (= true (:exclude-from-context? (first (loop/get-context agent)))))))

(t/deftest test-loop-add-bash-result-after-error
  ;; pi: isStreaming is false once a run has errored — the result lands
  ;; immediately instead of queueing for a flush that never comes
  (let [agent (loop/make-agent-state)]
    (reset! (:status agent) :error)
    (loop/add-bash-result! agent "git st" {:output "clean\n" :exit-code 0} false)
    (t/is (= 1 (count (loop/get-context agent))))
    (t/is (empty? @(:pending-bash agent)))))

(t/deftest test-loop-interactive-chat-flow-with-steer-and-followup
  ;; End-to-end: the interactive UI mirrors the loop via :message-start
  ;; (:user → addMessageToChat, :assistant → new streaming component) and
  ;; streams text via :on-text. A steered message lands in the chat when the
  ;; loop consumes it (before the response to it); a follow-up lands after
  ;; the run settles. Tool-only turns finalize an empty assistant placeholder
  ;; (:tool-execution-start), matching pi.
  (let [ch (ui/make-chat-history
            :theme th/dark-theme)
        calls (atom 0)
        events (atom [])
        agent (loop/make-agent-state
               :on-event (fn [evt]
                           (swap! events conj evt)
                           (case (:type evt)
                             :message-start
                             (case (:role (:message evt))
                               :user (ui/chat-history-add-message!
                                      ch (:message evt))
                               :assistant (do (ui/chat-history-finalize-streaming! ch)
                                              (ui/chat-history-finalize-thinking! ch)
                                              (ui/chat-history-start-streaming! ch))
                               nil)
                             :tool-execution-start
                             (let [msg {:role :tool :name (:tool-name evt) :args {} :content "" :is-error false}]
                               (ui/chat-history-finalize-streaming! ch)
                               (ui/chat-history-add-message! ch msg))
                             nil)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "resp"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  tools/execute-tool
                  (fn [_ _ _]
                    (loop/steer! agent "steered")
                    (loop/follow-up! agent "followup")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "start"
                             :on-text #(ui/chat-history-append-streaming-text! ch %)
                             :on-done (fn [_])
                             :on-error (fn [_])}))
    (ui/chat-history-finalize-streaming! ch)
    (let [chat (mapv (fn [m] {:role (:role m) :content (:content m) :streaming? (:streaming? m)})
                     @(:messages-atom ch))
          text (fn [m] (if (string? (:content m)) (:content m) (get-in m [:content 0 :text])))
          roles (mapv :role chat)
          texts (mapv text chat)]
      (t/is (= [:user :assistant :tool :user :assistant :user :assistant] roles)
            "prompt, tool-turn, tool, steered, response, follow-up, response")
      (t/is (= ["start" "" "" "steered" "resp" "followup" "resp"] texts)
            "steered/follow-up land at the right position; responses stream into place")
      (t/is (every? #(not (:streaming? %)) chat)
            "all streaming placeholders finalized by run end")
      (t/is (= ["start" "steered" "followup"]
               (mapv #(get-in % [:message :content 0 :text])
                     (filter #(and (= :message-start (:type %))
                                   (= :user (:role (:message %))))
                             @events)))
            "one :message-start :user per consumed message, in order"))))

;; ─── Active tools + thinking-level event (pi: setActiveTools / ───────────
;; ─── thinking_level_select) ──────────────────────────────────────────────

(t/deftest test-active-tools
  (t/testing "nil = all tools; set restricts; nil restores"
    (let [ag (loop/make-agent-state :provider :opencode-go :model "deepseek-v4-flash")]
      (t/is (nil? @(:enabled-tools ag)))
      (loop/set-active-tools! ag ["read" "write"])
      (t/is (= #{"read" "write"} @(:enabled-tools ag)))
      (loop/set-active-tools! ag nil)
      (t/is (nil? @(:enabled-tools ag))))))

(t/deftest test-thinking-level-select-event
  (let [events (atom [])
        ag (loop/make-agent-state :provider :opencode-go :model "deepseek-v4-flash"
                                  :thinking :off
                                  :on-event (fn [evt] (swap! events conj (:type evt))))]
    (loop/set-thinking-level! ag :high)
    (loop/set-thinking-level! ag :high) ;; no change → no event
    (t/is (= [:thinking-level-select] @events))))

;; ─── Pure phase helpers of run-agent-turn ─────────────────────────────────

(t/deftest test-normalize-llm-result
  (t/testing "provider :error stop-reason folds into :error (pi mapStopReason)"
    (let [out (#'loop/normalize-llm-result
               {:stop-reason :error :error-message "content filter"})]
      (t/is (= "content filter" (:error out)))))
  (t/testing "missing error-message gets a generic provider-stopped text"
    (let [out (#'loop/normalize-llm-result {:stop-reason :error})]
      (t/is (= "Provider stopped with: error" (:error out)))))
  (t/testing "normal results and the :timeout sentinel pass through untouched"
    (let [r {:stop-reason :end_turn :content [{:type :text :text "hi"}]}]
      (t/is (= r (#'loop/normalize-llm-result r))))
    (t/is (= :timeout (#'loop/normalize-llm-result :timeout)))))

(t/deftest test-retry-decision
  (t/testing "context overflow with session and no prior recovery compacts first"
    (t/is (= {:kind :overflow-recover}
             (#'loop/retry-decision
              {:err "prompt is too long" :retry-count 0 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered false :has-session true}))))
  (t/testing "overflow is compaction territory even with retries left"
    (t/is (= {:kind :overflow-recover}
             (#'loop/retry-decision
              {:err "prompt is too long" :retry-count 1 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered false :has-session true}))))
  (t/testing "second overflow (already recovered) falls through to classification"
    (t/is (= {:kind :terminal}
             (#'loop/retry-decision
              {:err "prompt is too long" :retry-count 0 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered true :has-session true}))
          "overflow errors are not retryable-error? matches"))
  (t/testing "retryable within budget backs off exponentially, same turn"
    (let [d (#'loop/retry-decision
             {:err "rate limited" :retry-count 1 :max-retries 3
              :base-delay-ms 2000 :overflow-recovered false :has-session true})]
      (t/is (= :backoff (:kind d)))
      (t/is (= 2 (:attempt d)))
      (t/is (= 4000 (:delay-ms d)) "2000 * 2^(2-1)")
      (t/is (= 3 (:max-attempts d)) "budget snapshot rides the decision")))
  (t/testing "exhausted budget or non-retryable errors are terminal"
    (t/is (= {:kind :terminal}
             (#'loop/retry-decision
              {:err "rate limited" :retry-count 3 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered false :has-session true})))
    (t/is (= {:kind :terminal}
             (#'loop/retry-decision
              {:err "quota exceeded for project" :retry-count 0 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered false :has-session true}))))
  (t/testing "overflow without a session cannot recover — terminal here"
    (t/is (= {:kind :terminal}
             (#'loop/retry-decision
              {:err "prompt is too long" :retry-count 0 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered false :has-session false})))))

;; ─── Total request deadline (pi: timeoutMs ?? httpIdleTimeoutMs) ─────────

(t/deftest test-loop-total-timeout-ms
  (t/testing "nil total falls back to the idle timeout (default)"
    (let [agent (loop/make-agent-state :http-idle-timeout-ms 300000)]
      (t/is (= 300000 (#'loop/llm-total-timeout-ms agent)))))
  (t/testing "explicit total wins over the idle-derived default"
    (let [agent (loop/make-agent-state :http-idle-timeout-ms 300000
                                       :http-total-timeout-ms 120000)]
      (t/is (= 120000 (#'loop/llm-total-timeout-ms agent)))))
  (t/testing "0 total falls back to idle (pi: timeoutMs ?? httpIdleTimeoutMs)"
    (let [agent (loop/make-agent-state :http-idle-timeout-ms 300000
                                       :http-total-timeout-ms 0)]
      (t/is (= 300000 (#'loop/llm-total-timeout-ms agent)))))
  (t/testing "no idle and no explicit total → disabled (MAX_VALUE)"
    (let [agent (loop/make-agent-state :http-idle-timeout-ms 0)]
      (t/is (= Integer/MAX_VALUE (#'loop/llm-total-timeout-ms agent))))))

(t/deftest test-loop-set-http-total-timeout-ms!
  (let [agent (loop/make-agent-state)]
    (loop/set-http-total-timeout-ms! agent 60000)
    (t/is (= 60000 (:http-total-timeout-ms @(:cfg agent))))
    (loop/set-http-total-timeout-ms! agent nil)
    (t/is (nil? (:http-total-timeout-ms @(:cfg agent)))
          "nil resets to the use-idle default")))

(t/deftest test-loop-call-llm-threads-total-timeout
  ;; call-llm must pass the total deadline to llm/send-message so the
  ;; transport (HttpRequest.timeout / curl --max-time) enforces it as the
  ;; whole-request wall-clock (pi: SDK timeoutMs ?? httpIdleTimeoutMs).
  (let [sent (atom nil)
        agent (loop/make-agent-state :http-idle-timeout-ms 300000
                                     :http-total-timeout-ms 120000)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (reset! sent opts)
                    (future
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop)))
                    :done)]
      @(loop/run-agent-turn agent {:message "hi" :on-done (fn [_])}))
    (t/is (= 120000 (:total-timeout-ms @sent))
          "the explicit total deadline reaches the transport")
    (t/is (= 300000 (:idle-timeout-ms @sent))
          "the idle timeout stays the separate per-byte deadline")))

(t/deftest test-loop-total-deadline-timeout-retries-then-surfaces
  ;; pi parity: a total-deadline timeout (the deref :timeout sentinel, which
  ;; fires when the transport deadline was disabled/didn't deliver) is a
  ;; RETRYABLE error — never a silent hard abort. It retries with backoff,
  ;; and after exhaustion surfaces via on-error and ends the run (status
  ;; :error), so the user sees the timeout instead of a mid-thought hang.
  (let [events (atom [])
        errors (atom [])
        agent (loop/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 1
               :base-delay-ms 1
               :http-idle-timeout-ms 0
               :http-total-timeout-ms 10)]
    ;; A tiny explicit total deadline makes the transport's own
    ;; HttpRequest.timeout fire quickly; but the fake send-message never
    ;; delivers, so the loop's deref hits the :timeout sentinel (the
    ;; fallback when the transport didn't deliver an error).
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [_]
                    ;; never deliver — the deref hits its timeout
                    (future (Thread/sleep 1000) :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= 1 (count @errors)) "the timeout surfaces via on-error (not silent)")
    (t/is (str/includes? (first @errors) "timed out")
          "the surfaced error names the timeout")
    (let [starts (filter #(= :auto-retry-start (:type %)) @events)]
      (t/is (= 1 (count starts)) "the timeout is retried once (retryable)")
      (t/is (= "LLM call timed out after 10ms" (:error-message (first starts)))))
    (t/is (= :idle @(:status agent)) "run settles idle after retries exhausted")
    (t/is (some #(= :error (:type %)) @events) "terminal :error event emitted")))

(t/deftest test-loop-compaction-failure-reports-failed
  ;; pi: a summarization failure surfaces via compaction_end errorMessage and
  ;; session_compact_failed — kmet returns :failed (distinct from false =
  ;; nothing to compact) so the manual /compact path can show the real error
  ;; instead of "Nothing to compact".
  (let [dir (fs/create-temp-dir {:dir (System/getProperty "user.home")})
        sess (session/create-session (str dir))
        events (atom [])
        agent (loop/make-agent-state :session sess
                                     :on-event (fn [e] (swap! events conj e))
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
                    ;; summarization call errors out — no summary delivered
                    llm/send-message (fn [opts]
                                       (future
                                         (when-let [on-error (:on-error opts)]
                                           (on-error "upstream failure"))
                                         :done))]
        (let [result (binding [*err* (java.io.StringWriter.)]
                       (loop/compact-context! agent nil :threshold))]
          (t/is (= :failed result) "summarization failure reports :failed")
          (let [end (first (filter #(= :compaction-end (:type %)) @events))
                failed (first (filter #(= :session-compact-failed (:type %)) @events))]
            (t/is (some? end) "compaction-end emitted")
            (t/is (false? (:result end)) "no compaction happened")
            (t/is (some? (:error-message end)) "compaction-end carries the error")
            (t/is (= :threshold (:reason end)) "reason preserved")
            (t/is (some? failed) "session-compact-failed emitted (pi: session_compact_failed)")
            (t/is (= :threshold (:reason failed)) "failure event carries the reason")
            (t/is (some? (:error-message failed)) "failure event carries the error"))))
      (t/is (= 6 (count @(:entries sess))) "session untouched by failed compaction")
      (finally
        (fs/delete-tree dir)))))
