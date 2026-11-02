(ns kmet.tui.components.test-container
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.text :as text]
            [kmet.hikap.component :as component]
            [kmet.tui.protocols :as protocols]))

(t/deftest test-container-create
  (let [c (container/make-container)]
    (t/is (satisfies? core/IComponent c))
    (t/is (empty? @(:children c)))))

(t/deftest test-container-render
  (let [t1 (text/make-text "a" 0 0)
        t2 (text/make-text "b" 0 0)
        c (container/make-container [t1 t2])
        lines (core/render c 5)]
    (t/is (= 2 (count lines)))
    (t/is (.contains (first lines) "a"))
    (t/is (.contains (second lines) "b"))))

(t/deftest test-container-add-remove
  (let [t1 (text/make-text "a" 0 0)
        t2 (text/make-text "b" 0 0)
        c (container/make-container [t1])]
    (t/is (= 1 (count (core/render c 5))))
    (container/container-add-child c t2)
    (t/is (= 2 (count (core/render c 5))))
    (container/container-remove-child c t1)
    (t/is (= 1 (count (core/render c 5))))))

(t/deftest test-container-clear
  (let [t1 (text/make-text "a" 0 0)
        c (container/make-container [t1])]
    (container/container-clear c)
    (let [lines (core/render c 5)]
      (t/is (empty? lines)))))

(t/deftest test-container-replace-children-disposes-dropped
  (let [kept (text/make-text "keep" 0 0)
        dropped (text/make-text "drop" 0 0)
        c (container/make-container [kept dropped])]
    (core/render c 10)
    (t/is (component/tracked? kept))
    (t/is (component/tracked? dropped))
    ;; replace: KEPT is re-added (same instance), a new child takes DROPPED's
    ;; place — the dropped child is disposed, its watches torn down
    (let [fresh (text/make-text "fresh" 0 0)]
      (container/container-replace-children! c [kept fresh])
      (t/is (= 2 (count @(:children c))))
      (t/is (not (component/tracked? dropped)) "dropped child disposed (no zombie watches)")
      (t/is (component/tracked? kept) "kept child untouched")
      (core/render c 10)
      (t/is (component/tracked? fresh)))))

(t/deftest test-container-replace-children-empty
  (let [t1 (text/make-text "a" 0 0)
        c (container/make-container [t1])]
    (core/render c 5)
    (container/container-replace-children! c [])
    (t/is (empty? @(:children c)))
    (t/is (not (component/tracked? t1)) "clearing disposes every child")
    (t/is (empty? (core/render c 5)))))

(t/deftest test-container-replace-children-keeps-reordered-instances
  (let [a (text/make-text "a" 0 0)
        b (text/make-text "b" 0 0)
        c (text/make-text "c" 0 0)
        cont (container/make-container [a b c])]
    (core/render cont 5)
    (container/container-replace-children! cont [c a b])
    (t/is (= [c a b] @(:children cont)))
    (t/is (component/tracked? a))
    (t/is (component/tracked? b))
    (t/is (component/tracked? c))
    (t/is (= ["c" "a" "b"] (mapv str/trim (core/render cont 5))))))

(defn- spy-component
  "A minimal IComponent counting dispose calls (for lifecycle assertions)."
  [counter]
  (reify
    protocols/IComponent
    (render [_ _] [])
    (handle-input [_ _] nil)
    (invalidate [_] nil)
    (dispose [_] (swap! counter inc))))

(t/deftest test-container-replace-children-disposes-each-dropped-once
  (t/testing "a dropped instance listed twice in the old list is disposed once
            (non-idempotent foreign disposes must not run twice)"
    (let [disposed (atom 0)
          spy (spy-component disposed)
          keep (text/make-text "k" 0 0)
          cont (container/make-container [spy spy keep])]
      (container/container-replace-children! cont [keep])
      (t/is (= 1 @disposed) "disposed exactly once")
      (t/is (= [keep] @(:children cont)))))
  (t/testing "distinct dropped instances are each disposed"
    (let [disposed (atom 0)
          a (spy-component disposed)
          b (spy-component disposed)
          cont (container/make-container [a b])]
      (container/container-replace-children! cont [])
      (t/is (= 2 @disposed)))))

(t/deftest test-container-replace-children-nil-safe
  (t/testing "a nil entry in the old list is skipped (nothing to release)"
    (let [t1 (text/make-text "a" 0 0)
          cont (container/make-container [t1 nil])]
      (t/is (nil? (try (container/container-replace-children! cont [t1])
                       nil
                       (catch Throwable e e)))
            "no throw on nil")
      (t/is (= [t1] @(:children cont)))))
  (t/testing "nil in the NEW list is stored as-is (no dispose attempted)"
    (let [t1 (text/make-text "a" 0 0)
          cont (container/make-container [t1])]
      (container/container-replace-children! cont [nil])
      (t/is (= 1 (count @(:children cont)))))))
(t/deftest test-container-reuses-unchanged-lines
  (t/testing "an idle container returns the vector it returned last pass"
    (let [t1 (text/make-text "a" 0 0)
          t2 (text/make-text "b" 0 0)
          c (container/make-container [t1 t2])
          f1 (core/render c 10)
          f2 (core/render c 10)]
      (t/is (= ["a" "b"] (mapv str/trim f2)))
      (t/is (identical? f1 f2) "no line copying while the children are unchanged")
      (t/testing "a re-derived child invalidates the reused vector"
        (text/text-set! t1 "A")
        (let [f3 (core/render c 10)]
          (t/is (not (identical? f2 f3)))
          (t/is (= ["A" "b"] (mapv str/trim f3)))))
      (t/testing "a width change rebuilds at the new width"
        (let [f4 (core/render c 12)]
          (t/is (not (identical? f2 f4)))
          (t/is (every? #(= 12 (count %)) f4))))))
  (t/testing "adding and removing children is never served stale"
    (let [t1 (text/make-text "a" 0 0)
          t2 (text/make-text "b" 0 0)
          c (container/make-container [t1])]
      (core/render c 10)
      (container/container-add-child c t2)
      (t/is (= ["a" "b"] (mapv str/trim (core/render c 10))))
      (container/container-remove-child c t1)
      (t/is (= ["b"] (mapv str/trim (core/render c 10)))))))

(t/deftest test-container-clearing-drops-the-lines-memo
  (let [t1 (text/make-text "a" 0 0)
        c (container/make-container [t1])]
    (core/render c 10)
    (t/is (some? @(:lines-cache c)) "a render memoizes the children's lines")
    (container/container-clear c)
    (t/is (nil? @(:lines-cache c)) "the dropped children's lines are not retained")
    (container/container-add-child c t1)
    (core/render c 10)
    (t/is (some? @(:lines-cache c)))
    (container/container-replace-children! c [])
    (t/is (nil? @(:lines-cache c)))))
