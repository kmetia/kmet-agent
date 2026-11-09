(ns kmet.ui.tui.interactive.test-compaction-queue
  "The compaction queue: messages queued while compaction runs, their flush into the
   next prompt, and the status the footer shows meanwhile."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.test-utils :as tu]
            [clojure.string :as str]
            [kmet.app.debug :as debug]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.core :as tui]
            [kmet.ui.tui.interactive.builtins :as builtins]
            [kmet.ui.tui.interactive.layout :as layout]
            [kmet.ui.tui.interactive.ui-registry :as ui-registry]
            [kmet.ui.tui.interactive.state :as state]
            [kmet.ui.tui.interactive.status :as status]
            [kmet.ui.tui.interactive.turn :as turn]
            [kmet.app.commands :as commands]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.app.compaction :as agent-compact]
            [kmet.app.loop.state :as loop-state]
            [kmet.app.loop :as agent]
            [kmet.app.session.compaction :as manual]
            [kmet.app.session.runtime :as runtime]
            [kmet.ui.tui.footer-data-provider :as fdp]
            [kmet.app.config :as cfg]
            [kmet.ui.tui.interactive.test-handle-support :as hs]
            [kmet.ui.tui.interactive.test-support :as support]))

;; The interactive builtins register the app's whole slash registry; clear it
;; so a later namespace never sees the tree filled by this one.
(t/use-fixtures :each hs/with-boot tu/with-clean-app-registries)

;; ─── Compaction queue (pi: queueCompactionMessage / flushCompactionQueue) ──

(deftest test-bash-already-running-restores-the-command
  (testing "pi: a bash submit while another command runs warns and puts the
            typed command back into the editor (the submit wiring cleared
            it before dispatch), so it can be re-submitted after cancelling
            the running one"
    (let [cs (support/compaction-cs)
          ed @(:current-editor-atom cs)
          msgs (atom [])]
      (editor/editor-set-text! ed "")
      (reset! (:bash-running? cs) true)
      (with-redefs [chat-history/chat-history-add-message! (fn [_ m] (swap! msgs conj m))
                    tui/tui-request-render (fn [_] nil)]
        ((var turn/handle-submit) cs "!ls -la"))
      (t/is (= "!ls -la" (editor/editor-get-text ed))
            "the typed command is back in the editor")
      (t/is (= ["A bash command is already running. Cancel it first."]
               (mapv :content @msgs))
            "the warning is the only chat output"))))

(deftest test-submit-history-targets-the-active-editor
  (testing "submitted text lands in the ACTIVE editor's Up/Down history
            (pi: this.editor.addToHistory) — a custom editor swapped in via
            ui-set-editor-component gets the history, not the default editor"
    (let [cs (support/compaction-cs)
          default-ed (:editor cs)
          custom-ed (editor/make-editor)]
      (reset! (:current-editor-atom cs) custom-ed)
      (reset! (:compacting? (runtime/agent-state (:engine cs))) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)]
        ((var turn/handle-submit) cs "queued draft"))
      (t/is (= ["queued draft"] (editor/editor-get-history custom-ed)))
      (t/is (empty? (editor/editor-get-history default-ed))
            "the default editor's history is untouched"))))

(deftest test-submit-queues-during-compaction
  (testing "a plain message submitted during compaction queues as steer
            (pi: onSubmit → isCompacting → queueCompactionMessage(text, steer))"
    (let [cs (support/compaction-cs)]
      (reset! (:compacting? (runtime/agent-state (:engine cs))) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)]
        ((var turn/handle-submit) cs "hello during compaction"))
      (t/is (= [{:text "hello during compaction" :mode :steer}]
               (runtime/compaction-queued (:engine cs)))
            "message queued with steer mode")
      (t/is (= ["hello during compaction"]
               @(:steering-atom (:pending-messages-comp cs)))
            "queued message shows in the pending steering display (pi:
             queueCompactionMessage → updatePendingMessagesDisplay)")
      (t/is (empty? @(:messages (runtime/agent-state (:engine cs))))
            "message NOT sent to the agent"))))

