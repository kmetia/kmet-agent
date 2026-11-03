(ns kmet.ai.test-provider-composer
  "Phase 6: 3-layer composition (builtin + models.edn + extension), model
   overrides (incl. thinking-level-map merge), custom model defaults
   (api/base-url errors, context-window 128000), modelOverrides applied
   last, auth-header flag, configured-auth-status (pi provider-composer.ts)."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.libs.dynamic-value :as dynamic-value]
            [kmet.ai.models :as models]
            [kmet.ai.provider-composer :as pc]))

(defn- base-model
  "A minimal builtin model map."
  [id & [overrides]]
  (merge {:id id :name (str "Model " id) :provider :builtin
          :api :openai-completions :base-url "https://builtin.example/v1"
          :reasoning false :input [:text]
          :cost {:input 1 :output 2 :cache-read 0.5 :cache-write 0}
          :context-window 100000 :max-tokens 8192
          :headers nil :compat {}}
         overrides))

(defn- base-provider
  "A minimal builtin provider map."
  [& models]
  {:id :builtin :name "Builtin" :api-types (set (map :api models))
   :models (vec models) :env-vars ["BUILTIN_KEY"] :default-model "m1"
   :base-url "https://builtin.example/v1" :headers nil})

;; ─── Custom model construction (pi modelFromJson) ─────────────────────────

(t/deftest test-model-from-json-defaults
  (let [model (pc/compose-model-provider
               :custom nil
               {:base-url "https://custom.example/v1" :api :openai-completions
                :models [{:id "my-model"}]}
               nil)]
    (t/is (= 1 (count (:models model))))
    (let [m (first (:models model))]
      (t/testing "name defaults to id; api/base-url from provider config"
        (t/is (= "my-model" (:name m)))
        (t/is (= :openai-completions (:api m)))
        (t/is (= "https://custom.example/v1" (:base-url m))))
      (t/testing "reasoning false, input [:text], cost zeros, 128000/16384 defaults"
        (t/is (= false (:reasoning m)))
        (t/is (= [:text] (:input m)))
        (t/is (= {:input 0 :output 0 :cache-read 0 :cache-write 0} (:cost m)))
        (t/is (= 128000 (:context-window m)))
        (t/is (= 16384 (:max-tokens m))))
      (t/testing "provider record fields"
        (t/is (= :custom (:id model)))
        (t/is (= :openai-completions (first (:api-types model))))))))

(t/deftest test-model-from-json-errors
  (t/testing "missing api at model and provider level"
    (let [e (try (pc/compose-model-provider :custom nil
                                            {:base-url "https://x/v1" :models [{:id "m"}]}
                                            nil)
                 (catch Exception e e))]
      (t/is (= "Provider custom, model m: no \"api\" specified. Set at provider or model level."
               (ex-message e)))))
  (t/testing "missing base-url"
    (let [e (try (pc/compose-model-provider :custom nil
                                            {:api :openai-completions :models [{:id "m"}]}
                                            nil)
                 (catch Exception e e))]
      (t/is (= "Provider custom: \"base-url\" is required when defining custom models."
               (ex-message e)))))
  (t/testing "non-positive context-window / max-tokens"
    (let [e (try (pc/compose-model-provider :custom nil
                                            {:base-url "https://x/v1" :api :openai-completions
                                             :models [{:id "m" :context-window 0}]}
                                            nil)
                 (catch Exception e e))]
      (t/is (= "Provider custom, model m: invalid contextWindow" (ex-message e)))))
  (t/testing "empty config defines nothing (pi: must specify...)"
    (let [e (try (pc/compose-model-provider :custom nil {:name "X"} nil)
                 (catch Exception e e))]
      (t/is (str/starts-with? (ex-message e)
                              "Provider custom: must specify")))))

;; ─── Builtin overlay (pi applyModelsJson) ──────────────────────────────────

(t/deftest test-apply-models-json-overrides
  (let [model (pc/compose-model-provider
               :builtin (base-provider (base-model "m1"))
               {:base-url "https://proxy.example/v1"
                :compat {:supports-reasoning-effort false}}
               nil)
        m (first (:models model))]
    (t/testing "provider base-url overrides every base model"
      (t/is (= "https://proxy.example/v1" (:base-url m))))
    (t/testing "provider compat merges onto model compat"
      (t/is (= false (:supports-reasoning-effort (:compat m))))
      (t/is (= false (:reasoning m)) "untouched fields survive"))
    (t/testing "builtin env-vars/default-model carried through"
      (t/is (= ["BUILTIN_KEY"] (:env-vars model)))
      (t/is (= "m1" (:default-model model))))))

