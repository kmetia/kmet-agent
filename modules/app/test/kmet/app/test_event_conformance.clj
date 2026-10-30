(ns kmet.app.test-event-conformance
  "Headless conformance harness (engine/UI split step 0): a text renderer
   driven by the loop's event stream alone. It proves a front end can be
   built from events, and its dispatch table must cover every
   loop-event-type — a new event type fails the coverage test until the
   renderer (and by extension every front end) decides what the transcript
   does with it."
  (:require [kmet.app.loop.state :as state]
            [clojure.test :as t]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.ai.llm :as llm]
            [kmet.app.events :as app-events]
            [kmet.app.wire :as wire]
            [kmet.app.loop :as loop]
            [kmet.app.loop.test-support :as support]
            [kmet.app.config :as cfg]
            [kmet.test-utils :as tu]))

(t/use-fixtures :each tu/with-extension-host)

;; ─── The headless renderer ────────────────────────────────────────────────

(defn- ignore
  "Consume a structural/lifecycle event without a transcript line."
  [state _event]
  state)

(defn- message-text
  "The concatenated text blocks of MESSAGE."
  [message]
  (->> (:content message)
       (keep #(when (= :text (:type %)) (:text %)))
       (str/join)))

(defn- on-message-start
  [state event]
  (let [message (:message event)]
    (case (:role message)
      :assistant (assoc state :assistant "")
      :user (update state :lines conj (str "user: " (message-text message)))
      state)))

(defn- on-message-update
  [state event]
  (let [delta (:delta event)]
    (if (= :text (:type delta))
      (update state :assistant str (:content delta))
      state)))

(defn- on-message-end
  [state event]
  (let [text (or (not-empty (:assistant state)) (message-text (:message event)))]
    (cond-> (assoc state :assistant "")
      (seq text) (update :lines conj (str "assistant: " text)))))

(defn- on-tool-start
  [state event]
  (update state :lines conj (str "tool " (:tool-name event))))

(defn- on-tool-end
  [state event]
  (update state :lines conj (str "tool " (:tool-name event)
                                 (if (:is-error event) " error" " ok"))))

(defn- on-error
  [state event]
  (update state :lines conj (str "error: " (:message event))))

(defn- on-settled
  [state event]
  (update state :lines conj (str "settled: " (name (:outcome event)))))

(def ^:private renderers
  "Loop event type -> (fn [state event] -> state). Exactly one entry per
   loop-event-type: test-renderer-covers-the-vocabulary fails until a new
   event type gets a decision here."
  {:agent-start ignore
   :agent-end ignore
   :agent-before-settle ignore
   :agent-settled on-settled
   :auto-retry-start ignore
   :auto-retry-end ignore
   :compaction-start ignore
   :compaction-end ignore
   :context-replaced ignore
   :error on-error
   :loop-guard ignore
   :message-start on-message-start
   :message-update on-message-update
   :message-end on-message-end
   :model-select ignore
   :queue-update ignore
   :session-before-compact ignore
   :session-compact ignore
   :session-compact-failed ignore
   :status ignore
   :summarization-retry-scheduled ignore
   :summarization-retry-attempt-start ignore
   :summarization-retry-finished ignore
   :thinking-level-select ignore
   :tool-execution-start on-tool-start
   :tool-execution-prepared ignore
   :tool-execution-update ignore
   :tool-execution-end on-tool-end
   :turn-start ignore
   :turn-end ignore})

(defn- render-events
  "Render EVENTS to a transcript (a vector of lines), reading only the
   events — never an AgentState. Throws on an event type with no renderer."
  [events]
  (:lines
   (reduce (fn [state event]
             (if-let [renderer (get renderers (:type event))]
               (renderer state event)
               (throw (ex-info "no renderer for loop event" {:type (:type event)}))))
           {:lines [] :assistant ""}
           events)))

;; ─── Tests ────────────────────────────────────────────────────────────────

(defn- stub-tool-then-text
  "send-message stub: the first call streams one tool call, later calls
   stream text and finish."
  [calls]
  (fn [opts]
    (future
      (if (= 1 (swap! calls inc))
        (do (when-let [on-tool-call (:on-tool-call opts)]
              (on-tool-call {:id "t1" :name "read" :arguments "{}" :index 0}))
            (when-let [on-done (:on-done opts)] (on-done :tool-calls)))
        (do (when-let [on-text (:on-text opts)] (on-text "hello"))
            (when-let [on-done (:on-done opts)] (on-done :stop))))
      :done)))

(defn- run-scenario
  "Run a scripted prompt through the real loop and return the captured
   events."
  [stub]
  (let [events (atom [])
        agent (support/make-agent-state :on-event (fn [event] (swap! events conj event)))]
    (with-redefs [cfg/get-api-key (fn [_] "test-key")
                  llm/send-message stub
                  state/execute-tool-for (fn [_ _ _ _] {:content "file body" :is-error false})]
      @(loop/run-agent-turn agent {:message "hi" :on-error (fn [_])}))
    @events))

(t/deftest test-renderer-covers-the-vocabulary
  (t/is (= (set (keys app-events/loop-event-types))
           (set (keys renderers)))
        "every loop event type needs a renderer decision (render or ignore)"))

(t/deftest test-wire-events-are-edn-round-trippable
  (doseq [type (keys app-events/loop-event-types)]
    (let [event {:type type :message {:role :assistant}}]
      (if (= :message-update type)
        (t/is (not (contains? (wire/wire-event event) :message))
              "the cumulative assistant snapshot is dropped")
        (t/is (= event (wire/wire-event event))
              (str "passes through unchanged: " type)))))
  (t/is (= "boom" (:message (wire/wire-event {:type :error
                                              :message (ex-info "boom" {})})))
        "an exception error payload becomes its message string")
  (t/is (= "boom" (get (wire/wire-event
                        {:type :auto-retry-end :final-error (Exception. "boom")})
                       :final-error)))
  (t/is (= "no auth" (:message (wire/wire-event {:type :error :message "no auth"})))
        "a string payload is left alone")
  (t/is (nil? (get (wire/wire-event {:type :error :message nil}) :message))
        "a nil payload stays nil")
  (doseq [event (run-scenario (stub-tool-then-text (atom 0)))]
    (let [wire (wire/wire-event event)]
      (t/is (= wire (edn/read-string (pr-str wire)))
            (str "round-trips through EDN: " (:type event))))))

(def ^:private loop-boundary-names
  "The loop's actionable names: the events `kmet.app.loop/emit-boundary`
   dispatches. Its two call sites are the source, so this set is what the test
   pins — derived from the vocabulary, the test would only compare the
   declaration with itself."
  #{:turn-end :agent-before-settle})

(t/deftest test-the-wire-projection-drops-a-control-object
  ;; `:signal` is an abort atom an in-process event may carry; the wire is EDN,
  ;; so it never crosses.
  (t/is (not (contains? (wire/wire-event {:type :turn-end :signal (atom false)})
                        :signal)))
  (t/is (not (contains? (wire/wire-event {:type :message-update
                                          :message {:role :assistant}
                                          :signal (atom false)})
                        :signal))))

(t/deftest test-every-emitted-event-matches-its-declared-mode
  ;; The vocabulary declares each event's dispatch mode, and the loop dispatches
  ;; plain events as :emit and the actionable ones (loop-boundary-names) as
  ;; boundaries. The declaration is only honest if what a live turn emits goes
  ;; down the path its own entry names — a name sent down the other fold would
  ;; contradict its documentation. One table, one dispatch: this is a property
  ;; of the vocabulary plus the loop, not a comparison between two layers.
  (let [modes (into {} (map (juxt :name :mode)) (app-events/builtin-events))
        emitted (set (map :type (run-scenario (stub-tool-then-text (atom 0)))))]
    (t/is (contains? emitted :turn-end) "the scenario reaches the boundary path")
    (t/is (contains? emitted :agent-before-settle) "and the settle boundary")
    (doseq [type emitted]
      (t/is (= (if (loop-boundary-names type) :boundary :emit)
               (get modes type))
            (str "declared mode matches the path it was dispatched on: " type)))))

(t/deftest test-headless-renderer-consumes-a-live-stream
  (let [events (run-scenario (stub-tool-then-text (atom 0)))
        text (str/join "\n" (render-events events))]
    (t/is (seq events) "the scenario emitted events")
    (t/is (str/includes? text "user: hi"))
    (t/is (str/includes? text "tool read"))
    (t/is (str/includes? text "tool read ok"))
    (t/is (str/includes? text "assistant: hello"))
    (t/is (str/includes? text "settled: completed"))))

(t/deftest test-headless-renderer-renders-failures
  (let [events (run-scenario
                (fn [opts]
                  (future
                    (when-let [on-error (:on-error opts)] (on-error "boom"))
                    :done)))
        text (str/join "\n" (render-events events))]
    (t/is (str/includes? text "error: boom"))
    (t/is (str/includes? text "settled: error"))))
