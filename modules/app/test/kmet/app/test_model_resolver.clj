(ns kmet.app.test-model-resolver
  "Phase 4: pattern parsing, exact-reference matching with ambiguity
   rejection, alias/dated preference, --models scope, CLI resolution."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.ai.models :as m]
            [kmet.ai.auth :as auth]
            [kmet.app.model-resolver :as r]))

(defn- test-model
  "Model record fixture: PROVIDER keyword, ID string."
  [provider id]
  (m/map->Model {:id id :name id :provider provider
                 :api :openai-completions :base-url "https://x"
                 :reasoning true :input [:text]
                 :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                 :context-window 1000 :max-tokens 100}))

(def ^:private alpha-models
  [(test-model :alpha "alpha-model")
   (test-model :alpha "alpha-model-20250929")  ;; dated variant of the alias
   (test-model :alpha "alpha-max")
   (test-model :alpha "shared")])  ;; same id in beta → ambiguous bare id

(def ^:private beta-models
  [(test-model :beta "beta-model")
   (test-model :beta "shared")])

(def ^:private all-models (vec (concat alpha-models beta-models)))

(defn- register-test-providers!
  "Register the test providers in the models registry (needed by
   resolve-cli-model's provider lookups)."
  []
  (m/clear-providers!)
  (doseq [[pid models] [[:alpha alpha-models] [:beta beta-models]]]
    (m/register-provider!
     (m/map->Provider {:id pid :name (name pid)
                       :api-types #{:openai-completions}
                       :models models :env-vars [] :default-model nil}))))

;; ─── find-exact-model-reference-match ──────────────────────────────────────

(t/deftest test-find-exact-model-reference-match
  (t/testing "canonical provider/id"
    (t/is (= "alpha-model" (:id (r/find-exact-model-reference-match "alpha/alpha-model" all-models))))
    (t/is (= "alpha-model" (:id (r/find-exact-model-reference-match "alpha-model" all-models))))
    (t/testing "case-insensitive"
      (t/is (= "alpha-model" (:id (r/find-exact-model-reference-match "ALPHA/Alpha-Model" all-models))))))
  (t/testing "bare id ambiguous across providers → nil"
    (t/is (nil? (r/find-exact-model-reference-match "shared" all-models))))
  (t/testing "unknown provider or model → nil"
    (t/is (nil? (r/find-exact-model-reference-match "nope/nope" all-models)))
    (t/is (nil? (r/find-exact-model-reference-match "nope" all-models))))
  (t/testing "empty/whitespace → nil"
    (t/is (nil? (r/find-exact-model-reference-match "" all-models)))
    (t/is (nil? (r/find-exact-model-reference-match "  " all-models)))))

;; ─── parse-model-pattern ───────────────────────────────────────────────────

(t/deftest test-parse-model-pattern-basic
  (t/testing "exact id"
    (let [r (r/parse-model-pattern "alpha-model" all-models)]
      (t/is (= "alpha-model" (:id (:model r))))
      (t/is (nil? (:thinking-level r)))
      (t/is (nil? (:warning r)))))
  (t/testing "canonical provider/id"
    (t/is (= "beta-model" (:id (:model (r/parse-model-pattern "beta/beta-model" all-models))))))
  (t/testing "ambiguous exact id falls back to partial matching (pi tryMatchModel)"
    (t/is (some? (:model (r/parse-model-pattern "shared" all-models)))))
  (t/testing "no match → nil model, no warning"
    (let [r (r/parse-model-pattern "nope" all-models)]
      (t/is (nil? (:model r)))
      (t/is (nil? (:warning r))))))

(t/deftest test-resolve-model-reference
  (t/testing "canonical provider/id and bare id"
    (let [{:keys [model thinking-level]} (r/resolve-model-reference "alpha/alpha-model" all-models)]
      (t/is (= "alpha-model" (:id model)))
      (t/is (nil? thinking-level)))
    (t/is (= "beta-model" (:id (:model (r/resolve-model-reference "beta-model" all-models))))))
  (t/testing ":thinking suffix"
    (let [{:keys [model thinking-level]} (r/resolve-model-reference "alpha-model:high" all-models)]
      (t/is (= "alpha-model" (:id model)))
      (t/is (= :high thinking-level))))
  (t/testing "ambiguous bare id → nil (pi rejects ambiguity)"
    (t/is (nil? (:model (r/resolve-model-reference "shared" all-models)))))
  (t/testing "unknown ref → nil"
    (t/is (nil? (:model (r/resolve-model-reference "nope" all-models))))))

