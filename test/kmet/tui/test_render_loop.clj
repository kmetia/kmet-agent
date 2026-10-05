(ns kmet.tui.test-render-loop
  "Render-loop tests over a virtual terminal (a protocol stub, 80x24, every
   write recorded — no JLine, no real tty). Covers the full-redraw clearing
   contract:

   - a full redraw re-emits the whole transcript, so it must clear the
     scrollback too (\\u001b[3J) or the re-emit appends after the old
     history and duplicates it (pi issue #6050);
   - the first render writes without clearing, preserving prior terminal
     output above the TUI;
   - forced renders — what tui-resume! (external editor) and the
     scrollback heal (tui-heal-scrollback!) do — take the clearing
     full-redraw path;
   - a change above the window does NOT clear or re-emit the transcript:
     a same-height change entirely above it emits nothing, and one that also
     touches visible lines is clamped to the window top and repainted in
     place (so Termux / Windows Terminal no longer jump to the top on
     ESC[3J). A shrink starting above the window keeps the clearing
     full-redraw path (the diff renderer's extra-line cleanup assumes the
     change began inside the window).
   - leaving those lines un-repainted marks the scrollback dirty
     (tui-scrollback-dirty?); tui-heal-scrollback! rebuilds it with one
     clearing full redraw. The heal is latched behind the first frame that
     starts after the request and re-checks the dirt there, so a call made
     in the same instant as the dirtying change (the app heals at turn
     boundaries, before the loop has diffed them) is never lost — and the
     latch survives one renderer settle frame (a renderer that corrects its
     state during the pass schedules its own follow-up frame, where the dirt
     lands).

   The render loop is driven headlessly through the private
   run-render-loop!, mirroring pi's VirtualTerminal-based render tests."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.core :as core]
            [kmet.tui.terminal :as term]
            [kmet.tui.utils :as utils]
            [kmet.app.ui.tool-execution :as te]))

(def ^:private clear-seq
  "The clear sequence a clearing full redraw must emit: erase screen, home,
   erase scrollback."
  "\u001b[2J\u001b[H\u001b[3J")

(def ^:private sync-on
  "Begin-synchronized-output marker that opens every frame write."
  "\u001b[?2026h")

(defn- count-occurrences
  "Count non-overlapping occurrences of NEEDLE in S."
  [s needle]
  (loop [i 0 n 0]
    (if-let [j (str/index-of s needle i)]
      (recur (+ j (count needle)) (inc n))
      n)))

;; ─── Virtual terminal ──────────────────────────────────────────────────────

(defrecord VirtualTerminal [size writes]
  term/ITerminal
  (start! [this _ _] this)
  (stop! [_] nil)
  (started? [_] true)
  (write-output [_ s] (swap! writes conj s))
  (read-input [_ _] -1)
  (columns [_] (:cols @size))
  (rows [_] (:rows @size))
  (set-progress! [_ _] nil))

(defrecord LockProbeTerminal [size writes lock-held]
  term/ITerminal
  (start! [this _ _] this)
  (stop! [_] nil)
  (started? [_] true)
  (write-output [_ s]
    (swap! lock-held conj (.isHeldByCurrentThread @(var core/dispatch-lock)))
    (swap! writes conj s))
  (read-input [_ _] -1)
  (columns [_] (:cols @size))
  (rows [_] (:rows @size))
  (set-progress! [_ _] nil))

(defn- make-virtual-terminal
  "A virtual terminal: protocol stub at 80x24 with every write recorded in
   an atom and a mutable size (resize the test by swapping the size atom)."
  []
  (let [writes (atom [])
        size (atom {:cols 80 :rows 24})]
    {:terminal (map->VirtualTerminal {:size size :writes writes})
     :size size
     :writes writes}))

(defn- test-component
  "IComponent rendering the strings in LINES (an atom)."
  [lines]
  (reify core/IComponent
    (render [_ _] @lines)
    (handle-input [_ _] nil)
    (invalidate [_] nil)))

(defn- gated-test-component
  "Like test-component, but the FIRST render snapshots LINES and blocks on
   GATE before returning the snapshot; later renders return the current
   LINES. Holds a frame in flight after it has read the document, so a test
   can make state changes and heal while that frame is still running (GATE
   signals that the snapshot is taken and the frame is about to block on
   RELEASE)."
  [lines gate release]
  (let [first? (atom true)]
    (reify core/IComponent
      (render [_ _]
        (if (compare-and-set! first? true false)
          (let [snapshot @lines]
            (deliver gate true)
            (deref release)
            snapshot)
          @lines))
      (handle-input [_ _] nil)
      (invalidate [_] nil))))

(defn- settling-test-component
  "Like test-component, but the first render after ARM is set snapshots LINES,
   then applies the state change and requests the follow-up frame — the
   one-frame renderer settle (a renderer that corrects its state during the
   pass, e.g. render-edit-result's preview correction via :invalidate). The
   snapshot frame itself stays clean; the follow-up frame records the change."
  [lines arm tui]
  (reify core/IComponent
    (render [_ _]
      (let [snapshot @lines]
        (when (compare-and-set! arm true false)
          (swap! lines assoc 2 "line 2 CHANGED")
          ;; the settle's invalidate schedules the follow-up frame
          (core/tui-request-render tui))
        snapshot))
    (handle-input [_ _] nil)
    (invalidate [_] nil)))

;; ─── Render-loop driver ────────────────────────────────────────────────────

(defn- start-loop
  "Run the private render loop in a background thread."
  [tui]
  (reset! (:running? tui) true)
  (reset! (:stopped? tui) false)
  (reset! (:render-loop tui) (future ((var core/run-render-loop!) tui)))
  tui)

(defn- stop-loop
  "Stop the render loop and join its thread (must run even on failure)."
  [tui]
  (when @(:running? tui)
    (core/tui-stop tui))
  (when-let [f @(:render-loop tui)]
    (try
      (deref f 3000 nil)
      (catch Throwable t
        (println "render loop ended with error:" t)))
    (reset! (:render-loop tui) nil)))

(defn- frame-writes
  "The per-frame write strings (every frame opens with the sync marker)."
  [writes]
  (filterv #(str/includes? % sync-on) @writes))

(defn- wait-for-frames
  "Block until N frames are written or TIMEOUT-MS elapses."
  [writes n timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (when (and (< (System/currentTimeMillis) deadline)
                 (< (count (frame-writes writes)) n))
        (Thread/sleep 5)
        (recur)))
    (t/is (<= n (count (frame-writes writes)))
          (str "expected " n " rendered frame(s), saw " (count (frame-writes writes))))))

(defn- wait-until
  "Block until PRED is truthy or TIMEOUT-MS elapses; returns whether it became truthy."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 10) (recur))
        :else false))))

