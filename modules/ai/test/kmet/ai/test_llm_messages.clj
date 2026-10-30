(ns kmet.ai.test-llm-messages
  "Message conversion for the wire: image blocks to provider parts, and the
   agent roles the app flattens first (kmet.app.loop.message/to-llm-messages,
   pi: convertToLlm — bash executions and custom messages reach a builder as
   user messages)."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.app.loop.message :as message]
            [kmet.app.retry :as retry]
            [kmet.libs.http :as libs-http]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.api.anthropic-messages :as anthropic]
            [kmet.ai.api.google-generative-ai :as google]
            [kmet.ai.api.openai-responses :as responses]
            [kmet.ai.models :as m]))

;; ─── Image block conversion ───────────────────────────────────────────────

(t/deftest test-llm-openai-image-conversion
  (let [msgs [{:role :user
               :content [{:type :text :text "look"}
                         {:type :image :data "AA" :mime-type "image/png"}]}]
        converted (@#'shared/openai-messages msgs)]
    (t/is (= [{:type "text" :text "look"}
              {:type "image_url"
               :image_url {:url "data:image/png;base64,AA"}}]
             (:content (first converted)))
          "image blocks convert to OpenAI image_url blocks")))

(t/deftest test-llm-openai-completions-cross-provider-tool-id
  (let [id "call_qwen|V7tZ6kGdR7aeKl14cUq9F_cRTzluuKfD1RULCNcPpxaaOuVPvzVouaxmjWg0G9H"
        msgs [{:role :assistant :content []
               :tool-calls [{:id id :name "bash" :arguments {}}]}
              {:role :tool :content [{:type :tool_result :tool_use_id id :content "done"}]}]
        converted (@#'shared/openai-messages msgs :opencode)
        assistant-id (-> converted first :tool_calls first :id)
        result-id (-> converted second :tool_call_id)]
    (t/is (= assistant-id result-id))
    (t/is (<= (count assistant-id) 40))
    (t/is (re-matches #"[a-zA-Z0-9_-]+" assistant-id))))

(t/deftest test-llm-openai-account-alias-target
  (let [long-id (apply str (repeat 60 "a"))
        model {:id "m1" :provider :openai#work :base-provider :openai}
        msgs [{:role :assistant :content [] :provider :openai#work :model "m1"
               :thinking "reasoned"
               :tool-calls [{:id long-id :name "bash" :arguments {}}]}
              {:role :tool :content [{:type :tool_result :tool_use_id long-id
                                      :content "done"}]}]
        as-record (@#'shared/openai-messages msgs model "m1")
        as-keyword (@#'shared/openai-messages msgs :openai#work "m1")]
    (t/is (= {:provider :openai#work :family :openai}
             (@#'shared/message-target model))
          "a model record carries its own provenance and family")
    (t/is (= {:provider :openai#work :family :openai#work}
             (@#'shared/message-target :openai#work))
          "a bare provider keyword is its own family")
    (t/is (= 40 (count (-> as-record first :tool_calls first :id)))
          "an alias uses its base provider's tool-call id shaping")
    (t/is (= long-id (-> as-keyword first :tool_calls first :id))
          "without the record there is no family to consult")
    (t/is (= 40 (count (-> as-record second :tool_call_id))))
    (t/is (= "reasoned" (:reasoning_content (first as-record)))
          "provenance stays the alias id, so its own thinking replays")))

(t/deftest test-llm-anthropic-tool-id-normalization
  (let [id "call_qwen|opaque/tool-id"
        converted (@#'anthropic/anthropic-messages
                   [{:role :assistant :content []
                     :tool-calls [{:id id :name "bash" :arguments {}}]}])]
    (t/is (= "call_qwen_opaque_tool-id"
             (-> converted first :content first :id)))
    (t/is (<= (count (-> converted first :content first :id)) 64))))

(t/deftest test-llm-anthropic-image-conversion
  (let [msgs [{:role :user
               :content [{:type :text :text "look"}
                         {:type :image :data "AA" :mime-type "image/png"}]}]
        converted (@#'anthropic/anthropic-messages msgs)]
    (t/is (= [{:type "text" :text "look"}
              {:type "image"
               :source {:type "base64" :media_type "image/png" :data "AA"}}]
             (:content (first converted)))
          "image blocks convert to Anthropic image blocks")))

(t/deftest test-llm-anthropic-empty-messages-dropped
  ;; pi convertMessages: blank text blocks are dropped and a message left
  ;; without sendable content is skipped — Anthropic rejects empty content
  ;; arrays, so a recorded empty completion (clean stream, zero blocks) must
  ;; not 400 every later turn of a resumed session.
  (let [converted (@#'anthropic/anthropic-messages
                   [;; empty assistant completion (the recorded failure mode)
                    {:role :assistant :content []}
                    ;; whitespace-only assistant message
                    {:role :assistant :content [{:type :text :text "   "}]}
                    ;; whitespace-only user text is dropped, but the image survives
                    {:role :user
                     :content [{:type :text :text "  "}
                               {:type :image :data "AA" :mime-type "image/png"}]}
                    ;; real messages pass through untouched
                    {:role :user :content [{:type :text :text "hi"}]}
                    {:role :assistant :content []
                     :tool-calls [{:id "tc1" :name "bash" :arguments {}}]}])]
    (t/is (= [{:role "user"
               :content [{:type "image"
                          :source {:type "base64" :media_type "image/png" :data "AA"}}]}
              {:role "user" :content "hi"}
              {:role "assistant"
               :content [{:type "tool_use"
                          :id "tc1"
                          :name "bash"
                          :input {}}]}]
             converted)
          "empty/blank assistant entries are dropped, images survive, tool calls keep the message")))

(t/deftest test-llm-custom-role-maps-to-user
  ;; G10: custom messages (from custom_message entries) are sent as user
  ;; messages (pi: convertToLlm custom→user)
  (let [msgs (message/to-llm-messages
              [{:role :custom
                :custom-type :note
                :content [{:type :text :text "hello from an extension"}]}])
        openai (@#'shared/openai-messages msgs)
        openai-reasoning (@#'shared/openai-messages-with-reasoning msgs)
        anthropic (@#'anthropic/anthropic-messages msgs)
        [google _] (@#'google/google-messages msgs {:id "m" :name "M"})]
    (t/is (= "user" (:role (first openai))))
    (t/is (= "user" (:role (first openai-reasoning))))
    (t/is (= "user" (:role (first anthropic))))
    (t/is (= "user" (:role (first google))))
    (t/is (= "hello from an extension" (:content (first openai)))
          "content preserved (openai-content returns plain text without images)")
    (t/is (= "hello from an extension" (:text (first (:parts (first google)))))
          "google content preserved"))
  ;; string content is normalized by the context projection (context-messages),
  ;; but the converters accept string content via the shared content helpers
  (let [msgs (message/to-llm-messages
              [{:role :custom :custom-type :note :content "plain string"}])
        openai (@#'shared/openai-messages msgs)]
    (t/is (= "user" (:role (first openai)))))
  (t/testing "custom content keeps its images once it is a user message"
    (let [content (fn [c] (:content (first (message/to-llm-messages
                                            [{:role :custom :custom-type :note :content c}]))))
          parts (responses/responses-user-content
                 (content [{:type :text :text "look"}
                           {:type :image :data "AA" :mime-type "image/png"}]))]
      (t/is (= ["input_text" "input_image"] (mapv :type parts))
            "the responses builder shapes them like any user image")
      (t/is (= "data:image/png;base64,AA" (:image_url (second parts))))
      (t/is (= [{:type "input_text" :text "look"}]
               (responses/responses-user-content (content "look")))
            "text-only custom content still collapses to one input_text"))))

(t/deftest test-llm-tool-result-images-conversion
  (let [msgs [{:role :tool
               :content [{:type :tool_result :tool_use_id "t1" :content "saw it"}]
               :images [{:data "AA" :mime-type "image/png"}]}]
        openai (@#'shared/openai-messages msgs)
        anthropic (@#'anthropic/anthropic-messages msgs)]
    (t/is (= [{:type "text" :text "saw it"}
              {:type "image_url"
               :image_url {:url "data:image/png;base64,AA"}}]
             (:content (first openai)))
          "tool-result :images convert to OpenAI image_url blocks")
    (t/is (= [{:type "tool_result"
               :tool_use_id "t1"
               :content [{:type "text" :text "saw it"}
                         {:type "image"
                          :source {:type "base64" :media_type "image/png" :data "AA"}}]}]
             (:content (first anthropic)))
          "tool-result :images convert to Anthropic image blocks")))

(t/deftest test-llm-no-images-backward-compat
  (let [msgs [{:role :user :content [{:type :text :text "hi"}]}]
        openai (@#'shared/openai-messages msgs)
        anthropic (@#'anthropic/anthropic-messages msgs)]
    (t/is (= "hi" (:content (first openai)))
          "text-only messages keep string content for OpenAI")
    (t/is (= "hi" (:content (first anthropic)))
          "text-only messages keep string content for Anthropic")))

(t/deftest test-llm-assistant-thinking-roundtrip
  ;; pi round-trips thinking only for same-provider-same-model messages and
  ;; degrades everything else: assistant messages with :thinking send it back
  ;; as reasoning_content (DeepSeek thinking mode) only when the recorded
  ;; provenance matches the request's target model — a mid-session /model
  ;; switch must not feed another model's chain-of-thought back as if it were
  ;; the new model's own reasoning (DeepSeek-class models derail into
  ;; re-running their last tool call on such transcripts).
  (let [own [{:role :assistant
              :content [{:type :text :text "answer"}]
              :thinking "let me think\nabout it"
              :provider :commandcode :model "deepseek/deepseek-v4-flash"}]
        openai (@#'shared/openai-messages own :commandcode "deepseek/deepseek-v4-flash")
        reasoning (@#'shared/openai-messages-with-reasoning own :commandcode "deepseek/deepseek-v4-flash")]
    (t/is (= "let me think\nabout it" (:reasoning_content (first openai)))
          "same-model thinking round-trips")
    (t/is (= "let me think\nabout it" (:reasoning_content (first reasoning)))
          "with-reasoning variant uses same-model thinking"))
  ;; cross-model / legacy-unstamped messages never leak their CoT; the
  ;; with-reasoning variant pads the required empty field instead
  (doseq [foreign [{:role :assistant
                    :content [{:type :text :text "answer"}]
                    :thinking "CoT produced by hetzner Qwen"
                    :provider :hetzner :model "Qwen3.8-27B"}
                   {:role :assistant
                    :content [{:type :text :text "answer"}]
                    :thinking "recorded before kmet stamped provenance"}]
          :let [openai (@#'shared/openai-messages [foreign] :commandcode "deepseek/deepseek-v4-flash")
                reasoning (@#'shared/openai-messages-with-reasoning [foreign] :commandcode "deepseek/deepseek-v4-flash")]]
    (t/is (nil? (:reasoning_content (first openai)))
          "foreign/unstamped thinking is not replayed")
    (t/is (= "" (:reasoning_content (first reasoning)))
          "with-reasoning pads the empty field for deepseek thinking mode"))
  ;; no target identity given (legacy call shape): nothing is replayed
  (let [msgs [{:role :assistant
               :content [{:type :text :text "answer"}]
               :thinking "orphan CoT"}]]
    (t/is (nil? (:reasoning_content (first (@#'shared/openai-messages msgs)))))
    (t/is (= "" (:reasoning_content
                 (first (@#'shared/openai-messages-with-reasoning msgs))))))
  ;; messages without thinking keep the empty-field compat for
  ;; requires-reasoning-content-on-assistant-messages providers
  (let [msgs [{:role :assistant :content [{:type :text :text "answer"}]}]]
    (t/is (nil? (:reasoning_content (first (@#'shared/openai-messages msgs)))))
    (t/is (= "" (:reasoning_content (first (@#'shared/openai-messages-with-reasoning msgs))))))
  ;; an explicit fill replaces the empty placeholder for endpoints that turn
  ;; the field into a thinking block and reject an empty one — including a
  ;; same-model message whose thinking is empty
  (let [msgs [{:role :assistant
               :content [{:type :text :text "answer"}]
               :thinking "foreign CoT"
               :provider :hetzner :model "Qwen3.8-27B"}
              {:role :assistant
               :content [{:type :text :text "answer"}]
               :provider :commandcode :model "stealth/cat-jam:free"}]
        reasoning (@#'shared/openai-messages-with-reasoning
                   msgs :commandcode "stealth/cat-jam:free" " ")]
    (t/is (= [" " " "] (mapv :reasoning_content reasoning))
          "the fill applies where no same-model reasoning exists")))

(t/deftest test-llm-empty-assistant-dropped
  ;; pi: "some providers require either content or tool_calls, but not none" —
  ;; an empty assistant message (e.g. a resumed session whose last turn ended
  ;; with only reasoning, or an aborted response that got no content) must be
  ;; skipped, not sent as content "" ("Invalid assistant message: content or
  ;; tool_calls must be set")
  (let [msgs [{:role :user :content [{:type :text :text "hi"}]}
              {:role :assistant :content []}
              {:role :assistant :content [] :thinking "only reasoning"}
              {:role :assistant :content [{:type :text :text "answer"}]}]
        openai (@#'shared/openai-messages msgs)
        reasoning (@#'shared/openai-messages-with-reasoning msgs)
        roles (mapv :role openai)
        roles-reasoning (mapv :role reasoning)]
    (t/is (= ["user" "assistant"] roles)
          "empty assistant messages are dropped")
    (t/is (= "answer" (:content (last openai)))
          "non-empty assistant messages are kept")
    (t/is (= ["user" "assistant"] roles-reasoning)
          "with-reasoning variant drops empty assistant messages too")
    (t/is (= "" (:reasoning_content (last reasoning)))
          "with-reasoning variant keeps the empty-field compat on kept messages"))
  ;; a tool-call assistant message with no text is kept (content stays unset)
  (let [msgs [{:role :assistant
               :content []
               :tool-calls [{:id "t1" :name "bash" :arguments {}}]}]
        openai (@#'shared/openai-messages msgs)
        reasoning (@#'shared/openai-messages-with-reasoning msgs)]
    (t/is (= "t1" (-> openai first :tool_calls first :id)))
    (t/is (nil? (:content (first openai)))
          "content is not set to empty string")
    (t/is (= "t1" (-> reasoning first :tool_calls first :id)))
    (t/is (= "" (:reasoning_content (first reasoning))))))

;; ─── Bash result conversion (pi: convertToLlm bashExecution) ──────────────

(t/deftest test-llm-bash-conversion
  (let [msgs (message/to-llm-messages
              [{:role :bash :command "git st" :output "clean\n" :exit-code 0
                :exclude-from-context? false}
               {:role :bash :command "git st" :output "clean\n" :exit-code 0
                :exclude-from-context? true}])
        openai (@#'shared/openai-messages msgs)
        reasoning (@#'shared/openai-messages-with-reasoning msgs)
        anthropic (@#'anthropic/anthropic-messages msgs)]
    (doseq [converted [openai reasoning anthropic]]
      (t/is (= 1 (count converted)) "excluded bash entries are dropped")
      (t/is (= "user" (:role (first converted))) "bash entries become user messages")
      (let [text (:content (first converted))]
        (t/is (str/includes? text "Ran `git st`"))
        (t/is (str/includes? text "clean"))))))

(t/deftest test-llm-bash-conversion-format
  ;; pi: bashExecutionToText shape — output block, exit code, truncation note
  (let [msgs (message/to-llm-messages
              [{:role :bash :command "false" :output "" :exit-code 1
                :exclude-from-context? false
                :truncated true :full-output-path "/tmp/out"}])
        openai (@#'shared/openai-messages msgs)
        text (:content (first openai))]
    (t/is (str/includes? text "Ran `false`"))
    (t/is (str/includes? text "(no output)"))
    (t/is (str/includes? text "Command exited with code 1"))
    (t/is (str/includes? text "[Output truncated. Full output: /tmp/out]"))))

(t/deftest test-llm-bash-cancelled-no-exit-code
  (let [msgs (message/to-llm-messages
              [{:role :bash :command "sleep 10" :output "" :exit-code nil
                :cancelled true :exclude-from-context? false}])
        openai (@#'shared/openai-messages msgs)
        text (:content (first openai))]
    (t/is (str/includes? text "(command cancelled)"))
    (t/is (not (str/includes? text "Command exited with code")))))

(t/deftest test-llm-transport-error-message
  ;; A connect-time failure on this JDK surfaces as a ConnectException with a
  ;; nil message — transport-error-message must still mark it retryable
  ;; (pi: undici always reports transport failures as "fetch failed"), or
  ;; auto-retry on network errors silently dies.
  (let [te @#'libs-http/transport-error-message]
    (t/is (= "network error: ConnectException"
             (te (java.net.ConnectException. nil))))
    (t/is (= "network error: request timed out"
             (te (java.net.http.HttpTimeoutException. "request timed out"))))
    (t/is (= "network error: Connection reset"
             (te (java.net.SocketException. "Connection reset"))))
    (t/is (= "network error: UnknownHostException"
             (te (java.net.UnknownHostException. nil))))
    ;; HTTP/2 RST_STREAM surfaces as a plain IOException (java.net.http
    ;; builds the message from the frame's error code) — same class of
    ;; transport reset as SocketException "Connection reset", so it gets
    ;; the same stable retryable token.
    (t/is (= "network error: Received RST_STREAM: Protocol error"
             (te (java.io.IOException. "Received RST_STREAM: Protocol error"))))
    (t/is (= "network error: Received RST_STREAM: CANCEL"
             (te (java.io.IOException. "Received RST_STREAM: CANCEL"))))
    ;; Other IOExceptions are not network errors
    (t/is (= "Broken pipe" (te (java.io.IOException. "Broken pipe"))))
    ;; Non-network exceptions keep their message / legacy fallback
    (t/is (= "Invalid API key" (te (ex-info "Invalid API key" {}))))
    (t/is (= "Request failed: ExceptionInfo" (te (ex-info nil {}))))
    ;; HTTP error responses: 'Exceptional status code: N' with
    ;; the full response in ex-data — the provider's error message must
    ;; surface so overflow/throttle classifiers see the real error
    (let [overflow "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"This model's maximum context length is 1048576 tokens. However, you requested 1048586 tokens. Please reduce the length of the messages or completion.\"}}"
          pairing "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'.\"}}"
          http-err (fn [body] (ex-info "Exceptional status code: 400" {:status 400 :body body}))]
      (t/is (= "This model's maximum context length is 1048576 tokens. However, you requested 1048586 tokens. Please reduce the length of the messages or completion."
               (te (http-err overflow)))
            "HTTP error bodies surface the provider's :error :message")
      (t/is (= "An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'."
               (te (http-err pairing)))
            "non-overflow provider messages pass through")
      (t/is (= "rate limit exceeded" (te (http-err "rate limit exceeded")))
            "plain-text bodies pass through trimmed")
      (t/is (= "Exceptional status code: 400" (te (http-err nil)))
            "missing body keeps the original message")
      ;; the surfaced overflow message must classify as a context overflow
      (t/is (retry/context-overflow? (te (http-err overflow)))
            "the surfaced body feeds the overflow classifier")
      (t/is (not (retry/context-overflow? (te (http-err pairing))))
            "other provider errors are not misclassified as overflow"))
    ;; 429/5xx get an 'HTTP <status>: ' prefix — the retry classifier's
    ;; status-code patterns must match even opaque gateway bodies that carry
    ;; no status token of their own (e.g. agentgateway's 500 'ext_proc
    ;; failed: no more response messages'); 4xx above stays unprefixed
    (let [http-status (fn [status body]
                        (ex-info (str "Exceptional status code: " status)
                                 {:status status :body body}))]
      (t/is (= "HTTP 500: ext_proc failed: no more response messages"
               (te (http-status 500 "ext_proc failed: no more response messages"))))
      (t/is (retry/retryable-error?
             (te (http-status 500 "ext_proc failed: no more response messages")))
            "opaque 500 bodies classify as retryable via the status token")
      (t/is (= "HTTP 502: Bad Gateway" (te (http-status 502 "Bad Gateway"))))
      (t/is (= "HTTP 503: upstream unavailable" (te (http-status 503 "upstream unavailable"))))
      (t/is (= "HTTP 504: Gateway Timeout" (te (http-status 504 "Gateway Timeout"))))
      (t/is (= "HTTP 429: slow down" (te (http-status 429 "slow down")))
            "429 is prefixed too — the regex's '429' token now always matches")
      (t/is (retry/retryable-error? (te (http-status 429 "slow down"))))
      (t/is (retry/retryable-error?
             "Proxy request failed: exceeded request buffer limit while retrying upstream")
            "OpenRouter buffer-limit wrapper failures retry (pi RETRYABLE pattern)")
      ;; JSON error bodies keep the provider message, prefixed
      (t/is (= "HTTP 500: something exploded"
               (te (http-status 500 "{\"error\":{\"message\":\"something exploded\"}}"))))
      ;; quota/billing bodies stay non-retryable even on 5xx (the
      ;; non-retryable quota patterns take precedence over the status token)
      (t/is (not (retry/retryable-error? (te (http-status 500 "billing suspended"))))))))

;; ─── Per-message cost attachment (Phase 5) ────────────────────────────────

(t/deftest test-usage-with-cost
  (m/load-catalogs! nil)
  (let [model (m/get-model :deepseek "deepseek-v4-pro")
        norm {:input 800 :output 500 :cache-read 200 :cache-write 0}]
    (t/testing "provider-native usage gains the pi-shaped :cost breakdown"
      (let [usage (@#'shared/usage-with-cost model
                                             {:prompt_tokens 1000 :completion_tokens 500
                                              :prompt_tokens_details {:cached_tokens 200}})]
        (t/is (= 1000 (:prompt_tokens usage)) "provider-native keys preserved")
        (t/is (= (m/calculate-cost model norm) (:cost usage))
              "cost equals calculate-cost over the normalized tokens")))
    (t/testing "google's already-normalized usage is priced too"
      (let [usage (@#'shared/usage-with-cost model {:input 10 :output 20
                                                    :cache-read 0 :cache-write 0})]
        (t/is (= 10 (:input usage)))
        (t/is (= (m/calculate-cost model {:input 10 :output 20
                                          :cache-read 0 :cache-write 0})
                 (:cost usage)))))
    (t/testing "unrecognized usage passes through unchanged (no :cost)"
      (t/is (= {:foo 1} (@#'shared/usage-with-cost model {:foo 1}))))))

(t/deftest test-request-headers-attribution
  (t/testing "opencode session headers flow through request-headers"
    (let [model {:provider :opencode :id "qwen3.6-plus"
                 :base-url "https://opencode.ai/zen/v1"}
          provider {:id :opencode}
          merged (@#'shared/request-headers {"Authorization" "Bearer k"} model provider "k" "sess-42")]
      (t/is (= "sess-42" (get merged "x-opencode-session")))
      (t/is (= "kmet" (get merged "x-opencode-client")))))
  (t/testing "no session id → no session headers"
    (let [model {:provider :opencode :id "qwen3.6-plus"}
          provider {:id :opencode}
          merged (@#'shared/request-headers {} model provider "k" nil)]
      (t/is (nil? (get merged "x-opencode-session"))))))