(t/deftest test-models-upsert-and-append
  (t/testing "existing id replaced in place, new id appended"
    (let [model (pc/compose-model-provider
                 :builtin (base-provider (base-model "m1") (base-model "m2"))
                 {:models [{:id "m1" :name "Replaced"}
                           {:id "m3" :reasoning true}]}
                 nil)
          ids (mapv :id (:models model))]
      (t/is (= ["m1" "m2" "m3"] ids))
      (t/is (= "Replaced" (:name (first (:models model)))))
      (t/testing "upserted model keeps base defaults (api/base-url from the existing model)"
        (t/is (= :openai-completions (:api (first (:models model)))))
        (t/is (= "https://builtin.example/v1" (:base-url (first (:models model))))))
      (t/testing "new model takes defaults from the first existing model"
        (let [m3 (nth (:models model) 2)]
          (t/is (= :openai-completions (:api m3)))
          (t/is (= "https://builtin.example/v1" (:base-url m3)))
          (t/is (= true (:reasoning m3))))))))

;; ─── Model overrides (pi applyModelOverride, topmost layer) ────────────────

(t/deftest test-model-overrides
  (let [model (pc/compose-model-provider
               :builtin (base-provider (base-model "m1" {:thinking-level-map {:high "high"}}))
               {:model-overrides
                {"m1" {:name "Overridden"
                       :context-window 999
                       :thinking-level-map {:medium "med" :max nil}
                       :cost {:input 9}
                       :compat {:supports-store false}}}}
               nil)
        m (first (:models model))]
    (t/testing "field-wise ?? merge"
      (t/is (= "Overridden" (:name m)))
      (t/is (= 999 (:context-window m)))
      (t/is (= 8192 (:max-tokens m)) "unoverridden field stays")
      (t/is (= 9 (:input (:cost m))))
      (t/is (= 2 (:output (:cost m))) "partial cost merge")
      (t/is (= false (:supports-store (:compat m)))))
    (t/testing "thinking-level-map merges key-wise"
      (t/is (= {:high "high" :medium "med" :max nil} (:thinking-level-map m))))))

