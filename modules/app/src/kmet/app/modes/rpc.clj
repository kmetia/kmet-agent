(ns kmet.app.modes.rpc
  "RPC mode as a row: EDN commands on stdin, protocol records on stdout, and the
   run's events as the wire projection (pi: modes/rpc). It is print mode's
   sibling — one agent, one stream, the same `wire/wire-event` — with the
   run driven by commands instead of by a prompt on the command line.

   **A record in, a record out.** A command is a map with a `:type` and an
   optional `:id` the matching response repeats; a command that starts work
   answers `:success true` and the outcome arrives as events, so `:started` is
   not `:settled` and the client waits for `:agent-settled`. A malformed line
   answers a `:parse` failure, a command the table knows nothing about answers
   `\"Unknown command\"`, and one this mode does not implement yet answers the
   landing that fills it — never a silent success, never a wrong answer.

   **The surface is a front end's.** kmet1's rpc mode binds `:mode :rpc`,
   `has-ui true` and a port that answers extension UI calls over the wire: the
   fire-and-forget channels here, dialogs left to landing 10a's command table
   (the `:request`/`:response` pair over the wire: this mode has one agent and no
   dialog correlation, and a bridged dialog that never got an answer would be
   worse than the headless default). The base is the composition's `:ui` port
   (`:bundle/headless`), and the wire's capabilities are assoc'd on top — so a
   profile that patched in a provider changes the answers this mode forwards.

   **The commands are the ones kmet2's services answer.** A prompt, a steer, a
   follow-up, an abort, the live state, the session's reads (`get-entries` with
   its `:since`, the live messages, the last assistant text, the `/session`
   stats, the tree, the HTML export), the model catalog, the model and
   compaction setters, and the command registry (`get-commands`) — each a call
   on the `:agents` handle, the session runtime, or the `:sessions` log. A prompt is the front end's submission chain (`:submit!`): the portable
   slash commands dispatch through the registry first (kmet1's `command-ui`
   over this port's output channels), and everything else runs the extension
   input hooks and the engine's expansion before the handle starts or queues
   it — the wire's queue being durable, the enqueue and delivery facts land in
   the session log, so a reconnect reads the inbox rather than a gap. The
   session moves are the handle's session ops (`new-session`, `switch-session`,
   `fork`, `clone`, `set-session-name`), and each move re-installs the
   process-wide extension session an out-of-run handler reads; the cycles, the
   queue modes and the dialog pair name their landing (`unsupported`) instead
   of pretending. The server row (`kmet.app.modes.server`) serves this same
   protocol over a socket, one agent per connection, out of this namespace's
   `handle-line`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.app.commands :as commands]
            [kmet.app.commands.builtins :as builtins]
            [kmet.app.wire :as wire]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.packages :as packages]
            [kmet.app.prompts :as prompts]
            [kmet.app.session.core :as session]
            [kmet.app.skills :as skills]
            [kmet.app.session.runtime :as runtime]
            [kmet.ai.models :as models]
            [kmet.app.events :as events]
            [kmet.app.front-end :as front-end]
            [kmet.app.invocation :as invocation]
            [kmet.app.modes.out :as out]
            [kmet.app.root :as root]
            [kmet.srce.core :as srce]))

;; ── protocol records ──────────────────────────────────────────────────────

(defn- ok
  "A success response carrying DATA as its `:data` payload; nil DATA omits the
   key (pi's data-less success responses)."
  [command data]
  (cond-> {:type :response :command command :success true}
    (some? data) (assoc :data data)))

(defn- failed
  "A failure response: COMMAND names the command that failed (nil when the
   record carried none), MESSAGE is the reason a client can read."
  [command message]
  {:type :response :command command :success false :error (str message)})

(defn- correlated
  "Attach the request's `:id` when it had one (an absent id stays absent)."
  [record id]
  (cond-> record (some? id) (assoc :id id)))

(defn- message-of
  "The command's non-empty `:message`, or nil."
  [command]
  (let [message (:message command)]
    (when (and (string? message) (not (str/blank? message)))
      message)))

;; ── the extension UI port ────────────────────────────────────────────────

(defn- write-ui-record!
  "An extension UI record — one of the port's fire-and-forget channels. Every
   one carries its own id so a client can correlate or dedupe."
  [out- record]
  (out/record! out- (assoc record :type :ui-request :id (str (random-uuid)))))

