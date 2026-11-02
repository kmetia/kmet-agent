;; Live prompt-cache verification (todo.md § Prompt-caching parity, P0).
;;
;; Sends three requests with the payload kmet's anthropic wire builds and folds
;; the streamed responses through kmet's own parser and usage handler, so the
;; cache breakpoints, the stream usage merge and the 1h TTL are all exercised:
;;
;;   1. cold prefix, :short        → the cache is WRITTEN (cache_creation > 0)
;;   2. same prefix                → the cache is READ   (cache_read > 0)
;;   3. cold prefix, :long         → the 1h TTL is accepted
;;                                   (cache_creation.ephemeral_1h_input_tokens > 0)
;;
;; Transport is curl: babashka's trust store may lack the host's CA chain. The
;; auth header still comes from the wire's own builder (anthropic-auth-headers),
;; so a subscription provider's bearer routing is exercised rather than assumed;
;; the payload and the event handling under test are kmet's too.
;;
;;   bb scripts/live_cache_check.bb [provider] [model-id]
(require '[kmet.ai.api.anthropic-messages :as anthropic]
         '[kmet.ai.api.sse :as sse]
         '[kmet.ai.auth :as auth]
         '[kmet.ai.models :as models]
         '[kmet.ai.usage :as usage]
         '[kmet.app.config :as cfg]
         '[kmet.libs.json :as json]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell])

(models/load-catalogs! nil)
(auth/load-auth! (cfg/get-agent-dir))

(def provider (keyword (or (first *command-line-args*) "github-copilot")))
(def model-id (or (second *command-line-args*) "claude-haiku-4.5"))
(def model (or (models/get-model provider model-id)
               (do (println "Unknown model:" provider model-id) (System/exit 1))))
(def base-url (:base-url model))
(def access (:api-key (auth/resolve-provider-auth provider)))
;; derived from the wire's builder, not hardcoded: a regression in the oauth
;; bearer routing (todo.md § github-copilot) fails this check
(def auth-args
  (into [] (mapcat (fn [[k v]] ["-H" (str k ": " v)]))
        (anthropic/anthropic-auth-headers provider access)))

;; a nonce per run keeps the prefix cold, so the first round really writes
(defn- prefix [nonce]
  (apply str
         (str "Cache probe " nonce " — an otherwise stable prefix.")
         (for [i (range 260)]
           (str "Paragraph " i ": the kmet agent composes a context tree in which every facility"
                " is a replaceable row, so a plugin mounts beside the core instead of patching it."
                " Cache breakpoints follow the conversation prefix, and a stable prefix is what makes"
                " a cache read possible at all."))))

(defn- messages [nonce turn]
  (cond-> [{:role :system :content (prefix nonce)}
           {:role :user :content "Reply with the single word: ready"}]
    (= turn 2) (conj {:role :assistant :content [{:type :text :text "ready"}]}
                     {:role :user :content "Reply with the single word: again"})))

(defn- stream! [msgs retention]
  (let [payload (anthropic/anthropic-payload model nil msgs nil model-id retention)
        out-file "target/live-cache-check.sse"
        _ (spit out-file "")
        {:keys [exit err]}
        (apply shell/sh "curl" "-sS" "--max-time" "180"
               (concat auth-args
                       ["-H" "Editor-Version: vscode/1.107.0"
                        "-H" "Copilot-Integration-Id: vscode-chat"
                        "-H" "content-type: application/json"
                        "-H" "anthropic-version: 2023-06-01"
                        "-H" "accept: text/event-stream"
                        "-d" (json/generate-string payload)
                        "-o" out-file
                        (str base-url "/v1/messages")]))
        seen (atom [])
        text (atom "")
        handle (anthropic/anthropic-events-handler
                {:model-record model
                 :on-text (fn [t] (swap! text str t))
                 :on-usage (fn [u] (swap! seen conj u))})]
    (when-not (zero? exit) (println "curl failed:" err))
    (with-open [r (io/reader out-file)]
      (sse/process-anthropic-stream {:body r} handle (atom false)))
    {:usage (last @seen) :reports (count @seen) :text @text
     :markers (mapv #(some-> % :cache_control) (:system payload))}))

(defn- report [label {:keys [usage reports text markers]}]
  (println label)
  (println "  system markers:" (pr-str markers)
           " usage reports:" reports
           " reply:" (pr-str text))
  (when usage
    (println "  usage:" (pr-str (dissoc usage :cost)))
    (println "  normalized:" (pr-str (usage/entry-usage usage))))
  usage)

(defn- pos-tokens [u & path]
  (pos? (long (or (get-in u path) 0))))

(let [nonce (str (System/currentTimeMillis))]
  (println "model:" provider model-id " base:" base-url)
  (println)
  (let [r1 (stream! (messages nonce 1) :short)
        r2 (stream! (messages nonce 2) :short)
        u1 (report "== round 1  :short, cold prefix — the cache is written ==" r1)
        u2 (report "== round 2  :short, same prefix — the cache is read ==" r2)
        u3 (report "== round 3  :long, cold prefix — the 1h TTL is accepted =="
                   (stream! (messages (str nonce "-1h") 1) :long))]
    (println)
    (println "cache written (round 1):" (pos-tokens u1 :cache_creation_input_tokens))
    (println "cache read    (round 2):" (pos-tokens u2 :cache_read_input_tokens))
    (println "output count from message_delta (round 2):" (:output_tokens u2)
             "after" (:reports r2) "usage reports")
    (println "1h cache write (round 3):" (pos-tokens u3 :cache_creation :ephemeral_1h_input_tokens)))
)
