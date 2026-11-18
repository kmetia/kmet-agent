(ns kmet.tui.test-reakt-integration
  "Integration tests: kmet.reakt.core exercised through
   the TUI harness (dsl.md Stage 1).
   No terminal, no sleeps: dependency discovery, watch set-diff, =-gated
   notification, queue draining, plain-atom interop, component-body capture
   (a Text render inside a reaction tracks text-set!), tracks, cursors,
   disposal, and the with-let store primitives."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.components.text :as text]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.hikap :as hicc]
            [kmet.hikap.component :as component :refer [with-let]]
            [kmet.reakt.core :as r]
            [kmet.test-utils :as tu]))

(defn- rd
  "Tracked deref for reaction bodies under test."
  [ref]
  (r/tracked-deref ref))

(defn- counting-rx
  "Reaction over REF reading through tracked-deref, counting body runs."
  [ref]
  (let [runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (rd ref)))]
    {:rx rx :runs runs :ref ref}))

;; ═══════════════════════════════════════════════════════════════════════════
;; Dependency discovery
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-dep-discovery-across-branches
  ;; Only the atoms actually read on the last pass are dependencies:
  ;; writes to dropped branches are silent after the branch changes.
  (let [a (atom 1)
        b (atom 10)
        switch (atom :a)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (case (rd switch)
                                :a (rd a)
                                :b (rd b))))]
    (t/is (= 1 (r/force-run! rx)))
    (t/is (= #{a switch} (set (:watching (r/reaction-state rx)))))
    (reset! b 99)
    (r/flush!)
    (t/is (= 1 @runs) "unread dep write must not re-run")
    (t/is (zero? (r/queued-count)))
    (reset! switch :b)
    (t/is (= 1 (r/queued-count)) "branch switch queues exactly once")
    (r/flush!)
    (t/is (= 99 @rx))
    (t/is (= 2 @runs))
    (t/is (= #{b switch} (set (:watching (r/reaction-state rx)))) "watch set-diff followed the branch")
    (reset! a 555)
    (r/flush!)
    (t/is (= 2 @runs) "write to dropped dep is silent")
    (t/is (zero? (r/queued-count)))))

(t/deftest test-equal-value-dep-writes-are-silent
  ;; = -gated notification end to end: identical? and structural-equal
  ;; dep writes produce no dirty flag, no queue entry, no re-run.
  (let [{:keys [rx runs ref]} (counting-rx (atom {:n 1}))]
    (r/force-run! rx)
    (swap! ref identity)
    (r/flush!)
    (t/is (= 1 @runs))
    (reset! ref {:n 1})
    (r/flush!)
    (t/is (= 1 @runs) "structural-equal fresh object stays silent")
    (t/is (zero? (r/queued-count)))))

(t/deftest test-output-watch-fires-only-on-change
  ;; Watchers of the reaction itself fire only when its OUTPUT changed by =.
  (let [a (atom {:n 1})
        notes (atom [])
        rx (r/make-reaction #(rd a))]
    (r/force-run! rx)
    (r/watch-ref rx :w (fn [_ _ o n] (swap! notes conj [o n])))
    (reset! a {:n 2})
    (r/flush!)
    (t/is (= [[{:n 1} {:n 2}]] @notes))
    (reset! a {:n 2})
    (r/flush!)
    (t/is (= [[{:n 1} {:n 2}]] @notes) "equal output → no notification")))

(t/deftest test-mid-run-invalidation-converges
  ;; A dep written while the body executes must not cache a stale result:
  ;; the pass re-runs against the fresh state. (First-ever runs can't see
  ;; mid-writes of deps whose watches aren't registered yet — prime once.)
  (let [x (atom :a)
        gate (atom false)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (let [v (rd x)]
                                (when @gate
                                  (reset! x :b))
                                v)))]
    (r/force-run! rx)                       ; prime: registers the watch on x
    (t/is (= 1 @runs))
    (reset! gate true)
    (reset! x :a2)                    ; dirty the reaction so the drain recomputes
    (r/flush!)                        ; convergence loop: pass 2 writes x mid-run,
    (t/is (= :b @rx) "converged value reflects the mid-run write")
    (t/is (= 3 @runs) "pass 3 converged against the fresh state")
    (t/is (= :b @rx))
    (t/is (= 3 @runs) "equal-value self-write stays silent; reads are cached")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Queue / scheduling
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-nonreactive-deref-settles-queue-and-answers-current
  ;; Reagent's non-reactive deref contract (ratom.cljs -deref): flush! the
  ;; queue FIRST, then answer — a fresh reaction runs synchronously on its
  ;; first read (Reagent births reactions dirty), and chained reactions
  ;; settled by that same flush are current before the read returns.
  ;; Dep-change re-runs still queue between frames; only READS force
  ;; settlement, so mutator-side coalescing is untouched.
  (let [src (atom 0)
        up-runs (atom 0)
        down-runs (atom 0)
        up (r/make-reaction (fn [] (swap! up-runs inc) (inc (rd src))))
        down (r/make-reaction (fn [] (swap! down-runs inc) (inc (rd up))))]
    (t/is (= 2 @down) "first deref runs the chain synchronously")
    (t/is (= 1 @up-runs))
    (t/is (= 1 @down-runs))
    (reset! src 10)
    (t/is (= 11 @up) "non-reactive deref settles queued work first")
    (t/is (= 12 @down) "...and the whole chain is current in the same read")
    (t/is (= 2 @up-runs) "each body ran exactly once more")
    (t/is (= 2 @down-runs))
    (t/is (zero? (r/queued-count)))
    (t/is (= 12 @down))
    (t/is (= 2 @down-runs) "clean re-read is a pure cache hit")))

(t/deftest test-chained-reactions-settle-within-one-flush
  ;; out ← mid ← src: one write fans out through both levels inside a single
  ;; flush!, each body running exactly once.
  (let [src (atom 1)
        mid-runs (atom 0)
        out-runs (atom 0)
        mid (r/make-reaction (fn [] (swap! mid-runs inc) (inc (rd src))))
        out (r/make-reaction (fn [] (swap! out-runs inc) (inc (rd mid))))]
    (r/force-run! mid)
    (r/force-run! out)
    (reset! src 10)
    (r/flush!)
    (t/is (= 11 @mid))
    (t/is (= 12 @out))
    (t/is (= 2 @mid-runs) "initial + one flush run")
    (t/is (= 2 @out-runs))))

(t/deftest test-fan-out-one-write-two-reactions
  ;; One dep, two subscribers: a single write re-runs each exactly once.
  (let [src (atom 0)
        a-runs (atom 0)
        b-runs (atom 0)
        ra (r/make-reaction (fn [] (swap! a-runs inc) (* 2 (rd src))))
        rb (r/make-reaction (fn [] (swap! b-runs inc) (+ 100 (rd src))))]
    (r/force-run! ra)
    (r/force-run! rb)
    (reset! src 1)
    (r/flush!)
    (t/is (= 2 @ra))
    (t/is (= 101 @rb))
    (t/is (= 2 @a-runs))
    (t/is (= 2 @b-runs))))

(t/deftest test-n-invalidations-collapse-to-one-run
  (let [{:keys [rx runs ref]} (counting-rx (atom 0))]
    (r/force-run! rx)
    (reset! ref 1)
    (reset! ref 2)
    (reset! ref 3)
    (t/is (= 1 (r/queued-count)) "membership dedupe")
    (r/flush!)
    (t/is (= 3 @rx))
    (t/is (= 2 @runs) "three writes between frames, one body run")))

(t/deftest test-dispose-while-queued-is-silent
  ;; Dirty + enqueued + disposed: the drain must skip it without error and
  ;; leave nothing stuck in the queue.
  (let [{:keys [rx runs ref]} (counting-rx (atom 1))]
    (r/force-run! rx)
    (reset! ref 2)                 ; dirty + queued
    (r/dispose! rx)
    (r/flush!)                     ; must not run the body, must not throw
    (t/is (zero? (r/queued-count)))
    (t/is (= 1 @runs))))

(t/deftest test-throwing-reaction-does-not-strand-siblings
  ;; A throwing entry surfaces its error AFTER the batch drained — its
  ;; siblings still ran. The thrower lands in :failed: sticky, NOT retried
  ;; by later flushes — recovery needs a new dep change or an explicit
  ;; force-run!.
  (let [flag (atom true)
        shared (atom 0)
        boom-runs (atom 0)
        boom (r/make-reaction (fn []
                                (swap! boom-runs inc)
                                (if @flag
                                  (throw (ex-info "boom" {}))
                                  (rd shared))))
        ok-runs (atom 0)
        ok (r/make-reaction (fn [] (swap! ok-runs inc) (inc (rd shared))))]
    ;; prime both with flag false so watches register
    (reset! flag false)
    (r/force-run! boom)
    (r/force-run! ok)
    (t/is (zero? @boom))
    (reset! flag true)
    (reset! shared 1)              ; queues both, boom first (registered first)
    (t/is (thrown? Exception (r/flush!)) "the error still surfaces")
    (t/is (= 2 @ok-runs) "sibling ran despite the thrower")
    (t/is (zero? (r/queued-count)) "queue fully drained")
    (t/is (= 2 @boom-runs) "thrower ran once, failed sticky")
    ;; Sticky: a later flush with no NEW change does not retry it...
    (r/flush!)
    (t/is (= 2 @boom-runs))
    ;; ...an explicit run! does:
    (reset! flag false)
    (t/is (= 1 (r/force-run! boom)))
    (t/is (= 3 @boom-runs))))

(t/deftest test-flush-is-reentrancy-safe
  ;; A reaction body calling flush! while the queue drains must not recurse
  ;; the drain (CAS guard); everything still settles exactly once.
  (let [a (atom 1)
        inner-runs (atom 0)
        inner (r/make-reaction (fn []
                                 (swap! inner-runs inc)
                                 (rd a)))
        _ (r/force-run! inner)
        trigger (r/make-reaction (fn []
                                   (rd a)
                                   (r/flush!) ; no-op: the outer drain holds the lock
                                   :done))]
    (r/force-run! trigger)
    (reset! a 2) ; queues inner + trigger
    (r/flush!)
    (t/is (= 2 @inner-runs) "initial run + one flush run — nested flush added none")
    (t/is (= 2 (r/force-run! inner)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Plain-atom interop (the pivot's core claim)
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-ratom-returns-a-plain-atom
  (let [ra (atom 5)]
    (t/is (instance? clojure.lang.Atom ra))
    (t/is (= 5 @ra))
    (swap! ra inc)
    (t/is (= 6 @ra))))

(t/deftest test-existing-setter-fns-work-on-tracked-inputs
  ;; The interchangeability story, post-pivot: deps ARE existing atoms, so
  ;; setter fns written against plain atoms drive reactions unchanged.
  (let [record {:text (atom "x")}
        set-text! #(reset! (:text %1) %2)
        runs (atom 0)
        rx (r/make-reaction (fn [] (swap! runs inc) (rd (:text record))))]
    (r/force-run! rx)
    (set-text! record "y")
    (r/flush!)
    (t/is (= "y" @rx))
    (t/is (= 2 @runs))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Component-body capture — the money test
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-component-render-body-is-reactive
  ;; A real Text component rendered inside a reaction: track! routes its
  ;; @reads through tracked-deref, which feeds *ratom-context*, so the
  ;; plain text-set! call marks the reaction dirty — zero migration.
  (let [c (text/make-text "a" 0 0)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (core/render c 20)))]
    (r/force-run! rx)
    (t/is (= 1 @runs))
    (text/text-set! c "b")
    (t/is (pos? (r/queued-count)) "component setter dirtied the enclosing reaction")
    (r/flush!)
    (t/is (= 2 @runs))
    (t/is (.contains (first (core/render c 20)) "b"))
    (text/text-set! c "b")
    (t/is (zero? (r/queued-count)) "equal-value setter stays silent")))

(t/deftest test-bare-deref-inside-body-is-untracked-but-correct
  ;; Documented coverage contract: a bare @plain-atom read is NOT captured
  ;; (no dirty, no re-run) — correct under the batched fallback, just not
  ;; narrow. Reads go through tracked-deref to be tracked.
  (let [tracked-ref (atom 1)
        bare-ref (atom 100)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              [(rd tracked-ref) @bare-ref]))]
    (r/force-run! rx)
    (reset! bare-ref 200)
    (r/flush!)
    (t/is (= 1 @runs) "bare read captured nothing")
    (reset! tracked-ref 2)
    (r/flush!)
    (t/is (= 2 @runs))
    (t/is (= [2 200] (r/force-run! rx)) "untracked read still yields the current value")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Tracks, cursors, nesting, disposal
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-track-is-lazy-and-caches-until-dirty
  ;; track runs lazily on first deref, caches between changes — and like
  ;; Reagent's manual reactions it JOINS THE QUEUE on dep change, so an
  ;; unread track still refreshes at the next flush.
  (let [src (atom 1)
        runs (atom 0)
        tr (r/make-reaction (fn [] (swap! runs inc) (inc (rd src)))
                            {:auto-run? false})]
    (t/is (zero? @runs) "creation runs nothing")
    (t/is (zero? (r/queued-count)))
    (t/is (= 2 @tr))
    (t/is (= 2 @tr) "clean cached deref does not re-run")
    (t/is (= 1 @runs))
    (reset! src 10)
    (t/is (= 1 @runs) "change alone: nothing yet")
    (t/is (= 1 (r/queued-count)) "manual tracks enqueue like Reagent's")
    (r/flush!)
    (t/is (= 11 @tr) "flush refreshed the unread track")
    (t/is (= 2 @runs))))

(t/deftest volatile-reads-are-skipped-not-fatal
  ;; Volatiles can't take watches: reading one through tracked-deref must
  ;; neither crash watch-registration nor become a dependency (read is
  ;; correct under the batched fallback; just not narrow).
  (let [v (volatile! 42)
        runs (atom 0)
        rx (r/make-reaction (fn [] (swap! runs inc) [(rd v) @v]))]
    (t/is (= [42 42] (r/force-run! rx)))
    (t/is (= [] (:watching (r/reaction-state rx))) "volatile captured nothing")
    (vreset! v 43)
    (t/is (zero? (r/queued-count)))
    (t/is (= [43 43] (r/force-run! rx)) "untracked read still yields current value")))

(t/deftest throwing-watcher-isolated-from-siblings-and-mutator
  ;; Watchers fire on the MUTATOR's thread (any background thread swapping
  ;; app state): a throwing watcher must neither block its siblings nor
  ;; blow up whoever wrote the dep.
  (let [a (atom 0)
        good (atom [])
        rx (r/make-reaction #(rd a))]
    (r/force-run! rx)
    (r/watch-ref rx :bad (fn [_ _ _ _] (throw (ex-info "watcher boom" {}))))
    (r/watch-ref rx :good (fn [_ _ _o n] (swap! good conj n)))
    ;; The failing watcher prints to stderr — suppress it (hosts without
    ;; per-var output capture, e.g. jolt, would leak it).
    (tu/with-captured-err
      (t/is (= 1 (reset! a 1))
            "mutator never sees the watcher failure")
      (r/flush!))
    (t/is (= [1] @good) "sibling watcher still fired")))

(t/deftest cursor-of-cursor-composes
  (let [state (atom {:a {:b {:c 7}}})
        outer (r/cursor state [:a])
        inner (r/cursor outer [:b :c])]
    (r/force-run! inner)
    (t/is (= 7 (r/force-run! inner)))
    (swap! state assoc-in [:a :b :c] 8)
    (r/flush!)
    (t/is (= 8 (r/force-run! inner)) "chained through both cursors"))
  (let [state (atom {:whole 5})
        cur (r/cursor state [])]
    (t/is (= {:whole 5} (r/force-run! cur)) "empty path = whole value")))

(t/deftest test-cursor-follows-source-and-composes
  (let [state (atom {:profile {:name "ada"}})
        name-cur (r/cursor state [:profile :name])
        shout (r/make-reaction (fn [] (str/upper-case (rd name-cur))))
        pre-cur (do (r/force-run! name-cur) (r/force-run! name-cur))
        pre-shout (do (r/force-run! shout) (r/force-run! shout))]
    (t/is (= "ada" pre-cur))
    (t/is (= "ADA" pre-shout))
    (reset! state {:profile {:name "grace"}})
    (r/flush!)
    (t/is (= "grace" (r/force-run! name-cur)) "cursor followed the source")
    (t/is (= "GRACE" (r/force-run! shout)) "cursor-as-dep propagated through the reaction"))
  (let [state (atom {:a {:b 7}})
        cur (r/cursor state :a)]
    (t/is (= {:b 7} (r/force-run! cur)) "bare-key path form")))

(t/deftest test-reset-in-reaction-converges
  ;; Ported from reagent's reset-in-reaction: a body writing back to its own
  ;; source atom must converge, not loop.
  (let [state (atom {})
        c1 (r/make-reaction #(get-in (rd state) [:data :a]))
        c2 (r/make-reaction #(get-in (rd state) [:data :b]))
        runs (atom 0)
        rxn (r/make-reaction (fn []
                               (swap! runs inc)
                               ;; (or cc 0): Reagent's original relies on JS
                               ;; coercing null to 0 — JVM needs the guard.
                               (let [cc1 (or (rd c1) 0)
                                     cc2 (or (rd c2) 0)]
                                 (swap! state assoc :derived (+ cc1 cc2))
                                 nil)))]
    (r/force-run! rxn)
    (t/is (= (:derived @state) 0))
    (swap! state assoc :data {:a 1 :b 2})
    (r/flush!)
    (t/is (= (:derived @state) 3))
    (swap! state assoc :data {:a 11 :b 22})
    (r/flush!)
    (t/is (= (:derived @state) 33))
    (r/dispose! rxn)
    (t/is (< @runs 10) "converged without runaway")))

(t/deftest test-exception-recover-indirect
  ;; Ported from reagent's exception-recover-indirect. Our contract differs
  ;; where the sync/queued models diverge: a FAILED upstream produces no new
  ;; output, so downstream reactions don't re-run until its output actually
  ;; changes again — failure does not propagate as a value.
  (let [state (atom 1)
        ref-runs (atom 0)
        ref (r/make-reaction (fn []
                               (swap! ref-runs inc)
                               (when (= (rd state) 2)
                                 (throw (ex-info "err" {})))))
        runner-runs (atom 0)
        rnr (r/make-reaction (fn []
                               (swap! runner-runs inc)
                               (rd ref)))]
    (r/force-run! rnr)
    (t/is (= 1 @runner-runs))
    (swap! state inc)                    ; → 2: upstream will throw
    (t/is (thrown? Exception (r/flush!)))
    (t/is (= 2 @ref-runs) "upstream ran and failed")
    (t/is (= :failed (:state (r/reaction-state ref))))
    (t/is (thrown? Exception @ref) "sticky on deref")
    (t/is (= 1 @runner-runs) "no output change → downstream untouched")
    (swap! state inc)                    ; → 3: recovery
    (r/flush!)
    (t/is (= 3 @ref-runs))
    (t/is (nil? (rd ref)))
    ;; ref's output went nil → nil: still no CHANGE for the runner
    (t/is (= 1 @runner-runs))
    (t/is (nil? @rnr))))

(t/deftest test-nested-reactions-self-record
  ;; Derefing a reaction inside another body records it as a dependency via
  ;; its own deref implementation.
  (let [src (atom 1)
        inner (r/make-reaction #(rd src))
        outer-runs (atom 0)
        outer (r/make-reaction (fn [] (swap! outer-runs inc) (* 2 (rd inner))))]
    (r/force-run! inner)
    (r/force-run! outer)
    (reset! src 21)
    (r/flush!)
    (t/is (= 42 @outer))
    (t/is (= 2 @outer-runs))))

(t/deftest test-dispose-stops-tracking-and-idempotent
  (let [{:keys [rx runs ref]} (counting-rx (atom 1))]
    (r/force-run! rx)
    (r/dispose! rx)
    (r/dispose! rx)
    (reset! ref 2)
    (r/flush!)
    (t/is (zero? (r/queued-count)))
    (t/is (= 1 @runs))
    (t/is (nil? @rx) "disposed deref returns nil")))

(t/deftest test-dispose-during-run-stays-dead
  ;; A dep watcher disposing the reaction mid-run (watch-fire order can put
  ;; it before the reaction's own handler; a background thread can land it
  ;; inside the body): no resurrection, no watch re-registration, no value
  ;; write, no further body execution, derefs stay nil.
  (let [x (atom 1)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (* 10 (rd x))))]
    (r/force-run! rx)
    (add-watch x :killer (fn [_ _ _ _] (r/dispose! rx)))
    (try
      (reset! x 2)                   ; killer fires, then rx's own handler
      (finally (remove-watch x :killer)))
    (t/is (= :disposed (:state (r/reaction-state rx))) "handler must not resurrect")
    (t/is (= 1 @runs))
    (t/is (zero? (r/queued-count)))
    (t/is (nil? (r/force-run! rx)) "force-run on disposed is a no-op")
    (t/is (= 1 @runs) "still no body execution")
    ;; A later write must not re-enqueue or re-run anything.
    (reset! x 3)
    (t/is (zero? (r/queued-count)))
    (t/is (nil? @rx))
    (t/is (= 1 @runs))))

(t/deftest test-in-context-flag
  (t/is (false? (r/in-reaction?)))
  (let [seen (atom nil)
        rx (r/make-reaction (fn [] (reset! seen (r/in-reaction?)) :ok))]
    (r/force-run! rx)
    (t/is (true? @seen))
    (t/is (false? (r/in-reaction?)) "context unwound after the run")))

(t/deftest test-body-exception-propagates-and-reaction-stays-retryable
  (let [fail? (atom true)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (if @fail? (throw (ex-info "boom" {})) :ok)))]
    (t/is (thrown? Exception (r/force-run! rx)))
    (t/is (thrown? Exception (r/force-run! rx)) "explicit run! retries")
    (reset! fail? false)
    (t/is (= :ok (r/force-run! rx)))
    (t/is (= 3 @runs))))

(t/deftest test-caught-error-sticky-until-change
  ;; Reagent's caught: a failure captured during a queued run is rethrown by
  ;; every deref WITHOUT re-executing the body; the next dep change clears
  ;; it. A read must never re-run side effects.
  (let [src (atom :good)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (if (= :bad (rd src))
                                (throw (ex-info "bad state" {}))
                                (str "ok:" (rd src)))))]
    (r/force-run! rx)
    (reset! src :bad)
    (t/is (thrown? Exception (r/flush!)) "the error surfaces from the drain")
    (let [after-fail @runs]          ; the failing run itself counts
      (t/is (thrown? Exception @rx) "deref rethrows")
      (t/is (thrown? Exception @rx) "and again")
      (t/is (= after-fail @runs) "body never re-ran for a mere read")
      (reset! src :good)
      (r/flush!)
      (t/is (= "ok::good" @rx) "keyword prints its colon")
      (t/is (= (+ after-fail 1) @runs) "recovered on the next change"))))

(t/deftest test-auto-run-callback-scheduling
  ;; :auto-run? fn = ComponentFn's future hook: dep changes invoke the
  ;; callback with the reaction instead of queueing — the callback owns
  ;; scheduling (invalidate + request frame).
  (let [src (atom 1)
        fired (atom [])
        body-runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! body-runs inc)
                              (rd src))
                            {:auto-run? (fn [reaction]
                                          (swap! fired conj reaction))})]
    (r/force-run! rx)
    (reset! src 2)
    (t/is (= 1 (count @fired)) "callback invoked once per change")
    (t/is (identical? rx (first @fired)) "with the reaction itself")
    (t/is (zero? (r/queued-count)) "nothing queued — callback owns scheduling")
    (t/is (= 1 @body-runs) "engine did not re-run the body")
    (t/is (= 2 (r/force-run! rx)) "explicit run! still brings it current")
    (t/is (= 2 @body-runs))))

(t/deftest test-on-dispose-and-branch-auto-dispose
  ;; Ported from reagent's test-dispose/test-add-dispose: on-dispose
  ;; callbacks fire when a reaction dies — including the branch-switch case,
  ;; where dropping the last reader auto-disposes an intermediate manual
  ;; reaction.
  (let [a (atom 0)
        b-disposed (atom false)
        c-disposed (atom false)
        b-runs (atom 0)
        b (r/make-reaction (fn []
                             (swap! b-runs inc)
                             (inc (rd a)))
                           {:auto-run? false
                            :on-dispose (fn [_] (reset! b-disposed true))})
        c (r/make-reaction (fn []
                             (if (< (rd a) 1)
                               (rd b)
                               (dec (rd a))))
                           {:auto-run? false
                            :on-dispose (fn [_] (reset! c-disposed true))})
        res (atom nil)
        cns (r/make-reaction (fn [] (reset! res (rd c))))]
    (r/force-run! cns)
    (t/is (= @res 1))
    (t/is (= 1 @b-runs))
    (reset! a -1)
    (r/flush!)
    (t/is (= @res 0))
    (t/is (= false @b-disposed) "b still read on this branch")
    (reset! a 2)
    (r/flush!)
    (t/is (= @res 1))
    (t/is (= true @b-disposed) "branch dropped b → last watcher gone → auto-disposed")
    (t/is (= false @c-disposed) "c still alive")
    (r/dispose! cns)
    (t/is (= true @c-disposed)))
  ;; add-on-dispose!: registered callbacks fire with the reaction, after it
  ;; is inert.
  (let [a (atom 0)
        seen (atom [])
        rx (r/make-reaction #(rd a))]
    (r/force-run! rx)
    (r/add-on-dispose! rx (fn [reaction] (swap! seen conj [:first reaction])))
    (r/add-on-dispose! rx (fn [_] (swap! seen conj :second)))
    (r/dispose! rx)
    ;; NOTE: structural = over vectors containing reactions is unreliable in
    ;; bb (sealed hashCode on SCI classes) — assert by count and identity.
    (t/is (= 2 (count @seen)))
    (t/is (identical? rx (second (first @seen))))
    (t/is (= :first (first (first @seen))))
    (t/is (= :second (last @seen)))
    (t/is (= :disposed (:state (r/reaction-state rx))))
    (t/is (nil? @rx))))

(t/deftest test-many-watchers-survive-hash-transition
  ;; Transcript-scale guard: an atom's internal watch map upgrades
  ;; array-map→hash-map past 8 entries, and hashing our reify'd reactions is
  ;; sealed in bb. Watch keys therefore go through RxKey records — 20
  ;; reactions on ONE atom must all fire, exactly once each.
  (let [src (atom 0)
        runs (vec (repeatedly 20 #(atom 0)))
        rxs (mapv (fn [counter]
                    (r/make-reaction (fn []
                                       (swap! counter inc)
                                       (rd src))))
                  runs)]
    (doseq [rx rxs] (r/force-run! rx))
    (reset! src 1)
    (r/flush!)
    (doseq [i (range 20)]
      (t/is (= 2 @(nth runs i)) (str "prime + one flush run, watcher #" i)))
    (doseq [rx rxs]
      (t/is (= 1 (rd rx))))))

(t/deftest test-watch-ref-handles-both-kinds
  (let [a (atom 0)
        notes (atom [])
        rx (r/make-reaction #(rd a))]
    (r/force-run! rx)
    (r/watch-ref a :plain (fn [_ _ o n] (swap! notes conj [:atom o n])))
    (r/watch-ref rx :rx (fn [_ _ o n] (swap! notes conj [:reaction o n])))
    (reset! a 1)
    (r/flush!)
    (t/is (= [[:atom 0 1] [:reaction 0 1]] @notes))
    (r/unwatch-ref a :plain)
    (r/unwatch-ref rx :rx)
    (reset! a 2)
    (r/flush!)
    (t/is (= [[:atom 0 1] [:reaction 0 1]] @notes) "watches removed")))

;; ═══════════════════════════════════════════════════════════════════════════
;; with-let store primitives (macros.clj)
;; ═══════════════════════════════════════════════════════════════════════════

(t/deftest test-store-fetch-initializes-once-per-site
  (let [store (component/new-store)
        inits (atom 0)
        init #(do (swap! inits inc) :value)
        site 'site-1]
    ;; each pass may read the site; the init runs only on the first
    (dotimes [_ 3]
      (component/begin-pass! store)
      (component/with-store store
        (t/is (= :value (component/fetch-local site init)))
        ;; a different site gets its own slot within the same pass
        (t/is (= :other (component/fetch-local 'site-2 (constantly :other))))))
    (t/is (= 1 @inits) "body re-runs per pass, init does not")))

(t/deftest same-site-twice-in-one-pass-throws
  ;; reagent's generation warning (ratom.clj with-let), loud per the v1
  ;; contract: two instances of one expansion sharing a state slot is
  ;; always a bug — give each instance its own element/component
  (let [store (component/new-store)]
    (component/begin-pass! store)
    (component/with-store store
      (t/is (= :v (component/fetch-local 'dup-site (constantly :v))))
      (t/is (thrown-with-msg?
             Exception #"more than once in the same render pass"
             (component/fetch-local 'dup-site (constantly :v)))))
    ;; the next pass frees the site again
    (component/begin-pass! store)
    (component/with-store store
      (t/is (= :v (component/fetch-local 'dup-site (constantly :v)))))))

(t/deftest throwing-init-retries-next-pass-and-holds-body-out
  ;; reagent issue 525: a binding whose init throws must retry on the next
  ;; pass, and later bindings/the body must not run until it succeeds
  (let [store (component/new-store)
        attempts (atom 0)
        body-runs (atom 0)
        run-pass #(component/with-store store
                    (try
                      (component/begin-pass! store)
                      (with-let [x (let [n (swap! attempts inc)]
                                     (when (= 1 n)
                                       (throw (ex-info "boom" {})))
                                     n)
                                 y (+ x 10)]
                        (swap! body-runs inc)
                        y)
                      (catch Exception _ ::threw)))]
    (t/is (= ::threw (run-pass)) "first pass: init throws")
    (t/is (zero? @body-runs) "body never ran")
    (t/is (= 12 (run-pass)) "second pass: init retried, body runs")
    (t/is (= 2 @attempts) "init retried exactly once")
    (t/is (= 1 @body-runs))
    (t/is (= 12 (run-pass)) "third pass: cached value, no re-init")
    (t/is (= 2 @attempts))))

(t/deftest with-let-initializes-once-and-cleans-up-on-destroy
  (let [store (component/new-store)
        inits (atom 0)
        cleanups (atom 0)
        render (fn []
                 (component/begin-pass! store)
                 (component/with-store store
                   (with-let [conn (do (swap! inits inc) ::conn)]
                     (identity conn)
                     (finally (swap! cleanups inc)))))]
    (render) (render) (render)
    (t/is (= 1 @inits) "three passes, one init")
    (t/is (zero? @cleanups) "cleanup fires at destroy, not per pass")
    (component/destroy-store! store)
    (t/is (= 1 @cleanups))))

(t/deftest with-let-sibling-bindings-of-same-name-cannot-collide
  ;; keys are per-expansion-site gensyms — two with-lets binding x get
  ;; separate slots by construction
  (let [store (component/new-store)]
    (component/begin-pass! store)
    (t/is (= [:outer-x :inner-x]
             (component/with-store store
               [(with-let [x :outer-x] x)
                (with-let [x :inner-x] x)])))))

(t/deftest with-let-nested-cleanups-run-lifo
  (let [store (component/new-store)
        log (atom [])]
    (component/begin-pass! store)
    (component/with-store store
      (with-let [a (swap! log conj :init-a)]
        (identity a)
        (finally (swap! log conj :dispose-a)))
      (with-let [b (swap! log conj :init-b)]
        (identity b)
        (finally (swap! log conj :dispose-b))))
    (component/destroy-store! store)
    (t/is (= [:init-a :init-b :dispose-b :dispose-a] @log))))

(t/deftest test-stores-are-isolated
  (let [s1 (component/new-store)
        s2 (component/new-store)]
    (component/with-store s1
      (component/fetch-local 'k (constantly :one)))
    (component/with-store s2
      (t/is (= :two (component/fetch-local 'k (constantly :two)))))))

(t/deftest test-cleanup-registers-once-runs-lifo
  (let [store (component/new-store)
        log (atom [])]
    (component/with-store store
      (dotimes [_ 50] ; body reruns must not grow the cleanup list
        (component/register-cleanup! 'conn #(swap! log conj :close-conn)))
      (component/register-cleanup! 'timer #(swap! log conj :stop-timer)))
    (t/is (empty? @log) "cleanups fire only at destroy")
    (component/destroy-store! store)
    (t/is (= [:stop-timer :close-conn] @log) "LIFO, registered once despite 50 passes")
    (component/destroy-store! store)
    (t/is (= [:stop-timer :close-conn] @log) "destroy is idempotent")))

(t/deftest test-store-outside-binding-throws
  (t/is (thrown? Exception (component/fetch-local 'k (constantly 1))))
  (t/is (thrown? Exception (component/register-cleanup! 'k (fn [])))))

(t/deftest test-destroy-store-survives-throwing-cleanup
  (let [store (component/new-store)
        log (atom [])]
    (component/with-store store
      (component/register-cleanup! 'good #(swap! log conj :good))
      (component/register-cleanup! 'bad #(throw (ex-info "cleanup boom" {}))))
    ;; The throwing cleanup prints to stderr — suppress it (hosts without
    ;; per-var output capture, e.g. jolt, would leak it).
    (tu/with-captured-err
      (component/destroy-store! store))
    (t/is (= [:good] @log) "throwing cleanup doesn't block the rest")))

(t/deftest invalidate-forces-next-deref-past-equal-deps
  (let [a (atom 1)
        runs (atom 0)
        rx (r/make-reaction (fn []
                              (swap! runs inc)
                              (str (rd a))))]
    (r/force-run! rx)
    (t/is (= "1" @rx))
    (t/is (= 1 @runs) "idle deref hands back the cached value")
    (r/invalidate! rx)
    (t/is (= "1" @rx))
    (t/is (= 2 @runs) "invalidate! forced exactly one recompute despite unchanged deps")
    (t/is (= "1" @rx))
    (t/is (= 2 @runs) "re-cached after the forced run")
    (r/dispose! rx)
    (t/is (nil? (r/invalidate! rx)) "disposed refs ignore invalidation")
    (t/is (= 2 @runs) "and never re-run afterwards")))

(t/deftest test-dispose-tree
  (t/testing "dispose-tree! unwinds with-let cleanups of compiled trees and
            leaves foreign records alone"
    (let [cleanups (atom 0)
          cleanup-fn (fn [_props]
                       (with-let [_ (swap! cleanups inc)]
                         [:text {:padding-x 0 :padding-y 0} "x"]
                         (finally (swap! cleanups dec))))
          comp (hicc/compile-tree
                [:container {}
                 [cleanup-fn {}]
                 (text/make-text "foreign" 0 0)])]
      ;; render once so the with-let store initializes (+1)
      (protocols/render comp 40)
      (t/is (= 1 @cleanups))
      (#'hicc/dispose-tree! comp)
      (t/is (= 0 @cleanups) "finally ran — ownership unwound")
      (#'hicc/dispose-tree! comp)
      (t/is (= 0 @cleanups) "second pass inert — nothing left to unwind"))))