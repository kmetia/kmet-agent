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
            [kmet.app.event-bus :as event-bus]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.ui :as ui]
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
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.protocols :as protocols]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defn- make-handler
  "The real event handler over a standalone chat history and stub refs: the
   cs-dependent clauses (footer/status indicators) no-op through a nil
   cs-ref; tool-execution events correlate through an empty pending map."
  []
  ((var inter/make-agent-event-handler)
   {:chat-history (ui/make-chat-history)
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
    (let [logo ((var inter/fmt-header-logo))]
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
          ch (ui/make-chat-history)
          fdp (ui/make-footer-data-provider :session old-sess)
          ftr (ui/make-footer :provider fdp)
          ed (editor/make-editor)
          _ (editor/editor-set-text! ed "draft")
          parked-bash (ui/make-bash-execution :command "sleep 10")
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
               :pending-messages-comp (ui/make-pending-messages)
               :status-current (atom nil)
               :status-root nil
               :status-indicator (ui/make-status-indicator)
               :footer-comp ftr
               :footer-provider fdp})
          shutdown-events (atom [])
          _ (event-bus/clear-event-listeners!)
          _ (event-bus/on-event :session-shutdown
                                (fn [ev] (swap! shutdown-events conj ev)))]
      (try
        ((var inter/handle-new-session) cs)
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
          ch (ui/make-chat-history)
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
               :status-indicator (ui/make-status-indicator)
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
        ((var inter/handle-new-session) cs)
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
             {:chat-history (ui/make-chat-history)
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
              ch (ui/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})]
          ((var inter/replay-branch!) cs loaded)
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
              (is (some #(str/includes? % "to expand") lines)
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
              ch (ui/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})
              prev-caps (timg/get-capabilities)]
          (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
          (try
            ((var inter/replay-branch!) cs loaded)
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
              ch (ui/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})]
          ((var inter/replay-branch!) cs loaded)
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
              ch (ui/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})]
          ((var inter/replay-branch!) cs loaded)
          (let [tools (filterv #(= :tool (:role %)) @(:messages-atom ch))]
            (is (= 1 (count tools)))
            (is (some #(str/includes? % "Aborted")
                      (protocols/render (:component (first tools)) 100))
                "aborted wording shown as the result")))
        (finally (fs/delete-tree sess-dir))))))

(deftest submit-command-line-gate
  (testing "multiline submit text (e.g. pasted blocks) is never a command"
    (let [command-line? @#'inter/command-line?]
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
              ch (ui/make-chat-history)
              cs (inter/map->CoreState {:chat-history ch})
              prev-caps (timg/get-capabilities)]
          (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
          (try
            ((var inter/replay-branch!) cs loaded)
            (let [msg (first (filter #(= :tool (:role %)) @(:messages-atom ch)))
                  lines (protocols/render (:component msg) 60)]
              (is (some? msg))
              (is (= [{:data "AA" :mime-type "image/png"}] (:images msg))
                  "the orphan result carries its image blocks")
              (is (str/includes? (str/join "\n" lines) "[Image: [image/png]")
                  "the orphan result's image renders"))
            (finally (timg/set-capabilities! prev-caps))))
        (finally (fs/delete-tree sess-dir))))))

(deftest reload-reseeds-image-settings-and-block-images
  (testing "/reload re-reads the image settings and the provider image-blocking
            knob from the reloaded config (pi: settingsManager.reload)"
    (let [ag (agent/make-agent-state)
          ch (ui/make-chat-history)
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
                      ui/chat-history-set-thinking-hidden! (fn [_ _] nil)
                      ui/loaded-resources-set-sections! (fn [_ _] nil)
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
        ((var inter/heal-stale-scrollback-when-idle!) cs)
        (is (healed? cs) "idle + dirty requests the heuristic full redraw"))
      (doseq [[label cs] [["mid agent turn" (make true false false true)]
                          ["mid bash command" (make false true false true)]
                          ["mid compaction" (make false false true true)]]]
        ((var inter/heal-stale-scrollback-when-idle!) cs)
        (is (not (healed? cs)) (str label " must not emit the destructive 3J clear")))
      (let [cs (make false false false false)]
        ((var inter/heal-stale-scrollback-when-idle!) cs)
        (is (not (healed? cs)) "a clean scrollback is a no-op")))))

(deftest request-global-reflow-render-gating
  (testing "a global reflow (toggle) forces the clearing redraw only when streaming-free"
    (let [make (fn [running]
                 (inter/map->CoreState
                  {:running-turn? (atom running)
                   :bash-running? (atom false)
                   :agent-state (atom {:compacting? (atom false)})
                   :tui {:force-redraw? (atom false)
                         :render-requested? (atom false)}}))]
      (let [cs (make false)]
        ((var inter/request-global-reflow-render!) cs)
        (is (true? @(get-in cs [:tui :force-redraw?]))
            "idle forces the rebuild (matches the shrinking direction)")
        (is (true? @(get-in cs [:tui :render-requested?])) "and requests a frame"))
      (let [cs (make true)]
        ((var inter/request-global-reflow-render!) cs)
        (is (false? @(get-in cs [:tui :force-redraw?]))
            "mid-turn falls back to the ordinary render — never 3J mid-stream")
        (is (true? @(get-in cs [:tui :render-requested?])) "still requests a frame")))))

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
                      inter/activate-working-indicator! noop
                      inter/start-anim-timer! noop
                      inter/update-footer! noop]
          ((var inter/start-agent-run!) cs))
        (is (true? @(:running-turn? cs)) "the turn still starts")
        (is (false? @(get-in cs [:tui :force-redraw?]))
            "a dirty scrollback is left for the turn end, not cleared at the start"))
      ;; Turn end (done and error): heal, but only when streaming-free.
      (doseq [[label call] [["on-agent-done" #((var inter/on-agent-done) %)]
                            ["on-agent-error" #((var inter/on-agent-error) % "boom")]]
              [case-label bash? dirty? expected] [["streaming-free" false true true]
                                                  ["mid bash command" true true false]
                                                  ["clean scrollback" false false false]]]
        (let [cs (inter/map->CoreState
                  {:anim-timer (atom nil)
                   :running-turn? (atom true)
                   :bash-running? (atom bash?)
                   :agent-state (atom {:compacting? (atom false)})
                   :chat-history (ui/make-chat-history)
                   :tui {:scrollback-dirty? (atom dirty?)
                         :force-redraw? (atom false)
                         :render-requested? (atom false)}})]
          (with-redefs [inter/stop-anim-timer! noop
                        inter/clear-status-indicator! noop
                        inter/update-footer! noop]
            (call cs))
          (is (= expected @(get-in cs [:tui :force-redraw?]))
              (str label " / " case-label
                   (if expected " heals the dirty scrollback"
                       " must not emit the clearing redraw")))
          (is (false? @(:running-turn? cs)) (str label " ends the turn")))))))
