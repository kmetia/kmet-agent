(ns kmet.modes.interactive.commands
  "Builtin slash commands for the interactive mode: thinking handling,
   help/tools text, /session and /share, their registration, and
   /reload."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.debug :as debug]
            [kmet.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.tui.theme :as th]
            [kmet.tui.terminal :as term]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.ai.models :as models]
            [kmet.ai.api.shared :as shared]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.session-export :as session-export]
            [kmet.app.commands :as commands]
            [kmet.app.extensions :as extensions]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.packages :as packages]
            [kmet.app.prompts :as prompts]
            [kmet.app.skills :as skills]
            [kmet.app.tools.core :as tools]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.ui.footer :as footer]
            [kmet.app.ui.fork-selector :refer [show-fork-selector]]
            [kmet.app.ui.hotkeys :as hotkeys-ui]
            [kmet.app.ui.loaded-resources :as loaded-resources]
            [kmet.app.ui.model-selector :refer [apply-model-switch! resolve-model-ref
                                                show-model-selector sync-footer-model!]]
            [kmet.app.ui.scoped-models-selector :refer [show-scoped-models-selector]]
            [kmet.app.ui.session-selector :refer [show-session-selector]]
            [kmet.app.ui.settings-selector :refer [show-settings]]
            [kmet.app.ui.tree-selector :refer [show-session-tree]]
            [kmet.app.ui.subs :as subs]
            [kmet.app.ui.thinking-selector :as thinking-selector]
            [kmet.libs.clipboard :as clipboard]
            [kmet.app.context :as context]
            [kmet.libs.process :as process]
            [kmet.libs.terminal :as lib-term]
            [kmet.modes.interactive.auth :as auth]
            [kmet.modes.interactive.resources :as resources]
            [kmet.modes.interactive.session-admin :as session-admin]
            [kmet.modes.interactive.state :as state]
            [kmet.modes.interactive.status :as status]
            [kmet.modes.interactive.turn :as turn]))

;; ─── Command handling ──────────────────────────────────────────────────────

(defn- apply-thinking-level!
  "Apply a thinking level selection (pi selectThinkingLevel): set the agent's
   level — the session records a :thinking-level-change entry (pi appends to
   the session transcript; /resume restores it), push the level into the
   footer and the editor border color, and report via a status line. When
   PERSIST? the level is additionally saved as [:thinking] in settings.edn,
   the default for future sessions (pi options.persist →
   settingsManager.setDefaultThinkingLevel — kmet's Ctrl+S path; kmet's own
   Shift+Tab cycle and /settings row persist unconditionally, this command
   follows pi)."
  [cs level persist?]
  (let [ag @(:agent-state cs)]
    (agent/set-thinking-level! ag level)
    (sync-footer-model! cs)
    (state/update-editor-border-color! cs level)
    (chat-history/chat-history-show-status!
     (:chat-history cs)
     (str (if persist? "Default thinking level: " "Thinking level: ")
          (name level)))
    (when persist?
      (cfg/save-setting! [:thinking] level))))

