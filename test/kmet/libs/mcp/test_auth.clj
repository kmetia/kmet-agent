(ns kmet.libs.mcp.test-auth
  "Tests for the MCP authorization policy in kmet.libs.mcp.auth —
   resource canonicalization, the WWW-Authenticate challenge record,
   scope/resource selection, the credential store (issuer-keyed, with the
   pre-SEP-2352 migration), the token lifecycle (bearer/machine
   grants/refresh plus make-auth-fns), and the 2026 hardening: issuer
   binding (SEP-2352), the SEP-837 application_type, and the RFC 9207
   authorization-response `iss` validation. Discovery and the interactive
   flows keep their socket-level coverage in the extension's
   validate-oauth.bb."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.mcp.auth :as auth]
            [kmet.libs.oauth :as oauth]))

(defn- temp-dir []
  (let [dir (str (fs/absolutize (str "target/test-mcp-auth-" (System/nanoTime))))]
    (fs/create-dirs dir)
    dir))

(defn- reset-storage! []
  (auth/configure-storage! {:mode :auto :path nil}))

(deftest file-store-round-trip
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (testing "an empty (or missing) store has no entry"
        (is (nil? (auth/server-entry "srv"))))
      (testing "store-server! persists and server-entry reads back"
        (auth/store-server! "srv" {:tokens {:access "a" :refresh "r" :expires 42}
                                   :client-info {:client-id "c"}})
        (is (= {:tokens {:access "a" :refresh "r" :expires 42}
                :client-info {:client-id "c"}}
               (auth/server-entry "srv")))
        (testing "the file is EDN on disk, per server"
          (is (fs/exists? path))
          (is (= "a" (get-in (edn/read-string (slurp path))
                             [:servers "srv" :tokens :access])))))
      (testing "entries are independent"
        (auth/store-server! "other" {:tokens {:access "b"}})
        (is (= "a" (get-in (auth/server-entry "srv") [:tokens :access])))
        (is (= "b" (get-in (auth/server-entry "other") [:tokens :access]))))
      (testing "logout! forgets the entry and its recorded challenge"
        (auth/record-challenge! "srv" "Bearer scope=\"x\"")
        (auth/logout! "srv")
        (is (nil? (auth/server-entry "srv")))
        (is (nil? (auth/challenge "srv")))
        (is (some? (auth/server-entry "other"))))
      (finally
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest file-store-survives-unreadable-content
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (spit path "not edn {")
      (is (nil? (auth/server-entry "srv")))
      (auth/store-server! "srv" {:tokens {:access "a"}})
      (is (= "a" (get-in (auth/server-entry "srv") [:tokens :access])))
      (finally
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest file-store-without-a-path-throws
  (doseq [path [nil "" "   "]]
    (testing (str "unconfigured path " (pr-str path))
      (try
        (auth/configure-storage! {:mode :file :path path})
        (testing "reads degrade to an empty store with no path"
          (is (nil? (auth/server-entry "srv"))))
        (testing "writes fail with the structured unconfigured error"
          (is (= :mcp-store-unconfigured
                 (try
                   (auth/store-server! "srv" {:tokens {}})
                   (catch Exception e (:type (ex-data e)))))))
        (testing "logout of a server with nothing stored is a no-op"
          (is (nil? (auth/logout! "srv")))
          (is (nil? (auth/server-entry "srv"))))
        (finally (reset-storage!))))))

(deftest file-store-with-a-bare-filename
  ;; no parent directory to create — the lib accepts any path, the
  ;; extension just happens to pass <agent-dir>/mcp-oauth.edn
  (let [path (str ".mcp-auth-parentless-" (System/nanoTime) ".edn")]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (auth/store-server! "srv" {:tokens {:access "a"}})
      (is (= "a" (get-in (auth/server-entry "srv") [:tokens :access])))
      (is (fs/exists? path))
      (finally
        (reset-storage!)
        (fs/delete-if-exists path)))))

(deftest file-store-writes-are-serialized
  ;; concurrent login flows must not lose each other's entries: the
  ;; read-modify-write is locked, so every server survives
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))
        n 12]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (->> (range n)
           (map (fn [i]
                  (future (auth/store-server! (str "srv-" i)
                                              {:tokens {:access (str "a" i)}}))))
           (doall)
           (run! deref))
      (is (= n (count (:servers (edn/read-string (slurp path))))))
      (doseq [i (range n)]
        (is (= (str "a" i)
               (get-in (auth/server-entry (str "srv-" i)) [:tokens :access]))))
      (finally
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest storage-mode-selection
  (try
    (testing "an explicit :file mode always reports :file"
      (auth/configure-storage! {:mode :file :path nil})
      (is (= :file (auth/storage-kind))))
    (testing "an unknown mode falls back to :auto"
      (auth/configure-storage! {:mode :bogus})
      (is (= (if (auth/keyring-available?) :keyring :file) (auth/storage-kind))))
    (testing ":keyring without a platform tool degrades to :file"
      (when-not (auth/keyring-available?)
        (auth/configure-storage! {:mode :keyring :path nil})
        (is (= :file (auth/storage-kind)))))
    (finally (reset-storage!))))