(deftest test-follow-up-queues-during-compaction
  (testing "Alt+Enter during compaction queues as follow-up
            (pi: handleFollowUp → queueCompactionMessage(text, followUp))"
    (let [cs (support/compaction-cs)
          ed @(:current-editor-atom cs)]
      (editor/editor-set-text! ed "later message")
      (reset! (:compacting? (runtime/agent-state (:engine cs))) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)]
        ((var turn/handle-follow-up) cs))
      (t/is (= [{:text "later message" :mode :follow-up}]
               (runtime/compaction-queued (:engine cs)))
            "message queued with follow-up mode")
      (t/is (= ["later message"]
               @(:follow-up-atom (:pending-messages-comp cs)))
            "queued follow-up shows in the pending display (pi:
             queueCompactionMessage → updatePendingMessagesDisplay)"))))

(deftest test-follow-up-expands-pasted-text
  (let [cs (support/compaction-cs)
        ed @(:current-editor-atom cs)
        text (apply str (repeat 1200 "x"))]
    (tui/handle-input ed (str "\u001b[200~" text "\u001b[201~"))
    (reset! (:compacting? (runtime/agent-state (:engine cs))) true)
    (with-redefs [tui/tui-request-render (fn [_] nil)]
      (turn/handle-follow-up cs))
    (t/is (= [{:text text :mode :follow-up}]
             (runtime/compaction-queued (:engine cs))))
    (t/is (= [text] (editor/editor-get-history ed)))
    (t/is (= "" (editor/editor-get-text ed)))))

(deftest test-flush-compaction-queue-prompts-when-idle
  (testing "compaction_end flushes the queue: the first non-extension message
            starts a run when idle, the rest queue (pi: flushCompactionQueue)"
    (let [cs (support/compaction-cs)
          started (atom [])]
      (reset! (:compaction-queued (:engine cs))
              [{:text "first" :mode :steer}
               {:text "second" :mode :follow-up}])
      (with-redefs [agent/run-agent-turn (fn [a opts] (reset! started [a opts]) (future))
                    status/activate-working-indicator! (fn [_] nil)
                    status/start-anim-timer! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    tui/tui-request-render (fn [_] nil)
                    chat-history/chat-history-start-streaming! (fn [_] nil)]
        ((var turn/flush-compaction-queue!) cs false))
      (t/is (seq @started) "a run started for the first message")
      (t/is (= "first" (get-in @started [1 :message])) "run carries the first message")
      (t/is (empty? (runtime/compaction-queued (:engine cs))) "queue drained")
      (let [{:keys [steering follow-up]} (loop-state/queued-messages (runtime/agent-state (:engine cs)))]
        (t/is (= [] steering) "second (follow-up) not steered")
        (t/is (= ["second"] follow-up) "second queued as follow-up into the run")))))

(deftest test-flush-compaction-queue-queues-when-running
  (testing "compaction_end during a running turn (overflow retry) queues every
            message into the turn (pi: flushCompactionQueue willRetry)"
    (let [cs (support/compaction-cs)]
      (reset! (:turn-running? (:engine cs)) true)
      (reset! (:compaction-queued (:engine cs))
              [{:text "steer-msg" :mode :steer}
               {:text "follow-msg" :mode :follow-up}])
      (with-redefs [tui/tui-request-render (fn [_] nil)]
        ((var turn/flush-compaction-queue!) cs true))
      (let [{:keys [steering follow-up]} (loop-state/queued-messages (runtime/agent-state (:engine cs)))]
        (t/is (= ["steer-msg"] steering) "steer-mode queued into steering")
        (t/is (= ["follow-msg"] follow-up) "follow-up-mode queued into follow-up"))
      (t/is (empty? (runtime/compaction-queued (:engine cs))) "queue drained"))))

(deftest test-extension-command-during-compaction-executes
  (testing "extension commands execute immediately during compaction
            (pi: isExtensionCommand → prompt() executes)"
    (commands/clear-commands!)
    (let [cs (support/compaction-cs)
          ran (atom nil)]
      (commands/register-command!
       {:name "my-ext-cmd"
        :description "test"
        :extension-handler (fn [_ctx args] (reset! ran args))})
      (reset! (:compacting? (runtime/agent-state (:engine cs))) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)
                    state/update-footer! (fn [_] nil)]
        ((var turn/handle-submit) cs "/my-ext-cmd arg1"))
      (t/is (= "arg1" @ran) "extension command executed immediately")
      (t/is (empty? (runtime/compaction-queued (:engine cs))) "not queued")
      (commands/clear-commands!))))

(deftest test-restore-queued-includes-compaction-queue
  (testing "Alt+Up / cancel restores the compaction queue alongside the
            session queues (pi: restoreQueuedMessagesToEditor → clearAllQueues)"
    (let [cs (support/compaction-cs)
          ed @(:current-editor-atom cs)]
      (loop-state/steer! (runtime/agent-state (:engine cs)) "steer-msg")
      (reset! (:compaction-queued (:engine cs)) [{:text "compact-msg" :mode :steer}])
      (editor/editor-set-text! ed "draft")
      (let [n ((var turn/restore-queued-messages!) cs)]
        (t/is (= 2 n) "both queues restored")
        (t/is (= "steer-msg\n\ncompact-msg\n\ndraft" (editor/editor-get-text ed))
              "messages combined with current editor text")
        (t/is (empty? (runtime/compaction-queued (:engine cs))) "compaction queue cleared")))))