(defn- handle-thinking-command!
  "pi handleThinkingCommand: a bare /thinking mounts the level selector; an
   argument applies the matching level directly — matched case-insensitively
   against the current model's supported levels, with a warning listing the
   available levels on a miss (pi showError)."
  [cs search-term]
  (let [levels (thinking-selector/available-levels cs)]
    (if (str/blank? search-term)
      ;; pi opens the selector even for non-reasoning models (only :off
      ;; listed); kmet's cycle parity — a single-level model gets the same
      ;; status as Shift+Tab instead of a one-row selector
      (if (<= (count levels) 1)
        (chat-history/chat-history-show-status! (:chat-history cs)
                                                "Current model does not support thinking")
        (thinking-selector/show-thinking-selector
         cs
         :on-select (fn [level] (apply-thinking-level! cs level false))
         :on-persist (fn [level] (apply-thinking-level! cs level true))))
      (let [wanted (str/lower-case (str/trim search-term))
            level (first (filter #(= (name %) wanted) levels))]
        (if level
          (apply-thinking-level! cs level false)
          (chat-history/show-warning!
           (:chat-history cs)
           (str "Unknown thinking level \"" search-term
                "\". Available levels: "
                (str/join ", " (map name levels)) ".")))))))

(defn- help-text
  "Help message derived from the live command registry."
  []
  (let [cmd-lines
        (mapv (fn [c]
                (let [name (:name c)
                      hint (:argument-hint c)
                      usage (if hint (str " /" name " " hint) (str " /" name))
                      desc (:description c "")]
                  (str "  " usage
                       (apply str (repeat (max 1 (- 32 (count usage))) " "))
                       desc)))
              (commands/get-commands))]
    (str "Available commands:\n"
         (clojure.string/join "\n" cmd-lines)
         "\n\nShortcuts:\n"
         "  Enter      — Submit message\n"
         "  Escape     — Cancel current turn / bash\n"
         "  Ctrl+C     — Clear editor (press twice to quit)\n"
         "  Ctrl+D     — Quit (when editor is empty)\n"
         "  Ctrl+Q     — Quit kmet (from anywhere)\n"
         "  Ctrl+G     — Open external editor\n"
         "  Ctrl+O     — Cycle tool display (collapsed/expanded/quiet)\n"
         "  Ctrl+T     — Toggle thinking blocks\n"
         "  Ctrl+L     — Select model\n"
         "  Up/Down    — Scroll chat history")))

(defn- tools-text
  "Text listing all available tools with their parameters."
  []
  (let [tool-list (sort-by :name (vals (tools/get-all-tools)))
        tool-lines
        (mapv (fn [t]
                (let [required (set (:required (:parameters t)))
                      param-lines
                      (mapv (fn [[pname p]]
                              (let [req (if (contains? required (name pname)) " (required)" "")]
                                (str "    " (name pname) " (" (:type p) ")" req " — " (:description p))))
                            (:properties (:parameters t)))]
                  (str "  " (:name t) " — " (:description t)
                       (when (seq param-lines)
                         (str "\n" (str/join "\n" param-lines))))))
              tool-list)]
    (str "Available tools (" (count tool-list) "):\n"
         (str/join "\n" tool-lines))))

(declare handle-reload)

;; ─── Session info (/session) ────────────────────────────────────────────────

(defn- tool-usage-text
  "Per-tool result-token attribution for /session (script.md T0): each tool's
   call count and estimated result tokens (chars/4), highest token count
   first, with a total line — the same numbers as session/tool-usage-report.
   nil when the session has no tool results, so the section is omitted."
  [sess]
  (let [{:keys [total] :as usage} (session/tool-usage sess)]
    (when (pos? (:calls total 0))
      (let [rows (sort-by (comp - :tokens val) (dissoc usage :total))
            width (apply max (map (fn [[tool _]] (count (name tool))) rows))
            cell (fn [label]
                   (th/dim (format (str "%-" (+ width 2) "s") (str label ":"))))
            row (fn [label {:keys [calls tokens]}]
                  (str "  " (cell label) calls " calls, "
                       (footer/format-tokens tokens) " tokens"))]
        (str (th/bold "Tool Results") " " (th/dim "(estimated)") "\n"
             (str/join "\n" (map (fn [[tool stats]] (row tool stats)) rows))
             "\n" (row "Total" total) "\n")))))

(defn- session-info-text
  "Pi: handleSessionCommand — stats + name + token/cost breakdown, plus
   per-tool result-token attribution (script.md T0, see tool-usage-text).
   Plain text with theme styling, rendered as an assistant message."
  [sess]
  (let [stats (session/get-session-stats sess)
        name (session/get-session-name sess)
        {:keys [input output cache-read cache-write]} (:tokens stats)
        prompt-tokens (+ input cache-read cache-write)
        cache-total (+ cache-read cache-write)
        breakdown (session/usage-breakdown sess)
        tool-usage (tool-usage-text sess)]
    (str (th/bold "Session Info") "\n\n"
         (when (seq name) (str (th/dim "Name:") " " name "\n"))
         (th/dim "File:") " " (:file stats) "\n"
         (th/dim "ID:") " " (:id stats) "\n\n"
         (th/bold "Messages") "\n"
         (th/dim "Total:") " " (:total-messages stats) "\n"
         (th/dim "User:") " " (:user-messages stats) "\n"
         (th/dim "Assistant:") " " (:assistant-messages stats) "\n"
         (th/dim "Tools:") " " (:tool-calls stats) " calls, " (:tool-results stats) " results\n\n"
         (th/bold "Tokens") "\n"
         (th/dim "Input:") " " prompt-tokens "\n"
         (when (pos? cache-total)
           (str (th/dim "  Cached:") " " cache-read
                " (" (format "%.1f" (* 100 (/ (double cache-read) (max 1 prompt-tokens)))) "%)\n"
                (th/dim "  Uncached:") " " (+ input cache-write)
                (when (pos? cache-write) (str " (" cache-write " written to cache)"))
                "\n"))
         (th/dim "Output:") " " output "\n"
         (th/dim "Total:") " " (:total (:tokens stats)) "\n"
         (when tool-usage (str "\n" tool-usage))
         (when (pos? (:cost stats))
           (str "\n" (th/bold "Cost") "\n"
                (th/dim "Total:") " $" (format "%.3f" (:cost stats))
                (when (> (count breakdown) 1)
                  (apply str (for [b breakdown]
                               (str "\n  " (th/dim (str (:key b) ":"))
                                    " $" (format "%.3f" (:cost b))
                                    " " (th/dim (str "(" (footer/format-tokens (:tokens b)) " tokens)"))))))
                "\n")))))

;; ─── Share (/share — gh gist) ───────────────────────────────────────────────

(defn- gh-auth-status
  "Pi: handleShareCommand — :ok when gh is installed and authenticated,
   :not-logged-in when gh exists but auth is missing, :not-installed when
   gh cannot be spawned, :timed-out when gh did not respond in time."
  []
  (try
    (let [p (proc/process ["gh" "auth" "status"]
                          {:out :string :err :string
                           :in (fs/file (if process/windows-os? "NUL" "/dev/null"))})
          r (deref p 10000 :timed-out)]
      (cond
        (= r :timed-out)
        (do (when-let [pid (try (-> p :proc .pid) (catch Exception _ nil))]
              (process/kill-process-tree! pid))
            :timed-out)
        (zero? (:exit r)) :ok
        :else :not-logged-in))
    (catch Exception _ :not-installed)))

(defn- share-session!
  "Export the current session to a temp HTML file and create a secret gist
   (pi: handleShareCommand). Runs on a future with a spinner status
   indicator; the chat reply carries the gist URL."
  [cs]
  (let [sess @(:session-atom cs)
        chat (:chat-history cs)
        indicator (spinner/make-spinner :text "Creating gist..." :active true)
        done (promise)]
    (status/show-status-indicator! cs :share indicator)
    ;; spinner animation rides the transient-indicator frame driver while
    ;; :share is up (cleared below on completion/timeout)
    (future
      (let [result
            (try
              (let [tmp-dir (or (System/getenv "TMPDIR")
                                (System/getProperty "java.io.tmpdir"))
                    tmp (fs/create-temp-file {:prefix "kmet-share-" :suffix ".html" :dir tmp-dir})]
                (try
                  (session-export/export-to-html! sess {:path (str tmp)})
                  (let [p (proc/process ["gh" "gist" "create" "--public=false" (str tmp)]
                                        {:out :string :err :string
                                         :in (fs/file (if process/windows-os? "NUL" "/dev/null"))})
                        r (deref p 60000 :timed-out)]
                    (if (= r :timed-out)
                      (do (when-let [pid (try (-> p :proc .pid) (catch Exception _ nil))]
                            (process/kill-process-tree! pid))
                          {:error "Gist creation timed out."})
                      (if (zero? (:exit r))
                        {:url (str/trim (:out r))}
                        {:error (str/trim (:err r))})))
                  (finally (fs/delete-if-exists tmp))))
              (catch Exception e
                {:error (or (ex-message e) (str e))}))]
        (deliver done result)))
    (future
      (let [result (deref done 90000 :timeout)]
        (status/release-background-status! cs :share indicator)
        (chat-history/chat-history-add-message!
         chat
         (cond
           (= result :timeout)
           {:role :info :label "Share" :content "Gist creation timed out."}

           (:error result)
           {:role :info :label "Share"
            :content (str "Failed to create gist: " (:error result))}

           :else
           {:role :info :label "Share" :content (str "Share URL: " (:url result))}))
        ;; The status clear may have requested a frame before the message
        ;; was appended; request again after the untracked chat mutation.
        (when-let [t (:tui cs)]
          (tui/tui-request-render t))))))

;; ─── Builtin command registration ──────────────────────────────────────────

(defn- register-builtin-command!
  "Register a builtin slash command unless an extension already took the
   name. Extensions load before the layout is built, so a command an
   extension registered under a builtin name (e.g. the shipped /tools
   extension replacing the builtin tools listing — pi has no builtin
   /tools; the example extension owns the name there) must not be
   clobbered."
  [cmd]
  (when-not (commands/find-command (:name cmd))
    (commands/register-command! cmd)))

(defn- make-hotkey-wired?
  "Predicate over a keybinding id: is it wired in this interactive mode?
   TUI ids are the editor's own (it implements the ids it declares); an app
   action counts only when a handler is installed on the editor
   (editor-set-on-action! — read live, so the predicate cannot drift from
   the wiring) or a global input listener owns the id (app.quit, see
   turn/global-quit-listener). /hotkeys shows wired bindings only: a declared
   pi-parity id whose action was never installed (app.suspend,
   app.message.copy in the main editor) stays out."
  [cs]
  (let [installed (into #{"app.quit"}
                        (when-let [ed (:editor cs)]
                          (editor/editor-app-action-ids ed)))]
    (fn [id]
      (or (not (str/starts-with? id "app."))
          (contains? installed id)))))

(defn register-builtin-commands!
  "Register kmet's builtin slash commands. Handlers receive [cs args];
   argument completions feed the editor autocomplete dropdown."
  [_config]
  (register-builtin-command!
   {:name "quit"
    :description "Exit kmet"
    :handler (fn [cs _]
               (debug/log "/quit command")
               (tui/tui-stop (:tui cs)))})
  (register-builtin-command!
   {:name "help"
    :description "Show available commands and shortcuts"
    :handler (fn [cs _]
               (chat-history/chat-history-add-message! (:chat-history cs)
                                                       {:role :assistant :content (help-text)}))})
  (register-builtin-command!
   {:name "hotkeys"
    :description "Show all keyboard shortcuts"
    :handler (fn [cs _]
               (chat-history/chat-history-add-message!
                (:chat-history cs)
                {:component (hotkeys-ui/make-hotkeys-view
                             (make-hotkey-wired? cs))}))})
  (register-builtin-command!
   {:name "tools"
    :description "List available tools with parameters"
    :handler (fn [cs _]
               (chat-history/chat-history-add-message! (:chat-history cs)
                                                       {:role :assistant :content (tools-text)}))})
  (register-builtin-command!
   {:name "model"
    :description "Switch model"
    :argument-hint "<provider:model[:thinking]>"
    :get-argument-completions
    (fn [_]
      (mapv (fn [m] (let [v (str (name (:provider m)) "/" (:id m))]
                      {:value v :label v}))
            (models/get-available)))
    :handler
    (fn [cs args]
      (if (seq args)
        (let [{:keys [model thinking-level]} (resolve-model-ref cs args)]
          (if model
            (apply-model-switch! cs model thinking-level)
            ;; pi: no cached match → selector with the term pre-filled (kmet
            ;; catalogs are static — no catalog refresh on a miss)
            (show-model-selector cs args)))
        (show-model-selector cs)))})
  (register-builtin-command!
   {:name "thinking"
    :description "Set thinking level"
    :argument-hint "<level>"
    :get-argument-completions
    (fn [prefix]
      (let [items (mapv (fn [l] {:value (name l)
                                 :label (name l)
                                 :description (thinking-selector/level-description l)})
                        shared/thinking-levels)]
        (if (str/blank? prefix)
          items
          (fuzzy/fuzzy-filter items prefix
                              (fn [it] (str (:value it) " " (:description it)))))))
    :handler (fn [cs args] (handle-thinking-command! cs args))})
  (register-builtin-command!
   {:name "scoped-models"
    :description "Enable/disable models for Ctrl+P cycling"
    :handler (fn [cs _] (show-scoped-models-selector cs))})
  (register-builtin-command!
   {:name "settings"
    :description "Open settings menu"
    :handler (fn [cs _] (show-settings cs))})
  (register-builtin-command!
   {:name "new"
    :description "Start a new session"
    :handler (fn [cs _] (session-admin/handle-new-session cs))})
  (register-builtin-command!
   {:name "resume"
    :description "Browse past sessions"
    :handler (fn [cs _]
               (debug/log "/resume command")
               ;; mid-turn refusal like the other switch commands (pi:
               ;; teardownCurrent aborts the run; kmet waits instead)
               (if (state/turn-running? cs)
                 (chat-history/chat-history-add-message! (:chat-history cs)
                                                         {:role :assistant
                                                          :content "Wait for the current response to finish before resuming."})
                 (show-session-selector cs state/ensure-session-dir
                                        (fn [path]
                                          ;; pi: emitBeforeSwitch (reason :resume)
                                          ;; — extensions may cancel the switch
                                          (when-not (:cancel (event-bus/emit-event!
                                                              {:type :session-before-switch
                                                               :reason :resume
                                                               :target-session-file path}))
                                            (let [sess (session/load-session path)
                                                  short-id (subs (:id sess) 0 (min 8 (count (:id sess))))]
                                              (session-admin/restore-session! cs sess true)
                                              (chat-history/chat-history-add-message! (:chat-history cs)
                                                                                      {:role :assistant
                                                                                       :content (str "Resumed session " short-id ".")})
                                              (tui/tui-request-render (:tui cs))))))))})
  (register-builtin-command!
   {:name "continue"
    :description "Continue where the agent left off (e.g. after a network error)"
    :handler (fn [cs _]
               (let [{:keys [chat-history]} cs
                     agent-state @(:agent-state cs)]
                 (cond
                   ;; A run may have ended in :error (network failure, retries
                   ;; exhausted) — that's exactly when /continue is useful, so
                   ;; only actively-running states refuse. Both the UI turn flag
                   ;; (set synchronously on submit) and the agent status
                   ;; (:thinking/:executing, set by the run future) are checked.
                   (or @(:running-turn? cs)
                       (contains? #{:thinking :executing} @(:status agent-state)))
                   (chat-history/chat-history-add-message! chat-history
                                                           {:role :info :label "Continue"
                                                            :content "Wait for the current response to finish before continuing."})

                   @(:compacting? agent-state)
                   (chat-history/chat-history-add-message! chat-history
                                                           {:role :info :label "Continue"
                                                            :content "Wait for the in-progress compaction to finish before continuing."})

                   (empty? (agent/get-context agent-state))
                   (chat-history/chat-history-add-message! chat-history
                                                           {:role :info :label "Continue"
                                                            :content "No conversation to continue."})

                   :else
                   (do (debug/log "/continue command")
                       (turn/start-agent-run! cs)))))})
  (register-builtin-command!
   ;; pi: no equivalent — /followup is the slash-command form of Alt+Enter
   ;; (app.message.followUp), letting the follow-up text ride the args
   ;; instead of the editor
   {:name "followup"
    :description "Queue a follow-up message (like Alt+Enter)"
    :argument-hint "<message>"
    :handler (fn [cs args]
               (let [text (str/trim args)]
                 (if (seq text)
                   ;; Alt+Enter semantics with the args as the text: during
                   ;; compaction the text queues as follow-up (extension
                   ;; commands execute immediately); while the agent runs it
                   ;; joins the follow-up queue (processed after the run
                   ;; settles, shown in the pending display); when idle it
                   ;; submits like a regular message.
                   (when (= :queued (turn/queue-follow-up-text! cs text))
                     (chat-history/chat-history-show-status!
                      (:chat-history cs)
                      "Queued follow-up message")
                     (tui/tui-request-render (:tui cs)))
                   (chat-history/chat-history-add-message!
                    (:chat-history cs)
                    {:role :info :label "Follow-up"
                     :content "Usage: /followup <message>"}))))})
  (register-builtin-command!
   {:name "tree"
    :description "Navigate session tree (switch branches)"
    :handler (fn [cs _]
               ;; mid-turn refusal like the other switch commands — branching
               ;; while a run streams would leave its events in the new branch
               (if (state/turn-running? cs)
                 (chat-history/chat-history-add-message! (:chat-history cs)
                                                         {:role :assistant
                                                          :content "Wait for the current response to finish before navigating the tree."})
                 (show-session-tree cs
                                    (fn [entry]
                                      (session-admin/ask-branch-summary cs @(:session-atom cs) entry)))))})
  (register-builtin-command!
   {:name "fork"
    :description "Create a new fork from a previous user message"
    :handler (fn [cs _]
               (show-fork-selector cs (fn [entry-id] (session-admin/fork-at! cs entry-id))))})
  (register-builtin-command!
   {:name "clone"
    :description "Duplicate the current session at the current position"
    :handler (fn [cs _]
               (session-admin/clone-current-session! cs))})
  (register-builtin-command!
   {:name "name"
    :description "Set session display name"
    :argument-hint "<name>"
    :handler (fn [cs args]
               (let [sess @(:session-atom cs)]
                 (if (nil? sess)
                   (chat-history/chat-history-add-message! (:chat-history cs)
                                                           {:role :assistant
                                                            :content "No active session."})
                   (if (seq args)
                     (let [sanitized (session/sanitize-session-name args)]
                       (session/append-session-info! sess sanitized)
                       ;; pi: session_info_changed — extensions track the
                       ;; display name
                       (event-bus/emit-event!
                        {:type :session-info-changed
                         :session-file (:file sess)
                         :name sanitized})
                       (state/update-terminal-title! cs)
                       (when-not (= args sanitized)
                         ;; pi: warn when normalization changed the input
                         (chat-history/show-warning!
                          (:chat-history cs)
                          (str "Session name was normalized from " (pr-str args)
                               " to " (pr-str sanitized))))
                       (chat-history/chat-history-add-message! (:chat-history cs)
                                                               {:role :info :label "Name"
                                                                :content (str "Session name set: " sanitized)}))
                     (if-let [current (session/get-session-name sess)]
                       (chat-history/chat-history-add-message! (:chat-history cs)
                                                               {:role :info :label "Name"
                                                                :content (str "Session name: " current)})
                       (chat-history/show-warning! (:chat-history cs)
                                                   "Usage: /name <name>"))))))})
  (register-builtin-command!
   {:name "session"
    :description "Show session info and stats"
    :handler (fn [cs _]
               (let [sess @(:session-atom cs)
                     chat (:chat-history cs)]
                 (if (nil? sess)
                   (chat-history/chat-history-add-message! chat
                                                           {:role :info :label "Session"
                                                            :content "No active session."})
                   (chat-history/chat-history-add-message! chat
                                                           {:role :assistant
                                                            :content (session-info-text sess)}))))})
  (register-builtin-command!
   {:name "export"
    :description "Export session to HTML (JSONL is not supported by design)"
    :argument-hint "<path>"
    :handler (fn [cs args]
               (let [sess @(:session-atom cs)
                     chat (:chat-history cs)
                     arg (session-admin/parse-path-argument args)]
                 (cond
                   (nil? sess)
                   (chat-history/chat-history-add-message! chat
                                                           {:role :info :label "Export"
                                                            :content "No active session."})

                   (str/ends-with? (str/lower-case (or arg "")) ".jsonl")
                   (chat-history/chat-history-add-message! chat
                                                           {:role :info :label "Export"
                                                            :content "JSONL export is not supported — kmet sessions are EDN-only. Use /export or /export <path.html>."})

                   :else
                   (try
                     (let [ag @(:agent-state cs)
                           system-prompt (or @(:system-prompt-override ag) @(:system ag))
                           tool-defs (vals (tools/get-all-tools))
                           opts (cond-> (when arg {:path arg})
                                  ;; without an explicit path the export lands in
                                  ;; the session's runtime cwd (where its tools work),
                                  ;; not the launch directory
                                  (not arg) (assoc :cwd (state/runtime-cwd cs))
                                  system-prompt (assoc :system-prompt system-prompt)
                                  (seq tool-defs) (assoc :tools tool-defs))
                           path (session-export/export-to-html! sess opts)]
                       (chat-history/chat-history-add-message! chat
                                                               {:role :info :label "Export"
                                                                :content (str "Session exported to: " path)}))
                     (catch Exception e
                       (chat-history/chat-history-add-message! chat
                                                               {:role :info :label "Export"
                                                                :content (str "Failed to export session: "
                                                                              (or (ex-message e) (str e)))}))))))})
  (register-builtin-command!
   ;; kmet keeps the path hint /export carries (pi hints neither; its usage
   ;; error spells the syntax out). kmet sessions are EDN, hence "from a
   ;; file" where pi says "from a JSONL file"
   {:name "import"
    :description "Import and resume a session from a file"
    :argument-hint "<path>"
    :handler (fn [cs args] (session-admin/handle-import-command cs args))})
  (register-builtin-command!
   {:name "share"
    :description "Share session as a secret GitHub gist"
    :handler (fn [cs _]
               (let [chat (:chat-history cs)]
                 (case (gh-auth-status)
                   :not-installed
                   (chat-history/chat-history-add-message! chat
                                                           {:role :info :label "Share"
                                                            :content "GitHub CLI (gh) is not installed. Install it from https://cli.github.com/"})

                   :not-logged-in
                   (chat-history/chat-history-add-message! chat
                                                           {:role :info :label "Share"
                                                            :content "GitHub CLI is not logged in. Run 'gh auth login' first."})

                   :timed-out
                   (chat-history/chat-history-add-message! chat
                                                           {:role :info :label "Share"
                                                            :content "GitHub CLI did not respond (timed out)."})

                   :else
                   (if (nil? @(:session-atom cs))
                     (chat-history/chat-history-add-message! chat
                                                             {:role :info :label "Share"
                                                              :content "No active session."})
                     (share-session! cs)))))})
  (register-builtin-command!
   {:name "copy"
    :description "Copy last agent message to clipboard"
    :handler (fn [cs _]
               (let [sess @(:session-atom cs)
                     chat (:chat-history cs)]
                 (cond
                   (nil? sess)
                   (chat-history/chat-history-add-message! chat
                                                           {:role :info :label "Copy"
                                                            :content "No active session."})

                   :else
                   (if-let [text (session/get-last-assistant-text sess)]
                     (if (clipboard/copy-text! text)
                       (tui/tui-flash! (:tui cs) "Copied!")
                     ;; No platform tool — fall back to the OSC 52 terminal
                     ;; protocol (pi: copyToClipboard remote fallback).
                       (if (lib-term/osc52-copy! (term/write-fn @(:terminal (:tui cs)))
                                                 text)
                         (tui/tui-flash! (:tui cs) "Copied!")
                         (chat-history/show-warning! chat
                                                     "No clipboard tool available on this system.")))
                     (chat-history/show-warning! chat
                                                 "No agent messages to copy yet.")))))})
  (register-builtin-command!
   {:name "reload"
    :description "Reload keybindings, extensions, skills, prompts, themes, and context files"
    :handler handle-reload})
  (register-builtin-command!
   {:name "compact"
    :description "Manually compact the session context"
    :argument-hint "<instructions>"
    :handler (fn [cs args]
               (let [{:keys [chat-history]} cs
                     agent-state @(:agent-state cs)
                     instructions (when (seq args) args)]
                 (cond
                   (not= :idle @(:status agent-state))
                   (chat-history/chat-history-add-message! chat-history
                                                           {:role :info :label "Compact"
                                                            :content "Wait for the current response to finish before compacting."})

                   @(:compacting? agent-state)
                   (chat-history/chat-history-add-message! chat-history
                                                           {:role :info :label "Compact"
                                                            :content "Compaction already in progress."})

                   :else
                   ;; Runs on a future so the input thread stays live and
                   ;; escape can cancel the compaction (pi: session.compact
                   ;; is async). The :compaction-end event reports an
                   ;; aborted compaction; only the result replies go here.
                   (future
                     (let [result (agent/compact-context! agent-state instructions :manual)]
                       (when-not (or (= :aborted result) (= :failed result))
                         ;; pi: handleCompactCommand ignores the thrown
                         ;; compaction error — compaction_end surfaces it
                         ;; (showError for manual); the future only replies
                         ;; for success and nothing-to-compact.
                         (chat-history/chat-history-add-message!
                          chat-history
                          {:role :info :label "Compact"
                           :content (if result
                                      "Session compacted."
                                      "Nothing to compact (session too small).")})))))))})
  (register-builtin-command!
   {:name "theme"
    :description "Switch theme"
    :argument-hint "<name>"
    :get-argument-completions
    (fn [_]
      (mapv (fn [t] {:value t :label t})
            (sort (keys (th/get-all-themes)))))
    :handler (fn [cs args]
               (let [tc (:theme-controller cs)
                     ;; args is the trimmed argument string — take it whole
                     ;; (a string is a seq of chars; (first args) would yield
                     ;; the first character)
                     name (str/trim (or args ""))]
                 (if (seq name)
                   (let [result (theme-ctrl/set-theme-name! tc name)]
                     (when (:success result)
                       (cfg/save-setting! [:theme] name))
                     (chat-history/chat-history-add-message! (:chat-history cs)
                                                             {:role :assistant
                                                              :content (if (:success result)
                                                                         (str "Switched to theme \"" name "\".")
                                                                         (str "Failed to load theme \"" name "\": "
                                                                              (:error result)))}))
                   (chat-history/chat-history-add-message! (:chat-history cs)
                                                           {:role :assistant
                                                            :content (str "Current theme: "
                                                                          (theme-ctrl/get-active-theme-name tc)
                                                                          "\nAvailable themes: "
                                                                          (str/join ", " (sort (keys (th/get-all-themes))))
                                                                          "\nUsage: /theme <name>")}))))})
  (register-builtin-command!
   {:name "login"
    :description "Configure provider authentication"
    :argument-hint "<provider>"
    :get-argument-completions auth/login-argument-completions
    :handler (fn [cs args] (auth/handle-login-command! cs args))})
  (register-builtin-command!
   ;; pi: no argument hint and no completions — /logout always opens the
   ;; stored-credential selector
   {:name "logout"
    :description "Remove provider authentication"
    :handler (fn [cs _] (auth/handle-logout-command! cs))}))

(defn handle-reload
  "Reload settings, extensions, skills, prompts, themes, context files, and
   rebuild the system prompt (pi: interactive-mode handleReloadCommand →
   session.reload → _rebuildSystemPrompt). Refuses while the agent is
   running (pi warns to wait for the current response)."
  [cs _]
  (let [{:keys [chat-history]} cs
        agent-state @(:agent-state cs)]
    (if-not (= :idle @(:status agent-state))
      (chat-history/chat-history-add-message! chat-history
                                              {:role :info :label "Reload"
                                               :content "Wait for the current response to finish before reloading."})
      (try
        ;; pi: settingsManager.reload() + theme re-registration
        (let [config (cfg/init!)
              ;; pi: keybindings.reload() — re-read keybindings.edn
              _ (app-kb/reload-agent-keybindings!)
        ;; pi: session.reload → emitSessionShutdownEvent(reason reload)
        ;; BEFORE the runner is torn down, so extensions can persist
        ;; state; then extension shutdown/start
              _ (event-bus/emit-event! {:type :session-shutdown :reason :reload})
              _ (extensions/ui-reset!)
              _ (extensions/clear-extensions!)
              ;; per-extension load results — the loaders only warn on
              ;; stderr, so failures must be collected for the transcript
              ;; (one unified load: top-level entries, auto dirs, packages)
              ext-results (packages/load-extensions!)
              _ (packages/load-themes!)
              ;; pi: model-runtime.refresh — recompose providers from models.edn
              _ (models/load-models-config!)
              ;; pi: resourceLoader.reload (skills, prompts)
              _ (skills/clear-skills!)
              _ (prompts/clear-prompt-templates!)
              _ (packages/load-skills!)
              _ (packages/load-prompts!)
              ;; pi: _rebuildSystemPrompt with new sources — the prompt is
              ;; built over the CURRENTLY active tool set (pi:
              ;; getActiveToolNames), so a pre-reload set-active-tools
              ;; restriction survives the reload.
              active-names @(:enabled-tools agent-state)
              active-tools (if active-names
                             (filterv #(contains? active-names (:name %))
                                      (vals (tools/get-all-tools)))
                             (vals (tools/get-all-tools)))
              system-prompt-opts {:custom-prompt (cfg/get-custom-prompt config)
                                  :append-prompt (cfg/get-append-system-prompt config)
                                  ;; the cwd line follows the runtime cwd (a session
                                  ;; from another project may be active); context
                                  ;; files and everything else project-scoped stay
                                  ;; with the launch dir
                                  :cwd (state/runtime-cwd cs)
                                  :context-files (context/load-project-context-files
                                                  (cfg/get-agent-dir) (str (fs/cwd)))
                                  :tools active-tools}
              system-prompt (apply skills/build-system-prompt
                                   (mapcat identity system-prompt-opts))]
          (state/set-global-config! config)
          (theme-ctrl/set-config! (:theme-controller cs) config)
;; pi: restoreChatBeforeSessionStart — re-apply hideThinkingBlock
          ;; and the tool display mode from settings to existing chat messages
          (chat-history/chat-history-set-thinking-hidden! chat-history
                                                          (cfg/get-hide-thinking-block config))
          ;; the header and loaded resources follow the same mode as the chat
          ;; (the ctrl+o handler and the extension setter both keep them in
          ;; sync; reload must not leave them on a stale expansion)
          (let [mode (cfg/get-tool-display-mode config)]
            (chat-history/chat-history-set-tool-display-mode! chat-history mode)
            (when-let [hdr (:header-comp cs)]
              (expandable-text/expandable-text-set-expanded! hdr (= :expanded mode)))
            (when-let [lr (:loaded-resources-comp cs)]
              (loaded-resources/loaded-resources-set-expanded! lr (= :expanded mode))))
          (reset! (:system agent-state) system-prompt)
          (reset! (:system-prompt-opts agent-state) system-prompt-opts)
          (loaded-resources/loaded-resources-set-sections!
           (:loaded-resources-comp cs) (resources/build-loaded-resource-sections))
          ;; pi: settingsManager.reload() — re-seed the live image settings
          ;; (/settings persists to settings.edn, whose merged value can also
          ;; change externally between reloads) and re-apply the provider
          ;; image-blocking knob to the running agent
          (reset! subs/image-settings-atom
                  {:show-images (cfg/get-show-images config)
                   :image-width-cells (cfg/get-image-width-cells config)})
          (agent/set-block-images! agent-state (cfg/get-block-images config))
          ;; pi: settingsManager.reload() — re-apply the clear-on-shrink knob
          ;; (the /settings row writes both the flag and the runtime)
          (when (:tui cs)
            (tui/tui-set-clear-on-shrink! (:tui cs) (cfg/get-clear-on-shrink config)))
          (state/update-footer! cs)
          ;; pi: reload re-emits session_start so extensions re-register UI.
          ;; Runs on a future — handlers may block on dialog promises, which
          ;; must never happen on the input thread.
          (future
            (try (event-bus/emit-event! {:type :session-start :reason :reload})
                 ;; pi: resources_discover fires after session_start (reason
                 ;; reload) — extensions contribute skill/prompt/theme paths
                 (extensions/discover-resources! :reload)
                 (catch Exception e (debug/log "session-start: " e))))
          (chat-history/chat-history-add-message! chat-history
                                                  {:role :info :label "Reload"
                                                   :content (str "Reloaded keybindings, extensions, skills, prompts, themes, context files, and models.edn."
                                                                 (when-let [err (models/get-model-config-error)]
                                                                   (str " [models.edn: " err "]"))
                                                                 (when-let [failures (seq (filter :error ext-results))]
                                                                   (str "\n\nFailed to load extension"
                                                                        (when (< 1 (count failures)) "s")
                                                                        ":\n"
                                                                        (str/join "\n"
                                                                                  (map (fn [{:keys [extension path error]}]
                                                                                         (str "- " (or extension path) ": " error))
                                                                                       failures))))
                                                                 (when-let [skipped (seq (filter :skipped ext-results))]
                                                                   (str "\n\nSkipped extension"
                                                                        (when (< 1 (count skipped)) "s")
                                                                        " (declared loaders this host does not offer):\n"
                                                                        (str/join "\n"
                                                                                  (map (fn [{:keys [extension declared-loaders available-loaders]}]
                                                                                         (str "- " extension " supports " (pr-str declared-loaders)
                                                                                              ", " (pr-str available-loaders) " available"))
                                                                                       skipped)))))}))
        (catch Exception e
          (debug/log "reload failed: " e)
          (chat-history/chat-history-add-message! chat-history
                                                  {:role :info :label "Reload"
                                                   :content (str "Reload failed: "
                                                                 (or (ex-message e)
                                                                     (.getName (class e))))}))))))


