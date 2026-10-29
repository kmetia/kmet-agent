(ns kmet.app.session.lifecycle
  "The session lifecycle on an engine: new, resume, switch, fork, clone and
   branch, with the settle/event sequence around them.

   Below the drivers. `kmet.app.loop.driver`'s handle (production) and the
   bare-engine tests (`kmet.test-utils/test-engine`) run these same fns; what
   a front end does *around* a switch — its UI cleanup, its extension install —
   stays the front end's.

   The split that matters is `:install!`: kmet1 installs the switched session
   into the process-wide extension slot (`extensions/install-session!`), and a
   handle passes none, because a process-global slot is a front end's and a
   second agent in one process must not take it. A run's extension surface
   reads the run's own binding, so a handle loses nothing.

   The engine's session slot is an atom for a bootstrapped engine and a thunk
   for a handle (whose live state record is replaced on a switch); the swap
   moves whichever holds it."
  (:require
   [babashka.fs :as fs]
   [kmet.app.config :as cfg]
   [kmet.app.debug :as debug]
   [kmet.app.events :as events]
   [kmet.app.extensions.core :as extensions]
   [kmet.app.loop :as agent]
   [kmet.app.loop.state :as loop-state]
   [kmet.app.session.core :as session]
   [kmet.app.session.runtime :as runtime]
   [kmet.app.skills :as skills]
   [kmet.libs.concurrent :as concurrent]))

(defn- refresh-prompt-cwd!
  "Point the system prompt's `Current working directory` line at CWD (the
   session's runtime cwd). Project context files are NOT re-read: everything
   project-scoped — settings.edn, context files, project extensions/skills/
   prompts/themes/packages — stays with the launch directory, whatever
   session is imported; only the transcript (and the working directory its
   tools run in) crosses the switch."
  [engine cwd]
  (let [ag (runtime/agent-state engine)
        opts (assoc @(:system-prompt-opts ag) :cwd cwd)]
    (reset! (:system-prompt-opts ag) opts)
    (reset! (:system ag) (apply (or (:system-prompt-fn ag) skills/build-system-prompt)
                                (mapcat identity opts)))))

(defn apply-session-cwd!
  "Point the runtime at SESS's working directory (pi: createRuntime's cwd +
   footerDataProvider.setCwd — a resumed or imported session brings its
   recorded cwd): the system prompt's cwd line and everything reading the
   runtime cwd (footer pwd, extension ctx.cwd, tool resolution) follow it.
   A recorded cwd that no longer exists keeps the current one (the front end
   reports it); returns the cwd in effect."
  [engine sess]
  (let [sess-cwd (session/session-cwd sess)
        current (runtime/cwd engine)
        same? (and sess-cwd current
                   (= sess-cwd (str (fs/normalize (str current)))))]
    (cond
      same? current
      sess-cwd (do (reset! (:cwd engine) sess-cwd)
                   (refresh-prompt-cwd! engine sess-cwd)
                   sess-cwd)
      :else current)))

(defn emit-session-start-async!
  "Emit :session-start away from the input path — pi: AgentSession emits
   session_start on every runtime creation (startup, resume, new, fork) and
   extension handlers may block on dialog promises. The resources_discover
   pass follows (pi: extendResourcesFromExtensions after session_start)."
  [engine payload]
  (future
    (try
      (let [ctx (runtime/agent-ctx engine)]
        (events/emit! ctx payload)
        (extensions/discover-resources! ctx :startup))
      (catch Exception e
        (debug/log "session-start: " e)))))

(defn switch-session!
  "Swap the live session to SESS (the runtime owns the swap; front ends
   keep the UX around it): point the live session at it (the engine's session
   atom, or the agent-state record a handle's thunk reads), replace the
   agent's session and rebuild its in-memory context from the new branch,
   apply the session-derived model/thinking/tool settings when the opts
   :apply-settings? is true, drop the compaction queue and any deferred
   compaction request (both belong to the outgoing session), and point the
   runtime cwd at the session's.

   The opts `:install!` fn, when given, receives SESS after the engine's
   session slot moves (an atom-held one; a thunk-held handle follows its state
   record below) — that is where kmet1 installs the process-wide extension
   session.
   Callers own the before/after lifecycle events. Returns SESS."
  ([engine sess] (switch-session! engine sess nil))
  ([engine sess {:keys [apply-settings? install!]}]
   (let [new-ag (assoc (runtime/agent-state engine) :session sess)]
     ;; a thunk-held session follows the state record below; an atom-held one
     ;; must move before anything reads it
     (when-not (fn? (:session engine))
       (reset! (:session engine) sess))
     (when install! (install! sess))
     (reset! (:compaction-queued engine) [])
     (reset! (:pending-compaction engine) [])
     (reset! (:agent-state engine) new-ag)
     (loop-state/restore-session-context! new-ag)
     (when apply-settings?
       ;; pi: createRuntime builds a fresh session with the config-derived
       ;; tool set — the outgoing session's set-active-tools!/tools-selector
       ;; selection must not carry over (the switch also emits :session-start,
       ;; letting extensions restore their own per-session state).
       (loop-state/set-active-tools! new-ag nil)
       (loop-state/apply-session-settings! new-ag))
     (apply-session-cwd! engine sess)
     sess)))