(deftest test-compaction-end-handler-flushes
  (testing "the :compaction-end event handler flushes the queue"
    (let [cs (support/compaction-cs)
          h ((var layout/make-agent-event-handler)
             {:chat-history (:chat-history cs)
              :tui {:render-requested? (atom false)}
              :cs-ref (atom cs)
              :pending-tool-comps (atom {})})
          started (atom [])]
      (reset! (:compaction-queued (:engine cs)) [{:text "after" :mode :steer}])
      (with-redefs [agent/run-agent-turn (fn [a opts] (reset! started [a opts]) (future))
                    status/activate-working-indicator! (fn [_] nil)
                    status/start-anim-timer! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    tui/tui-request-render (fn [_] nil)
                    chat-history/chat-history-start-streaming! (fn [_] nil)]
        (h {:type :compaction-end :reason :threshold :result true :will-retry false}))
      (t/is (seq @started) "queued message prompted a run after compaction"))))

(deftest test-ui-compact-defers-while-a-turn-runs
  (testing "the extension UI's compact defers mid-turn — pi's compact() aborts
            the running operation first, but kmet's cancel is cooperative, so
            the request is queued and starts when the prompt settles"
    (let [cs (hs/core-state (hs/new-handle) {:config cfg/default-config
                                             :turn-running? true})
          registry ((var ui-registry/build-extension-ui-registry)
                    {:tui nil :cs cs}
                    {:fdp (fdp/make-footer-data-provider)}
                    nil)
          ctx ((:build-context registry))
          errors (atom [])]
      (try
        (with-redefs [agent-compact/compact-context! (fn [& _]
                                                       (throw (ex-info "must not compact" {})))]
          (t/is (nil? ((:compact ctx)
                       {:custom-instructions "focus on tests"
                        :on-error (fn [e] (swap! errors conj (ex-message e)))}))
                "the request is accepted, not refused"))
        (t/is (empty? @errors) "no error reaches the caller")
        (t/is (= ["focus on tests"]
                 (mapv :custom-instructions (runtime/pending-compaction (:engine cs))))
              "the request is queued with its instructions")
        (finally
          (tu/clear-extension-wiring!))))))

(deftest test-ui-compact-reports-the-failure-message
  (testing "a failed compaction reaches the extension caller with pi's
            `Compaction failed: …` message exactly once — :compaction-error
            already carries the reason prefix"
    (let [cs (hs/core-state (hs/new-handle) {:config cfg/default-config
                                             :turn-running? false})
          registry ((var ui-registry/build-extension-ui-registry)
                    {:tui nil :cs cs}
                    {:fdp (fdp/make-footer-data-provider)}
                    nil)
          ctx ((:build-context registry))
          errors (atom [])
          reported (promise)]
      (try
        (with-redefs [agent-compact/compact-context!
                      (fn [ag & _]
                        (reset! (:compaction-error ag)
                                "Compaction failed: Summarization failed: provider exploded")
                        :failed)]
          (t/is (nil? ((:compact ctx)
                       {:on-error (fn [e]
                                    (swap! errors conj (ex-message e))
                                    (deliver reported true))}))
                "a request while idle starts immediately (callbacks carry the outcome)")
          (t/is (true? (deref reported 2000 ::timeout)) "the async failure is reported"))
        (t/is (= ["Compaction failed: Summarization failed: provider exploded"]
                 @errors)
              "the message is reported verbatim, once")
        (finally
          (tu/clear-extension-wiring!))))))

(deftest test-compact-command-logs-an-escaping-error
  (testing "the /compact future logs an exception escaping compact-context!
            (pi: handleCompactCommand catches and ignores — the events carry
            the failure; kmet logs it so a future never swallows a bug)"
    (commands/clear-commands!)
    (let [cs (support/compaction-cs)
          logged (promise)]
      ((var builtins/register-builtin-commands!) cfg/default-config)
      (with-redefs [agent-compact/compact-context! (fn [& _] (throw (ex-info "boom" {})))
                    debug/log-error (fn [& parts] (deliver logged (apply str parts)))]
        ((:handler (commands/find-command "compact")) (support/command-ctx cs) "")
        (t/is (str/includes? (deref logged 2000 "") "boom")
              "the escaping error is logged")))))

