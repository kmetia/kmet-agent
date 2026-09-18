(ns kmet.libs.test-reakt
  "Pure engine tests for kmet.libs.reakt — no TUI, no components: dependency
   discovery across branches, =-gated watch notification, cursors, manual
   tracks, disposal semantics, and the changed? gate. (The engine's
   integration with component render bodies is covered in
   kmet.tui.test-reakt-integration / test-compute / test-track.)"
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.reakt :as r]))

(deftest test-changed-gate
  (testing "identical? fast path + structural = fallback"
    (let [a (atom [1 2])]
      (is (r/changed? a (atom [1 2])) "different identities are 'changed'")
      (reset! a [1 2])
      (is (not (r/changed? @a [1 2])) "equal values by = are not")
      (is (r/changed? @a [1 3])))))

(deftest test-derive-dep-discovery-and-gating
  (testing "derive re-runs only on real dep changes; equal resets notify nobody"
    (let [a (atom 1)
          runs (atom 0)
          fired (atom 0)
          d (r/derive [a] (fn [v] (swap! runs inc) (* 10 v)))]
      (is (= 10 @d) "first deref derives lazily")
      (r/watch-ref d :t (fn [& _] (swap! fired inc)))
      (is (= 1 @runs))
      (swap! a inc)
      (is (= 20 @d) "dep change re-derives")
      (is (= 2 @runs))
      (is (= 1 @fired) "one notification for the real output change")
      (reset! a 2)
      (is (= 2 @runs) "equal-value reset does not re-run")
      (is (= 1 @fired) "equal-value reset adds no notification")))
  (testing "extra deps read through tracked-deref join the set"
    (let [a (atom 1)
          b (atom 100)
          d (r/derive [a] (fn [_] (+ @a (r/tracked-deref b))))]
      (is (= 101 @d))
      (swap! b inc)
      (is (= 102 @d) "discovered dep re-derives"))))

(deftest test-cursor
  (testing "cursor tracks its source path"
    (let [src (atom {:a {:b 1}})
          cur (r/cursor src [:a :b])]
      (is (= 1 @cur))
      (swap! src assoc-in [:a :b] 5)
      (is (= 5 @cur))
      (is (= 5 @(r/cursor src :a :b)) "varargs keys normalize"))))

