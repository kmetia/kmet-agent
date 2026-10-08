(ns kmet.tui.test-compute
  "Headless tests for hiccup/compute (dsl.md §3.1, Stage 5): derived refs —
   sugar over reactions with the dep list seeding tracking — with equal-value
   no-op notification, auto-disposal for per-instance computes, record
   components subscribing through the track!/RXRef bridge, and the
   scheduler-gate interaction (an upstream change that alters a subscribed
   component's input invalidates it and requests exactly one frame)."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as h]
            [kmet.tui.macros :as macros :refer [with-let defcomponent track!]]
            [kmet.tui.protocols :as protocols]
            [kmet.libs.reakt :as rag]))

(defcomponent Probe nil [cache-atom render-count-atom body]
  (render [this width]
    (track! this width
      (swap! (:render-count-atom this) inc)
      ((:body this)))))

(t/deftest compute-derived-ref
  (let [a (atom 1)
        b (atom 10)
        c (h/compute [a b] +)]
    ;; lazy: nothing ran until read; first deref brings it current
    (t/is (= 11 @c) "first deref computes")
    (reset! a 2)
    (t/is (= 12 @c) "dep change recomputes (deref settles the queue)")
    (reset! b 20)
    (t/is (= 22 @c))))

(t/deftest compute-equal-value-write-is-noop
  ;; the payoff: recomputing to the SAME value notifies nobody — fine-grained
  ;; invalidation for free (component caches stay valid, no frame requested)
  (let [a (atom 1)
        c (h/compute [a] (fn [x] (mod x 3)))
        _ @c
        fired (atom 0)]
    (rag/watch-ref c :w (fn [_ _ _ _] (swap! fired inc)))
    (reset! a 4)
    (t/is (= 1 @c))
    (t/is (zero? @fired) "dep changed but derived value equal — no notify")
    (reset! a 5)
    (t/is (= 2 @c))
    (t/is (= 1 @fired) "real output change notified")))

(t/deftest compute-instances-dont-collide
  ;; each compute instance owns its dependency watches — two computes over
  ;; the same dep don't interfere
  (let [a (atom 1)
        c1 (h/compute [a] (fn [x] (* x 2)))
        c2 (h/compute [a] (fn [x] (* x 3)))]
    (reset! a 5)
    (t/is (= 10 @c1))
    (t/is (= 15 @c2))))

(t/deftest compute-discovers-unlisted-tracked-reads
  ;; explicit deps seed tracking; reads F makes through tracked channels join
  ;; automatically (auto-discovery is the default under compute)
  (let [a (atom 1)
        hidden (atom 100)
        c (h/compute [a]
                     (fn [x]
                       (+ x
                          (rag/tracked-deref hidden))))]
    (t/is (= 101 @c))
    (reset! hidden 200)
    (t/is (= 201 @c) "unlisted but tracked read re-derives")))

(t/deftest compute-under-component-unwinds-on-dispose
  ;; per-instance computes register their disposal with the component store —
  ;; dispose tears the reaction down (no zombie dep watches)
  (let [a (atom 1)
        log (atom [])
        r (h/root (fn [_]
                    (with-let [c (h/compute [a] inc)]
                      [:text {:padding-x 0 :padding-y 0} (str "v" @c)]
                      (finally (swap! log conj :disposed)))))]
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "v2"))
    (t/is (= [] @log) "no dispose yet")
    (protocols/dispose r)
    (t/is (= [:disposed] @log) "store cleanup ran on dispose")
    ;; the compute's dep watch is gone: writing the dep must not throw, and
    ;; the disposed compute is simply inert (nobody reads it anymore)
    (reset! a 10)))