(defn- extension-cancelled?
  "Emit a before-event (session-before-switch / session-before-fork) from the
   engine's ctx and report whether an extension cancelled the pending
   operation (pi: emitBeforeSwitch/emitBeforeFork → cancel)."
  [ctx event]
  (boolean (:cancel (events/emit! ctx event))))

(defn cancel-run!
  "Cancel the in-flight run, if any (pi: abort): abort the loop's turn, which
   delivers its promise as :cancelled, clears the queues with :reason
   :aborted and settles the run with :outcome :aborted, then drop the turn
   flag. The flag stays set until the abort returns, so a submission arriving
   concurrently queues behind the run being cancelled instead of starting a
   second one over the same agent. Returns nil. Both drivers cancel through
   this."
  [engine]
  (agent/cancel-turn (runtime/agent-state engine))
  (reset! (:turn-running? engine) false)
  nil)

(defn- settle-for-switch!
  "Settle an in-flight turn and compaction before a session swap so the
   aborted work persists to the outgoing session and cannot race the swap;
   waits (bounded, 3s) for the pending bash results to drain and for the
   compaction to finish. Clears the cancel signal when everything settled."
  [engine]
  (let [ag (runtime/agent-state engine)
        was-running (runtime/turn-running? engine)]
    (when was-running
      ;; cancel-run! drops the turn flag too: a cancelled run never runs the
      ;; wrapped :on-done
      (cancel-run! engine))
    (when @(:compacting? ag)
      (reset! (:signal ag) true))
    (let [deadline (+ (concurrent/monotonic-ms) 3000)]
      (loop []
        (when (and (or (seq @(:pending-bash ag)) @(:compacting? ag))
                   (< (concurrent/monotonic-ms) deadline))
          (Thread/sleep 10)
          (recur))))
    (when (and (not @(:compacting? ag))
               (empty? @(:pending-bash ag)))
      (reset! (:signal ag) false))
    was-running))

(defn new-session!
  "Start a fresh session in the runtime cwd (pi: runtimeHost.newSession):
   emit :session-before-switch (extensions may cancel), settle the in-flight
   turn/compaction (dropping a compaction deferred for the outgoing
   session), emit :session-shutdown, create and switch to the new
   session, reset the loop's per-run state, and emit :session-start
   asynchronously. The opts :before-settle fn runs after the cancel check
   and before the settle wait — the front end cancels its own resources
   (e.g. a running bash command) there so their results still land in the
   outgoing session. Returns {:session new} | {:cancelled true}; the front
   end owns UI cleanup (chat, editors, bash widgets)."
  ([engine] (new-session! engine nil))
  ([engine {:keys [before-settle install!]}]
   (let [previous-file (:file (runtime/session engine))]
     (if (extension-cancelled? (runtime/agent-ctx engine)
                               {:type :session-before-switch
                                :reason :new
                                :target-session-file previous-file})
       {:cancelled true}
       (do
         (when before-settle (before-settle))
         ;; a compaction deferred for the outgoing session dies with it: the
         ;; settle below cancels the running turn and would otherwise let the
         ;; :agent-settled drain start the request against the swap
         (reset! (:pending-compaction engine) [])
         (let [was-running (settle-for-switch! engine)]
           ;; pi: teardownCurrent — after the run is settled, tell extensions the
           ;; runtime is being torn down (reason :new, destination session file)
           ;; so they can persist state before the swap.
           (events/emit! (runtime/agent-ctx engine)
                         {:type :session-shutdown :reason :new
                          :target-session-file previous-file})
           (let [cwd (runtime/cwd engine)
                 sessions-dir (cfg/get-session-dir (runtime/config engine))
                 new-session (session/create-session (runtime/ensure-cwd-session-dir
                                                      sessions-dir cwd)
                                                     {:cwd cwd})]
             (debug/log "new session created: " (:id new-session))
             (switch-session! engine new-session {:apply-settings? false
                                                  :install! install!})
             ;; Rebuild the in-memory context from the new session — empty — and
             ;; reset per-run state (pi: createRuntime builds a fresh agent
             ;; state; the hook/config atoms are kept — not session state).
             (let [ag (runtime/agent-state engine)]
               (reset! (:status ag) :idle)
               (reset! (:steering ag) [])
               (reset! (:follow-up ag) [])
               (reset! (:pending-bash ag) [])
               (reset! (:overflow-recovered ag) false)
               (reset! (:retry-count ag) 0))
             ;; pi: newSession emits session_start (reason "new",
             ;; previousSessionFile) — on a future, handlers may block on dialog
             ;; promises (same as /reload).
             (emit-session-start-async!
              engine
              (cond-> {:type :session-start :reason :new}
                previous-file (assoc :previous-session-file previous-file)))
             {:session new-session :was-running was-running})))))))

