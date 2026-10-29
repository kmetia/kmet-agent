(ns kmet.extensions.mcp-adapter.auth-test
  "The auth adapter's policy surface (§7.8) without a server: the RFC 8707
   resource canonicalization, the WWW-Authenticate parse, the loopback
   redirect rules, the token store's status vocabulary, and the header
   provider. The flows against the fake authorization server run in the
   ^:slow oauth suite."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.libs.mcp.auth :as mcp-auth]
            [kmet.libs.oauth :as oauth-lib]
            [kmet.extensions.mcp-adapter.auth :as auth]
            [kmet.extensions.mcp-adapter.test-util :as tu]))

;; ─── RFC 8707 resource indicator ──────────────────────────────────────────

(deftest canonical-resource-uri
  (is (= "https://mcp.example.com/mcp"
         (auth/canonical-resource-uri "HTTPS://MCP.Example.com/mcp/")))
  (testing "a root path is dropped"
    (is (= "https://mcp.example.com"
           (auth/canonical-resource-uri "https://mcp.example.com"))))
  (testing "query and fragment are stripped"
    (is (= "https://mcp.example.com/mcp"
           (auth/canonical-resource-uri "https://mcp.example.com/mcp?key=1")))
    (is (= "https://mcp.example.com/mcp"
           (auth/canonical-resource-uri "https://mcp.example.com/mcp#frag")))))

;; ─── RFC 9728 WWW-Authenticate ────────────────────────────────────────────

(deftest parse-www-authenticate
  (is (= {:resource-metadata "https://as.example/.well-known/oauth-protected-resource"
          :scope "files:read"}
         (auth/parse-www-authenticate
          (str "Bearer resource_metadata=\"https://as.example/.well-known/"
               "oauth-protected-resource\", scope=\"files:read\""))))
  (testing "a challenge with neither field parses to nothing usable"
    (is (not (seq (auth/parse-www-authenticate "Bearer"))))))

;; ─── Loopback redirect ────────────────────────────────────────────────────

(deftest callback-redirect-uri
  (let [bound {:port 4321 :path "/custom-cb"}
        configured "http://127.0.0.1:4321/custom-cb"]
    (testing "a matching config keeps the configured spelling"
      (is (= configured
             (auth/callback-redirect-uri bound 4321 "/custom-cb" configured))))
    (testing "a later config cannot rebind the callback server"
      (is (= (str "http://" (oauth-lib/callback-host) ":4321/custom-cb")
             (auth/callback-redirect-uri bound 4322 "/other"
                                         "http://127.0.0.1:4322/other"))))
    (testing "a path-less config derives the served callback path"
      (is (= (str "http://" (oauth-lib/callback-host) ":4321/callback")
             (auth/callback-redirect-uri {:port 4321 :path "/callback"}
                                         4321 nil
                                         "http://127.0.0.1:4321"))))
    (testing "no configured URI uses the bound server's URI"
      (is (= (str "http://" (oauth-lib/callback-host) ":4321/custom-cb")
             (auth/callback-redirect-uri bound nil nil nil))))))