(defn- ui-port
  "The RPC UI port: the row's ABI port (`base`, from `:bundle/headless`) plus
   what the protocol carries. Widget content cannot cross the wire, so only nil
   or string lines are forwarded (pi: component factories are ignored) and the
   TUI-only capabilities stay degraded. `:request` stays the row's: a dialog's
   wire pair is landing 10a's command table, not this mode's."
  [base out-]
  (let [set-editor-text (fn [text]
                          (write-ui-record! out- {:method :set-editor-text :text text}))]
    (assoc base
           :notify (fn [message type]
                     (write-ui-record! out- (cond-> {:method :notify :message message}
                                              type (assoc :notify-type type))))
           :set-status (fn [key text]
                         (write-ui-record! out- {:method :set-status
                                                 :status-key key
                                                 :status-text text}))
           :set-widget (fn [key content {:keys [placement]}]
                         (when (or (nil? content)
                                   (and (sequential? content) (every? string? content)))
                           (write-ui-record! out- (cond-> {:method :set-widget
                                                           :widget-key key
                                                           :widget-lines content}
                                                    placement (assoc :widget-placement placement)))))
           :set-title (fn [title]
                        (write-ui-record! out- {:method :set-title :title title}))
           :chat-info (fn [label content]
                        (write-ui-record! out- (cond-> {:method :chat-info :content content}
                                                 label (assoc :label label))))
           :set-editor-text set-editor-text
           ;; pi: pasteToEditor delegates to setEditorText
           :paste-to-editor set-editor-text)))

;; ── commands ──────────────────────────────────────────────────────────────

(defn- enqueue!
  "Queue TEXT for BEHAVIOR — `:steer` for the next step, `:follow-up` for the
   next turn — and answer the disposition it produced. The two targets are the
   loop's queues, but kmet2's delivery rule is start-or-queue: an idle agent
   starts a run whatever the target, so the answer says `:started` rather than a
   `:queued` no run would ever drain. A running agent queues, which is the
   pi-compatible case."
  [{:keys [agents handle]} behavior text]
  (let [running? (runtime/turn-running? handle)]
    ((get agents (if (= :steer behavior) :steer! :follow-up!)) handle text)
    (if running? :queued :started)))

(defn- command-ui
  "The command-UI value the portable builtins receive over the wire: the shared
   capabilities mapped onto the port's output channels (pi: the RPC command
   UI). `:set-title` is absent — a terminal title has no wire meaning; /name's
   reply carries the name."
  [port]
  {:message (fn [text] ((:chat-info port) nil text))
   :info (:chat-info port)
   :warning (fn [text] ((:notify port) text :warning))
   :status (fn [text] ((:set-status port) "status" text))
   :notify (:notify port)})

(defn- dispatch-command
  "A slash-command prompt is the registry's before it is the agent's (pi:
   prompt → the command registry): a command available to :rpc — the portable
   builtins and extension commands, the same filter `get-commands :rpc`
   reports — runs with the command ctx `{:runtime handle :ui command-ui
   :frontend :rpc}` and the prompt answers handled. Returns the handled
   response, or nil when the trimmed text is not a known command: an unknown
   slash stays a normal prompt, where skill and template expansion happen."
  [{:keys [handle command-ui]} text]
  (when-let [{:keys [name args]} (commands/parse-command-line (str/trim text))]
    (when-let [c (commands/find-command name :rpc)]
      (if command-ui
        (do
          (if-let [handler (:extension-handler c)]
            (handler (extensions/build-extension-context) args)
            ((:handler c) {:runtime handle :ui command-ui :frontend :rpc} args))
          (ok :prompt {:disposition :handled}))
        ;; the server row serves this protocol but has no per-connection command
        ;; surface (a per-connection `:ui` port, not scheduled): running a
        ;; command would drop its reply
        (failed :prompt "slash commands are not available on this connection yet")))))

(defn- wire-command
  "One command in the shape `get-commands` reports: its source, and the
   description and argument hint when it has them."
  [source c]
  (cond-> {:name (:name c) :source source}
    (:description c) (assoc :description (:description c))
    (:argument-hint c) (assoc :argument-hint (:argument-hint c))))

(defn- command-get-commands
  "The registry as a client builds a palette from it (docs/rpc.md §
   get-commands): the portable builtins and extension commands this front end
   dispatches, then the prompt templates and skills a prompt expands."
  [_state _command]
  (ok :get-commands
      {:commands (vec (concat (map #(wire-command (if (:extension-handler %) :extension :builtin) %)
                                   (commands/get-commands :rpc))
                              (map #(wire-command :prompt %)
                                   (prompts/as-command-maps (prompts/get-prompt-templates)))
                              (map #(wire-command :skill %)
                                   (skills/as-command-maps (skills/get-skills)))))}))