(defn resume!
  "Load PATH and switch to it (pi: switchSession, reason :resume): emit
   :session-before-switch (extensions may cancel), load, switch with the
   session-derived settings applied, and emit :session-start asynchronously.
   Returns {:session sess} | {:cancelled true}."
  ([engine path] (resume! engine path nil))
  ([engine path {:keys [install!]}]
   (let [previous-file (:file (runtime/session engine))]
     (if (extension-cancelled? (runtime/agent-ctx engine)
                               {:type :session-before-switch
                                :reason :resume
                                :target-session-file path})
       {:cancelled true}
       (let [sess (session/load-session path)]
         (switch-session! engine sess {:apply-settings? true
                                       :install! install!})
         (emit-session-start-async!
          engine
          (cond-> {:type :session-start :reason :resume}
            previous-file (assoc :previous-session-file previous-file)))
         {:session sess})))))

(defn branch-session!
  "Branch the live session to TARGET-LEAF (nil = the root), optionally with
   a branch SUMMARY-RESULT ({:summary str :usage map}), label the summary
   entry (or the target), rebuild the agent's in-memory context, drop the
   compaction queue (pi: renderCurrentSessionState on session rebind), and
   emit :session-tree. Returns the branch-summary entry or nil; the front
   end keeps the tree UI and replays the new branch into its view."
  [engine {:keys [old-leaf target-leaf summary-result label from-extension?]}]
  (let [sess (runtime/session engine)
        summary-entry (if summary-result
                        (session/branch-with-summary!
                         sess target-leaf (:summary summary-result)
                         (when (:usage summary-result)
                           {:usage (:usage summary-result)}))
                        (do (if (nil? target-leaf)
                              (session/reset-leaf! sess)
                              (session/branch! sess target-leaf))
                            nil))
        label-target (if summary-entry (:id summary-entry) target-leaf)]
    (when (and label label-target)
      (session/set-label! sess label-target label))
    (loop-state/restore-session-context! (runtime/agent-state engine))
    (reset! (:compaction-queued engine) [])
    (reset! (:pending-compaction engine) [])
    (events/emit! (runtime/agent-ctx engine)
                  (cond-> {:type :session-tree
                           :new-leaf-id @(:leaf-id sess)
                           :old-leaf-id old-leaf
                           :from-extension? (boolean from-extension?)}
                    summary-entry (assoc :summary-entry summary-entry)))
    summary-entry))

(defn fork!
  "Fork the session before the user message ENTRY-ID and switch to the fork
   (pi: runtimeHost.fork — the new session starts at the message's parent;
   the front end restores the message text to its editor): emit
   :session-before-fork (extensions may cancel), fork, or create an empty
   session linked to this one when the entry has no parent. Returns
   {:session fork :entry entry} | {:cancelled true} | {:error :unknown-entry}."
  ([engine entry-id] (fork! engine entry-id nil))
  ([engine entry-id {:keys [install!]}]
   (let [sess (runtime/session engine)
         entry (session/get-entry sess entry-id)]
     (cond
       (nil? entry) {:error :unknown-entry}
       (extension-cancelled? (runtime/agent-ctx engine)
                             {:type :session-before-fork
                              :entry-id entry-id
                              :position :at})
       {:cancelled true}
       :else
       (let [cwd (runtime/cwd engine)
             sessions-dir (cfg/get-session-dir (runtime/config engine))
             fork (if (:parent-id entry)
                    (session/fork-session sess (:parent-id entry))
                    (session/create-session (runtime/ensure-cwd-session-dir
                                             sessions-dir cwd)
                                            {:parent-session (:file sess)
                                             :cwd cwd}))]
         (if (nil? fork)
           {:error :fork-failed}
           (do
             (debug/log "forked session " (:id fork) " from " (:id sess))
             (switch-session! engine fork {:apply-settings? false
                                           :install! install!})
             {:session fork :entry entry})))))))

(defn clone!
  "Duplicate the live session at its current position (pi: /clone → fork at
   the current leaf) and switch to the clone: emit :session-before-fork
   (extensions may cancel), clone, switch without applying settings (pi:
   navigateTree keeps the current agent state). Returns
   {:session clone} | {:cancelled true} | {:error :no-session|:no-leaf|
   :not-persisted|:clone-failed}."
  ([engine] (clone! engine nil))
  ([engine {:keys [install!]}]
   (let [sess (runtime/session engine)]
     (cond
       (nil? sess) {:error :no-session}
       (nil? @(:leaf-id sess)) {:error :no-leaf}
       (not (fs/exists? (:file sess))) {:error :not-persisted}
       (extension-cancelled? (runtime/agent-ctx engine)
                             {:type :session-before-fork
                              :entry-id @(:leaf-id sess)
                              :position :at})
       {:cancelled true}
       :else
       (let [clone (session/clone-session sess)]
         (if (nil? clone)
           {:error :clone-failed}
           (do
             (debug/log "cloned session " (:id clone) " from " (:id sess))
             (switch-session! engine clone {:apply-settings? false
                                            :install! install!})
             {:session clone})))))))