;; ─── Tests ─────────────────────────────────────────────────────────────────

(deftest ^:slow first-render-preserves-terminal-output
  (testing "the first frame writes without clearing (prior terminal output above the TUI survives)"
    (let [vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component (atom ["alpha" "beta"])))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (let [frame (first (frame-writes (:writes vt)))]
          (t/is (some? frame) "a frame was rendered")
          (t/is (not (str/includes? frame "\u001b[2J")) "no screen clear on first render")
          (t/is (not (str/includes? frame "\u001b[3J")) "no scrollback clear on first render")
          (t/is (str/includes? frame "alpha") "transcript written")
          (t/is (str/includes? frame "beta") "transcript written"))
        (finally
          (stop-loop tui))))))

(deftest ^:slow terminal-release-parks-the-cursor-below-the-frame
  (testing "a final stop and a suspend both end with a line break below the
            last rendered line (pi: main-screen beforeTerminalStop runs on
            every ui.stop()), so the shell's prompt and job-control message
            land below the footer instead of overwriting it"
    (doseq [[label release] [["stop" core/tui-stop]
                             ["suspend" core/tui-suspend!]]]
      (let [vt (make-virtual-terminal)
            tui (core/create-tui (:terminal vt))]
        (try
          (core/tui-add-child tui (test-component (atom ["alpha" "beta"])))
          (start-loop tui)
          (wait-for-frames (:writes vt) 1 5000)
          (let [loop-fut @(:render-loop tui)
                before (count @(:writes vt))]
            (release tui)
            (t/is (not= ::timeout (deref loop-fut 5000 ::timeout))
                  (str label ": the render loop released the terminal"))
            (let [after (subvec @(:writes vt) before)]
              (t/is (some #(= "\r\n" %) after)
                    (str label ": ends the frame with a line break"))
              (t/is (= "\u001b[?25h" (peek @(:writes vt)))
                    (str label ": the cursor is shown after the line break"))))
          (finally
            (stop-loop tui)))))))

(deftest ^:slow emitted-lines-carry-segment-reset
  (testing "every emitted non-image content line ends with SEGMENT_RESET
            (pi: applyLineResets) — applied at emit time, on both the full-redraw
            and the diff-rewrite paths"
    (let [lines (atom ["alpha" "beta" "gamma"])
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))
          reset utils/SEGMENT-RESET]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (let [frame (first (frame-writes (:writes vt)))]
          (t/is (str/includes? frame (str "alpha" reset))
                "full redraw: a content line carries the reset")
          (t/is (str/includes? frame (str "gamma" reset))
                "full redraw: the last line carries the reset"))
        ;; a diff rewrite must carry it too (the reset was re-appended after
        ;; the 2K clear that erased the old line)
        (swap! lines assoc 1 "beta CHANGED")
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 5000)
        (let [redraw (second (frame-writes (:writes vt)))]
          (t/is (str/includes? redraw (str "beta CHANGED" reset))
                "diff rewrite: the rewritten line carries the reset"))
        (finally
          (stop-loop tui))))))

