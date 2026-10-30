(ns kmet.ai.test-auth
  "Phase 3: credential resolution, env precedence (auth.edn over env, pi env
   order), configured?, and auth.edn persistence."
  (:require [clojure.test :as t]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [kmet.ai.auth :as auth]
            [kmet.libs.aws-sigv4 :as aws-sigv4]
            [kmet.ai.google-adc :as google-adc]
            [kmet.app.config :as cfg]))

(defn- with-auth-file
  "Run F with the agent dir (and thus auth.edn) redirected to a temp dir and
   an empty auth atom; F receives the auth file path."
  [f]
  (let [agent-dir (str (fs/absolutize (fs/file "target" (str "test-auth-" (System/currentTimeMillis)))))
        path (str agent-dir "/auth.edn")]
    (fs/create-dirs agent-dir)
    (try
      ;; both cells are process-wide: redefining them (with-redefs restores
      ;; on exit) keeps one suite's temp root out of the next
      (with-redefs [auth/auth-atom (atom {})
                    auth/auth-root (atom nil)]
        (auth/load-auth! agent-dir)
        (f path))
      (finally (fs/delete-tree agent-dir)))))

;; ─── Env var table (pi env-api-keys.ts) ────────────────────────────────────

(t/deftest test-provider-env-vars-table
  (t/testing "pi env-api-keys order"
    (t/is (= ["OPENCODE_API_KEY"] (auth/provider-env-vars :opencode-go)))
    (t/is (= ["OPENCODE_API_KEY"] (auth/provider-env-vars :opencode)))
    (t/is (= ["DEEPSEEK_API_KEY"] (auth/provider-env-vars :deepseek)))
    (t/is (= ["COPILOT_GITHUB_TOKEN"] (auth/provider-env-vars :github-copilot)))
    (t/is (= ["OPENAI_API_KEY"] (auth/provider-env-vars :openai)))
    (t/is (= ["ANTHROPIC_AUTH_TOKEN" "ANTHROPIC_OAUTH_TOKEN" "ANTHROPIC_API_KEY"]
             (auth/provider-env-vars :anthropic))
          "Phase 9: all three anthropic env vars participate in discovery (pi findEnvKeys)")
    (t/is (= ["GEMINI_API_KEY"] (auth/provider-env-vars :google)))
    (t/is (= [] (auth/provider-env-vars :openai-codex))
          "Phase 12: openai-codex is OAuth-only — no env var")
    (t/is (= ["AZURE_OPENAI_API_KEY"] (auth/provider-env-vars :azure-openai-responses)))
    (t/is (= ["GROQ_API_KEY"] (auth/provider-env-vars :groq)))
    (t/is (= ["CEREBRAS_API_KEY"] (auth/provider-env-vars :cerebras)))
    (t/is (= ["HF_TOKEN"] (auth/provider-env-vars :huggingface)))
    (t/is (= ["MOONSHOT_API_KEY"] (auth/provider-env-vars :moonshotai)))
    (t/is (= ["XIAOMI_API_KEY"] (auth/provider-env-vars :xiaomi)))
    (t/is (= ["XIAOMI_TOKEN_PLAN_CN_API_KEY"] (auth/provider-env-vars :xiaomi-token-plan-cn)))
    (t/is (= ["QWEN_TOKEN_PLAN_API_KEY"] (auth/provider-env-vars :qwen-token-plan)))
    (t/is (= ["QWEN_TOKEN_PLAN_CN_API_KEY"] (auth/provider-env-vars :qwen-token-plan-cn)))
    (t/is (= ["MINIMAX_API_KEY"] (auth/provider-env-vars :minimax)))
    (t/is (= ["MINIMAX_CN_API_KEY"] (auth/provider-env-vars :minimax-cn)))
    (t/is (= ["NVIDIA_API_KEY"] (auth/provider-env-vars :nvidia)))
    (t/is (= ["OPENROUTER_API_KEY"] (auth/provider-env-vars :openrouter)))
    (t/is (= ["FIREWORKS_API_KEY"] (auth/provider-env-vars :fireworks)))
    (t/is (= ["AI_GATEWAY_API_KEY"] (auth/provider-env-vars :vercel-ai-gateway)))
    (t/is (= ["ZAI_API_KEY"] (auth/provider-env-vars :zai)))
    (t/is (= ["ZAI_CODING_CN_API_KEY"] (auth/provider-env-vars :zai-coding-cn)))
    (t/is (= ["TOGETHER_API_KEY"] (auth/provider-env-vars :together)))
    (t/is (= ["BASETEN_API_KEY"] (auth/provider-env-vars :baseten)))
    (t/is (= ["ANT_LING_API_KEY"] (auth/provider-env-vars :ant-ling)))
    (t/is (= ["KIMI_API_KEY"] (auth/provider-env-vars :kimi-coding)))
    (t/is (= ["CLOUDFLARE_API_KEY" "CLOUDFLARE_ACCOUNT_ID"]
             (auth/provider-env-vars :cloudflare-workers-ai)))
    (t/is (= ["CLOUDFLARE_API_KEY" "CLOUDFLARE_ACCOUNT_ID" "CLOUDFLARE_GATEWAY_ID"]
             (auth/provider-env-vars :cloudflare-ai-gateway)))
    (t/is (= ["MISTRAL_API_KEY"] (auth/provider-env-vars :mistral)))
    (t/is (= ["GOOGLE_CLOUD_API_KEY"] (auth/provider-env-vars :google-vertex)))
    (t/is (= [] (auth/provider-env-vars :amazon-bedrock))
          "bedrock auth is ambient AWS credentials — no api-key env var"))
  (t/testing "unknown provider → empty"
    (t/is (= [] (auth/provider-env-vars :nonexistent)))))

