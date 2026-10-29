(ns kmet.ui.tui.interactive.builtins
  "Builtin slash commands for the interactive mode: the view-bound commands
   (thinking handling, help/tools text, /session, /share, /reload, and the
   session/selector flows) and their registration. The engine-portable
   builtins live in kmet.app.commands.builtins and are registered by
   register-builtin-commands! first."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.app.debug :as debug]
            [kmet.app.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.libs.theme :as th]
            [kmet.tui.terminal :as term]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.spinner :as spinner]
            [kmet.libs.fuzzy :as fuzzy]
            [kmet.ai.models :as models]
            [kmet.app.session.core :as session]
            [kmet.app.session.runtime :as runtime]
            [kmet.app.session.export :as session-export]
            [kmet.app.commands :as commands]
            [kmet.app.commands.builtins :as runtime-commands]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.extensions.rows :as extension-rows]
            [kmet.app.events :as events]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.packages :as packages]
            [kmet.app.prompts :as prompts]
            [kmet.app.root :as root]
            [kmet.app.skills :as skills]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.shell :as shell]
            [kmet.ui.tui.theme-controller :as theme-ctrl]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.footer :as footer]
            [kmet.ui.tui.fork-selector :refer [show-fork-selector]]
            [kmet.ui.tui.hotkeys :as hotkeys-ui]
            [kmet.ui.tui.loaded-resources :as loaded-resources]
            [kmet.ui.tui.model-selector :refer [apply-model-switch! resolve-model-ref
                                                show-model-selector sync-footer-model!]]
            [kmet.ui.tui.scoped-models-selector :refer [show-scoped-models-selector]]
            [kmet.ui.tui.session-selector :refer [show-session-selector]]
            [kmet.ui.tui.settings-selector :refer [show-settings]]
            [kmet.ui.tui.tree-selector :refer [show-session-tree]]
            [kmet.ui.tui.subs :as subs]
            [kmet.ui.tui.thinking-selector :as thinking-selector]
            [kmet.libs.clipboard :as clipboard]
            [kmet.app.project-context :as context]
            [kmet.libs.process :as process]
            [kmet.tui.terminal-escape :as lib-term]
            [kmet.ui.tui.interactive.auth :as auth]
            [kmet.ui.tui.interactive.resources :as resources]
            [kmet.ui.tui.interactive.session-admin :as session-admin]
            [kmet.ui.tui.interactive.state :as state]
            [kmet.ui.tui.interactive.status :as status]
            [kmet.ui.tui.interactive.turn :as turn]))

;; ─── Command handling ──────────────────────────────────────────────────────

(defn- apply-thinking-level!
  "Apply a thinking level selection (pi selectThinkingLevel): set the agent's
   level — the session records a :thinking-level-change entry (pi appends to
   the session transcript; /resume restores it), push the level into the
   footer and the editor border color, and report via a status line. When
   PERSIST? the level is additionally saved as [:thinking] in settings.edn,
   the default for future sessions (pi options.persist →
   settingsManager.setDefaultThinkingLevel). The /thinking argument passes
   true — an explicit command request must survive a restart (kmet
   deviation: pi keeps the direct command session-scoped; kmet's Shift+Tab
   cycle, /settings row and /model argument all persist too) — while the
   selector keeps pi's split: Enter applies the level to the session only,
   Ctrl+S also sets the default."
  [cs level persist?]
  (runtime/set-thinking-level! (:engine cs) level)
  (sync-footer-model! cs)
  (state/update-editor-border-color! cs level)
  (chat-history/chat-history-show-status!
   (:chat-history cs)
   (str (if persist? "Default thinking level: " "Thinking level: ")
        (name level)))
  (when persist?
    (cfg/save-setting! [:thinking] level)))

(defn- handle-thinking-command!
  "pi handleThinkingCommand: a bare /thinking mounts the level selector; an
   argument applies the matching level directly and saves it as the settings
   default — matched case-insensitively against the current model's
   supported levels, with a warning listing the available levels on a miss
   (pi showError). Persisting is a kmet deviation: pi's direct command is
   session-scoped, but every explicit kmet path (Shift+Tab, /settings,
   /model) persists, so a level set from the command must survive a
   restart."
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
          (apply-thinking-level! cs level true)
          (chat-history/show-warning!
           (:chat-history cs)
           (str "Unknown thinking level \"" search-term
                "\". Available levels: "
                (str/join ", " (map name levels)) ".")))))))

