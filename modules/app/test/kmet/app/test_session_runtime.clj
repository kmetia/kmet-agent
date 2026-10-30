(ns kmet.app.test-session-runtime
  "Tests for kmet.app.session.runtime over a real handle: the value a front end
   drives, the accessors it reads, and the operations a handle answers.

   The subject is the handle the composition's `:agents` factory creates, not a
   stand-in engine — the shape production runs."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.commands :as commands]
            [kmet.app.compaction :as agent-compact]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.loop :as agent]
            [kmet.app.loop.state :as loop-state]
            [kmet.app.prompts :as prompts]
            [kmet.app.session.core :as session]
            [kmet.app.session.runtime :as rt]
            [kmet.app.session.submission :as submission]
            [kmet.app.skills :as skills]
            [kmet.app.tools.invoke :as invoke]
            [kmet.app.tools.registry :as tools-reg]
            [kmet.ai.models :as models]
            [kmet.app.registry :as registry]
            [kmet.srce.core :as srce]
            [kmet.test-utils :as tu]
            [kmet.app.test-support :as support]))

;; The composition provides the handle factory, its ctx and the prompt row.
(clojure.test/use-fixtures :once support/with-composition)
;; Several tests register app-wide commands, tools, prompts, skills and hooks;
;; clear the process-global registrations between tests.
(clojure.test/use-fixtures :each tu/with-clean-app-registries)

(defn- with-handle
  "Create a real handle through the composition and call F with it and its
   session, disposing it through the registry afterwards (which also releases
   its child ctx) and removing its scratch session dir."
  ([f] (with-handle {} f))
  ([opts f]
   (let [handle (support/create-agent-handle! opts)
         agents (srce/get (support/composition-ctx) :agents)
         dir (:session-dir (rt/config handle))]
     (try (f handle (rt/session handle))
          (finally
            ((:dispose! agents) handle)
            (when dir (fs/delete-tree dir {:missing-ok true})))))))

(deftest test-commands-outlive-an-engine
  ;; The app root's store owns commands, so disposing a handle does not drop
  ;; them.
  (with-handle
    (fn [_ _]
      (commands/register-command!
       {:name "outlives-demo" :handler (fn [& _] nil)})))
  (try
    (is (some? (commands/find-command "outlives-demo")))
    (finally
      (commands/unregister-command! "outlives-demo"))))

(deftest test-tools-outlive-an-engine
  ;; Custom tools and contributed sources are the app's: registered while a
  ;; handle runs, they are found after its disposal.
  (with-handle
    (fn [_ _]
      (tools-reg/register-tool! (registry/current)
                                {:name "outlives-tool" :label "Outlives"
                                 :description "d"
                                 :parameters {:type "object" :properties {}}
                                 :execute (fn [_] {:content "ok"})})
      (tools-reg/register-tool-source! (registry/current) ::outlives
                                       (fn [] {"sandbox-outlives" {}}))))
  (try
    (is (contains? (tools-reg/get-all-tools (registry/current))
                   "outlives-tool"))
    (is (contains? (tools-reg/get-contributed-tools (registry/current))
                   "sandbox-outlives"))
    (finally
      (tools-reg/unregister-tool! (registry/current) "outlives-tool")
      (tools-reg/unregister-tool-source! (registry/current) ::outlives))))

(deftest test-prompt-templates-outlive-an-engine
  ;; Prompt templates are the app's: one registered while a handle runs is
  ;; found after its disposal, and looked up by name from the store.
  (with-handle
    (fn [_ _]
      (prompts/register-prompt-template!
       {:name "outlives-template" :content "---\ndescription: d\n---\nBody"
        :location "my-ext:prompts/outlives.md" :extension "my-ext"})))
  (try
    (is (= "Body" (:content (prompts/get-prompt-template "outlives-template"))))
    (finally
      (prompts/clear-prompt-templates!))))