;; ─── resolve-api-key precedence ────────────────────────────────────────────

(t/deftest test-resolve-api-key-precedence
  (t/testing "auth.edn credential wins over env var (pi: auth.json before env)"
    (with-redefs [auth/auth-atom (atom {:deepseek {:key "file-key"}})
                  auth/getenv (fn [_] "env-key")]
      (t/is (= "file-key" (auth/resolve-api-key :deepseek)))))
  (t/testing "env var used when no auth.edn entry"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv #(when (= % "DEEPSEEK_API_KEY") "env-key")]
      (t/is (= "env-key" (auth/resolve-api-key :deepseek)))))
  (t/testing "first present env var in pi order wins"
    (with-redefs [auth/auth-atom (atom {})
                  auth/provider-env-vars (fn [_] ["TEST_A" "TEST_B"])
                  auth/getenv #(get {"TEST_A" "a" "TEST_B" "b"} %)]
      (t/is (= "a" (auth/resolve-api-key :fake))))
    (with-redefs [auth/auth-atom (atom {})
                  auth/provider-env-vars (fn [_] ["TEST_A" "TEST_B"])
                  auth/getenv #(get {"TEST_B" "b"} %)]
      (t/is (= "b" (auth/resolve-api-key :fake)))))
  (t/testing "unknown provider → nil"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv (fn [_] nil)]
      (t/is (nil? (auth/resolve-api-key :nonexistent))))))

;; ─── configured? semantics ─────────────────────────────────────────────────

(t/deftest test-configured?
  (t/testing "auth.edn entry → true even without env vars"
    (with-redefs [auth/auth-atom (atom {:deepseek {:key "k"}})
                  auth/getenv (fn [_] nil)]
      (t/is (true? (auth/configured? :deepseek)))))
  (t/testing "any env var present → true"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv #(when (= % "DEEPSEEK_API_KEY") "k")]
      (t/is (true? (auth/configured? :deepseek)))))
  (t/testing "neither → false; unknown provider → false"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv (fn [_] nil)]
      (t/is (false? (auth/configured? :deepseek)))
      (t/is (false? (auth/configured? :nonexistent))))))

(t/deftest test-provider-auth-status
  (t/testing "stored oauth credential → oauth/stored"
    (with-redefs [auth/auth-atom (atom {:github-copilot {:type :oauth
                                                         :access "a" :refresh "r"
                                                         :expires 9e9}})]
      (t/is (= {:configured? true :type :oauth :source "stored"}
               (auth/provider-auth-status :github-copilot)))))
  (t/testing "stored api-key → api-key/stored"
    (with-redefs [auth/auth-atom (atom {:deepseek {:key "k"}})
                  auth/getenv (fn [_] nil)]
      (t/is (= {:configured? true :type :api-key :source "stored" :label nil}
               (auth/provider-auth-status :deepseek)))))
  (t/testing "present env var → environment with the var name as label"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv #(when (= % "DEEPSEEK_API_KEY") "v")]
      (t/is (= {:configured? true :type :api-key :source "environment"
                :label "DEEPSEEK_API_KEY"}
               (auth/provider-auth-status :deepseek)))))
  (t/testing "nothing configured → configured? false"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv (fn [_] nil)]
      (t/is (= {:configured? false} (auth/provider-auth-status :deepseek))))))

;; ─── auth.edn persistence ──────────────────────────────────────────────────

