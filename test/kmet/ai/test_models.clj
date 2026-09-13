(ns kmet.ai.test-models
  "Phase 0: registry semantics, catalog loading, EDN shape, manifest;
   Phase 6: models.edn composition (load-models-config!)."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.ai.models :as m]
            [kmet.ai.auth :as auth]
            [kmet.libs.dynamic-value :as dynamic-value]))

;; ─── Registry semantics (pi: MutableModels) ────────────────────────────────

(defn- test-provider
  [id & [models]]
  (m/map->Provider {:id id :name (str id) :api-types #{:openai-completions}
                    :models (or models []) :env-vars [] :default-model nil}))

(t/deftest test-register-override-clear
  (m/clear-providers!)
  (m/register-provider! (test-provider :alpha))
  (t/is (= :alpha (:id (m/get-provider :alpha))))
  (t/is (= 1 (count (m/get-providers))))
  (t/testing "registering the same id replaces the entry (pi setProvider upsert)"
    (m/register-provider! (assoc (test-provider :alpha) :name "Alpha 2"))
    (t/is (= "Alpha 2" (:name (m/get-provider :alpha))))
    (t/is (= 1 (count (m/get-providers)))))
  (t/testing "unregister removes only that id"
    (m/register-provider! (test-provider :beta))
    (m/unregister-provider! :alpha)
    (t/is (nil? (m/get-provider :alpha)))
    (t/is (= :beta (:id (m/get-provider :beta)))))
  (t/testing "clear removes everything"
    (m/clear-providers!)
    (t/is (empty? (m/get-providers)))))

(t/deftest test-get-models-and-get-model
  (m/clear-providers!)
  (let [a (m/map->Model {:id "m1" :name "M1" :provider :alpha
                         :api :openai-completions :base-url "https://a.example/v1"
                         :reasoning false :input [:text]
                         :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                         :context-window 1000 :max-tokens 100})
        b (m/map->Model {:id "m2" :name "M2" :provider :alpha
                         :api :openai-completions :base-url "https://a.example/v1"
                         :reasoning true :input [:text :image]
                         :cost {:input 1 :output 2 :cache-read 3 :cache-write 4}
                         :context-window 2000 :max-tokens 200})
        c (m/map->Model {:id "m1" :name "M1" :provider :beta
                         :api :openai-completions :base-url "https://b.example/v1"
                         :reasoning false :input [:text]
                         :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                         :context-window 1000 :max-tokens 100})]
    (m/register-provider! (test-provider :alpha [a b]))
    (m/register-provider! (test-provider :beta [c]))
    (t/testing "get-models scoped to one provider (pi Models.getModels)"
      (t/is (= ["m1" "m2"] (mapv :id (m/get-models :alpha))))
      (t/is (= ["m1"] (mapv :id (m/get-models :beta))))
      (t/is (= [] (m/get-models :unknown))))
    (t/testing "get-models across all providers"
      (t/is (= ["m1" "m2" "m1"] (mapv :id (m/get-models)))))
    (t/testing "get-model by provider + id (pi Models.getModel)"
      (t/is (= b (m/get-model :alpha "m2")))
      (t/is (nil? (m/get-model :alpha "nope")))
      (t/is (nil? (m/get-model :unknown "m1"))))
    (t/testing "same id in different providers are distinct models"
      (t/is (not= (m/get-model :alpha "m1") (m/get-model :beta "m1"))))))

(t/deftest test-default-model-for
  (m/clear-providers!)
  (m/register-provider! (m/map->Provider {:id :p
                                          :name "P"
                                          :api-types #{:openai-completions}
                                          :models [(m/map->Model {:id "a" :name "A"
                                                                  :provider :p
                                                                  :api :openai-completions
                                                                  :base-url "u"
                                                                  :reasoning false
                                                                  :input [:text]
                                                                  :cost {:input 0 :output 0
                                                                         :cache-read 0
                                                                         :cache-write 0}
                                                                  :context-window 1
                                                                  :max-tokens 1})]
                                          :env-vars []
                                          :default-model "a"
                                          :base-url nil :headers nil}))
  (t/is (= "a" (:id (m/default-model-for :p))))
  (t/testing "falls back to the first catalog model when :default-model is unset"
    (m/register-provider! (assoc (m/get-provider :p) :default-model nil))
    (t/is (= "a" (:id (m/default-model-for :p))))
    (t/testing "unknown provider → nil"
      (m/unregister-provider! :p)
      (t/is (nil? (m/default-model-for :p))))))

;; ─── Catalog loading ───────────────────────────────────────────────────────

