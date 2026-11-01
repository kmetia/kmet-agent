(ns kmet.app.loop.test-support
  "Shared stubs for the loop tests: scripted LLM transports, event capture,
   provider registration, compaction runs, and turn-capture helpers."
  (:require [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.ai.llm :as llm]
            [kmet.ai.models :as models]
            [kmet.ai.auth :as auth]
            [kmet.app.events :as events]
            [kmet.app.session.core :as session]
            [kmet.app.loop :as loop]
            [kmet.app.loop.stages :as loop-stages]
            [kmet.app.test-support :as app-support]
            [kmet.app.config :as cfg]
            [kmet.srce.core :as srce]
            [kmet.test-utils :as tu]
            [kmet.test-tmp :as test-tmp]))

(declare make-test-provider test-ctx)

(def ^:dynamic *ctx*
  "The ctx a hand-built loop state runs on. `nil` means the suite's own
   (`process-ctx`); a test that needs its own tool table binds this
   (`with-registry`) or names `:ctx` at the constructor."
  nil)

(defonce ^:private suite-ctx
  ;; One test ctx per process, built on first use: a hand-built state names the
  ;; ctx it reads, and a test process's is `test-ctx` — a bare root carrying a
  ;; fresh tool table — not the composed application root.
  (delay (:ctx (test-ctx))))

(defn process-ctx
  "The ctx a test's hand-built state runs on: `*ctx*` when a test bound one, the
   suite's own ctx otherwise."
  []
  (or *ctx* @suite-ctx))

(defn on-event
  "Register a listener for TYPE on the test's ctx (`process-ctx`) — the ctx a
   hand-built state emits at. The 3-arity carries an owner, as the app's `on!`
   does (`:resources-discover` contributions are read back beside it). Returns
   the disposer."
  ([type f] (events/on! (process-ctx) type f))
  ([type owner f] (events/on! (process-ctx) type {:owner owner} f)))

(defn clear-listeners!
  "Drop every listener on the test's ctx (`process-ctx`)."
  []
  (tu/clear-listeners! (process-ctx)))

(defn tool-table
  "The tool table the loop reads on `process-ctx`: the `:tools` service, a
   registry handle (a fresh one under `with-registry`)."
  []
  (srce/get (process-ctx) :tools))

(defn make-agent-state
  "`kmet.app.loop/make-agent-state` on the test process's ctx (`process-ctx`):
   a state names the ctx whose `:tools` table and `:loop-stages` it reads, and
   the loop's constructor has no process default of its own. An explicit `:ctx`
   in OPTS still wins."
  [& opts]
  (apply loop/make-agent-state :ctx (process-ctx) opts))

(defn run-turn-capturing-llm
  "Run one turn on AGENT with a stubbed LLM call; returns the captured
   send-message opts."
  [agent & [opts]]
  (let [sent (atom nil)]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message
                  (fn [o]
                    (reset! sent o)
                    (future
                      (when-let [on-done (:on-done o)]
                        (on-done :stop))
                      :done))]
      @(loop/run-agent-turn agent (merge {:on-done (fn [_])
                                          :on-error (fn [_])}
                                         opts)))
    @sent))

(defn loop-guard-tc
  "A tool-call map shaped like the loop's accumulator output."
  [name args]
  {:id (str (gensym "tc")) :name name :arguments args})

(defn stub-llm-tool-then-text
  "send-message stub: first call streams one tool call, subsequent calls a plain text reply."
  [call-count]
  (fn [opts]
    (future
      (if (= 1 (swap! call-count inc))
        (do (when-let [on-tc (:on-tool-call opts)]
              (on-tc {:id "tc1" :name "bash" :arguments "{}" :index 0}))
            (when-let [on-done (:on-done opts)]
              (on-done :tool-calls)))
        (do (when-let [on-text (:on-text opts)]
              (on-text "ok"))
            (when-let [on-done (:on-done opts)]
              (on-done :stop))))
      :done)))

(defn make-test-provider
  "A minimal test provider with models IDS (pi-shaped Model records)."
  [ids]
  (models/map->Provider
   {:id :test-prov :name "Test"
    :api-types #{:openai-completions}
    :models (mapv (fn [id]
                    (models/map->Model {:id id :name (str "Model " id)
                                        :provider :test-prov
                                        :api :openai-completions
                                        :base-url "https://test"
                                        :reasoning false :input [:text]
                                        :cost {:input 0 :output 0 :cache-read 0
                                               :cache-write 0}
                                        :context-window 1000 :max-tokens 100}))
                  ids)
    :env-vars [] :default-model nil}))

