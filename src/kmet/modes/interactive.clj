(ns kmet.modes.interactive
  "Interactive TUI mode — main layout, agent integration, command handling,
   session browsing, bash commands, external editor.
   pi: modes/interactive/interactive-mode.ts."
  (:require [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as term]
            [kmet.tui.theme :as th]
            [kmet.tui.components.spacer :as spacer]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.expandable-text :as expandable-text]
            [kmet.tui.components.container :as container]
            [kmet.tui.hiccup :as hiccup]
            [kmet.libs.reakt :as r]
            [kmet.libs.host :as host]
            [kmet.app.ui :as ui]
            [kmet.app.ui.subs :as subs]
            [kmet.app.ui.image-block :as image-block]
            [kmet.app.ui.custom-dialog-adapter :as cda]
            [kmet.app.ui.auth-selector :as auth-selector]
            [kmet.app.ui.dock :as dock]
            [kmet.app.ui.login-dialog :as login-dialog]
            [kmet.app.ui.external-editor :refer [editor-text-get editor-text-get-expanded
                                                 editor-text-set! handle-external-editor]]
            [kmet.app.ui.fork-selector :refer [show-fork-selector]]
            [kmet.app.ui.model-catalog :as model-catalog]
            [kmet.app.ui.model-selector :refer [apply-model-switch!
                                                resolve-model-ref show-model-selector
                                                sync-footer-model!]]
            [kmet.app.ui.scoped-models-selector :refer [show-scoped-models-selector]]
            [kmet.app.ui.settings-selector :refer [show-settings]]
            [kmet.app.ui.session-selector :refer [show-session-selector]]
            [kmet.app.ui.thinking-selector :as thinking-selector]
            [kmet.app.ui.tree-selector :refer [show-session-tree]]
            [kmet.app.ui.footer :as footer]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.app.ui.hotkeys :as hotkeys-ui]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.tui.components.select-list :as select-list]
            [kmet.app.loop :as agent]
            [kmet.ai.models :as models]
            [kmet.ai.auth :as auth]
            [kmet.app.session :as session]
            [kmet.app.session-export :as session-export]
            [kmet.libs.clipboard :as clipboard]
            [kmet.app.tools.core :as tools]
            [kmet.app.keybindings :as app-kb]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.config :as cfg]
            [kmet.ai.api.shared :as shared]
            [kmet.app.skills :as skills]
            [kmet.libs.context :as context]
            [kmet.libs.usage :as usage]
            [kmet.app.prompts :as prompts]
            [kmet.app.commands :as commands]
            [kmet.app.extensions :as extensions]
            [kmet.app.event-bus :as event-bus]
            [kmet.app.packages :as packages]
            [kmet.tui.autocomplete :as ac]
            [kmet.tui.fuzzy :as fuzzy]
            [kmet.debug :as debug]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.app.bash-executor :as bash-exec]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.util :as tools-util]
            [kmet.app.ui.bash-execution :as be]
            [kmet.app.ui.dialogs :as dialogs]
            [kmet.tui.components.spinner :as spinner]
            [kmet.libs.process :as process]
            [kmet.libs.terminal :as lib-term]))

(declare clone-current-session! fork-at! restore-session! handle-new-session
         handle-import-command
         build-extension-ui-registry ask-branch-summary
         build-loaded-resource-sections start-agent-run!
         show-status-indicator! clear-status-indicator! release-background-status!
         stop-anim-timer!
         maybe-show-cache-miss-notice!
         make-widget-area-above make-widget-area-below
         send-message submit-message apply-hooks
         queue-follow-up-text!
         heal-stale-scrollback-when-idle!)

;; ─── Global config ref ────────────────────────────────────────────────────

(defonce ^:private global-config (atom nil))

;; ─── Session helpers ───────────────────────────────────────────────────────

(defn- get-session-dir []
  (if-let [c @global-config]
    (cfg/get-session-dir c)
    (str (System/getProperty "user.home") "/.kmet/sessions")))

(defn- ensure-session-dir
  "The base sessions dir (created). Listings (resume-session) walk it plus
   its cwd-encoded subdirectories (pi: listAll — G2), so legacy flat session
   files remain visible alongside per-project ones."
  []
  (let [d (get-session-dir)]
    (fs/create-dirs d)
    d))

(defn- ensure-cwd-session-dir
  "The sessions dir for a cwd — where new sessions are placed:
   BASE/<--cwd-->/ (pi: getDefaultSessionDir — per-project isolation, G2).
   The 0-arity uses the process cwd (startup, --continue); callers with a
   live session pass the runtime cwd (runtime-cwd), so a session resumed or
   imported from another project keeps creating its sessions in that
   project's dir (pi: newSession uses the runtime cwd, not process.cwd)."
  ([] (ensure-cwd-session-dir (str (fs/cwd))))
  ([cwd]
   (let [d (session/session-dir-for-cwd (get-session-dir) cwd)]
     (fs/create-dirs d)
     d)))

(defn- runtime-cwd
  "The working directory of CS's runtime (pi: AgentSession._cwd, seeded from
   the active session's recorded cwd): the footer data provider's cwd — the
   live value the footer pwd, the extension ctx.cwd, and tool components
   read — else the process cwd (tests, headless)."
  [cs]
  (or (some-> (:footer-provider cs) fdp/fdp-get-cwd) (str (fs/cwd))))

(defn- find-session
  "Most recent session for the current cwd (pi: continueRecent →
   findMostRecentSession — header-based discovery in the cwd-encoded dir;
   legacy headerless sessions and other-cwd files are excluded, no fallback).
   Used by --continue so the current project's last session is resumed."
  []
  (session/find-most-recent-session (ensure-cwd-session-dir) (str (fs/cwd))))