(deftest test-writable-cursor
  (testing "reads the slice; cursor-reset!/cursor-swap! write back through the source"
    (let [src (atom {:http {:transport :curl :timeout 5}})
          cur (r/writable-cursor src [:http :transport])]
      (is (= :curl @cur) "reads are (get-in @source path)")
      (is (r/writable-cursor? cur))
      (is (= :babashka (r/cursor-reset! cur :babashka)))
      (is (= {:http {:transport :babashka :timeout 5}} @src) "assoc-in write-back")
      (is (= "babashka" (r/cursor-swap! cur name)))
      (is (= {:http {:transport "babashka" :timeout 5}} @src))
      (is (= "babashka" @cur) "the cursor sees its own write")))
  (testing "varargs keys, the whole-value path [], and nested lenses"
    (let [src (atom {:a {:b 1}})
          cur (r/writable-cursor src :a :b)]
      (is (= 1 @cur) "bare-key path form")
      (r/cursor-reset! cur 2)
      (is (= {:a {:b 2}} @src)))
    (let [src (atom {:whole 5})
          whole (r/writable-cursor src [])]
      (is (= {:whole 5} @whole))
      (r/cursor-reset! whole {:x 1})
      (is (= {:x 1} @src) "[] replaces the value — no nil key from assoc-in"))
    (let [src (atom {:a {:b 1}})
          outer (r/writable-cursor src [:a])
          inner (r/writable-cursor outer [:b])]
      (is (= 1 @inner))
      (r/cursor-reset! inner 9)
      (is (= {:a {:b 9}} @src) "a writable cursor as source nests the write")))
  (testing "writes are =-gated: an equal value touches neither source nor watchers"
    (let [src (atom {:n 1})
          cur (r/writable-cursor src [:n])
          fired (atom 0)]
      @cur
      (r/watch-ref cur :w (fn [& _] (swap! fired inc)))
      (r/cursor-reset! cur 1)
      (r/flush!)
      (is (zero? @fired) "equal write is silent")
      (is (identical? (:n @src) 1))
      (r/cursor-reset! cur 2)
      (r/flush!)
      (is (= 1 @fired) "a real write notifies once")))
  (testing "a write is an ordinary source change for readers"
    (let [src (atom {:n 1})
          cur (r/writable-cursor src [:n])
          runs (atom 0)
          rx (r/make-reaction (fn [] (swap! runs inc) (r/tracked-deref cur)))]
      (is (= 1 @rx))
      (is (= 2 (r/cursor-swap! cur inc)))
      (is (= 1 @runs) "queued, not synchronous")
      (r/flush!)
      (is (= 2 @rx))
      (is (= 2 @runs))))
  (testing "read-only cursors and plain atoms refuse to be written"
    (let [src (atom {:n 1})]
      (is (not (r/writable-cursor? (r/cursor src [:n]))))
      (is (not (r/writable-cursor? src)))
      (is (thrown-with-msg? Exception #"not a writable cursor"
                            (r/cursor-reset! (r/cursor src [:n]) 2)))
      (is (thrown-with-msg? Exception #"not a writable cursor"
                            (r/cursor-swap! src inc)))
      (is (= {:n 1} @src) "refused writes never touched the source")))
  (testing "a disposed cursor is inert — no zombie write-back"
    (let [src (atom {:n 1})
          cur (r/writable-cursor src [:n])]
      (is (= 1 @cur))
      (r/dispose! cur)
      (is (nil? @cur) "derefs answer nil")
      (is (nil? (r/cursor-reset! cur 9)) "the write is refused, not landed")
      (is (nil? (r/cursor-swap! cur inc)))
      (is (= {:n 1} @src) "the source was never touched")))
  (testing "a write through a disposed nested lens is refused, not silently dropped"
    (let [src (atom {:a {:b 1}})
          outer (r/writable-cursor src [:a])
          inner (r/writable-cursor outer [:b])]
      (is (= 1 @inner))
      (r/cursor-reset! inner 2)
      (is (= {:a {:b 2}} @src) "nested lens writes compose")
      (r/dispose! outer)
      (is (nil? (r/cursor-reset! inner 9))
          "the refusal propagates up the lens chain")
      (is (= {:a {:b 2}} @src) "nothing was written through the dead lens")))
  (testing "a reaction source is rejected at construction, loudly"
    (let [src (atom {:n 1})
          ro (r/cursor src [:n])]
      (is (thrown-with-msg? Exception #"source must be a plain atom"
                            (r/writable-cursor ro [:deeper])))
      (is (thrown-with-msg? Exception #"source must be a plain atom"
                            (r/writable-cursor (r/make-reaction (fn [] 1)) []))))))

(deftest test-manual-track-lazy-and-disposed
  (testing "manual track caches and disposes with its last watcher"
    (let [a (atom 1)
          runs (atom 0)
          v0 @a
          tr (r/make-reaction (fn [] (swap! runs inc) v0)
                              {:auto-run? false})]
      (is (zero? @runs) "no eager run")
      (is (= 1 @tr))
      (is (= 1 @runs))
      (is (= 1 @tr) "cached hand-back")
      (is (= 1 @runs))
      (r/dispose! tr)
      (is (nil? @tr) "disposed refs deref nil"))))