(deftest store-tokens-normalizes-the-token-response
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (auth/store-server! "srv" {:client-info {:client-id "c"}})
      (let [before (System/currentTimeMillis)]
        (auth/store-tokens! "srv" {:access "a" :refresh "r" :expires-in 3600 :scope "read"})
        (let [entry (auth/server-entry "srv")]
          (testing "the oauth response shape is normalized into the store shape"
            (is (= "a" (get-in entry [:tokens :access])))
            (is (= "r" (get-in entry [:tokens :refresh])))
            (is (= "read" (get-in entry [:tokens :scope])))
            (is (<= (+ before 3600000) (get-in entry [:tokens :expires])
                    (+ (System/currentTimeMillis) 3600000))))
          (testing "the rest of the entry (client info) survives"
            (is (= {:client-id "c"} (:client-info entry))))))
      (testing "a response without refresh/scope leaves those keys absent"
        (auth/store-tokens! "bare" {:access "b" :expires-in 10})
        (is (nil? (get-in (auth/server-entry "bare") [:tokens :refresh])))
        (is (nil? (get-in (auth/server-entry "bare") [:tokens :scope]))))
      (testing "a response without :expires-in gets no expiry (used until a 401)"
        (auth/store-tokens! "noexp" {:access "n"})
        (is (= "n" (get-in (auth/server-entry "noexp") [:tokens :access])))
        (is (nil? (get-in (auth/server-entry "noexp") [:tokens :expires])))
        (is (not (auth/token-expired? (auth/server-entry "noexp")))))
      (finally
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest token-expiry-has-a-sixty-second-skew
  (let [now (System/currentTimeMillis)]
    (is (auth/token-expired? {:tokens {:expires (- now 1)}}))
    (is (auth/token-expired? {:tokens {:expires (+ now 30000)}})
        "inside the skew window counts as expired")
    (is (not (auth/token-expired? {:tokens {:expires (+ now 120000)}})))
    (is (not (auth/token-expired? {:tokens {:access "a"}}))
        "an entry without an expiry is not expired")))

(deftest bearer-and-grant-selection
  (is (= "t" (auth/bearer-token {:bearer-token "t"})))
  (is (nil? (auth/bearer-token {})))
  (is (= :authorization-code (auth/grant-of {})))
  (is (= :client-credentials (auth/grant-of {:oauth {:grant :client-credentials}})))
  (is (auth/machine-grant? {:oauth {:grant :jwt-bearer}}))
  (is (not (auth/machine-grant? {:oauth {:grant :authorization-code}})))
  (is (not (auth/machine-grant? {})))
  (is (not (auth/machine-token-cached? "nope"))))

(deftest discover-meta-prefers-the-resource-document
  (let [prm {:authorization_servers ["https://as.example.com"]}]
    (testing "the RFC 9728 document names the AS and rides along under ::prm"
      (with-redefs [oauth/protected-resource-metadata (fn [_url _opts] prm)
                    oauth/discover-authorization-server
                    (fn [url _opts]
                      (when (= url "https://as.example.com")
                        {:issuer url
                         :token_endpoint (str url "/token")
                         :authorization_endpoint (str url "/authorize")}))]
        (let [m (auth/discover-meta "srv" {:url "https://mcp.example.com/mcp" :oauth {}})]
          (is (= "https://as.example.com" (:issuer m)))
          (is (= "https://as.example.com" (::auth/issuer m)))
          (is (= "https://as.example.com/token" (:token_endpoint m)))
          (is (= prm (::auth/prm m))))))
    (testing "an issuer that does not match the document URL is rejected"
      (with-redefs [oauth/protected-resource-metadata (fn [_url _opts] prm)
                    oauth/discover-authorization-server
                    (fn [url _opts]
                      {:issuer (str url ".evil.example")
                       :token_endpoint "https://evil.example/token"})]
        (is (= :oauth-issuer-mismatch
               (try (auth/discover-meta "srv" {:url "https://mcp.example.com/mcp" :oauth {}})
                    nil
                    (catch Exception e (:type (ex-data e))))))
        (testing ":skip-issuer-metadata-validation accepts it"
          (is (some? (auth/discover-meta
                      "srv" {:url "https://mcp.example.com/mcp"
                             :oauth {:skip-issuer-metadata-validation true}}))))))))

(deftest discover-meta-token-endpoint-short-circuits
  (with-redefs [oauth/protected-resource-metadata
                (fn [_url _opts] (throw (ex-info "discovery should not run" {})))
                oauth/discover-authorization-server
                (fn [_url _opts] (throw (ex-info "discovery should not run" {})))]
    (let [m (auth/discover-meta "srv" {:url "https://mcp.example.com/mcp"
                                       :oauth {:token-endpoint "https://as.example.com/token"}})]
      (is (= "https://as.example.com/token" (:token_endpoint m)))
      (is (nil? (::auth/prm m)) "no protected-resource document was fetched")
      (is (= "https://mcp.example.com/mcp" (::auth/issuer m))
          "an explicit token endpoint has no issuer — the server url is the fallback"))))

(deftest required-endpoint-reports-the-missing-key
  (is (= "t" (auth/required-endpoint {:token_endpoint "t"} :token_endpoint "srv")))
  (is (= :oauth-no-endpoint
         (try (auth/required-endpoint {} :token_endpoint "srv")
              nil
              (catch Exception e (:type (ex-data e)))))))

(deftest make-auth-fns-per-auth-kind
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))
        oauth-definition {:auth :oauth :url "https://mcp.example.com/mcp"}]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (testing "no auth and no headers → no auth fns"
        (is (nil? (auth/make-auth-fns "srv" {}))))
      (testing "static config headers alone still go out"
        (let [fns (auth/make-auth-fns "srv" {:headers {"X-Api" "1"}})]
          (is (= {"X-Api" "1"} ((:auth-headers fns))))
          (is (nil? (:on-401 fns)))))
      (testing ":bearer with config headers"
        (let [fns (auth/make-auth-fns "srv" (assoc oauth-definition
                                                   :auth :bearer :bearer-token "t"
                                                   :headers {"X-Api" "1"}))]
          (is (= {"X-Api" "1" "Authorization" "Bearer t"} ((:auth-headers fns))))))
      (testing ":bearer without a token fails instead of sending an empty header"
        (let [fns (auth/make-auth-fns "srv" {:auth :bearer})]
          (is (= :mcp-auth-required
                 (try ((:auth-headers fns)) nil
                      (catch Exception e (:type (ex-data e))))))))
      (testing ":oauth without a stored entry → auth required"
        (let [fns (auth/make-auth-fns "srv" oauth-definition)]
          (is (= :mcp-auth-required
                 (try ((:auth-headers fns)) nil
                      (catch Exception e (:type (ex-data e))))))))
      (testing ":oauth with a fresh stored token uses it and merges headers"
        (auth/store-tokens! "srv" {:access "a" :expires-in 3600})
        (let [fns (auth/make-auth-fns "srv" (assoc oauth-definition :headers {"X-Api" "1"}))]
          (is (= {"X-Api" "1" "Authorization" "Bearer a"} ((:auth-headers fns))))))
      (testing ":oauth with an expired token and no refresh token → auth required"
        (auth/store-tokens! "srv" {:access "old" :expires-in -1})
        (let [fns (auth/make-auth-fns "srv" oauth-definition)]
          (is (= :mcp-auth-required
                 (try ((:auth-headers fns)) nil
                      (catch Exception e (:type (ex-data e))))))))
      (testing "the 401 hook records the challenge before failing without a refresh"
        (let [fns (auth/make-auth-fns "srv" oauth-definition)
              err (try ((:on-401 fns)
                        {:headers {"WWW-Authenticate"
                                   "Bearer scope=\"s\", resource_metadata=\"https://prm\""}})
                       nil
                       (catch Exception e (ex-data e)))]
          (is (= :mcp-auth-required (:type err)))
          (is (= {:scope "s" :resource-metadata "https://prm"} (auth/challenge "srv")))))
      (testing "machine grants ask the fetcher for a token"
        (with-redefs [auth/fetch-machine-token!
                      (fn [name _] {:access (str "m-" name)
                                    :expires (+ (System/currentTimeMillis) 3600000)})]
          (let [fns (auth/make-auth-fns "g" {:auth :oauth :url "https://mcp.example.com/mcp"
                                             :oauth {:grant :client-credentials}})]
            (is (= "Bearer m-g" (get ((:auth-headers fns)) "Authorization"))))))
      (finally
        (auth/logout! "srv")
        (auth/logout! "g")
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest machine-tokens-are-cached-per-connection-fingerprint
  ;; the real fetch-machine-token! path with only the network leaves
  ;; redefined: discovery off the server URL, tokens from a stub
  (let [calls (atom 0)
        definition {:auth :oauth
                    :url "https://a.example.com/mcp"
                    :oauth {:grant :client-credentials
                            :client-id "c" :client-secret "s"}}
        other (assoc definition :url "https://b.example.com/mcp")]
    (try
      (with-redefs [oauth/protected-resource-metadata (fn [_url _opts] nil)
                    oauth/discover-authorization-server
                    (fn [url _opts] {:token_endpoint (str url "/token")})
                    oauth/client-credentials-token
                    (fn [_endpoint _opts]
                      (swap! calls inc)
                      {:access (str "token-" @calls) :expires-in 3600})]
        (let [headers (fn [d] ((:auth-headers (auth/make-auth-fns "fp" d))))]
          (is (= "Bearer token-1" (get (headers definition) "Authorization")))
          (is (auth/machine-token-cached? "fp"))
          (testing "the same connection reuses the cached token"
            (is (= "Bearer token-1" (get (headers definition) "Authorization")))
            (is (= 1 @calls)))
          (testing "a changed :url re-fetches instead of reusing the old token"
            (is (= "Bearer token-2" (get (headers other) "Authorization")))
            (is (= 2 @calls)))
          (testing "the original connection's token is still cached under its own key"
            (is (= "Bearer token-1" (get (headers definition) "Authorization")))
            (is (= 2 @calls)))))
      (finally
        (auth/clear-machine-tokens! "fp")
        (auth/clear-challenges! "fp")))))

