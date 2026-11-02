(ns kmet.tui.frame
  "One frame of the render loop: normalize the composed document, reuse the
   lines that did not change, diff the result against the previous frame, and
   stage the terminal bytes. The loop (kmet.tui.core) owns the terminal
   lifecycle, timers, the dispatch lock and the frame schedule; this namespace
   owns what one rendered frame is, so the passes are testable without a
   terminal."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.libs.terminal-image :as img]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as terminal]
            [kmet.tui.utils :as utils]))

;; --- Sync markers (pi: begin/end synchronized output)

(def ^:private CSI-2026-H terminal/CSI-2026-SYNC-ON)
(def ^:private CSI-2026-L terminal/CSI-2026-SYNC-OFF)

;; ─── Cursor and the document pass

(defn- removed-range
  "The [first last] indices of the non-empty lines that PREV-OUT lost
   between N and PREV-COUNT, or [nil nil] when every removed line was
   empty. The diff's missing side is \"\", so an empty removal repaints
   nothing; a non-empty one must stay in the frame's changed range, since
   that range replaces the diff's own scan."
  [prev-out n prev-count]
  (loop [i n, f nil, l nil]
    (if (< i prev-count)
      (if (= "" (nth prev-out i))
        (recur (inc i) f l)
        (recur (inc i) (or f i) i))
      [f l])))

(defn- line-cursor
  "The cursor {:row :col} for the bottom-most marker among the bottom HEIGHT
   lines of an N-line document, or nil when no marker is visible. The row is
   the marker's absolute document line (the caller subtracts the viewport);
   the column is the visible width of the line's prefix before the marker."
  [marks lines n height]
  (let [viewport-top (max 0 (- n height))
        cursor-i (reduce-kv (fn [best i _]
                              (if (and (>= i viewport-top) (> i best)) i best))
                            -1
                            marks)]
    (when (>= cursor-i 0)
      {:row cursor-i
       :col (utils/visible-width (subs (nth lines cursor-i) 0 (get marks cursor-i)))})))

(def ^:private chunk-lines
  "Frame-pass chunk size. The pass compares the previous and current raw
   lines in chunks of this size with a host-native `=`: an equal chunk is
   copied whole from the previous output without entering the interpreted
   per-line loop, so a one-line change costs the walk over one chunk instead
   of the whole document (the interpreted loop is the pass's dominant cost
   on bb)."
  1024)

(def ^:private termux-host?
  "True on Termux ($TERMUX_VERSION marks it). Resolved once: the environment
   cannot change inside a process, and the frame path used to read it on every
   render."
  (boolean (System/getenv "TERMUX_VERSION")))

(defn- build-frame-lines
  "Build one frame's document: strip every CURSOR-MARKER, normalize each
   line, reuse PREV-OUT for lines unchanged since PREV-RAW, and report the
   range the frame's diff must repaint.

   Returns
   {:lines stripped-and-normalized lines
    :cursor {:row r :col c} or nil
    :marks {line-idx marker-idx}        ; carry into the next frame
    :changed [first last] or nil}.      ; nil = no change

   Stripping must be unconditional: a marker that leaks into the terminal
   stream is an APC sequence (ESC _ ... BEL) whose parser runs to ST
   (ESC \\) on strict terminals — Termux then swallows every following byte
   and the display freezes at the last painted frame while the app keeps
   rendering. The cursor is the bottom-most marker in the bottom HEIGHT
   lines; a marker above the viewport is still stripped, it just yields no
   cursor.

   The shared prefix is walked in CHUNK-LINES-sized chunks compared with a
   host-native `=`: an equal chunk is copied from PREV-OUT whole (content
   equality is enough — strip/normalize/marks are pure functions of the
   line content), and only a differing chunk enters the interpreted walk.
   Inside a walked chunk, a raw-identical line copies its previous output
   and marker index. The walked lines are the frame's change detector: they
   are the range main-diff consumes instead of walking the document again,
   and an appended tail is always walked. The walked range over-approximates
   the diff's `=` compare (a fresh-but-equal line counts as changed), which
   is safe: a spurious line is rewritten, never skipped; an equal chunk is
   never in the range, so the range is exact for whole chunks.

   QUICK? is the caller's hint that the previous frame repainted nothing.
   It buys one step before the chunk walk: a document content-equal to
   PREV-RAW reuses the whole previous result, with only the cursor
   recomputed for the height. `=` is host-native — far cheaper than the
   interpreted loop on bb — but it scans the whole vector when it fails, so
   it is only worth trying after an unchanged frame.

   :changed covers the shared prefix and any appended or removed tail
   (main-diff re-applies its appended? adjustment, which agrees). It
   describes the pre-flash document, so it must not be used for a frame
   whose final lines flash compositing changed (the caller gates on the
   flash state)."
  ([lines height]
   (build-frame-lines [] [] {} lines height))
  ([prev-raw prev-out prev-marks lines height]
   (build-frame-lines prev-raw prev-out prev-marks lines height false))
  ([prev-raw prev-out prev-marks lines height quick?]
   (let [prev-count (count prev-raw)
         n (count lines)]
     (if (and quick?
              (= lines prev-raw))
       {:lines prev-out
        :cursor (line-cursor prev-marks lines n height)
        :marks prev-marks
        :changed nil}
       (let [marker utils/CURSOR-MARKER
             ;; strip/normalize/reuse/mark the lines in [a b): a fresh chunk
             ;; vector plus the marks and the walked range's bounds
             scan-range (fn scan-range [a b marks fc lc]
                          (loop [i a, chunk (transient []), marks marks, fc fc, lc lc]
                            (if (< i b)
                              (let [line (nth lines i)]
                                (if (and (< i prev-count)
                                         (identical? (nth prev-raw i) line))
                                  (recur (inc i) (conj! chunk (nth prev-out i)) marks fc lc)
                                  (let [idx (clojure.string/index-of line marker)
                                        stripped (if idx
                                                   (clojure.string/replace line marker "")
                                                   line)]
                                    (recur (inc i)
                                           (conj! chunk (utils/normalize-terminal-output stripped))
                                           (if idx (assoc marks i idx) marks)
                                           (if (neg? fc) i fc)
                                           i))))
                              [(persistent! chunk) marks fc lc])))
             [acc marks prefix-first prefix-last]
             (if (and (vector? lines) (vector? prev-raw) (>= n prev-count))
               ;; The shared prefix in native chunks: an equal chunk stays in
               ;; place inside PREV-OUT (no copy at all), a differing one is
               ;; rescanned and assoc'd over its range. The appended tail is
               ;; scanned once and conj'd on. An unchanged document therefore
               ;; returns PREV-OUT itself, so the diff scan's `identical?`
               ;; fast path sees the vector it returned last frame.
               (loop [a 0, acc prev-out, marks {}, fc -1, lc -1]
                 (if (< a prev-count)
                   (let [b (min prev-count (+ a chunk-lines))]
                     (if (= (subvec prev-raw a b) (subvec lines a b))
                       (recur b acc marks fc lc)
                       (let [[chunk marks fc lc] (scan-range a b marks fc lc)
                             acc (reduce-kv (fn [v i line] (assoc v (+ a i) line))
                                            acc chunk)]
                         (recur b acc marks fc lc))))
                   (let [[tail marks fc lc] (scan-range prev-count n marks fc lc)]
                     ;; reduce, not into: an empty tail returns ACC itself, so
                     ;; an unchanged document keeps its vector identity
                     [(reduce conj acc tail) marks fc lc])))
               (scan-range 0 n {} -1 -1))
             out acc
             ;; A previous marker stays valid exactly while its line's
             ;; content is unchanged; a rescanned line's new status (in
             ;; MARKS) wins.
             marks (reduce-kv (fn [m i idx]
                                (if (and (< i prev-count)
                                         (< i n)
                                         (= (nth prev-raw i) (nth lines i)))
                                  (assoc m i idx)
                                  m))
                              marks
                              prev-marks)
             ;; A removed line repaints only when it held content — the diff's
             ;; missing side is "" (an appended tail is main-diff's appended?).
             [first-changed last-changed]
             (let [[rf rl] (when (< n prev-count)
                             (removed-range prev-out n prev-count))]
               [(if (and rf (neg? prefix-first)) rf prefix-first)
                (or rl prefix-last)])
             cursor (line-cursor marks lines n height)]
         {:lines out
          :cursor cursor
          :marks marks
          :changed (when (>= first-changed 0) [first-changed last-changed])})))))

