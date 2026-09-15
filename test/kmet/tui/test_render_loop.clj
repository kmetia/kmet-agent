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
     clearing full redraw (the app calls it at the start of a turn).

   The render loop is driven headlessly through the private
   run-render-loop!, mirroring pi's VirtualTerminal-based render tests."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.core :as core]
            [kmet.tui.terminal :as term]
            [kmet.tui.utils :as utils]))

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
        (wait-for-frames (:writes vt) 1 2000)
        (let [frame (first (frame-writes (:writes vt)))]
          (t/is (some? frame) "a frame was rendered")
          (t/is (not (str/includes? frame "\u001b[2J")) "no screen clear on first render")
          (t/is (not (str/includes? frame "\u001b[3J")) "no scrollback clear on first render")
          (t/is (str/includes? frame "alpha") "transcript written")
          (t/is (str/includes? frame "beta") "transcript written"))
        (finally
          (stop-loop tui))))))

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
        (wait-for-frames (:writes vt) 1 2000)
        (let [frame (first (frame-writes (:writes vt)))]
          (t/is (str/includes? frame (str "alpha" reset))
                "full redraw: a content line carries the reset")
          (t/is (str/includes? frame (str "gamma" reset))
                "full redraw: the last line carries the reset"))
        ;; a diff rewrite must carry it too (the reset was re-appended after
        ;; the 2K clear that erased the old line)
        (swap! lines assoc 1 "beta CHANGED")
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 2000)
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
        (wait-for-frames (:writes vt) 1 2000)
        (core/tui-request-render tui true)
        (wait-for-frames (:writes vt) 2 2000)
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
        (wait-for-frames (:writes vt) 1 2000)
        ;; 30 lines on a 24-row screen → viewport top at row 6; change row 2
        (let [writes-before (count @(:writes vt))]
          (swap! lines assoc 2 "line 2 CHANGED")
          (core/tui-request-render tui)
          ;; The skip path emits NOTHING, so there is no frame to wait for;
          ;; poll the state the loop updates.
          (t/is (wait-until #(str/includes? (nth @(:previous-lines tui) 2) "line 2 CHANGED") 2000)
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
        (wait-for-frames (:writes vt) 1 2000)
        ;; change row 2 (scrollback) and row 29 (visible)
        (swap! lines assoc 2 "line 2 CHANGED" 29 "line 29 CHANGED")
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 2000)
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
        (wait-for-frames (:writes vt) 1 2000)
        (t/is (false? (core/tui-scrollback-dirty? tui)) "clean after the first render")
        ;; dirty it with a scrollback-only change
        (swap! lines assoc 2 "line 2 CHANGED")
        (core/tui-request-render tui)
        (t/is (wait-until #(core/tui-scrollback-dirty? tui) 2000)
              "the above-window change marks the scrollback dirty")
        (let [frames-before (count (frame-writes (:writes vt)))]
          (core/tui-heal-scrollback! tui)
          (wait-for-frames (:writes vt) (inc frames-before) 2000)
          (let [redraw (last (frame-writes (:writes vt)))]
            (t/is (str/includes? redraw clear-seq)
                  "the heal emits the clearing full redraw (2J H 3J)")
            (t/is (str/includes? redraw "line 2 CHANGED")
                  "the healed scrollback re-emits the current content")
            (t/is (str/includes? redraw "line 29") "the whole transcript is re-emitted")))
        (t/is (false? (core/tui-scrollback-dirty? tui)) "clean again after the heal")
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
        (wait-for-frames (:writes vt) 1 2000)
        ;; change row 2 (scrollback) and append rows 30..40
        (swap! lines (fn [v]
                       (into (assoc v 2 "line 2 CHANGED")
                             (mapv #(str "line " %) (range 30 41)))))
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 2000)
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
        (wait-for-frames (:writes vt) 1 2000)
        ;; 40 lines on a 24-row screen → viewport top at row 16; drop lines
        ;; 2-5 (all above the window), keeping the visible tail identical
        (let [writes-before (count @(:writes vt))]
          (swap! lines (fn [v] (into (subvec v 0 2) (subvec v 6))))
          (core/tui-request-render tui)
          ;; the no-repaint path emits nothing, so there is no frame to wait
          ;; for; poll the state the loop updates
          (t/is (wait-until #(= 36 (count @(:previous-lines tui))) 2000)
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
        (wait-for-frames (:writes vt) 1 2000)
        (swap! lines (fn [v]
                       (-> (into (subvec v 0 2) (subvec v 6))
                           (assoc 35 "line 39 CHANGED"))))
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 2000)
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
        (wait-for-frames (:writes vt) 1 2000)
        ;; change row 2 (above the window) and truncate 30 → 10 lines
        (swap! lines (fn [v] (assoc (subvec v 0 10) 2 "line 2 CHANGED")))
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 2000)
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
        (wait-for-frames (:writes vt) 1 2000)
        ;; grow by one (the dropdown opens): the screen scrolls down, so the
        ;; addressable window top moves from line 6 to line 7
        (swap! lines conj "dropdown")
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 2000)
        ;; shrink back while the flash is up — the natural flash row (the new
        ;; window top, line 6) is now above what the screen shows (line 7)
        (swap! lines (fn [v] (subvec v 0 30)))
        (core/tui-flash! tui "Copied!" :duration-ms 60000)
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 3 2000)
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
        (wait-for-frames (:writes vt) 1 2000)
        (swap! lines conj "gamma") ;; append — below the viewport
        (core/tui-request-render tui)
        (wait-for-frames (:writes vt) 2 2000)
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
        (wait-for-frames (:writes vt) 1 2000)
        (t/is (= 80 @(:previous-width tui)) "rendered at the initial 80 cols")
        ;; Resize the terminal WITHOUT requesting a render — the loop's own
        ;; size poll must notice and reflow.
        (swap! size assoc :cols 60)
        (wait-for-frames (:writes vt) 2 2000)
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
        (let [deadline (+ (System/currentTimeMillis) 2000)]
          (loop []
            (when (and (< (System/currentTimeMillis) deadline)
                       (not= 30 @(:previous-height tui)))
              (Thread/sleep 5)
              (recur))))
        (t/is (= 30 @(:previous-height tui)) "height change detected and rendered")
        (finally
          (stop-loop tui))))))