(deftest canonical-resource-uri-normalizes
  (testing "scheme and host are lowercased, path case is kept"
    (is (= "https://mcp.example.com/mcp"
           (auth/canonical-resource-uri "HTTPS://MCP.Example.com/mcp/")))
    (is (= "https://mcp.example.com:8443/MCP/V1"
           (auth/canonical-resource-uri "https://mcp.example.com:8443/MCP/V1///"))))
  (testing "query and fragment are dropped"
    (is (= "https://mcp.example.com/mcp"
           (auth/canonical-resource-uri "https://mcp.example.com/mcp?key=1")))
    (is (= "https://mcp.example.com/mcp"
           (auth/canonical-resource-uri "https://mcp.example.com/mcp#frag")))
    (is (= "https://mcp.example.com"
           (auth/canonical-resource-uri "https://mcp.example.com?q=1"))))
  (testing "a root path loses its trailing slash, an absent path stays absent"
    (is (= "https://mcp.example.com" (auth/canonical-resource-uri "https://mcp.example.com")))
    (is (= "https://mcp.example.com" (auth/canonical-resource-uri "https://mcp.example.com/")))
    (is (= "http://mcp.example.com" (auth/canonical-resource-uri "http://mcp.example.com///"))))
  (testing "only http(s) urls are resource indicators"
    (is (nil? (auth/canonical-resource-uri "ftp://mcp.example.com")))
    (is (nil? (auth/canonical-resource-uri "mcp.example.com/mcp")))
    (is (nil? (auth/canonical-resource-uri nil)))
    (is (nil? (auth/canonical-resource-uri 42)))))

