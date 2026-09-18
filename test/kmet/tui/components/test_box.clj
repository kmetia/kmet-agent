(ns kmet.tui.components.test-box
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.components.box :as box]
            [kmet.tui.components.text :as text]))

(t/deftest test-box-create
  (let [b (box/make-box)]
    (t/is (satisfies? core/IComponent b))
    (t/is (empty? @(:children b)))))

(t/deftest test-box-render
  (let [t1 (text/make-text "hi" 0 0)
        b (box/make-box 1 1 nil)]
    (box/box-add-child b t1)
    (let [lines (core/render b 6)]
      (t/is (= 3 (count lines)))   ;; top pad + content + bottom pad
      (t/is (= 6 (count (first lines))))
      (t/is (.contains (second lines) "hi")))))

(t/deftest test-box-render-no-padding
  (let [t1 (text/make-text "hi" 0 0)
        b (box/make-box 0 0 nil)]
    (box/box-add-child b t1)
    (let [lines (core/render b 6)]
      (t/is (= 1 (count lines)))
      (t/is (.contains (first lines) "hi")))))

(t/deftest test-box-empty
  (let [b (box/make-box 1 1 nil)
        lines (core/render b 10)]
    (t/is (empty? lines))))

(t/deftest test-box-set-padding!
  ;; padding is live (the hiccup :box apply path): the setters update the
  ;; layout and reset the hand-rolled cache, so the next render is fresh
  (let [t1 (text/make-text "hi" 0 0)
        b (box/make-box 1 1 nil)]
    (box/box-add-child b t1)
    (t/is (= 3 (count (core/render b 8))))
    (box/box-set-padding-y! b 0)
    (let [lines (core/render b 8)]
      (t/is (= 1 (count lines)) "padding-y 0 drops the pad rows")
      (t/is (.contains (first lines) "hi")))
    (box/box-set-padding-x! b 0)
    (t/is (.startsWith (first (core/render b 8)) "hi")
          "padding-x 0 removes the left inset")))

(t/deftest test-box-set-padding-no-ops-when-unchanged
  ;; callers sync the padding every pass (the chat history's shared output
  ;; pad) — a same-value set must keep the memoized composition
  (let [t1 (text/make-text "hi" 0 0)
        b (box/make-box 1 1 nil)]
    (box/box-add-child b t1)
    (core/render b 8)
    (let [cached @(:cache b)]
      (t/is (some? cached) "a render memoizes the composition")
      (box/box-set-padding-x! b 1)
      (box/box-set-padding-y! b 1)
      (t/is (identical? cached @(:cache b)) "same value keeps the memo")
      (box/box-set-padding-x! b 2)
      (t/is (nil? @(:cache b)) "a changed value drops it"))))

(t/deftest test-box-multiple-children
  (let [t1 (text/make-text "a" 0 0)
        t2 (text/make-text "b" 0 0)
        b (box/make-box 0 0 nil)]
    (box/box-add-child b t1)
    (box/box-add-child b t2)
    (let [lines (core/render b 5)]
      (t/is (= 2 (count lines)))
      (t/is (.contains (first lines) "a"))
      (t/is (.contains (second lines) "b")))))

(t/deftest test-box-background
  (let [t1 (text/make-text "hi" 0 0)
        bg-calls (atom 0)
        b (box/make-box 1 1 (fn [s] (swap! bg-calls inc) s))]
    (box/box-add-child b t1)
    (let [_ (core/render b 6)]
      (t/is (pos? @bg-calls)))))
