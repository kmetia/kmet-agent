(ns kmet.modes.interactive.status
  "Status display for the interactive mode: the animation timer, the
   working/background status indicator, and the queued steering/follow-up
   message display (pi: showStatusIndicator / clearStatusIndicator /
   updatePendingMessagesDisplay)."
  (:require [clojure.string :as str]
            [kmet.tui.core :as tui]
            [kmet.app.loop :as agent]
            [kmet.app.ui.pending-messages :as pending-messages]
            [kmet.app.ui.status-indicator :as status-indicator]
            [kmet.debug :as debug]))

;; Internal forward references (the timer teardown paths).
(declare cancel-indicator-driver! clear-status-indicator!)

;; ─── Animation timer ────────────────────────────────────────────────────────
;; Drives re-renders while the agent turn is running, so the separate
;; StatusIndicator (Pi-style) between chat and editor animates smoothly.

(defn start-anim-timer!
  "Start requesting renders every 80ms while the agent turn runs.
   Powers the StatusIndicator spinner animation (Pi-style: separate layer
   between chat and editor)."
  [cs]
  (let [t (future
            (try
              (loop []
                (when (and @(:running? (:tui cs))
                           @(:running-turn? cs))
                  (Thread/sleep 80)
                  (tui/tui-request-render (:tui cs))
                  (recur)))
              ;; The timer is stopped via future-cancel — the interrupt it
              ;; raises on Thread/sleep is expected, not an error.
              (catch InterruptedException _)
              (catch Exception e
                (debug/log "anim timer: " e))))]
    (reset! (:anim-timer cs) t)))

(defn- start-indicator-driver!
  "Request renders every 80ms while a TRANSIENT status indicator is up.
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
  (future
    (try
      (loop []
        (Thread/sleep 80)
        (when (and (some-> (:tui cs) :running? deref)
                   (identical? indicator (:indicator @(:status-current cs))))
          (tui/tui-request-render (:tui cs))
          (recur)))
      ;; cancelled via future-cancel — the interrupt raised on Thread/sleep
      ;; is expected, not an error
      (catch InterruptedException _)
      (catch Exception e
        (debug/log "indicator driver: " e)))))

(defn- cancel-indicator-driver!
  "Cancel the current status entry's frame driver (idempotent; read the
   entry before clearing :status-current so the driver is still there to
   cancel)."
  [cs]
  (when-some [t (:driver @(:status-current cs))]
    (future-cancel t))
  nil)

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
   an 80ms frame driver (start-indicator-driver!; during turns the anim
   timer already drives frames)."
  [cs kind indicator]
  (cancel-indicator-driver! cs)
  (status-indicator/status-indicator-stop! (:status-indicator cs))
  (reset! (:status-current cs)
          {:kind kind
           :indicator indicator
           :driver (start-indicator-driver! cs indicator)}))

(defn activate-working-indicator!
  "Restore the default working StatusIndicator as the current status and
   activate it (pi: agent_start → showStatusIndicator(new
   WorkingStatusIndicator)). Used when a new LLM call starts after a retry
   backoff or compaction, which swapped in a transient indicator."
  [cs]
  ;; The nil swap schedules the frame through the status-area root's
  ;; reaction when a transient indicator was shown; start-agent-run! (the
  ;; one cold-start path where current is already nil) requests its own
  ;; frame right after, covering the spinner activation. The transient
  ;; indicator's frame driver stops — the working spinner animates via the
  ;; anim timer once the turn runs.
  (cancel-indicator-driver! cs)
  (reset! (:status-current cs) nil)
  (status-indicator/status-indicator-start! (:status-indicator cs))
  ;; A background thread (share/branch-summary completion, an extension's
  ;; set-working-visible) can revive into a turn that just ended: teardown
  ;; (on-agent-done/on-agent-error) drops running-turn? BEFORE clearing
  ;; the status, so an activation interleaved with it can land after the
  ;; clear. Never leave a spinner with no turn behind it.
  (when-not @(:running-turn? cs)
    (status-indicator/status-indicator-stop! (:status-indicator cs))))

(defn release-background-status!
  "Release a long-running background status indicator (share, branch
   summarization) once its work finishes, and give the working spinner
   back while the agent turn is still streaming — taking the status slot
   stopped it, and the next :turn-start (the only other revival point) can
   be arbitrarily far away.

   IDENT is the indicator the flow installed: only it may be cleared, so a
   later /share or a transient (retry/compaction) that claimed the slot
   meanwhile is left alone. The working spinner returns only when the turn
   is still running, nothing else claimed the slot in the meantime, and it
   isn't already spinning (re-activation would reset its animation
   clock). The activation itself re-checks the running-turn flag, so a
   turn ending mid-revival cannot leave a spinner behind."
  [cs kind ident]
  (when (identical? ident (:indicator @(:status-current cs)))
    (clear-status-indicator! cs kind)
    (when (and @(:running-turn? cs)
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
  (let [{:keys [steering follow-up]} (agent/queued-messages @(:agent-state cs))
        cq @(:compaction-queued cs)
        c-steer (mapv :text (filter #(= :steer (:mode %)) cq))
        c-follow (mapv :text (filter #(= :follow-up (:mode %)) cq))]
    ;; set-queues! swaps track!-watched atoms — the watch invalidates the
    ;; component and schedules the frame (§3.4); no manual poke.
    (pending-messages/pending-messages-set-queues! (:pending-messages-comp cs)
                                                   (into (vec steering) c-steer)
                                                   (into (vec follow-up) c-follow))))