(t/deftest test-parse-model-pattern-thinking-suffix
  (t/testing "valid :thinking suffix"
    (let [r (r/parse-model-pattern "alpha-model:high" all-models)]
      (t/is (= "alpha-model" (:id (:model r))))
      (t/is (= :high (:thinking-level r)))))
  (t/testing "provider/id:thinking"
    (let [r (r/parse-model-pattern "beta/beta-model:max" all-models)]
      (t/is (= "beta-model" (:id (:model r))))
      (t/is (= :max (:thinking-level r)))))
  (t/testing "invalid suffix in scope mode warns and resolves the model"
    (let [r (r/parse-model-pattern "alpha-model:bogus" all-models)]
      (t/is (= "alpha-model" (:id (:model r))))
      (t/is (nil? (:thinking-level r)))
      (t/is (str/includes? (:warning r) "Invalid thinking level \"bogus\""))))
  (t/testing "invalid suffix in strict mode fails"
    (let [r (r/parse-model-pattern "alpha-model:bogus" all-models {:strict? true})]
      (t/is (nil? (:model r)))))
  (t/testing "no match, no colon → nil"
    (t/is (nil? (:model (r/parse-model-pattern "nope" all-models))))))

(t/deftest test-parse-model-pattern-partial-and-alias
  (t/testing "partial id match"
    (let [r (r/parse-model-pattern "alpha-mod" all-models)]
      (t/is (= "alpha-model" (:id (:model r))))))
  (t/testing "alias beats dated variant (pi isAlias preference)"
    (let [r (r/parse-model-pattern "alpha-model" all-models)]
      (t/is (= "alpha-model" (:id (:model r))) "alias wins over -20250929"))
    (let [r (r/parse-model-pattern "alpha-model-20250929" all-models)]
      (t/is (= "alpha-model-20250929" (:id (:model r))) "exact dated id still matches")))
  (t/testing "partial name match"
    (let [r (r/parse-model-pattern "alpha-max" all-models)]
      (t/is (= "alpha-max" (:id (:model r)))))))

;; ─── resolve-model-scope (--models) ────────────────────────────────────────

(t/deftest test-resolve-model-scope
  (t/testing "patterns resolve to ids"
    (let [{:keys [models warnings]} (r/resolve-model-scope ["alpha-model" "beta/beta-model"] all-models)]
      (t/is (= ["alpha-model" "beta-model"] models))
      (t/is (empty? warnings))))
  (t/testing "unmatched pattern → warning"
    (let [{:keys [models warnings]} (r/resolve-model-scope ["nope"] all-models)]
      (t/is (empty? models))
      (t/is (= ["No models match pattern \"nope\""] warnings))))
  (t/testing "thinking suffix resolves to the model id"
    (let [{:keys [models]} (r/resolve-model-scope ["alpha-model:high"] all-models)]
      (t/is (= ["alpha-model"] models)))))

(t/deftest test-resolve-model-scope-models
  (t/testing "patterns resolve to full Model records (pi resolveModelScopeFromModels)"
    (let [{:keys [models warnings]} (r/resolve-model-scope-models
                                     ["alpha-model" "beta/beta-model"] all-models)]
      (t/is (= [:alpha :beta] (mapv :provider models)))
      (t/is (= ["alpha-model" "beta-model"] (mapv :id models)))
      (t/is (every? map? models) "records are maps")
      (t/is (empty? warnings))))
  (t/testing "unmatched pattern → warning, no model"
    (let [{:keys [models warnings]} (r/resolve-model-scope-models ["nope"] all-models)]
      (t/is (empty? models))
      (t/is (= ["No models match pattern \"nope\""] warnings))))
  (t/testing "thinking suffix resolves to the model, level dropped (kmet deviation)"
    (let [{:keys [models]} (r/resolve-model-scope-models ["alpha-model:high"] all-models)]
      (t/is (= ["alpha-model"] (mapv :id models)))))
  (t/testing "resolve-model-scope delegates with bare ids"
    (let [{:keys [models]} (r/resolve-model-scope ["alpha-model"] all-models)]
      (t/is (= ["alpha-model"] models))))
  (t/testing "duplicate patterns collapse to the first occurrence (pi dedupe)"
    (let [{:keys [models]} (r/resolve-model-scope-models
                            ["alpha-model" "alpha/alpha-model"] all-models)]
      (t/is (= ["alpha-model"] (mapv :id models))))))