(defn with-test-provider
  "Register the test provider + auth and restore the registry after."
  [f]
  (let [saved (models/get-providers)]
    (models/clear-providers!)
    (models/register-provider! (make-test-provider ["a" "b" "c"]))
    (try
      (with-redefs [auth/auth-atom (atom {:test-prov {:key "sk"}})]
        (f))
      (finally
        (models/clear-providers!)
        (doseq [p saved] (models/register-provider! p))))))

(defn without-providers
  "Run F with the model registry empty and restore it after. A test that
   asserts what an empty registry does has to make it empty: another namespace's
   composition can have registered providers in the same process."
  [f]
  (let [saved (models/get-providers)]
    (models/clear-providers!)
    (try
      (f)
      (finally
        (models/clear-providers!)
        (doseq [p saved] (models/register-provider! p))))))

(defn thinking-model
  "Reasoning model supporting exactly the non-off LEVELS (pi
   getSupportedThinkingLevels): levels absent from the set are pinned to nil
   in the thinking-level-map; :xhigh/:max need a non-nil entry to be
   supported."
  [id levels]
  (let [supported (set levels)]
    (models/map->Model
     {:id id :name (str "Model " id) :provider :test-prov
      :api :openai-completions :base-url "https://test"
      :reasoning true :input [:text]
      :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
      :context-window 1000 :max-tokens 100
      :thinking-level-map
      (into {}
            (concat
             ;; exclude non-:xhigh/:max levels not in the set
             (for [l [:minimal :low :medium :high]
                   :when (not (contains? supported l))]
               [l nil])
             ;; :xhigh/:max require a non-nil map entry to be enabled
             (for [l [:xhigh :max]
                   :when (contains? supported l)]
               [l (name l)])))})))

(defn stub-llm-text-turns
  "send-message stub: the Nth call streams TEXTS' Nth entry (\"ok\" once the
   list runs out) and finishes with :stop. Each call's opts are recorded in
   OPTS."
  [calls texts opts]
  (fn [o]
    (swap! opts conj o)
    (future
      (let [n (swap! calls inc)]
        (when-let [on-text (:on-text o)]
          (on-text (nth texts (dec n) "ok")))
        (when-let [on-done (:on-done o)]
          (on-done :stop))
        :done))))

