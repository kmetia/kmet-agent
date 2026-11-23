(ns kmet.ai.test-attribution
  "Phase 8: provider attribution headers (pi provider-attribution.ts) —
   openrouter/nvidia/cloudflare origin headers, opencode session headers,
   merge order. kmet drops pi's install-telemetry gate (no telemetry), so
   attribution always sends."
  (:require [clojure.test :as t]
            [kmet.ai.attribution :as attr]))

(defn- model
  [& {:keys [provider base-url base-provider]}]
  {:provider provider :base-provider base-provider
   :base-url (or base-url "https://api.example.com/v1")})

;; ─── Default attribution (pi getDefaultAttributionHeaders) ─────────────────

(t/deftest test-openrouter-headers
  (t/testing "provider :openrouter"
    (t/is (= {"HTTP-Referer" "https://github.com/markokocic/kmet"
              "X-OpenRouter-Title" "kmet"
              "X-OpenRouter-Categories" "cli-agent"}
             (attr/merge-provider-attribution-headers (model :provider :openrouter) nil))))
  (t/testing "base-url containing openrouter.ai (pi uses substring, not hostname)"
    (t/is (some? (attr/merge-provider-attribution-headers
                  (model :base-url "https://my-proxy.example.com/openrouter.ai/v1") nil)))
    (t/is (nil? (attr/merge-provider-attribution-headers
                 (model :base-url "https://notopenrouter.example.com/v1") nil)))))

(t/deftest test-openrouter-model-predicate
  (t/is (true? (attr/openrouter-model? (model :provider :openrouter))))
  (t/is (true? (attr/openrouter-model?
                (model :base-url "https://openrouter.ai/api/v1"))))
  (t/is (false? (attr/openrouter-model?
                 (model :base-url "https://notopenrouter.example.com/v1")))))

(t/deftest test-nvidia-headers
  (t/testing "provider :nvidia"
    (t/is (= {"X-BILLING-INVOKE-ORIGIN" "Kmet"}
             (attr/merge-provider-attribution-headers (model :provider :nvidia) nil))))
  (t/testing "hostname integrate.api.nvidia.com"
    (t/is (= {"X-BILLING-INVOKE-ORIGIN" "Kmet"}
             (attr/merge-provider-attribution-headers
              (model :base-url "https://integrate.api.nvidia.com/v1") nil)))
    (t/testing "port is stripped for the hostname match (pi: new URL().hostname)"
      (t/is (= {"X-BILLING-INVOKE-ORIGIN" "Kmet"}
               (attr/merge-provider-attribution-headers
                (model :base-url "https://integrate.api.nvidia.com:8443/v1") nil))))
    (t/is (nil? (attr/merge-provider-attribution-headers
                 (model :base-url "https://integrate.api.nvidia.com.evil.example/v1") nil))
          "subdomain/suffix tricks don't match the exact hostname")))

(t/deftest test-cloudflare-headers
  (t/testing "provider ids"
    (t/is (= {"User-Agent" "kmet-coding-agent"}
             (attr/merge-provider-attribution-headers
              (model :provider :cloudflare-workers-ai) nil)))
    (t/is (= {"User-Agent" "kmet-coding-agent"}
             (attr/merge-provider-attribution-headers
              (model :provider :cloudflare-ai-gateway) nil))))
  (t/testing "hostnames api.cloudflare.com / gateway.ai.cloudflare.com"
    (t/is (= {"User-Agent" "kmet-coding-agent"}
             (attr/merge-provider-attribution-headers
              (model :base-url "https://api.cloudflare.com/client/v4") nil)))
    (t/is (= {"User-Agent" "kmet-coding-agent"}
             (attr/merge-provider-attribution-headers
              (model :base-url "https://gateway.ai.cloudflare.com/v1") nil))))
  (t/testing "other providers get no attribution"
    (t/is (nil? (attr/merge-provider-attribution-headers
                 (model :provider :deepseek :base-url "https://api.deepseek.com") nil)))
    (t/is (nil? (attr/merge-provider-attribution-headers
                 (model :base-url "https://not-a-matching-host.example/v1") nil)))))

;; ─── OpenCode session headers (pi getSessionHeaders, not telemetry-gated) ──

(t/deftest test-opencode-session-headers
  (t/testing "opencode / opencode-go providers"
    (t/is (= {"x-opencode-session" "sess-1" "x-opencode-client" "kmet"}
             (attr/merge-provider-attribution-headers
              (model :provider :opencode :base-url "https://api.example.com") "sess-1")))
    (t/is (= {"x-opencode-session" "sess-2" "x-opencode-client" "kmet"}
             (attr/merge-provider-attribution-headers
              (model :provider :opencode-go :base-url "https://api.example.com") "sess-2"))))
  (t/testing "hostname opencode.ai"
    (t/is (= {"x-opencode-session" "s" "x-opencode-client" "kmet"}
             (attr/merge-provider-attribution-headers
              (model :base-url "https://opencode.ai/zen/v1") "s"))))
  (t/testing "no session id → no session headers (and no attribution for a plain provider)"
    (t/is (nil? (attr/merge-provider-attribution-headers
                 (model :provider :opencode) nil)))
    (t/is (nil? (attr/merge-provider-attribution-headers
                 (model :provider :deepseek) "sess-1"))
          "session headers only for opencode providers/hosts")))

;; ─── Merge order (pi mergeProviderAttributionHeaders) ──────────────────────

(t/deftest test-merge-order
  (t/testing "header sources override the attribution layer (pi transformHeaders last)"
    (let [r (attr/merge-provider-attribution-headers
             (model :provider :openrouter) "sess-1"
             {"HTTP-Referer" "https://custom.example" "X-Custom" "v"})]
      (t/is (= "https://custom.example" (get r "HTTP-Referer"))
            "request headers win collisions")
      (t/is (= "v" (get r "X-Custom")))))
  (t/testing "session + attribution combine"
    (let [r (attr/merge-provider-attribution-headers
             (model :provider :opencode :base-url "https://opencode.ai") "sess-1")]
      (t/is (= "sess-1" (get r "x-opencode-session")))))
  (t/testing "nil header sources pass through"
    (let [r (attr/merge-provider-attribution-headers
             (model :provider :openrouter) nil nil)]
      (t/is (= "https://github.com/markokocic/kmet" (get r "HTTP-Referer"))))))

;; ─── Account aliases keep their base provider's attribution ────────────────

(t/deftest test-account-alias-attribution
  (t/is (= {"HTTP-Referer" "https://github.com/markokocic/kmet"
            "X-OpenRouter-Title" "kmet"
            "X-OpenRouter-Categories" "cli-agent"}
           (attr/merge-provider-attribution-headers
            (model :provider :openrouter#work :base-provider :openrouter
                   :base-url "https://openrouter.example/v1")
            nil))
        "an alias with a non-openrouter URL still gets openrouter attribution")
  (t/is (= {"x-opencode-session" "sess-1" "x-opencode-client" "kmet"}
           (attr/merge-provider-attribution-headers
            (model :provider :opencode#work :base-provider :opencode
                   :base-url "https://api.example.com")
            "sess-1"))))
