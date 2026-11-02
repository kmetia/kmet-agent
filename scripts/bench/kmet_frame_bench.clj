;; Live render-loop frame phases: the hot path the app actually runs —
;; compose (document concat + overlays) → lines → flashes → diff+emit → write —
;; measured per frame on a real session in a virtual terminal.
;;
;; Usage:
;;   bb   scripts/bench/kmet_frame_bench.clj <session-file> [width] [height] [scenario]
;;   jolt scripts/bench/kmet_frame_bench.clj <session-file> [width] [height] [scenario]
;;
;; Scenarios (default `all`):
;;   cold   — the first frame (the whole transcript, full emit)
;;   calm   — render requests with no content change at all: the settled,
;;            turn-over state (the frame emits nothing)
;;   typing — a settled transcript + one editor line changing per frame
;;            (the frame a keystroke in the calm state produces)
;;   stream — chat-history-start-streaming! + one append per frame
;;            (the streaming assistant path)
;;   redraw — width resizes, i.e. the clearing full-redraw path
;;
;; A theme switch (the global-reflow path) is measured by
;; kmet_render_bench.clj's render mode: it needs the app's theme
;; controller wiring, which this bare loop harness does not set up.
;;
;; Idle CPU (no frames requested) is not measured here — the loop's park
;; cost is process-level; measure it live (perf.md §1's /proc recipe).
;;
;; Phase attribution wraps the loop's own functions with timers
;; (alter-var-root): kmet.tui.core/compose-document (the flat document:
;; protocols/cached-lines plus the overlay composite), the chat history's
;; message walk (kmet.ui.tui.chat-history/message-parts),
;; kmet.tui.frame/{build-frame-lines,composite-flashes} and the virtual
;; terminal's write-output. The compose wrapper also emits the frame opener
;; (:compose-start, before the document is touched), because the overlay
;; composite it calls records its own phase first. diff+emit is the residual
;; between the last wrapped phase and the write. Numbers are wall-clock,
;; single-process, means of the scenario's frames — the harness perf.md
;; §11.3/§13's tables use.
;;
;; Measurement discipline:
;; - A frame is complete when its write lands: every frame emits the
;;   synchronized-output markers, so the sync write (not the frame-count
;;   increment, which happens when the loop *starts* the frame) is what
;;   next-frame! and settle! wait for. A multi-second cold compose leaves
;;   the request flag false the whole time, so counting started frames
;;   would declare an idle loop while the first frame is still building.
;; - GC is measured, not absorbed: a sampler thread records every stall over
;;   PAUSE-THRESHOLD-NS (a global pause — the collector, or the scheduler),
;;   and report excludes frames that overlap one and states how many. A
;;   benchmark of an allocation-heavy path must not report the collector as
;;   work: the same no-change compose measures 4 ms on a quiet thread and
;;   15-60 ms when a pause lands in it.

(ns kmet-frame-bench
  (:require [clojure.string :as str]
            [kmet.app.session.core :as session]
            [kmet.app.registry :as registry]
            [kmet.app.tools.registry :as tools]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.interactive :as inter]
            [kmet.test-utils :as tu]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.core :as core]
            [kmet.tui.frame :as frame]
            [kmet.tui.terminal :as term]))

;; ─── Virtual terminal (writes recorded; size mutable) ──────────────────────

(def events (atom []))
(def frames-done
  "Frames whose render-frame! call returned. The loop's frame counter says a
   frame was *started*; a frame that repaints nothing stages no bytes, so no
   write can mark its end."
  (atom 0))

(defrecord VirtualTerminal [size writes]
  term/ITerminal
  (start! [this _ _] this)
  (stop! [_] nil)
  (started? [_] true)
  (write-output [_ s]
    (let [t0 (System/nanoTime)]
      (swap! writes conj s)
      (swap! events conj [:write t0 (System/nanoTime) (count s)])))
  (read-input [_ _] -1)
  (columns [_] (:cols @size))
  (rows [_] (:rows @size))
  (set-progress! [_ _] nil))

;; ─── Phase timers ──────────────────────────────────────────────────────────

(defn- timed-var!
  "Rebind VAR to a wrapper recording [:phase t-start t-end] per call.
   The original fn is looked up through the var on every call so the
   wrapper stays correct if the var is redefined. OPENER, when given, names
   an event recorded before the body runs (zero-length), so a frame can be
   opened before a phase the body itself records first."
  ([phase v] (timed-var! phase v nil))
  ([phase v opener]
   (alter-var-root v (fn [orig]
                       (fn [& args]
                         (let [t0 (System/nanoTime)
                               _ (when opener (swap! events conj [opener t0 t0]))
                               r (apply orig args)
                               t1 (System/nanoTime)]
                           (swap! events conj [phase t0 t1])
                           r))))))

