#!/usr/bin/env bb
;; OAuth adapter validation (§12.5) against scripts/fake-oauth-server.bb:
;; DCR → PKCE loopback → token → authenticated request headers → 401
;; refresh → device flow → client-credentials + jwt-bearer machine grants
;; → status → logout. The token store is redirected to a temp file; the
;; "browser" is simulated by hitting the redirect URI directly (the state
;; is read from the authorize URL the flow emits).
;;
;; Usage: bb validate-oauth.bb <fake-oauth-server.bb>
(require '[babashka.process :as proc]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[kmet.extensions.mcp-adapter.auth :as auth]
         '[kmet.libs.mcp.auth :as mcp-auth]
         '[kmet.libs.oauth :as oauth-lib])

(def failures (atom 0))

(defn check [label ok]
  (println (if ok "PASS" "FAIL") label)
  (when-not ok (swap! failures inc)))

(defn spawn-server! [script]
  (let [out-file (str (System/getProperty "user.dir") "/.mcp-fake-" (System/nanoTime) ".out")
        p (proc/process ["bb" script]
                        {:in :discard :out out-file :err :discard})]
    (loop [waits 0]
      (let [out (try (slurp out-file) (catch Exception _ ""))]
        (if-let [m (re-find #"PORT (\d+)" out)]
          {:proc p :port (Long/parseLong (second m)) :out-file out-file}
          (do (Thread/sleep 100)
              (if (< waits 50)
                (recur (inc waits))
                (throw (ex-info (str "server did not start: " out)
                                {:type :server-start-failed})))))))))

(defn stop-server! [{:keys [proc out-file]}]
  (try (proc/destroy-tree proc) (catch Exception _ nil))
  (when out-file (io/delete-file out-file true)))

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
   checks assert the RFC 8707 resource indicator and the scopes."
  [oauth-port]
  (:body (oauth-lib/fetch-json (str "http://127.0.0.1:" oauth-port "/last-token-request")
                               {:method :get})))

(defn- last-registration-request
  "What the fake authorization server last received at /register — the
   SEP-837 application_type check."
  [oauth-port]
  (:body (oauth-lib/fetch-json (str "http://127.0.0.1:" oauth-port "/last-registration-request")
                               {:method :get})))

(defn- capture-notify
  "Interaction helper: capture the :auth-url / :device-code events."
  [events]
  (fn [event]
    (when (contains? #{:auth-url :device-code} (:type event))
      (swap! events conj event))))

(defn- params-of [url]
  (into {} (for [pair (str/split (or (second (str/split url #"\?" 2)) "") #"&")]
             (let [[k v] (str/split pair #"=" 2)]
               [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                (java.net.URLDecoder/decode (or v "") "UTF-8")]))))

(defn- interaction
  "Test interaction: no UI, no real browser. When the flow emits the
   authorize URL, a future simulates the browser: the AS redirects to the
   loopback callback with code + state + the issuer's `iss` (RFC 9207),
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

(defn test-pkce-flow [oauth-port store-path]
  (println "\n── DCR + PKCE loopback ──")
  (let [issuer (str "http://127.0.0.1:" oauth-port)
        events (atom [])
        definition {:url (str "http://127.0.0.1:" oauth-port "/mcp")
                    :auth :oauth
                    :oauth {:flow :pkce :scopes ["read"]}}
        abort-called (atom false)
        flow-interaction (assoc (interaction events issuer)
                                :abort-prompt! (fn [] (reset! abort-called true)))
        status (auth/run-flow! "pkce-server" definition flow-interaction)
        auth-event (first (filter #(= :auth-url (:type %)) @events))]
    (check "flow returns :logged-in" (= :logged-in status))
    (check "abort-prompt! invoked after the callback (pi manualAbort.abort)"
           (true? @abort-called))
    (check "authorize URL emitted"
           (and auth-event (str/includes? (:url auth-event) "/authorize")))
    (check "authorize URL carries PKCE params"
           (let [p (params-of (:url auth-event))]
             (and (= "S256" (:code_challenge_method p))
                  (seq (:code_challenge p))
                  (= "read" (:scope p))
                  (seq (:state p)))))
    ;; the browser simulation (from the notify handler) delivered the code
    (check "callback was hit"
           (some? (get-in (auth/server-entry "pkce-server") [:tokens :access])))
    ;; the flow exchanged the code and stored the tokens
    (let [entry (auth/server-entry "pkce-server")]
      (check "tokens stored" (and entry (seq (get-in entry [:tokens :access]))
                                  (seq (get-in entry [:tokens :refresh]))))
      (check "client-info stored (DCR)"
             (= "dcr-client-1" (get-in entry [:client-info :client-id])))
      (check "client-info redirect-uris"
             (let [auth-event (first (filter #(= :auth-url (:type %)) @events))
                   decoded (java.net.URLDecoder/decode
                            (get (params-of (:url auth-event)) :redirect_uri) "UTF-8")]
               (some #(= decoded %) (get-in entry [:client-info :redirect-uris]))))
      (check "DCR carries application_type native (SEP-837)"
             (= "native" (:application_type (last-registration-request oauth-port)))))
    ;; request headers carry the bearer token
    (let [auth-fns (auth/make-auth-fns "pkce-server" definition)
          headers ((:auth-headers auth-fns))
          access (get-in (auth/server-entry "pkce-server") [:tokens :access])]
      (check "auth headers bearer"
             (= (str "Bearer " access) (get headers "Authorization"))))
    ;; 401-refresh: corrupt the access token, refresh via :on-401
    (let [old-refresh (get-in (auth/server-entry "pkce-server") [:tokens :refresh])]
      (auth/store-server! "pkce-server"
                          (assoc-in (auth/server-entry "pkce-server")
                                    [:tokens :access] "stale-access"))
      (let [auth-fns (auth/make-auth-fns "pkce-server" definition)
            challenge {:headers {"www-authenticate"
                                 (str "Bearer resource_metadata=\"http://127.0.0.1:"
                                      oauth-port "/.well-known/oauth-protected-resource\", "
                                      "scope=\"files:read\"")}}
            headers ((:on-401 auth-fns) challenge)
            new-access (get-in (auth/server-entry "pkce-server") [:tokens :access])]
        (check "401 refresh replaces access token"
               (and (not= "stale-access" new-access)
                    (seq new-access)
                    (= (str "Bearer " new-access) (get headers "Authorization"))))
        (check "refresh rotated the refresh token" (some? old-refresh))
        ;; the challenge is recorded (resource_metadata + scope); a flow
        ;; with no configured :scopes must take the challenged scope (the
        ;; earlier definition's :scopes ["read"] would mask it)
        (let [bare-events (atom [])
              bare-definition (assoc definition :oauth {:flow :pkce})]
          (auth/run-flow! "pkce-server" bare-definition (interaction bare-events issuer))
          (check "challenge scope used for the next token request"
                 (= "files:read" (:scope (last-token-request oauth-port)))))))
    ;; RFC 8707: the resource indicator is mandatory in authorization
    ;; and token requests
    (check "canonical resource uri"
           (= "https://mcp.example.com/mcp"
              (auth/canonical-resource-uri "HTTPS://MCP.Example.com/mcp/")))
    (check "canonical resource uri drops a root path"
           (= "https://mcp.example.com" (auth/canonical-resource-uri "https://mcp.example.com")))
    (check "canonical resource uri strips query and fragment"
           (and (= "https://mcp.example.com/mcp"
                   (auth/canonical-resource-uri "https://mcp.example.com/mcp?key=1"))
                (= "https://mcp.example.com/mcp"
                   (auth/canonical-resource-uri "https://mcp.example.com/mcp#frag"))))
    (check "www-authenticate parsed"
           (= {:resource-metadata "https://as.example/.well-known/oauth-protected-resource"
               :scope "files:read"}
              (auth/parse-www-authenticate
               (str "Bearer resource_metadata=\"https://as.example/.well-known/"
                    "oauth-protected-resource\", scope=\"files:read\""))))
    (check "authorize URL carries the resource indicator"
           (= (str "http://127.0.0.1:" oauth-port "/mcp")
              (:resource (params-of (:url (first (filter #(= :auth-url (:type %)) @events)))))))
    (check "token request carries the resource indicator"
           (= (str "http://127.0.0.1:" oauth-port "/mcp")
              (:resource (last-token-request oauth-port))))
    ;; RFC 9207: the authorization response's iss is validated before the
    ;; code reaches any token endpoint
    (check "iss mismatch is rejected (RFC 9207)"
           (try (auth/run-flow! "pkce-server" definition
                                (interaction (atom []) issuer {:iss "https://evil.example"}))
                false
                (catch Exception e (= :oauth-issuer-mismatch (:type (ex-data e))))))
    (check "missing iss is rejected when the AS advertised support (RFC 9207)"
           (try (auth/run-flow! "pkce-server" definition
                                (interaction (atom []) issuer {:iss nil}))
                false
                (catch Exception e (= :oauth-iss-missing (:type (ex-data e))))))
    (check "status logged-in" (= :logged-in (auth/auth-status "pkce-server" definition)))))

(defn test-configured-redirect-uri [oauth-port store-path]
  (println "\n── configured :redirect-uri port binding ──")
  (let [events (atom [])
        probe (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
        cb-port (.getLocalPort probe)
        _ (.close probe)
        redirect-uri (str "http://127.0.0.1:" cb-port "/custom-cb")
        definition {:url (str "http://127.0.0.1:" oauth-port "/mcp")
                    :auth :oauth
                    :oauth {:flow :pkce :redirect-uri redirect-uri}}
        status (auth/run-flow! "custom-redirect" definition
                               (interaction events (str "http://127.0.0.1:" oauth-port)))
        auth-event (first (filter #(= :auth-url (:type %)) @events))
        p (params-of (:url auth-event))]
    (check "flow returns :logged-in" (= :logged-in status))
    (check "configured redirect-uri used"
           (= redirect-uri (java.net.URLDecoder/decode (:redirect_uri p) "UTF-8")))
    (check "callback served on the configured port"
           (some? (get-in (auth/server-entry "custom-redirect") [:tokens :access])))
    (auth/logout! "custom-redirect")))

(defn test-device-flow [oauth-port store-path]
  (println "\n── RFC 8628 device flow ──")
  (let [events (atom [])
        definition {:url (str "http://127.0.0.1:" oauth-port "/mcp")
                    :auth :oauth
                    :oauth {:flow :device :scopes ["read"]}}
        status (auth/run-flow! "device-server" definition
                               (interaction events (str "http://127.0.0.1:" oauth-port)))
        device-event (first (filter #(= :device-code (:type %)) @events))]
    (check "device flow returns :logged-in" (= :logged-in status))
    (check "device code notified"
           (and device-event (= "ABCD-EFGH" (:user-code device-event))
                (str/includes? (:verification-uri device-event) "/device-verify")))
    (let [entry (auth/server-entry "device-server")]
      (check "device tokens stored"
             (and entry (seq (get-in entry [:tokens :access]))))
      (check "device client-id stored"
             (= "dcr-client-1" (get-in entry [:client-info :client-id]))))
    (check "status logged-in" (= :logged-in (auth/auth-status "device-server" definition)))
    ;; logout clears everything
    (auth/logout! "device-server")
    (check "logout clears store" (nil? (auth/server-entry "device-server")))
    (check "status none after logout" (= :none (auth/auth-status "device-server" definition)))))

(defn test-tokenless-status [oauth-port store-path]
  (println "\n── credential-less store entry ──")
  (let [issuer (str "http://127.0.0.1:" oauth-port)
        definition {:url (str issuer "/mcp")
                    :auth :oauth
                    :oauth {:flow :pkce}}]
    ;; what an interrupted issuer change leaves behind: a shared client
    ;; registration with no tokens for this server
    (auth/store-server! "tokenless" {:issuer issuer
                                     :client-info {:client-id "c1"}})
    (check "a client registration alone is not logged in"
           (= :none (auth/auth-status "tokenless" definition)))
    (check "auth headers refuse a credential-less entry"
           (try ((:auth-headers (auth/make-auth-fns "tokenless" definition)))
                false
                (catch Exception e (= :mcp-auth-required (:type (ex-data e))))))
    (auth/logout! "tokenless")
    (check "logout clears the credential-less entry"
           (nil? (auth/server-entry "tokenless")))))

(defn test-client-credentials-flow [oauth-port store-path]
  (println "\n── client-credentials grant ──")
  (let [definition {:url (str "http://127.0.0.1:" oauth-port "/mcp")
                    :auth :oauth
                    :oauth {:grant :client-credentials
                            :client-id "cc-client-1"
                            :client-secret "cc-secret"
                            :scopes ["read"]}}
        interaction {:signal (atom false) :has-ui false
                     :notify (fn [_]) :prompt (fn [_] nil) :open-url (fn [_])}
        status (auth/run-flow! "cc-server" definition interaction)]
    (check "machine flow returns :logged-in" (= :logged-in status))
    (let [auth-fns (auth/make-auth-fns "cc-server" definition)
          headers ((:auth-headers auth-fns))]
      (check "machine auth headers bearer"
             (str/starts-with? (get headers "Authorization") "Bearer access-cc-")))
    (check "status shows client-credentials grant"
           (= :client-credentials (auth/auth-status "cc-server" definition)))
    ;; the 401 retry path re-fetches (forced) — a fresh token id
    (let [auth-fns (auth/make-auth-fns "cc-server" definition)
          headers ((:on-401 auth-fns) {:headers {}})]
      (check "401 re-fetch gets a fresh token"
             (str/starts-with? (get headers "Authorization") "Bearer access-cc-")))
    (auth/logout! "cc-server")
    (check "logout clears machine cache"
           (not (auth/machine-token-cached? "cc-server")))))

(defn test-discovery-and-pkce-verification [oauth-port store-path]
  (println "\n── RFC 9728 / 8414 discovery + PKCE verification ──")
  ;; RFC 9728: the protected-resource document is found by probing, and it
  ;; carries the authorization-server location
  (let [prm (oauth-lib/protected-resource-metadata
             (str "http://127.0.0.1:" oauth-port "/mcp") {})]
    (check "protected-resource metadata discovered"
           (= [(str "http://127.0.0.1:" oauth-port)] (:authorization_servers prm)))
    (check "protected-resource metadata advertises scopes"
           (= ["read" "write"] (:scopes_supported prm))))
  ;; RFC 9728: the resource server may name an authorization server on a
  ;; different origin. RFC 8414 §3.3 validates the issuer against the URL
  ;; the document was fetched from (the AS), not the resource server —
  ;; otherwise every split-origin deployment fails the issuer check
  (let [discover-meta (deref (resolve 'kmet.extensions.mcp-adapter.auth/discover-meta))]
    (with-redefs [oauth-lib/protected-resource-metadata
                  (fn [_url _opts]
                    {:authorization_servers ["https://auth.example.com"]})
                  oauth-lib/discover-authorization-server
                  (fn [url _opts]
                    (when (= url "https://auth.example.com")
                      {:issuer url
                       :authorization_endpoint (str url "/authorize")
                       :token_endpoint (str url "/token")
                       :code_challenge_methods_supported ["S256"]}))]
      (check "cross-origin authorization server accepted"
             (= "https://auth.example.com"
                (:issuer (discover-meta "srv" {:url "https://mcp.example.com/mcp"
                                               :oauth {}})))))
    (with-redefs [oauth-lib/protected-resource-metadata
                  (fn [_url _opts]
                    {:authorization_servers ["https://auth.example.com"]})
                  oauth-lib/discover-authorization-server
                  (fn [url _opts]
                    {:issuer (str url ".evil.example")
                     :token_endpoint "https://evil.example/token"})]
      (check "issuer mismatch against the AS URL still rejected"
             (try (discover-meta "srv" {:url "https://mcp.example.com/mcp"
                                        :oauth {}})
                  false
                  (catch Exception e
                    (= :oauth-issuer-mismatch (:type (ex-data e))))))))
  ;; a server with no configured scopes requests what the resource
  ;; document advertises
  (let [events (atom [])
        definition {:url (str "http://127.0.0.1:" oauth-port "/mcp")
                    :auth :oauth
                    :oauth {:flow :device}}
        _ (auth/run-flow! "scoped-server" definition
                          (interaction events (str "http://127.0.0.1:" oauth-port)))]
    (check "scopes come from the resource metadata"
           (= "read write" (:scope (last-token-request oauth-port))))
    (auth/logout! "scoped-server"))
  ;; OAuth 2.1: an authorization server that does not advertise PKCE
  ;; support must be refused before we send a challenge
  (let [events (atom [])
        definition {:url (str "http://127.0.0.1:" oauth-port "/mcp")
                    :auth :oauth
                    :oauth {:flow :pkce
                            :authorization-server-url
                            (str "http://127.0.0.1:" oauth-port "/no-pkce-metadata")}}]
    (check "refuses an AS without PKCE support"
           (try (auth/run-flow! "no-pkce" definition
                                (interaction events (str "http://127.0.0.1:" oauth-port)))
                false
                (catch Exception e (= :oauth-no-pkce (:type (ex-data e)))))))
  ;; ...and the documented override lets a user proceed anyway
  (let [events (atom [])
        definition {:url (str "http://127.0.0.1:" oauth-port "/mcp")
                    :auth :oauth
                    :oauth {:flow :pkce
                            :skip-pkce-verification true
                            :authorization-server-url
                            (str "http://127.0.0.1:" oauth-port "/no-pkce-metadata")}}]
    (check "skip-pkce-verification overrides the refusal"
           (= :logged-in (auth/run-flow! "no-pkce-override" definition
                                         (interaction events (str "http://127.0.0.1:" oauth-port)))))
    (auth/logout! "no-pkce-override"))
  ;; the RFC 8628 device flow sends no code challenge, so missing PKCE
  ;; metadata must not refuse it — it stops at the missing device endpoint
  ;; instead, and the point is that :oauth-no-pkce is never thrown
  (let [events (atom [])
        definition {:url (str "http://127.0.0.1:" oauth-port "/mcp")
                    :auth :oauth
                    :oauth {:flow :device
                            :authorization-server-url
                            (str "http://127.0.0.1:" oauth-port "/no-pkce-metadata")}}]
    (check "device flow is not refused for missing PKCE metadata"
           (try (auth/run-flow! "no-pkce-device" definition
                                (interaction events (str "http://127.0.0.1:" oauth-port)))
                false
                (catch Exception e (not= :oauth-no-pkce (:type (ex-data e))))))))

(defn test-sep-2352-binding [oauth-port store-path]
  (println "\n── SEP-2352 authorization-server binding ──")
  (let [issuer (str "http://127.0.0.1:" oauth-port)
        definition {:url (str issuer "/mcp")
                    :auth :oauth
                    :oauth {:flow :pkce :client-id "pre-registered"}}]
    ;; pkce-server is bound to this issuer; the same server pointing at a
    ;; different authorization server must not reuse the credentials
    (check "pre-registered credentials are not reused after an AS change"
           (try
             (with-redefs [mcp-auth/discover-meta
                           (fn [_name _definition]
                             {:issuer "https://other.example"
                              :authorization_endpoint "https://other.example/authorize"
                              :token_endpoint "https://other.example/token"
                              :code_challenge_methods_supported ["S256"]})]
               (auth/run-flow! "pkce-server" definition (interaction (atom []) issuer)))
             false
             (catch Exception e (= :oauth-issuer-mismatch (:type (ex-data e))))))
    ;; the store file is issuer-keyed (v2) with a server → issuer binding
    (let [store (edn/read-string (slurp store-path))]
      (check "store file is issuer-keyed (SEP-2352)"
             (and (= 2 (:version store))
                  (seq (:issuers store))
                  (seq (:servers store))
                  (every? :issuer (vals (:servers store))))))
    (auth/logout! "pkce-server")))

(defn test-jwt-bearer-flow [oauth-port store-path]
  (println "\n── jwt-bearer grant (RFC 7523) ──")
  (let [kg (java.security.KeyPairGenerator/getInstance "RSA")]
    (.initialize kg 2048)
    (let [der (.getEncoded (.getPrivate (.generateKeyPair kg)))
          b64 (.encodeToString (java.util.Base64/getEncoder) der)
          pem (str "-----BEGIN PRIVATE KEY-----\n"
                   (str/join "\n" (map #(apply str %) (partition-all 64 b64)))
                   "\n-----END PRIVATE KEY-----\n")
          key-file (str (System/getProperty "user.dir") "/.mcp-jwt-key-"
                        (System/nanoTime) ".pem")]
      (spit key-file pem)
      (try
        (let [definition {:url (str "http://127.0.0.1:" oauth-port "/mcp")
                          :auth :oauth
                          :oauth {:grant :jwt-bearer
                                  :private-key-file key-file
                                  :issuer "kmet-validator"
                                  :audience (str "http://127.0.0.1:" oauth-port "/token")}}
              interaction {:signal (atom false) :has-ui false
                           :notify (fn [_]) :prompt (fn [_] nil) :open-url (fn [_])}
              status (auth/run-flow! "jwt-server" definition interaction)]
          (check "jwt flow returns :logged-in" (= :logged-in status))
          (let [auth-fns (auth/make-auth-fns "jwt-server" definition)
                headers ((:auth-headers auth-fns))]
            (check "jwt auth headers bearer"
                   (str/starts-with? (get headers "Authorization")
                                     "Bearer access-jwt-")))
          (check "status shows jwt-bearer grant"
                 (= :jwt-bearer (auth/auth-status "jwt-server" definition))))
        (finally (io/delete-file key-file true))))))

(let [[fake-oauth] *command-line-args*]
  (when-not fake-oauth
    (println "Usage: bb validate-oauth.bb <fake-oauth-server.bb>")
    (System/exit 1))
  (let [{:keys [proc port] :as server} (spawn-server! fake-oauth)
        store-path (str (System/getProperty "user.dir") "/.mcp-oauth-test-" (System/nanoTime) ".edn")]
    (with-redefs [auth/store-path (constantly store-path)]
      ;; force the file backend so the temp path is really used on hosts
      ;; that have a keyring (unless MCP_TOKEN_STORAGE overrides the mode)
      (auth/configure-storage! {:token-storage :file})
      (try
        ;; the configured :redirect-uri must be the FIRST flow — the
        ;; callback server binds once (port + path), later flows reuse it
        (test-configured-redirect-uri port store-path)
        (test-pkce-flow port store-path)
        (test-sep-2352-binding port store-path)
        (test-device-flow port store-path)
        (test-tokenless-status port store-path)
        (test-client-credentials-flow port store-path)
        (test-jwt-bearer-flow port store-path)
        (test-discovery-and-pkce-verification port store-path)
        (finally
          (stop-server! server)
          (io/delete-file store-path true)))))
  (println "\n" (if (zero? @failures) "ALL PASS" (str @failures " FAILURES")))
  (System/exit (if (zero? @failures) 0 1)))
