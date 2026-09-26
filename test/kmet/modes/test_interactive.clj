(ns kmet.modes.test-interactive
  "Interactive-mode tests: the agent-loop :on-event handler
   (make-agent-event-handler) must consume every event type in the loop
   vocabulary (kmet.app.event-bus/event-types) without throwing.

   Regression guard: the handler's case previously had no clause for
   :agent-start and no :default — it threw IllegalArgumentException on the
   first event of every run, the exception was swallowed by the run future
   (its catch re-emits :error through the same handler, which also threw),
   and the UI stayed on \"Working...\" forever while print mode kept working."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.modes.interactive :as inter]
            [kmet.modes.interactive.state :as state]
            [kmet.modes.interactive.status :as status]
            [kmet.modes.interactive.turn :as turn]
            [kmet.modes.interactive.session-admin :as session-admin]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.ui.bash-execution :as bash-execution]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.footer :as footer]
            [kmet.app.ui.loaded-resources :as loaded-resources]
            [kmet.app.ui.pending-messages :as pending-messages]
            [kmet.app.ui.status-indicator :as status-indicator]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.app.ui.subs :as subs]
            [kmet.app.packages :as packages]
            [kmet.app.skills :as skills]
            [kmet.app.prompts :as prompts]
            [kmet.libs.context :as context]
            [kmet.libs.host :as host]
            [kmet.app.extensions :as extensions]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.ai.models :as models]
            [kmet.app.keybindings :as app-kb]
            [kmet.config :as cfg]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.core :as tui]
            [kmet.tui.keybindings :as tui-kb]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.app.session-export :as session-export]
            [clojure.string :as str]))

(defn- make-handler
  "The real event handler over a standalone chat history and stub refs: the
   cs-dependent clauses (footer/status indicators) no-op through a nil
   cs-ref; tool-execution events correlate through an empty pending map."
  []
  ((var inter/make-agent-event-handler)
   {:chat-history (chat-history/make-chat-history)
    :tui {:render-requested? (atom false)}
    :cs-ref (atom nil)
    :pending-tool-comps (atom {})}))

(defn- minimal-event
  "Minimal but shape-valid payload for TYPE — the clause bodies run against
   real components, so payloads carry the keys the clauses read."
  [type]
  (case type
    :message-start {:type :message-start :message {:role :user :content "x"}}
    :message-update {:type :message-update
                     :message {:role :assistant :content []}
                     :delta {:type :text :content ""}}
    :message-end {:type :message-end :message {:role :assistant :content ""}}
    :tool-execution-start {:type :tool-execution-start
                           :tool-call-id "t1" :tool-name "bash" :args {}}
    :tool-execution-update {:type :tool-execution-update
                            :tool-call-id "t1" :content "x" :is-partial true}
    :tool-execution-end {:type :tool-execution-end
                         :tool-call-id "t1" :tool-name "bash"
                         :args {} :result {:content "x"}}
    :turn-end {:type :turn-end
               :message {:role :assistant :content ""} :tool-results []}
    :compaction-end {:type :compaction-end :reason :threshold :result true}
    :context-replaced {:type :context-replaced :messages []}
    :auto-retry-end {:type :auto-retry-end :success true :attempt 1}
    :loop-guard {:type :loop-guard :reason :tool-calls
                 :details "Stopped: repeated identical tool calls"}
    ;; the remaining vocabulary events carry only :type
    {:type type}))

(deftest header-logo-names-the-host
  (testing "the welcome header logo names the hosting runtime after kmet"
    (let [logo ((var state/fmt-header-logo))]
      (is (str/includes? logo (str "kmet (" (host/runtime-name) ")"))
          "logo reads 'kmet (babashka)' / 'kmet (jolt)'"))))