(t/deftest compute-in-reaction-body-tracks-deps
  ;; computing inside a reaction body records the SOURCE deps as reaction
  ;; deps — an upstream change re-runs the body, which re-reads the compute
  ;; and recomputes. This is the §3.1 component pattern.
  (let [a (atom 1)
        calls (atom 0)
        r (h/root (fn [_]
                    (swap! calls inc)
                    (with-let [c (h/compute [a] (fn [x] (* x 2)))]
                      [:text {:padding-x 0 :padding-y 0} (str "v" @c)])))]
    (core/render r 20)
    (core/render r 20)
    (t/is (= 1 @calls) "idle: body memoized")
    (reset! a 3)
    (core/render r 20)
    (t/is (= 2 @calls) "source change re-ran the body")
    (t/is (str/includes? (str/join "\n" (core/render r 20)) "v6")
          "recomputed value rendered")
    (core/render r 20)
    (t/is (= 2 @calls) "re-cached after the change")))

(t/deftest record-component-subscribes-to-compute
  ;; Stage 5 bridge: a RECORD component's track! body derefs a compute —
  ;; the reactive ref joins the tracked set, so an upstream change
  ;; invalidates the record's cache without any manual invalidate call
  (let [a (atom 5)
        c (h/compute [a] (fn [x] (* x 2)))
        renders (atom 0)
        probe (map->Probe {:cache-atom (atom nil)
                           :render-count-atom renders
                           :body (fn [] [(str "v" @c)])})]
    (t/is (= ["v10"] (protocols/render probe 40)))
    (t/is (= ["v10"] (protocols/render probe 40)) "cache hit")
    (t/is (= 1 @renders))
    (reset! a 7)
    (t/is (= ["v14"] (protocols/render probe 40))
          "dep change flowed through the compute into the record cache")
    (t/is (= 2 @renders))
    (protocols/dispose probe)))

(t/deftest record-component-loses-compute-watch-on-branch-switch
  ;; branch switch: the next pass stops reading the compute — its watch must
  ;; be dropped (else it fires invalidate-cache forever on writes nobody
  ;; consumes), and writes to the dropped source change nothing
  (let [a (atom 1)
        b (atom 0)
        c (h/compute [a] (fn [x] (+ x 100)))
        renders (atom 0)
        probe (map->Probe {:cache-atom (atom nil)
                           :render-count-atom renders
                           :body (fn []
                                   (if (pos? (rag/tracked-deref b))
                                     [(str "v" @c)]
                                     ["off"]))})]
    (protocols/render probe 40)
    (reset! b 1)
    (protocols/render probe 40)
    (reset! b 0)
    (protocols/render probe 40)
    (t/is (= ["off"] (protocols/render probe 40)) "cache holds")
    (t/is (= 3 @renders) "hit-check passed without re-running")
    ;; a is no longer watched by the probe (branch switched away from c):
    ;; writing it must not invalidate anything
    (reset! a 50)
    (t/is (= 3 @renders) "write to a dropped dep changes nothing")
    (protocols/dispose probe)))