(deftest test-compact-command-defers-mid-turn
  (testing "/compact while a turn is running schedules the compaction instead
            of refusing it — the request starts when the prompt settles"
    (commands/clear-commands!)
    (let [cs (support/compaction-cs)
          ran (promise)]
      (try
        ((var builtins/register-builtin-commands!) cfg/default-config)
        (reset! (:turn-running? (:engine cs)) true)
        (with-redefs [agent-compact/compact-context!
                      (fn [_ag instructions reason & _]
                        (deliver ran [instructions reason])
                        true)]
          ((:handler (commands/find-command "compact")) (support/command-ctx cs) "focus")
          (t/is (= ["Compaction scheduled — it will run when the current response finishes."]
                   (mapv :content
                         (filter #(and (= :info (:role %)) (= "Compact" (:label %)))
                                 @(:messages-atom (:chat-history cs)))))
                "the reply says the compaction is scheduled")
          (t/is (not (realized? ran)) "nothing runs while the turn is live")
          (reset! (:turn-running? (:engine cs)) false)
          (manual/start-pending-compactions! (:engine cs))
          (t/is (= ["focus" :manual] (deref ran 2000 nil))
                "the deferred compaction ran after the turn settled"))
        (finally (commands/clear-commands!))))))

(deftest test-cancel-during-compaction-keeps-turn
  (testing "escape during compaction aborts ONLY the compaction — a running
            turn is not cancelled (pi: compaction_start swaps the escape
            handler to abortCompaction; abortCompaction touches only the
            compaction controllers, never the agent run)"
    (let [cs (support/compaction-cs)]
      (reset! (:compacting? (runtime/agent-state (:engine cs))) true)
      (reset! (:signal (runtime/agent-state (:engine cs))) false)
      (reset! (:compaction-signal (runtime/agent-state (:engine cs))) false)
      (reset! (:turn-running? (:engine cs)) true)
      (with-redefs [status/stop-anim-timer! (fn [_] nil)
                    status/clear-status-indicator! (fn [_] nil)
                    state/update-footer! (fn [_] nil)
                    agent/cancel-turn (fn [_] (throw (ex-info "must not cancel the turn" {})))]
        ((var turn/handle-cancel) cs))
      (t/is (true? @(:compaction-signal (runtime/agent-state (:engine cs)))) "compaction aborted via its own signal")
      (t/is (false? @(:signal (runtime/agent-state (:engine cs)))) "the run's cancel signal is untouched")
      (t/is (true? (runtime/turn-running? (:engine cs))) "the turn is NOT cancelled"))))

(deftest test-cancel-idle-compaction-keeps-turn
  (testing "escape during compaction when no turn is running"
    (let [cs (support/compaction-cs)]
      (reset! (:compacting? (runtime/agent-state (:engine cs))) true)
      (reset! (:signal (runtime/agent-state (:engine cs))) false)
      (reset! (:compaction-signal (runtime/agent-state (:engine cs))) false)
      (reset! (:turn-running? (:engine cs)) false)
      (with-redefs [state/update-footer! (fn [_] nil)]
        ((var turn/handle-cancel) cs))
      (t/is (true? @(:compaction-signal (runtime/agent-state (:engine cs)))) "compaction aborted")
      (t/is (false? @(:signal (runtime/agent-state (:engine cs)))) "the run's cancel signal is untouched"))))

(deftest test-cancel-bash-signals-the-running-command
  (testing "escape with a running bash command signals it and drops the
            running flag (the executor returns, the bash future records the
            result)"
    (let [cs (support/compaction-cs)]
      (reset! (:bash-running? cs) true)
      (reset! (:bash-signal cs) false)
      (with-redefs [state/update-footer! (fn [_] nil)]
        ((var turn/handle-cancel) cs))
      (t/is (true? @(:bash-signal cs)) "the running executor is signalled")
      (t/is (false? @(:bash-running? cs)) "the running flag drops"))))

(deftest test-follow-up-extension-command-executes-during-compaction
  (testing "Alt+Enter with an extension command during compaction executes
            immediately (pi: handleFollowUp → isExtensionCommand → prompt)"
    (commands/clear-commands!)
    (let [cs (support/compaction-cs)
          ed @(:current-editor-atom cs)
          ran (atom nil)]
      (commands/register-command!
       {:name "my-fu-cmd"
        :description "test"
        :extension-handler (fn [_ctx args] (reset! ran args))})
      (editor/editor-set-text! ed "/my-fu-cmd arg")
      (reset! (:compacting? (runtime/agent-state (:engine cs))) true)
      (with-redefs [tui/tui-request-render (fn [_] nil)]
        ((var turn/handle-follow-up) cs))
      (t/is (= "arg" @ran) "extension command executed immediately")
      (t/is (empty? (runtime/compaction-queued (:engine cs))) "not queued")
      (commands/clear-commands!))))

