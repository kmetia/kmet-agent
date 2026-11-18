(ns kmet.app.test-agents
  "The `:agents` registry: the factory seam, creation's rollback, the handle as
   the disposal capability, roots, and the durable send surface over
   `:projections`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.agents :as agents]
            [kmet.app.projections :as projections]
            [kmet.app.sessions :as sessions]
            [kmet.srce.core :as srce]))

(defn- fixture
  "A root ctx, a log over the in-process backend, and the registry over it."
  []
  (let [log (sessions/service (sessions/memory-backend))
        projections (projections/service log)
        root (srce/root {:name :app})]
    {:root root
     :log log
     :projections projections
     :agents (agents/service root projections)}))

(defn- create-session! [{:keys [log]}]
  ((:create! log) {:dir "mem" :cwd "/work"}))

(defn- stand-in
  "A factory producing a handle for the session in OPTS, recording what it was
   asked for and what it disposed. OPS override or extend the handle."
  ([]
   (stand-in {} {}))
  ([recorder]
   (stand-in recorder {}))
  ([{:keys [calls disposed] :or {calls (atom []) disposed (atom [])}} ops]
   (fn [child opts]
     (swap! calls conj {:ctx child :opts opts})
     (merge {:id (:id opts)
             :ctx child
             :agent {:id (:id opts) :ctx child}
             :session (constantly (:session opts))
             :dispose! (fn [] (swap! disposed conj (:id opts)))}
            ops))))

(defn- error-type [f]
  (try (f) nil (catch Exception e (:type (ex-data e)))))

(defn- child-names
  "ROOT's child ctxs by name: comparing names keeps a failed assertion from
   printing a ctx, which is cyclic."
  [ctx]
  (mapv :name (srce/children ctx)))

(defn- persistence-row []
  {:name :sessionPersistence
   :provide #{:sessionPersistence}
   :apply (fn [ctx _]
            (srce/provide! ctx :sessionPersistence (sessions/memory-backend)
                           {:in (:parent ctx)}))})

;; ── the factory seam ──────────────────────────────────────────────────────

(deftest creation-needs-a-factory
  (let [{:keys [root agents]} (fixture)
        e (try ((:create! agents) root {}) nil (catch Exception e e))]
    (is (= :kmet.app/no-agent-factory (:type (ex-data e))))
    (is (str/includes? (ex-message e) ":agentLoop")
        "the error names the plugin that registers one")))

(deftest one-factory-per-registry
  (let [{:keys [root agents]} (fixture)
        dispose ((:register-factory! agents) root (stand-in))]
    (is (= :kmet.app/factory-taken
           (error-type #((:register-factory! agents) root (stand-in))))
        "a second live driver is two owners of one teardown")
    (dispose)
    (is (fn? ((:register-factory! agents) root (stand-in)))
        "the disposer withdraws the registration, so a reload can re-register")))

(deftest an-old-disposer-cannot-clear-its-replacement
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        dispose ((:register-factory! agents) root (stand-in))]
    (dispose)
    ((:register-factory! agents) root (stand-in))
    (dispose)
    (is (some? (:id ((:create! agents) root {:session session})))
        "a stale disposer is a no-op, not a hole in the registry")))