(deftest test-skills-outlive-an-engine
  ;; Skills are the app's: one registered while a handle runs is found after
  ;; its disposal, and looked up by name from the store.
  (with-handle
    (fn [_ _]
      (skills/register-skill! "outlives-skill" "A skill that outlives the handle.")))
  (try
    (is (= "outlives-skill" (:name (skills/get-skill "outlives-skill"))))
    (finally
      (skills/clear-skills!))))

(deftest test-handle-carries-the-run-config
  (with-handle
    (fn [handle sess]
      (let [ag (rt/agent-state handle)]
        (is (identical? sess (rt/session handle)) "the session is the resolved record")
        (is (identical? sess (:session ag)) "the agent runs on the same session")
        (is (= :opencode-go @(:provider ag)))
        (is (= "deepseek-v4-flash" @(:model ag)))
        (is (string? @(:system ag)) "the system prompt was built")
        (is (= [] (rt/scoped-models handle)))
        (is (= :off @(:thinking ag)) "thinking defaults to off")
        (is (srce/inside? (rt/agent-ctx handle) (support/composition-ctx))
            "the handle's ctx is the composition's child")))))

(deftest test-expansion-chain
  ;; The handle's expansion is the process's unified chain, applied to queued
  ;; and submitted text before it reaches the run.
  (with-handle
    (fn [handle _]
      (prompts/register-prompt-template! {:name "expand-me" :content "Expanded body."})
      (is (= "Expanded body." (submission/expand-text handle "/expand-me"))))))

(deftest test-send-starts-and-records-the-run
  (with-handle
    (fn [handle _]
      (let [seen (atom nil)
            run (future :settled)]
        (with-redefs [agent/run-agent-turn
                      (fn [ag opts]
                        (reset! seen {:ag ag :opts opts})
                        run)]
          (is (= :started ((:send! handle) "hi" {:target :next-turn}))
              "send returns the delivery outcome immediately")
          (is (identical? (rt/agent-state handle) (:ag @seen)))
          (is (= "hi" (:message (:opts @seen))))
          (is (true? (rt/turn-running? handle)) "the send owns the turn flag")
          (is (fn? (:on-done (:opts @seen))) "the runtime wraps the settle")
          ((:on-done (:opts @seen)) "answer")
          (is (false? (rt/turn-running? handle))
              "the wrapped settle clears the turn flag")
          (is (identical? run @(:last-run handle))
              "the run is recorded for disposal"))))))

(deftest test-send-uses-the-live-agent-after-a-session-switch
  ;; A session switch replaces the agent state in the shared atom; the send
  ;; must run the new record, not the one the handle was created with.
  (with-handle
    (fn [handle _]
      (let [seen (atom nil)]
        (reset! (:agent-state handle)
                (assoc (rt/agent-state handle) :session ::switched))
        (with-redefs [agent/run-agent-turn (fn [ag _]
                                             (reset! seen (:session ag))
                                             (future))]
          ((:send! handle) "hi" {:target :next-turn})
          (is (= ::switched @seen)))))))

(deftest test-send-queues-while-the-turn-flag-is-set
  ;; The turn flag is set synchronously when a prompt starts, so a second send
  ;; queues into the run instead of starting a concurrent one — even when the
  ;; agent's status already reads idle (the settle window).
  (with-handle
    (fn [handle _]
      (let [started (atom 0)]
        (with-redefs [agent/run-agent-turn (fn [& _]
                                             (swap! started inc)
                                             (future))]
          (reset! (:turn-running? handle) true)
          (is (= :steered ((:send! handle) "queued" {:target :next-turn})))
          (is (= ["queued"] @(:follow-up (rt/agent-state handle))))
          (is (= 0 @started) "no concurrent run")
          (reset! (:turn-running? handle) false)
          (is (= :started ((:send! handle) "fresh" {:target :next-turn})))
          (is (= 1 @started) "an idle send starts the run"))))))

(deftest test-dispose-is-idempotent
  (with-handle
    (fn [handle _]
      (is (nil? ((:dispose! handle))))
      (is (true? @(:disposed? handle)))
      (is (nil? ((:dispose! handle))) "dispose! is idempotent"))))