(defn- quote-if-needed
  "pi quoteIfNeeded — leave safe shell tokens bare, else single-quote with
   escaped single quotes."
  [value]
  (if (and (seq value)
           (every? (fn [c]
                     (or (Character/isLetterOrDigit ^char c)
                         (contains? #{\_ \- \. \/ \~ \: \@} c)))
                   value))
    value
    (str "'" (str/replace value "'" "'\\''") "'")))

(defn- format-resume-command
  "pi formatResumeCommand — build 'kmet --session <id>' (with --session-dir
   when non-default) or nil when stdout is not a tty, the session is not
   persisted (no file yet), or the file is missing."
  [sess config]
  (when (and sess (:file sess) (fs/exists? (:file sess)))
    (when (some? (System/console))
      (let [base-dir (cfg/get-session-dir config)
            default-base (cfg/expand-path "~/.kmet/sessions")
            base-is-default? (try
                               (= (str (fs/canonicalize base-dir))
                                  (str (fs/canonicalize default-base)))
                               (catch Exception _
                                 (= base-dir default-base)))
            actual-dir (str (fs/parent (:file sess)))
            sess-cwd (or (get-in sess [:header :cwd]) (str (fs/cwd)))
            expected-dir (session/session-dir-for-cwd base-dir sess-cwd)
            ;; Pi's usesDefaultSessionDir: true only when sessionDir equals
            ;; the encoded default for its cwd. For kmet that is actual-dir
            ;; == expected-dir *and* the base itself is the default base.
            ;; A custom base is always non-default even when the per-cwd
            ;; dir matches. An out-of-tree file (absolute --session path)
            ;; is also non-default.
            uses-default? (and base-is-default?
                               (try
                                 (= (str (fs/canonicalize actual-dir))
                                    (str (fs/canonicalize expected-dir)))
                                 (catch Exception _
                                   (= actual-dir expected-dir))))
            session-dir-arg (when-not uses-default?
                              (if (not base-is-default?)
                                base-dir
                                actual-dir))
            args (cond-> ["kmet"]
                   session-dir-arg (into ["--session-dir" (quote-if-needed session-dir-arg)])
                   true (into ["--session" (:id sess)]))]
        (str/join " " args)))))

(defn- resolve-session-arg
  "Resolve a --session arg to a session file path. Path-like values
   (containing / \\ or ending with .ednl/.jsonl) resolve relative to cwd;
   otherwise the arg is treated as a session id or id prefix and searched
   among all sessions under BASE-DIR (pi: resolveSessionPath). Returns the
   canonical path or nil when not found."
  [arg base-dir]
  (let [arg (cfg/expand-path (str arg))
        cwd (str (fs/cwd))]
    (if (or (str/includes? arg "/")
            (str/includes? arg "\\")
            (str/ends-with? arg ".ednl")
            (str/ends-with? arg ".jsonl"))
      (let [p (if (fs/absolute? arg) arg (str (fs/path cwd arg)))]
        (when (fs/exists? p)
          (str (fs/canonicalize p))))
      (let [infos (session/list-sessions-info base-dir)
            exact (some #(when (= (:id %) arg) (:path %)) infos)
            prefix (some #(when (str/starts-with? (:id %) arg) (:path %)) infos)]
        (or exact prefix)))))

;; ─── Core state ────────────────────────────────────────────────────────────

(defrecord CoreState [tui
                      agent-state
                      chat-history
                      editor
                      current-editor-atom
                      header-comp
                      loaded-resources-comp
                      anim-timer
                      footer-comp
                      footer-provider
                      status-indicator
                      status-root
                      status-current
                      pending-messages-comp
                      session-atom
                      running-turn?
                      compaction-queued
                      config
                      pending-tool-comps
                      bash-running?
                      bash-signal
                      pending-bash-components
                      pending-messages-container
                      dock-root
                      dock-current
                      theme-controller])

;; ─── Formatting helpers ────────────────────────────────────────────────────

(defn- fmt-key-hint
  "Pi: keyHint — dim key + muted description, from the live keybindings."
  [id desc]
  (app-kb/key-hint id desc))

(defn- fmt-raw-hint
  "Pi: rawKeyHint — dim literal key text + muted description."
  [key desc]
  (str (th/dim key) (th/fg (th/get-current-theme) :muted (str " " desc))))

(defn- fmt-header-logo
  "Pi: logo — bold accent app name, followed by the hosting runtime in
   parentheses (kmet deviation)."
  []
  (th/bold (th/fg (th/get-current-theme) :accent
                  (str "kmet (" (host/runtime-name) ")"))))

(defn- fmt-header-compact
  "Compact welcome header (pi: compactInstructions + compactOnboarding)."
  []
  (let [expand-key (or (app-kb/key-text "app.tools.expand") "Ctrl+O")
        compact-instructions
        (str/join (th/dim " · ")
                  [(fmt-key-hint "app.interrupt" "interrupt")
                   (fmt-raw-hint (str (or (app-kb/key-text "app.clear") "Ctrl+C")
                                      "/" (or (app-kb/key-text "app.exit") "Ctrl+D"))
                                 "clear/exit")
                   (fmt-raw-hint "/" "commands")
                   (fmt-raw-hint "!" "bash")
                   (fmt-key-hint "app.tools.expand" "more")])
        compact-onboarding (th/dim (str "Press " expand-key " to show full startup help and loaded resources."))]
    (str (fmt-header-logo) "\n" compact-instructions "\n" compact-onboarding)))

(defn- fmt-header-full
  "Full welcome header (pi: expandedInstructions)."
  []
  (let [clear-key (or (app-kb/key-text "app.clear") "Ctrl+C")
        expanded-instructions
        (str/join "\n"
                  [(fmt-key-hint "app.interrupt" "to interrupt")
                   (fmt-key-hint "app.clear" "to clear")
                   (fmt-raw-hint (str clear-key " twice") "to exit")
                   (fmt-key-hint "app.exit" "to exit (empty)")
                   (fmt-key-hint "app.quit" "to quit anywhere")
                   (fmt-key-hint "app.thinking.cycle" "to cycle thinking level")
                   (fmt-key-hint "app.model.cycleForward" "to cycle models")
                   (fmt-key-hint "app.model.select" "to select model")
                   (fmt-key-hint "app.tools.expand" "to cycle tool display")
                   (fmt-key-hint "app.thinking.toggle" "to expand thinking")
                   (fmt-key-hint "app.editor.external" "for external editor")
                   (fmt-raw-hint "/" "for commands")
                   (fmt-raw-hint "!" "to run bash")
                   (fmt-raw-hint "!!" "to run bash (no context)")
                   (fmt-key-hint "app.message.followUp" "to queue follow-up")
                   (fmt-key-hint "app.message.dequeue" "to edit all queued messages")])
        onboarding (th/dim "kmet can explain its own features. Ask it how to use or extend kmet.")]
    (str (fmt-header-logo) "\n" expanded-instructions "\n\n" onboarding)))

(defn- update-editor-border-color!
  "Update the editor border color to reflect the given thinking LEVEL.
   Pi: updateEditorBorderColor — sets borderColor based on session.thinkingLevel.
   Reads the ACTIVE theme (not the config :theme setting, which is a stale
   startup snapshot after /theme), so a theme switch re-styles the border."
  [cs level]
  (reset! (:border-fn (:editor cs))
          (th/get-thinking-border-color (th/get-current-theme) level)))

(defn- update-footer!
  "Sync the footer's session data source (cs → fdp bridge). No explicit
   invalidation: the footer's track! pass declares the fdp atoms — and the
   live session :entries vector, which mutates in place — as track-deps,
   so every change here re-derives the footer and schedules the frame
   reactively (§3.4 hook)."
  [cs]
  (ui/fdp-set-session! (:footer-provider cs) @(:session-atom cs))
  nil)

(defn- update-terminal-title!
  "Set the terminal window title to \"kmet - <session name> - <cwd basename>\"
   (pi: updateTerminalTitle — the session's runtime cwd, not the process
   cwd). The session display name is included when set (/name); an explicit
   empty name clears it, falling back to just app + cwd. No-ops when the
   TUI/terminal isn't live (e.g. tests with a stub tui)."
  [cs]
  (let [title (str "kmet"
                   (when-let [name (session/get-session-name @(:session-atom cs))]
                     (str " - " name))
                   " - " (fs/file-name (runtime-cwd cs)))]
    (when-let [term (:terminal (:tui cs))]
      (term/set-title! @term title))))

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
    (update-editor-border-color! cs level)
    (ui/chat-history-show-status!
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
        (ui/chat-history-show-status! (:chat-history cs)
                                      "Current model does not support thinking")
        (thinking-selector/show-thinking-selector
         cs
         :on-select (fn [level] (apply-thinking-level! cs level false))
         :on-persist (fn [level] (apply-thinking-level! cs level true))))
      (let [wanted (str/lower-case (str/trim search-term))
            level (first (filter #(= (name %) wanted) levels))]
        (if level
          (apply-thinking-level! cs level false)
          (ui/show-warning!
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

(defn- session-info-text
  "Pi: handleSessionCommand — stats + name + token/cost breakdown. Plain
   text with theme styling, rendered as an assistant message."
  [sess]
  (let [stats (session/get-session-stats sess)
        name (session/get-session-name sess)
        {:keys [input output cache-read cache-write]} (:tokens stats)
        prompt-tokens (+ input cache-read cache-write)
        cache-total (+ cache-read cache-write)
        breakdown (session/usage-breakdown sess)]
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
         (when (pos? (:cost stats))
           (str "\n" (th/bold "Cost") "\n"
                (th/dim "Total:") " $" (format "%.3f" (:cost stats))
                (when (> (count breakdown) 1)
                  (apply str (for [b breakdown]
                               (str "\n  " (th/dim (str (:key b) ":"))
                                    " $" (format "%.3f" (:cost b))
                                    " " (th/dim (str "(" (footer/format-tokens (:tokens b)) " tokens)"))))))
                "\n")))))

(defn- parse-path-argument
  "Pi: getPathCommandArgument — strip surrounding quotes, else take the
   first whitespace-delimited token. Returns nil when there is no
   argument. Shared by /export and /import."
  [args]
  (let [args (str/trim args)]
    (when (seq args)
      (let [first-char (first args)]
        (if (contains? #{\" \'} first-char)
          (when-let [end (str/index-of args first-char 1)]
            (subs args 1 end))
          (let [ws (str/index-of args " ")]
            (if ws (subs args 0 ws) args)))))))

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
    (show-status-indicator! cs :share indicator)
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
        (release-background-status! cs :share indicator)
        (ui/chat-history-add-message!
         chat
         (cond
           (= result :timeout)
           {:role :info :label "Share" :content "Gist creation timed out."}

           (:error result)
           {:role :info :label "Share"
            :content (str "Failed to create gist: " (:error result))}

           :else
           {:role :info :label "Share" :content (str "Share URL: " (:url result))}))))))

;; ─── Login/logout (pi getLoginProviderOptions / showLoginAuthTypeSelector /
;;    showLoginDialog / showApiKeyLoginDialog / OAuthSelectorComponent) ──────

(def ^:private api-key-login-label "Sign in with an API key")

(declare show-login-provider-selector!)

(defn- oauth-prompt!
  "Show PROMPT inside the dock-mounted login dialog and block for the
   entered string (pi showAuthPrompt → LoginDialogComponent.showPrompt /
   showManualInput / showAuthSelect; the flow runs on a future, so kmet
   blocks on the promise). :select swaps the dock to a method selector and
   restores the dialog after; :text/:secret prompts through the dialog's
   input; :manual-code is the manual-paste variant. The pending promise is
   registered in PROMPT-STATE so a loopback flow's :abort-prompt! can
   settle it when the browser callback wins the race. Dialog cancel settles
   the promise with the cancellation ex-info, which propagates here."
  [cs dlg prompt prompt-state]
  (case (:type prompt)
    :select
    (let [p (promise)
          labels (mapv :label (:options prompt))
          ;; pi showAuthSelect: swap the dock to the selector, restore the
          ;; login dialog when it resolves (re-mounting IS the restore)
          restore #(dock/mount! cs dlg)]
      (reset! prompt-state {:promise p})
      (dock/mount!
       cs (auth-selector/make-auth-method-selector
           (:message prompt) labels
           (fn [label]
             (restore)
             (deliver p (or (:id (first (filter #(= label (:label %)) (:options prompt))))
                            label)))
           (fn []
             (restore)
             (deliver p (ex-info "Login cancelled" {:type :login-cancelled})))))
      (login-dialog/await-prompt! p))

    :manual-code
    (let [p (login-dialog/login-dialog-show-manual-input! dlg (:message prompt))]
      (reset! prompt-state {:promise p})
      (login-dialog/await-prompt! p))

    (let [p (login-dialog/login-dialog-show-prompt!
             dlg (:message prompt) (:placeholder prompt))]
      (reset! prompt-state {:promise p})
      (login-dialog/await-prompt! p))))

(defn- oauth-notify!
  "Map an OAuth AuthEvent onto the dock-mounted login dialog (pi
   notifyAuthDialog): :device-code shows the verification URI + user code
   and the waiting line; :auth-url shows the URL (+ instructions) and opens
   the browser; :info/:progress append dim lines."
  [dlg event]
  (case (:type event)
    :device-code
    (do (login-dialog/login-dialog-show-device-code!
         dlg (:verification-uri event) (:user-code event))
        (login-dialog/login-dialog-show-waiting! dlg "Waiting for authentication..."))
    :auth-url
    (login-dialog/login-dialog-show-auth! dlg (:url event) (:instructions event))
    :info
    (login-dialog/login-dialog-show-info! dlg (:message event))
    (login-dialog/login-dialog-show-progress! dlg (:message event)))
  nil)

(defn- oauth-login!
  "Run an OAuthAuth login flow on a future with a dock-mounted login dialog
   (pi showLoginDialog): prompts and auth events render inside the dialog;
   on success the credential is persisted to auth.edn and the editor is
   restored. Escape cancels the flow through the dialog's on-complete.
   :abort-prompt! — the loopback flows' race hook — settles the pending
   manual-paste prompt as cancelled when the browser callback wins (pi
   manualAbort.abort())."
  [cs provider]
  (let [oauth (:oauth provider)
        signal (atom false)
        prompt-cancelled (atom false)
        prompt-state (atom nil)
        cancel-pending! (fn []
                          (reset! prompt-cancelled true)
                          (when-let [p (:promise @prompt-state)]
                            (deliver p (ex-info "Login cancelled"
                                                {:type :login-cancelled}))))
        dlg (login-dialog/make-login-dialog
             (:tui cs) (:name provider)
             (fn [_success _message]
               ;; escape — abort the flow like pi's AbortController; the
               ;; future unwinds at the next signal check and restores
               (reset! signal true)
               (cancel-pending!)))
        prompt-fn (fn [prompt]
                    (when @prompt-cancelled
                      (throw (ex-info "Login cancelled" {:type :login-cancelled})))
                    (oauth-prompt! cs dlg prompt prompt-state))
        interaction {:signal signal
                     :prompt prompt-fn
                     :abort-prompt! cancel-pending!
                     :notify (fn [event] (oauth-notify! dlg event))}
        done (dock/mount! cs dlg)]
    (future
      (try
        (let [credential ((:login oauth) interaction)]
          (auth/set-oauth-credential! (:id provider) credential)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :assistant
                                         :content (str "Logged in to " (:name provider)
                                                       ". Credentials saved to "
                                                       (auth/auth-file-path) ".")})
          (when (and (:session-atom cs) (:footer-comp cs) (:footer-provider cs))
            (update-footer! cs)))
        (catch Exception e
          ;; pi: silent on "Login cancelled", an error otherwise
          (when-not (str/includes? (or (ex-message e) "") "Login cancelled")
            (ui/show-warning! (:chat-history cs)
                              (str "Failed to login to " (:name provider) ": "
                                   (ex-message e)))))
        (finally
          (done)
          ;; release the dialog's content-tree reaction (rows watches) —
          ;; the dialog leaves the dock for good here
          (protocols/dispose dlg)
          (tui/tui-request-render (:tui cs)))))))

(defn- api-key-login!
  "The api-key login flow (pi showApiKeyLoginDialog): the key is prompted
   inside a dock-mounted \"Login to <provider>\" dialog and saved to
   auth.edn."
  [cs p]
  (let [dlg (login-dialog/make-login-dialog
             (:tui cs) (:name p)
             ;; escape before submitting — nothing to abort, silently
             ;; restore like pi (the "Login cancelled" error is suppressed)
             (fn [_success _message] nil))
        done (dock/mount! cs dlg)]
    (future
      (try
        (let [key (str/trim (login-dialog/await-prompt!
                             (login-dialog/login-dialog-show-prompt!
                              dlg (str "Enter " (:name p) " API key") nil)))]
          (if (seq key)
            (do (auth/set-credential! (:id p) key)
                (ui/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant
                                               :content (str "Saved API key for " (:name p)
                                                             ". Credentials saved to "
                                                             (auth/auth-file-path) ".")})
                (when (and (:session-atom cs) (:footer-comp cs) (:footer-provider cs))
                  (update-footer! cs)))
            (ui/show-warning! (:chat-history cs)
                              "No API key entered — nothing saved.")))
        (catch Exception e
          (when-not (str/includes? (or (ex-message e) "") "Login cancelled")
            (ui/show-warning! (:chat-history cs)
                              (str "Failed to save API key for " (:name p) ": "
                                   (ex-message e)))))
        (finally
          (done)
          ;; release the dialog's content-tree reaction (rows watches) —
          ;; the dialog leaves the dock for good here
          (protocols/dispose dlg)
          (tui/tui-request-render (:tui cs)))))))

;; ─── Provider options (pi getLoginProviderOptions / getLogoutProviderOptions
;;    / findLoginProviderOptions / handleLoginCommand) ────────────────────────

(defn- format-auth-type-label
  "pi formatAuthSelectorProviderType."
  [auth-type]
  (if (= :oauth auth-type) "subscription" "API key"))

(defn- api-key-login-path?
  "True when the provider offers an api-key login (env vars, models.edn
   configured key, or :auth-header) — openai-codex is oauth-only."
  [p]
  (or (seq (:env-vars p)) (:api-key p) (:auth-header p)))

(defn- login-provider-options
  "pi getLoginProviderOptions: one entry per offered auth type per provider,
   sorted by display name. Each entry carries the provider's auth status
   (auth/provider-auth-status) for the selector's ✓/• indicator."
  []
  (->> (models/get-providers)
       (mapcat (fn [p]
                 (let [status (auth/provider-auth-status (:id p))]
                   (concat
                    (when (:oauth p)
                      [{:id (name (:id p)) :name (:name p) :auth-type :oauth
                        :method-name (:name (:oauth p)) :status status
                        :provider p}])
                    (when (api-key-login-path? p)
                      [{:id (name (:id p)) :name (:name p) :auth-type :api-key
                        :status status :provider p}])))))
       (sort-by :name)))

(defn- logout-provider-options
  "pi getLogoutProviderOptions: one entry per stored credential, sorted by
   display name (the id stands in for providers no longer registered)."
  []
  (->> (auth/get-credentials)
       (mapv (fn [[pid cred]]
               (let [type (if (= :oauth (:type cred)) :oauth :api-key)
                     p (models/get-provider pid)]
                 {:id (name pid)
                  :name (or (:name p) (name pid))
                  :auth-type type
                  :status {:configured? true :type type :source "stored credential"}
                  :provider p})))
       (sort-by :name)))

(defn- find-login-provider-options
  "pi findLoginProviderOptions: exact id or name match on the lowercased
   reference, empty when nothing matches."
  [provider-ref]
  (let [ref (str/lower-case (str/trim (or provider-ref "")))]
    (when (seq ref)
      (filterv #(or (= (str/lower-case (:id %)) ref)
                    (= (str/lower-case (:name %)) ref))
               (login-provider-options)))))

(defn- start-provider-login!
  "pi startProviderLogin: oauth entries run the OAuth flow, api-key entries
   the key prompt."
  [cs entry]
  (if (= :oauth (:auth-type entry))
    (oauth-login! cs (:provider entry))
    (api-key-login! cs (:provider entry))))

(defn- show-login-auth-type-selector!
  "Offer a provider's (or the global) auth methods (pi
   showLoginAuthTypeSelector): the oauth subscription label (the OAuthAuth's
   :login-label or \"Sign in with an account\") and \"Sign in with an API
   key\" in a dock-mounted method selector; a single available method starts
   directly."
  ([cs] (show-login-auth-type-selector! cs nil))
  ([cs provider-options]
   (let [oauth-entry (some #(when (= :oauth (:auth-type %)) %) provider-options)
         subscription-label (or (when oauth-entry
                                  (:login-label (:oauth (:provider oauth-entry))))
                                "Sign in with an account")
         available-types (if provider-options
                           (set (map :auth-type provider-options))
                           #{:oauth :api-key})
         options (cond-> []
                   (contains? available-types :oauth) (conj subscription-label)
                   (contains? available-types :api-key) (conj api-key-login-label))]
     (cond
       (empty? options)
       (ui/chat-history-add-message! (:chat-history cs)
                                     {:role :assistant
                                      :content "No login methods available."})

       (and provider-options (= 1 (count options)))
       (start-provider-login! cs (first provider-options))

       :else
       (let [title (if (seq provider-options)
                     (str "Select authentication method for "
                          (:name (first provider-options)) ":")
                     "Select authentication method:")
             sel-atom (atom nil)]
         (reset! sel-atom
                 {:done (dock/mount!
                         cs
                         (auth-selector/make-auth-method-selector
                          title options
                          (fn [label]
                            ((:done @sel-atom))
                            (let [auth-type (if (= label subscription-label)
                                              :oauth :api-key)]
                              (if provider-options
                                (when-let [entry (some #(when (= auth-type (:auth-type %)) %)
                                                       provider-options)]
                                  (start-provider-login! cs entry))
                                (show-login-provider-selector! cs auth-type))))
                          (fn []
                            ((:done @sel-atom))
                            (tui/tui-request-render (:tui cs)))))}))))))

(defn- show-login-provider-selector!
  "pi showLoginProviderSelector: the searchable provider selector over the
   auth-type-filtered options; SEARCH pre-fills the filter (an unmatched
   /login argument). Cancel reopens the auth-type selector when one was
   shown (pi), otherwise just restores the editor."
  ([cs auth-type] (show-login-provider-selector! cs auth-type nil))
  ([cs auth-type search]
   (let [entries (vec (cond->> (login-provider-options)
                        auth-type (filter #(= auth-type (:auth-type %)))))]
     (if (empty? entries)
       (ui/chat-history-add-message! (:chat-history cs)
                                     {:role :assistant
                                      :content (case auth-type
                                                 :oauth "No subscription providers available."
                                                 :api-key "No API key providers available."
                                                 "No login providers available.")})
       (let [sel-atom (atom nil)]
         (reset! sel-atom
                 {:done (dock/mount!
                         cs
                         (auth-selector/make-auth-selector
                          :login entries
                          (fn [provider-id selected-type]
                            ((:done @sel-atom))
                            (if-let [entry (some #(when (and (= provider-id (:id %))
                                                             (= selected-type (:auth-type %)))
                                                    %)
                                                 entries)]
                              (start-provider-login! cs entry)
                              (tui/tui-request-render (:tui cs))))
                          (fn []
                            ((:done @sel-atom))
                            (if auth-type
                              (show-login-auth-type-selector! cs)
                              (tui/tui-request-render (:tui cs))))
                          search))}))))))

(defn- login-argument-completions
  "pi getArgumentCompletions for /login (getLoginProviderCompletionOptions +
   createFuzzyAutocompleteItems): one item per provider id with both auth
   types merged, sorted by display name, fuzzy-filtered over
   \"id name auth-types\" — the value completes to the provider id and the
   description reads \"Name · subscription/API key\"."
  [prefix]
  (let [options (->> (login-provider-options)
                     (group-by :id)
                     (mapv (fn [[id entries]]
                             (let [types (->> entries
                                              (map :auth-type) distinct
                                              (sort-by {:oauth 0 :api-key 1}))
                                   pname (:name (first entries))
                                   labels (map format-auth-type-label types)]
                               {:id id :name pname
                                :type-desc (str/join "/" labels)
                                :search (str/join " "
                                                  (concat [id pname]
                                                          (for [[t l] (map vector types labels)
                                                                part [(clojure.core/name t) l]]
                                                            part)))})))
                     (sort-by :name))
        filtered (fuzzy/fuzzy-filter options prefix :search)]
    (when (seq filtered)
      (mapv (fn [{:keys [id name type-desc]}]
              {:value id :label id
               :description (if (= name id) type-desc (str name " · " type-desc))})
            filtered))))

(defn- handle-login-command!
  "pi handleLoginCommand: bare /login opens the auth-type selector; a
   reference resolves by exact id/name (case-insensitive) — one hit starts
   directly, several hits on one provider open its method selector, and no
   hit opens the provider selector pre-filtered with the typed text."
  [cs provider-ref]
  (if (str/blank? provider-ref)
    (show-login-auth-type-selector! cs)
    (let [options (find-login-provider-options provider-ref)]
      (cond
        (= 1 (count options))
        (start-provider-login! cs (first options))

        (and (pos? (count options))
             (= 1 (count (distinct (map :id options)))))
        (show-login-auth-type-selector! cs options)

        :else
        (show-login-provider-selector! cs nil provider-ref)))))

(defn- handle-logout-command!
  "pi showOAuthSelector(\"logout\"): the searchable selector over the stored
   credentials; selecting removes the credential (pi modelRuntime.logout)."
  [cs]
  (let [entries (logout-provider-options)]
    (if (empty? entries)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant
                                     :content "No stored credentials to remove. /logout only removes credentials saved by /login; environment variables are unchanged."})
      (let [sel-atom (atom nil)]
        (reset! sel-atom
                {:done (dock/mount!
                        cs
                        (auth-selector/make-auth-selector
                         :logout entries
                         (fn [provider-id _selected-type]
                           ((:done @sel-atom))
                           (if-let [entry (some #(when (= provider-id (:id %)) %) entries)]
                             (try
                               (auth/remove-credential! (keyword provider-id))
                               (when (and (:session-atom cs) (:footer-comp cs)
                                          (:footer-provider cs))
                                 (update-footer! cs))
                               (ui/chat-history-add-message! (:chat-history cs)
                                                             {:role :assistant
                                                              :content (if (= :oauth (:auth-type entry))
                                                                         (str "Logged out of " (:name entry))
                                                                         (str "Removed stored API key for " (:name entry)
                                                                              ". Environment variables are unchanged."))})
                               (catch Exception e
                                 (ui/show-warning! (:chat-history cs)
                                                   (str "Logout failed: " (ex-message e)))))
                             (tui/tui-request-render (:tui cs))))
                         (fn []
                           ((:done @sel-atom))
                           (tui/tui-request-render (:tui cs)))))})))))

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
   global-quit-listener). /hotkeys shows wired bindings only: a declared
   pi-parity id whose action was never installed (app.suspend,
   app.message.copy in the main editor) stays out."
  [cs]
  (let [installed (into #{"app.quit"}
                        (when-let [ed (:editor cs)]
                          (editor/editor-app-action-ids ed)))]
    (fn [id]
      (or (not (str/starts-with? id "app."))
          (contains? installed id)))))

(defn- register-builtin-commands!
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
               (ui/chat-history-add-message! (:chat-history cs)
                                             {:role :assistant :content (help-text)}))})
  (register-builtin-command!
   {:name "hotkeys"
    :description "Show all keyboard shortcuts"
    :handler (fn [cs _]
               (ui/chat-history-add-message!
                (:chat-history cs)
                {:component (hotkeys-ui/make-hotkeys-view
                             (make-hotkey-wired? cs))}))})
  (register-builtin-command!
   {:name "tools"
    :description "List available tools with parameters"
    :handler (fn [cs _]
               (ui/chat-history-add-message! (:chat-history cs)
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
    :handler (fn [cs _] (handle-new-session cs))})
  (register-builtin-command!
   {:name "resume"
    :description "Browse past sessions"
    :handler (fn [cs _]
               (debug/log "/resume command")
               (show-session-selector cs ensure-session-dir
                                      (fn [path]
                                        ;; pi: emitBeforeSwitch (reason :resume)
                                        ;; — extensions may cancel the switch
                                        (when-not (:cancel (event-bus/emit-event!
                                                            {:type :session-before-switch
                                                             :reason :resume
                                                             :target-session-file path}))
                                          (let [sess (session/load-session path)
                                                short-id (subs (:id sess) 0 (min 8 (count (:id sess))))]
                                            (restore-session! cs sess true)
                                            (ui/chat-history-add-message! (:chat-history cs)
                                                                          {:role :assistant
                                                                           :content (str "Resumed session " short-id ".")})
                                            (tui/tui-request-render (:tui cs)))))))})
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
                   (ui/chat-history-add-message! chat-history
                                                 {:role :info :label "Continue"
                                                  :content "Wait for the current response to finish before continuing."})

                   @(:compacting? agent-state)
                   (ui/chat-history-add-message! chat-history
                                                 {:role :info :label "Continue"
                                                  :content "Wait for the in-progress compaction to finish before continuing."})

                   (empty? (agent/get-context agent-state))
                   (ui/chat-history-add-message! chat-history
                                                 {:role :info :label "Continue"
                                                  :content "No conversation to continue."})

                   :else
                   (do (debug/log "/continue command")
                       (start-agent-run! cs)))))})
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
                   (when (= :queued (queue-follow-up-text! cs text))
                     (ui/chat-history-show-status!
                      (:chat-history cs)
                      "Queued follow-up message")
                     (tui/tui-request-render (:tui cs)))
                   (ui/chat-history-add-message!
                    (:chat-history cs)
                    {:role :info :label "Follow-up"
                     :content "Usage: /followup <message>"}))))})
  (register-builtin-command!
   {:name "tree"
    :description "Navigate session tree (switch branches)"
    :handler (fn [cs _]
               (show-session-tree cs
                                  (fn [entry]
                                    (ask-branch-summary cs @(:session-atom cs) entry))))})
  (register-builtin-command!
   {:name "fork"
    :description "Create a new fork from a previous user message"
    :handler (fn [cs _]
               (show-fork-selector cs (fn [entry-id] (fork-at! cs entry-id))))})
  (register-builtin-command!
   {:name "clone"
    :description "Duplicate the current session at the current position"
    :handler (fn [cs _]
               (clone-current-session! cs))})
  (register-builtin-command!
   {:name "name"
    :description "Set session display name"
    :argument-hint "<name>"
    :handler (fn [cs args]
               (let [sess @(:session-atom cs)]
                 (if (nil? sess)
                   (ui/chat-history-add-message! (:chat-history cs)
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
                       (update-terminal-title! cs)
                       (when-not (= args sanitized)
                         ;; pi: warn when normalization changed the input
                         (ui/show-warning!
                          (:chat-history cs)
                          (str "Session name was normalized from " (pr-str args)
                               " to " (pr-str sanitized))))
                       (ui/chat-history-add-message! (:chat-history cs)
                                                     {:role :info :label "Name"
                                                      :content (str "Session name set: " sanitized)}))
                     (if-let [current (session/get-session-name sess)]
                       (ui/chat-history-add-message! (:chat-history cs)
                                                     {:role :info :label "Name"
                                                      :content (str "Session name: " current)})
                       (ui/show-warning! (:chat-history cs)
                                         "Usage: /name <name>"))))))})
  (register-builtin-command!
   {:name "session"
    :description "Show session info and stats"
    :handler (fn [cs _]
               (let [sess @(:session-atom cs)
                     chat (:chat-history cs)]
                 (if (nil? sess)
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Session"
                                                  :content "No active session."})
                   (ui/chat-history-add-message! chat
                                                 {:role :assistant
                                                  :content (session-info-text sess)}))))})
  (register-builtin-command!
   {:name "export"
    :description "Export session to HTML (JSONL is not supported by design)"
    :argument-hint "<path>"
    :handler (fn [cs args]
               (let [sess @(:session-atom cs)
                     chat (:chat-history cs)
                     arg (parse-path-argument args)]
                 (cond
                   (nil? sess)
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Export"
                                                  :content "No active session."})

                   (str/ends-with? (str/lower-case (or arg "")) ".jsonl")
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Export"
                                                  :content "JSONL export is not supported — kmet sessions are EDN-only. Use /export or /export <path.html>."})

                   :else
                   (try
                     (let [ag @(:agent-state cs)
                           system-prompt (or @(:system-prompt-override ag) @(:system ag))
                           tool-defs (vals (tools/get-all-tools))
                           opts (cond-> (when arg {:path arg})
                                  system-prompt (assoc :system-prompt system-prompt)
                                  (seq tool-defs) (assoc :tools tool-defs))
                           path (session-export/export-to-html! sess opts)]
                       (ui/chat-history-add-message! chat
                                                     {:role :info :label "Export"
                                                      :content (str "Session exported to: " path)}))
                     (catch Exception e
                       (ui/chat-history-add-message! chat
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
    :handler (fn [cs args] (handle-import-command cs args))})
  (register-builtin-command!
   {:name "share"
    :description "Share session as a secret GitHub gist"
    :handler (fn [cs _]
               (let [chat (:chat-history cs)]
                 (case (gh-auth-status)
                   :not-installed
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Share"
                                                  :content "GitHub CLI (gh) is not installed. Install it from https://cli.github.com/"})

                   :not-logged-in
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Share"
                                                  :content "GitHub CLI is not logged in. Run 'gh auth login' first."})

                   :timed-out
                   (ui/chat-history-add-message! chat
                                                 {:role :info :label "Share"
                                                  :content "GitHub CLI did not respond (timed out)."})

                   :else
                   (if (nil? @(:session-atom cs))
                     (ui/chat-history-add-message! chat
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
                   (ui/chat-history-add-message! chat
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
                         (ui/show-warning! chat
                                           "No clipboard tool available on this system.")))
                     (ui/show-warning! chat
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
                   (ui/chat-history-add-message! chat-history
                                                 {:role :info :label "Compact"
                                                  :content "Wait for the current response to finish before compacting."})

                   @(:compacting? agent-state)
                   (ui/chat-history-add-message! chat-history
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
                         (ui/chat-history-add-message!
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
                     (ui/chat-history-add-message! (:chat-history cs)
                                                   {:role :assistant
                                                    :content (if (:success result)
                                                               (str "Switched to theme \"" name "\".")
                                                               (str "Failed to load theme \"" name "\": "
                                                                    (:error result)))}))
                   (ui/chat-history-add-message! (:chat-history cs)
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
    :get-argument-completions login-argument-completions
    :handler (fn [cs args] (handle-login-command! cs args))})
  (register-builtin-command!
   ;; pi: no argument hint and no completions — /logout always opens the
   ;; stored-credential selector
   {:name "logout"
    :description "Remove provider authentication"
    :handler (fn [cs _] (handle-logout-command! cs))}))

(defn- handle-reload
  "Reload settings, extensions, skills, prompts, themes, context files, and
   rebuild the system prompt (pi: interactive-mode handleReloadCommand →
   session.reload → _rebuildSystemPrompt). Refuses while the agent is
   running (pi warns to wait for the current response)."
  [cs _]
  (let [{:keys [chat-history]} cs
        agent-state @(:agent-state cs)]
    (if-not (= :idle @(:status agent-state))
      (ui/chat-history-add-message! chat-history
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
                                  :context-files (context/load-project-context-files
                                                  (cfg/get-agent-dir) (str (fs/cwd)))
                                  :tools active-tools}
              system-prompt (apply skills/build-system-prompt
                                   (mapcat identity system-prompt-opts))]
          (reset! global-config config)
          (theme-ctrl/set-config! (:theme-controller cs) config)
;; pi: restoreChatBeforeSessionStart — re-apply hideThinkingBlock
          ;; and the tool display mode from settings to existing chat messages
          (ui/chat-history-set-thinking-hidden! chat-history
                                                (cfg/get-hide-thinking-block config))
          ;; the header and loaded resources follow the same mode as the chat
          ;; (the ctrl+o handler and the extension setter both keep them in
          ;; sync; reload must not leave them on a stale expansion)
          (let [mode (cfg/get-tool-display-mode config)]
            (ui/chat-history-set-tool-display-mode! chat-history mode)
            (when-let [hdr (:header-comp cs)]
              (expandable-text/expandable-text-set-expanded! hdr (= :expanded mode)))
            (when-let [lr (:loaded-resources-comp cs)]
              (ui/loaded-resources-set-expanded! lr (= :expanded mode))))
          (reset! (:system agent-state) system-prompt)
          (reset! (:system-prompt-opts agent-state) system-prompt-opts)
          (ui/loaded-resources-set-sections!
           (:loaded-resources-comp cs) (build-loaded-resource-sections))
          ;; pi: settingsManager.reload() — re-seed the live image settings
          ;; (/settings persists to settings.edn, whose merged value can also
          ;; change externally between reloads) and re-apply the provider
          ;; image-blocking knob to the running agent
          (reset! subs/image-settings-atom
                  {:show-images (cfg/get-show-images config)
                   :image-width-cells (cfg/get-image-width-cells config)})
          (agent/set-block-images! agent-state (cfg/get-block-images config))
          (update-footer! cs)
          ;; pi: reload re-emits session_start so extensions re-register UI.
          ;; Runs on a future — handlers may block on dialog promises, which
          ;; must never happen on the input thread.
          (future
            (try (event-bus/emit-event! {:type :session-start :reason :reload})
                 ;; pi: resources_discover fires after session_start (reason
                 ;; reload) — extensions contribute skill/prompt/theme paths
                 (extensions/discover-resources! :reload)
                 (catch Exception e (debug/log "session-start: " e))))
          (ui/chat-history-add-message! chat-history
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
          (ui/chat-history-add-message! chat-history
                                        {:role :info :label "Reload"
                                         :content (str "Reload failed: "
                                                       (or (ex-message e)
                                                           (.getName (class e))))}))))))

;; ─── Resume session ────────────────────────────────────────────────────────

(defn- custom-message-text
  "Plain text of a :custom message's content (string or text blocks) — the
   display text for custom_message entries/messages in the TUI (pi renders
   custom messages as labeled info boxes)."
  [m]
  (if (string? (:content m))
    (:content m)
    (str/join (for [b (:content m)
                    :when (= :text (:type b))]
                (:text b)))))

(defn- run-message-renderer
  "Run a registered extension message renderer for M. Returns the renderer's
   result (a chat message map, a bare component, or nil). A throwing or
   nil-returning renderer falls through to the default labeled box (pi:
   CustomMessageComponent rebuild catches renderer errors and keeps the
   default rendering when the renderer produces nothing)."
  [renderer m]
  (when renderer
    (try (renderer m) (catch Exception _ nil))))

(defn- run-entry-renderer
  "Run a registered extension entry renderer for ENTRY. Returns the
   renderer's result (a chat message map, a bare component, or nil). A
   throwing renderer yields a visible error line naming the custom type
   (pi: CustomEntryComponent rebuild catches renderer errors and renders
   `[type] renderer failed: message`)."
  [renderer entry]
  (when renderer
    (try (renderer entry)
         (catch Exception e
           (let [ct (or (:custom-type entry) "custom")]
             {:role :notice
              :style :error
              :content (str "[" (if (keyword? ct) (name ct) (str ct))
                            "] renderer failed: "
                            (or (ex-message e) (str (class e))))})))))

(defn- renderer-result->message
  "Normalize an extension renderer result for chat-history-add-message!:
   a plain message map passes through, a bare component is wrapped as
   {:component c}. Components are records and records satisfy map?, so a
   plain map? test would misclassify every component as a message map."
  [rendered]
  (if (and (map? rendered) (not (record? rendered)))
    rendered
    {:component rendered}))

(defn- compaction-cost-notice
  "pi: addCompactionCostNotice — the warning line appended after a
   compaction / branch summary whose summarization call recorded usage
   (entry :usage). Gated on :show-cache-miss-notices, like the assistant
   cache-miss notice. Returns the message map to append, or nil."
  [config variant usage]
  (when (and (cfg/get-show-cache-miss-notices config) usage)
    (when-let [u (usage/entry-usage usage)]
      (let [tokens (+ (long (or (:input u) 0)) (long (or (:output u) 0))
                      (long (or (:cache-read u) 0)) (long (or (:cache-write u) 0)))
            cost (:cost u)]
        (when (pos? tokens)
          {:role :notice
           :style :warning
           :content (str (case variant
                           :compaction "Compaction"
                           :branch-summary "Branch summary"
                           "Summary")
                         ": " (footer/format-tokens tokens) " tokens billed"
                         (when (and cost (>= (double cost) 0.01))
                           (str " (~$" (format "%.2f" (double cost)) ")")))})))))

(defn- replay-branch!
  "Replay a session's compaction-aware context into the chat history (pi:
   renderInitialMessages — buildContextEntries, so summarized history is
   not re-rendered; only message entries; session_info, label and
   model/thinking change entries are metadata and never rendered; custom
   (extension state) entries render when an extension registered a renderer
   for their custom-type; compaction and branch_summary entries render as
   dedicated collapsible summary boxes; custom_message entries render as
   labeled info boxes when their display flag is set). Tool results replay
   as ToolExecutionComponents created from the assistant entry's
   :tool-calls (name + args) and filled by the matching :tool result entry
   by tool-call id (pi: renderedPendingTools); a result without a matching
   call renders standalone from its own :tool-name; !/!! bash entries replay
   as completed BashExecutionComponents (pi: addMessageToChat case
   bashExecution)."
  [cs sess]
  (ui/chat-history-clear! (:chat-history cs))
  (let [content-of
        (fn [e]
          (str/join
           (keep (fn [b]
                   (case (:type b)
                     :text (:text b)
                     :tool_result (:content b)
                     nil))
                 (:content e))))
        ;; Pi: renderedPendingTools — tool-call id → ToolExecutionComponent
        ;; created from the assistant message's tool calls, filled by the
        ;; matching tool-result entry (results can arrive out of order with
        ;; parallel tools).
        pending-tools (atom {})]
    (doseq [e (session/build-context sess)
            :when (not (contains? #{:session_info :label :model-change
                                    :thinking-level-change} (:role e)))]
      (let [role (:role e)]
        (cond
          (= role :custom)
          ;; extension state entries render only with a registered renderer
          ;; (pi: registerEntryRenderer + CustomEntryComponent); a renderer
          ;; failure renders the pi error line instead of crashing the replay
          (when-let [rendered (run-entry-renderer
                               (extensions/get-entry-renderer (:custom-type e)) e)]
            (ui/chat-history-add-message! (:chat-history cs)
                                          (renderer-result->message rendered)))

          (= role :custom-message)
          ;; extension custom messages render only when display is set (pi:
          ;; the display flag controls TUI rendering); content may be a string
          ;; or a block vector (pi CustomMessageEntry). A registered message
          ;; renderer overrides the default labeled info box.
          (when (:display e)
            (ui/chat-history-add-message!
             (:chat-history cs)
             (if-let [rendered (run-message-renderer
                                (extensions/get-message-renderer (:custom-type e)) e)]
               (renderer-result->message rendered)
               {:role :info
                :content (custom-message-text e)
                :images (image-block/content-images (:content e))
                :label (:custom-type e)})))

          (= role :assistant)
          (do
            (ui/chat-history-add-message! (:chat-history cs)
                                          (cond-> {:role role :content (content-of e)}
                                            (= role :assistant) (assoc :thinking (:thinking e)
                                                                       ;; replayed tool-call-only
                                                                       ;; messages render no
                                                                       ;; '(no response)' bubble
                                                                       :tool-calls (:tool-calls e))))
            ;; Pi: create a ToolExecutionComponent per tool call declared in
            ;; the assistant message (name + args from the call — the same
            ;; fields the live :tool-execution-start event carries), then
            ;; match the following tool-result entries by tool-call id. The
            ;; tool entries themselves only store the pi-faithful
            ;; :tool-name/:content — the call line (args) lives here.
            ;; Tool calls inside an errored/aborted message get the failure
            ;; text as their result instead of waiting for a result that
            ;; never came (pi: renderInitialMessages updateResult error).
            (let [errored? (contains? #{:error :aborted} (:stop-reason e))]
              (doseq [tc (:tool-calls e)]
                (when-let [comp (ui/chat-history-add-message!
                                 (:chat-history cs)
                                 {:role :tool
                                  :name (:name tc)
                                  :args (:arguments tc)
                                  :content ""
                                  :is-error false})]
                  (reset! (:tool-call-id-atom comp) (:id tc))
                  (ui/tool-execution-set-args-complete! comp)
                  (if errored?
                    (do (reset! (:content-atom comp)
                                (or (:error-message e)
                                    (if (= :aborted (:stop-reason e))
                                      "Aborted"
                                      "Error")))
                        (ui/tool-execution-set-error! comp true))
                    (swap! pending-tools assoc (:id tc) comp))))))

          (= role :tool)
          (let [tc-id (some (fn [b] (when (= :tool_result (:type b))
                                      (:tool_use_id b)))
                            (:content e))]
            (if-let [comp (get @pending-tools tc-id)]
              ;; matched result — fill the pending call component (pi:
              ;; updateResult by toolCallId)
              (do (reset! (:content-atom comp) (content-of e))
                  (ui/tool-execution-set-error! comp (:is-error e false))
                  (when-let [truncation (:truncation e)]
                    (reset! (:truncation-atom comp) truncation))
                  (when-let [details (:details e)]
                    (reset! (:details-atom comp) details))
                  (when-let [images (:images e)]
                    (ui/tool-execution-set-images! comp images))
                  (swap! pending-tools dissoc tc-id))
              ;; unpaired result (no matching tool call in the branch —
              ;; legacy sessions, extension tools) — standalone component
              ;; from the entry's own fields
              (ui/chat-history-add-message!
               (:chat-history cs)
               (cond-> {:role :tool
                        :content (content-of e)
                        :name (or (:tool-name e) (:name e) "tool")
                        :is-error (:is-error e false)
                        :truncation (:truncation e)
                        :details (:details e)}
                 ;; image results carry their blocks on the entry (pi:
                 ;; toolResult content blocks) — the callback component
                 ;; renders them like a matched result's would
                 (seq (:images e)) (assoc :images (:images e))))))

          (= role :bash)
          ;; pi: addMessageToChat case "bashExecution" — !/!! replays as a
          ;; COMPLETED BashExecutionComponent (no spinner, no duration)
          (let [comp (be/make-bash-execution
                      :command (:command e)
                      :exclude-from-context? (:exclude-from-context? e)
                      :replayed? true
                      :tools-expanded-atom (:tools-expanded-atom (:chat-history cs)))]
            (when (seq (:output e))
              (be/bash-execution-append-output! comp (:output e)))
            (be/bash-execution-set-complete! comp (:exit-code e) (:cancelled e false)
                                             :truncation (when (:truncated e) {:truncated true})
                                             :full-output-path (:full-output-path e))
            (ui/chat-history-add-message! (:chat-history cs)
                                          {:role :bash :command (:command e) :component comp}))

          ;; pi: CompactionSummaryMessageComponent / BranchSummary — dedicated
          ;; collapsible summary boxes; a usage-carrying summary appends the
          ;; billing notice when :show-cache-miss-notices is on
          (contains? #{:compaction :branch-summary} role)
          (do
            (ui/chat-history-add-message!
             (:chat-history cs)
             (cond-> {:role role :summary (:summary e) :tokens-before (:tokens-before e)}
               (:usage e) (assoc :usage (:usage e))))
            (when-let [notice (compaction-cost-notice (:config cs) role (:usage e))]
              (ui/chat-history-add-message! (:chat-history cs) notice)))

          :else
          (ui/chat-history-add-message!
           (:chat-history cs)
           (cond-> {:role role :content (content-of e)}
             ;; user-attached images replay too — content-of keeps only the
             ;; text, so the image blocks must ride the message's :images
             ;; (the live path carries them inside :content)
             (= role :user) (assoc :images (image-block/content-images (:content e)))
             (= role :info) (assoc :label (:label e)))))))))

(defn- refresh-system-prompt!
  "Rebuild the system prompt for CWD (pi: a new runtime rebuilds it from its
   own cwd): the project context files are re-read from the new directory
   and the prompt's Current working directory line follows. The
   loaded-resources display is rebuilt with them (pi: showLoadedResources)."
  [cs cwd]
  (let [ag @(:agent-state cs)
        opts (assoc @(:system-prompt-opts ag)
                    :cwd cwd
                    :context-files (context/load-project-context-files
                                    (cfg/get-agent-dir) cwd))]
    (reset! (:system-prompt-opts ag) opts)
    (reset! (:system ag) (apply skills/build-system-prompt (mapcat identity opts)))
    (when-let [lr (:loaded-resources-comp cs)]
      (ui/loaded-resources-set-sections! lr (build-loaded-resource-sections)))))

(defn- apply-session-cwd!
  "Point the runtime at SESS's working directory (pi: createRuntime's cwd +
   footerDataProvider.setCwd — a resumed or imported session brings its
   recorded cwd): the footer pwd, the extension ctx.cwd, tool components and
   the tools' relative-path resolution all read the footer provider's cwd,
   and the system prompt is rebuilt for the new directory. A recorded cwd
   that no longer exists keeps the current one (pi asks via
   MissingSessionCwdError; kmet says so and continues). Returns the cwd in
   effect."
  [cs sess]
  (let [fdp* (:footer-provider cs)
        recorded (get-in sess [:header :cwd])
        cwd (session/session-cwd sess)
        current (when fdp* (fdp/fdp-get-cwd fdp*))]
    (cond
      (or (nil? fdp*) (= cwd current))
      current

      cwd
      (do (fdp/fdp-set-cwd! fdp* cwd)
          (refresh-system-prompt! cs cwd)
          cwd)

      :else
      (do (ui/chat-history-show-status!
           (:chat-history cs)
           (str "Session cwd " recorded " no longer exists — continuing in " current))
          current))))

(defn- restore-session!
  "Restore a session into the UI and the agent: swap the active session,
   rebuild the agent's in-memory context from the session branch (pi: the
   session is the source of truth — buildContextEntries; steered and
   follow-up user messages live in the branch and must come back for the
   next LLM call), and replay the branch into the chat history. session_info
   entries are metadata — never rendered (pi: only message entries are
   replayed on resume). When APPLY-SETTINGS? is true (startup resume,
   /resume — pi: createAgentSession), the session-derived model/thinking are
   applied to the agent and the footer refreshes; fork and clone pass false
   (pi: navigateTree keeps the current agent state)."
  [cs sess apply-settings?]
  (reset! (:session-atom cs) sess)
  (extensions/set-session! sess)
  ;; pi: renderCurrentSessionState — a session switch drops the compaction
  ;; queue (compactionQueuedMessages = [])
  (reset! (:compaction-queued cs) [])
  (let [new-ag (assoc @(:agent-state cs) :session sess)]
    (reset! (:agent-state cs) new-ag))
  (agent/restore-session-context! @(:agent-state cs))
  (when apply-settings?
    (agent/apply-session-settings! @(:agent-state cs))
    (sync-footer-model! cs))
  (replay-branch! cs sess)
  ;; pi: renderInitialMessages — "Session compacted N times" status when
  ;; the session file (any branch) contains compactions
  (let [n (count (filter #(= :compaction (:role %)) @(:entries sess)))]
    (when (pos? n)
      (ui/chat-history-show-status!
       (:chat-history cs)
       (str "Session compacted " n (if (= n 1) " time" " times")))))
  ;; pi: createRuntime's cwd + footerDataProvider.setCwd — the session's
  ;; working directory becomes the runtime's (tools, the prompt's cwd line,
  ;; the footer pwd and ctx.cwd all follow it)
  (apply-session-cwd! cs sess)
  ;; Repopulate the editor's prompt history only on the resume paths
  ;; (startup --continue, /resume — pi: renderInitialMessages with
  ;; populateHistory). Fork/clone keep the shared editor's existing history
  ;; (pi: navigateTree/switchSession reuse the same editor instance).
  (when apply-settings?
    (editor/editor-set-history! (:editor cs) (session/get-prompt-history sess)))
  (update-footer! cs)
  (update-terminal-title! cs))

(defn- handle-new-session
  "Pi: handleClearCommand → runtimeHost.newSession. Fully reset the
   conversation: settle the in-flight run and compaction first so the
   aborted turn (incl. pending bash results) persists to the OUTGOING
   session (pi: teardownCurrent awaits session.abort), then swap in a fresh
   session, rebuild the agent's in-memory context from it — empty (pi:
   createRuntime; the session is the source of truth) — reset per-run
   state, and clear the chat, pending container, and editor (pi:
   editor.setText(\"\")). Emits :session-before-switch (extensions may
   cancel), :session-shutdown (reason :new, target-session-file) before the
   swap and :session-start (reason :new, previous-session-file) after it
   for extensions (pi: teardownCurrent → session_start on every switch)."
  [cs]
  (let [previous-file (:file @(:session-atom cs))
        ;; pi: emitBeforeSwitch — extensions may cancel the switch; the
        ;; conversation stays completely untouched
        switch-result (event-bus/emit-event! {:type :session-before-switch
                                              :reason :new
                                              :target-session-file previous-file})]
    (when-not (:cancel switch-result)
      (let [ag @(:agent-state cs)
            was-running @(:running-turn? cs)]
        ;; Settle in-flight work so it lands in the outgoing session and cannot
        ;; race the swap. Queued steering/follow-up are dropped — a new session
        ;; discards them (unlike cancel, which restores them to the editor).
        (when was-running
          (agent/cancel-turn ag))
        (when @(:compacting? ag)
          (reset! (:signal ag) true))
        (when @(:bash-running? cs)
          (reset! (:bash-signal cs) true)
          (reset! (:bash-running? cs) false))
        ;; Wait (bounded) for the cancelled run's finally to drain pending bash
        ;; results and for an in-flight compaction to settle — its context sync
        ;; must not run after the swap.
        (let [deadline (+ (System/currentTimeMillis) 3000)]
          (loop []
            (when (and (or (seq @(:pending-bash ag)) @(:compacting? ag))
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 10)
              (recur))))
        (when (and (not @(:compacting? ag))
                   (empty? @(:pending-bash ag)))
          (reset! (:signal ag) false))
        (when was-running
          (reset! (:running-turn? cs) false)
          (stop-anim-timer! cs)
          (clear-status-indicator! cs))
        ;; pi: teardownCurrent — after the run is settled, tell extensions the
        ;; runtime is being torn down (reason :new, destination session file)
        ;; so they can persist state before the swap.
        (event-bus/emit-event! {:type :session-shutdown :reason :new
                                :target-session-file previous-file})
        (let [new-session (session/create-session (ensure-cwd-session-dir (runtime-cwd cs))
                                                  ;; the new session belongs to the runtime's
                                                  ;; project (pi: newSession keeps this.cwd)
                                                  {:cwd (runtime-cwd cs)})]
          (debug/log "new session created: " (:id new-session))
          (ui/chat-history-clear! (:chat-history cs))
          (ui/dispose-pending-bash! @(:pending-bash-components cs))
          (container/container-clear (:pending-messages-container cs))
          ;; pi: a session switch drops the compaction queue
          ;; (compactionQueuedMessages = [])
          (reset! (:compaction-queued cs) [])
          ;; Re-attach the PendingMessages component — container-clear removes
          ;; every child (queued steering/follow-up display included), and the
          ;; display must keep rendering for messages queued after /new
          (when-let [pm (:pending-messages-comp cs)]
            (container/container-add-child (:pending-messages-container cs) pm))
          (reset! (:pending-bash-components cs) [])
          (editor-text-set! @(:current-editor-atom cs) "")
          (reset! (:session-atom cs) new-session)
          (extensions/set-session! new-session)
          (let [new-ag (assoc ag :session new-session)]
            (reset! (:agent-state cs) new-ag)
            ;; Rebuild the in-memory context from the new session — empty — and
            ;; reset per-run state (pi: createRuntime builds a fresh agent
            ;; state; the hook/config atoms are kept — not session state).
            (agent/restore-session-context! new-ag)
            (reset! (:status ag) :idle)
            (reset! (:steering ag) [])
            (reset! (:follow-up ag) [])
            (reset! (:pending-bash ag) [])
            (reset! (:overflow-recovered ag) false)
            (reset! (:retry-count ag) 0))
      ;; pi: newSession emits session_start (reason "new",
      ;; previousSessionFile) — on a future, handlers may block on dialog
      ;; promises (same as /reload).
          (future
            (try
              (event-bus/emit-event!
               (cond-> {:type :session-start :reason :new}
                 previous-file (assoc :previous-session-file previous-file)))
              ;; pi: resources_discover fires after session_start (reason
              ;; startup for non-reload session starts)
              (extensions/discover-resources! :startup)
              (catch Exception e (debug/log "session-start: " e))))
          (update-footer! cs)
          (update-terminal-title! cs)
          (tui/tui-request-render (:tui cs))
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :assistant :content "Started a new session."}))))))

