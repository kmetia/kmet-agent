(ns kmet.ui.tui.plugins-command
  "The `kmet plugins` command's terminal UI: a standalone TuiMainScreen
   hosting the static plugin-composition screen. Command parsing and dispatch
   stay in kmet.app.packages-cli (headless); this front-end namespace owns the
   terminal lifecycle and is injected there as :run-plugins! by the CLI
   composition root (`kmet.app.core`)."
  (:require [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.app.packages :as pkgs]
            [kmet.libs.theme :as theme]
            [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as term]
            [kmet.ui.tui.plugins :as plugins]))

(defn run-plugins!
  "Start the plugin-composition screen and block until it closes. Returns the
   process exit code (0). The starting write scope is project-local whenever a
   `.kmet/` project exists: kmet has no project-trust gate and project files
   load unconditionally, so project-defined rows are shown by default (pi
   starts global because its project mode needs trust). Without a `.kmet/`
   dir it starts global; `-l` forces project-local. The screen re-reads and
   writes the composition layers directly. Escape/ctrl+c close with exit code
   0."
  [{:keys [local]}]
  (let [config (cfg/init!)
        project-mode? (or local (fs/exists? (str (fs/path (fs/cwd) ".kmet"))))
        ;; custom themes for the configured :theme name
        _ (theme/load-theme-paths! (pkgs/resolve-theme-paths))
        terminal (term/create-terminal)
        tui (tui/create-tui terminal)
        ;; theme before the UI starts
        _ (theme/init-theme! (cfg/get-theme-name config))
        screen (plugins/make-plugins-screen
                :write-scope (if project-mode? :project :global)
                :project-mode? project-mode?
                :rows (try (max 10 (term/rows terminal)) (catch Exception _ 24))
                ;; The TUI publishes the live terminal size: Termux resizes
                ;; for the soft keyboard, and the screen re-lays out from
                ;; the size ref and forces a clean repaint on every height
                ;; change.
                :size-ref (tui/tui-terminal-size-ref tui)
                :request-render! (fn [] (tui/tui-request-render tui true))
                :on-close (fn [] (tui/tui-stop tui)))]
    (tui/tui-add-child tui screen)
    (tui/tui-set-focus tui screen)
    ;; the screen owns the terminal-size watch cancel; its dispose must run
    ;; even when tui-start throws, or the cancel is lost with the screen
    (try
      (tui/tui-start tui)
      (finally
        (tui/tui-stop tui)
        (protocols/dispose screen)))
    0))