(deftest parse-www-authenticate-reads-bearer-params
  (is (= {:resource-metadata "https://as.example.com/.well-known/oauth-protected-resource"
          :scope "read write"
          :error "insufficient_scope"}
         (auth/parse-www-authenticate
          (str "Bearer resource_metadata=\"https://as.example.com/.well-known/oauth-protected-resource\""
               ", scope=\"read write\", error=\"insufficient_scope\""))))
  (testing "param names are case-insensitive and land hyphenated"
    (is (= {:resource-metadata "https://prm"}
           (auth/parse-www-authenticate "Bearer RESOURCE_METADATA=\"https://prm\""))))
  (testing "a non-Bearer or absent header is nil"
    (is (nil? (auth/parse-www-authenticate "Basic realm=\"x\"")))
    (is (nil? (auth/parse-www-authenticate nil)))
    (is (nil? (auth/parse-www-authenticate ""))))
  (testing "unquoted params, and a Bearer challenge without any, are nil"
    (is (nil? (auth/parse-www-authenticate "Bearer realm=example")))
    (is (nil? (auth/parse-www-authenticate "Bearer"))))
  (testing "whitespace around the scheme and a leading param is tolerated"
    (is (= {:scope "a"} (auth/parse-www-authenticate "  Bearer   scope=\"a\""))))
  (testing "OWS around the auth-param separators is tolerated (RFC 9110)"
    (is (= {:scope "a" :error "b"}
           (auth/parse-www-authenticate "Bearer scope=\"a\" , error=\"b\""))))
  (testing "a comma inside a quoted value does not split the parameter"
    (is (= {:error "invalid_token"
            :error-description "the token expired, please retry"
            :scope "read"}
           (auth/parse-www-authenticate
            (str "Bearer error=\"invalid_token\", "
                 "error_description=\"the token expired, please retry\", scope=\"read\"")))))
  (testing "a quoted-pair is unescaped"
    (is (= {:error-description "say \"hi\""}
           (auth/parse-www-authenticate
            "Bearer error_description=\"say \\\"hi\\\"\"")))))

(deftest challenge-record-and-selection
  (let [s "test-auth-server-1"
        other "test-auth-server-2"]
    (try
      (is (nil? (auth/challenge s)))
      (auth/record-challenge! s "Bearer scope=\"chal\", resource_metadata=\"https://prm\"")
      (is (= {:scope "chal" :resource-metadata "https://prm"} (auth/challenge s)))
      (testing "a non-Bearer 401 does not overwrite the recorded challenge"
        (auth/record-challenge! s "Basic realm=\"x\"")
        (is (= {:scope "chal" :resource-metadata "https://prm"} (auth/challenge s))))
      (testing "scope selection: config wins, then the challenge, then the metadata"
        (is (= "cfg" (auth/effective-scopes s {:oauth {:scopes "cfg"}} nil)))
        (is (= "a b" (auth/effective-scopes s {:oauth {:scopes ["a" "b"]}} nil)))
        (is (= "chal" (auth/effective-scopes s {:oauth {}} nil)))
        (is (= "p q" (auth/effective-scopes other {} {:scopes_supported ["p" "q"]})))
        (is (nil? (auth/effective-scopes other {} {}))))
      (testing "resource selection: config :resource overrides the canonical url"
        (is (= "https://mcp.example.com/mcp"
               (auth/effective-resource {:url "HTTPS://MCP.Example.com/mcp/"})))
        (is (= "https://gateway.example.com"
               (auth/effective-resource {:url "https://mcp.example.com"
                                         :oauth {:resource "https://gateway.example.com"}})))
        (is (nil? (auth/effective-resource {:oauth {}}))))
      (finally
        (auth/clear-challenges! s)
        (auth/clear-challenges! other)))))

