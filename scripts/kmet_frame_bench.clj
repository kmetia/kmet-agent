;; Live render-loop frame phases: the hot path the app actually runs —
;; render-stack → overlays → lines → flashes → diff+emit → write —
;; measured per frame on a real session in a virtual terminal.
;;
;; Usage:
;;   bb   scripts/kmet_frame_bench.clj <session-file> [width] [height] [scenario]
;;   jolt scripts/kmet_frame_bench.clj <session-file> [width] [height] [scenario]
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
;; (alter-var-root): kmet.tui.components.stack/render-stack,
;; kmet.tui.core/{composite-overlays,build-frame-lines,
;; composite-flashes} and the virtual terminal's
;; write-output. diff+emit is the residual between the last wrapped phase
;; and the write (a no-change frame writes nothing, so its tail is not
;; attributed). Numbers are wall-clock, single-process, means of the
;; scenario's frames — the harness perf.md §11.3/§13's tables use.

(ns kmet-frame-bench
  (:require [clojure.string :as str]
            [kmet.app.session :as session]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.modes.interactive :as inter]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.stack :as stack]
            [kmet.tui.core :as core]
            [kmet.tui.terminal :as term]
            [kmet.tui.theme :as theme]))

;; ─── Virtual terminal (writes recorded; size mutable) ──────────────────────

(def events (atom []))

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
   wrapper stays correct if the var is redefined."
  [phase v]
  (alter-var-root v (fn [orig]
                      (fn [& args]
                        (let [t0 (System/nanoTime)
                              r (apply orig args)
                              t1 (System/nanoTime)]
                          (swap! events conj [phase t0 t1])
                          r)))))

