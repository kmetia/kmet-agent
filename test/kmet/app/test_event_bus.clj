(ns kmet.app.test-event-bus
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.app.event-bus :as event-bus]))

;; ─── Event vocabulary ─────────────────────────────────────────────────────

(t/deftest test-events-vocabulary
  (t/is (map? event-bus/event-types))
  (t/is (pos? (count event-bus/event-types)))
  (t/is (every? keyword? (keys event-bus/event-types)))
  (t/is (every? string? (vals event-bus/event-types))))

(t/deftest test-events-core-types-known
  (doseq [t [:agent-start :agent-end :agent-settled :turn-start :turn-end
             :agent-before-settle
             :message-start :message-update :message-end
             :tool-execution-start :tool-execution-update :tool-execution-end
             :status :error :user-bash]]
    (t/is (event-bus/known-event-type? t) (str t " should be a known event type"))))

(t/deftest test-events-unknown-types
  (t/is (not (event-bus/known-event-type? :bogus)))
  (t/is (not (event-bus/known-event-type? nil)))
  (t/is (not (event-bus/known-event-type? "agent-start"))))

(t/deftest test-session-shutdown-event
  (t/is (event-bus/known-event-type? :session-shutdown)
        "session_shutdown is part of the vocabulary (pi: SessionShutdownEvent)")
  (t/is (contains? event-bus/app-event-types :session-shutdown)
        "emitted outside the loop by the interactive mode, like :session-start"))

(t/deftest test-resources-discover-event
  (t/is (event-bus/known-event-type? :resources-discover)
        "resources_discover is part of the vocabulary (pi: ResourcesDiscoverEvent)")
  (t/is (contains? event-bus/app-event-types :resources-discover)
        "emitted outside the loop (extensions/discover-resources!), not consumed by the UI"))

;; ─── Event bus ────────────────────────────────────────────────────────────

(t/deftest test-event-register-and-emit
  (let [log (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :test-event (fn [e] (swap! log conj e)))
    (event-bus/emit-event! {:type :test-event :data "hello"})
    (t/is (= 1 (count @log)))
    (t/is (= :test-event (:type (first @log))))
    (t/is (= "hello" (:data (first @log))))))

(t/deftest test-event-multiple-listeners
  (let [log (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :multi (fn [e] (swap! log conj (str "a:" (:val e)))))
    (event-bus/on-event :multi (fn [e] (swap! log conj (str "b:" (:val e)))))
    (event-bus/emit-event! {:type :multi :val 42})
    (t/is (= 2 (count @log)))
    (t/is (str/includes? (first @log) "42"))))

(t/deftest test-event-no-listeners
  (t/testing "Emitting with no listeners should not throw"
    (event-bus/clear-event-listeners!)
    (event-bus/emit-event! {:type :unregistered :data "test"})))

(t/deftest test-event-deregister
  (let [log (atom [])]
    (event-bus/clear-event-listeners!)
    (let [dereg (event-bus/on-event :dereg-test (fn [e] (swap! log conj e)))]
      (event-bus/emit-event! {:type :dereg-test :data "first"})
      (dereg)
      (event-bus/emit-event! {:type :dereg-test :data "second"})
      (t/is (= 1 (count @log)))
      (t/is (= "first" (:data (first @log)))))))

(t/deftest test-event-types-distinct
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :type-a identity)
  (event-bus/on-event :type-b identity)
  (let [types (event-bus/get-event-types)]
    (t/is (contains? (set types) :type-a))
    (t/is (contains? (set types) :type-b))))

(t/deftest test-event-clear
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :clear-test identity)
  (event-bus/clear-event-listeners!)
  (t/is (empty? (event-bus/get-event-types))))

(t/deftest test-event-listener-error-handling
  (let [log (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :err-test (fn [_] (throw (Exception. "boom"))))
    (event-bus/on-event :err-test (fn [e] (swap! log conj (:data e))))
    ;; The throwing listener prints a warning to stderr — suppress it.
    (binding [*err* (java.io.StringWriter.)]
      (event-bus/emit-event! {:type :err-test :data "ok"}))
    (t/is (= 1 (count @log)))
    (t/is (= "ok" (first @log)))))

(t/deftest test-event-returns-last-non-nil-result
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :tree-prep (fn [_] {:summary "ext summary"}))
  (event-bus/on-event :tree-prep (fn [_] nil))
  (t/is (= {:summary "ext summary"}
           (event-bus/emit-event! {:type :tree-prep :x 1}))
        "emit returns the last non-nil handler result (pi: runner.emit)")
  (t/is (nil? (event-bus/emit-event! {:type :unregistered :x 1}))
        "no listeners → nil"))

(t/deftest test-event-handler-error-swallowed
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :bad (fn [_] (throw (ex-info "boom" {}))))
  (event-bus/on-event :bad (fn [_] {:ok true}))
  ;; The throwing listener prints a warning to stderr — suppress it.
  (binding [*err* (java.io.StringWriter.)]
    (t/is (= {:ok true} (event-bus/emit-event! {:type :bad}))
          "an errored handler doesn't prevent later handlers or throw")))

;; ─── Boundary dispatch (pi: runner.emitBoundary) ──────────────────────────

(t/deftest test-boundary-threads-handler-results
  (let [seen (atom [])]
    (event-bus/clear-event-listeners!)
    (event-bus/on-event :b1 (fn [e] (swap! seen conj e) {:entries [:a]}))
    (event-bus/on-event :b1 (fn [e] (swap! seen conj e) {:continue true}))
    (t/is (= {:entries [:a] :continue true}
             (event-bus/emit-boundary! {:type :b1 :entries [] :continue false}))
          "each handler's keys replace the accumulated ones")
    (t/is (= [] (:entries (first @seen))) "the first handler sees the event as given")
    (t/is (= [:a] (:entries (second @seen)))
          "the second handler sees the first handler's entries (pi: emitBoundary)")
    (t/is (false? (:continue (first @seen))))
    (t/is (nil? (event-bus/emit-boundary! {:type :unregistered}))
          "no listeners → nil")))

(t/deftest test-boundary-nil-and-non-map-results-keep-the-state
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :b2 (fn [_] {:entries [:a]}))
  (event-bus/on-event :b2 (fn [_] nil))
  (event-bus/on-event :b2 (fn [_] :bogus))
  (t/is (= {:entries [:a]}
           (event-bus/emit-boundary! {:type :b2 :entries []}))
        "a nil or non-map result leaves the accumulated state alone"))

(t/deftest test-boundary-handler-error-swallowed
  (event-bus/clear-event-listeners!)
  (event-bus/on-event :b3 (fn [_] (throw (ex-info "boom" {}))))
  (event-bus/on-event :b3 (fn [_] {:continue true}))
  ;; The throwing listener prints a warning to stderr — suppress it.
  (binding [*err* (java.io.StringWriter.)]
    (t/is (= {:continue true} (event-bus/emit-boundary! {:type :b3}))
          "an errored boundary handler is skipped, later handlers still run")))