(deftest clear-challenges-forgets-everything
  (try
    (auth/record-challenge! "test-auth-a" "Bearer scope=\"a\"")
    (auth/record-challenge! "test-auth-b" "Bearer scope=\"b\"")
    (auth/clear-challenges!)
    (is (nil? (auth/challenge "test-auth-a")))
    (is (nil? (auth/challenge "test-auth-b")))
    (finally
      (auth/clear-challenges!))))

;; ─── 2026 hardening: SEP-2352 / SEP-837 / RFC 9207 ───────────────────────

(deftest issuer-keyed-store-and-binding
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (auth/store-client-info! "srv" "https://as.example" {:client-id "c1"})
      (auth/store-tokens! "srv" "https://as.example" {:access "a" :refresh "r" :expires-in 3600})
      (testing "a bound entry reads back with its issuer"
        (is (= "https://as.example" (auth/server-issuer "srv")))
        (let [entry (auth/server-entry "srv")]
          (is (= "https://as.example" (:issuer entry)))
          (is (= "c1" (get-in entry [:client-info :client-id])))
          (is (= "a" (get-in entry [:tokens :access])))
          (is (= "r" (get-in entry [:tokens :refresh])))))
      (testing "the file is issuer-keyed (v2)"
        (let [store (edn/read-string (slurp path))]
          (is (= 2 (:version store)))
          (is (= "https://as.example" (get-in store [:servers "srv" :issuer])))
          (is (= "c1" (get-in store [:issuers "https://as.example" :client-info :client-id])))
          (is (= "a" (get-in store [:issuers "https://as.example" :tokens "srv" :access])))))
      (testing "another server on the same issuer shares the registration"
        (auth/store-tokens! "other" "https://as.example" {:access "b"})
        (is (= "https://as.example" (auth/server-issuer "other")))
        (is (= "c1" (get-in (auth/server-entry "other") [:client-info :client-id])))
        (is (= "b" (get-in (auth/server-entry "other") [:tokens :access]))))
      (testing "rebinding to a new issuer drops the old server tokens"
        (auth/store-client-info! "srv" "https://new.example" {:client-id "c2"})
        (let [entry (auth/server-entry "srv")]
          (is (= "https://new.example" (:issuer entry)))
          (is (= "c2" (get-in entry [:client-info :client-id])))
          (is (nil? (get-in entry [:tokens])))))
      (testing "logout! keeps a registration another server still uses"
        (auth/store-tokens! "srv" "https://new.example" {:access "n"})
        (auth/logout! "srv")
        (is (nil? (auth/server-entry "srv")))
        (is (= "c1" (get-in (auth/server-entry "other") [:client-info :client-id]))))
      (finally
        (auth/logout! "srv")
        (auth/logout! "other")
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest store-migrates-pre-sep-2352-entries-on-use
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))]
    (try
      (auth/configure-storage! {:mode :file :path path})
      ;; a pre-SEP-2352 file: per server, no issuer stamp
      (spit path (pr-str {:servers {"srv" {:client-info {:client-id "legacy"}
                                           :tokens {:access "old" :refresh "r"}}}}))
      (testing "an unstamped entry still reads back"
        (is (= "legacy" (get-in (auth/server-entry "srv") [:client-info :client-id])))
        (is (nil? (auth/server-issuer "srv"))))
      (testing "the first authenticated use binds it to the resolved issuer"
        (auth/store-tokens! "srv" "https://as.example" {:access "new"})
        (is (= "https://as.example" (auth/server-issuer "srv")))
        (let [entry (auth/server-entry "srv")]
          (is (= "legacy" (get-in entry [:client-info :client-id])))
          (is (= "new" (get-in entry [:tokens :access]))))
        (let [store (edn/read-string (slurp path))]
          (is (= 2 (:version store)))
          (is (nil? (get-in store [:servers "srv" :tokens]))
              "the unstamped per-server entry was claimed into the issuer")))
      (finally
        (auth/logout! "srv")
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest shared-registration-survives-a-second-legacy-migration
  ;; two pre-SEP-2352 servers on one AS: the first migration's client
  ;; registration stays the shared one — a later legacy entry must not
  ;; replace it under the server already using it
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (spit path (pr-str {:servers
                          {"a" {:client-info {:client-id "ca"}
                                :tokens {:access "oa" :refresh "ra"}}
                           "b" {:client-info {:client-id "cb"}
                                :tokens {:access "ob" :refresh "rb"}}}}))
      (auth/store-tokens! "a" "https://as.example" {:access "na" :expires-in 3600})
      (testing "a second legacy server authorizes with the shared registration"
        (is (= "ca" (auth/resolve-client-id! "b" {:oauth {}}
                                             {:issuer "https://as.example"}
                                             "http://127.0.0.1:1/cb"))))
      (auth/store-tokens! "b" "https://as.example" {:access "nb" :expires-in 3600})
      (is (= "ca" (get-in (auth/server-entry "a") [:client-info :client-id]))
          "the first migrated server keeps its registration")
      (is (= "ca" (get-in (auth/server-entry "b") [:client-info :client-id]))
          "the second legacy server joins the shared registration")
      (is (= "na" (get-in (auth/server-entry "a") [:tokens :access])))
      (is (= "nb" (get-in (auth/server-entry "b") [:tokens :access])))
      (testing "a new registration still wins over the shared one"
        (auth/store-client-info! "b" "https://as.example" {:client-id "cb2"})
        (is (= "cb2" (get-in (auth/server-entry "a") [:client-info :client-id]))))
      (finally
        (auth/logout! "a")
        (auth/logout! "b")
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest registration-without-tokens-is-not-authenticated
  ;; an authorization-server change drops the old issuer's tokens but keeps
  ;; the shared client registration: the request path must not send an
  ;; empty "Bearer" for it
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))
        definition {:auth :oauth :url "https://mcp.example.com/mcp"}]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (auth/store-client-info! "srv" "https://as.example" {:client-id "c1"})
      (is (some? (auth/server-entry "srv"))
          "the shared registration is stored")
      (is (= :mcp-auth-required
             (try ((:auth-headers (auth/make-auth-fns "srv" definition)))
                  nil
                  (catch Exception e (:type (ex-data e))))))
      (is (= :mcp-auth-required
             (try ((:on-401 (auth/make-auth-fns "srv" definition)) {:headers {}})
                  nil
                  (catch Exception e (:type (ex-data e))))))
      (finally
        (auth/logout! "srv")
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest unstamped-registration-is-reused-before-its-first-write
  ;; a pre-SEP-2352 entry keeps working: its DCR registration is reused
  ;; and back-stamped on the first authenticated use, not re-registered
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (spit path (pr-str {:servers {"srv" {:client-info {:client-id "legacy"}
                                           :tokens {:access "old"}}}}))
      (with-redefs [oauth/register-client
                    (fn [& _] (throw (ex-info "must not register" {})))]
        (is (= "legacy" (auth/resolve-client-id! "srv" {:oauth {}}
                                                 {:issuer "https://as.example"}
                                                 "http://127.0.0.1:1/cb"))))
      (is (nil? (auth/server-issuer "srv"))
          "resolve alone does not bind; the first write back-stamps")
      (finally
        (auth/logout! "srv")
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest application-type-inference
  (is (= "native" (auth/application-type ["http://127.0.0.1:8080/callback"])))
  (is (= "native" (auth/application-type ["http://localhost:1/cb"])))
  (is (= "native" (auth/application-type ["http://[::1]:1/cb"])))
  (is (= "web" (auth/application-type ["https://app.example.com/cb"])))
  (testing "disagreeing or unclassifiable URIs omit the parameter"
    (is (nil? (auth/application-type ["http://127.0.0.1:1/cb" "https://app.example.com/cb"])))
    (is (nil? (auth/application-type [])))
    (is (nil? (auth/application-type ["not a uri"])))
    (is (nil? (auth/application-type [nil])))
    (is (nil? (auth/application-type ["ftp://example.com/cb"])))))

(deftest client-registration-is-issuer-bound
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))
        metadata {:issuer "https://as.example"
                  :registration_endpoint "https://as.example/register"}
        definition {:url "https://mcp.example.com/mcp" :oauth {}}
        registered (atom nil)]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (testing "DCR sends the SEP-837 application_type and stores under the issuer"
        (with-redefs [oauth/register-client
                      (fn [_endpoint opts]
                        (reset! registered opts)
                        {:client_id "dcr-1"})]
          (is (= "dcr-1" (auth/resolve-client-id! "srv" definition metadata
                                                  "http://127.0.0.1:1/cb"))))
        (is (= "native" (:application-type @registered)))
        (is (= "https://as.example" (auth/server-issuer "srv")))
        (is (= "dcr-1" (get-in (auth/server-entry "srv") [:client-info :client-id]))))
      (testing "a server on the same issuer reuses the registration (no new DCR)"
        (with-redefs [oauth/register-client
                      (fn [& _] (throw (ex-info "must not register" {})))]
          (is (= "dcr-1" (auth/resolve-client-id! "other" definition metadata
                                                  "http://127.0.0.1:1/cb")))))
      (testing "config :client-id wins and :application-type is overridable"
        (with-redefs [oauth/register-client
                      (fn [_endpoint opts]
                        (reset! registered opts)
                        {:client_id "dcr-2"})]
          (is (= "cfg" (auth/resolve-client-id! "pre" {:oauth {:client-id "cfg"}}
                                                metadata "http://127.0.0.1:1/cb")))
          (is (= "dcr-2" (auth/resolve-client-id!
                          "override"
                          {:oauth {:application-type "web"}}
                          {:issuer "https://as2.example"
                           :registration_endpoint "https://as2.example/register"}
                          "http://127.0.0.1:1/cb")))
          (is (= "web" (:application-type @registered)))))
      (testing "pre-registered credentials bound to another AS throw (SEP-2352)"
        (auth/store-tokens! "pre" "https://other.example" {:access "a"})
        (is (= :oauth-issuer-mismatch
               (try (auth/resolve-client-id! "pre" {:oauth {:client-id "cfg"}}
                                             metadata "http://127.0.0.1:1/cb")
                    nil
                    (catch Exception e (:type (ex-data e)))))))
      (finally
        (auth/logout! "srv")
        (auth/logout! "other")
        (auth/logout! "pre")
        (auth/logout! "override")
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest flow-selection
  (is (= :pkce (auth/resolve-flow {:flow :pkce} {} {:has-ui true})))
  (is (= :device (auth/resolve-flow {:flow :device} {} {:has-ui true})))
  (is (= :pkce (auth/resolve-flow {} {} {:has-ui true})))
  (testing "auto picks the device flow only when headless and advertised"
    (is (= :device (auth/resolve-flow {} {:device_authorization_endpoint "x"}
                                      {:has-ui false})))
    (is (= :pkce (auth/resolve-flow {} {:device_authorization_endpoint "x"}
                                    {:has-ui true})))
    (is (= :pkce (auth/resolve-flow {} {} {:has-ui false}))))
  (is (= :oauth-invalid-config
         (try (auth/resolve-flow {:flow :bogus} {} {:has-ui true})
              nil
              (catch Exception e (:type (ex-data e)))))))