(deftest a-factory-registration-is-a-fiber-effect
  (let [{:keys [root log]} (fixture)
        session (create-session! {:log log})]
    (srce/plugin! root (persistence-row))
    (srce/plugin! root sessions/plugin)
    (srce/plugin! root projections/plugin)
    (srce/plugin! root agents/plugin)
    (let [service (srce/get root :agents)
          driver (srce/plugin! root
                               {:name :agent-loop
                                :inject {:agents nil}
                                :provide #{:agentLoop}
                                :apply (fn [ctx _]
                                         (srce/provide! ctx :agentLoop {}
                                                        {:in (:parent ctx)})
                                         ((:register-factory! (srce/get ctx :agents))
                                          ctx (stand-in)))})]
      (is (= :active (:state @driver)) "the driver mounts once the registry does")
      (let [handle ((:create! service) root {:session session})]
        (is (some? (:id handle)) "the driver's factory created an agent")
        (srce/dispose! driver)
        (is (= :kmet.app/no-agent-factory
               (error-type #((:create! service) root {:session session})))
            "unmounting the driver takes its factory with it")))))

;; ── creation ──────────────────────────────────────────────────────────────

(deftest a-created-agent-is-a-child-context-and-a-handle
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        calls (atom [])
        _ ((:register-factory! agents) root (stand-in {:calls calls}))
        handle ((:create! agents) root {:session session :cwd "/work"})]
    (is (re-matches #"[0-9a-f]{8}" (:id handle)) "the id is short and readable")
    (is (= [(keyword "agent" (:id handle))] (map :name (srce/children root)))
        "the agent's ctx is a child of the ctx create! was called with, named for it")
    (is (= (:id handle) (get-in @calls [0 :opts :id]))
        "the factory is handed the id it was minted")
    (is (= "/work" (get-in @calls [0 :opts :cwd])) "and the caller's options")
    (is (= (:agent handle) ((:get agents) root (:id handle)))
        "get returns the bare agent, not the capability")
    (is (= [{:id (:id handle) :agent (:agent handle) :status nil}]
           ((:list agents) root))
        "list is the creation order, with the handle's status when it has one")
    (is (= [{:id (:id handle) :agent (:agent handle) :status nil}]
           ((:roots agents) root))
        "an agent created under a non-agent ctx is a root")))

(deftest a-setup-runs-before-the-factory-and-stays-out-of-its-opts
  ;; The seam `kmet.app.presets` mounts through: setup gets the child ctx and the
  ;; minted id, the factory gets the same ctx, and `:setup` is the registry's
  ;; option — it is consumed here, never forwarded.
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        order (atom [])
        setup-seen (atom nil)
        factory-seen (atom nil)
        factory (stand-in {})
        _ ((:register-factory! agents) root
                                       (fn [child opts]
                                         (swap! order conj :factory)
                                         (reset! factory-seen {:ctx child :opts opts})
                                         (factory child opts)))]
    ((:create! agents) root
                       {:session session
                        :setup (fn [ctx id]
                                 (swap! order conj :setup)
                                 (reset! setup-seen {:ctx ctx :id id}))})
    (is (= [:setup :factory] @order) "setup runs before the factory")
    (is (identical? (:ctx @setup-seen) (:ctx @factory-seen))
        "both see the agent's one child ctx")
    (is (re-matches #"[0-9a-f]{8}" (:id @setup-seen))
        "and setup is handed the id the agent will carry")
    (is (false? (contains? (:opts @factory-seen) :setup))
        ":setup is consumed by create!, not forwarded to the factory")))

(deftest a-resumed-agent-keeps-its-composition
  ;; A resumed agent is still that agent's composition: `resume!` takes the same
  ;; options `create!` does, so a preset mounted at creation can be mounted again
  ;; on the resume rather than silently lost.
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        ran (atom false)
        _ ((:register-factory! agents) root (stand-in {}))]
    ((:resume! agents) root "a-session" {:session session
                                         :setup (fn [_ _] (reset! ran true))})
    (is (true? @ran) "the resume path runs the caller's setup")))

