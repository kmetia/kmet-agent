(ns kmet.app.loop.test-custom
  "Custom-message delivery (sendCustomMessage) and the settled outcome a run reports."
  (:require [clojure.test :as t]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [kmet.ai.llm :as llm]
            [kmet.ai.models :as models]
            [kmet.ai.auth :as auth]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.session.core :as session]
            [kmet.app.loop.state :as state]
            [kmet.app.loop :as loop]
            [kmet.app.config :as cfg]
            [kmet.app.events :as events]
            [kmet.app.loop.test-support :as support]
            [kmet.test-tmp :as test-tmp]))

(t/use-fixtures :each support/with-host)

;; ─── Phase 4: custom-message delivery (pi: sendCustomMessage) ────────────

(t/deftest test-loop-next-turn-custom-message-rides-the-next-prompt
  (t/testing "a custom message queued for the next prompt rides with it,
              after its user message (pi: _pendingNextTurnMessages injected
              in prompt() and cleared)"
    (let [dir (-> (fs/create-dirs (fs/path "target" (str "test-next-turn-"
                                                         (System/currentTimeMillis))))
                  fs/absolutize str)
          sess (session/create-session dir)
          agent (support/make-agent-state :session sess)
          calls (atom 0)
          opts (atom [])]
      (try
        (state/queue-next-turn-message! agent {:role :custom
                                               :custom-type :note
                                               :content "queued"
                                               :display true})
        (t/is (empty? @(:messages agent))
              "the message waits for a prompt — nothing is in context yet")
        (t/is (empty? (session/get-branch sess))
              "and nothing is persisted yet")
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message (support/stub-llm-text-turns calls ["ok"] opts)]
          @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
        (let [requests (mapv :messages @opts)]
          (t/is (= 1 (count requests)))
          (t/is (= [:system :user :custom :tool-change]
                   (mapv :role (first requests)))
                "the queued message rides after the user message; the
                 declared-set marker is appended by the request's reconcile"))
        (t/is (= [:turn-start :user :custom-message]
                 (mapv :role (take 3 (session/get-branch sess))))
              "persisted once, after the turn marker and the user entry")
        (finally
          (fs/delete-tree dir))))))

(t/deftest test-loop-deferred-custom-message-lands-after-the-tool-result
  (t/testing "a custom message deferred while streaming waits for the end of
              the turn, then lands after the tool result (pi:
              _pendingCustomMessages flushed at turn_end)"
    (let [agent (support/make-agent-state)
          requests (atom [])
          calls (atom 0)
          deferred? (fn [m] (= "deferred" (get-in m [:content 0 :text])))]
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})
                    llm/send-message
                    (fn [opts]
                      (future
                        (swap! requests conj (:messages opts))
                        (if (= 1 (swap! calls inc))
                          (do
                            (state/defer-custom-message! agent {:role :custom
                                                                :custom-type :note
                                                                :content "deferred"
                                                                :display true})
                            (t/is (not-any? deferred? @(:messages agent))
                                  "not appended while the turn is in flight")
                            (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                          (do (when-let [on-text (:on-text opts)] (on-text "ok"))
                              (when-let [on-done (:on-done opts)] (on-done :stop))))
                        :done))]
        @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
      (t/is (= 2 (count @requests)))
      (let [second-request (second @requests)]
        (t/is (= :tool (:role (last (butlast second-request))))
              "the tool result is in before the deferred message")
        (t/is (deferred? (last second-request))
              "the deferred message follows it"))
      (t/is (= 1 (count (filter deferred? @(:messages agent))))
            "appended exactly once"))))

