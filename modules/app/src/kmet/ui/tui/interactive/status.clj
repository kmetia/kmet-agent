(ns kmet.ui.tui.interactive.status
  "Status display for the interactive mode: the animation timer, the
   working/background status indicator, and the queued steering/follow-up
   message display (pi: showStatusIndicator / clearStatusIndicator /
   updatePendingMessagesDisplay)."
  (:require [clojure.string :as str]
            [kmet.tui.core :as tui]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.timers :as timers]
            [kmet.app.session.runtime :as runtime]
            [kmet.ui.tui.pending-messages :as pending-messages]
            [kmet.ui.tui.status-indicator :as status-indicator]
            [kmet.app.debug :as debug]))

;; Internal forward references (the timer teardown paths).
(declare cancel-indicator-driver! clear-status-indicator!)

;; ─── Animation timer ────────────────────────────────────────────────────────
;; Drives re-renders while the agent turn is running, so the separate
;; StatusIndicator (Pi-style) between chat and editor animates smoothly.

(defn- frame-driver!
  "A future requesting a render every spinner/default-interval-ms — the
   animation bucket — while (ACTIVE? TUI) holds, then exiting. The request
   is quiet-gated (timers/frame-due?): when streaming output, a keystroke
   or the elapsed ticker painted a frame within the last half bucket, that
   frame already carries the current animation frame, so the driver skips
   its request instead of buying another whole-tree walk. Frames are a pure
   function of elapsed time, so a closed gate delays a sample but never
   drops one from the cycle. TAG labels unexpected failures. Stop the
   driver with future-cancel: the interrupt it raises on Thread/sleep is
   expected, not an error."
  [tui active? tag]
  (future
    (try
      (loop []
        (when (active? tui)
          (Thread/sleep spinner/default-interval-ms)
          (when (timers/frame-due? spinner/default-interval-ms)
            (tui/tui-request-render tui))
          (recur)))
      (catch InterruptedException _)
      (catch Exception e
        (debug/log tag e)))))

(defn start-anim-timer!
  "Start requesting renders every 240ms while the agent turn runs — the
   spinner's animation bucket (spinner/default-interval-ms), quiet-gated
   (timers/frame-due?): a frame painted by streaming output, a keystroke or
   the elapsed ticker within the last half bucket already carries the
   current animation frame, so the driver skips instead of buying another
   whole-tree walk. When the screen is otherwise quiet, sampling at the
   bucket shows every braille frame exactly once (the earlier 80ms beat
   oversampled the then-100ms bucket: 12.5 requests/s for 10 distinct
   frames, one of them held 160ms). Powers the StatusIndicator spinner
   animation (Pi-style: separate layer between chat and editor)."
  [cs]
  (reset! (:anim-timer cs)
          (frame-driver! (:tui cs)
                         (fn [tui] (and @(:running? tui) (runtime/turn-running? (:engine cs))))
                         "anim timer: ")))

(defn- start-indicator-driver!
  "Request renders every 240ms while a TRANSIENT status indicator is up —
   the elapsed-frame bucket (spinner/default-interval-ms; frame-at), so
   each quiet wake is a distinct frame; the quiet gate skips a wake that
   another source's frame (streaming output, a keystroke) already covers.
   Covers indicators shown outside agent turns (manual /compact, /share)
   — the elapsed-time/countdown indicators render from wall-clock time per
   pass and otherwise sit on a single static frame when no anim timer is
   running (during turns the anim timer already drives frames). The future
   is recorded on the indicator's :status-current entry: the driver's
   lifetime is the indicator's, so there is no separate cell to initialize
   (or forget) and a clear cancels exactly the driver it owns. Self-exits
   when the TUI stops or the indicator it was started for is no longer
   current; cancel-indicator-driver! stops it eagerly on a clear/swap."
  [cs indicator]
  (frame-driver! (:tui cs)
                 (fn [tui] (and @(:running? tui)
                                (identical? indicator (:indicator @(:status-current cs)))))
                 "indicator driver: "))

(defn- cancel-indicator-driver!
  "Cancel the current status entry's frame driver (idempotent; read the
   entry before clearing :status-current so the driver is still there to
   cancel)."
  [cs]
  (when-some [t (:driver @(:status-current cs))]
    (future-cancel t))
  nil)

