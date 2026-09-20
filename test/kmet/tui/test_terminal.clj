(ns kmet.tui.test-terminal
  "Tests for the shared terminal body in kmet.tui.terminal — the OSC 9;4
   progress keepalive/dedupe (apply-progress!) and the stop-time clear."
  (:require [clojure.test :as t]
            [kmet.libs.terminal :as lib]
            [kmet.tui.core :as core]
            [kmet.tui.terminal :as term]))

(defn- recording-terminal
  "ITerminal stub recording written output."
  []
  (let [writes (atom [])]
    {:terminal (reify term/ITerminal
                 (start! [_ _ _] nil)
                 (stop! [_] nil)
                 (started? [_] true)
                 (write-output [_ s] (swap! writes conj s))
                 (read-input [_ _] -1)
                 (columns [_] 80)
                 (rows [_] 24)
                 (set-progress! [_ _] nil))
     :writes writes}))

(defn- progress-terminal
  "ITerminal stub whose set-progress! routes through the shared
   apply-progress! body, with the keepalive interval exposed."
  []
  (let [writes (atom [])
        interval (atom nil)
        terminal (reify term/ITerminal
                   (start! [_ _ _] nil)
                   (stop! [_] nil)
                   (started? [_] true)
                   (write-output [_ s] (swap! writes conj s))
                   (read-input [_ _] -1)
                   (columns [_] 80)
                   (rows [_] 24)
                   (set-progress! [this active]
                     (term/apply-progress! this interval active)))]
    {:terminal terminal :writes writes :interval interval}))

(t/deftest apply-progress-dedupes-the-clear
  (let [{:keys [terminal writes]} (recording-terminal)
        interval (atom nil)]
    (t/testing "inactive with no interval writes nothing (turn ends while
                the feature is off must not emit an OSC clear)"
      (term/apply-progress! terminal interval false)
      (t/is (empty? @writes)))
    (t/testing "active writes the sequence and arms the keepalive"
      (term/apply-progress! terminal interval true)
      (t/is (= [lib/TERMINAL-PROGRESS-ACTIVE-SEQUENCE] @writes))
      (t/is (some? @interval)))
    (t/testing "clearing writes once and cancels the keepalive"
      (term/apply-progress! terminal interval false)
      (t/is (= [lib/TERMINAL-PROGRESS-ACTIVE-SEQUENCE
                lib/TERMINAL-PROGRESS-CLEAR-SEQUENCE]
               @writes))
      (t/is (nil? @interval)))
    (t/testing "a second clear is a no-op"
      (term/apply-progress! terminal interval false)
      (t/is (= [lib/TERMINAL-PROGRESS-ACTIVE-SEQUENCE
                lib/TERMINAL-PROGRESS-CLEAR-SEQUENCE]
               @writes)))))

(t/deftest tui-stop-clears-an-active-progress-indicator
  (t/testing "pi: stop → setProgress(false) — quitting mid-turn must not
              leave the indicator asserting"
    (let [{:keys [terminal writes interval]} (progress-terminal)
          tui (core/create-tui terminal)]
      (term/set-progress! terminal true)
      (t/is (= [lib/TERMINAL-PROGRESS-ACTIVE-SEQUENCE] @writes))
      (core/tui-stop tui)
      (t/is (= [lib/TERMINAL-PROGRESS-ACTIVE-SEQUENCE
                lib/TERMINAL-PROGRESS-CLEAR-SEQUENCE]
               @writes))
      (t/is (nil? @interval) "the keepalive is cancelled")))
  (t/testing "a stop without active progress writes nothing"
    (let [{:keys [terminal writes]} (progress-terminal)
          tui (core/create-tui terminal)]
      (core/tui-stop tui)
      (t/is (empty? @writes)))))
