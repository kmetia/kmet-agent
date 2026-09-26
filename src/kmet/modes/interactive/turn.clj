(ns kmet.modes.interactive.turn
  "Turn lifecycle for the interactive mode: the agent event handlers,
   the submit/cancel/queue paths, message submission, the compaction
   queue, and the terminal-progress indicator (pi: session.prompt input
   events, agent event handlers, flushCompactionQueue)."
  (:require [clojure.string :as str]
            [kmet.debug :as debug]
            [kmet.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.theme :as th]
            [kmet.tui.terminal :as term]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.container :as container]
            [kmet.app.loop :as agent]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.bash-execution :as be]
            [kmet.app.ui.external-editor :refer [editor-text-get editor-text-set!]]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.util :as tools-util]
            [kmet.app.skills :as skills]
            [kmet.app.prompts :as prompts]
            [kmet.app.extensions :as extensions]
            [kmet.app.commands :as commands]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.keybindings :as app-kb]
            [kmet.modes.interactive.state :as state]
            [kmet.modes.interactive.status :as status]))

;; Forward references inside this namespace (the compaction delivery paths).
(declare send-message submit-message apply-hooks heal-stale-scrollback-when-idle!)

;; ─── Compaction queue + turn progress ───────

(defn- queue-compaction-message!
  "Queue a message typed during compaction (pi: queueCompactionMessage —
   non-extension input is queued for after compaction; extension commands
   execute immediately and never reach here). The message displays in the
   pending area and is flushed by the :compaction-end handler."
  [cs text mode]
  (swap! (:compaction-queued cs) conj {:text text :mode mode})
  (status/update-pending-messages! cs)
  (chat-history/chat-history-show-status!
   (:chat-history cs) "Queued message for after compaction")
  (tui/tui-request-render (:tui cs)))

(defn- compaction-queued?
  "True when the compaction queue holds any message."
  [cs]
  (seq @(:compaction-queued cs)))

(defn- clear-compaction-queue!
  "Drop all compaction-queued messages (pi: session switch clears the
   compaction queue). Returns the dropped messages."
  [cs]
  (let [q @(:compaction-queued cs)]
    (reset! (:compaction-queued cs) [])
    q))

(defn- split-command
  "Split a slash-command line into [name args]. Non-command text returns
   [nil nil]."
  [text]
  (when (str/starts-with? text "/")
    (let [sp (str/index-of text " ")]
      (if sp
        [(subs text 1 sp) (subs text (inc sp))]
        [(subs text 1) ""]))))

(defn- extension-command?
  "True when TEXT is a registered extension command (pi: isExtensionCommand)."
  [text]
  (some? (some-> (split-command text) first commands/find-command :extension-handler)))

(defn- execute-extension-command!
  "Run a registered extension command with the extension context (pi:
   prompt() → _tryExecuteExtensionCommand → handler(args, ctx))."
  [text]
  (let [[name args] (split-command text)
        c (commands/find-command name)]
    ((:extension-handler c) (extensions/build-extension-context) (or args ""))))

(defn- expand-compaction-text
  "Expand a compaction-queued message's skill commands and prompt templates
   (pi: steer/followUp expand skill+template before queueing; prompt applies
   the full chain). Queued text is raw (pre-hook); the flush applies input
   hooks first (idle path) and expansion here matches pi's steer/followUp
   for the running-turn path."
  [text]
  (-> text
      (skills/expand-skill-command)
      (prompts/expand-prompt-template (prompts/get-prompt-templates))))

(defn- deliver-compaction-message!
  "Deliver one compaction-queued message: extension commands execute
   immediately; otherwise the message queues into the running turn
   (steer/follow-up per its mode, with skill/template expansion) or, when
   idle, runs the full submit chain — input hooks, then expansion, then
   the run with editor history — like a normal submit (pi:
   flushCompactionQueue → session.prompt runs the input event +
   skill/template expansion)."
  [cs m]
  (if (extension-command? (:text m))
    (execute-extension-command! (:text m))
    (if @(:running-turn? cs)
      ;; running turn: expand skill/template and queue per mode (pi:
      ;; steer/followUp expand; no input event)
      (let [text (expand-compaction-text (:text m))]
        (if (= :follow-up (:mode m))
          (agent/follow-up! @(:agent-state cs) text)
          (agent/steer! @(:agent-state cs) text)))
      ;; idle: full submit chain — input hooks FIRST, then skill/template
      ;; expansion, then the run (pi: flushCompactionQueue → prompt runs
      ;; input event → expansion; editor history like any submit)
      (when-let [text (apply-hooks cs (:text m))]
        (editor/editor-push-history! (:editor cs) text)
        (send-message cs (expand-compaction-text text))))))