(deftest test-session-install-is-last-wins
  ;; The extension-facing session is one process cache (one live front end at
  ;; a time): installing a session replaces the previous one.
  (try
    (extensions/install-session! :old-session)
    (extensions/install-session! :newer-session)
    (is (= :newer-session (extensions/get-session)))
    (finally
      (extensions/install-session! nil))))

(deftest test-snapshot-is-plain-data
  (with-handle
    (fn [handle sess]
      (let [snap (rt/snapshot handle)]
        (is (identical? sess (:session snap)))
        (is (= :idle (:status snap)))
        (is (= :opencode-go (:provider snap)))
        (is (= "deepseek-v4-flash" (:model snap)))
        (is (= {:steering [] :follow-up []} (:queues snap)))
        (is (= :completed (:last-outcome snap)))
        (is (not (contains? snap :agent-state)) "the snapshot is a view, not the engine")))))

(deftest test-front-end-state-accessors
  (with-handle
    (fn [handle _]
      (let [ag (rt/agent-state handle)]
        (is (= "deepseek-v4-flash" (rt/model handle)))
        (is (= :opencode-go (rt/provider handle)))
        (with-redefs [models/get-model (fn [p id] {:provider p :id id})]
          (is (= {:provider :opencode-go :id "deepseek-v4-flash"}
                 (rt/model-record handle))
              "the record resolves from the live provider/model"))
        (is (= :off (rt/thinking-level handle)))
        (reset! (:thinking ag) :high)
        (is (= :high (rt/thinking-level handle))
            "accessors read the live atom, not a captured record")
        (is (= @(:system ag) (rt/system-prompt handle)))
        (is (false? (rt/compacting? handle)))
        (reset! (:compacting? ag) true)
        (is (true? (rt/compacting? handle)))
        (is (nil? (rt/compaction-error handle)))
        (reset! (:compaction-error ag) "boom")
        (is (= "boom" (rt/compaction-error handle)))
        (is (false? (rt/cancel-signal handle)))
        (reset! (:signal ag) true)
        (is (true? (rt/cancel-signal handle)))
        (is (false? (rt/has-queued-messages? handle)))
        (loop-state/steer! ag "queued")
        (is (true? (rt/has-queued-messages? handle))
            "a queued steer message makes the engine non-idle for extensions")))))

(deftest test-run-options-are-live-and-merge
  (with-handle
    (fn [handle _]
      (is (= :all (:steering-mode (rt/run-options handle))))
      (rt/set-run-options! handle {:context-window 12345})
      (is (= 12345 (:context-window (rt/run-options handle)))
          "the option applies live")
      (is (= :all (:steering-mode (rt/run-options handle)))
          "merge keeps untouched options")
      (rt/set-run-options! handle {:steering-mode :one-at-a-time})
      (is (= :one-at-a-time (:steering-mode (rt/run-options handle))))
      (is (= 12345 (:context-window (rt/run-options handle)))))))

(deftest test-snapshot-carries-queue-and-compaction-state
  (with-handle
    (fn [handle _]
      (rt/set-scoped-models! handle ["opencode-go/a" "opencode-go/b"])
      (rt/set-run-options! handle {:steering-mode :one-at-a-time
                                   :follow-up-mode :all
                                   :auto-compact false})
      (reset! (:compacting? (rt/agent-state handle)) true)
      (let [snap (rt/snapshot handle)]
        (is (= ["opencode-go/a" "opencode-go/b"] (:scoped-models snap)))
        (is (true? (:compacting? snap)))
        (is (= :one-at-a-time (:steering-mode snap)))
        (is (= :all (:follow-up-mode snap)))
        (is (false? (:auto-compact snap)))))))

(deftest test-set-system-prompt-replaces-prompt-and-opts
  (with-handle
    (fn [handle _]
      (let [ag (rt/agent-state handle)
            opts {:cwd "target/test-prompt-cwd"}]
        (rt/set-system-prompt! handle "custom prompt" opts)
        (is (= "custom prompt" (rt/system-prompt handle)))
        (is (= opts @(:system-prompt-opts ag)))))))

