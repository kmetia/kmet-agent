(ns kmet.app.loop.test-models
  "Model management: selection, per-call API-key resolution, the get-api-key hook,
   and endpoint discovery from the model registry."
  (:require [clojure.test :as t]
            [kmet.ai.llm :as llm]
            [kmet.ai.models :as models]
            [kmet.ai.auth :as auth]
            [kmet.app.loop.state :as state]
            [kmet.app.loop :as loop]
            [kmet.app.config :as cfg]
            [kmet.app.loop.test-support :as support]))

(t/use-fixtures :each support/with-host)

;; ─── Phase 3: model management ───────────────────────────────────────────

(t/deftest test-loop-make-agent-state-phase3-opts
  (let [agent (support/make-agent-state)]
    (t/is (instance? clojure.lang.Atom (:system-prompt-override agent)))
    (t/is (instance? clojure.lang.Atom (:transform-context agent)))
    (t/is (instance? clojure.lang.Atom (:prepare-next-turn agent)))
    (t/is (instance? clojure.lang.Atom (:finish-turn agent)))
    (t/is (instance? clojure.lang.Atom (:get-api-key agent)))
    (t/is (= [] @(:scoped-models agent)))
    (t/is (false? @(:overflow-recovered agent)))
    (t/is (nil? (:compact-token-threshold agent)))
    (t/is (nil? (:context-window @(:cfg agent))))
    (t/is (= 16384 (:compact-reserve-tokens agent)) "pi default reserveTokens")
    (t/is (= 3 (:max-retries @(:cfg agent))) "pi default maxRetries")))

(t/deftest test-loop-cycle-model
  (support/with-test-provider
    (fn []
      (let [events (atom [])
            agent (support/make-agent-state
                   :provider :test-prov :model "a"
                   :scoped-models ["test-prov/a" "test-prov/b" "test-prov/c"]
                   :on-event (fn [e] (swap! events conj e)))]
        (t/is (= "b" (state/cycle-model! agent 1)))
        (t/is (= "c" (state/cycle-model! agent 1)))
        (t/is (= "a" (state/cycle-model! agent 1)) "wraps around at the end")
        (t/is (= "c" (state/cycle-model! agent -1)) "cycles backward")
        (t/is (= "b" (state/cycle-model! agent -1)))
        (let [ev (last @events)]
          (t/is (= :model-select (:type ev)) "cycle emits :model-select")
          (t/is (= :cycle (:source ev)))
          (t/is (= "b" (:id (:model ev))))
          (t/is (= :test-prov (:provider (:model ev))))
          (t/is (= "c" (:id (:previous-model ev))))
          (t/is (= :test-prov (:provider (:previous-model ev)))))))))

(t/deftest test-loop-set-scoped-models-deduplicates
  (t/testing "duplicate scoped entries are dropped so cycling never stalls"
    (support/with-test-provider
      (fn []
        (let [agent (support/make-agent-state :provider :test-prov :model "a")]
          (state/set-scoped-models! agent ["test-prov/a" "test-prov/a" "test-prov/b"])
          (t/is (= ["test-prov/a" "test-prov/b"] @(:scoped-models agent)))
          (t/is (= "b" (state/cycle-model! agent 1))
                "the duplicate would otherwise consume the next cycle slot")))))
  (t/testing "the initial scoped list is deduplicated too"
    (let [agent (support/make-agent-state :scoped-models ["test-prov/a" "test-prov/a"])]
      (t/is (= ["test-prov/a"] @(:scoped-models agent))))))

(t/deftest test-loop-cycle-model-falls-back-to-available
  ;; pi: no session scoped models → cycle over all available models
  (support/with-test-provider
    (fn []
      (let [agent (support/make-agent-state :provider :test-prov :model "b")]
        (t/is (= "c" (state/cycle-model! agent 1)))
        (t/is (= "a" (state/cycle-model! agent 1)))))))

(t/deftest test-loop-cycle-model-switches-provider
  ;; a scoped entry carries its provider — cycling can switch providers
  (let [saved (models/get-providers)
        prov2 (models/map->Provider
               {:id :test-prov2 :name "Test2"
                :api-types #{:openai-completions}
                :models [(models/map->Model {:id "x" :name "X" :provider :test-prov2
                                             :api :openai-completions :base-url "https://t"
                                             :reasoning false :input [:text]
                                             :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                                             :context-window 1000 :max-tokens 100})]
                :env-vars [] :default-model nil})]
    (models/clear-providers!)
    (models/register-provider! (support/make-test-provider ["a" "b"]))
    (models/register-provider! prov2)
    (try
      (with-redefs [auth/auth-atom (atom {:test-prov {:key "sk"}
                                          :test-prov2 {:key "sk2"}})]
        (let [agent (support/make-agent-state
                     :provider :test-prov :model "a"
                     :scoped-models ["test-prov/a" "test-prov2/x"])]
          (t/is (= "x" (state/cycle-model! agent 1)))
          (t/is (= :test-prov2 @(:provider agent)))
          (t/is (= "a" (state/cycle-model! agent 1)))
          (t/is (= :test-prov @(:provider agent)))))
      (finally
        (models/clear-providers!)
        (doseq [p saved] (models/register-provider! p))))))