;; ─── Import (/import — pi: handleImportCommand) ───────────────────────────

(defn- import-error!
  "pi: showError — the Error: line in the transcript."
  [chat message]
  (ui/chat-history-add-message! chat {:role :error :content message}))

(defn- import-failed!
  "pi: the catch in handleImportCommand — showError with pi's prefix."
  [chat e]
  (import-error! chat (str "Failed to import session: "
                           (or (ex-message e) (str e)))))

(defn- show-import-confirm!
  "pi: showExtensionConfirm — ask through a dock-mounted Yes/No selector
   (pi: showExtensionSelector of [\"Yes\" \"No\"]): TITLE with MESSAGE as
   its second header line. ON-CONFIRM runs on Yes; No or escape shows pi's
   \"Import cancelled\" status."
  [cs title message on-confirm]
  (let [tui (:tui cs)
        chat (:chat-history cs)
        ;; late binding: the callbacks reach the mount's done through this
        ;; atom (pi: done() is created by showSelector)
        sel-atom (atom nil)
        close! (fn []
                 ((:done @sel-atom))
                 (tui/tui-request-render tui))
        cancelled! (fn []
                     (ui/chat-history-show-status! chat "Import cancelled")
                     (tui/tui-request-render tui))
        dlg (dialogs/make-selector-dialog
             (str title "\n" message)
             ["Yes" "No"]
             (fn [choice]
               (close!)
               (if (= "Yes" choice) (on-confirm) (cancelled!)))
             (fn [] (close!) (cancelled!))
             (th/get-current-theme))]
    ;; pi: showSelector — the selector replaces the editor dock
    (reset! sel-atom {:done (dock/mount! cs dlg)})
    (tui/tui-request-render tui)))

(defn- handle-import-command
  "pi: handleImportCommand — copy a session file into the active session's
   directory and resume it. The path may be quoted, trailing arguments are
   ignored (pi: getPathCommandArgument, shared with /export). kmet sessions
   are EDN and pi imports its JSONL, so the file must open with a :session
   header. Written against kmet's session-switch path rather than pi's
   teardownCurrent: an in-flight turn refuses the import (like /fork and
   /clone — the switch path does not abort) and the switch emits
   :session-before-switch, exactly as /resume does. The session's recorded
   cwd becomes the runtime cwd (restore-session! → apply-session-cwd!), so
   the tools, the prompt's cwd line and the footer follow it, as in pi's
   createRuntime."
  [cs args]
  (let [chat (:chat-history cs)
        path-arg (parse-path-argument args)]
    (cond
      (nil? path-arg)
      (import-error! chat "Usage: /import <path>")

      @(:running-turn? cs)
      (ui/chat-history-add-message! chat
                                    {:role :assistant
                                     :content "Wait for the current response to finish before importing."})

      :else
      (let [sess @(:session-atom cs)
            ;; pi: this.session.sessionManager.getSessionDir() — the active
            ;; session's directory; a not-yet-persisted session still carries
            ;; its computed :file (lazy creation)
            dest-dir (or (some-> (:file sess) fs/parent str)
                         (ensure-cwd-session-dir))]
        (try
          (let [plan (session/plan-session-import path-arg dest-dir)]
            (show-import-confirm!
             cs "Import session"
             (str "Replace current session with " (:source plan) "?")
             (fn []
               (try
                 ;; pi: emitBeforeSwitch (reason :resume) — extensions may
                 ;; cancel; the copy is still pending there, so a cancelled
                 ;; import leaves no trace
                 (if (:cancel (event-bus/emit-event!
                               {:type :session-before-switch
                                :reason :resume
                                :target-session-file (:path plan)}))
                   (ui/chat-history-show-status! chat "Import cancelled")
                   (do (session/copy-imported-session! plan)
                       (restore-session! cs (session/load-session (:path plan)) true)
                       (ui/chat-history-show-status!
                        chat (str "Session imported from: " (:source plan)))
                       (tui/tui-request-render (:tui cs))))
                 (catch Exception e
                   (import-failed! chat e))))))
          (catch Exception e
            (import-failed! chat e)))))))

;; ─── Session tree navigation (pi: TreeSelectorComponent) ──────────────────

(defn- session-entry-text
  "Plain trimmed text of a session entry's content blocks (pi:
   extractUserMessageText — used for fork/tree editor restore)."
  [e]
  (let [content (:content e)]
    (if (string? content)
      (str/trim content)
      (str/trim (str/join (map :text (filter #(= :text (:type %)) content)))))))

(defn- complete-tree-navigation!
  "Apply a tree navigation (pi: navigateTree tail): branch the session leaf
   (with an optional branch summary), rebuild the agent context and chat
   history from the new branch, restore USER-MSG-TEXT into the editor when
   navigating to a user message (only when the editor is empty), attach
   LABEL to the summary/target entry when given, and emit :session-tree.
   SUMMARY-RESULT is nil or {:summary str :usage usage-map} (the usage of
   the summarization call, recorded on the branch-summary entry — pi:
   BranchSummaryEntry.usage)."
  [cs sess old-leaf target-leaf summary-result user-msg-text from-extension? label]
  (try
    ;; pi: renderCurrentSessionState on session rebind — the compaction
    ;; queue does not survive navigation
    (reset! (:compaction-queued cs) [])
    (let [summary-entry (if summary-result
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
      (agent/restore-session-context! @(:agent-state cs))
      (replay-branch! cs sess)
      ;; pi: restore the user message only when the editor is empty — a draft
      ;; the user is composing is not clobbered by navigation
      (when (and user-msg-text
                 (str/blank? (editor-text-get (:editor cs))))
        (editor-text-set! (:editor cs) user-msg-text))
      (update-footer! cs)
      (event-bus/emit-event!
       (cond-> {:type :session-tree
                :new-leaf-id @(:leaf-id sess)
                :old-leaf-id old-leaf
                :from-extension? (boolean from-extension?)}
         summary-entry (assoc :summary-entry summary-entry)))
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant
                                     :content (if summary-result
                                                "Navigated to the selected point (branch summarized)."
                                                "Navigated to the selected point.")})
      (tui/tui-request-render (:tui cs)))
    (catch Exception e
      (debug/log "tree navigation failed: " e)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :info :label "Tree"
                                     :content (str "Navigation failed: " (ex-message e))})
      (tui/tui-request-render (:tui cs)))))

(defn- branch-summarize-and-apply!
  "Run the LLM branch summarization (pi: navigateTree summarize) with the
   BranchSummaryStatusIndicator and editor-escape abort, then branch with
   the summary. On abort/failure the branch is unchanged. PREP is the
   :session-before-tree preparation map (now carrying the effective :label
   and :replace-instructions after :session-before-tree overrides); ABORT-ATOM
   cancels the call."
  [cs sess old-leaf target-leaf user-msg-text prep abort-atom
   custom-instructions replace-instructions?]
  (let [ag @(:agent-state cs)
        ed (:editor cs)
        prev-interrupt (get @(:action-handlers ed) "app.interrupt")
        indicator (ui/make-branch-summary-status-indicator)
        done (promise)]
    ;; escape → abort (pi: defaultEditor.onEscape = abortBranchSummary)
    (editor/editor-set-on-action! ed "app.interrupt"
                                  (fn [] (reset! abort-atom true)))
    (show-status-indicator! cs :branch-summary indicator)
    (tui/tui-request-render (:tui cs))
    ;; render driver: tick the indicator while the summarization runs
    (future
      (while (not (realized? done))
        (Thread/sleep 100)
        (tui/tui-request-render (:tui cs))))
    (future
      (try
        (deliver done (agent/generate-branch-summary
                       ag (:entries-to-summarize prep) custom-instructions
                       abort-atom replace-instructions?))
        (catch Exception e
          (debug/log "branch summarization failed: " e)
          (deliver done nil))))
    (future
      (let [result (deref done 120000 :timeout)]
        (editor/editor-set-on-action! ed "app.interrupt" prev-interrupt)
        (release-background-status! cs :branch-summary indicator)
        (cond
          (= result :timeout)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :info :label "Tree"
                                         :content "Branch summarization timed out — branch unchanged."})

          (nil? result)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :info :label "Tree"
                                         :content "Branch summarization failed — branch unchanged."})

          (:aborted result)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :info :label "Tree"
                                         :content "Branch summarization cancelled — branch unchanged."})

          :else
          (complete-tree-navigation! cs sess old-leaf target-leaf
                                     result user-msg-text false
                                     (:label prep)))
        (tui/tui-request-render (:tui cs))))))