(defn- refine-changed-range
  "Narrow HINT — the pass's [first last] repaint range — to what the diff's
   own content compare finds, so a line rebuilt to the same text (the
   editor's border) does not force a repaint. Returns [-1 -1] when every
   line in the range is content-equal."
  [prev lines [f l]]
  (let [equal? (fn [i]
                 (let [a (nth prev i nil)
                       b (nth lines i nil)]
                   (or (identical? a b) (= a b))))]
    (loop [f f, l l]
      (cond
        (> f l) [-1 -1]
        (equal? f) (recur (inc f) l)
        (equal? l) (recur f (dec l))
        :else [f l]))))

;; ─── Flash compositing (pi: compositeFlashes)

(defn- composite-flashes
  "Overlay transient flash lines (AltScreenFlashContainer) onto the screen
   window — the bottom HEIGHT content rows — right-aligned, one flash per
   row (pi: compositeFlashes). Takes the LAST HEIGHT flash lines so a flash
   flood stays on-screen (pi: .slice(-height)). Returns LINES unchanged
   when nothing flashes.

   FLOOR-TOP is the topmost document row the frame's diff can address — the
   previous frame's viewport top. The natural position is the new window's
   top (`count - height`), but when the document shrank in this frame that
   row sits above what the screen actually shows; compositing it there would
   make the diff read the flash as an above-window change and fall back to a
   clearing full redraw (the /copy case: the command dropdown closes and the
   'Copied!' flash lands one row above the still-visible old window top).
   Clamp the stack to the addressable rows instead: the flash is a screen
   overlay, so it can only land where the screen is. A frame that grows the
   document has its natural top below the floor already — unchanged."
  [flashes lines width height floor-top]
  (let [flash-lines (protocols/render flashes width)
        flash-lines (if (> (count flash-lines) height)
                      (vec (take-last height flash-lines))
                      flash-lines)
        n (count flash-lines)
        natural-top (max 0 (- (count lines) height))
        top (max natural-top (min floor-top (max 0 (- (count lines) n))))]
    (if (zero? n)
      lines
      (loop [row 0, lines lines]
        (if (>= row n)
          lines
          (let [line (nth flash-lines row)
                fw (utils/visible-width line)]
            (if (pos? fw)
              (let [idx (+ top row)
                    out (utils/composite-line (if (< idx (count lines)) (nth lines idx) "")
                                              line (- width fw) fw width)]
                (recur (inc row)
                       (if (< idx (count lines))
                         (assoc lines idx out)
                         (conj lines out))))
              (recur (inc row) lines))))))))

;; ─── Kitty image diff support (pi: expandChangedRangeForKittyImages /
;; deleteChangedKittyImages / getKittyImageReservedRows)

(defn- kitty-image-reserved-rows
  "Rows the image at INDEX occupies: the declared rows, bounded by the
   consecutive blank rows after it and by MAX-INDEX (pi:
   getKittyImageReservedRows). Must be computed on the raw, unpadded lines —
   padded spaces would terminate the walk immediately."
  [lines index & [max-index]]
  (let [rows (img/extract-kitty-image-rows (or (nth lines index nil) ""))
        max-rows (min rows
                      (if max-index
                        (- max-index index -1)
                        (count lines))
                      (- (count lines) index))]
    (if (<= rows 1)
      1
      (loop [reserved 1]
        (if (>= reserved max-rows)
          reserved
          (let [line (or (nth lines (+ index reserved) nil) "")]
            (if (or (img/is-image-line line) (pos? (utils/visible-width line)))
              reserved
              (recur (inc reserved)))))))))

(defn- collect-kitty-image-ids
  "All Kitty image ids referenced by LINES (pi: collectKittyImageIds)."
  [lines]
  (reduce (fn [acc line] (reduce conj acc (img/extract-kitty-image-ids line)))
          #{} lines))

(defn- expand-changed-range-for-kitty-images
  "Port of pi's expandChangedRangeForKittyImages: widen the changed range so it
   covers every image block that touches it in either prev or new lines.
   Terminals without image support produce no image lines (components gate on
   the capabilities), so short-circuit to the unchanged range instead of
   walking the whole transcript on every changed frame.

   PREV-HAD-IMAGES? (the previous frame's ids were non-empty) is the cheap
   discriminator that skips both walks when it is false and LINES carries no
   image line from FIRST-CHANGED on: PREV then has no image lines at all, and
   of the walk's two expansion conditions only `(>= i first-changed)` can
   fire in LINES (an image line below FIRST-CHANGED would itself be a change
   — PREV had none there — contradicting FIRST-CHANGED). The suffix test is
   `is-image-line`, a conservative over-approximation of the walk's
   extract-ids test.

   Returns [first-changed last-changed lines-have-images?]; the third value
   feeds the post-frame id update."
  [first-changed last-changed prev lines prev-had-images?]
  (if-not (:images (img/get-capabilities))
    [first-changed last-changed false]
    (let [suffix-has-image? (loop [i first-changed]
                              (if (< i (count lines))
                                (if (img/is-image-line (nth lines i))
                                  true
                                  (recur (inc i)))
                                false))]
      (if-not (or prev-had-images? suffix-has-image?)
        [first-changed last-changed false]
        (let [expanded (volatile! [first-changed last-changed])
              lines-have-images? (volatile! false)
              expand-for (fn [ls track-lines?]
                           (doseq [i (range (count ls))]
                             (when (seq (img/extract-kitty-image-ids (nth ls i)))
                               (when track-lines? (vreset! lines-have-images? true))
                               (let [block-end (+ i (kitty-image-reserved-rows ls i) -1)]
                                 (when (or (>= i first-changed)
                                           (and (<= i last-changed) (>= block-end first-changed)))
                                   (vswap! expanded
                                           (fn [[f l]] [(min f i) (max l block-end)])))))))]
          (expand-for prev false)
          (expand-for lines true)
          (conj @expanded @lines-have-images?))))))

(defn- delete-changed-kitty-images
  "Port of pi's deleteChangedKittyImages: the delete sequence for all image ids
   in PREV within the changed range."
  [first-changed last-changed prev]
  (let [ids (reduce (fn [acc i]
                      (reduce conj acc (img/extract-kitty-image-ids (nth prev i ""))))
                    #{}
                    (range first-changed (min (inc last-changed) (count prev))))]
    (apply str (map img/delete-kitty-image ids))))

;; ─── Diagnostic logs (pi: pi-crash.log / PI_DEBUG_REDRAW / PI_TUI_DEBUG)

(defn log-path
  "A diagnostic log file's path: the `kmet.log.dir` override (the same
   property `kmet.app.debug/logs-dir` reads, so a programmatic host — the test
   runner included — keeps crash and error logs together) else the cwd, where
   pi writes its pi-crash.log."
  [filename]
  (str (io/file (or (System/getProperty "kmet.log.dir")
                    (System/getProperty "user.dir"))
                filename)))

(defn append-log!
  "Append TEXT to a file at PATH, ignoring errors (pi: appendFileSync).
   io/writer :append, not spit: Jolt's spit is atomic (its own temp file
   plus a rename), which a per-line debug append does not need."
  [path text]
  (try
    (with-open [w (io/writer path :append true)]
      (.write w text))
    (catch Exception _)))

(defn log-scrollback-heal!
  "Append a `scrollbackHeal: DETAIL` line to the KMET_DEBUG_REDRAW log
   (no-op unless debug redraw is on)."
  [tui detail]
  (when (some-> (:debug-redraw? tui) deref)
    (append-log! (log-path "kmet-debug-render.log")
                 (str "[" (java.time.LocalDateTime/now) "] scrollbackHeal: "
                      detail "\n"))))

(defn- debug-line-preview
  "One-line description of LINE for the scrollback-dirty debug log
   (KMET_DEBUG_REDRAW=1): the escape-stripped text, truncated, plus the
   line's leading escape sequence so a style-only change — a box
   background flipping from pending to success, say — reads as a change
   instead of looking identical to its text."
  [line]
  (let [raw (str (or line ""))
        esc-pos (str/index-of raw "\u001b")
        esc (when esc-pos
              (pr-str (subs raw esc-pos (min (count raw) (+ esc-pos 48)))))]
    (str (pr-str (utils/truncate-to-width (utils/strip-ansi-codes raw) 90 "…"))
         (when esc (str " esc=" esc)))))

(defn- write-crash-log!
  "Write all rendered lines + overflow info to kmet-crash.log (pi:
   pi-crash.log), in the cwd unless a `kmet.log.dir` override names one."
  [lines w idx vw]
  (let [path (log-path "kmet-crash.log")
        data (str "Crash at " (java.time.LocalDateTime/now) "\n"
                  "Terminal width: " w "\n"
                  "Line " idx " visible width: " vw "\n"
                  "\n"
                  "=== All rendered lines ===\n"
                  (apply str (map-indexed (fn [i l]
                                            (str "[" i "] (w=" (utils/visible-width l) ") " l "\n"))
                                          lines))
                  "\n")]
    (try
      (spit path data)
      (catch Exception _))))

(defn- tui-debug-dump!
  "Write a render frame dump to $TMPDIR/tui/ (or java.io.tmpdir/tui/ when
   TMPDIR is unset; /tmp/tui/ on hosts where java.io.tmpdir is /tmp) when
   KMET_TUI_DEBUG=1 (pi: PI_TUI_DEBUG render dumps)."
  [prev lines buffer w h viewport-top hardware-cursor-row]
  (try
    (let [dir (str (io/file (or (System/getenv "TMPDIR")
                                (System/getProperty "java.io.tmpdir"))
                            "tui"))]
      (.mkdirs (io/file dir))
      (let [path (str (io/file dir (str "kmet-render-" (System/nanoTime) "-"
                                        (rand-int 0x7fffffff) ".log")))]
        (spit path (str "viewportTop: " viewport-top "\n"
                        "hardwareCursorRow: " hardware-cursor-row "\n"
                        "height: " h "\n"
                        "width: " w "\n"
                        "newLines.length: " (count lines) "\n"
                        "previousLines.length: " (count prev) "\n"
                        "\n"
                        "=== newLines ===\n"
                        (apply str (map-indexed (fn [i l] (str "[" i "] " l "\n")) lines))
                        "\n"
                        "=== previousLines ===\n"
                        (apply str (map-indexed (fn [i l] (str "[" i "] " l "\n")) prev))
                        "\n"
                        "=== buffer ===\n" buffer "\n"))))
    (catch Exception _)))

