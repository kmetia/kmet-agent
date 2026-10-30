(ns kmet.ai.test-llm-core
  "The llm entry points that are not provider-specific: module loading, the
   no-API-key path, tool-schema consistency, SSE helpers, empty tool lists,
   multiple providers, and the transport's total/idle timeouts."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.ai.auth :as auth]
            [kmet.ai.llm :as llm]
            [kmet.ai.api.shared :as shared]
            [kmet.ai.api.openai-responses :as responses]
            [kmet.ai.models :as m]
            [kmet.app.tools.registry :as tools]
            [kmet.libs.concurrent :as concurrent]
            [kmet.test-utils :as tu]))

;; ─── Module loads ─────────────────────────────────────────────────────────

(t/deftest test-llm-loaded
  (t/is (fn? llm/send-message))
  (m/load-catalogs! nil)
  (t/is (= 41 (count (m/get-providers))))
  (t/is (fn? m/get-model)))

(t/deftest test-request-opts-applies-the-callers-conversion
  ;; pi: emitContext then convertToLlm — the hook sees the caller's messages,
  ;; the converter sees the hook's output, and no caller conversion leaves the
  ;; messages untouched (kmet.ai knows no agent-context role)
  (let [seen (atom [])
        hook (fn [m] (swap! seen conj [:hook (mapv :role m)]) m)
        convert (fn [m] (swap! seen conj [:converter (mapv :role m)])
                  (mapv #(if (= :compaction (:role %))
                           {:role :user :content [{:type :text :text "flat"}]}
                           %)
                        m))
        input [{:role :compaction :summary "S"}]
        out (llm/request-opts {:messages input
                               :on-context hook
                               :convert-to-llm convert})]
    (t/is (= [[:hook [:compaction]] [:converter [:compaction]]] @seen)
          "the hook runs first and still sees the role")
    (t/is (= [:user] (mapv :role (:messages out))))
    (t/is (= input (:messages (llm/request-opts {:messages input})))
          "no :convert-to-llm — the messages go through as given")))

(t/deftest test-request-opts-hooks
  (t/testing "the attempt's :hooks map is merged and the context hook rewrites messages"
    (let [seen (atom nil)
          out (llm/request-opts
               {:messages [{:role :user :content "hi"}]
                :hooks {:on-context (fn [m] (reset! seen m) [{:role :user :content "ctx"}])
                        :on-headers (fn [h] h)
                        :on-payload (fn [p] p)}})]
      (t/is (= [{:role :user :content "hi"}] @seen)
            "the context hook sees the pre-conversion messages")
      (t/is (= [{:role :user :content "ctx"}] (:messages out)))
      (t/is (fn? (:on-headers (:hooks out)))
            "the request-time pair is re-packed for kmet.ai.http")
      (t/is (nil? (:on-response (:hooks out))) "a nil hook is dropped, not carried")
      (t/is (fn? (:on-payload out)) "the payload hook reaches the builders")))
  (t/testing "a flat :on-* key overrides the attempt map (per-call override)"
    (let [f (fn [p] p)
          out (llm/request-opts {:hooks {:on-payload (fn [_] :hook)} :on-payload f})]
      (t/is (= f (:on-payload out)))))
  (t/testing "no hooks — nil payload hook, empty hook map, messages untouched"
    (let [out (llm/request-opts {:messages [{:role :user :content "hi"}]})]
      (t/is (nil? (:on-payload out)))
      (t/is (= {} (:hooks out)))
      (t/is (= [{:role :user :content "hi"}] (:messages out))))))

(t/deftest test-unknown-api-type
  ;; An unknown wire reports through the async error channel like every other
  ;; pre-request failure, naming the wires kmet.ai.wires knows.
  (m/load-catalogs! nil)
  (let [err (promise)]
    (llm/send-message {:provider :deepseek
                       :model "deepseek-flash"
                       :api-key "k"
                       :api-type :no-such-wire
                       :messages [{:role :user :content "hi"}]
                       :on-error (fn [msg] (deliver err msg))})
    (let [msg (deref err 2000 :timeout)]
      (t/is (string? msg))
      (t/is (str/includes? msg "Unknown api-type: :no-such-wire"))
      (t/is (str/includes? msg ":anthropic-messages")
            "the error names the known set"))))

