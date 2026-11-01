(ns kmet.ai.test-model-config
  "Phase 6: models.edn loading (global + project merge), schema validation
   error paths, get-error on parse failure (pi model-config.ts)."
  (:require [clojure.test :as t]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.ai.model-config :as mc]
            [kmet.test-tmp :as test-tmp]))

(defn- with-models-edn-paths
  "Run F with models.edn redirected to a temp global/project pair.
   AGENT-DIR (the temp agent root) pins every load-config call — no
   with-redefs needed. The project file resolves against the stubbed cwd."
  [f]
  (let [tmp (test-tmp/temp-dir! "test-model-config-")
        agent-dir (str tmp "/agent")
        global (str agent-dir "/models.edn")
        project (str tmp "/project/.kmet/models.edn")]
    (fs/create-dirs (fs/parent global))
    (fs/create-dirs (fs/parent project))
    (try
      (with-redefs [fs/cwd (fn [] (str tmp "/project"))]
        (f agent-dir global project))
      (finally
        ;; leave the atom empty for the next test namespace (drop the
        ;; project file first so the cleanup load sees only an empty dir)
        (when (fs/exists? project) (fs/delete project))
        (with-redefs [fs/cwd (fn [] (str tmp "/project"))]
          (mc/load-config! (str tmp "/empty")))
        (fs/delete-tree tmp)))))

(defn- write! [path content]
  (spit path content))

;; ─── Loading ───────────────────────────────────────────────────────────────

(t/deftest test-load-no-files
  (with-models-edn-paths
    (fn [agent-dir _global _project]
      (t/is (= {:providers {} :accounts {} :error nil} (mc/load-config agent-dir)))
      (t/is (= {:providers {} :accounts {} :error nil} (mc/load-config! agent-dir)))
      (t/is (= nil (mc/get-error)))
      (t/is (= {} (mc/get-providers))))))

