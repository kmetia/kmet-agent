(ns kmet.ui.tui.interactive.test-interactive
  "Interactive-mode tests: the agent-loop :on-event handler
   (make-agent-event-handler) must consume every event type in the loop
   vocabulary (kmet.app.events/event-types) without throwing.

   Regression guard: the handler's case previously had no clause for
   :agent-start and no :default — it threw IllegalArgumentException on the
   first event of every run, the exception was swallowed by the run future
   (its catch re-emits :error through the same handler, which also threw),
   and the UI stayed on \"Working...\" forever while print mode kept working."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.ui.tui.interactive :as inter]
            [kmet.ui.tui.interactive.builtins :as builtins]
            [kmet.ui.tui.interactive.layout :as layout]
            [kmet.ui.tui.interactive.ui-registry :as ui-registry]
            [kmet.ui.tui.interactive.state :as state]
            [kmet.ui.tui.interactive.status :as status]
            [kmet.ui.tui.interactive.turn :as turn]
            [kmet.ui.tui.interactive.session-admin :as session-admin]
            [kmet.app.commands :as commands]
            [kmet.app.events :as events]
            [kmet.app.extensions.rows :as extension-rows]
            [kmet.app.loop.state :as loop-state]
            [kmet.app.loop :as agent]
            [kmet.app.session.core :as session]
            [kmet.ui.tui.bash-execution :as bash-execution]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.loaded-resources :as loaded-resources]
            [kmet.ui.tui.status-indicator :as status-indicator]
            [kmet.ui.tui.footer-data-provider :as fdp]
            [kmet.ui.tui.subs :as subs]
            [kmet.app.packages :as packages]
            [kmet.app.skills :as skills]
            [kmet.app.prompts :as prompts]
            [kmet.app.project-context :as context]
            [kmet.libs.host :as host]
            [kmet.libs.process :as process]
            [kmet.reakt.core :as reakt]
            [kmet.app.extensions.core :as extensions]
            [kmet.ui.tui.theme-controller :as theme-ctrl]
            [kmet.ai.models :as models]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.config :as cfg]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.tui.components.spinner :as spinner]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.theme :as theme]
            [kmet.tui.core :as tui]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.timers :as timers]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.app.session.export :as session-export]
            [clojure.string :as str]
            [kmet.app.session.runtime :as runtime]
            [kmet.ui.tui.interactive.test-handle-support :as hs]
            [kmet.test-utils :as tu]))

;; The tests that install the interactive builtins leave the app's slash
;; registry filled; clear the process-global registrations between tests.
(clojure.test/use-fixtures :each hs/with-boot tu/with-clean-app-registries)

(defn- make-handler
  "The real event handler over a standalone chat history and stub refs: the
   cs-dependent clauses (footer/status indicators) no-op through a nil
   cs-ref; tool-execution events correlate through an empty pending map."
  []
  ((var layout/make-agent-event-handler)
   {:chat-history (chat-history/make-chat-history :tool-fn (tu/tool-fn))
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
    :tool-execution-prepared {:type :tool-execution-prepared
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
          "logo reads 'kmet (babashka)' / 'kmet (jolt)' / 'kmet (clojure)'"))))

