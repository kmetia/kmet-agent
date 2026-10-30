(ns kmet.libs.aws-sigv4
  "AWS Signature Version 4 request signing (the SigV4 side of pi's
   amazon-bedrock provider — pi uses the AWS SDK's default credential chain
   + SigV4; Babashka has no AWS SDK, so the signing is done here).

   One public fn: sign-request. The canonical request / string-to-sign /
   signing-key chain follow the AWS docs; the tests pin the intermediate
   values against the AWS SigV4 test-suite example."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]))

(def ^:private utc
  "UTC as a zone offset, resolved once at load (see `time-unit-millis` in
   kmet.libs.sse for why a field in a `def` beats a static method here)."
  java.time.ZoneOffset/UTC)

(def ^:private getenv
  (fn [k] (System/getenv k)))

(defn- bytes->hex
  "Lowercase hex of a byte array (portable replacement for
   HexFormat/of+formatHex — java.util.HexFormat is unshimmed on Jolt)."
  [bytes]
  (let [sb (StringBuilder.)]
    (doseq [b bytes]
      (.append sb (format "%02x" (bit-and b 0xff))))
    (str sb)))

(defn sha256-hex
  "Hex-encoded SHA-256 of a UTF-8 string (public — the request builders
   hash the payload for x-amz-content-sha256)."
  [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes s "UTF-8"))]
    (bytes->hex bytes)))

(defn- hmac
  "HMAC-SHA256 of data with key (byte arrays)."
  [key data]
  (let [mac (javax.crypto.Mac/getInstance "HmacSHA256")]
    (.init mac (javax.crypto.spec.SecretKeySpec. key "HmacSHA256"))
    (.doFinal mac data)))

(defn- hmac-hex
  "Hex-encoded HMAC-SHA256 of a string with a byte-array key."
  [key s]
  (bytes->hex (hmac key (.getBytes s "UTF-8"))))