(t/deftest test-send-message-threads-attempt-hooks
  ;; The loop hands send-message the attempt's :hooks map; the context hook
  ;; rewrites the transcript the wire builder receives and the request-time
  ;; pair rides along for kmet.ai.http. The loop and bridge tests cover each
  ;; end structurally — this is the join.
  (m/load-catalogs! nil)
  (let [request (atom nil)
        seen (atom nil)]
    (with-redefs [auth/resolve-provider-auth (fn [_] {:api-key "test-key"})
                  responses/responses-request
                  (fn [opts] (reset! request opts) (future nil))]
      @(llm/send-message {:provider :openai
                          :model "gpt-5.4"
                          :messages [{:role :user :content "orig"}]
                          :hooks {:on-context (fn [msgs]
                                                (reset! seen msgs)
                                                [{:role :user :content "rewritten"}])
                                  :on-headers (fn [h] h)
                                  :on-response (fn [_] nil)}}))
    (t/is (= [{:role :user :content "orig"}] @seen)
          "the context hook sees the pre-conversion transcript")
    (t/is (= [{:role :user :content "rewritten"}] (:messages @request))
          "the wire builder receives the rewritten transcript")
    (t/is (fn? (:on-headers (:hooks @request)))
          "the request-time pair reaches the builder's HTTP call")
    (t/is (nil? (:on-context (:hooks @request)))
          "the context hook is consumed here, not forwarded to the builder")))

;; ─── send-message with no API key ─────────────────────────────────────────

(t/deftest test-llm-no-api-key
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :opencode-go
              :on-error (fn [e] (swap! errors conj e))})]
    @fut  ;; wait for future
    (t/is (pos? (count @errors)))
    (t/is (.contains (first @errors) "No API key"))))

(t/deftest test-llm-no-api-key-anthropic
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :deepseek
              :on-error (fn [e] (swap! errors conj e))})]
    @fut
    (t/is (pos? (count @errors)))
    (t/is (.contains (first @errors) "No API key"))))

;; ─── Tool schema consistency ──────────────────────────────────────────────

(t/deftest test-llm-tool-schemas
  (let [tools (vals (tools/get-all-tools (tu/tool-registry)))]
    (doseq [t tools]
      (let [openai-schema (shared/tool->openai-schema t)
            anthropic-schema (shared/tool->anthropic-schema t)]
        (t/is (= "function" (:type openai-schema)))
        (t/is (= (:name t) (get-in openai-schema [:function :name])))
        (t/is (= (:name t) (:name anthropic-schema)))
        (t/is (map? (get-in openai-schema [:function :parameters])))
        (t/is (map? (:input_schema anthropic-schema)))))))

;; ─── SSE parsing helpers ──────────────────────────────────────────────────

(t/deftest test-llm-send-message-returns-future
  (m/load-catalogs! nil)
  (let [fut (llm/send-message {:provider :opencode-go :model "deepseek-v4.1-flash"
                               :api-key "test" :on-error (fn [_])})]
    (t/is (future? fut))))

;; ─── Edge: empty tools list ───────────────────────────────────────────────

(t/deftest test-llm-no-tools
  (let [errors (atom [])
        fut (llm/send-message
             {:provider :opencode-go
              :model "deepseek-v4.1-flash"
              :tools []
              :on-error (fn [e] (swap! errors conj e))})]
    @fut
    (t/is (pos? (count @errors)))))

;; ─── Multiple providers ──────────────────────────────────────────────────

(t/deftest test-llm-all-providers-resolve
  (m/load-catalogs! nil)
  (t/is (= 41 (count (m/get-providers))))
  (doseq [p [:opencode-go :opencode :deepseek :github-copilot :openai :xai
             :openai-codex :azure-openai-responses :anthropic :google :groq
             :cerebras :huggingface :moonshotai :moonshotai-cn :xiaomi
             :xiaomi-token-plan-cn :xiaomi-token-plan-ams :xiaomi-token-plan-sgp
             :qwen-token-plan :qwen-token-plan-cn :qwen-token-plan-individual
             :minimax :minimax-cn :nvidia :openrouter :fireworks
             :vercel-ai-gateway :open-design :zai :zai-coding-cn :together :baseten
             :ant-ling :kimi-coding :cloudflare-workers-ai :cloudflare-ai-gateway
             :mistral :google-vertex :amazon-bedrock :commandcode]]
    (t/is (some? (m/get-provider p)) (str p " has a catalog entry"))))

;; ─── Transport total timeout follows the configured idle timeout ──────────

(t/deftest test-effective-total-timeout-ms
  (t/is (= 120000 (shared/effective-total-timeout-ms 120000 300000))
        "a positive explicit total timeout wins")
  (t/is (nil? (shared/effective-total-timeout-ms 0 300000))
        "an explicit disabled total timeout does not fall back to idle")
  (t/is (= 300000 (shared/effective-total-timeout-ms nil 300000))
        "nil uses a positive idle timeout")
  (t/is (nil? (shared/effective-total-timeout-ms nil 0))
        "no total or idle timeout leaves the transport unlimited"))