(deftest test-active-tools-follow-the-default-selection
  (with-handle
    (fn [handle _]
      (is (contains? (set (map :name (rt/active-tools handle))) "read")
          "the bootstrap default set is active")
      (is (= ["read" "bash"]
             (rt/apply-default-tools! handle ["read" "bash"]))
          "the applied selection is returned")
      (let [names (set (map :name (rt/active-tools handle)))]
        (is (contains? names "read"))
        (is (contains? names "bash"))
        (is (not (contains? names "write"))
            "built-ins outside the selection are inactive")))))

(deftest test-add-context-message-and-bash-result
  (with-handle
    (fn [handle _]
      (let [before (count (rt/messages handle))]
        (rt/add-context-message! handle {:role :custom :content "note"})
        (is (= (inc before) (count (rt/messages handle))))
        (is (= [{:type :text :text "note"}] (:content (last (rt/messages handle))))
            "string content is normalized to a text block"))
      (rt/add-bash-result! handle "echo hi" {:output "hi" :exit-code 0} false)
      (let [entry (last (rt/messages handle))]
        (is (= :bash (:role entry)) "an idle bash result reaches the context")
        (is (= "echo hi" (:command entry)))
        (is (= "hi" (:output entry)))))))

(deftest test-abort-compaction-signals
  (with-handle
    (fn [handle _]
      (let [ag (rt/agent-state handle)]
        (is (false? @(:compaction-signal ag)))
        (is (nil? ((:abort-compaction! handle))))
        (is (true? @(:compaction-signal ag)) "escape during compaction signals it")
        (is (false? @(:signal ag)) "the run-cancel signal is untouched")))))