(defn- queue-compaction-message-into-turn!
  "Queue one compaction-queued message into the (possibly not yet started)
   run without ever starting a new one: extension commands execute
   immediately, everything else goes to the steering/follow-up queue per
   its mode with skill/template expansion (pi: flushCompactionQueue
   willRetry — every message queues via steer/followUp, never a prompt)."
  [cs m]
  (if (extension-command? (:text m))
    (execute-extension-command! (:text m))
    (let [text (expand-compaction-text (:text m))]
      (if (= :follow-up (:mode m))
        (agent/follow-up! @(:agent-state cs) text)
        (agent/steer! @(:agent-state cs) text)))))

(defn flush-compaction-queue!
  "Deliver messages queued during compaction (pi: flushCompactionQueue —
   runs on every compaction_end). With WILL-RETRY (overflow compaction: the
   interrupted turn continues), every message queues into the running turn
   via steer/follow-up per its mode. Otherwise the first non-extension
   message becomes a prompt (starts a run when idle), extension commands
   execute immediately, and the rest queue per mode. A failure restoring the
   queue re-queues the messages so they are not lost."
  [cs will-retry]
  (when (compaction-queued? cs)
    (let [msgs (clear-compaction-queue! cs)
          restore! (fn []
                     ;; pi: restoreQueue — the session queue is cleared too,
                     ;; so messages already delivered by the failed flush are
                     ;; not delivered again when the queue is re-flushed
                     (agent/clear-queues! @(:agent-state cs))
                     (reset! (:compaction-queued cs) msgs)
                     (status/update-pending-messages! cs))]
      (try
        (if will-retry
          ;; Overflow compaction: the turn retries — queue everything into it
          ;; (pi: flushCompactionQueue willRetry — steer/followUp only, never
          ;; a fresh prompt)
          (doseq [m msgs] (queue-compaction-message-into-turn! cs m))
          ;; Normal completion: first non-extension message prompts, the
          ;; rest queue per mode
          (let [first-idx (first (keep-indexed
                                  (fn [i m] (when-not (extension-command? (:text m)) i))
                                  msgs))]
            (if first-idx
              (do
                ;; extension commands before the first prompt execute now
                (doseq [m (take first-idx msgs)]
                  (execute-extension-command! (:text m)))
                ;; the first message: prompt when idle, else queue per mode
                (deliver-compaction-message! cs (nth msgs first-idx))
                ;; remaining messages queue per mode — never a fresh prompt
                ;; (pi: the rest go through steer/followUp)
                (doseq [m (drop (inc first-idx) msgs)]
                  (queue-compaction-message-into-turn! cs m)))
              ;; all extension commands — execute them all
              (doseq [m msgs] (execute-extension-command! (:text m))))))
        (status/update-pending-messages! cs)
        (catch Exception e
          (restore!)
          (chat-history/chat-history-show-status!
           (:chat-history cs)
           (str "Failed to send queued message"
                (when (> (count msgs) 1) "s")
                ": " (ex-message e)))
          (debug/log "compaction queue flush failed: " e))))))

(defn compaction-status-message
  "Pi: CompactionStatusIndicator label — reason-specific, with the cancel
   hint (escape aborts compaction)."
  [reason]
  (let [cancel (str " (" (status/fmt-key-display (app-kb/key-text "app.interrupt"))
                    " to cancel)")]
    (case reason
      :manual (str "Compacting context..." cancel)
      :overflow (str "Context overflow detected, auto-compacting..." cancel)
      (str "Auto-compacting..." cancel))))

(defn set-terminal-progress!
  "Show/clear the OSC 9;4 terminal progress indicator. Activation is gated
   on the show-terminal-progress setting (pi: showTerminalProgress —
   getShowTerminalProgress gates setProgress; default off); the CLEAR always
   passes: pi gates both ends, so disabling the setting mid-turn leaves its
   keepalive interval asserting the indicator forever — kmet cancels it on
   the turn end regardless (the terminal's clear is a no-op when nothing is
   active; see kmet.tui.terminal/apply-progress!)."
  [cs active?]
  (when cs
    (when-let [term (some-> cs :tui :terminal deref)]
      (when (or (not active?)
                (cfg/get-show-terminal-progress (:config cs)))
        (term/set-progress! term active?)))))