(t/deftest test-set-remove-credential!
  (with-auth-file
    (fn [path]
      (t/testing "set-credential! writes auth.edn and refreshes the atom"
        (auth/set-credential! :deepseek "sk-123")
        (t/is (= {:deepseek {:key "sk-123"}} (edn/read-string (slurp path))))
        (t/is (= "sk-123" (auth/resolve-api-key :deepseek))))
      (t/testing "set-credential! keeps other providers"
        (auth/set-credential! :opencode-go "ok-456")
        (t/is (= {:deepseek {:key "sk-123"} :opencode-go {:key "ok-456"}}
                 (edn/read-string (slurp path)))))
      (t/testing "remove-credential! drops only that provider"
        (auth/remove-credential! :deepseek)
        (t/is (= {:opencode-go {:key "ok-456"}} (edn/read-string (slurp path))))
        (t/is (nil? (auth/resolve-api-key :deepseek))))
      (t/testing "remove-credential! is a no-op when absent"
        (auth/remove-credential! :deepseek)
        (t/is (= {:opencode-go {:key "ok-456"}} (edn/read-string (slurp path))))))))

(t/deftest test-set-credential-replaces-non-map-file
  (with-auth-file
    (fn [path]
      (t/testing "non-map content is replaced, not merged (pi settings behavior)"
        (spit path "[1 2 3]\n")
        (auth/set-credential! :deepseek "sk-1")
        (t/is (= {:deepseek {:key "sk-1"}} (edn/read-string (slurp path))))
        (t/is (= "sk-1" (auth/resolve-api-key :deepseek))))
      (t/testing "load-auth! treats non-map content as empty"
        (spit path "[1 2 3]\n")
        (t/is (= {} (auth/load-auth! (str (fs/parent path)))))))))

(t/deftest test-load-auth!
  (with-auth-file
    (fn [path]
      (spit path "{:opencode-go {:key \"ok-1\"}}\n")
      (t/is (= {:opencode-go {:key "ok-1"}} (auth/load-auth! (str (fs/parent path)))))
      (t/is (= "ok-1" (auth/resolve-api-key :opencode-go))))))

(t/deftest test-auth-file-pretty-format
  (with-auth-file
    (fn [path]
      (auth/set-credential! :deepseek "sk-1")
      (t/is (= "{:deepseek {:key \"sk-1\"}\n}\n" (slurp path)))
      (t/testing "the lock file is cleaned up after the write"
        (t/is (false? (fs/exists? (str path ".lock"))))))))

(t/deftest test-concurrent-credential-writes
  (t/testing "lock serializes read-modify-write — no lost update"
    (with-auth-file
      (fn [path]
        (let [futs (doall (for [[p k] [[:deepseek "sk-1"] [:opencode-go "ok-1"]]]
                            (future (auth/set-credential! p k))))]
          (doseq [f futs] @f)
          (t/is (= {:deepseek {:key "sk-1"} :opencode-go {:key "ok-1"}}
                   (edn/read-string (slurp path)))))))))

;; ─── config delegation ─────────────────────────────────────────────────────

(t/deftest test-config-get-api-key-delegates
  (t/testing "cfg/get-api-key resolves through kmet.ai.auth (Phase 3)"
    (with-redefs [auth/auth-atom (atom {:deepseek {:key "file-key"}})
                  auth/getenv (fn [_] nil)]
      (t/is (= "file-key" (cfg/get-api-key :deepseek))))
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv (fn [_] nil)]
      (t/is (nil? (cfg/get-api-key :deepseek))))))

;; ─── Phase 9: anthropic auth-token variants ────────────────────────────────

(t/deftest test-anthropic-auth-token
  (t/testing "anthropic-auth-token returns the AUTH_TOKEN for :anthropic"
    (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
      (t/is (= "tok" (auth/anthropic-auth-token :anthropic)))
      (t/is (nil? (auth/anthropic-auth-token :deepseek)) "other providers → nil"))))

(t/deftest test-anthropic-resolve-api-key-skips-auth-token
  (t/testing "ANTHROPIC_AUTH_TOKEN alone → no api key (pi getEnvApiKey skips it)"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
      (t/is (nil? (auth/resolve-api-key :anthropic)))
      (t/is (true? (auth/configured? :anthropic)) "AUTH_TOKEN still counts as configured (pi findEnvKeys)")))
  (t/testing "OAUTH_TOKEN preferred over API_KEY (pi order)"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv #(get {"ANTHROPIC_OAUTH_TOKEN" "oauth" "ANTHROPIC_API_KEY" "key"} %)]
      (t/is (= "oauth" (auth/resolve-api-key :anthropic))))
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv #(get {"ANTHROPIC_OAUTH_TOKEN" "oauth" "ANTHROPIC_API_KEY" "key"
                                     "ANTHROPIC_AUTH_TOKEN" "tok"} %)]
      (t/is (nil? (auth/resolve-api-key :anthropic))
            "AUTH_TOKEN wins over oauth/api env (pi resolve order) — the bearer path has no api-key")))
  (t/testing "API_KEY used when oauth absent"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv #(get {"ANTHROPIC_API_KEY" "key"} %)]
      (t/is (= "key" (auth/resolve-api-key :anthropic)))))
  (t/testing "auth.edn credential still wins over all env variants"
    (with-redefs [auth/auth-atom (atom {:anthropic {:key "file-key"}})
                  auth/getenv (fn [_] "env")]
      (t/is (= "file-key" (auth/resolve-api-key :anthropic))))))

