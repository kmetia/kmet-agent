(ns kmet.ui.tui.interactive.state
  "Shared state of the interactive mode: the CoreState record, the
   process config ref, session-directory helpers, header formatting,
   and the live-state updaters (footer/title/border + selector
   mounting) called from every interactive section."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.app.session.core :as session]
            [kmet.app.session.runtime :as runtime]
            [kmet.app.keybindings :as app-kb]
            [kmet.ui.tui.footer-data-provider :as fdp]
            [kmet.ui.tui.dock :as dock]
            [kmet.libs.theme :as th]
            [kmet.tui.terminal :as term]
            [kmet.libs.host :as host]))

;; ─── Session helpers ───────────────────────────────────────────────────────

(defn ensure-session-dir
  "The base sessions dir (created). Listings (resume-session) walk it plus
   its cwd-encoded subdirectories (pi: listAll — G2), so legacy flat session
   files remain visible alongside per-project ones. CONFIG is the loaded
   config map."
  [config]
  (let [d (cfg/get-session-dir config)]
    (fs/create-dirs d)
    d))

(defn ensure-cwd-session-dir
  "The sessions dir for a cwd — where new sessions are placed:
   BASE/<encode-cwd CWD>/ (pi: getDefaultSessionDir — per-project isolation, G2).
   CONFIG is the loaded or runtime config map and CWD is the directory the
   session belongs to."
  [config cwd]
  (runtime/ensure-cwd-session-dir (cfg/get-session-dir config) cwd))

(defn runtime-cwd
  "The working directory of CS's runtime (pi: AgentSession._cwd, seeded from
   the active session's recorded cwd): the live value the footer pwd, the
   extension ctx.cwd, and tool components read. The footer data provider
   holds the runtime's cwd atom in production; a test CoreState without one
   falls back to the engine's, else the process cwd."
  [cs]
  (or (some-> (:footer-provider cs) fdp/fdp-get-cwd)
      (runtime/cwd (:engine cs))
      (str (fs/cwd))))

(defn turn-running?
  "True while a prompt has not settled. The run owns the turn flag — an
   engine's prompt wrapper or a handle's start/settle — and a nil engine
   counts as idle."
  [cs]
  (runtime/turn-running? (:engine cs)))

(defn cancel-bash!
  "Ask the mode's running bash command to stop: signal it and drop the
   running flag (the executor returns, the bash future records the result
   and marks its component complete). True when one was running, else
   false."
  [cs]
  (if @(:bash-running? cs)
    (do (reset! (:bash-signal cs) true)
        (reset! (:bash-running? cs) false)
        true)
    false))

(defn find-session
  "Most recent session for the current cwd (pi: continueRecent →
   findMostRecentSession — header-based discovery in the cwd-encoded dir;
   legacy headerless sessions and other-cwd files are excluded, no fallback).
   Used by --continue so the current project's last session is resumed.
   CONFIG is the loaded config map."
  [config]
  (runtime/find-recent-session (ensure-session-dir config) (str (fs/cwd))))

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
    (when (host/stdout-tty?)
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

;; ─── Core state ────────────────────────────────────────────────────────────

(defrecord CoreState [tui
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
                      pending-tool-comps
                      bash-running?
                      bash-signal
                      pending-bash-components
                      pending-messages-container
                      dock-root
                      dock-stack
                      theme-controller
                      ;; the kmet.app.session.runtime engine; the layout owns
                      ;; view state and subscribes to the engine's stream
                      engine
                      ;; the run's composition ctx: `/units` and /reload's
                      ;; stage re-seat read it, so the mode never reaches for a
                      ;; process root it was not given
                      ctx])

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
  (fdp/fdp-set-session! (:footer-provider cs) (runtime/session (:engine cs)))
  nil)

(defn update-terminal-title!
  "Set the terminal window title to \"kmet - <session name> - <cwd basename>\"
   (pi: updateTerminalTitle — the session's runtime cwd, not the process
   cwd). The session display name is included when set (/name); an explicit
   empty name clears it, falling back to just app + cwd. No-ops when the
   TUI/terminal isn't live (e.g. tests with a stub tui)."
  [cs]
  (let [title (str "kmet"
                   (when-let [name (session/get-session-name (runtime/session (:engine cs)))]
                     (str " - " name))
                   " - " (fs/file-name (runtime-cwd cs)))]
    (when-let [term (:terminal (:tui cs))]
      (term/set-title! @term title))))

;; ─── Selector mounting (shared by the login and session-tree flows) ────────

(defn- install-selector!
  [dock-fn cs sel-atom sel]
  (reset! sel-atom sel)
  (dock-fn cs sel))

(defn mount-selector!
  "Replace CS's editor dock top with SEL, recording it on SEL-ATOM so
   close-selector! can dispose it. dock/mount! disposes the selector it
   displaces; cover-selector! leaves what is below alive instead.

   SEL is recorded BEFORE the dock mutation: the dock publishes the stack
   by swap!, and any watch — or an input handler the render/handle cycle
   runs the moment the stack lands — can close the selector synchronously.
   close-selector! must find it then to release + dispose it (kmetia/
   kmet-agent#5's cover round trip is exactly this shape)."
  [cs sel-atom sel]
  (install-selector! dock/mount! cs sel-atom sel))

(defn cover-selector!
  "Push SEL on top of CS's editor dock — the auth method selector over its
   login dialog (pi showAuthSelect); closing reveals the covered surface
   again. Records SEL on SEL-ATOM so close-selector! can dispose it.

   As in mount-selector!, SEL is recorded BEFORE the dock mutation: the
   cover publishes the stack and a watch (or the input path) can close the
   selector synchronously, and close-selector! must find it to release +
   dispose — the login flow's finally only releases its dialog."
  [cs sel-atom sel]
  (install-selector! dock/cover! cs sel-atom sel))

(defn close-selector!
  "Leave the dock and dispose the selector recorded on SEL-ATOM: release!
   removes it by identity (wherever it sits) and dispose! checks the
   remove-before-dispose invariant before unwinding its root reaction and
   foreign inputs."
  [cs sel-atom]
  (dock/close-atom! cs sel-atom))