;; ─── Agent response handler ────────────────────────────────────────────────

(defn- on-agent-text
  "Called for each text delta from the LLM during streaming. A pure data
   append: the swap hits the message map's text atom (shared with the
   assistant component), whose track! watch invalidates the cache and
   schedules the frame (§3.4). Before the placeholder's first render the
   anim timer (started at turn start) provides frames — no manual poke
   needed here."
  [cs text]
  (try
    (chat-history/chat-history-append-streaming-text! (:chat-history cs) text)
    (catch Exception e
      (debug/log "on-agent-text callback: " e)
      (binding [*out* *err*] (println "on-agent-text error:" (ex-message e) (.getClass e))))))

(defn- on-agent-thinking
  "Called for each thinking/reasoning delta from the LLM during streaming
   (pure data append — see on-agent-text)."
  [cs text]
  (try
    (chat-history/chat-history-append-thinking-text! (:chat-history cs) text)
    (catch Exception e
      (debug/log "on-agent-thinking callback: " e)
      (binding [*out* *err*] (println "on-agent-thinking error:" (ex-message e) (.getClass e))))))

(defn- on-agent-done
  "Called when the LLM turn completes.
   Drop the running-turn flag FIRST — a background status completion
   (share, branch summary: status/release-background-status!) racing this teardown
   must see the turn over before it may revive the working spinner; the
   flag-first order plus its post-revival re-check makes a stuck spinner
   impossible. Then finalize streaming FIRST (captures thinking text),
   then clear thinking. Session persistence is handled by the agent loop
   internally."
  [cs]
  (try
    (status/stop-anim-timer! cs)
    (reset! (:running-turn? cs) false)
    (status/clear-status-indicator! cs)
    (chat-history/chat-history-finalize-streaming! (:chat-history cs))
    (chat-history/chat-history-finalize-thinking! (:chat-history cs))
    ;; Heal stale above-window scrollback now that the turn has ended: the turn
    ;; itself produced the stale lines (tool output and streamed text that
    ;; changed above the window), and the document is bottom-pinned with the
    ;; user watching its end, so the rebuild's ESC[3J viewport jump lands on
    ;; the turn transition. Gated on streaming-free — a user `!` bash command
    ;; or a compaction may still be live — and a no-op unless dirty.
    (heal-stale-scrollback-when-idle! cs)
    (state/update-footer! cs)
    (tui/tui-request-render (:tui cs))
    (debug/log "agent turn completed")
    (catch Exception e
      (debug/log "on-agent-done callback: " e)
      (binding [*out* *err*] (println "on-agent-done error:" (ex-message e) (.getClass e))))))

(defn- on-agent-error
  "Called when an error occurs during the agent turn."
  [cs error-msg]
  (try
    (status/stop-anim-timer! cs)
    ;; flag before the status clear — see on-agent-done
    (reset! (:running-turn? cs) false)
    (status/clear-status-indicator! cs)
    ;; If streaming placeholder is still empty, remove it
    ;; so we don't get a blank assistant entry before the error message.
    ;; Removed by identity — a consumed steering/follow-up message or a
    ;; tool execution can sit after the placeholder, and popping the last
    ;; entry would delete that message instead (pi: agent_end removes the
    ;; streamingComponent by reference).
    (let [ch (:chat-history cs)]
      (if (and @(:streaming-atom ch)
               (chat-history/chat-history-streaming-empty? ch))
        (chat-history/chat-history-remove-streaming-placeholder! ch)
        (do (chat-history/chat-history-finalize-streaming! ch)
            (chat-history/chat-history-finalize-thinking! ch))))
    (chat-history/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant :content (th/fg th/dark-theme :error (str "Error: " error-msg))})
    ;; A failed turn still produced above-window changes while streaming, so
    ;; heal here too (gated on streaming-free; no-op unless dirty).
    (heal-stale-scrollback-when-idle! cs)
    (state/update-footer! cs)
    (tui/tui-request-render (:tui cs))
    (debug/log "agent turn error: " error-msg)
    (catch Exception e
      (debug/log "on-agent-error callback: " e)
      (binding [*out* *err*] (println "on-agent-error error:" (ex-message e) (.getClass e))))))

;; ─── Submit handler ────────────────────────────────────────────────────────

