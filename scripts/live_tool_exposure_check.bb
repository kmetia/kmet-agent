;; Live tool-exposure verification (todo.md § Tool exposure and declared sets,
;; P5 — "Verification beyond tests").
;;
;; Three rounds through kmet's own derivations — registry/get-all-tools,
;; registry/get-contributed-tools and loop.state/declared-tools-for, the
;; function the wire list comes from — and kmet.ai.llm/send-message, i.e. the
;; payload builder, the headers, the transport and the usage merge. The payload
;; each round is captured with :on-payload, so the check reads the tools array
;; Anthropic actually received, not what we think we sent:
;;
;;   1. a deferred source registered but not loaded
;;        → the tools array holds tool_search, not the source's schemas
;;        → the cache is WRITTEN (cache_creation > 0) over that prefix
;;   2. the same prefix, unchanged tools
;;        → the cache is READ (cache_read > 0): the tools array sits inside the
;;          cached prefix, which is why a change to it is expensive
;;   3. one tool promoted into the declared set
;;        → the array grows by exactly that one declaration (nothing else
;;          moves); under P5 this re-sends the array, so the prefix is
;;          re-written rather than read — that cost is what P6's delta removes
;;
;;   bb scripts/live_tool_exposure_check.bb
;;   bb scripts/live_tool_exposure_check.bb commandcode claude-haiku-4-5-20251001
(require '[kmet.ai.auth :as auth]
         '[kmet.ai.llm :as llm]
         '[kmet.ai.models :as models]
         '[kmet.ai.usage :as usage]
         '[kmet.app.config :as cfg]
         '[kmet.app.loop.state :as state]
         '[kmet.app.registry :as registry]
         '[kmet.app.session.core :as session]
         '[kmet.app.tools.registry :as tools]
         '[kmet.app.tools.search :as search]
         '[babashka.fs :as fs])

(models/load-catalogs! nil)
(auth/load-auth! (cfg/get-agent-dir))

(def timeout-ms 180000)
(def provider (keyword (or (first *command-line-args*) "github-copilot")))
(def model-id (or (second *command-line-args*) "claude-haiku-4.5"))
(def model (or (models/get-model provider model-id)
               (do (println "Unknown model:" provider model-id) (System/exit 1))))

;; ─── The tool table the check derives from ───────────────────────────────

(def ^:private session-dir "target/live-tool-exposure")

(def handle (registry/atom-registry))

(defn- stand-in
  "A direct tool that only has to exist: the check reads declarations, never
   executes one."
  [name]
  {:name name
   :label name
   :description (str name " — a stand-in direct tool for the exposure check.")
   :parameters {:type "object" :properties {} :required []}
   :execute (fn [_] {:content "ok" :is-error false})})

(def deferred-names ["cat_alpha" "cat_beta"])

(tools/register-tool! handle (stand-in "probe_one"))
(tools/register-tool! handle (stand-in "probe_two"))
(tools/register-tool! handle (search/definition handle))
(tools/register-tool-source! handle ::catalog
                             (fn []
                               (into {} (map (fn [n] [n (stand-in n)])) deferred-names))
                             {:exposure :deferred
                              :description "Deferred catalog used by the exposure check"})

(fs/create-dirs session-dir)
(def sess (session/create-session session-dir))

(defn- declared
  "What the request declares right now — the same derivation the loop's
   `state/active-tools` hands the wire."
  []
  (vals (state/declared-tools-for (tools/get-all-tools handle)
                                  (tools/get-contributed-tools handle)
                                  {:loaded (state/session-loaded-tools sess)})))

;; ─── Requests ────────────────────────────────────────────────────────────

(defn- prefix
  "A stable, nonce-open prefix: the first round must be cold, so it really
   writes the cache rather than reading someone else's."
  [nonce]
  (apply str
         (str "Tool exposure probe " nonce " — an otherwise stable prefix.\n")
         (for [i (range 160)]
           (str "Paragraph " i ": the kmet agent declares a tool once, at the front of the"
                " prefix, so every schema is paid on every round and every change to the"
                " declared set invalidates everything behind it.\n"))))

(defn- messages [nonce turn]
  (let [base [{:role :system :content [{:type :text :text (prefix nonce)}]}
              {:role :user :content [{:type :text :text "Reply with the single word: ready"}]}]]
    (case turn
      1 base
      2 (conj base
              {:role :assistant :content [{:type :text :text "ready"}]}
              {:role :user :content [{:type :text :text "Reply with the single word: again"}]})
      3 (conj base
              {:role :assistant :content [{:type :text :text "ready"}]}
              {:role :user :content [{:type :text :text "Reply with the single word: again"}]}
              {:role :assistant :content [{:type :text :text "again"}]}
              {:role :user :content [{:type :text :text "Reply with the single word: once more"}]}))))

(defn- send-request
  "One request with TOOLS declared; returns the parsed payload, the usage and
   the reply text."
  [tool-records msgs session-id]
  (let [payload (atom nil)
        usages (atom [])
        text (atom [])
        errors (atom [])
        fut (llm/send-message {:provider provider
                               :model model-id
                               :messages msgs
                               :tools (vec tool-records)
                               :cache-retention :short
                               :session-id session-id
                               :max-tokens 16
                               :on-payload (fn [p] (reset! payload p) nil)
                               :on-text #(swap! text conj %)
                               :on-usage #(swap! usages conj %)
                               :on-error #(swap! errors conj %)})]
    (deref fut timeout-ms ::timeout)
    (when (seq @errors)
      (throw (ex-info "request failed" {:errors @errors})))
    {:payload @payload
     :wire-tools (mapv :name (:tools @payload))
     :usage (usage/entry-usage (last @usages))
     :raw (last @usages)
     :text (apply str @text)}))

(defn- pos?* [n] (pos? (long (or n 0))))

(defn- report [label {:keys [wire-tools usage text]}]
  (println label)
  (println "  tools declared:" (count wire-tools) (pr-str wire-tools))
  (println "  reply:" (pr-str text))
  (println "  usage:" (pr-str (select-keys usage [:cache-write :cache-read :cache-write-1h :input :output])))
  usage)

(let [nonce (str (System/currentTimeMillis))
      session-id (str "live-tool-exposure-" nonce)
      anthropic-wire? (= :anthropic-messages (:api model))
      r1 (send-request (declared) (messages nonce 1) session-id)
      u1 (report "== round 1  deferred, undeclared — the cache is written ==" r1)
      r2 (send-request (declared) (messages nonce 2) session-id)
      u2 (report "== round 2  same prefix and tools — the cache is read ==" r2)]
  (println "model:" provider model-id " api:" (:api model) " base:" (:base-url model))
  (println "deferred names in round 1:" (pr-str deferred-names)
           "→ present?" (boolean (some (set deferred-names) (r1 :wire-tools))))
  (println "tool_search declared in round 1?" (boolean ((set (r1 :wire-tools)) search/tool-search-name)))
  ;; the promotion: one tool loaded, nothing else touched
  (state/promote-tools! {:session sess} [(first deferred-names)])
  (let [r3 (send-request (declared) (messages nonce 3) session-id)
        u3 (report "== round 3  one tool promoted — the array is re-sent ==" r3)
        added (remove (set (r2 :wire-tools)) (r3 :wire-tools))
        removed (remove (set (r3 :wire-tools)) (r2 :wire-tools))
        ok? (and (not (some (set deferred-names) (r1 :wire-tools)))
                 ((set (r1 :wire-tools)) search/tool-search-name)
                 (= (:wire-tools r1) (:wire-tools r2))
                 (pos?* (:cache-write u1))
                 (pos?* (:cache-read u2))
                 (= [::added (first deferred-names)] [::added (first added)])
                 (empty? removed))]
    (println)
    (println "deferred source contributes no schema before a load:" ok?)
    (println "cache written (round 1):" (pos?* (:cache-write u1)))
    (println "cache read    (round 2):" (pos?* (:cache-read u2)))
    (println "promoted tool added to the array (round 3):" (pr-str added))
    (println "array re-sent on a promotion (P6 removes this):"
             (not (pos?* (:cache-read u3))))
    (when-not anthropic-wire?
      (println "note: this model's wire is" (:api model) "— the 1h/five-minute"
               "cache fields are the anthropic wire's"))
    (System/exit (if ok? 0 1))))
