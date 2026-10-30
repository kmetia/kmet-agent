(ns kmet.app.test-projections
  "The `:projections` service: the durable inbox (an enqueued message is a log
   fact, a delivery consumes it), the turn boundaries, the token meter, and the
   incremental fold behind all three."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.session.context :as context]
            [kmet.app.session.stats :as stats]
            [kmet.app.projections :as projections]
            [kmet.app.sessions :as sessions]
            [kmet.srce.core :as srce]))

(defn- fixture
  "A log over the in-process backend, its projections, and a session."
  []
  (let [log (sessions/service (sessions/memory-backend))
        session ((:create! log) {:dir "mem" :cwd "/work"})]
    {:log log :projections (projections/service log) :session session}))

(defn- append! [{:keys [log session]} entry]
  ((:append! log) session entry))

(defn- assistant [& {:as extra}]
  (merge {:role :assistant :content [{:type :text :text "ok"}]} extra))

;; ── the inbox ─────────────────────────────────────────────────────────────

(deftest an-enqueued-message-is-in-the-inbox
  (let [{:keys [projections session]} (fixture)
        entry ((:enqueue! projections) session "hello" {:target :next-turn})
        inbox ((:inbox projections) session)]
    (is (= :enqueue (get-in entry [:data :op])))
    (is (= :queued-message (:custom-type entry)))
    (is (= [{:id (:id entry) :target :next-turn :text "hello"}]
           (get-in inbox [:next-turn]))
        "the inbox row is the enqueue under the entry's own id")
    (is (= [] (:next-step inbox)) "and only the target it was queued for")))

(deftest a-message-map-is-queued-as-a-message
  (let [{:keys [projections session]} (fixture)
        entry ((:enqueue! projections) session {:role :custom :content "x"}
                                       {:target :next-step :display :notice})]
    (is (= {:role :custom :content "x"} (get-in entry [:data :message]))
        "a structured message keeps its shape")
    (is (= :notice (get-in entry [:data :display])) "an extra option rides along")
    (is (= [{:id (:id entry) :target :next-step
             :message {:role :custom :content "x"} :display :notice}]
           (get-in ((:inbox projections) session) [:next-step])))))

(deftest a-delivery-consumes-only-the-ids-it-names
  (let [{:keys [log projections session]} (fixture)
        a ((:enqueue! projections) session "a" {:target :next-turn})
        b ((:enqueue! projections) session "b" {:target :next-turn})]
    ((:deliver! projections) session [(:id a)])
    (is (= [(:id b)] (map :id (get-in ((:inbox projections) session) [:next-turn])))
        "the delivered one is gone, the other stays")
    ((:deliver! projections) session [(:id b)])
    (is (= [] (get-in ((:inbox projections) session) [:next-turn])))
    (testing "a delivery appends a fact; nothing is rewritten"
      (is (= 4 (count ((:entries log) session)))
          "two enqueues and two deliveries are four log entries"))))

(deftest the-inbox-follows-the-active-branch
  (let [{:keys [log projections session]} (fixture)
        f {:log log :session session}
        _ (append! f {:role :user :content "u1"})
        a1 (append! f (assistant))
        _ (append! f {:role :user :content "u2"})
        on-other ((:enqueue! projections) session "other" {:target :next-turn})]
    (is (= [(:id on-other)]
           (map :id (get-in ((:inbox projections) session) [:next-turn])))
        "the enqueue is on the active branch")
    (testing "switching the leaf abandons the branch that carried it"
      (reset! (:leaf-id session) (:id a1))
      (is (= [] (get-in ((:inbox projections) session) [:next-turn])))
      (let [on-this ((:enqueue! projections) session "this" {:target :next-turn})]
        (is (= [(:id on-this)]
               (map :id (get-in ((:inbox projections) session) [:next-turn]))))
        (testing "and switching back brings the other one back"
          (reset! (:leaf-id session) (:id on-other))
          (is (= [(:id on-other)]
                 (map :id (get-in ((:inbox projections) session) [:next-turn])))))))))

