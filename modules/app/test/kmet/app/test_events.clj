(ns kmet.app.test-events
  "kmet.app.events — the app's bus: the vocabulary, the owner a registration
   carries, and the dispatch its declaration drives.

   All six non-awaiting modes are the kernel's (`kmet.srce.core`, `srce.md` §5);
   what this namespace adds is what a kernel must not know — the event names,
   the owner an app registration carries (`{:data owner}`, reported by
   `srce/collect-all!`), the **declaration** that picks the mode, and which
   declarations are public. The kernel's own tests hold the folds; these hold
   the app layer that dispatches them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.events :as events]
            [kmet.app.registry :as registry]
            [kmet.srce.core :as srce]))

(defn- fresh-ctx
  "A bare root ctx per test/clearing: a root is a value, so clearing is
   dropping one — and its fiber is already active, which a registration needs."
  []
  (srce/root))

(defn- sub!
  "Register F for TYPE with an owner (nil for a host registration)."
  [ctx type owner f]
  (events/on! ctx type (when owner {:owner owner}) f))

;; ── the vocabulary ───────────────────────────────────────────────────────

(deftest test-events-vocabulary
  (let [types (:events events/plugin)]
    (is (map? types))
    (is (pos? (count types)))
    (is (every? keyword? (keys types)))
    (is (every? (comp string? :doc) (vals types)))))

(deftest test-events-core-types-known
  (doseq [type [:agent-start :agent-end :agent-settled :turn-start :turn-end
                :agent-before-settle
                :message-start :message-update :message-end
                :tool-execution-start :tool-execution-prepared
                :tool-execution-update :tool-execution-end
                :status :error :user-bash]]
    (is (events/known-event-type? type) (str type " should be a known event type"))))

(deftest test-events-unknown-types
  (is (not (events/known-event-type? :bogus)))
  (is (not (events/known-event-type? nil)))
  (is (not (events/known-event-type? "agent-start"))))

(deftest test-session-lifecycle-events-are-app-events
  (is (events/known-event-type? :session-shutdown)
      "session_shutdown is part of the vocabulary (pi: SessionShutdownEvent)")
  (is (contains? events/app-event-types :session-shutdown)
      "emitted outside the loop by the interactive mode, like :session-start")
  (is (contains? events/app-event-types :resources-discover)
      "emitted outside the loop (extensions/discover-resources!)"))

(deftest test-a-listener-hears-its-own-subtree
  ;; The kernel's rule: a listener hears emissions made at its ctx or below it,
  ;; and the host/root registration hears every agent under it — which is what
  ;; the extension ABI relies on.
  (let [root (fresh-ctx)
        child (srce/extend root {})
        grandchild (srce/extend child {})
        sibling (srce/extend root {})
        scoped (atom [])
        at-root (atom [])]
    (events/on! child :turn-start (fn [e] (swap! scoped conj (:from e))))
    (events/on! root :turn-start (fn [e] (swap! at-root conj (:from e))))
    (events/emit! child {:type :turn-start :from :child})
    (events/emit! grandchild {:type :turn-start :from :grandchild})
    (is (= [:child :grandchild] @scoped)
        "the listener hears its own ctx and its descendants")
    (is (= [:child :grandchild] @at-root)
        "an ancestor's listener hears the same emissions (pi: emit)")
    (events/emit! root {:type :turn-start :from :root})
    (events/emit! sibling {:type :turn-start :from :sibling})
    (is (= [:child :grandchild] @scoped) "the parent and a sibling stay out")
    (is (= [:child :grandchild :root :sibling] @at-root)
        "the root listener hears every ctx in the tree")))

(deftest test-an-emission-with-no-ctx-is-a-caller-error
  (is (thrown? Exception (events/emit! nil {:type :turn-start}))))