(t/deftest test-load-global-only
  (with-models-edn-paths
    (fn [agent-dir global _project]
      (write! global "{:providers {:my {:base-url \"https://x/v1\" :api :openai-completions
                                        :models [{:id \"m1\"}]}}}\n")
      (let [config (mc/load-config agent-dir)]
        (t/is (nil? (:error config)))
        (t/is (= "https://x/v1" (get-in config [:providers :my :base-url])))
        (t/is (= [{:id "m1"}] (get-in config [:providers :my :models])))))))

(t/deftest test-load-project-overrides-global
  (with-models-edn-paths
    (fn [agent-dir global project]
      (write! global "{:providers {:my {:base-url \"https://global/v1\" :api :openai-completions
                                        :models [{:id \"m1\"}]}
                                   :other {:base-url \"https://other/v1\" :api :anthropic-messages
                                           :models [{:id \"m2\"}]}}}\n")
      (write! project "{:providers {:my {:base-url \"https://project/v1\"}}}\n")
      (let [config (mc/load-config agent-dir)]
        (t/is (nil? (:error config)))
        (t/testing "project base-url wins; untouched providers survive the deep merge"
          (t/is (= "https://project/v1" (get-in config [:providers :my :base-url])))
          (t/is (= "https://other/v1" (get-in config [:providers :other :base-url])))
          (t/is (= [{:id "m1"}] (get-in config [:providers :my :models]))))))))

(t/deftest test-load-parse-error
  (with-models-edn-paths
    (fn [agent-dir global _project]
      (write! global "{:providers {:my {:base-url")
      (let [config (mc/load-config agent-dir)]
        (t/is (= {} (:providers config)))
        (t/is (some? (:error config)))
        (t/is (str/starts-with? (:error config) "Failed to parse models.edn:"))
        (t/is (str/includes? (:error config) "File: "))))))

(t/deftest test-load-non-map-root
  (with-models-edn-paths
    (fn [agent-dir global _project]
      (write! global "[1 2 3]\n")
      (let [config (mc/load-config agent-dir)]
        (t/is (= {} (:providers config)))
        (t/is (str/includes? (:error config) "root: expected object"))))))

(t/deftest test-load-into-atom
  (with-models-edn-paths
    (fn [agent-dir global _project]
      (write! global "{:providers {:my {:base-url \"https://x/v1\" :api :openai-completions
                                        :models [{:id \"m1\"}]}}}\n")
      (t/is (= {:providers {:my {:base-url "https://x/v1" :api :openai-completions :models [{:id "m1"}]}}
                :accounts {}
                :error nil}
               (mc/load-config! agent-dir)))
      (t/is (= {:my {:base-url "https://x/v1" :api :openai-completions :models [{:id "m1"}]}}
               (mc/get-providers)))
      (t/is (= [:my] (mc/get-provider-ids)))
      (t/is (= "https://x/v1" (get-in (mc/get-provider :my) [:base-url])))
      (t/is (nil? (mc/get-provider :nope)))
      (t/is (nil? (mc/get-error))))))

;; ─── Schema validation ─────────────────────────────────────────────────────

(t/deftest test-validate-config-valid
  (t/is (= [] (mc/validate-config
               {:providers
                {:my {:name "My Provider"
                      :base-url "https://api.example.com/v1"
                      :api-key "$MY_PROVIDER_KEY"
                      :api :openai-completions
                      :headers {"X-Custom" "value"}
                      :compat {:supports-reasoning-effort true}
                      :auth-header true
                      :models [{:id "my-model" :name "My Model"
                                :reasoning true :input [:text :image]
                                :thinking-level-map {:high "high" :max nil}
                                :context-window 200000 :max-tokens 16384
                                :cost {:input 0.5 :output 1.5 :cache-read 0.1 :cache-write 0.5}
                                :headers {"X-Model" "m"}
                                :compat {:max-tokens-field :max-tokens}}]
                      :model-overrides
                      {"deepseek-v4-flash" {:context-window 128000
                                            :cost {:input 0.2}}}}}}))))

(t/deftest test-validate-config-errors
  (t/testing "missing providers key"
    (t/is (= ["providers: expected object"] (mc/validate-config {}))))
  (t/testing "non-map root"
    (t/is (= ["root: expected object"] (mc/validate-config [1]))))
  (t/testing "model missing id and definition cost requires all four rates"
    (t/is (= ["providers.my.models[0].id: required non-empty string"
              "providers.my.models[0].cost.input: expected number"
              "providers.my.models[0].cost.cache-read: expected number"
              "providers.my.models[0].cost.cache-write: expected number"]
             (mc/validate-config {:providers {:my {:models [{:cost {:output 1}}]}}}))))
  (t/testing "full path-style messages"
    (t/is (= ["providers.my.models[0].cost: expected map"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :cost 5}]}}})))
    (t/is (= ["providers.my.models[0].cost.tiers: expected array"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :cost {:input 1 :output 1
                                                                            :cache-read 0 :cache-write 0
                                                                            :tiers 5}}]}}})))
    (t/is (= ["providers.my.models[0].cost.tiers[0]: expected map"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :cost {:input 1 :output 1
                                                                            :cache-read 0 :cache-write 0
                                                                            :tiers [5]}}]}}})))
    (t/is (= ["providers.my.models[0].cost.tiers[0].input-tokens-above: expected number"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :cost {:input 1 :output 1
                                                                            :cache-read 0 :cache-write 0
                                                                            :tiers [{:input-tokens-above "big"
                                                                                     :input 2 :output 2
                                                                                     :cache-read 0 :cache-write 0}]}}]}}})))
    (t/is (= ["providers.my.models[0].cost.tiers[0].output: expected number"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :cost {:input 1 :output 1
                                                                            :cache-read 0 :cache-write 0
                                                                            :tiers [{:input-tokens-above 100
                                                                                     :input 2 :output "x"
                                                                                     :cache-read 0 :cache-write 0}]}}]}}})))
    (t/testing "a valid tier array validates clean"
      (t/is (= [] (mc/validate-config {:providers {:my {:models [{:id "m" :cost {:input 1 :output 1
                                                                                 :cache-read 0 :cache-write 0
                                                                                 :tiers [{:input-tokens-above 272000
                                                                                          :input 2 :output 1.5
                                                                                          :cache-read 0 :cache-write 0}]}}]}}}))))
    (t/is (= ["providers.my.models[0].context-window: expected number"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :context-window "big"}]}}})))
    (t/is (= ["providers.my.model-overrides.deepseek-v4-flash.cost.input: expected number"]
             (mc/validate-config {:providers {:my {:model-overrides {"deepseek-v4-flash" {:cost {:input "x"}}}}}})))
    (t/is (= ["providers.my.models[0].compat.supports-reasoning-effort: expected boolean"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :compat {:supports-reasoning-effort "yes"}}]}}})))
    (t/is (= ["providers.my.headers.X-Custom: expected string"]
             (mc/validate-config {:providers {:my {:headers {"X-Custom" 5}}}})))
    (t/is (= ["providers.my.models[0].input: expected :text or :image"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :input [:text :video]}]}}})))
    (t/is (= ["providers.my.models[0].thinking-level-map.ultra: unknown thinking level"]
             (mc/validate-config {:providers {:my {:models [{:id "m" :thinking-level-map {:ultra "x"}}]}}}))))
  (t/testing "unknown keys pass (pi TypeBox allows additional properties)"
    (t/is (= [] (mc/validate-config {:providers {:my {:models [{:id "m" :typo-key 1}]}}})))))

