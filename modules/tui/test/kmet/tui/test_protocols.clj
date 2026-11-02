(ns kmet.tui.test-protocols
  "The line-collection primitives: join-lines, concat-cached and
   cached-lines — the memo contract every multi-child component (Container,
   Box, ChatHistory, the frame's document) builds on."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.components.text :as text]))

(t/deftest test-join-lines
  (t/testing "flattens parts in order"
    (t/is (= [] (protocols/join-lines [])))
    (t/is (= ["a" "b" "c"] (protocols/join-lines [["a"] ["b" "c"]])))
    (t/is (= ["a"] (protocols/join-lines [["a"]])))
    (t/is (= ["a"] (protocols/join-lines [["a"] nil]))
          "a component that renders nothing is a no-op part")
    (t/is (= [] (protocols/join-lines [nil]))))
  (t/testing "a single vector part is returned as-is, not copied"
    (let [part ["a" "b"]]
      (t/is (identical? part (protocols/join-lines [part])))))
  (t/testing "a single non-vector part still yields a vector"
    (t/is (= ["a" "b"] (protocols/join-lines [(list "a" "b")])))))

(t/deftest test-render-all-single-component-is-not-copied
  (let [c (text/make-text "hi" 0 0)]
    (t/is (identical? (protocols/render c 10) (protocols/render-all [c] 10)))))

(t/deftest test-concat-cached-reuses-an-unchanged-join
  (let [a (text/make-text "a" 0 0)
        b (text/make-text "b" 0 0)
        render-both (fn [] (mapv (fn [c] (protocols/render c 10)) [a b]))
        r1 (protocols/concat-cached nil [10] (render-both))
        r2 (protocols/concat-cached (:cache r1) [10] (render-both))]
    (t/is (= (:lines r1) (:lines r2)))
    (t/is (identical? (:lines r1) (:lines r2))
          "an unchanged pass returns the previous vector itself")
    (t/is (identical? (:cache r1) (:cache r2))
          "the memo is carried over, not rebuilt")))

(t/deftest test-concat-cached-rebuilds-when-an-input-changes
  (let [a (text/make-text "a" 0 0)
        b (text/make-text "b" 0 0)
        render-both (fn [] (mapv (fn [c] (protocols/render c 10)) [a b]))
        r1 (protocols/concat-cached nil [10] (render-both))]
    (t/testing "a re-derived child is a miss, never a stale hit"
      (text/text-set! a "A")
      (let [r2 (protocols/concat-cached (:cache r1) [10] (render-both))]
        (t/is (not (identical? (:lines r1) (:lines r2))))
        (t/is (some #(str/includes? % "A") (:lines r2)))))
    (t/testing "a changed width is a miss"
      (let [r2 (protocols/concat-cached (:cache r1) [10] (render-both))
            r3 (protocols/concat-cached (:cache r2) [11] (render-both))]
        (t/is (not (identical? (:lines r2) (:lines r3))))
        (t/is (= (count (:lines r2)) (count (:lines r3))))))
    (t/testing "a changed part count is a miss"
      (let [r2 (protocols/concat-cached (:cache r1) [10] (render-both))
            r3 (protocols/concat-cached (:cache r2) [10]
                                        (mapv (fn [c] (protocols/render c 10)) [a]))]
        (t/is (not (identical? (:lines r2) (:lines r3))))
        (t/is (= 1 (count (:lines r3))))))))

(t/deftest test-concat-cached-misses-on-a-fresh-but-equal-part
  ;; a child that rebuilds an equal vector is a miss (correct, just no
  ;; saving) — the cache never requires a child to be stable
  (let [fresh (fn [] (vec (list "line")))
        r1 (protocols/concat-cached nil [10] [(fresh)])
        r2 (protocols/concat-cached (:cache r1) [10] [(fresh)])]
    (t/is (= (:lines r1) (:lines r2)))
    (t/is (not (identical? (:lines r1) (:lines r2))))))

(t/deftest test-concat-cached-runs-the-join-only-on-a-miss
  (let [part ["a"]
        calls (atom 0)
        join-fn (fn [parts] (swap! calls inc) (protocols/join-lines parts))
        r1 (protocols/concat-cached nil [10] [part] join-fn)
        r2 (protocols/concat-cached (:cache r1) [10] [part] join-fn)]
    (t/is (= 1 @calls) "the join runs once, on the miss")
    (t/is (identical? (:lines r1) (:lines r2)))
    (t/is (= ["a"] (:lines r2)))))

(t/deftest test-concat-cached-without-a-prev-joins
  (t/is (= ["a" "b"] (:lines (protocols/concat-cached nil [10] [["a"] ["b"]]))))
  (t/is (= [] (:lines (protocols/concat-cached nil [10] [])))))

(t/deftest test-cached-lines
  (let [memo (atom nil)
        a (text/make-text "a" 0 0)
        b (text/make-text "b" 0 0)
        render #(mapv (fn [c] (protocols/render c 10)) [a b])
        first-lines (protocols/cached-lines memo [10] (render))
        writes (atom 0)]
    (t/testing "a hit returns the previous vector and does not touch the memo"
      (add-watch memo ::count (fn [_ _ _ _] (swap! writes inc)))
      (try
        (t/is (identical? first-lines (protocols/cached-lines memo [10] (render))))
        (t/is (zero? @writes) "a hit writes nothing")
        (t/testing "a miss writes the new memo once"
          (text/text-set! b "B")
          (t/is (some #(str/includes? % "B")
                      (protocols/cached-lines memo [10] (render))))
          (t/is (= 1 @writes)))
        (finally
          (remove-watch memo ::count))))
    (t/testing "no memo atom still renders (it just never memoizes)"
      (t/is (= ["a"] (protocols/cached-lines nil [10] [["a"]]))))))