(defn- navigate-tree!
  "Branch the session to the selected tree entry (pi: navigateTree):
   selecting a user message re-opens it in the editor (leaf = its parent),
   any other entry becomes the new leaf. Emits :session-before-tree
   (extensions may cancel, supply the summary, or override
   custom-instructions/replace-instructions/label), optionally summarizes
   the abandoned path, branches, and emits :session-tree."
  [cs sess entry wants-summary custom-instructions replace-instructions? label]
  (let [old-leaf @(:leaf-id sess)
        target-leaf (if (= :user (:role entry)) (:parent-id entry) (:id entry))
        entries (session/branch-summary-entries sess old-leaf (:id entry))
        user-msg-text (when (= :user (:role entry)) (session/session-entry-text entry))
        abort-atom (atom false)
        prep {:target-id (:id entry)
              :old-leaf-id old-leaf
              :common-ancestor-id (session/common-ancestor-id sess old-leaf (:id entry))
              :entries-to-summarize entries
              :user-wants-summary (boolean wants-summary)
              :custom-instructions custom-instructions
              :replace-instructions (boolean replace-instructions?)
              :label label}
        ext-result (event-bus/emit-event! {:type :session-before-tree
                                           :preparation prep
                                           :signal abort-atom})
        custom-instructions (or (:custom-instructions ext-result) custom-instructions)
        replace-instructions? (if (contains? (or ext-result {}) :replace-instructions)
                                (:replace-instructions ext-result)
                                replace-instructions?)
        effective-label (if (contains? (or ext-result {}) :label)
                          (:label ext-result)
                          label)
        prep (assoc prep
                    :custom-instructions custom-instructions
                    :replace-instructions replace-instructions?
                    :label effective-label)]
    (cond
      (and wants-summary (empty? entries))
      ;; nothing abandoned to summarize — branch without a summary
      (complete-tree-navigation! cs sess old-leaf target-leaf nil user-msg-text
                                 false effective-label)

      (:cancel ext-result)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :info :label "Tree"
                                     :content "Navigation cancelled by an extension."})

      (and wants-summary (:summary ext-result))
      (complete-tree-navigation! cs sess old-leaf target-leaf
                                 (when-let [s (:summary ext-result)]
                                   {:summary s :usage (:usage ext-result)})
                                 user-msg-text true
                                 effective-label)

      (not wants-summary)
      (complete-tree-navigation! cs sess old-leaf target-leaf nil user-msg-text
                                 false effective-label)

      :else
      (branch-summarize-and-apply! cs sess old-leaf target-leaf user-msg-text
                                   prep abort-atom custom-instructions
                                   replace-instructions?))))

(defn- prompt-custom-summary!
  "Ask for custom summarization instructions, then navigate with them
   (pi: 'Summarize with custom prompt'). Escape loops back to the summarize
   choice."
  [cs sess entry]
  (tui/tui-show-overlay
   (:tui cs)
   (dialogs/make-input-dialog
    "Custom branch summarization instructions"
    (fn [instructions]
      (tui/tui-hide-overlay (:tui cs))
      (navigate-tree! cs sess entry true (str/trim instructions) false nil))
    (fn []
      (tui/tui-hide-overlay (:tui cs))
      (ask-branch-summary cs sess entry))
    (th/get-current-theme)))
  (tui/tui-request-render (:tui cs)))

(defn- ask-branch-summary
  "Ask whether to summarize the abandoned branch before branching (pi: the
   Summarize branch? selector), then navigate. Escape re-opens the tree."
  [cs sess entry]
  (let [items [{:value "none" :label "No summary"}
               {:value "summarize" :label "Summarize"}
               {:value "custom" :label "Summarize with custom prompt"}]
        sl-ref (atom nil)
        on-select (fn [_]
                    (when-let [sel (select-list/select-list-get-selected @sl-ref)]
                      (tui/tui-hide-overlay (:tui cs))
                      (case (:value sel)
                        "none" (navigate-tree! cs sess entry false nil false nil)
                        "summarize" (navigate-tree! cs sess entry true nil false nil)
                        "custom" (prompt-custom-summary! cs sess entry))))
        on-escape (fn []
                    (tui/tui-hide-overlay (:tui cs))
                    ;; re-open with the highlight on the entry being
                    ;; navigated to (pi showTreeSelector initialSelectedId)
                    (show-session-tree cs
                                       (fn [entry]
                                         (ask-branch-summary cs @(:session-atom cs) entry))
                                       (:id entry)))
        sl (select-list/make-select-list items
                                         :height 3
                                         :header "Summarize branch?"
                                         :on-select on-select
                                         :on-escape on-escape)]
    (reset! sl-ref sl)
    (tui/tui-show-overlay (:tui cs) sl :width 42 :height 3)
    (tui/tui-request-render (:tui cs))))

;; ─── Fork / clone (pi: /fork, /clone) ─────────────────────────────────────

(defn- fork-at!
  "Fork the session before the given user message and switch to the fork
   (pi: runtimeHost.fork — the new session starts at the message's parent;
   the message text is restored to the editor for re-editing). Forking the
   first user message (no parent) starts an empty session linked to this
   one."
  [cs entry-id]
  (if @(:running-turn? cs)
    (ui/chat-history-add-message! (:chat-history cs)
                                  {:role :assistant
                                   :content "Wait for the current response to finish before forking."})
    (let [sess @(:session-atom cs)
          entry (session/get-entry sess entry-id)]
      (if (nil? entry)
        (ui/chat-history-add-message! (:chat-history cs)
                                      {:role :assistant :content "Invalid entry for forking."})
        (try
          ;; pi: emitBeforeFork — extensions may cancel the fork; the
          ;; conversation stays untouched
          (if (:cancel (event-bus/emit-event! {:type :session-before-fork
                                               :entry-id entry-id
                                               :position :at}))
            (ui/chat-history-add-message! (:chat-history cs)
                                          {:role :assistant
                                           :content "Fork cancelled by an extension."})
            (let [fork (if (:parent-id entry)
                         (session/fork-session sess (:parent-id entry))
                         (session/create-session (ensure-cwd-session-dir (runtime-cwd cs))
                                                 {:parent-session (:file sess)
                                                  :cwd (runtime-cwd cs)}))]
              (if (nil? fork)
                (ui/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant :content "Failed to create forked session."})
                (do
                  (debug/log "forked session " (:id fork) " from " (:id sess))
                  (restore-session! cs fork false)
                  (editor-text-set! (:editor cs) (session/session-entry-text entry))
                  (ui/chat-history-add-message! (:chat-history cs)
                                                {:role :assistant
                                                 :content (str "Forked to new session " (subs (:id fork) 0 8) ".")})
                  (tui/tui-request-render (:tui cs))))))
          (catch Exception e
            (debug/log "fork failed: " e)
            (ui/chat-history-add-message! (:chat-history cs)
                                          {:role :info :label "Fork"
                                           :content (str "Fork failed: " (ex-message e))})
            (tui/tui-request-render (:tui cs))))))))

(defn- clone-current-session!
  "Duplicate the session at its current position (pi: /clone → fork at the
   current leaf) and switch to the clone."
  [cs]
  (let [sess @(:session-atom cs)]
    (cond
      @(:running-turn? cs)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant
                                     :content "Wait for the current response to finish before cloning."})

      (nil? sess)
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant :content "No active session."})

      (nil? @(:leaf-id sess))
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant :content "Nothing to clone yet."})

      (not (fs/exists? (:file sess)))
      (ui/chat-history-add-message! (:chat-history cs)
                                    {:role :assistant
                                     :content "Wait for the first assistant response before cloning."})

      :else
      (try
        ;; pi: /clone is a fork at the current leaf — session_before_fork
        ;; applies; extensions may cancel
        (if (:cancel (event-bus/emit-event! {:type :session-before-fork
                                             :entry-id @(:leaf-id sess)
                                             :position :at}))
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :assistant
                                         :content "Clone cancelled by an extension."})
          (let [fork (session/clone-session sess)]
            (if (nil? fork)
              (ui/chat-history-add-message! (:chat-history cs)
                                            {:role :assistant :content "Failed to clone session."})
              (do
                (debug/log "cloned session " (:id fork) " from " (:id sess))
                (restore-session! cs fork false)
                (ui/chat-history-add-message! (:chat-history cs)
                                              {:role :assistant
                                               :content (str "Cloned to new session " (subs (:id fork) 0 8) ".")})
                (tui/tui-request-render (:tui cs))))))
        (catch Exception e
          (debug/log "clone failed: " e)
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :info :label "Clone"
                                         :content (str "Clone failed: " (ex-message e))})
          (tui/tui-request-render (:tui cs)))))))

;; ─── Animation timer ────────────────────────────────────────────────────────
;; Drives re-renders while the agent turn is running, so the separate
;; StatusIndicator (Pi-style) between chat and editor animates smoothly.

(defn- start-anim-timer!
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

(defn- stop-anim-timer!
  "Cancel the animation timer."
  [cs]
  (when-let [t @(:anim-timer cs)]
    (future-cancel t)
    (reset! (:anim-timer cs) nil)))

;; ─── Status indicator swap model (pi: showStatusIndicator/clearStatusIndicator) ──
;; The status layer is a fn component (ui/make-status-area) mounted via
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

(defn- current-status-indicator
  "The indicator the session is showing right now: the transient swap
   recorded in :status-current when one is up, else the default working
   indicator while it is active, else nil (the editor border then draws its
   plain rule). The editor's top-border hook resolves this per render."
  [cs]
  (or (:indicator @(:status-current cs))
      (when (ui/status-indicator-active? (:status-indicator cs))
        (:status-indicator cs))))

(defn- show-status-indicator!
  "Record INDICATOR as the active status (pi: showStatusIndicator —
   disposes the active indicator). KIND records which indicator is active
   for kind-gated clears. No manual render request for the SWAP itself —
   the tracked :status-current read schedules that frame (§3.4) — but the
   transient indicators animate from wall-clock time, so the entry carries
   an 80ms frame driver (start-indicator-driver!; during turns the anim
   timer already drives frames)."
  [cs kind indicator]
  (cancel-indicator-driver! cs)
  (ui/status-indicator-stop! (:status-indicator cs))
  (reset! (:status-current cs)
          {:kind kind
           :indicator indicator
           :driver (start-indicator-driver! cs indicator)}))

(defn- activate-working-indicator!
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
  (ui/status-indicator-start! (:status-indicator cs))
  ;; A background thread (share/branch-summary completion, an extension's
  ;; set-working-visible) can revive into a turn that just ended: teardown
  ;; (on-agent-done/on-agent-error) drops running-turn? BEFORE clearing
  ;; the status, so an activation interleaved with it can land after the
  ;; clear. Never leave a spinner with no turn behind it.
  (when-not @(:running-turn? cs)
    (ui/status-indicator-stop! (:status-indicator cs))))

(defn- release-background-status!
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
               (not (ui/status-indicator-active? (:status-indicator cs))))
      (activate-working-indicator! cs))))

(defn- clear-status-indicator!
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
      (ui/status-indicator-stop! (:status-indicator cs)))))

;; ─── Pending messages display (pi: updatePendingMessagesDisplay) ──────────

(defn- fmt-key-display
  "Pi: formatKeyText capitalize — 'alt+up' → 'Alt+Up'."
  [k]
  (->> (str/split (or k "") #"\+")
       (map (fn [part]
              (if (seq part)
                (str (str/upper-case (subs part 0 1)) (subs part 1))
                part)))
       (str/join "+")))

(defn- update-pending-messages!
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
    (ui/pending-messages-set-queues! (:pending-messages-comp cs)
                                     (into (vec steering) c-steer)
                                     (into (vec follow-up) c-follow))))

(defn- queue-compaction-message!
  "Queue a message typed during compaction (pi: queueCompactionMessage —
   non-extension input is queued for after compaction; extension commands
   execute immediately and never reach here). The message displays in the
   pending area and is flushed by the :compaction-end handler."
  [cs text mode]
  (swap! (:compaction-queued cs) conj {:text text :mode mode})
  (update-pending-messages! cs)
  (ui/chat-history-show-status!
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

(defn- flush-compaction-queue!
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
                     (update-pending-messages! cs))]
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
        (update-pending-messages! cs)
        (catch Exception e
          (restore!)
          (ui/chat-history-show-status!
           (:chat-history cs)
           (str "Failed to send queued message"
                (when (> (count msgs) 1) "s")
                ": " (ex-message e)))
          (debug/log "compaction queue flush failed: " e))))))

(defn- compaction-status-message
  "Pi: CompactionStatusIndicator label — reason-specific, with the cancel
   hint (escape aborts compaction)."
  [reason]
  (let [cancel (str " (" (fmt-key-display (app-kb/key-text "app.interrupt"))
                    " to cancel)")]
    (case reason
      :manual (str "Compacting context..." cancel)
      :overflow (str "Context overflow detected, auto-compacting..." cancel)
      (str "Auto-compacting..." cancel))))

(defn- set-terminal-progress!
  "Show/clear the OSC 9;4 terminal progress indicator when the
   show-terminal-progress setting is on (pi: showTerminalProgress —
   getShowTerminalProgress gates setProgress; default off)."
  [cs active?]
  (when (and cs (cfg/get-show-terminal-progress (:config cs)))
    (when-let [term @(:terminal (:tui cs))]
      (term/set-progress! term active?))))

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
    (ui/chat-history-append-streaming-text! (:chat-history cs) text)
    (catch Exception e
      (debug/log "on-agent-text callback: " e)
      (binding [*out* *err*] (println "on-agent-text error:" (ex-message e) (.getClass e))))))

(defn- on-agent-thinking
  "Called for each thinking/reasoning delta from the LLM during streaming
   (pure data append — see on-agent-text)."
  [cs text]
  (try
    (ui/chat-history-append-thinking-text! (:chat-history cs) text)
    (catch Exception e
      (debug/log "on-agent-thinking callback: " e)
      (binding [*out* *err*] (println "on-agent-thinking error:" (ex-message e) (.getClass e))))))

