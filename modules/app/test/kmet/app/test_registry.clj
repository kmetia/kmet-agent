(ns kmet.app.test-registry
  "The registry contract, over the handle the app installs (`atom-registry`).
   It ran against the legacy CLI's scope-store handle too until 4e deleted the store;
   the rules stayed the same, so the assertions did."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.registry :as registry]))

(defn- fresh
  "A fresh handle per case, so one case's registrations never leak into the
   next."
  []
  (registry/atom-registry))

(deftest an-unpublished-handle-still-remembers
  (testing "a caller with no ctx in hand reads one per-process handle, not a
            fresh map per call: a registration made before the owner publishes
            is still visible to the next read"
    (let [previous (registry/publish-process-handle! nil)]
      (try
        (is (identical? (registry/current) (registry/current))
            "the fallback is one handle, not one per call")
        (registry/register! (registry/current) :kmet/prompt "probe" {:name "probe"} [:test])
        (is (= {:name "probe"} (registry/entry (registry/current) :kmet/prompt "probe")))
        (finally (registry/clear! (registry/current) :kmet/prompt)
                 (registry/publish-process-handle! previous))))))

(deftest publishing-hands-the-previous-handle-back
  (testing "a second owner takes over and can put the first one back; nil
            un-publishes, so a test never leaves the process pointed at its own
            handle"
    (let [a (fresh)
          b (fresh)
          before (registry/publish-process-handle! a)]
      (try
        (is (identical? a (registry/current)))
        (is (identical? a (registry/publish-process-handle! b)) "the handover returns A")
        (is (identical? b (registry/current)))
        (is (identical? b (registry/publish-process-handle! nil)) "un-publish returns B")
        (is (not (identical? b (registry/current))) "nil un-publishes back to the fallback")
        (finally (registry/publish-process-handle! before))))))

(deftest register-entry-and-dispose
  (let [r (fresh)
        dispose (registry/register! r :kmet/tool "read" {:name "read"} [:app :tools])]
    (is (= {:name "read"} (registry/entry r :kmet/tool "read")))
    (is (= [["read" {:name "read"}]] (registry/entries r :kmet/tool)))
    (is (nil? (registry/entry r :kmet/tool "probe")))
    (is (true? (dispose)) "the disposer reports the removal")
    (is (nil? (registry/entry r :kmet/tool "read")))
    (is (false? (dispose)) "a stale disposer removes nothing")))

(deftest one-owner-re-registering-replaces
  (let [r (fresh)]
    (registry/register! r :kmet/tool "read" {:name "read" :v 1} [:app :tools])
    (registry/register! r :kmet/tool "read" {:name "read" :v 2} [:app :tools])
    (is (= {:name "read" :v 2} (registry/entry r :kmet/tool "read")))
    (is (= 1 (count (registry/entries r :kmet/tool))))))

(deftest the-owner-that-took-a-name-keeps-it
  (let [r (fresh)]
    (registry/register! r :kmet/skill "review" {:from :first} [:ext/a])
    (registry/register! r :kmet/skill "review" {:from :second} [:ext/b])
    (is (= {:from :first} (registry/entry r :kmet/skill "review"))
        "lookup reads display order, so the first owner wins")
    (is (= {[:ext/a] {:from :first} [:ext/b] {:from :second}}
           (registry/by-owner r :kmet/skill "review"))
        "by-owner reports each owner's own value")
    (is (= 2 (count (registry/entries r :kmet/skill))))
    (is (= [["review" {:from :first}]]
           (registry/effective-entries r :kmet/skill))
        "listing collapses to one entry per name, and lookup agrees")))

(deftest deregister-clears-by-tag-across-owners
  (let [r (fresh)]
    (registry/register! r :kmet/tool "a" 1 [:app :tools])
    (registry/register! r :kmet/tool "a" 2 [:ext/x])
    (registry/register! r :kmet/command "a" :cmd [:app :commands])
    (registry/deregister! r :kmet/tool "a" [:ext/x])
    (is (= [[:app :tools]] (keys (registry/by-owner r :kmet/tool "a")))
        "an owner-scoped removal leaves the other owner's registration")
    (registry/deregister! r :kmet/tool "a" nil)
    (is (empty? (registry/entries r :kmet/tool)))
    (is (= [["a" :cmd]] (registry/entries r :kmet/command))
        "another tag's registrations survive")
    (registry/clear! r :kmet/command)
    (is (empty? (registry/entries r :kmet/command)))))