(t/deftest compute-change-invalidates-subscriber-and-schedules-frame
  ;; the scheduler gate (§3.4) end to end: a compute whose output feeds a
  ;; subscribed component recomputes at the flush, the subscriber's cache
  ;; invalidates, and invalidate-cache requests EXACTLY ONE frame through
  ;; the hook (installed here like tui.core does)
  (let [a (atom 1)
        c (h/compute [a] (fn [x] (* x 2)))
        fired (atom 0)
        r (h/root (fn [_]
                    [:text {:padding-x 0 :padding-y 0}
                     (str "c" (rag/tracked-deref c))]))]
    (core/render r 20)
    (macros/set-frame-hook! #(swap! fired inc))
    (try
      (reset! a 5)
      (t/is (zero? @fired) "queued model: nothing fired before the flush")
      (rag/flush!)
      ;; the compute recomputed 10 (≠ 2) → notified the subscriber → its
      ;; reaction invalidated + requested a frame
      (t/is (= 1 @fired) "change scheduled exactly one frame")
      (t/is (str/includes? (str/join "\n" (core/render r 20)) "c10")
            "new value rendered")
      (t/is (= 1 @fired) "rendering itself does not poke the hook")
      (finally
        (macros/set-frame-hook! nil)))))

(t/deftest compute-creation-counted
  ;; leak observability (dsl.md §3.1): every creation bumps :computes —
  ;; per-instance computes create once, bare-in-body computes climb per pass
  (h/reset-counters!)
  (let [a (atom 1)
        _ (h/compute [a] inc)
        _ (h/compute [a] (fn [x] (* x 2)))]
    (t/is (= 2 (:computes (h/counters))) "two creations counted")
    (h/reset-counters!)))

(t/deftest compute-dirty-at-dispose
  ;; swap the dep (compute enqueued) then dispose BEFORE any flush: dispose
  ;; must purge the queue so the flush neither resurrects nor re-runs it,
  ;; and the with-let cleanup still runs exactly once
  (let [a (atom 1)
        log (atom [])
        r (h/root (fn [_]
                    (with-let [c (h/compute [a] #(+ % 10))]
                      [:text {:padding-x 0 :padding-y 0} (str "v" @c)]
                      (finally (swap! log conj :cleanup)))))]
    (core/render r 20)
    (reset! a 99)
    (protocols/dispose r)
    (rag/flush!)
    (t/is (= [:cleanup] @log) "single cleanup, no resurrection")))

(t/deftest record-branch-switches-between-atom-and-compute
  ;; mixed tracked deps: a body alternating between a plain atom read and a
  ;; compute read must prune the dropped kind each switch — watches stay
  ;; exactly one-per-dep-per-kind per pass, no stale invalidation either way
  (let [a (atom 0)
        mode (atom :atom)
        c (h/compute [a] (fn [x] (str "compute-" x)))
        renders (atom 0)
        probe (map->Probe {:cache-atom (atom nil)
                           :render-count-atom renders
                           :body (fn []
                                   (if (= :compute (rag/tracked-deref mode))
                                     [(str @c)]
                                     [(str "raw-" (rag/tracked-deref a))]))})]
    (protocols/render probe 40)
    (t/is (= ["raw-0"] (protocols/render probe 40)) "atom branch cached")
    (reset! a 5)
    (t/is (= ["raw-5"] (protocols/render probe 40)) "atom watch fires")
    (reset! mode :compute)
    (t/is (= ["compute-5"] (protocols/render probe 40))
          "switch to compute branch picks up current value")
    (reset! a 6)
    (t/is (= ["compute-6"] (protocols/render probe 40))
          "compute flows through while watched")
    (reset! mode :atom)
    (t/is (= ["raw-6"] (protocols/render probe 40)) "back on atom branch")
    (protocols/dispose probe)))

(t/deftest compute-top-level-lives-forever
  ;; no enclosing store → the reaction keeps its dep watches (the shared
  ;; def'd compute pattern)
  (let [a (atom 1)
        c (h/compute [a] (fn [x] (* x 2)))]
    (t/is (= 2 @c))
    (reset! a 4)
    (t/is (= 8 @c) "still live after no dispose path")))

(t/deftest shared-cache-atom-throws-before-watches-land
  ;; two live components cannot share one cache atom: the second render
  ;; throws in weak/register! BEFORE it installs any watches, so the loser
  ;; leaves nothing behind that remove-track-watches! could not find
  (let [shared (atom nil)
        dep-a (atom 0)
        dep-b (atom 0)
        c1 (map->Probe {:cache-atom shared
                        :render-count-atom (atom 0)
                        :body (fn [] [(str (rag/tracked-deref dep-a))])})
        c2 (map->Probe {:cache-atom shared
                        :render-count-atom (atom 0)
                        :body (fn [] [(str (rag/tracked-deref dep-b))])})]
    (t/is (= ["0"] (protocols/render c1 40)))
    (let [key (:kmet.tui.macros/watch-key (meta shared))]
      (t/is (contains? (.getWatches dep-a) key))
      (t/is (thrown? clojure.lang.ExceptionInfo (protocols/render c2 41)))
      (t/is (contains? (.getWatches dep-a) key) "the live entry keeps its watch")
      (t/is (empty? (.getWatches dep-b)) "the throwing render installed nothing"))
    (protocols/dispose c1)))