(deftest rfc-9207-authorization-response-validation
  (testing "a matching iss passes (simple string comparison)"
    (is (nil? (auth/validate-authorization-response! {:code "c" :iss "https://as"}
                                                     "https://as" false)))
    (is (nil? (auth/validate-authorization-response! {:code "c" :iss "https://as"}
                                                     "https://as" true))))
  (testing "a mismatching iss is rejected"
    (is (= :oauth-issuer-mismatch
           (try (auth/validate-authorization-response! {:code "c" :iss "https://evil"}
                                                       "https://as" false)
                nil
                (catch Exception e (:type (ex-data e)))))))
  (testing "comparison is not normalized (case, trailing slash, default port)"
    (doseq [bad ["" "https://AS" "https://as/" "https://as:443"]]
      (is (= :oauth-issuer-mismatch
             (try (auth/validate-authorization-response! {:code "c" :iss bad}
                                                         "https://as" false)
                  nil
                  (catch Exception e (:type (ex-data e))))))))
  (testing "an absent iss is rejected only when the server advertised support"
    (is (= :oauth-iss-missing
           (try (auth/validate-authorization-response! {:code "c"} "https://as" true)
                nil
                (catch Exception e (:type (ex-data e))))))
    (is (nil? (auth/validate-authorization-response! {:code "c"} "https://as" false))))
  (testing "an authorization error is surfaced only when authentic"
    (is (= :oauth-authorization-error
           (try (auth/validate-authorization-response!
                 {:error "access_denied" :error_description "nope" :iss "https://as"}
                 "https://as" false)
                nil
                (catch Exception e (:type (ex-data e))))))
    (is (= :oauth-issuer-mismatch
           (try (auth/validate-authorization-response!
                 {:error "access_denied" :error_description "attacker text" :iss "https://evil"}
                 "https://as" false)
                nil
                (catch Exception e (:type (ex-data e))))))
    (is (not (str/includes?
              (try
                (auth/validate-authorization-response!
                 {:error "access_denied" :error_description "attacker text"
                  :iss "https://evil"}
                 "https://as" false)
                ""
                (catch Exception e (ex-message e)))
              "attacker text"))
        "a mismatched response must not surface attacker-controlled error text")))

