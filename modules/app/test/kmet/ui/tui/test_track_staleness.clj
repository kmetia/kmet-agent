(ns kmet.ui.tui.test-track-staleness
  "The stale-cache oracle for the message components' `track!` caches: a
   component whose inputs changed must render exactly what a freshly
   constructed component renders, and an unchanged one must hand back the
   identical object.

   This is the net `atoms-unchanged?` used to be (render-opt.md §6), moved
   into the suite where it belongs: the frame benchmark can show a cache got
   faster, never that it went stale, and the failure mode is silent."
  (:require [clojure.test :as t :refer [deftest]]
            [kmet.test-utils :as tu]
            [kmet.tui.core :as core]
            [kmet.tui.components.expandable-text :as et]
            [kmet.ui.tui.assistant-message :as am]
            [kmet.ui.tui.custom-message :as cm]
            [kmet.ui.tui.tool-execution :as te]
            [kmet.ui.tui.user-message :as um]))

(t/use-fixtures :each tu/with-extension-host)

(defn- cache-matches-fresh
  "Differential staleness check for one component family. MAKE builds a fresh
   instance, MUTATE! applies the input change under test to an instance.

   Four assertions, and the third is what keeps the fourth from being
   vacuous: the mutation must actually change what the component renders, or
   comparing the reused instance against a fresh one proves nothing."
  [label make mutate! width]
  (let [c (make)
        before (core/render c width)]
    (t/is (= (vec before) (vec (core/render (make) width)))
          (str label ": two fresh instances agree"))
    (t/is (identical? before (core/render c width))
          (str label ": an unchanged re-render returns the cached object"))
    (mutate! c)
    (let [reused (vec (core/render c width))
          fresh (make)
          _ (mutate! fresh)
          expected (vec (core/render fresh width))]
      (t/is (not= (vec before) reused)
            (str label ": the mutation changes the output, so the oracle bites"))
      (t/is (= expected reused)
            (str label ": the invalidated cache matches a fresh component")))))

(deftest test-user-message-cache-matches-fresh
  ;; the pad atom is the chat history's shared one: one reset! re-pads every
  ;; message it owns, which is the invalidation the transcript relies on
  (cache-matches-fresh "user message"
                       #(um/make-user-message :text "hello world hello world")
                       #(reset! (:output-pad-atom %) 3)
                       60))

(deftest test-assistant-message-cache-matches-fresh
  (cache-matches-fresh "assistant message"
                       (fn []
                         (am/make-assistant-message
                          :text (str "an answer long enough to wrap at this width "
                                     "and to need a second line")
                          :thinking "a thinking block"))
                       (fn [c]
                         (reset! (:output-pad-atom c) 3)
                         (swap! (:text-atom c) str " plus one more delta"))
                       60))

(deftest test-tool-execution-cache-matches-fresh
  (cache-matches-fresh "tool execution"
                       #(te/make-tool-execution :name "bash"
                                                :args {:command "echo hello"}
                                                :content "hello\n")
                       #(reset! (:output-pad-atom %) 3)
                       60))

(deftest test-custom-message-cache-matches-fresh
  (cache-matches-fresh "custom message"
                       #(cm/make-custom-message :label "note"
                                                :content "a custom message body")
                       #(reset! (:output-pad-atom %) 3)
                       60))

(deftest test-expandable-text-cache-matches-fresh
  ;; the track-deps family: the body reads no atom whose value appears in the
  ;; output, it declares the inner Text's text atom as its dependency
  (cache-matches-fresh "expandable text"
                       #(et/make-expandable-text (constantly "collapsed")
                                                 (constantly "the expanded content"))
                       #(et/expandable-text-set-expanded! % true)
                       40))
