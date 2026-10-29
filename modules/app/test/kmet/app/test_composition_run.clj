(ns kmet.app.test-composition-run
  "The composition runs a turn — the gate 7a could not reach from a hand-mounted
   root: a pure kmet2 boot has no extension host until the `:extensions` row
   binds one, and the loop resolves its hooks through it.

   One turn, through the real rows: `create!` publishes an agent, `send!` wakes
   its run, the run reaches the (stubbed) model and settles `:completed`."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [kmet.app.config :as cfg]
            [kmet.ai.llm :as llm]
            [kmet.app.tools.registry :as tools]
            [kmet.app.compaction :as compaction]
            [kmet.app.llm :as app-llm]
            [kmet.app.loop.stages :as stages]
            [kmet.app.core :as core]
            [kmet.app.events :as events]
            [kmet.app.runtime :as runtime]
            [kmet.app.test-support :as support]
            [kmet.srce.core :as srce]))

(def ^:private sessions-dir "target/test-composition-run")

(def ^:private config
  {:provider :opencode-go
   :model "deepseek-v4-flash"
   :session-dir sessions-dir})

(deftest a-composed-agent-runs-a-turn
  (fs/create-dirs sessions-dir)
  (let [boot (runtime/boot (core/compose-config {:profile :tui}) {})]
    (try
      (let [agents (srce/get (:ctx boot) :agents)
            settled (promise)
            ;; the app's layer, not kmet1's process table: this test is what
            ;; 7b-i re-pointed, and subscribing here is the gate it leaves
            unsubscribe (events/on! (:ctx boot) :agent-settled
                                    (fn [ev] (deliver settled ev)))]
        (try
          (with-redefs [cfg/get-api-key (fn [_] "test-key")
                        llm/send-message (fn [opts]
                                           (future
                                             (when-let [done (:on-done opts)]
                                               (done :stop))))]
            (let [handle ((:create! agents) (:ctx boot) {:config config})]
              (is (= app-llm/builtin (srce/get (:ctx boot) :llm))
                  "the :llm row mounted model access; the loop's completion and
                   the summarizer resolve it here")
              (is (= compaction/builtin (srce/get (:ctx boot) :compaction))
                  (str "and the :compaction row mounted the builtin strategy, "
                       "which the loop and the session handle resolve"))
              (is (= (get-in stages/defaults [:retry-policy :call])
                     (get-in (stages/for-ctx (:ctx (:agent handle)))
                             [:retry-policy :call]))
                  (str "the agent's ctx reads the composition's stage rows — the "
                       "service the :retry-policy row provided at the mount "
                       "site, not a library bound into the loop"))
              ((:send! agents) handle "hello" {:target :next-turn})
              (is (= :completed (:outcome (deref settled 20000 ::timeout)))
                  "the booted composition runs a turn and settles it once")
              (is (= [:user :assistant]
                     (mapv :role @(:messages (:agent handle))))
                  "and the turn is the agent's context")))
          (finally (unsubscribe))))
      (finally (srce/dispose-subtree! (:ctx boot))
               (fs/delete-tree sessions-dir)))))

(deftest a-tool-threads-mount-waits-for-the-turn-boundary
  ;; 10b's gate, end to end. A tool body runs on its own future, and the
  ;; kernel's rule is that the calls that run plugin code stay on the thread
  ;; that owns the root (`srce.md` §8) — so a tool that must mount **submits**,
  ;; and the loop's drain is what makes the change live. The mount is invisible
  ;; to the turn that submitted it and present when `:agent-settled` fires,
  ;; because that event means both "no turn in flight" and "no pending
  ;; refresh" (`docs/architecture.md`, § Reload and the turn boundary).
  (fs/create-dirs sessions-dir)
  (let [boot (runtime/boot (core/compose-config {:profile :tui}) {})
        ctx (:ctx boot)]
    (try
      (let [agents (srce/get ctx :agents)
            settled (promise)
            ;; what the submitting thread saw: the run's ctx, and the service as
            ;; it read it — nil means the change was queued, not applied
            seen (atom nil)
            mount-tool
            {:name "defer-a-mount"
             :description "Submit a mount and answer without it"
             :parameters {:type "object" :properties {}}
             :contextual? true
             :execute (fn [_args _on-update _signal tool-ctx]
                        (let [run-ctx (:srce-ctx tool-ctx)]
                          (runtime/submit!
                           run-ctx
                           (fn []
                             (srce/plugin! run-ctx
                                           {:name :late
                                            :provide #{:late}
                                            :apply (fn [c _]
                                                     (srce/provide! c :late :here
                                                                    {:in (:parent c)}))})))
                          (reset! seen {:ctx run-ctx :late (srce/get run-ctx :late)})
                          {:content "submitted" :is-error false}))}
            calls (atom 0)
            unsubscribe (events/on! ctx :agent-settled (fn [ev] (deliver settled ev)))]
        (try
          (with-redefs [cfg/get-api-key (fn [_] "test-key")
                        llm/send-message
                        (fn [opts]
                          (future
                            (if (= 1 (swap! calls inc))
                              (do (when-let [on-tc (:on-tool-call opts)]
                                    (on-tc {:id "tc-mount" :name "defer-a-mount"
                                            :arguments "{}" :index 0}))
                                  (when-let [done (:on-done opts)] (done :tool-calls)))
                              (do (when-let [text (:on-text opts)] (text "ok"))
                                  (when-let [done (:on-done opts)] (done :stop))))
                            :done))]
            (tools/register-tool! (srce/get ctx :tools) mount-tool)
            (let [handle ((:create! agents) ctx {:config config})]
              ((:send! agents) handle "mount it" {:target :next-turn})
              (is (= :completed (:outcome (deref settled 20000 ::timeout))))
              (is (= (:ctx handle) (:ctx @seen)) "the tool ran in its own agent's ctx")
              (is (nil? (:late @seen))
                  "the tool thread never saw its own change: the boundary had not run")
              (is (= :here (srce/get (:ctx handle) :late))
                  "the boundary applied it before the settle event")))
          (finally
            (unsubscribe)
            (tools/unregister-tool! (srce/get ctx :tools) "defer-a-mount"))))
      (finally (srce/dispose-subtree! (:ctx boot))
               (fs/delete-tree sessions-dir)))))

(deftest a-tool-swaps-at-the-turn-boundary
  ;; 7b-ii's second leg. The registry is live and what the model is offered is
  ;; read per request (`loop-state/active-tools`), so a tool registered between
  ;; two turns is in the next turn's declaration — and out of it again the turn
  ;; after it is unregistered.
  (fs/create-dirs sessions-dir)
  (let [boot (runtime/boot (core/compose-config {:profile :tui}) {})]
    (try
      (let [agents (srce/get (:ctx boot) :agents)
            offered (atom [])
            echo {:name "custom-echo"
                  :description "Echo the given text back"
                  :parameters {:type "object"
                               :properties {"text" {:type "string"}}}
                  :execute (fn [_ _] "ok")}]
        (with-redefs [cfg/get-api-key (fn [_] "test-key")
                      llm/send-message (fn [opts]
                                         (swap! offered conj (set (map :name (:tools opts))))
                                         (future
                                           (when-let [done (:on-done opts)]
                                             (done :stop))))]
          (let [handle ((:create! agents) (:ctx boot) {:config config})
                turn! (fn [text]
                        (let [n (count @offered)]
                          ((:send! agents) handle text {:target :next-turn})
                          (is (support/wait-until #(> (count @offered) n) 20000)
                              "the turn reached the model")
                          (last @offered)))]
            (is (not (contains? (turn! "one") "custom-echo")))
            (tools/register-tool! (srce/get (:ctx boot) :tools) echo)
            (is (contains? (turn! "two") "custom-echo")
                "registered between turns, offered at the next")
            (tools/unregister-tool! (srce/get (:ctx boot) :tools) "custom-echo")
            (is (not (contains? (turn! "three") "custom-echo"))
                "and gone the turn after it is unregistered"))))
      (finally (srce/dispose-subtree! (:ctx boot))
               (fs/delete-tree sessions-dir)))))