(defn- help-text
  "Help message derived from the live command registry for this front end."
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
              (commands/get-commands :interactive))]
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
  "Text listing the tools this session runs on, with their parameters."
  [cs]
  (let [tool-list (sort-by :name (vals (runtime/tool-table (:engine cs))))
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
  "Per-tool result-token attribution for /session: each tool's
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
   per-tool result-token attribution (see tool-usage-text).
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
  (let [sess (runtime/session (:engine cs))
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
        (status/release-background-status! cs :share)
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
  "Register an interactive builtin slash command unless an extension
   already took the name (commands/register-builtin! owns the check — e.g.
   the shipped /tools extension replaces the builtin tools listing).

   The registry calls handlers with the command ctx ({:runtime :ui
   :frontend}); these builtins are written against the interactive UI
   value, so the wrapper passes (:state (:ui ctx)) — the CoreState. Tests
   that call a handler directly with a raw CoreState keep working: a map
   without :runtime is taken as the view value itself. Builtins carry the
   :interactive front-end tag, so a future non-terminal front end's
   autocomplete and dispatch filter them out."
  [cmd]
  (commands/register-builtin!

   (assoc cmd
          :frontend :interactive
          :handler (fn [ctx args]
                     ((:handler cmd)
                      (if (contains? ctx :runtime) (:state (:ui ctx)) ctx)
                      args)))))

(defn- make-hotkey-wired?
  "Predicate over a keybinding id: is it wired in this interactive mode?
   TUI ids are the editor's own (it implements the ids it declares); an app
   action counts only when a handler is installed on the editor
   (editor-set-on-action! — read live, so the predicate cannot drift from
   the wiring) or a global input listener owns the id (app.quit, see
   turn/global-quit-listener). /hotkeys shows wired bindings only: a declared
   pi-parity id whose action was never installed (app.message.copy in the
   main editor) stays out."
  [cs]
  (let [installed (into #{"app.quit"}
                        (when-let [ed (:editor cs)]
                          (editor/editor-app-action-ids ed)))]
    (fn [id]
      (or (not (str/starts-with? id "app."))
          (contains? installed id)))))

(defn register-builtin-commands!
  "Register kmet's builtin slash commands. Handlers receive [cs args];
   argument completions feed the editor autocomplete dropdown.

   ROOT-CTX-FN returns the ctx `/units` renders: the process tree, because the
   mounted units it lists hang under the root, not under the agent the command
   runs in (an agent's ctx holds what a preset composed on it). The layout
   passes the run's composition root; the 1-arity reads the process root, which
   is what kmet1's entry registers against (and what a bare test has)."
  ([config] (register-builtin-commands! config root/ctx))
  ([_config root-ctx-fn]
   (runtime-commands/register-runtime-commands! root-ctx-fn)
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
                                                        {:role :assistant :content (tools-text cs)}))})
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
                         models/thinking-levels)]
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
                  (show-session-selector cs (fn [] (state/ensure-session-dir (runtime/config (:engine cs))))
                                         (fn [path]
                                          ;; pi: emitBeforeSwitch (reason :resume)
                                          ;; — extensions may cancel the switch
                                           (let [result ((:resume-session! (:engine cs)) path)]
                                             (when-not (:cancelled result)
                                               (let [sess (:session result)
                                                     short-id (subs (:id sess) 0 (min 8 (count (:id sess))))]
                                                 (session-admin/install-live-session! cs)
                                                 (session-admin/replay-session! cs sess true)
                                                 (chat-history/chat-history-add-message! (:chat-history cs)
                                                                                         {:role :assistant
                                                                                          :content (str "Resumed session " short-id ".")})
                                                 (tui/tui-request-render (:tui cs)))))))))})
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
                                       (session-admin/ask-branch-summary cs (runtime/session (:engine cs)) entry)))))})
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
    {:name "session"
     :description "Show session info and stats"
     :handler (fn [cs _]
                (let [sess (runtime/session (:engine cs))
                      chat (:chat-history cs)]
                  (if (nil? sess)
                    (chat-history/chat-history-add-message! chat
                                                            {:role :info :label "Session"
                                                             :content "No active session."})
                    (chat-history/chat-history-add-message! chat
                                                            {:role :assistant
                                                             :content (session-info-text sess)}))))})
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

                    :ok
                    (cond
                      (nil? (runtime/session (:engine cs)))
                      (chat-history/chat-history-add-message! chat
                                                              {:role :info :label "Share"
                                                               :content "No active session."})

                     ;; one share at a time: the background-status release is
                     ;; kind-gated, so a second flow would take over the slot
                     ;; the first one clears when it finishes
                      (= :share (:kind (some-> (:status-current cs) deref)))
                      (chat-history/chat-history-add-message! chat
                                                              {:role :info :label "Share"
                                                               :content "A share is already in progress."})

                      :else
                      (share-session! cs)))))})
   (register-builtin-command!
    {:name "copy"
     :description "Copy last agent message to clipboard"
     :handler (fn [cs _]
                (let [sess (runtime/session (:engine cs))
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
     :description "Reload settings, extensions, skills, prompts, themes, and the builtin loop stages"
     :handler handle-reload})
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
     :argument-hint "<provider|provider#alias>"
     :get-argument-completions auth/login-argument-completions
     :handler (fn [cs args] (auth/handle-login-command! cs args))})
   (register-builtin-command!
   ;; pi: no argument hint and no completions — /logout always opens the
   ;; stored-credential selector
    {:name "logout"
     :description "Remove provider authentication"
     :handler (fn [cs _] (auth/handle-logout-command! cs))})))