(defn- dispose-transient-indicator!
  "Dispose the transient indicator currently recorded in :status-current —
   a Retry/Compaction/BranchSummary/share indicator being replaced or
   cleared. Never touches the working indicator: the implicit status has no
   :status-current entry and is reused, not dropped."
  [cs]
  ;; tolerant verb: a foreign indicator shape still disposes instead of
  ;; throwing into the swap
  (when-let [indicator (:indicator @(:status-current cs))]
    (protocols/dispose-component! indicator)))

(defn stop-anim-timer!
  "Cancel the animation timer."
  [cs]
  (when-let [t @(:anim-timer cs)]
    (future-cancel t)
    (reset! (:anim-timer cs) nil)))

;; ─── Status indicator swap model (pi: showStatusIndicator/clearStatusIndicator) ──
;; The status layer is a fn component (status-indicator/make-status-area) mounted via
;; hiccup/root: it renders whichever indicator the :status-current atom
;; records ({:kind k :indicator c :driver f}), or the default working
;; StatusIndicator when nil — except while the active editor embeds the
;; status in its own top border (the default editor), when the layer renders
;; nothing. A swap
;; is a pure reset! on that atom — reconcile diffs the tree and swaps the
;; child record; no container clear/add dance. The working indicator's
;; start/stop stays imperative (spinner lifecycle, dsl.md §5). The kind
;; rides in the recorded map so a stale end event can't stop an indicator
;; that was already replaced (pi: clearStatusIndicator(kind) checks the
;; active kind and no-ops on mismatch).

(defn current-status-indicator
  "The indicator the session is showing right now: the transient swap
   recorded in :status-current when one is up, else the default working
   indicator while it is active, else nil (the editor border then draws its
   plain rule). The editor's top-border hook resolves this per render."
  [cs]
  (or (:indicator @(:status-current cs))
      (when (status-indicator/status-indicator-active? (:status-indicator cs))
        (:status-indicator cs))))

(defn show-status-indicator!
  "Record INDICATOR as the active status (pi: showStatusIndicator —
   disposes the active indicator). KIND records which indicator is active
   for kind-gated clears. No manual render request for the SWAP itself —
   the tracked :status-current read schedules that frame (§3.4) — but the
   transient indicators animate from wall-clock time, so the entry carries
   a 240ms frame driver (start-indicator-driver!; during turns the anim
   timer already drives frames)."
  [cs kind indicator]
  (cancel-indicator-driver! cs)
  (dispose-transient-indicator! cs)
  (status-indicator/status-indicator-stop! (:status-indicator cs))
  ;; Install the entry BEFORE starting its driver: the driver's liveness
  ;; predicate reads :status-current, so a driver scheduled between the
  ;; start and the swap would see the previous entry (or nil), consider
  ;; itself superseded, and exit immediately — leaving the indicator with
  ;; no frame driver (a spinner stuck on one frame).
  (reset! (:status-current cs) {:kind kind :indicator indicator :driver nil})
  (swap! (:status-current cs) assoc :driver (start-indicator-driver! cs indicator)))