(deftest test-an-unknown-event-type-is-reported
  ;; A typo'd :turn-ended registers anyway (the vocabulary is documented, not a
  ;; gate) and is reported through the ctx's :logger service (F12).
  (let [ctx (fresh-ctx)
        reported (atom [])]
    (srce/provide! ctx :logger (fn [level & args] (swap! reported conj [level (apply str args)])))
    (events/on! ctx :turn-ended identity)
    (is (seq @reported) "the unknown type is reported")
    (is (str/includes? (str (first @reported)) "turn-ended"))
    (is (some #{:turn-ended} (srce/event-names ctx))
        "and the registration still lands")))

;; ── delivery through the app dispatcher ───────────────────────────────────

(deftest the-app-bus-delivers
  (testing "registration order, and every listener runs"
    (let [ctx (fresh-ctx)
          seen (atom [])]
      (sub! ctx :turn-start nil (fn [e] (swap! seen conj [:a (:n e)])))
      (sub! ctx :turn-start nil (fn [e] (swap! seen conj [:b (:n e)])))
      (events/emit! ctx {:type :turn-start :n 1})
      (is (= [[:a 1] [:b 1]] @seen))))

  (testing "the last non-nil result wins"
    (let [ctx (fresh-ctx)]
      (sub! ctx :session-before-tree nil (constantly nil))
      (sub! ctx :session-before-tree nil (constantly {:summary "one"}))
      (sub! ctx :session-before-tree nil (constantly nil))
      (sub! ctx :session-before-tree nil (constantly {:summary "two"}))
      (is (= {:summary "two"}
             (events/emit! ctx {:type :session-before-tree})))))

  (testing "a throwing listener is reported and does not stop the rest"
    (let [ctx (fresh-ctx)
          seen (atom [])]
      (sub! ctx :session-before-tree nil (fn [_] (throw (ex-info "boom" {}))))
      (sub! ctx :session-before-tree nil (fn [_] (swap! seen conj :after) :after))
      (is (= :after (events/emit! ctx {:type :session-before-tree}))
          "the throw contributed nothing; the next listener answered")
      (is (= [:after] @seen))))

  (testing "collect-all reports every non-nil result beside its owner"
    (let [ctx (fresh-ctx)]
      (sub! ctx :resources-discover [:app :extensions "a"]
            (constantly {:prompt-paths ["/a"]}))
      (sub! ctx :resources-discover [:app :extensions "b"] (constantly nil))
      (sub! ctx :resources-discover nil (constantly {:prompt-paths ["/host"]}))
      (is (= [[[:app :extensions "a"] {:prompt-paths ["/a"]}]
              [nil {:prompt-paths ["/host"]}]]
             (events/emit! ctx {:type :resources-discover})))))

  (testing "boundary threads the accumulated map into each listener"
    (let [ctx (fresh-ctx)]
      (sub! ctx :turn-end nil (fn [e]
                                (is (= 1 (:n e)))
                                {:entries [:a]}))
      (sub! ctx :turn-end nil (fn [e]
                                (is (= [:a] (:entries e))
                                    "the next listener sees what was accumulated")
                                {:continue false}))
      (sub! ctx :turn-end nil (constantly nil))
      (is (= {:entries [:a] :continue false}
             (events/emit! ctx {:type :turn-end :n 1})))))

  (testing "nothing listening collects nothing"
    (let [ctx (fresh-ctx)]
      (is (= [] (events/emit! ctx {:type :resources-discover})))
      (is (nil? (events/emit! ctx {:type :turn-start})))
      (is (nil? (events/emit! ctx {:type :turn-end})))))

  (testing "a deregistered listener stops hearing, and the names list the rest"
    (let [ctx (fresh-ctx)
          seen (atom [])
          dereg (sub! ctx :message-end nil (fn [e] (swap! seen conj (:n e))))]
      (sub! ctx :message-end nil (constantly nil))
      (events/emit! ctx {:type :message-end :n 1})
      (dereg)
      (events/emit! ctx {:type :message-end :n 2})
      (is (= [1] @seen))
      (is (= [:message-end] (vec (srce/event-names ctx)))
          "the surviving registration is the only name left"))))

