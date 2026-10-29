(ns kmet.app.session.runtime
  "The session engine: the value a front end drives, and the accessors it reads
   and writes through.

   An `Engine` holds the agent state, the session, the config-derived run
   options and the front end's callbacks. Production builds one through the
   agent factory (`kmet.app.loop.driver`'s handle); a test that drives a bare
   Engine builds the record directly (`kmet.test-utils/test-engine`). This
   namespace is what the drivers and the front ends read: the session and its
   cwd, the model and thinking level, the run options, the queues, the turn
   flag.

   Everything here works on the value and the app's services; nothing here starts
   work. That is what lets the UI layer require it: a front end reads an engine
   (session, model, thinking level) without reaching the driver, which owns the
   turns and sits above the UI's module.

   The flag discipline is the one rule worth reading twice: `turn-running?` is
   set synchronously when a prompt starts, so a second submission steers instead
   of starting a concurrent run — cleared by the driver's cancel and settle
   paths, never by a reader."
  (:require
   [babashka.fs :as fs]
   [kmet.app.config :as cfg]
   [kmet.app.events :as events]
   [kmet.app.loop.stages :as loop-stages]
   [kmet.app.loop.state :as loop-state]
   [kmet.app.model-resolver :as resolver]
   [kmet.app.session.core :as session]
   [kmet.app.session.export :as session-export]
   [kmet.ai.models :as models]
   [kmet.srce.core :as srce]))

(declare agent-state compaction-queued config cwd model pending-compaction session system-prompt turn-running?)

(defrecord Engine [agent-state session config subscribers last-run disposed?
                   cwd expand-input turn-running? compaction-queued
                   pending-compaction pending-ui run-callbacks])

(defn agent-state
  "The live AgentState record. The engine holds it in an atom because a
   session switch replaces the record (the mode assoc's :session and resets
   the atom) — the layout's CoreState :agent-state is this same atom. nil
   for a bare test engine without one."
  [engine]
  (some-> engine :agent-state deref))

(defn agent-ctx
  "The ctx the engine's agent runs on: the app root by default, a child ctx
   when the engine was bootstrapped onto one; nil for a bare test engine
   without agent state."
  [engine]
  (:ctx (agent-state engine)))

(defn shell
  "The run's `:shell` service value on the engine's ctx —
   `{:shell-path … :command-prefix …}` — or nil when the composition did not
   mount the `:shell` row. The TUI's `!` path binds it so its bash uses the
   same shell an agent run would (docs/architecture.md § Service catalog)."
  [engine]
  (some-> (agent-ctx engine) (srce/get :shell) deref))

(defn tool-table
  "The engine's tool table (name → Tool record) — the one its agent resolves
   through, from the ctx's `:tools` service. Front ends read tools here; the
   loop's own state stays the loop's."
  [engine]
  (some-> (agent-state engine) loop-state/tool-table))

(defn session
  "The live session (an atom's value; a session switch replaces it). nil
   for a context-less engine or a bare test engine.

   The engine keeps it in an atom; a handle (`kmet.app.loop.driver`) keeps a
   thunk, because its live state record is replaced on a session switch. Both
   move, and a front end reads either through here."
  [engine]
  (let [s (:session engine)]
    (if (fn? s) (s) (some-> s deref))))

(defn ensure-cwd-session-dir
  "The cwd-encoded sessions dir for CWD under BASE-DIR, created if missing
   (pi: getDefaultSessionDir — per-project isolation). BASE-DIR is the
   config's sessions dir; CWD is the process cwd at startup, or the
   session's runtime cwd after a switch, so a session resumed from another
   project keeps creating its sessions in that project's dir."
  [base-dir cwd]
  (let [dir (session/session-dir-for-cwd base-dir cwd)]
    (fs/create-dirs dir)
    dir))

(defn find-recent-session
  "The most recent session file for CWD under BASE-DIR, or nil (pi:
   continueRecent → findMostRecentSession). Header-based discovery: legacy
   headerless sessions and other-cwd files are excluded."
  [base-dir cwd]
  (session/find-most-recent-session (ensure-cwd-session-dir base-dir cwd) cwd))

(defn config
  "The live process config (the engine holds it in an atom because /reload
   replaces it)."
  [engine]
  (some-> engine :config deref))

(defn refresh-loop-stages!
  "Re-seat the agent's own loop stage map with the builtin defaults — the
   /reload path: a reloaded guard or retry stage reaches the next attempt, and
   an in-flight attempt keeps the map it read. A ctx that carries no stage atom
   of its own is left alone."
  [engine]
  (loop-stages/refresh! (:ctx (agent-state engine))))

(defn set-config!
  "Replace the live process config (/reload re-reads settings.edn and
   extensions); session-dir and run-option lookups follow it."
  [engine config]
  (reset! (:config engine) config)
  nil)

(defn cwd
  "The runtime working directory (pi: AgentSession._cwd — the resumed
   session's recorded cwd, else the process cwd at bootstrap). nil for a
   bare test engine without a :cwd atom."
  [engine]
  (some-> engine :cwd deref))