;; ─── The frame

(defn- scrollback-dirty?
  "True when a change above the window was left un-repainted since the last
   clearing full redraw (kmet.tui.core/tui-scrollback-dirty? over the TUI's
   frame state)."
  [tui]
  (boolean (some-> (:scrollback-dirty? tui) deref)))

(defn reset-frame!
  "Drop the previous frame's memo so the next frame rebuilds and re-emits the
   whole document (a forced render: tui-resume!, the scrollback heal)."
  [tui]
  (reset! (:previous-lines tui) [])
  (reset! (:previous-width tui) -1)
  (reset! (:previous-height tui) -1)
  (reset! (:max-lines-rendered tui) 0)
  (reset! (:previous-kitty-image-ids tui) #{})
  (reset! (:previous-raw-lines tui) [])
  (reset! (:previous-marks tui) {})
  (reset! (:previous-repainted? tui) true))

(defn render-frame!
  "Render one frame: COMPOSE returns the raw document (the component stack with
   the visible overlays composited), the pass normalizes it, diffs it against
   the previous frame and stages the terminal bytes in :pending-write. Mutates
   the frame state on TUI and the running frame's cursor and viewport atoms;
   the caller writes the staged buffer outside the dispatch lock, so a stalled
   tty cannot hold up input.

   FRAME carries the loop's per-run state: :width and :height (the live
   terminal size), :compose (fn [tui w h] -> the raw document lines),
   :hardware-cursor-row and :previous-viewport-top (the loop's atoms: the
   terminal-cursor model persists across frames and survives a resume),
   :request-render (the loop's frame request, used by the scrollback heal) and
   :pending-write, the atom the staged bytes land in."
  [tui {:keys [width height compose hardware-cursor-row previous-viewport-top
               request-render pending-write]}]
  (let [w width
        h height
        raw-lines (compose tui w h)
        ;; one document pass: strip the cursor markers,
        ;; normalize, and report the frame's repaint range. The
        ;; QUICK? hint may reuse the whole previous result when
        ;; the previous frame repainted nothing — a content-
        ;; equal document is then likely.
        frame (build-frame-lines
               @(:previous-raw-lines tui)
               @(:previous-normalized-lines tui)
               @(:previous-marks tui)
               raw-lines h
               (not @(:previous-repainted? tui)))
        cursor (:cursor frame)
        ;; pi: normalizeTerminalOutput — Thai/Lao AM decomposition +
        ;; tab expansion — runs before applyLineResets (pi
        ;; normalizes in applyLineResets, after cursor extraction).
        ;; Lines are written at their natural width like pi (no
        ;; global padding): the diff's \x1b[2K clears each rewritten
        ;; line before the write, so full-width padding is not
        ;; needed for clean rewrites — and it actively broke
        ;; kitty-image-reserved-rows, whose blank-row walk must run
        ;; on raw unpadded lines (padded spaces terminated it
        ;; immediately, collapsing every image block to one row).
        normalized (:lines frame)
        lines normalized
        ;; pi: applyLineResets — every non-image line ends with a
        ;; full SGR + OSC 8 reset (SEGMENT_RESET) so a truncated
        ;; line can never leave active attributes or an open
        ;; hyperlink bleeding into the next line. It is applied at
        ;; emit time (emit-line!) rather than to the whole LINES
        ;; vector: rebuilding every line's string each frame would
        ;; defeat the diff scan's identical? fast path (unchanged
        ;; lines stay the same objects that the component caches
        ;; return).
        prev @(:previous-lines tui)
        prev-w @(:previous-width tui)
        prev-h @(:previous-height tui)
        prev-count (count prev)
        width-changed (and (not (zero? prev-w)) (not= prev-w w))
        height-changed (and (not (zero? prev-h)) (not= prev-h h))
        first-render? (and (empty? prev)
                           (not width-changed)
                           (not height-changed))
        ;; One write per frame: the whole frame's output (sync markers,
        ;; cursor moves, line rewrites, cursor hide) accumulates into SB
        ;; and is written+flushed once (pi: one write per render).
        ;; Pi-state: the viewport top persists across frames
        prev-buffer-length (if (pos? prev-h) (+ @previous-viewport-top prev-h) h)
        prev-viewport-top (atom (if height-changed
                                  (max 0 (- prev-buffer-length h))
                                  @previous-viewport-top))
        ;; Flash compositing needs the viewport top the diff below will
        ;; use as its screen reference — the addressable floor for the
        ;; overlay (see composite-flashes).
        lines (composite-flashes @(:flashes tui) lines w h @prev-viewport-top)
        ;; flash rows overwrite LINES after the pass, so its
        ;; repaint range does not describe them; a frame that
        ;; composed or inherited a flash walks the diff's scan
        flashed? (not (identical? lines normalized))
        new-count (count lines)
        viewport-top (atom @prev-viewport-top)
        ;; The frame buffer is an atom so a mid-diff full-redraw
        ;; fallback can discard the partial diff output (pi: the
        ;; diff buffer is a local that fullRender replaces).
        sb (atom (StringBuilder.))
        emit! (fn [s] (.append @sb s))
        emit-line! (fn [line]
                     (emit! (if (img/is-image-line line)
                              line
                              ;; A line wider than the window wraps in the terminal, so the
                              ;; terminal ends up holding more rows than the model does and
                              ;; every later relative move is off by the wrapped rows. Clamp
                              ;; here, the one choke point both the diff and the full redraw
                              ;; write through: a component cannot desync the terminal
                              ;; whatever width its line claims (the renderer already clamps
                              ;; its own content slices; this covers the rest). pi throws in
                              ;; this case — truncating keeps the app alive and the rows
                              ;; aligned.
                              (str (if (> (utils/visible-width line) w)
                                     (:text (utils/slice-with-width line 0 w :strict? true))
                                     line)
                                   utils/SEGMENT-RESET))))
        debug-redraw? @(:debug-redraw? tui)
        log-redraw! (fn [reason]
                      (when debug-redraw?
                        (append-log!
                         (log-path "kmet-debug-render.log")
                         (str "[" (java.time.LocalDateTime/now) "] fullRender: "
                              reason " (prev=" prev-count ", new=" new-count
                              ", height=" h ")\n"))))
        log-heal! (fn [reason]
                    (log-scrollback-heal!
                     tui
                     (str reason " (frame=" @(:frame-count tui)
                          ", prev=" prev-count ", new=" new-count
                          ", height=" h ")")))
        compute-line-diff (fn [target-row]
                            (- (- target-row @viewport-top)
                               (- @hardware-cursor-row @prev-viewport-top)))
        position-hardware-cursor (fn [cursor-pos total-lines]
                                   (if (or (nil? cursor-pos) (<= total-lines 0))
                                     (emit! "\u001b[?25l")
                                     (let [target-row (max 0 (min (:row cursor-pos) (dec total-lines)))
                                           target-col (max 0 (:col cursor-pos))
                                           row-delta (- target-row @hardware-cursor-row)
                                           buf (str (cond
                                                      (pos? row-delta) (str "\u001b[" row-delta "B")
                                                      (neg? row-delta) (str "\u001b[" (- row-delta) "A")
                                                      :else "")
                                                    "\u001b[" (inc target-col) "G")]
                                       (when (seq buf) (emit! buf))
                                       (reset! hardware-cursor-row target-row)
                                       (if @(:show-hardware-cursor? tui)
                                         (emit! "\u001b[?25h")
                                         (emit! "\u001b[?25l")))))
        do-full-redraw (fn do-full-redraw [clear?]
                         (swap! (:full-redraw-count tui) inc)
                         (emit! CSI-2026-H)
                         (when clear?
                           (reset! (:scrollback-dirty? tui) false)
                           (doseq [id @(:previous-kitty-image-ids tui)]
                             (emit! (img/delete-kitty-image id)))
                           ;; The full redraw re-emits the whole
                           ;; transcript below, so the scrollback MUST
                           ;; be cleared too: 2J alone leaves the old
                           ;; transcript in the scrollback and the
                           ;; re-emit appends after it, duplicating
                           ;; every line (pi issue #6050: "without
                           ;; clearing scrollback the whole history
                           ;; duplicates"). Windows Terminal scrolls
                           ;; to the top on 3J (microsoft/terminal
                           ;; #20370, being fixed upstream) — pi
                           ;; accepts that over duplicated output.
                           (emit! "\u001b[2J\u001b[H\u001b[3J"))
                         (loop [i 0]
                           (when (< i new-count)
                             (when (pos? i) (emit! "\r\n"))
                             (let [rows (if (img/is-image-line (nth lines i))
                                          (kitty-image-reserved-rows lines i)
                                          1)]
                               (if (and (> rows 1) (<= rows h))
                                 (do (dotimes [_ (dec rows)] (emit! "\r\n"))
                                     (emit! (str "\u001b[" (dec rows) "A"))
                                     (emit-line! (nth lines i))
                                     (emit! (str "\u001b[" (dec rows) "B"))
                                     (recur (+ i rows)))
                                 (do (emit-line! (nth lines i))
                                     (recur (inc i)))))))
                         (emit! CSI-2026-L)
                         (reset! hardware-cursor-row (max 0 (dec new-count)))
                         (reset! viewport-top (max 0 (- (max h new-count) h)))
                         (if clear?
                           (reset! (:max-lines-rendered tui) new-count)
                           (swap! (:max-lines-rendered tui) max new-count))
                         (position-hardware-cursor cursor new-count))
        ;; The diff path records whether LINES carries kitty image
        ;; lines: nil means the diff did not run (full redraw /
        ;; first render), ::unchanged means it ran and found no
        ;; change, true/false is the kitty gate's answer. The
        ;; post-frame id update reads it instead of scanning the
        ;; whole document every frame.
        kitty-frame-lines (volatile! nil)
        ;; HINT is build-frame-lines' repaint range, refined
        ;; against the final lines so fresh-but-equal objects
        ;; (the editor's border) do not force a repaint. nil
        ;; means the pass proved the document unchanged (no
        ;; scan needed); ::scan forces the walks below (a flash
        ;; frame: the pass's range describes pre-flash lines).
        main-diff (fn main-diff [hint]
                    (let [max-lines (max new-count prev-count)
                          shared (min new-count prev-count)
                          [first-changed last-changed]
                          (cond
                            (nil? hint) [-1 -1]
                            (= ::scan hint) (let [;; The shared prefix (both vectors present) takes
                                                  ;; no per-line bounds branches — only the appended
                                                  ;; or removed tail needs the missing-line defaults.
                                                  [prefix-first prefix-last]
                                                  (loop [i 0, fc -1, lc -1]
                                                    (if (< i shared)
                                                      (let [p (nth prev i)
                                                            l (nth lines i)]
                                                        (if (or (identical? p l) (= p l))
                                                          (recur (inc i) fc lc)
                                                          (recur (inc i) (if (neg? fc) i fc) i)))
                                                      [fc lc]))]
                                              (loop [i shared, fc prefix-first, lc prefix-last]
                                                (if (< i max-lines)
                                                  (let [old-line (if (< i prev-count) (nth prev i) "")
                                                        new-line (if (< i new-count) (nth lines i) "")]
                                                    (if (or (identical? old-line new-line)
                                                            (= old-line new-line))
                                                      (recur (inc i) fc lc)
                                                      (recur (inc i) (if (neg? fc) i fc) i)))
                                                  [fc lc])))
                            :else (refine-changed-range prev lines hint))
                          appended? (> new-count prev-count)
                          [first-changed last-changed]
                          (if appended?
                            [(if (neg? first-changed) prev-count first-changed)
                             (dec new-count)]
                            [first-changed last-changed])
                          [first-changed last-changed]
                          (if (not (neg? first-changed))
                            (let [[f l lines-have-images?]
                                  (expand-changed-range-for-kitty-images
                                   first-changed last-changed prev lines
                                   (boolean (seq @(:previous-kitty-image-ids tui))))]
                              (vreset! kitty-frame-lines lines-have-images?)
                              [f l])
                            (do (vreset! kitty-frame-lines ::unchanged)
                                [first-changed last-changed]))
                          append-start? (and appended?
                                             (= first-changed prev-count)
                                             (pos? first-changed))
                          ;; A terminal has no addressable scrollback: a
                          ;; change that starts above the window can only be
                          ;; repainted from the window top, and the stale
                          ;; scrollback lines above it stay as they are. Doing
                          ;; that instead of the destructive full redraw keeps
                          ;; ESC[3J out of the streaming path — Termux and
                          ;; Windows Terminal yank the viewport to the top when
                          ;; it is scrolled up and a clear scrollback arrives
                          ;; (microsoft/terminal#20370; pi #4506/#6502). A
                          ;; change *entirely* above the window has nothing
                          ;; visible to repaint at all. Only the same-height
                          ;; and growing cases take this path; a shrink whose
                          ;; visible tail is unchanged takes
                          ;; shrink-above-window? below (the extra-line
                          ;; cleanup in the diff renderer needs the change to
                          ;; have begun inside the window, so a shrink with a
                          ;; changed tail keeps the full-redraw fallback).
                          scrollback-only-change? (and (not (neg? first-changed))
                                                       (= new-count prev-count)
                                                       (< first-changed @prev-viewport-top)
                                                       (< last-changed @prev-viewport-top)
                                                       (>= new-count @prev-viewport-top))
                          ;; A shrink whose removed lines are all above the
                          ;; window paints nothing when the visible tail is
                          ;; unchanged: the screen already shows exactly those
                          ;; rows (the same content, the removed count higher
                          ;; in the document), and a terminal has no
                          ;; addressable scrollback to clean up. The history
                          ;; above is stale — mark it dirty like the paths
                          ;; above and let the app heal at a streaming-free
                          ;; boundary, instead of a clearing full redraw in
                          ;; the middle of a stream. The tails are compared
                          ;; by content: a shrink that also changed what is
                          ;; visible must still repaint.
                          shrink-above-window? (and (not (neg? first-changed))
                                                    (< new-count prev-count)
                                                    (< first-changed @prev-viewport-top)
                                                    (>= new-count h)
                                                    (>= prev-count h)
                                                    (= (subvec lines (- new-count h) new-count)
                                                       (subvec prev (- prev-count h) prev-count)))
                          viewport-clamp? (and (not scrollback-only-change?)
                                               (not shrink-above-window?)
                                               (not (neg? first-changed))
                                               (< first-changed @prev-viewport-top)
                                               (>= new-count prev-count)
                                               (>= new-count @prev-viewport-top))
                          raw-first-changed first-changed
                          first-changed (if viewport-clamp? @prev-viewport-top raw-first-changed)
                          ;; Both paths below leave the changed lines above
                          ;; the window un-repainted (the terminal has no
                          ;; addressable scrollback), so the history is stale
                          ;; until a clearing full redraw. Record it; the app
                          ;; heals at a streaming-free boundary
                          ;; (tui-heal-scrollback!). The debug log below
                          ;; names the changed block's edges so a latched
                          ;; heal can be attributed to its renderer
                          ;; (KMET_DEBUG_REDRAW=1).
                          _ (when (or scrollback-only-change?
                                      shrink-above-window?
                                      viewport-clamp?)
                              (when debug-redraw?
                                (append-log!
                                 (log-path "kmet-debug-render.log")
                                 (str "[" (java.time.LocalDateTime/now) "] scrollbackDirty: "
                                      (cond scrollback-only-change? "scrollback-only"
                                            shrink-above-window? "shrink-above-window"
                                            :else "viewport-clamp")
                                      " (frame=" (some-> (:frame-count tui) deref)
                                      ", prev=" prev-count ", new=" new-count
                                      ", height=" h
                                      ", firstChanged=" raw-first-changed
                                      ", lastChanged=" last-changed
                                      ", prevViewportTop=" @prev-viewport-top
                                      (when viewport-clamp?
                                        (str ", clampedTo=" first-changed))
                                      ", alreadyDirty=" (boolean (some-> (:scrollback-dirty? tui) deref))
                                      ")\n"
                                      "  old[first]: " (debug-line-preview (nth prev raw-first-changed nil)) "\n"
                                      "  new[first]: " (debug-line-preview (nth lines raw-first-changed nil)) "\n"
                                      "  old[last]:  " (debug-line-preview (nth prev last-changed nil)) "\n"
                                      "  new[last]:  " (debug-line-preview (nth lines last-changed nil)) "\n")))
                              (reset! (:scrollback-dirty? tui) true))
                          mid-full-redraw! (fn [reason]
                                             (log-redraw! reason)
                                             (reset! sb (StringBuilder.))
                                             (do-full-redraw true))]
                      (cond
                        (neg? first-changed)
                        (do (position-hardware-cursor cursor new-count)
                            (reset! viewport-top @prev-viewport-top))

                        scrollback-only-change?
                        (do (log-redraw! (str "firstChanged < viewportTop, scrollback only ("
                                              last-changed " < " @prev-viewport-top
                                              "), no visible change"))
                            (reset! viewport-top @prev-viewport-top))

                        shrink-above-window?
                        ;; the window's top moved up with the document;
                        ;; the visible content (and the cursor's screen
                        ;; row) did not, so only the index model shifts
                        (let [removed (- prev-count new-count)]
                          (swap! prev-viewport-top - removed)
                          (swap! hardware-cursor-row - removed)
                          (reset! viewport-top @prev-viewport-top)
                          (position-hardware-cursor cursor new-count))

                        (>= first-changed new-count)
                        (if (> prev-count new-count)
                          (let [target-row (max 0 (dec new-count))]
                            (if (< target-row @prev-viewport-top)
                              (mid-full-redraw! (str "deleted lines moved viewport up ("
                                                     target-row " < " @prev-viewport-top ")"))
                              (let [line-diff (compute-line-diff target-row)
                                    extra-lines (- prev-count new-count)]
                                (if (> extra-lines h)
                                  (mid-full-redraw! (str "extraLines > height ("
                                                         extra-lines " > " h ")"))
                                  (let [clear-start-offset (if (zero? new-count) 0 1)
                                        move-back (max 0 (- (+ extra-lines clear-start-offset) 1))]
                                    (emit! CSI-2026-H)
                                    (emit! (delete-changed-kitty-images
                                            first-changed last-changed prev))
                                    (when (pos? line-diff)
                                      (emit! (str "\u001b[" line-diff "B")))
                                    (when (neg? line-diff)
                                      (emit! (str "\u001b[" (- line-diff) "A")))
                                    (emit! "\r")
                                    (when (and (pos? extra-lines) (pos? clear-start-offset))
                                      (emit! (str "\u001b[" clear-start-offset "B")))
                                    (dotimes [i extra-lines]
                                      (emit! "\r\u001b[2K")
                                      (when (< i (dec extra-lines))
                                        (emit! "\u001b[1B")))
                                    (when (pos? move-back)
                                      (emit! (str "\u001b[" move-back "A")))
                                    (emit! CSI-2026-L)
                                    (reset! hardware-cursor-row target-row)
                                    (position-hardware-cursor cursor new-count)
                                    (reset! viewport-top @prev-viewport-top))))))
                          (do (position-hardware-cursor cursor new-count)
                              (reset! viewport-top @prev-viewport-top)))

                        ;; Reached by any shrink (new content shorter than the
                        ;; old) whose first change is above the window — the
                        ;; diff renderer's extra-line cleanup assumes the
                        ;; change began inside the window, so rebuild the screen
                        ;; (e.g. compaction replacing the transcript).
                        (< first-changed @prev-viewport-top)
                        (mid-full-redraw! (str "firstChanged < viewportTop ("
                                               first-changed " < " @prev-viewport-top ")"))

                        :else
                        (let [prev-viewport-bottom (+ @prev-viewport-top h -1)
                              move-target-row (if append-start? (dec first-changed) first-changed)]
                          (when (> move-target-row prev-viewport-bottom)
                            (let [current-screen-row (max 0 (min (dec h)
                                                                 (- @hardware-cursor-row @prev-viewport-top)))
                                  move-to-bottom (- (dec h) current-screen-row)
                                  scroll (- move-target-row prev-viewport-bottom)]
                              (when (pos? move-to-bottom)
                                (emit! (str "\u001b[" move-to-bottom "B")))
                              (emit! (apply str (repeat scroll "\r\n")))
                              (swap! prev-viewport-top + scroll)
                              (reset! viewport-top @prev-viewport-top)
                              (reset! hardware-cursor-row move-target-row)))
                          (let [line-diff (compute-line-diff move-target-row)
                                render-end (min last-changed (dec new-count))
                                final-cursor-row (volatile! render-end)]
                            (emit! CSI-2026-H)
                            (emit! (delete-changed-kitty-images
                                    first-changed last-changed prev))
                            (when (pos? line-diff)
                              (emit! (str "\u001b[" line-diff "B")))
                            (when (neg? line-diff)
                              (emit! (str "\u001b[" (- line-diff) "A")))
                            (emit! (if append-start? "\r\n" "\r"))
                            (loop [i first-changed]
                              (when (<= i render-end)
                                (when (> i first-changed) (emit! "\r\n"))
                                (let [line (nth lines i)
                                      is-image (img/is-image-line line)
                                      rows (if is-image
                                             (kitty-image-reserved-rows lines i render-end)
                                             1)]
                                  (if (> rows 1)
                                    (let [image-start-screen-row (- i @viewport-top)]
                                      (if (or (< image-start-screen-row 0)
                                              (> (+ image-start-screen-row rows) h))
                                        (mid-full-redraw! (str "kitty image pre-clear would scroll ("
                                                               image-start-screen-row " + " rows " > " h ")"))
                                        (do (emit! "\u001b[2K")
                                            (dotimes [_ (dec rows)] (emit! "\r\n\u001b[2K"))
                                            (emit! (str "\u001b[" (dec rows) "A"))
                                            (emit-line! line)
                                            (emit! (str "\u001b[" (dec rows) "B"))
                                            (recur (+ i rows)))))
                                    (do (emit! "\u001b[2K")
                                        ;; A line wider than the terminal must NEVER kill the app — that
                                        ;; leaves a frozen frame on screen and a dead reader ("fully
                                        ;; stuck, had to kill it from the OS"). Log it and truncate the
                                        ;; line instead so the TUI keeps running; the ANSI-aware slice
                                        ;; drops the pending SGR/OSC reset, so emit-line! re-appends it.
                                        (let [line (if (and (not is-image)
                                                            (> (utils/visible-width line) w))
                                                     (do (write-crash-log! lines w i (utils/visible-width line))
                                                         (:text (utils/slice-with-width line 0 w :strict? true)))
                                                     line)]
                                          (emit-line! line)
                                          (recur (inc i))))))))
                            (when (> prev-count new-count)
                              (when (< render-end (dec new-count))
                                (emit! (str "\u001b[" (- (dec new-count) render-end) "B"))
                                (vreset! final-cursor-row (dec new-count)))
                              (let [extra-lines (- prev-count new-count)]
                                (dotimes [_ extra-lines]
                                  (emit! "\r\n\u001b[2K"))
                                (emit! (str "\u001b[" extra-lines "A"))))
                            (emit! CSI-2026-L)
                            (reset! hardware-cursor-row @final-cursor-row)
                            (swap! (:max-lines-rendered tui) max new-count)
                            (reset! viewport-top
                                    (max @prev-viewport-top
                                         (- @final-cursor-row h -1)))
                            (position-hardware-cursor cursor new-count))))))]
    (cond
      first-render?
      (do (log-redraw! "first render")
          (do-full-redraw false))
      width-changed
      (do (log-redraw! (str "terminal width changed (" prev-w " -> " w ")"))
          (do-full-redraw true))
      (and height-changed (not termux-host?))
      (do (log-redraw! (str "terminal height changed (" prev-h " -> " h ")"))
          (do-full-redraw true))
      (and @(:clear-on-shrink? tui)
           (< new-count @(:max-lines-rendered tui))
           (empty? @(:overlays tui)))
      (do (log-redraw! (str "clearOnShrink (maxLinesRendered="
                            @(:max-lines-rendered tui) ")"))
          (do-full-redraw true))
      :else
      (main-diff (if (and (not flashed?)
                          (not @(:previous-flashed? tui)))
                   (:changed frame)
                   ::scan)))
    (reset! previous-viewport-top @viewport-top)
    (when @(:tui-debug? tui)
      (tui-debug-dump! prev lines (str @sb) w h @viewport-top @hardware-cursor-row))
    (when (pos? (.length @sb))
      ;; staged, not written: the buffer is written after this
      ;; frame's lock is released (below); nothing appends to
      ;; it past this point
      (reset! pending-write @sb))
    (reset! (:previous-lines tui) lines)
    ;; line memo: raw in / normalized out (pre-flash) + markers
    (reset! (:previous-normalized-lines tui) normalized)
    (reset! (:previous-raw-lines tui) raw-lines)
    (reset! (:previous-marks tui) (:marks frame))
    (reset! (:previous-flashed? tui) flashed?)
    ;; the diff's own verdict (kitty-frame-lines: ::unchanged
    ;; when it ran and found nothing, nil when a full redraw
    ;; skipped it) feeds the next frame's QUICK? hint
    (reset! (:previous-repainted? tui)
            (not= ::unchanged @kitty-frame-lines))
    (reset! (:previous-width tui) w)
    (reset! (:previous-height tui) h)
    ;; Image lines only exist when the terminal supports
    ;; images — components gate on the capabilities. Without
    ;; support the whole-document scan (and the collect walk)
    ;; is pure overhead, so skip it. The diff path reports
    ;; whether LINES carries image lines (::unchanged = no
    ;; change, so the previous ids still describe LINES); only
    ;; a full redraw (nil) falls back to the scan.
    (reset! (:previous-kitty-image-ids tui)
            (let [frame-images @kitty-frame-lines]
              (cond
                (true? frame-images) (collect-kitty-image-ids lines)
                (false? frame-images) #{}
                (= ::unchanged frame-images) @(:previous-kitty-image-ids tui)
                :else (if (and (:images (img/get-capabilities))
                               (some img/is-image-line lines))
                        (collect-kitty-image-ids lines)
                        #{}))))
    ;; A latched scrollback heal (tui-heal-scrollback!) belongs to
    ;; the first frame that STARTED after the request, so this frame
    ;; — and not one already in flight — is the one whose diff
    ;; normally recorded the boundary's above-window changes. A
    ;; dirty scrollback forces the clearing rebuild. A CLEAN
    ;; qualifying frame does not always drop the latch: a renderer
    ;; that corrected its state during the pass (the tool result's
    ;; preview correction) requested the follow-up frame itself, and
    ;; the dirt it records there still belongs to this healing
    ;; boundary — keep the latch for that one grace frame. The grace
    ;; is spent at most once (reset by the next heal request), so a
    ;; later streaming frame can never keep the latch alive into a
    ;; mid-stream clear. compare-and-set! leaves a new latch raised
    ;; concurrently by another heal call alone.
    (let [target @(:scrollback-heal-target tui)]
      (when (and (pos? target) (>= @(:frame-count tui) target))
        (when (compare-and-set! (:scrollback-heal-target tui) target 0)
          (cond
            (scrollback-dirty? tui)
            (do (log-heal! (str "firing clearing full redraw (target=" target ")"))
                (some-> (:scrollback-heal-graced? tui) (reset! false))
                (request-render true))

            (and @(:render-requested? tui)
                 (not (some-> (:scrollback-heal-graced? tui) deref)))
            (do (log-heal! (str "clean target frame, one grace frame (target=" target ")"))
                (some-> (:scrollback-heal-graced? tui) (reset! true))
                (swap! (:scrollback-heal-target tui)
                       max (inc @(:frame-count tui))))

            :else
            (log-heal! (str "clean target frame, latch dropped (target=" target ")"))))))))
