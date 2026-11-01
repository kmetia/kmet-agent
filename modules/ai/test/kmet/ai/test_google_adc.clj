(ns kmet.ai.test-google-adc
  "Google ADC token acquisition (kmet.ai.google-adc): service-account
   self-signed JWT flow and authorized_user refresh flow, with the token
   endpoint mocked and a real RSA key verifying the JWT signature."
  (:require [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.libs.json :as json]
            [clojure.string :as str]
            [kmet.ai.google-adc :as adc]
            [kmet.libs.http :as http]
            [kmet.test-tmp :as test-tmp]))

(defn- generate-keypair
  "A fresh RSA keypair (PEM private key like a service-account file)."
  []
  (let [gen (java.security.KeyPairGenerator/getInstance "RSA")]
    (.initialize gen 2048)
    (.generateKeyPair gen)))

(defn- pem-private-key
  "PKCS8 PEM encoding of a private key."
  [priv]
  (let [der (.getEncoded priv)
        b64 (.encodeToString (java.util.Base64/getEncoder) der)
        lines (partition-all 64 b64)]
    (str "-----BEGIN PRIVATE KEY-----\n"
         (str/join "\n" (map #(apply str %) lines))
         "\n-----END PRIVATE KEY-----\n")))

(defn- service-account-cred
  "A service-account credentials file map with a real key."
  [email]
  (let [kp (generate-keypair)]
    {:type "service_account"
     :client_email email
     :private_key (pem-private-key (.getPrivate kp))
     :private_key_id "key1"
     :public-key (.getPublic kp)}))

(defn- write-cred
  "Write a credentials file and return [path public-key]."
  [cred tmp]
  (let [path (str tmp "/credentials.json")]
    (spit path (json/generate-string (dissoc cred :public-key)))
    [path (:public-key cred)]))

(defn- verify-jwt
  "Parse a JWT assertion, return {:header :claims :valid-signature?}."
  [assertion public-key]
  (let [[h c s] (str/split assertion #"\.")
        header (json/parse-string (String. (.decode (java.util.Base64/getUrlDecoder) h) "UTF-8") true)
        claims (json/parse-string (String. (.decode (java.util.Base64/getUrlDecoder) c) "UTF-8") true)
        sig (.decode (java.util.Base64/getUrlDecoder) s)
        verifier (java.security.Signature/getInstance "SHA256withRSA")]
    (.initVerify verifier public-key)
    (.update verifier (.getBytes (str h "." c) "UTF-8"))
    {:header header :claims claims :valid? (.verify verifier sig)}))

(t/deftest test-adc-service-account-flow
  (let [tmp (test-tmp/temp-dir! "test-adc-")
        email "sa@project.iam.gserviceaccount.com"]
    (fs/create-dirs tmp)
    (let [cred (service-account-cred email)
          [path public-key] (write-cred cred tmp)]
      (try
        (adc/clear-token-cache!)
        (with-redefs [adc/credentials-path (constantly path)]
          (t/is (adc/configured?)))
        (with-redefs [adc/credentials-path (constantly path)
                      http/request-json (fn [url opts]
                                          (t/is (= "https://oauth2.googleapis.com/token" url))
                                          (let [form (:body opts)]
                                            (t/is (str/includes? form "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer"))
                                            (t/is (str/includes? form "assertion="))
                                            (let [assertion (subs form (+ (count "assertion=")
                                                                          (str/index-of form "assertion=")))
                                                  parsed (verify-jwt assertion public-key)]
                                              (t/is (= "RS256" (:alg (:header parsed))))
                                              (t/is (= email (:iss (:claims parsed))))
                                              (t/is (= "https://oauth2.googleapis.com/token" (:aud (:claims parsed))))
                                              (t/is (str/includes? (:scope (:claims parsed))
                                                                   "cloud-platform"))
                                              (t/is (:valid? parsed) "JWT signature verifies with the public key"))
                                            {:status 200
                                             :body {:access_token "tok-1" :expires_in 3600}}))]
          (t/is (= "tok-1" (adc/access-token!))))
        (t/testing "tokens are cached until the expiry window"
          (adc/clear-token-cache!)
          (let [calls (atom 0)]
            (with-redefs [adc/credentials-path (constantly path)
                          http/request-json (fn [& _] (swap! calls inc)
                                              {:status 200
                                               :body {:access_token "tok-2" :expires_in 3600}})]
              (t/is (= "tok-2" (adc/access-token!)))
              (t/is (= "tok-2" (adc/access-token!)) "second call hits the cache")
              (t/is (= 1 @calls)))))
        (finally
          (fs/delete-tree tmp))))))

(t/deftest test-adc-authorized-user-flow
  (let [tmp (test-tmp/temp-dir! "test-adc-user-")
        path (str tmp "/credentials.json")]
    (fs/create-dirs tmp)
    (try
      (spit path (json/generate-string {:type "authorized_user"
                                        :client_id "client-1"
                                        :client_secret "secret-1"
                                        :refresh_token "refresh-1"}))
      (adc/clear-token-cache!)
      (with-redefs [adc/credentials-path (constantly path)
                    http/request-json (fn [url opts]
                                        (t/is (= "https://oauth2.googleapis.com/token" url))
                                        (let [form (:body opts)]
                                          (t/is (str/starts-with? form "grant_type=refresh_token"))
                                          (t/is (str/includes? form "client_id=client-1"))
                                          (t/is (str/includes? form "refresh_token=refresh-1")))
                                        {:status 200
                                         :body {:access_token "user-tok" :expires_in 1800}})]
        (t/is (= "user-tok" (adc/access-token!))))
      (finally
        (fs/delete-tree tmp)))))

(t/deftest test-adc-credentials-path
  (t/testing "GOOGLE_APPLICATION_CREDENTIALS wins when it exists"
    (let [tmp (test-tmp/temp-dir! "test-adc-path-")
          path (str tmp "/sa.json")]
      (fs/create-dirs tmp)
      (spit path "{}")
      (try
        (with-redefs [adc/credentials-path (constantly path)
                      adc/configured? (constantly true)]
          nil)
        (with-redefs [adc/configured? (constantly true)]
          (t/is (adc/configured?)))
        (finally
          (fs/delete-tree tmp)))))
  (t/testing "no credentials → not configured"
    (with-redefs [adc/credentials-path (constantly nil)]
      (t/is (not (adc/configured?))))))
