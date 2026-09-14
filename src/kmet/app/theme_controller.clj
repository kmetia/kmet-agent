(ns kmet.app.theme-controller
  "Theme controller (pi: InteractiveThemeController) — terminal-theme
   detection, auto light/dark sync via color-scheme notifications, and
   theme switching. Every applied change re-themes the app components
   (via the on-changed callback) and invalidates the TUI."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.tui.theme :as theme]))

(defrecord ThemeController [ui config-atom show-error on-changed
                            terminal-theme-atom active-theme-name-atom
                            auto-sync-enabled-atom])

(defn- notify-changed!
  "Re-theme the app: invalidate every component, then tell the owner.
   A theme change re-renders every message, so the first changed line is the
   header — above the window — and an ordinary diff would clamp it: the
   visible window repaints, but the scrolled-off scrollback keeps the OLD
   theme until the next heal. Force the clearing full redraw so the rebuild
   is immediate (this is an explicit change, not an automatic above-window
   update, so it may emit the scrollback clear; see tui.md §1)."
  [ctrl]
  (tui/tui-invalidate (:ui ctrl))
  ((:on-changed ctrl))
  (tui/tui-request-render (:ui ctrl) true))

(defn- apply-theme-name!
  "pi: applyThemeName — set the theme (with the file watcher), track the
   active name, notify. SHOW-ERROR? reports failures through the error
   callback.

   A re-application of the theme that is already current is a no-op: the
   notify forces a clearing full redraw (notify-changed!), and every startup
   runs apply-from-settings!, which resolves the same theme the constructor
   already applied — without this guard the resumed transcript is painted
   once and then thrown away and re-emitted (plus a scrollback clear) by a
   theme that never changed. A same-name call still syncs the active-name
   atom: auto-sync's scheme-report guard compares against it."
  [ctrl theme-name show-error?]
  ;; the detection path passes :dark/:light keywords; elsewhere the name is
  ;; already a string — normalize so the comparison and the stored active
  ;; name are both the bare theme name (:dark would str to ":dark")
  (let [theme-name (if (keyword? theme-name) (name theme-name) (str theme-name))]
    (if (= theme-name (theme/get-current-theme-name))
      (do (reset! (:active-theme-name-atom ctrl) theme-name)
          {:success true})
      (let [result (theme/set-theme! theme-name true)]
        (reset! (:active-theme-name-atom ctrl) (if (:success result)
                                                 theme-name
                                                 "dark"))
        (notify-changed! ctrl)
        (when (and (not (:success result)) show-error?)
          ((:show-error ctrl)
           (str "Failed to load theme \"" theme-name "\": " (:error result)
                "\nFell back to dark theme.")))
        result))))

(defn- set-auto-sync!
  "pi: setAutoSync — enable/disable unsolicited terminal color scheme
   reports (CSI ? 2031 h/l)."
  [ctrl enabled?]
  (when (not= enabled? @(:auto-sync-enabled-atom ctrl))
    (reset! (:auto-sync-enabled-atom ctrl) enabled?)
    (tui/tui-set-terminal-color-scheme-notifications (:ui ctrl) enabled?)))

(defn- detect-terminal-background-theme!
  "pi: detectTerminalBackgroundTheme — OSC 11 background query first, env
   fallback (COLORFGBG)."
  [ctrl timeout-ms]
  (let [rgb (deref (tui/tui-query-terminal-background-color (:ui ctrl)
                                                            :timeout-ms timeout-ms)
                   (+ timeout-ms 100) nil)]
    (if rgb
      {:theme (theme/get-theme-for-rgb-color rgb)
       :source "terminal background"
       :detail (str "OSC 11 background rgb(" (:r rgb) ", " (:g rgb) ", " (:b rgb) ")")
       :confidence :high}
      (theme/detect-terminal-background-from-env))))

(defn- detect-terminal-theme-for-auto!
  "pi: detectTerminalThemeForAuto — color-scheme DSR first, then the
   background fallback."
  [ctrl timeout-ms]
  (or (deref (tui/tui-query-terminal-color-scheme (:ui ctrl)
                                                  :timeout-ms timeout-ms)
             (+ timeout-ms 100) nil)
      (:theme (detect-terminal-background-theme! ctrl timeout-ms))))

(defn apply-terminal-theme!
  "pi: applyTerminalTheme — a color scheme report switches between the auto
   setting's light/dark themes while auto-sync is on."
  [ctrl terminal-theme]
  (when @(:auto-sync-enabled-atom ctrl)
    (reset! (:terminal-theme-atom ctrl) terminal-theme)
    (if-let [auto (theme/parse-auto-theme-setting (:theme @(:config-atom ctrl)))]
      (let [theme-name (if (= terminal-theme :light)
                         (:light-theme auto)
                         (:dark-theme auto))]
        (when (not= theme-name @(:active-theme-name-atom ctrl))
          (apply-theme-name! ctrl theme-name false)))
      (set-auto-sync! ctrl false))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-theme-controller
  "Create the theme controller (pi: InteractiveThemeController). The initial
   theme resolves from the :theme config setting against the env-detected
   terminal theme; the custom-themes dir is registered for the file watcher;
   a terminal color-scheme listener drives auto light/dark sync."
  [ui config show-error on-changed]
  (let [agent-dir (cfg/get-agent-dir)
        terminal-theme (:theme (theme/detect-terminal-background-from-env))
        active (theme/resolve-theme-setting (:theme config) terminal-theme)
        ctrl (map->ThemeController
              {:ui ui
               :config-atom (atom config)
               :show-error show-error
               :on-changed on-changed
               :terminal-theme-atom (atom terminal-theme)
               :active-theme-name-atom (atom active)
               :auto-sync-enabled-atom (atom false)})]
    (theme/set-custom-themes-dir! (str (fs/path agent-dir "themes")))
    (theme/init-theme! active true)
    ;; pi: onThemeChange — the theme file watcher's reloads notify through
    ;; this callback so the UI re-themes on live file edits too
    (theme/on-theme-change #(notify-changed! ctrl))
    (tui/tui-on-terminal-color-scheme-change ui #(apply-terminal-theme! ctrl %))
    ctrl))

;; ─── Public API (pi: InteractiveThemeController methods) ──────────────────

(defn apply-from-settings!
  "pi: applyFromSettings — auto setting (\"light/dark\") → detect the
   terminal theme and sync via notifications; explicit setting → apply;
   no setting → detect from the terminal (OSC 11, then env) and apply.
   Divergence: pi persists a high-confidence detection into settings; kmet's
   settings are read-only EDN, so the detection is applied without saving."
  [ctrl]
  (let [setting (:theme @(:config-atom ctrl))]
    (if-let [auto (theme/parse-auto-theme-setting setting)]
      (let [tt (detect-terminal-theme-for-auto! ctrl 100)]
        (reset! (:terminal-theme-atom ctrl) tt)
        (set-auto-sync! ctrl true)
        (apply-theme-name! ctrl (if (= tt :light)
                                  (:light-theme auto)
                                  (:dark-theme auto))
                           true))
      (do (set-auto-sync! ctrl false)
          (if (and (string? setting) (not (str/includes? setting "/")))
            (apply-theme-name! ctrl setting true)
            (let [detection (detect-terminal-background-theme! ctrl 100)]
              (reset! (:terminal-theme-atom ctrl) (:theme detection))
              (apply-theme-name! ctrl (:theme detection) false)))))))

(defn set-config!
  "Update the controller's config (pi reads settings live; kmet caches the
   config, so /reload must push the new one). Also refreshes the watcher
   dir for the current agent dir."
  [ctrl config]
  (reset! (:config-atom ctrl) config)
  (theme/set-custom-themes-dir! (str (fs/path (cfg/get-agent-dir) "themes"))))

(defn set-theme-name!
  "pi: setThemeName — switch to a named theme, disabling auto-sync.
   SHOW-ERROR? reports failures via the error callback."
  [ctrl theme-name & [show-error?]]
  (set-auto-sync! ctrl false)
  (apply-theme-name! ctrl theme-name (boolean show-error?)))

(defn set-theme-instance!
  "pi: setThemeInstance — switch to an in-memory Theme instance."
  [ctrl theme-instance]
  (set-auto-sync! ctrl false)
  (theme/set-theme-instance! theme-instance)
  (reset! (:active-theme-name-atom ctrl) "<in-memory>")
  (notify-changed! ctrl)
  {:success true})

(defn preview
  "pi: preview — apply a theme setting/name without touching the auto-sync
   state; invalidates and re-renders. Deliberately NOT a forced render: a
   live preview can fire per keystroke, and clearing the scrollback on every
   step would re-emit the whole transcript. A commit that actually switches
   the theme forces via notify-changed!; committing the already-previewed
   (current) theme is a no-op in apply-theme-name! — the preview's partial
   repaint already marked the scrollback dirty and the app's heal rebuilds
   it at the next streaming-free boundary."
  [ctrl setting-or-name]
  (when-let [theme-name (theme/resolve-theme-setting setting-or-name
                                                     @(:terminal-theme-atom ctrl))]
    (when (:success (theme/set-theme! theme-name true))
      (tui/tui-invalidate (:ui ctrl))
      (tui/tui-request-render (:ui ctrl)))))

(defn get-terminal-theme
  "The current terminal theme (:dark/:light) (pi: getTerminalTheme)."
  [ctrl]
  @(:terminal-theme-atom ctrl))

(defn get-active-theme-name
  "The currently applied theme's name (pi: activeThemeName)."
  [ctrl]
  @(:active-theme-name-atom ctrl))
