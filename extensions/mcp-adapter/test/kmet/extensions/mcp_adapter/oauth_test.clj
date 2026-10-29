(ns kmet.extensions.mcp-adapter.oauth-test
  "§12.5 OAuth flows against scripts/fake-oauth-server.bb: DCR → PKCE
   loopback → token → bearer headers → 401 refresh, the RFC 8628 device
   flow, client-credentials and jwt-bearer machine grants, RFC 9728/8414
   discovery, the PKCE requirement and SEP-2352 issuer binding. The
   \"browser\" is simulated by hitting the redirect URI with the state the
   authorize URL carried. Spawns fixtures (^:slow)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.mcp.auth :as mcp-auth]
            [kmet.libs.oauth :as oauth-lib]
            [kmet.extensions.mcp-adapter.auth :as auth]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

;; ─── Fixture ──────────────────────────────────────────────────────────────

(defmacro ^:private with-oauth
  "Fake authorization server + a temp credential store for BODY. Binds the
   server map and the store path."
  [[server-sym store-sym] & body]
  `(tu/with-server [~server-sym "fake-oauth-server.bb"]
     (let [dir# (tu/temp-dir! "mcp-oauth")
           ~store-sym (str dir# "/store.edn")]
       (try
         (with-redefs [auth/store-path (fn [] ~store-sym)]
           ;; force the file backend so the temp path is really used on
           ;; hosts that have a keyring (unless MCP_TOKEN_STORAGE overrides)
           (auth/configure-storage! {:token-storage :file})
           ~@body)
         (finally
           (mcp-auth/configure-storage! {:mode :auto})
           (auth/shutdown!)
           (tu/delete-tree! dir#))))))

;; ─── HTTP + fake-AS helpers ───────────────────────────────────────────────

(defn- http-get!
  "Minimal HTTP GET (raw socket) — used to hit the loopback callback."
  [url]
  (let [uri (java.net.URI. url)
        port (.getPort uri)
        s (java.net.Socket. (.getHost uri) port)]
    (try
      (.setSoTimeout s 3000)
      (.write (.getOutputStream s)
              (.getBytes (str "GET " (or (.getRawPath uri) "/")
                              (when (.getRawQuery uri) (str "?" (.getRawQuery uri)))
                              " HTTP/1.1\r\nHost: x\r\n\r\n") "UTF-8"))
      (.flush (.getOutputStream s))
      (let [text (slurp (.getInputStream s))]
        (str/starts-with? text "HTTP/1.1 200"))
      (catch Exception _ false)
      (finally (.close s)))))

(defn- last-token-request
  "What the fake authorization server last received at /token — lets the
   assertions check the RFC 8707 resource indicator and the scopes."
  [port]
  (:body (oauth-lib/fetch-json (str "http://127.0.0.1:" port "/last-token-request")
                               {:method :get})))

(defn- last-registration-request
  "What the fake authorization server last received at /register (the
   SEP-837 application_type check)."
  [port]
  (:body (oauth-lib/fetch-json (str "http://127.0.0.1:" port "/last-registration-request")
                               {:method :get})))

(defn- params-of [url]
  (into {} (for [pair (str/split (or (second (str/split url #"\?" 2)) "") #"&")]
             (let [[k v] (str/split pair #"=" 2)]
               [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                (java.net.URLDecoder/decode (or v "") "UTF-8")]))))

(defn- interaction
  "Test interaction: no UI, no real browser. When the flow emits the
   authorize URL, a future simulates the browser — the AS redirects to the
   loopback callback with code + state + the issuer's `iss` (RFC 9207) —
   and the callback server settles the code promise. Pass {:iss ...} to
   simulate a mismatching or absent `iss`; the prompt blocks so only the
   callback can win."
  [events issuer & [{:keys [iss] :or {iss issuer}}]]
  {:signal (atom false)
   :has-ui false
   :open-url (fn [_])
   :notify (fn [event]
             (when (= :auth-url (:type event))
               (swap! events conj event)
               (future
                 (let [url (:url event)
                       p (params-of url)
                       redirect-uri (:redirect_uri p)
                       callback (str redirect-uri "?code=fake-code&state=" (:state p)
                                     (when iss
                                       (str "&iss=" (java.net.URLEncoder/encode iss "UTF-8"))))]
                   (http-get! callback))))
             (when (= :device-code (:type event))
               (swap! events conj event)))
   :prompt (fn [_] (Thread/sleep 30000) nil)})

(defn- pkce-definition [port & [extra-oauth]]
  {:url (str "http://127.0.0.1:" port "/mcp")
   :auth :oauth
   :oauth (merge {:flow :pkce :scopes ["read"]} extra-oauth)})

;; ─── DCR + PKCE loopback ──────────────────────────────────────────────────

(deftest ^:slow pkce-loopback-flow
  (with-oauth [server _store]
    (let [port (:port server)
          issuer (str "http://127.0.0.1:" port)
          events (atom [])
          definition (pkce-definition port)
          abort-called (atom false)
          flow-interaction (assoc (interaction events issuer)
                                  :abort-prompt! (fn [] (reset! abort-called true)))
          status (auth/run-flow! "pkce-server" definition flow-interaction)
          auth-event (first (filter #(= :auth-url (:type %)) @events))]
      (testing "the flow reports a login and dismisses the manual prompt"
        (is (= :logged-in status))
        (is (true? @abort-called)))
      (testing "the authorize URL carries the PKCE parameters"
        (is (and auth-event (str/includes? (:url auth-event) "/authorize")))
        (let [p (params-of (:url auth-event))]
          (is (= "S256" (:code_challenge_method p)))
          (is (seq (:code_challenge p)))
          (is (= "read" (:scope p)))
          (is (seq (:state p)))))
      (testing "the callback delivered the code and the tokens are stored"
        (let [entry (auth/server-entry "pkce-server")]
          (is (some? entry))
          (is (seq (get-in entry [:tokens :access])))
          (is (seq (get-in entry [:tokens :refresh])))
          (is (= "dcr-client-1" (get-in entry [:client-info :client-id])))
          (testing "the DCR'd redirect URI is the one the callback served"
            (let [decoded (java.net.URLDecoder/decode
                           (get (params-of (:url auth-event)) :redirect_uri) "UTF-8")]
              (is (some #(= decoded %) (get-in entry [:client-info :redirect-uris])))))))
      (testing "DCR advertises the native application type (SEP-837)"
        (is (= "native" (:application_type (last-registration-request port)))))
      (testing "auth headers carry the stored bearer token"
        (let [headers ((:auth-headers (auth/make-auth-fns "pkce-server" definition)))]
          (is (= (str "Bearer " (get-in (auth/server-entry "pkce-server")
                                        [:tokens :access]))
                 (get headers "Authorization")))))
      (testing "RFC 8707: the authorize and token requests carry the resource"
        (is (= (str "http://127.0.0.1:" port "/mcp")
               (:resource (params-of (:url auth-event)))))
        (is (= (str "http://127.0.0.1:" port "/mcp")
               (:resource (last-token-request port)))))
      (testing "status reports the logged-in server"
        (is (= :logged-in (auth/auth-status "pkce-server" definition)))))))

(deftest ^:slow pkce-401-refresh
  (with-oauth [server _store]
    (let [port (:port server)
          issuer (str "http://127.0.0.1:" port)
          definition (pkce-definition port)]
      (auth/run-flow! "pkce-server" definition (interaction (atom []) issuer))
      (testing "a 401 refreshes the access token and retries once"
        (auth/store-server! "pkce-server"
                            (assoc-in (auth/server-entry "pkce-server")
                                      [:tokens :access] "stale-access"))
        (let [auth-fns (auth/make-auth-fns "pkce-server" definition)
              challenge {:headers {"www-authenticate"
                                   (str "Bearer resource_metadata=\"http://127.0.0.1:"
                                        port
                                        "/.well-known/oauth-protected-resource\", "
                                        "scope=\"files:read\"")}}
              headers ((:on-401 auth-fns) challenge)
              new-access (get-in (auth/server-entry "pkce-server") [:tokens :access])]
          (is (and (not= "stale-access" new-access) (seq new-access)))
          (is (= (str "Bearer " new-access) (get headers "Authorization")))))
      (testing "the challenged scope is used when the config names none"
        (let [bare (assoc definition :oauth {:flow :pkce})]
          (auth/run-flow! "pkce-server" bare (interaction (atom []) issuer))
          (is (= "files:read" (:scope (last-token-request port)))))))))

(deftest ^:slow rfc-9207-issuer-validation
  (with-oauth [server _store]
    (let [port (:port server)
          issuer (str "http://127.0.0.1:" port)
          definition (pkce-definition port)]
      (auth/run-flow! "pkce-server" definition (interaction (atom []) issuer))
      (testing "a mismatching iss is rejected before the code is exchanged"
        (is (try (auth/run-flow! "pkce-server" definition
                                 (interaction (atom []) issuer
                                              {:iss "https://evil.example"}))
                 false
                 (catch Exception e (= :oauth-issuer-mismatch (:type (ex-data e)))))))
      (testing "a missing iss is rejected when the AS advertised support"
        (is (try (auth/run-flow! "pkce-server" definition
                                 (interaction (atom []) issuer {:iss nil}))
                 false
                 (catch Exception e (= :oauth-iss-missing (:type (ex-data e))))))))))

;; ─── Configured redirect URI ──────────────────────────────────────────────

(deftest ^:slow configured-redirect-uri-binds-once
  (with-oauth [server _store]
    (let [port (:port server)
          issuer (str "http://127.0.0.1:" port)
          probe (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
          cb-port (.getLocalPort probe)
          _ (.close probe)
          redirect-uri (str "http://127.0.0.1:" cb-port "/custom-cb")
          events (atom [])
          definition (pkce-definition port {:redirect-uri redirect-uri})
          status (auth/run-flow! "custom-redirect" definition
                                 (interaction events issuer))
          p (params-of (:url (first (filter #(= :auth-url (:type %)) @events))))]
      (testing "the configured redirect URI is advertised and served"
        (is (= :logged-in status))
        (is (= redirect-uri (java.net.URLDecoder/decode (:redirect_uri p) "UTF-8")))
        (is (some? (get-in (auth/server-entry "custom-redirect") [:tokens :access]))))
      (testing "a later config cannot rebind the callback server"
        (is (= (str "http://" (oauth-lib/callback-host) ":" cb-port "/custom-cb")
               (auth/callback-redirect-uri {:port cb-port :path "/custom-cb"}
                                           (inc cb-port) "/other"
                                           (str "http://127.0.0.1:" (inc cb-port)
                                                "/other")))))
      (testing "a path-less config derives the served callback path"
        (is (= (str "http://" (oauth-lib/callback-host) ":" cb-port "/callback")
               (auth/callback-redirect-uri {:port cb-port :path "/callback"}
                                           cb-port nil
                                           (str "http://127.0.0.1:" cb-port)))))
      (auth/logout! "custom-redirect"))))

;; ─── RFC 8628 device flow ─────────────────────────────────────────────────

(deftest ^:slow device-flow
  (with-oauth [server _store]
    (let [port (:port server)
          issuer (str "http://127.0.0.1:" port)
          events (atom [])
          definition {:url (str issuer "/mcp")
                      :auth :oauth
                      :oauth {:flow :device :scopes ["read"]}}
          status (auth/run-flow! "device-server" definition
                                 (interaction events issuer))
          device-event (first (filter #(= :device-code (:type %)) @events))]
      (testing "the user code is surfaced with its verification URI"
        (is (= :logged-in status))
        (is (= "ABCD-EFGH" (:user-code device-event)))
        (is (str/includes? (:verification-uri device-event) "/device-verify")))
      (testing "the device tokens are stored with their client id"
        (let [entry (auth/server-entry "device-server")]
          (is (seq (get-in entry [:tokens :access])))
          (is (= "dcr-client-1" (get-in entry [:client-info :client-id])))))
      (testing "logout clears the store"
        (auth/logout! "device-server")
        (is (nil? (auth/server-entry "device-server")))
        (is (= :none (auth/auth-status "device-server" definition)))))))

;; ─── Credential-less store entry ──────────────────────────────────────────

(deftest ^:slow tokenless-store-entry
  (with-oauth [server _store]
    (let [issuer (str "http://127.0.0.1:" (:port server))
          definition {:url (str issuer "/mcp") :auth :oauth :oauth {:flow :pkce}}]
      ;; what an interrupted issuer change leaves behind: a shared client
      ;; registration with no tokens for this server
      (auth/store-server! "tokenless" {:issuer issuer
                                       :client-info {:client-id "c1"}})
      (testing "a client registration alone is not logged in"
        (is (= :none (auth/auth-status "tokenless" definition))))
      (testing "auth headers refuse a credential-less entry"
        (is (try ((:auth-headers (auth/make-auth-fns "tokenless" definition)))
                 false
                 (catch Exception e (= :mcp-auth-required (:type (ex-data e)))))))
      (testing "logout clears the credential-less entry"
        (auth/logout! "tokenless")
        (is (nil? (auth/server-entry "tokenless")))))))

;; ─── Machine grants ───────────────────────────────────────────────────────

(deftest ^:slow client-credentials-grant
  (with-oauth [server _store]
    (let [port (:port server)
          definition {:url (str "http://127.0.0.1:" port "/mcp")
                      :auth :oauth
                      :oauth {:grant :client-credentials
                              :client-id "cc-client-1"
                              :client-secret "cc-secret"
                              :scopes ["read"]}}
          interaction {:signal (atom false) :has-ui false
                       :notify (fn [_]) :prompt (fn [_] nil) :open-url (fn [_])}
          status (auth/run-flow! "cc-server" definition interaction)]
      (testing "the machine flow fetches a token with no browser"
        (is (= :logged-in status))
        (is (str/starts-with?
             (get ((:auth-headers (auth/make-auth-fns "cc-server" definition)))
                  "Authorization")
             "Bearer access-cc-")))
      (testing "status reports the grant"
        (is (= :client-credentials (auth/auth-status "cc-server" definition))))
      (testing "the 401 path re-fetches a fresh token"
        (let [headers ((:on-401 (auth/make-auth-fns "cc-server" definition))
                       {:headers {}})]
          (is (str/starts-with? (get headers "Authorization") "Bearer access-cc-"))))
      (testing "logout clears the machine cache"
        (auth/logout! "cc-server")
        (is (not (auth/machine-token-cached? "cc-server")))))))

(deftest ^:slow jwt-bearer-grant
  (with-oauth [server _store]
    (let [port (:port server)
          kg (java.security.KeyPairGenerator/getInstance "RSA")]
      (.initialize kg 2048)
      (let [der (.getEncoded (.getPrivate (.generateKeyPair kg)))
            b64 (.encodeToString (java.util.Base64/getEncoder) der)
            pem (str "-----BEGIN PRIVATE KEY-----\n"
                     (str/join "\n" (map #(apply str %) (partition-all 64 b64)))
                     "\n-----END PRIVATE KEY-----\n")
            dir (tu/temp-dir! "mcp-jwt")
            key-file (str dir "/svc.pem")]
        (try
          (spit key-file pem)
          (let [definition {:url (str "http://127.0.0.1:" port "/mcp")
                            :auth :oauth
                            :oauth {:grant :jwt-bearer
                                    :private-key-file key-file
                                    :issuer "kmet-test"
                                    :audience (str "http://127.0.0.1:" port "/token")}}
                interaction {:signal (atom false) :has-ui false
                             :notify (fn [_]) :prompt (fn [_] nil)
                             :open-url (fn [_])}
                status (auth/run-flow! "jwt-server" definition interaction)]
            (testing "the signed assertion is exchanged for a token"
              (is (= :logged-in status))
              (is (str/starts-with?
                   (get ((:auth-headers (auth/make-auth-fns "jwt-server" definition)))
                        "Authorization")
                   "Bearer access-jwt-")))
            (testing "status reports the grant"
              (is (= :jwt-bearer (auth/auth-status "jwt-server" definition)))))
          (finally (tu/delete-tree! dir)))))))

;; ─── Discovery + PKCE requirement ─────────────────────────────────────────

(deftest ^:slow discovery-and-pkce-requirement
  (with-oauth [server _store]
    (let [port (:port server)
          issuer (str "http://127.0.0.1:" port)]
      (testing "RFC 9728: the protected-resource document names the AS and scopes"
        (let [prm (oauth-lib/protected-resource-metadata (str issuer "/mcp") {})]
          (is (= [issuer] (:authorization_servers prm)))
          (is (= ["read" "write"] (:scopes_supported prm)))))
      (testing "a server with no configured scopes uses the resource metadata"
        (let [events (atom [])
              definition {:url (str issuer "/mcp") :auth :oauth
                          :oauth {:flow :device}}]
          (auth/run-flow! "scoped-server" definition (interaction events issuer))
          (is (= "read write" (:scope (last-token-request port))))
          (auth/logout! "scoped-server")))
      (testing "an AS that does not advertise PKCE is refused"
        (let [definition {:url (str issuer "/mcp")
                          :auth :oauth
                          :oauth {:flow :pkce
                                  :authorization-server-url
                                  (str issuer "/no-pkce-metadata")}}]
          (is (try (auth/run-flow! "no-pkce" definition
                                   (interaction (atom []) issuer))
                   false
                   (catch Exception e (= :oauth-no-pkce (:type (ex-data e))))))))
      (testing "skip-pkce-verification overrides the refusal"
        (let [definition {:url (str issuer "/mcp")
                          :auth :oauth
                          :oauth {:flow :pkce
                                  :skip-pkce-verification true
                                  :authorization-server-url
                                  (str issuer "/no-pkce-metadata")}}]
          (is (= :logged-in (auth/run-flow! "no-pkce-override" definition
                                            (interaction (atom []) issuer))))
          (auth/logout! "no-pkce-override")))
      (testing "the device flow is not refused for missing PKCE metadata"
        (let [definition {:url (str issuer "/mcp")
                          :auth :oauth
                          :oauth {:flow :device
                                  :authorization-server-url
                                  (str issuer "/no-pkce-metadata")}}]
          (is (try (auth/run-flow! "no-pkce-device" definition
                                   (interaction (atom []) issuer))
                   false
                   (catch Exception e (not= :oauth-no-pkce (:type (ex-data e)))))))))))

;; ─── SEP-2352 issuer binding ──────────────────────────────────────────────

(deftest ^:slow sep-2352-issuer-binding
  (with-oauth [server store]
    (let [issuer (str "http://127.0.0.1:" (:port server))
          definition {:url (str issuer "/mcp")
                      :auth :oauth
                      :oauth {:flow :pkce :client-id "pre-registered"}}]
      (auth/run-flow! "pkce-server" definition (interaction (atom []) issuer))
      (testing "credentials are not reused after the AS changes"
        (is (try
              (with-redefs [mcp-auth/discover-meta
                            (fn [_name _definition]
                              {:issuer "https://other.example"
                               :authorization_endpoint "https://other.example/authorize"
                               :token_endpoint "https://other.example/token"
                               :code_challenge_methods_supported ["S256"]})]
                (auth/run-flow! "pkce-server" definition
                                (interaction (atom []) issuer)))
              false
              (catch Exception e (= :oauth-issuer-mismatch (:type (ex-data e)))))))
      (testing "the store file is issuer-keyed (v2) with a server binding"
        (let [raw (edn/read-string (slurp store))]
          (is (= 2 (:version raw)))
          (is (seq (:issuers raw)))
          (is (seq (:servers raw)))
          (is (every? :issuer (vals (:servers raw))))))
      (auth/logout! "pkce-server"))))