(defn- on-agent-done
  "Called when the LLM turn completes.
   Drop the running-turn flag FIRST — a background status completion
   (share, branch summary: release-background-status!) racing this teardown
   must see the turn over before it may revive the working spinner; the
   flag-first order plus its post-revival re-check makes a stuck spinner
   impossible. Then finalize streaming FIRST (captures thinking text),
   then clear thinking. Session persistence is handled by the agent loop
   internally."
  [cs]
  (try
    (stop-anim-timer! cs)
    (reset! (:running-turn? cs) false)
    (clear-status-indicator! cs)
    (ui/chat-history-finalize-streaming! (:chat-history cs))
    (ui/chat-history-finalize-thinking! (:chat-history cs))
    ;; Heal stale above-window scrollback now that the turn has ended: the turn
    ;; itself produced the stale lines (tool output and streamed text that
    ;; changed above the window), and the document is bottom-pinned with the
    ;; user watching its end, so the rebuild's ESC[3J viewport jump lands on
    ;; the turn transition. Gated on streaming-free — a user `!` bash command
    ;; or a compaction may still be live — and a no-op unless dirty.
    (heal-stale-scrollback-when-idle! cs)
    (update-footer! cs)
    (tui/tui-request-render (:tui cs))
    (debug/log "agent turn completed")
    (catch Exception e
      (debug/log "on-agent-done callback: " e)
      (binding [*out* *err*] (println "on-agent-done error:" (ex-message e) (.getClass e))))))