(deftest a-failed-factory-leaves-no-handle-and-no-ctx
  (let [{:keys [root agents]} (fixture)
        _ ((:register-factory! agents) root (fn [_ _] (throw (ex-info "boom" {}))))
        e (try ((:create! agents) root {}) nil (catch Exception e e))]
    (is (= :kmet.app/agent-create-failed (:type (ex-data e))))
    (is (= "boom" (ex-message (ex-cause e))) "the factory's error is kept")
    (is (= [] (child-names root)) "the child ctx was released")
    (is (= [] ((:list agents) root)))
    (testing "and the create slot is free again"
      (is (= :kmet.app/agent-create-failed
             (error-type #((:create! agents) root {})))
          "a second attempt runs rather than reporting create-busy"))))

(deftest a-handle-that-does-not-fit-the-contract-is-refused
  (let [{:keys [root agents]} (fixture)
        _ ((:register-factory! agents) root (fn [_ _] {}))
        e (try ((:create! agents) root {}) nil (catch Exception e e))]
    (is (= :kmet.app/bad-agent-handle (:type (ex-data e))))
    (is (= [:id :ctx :agent :session :dispose!] (:problems (ex-data e))))
    (is (= [] (child-names root))
        "the ctx is rolled back like any failed create")))

(deftest a-handle-with-a-non-fn-op-is-refused
  (let [{:keys [root agents]} (fixture)
        _ ((:register-factory! agents) root (stand-in {} {:status :running}))
        e (try ((:create! agents) root {}) nil (catch Exception e e))]
    (is (= :kmet.app/bad-agent-handle (:type (ex-data e))))
    (is (= [:status] (:problems (ex-data e)))
        "an optional op that is present but not callable is a shape problem")
    (is (= [] (child-names root)))))

(deftest a-plugins-mount-ctx-is-not-a-parent
  (let [{:keys [root agents]} (fixture)
        row (srce/plugin! root {:name :mode/tui :apply (fn [_ _] nil)})]
    (is (= :kmet.app/agent-parent-is-a-mount
           (error-type #((:create! agents) (:ctx @row) {})))
        "a mount ctx is dropped from the tree with its children, so an agent
         parented there would leak invisibly")
    (is (= [] (child-names (:ctx @row))))
    (srce/dispose! row)))

(deftest only-this-tree-registers-a-factory
  (let [{:keys [agents]} (fixture)
        other (srce/root {:name :other})]
    (is (= :kmet.app/outside-scope
           (error-type #((:register-factory! agents) other (stand-in))))
        "a registry is not something another tree can hand a driver to")))

(deftest an-agent-without-a-session-cannot-queue-or-read
  (let [{:keys [root agents]} (fixture)
        _ ((:register-factory! agents) root (stand-in))
        handle ((:create! agents) root {:session nil})]
    (is (= :kmet.app/agent-has-no-session
           (error-type #((:send! agents) handle "hi" {:target :next-turn})))
        "the inbox is the log: no session, nowhere to queue")
    (is (= :kmet.app/agent-has-no-session
           (error-type #((:inbox agents) root handle)))
        "and nothing to read — the same answer from both halves, not a nil
         deref from inside the backend")))

(deftest roots-are-the-agents-that-do-not-hang-off-an-agent
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        _ ((:register-factory! agents) root (stand-in))
        a ((:create! agents) root {:session session})
        b ((:create! agents) (:ctx a) {:session session})]
    (is (= [(:id a)] (map :id ((:roots agents) root)))
        "the subagent's ctx is under the parent agent, so it is not a root itself")
    (is (= [(:id a) (:id b)] (map :id ((:list agents) root)))
        "both are live agents of this registry")))

;; ── disposal ──────────────────────────────────────────────────────────────

(deftest the-handle-is-the-capability
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        disposed (atom [])
        _ ((:register-factory! agents) root (stand-in {:disposed disposed}))
        handle ((:create! agents) root {:session session})
        id (:id handle)]
    (testing "a value that is not the registered handle cannot dispose it"
      (is (= :kmet.app/unknown-agent
             (error-type #((:dispose! agents) (assoc handle :agent :fake))))))
    (is (= :kmet.app/unknown-agent
           (error-type #((:dispose! agents) {:id id})))
        "and neither can an id a consumer assembled")
    ((:dispose! agents) handle)
    (is (= [id] @disposed) "the factory's teardown ran")
    (is (= [] (child-names root)) "the child ctx is released")
    (is (nil? ((:get agents) root id)))
    (is (= [] ((:list agents) root)))
    (is (= :kmet.app/unknown-agent (error-type #((:dispose! agents) handle)))
        "the capability is spent once its agent is gone")))

(deftest a-disposal-takes-the-agents-under-it
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        disposed (atom [])
        _ ((:register-factory! agents) root (stand-in {:disposed disposed}))
        parent ((:create! agents) root {:session session})
        child ((:create! agents) (:ctx parent) {:session session})]
    (testing "a subagent's ctx is a plain child of its parent's, so the parent
              cannot be detached around while it lives"
      (is (= [(keyword "agent" (:id child))]
             (map :name (srce/children (:ctx parent))))))
    ((:dispose! agents) parent)
    (is (= #{(:id parent) (:id child)} (set @disposed))
        "both teardowns ran, the child's before the parent's ctx was released")
    (is (= [] (child-names root)) "no ctx is left behind")
    (is (= [] ((:list agents) root)) "and neither handle survives")
    (is (= :kmet.app/unknown-agent
           (error-type #((:dispose! agents) child)))
        "the child's capability was spent by the cascade")))

(deftest a-create-inside-a-create-is-refused
  (let [{:keys [root agents]} (fixture)
        _ ((:register-factory! agents)
           root
           (fn [child opts]
             ;; a factory that decides to create another agent on the spot: the
             ;; kernel is single-owner-threaded, so this is the interleaving the
             ;; busy claim exists to refuse
             ((:create! agents) child {})
             {:id (:id opts) :ctx child :agent {} :session (constantly nil)
              :dispose! (fn [] nil)}))
        e (try ((:create! agents) root {}) nil (catch Exception e e))]
    (is (= :kmet.app/agent-create-failed (:type (ex-data e))))
    (is (= :kmet.app/create-busy (:type (ex-data (ex-cause e))))
        "the inner create is refused rather than interleaved")
    (is (= [] (child-names root))
        "the outer create rolled back, and the inner one never attached")))

(deftest resume-goes-through-the-factory
  (let [{:keys [root agents]} (fixture)
        calls (atom [])
        _ ((:register-factory! agents) root (stand-in {:calls calls}))
        handle ((:resume! agents) root "stored-session-id")]
    (is (= {:resume "stored-session-id"} (dissoc (get-in @calls [0 :opts]) :id))
        "the factory rebuilds the agent; the registry only passes the id on")
    (is (= [(:id handle)] (map :id ((:list agents) root)))
        "and the resumed handle is published like a created one")))

(deftest a-failed-teardown-still-releases-the-ctx
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        _ ((:register-factory! agents) root
                                       (stand-in {} {:dispose! (fn [] (throw (ex-info "teardown broke" {})))}))
        handle ((:create! agents) root {:session session})]
    ((:dispose! agents) handle)
    (is (= [] (child-names root))
        "a driver bug must not leak the agent's ctx")
    (is (= [] ((:list agents) root)))))

(deftest disposal-takes-the-agents-mounts-with-it
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        _ ((:register-factory! agents) root (stand-in))
        handle ((:create! agents) root {:session session})
        mounted (srce/plugin! (:ctx handle)
                              {:name :agent/tools
                               :provide #{:tools}
                               :apply (fn [ctx _] (srce/provide! ctx :tools {}))})]
    (is (= :active (:state @mounted)))
    ((:dispose! agents) handle)
    (is (= :disposed (:state @mounted)) "the agent's own rows are disposed")
    (is (= [] (child-names (:ctx handle))))))

;; ── the send surface ──────────────────────────────────────────────────────

(deftest sending-appends-and-notifies
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        sent (atom [])
        _ ((:register-factory! agents) root
                                       (stand-in {} {:send! (fn [msg opts] (swap! sent conj [msg opts]))}))
        handle ((:create! agents) root {:session session})
        entry ((:send! agents) handle "hello" {:target :next-turn})]
    (is (= :enqueue (get-in entry [:data :op])) "the send is a log fact")
    (is (= 1 (count ((:entries log) session))))
    (is (= [["hello" {:target :next-turn :entry entry}]] @sent)
        "and only then the live handle is woken, with the entry the driver
         deliveries")
    (is (= [(:id entry)]
           (map :id (get-in ((:inbox agents) root handle) [:next-turn])))
        "the agent's inbox reads its own session")
    (testing "steer! and follow-up! are the two targets"
      ((:steer! agents) handle "mid")
      ((:follow-up! agents) handle "after")
      (is (= [:next-step :next-turn]
             (map (comp :target second) (take-last 2 @sent))))
      (is (= ["mid"] (map :text (get-in ((:inbox agents) root handle) [:next-step])))))
    (testing "a refused target appends nothing and wakes nobody"
      (reset! sent [])
      (is (= :kmet.app/bad-queue-target
             (error-type #((:send! agents) handle "x" {:target :someday}))))
      (is (= [] @sent))
      (is (= 3 (count ((:entries log) session)))))))

(deftest a-submit-records-a-durable-queue-fact-when-asked
  ;; The wire's submit says `:record?` because its queue outlives the process
  ;; (10a); the TUI's local prompt says nothing, because a message typed into
  ;; the editor is not a queued one. The fact lands where the message is
  ;; delivered — a hook that consumes the input delivers nothing, so it
  ;; records nothing.
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        submitted (atom nil)
        _ ((:register-factory! agents) root
                                       (stand-in {} {:submit!
                                                     (fn [text opts]
                                                       (reset! submitted {:text text :opts opts})
                                                       {:action :started})}))
        handle ((:create! agents) root {:session session})]
    (testing "without :record? the submit appends nothing"
      ((:submit! agents) handle "typed locally" {})
      (is (= 0 (count ((:entries log) session))))
      (is (nil? (:enqueue! (:opts @submitted)))
          "the chain is given no callback to record with"))
    (testing "with :record? the enqueue fact is written where delivery happens"
      ((:submit! agents) handle "from the wire" {:record? true})
      ((:enqueue! (:opts @submitted)) "from the wire" :next-step)
      (let [entry (last ((:entries log) session))]
        (is (= {:op :enqueue :target :next-step :text "from the wire"}
               (:data entry))
            "the fact carries what was queued and where it would have gone")
        (is (= :queued-message (:custom-type entry)))))
    (testing "an image prompt records nothing: it starts a run or is refused"
      (reset! submitted nil)
      ((:submit! agents) handle "with an image"
                         {:record? true :run-opts {:images [{:type :image :data "eA=="}]}})
      (is (nil? (:enqueue! (:opts @submitted))))
      (is (= 1 (count ((:entries log) session)))
          "the log still holds only the text prompt's fact"))))

(deftest two-agents-queue-into-two-logs
  (let [{:keys [root log agents]} (fixture)
        one (create-session! {:log log})
        two (create-session! {:log log})
        _ ((:register-factory! agents) root (stand-in))
        a ((:create! agents) root {:session one})
        b ((:create! agents) root {:session two})]
    ((:send! agents) a "for a" {:target :next-turn})
    ((:send! agents) b "for b" {:target :next-turn})
    (is (= ["for a"] (map :text (get-in ((:inbox agents) root a) [:next-turn]))))
    (is (= ["for b"] (map :text (get-in ((:inbox agents) root b) [:next-turn]))))
    (is (= 1 (count ((:entries log) one))))
    (is (= 1 (count ((:entries log) two))))))

(deftest a-dead-handle-cannot-send
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        _ ((:register-factory! agents) root (stand-in))
        handle ((:create! agents) root {:session session})]
    ((:dispose! agents) handle)
    (is (= :kmet.app/unknown-agent
           (error-type #((:send! agents) handle "late" {:target :next-turn}))))
    (is (= 0 (count ((:entries log) session)))
        "nothing was appended for a dead agent")))

;; ── the control surface ───────────────────────────────────────────────────

(deftest an-op-a-handle-does-not-answer-says-so
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        _ ((:register-factory! agents) root (stand-in))
        handle ((:create! agents) root {:session session})]
    (doseq [[op call] {:inject! #((:inject! agents) handle {:role :user})
                       :cancel! #((:cancel! agents) handle {:kind :user})
                       :when-idle #((:when-idle agents) handle)
                       :status #((:status agents) handle)}]
      (let [e (try (call) nil (catch Exception e e))]
        (is (= :kmet.app/unsupported-op (:type (ex-data e))))
        (is (= op (:op (ex-data e)))
            "the error names the op it could not answer")))))

(deftest the-control-surface-forwards-to-the-handle
  (let [{:keys [root log agents]} (fixture)
        session (create-session! {:log log})
        seen (atom [])
        _ ((:register-factory! agents) root
                                       (stand-in {} {:status (fn [] :running)
                                                     :when-idle (fn [] :idle-promise)
                                                     :inject! (fn [msg _opts] (swap! seen conj [:inject msg]))
                                                     :cancel! (fn [opts] (swap! seen conj [:cancel opts]))}))
        handle ((:create! agents) root {:session session})]
    (is (= :running ((:status agents) handle)))
    (is (= :idle-promise ((:when-idle agents) handle)))
    ((:inject! agents) handle {:role :user})
    ((:cancel! agents) handle {:kind :user})
    (is (= [[:inject {:role :user}] [:cancel {:kind :user}]] @seen))
    (is (= :running (:status (first ((:list agents) root))))
        "a listing reads the handle's status, not a registry copy")))

;; ── the tree and the events ──────────────────────────────────────────────

(deftest the-registry-answers-for-its-own-tree
  (let [{:keys [agents]} (fixture)
        other (srce/root {:name :other})]
    (is (= :kmet.app/outside-scope (error-type #((:list agents) other))))
    (is (= :kmet.app/outside-scope (error-type #((:get agents) other "nope"))))
    (is (= :kmet.app/outside-scope (error-type #((:create! agents) other {}))))))

(deftest an-agent-comes-and-goes-on-the-event-bus
  (let [{:keys [root log agents]} (fixture)
        heard (atom [])
        session (create-session! {:log log})]
    (srce/on root :agent/created (fn [e] (swap! heard conj [:created (:id e)])))
    (srce/on root :agent/disposed (fn [e] (swap! heard conj [:disposed (:id e)])))
    ((:register-factory! agents) root (stand-in))
    (let [handle ((:create! agents) root {:session session})]
      ((:dispose! agents) handle)
      (is (= [[:created (:id handle)] [:disposed (:id handle)]] @heard)
          "created after the handle is published, disposed after the ctx is released"))))

;; ── the row ───────────────────────────────────────────────────────────────

(deftest the-row-mounts-over-the-projections
  (let [ctx (srce/root {:name :app})]
    (srce/plugin! ctx (persistence-row))
    (srce/plugin! ctx sessions/plugin)
    (srce/plugin! ctx projections/plugin)
    (let [row (srce/plugin! ctx agents/plugin)]
      (is (= :active (:state @row)))
      (is (= #{:agents} (:provides @row)))
      (is (map? (srce/get ctx :agents)))
      (is (= :kmet.app/no-agent-factory
             (error-type #((:create! (srce/get ctx :agents)) ctx {})))
          "the row exists before any driver does")
      (srce/dispose! row)
      (is (nil? (srce/get ctx :agents))))))
