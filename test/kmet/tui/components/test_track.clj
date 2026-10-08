(ns kmet.tui.components.test-track
  "Tests for the reactive track! cache: setters need no manual invalidate,
   cache hits return the same object, and width changes bust the cache."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.macros :as macros]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.text :as text]
            [kmet.tui.components.markdown :as md]
            [kmet.tui.components.select-list :as sl]
            [kmet.tui.components.settings-list :as settings]
            [kmet.app.ui.footer :as footer]
            [kmet.libs.weak :as weak]
            [kmet.test-utils :as test-utils]))

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

(t/deftest test-cache-verifies-values-even-without-watch
  ;; Simulates the watch-registration race: a value changed after the watch
  ;; was removed never fires a notification — the value re-verification on
  ;; cache hit must catch it anyway.
  (let [c (text/make-text "a" 0 0)]
    (core/render c 5)
    (remove-watch (:text-atom c)
                  (:kmet.tui.macros/watch-key (meta (:cache c))))
    (text/text-set! c "b") ;; no watch → no invalidation
    (let [lines (core/render c 5)]
      (t/is (.contains (first lines) "b")))))

;; ─── weak registry (leaks.md Pass 2, Stage B) ─────────────────────────────

(t/deftest test-live-watch-count-follows-render-and-dispose
  (t/testing "the track! registry is weak-registry backed: a live component
            counts, dispose unregisters, both idempotently"
    (let [pre (macros/live-watch-count)
          c (text/make-text "a" 0 0)]
      (t/is (not (macros/tracked? c)) "nothing is registered before the first render")
      (core/render c 5)
      (t/is (macros/tracked? c))
      (protocols/dispose c)
      (t/is (not (macros/tracked? c)) "the entry is gone")
      (t/is (<= (macros/live-watch-count) pre))
      (t/is (nil? (protocols/dispose c)) "dispose again is a no-op"))))

(t/deftest test-dispose-before-any-render-is-a-no-op
  (t/testing "teardown must not mint a key for a component that never ran
            a track! body"
    (let [pre (macros/live-watch-count)
          c (text/make-text "a" 0 0)]
      (t/is (not (macros/tracked? c)))
      (t/is (nil? (protocols/dispose c)))
      (t/is (<= (macros/live-watch-count) pre)))))

(defn- make-droppable-text
  "A rendered text component with only non-component references escaping
   (weak ref, watch key, its tracked atom) so it can be collected."
  []
  (let [c (text/make-text "drop" 0 0)]
    (core/render c 5)
    {:wref (java.lang.ref.WeakReference. c)
     :key (:kmet.tui.macros/watch-key (meta (:cache c)))
     :text-atom (:text-atom c)}))

(defn- live-subject?
  "True while KEY's weak entry still holds its subject. Look the subject up
   inside a helper: a direct (weak/subject key) (or any component value)
   evaluated in the deftest body stays in bb/SCI's interpreter frame and
   roots the component, defeating the collection assertion."
  [key]
  (some? (weak/subject key)))

(t/deftest ^:slow test-dropped-component-is-reclaimed-by-gc-and-sweep
  (t/testing "Stage B: a component dropped without dispose (the third-party
            drop-site case) is reclaimed once collected — the sweep unwatches
            its refs and removes the registry entry"
    (let [pre (macros/live-watch-count)
          {:keys [wref key text-atom]} (make-droppable-text)]
      (t/is (live-subject? key) "live while referenced")
      (t/is (test-utils/await-collected #(nil? (.get wref)))
            "the component was collected")
      (t/is (contains? (.getWatches text-atom) key)
            "the atom watch is still installed until the sweep")
      (macros/sweep-dead-watches!)
      (t/is (not (contains? (.getWatches text-atom) key))
            "the sweep removed the atom watch")
      (t/is (not (live-subject? key)) "the registry entry is gone")
      ;; <=, not =: the forced GC may also clear unrelated stale entries from
      ;; earlier tests, which lowers the registry-wide live count.
      (t/is (<= (macros/live-watch-count) pre)))))

(t/deftest test-tracker-keys-are-unique-and-meta-owned
  (t/testing "finding 4: the watch key lives in the component's cache atom
            metadata — unique per component, never allocated for a
            cache-less record"
    (let [a (text/make-text "a" 0 0)
          b (text/make-text "b" 0 0)]
      (core/render a 5)
      (core/render b 5)
      (let [ka (:kmet.tui.macros/watch-key (meta (:cache a)))
            kb (:kmet.tui.macros/watch-key (meta (:cache b)))]
        (t/is (keyword? ka) "a render allocates the key once")
        (t/is (not= ka kb) "two live components never share a key"))))
  (t/testing "a cache-less component disposes without minting a key"
    (t/is (nil? (protocols/dispose (container/make-container []))))))

(t/deftest test-tracker-key-concurrent-allocation-converges
  (t/testing "finding 4: concurrent first renders converge on one key — a
            lost update would leave one thread watching under a key the
            others can never unwatch"
    (let [c (text/make-text "x" 0 0)
          key-fn (var-get #'macros/tracker-key)
          ks (->> (range 32)
                  (mapv (fn [_] (future (key-fn c))))
                  (mapv deref))]
      (t/is (= 1 (count (distinct ks))) "one key for every allocator")
      (t/is (= (first ks) (:kmet.tui.macros/watch-key (meta (:cache c))))))))

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