(defn- on-agent-error
  "Called when an error occurs during the agent turn."
  [cs error-msg]
  (try
    (stop-anim-timer! cs)
    ;; flag before the status clear — see on-agent-done
    (reset! (:running-turn? cs) false)
    (clear-status-indicator! cs)
    ;; If streaming placeholder is still empty, remove it
    ;; so we don't get a blank assistant entry before the error message.
    ;; Removed by identity — a consumed steering/follow-up message or a
    ;; tool execution can sit after the placeholder, and popping the last
    ;; entry would delete that message instead (pi: agent_end removes the
    ;; streamingComponent by reference).
    (let [ch (:chat-history cs)]
      (if (and @(:streaming-atom ch)
               (ui/chat-history-streaming-empty? ch))
        (ui/chat-history-remove-streaming-placeholder! ch)
        (do (ui/chat-history-finalize-streaming! ch)
            (ui/chat-history-finalize-thinking! ch))))
    (ui/chat-history-add-message! (:chat-history cs)
                                  {:role :assistant :content (th/fg th/dark-theme :error (str "Error: " error-msg))})
    ;; A failed turn still produced above-window changes while streaming, so
    ;; heal here too (gated on streaming-free; no-op unless dirty).
    (heal-stale-scrollback-when-idle! cs)
    (update-footer! cs)
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
      (ui/show-warning! (:chat-history cs)
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
                        tools-util/*cwd* (runtime-cwd cs)]
                (event-bus/emit-event!
                 {:type :user-bash
                  :command command
                  :exclude-from-context? exclude-from-context?
                  :cwd (runtime-cwd cs)}))

            ;; ── Spawn hook (pi: BashSpawnHook) — extensions can modify command ──
            spawn-hook nil]

        ;; Add to chat (or pending container if agent is streaming)
        ;; Pi: pendingMessagesContainer sits between chat and footer
        (if @(:running-turn? cs)
          (do
            (container/container-add-child (:pending-messages-container cs) bash-comp)
            (swap! (:pending-bash-components cs) conj bash-comp))
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :bash :command command
                                         :component bash-comp}))

        (update-footer! cs)
        (tui/tui-request-render (:tui cs))

        ;; Execute in background
        (future
          (try
            (let [result (bash-exec/execute-bash
                          {:command command
                           :cwd (runtime-cwd cs)
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
                      (ui/chat-history-add-message! (:chat-history cs)
                                                    {:role :bash :command command :component comp}))
                    (reset! pending []))))

              (reset! (:bash-running? cs) false)
              (update-footer! cs)
              (tui/tui-request-render (:tui cs)))

            (catch Exception e
              (let [err-msg (or (ex-message e) "Unknown error")]
                (debug/log "bash command error: " e)
                (be/bash-execution-set-complete! bash-comp nil false)
                (ui/show-error! (:chat-history cs) err-msg)
                (reset! (:bash-running? cs) false)
                (update-footer! cs)
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

(defn- heal-stale-scrollback-when-idle!
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

(defn- global-quit-listener
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

(defn- request-global-reflow-render!
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

(defn- start-agent-run!
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
  (activate-working-indicator! cs)
  (start-anim-timer! cs)
  (update-footer! cs)
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
      (update-pending-messages! cs)
      (update-footer! cs)
      (tui/tui-request-render (:tui cs)))
    (do
      (debug/log "user submitted: " text)
      (start-agent-run! cs text))))

(declare command-line?)

(defn- expand-user-message-text
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
                (update-footer! cs)
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

(defn- handle-submit [cs text]
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
                (update-footer! cs))
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
              (ui/chat-history-add-message! (:chat-history cs)
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

(defn- queue-follow-up-text!
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
        (update-pending-messages! cs)
        :queued)

    :else
    (do (handle-submit cs text) :submitted)))

(defn- handle-follow-up
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

(defn- restore-queued-messages!
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
        (update-pending-messages! cs)))
    (count all)))

(defn- handle-dequeue
  "Pi: handleDequeue — Alt+Up. Restore all queued steering/follow-up
   messages to the editor, combined with the current text."
  [cs]
  (let [restored (restore-queued-messages! cs)]
    (ui/chat-history-show-status!
     (:chat-history cs)
     (if (pos? restored)
       (str "Restored " restored " queued message"
            (when (> restored 1) "s") " to editor")
       "No queued messages to restore"))
    (tui/tui-request-render (:tui cs))))

(defn- handle-cancel
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
        (update-footer! cs))
      (when @(:running-turn? cs)
        (debug/log "agent turn cancelled by user")
        ;; flag before the status clear — see on-agent-done: a background
        ;; revive racing this teardown must see the turn over
        (reset! (:running-turn? cs) false)
        (stop-anim-timer! cs)
        (clear-status-indicator! cs)
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
                (ui/chat-history-remove-streaming-placeholder! ch)
                (do (ui/chat-history-finalize-streaming! ch) (ui/chat-history-finalize-thinking! ch)))))
          (ui/chat-history-add-message! (:chat-history cs)
                                        {:role :assistant :content (th/dim "(cancelled)")})
          (when (pos? restored)
            (ui/chat-history-show-status!
             (:chat-history cs)
             (str "Restored " restored " queued message"
                  (when (> restored 1) "s") " to editor"))))
        ;; A cancel is a turn end too: heal the stale scrollback it left (the
        ;; Escape keypress itself could not — the input listener ran while the
        ;; turn was still marked running). Gated + no-op unless dirty.
        (heal-stale-scrollback-when-idle! cs)
        (update-footer! cs)))))

;; ─── External editor (pi: handleOpenExternalEditor) ────────────────────────

;; ─── Loaded resources (pi: showLoadedResources) ────────────────────────────

(defn- display-path
  "Home-relative display path for a loaded resource (pi: formatDisplayPath)."
  [p]
  (let [p (str p)
        home (System/getProperty "user.home")]
    (if (and (seq home) (str/starts-with? p home))
      (str "~" (subs p (count home)))
      p)))

(defn- build-loaded-resource-sections
  "Pi: showLoadedResources — one section per loaded resource group (Context,
   Skills, Prompts, Extensions). Sections carry raw items; styling happens
   in the LoadedResources component render."
  []
  (let [context-files (context/load-project-context-files
                       (cfg/get-agent-dir) (str (fs/cwd)))
        skills (skills/get-skills)
        templates (prompts/get-prompt-templates)
        extensions (extensions/get-loaded-extensions)
        sections (cond-> []
                   (seq context-files)
                   (conj {:name "Context"
                          :items (mapv (comp display-path :path) context-files)
                          :expanded-items (mapv #(str "  " (display-path (:path %))) context-files)})
                   (seq skills)
                   (conj {:name "Skills"
                          :items (mapv :name skills)
                          :expanded-items (mapv (fn [s]
                                                  (str "  " (or (:file-path s) (:name s))))
                                                skills)})
                   (seq templates)
                   (conj {:name "Prompts"
                          :items (mapv #(str "/" (:name %)) templates)
                          :expanded-items (mapv (fn [t] (str "  /" (:name t))) templates)})
                   (seq extensions)
                   (conj {:name "Extensions"
                          :items (mapv :name extensions)
                          :expanded-items (mapv (fn [e]
                                                  (str "  " (display-path (:path e))))
                                                extensions)}))]
    sections))

;; ─── Layout setup ──────────────────────────────────────────────────────────

(defn- make-agent-event-handler
  "Build the agent-loop :on-event callback for the interactive UI: routes
   lifecycle events to the chat history, pending-messages display, footer,
   and status-indicator layers (pi: the TUI session's event handlers).
   DEPS — map of the layout's mutable refs:
     :chat-history        — ChatHistoryComponent (messages, streaming state)
     :tui                 — TUI record (render requests)
     :cs-ref              — atom holding the CoreState (footer/status updates;
                             nil until the layout is assembled)
     :pending-tool-comps   — atom map tool-call-id → in-flight tool component
                            (pi: pendingTools Map — parallel tool calls each
                            get their own component; end events correlate by
                            id and remove the entry, so every component's
                            elapsed ticker is cleared by its own end).
   Extracted from build-layout so the contract that every vocabulary event
   is consumed is testable (kmet.modes.test-interactive)."
  [{:keys [chat-history tui cs-ref pending-tool-comps]}]
  (fn [evt]
    (case (:type evt)
      :tool-execution-start
      ;; Pi: create pending component once, update in place.
      ;; The streaming placeholder finalizes here (the tool box lands after
      ;; it) — mark the message as tool-call-bearing FIRST so a tool-call-only
      ;; assistant message stays invisible instead of bubbling '(no response)'
      ;; (pi: hasToolCalls; loop-continue turns are usually tool-call-only).
      (let [msg {:role :tool
                 :name (:tool-name evt)
                 :args (:args evt {})
                 :content ""
                 :is-error false}]
        (ui/chat-history-mark-streaming-tool-calls! chat-history)
        (ui/chat-history-finalize-streaming! chat-history)
        (let [comp (ui/chat-history-add-message! chat-history msg)]
          ;; Store tool call ID for correlation
          (reset! (:tool-call-id-atom comp) (:tool-call-id evt))
          ;; Args are complete when received (kmet: no streaming args)
          (ui/tool-execution-set-args-complete! comp)
          ;; Mark execution started so pending bg + timer activate now
          (ui/tool-execution-mark-execution-started! comp)
          ;; Pi: pendingTools.set(toolCallId, component) — parallel tool
          ;; calls each own a component; updates/ends correlate by id
          (swap! pending-tool-comps assoc (:tool-call-id evt) comp))
        ;; mount poke stays: the new message lands in the untracked
        ;; messages-atom, so no watch exists for it yet
        (tui/tui-request-render tui))
      :tool-execution-update
      ;; Pi: live partial content from streaming tools (bash). The
      ;; elapsed counter itself ticks via the bash render-result's own
      ;; 1s interval (pi: setInterval → context.invalidate), so a
      ;; silent long-running tool still updates Elapsed steadily — this
      ;; event only pushes the new output chunks.
      ;; No manual render request: set-content! swaps a track!-watched
      ;; atom, which schedules the frame itself (§3.4).
      (when-let [comp (get @pending-tool-comps (:tool-call-id evt))]
        (when-let [content (:content evt)]
          (reset! (:content-atom comp) content)))
      :tool-execution-end
      ;; Pi: update the component by id and remove it from pendingTools
      ;; (watched atoms schedule their own frame — §3.4)
      (when-let [comp (get @pending-tool-comps (:tool-call-id evt))]
        (let [result (:result evt)]
          (reset! (:content-atom comp) (:content result))
          (ui/tool-execution-set-error! comp (:is-error result false))
          (when-let [truncation (:truncation result)]
            (reset! (:truncation-atom comp) truncation))
          (when-let [details (:details result)]
            (reset! (:details-atom comp) details))
          (when-let [images (:images result)]
            (ui/tool-execution-set-images! comp images))
          (swap! pending-tool-comps dissoc (:tool-call-id evt))))
      :status
      ;; Pi: agent status changes keep the footer/status
      ;; layer in sync via the :status event (update-footer!'s invalidate
      ;; schedules the frame)
      (when-let [cs @cs-ref]
        (update-footer! cs))
      :loop-guard
      ;; Repeat-loop guard tripped (kmet-specific): show a warning line in
      ;; the transcript — the run has already settled (the final text
      ;; carries the explanation)
      (ui/chat-history-add-message!
       chat-history
       {:role :warning
        :content (or (:details evt) "Stopped: repeat-loop guard tripped")})
      :agent-end
      ;; Pi: maybeShowCacheMissNotice — a significant
      ;; prompt-cache miss on the completed turn (only
      ;; when the run actually produced an assistant
      ;; message — a failed run must not re-show the
      ;; previous turn's miss)
      (do (when-let [cs @cs-ref]
            (set-terminal-progress! cs false)
            (maybe-show-cache-miss-notice! cs (:messages evt)))
          (tui/tui-request-render tui))
      :queue-update
      ;; Queued steering/follow-up messages changed (pi:
      ;; queue_update → updatePendingMessagesDisplay; the tracked-atom
      ;; swap schedules the frame)
      (when-let [cs @cs-ref]
        (update-pending-messages! cs))
      :turn-start
      ;; A new LLM call is starting. After a retry backoff
      ;; or compaction the status container holds a
      ;; transient indicator (or the stopped working
      ;; indicator); revive the working spinner so the
      ;; call streams under "Working" (pi: the session
      ;; emits a fresh agent_start after retry/compaction
      ;; via agent.continue(), re-showing the
      ;; WorkingStatusIndicator — kmet's loop recurs
      ;; in-turn, so turn-start is the equivalent signal).
      ;; The guarded nil-swap inside activate-working-indicator!
      ;; schedules the frame when a real change happens.
      (when-let [cs @cs-ref]
        (when (and @(:running-turn? cs)
                   (not= :working (:kind @(:status-current cs))))
          (activate-working-indicator! cs)))
      :auto-retry-start
      ;; Show the retry countdown; the failed attempt's partial
      ;; text stays visible (pi: auto_retry_start only swaps in a
      ;; RetryStatusIndicator — the errored block remains in the
      ;; chat and the retried stream opens a fresh message below it)
      ;; (the :status-current swap schedules its own frame)
      (when-let [cs @cs-ref]
        (show-status-indicator!
         cs :retry
         (ui/make-retry-status-indicator
          (:attempt evt) (:max-attempts evt) (:delay-ms evt)
          :cancel-hint (fmt-key-display
                        (app-kb/key-text "app.interrupt")))))
      :auto-retry-end
      ;; Retry finished (pi: auto_retry_end →
      ;; clearStatusIndicator("retry")). Kind-gated: when
      ;; the retried call already started (turn-start
      ;; revived the working indicator) this no-ops and
      ;; the working spinner keeps spinning.) The kind-gated nil-swap
      ;; schedules its own frame.
      (when-let [cs @cs-ref]
        (clear-status-indicator! cs :retry))
      :compaction-start
      ;; Session compaction in progress (pi:
      ;; compaction_start → CompactionStatusIndicator + terminal progress);
      ;; the hint is truthful — escape aborts it
      (do (when-let [cs @cs-ref]
            (set-terminal-progress! cs true)
            (show-status-indicator!
             cs :compaction
             (ui/make-compaction-status-indicator
              :message (compaction-status-message
                        (:reason evt)))))
          (tui/tui-request-render tui))
      :compaction-end
      ;; Compaction done — restore the idle status (pi:
      ;; compaction_end → clearStatusIndicator). For the
      ;; manual path the /compact future skips its reply
      ;; on abort, so the status is the only feedback;
      ;; an in-loop abort is already reported by the
      ;; full turn cancel ("(cancelled)") — no double
      ;; report.
      (do (when-let [cs @cs-ref]
            (set-terminal-progress! cs false)
            (clear-status-indicator! cs :compaction)
            (when (:aborted evt)
              ;; pi: compaction_end aborted — manual: error line; auto:
              ;; dim status
              (if (= :manual (:reason evt))
                (ui/chat-history-show-status!
                 chat-history "Compaction cancelled")
                (ui/chat-history-show-status!
                 chat-history "Auto-compaction cancelled")))
            ;; pi: compaction_end errorMessage — a failed summarization is
            ;; surfaced BEFORE the queue flush (manual: error line; auto:
            ;; dim status), so the error precedes any run the flush starts.
            (when-let [err (:error-message evt)]
              (if (= :manual (:reason evt))
                (ui/chat-history-add-message!
                 chat-history {:role :error :content err})
                (ui/chat-history-show-status! chat-history err)))
            ;; pi: compaction_end → flushCompactionQueue — messages queued
            ;; during compaction are delivered now (queued into the retrying
            ;; turn when will-retry, else the first prompts a fresh run).
            (flush-compaction-queue! cs (:will-retry evt)))
          (tui/tui-request-render tui))
      :context-replaced
      ;; Rebuild the chat history to mirror the replaced
      ;; context; custom messages honor the display flag
      ;; (pi: display controls TUI rendering — hidden
      ;; ones stay in the LLM context only)
      (do (ui/chat-history-rebuild!
           chat-history
           (mapcat (fn [m]
                     (let [m (if (and (= :custom (:role m)) (:display m))
                               (if-let [rendered (run-message-renderer
                                                  (extensions/get-message-renderer
                                                   (:custom-type m)) m)]
                                 (renderer-result->message rendered)
                                 (assoc m :role :info
                                        :content (custom-message-text m)
                                        :images (image-block/content-images (:content m))
                                        :label (:custom-type m)))
                               m)]
                       ;; pi: addCompactionCostNotice — the billing line
                       ;; follows its summary message
                       (if-let [notice (compaction-cost-notice (:config @cs-ref)
                                                               (:role m) (:usage m))]
                         [m notice]
                         [m])))
                   (remove #(and (= :custom (:role %))
                                 (not (:display %)))
                           (:messages evt))))
          (tui/tui-request-render tui))
      :message-start
      ;; Pi: message_start → user messages (the initial
      ;; prompt and consumed steering/follow-up messages)
      ;; land in the chat here; assistant message starts
      ;; finalize the previous turn's streaming placeholder
      ;; and open a fresh one, so a follow-up continuation
      ;; never merges into the prior response; before-
      ;; agent-start injected messages (role :info) display
      ;; as labeled info boxes above the response. Content
      ;; is normalized from text blocks to a string for
      ;; the info box.
      (case (:role (:message evt))
        :user (do (ui/chat-history-add-message! chat-history (:message evt))
                  (when-let [cs @cs-ref]
                    (update-pending-messages! cs))
                  (tui/tui-request-render tui))
        :assistant (do (ui/chat-history-finalize-streaming! chat-history)
                       (ui/chat-history-finalize-thinking! chat-history)
                       (ui/chat-history-start-streaming! chat-history)
                       (tui/tui-request-render tui))
        :info (let [m (:message evt)
                    text (if (string? (:content m))
                           (:content m)
                           (str/join
                            (for [b (:content m)
                                  :when (= :text (:type b))]
                              (:text b))))]
                (ui/chat-history-insert-before-streaming! chat-history
                                                          (assoc m
                                                                 :content text
                                                                 :images (image-block/content-images (:content m))))
                (tui/tui-request-render tui))
                            ;; extension custom messages (pi: custom messages
                            ;; render when display=true — a registered message
                            ;; renderer overrides the labeled info box; same
                            ;; rule as the session-restore path)
        :custom (do (when (:display (:message evt))
                      (let [m (:message evt)]
                        (ui/chat-history-insert-before-streaming!
                         chat-history
                         (if-let [rendered (run-message-renderer
                                            (extensions/get-message-renderer
                                             (:custom-type m)) m)]
                           (renderer-result->message rendered)
                           (assoc m :role :info
                                  :content (custom-message-text m)
                                  :images (image-block/content-images (:content m))
                                  :label (:custom-type m))))))
                    (tui/tui-request-render tui))
        nil)
      ;; Remaining vocabulary events need no UI action in
      ;; kmet's architecture: streaming text/thinking
      ;; arrives via the on-text/on-thinking callbacks,
      ;; message finalization + the idle transition happen
      ;; in on-agent-done, errors surface via on-error, and
      ;; the /model + Ctrl+P cycling paths sync the footer
      ;; themselves. Every event must still be consumed — a
      ;; case with no matching clause throws, and the
      ;; exception is swallowed by the run future, leaving
      ;; the UI stuck on "Working" forever.

      :agent-start
      ;; Pi: agent_start → setProgress(true) (showTerminalProgress gated)
      (when-let [cs @cs-ref]
        (set-terminal-progress! cs true))
      :message-update nil
      :message-end nil
      :turn-end nil
      :agent-settled nil
      :session-compact-failed nil
      :error nil
      :model-select nil
      :thinking-level-select nil
      ;; Trailing default expression (SCI's case rejects the :default keyword)
      nil)))

(defn- build-layout
  "Create TUI layout and return CoreState."
  [config session]
  (let [tui-term (term/create-terminal)
        t (tui/create-tui tui-term)

        ;; Resolve model and provider from config
        provider (cfg/get-provider config)
        model (models/resolve-config-model config)

        ;; The runtime working directory (pi: AgentSession._cwd — the cwd
        ;; the resumed/continued session was recorded in, else the process
        ;; cwd). The atom is shared by the footer data provider (the footer
        ;; pwd, the extension ctx.cwd) and the chat history (tool-component
        ;; path displays), so apply-session-cwd! switches both with one
        ;; write.
        cwd (or (session/session-cwd session) (str (fs/cwd)))
        cwd-atom (atom cwd)

        ;; Load skills and prompt templates (pi: the unified resolution —
        ;; top-level entries, auto roots, then packages)
        _ (packages/load-skills!)
        _ (packages/load-prompts!)
        system-prompt-opts {:custom-prompt (cfg/get-custom-prompt config)
                            :append-prompt (cfg/get-append-system-prompt config)
                            :cwd cwd
                            :context-files (context/load-project-context-files
                                            (cfg/get-agent-dir) cwd)
                            :tools (vals (tools/get-all-tools))}
        system-prompt (apply skills/build-system-prompt
                             (mapcat identity system-prompt-opts))

        ;; Migrate legacy keybinding ids in keybindings.edn (pi: migrations
        ;; runner calls migrateKeybindingsConfigFile at startup)
        _ (app-kb/migrate-keybindings-config-file!)

        ;; Initialize keybindings (global singleton for key-hint + input
        ;; handling); persisted overrides load from keybindings.edn
        ;; (pi: KeybindingsManager.create)
        _ (let [kmgr (app-kb/create-agent-keybindings-manager)]
            (tui-kb/set-global-keybindings! kmgr)
            (app-kb/set-key-hint-theme-fns!
             #(th/dim %)
             #(th/fg (cfg/get-theme config) :muted %)))

        ;; Components (define before agent state so on-event can reference them)
        sp1 (spacer/make-spacer 1)
        ;; Inline image display settings (pi: terminal.showImages /
        ;; terminal.imageWidthCells) — seeded here, then read live by every
        ;; image (tool results, message attachments) through the shared sub
        _ (reset! subs/image-settings-atom
                  {:show-images (cfg/get-show-images config)
                   :image-width-cells (cfg/get-image-width-cells config)})
        ;; no :theme — message components subscribe to ui.subs/theme-sub
        ;; themselves (Stage 5)
        ch (ui/make-chat-history
            :cwd-fn #(deref cwd-atom)
            :thinking-hidden (cfg/get-hide-thinking-block config)
            :tool-display-mode (cfg/get-tool-display-mode config)
            :output-pad (cfg/get-output-pad config))
        pending-tool-comps (atom {})  ;; Pi: pendingTools Map (tool-call-id → comp)
        cs-ref (atom nil)             ;; CoreState, filled after layout (for :status events)

        ;; Context window of the active model — drives the footer % and the
        ;; proactive auto-compaction check (pi: state.model.contextWindow)
        ctx-window (or (:context-window (models/get-model provider model))
                       (:context-window config))

        ;; Agent state
        ag (agent/make-agent-state
            :model model
            :provider provider
            :system system-prompt
            :system-prompt-opts system-prompt-opts
            :session session
            :context-window ctx-window
            :compact-reserve-tokens (or (:compact-reserve-tokens config) 16384)
            :compact-token-threshold (:compact-token-threshold config)
            ;; get (not :kw) — an absent key must hit make-agent-state's
            ;; :or default, not be overridden with nil
            :auto-compact (get config :auto-compact true)
            :steering-mode (or (:steering-mode config) :all)
            :follow-up-mode (or (:follow-up-mode config) :all)
            :keep-recent-tokens (or (:keep-recent-tokens config) 20000)
            :http-idle-timeout-ms (:http-idle-timeout-ms config)
            :http-total-timeout-ms (get config :http-total-timeout-ms)
            ;; pi: images.blockImages — strip image blocks from provider calls
            :block-images (cfg/get-block-images config)
            :thinking (let [model-rec (models/get-model provider model)
                            raw-level (:thinking config :off)]
                        (if (:reasoning model-rec)
                          (shared/clamp-thinking-level model-rec raw-level)
                          raw-level))
            ;; pi: retry settings (settings.edn :retry block — enabled gates
            ;; max-retries to 0)
            :max-retries (let [retry (cfg/get-retry-settings config)]
                           (if (:enabled retry) (:max-retries retry) 0))
            :base-delay-ms (:base-delay-ms (cfg/get-retry-settings config))
            ;; Repeat-loop guard (kmet-specific): settings.edn :loop-guard
            ;; block — enabled gates threshold to 0 (off)
            :loop-guard-enabled (:enabled (cfg/get-loop-guard-settings config))
            :loop-guard-threshold (:threshold (cfg/get-loop-guard-settings config))
            :thinking-loop-guard-enabled (get config :thinking-loop-guard-enabled true)
            ;; Extension tool hooks (pi: tool_call / tool_result transforms):
            ;; chained in registration order; later hooks see earlier
            ;; rewrites. Captured at layout build — extensions register at
            ;; load, before the agent state exists.
            :before-tool-call (fn [ctx]
                                (loop [hooks (extensions/get-tool-call-hooks)
                                       blocked nil
                                       args (:args ctx)]
                                  (if-let [hook (first hooks)]
                                    (let [r (try (hook (assoc ctx :args args))
                                                 (catch Exception e
                                                   {:block true
                                                    :reason (str "tool-call hook error: "
                                                                 (ex-message e))}))]
                                      (cond
                                        (:block r)
                                        (recur (next hooks)
                                               (or blocked {:block true :reason (:reason r)})
                                               args)
                                        (contains? r :args)
                                        (recur (next hooks) blocked (:args r))
                                        :else
                                        (recur (next hooks) blocked args)))
                                    (or blocked
                                        (when (not= args (:args ctx)) {:args args})))))
            :after-tool-call (fn [ctx]
                               (reduce (fn [result hook]
                                         (if-let [r (try (hook (assoc ctx :result result
                                                                      :is-error (:is-error result false)))
                                                         (catch Exception e
                                                           {:content (str "tool-result hook error: "
                                                                          (ex-message e))
                                                            :is-error true}))]
                                           (cond-> result
                                             (:content r) (assoc :content (:content r))
                                             (contains? r :is-error) (assoc :is-error (:is-error r)))
                                           result))
                                       (:result ctx)
                                       (extensions/get-tool-result-hooks)))
            :on-event (make-agent-event-handler
                       {:chat-history ch :tui t :cs-ref cs-ref
                        :pending-tool-comps pending-tool-comps}))
            ;; Session scoped model list for cycle-model! / the scoped-models
            ;; selector / the /model scope toggle (pi: resolveModelScope →
            ;; session.scopedModels at startup — parsed.models ?? settings
            ;; enabledModels, full "provider/id" refs so cycling can switch
            ;; providers)
        _ (agent/init-scoped-models! ag config)
        ;; B.1: welcome header — ExpandableText with compact/full variants
        ;; (pi: builtInHeader), toggled by app.tools.expand
        hdr (let [mode (cfg/get-tool-display-mode config)]
              (expandable-text/make-expandable-text
               fmt-header-compact fmt-header-full
               :expanded? (= :expanded mode) :padding-x 1 :padding-y 0))
        ;; B.2: loaded resources between header and chat (pi: showLoadedResources)
        lr (ui/make-loaded-resources :theme (cfg/get-theme config)
                                     :expanded? (= :expanded (cfg/get-tool-display-mode config)))
        ;; B.3: queued steering/follow-up display (pi: updatePendingMessagesDisplay)
        pm (ui/make-pending-messages
            :hint (fmt-key-display (app-kb/key-text "app.message.dequeue")))
        ;; B.5: editor dynamic height — max(5, rows*0.3) via :terminal-rows;
        ;; the fixed :height fallback stays at the default 12;
        ;; border color reflects the current thinking level (pi: updateEditorBorderColor)
        ed (tui/make-editor :padding-x (cfg/get-editor-padding-x config)
                            :autocomplete-max-visible (cfg/get-autocomplete-max-visible config)
                            :terminal-rows (fn [] (term/rows @(:terminal t)))
                            :border-fn (th/get-thinking-border-color
                                        (cfg/get-theme config)
                                        (or @(:thinking ag) :off)))
        ;; B.6: footer data provider + footer (pi: FooterComponent; the
        ;; model line wraps to its own line when the stats line is too narrow)
        fdp (ui/make-footer-data-provider
             :cwd-atom cwd-atom
             :session session
             :provider-count (count (distinct (map :provider (model-catalog/scoped-or-available-models ag))))
             ;; Phase 2: context window from the resolved Model record, falling
             ;; back to the settings value when the model is unknown (pi footer
             ;; contextPercentDisplay)
             :context-window ctx-window
             :model @(:model ag) :provider @(:provider ag) :thinking @(:thinking ag)
             ;; pi: the thinking suffix renders only for reasoning models
             :reasoning (boolean (:reasoning (models/get-model provider model))))
        ftr (ui/make-footer :theme (cfg/get-theme config)
                            :provider fdp
                            ;; pi: the "(auto)" badge reflects the autoCompact
                            ;; setting; overflow-only recovery doesn't count
                            :auto-compact (get config :auto-compact true))

        ;; Core state (status-indicator/status-root filled in after layout)
        ;; the active editor lives behind an atom so custom editors can swap
        ;; in; the focus home reads both atoms at restore time so a mounted
        ;; dock selector outranks the editor (tui-set-focus-home!)
        current-editor-atom (atom ed)
        dock-current (atom nil)
        cs (map->CoreState {:tui t
                            :agent-state (atom ag)
                            :chat-history ch
                            :editor ed
                            :current-editor-atom current-editor-atom
                            :header-comp hdr
                            :loaded-resources-comp lr
                            :anim-timer (atom nil)
                            :footer-comp ftr
                            :footer-provider fdp
                            :status-indicator nil
                            :status-current (atom nil)
                            :status-root nil
                            :pending-messages-comp pm
                            :session-atom (atom session)
                            :running-turn? (atom false)
                            :compaction-queued (atom [])
                            :config config
                            :pending-tool-comps pending-tool-comps
                            :bash-running? (atom false)
                            :bash-signal (atom false)
                            :pending-bash-components (atom [])
                            :pending-messages-container (container/make-container [pm])
                            :dock-current dock-current})]

    ;; Initial loaded-resources sections (rebuilt on /reload)
    (ui/loaded-resources-set-sections! lr (build-loaded-resource-sections))

    ;; Focus editor
    (tui/tui-set-focus t ed)
    ;; Terminal focus fallback: when nothing capturing holds input, keys
    ;; return to the dock's selector if one is mounted, else the ACTIVE
    ;; editor - resolved through atoms so swaps stay live
    ;; (tui-set-focus-home!)
    (tui/tui-set-focus-home! t #(or (:component (deref dock-current))
                                    (deref current-editor-atom)))

    ;; Hardware cursor: the setting wins over the KMET_HARDWARE_CURSOR env
    ;; default (pi: showHardwareCursor)
    (tui/tui-set-show-hardware-cursor! t (cfg/get-show-hardware-cursor config))

    ;; Register builtin slash commands (autocomplete dropdown + dispatch)
    (register-builtin-commands! config)

    ;; Autocomplete provider: slash commands + prompt templates + skill
    ;; commands + file paths
    (editor/editor-set-autocomplete-provider! ed
                                              (ac/make-combined-provider
                                               :commands-fn #(vec (concat (commands/get-commands)
                                                                          (prompts/as-command-maps (prompts/get-prompt-templates))
                                                                          (skills/as-command-maps (skills/get-skills))))
                                               ;; a fn: path completion follows a session switch's cwd
                                               :base-path #(deref cwd-atom)))
    (editor/editor-set-autocomplete-theme! ed (th/get-select-list-theme (cfg/get-theme config)))

    ;; Status indicator: the default editor embeds the active status in its
    ;; own top border (pi: embedWorkingStatus); the standalone layer above
    ;; the editor only appears for a custom editor without a top-border fn.
    (let [si (ui/make-status-indicator
              :theme (cfg/get-theme config)
              ;; Embedded color (pi: showWorkingStatusIndicator's colorFn):
              ;; while the ACTIVE editor carries the status, the spinner and
              ;; message take the editor border color — the thinking level —
              ;; resolved per call, so a level change recolors without
              ;; reinstalling; standalone keeps the accent/muted pair.
              :border-color-fn
              (fn []
                (let [active @current-editor-atom]
                  (when (ui/editor-embeds-status? active)
                    (when-some [bf (:border-fn active)] @bf)))))
          ;; Theme controller (pi: InteractiveThemeController) — created in
          ;; the layout so CoreState carries it for all handlers (slash
          ;; commands, /reload, extension registry); applies the configured
          ;; theme, drives auto light/dark sync via color-scheme
          ;; notifications, and re-themes the app components live on change
          ;; (the on-changed callback — pi: notifyChanged → onChanged). The
          ;; callback runs only after the layout is fully bound.
          tc (theme-ctrl/make-theme-controller
              t config
              (fn [msg] (tui/tui-flash! t msg :duration-ms 3000))
              (fn []
                (let [current-theme (th/get-current-theme)]
                  ;; key-hint theme fns first — the header rebuild re-runs
                  ;; the content fns that use them
                  (app-kb/set-key-hint-theme-fns!
                   #(th/dim %) #(th/fg current-theme :muted %))
                  ;; transcript components subscribe to ui.subs/theme-sub —
                  ;; no walk needed (Stage 5); footer/indicator/resources
                  ;; keep their setter paths
                  (ui/footer-set-theme! ftr current-theme)
                  (ui/status-indicator-set-theme! si current-theme)
                  (ui/loaded-resources-set-theme! lr current-theme)
                  (expandable-text/expandable-text-rebuild! hdr)
                  (editor/editor-set-autocomplete-theme!
                   ed (th/get-select-list-theme current-theme))
                  ;; the editor's dynamic border is a border-fn baked at
                  ;; construction — re-style it from the active theme so the
                  ;; most prominent themed element changes with the palette
                  (reset! (:border-fn ed)
                          (th/get-thinking-border-color
                           current-theme (or @(:thinking ag) :off)))
                  (tui/tui-request-render t))))
          cs (assoc cs :status-indicator si :theme-controller tc)
          ;; The default editor renders the session status in its first
          ;; line — its top border (pi: CustomEditor renderTopBorder). The
          ;; hook reads the live status per render, so swaps and spinner
          ;; ticks repaint with the normal frame.
          _ (editor/editor-set-top-border-fn!
             ed
             (fn [{:keys [width hidden-line-count rule border-fn]}]
               (ui/editor-top-border
                {:indicator (current-status-indicator cs)
                 :width width
                 :hidden-line-count hidden-line-count
                 :rule rule
                 :border-fn border-fn})))
          ;; Pi layout (interactive-mode.ts setupUiLayout): the TUI root is a
          ;; single flat document — the transcript (header, loaded resources,
          ;; chat) followed top-to-bottom by pending messages, status, widgets
          ;; above the editor, editor, widgets below, footer. The render loop
          ;; keeps the viewport pinned to the document end: when the document
          ;; grows past the screen height it scrolls natively into the
          ;; terminal scrollback, so the whole interface scrolls together and
          ;; the editor (document end) stays visible at the bottom.
          header-container (container/make-container [sp1 hdr sp1])
          loaded-resources-container (container/make-container [lr])
          chat-container (container/make-container [ch])
          document-container (container/make-container [header-container
                                                        loaded-resources-container
                                                        chat-container])
          pending-messages-container (:pending-messages-container cs)
          ;; extension widget registries (pi: renderWidgets' maps) — read
          ;; tracked by the widget-area roots below, mutated by :set-widget
          widgets-above-atom (atom {})
          widgets-below-atom (atom {})
          ;; The status layer as a mounted DSL tree (dsl.md stage 4): the
          ;; root's reaction re-derives when :status-current swaps, and
          ;; reconcile swaps the child record — no clear/add dance. It
          ;; renders nothing while the active editor embeds the status in
          ;; its top border (the default editor), and the standalone
          ;; indicator otherwise (custom editors).
          status-root (hiccup/root (ui/make-status-area (:status-current cs)
                                                        si
                                                        (:current-editor-atom cs)))
          ;; Widget areas as mounted DSL trees (dsl.md stage 4, pi:
          ;; renderWidgets): the widget maps are read tracked, so a
          ;; :set-widget swap re-derives exactly once; the leading spacer is
          ;; a tree element reused across passes via the equal-props
          ;; fast-path (the hand-built default Spacer retires).
          widgets-above-root (hiccup/root
                              (make-widget-area-above widgets-above-atom))
          widgets-below-root (hiccup/root
                              (make-widget-area-below widgets-below-atom))
          ;; The editor dock as a mounted DSL tree (dsl.md stage 4): the
          ;; root re-derives when :dock-current or the active editor swaps —
          ;; selectors mount/unmount through pure atom writes.
          dock-root (hiccup/root (dock/make-dock-area (:dock-current cs)
                                                      (:current-editor-atom cs)))
          cs (assoc cs :status-root status-root
                    :dock-root dock-root)]

      ;; Add components in pi's layout-root order: the transcript document
      ;; first, then the dock children top-to-bottom (pending messages,
      ;; status, widgets above, editor, widgets below, footer)
      (tui/tui-add-child t document-container)
      (tui/tui-add-child t pending-messages-container)
      (tui/tui-add-child t status-root)
      (tui/tui-add-child t widgets-above-root)
      (tui/tui-add-child t dock-root)
      (tui/tui-add-child t widgets-below-root)
      (tui/tui-add-child t ftr)

      ;; Global quit (app.quit, ctrl+q by default): the input-listener chain
      ;; runs before the overlay modality guard and focus dispatch, so the
      ;; shortcut quits from anywhere — editor, selector, dialog included.
      ;; Registered before the heal listener so a quit key skips it.
      (tui/tui-add-input-listener t (global-quit-listener t))

      ;; Heal a stale scrollback on the next keystroke when idle (see
      ;; heal-stale-scrollback-when-idle!): short of a scroll event, input is
      ;; the only signal that the user is back at the bottom, and any render
      ;; already pulls the inline viewport there anyway.
      (tui/tui-add-input-listener
       t
       (fn [_data] (heal-stale-scrollback-when-idle! cs) nil))

      ;; Wire editor submit
      (editor/editor-set-on-submit! ed
                                    (fn [text]
                                      (when text
                                        (handle-submit cs text)
                                        (editor/editor-set-text! ed "")
                                        (tui/tui-request-render t))))

      ;; Editor actions (pi: CustomEditor.onAction) — app keybindings dispatched
      ;; through the editor's action system, which also checks the autocomplete
      ;; dropdown state (e.g. escape closes the dropdown instead of cancelling)
      (editor/editor-set-on-action! ed "app.interrupt"
        ;; pi: onEscape — abort the running agent turn or bash command
                                    (fn [] (handle-cancel cs)))
      (editor/editor-set-on-action! ed "app.exit"
                                    (fn [] (tui/tui-stop t)))
      ;; Force a clearing full redraw on demand — the escape hatch for a
      ;; corrupted or stale screen/scrollback, and the same heuristic rebuild
      ;; the suspend/resume path uses (tui-request-render with force).
      (editor/editor-set-on-action! ed "app.view.forceRedraw"
                                    (fn [] (tui/tui-request-render t true)))
      ;; pi: handleCtrlC — single ctrl+c clears the editor, double within
      ;; 500ms quits
      (let [last-ctrl-c (atom 0)]
        (editor/editor-set-on-action! ed "app.clear"
                                      (fn []
                                        (let [now (System/currentTimeMillis)]
                                          (if (< (- now @last-ctrl-c) 500)
                                            (tui/tui-stop t)
                                            (do (reset! last-ctrl-c now)
                                                (editor/editor-set-text! ed "")
                                                (tui/tui-request-render t)))))))
      (editor/editor-set-on-action! ed "app.tools.expand"
                                    (fn []
          ;; pi: the same toggle drives tool expansion, the builtInHeader, and
          ;; the loaded-resources sections (getStartupExpansionState) —
          ;; extended: ctrl+o cycles collapsed → expanded → quiet. Header,
          ;; resources and the info banner treat quiet as collapsed.
                                      (let [mode (ui/chat-history-cycle-tool-display! ch)
                                            expanded? (= :expanded mode)]
                                        (try (cfg/set-tool-display-mode! mode)
                                             (catch Exception e
                                               (debug/log "Failed to persist tool-display-mode: " e)))
                                        (expandable-text/expandable-text-set-expanded! hdr expanded?)
                                        (ui/loaded-resources-set-expanded! lr expanded?)
                                        (ui/chat-history-show-status! ch
                                                                      (str "Tool display: " (name mode)))
                                        (request-global-reflow-render! cs))))
      (editor/editor-set-on-action! ed "app.thinking.toggle"
                                    (fn []
          ;; pi: showStatus feedback on toggle + persist hideThinkingBlock to
          ;; settings so the state survives restarts (SettingsManager.save;
          ;; write errors are recorded — the toggle still applies)
                                      (let [hidden? (ui/chat-history-toggle-thinking-hidden! ch)]
                                        (try (cfg/set-hide-thinking-block! hidden?)
                                             (catch Exception e
                                               (debug/log "Failed to persist hide-thinking-block: " e)))
                                        (ui/chat-history-show-status! ch
                                                                      (str "Thinking blocks: " (if hidden? "hidden" "visible")))
                                        (request-global-reflow-render! cs))))
      ;; pi: cycleThinkingLevel — Shift+Tab cycles through available levels
      (editor/editor-set-on-action! ed "app.thinking.cycle"
                                    (fn []
                                      (let [ag @(:agent-state cs)
                                            model (models/get-model @(:provider ag) @(:model ag))
                                            levels (if model
                                                     (shared/get-supported-thinking-levels model)
                                                     [:off])]
                                        (if (<= (count levels) 1)
                                          (ui/chat-history-show-status! ch "Current model does not support thinking")
                                          (let [current @(:thinking ag)
                                                idx (or (first (keep-indexed (fn [i l] (when (= l current) i)) levels))
                                                        0)
                                                next-level (nth levels (mod (inc idx) (count levels)))]
                                            (agent/set-thinking-level! ag next-level)
                                            (cfg/save-setting! [:thinking] next-level)
                                            (sync-footer-model! cs)
                                            (update-editor-border-color! cs next-level)
                                            (ui/chat-history-show-status! ch (str "Thinking level: " (name next-level)))
                                            (tui/tui-request-render t))))))
      (editor/editor-set-on-action! ed "app.editor.external"
                                    (fn [] (handle-external-editor cs)))
      ;; B.3: Alt+Enter queues a follow-up (pi: handleFollowUp); Alt+Up
      ;; restores queued messages to the editor (pi: handleDequeue)
      (editor/editor-set-on-action! ed "app.message.followUp"
                                    (fn [] (handle-follow-up cs)))
      (editor/editor-set-on-action! ed "app.message.dequeue"
                                    (fn [] (handle-dequeue cs)))
      ;; Model selection/cycling (pi: onAction selectModel/cycleModelForward/
      ;; cycleModelBackward — the session scoped list feeds cycling (set via
      ;; /scoped-models, seeded from --models / settings :models); without
      ;; scoped models all available models cycle; a scoped entry may switch
      ;; the provider)
      (editor/editor-set-on-action! ed "app.model.select"
                                    (fn [] (show-model-selector cs)))
      (editor/editor-set-on-action! ed "app.model.cycleForward"
                                    (fn []
                                      (if (agent/cycle-model! @(:agent-state cs) 1)
                                        (do (cfg/set-default-model! @(:provider @(:agent-state cs))
                                                                    @(:model @(:agent-state cs)))
                                            (sync-footer-model! cs))
                                        (ui/chat-history-show-status!
                                         (:chat-history cs)
                                         (if (seq @(:scoped-models @(:agent-state cs)))
                                           "Only one model in scope"
                                           "Only one model available")))))
      (editor/editor-set-on-action! ed "app.model.cycleBackward"
                                    (fn []
                                      (if (agent/cycle-model! @(:agent-state cs) -1)
                                        (do (cfg/set-default-model! @(:provider @(:agent-state cs))
                                                                    @(:model @(:agent-state cs)))
                                            (sync-footer-model! cs))
                                        (ui/chat-history-show-status!
                                         (:chat-history cs)
                                         (if (seq @(:scoped-models @(:agent-state cs)))
                                           "Only one model in scope"
                                           "Only one model available")))))

      ;; Initialize footer (header content is produced lazily by the
      ;; ExpandableText fns on first render)
      (update-footer! cs)

      ;; Extension UI registry (pi: ExtensionUIContext) — installed after the
      ;; layout is live so extensions can drive the UI from event handlers
      (build-extension-ui-registry {:tui t :cs cs}
                                   {:ed ed :ftr ftr :hdr hdr :ch ch
                                    :sp1 sp1 :fdp fdp
                                    :header-container header-container
                                    :widgets-above-atom widgets-above-atom
                                    :widgets-below-atom widgets-below-atom}
                                   tc)

      ;; Expose the fully-built CoreState to the agent on-event handler (for
      ;; :status events) — after the layout assocs so the status
      ;; indicator/container and theme controller are present
      (reset! cs-ref cs)

      ;; Restore a --continue session into the chat history AND the agent
      ;; context (pi: renderInitialMessages from buildContextEntries) — the
      ;; agent's in-memory messages must mirror the session branch or the
      ;; next LLM call loses the whole restored conversation (steered and
      ;; follow-up messages included)
      (when (and session (seq @(:entries session)))
        (restore-session! cs session true))

      cs)))

;; ─── Extension UI registry (pi: ExtensionUIContext) ────────────────────────
;; build-layout installs this registry after the layout is live; extensions
;; call the kmet.app.extensions ui-* fns, which dispatch through it. All
;; closures capture the layout pieces they mutate.

(defn- make-widget-area-above
  "The above-editor widget strip as a fn component (dsl.md stage 4, pi:
   renderWidgets): a leading spacer plus the registered widgets. The widget
   map is read tracked — a :set-widget swap re-derives exactly once; the
   spacer is a tree element reused via the equal-props fast-path, widgets
   splice as foreign records (owned by the extension flow)."
  [widgets-atom]
  (fn [_props]
    ;; SEQ of sibling roots — widgets must be SIBLINGS of the spacer, not
    ;; its children (:spacer is a leaf tag; children on a leaf throws)
    (concat [[:spacer {:lines 1}]]
            (vals (r/tracked-deref widgets-atom)))))

(defn- make-widget-area-below
  "Below-editor widget area (pi: renderWidgets) — the registered widgets
   only, no leading spacer."
  [widgets-atom]
  (fn [_props]
    (vals (r/tracked-deref widgets-atom))))

(defn- dispose-dialog-component!
  "Dispose an extension dialog/widget value (kmet.app.ui.custom-dialog-adapter/
   dispose-component! — duck-typed :dispose, else the protocol multimethod)."
  [component]
  (cda/dispose-component! component))

(defn- make-extension-widget-component
  "Widget content forms (pi: renderWidgets' map values):
   - hiccup element tree → compiled once to a stamped component (spliceable
     into the widget strips; its dispose unwinds owned cleanups)
   - factory fn → (content t theme); a duck-typed render map result is
     adapted to a CustomDialogAdapter record so it splices into the strips'
     trees — component/record results pass through untouched"
  [t content]
  (if (fn? content)
    (let [c (content t (th/get-current-theme))]
      ;; a duck-typed map cannot splice into the widget strips' trees —
      ;; adapt it to a record; components/trees pass through untouched
      (if (and (map? c) (not (record? c)) (fn? (:render c)))
        (cda/map->CustomDialogAdapter
         {:render-fn (:render c)
          :handle-input-fn (:handle-input c)
          :invalidate-fn (:invalidate c)
          :dispose-fn (:dispose c)})
        c))
    (hiccup/compile-tree content)))

(defn- normalize-custom-component
  "Accept an IComponent, a plain render map {:render :handle-input
   :invalidate :dispose} (pi: custom() accepts both a Component and a
   duck-typed object), or a hiccup element tree — maps and trees are
   wrapped in a CustomDialogAdapter RECORD so the result always splices
   into hiccup trees by record? (reify wrappers would trip reconcile:
   bb's satisfies? misses reifies from other evaluation contexts even
   though dispatch works on them). Trees compile once here and the
   adapter's dispose unwinds them."
  [x]
  (cond
    ;; structural branches first: maps/trees are recognized reliably,
    ;; satisfies? only decides for foreign component objects (records
    ;; satisfy robustly; a hostile reify failing it fails LOUD at the
    ;; ui-custom call site instead of crashing a render pass)
    (and (map? x) (not (record? x)) (fn? (:render x)))
    (cda/map->CustomDialogAdapter
     {:render-fn (:render x)
      :handle-input-fn (:handle-input x)
      :invalidate-fn (:invalidate x)
      :dispose-fn (:dispose x)})
    (vector? x)
    (let [comp (hiccup/compile-tree x)]
      ;; static trees take no input; invalidate clears the compiled caches
      (cda/map->CustomDialogAdapter
       {:render-fn #(protocols/render comp %)
        :invalidate-fn #(protocols/invalidate comp)
        :dispose-fn #(hiccup/dispose-tree! comp)}))
    (satisfies? tui/IComponent x) x))

(defn- transfer-editor!
  "Copy the app editor's wiring onto a custom editor component (pi:
   setCustomEditorComponent). Components implementing IEditorComponent get
   the method-based transfer (pi: setText/setPaddingX/setAutocomplete…);
   others get pi's duck-typed property copy of the record fields, plus the
   CustomEditor action-handler/keybinding extras in both cases."
  [app-ed custom-ed keybindings]
  (if (satisfies? protocols/IEditorComponent custom-ed)
    (do (protocols/editor-set-on-submit! custom-ed @(:on-submit app-ed))
        (protocols/editor-set-on-change! custom-ed @(:on-change app-ed))
        (protocols/editor-set-padding-x! custom-ed @(:padding-x app-ed))
        (protocols/editor-set-autocomplete-max-visible!
         custom-ed @(:autocomplete-max-visible app-ed))
        (when-let [p @(:autocomplete-provider app-ed)]
          (protocols/editor-set-autocomplete-provider! custom-ed p)))
    (doseq [field [:on-submit :on-change :padding-x
                   :autocomplete-provider :terminal-rows-atom]]
      (when (contains? custom-ed field)
        (reset! (get custom-ed field) @(get app-ed field)))))
  ;; pi: appearance properties are assigned whenever the target has them,
  ;; regardless of protocol (borderColor, kmet's dynamic-height source)
  (doseq [field [:border-fn :terminal-rows-atom]]
    (when (contains? custom-ed field)
      (reset! (get custom-ed field) @(get app-ed field))))
  (when (contains? custom-ed :action-handlers)
    (doseq [[action-id f] @(:action-handlers app-ed)]
      (swap! (:action-handlers custom-ed) assoc action-id f)))
  (when (contains? custom-ed :keybindings)
    (reset! (:keybindings custom-ed) keybindings))
  nil)

(defn- normalize-autocomplete-provider
  "Accept either an AutocompleteProvider or a duck-typed map with
   :get-suggestions (fn [state]) and optional :apply-completion,
   :should-trigger-file-completion, :get-trigger-characters (pi-style
   object). Returns a provider or nil for anything else."
  [x]
  (cond
    (satisfies? ac/AutocompleteProvider x) x
    (map? x) (reify ac/AutocompleteProvider
               (get-suggestions [_ lines cursor-line cursor-col opts]
                 (when-let [f (:get-suggestions x)]
                   (f {:lines lines :cursor-line cursor-line
                       :cursor-col cursor-col :opts opts})))
               (apply-completion [_ lines cursor-line cursor-col item prefix]
                 (if-let [f (:apply-completion x)]
                   (f {:lines lines :cursor-line cursor-line
                       :cursor-col cursor-col :item item :prefix prefix})
                   ;; default: replace the prefix with the item value
                   (let [line (nth lines cursor-line "")
                         start (max 0 (- cursor-col (count prefix)))
                         new-line (str (subs line 0 start) (:value item)
                                       (subs line cursor-col))]
                     {:lines (assoc lines cursor-line new-line)
                      :cursor-line cursor-line
                      :cursor-col (+ start (count (:value item)))})))
               (should-trigger-file-completion [_ lines cursor-line cursor-col]
                 (boolean (and (:should-trigger-file-completion x)
                               ((:should-trigger-file-completion x)
                                {:lines lines :cursor-line cursor-line
                                 :cursor-col cursor-col}))))
               (get-trigger-characters [_]
                 (vec (:get-trigger-characters x []))))
    :else nil))

(defn- build-extension-ui-registry
  "Create the ExtensionUIContext implementation for the live layout
   (pi: createExtensionUIContext). Returns the capability map installed via
   extensions/set-ui-registry!."
  [{:keys [tui cs]}
   {:keys [ed ftr hdr ch sp1 fdp header-container
           widgets-above-atom widgets-below-atom]}
   theme-controller]
  (let [t tui
        custom-footer-atom (atom nil)
        custom-header-atom (atom nil)
        custom-dialog-comp (atom nil)
        ;; the ACTIVE editor — the default or a swapped-in custom editor
        ;; (pi: this.editor is rebound by setCustomEditorComponent); the atom
        ;; lives on CoreState so action handlers outside this closure (e.g.
        ;; the external-editor flow) see the active editor too
        current-editor-atom (:current-editor-atom cs)
        editor-factory-atom (atom nil)
        extension-autocomplete-factories (atom [])
        terminal-input-unsubscribers (atom [])
        hide-dialog (fn []
                      (reset! (:dock-current cs) nil)
                      (tui/tui-set-focus t @current-editor-atom)
                      (tui/tui-request-render t))
        rebuild-autocomplete-provider! (fn []
                                         ;; pi: setupAutocompleteProvider — each
                                         ;; extension factory wraps the provider
                                         ;; chain; nil results keep the base
                                         (let [base (ac/make-combined-provider
                                                     :commands-fn #(vec (concat
                                                                         (commands/get-commands)
                                                                         (prompts/as-command-maps (prompts/get-prompt-templates))
                                                                         (skills/as-command-maps (skills/get-skills))))
                                                     :base-path #(fdp/fdp-get-cwd fdp))
                                               provider (reduce (fn [prov factory]
                                                                  (or (normalize-autocomplete-provider
                                                                       (factory prov))
                                                                      prov))
                                                                base
                                                                @extension-autocomplete-factories)]
                                           (when (contains? @current-editor-atom
                                                            :autocomplete-provider)
                                             (editor/editor-set-autocomplete-provider!
                                              @current-editor-atom provider))
                                           nil))
        footer-data {:get-git-branch (fn [] (fdp/fdp-get-git-branch fdp))
                     :get-extension-statuses (fn []
                                               @(:extension-statuses-atom ftr))
                     :on-branch-change (fn [_f] (fn []))}
        registry
        {:notify (fn [message _type]
                   (tui/tui-flash! t message)
                   nil)
         ;; /session-style output for extension commands: append an :info
         ;; message to the chat history — part of the live transcript, no
         ;; overlay and nothing to dismiss (never sent to the LLM, never
         ;; persisted to the session; pi has no equivalent — kmet-specific).
         :chat-info (fn [label content]
                      (ui/chat-history-add-message!
                       ch {:role :info :label label :content (str content)})
                      (tui/tui-request-render t)
                      nil)
         ;; pi: ctx.ui.custom — mount an extension-built component (built
         ;; with kmet.tui.*) as an overlay or in the editor dock; the
         ;; factory gets (tui theme keybindings close) and close resolves
         ;; the returned promise
         :custom (fn [factory {:keys [overlay overlay-options on-handle]}]
                   (let [p (promise)
                         saved-text (editor-text-get @current-editor-atom)
                         closed (atom false)
                         close (fn [result]
                                 (when-not @closed
                                   (reset! closed true)
                                   ;; pi: dispose?() runs when the dialog
                                   ;; closes, before the component is
                                   ;; removed — a map/record :dispose fn
                                   (when-let [component @custom-dialog-comp]
                                     (dispose-dialog-component! component))
                                   (reset! custom-dialog-comp nil)
                                   (if overlay
                                     (tui/tui-hide-overlay t)
                                     (do (hide-dialog)
                                         (editor-text-set!
                                          @current-editor-atom saved-text)))
                                   (deliver p result)))]
                     (try
                       ;; pi: custom() accepts a Promise<Component> — deref
                       ;; with a timeout; a timeout or nil factory result
                       ;; hits the same error path as a throwing factory
                       (let [raw (factory t (th/get-current-theme) (tui-kb/get-global-keybindings) close)
                             raw (if (instance? clojure.lang.IDeref raw)
                                   (deref raw 5000 ::timeout)
                                   raw)
                             component (normalize-custom-component raw)]
                         (when (or (nil? component) (= ::timeout raw))
                           (when-not @closed
                             (throw (ex-info "ui-custom factory returned no component (or timed out)" {}))))
                         (when-not @closed
                           ;; a previous live dialog (defensive — normal flow
                           ;; closes first) unwinds exactly like widget replace
                           (when-let [prev @custom-dialog-comp]
                             (dispose-dialog-component! prev))
                           (reset! custom-dialog-comp component)
                           (if overlay
                             (let [opts (if (fn? overlay-options)
                                          (overlay-options)
                                          overlay-options)
                                   handle (tui/tui-show-overlay t component opts)]
                               (when on-handle (on-handle handle)))
                             (do (reset! (:dock-current cs) {:component component})
                                 (tui/tui-set-focus t component)
                                 (tui/tui-request-render t)))))
                       (catch Exception e
                         (when-not @closed
                           (reset! closed true)
                           (when-not overlay (hide-dialog))
                           (tui/tui-flash! t (str "Extension UI error: " (ex-message e)))
                           (deliver p nil))))
                     p))
         ;; footer-set-extension-status! swaps a track!-watched atom —
         ;; the watch schedules the frame (§3.4), no manual poke
         :set-status (fn [key text]
                       (ui/footer-set-extension-status! ftr key text))
         :set-widget (fn [key content options]
                       (let [placement (or (:placement options) :above-editor)
                             m (if (= :below-editor placement) widgets-below-atom widgets-above-atom)
                             existing (get @m key)]
                         ;; pi: removeExisting disposes the old widget on
                         ;; replace AND remove — skipping :remove would leak
                         ;; its cleanups. Duck-typed maps carry :dispose;
                         ;; compiled trees are IComponents.
                         (when existing
                           (dispose-dialog-component! existing))
                         (swap! m dissoc key)
                         (when content
                           (swap! m assoc key
                                  (make-extension-widget-component t content)))
                         ;; the area roots track the widget maps — the swap
                         ;; alone re-derives them and schedules the frame
                         ;; (§3.4); pre-first-frame registrations land in the
                         ;; roots' first render anyway
                         nil))
         :set-footer (fn [factory]
                       (when-let [cf @custom-footer-atom]
                         (when-let [dispose (:dispose cf)]
                           (try (dispose) (catch Exception _)))
                         (tui/tui-remove-child t cf))
                       (tui/tui-remove-child t ftr)
                       (if factory
                         (let [cf (factory t (th/get-current-theme) footer-data)]
                           (reset! custom-footer-atom cf)
                           (tui/tui-add-child t cf))
                         (do (reset! custom-footer-atom nil)
                             (tui/tui-add-child t ftr)))
                       (tui/tui-request-render t))
         :set-header (fn [factory]
                       (when @custom-header-atom
                         (when-let [dispose (:dispose @custom-header-atom)]
                           (try (dispose) (catch Exception _)))
                         (reset! custom-header-atom nil))
                       (let [child (if factory (factory t (th/get-current-theme)) hdr)]
                         (container/container-clear header-container)
                         (container/container-add-child header-container sp1)
                         (container/container-add-child header-container child)
                         (container/container-add-child header-container sp1)
                         (when-not factory
                           (expandable-text/expandable-text-rebuild! hdr))
                         (tui/tui-request-render t)))
         :set-title (fn [title]
                      (when-let [term @(:terminal t)] (term/set-title! term title)))
         :on-terminal-input (fn [handler]
                              (tui/tui-add-input-listener t handler)
                              (let [unsub (fn [] (tui/tui-remove-input-listener t handler))]
                                (swap! terminal-input-unsubscribers conj unsub)
                                unsub))
         :set-editor-text (fn [text]
                            (editor-text-set! @current-editor-atom text)
                            (tui/tui-request-render t))
         :get-editor-text (fn [] (editor-text-get-expanded @current-editor-atom))
         :paste-to-editor (fn [text]
                            (tui/handle-input @current-editor-atom
                                              (str "\u001b[200~" text "\u001b[201~")))
         :set-working-indicator (fn [options]
                                  (spinner/spinner-set-indicator!
                                   (:spinner (:status-indicator cs)) options)
                                  (tui/tui-request-render t))
         :set-working-message (fn [message]
                                (ui/status-indicator-set-text! (:status-indicator cs)
                                                               (or message "Working"))
                                (tui/tui-request-render t))
         :set-working-visible (fn [visible?]
                                ;; Pi: setWorkingVisible — clearStatusIndicator("working")
                                ;; when hiding (kind-gated: a transient retry/
                                ;; compaction/share indicator stays), re-show the
                                ;; working indicator when showing (only while the
                                ;; turn runs, and only when it isn't already the
                                ;; active status — pi checks the active kind, so
                                ;; re-showing never restarts a spinning clock).
                                ;; both branches schedule through the
                                ;; guarded :status-current swap when real
                                (if visible?
                                  (when (and @(:running-turn? cs)
                                             (not (ui/status-indicator-active?
                                                   (:status-indicator cs))))
                                    (activate-working-indicator! cs))
                                  (clear-status-indicator! cs :working)))
         :set-hidden-thinking-label (fn [label]
                                      ;; one reset! on the shared label atom;
                                      ;; assistant messages' watches schedule
                                      (ui/chat-history-set-hidden-thinking-label!
                                       ch label))
         :set-editor-component (fn [factory]
                                 (let [current-text (editor-text-get @current-editor-atom)]
                                   ;; pi parity: setCustomEditorComponent runs
                                   ;; disposeActiveSelector() then clears the dock —
                                   ;; the swap displaces whatever it held, and a
                                   ;; displaced selector's done() goes inert
                                   (dock/invalidate-pending!)
                                   (reset! (:dock-current cs) nil)
                                   (if factory
                                     (let [new-ed (factory t (th/get-current-theme) (tui-kb/get-global-keybindings))]
                                       (transfer-editor! ed new-ed (tui-kb/get-global-keybindings))
                                       (editor-text-set! new-ed current-text)
                                       (tui/tui-set-focus t new-ed)
                                       ;; tracked by the dock area: the swap alone re-derives
                                       (reset! current-editor-atom new-ed))
                                     (do (editor-text-set! ed current-text)
                                         (tui/tui-set-focus t ed)
                                         (reset! current-editor-atom ed)))
                                   (reset! editor-factory-atom factory)
                                   (tui/tui-request-render t)))
         :add-autocomplete-provider (fn [factory]
                                      (swap! extension-autocomplete-factories conj factory)
                                      (rebuild-autocomplete-provider!))
         :set-theme (fn [theme-or-name]
                      ;; pi: setTheme — Theme instances go through
                      ;; setThemeInstance; names through setThemeName (which
                      ;; disables auto-sync). kmet has no settings write
                      ;; path — the switch is applied live only.
                      (if (instance? kmet.tui.theme.Theme theme-or-name)
                        (theme-ctrl/set-theme-instance! theme-controller theme-or-name)
                        (theme-ctrl/set-theme-name! theme-controller theme-or-name true)))
         :get-tools-expanded (fn [] (ui/chat-history-get-tool-expanded ch))
         :set-tools-expanded (fn [expanded?]
                               ;; the flag swap invalidates every tool
                               ;; component's watch, which schedules the frame
                               (let [current? (ui/chat-history-get-tool-expanded ch)]
                                 (when (not= current? expanded?)
                                   (ui/chat-history-toggle-tool-expanded! ch)
                                   (request-global-reflow-render! cs))))
         :get-tool-display-mode (fn [] (ui/chat-history-get-tool-display-mode ch))
         :set-tool-display-mode (fn [mode]
                                  (when (not= mode (ui/chat-history-get-tool-display-mode ch))
                                    (ui/chat-history-set-tool-display-mode! ch mode)
                                    (try (cfg/set-tool-display-mode! mode)
                                         (catch Exception e
                                           (debug/log "Failed to persist tool-display-mode: " e)))
                                    (expandable-text/expandable-text-set-expanded!
                                     (:header-comp cs) (= :expanded mode))
                                    (ui/loaded-resources-set-expanded!
                                     (:loaded-resources-comp cs) (= :expanded mode))
                                    (request-global-reflow-render! cs)))
         ;; pi: registerShortcut — a raw key-id bound as a priority editor
         ;; action, checked before every builtin app binding (escape
         ;; included). The keybinding definition is registered on the global
         ;; manager so key-hints and user overrides resolve. Last
         ;; registration of a key wins; deregistration removes only its own.
         :register-shortcut! (fn [key-id {:keys [description handler]}]
                               (let [kmgr (tui-kb/get-global-keybindings)
                                     key-id (str/lower-case (str key-id))]
                                 (tui-kb/register-definition!
                                  kmgr key-id
                                  {:default-keys [key-id]
                                   :description (or description "Extension shortcut")})
                                 (editor/editor-set-priority-action!
                                  ed key-id
                                  (fn []
                                    (try
                                      (handler (extensions/build-extension-context))
                                      (catch Exception e
                                        (tui/tui-flash!
                                         t (str "Extension shortcut error: " (ex-message e)))))))
                                 (fn []
                                   (tui-kb/unregister-definition! kmgr key-id)
                                   (editor/editor-set-priority-action! ed key-id nil))))
         ;; Agent control (pi: ctx.setModel / getThinkingLevel /
         ;; setThinkingLevel / sendUserMessage / getActiveTools /
         ;; setActiveTools)
         :set-model (fn [model]
                      (if (and model (models/has-configured-auth model))
                        (let [ag @(:agent-state cs)
                              old-model (models/get-model @(:provider ag) @(:model ag))]
                          (reset! (:provider ag) (:provider model))
                          (agent/set-model! ag (:id model))
                          (let [new-thinking (agent/switch-thinking-level old-model model @(:thinking ag) nil)]
                            (agent/set-thinking-level! ag new-thinking)
                            (cfg/set-default-model! (:provider model) (:id model))
                            (sync-footer-model! cs)
                            (update-editor-border-color! cs new-thinking)
                            (tui/tui-request-render (:tui cs)))
                          true)
                        false))
         :set-thinking-level (fn [level]
                               (when (contains? #{:off :minimal :low :medium
                                                  :high :xhigh :max} level)
                                 (agent/set-thinking-level! @(:agent-state cs) level)
                                 (sync-footer-model! cs)
                                 (update-editor-border-color! cs level)
                                 (tui/tui-request-render (:tui cs)))
                               nil)
         :get-thinking-level (fn []
                               @(:thinking @(:agent-state cs)))
         :send-user-message (fn [text & [{:keys [deliver-as expand-prompt-templates?]}]]
                              (let [ag @(:agent-state cs)
                                    ;; pi: prompt() throws while compaction is
                                    ;; in progress — extension messages cannot
                                    ;; queue into the UI compaction queue.
                                    _ (when @(:compacting? ag)
                                        (throw (ex-info "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."
                                                        {:type :compaction-in-progress})))
                                    ;; pi: prompt() with expandPromptTemplates —
                                    ;; extension commands execute immediately
                                    ;; (consuming the message), then skill
                                    ;; commands + prompt templates expand.
                                    ;; kmet defaults to NO expansion (the
                                    ;; existing behavior); opt in explicitly.
                                    text (if expand-prompt-templates?
                                           (expand-user-message-text cs text)
                                           text)]
                                (when text
                                  (if (= :idle @(:status ag))
                                    ;; pi: sendUserMessage always triggers a
                                    ;; turn when idle
                                    (start-agent-run! cs text)
                                    (if (= :steer deliver-as)
                                      (agent/steer! ag text)
                                      (agent/follow-up! ag text))))
                                ;; both updates schedule their own frames
                                (update-pending-messages! cs)
                                (update-footer! cs)
                                nil))
         ;; pi: sendMessage — a custom message: persisted as a custom_message
         ;; session entry, injected into the agent context (sent to the LLM
         ;; as a user message; rendered when :display) and optionally
         ;; triggering a turn. Idle + trigger-turn starts the run (the
         ;; message is already in context); busy queues via deliver-as
         ;; (:steer injects immediately — the next LLM call sees it;
         ;; anything else defers to the next turn).
         :send-message! (fn [message & [opts]]
                          (let [ag @(:agent-state cs)
                                ;; pi: sendMessage → prompt — custom messages
                                ;; cannot be submitted while compaction is in
                                ;; progress (the compaction queue is UI-only).
                                _ (when @(:compacting? ag)
                                    (throw (ex-info "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."
                                                    {:type :compaction-in-progress})))
                                custom-type (or (:custom-type message) :custom)
                                display (if (nil? (:display message)) true (:display message))
                                msg {:role :custom
                                     :custom-type custom-type
                                     :content (:content message)
                                     :display display
                                     :details (:details message)}]
                            (when (:session ag)
                              (session/append-custom-message-entry!
                               (:session ag) custom-type (:content message)
                               display (:details message)))
                            (agent/add-context-message! ag msg)
                            (when (:trigger-turn opts)
                              (if (= :idle @(:status ag))
                                (start-agent-run! cs)
                                (if (= :steer (:deliver-as opts))
                                  ;; already in context — the next LLM call
                                  ;; sees it (pi: steer into the current run)
                                  nil
                                  (agent/follow-up! ag msg))))
                            ;; both updates schedule their own frames
                            (update-pending-messages! cs)
                            (update-footer! cs)
                            true))
         :get-active-tools (fn []
                             @(:enabled-tools @(:agent-state cs)))
         :set-active-tools (fn [names]
                             ;; agent state only — no display depends on
                             ;; this synchronously
                             (agent/set-active-tools! @(:agent-state cs) names)
                             nil)
         ;; Extension context (pi: ExtensionContext) — captures the live
         ;; layout/agent state per call; the headless default in
         ;; extensions.clj covers everything else (mode/has-ui/…)
         :build-context (fn []
                          ;; capture the agent-state ATOM: session swaps
                          ;; assoc a NEW record onto the atom, so the old
                          ;; record's :session field goes stale (compact);
                          ;; the atom fields are shared and always current
                          (let [ag-atom (:agent-state cs)
                                ag @ag-atom]
                            {:mode :interactive
                             :has-ui true
                             :cwd (fdp/fdp-get-cwd fdp)
                             :model @(:model ag)
                             :scoped-models @(:scoped-models ag)
                             :thinking-level @(:thinking ag)
                             :is-idle (fn [] (= :idle @(:status @ag-atom)))
                             :has-pending-messages (fn []
                                                     (boolean
                                                      (agent/has-queued-messages?
                                                       @ag-atom)))
                             :signal (fn [] @(:signal @ag-atom))
                             :abort (fn []
                                      (when-not (= :idle @(:status @ag-atom))
                                        ;; pi: ctx.abort() restores queued
                                        ;; steering/follow-up messages to the
                                        ;; editor before aborting — a message
                                        ;; only reaches the chat once the loop
                                        ;; consumes it, so clearing the queues
                                        ;; without restoring would drop it
                                        ;; entirely (restoreQueuedMessagesToEditor
                                        ;; {abort:true})
                                        (restore-queued-messages! cs)
                                        (agent/cancel-turn @ag-atom)))
                             :shutdown (fn [] (tui/tui-stop t))
                             :get-context-usage (fn []
                                                  ;; pi: null without an
                                                  ;; active session
                                                  (when-let [_ (fdp/fdp-get-session fdp)]
                                                    (let [tokens (fdp/fdp-context-tokens fdp)
                                                          window (fdp/fdp-get-context-window fdp)]
                                                      {:tokens tokens
                                                       :context-window window
                                                       :percent (when (and tokens window
                                                                           (pos? window))
                                                                  (int (* 100.0 (/ tokens window))))})))
                             :compact (fn [& [{:keys [custom-instructions
                                                      on-complete on-error]}]]
                                        (future
                                          (try
                                            (let [r (agent/compact-context! @ag-atom custom-instructions :manual)]
                                              (if (and (= :failed r) on-error)
                                                ;; pi: compact() throws on
                                                ;; summarization failure →
                                                ;; onError fires
                                                (on-error (ex-info "Context compaction failed: the summarization call did not return a summary."
                                                                   {:type :compaction-failed}))
                                                (when on-complete (on-complete {:result r}))))
                                            (catch Exception e
                                              (when on-error (on-error e))))))
                             :get-system-prompt (fn [] @(:system @ag-atom))
                             :get-system-prompt-options (fn []
                                                          (let [config (:config cs)]
                                                            {:custom-prompt (cfg/get-custom-prompt config)
                                                             :append-prompt (cfg/get-append-system-prompt config)
                                                             :context-files (context/load-project-context-files
                                                                             (cfg/get-agent-dir)
                                                                             (str (fs/cwd)))}))
                             :wait-for-idle (fn []
                                              (if (= :idle @(:status @ag-atom))
                                                nil
                                                (let [p (promise)]
                                                  (future
                                                    (loop []
                                                      (if (= :idle @(:status @ag-atom))
                                                        (deliver p true)
                                                        (do (Thread/sleep 100) (recur)))))
                                                  p)))
                             :reload (fn [] (handle-reload cs nil))
                             :new-session (fn [& _]
                                            (handle-new-session cs)
                                            {:cancelled false})
                             :fork (fn [entry-id & _]
                                     (if entry-id
                                       (do (fork-at! cs entry-id) {:cancelled false})
                                       {:cancelled true}))
                             :navigate-tree (fn [target-id & [{:keys [summarize
                                                                      custom-instructions
                                                                      replace-instructions
                                                                      label]}]]
                                              (if-let [sess @(:session-atom cs)]
                                                (if-let [entry (session/get-entry sess
                                                                                  target-id)]
                                                  (do (navigate-tree! cs sess entry
                                                                      (boolean summarize)
                                                                      custom-instructions
                                                                      (boolean replace-instructions)
                                                                      label)
                                                      {:cancelled false})
                                                  {:cancelled true})
                                                {:cancelled true}))
                             :switch-session (fn [session-path & _]
                                               (try
                                                 (let [sess (session/load-session session-path)
                                                       ;; pi: emitBeforeSwitch (reason :resume) —
                                                       ;; extensions may cancel the switch
                                                       result (event-bus/emit-event!
                                                               {:type :session-before-switch
                                                                :reason :resume
                                                                :target-session-file session-path})]
                                                   (if (:cancel result)
                                                     {:cancelled true}
                                                     (do (restore-session! cs sess true)
                                                         {:cancelled false})))
                                                 (catch Exception _ {:cancelled true})))
                             :is-project-trusted (fn [] false)}))
         :reset (fn []
                  ;; pi: resetExtensionUI — dispose widgets, restore
                  ;; footer/header/editor, clear statuses + working
                  ;; customization, drop terminal input listeners
                  (doseq [m [widgets-above-atom widgets-below-atom]]
                    (doseq [w (vals @m)]
                      (when-let [dispose (:dispose w)]
                        (try (dispose) (catch Exception _)))))
                  (reset! widgets-above-atom {})
                  (reset! widgets-below-atom {})
                  (when @custom-footer-atom
                    (when-let [dispose (:dispose @custom-footer-atom)]
                      (try (dispose) (catch Exception _)))
                    (tui/tui-remove-child t @custom-footer-atom)
                    (reset! custom-footer-atom nil)
                    (tui/tui-add-child t ftr))
                  (when @custom-header-atom
                    (when-let [dispose (:dispose @custom-header-atom)]
                      (try (dispose) (catch Exception _)))
                    (reset! custom-header-atom nil)
                    (container/container-clear header-container)
                    (container/container-add-child header-container sp1)
                    (container/container-add-child header-container hdr)
                    (container/container-add-child header-container sp1)
                    (expandable-text/expandable-text-rebuild! hdr))
                  (when-let [component @custom-dialog-comp]
                    (dispose-dialog-component! component)
                    (reset! custom-dialog-comp nil))
                  (doseq [unsub @terminal-input-unsubscribers]
                    (try (unsub) (catch Exception _)))
                  (reset! terminal-input-unsubscribers [])
                  (doseq [key (keys @(:extension-statuses-atom ftr))]
                    (ui/footer-set-extension-status! ftr key nil))
                  (spinner/spinner-set-indicator! (:spinner (:status-indicator cs)) nil)
                  (ui/status-indicator-set-text! (:status-indicator cs) "Working")
                  (ui/chat-history-set-hidden-thinking-label! ch nil)
                  (reset! extension-autocomplete-factories [])
                  (rebuild-autocomplete-provider!)
                  (when @editor-factory-atom
                    (let [current-text (editor-text-get @current-editor-atom)]
                      (editor-text-set! ed current-text)
                      (tui/tui-set-focus t ed)
                      (reset! current-editor-atom ed))
                    (reset! editor-factory-atom nil))
                  ;; restore any open dialog
                  (reset! (:dock-current cs) nil)
                  (tui/tui-set-focus t ed)
                  (reset! current-editor-atom ed)
                  (when (tui/tui-has-overlay? t) (tui/tui-hide-overlay t))
                  (tui/tui-request-render t))}]
    (extensions/set-ui-registry! registry)
    ;; Live session + context injection for extensions (pi:
    ;; ctx.sessionManager / custom messages flowing through the agent loop).
    ;; Installed once with the registry; reload mutates the same CoreState
    ;; (agent, session atom), so the wiring stays valid across reloads —
    ;; only /new, /resume, fork and clone re-register the session.
    (extensions/set-session! @(:session-atom cs))
    (extensions/set-context-sink!
     (fn [msg] (agent/add-context-message! @(:agent-state cs) msg)))
    (extensions/set-entry-sink!
     (fn [entry]
       (when-let [rendered (run-entry-renderer
                            (extensions/get-entry-renderer (:custom-type entry)) entry)]
         (ui/chat-history-add-message!
          (:chat-history cs)
          (renderer-result->message rendered)))))
    registry))

;; ─── Run ───────────────────────────────────────────────────────────────────

(defn- maybe-show-cache-miss-notice!
  "pi: maybeShowCacheMissNotice — when :show-cache-miss-notices is on and
   the completed turn (RUN-MESSAGES) paid for a significant prompt-cache
   miss, add a transcript notice. Display floor: >= 20k tokens re-billed
   (pi's `missedTokens < 20_000 && missedCost < 0.1` is simplified to the
   token arm — kmet has no per-message price lookup here)."
  [cs run-messages]
  (when (and (cfg/get-show-cache-miss-notices (:config cs))
             ;; the run must have produced an assistant message with usage
             (some #(and (= :assistant (:role %)) (:usage %)) run-messages))
    (when-let [sess @(:session-atom cs)]
      (when-let [miss (session/detect-cache-miss (session/get-branch sess))]
        (when (>= (:missed-tokens miss) 20000)
          (ui/chat-history-add-message!
           (:chat-history cs)
           {:role :info
            :label (if (:model-changed miss)
                     "Cache miss after model switch"
                     "Cache miss")
            :content (str (:missed-tokens miss) " tokens re-billed")}))))))

(defn run
  "Start the interactive TUI with the given config and CLI opts.
   Loads extensions, resolves the session (:resume/:continue/new), builds the
   layout, and runs the TUI loop until quit. Cleans up the TUI and tracked
   child processes on error, then rethrows for the top-level handler.
   pi: cli.js dispatch to interactive mode."
  [config opts]
  (let [tui-ref (atom nil)]
    (try
      ;; Extensions were loaded before dispatch (core/-main, pi: extension
      ;; discovery before model resolution); /reload re-loads them.

      ;; Apply command-line overrides
      (let [config (cfg/apply-cli-overrides config opts)
            _ (reset! global-config config)
            session (cond
                      (:session opts)
                      (let [base-dir (cfg/get-session-dir config)
                            path (resolve-session-arg (:session opts) base-dir)]
                        (if path
                          (session/load-session path)
                          (do (binding [*out* *err*]
                                (println (str "No session found matching '" (:session opts) "'")))
                              (System/exit 1))))
                      (:resume opts) nil
                      (:continue opts) (if-let [path (find-session)]
                                         ;; find-session returns the session
                                         ;; file path — load it into a Session
                                         ;; record so the context and chat can
                                         ;; be restored below
                                         (session/load-session path)
                                         ;; pi: continueRecent — no session to
                                         ;; continue → start a fresh one
                                         (session/create-session (ensure-cwd-session-dir)))
                      :else (session/create-session (ensure-cwd-session-dir)))
            cs (build-layout config session)]
        (reset! tui-ref (:tui cs))
        (when (:resume opts)
          (show-session-selector cs ensure-session-dir
                                 (fn [path]
                                   ;; pi: emitBeforeSwitch (reason :resume) —
                                   ;; extensions may cancel the switch
                                   (when-not (:cancel (event-bus/emit-event!
                                                       {:type :session-before-switch
                                                        :reason :resume
                                                        :target-session-file path}))
                                     (let [sess (session/load-session path)
                                           short-id (subs (:id sess) 0 (min 8 (count (:id sess))))]
                                       (restore-session! cs sess true)
                                       (ui/chat-history-add-message! (:chat-history cs)
                                                                     {:role :assistant
                                                                      :content (str "Resumed session " short-id ".")})
                                       (tui/tui-request-render (:tui cs)))))))
        ;; start the UI before initializing extensions so session_start
        ;; handlers can use interactive dialogs — kmet loads extensions
        ;; earlier, so the event fires once the layout + UI registry are
        ;; live and the render loop is running (the future waits for it).
        (future
          (try
            (loop []
              (when-not (or @(:running? (:tui cs))
                            @(:stopped? (:tui cs)))
                (Thread/sleep 20)
                (recur)))
            ;; skipped entirely when the TUI already stopped (immediate quit)
            (when @(:running? (:tui cs))
              (event-bus/emit-event!
               {:type :session-start
                :reason (cond (:resume opts) :resume
                              (:continue opts) :continue
                              :else :new)})
              ;; pi: resources_discover fires after session_start (reason
              ;; startup for non-reload session starts)
              (extensions/discover-resources! :startup))
            (catch Exception e
              (debug/log "session-start: " e))))
        ;; Theme detection + application (pi: startup applyFromSettings) —
        ;; waits for the render loop so OSC 11 / color-scheme responses are
        ;; consumed by the input path.
        (future
          (try
            (loop []
              (when-not (or @(:running? (:tui cs))
                            @(:stopped? (:tui cs)))
                (Thread/sleep 20)
                (recur)))
            (when @(:running? (:tui cs))
              (theme-ctrl/apply-from-settings! (:theme-controller cs)))
            (catch Exception e
              (debug/log "theme detection: " e))))
        ;; Set the initial terminal title (pi: updateTerminalTitle in init —
        ;; after ui.start). Waits until the backend reports started? (raw mode
        ;; entered; a write before that could interleave with the pre-TUI
        ;; terminal state); --continue/--resume sessions restored in
        ;; build-layout get their display name reflected here.
        (future
          (try
            (loop []
              (when-not (or @(:running? (:tui cs))
                            @(:stopped? (:tui cs)))
                (Thread/sleep 20)
                (recur)))
            (when @(:running? (:tui cs))
              (loop []
                (when (and @(:running? (:tui cs))
                           (not (term/started? @(:terminal (:tui cs)))))
                  (Thread/sleep 20)
                  (recur)))
              (when @(:running? (:tui cs))
                (update-terminal-title! cs)))
            (catch Exception e
              (debug/log "terminal title: " e))))
        (tui/tui-start (:tui cs))
        (process/kill-tracked-children!)
        (when-let [resume (format-resume-command @(:session-atom cs) config)]
          (println (str (th/dim "To resume this session:") " " resume)))
        (:tui cs))
      (catch Exception e
        ;; Restore terminal if TUI was started, then rethrow for -main
        (process/kill-tracked-children!)
        (when-let [t @tui-ref]
          (try (tui/tui-stop t) (catch Exception _)))
        (throw e)))))