(defn entries
  "The live session's entries (the session's own atom)."
  [engine]
  (some-> (session engine) :entries deref))

(defn messages
  "The agent's in-memory context messages (pi: session.messages — what the
   next request sends, compaction summaries and context edits applied)."
  [engine]
  (loop-state/get-context (agent-state engine)))

(defn set-session-name!
  "Set the session display name (the /name builtin and RPC set_session_name
   share this): sanitize, append the session_info entry, emit
   :session-info-changed. Returns the sanitized name."
  [engine name]
  (let [sess (session engine)
        sanitized (session/sanitize-session-name name)]
    (session/append-session-info! sess sanitized)
    (events/emit! (agent-ctx engine)
                  {:type :session-info-changed
                   :session-file (:file sess)
                   :name sanitized})
    sanitized))

(defn export-html!
  "Export the live session to HTML (the /export builtin and RPC export_html
   share this). PATH, or the session's runtime cwd default; the engine's
   system prompt and tool definitions are embedded (pi: exportSessionToHtml
   state). Returns the written path; throws when there is nothing to
   export."
  [engine {:keys [path]}]
  (let [ag (agent-state engine)
        system-prompt (or @(:system-prompt-override ag) @(:system ag))
        tool-defs (vals (loop-state/tool-table ag))]
    (session-export/export-to-html!
     (session engine)
     (cond-> (when path {:path path})
       (not path) (assoc :cwd (cwd engine))
       system-prompt (assoc :system-prompt system-prompt)
       (seq tool-defs) (assoc :tools tool-defs)))))

(defn snapshot
  "A plain-data view of the engine: the session, the agent's status /
   model / provider / thinking, the queue state (including the batch modes
   and auto-compaction flag), and the last outcome. Fields are read from
   independent atoms, so the snapshot is not one atomic cut — display
   consumers are fine with that."
  [engine]
  (let [agent-state (agent-state engine)
        cfg @(:cfg agent-state)]
    {:session (session engine)
     :status @(:status agent-state)
     :model @(:model agent-state)
     :provider @(:provider agent-state)
     :thinking @(:thinking agent-state)
     :scoped-models (vec @(:scoped-models agent-state))
     :compacting? (boolean @(:compacting? agent-state))
     :steering-mode (:steering-mode cfg)
     :follow-up-mode (:follow-up-mode cfg)
     :auto-compact (:auto-compact cfg)
     :queues (loop-state/queued-messages agent-state)
     :last-outcome @(:last-outcome agent-state)}))

(defn last-outcome
  "The outcome of the last settled prompt (:completed | :error | :aborted)."
  [engine]
  @(:last-outcome (agent-state engine)))

(defn turn-running?
  "True while a prompt started by `prompt!` has not settled. The runtime
   owns the flag: it is set synchronously by `prompt!` (or a handle's
   `start!`) so a second submission steers instead of starting a concurrent
   run, and cleared by `cancel!` and by the settle path — `prompt!`'s wrapped
   :on-done/:on-error for an engine, a handle's `settle!` before it emits
   :agent-settled — so a callback interleaved with a background status revival
   can never see a settled turn as running."
  [engine]
  (boolean (some-> engine :turn-running? deref)))

(defn queues [engine] (loop-state/queued-messages (agent-state engine)))

(defn clear-queues!
  "Drop the steering/follow-up queues (the restore-to-editor path clears
   them before cancelling); the loop emits the matching :queue-update."
  [engine]
  (loop-state/clear-queues! (agent-state engine)))

(defn compaction-queued
  "Messages queued while a compaction was running, in order
   ({:text s :mode :steer|:follow-up} — pi: compactionQueuedMessages);
   flushed by flush-compaction-queue! when the compaction ends."
  [engine]
  (or (some-> engine :compaction-queued deref) []))

(defn pending-compaction
  "Manual compaction requests waiting to run, in order
   ({:custom-instructions s-or-nil :on-complete f :on-error f} — see
   request-compaction!): a request that arrived while a prompt was running
   waits for the settle, an idle one only for the drain to start."
  [engine]
  (or (some-> engine :pending-compaction deref) []))

(defn set-model! [engine model] (loop-state/set-model! (agent-state engine) model))

(defn cycle-model!
  "Cycle the session model forward (1-arity) or in a direction (-1/1)."
  ([engine] (cycle-model! engine 1))
  ([engine direction] (loop-state/cycle-model! (agent-state engine) direction)))

(defn set-thinking-level! [engine level] (loop-state/set-thinking-level! (agent-state engine) level))

(defn cycle-thinking-level!
  "Advance the thinking level through the current model's levels; returns
   the new level, or nil when the model offers fewer than two."
  [engine]
  (loop-state/cycle-thinking-level! (agent-state engine)))

(defn set-auto-compact! [engine enabled?] (loop-state/set-auto-compact! (agent-state engine) enabled?))