(defn- uri-encode
  "Percent-encode a URI path segment (AWS canonical-uri encoding: encode
   everything except unreserved characters, then compress %2F)."
  [s]
  (str/replace (java.net.URLEncoder/encode s "UTF-8") #"\+" "%20"))

(defn- canonical-uri
  "AWS canonical URI: the URL path, URI-encoded, with %2F kept as /."
  [path]
  (let [encoded (-> (str/replace path #"/+" "/")
                    (uri-encode))]
    (str/replace encoded #"%2F" "/")))

(defn- canonical-query
  "AWS canonical query string: sorted key=value pairs (URI-encoded). Accepts
   a raw query string (Action=ListUsers&Version=2010-05-08) or a seq of
   [k v] pairs."
  [query]
  (if (string? query)
    (->> (str/split query #"&")
         (map #(str/split % #"=" 2))
         (sort-by first)
         (map (fn [[k v]] (str (uri-encode k) "=" (uri-encode (or v "")))))
         (str/join "&"))
    (if (empty? query)
      ""
      (->> query
           (sort-by key)
           (map (fn [[k v]] (str (uri-encode k) "=" (uri-encode v))))
           (str/join "&")))))

(defn- signing-key
  "The SigV4 signing key chain: HMAC(HMAC(HMAC(HMAC(AWS4Secret, date),
   region), service), 'aws4_request')."
  [secret-key date-stamp region service]
  (-> (hmac (.getBytes (str "AWS4" secret-key) "UTF-8") (.getBytes date-stamp "UTF-8"))
      (hmac (.getBytes region "UTF-8"))
      (hmac (.getBytes service "UTF-8"))
      (hmac (.getBytes "aws4_request" "UTF-8"))))

(defn sign-request
  "AWS SigV4 signing headers for one request (pi: the SDK's SigV4 signer —
   used by the bedrock-converse-stream builder).

   opts:
     :method        — \"POST\"
     :url           — full request URL (host + path + query)
     :region        — AWS region (e.g. \"us-east-1\")
     :service       — AWS service name (\"bedrock\")
     :access-key    — AWS access key id
     :secret-key    — AWS secret access key
     :session-token — optional session token (STS) → x-amz-security-token
     :payload-hash  — hex SHA-256 of the request body (\"\" for an empty
                      body)
     :headers       — extra request headers to include in the signature
                      (e.g. a content-type header — AWS requires it in the
                      canonical request); lowercased before signing
     :amz-date      — optional explicit timestamp (yyyyMMdd'T'HHmmss'Z');
                      defaults to now (test seam — the AWS example pins it)

   Returns the header map to merge into the request: Authorization,
   x-amz-date, x-amz-content-sha256, (+ x-amz-security-token)."
  [{:keys [method url region service access-key secret-key session-token payload-hash headers amz-date]}]
  (let [uri (java.net.URI. url)
        host (or (.getHost uri) "")
        path (or (.getPath uri) "/")
        query (or (.getQuery uri) "")
        amz-date (or amz-date
                     (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
                              (java.time.ZonedDateTime/now utc)))
        date-stamp (subs amz-date 0 8)
        hashed-payload (or payload-hash (sha256-hex ""))
        all-headers (merge {"host" host
                            "x-amz-content-sha256" hashed-payload
                            "x-amz-date" amz-date}
                           (when (seq session-token)
                             {"x-amz-security-token" session-token})
                           (into {} (map (fn [[k v]] [(str/lower-case k) v])) headers))
        sorted (sort-by key all-headers)
        canonical-headers (apply str (map (fn [[k v]] (str (str/lower-case k) ":" (str/trim v) "\n")) sorted))
        signed-headers (str/join ";" (map (fn [[k _]] (str/lower-case k)) sorted))
        canonical-request (str method "\n"
                               (canonical-uri path) "\n"
                               (canonical-query query) "\n"
                               canonical-headers "\n"
                               signed-headers "\n"
                               hashed-payload)
        scope (str date-stamp "/" region "/" service "/aws4_request")
        string-to-sign (str "AWS4-HMAC-SHA256\n" amz-date "\n" scope "\n"
                            (sha256-hex canonical-request))
        key (signing-key secret-key date-stamp region service)
        signature (hmac-hex key string-to-sign)
        credential (str access-key "/" scope)]
    (cond-> {"Authorization" (str "AWS4-HMAC-SHA256 Credential=" credential
                                  ", SignedHeaders=" signed-headers
                                  ", Signature=" signature)
             "x-amz-date" amz-date
             "x-amz-content-sha256" hashed-payload}
      (seq session-token) (assoc "x-amz-security-token" session-token))))

;; ─── Ambient credential resolution (pi: the AWS SDK default chain, kmet's
;;      subset — env keys, the shared credentials file, bearer token) ───────

(defn ambient-configured?
  "True when any ambient AWS credential source is present (pi
   getEnvApiKey amazon-bedrock): a profile, access keys, the Bedrock bearer
   token, or the ECS/IRSA role vars."
  []
  (or (getenv "AWS_PROFILE")
      (and (getenv "AWS_ACCESS_KEY_ID") (getenv "AWS_SECRET_ACCESS_KEY"))
      (getenv "AWS_BEARER_TOKEN_BEDROCK")
      (getenv "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
      (getenv "AWS_CONTAINER_CREDENTIALS_FULL_URI")
      (getenv "AWS_WEB_IDENTITY_TOKEN_FILE")))

(defn- parse-ini
  "Minimal INI parse of the ~/.aws/credentials file: {profile-name ->
   {key value}}; [default] becomes the :default key. Lines with a key are
   lowercased (aws-cli canonicalizes section keys)."
  [text]
  (let [lines (str/split-lines text)]
    (loop [lines lines
           section nil
           result {}]
      (if-let [line (first lines)]
        (let [line (str/trim line)]
          (cond
            (or (str/blank? line) (str/starts-with? line "#") (str/starts-with? line ";"))
            (recur (rest lines) section result)

            (and (str/starts-with? line "[") (str/ends-with? line "]"))
            (recur (rest lines) (keyword (subs line 1 (dec (count line)))) result)

            :else
            (if-let [[_ k v] (re-matches #"([^=]+)=(.*)" line)]
              (recur (rest lines) section
                     (assoc-in result [section (keyword (str/lower-case (str/trim k)))] (str/trim v)))
              (recur (rest lines) section result))))
        result))))

(defn profile-credentials
  "AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY (+ AWS_SESSION_TOKEN) for an
   AWS_PROFILE, read from ~/.aws/credentials (the shared-credentials file
   path honors AWS_SHARED_CREDENTIALS_FILE). Returns nil when the profile
   or its keys are missing."
  [profile]
  (when (seq profile)
    (let [path (or (some-> (getenv "AWS_SHARED_CREDENTIALS_FILE") not-empty)
                   (str (fs/path (System/getProperty "user.home") ".aws" "credentials")))]
      (when (fs/exists? path)
        (let [section (get (parse-ini (slurp path)) (keyword profile))]
          (when (and section
                     (seq (:aws_access_key_id section))
                     (seq (:aws_secret_access_key section)))
            {:access-key (:aws_access_key_id section)
             :secret-key (:aws_secret_access_key section)
             :session-token (:aws_session_token section)}))))))

(defn resolve-credentials
  "The SigV4 credentials for a request, in pi's SDK-default-chain order:
   explicit env keys → the AWS_PROFILE's shared-credentials section →
   nil (the bearer-token path or an error reports the rest)."
  []
  (let [access (getenv "AWS_ACCESS_KEY_ID")
        secret (getenv "AWS_SECRET_ACCESS_KEY")]
    (if (and (seq access) (seq secret))
      {:access-key access
       :secret-key secret
       :session-token (getenv "AWS_SESSION_TOKEN")}
      (profile-credentials (getenv "AWS_PROFILE")))))
