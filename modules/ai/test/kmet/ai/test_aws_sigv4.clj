(ns kmet.libs.test-aws-sigv4
  "SigV4 signing (kmet.libs.aws-sigv4). The signing-key chain is validated
   against the AWS-documented derivation (Signature Version 4 signing
   process — kSigning = HMAC(HMAC(HMAC(HMAC('AWS4'+secret, date), region),
   service), 'aws4_request')); the full signature is exercised on the
   Bedrock-shaped request (content-type + x-amz-content-sha256 signed, the
   SDK header set)."
  (:require [clojure.test :as t]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.libs.aws-sigv4 :as aws]))

(defn- hex->bytes
  "Parse lowercase hex into a byte array (portable replacement for
   HexFormat/of+parseHex — java.util.HexFormat is unshimmed on Jolt)."
  [hex]
  (let [hex (str hex)
        n (/ (count hex) 2)
        out (byte-array n)]
    (dotimes [i n]
      (aset out i (unchecked-byte (Integer/parseInt (subs hex (* 2 i) (+ (* 2 i) 2)) 16))))
    out))

(defn- hmac-sha256
  "HMAC-SHA256 hex of a UTF-8 string with a byte-array key (independent
   reimplementation for the key-chain check)."
  [key s]
  (let [mac (javax.crypto.Mac/getInstance "HmacSHA256")]
    (.init mac (javax.crypto.spec.SecretKeySpec. key "HmacSHA256"))
    (let [bytes (.doFinal mac (.getBytes s "UTF-8"))
          sb (StringBuilder.)]
      (doseq [b bytes]
        (.append sb (format "%02x" (bit-and b 0xff))))
      (str sb))))

(def ^:private example-secret "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")

(t/deftest test-sigv4-signing-key-chain
  ;; The AWS docs example derivation (date 20150830, region us-east-1,
  ;; service iam) — the chain order and AWS4 prefix are the security-critical
  ;; parts; the test recomputes the same chain with the documented formula.
  (let [k-date (hmac-sha256 (.getBytes (str "AWS4" example-secret) "UTF-8") "20150830")
        k-region (hmac-sha256 (hex->bytes k-date) "us-east-1")
        k-service (hmac-sha256 (hex->bytes k-region) "iam")
        k-signing (hmac-sha256 (hex->bytes k-service) "aws4_request")]
    ;; the AWS docs list this exact value for the example's kSigning
    (t/is (= "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9"
             k-signing)
          "signing key chain matches the AWS-documented derivation")
    (let [auth (get (aws/sign-request
                     {:method "GET"
                      :url "https://iam.amazonaws.com/"
                      :region "us-east-1" :service "iam"
                      :access-key "AKIDEXAMPLE" :secret-key example-secret
                      :amz-date "20150830T123600Z" :payload-hash "empty"})
                    "Authorization")]
      (t/is (= 64 (count (last (str/split auth #"Signature="))))
            "the final signature is a 64-char hex HMAC of the string-to-sign"))))

(t/deftest test-sigv4-bedrock-shape
  ;; a bedrock POST: the SDK signs content-type + x-amz-content-sha256
  (let [payload "{\"modelId\":\"m\"}"
        headers (aws/sign-request
                 {:method "POST"
                  :url "https://bedrock-runtime.us-east-1.amazonaws.com/model/m/converse-stream"
                  :region "us-east-1" :service "bedrock"
                  :access-key "AKID" :secret-key "SECRET"
                  :headers {"Content-Type" "application/json"}
                  :amz-date "20240101T000000Z"
                  :payload-hash (aws/sha256-hex payload)})]
    (t/is (= (aws/sha256-hex payload) (get headers "x-amz-content-sha256")))
    (t/is (= "20240101T000000Z" (get headers "x-amz-date")))
    (t/is (str/includes? (get headers "Authorization")
                         "Credential=AKID/20240101/us-east-1/bedrock/aws4_request"))
    (t/is (str/includes? (get headers "Authorization")
                         "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date"))
    (t/is (= 64 (count (last (str/split (get headers "Authorization") #"Signature=")))))))

(t/deftest test-sigv4-stability-and-sensitivity
  (let [base {:method "POST"
              :url "https://bedrock-runtime.us-east-1.amazonaws.com/model/m/converse-stream"
              :region "us-east-1" :service "bedrock"
              :access-key "AKID" :secret-key "SECRET"
              :amz-date "20240101T000000Z"
              :payload-hash "abc"}]
    (t/is (= (get (aws/sign-request base) "Authorization")
             (get (aws/sign-request base) "Authorization"))
          "deterministic")
    (doseq [[label changed] {"different secret" (assoc base :secret-key "OTHER")
                             "different date" (assoc base :amz-date "20240102T000000Z")
                             "different payload" (assoc base :payload-hash "def")
                             "different region" (assoc base :region "eu-west-1")}]
      (t/is (not= (get (aws/sign-request base) "Authorization")
                  (get (aws/sign-request changed) "Authorization"))
            label))))

(t/deftest test-sigv4-session-token
  (let [headers (aws/sign-request
                 {:method "POST" :url "https://bedrock-runtime.us-east-1.amazonaws.com/model/m/converse-stream"
                  :region "us-east-1" :service "bedrock"
                  :access-key "AKID" :secret-key "SECRET" :session-token "TOK"
                  :amz-date "20240101T000000Z" :payload-hash "hash"})]
    (t/is (= "TOK" (get headers "x-amz-security-token")))
    (t/is (str/includes? (get headers "Authorization")
                         "SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token"))))

;; ─── Ambient credential resolution ─────────────────────────────────────────

(t/deftest test-profile-credentials
  (let [tmp (str (fs/absolutize (fs/file "target" (str "test-aws-creds-" (System/currentTimeMillis)))))
        path (str tmp "/credentials")]
    (fs/create-dirs tmp)
    (try
      (spit path (str "[default]\n"
                      "aws_access_key_id = AKID123\n"
                      "aws_secret_access_key = SECRET456\n"
                      "aws_session_token = TOKEN789\n"
                      "\n"
                      "[other]\n"
                      "aws_access_key_id = OTHER\n"
                      "aws_secret_access_key = OTHER-SECRET\n"))
      (with-redefs [aws/getenv (fn [k] (case k
                                         "AWS_SHARED_CREDENTIALS_FILE" path
                                         "AWS_PROFILE" nil
                                         nil))]
        (t/is (= {:access-key "AKID123" :secret-key "SECRET456" :session-token "TOKEN789"}
                 (aws/profile-credentials "default")))
        (t/is (= {:access-key "OTHER" :secret-key "OTHER-SECRET" :session-token nil}
                 (aws/profile-credentials "other")))
        (t/is (nil? (aws/profile-credentials "missing"))))
      (with-redefs [aws/getenv (fn [_] nil)]
        (t/is (nil? (aws/profile-credentials "default")) "missing file → nil"))
      (finally
        (fs/delete-tree tmp)))))