(defn set-steering-mode! [engine mode] (loop-state/set-steering-mode! (agent-state engine) mode))

(defn set-follow-up-mode! [engine mode] (loop-state/set-follow-up-mode! (agent-state engine) mode))

(defn set-block-images!
  "Block or allow image content in provider requests (the /settings row and
   /reload re-applying the config value)."
  [engine blocked?]
  (loop-state/set-block-images! (agent-state engine) blocked?))

(defn scoped-models [engine] @(:scoped-models (agent-state engine)))

(defn set-scoped-models! [engine models]
  (loop-state/set-scoped-models! (agent-state engine) models))

(defn model
  "The current model id (a string), as stored on the agent state."
  [engine]
  @(:model (agent-state engine)))

(defn provider
  "The current provider keyword."
  [engine]
  @(:provider (agent-state engine)))

(defn model-record
  "The resolved Model record for the current provider/model, or nil when
   the registry does not know it."
  [engine]
  (models/get-model (provider engine) (model engine)))

(defn thinking-level
  "The current thinking level."
  [engine]
  @(:thinking (agent-state engine)))

(defn extension-context
  "The ExtensionContext keys that describe the live agent: the model record,
   the scoped set the ctx shows (not the auth-filtered cycling view), and the
   thinking level. Every front end's `:build-context` supplies them and a run
   binds them, so the rule has one definition rather than one per front end."
  [engine]
  {:model (model-record engine)
   :scoped-models (resolver/scoped-models-for-context
                   (scoped-models engine)
                   (provider engine))
   :thinking-level (thinking-level engine)})

(defn compacting?
  "True while a compaction is in flight."
  [engine]
  (boolean @(:compacting? (agent-state engine))))

(defn compaction-error
  "The last compaction failure message, or nil."
  [engine]
  @(:compaction-error (agent-state engine)))

(defn has-queued-messages?
  "True when the steering or follow-up queue holds messages."
  [engine]
  (boolean (loop-state/has-queued-messages? (agent-state engine))))

(defn cancel-signal
  "The current cancel signal value (extension ctx.signal)."
  [engine]
  @(:signal (agent-state engine)))

(defn system-prompt
  "The live system prompt text."
  [engine]
  @(:system (agent-state engine)))

(defn set-system-prompt!
  "Replace the live system prompt and its build options (the /reload
   rebuild path)."
  [engine prompt opts]
  (let [ag (agent-state engine)]
    (reset! (:system ag) prompt)
    (reset! (:system-prompt-opts ag) opts))
  nil)

(defn run-options
  "The live runtime-tunable run options (the agent's cfg map: retry delays,
   steering/follow-up modes, loop guard, context window, ...). Each call
   reads the current values."
  [engine]
  @(:cfg (agent-state engine)))

(defn active-tools
  "The LLM-visible active tool set (registry maps filtered by the enabled
   and default-tool selections)."
  [engine]
  (loop-state/active-tools (agent-state engine)))

(defn apply-default-tools!
  "Re-apply a :default-tools selection after a settings reload; returns the
   new selection."
  [engine selection]
  (loop-state/apply-default-tools! (agent-state engine) selection))

(defn add-context-message!
  "Append a context-only message (not persisted to the session) to the
   agent's in-memory context."
  [engine msg]
  (loop-state/add-context-message! (agent-state engine) msg))

(defn add-bash-result!
  "Record a !/!! bash execution result in the context and session (deferred
   while the agent streams; flushed when the run settles)."
  [engine command result exclude-from-context?]
  (loop-state/add-bash-result! (agent-state engine) command result exclude-from-context?))

(defn set-http-idle-timeout-ms!
  "Set the SSE idle timeout live (0 disables)."
  [engine ms]
  (loop-state/set-http-idle-timeout-ms! (agent-state engine) ms))

(defn set-http-total-timeout-ms!
  "Set the whole-request total deadline live (nil follows the idle
   timeout; 0 disables it)."
  [engine ms]
  (loop-state/set-http-total-timeout-ms! (agent-state engine) ms))

(defn set-run-options!
  "Merge M into the runtime-tunable run options (retry delays, loop-guard
   thresholds, the context window, ...)."
  [engine m]
  (swap! (:cfg (agent-state engine)) merge m)
  nil)

(defn switch-model!
  "Switch the session model with the position-preserving thinking rule
   (kmet rank rule — see kmet.app.loop.state/switch-thinking-level), persist it
   as the settings default, and return the resulting thinking level.
   EXPLICIT-LEVEL wins when given. The /model + selector + extension
   capability path; set-model! applies the model alone."
  [engine model explicit-level]
  (let [ag (agent-state engine)
        old-model (models/get-model @(:provider ag) @(:model ag))
        level (loop-state/switch-thinking-level old-model model @(:thinking ag) explicit-level)]
    (loop-state/set-model! ag model)
    (loop-state/set-thinking-level! ag level)
    (cfg/set-default-model! (:provider model) (:id model))
    level))