(deftest redirect-uri-validation
  (let [valid (fn [cfg] ((var-get #'auth/redirect-uri-for) cfg 0 "/callback"))]
    (testing "an http loopback URI with a port is accepted verbatim"
      (is (= ["http://127.0.0.1:7777/cb" 7777]
             (valid {:redirect-uri "http://127.0.0.1:7777/cb"}))))
    (testing "localhost and ::1 are loopback hosts"
      (is (= 7777 (second (valid {:redirect-uri "http://localhost:7777/cb"}))))
      (is (= 7777 (second (valid {:redirect-uri "http://[::1]:7777/cb"})))))
    (testing "a non-loopback, non-http or port-less URI is refused"
      (doseq [bad ["https://127.0.0.1:7777/cb"
                   "http://example.com:7777/cb"
                   "http://127.0.0.1/cb"]]
        (let [e (try (valid {:redirect-uri bad}) nil (catch Exception e e))]
          (is (= :oauth-invalid-config (:type (ex-data e))) bad))))
    (testing "no configured URI gets the OS-assigned default"
      (is (= 0 (second (valid {})))))))

;; ─── Token store vocabulary ───────────────────────────────────────────────

(defmacro ^:private with-store
  "Redirect the OAuth store to a temp file for BODY."
  [& body]
  `(let [dir# (tu/temp-dir! "mcp-auth")
         path# (str dir# "/store.edn")]
     (try
       (with-redefs [auth/store-path (fn [] path#)]
         (auth/configure-storage! {:token-storage :file})
         (let [result# (do ~@body)]
           result#))
       (finally
         (mcp-auth/configure-storage! {:mode :auto})
         (tu/delete-tree! dir#)))))

(defn- oauth-definition
  "An oauth-configured definition for the store/status assertions."
  []
  {:url "https://mcp.example.com/mcp" :auth :oauth
   :oauth {:flow :pkce}})

(deftest token-store-status
  (with-store
    (let [definition (oauth-definition)]
      (testing "no stored credentials answer :none"
        (is (= :none (auth/auth-status "srv" definition))))
      (testing "a client registration without tokens is still :none"
        (auth/store-server! "srv" {:issuer "https://as.example"
                                   :client-info {:client-id "c1"}})
        (is (= :none (auth/auth-status "srv" definition))))
      (testing "stored tokens answer :logged-in"
        (auth/store-server! "srv" {:issuer "https://as.example"
                                   :client-info {:client-id "c1"}
                                   :tokens {:access "a" :refresh "r"}})
        (is (= :logged-in (auth/auth-status "srv" definition))))
      (testing "an expired access token answers :expired"
        (auth/store-server! "srv" {:issuer "https://as.example"
                                   :client-info {:client-id "c1"}
                                   :tokens {:access "a"
                                            :expires (- (System/currentTimeMillis)
                                                        (* 60 60 1000))}})
        (is (= :expired (auth/auth-status "srv" definition))))
      (testing "logout clears the entry"
        (auth/logout! "srv")
        (is (nil? (auth/server-entry "srv")))
        (is (= :none (auth/auth-status "srv" definition)))))))

(deftest auth-status-vocabulary
  (testing "no url or no auth configured answers nil"
    (is (nil? (auth/auth-status "s" {:command "bb"})))
    (is (nil? (auth/auth-status "s" {:url "https://x"}))))
  (testing "a bearer definition reports :bearer only with a token"
    (is (= :bearer (auth/auth-status "s" {:url "https://x" :auth :bearer
                                          :bearer-token "t"})))
    (is (= :none (auth/auth-status "s" {:url "https://x" :auth :bearer}))))
  (testing "machine grants report their grant"
    (is (= :client-credentials
           (auth/auth-status "s" {:url "https://x" :auth :oauth
                                  :oauth {:grant :client-credentials}})))
    (is (= :jwt-bearer
           (auth/auth-status "s" {:url "https://x" :auth :oauth
                                  :oauth {:grant :jwt-bearer}})))))

(deftest auth-headers
  (with-store
    (let [definition (oauth-definition)]
      (testing "a stored token becomes an Authorization header"
        (auth/store-server! "srv" {:issuer "https://as.example"
                                   :tokens {:access "access-1"}})
        (is (= "Bearer access-1"
               (get ((:auth-headers (auth/make-auth-fns "srv" definition)))
                    "Authorization"))))
      (testing "a credential-less entry refuses instead of sending nothing"
        (auth/store-server! "other" {:issuer "https://as.example"
                                     :client-info {:client-id "c1"}})
        (let [e (try ((:auth-headers (auth/make-auth-fns "other" definition)))
                     nil
                     (catch Exception e e))]
          (is (= :mcp-auth-required (:type (ex-data e)))))))))
