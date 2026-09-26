(ns kmet.ai.test-oauth
  "Phase 10: OAuth subscriptions — device-code poll state machine (interval,
   slow_down, expiry, cancel), PKCE, auth.edn oauth shape validation,
   credential refresh on expiry (5-min skew), serialized credential ops,
   copilot available-model-ids filtering + proxy-ep base-url, and the /login
   auth-type selection."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [testing]]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [kmet.libs.http :as http]
            [kmet.ai.auth :as auth]
            [kmet.app.commands :as commands]
            [kmet.app.ui.dock :as dock]
            [kmet.ai.models :as models]
            [kmet.ai.oauth :as oauth]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.modes.interactive :as inter]))

;; ─── Device-code poll state machine (pi pollOAuthDeviceCodeFlow) ───────────

(defn- fake-clock-flow
  "Run poll-oauth-device-code-flow with a fake clock: abortable-sleep advances
   the clock instead of sleeping, so time passage is deterministic."
  [opts]
  (let [t (atom 0)]
    (with-redefs [oauth/abortable-sleep (fn [ms _] (swap! t + ms))]
      (oauth/poll-oauth-device-code-flow (assoc opts :now (fn [] @t))))))

(t/deftest test-device-code-completes-immediately
  (t/is (= "token"
           (oauth/poll-oauth-device-code-flow
            {:poll (fn [] {:status :complete :value "token"})
             :signal (atom false)}))))

(t/deftest test-device-code-pending-then-complete
  (let [calls (atom 0)
        sleeps (atom [])
        result (with-redefs [oauth/abortable-sleep (fn [ms _] (swap! sleeps conj ms))]
                 (oauth/poll-oauth-device-code-flow
                  {:interval-seconds 1
                   :poll (fn [] (swap! calls inc)
                           (if (= 1 @calls)
                             {:status :pending}
                             {:status :complete :value "ok"}))
                   :signal (atom false)}))]
    (t/is (= "ok" result))
    (t/is (= 2 @calls) "polls again after pending")
    (t/is (= [1000] @sleeps) "sleeps one interval between polls")))

(t/deftest test-device-code-slow-down-server-interval
  (let [sleeps (atom [])
        calls (atom 0)
        result (with-redefs [oauth/abortable-sleep (fn [ms _] (swap! sleeps conj ms))]
                 (oauth/poll-oauth-device-code-flow
                  {:interval-seconds 1
                   :poll (fn [] (swap! calls inc)
                           (case @calls
                             1 {:status :slow_down :interval-seconds 2}
                             2 {:status :complete :value "ok"}))
                   :signal (atom false)}))]
    (t/is (= "ok" result))
    ;; after slow_down the server-provided interval (2s) is used for the sleep
    (t/is (= [2000] @sleeps))))

(t/deftest test-device-code-slow-down-increment
  (let [sleeps (atom [])
        calls (atom 0)
        result (with-redefs [oauth/abortable-sleep (fn [ms _] (swap! sleeps conj ms))]
                 (oauth/poll-oauth-device-code-flow
                  {:interval-seconds 1
                   :poll (fn [] (swap! calls inc)
                           (case @calls
                             1 {:status :slow_down}
                             2 {:status :complete :value "ok"}))
                   :signal (atom false)}))]
    (t/is (= "ok" result))
    ;; RFC 8628 3.5: slow_down without a server interval → +5s (1000 + 5000)
    (t/is (= [6000] @sleeps))))

(t/deftest test-device-code-wait-before-first-poll
  (let [calls (atom 0)
        sleeps (atom [])
        result (with-redefs [oauth/abortable-sleep (fn [ms _] (swap! sleeps conj ms))]
                 (oauth/poll-oauth-device-code-flow
                  {:interval-seconds 2
                   :wait-before-first-poll true
                   :poll (fn [] (swap! calls inc) {:status :complete :value "ok"})
                   :signal (atom false)}))]
    (t/is (= "ok" result))
    (t/is (= 1 @calls) "one poll")
    (t/is (= [2000] @sleeps) "waits one interval before the first poll")))

(t/deftest test-device-code-timeout
  (t/is (thrown-with-msg? Exception #"Device flow timed out"
                          (fake-clock-flow
                           {:expires-in-seconds 1
                            :poll (fn [] {:status :pending})
                            :signal (atom false)}))))

(t/deftest test-device-code-slow-down-timeout-message
  (testing "a timeout after one or more slow_down responses gets the clock-drift hint"
    (t/is (thrown-with-msg? Exception #"slow_down responses"
                            (fake-clock-flow
                             {:expires-in-seconds 1
                              :poll (fn [] {:status :slow_down})
                              :signal (atom false)})))))

(t/deftest test-device-code-cancel
  (t/is (thrown-with-msg? Exception #"Login cancelled"
                          (oauth/poll-oauth-device-code-flow
                           {:poll (fn [] {:status :pending})
                            :signal (atom true)}))))

(t/deftest test-device-code-cancel-mid-flow
  (let [signal (atom false)]
    (t/is (thrown-with-msg? Exception #"Login cancelled"
                            (with-redefs [oauth/abortable-sleep (fn [_ _] nil)]
                              (oauth/poll-oauth-device-code-flow
                               {:poll (fn [] (reset! signal true) {:status :pending})
                                :signal signal}))))))

(t/deftest test-device-code-failed
  (t/is (thrown-with-msg? Exception #"Device flow failed: access_denied: nope"
                          (oauth/poll-oauth-device-code-flow
                           {:poll (fn [] {:status :failed
                                          :message "Device flow failed: access_denied: nope"})
                            :signal (atom false)}))))

;; ─── PKCE (pi pkce.ts) ─────────────────────────────────────────────────────

(defn- base64url
  [bytes]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes))

(t/deftest test-generate-pkce
  (let [{:keys [verifier challenge]} (oauth/generate-pkce)
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes verifier "UTF-8"))]
    (t/is (= 43 (count verifier)) "32 random bytes base64url")
    (t/is (re-matches #"[A-Za-z0-9_-]+" verifier) "base64url alphabet")
    (t/is (= (base64url digest) challenge) "challenge = base64url(SHA-256(verifier))"))
  (testing "two calls produce different verifiers"
    (t/is (not= (:verifier (oauth/generate-pkce))
                (:verifier (oauth/generate-pkce))))))

;; ─── Copilot base-url derivation (pi getGitHubCopilotBaseUrl) ─────────────

