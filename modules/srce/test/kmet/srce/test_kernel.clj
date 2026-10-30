(ns kmet.srce.test-kernel
  "The kernel's contract tests: one per §12 behavior of the module note."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.srce.core :as srce]))

;; ── helpers ───────────────────────────────────────────────────────────────

(defn- capture
  "A :logger service value that records [level & args] in CALLS."
  [calls]
  (fn [level & args]
    (swap! calls conj (into [level] args))))

(defn- logged
  "The recorded logger calls at LEVEL."
  [calls level]
  (filterv #(= level (first %)) @calls))

(defn- logged?
  "Did the logger see a call at LEVEL whose message includes TEXT?"
  [calls level text]
  (boolean (some (fn [[_ msg]] (and (string? msg) (str/includes? msg text)))
                 (logged calls level))))

(defn- kernel
  "A root ctx with a capturing logger installed. [ctx calls]."
  []
  (let [calls (atom [])
        ctx (srce/root)]
    (srce/provide! ctx :logger (capture calls))
    [ctx calls]))

(defn- thrown
  [f]
  (try
    (f)
    nil
    (catch Throwable t t)))

(defn- thrown-type
  [f]
  (some-> (thrown f) ex-data :type))

;; ── ctx tree ──────────────────────────────────────────────────────────────

(deftest root-is-a-value
  (testing "a root is a value with an active uid-0 fiber, and roots are independent"
    (let [a (srce/root)
          b (srce/root)]
      (is (srce/ctx? a))
      (is (not (srce/ctx? {:a 1})))
      (is (= 0 (:uid @(:fiber a))))
      (is (= :active (:state @(:fiber a))))
      (srce/provide! a :x 1)
      (is (= 1 (srce/get a :x)))
      (is (nil? (srce/get b :x))))))

(deftest extend-and-isolate-build-the-tree
  (let [r (srce/root)
        a (srce/extend r {:name :agent})
        b (srce/extend r {:name :other})]
    (is (= [a b] (srce/children r)))
    (is (srce/inside? a r))
    (is (srce/inside? r r))
    (is (not (srce/inside? a b)))
    (is (= :agent (:name a)))
    (is (identical? (:fiber r) (:fiber a)))
    (is (nil? (srce/mount-fiber r)) "a root is not a mount")
    (is (nil? (srce/mount-fiber a)) "a plain child inherits its parent's fiber")
    (is (not (contains? a :runtimes)))))

(deftest a-plain-child-ctx-can-be-detached
  (let [r (srce/root)
        a (srce/extend r {:name :agent})
        b (srce/extend r {:name :other})
        mount (srce/plugin! a {:name :p :apply (fn [_ _] nil)})]
    (is (identical? mount (srce/mount-fiber (:ctx @mount)))
        "a mount's own ctx names the fiber that created it")
    (testing "a ctx with something mounted under it refuses, rather than losing a live unit"
      (is (= :srce/ctx-busy
             (try (srce/detach! a) nil
                  (catch Exception e (:type (ex-data e)))))))
    (testing "a fiber's disposal removes its own mount ctx, so its parent can go"
      (srce/dispose! mount)
      (srce/detach! a)
      (is (= [b] (srce/children r))
          "the listing drops it; the chain it keeps is not the listing's tree"))
    (testing "idempotent, and a root has no parent to leave"
      (srce/detach! a)
      (srce/detach! r)
      (is (= [b] (srce/children r))))))

(deftest a-realm-blocks-the-outside
  (let [r (srce/root)
        outer (srce/extend r {:name :outer})
        realm (srce/isolate r :tools)
        inner (srce/extend realm {:name :inner})]
    (srce/provide! outer :tools :outer-tools)
    (is (= :outer-tools (srce/get outer :tools)))
    (testing "the boundary hides the outside provider, even with none inside"
      (is (nil? (srce/get realm :tools)))
      (is (nil? (srce/get inner :tools))))
    (srce/provide! realm :tools :realm-tools)
    (is (= :realm-tools (srce/get inner :tools)))
    (is (= :outer-tools (srce/get outer :tools)))
    (is (nil? (srce/get r :tools)))))

(deftest isolate-nests
  (let [r (srce/root)
        a (srce/isolate r :x)
        b (srce/isolate a :x)]
    (srce/provide! a :x :a)
    (is (= :a (srce/get (srce/extend a {}) :x)))
    (is (nil? (srce/get b :x)))
    (srce/provide! b :x :b)
    (is (= :b (srce/get b :x)))
    (is (= :a (srce/get (srce/extend a {}) :x)))))

(deftest services-is-a-realm-aware-merge
  (let [r (srce/root)
        realm (srce/isolate r :tools)
        inner (srce/extend realm {:name :inner})]
    (srce/provide! r :tools :root-tools)
    (srce/provide! r :llm :root-llm)
    (srce/provide! inner :sessions :inner-sessions)
    (testing "from inside the realm: the realm's own names plus the outer ones"
      (is (= #{:sessions :llm} (set (keys (srce/services inner)))))
      (is (nil? (srce/get inner :tools))))
    (testing "from outside: the child's provider is not visible"
      (is (= #{:tools :llm} (set (keys (srce/services r))))))
    (srce/provide! realm :tools :realm-tools)
    (is (= :realm-tools (:value (get (srce/services inner) :tools))))))

;; ── services ──────────────────────────────────────────────────────────────

(deftest provider-records-are-inspection
  (let [[r] (kernel)
        d (srce/provide! r :tools :table)
        p (srce/provider r :tools)]
    (is (= :tools (:name p)))
    (is (= :table (:value p)))
    (is (identical? (:fiber r) (:fiber p)))
    (is (fn? d))
    (is (= :table (srce/get r :tools)))
    (is (true? (d)))
    (is (nil? (srce/get r :tools)))
    (is (nil? (srce/provider r :tools)))
    (is (nil? (d)))))

(deftest duplicate-providers-throw
  (let [[r] (kernel)
        child (srce/extend r {})]
    (srce/provide! r :x 1)
    (testing "in the same ctx"
      (is (= :srce/duplicate-service (thrown-type #(srce/provide! r :x 2)))))
    (testing "in a nested extend (the outer provider is visible)"
      (is (= :srce/duplicate-service (thrown-type #(srce/provide! child :x 2)))))))

(deftest siblings-in-one-realm-may-each-provide
  (let [[r] (kernel)
        a (srce/extend r {:name :a})
        b (srce/extend r {:name :b})]
    (srce/provide! a :tools :a-tools)
    (srce/provide! b :tools :b-tools)
    (is (= :a-tools (srce/get a :tools)))
    (is (= :b-tools (srce/get b :tools)))
    (is (nil? (srce/get r :tools)))
    (is (not (contains? (srce/services r) :tools)))
    (is (= :a-tools (:value (get (srce/services a) :tools))))))

(deftest check-gates-availability
  (testing "false and a throw read as unavailable"
    (let [[r] (kernel)
          ready? (atom false)]
      (srce/provide! r :svc :v {:check (fn [] @ready?)})
      (is (nil? (srce/get r :svc)))
      (is (some? (srce/provider r :svc)))
      (reset! ready? true)
      (is (= :v (srce/get r :svc))))
    (let [[r] (kernel)]
      (srce/provide! r :svc :v {:check (fn [] (throw (ex-info "no" {})))})
      (is (nil? (srce/get r :svc))))))

(deftest check-turns-true-at-the-boundary
  (let [[r] (kernel)
        ready? (atom false)
        applied (atom 0)]
    (srce/provide! r :svc :v {:check (fn [] @ready?)})
    (let [f (srce/plugin! r {:name :needs
                             :inject [:svc]
                             :apply (fn [_ _] (swap! applied inc))})]
      (is (= :pending (:state @f)))
      (reset! ready? true)
      (srce/settle! r)
      (is (= :active (:state @f)))
      (is (= 1 @applied)))))

;; ── inject ────────────────────────────────────────────────────────────────

(deftest inject-waits-for-a-required-service
  (let [[r] (kernel)
        applied (atom 0)
        f (srce/plugin! r {:name :tools/mount
                           :inject [:tools]
                           :apply (fn [_ _] (swap! applied inc))})]
    (is (= :pending (:state @f)))
    (is (= #{:tools} (:waiting-for @f)))
    (is (zero? @applied))
    (srce/provide! r :tools :table)
    (is (= :active (:state @f)))
    (is (= 1 @applied))
    (is (= #{} (:waiting-for @f)))))

(deftest provide-in-an-ancestor-s-scope
  ;; a plugin mounted at the root shares a service with its siblings, and the
  ;; entry stays that plugin's effect
  (let [[r] (kernel)
        f (srce/plugin! r {:name :events
                           :provide #{:events}
                           :apply (fn [ctx _]
                                    (srce/provide! ctx :events :bus
                                                   {:in (:parent ctx)}))})
        sibling (srce/extend r {})]
    (is (= :bus (srce/get r :events)) "the mount site sees it")
    (is (= :bus (srce/get sibling :events)) "a sibling does too")
    (is (= #{:events} (:provides @f)))
    (srce/dispose! f)
    (is (nil? (srce/get r :events)) "the entry went with its fiber, not the root")))

(deftest optional-inject-upgrades-and-downgrades
  (let [[r] (kernel)
        applied (atom 0)
        f (srce/plugin! r {:name :ui/terminal
                           :inject {:ui :optional}
                           :apply (fn [_ _] (swap! applied inc))})
        uid (:uid @f)]
    (is (= :active (:state @f)))
    (is (= 1 @applied))
    (let [d (srce/provide! r :ui :tk)]
      (is (= 2 @applied) "the richer UI attached: dispose + remount")
      (is (= uid (:uid @f)) "a reload keeps the fiber")
      (d)
      (is (= 3 @applied) "the UI went away: still active as optional"))
    (is (= :active (:state @f)))))

(deftest normalize-inject-forms
  (is (= {} (srce/normalize-inject nil)))
  (is (= {:tools nil} (srce/normalize-inject [:tools])))
  (is (= {:tools nil :llm nil} (srce/normalize-inject {:tools nil :llm nil})))
  (is (= {:ui :optional} (srce/normalize-inject {:ui :optional})))
  (is (= :srce/bad-inject (thrown-type #(srce/normalize-inject {:ui {:x 1}})))))

(deftest bad-plugins-and-sources
  (let [[r] (kernel)]
    (testing "a bad map is a caller bug, not a failed fiber"
      (let [e (thrown #(srce/plugin! r {:name :x}))]
        (is (= :srce/bad-plugin (:type (ex-data e))))
        (is (some #{:apply} (:problems (ex-data e))))))
    (testing "a reserved :inject value surfaces as a plugin problem"
      (is (= :srce/bad-plugin
             (thrown-type #(srce/plugin! r {:name :x :apply (fn [_ _]) :inject {:a 1}})))))
    (testing "a non-fn :update is a plugin problem"
      (let [e (thrown #(srce/plugin! r {:name :x :apply (fn [_ _] nil) :update 42}))]
        (is (= :srce/bad-plugin (:type (ex-data e))))
        (is (some #{:update} (:problems (ex-data e))))))
    (testing "an unresolved keyword source"
      (is (= :srce/bad-source (thrown-type #(srce/plugin! r :nope)))))
    (testing "the resolver is the caller's"
      (let [r (srce/root {:resolver (fn [k]
                                      (when (= :demo k)
                                        {:name :demo :apply (fn [_ _] nil)}))})
            f (srce/plugin! r :demo)]
        (is (= :active (:state @f)))
        (is (= :demo (:name @f)))))))

(srce/defplugin demo-plugin
  {:name :demo/plugin
   :apply (fn [_ _] nil)})

(deftest defplugin-defines-a-source
  (let [[r] (kernel)
        f (srce/plugin! r #'demo-plugin)]
    (is (= :active (:state @f)))
    (is (= :demo/plugin (:name @f)))
    (is (= :demo/plugin (:srce/plugin (meta #'demo-plugin))))))

(deftest defplugin-expands-to-public-api-only
  ;; A macro a sandboxed caller uses must expand to `kmet.srce.core` and
  ;; nothing else: an extension context shares that one namespace, so an
  ;; expansion naming `kmet.srce.fiber` (or any other sub-namespace) fails to
  ;; evaluate there — the macro would be unusable exactly where it is needed.
  (let [form (macroexpand-1 '(srce/defplugin p {:name :p :apply (fn [_ _] nil)}))
        syms (filter symbol? (tree-seq coll? seq form))
        kmet-syms (filter #(str/starts-with? (str %) "kmet.srce.") syms)]
    (is (every? #(str/starts-with? (str %) "kmet.srce.core")
                kmet-syms)
        (str "defplugin expands to public kernel API only, got " (pr-str kmet-syms)))))

;; ── fibers and effects ────────────────────────────────────────────────────

(deftest one-runtime-many-fibers
  (let [[r] (kernel)
        f1 (srce/plugin! r #'demo-plugin)
        f2 (srce/plugin! r #'demo-plugin)]
    (is (srce/runtime? r #'demo-plugin))
    (is (= 1 (count (srce/runtimes r))))
    (is (= 2 (count @(:fibers (srce/runtime r #'demo-plugin)))))
    (is (not= (:uid @f1) (:uid @f2)))
    (srce/dispose! f1)
    (is (srce/runtime? r #'demo-plugin))
    (srce/dispose! f2)
    (is (not (srce/runtime? r #'demo-plugin)))
    (is (empty? (srce/runtimes r)))))

(deftest restart-allocates-a-new-fiber
  (let [[r] (kernel)
        f (srce/plugin! r #'demo-plugin)
        uid (:uid @f)
        f2 (srce/restart! f)]
    (is (not (identical? f f2)))
    (is (> (:uid @f2) uid))
    (is (= :disposed (:state @f)))
    (is (= :active (:state @f2)))))

(deftest apply-return-is-an-effect
  (let [[r] (kernel)
        cleaned (atom 0)
        f (srce/plugin! r {:name :p
                           :apply (fn [_ _] (fn [] (swap! cleaned inc)))})]
    (is (zero? @cleaned))
    (srce/dispose! f)
    (is (= 1 @cleaned))))

(deftest the-record-tracks-its-provides
  (let [[r] (kernel)
        f (srce/plugin! r {:name :p
                           :inject {:tools :optional}
                           :provide #{:a}
                           :apply (fn [c _] (srce/provide! c :a 1) nil)})]
    (is (= {:tools :optional} (:inject @f)))
    (is (= #{:a} (:provides @f)))
    (is (= 1 (srce/get (:ctx @f) :a)))
    (srce/dispose! f)
    (is (= #{} (:provides @f)))
    (is (nil? (srce/provider (:ctx @f) :a)))
    (is (nil? (srce/get r :a)))))

(deftest effects-unwind-lifo
  (let [[r] (kernel)
        events (atom [])
        f (srce/plugin! r {:name :p
                           :apply (fn [c _]
                                    (srce/effect c (fn []
                                                     (swap! events conj :a)
                                                     (fn [] (swap! events conj :a-undo)))
                                                 :a)
                                    (srce/effect c (fn []
                                                     (swap! events conj :b)
                                                     (fn [] (swap! events conj :b-undo)))
                                                 :b)
                                    nil)})]
    (is (= [:a :b] @events))
    (is (= [:a :b] (srce/effects f)))
    (srce/dispose! f)
    (is (= [:a :b :b-undo :a-undo] @events))))

(deftest a-disposed-registration-drops-its-effect-entry
  ;; A registration disposed before its fiber unloads leaves the live effect
  ;; list — what `/units` prints — rather than a dead label that outlives it.
  (let [[r] (kernel)
        f (srce/plugin! r {:name :p :apply (fn [_ _] nil)})
        ctx (:ctx (srce/fiber-info f))
        dispose (srce/on ctx :probe/event (fn [_]))]
    (is (= ["on :probe/event"] (srce/effects f)))
    (is (true? (dispose)) "the removal reports a registration it removed")
    (is (= [] (srce/effects f)) "the entry is gone with the registration")
    (is (nil? (dispose)) "the disposer is single-shot")))

(deftest inactive-effect-throws
  (let [[r] (kernel)
        ctxs (atom nil)
        f (srce/plugin! r {:name :p :apply (fn [c _] (reset! ctxs c) nil)})]
    (srce/dispose! f)
    (is (= :srce/inactive-effect (thrown-type #(srce/effect @ctxs (fn [] nil)))))))

(deftest registering-from-a-disposer-throws-and-is-logged
  (let [[r calls] (kernel)
        ctxs (atom nil)
        f (srce/plugin! r {:name :p
                           :apply (fn [c _]
                                    (reset! ctxs c)
                                    (srce/effect c (fn []
                                                     (fn [] (srce/effect c (fn [] nil))))
                                                 :bad)
                                    nil)})]
    (srce/dispose! f)
    (is (logged? calls :error "disposer threw"))))

(deftest a-throwing-disposer-does-not-stop-the-rest
  (let [[r calls] (kernel)
        events (atom [])
        f (srce/plugin! r {:name :p
                           :apply (fn [c _]
                                    (srce/effect c (fn []
                                                     (fn [] (throw (ex-info "boom" {}))))
                                                 :a)
                                    (srce/effect c (fn []
                                                     (fn [] (swap! events conj :b)))
                                                 :b)
                                    nil)})]
    (srce/dispose! f)
    (is (= [:b] @events))
    (is (logged? calls :error "disposer threw"))))

(deftest a-failed-apply-unwinds-and-frees-names
  (let [[r calls] (kernel)
        failures (atom [])
        _ (srce/on r :srce/failure (fn [ev] (swap! failures conj ev)))
        f (srce/plugin! r {:name :bad
                           :apply (fn [c _]
                                    (srce/provide! c :tools :table)
                                    (throw (ex-info "boom" {})))})]
    (is (= :failed (:state @f)))
    (is (some? (:error @f)))
    (is (nil? (srce/get r :tools)))
    (is (= 1 (count @failures)))
    (is (logged? calls :error "plugin failed"))
    (testing "the name is free again"
      (is (some? (srce/provide! r :tools :again)))
      (is (= :again (srce/get r :tools))))))

(deftest a-failed-provider-leaves-dependents-pending
  (let [[r] (kernel)
        applied (atom 0)
        _ (srce/plugin! r {:name :bad :apply (fn [_ _] (throw (ex-info "boom" {})))})
        f (srce/plugin! r {:name :needs
                           :inject [:svc]
                           :apply (fn [_ _] (swap! applied inc))})]
    (is (= :pending (:state @f)))
    (is (zero? @applied))))

(deftest provide-inside-apply-does-not-reenter
  (let [[r] (kernel)
        applied (atom 0)]
    (srce/provide! r :base :b)
    (let [f (srce/plugin! r {:name :p
                             :inject [:base]
                             :apply (fn [c _]
                                      (swap! applied inc)
                                      (srce/provide! c :derived 1)
                                      nil)})
          uid (:uid @f)]
      (is (= :active (:state @f)))
      (is (= 1 @applied))
      (is (= :p (:name @f)))
      (is (= uid (:uid @f)))
      (is (= 1 (srce/get (:ctx @f) :derived))))))

(deftest dispose-plugin-ends-every-fiber
  (let [[r] (kernel)
        f1 (srce/plugin! r #'demo-plugin)
        f2 (srce/plugin! r #'demo-plugin)]
    (srce/dispose-plugin! r #'demo-plugin)
    (is (= [:disposed :disposed] (mapv #(:state @%) [f1 f2])))
    (is (empty? (srce/runtimes r)))))

(deftest dispose-subtree-is-children-first
  (let [[r] (kernel)
        agent (srce/extend r {:name :agent})
        seam (srce/extend agent {:name :seam})
        order (atom [])
        _ (srce/on r :srce/status
                   (fn [ev]
                     (when (= :disposed (:to ev))
                       (swap! order conj (:name @(:fiber ev))))))
        _a (srce/plugin! agent {:name :a :apply (fn [_ _] nil)})
        _b (srce/plugin! seam {:name :b :apply (fn [_ _] nil)})
        c (srce/plugin! r {:name :c :apply (fn [_ _] nil)})]
    (srce/dispose-subtree! agent)
    (is (= [:b :a] @order))
    (is (= :active (:state @c)))
    (is (= 1 (count (srce/runtimes r))) "only c's runtime is left")))

(deftest a-disposed-tree-leaks-nothing
  (let [r (srce/root)
        f1 (srce/plugin! r {:name :p1 :apply (fn [c _] (srce/provide! c :a 1) nil)})
        agent (srce/extend (:ctx @f1) {:name :agent})
        f2 (srce/plugin! agent {:name :p2 :apply (fn [c _] (srce/provide! c :b 2) nil)})
        f3 (srce/plugin! r {:name :p3 :apply (fn [_ _] nil)})]
    (is (= #{:a} (set (keys (srce/services (:ctx @f1))))))
    (is (= 2 (srce/get (:ctx @f2) :b)))
    (doseq [f [f2 f3 f1]]
      (srce/dispose! f))
    (is (empty? (srce/runtimes r)))
    (is (empty? (srce/children r)))
    (is (empty? @(:store r)))
    (is (empty? (srce/services r)))))

;; ── events ────────────────────────────────────────────────────────────────

(deftest listeners-run-in-order-up-the-chain
  (let [r (srce/root)
        child (srce/extend r {:name :child})
        grand (srce/extend child {:name :grand})
        sib (srce/extend r {:name :sib})
        seen (atom [])]
    (srce/on r :e (fn [_] (swap! seen conj :root)))
    (srce/on child :e (fn [_] (swap! seen conj :child)))
    (srce/on sib :e (fn [_] (swap! seen conj :sib)))
    (srce/emit! grand :e {})
    (is (= [:child :root] @seen) "a sibling does not hear the subtree")
    (reset! seen [])
    (srce/emit! r :e {})
    (is (= [:root] @seen))
    (reset! seen [])
    (srce/emit! child :e {})
    (is (= [:child :root] @seen))))

(deftest emit-ignores-results-and-bail-stops-at-the-first-answer
  (let [r (srce/root)
        seen (atom [])]
    (srce/on r :e (fn [_] (swap! seen conj :first) nil))
    (srce/on r :e (fn [_] (swap! seen conj :second) :answer))
    (srce/on r :e (fn [_] (swap! seen conj :third) :ignored))
    (is (nil? (srce/emit! r :e {})) "emit returns nothing, whatever listeners return")
    (is (= [:first :second :third] @seen))
    (reset! seen [])
    (is (= :answer (srce/bail! r :e {})) "bail returns the first non-nil answer")
    (is (= [:first :second] @seen) "and the remaining listeners never see the event")
    (is (nil? (srce/bail! r :nobody {})) "nothing listening answers nothing")))

(deftest waterfall-wraps-the-rest-of-the-chain
  (let [r (srce/root)]
    (srce/on r :e (fn [_ next] (assoc (next) :outer true)))
    (srce/on r :e (fn [_ next] (assoc (next) :inner true)))
    (srce/on r :e (fn [_ _next] {:vetoed true}))
    (is (= {:vetoed true :inner true :outer true}
           (srce/waterfall! r :e {}))
        "each listener wraps the rest; the innermost vetoes")
    (is (nil? (srce/waterfall! r :nobody {})) "nothing listening is nil")))

(deftest a-throwing-listener-does-not-stop-bail-or-waterfall
  (let [[r _calls] (kernel)]
    (srce/on r :b (fn [_] (throw (ex-info "boom" {}))))
    (srce/on r :b (fn [_] :after-bail))
    (is (= :after-bail (srce/bail! r :b {})))
    (srce/on r :w (fn [_ _next] (throw (ex-info "boom" {}))))
    (srce/on r :w (fn [_ next] (next)))
    (is (nil? (srce/waterfall! r :w {})) "a rung that throws reads as nil")))

(deftest listener-filter-sees-the-listener-ctx
  (let [r (srce/root)
        child (srce/extend r {:name :child})
        seen (atom [])]
    (srce/on r :e (fn [_] (swap! seen conj :root)))
    (srce/on child :e (fn [_] (swap! seen conj :child)))
    (srce/emit! child :e {} {:filter #(identical? % child)})
    (is (= [:child] @seen))))

(deftest once-fires-once-and-disposes-first
  (let [r (srce/root)
        seen (atom [])]
    (srce/once r :e (fn [ev] (swap! seen conj [(srce/listeners r :e nil) ev])))
    (srce/emit! r :e 1)
    (srce/emit! r :e 2)
    (is (= [[] 1] (first @seen)) "the registration is gone before the call")
    (is (= 1 (count @seen)))))

(deftest a-throwing-listener-is-logged
  (let [[r calls] (kernel)
        seen (atom [])]
    (srce/on r :e (fn [_] (throw (ex-info "boom" {}))))
    (srce/on r :e (fn [_] (swap! seen conj :ok)))
    (srce/emit! r :e {})
    (is (= [:ok] @seen))
    (is (logged? calls :error "listener threw"))))

(deftest registration-data-rides-the-listener
  (let [r (srce/root)]
    (srce/on r :e {:data :host} (fn [_] :a-result))
    (srce/once r :e {:data :once-owner} (fn [_] :once-result))
    (is (= [[:host :a-result] [:once-owner :once-result]]
           (srce/collect-all! r :e {}))
        "the data a registration attached is reported beside its result")
    (is (= [[:host :a-result]] (srce/collect-all! r :e {}))
        "a `once` registration delivers once, with its data")))

(deftest event-names-enumerates-the-chain
  (let [r (srce/root)
        child (srce/extend r {})]
    (srce/on r :a (fn [_]))
    (srce/on r :b (fn [_]))
    (srce/on child :c (fn [_]))
    (is (= [:a :b :c] (srce/event-names child)))
    (is (= [:a :b] (srce/event-names r)) "an ancestor's own names only")))

(deftest a-listener-disposer-reports-removal
  (let [[r] (kernel)
        d (srce/on r :e (fn [_]))]
    (is (true? (d)))
    (is (nil? (d)))))

(deftest listeners-resolves-the-chain
  (let [r (srce/root)
        child (srce/extend r {})
        f (fn [_])
        g (fn [_])]
    (srce/on r :e {:data :root} f)
    (srce/on child :e {:data :child} g)
    (let [resolved (srce/listeners child :e nil)]
      (is (= [g f] (mapv :f resolved)) "the reading ctx first, then its ancestors")
      (is (= [:child :root] (mapv :data resolved)))
      (is (identical? child (:ctx (first resolved)))))))

(deftest dispatch-modes-names-every-mode-the-kernel-has
  (let [fns {:emit srce/emit!
             :bail srce/bail!
             :waterfall srce/waterfall!
             :collect-last srce/collect-last!
             :collect-all srce/collect-all!
             :boundary srce/boundary!}]
    (is (= (set (keys fns)) srce/dispatch-modes)
        "the capability list is exactly the modes with a fn behind them — an app
         declaring an event's mode reads this set, so it cannot promise more")))

(deftest kernel-events
  (let [[r] (kernel)
        statuses (atom [])
        services (atom [])]
    (srce/on r :srce/status (fn [ev] (swap! statuses conj (:to ev))))
    (srce/on r :srce/service (fn [ev] (swap! services conj [(:name ev) (:value ev)])))
    (let [f (srce/plugin! r #'demo-plugin)]
      (is (= [:pending :active] @statuses))
      (srce/dispose! f)
      (is (= :disposed (last @statuses)))
      (is (= :active (:state @(:fiber r)))))
    (let [d (srce/provide! r :svc :v)]
      (is (= [[:svc :v]] @services))
      (d)
      (is (= [[:svc :v] [:svc nil]] @services)))))

;; ── logger, settlement, threads ───────────────────────────────────────────

(deftest log-without-a-logger-is-silent
  (let [r (srce/root)]
    (is (nil? (srce/log! r :info "x")))
    (is (nil? (srce/log! r :error (ex-info "x" {}))))))

(deftest settle-is-idempotent
  (let [[r] (kernel)]
    (is (= {:rounds 1} (srce/settle! r)) "one empty pass")
    (is (= {:rounds 1} (srce/settle! r)))
    (is (empty? @(:changes (:kernel r))))))

(deftest two-threads-provide-at-once
  (let [[r] (kernel)
        a (future (srce/provide! r :a 1))
        b (future (srce/provide! r :b 2))]
    @a
    @b
    (srce/settle! r)
    (is (= 1 (srce/get r :a)))
    (is (= 2 (srce/get r :b)))
    (is (empty? @(:changes (:kernel r))))))

(deftest a-provider-cycle-is-bounded
  (let [[r calls] (kernel)]
    (srce/plugin! r {:name :cyclic
                     :inject {:self :optional}
                     :apply (fn [c _]
                              (srce/provide! c :self :value)
                              nil)})
    (is (logged? calls :warn ":srce/churn"))
    (is (empty? @(:changes (:kernel r))))
    (is (fn? (srce/provide! r :other 1)) "settle! returned; the kernel is usable")))

(deftest the-root-fiber-can-be-disposed
  (let [[r] (kernel)]
    (srce/provide! r :x 1)
    (srce/dispose! (:fiber r))
    (is (= :disposed (:state @(:fiber r))))
    (is (nil? (srce/get r :x)))
    (is (nil? (srce/log! r :info "silent")))
    (is (= :srce/inactive-effect (thrown-type #(srce/provide! r :y 2))))))

;; ── inspection ────────────────────────────────────────────────────────────

(deftest fiber-info-is-a-plain-snapshot
  (let [[r] (kernel)
        f (srce/plugin! r {:name :probe/info
                           :provide #{:probe}
                           :apply (fn [c _]
                                    (srce/provide! c :probe :value)
                                    (srce/effect c (constantly nil) :probe/effect)
                                    nil)})]
    (is (srce/active? f) "a satisfied mount is active immediately")
    (let [info (srce/fiber-info f)]
      (is (map? info) "plain data, not the record's atom")
      (is (= :probe/info (:name info)))
      (is (= :active (:state info)))
      (is (= #{:probe} (:provides info)))
      (is (= (srce/effects f) (:effects info)) "the labels are the kernel's")
      (is (some #{:probe/effect} (:effects info)))
      (is (identical? f (srce/mount-fiber (:ctx info))) "the info carries the mount's ctx")
      (srce/dispose! f)
      (is (= :active (:state info)) "the projection is a snapshot")
      (is (not (srce/active? f)))
      (is (= :disposed (:state (srce/fiber-info f)))))))

(deftest fibers-is-mount-order-and-drops-disposed
  (let [[r] (kernel)
        a (srce/plugin! r {:name :probe/a :apply (fn [_ _] nil)})
        b (srce/plugin! r {:name :probe/b
                           :inject {:probe/a :optional}
                           :apply (fn [_ _] nil)})]
    (is (= [:probe/a :probe/b] (mapv :name (srce/fibers r))))
    (is (apply < (mapv :uid (srce/fibers r))) "mount order is uid order")
    (is (= (mapv :name (srce/fibers r))
           (mapv :name (srce/fibers (srce/extend r {}))))
        "the mount list is the root's, so a child ctx answers the same one")
    (srce/dispose! a)
    (is (= [:probe/b] (mapv :name (srce/fibers r))) "a disposed fiber leaves the list")
    (is (srce/active? b) "an optional dependency does not hold a mount pending")))

;; ── in-place config updates and teardown results ──────────────────────────

(deftest update-needs-an-active-fiber-and-a-hook
  (let [[r] (kernel)
        plain (srce/plugin! r {:name :probe/plain :apply (fn [_ _] nil)})
        pending (srce/plugin! r {:name :probe/pending
                                 :inject {:probe/missing nil}
                                 :apply (fn [_ _] nil)})]
    (is (= :srce/no-update (thrown-type #(srce/update! plain {:a 1})))
        "a plugin that cannot absorb a config change refuses by name")
    (is (= :srce/inactive-update (thrown-type #(srce/update! pending {:a 1})))
        "only an :active fiber can be updated")
    (is (= :pending (:state (srce/fiber-info pending))))))

(deftest update-keeps-the-fiber-and-does-not-remount-dependents
  (let [[r] (kernel)
        applied (atom 0)
        seen (atom [])
        provider (srce/plugin! r {:name :probe/provider
                                  :provide #{:probe}
                                  :config (fn [cfg] (assoc cfg :resolved true))
                                  :update (fn [_ old new] (swap! seen conj [old new]))
                                  :apply (fn [c cfg]
                                           (srce/provide! c :probe cfg {:in (:parent c)})
                                           nil)}
                               {:n 1})]
    (srce/plugin! r {:name :probe/dependent
                     :inject {:probe nil}
                     :apply (fn [_ _] (swap! applied inc) nil)})
    (let [before (srce/fiber-info provider)]
      (is (= 1 @applied))
      (srce/update! provider {:n 2})
      (let [after (srce/fiber-info provider)]
        (is (= (:uid before) (:uid after)) "same fiber, same uid")
        (is (= [[{:n 1 :resolved true} {:n 2 :resolved true}]] @seen)
            "the hook gets the old and the resolved new config")
        (is (= {:n 2} (:config after)) "the raw config is what is stored")
        (is (= 1 @applied) "no dependent remounted")
        (is (= {:n 1 :resolved true} (srce/get r :probe))
            "a dependent keeps the value it resolved — a config update is a snapshot")))))

(deftest update-stores-nothing-when-validation-throws
  (let [[r] (kernel)
        seen (atom [])
        f (srce/plugin! r {:name :probe/strict
                           :config (fn [cfg]
                                     (when-not (:ok cfg)
                                       (throw (ex-info "bad config" {:type ::bad})))
                                     cfg)
                           :update (fn [_ _ cfg] (swap! seen conj cfg))
                           :apply (fn [_ _] nil)}
                        {:ok true})]
    (is (thrown? Throwable (srce/update! f {:ok false})))
    (is (empty? @seen) "the hook did not run")
    (is (= {:ok true} (:config (srce/fiber-info f))) "the old config is intact")
    (is (srce/active? f))))

(deftest teardown-reports-what-threw
  (let [[r] (kernel)
        second-ran (atom false)
        f (srce/plugin! r {:name :probe/teardown
                           :apply (fn [c _]
                                    (srce/effect c (fn [] (fn [] (throw (ex-info "boom" {}))))
                                                 :probe/first)
                                    (srce/effect c (fn [] (fn [] (reset! second-ran true)))
                                                 :probe/second)
                                    nil)})]
    (let [{:keys [disposed failed]} (srce/dispose! f)]
      (is (= 1 disposed))
      (is (= 1 (count failed)))
      (is (= :probe/first (:label (first failed))) "the failing effect is named")
      (is (= "boom" (ex-message (:error (first failed)))))
      (is (= "boom" (:message (first failed))) "the report carries text, not only the throwable")
      (is (true? @second-ran) "the remaining disposers still ran"))
    (is (= {:disposed 0 :failed [] :undoing []} (srce/dispose! f)) "a second dispose is a no-op")))

(deftest teardown-reports-work-still-running
  (let [[r] (kernel)
        pending (promise)
        delivered (promise)
        f (srce/plugin! r {:name :probe/undoing
                           :apply (fn [c _]
                                    (srce/effect c (fn [] (fn [] pending)) :probe/pending)
                                    (srce/effect c (fn [] (fn [] delivered)) :probe/delivered)
                                    (srce/effect c (fn [] (fn [] (atom :value))) :probe/plain)
                                    nil)})]
    (deliver delivered :done)
    (let [{:keys [disposed undoing]} (srce/dispose! f)]
      (is (= 1 disposed))
      (is (= [:probe/pending] (mapv :label undoing))
          "an unrealized promise is in flight; a delivered one and a plain value are not")
      (is (identical? pending (:handle (first undoing))) "the report carries the handle itself")
      (is (not (realized? pending)) "the kernel did not wait for it"))
    (is (= {:disposed 0 :failed [] :undoing []} (srce/dispose! f))
        "a second dispose is a no-op")))

(deftest in-flight-work-aggregates-and-reports-into-the-outer-run
  (let [[r] (kernel)
        inner-p (promise)
        inner (srce/plugin! r {:name :probe/inner-undoing
                               :apply (fn [c _]
                                        (srce/effect c (fn [] (fn [] inner-p)) :probe/inner)
                                        nil)})
        outer-p (promise)
        outer (srce/plugin! r {:name :probe/outer-undoing
                               :apply (fn [c _]
                                        (srce/effect c (fn [] (fn [] (srce/dispose! inner)))
                                                     :probe/outer)
                                        (srce/effect c (fn [] (fn [] outer-p)) :probe/outer-undo)
                                        nil)})]
    (is (= [:probe/outer-undo :probe/inner] (mapv :label (:undoing (srce/dispose! outer))))
        "a nested dispose reports into the outer run, in unwind order")
    (is (= {:disposed 0 :failed [] :undoing []} (srce/dispose! inner))
        "the nested call itself reports nothing pending"))
  (let [[r] (kernel)
        a (promise)
        b (promise)
        _ (srce/plugin! r {:name :probe/sub-a
                           :apply (fn [c _] (srce/effect c (fn [] (fn [] a)) :probe/a) nil)})
        _ (srce/plugin! r {:name :probe/sub-b
                           :apply (fn [c _] (srce/effect c (fn [] (fn [] b)) :probe/b) nil)})]
    (is (= [:probe/b :probe/a] (mapv :label (:undoing (srce/dispose-subtree! r))))
        "a subtree teardown aggregates every handle, children first")))

(deftest a-dispose-inside-a-disposer-reports-into-the-outer-run
  (let [[r] (kernel)
        inner (srce/plugin! r {:name :probe/inner
                               :apply (fn [c _]
                                        (srce/effect c (fn [] (fn [] (throw (ex-info "inner" {}))))
                                                     :probe/inner)
                                        nil)})
        outer (srce/plugin! r {:name :probe/outer
                               :apply (fn [c _]
                                        (srce/effect c (fn [] (fn [] (srce/dispose! inner)))
                                                     :probe/outer)
                                        nil)})]
    (let [{:keys [disposed failed]} (srce/dispose! outer)]
      (is (= 1 disposed))
      (is (= [:probe/inner] (mapv :label failed))
          "the nested run's failure is the outer call's to report"))
    (is (= {:disposed 0 :failed [] :undoing []} (srce/dispose! inner)) "already disposed")))

(deftest aggregate-disposals-report-per-fiber-failures
  (let [[r] (kernel)
        ok (srce/plugin! r {:name :probe/ok :apply (fn [_ _] nil)})
        bad (srce/plugin! r {:name :probe/bad
                             :apply (fn [c _]
                                      (srce/effect c (fn [] (fn [] (throw (ex-info "bad" {}))))
                                                   :probe/bad)
                                      nil)})]
    (let [{:keys [disposed failed]} (srce/dispose-subtree! r)]
      (is (= 2 disposed) "children first, both fibers")
      (is (= [{:label :probe/bad :message "bad"}]
             (mapv #(select-keys % [:label :message]) failed))
          "the failure keeps the effect's label and its message"))
    (is (not (srce/active? ok)))
    (is (not (srce/active? bad)))))

(deftest an-update-that-provides-a-new-name-reloads-dependents
  (let [[r] (kernel)
        applied (atom 0)
        provider (srce/plugin! r {:name :probe/grower
                                  :update (fn [c _ cfg]
                                            (srce/provide! c :grown cfg {:in (:parent c)}))
                                  :apply (fn [_ _] nil)})]
    (srce/plugin! r {:name :probe/late
                     :inject {:grown nil}
                     :apply (fn [_ _] (swap! applied inc) nil)})
    (is (= 0 @applied) "nothing provides :grown yet")
    (srce/update! provider :value)
    (is (= 1 @applied) "the update's own drain reloads the dependent")
    (is (= :value (srce/get r :grown)))))

(deftest a-throwing-update-unwinds-what-its-hook-registered
  (let [[r] (kernel)
        f (srce/plugin! r {:name :probe/partial
                           :update (fn [c _ new]
                                     (srce/provide! c :partial new {:in (:parent c)})
                                     (throw (ex-info "late" {})))
                           :apply (fn [_ _] nil)}
                        :before)]
    (is (thrown? Throwable (srce/update! f :after)))
    (is (nil? (srce/get r :partial)) "the hook's provide was unwound")
    (is (= :before (:config (srce/fiber-info f))) "the config is untouched")
    (is (srce/active? f) "the mount is still live")))