(defn request-user-texts
  "The user-message texts a send-message opts map carries."
  [o]
  (mapv #(get-in % [:content 0 :text])
        (filter #(= :user (:role %)) (:messages o))))

(defn events-of
  "The event types in EVENTS that are members of TYPES, in order."
  [events types]
  (into [] (comp (filter #(contains? types (:type %))) (map :type)) events))

(defn run-prompt-asking-to-continue
  "Run a one-turn prompt whose EVENT handler returns `(merge EXTRA {:continue
   true})` and capture the stderr warning. Returns {:calls n :warn str :agent
   ag}."
  [event extra]
  (let [calls (atom 0)
        opts (atom [])
        warn (java.io.StringWriter.)
        agent (make-agent-state)]
    (on-event event (fn [_] (assoc extra :continue true)))
    (try
      (with-redefs [cfg/get-api-key (fn [_] "test-key")
                    llm/send-message (stub-llm-text-turns calls ["done"] opts)]
        (binding [*err* warn]
          @(loop/run-agent-turn agent {:message "run"
                                       :on-done (fn [_])
                                       :on-error (fn [_])})))
      (finally
        (clear-listeners!)))
    {:calls @calls :warn (str warn) :agent agent}))

(defn stub-llm-two-tool-calls-then-text
  "send-message stub: first call streams two bash tool calls (ARGS1/ARGS2,
   JSON strings, defaulting to {}), subsequent calls a plain reply."
  ([call-count] (stub-llm-two-tool-calls-then-text call-count "{}" "{}"))
  ([call-count args1 args2]
   (fn [opts]
     (future
       (if (= 1 (swap! call-count inc))
         (do (when-let [on-tc (:on-tool-call opts)]
               (on-tc {:id "tc1" :name "bash" :arguments args1 :index 0})
               (on-tc {:id "tc2" :name "bash" :arguments args2 :index 1}))
             (when-let [on-done (:on-done opts)]
               (on-done :tool-calls)))
         (do (when-let [on-text (:on-text opts)]
               (on-text "ok"))
             (when-let [on-done (:on-done opts)]
               (on-done :stop))))
       :done))))

(defn with-summarization-stub
  "Run f with the LLM stubbed to complete one compaction summarization call
   (only the compaction path calls the LLM with an empty tool list). Delivers
   usage like a real provider so the compaction entry records it (pi:
   CompactionEntry.usage)."
  [f]
  (with-redefs [cfg/get-api-key (fn [_] "test-key")
                llm/send-message
                (fn [opts]
                  (future
                    (t/is (empty? (:tools opts)) "summarization call carries no tools")
                    (t/is (= :none (:cache-retention opts))
                          "compaction summaries disable prompt caching (pi)")
                    (when-let [on-text (:on-text opts)]
                      (on-text "summary of the old conversation"))
                    (when-let [on-usage (:on-usage opts)]
                      (on-usage {:prompt_tokens 100 :completion_tokens 10
                                 :prompt_tokens_details {:cached_tokens 20}
                                 :cost {:total 0.001}}))
                    (when-let [on-done (:on-done opts)]
                      (on-done :stop))
                    :done))]
    (f)))

(defn seed-context!
  "Append N token-heavy user entries to SESS so the estimated context passes
   the small compaction thresholds the tests configure."
  [sess n]
  (dotimes [i n]
    (session/append-entry sess
                          {:role :user
                           :content [{:type :text
                                      :text (str "This is message body number " i
                                                 " with plenty of words so the estimated token count "
                                                 "easily exceeds the small test threshold.")}]})))

(defn event-of
  "The first event of TYPE in the EVENTS atom, or nil."
  [events type]
  (first (filter #(= type (:type %)) @events)))

(defn with-compaction-run
  "Run F with a temp-dir session seeded over a tiny compaction threshold and
   an agent collecting its events. F receives {:sess :events :agent}; the
   session directory is removed afterwards."
  [f]
  (let [dir (test-tmp/temp-dir! "sess-")
        sess (session/create-session dir)
        events (atom [])
        agent (make-agent-state
               :session sess
               :compact-token-threshold 10
               :keep-recent-tokens 40
               :on-event (fn [e] (swap! events conj e)))]
    (try
      (seed-context! sess 6)
      (f {:sess sess :events events :agent agent})
      (finally (fs/delete-tree dir)))))

(defn test-ctx
  "A bare ctx for a loop test's agent: STAGES merged over the builtin defaults
   (`kmet.app.loop.stages/defaults`) and a tool service of its own. Returns {:ctx ctx :tools handle} — the handle is fresh, with the
   composition's built-in tool rows mounted into it, so a test can register
   stand-ins beside the tools a run would offer.

   Bare means no parent: the loop reads the stage map from the ctx (the
   defaults when STAGES is nil) and the tool table from its `:tools` service,
   so a loop test never boots kmet2's composition to get either."
  ([] (test-ctx nil))
  ([stages]
   (let [;; the stage map rides the ctx meta as an atom, the way a run's own
         ;; ctx carries one, so a refresh can swap it
         ctx (srce/root {:name :test-loop
                         :loop-stages (atom (loop-stages/with-defaults stages))})
         handle (tu/tools-service! ctx)]
     (tu/mount-builtin-tools! ctx)
     {:ctx ctx :tools handle})))

(defn with-no-credentials
  "Run F with the agent's provider unconfigured, as a machine that never logged
   in. `run-agent-turn`'s no-key guard reads the api-key resolver *and*
   `auth/configured?` — which sees a stored auth.edn entry, a configured
   models.edn key, an env var, or an ambient credential — so a test asserting
   the refusal pins both; otherwise a developer's `~/.kmet/agent/auth.edn`
   turns the refusal into a real (model-less) request."
  [f]
  (with-redefs [cfg/get-api-key (fn [_] nil)
                auth/configured? (fn [_] false)]
    (f)))

(defn with-host
  "Run F with a bound extension host over a fresh registry handle
   (`kmet.app.test-support/with-host`): the loop's tool and render paths ask the
   host for its hooks, and a bare test process has no composition to bind one."
  [f]
  (app-support/with-host f))

(defn with-registry
  "Run F with the loop's tool table replaced by a fresh handle.

   The loop reads the table from the `:tools` service on the agent's ctx
   (`loop.state/tool-table`), so a test needs a ctx of its own: binding `*ctx*`
   to a bare ctx whose `:tools` is a fresh handle hands every
   `make-agent-state` in F that table. A test registers a stand-in through
   `tool-table`, so what it adds is what the loop reads.

   A test that needs a tool table should not boot an application: booting the
   root here used to be observable, because `kmet.app.modes.test-rpc`'s
   terminal-event test races a stubbed run's `:agent-settled` against the
   `:status` the mode writes on dispose, and an earlier boot made it fail in
   most full runs."
  [f]
  (let [{:keys [ctx]} (test-ctx)]
    (with-host
      (fn [] (binding [*ctx* ctx]
               (f))))))