(t/deftest test-github-copilot-base-url
  (testing "proxy-ep token claim converts to the API host"
    (t/is (= "https://api.individual.githubcopilot.com"
             (oauth/get-github-copilot-base-url
              "tid=abc;exp=123;proxy-ep=proxy.individual.githubcopilot.com;x=y"))))
  (testing "no proxy-ep → default individual endpoint"
    (t/is (= "https://api.individual.githubcopilot.com"
             (oauth/get-github-copilot-base-url "tid=abc;exp=123"))))
  (testing "enterprise fallback"
    (t/is (= "https://copilot-api.company.ghe.com"
             (oauth/get-github-copilot-base-url nil "company.ghe.com")))
    (t/is (= "https://copilot-api.company.ghe.com"
             (oauth/get-github-copilot-base-url "tid=abc" "company.ghe.com")))))

;; ─── Copilot OAuthAuth to-auth ─────────────────────────────────────────────

(t/deftest test-copilot-oauth-to-auth
  (let [oauth (oauth/make-github-copilot-oauth (fn [] []))]
    (testing "to-auth derives the api-key + proxy-ep base-url"
      (t/is (= {:api-key "cp-access"
                :base-url "https://api.individual.githubcopilot.com"}
               ((:to-auth oauth)
                {:type :oauth :access "cp-access" :refresh "r" :expires 9999999999999})))
      (t/is (= {:api-key "cp-access"
                :base-url "https://copilot-api.company.ghe.com"}
               ((:to-auth oauth)
                {:type :oauth :access "cp-access" :refresh "r" :expires 9999999999999
                 :enterprise-url "company.ghe.com"}))))))

;; ─── Copilot login flow (network mocked) ───────────────────────────────────

(t/deftest test-copilot-login-flow
  (let [notified (atom [])
        enabled (atom nil)
        interaction {:signal (atom false)
                     :prompt (fn [_] "")  ;; blank → default github.com
                     :notify (fn [e] (swap! notified conj e))}
        oauth (oauth/make-github-copilot-oauth (fn [] ["m1" "m2"]))]
    (with-redefs [oauth/start-device-flow
                  (fn [_] {:device-code "dc"
                           :user-code "123-456"
                           :verification-uri "https://github.com/login/device"
                           :interval 5
                           :expires-in 900})
                  oauth/poll-for-github-access-token (fn [_ _ _] "gh-access")
                  oauth/refresh-github-copilot-access-token
                  (fn [_t enterprise]
                    {:type :oauth :access "cp-access" :refresh "cp-refresh"
                     :expires 9999999999999 :enterprise-url enterprise})
                  oauth/enable-all-github-copilot-models
                  (fn [token enterprise ids] (reset! enabled [token enterprise ids]))
                  oauth/fetch-available-github-copilot-model-ids (fn [_ _] ["m1"])]
      (let [credential ((:login oauth) interaction)]
        (t/is (= "cp-access" (:access credential)))
        (t/is (= "cp-refresh" (:refresh credential)))
        (t/is (= ["m1"] (:available-model-ids credential)))
        (t/is (nil? (:enterprise-url credential)) "blank domain → github.com")
        (t/is (= ["cp-access" nil ["m1" "m2"]] @enabled)
              "enable-all receives the catalog model ids")
        (t/is (= :device-code (:type (first @notified))))
        (t/is (= "123-456" (:user-code (first @notified))))
        (t/is (= :progress (:type (second @notified))))))))

(t/deftest test-copilot-login-enterprise
  (let [interaction {:signal (atom false)
                     :prompt (fn [_] "https://company.ghe.com")
                     :notify (fn [_] nil)}
        oauth (oauth/make-github-copilot-oauth (fn [] []))]
    (with-redefs [oauth/start-device-flow (fn [domain] {:domain domain})
                  oauth/poll-for-github-access-token (fn [_ _ _] "gh-access")
                  oauth/refresh-github-copilot-access-token
                  (fn [_t enterprise] {:type :oauth :access "a" :refresh "r"
                                       :expires 9999999999999 :enterprise-url enterprise})
                  oauth/enable-all-github-copilot-models (fn [_ _ _] nil)
                  oauth/fetch-available-github-copilot-model-ids (fn [_ _] [])]
      (let [credential ((:login oauth) interaction)]
        (t/is (= "company.ghe.com" (:enterprise-url credential))
              "normalized hostname is carried on the credential")
        (t/is (= "https://copilot-api.company.ghe.com"
                 (:base-url ((:to-auth oauth) credential))))))))

(t/deftest test-copilot-login-invalid-domain
  (let [interaction {:signal (atom false)
                     :prompt (fn [_] "not a url")
                     :notify (fn [_] nil)}
        oauth (oauth/make-github-copilot-oauth (fn [] []))]
    (t/is (thrown-with-msg? Exception #"Invalid GitHub Enterprise URL/domain"
                            ((:login oauth) interaction)))))

;; ─── OpenAI Codex OAuth (device-code login; browser loopback deferred) ────

