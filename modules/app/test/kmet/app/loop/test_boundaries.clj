(ns kmet.app.loop.test-boundaries
  "Turn boundaries and phases: transform-context, system-prompt override,
   prepareNextTurn/finishTurn, the turn_end and agent_before_settle boundaries,
   and total request deadlines."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.ai.llm :as llm]
            [kmet.ai.models :as models]
            [kmet.app.events :as events]
            [kmet.app.session.core :as session]
            [kmet.app.loop.state :as state]
            [kmet.app.loop :as loop]
            [kmet.app.retry :as retry]
            [kmet.app.config :as cfg]
            [kmet.app.loop.test-support :as support]
            [kmet.srce.core :as srce]))

(t/use-fixtures :each support/with-host)

;; ─── Phase 3: transform-context ──────────────────────────────────────────

(t/deftest test-loop-transform-context
  (let [seen (atom nil)
        agent (support/make-agent-state)]
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
        agent (support/make-agent-state
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

;; ─── Phase 3: prepareNextTurn / finishTurn ───────────────────────────────

(t/deftest test-loop-prepare-next-turn-updates-state
  (let [agent (support/make-agent-state :model "model-a" :thinking :off)]
    (reset! (:prepare-next-turn agent)
            (fn [_] {:model (models/map->Model {:provider :anthropic :id "model-b"})
                     :thinking :high
                     :system-prompt-override "custom"}))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text (atom 0))
                  state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= "model-b" @(:model agent)) "prepare-next-turn swaps the model")
    (t/is (= :anthropic @(:provider agent))
          "prepare-next-turn follows the record's provider")
    (t/is (= :high @(:thinking agent)) "prepare-next-turn updates thinking level")
    (t/is (= "custom" @(:system-prompt-override agent))
          "prepare-next-turn sets the system prompt override for later turns")))

