(ns kmet.app.ui.test-bash-execution
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.ui.bash-execution :as be]
            [kmet.tui.core :as core]
            [kmet.tui.macros :as macros]
            [kmet.tui.timers :as timers]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]))

(t/deftest test-bash-execution-render-no-output
  (let [c (be/make-bash-execution :command "sleep 1" :exclude-from-context? false)
        lines (protocols/render c 40)]
    (t/is (seq lines))
    (t/is (= 40 (u/visible-width (first lines))) "top rule spans the width")
    (t/is (= 40 (u/visible-width (last lines))) "bottom rule spans the width")
    (t/is (some #(clojure.string/includes? % "$ sleep 1") lines) "command header shown")
    (t/is (some #(clojure.string/includes? % "Running") lines) "spinner shown while running")
    (t/is (some #(clojure.string/includes? % "Elapsed") lines) "elapsed shown while running")
    (be/bash-execution-set-complete! c 0 false)
    (protocols/dispose c)))

(t/deftest test-bash-execution-border-sets
  ;; the top/bottom rules come from a kmet.tui.border set (R5): the default
  ;; is the pi-parity rule, :ascii degrades it, :none drops the rules and
  ;; keeps the content. There are no side borders (issue #6), so output
  ;; stays mouse-select / copy-paste friendly.
  (let [render (fn [style]
                 (let [c (be/make-bash-execution :command "ls" :border style)
                       lines (protocols/render c 20)]
                   (be/bash-execution-set-complete! c 0 false)
                   (protocols/dispose c)
                   (mapv u/strip-ansi-codes lines)))]
    (t/is (= (apply str (repeat 20 "─")) (first (render nil))) "default rule unchanged")
    (t/is (clojure.string/includes? (second (render nil)) "$ ls") "command header shown")
    (t/is (not-any? #(or (clojure.string/starts-with? % "│")
                         (clojure.string/starts-with? % "|"))
                    (render nil))
          "no side-border glyphs on any line")
    (t/testing ":ascii"
      (let [lines (render :ascii)]
        (t/is (= (apply str (repeat 20 "-")) (first lines)))
        (t/is (clojure.string/includes? (second lines) "$ ls"))))
    (t/testing ":hidden keeps the rules' footprint without ink"
      (let [lines (render :hidden)]
        (t/is (= (apply str (repeat 20 " ")) (first lines)))
        (t/is (clojure.string/includes? (second lines) "$ ls"))))
    (t/testing ":none drops the rules entirely"
      (let [lines (render :none)]
        (t/is (clojure.string/includes? (first lines) "$ ls"))
        (t/is (= (- (count (render nil)) 2) (count lines))
              "the two rule lines are gone, the content is untouched")))
    (t/testing "an unknown style fails at construction"
      (t/is (thrown-with-msg? Exception #"unknown border style"
                              (be/make-bash-execution :command "ls" :border :asci))))))

(t/deftest test-bash-execution-render-with-output
  ;; Collapsed preview renders the last lines plus the expand hint.
  (let [c (be/make-bash-execution :command "ls" :exclude-from-context? false)
        big (clojure.string/join "\n" (repeat 30 "line"))]
    (be/bash-execution-append-output! c big)
    (be/bash-execution-set-complete! c 0 false)
    (let [lines (protocols/render c 40)]
      (t/is (seq lines))
      (t/is (some #(clojure.string/includes? % "line") lines) "preview lines shown")
      (t/is (some #(clojure.string/includes? % "more lines") lines) "expand hint shown")
      (t/is (some #(clojure.string/includes? % "Took") lines) "duration shown"))))

(t/deftest test-bash-execution-render-expanded
  (let [c (be/make-bash-execution :command "ls" :exclude-from-context? false)
        big (clojure.string/join "\n" (repeat 30 "line"))]
    (be/bash-execution-append-output! c big)
    (reset! (:expanded-atom c) true)
    (let [lines (protocols/render c 40)]
      (t/is (seq lines))
      (t/is (>= (count lines) 30) "expanded output renders all lines"))
    (be/bash-execution-set-complete! c 0 false)
    (protocols/dispose c)))

(t/deftest test-bash-execution-render-cancelled
  (let [c (be/make-bash-execution :command "sleep 5" :exclude-from-context? false)]
    (be/bash-execution-set-complete! c nil true)
    (let [lines (protocols/render c 40)]
      (t/is (some #(clojure.string/includes? % "cancelled") lines) "cancelled status shown"))))

(t/deftest test-bash-execution-frame-driver
  (t/testing "80ms frame driver (pi Loader setInterval parity) runs while :running; completion cancels it"
    (let [c (be/make-bash-execution :command "sleep 1")
          driver @(:ticker-id-atom c)]
      (t/is (some? driver) "driver armed with the component")
      (t/is (contains? (timers/scheduled) driver) "it is a live registry timer")
      (be/bash-execution-set-complete! c 0 false)
      (t/is (nil? @(:ticker-id-atom c)) "completion clears the driver")
      (t/is (not (contains? (timers/scheduled) driver))
            "completion cancels the registry timer"))))

(t/deftest test-bash-execution-elapsed-ticks-while-running
  (t/testing "1s elapsed ticker (pi renderResult setInterval parity) re-stamps while :running; completion clears it"
    (let [c (be/make-bash-execution :command "sleep 5")
          ticker @(:elapsed-ticker-id-atom c)]
      (t/is (some? ticker) "elapsed ticker armed with the component")
      (t/is (contains? (timers/scheduled) ticker))
      (protocols/render c 40)
      (let [before @(:now-atom c)]
        ;; the loop pumps; headless we pump by hand once the tick is due
        (Thread/sleep 1200)
        (timers/pump!)
        (t/is (> @(:now-atom c) before) "now re-stamped after ~1s")
        (t/is (some #(clojure.string/includes? % "Elapsed") (protocols/render c 40))
              "elapsed line renders while running"))
      (let [ticker @(:elapsed-ticker-id-atom c)]
        (be/bash-execution-set-complete! c 0 false)
        (t/is (nil? @(:elapsed-ticker-id-atom c)) "completion clears the elapsed ticker")
        (t/is (not (contains? (timers/scheduled) ticker))
              "completion cancels the registry timer")
        (t/is (some #(clojure.string/includes? % "Took") (protocols/render c 40))
              "took line renders after completion"))
      (protocols/dispose c))))

(t/deftest test-bash-execution-borders-flush
  ;; The top/bottom rules span the full width and the content carries no
  ;; side borders (issue #6), so output stays mouse-select / copy-paste
  ;; friendly. A broken rule shows as a line short of the last column.
  (let [c (be/make-bash-execution :command "ls" :exclude-from-context? false)]
    (be/bash-execution-append-output! c (clojure.string/join "\n" (repeat 30 "line")))
    (be/bash-execution-set-complete! c 0 false)
    (let [lines (protocols/render c 40)
          plain (mapv u/strip-ansi-codes lines)]
      (t/is (= 40 (u/visible-width (first lines))) "top rule spans the width")
      (t/is (= 40 (u/visible-width (last lines))) "bottom rule spans the width")
      (t/is (not-any? #(or (clojure.string/starts-with? % "│")
                           (clojure.string/starts-with? % "|"))
                      plain)
            "no side-border glyphs on any line")
      (t/is (some #(clojure.string/includes? % "line") lines) "preview lines shown")
      ;; No stray spacer line between status and bottom rule
      (t/is (some #(clojure.string/includes? % "Took") lines))
      (let [took-idx (first (keep-indexed #(when (clojure.string/includes? %2 "Took") %1) lines))]
        (t/is (= took-idx (- (count lines) 2))
              "bottom rule directly follows the status line")))))

(t/deftest test-theme-sub-retheme
  ;; Stage 5: the component subscribes to ui.subs/theme-sub — swapping the
  ;; shared atom re-themes rules/spinner on the next render, without any
  ;; per-component setter.
  (let [c (be/make-bash-execution :command "ls")]
    (t/is (some? c))
    (let [before (protocols/render c 40)]
      (t/is (seq before) "renders with the current theme")
      (reset! theme/theme-atom (theme/get-theme "light"))
      (try
        (let [lines (protocols/render c 40)]
          (t/is (some #(clojure.string/includes? % "$ ls") lines)
                "still renders after theme switch")
          (t/is (= 40 (u/visible-width (first lines))) "rules stay flush")
          (t/is (not= before lines) "styling changed with the theme"))
        (finally
          (reset! theme/theme-atom (theme/get-theme "dark"))
          (be/bash-execution-set-complete! c 0 false)
          (protocols/dispose c))))))

(t/deftest test-dispose-stops-frame-driver
  ;; A component dropped from the chat (e.g. /new while a run is in
  ;; flight) must not keep firing schedule-frame! into the frame hook.
  ;; The loop pumps the registry; here we pump by hand, so the assertion
  ;; is deterministic — no settle window, no wall-clock race.
  (let [c (be/make-bash-execution :command "sleep 10")
        fired (atom 0)]
    (core/render c 40)
    (macros/set-frame-hook! #(swap! fired inc))
    (try
      (t/is (zero? @fired) "nothing fired before a pump")
      (Thread/sleep 90)
      (timers/pump!)
      (t/is (pos? @fired) "driver fires while running")
      (protocols/dispose c)
      (t/is (nil? @(:ticker-id-atom c)) "dispose cleared the driver")
      (let [n @fired]
        (timers/pump!)
        (t/is (= n @fired) "no frames scheduled after dispose"))
      (finally
        (macros/set-frame-hook! nil)))))

(t/deftest test-append-output-schedules-frame
  ;; The on-chunk path retired its manual request-render — the state swap
  ;; must dirty the root's reaction, which schedules the frame (edge-triggered:
  ;; the first swap schedules; further swaps coalesce until the frame flush
  ;; re-arms, exactly like chat-history's streaming appends). Requires a
  ;; rendered-once component so the reaction's watches are installed. The
  ;; 80ms driver is cancelled first so its ticks can't race the counts.
  (let [c (be/make-bash-execution :command "sleep 10")
        fired (atom 0)]
    (core/render c 40)
    (when-let [driver @(:ticker-id-atom c)]
      (timers/cancel! driver)
      (reset! (:ticker-id-atom c) nil))
    (macros/set-frame-hook! #(swap! fired inc))
    (try
      (be/bash-execution-append-output! c "hello\n")
      (t/is (= 1 @fired) "first chunk scheduled the frame")
      (be/bash-execution-append-output! c "world\n")
      (t/is (= 1 @fired) "coalesced chunk scheduled no extra frame")
      (let [lines (core/render c 40)]
        (t/is (some #(clojure.string/includes? % "hello") lines))
        (t/is (some #(clojure.string/includes? % "world") lines)))
      (finally
        (macros/set-frame-hook! nil)
        (be/bash-execution-set-complete! c 0 false)
        (protocols/dispose c)))))