(defn- instrument! []
  (timed-var! :render-stack #'stack/render-stack)
  (timed-var! :overlays #'core/composite-overlays)
  (timed-var! :lines #'core/build-frame-lines)
  (timed-var! :flashes #'core/composite-flashes))

(def ^:private replay-branch!
  "The private session-admin replay entry (resume populates the chat
   history through it; the harness only needs the same populated history)."
  (var-get (ns-resolve 'kmet.modes.interactive.session-admin 'replay-branch!)))

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
  "Wait until the loop is parked with no frame in flight (nothing
   requested) and the frame count has been stable for SETTLE-MS."
  [tui]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop [last-count -1, stable 0]
      (let [c @(:frame-count tui)]
        (cond
          (> stable 3) true
          (> (System/currentTimeMillis) deadline) true
          :else (do (Thread/sleep 20)
                    (recur c (if (and (= c last-count)
                                      (not @(:render-requested? tui)))
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
  (reset! events []))

(defn- next-frame!
  "Request one render and wait for it: the loop's frame counter for the
   start, and (unless QUIET?) the frame write for the end — a no-change
   frame emits nothing, so QUIET? settles briefly instead."
  [tui writes & {:keys [quiet?]}]
  (let [n (inc @(:frame-count tui))
        w (inc (frame-writes writes))
        diag (fn [e]
               (ex-info (str (ex-message e) " [frame=" @(:frame-count tui)
                             " sync-writes=" (frame-writes writes)
                             " writes=" (count @writes)
                             " loop-done=" (future-done? @(:render-loop tui))
                             " last-write=" (pr-str (subs (str (last @writes)) 0 (min 80 (count (str (last @writes))))))
                             "]")
                        {:events (vec (take-last 12 @events))
                         :threads (mapv (fn [[t frames]]
                                          [(.getName ^Thread t) (mapv str (take 25 frames))])
                                        (sort-by (fn [[t _]] (.getName ^Thread t))
                                                 (Thread/getAllStackTraces)))}
                        e))]
    (core/tui-request-render tui)
    (try
      (wait-until #(>= @(:frame-count tui) n) 20000 "frame start")
      (if quiet?
        (Thread/sleep 60)
        (wait-until #(>= (frame-writes writes) w) 20000 "frame write"))
      (catch Exception e (throw (diag e))))))

(defn- us
  "Nanoseconds to milliseconds."
  [t0 t1] (/ (double (- t1 t0)) 1e6))

(defn- analyze
  "Turn the flat event log into per-frame maps of phase → µs. A frame opens
   at each :render-stack event; the residual between the last wrapped phase
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
          :render-stack (recur (next es)
                               {:start t0 :last t1 :wrote? false
                                :phases {:render-stack (us t0 t1)}}
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

(def ^:private phases [:render-stack :overlays :lines :flashes :diff+emit])

(defn- report [label evs lines]
  (let [fs (analyze evs)
        n (count fs)]
    (when (pos? n)
      (println (format "%s: %d frames (%d wrote), %d lines"
                       label n (count (filter :wrote? fs)) lines))
      (doseq [p phases]
        (let [xs (keep #(get-in % [:phases p]) fs)]
          (when (seq xs)
            (println (format "  %-13s %8.3f ms mean %8.3f ms median %8.3f ms max"
                             (name p) (mean xs) (median xs) (apply max xs))))))
      (let [totals (map #(/ (- (:end %) (:start %)) 1e6) fs)
            bytes (map #(or (:write-bytes %) 0) fs)]
        (println (format "  %-13s %8.3f ms mean %8.3f ms median"
                         "frame total" (mean totals) (median totals)))
        (println (format "  %-13s %8.0f bytes mean"
                         "write size" (mean bytes))))
      (println))))

;; ─── Driver ────────────────────────────────────────────────────────────────

(def ^:private chunk
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

(defn- run-typing! [tui writes ed n]
  ;; settle the preceding scenario's caches, then warm this one before the
  ;; measured window
  (dotimes [i 3]
    (core/editor-set-text! ed (str "warm " i " typing"))
    (next-frame! tui writes))
  (begin-scenario! tui)
  (dotimes [i n]
    (core/editor-set-text! ed (str "calm " i " typing"))
    (next-frame! tui writes))
  (report "typing (editor tick)" @events (count @(:previous-lines tui))))

(defn- run-calm! [tui writes n]
  ;; The frames right after the cold render still settle caches (content-
  ;; equal but not identical lines, a trailing full pass), so warm up before
  ;; the measured window: the scenario reports the settled state.
  (dotimes [_ 12] (next-frame! tui writes :quiet? true))
  (begin-scenario! tui)
  (dotimes [_ n] (next-frame! tui writes :quiet? true))
  (report "calm (no change)" @events (count @(:previous-lines tui))))

(defn- run-stream! [tui writes ch n]
  (begin-scenario! tui)
  (chat-history/chat-history-start-streaming! ch)
  ;; the first appends mount the assistant component and grow its caches;
  ;; warm those, then measure the steady append frame
  (dotimes [_ 3]
    (chat-history/chat-history-append-streaming-text! ch chunk)
    (next-frame! tui writes))
  (begin-scenario! tui)
  (dotimes [_ n]
    (chat-history/chat-history-append-streaming-text! ch chunk)
    (next-frame! tui writes))
  (report "stream (append/frame)" @events (count @(:previous-lines tui))))

(defn- run-redraw! [tui writes vt n]
  (begin-scenario! tui)
  (dotimes [i n]
    (let [w (frame-writes writes)]
      (swap! (:size vt) assoc :cols (if (even? i) 90 100))
      (core/tui-request-render tui)
      ;; the width-changed branch always writes (clearing full redraw); an
      ;; extra request/loop-poll frame after it changes nothing and emits
      ;; nothing, so wait on the write itself, not on next-frame!'s count.
      (wait-until #(> (frame-writes writes) w) 20000 "redraw write")
      (settle! tui)))
  (report "redraw (resize -> clearing)" @events (count @(:previous-lines tui))))

(defn- run-reflow! [tui writes n]
  (begin-scenario! tui)
  (let [cur (theme/get-current-theme)
        other (assoc cur :name "probe-theme" :text "#ff0000")]
    (dotimes [i n]
      (let [w (frame-writes writes)]
        (theme/set-theme-instance! (if (even? i) other cur))
        (core/tui-request-render tui)
        ;; the switched-in palette changes every styled line, so a reflow
        ;; frame always writes; waiting on the write is race-proof (the
        ;; theme watch may have scheduled a frame of its own)
        (wait-until #(> (frame-writes writes) w) 20000 "reflow write")
        (settle! tui)))
    (theme/set-theme-instance! cur))
  (report "reflow (theme switch)" @events (count @(:previous-lines tui))))

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
    (instrument!)
    (let [sess (session/load-session session-file)
          ch (chat-history/make-chat-history :tool-display-mode :expanded)
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
        (when (or (scenarios "all") (scenarios "calm")) (run-calm! tui writes 10))
        (when (or (scenarios "all") (scenarios "typing")) (run-typing! tui writes ed 20))
        (when (or (scenarios "all") (scenarios "stream")) (run-stream! tui writes ch 40))
        (when (or (scenarios "all") (scenarios "redraw")) (run-redraw! tui writes vt 4))
        (when (scenarios "reflow") (run-reflow! tui writes 2))
        (finally
          (core/tui-stop tui)
          (when-let [f @(:render-loop tui)]
            (let [v (try (deref f 5000 nil) (catch Throwable t t))]
              (when (instance? Throwable v)
                (println "render loop died:" v))))))
      (println "total frames:" @(:frame-count tui)))))

(apply -main *command-line-args*)