(defn activate-working-indicator!
  "Restore the default working StatusIndicator as the current status and
   activate it (pi: agent_start → showStatusIndicator(new
   WorkingStatusIndicator)). Used when a new LLM call starts after a retry
   backoff or compaction, which swapped in a transient indicator."
  [cs]
  ;; The nil swap schedules the frame through the status-area root's
  ;; reaction when a transient indicator was shown; the :agent-start handler
  ;; calls this for every run (the editor's, an extension's, the compaction
  ;; flush's). The transient indicator's frame driver stops — the working
  ;; spinner animates via the anim timer once the turn runs.
  (cancel-indicator-driver! cs)
  (dispose-transient-indicator! cs)
  (reset! (:status-current cs) nil)
  (status-indicator/status-indicator-start! (:status-indicator cs))
  ;; A background thread (share/branch-summary completion, an extension's
  ;; set-working-visible) can revive into a turn that just ended: the run
  ;; settles its turn flag before the teardown event reaches the UI, and the
  ;; teardown clears the status, so an activation interleaved with it can
  ;; land after the clear. Never leave a spinner with no turn behind it.
  (when-not (runtime/turn-running? (:engine cs))
    (status-indicator/status-indicator-stop! (:status-indicator cs))))

(defn show-working-phase!
  "Name the run phase in the working spinner: :waiting-for-model while the
   agent waits for the model's first token, :working for everything else
   (streaming text or thinking, tool execution). Only the silence is named
   because the transcript already shows what the model produces — thinking
   text, the response, the tool boxes — while nothing on screen shows the
   wait. An extension message (:set-working-message) owns the line while it
   is set, so the phase stays invisible then."
  [cs phase]
  (when-some [indicator (:status-indicator cs)]
    (status-indicator/status-indicator-set-phase! indicator phase)))

(defn release-background-status!
  "Release a long-running background status indicator (share, branch
   summarization) once its work finishes, and give the working spinner
   back while the agent turn is still streaming — taking the status slot
   stopped it, and the next :turn-start (the only other revival point) can
   be arbitrarily far away.

   KIND scopes the release (pi: clearStatusIndicator(kind) — the active
   indicator's kind must match): a later transient (retry/compaction) that
   claimed the slot keeps it, while an indicator the same flow re-showed —
   a branch-summarization retry re-shows the branch-summary indicator
   after its retry countdown — is still released. The working spinner
   returns only when the turn is still running, nothing else claimed the
   slot in the meantime, and it isn't already spinning (re-activation
   would reset its animation clock). The activation itself re-checks the
   running-turn flag, so a turn ending mid-revival cannot leave a spinner
   behind."
  [cs kind]
  (when (= kind (:kind @(:status-current cs)))
    (clear-status-indicator! cs kind)
    (when (and (runtime/turn-running? (:engine cs))
               (nil? @(:status-current cs))
               (not (status-indicator/status-indicator-active? (:status-indicator cs))))
      (activate-working-indicator! cs))))

(defn clear-status-indicator!
  "Clear the active status: the standalone layer falls back to the idle
   two-row shape; with the status embedded in the editor border the border
   returns to its plain rule (pi: clearStatusIndicator → idleStatus).
   With KIND, only clears when that indicator is currently active — a stale
   end event (e.g. auto-retry-end arriving after the working indicator was
   revived) then no-ops instead of stopping the working spinner. The
   working indicator is the IMPLICIT status (no :status-current entry), so
   a :working clear matches exactly when no transient is swapped in —
   pi: the active indicator's kind is \"working\" during a turn, and
   setWorkingVisible(false) clears it; a transient retry/compaction/share
   indicator is left alone."
  [cs & [kind]]
  (let [current @(:status-current cs)]
    (when (or (nil? kind)
              (= kind (:kind current))
              (and (= :working kind) (nil? current)))
      ;; A real swap (transient → idle) schedules its own frame through the
      ;; status-area root reaction; an already-idle clear needs no frame.
      (cancel-indicator-driver! cs)
      (dispose-transient-indicator! cs)
      (reset! (:status-current cs) nil)
      (status-indicator/status-indicator-stop! (:status-indicator cs)))))

;; ─── Pending messages display (pi: updatePendingMessagesDisplay) ──────────

(defn fmt-key-display
  "Pi: formatKeyText capitalize — 'alt+up' → 'Alt+Up'."
  [k]
  (->> (str/split (or k "") #"\+")
       (map (fn [part]
              (if (seq part)
                (str (str/upper-case (subs part 0 1)) (subs part 1))
                part)))
       (str/join "+")))

(defn update-pending-messages!
  "Refresh the queued steering/follow-up display (pi:
   updatePendingMessagesDisplay). Combines the agent's steering/follow-up
   queues with the compaction queue (pi: getAllQueuedMessages — messages
   queued during compaction display alongside the session queue)."
  [cs]
  (let [{:keys [steering follow-up]} (runtime/queues (:engine cs))
        cq (runtime/compaction-queued (:engine cs))
        c-steer (mapv :text (filter #(= :steer (:mode %)) cq))
        c-follow (mapv :text (filter #(= :follow-up (:mode %)) cq))]
    ;; set-queues! swaps track!-watched atoms — the watch invalidates the
    ;; component and schedules the frame (§3.4); no manual poke.
    (pending-messages/pending-messages-set-queues! (:pending-messages-comp cs)
                                                   (into (vec steering) c-steer)
                                                   (into (vec follow-up) c-follow))))