(t/deftest test-resolve-scoped-model
  (register-test-providers!)
  (t/testing "full provider/id entries resolve exactly"
    (let [r (r/resolve-scoped-model "alpha/alpha-model" :beta)]
      (t/is (= :alpha (:provider r)))
      (t/is (= "alpha-model" (:id r)))))
  (t/testing "bare ids prefer the current provider"
    (t/is (= :alpha (:provider (r/resolve-scoped-model "shared" :alpha))))
    (t/is (= :beta (:provider (r/resolve-scoped-model "shared" :beta)))))
  (t/testing "a bare id with no current-provider match must be unambiguous"
    (t/is (nil? (r/resolve-scoped-model "shared" :gamma))
          "the same id under two providers → nil, not first-match")
    (t/is (= :beta (:provider (r/resolve-scoped-model "beta-model" :gamma))))
    (t/is (nil? (r/resolve-scoped-model "nope" :alpha)))))

;; ─── resolve-cli-model (--provider/--model) ────────────────────────────────

(t/deftest test-resolve-cli-model
  (register-test-providers!)
  (t/testing "provider/model pattern infers the provider"
    (let [r (r/resolve-cli-model nil "alpha/alpha-model" (m/get-models))]
      (t/is (= :alpha (:provider r)))
      (t/is (= "alpha-model" (:model-id r)))
      (t/is (nil? (:error r)))))
  (t/testing "explicit provider + bare id"
    (let [r (r/resolve-cli-model :beta "beta-model" (m/get-models))]
      (t/is (= :beta (:provider r)))
      (t/is (= "beta-model" (:model-id r)))))
  (t/testing "bare id unambiguous across all models"
    (let [r (r/resolve-cli-model nil "alpha-model" (m/get-models))]
      (t/is (= :alpha (:provider r)))
      (t/is (= "alpha-model" (:model-id r)))))
  (t/testing "bare id ambiguous across providers → error (pi exact-match pre-check)"
    (with-redefs [auth/configured? (fn [_] false)]
      (let [r (r/resolve-cli-model nil "shared" (m/get-models))]
        (t/is (nil? (:model-id r)))
        (t/is (str/includes? (:error r) "is ambiguous across providers"))
        (t/is (str/includes? (:error r) "alpha/shared"))
        (t/is (str/includes? (:error r) "beta/shared")))))
  (t/testing "ambiguous bare id with exactly one authenticated provider resolves to it"
    (with-redefs [auth/configured? #(= % :beta)]
      (let [r (r/resolve-cli-model nil "shared" (m/get-models))]
        (t/is (= :beta (:provider r)))
        (t/is (= "shared" (:model-id r))))))
  (t/testing "thinking suffix"
    (let [r (r/resolve-cli-model :alpha "alpha-model:high" (m/get-models))]
      (t/is (= :high (:thinking-level r)))
      (t/is (= "alpha-model" (:model-id r)))))
  (t/testing "unknown provider → error"
    (let [r (r/resolve-cli-model :nope "alpha-model" (m/get-models))]
      (t/is (nil? (:model-id r)))
      (t/is (= "Unknown provider \"nope\"." (:error r)))))
  (t/testing "no match → error naming the provider"
    (let [r (r/resolve-cli-model :alpha "nope" (m/get-models))]
      (t/is (nil? (:model-id r)))
      (t/is (= "Model \"nope\" not found for provider \"alpha\"." (:error r)))))
  (t/testing "no match, no provider → error"
    (let [r (r/resolve-cli-model nil "nope" (m/get-models))]
      (t/is (nil? (:model-id r)))
      (t/is (= "Model \"nope\" not found." (:error r))))))

(t/deftest test-resolve-cli-model-exact-id-with-slash
  ;; CommandCode's model ids carry a slash; the provider-context lookup must
  ;; prefer the exact id over the fuzzy sibling (-fast).
  (m/clear-providers!)
  (let [models [(test-model :gamma "vendor/model")
                (test-model :gamma "vendor/model-fast")]]
    (m/register-provider!
     (m/map->Provider {:id :gamma :name "gamma"
                       :api-types #{:openai-completions}
                       :models models :env-vars [] :default-model nil}))
    (let [r (r/resolve-cli-model :gamma "vendor/model" (m/get-models))]
      (t/is (= :gamma (:provider r)))
      (t/is (= "vendor/model" (:model-id r)))
      (t/is (nil? (:error r))))))