(t/deftest test-model-override-compat-nil-does-not-erase
  ;; merge-compat drops a nil override (the rule resolved-openai-compat
  ;; applies to a model's own :compat): writing a key out empty must fall
  ;; back to the provider's value, not delete it.
  (let [model (pc/compose-model-provider
               :builtin (base-provider (base-model "m1" {:compat {:supports-store false
                                                                  :cache-control-format :anthropic
                                                                  :thinking-format "openai"}}))
               {:model-overrides
                {"m1" {:compat {:supports-store nil
                                :cache-control-format nil
                                :supports-reasoning-effort false}}}}
               nil)
        m (first (:models model))]
    (t/is (= false (:supports-store (:compat m))) "the provider value survives")
    (t/is (= :anthropic (:cache-control-format (:compat m))))
    (t/is (= false (:supports-reasoning-effort (:compat m))) "a real override still wins")
    (t/is (= :openai (:thinking-format (:compat m)))
          "normalization still runs over the merged map")))

(t/deftest test-model-overrides-wins-over-upsert
  (t/testing "modelOverrides apply after custom-model upserts (pi: topmost layer)"
    (let [model (pc/compose-model-provider
                 :builtin (base-provider (base-model "m1"))
                 {:models [{:id "m1" :name "Upserted" :context-window 100}]
                  :model-overrides {"m1" {:context-window 200}}}
                 nil)
          m (first (:models model))]
      (t/is (= "Upserted" (:name m)) "upsert name kept")
      (t/is (= 200 (:context-window m)) "override wins over the upsert"))))

;; ─── Extension layer (Phase 7 shape) ───────────────────────────────────────

(t/deftest test-extension-layer
  (t/testing "extension name/base-url win; extension models replace wholesale"
    (let [model (pc/compose-model-provider
                 :builtin (base-provider (base-model "m1"))
                 nil
                 {:name "Ext" :base-url "https://ext.example/v1"
                  :models [{:id "e1" :api :anthropic-messages}]})
          m (first (:models model))]
      (t/is (= "Ext" (:name model)))
      (t/is (= "https://ext.example/v1" (:base-url model)))
      (t/is (= ["e1"] (mapv :id (:models model))))
      (t/is (= :anthropic-messages (:api m)))
      (t/is (= "https://ext.example/v1" (:base-url m)))))
  (t/testing "base-url-only extension overrides every model's base-url"
    (let [model (pc/compose-model-provider
                 :builtin (base-provider (base-model "m1") (base-model "m2"))
                 nil
                 {:base-url "https://ext.example/v1"})]
      (t/is (= ["m1" "m2"] (mapv :id (:models model))))
      (t/is (every? #(= "https://ext.example/v1" (:base-url %)) (:models model))))))

;; ─── Provider fields (auth-header, api-key, configured headers) ───────────

(t/deftest test-provider-fields
  (let [model (pc/compose-model-provider
               :builtin (base-provider (base-model "m1"))
               {:api-key "$MY_KEY" :auth-header true
                :headers {"X-Provider" "p"}
                :model-overrides {"m1" {:headers {"X-Model" "$MODEL_HDR"}}}}
               {:api-key "ext-key"})
        m (first (:models model))]
    (t/testing "extension api-key wins over config; auth-header from config"
      (t/is (= "ext-key" (:api-key model)))
      (t/is (= true (:auth-header model))))
    (t/testing "configured provider headers carried raw"
      (t/is (= {"X-Provider" "p"} (:configured-headers model))))
    (t/testing "model headers: builtin static + models.edn model-level raw"
      (t/is (= {"X-Model" "$MODEL_HDR"} (:headers m)))
      (t/is (nil? (:headers model)) "provider static headers stay builtin-only"))))

(t/deftest test-configured-request-auth-status
  (t/testing "literal key → configured"
    (t/is (= {:configured true :source :models-json-key}
             (pc/configured-request-auth-status {:api-key "sk-lit"} nil))))
  (t/testing "!command key → configured"
    (t/is (= {:configured true :source :models-json-command}
             (pc/configured-request-auth-status {:api-key "!op read"} nil))))
  (t/testing "$ENV key → configured iff the var is present"
    (with-redefs [dynamic-value/getenv (fn [k] (when (= k "MY_KEY") "v"))]
      (t/is (= {:configured true :source :environment}
               (pc/configured-request-auth-status {:api-key "$MY_KEY"} nil)))
      (t/is (= {:configured false}
               (pc/configured-request-auth-status {:api-key "$MISSING"} nil)))))
  (t/testing "no key → nil"
    (t/is (nil? (pc/configured-request-auth-status {} nil))))
  (t/testing "extension key wins over config (source :fallback, pi)"
    (t/is (= {:configured true :source :fallback}
             (pc/configured-request-auth-status {:api-key "cfg"} {:api-key "ext"})))
    (t/is (= {:configured true :source :models-json-key}
             (pc/configured-request-auth-status {:api-key "cfg"} nil)))))

;; ─── Sampling params (pi ModelDefinition.samplingParams) ───────────────────

(t/deftest test-sampling-params
  (t/testing "definition sampling-params carried onto the composed model"
    (let [model (pc/compose-model-provider
                 :custom nil
                 {:base-url "https://x/v1" :api :openai-completions
                  :models [{:id "m" :sampling-params {:temperature 1.0
                                                      :min_p 0.0}}]}
                 nil)
          m (first (:models model))]
      (t/is (= {:temperature 1.0 :min_p 0.0} (:sampling-params m)))))
  (t/testing "no sampling-params → nil"
    (let [model (pc/compose-model-provider
                 :custom nil
                 {:base-url "https://x/v1" :api :openai-completions
                  :models [{:id "m"}]}
                 nil)]
      (t/is (nil? (:sampling-params (first (:models model)))))))
  (t/testing "model-overrides merge per key with the base value (pi: {...base, ...override})"
    (let [model (pc/compose-model-provider
                 :custom nil
                 {:base-url "https://x/v1" :api :openai-completions
                  :models [{:id "m" :sampling-params {:temperature 0.7 :top_p 0.9}}]
                  :model-overrides {"m" {:sampling-params {:temperature 0.2 :min_p 0.0}}}}
                 nil)
          m (first (:models model))]
      (t/is (= {:temperature 0.2 :top_p 0.9 :min_p 0.0} (:sampling-params m)))))
  (t/testing "override without sampling-params keeps the base value"
    (let [model (pc/compose-model-provider
                 :custom nil
                 {:base-url "https://x/v1" :api :openai-completions
                  :models [{:id "m" :sampling-params {:temperature 0.7}}]
                  :model-overrides {"m" {:context-window 100}}}
                 nil)
          m (first (:models model))]
      (t/is (= {:temperature 0.7} (:sampling-params m))))))

;; ─── Eager extension validation (pi validateExtensionProvider) ─────────────

(t/deftest test-validate-extension-provider
  (t/testing "valid extension config passes"
    (t/is (nil? (pc/validate-extension-provider
                 :custom nil nil
                 {:base-url "https://x/v1" :api :openai-completions
                  :models [{:id "m"}]}))))
  (t/testing "missing api throws (broken registration fails before touching state)"
    (t/is (thrown? Exception
                   (pc/validate-extension-provider
                    :custom nil nil
                    {:base-url "https://x/v1" :models [{:id "m"}]}))))
  (t/testing "missing base-url throws"
    (t/is (thrown? Exception
                   (pc/validate-extension-provider
                    :custom nil nil
                    {:api :openai-completions :models [{:id "m"}]})))))

;; ─── Extension oauth adaptation (Phase 10; pi adaptOAuth) ──────────────────

(t/deftest test-adapt-oauth
  (let [login (fn [_] {:type :oauth :access "a" :refresh "r" :expires 1})
        to-auth (fn [cred] {:api-key (:access cred)})
        refresh-token (fn [cred _] cred)]
    (t/testing "a map config adapts to an OAuthAuth record"
      (let [oauth (pc/adapt-oauth {:name "Ext OAuth" :login login :to-auth to-auth})
            cred {:type :oauth :access "a" :refresh "r" :expires 1}]
        (t/is (instance? kmet.ai.oauth.OAuthAuth oauth))
        (t/is (= "Ext OAuth" (:name oauth)))
        (t/is (= login (:login oauth)))
        (t/is (= to-auth (:to-auth oauth)))
        (t/is (= cred ((:refresh oauth) cred (atom false)))
              "refresh-token absent → credential passes through (pi default)")))
    (t/testing "refresh-token wires to :refresh"
      (let [oauth (pc/adapt-oauth {:name "Ext" :login login :to-auth to-auth
                                   :refresh-token refresh-token})]
        (t/is (= refresh-token (:refresh oauth)))))
    (t/testing "non-map config → nil"
      (t/is (nil? (pc/adapt-oauth nil)))
      (t/is (nil? (pc/adapt-oauth "not-a-config")))))
  (t/testing "compose-model-provider carries the builtin oauth through layers"
    (let [base (models/map->Provider
                {:id :copilot :name "Copilot" :models [] :oauth :builtin-oauth})
          composed (pc/compose-model-provider :copilot base nil nil)]
      (t/is (= :builtin-oauth (:oauth composed))))
    (let [extension-oauth {:name "Ext" :login (fn [_] {}) :to-auth (fn [c] {:api-key (:access c)})}
          base (models/map->Provider
                {:id :copilot :name "Copilot" :models [] :oauth :builtin-oauth})
          composed (pc/compose-model-provider :copilot base nil {:oauth extension-oauth})]
      (t/is (instance? kmet.ai.oauth.OAuthAuth (:oauth composed))
            "extension oauth replaces the builtin")
      (t/is (= "Ext" (:name (:oauth composed)))))))

(t/deftest test-adapt-oauth-validation
  (t/testing "missing login/to-auth throws (eager registration check)"
    (t/is (thrown-with-msg? Exception #"requires :login and :to-auth"
                            (pc/adapt-oauth {:name "Broken"})))
    (t/is (thrown-with-msg? Exception #"requires :login and :to-auth"
                            (pc/adapt-oauth {:name "Broken" :login (fn [_] {})}))))
  (t/testing "a valid config passes"
    (t/is (some? (pc/adapt-oauth {:name "Ok" :login (fn [_] {})
                                  :to-auth (fn [c] {:api-key (:access c)})})))))

(t/deftest test-validate-extension-provider-oauth
  (t/testing "broken oauth config fails eager validation"
    (t/is (thrown-with-msg? Exception #"requires :login and :to-auth"
                            (pc/validate-extension-provider
                             :custom nil nil {:oauth {:name "Broken"}}))))
  (t/testing "valid oauth config passes"
    (t/is (nil? (pc/validate-extension-provider
                 :custom nil nil
                 {:oauth {:name "Ok" :login (fn [_] {})
                          :to-auth (fn [c] {:api-key (:access c)})}})))))