(defn- instrument! []
  ;; render-frame! is the frame: its return is the only completion signal
  ;; (see frames-done)
  (alter-var-root #'frame/render-frame!
                  (fn [orig]
                    (fn [tui frame]
                      (let [r (orig tui frame)]
                        (swap! frames-done inc)
                        r))))
  ;; compose-document is private: the loop's closure calls it through the var,
  ;; so wrapping the var times the whole document compose.
  (timed-var! :compose (ns-resolve 'kmet.tui.core 'compose-document) :compose-start)
  ;; the chat history's walk over its message components (render calls +
  ;; separator/parts assembly) — the compose's dominant term
  (timed-var! :parts (ns-resolve 'kmet.ui.tui.chat-history 'message-parts))
  (timed-var! :lines #'frame/build-frame-lines)
  (timed-var! :flashes #'frame/composite-flashes))

(def ^:private replay-branch!
  "The private session-admin replay entry (resume populates the chat
   history through it; the harness only needs the same populated history)."
  (var-get (ns-resolve 'kmet.ui.tui.interactive.session-admin 'replay-branch!)))

;; ─── Global pauses (the collector, the scheduler) ───────────────────────────

(def ^:private pause-threshold-ns (* 5 1000000))

(def pauses (atom []))
(def ^:private heartbeat? (atom false))

(defn- start-heartbeat!
  "Record every stall over PAUSE-THRESHOLD-NS: a sampler that sleeps 1 ms and
   measures how late it woke sees the same global pauses the render thread
   does, so a frame overlapping one is excluded from the means instead of
   being reported as render work."
  []
  (reset! heartbeat? true)
  (future
    (loop []
      (let [t0 (System/nanoTime)]
        (Thread/sleep 1)
        (let [t1 (System/nanoTime)]
          (when (> (- t1 t0) pause-threshold-ns)
            (swap! pauses conj [t0 t1]))))
      (when @heartbeat? (recur)))))

(defn- stop-heartbeat! [] (reset! heartbeat? false))

(defn- pause-ms
  "Milliseconds of PAUSES that overlap [START END] — 0 for a frame that ran
   between pauses."
  [start end]
  (let [overlap-ns (reduce (fn [acc [p0 p1]]
                             (if (and (< p0 end) (> p1 start))
                               (+ acc (- (min p1 end) (max p0 start)))
                               acc))
                           0
                           @pauses)]
    (/ (double overlap-ns) 1e6)))

;; ─── Frame/phase post-processing ───────────────────────────────────────────

(defn- sync-frame? [s] (str/includes? s "\u001b[?2026h"))
(defn- frame-writes [writes] (count (filter sync-frame? @writes)))

(defn- wait-until [pred timeout-ms what]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 1) (recur))
        :else (throw (ex-info (str "timeout waiting for " what) {}))))))