(t/deftest test-resolve-provider-auth-precedence
  ;; pi order: credential → configured key → AUTH_TOKEN (bearer) → oauth/api env
  (t/testing "auth.edn credential beats AUTH_TOKEN"
    (with-redefs [auth/auth-atom (atom {:anthropic {:key "file-key"}})
                  auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
      (t/is (= {:api-key "file-key"} (auth/resolve-provider-auth :anthropic)))))
  (t/testing "configured models.edn key beats AUTH_TOKEN"
    (with-redefs [auth/config-key-source (atom (fn [_] "cfg-key"))
                  auth/auth-atom (atom {})
                  auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
      (t/is (= {:api-key "cfg-key"} (auth/resolve-provider-auth :anthropic)))))
  (t/testing "AUTH_TOKEN beats oauth/api env (pi: token checked before them)"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv #(get {"ANTHROPIC_AUTH_TOKEN" "tok" "ANTHROPIC_OAUTH_TOKEN" "oauth"
                                     "ANTHROPIC_API_KEY" "key"} %)]
      (t/is (= {:bearer "tok"} (auth/resolve-provider-auth :anthropic)))
      (t/is (nil? (auth/resolve-api-key :anthropic)) "resolve-api-key has no key for the bearer path")))
  (t/testing "no AUTH_TOKEN → oauth/api env as api-key"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv #(get {"ANTHROPIC_OAUTH_TOKEN" "oauth"} %)]
      (t/is (= {:api-key "oauth"} (auth/resolve-provider-auth :anthropic))))
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv #(get {"ANTHROPIC_API_KEY" "key"} %)]
      (t/is (= {:api-key "key"} (auth/resolve-provider-auth :anthropic)))))
  (t/testing "non-anthropic providers never resolve a bearer"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
      (t/is (nil? (auth/resolve-provider-auth :deepseek))
            "AUTH_TOKEN does not leak to other providers")))
  (t/testing "nothing configured → nil"
    (with-redefs [auth/auth-atom (atom {})
                  auth/getenv (fn [_] nil)]
      (t/is (nil? (auth/resolve-provider-auth :anthropic))))))

(t/deftest test-ambient-configured
  (t/testing "amazon-bedrock: any AWS credential source configures the provider"
    (with-redefs [auth/getenv (fn [_] nil)
                  aws-sigv4/getenv (fn [_] nil)]
      (t/is (not (auth/ambient-configured? :amazon-bedrock)))
      (t/is (not (auth/configured? :amazon-bedrock))))
    (with-redefs [aws-sigv4/getenv (fn [k] (when (= k "AWS_ACCESS_KEY_ID") "AKID"))]
      (t/is (not (auth/ambient-configured? :amazon-bedrock)) "key alone is not enough"))
    (with-redefs [aws-sigv4/getenv (fn [k] (case k "AWS_ACCESS_KEY_ID" "AKID"
                                                 "AWS_SECRET_ACCESS_KEY" "SECRET" nil))]
      (t/is (auth/ambient-configured? :amazon-bedrock)))
    (with-redefs [aws-sigv4/getenv (fn [k] (when (= k "AWS_BEARER_TOKEN_BEDROCK") "tok"))]
      (t/is (auth/ambient-configured? :amazon-bedrock)))
    (with-redefs [aws-sigv4/getenv (fn [k] (when (= k "AWS_PROFILE") "default"))]
      (t/is (auth/ambient-configured? :amazon-bedrock)))
    (with-redefs [auth/getenv (fn [k] (when (= k "AWS_BEDROCK_SKIP_AUTH") "1"))]
      (t/is (auth/ambient-configured? :amazon-bedrock)) "skip-auth counts as configured"))
  (t/testing "google-vertex: ADC credentials + project + location (pi)"
    (with-redefs [google-adc/configured? (constantly false)
                  auth/getenv (fn [_] nil)]
      (t/is (not (auth/ambient-configured? :google-vertex))))
    (with-redefs [google-adc/configured? (constantly true)
                  auth/getenv (fn [k] (case k "GOOGLE_CLOUD_PROJECT" "p"
                                            "GCLOUD_PROJECT" nil
                                            "GOOGLE_CLOUD_LOCATION" "us-central1" nil))]
      (t/is (auth/ambient-configured? :google-vertex)))
    (with-redefs [google-adc/configured? (constantly true)
                  auth/getenv (fn [_] nil)]
      (t/is (not (auth/ambient-configured? :google-vertex)) "project/location required")))
  (t/is (not (auth/ambient-configured? :openai)) "api-key providers are never ambient"))
