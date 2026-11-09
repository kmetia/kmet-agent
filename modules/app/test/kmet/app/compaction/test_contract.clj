(ns kmet.app.compaction.test-contract
  "The `:compaction` contract (kmet.app.compaction): callers reach whatever the
   agent's composition mounted, a state outside one falls back to the builtin,
   and the row provides that builtin without accepting a `:config` the budgets
   do not read."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.compaction :as compaction]
            [kmet.app.compaction.stage :as stage]
            [kmet.app.compaction.vcc :as vcc]
            [kmet.srce.core :as srce]))

(deftest the-builtin-is-the-stage-implementation
  (is (= #{:maybe-compact! :compact-for-overflow! :compact-context!
           :omit-recovery-attempt! :abort-compaction! :reset-compaction-blocked!
           :stage-retry-policy :generate-branch-summary}
         (set (keys compaction/builtin)))
      "one operation per entry point the loop and the session handle call")
  (is (every? fn? (vals compaction/builtin))))

(deftest a-state-outside-a-composition-falls-back-to-the-builtin
  ;; no ctx, no service: the contract runs the builtin's own body
  (let [agent {:ctx nil
               :compaction-blocked (atom true)
               :compaction-signal (atom false)}]
    (compaction/reset-compaction-blocked! agent)
    (is (false? @(:compaction-blocked agent)) "the builtin's body ran")
    (is (nil? (compaction/abort-compaction! agent)))
    (is (true? @(:compaction-signal agent)))))

(deftest a-mounted-implementation-is-what-callers-reach
  (let [ctx (srce/root)
        calls (atom [])
        agent {:ctx ctx :session nil}
        mounted (assoc compaction/builtin
                       :maybe-compact! (fn [a] (swap! calls conj a) :replaced)
                       :abort-compaction! (fn [_] :replaced-abort))]
    (srce/provide! ctx :compaction mounted)
    (is (= :replaced (compaction/maybe-compact! agent))
        "the loop reaches the mounted strategy, not the library")
    (is (= :replaced-abort (compaction/abort-compaction! agent)))
    (is (= [agent] @calls) "with the agent it was given")))

(deftest the-row-provides-the-builtin-and-takes-no-config
  (let [world (srce/root)]
    (srce/provide! world :compactor/llm stage/llm-strategy)
    (srce/provide! world :compactor/vcc vcc/strategy)
    (srce/plugin! world #'compaction/plugin nil)
    (is (= compaction/builtin (srce/get world :compaction))
        "the row's value is the builtin; a profile replaces it by id")
    (testing "a :config the row would ignore is refused, not silently dropped"
      (is (thrown-with-msg? Exception #"takes no :config"
                            ((:apply compaction/plugin)
                             (srce/extend world {}) {:reserve-tokens 1}))))))

(deftest the-row-refuses-a-missing-compactor-service
  (let [world (srce/root)
        message (try
                  ((:apply compaction/plugin) (srce/extend world {}) nil)
                  nil
                  (catch Exception e (ex-message e)))]
    (is (re-find #"requires :compactor/llm" message)
        "a composition that omits a strategy fails at mount, not at use")))