(t/deftest test-headers-timeout-ms
  ;; the idle deadline doubles as the response-start deadline (pi:
  ;; headersTimeout = httpIdleTimeoutMs) — the SSE reader's own idle clock
  ;; only starts once the headers have arrived
  (t/is (= 60000 (shared/headers-timeout-ms 60000)))
  (t/is (nil? (shared/headers-timeout-ms 0)) "0 disables the headers deadline")
  (t/is (nil? (shared/headers-timeout-ms nil))))

(t/deftest ^:slow test-llm-request-timeout-follows-idle
  (m/load-catalogs! nil)
  ;; A server that accepts the request but never responds: the request must
  ;; error via the configured idle timeout (pi: undici bodyTimeout AND
  ;; headersTimeout are both httpIdleTimeoutMs), both when the total deadline
  ;; follows idle (nil) and when it is explicitly disabled (0 — the default),
  ;; where the response start would otherwise hang the turn until Escape (the
  ;; SSE idle reader only starts after the headers arrive). The window rejects
  ;; the old hardcoded 120s too.
  (doseq [total-timeout-ms [nil 0]]
    (t/testing (str "total-timeout-ms " (pr-str total-timeout-ms))
      (let [ss (java.net.ServerSocket. 0)
            port (.getLocalPort ss)
            _ (doto (Thread.
                     (fn []
                       (try
                         (let [s (.accept ss)
                               rdr (java.io.BufferedReader.
                                    (java.io.InputStreamReader. (.getInputStream s)))]
                           ;; drain request headers, then stall — never respond
                           (while (seq (or (.readLine rdr) "")) nil)
                           (Thread/sleep 5000)
                           (.close s))
                         (catch Exception _ nil))))
                (.setDaemon true)
                (.start))
            t0 (concurrent/monotonic-ms)
            errors (atom [])
            fut (llm/send-message {:provider :opencode-go
                                   :api-key "sk-test"
                                   :base-url (str "http://127.0.0.1:" port "/v1/chat/completions")
                                   :model "deepseek-v4.1-flash"
                                   :messages [{:role "user" :content "hi"}]
                                   :idle-timeout-ms 500
                                   :total-timeout-ms total-timeout-ms
                                   :on-error (fn [e] (swap! errors conj e))})]
        (try
          @fut
          (t/is (= 1 (count @errors)))
          (let [elapsed (- (concurrent/monotonic-ms) t0)]
            ;; Window 20s: the error message assertion below guards the mechanism
            ;; (must contain "timed out" — a broken deadline yields the EOF
            ;; error instead); this window only allows for scheduling delay on
            ;; loaded hosts while still rejecting the old 120s hardcoded timeout.
            (t/is (< elapsed 20000)
                  (str "errored via the configured timeout (took " elapsed "ms)"))
            (t/is (str/includes? (first @errors) "timed out")))
          (finally
            (.close ss)))))))

;; guards the java.net.http body-stream deadlock
(t/deftest ^:slow test-llm-body-stall-idle-timeout-completes
  (m/load-catalogs! nil)
  ;; A server that sends response headers then stalls the body: the SSE idle
  ;; timeout must fire and the request future must complete promptly. Before
  ;; the interrupt/join-before-close fix, closing the java.net.http body
  ;; stream while the daemon read was in flight deadlocked the future.
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)
        _ (doto (Thread.
                 (fn []
                   (try
                     (let [s (.accept ss)
                           rdr (java.io.BufferedReader.
                                (java.io.InputStreamReader. (.getInputStream s)))]
                       (while (seq (or (.readLine rdr) "")) nil)
                       (let [out (.getOutputStream s)]
                         (.write out (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n"))
                         (.flush out)
                         (Thread/sleep 30000))
                       (.close s))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))
        errors (atom [])
        fut (llm/send-message {:provider :opencode-go
                               :api-key "sk-test"
                               :base-url (str "http://127.0.0.1:" port "/v1/chat/completions")
                               :model "deepseek-v4.1-flash"
                               :messages [{:role "user" :content [{:type :text :text "hi"}]}]
                               :idle-timeout-ms 1000
                               :on-error (fn [e] (swap! errors conj e))})]
    (try
      (t/is (not= :timeout (deref fut 8000 :timeout))
            "request future completes instead of deadlocking on close")
      (t/is (= ["Stream idle timeout after 1000 ms (no data received)"]
               @errors))
      (finally
        (.close ss)))))