(defn- command-prompt
  "Start, steer, queue, or handle a prompt (pi: prompt). A slash-command line
   dispatches through the registry first, even mid-run; otherwise, while a run
   is live the client must choose `:streaming-behavior` — pi errors without
   one, because the two targets mean different things and guessing would drop
   the message or deliver it at the wrong boundary.

   The idle prompt is the front end's submission chain (`:submit!`): the
   extension input hooks, the engine's expansion (pi: prompt), then the
   handle's start-or-queue rule. The disposition is what the chain did —
   :started, :handled when a hook consumed the input, :queued when the start
   lost the race and the message queued. `:images` ride the started path and
   are refused if they cannot (a queue carries text)."
  [state command]
  (let [{:keys [agents handle]} state
        behavior (:streaming-behavior command)
        running? (runtime/turn-running? handle)]
    (if-let [text (message-of command)]
      ;; a command never reaches the model, and runs even mid-stream; an
      ;; unknown slash is a prompt (dispatch answers nil)
      (or (dispatch-command state text)
          (cond
            (and (some? behavior) (not (#{:steer :follow-up} behavior)))
            (failed :prompt "streaming-behavior must be :steer or :follow-up")

            (and running? (nil? behavior))
            (failed :prompt (str "The agent is already running; specify"
                                 " :streaming-behavior :steer or :follow-up"))

            running?
            ;; the answer is what the enqueue did: the flag was read a moment
            ;; ago, and a run that settled since then starts the message instead
            (ok :prompt {:disposition (enqueue! state behavior
                                                (packages/expand-input text))})

            :else
            (let [{:keys [action]}
                  ((:submit! agents) handle text
                                     {:expand? true
                                      :mode :rpc
                                      ;; the wire's queue is durable: the
                                      ;; enqueue and delivery facts land in the
                                      ;; session log (kmet1's rpc recorded them
                                      ;; through the registry's send!), so a
                                      ;; reconnect reads the inbox, not a gap
                                      :record? true
                                      :run-opts (when (seq (:images command))
                                                  {:images (:images command)})})]
              ;; the chain's action is the wire's disposition
              (ok :prompt {:disposition action}))))
      (failed :prompt "prompt requires a non-empty :message string"))))

(defn- command-queue
  "The `:steer` / `:follow-up` commands: the loop's queues carry no image
   blocks (pi: steer/followUp are text), so reject `:images` rather than drop
   them, and answer with the disposition the send produced — `:queued` while a
   run is live, `:started` when an idle agent began one (kmet2's delivery rule;
   kmet1 queued and waited). The command's own `:type` is the behavior it
   queues for."
  [state command]
  (let [type (:type command)
        text (some-> (message-of command) packages/expand-input)]
    (cond
      (:images command)
      (failed type (str (name type) " does not support :images yet"))

      (nil? text)
      (failed type (str (name type) " requires a non-empty :message string"))

      :else
      (ok type {:disposition (enqueue! state type text)}))))

(defn- command-abort
  "Cancel the in-flight run and answer whether the agent is idle now (pi:
   abort). An idle abort is a no-op: cancel! would drop nothing and report an
   abort that never happened.

   The answer is a read, not a wait, and a cancel is a request: the run itself
   releases the turn flag in its settle path (after the settle boundary,
   before :agent-settled). An abort of a live run therefore answers whatever
   the flag reads at that moment — `:idle false` while the run unwinds, `:idle
   true` when the delivered cancel settled it first — and either way the client
   waits for `:agent-settled`, the same rule a prompt's `:started` follows. A
   compaction-only abort answers `:idle true`: no run owns the flag.
   A manual compaction is cancelled too, as kmet1's abort did — it is work
   nobody waits for once the client asks to stop — while the answer's `:idle`
   speaks for the turn. kmet1 then waited (bounded) for idle; a wait here would
   be the reader thread's time, which is the one thread an abort must not
   occupy."
  [{:keys [agents handle]} _command]
  (let [working? (or (runtime/turn-running? handle) (runtime/compacting? handle))]
    (when working? ((:cancel! agents) handle {:kind :user}))
    (ok :abort {:idle (not (runtime/turn-running? handle))})))

(defn- pending-count
  "How many messages the agent has queued (the durable inbox, by target)."
  [agents scope handle]
  (reduce + 0 (map count (vals ((:inbox agents) scope handle)))))

(defn- available-model
  "The available (authenticated) model record for PROVIDER + MODEL-ID, or nil
   (pi: modelRuntime.getAvailableSnapshot) — the same snapshot the catalog
   command answers."
  [provider model-id]
  (first (filter #(and (= (keyword provider) (:provider %))
                       (= model-id (:id %)))
                 (models/get-available))))

(defn- command-set-model
  "Switch the agent's model (pi: set_model) — the model must be available
   (authenticated, as in pi's snapshot). The handle writes the agent's own
   state, so a sibling agent's model is untouched."
  [{:keys [agents handle]} command]
  (let [provider (:provider command)
        model-id (:model-id command)]
    (if (and (string? provider) (seq provider)
             (string? model-id) (seq model-id))
      (if-let [model (available-model provider model-id)]
        (do ((:set-model! agents) handle model)
            (ok :set-model model))
        (failed :set-model (str "Model not found: " provider "/" model-id)))
      (failed :set-model "set-model requires :provider and :model-id strings"))))

(defn- command-set-auto-compaction
  "Turn the loop's automatic compaction on or off for this agent
   (pi: set_auto_compaction)."
  [{:keys [agents handle]} command]
  (if (boolean? (:enabled command))
    (do ((:set-auto-compaction! agents) handle (:enabled command))
        (ok :set-auto-compaction nil))
    (failed :set-auto-compaction "set-auto-compaction requires :enabled boolean")))

(defn- command-compact
  "Start a manual compaction (pi: compact), or defer it when a run is in
   flight: accepted is not completed — the response says :accepted (+ :scheduled
   true when the compaction was deferred until the running prompt settles) and
   the outcome arrives as :compaction-end / :session-compact(-failed) events.
   The work runs on a future so the reader stays live."
  [{:keys [agents handle]} command]
  (let [instructions (:custom-instructions command)]
    (cond
      (and (some? instructions) (not (string? instructions)))
      (failed :compact ":custom-instructions must be a string")

      :else
      (case ((:request-compaction! agents) handle instructions nil)
        :compacting (failed :compact "Compaction already in progress")
        :scheduled (ok :compact {:accepted true :scheduled true})
        (ok :compact {:accepted true})))))

(defn- command-get-state
  "The live state a client polls after a reconnect (pi: get-state): the agent's
   model and thinking level, whether a turn is in flight, whether a compaction
   runs, what is queued, how the last prompt settled, and the session it all
   belongs to (its name only when it has one, as pi omits it).

   The values are `runtime/snapshot`'s — one documented view of atoms that move
   under a run — with the wire's field names on them. The pending count is the
   durable inbox's rather than the in-memory queues': a client that reconnects
   asks what is still waiting for it."
  [{:keys [agents scope handle]} _command]
  (let [{:keys [model provider thinking compacting? steering-mode follow-up-mode
                auto-compact last-outcome session]}
        (runtime/snapshot handle)
        sess-name (some-> session session/get-session-name)]
    (ok :get-state
        (cond-> {:model model
                 :provider provider
                 :thinking-level thinking
                 :turn-running? (runtime/turn-running? handle)
                 :compacting? compacting?
                 :steering-mode steering-mode
                 :follow-up-mode follow-up-mode
                 :auto-compaction-enabled (boolean auto-compact)
                 :pending-message-count (pending-count agents scope handle)
                 :last-outcome last-outcome}
          session (assoc :session
                         (cond-> {:id (:id session)
                                  :file (:file session)
                                  ;; message entries only — queue facts and
                                  ;; metadata are the log's, not the
                                  ;; transcript's (kmet1's get-message-count)
                                  :message-count (session/get-message-count session)}
                           ;; pi omits sessionName when unset
                           sess-name (assoc :name sess-name)))))))

(defn- command-get-entries
  "The session's entries, the durable log the run appends (pi: get-entries):
   all of them, or those appended after `:since`. `:leaf-id` is the entry the
   next append chains onto — the branch's tip, which a client that forked or
   switched reads instead of assuming the last entry."
  [{:keys [handle]} command]
  (if-let [sess (runtime/session handle)]
    (let [entries (vec (runtime/entries handle))
          since (:since command)]
      (cond
        (and (some? since) (not (string? since)))
        (failed :get-entries ":since must be an entry id string")

        (and since (not-any? #(= since (:id %)) entries))
        (failed :get-entries (str "Entry not found: " since))

        :else
        (ok :get-entries
            {:entries (if since
                        (vec (rest (drop-while #(not= since (:id %)) entries)))
                        entries)
             :leaf-id @(:leaf-id sess)})))
    (failed :get-entries "No active session")))

(defn- after-move
  "Tell STATE's `:on-session-move` hook about the session a move landed on, when
   it has one, and answer SESS. An rpc row owns the process-wide extension
   session — an out-of-run handler (an extension command over the wire) reads it
   — so it re-installs on every move, as the interactive front end does. A
   server connection passes no hook: one process, many agents, and a slot that
   is process-global is not a connection's to take."
  [{:keys [on-session-move]} sess]
  (when (and sess on-session-move) (on-session-move sess))
  sess)

(defn- command-new-session
  "Start a fresh session in the runtime cwd (pi: new-session). The agent keeps
   serving; the client learns the new session here and through the session
   events. `:was-running` is read before the move, which is what a client needs
   to know the run it had in flight was settled."
  [{:keys [agents handle] :as state} _command]
  (let [was-running (runtime/turn-running? handle)
        result ((:new-session! agents) handle)]
    (if (:cancelled result)
      (ok :new-session {:cancelled true})
      (let [sess (after-move state (:session result))]
        (ok :new-session {:cancelled false
                          :session-id (:id sess)
                          :session-file (:file sess)
                          :was-running was-running})))))

(defn- command-switch-session
  "Load a stored session and switch to it (pi: switch-session) — the wire's
   resume-by-path. The switch emits the session lifecycle events on the same
   stream, so a subscribed client sees the swap."
  [{:keys [agents handle] :as state} command]
  (let [path (:session-path command)]
    (if (and (string? path) (not (str/blank? path)))
      (let [result ((:resume-session! agents) handle path)]
        (if (:cancelled result)
          (ok :switch-session {:cancelled true})
          (do (after-move state (:session result))
              (ok :switch-session {:cancelled false}))))
      (failed :switch-session "switch-session requires a non-empty :session-path string"))))

(defn- command-fork
  "Fork the session before the user message ENTRY-ID (pi: fork); `:text` is the
   message's text for the client to restore to its editor."
  [{:keys [agents handle] :as state} command]
  (let [entry-id (:entry-id command)]
    (if (and (string? entry-id) (not (str/blank? entry-id)))
      (let [result ((:fork! agents) handle entry-id)]
        (cond
          (:cancelled result) (ok :fork {:cancelled true})
          (:error result) (failed :fork (if (= :unknown-entry (:error result))
                                          (str "Entry not found: " entry-id)
                                          "Cannot fork session"))
          :else (do (after-move state (:session result))
                    (ok :fork {:cancelled false
                               :text (session/session-entry-text (:entry result))}))))
      (failed :fork "fork requires a non-empty :entry-id string"))))

(defn- command-clone
  "Duplicate the live session at its current position (pi: clone) — a fork at
   the leaf, keeping the agent's own model and thinking level."
  [{:keys [agents handle] :as state} _command]
  (let [result ((:clone! agents) handle)]
    (cond
      (:cancelled result) (ok :clone {:cancelled true})
      (:error result) (failed :clone (case (:error result)
                                       :no-leaf "Cannot clone session: no current entry selected"
                                       :not-persisted "Cannot clone session: the session is not persisted yet"
                                       :no-session "Cannot clone session: there is no session"
                                       "Cannot clone session"))
      :else (do (after-move state (:session result))
                (ok :clone {:cancelled false})))))

(defn- command-set-session-name
  "Set the session display name (pi: set-session-name) — the append and event
   are the /name builtin's, shared through the runtime. A blank name is
   refused: a session that loses its name to a whitespace-only string is not
   what the client asked for."
  [{:keys [agents handle]} command]
  (let [name (:name command)]
    (if (and (string? name) (not (str/blank? name)))
      (do ((:set-session-name! agents) handle name)
          (ok :set-session-name nil))
      (failed :set-session-name "Session name cannot be empty"))))

(defn- command-get-messages
  "The live model context a client reads (pi: get-messages): what the next
   request would send, with compaction summaries and context edits applied —
   not the raw log `get-entries` answers."
  [{:keys [handle]} _command]
  (ok :get-messages {:messages (vec (runtime/messages handle))}))

(defn- command-get-last-assistant-text
  "The last assistant message's text on the branch (pi:
   get-last-assistant-text — /copy's source). A session with none answers
   `{:text nil}` rather than failing: the question was answered."
  [{:keys [handle]} _command]
  (ok :get-last-assistant-text
      {:text (some-> (runtime/session handle) session/get-last-assistant-text)}))

(defn- command-get-session-stats
  "The token/tool totals `/session` shows (pi: get-session-stats), aggregated
   over every entry including the ones compaction replaced, so the numbers are
   what was billed."
  [{:keys [handle]} _command]
  (if-let [sess (runtime/session handle)]
    (ok :get-session-stats (session/get-session-stats sess))
    (failed :get-session-stats "No active session")))

(defn- command-get-tree
  "The session's branch tree with the current leaf (pi: get-tree), the shape a
   client renders a session tree from."
  [{:keys [handle]} _command]
  (if-let [sess (runtime/session handle)]
    (ok :get-tree {:tree (session/get-tree sess) :leaf-id @(:leaf-id sess)})
    (failed :get-tree "No active session")))

(defn- command-export-html
  "Export the session to HTML (pi: export-html), to `:output-path` or the
   session's runtime cwd. The write is synchronous: the answer is the path, and
   a failure to write it is this command's failure."
  [{:keys [handle]} command]
  (let [path (:output-path command)]
    (cond
      (and (some? path) (not (and (string? path) (not (str/blank? path)))))
      (failed :export-html "export-html requires :output-path to be a non-empty string")

      :else
      (ok :export-html {:path (runtime/export-html! handle (cond-> {} path (assoc :path path)))}))))

(defn- command-get-available-models
  "The models this process can run (pi: get-available-models): the catalog's
   authenticated entries — the same snapshot a `--model` pattern resolves
   against."
  [_state _command]
  (ok :get-available-models {:models (models/get-available)}))

(defn- command-cycle-model
  "Cycle to the next scoped or available model (pi: cycle-model). The answer
   carries the switched record, the thinking level the switch settled on (kmet's
   rank rule, not pi's keep-with-clamp) and whether a scoped list drove it; nil
   data when the model offers fewer than two choices."
  [{:keys [agents handle]} _command]
  (if-let [model-id ((:cycle-model! agents) handle)]
    (ok :cycle-model {:model (models/get-model (runtime/provider handle) model-id)
                      :thinking-level (runtime/thinking-level handle)
                      :scoped? (boolean (seq (runtime/scoped-models handle)))})
    (ok :cycle-model nil)))

(defn- command-set-thinking-level
  "Set the thinking level (pi: set-thinking-level). The catalog decides what a
   level is; an unknown one is refused by name rather than handed to a model."
  [{:keys [agents handle]} command]
  (let [level (:level command)]
    (if (models/valid-thinking-level? level)
      (do ((:set-thinking-level! agents) handle level)
          (ok :set-thinking-level nil))
      (failed :set-thinking-level (str "Unknown thinking level: " (pr-str level))))))

(defn- command-cycle-thinking-level
  "Advance the thinking level through the model's own set (pi:
   cycle-thinking-level); nil data when it offers fewer than two levels."
  [{:keys [agents handle]} _command]
  (if-let [level ((:cycle-thinking-level! agents) handle)]
    (ok :cycle-thinking-level {:level level})
    (ok :cycle-thinking-level nil)))

(defn- command-get-available-thinking-levels
  "The current model's supported levels (pi: get-available-thinking-levels);
   `[:off]` for an agent with no model record."
  [{:keys [handle]} _command]
  (let [model (runtime/model-record handle)]
    (ok :get-available-thinking-levels
        {:levels (if model (models/get-supported-thinking-levels model) [:off])})))

(defn- command-set-queue-mode
  "The shared steering/follow-up setter path (pi: set-steering-mode /
   set-follow-up-mode). The runtime setter validates the mode, so an invalid one
   is this command's failure through the handler's catch."
  [{:keys [agents handle]} {:keys [type] :as command} op]
  ((get agents op) handle (:mode command))
  (ok type nil))

(defn- queued-text
  "The text of a queued entry: the loop's steering and follow-up queues carry
   plain strings, and a compaction-queue entry is a map."
  [item]
  (cond
    (string? item) item
    (map? item) (str (or (:text item) (:content item) (:custom-type item)))
    :else (str item)))

(defn- command-clear-queue
  "Drop the queued steering and follow-up messages and answer their texts (pi:
   clear-queue). The drop emits the loop's `:queue-update`, so a subscribed
   client sees the queues empty."
  [{:keys [agents handle]} _command]
  (let [{:keys [steering follow-up]} (runtime/queues handle)]
    ((:clear-queues! agents) handle)
    (ok :clear-queue {:steering (mapv queued-text steering)
                      :follow-up (mapv queued-text follow-up)})))

(defn- command-respond
  "Answer a dialog request (pi: respond — extension_ui_response). The answer is
   silence: pi writes no response record for one, so the mode's own reader must
   not write either.

   kmet2's extension ABI carries no host-built dialogs (`kmet.extension`'s UI
   surface has no select/confirm/input/editor), so nothing in this process asks
   one and there is no pending request to resolve — an id nobody holds resolves
   nothing, which is also what pi does with a late answer. The pair that *is*
   live is the server row's approval route (`kmet.app.modes.server`), which
   reads a client's `:response` record directly. Only a missing id is this
   command's failure, because the id is what correlates an answer."
  [_state command]
  (let [id (:id command)]
    (when-not (and (string? id) (not (str/blank? id)))
      (failed :respond "respond requires a string :id"))))

(defn- handlers
  "The dispatch table for STATE — the agent's door: the registry, the log, and
   the handle this mode owns."
  [state]
  {:prompt #(command-prompt state %)
   :steer #(command-queue state %)
   :follow-up #(command-queue state %)
   :abort #(command-abort state %)
   :get-state #(command-get-state state %)
   :get-entries #(command-get-entries state %)
   :new-session #(command-new-session state %)
   :switch-session #(command-switch-session state %)
   :fork #(command-fork state %)
   :clone #(command-clone state %)
   :set-session-name #(command-set-session-name state %)
   :get-messages #(command-get-messages state %)
   :get-last-assistant-text #(command-get-last-assistant-text state %)
   :get-session-stats #(command-get-session-stats state %)
   :get-tree #(command-get-tree state %)
   :export-html #(command-export-html state %)
   :get-available-models #(command-get-available-models state %)
   :set-model #(command-set-model state %)
   :cycle-model #(command-cycle-model state %)
   :set-thinking-level #(command-set-thinking-level state %)
   :cycle-thinking-level #(command-cycle-thinking-level state %)
   :get-available-thinking-levels #(command-get-available-thinking-levels state %)
   :set-steering-mode #(command-set-queue-mode state % :set-steering-mode!)
   :set-follow-up-mode #(command-set-queue-mode state % :set-follow-up-mode!)
   :clear-queue #(command-clear-queue state %)
   :set-auto-compaction #(command-set-auto-compaction state %)
   :compact #(command-compact state %)
   :get-commands #(command-get-commands state %)
   :respond #(command-respond state %)})

(defn handle-command
  "Handle one parsed command, returning the response — nil only for the one
   command whose answer is silence (`:respond`). A handler that throws is that
   command's failure, not the mode's."
  [state command]
  (let [type (:type command)]
    (cond
      (not (map? command))
      (failed nil "A command must be an EDN map")

      (nil? type)
      (failed nil "A command record requires a :type")

      :else
      (if-let [handler (get (handlers state) type)]
        (try
          (handler command)
          (catch Throwable t
            (failed type (or (ex-message t) (str t)))))
        (failed type (str "Unknown command: " (pr-str type)))))))

;; ── transport ─────────────────────────────────────────────────────────────

(defn parse-line
  "Parse one line as exactly one EDN form (pi: JSON.parse rejects trailing
   content); returns `{:command value}` or `{:error message}`. Public because the
   server mode asks what a record *is* before dispatching it: a `:response` from
   a client answers a dialog, every other record is a command."
  [line]
  (try
    (let [rdr (java.io.PushbackReader. (java.io.StringReader. line))
          value (edn/read {:eof ::eof} rdr)]
      (cond
        (= ::eof value) {:error "EOF while reading"}
        (= ::eof (edn/read {:eof ::eof} rdr)) {:command value}
        :else {:error "Unexpected content after the command record"}))
    (catch Throwable t
      {:error (or (ex-message t) (str t))})))

(defn parse-failure
  "The response a line that is not one EDN map answers: a parse failure carrying
   no id, because there is nothing to correlate it with."
  [error]
  (failed :parse (str "Failed to parse command: " error)))

(defn handle-record
  "RECORD — already parsed — served against STATE, with the request's `:id`
   echoed when it carried one. The transport parses and this answers: a line
   crossing a wire is parsed once, by whoever read it (`handle-line` for stdio,
   the server for a socket, which must classify the record before serving it)."
  [state record]
  (when-let [response (handle-command state record)]
    (correlated response (:id record))))

(defn handle-line
  "LINE served against STATE — one record out, whatever LINE was: the command's
   response, a parse failure, or a command answered with the `:id` it carried.
   nil for the silent one (`:respond`), which writes nothing.

   STATE is `{:agents :scope :handle}` (plus the rpc row's `:command-ui` and
   `:on-session-move`): the registry, the app scope, and the one handle this
   door serves. The log is read through the handle (`runtime/entries`, the
   session's own entries), so the log service is not part of it."
  [state line]
  (let [{:keys [command error]} (parse-line line)]
    (if error
      (parse-failure error)
      (handle-record state command))))

(defn- serve-commands
  "Serve commands from IN until it closes. Only OUT writes: the reader here and
   the loop's event listener share it, and `out/record!` is what keeps two
   records from interleaving."
  [state in out-]
  (with-open [rdr (io/reader in)]
    (loop []
      (when-let [line (.readLine rdr)]
        (when-let [response (handle-line state line)]
          (out/record! out- response))
        (recur)))))

;; ── the row ───────────────────────────────────────────────────────────────

(defn register-commands!
  "Register the portable builtins this wire front end dispatches over `prompt`
   and reports through `get-commands` (docs/rpc.md § Portable slash commands).
   Idempotent and owned by the app root, so a second mode in one process reuses
   them. `/units` answers the process tree when the entry published one — the
   tree kmet1's rpc answered from — and FALLBACK, the mode's mount scope, for a
   composition that published none."
  [fallback]
  (builtins/register-runtime-commands! (fn [] (or (root/ctx) fallback))))

(defn- serve
  "The `:mode-rpc` row's apply: create the mode's one agent, bind the front-end
   surface, stream the wire, then serve commands until stdin closes.

   A mode runs only when the entry point hands it somewhere to report
   (`:on-result`): `--dump-state` mounts this row without one, and a diagnostic
   mount must prove the row composes without creating an agent or reading
   stdin. Its input is `*in*` and its stream `*out*`, both read here — inside
   the entry point's bindings — so a caller drives the mode by binding them, not
   by smuggling a reader through the row's config."
  [ctx config]
  (let [scope (:parent ctx)
        agents (srce/get ctx :agents)
        {:keys [on-result]} config]
    (when on-result
      (let [out- *out*
            in *in*
            _ (register-commands! (:parent ctx))
            ;; the agent does not exist yet, and the listener must scope to it:
            ;; the mode's stream is its own agent's events, not every agent's
            ;; the process booted (5d listened on the app scope)
            listener (atom nil)
            ;; installed once the handle exists: the surface carries the
            ;; agent's runtime capabilities, so an out-of-run extension handler
            ;; acts on that agent
            restore (atom nil)
            restore! (fn []
                       (when-let [f @restore]
                         (reset! restore nil)
                         (f)))
            ;; kmet1's rpc mode binds exactly this: a front end with a port,
            ;; whose extension UI calls travel the protocol — the row's port as
            ;; the base, the wire capabilities on top
            port (ui-port (:port (srce/get ctx :ui)) out-)]
        (future
          (on-result
           {:exit (try
                    ;; the invocation's run overrides are the agent's own
                    ;; options, exactly as in print mode: the driver resolves a
                    ;; `--model` pattern per creation and applies the rest over
                    ;; the settings it loads
                    (let [handle ((:create! agents) scope (invocation/create-opts config))]
                      (reset! restore
                              (front-end/install! {:mode :rpc
                                                   :has-ui true
                                                   :ui port
                                                   :runtime (runtime/extension-runtime handle)}))
                      (try
                        ;; an out-of-run handler (an extension command over the
                        ;; wire) reads the process-wide slot; one agent serves
                        ;; this mode, so it is this session — and every move
                        ;; re-installs, as the interactive front end does
                        (extensions/install-session! (runtime/session handle))
                        (reset! listener
                                (events/on-every (:ctx handle)
                                                 (fn [event]
                                                   (out/record! out- (wire/wire-event event)))))
                        (serve-commands {:agents agents
                                         :scope scope :handle handle
                                         :command-ui (command-ui port)
                                         :on-session-move extensions/install-session!}
                                        in out-)
                        0
                        (finally
                          (events/unsubscribe! listener)
                          (restore!)
                          ((:dispose! agents) handle))))
                    (catch Throwable t
                      (out/error! (or (ex-message t) (str t)))
                      1))}))
        (fn []
          (events/unsubscribe! listener)
          (restore!))))))

(srce/defplugin plugin
  {:name :mode-rpc
   :inject [:agents :ui]
   :apply serve})
