(ns kmet.app.loop.test-state
  "Agent-state construction and lifecycle: make-agent-state defaults and helpers,
   model/thinking persistence, cancellation and status transitions, event
   dispatching, and the pure phase helpers of run-agent-turn."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.ai.models :as models]
            [kmet.ai.auth :as auth]
            [kmet.app.skills :as skills]
            [kmet.app.tools.registry :as tools-registry]
            [kmet.app.session.core :as session]
            [kmet.app.loop.state :as state]
            [kmet.app.system-prompt :as system-prompt]
            [kmet.app.loop :as loop]
            [kmet.app.retry :as retry]
            [kmet.app.events :as events]
            [kmet.app.loop.test-support :as support]
            [kmet.test-tmp :as test-tmp]))

(t/use-fixtures
  :each
  ;; The app root is not this namespace's subject: `test-support/with-registry`
  ;; gives each test its own tool table, so registering a stand-in tool here
  ;; cannot boot kmet2's composition for the rest of the suite.
  (fn [f] (support/with-registry f)))

;; ─── State construction ───────────────────────────────────────────────────

(t/deftest test-loop-make-agent-state-defaults
  (let [agent (support/make-agent-state)]
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

(t/deftest test-loop-make-agent-state-enabled-tool-entries
  (let [agent (support/make-agent-state :enabled-tool-entries ["read"])]
    (t/is (= #{"read"} (state/callable-tool-names agent))
          "a plain enabled list is the absolute callable selection"))
  (let [agent (support/make-agent-state :enabled-tool-entries ["-bash"])]
    (t/is (contains? (state/callable-tool-names agent) "read"))
    (t/is (not (contains? (state/callable-tool-names agent) "bash"))
          "a modifier-only enabled list excludes over the live names"))
  (let [h (support/tool-table)
        agent (support/make-agent-state :enabled-tool-entries ["-bash"])]
    (tools-registry/register-tool! h {:name "late-tool"
                                      :description "late"
                                      :parameters {:type "object" :properties {}}
                                      :execute (fn [_] {:content "ok"})})
    (try
      (t/is (contains? (state/callable-tool-names agent) "late-tool")
            "a tool mounted after the setting defaults on")
      (finally (tools-registry/unregister-tool! h "late-tool")))))

(t/deftest test-loop-apply-enabled-tools-replaces-the-setting
  (let [agent (support/make-agent-state :enabled-tool-entries ["read"])]
    (t/is (= #{"read"} (state/callable-tool-names agent)))
    (state/apply-enabled-tools! agent ["-bash"])
    (t/is (contains? (state/callable-tool-names agent) "read"))
    (t/is (not (contains? (state/callable-tool-names agent) "bash")))
    (state/apply-enabled-tools! agent nil)
    (t/is (contains? (state/callable-tool-names agent) "bash")
          "nil clears the setting back to the default surface")))

(t/deftest test-loop-enabled-tool-names
  (let [agent (support/make-agent-state :enabled-tool-entries ["-bash"])]
    (t/is (contains? (state/enabled-tool-names agent) "read"))
    (t/is (not (contains? (state/enabled-tool-names agent) "bash"))))
  (let [agent (support/make-agent-state)]
    (t/is (contains? (state/enabled-tool-names agent) "read")
          "no setting enables the live surface")))

(t/deftest test-loop-make-agent-state-with-opts
  (let [session {:dummy :session}
        events (atom [])
        agent (support/make-agent-state
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
  (let [all-tools (vals (tools-registry/get-all-tools (support/tool-table)))
        opts {:tools all-tools}
        prompt (skills/build-system-prompt :tools all-tools)
        agent (support/make-agent-state
               :model "m"
               :system prompt
               :system-prompt-opts opts)]
    ;; initial prompt advertises the builtin tools; the shell-exploration rule
    ;; fires for a selected shell (pi: buildRules)
    (t/is (str/includes? @(:system agent) "- bash:"))
    (t/is (str/includes? @(:system agent) "Use bash for file operations"))
    ;; disable bash
    (state/set-active-tools! agent (mapv :name (remove #(= "bash" (:name %)) all-tools)))
    (t/is (= (set (map :name (remove #(= "bash" (:name %)) all-tools)))
             @(:enabled-tools agent)))
    (t/is (not (str/includes? @(:system agent) "- bash:")))
    (t/is (not (str/includes? @(:system agent) "Use bash for file operations")))
    ;; a dedicated search tool in the active set suppresses the shell rule
    ;; (pi: buildRules — grep/find/ls); the stand-in mirrors the grep extension
    (try
      (tools-registry/register-tool! (support/tool-table) {:name "grep"
                                                           :description "stand-in for the grep extension"
                                                           :prompt-snippet "stand-in for the grep extension"
                                                           :execute (fn [_] {:content ""})})
      (state/set-active-tools! agent ["bash" "read" "grep"])
      (t/is (str/includes? @(:system agent) "- grep:"))
      (t/is (not (str/includes? @(:system agent) "Use bash for file operations")))
      (finally
        (tools-registry/unregister-tool! (support/tool-table) "grep")))
    ;; restore all
    (state/set-active-tools! agent nil)
    (t/is (nil? @(:enabled-tools agent)))
    (t/is (str/includes? @(:system agent) "- bash:"))
    (t/is (str/includes? @(:system agent) "Use bash for file operations"))
    ;; unknown tool names are filtered out of the enabled set (pi)
    (state/set-active-tools! agent ["read" "nonexistent-tool"])
    (t/is (= #{"read"} @(:enabled-tools agent)))
    (t/is (str/includes? @(:system agent) "- read:"))
    (t/is (not (str/includes? @(:system agent) "nonexistent-tool")))
    ;; a bare string is treated as a single tool name (untyped extensions)
    (state/set-active-tools! agent "write")
    (t/is (= #{"write"} @(:enabled-tools agent)))
    (t/is (str/includes? @(:system agent) "- write:"))))

(t/deftest test-loop-reload-keeps-tools-reenableable
  ;; pi: reload rebuilds the prompt over the ACTIVE tool set — a tool
  ;; disabled before the reload must be re-enableable afterwards (the
  ;; reload handler in interactive.clj rebuilds opts over the active set;
  ;; set-active-tools! always validates against the live registry).
  (let [all-tools (vals (tools-registry/get-all-tools (support/tool-table)))
        opts {:tools all-tools}
        agent (support/make-agent-state
               :model "m"
               :system (skills/build-system-prompt :tools all-tools)
               :system-prompt-opts opts)]
    ;; restrict to read (as an extension would)
    (state/set-active-tools! agent ["read"])
    (t/is (= #{"read"} @(:enabled-tools agent)))
    ;; reload: prompt rebuilt over active set, opts updated
    (let [read-tool (first (filter #(= "read" (:name %)) all-tools))]
      (reset! (:system-prompt-opts agent) (assoc opts :tools [read-tool]))
      (reset! (:system agent) (skills/build-system-prompt :tools [read-tool]))
      (t/is (str/includes? @(:system agent) "- read:"))
      (t/is (not (str/includes? @(:system agent) "- bash:"))))
    ;; re-enable bash after the reload — must work (sources = full registry)
    (state/set-active-tools! agent ["read" "bash"])
    (t/is (= #{"read" "bash"} @(:enabled-tools agent)))
    (t/is (str/includes? @(:system agent) "- bash:"))))

;; ─── State helpers ───────────────────────────────────────────────────────

(t/deftest test-loop-get-context
  (let [agent (support/make-agent-state)]
    (t/is (empty? (state/get-context agent)))
    (swap! (:messages agent) conj {:role :user :content "hi"})
    (t/is (= 1 (count (state/get-context agent))))))

(t/deftest test-loop-restore-session-context
  ;; Restoring a session rebuilds the agent context from the session branch —
  ;; steered and follow-up user messages come back for the next LLM call.
  (let [dir (test-tmp/temp-dir! "test-loop-restore-")]
    (try
      (let [sess (session/create-session dir)]
        (session/append-entry sess {:role :user :content [{:type :text :text "hello"}]})
        (session/append-entry sess {:role :assistant :content [{:type :text :text "hi"}]})
        (session/append-entry sess {:role :user :content [{:type :text :text "steered"}]})
        (session/append-entry sess {:role :assistant :content [{:type :text :text "done"}]})
        (session/append-entry sess {:role :session_info :name "t"})
        (let [loaded (session/load-session (:file sess))
              agent (support/make-agent-state :session loaded)]
          (state/restore-session-context! agent)
          (t/is (= [:user :assistant :user :assistant]
                   (mapv :role (state/get-context agent)))
                "session_info entries are metadata — not context")
          (t/is (= "steered" (-> (state/get-context agent) (nth 2) :content first :text))
                "steered user message restored")))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-set-system-prompt
  (let [agent (support/make-agent-state)]
    (reset! (:system agent) "new prompt")
    (t/is (= "new prompt" @(:system agent)))))

(t/deftest test-loop-set-model
  (let [agent (support/make-agent-state)
        model (models/map->Model {:provider :anthropic :id "gpt-4"})]
    (state/set-model! agent model)
    (t/is (= "gpt-4" @(:model agent)))
    (t/is (= :anthropic @(:provider agent)) "the provider follows the record")))

(t/deftest test-loop-set-provider
  (let [agent (support/make-agent-state)]
    (reset! (:provider agent) :anthropic)
    (t/is (= :anthropic @(:provider agent)))))

;; ─── Model/thinking change persistence (G6) ───────────────────────────────

(t/deftest test-loop-set-model-persists-change
  (let [dir (test-tmp/temp-dir! "test-loop-model-change-")]
    (try
      (let [sess (session/create-session dir)
            agent (support/make-agent-state :session sess
                                            :provider :anthropic
                                            :model "claude-sonnet")
            model (models/map->Model {:provider :openai :id "gpt-4o"})]
        (state/set-model! agent model)
        (let [branch (session/get-branch sess)]
          (t/is (= [:model-change] (mapv :role branch)))
          (t/is (= "gpt-4o" (:model (last branch))))
          (t/is (= :openai (:provider (last branch)))
                "entry records the model's provider, not the previous provider")
          (t/is (= :openai @(:provider agent))))
        (state/set-model! agent model)
        (t/is (= 1 (count (session/get-branch sess)))
              "unchanged model: no duplicate entry"))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-cycle-model-persists-change
  (let [dir (test-tmp/temp-dir! "test-loop-cycle-model-")
        saved (models/get-providers)]
    (models/clear-providers!)
    (models/register-provider! (support/make-test-provider ["a" "b"]))
    (try
      (with-redefs [auth/auth-atom (atom {:test-prov {:key "sk"}})]
        (let [sess (session/create-session dir)
              agent (support/make-agent-state :session sess
                                              :provider :test-prov
                                              :model "a"
                                              :scoped-models ["test-prov/a" "test-prov/b"])]
          ;; lazy creation: file exists only after an assistant message
          (session/append-entry sess {:role :user :content "q"})
          (session/append-entry sess {:role :assistant :content "a"})
          (state/cycle-model! agent 1)
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
  (let [dir (test-tmp/temp-dir! "test-loop-thinking-change-")]
    (try
      (let [sess (session/create-session dir)
            agent (support/make-agent-state :session sess :thinking :off)]
        (state/set-thinking-level! agent :high)
        (t/is (= :high @(:thinking agent)))
        (t/is (= [:thinking-level-change] (mapv :role (session/get-branch sess))))
        (t/is (= :high (:thinking-level (last (session/get-branch sess)))))
        (state/set-thinking-level! agent :high)
        (t/is (= 1 (count (session/get-branch sess)))
              "same level: no duplicate entry")
        (state/set-thinking-level! agent :off)
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
  (let [dir (test-tmp/temp-dir! "test-loop-restore-messages-only-")]
    (try
      (let [sess (session/create-session dir)]
        (session/append-entry sess {:role :user :content [{:type :text :text "hi"}]})
        (session/append-model-change! sess :anthropic "claude-sonnet")
        (session/append-thinking-level-change! sess :medium)
        (session/append-entry sess {:role :assistant :content [{:type :text :text "yo"}]})
        (let [loaded (session/load-session (:file sess))
              agent (support/make-agent-state :session loaded
                                              :provider :opencode-go
                                              :model "gpt-4o"
                                              :thinking :off)]
          (state/restore-session-context! agent)
          (t/is (= "gpt-4o" @(:model agent)))
          (t/is (= :opencode-go @(:provider agent)))
          (t/is (= :off @(:thinking agent)))
          (t/is (= [:user :assistant] (mapv :role (state/get-context agent)))
                "change entries are metadata — not context")))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-restore-session-context-drops-incomplete-tool-calls
  ;; A session interrupted mid-tool-batch (process death between the assistant
  ;; tool-call message and its results) resumes without the dangling tool
  ;; calls — restore drops them so the next provider request stays valid.
  (let [dir (test-tmp/temp-dir! "test-loop-restore-dangling-")]
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
              agent (support/make-agent-state :session loaded)]
          (state/restore-session-context! agent)
          (let [ctx (state/get-context agent)
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
  (let [dir (test-tmp/temp-dir! "test-loop-apply-settings-")
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
                agent (support/make-agent-state :session (session/load-session (:file s))
                                                :provider :test-prov :model "m1" :thinking :off)]
            (t/is (state/apply-session-settings! agent))
            (t/is (= "m2" @(:model agent)) "authenticated derived model restored")
            (t/is (= :medium @(:thinking agent)) "recorded thinking restored"))))
      ;; no auth → model not restored, thinking still is
      (with-auth {}
        (fn []
          (let [s (make-session)
                _ (session/append-thinking-level-change! s :high)
                agent (support/make-agent-state :session (session/load-session (:file s))
                                                :provider :test-prov :model "m1" :thinking :off)]
            (t/is (state/apply-session-settings! agent))
            (t/is (= "m1" @(:model agent)) "unauth'd model not restored (pi: hasConfiguredAuth)")
            (t/is (= :high @(:thinking agent)) "thinking has no auth guard"))))
      ;; unrecorded thinking → current level kept (pi: hasThinkingEntry guard)
      (with-auth {:test-prov {:key "sk-test"}}
        (fn []
          (let [agent (support/make-agent-state :session (session/load-session (:file (make-session)))
                                                :provider :test-prov :model "m1" :thinking :high)]
            (t/is (state/apply-session-settings! agent))
            (t/is (= "m2" @(:model agent)))
            (t/is (= :high @(:thinking agent)) "unrecorded thinking keeps the current level"))))
      (finally
        (models/clear-providers!)
        (doseq [p saved-providers] (models/register-provider! p))
        (fs/delete-tree dir)))))

;; ─── Cancellation ────────────────────────────────────────────────────────

(t/deftest test-loop-cancel-turn
  (let [agent (support/make-agent-state)]
    (reset! (:status agent) :thinking)
    (reset! (:signal agent) false)
    (loop/cancel-turn agent)
    (t/is (= :idle @(:status agent)))
    (t/is (true? @(:signal agent)))))

(t/deftest test-loop-cancel-turn-when-idle
  (let [agent (support/make-agent-state)]
    (loop/cancel-turn agent)
    (t/is (= :idle @(:status agent)))))

;; ─── Status transitions ──────────────────────────────────────────────────

(t/deftest test-loop-status-transitions
  (let [agent (support/make-agent-state)]
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
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (loop/cancel-turn agent)
    (t/is (pos? (count @events)))
    (t/is (= :idle (:status (last @events))))))

(t/deftest test-loop-emit-reaches-the-callback-and-the-one-table
  ;; One table: an event reaches the front end twice — the `:on-event` callback
  ;; and the listeners — and both see the same decorated copy (`:will-retry`),
  ;; because pi's two-copy agent_end split became one emission.
  (let [{ctx :ctx} (support/test-ctx)
        ui (atom [])
        heard (atom [])
        dispose (events/on! ctx :agent-end (fn [ev] (swap! heard conj ev)))]
    (try
      (binding [support/*ctx* ctx]
        (state/emit (support/make-agent-state :on-event (fn [e] (swap! ui conj e)))
                    {:type :agent-end :will-retry true}))
      (t/is (= [true] (mapv :will-retry @ui)) "the callback gets the event")
      (t/is (= [true] (mapv :will-retry @heard)) "and so does the listener")
      (finally (dispose)))))

(t/deftest test-loop-on-event-no-listener
  (let [agent (support/make-agent-state)]
    (t/is (nil? (:on-event agent)))
    (loop/cancel-turn agent)
    (t/is (= :idle @(:status agent)))))

(t/deftest test-loop-make-agent-state-with-thinking
  (let [agent (support/make-agent-state :thinking :high)]
    (t/is (= :high @(:thinking agent)))))

(t/deftest test-loop-thinking-default
  (let [agent (support/make-agent-state)]
    (t/is (= :off @(:thinking agent)))))

;; ─── Pure phase helpers of run-agent-turn ─────────────────────────────────

(t/deftest test-normalize-llm-result
  (t/testing "provider :error stop-reason folds into :error (pi mapStopReason)"
    (let [out (#'retry/normalize-llm-result
               {:stop-reason :error :error-message "content filter"})]
      (t/is (= "content filter" (:error out)))))
  (t/testing "missing error-message gets a generic provider-stopped text"
    (let [out (#'retry/normalize-llm-result {:stop-reason :error})]
      (t/is (= "Provider stopped with: error" (:error out)))))
  (t/testing "normal results and the :timeout sentinel pass through untouched"
    (let [r {:stop-reason :end_turn :content [{:type :text :text "hi"}]}]
      (t/is (= r (#'retry/normalize-llm-result r))))
    (t/is (= :timeout (#'retry/normalize-llm-result :timeout)))))

(t/deftest test-retry-decision
  (t/testing "context overflow with session and no prior recovery compacts first"
    (t/is (= {:kind :overflow-recover}
             (#'retry/retry-decision
              {:err "prompt is too long" :retry-count 0 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered false :has-session true}))))
  (t/testing "overflow is compaction territory even with retries left"
    (t/is (= {:kind :overflow-recover}
             (#'retry/retry-decision
              {:err "prompt is too long" :retry-count 1 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered false :has-session true}))))
  (t/testing "second overflow (already recovered) falls through to classification"
    (t/is (= {:kind :terminal}
             (#'retry/retry-decision
              {:err "prompt is too long" :retry-count 0 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered true :has-session true}))
          "overflow errors are not retryable-error? matches"))
  (t/testing "retryable within budget backs off exponentially, same turn"
    (let [d (#'retry/retry-decision
             {:err "rate limited" :retry-count 1 :max-retries 3
              :base-delay-ms 2000 :overflow-recovered false :has-session true})]
      (t/is (= :backoff (:kind d)))
      (t/is (= 2 (:attempt d)))
      (t/is (= 4000 (:delay-ms d)) "2000 * 2^(2-1)")
      (t/is (= 3 (:max-attempts d)) "budget snapshot rides the decision")))
  (t/testing "exhausted budget or non-retryable errors are terminal"
    (t/is (= {:kind :terminal}
             (#'retry/retry-decision
              {:err "rate limited" :retry-count 3 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered false :has-session true})))
    (t/is (= {:kind :terminal}
             (#'retry/retry-decision
              {:err "quota exceeded for project" :retry-count 0 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered false :has-session true}))))
  (t/testing "overflow without a session cannot recover — terminal here"
    (t/is (= {:kind :terminal}
             (#'retry/retry-decision
              {:err "prompt is too long" :retry-count 0 :max-retries 3
               :base-delay-ms 2000 :overflow-recovered false :has-session false})))))

(t/deftest a-custom-system-prompt-fn-rebuilds-the-prompt
  ;; An active-tools change must re-render the prompt through the builder the
  ;; state was given — the seam the :systemPrompt row fills — not through a
  ;; namespace.
  (let [calls (atom [])
        agent (support/make-agent-state
               :system "base"
               :system-prompt-opts {:tools []}
               :system-prompt-fn (fn [& {:as opts}]
                                   (swap! calls conj opts)
                                   "rebuilt"))]
    (state/set-active-tools! agent nil)
    (t/is (= "rebuilt" @(:system agent)))
    (t/is (= 1 (count @calls)) "the builder ran once")
    (t/is (contains? (first @calls) :tools)
          "with the state's options map, its tool selection included"))
  (t/is (identical? system-prompt/build-system-prompt
                    (:system-prompt-fn (support/make-agent-state)))
        "and the default is the app's own builder, which a process-bound caller
       replaces (kmet1's Engine passes skills/build-system-prompt)"))