(defn- settle!
  "Wait until the loop is parked with no frame in flight: every started frame
   (the loop's counter) has returned from render-frame! (frames-done), nothing
   is requested, and that has held for a few polls. The request flag is
   already false while a long compose runs, so it alone is not enough; the
   budget is generous — a cold frame on a long session composes for seconds."
  [tui]
  (let [deadline (+ (System/currentTimeMillis) 180000)]
    (loop [last-done -1, stable 0]
      (let [done @frames-done
            in-flight? (or @(:render-requested? tui)
                           (> @(:frame-count tui) done))]
        (cond
          (and (> stable 3) (not in-flight?)) true
          (> (System/currentTimeMillis) deadline) true
          :else (do (Thread/sleep 20)
                    (recur done (if (and (= done last-done) (not in-flight?))
                                  (inc stable) 0))))))))

(defn- begin-scenario!
  "Settle the loop — nothing requested, frame count stable — drop garbage
   from the previous scenario, then open a fresh event window. Without the
   collection, the frames right after the cold full render (megabytes of
   frame vectors and write buffers) measured 2-3x slower than the same
   no-change frames later in the run; the per-scenario medians must
   describe the steady state."
  [tui]
  (settle! tui)
  (System/gc)
  (Thread/sleep 100)
  (reset! events [])
  (reset! pauses []))

(defn- next-frame!
  "Request one render and wait for render-frame! to return. A frame that
   repaints nothing stages no bytes, so the write is not a completion signal;
   frames-done is."
  [tui]
  (let [n (inc @frames-done)
        diag (fn [e]
               (ex-info (str (ex-message e) " [frame=" @(:frame-count tui)
                             " frames-done=" @frames-done
                             " loop-done=" (future-done? @(:render-loop tui))
                             " last-events=" (pr-str (mapv first (take-last 6 @events)))
                             "]")
                        {:events (vec (take-last 12 @events))
                         :threads (mapv (fn [[t frames]]
                                          [(.getName ^Thread t) (mapv str (take 25 frames))])
                                        (sort-by (fn [[t _]] (.getName ^Thread t))
                                                 (Thread/getAllStackTraces)))}
                        e))]
    (core/tui-request-render tui)
    (try
      (wait-until #(>= @frames-done n) 180000 "frame")
      (catch Exception e (throw (diag e))))))

(defn- us
  "Nanoseconds to milliseconds."
  [t0 t1] (/ (double (- t1 t0)) 1e6))

(defn- analyze
  "Turn the flat event log into per-frame maps of phase → µs. A frame opens
   at each :compose-start event; the residual between the last wrapped phase
   and a :write is :diff+emit. A frame without a write (no-change) is closed
   at the next frame's start with :wrote? false; a frame still in flight
   (no :flashes yet) is dropped."
  [evs]
  (let [flush-frame (fn [cur out]
                      (if (and cur (contains? (:phases cur) :flashes))
                        (conj out (-> cur
                                      (dissoc :write-at)
                                      (assoc :end (if (:wrote? cur) (:write-at cur) (:last cur)))))
                        out))]
    (loop [es (seq evs), cur nil, out []]
      (if-let [[p t0 t1 len] (first es)]
        (case p
          :compose-start (recur (next es)
                                {:start t0 :last t1 :wrote? false
                                 :phases {}}
                                (flush-frame cur out))
          :write (recur (next es)
                        (if cur
                          (-> cur
                              (assoc :wrote? true :write-at t1 :write-bytes len)
                              (assoc-in [:phases :diff+emit] (us (:last cur) t0)))
                          cur)
                        out)
          (recur (next es)
                 (if cur
                   (-> cur (assoc :last t1) (assoc-in [:phases p] (us t0 t1)))
                   cur)
                 out))
        (flush-frame cur out)))))

(defn- mean [xs] (if (seq xs) (/ (double (reduce + xs)) (count xs)) 0.0))
(defn- median [xs] (when (seq xs) (let [v (vec (sort xs))] (nth v (quot (count v) 2)))))

(def ^:private phases [:compose :parts :lines :flashes :diff+emit])

(defn- report [label evs lines]
  (let [fs (analyze evs)
        n (count fs)
        ;; a frame that overlapped a global pause reports the pause as
        ;; phase time; those frames are excluded and counted, never averaged
        clean (filterv #(zero? (pause-ms (:start %) (:end %))) fs)
        paused (- n (count clean))]
    (when (pos? n)
      (println (format "%s: %d frames (%d wrote, %d excluded as paused), %d lines"
                       label n (count (filter :wrote? fs)) paused lines))
      (when (pos? (count @pauses))
        (println (format "  pauses %d totalling %.1f ms over the window (threshold %d ms)"
                         (count @pauses)
                         (/ (reduce + (map (fn [[a b]] (- b a)) @pauses)) 1e6)
                         (quot pause-threshold-ns 1000000))))
      (if (empty? clean)
        ;; every frame overlapped a pause: attributing its time to the phases
        ;; would report the collector as render work
        (println "  no frame ran between pauses — nothing to attribute")
        (do
          (doseq [p phases]
            (let [xs (keep #(get-in % [:phases p]) clean)]
              (when (seq xs)
                (println (format "  %-13s %8.3f ms mean %8.3f ms median %8.3f ms max"
                                 (name p) (mean xs) (median xs) (apply max xs))))))
          (let [totals (map #(/ (- (:end %) (:start %)) 1e6) clean)
                bytes (map #(or (:write-bytes %) 0) clean)]
            (println (format "  %-13s %8.3f ms mean %8.3f ms median"
                             "frame total" (mean totals) (median totals)))
            (println (format "  %-13s %8.0f bytes mean"
                             "write size" (mean bytes))))))
      (println))))

;; ─── Driver ────────────────────────────────────────────────────────────────

(def ^:private stream-chunk
  "A realistic streaming chunk: prose with inline markup."
  "The renderer walks the whole document each pass; **bold** and `code` keep it honest.\n\n")

(defn- run-cold! [tui writes]
  (when (zero? (frame-writes writes)) (reset! events []))
  (wait-until #(>= @(:frame-count tui) 1) 60000 "first frame")
  (wait-until #(pos? (frame-writes writes)) 60000 "first write")
  (report "cold (first frame)" @events (count @(:previous-lines tui)))
  ;; the first frame can be followed by a settling full re-render; absorb it
  ;; here so it is not attributed to the next scenario's window
  (settle! tui))

(defn- run-typing! [tui ed n]
  ;; settle the preceding scenario's caches, then warm this one before the
  ;; measured window
  (dotimes [i 3]
    (core/editor-set-text! ed (str "warm " i " typing"))
    (next-frame! tui))
  (begin-scenario! tui)
  (dotimes [i n]
    (core/editor-set-text! ed (str "calm " i " typing"))
    (next-frame! tui))
  (report "typing (editor tick)" @events (count @(:previous-lines tui))))

(defn- run-calm! [tui n]
  ;; The frames right after the cold render still settle caches (content-
  ;; equal but not identical lines, a trailing full pass), so warm up before
  ;; the measured window: the scenario reports the settled state.
  (dotimes [_ 12] (next-frame! tui))
  (begin-scenario! tui)
  (dotimes [_ n] (next-frame! tui))
  (report "calm (no change)" @events (count @(:previous-lines tui))))

(defn- run-stream! [tui ch n]
  (begin-scenario! tui)
  (chat-history/chat-history-start-streaming! ch)
  ;; the first appends mount the assistant component and grow its caches;
  ;; warm those, then measure the steady append frame
  (dotimes [_ 3]
    (chat-history/chat-history-append-streaming-text! ch stream-chunk)
    (next-frame! tui))
  (begin-scenario! tui)
  (dotimes [_ n]
    (chat-history/chat-history-append-streaming-text! ch stream-chunk)
    (next-frame! tui))
  (report "stream (append/frame)" @events (count @(:previous-lines tui))))

(defn- run-redraw! [tui writes vt n]
  (begin-scenario! tui)
  (dotimes [i n]
    (let [w (frame-writes writes)]
      (swap! (:size vt) assoc :cols (if (even? i) 90 100))
      (core/tui-request-render tui)
      ;; the width-changed branch always writes (a clearing full redraw), so
      ;; this one waits on the write: the redraw's bytes, not just the frame's
      ;; return.
      (wait-until #(> (frame-writes writes) w) 20000 "redraw write")
      (settle! tui)))
  (report "redraw (resize -> clearing)" @events (count @(:previous-lines tui))))

;; ─── Main ──────────────────────────────────────────────────────────────────

(defn -main [& args]
  (let [session-file (first args)
        width (Integer/parseInt (or (second args) "100"))
        height (Integer/parseInt (or (nth args 2 nil) "30"))
        scenario (or (nth args 3 nil) "all")
        scenarios (set (str/split scenario #","))
        host (if (resolve 'clojure.core/*jolt-version*) "jolt" "bb")]
    (when-not session-file
      (println "usage: kmet_frame_bench <session-file> [width] [height] [scenario]")
      (System/exit 1))
    (println (str "host: " host "  session: " (last (str/split session-file #"/"))
                  "  " width "x" height "  scenario: " scenario))
    (tu/with-extension-host
      (fn []
        (instrument!)
        (start-heartbeat!)
        (let [sess (session/load-session session-file)
              ch (chat-history/make-chat-history :tool-display-mode :expanded
                                                 :tool-fn (fn [name] (tools/get-tool (registry/atom-registry) name)))
              cs (inter/map->CoreState {:chat-history ch})
              _ (replay-branch! cs sess)
              ed (editor/make-editor :height 1)
              size (atom {:cols width :rows height})
              writes (atom [])
              vt (map->VirtualTerminal {:size size :writes writes})
              tui (core/create-tui vt)]
          (core/editor-set-text! ed "")
          (core/tui-add-child tui ch)
          (core/tui-add-child tui ed)
          (reset! (:running? tui) true)
          (reset! (:stopped? tui) false)
          (reset! (:render-loop tui) (future ((var core/run-render-loop!) tui)))
          (try
            (when (or (scenarios "all") (scenarios "cold")) (run-cold! tui writes))
            (when (or (scenarios "all") (scenarios "calm")) (run-calm! tui 20))
            (when (or (scenarios "all") (scenarios "typing")) (run-typing! tui ed 20))
            (when (or (scenarios "all") (scenarios "stream")) (run-stream! tui ch 40))
            (when (or (scenarios "all") (scenarios "redraw")) (run-redraw! tui writes vt 4))
            (finally
              (stop-heartbeat!)
              (core/tui-stop tui)
              (when-let [f @(:render-loop tui)]
                (let [v (try (deref f 5000 nil) (catch Throwable t t))]
                  (when (instance? Throwable v)
                    (println "render loop died:" v))))))
          (println "total frames:" @(:frame-count tui)))))))

(apply -main *command-line-args*)