(deftest handle-new-session-clears-context
  (testing "/new (pi: handleClearCommand → runtimeHost.newSession) swaps in a
            fresh session and rebuilds the agent's in-memory context from it
            — the old conversation must never reach the next LLM call"
    (let [sess-dir (str "target/test-interactive-new-session-" (System/currentTimeMillis))
          old-sess (session/create-session sess-dir)
          ag (agent/make-agent-state :session old-sess)
          _ (swap! (:messages ag) conj {:role :user :content "old message"})
          _ (swap! (:messages ag) conj {:role :assistant :content "old reply"})
          tui-stub {:render-requested? (atom false)}
          ch (chat-history/make-chat-history)
          fdp (fdp/make-footer-data-provider :session old-sess)
          ftr (footer/make-footer :provider fdp)
          ed (editor/make-editor)
          _ (editor/editor-set-text! ed "draft")
          parked-bash (bash-execution/make-bash-execution :command "sleep 10")
          pending-bash (atom [parked-bash])
          cs (inter/map->CoreState
              {:tui tui-stub
               :agent-state (atom ag)
               :chat-history ch
               :editor ed
               :current-editor-atom (atom ed)
               :anim-timer (atom nil)
               :running-turn? (atom false)
               :compaction-queued (atom [])
               :bash-running? (atom false)
               :bash-signal (atom false)
               :session-atom (atom old-sess)
               :pending-messages-container (container/make-container)
               :pending-bash-components pending-bash
               :pending-messages-comp (pending-messages/make-pending-messages)
               :status-current (atom nil)
               :status-root nil
               :status-indicator (status-indicator/make-status-indicator)
               :footer-comp ftr
               :footer-provider fdp})
          shutdown-events (atom [])
          _ (event-bus/clear-event-listeners!)
          _ (event-bus/on-event :session-shutdown
                                (fn [ev] (swap! shutdown-events conj ev)))]
      (try
        ((var session-admin/handle-new-session) cs)
        (let [ag' @(:agent-state cs)
              new-sess @(:session-atom cs)]
          (is (not= (:id old-sess) (:id new-sess)) "a fresh session is created")
          (is (= new-sess (:session ag')) "agent points at the new session")
          (is (empty? @(:messages ag'))
              "the old conversation is not carried into the new session")
          (is (= :idle @(:status ag')) "agent status back to :idle")
          (is (str/blank? (editor/editor-get-text ed))
              "editor cleared (pi: editor.setText(\"\"))")
          (is (= [{:type :session-shutdown :reason :new
                   :target-session-file (:file old-sess)}]
                 @shutdown-events)
              "extensions are told the runtime is torn down before the swap (pi: teardownCurrent)")
          (is (true? @(:done-atom parked-bash)) "/new stops the parked bash frame driver")
          (is (nil? @(:ticker-id-atom parked-bash)) "/new cancels the parked driver timer")
          (is (nil? @(:elapsed-ticker-id-atom parked-bash)) "/new cancels the parked elapsed ticker")
          (is (empty? @pending-bash) "parked bash refs are dropped"))
        (finally
          (event-bus/clear-event-listeners!)
          (fs/delete-tree sess-dir))))))

(deftest handle-new-session-extension-cancel
  (testing "a {:cancel true} :session-before-switch handler aborts /new —
            the session, agent context and editor stay untouched and no
            :session-shutdown fires (pi: emitBeforeSwitch → cancel)"
    (let [sess-dir (str "target/test-interactive-new-session-cancel-"
                        (System/currentTimeMillis))
          old-sess (session/create-session sess-dir)
          ag (agent/make-agent-state :session old-sess)
          _ (swap! (:messages ag) conj {:role :user :content "old message"})
          _ (swap! (:messages ag) conj {:role :assistant :content "old reply"})
          tui-stub {:render-requested? (atom false)}
          ch (chat-history/make-chat-history)
          ed (editor/make-editor)
          _ (editor/editor-set-text! ed "draft")
          cs (inter/map->CoreState
              {:tui tui-stub
               :agent-state (atom ag)
               :chat-history ch
               :editor ed
               :current-editor-atom (atom ed)
               :anim-timer (atom nil)
               :running-turn? (atom false)
               :compaction-queued (atom [])
               :bash-running? (atom false)
               :bash-signal (atom false)
               :session-atom (atom old-sess)
               :pending-messages-container (container/make-container)
               :pending-bash-components (atom [])
               :status-current (atom nil)
               :status-root nil
               :status-indicator (status-indicator/make-status-indicator)
               :footer-comp nil
               :footer-provider nil})
          shutdown-events (atom [])
          seen-before-switch (atom nil)
          _ (event-bus/clear-event-listeners!)
          _ (event-bus/on-event :session-before-switch
                                (fn [ev]
                                  (reset! seen-before-switch ev)
                                  {:cancel true}))
          _ (event-bus/on-event :session-shutdown
                                (fn [ev] (swap! shutdown-events conj ev)))]
      (try
        ((var session-admin/handle-new-session) cs)
        (is (= old-sess @(:session-atom cs)) "session NOT swapped")
        (is (= old-sess (:session @(:agent-state cs))) "agent still points at the old session")
        (is (= 2 (count @(:messages @(:agent-state cs)))) "conversation untouched")
        (is (= "draft" (editor/editor-get-text ed)) "editor untouched")
        (is (= :new (:reason @seen-before-switch)) "before-switch carries the reason")
        (is (empty? @shutdown-events) "no :session-shutdown on a cancelled switch")
        (finally
          (event-bus/clear-event-listeners!)
          (fs/delete-tree sess-dir))))))

(deftest agent-event-handler-consumes-vocabulary
  (testing "every loop event type is consumed by the UI handler without throwing"
    (let [h (make-handler)
          types (keys event-bus/loop-event-types)]
      (is (seq types) "loop-event-types must not be empty")
      (doseq [type types]
        (testing (name type)
          ;; an unhandled case clause throws — which the run future swallows,
          ;; hanging the UI; the handler call must simply not throw (clause
          ;; bodies legitimately return truthy values)
          (h (minimal-event type)))))))

(deftest agent-event-handler-default-is-safe
  (testing "unknown event types fall through the :default clause"
    (is (nil? ((make-handler) {:type :some-future-event})))))

(deftest tool-execution-parallel-correlation
  (testing "parallel tool calls each own a component; end events correlate by id
            and clear their own elapsed ticker (pi: pendingTools Map)"
    (let [pending (atom {})
          h ((var inter/make-agent-event-handler)
             {:chat-history (chat-history/make-chat-history)
              :tui {:render-requested? (atom false)}
              :cs-ref (atom nil)
              :pending-tool-comps pending})]
      (h {:type :tool-execution-start :tool-call-id "t1" :tool-name "bash"
          :args {:command "sleep 5"}})
      (h {:type :tool-execution-start :tool-call-id "t2" :tool-name "bash"
          :args {:command "sleep 5"}})
      (let [comp1 (get @pending "t1")
            comp2 (get @pending "t2")]
        (is (some? comp1) "first tool component tracked")
        (is (some? comp2) "second tool component tracked")
        (is (not (identical? comp1 comp2))
            "each parallel tool call owns its own component")
        ;; render comp1 → its bash render-result starts the 100ms ticker
        (protocols/render comp1 60)
        (is (some? (:timer-id @(:renderer-state-atom comp1)))
            "t1 elapsed ticker running")
        ;; partial update for t1 reaches only comp1
        (h {:type :tool-execution-update :tool-call-id "t1" :content "chunk"
            :is-partial true})
        (is (= "chunk" @(:content-atom comp1)) "t1 got its chunk")
        (is (= "" @(:content-atom comp2)) "updates correlate by id")
        ;; t1 ends (error) — its own ticker is cleared, comp2 untouched
        (h {:type :tool-execution-end :tool-call-id "t1" :tool-name "bash"
            :args {} :result {:content "Command aborted" :is-error true}
            :is-error true})
        (is (nil? (get @pending "t1")) "ended tool removed from pending")
        (is (some? (get @pending "t2")) "other tool stays pending")
        (is (some? @(:ended-at-atom comp1)) "t1 marked ended")
        (is (nil? (:timer-id @(:renderer-state-atom comp1)))
            "t1 elapsed ticker cleared by its own end")
        (is (nil? @(:ended-at-atom comp2)) "t2 still running")
        ;; t2 ends normally
        (h {:type :tool-execution-end :tool-call-id "t2" :tool-name "bash"
            :args {} :result {:content "ok" :is-error false}
            :is-error false})
        (is (empty? @pending)
            "all tools removed from pending after their end events")))))

(deftest fast-parallel-tool-completions-request-a-frame
  (testing "parallel tools that finish before their first frame still request a completion frame
            (their new components have no track! watches yet)"
    (let [render-requested? (atom false)
          pending (atom {})
          h ((var inter/make-agent-event-handler)
             {:chat-history (chat-history/make-chat-history)
              :tui {:render-requested? render-requested?}
              :cs-ref (atom nil)
              :pending-tool-comps pending})]
      (doseq [[id command] [["t1" "command-one"] ["t2" "command-two"]]]
        (h {:type :tool-execution-start
            :tool-call-id id :tool-name "bash" :args {:command command}}))
      ;; Model the mount frame consuming the start request before either
      ;; tool finishes; neither component has rendered, so neither owns a
      ;; track! watch that can request its completion frame.
      (reset! render-requested? false)
      (h {:type :tool-execution-update :tool-call-id "t1"
          :content "partial" :is-partial true})
      (is (true? @render-requested?)
          "a pre-mount update also requests its frame")
      (reset! render-requested? false)
      (let [components (select-keys @pending ["t1" "t2"])]
        (doseq [[id content] [["t1" "result-one"] ["t2" "result-two"]]]
          (h {:type :tool-execution-end
              :tool-call-id id :tool-name "bash"
              :args {}
              :result {:content content :is-error false}
              :is-error false}))
        (is (true? @render-requested?)
            "the completion frame is requested even before the components first render")
        (is (every? #(some? @(:ended-at-atom %)) (vals components))
            "both fast tools are marked complete")
        (doseq [[id content] [["t1" "result-one"] ["t2" "result-two"]]]
          (is (str/includes? (str/join "\n" (protocols/render (get components id) 60))
                             content)
              (str id " renders its result")))))))

(deftest loop-guard-message-requests-frame
  (testing "the loop-guard warning requests a frame after appending to the chat"
    (let [render-requested? (atom false)
          chat-history (chat-history/make-chat-history)
          h ((var inter/make-agent-event-handler)
             {:chat-history chat-history
              :tui {:render-requested? render-requested?}
              :cs-ref (atom nil)
              :pending-tool-comps (atom {})})]
      (h {:type :loop-guard
          :reason :tool-calls
          :details "Stopped: repeated identical tool calls"})
      (is (true? @render-requested?))
      (is (= [{:role :warning
               :content "Stopped: repeated identical tool calls"}]
             (mapv #(select-keys % [:role :content])
                   @(:messages-atom chat-history)))))))

(deftest share-completion-requests-frame-after-message
  (testing "the async share reply requests a frame after appending its chat message"
    (let [dir (str (fs/create-temp-dir {:prefix "test-share-render-" :dir "target"}))
          sess (session/create-session dir)
          render-requested? (atom false)
          request-message-counts (atom [])
          completed (promise)
          tui* {:render-requested? render-requested?
                :running? (atom false)}
          cs {:session-atom (atom sess)
              :chat-history (chat-history/make-chat-history)
              :tui tui*
              :status-indicator (status-indicator/make-status-indicator)
              :status-current (atom nil)
              :running-turn? (atom false)}]
      (try
        (with-redefs [proc/process
                      (fn [& _]
                        (future {:exit 0 :out "https://gist.example\n" :err ""}))
                      session-export/export-to-html! (fn [& _] nil)
                      tui/tui-request-render
                      (fn [_]
                        (reset! render-requested? true)
                        (swap! request-message-counts conj
                               (count @(:messages-atom (:chat-history cs))))
                        (deliver completed true))]
          ((var inter/share-session!) cs)
          (is (true? (deref completed 2000 false))
              "the share worker completed")
          (is (some #(str/includes? (str (:content %)) "https://gist.example")
                    @(:messages-atom (:chat-history cs)))
              "the share URL is present")
          (is (some pos? @request-message-counts)
              "the share request was made after the reply was appended"))
        (finally
          (fs/delete-tree dir))))))

(deftest custom-entry-sink-requests-frame-after-append
  (testing "a rendered custom entry requests a frame after appending to chat"
    (let [dir (str (fs/create-temp-dir {:prefix "test-entry-render-" :dir "target"}))
          sess (session/create-session dir)
          render-requested? (atom false)
          request-message-counts (atom [])
          tui* {:render-requested? render-requested?}
          chat-history (chat-history/make-chat-history)
          cs {:tui tui*
              :chat-history chat-history
              :session-atom (atom sess)
              :agent-state (atom (agent/make-agent-state))}
          dereg (extensions/register-entry-renderer!
                 "test-entry-render"
                 (fn [_]
                   {:role :info :label "Entry" :content "rendered entry"}))]
      (try
        ((var inter/build-extension-ui-registry)
         {:tui tui* :cs cs}
         {:ch chat-history}
         nil)
        (extensions/set-session! sess)
        (with-redefs [tui/tui-request-render
                      (fn [_]
                        (reset! render-requested? true)
                        (swap! request-message-counts conj
                               (count @(:messages-atom chat-history))))]
          (extensions/append-custom-entry! "test-entry-render" {}))
        (is (true? @render-requested?))
        (is (some #(and (= :info (:role %))
                        (= "rendered entry" (:content %)))
                  @(:messages-atom chat-history)))
        (is (some pos? @request-message-counts)
            "the entry request was made after the message was appended")
        (finally
          (dereg)
          (extensions/set-session! nil)
          (extensions/set-context-sink! nil)
          (extensions/set-entry-sink! nil)
          (extensions/clear-ui-registry!)
          (fs/delete-tree dir))))))

(deftest replay-branch-restores-tool-rendering
  (testing "replaying a session branch restores tool executions with their
            real name + args: the components are created from the assistant
            message's :tool-calls and results are matched by tool-call id
            (pi: renderSessionItems + renderedPendingTools). Regression: the
            replay read :name from tool entries that are saved with the
            pi-faithful :tool-name key, so every tool rendered as \"tool\"
            through the default renderer — no bash call line, full output
            with no collapsing"
    (let [sess-dir (str "target/test-interactive-replay-tools-" (System/currentTimeMillis))
          sess (session/create-session sess-dir)]
      (try
        (session/append-entry sess {:role :user :content "run the tests"})
        (session/append-entry sess
                              {:role :assistant
                               :content [{:type :text :text "Running..."}]
                               :tool-calls [{:id "call_1" :name "bash"
                                             :arguments {:command "ls -la"}}
                                            {:id "call_2" :name "bash"
                                             :arguments {:command "echo hi"}}]})
        ;; results arrive out of order (parallel tools)
        (session/append-entry sess
                              {:role :tool
                               :content [{:type :tool_result :tool_use_id "call_2"
                                          :content "hi"}]
                               :tool-name "bash" :is-error false})
        (session/append-entry sess
                              {:role :tool
                               :content [{:type :tool_result :tool_use_id "call_1"
                                          :content (str/join "\n" (range 20))}]
                               :tool-name "bash" :is-error false})
        (session/append-entry sess {:role :assistant
                                    :content [{:type :text :text "Done."}]})
        (let [loaded (session/load-session (:file sess))
              ch (chat-history/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})]
          ((var session-admin/replay-branch!) cs loaded)
          (let [msgs @(:messages-atom ch)
                tools (filterv #(= :tool (:role %)) msgs)
                [t1 t2] tools]
            (is (= 2 (count tools)) "one component per tool call")
            (is (= "bash" (:name t1)) "tool name restored from the call")
            (is (= "bash" (:name t2)) "second call too")
            ;; collapsed bash: call line + 5-line preview + expand hint
            (let [lines (protocols/render (:component t1) 100)]
              (is (some #(str/includes? % "$ ls -la") lines)
                  "call line shows the restored command")
              (is (some #(str/includes? % "to toggle") lines)
                  "collapsed preview shows the expand hint")
              (is (< (count lines) 30)
                  "collapsed output is truncated, not the full 20 lines")
              (is (not-any? #(str/includes? % "Took") lines)
                  "replayed tools show no fabricated duration (pi: startedAt stays undefined)"))
            (is (str/includes? (str/join "\n" (protocols/render (:component t2) 100)) "hi")
                "result content matched to the right call by id")))
        (finally (fs/delete-tree sess-dir))))))

(deftest replay-branch-restores-user-images
  (testing "user-attached images survive replay: content-of flattens the
            entry's blocks to text, so the image blocks ride the message's
            :images and the image element renders (regression: resume showed
            text only while the live path rendered the images)"
    (let [sess-dir (str "target/test-interactive-replay-images-" (System/currentTimeMillis))
          sess (session/create-session sess-dir)]
      (try
        (session/append-entry sess
                              {:role :user
                               :content [{:type :text :text "see:"}
                                         {:type :image :data "AA" :mime-type "image/png"}]})
        ;; session files are created lazily on the first assistant message (G4)
        (session/append-entry sess {:role :assistant
                                    :content [{:type :text :text "ok"}]})
        (let [loaded (session/load-session (:file sess))
              ch (chat-history/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})
              prev-caps (timg/get-capabilities)]
          (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
          (try
            ((var session-admin/replay-branch!) cs loaded)
            (let [msg (first @(:messages-atom ch))
                  lines (protocols/render (:component msg) 60)]
              (is (some? msg))
              (is (= [{:data "AA" :mime-type "image/png"}] (:images msg))
                  "image blocks ride the replayed message's :images")
              (is (str/includes? (str/join "\n" lines) "[Image: [image/png]")
                  "the image element renders (text indicator without protocol support)")
              (is (str/includes? (str/join "\n" lines) "see:")
                  "the text part is unchanged"))
            (finally (timg/set-capabilities! prev-caps))))
        (finally (fs/delete-tree sess-dir))))))

(deftest replay-branch-marks-errored-tool-calls
  (testing "tool calls inside an errored assistant entry render with the
            failure text instead of waiting for a result that never came
            (pi: renderInitialMessages updateResult error for stopReason
            error/aborted messages)"
    (let [sess-dir (str "target/test-interactive-replay-errored-" (System/currentTimeMillis))
          sess (session/create-session sess-dir)]
      (try
        (session/append-entry sess {:role :user :content "go"})
        (session/append-entry sess
                              {:role :assistant
                               :content [{:type :text :text "partial answer"}]
                               :tool-calls [{:id "call_x" :name "bash"
                                             :arguments {:command "ls"}}]
                               :stop-reason :error
                               :error-message "upstream connect error"})
        (let [loaded (session/load-session (:file sess))
              ch (chat-history/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})]
          ((var session-admin/replay-branch!) cs loaded)
          (let [msgs @(:messages-atom ch)
                tools (filterv #(= :tool (:role %)) msgs)]
            (is (= 1 (count tools)) "one component for the dangling call")
            (let [lines (protocols/render (:component (first tools)) 100)]
              (is (some #(str/includes? % "upstream connect error") lines)
                  "failure text shown as the result"))))
        (finally (fs/delete-tree sess-dir))))))

(deftest replay-branch-marks-aborted-tool-calls
  (testing "an aborted assistant entry's dangling tool calls render as
            aborted rather than pending (pi: \"Operation aborted\" on
            restore of stopReason-aborted messages)"
    (let [sess-dir (str "target/test-interactive-replay-aborted-" (System/currentTimeMillis))
          sess (session/create-session sess-dir)]
      (try
        (session/append-entry sess {:role :user :content "go"})
        (session/append-entry sess
                              {:role :assistant
                               :content [{:type :text :text "partial answer"}]
                               :tool-calls [{:id "call_a" :name "bash"
                                             :arguments {:command "ls"}}]
                               :stop-reason :aborted})
        (let [loaded (session/load-session (:file sess))
              ch (chat-history/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})]
          ((var session-admin/replay-branch!) cs loaded)
          (let [tools (filterv #(= :tool (:role %)) @(:messages-atom ch))]
            (is (= 1 (count tools)))
            (is (some #(str/includes? % "Aborted")
                      (protocols/render (:component (first tools)) 100))
                "aborted wording shown as the result")))
        (finally (fs/delete-tree sess-dir))))))

(deftest submit-command-line-gate
  (testing "multiline submit text (e.g. pasted blocks) is never a command"
    (let [command-line? @#'turn/command-line?]
      ;; single-line slash/bang input stays a command
      (is (true? (command-line? "/model gpt-4o")))
      (is (true? (command-line? "/model")))
      (is (true? (command-line? "!ls -la")))
      (is (true? (command-line? "!!ls -la")))
      ;; multiline input whose first line starts with a command prefix is a
      ;; message, not a command (regression: pasting text starting with
      ;; "/model ..." ran the /model command)
      (is (false? (command-line? "/model gpt-4o\nsecond line")))
      (is (false? (command-line? "/model\n\nrest")))
      (is (false? (command-line? "!ls\n!echo hi"))))))

(deftest replay-branch-restores-unpaired-tool-images
  (testing "an unpaired tool result (no matching call — legacy sessions,
            extension tools) keeps its image blocks on replay"
    (let [sess-dir (str "target/test-interactive-replay-orphan-" (System/currentTimeMillis))
          sess (session/create-session sess-dir)]
      (try
        (session/append-entry sess {:role :assistant
                                    :content [{:type :text :text "ok"}]})
        (session/append-entry sess
                              {:role :tool
                               :content [{:type :tool_result :tool_use_id "orphan"
                                          :content "Read image file [image/png]"}]
                               :tool-name "read" :is-error false
                               :images [{:data "AA" :mime-type "image/png"}]})
        (let [loaded (session/load-session (:file sess))
              ch (chat-history/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})
              prev-caps (timg/get-capabilities)]
          (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
          (try
            ((var session-admin/replay-branch!) cs loaded)
            (let [msg (first (filter #(= :tool (:role %)) @(:messages-atom ch)))
                  lines (protocols/render (:component msg) 60)]
              (is (some? msg))
              (is (= [{:data "AA" :mime-type "image/png"}] (:images msg))
                  "the orphan result carries its image blocks")
              (is (str/includes? (str/join "\n" lines) "[Image: [image/png]")
                  "the orphan result's image renders"))
            (finally (timg/set-capabilities! prev-caps))))
        (finally (fs/delete-tree sess-dir))))))

(deftest replay-branch-rebuilds-bash-executions
  (testing "!/!! entries replay as COMPLETED bash components (pi:
            addMessageToChat case bashExecution): command + output + exit
            status, no spinner driver, no fabricated duration"
    (let [sess-dir (str "target/test-interactive-replay-bash-" (System/currentTimeMillis))
          sess (session/create-session sess-dir)]
      (try
        (session/append-entry sess {:role :user :content "run it"})
        (session/append-entry sess {:role :bash :command "ls -la"
                                    :output "a.txt\nb.txt" :exit-code 0
                                    :cancelled false :truncated false
                                    :exclude-from-context? false})
        (session/append-entry sess {:role :bash :command "false"
                                    :output "" :exit-code 1
                                    :cancelled false :truncated false
                                    :exclude-from-context? true})
        ;; sessions persist lazily — the first assistant message writes the file
        (session/append-entry sess {:role :assistant
                                    :content [{:type :text :text "done"}]})
        (let [loaded (session/load-session (:file sess))
              ch (chat-history/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})]
          ((var session-admin/replay-branch!) cs loaded)
          (let [bashes (filterv #(= :bash (:role %)) @(:messages-atom ch))
                rendered (mapv (fn [m]
                                 (str/join "\n" (protocols/render (:component m) 100)))
                               bashes)]
            (is (= 2 (count bashes)) "both bash entries replay")
            (is (str/includes? (first rendered) "$ ls -la"))
            (is (str/includes? (first rendered) "a.txt"))
            (is (str/includes? (second rendered) "$ false"))
            (is (str/includes? (second rendered) "(exit 1)"))
            (is (not-any? #(str/includes? % "Took") rendered)
                "replayed runs show no fabricated duration")
            (is (every? #(nil? @(:ticker-id-atom (:component %))) bashes)
                "no frame driver stays armed")))
        (finally (fs/delete-tree sess-dir))))))

(deftest replay-branch-uses-the-compaction-aware-context
  (testing "resume replays buildContextEntries (pi: renderInitialMessages):
            summarized history is not re-rendered, and the compaction
            renders as the dedicated collapsible summary component"
    (let [sess-dir (str "target/test-interactive-replay-compaction-" (System/currentTimeMillis))
          sess (session/create-session sess-dir)]
      (try
        (dotimes [i 4]
          (session/append-entry sess {:role :user
                                      :content [{:type :text :text (str "msg " i)}]}))
        (let [keep-id (:id (nth @(:entries sess) 2))]
          (session/compact-with-summary! sess "THE SUMMARY" keep-id {:tokens-before 12345})
          (session/append-entry sess {:role :assistant
                                      :content [{:type :text :text "after"}]})
          (let [loaded (session/load-session (:file sess))
                ch (chat-history/make-chat-history)
                cs (inter/map->CoreState {:chat-history ch})]
            ((var session-admin/replay-branch!) cs loaded)
            (is (= [:compaction :user :user :assistant]
                   (mapv :role @(:messages-atom ch)))
                "only the compaction + kept tail replay")
            (let [rendered (str/join "\n"
                                     (map #(str/join "\n" (protocols/render (:component %) 100))
                                          @(:messages-atom ch)))]
              (is (not (str/includes? rendered "msg 0"))
                  "summarized history is not re-rendered (build-context)")
              (is (not (str/includes? rendered "msg 1")))
              (is (str/includes? rendered "msg 2") "the kept tail stays"))
            (let [comp (:component (first @(:messages-atom ch)))
                  collapsed (str/join "\n" (protocols/render comp 100))]
              (is (= :summary (:kind comp)))
              (is (str/includes? collapsed "[compaction]"))
              (is (str/includes? collapsed "Compacted from 12,345 tokens"))
              (is (not (str/includes? collapsed "THE SUMMARY"))
                  "collapsed by default (pi: setExpanded(toolOutputExpanded))")
              (chat-history/chat-history-set-tool-display-mode! ch :expanded)
              (is (str/includes? (str/join "\n" (protocols/render comp 100))
                                 "THE SUMMARY")
                  "ctrl+o expands it"))))
        (finally (fs/delete-tree sess-dir))))))

(deftest run-message-renderer-falls-back-to-the-default-box
  ;; pi: CustomMessageComponent rebuild — a throwing or empty renderer
  ;; result keeps the default labeled box instead of an empty component
  (let [run #'session-admin/run-message-renderer]
    (is (nil? (run nil {:role :custom})) "no renderer")
    (is (nil? (run (fn [_] nil) {:role :custom})) "nil result")
    (is (nil? (run (fn [_] (throw (ex-info "boom" {}))) {:role :custom}))
        "throwing renderer")
    (is (= {:component :x} (run (fn [_] {:component :x}) {:role :custom})))))

(deftest run-entry-renderer-catches-and-reports
  ;; pi: CustomEntryComponent rebuild — a throwing entry renderer renders
  ;; `[type] renderer failed: message` instead of crashing the replay
  (let [run #'session-admin/run-entry-renderer]
    (is (nil? (run nil {:custom-type :note})) "no renderer")
    (is (nil? (run (fn [_] nil) {:custom-type :note})) "nil result")
    (is (= {:role :notice :style :error
            :content "[note] renderer failed: boom"}
           (run (fn [_] (throw (ex-info "boom" {}))) {:custom-type :note})))
    (is (= {:role :info :content "ok"}
           (run (fn [_] {:role :info :content "ok"}) {:custom-type :note})))))

(deftest renderer-result-wraps-components-not-maps
  ;; all kmet components are records, and records satisfy map? — a plain
  ;; map? test would treat a bare component as a message map (regression:
  ;; the documented bare-component renderer result produced an empty
  ;; fallback instead of the component)
  (let [wrap #'session-admin/renderer-result->message
        comp (container/make-container)]
    (is (identical? comp (:component (wrap comp))))
    (is (= {:role :info :content "x"} (wrap {:role :info :content "x"})))))

(deftest replay-branch-hardens-custom-entry-renderers
  (testing "a throwing entry renderer renders the pi failure line instead of
            crashing the replay; a bare-component result is wrapped"
    (let [component (container/make-container)
          dereg-boom (extensions/register-entry-renderer!
                      "boom-entry" (fn [_] (throw (ex-info "kaput" {}))))
          dereg-comp (extensions/register-entry-renderer!
                      "comp-entry" (fn [_] component))]
      (try
        (let [sess-dir (str "target/test-interactive-entry-rdr-" (System/currentTimeMillis))
              sess (session/create-session sess-dir)]
          (try
            (session/append-entry sess {:role :user :content "q"})
            (session/append-entry sess {:role :custom :custom-type "boom-entry" :data {}})
            (session/append-entry sess {:role :custom :custom-type "comp-entry" :data {}})
            (session/append-entry sess {:role :assistant
                                        :content [{:type :text :text "a"}]})
            (let [loaded (session/load-session (:file sess))
                  ch (chat-history/make-chat-history)
                  cs (inter/map->CoreState {:chat-history ch})]
              ((var session-admin/replay-branch!) cs loaded)
              (let [text (str/join "\n"
                                   (map #(str/join "\n" (protocols/render (:component %) 100))
                                        @(:messages-atom ch)))]
                (is (str/includes? text "[boom-entry] renderer failed: kaput")))
              (is (some #(identical? component (:component %)) @(:messages-atom ch))
                  "a bare component result is wrapped"))
            (finally (fs/delete-tree sess-dir))))
        (finally (dereg-boom) (dereg-comp))))))

(deftest compaction-cost-notice-follows-usage-and-setting
  (let [notice #'session-admin/compaction-cost-notice]
    (with-redefs [cfg/get-show-cache-miss-notices (constantly true)]
      (is (= {:role :notice :style :warning
              :content "Compaction: 12k tokens billed (~$0.01)"}
             (notice nil :compaction {:input 10000 :output 1000
                                      :cache-read 1000 :cache-write 0
                                      :cost {:total 0.012}}))
          "pi: Compaction: <tokens> tokens billed (~$cost) when usage is present")
      (is (nil? (notice nil :compaction nil)) "no usage → no notice"))
    (with-redefs [cfg/get-show-cache-miss-notices (constantly false)]
      (is (nil? (notice nil :compaction {:input 1 :output 1 :cost 0.5}))
          "gated on :show-cache-miss-notices (pi: showCacheMissNotices)"))))

(deftest context-replaced-renders-summary-roles-live
  ;; the live compaction rebuild carries role-preserving context messages,
  ;; so the dedicated summary component renders without a reload
  (let [ch (chat-history/make-chat-history)
        handler ((var inter/make-agent-event-handler)
                 {:chat-history ch
                  :tui {:render-requested? (atom false)}
                  :cs-ref (atom nil)
                  :pending-tool-comps (atom {})})]
    (handler {:type :context-replaced
              :messages [{:role :compaction :summary "SUM" :tokens-before 99}
                         {:role :user :content [{:type :text :text "kept"}]}]})
    (is (= [:compaction :user] (mapv :role @(:messages-atom ch))))
    (is (= :summary (:kind (:component (first @(:messages-atom ch))))))
    (let [collapsed (str/join "\n" (protocols/render (:component (first @(:messages-atom ch))) 100))]
      (is (str/includes? collapsed "[compaction]"))
      (is (not (str/includes? collapsed "SUM")) "collapsed by default"))))

(deftest replay-branch-renders-a-branch-summary-box
  (testing "a branch_summary entry replays as the dedicated collapsible
            branch component (pi: BranchSummaryMessageComponent)"
    (let [sess-dir (str "target/test-interactive-replay-branch-sum-" (System/currentTimeMillis))
          sess (session/create-session sess-dir)]
      (try
        (session/append-entry sess {:role :user :content "q"})
        (session/append-entry sess {:role :branch-summary :summary "BRANCH SUM"})
        (session/append-entry sess {:role :assistant
                                    :content [{:type :text :text "a"}]})
        (let [loaded (session/load-session (:file sess))
              ch (chat-history/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})]
          ((var session-admin/replay-branch!) cs loaded)
          (is (= [:user :branch-summary :assistant] (mapv :role @(:messages-atom ch))))
          (let [comp (:component (second @(:messages-atom ch)))
                collapsed (str/join "\n" (protocols/render comp 100))]
            (is (= :summary (:kind comp)))
            (is (str/includes? collapsed "[branch]"))
            (is (str/includes? collapsed "Branch summary"))
            (is (not (str/includes? collapsed "BRANCH SUM")) "collapsed by default")
            (chat-history/chat-history-set-tool-display-mode! ch :expanded)
            (is (str/includes? (str/join "\n" (protocols/render comp 100)) "BRANCH SUM"))))
        (finally (fs/delete-tree sess-dir))))))

(deftest reload-reseeds-image-settings-and-block-images
  (testing "/reload re-reads the image settings and the provider image-blocking
            knob from the reloaded config (pi: settingsManager.reload)"
    (let [ag (agent/make-agent-state)
          ch (chat-history/make-chat-history)
          cs {:agent-state (atom ag)
              :chat-history ch
              :theme-controller nil
              :loaded-resources-comp nil
              :footer-comp nil
              :config cfg/default-config
              :tui nil
              :system-prompt-opts (atom nil)}
          prev-settings @subs/image-settings-atom]
      (try
        ;; stale live state — the reloaded config must overwrite it
        (reset! subs/image-settings-atom {:show-images false :image-width-cells 5})
        (agent/set-block-images! ag false)
        (with-redefs [cfg/init! (fn [] (assoc cfg/default-config
                                              :terminal {:show-images true :image-width-cells 120}
                                              :images {:block-images true}))
                      ;; the minimal cs has no theme controller — the real
                      ;; set-config! would NPE on the nil controller
                      theme-ctrl/set-config! (fn [_ _] nil)
                      app-kb/reload-agent-keybindings! (fn [] nil)
                      packages/load-extensions! (fn [] [])
                      packages/load-themes! (fn [] nil)
                      models/load-models-config! (fn [] nil)
                      skills/clear-skills! (fn [] nil)
                      prompts/clear-prompt-templates! (fn [] nil)
                      packages/load-skills! (fn [] nil)
                      packages/load-prompts! (fn [] nil)
                      context/load-project-context-files (fn [_ _] [])
                      chat-history/chat-history-set-thinking-hidden! (fn [_ _] nil)
                      loaded-resources/loaded-resources-set-sections! (fn [_ _] nil)
                      extensions/ui-reset! (fn [] nil)
                      extensions/clear-extensions! (fn [] nil)
                      extensions/discover-resources! (fn [_ _] nil)
                      event-bus/emit-event! (fn [_] nil)]
          ((var inter/handle-reload) cs nil))
        (is (= {:show-images true :image-width-cells 120} @subs/image-settings-atom)
            "live image settings re-seeded from the reloaded config")
        (is (true? (:block-images @(:cfg ag)))
            "block-images re-applied to the running agent")
        (finally (reset! subs/image-settings-atom prev-settings))))))

(deftest reload-reseeds-tool-display-mode-into-header-and-resources
  (testing "/reload applies the reloaded tool display mode to the chat, the
            header and the loaded resources — the ctrl+o handler and the
            extension setter keep all three in sync, reload must too"
    (let [ch (chat-history/make-chat-history :tool-display-mode :collapsed)
          hdr (expandable-text/make-expandable-text (constantly "compact")
                                                    (constantly "full"))
          lr (loaded-resources/make-loaded-resources)
          cs {:agent-state (atom (agent/make-agent-state))
              :chat-history ch
              :header-comp hdr
              :loaded-resources-comp lr
              :theme-controller nil
              :footer-comp nil
              :config cfg/default-config
              :tui nil
              :system-prompt-opts (atom nil)}
          prev-settings @subs/image-settings-atom
          reload (fn [mode]
                   (with-redefs [cfg/init! (fn [] (assoc cfg/default-config
                                                         :tool-display-mode mode))
                                 theme-ctrl/set-config! (fn [_ _] nil)
                                 app-kb/reload-agent-keybindings! (fn [] nil)
                                 packages/load-extensions! (fn [] [])
                                 packages/load-themes! (fn [] nil)
                                 models/load-models-config! (fn [] nil)
                                 skills/clear-skills! (fn [] nil)
                                 prompts/clear-prompt-templates! (fn [] nil)
                                 packages/load-skills! (fn [] nil)
                                 packages/load-prompts! (fn [] nil)
                                 context/load-project-context-files (fn [_ _] [])
                                 extensions/ui-reset! (fn [] nil)
                                 extensions/clear-extensions! (fn [] nil)
                                 extensions/discover-resources! (fn [_ _] nil)
                                 event-bus/emit-event! (fn [_] nil)]
                     ((var inter/handle-reload) cs nil)))]
      (try
        (reload :expanded)
        (is (= :expanded (chat-history/chat-history-get-tool-display-mode ch)))
        (is (true? @(:expanded?-atom hdr)) "header follows the reloaded mode")
        (is (true? @(:expanded?-atom lr)) "resources follow the reloaded mode")
        (reload :quiet)
        (is (= :quiet (chat-history/chat-history-get-tool-display-mode ch)))
        (is (false? @(:expanded?-atom hdr)) "quiet projects to collapsed")
        (is (false? @(:expanded?-atom lr)) "quiet projects to collapsed")
        (finally
          (reset! subs/image-settings-atom prev-settings))))))

(deftest reload-keeps-the-runtime-cwd-and-the-launch-context
  (testing "/reload rebuilds the prompt from the launch dir's config: the cwd
            line keeps the session's runtime cwd (a foreign session's — it
            must not revert to the launch dir) and context files keep loading
            from the launch dir, whatever session is active"
    (let [ag (agent/make-agent-state)
          runtime-cwd "/fake/runtime-project"
          cs {:agent-state (atom ag)
              :chat-history (chat-history/make-chat-history)
              :footer-provider (fdp/make-footer-data-provider
                                :cwd-atom (atom runtime-cwd))
              :theme-controller nil
              :loaded-resources-comp nil
              :header-comp nil
              :footer-comp nil
              :config cfg/default-config
              :tui nil}
          loader-cwd (atom nil)]
      (with-redefs [cfg/init! (fn [] cfg/default-config)
                    theme-ctrl/set-config! (fn [_ _] nil)
                    app-kb/reload-agent-keybindings! (fn [] nil)
                    packages/load-extensions! (fn [] [])
                    packages/load-themes! (fn [] nil)
                    models/load-models-config! (fn [] nil)
                    skills/clear-skills! (fn [] nil)
                    prompts/clear-prompt-templates! (fn [] nil)
                    packages/load-skills! (fn [] nil)
                    packages/load-prompts! (fn [] nil)
                    context/load-project-context-files
                    (fn [_ cwd]
                      (reset! loader-cwd cwd)
                      [{:path "AGENTS.md" :content "LAUNCH CONTEXT"}])
                    chat-history/chat-history-set-thinking-hidden! (fn [_ _] nil)
                    loaded-resources/loaded-resources-set-sections! (fn [_ _] nil)
                    extensions/ui-reset! (fn [] nil)
                    extensions/clear-extensions! (fn [] nil)
                    extensions/discover-resources! (fn [_ _] nil)
                    event-bus/emit-event! (fn [_] nil)]
        ((var inter/handle-reload) cs nil))
      (is (= (str (fs/cwd)) @loader-cwd)
          "context files load from the launch dir, not the active session's cwd")
      (is (= runtime-cwd (get-in @(:system-prompt-opts ag) [:cwd]))
          "the rebuilt prompt options carry the runtime cwd")
      (is (str/includes? @(:system ag) (str "Current working directory: " runtime-cwd))
          "the prompt's cwd line does not revert to the launch dir")
      (is (str/includes? @(:system ag) "LAUNCH CONTEXT")
          "the launch dir's context files are in the rebuilt prompt"))))

(deftest heal-stale-scrollback-when-idle-gating
  (testing "input heals a stale scrollback only at a streaming-free moment"
    (let [make (fn [running bash compacting dirty]
                 (inter/map->CoreState
                  {:running-turn? (atom running)
                   :bash-running? (atom bash)
                   :agent-state (atom {:compacting? (atom compacting)})
                   :tui {:scrollback-dirty? (atom dirty)
                         :force-redraw? (atom false)
                         :render-requested? (atom false)}}))
          healed? (fn [cs] (true? @(get-in cs [:tui :force-redraw?])))]
      (let [cs (make false false false true)]
        ((var turn/heal-stale-scrollback-when-idle!) cs)
        (is (healed? cs) "idle + dirty requests the heuristic full redraw"))
      (doseq [[label cs] [["mid agent turn" (make true false false true)]
                          ["mid bash command" (make false true false true)]
                          ["mid compaction" (make false false true true)]]]
        ((var turn/heal-stale-scrollback-when-idle!) cs)
        (is (not (healed? cs)) (str label " must not emit the destructive 3J clear")))
      (let [cs (make false false false false)]
        ((var turn/heal-stale-scrollback-when-idle!) cs)
        (is (not (healed? cs)) "a clean scrollback is a no-op")))))

(deftest request-global-reflow-render-forces
  (testing "an explicit global reflow (toggle) forces the clearing rebuild, streaming or not"
    (doseq [[label running] [["idle" false] ["mid-turn" true]]]
      (let [cs (inter/map->CoreState
                {:running-turn? (atom running)
                 :tui {:force-redraw? (atom false)
                       :render-requested? (atom false)}})]
        ((var turn/request-global-reflow-render!) cs)
        (is (true? @(get-in cs [:tui :force-redraw?]))
            (str label " — the scrollback is rebuilt, not left stale"))
        (is (true? @(get-in cs [:tui :render-requested?]))
            (str label " — and a frame is requested"))))))

(deftest turn-boundary-scrollback-heal
  (testing "the scrollback heal runs at the turn END, not the turn start"
    (let [noop (fn [& _] nil)]
      ;; Turn start: the heal no longer runs here, so the dirt stays for the
      ;; turn end (and a streaming turn cannot emit the clearing redraw).
      (let [cs (inter/map->CoreState
                {:running-turn? (atom false)
                 :agent-state (atom {})
                 :tui {:scrollback-dirty? (atom true)
                       :force-redraw? (atom false)
                       :render-requested? (atom false)}})]
        (with-redefs [agent/run-agent-turn noop
                      status/activate-working-indicator! noop
                      status/start-anim-timer! noop
                      state/update-footer! noop]
          ((var turn/start-agent-run!) cs))
        (is (true? @(:running-turn? cs)) "the turn still starts")
        (is (false? @(get-in cs [:tui :force-redraw?]))
            "a dirty scrollback is left for the turn end, not cleared at the start"))
      ;; Turn end (done and error): heal, but only when streaming-free.
      (doseq [[label call] [["on-agent-done" #((var turn/on-agent-done) %)]
                            ["on-agent-error" #((var turn/on-agent-error) % "boom")]]
              [case-label bash? dirty? expected] [["streaming-free" false true true]
                                                  ["mid bash command" true true false]
                                                  ["clean scrollback" false false false]]]
        (let [cs (inter/map->CoreState
                  {:anim-timer (atom nil)
                   :running-turn? (atom true)
                   :bash-running? (atom bash?)
                   :agent-state (atom {:compacting? (atom false)})
                   :chat-history (chat-history/make-chat-history)
                   :tui {:scrollback-dirty? (atom dirty?)
                         :force-redraw? (atom false)
                         :render-requested? (atom false)}})]
          (with-redefs [status/stop-anim-timer! noop
                        status/clear-status-indicator! noop
                        state/update-footer! noop]
            (call cs))
          (is (= expected @(get-in cs [:tui :force-redraw?]))
              (str label " / " case-label
                   (if expected " heals the dirty scrollback"
                       " must not emit the clearing redraw")))
          (is (false? @(:running-turn? cs)) (str label " ends the turn")))))))

(deftest global-quit-listener
  (testing "app.quit (ctrl+q by default) quits from anywhere and consumes the key"
    (let [prev-global (tui-kb/get-global-keybindings)]
      (try
        (tui-kb/set-global-keybindings! (app-kb/make-agent-keybindings-manager))
        (let [tui-stub {:stopped? (atom false)}
              listener ((var turn/global-quit-listener) tui-stub)
              stopped (atom nil)]
          (with-redefs [tui/tui-stop (fn [t] (reset! stopped t))]
            (is (= {:consume true} (listener "\u0011"))
                "ctrl+q is consumed so the focused component never sees it")
            (is (identical? tui-stub @stopped) "the TUI is stopped")
            (reset! stopped nil)
            (is (nil? (listener "x")) "other keys pass through untouched")
            (is (nil? @stopped) "…and do not stop the TUI")
            (testing "a keybindings.edn override moves the shortcut"
              (tui-kb/set-user-bindings! (tui-kb/get-global-keybindings)
                                         {"app.quit" "alt+x"})
              (is (nil? (listener "\u0011")) "ctrl+q no longer quits")
              (is (= {:consume true} (listener "\u001bx"))
                  "the override key quits"))))
        (finally (tui-kb/set-global-keybindings! prev-global))))))

(deftest session-info-shows-per-tool-usage
  (testing "/session's Tool Results section: per-tool calls + estimated result tokens, highest first, with a TOTAL (script.md T0)"
    (let [sess-dir (str "target/test-interactive-session-info-" (System/currentTimeMillis))
          sess (session/create-session sess-dir)
          pad (fn [n] (apply str (repeat n \a)))]
      (session/append-entry sess {:role :user :content "hi"})
      (session/append-entry sess {:role :tool :tool-name "bash"
                                  :content [{:type :tool_result :tool_use_id "t1"
                                             :content "ab"}]})
      (session/append-entry sess {:role :tool :tool-name "read"
                                  :content [{:type :tool_result :tool_use_id "t2"
                                             :content (pad 400)}]})
      (session/append-entry sess {:role :assistant :content "ok"})
      (let [text ((var inter/session-info-text) sess)]
        (is (str/includes? text "Tool Results")
            "the section names itself (omitted only when there are no tool results)")
        (is (str/includes? text "read:") "read's row is present")
        (is (str/includes? text "1 calls, 100 tokens")
            "read's row carries its call count and estimated tokens (400 chars/4)")
        (is (str/includes? text "Total:") "and a total line")
        (is (str/includes? text "2 calls, 101 tokens")
            "the total covers both tools (400+2 chars → 100+1 tokens)")
        (is (< (str/index-of text "read") (str/index-of text "bash"))
            "rows sort highest token count first"))
      (testing "the section is omitted when the session has no tool results"
        (let [bare (session/create-session (str sess-dir "-bare"))]
          (session/append-entry bare {:role :user :content "hi"})
          (is (not (str/includes? ((var inter/session-info-text) bare) "Tool Results"))))))))