(t/deftest test-load-catalogs
  (let [providers (m/load-catalogs!)]
    (t/is (map? providers))
    (t/is (= #{:opencode-go :opencode :deepseek :github-copilot :openai :xai
               :openai-codex :azure-openai-responses :anthropic :google :groq
               :cerebras :huggingface :moonshotai :moonshotai-cn :xiaomi
               :xiaomi-token-plan-cn :xiaomi-token-plan-ams :xiaomi-token-plan-sgp
               :qwen-token-plan :qwen-token-plan-cn :qwen-token-plan-individual
               :minimax :minimax-cn :nvidia :openrouter :fireworks
               :vercel-ai-gateway :zai :zai-coding-cn :together :baseten
               :ant-ling :kimi-coding :cloudflare-workers-ai :cloudflare-ai-gateway
               :mistral :google-vertex :amazon-bedrock :commandcode}
             (set (keys providers))))
    (t/testing "provider records carry catalog metadata"
      (let [og (m/get-provider :opencode-go)]
        (t/is (= "OpenCode Go" (:name og)))
        (t/is (= ["OPENCODE_API_KEY"] (:env-vars og)))
        (t/is (= "deepseek-v4-flash" (:default-model og)))
        (t/is (= #{:openai-completions :anthropic-messages :openai-responses}
                 (:api-types og))))
      (t/is (= #{:openai-completions :anthropic-messages :google-generative-ai
                 :openai-responses}
               (:api-types (m/get-provider :opencode))))
      (t/is (= #{:openai-completions} (:api-types (m/get-provider :deepseek))))
      (t/is (= #{:openai-completions :anthropic-messages :openai-responses}
               (:api-types (m/get-provider :github-copilot))))
      (t/is (= #{:openai-responses} (:api-types (m/get-provider :openai))))
      (t/is (= #{:openai-responses :openai-completions}
               (:api-types (m/get-provider :xai))))
      (t/is (= #{:openai-codex-responses} (:api-types (m/get-provider :openai-codex))))
      (t/is (= #{:azure-openai-responses}
               (:api-types (m/get-provider :azure-openai-responses))))
      (t/is (= [] (:env-vars (m/get-provider :openai-codex)))
            "codex is OAuth-only — no env var")
      (t/is (= ["AZURE_OPENAI_API_KEY"] (:env-vars (m/get-provider :azure-openai-responses))))
      (t/is (= #{:anthropic-messages} (:api-types (m/get-provider :anthropic))))
      (t/is (= #{:google-generative-ai} (:api-types (m/get-provider :google))))
      (t/is (= #{:openai-completions} (:api-types (m/get-provider :groq))))
      (t/is (= #{:openai-completions} (:api-types (m/get-provider :cerebras))))
      (t/is (= #{:openai-completions :anthropic-messages}
               (:api-types (m/get-provider :fireworks))))
      (t/is (= #{:anthropic-messages} (:api-types (m/get-provider :minimax))))
      (t/is (= ["GROQ_API_KEY"] (:env-vars (m/get-provider :groq))))
      (t/is (= ["GEMINI_API_KEY"] (:env-vars (m/get-provider :google))))
      (t/is (= ["ANTHROPIC_AUTH_TOKEN" "ANTHROPIC_OAUTH_TOKEN" "ANTHROPIC_API_KEY"]
               (:env-vars (m/get-provider :anthropic))))
      (t/is (= ["NVIDIA_API_KEY"] (:env-vars (m/get-provider :nvidia))))
      (t/is (= ["OPENROUTER_API_KEY"] (:env-vars (m/get-provider :openrouter))))
      (t/is (= ["QWEN_TOKEN_PLAN_API_KEY"]
               (:env-vars (m/get-provider :qwen-token-plan-individual))))
      (t/is (= "moonshotai/kimi-k2.6" (:default-model (m/get-provider :openrouter))))
      (t/is (= #{:openai-completions} (:api-types (m/get-provider :zai))))
      (t/is (= #{:anthropic-messages} (:api-types (m/get-provider :kimi-coding))))
      (t/is (= #{:anthropic-messages :openai-responses}
               (:api-types (m/get-provider :cloudflare-ai-gateway))))
      (t/is (= ["ZAI_API_KEY"] (:env-vars (m/get-provider :zai))))
      (t/is (= ["KIMI_API_KEY"] (:env-vars (m/get-provider :kimi-coding))))
      (t/is (= ["CLOUDFLARE_API_KEY" "CLOUDFLARE_ACCOUNT_ID" "CLOUDFLARE_GATEWAY_ID"]
               (:env-vars (m/get-provider :cloudflare-ai-gateway))))
      (t/is (= "Ring-2.6-1T" (:default-model (m/get-provider :ant-ling))))
      (t/is (= ["DEEPSEEK_API_KEY"] (:env-vars (m/get-provider :deepseek))))
      (t/is (= ["COPILOT_GITHUB_TOKEN"] (:env-vars (m/get-provider :github-copilot))))
      (t/is (= ["OPENAI_API_KEY"] (:env-vars (m/get-provider :openai))))
      (t/is (= ["XAI_API_KEY"] (:env-vars (m/get-provider :xai))))
      (t/is (= "gpt-5.5" (:default-model (m/get-provider :openai))))
      (t/is (= "grok-4.5" (:default-model (m/get-provider :xai))))
      (t/is (= #{:mistral-conversations} (:api-types (m/get-provider :mistral))))
      (t/is (= ["MISTRAL_API_KEY"] (:env-vars (m/get-provider :mistral))))
      (t/is (= "devstral-medium-latest" (:default-model (m/get-provider :mistral))))
      (t/is (= #{:google-vertex} (:api-types (m/get-provider :google-vertex))))
      (t/is (= ["GOOGLE_CLOUD_API_KEY"] (:env-vars (m/get-provider :google-vertex))))
      (t/is (= #{:bedrock-converse-stream} (:api-types (m/get-provider :amazon-bedrock))))
      (t/is (= [] (:env-vars (m/get-provider :amazon-bedrock)))
            "bedrock auth is ambient AWS credentials — no api-key var"))
    (t/testing "default provider/model resolves against the opencode-go catalog"
      (t/is (= "deepseek-v4-flash" (:id (m/default-model-for :opencode-go)))))))

(t/deftest test-catalog-edn-shape
  ;; Per-model structural validation of the committed catalogs lives in
  ;; test-model-data (the generator's validate-groups! runs over every
  ;; model) — this fast-suite test samples one model per provider for the
  ;; registry integration aspects (Model records + get-model reachability)
  ;; instead of iterating all 1058 models.
  (m/load-catalogs!)
  (let [providers (m/get-providers)
        sampled (mapv (fn [p] (first (:models p))) providers)
        api-set #{:openai-completions :openai-responses
                  :openai-codex-responses :azure-openai-responses
                  :anthropic-messages :google-generative-ai
                  :mistral-conversations :google-vertex
                  :bedrock-converse-stream}
        violations (into []
                         (keep (fn [mod]
                                 (when mod
                                   (let [bad (cond
                                               (not (record? mod)) "not a Model record"
                                               (not (every? #(contains? mod %)
                                                            [:id :name :provider :api :base-url
                                                             :reasoning :input :cost
                                                             :context-window :max-tokens]))
                                               "missing required field"
                                               (not (string? (:id mod))) "id not a string"
                                               (not (string? (:name mod))) "name not a string"
                                               (not (keyword? (:provider mod))) "provider not a keyword"
                                               (not (contains? api-set (:api mod))) "unknown api"
                                               (not (string? (:base-url mod))) "base-url not a string"
                                               (not (boolean? (:reasoning mod))) "reasoning not a boolean"
                                               (not (and (vector? (:input mod)) (seq (:input mod))))
                                               "input not a non-empty vector"
                                               (not (every? number?
                                                            ((juxt :input :output :cache-read
                                                                   :cache-write) (:cost mod))))
                                               "cost lacks 4 numeric fields"
                                               (not (pos? (:context-window mod))) "context-window not positive"
                                               (not (pos? (:max-tokens mod))) "max-tokens not positive"
                                               (not= (:id mod) (:id (m/get-model (:provider mod)
                                                                                 (:id mod))))
                                               "not reachable via get-model"
                                               :else nil)]
                                     (when bad (str (:provider mod) "/" (:id mod) ": " bad))))))
                         sampled)]
    (t/is (seq sampled))
    (t/is (= [] violations)
          (str "sampled catalog models are well-formed, first violations: "
               (pr-str (take 5 violations))))
    (t/is (every? some? (map #(m/get-model (:id %) (:id (first (:models %)))) providers))
          "every provider's first model is reachable via get-model")
    (t/testing "copilot models carry static headers (COPILOT_STATIC_HEADERS)"
      (let [copilot (first (filter :headers (m/get-models :github-copilot)))]
        (t/is (some? copilot) "at least one copilot model carries static headers")
        (t/is (= "GitHubCopilotChat/0.35.0" (get-in copilot [:headers "User-Agent"]))))
      (t/is (nil? (:headers (m/get-model :deepseek "deepseek-v4-pro")))))))
(t/deftest test-catalog-validation
  (t/testing "missing :provider info block is rejected"
    (t/is (thrown-with-msg? Exception #"no :provider info block"
                            (@#'m/validate-catalog! "x.edn" {:models {:openai-completions {}}}))))
  (t/testing "model in two api groups is rejected"
    (let [model {:id "m" :name "M" :provider :p :api :openai-completions
                 :base-url "u" :reasoning false :input [:text]
                 :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                 :context-window 1 :max-tokens 1}
          data {:provider {:id :p}
                :models {:openai-completions {"m" model}
                         :anthropic-messages {"m" (assoc model :api :anthropic-messages)}}}]
      (t/is (thrown-with-msg? Exception #"more than one api group"
                              (@#'m/validate-catalog! "x.edn" data)))))
  (t/testing "model missing required fields is rejected"
    (t/is (thrown-with-msg? Exception #"missing required fields"
                            (@#'m/validate-catalog! "x.edn"
                                                    {:provider {:id :p}
                                                     :models {:openai-completions
                                                              {"m" {:id "m" :name "M"}}}})))))

;; ─── get-available (auth check = auth/configured?, Phase 3) ───────────────

(t/deftest test-get-available
  (m/load-catalogs!)
  (t/testing "no keys → nothing available"
    (with-redefs [auth/configured? (fn [_] false)]
      (t/is (empty? (m/get-available)))))
  (t/testing "all keys → every model available"
    (with-redefs [auth/configured? (fn [_] true)]
      (let [available (m/get-available)]
        (t/is (= (count (m/get-models)) (count available)))
        (t/is (seq (m/get-available :deepseek))))))
  (t/testing "per-provider filter"
    (with-redefs [auth/configured? (fn [p] (= p :deepseek))]
      (let [available (m/get-available)]
        (t/is (= (mapv :id (m/get-models :deepseek)) (mapv :id available)))))))

;; ─── Config interop ────────────────────────────────────────────────────────

(t/deftest test-resolve-config-model
  (m/load-catalogs!)
  (t/testing ":model setting wins"
    (t/is (= "custom" (m/resolve-config-model {:provider :opencode-go
                                               :model "custom"}))))
  (t/testing "no :model → provider default from the registry"
    (t/is (= "deepseek-v4-flash" (m/resolve-config-model {:provider :opencode-go})))
    (t/is (= "deepseek-v4-pro" (m/resolve-config-model {:provider :deepseek}))))
  (t/testing "unknown provider → nil"
    (t/is (nil? (m/resolve-config-model {:provider :nosuch-provider})))))

;; ─── Cost (pi: models.ts calculateCost) ────────────────────────────────────

(t/deftest test-calculate-cost
  (let [model (m/map->Model {:id "m" :name "M" :provider :p
                             :api :openai-completions :base-url "u"
                             :reasoning false :input [:text]
                             :cost {:input 2.0 :output 8.0 :cache-read 0.1 :cache-write 0.0}
                             :context-window 1000 :max-tokens 100})]
    (t/testing "token counts × $/M rates (pi: tokens * rate / 1e6)"
      (let [c (m/calculate-cost model {:input 1000 :output 500 :cache-read 200 :cache-write 0})]
        (t/is (= 0.002 (:input c)))
        (t/is (= 0.004 (:output c)))
        (t/is (= 0.00002 (:cache-read c)))
        (t/is (= 0.0 (:cache-write c)))
        (t/is (= 0.00602 (:total c)))))
    (t/testing "zero usage → zero cost"
      (t/is (= 0.0 (:total (m/calculate-cost model {:input 0 :output 0
                                                    :cache-read 0 :cache-write 0})))))
    (t/testing "missing rates/tokens default to zero (defensive)"
      (let [bare (m/map->Model {:id "b" :name "B" :provider :p
                                :api :openai-completions :base-url "u"
                                :reasoning false :input [:text] :cost {}
                                :context-window 1 :max-tokens 1})]
        (t/is (= 0.0 (:total (m/calculate-cost bare {:input 100 :output 100
                                                     :cache-read 0 :cache-write 0}))))))
    (t/testing "cost tiers: the highest threshold the total input exceeds wins"
      (let [tiered (assoc model :cost {:input 2.0 :output 8.0 :cache-read 0.1 :cache-write 0.0
                                       :tiers [{:input-tokens-above 200000 :input 4.0 :output 12.0
                                                :cache-read 0.2 :cache-write 0.0}
                                               {:input-tokens-above 272000 :input 10.0 :output 24.0
                                                :cache-read 0.5 :cache-write 0.0}]})]
        (t/is (= 0.002 (:input (m/calculate-cost tiered {:input 1000 :output 500
                                                         :cache-read 200 :cache-write 0})))
              "below every threshold → base rates")
        (t/is (= 0.8 (:input (m/calculate-cost tiered {:input 200000 :output 500
                                                       :cache-read 100 :cache-write 0})))
              "total input (input + cache) crosses the 200K tier")
        (t/is (= 0.006 (:output (m/calculate-cost tiered {:input 200000 :output 500
                                                          :cache-read 100 :cache-write 0})))
              "tier rates replace the base rates wholesale")
        (t/is (= 2.72 (:input (m/calculate-cost tiered {:input 272000 :output 500
                                                        :cache-read 100 :cache-write 100})))
              "crosses 272K → the highest matching tier")
        (t/is (= 0.00005 (:cache-read (m/calculate-cost tiered {:input 272000 :output 500
                                                                :cache-read 100 :cache-write 100}))))
        (t/is (= 0.0 (:cache-write (m/calculate-cost tiered {:input 272000 :output 500
                                                             :cache-read 100 :cache-write 100})))
              "the 272K tier's cache-write rate is 0"))
      (t/testing "a threshold met exactly (not exceeded) does not trigger the tier"
        (let [tiered (assoc model :cost {:input 1.0 :output 1.0 :cache-read 0 :cache-write 0
                                         :tiers [{:input-tokens-above 100 :input 9.0 :output 9.0
                                                  :cache-read 0 :cache-write 0}]})]
          (t/is (= 0.0001 (:input (m/calculate-cost tiered {:input 100 :output 0
                                                            :cache-read 0 :cache-write 0}))))
          (t/is (= 0.000909 (:input (m/calculate-cost tiered {:input 101 :output 0
                                                              :cache-read 0 :cache-write 0})))))))))

;; ─── models.edn composition (pi: ModelRuntime.rebuildProviders) ────────────

(t/deftest test-load-models-config!
  (m/load-catalogs!)
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-models-config-" (System/currentTimeMillis)))))
        agent-dir (str tmp "/agent")
        global (str agent-dir "/models.edn")
        project (str tmp "/project/.kmet/models.edn")]
    (fs/create-dirs (fs/parent global))
    (fs/create-dirs (fs/parent project))
    (try
      (spit global "{:providers {:deepseek {:base-url \"https://proxy.example/v1\"
                                            :model-overrides {\"deepseek-v4-pro\" {:context-window 555}}
                                            :api-key \"$MODELS_CONFIG_TEST_KEY\"}
                                 :my-custom {:base-url \"https://custom.example/v1\"
                                             :api :openai-completions
                                             :models [{:id \"custom-1\" :reasoning true}]}
                                 :my-literal {:base-url \"https://literal.example/v1\"
                                              :api :openai-completions
                                              :api-key \"sk-literal\"
                                              :models [{:id \"lit-1\"}]}}}\n")
      (spit project "{:providers {:my-custom {:name \"Custom Provider\"}}}\n")
      (with-redefs [fs/cwd (fn [] (str tmp "/project"))]
        (m/load-models-config! agent-dir))
      (t/testing "builtin + config providers recomposed; config-only provider added"
        (t/is (some? (m/get-provider :deepseek)))
        (t/is (some? (m/get-provider :my-custom)))
        (t/is (= "Custom Provider" (:name (m/get-provider :my-custom))))
        (t/is (= 555 (:context-window (m/get-model :deepseek "deepseek-v4-pro"))))
        (t/is (= "https://proxy.example/v1" (:base-url (m/get-model :deepseek "deepseek-flash"))))
        (t/is (= ["custom-1"] (mapv :id (m/get-models :my-custom))))
        (t/is (= :openai-completions (:api (m/get-model :my-custom "custom-1")))))
      (t/testing "auth hook: models.edn api-key resolves ahead of env (pi order)"
        (with-redefs [auth/getenv (fn [k] (when (= k "DEEPSEEK_API_KEY") "env-key"))]
          (t/is (true? (auth/configured? :deepseek)))
          (t/is (nil? (auth/resolve-api-key :deepseek)) "unresolvable $ENV key → no env fallback (pi resolveConfigValueOrThrow)")
          (with-redefs [dynamic-value/getenv (fn [k] (when (= k "MODELS_CONFIG_TEST_KEY") "cfg-key"))]
            (t/is (= "cfg-key" (auth/resolve-api-key :deepseek)))))
        (with-redefs [auth/getenv (fn [_] nil)
                      dynamic-value/getenv (fn [_] nil)]
          (t/is (false? (auth/configured? :my-custom)))
          (t/is (true? (auth/configured? :my-literal)) "literal api-key counts as configured (pi configuredRequestAuthStatus)")
          (t/is (= "sk-literal" (auth/resolve-api-key :my-literal))))
        (t/is (nil? (m/get-model-config-error))))
      (t/testing "config-only providers without auth stay unavailable (pi getAvailable)"
        (with-redefs [auth/getenv (fn [_] nil)
                      dynamic-value/getenv (fn [_] nil)]
          (t/is (false? (auth/configured? :my-custom)))))
      (t/testing "configured custom providers surface in get-available / defaults / cost"
        (with-redefs [auth/getenv (fn [_] nil)
                      dynamic-value/getenv (fn [_] nil)]
          (t/is (some #(= "lit-1" (:id %)) (m/get-available)))
          (t/is (= "lit-1" (:id (m/default-model-for :my-literal))))
          (t/is (= "lit-1" (m/resolve-config-model {:provider :my-literal})))))
      (finally
        (fs/delete-tree tmp)
        (m/load-catalogs!)))))

(t/deftest test-load-models-config-error-keeps-builtins
  (m/load-catalogs!)
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-models-config-err-" (System/currentTimeMillis)))))
        path (str tmp "/models.edn")]
    (fs/create-dirs tmp)
    (try
      (spit path "{:providers {:deepseek {:base-url")
      (let [agent-dir (str tmp "/agent")]
        (fs/create-dirs agent-dir)
        (fs/copy path (str agent-dir "/models.edn"))
        (with-redefs [fs/cwd (fn [] tmp)]
          (m/load-models-config! agent-dir)))
      (t/testing "parse failure → error surfaced, built-ins kept"
        (t/is (some? (m/get-model-config-error)))
        (t/is (= 40 (count (m/get-providers)))))
      (t/testing "composition failure falls back to the builtin provider"
        (spit path "{:providers {:broken {:models [{:id \"x\"}]}}}\n")
        (fs/copy path (str tmp "/agent/models.edn") {:replace-existing true})
        (with-redefs [fs/cwd (fn [] tmp)]
          (m/load-models-config! (str tmp "/agent")))
        (t/is (some? (m/get-model-config-error)))
        (t/is (nil? (m/get-provider :broken)) "config-only provider that fails to compose is dropped")
        (t/is (some? (m/get-provider :deepseek)))
        (t/is (some? (m/get-model :deepseek "deepseek-flash")) "builtin models kept"))
      (finally
        (fs/delete-tree tmp)
        (m/load-catalogs!)))))

(t/deftest test-load-models-config-reload-stacking
  ;; Repeated load-models-config! (startup + /reload) must compose over the
  ;; pristine builtins, never over already-composed providers: a removed
  ;; provider disappears and a changed override replaces the old one.
  (m/load-catalogs!)
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-models-reload-" (System/currentTimeMillis)))))
        path (str tmp "/models.edn")]
    (fs/create-dirs tmp)
    (try
      (spit path "{:providers {:temp-provider {:base-url \"https://temp.example/v1\"
                                               :api :openai-completions
                                               :models [{:id \"t1\"}]}
                               :deepseek {:model-overrides {\"deepseek-v4-pro\" {:context-window 777}}}}}\n")
      (let [agent-dir (str tmp "/agent")]
        (fs/create-dirs agent-dir)
        (fs/copy path (str agent-dir "/models.edn"))
        (with-redefs [fs/cwd (fn [] tmp)]
          (m/load-models-config! agent-dir)))
      (t/is (some? (m/get-provider :temp-provider)))
      (t/is (= 777 (:context-window (m/get-model :deepseek "deepseek-v4-pro"))))
      (t/testing "second load with temp-provider removed and override changed"
        (spit path "{:providers {:deepseek {:model-overrides {\"deepseek-v4-pro\" {:context-window 888}}}}}\n")
        (fs/copy path (str tmp "/agent/models.edn") {:replace-existing true})
        (with-redefs [fs/cwd (fn [] tmp)]
          (m/load-models-config! (str tmp "/agent")))
        (t/is (nil? (m/get-provider :temp-provider)) "removed provider disappears on reload")
        (t/is (= 888 (:context-window (m/get-model :deepseek "deepseek-v4-pro"))) "override replaces the old value")
        (t/is (= 40 (count (m/get-providers))) "registry back to builtin count"))
      (finally
        (fs/delete-tree tmp)
        (m/load-catalogs!)))))

(t/deftest test-composed-models-are-records
  ;; Registry contract: get-models returns Model records. Composed custom
  ;; models (plain maps from model-from-json) normalize to records.
  (m/load-catalogs!)
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-models-records-" (System/currentTimeMillis)))))
        path (str tmp "/models.edn")]
    (fs/create-dirs tmp)
    (try
      (spit path "{:providers {:custom {:base-url \"https://x/v1\" :api :openai-completions
                                        :models [{:id \"cm\"}]}}}\n")
      (let [agent-dir (str tmp "/agent")]
        (fs/create-dirs agent-dir)
        (fs/copy path (str agent-dir "/models.edn"))
        (with-redefs [fs/cwd (fn [] tmp)]
          (m/load-models-config! agent-dir)))
      (t/is (= (class (m/get-model :opencode-go "deepseek-v4-flash"))
               (class (m/get-model :custom "cm")))
            "custom models are Model records like builtin ones")
      (finally
        (fs/delete-tree tmp)
        (m/load-catalogs!)))))

;; ─── Phase 7: extension provider registration (pi ModelRuntime) ───────────

(defn- ext-model
  [id]
  (m/map->Model {:id id :name (str "Ext " id) :provider :native-ext
                 :api :openai-completions :base-url "https://native.example/v1"
                 :reasoning false :input [:text]
                 :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                 :context-window 1000 :max-tokens 100}))

(t/deftest test-register-provider-config!
  (m/load-catalogs!)
  (m/clear-extension-providers!)
  (try
    (t/testing "config-only extension provider composes + registers"
      (m/register-provider-config! :ext-prov
                                   {:base-url "https://ext.example/v1" :api :openai-completions
                                    :api-key "sk-ext" :auth-header true
                                    :models [{:id "ext-1" :reasoning true}]})
      (t/is (= "ext-prov" (:name (m/get-provider :ext-prov)))
            "name defaults to provider id")
      (t/is (= ["ext-1"] (mapv :id (m/get-models :ext-prov))))
      (t/is (= "sk-ext" (:api-key (m/get-provider :ext-prov))))
      (t/is (= true (:auth-header (m/get-provider :ext-prov))))
      (t/is (= {:base-url "https://ext.example/v1" :api :openai-completions
                :api-key "sk-ext" :auth-header true
                :models [{:id "ext-1" :reasoning true}]}
               (m/get-registered-provider-config :ext-prov)))
      (t/testing "auth: literal api-key counts as configured"
        (with-redefs [auth/getenv (fn [_] nil)
                      dynamic-value/getenv (fn [_] nil)]
          (t/is (true? (auth/configured? :ext-prov)))
          (t/is (= "sk-ext" (auth/resolve-api-key :ext-prov)))
          (t/is (some #(= "ext-1" (:id %)) (m/get-available))))))
    (t/testing "re-registration merges defined values, preserves unset ones (pi)"
      (m/register-provider-config! :ext-prov {:base-url "https://ext2.example/v1"})
      (t/is (= "https://ext2.example/v1" (:base-url (m/get-provider :ext-prov))))
      (t/is (= ["ext-1"] (mapv :id (m/get-models :ext-prov))) "models preserved")
      (t/is (= :openai-completions (:api (m/get-model :ext-prov "ext-1")))))
    (t/testing "broken re-registration throws without touching stored state"
      (t/is (thrown? Exception
                     (m/register-provider-config! :ext-prov {:models [{:id "bad"}]})))
      (t/is (= "https://ext2.example/v1" (:base-url (m/get-provider :ext-prov)))
            "previous registration intact"))
    (t/testing "unregister drops a config-only provider"
      (m/unregister-provider-config! :ext-prov)
      (t/is (nil? (m/get-provider :ext-prov)))
      (t/is (nil? (m/get-registered-provider-config :ext-prov))))
    (t/testing "extension over a builtin: base-url override, builtin restored on unregister"
      (m/register-provider-config! :deepseek {:base-url "https://ext-proxy/v1"})
      (t/is (= "https://ext-proxy/v1" (:base-url (m/get-model :deepseek "deepseek-flash"))))
      (t/is (some? (m/get-model :deepseek "deepseek-v4-pro")) "builtin models kept")
      (m/unregister-provider-config! :deepseek)
      (t/is (= "https://api.deepseek.com" (:base-url (m/get-model :deepseek "deepseek-flash")))
            "builtin restored"))
    (finally
      (m/clear-extension-providers!)
      (m/load-catalogs!))))

(t/deftest test-register-native-provider!
  (m/load-catalogs!)
  (m/clear-extension-providers!)
  (try
    (t/testing "full Provider record registers as-is"
      (let [p (m/map->Provider {:id :native-ext :name "Native Ext"
                                :api-types #{:openai-completions}
                                :models [(ext-model "n1")] :env-vars []
                                :default-model nil})]
        (m/register-native-provider! p)
        (t/is (= p (m/get-provider :native-ext)))
        (t/is (= ["n1"] (mapv :id (m/get-models :native-ext))))
        (t/is (= p (m/get-registered-native-provider :native-ext)))))
    (t/testing "native registration clears a prior config registration"
      (m/register-provider-config! :native-ext {:base-url "https://cfg/v1" :api :openai-completions
                                                :models [{:id "c1"}]})
      (let [p (m/map->Provider {:id :native-ext :name "Native" :api-types #{:openai-completions}
                                :models [(ext-model "n2")] :env-vars [] :default-model nil})]
        (m/register-native-provider! p)
        (t/is (= ["n2"] (mapv :id (m/get-models :native-ext))))
        (t/is (nil? (m/get-registered-provider-config :native-ext)))))
    (t/testing "empty provider id throws"
      (t/is (thrown? Exception
                     (m/register-native-provider!
                      (m/map->Provider {:id nil :name "X" :api-types #{} :models []
                                        :env-vars [] :default-model nil})))))
    (t/testing "unregister restores the builtin / drops config-only"
      (m/unregister-provider-config! :native-ext)
      (t/is (nil? (m/get-provider :native-ext))))
    (t/testing "registered ids surface"
      (m/register-provider-config! :ext-a {:base-url "https://a/v1" :api :openai-completions
                                           :models [{:id "a1"}]})
      (m/register-native-provider!
       (m/map->Provider {:id :ext-b :name "B" :api-types #{:openai-completions}
                         :models [(ext-model "b1")] :env-vars [] :default-model nil}))
      (t/is (= [:ext-a :ext-b] (m/get-registered-provider-ids)))
      (t/is (= 42 (count (m/get-providers))) "builtins + 2 extension providers"))
    (finally
      (m/clear-extension-providers!)
      (m/load-catalogs!))))

(t/deftest test-extension-facade
  (m/load-catalogs!)
  (m/clear-extension-providers!)
  (try
    (m/register-provider-config! :facade-ext
                                 {:base-url "https://facade/v1" :api :openai-completions
                                  :api-key "sk-facade" :auth-header true
                                  :models [{:id "f1"}]})
    (let [model (m/get-model :facade-ext "f1")]
      (t/testing "has-configured-auth"
        (t/is (true? (m/has-configured-auth model)))
        (t/is (true? (m/has-configured-auth :facade-ext))))
      (t/testing "get-provider-auth-status sources"
        (with-redefs [auth/getenv (fn [_] nil)
                      dynamic-value/getenv (fn [_] nil)]
          (t/is (= {:configured true :source :fallback}
                   (m/get-provider-auth-status :facade-ext))
                "extension-sourced key reports :fallback (pi)")
          (t/is (= {:configured false} (m/get-provider-auth-status :no-such))))
        (with-redefs [auth/getenv (fn [k] (when (= k "DEEPSEEK_API_KEY") "dk"))]
          (t/is (= {:configured true :source :environment}
                   (m/get-provider-auth-status :deepseek))))
        (with-redefs [auth/auth-atom (atom {:deepseek {:key "stored-key"}})
                      auth/getenv (fn [_] nil)]
          (t/is (= {:configured true :source :stored}
                   (m/get-provider-auth-status :deepseek)))))
      (t/testing "get-api-key-and-headers"
        (with-redefs [auth/auth-atom (atom {})
                      auth/getenv (fn [_] nil)
                      dynamic-value/getenv (fn [_] nil)]
          (t/is (= {:ok true :api-key "sk-facade" :headers nil}
                   (m/get-api-key-and-headers model)))
          (t/is (= {:ok false :error "Unknown provider: no-such-provider"}
                   (m/get-api-key-and-headers {:provider :no-such-provider :id "x"})))
          (t/testing "no key + no auth-header → ok without key (pi)"
            (t/is (= {:ok true :api-key nil :headers nil}
                     (m/get-api-key-and-headers (m/get-model :deepseek "deepseek-flash")))))
          (t/testing "no key + auth-header → error naming the provider (pi)"
            (m/register-provider-config! :keyless
                                         {:base-url "https://k/v1" :api :openai-completions
                                          :auth-header true :models [{:id "k1"}]})
            (t/is (= {:ok false :error "No API key found for \"keyless\""}
                     (m/get-api-key-and-headers (m/get-model :keyless "k1"))))))))
    (finally
      (m/clear-extension-providers!)
      (m/load-catalogs!))))

(t/deftest test-extension-over-models-edn-layer
  ;; 3 layers: builtin < models.edn < extension (pi composeModelProvider)
  (m/load-catalogs!)
  (m/clear-extension-providers!)
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-ext-models-edn-" (System/currentTimeMillis)))))
        path (str tmp "/models.edn")]
    (fs/create-dirs tmp)
    (try
      (let [orig-flash-cw (:context-window (m/get-model :deepseek "deepseek-flash"))]
        (spit path "{:providers {:deepseek {:model-overrides {\"deepseek-v4-pro\" {:context-window 444}}}}}\n")
        (let [agent-dir (str tmp "/agent")]
          (fs/create-dirs agent-dir)
          (fs/copy path (str agent-dir "/models.edn"))
          (with-redefs [fs/cwd (fn [] tmp)]
            (m/load-models-config! agent-dir))
          (m/register-provider-config! :deepseek {:base-url "https://ext-overlay/v1"})
          (t/is (= "https://ext-overlay/v1" (:base-url (m/get-model :deepseek "deepseek-flash")))
                "extension base-url wins over builtin")
          (t/is (= 444 (:context-window (m/get-model :deepseek "deepseek-v4-pro")))
                "models.edn override still applied underneath")
          (t/is (= orig-flash-cw (:context-window (m/get-model :deepseek "deepseek-flash")))
                "untouched model keeps catalog values")))
      (finally
        (fs/delete-tree tmp)
        (m/clear-extension-providers!)
        (m/load-catalogs!)))))

(t/deftest test-facade-header-errors-and-native-key
  ;; pi getApiKeyAndHeaders: unresolvable configured headers → ok:false with
  ;; the message; native provider api-keys count as configured.
  (m/load-catalogs!)
  (m/clear-extension-providers!)
  (try
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv (fn [_] nil)
                  dynamic-value/getenv (fn [_] nil)]
      (t/testing "unresolvable configured header → ok:false with the message"
        (m/register-provider-config! :hdr-ext
                                     {:base-url "https://h/v1" :api :openai-completions
                                      :headers {"X-Bad" "$MISSING_HDR"}
                                      :models [{:id "h1"}]})
        (let [r (m/get-api-key-and-headers (m/get-model :hdr-ext "h1"))]
          (t/is (false? (:ok r)))
          (t/is (str/includes? (:error r) "MISSING_HDR"))))
      (t/testing "native provider api-key counts as configured"
        (m/register-native-provider!
         (m/map->Provider {:id :native-key :name "NK" :api-types #{:openai-completions}
                           :api-key "nk"
                           :models [(m/map->Model {:id "nk1" :name "NK1"
                                                   :provider :native-key
                                                   :api :openai-completions
                                                   :base-url "https://n/v1"
                                                   :reasoning false :input [:text]
                                                   :cost {:input 0 :output 0 :cache-read 0
                                                          :cache-write 0}
                                                   :context-window 100 :max-tokens 10})]
                           :env-vars [] :default-model nil}))
        (t/is (true? (auth/configured? :native-key)))
        (t/is (= {:configured true :source :environment}
                 (m/get-provider-auth-status :native-key)))
        (t/is (= {:ok true :api-key "nk" :headers nil}
                 (m/get-api-key-and-headers (m/get-model :native-key "nk1"))))))
    (finally
      (m/clear-extension-providers!)
      (m/load-catalogs!))))

(t/deftest test-fresh-cache-wins
  (let [dir (str (fs/create-temp-dir {:dir "target" :prefix "model-cache-test-"}))
        catalog {:schema-version 1
                 :generated-at "2999-01-01T00:00:00Z"
                 :provider {:id :scratchcache :name "Scratch Cache"
                            :env-vars ["SCRATCH_CACHE_KEY"] :default-model "c1"}
                 :models {:openai-completions
                          {"c1" {:id "c1" :name "C1" :provider :scratchcache
                                 :api :openai-completions
                                 :base-url "https://cache.example/v1"
                                 :reasoning false :input [:text]
                                 :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                                 :context-window 1000 :max-tokens 100}}}}]
    (try
      (spit (str dir "/scratchcache.edn") (pr-str catalog))
      (spit (str dir "/manifest.edn")
            (pr-str {:schema-version 1 :generated-at "2999-01-01T00:00:00Z"}))
      ;; load-catalogs! announces the cache on stdout — suppress it (hosts
      ;; without per-var output capture, e.g. jolt, would leak it).
      (binding [*out* (java.io.StringWriter.)
                *err* (java.io.StringWriter.)
                m/*use-models-cache* true
                m/*models-cache-dir* dir]
        (t/is (= dir (m/fresh-model-cache)))
        (let [providers (m/load-catalogs!)]
          (t/is (= #{:scratchcache} (set (keys providers))))
          (t/is (= "Scratch Cache" (:name (m/get-provider :scratchcache))))))
      (finally
        (fs/delete-tree dir)
        (m/load-catalogs!)))))

(t/deftest test-stale-or-incomplete-cache-ignored
  (let [dir (str (fs/create-temp-dir {:dir "target" :prefix "model-cache-test-"}))
        write-cache (fn [{:keys [manifest generated-at]}]
                      (fs/delete-tree dir)
                      (fs/create-dirs dir)
                      (spit (str dir "/scratchcache.edn")
                            (pr-str {:schema-version 1 :generated-at nil
                                     :provider {:id :scratchcache :name "S"
                                                :env-vars [] :default-model nil}
                                     :models {}}))
                      (when manifest
                        (spit (str dir "/manifest.edn")
                              (pr-str {:schema-version 1 :generated-at generated-at}))))]
    (try
      (write-cache {:manifest true :generated-at "2000-01-01T00:00:00Z"})
      (binding [m/*use-models-cache* true
                m/*models-cache-dir* dir]
        (t/is (nil? (m/fresh-model-cache)) "stale generation ignored")
        (t/is (contains? (m/load-catalogs!) :deepseek) "bundled catalogs win"))
      (write-cache {:manifest false})
      (binding [m/*use-models-cache* true
                m/*models-cache-dir* dir]
        (t/is (nil? (m/fresh-model-cache)) "generation without manifest ignored"))
      (finally
        (fs/delete-tree dir)
        (m/load-catalogs!)))))

(t/deftest test-unusable-cache-falls-back-to-bundled
  (let [dir (str (fs/create-temp-dir {:dir "target" :prefix "model-cache-test-"}))]
    (try
      (spit (str dir "/scratchcache.edn") "{{{ not edn")
      (spit (str dir "/manifest.edn")
            (pr-str {:schema-version 1 :generated-at "2999-01-01T00:00:00Z"}))
      ;; load-catalogs! announces the cache and warns (stderr) when it is
      ;; unusable — suppress both (hosts without per-var output capture,
      ;; e.g. jolt, would leak them).
      (binding [*out* (java.io.StringWriter.)
                *err* (java.io.StringWriter.)
                m/*use-models-cache* true
                m/*models-cache-dir* dir]
        (let [providers (m/load-catalogs!)]
          (t/is (nil? (get providers :scratchcache)))
          (t/is (contains? providers :deepseek) "bundled catalogs win")))
      (finally
        (fs/delete-tree dir)
        (m/load-catalogs!)))))
