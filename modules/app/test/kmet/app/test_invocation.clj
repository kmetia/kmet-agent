(ns kmet.app.test-invocation
  "kmet.app.invocation: the entry's half of the run flags (model patterns
   resolved against the catalog before anything boots, as kmet1 resolves them)
   and the mode's (`create-opts`, the map the driver applies over the settings
   it loads)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.ai.models :as models]
            [kmet.app.invocation :as invocation]))

(def ^:private catalog
  [{:provider :opencode-go :id "deepseek-v4-flash" :reasoning true}
   {:provider :anthropic :id "claude-sonnet" :reasoning true}])

(defn- with-catalog
  "Run F with CATALOG as the registry: one reasoning model per provider."
  [f]
  (with-redefs [models/get-provider (fn [p] {:id p})
                models/get-models (fn [& _] catalog)]
    (f)))

(deftest a-model-pattern-becomes-the-catalogs-provider-model-and-level
  ;; `--model` is kmet1's pattern, and the entry resolves it the way kmet1's
  ;; does: the provider inferred from `provider/model`, the id from the catalog,
  ;; the suffix as the thinking level. A mode that runs kmet1's front end has
  ;; nothing left to resolve; a mode that creates through `:agents` hands the
  ;; ids on and the factory resolves them again (an id is a pattern).
  (with-catalog
    (fn []
      (is (= {:provider :opencode-go :model "deepseek-v4-flash" :thinking :high}
             (invocation/resolve-overrides
              {:model "opencode-go/deepseek-v4-flash:high"})))
      (is (= {:provider :anthropic :model "claude-sonnet"}
             (invocation/resolve-overrides {:model "claude-sonnet"}))
          "a bare id resolves to the one provider that has it"))))

(deftest an-explicit-thinking-flag-wins-over-the-patterns-suffix
  (with-catalog
    (fn []
      (is (= :low (:thinking (invocation/resolve-overrides
                              {:model "opencode-go/deepseek-v4-flash:high"
                               :thinking :low})))
          "the flag is the more specific answer, as in kmet1's resolver"))))

(deftest a-model-the-catalog-does-not-have-is-a-named-failure
  (with-catalog
    (fn []
      (let [err (try (invocation/resolve-overrides {:model "no-such-model"})
                     (catch Exception e e))]
        (is (= :kmet.app/unknown-model (:type (ex-data err))))
        (is (str/includes? (ex-message err) "Model \"no-such-model\" not found")
            "kmet1's wording, so the entry reports it like any other
             configuration error")))))

(deftest a-model-scope-resolves-to-ids-with-a-warning-for-what-matches-nothing
  (with-catalog
    (fn []
      (let [err (java.io.StringWriter.)
            opts (binding [*err* err]
                   (invocation/resolve-overrides {:models ["deepseek-v4-flash" "nope"]}))]
        (is (= ["deepseek-v4-flash"] (:models opts))
            "the scope is ids, as kmet1 stores it; the unmatched pattern is not
             invented back")
        (is (str/includes? (str err) "No models match pattern \"nope\"")
            "and it says which pattern it left out")))))

(deftest an-invocation-without-model-flags-never-reads-the-catalog
  ;; Most runs name no model; resolving must not make them depend on the
  ;; registry, which a `main` called from a test has not loaded.
  (with-redefs [models/get-models (fn [& _] (throw (ex-info "catalog read" {})))
                models/get-provider (fn [_] (throw (ex-info "catalog read" {})))]
    (is (= {:args ["hi"] :thinking :high :system-prompt "s"}
           (invocation/resolve-overrides
            {:args ["hi"] :thinking :high :system-prompt "s"})))))

(deftest create-opts-is-the-run-overrides-and-nothing-of-the-modes-own
  (is (= {:model "deepseek-v4-flash" :provider :opencode-go :thinking :high
          :models ["deepseek-v4-flash"] :session-dir "target/sessions"
          :no-context-files true
          :system-prompt "SYS" :append-system-prompt ["MORE"]}
         (invocation/create-opts
          {:model "deepseek-v4-flash" :provider :opencode-go :thinking :high
           :models ["deepseek-v4-flash"] :session-dir "target/sessions"
           :no-context-files true
           :system-prompt "SYS" :append-system-prompt ["MORE"]
           ;; the mode's own, which `create!` must not see
           :prompt "hi" :format :text :port 0 :on-result :result
           :session "1a001ea0a00" :continue true}))))