(deftest prepare-and-complete-pkce-flow
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))
        metadata {:issuer "https://as.example"
                  :authorization_endpoint "https://as.example/authorize"
                  :token_endpoint "https://as.example/token"
                  :registration_endpoint "https://as.example/register"
                  :authorization_response_iss_parameter_supported true
                  ::auth/prm {:scopes_supported ["read" "write"]}}
        definition {:url "https://mcp.example.com/mcp" :oauth {:flow :pkce}}
        exchange (atom nil)]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (with-redefs [oauth/register-client (fn [_endpoint _opts] {:client_id "dcr-1"})]
        (let [{:keys [url pending]} (auth/prepare-pkce-flow
                                     "srv" definition metadata "http://127.0.0.1:1/cb")
              params (oauth/parse-query-string (second (str/split url #"\?" 2)))]
          (testing "the authorize URL carries PKCE, state, scope and resource"
            (is (= "S256" (:code_challenge_method params)))
            (is (seq (:code_challenge params)))
            (is (= "read write" (:scope params)))
            (is (= "https://mcp.example.com/mcp" (:resource params)))
            (is (= (:state pending) (:state params))))
          (testing "the pending record carries the RFC 9207 issuer"
            (is (= "https://as.example" (:issuer pending)))
            (is (true? (:iss-supported? pending))))
          (testing "a wrong state is rejected"
            (is (= :oauth-state-mismatch
                   (try (auth/complete-pkce-flow! "srv" pending
                                                  {:code "c" :state "nope" :iss "https://as.example"})
                        nil
                        (catch Exception e (:type (ex-data e)))))))
          (testing "a missing state is rejected"
            (is (= :oauth-state-mismatch
                   (try (auth/complete-pkce-flow! "srv" pending
                                                  {:code "c" :iss "https://as.example"})
                        nil
                        (catch Exception e (:type (ex-data e)))))))
          (testing "a missing iss is rejected when the server advertised support"
            (is (= :oauth-iss-missing
                   (try (auth/complete-pkce-flow! "srv" pending
                                                  {:code "c" :state (:state pending)})
                        nil
                        (catch Exception e (:type (ex-data e)))))))
          (testing "a matching response exchanges and stores under the issuer"
            (with-redefs [oauth/exchange-authorization-code
                          (fn [_endpoint opts]
                            (reset! exchange opts)
                            {:access "a" :refresh "r" :expires-in 3600})]
              (is (= :logged-in (auth/complete-pkce-flow!
                                 "srv" pending
                                 {:code "c" :state (:state pending) :iss "https://as.example"})))
              (is (= "dcr-1" (:client-id @exchange)))
              (is (= "https://mcp.example.com/mcp" (:resource @exchange)))
              (is (= "read write" (:scope @exchange)))
              (is (= "https://as.example" (auth/server-issuer "srv")))
              (is (= "a" (get-in (auth/server-entry "srv") [:tokens :access])))))))
      (finally
        (auth/logout! "srv")
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest begin-and-complete-device-flow
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))
        definition {:url "https://mcp.example.com/mcp" :oauth {:flow :device}}
        metadata {:issuer "https://as.example"
                  :device_authorization_endpoint "https://as.example/device"
                  :token_endpoint "https://as.example/token"
                  :registration_endpoint "https://as.example/register"}]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (with-redefs [oauth/register-client (fn [_endpoint _opts] {:client_id "dcr-1"})
                    oauth/start-device-authorization
                    (fn [_endpoint _opts]
                      {:device-code "dev-1" :user-code "ABCD-EFGH"
                       :verification-uri "https://as.example/verify"
                       :interval 1 :expires-in 30})
                    oauth/fetch-json
                    (fn [_url _opts]
                      {:status 200 :body {:access_token "tok" :expires_in 3600}})
                    oauth/poll-oauth-device-code-flow
                    (fn [opts]
                      (let [r ((:poll opts))]
                        (when (= :complete (:status r)) (:value r))))]
        (let [started (auth/begin-device-flow! "dev" definition metadata
                                               "http://127.0.0.1:1/cb")]
          (is (= "ABCD-EFGH" (:user-code started)))
          (is (= "https://as.example/verify" (:verification-uri started)))
          (is (= :logged-in (auth/complete-device-flow! "dev" started (atom false))))
          (is (= "https://as.example" (auth/server-issuer "dev")))
          (is (= "tok" (get-in (auth/server-entry "dev") [:tokens :access])))))
      (finally
        (auth/logout! "dev")
        (reset-storage!)
        (fs/delete-tree dir)))))