(defn handle-reload
  "Reload settings, extensions, skills, prompts, themes and context files,
   re-seat the builtin loop stages, and rebuild the system prompt (pi:
   interactive-mode handleReloadCommand → session.reload →
   _rebuildSystemPrompt). Refuses while the agent is running (pi warns to wait
   for the current response)."
  [cs _]
  (let [{:keys [chat-history]} cs]
    (if (state/turn-running? cs)
      (chat-history/chat-history-add-message! chat-history
                                              {:role :info :label "Reload"
                                               :content "Wait for the current response to finish before reloading."})
      (try
        ;; pi: settingsManager.reload() + theme re-registration
        (let [config (cfg/init!)
              ;; a reloaded loop stage reaches the next attempt by re-seating
              ;; the app node's stages
              _ (runtime/refresh-loop-stages! (:engine cs))
              ;; the shell settings are mounted, so a reloaded setting reaches
              ;; the next run only if the mounted value is re-seated
              _ (shell/refresh! (runtime/agent-ctx (:engine cs))
                                (runtime/config (:engine cs)))
              ;; pi: keybindings.reload() — re-read keybindings.edn
              _ (app-kb/reload-agent-keybindings!)
        ;; pi: session.reload → emitSessionShutdownEvent(reason reload)
        ;; BEFORE the runner is torn down, so extensions can persist
        ;; state; then extension shutdown/start
              _ (events/emit! (runtime/agent-ctx (:engine cs))
                              {:type :session-shutdown :reason :reload})
              _ (extensions/ui-reset!)
              ;; extensions are plugin rows: reload the ones the composition
              ;; mounted in place (the world no longer loads any from settings)
              ext-results (extension-rows/reload-all!)
              _ (th/load-theme-paths! (packages/resolve-theme-paths))
              ;; pi: model-runtime.refresh — recompose providers from models.edn
              _ (models/load-models-config!)
              ;; pi: resourceLoader.reload (skills, prompts)
              _ (skills/clear-skills!)
              _ (prompts/clear-prompt-templates!)
              _ (packages/load-resources!)
              ;; pi: _rebuildSystemPrompt with new sources — the prompt is
              ;; built over the CURRENTLY active tool set (pi:
              ;; getActiveToolNames), so a pre-reload set-active-tools
              ;; restriction survives the reload. The :default-tools setting
              ;; is re-resolved first (pi: AgentSession.reload — tools newly
              ;; added to the setting become active; removed ones stay).
              _ (runtime/apply-default-tools! (:engine cs)
                                              (tools/resolve-default-tools
                                               (cfg/get-default-tools config)))
              active-tools (runtime/active-tools (:engine cs))
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
          ;; the runtime reads the config for session dirs and new sessions
          (runtime/set-config! (:engine cs) config)
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
          (runtime/set-system-prompt! (:engine cs) system-prompt system-prompt-opts)
          (loaded-resources/loaded-resources-set-sections!
           (:loaded-resources-comp cs) (resources/build-loaded-resource-sections))
          ;; pi: settingsManager.reload() — re-seed the live image settings
          ;; (/settings persists to settings.edn, whose merged value can also
          ;; change externally between reloads) and re-apply the provider
          ;; image-blocking knob to the running agent
          (reset! subs/image-settings-atom
                  {:show-images (cfg/get-show-images config)
                   :image-width-cells (cfg/get-image-width-cells config)})
          (runtime/set-block-images! (:engine cs) (cfg/get-block-images config))
          ;; pi: settingsManager.reload() — re-apply the clear-on-shrink knob
          ;; (the /settings row writes both the flag and the runtime)
          (when (:tui cs)
            (tui/tui-set-clear-on-shrink! (:tui cs) (cfg/get-clear-on-shrink config)))
          (state/update-footer! cs)
          ;; pi: reload re-emits session_start so extensions re-register UI.
          ;; Runs on a future — handlers may block on dialog promises, which
          ;; must never happen on the input thread.
          (future
            (try (events/emit! (runtime/agent-ctx (:engine cs))
                               {:type :session-start :reason :reload})
                 ;; pi: resources_discover fires after session_start (reason
                 ;; reload) — extensions contribute skill/prompt/theme paths
                 (extensions/discover-resources! (runtime/agent-ctx (:engine cs)) :reload)
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