(t/deftest test-loop-queued-custom-message-appended-once
  (t/testing "a custom message queued into the running prompt (pi: steer) is
              persisted and appended exactly once, when the loop consumes it"
    (let [dir (-> (fs/create-dirs (fs/path "target" (str "test-queued-custom-"
                                                         (System/currentTimeMillis))))
                  fs/absolutize str)
          sess (session/create-session dir)
          agent (support/make-agent-state :session sess)
          calls (atom 0)]
      (try
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})
                      llm/send-message
                      (fn [opts]
                        (future
                          (if (= 1 (swap! calls inc))
                            (do
                              (state/steer! agent {:role :custom
                                                   :custom-type :note
                                                   :content "steered"
                                                   :display true})
                              (when-let [on-tc (:on-tool-call opts)]
                                (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                              (when-let [on-done (:on-done opts)]
                                (on-done :tool-calls)))
                            (do (when-let [on-text (:on-text opts)] (on-text "ok"))
                                (when-let [on-done (:on-done opts)] (on-done :stop))))
                          :done))]
          @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
        (t/is (= 2 @calls))
        (t/is (= 1 (count (filter #(= "steered" (get-in % [:content 0 :text]))
                                  @(:messages agent))))
              "appended exactly once — the queue drain is the only append")
        (t/is (= 1 (count (filter #(and (= :custom-message (:role %))
                                        (= "steered" (:content %)))
                                  (session/get-branch sess))))
              "persisted exactly once")
        (finally
          (fs/delete-tree dir))))))

(t/deftest test-loop-prepare-request-checkpoint
  (t/testing "the per-request checkpoint runs before every provider request
              (pi: config.prepareRequest): a :context update applies to the
              request that follows with no turn boundary between, and it is
              not persisted (the session projection stays authoritative for
              what is stored)"
    (let [dir (-> (fs/create-dirs (fs/path "target" (str "test-prepare-request-"
                                                         (System/currentTimeMillis))))
                  fs/absolutize str)
          sess (session/create-session dir)
          agent (support/make-agent-state :session sess)
          requests (atom [])
          hook-calls (atom 0)
          hook-contexts (atom [])
          calls (atom 0)]
      (try
        (reset! (:prepare-request agent)
                (fn [req]
                  (swap! hook-calls inc)
                  (swap! hook-contexts conj (:context req))
                  (cond-> {:model (models/map->Model {:provider :anthropic
                                                      :id "model-b"})}
                    ;; replace the conversation on the first request only —
                    ;; the replacement must already reach that request
                    (= 1 @hook-calls)
                    (assoc :context (conj (vec (:context req))
                                          {:role :user
                                           :content [{:type :text :text "checkpoint"}]})))))
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})
                      llm/send-message
                      (fn [opts]
                        (swap! requests conj opts)
                        ((support/stub-llm-tool-then-text calls) opts))]
          @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
        (t/is (= 2 @hook-calls) "once per provider request")
        (t/is (= "model-b" (:model (first @requests)))
              "the model swap reaches the first request — no turn boundary needed")
        (t/is (= :anthropic (:provider (first @requests)))
              "and its provider follows")
        (t/is (= [:system :user :user :tool-change]
                 (mapv :role (:messages (first @requests))))
              "the replaced context is what the first request sends")
        (t/is (= "checkpoint" (get-in (:messages (first @requests)) [2 :content 0 :text])))
        (t/is (not-any? #(= "checkpoint" (get-in % [:content 0 :text]))
                        (second @hook-contexts))
              "the next request's hook sees the canonical projection again
               (pi: the session wrapper re-installs it before config.prepareRequest)")
        (t/is (= 1 (count (filter #(= :user (:role %)) (:messages (second @requests)))))
              "a one-shot replacement does not survive the next request — the
               install rebuilds the context from the session (pi parity)")
        (t/is (= 1 (count (filter #(= :user (:role %)) (session/get-branch sess))))
              "the request projection is not persisted — only the prompt's user entry")
        (finally
          (fs/delete-tree dir))))))

(t/deftest test-loop-request-installs-the-session-projection
  ;; pi parity (_installAgentRequestProjection): the canonical session
  ;; projection is installed as the request context, so an append-only edit
  ;; appended straight to the session — no loop rebuild after it — still
  ;; reaches the request
  (let [dir (test-tmp/temp-dir! "test-loop-request-projection-")
        requests (atom [])
        calls (atom 0)
        sess (session/create-session dir)
        agent (support/make-agent-state :session sess)]
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["first"] requests)]
        @(loop/run-agent-turn agent {:message "seed"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (let [seed-id (:id (first (filter #(= :user (:role %))
                                        (session/get-branch sess))))]
        (session/append-context-edit! sess seed-id nil)
        (t/is (some #(= "seed" (get-in % [:content 0 :text]))
                    (state/get-context agent))
              "the live context still carries the edited message — no rebuild ran")
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message (support/stub-llm-text-turns calls ["second"] requests)]
          @(loop/run-agent-turn agent {:message "second"
                                       :on-done (fn [_])
                                       :on-error (fn [_])}))
        (t/is (not-any? #(= "seed" (get-in % [:content 0 :text]))
                        (:messages (last @requests)))
              "the request's install applied the edit with no rebuild between")
        (t/is (= ["second"] (support/request-user-texts (last @requests))))
        (t/is (not-any? #(= "seed" (get-in % [:content 0 :text]))
                        (state/get-context agent))
              "the live context follows the installed projection"))
      (finally
        (fs/delete-tree dir)))))

(t/deftest test-loop-request-install-keeps-extension-custom-messages
  ;; the extension context sink injects a custom message the session already
  ;; holds (extensions/append-custom-message! persists the entry, then calls
  ;; the sink); the request install rebuilds the context from the session, so
  ;; the injected message must survive that rebuild
  (let [dir (test-tmp/temp-dir! "test-loop-request-custom-")
        requests (atom [])
        calls (atom 0)
        sess (session/create-session dir)
        agent (support/make-agent-state :session sess)]
    (try
      (extensions/set-session! sess)
      (extensions/set-context-sink! (fn [msg] (state/add-context-message! agent msg)))
      (extensions/append-custom-message! :note "injected" true)
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-text-turns calls ["done"] requests)]
        @(loop/run-agent-turn agent {:message "hi"
                                     :on-done (fn [_])
                                     :on-error (fn [_])}))
      (t/is (some #(= "injected" (get-in % [:content 0 :text]))
                  (:messages (first @requests)))
            "the persisted custom message reaches the request after the install")
      (finally
        (extensions/set-session! nil)
        (extensions/set-context-sink! nil)
        (fs/delete-tree dir)))))