(t/deftest test-string-provider-id-normalized
  (t/testing "string provider keys normalize to keywords (pi models.json ids are strings)"
    (with-models-edn-paths
      (fn [agent-dir global _project]
        (write! global "{:providers {\"string-prov\" {:base-url \"https://x/v1\" :api :openai-completions
                                                     :models [{:id \"m\"}]}}}\n")
        (let [config (mc/load-config agent-dir)]
          (t/is (nil? (:error config)))
          (t/is (= [:string-prov] (keys (:providers config))))
          (t/is (= "https://x/v1" (get-in config [:providers :string-prov :base-url]))))))))

;; ─── Account aliases ───────────────────────────────────────────────────────

(t/deftest test-account-config-load
  (with-models-edn-paths
    (fn [agent-dir global _project]
      (write! global (str "{:accounts {:github-copilot#work {}\n"
                          "            :deepseek#work {:api-key \"sk-work\" :name \"DeepSeek Work\"}\n"
                          "            \"openai#personal\" {:provider \"openai\"}}}\n"))
      (let [config (mc/load-config! agent-dir)]
        (t/is (nil? (:error config)))
        (t/is (= {:github-copilot#work {}
                  :deepseek#work {:api-key "sk-work" :name "DeepSeek Work"}
                  :openai#personal {:provider :openai}}
                 (:accounts config)))
        (t/is (= [:github-copilot#work :deepseek#work :openai#personal]
                 (mc/get-account-ids)))))))

(t/deftest test-account-config-project-merge
  (with-models-edn-paths
    (fn [agent-dir global project]
      (write! global "{:accounts {:deepseek#work {:api-key \"global\"}\n            :deepseek#home {:api-key \"home\"}}}\n")
      (write! project "{:accounts {:deepseek#work {:api-key \"project\"}}}\n")
      (let [config (mc/load-config agent-dir)]
        (t/is (nil? (:error config)))
        (t/is (= "project" (get-in config [:accounts :deepseek#work :api-key])))
        (t/is (= "home" (get-in config [:accounts :deepseek#home :api-key])))))))

(t/deftest test-account-validation
  (t/is (= [] (mc/validate-config {:accounts {:deepseek#work {}}})))
  (t/is (= [] (mc/validate-config {:accounts {:work {:provider :deepseek} :deepseek#home {}}})))
  (t/testing "an account without a provider or base#label id is rejected"
    (t/is (= ["accounts.work.provider: required unless the account id has the form base#label"]
             (mc/validate-config {:accounts {:work {}}}))))
  (t/testing "shape errors use the account path"
    (t/is (= ["accounts.deepseek#work: expected map"]
             (mc/validate-config {:accounts {:deepseek#work []}})))
    (t/is (= ["accounts.deepseek#work.name: expected non-empty string"]
             (mc/validate-config {:accounts {:deepseek#work {:name ""}}})))
    (t/is (= ["accounts.deepseek#work.api-key: expected non-empty string"]
             (mc/validate-config {:accounts {:deepseek#work {:api-key 5}}})))
    (t/is (= ["accounts.7: provider id must be a keyword or string"]
             (mc/validate-config {:accounts {7 {}}})))
    (t/is (= ["accounts.github-copilot/work: account alias id must not contain '/'"]
             (mc/validate-config {:accounts {:github-copilot/work {}}}))
          "'/' is the provider/model separator, so an alias id cannot be namespaced")
    (t/is (= ["accounts.wörk: account alias id must be ASCII letters, digits, '-', '_' or '.'"]
             (mc/validate-config {:accounts {:wörk {}}}))
          "an alias id is ASCII — it is carried by model refs, flags and terminals")))

(t/deftest test-parse-account-ref
  (t/is (= {:id :github-copilot#work :provider :github-copilot}
           (mc/parse-account-ref "github-copilot#work")))
  (t/is (= {:id :deepseek#work#two :provider :deepseek}
           (mc/parse-account-ref "  deepseek#work#two ")))
  (t/is (= {:id :deepseek#work :provider :deepseek}
           (mc/parse-account-ref " deepseek # work "))
        "whitespace around the halves is trimmed")
  (t/is (nil? (mc/parse-account-ref "deepseek")))
  (t/is (nil? (mc/parse-account-ref "deepseek#")))
  (t/is (nil? (mc/parse-account-ref "deepseek # ")))
  (t/is (nil? (mc/parse-account-ref "#work"))))

(t/deftest test-writable-account-id
  (t/is (true? (mc/writable-account-id? :deepseek#work)))
  (t/is (true? (mc/writable-account-id? "deepseek#work")))
  (t/is (true? (mc/writable-account-id? :work.eu_2-x)))
  (t/is (true? (mc/writable-account-id? :deepseek#work#two)))
  (t/is (false? (mc/writable-account-id? (keyword "deep seek#work")))
        "an EDN keyword name cannot embed whitespace")
  (t/is (false? (mc/writable-account-id? :deepseek/work))
        "'/' is the provider/model separator")
  (t/is (false? (mc/writable-account-id? :deepseek#wörk))
        "ids are ASCII: they ride on provider/model refs, CLI flags and terminals")
  (t/is (false? (mc/writable-account-id? :wörk)))
  (t/is (false? (mc/writable-account-id? "deepseek#wörk")))
  (t/is (false? (mc/writable-account-id? :deepseek#)))
  (t/is (not (mc/writable-account-id? ""))))

(t/deftest test-add-account!
  (with-models-edn-paths
    (fn [agent-dir global project]
      (t/testing "creates the global models.edn with the :accounts map"
        (mc/add-account! :github-copilot#work {} agent-dir)
        (t/is (= {:accounts {:github-copilot#work {}}}
                 (edn/read-string (slurp global)))))
      (t/testing "merges into an existing file and preserves the rest"
        (write! global ";; my models\n{:providers {:my {:base-url \"https://x/v1\"\n                                           :api :openai-completions\n                                           :models [{:id \"m1\"}]}}}\n")
        (mc/add-account! :deepseek#work {:provider :deepseek} agent-dir)
        (let [text (slurp global)
              config (edn/read-string text)]
          (t/is (str/includes? text ";; my models") "comments survive the append")
          (t/is (= {:my {:base-url "https://x/v1" :api :openai-completions
                         :models [{:id "m1"}]}}
                   (:providers config)))
          (t/is (= {:provider :deepseek} (get-in config [:accounts :deepseek#work])))))
      (t/testing "replaces an existing declaration"
        (mc/add-account! :deepseek#work {:provider :deepseek :name "Work"} agent-dir)
        (t/is (= {:provider :deepseek :name "Work"}
                 (get-in (edn/read-string (slurp global)) [:accounts :deepseek#work]))))
      (t/testing "treats a blank file as empty"
        (spit global "  \n")
        (mc/add-account! :deepseek#home {} agent-dir)
        (t/is (= {:accounts {:deepseek#home {}}}
                 (edn/read-string (slurp global)))))
      (t/testing "refuses an id that would not read back"
        (write! global "{:accounts {}}\n")
        (t/is (thrown? Exception
                       (mc/add-account! (keyword "deep seek#x") {} agent-dir)))
        (t/is (thrown? Exception (mc/add-account! :deepseek#wörk {} agent-dir)))
        (t/is (= "{:accounts {}}\n" (slurp global))
              "the file is untouched"))
      (t/testing "refuses to rewrite a malformed models.edn"
        (write! global "{:providers {:my {:base-url")
        (t/is (thrown? Exception (mc/add-account! :deepseek#home {} agent-dir)))
        (t/is (= "{:providers {:my {:base-url" (slurp global))))
      (t/testing "the project file is never written"
        (t/is (not (fs/exists? project)))))))
