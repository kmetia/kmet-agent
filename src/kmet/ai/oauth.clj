(ns kmet.ai.oauth
  "OAuth subscriptions (pi: packages/ai/src/auth/types.ts +
   auth/oauth/*.ts, adapted to Babashka).

   kmet's OAuth-capable catalog providers: github-copilot (device-code,
   RFC 8628), openai-codex (browser PKCE loopback + device code), anthropic
   (Claude Pro/Max, PKCE loopback), openrouter (PKCE loopback, permanent
   key). The generic OAuthAuth record, device-code poll flow, PKCE
   machinery and the loopback callback server are shared plumbing for
   future subscription providers.

   Credential model (pi): OAuth credentials are plain maps
   {:type :oauth :access str :refresh str :expires ms
    :available-model-ids [str] :enterprise-url str?} stored in auth.edn
   (expires = provider expiry × 1000 − 5 min skew, pi). The refresh/to-auth
   split lets the credential store own the locked refresh pattern: refresh
   produces a credential, to-auth derives request auth (api-key + per-
   credential base-url, e.g. Copilot's proxy-ep endpoint).

   Interaction (pi AuthInteraction): a map {:signal cancel-atom :prompt
   (fn [prompt-map] → string) :notify (fn [event] nil)} provided by the UI
   (kmet.modes.interactive) — this namespace never touches the TUI.

   The generic machinery (device-code poll, PKCE, loopback callback server,
   RFC 8414/7591/8628 helpers) lives in kmet.libs.oauth — the shared seam
   with the mcp-adapter extension; this namespace keeps the provider flows
   and thin delegations (the private aliases below preserve the vars the
   tests with-redef)."
  (:require [kmet.libs.json :as json]
            [clojure.string :as str]
            [kmet.libs.http :as http]
            [kmet.libs.oauth :as oauth-lib]))

;; ─── Records (pi: types.ts OAuthAuth) ──────────────────────────────────────

(defrecord OAuthAuth [name            ;; display name, e.g. "GitHub Copilot"
                      is-subscription? ;; bool: access is a provider subscription
                      login-label     ;; selector label for the oauth login option
                      login           ;; (fn [interaction] → oauth credential)
                      refresh         ;; (fn [credential signal] → oauth credential)
                      to-auth         ;; (fn [credential] → {:api-key str :base-url str?})
                      ])

;; ─── Device-code polling — state machine in kmet.libs.oauth ───────────────

(def ^:private cancel-message "Login cancelled")

(defn- abortable-sleep
  "Sleep MS ms, aborting early when SIGNAL (an atom) turns truthy; then throw
   \"Login cancelled\" (pi abortableSleep). Checks in 100ms slices so cancel
   is never blocked by a long sleep. Delegates to kmet.libs.oauth; kept as a
   var here so the tests can with-redef it through the poll wrapper."
  [ms signal]
  (oauth-lib/abortable-sleep ms signal))

(defn poll-oauth-device-code-flow
  "RFC 8628 device-code polling (pi pollOAuthDeviceCodeFlow) — see
   kmet.libs.oauth. The :sleep option is wired to this namespace's
   abortable-sleep var so with-redefs in tests keep working."
  [opts]
  (oauth-lib/poll-oauth-device-code-flow (assoc opts :sleep abortable-sleep)))

;; ─── PKCE (pi: auth/oauth/pkce.ts) — machinery in kmet.libs.oauth ────────

(def generate-pkce
  "PKCE verifier + challenge (pi pkce.ts) — kmet.libs.oauth/generate-pkce."
  oauth-lib/generate-pkce)

(defn- random-hex
  "N random bytes as lowercase hex (pi createState)."
  [n]
  (oauth-lib/random-hex n))

;; ─── OAuth callback server + login pages — machinery in kmet.libs.oauth ──
;; Babashka has no bundled HTTP server, so the one-shot loopback callback is
;; a plain java.net.ServerSocket: read one HTTP request, answer it, close.

(defn- oauth-success-html
  "The success page shown after a completed callback (pi oauthSuccessHtml)."
  [message]
  (oauth-lib/oauth-success-html message))

(defn- oauth-error-html
  "The failure page shown for a bad/denied callback (pi oauthErrorHtml)."
  [message & [details]]
  (oauth-lib/oauth-error-html message details))

(def start-callback-server
  "One-shot HTTP callback server (pi: node http.createServer in the loopback
   OAuth flows) — kmet.libs.oauth/start-callback-server. PORT 0 binds an
   ephemeral port."
  oauth-lib/start-callback-server)

(defn- parse-authorization-input
  "pi parseAuthorizationInput (anthropic/codex) — kmet.libs.oauth."
  [input]
  (oauth-lib/parse-authorization-input input))

(defn- wait-for-callback-or-manual
  "Race the callback server's CODE-P promise against a manual-paste prompt
   (pi) — kmet.libs.oauth/wait-for-callback-or-manual."
  [interaction code-p prompt-map timeout-ms]
  (oauth-lib/wait-for-callback-or-manual interaction code-p prompt-map timeout-ms))

;; ─── GitHub Copilot device-code flow (pi: auth/oauth/github-copilot.ts) ───

(def ^:private copilot-client-id
  "The GitHub OAuth client id used by the Copilot Chat integrations
   (pi decodes the same literal)."
  "Iv1.b507a08c87ecfe98")

(def ^:private copilot-headers
  {"User-Agent" "GitHubCopilotChat/0.35.0"
   "Editor-Version" "vscode/1.107.0"
   "Editor-Plugin-Version" "copilot-chat/0.35.0"
   "Copilot-Integration-Id" "vscode-chat"})

(def ^:private copilot-api-version "2026-06-01")
(def ^:private copilot-policy-concurrency 4)

(defn- normalize-domain
  "Trim a GitHub Enterprise URL/domain to its hostname; nil when blank or
   unparsable (pi normalizeDomain)."
  [input]
  (let [trimmed (str/trim (or input ""))]
    (when (seq trimmed)
      (try
        (let [uri (java.net.URI. (if (str/includes? trimmed "://")
                                   trimmed
                                   (str "https://" trimmed)))
              host (.getHost uri)]
          (when (seq host) host))
        (catch Exception _ nil)))))

(defn- get-urls
  "Endpoints for a GitHub domain (pi getUrls)."
  [domain]
  {:device-code-url (str "https://" domain "/login/device/code")
   :access-token-url (str "https://" domain "/login/oauth/access_token")
   :copilot-token-url (str "https://api." domain "/copilot_internal/v2/token")})

(defn- get-base-url-from-token
  "Parse the proxy-ep claim from a Copilot token and convert it to the API
   base URL (pi getBaseUrlFromToken): 'proxy.xxx' → 'https://api.xxx'."
  [token]
  (when-let [proxy-host (second (re-find #"proxy-ep=([^;]+)" token))]
    (str "https://" (str/replace proxy-host #"^proxy\." "api."))))

(defn get-github-copilot-base-url
  "API base URL for a Copilot token/enterprise (pi getGitHubCopilotBaseUrl):
   the token's proxy-ep claim when present, else the enterprise
   copilot-api endpoint, else the individual default."
  ([token] (get-github-copilot-base-url token nil))
  ([token enterprise-domain]
   (or (when token (get-base-url-from-token token))
       (when enterprise-domain (str "https://copilot-api." enterprise-domain))
       "https://api.individual.githubcopilot.com")))

(defn- fetch-json
  "HTTP request expecting JSON (pi fetchJson) via http/request-json, which
   throws ex-info on non-2xx; returns the parsed JSON body (pi returns
   response.json() — callers read payload fields, not the status envelope)."
  [url opts]
  (:body (http/request-json url opts)))

(defn- start-device-flow
  "Start a device-code flow for a GitHub domain (pi startDeviceFlow):
   POST /login/device/code with the client id. Validates the response fields
   and that the verification URI is http(s)."
  [domain]
  (let [{:keys [device-code-url]} (get-urls domain)
        data (fetch-json device-code-url
                         {:method :post
                          :headers {"Accept" "application/json"
                                    "Content-Type" "application/x-www-form-urlencoded"
                                    "User-Agent" "GitHubCopilotChat/0.35.0"}
                          :body (str "client_id=" copilot-client-id "&scope=read%3Auser")
                          :timeout 15000})
        device-code (:device_code data)
        user-code (:user_code data)
        verification-uri (:verification_uri data)
        interval (:interval data)
        expires-in (:expires_in data)]
    (when-not (and (string? device-code)
                   (string? user-code)
                   (string? verification-uri)
                   (or (nil? interval) (number? interval))
                   (number? expires-in))
      (throw (ex-info "Invalid device code response fields"
                      {:type :oauth-invalid-response})))
    ;; The verification URI is opened in the user's browser — force a real
    ;; http(s) URL so `open` can never run an executable or similar (pi).
    (let [uri (try (java.net.URI. verification-uri)
                   (catch Exception _ nil))
          scheme (some-> uri .getScheme str/lower-case)]
      (when-not (and uri (contains? #{"https" "http"} scheme))
        (throw (ex-info "Untrusted verification_uri in device code response"
                        {:type :oauth-untrusted-uri})))
      {:device-code device-code
       :user-code user-code
       :verification-uri (.toString uri)
       :interval interval
       :expires-in expires-in})))

(defn- poll-for-github-access-token
  "Poll /login/oauth/access_token until the user authorizes (pi
   pollForGitHubAccessToken). Returns the GitHub access token."
  [domain device signal]
  (poll-oauth-device-code-flow
   {:interval-seconds (:interval device)
    :expires-in-seconds (:expires-in device)
    :wait-before-first-poll true
    :signal signal
    :poll
    (fn []
      (let [{:keys [access-token-url]} (get-urls domain)
            raw (fetch-json access-token-url
                            {:method :post
                             :headers {"Accept" "application/json"
                                       "Content-Type" "application/x-www-form-urlencoded"
                                       "User-Agent" "GitHubCopilotChat/0.35.0"}
                             :body (str "client_id=" copilot-client-id
                                        "&device_code=" (oauth-lib/url-encode (:device-code device))
                                        "&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")
                             :timeout 15000})]
        (cond
          (string? (:access_token raw))
          {:status :complete :value (:access_token raw)}

          (string? (:error raw))
          (case (:error raw)
            "authorization_pending" {:status :pending}
            "slow_down" {:status :slow_down :interval-seconds (:interval raw)}
            {:status :failed
             :message (str "Device flow failed: " (:error raw)
                           (when (:error_description raw)
                             (str ": " (:error_description raw))))})

          :else {:status :failed :message "Invalid device token response"})))}))

(defn- refresh-github-copilot-access-token
  "Exchange a GitHub access/refresh token for a Copilot token (pi
   refreshGitHubCopilotAccessToken): GET /copilot_internal/v2/token with
   Authorization: Bearer (pi sends no method — fetch defaults to GET; a
   POST 404s). Returns the oauth credential map with the 5-min expiry
   skew applied."
  [refresh-token enterprise-domain]
  (let [domain (or enterprise-domain "github.com")
        {:keys [copilot-token-url]} (get-urls domain)
        raw (fetch-json copilot-token-url
                        {:method :get
                         :headers (assoc copilot-headers
                                         "Accept" "application/json"
                                         "Authorization" (str "Bearer " refresh-token))
                         :timeout 15000})
        token (:token raw)
        expires-at (:expires_at raw)]
    (when-not (and (string? token) (number? expires-at))
      (throw (ex-info "Invalid Copilot token response fields"
                      {:type :oauth-invalid-response})))
    {:type :oauth
     :refresh refresh-token
     :access token
     :expires (- (* expires-at 1000) (* 5 60 1000))
     :enterprise-url enterprise-domain}))

(defn- parse-available-copilot-model-ids
  "Parse the /models response into the model ids the account can use (pi
   parseAvailableCopilotModelIds): picker-enabled ids take precedence,
   falling back to policy-enabled ids for individual accounts (which report
   false picker flags despite explicit enabled policies)."
  [raw allow-policy-fallback]
  (let [data (:data raw)]
    (when-not (vector? data)
      (throw (ex-info "Invalid Copilot models response"
                      {:type :oauth-invalid-response})))
    (let [picker-ids (atom [])
          policy-ids (atom [])]
      (doseq [item data]
        (when (map? item)
          (let [id (:id item)]
            (when (string? id)
              (let [supports (get-in item [:capabilities :supports])
                    policy (:policy item)]
                (when-not (false? (:tool_calls supports))
                  (when (and (true? (:model_picker_enabled item))
                             (not= "disabled" (:state policy)))
                    (swap! picker-ids conj id))
                  (when (= "enabled" (:state policy))
                    (swap! policy-ids conj id))))))))
      (if (or (seq @picker-ids) (not allow-policy-fallback))
        @picker-ids
        @policy-ids))))

(defn- fetch-available-github-copilot-model-ids
  "GET the Copilot /models endpoint and parse the usable ids (pi
   fetchAvailableGitHubCopilotModelIds; 5s timeout like pi's abort race)."
  [copilot-token enterprise-domain]
  (let [base-url (get-github-copilot-base-url copilot-token enterprise-domain)
        ;; Individual accounts return false for every picker flag despite
        ;; explicit enabled policies — limit the fallback to that endpoint.
        allow-policy-fallback (= base-url "https://api.individual.githubcopilot.com")
        raw (fetch-json (str base-url "/models")
                        {:method :get
                         :headers (assoc copilot-headers
                                         "Accept" "application/json"
                                         "Authorization" (str "Bearer " copilot-token)
                                         "X-GitHub-Api-Version" copilot-api-version)
                         :timeout 10000})]
    (parse-available-copilot-model-ids raw allow-policy-fallback)))

(defn- enable-github-copilot-model
  "POST a policy-enable for one Copilot model (pi enableGitHubCopilotModel);
   some models (Claude, Grok) need it before they can be used. Non-2xx or
   transport failure → false (pi swallows the error)."
  [token model-id enterprise-domain]
  (let [base-url (get-github-copilot-base-url token enterprise-domain)
        url (str base-url "/models/" model-id "/policy")]
    (try
      (let [response (http/request-json
                      url
                      {:method :post
                       :headers (assoc copilot-headers
                                       "Content-Type" "application/json"
                                       "Authorization" (str "Bearer " token)
                                       "openai-intent" "chat-policy"
                                       "x-interaction-type" "chat-policy")
                       :body (json/generate-string {:state "enabled"})
                       :timeout 15000})]
        (<= 200 (:status response) 299))
      (catch Exception _ false))))

(defn- enable-all-github-copilot-models
  "Enable all catalog Copilot models in batches of 4 (pi
   enableAllGitHubCopilotModels — COPILOT_POLICY_CONCURRENCY)."
  [token enterprise-domain model-ids]
  (doseq [batch (partition-all copilot-policy-concurrency model-ids)]
    (doseq [model-id batch]
      (enable-github-copilot-model token model-id enterprise-domain))))

(defn- login-github-copilot
  "Full device-code login (pi loginGitHubCopilot): prompt for the enterprise
   domain, start the flow, notify the device code, poll, exchange for the
   Copilot token, enable models, fetch the account's available model ids."
  [interaction model-ids]
  (let [input ((:prompt interaction)
               {:type :text
                :message "GitHub Enterprise URL/domain (blank for github.com)"
                :placeholder "company.ghe.com"})
        _ (when @(:signal interaction)
            (throw (ex-info cancel-message {:type :login-cancelled})))
        trimmed (str/trim (or input ""))
        enterprise-domain (normalize-domain input)]
    (when (and (seq trimmed) (nil? enterprise-domain))
      (throw (ex-info "Invalid GitHub Enterprise URL/domain"
                      {:type :oauth-invalid-domain})))
    (let [domain (or enterprise-domain "github.com")
          device (start-device-flow domain)]
      ((:notify interaction)
       {:type :device-code
        :user-code (:user-code device)
        :verification-uri (:verification-uri device)
        :interval-seconds (:interval device)
        :expires-in-seconds (:expires-in device)})
      (let [github-access-token (poll-for-github-access-token
                                 domain device (:signal interaction))
            credentials (refresh-github-copilot-access-token
                         github-access-token enterprise-domain)]
        ((:notify interaction) {:type :progress :message "Enabling models..."})
        (enable-all-github-copilot-models (:access credentials)
                                          enterprise-domain
                                          model-ids)
        (assoc credentials
               :available-model-ids (fetch-available-github-copilot-model-ids
                                     (:access credentials)
                                     enterprise-domain))))))

(defn- refresh-github-copilot-token
  "Refresh a Copilot credential (pi refreshGitHubCopilotToken): new access
   token + a refreshed available-model-ids list."
  [credential]
  (let [credentials (refresh-github-copilot-access-token
                     (:refresh credential) (:enterprise-url credential))]
    (assoc credentials
           :available-model-ids (fetch-available-github-copilot-model-ids
                                 (:access credentials)
                                 (:enterprise-url credentials)))))

(defn- copilot-enterprise-domain
  "Normalized enterprise domain from a credential (pi
   copilotEnterpriseDomain), nil when absent."
  [credential]
  (when (and (string? (:enterprise-url credential))
             (seq (:enterprise-url credential)))
    (normalize-domain (:enterprise-url credential))))

(defn make-github-copilot-oauth
  "The github-copilot OAuthAuth (pi githubCopilotOAuth). MODEL-IDS — a thunk
   returning the catalog model ids used for the enable-all policy step
   (kmet reads the committed github-copilot.edn catalog; pi imports the
   static GITHUB_COPILOT_MODELS list)."
  [model-ids]
  (map->OAuthAuth
   {:name "GitHub Copilot"
    :is-subscription? true
    :login (fn [interaction] (login-github-copilot interaction (model-ids)))
    :refresh (fn [credential _signal]
               (refresh-github-copilot-token credential))
    :to-auth (fn [credential]
               {:api-key (:access credential)
                :base-url (get-github-copilot-base-url
                           (:access credential)
                           (copilot-enterprise-domain credential))})}))

;; ─── OpenAI Codex (ChatGPT) OAuth (pi: auth/oauth/openai-codex.ts) ────────
;; Two login methods (pi): the browser PKCE loopback on the fixed port 1455
;; (/auth/callback, default) and the RFC 8628 device-code flow (headless,
;; same machinery as copilot). Both exchange the resulting authorization
;; code at the auth.openai.com token endpoint; the device flow yields an
;; authorization_code + PKCE verifier of its own.

(def ^:private codex-client-id "app_EMoamEEZ73f0CkXaXp7hrann")
(def ^:private codex-auth-base-url "https://auth.openai.com")
(def ^:private codex-device-user-code-url
  (str codex-auth-base-url "/api/accounts/deviceauth/usercode"))
(def ^:private codex-device-token-url
  (str codex-auth-base-url "/api/accounts/deviceauth/token"))
(def ^:private codex-verification-uri (str codex-auth-base-url "/codex/device"))
(def ^:private codex-device-redirect-uri
  (str codex-auth-base-url "/deviceauth/callback"))
(def ^:private codex-token-url (str codex-auth-base-url "/oauth/token"))
(def ^:private codex-device-code-timeout-seconds (* 15 60))
(def ^:private codex-callback-port 1455)
(def ^:private codex-callback-path "/auth/callback")
(def ^:private codex-redirect-uri
  (str "http://localhost:" codex-callback-port codex-callback-path))
(def ^:private codex-scope "openid profile email offline_access")
(def ^:private codex-browser-login-method "browser")
(def ^:private codex-device-code-login-method "device_code")

(defn- codex-credential-from-token
  "pi credentialsFromToken: the access/refresh/expires trio as an oauth
   credential. Expiry is absolute ms (now + expires_in*1000, pi) — the
   5-min validity window is applied at refresh time by oauth-fresh?."
  [{:keys [access refresh expires]}]
  {:type :oauth :access access :refresh refresh :expires expires})

(defn- codex-token-response
  "pi readTokenResponse: parse the token endpoint response into the
   access/refresh/expires trio; throws when a field is missing."
  [op data]
  (let [access (:access_token data)
        refresh (:refresh_token data)
        expires-in (:expires_in data)]
    (when-not (and (string? access) (string? refresh) (number? expires-in))
      (throw (ex-info (str "OpenAI Codex token " op " response missing fields: "
                           (pr-str data))
                      {:type :oauth-invalid-response})))
    {:access access
     :refresh refresh
     :expires (+ (System/currentTimeMillis) (* expires-in 1000))}))

(defn- codex-exchange-authorization-code
  "pi exchangeAuthorizationCode: grant_type=authorization_code with the PKCE
   verifier from the device flow."
  [code verifier redirect-uri]
  (codex-token-response
   "exchange"
   (fetch-json codex-token-url
               {:method :post
                :headers {"Content-Type" "application/x-www-form-urlencoded"}
                :body (str "grant_type=authorization_code"
                           "&client_id=" codex-client-id
                           "&code=" (oauth-lib/url-encode code)
                           "&code_verifier=" (oauth-lib/url-encode verifier)
                           "&redirect_uri=" (oauth-lib/url-encode redirect-uri))
                :timeout 15000})))

(defn- codex-refresh-access-token
  "pi refreshAccessToken: grant_type=refresh_token."
  [refresh-token]
  (codex-token-response
   "refresh"
   (fetch-json codex-token-url
               {:method :post
                :headers {"Content-Type" "application/x-www-form-urlencoded"}
                :body (str "grant_type=refresh_token"
                           "&refresh_token=" (oauth-lib/url-encode refresh-token)
                           "&client_id=" codex-client-id)
                :timeout 15000})))

(defn- start-codex-device-auth
  "pi startOpenAICodexDeviceAuth: POST the device usercode endpoint and
   validate device_auth_id/user_code/interval."
  []
  (let [data (fetch-json codex-device-user-code-url
                         {:method :post
                          :headers {"Content-Type" "application/json"}
                          :body (json/generate-string {:client_id codex-client-id})
                          :timeout 15000})
        device-auth-id (:device_auth_id data)
        user-code (:user_code data)
        interval (:interval data)]
    (when-not (and (string? device-auth-id)
                   (string? user-code)
                   (number? interval)
                   (not (neg? interval)))
      (throw (ex-info "Invalid OpenAI Codex device code response"
                      {:type :oauth-invalid-response})))
    {:device-auth-id device-auth-id
     :user-code user-code
     :interval interval}))

(defn- codex-device-poll
  "One poll of the codex device token endpoint (pi pollOpenAICodexDeviceAuth
   poll fn): 403/404 (pi checks response.status directly; request-json
   throws) and deviceauth_authorization_pending → pending, slow_down →
   slow_down, an authorization_code + verifier → complete."
  [device]
  (let [data (try
               (fetch-json codex-device-token-url
                           {:method :post
                            :headers {"Content-Type" "application/json"}
                            :body (json/generate-string
                                   {:device_auth_id (:device-auth-id device)
                                    :user_code (:user-code device)})
                            :timeout 15000})
               (catch Exception e
                 (let [{:keys [status]} (ex-data e)]
                   (if (contains? #{403 404} status)
                     {:error "deviceauth_authorization_pending"}
                     (throw e)))))
        error (:error data)
        error-code (if (map? error) (:code error) error)]
    (cond
      (and (string? (:authorization_code data))
           (string? (:code_verifier data)))
      {:status :complete
       :value {:authorization-code (:authorization_code data)
               :code-verifier (:code_verifier data)}}

      (= "deviceauth_authorization_pending" error-code)
      {:status :pending}

      (= "slow_down" error-code)
      {:status :slow_down}

      :else
      {:status :failed
       :message (str "OpenAI Codex device auth failed: " (pr-str data))})))

(defn- poll-codex-device-auth
  "pi pollOpenAICodexDeviceAuth: poll the device token endpoint until the
   flow yields an authorization_code + verifier."
  [device signal]
  (poll-oauth-device-code-flow
   {:interval-seconds (:interval device)
    :expires-in-seconds codex-device-code-timeout-seconds
    :signal signal
    :poll (fn [] (codex-device-poll device))}))

(defn- login-openai-codex-device-code
  "pi loginOpenAICodexDeviceCode: start the flow, notify the device code +
   verification URI, poll, exchange the authorization code for the
   credential."
  [interaction]
  (let [device (start-codex-device-auth)
        _ (when @(:signal interaction)
            (throw (ex-info cancel-message {:type :login-cancelled})))
        _ ((:notify interaction)
           {:type :device-code
            :user-code (:user-code device)
            :verification-uri codex-verification-uri
            :interval-seconds (:interval device)
            :expires-in-seconds codex-device-code-timeout-seconds})
        result (poll-codex-device-auth device (:signal interaction))]
    (codex-credential-from-token
     (codex-exchange-authorization-code (:authorization-code result)
                                        (:code-verifier result)
                                        codex-device-redirect-uri))))

;; ─── Codex browser PKCE loopback (pi openai-codex.ts loginOpenAICodex) ────

(defn- codex-authorize-url
  "pi createAuthorizationFlow: the authorize URL with the PKCE challenge and
   the codex CLI params (id_token_add_organizations, simplified flow,
   originator)."
  [challenge state]
  (str codex-auth-base-url "/oauth/authorize?response_type=code"
       "&client_id=" codex-client-id
       "&redirect_uri=" (oauth-lib/url-encode codex-redirect-uri)
       "&scope=" (oauth-lib/url-encode codex-scope)
       "&code_challenge=" (oauth-lib/url-encode challenge)
       "&code_challenge_method=S256"
       "&state=" (oauth-lib/url-encode state)
       "&id_token_add_organizations=true"
       "&codex_cli_simplified_flow=true"
       "&originator=pi"))

(defn- start-codex-callback-server
  "pi startLocalOAuthServer (codex): listen on the fixed port 1455 and
   settle the code promise when the browser hits /auth/callback with a
   matching state. PORT override is a test seam."
  [expected-state & [port]]
  (let [code-p (promise)
        server (start-callback-server
                (or port codex-callback-port)
                (fn [{:keys [path query-params]}]
                  (cond
                    (not= path codex-callback-path)
                    {:status 404 :body (oauth-error-html "Callback route not found.")}

                    (not= (:state query-params) expected-state)
                    {:status 400 :body (oauth-error-html "State mismatch.")}

                    (nil? (:code query-params))
                    {:status 400 :body (oauth-error-html "Missing authorization code.")}

                    :else
                    (do (deliver code-p {:code (:code query-params)})
                        {:status 200
                         :body (oauth-success-html
                                "OpenAI authentication completed. You can close this window.")}))))]
    {:server server :code-p code-p :close (:close server)}))

(defn- login-openai-codex-browser
  "pi loginOpenAICodex (browser): PKCE loopback on port 1455 — notify the
   authorize URL, race the callback against the manual-paste prompt,
   exchange the code for the credential. PORT override is a test seam."
  [interaction & [port]]
  (let [{:keys [verifier challenge]} (generate-pkce)
        state (oauth-lib/random-hex 16)
        url (codex-authorize-url challenge state)
        {:keys [server code-p]} (start-codex-callback-server state port)]
    (try
      ;; Check signal BEFORE notifying — matches pi's abort check before notify
      (when @(:signal interaction)
        (throw (ex-info cancel-message {:type :login-cancelled})))
      ((:notify interaction)
       {:type :auth-url :url url
        :instructions "A browser window should open. Complete login to finish."})
      (let [result (wait-for-callback-or-manual
                    interaction code-p
                    {:type :manual-code
                     :message "Complete login in your browser, or paste the authorization code / redirect URL here:"
                     :placeholder codex-redirect-uri}
                    600000)
            code (case (:source result)
                   :callback (:code (:value result))
                   :manual (let [parsed (parse-authorization-input (:value result))]
                             (when (and (:state parsed)
                                        (not= (:state parsed) state))
                               (throw (ex-info "State mismatch"
                                               {:type :oauth-state-mismatch})))
                             (:code parsed))
                   :cancelled (throw (ex-info cancel-message {:type :login-cancelled}))
                   :timeout (throw (ex-info "OAuth login timed out" {:type :oauth-timeout}))
                   :error (throw (:error result)))]
        (when-not code
          (throw (ex-info "Missing authorization code" {:type :oauth-missing-code})))
        (codex-credential-from-token
         (codex-exchange-authorization-code code verifier codex-redirect-uri)))
      (finally
        (when-let [abort-prompt! (:abort-prompt! interaction)]
          (abort-prompt!))
        ((:close server))))))

(defn make-openai-codex-oauth
  "The openai-codex OAuthAuth (pi openaiCodexOAuth). Login prompts for the
   method — browser PKCE loopback (default) or device code (headless).
   PORT override is a test seam for the browser callback server."
  [& [port]]
  (map->OAuthAuth
   {:name "OpenAI (ChatGPT Plus/Pro)"
    :is-subscription? true
    :login (fn [interaction]
             (let [method ((:prompt interaction)
                           {:type :select
                            :message "Select OpenAI Codex login method:"
                            :options [{:id codex-browser-login-method
                                       :label "Browser login (default)"}
                                      {:id codex-device-code-login-method
                                       :label "Device code login (headless)"}]})]
               (cond
                 (= method codex-browser-login-method)
                 (login-openai-codex-browser interaction port)

                 (= method codex-device-code-login-method)
                 (login-openai-codex-device-code interaction)

                 :else
                 (throw (ex-info (str "Unknown OpenAI Codex login method: " method)
                                 {:type :oauth-invalid-method})))))
    :refresh (fn [credential _signal]
               (codex-credential-from-token
                (codex-refresh-access-token (:refresh credential))))
    :to-auth (fn [credential] {:api-key (:access credential)})}))

;; ─── Anthropic (Claude Pro/Max) OAuth (pi: auth/oauth/anthropic.ts) ───────
;; PKCE loopback flow: browser → claude.ai authorize → local callback server
;; on the fixed port 53692 (/callback) → the authorization code is exchanged
;; at platform.claude.com for an access/refresh pair (5-min expiry skew).

(def ^:private anthropic-client-id
  "The Anthropic OAuth client id (pi decodes the same literal)."
  "9d1c250a-e61b-44d9-88ed-5944d1962f5e")
(def ^:private anthropic-authorize-url "https://claude.ai/oauth/authorize")
(def ^:private anthropic-token-url "https://platform.claude.com/v1/oauth/token")
(def ^:private anthropic-callback-port 53692)
(def ^:private anthropic-callback-path "/callback")
(def ^:private anthropic-redirect-uri
  (str "http://localhost:" anthropic-callback-port anthropic-callback-path))
(def ^:private anthropic-scopes
  "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload")

(defn- start-anthropic-callback-server
  "pi startCallbackServer (anthropic): listen on the fixed CALLBACK_PORT and
   settle the code promise when the browser hits /callback with a code +
   matching state (the state is the PKCE verifier, pi). PORT override is a
   test seam."
  [expected-state & [port]]
  (let [code-p (promise)
        server (start-callback-server
                (or port anthropic-callback-port)
                (fn [{:keys [path query-params]}]
                  (cond
                    (not= path anthropic-callback-path)
                    {:status 404 :body (oauth-error-html "Callback route not found.")}

                    (:error query-params)
                    {:status 400
                     :body (oauth-error-html "Anthropic authentication did not complete."
                                             (str "Error: " (:error query-params)))}

                    (or (nil? (:code query-params)) (nil? (:state query-params)))
                    {:status 400 :body (oauth-error-html "Missing code or state parameter.")}

                    (not= (:state query-params) expected-state)
                    {:status 400 :body (oauth-error-html "State mismatch.")}

                    :else
                    (do (deliver code-p {:code (:code query-params)
                                         :state (:state query-params)})
                        {:status 200
                         :body (oauth-success-html
                                "Anthropic authentication completed. You can close this window.")}))))]
    {:server server :code-p code-p :close (:close server)}))

(defn- exchange-anthropic-authorization-code
  "pi exchangeAuthorizationCode (anthropic): POST the token endpoint with the
   authorization code + verifier; returns the oauth credential with the
   5-min expiry skew applied."
  [code state verifier]
  (let [data (fetch-json anthropic-token-url
                         {:method :post
                          :headers {"Content-Type" "application/json"
                                    "Accept" "application/json"}
                          :body {"grant_type" "authorization_code"
                                 "client_id" anthropic-client-id
                                 "code" code
                                 "state" state
                                 "redirect_uri" anthropic-redirect-uri
                                 "code_verifier" verifier}
                          :timeout 30000})
        access (:access_token data)
        refresh (:refresh_token data)
        expires-in (:expires_in data)]
    (when-not (and (string? access) (string? refresh) (number? expires-in))
      (throw (ex-info "Token exchange returned invalid JSON"
                      {:type :oauth-invalid-response})))
    {:type :oauth
     :refresh refresh
     :access access
     :expires (- (+ (System/currentTimeMillis) (* expires-in 1000))
                 (* 5 60 1000))}))

(defn- refresh-anthropic-token
  "pi refreshAnthropicToken: grant_type=refresh_token; same credential shape
   (5-min skew)."
  [refresh-token]
  (let [data (fetch-json anthropic-token-url
                         {:method :post
                          :headers {"Content-Type" "application/json"
                                    "Accept" "application/json"}
                          :body {"grant_type" "refresh_token"
                                 "client_id" anthropic-client-id
                                 "refresh_token" refresh-token}
                          :timeout 30000})
        access (:access_token data)
        refresh (:refresh_token data)
        expires-in (:expires_in data)]
    (when-not (and (string? access) (string? refresh) (number? expires-in))
      (throw (ex-info "Anthropic token refresh returned invalid JSON"
                      {:type :oauth-invalid-response})))
    {:type :oauth
     :refresh refresh
     :access access
     :expires (- (+ (System/currentTimeMillis) (* expires-in 1000))
                 (* 5 60 1000))}))

(defn- login-anthropic
  "pi loginAnthropic: generate PKCE, start the callback server, notify the
   authorize URL, race the browser callback against the manual-paste prompt,
   exchange the code. PORT override is a test seam."
  [interaction & [port]]
  (let [{:keys [verifier challenge]} (generate-pkce)
        {:keys [server code-p]} (start-anthropic-callback-server verifier port)
        authorize-url (str anthropic-authorize-url "?code=true"
                           "&client_id=" anthropic-client-id
                           "&response_type=code"
                           "&redirect_uri=" (oauth-lib/url-encode anthropic-redirect-uri)
                           "&scope=" (oauth-lib/url-encode anthropic-scopes)
                           "&code_challenge=" (oauth-lib/url-encode challenge)
                           "&code_challenge_method=S256"
                           "&state=" (oauth-lib/url-encode verifier))]
    (try
      ;; Check signal BEFORE notifying — matches pi's abort check before notify
      (when @(:signal interaction)
        (throw (ex-info cancel-message {:type :login-cancelled})))
      ((:notify interaction)
       {:type :auth-url
        :url authorize-url
        :instructions "Complete login in your browser. If the browser is on another machine, paste the final redirect URL here."})
      (let [result (wait-for-callback-or-manual
                    interaction code-p
                    {:type :manual-code
                     :message "Complete login in your browser, or paste the authorization code / redirect URL here:"
                     :placeholder anthropic-redirect-uri}
                    600000)
            {:keys [code state]} (case (:source result)
                                   :callback (:value result)
                                   :manual (let [parsed (parse-authorization-input (:value result))]
                                             (when (and (:state parsed)
                                                        (not= (:state parsed) verifier))
                                               (throw (ex-info "OAuth state mismatch"
                                                               {:type :oauth-state-mismatch})))
                                             {:code (:code parsed)
                                              :state (or (:state parsed) verifier)})
                                   :cancelled (throw (ex-info cancel-message
                                                              {:type :login-cancelled}))
                                   :timeout (throw (ex-info "OAuth login timed out"
                                                            {:type :oauth-timeout}))
                                   :error (throw (:error result)))]
        (when-not code
          (throw (ex-info "Missing authorization code" {:type :oauth-missing-code})))
        ((:notify interaction)
         {:type :progress :message "Exchanging authorization code for tokens..."})
        (exchange-anthropic-authorization-code code state verifier))
      (finally
        (when-let [abort-prompt! (:abort-prompt! interaction)]
          (abort-prompt!))
        ((:close server))))))

(defn make-anthropic-oauth
  "The anthropic OAuthAuth (pi anthropicOAuth) — the Claude Pro/Max
   subscription via PKCE loopback login. PORT override is a test seam."
  [& [port]]
  (map->OAuthAuth
   {:name "Anthropic (Claude Pro/Max)"
    :is-subscription? true
    :login (fn [interaction] (login-anthropic interaction port))
    :refresh (fn [credential _signal]
               (refresh-anthropic-token (:refresh credential)))
    :to-auth (fn [credential] {:api-key (:access credential)})}))

;; ─── OpenRouter OAuth (pi: auth/oauth/openrouter.ts) ───────────────────────
;; PKCE loopback on an ephemeral port: the callback server itself exchanges
;; the code for a permanent user-controlled API key (the /auth/keys endpoint
;; — no expiring token pair, refresh is the identity). The callback URL is a
;; fresh /oauth/callback/<uuid> path so an old callback cannot claim a new
;; login.

(def ^:private openrouter-authorize-url "https://openrouter.ai/auth")
(def ^:private openrouter-token-url "https://openrouter.ai/api/v1/auth/keys")
(def ^:private openrouter-login-timeout-ms (* 5 60 1000))
(def ^:private openrouter-exchange-timeout-ms 30000)

(defn- parse-openrouter-code
  "pi parseAuthorizationInput (openrouter): the code from a redirect URL, a
   'code=...' query, or a bare code (no state — the openrouter flow's code
   is one-shot)."
  [input]
  (let [value (str/trim (or input ""))]
    (when (seq value)
      (cond
        (str/includes? value "://")
        (let [uri (try (java.net.URI. value) (catch Exception _ nil))]
          (when uri (:code (oauth-lib/parse-query-string (.getQuery uri)))))
        (str/includes? value "code=")
        (:code (oauth-lib/parse-query-string value))
        :else value))))

(defn- exchange-openrouter-code
  "pi exchangeAuthorizationCode (openrouter): POST the keys endpoint with
   code + verifier; the response carries the permanent 'key' — refresh is
   empty and expires is effectively never (pi)."
  [code verifier]
  (let [data (fetch-json openrouter-token-url
                         {:method :post
                          :headers {"Accept" "application/json"
                                    "Content-Type" "application/json"}
                          :body {"code" code
                                 "code_verifier" verifier
                                 "code_challenge_method" "S256"}
                          :timeout openrouter-exchange-timeout-ms})]
    (when-not (and (string? (:key data)) (seq (:key data)))
      (throw (ex-info "OpenRouter OAuth response carries no \"key\""
                      {:type :oauth-invalid-response})))
    {:type :oauth
     :access (:key data)
     :refresh ""
     :expires Long/MAX_VALUE}))

(defn- start-openrouter-callback-server
  "pi startCallbackServer (openrouter): ephemeral-port loopback server on a
   fresh /oauth/callback/<uuid> path. The handler itself exchanges the code
   (the manual-paste fallback exchanges in the flow) and settles the
   credential promise with the credential map or the exchange exception. A
   claimed callback answers 409 (pi)."
  [callback-path verifier]
  (let [credential-p (promise)
        claimed (atom false)
        server (start-callback-server
                0
                (fn [{:keys [method path query-params]}]
                  (cond
                    (or (not= method "GET") (not= path callback-path))
                    {:status 404 :body (oauth-error-html "OAuth callback route not found.")}

                    @claimed
                    {:status 409 :body (oauth-error-html "This OAuth callback has already been used.")}

                    (:error query-params)
                    (let [description (or (:error_description query-params)
                                          (:error query-params))]
                      (deliver credential-p
                               (ex-info (str "OpenRouter authorization failed: " description)
                                        {:type :oauth-authorization-failed}))
                      {:status 400
                       :body (oauth-error-html "OpenRouter authorization was denied." description)})

                    (nil? (:code query-params))
                    {:status 400 :body (oauth-error-html "OpenRouter returned no authorization code.")}

                    :else
                    (do (reset! claimed true)
                        (try
                          (let [credential (exchange-openrouter-code
                                            (:code query-params) verifier)]
                            (deliver credential-p credential)
                            {:status 200
                             :body (oauth-success-html
                                    "Signed in to OpenRouter. You may now close this page.")})
                          (catch Exception e
                            (deliver credential-p e)
                            {:status 502
                             :body (oauth-error-html "OpenRouter key exchange failed."
                                                     (ex-message e))}))))))]
    {:callback-url (str "http://" (oauth-lib/callback-host) ":" (:port server) callback-path)
     :code-p credential-p
     :close (:close server)}))

(defn- login-open-router
  "pi loginOpenRouter: PKCE + ephemeral callback server; the callback
   exchanges the code itself, the manual-paste fallback exchanges it in the
   flow."
  [interaction]
  (let [{:keys [verifier challenge]} (generate-pkce)
        callback-path (str "/oauth/callback/" (oauth-lib/random-hex 16))
        callback (start-openrouter-callback-server callback-path verifier)]
    (try
      ;; Check signal BEFORE notifying — matches pi's abort check before notify
      (when @(:signal interaction)
        (throw (ex-info cancel-message {:type :login-cancelled})))
      ((:notify interaction)
       {:type :progress
        :message (str "Listening for OpenRouter OAuth callback on " (:callback-url callback))})
      ((:notify interaction)
       {:type :auth-url
        :url (str openrouter-authorize-url
                  "?callback_url=" (oauth-lib/url-encode (:callback-url callback))
                  "&code_challenge=" (oauth-lib/url-encode challenge)
                  "&code_challenge_method=S256")
        :instructions "Complete sign-in in your browser. If the browser is on another machine, paste the final redirect URL here."})
      (let [result (wait-for-callback-or-manual
                    interaction (:code-p callback)
                    {:type :manual-code
                     :message "Complete sign-in in your browser, or paste the authorization code / redirect URL here:"
                     :placeholder (:callback-url callback)}
                    openrouter-login-timeout-ms)]
        (case (:source result)
          :callback
          (let [v (:value result)]
            (if (instance? Exception v) (throw v) v))

          :manual
          (let [code (parse-openrouter-code (:value result))]
            (when-not code
              (throw (ex-info "Missing authorization code" {:type :oauth-missing-code})))
            ((:notify interaction)
             {:type :progress :message "Exchanging authorization code for an API key..."})
            (exchange-openrouter-code code verifier))

          :cancelled (throw (ex-info cancel-message {:type :login-cancelled}))
          :timeout (throw (ex-info "OpenRouter OAuth login timed out" {:type :oauth-timeout}))
          :error (throw (:error result))))
      (finally
        (when-let [abort-prompt! (:abort-prompt! interaction)]
          (abort-prompt!))
        ((:close callback))))))

(defn make-open-router-oauth
  "The openrouter OAuthAuth (pi openRouterOAuth): the permanent-key loopback
   flow; refresh is the identity (the key never expires)."
  []
  (map->OAuthAuth
   {:name "OpenRouter OAuth"
    :login-label "Sign in with OpenRouter"
    :login login-open-router
    :refresh (fn [credential _signal] credential)
    :to-auth (fn [credential] {:api-key (:access credential)})}))
