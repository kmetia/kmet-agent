(ns kmet.ai.test-llm-anthropic
  "Anthropic messages: auth-token (Authorization: Bearer) support, parallel tool
   calls, and Kimi's adaptive thinking end-to-end."
  (:require [clojure.test :as t]
            [kmet.libs.json :as json]
            [clojure.string :as str]
            [kmet.ai.auth :as auth]
            [kmet.libs.dynamic-value :as dynamic-value]
            [kmet.ai.llm :as llm]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.api.anthropic-messages :as anthropic]
            [kmet.ai.models :as m]
            [kmet.app.tools.tool :as tools]))

;; ─── Phase 9: anthropic auth-token (Authorization: Bearer) ─────────────────

(t/deftest test-anthropic-auth-headers
  (t/testing "the :anthropic provider with AUTH_TOKEN → Authorization: Bearer (pi anthropic resolve)"
    (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
      (t/is (= {"Authorization" "Bearer tok"}
               (@#'anthropic/anthropic-auth-headers :anthropic "ignored-key")))))
  (t/testing "no AUTH_TOKEN → x-api-key with the resolved key"
    (with-redefs [auth/getenv (fn [_] nil)]
      (t/is (= {"x-api-key" "api-key"}
               (@#'anthropic/anthropic-auth-headers :anthropic "api-key")))))
  (t/testing "other providers never take the anthropic bearer path"
    (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
      (t/is (= {"x-api-key" "k"}
               (@#'anthropic/anthropic-auth-headers :github-copilot "k"))))))

(t/deftest test-anthropic-auth-headers-oauth
  ;; An oauth-derived credential (Copilot) is a subscription token: Copilot's
  ;; /v1/messages is bearer-only, so the :oauth marker routes it to
  ;; Authorization: Bearer instead of x-api-key.
  (let [prev @#'auth/oauth-source]
    (try
      (auth/set-oauth-source!
       (fn [_]
         {:to-auth (fn [cred] {:api-key (:access cred)
                               :base-url "https://api.individual.githubcopilot.com"})}))
      (with-redefs [auth/auth-atom
                    (atom {:github-copilot
                           {:type :oauth :access "sub-token" :refresh "r"
                            ;; far future: a refresh would write the real auth.edn
                            :expires 9999999999999}})
                    auth/getenv (fn [_] nil)]
        (t/testing "an oauth credential → Authorization: Bearer, no x-api-key"
          (t/is (= {"Authorization" "Bearer sub-token"}
                   (@#'anthropic/anthropic-auth-headers :github-copilot "sub-token")))))
      (finally (auth/set-oauth-source! prev)))))

(t/deftest ^:slow test-llm-anthropic-bearer-only
  (m/load-catalogs! nil)
  (m/clear-extension-providers!)
  (try
    (m/register-provider-config! :anthropic
                                 {:base-url "https://api.anthropic.com" :api :anthropic-messages
                                  :models [{:id "claude-sonnet-4.5"}]})
    (t/testing "non-anthropic providers still reject a missing api key"
      (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
        (let [errors (atom [])]
          @(llm/send-message {:provider :deepseek
                              :on-error (fn [e] (swap! errors conj e))})
          (t/is (pos? (count @errors)))
          (t/is (str/includes? (first @errors) "No API key")))))
    (t/testing "the :anthropic provider with AUTH_TOKEN: proceeds past the key check"
      (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))]
        (let [errors (atom [])]
          @(llm/send-message {:provider :anthropic
                              :model "claude-sonnet-4.5"
                              :on-error (fn [e] (swap! errors conj e))})
          (t/is (not-any? #(str/includes? (or % "") "No API key") @errors)
                "reaches the request (fails on the network, not on auth)"))))
    (finally
      (m/clear-extension-providers!)
      (m/load-catalogs! nil))))

(t/deftest test-llm-anthropic-broken-configured-key-blocks-bearer
  ;; A configured-but-unresolvable models.edn key blocks the whole resolution
  ;; (pi resolveConfigValueOrThrow: rawKey present → inherited env never
  ;; reached) — even with AUTH_TOKEN set, the request must NOT proceed with a
  ;; nil x-api-key.
  (m/load-catalogs! nil)
  (m/clear-extension-providers!)
  (try
    (m/register-provider-config! :anthropic
                                 {:base-url "https://api.anthropic.com" :api :anthropic-messages
                                  :api-key "$KMT_MISSING_ANTHROPIC_KEY"
                                  :models [{:id "claude-sonnet-4.5"}]})
    (with-redefs [auth/getenv (fn [k] (when (= k "ANTHROPIC_AUTH_TOKEN") "tok"))
                  dynamic-value/getenv (fn [_] nil)]
      (let [errors (atom [])]
        @(llm/send-message {:provider :anthropic
                            :model "claude-sonnet-4.5"
                            :on-error (fn [e] (swap! errors conj e))})
        (t/is (pos? (count @errors)))
        (t/is (str/includes? (first @errors) "No API key")
              "unresolvable configured key wins over AUTH_TOKEN (pi)")))
    (finally
      (m/clear-extension-providers!)
      (m/load-catalogs! nil))))

(t/deftest ^:slow test-llm-resolves-key-from-auth
  ;; send-message resolves the key itself when the caller passes none (pi
  ;; prepareRequest) — an auth.edn credential lets a bare call proceed
  ;; instead of erroring with an empty Authorization header.
  (m/load-catalogs! nil)
  (with-redefs [auth/auth-atom (atom {:opencode-go {:key "file-key"}})
                auth/getenv (fn [_] nil)]
    (let [errors (atom [])]
      @(llm/send-message {:provider :opencode-go
                          :model "deepseek-v4.1-flash"
                          :on-error (fn [e] (swap! errors conj e))})
      (t/is (not-any? #(str/includes? (or % "") "No API key") @errors)
            "proceeds with the auth.edn key (fails on the network, not on auth)"))))

;; ─── Anthropic parallel tool calls (pi parity) ─────────────────────────────

(t/deftest ^:slow test-llm-anthropic-parallel-tool-calls
  ;; Regression: parallel tool_use blocks streamed by Claude must survive the
  ;; tool-call accumulator as SEPARATE calls. Before the :index fix, kmet's
  ;; anthropic wire dropped the block index (and the input_json_delta events
  ;; entirely), so sibling calls collapsed into one and args stayed {} — the
  ;; model saw one-at-a-time execution and adapted to serial tool calls.
  (m/load-catalogs! nil)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-body (atom nil)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           ;; capture the request body (single-line JSON)
                           clen (atom 0)
                           _ (loop []
                               (let [line (.readLine rdr)]
                                 (when (seq (or line ""))
                                   (when (str/starts-with? (str/lower-case (or line "")) "content-length:")
                                     (reset! clen (Long/parseLong (str/trim (subs line 15)))))
                                   (recur))))
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
                           ;; Two parallel tool_use blocks: read (index 0) and
                           ;; bash (index 1), each with input_json_delta streams.
                           stream-body (str "event: content_block_start\n"
                                            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_read\",\"name\":\"read\",\"input\":{}}}\n\n"
                                            "event: content_block_delta\n"
                                            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"\"}}\n\n"
                                            "event: content_block_delta\n"
                                            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"/etc/hosts\\\"\"}}\n\n"
                                            "event: content_block_start\n"
                                            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_bash\",\"name\":\"bash\",\"input\":{}}}\n\n"
                                            "event: content_block_delta\n"
                                            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\\\"ls\\\"}\"}}\n\n"
                                            "event: content_block_delta\n"
                                            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"}\"}}\n\n"
                                            "event: message_delta\n"
                                            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n"
                                            "event: message_stop\n"
                                            "data: {\"type\":\"message_stop\"}\n\n")]
                       (.write out (.getBytes (str "HTTP/1.1 200 OK\r\n"
                                                   "Content-Type: text/event-stream\r\n"
                                                   "Content-Length: " (count stream-body) "\r\n\r\n"
                                                   stream-body)))
                       (.flush out)
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        tool-calls (atom [])
        done-reason (atom nil)
        errors (atom [])
        fut (llm/send-message {:provider :anthropic
                               :api-key "sk-test"
                               :base-url (str "http://localhost:" port "/v1/messages")
                               :model "claude-haiku-4-5"
                               :messages [{:role :system :content [{:type :text :text "S"}]}
                                          {:role :user :content [{:type :text :text "hi"}]}]
                               :tools [(tools/make-tool :name "read" :description "Read a file"
                                                        :parameters {:type "object" :properties {}
                                                                     :required []})]
                               :on-tool-call (fn [tc] (swap! tool-calls conj tc))
                               :on-done (fn [r] (reset! done-reason r))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= :tool-use @done-reason))
      (t/is (= [{:id "toolu_read" :name "read" :arguments {} :index 0}
                {:id nil :arguments "{\"path\":\"" :index 0}
                {:id nil :arguments "/etc/hosts\"" :index 0}
                {:id "toolu_bash" :name "bash" :arguments {} :index 1}
                {:id nil :arguments "{\"command\":\"ls\"}" :index 1}
                {:id nil :arguments "\"}" :index 1}]
               @tool-calls)
            "both parallel tool calls stream through with their own index and merged args deltas")
      (t/testing "the wire payload puts the system prompt in `system`, not a message"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= [{:type "text" :text "S" :cache_control {:type "ephemeral"}}]
                   (:system payload))
                "system prompt goes to params.system, carrying the cache breakpoint (pi semantics)")
          (t/is (not-any? #(= "system" (:role %)) (:messages payload))
                "no illegal \"system\" message")
          (t/is (= "claude-haiku-4-5" (:model payload)))
          (t/is (seq (:tools payload)) "tools are included")))
      (finally
        (.close ss)))))

;; ─── Kimi adaptive thinking e2e (anthropic-messages, forceAdaptiveThinking) ─

(t/deftest ^:slow test-llm-kimi-adaptive-thinking-end-to-end
  (m/load-catalogs! nil)
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        request-body (atom nil)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))
                           _ (.readLine rdr)
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
                           _ (reset! request-body (str sb))
                           out (.getOutputStream s)
                           stream-body (str "event: content_block_delta\n"
                                            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n"
                                            "event: message_stop\n"
                                            "data: {\"type\":\"message_stop\"}\n\n")]
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
        fut (llm/send-message {:provider :kimi-coding
                               :model "kimi-for-coding"
                               :api-key "k"
                               :base-url (str "http://localhost:" port)
                               :thinking :medium
                               :messages [{:role :user :content [{:type :text :text "hi"}]}]
                               :on-text (fn [t] (swap! text str t))
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      @fut
      (t/is (= [] @errors) (str "no stream errors: " @errors))
      (t/is (= "hi" @text))
      (t/testing "the adaptive thinking payload is on the wire (pi forceAdaptiveThinking)"
        (let [payload (json/parse-string @request-body true)]
          (t/is (= {:type "adaptive" :display "summarized"} (:thinking payload)))
          (t/is (= {:effort "medium"} (:output_config payload)))
          (t/is (= 32768 (:max_tokens payload)))))
      (finally
        (.close ss)))))

(t/deftest test-llm-cloudflare-missing-env-reports-error
  (m/load-catalogs! nil)
  (let [errors (atom [])]
    ;; the interpolation runs inside the request future, so the env redef
    ;; must stay active until the future completes
    (with-redefs [shared/getenv (fn [_] nil)]
      @(llm/send-message {:provider :cloudflare-workers-ai
                          :model "@cf/moonshotai/kimi-k2.6"
                          :api-key "k"
                          :on-error (fn [e] (swap! errors conj e))}))
    (t/is (= ["Cloudflare requires the env vars: CLOUDFLARE_ACCOUNT_ID"] @errors)
          "a missing cloudflare account id reports via on-error (never hangs)")))

;; ─── Prompt-cache breakpoints (pi buildParams / getCacheControl) ───────────

(def ^:private cache-model
  {:id "claude-sonnet-4.6" :provider :anthropic :api :anthropic-messages
   :max-tokens 4096 :compat {}
   ;; pi claude-opus-4-8 rates: 1h cache writes are 2x base input
   :cost {:input 5.0 :output 25.0 :cache-read 0.5 :cache-write 6.25}})

(def ^:private cache-tools
  [{:name "t1" :description "d1" :parameters {:type "object" :properties {}}}
   {:name "t2" :description "d2" :parameters {:type "object" :properties {}}}])

(def ^:private two-round-messages
  [{:role :system :content "SYS"}
   {:role :user :content "hello"}
   {:role :assistant :content [{:type :text :text "hi"}]}
   {:role :user :content "again"}])

(defn- payload
  [& {:keys [model retention messages tools]
      :or {model cache-model retention :short
           messages two-round-messages tools cache-tools}}]
  (anthropic/anthropic-payload model nil messages tools "claude-sonnet-4.6" retention))

(t/deftest test-anthropic-cache-breakpoints
  (t/testing ":short places the three breakpoints — system, last tool, last user block"
    (let [p (payload)
          marker {:type "ephemeral"}]
      (t/is (= marker (:cache_control (first (:system p)))))
      (t/is (nil? (:cache_control (first (:tools p)))))
      (t/is (= marker (:cache_control (last (:tools p)))))
      (t/is (= "user" (:role (last (:messages p)))))
      (t/is (= [{:type "text" :text "again" :cache_control marker}]
               (:content (last (:messages p))))
            "a string last-user content is wrapped into a marked text block")
      (t/is (= "hello" (:content (first (:messages p))))
            "an earlier user message is untouched")))
  (t/testing ":none places no marker anywhere (pi getCacheControl)"
    (t/is (not (str/includes? (json/generate-string (payload :retention :none))
                              "cache_control"))))
  (t/testing ":long marks all three with ttl 1h, and no beta header exists to send"
    (let [p (payload :retention :long)]
      (t/is (= {:type "ephemeral" :ttl "1h"} (:cache_control (first (:system p)))))
      (t/is (= {:type "ephemeral" :ttl "1h"} (:cache_control (last (:tools p)))))
      (t/is (= {:type "ephemeral" :ttl "1h"}
               (:cache_control (last (:content (last (:messages p)))))))))
  (t/testing ":long on a model without long retention stays ephemeral (pi compat default true)"
    (t/is (= {:type "ephemeral"}
             (:cache_control (first (:system (payload :model (assoc cache-model :compat
                                                                    {:supports-long-cache-retention false})
                                                      :retention :long)))))))
  (t/testing ":supports-cache-control-on-tools false leaves the tool list bare"
    (let [p (payload :model (assoc cache-model :compat
                                   {:supports-cache-control-on-tools false}))]
      (t/is (not-any? :cache_control (:tools p)))
      (t/is (some? (:cache_control (first (:system p)))))))
  (t/testing "a conversation ending on an assistant turn takes no message marker"
    (let [p (payload :messages (conj (subvec two-round-messages 0 3)
                                     {:role :assistant :content [{:type :text :text "bye"}]}))]
      (t/is (nil? (:cache_control (last (last (:messages p))))))))
  (t/testing "no tools → no tools key"
    (t/is (nil? (:tools (payload :tools nil))))))

(t/deftest test-anthropic-session-affinity-headers
  (t/testing "the default is OpenRouter-only (pi getAnthropicCompat)"
    (t/is (nil? (anthropic/anthropic-affinity-headers cache-model "sid" :short)))
    (t/is (= {"x-session-id" "sid"}
             (anthropic/anthropic-affinity-headers
              (assoc cache-model :provider :openrouter :base-url "https://openrouter.ai/api/v1")
              "sid" :short)))
    (t/is (= {"x-session-id" "sid"}
             (anthropic/anthropic-affinity-headers
              (assoc cache-model :base-url "https://openrouter.ai/api/v1") "sid" :short))
          "the base URL alone detects OpenRouter"))
  (t/testing "an explicit compat enables them with pi's non-OpenRouter header"
    (t/is (= {"x-session-affinity" "sid"}
             (anthropic/anthropic-affinity-headers
              (assoc cache-model :compat {:send-session-affinity-headers true})
              "sid" :short))))
  (t/testing ":none sends none (pi: cacheSessionId), and a nil session id too"
    (t/is (nil? (anthropic/anthropic-affinity-headers
                 (assoc cache-model :compat {:send-session-affinity-headers true})
                 "sid" :none)))
    (t/is (nil? (anthropic/anthropic-affinity-headers
                 (assoc cache-model :compat {:send-session-affinity-headers true})
                 nil :short)))))

(t/deftest test-anthropic-usage-spans-two-events
  (let [merge-usage @#'anthropic/merge-anthropic-usage]
    (t/testing "message_start's counts survive a delta that omits them (pi: proxies drop input_tokens)"
      (t/is (= {:input_tokens 100 :output_tokens 5 :cache_read_input_tokens 90
                :cache_creation_input_tokens 10}
               (merge-usage {:input_tokens 100 :output_tokens 1 :cache_read_input_tokens 90
                             :cache_creation_input_tokens 10}
                            {:output_tokens 5}))))
    (t/testing "a delta's field replaces the start's"
      (t/is (= 1000 (:cache_creation_input_tokens
                     (merge-usage {:cache_creation_input_tokens 10}
                                  {:cache_creation_input_tokens 1000})))))
    (t/testing "a nil field in the delta never blanks a start value"
      (t/is (= 100 (:input_tokens (merge-usage {:input_tokens 100}
                                               {:input_tokens nil :output_tokens 3})))))
    (t/testing "the nested 1h breakdown merges the same way (the Vercel delta case)"
      (t/is (= {:ephemeral_1h_input_tokens 400 :ephemeral_5m_input_tokens 600}
               (:cache_creation
                (merge-usage {:cache_creation {:ephemeral_5m_input_tokens 600}}
                             {:cache_creation {:ephemeral_1h_input_tokens 400}})))))
    (t/testing "no usage on the delta → the accumulator is unchanged"
      (t/is (= {:input_tokens 100} (merge-usage {:input_tokens 100} nil))))))

(defn- usage-probe
  "An anthropic event handler plus the usage maps it reported, for one stream."
  []
  (let [seen (atom [])
        handle (anthropic/anthropic-events-handler
                {:model-record cache-model
                 :on-usage (fn [u] (swap! seen conj u))})]
    [handle seen]))

(t/deftest test-anthropic-events-handler-usage
  ;; pi anthropic-cache-write-1h-cost.test.ts: the counts are split across
  ;; message_start and message_delta, and the caller must end up with both
  (let [[handle seen] (usage-probe)]
    (handle {:type :usage
             :usage {:input_tokens 100 :output_tokens 1 :cache_read_input_tokens 900
                     :cache_creation_input_tokens 1000000}})
    (handle {:type :message-delta :stop-reason :stop
             :usage {:output_tokens 250 :cache_creation
                     {:ephemeral_5m_input_tokens 600000
                      :ephemeral_1h_input_tokens 400000}}})
    (handle {:type :done :stop-reason :stop})
    (t/is (= 2 (count @seen)) "one call per usage-bearing chunk, none at :done")
    (t/is (= {:input_tokens 100 :cache_read_input_tokens 900 :cache_creation_input_tokens 1000000
              :output_tokens 250
              :cache_creation {:ephemeral_5m_input_tokens 600000
                               :ephemeral_1h_input_tokens 400000}}
             (dissoc (second @seen) :cost))
          "the last report carries the delta's output count and the start's input counts")
    (t/is (pos? (:total (:cost (second @seen))))
          "priced on the merged map, not on either half")))

(t/deftest test-anthropic-events-handler-without-usage
  (let [[handle seen] (usage-probe)]
    (handle {:type :message-delta :stop-reason :stop})
    (t/is (empty? @seen) "a message_delta with no usage reports nothing")))

(t/deftest test-resolve-cache-retention
  (t/is (= :short (llm/resolve-cache-retention {})) "pi's default")
  (t/is (= :long (llm/resolve-cache-retention {:cache-retention :long}))
        "the caller's value wins — the host resolves its own knob above this layer")
  (t/is (= :none (llm/resolve-cache-retention {:cache-retention :none}))))