(t/deftest test-loop-deferred-custom-message-enables-a-boundary-continuation
  (t/testing "a custom message deferred during the settle window counts toward
              the boundary's :can-continue (pi: _buildBoundaryContext's
              pendingCustomContext): the handler sees a runnable boundary and
              its {:continue true} is honored, with the flush landing the
              message before the continuation request"
    (let [agent (support/make-agent-state)
          calls (atom 0)
          opts (atom [])
          boundaries (atom [])
          ends (atom 0)
          late? (fn [m] (= "late" (get-in m [:content 0 :text])))]
      (try
        (support/on-event :agent-end
                          (fn [_]
                              ;; deferred once, during the settle window —
                              ;; after the last turn's flush
                            (when (= 1 (swap! ends inc))
                              (state/defer-custom-message! agent {:role :custom
                                                                  :custom-type :note
                                                                  :content "late"
                                                                  :display true}))))
        (support/on-event :agent-before-settle
                          (fn [e]
                            (swap! boundaries conj e)
                            {:continue true}))
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message (support/stub-llm-text-turns calls ["ok"] opts)]
          ;; the second boundary's rejected continuation warns to stderr
          (binding [*err* (java.io.StringWriter.)]
            @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])})))
        (let [requests (mapv :messages @opts)
              boundary (first @boundaries)]
          (t/is (true? (:can-continue boundary))
                "the deferred message makes the boundary runnable")
          (t/is (= ["late"] (mapv :content (:pending-messages boundary)))
                "and is previewed in :pending-messages")
          (t/is (= 2 (count requests))
                "the continuation ran one more attempt")
          (t/is (late? (last (second requests)))
                "the flush landed the message before the continuation request"))
        (t/is (= 1 (count (filter late? @(:messages agent))))
              "appended exactly once")
        (finally
          (support/clear-listeners!))))))

