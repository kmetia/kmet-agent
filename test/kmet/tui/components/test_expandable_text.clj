(ns kmet.tui.components.test-expandable-text
  (:require [clojure.test :as t]
            [kmet.tui.core :as core]
            [kmet.tui.components.expandable-text :as et]
            [kmet.tui.macros :as macros]
            [kmet.tui.protocols :as protocols]
            [kmet.test-utils :as tu]))

(defn- render-plain [c width]
  (mapv tu/strip-ansi (core/render c width)))

(t/deftest test-collapsed-by-default
  (let [c (et/make-expandable-text (fn [] "collapsed") (fn [] "expanded"))]
    (t/is (some #(.contains % "collapsed") (render-plain c 40)))
    (t/is (not-any? #(.contains % "expanded") (render-plain c 40)))))

(t/deftest test-expanded-init
  (let [c (et/make-expandable-text (fn [] "collapsed") (fn [] "expanded")
                                   :expanded? true)]
    (t/is (some #(.contains % "expanded") (render-plain c 40)))
    (t/is (et/expandable-text-get-expanded c))))

(t/deftest test-set-expanded-switches
  (let [c (et/make-expandable-text (fn [] "collapsed") (fn [] "expanded"))]
    (et/expandable-text-set-expanded! c true)
    (t/is (some #(.contains % "expanded") (render-plain c 40)))
    (t/is (not-any? #(.contains % "collapsed") (render-plain c 40)))
    (et/expandable-text-set-expanded! c false)
    (t/is (some #(.contains % "collapsed") (render-plain c 40)))))

(t/deftest test-rebuild-reruns-fns
  (let [collapsed (atom "a")
        expanded (atom "b")
        c (et/make-expandable-text #(str "c:" @collapsed) #(str "e:" @expanded))]
    (core/render c 40)
    (reset! collapsed "z")
    ;; The fns are not tracked — rebuild! re-runs them explicitly
    (et/expandable-text-rebuild! c)
    (t/is (some #(.contains % "c:z") (render-plain c 40)))))

(t/deftest test-set-expanded-uses-current-fn-output
  (let [c (et/make-expandable-text (fn [] "one") (fn [] "two"))]
    (core/render c 40)
    (et/expandable-text-set-expanded! c true)
    (t/is (some #(.contains % "two") (render-plain c 40)))))

(t/deftest test-padding
  (let [c (et/make-expandable-text (fn [] "hi") (fn [] "hi") :padding-x 1)
        line (first (render-plain c 10))]
    (t/is (.startsWith line " hi"))))

(t/deftest test-dispose-disposes-the-inner-text
  ;; the wrapper renders an inner Text that registers its own track! watches;
  ;; it is not a container child, so the wrapper's dispose must reach it
  (let [watchers macros/live-watch-count
        pre (watchers)
        c (et/make-expandable-text (fn [] "collapsed") (fn [] "expanded"))]
    (core/render c 40)
    (t/is (macros/tracked? c) "the wrapper is tracked")
    (t/is (macros/tracked? @(:text-comp c)) "its inner Text is tracked")
    (protocols/dispose c)
    (t/is (<= (watchers) pre)
          "disposing the wrapper disposes the inner Text")))