(deftest refresh-never-crosses-authorization-servers
  (let [dir (temp-dir)
        path (str (fs/path dir "mcp-oauth.edn"))
        definition {:auth :oauth :url "https://mcp.example.com/mcp"}]
    (try
      (auth/configure-storage! {:mode :file :path path})
      (auth/store-client-info! "srv" "https://as-a.example" {:client-id "c"})
      (auth/store-tokens! "srv" "https://as-a.example"
                          {:access "a" :refresh "r" :expires-in 3600})
      (testing "a refresh token is not sent to a different authorization server"
        (with-redefs [auth/discover-meta
                      (fn [_name _definition]
                        {::auth/issuer "https://as-b.example"
                         :issuer "https://as-b.example"
                         :token_endpoint "https://as-b.example/token"})
                      oauth/refresh-access-token
                      (fn [& _] (throw (ex-info "must not refresh" {})))]
          (is (= :mcp-auth-required
                 (try ((:on-401 (auth/make-auth-fns "srv" definition)) {:headers {}})
                      nil
                      (catch Exception e (:type (ex-data e))))))))
      (testing "the same issuer refreshes and re-binds the stored tokens"
        (with-redefs [auth/discover-meta
                      (fn [_name _definition]
                        {::auth/issuer "https://as-a.example"
                         :issuer "https://as-a.example"
                         :token_endpoint "https://as-a.example/token"})
                      oauth/refresh-access-token
                      (fn [_endpoint _opts] {:access "fresh" :expires-in 3600})]
          (is (= "Bearer fresh"
                 (get ((:on-401 (auth/make-auth-fns "srv" definition)) {:headers {}})
                      "Authorization")))
          (is (= "fresh" (get-in (auth/server-entry "srv") [:tokens :access])))
          (is (= "https://as-a.example" (auth/server-issuer "srv")))))
      (finally
        (auth/logout! "srv")
        (reset-storage!)
        (fs/delete-tree dir)))))
