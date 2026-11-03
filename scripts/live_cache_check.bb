;; Live prompt-cache verification (todo.md § Prompt-caching parity with pi,
;; Verification beyond tests).
;;
;; Three rounds per model through kmet's own request path —
;; kmet.ai.llm/send-message, i.e. the payload builder, the headers, the
;; transport, the stream parser and the usage merge — so the check proves the
;; provider ACCEPTS the cache breakpoints, not merely that the suite sends
;; them:
;;
;;   1. cold prefix, :short  → the cache is WRITTEN (cache_creation > 0)
;;   2. same prefix          → the cache is READ    (cache_read > 0)
;;   3. cold prefix, :long   → on an anthropic-format wire the 1h TTL is
;;                             accepted (ephemeral_1h_input_tokens > 0)
;;
;; The wire follows the model record, so the same script checks the anthropic
;; wire (copilot: the default) and the anthropic-format markers the
;; openai-completions wire places for OpenRouter's anthropic/* models:
;;
;;   bb scripts/live_cache_check.bb
;;   bb scripts/live_cache_check.bb openrouter anthropic/claude-haiku-4.5
(require '[kmet.ai.auth :as auth]
         '[kmet.ai.llm :as llm]
         '[kmet.ai.models :as models]
         '[kmet.ai.usage :as usage]
         '[kmet.app.config :as cfg])

(models/load-catalogs! nil)
(auth/load-auth! (cfg/get-agent-dir))

(def timeout-ms 180000)

(def provider (keyword (or (first *command-line-args*) "github-copilot")))
(def model-id (or (second *command-line-args*) "claude-haiku-4.5"))
(def model (or (models/get-model provider model-id)
               (do (println "Unknown model:" provider model-id) (System/exit 1))))

(defn prefix
  "A nonce keeps the first round cold, so it really writes the cache."
  [nonce]
  (apply str
         (str "Cache probe " nonce " — an otherwise stable prefix.\n")
         (for [i (range 260)]
           (str "Paragraph " i ": the kmet agent composes a context tree in which every facility"
                " is a replaceable row, so a plugin mounts beside the core instead of patching it."
                " Cache breakpoints follow the conversation prefix, and a stable prefix is what makes"
                " a cache read possible at all.\n"))))

(defn messages
  "Round 2 replays round 1 exactly and appends one turn — the shape a cache
   hit requires."
  [nonce turn]
  (cond-> [{:role :system :content [{:type :text :text (prefix nonce)}]}
           {:role :user :content [{:type :text :text "Reply with the single word: ready"}]}]
    (= turn 2) (conj {:role :assistant :content [{:type :text :text "ready"}]}
                     {:role :user :content [{:type :text :text "Reply with the single word: again"}]})))

(defn- send-round
  [msgs retention session-id]
  (let [usages (atom [])
        text (atom [])
        errors (atom [])
        fut (llm/send-message {:provider provider
                               :model model-id
                               :messages msgs
                               :cache-retention retention
                               :session-id session-id
                               :max-tokens 64
                               :on-text #(swap! text conj %)
                               :on-usage #(swap! usages conj %)
                               :on-error #(swap! errors conj %)})]
    (deref fut timeout-ms ::timeout)
    (when (seq @errors)
      (throw (ex-info "request failed" {:errors @errors})))
    {:raw (last @usages)
     :entry (usage/entry-usage (last @usages))
     :text (apply str @text)}))

(defn- report [label {:keys [raw entry text]}]
  (println label)
  (println "  reply:" (pr-str text))
  (println "  usage:" (pr-str raw))
  (println "  normalized:" (pr-str entry))
  entry)

(defn- pos?* [n] (pos? (long (or n 0))))

(let [nonce (str (System/currentTimeMillis))
      session-id (str "live-cache-" nonce)
      anthropic-wire? (= :anthropic-messages (:api model))]
  (println "model:" provider model-id " api:" (:api model) " base:" (:base-url model))
  (let [u1 (report "== round 1  :short, cold prefix — the cache is written =="
                   (send-round (messages nonce 1) :short session-id))
        u2 (report "== round 2  :short, same prefix — the cache is read =="
                   (send-round (messages nonce 2) :short session-id))
        u3 (report "== round 3  :long, cold prefix — the 1h TTL (anthropic wire only) =="
                   (send-round (messages (str nonce "-1h") 1) :long session-id))
        written? (pos?* (:cache-write u1))
        read? (pos?* (:cache-read u2))
        hour? (pos?* (:cache-write-1h u3))]
    (println)
    (println "cache written (round 1):" written?)
    (println "cache read    (round 2):" read?)
    (when anthropic-wire?
      (println "1h cache write (round 3):" hour?))
    (System/exit (if (and written? read? (or hour? (not anthropic-wire?))) 0 1))))