(t/deftest test-loop-finish-turn-end-after-tool-calls
  (let [calls (atom 0)
        agent (support/make-agent-state)]
    (reset! (:finish-turn agent) (fn [_] :end))
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

(t/deftest test-loop-finish-turn-still-drains-a-queued-follow-up
  ;; The tool-phase :end path settles with a :turn tag and resumes from it
  ;; (pi: runLoop's follow-up poll after the turn keeps counting turns). The
  ;; final-response variant below also checks the message reaches the context.
  (let [calls (atom 0)
        errors (atom [])
        events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:finish-turn agent) (fn [_] :end))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message (support/stub-llm-tool-then-text calls)
                  state/execute-tool-for
                  (fn [_ _ _ _]
                    (state/follow-up! agent "queued follow-up")
                    {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent
                            {:message "run"
                             :on-done (fn [_])
                             :on-error (fn [e] (swap! errors conj e))}))
    (t/is (empty? @errors) "the :end path resumed the run instead of crashing")
    (t/is (= 2 @calls) "the queued follow-up ran")
    (t/is (= [0 1]
             (mapv :turn-index (filter #(= :turn-start (:type %)) @events)))
          "turn numbering resumes from the stopped turn")))

(t/deftest test-loop-finish-turn-final-drains-a-queued-follow-up
  ;; The final-response :end path must settle with a :turn tag too: a
  ;; follow-up queued while that response streamed (the user's Alt+Enter) is
  ;; drained by the outer poll, and an untagged outcome resumes the loop with
  ;; a nil turn index — the resumed turn then crashes computing the next one.
  (let [calls (atom 0)
        errors (atom [])
        events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (reset! (:finish-turn agent) (fn [_] :end))
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (when (= 1 (swap! calls inc))
                        ;; the user queues a follow-up while this response streams
                        (state/follow-up! agent "queued follow-up"))
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent
                            {:message "run"
                             :on-done (fn [_])
                             :on-error (fn [e] (swap! errors conj e))}))
    (t/is (empty? @errors) "the :end path resumed the run instead of crashing")
    (t/is (= 2 @calls) "the queued follow-up ran a second turn")
    (t/is (= [0 1]
             (mapv :turn-index (filter #(= :turn-start (:type %)) @events)))
          "turn numbering resumes from the stopped turn")
    (t/is (= "queued follow-up"
             (get-in (last (filter #(= :user (:role %)) (state/get-context agent)))
                     [:content 0 :text])))
    (t/is (= :idle @(:status agent)))))

;; ─── Turn and settle boundaries (pi: actionable turn_end / agent_before_settle) ──

(t/deftest test-loop-turn-end-boundary-commits-entries
  ;; pi: _commitBoundaryDrafts — a turn_end handler's :entries are appended
  ;; through the session in order, and a custom_message also reaches the live
  ;; context (as a context-only append: pi's newMessages stays the loop's own
  ;; list).
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-turn-end-entries")))
        events (atom [])
        boundaries (atom [])
        calls (atom 0)
        opts (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e))
                                        :session (session/create-session dir))]
    (try
      (support/on-event
       :turn-end
       (fn [e]
         (swap! boundaries conj e)
         {:entries [{:role :custom :custom-type "state" :data {:n 1}}
                    {:role :custom-message :custom-type "note"
                     :content "boundary note" :display true}]}))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["done"] opts)]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (let [boundary (first @boundaries)]
        (t/is (= :turn-end (:type boundary)))
        (t/is (= 0 (:turn-index boundary)))
        (t/is (= :completed (:outcome boundary)) "a normal turn's outcome")
        (t/is (= :assistant (get-in boundary [:message :role])))
        (t/is (= [] (:tool-results boundary)))
        (t/is (= [] (:pending-messages boundary)))
        (t/is (false? (:can-continue boundary))
              "assistant-last and nothing queued: a continuation needs an entry")
        (t/is (= [] (:entries boundary))
              "the first handler sees the accumulated boundary state"))
      (let [branch (session/get-branch (:session agent))
            appended (filterv #(contains? #{"state" "note"} (:custom-type %)) branch)]
        (t/is (= [:custom :custom-message] (mapv :role appended))
              "both entries were committed in order")
        (t/is (= (:id (first appended)) (:parent-id (second appended)))
              "the second entry chains after the first"))
      (t/is (= "boundary note"
               (get-in (last (state/get-context agent)) [:content 0 :text]))
            "the custom-message projected into the context")
      (t/is (not-any? #(= "state" (:custom-type %)) (state/get-context agent))
            "a :custom entry stays out of the context (pi: metadata/state)")
      (t/is (not-any? #(= "boundary note" (get-in % [:content 0 :text]))
                      (mapcat :messages (filter #(= :agent-end (:type %)) @events)))
            "committed entries stay out of the attempt's :agent-end :messages")
      (t/is (= [:turn-end :message-start]
               (mapv :type (take 2 (drop-while #(not= :turn-end (:type %)) @events))))
            "the committed message is announced as the boundary closes")
      (finally
        (support/clear-listeners!)
        (fs/delete-tree dir)))))

(t/deftest test-loop-turn-end-boundary-carries-persisted-entry-ids
  ;; pi: _dispatchTurnEndBoundary hands :turn-end the persisted entry ids
  ;; (messageEntryId / toolResultEntryIds) and _buildBoundaryContext's
  ;; projection preview, so a handler can target an edit without reading the
  ;; session itself
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-turn-end-ids")))
        boundaries (atom [])
        calls (atom 0)
        agent (support/make-agent-state :session (session/create-session dir))]
    (try
      (support/on-event :turn-end (fn [e] (swap! boundaries conj e) nil))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})
                    llm/send-message (support/stub-llm-tool-then-text calls)]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (let [branch (session/get-branch (:session agent))
            assistant (first (filter #(= :assistant (:role %)) branch))
            tool-entry (first (filter #(= :tool (:role %)) branch))
            tool-turn (first @boundaries)
            final-turn (second @boundaries)]
        (t/is (= 2 (count @boundaries)) "one boundary per turn")
        (t/is (= (:id assistant) (:message-entry-id tool-turn))
              "the tool-call turn carries its assistant entry id")
        (t/is (= [(:id tool-entry)] (:tool-result-entry-ids tool-turn))
              "and its tool results' entry ids, in call order")
        (t/is (some? (session/get-entry (:session agent) (:message-entry-id tool-turn)))
              "the id resolves to a real session entry")
        (t/is (= [] (:tool-result-entry-ids final-turn))
              "a turn without tool calls carries no result ids")
        (t/is (= (:id (last (filter #(= :assistant (:role %)) branch)))
                 (:message-entry-id final-turn)))
        (t/is (= [:user :assistant :tool]
                 (mapv :role (:context-messages tool-turn)))
              "the preview carries the projected context messages")
        (t/is (= (mapv (comp :id :source) (:context-entries tool-turn))
                 (mapv :id (take 3 branch)))
              "the preview entries carry their source session entries (the
               preview was built at the first boundary — before the final
               assistant entry was appended)")
        (t/is (= (:context-messages tool-turn) (:llm-messages tool-turn))
              "nothing to flatten: the llm view is the projection"))
      (finally
        (support/clear-listeners!)
        (fs/delete-tree dir)))))

(t/deftest test-loop-turn-end-boundary-continue-runs-one-more-turn
  (let [calls (atom 0)
        events (atom [])
        opts (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (try
      (support/on-event :turn-end
                        (fn [e]
                          (when (zero? (:turn-index e))
                            {:entries [{:role :user
                                        :content [{:type :text :text "nudge"}]}]
                             :continue true})))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["first" "second"] opts)]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (t/is (= 2 @calls) "the boundary's :continue ran one more request")
      (t/is (= [0 1]
               (mapv :turn-index (filter #(= :turn-start (:type %)) @events))))
      (t/is (= ["run"] (support/request-user-texts (first @opts))))
      (t/is (= ["run" "nudge"] (support/request-user-texts (second @opts)))
            "the committed entry is part of the next request")
      (finally
        (support/clear-listeners!)))))

(t/deftest test-loop-turn-end-continue-without-a-runnable-context-is-rejected
  (let [{:keys [calls warn]} (support/run-prompt-asking-to-continue :turn-end {})]
    (t/is (= 1 calls)
          "assistant-last with nothing queued: the continuation is dropped")
    (t/is (str/includes? warn "turn-end")
          "the rejected continuation is reported (pi: _reportInvalidBoundaryContinuation)")))

(t/deftest test-loop-turn-end-invalid-entries-are-dropped
  (let [{:keys [calls warn agent]}
        (support/run-prompt-asking-to-continue :turn-end {:entries [:not-an-entry]})]
    (t/is (= 1 calls)
          "an invalid batch drops the continuation too (pi: valid=false)")
    (t/is (str/includes? warn "invalid") "the invalid batch is reported")
    (t/is (= [:user :assistant] (mapv :role (state/get-context agent)))
          "nothing was committed")))

(t/deftest test-loop-append-context-edit-refreshes-the-context
  ;; K: kmet.app.session.core/append-context-edit! — the session keeps the raw entry,
  ;; the live context is rebuilt from the projection (pi: appendContextEdit +
  ;; _refreshFinalizedContext)
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-context-edit")))
        calls (atom 0)
        opts (atom [])
        agent (support/make-agent-state :session (session/create-session dir))]
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["first"] opts)]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (let [branch (session/get-branch (:session agent))
            user-id (:id (first branch))
            before (state/get-context agent)]
        (t/is (= ["run" "first"] (mapv #(get-in % [:content 0 :text]) before)))
        (t/is (= :context-edit (:role (state/append-context-edit! agent user-id nil))))
        (t/is (= ["first"] (mapv #(get-in % [:content 0 :text]) (state/get-context agent)))
              "the omitted message leaves the live context")
        (t/is (= ["run" "first"]
                 (mapv #(get-in % [:content 0 :text])
                       (filter #(contains? #{:user :assistant} (:role %))
                               (session/get-branch (:session agent)))))
              "the raw branch keeps it")
        (state/append-context-edit! agent user-id {:content "rewritten"})
        (t/is (= "rewritten" (get-in (first (state/get-context agent)) [:content 0 :text]))
              "a later edit restores the message with new content"))
      (t/is (thrown? Exception (state/append-context-edit! agent "missing" nil))
            "an unknown target throws (see session/append-context-edit!)")
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-turn-end-boundary-context-edit
  ;; a :turn-end handler's context_edit draft is committed through the
  ;; validating session API and rebuilds the live context, so a later request
  ;; no longer carries the omitted message
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-turn-end-edit")))
        calls (atom 0)
        opts (atom [])
        boundaries (atom 0)
        agent (support/make-agent-state :session (session/create-session dir))]
    (try
      ;; first prompt: seed the session with the message we later omit
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["first"] opts)]
        @(loop/run-agent-turn agent {:message "seed"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (let [seed-id (:id (first (session/get-branch (:session agent))))]
        (support/on-event
         :turn-end
         (fn [_]
           ;; only the first boundary dispatches a draft
           (when (= 1 (swap! boundaries inc))
             {:entries [{:role :context-edit
                         :target-id seed-id
                         :replacement nil}]})))
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message (support/stub-llm-text-turns calls ["second"] opts)]
          @(loop/run-agent-turn agent {:message "second"
                                       :on-done (fn [_])
                                       :on-error (fn [_])}))
        (t/is (= 1 @boundaries) "the boundary ran")
        (t/is (= ["seed" "second"] (support/request-user-texts (second @opts)))
              "the request before the boundary still carried the message")
        (t/is (= :context-edit (:role (last (session/get-branch (:session agent)))))
              "the draft was committed as a real session entry")
        (t/is (= ["second"]
                 (mapv #(get-in % [:content 0 :text])
                       (filter #(= :user (:role %)) (state/get-context agent))))
              "the live context no longer carries the omitted message")
        (t/is (some #(= "seed" (get-in % [:content 0 :text]))
                    (session/get-branch (:session agent)))
              "the raw branch keeps it")
        ;; the next prompt's request is built from the edited projection
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message (support/stub-llm-text-turns calls ["third"] opts)]
          @(loop/run-agent-turn agent {:message "third"
                                       :on-done (fn [_])
                                       :on-error (fn [_])}))
        (t/is (= ["second" "third"] (support/request-user-texts (last @opts)))
              "the next prompt's request excludes the omitted message"))
      (finally
        (support/clear-listeners!)
        (fs/delete-tree dir)))))

(t/deftest test-loop-turn-end-boundary-invalid-context-edit-is-dropped
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-turn-end-bad-edit")))
        calls (atom 0)
        opts (atom [])
        warn (java.io.StringWriter.)
        agent (support/make-agent-state :session (session/create-session dir))]
    (try
      (support/on-event
       :turn-end
       (fn [_]
         {:entries [{:role :context-edit :target-id "missing" :replacement nil}
                    {:role :custom :custom-type "state" :data {}}]}))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["done"] opts)]
        (binding [*err* warn]
          @(loop/run-agent-turn agent {:message "run"
                                       :on-done (fn [_])
                                       :on-error (fn [_])})))
      (t/is (str/includes? (str warn) "context edit")
            "the bad edit is reported, not fatal")
      (t/is (some #(= "state" (:custom-type %)) (session/get-branch (:session agent)))
            "the rest of the batch still commits")
      (t/is (= 1 @calls) "the prompt settled normally")
      (finally
        (support/clear-listeners!)
        (fs/delete-tree dir)))))

(t/deftest test-loop-finish-turn-hook-decisions
  (t/testing ":end settles the attempt"
    (let [calls (atom 0)
          opts (atom [])
          agent (support/make-agent-state)]
      (reset! (:finish-turn agent) (fn [_] :end))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["done"] opts)]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (t/is (= 1 @calls) "the hook stopped the attempt after the first turn")))
  (t/testing ":continue runs one context-only turn"
    (let [calls (atom 0)
          events (atom [])
          opts (atom [])
          agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
      (reset! (:finish-turn agent)
              (fn [e] (when (zero? (:turn-index e)) :continue)))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["first" "second"] opts)]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (t/is (= 2 @calls) "the hook's :continue forces another request")
      (t/is (= ["run"] (support/request-user-texts (second @opts)))
            "no new input: the extra turn is context-only (pi: explicitContinuation)")
      (t/is (= [0 1]
               (mapv :turn-index (filter #(= :turn-start (:type %)) @events))))))
  (t/testing ":continue runs one more turn after a terminated tool batch"
    (let [calls (atom 0)
          opts (atom [])
          agent (support/make-agent-state)]
      (reset! (:before-tool-call agent)
              (fn [_] {:block true :reason "Policy" :terminate true}))
      (reset! (:finish-turn agent)
              (fn [e] (when (zero? (:turn-index e)) :continue)))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [o]
                      (swap! opts conj o)
                      (future
                        (if (= 1 (swap! calls inc))
                          (do (when-let [on-tc (:on-tool-call o)]
                                (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                              (when-let [on-done (:on-done o)] (on-done :tool-calls)))
                          (do (when-let [on-text (:on-text o)] (on-text "after"))
                              (when-let [on-done (:on-done o)] (on-done :stop))))
                        :done))]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (t/is (= 2 @calls)
            "a terminated batch settles without the decision; :continue overrides it")
      (t/is (= ["run"] (support/request-user-texts (second @opts)))
            "context-only: the model resumes from the blocked tool result")))
  (t/testing ":end wins over a boundary :continue"
    (let [calls (atom 0)
          opts (atom [])
          agent (support/make-agent-state)]
      (reset! (:finish-turn agent) (fn [_] :end))
      (support/on-event :turn-end
                        (fn [_] {:entries [{:role :user
                                            :content [{:type :text :text "nudge"}]}]
                                 :continue true}))
      (try
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message (support/stub-llm-text-turns calls ["done"] opts)]
          @(loop/run-agent-turn agent {:message "run"
                                       :on-done (fn [_])
                                       :on-error (fn [_])}))
        (t/is (= 1 @calls) "pi checks the :end decision before :continue")
        (t/is (= "nudge" (get-in (last (state/get-context agent)) [:content 0 :text]))
              "the boundary's entries are committed regardless")
        (finally
          (support/clear-listeners!))))))

(t/deftest test-loop-turn-end-fires-for-a-failed-attempt
  ;; pi: finishTurn and turn_end run for a stopReason-error response too, so an
  ;; errored turn ends before its :agent-end (and its :auto-retry-start).
  (let [calls (atom 0)
        events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e))
                                        :max-retries 1
                                        :base-delay-ms 1)]
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [o]
                      (future
                        (if (= 1 (swap! calls inc))
                          (when-let [on-error (:on-error o)]
                            (on-error "upstream connect error"))
                          (do (when-let [on-text (:on-text o)] (on-text "recovered"))
                              (when-let [on-done (:on-done o)] (on-done :stop))))
                        :done))]
        @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
      (t/is (= [:turn-end :agent-end :auto-retry-start :turn-end :agent-end]
               (support/events-of @events #{:turn-end :agent-end :auto-retry-start}))
            "each failed turn ends before its :agent-end")
      (let [tes (filter #(= :turn-end (:type %)) @events)]
        (t/is (= [:error :completed] (mapv :outcome tes))
              "the errored turn reports :error, the recovered one :completed")
        (t/is (= :error (get-in (first tes) [:message :stop-reason])))
        (t/is (= 0 (:turn-index (first tes)))))
      (finally
        (support/clear-listeners!)))))

(t/deftest test-loop-turn-end-fires-for-an-aborted-turn
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (fn [o]
                                       (future
                                         (when-let [on-text (:on-text o)]
                                           (on-text "partial"))
                                         (loop/cancel-turn agent)
                                         :done))]
        @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
      (let [te (first (filter #(= :turn-end (:type %)) @events))]
        (t/is (= :aborted (:outcome te)))
        (t/is (= "partial" (get-in te [:message :content 0 :text]))
              "the aborted partial is the turn's message")
        (t/is (= [:turn-end :agent-end :agent-settled]
                 (support/events-of @events #{:turn-end :agent-end :agent-settled}))))
      (finally
        (support/clear-listeners!)))))

(t/deftest test-loop-agent-before-settle-carries-the-context-preview
  ;; pi: _buildBoundaryContext — the pre-settle boundary carries the same
  ;; projection preview as :turn-end (BoundaryContextPreview)
  (let [dir (str (fs/create-dirs (fs/path "target" "test-loop-before-settle-preview")))
        boundaries (atom [])
        calls (atom 0)
        opts (atom [])
        agent (support/make-agent-state :session (session/create-session dir))]
    (try
      (support/on-event :agent-before-settle (fn [e] (swap! boundaries conj e) nil))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["done"] opts)]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (let [boundary (first @boundaries)]
        (t/is (= :agent-before-settle (:type boundary)))
        (t/is (= [:user :assistant] (mapv :role (:context-messages boundary)))
              "the projected context messages")
        (t/is (= (mapv :id (session/get-branch (:session agent)))
                 (mapv (comp :id :source) (:context-entries boundary)))
              "the projected entries keep their source session entries")
        (t/is (= (:context-messages boundary) (:llm-messages boundary))
              "nothing to flatten: the llm view is the projection"))
      (finally
        (support/clear-listeners!)
        (fs/delete-tree dir)))))

(t/deftest test-loop-agent-before-settle-continue-runs-another-attempt
  (let [calls (atom 0)
        dones (atom 0)
        events (atom [])
        boundaries (atom [])
        opts (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (try
      (support/on-event
       :agent-before-settle
       (fn [e]
         (swap! boundaries conj e)
         (when (= 1 (count @boundaries))
           {:entries [{:role :user
                       :content [{:type :text :text "settle nudge"}]}]
            :continue true})))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["first" "second"] opts)]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-done (fn [_] (swap! dones inc))
                                     :on-error (fn [_])}))
      (t/is (= 2 (count @boundaries)) "one boundary per settle")
      (t/is (= :agent-before-settle (:type (first @boundaries))))
      (t/is (= :completed (:outcome (first @boundaries))))
      (t/is (false? (:can-continue (first @boundaries))))
      (t/is (= 2 @calls) "the boundary ran one more attempt")
      (t/is (= 2 (count (filter #(= :agent-start (:type %)) @events))))
      (t/is (= ["run" "settle nudge"] (support/request-user-texts (second @opts)))
            "the committed entry is the next attempt's context")
      (t/is (= 1 @dones) "on-done fires once per prompt, at the settle point")
      (t/is (= [:agent-before-settle :agent-before-settle :agent-settled]
               (support/events-of @events #{:agent-before-settle :agent-settled}))
            "the boundary precedes the terminal :agent-settled")
      (finally
        (support/clear-listeners!)))))

(t/deftest test-loop-agent-before-settle-continue-without-context-is-rejected
  (let [{:keys [calls warn]} (support/run-prompt-asking-to-continue :agent-before-settle {})]
    (t/is (= 1 calls) "a continuation without a runnable context is dropped")
    (t/is (str/includes? warn "agent-before-settle")
          "the rejected continuation is reported")))

(t/deftest test-loop-agent-before-settle-queued-message-runs-an-attempt
  ;; A boundary handler that queues a message continues the prompt without
  ;; asking (pi: _runBeforeSettleBoundary's hasQueuedMessages), and the queued
  ;; batch becomes the next attempt's prompt (pi: agent.continue).
  (let [calls (atom 0)
        opts (atom [])
        queued (atom 0)
        agent (support/make-agent-state)]
    (try
      (support/on-event :agent-before-settle
                        (fn [_]
                          (when (= 1 (swap! queued inc))
                            (state/follow-up! agent "settle follow-up"))))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["first" "second"] opts)]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (t/is (= 2 @calls) "the queued message ran a second attempt")
      (t/is (= ["run" "settle follow-up"] (support/request-user-texts (second @opts))))
      (t/is (empty? @(:follow-up agent)) "the queue was drained into the prompt")
      (finally
        (support/clear-listeners!)))))

(t/deftest test-loop-agent-before-settle-reports-an-error-outcome
  ;; pi: a terminal error still reaches the pre-settle boundary, with the
  ;; errored turn's outcome — and the boundary can revive the prompt.
  (let [calls (atom 0)
        errors (atom 0)
        boundaries (atom [])
        agent (support/make-agent-state :max-retries 0 :base-delay-ms 1)]
    (try
      (support/on-event
       :agent-before-settle
       (fn [e]
         (swap! boundaries conj e)
         (when (= 1 (count @boundaries))
           {:entries [{:role :user
                       :content [{:type :text :text "retry nudge"}]}]
            :continue true})))
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (fn [o]
                                       (future
                                         (swap! calls inc)
                                         (when-let [on-error (:on-error o)]
                                           (on-error "down"))
                                         :done))]
        @(loop/run-agent-turn agent {:message "run"
                                     :on-error (fn [_] (swap! errors inc))}))
      (t/is (= [:error :error] (mapv :outcome @boundaries)))
      (t/is (= 2 @calls) "the boundary revived the errored prompt")
      (t/is (= 2 @errors) "the second attempt's error surfaces too")
      (finally
        (support/clear-listeners!)))))

(t/deftest test-loop-agent-before-settle-not-run-for-a-cancelled-prompt
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (fn [o]
                                       (future
                                         (when-let [on-text (:on-text o)]
                                           (on-text "partial"))
                                         (loop/cancel-turn agent)
                                         :done))]
        @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
      (t/is (= [:turn-end :agent-end :agent-settled]
               (support/events-of @events #{:turn-end :agent-end :agent-settled}))
            "the aborted turn still ends, then the prompt settles")
      (t/is (not-any? #(= :agent-before-settle (:type %)) @events)
            "a cancelled prompt skips the pre-settle boundary (pi: the prompt
             loop's abort exits before _runBeforeSettleBoundary)")
      (finally
        (support/clear-listeners!)))))

;; ─── Total request deadline ──────────────────────────────────────────────

(t/deftest test-loop-total-timeout-ms
  (t/testing "agent defaults use a 120-second idle timeout and no total deadline"
    (let [agent (support/make-agent-state)]
      (t/is (= 120000 (:http-idle-timeout-ms @(:cfg agent))))
      (t/is (= 0 (#'retry/llm-total-timeout-ms @(:cfg agent)))))
    (t/testing "explicit nil total follows the idle timeout"
      (let [agent (support/make-agent-state :http-idle-timeout-ms 300000
                                            :http-total-timeout-ms nil)]
        (t/is (= 300000 (#'retry/llm-total-timeout-ms @(:cfg agent))))))
    (t/testing "explicit total wins over the idle-derived default"
      (let [agent (support/make-agent-state :http-idle-timeout-ms 300000
                                            :http-total-timeout-ms 120000)]
        (t/is (= 120000 (#'retry/llm-total-timeout-ms @(:cfg agent))))))
    (t/testing "zero total disables the total deadline despite an idle timeout"
      (let [agent (support/make-agent-state :http-idle-timeout-ms 300000
                                            :http-total-timeout-ms 0)]
        (t/is (= 0 (#'retry/llm-total-timeout-ms @(:cfg agent))))))
    (t/testing "no idle and nil total means no total deadline"
      (let [agent (support/make-agent-state :http-idle-timeout-ms 0
                                            :http-total-timeout-ms nil)]
        (t/is (= 0 (#'retry/llm-total-timeout-ms @(:cfg agent))))))))

(t/deftest test-loop-set-http-total-timeout-ms!
  (let [agent (support/make-agent-state :http-idle-timeout-ms 120000)]
    (state/set-http-total-timeout-ms! agent 60000)
    (t/is (= 60000 (:http-total-timeout-ms @(:cfg agent))))
    (state/set-http-total-timeout-ms! agent nil)
    (t/is (nil? (:http-total-timeout-ms @(:cfg agent)))
          "nil resets to the use-idle default")
    (state/set-http-total-timeout-ms! agent 0)
    (t/is (= 0 (:http-total-timeout-ms @(:cfg agent))))
    (t/is (= 0 (#'retry/llm-total-timeout-ms @(:cfg agent)))
          "zero disables total timeout rather than following idle")))

(t/deftest test-loop-call-llm-threads-total-timeout
  ;; call-llm must pass the total deadline to llm/send-message so the
  ;; transport (HttpRequest.timeout / curl --max-time) enforces it as the
  ;; whole-request wall-clock.
  (let [sent (atom nil)
        agent (support/make-agent-state :http-idle-timeout-ms 120000
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
    (t/is (= 120000 (:idle-timeout-ms @sent))
          "the idle timeout stays the separate per-byte deadline")))

(t/deftest test-loop-call-llm-threads-disabled-total-timeout
  (let [sent (atom nil)
        agent (support/make-agent-state :http-idle-timeout-ms 120000
                                        :http-total-timeout-ms 0)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (reset! sent opts)
                    (future
                      (when-let [on-done (:on-done opts)]
                        (on-done :stop)))
                    :done)]
      @(loop/run-agent-turn agent {:message "hi" :on-done (fn [_])}))
    (t/is (= 0 (:total-timeout-ms @sent))
          "explicit disable is passed through, not replaced by the idle timeout")))

(t/deftest ^:slow test-loop-total-deadline-timeout-retries-then-surfaces
  ;; A total-deadline timeout (the deref :timeout sentinel when the transport
  ;; fails to deliver an error) is retryable, not a silent abort. It retries
  ;; with backoff and surfaces via on-error when exhausted.
  (let [events (atom [])
        errors (atom [])
        agent (support/make-agent-state
               :on-event (fn [e] (swap! events conj e))
               :max-retries 1
               :base-delay-ms 1
               :http-idle-timeout-ms 0
               :http-total-timeout-ms 10)]
    ;; A tiny explicit total deadline makes the loop's deref hit the
    ;; :timeout sentinel quickly because fake send-message never delivers.
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    ;; stream partials, then never deliver — the deref hits
                    ;; its timeout and the partials must survive it
                    ((:on-text opts) "partial answer")
                    ((:on-thinking opts) "still reasoning")
                    (promise))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= 1 (count @errors)) "the timeout surfaces via on-error (not silent)")
    (t/is (str/includes? (first @errors) "timed out")
          "the surfaced error names the timeout")
    (let [starts (filter #(= :auto-retry-start (:type %)) @events)]
      (t/is (= 1 (count starts)) "the timeout is retried once (retryable)")
      (t/is (= "LLM call timed out after 10ms" (:error-message (first starts)))))
    (let [errored (first (filter #(:error-message (:message %))
                                 (filter #(= :message-end (:type %)) @events)))]
      (t/is (some? errored) "the failed attempt is recorded with its error")
      (t/is (= "partial answer"
               (apply str (map :text (:content (:message errored)))))
            "text streamed before the deadline survives the timeout")
      (t/is (= "still reasoning" (:thinking (:message errored)))
            "thinking streamed before the deadline survives the timeout"))
    (t/is (= :idle @(:status agent)) "run settles idle after retries exhausted")
    (t/is (some #(= :error (:type %)) @events) "terminal :error event emitted")))

;; ─── Phase 7b-i: one boundary, one decision ───────────────────────────────

(t/deftest test-a-boundary-decision-reaches-the-next-handler
  ;; One chain: a front end's contribution reaches the handler after it, so two
  ;; layers cannot hold different opinions (docs/architecture.md § The event bus). The handlers sit
  ;; on the run's own ctx, which is where the loop dispatches.
  (let [{ctx :ctx} (support/test-ctx)
        front-end-saw (atom [])
        next-saw (atom [])
        dispose-fe (events/on! ctx :turn-end
                               (fn [ev] (swap! front-end-saw conj (:type ev))
                                 {:kmet.test/marker true}))
        dispose-next (events/on! ctx :turn-end
                                 (fn [ev] (swap! next-saw conj (:kmet.test/marker ev))))]
    (try
      (binding [support/*ctx* ctx]
        (support/run-turn-capturing-llm (support/make-agent-state) {:message "hi"}))
      (t/is (= [:turn-end] @front-end-saw) "the first listener hears the boundary")
      (t/is (= [true] @next-saw)
            "and the handler after it sees the decision, not a blank slate")
      (finally (dispose-fe) (dispose-next)))))

(t/deftest test-a-runs-events-belong-to-its-subtree
  ;; The loop dispatches from the run's own ctx, not from the app's scope: a
  ;; listener at that ctx hears its run and a sibling does not. This is why
  ;; `state/emit` calls the kernel with the agent's ctx rather than the
  ;; app-wide `:events` service, whose emissions belong to the app's scope —
  ;; switching to the service would keep the app's listeners working and
  ;; silently move every run's own out of reach.
  (let [{root :ctx} (support/test-ctx)
        run-ctx (srce/extend root {})
        sibling (srce/extend root {})
        own (atom [])
        sib (atom [])
        dispose-own (events/on! run-ctx :turn-end (fn [_] (swap! own conj true)))
        dispose-sib (events/on! sibling :turn-end (fn [_] (swap! sib conj true)))]
    (try
      (binding [support/*ctx* run-ctx]
        (support/run-turn-capturing-llm (support/make-agent-state) {:message "hi"}))
      (t/is (= [true] @own) "the run's own ctx hears its boundary")
      (t/is (= [] @sib) "a sibling under the same app scope does not")
      (finally (dispose-own) (dispose-sib)))))
