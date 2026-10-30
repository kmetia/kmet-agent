(ns kmet.ai.test-llm-bedrock
  "AWS Bedrock ConverseStream: the event framing, header/CRC helpers, and the
   streaming end-to-end."
  (:require [clojure.test :as t]
            [kmet.ai.hash :as hash]
            [kmet.libs.json :as json]
            [clojure.string :as str]
            [kmet.ai.auth :as auth]
            [kmet.ai.aws-sigv4 :as aws-sigv4]
            [kmet.ai.llm :as llm]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.api.openai-responses :as responses]
            [kmet.ai.api.bedrock-converse-stream :as bedrock]
            [kmet.ai.models :as m]
            [kmet.app.tools.tool :as tools]))

;; ─── AWS Bedrock ConverseStream ───────────────────────────────────────────

(defn- bedrock-model
  [& {:keys [id name reasoning max-tokens compat]
      :or {id "anthropic.claude-sonnet-4-5-20250929-v1:0" name "Claude Sonnet 4.5"
           reasoning true max-tokens 64000 compat nil}}]
  {:id id :name name :provider :amazon-bedrock :api :bedrock-converse-stream
   :base-url "https://bedrock-runtime.us-east-1.amazonaws.com"
   :reasoning reasoning :max-tokens max-tokens :compat compat})