(t/deftest test-loop-cycle-model-no-models
  (support/without-providers
   (fn []
     (let [agent (support/make-agent-state :model "a")]
       (t/is (nil? (state/cycle-model! agent 1))
             "no scoped models and no available models → nil")))))

(t/deftest test-loop-init-scoped-models
  ;; pi: parsed.models ?? settingsManager.getEnabledModels — CLI/config
  ;; :models patterns win, else settings :enabled-models seed the session
  ;; scoped list at startup (the /model all/scoped toggle and Ctrl+P cycling
  ;; depend on it). Unresolved patterns are skipped with a stderr warning.
  (support/with-test-provider
    (fn []
      ;; config :models wins over :enabled-models (pi: parsed.models ?? …)
      (let [agent (state/init-scoped-models!
                   (support/make-agent-state)
                   {:models ["test-prov/b"] :enabled-models ["test-prov/a"]})]
        (t/is (= ["test-prov/b"] @(:scoped-models agent))))
      ;; :enabled-models falls back when :models is unset
      (let [agent (state/init-scoped-models!
                   (support/make-agent-state)
                   {:enabled-models ["test-prov/a" "test-prov/c"]})]
        (t/is (= ["test-prov/a" "test-prov/c"] @(:scoped-models agent))))
      ;; no patterns → list stays empty (no scoping)
      (let [agent (state/init-scoped-models!
                   (support/make-agent-state)
                   {:enabled-models nil})]
        (t/is (= [] @(:scoped-models agent))))
      ;; unresolved patterns are skipped with a stderr warning — suppress it
      ;; (hosts without per-var output capture, e.g. jolt, would leak it)
      (let [agent (binding [*err* (java.io.StringWriter.)]
                    (state/init-scoped-models!
                     (support/make-agent-state)
                     {:enabled-models ["test-prov/a" "ghost/model"]}))]
        (t/is (= ["test-prov/a"] @(:scoped-models agent)))))))

