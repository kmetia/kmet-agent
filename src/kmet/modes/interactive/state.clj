(ns kmet.modes.interactive.state
  "Shared state of the interactive mode: the CoreState record, the
   process config ref, session-directory helpers, header formatting,
   and the live-state updaters (footer/title/border + selector
   mounting) called from every interactive section."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.config :as cfg]
            [kmet.app.session :as session]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.app.ui.dock :as dock]
            [kmet.tui.theme :as th]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as term]
            [kmet.libs.host :as host]))

;; ─── Global config ref ────────────────────────────────────────────────────

(defonce ^:private global-config (atom nil))

(defn set-global-config!
  "Install the process config (kmet.modes.interactive/run and
   /reload); session helpers read it for the sessions dir."
  [config]
  (reset! global-config config)
  nil)

;; ─── Session helpers ───────────────────────────────────────────────────────

(defn- get-session-dir []
  (if-let [c @global-config]
    (cfg/get-session-dir c)
    (str (System/getProperty "user.home") "/.kmet/sessions")))

(defn ensure-session-dir
  "The base sessions dir (created). Listings (resume-session) walk it plus
   its cwd-encoded subdirectories (pi: listAll — G2), so legacy flat session
   files remain visible alongside per-project ones."
  []
  (let [d (get-session-dir)]
    (fs/create-dirs d)
    d))

(defn ensure-cwd-session-dir
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

(defn runtime-cwd
  "The working directory of CS's runtime (pi: AgentSession._cwd, seeded from
   the active session's recorded cwd): the footer data provider's cwd — the
   live value the footer pwd, the extension ctx.cwd, and tool components
   read — else the process cwd (tests, headless)."
  [cs]
  (or (some-> (:footer-provider cs) fdp/fdp-get-cwd) (str (fs/cwd))))

(defn turn-running?
  "True when a response is streaming (the CoreState turn flag) — the
   condition the switch commands refuse on. Nil-safe: a CS without the flag
   (test stubs, extension contexts built over reduced state) counts as idle."
  [cs]
  (boolean (some-> (:running-turn? cs) deref)))

(defn find-session
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

(defn format-resume-command
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
            ;; the recorded header cwd, not session/session-cwd (which checks
            ;; existence): the hint needs the directory the session *belongs*
            ;; to even when it is gone — expected-dir below is derived from it
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

(defn resolve-session-arg
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

(defn fmt-header-compact
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

(defn fmt-header-full
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

(defn update-editor-border-color!
  "Update the editor border color to reflect the given thinking LEVEL.
   Pi: updateEditorBorderColor — sets borderColor based on session.thinkingLevel.
   Reads the ACTIVE theme (not the config :theme setting, which is a stale
   startup snapshot after /theme), so a theme switch re-styles the border."
  [cs level]
  (reset! (:border-fn (:editor cs))
          (th/get-thinking-border-color (th/get-current-theme) level)))

(defn update-footer!
  "Sync the footer's session data source (cs → fdp bridge). No explicit
   invalidation: the footer's track! pass declares the fdp atoms — and the
   live session :entries vector, which mutates in place — as track-deps,
   so every change here re-derives the footer and schedules the frame
   reactively (§3.4 hook)."
  [cs]
  (fdp/fdp-set-session! (:footer-provider cs) @(:session-atom cs))
  nil)

(defn update-terminal-title!
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

;; ─── Selector mounting (shared by the login and session-tree flows) ────────

(defn mount-selector!
  "Swap SEL into CS's editor dock, recording the mount's done and the
   selector itself on SEL-ATOM so close-selector! can unwind both."
  [cs sel-atom sel]
  (reset! sel-atom {:done (dock/mount! cs sel) :sel sel}))

(defn close-selector!
  "Run the mount's done (restores the editor) and dispose the selector —
   the dock drops foreign records without disposing them, so the root
   reaction + foreign inputs would otherwise outlive the panel."
  [sel-atom]
  ((:done @sel-atom))
  (when-let [s (:sel @sel-atom)] (protocols/dispose s)))