(deftest test-flush-and-queued-count
  (testing "batch queue drains and settles chained dirt"
    (let [a (atom 1)
          b (r/make-reaction (fn [] (inc (r/tracked-deref a))))]
      (is (= 2 @b))
      (is (zero? (r/queued-count)) "clean deref leaves nothing queued")
      (swap! a inc)
      (is (pos? (r/queued-count)) "dirty dep enqueues downstream")
      (r/flush!)
      (is (zero? (r/queued-count)))
      (is (= 3 @b))))
  (testing "sticky failure: deref rethrows without re-running until change"
    (let [fail? (atom true)
          runs (atom 0)
          rx (r/make-reaction
              (fn [] (swap! runs inc)
                (when @fail? (throw (ex-info "boom" {})))
                :ok))]
      (is (thrown? Exception @rx))
      (is (thrown? Exception @rx) "second deref rethrows captured error")
      (is (= 1 @runs) "body did not re-execute")
      (reset! fail? false)
      (r/force-run! rx)
      (is (= :ok @rx) "run! retries through sticky failure"))))

(deftest test-enqueue-hook-fires-per-new-invalidation
  ;; an event-driven host (kmet.tui.core) wakes its parked loop from this:
  ;; a derived ref dirtied with no frame requested yet must not wait for a
  ;; polling cadence to re-derive
  (let [a (atom 1)
        wakes (atom 0)
        d (r/derive [a] identity)]
    @d
    (r/set-enqueue-hook! #(swap! wakes inc))
    (try
      (swap! a inc)
      (is (= 1 @wakes) "a newly queued reaction wakes the host once")
      (swap! a inc)
      (is (= 1 @wakes) "a reaction already queued needs no second wake")
      (r/flush!)
      (swap! a inc)
      (is (= 2 @wakes) "settled again, the next invalidation wakes")
      (finally
        (r/set-enqueue-hook! nil)
        (r/dispose! d)))))

(deftest test-enqueue-hook-failure-is-isolated
  ;; the hook runs inside a dep watch on the MUTATOR's thread: a throwing
  ;; hook must not reach the write that dirtied the dep
  (let [a (atom 1)
        d (r/derive [a] identity)]
    @d
    (r/set-enqueue-hook! (fn [] (throw (ex-info "hook boom" {}))))
    (try
      (swap! a inc)
      (is (= 2 @a) "the mutating write survives a throwing hook")
      (finally
        (r/set-enqueue-hook! nil)
        (r/dispose! d)))))

(deftest test-dep-written-mid-run-still-invalidates
  (testing "a dep written between its read and the run's watch registration is
            not lost: the run compares captured values against current ones
            and re-runs (kmet mutates app state from futures/timers/agent
            events, so the write-between-read-and-watch window is real)"
    (let [a (atom 0)
          runs (atom 0)
          writes (atom 0)
          r (r/make-reaction (fn []
                               (swap! runs inc)
                               (let [v (r/tracked-deref a)]
                                 ;; simulate a concurrent writer landing after
                                 ;; the read but before the watch was added
                                 (when (= 0 v)
                                   (swap! writes inc)
                                   (reset! a 1))
                                 v)))]
      (is (= 1 @r) "the reaction converges: the mid-run write is not cached over")
      (is (= 1 @r) "and stays current on later reads")
      (is (= 1 @writes) "the writer ran once — no re-run loop")
      (is (= :idle (:state (r/reaction-state r))))
      (r/dispose! r))))

(deftest test-mid-run-write-on-a-child-reaction-is-seen
  (testing "the same gap for a reaction dep: the child's captured value is
            compared, so a child re-run inside the parent's window is seen"
    (let [a (atom 0)
          child (r/make-reaction (fn [] (r/tracked-deref a)))
          out (atom nil)
          parent (r/make-reaction (fn []
                                    (let [v @child]
                                      (when (= 0 v)
                                        (reset! a 1)
                                        (r/force-run! child))
                                      (reset! out v)
                                      v)))]
      (is (= 1 @parent) "parent sees the child's mid-run change")
      (r/dispose! parent)
      (r/dispose! child))))