(t/deftest test-bedrock-model-classification
  (t/is (true? (@#'bedrock/bedrock-is-claude? (bedrock-model))))
  (t/is (true? (@#'bedrock/bedrock-is-claude? (bedrock-model :id "arn:aws:bedrock:us-east-1:123:inference-profile/xyz"
                                                             :name "Claude Opus 4.8"))))
  (t/is (false? (@#'bedrock/bedrock-is-claude? (bedrock-model :id "amazon.nova-lite-v1:0"
                                                              :name "Nova Lite"))))
  (t/is (true? (@#'bedrock/bedrock-supports-adaptive-thinking? (bedrock-model :id "anthropic.claude-opus-4-6-v1:0"))))
  (t/is (false? (@#'bedrock/bedrock-supports-adaptive-thinking? (bedrock-model))))
  (t/is (true? (@#'bedrock/bedrock-supports-prompt-caching? (bedrock-model))))
  (t/is (false? (@#'bedrock/bedrock-supports-prompt-caching?
                 (bedrock-model :id "amazon.nova-lite-v1:0" :name "Nova Lite")))
        "non-claude models have automatic caching — no explicit cache points"))

(t/deftest test-bedrock-messages
  (let [msgs [{:role :system :content [{:type :text :text "sys"}]}
              {:role :user :content [{:type :text :text "hi"}]}
              {:role :assistant :content [{:type :text :text "answer"}]
               :thinking "let me think"
               :tool-calls [{:id "t1" :name "read" :arguments {:path "/x"}}]}
              {:role :tool :content [{:content "done" :tool_use_id "t1"}]
               :tool-name "read"}
              {:role :user :content [{:type :text :text "more"}]}]]
    (t/testing "claude model: system block with cache point, thinking as plain
                text (kmet stores no reasoning signatures — pi's no-signature
                fallback; Bedrock rejects a signature-less reasoningContent),
                cache point on last user"
      (let [[result system] (@#'bedrock/bedrock-messages msgs (bedrock-model) :short)]
        (t/is (= [{:text "sys"} {:cachePoint {:type "default"}}] system))
        (t/is (= [{:role "user" :content [{:text "hi"}]}
                  {:role "assistant"
                   :content [{:text "answer"}
                             {:toolUse {:toolUseId "t1" :name "read" :input {:path "/x"}}}
                             {:text "let me think"}]}
                  {:role "user"
                   :content [{:toolResult {:toolUseId "t1"
                                           :content [{:text "done"}]
                                           :status "success"}}]}
                  {:role "user"
                   :content [{:text "more"}
                             {:cachePoint {:type "default"}}]}]
                 result))))
    (t/testing "non-claude model: thinking as reasoningContent (no signature),
                no cache points"
      (let [[result system] (@#'bedrock/bedrock-messages
                             msgs (bedrock-model :id "amazon.nova-lite-v1:0" :name "Nova") :short)]
        (t/is (= [{:text "sys"}] system) "no cache point on the system block")
        (t/is (= [{:role "user" :content [{:text "hi"}]}
                  {:role "assistant"
                   :content [{:text "answer"}
                             {:toolUse {:toolUseId "t1" :name "read" :input {:path "/x"}}}
                             {:reasoningContent {:reasoningText {:text "let me think"}}}]}
                  {:role "user"
                   :content [{:toolResult {:toolUseId "t1"
                                           :content [{:text "done"}]
                                           :status "success"}}]}
                  {:role "user" :content [{:text "more"}]}]
                 result))))
    (t/testing "no system prompt → nil system blocks"
      (let [[result system] (@#'bedrock/bedrock-messages
                             (remove #(= :system (:role %)) msgs) (bedrock-model) :short)]
        (t/is (nil? system))
        (t/is (= 4 (count result)))))))

(t/deftest test-llm-bedrock-tool-id-normalization
  (let [id "call_qwen|opaque/tool-id"
        [messages _] (@#'bedrock/bedrock-messages
                      [{:role :assistant :content []
                        :tool-calls [{:id id :name "bash" :arguments {}}]}
                       {:role :tool
                        :content [{:content "done" :tool_use_id id}]}]
                      (bedrock-model) :none)]
    (t/is (= "call_qwen_opaque_tool-id"
             (get-in messages [0 :content 0 :toolUse :toolUseId])))
    (t/is (= "call_qwen_opaque_tool-id"
             (get-in messages [1 :content 0 :toolResult :toolUseId])))))

(t/deftest test-bedrock-messages-images
  (let [msgs [{:role :user :content [{:type :image :mime-type "image/png" :data "AAAA"}]}
              {:role :tool :content [{:content "" :tool_use_id "t1"}]
               :tool-name "read" :images [{:mime-type "image/jpeg" :data "BBBB"}]}]]
    (t/is (= [{:role "user"
               :content [{:image {:format "png" :source {:bytes "AAAA"}}}]}
              {:role "user"
               :content [{:toolResult {:toolUseId "t1"
                                       :content [{:image {:format "jpeg" :source {:bytes "BBBB"}}}]
                                       :status "success"}}]}]
             (first (@#'bedrock/bedrock-messages msgs (bedrock-model) :none))))
    (t/testing "unknown image type throws"
      (t/is (thrown? Exception
                     (@#'bedrock/bedrock-messages
                      [{:role :user :content [{:type :image :mime-type "image/tiff" :data "x"}]}]
                      (bedrock-model) :none))))))

(t/deftest test-bedrock-endpoint-url
  (with-redefs [shared/getenv (fn [_] nil)]
    (t/is (= {:url "https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-sonnet-4-5-20250929-v1:0/converse-stream"
              :region "us-east-1"}
             (@#'bedrock/bedrock-endpoint-url "https://bedrock-runtime.us-east-1.amazonaws.com" "anthropic.claude-sonnet-4-5-20250929-v1:0")))
    (t/is (= {:url "https://bedrock-runtime.eu-central-1.amazonaws.com/model/eu.claude-opus/converse-stream"
              :region "eu-central-1"}
             (@#'bedrock/bedrock-endpoint-url "https://bedrock-runtime.eu-central-1.amazonaws.com" "eu.claude-opus"))))
  (t/testing "configured region overrides the endpoint region"
    (with-redefs [shared/getenv (fn [k] (when (= k "AWS_REGION") "eu-west-1"))]
      (t/is (= {:url "https://bedrock-runtime.eu-west-1.amazonaws.com/model/m/converse-stream"
                :region "eu-west-1"}
               (@#'bedrock/bedrock-endpoint-url "https://bedrock-runtime.us-east-1.amazonaws.com" "m")))))
  (t/testing "custom endpoints always win"
    (with-redefs [shared/getenv (fn [_] nil)]
      (t/is (= {:url "https://my-vpc.example/model/m/converse-stream" :region "us-east-1"}
               (@#'bedrock/bedrock-endpoint-url "https://my-vpc.example" "m"))))))

(t/deftest test-bedrock-additional-fields
  (t/is (= {:thinking {:type "adaptive" :display "summarized"}
            :output_config {:effort "high"}}
           (@#'bedrock/bedrock-additional-fields (bedrock-model :id "anthropic.claude-opus-4-6-v1:0") :high)))
  (t/is (= {:thinking {:type "enabled" :budget_tokens 8192 :display "summarized"}
            :anthropic_beta ["interleaved-thinking-2025-05-14"]}
           (@#'bedrock/bedrock-additional-fields (bedrock-model) :medium)))
  (t/is (nil? (@#'bedrock/bedrock-additional-fields (bedrock-model) nil)) "off → no thinking")
  (t/is (nil? (@#'bedrock/bedrock-additional-fields (bedrock-model :id "amazon.nova-lite-v1:0" :name "Nova") :high))
        "non-claude model → no thinking"))

(t/deftest test-bedrock-tool-config
  (let [tools [(tools/make-tool :name "read" :label "Read" :description "Read a file"
                                :params {:path {:type :string :description "path"}})]
        config (@#'bedrock/bedrock-tool-config tools false)]
    (t/is (= {:tools [{:toolSpec {:name "read" :description "Read a file"
                                  :inputSchema {:json (:parameters (first tools))}}}]
              :toolChoice {:auto {}}}
             config))
    (t/is (nil? (@#'bedrock/bedrock-tool-config nil false)))
    (t/testing "strict-resolving tools get the strictified schema and :strict true"
      (let [strict-tool (assoc (first tools)
                               :constrained-sampling {:type :json-schema :strict :prefer})
            config (@#'bedrock/bedrock-tool-config [strict-tool] true)]
        (t/is (= true (get-in config [:tools 0 :toolSpec :strict])))
        (t/is (= ["path"] (get-in config [:tools 0 :toolSpec :inputSchema :json "required"])))))))

;; bedrock e2e frame builder (the sse test's private helpers are not
;; importable — kept here, mirroring the AWS event-stream framing)
(defn- bedrock-e2e-u32 [n]
  [(bit-and (bit-shift-right n 24) 0xFF) (bit-and (bit-shift-right n 16) 0xFF)
   (bit-and (bit-shift-right n 8) 0xFF) (bit-and n 0xFF)])
(defn- bedrock-e2e-u16 [n] [(bit-and (bit-shift-right n 8) 0xFF) (bit-and n 0xFF)])
(defn- bedrock-e2e-crc [bytes]
  (hash/crc32 bytes))
(defn- bedrock-e2e-ba [ints] (byte-array (map #(bit-and (long %) 0xFF) ints)))
(defn- bedrock-e2e-header [name value]
  (let [nb (map int (.getBytes name "UTF-8"))
        vb (map int (.getBytes value "UTF-8"))]
    (concat [(count nb)] nb [7] (bedrock-e2e-u16 (count vb)) vb)))
(defn- bedrock-e2e-frame [event-type payload]
  (let [payload-bytes (map int (.getBytes payload "UTF-8"))
        hdrs (concat (bedrock-e2e-header ":message-type" "event")
                     (bedrock-e2e-header ":event-type" event-type))
        total (+ 12 (count hdrs) (count payload-bytes) 4)
        prelude (bedrock-e2e-ba (concat (bedrock-e2e-u32 total) (bedrock-e2e-u32 (count hdrs))
                                        (bedrock-e2e-u32 0)))
        prelude-crc (bedrock-e2e-crc (java.util.Arrays/copyOfRange prelude 0 8))
        prelude-full (bedrock-e2e-ba (concat (bedrock-e2e-u32 total) (bedrock-e2e-u32 (count hdrs))
                                             (bedrock-e2e-u32 prelude-crc)))
        msg-body (mapv #(bit-and (long %) 0xFF)
                       (concat (mapv int prelude-full) hdrs payload-bytes))
        msg-crc (bedrock-e2e-crc (bedrock-e2e-ba msg-body))]
    (bedrock-e2e-ba (concat msg-body (bedrock-e2e-u32 msg-crc)))))
(defn- bedrock-e2e-frames [& frames]
  (bedrock-e2e-ba (mapcat #(mapv int %) frames)))

(t/deftest ^:slow test-llm-bedrock-stream-end-to-end
  ;; Full send-message → SigV4-signed ConverseStream request → binary
  ;; event-stream response → events/usage path, over a local socket (the
  ;; AWS example keys make the signature deterministic).
  (m/load-catalogs! nil)
  (with-redefs [aws-sigv4/getenv (fn [k] (case k "AWS_ACCESS_KEY_ID" "AKIDEXAMPLE"
                                               "AWS_SECRET_ACCESS_KEY" "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
                                               nil))]
    (let [ss (java.net.ServerSocket. 0)
          port (.getLocalPort ss)
          request-body (atom nil)
          request-headers (atom {})
          _ (doto (Thread.
                   (fn []
                     (try
                       (let [s (.accept ss)
                             rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                             clen (atom 0)
                             req-headers (atom {})
                             _ (loop []
                                 (let [line (.readLine rdr)]
                                   (when (seq (or line ""))
                                     (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                       (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                     (when-let [colon (str/index-of line ":")]
                                       (reset! req-headers
                                               (assoc @req-headers
                                                      (str/trim (subs line 0 colon))
                                                      (str/trim (subs line (inc colon))))))
                                     (recur))))
                             _ (reset! request-headers @req-headers)
                             body-sb (StringBuilder.)
                             _ (loop [n 0]
                                 (if (< n @clen)
                                   (let [buf (char-array (- @clen n))
                                         m (.read rdr buf)]
                                     (when (pos? m)
                                       (.append body-sb (String. buf 0 m))
                                       (recur (+ n m))))
                                   nil))
                             _ (reset! request-body (str body-sb))
                             out (.getOutputStream s)
                             frames (bedrock-e2e-frames
                                     (bedrock-e2e-frame "contentBlockDelta"
                                                        "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"hello\"}}")
                                     (bedrock-e2e-frame "messageStop" "{\"stopReason\":\"end_turn\"}")
                                     ;; real ConverseStream sends the usage metadata frame
                                     ;; AFTER messageStop (the trailing-usage ordering the
                                     ;; deferred-done fix in responses-events-handler exists
                                     ;; for)
                                     (bedrock-e2e-frame "metadata"
                                                        "{\"usage\":{\"inputTokens\":10,\"outputTokens\":5,\"totalTokens\":15,\"cacheReadInputTokens\":2,\"cacheWriteInputTokens\":1}}"))]
                         (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                     "Content-Type: application/vnd.amazon.eventstream\r\n"
                                                     "Content-Length: " (count frames) "\r\n\r\n")))
                         (.write out frames)
                         (.flush out)
                         (.close s))
                       (catch Exception _ nil))))
              (.setDaemon true)
              (.start))
          text (atom "")
          done-reason (atom nil)
          usage (atom nil)
          errors (atom [])
          fut (llm/send-message {:provider :amazon-bedrock
                                 :model "anthropic.claude-sonnet-4-5-20250929-v1:0"
                                 :base-url (str "http://localhost:" port)
                                 :messages [{:role :system :content [{:type :text :text "SYSTEM"}]}
                                            {:role :user :content [{:type :text :text "hi"}]}]
                                 :session-id "sess-1"
                                 :thinking :medium
                                 :on-text (fn [t] (swap! text str t))
                                 :on-done (fn [r] (reset! done-reason r))
                                 :on-usage (fn [u] (reset! usage u))
                                 :on-error (fn [e] (swap! errors conj e))})]
      (try
        @fut
        (t/is (= [] @errors) (str "no stream errors: " @errors))
        (t/is (= "hello" @text))
        (t/is (= :stop @done-reason))
        (t/is (= {:input_tokens 10 :output_tokens 5 :total_tokens 15
                  :cache_read_input_tokens 2 :cache_write_input_tokens 1
                  :cost {:input 2.1E-5 :output 7.5E-5 :cache-read 6.0E-7
                         :cache-write 3.75E-6 :total 1.0034999999999999E-4}}
                 @usage)
              "bedrock usage gains the per-message cost (sonnet 4.5 rates)")
        (t/testing "the wire payload is ConverseStream-shaped with the system prompt"
          (let [payload (json/parse-string @request-body true)]
            (t/is (= "anthropic.claude-sonnet-4-5-20250929-v1:0" (:modelId payload)))
            (t/is (= [{:text "SYSTEM"} {:cachePoint {:type "default"}}] (:system payload))
                  "system prompt + cache point ride in :system")
            (t/is (= 64000 (get-in payload [:inferenceConfig :maxTokens])))
            (t/is (= {:type "enabled" :budget_tokens 8192 :display "summarized"}
                     (get-in payload [:additionalModelRequestFields :thinking]))
                  "budget-based thinking for a non-adaptive claude")
            (t/is (= ["interleaved-thinking-2025-05-14"]
                     (:anthropic_beta (:additionalModelRequestFields payload))))))
        (t/testing "the request is SigV4-signed with content-type in the signature"
          (t/is (str/starts-with? (get @request-headers "Authorization")
                                  "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"))
          (t/is (= "application/json" (get @request-headers "Content-Type")))
          (t/is (str/includes? (get @request-headers "Authorization")
                               "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date")))
        (finally
          (.close ss))))))

(t/deftest ^:slow test-llm-mistral-stream-end-to-end
  ;; Full send-message → Mistral chat-completions request (normalized
  ;; tool-call ids, x-affinity, prompt_cache_key) → SSE response → events.
  (m/load-catalogs! nil)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-body (atom nil)
        request-headers (atom {})
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           clen (atom 0)
                           req-headers (atom {})
                           _ (loop []
                               (let [l (.readLine rdr)]
                                 (when (seq (or l ""))
                                   (when (str/starts-with? (str/lower-case (or l "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs l 15)))))
                                   (when-let [colon (str/index-of l ":")]
                                     (reset! req-headers (assoc @req-headers
                                                                (str/trim (subs l 0 colon))
                                                                (str/trim (subs l (inc colon))))))
                                   (recur))))
                           _ (reset! request-headers @req-headers)
                           sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n)) m (.read rdr buf)]
                                   (when (pos? m) (.append sb buf 0 m) (recur (+ n m))))
                                 nil))
                           _ (reset! request-body (str sb))
                           out (.getOutputStream s)
                           stream-body (str "data: {\"data\":{\"choices\":[{\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"},{\"type\":\"thinking\",\"thinking\":[{\"type\":\"text\",\"text\":\"hmm\"}]}]}}]}}\n\n"
                                            "data: {\"data\":{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15},\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}}\n\n"
                                            "data: [DONE]\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        errors (atom [])
        text (atom "")
        thinking (atom "")
        done-reason (atom nil)
        usage (atom nil)
        fut (llm/send-message {:provider :mistral
                               :model "mistral-medium-3.5"
                               :api-key "k"
                               :base-url (str "http://localhost:" port)
                               :session-id "sess-9"
                               :thinking :medium
                               :messages [{:role :assistant
                                           :content [{:type :text :text "prev"}]
                                           :tool-calls [{:id "call-abc-123" :name "read"
                                                         :arguments {:path "/x"}}]}
                                          {:role :tool :content [{:content "done"
                                                                  :tool_use_id "call-abc-123"}]
                                           :tool-name "read"}
                                          {:role :user :content [{:type :text :text "hi"}]}]
                               :on-text (fn [t] (swap! text str t))
                               :on-thinking (fn [t] (swap! thinking str t))
                               :on-done (fn [r] (reset! done-reason r))
                               :on-usage (fn [u] (reset! usage u))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hi" @text))
      (t/is (= "hmm" @thinking))
      (t/is (= :stop @done-reason))
      (t/is (= 15 (:total_tokens @usage)) "usage rides through")
      (t/testing "the wire request is Mistral-shaped"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= "mistral-medium-3.5" (:model payload)))
          (t/is (= "sess-9" (:prompt_cache_key payload)))
          (t/is (= "sess-9" (get @request-headers "x-affinity")))
          (t/is (= "high" (:reasoning_effort payload))
                "mistral-medium-3.5 uses reasoning_effort (tlm ?? high)")
          (t/is (= 262144 (:max_tokens payload)))
          ;; the session tool-call id is normalized to 9 alphanumeric chars
          ;; on the wire, and the tool result carries the SAME normalized id
          (let [tool-msgs (filter #(= "tool" (:role %)) (:messages payload))
                assistant-msg (first (filter #(= "assistant" (:role %)) (:messages payload)))]
            (t/is (= 9 (count (get-in assistant-msg [:tool_calls 0 :id]))))
            (t/is (re-matches #"[a-zA-Z0-9]{9}" (get-in assistant-msg [:tool_calls 0 :id])))
            (t/is (= (get-in assistant-msg [:tool_calls 0 :id])
                     (get-in (first tool-msgs) [:tool_call_id]))
                  "tool result correlates via the same normalized id"))))
      (finally
        (.close ss)))))

(t/deftest ^:slow test-llm-google-stream-end-to-end
  ;; Full send-message → Google Generative Language request (x-goog-api-key)
  ;; → Google SSE response → events/usage. The twin of the vertex e2e below:
  ;; google-request's success path previously had no end-to-end coverage
  ;; (only its payload helpers were unit-tested).
  (m/load-catalogs! nil)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-url (atom nil)
        request-headers (atom {})
        request-body (atom nil)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           first-line (.readLine rdr)
                           clen (atom 0)
                           req-headers (atom {})
                           _ (loop []
                               (let [l (.readLine rdr)]
                                 (when (seq (or l ""))
                                   (when (str/starts-with? (str/lower-case (or l "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs l 15)))))
                                   (when-let [colon (str/index-of l ":")]
                                     (reset! req-headers (assoc @req-headers
                                                                (str/trim (subs l 0 colon))
                                                                (str/trim (subs l (inc colon))))))
                                   (recur))))
                           _ (reset! request-headers @req-headers)
                           sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n)) m (.read rdr buf)]
                                   (when (pos? m) (.append sb buf 0 m) (recur (+ n m))))
                                 nil))
                           _ (reset! request-url first-line)
                           _ (reset! request-body (str sb))
                           out (.getOutputStream s)
                           stream-body (str "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}\n\n"
                                            "data: {\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5,\"totalTokenCount\":15,\"cachedContentTokenCount\":2},\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        errors (atom [])
        text (atom "")
        done-reason (atom nil)
        usage (atom nil)
        fut (llm/send-message {:provider :google
                               :model "gemini-3.1-pro-preview"
                               :api-key "gk"
                               :base-url (str "http://localhost:" port)
                               :thinking :high
                               :messages [{:role :user :content [{:type :text :text "hi"}]}]
                               :on-text (fn [t] (swap! text str t))
                               :on-done (fn [r] (reset! done-reason r))
                               :on-usage (fn [u] (reset! usage u))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hi" @text))
      (t/is (= :stop @done-reason))
      (t/is (= 8 (:input @usage)) "usage: input excludes cached tokens")
      (t/testing "the wire request is Google Generative Language-shaped"
        ;; the :base-url override wins (the /models/<id>:streamGenerateContent
        ;; path construction is unit-tested separately); the request still
        ;; sends the body, the key, and a JSON content type
        (t/is (str/starts-with? @request-url "POST / HTTP")
              (str "request line: " @request-url))
        (t/is (= "gk" (get @request-headers "x-goog-api-key")))
        (t/is (= "application/json" (get @request-headers "Content-Type")))
        (let [payload (json/parse-string @request-body true)]
          (t/is (= [{:parts [{:text "hi"}] :role "user"}] (:contents payload)))
          (t/is (= {:maxOutputTokens 65536
                    :thinkingConfig {:includeThoughts true :thinkingLevel "HIGH"}}
                   (:generationConfig payload))
                "gemini-3.1-pro at :high → includeThoughts + thinkingLevel HIGH")))
      (finally
        (.close ss)))))

(t/deftest ^:slow test-llm-vertex-stream-end-to-end
  ;; Full send-message → Vertex request (project/location URL, x-goog-api-key)
  ;; → Google SSE response → events.
  (m/load-catalogs! nil)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-url (atom nil)
        request-body (atom nil)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           first-line (.readLine rdr)
                           clen (atom 0)
                           _ (loop []
                               (let [l (.readLine rdr)]
                                 (when (seq (or l ""))
                                   (when (str/starts-with? (str/lower-case (or l "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs l 15)))))
                                   (recur))))
                           sb (StringBuilder.)
                           _ (loop [n 0]
                               (if (< n @clen)
                                 (let [buf (char-array (- @clen n)) m (.read rdr buf)]
                                   (when (pos? m) (.append sb buf 0 m) (recur (+ n m))))
                                 nil))
                           _ (reset! request-url first-line)
                           _ (reset! request-body (str sb))
                           out (.getOutputStream s)
                           stream-body (str "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}\n\n"
                                            "data: {\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5,\"totalTokenCount\":15,\"cachedContentTokenCount\":2},\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        errors (atom [])
        text (atom "")
        done-reason (atom nil)
        usage (atom nil)
        fut (with-redefs [shared/getenv (fn [k] (case k "GOOGLE_CLOUD_PROJECT" "proj"
                                                      "GCLOUD_PROJECT" nil
                                                      "GOOGLE_CLOUD_LOCATION" "us-central1"
                                                      nil))]
              (llm/send-message {:provider :google-vertex
                                 :model "gemini-3.1-pro-preview"
                                 :api-key "vk"
                                 :base-url (str "http://localhost:" port)
                                 :thinking :high
                                 :messages [{:role :user :content [{:type :text :text "hi"}]}]
                                 :on-text (fn [t] (swap! text str t))
                                 :on-done (fn [r] (reset! done-reason r))
                                 :on-usage (fn [u] (reset! usage u))
                                 :on-error (fn [e] (swap! errors conj e))}))]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hi" @text))
      (t/is (= :stop @done-reason))
      (t/is (= 8 (:input @usage)) "usage: input excludes cached tokens")
      (t/testing "the wire request is Vertex-shaped"
        ;; the :base-url override wins (project/location URL construction is
        ;; unit-tested separately); the request still sends the body + the
        ;; x-goog-api-key auth (the mock captures headers implicitly)
        (t/is (str/starts-with? @request-url "POST / HTTP"))
        (let [payload (json/parse-string @request-body true)]
          (t/is (= [{:parts [{:text "hi"}] :role "user"}] (:contents payload)))
          (t/is (= {:maxOutputTokens 65536
                    :thinkingConfig {:includeThoughts true :thinkingLevel "HIGH"}}
                   (:generationConfig payload))
                "gemini-3.1-pro at :high → includeThoughts + thinkingLevel HIGH")))
      (finally
        (.close ss)))))

(t/deftest test-llm-max-tokens-override
  ;; pi: compaction caps the summarization output at 0.8 * reserveTokens —
  ;; the per-call :max-tokens opt must reach every API builder via the
  ;; model record.
  (m/load-catalogs! nil)
  (let [request (atom nil)]
    (with-redefs [auth/resolve-provider-auth (fn [_] {:api-key "test-key"})
                  responses/responses-request
                  (fn [opts]
                    (reset! request opts)
                    (future nil))]
      @(llm/send-message {:provider :openai
                          :model "gpt-5.4"
                          :messages []
                          :max-tokens 13107}))
    (t/is (= 13107 (:max-tokens (:model-record @request)))
          "per-call max-tokens overrides the model's configured value")
    (t/is (not= 13107 (:max-tokens (m/get-model :openai "gpt-5.4")))
          "the catalog model record is untouched")))

(t/deftest test-llm-max-tokens-absent-keeps-model-value
  (m/load-catalogs! nil)
  (let [request (atom nil)]
    (with-redefs [auth/resolve-provider-auth (fn [_] {:api-key "test-key"})
                  responses/responses-request
                  (fn [opts]
                    (reset! request opts)
                    (future nil))]
      @(llm/send-message {:provider :openai
                          :model "gpt-5.4"
                          :messages []}))
    (t/is (= (:max-tokens (m/get-model :openai "gpt-5.4"))
             (:max-tokens (:model-record @request)))
          "no override → the model's configured max-tokens")))
