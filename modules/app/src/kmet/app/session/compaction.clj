(ns kmet.app.session.compaction
  "The manual compaction queue: a `/compact` or a wire `compact` request, and
   the deferred ones that wait for a running prompt to settle.

   The queue rides the front end's value (`:pending-compaction`), not this
   namespace, because a session switch drops it and a disposal drops it: what
   belongs to one session cannot outlive the value that names the session.
   The work itself is `kmet.app.compaction.stage/compact-context!`; this
   namespace only orders it — one drain at a time, never under a live turn
   (kmet's cancel is cooperative, so compacting under a running prompt would
   race it), and never after the value was disposed.

   A front end drives it through `:agents` (the handle's `compact!`,
   `abort-compaction!`, `queue-compaction-message!`, `clear-compaction-queue!`);
   the engine path and the loop handle share this implementation, so the
   deferred-completion rule has one copy."
  (:require [kmet.app.compaction :as agent-compact]
            [kmet.app.debug :as debug]
            [kmet.app.session.runtime :as runtime]))

(defn- run-manual-compaction!
  "Run one queued manual compaction request and report it through the
   request's callbacks. A :failed compaction reports through :on-error with
   pi's `Compaction failed: …` message; a completed one (true, or false when
   there was nothing to compact) and :aborted report through :on-complete —
   the :compaction-end event carries the rest to the UI. An exception
   escaping the compaction reports through :on-error too (logged either
   way); one escaping a callback is contained so it cannot kill the drain."
  [engine {:keys [custom-instructions keep-user-turns on-complete on-error]}]
  (let [outcome (try
                  {:result (agent-compact/compact-context!
                            (runtime/agent-state engine) custom-instructions :manual
                            {:keep-user-turns keep-user-turns})}
                  (catch Exception e
                    ;; a future that swallows the exception hides real bugs,
                    ;; so log it (pi: handleCompactCommand catches and ignores
                    ;; — the events carry the failure)
                    (debug/log-error "manual compaction failed: " e)
                    {:error e}))]
    (try
      (if-let [e (:error outcome)]
        (when on-error (on-error e))
        (let [result (:result outcome)]
          (if (= :failed result)
            (when on-error
              (on-error (ex-info (or (runtime/compaction-error engine)
                                     "Compaction failed: Unknown error")
                                 {:type :compaction-failed})))
            (when on-complete (on-complete result)))))
      (catch Exception e
        (debug/log-error "compaction callback failed: " e)))))

(defn start-pending-compactions!
  "Start the manual compactions queued in :pending-compaction — the deferred
   ones from a running prompt and an idle request-compaction! alike. Runs on
   a future so the caller/settle emit stays non-blocking, and serializes the
   drains (locking on the queue): a settle and an idle request can both
   trigger one, and two concurrent compact-context! calls would race — the
   second returns false because one is in flight. When a newer prompt
   already owns the runtime — or a compaction is somehow in flight — the
   requests stay queued for the next settle. A disposed value drops them."
  [engine]
  (when (and (not @(:disposed? engine))
             (seq (runtime/pending-compaction engine)))
    (future
      (let [queue (:pending-compaction engine)]
        (locking queue
          (when-not (or (runtime/turn-running? engine)
                        (runtime/compacting? engine))
            (loop []
              (when-not @(:disposed? engine)
                (let [[claimed _] (swap-vals! queue
                                              (fn [qs] (if (seq qs) (subvec qs 1) qs)))]
                  (when-let [req (first claimed)]
                    (run-manual-compaction! engine req)
                    (recur)))))))))))

(defn request-compaction!
  "Request a manual compaction (CUSTOM-INSTRUCTIONS optional). While the
   runtime is idle the compaction starts immediately; while a prompt is
   still running it is queued and starts when the prompt settles — kmet's
   cancel is cooperative, so compacting under a live run would race it (pi
   instead aborts the run first). OPTS:
     :on-complete — (fn [result]): a completed compaction (true, or false
                    when there was nothing to compact), or :aborted.
     :on-error    — (fn [error]): an exception escaping the compaction, or a
                    :failed compaction with its `Compaction failed: …`
                    message.
     :keep-user-turns — optional explicit cut: keep the last N user turns
                    (0 compacts everything).
   Returns :started (the compaction launched), :scheduled (deferred until
   the running prompt settles), or :compacting (one is already in flight —
   nothing is queued)."
  ([engine] (request-compaction! engine nil nil))
  ([engine custom-instructions opts]
   (if (runtime/compacting? engine)
     :compacting
     (let [scheduled? (runtime/turn-running? engine)]
       (swap! (:pending-compaction engine) conj
              (assoc opts :custom-instructions custom-instructions))
       (when-not scheduled?
         (start-pending-compactions! engine))
       (if scheduled? :scheduled :started)))))

(defn reject-submit!
  "Throw when compaction is in flight: a send cannot queue into one (pi:
   prompt() throws). Shared by the front-end capabilities and the handle's
   `:inject!`, so the rule and its message have one home."
  [value]
  (when (runtime/compacting? value)
    (throw (ex-info "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."
                    {:type :compaction-in-progress})))
  nil)

(defn abort-compaction!
  "Abort the in-flight compaction without cancelling a running turn."
  [engine]
  (agent-compact/abort-compaction! (runtime/agent-state engine)))

(defn queue-compaction-message!
  "Queue TEXT typed during compaction for delivery after it
   (pi: queueCompactionMessage); MODE is :steer or :follow-up."
  [engine text mode]
  (swap! (:compaction-queued engine) conj {:text text :mode mode})
  nil)

(defn clear-compaction-queue!
  "Drop all compaction-queued messages; returns the dropped messages."
  [engine]
  (let [q (runtime/compaction-queued engine)]
    (reset! (:compaction-queued engine) [])
    q))