(deftest ^:slow forced-full-redraw-clears-scrollback
  (testing "a forced render (tui-resume! / Ctrl+L) clears screen AND scrollback before re-emitting"
    (let [vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component (atom ["alpha" "beta" "gamma"])))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (core/tui-request-render tui true)
        (wait-for-frames (:writes vt) 2 5000)
        (let [redraw (second (frame-writes (:writes vt)))
              clear-idx (str/index-of redraw clear-seq)
              alpha-idx (str/index-of redraw "alpha")]
          (t/is (some? clear-idx) "forced full redraw emits 2J H 3J (screen + scrollback)")
          (when clear-idx
            (t/is (and alpha-idx (> alpha-idx clear-idx))
                  "transcript re-emitted after the clear — no duplication"))
          (t/is (= 1 (count-occurrences (apply str @(:writes vt)) clear-seq))
                "the clear sequence appears exactly once (only in the forced frame)")
          (t/is (= 80 @(:previous-width tui)) "diff state consistent after the forced redraw"))
        (finally
          (stop-loop tui))))))

(deftest ^:slow scrollback-only-change-does-not-clear
  (testing "a change entirely above the viewport updates state without emitting output —
            no clearing full redraw, so Termux/Windows Terminal no longer yank the viewport to
            the top (firstChanged < viewportTop, lastChanged < viewportTop)"
    (let [lines (atom (vec (map #(str "line " %) (range 30))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        ;; 30 lines on a 24-row screen → viewport top at row 6; change row 2
        (let [writes-before (count @(:writes vt))]
          (swap! lines assoc 2 "line 2 CHANGED")
          (core/tui-request-render tui)
          ;; The skip path emits NOTHING, so there is no frame to wait for;
          ;; poll the state the loop updates.
          (t/is (wait-until #(str/includes? (nth @(:previous-lines tui) 2) "line 2 CHANGED") 5000)
                "the frame processed and internal state updated")
          (let [emitted (apply str (drop writes-before @(:writes vt)))]
            (t/is (not (str/includes? emitted clear-seq))
                  "no screen/scrollback clear for a scrollback-only change")
            (t/is (not (str/includes? emitted "line 2 CHANGED"))
                  "the stale scrollback line is not re-emitted (nothing visible changed)")
            (t/is (not (str/includes? emitted "line 29"))
                  "the visible window is not repainted (it did not change)"))
          (t/is (true? (core/tui-scrollback-dirty? tui))
                "a scrollback-only change marks the scrollback dirty for a later heal"))
        (finally
          (stop-loop tui))))))

(deftest ^:slow change-spanning-viewport-repaints-without-clearing
  (testing "when scrollback AND visible content change, the diff is clamped to the viewport top
            and only visible lines are repainted — no clear (firstChanged < viewportTop ≤ lastChanged)"
    (let [lines (atom (vec (map #(str "line " %) (range 30))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        ;; change row 2 (scrollback) and row 29 (visible)
        (swap! lines assoc 2 "line 2 CHANGED" 29 "line 29 CHANGED")
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 5000)
        (let [redraw (second (frame-writes (:writes vt)))]
          (t/is (not (str/includes? redraw clear-seq))
                "no screen/scrollback clear when clamping to the viewport")
          (t/is (str/includes? redraw "line 29 CHANGED") "the visible change is repainted")
          (t/is (str/includes? redraw "line 6") "repaint starts at the viewport top")
          (t/is (not (str/includes? redraw "line 2 CHANGED"))
                "nothing above the viewport top is repainted"))
        (t/is (true? (core/tui-scrollback-dirty? tui))
              "a clamped above-window change also marks the scrollback dirty")
        (finally
          (stop-loop tui))))))

(deftest ^:slow scrollback-dirty-heals-on-demand
  (testing "tui-heal-scrollback! rebuilds a dirty scrollback with ONE clearing full redraw,
            re-emitting the current content and clearing the dirty flag — the app calls it at a
            streaming-free turn boundary"
    (let [lines (atom (vec (map #(str "line " %) (range 30))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (t/is (false? (core/tui-scrollback-dirty? tui)) "clean after the first render")
        ;; dirty it with a scrollback-only change
        (swap! lines assoc 2 "line 2 CHANGED")
        (core/tui-request-render tui)
        (t/is (wait-until #(core/tui-scrollback-dirty? tui) 5000)
              "the above-window change marks the scrollback dirty")
        (let [frames-before (count (frame-writes (:writes vt)))]
          (core/tui-heal-scrollback! tui)
          (wait-for-frames (:writes vt) (inc frames-before) 5000)
          (let [redraw (last (frame-writes (:writes vt)))]
            (t/is (str/includes? redraw clear-seq)
                  "the heal emits the clearing full redraw (2J H 3J)")
            (t/is (str/includes? redraw "line 2 CHANGED")
                  "the healed scrollback re-emits the current content")
            (t/is (str/includes? redraw "line 29") "the whole transcript is re-emitted")))
        (t/is (false? (core/tui-scrollback-dirty? tui)) "clean again after the heal")
        (finally
          (stop-loop tui))))))

(deftest ^:slow scrollback-heal-before-dirty-frame-still-heals
  (testing "tui-heal-scrollback! called in the same instant as the change — before the
            render loop has diffed it — latches the request and still rebuilds the stale
            scrollback. This is the fast-tool race: the heal used to check the not-yet-set
            dirty flag, no-op, and leave the stale lines until the next trigger."
    (let [lines (atom (vec (map #(str "line " %) (range 30))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (let [frames-before (count (frame-writes (:writes vt)))
              lock @(var core/dispatch-lock)]
          ;; hold the frame pass off between the change and the latch check so
          ;; the pending assertion is deterministic, not a race with the loop
          (.lock lock)
          (try
            ;; the boundary's change and its heal land together: the heal must
            ;; not rely on the dirty flag, which no frame has set yet
            (swap! lines assoc 2 "line 2 CHANGED")
            (core/tui-heal-scrollback! tui)
            (t/is (true? (core/tui-scrollback-heal-pending? tui))
                  "the heal is latched, not dropped by the not-yet-dirty flag")
            (finally (.unlock lock)))
          (t/is (wait-until #(some (fn [w] (str/includes? w clear-seq))
                                   (drop frames-before (frame-writes (:writes vt))))
                            5000)
                "the clearing rebuild fires after the frame that records the dirt")
          (t/is (false? (core/tui-scrollback-dirty? tui))
                "the stale scrollback is healed, not left for the next trigger")
          (t/is (false? (core/tui-scrollback-heal-pending? tui))
                "the latch cleared")
          (let [redraw (last (filterv #(str/includes? % clear-seq)
                                      (drop frames-before (frame-writes (:writes vt)))))]
            (t/is (str/includes? redraw "line 2 CHANGED")
                  "the healed rebuild re-emits the current content")))
        (finally
          (stop-loop tui))))))

(deftest ^:slow scrollback-heal-latch-survives-an-in-flight-frame
  (testing "a heal requested while a frame is in flight — after that frame had already
            read the document — is not cleared by the in-flight frame; the frame that
            starts after the request still records the dirt and forces the rebuild"
    (let [lines (atom (vec (map #(str "line " %) (range 30))))
          gate (promise)
          release (promise)
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (gated-test-component lines gate release))
        (start-loop tui)
        (t/is (true? (deref gate 5000 false))
              "the first frame is in flight, blocked after reading the document")
        ;; the boundary's change and heal land while frame 1 is in flight
        (swap! lines assoc 2 "line 2 CHANGED")
        (core/tui-request-render tui)
        (core/tui-heal-scrollback! tui)
        (t/is (false? (core/tui-scrollback-dirty? tui))
              "the in-flight frame has not diffed the change yet")
        (t/is (true? (core/tui-scrollback-heal-pending? tui))
              "the request latches for the frame that starts after it")
        ;; release frame 1: it returns the OLD snapshot and must not clear the
        ;; latch (it started before the heal request)
        (deliver release true)
        (t/is (wait-until #(some (fn [w] (str/includes? w clear-seq))
                                 (frame-writes (:writes vt)))
                          3000)
              "the heal still fired once the post-request frame recorded the dirt")
        (t/is (false? (core/tui-scrollback-dirty? tui))
              "the stale scrollback was rebuilt, not left pending")
        (t/is (false? (core/tui-scrollback-heal-pending? tui))
              "the latch cleared only after the heal")
        (finally
          (deliver gate true) ;; never leave the render loop blocked
          (deliver release true)
          (stop-loop tui))))))

(deftest ^:slow scrollback-heal-survives-a-renderer-settle-frame
  (testing "a renderer that corrects its state during the pass (the tool result's
            preview correction calls :invalidate from inside the render) records its
            above-window change in the frame that STARTS AFTER the qualifying one; the
            dirt still belongs to the heal's streaming-free boundary and is rebuilt,
            not dropped when the clean qualifying frame ends"
    (let [lines (atom (vec (map #(str "line " %) (range 30))))
          arm (atom false)
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (settling-test-component lines arm tui))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (let [frames-before (count (frame-writes (:writes vt)))]
          (reset! arm true)
          (core/tui-heal-scrollback! tui)
          ;; the qualifying frame renders the OLD snapshot and schedules its own
          ;; settle frame; the latch must survive that clean qualifying frame
          (t/is (wait-until #(some (fn [w] (str/includes? w clear-seq))
                                   (drop frames-before (frame-writes (:writes vt))))
                            5000)
                "the settle frame's above-window dirt forces the clearing rebuild")
          (t/is (false? (core/tui-scrollback-dirty? tui))
                "the settled scrollback is healed, not left for the next trigger")
          (let [redraw (last (filterv #(str/includes? % clear-seq)
                                      (drop frames-before (frame-writes (:writes vt)))))]
            (t/is (str/includes? redraw "line 2 CHANGED")
                  "the rebuilt scrollback re-emits the settled content")))
        (finally
          (stop-loop tui))))))

(deftest ^:slow scrollback-heal-clean-is-a-no-op
  (testing "a heal on a clean scrollback latches but emits no clearing rebuild — the
            idle-input heal must not re-emit the transcript when nothing is stale"
    (let [lines (atom (vec (map #(str "line " %) (range 30))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (let [writes-before (count @(:writes vt))
              lock @(var core/dispatch-lock)]
          ;; hold the frame pass off between the request and the latch check so
          ;; the pending assertion is deterministic, not a race with the loop
          (.lock lock)
          (try
            (core/tui-heal-scrollback! tui)
            (t/is (true? (core/tui-scrollback-heal-pending? tui))
                  "the request latches")
            (finally (.unlock lock)))
          (t/is (wait-until #(false? (core/tui-scrollback-heal-pending? tui)) 5000)
                "the frame after the request drops the clean latch")
          (t/is (not (str/includes? (apply str (drop writes-before @(:writes vt))) clear-seq))
                "no clearing redraw for a scrollback that stayed clean"))
        (finally
          (stop-loop tui))))))

(deftest ^:slow scrollback-change-with-append-clamps-and-scrolls
  (testing "a change above the window plus appended content repaints from the window top
            and scrolls to keep the document end visible — no clear"
    (let [lines (atom (vec (map #(str "line " %) (range 30))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        ;; change row 2 (scrollback) and append rows 30..40
        (swap! lines (fn [v]
                       (into (assoc v 2 "line 2 CHANGED")
                             (mapv #(str "line " %) (range 30 41)))))
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 5000)
        (let [redraw (second (frame-writes (:writes vt)))]
          (t/is (not (str/includes? redraw clear-seq))
                "no screen/scrollback clear when clamping to the viewport")
          (t/is (str/includes? redraw "line 40") "the appended tail is rendered")
          (t/is (not (str/includes? redraw "line 2 CHANGED"))
                "nothing above the viewport top is repainted"))
        (finally
          (stop-loop tui))))))

(deftest ^:slow shrink-above-window-with-unchanged-tail-does-not-repaint
  (testing "a shrink whose removed lines are all above the window and whose visible tail is
            unchanged paints nothing: the screen already shows the right rows, so the diff
            emits no output — no clearing full redraw mid-stream — and the stale scrollback
            above is marked dirty for the app's streaming-free heal"
    (let [lines (atom (vec (map #(str "line " %) (range 40))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        ;; 40 lines on a 24-row screen → viewport top at row 16; drop lines
        ;; 2-5 (all above the window), keeping the visible tail identical
        (let [writes-before (count @(:writes vt))]
          (swap! lines (fn [v] (into (subvec v 0 2) (subvec v 6))))
          (core/tui-request-render tui)
          ;; the no-repaint path emits nothing, so there is no frame to wait
          ;; for; poll the state the loop updates
          (t/is (wait-until #(= 36 (count @(:previous-lines tui))) 5000)
                "the frame processed and internal state updated")
          (let [emitted (apply str (drop writes-before @(:writes vt)))]
            (t/is (not (str/includes? emitted clear-seq))
                  "no clearing full redraw for an above-window shrink")
            (t/is (not (str/includes? emitted "line 39"))
                  "the visible rows are not repainted (their content did not change)")
            (t/is (not (str/includes? emitted "line 2"))
                  "the stale scrollback line is not re-emitted"))
          (t/is (true? (core/tui-scrollback-dirty? tui))
                "the above-window shrink marks the scrollback dirty for the turn-end heal"))
        (finally
          (stop-loop tui)))))
  (testing "a shrink with a changed visible line keeps the full-redraw fallback: the freed
            rows are on screen then, and the diff renderer's extra-line cleanup assumes the
            change began inside the window"
    (let [lines (atom (vec (map #(str "line " %) (range 40))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (swap! lines (fn [v]
                       (-> (into (subvec v 0 2) (subvec v 6))
                           (assoc 35 "line 39 CHANGED"))))
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 5000)
        (let [redraw (second (frame-writes (:writes vt)))]
          (t/is (str/includes? redraw clear-seq)
                "a shrink with a visible change still rebuilds the screen")
          (t/is (str/includes? redraw "line 39 CHANGED")
                "the visible change is re-emitted by the rebuild"))
        (finally
          (stop-loop tui))))))

(deftest ^:slow shrink-starting-above-window-keeps-full-redraw
  (testing "a shrink starting above the window keeps the clearing full-redraw path
            (the diff renderer's extra-line cleanup assumes the change began inside the window)"
    (let [lines (atom (vec (map #(str "line " %) (range 30))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        ;; change row 2 (above the window) and truncate 30 → 10 lines
        (swap! lines (fn [v] (assoc (subvec v 0 10) 2 "line 2 CHANGED")))
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 5000)
        (let [redraw (second (frame-writes (:writes vt)))]
          (t/is (str/includes? redraw clear-seq)
                "a shrink above the window rebuilds the screen (clearing redraw)")
          (t/is (str/includes? redraw "line 9") "the rebuilt screen re-emits the content"))
        (finally
          (stop-loop tui))))))

(deftest ^:slow flash-during-shrink-stays-addressable
  (testing "a flash composited while the document shrinks is clamped to the viewport top the
            diff can address — no clearing full redraw (the /copy pattern: the command dropdown
            closes, shrinking the document by a line, and the 'Copied!' flash would otherwise
            land one row above the still-visible old window top and trip the shrink fallback)"
    (let [lines (atom (vec (map #(str "line " %) (range 30))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        ;; grow by one (the dropdown opens): the screen scrolls down, so the
        ;; addressable window top moves from line 6 to line 7
        (swap! lines conj "dropdown")
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 5000)
        ;; shrink back while the flash is up — the natural flash row (the new
        ;; window top, line 6) is now above what the screen shows (line 7)
        (swap! lines (fn [v] (subvec v 0 30)))
        (core/tui-flash! tui "Copied!" :duration-ms 60000)
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 3 5000)
        (let [redraw (nth (frame-writes (:writes vt)) 2)
              flash-line (some #(when (str/includes? % "Copied!") %)
                               (str/split-lines redraw))]
          (t/is (not (str/includes? redraw clear-seq))
                "no screen/scrollback clear — the flash must not read as an above-window change")
          (t/is (some? flash-line) "the flash is rendered")
          (t/is (str/includes? (or flash-line "") "line 7")
                "the flash is composited onto the addressable window top, not the row above it"))
        (finally
          (stop-loop tui))))))

(deftest ^:slow ordinary-diff-does-not-clear
  (testing "an in-viewport change takes the diff path — no clear, no scrollback wipe"
    (let [lines (atom ["alpha" "beta"])
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (swap! lines conj "gamma") ;; append — below the viewport
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 5000)
        (let [diff (second (frame-writes (:writes vt)))]
          (t/is (not (str/includes? diff "\u001b[2J")) "no screen clear on an ordinary diff")
          (t/is (not (str/includes? diff "\u001b[3J")) "no scrollback clear on an ordinary diff")
          (t/is (str/includes? diff "gamma") "new line written"))
        (t/is (false? (core/tui-scrollback-dirty? tui))
              "an in-window/append diff leaves the scrollback clean")
        (finally
          (stop-loop tui))))))

(deftest ^:slow terminal-resize-triggers-full-redraw
  (testing "a terminal resize re-renders without any input event: the JLine backend's
            native WINCH handler is dead under the GraalVM native image, so the loop must detect
            the size change itself and reflow (pi: terminal.on('resize') → requestRender).
            Without this the editor keeps wrapping at the pre-resize width."
    (let [lines (atom ["alpha" "beta"])
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))
          size (:size vt)]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (t/is (= 80 @(:previous-width tui)) "rendered at the initial 80 cols")
        ;; Resize the terminal WITHOUT requesting a render — the loop's own
        ;; size poll must notice and reflow.
        (swap! size assoc :cols 60)
        (wait-for-frames (:writes vt) 2 5000)
        (let [frame (second (frame-writes (:writes vt)))]
          (t/is (some? frame) "a new frame was rendered after the resize")
          (t/is (str/includes? frame clear-seq)
                "width change takes the clearing full-redraw path")
          (t/is (str/includes? frame "alpha") "transcript re-emitted at the new width"))
        (t/is (= 60 @(:previous-width tui)) "diff state updated to the new width")
        ;; Height-only change must also re-render (editor dynamic height depends
        ;; on rows): the loop detects it and renders. Whether a frame is written
        ;; depends on the diff (Termux: height changes take the diff path, so
        ;; unchanged content emits nothing) — the render itself is observable
        ;; via the diff-state update.
        (swap! size assoc :rows 30)
        (let [deadline (+ (System/currentTimeMillis) 5000)]
          (loop []
            (when (and (< (System/currentTimeMillis) deadline)
                       (not= 30 @(:previous-height tui)))
              (Thread/sleep 5)
              (recur))))
        (t/is (= 30 @(:previous-height tui)) "height change detected and rendered")
        (finally
          (stop-loop tui))))))

(deftest ^:slow terminal-size-ref-publishes-and-updates-on-resize
  (testing "the live size ref carries every size change, published before
            the frame that renders it"
    (let [vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))
          size-ref (core/tui-terminal-size-ref tui)]
      (try
        (t/is (nil? (core/tui-terminal-size tui)) "nothing published before the first frame")
        (core/tui-add-child tui (test-component (atom ["alpha"])))
        (start-loop tui)
        (t/is (wait-until #(= {:cols 80 :rows 24} @size-ref) 5000)
              "the first frame publishes the size")
        (swap! (:size vt) assoc :rows 16)
        (t/is (wait-until #(= {:cols 80 :rows 16} @size-ref) 5000)
              "a resize updates the ref")
        (finally
          (stop-loop tui))))))

;; ─── Render vs input serialization (dispatch-lock) ─────────────────────────
;; The render loop and the input reader mutate/read component state on
;; separate threads; pi is single-threaded and has no interleaving. The
;; dispatch-lock mutex is what makes a frame's props application and a
;; keystroke non-overlapping (the /scoped-models backspace freeze).

(deftest ^:slow render-pass-holds-the-dispatch-lock
  (testing "the frame pass runs under dispatch-lock — the same mutex input
            dispatch takes — so no keystroke can interleave with it"
    (let [held (atom [])
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))
          lock @(var core/dispatch-lock)]
      (try
        (core/tui-add-child
         tui
         (reify core/IComponent
           (render [_ _]
             (swap! held conj (.isHeldByCurrentThread lock))
             ["frame"])
           (handle-input [_ _] nil)
           (invalidate [_] nil)))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (t/is (seq @held) "the component rendered")
        (t/is (every? true? @held)
              "every render ran with dispatch-lock held by the loop thread")
        (finally
          (stop-loop tui))))))

(deftest ^:slow input-dispatch-waits-for-an-in-flight-frame
  (testing "a key dispatched from another thread blocks on dispatch-lock while
            the frame pass is running (pi: single-threaded, no interleaving)"
    (let [entered (promise)
          release (promise)
          frames (atom 0)
          handled (atom [])
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))
          lock @(var core/dispatch-lock)
          comp (reify core/IComponent
                 (render [_ _]
                   (when (= 1 (swap! frames inc))
                     (deliver entered true)
                     (deref release))
                   ["frame"])
                 (handle-input [_ data] (swap! handled conj data))
                 (invalidate [_] nil))]
      (try
        (core/tui-add-child tui comp)
        (core/tui-set-focus tui comp)
        (start-loop tui)
        (t/is (deref entered 5000 false) "the first frame started")
        (t/is (some? @(:focused-component tui)) "the component is focused")
        ;; the loop holds the lock for the in-flight frame
        (let [locked? (.tryLock lock 100 java.util.concurrent.TimeUnit/MILLISECONDS)]
          (when locked? (.unlock lock))
          (t/is (false? locked?) "dispatch-lock is held while rendering"))
        ;; an input pass from another thread cannot get in
        (let [buf (atom "x")
              attempted (promise)
              input-future (future
                             (deliver attempted true)
                             ((var core/process-input-buffer!) tui nil buf))]
          (t/is (deref attempted 1000 false) "the input pass reached dispatch")
          (Thread/sleep 150)
          (t/is (empty? @handled)
                "the key waits for the in-flight frame, not the other way around")
          (deliver release true)
          (t/is (wait-until #(seq @handled) 5000) "the key dispatches once the frame releases")
          (t/is (not= ::timeout (deref input-future 5000 ::timeout))
                "the input pass finished"))
        (finally
          (deliver release true)
          (stop-loop tui))))))

(deftest ^:slow suspend-while-holding-the-dispatch-lock
  (testing "tui-suspend! called while holding dispatch-lock (the reader's
            external-editor path) still lets the loop exit — a plain
            blocking acquire would deadlock its join of the render loop"
    (let [vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))
          lock @(var core/dispatch-lock)]
      (try
        (core/tui-add-child tui (test-component (atom ["frame"])))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (let [loop-fut @(:render-loop tui)]
          ;; the reader dispatches under this lock; suspend joins the loop
          (.lock lock)
          ;; wake the loop into its next acquisition and let it block there
          ;; (a plain blocking acquire would now wait on the lock this thread
          ;; holds, deadlocking the join below)
          (core/tui-request-render tui)
          (Thread/sleep 100)
          (core/tui-suspend! tui)
          ;; still holding the lock: a plain blocking acquire could not have
          ;; exited, so the exit must have come from the running? recheck
          (t/is (wait-until #(realized? loop-fut) 5000)
                "the loop backed out of the acquisition instead of waiting out
                 the join timeout"))
        (finally
          (.unlock lock)
          (stop-loop tui))))))

(deftest ^:slow terminal-write-runs-outside-the-dispatch-lock
  (testing "the frame's bytes are written after dispatch-lock is released —
            a stalled terminal must not stall input dispatch"
    (let [writes (atom [])
          lock-held (atom [])
          tui (core/create-tui (map->LockProbeTerminal
                                {:size (atom {:cols 80 :rows 24})
                                 :writes writes
                                 :lock-held lock-held}))]
      (try
        (core/tui-add-child tui (test-component (atom ["frame"])))
        (start-loop tui)
        (wait-for-frames writes 1 5000)
        (t/is (seq @lock-held) "the frame was written")
        (t/is (every? false? @lock-held)
              "every terminal write ran with dispatch-lock free")
        (finally
          (stop-loop tui))))))

(deftest ^:slow cursor-marker-never-reaches-the-terminal
  (testing "a CURSOR-MARKER line above the viewport is stripped, not emitted:
            the marker is an APC (ESC _ ... BEL) that strict terminals consume
            through the next ST, swallowing every following frame — the
            /scoped-models freeze on short terminals"
    (let [marker utils/CURSOR-MARKER
          ;; 30 lines at 24 rows: the marker line (index 0) is above the
          ;; bottom-24 scan window, so the viewport scan cannot see it — and
          ;; it must still be stripped from the emitted frame
          lines (atom (into [(str "top" marker "line")]
                            (map #(str "line-" %) (range 29))))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))]
      (try
        (core/tui-add-child tui (test-component lines))
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        (t/is (not-any? #(str/includes? % marker) @(:writes vt))
              "no emitted frame contains the marker's APC sequence")
        ;; and again on a diff pass: only line 15 changes, so the marker line
        ;; (index 0) rides along in previous-lines and must stay stripped
        (swap! lines assoc 15 "line-CHANGED")
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 5000)
        (t/is (not-any? #(str/includes? % marker)
                        (second (frame-writes (:writes vt))))
              "neither does the diff frame")
        (finally
          (stop-loop tui))))))

(deftest ^:slow tall-tool-settle-keeps-scrollback-clean
  (testing "option 1 (state on the tail): a tall live tool settles into a
            tail state line — the box body is constant, so the settle can
            never repaint a line above the window and the scrollback stays
            clean (regression: the whole-box pending→success bg flip latched
            a heal at every such tool end)"
    (let [cmd (str/join "\n" (repeat 40 "echo x"))
          vt (make-virtual-terminal)
          tui (core/create-tui (:terminal vt))
          tool (te/make-tool-execution :name "bash" :args {:command cmd} :status true)]
      (try
        (core/tui-add-child tui (test-component
                                 (atom (mapv #(str "history " %) (range 40)))))
        (core/tui-add-child tui tool)
        (te/tool-execution-mark-execution-started! tool)
        (start-loop tui)
        (wait-for-frames (:writes vt) 1 5000)
        ;; settle: content + ended-at change the state line only
        (reset! (:content-atom tool) "ok")
        (te/tool-execution-set-error! tool false)
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 5000)
        (t/is (false? (core/tui-scrollback-dirty? tui))
              "the settle left the scrollback clean (tail line only)")
        (t/is (some #(str/includes? % "Took") @(:writes vt))
              "the tail line rendered the final state")
        (finally
          (stop-loop tui))))))