(deftest test-request-compaction-starts-when-idle
  (with-handle
    (fn [handle _]
      (let [completed (promise)]
        (with-redefs [agent-compact/compact-context! (fn [& _] true)]
          (is (= :started ((:request-compaction! handle) nil
                                                         {:on-complete #(deliver completed %)})))
          (is (true? (deref completed 2000 ::timeout))
              "an idle request starts immediately and reports its result"))
        (reset! (:compacting? (rt/agent-state handle)) true)
        (is (= :compacting ((:request-compaction! handle) nil nil))
            "a compaction in flight refuses a second one")
        (is (empty? (rt/pending-compaction handle))
            "a refused request is not queued")))))

(deftest test-request-compaction-defers-while-a-turn-runs
  (with-handle
    (fn [handle _]
      (let [completed (promise)
            seen (atom [])]
        (reset! (:turn-running? handle) true)
        (with-redefs [agent-compact/compact-context!
                      (fn [_ag instructions reason]
                        (swap! seen conj [instructions reason])
                        true)]
          (is (= :scheduled
                 ((:request-compaction! handle) "focus"
                                                {:on-complete #(deliver completed %)}))
              "a live prompt defers the request")
          (is (= ["focus"] (mapv :custom-instructions (rt/pending-compaction handle))))
          ;; a settle while a newer prompt already owns the runtime leaves it
          ;; queued for the next settle
          ((:on-event (rt/agent-state handle)) {:type :agent-settled :outcome :completed})
          (is (false? (realized? completed)) "a busy settle does not start it")
          (is (= 1 (count (rt/pending-compaction handle))) "still queued")
          (reset! (:turn-running? handle) false)
          ((:on-event (rt/agent-state handle)) {:type :agent-settled :outcome :completed})
          (is (true? (deref completed 2000 ::timeout))
              "the settle event starts the deferred compaction")
          (is (= [["focus" :manual]] @seen)
              "it runs as a manual compaction with its instructions"))
        (is (empty? (rt/pending-compaction handle)) "the queue drained")))))

(deftest test-request-compaction-reports-failures
  (with-handle
    (fn [handle _]
      (let [failed (promise)]
        (with-redefs [agent-compact/compact-context!
                      (fn [ag & _]
                        (reset! (:compaction-error ag) "Compaction failed: boom")
                        :failed)]
          ((:request-compaction! handle) nil
                                         {:on-error #(deliver failed (ex-message %))})
          (is (= "Compaction failed: boom" (deref failed 2000 ::timeout))
              ":failed reports pi's message through :on-error"))))))

(deftest test-new-session-drops-a-deferred-compaction
  (testing "a compaction deferred for the outgoing session dies with it —
            starting a new session must not let the settle drain run it
            against the swap"
    (with-handle
      (fn [handle _]
        (reset! (:turn-running? handle) true)
        (is (= :scheduled ((:request-compaction! handle) nil nil)))
        (is (= 1 (count (rt/pending-compaction handle))))
        ((:new-session! handle))
        (is (empty? (rt/pending-compaction handle))
            "the deferred request is dropped before the settle")))))

(deftest test-http-timeout-setters
  (with-handle
    (fn [handle _]
      (rt/set-http-idle-timeout-ms! handle 5000)
      (is (= 5000 (:http-idle-timeout-ms (rt/run-options handle))))
      (rt/set-http-total-timeout-ms! handle 9000)
      (is (= 9000 (:http-total-timeout-ms (rt/run-options handle))))
      (rt/set-http-total-timeout-ms! handle nil)
      (is (nil? (:http-total-timeout-ms (rt/run-options handle)))
          "nil follows the idle timeout"))))

(deftest test-switch-model-applies-the-rank-rule-and-persists
  (with-handle
    (fn [handle _]
      (let [ag (rt/agent-state handle)
            reasoner (models/map->Model {:provider :test-native :id "reasoner"
                                         :reasoning true})
            plain (models/map->Model {:provider :test-native :id "plain"
                                      :reasoning false})
            persisted (atom ::none)]
        (with-redefs [models/get-model (fn [p id]
                                         (when (= p :test-native)
                                           (case id
                                             "reasoner" reasoner
                                             "plain" plain
                                             nil)))
                      cfg/set-default-model! (fn [p id] (reset! persisted [p id]))]
          (reset! (:provider ag) :test-native)
          (reset! (:model ag) "reasoner")
          (reset! (:thinking ag) :low)
          (testing "a non-reasoning target drops the reasoning level to :off"
            (is (= :off (rt/switch-model! handle plain nil)))
            (is (= "plain" (rt/model handle)) "the model is applied")
            (is (= :off (rt/thinking-level handle)) "the clamped level is applied")
            (is (= [:test-native "plain"] @persisted) "persisted as the default"))
          (testing "an explicit level wins and is clamped to the target"
            (is (= :high (rt/switch-model! handle reasoner :high)))
            (is (= :high (rt/thinking-level handle)))
            (is (= [:test-native "reasoner"] @persisted))))))))

(deftest test-generate-branch-summary-forwards-to-the-agent
  (with-handle
    (fn [handle _]
      (let [signal (atom false)
            seen (atom nil)]
        (with-redefs [agent-compact/generate-branch-summary
                      (fn [ag entries instructions sig replace?]
                        (reset! seen [ag entries instructions sig replace?])
                        {:summary "s"})]
          (is (= {:summary "s"}
                 (rt/generate-branch-summary handle [{:id "e1"}] "instr" signal true)))
          (is (identical? (rt/agent-state handle) (nth @seen 0))
              "the call hands the live agent state over")
          (is (= [[{:id "e1"}] "instr" signal true] (subvec @seen 1))))))))

(deftest test-session-directory-helpers
  (let [dir (str "target/test-session-runtime-dirs-" (System/currentTimeMillis))
        cwd (str (fs/cwd))]
    (try
      (let [sess (session/create-session (rt/ensure-cwd-session-dir dir cwd)
                                         {:cwd cwd})]
        (is (= (some-> (session/session-dir-for-cwd dir cwd) fs/canonicalize str)
               (str (fs/parent (:file sess))))
            "a new session lands in the cwd-encoded dir under base-dir")
        (is (nil? (rt/find-recent-session dir cwd))
            "an empty cwd dir has no session to continue")
        (session/append-entry sess {:role :assistant :content "prior"})
        (is (= (str (fs/canonicalize (:file sess)))
               (str (fs/canonicalize (rt/find-recent-session dir cwd))))
            "the most recent session for the cwd is found"))
      (finally (fs/delete-tree dir)))))

(deftest test-two-handles-do-not-cross-contaminate
  (let [h1 (support/create-agent-handle!)
        h2 (support/create-agent-handle!)
        agents (srce/get (support/composition-ctx) :agents)
        dirs (map (comp :session-dir rt/config) [h1 h2])]
    (try
      (is (not (identical? (:ctx h1) (:ctx h2))) "one child ctx per handle")
      ;; The registries are the app's, not a handle's: a registration reaches
      ;; both, and a deregistered hook is gone from the tree every handle reads.
      (let [fired (atom 0)
            dereg (extensions/register-input-hook!
                   (extensions/extension-owner "test-hooks-app-wide")
                   (fn [_] (swap! fired inc) nil))]
        (try
          (commands/register-command!
           {:name "app-wide-demo" :handler (fn [& _] nil)})
          (is (some? (commands/find-command "app-wide-demo")))
          (extensions/apply-input-hooks "hello" :interactive)
          (is (= 1 @fired) "the hook fires from the registry alone")
          (dereg)
          (extensions/apply-input-hooks "hello" :interactive)
          (is (= 1 @fired) "and a deregistered hook stays gone")
          (finally
            (commands/unregister-command! "app-wide-demo"))))
      (finally
        ((:dispose! agents) h2)
        ((:dispose! agents) h1)
        (doseq [dir dirs]
          (when dir (fs/delete-tree dir {:missing-ok true})))))))

(deftest test-tool-hooks-come-from-the-app-tree
  ;; The transform chain reads the store (P2), so a hook an extension
  ;; registered reaches the engine — and leaves when it is deregistered.
  (with-handle
    (fn [handle _]
      (let [ag (rt/agent-state handle)
            before (fn [ctx] (@(:before-tool-call ag) ctx))
            after (fn [ctx] (@(:after-tool-call ag) ctx))
            owner (extensions/extension-owner "test-tool-hooks")
            hook-call (fn [{:keys [tool-name args]}]
                        (if (= "kmet-test-danger" tool-name)
                          {:block true :reason "not that one" :terminate true}
                          {:args (assoc args :patched true)}))
            hook-result (fn [{:keys [result]}]
                          {:content (str "wrapped:" (:content result))
                           :is-error false})
            dereg-call (extensions/register-tool-call-hook! owner hook-call)
            dereg-result (extensions/register-tool-result-hook! owner hook-result)]
        (try
          (is (= {:block true :reason "not that one" :terminate true}
                 (before {:tool-name "kmet-test-danger" :args {}}))
              "a tool-call hook can block a call and says why")
          (is (= {:block {:content "not that one" :is-error true :terminate true}}
                 (invoke/prepare-tool-call
                  {:before-hook @(:before-tool-call ag)
                   :tool-name "kmet-test-danger"
                   :args {}}))
              "its :terminate reaches the call's block result (extension.md:916)")
          (is (true? (:patched (:args (before {:tool-name "read" :args {}}))))
              "or rewrite its arguments")
          (is (= "wrapped:ok"
                 (:content (after {:tool-name "read"
                                   :result {:content "ok" :is-error false}})))
              "a tool-result hook rewrites the result")
          (finally (dereg-call) (dereg-result)))
        (is (not-any? #(identical? hook-call %)
                      (extensions/get-tool-call-hooks))
            "a deregistered tool-call hook leaves the registry")
        (is (not-any? #(identical? hook-result %)
                      (extensions/get-tool-result-hooks))
            "so does a tool-result hook")))))