(defn- handle-bash-command
  "Execute a ! or !! bash command.
   !! → exclude-from-context (output not sent to LLM)
   !  → normal execution (output goes to LLM context)
   Pi: handleBashCommand() in interactive-mode.ts"
  [cs command exclude-from-context?]
  (debug/log "bash command: " command " (exclude-context: " exclude-from-context? ")")

  (if @(:bash-running? cs)
    (do
      (debug/log "bash: already running, ignoring")
      (chat-history/show-warning! (:chat-history cs)
                                  "A bash command is already running. Press Escape to cancel it first."))
    (do
      (reset! (:bash-signal cs) false)
      (reset! (:bash-running? cs) true)

      ;; Create the UI component
      ;; no :theme — the component subscribes to ui.subs/theme-sub (Stage 5)
      (let [bash-comp (be/make-bash-execution
                       :command command
                       :exclude-from-context? exclude-from-context?
                       ;; link to the chat-wide expansion toggle so ctrl+o
                       ;; reaches live bash executions too (reactive read)
                       :tools-expanded-atom (:tools-expanded-atom (:chat-history cs)))
            ;; ── Build session env (pi: resolveSpawnContext) ─────────────
            ag @(:agent-state cs)
            session-env
            (bash-tool/session-env {:session @(:session-atom cs)
                                    :provider @(:provider ag)
                                    :model @(:model ag)
                                    :thinking-level @(:thinking ag)})

            ;; ── Emit user-bash event for extensions (pi: emitUserBash) ──
            ;; Bind the ! cancel signal so extension handlers reacting to
            ;; user-bash can run cancellable bash via execute-tool, and the
            ;; session-env thunk so their bash tools see the same KMET_*
            ;; metadata as the ! command itself (pi: the execute ctx).
            _ (binding [bash-tool/*cancel-signal* (:bash-signal cs)
                        bash-tool/*session-env-fn* (constantly session-env)
                        ;; relative paths in extension bash/tool calls resolve
                        ;; against the runtime cwd, as in an agent run
                        tools-util/*cwd* (state/runtime-cwd cs)]
                (event-bus/emit-event!
                 {:type :user-bash
                  :command command
                  :exclude-from-context? exclude-from-context?
                  :cwd (state/runtime-cwd cs)}))

            ;; ── Spawn hook (pi: BashSpawnHook) — extensions can modify command ──
            spawn-hook nil]

        ;; Add to chat (or pending container if agent is streaming)
        ;; Pi: pendingMessagesContainer sits between chat and footer
        (if @(:running-turn? cs)
          (do
            (container/container-add-child (:pending-messages-container cs) bash-comp)
            (swap! (:pending-bash-components cs) conj bash-comp))
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :bash :command command
                                                   :component bash-comp}))

        (state/update-footer! cs)
        (tui/tui-request-render (:tui cs))

        ;; Execute in background
        (future
          (try
            (let [result (bash-exec/execute-bash
                          {:command command
                           :cwd (state/runtime-cwd cs)
                           :env session-env
                           :on-chunk (fn [chunk]
                                       ;; pure data append: the component's
                                       ;; track! watch schedules the frame
                                       ;; (§3.4); the mount frame above has
                                       ;; installed the watches
                                       (be/bash-execution-append-output!
                                        bash-comp chunk))
                           :signal (:bash-signal cs)
                           :spawn-hook spawn-hook
                           :timeout 300})
                  {:keys [exit-code cancelled truncated full-output-path]} result]
              (debug/log "bash done: exit=" exit-code " cancelled=" cancelled " truncated=" truncated)

              ;; Mark complete on component (pi: truncation metadata from the executor)
              (be/bash-execution-set-complete! bash-comp exit-code cancelled
                                               :truncation (:truncation result)
                                               :full-output-path full-output-path)

              ;; Record in context + session — deferred while the agent is
              ;; streaming so tool_use/tool_result ordering is preserved
              ;; (pi: recordBashResult queues pending bash messages, flushed
              ;; by run-agent-turn once the run settles)
              (agent/add-bash-result! @(:agent-state cs) command result exclude-from-context?)

              ;; Move pending bash from pending container to chat (pi: pendingMessagesContainer)
              (when @(:running-turn? cs)
                (let [pending (:pending-bash-components cs)]
                  (when (seq @pending)
                    (doseq [comp @pending]
                      (container/container-remove-child (:pending-messages-container cs) comp)
                      (chat-history/chat-history-add-message! (:chat-history cs)
                                                              {:role :bash :command command :component comp}))
                    (reset! pending []))))

              (reset! (:bash-running? cs) false)
              (state/update-footer! cs)
              (tui/tui-request-render (:tui cs)))

            (catch Exception e
              (let [err-msg (or (ex-message e) "Unknown error")]
                (debug/log "bash command error: " e)
                (be/bash-execution-set-complete! bash-comp nil false)
                (chat-history/show-error! (:chat-history cs) err-msg)
                (reset! (:bash-running? cs) false)
                (state/update-footer! cs)
                (tui/tui-request-render (:tui cs))))))))))

;; ─── Message submission (pi: session.prompt input event + agent run) ──────

(defn- streaming-free?
  "True when nothing is streaming — no agent turn, bash command, or
   compaction in flight. The only moments where the destructive clearing
   full redraw (ESC[3J) may be emitted: landing one mid-stream yanks the
   viewport, which is the scroll-to-top bug."
  [cs]
  (and (not @(:running-turn? cs))
       (not @(:bash-running? cs))
       (not @(:compacting? @(:agent-state cs)))))

(defn heal-stale-scrollback-when-idle!
  "Heal a stale above-window scrollback when the app is streaming-free (see
   streaming-free?) — at the end of a turn (on-agent-done / on-agent-error /
   handle-cancel) and on idle user input. All bring the destructively-clearing
   ESC[3J rebuild to a boundary where nothing is streaming, so its viewport
   jump lands on a screen transition instead of mid-stream (the scroll-to-top
   bug). The idle-input trigger also covers input that never starts a turn
   (slash/bash commands, text typed then cancelled), which would otherwise
   leave stale lines until the next turn. No-op unless the scrollback is
   dirty (kmet.tui.core/tui-heal-scrollback!)."
  [cs]
  (when (streaming-free? cs)
    (tui/tui-heal-scrollback! (:tui cs))))

(defn global-quit-listener
  "Build the TUI input listener for the global quit shortcut (app.quit,
   ctrl+q by default). The listener chain runs before the overlay modality
   guard and focus dispatch, so the shortcut quits from anywhere — the
   editor, a selector, a dialog — and consumes the event so neither the
   focused component nor an extension shortcut sees the key. The binding
   is resolved per event through the global manager, so keybindings.edn
   overrides apply (and /reload moves the shortcut live)."
  [t]
  (fn [data]
    (when (tui-kb/matches-key (tui-kb/get-global-keybindings) data "app.quit")
      (tui/tui-stop t)
      {:consume true})))

(defn request-global-reflow-render!
  "Request a render after a global reflow — a discrete toggle that
   re-renders many messages at once (tool expansion, thinking visibility,
   the extension set-tools-expanded API). The first changed line is then an
   early message, above the window, so an ordinary diff would clamp: the
   window repaints correctly but the scrolled-off scrollback keeps the
   pre-reflow content — stale for as long as the turn streams. These are
   explicit user actions (the shrinking direction of the same toggle is
   already rebuilt by the shrink-above-window fallback), so force the
   clearing full redraw: the scrollback is rebuilt immediately.
   Automatic above-window changes — streaming text, tool output updates —
   still clamp and mark the scrollback dirty for a later heal; only an
   explicit reflow asks for the clear."
  [cs]
  (tui/tui-request-render (:tui cs) true))

(defn start-agent-run!
  "Start an agent run: set turn state, show the working indicator + animation
   timer, wire the streaming callbacks. The user message and the assistant
   streaming placeholder are created from the loop's :message-start events
   (pi: message_start → addMessageToChat / new streaming component), so the
   initial prompt and consumed steering/follow-up messages all land in the
   chat at the same lifecycle point.
   MESSAGE — optional initial user message; when nil the run continues on the
   existing context without adding a message (the /continue path, where the
   last entry is an unanswered user message or a dangling tool result the
   model must pick up)."
  [cs & [message]]
  (reset! (:running-turn? cs) true)
  (status/activate-working-indicator! cs)
  (status/start-anim-timer! cs)
  (state/update-footer! cs)
  (tui/tui-request-render (:tui cs))
  (agent/run-agent-turn @(:agent-state cs)
                        (cond-> {:on-text #(on-agent-text cs %)
                                 :on-thinking #(on-agent-thinking cs %)
                                 :on-done (fn [_] (on-agent-done cs))
                                 :on-error #(on-agent-error cs %)}
                          message (assoc :message message))))

(defn- send-message
  "Send text to the agent: steer while streaming, else start a new turn.
   Input hooks must already have been applied (pi: agent run happens after
   hook/expansion processing). Returns nil."
  [cs text]
  (if @(:running-turn? cs)
    ;; Agent running: steer the current run (pi: steeringQueue). The message
    ;; is only queued (and shown in the pending display) — it lands in the
    ;; chat as a user message when the loop consumes it (:message-start,
    ;; pi: message_start → addMessageToChat). The in-flight response keeps
    ;; streaming into its own message until then.
    (do
      (debug/log "user steered: " text)
      (agent/steer! @(:agent-state cs) text)
      (status/update-pending-messages! cs)
      (state/update-footer! cs)
      (tui/tui-request-render (:tui cs)))
    (do
      (debug/log "user submitted: " text)
      (start-agent-run! cs text))))

(declare command-line?)

(defn expand-user-message-text
  "pi: prompt() expansion chain (sendUserMessage with
   :expand-prompt-templates?): extension commands execute immediately and
   consume the message; then skill commands and prompt templates expand.
   Builtin commands are NOT dispatched (pi: _tryExecuteExtensionCommand
   only). Returns the expanded text, or nil when an extension command
   consumed the message."
  [cs text]
  (let [trimmed (str/trim text)]
    (if (and (str/starts-with? trimmed "/")
             (command-line? trimmed))
      (let [space (str/index-of trimmed " ")
            cmd (if (nil? space) (subs trimmed 1) (subs trimmed 1 space))
            args (if (nil? space) "" (str/trim (subs trimmed (inc space))))]
        (if-let [c (commands/find-command cmd)]
          (if-let [eh (:extension-handler c)]
            (do (eh (extensions/build-extension-context) args)
                (state/update-footer! cs)
                nil ;; consumed — nothing to send
                )
            text) ;; builtin command — not dispatched here (pi parity)
          (-> text
              (skills/expand-skill-command)
              (prompts/expand-prompt-template (prompts/get-prompt-templates)))))
      text)))

(defn- apply-hooks
  "Run extension input hooks on text; returns the (possibly transformed)
   text, or nil when a hook consumed the input (pi: session.prompt input
   event)."
  [cs text]
  (let [input (extensions/apply-input-hooks text :interactive
                                            {:streaming-behavior (when @(:running-turn? cs) :steer)})]
    (if (= :handled (:action input))
      (do (debug/log "input handled by extension: " text) nil)
      (if (contains? input :text) (:text input) text))))

(defn- submit-message
  "Run input hooks on text, then send it to the agent (pi: session.prompt —
   input event, then agent run). Records the submitted text in the editor
   history so Up/Down can browse it (pi: editor.addToHistory on submit —
   regular messages, steered messages, and follow-ups all land there).
   Returns nil."
  [cs text]
  (when-let [text (apply-hooks cs text)]
    (let [ed @(:current-editor-atom cs)]
      ;; IEditorComponent when available (custom editors), else the
      ;; field-based fn (duck-typed editors — same pattern as editor-text-set!)
      (if (satisfies? protocols/IEditorComponent ed)
        (protocols/editor-add-to-history! ed text)
        (editor/editor-push-history! ed text)))
    (send-message cs text)))

(defn- command-line?
  "True when TRIMMED submit text is a single line. Slash and bang commands
   are single-line by nature; a multiline input — e.g. pasted text whose
   first line happens to start with / or ! — is a message, never a command
   (pi: command matching is exact-match or name-then-space, so a newline
   after the command name never dispatches)."
  [trimmed]
  (not (str/includes? trimmed "\n")))

(defn handle-submit [cs text]
  (let [trimmed (str/trim text)]
    (when (seq trimmed)
      (cond
        ;; Slash command; else skill command (/skill:name), prompt template
        ;; (/name), or fall through to the agent (pi: commands dispatch
        ;; first, then skill/template expansion). Only single-line input is
        ;; a command — a pasted block whose first line starts with / must
        ;; not dispatch (see command-line?).
        (and (str/starts-with? trimmed "/")
             (command-line? trimmed))
        (let [space (str/index-of trimmed " ")
              cmd (if (nil? space) (subs trimmed 1) (subs trimmed 1 space))
              args (if (nil? space) "" (str/trim (subs trimmed (inc space))))]
          (if-let [c (commands/find-command cmd)]
            (do (if-let [eh (:extension-handler c)]
                  ;; extension commands receive the extension context
                  ;; (pi: handler(args, ctx)); builtins keep CoreState
                  (eh (extensions/build-extension-context) args)
                  ((:handler c) cs args))
                (state/update-footer! cs))
            ;; pi: input hooks → skill command → prompt template → fall
            ;; through to the agent (unknown /cmd is sent as a message).
            ;; During compaction the raw text queues like a plain message —
            ;; the flush applies hooks + skill/template expansion at
            ;; delivery like a normal submit (pi: unknown commands fall
            ;; through to the compaction check and queue raw text).
            (if @(:compacting? @(:agent-state cs))
              (do (editor/editor-push-history! (:editor cs) trimmed)
                  (queue-compaction-message! cs trimmed :steer))
              (when-let [text (apply-hooks cs trimmed)]
                (send-message cs
                              (-> text
                                  (skills/expand-skill-command)
                                  (prompts/expand-prompt-template (prompts/get-prompt-templates))))))))

        ;; Bash command (! or !!) — single-line like slash commands
        (and (str/starts-with? trimmed "!")
             (command-line? trimmed))
        (let [exclude-from-context? (str/starts-with? trimmed "!!")
              command (str/trim (subs trimmed (if exclude-from-context? 2 1)))]
          (when (seq command)
            (if @(:bash-running? cs)
              (chat-history/chat-history-add-message! (:chat-history cs)
                                                      {:role :assistant :content "A bash command is already running. Cancel it first."})
              (do
                (editor/editor-push-history! (:editor cs) trimmed)
                (editor/editor-set-text! (:editor cs) "")
                (handle-bash-command cs command exclude-from-context?)))))

        ;; Regular message — agent loop handles session persistence.
        ;; Input hooks (pi: input extension event) run first: a hook can
        ;; consume the input ({:action :handled}) or rewrite it
        ;; ({:action :transform :text ...}); :streaming-behavior tells hooks
        ;; whether the agent is running (input will be steered). Slash and
        ;; bash commands are native UI features and bypass the hooks.
        :else
        ;; Queue input during compaction (pi: onSubmit → isCompacting →
        ;; queueCompactionMessage(text, "steer") — addToHistory + queue;
        ;; extension commands and builtins already dispatched above, so
        ;; only plain messages land here). Flushed by the :compaction-end
        ;; handler.
        (if @(:compacting? @(:agent-state cs))
          (do (editor/editor-push-history! (:editor cs) trimmed)
              (queue-compaction-message! cs trimmed :steer))
          (submit-message cs trimmed))))))

(defn queue-follow-up-text!
  "Queue TEXT as a follow-up, exactly like Alt+Enter would with the text
   in the editor (pi: followUp): during compaction extension commands
   execute immediately and everything else queues as a follow-up for after
   compaction; while the agent runs the text queues into the follow-up
   queue (processed after the run settles — drained by the run's outer
   loop); when idle it submits like a regular Enter. Shared by
   handle-follow-up (Alt+Enter) and the /followup command. Returns
   :executed | :compaction | :queued | :submitted."
  [cs text]
  (cond
    ;; Queue input during compaction (pi: handleFollowUp → isCompacting
    ;; → extension commands execute immediately via prompt(), everything
    ;; else queues as followUp).
    @(:compacting? @(:agent-state cs))
    (if (extension-command? text)
      (do (execute-extension-command! text) :executed)
      (do (queue-compaction-message! cs text :follow-up) :compaction))

    @(:running-turn? cs)
    ;; Pi: handleFollowUp queues a follow-up (processed after the run
    ;; settles). Not added to the chat here — like steering, it appears
    ;; as a user message when the loop consumes it (:message-start,
    ;; pi: message_start → addMessageToChat).
    (do (agent/follow-up! @(:agent-state cs) text)
        (status/update-pending-messages! cs)
        :queued)

    :else
    (do (handle-submit cs text) :submitted)))

(defn handle-follow-up
  "Pi: handleFollowUp — Alt+Enter. While the agent is running, queue the
   editor text as a follow-up (processed after the run settles); when idle,
   submit like regular Enter."
  [cs]
  (let [ed @(:current-editor-atom cs)
        text (str/trim (editor-text-get ed))]
    (when (seq text)
      (editor/editor-push-history! ed text)
      (editor-text-set! ed "")
      (queue-follow-up-text! cs text)
      (tui/tui-request-render (:tui cs)))))

(defn restore-queued-messages!
  "Restore queued steering/follow-up messages to the editor, combined with
   the current text, and clear the queues (pi: restoreQueuedMessagesToEditor
   → clearAllQueues — the compaction queue is restored alongside the session
   queues). Returns the number of messages restored."
  [cs]
  (let [{:keys [steering follow-up]} (agent/queued-messages @(:agent-state cs))
        cq @(:compaction-queued cs)
        all (into (vec steering) (concat follow-up (map :text cq)))]
    (when (seq all)
      (let [ed @(:current-editor-atom cs)
            current (editor-text-get ed)
            queued-text (str/join "\n\n" all)
            combined (str/join "\n\n" (remove str/blank? [queued-text current]))]
        (agent/clear-queues! @(:agent-state cs))
        (reset! (:compaction-queued cs) [])
        (editor-text-set! ed combined)
        (status/update-pending-messages! cs)))
    (count all)))

(defn handle-dequeue
  "Pi: handleDequeue — Alt+Up. Restore all queued steering/follow-up
   messages to the editor, combined with the current text."
  [cs]
  (let [restored (restore-queued-messages! cs)]
    (chat-history/chat-history-show-status!
     (:chat-history cs)
     (if (pos? restored)
       (str "Restored " restored " queued message"
            (when (> restored 1) "s") " to editor")
       "No queued messages to restore"))
    (tui/tui-request-render (:tui cs))))

(defn handle-cancel
  "Cancel the current agent turn, bash command, or in-progress compaction."
  [cs]
  (if @(:compacting? @(:agent-state cs))
    ;; Escape during compaction aborts ONLY the summarization (pi: onEscape
    ;; → abortCompaction — the compaction_start handler swaps the escape
    ;; handler to abortCompaction, restored at compaction_end). A running
    ;; turn is NOT cancelled: an aborted mid-run compaction leaves the turn
    ;; to continue on the pre-compaction context. The compaction-end event
    ;; clears the indicator and reports the cancellation.
    (do
      (debug/log "compaction cancelled by user")
      ;; no visual change here — compaction-end's clear-status-indicator!
      ;; schedules the frame when the abort lands
      (reset! (:signal @(:agent-state cs)) true))
    (do
      (when @(:bash-running? cs)
        (debug/log "bash command cancelled by user")
        (reset! (:bash-signal cs) true)
        (reset! (:bash-running? cs) false)
        (state/update-footer! cs))
      (when @(:running-turn? cs)
        (debug/log "agent turn cancelled by user")
        ;; flag before the status clear — see on-agent-done: a background
        ;; revive racing this teardown must see the turn over
        (reset! (:running-turn? cs) false)
        (status/stop-anim-timer! cs)
        (status/clear-status-indicator! cs)
    ;; pi: restoreQueuedMessagesToEditor({abort: true}) — queued steering/
    ;; follow-up messages return to the editor instead of vanishing when
    ;; cancel-turn clears the queues (they reach the chat only once the
    ;; loop consumes them, so cancel would otherwise lose them entirely).
        (let [restored (restore-queued-messages! cs)]
          (agent/cancel-turn @(:agent-state cs))
      ;; Remove empty streaming placeholder if present — by identity, so
      ;; an entry appended after it (steered/follow-up message, tool
      ;; execution) is never popped in its place
          (let [ch (:chat-history cs)]
            (when-let [s @(:streaming-atom ch)]
              (if (and (empty? @(:text-atom (:component s)))
                       (empty? @(:thinking-text-atom (:component s))))
                (chat-history/chat-history-remove-streaming-placeholder! ch)
                (do (chat-history/chat-history-finalize-streaming! ch) (chat-history/chat-history-finalize-thinking! ch)))))
          (chat-history/chat-history-add-message! (:chat-history cs)
                                                  {:role :assistant :content (th/dim "(cancelled)")})
          (when (pos? restored)
            (chat-history/chat-history-show-status!
             (:chat-history cs)
             (str "Restored " restored " queued message"
                  (when (> restored 1) "s") " to editor"))))
        ;; A cancel is a turn end too: heal the stale scrollback it left (the
        ;; Escape keypress itself could not — the input listener ran while the
        ;; turn was still marked running). Gated + no-op unless dirty.
        (heal-stale-scrollback-when-idle! cs)
        (state/update-footer! cs)))))