(deftest handle-new-session-clears-context
  (testing "/new (pi: handleClearCommand → runtimeHost.newSession) swaps in a
            fresh session and rebuilds the agent's in-memory context from it
            — the old conversation must never reach the next LLM call"
    (let [handle (hs/new-handle)
          old-sess (runtime/session handle)
          _ (swap! (:messages (runtime/agent-state handle))
                   conj {:role :user :content "old message"})
          _ (swap! (:messages (runtime/agent-state handle))
                   conj {:role :assistant :content "old reply"})
          cs (hs/core-state handle)
          parked-bash (bash-execution/make-bash-execution :command "sleep 10")
          _ (reset! (:pending-bash-components cs) [parked-bash])
          shutdown-events (atom [])
          _ (tu/clear-listeners! (hs/events-ctx handle))
          _ (events/on! (hs/events-ctx handle)
                        :session-shutdown
                        (fn [ev] (swap! shutdown-events conj ev)))]
      (try
           ;; a live turn: /new settles it and the runtime drops its turn flag
           ;; (a cancelled run never reaches the wrapped :on-done)
        (reset! (:turn-running? handle) true)
        ((var session-admin/handle-new-session) cs)
        (is (false? (runtime/turn-running? handle))
            "/new settles the runtime turn flag")
        (let [ag' (runtime/agent-state handle)
              new-sess (runtime/session handle)]
          (is (not= (:id old-sess) (:id new-sess))
              "a fresh session is created")
          (is (= new-sess (:session ag')) "agent points at the new session")
          (is (empty? @(:messages ag'))
              "the old conversation is not carried into the new session")
          (is (= :idle ((:status handle))) "agent status back to :idle")
          (is (str/blank? (editor/editor-get-text (:editor cs)))
              "editor cleared (pi: editor.setText(\"\"))")
          (is (= [{:type :session-shutdown :reason :new
                   :target-session-file (:file old-sess)}]
                 @shutdown-events)
              "extensions are told the runtime is torn down before the swap (pi: teardownCurrent)")
          (is (true? @(:done-atom parked-bash))
              "/new stops the parked bash frame driver")
          (is (nil? @(:ticker-id-atom parked-bash))
              "/new cancels the parked driver timer")
          (is (nil? @(:elapsed-ticker-id-atom parked-bash))
              "/new cancels the parked elapsed ticker")
          (is (empty? @(:pending-bash-components cs))
              "parked bash refs are dropped"))
        (finally
            ;; the fixture deletes the session tree it created
          (tu/clear-listeners! (hs/events-ctx handle)))))))

(deftest handle-new-session-extension-cancel
  (testing "a {:cancel true} :session-before-switch handler aborts /new —
            the session, agent context and editor stay untouched and no
            :session-shutdown fires (pi: emitBeforeSwitch → cancel)"
    (let [handle (hs/new-handle)
          old-sess (runtime/session handle)
          _ (swap! (:messages (runtime/agent-state handle))
                   conj {:role :user :content "old message"})
          _ (swap! (:messages (runtime/agent-state handle))
                   conj {:role :assistant :content "old reply"})
          cs (hs/core-state handle)
          shutdown-events (atom [])
          seen-before-switch (atom nil)
          _ (tu/clear-listeners! (hs/events-ctx handle))
          _ (events/on! (hs/events-ctx handle)
                        :session-before-switch
                        (fn [ev]
                          (reset! seen-before-switch ev)
                          {:cancel true}))
          _ (events/on! (hs/events-ctx handle)
                        :session-shutdown
                        (fn [ev] (swap! shutdown-events conj ev)))]
      (try
        ((var session-admin/handle-new-session) cs)
        (is (= old-sess (runtime/session handle)) "session NOT swapped")
        (is (= 2 (count @(:messages (runtime/agent-state handle))))
            "conversation untouched")
        (is (= "draft" (editor/editor-get-text (:editor cs)))
            "editor untouched")
        (is (= :new (:reason @seen-before-switch))
            "before-switch carries the reason")
        (is (empty? @shutdown-events)
            "no :session-shutdown on a cancelled switch")
        (finally
          (tu/clear-listeners! (hs/events-ctx handle)))))))

(deftest runtime-started-run-gets-the-working-presentation
  (testing "a run the runtime starts without start-agent-run! (a submit! or
            the compaction-queue flush) brings the anim timer and the working
            indicator up on :agent-start — once per run, not per attempt"
    (let [cs (assoc (hs/core-state (hs/new-handle)
                                   {:tui {:render-requested? (atom false) :running? (atom false)}
                                    :chat-history (chat-history/make-chat-history :tool-fn (tu/tool-fn))
                                    :status-indicator (status-indicator/make-status-indicator :text "Working")
                                    :current-editor-atom (atom nil)
                                    :footer-provider (fdp/make-footer-data-provider
                                                      :cwd-atom (atom (str (fs/cwd)))
                                                      :session (session/create-session
                                                                (str "target/test-agent-start-"
                                                                     (System/currentTimeMillis))))})
                    :pending-tool-comps (atom {}))
          h ((var layout/make-agent-event-handler)
             {:chat-history (:chat-history cs)
              :tui (:tui cs)
              :cs-ref (atom cs)
              :pending-tool-comps (atom {})})]
      (reset! (:turn-running? (:engine cs)) true)
      (h {:type :agent-start})
      (is (some? @(:anim-timer cs)) "the animation timer runs")
      (is (status-indicator/status-indicator-active? (:status-indicator cs))
          "the working indicator is up")
      (let [timer @(:anim-timer cs)]
        (h {:type :agent-start})
        (is (identical? timer @(:anim-timer cs))
            "a retry attempt does not restart the presentation")
        (status/stop-anim-timer! cs)))))

(deftest agent-event-handler-consumes-vocabulary
  (testing "every loop event type is consumed by the UI handler without throwing"
    (let [h (make-handler)
          types (keys events/loop-event-types)]
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
          h ((var layout/make-agent-event-handler)
             {:chat-history (chat-history/make-chat-history :tool-fn (tu/tool-fn))
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
        ;; a permission ask may still be up: the block exists, the timer does
        ;; not — the human's thinking time is not the tool's
        (protocols/render comp1 60)
        (is (nil? @(:started-at-atom comp1))
            "an unprepared call has no start stamp")
        (is (nil? (:timer-id @(:renderer-state-atom comp1)))
            "and no elapsed ticker")
        ;; the gate answered: execution begins
        (h {:type :tool-execution-prepared :tool-call-id "t1" :tool-name "bash"
            :args {:command "sleep 5"}})
        (protocols/render comp1 60)
        (is (some? @(:started-at-atom comp1)) "prepared stamps the start")
        (is (some? (:timer-id @(:renderer-state-atom comp1)))
            "t1 elapsed ticker running")
        (is (nil? @(:started-at-atom comp2))
            "the other call is still waiting for its own prepared event")
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
          h ((var layout/make-agent-event-handler)
             {:chat-history (chat-history/make-chat-history :tool-fn (tu/tool-fn))
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
          chat-history (chat-history/make-chat-history :tool-fn (tu/tool-fn))
          h ((var layout/make-agent-event-handler)
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

(deftest share-command-dispatches-auth-status
  (let [registered (atom {})]
    (with-redefs [commands/find-command (constantly nil)
                  commands/register-command! (fn [cmd & _]
                                               (swap! registered assoc (:name cmd) cmd))]
      (builtins/register-builtin-commands! {}))
    (doseq [[auth-status sess expected-content expected-shares]
            [[:ok {:id "session"} nil 1]
             [:ok nil "No active session." 0]
             [:not-installed {:id "session"}
              "GitHub CLI (gh) is not installed. Install it from https://cli.github.com/" 0]
             [:not-logged-in {:id "session"}
              "GitHub CLI is not logged in. Run 'gh auth login' first." 0]
             [:timed-out {:id "session"}
              "GitHub CLI did not respond (timed out)." 0]]]
      (testing (str "auth status " auth-status ", session " (some? sess))
        (let [shared (atom [])
              chat (chat-history/make-chat-history :tool-fn (tu/tool-fn))
              cs (hs/core-state (hs/new-handle)
                                {:session sess
                                 :chat-history chat})]
          (with-redefs [builtins/gh-auth-status (constantly auth-status)
                        builtins/share-session! (fn [cs] (swap! shared conj cs))]
            ((:handler (get @registered "share")) cs ""))
          (is (= (vec (repeat expected-shares cs)) @shared))
          (is (= (if expected-content [expected-content] [])
                 (mapv :content @(:messages-atom chat)))))))))

(deftest share-command-refuses-a-second-share
  (let [registered (atom {})]
    (with-redefs [commands/find-command (constantly nil)
                  commands/register-command! (fn [cmd & _]
                                               (swap! registered assoc (:name cmd) cmd))]
      (builtins/register-builtin-commands! {}))
    (testing "a share already in progress is refused — the background status is
              released kind-gated, so a second flow would take over the slot
              the first one clears"
      (let [shared (atom [])
            chat (chat-history/make-chat-history :tool-fn (tu/tool-fn))
            cs (hs/core-state (hs/new-handle)
                              {:session {:id "session"}
                               :chat-history chat
                               :status-current (atom {:kind :share})})]
        (with-redefs [builtins/gh-auth-status (constantly :ok)
                      builtins/share-session! (fn [cs] (swap! shared conj cs))]
          ((:handler (get @registered "share")) cs ""))
        (is (empty? @shared) "no second upload starts")
        (is (= ["A share is already in progress."]
               (mapv :content @(:messages-atom chat))))))))

(deftest share-completion-requests-frame-after-message
  (testing "the async share reply requests a frame after appending its chat message"
    (let [dir (str (fs/create-temp-dir {:prefix "test-share-render-" :dir "target"}))
          sess (session/create-session dir)
          render-requested? (atom false)
          request-message-counts (atom [])
          completed (promise)
          tui* {:render-requested? render-requested?
                :running? (atom false)}
          cs (hs/core-state (hs/handle-with {:session sess})
                            {:chat-history (chat-history/make-chat-history :tool-fn (tu/tool-fn))
                             :tui tui*
                             :status-indicator (status-indicator/make-status-indicator)
                             :turn-running? false})]
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
          ((var builtins/share-session!) cs)
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
          chat-history (chat-history/make-chat-history :tool-fn (tu/tool-fn))
          cs (hs/core-state (hs/handle-with {:session sess})
                            {:tui tui*
                             :chat-history chat-history})
          dereg (extensions/register-entry-renderer! (extensions/extension-owner "test-entry-render")
                                                     "test-entry-render"
                                                     (fn [_]
                                                       {:role :info :label "Entry" :content "rendered entry"}))]
      (try
        ((var ui-registry/build-extension-ui-registry)
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
          (tu/clear-extension-wiring!)
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
              ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
              cs (inter/map->CoreState {:chat-history ch})]
          ((var session-admin/replay-branch!) cs loaded)
          (let [msgs @(:messages-atom ch)
                tools (filterv #(= :tool (:role %)) msgs)
                [t1 t2] tools]
            (is (= 2 (count tools)) "one component per tool call")
            (is (= "bash" (:tool-name t1)) "tool name restored from the call")
            (is (= "bash" (:tool-name t2)) "second call too")
            ;; collapsed bash: call line + 5-line preview + expand hint
            (let [lines (protocols/render (:component t1) 100)]
              (is (some #(str/includes? % "$ ls -la") lines)
                  "call line shows the restored command")
              (is (some #(str/includes? % "(+") lines)
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
              ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
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

(deftest replay-branch-skips-context-edit-entries
  (testing "a context edit is bookkeeping, not a message: replay renders no
            bubble for it, while the omitted message stays in the transcript
            (pi: context_edit is a settings entry; the edit affects the model
            projection only — raw history, replay and the UI keep the target)"
    (let [sess-dir (str "target/test-interactive-replay-context-edit-" (System/currentTimeMillis))
          sess (session/create-session sess-dir)]
      (try
        (let [u (session/append-entry sess {:role :user :content "ask"})]
          (session/append-entry sess {:role :assistant
                                      :content [{:type :text :text "answer"}]})
          (session/append-context-edit! sess (:id u) nil)
          (let [loaded (session/load-session (:file sess))
                ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
                cs (inter/map->CoreState {:chat-history ch})]
            ((var session-admin/replay-branch!) cs loaded)
            (let [roles (mapv :role @(:messages-atom ch))]
              (is (= [:user :assistant] roles)
                  "the edit renders nothing; the omitted message is still replayed"))))
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
              ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
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
              ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
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
    ;; single-line slash/bang input stays a command
    (is (true? (commands/command-line? "/model gpt-4o")))
    (is (true? (commands/command-line? "/model")))
    (is (true? (commands/command-line? "!ls -la")))
    (is (true? (commands/command-line? "!!ls -la")))
    ;; multiline input whose first line starts with a command prefix is a
    ;; message, not a command (regression: pasting text starting with
    ;; "/model ..." ran the /model command)
    (is (false? (commands/command-line? "/model gpt-4o\nsecond line")))
    (is (false? (commands/command-line? "/model\n\nrest")))
    (is (false? (commands/command-line? "!ls\n!echo hi")))
    ;; the split parses name and args off a single-line command
    (is (= {:name "model" :args "gpt-4o"}
           (commands/parse-command-line "/model gpt-4o")))
    (is (= {:name "help" :args ""} (commands/parse-command-line "/help")))
    (is (nil? (commands/parse-command-line "not a command")))
    (is (nil? (commands/parse-command-line "/model\nnext")))))

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
              ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
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
              ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
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
                ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
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
          dereg-boom (extensions/register-entry-renderer! (extensions/extension-owner "test-entry-boom")
                                                          "boom-entry" (fn [_] (throw (ex-info "kaput" {}))))
          dereg-comp (extensions/register-entry-renderer! (extensions/extension-owner "test-entry-comp")
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
                  ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
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
  (let [ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
        handler ((var layout/make-agent-event-handler)
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

(deftest context-replaced-pairs-tool-calls-with-results-live
  ;; the live compaction rebuild carries role-preserving context messages:
  ;; tool results must re-render with the preceding assistant call's name +
  ;; args (regression: a rebuilt tool box rendered an empty call line/title)
  (let [ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
        handler ((var layout/make-agent-event-handler)
                 {:chat-history ch
                  :tui {:render-requested? (atom false)}
                  :cs-ref (atom nil)
                  :pending-tool-comps (atom {})})]
    (handler {:type :context-replaced
              :messages [{:role :assistant :content ""
                          :tool-calls [{:id "t1" :name "read"
                                        :arguments {:path "src/main.clj"}}]}
                         {:role :tool :tool-name "read" :is-error false
                          :content [{:type :tool_result :tool_use_id "t1"
                                     :content "file body"}]}]})
    (let [tools (filter #(= :tool (:kind (:component %))) @(:messages-atom ch))
          rendered (-> (str/join "\n" (protocols/render (:component (first tools)) 80))
                       (tu/strip-ansi))]
      (is (= 1 (count tools)) "the raw result message is paired, not doubled")
      (is (str/includes? rendered "read src/main.clj")
          "the rebuilt tool component carries the call line"))))

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
              ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
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
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
          cs (hs/core-state handle {:chat-history ch
                                    :config cfg/default-config
                                    :tui nil})
          prev-settings @subs/image-settings-atom]
      (try
        ;; stale live state — the reloaded config must overwrite it
        (reset! subs/image-settings-atom {:show-images false :image-width-cells 5})
        (loop-state/set-block-images! ag false)
        (with-redefs [cfg/init! (fn [] (assoc cfg/default-config
                                              :terminal {:show-images true :image-width-cells 120}
                                              :images {:block-images true}))
                      ;; the minimal cs has no theme controller — the real
                      ;; set-config! would NPE on the nil controller
                      theme-ctrl/set-config! (fn [_ _] nil)
                      app-kb/reload-agent-keybindings! (fn [] nil)
                      extension-rows/reload-all! (fn [] [])
                      packages/resolve-theme-paths (fn [] nil)
                      models/load-models-config! (fn [_] nil)
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
                      events/emit! (fn [& _] nil)]
          ((var builtins/handle-reload) cs nil))
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
          cs (assoc (hs/core-state (hs/new-handle)
                                   {:chat-history ch
                                    :config cfg/default-config
                                    :tui nil})
                    :header-comp hdr
                    :loaded-resources-comp lr)
          prev-settings @subs/image-settings-atom
          reload (fn [mode]
                   (with-redefs [cfg/init! (fn [] (assoc cfg/default-config
                                                         :tool-display-mode mode))
                                 theme-ctrl/set-config! (fn [_ _] nil)
                                 app-kb/reload-agent-keybindings! (fn [] nil)
                                 extension-rows/reload-all! (fn [] [])
                                 packages/resolve-theme-paths (fn [] nil)
                                 models/load-models-config! (fn [_] nil)
                                 skills/clear-skills! (fn [] nil)
                                 prompts/clear-prompt-templates! (fn [] nil)
                                 packages/load-skills! (fn [] nil)
                                 packages/load-prompts! (fn [] nil)
                                 context/load-project-context-files (fn [_ _] [])
                                 extensions/ui-reset! (fn [] nil)
                                 extensions/clear-extensions! (fn [] nil)
                                 extensions/discover-resources! (fn [_ _] nil)
                                 events/emit! (fn [& _] nil)]
                     ((var builtins/handle-reload) cs nil)))]
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
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          runtime-cwd "/fake/runtime-project"
          cs (hs/core-state handle {:chat-history (chat-history/make-chat-history :tool-fn (tu/tool-fn))
                                    :footer-provider (fdp/make-footer-data-provider
                                                      :cwd-atom (atom runtime-cwd))
                                    :config cfg/default-config
                                    :tui nil})
          loader-cwd (atom nil)]
      (with-redefs [cfg/init! (fn [] cfg/default-config)
                    theme-ctrl/set-config! (fn [_ _] nil)
                    app-kb/reload-agent-keybindings! (fn [] nil)
                    extension-rows/reload-all! (fn [] [])
                    packages/resolve-theme-paths (fn [] nil)
                    models/load-models-config! (fn [_] nil)
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
                    events/emit! (fn [& _] nil)]
        ((var builtins/handle-reload) cs nil))
      (is (= (str (fs/cwd)) @loader-cwd)
          "context files load from the launch dir, not the active session's cwd")
      (is (= runtime-cwd (get-in @(:system-prompt-opts ag) [:cwd]))
          "the rebuilt prompt options carry the runtime cwd")
      (is (str/includes? @(:system ag) (str "Current working directory: " runtime-cwd))
          "the prompt's cwd line does not revert to the launch dir")
      (is (str/includes? @(:system ag) "LAUNCH CONTEXT")
          "the launch dir's context files are in the rebuilt prompt"))))

(deftest reload-refuses-while-running
  (testing "/reload refuses while a run is active even when the status atom
            already reads idle — the settle window keeps the run flag true,
            and the run flag is what counts (pi: isStreaming)"
    (let [handle (hs/new-handle)
          ag (runtime/agent-state handle)
          msg (atom nil)
          cs (hs/core-state handle {:turn-running? true
                                    :tui nil})]
      (is (= :idle @(:status ag))
          "precondition: the status atom already reads idle")
      (with-redefs [chat-history/chat-history-add-message! (fn [_ m] (reset! msg m))]
        ((var builtins/handle-reload) cs nil))
      (is (= "Wait for the current response to finish before reloading."
             (:content @msg))
          "refuses while a run is active"))))

(deftest heal-stale-scrollback-when-idle-gating
  (testing "input heals a stale scrollback only at a streaming-free moment"
    (let [make (fn [running bash compacting dirty]
                 (hs/core-state (hs/new-handle)
                                {:turn-running? running
                                 :bash-running? (atom bash)
                                 :compacting? compacting
                                 :tui {:scrollback-dirty? (atom dirty)
                                       :force-redraw? (atom false)
                                       :render-requested? (atom false)
                                       :frame-count (atom 0)
                                       :scrollback-heal-target (atom 0)}}))
          healed? (fn [cs] (true? @(get-in cs [:tui :force-redraw?])))
          latched? (fn [cs] (pos? @(get-in cs [:tui :scrollback-heal-target])))]
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
        (is (not (healed? cs)) "a clean scrollback requests no clearing redraw")
        (is (latched? cs) "but the heal latches, so a frame still to run cannot race it")))))

(deftest request-global-reflow-render-forces
  (testing "an explicit global reflow (toggle) forces the clearing rebuild, streaming or not"
    (doseq [[label running] [["idle" false] ["mid-turn" true]]]
      (let [cs (hs/core-state (hs/new-handle)
                              {:turn-running? running
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
      (let [cs (hs/core-state (hs/new-handle)
                              {:turn-running? false
                               :tui {:scrollback-dirty? (atom true)
                                     :force-redraw? (atom false)
                                     :render-requested? (atom false)}})]
        (with-redefs [agent/run-agent-turn noop
                      status/activate-working-indicator! noop
                      status/start-anim-timer! noop
                      state/update-footer! noop]
          ((var turn/start-agent-run!) cs))
        (is (true? (runtime/turn-running? (:engine cs))) "the turn still starts")
        (is (false? @(get-in cs [:tui :force-redraw?]))
            "a dirty scrollback is left for the turn end, not cleared at the start"))
      ;; Turn end (done and error): heal, but only when streaming-free.
      (doseq [[label call] [["on-agent-done" #((var turn/on-agent-done) %)]
                            ["on-agent-error" #((var turn/on-agent-error) % "boom")]]
              [case-label bash? dirty? expected] [["streaming-free" false true true]
                                                  ["mid bash command" true true false]
                                                  ["clean scrollback" false false false]]]
        (let [cs (hs/core-state (hs/new-handle)
                                {;; the run settles its flag before the
                                 ;; teardown event reaches the UI
                                 :turn-running? false
                                 :bash-running? (atom bash?)
                                 :compacting? false
                                 :chat-history (chat-history/make-chat-history :tool-fn (tu/tool-fn))
                                 :tui {:scrollback-dirty? (atom dirty?)
                                       :force-redraw? (atom false)
                                       :render-requested? (atom false)
                                       :frame-count (atom 0)
                                       :scrollback-heal-target (atom 0)}})]
          (with-redefs [status/stop-anim-timer! noop
                        status/clear-status-indicator! noop
                        state/update-footer! noop]
            (call cs))
          (is (= expected @(get-in cs [:tui :force-redraw?]))
              (str label " / " case-label
                   (if expected " heals the dirty scrollback"
                       " must not emit the clearing redraw")))
          (is (false? (runtime/turn-running? (:engine cs)))
              (str label " leaves the settled flag alone"))))
      ;; …and it never writes the flag: a run that claimed the agent after the
      ;; settle saw it (an extension's settle handler sending a message) keeps
      ;; its claim.
      (let [cs (hs/core-state (hs/new-handle)
                              {:turn-running? true
                               :bash-running? (atom false)
                               :compacting? false
                               :chat-history (chat-history/make-chat-history :tool-fn (tu/tool-fn))
                               :tui {:scrollback-dirty? (atom false)
                                     :force-redraw? (atom false)
                                     :render-requested? (atom false)}})]
        (with-redefs [status/stop-anim-timer! noop
                      status/clear-status-indicator! noop
                      state/update-footer! noop]
          ((var turn/on-agent-done) cs))
        (is (true? (runtime/turn-running? (:engine cs)))
            "the teardown does not clear a newly claimed turn")))))

(deftest an-external-abort-settles-the-presentation
  ;; An abort that did not come through this front end's cancel (an
  ;; extension's cancel, a handle op, a teardown) emits :agent-settled
  ;; :aborted with no prior error or cancel event; without this the run's
  ;; spinner keeps working on an idle agent.
  (let [cs (hs/core-state (hs/new-handle) {:turn-running? false})
        scope {:cs-ref (atom cs) :tui (:tui cs) :chat-history (:chat-history cs)}
        calls (atom [])]
    (with-redefs [turn/on-agent-done (fn [c] (swap! calls conj c))]
      ((var layout/on-run-lifecycle) scope {:type :agent-settled :outcome :aborted})
      (is (= [cs] @calls) "an aborted settle runs the done teardown")
      (reset! calls [])
      ((var layout/on-run-lifecycle) scope {:type :agent-settled :outcome :completed})
      (is (= [cs] @calls) "and a completed one still does")
      (reset! calls [])
      ((var layout/on-run-lifecycle) scope {:type :agent-settled :outcome :error})
      (is (empty? @calls) "an error was torn down by its own event"))))

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

(deftest tool-display-listener
  (testing "app.tools.expand (ctrl+o by default) cycles the tool display while
            the permission ask holds the keys, and consumes the key"
    (let [prev-global (tui-kb/get-global-keybindings)]
      (try
        (tui-kb/set-global-keybindings! (app-kb/make-agent-keybindings-manager))
        (with-redefs [cfg/set-tool-display-mode! (fn [_] nil)]
          (let [ch (chat-history/make-chat-history)
                cs {:tui {:render-requested? (atom false)
                          :force-redraw? (atom false)}
                    :chat-history ch}
                listener ((var turn/tool-display-listener) cs)]
            (is (= :collapsed (chat-history/chat-history-get-tool-display-mode ch)))
            (is (= {:consume true} (listener "\u000f"))
                "ctrl+o is consumed so the dialog never sees it")
            (is (= :quiet (chat-history/chat-history-get-tool-display-mode ch))
                "the transcript behind the dialog cycles")
            (is (nil? (listener "x")) "other keys pass through untouched")
            (testing "a keybindings.edn override moves the cycle key"
              (tui-kb/set-user-bindings! (tui-kb/get-global-keybindings)
                                         {"app.tools.expand" "alt+x"})
              (is (nil? (listener "\u000f")) "ctrl+o no longer cycles")
              (is (= {:consume true} (listener "\u001bx"))
                  "the override key does"))))
        (finally (tui-kb/set-global-keybindings! prev-global))))))

(deftest ^:slow a-cancelled-run-answers-its-open-question
  (testing "a run cancelled while its permission question waits answers it
            with the run-cancelled deny — the dialog must not hold a run that
            is already gone (the poll is a timer thunk, so the close happens on
            the loop thread)"
    (let [signal (atom false)
          answers (atom [])
          abandon {:decision :deny :reason "abandoned for the test"}
          watcher ((var inter/abandon-on-cancel!)
                   {:signal signal :abandon abandon}
                   (fn [answer] (swap! answers conj answer)))]
      (try
        (Thread/sleep 130)
        (timers/pump!)
        (is (empty? @answers) "nothing while the run is live")
        (reset! signal true)
        (Thread/sleep 130)
        (timers/pump!)
        (is (= [abandon] @answers) "the question is answered once, with the deny")
        (finally (timers/cancel! watcher))))))

(deftest run-clears-the-extension-ui-registry-on-mode-end
  (testing "interactive/run releases the mode when it ends: the extension UI
            registry closes over the TUI, chat history, editor and session,
            so a stopped mode must not stay reachable through the global
            registry (the CLI exits right after; embedded hosts and tests
            keep running)"
    (let [noop (fn [& _] nil)
          tui-stub {:running? (atom false) :stopped? (atom true)}
          sess {}]
      (extensions/set-ui-registry! {:probe (fn [] :alive)})
      (try
        (is (= :alive (extensions/ui-call :probe))
            "sanity: the registry is installed")
        (with-redefs [session/resolve-session-path (fn [_ _] "target/fake-session.jsonl")
                      state/format-resume-command (fn [_ _] nil)
                      session/load-session (fn [_] sess)
                      layout/build-layout (fn [_ _ _ctx _opts]
                                            {:tui tui-stub
                                             :session-atom (atom sess)})
                      tui/tui-start noop
                      process/kill-tracked-children! noop]
          (inter/run {} {:session "fake"} nil))
        (is (nil? (extensions/ui-call :probe))
            "the registry is cleared when the mode ends")
        (finally
          (extensions/clear-ui-registry!))))))

(deftest teardown-mode!-clears-the-mode-roots
  (testing "finding 10: teardown-mode! drops the runtime sinks, the theme
            callback/watcher and the reakt queue; idempotent and safe on a
            stub CoreState"
    (let [a (atom 0)
          d (reakt/derive [a] (fn [v] v))]
      (is (= 0 @d))
      (swap! a inc)
      (is (pos? (reakt/queued-count)) "sanity: a derived ref is queued")
      (extensions/set-session! {:probe true})
      (extensions/set-context-sink! identity)
      (extensions/set-entry-sink! identity)
      (theme/on-theme-change (fn [] nil))
      (inter/teardown-mode! {:tui {:overlays (atom [])}})
      (is (nil? (extensions/get-session)))
      (is (nil? @(deref #'extensions/context-sink-atom)))
      (is (nil? @(deref #'extensions/entry-sink-atom)))
      (is (nil? @(deref #'theme/theme-change-callback)))
      (is (zero? (reakt/queued-count)))
      (inter/teardown-mode! {:tui {:overlays (atom [])}})
      (is (nil? (extensions/get-session))
          "a second call is a no-op"))))

(deftest teardown-mode!-disposes-the-tree
  (testing "finding 2: a stopped mode's TUI children are disposed — the
            chat-history cascade reaches the messages a tui-clear alone
            used to strand"
    (let [ui (tui/create-tui nil)
          ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
          cs (assoc (hs/core-state (hs/new-handle)
                                   {:tui ui
                                    :chat-history ch})
                    :dock-stack (atom []))]
      (chat-history/chat-history-add-message! ch {:role :user :content "hello"})
      (tui/tui-add-child ui ch)
      (protocols/render ch 40)
      (is (seq (chat-history/chat-history-get-messages ch)))
      (inter/teardown-mode! cs)
      (is (empty? @(:components ui)) "TUI children cleared")
      (is (empty? (chat-history/chat-history-get-messages ch))
          "the chat history cascaded into its messages"))))

(deftest branch-summary-render-driver-stops-with-the-tui
  (testing "finding 8: the branch-summary frame driver exits when the TUI
            stops or the summary promise lands — a mode stop must not leave
            a 100ms poller behind"
    (let [driver (var session-admin/start-branch-summary-render-driver!)]
      (let [done (promise)
            f (driver {:running? (atom false)} done)]
        (is (nil? (deref f 2000 ::timeout))
            "a stopped TUI ends the driver"))
      (let [done (promise)
            _ (deliver done :already-done)
            f (driver {:running? (atom true)} done)]
        (is (nil? (deref f 2000 ::timeout))
            "an already-delivered promise ends the driver")))))

(deftest session-info-shows-per-tool-usage
  (testing "/session's Tool Results section: per-tool calls + estimated result tokens, highest first, with a TOTAL (tool-result attribution)"
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
      (let [text ((var builtins/session-info-text) sess)]
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
          (is (not (str/includes? ((var builtins/session-info-text) bare) "Tool Results"))))))))

(deftest failed-attempt-leaves-no-blank-bubble-and-reports-its-reason
  (testing "a stalled/errored agent attempt leaves no bare (no response)
            bubble, keeps whatever streamed, and reports the reason with the
            retry line (terminal failures report through on-agent-error)"
    (let [ch (chat-history/make-chat-history :tool-fn (tu/tool-fn))
          handler ((var layout/make-agent-event-handler)
                   {:chat-history ch
                    :tui {:render-requested? (atom false)}
                    :cs-ref (atom nil)
                    :pending-tool-comps (atom {})})
          err-msg "LLM call timed out after 300000ms"]
      (testing "contentless attempt: the empty placeholder is dropped, the
                reason arrives with the retry event"
        (handler {:type :message-start :message {:role :assistant :content []}})
        (handler {:type :message-end
                  :message {:role :assistant :content []
                            :stop-reason :error
                            :error-message err-msg}})
        (is (nil? @(:streaming-atom ch))
            "the empty placeholder was removed, not left to render (no response)")
        (is (empty? (filterv #(= :error (:role %)) @(:messages-atom ch)))
            "no error line yet — a terminal failure must report exactly once")
        (handler {:type :auto-retry-start :attempt 1 :max-attempts 3
                  :delay-ms 500 :error-message err-msg})
        (let [errs (filterv #(= :error (:role %)) @(:messages-atom ch))]
          (is (= 1 (count errs)) "the retried attempt records its reason once")
          (is (= err-msg (:content (first errs))))))
      (testing "attempt with partials: the streamed text stays, no placeholder
                is dropped"
        (handler {:type :message-start :message {:role :assistant :content []}})
        ;; text deltas reach the streaming component through the agent's
        ;; on-text callback (turn.clj), not through a bus event
        (chat-history/chat-history-append-streaming-text! ch "partial answer")
        (handler {:type :message-end
                  :message {:role :assistant
                            :content [{:type :text :text "partial answer"}]
                            :stop-reason :error
                            :error-message "network error: request timed out"}})
        (is (some? @(:streaming-atom ch))
            "the placeholder with streamed text is kept")
        (handler {:type :auto-retry-start :attempt 2 :max-attempts 3
                  :delay-ms 1000 :error-message "network error: request timed out"})
        (let [errs (filterv #(= :error (:role %)) @(:messages-atom ch))]
          (is (= 2 (count errs)) "the second failure records its own reason")
          (is (= "network error: request timed out" (:content (last errs))))))
      (testing "a clean message-end stays a no-op"
        (let [before (count @(:messages-atom ch))]
          (handler {:type :message-end
                    :message {:role :assistant
                              :content [{:type :text :text "done"}]}})
          (is (= before (count @(:messages-atom ch)))
              "no error line for a successful message"))))))

(deftest survivor-report-lists-live-kinds
  ;; the --debug exit line names what teardown could not
  ;; release — live components, live reactions and armed timers; an armed
  ;; timer alone can root a mode after both counters read zero
  (let [report #'inter/survivor-report
        rx (reakt/make-reaction (fn [] 1))
        timer-id (timers/after! 60000 (fn []))]
    (try
      @rx
      (let [line (str (report))]
        (is (str/includes? line "leaks: track! components="))
        (is (str/includes? line " reactions="))
        (is (str/includes? line " armed timers="))
        (is (str/includes? line (str timer-id)) "the armed timer is named"))
      (finally
        (reakt/dispose! rx)
        (timers/cancel! timer-id)))))

(deftest ^:slow anim-timer-quiet-gate
  ;; The anim driver must consult timers/frame-due? — a frame painted by
  ;; streaming output or a keystroke within the last half bucket already
  ;; carries the current animation frame, so the driver's request would only
  ;; buy another whole-tree walk.
  (testing "the anim driver consults the quiet gate before requesting a frame"
    (let [requested (atom 0)
          tui {:running? (atom true)}
          cs (hs/core-state (hs/new-handle) {:tui tui
                                             :turn-running? true})
          driver (fn [gate]
                   (reset! requested 0)
                   (with-redefs [tui/tui-request-render (fn [_] (swap! requested inc))
                                 timers/frame-due? (constantly gate)
                                 spinner/default-interval-ms 20]
                     (status/start-anim-timer! cs)
                     (Thread/sleep 80)
                     (status/stop-anim-timer! cs))
                   @requested)]
      (is (pos? (driver true)) "an open gate requests frames")
      (is (zero? (driver false)) "a closed gate suppresses the request"))))