(deftest test-a-contradicting-declaration-is-refused
  ;; A plugin may add a name, or re-declare one identically; it may not change
  ;; how an already-declared event dispatches — including a built-in one.
  (let [ctx (fresh-ctx)
        spec {:mode :emit :domain :app :doc "mine"}]
    (srce/provide! ctx :events (registry/atom-registry))
    (events/declare! ctx [:app :a] {:my/event spec})
    (is (= :emit (:mode (events/declaration ctx :my/event))))
    (is (nil? (events/declare! ctx [:app :b] {:my/event spec}))
        "an identical re-declaration is allowed")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already declared differently"
                          (events/declare! ctx [:app :c]
                                           {:my/event (assoc spec :mode :collect-last)}))
        "a contradicting spec is refused")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already declared differently"
                          (events/declare! ctx [:app :c]
                                           {:turn-end {:mode :emit :domain :loop :doc "hijack"}}))
        "a built-in event cannot be re-declared differently")
    (is (thrown? clojure.lang.ExceptionInfo
                 (events/declare! ctx [:app :d]
                                  {:new/one spec
                                   :turn-end {:mode :emit :domain :loop :doc "hijack"}})))
    (is (nil? (events/declaration ctx :new/one))
        "a refused set registers none of its names")))

;; ── the public stream: extension-only events stay in-process ─────────────

(deftest test-on-every-is-the-public-stream
  ;; The provider bridges carry the request's payload and headers (an
  ;; `Authorization` header among them) and the session-before hooks carry a
  ;; control object. They are for extensions, which subscribe by name; the
  ;; public stream a front end or a wire consumer takes must not carry them.
  (let [ctx (fresh-ctx)
        seen (atom [])
        declared (events/builtin-events)
        dispose (events/on-every ctx (fn [event] (swap! seen conj (:type event))))]
    (try
      (doseq [{:keys [name]} declared]
        (events/emit! ctx {:type name}))
      (is (= (set (map :name (filter :public declared))) (set @seen))
          "the stream carries exactly the public declarations")
      (is (some (comp not :public) declared)
          "the vocabulary has extension-only events at all")
      (is (not (contains? (set @seen) :before-provider-headers))
          "a request header map never reaches the stream")
      (finally (dispose)))))

;; ── the dispatcher: the declaration picks the mode ────────────────────────

(deftest test-emit!-dispatches-by-declared-mode
  (let [ctx (fresh-ctx)
        seen (atom [])]
    (testing ":emit runs listeners and returns nil"
      (events/on! ctx :message-start (fn [e] (swap! seen conj (:type e))))
      (is (nil? (events/emit! ctx {:type :message-start})))
      (is (= [:message-start] @seen)))
    (testing ":collect-last returns the last non-nil answer"
      (events/on! ctx :session-before-switch (fn [_] {:cancel true}))
      (is (= {:cancel true}
             (events/emit! ctx {:type :session-before-switch}))))
    (testing ":collect-all returns [owner result] pairs"
      (events/on! ctx :resources-discover {:owner [:app :extensions "x"]}
                  (fn [_] {:prompt-paths ["/x"]}))
      (is (= [[[:app :extensions "x"] {:prompt-paths ["/x"]}]]
             (events/emit! ctx {:type :resources-discover}))))
    (testing ":boundary returns the accumulated decision"
      (events/on! ctx :turn-end (fn [_] {:entries [:a]}))
      (is (= {:entries [:a]} (events/emit! ctx {:type :turn-end}))))
    (testing "an undeclared type is reported and dispatches as :emit"
      (let [typed (atom nil)]
        (events/on! ctx :my-custom (fn [e] (reset! typed (:type e))))
        (is (nil? (events/emit! ctx {:type :my-custom})))
        (is (= :my-custom @typed))))
    (testing "a declaration naming an unknown mode is refused"
      (with-redefs [events/builtin-declarations (fn [] {:bad {:mode :serial}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown dispatch mode"
                              (events/emit! ctx {:type :bad})))))))