(t/deftest test-loop-switch-thinking-level
  ;; kmet rank rule (deliberately not pi's keep-with-clamp): the current
  ;; level's position among the old model's levels maps to the same position
  ;; on the new model, clamped to the new model's level count.
  (let [a (support/thinking-model "a" [:low :medium :high])     ;; 3 levels
        b (support/thinking-model "b" [:low :medium :high :max]) ;; 4 levels
        c (support/thinking-model "c" [:low :high])             ;; 2 levels
        d (support/thinking-model "d" [:max])]                  ;; 1 level
    (t/is (= :max (state/switch-thinking-level a b :high nil))
          "old highest → new highest")
    (t/is (= :high (state/switch-thinking-level a b :medium nil))
          "old second-highest → new second-highest")
    (t/is (= :medium (state/switch-thinking-level b a :high nil))
          "old second-highest (4 levels) → new second-highest (3 levels)")
    (t/is (= :low (state/switch-thinking-level b c :medium nil))
          "rank clamps to the new model's level count (3rd of 4 → last of 2)")
    (t/is (= :max (state/switch-thinking-level b d :medium nil))
          "rank clamps to the single available level")
    (t/is (= :max (state/switch-thinking-level a b :off nil))
          ":off jumps to the reasoning model's highest (pi parity)")
    (t/is (= :off (state/switch-thinking-level a (models/map->Model
                                                  {:id "n" :reasoning false})
                                               :off nil))
          "non-reasoning new model keeps :off")
    (t/is (= :high
             (state/switch-thinking-level
              a
              (models/map->Model {:id "no-off" :provider :test-prov
                                  :reasoning true :api :openai-completions
                                  :base-url "https://test"
                                  :cost {:input 0 :output 0 :cache-read 0
                                         :cache-write 0}
                                  :context-window 1000 :max-tokens 100
                                  :thinking-level-map {:off nil}})
              :off nil))
          "a model that can't disable thinking gets its highest")
    (t/is (= :low (state/switch-thinking-level a b :medium :low))
          "explicit level wins (clamped)")
    (t/is (= :off (state/switch-thinking-level a b :off :off))
          "explicit :off wins when given")))

(t/deftest test-loop-retry-setters
  (let [agent (support/make-agent-state)]
    (t/is (= 3 (:max-retries @(:cfg agent))))
    (t/is (= 2000 (:base-delay-ms @(:cfg agent))))
    (swap! (:cfg agent) assoc :max-retries 0)
    (swap! (:cfg agent) assoc :base-delay-ms 500)
    (t/is (= 0 (:max-retries @(:cfg agent))) "0 disables auto-retry")
    (t/is (= 500 (:base-delay-ms @(:cfg agent))))))

(t/deftest test-loop-set-model-emits-model-select
  (let [events (atom [])
        a (models/map->Model {:provider :p :id "a"})
        b (models/map->Model {:provider :p :id "b"})
        agent (support/make-agent-state :provider :p :model "a"
                                        :on-event (fn [e] (swap! events conj e)))]
    (with-redefs [models/providers-atom (atom {:p {:models [a b]}})]
      (state/set-model! agent b)
      (let [ev (last @events)]
        (t/is (= :model-select (:type ev)))
        (t/is (identical? b (:model ev)))
        (t/is (identical? a (:previous-model ev)) "previous resolves against the old selection")
        (t/is (= :set (:source ev))))
      ;; setting the same model is a no-op (no event)
      (state/set-model! agent b)
      ;; nil is ignored (no state change, no event)
      (state/set-model! agent nil)
      (t/is (= "b" @(:model agent)))
      (t/is (= :p @(:provider agent)))
      (t/is (= 1 (count (filter #(= :model-select (:type %)) @events)))))))

;; ─── Phase 3: dynamic API key ────────────────────────────────────────────

(t/deftest test-loop-get-api-key-hook
  (let [agent (support/make-agent-state)]
    (reset! (:get-api-key agent) (fn [_] "hook-key"))
    (with-redefs [cfg/get-api-key (fn [_] "cfg-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (t/is (= "hook-key" (:api-key opts))
                            "dynamic get-api-key hook preferred over cfg")
                      (when-let [on-text (:on-text opts)] (on-text "ok"))
                      (when-let [on-done (:on-done opts)] (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))))

(t/deftest test-loop-endpoint-from-model-registry
  ;; Phase 2: llm resolves the Model itself (registry is the unit of truth);
  ;; loop forwards provider/model plus only the agent-level overrides.
  ;; Providers without a catalog entry are unknown; overrides win over the
  ;; model.
  (models/load-catalogs! nil)
  (let [sent (atom nil)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [opts]
                    (reset! sent (select-keys opts [:provider :model :api-type :base-url]))
                    (future
                      (when-let [on-text (:on-text opts)] (on-text "hi"))
                      (when-let [on-done (:on-done opts)] (on-done :stop))))]
      (t/testing "loop forwards provider + model; llm resolves the wire api"
        @(loop/run-agent-turn (support/make-agent-state :provider :opencode-go
                                                        :model "deepseek-v4-flash")
                              {:message "hi" :on-error (fn [_])})
        (t/is (= {:provider :opencode-go :model "deepseek-v4-flash"
                  :api-type nil :base-url nil}
                 @sent)))
      (t/testing "agent-level overrides flow through to llm"
        @(loop/run-agent-turn (support/make-agent-state :provider :opencode-go
                                                        :model "deepseek-v4-flash"
                                                        :api-type :anthropic-messages
                                                        :base-url "https://custom.example/v1/messages")
                              {:message "hi" :on-error (fn [_])})
        (t/is (= {:provider :opencode-go :model "deepseek-v4-flash"
                  :api-type :anthropic-messages
                  :base-url "https://custom.example/v1/messages"}
                 @sent))))))

(t/deftest test-loop-get-api-key-resolved-per-call
  (let [keys-seen (atom [])
        llm-keys (atom [])
        calls (atom 0)
        agent (support/make-agent-state)]
    (reset! (:get-api-key agent)
            (fn [_] (swap! keys-seen conj (str "key-" (count @keys-seen)))))
    (with-redefs [cfg/get-api-key (fn [_] "cfg-key")
                  llm/send-message
                  (fn [opts]
                    (future
                      (swap! llm-keys conj (:api-key opts))
                      (if (= 1 (swap! calls inc))
                        (do (when-let [on-tc (:on-tool-call opts)]
                              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
                            (when-let [on-done (:on-done opts)]
                              (on-done :tool-calls)))
                        (do (when-let [on-text (:on-text opts)]
                              (on-text "ok"))
                            (when-let [on-done (:on-done opts)]
                              (on-done :stop))))
                      :done))
                  state/execute-tool-for (fn [_ _ _ _] {:content "ok" :is-error false})]
      @(loop/run-agent-turn agent {:message "run" :on-error (fn [_])}))
    (t/is (= 2 (count @llm-keys)) "two LLM calls in the run")
    (t/is (apply not= @llm-keys)
          "each LLM call gets a freshly resolved key")
    (t/is (>= (count @keys-seen) 3)
          "hook also resolves at run start for the nil check")))