(t/deftest test-codex-device-poll
  (let [device {:device-auth-id "da-1" :user-code "12345" :interval 1}]
    (testing "complete → authorization_code + verifier"
      (with-redefs [oauth/fetch-json
                    (fn [_ _] {:authorization_code "ac-1" :code_verifier "v-1"})]
        (t/is (= {:status :complete
                  :value {:authorization-code "ac-1" :code-verifier "v-1"}}
                 (@#'oauth/codex-device-poll device)))))
    (testing "403 → pending (pi checks response.status directly)"
      (with-redefs [oauth/fetch-json
                    (fn [_ _] (throw (ex-info "403: pending" {:type :oauth-http :status 403})))]
        (t/is (= {:status :pending} (@#'oauth/codex-device-poll device)))))
    (testing "404 → pending"
      (with-redefs [oauth/fetch-json
                    (fn [_ _] (throw (ex-info "404: pending" {:type :oauth-http :status 404})))]
        (t/is (= {:status :pending} (@#'oauth/codex-device-poll device)))))
    (testing "non-pending transport errors propagate"
      (with-redefs [oauth/fetch-json
                    (fn [_ _] (throw (ex-info "500" {:type :oauth-http :status 500})))]
        (t/is (thrown-with-msg? Exception #"500" (@#'oauth/codex-device-poll device)))))
    (testing "deviceauth_authorization_pending error → pending"
      (with-redefs [oauth/fetch-json
                    (fn [_ _] {:error "deviceauth_authorization_pending"})]
        (t/is (= {:status :pending} (@#'oauth/codex-device-poll device)))))
    (testing "nested error object code → pending"
      (with-redefs [oauth/fetch-json
                    (fn [_ _] {:error {:code "deviceauth_authorization_pending"}})]
        (t/is (= {:status :pending} (@#'oauth/codex-device-poll device)))))
    (testing "slow_down → slow_down"
      (with-redefs [oauth/fetch-json (fn [_ _] {:error "slow_down"})]
        (t/is (= {:status :slow_down} (@#'oauth/codex-device-poll device)))))
    (testing "unknown responses → failed"
      (with-redefs [oauth/fetch-json (fn [_ _] {:error "something_else"})]
        (let [result (@#'oauth/codex-device-poll device)]
          (t/is (= :failed (:status result)))
          (t/is (str/includes? (:message result) "something_else")))))))

(t/deftest test-codex-login-flow
  (let [notified (atom [])
        interaction {:signal (atom false)
                     :prompt (fn [_] "device_code")
                     :notify (fn [e] (swap! notified conj e))}
        oauth (oauth/make-openai-codex-oauth)]
    (with-redefs [oauth/start-codex-device-auth
                  (fn [] {:device-auth-id "da-1" :user-code "ABC-DEF" :interval 5})
                  oauth/poll-codex-device-auth
                  (fn [_ _] {:status :complete
                             :value {:authorization-code "ac-1" :code-verifier "v-1"}})
                  oauth/codex-exchange-authorization-code
                  (fn [_ _ _]
                    {:access "acc-token" :refresh "ref-token" :expires 9999999999999})]
      (let [credential ((:login oauth) interaction)]
        (t/is (= "acc-token" (:access credential)))
        (t/is (= "ref-token" (:refresh credential)))
        (t/is (= 9999999999999 (:expires credential)))
        (t/is (= :device-code (:type (first @notified))))
        (t/is (= "ABC-DEF" (:user-code (first @notified))))
        (t/is (= "https://auth.openai.com/codex/device"
                 (:verification-uri (first @notified))))
        (t/is (= 900 (:expires-in-seconds (first @notified))))))))

(t/deftest test-codex-refresh-and-to-auth
  (let [oauth (oauth/make-openai-codex-oauth)]
    (testing "refresh exchanges the stored refresh token"
      (with-redefs [oauth/codex-refresh-access-token
                    (fn [_] {:access "new-access" :refresh "new-refresh" :expires 123})]
        (let [credential ((:refresh oauth)
                          {:type :oauth :access "a" :refresh "r" :expires 1} nil)]
          (t/is (= "new-access" (:access credential)))
          (t/is (= "new-refresh" (:refresh credential)))
          (t/is (= 123 (:expires credential))))))
    (testing "to-auth passes the access token through"
      (t/is (= {:api-key "acc-token"}
               ((:to-auth oauth)
                {:type :oauth :access "acc-token" :refresh "r" :expires 1}))))))

(t/deftest test-codex-token-response-validation
  (testing "missing fields throw"
    (t/is (thrown-with-msg? Exception #"missing fields"
                            (@#'oauth/codex-token-response "exchange" {})))
    (t/is (thrown-with-msg? Exception #"missing fields"
                            (@#'oauth/codex-token-response
                             "refresh" {:access_token "a" :refresh_token "r"}))))
  (testing "a valid response carries the trio with an absolute expiry"
    (let [before (System/currentTimeMillis)
          parsed (@#'oauth/codex-token-response
                  "exchange" {:access_token "a" :refresh_token "r" :expires_in 3600})
          after (System/currentTimeMillis)]
      (t/is (= "a" (:access parsed)))
      (t/is (= "r" (:refresh parsed)))
      (t/is (<= (+ before (* 3600 1000)) (:expires parsed) (+ after (* 3600 1000))))))
  (testing "start-codex-device-auth validates the response fields"
    (with-redefs [oauth/fetch-json (fn [_ _] {:device_auth_id "d" :user_code "u"})]
      (t/is (thrown-with-msg? Exception #"Invalid OpenAI Codex device code response"
                              (@#'oauth/start-codex-device-auth))))
    (with-redefs [oauth/fetch-json
                  (fn [_ _] {:device_auth_id "d" :user_code "u" :interval 5})]
      (let [device (@#'oauth/start-codex-device-auth)]
        (t/is (= "d" (:device-auth-id device)))
        (t/is (= "u" (:user-code device)))
        (t/is (= 5 (:interval device)))))))

;; ─── auth.edn oauth shape (pi auth-storage parse) ─────────────────────────

(t/deftest test-valid-credential
  (testing "api-key shapes"
    (t/is (true? (auth/valid-credential? {:key "sk-123"})))
    (t/is (true? (auth/valid-credential? {}))
          "key is optional (pi ApiKeyCredential.key optional)"))
  (testing "oauth shapes"
    (t/is (true? (auth/valid-credential? {:type :oauth :access "a" :refresh "r"
                                          :expires 1234567890123})))
    (t/is (false? (auth/valid-credential? {:type :oauth :access "a"
                                           :refresh "r"}))
          "missing expires")
    (t/is (false? (auth/valid-credential? {:type :oauth :access "a"
                                           :refresh "r" :expires ##Inf}))
          "non-finite expires")
    (t/is (false? (auth/valid-credential? {:type :oauth :access 42
                                           :refresh "r" :expires 123}))
          "non-string access"))
  (testing "non-map entries are invalid (pi auth-storage parse throws)"
    (t/is (false? (auth/valid-credential? "sk-123")))
    (t/is (false? (auth/valid-credential? nil)))
    (t/is (false? (auth/valid-credential? ["a"])))))

(defn- with-auth-file
  "Run F with the agent dir (and thus auth.edn) redirected to a temp dir and
   an empty auth atom; F receives the auth file path."
  [f]
  (let [agent-dir (str (fs/absolutize (fs/file "target" (str "test-oauth-" (System/currentTimeMillis)))))
        path (str agent-dir "/auth.edn")]
    (fs/create-dirs agent-dir)
    (try
      (with-redefs [auth/resolve-agent-dir (fn [] agent-dir)
                    auth/auth-atom (atom {})]
        (f path))
      (finally (fs/delete-tree agent-dir)))))

(defn- with-oauth-source
  "Install an OAuthAuth source for the test, restoring the previous source."
  [oauth f]
  (let [prev @#'auth/oauth-source]
    (try
      (auth/set-oauth-source! (fn [_] oauth))
      (f)
      (finally (auth/set-oauth-source! prev)))))

(defn- test-oauth-auth
  "A fake OAuthAuth whose refresh/to-auth are driven by the test."
  [& {:keys [refresh to-auth]
      :or {refresh (fn [cred _signal]
                     (assoc cred :access "rotated" :expires 9999999999999))
           to-auth (fn [cred] {:api-key (:access cred)
                               :base-url "https://api.example.com"})}}]
  (oauth/map->OAuthAuth {:name "Test"
                         :login (fn [_] {})
                         :refresh refresh
                         :to-auth to-auth}))

(t/deftest test-set-oauth-credential!
  (with-auth-file
    (fn [path]
      (testing "set-oauth-credential! writes the oauth shape"
        (auth/set-oauth-credential! :github-copilot
                                    {:type :oauth :access "a" :refresh "r"
                                     :expires 1234567890123
                                     :available-model-ids ["m1"]})
        (t/is (= {:github-copilot {:type :oauth :access "a" :refresh "r"
                                   :expires 1234567890123
                                   :available-model-ids ["m1"]}}
                 (edn/read-string (slurp path))))
        (t/is (= "a" (:access (auth/stored-oauth-credential :github-copilot)))))
      (testing "invalid credential throws"
        (t/is (thrown-with-msg? Exception #"Invalid OAuth credential"
                                (auth/set-oauth-credential! :github-copilot
                                                            {:type :oauth :access "a"})))))))

(t/deftest test-oauth-credential-persistence
  (with-auth-file
    (fn [path]
      (auth/set-oauth-credential! :github-copilot
                                  {:type :oauth :access "a" :refresh "r"
                                   :expires 1234567890123})
      (testing "load-auth! keeps the oauth entry"
        (auth/load-auth!)
        (t/is (= "a" (get-in (auth/get-credentials) [:github-copilot :access]))))
      (testing "invalid entries are dropped on load"
        (spit path (pr-str {:github-copilot {:type :oauth :access "a" :refresh "r"
                                             :expires 1234567890123}
                            :bad {:type :oauth :access "a"}}))
        (auth/load-auth!)
        (t/is (= #{:github-copilot} (set (keys (auth/get-credentials)))))))))

;; ─── Credential resolution + refresh on expiry (pi resolveStoredOAuth) ─────

(t/deftest test-resolve-oauth-fresh
  (with-auth-file
    (fn [_]
      (let [to-auth-calls (atom 0)
            fake (test-oauth-auth :to-auth (fn [cred]
                                             (swap! to-auth-calls inc)
                                             {:api-key (:access cred)
                                              :base-url "https://api.example.com"}))]
        (with-oauth-source
          fake
          (fn []
            (auth/set-oauth-credential! :github-copilot
                                        {:type :oauth :access "fresh"
                                         :refresh "r"
                                         :expires (+ (System/currentTimeMillis) 3600000)})
            (t/is (= {:api-key "fresh" :base-url "https://api.example.com"}
                     (auth/resolve-provider-auth :github-copilot)))
            (t/is (= 1 @to-auth-calls) "fresh token → no refresh")
            (t/is (= "fresh" (auth/resolve-api-key :github-copilot)))))))))

(t/deftest test-resolve-oauth-refresh-on-expiry
  (with-auth-file
    (fn [_]
      (let [refresh-count (atom 0)
            fake (test-oauth-auth
                  :refresh (fn [cred _signal]
                             (swap! refresh-count inc)
                             (assoc cred :access "rotated"
                                    :expires (+ (System/currentTimeMillis) 3600000)))
                  :to-auth (fn [cred] {:api-key (:access cred)
                                       :base-url "https://api.example.com"}))]
        (with-oauth-source
          fake
          (fn []
            (auth/set-oauth-credential! :github-copilot
                                        {:type :oauth :access "expiring"
                                         :refresh "r"
                                         ;; 1 min left — inside the 5-min window
                                         :expires (+ (System/currentTimeMillis) 60000)})
            (t/is (= {:api-key "rotated" :base-url "https://api.example.com"}
                     (auth/resolve-provider-auth :github-copilot))
                  "expiring token refreshed on resolve")
            (t/is (= 1 @refresh-count) "refresh receives the stored credential")
            (t/is (= "rotated" (get-in (auth/get-credentials)
                                       [:github-copilot :access]))
                  "rotated credential persisted")
            (testing "double-checked: a second resolve sees the fresh token"
              (t/is (= {:api-key "rotated" :base-url "https://api.example.com"}
                       (auth/resolve-provider-auth :github-copilot)))
              (t/is (= 1 @refresh-count) "no second refresh after rotation"))))))))

(t/deftest test-resolve-oauth-refresh-failure
  (with-auth-file
    (fn [_]
      (let [fake (test-oauth-auth
                  :refresh (fn [_ _] (throw (ex-info "invalid_grant"
                                                     {:type :oauth-refresh-failed}))))]
        (with-oauth-source
          fake
          (fn []
            (auth/set-oauth-credential! :github-copilot
                                        {:type :oauth :access "stale" :refresh "r"
                                         :expires 1})
            (t/is (nil? (auth/resolve-provider-auth :github-copilot))
                  "refresh failure resolves nil (request reports no auth)")
            (t/is (= "stale" (get-in (auth/get-credentials) [:github-copilot :access]))
                  "stored credential preserved for retry (pi)")))))))

(t/deftest test-resolve-oauth-no-oauth-auth
  (models/load-catalogs!)
  (with-auth-file
    (fn [_]
      (with-oauth-source
        nil
        (fn []
          ;; :deepseek has no OAuthAuth — a stored oauth credential there is
          ;; not configured and blocks env fallback (pi checkAuth)
          (auth/set-oauth-credential! :deepseek
                                      {:type :oauth :access "a" :refresh "r"
                                       :expires 9999999999999})
          (t/is (nil? (auth/resolve-provider-auth :deepseek))
                "no registered OAuthAuth → not configured")
          (t/is (false? (auth/configured? :deepseek))
                "configured? counts stored oauth only with a registered OAuthAuth")
          (t/is (= {:configured false} (models/get-provider-auth-status :deepseek))
                "auth status agrees with configured?"))))))

(t/deftest test-configured-oauth
  (models/load-catalogs!)
  (with-auth-file
    (fn [_]
      (let [fake (test-oauth-auth)]
        (with-oauth-source
          fake
          (fn []
            (auth/set-oauth-credential! :github-copilot
                                        {:type :oauth :access "a" :refresh "r"
                                         :expires 9999999999999})
            (t/is (true? (auth/configured? :github-copilot))
                  "stored oauth credential counts as configured")
            (t/is (= {:configured true :source :oauth}
                     (models/get-provider-auth-status :github-copilot)))
            (auth/remove-credential! :github-copilot)
            (t/is (false? (auth/configured? :github-copilot)))))))))

;; ─── Serialized credential ops (pi enqueueCredentialOperation) ─────────────

(t/deftest test-credential-ops-serialized
  (let [order (atom [])
        started (promise)
        gate (promise)
        op1 (auth/run-credential-op! :p (fn []
                                          (swap! order conj :one)
                                          (deliver started :go)
                                          @gate
                                          :done1))
        _ @started  ;; wait for op1 to start (no sleeps)
        op2 (auth/run-credential-op! :p (fn [] (swap! order conj :two) :done2))]
    (t/is (= [:one] @order) "op2 waits for op1")
    (deliver gate :go)
    (t/is (= :done1 @op1))
    (t/is (= :done2 @op2))
    (t/is (= [:one :two] @order) "serialized order")))

(t/deftest test-credential-op-failure-does-not-break-chain
  (let [order (atom [])
        op1 (auth/run-credential-op! :p (fn [] (swap! order conj :one)
                                          (throw (ex-info "boom" {}))))
        op2 (auth/run-credential-op! :p (fn [] (swap! order conj :two) :done2))]
    (t/is (thrown-with-msg? Exception #"boom" @op1))
    (t/is (= :done2 @op2) "a failed op does not block the next")
    (t/is (= [:one :two] @order))))

;; ─── Copilot available-model-ids filtering (pi filterModels) ───────────────

(t/deftest test-available-model-ids-filtering
  (models/load-catalogs!)
  (let [p (models/get-provider :github-copilot)
        all (count (:models p))]
    (with-redefs [auth/get-credentials (fn [] {})
                  auth/configured? (fn [_] true)]
      (testing "no stored credential → all models"
        (t/is (= all (count (models/get-available :github-copilot)))))
      (testing "oauth credential filters by available-model-ids"
        (let [mid (:id (first (:models p)))]
          (with-redefs [auth/get-credentials
                        (fn [] {:github-copilot
                                {:type :oauth :access "a" :refresh "r"
                                 :expires 9999999999999
                                 :available-model-ids [mid]}})]
            (let [available (models/get-available :github-copilot)]
              (t/is (= [mid] (mapv :id available))
                    "only the account's models remain"))))
        (testing "malformed available-model-ids → full list"
          (with-redefs [auth/get-credentials
                        (fn [] {:github-copilot
                                {:type :oauth :access "a" :refresh "r"
                                 :expires 9999999999999
                                 :available-model-ids "not-a-vector"}})]
            (t/is (= all (count (models/get-available :github-copilot))))))))))

;; ─── /login auth-type selection (pi showLoginAuthTypeSelector) ─────────────

(t/deftest test-login-provider-options
  (models/load-catalogs!)
  (let [options ((var inter/login-provider-options))]
    (testing "one entry per offered auth type, sorted by display name"
      (t/is (= options (sort-by :name options))))
    (testing "github-copilot offers oauth + api-key entries"
      (let [entries (filter #(= "github-copilot" (:id %)) options)]
        (t/is (= #{:oauth :api-key} (set (map :auth-type entries))))))
    (testing "openai-codex is oauth-only — no api-key login"
      (let [entries (filter #(= "openai-codex" (:id %)) options)]
        (t/is (= [:oauth] (mapv :auth-type entries)))))
    (testing "entries carry the provider's auth status for the ✓/• indicator"
      (t/is (every? #(contains? % :status) options)))))

(t/deftest test-find-login-provider-options
  (models/load-catalogs!)
  (let [find (var inter/find-login-provider-options)]
    (testing "exact id match"
      (t/is (= ["deepseek"] (mapv :id (find "deepseek")))))
    (testing "exact name match, case-insensitive (both auth-type entries)"
      (t/is (= [:oauth :api-key]
               (sort-by {:oauth 0 :api-key 1}
                        (mapv :auth-type (find "GitHub Copilot")))))
      (t/is (= #{"github-copilot"} (set (mapv :id (find "GITHUB COPILOT"))))))
    (testing "no match → empty"
      (t/is (= [] (find "no-such-provider"))))
    (testing "blank reference → nil (bare /login opens the selector)"
      (t/is (nil? (find "")))
      (t/is (nil? (find nil))))))

(t/deftest test-login-command-auth-type-selection
  (commands/clear-commands!)
  (models/load-catalogs!)
  ((var inter/register-builtin-commands!) cfg/default-config)
  (let [started (atom nil)
        sel-ref (atom nil)
        cs {:chat-history nil
            :session-atom nil
            :footer-comp nil
            :footer-provider nil
            :tui nil}]
    (with-redefs [dock/mount! (fn [_ component & _]
                                (reset! sel-ref component)
                                (fn []))
                  tui/tui-request-render (fn [_] nil)
                  inter/oauth-login! (fn [_ prov] (reset! started [:oauth prov]))
                  inter/api-key-login! (fn [_ prov] (reset! started [:api-key prov]))]
      (testing "bare /login opens the auth-type selector (pi)"
        (reset! started nil)
        ((:handler (commands/find-command "login")) cs "")
        (t/is (some? @sel-ref) "method selector mounted")
        (t/is (nil? @started) "no flow started yet")
        (testing "choosing the api-key option opens the provider selector"
          (let [on-select @(:on-select-atom @sel-ref)]
            (on-select "Sign in with an API key"))
          (t/is (nil? @started) "provider selector shown first")
          (testing "then choosing a provider starts its api-key flow"
            (let [provider-sel @sel-ref]
              (t/is (= :login (:mode provider-sel)))
              (let [on-select @(:on-select-atom provider-sel)]
                (on-select "deepseek" :api-key))
              (t/is (= :api-key (first @started))))))))))

(t/deftest test-login-command-reference-resolution
  (commands/clear-commands!)
  (models/load-catalogs!)
  ((var inter/register-builtin-commands!) cfg/default-config)
  (let [started (atom nil)
        sel-ref (atom nil)
        cs {:chat-history nil
            :session-atom nil
            :footer-comp nil
            :footer-provider nil
            :tui nil}]
    (with-redefs [dock/mount! (fn [_ component & _]
                                (reset! sel-ref component)
                                (fn []))
                  tui/tui-request-render (fn [_] nil)
                  inter/oauth-login! (fn [_ prov] (reset! started [:oauth prov]))
                  inter/api-key-login! (fn [_ prov] (reset! started [:api-key prov]))]
      (testing "an exact id reference starts directly (pi findLoginProviderOptions)"
        (reset! started nil)
        ((:handler (commands/find-command "login")) cs "deepseek")
        (t/is (= :api-key (first @started))))
      (testing "a display-name reference matches case-insensitively"
        (reset! started nil)
        ((:handler (commands/find-command "login")) cs "DeepSeek")
        (t/is (= :api-key (first @started))))
      (testing "a partial reference opens the provider selector pre-filled"
        (reset! started nil)
        (reset! sel-ref nil)
        ((:handler (commands/find-command "login")) cs "git")
        (t/is (some? @sel-ref) "provider selector mounted")
        (t/is (nil? @started) "no flow started yet")))))

;; ─── /logout credential kinds ───────────────────────────────────────────────

(t/deftest test-logout-removes-oauth-credential
  (commands/clear-commands!)
  (models/load-catalogs!)
  ((var inter/register-builtin-commands!) cfg/default-config)
  (with-auth-file
    (fn [_]
      (let [msgs (atom [])
            sel-ref (atom nil)
            cs {:chat-history nil
                :session-atom nil
                :footer-comp nil
                :footer-provider nil
                :tui nil}]
        (with-redefs [chat-history/chat-history-add-message! (fn [_ msg] (swap! msgs conj msg))
                      chat-history/show-warning! (fn [_ _] nil)
                      dock/mount! (fn [_ component & _]
                                    (reset! sel-ref component)
                                    (fn []))
                      tui/tui-request-render (fn [_] nil)]
          (auth/set-oauth-credential! :github-copilot
                                      {:type :oauth :access "a" :refresh "r"
                                       :expires 9999999999999})
          ;; /logout takes no argument — it always opens the selector (pi)
          ((:handler (commands/find-command "logout")) cs "")
          (t/is (some? @sel-ref) "logout selector mounted")
          (t/is (= :logout (:mode @sel-ref)))
          (let [entry (some #(when (= "github-copilot" (:id %)) %)
                            (:entries @(:state-atom @sel-ref)))]
            (t/is (some? entry) "stored credential listed")
            (t/is (= :oauth (:auth-type entry)))
            (let [on-select @(:on-select-atom @sel-ref)]
              (on-select "github-copilot" :oauth)))
          (t/is (= 1 (count @msgs)))
          (t/is (re-find #"Logged out of GitHub Copilot" (:content (first @msgs)))
                "oauth logout names the provider")
          (t/is (nil? (auth/stored-credential :github-copilot))
                "credential removed"))))))

;; ─── PKCE loopback batch (anthropic + codex browser + openrouter) ──────────

(defn- wait-for
  "Poll (fn [] value) until truthy or TIMEOUT-MS elapses (25ms slices)."
  [f timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (f)]
        (if (or v (< deadline (System/currentTimeMillis)))
          v
          (do (Thread/sleep 25) (recur)))))))

(t/deftest test-parse-authorization-input
  (testing "full URL → code + state"
    (t/is (= {:code "abc" :state "xyz"}
             (@#'oauth/parse-authorization-input
              "http://localhost:53692/callback?code=abc&state=xyz"))))
  (testing "code#state"
    (t/is (= {:code "abc" :state "xyz"} (@#'oauth/parse-authorization-input "abc#xyz"))))
  (testing "code= query"
    (t/is (= {:code "abc" :state "xyz"}
             (@#'oauth/parse-authorization-input "code=abc&state=xyz"))))
  (testing "bare code"
    (t/is (= {:code "abc"} (@#'oauth/parse-authorization-input "abc"))))
  (testing "blank → nil"
    (t/is (nil? (@#'oauth/parse-authorization-input "  ")))))

(t/deftest test-oauth-pages
  (let [html (@#'oauth/oauth-success-html "Done!")]
    (t/is (str/includes? html "Authentication successful"))
    (t/is (str/includes? html "Done!")))
  (let [html (@#'oauth/oauth-error-html "Failed" "<b>detail</b>")]
    (t/is (str/includes? html "Authentication failed"))
    (t/is (str/includes? html "&lt;b&gt;detail&lt;/b&gt;"))
    (t/is (str/includes? html "class=\"details\""))))

(t/deftest test-callback-server-http
  (let [received (atom nil)
        server (oauth/start-callback-server
                0
                (fn [req] (reset! received req)
                  {:status 200 :body "<html>ok</html>"}))]
    (try
      (let [resp (http/get (str "http://127.0.0.1:" (:port server) "/path?code=abc&state=xyz")
                           {:throw? false})]
        (t/is (= 200 (:status resp)))
        (t/is (str/includes? (:body resp) "ok")))
      (let [{:keys [method path query-params]} @received]
        (t/is (= "GET" method))
        (t/is (= "/path" path))
        (t/is (= "abc" (:code query-params)))
        (t/is (= "xyz" (:state query-params))))
      (finally ((:close server))))))

(t/deftest test-wait-for-callback-or-manual
  (testing "callback wins the race"
    (let [manual-p (promise)
          manual-entered (promise)
          interaction {:signal (atom false)
                       :prompt (fn [_]
                                 (deliver manual-entered true)
                                 (deref manual-p))}
          code-p (promise)
          _ (future
              (deref manual-entered 2000 :timeout)
              (deliver code-p {:code "c"}))]
      (try
        (let [r (@#'oauth/wait-for-callback-or-manual
                 interaction code-p {:type :manual-code :message "m"} 5000)]
          (t/is (= :callback (:source r)))
          (t/is (= "c" (get-in r [:value :code]))))
        (finally (deliver manual-p "x")))))
  (testing "flow cancel → :cancelled"
    (let [manual-p (promise)
          manual-entered (promise)
          signal (atom false)
          interaction {:signal signal
                       :prompt (fn [_]
                                 (deliver manual-entered true)
                                 (deref manual-p))}
          code-p (promise)
          _ (future
              (deref manual-entered 2000 :timeout)
              (reset! signal true))]
      (try
        (t/is (= :cancelled
                 (:source (@#'oauth/wait-for-callback-or-manual
                           interaction code-p {:type :manual-code :message "m"} 10000))))
        (finally (deliver manual-p "x"))))))

(t/deftest ^:slow test-wait-for-callback-or-manual-deadline
  (let [manual-p (promise)
        interaction {:signal (atom false)
                     :prompt (fn [_] (deref manual-p))}
        code-p (promise)]
    (t/is (= :timeout
             (:source (@#'oauth/wait-for-callback-or-manual
                       interaction code-p {:type :manual-code :message "m"} 100))))
    (deliver manual-p "x")))

(t/deftest test-anthropic-callback-server
  (let [server (@#'oauth/start-anthropic-callback-server "exp-state" 54603)]
    (try
      (let [base "http://127.0.0.1:54603"]
        (testing "wrong path → 404"
          (t/is (= 404 (:status (http/get (str base "/nope") {:throw? false})))))
        (testing "error param → 400"
          (t/is (= 400 (:status (http/get (str base "/callback?error=denied") {:throw? false})))))
        (testing "missing code → 400"
          (t/is (= 400 (:status (http/get (str base "/callback?state=x") {:throw? false})))))
        (testing "state mismatch → 400"
          (t/is (= 400 (:status (http/get (str base "/callback?code=c&state=wrong")
                                          {:throw? false})))))
        (testing "bad requests never settle the code promise"
          (t/is (= :pending (deref (:code-p server) 50 :pending))))
        (testing "valid callback → 200 + code/state delivered"
          (let [resp (http/get (str base "/callback?code=the-code&state=exp-state")
                               {:throw? false})]
            (t/is (= 200 (:status resp)))
            (t/is (= {:code "the-code" :state "exp-state"}
                     (deref (:code-p server) 100 :timeout))))))
      (finally ((:close server))))))

(t/deftest test-anthropic-exchange-and-refresh
  (let [captured (atom nil)]
    (with-redefs [oauth/fetch-json
                  (fn [url opts]
                    (reset! captured [url opts])
                    {:access_token "acc" :refresh_token "ref" :expires_in 3600})]
      (let [cred (@#'oauth/exchange-anthropic-authorization-code "code" "state" "verifier")]
        (t/is (= "acc" (:access cred)))
        (t/is (= "ref" (:refresh cred)))
        (t/is (number? (:expires cred)))
        (t/is (< (:expires cred) (+ (System/currentTimeMillis) (* 3600 1000)))
              "5-min skew applied")
        (let [[url opts] @captured]
          (t/is (= "https://platform.claude.com/v1/oauth/token" url))
          (t/is (= "authorization_code" (get-in opts [:body "grant_type"])))
          (t/is (= "code" (get-in opts [:body "code"])))
          (t/is (= "state" (get-in opts [:body "state"])))
          (t/is (= "verifier" (get-in opts [:body "code_verifier"])))))
      (let [cred (@#'oauth/refresh-anthropic-token "ref-token")]
        (t/is (= "acc" (:access cred)))
        (let [[_ opts] @captured]
          (t/is (= "refresh_token" (get-in opts [:body "grant_type"])))
          (t/is (= "ref-token" (get-in opts [:body "refresh_token"]))))))
    (with-redefs [oauth/fetch-json (fn [_ _] {})]
      (t/is (thrown-with-msg? Exception #"invalid JSON"
                              (@#'oauth/exchange-anthropic-authorization-code "c" "s" "v"))))))

(t/deftest test-anthropic-login-callback
  (let [test-port 54601
        auth-url (atom nil)
        manual-p (promise)
        interaction {:signal (atom false)
                     :prompt (fn [_] (deref manual-p 15000 :cancelled))
                     :abort-prompt! (fn [] (deliver manual-p "aborted"))
                     :notify (fn [e] (when (= :auth-url (:type e))
                                       (reset! auth-url (:url e))))}
        result-p (promise)]
    (with-redefs [oauth/exchange-anthropic-authorization-code
                  (fn [_code _state _verifier]
                    {:type :oauth :access "acc" :refresh "ref" :expires 1})]
      (future (deliver result-p
                       (try ((:login (oauth/make-anthropic-oauth test-port)) interaction)
                            (catch Exception e e))))
      (t/is (some? (wait-for (fn [] @auth-url) 5000)) "auth-url notified (server listening)")
      (let [state (second (re-find #"state=([^&]+)" @auth-url))]
        (t/is (some? state) "state = PKCE verifier, present in the authorize URL")
        (let [resp (http/get (str "http://127.0.0.1:" test-port "/callback?code=the-code&state=" state)
                             {:throw? false})]
          (t/is (= 200 (:status resp))))
        (let [credential (deref result-p 5000 :timeout)]
          (t/is (map? credential) "no exception — a credential")
          (t/is (= "acc" (:access credential)))
          (t/is (= "ref" (:refresh credential))))))))

(t/deftest test-anthropic-login-manual
  (let [interaction {:signal (atom false)
                     :prompt (fn [_] "manual-code")
                     :notify (fn [_] nil)}
        exchanged (atom nil)]
    (with-redefs [oauth/exchange-anthropic-authorization-code
                  (fn [code _state _verifier]
                    (reset! exchanged [code _state _verifier])
                    {:type :oauth :access "a" :refresh "r" :expires 1})]
      (let [cred ((:login (oauth/make-anthropic-oauth 0)) interaction)]
        (t/is (= "a" (:access cred)))
        (t/is (= "manual-code" (first @exchanged)))
        (t/is (some? (second @exchanged)) "state falls back to the verifier")))))

(t/deftest test-codex-browser-login-callback
  (let [test-port 54604
        auth-url (atom nil)
        manual-p (promise)
        interaction {:signal (atom false)
                     :prompt (fn [p] (if (= :select (:type p))
                                       "browser"
                                       (deref manual-p 15000 :cancelled)))
                     :abort-prompt! (fn [] (deliver manual-p "aborted"))
                     :notify (fn [e] (when (= :auth-url (:type e))
                                       (reset! auth-url (:url e))))}
        result-p (promise)]
    (with-redefs [oauth/codex-exchange-authorization-code
                  (fn [_code _verifier _redirect-uri]
                    {:access "acc" :refresh "ref" :expires 9999999999999})]
      (future (deliver result-p
                       (try ((:login (oauth/make-openai-codex-oauth test-port)) interaction)
                            (catch Exception e e))))
      (t/is (some? (wait-for (fn [] @auth-url) 5000)) "auth-url notified")
      (let [state (second (re-find #"state=([^&]+)" @auth-url))]
        (t/is (some? state))
        (t/is (re-find #"codex_cli_simplified_flow=true" @auth-url))
        (let [resp (http/get (str "http://127.0.0.1:" test-port "/auth/callback?code=cx&state=" state)
                             {:throw? false})]
          (t/is (= 200 (:status resp))))
        (let [credential (deref result-p 5000 :timeout)]
          (t/is (= "acc" (:access credential))))))))

(t/deftest test-codex-browser-login-manual
  (let [interaction {:signal (atom false)
                     :prompt (fn [p] (if (= :select (:type p))
                                       "browser"
                                       "manual-code"))
                     :notify (fn [_] nil)}
        exchanged (atom nil)]
    (with-redefs [oauth/codex-exchange-authorization-code
                  (fn [code _verifier redirect-uri]
                    (reset! exchanged [code _verifier redirect-uri])
                    {:access "a" :refresh "r" :expires 1})]
      (let [cred ((:login (oauth/make-openai-codex-oauth 0)) interaction)]
        (t/is (= "a" (:access cred)))
        (t/is (= "manual-code" (first @exchanged)))
        (t/is (= "http://localhost:1455/auth/callback" (nth @exchanged 2)))))))

(t/deftest test-codex-method-selector
  (let [prompts (atom [])
        interaction {:signal (atom false)
                     :prompt (fn [p] (swap! prompts conj p) "device_code")
                     :notify (fn [_] nil)}
        oauth (oauth/make-openai-codex-oauth)]
    (with-redefs [oauth/start-codex-device-auth
                  (fn [] {:device-auth-id "da" :user-code "UC" :interval 5})
                  oauth/poll-codex-device-auth
                  (fn [_ _] {:status :complete
                             :value {:authorization-code "ac" :code-verifier "v"}})
                  oauth/codex-exchange-authorization-code
                  (fn [_ _ _] {:access "a" :refresh "r" :expires 1})]
      (let [cred ((:login oauth) interaction)]
        (t/is (= "a" (:access cred)))
        (let [sel (first @prompts)]
          (t/is (= :select (:type sel)))
          (t/is (= 2 (count (:options sel))))
          (t/is (= "browser" (:id (first (:options sel)))))
          (t/is (= "device_code" (:id (second (:options sel))))))))))

(t/deftest test-parse-openrouter-code
  (testing "redirect URL → code"
    (t/is (= "abc" (@#'oauth/parse-openrouter-code
                    "http://127.0.0.1:12345/oauth/callback/x?code=abc"))))
  (testing "code= query"
    (t/is (= "abc" (@#'oauth/parse-openrouter-code "code=abc&state=ignored"))))
  (testing "bare code"
    (t/is (= "abc" (@#'oauth/parse-openrouter-code "abc"))))
  (testing "blank → nil"
    (t/is (nil? (@#'oauth/parse-openrouter-code "  ")))))

(t/deftest test-openrouter-exchange
  (with-redefs [oauth/fetch-json (fn [_ _] {:key "sk-or-v1-xyz"})]
    (let [cred (@#'oauth/exchange-openrouter-code "code" "verifier")]
      (t/is (= "sk-or-v1-xyz" (:access cred)))
      (t/is (= "" (:refresh cred)))
      (t/is (= Long/MAX_VALUE (:expires cred)))))
  (with-redefs [oauth/fetch-json (fn [_ _] {})]
    (t/is (thrown-with-msg? Exception #"no \"key\""
                            (@#'oauth/exchange-openrouter-code "c" "v")))))

(t/deftest test-openrouter-callback-server
  (with-redefs [oauth/exchange-openrouter-code
                (fn [_ _] {:type :oauth :access "key" :refresh "" :expires 1})]
    (let [server (@#'oauth/start-openrouter-callback-server "/oauth/callback/abc" "verifier")]
      (try
        (let [base (str/replace (:callback-url server) "/oauth/callback/abc" "")]
          (testing "wrong path → 404"
            (t/is (= 404 (:status (http/get (str base "/other") {:throw? false})))))
          (testing "no code → 400"
            (t/is (= 400 (:status (http/get (:callback-url server) {:throw? false})))))
          (testing "valid callback → 200 + credential delivered by the handler"
            (let [resp (http/get (str (:callback-url server) "?code=xyz") {:throw? false})]
              (t/is (= 200 (:status resp)))
              (t/is (str/includes? (:body resp) "Signed in"))
              (t/is (= "key" (:access (deref (:code-p server) 100 :timeout))))))
          (testing "already-claimed callback → 409"
            (t/is (= 409 (:status (http/get (str (:callback-url server) "?code=again")
                                            {:throw? false}))))))
        (finally ((:close server))))))
  (let [server (@#'oauth/start-openrouter-callback-server "/oauth/callback/err" "v")]
    (try
      (let [resp (http/get (str (:callback-url server) "?error=access_denied&error_description=nope")
                           {:throw? false})]
        (t/is (= 400 (:status resp)))
        (let [v (deref (:code-p server) 100 :timeout)]
          (t/is (instance? Exception v))
          (t/is (str/includes? (ex-message v) "nope")
                "error_description is preferred over error")))
      (finally ((:close server))))))

(t/deftest test-openrouter-login-callback
  (let [callback-url (atom nil)
        manual-p (promise)
        interaction {:signal (atom false)
                     :prompt (fn [_] (deref manual-p 15000 :cancelled))
                     :abort-prompt! (fn [] (deliver manual-p "aborted"))
                     :notify (fn [e]
                               (when (= :progress (:type e))
                                 (when-let [u (second (re-find #"callback on (http\S+)" (:message e)))]
                                   (reset! callback-url u))))}
        result-p (promise)]
    (with-redefs [oauth/exchange-openrouter-code
                  (fn [_code _verifier]
                    {:type :oauth :access "or-key" :refresh "" :expires Long/MAX_VALUE})]
      (future (deliver result-p
                       (try ((:login (oauth/make-open-router-oauth)) interaction)
                            (catch Exception e e))))
      (t/is (some? (wait-for (fn [] @callback-url) 5000)) "callback URL notified")
      (let [resp (http/get (str @callback-url "?code=xyz") {:throw? false})]
        (t/is (= 200 (:status resp)))
        (t/is (str/includes? (:body resp) "Signed in")))
      (let [credential (deref result-p 5000 :timeout)]
        (t/is (map? credential))
        (t/is (= "or-key" (:access credential)))))))

(t/deftest test-openrouter-login-manual
  (let [interaction {:signal (atom false)
                     :prompt (fn [_] "or-code")
                     :notify (fn [_] nil)}
        exchanged (atom nil)]
    (with-redefs [oauth/exchange-openrouter-code
                  (fn [code verifier]
                    (reset! exchanged [code verifier])
                    {:type :oauth :access "k" :refresh "" :expires Long/MAX_VALUE})]
      (let [cred ((:login (oauth/make-open-router-oauth)) interaction)]
        (t/is (= "k" (:access cred)))
        (t/is (= "or-code" (first @exchanged)))))))

(t/deftest test-builtin-oauth-providers
  (models/load-catalogs!)
  (doseq [id [:github-copilot :openai-codex :anthropic :openrouter]]
    (t/is (some? (:oauth (models/get-provider id)))
          (str (name id) " carries an OAuthAuth")))
  (t/is (nil? (:oauth (models/get-provider :deepseek)))
        "non-oauth providers carry none"))
