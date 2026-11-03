(ns kmet.tui.components.test-track
  "Tests for the reactive track! cache: setters need no manual invalidate,
   cache hits return the same object, and width changes bust the cache."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.hikap.component :as component]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.components.select-list :as sl]
            [kmet.tui.components.settings-list :as settings]
            [kmet.ui.tui.footer :as footer]
            [kmet.reakt.core :as reakt]
            [kmet.reakt.weak :as weak]
            [kmet.test-utils :as test-utils]))

(defn- watch-key-of
  "The track! watch key COMPONENT's cache allocated, or nil before its first
   render."
  [component]
  (:kmet.hikap.component/watch-key (meta (:cache component))))

(t/deftest test-set!-auto-invalidates
  ;; text-set! no longer calls invalidate — the watch must do it
  (let [c (text/make-text "a" 0 0)]
    (core/render c 5)
    (text/text-set! c "b")
    (let [lines (core/render c 5)]
      (t/is (.contains (first lines) "b")))))

(t/deftest test-cache-hit-returns-same-object
  (let [c (text/make-text "hi" 0 0)
        r1 (core/render c 10)
        r2 (core/render c 10)]
    (t/is (identical? r1 r2))))

(t/deftest test-width-change-busts-cache
  (let [c (text/make-text "hello world foo" 0 0)]
    (t/is (= 1 (count (core/render c 20))))
    (t/is (> (count (core/render c 6)) 1))))

(t/deftest test-multiple-tracked-atoms
  ;; changing any tracked atom (theme or text) must invalidate
  (let [c (text/make-text "a" 0 0)]
    (core/render c 5)
    (text/text-set! c "b")
    (t/is (.contains (first (core/render c 5)) "b"))
    (text/text-set! c "c")
    (t/is (.contains (first (core/render c 5)) "c"))))