(deftest the-queue-never-reaches-the-model
  (let [{:keys [projections session] :as f} (fixture)]
    (append! f {:role :user :content "hello"})
    ((:enqueue! projections) session "queued" {:target :next-turn})
    (is (empty? (filter (comp #{:queued-message} :custom-type)
                        (context/project-context session)))
        "a queue entry is :custom metadata: the context projection drops it, so a
         queued message cannot leak into a prompt as a message of its own")))

;; ── turn boundaries and the meter ─────────────────────────────────────────

(deftest a-boundary-is-an-assistant-entry-with-no-tool-calls
  (let [{:keys [log projections session]} (fixture)
        f {:log log :session session}]
    (append! f {:role :user :content "hi"})
    (append! f (assistant :tool-calls [{:id "c1" :name "read"}]))
    (append! f {:role :tool :content "x" :tool-name "read"})
    (let [final (append! f (assistant :stop-reason :aborted))]
      (is (= [{:entry-id (:id final) :index 3 :outcome :aborted}]
             (:boundaries ((:project projections) session)))
          "the tool-call assistant continues the turn; the next one ends it"))))

(deftest a-boundary-carries-the-outcome-the-loop-acts-on
  (is (= :completed (projections/outcome (assistant :stop-reason :stop))))
  (is (= :completed (projections/outcome (assistant)))
      "no stop reason is a completed turn")
  (is (= :error (projections/outcome (assistant :stop-reason :error))))
  (is (= :aborted (projections/outcome (assistant :stop-reason :aborted)))))

(deftest the-token-meter-matches-the-session-stats
  (let [{:keys [log projections session]} (fixture)
        f {:log log :session session}]
    (append! f {:role :user :content "q"})
    (append! f (assistant :usage {:prompt_tokens 100 :completion_tokens 10}))
    (append! f (assistant :usage {:input_tokens 30 :output_tokens 5}))
    (is (= {:input 130 :output 15 :cache-read 0 :cache-write 0 :cost 0.0}
           (:tokens ((:project projections) session))))
    (is (= (stats/usage-totals session)
           (:tokens ((:project projections) session)))
        "the fold and the session's own totals answer one number")))

;; ── the cache ─────────────────────────────────────────────────────────────

(deftest the-fold-is-incremental
  (let [{:keys [log projections session]} (fixture)
        f {:log log :session session}]
    (append! f {:role :user :content "one"})
    (append! f (assistant))
    (is (= 2 (:extended ((:project projections) session)))
        "the first projection folds the branch")
    (is (= 0 (:extended ((:project projections) session)))
        "a second call with nothing new folds nothing")
    (append! f {:role :user :content "two"})
    (let [projected ((:project projections) session)]
      (is (= 1 (:extended projected)) "only the new entry is folded")
      (is (= 3 (:entries projected)))
      (is (= (dissoc (projections/project (atom {}) session) :extended)
             (dissoc projected :extended))
          "and the incremental answer equals a full fold of the same branch"))))

(deftest a-moved-leaf-starts-the-fold-over
  (let [{:keys [log projections session]} (fixture)
        f {:log log :session session}
        u1 (append! f {:role :user :content "one"})
        a1 (append! f (assistant))
        _ (append! f {:role :user :content "two"})]
    ((:project projections) session)
    (reset! (:leaf-id session) (:id u1))
    (let [projected ((:project projections) session)]
      (is (= 1 (:extended projected))
          "the leaf moved, so the cache cannot extend: the new branch is 1 long")
      (is (= 1 (:entries projected)))
      (is (= [] (:boundaries projected))))
    (reset! (:leaf-id session) (:id a1))
    (is (= 2 (:entries ((:project projections) session)))
        "and moving back re-folds that branch")))

(deftest each-service-value-owns-its-cache
  (let [{:keys [log projections session]} (fixture)
        other (projections/service log)]
    (append! {:log log :session session} {:role :user :content "one"})
    (is (= 1 (:extended ((:project projections) session))))
    (is (= 1 (:extended ((:project other) session)))
        "a second service folds the same session itself, from its own cache")))

(deftest forgetting-a-session-drops-its-fold
  (let [{:keys [log projections session]} (fixture)]
    (append! {:log log :session session} {:role :user :content "one"})
    (append! {:log log :session session} (assistant))
    (is (= 2 (:extended ((:project projections) session))))
    ((:forget! projections) session)
    (is (= 2 (:extended ((:project projections) session)))
        "a deleted session has nothing left to fold, so its cache goes with it")
    (is (nil? ((:forget! projections) session)) "and forgetting twice is harmless")))

(deftest an-enqueue-needs-a-known-target-and-a-message
  (let [{:keys [log projections session]} (fixture)]
    (is (= :kmet.app/bad-queue-target
           (try ((:enqueue! projections) session "hi" {:target :next-week})
                nil
                (catch Exception e (:type (ex-data e))))))
    (is (= :kmet.app/bad-queued-message
           (try ((:enqueue! projections) session 42 {:target :next-turn})
                nil
                (catch Exception e (:type (ex-data e))))))
    (is (= :kmet.app/bad-delivery
           (try ((:deliver! projections) session [])
                nil
                (catch Exception e (:type (ex-data e))))))
    (is (= [] ((:entries log) session)) "a refused call appends nothing")))

;; ── the row ───────────────────────────────────────────────────────────────

(defn- persistence-row []
  {:name :sessionPersistence
   :provide #{:sessionPersistence}
   :apply (fn [ctx _]
            (srce/provide! ctx :sessionPersistence (sessions/memory-backend)
                           {:in (:parent ctx)}))})

(deftest the-row-waits-for-the-log-and-then-serves-it
  (let [ctx (srce/root)
        row (srce/plugin! ctx projections/plugin)]
    (is (= :pending (:state @row)) "no log yet, so no projections")
    (is (= #{:sessions} (:waiting-for @row)))
    (srce/plugin! ctx (persistence-row))
    (srce/plugin! ctx sessions/plugin)
    (is (= :active (:state @row)) "the log mounting activates the projection row")
    (let [service (srce/get ctx :projections)]
      (is (map? service))
      (is (every? #(fn? (get service %)) [:enqueue! :deliver! :project :inbox])))
    (srce/dispose! row)
    (is (nil? (srce/get ctx :projections)) "disposing the row withdraws the service")))

(deftest a-sibling-and-a-child-see-the-service
  (let [ctx (srce/root)]
    (srce/plugin! ctx (persistence-row))
    (srce/plugin! ctx sessions/plugin)
    (srce/plugin! ctx projections/plugin)
    (let [service (srce/get ctx :projections)
          seen (atom nil)
          sibling (srce/plugin! ctx {:name :consumer
                                     :inject {:projections nil}
                                     :apply (fn [c _]
                                              (reset! seen (identical?
                                                            service
                                                            (srce/get c :projections)))
                                              nil)})
          child (srce/extend ctx {:name :agent-1})]
      (is (= :active (:state @sibling)))
      (is (true? @seen) "a sibling row reads the same value")
      (is (identical? service (srce/get child :projections))
          "and a child ctx inherits it through the chain")
      (srce/dispose! sibling))))
