(ns kmet.libs.test-weak
  "Unit tests for kmet.libs.weak: registration and
   refresh, key conflicts, unregister payloads, queue-gated sweeps and
   on-dead isolation. Collection is simulated deterministically (`.clear` +
   `.enqueue` on the entry's WeakReference, exactly what the GC does) so the
   fast suite needs no GC; the ^:slow GC regressions live with the
   integrations (Stages B/C)."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.weak :as weak]))

(defn- fresh-key []
  (keyword (gensym "weak-test")))

(defn- ref-of [key]
  (:ref (get @(deref #'weak/registry) key)))

(defn- collect!
  "Make KEY's subject look collected: clear the entry's weak reference and
   enqueue it, exactly what the GC would have done."
  [key]
  (let [r (ref-of key)]
    (.clear r)
    (.enqueue r)))

(deftest register-subject-and-payload
  (testing "register! stores a weakly held subject and its payload"
    (let [k (fresh-key)
          s (Object.)
          p {:atoms #{:a :b}}]
      (weak/register! k s p identity)
      (is (identical? s (weak/subject k)))
      (is (= p (weak/payload k)))
      (is (nil? (weak/subject (fresh-key))) "unknown keys have no subject")
      (is (nil? (weak/payload (fresh-key))) "unknown keys have no payload"))))

(deftest register-refreshes-without-reallocating-the-ref
  (testing "the same live subject keeps its WeakReference; the payload and
            on-dead are the latest"
    (let [k (fresh-key)
          s (Object.)
          dead (atom 0)]
      (weak/register! k s {:n 1} (fn [_ _] (swap! dead inc)))
      (let [r (ref-of k)]
        (weak/register! k s {:n 2} (fn [_ _] (swap! dead inc)))
        (is (identical? r (ref-of k)) "one ref for one subject")
        (is (= {:n 2} (weak/payload k))))
      (is (zero? @dead) "refresh never runs on-dead"))))

(deftest register-after-death-installs-a-fresh-entry
  (testing "a collected subject frees its key for a new subject (sweep on
            register), and the dead entry's on-dead runs"
    (let [k (fresh-key)
          s1 (Object.)
          dead (atom [])]
      (weak/register! k s1 {:n 1} (fn [kk p] (swap! dead conj [kk p])))
      (collect! k)
      (let [s2 (Object.)]
        (weak/register! k s2 {:n 2} identity)
        (is (identical? s2 (weak/subject k)))
        (is (= {:n 2} (weak/payload k)))
        (is (= [[k {:n 1}]] @dead)
            "register!'s opportunistic sweep claimed the dead entry")))))

(deftest register-throws-on-a-different-live-subject
  (testing "a shared key between two live subjects is loud, not silent
            watch-stealing"
    (let [k (fresh-key)
          s1 (Object.)
          s2 (Object.)]
      (weak/register! k s1 {:n 1} identity)
      (let [e (try (weak/register! k s2 {:n 2} identity)
                   nil
                   (catch Exception ex ex))]
        (is (some? e) "register! throws")
        (is (= :kmet.libs.weak/subject-conflict (:type (ex-data e))))
        (is (identical? s1 (weak/subject k)) "the original entry survived")
        (is (= {:n 1} (weak/payload k)))))))

(deftest unregister-returns-payload-without-on-dead
  (testing "deterministic teardown returns what unsubscribe needs and leaves
            on-dead to the sweep"
    (let [k (fresh-key)
          s (Object.)
          p {:atoms #{:a}}
          dead (atom [])]
      (weak/register! k s p (fn [kk pp] (swap! dead conj [kk pp])))
      (is (= p (weak/unregister! k)))
      (is (nil? (weak/unregister! k)) "idempotent")
      (is (nil? (weak/subject k)))
      (is (nil? (weak/payload k)))
      (is (empty? @dead) "on-dead did not run")
      (testing "a pending reference dropped by unregister! is drained without
                claiming the entry it belonged to"
        (let [k2 (fresh-key)
              ran (atom [])]
          (weak/register! k2 (Object.) {} (fn [kk _] (swap! ran conj kk)))
          (collect! k2)
          (weak/unregister! k2)
          (weak/sweep!)
          (is (empty? @ran) "the unregistered entry's on-dead never ran")
          (is (nil? (weak/subject k2))))))))

(deftest sweep-claims-dead-entries-and-runs-on-dead
  (testing "only the collected subject's entry is claimed; its payload
            reaches on-dead and the entry is gone"
    (let [k1 (fresh-key)
          k2 (fresh-key)
          s2 (Object.)
          dead (atom [])]
      (weak/register! k1 (Object.) {:n 1} (fn [kk p] (swap! dead conj [kk p])))
      (weak/register! k2 s2 {:n 2} identity)
      (collect! k1)
      (is (pos? (weak/sweep!)) "the sweep claimed at least the dead entry")
      (is (= [[k1 {:n 1}]] @dead) "on-dead got the key and its payload")
      (is (nil? (weak/subject k1)))
      (is (nil? (weak/payload k1)) "the entry was removed")
      (is (identical? s2 (weak/subject k2)) "the live entry survived"))))

(deftest concurrent-sweeps-claim-an-entry-once
  (testing "concurrent sweep! calls cannot run one entry's on-dead twice"
    (let [k (fresh-key)
          dead (atom 0)]
      (weak/register! k (Object.) {} (fn [_ _] (swap! dead inc)))
      (collect! k)
      (run! deref (mapv (fn [_] (future (weak/sweep!))) (range 16)))
      (is (= 1 @dead) "exactly one on-dead")
      (is (nil? (weak/subject k))))))

(deftest on-dead-errors-are-isolated-per-entry
  (testing "one throwing on-dead neither stops its siblings nor the sweep"
    (let [k1 (fresh-key)
          k2 (fresh-key)
          ran (atom [])
          err (java.io.StringWriter.)]
      (weak/register! k1 (Object.) {:n 1}
                      (fn [kk _] (swap! ran conj kk) (throw (ex-info "boom" {}))))
      (weak/register! k2 (Object.) {:n 2} (fn [kk _] (swap! ran conj kk)))
      (collect! k1)
      (collect! k2)
      (let [n (binding [*err* err] (weak/sweep!))]
        (is (>= n 2) "both entries were claimed")
        (is (= #{k1 k2} (set @ran)) "both on-dead fns ran despite the throw")
        (is (str/includes? (str err) "on-dead error")
            "the failure was reported on stderr")
        (is (nil? (weak/subject k1)))
        (is (nil? (weak/subject k2)))))))

(deftest live-count-filters-by-payload
  (testing "consumers sharing the registry derive their own kind's count from
            the payload (track! components vs reactions)"
    (let [ka (fresh-key)
          kb (fresh-key)]
      (weak/register! ka (Object.) {:t/kind :a} identity)
      (weak/register! kb (Object.) {:t/kind :b} identity)
      (is (= 1 (weak/live-count (fn [p] (= :a (:t/kind p))))))
      (is (= 1 (weak/live-count (fn [p] (= :b (:t/kind p))))))
      (is (zero? (weak/live-count (fn [_] false))) "a rejecting pred counts none")
      (collect! ka)
      (is (zero? (weak/live-count (fn [p] (= :a (:t/kind p)))))
          "a collected subject is not counted")
      (weak/unregister! kb)
      (is (zero? (weak/live-count (fn [p] (= :b (:t/kind p)))))
          "an unregistered entry is not counted"))))