(t/deftest test-markdown-set!-auto-invalidates
  (let [c (md/make-markdown "# hi")]
    (core/render c 20)
    (md/markdown-set-text! c "# bye")
    (t/is (some #(.contains % "bye") (core/render c 20)))))

(t/deftest test-markdown-append!-auto-invalidates
  (let [c (md/make-markdown "a")]
    (core/render c 20)
    (md/markdown-append! c "b")
    (let [out (clojure.string/join "\n" (core/render c 20))]
      (t/is (.contains out "a"))
      (t/is (.contains out "b")))))

(t/deftest test-manual-invalidate-still-works
  ;; protocol invalidate remains a working manual override
  (let [c (text/make-text "a" 0 0)]
    (core/render c 5)
    (core/invalidate c)
    (t/is (.contains (first (core/render c 5)) "a"))))

;; ─── select-list / settings-list navigation (latent staleness fixes) ────────

(t/deftest test-select-list-navigation-re-renders
  ;; selection change must invalidate the cache — the selected item shows
  ;; the accent-colored prefix after navigating (was stale without manual invalidate)
  (let [s (sl/make-select-list [{:label "apple"} {:label "banana"}])]
    (core/render s 20)
    (core/handle-input s "\u001b[B") ;; down
    (let [banana-line (nth (core/render s 20) 1)]
      (t/is (.contains banana-line "→ ")))))

(t/deftest test-settings-list-navigation-re-renders
  ;; regression: navigation never re-rendered (cache omitted selected-idx) —
  ;; the selected value highlight must move from Alpha to Beta
  (let [s (settings/make-settings-list [{:id :a :label "Alpha" :value "x"}
                                        {:id :b :label "Beta" :value "y"}])
        before (vec (core/render s 30))]
    (core/handle-input s "\u001b[B") ;; down
    (t/is (not= before (vec (core/render s 30))))))

(t/deftest test-settings-list-focus-shows-cursor
  ;; the selected item always shows the → cursor (pi: prefix = cursor when
  ;; selected, regardless of focus — extension dialogs wrap the list in a
  ;; duck-typed component that never receives focus)
  (let [s (settings/make-settings-list [{:id :a :label "Alpha" :value "x"}
                                        {:id :b :label "Beta" :value "y"}])]
    (t/is (.contains (first (core/render s 30)) "→"))))

(t/deftest test-a-lost-watch-leaves-the-component-stale
  ;; The cache trusts its invalidation edges; there is no value re-check on a
  ;; hit. A watch removed behind track!'s back is therefore NOT compensated:
  ;; the component serves its cached lines until something else invalidates
  ;; it. Accepted residual class (tui.md §3.2) — reachable only by
  ;; unwatching track!'s own key, which only teardown does. core/invalidate
  ;; is the way back.
  (let [c (text/make-text "a" 0 0)
        before (core/render c 5)]
    (remove-watch (:text-atom c) (watch-key-of c))
    (text/text-set! c "b") ;; no watch → no invalidation
    (t/is (identical? before (core/render c 5)) "the stale cache is served")
    (t/is (not (some #(str/includes? % "b") (core/render c 5))))
    (core/invalidate c)
    (t/is (some #(str/includes? % "b") (core/render c 5)) "explicit invalidation recovers")))

(t/deftest test-a-dropped-registry-entry-heals-through-the-hit-path
  ;; The hit path requires ownership (tracked?), so an entry lost to a stray
  ;; unregister! costs one re-render and re-registers — it must not freeze
  ;; the component (tui.md §3.2).
  (let [c (text/make-text "a" 0 0)]
    (core/render c 5)
    (t/is (component/tracked? c))
    (component/remove-track-watches! c)
    (t/is (not (component/tracked? c)))
    (t/is (some #(str/includes? % "a") (core/render c 5)) "served after a re-render")
    (t/is (component/tracked? c) "the miss re-registered the entry")
    (text/text-set! c "b")
    (t/is (some #(str/includes? % "b") (core/render c 5)) "and it invalidates again")
    (protocols/dispose c)))

;; ─── install-at-read (change 1) ────────────────

(t/deftest test-a-body-that-writes-a-tracked-ref-is-not-cached
  (t/testing "the watch is installed at the read, so a mid-body write fires
            and the pass is not cached at all"
    (let [a (atom 1)
          c {:cache (atom nil)}
          runs (atom 0)
          body (fn []
                 (let [v (reakt/tracked-deref a)]
                   (swap! runs inc)
                   (when (= 1 v) (reset! a 2))
                   v))]
      (t/is (= 1 (component/track-render c 10 body)) "the body returns what it read")
      (t/is (= 1 @runs))
      (t/is (nil? @(:cache c))
            "a write the old post-body install would have missed is observed")
      (t/is (= 2 (component/track-render c 10 (fn [] (swap! runs inc) (reakt/tracked-deref a))))
            "the next render re-reads the ref")
      (t/is (= 2 @runs))
      (t/is (= 2 (component/track-render c 10 (fn [] (throw (ex-info "hit must not run" {})))))
            "and the pass it wrote is cached")
      (component/remove-track-watches! c))))

(t/deftest test-a-throwing-body-leaves-no-watches-or-cache
  (t/testing "a throw after a read unwatches what it read — a stranded watch
            would fire forever and invalidate a later pass that never read it"
    (let [a (atom 1)
          b (atom 2)
          c {:cache (atom nil)}]
      (t/is (thrown? Exception
                     (component/track-render c 10 (fn [] (reakt/tracked-deref a)
                                                    (throw (ex-info "boom" {}))))))
      (t/is (nil? @(:cache c)) "no cache from a failed pass")
      (t/is (not (component/tracked? c)) "no registry entry")
      (t/is (= 2 (component/track-render c 10 (fn [] (reakt/tracked-deref b)))))
      (t/is (some? @(:cache c)))
      (reset! a 99)
      (t/is (some? @(:cache c)) "a ref only the failed pass read is not watched")
      (reset! b 3)
      (t/is (nil? @(:cache c)) "the ref the successful pass read is watched")
      (component/remove-track-watches! c)))
  (t/testing "the same when a previous pass's cache is present"
    (let [a (atom 1)
          c {:cache (atom nil)}]
      (component/track-render c 10 (fn [] (reakt/tracked-deref a)))
      (t/is (some? @(:cache c)))
      ;; width change forces the miss that then throws
      (t/is (thrown? Exception
                     (component/track-render c 11 (fn [] (reakt/tracked-deref a)
                                                    (throw (ex-info "boom" {}))))))
      (t/is (nil? @(:cache c)) "the rejected cache is not left for the next hit")
      (t/is (not (component/tracked? c)))
      (reset! a 2)
      (t/is (= 2 (component/track-render c 11 (fn [] (reakt/tracked-deref a))))
            "a retry re-reads and re-registers")
      (component/remove-track-watches! c))))

(t/deftest test-a-self-invalidating-body-is-not-cached
  (t/testing "a body that invalidates itself mid-run is not cached: its
            reset! destroys the pass's in-flight token, so the commit keeps nil"
    (let [c {:cache (atom nil)}
          runs (atom 0)]
      (t/is (= :v (component/track-render c 10
                                          (fn []
                                            (swap! runs inc)
                                            (component/invalidate-cache c)
                                            :v))))
      (t/is (= 1 @runs))
      (t/is (nil? @(:cache c)))
      (t/is (= :v (component/track-render c 10 (fn [] (swap! runs inc) :v))))
      (t/is (= 2 @runs) "the next render re-ran the body")
      (t/is (= :v (component/track-render c 10 (fn [] (throw (ex-info "hit must not run" {})))))
            "and the pass after that is cached")
      (component/remove-track-watches! c))))

(t/deftest test-a-pass-parks-a-non-cache-token
  (t/testing "the in-flight token, not nil: that is what lets the commit tell
            an invalidate apart from an empty cache (and only the atom does)"
    (let [c {:cache (atom nil)}
          during (atom ::unset)]
      (component/track-render c 10 (fn [] (reset! during @(:cache c)) :v))
      (t/is (map? @during) "the atom held a marker while the body ran")
      (t/is (not (contains? @during :atoms)) "and it is not a cache")
      (t/is (some? @(:cache c)) "the commit replaced it")
      (t/is (contains? @(:cache c) :atoms))
      (component/remove-track-watches! c))))

(t/deftest test-any-write-to-the-cache-atom-destroys-the-pass
  (t/testing "the refusal signal is the cache atom itself, not invalidate-cache:
            a body that nils it directly is not cached either"
    (let [c {:cache (atom nil)}]
      (t/is (= :v (component/track-render c 10 (fn [] (reset! (:cache c) nil) :v))))
      (t/is (nil? @(:cache c)))
      (component/remove-track-watches! c))))

(t/deftest test-repeated-failed-passes-accumulate-nothing
  (t/testing "two throws leave no cache, no entry and no extra watches, and a
            later pass is not invalidated by refs only the failed passes read"
    (let [a (atom 1)
          b (atom 2)
          c {:cache (atom nil)}
          before (component/live-watch-count)]
      (dotimes [_ 2]
        (t/is (thrown? Exception
                       (component/track-render c 10
                                               (fn []
                                                 (reakt/tracked-deref a)
                                                 (throw (ex-info "boom" {})))))))
      (t/is (nil? @(:cache c)) "no cache from a failed pass")
      (t/is (not (component/tracked? c)) "no registry entry")
      (t/is (<= (component/live-watch-count) before) "no entry accumulated")
      (component/track-render c 10 (fn [] (reakt/tracked-deref b)))
      (reset! a 42)
      (t/is (some? @(:cache c)) "a ref only the failed passes read is not watched")
      (reset! b 3)
      (t/is (nil? @(:cache c)) "the ref the successful pass read is watched")
      (component/remove-track-watches! c))))

(t/deftest test-a-ref-read-twice-is-watched-once
  (t/testing "the payload's :watched is a set — re-reads add no second entry"
    (let [a (atom 1)
          c {:cache (atom nil)}]
      (component/track-render c 10 (fn [] (+ (reakt/tracked-deref a)
                                             (reakt/tracked-deref a)
                                             (reakt/tracked-deref a))))
      (t/is (= #{a} (:watched (weak/payload (watch-key-of c)))))
      (component/remove-track-watches! c))))

(t/deftest test-branch-switch-unwatches-dropped-refs
  (t/testing "a ref only a previous branch read stops invalidating"
    (let [a (atom 1)
          b (atom 10)
          branch (atom :a)
          c {:cache (atom nil)}
          body (fn [] (if (= :a (reakt/tracked-deref branch))
                        (reakt/tracked-deref a)
                        (reakt/tracked-deref b)))]
      (t/is (= 1 (component/track-render c 10 body)))
      (reset! branch :b)                         ;; invalidates
      (t/is (= 10 (component/track-render c 10 body)))
      (t/is (some? @(:cache c)) "the second pass cached")
      (reset! a 2)                               ;; no longer read
      (t/is (some? @(:cache c)) "a dropped ref keeps its old watch away")
      (reset! b 11)                              ;; still read
      (t/is (nil? @(:cache c)) "a ref this branch reads still invalidates")
      (component/remove-track-watches! c))))

;; ─── weak registry ─────────────────────────────

(t/deftest test-live-watch-count-follows-render-and-dispose
  (t/testing "the track! registry is weak-registry backed: a live component
            counts, dispose unregisters, both idempotently"
    (let [pre (component/live-watch-count)
          c (text/make-text "a" 0 0)]
      (t/is (not (component/tracked? c)) "nothing is registered before the first render")
      (core/render c 5)
      (t/is (component/tracked? c))
      (protocols/dispose c)
      (t/is (not (component/tracked? c)) "the entry is gone")
      (t/is (<= (component/live-watch-count) pre))
      (t/is (nil? (protocols/dispose c)) "dispose again is a no-op"))))

(t/deftest test-dispose-before-any-render-is-a-no-op
  (t/testing "teardown must not mint a key for a component that never ran
            a track! body"
    (let [pre (component/live-watch-count)
          c (text/make-text "a" 0 0)]
      (t/is (not (component/tracked? c)))
      (t/is (nil? (protocols/dispose c)))
      (t/is (<= (component/live-watch-count) pre)))))

(defn- make-droppable-text
  "A rendered text component with only non-component references escaping
   (weak ref, watch key, its tracked atom) so it can be collected."
  []
  (let [c (text/make-text "drop" 0 0)]
    (core/render c 5)
    {:wref (java.lang.ref.WeakReference. c)
     :key (watch-key-of c)
     :text-atom (:text-atom c)}))

(defn- live-subject?
  "True while KEY's weak entry still holds its subject. Look the subject up
   inside a helper: a direct (weak/subject key) (or any component value)
   evaluated in the deftest body stays in bb/SCI's interpreter frame and
   roots the component, defeating the collection assertion."
  [key]
  (some? (weak/subject key)))

(t/deftest ^:slow test-dropped-component-is-reclaimed-by-gc-and-sweep
  (t/testing "a component dropped without dispose (the third-party
            drop-site case) is reclaimed once collected — the sweep unwatches
            its refs and removes the registry entry"
    (let [pre (component/live-watch-count)
          {:keys [wref key text-atom]} (make-droppable-text)]
      (t/is (live-subject? key) "live while referenced")
      (t/is (test-utils/await-collected #(nil? (.get wref)))
            "the component was collected")
      (t/is (contains? (.getWatches text-atom) key)
            "the atom watch is still installed until the sweep")
      (component/sweep-dead-watches!)
      (t/is (not (contains? (.getWatches text-atom) key))
            "the sweep removed the atom watch")
      (t/is (not (live-subject? key)) "the registry entry is gone")
      ;; <=, not =: the forced GC may also clear unrelated stale entries from
      ;; earlier tests, which lowers the registry-wide live count.
      (t/is (<= (component/live-watch-count) pre)))))

(t/deftest test-tracker-keys-are-unique-and-meta-owned
  (t/testing "finding 4: the watch key lives in the component's cache atom
            metadata — unique per component, never allocated for a
            cache-less record"
    (let [a (text/make-text "a" 0 0)
          b (text/make-text "b" 0 0)]
      (core/render a 5)
      (core/render b 5)
      (let [ka (watch-key-of a)
            kb (watch-key-of b)]
        (t/is (keyword? ka) "a render allocates the key once")
        (t/is (not= ka kb) "two live components never share a key"))))
  (t/testing "a cache-less component disposes without minting a key"
    (t/is (nil? (protocols/dispose (container/make-container []))))))

(t/deftest test-tracker-key-concurrent-allocation-converges
  (t/testing "finding 4: concurrent first renders converge on one key — a
            lost update would leave one thread watching under a key the
            others can never unwatch"
    (let [c (text/make-text "x" 0 0)
          key-fn (var-get #'component/tracker-key)
          ks (->> (range 32)
                  (mapv (fn [_] (future (key-fn c))))
                  (mapv deref))]
      (t/is (= 1 (count (distinct ks))) "one key for every allocator")
      (t/is (= (first ks) (watch-key-of c))))))

(t/deftest test-equal-value-reset-keeps-cache
  ;; equal-value reset! must not invalidate — the cached result stays valid
  (let [c (text/make-text "hi" 0 0)
        r1 (core/render c 10)]
    (text/text-set! c "hi") ;; same value → watch fires but must skip
    (t/is (identical? r1 (core/render c 10)) "cache survives equal-value reset"))
  ;; a genuinely different value still invalidates
  (let [c (text/make-text "hi" 0 0)]
    (core/render c 10)
    (text/text-set! c "bye")
    (let [lines (core/render c 10)]
      (t/is (.contains (first lines) "bye")))))

(t/deftest test-fresh-but-equal-collection-write-keeps-cache
  ;; A persistent collection write always yields a fresh root object, so
  ;; the watch/hit-check identical? fast path misses — structural = must
  ;; catch it and keep the cache valid. select-list renders @items-atom,
  ;; so its cached lines must survive a fresh-but-equal vector write and
  ;; bust on a genuinely different one.
  (let [sl (sl/make-select-list [{:id :a :label "Alpha"}
                                 {:id :b :label "Beta"}])
        r1 (core/render sl 60)]
    ;; fresh vector, equal content → identical? misses, = catches → hit
    (reset! (:items-atom sl) [{:id :a :label "Alpha"} {:id :b :label "Beta"}])
    (t/is (identical? r1 (core/render sl 60))
          "cache survives fresh-but-equal collection write"))
  ;; a genuinely different collection still invalidates
  (let [sl (sl/make-select-list [{:id :a :label "Alpha"}])]
    (core/render sl 60)
    (reset! (:items-atom sl) [{:id :a :label "Changed!"}])
    (t/is (some #(.contains % "Changed!") (core/render sl 60)))))

(t/deftest test-footer-reactive
  ;; the footer's render cache must invalidate when a tracked atom changes —
  ;; here the extension-statuses atom (provider atoms are read inside helper
  ;; fns, so callers invalidate explicitly; this tests the lexical tracking)
  (let [f (footer/make-footer)]
    (core/render f 40)
    (footer/footer-set-extension-status! f "ext" "● active")
    (let [lines (core/render f 40)]
      (t/is (some #(.contains % "● active") lines)))))

;; ─── cache pins its reactions ─────────────────

(t/deftest test-cache-pins-its-reactions
  (t/testing "a track! cache entry is [reaction value], so a collected
            reaction can never validate a stale cache"
    (let [a (atom 1)
          rx (reakt/make-reaction (fn [] (reakt/tracked-deref a)))
          c {:cache (atom nil)}]
      (t/is (= 1 (component/track-render c 10 (fn [] (reakt/tracked-deref rx)))))
      (let [entry (get (:rx @(:cache c)) (reakt/-cell rx))]
        (t/is (vector? entry) "the entry holds [reaction value]")
        (t/is (identical? rx (first entry)) "the reaction is pinned")
        (t/is (= 1 (second entry)) "with the value as read"))
      (t/is (= 1 (component/track-render c 10 (fn [] :not-run)))
            "the cache still hits through the pinned reaction")
      (component/remove-track-watches! c)
      (reakt/dispose! rx))))

(t/deftest test-counts-are-per-kind
  (t/testing "components and reactions share the weak registry but count
            separately"
    (let [watches (component/live-watch-count)
          rx (reakt/make-reaction (fn [] 1))]
      @rx
      (t/is (<= (component/live-watch-count) watches)
            "a reaction does not inflate the track! component count")
      (reakt/dispose! rx))
    (let [reactions (reakt/live-reaction-count)
          c (text/make-text "a" 0 0)]
      (core/render c 5)
      (t/is (<= (reakt/live-reaction-count) reactions)
            "a component does not inflate the reaction count")
      (protocols/dispose c))))

(defn- cache-pinned-reaction-lifecycle
  "One helper on purpose: a deftest body that evaluates any intermediate
   (the reaction, its handles) roots it through the bb/SCI frame — see
   test-utils/await-collected. The WRef is built in its own fn so no local
   here holds the reaction. Returns booleans only: the cache pins the
   reaction through GC, and clearing the cache releases it."
  []
  (let [a (atom 0)
        c {:cache (atom nil)}]
    (component/track-render c 5
                            (fn [] (reakt/tracked-deref
                                    (reakt/make-reaction
                                     (fn [] (reakt/tracked-deref a))))))
    (let [wref ((fn [] (java.lang.ref.WeakReference.
                        (first (val (first (:rx @(:cache c))))))))
          cache (:cache c)]
      ;; Unregister the component's weak entry: only the cache pins the
      ;; reaction now.
      (component/remove-track-watches! c)
      (dotimes [_ 5]
        (System/gc)
        (Thread/sleep 20))
      (let [pinned? (some? (.get wref))]
        (reset! cache nil)
        {:pinned? pinned?
         :collected? (test-utils/await-collected #(nil? (.get wref)))}))))

(t/deftest ^:slow test-cache-pinned-reaction-survives-until-the-cache-is-cleared
  (t/testing "GC cannot collect a reaction the track! cache
            validates; clearing the cache releases it"
    (let [{:keys [pinned? collected?]} (cache-pinned-reaction-lifecycle)]
      (t/is pinned? "the cache entry holds the reaction")
      (t/is collected? "with the cache cleared the reaction is collected"))))