(t/deftest test-loop-turn-end-continue-honors-a-deferred-custom-message
  (t/testing "a deferred custom message makes the turn-end boundary runnable
              (pi: canContinue includes pendingCustomContext), so a handler's
              {:continue true} is honored even though the live context still
              ends with the assistant message — the flush follows the
              boundary"
    (let [agent (support/make-agent-state)
          opts (atom [])
          turns (atom 0)
          calls (atom 0)
          late? (fn [m] (= "late" (get-in m [:content 0 :text])))]
      (try
        (support/on-event :turn-end
                          (fn [_] (when (= 1 (swap! turns inc)) {:continue true})))
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message
                      (fn [o]
                        (swap! opts conj o)
                        (future
                          (when (= 1 (swap! calls inc))
                            ;; deferred mid-turn, still pending at the boundary
                            (state/defer-custom-message! agent {:role :custom
                                                                :custom-type :note
                                                                :content "late"
                                                                :display true}))
                          (when-let [on-text (:on-text o)] (on-text "ok"))
                          (when-let [on-done (:on-done o)] (on-done :stop))
                          :done))]
          @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
        (t/is (= 2 @calls) "the boundary continuation ran one more request")
        (t/is (late? (last (:messages (second @opts))))
              "with the deferred message flushed in")
        (t/is (= 1 (count (filter late? @(:messages agent))))
              "appended exactly once")
        (finally
          (support/clear-listeners!))))))

(t/deftest test-loop-events-are-plain-edn
  (t/testing "every event the loop emits is documented and round-trips through EDN"
    (let [events (atom [])
          calls (atom 0)
          agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (support/stub-llm-tool-then-text calls)
                    state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})]
        @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
      (t/is (seq @events) "the scenario emitted events")
      (doseq [ev @events]
        (t/is (contains? events/loop-event-types (:type ev))
              (str "event type outside the loop vocabulary: " (:type ev)))
        (t/is (= ev (edn/read-string (pr-str ev)))
              (str "event did not round-trip through EDN: " (:type ev)))))))

;; ─── Settle outcome (:agent-settled :outcome) ─────────────────────────────

(t/deftest test-loop-agent-settled-outcome
  (t/testing "a completed run settles :completed"
    (let [events (atom [])
          agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [o]
                      (future
                        (when-let [on-text (:on-text o)] (on-text "ok"))
                        (when-let [on-done (:on-done o)] (on-done :stop))
                        :done))]
        @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
      (t/is (= :completed (:outcome (support/event-of events :agent-settled))))))

  (t/testing "a terminal error settles :error"
    (let [events (atom [])
          agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message
                    (fn [opts]
                      (future
                        (when-let [on-error (:on-error opts)] (on-error "boom"))
                        :done))]
        @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
      (t/is (= :error (:outcome (support/event-of events :agent-settled))))))

  (t/testing "an unhandled exception settles :error"
    (let [events (atom [])
          agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (fn [_] (throw (ex-info "kaboom" {})))]
        (binding [*err* (java.io.StringWriter.)]
          @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])})))
      (t/is (= :error (:outcome (support/event-of events :agent-settled))))))

  (t/testing "a cancelled run settles :aborted"
    (let [events (atom [])
          started (promise)
          agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (fn [_] (deliver started true) (promise))]
        (let [fut (loop/run-agent-turn agent {:message "hi" :on-error (fn [_])})]
          (t/is (true? (deref started 2000 false)) "the LLM call is in flight")
          (loop/cancel-turn agent)
          (t/is (not= ::timeout (deref fut 2000 ::timeout)) "run settles after cancel")))
      (t/is (= :aborted (:outcome (support/event-of events :agent-settled))))))

  (t/testing "a prompt that cannot start still settles :error"
    (let [events (atom [])
          agent (support/make-agent-state :on-event (fn [e] (swap! events conj e)))]
      (with-redefs [cfg/get-api-key (fn [_] nil)
                    auth/configured? (fn [_] false)]
        @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
      (t/is (= :error (:outcome (support/event-of events :agent-settled)))))))
