(ns kmet.package-manager
  "CLI entry for the package subcommands (pi: package-manager-cli.ts —
   `pi install/remove/uninstall/list/config`, local directory/file sources
   only). Usage and error texts mirror pi's with the kmet naming; exit code
   1 on errors, 0 on success (pi process.exitCode = 1 + `true` handling).
   kmet has no project-trust model, so -l/--local always works and the
   -a/--approve and -na/--no-approve flags parse as no-ops (they exist for
   pi CLI parity; project files load unconditionally, see pi-alignment.md)."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.packages :as pkgs]
            [kmet.app.ui.resource-config :as resource-config]
            [kmet.config :as cfg]
            [kmet.tui.core :as tui]
            [kmet.tui.terminal :as term]
            [kmet.tui.theme :as theme]))

;; ─── Parsing (pi: parsePackageCommand — the install/remove/list subset) ───

(def ^:private commands
  "Recognized first-argument commands (pi adds `update`, which kmet does
   not have — no npm/git installs and no self-update)."
  #{"install" "remove" "uninstall" "list" "config"})

(defn package-command?
  "True when ARGS starts with a package subcommand (pi main.ts checks the
   package commands before generic arg parsing)."
  [args]
  (contains? commands (first args)))

(defn- usage-for
  "pi getPackageCommandUsage."
  [command]
  (case command
    "install" "kmet install <source> [-l] [-a|--approve|--no-approve]"
    "remove" "kmet remove <source> [-l] [-a|--approve|--no-approve]"
    "list" "kmet list [-a|--approve|--no-approve]"))

(defn- print-command-help
  "pi printPackageCommandHelp."
  [command]
  (println "Usage:")
  (println (str "  " (usage-for command)))
  (println)
  (case command
    "install"
    (do (println "Install a package and add it to settings.")
        (println)
        (println "Options:")
        (println "  -l, --local       Install project-locally (.kmet/settings.edn)")
        (println "  -a, --approve     Trust project-local files for this command")
        (println "  -na, --no-approve Ignore project-local files for this command")
        (println)
        (println "Examples:")
        (println "  kmet install ./local/path")
        (println "  kmet install /absolute/path/to/package")
        (println "  kmet install ~/packages/my-extension")
        (println)
        (println "Sources are local files or directories (files load as a single")
        (println "extension; directories load extensions, skills, prompts and themes")
        (println "from their conventional subdirectories).")
        (println))
    "remove"
    (do (println "Remove a package and its source from settings.")
        (println "Alias: kmet uninstall <source> [-l]")
        (println)
        (println "Options:")
        (println "  -l, --local       Remove from project settings (.kmet/settings.edn)")
        (println "  -a, --approve     Trust project-local files for this command")
        (println "  -na, --no-approve Ignore project-local files for this command")
        (println)
        (println "Examples:")
        (println "  kmet remove ./local/path")
        (println "  kmet uninstall ./local/path"))
    "list"
    (do (println "List installed packages from user and project settings.")
        (println)
        (println "Options:")
        (println "  -a, --approve      Trust project-local files for this command")
        (println "  -na, --no-approve  Ignore project-local files for this command"))))

(defn- print-config-help
  "pi printConfigCommandHelp."
  []
  (println "Usage:")
  (println "  kmet config [-l] [-a|--approve|--no-approve]")
  (println)
  (println "Open the resource configuration TUI to enable or disable resources")
  (println "(packages, top-level settings entries and the auto dirs).")
  (println "Without -l, starts in global settings (~/.kmet/agent/settings.edn).")
  (println "Press Tab in the TUI to switch between global and project-local modes.")
  (println)
  (println "Options:")
  (println "  -l, --local       Edit project overrides (.kmet/settings.edn)")
  (println "  -a, --approve     Trust project-local files for this command with -l")
  (println "  -na, --no-approve Ignore project-local files for this command with -l"))

(defn- err-unknown-option
  "pi's unknown-option error output; returns the exit code 1 convention."
  [command option]
  (binding [*out* *err*]
    (println (str "Unknown option " option " for \"" command "\"."))
    (println (str "Use \"kmet --help\" or \"" (usage-for command) "\".")))
  1)

(defn- err-usage
  "pi's missing/invalid argument errors."
  [command message]
  (binding [*out* *err*]
    (println message)
    (println (str "Usage: " (usage-for command))))
  1)

(defn- err
  "Print a command error (pi: console.error red `Error: ...`); plain text in
   kmet's colorless CLI."
  [message]
  (binding [*out* *err*]
    (println (str "Error: " message)))
  1)

(defn- parse-command-args
  "Parse the args after the command (pi parsePackageCommand minus update).
   Returns {:command :local :help :invalid-option :invalid-argument
   :source}."
  [command args]
  (loop [args args
         opts {:command command :local false :help false
               :invalid-option nil :invalid-argument nil :source nil}]
    (if-let [arg (first args)]
      (let [rest (rest args)]
        (cond
          (#{"-h" "--help"} arg)
          (recur rest (assoc opts :help true))

          (#{"-l" "--local"} arg)
          (if (contains? #{"install" "remove" "config"} command)
            (recur rest (assoc opts :local true))
            (recur rest (assoc opts :invalid-option (or (:invalid-option opts) arg))))

          (#{"-a" "--approve"} arg)
          ;; project trust is not ported — accepted as a no-op (kmet loads
          ;; project files unconditionally)
          (recur rest opts)

          (#{"-na" "--no-approve"} arg)
          (recur rest opts)

          (str/starts-with? arg "-")
          (recur rest (assoc opts :invalid-option (or (:invalid-option opts) arg)))

          (nil? (:source opts))
          (recur rest (assoc opts :source arg))

          :else
          (recur rest (assoc opts :invalid-argument (or (:invalid-argument opts) arg)))))
      opts)))

;; ─── Command implementations (pi handlePackageCommand) ────────────────────

(defn- run-install
  "pi: installAndPersist — validate the source exists, then persist it to
   the scope's settings :packages; prints `Installed <source>`."
  [{:keys [source local]}]
  (let [parsed (pkgs/parse-source source)]
    (cond
      (= :remote (:kind parsed))
      (err (str "Unsupported package source \"" source "\": kmet installs local "
                "directories and files only (npm/git package installs are not "
                "ported)"))

      :else
      ;; pi install resolves the source against the cwd (the settings base
      ;; dir only defines what a stored relative source means)
      (let [resolved (pkgs/resolve-path (:path parsed) (str (fs/cwd)))]
        (if-not (fs/exists? resolved)
          (err (str "Path does not exist: " resolved))
          (do (pkgs/add-package-to-settings! source {:local local})
              (println (str "Installed " source))
              0))))))

(defn- run-remove
  "pi: removeAndPersist — remove matching entries from the scope's settings
   :packages; prints `Removed <source>` or fails when nothing matched."
  [{:keys [source local]}]
  (if (pkgs/remove-package-from-settings! source {:local local})
    (do (println (str "Removed " source))
        0)
    (do (binding [*out* *err*]
          (println (str "No matching package found for " source)))
        1)))

(defn- print-list
  "pi: the `list` command output — user and project sections, sources (with
   pi's `(filtered)` marker for object entries) and their installed path
   underneath (dim in pi, plain here)."
  []
  (let [configured (pkgs/list-configured-packages)
        user (filter #(= :user (:scope %)) configured)
        project (filter #(= :project (:scope %)) configured)
        format-pkg (fn [pkg]
                     (println (str "  " (:source pkg)
                                   (when (:filtered pkg) " (filtered)")))
                     (when (:installed-path pkg)
                       (println (str "    " (:installed-path pkg)))))]
    (if (empty? configured)
      (println "No packages installed.")
      (do
        (when (seq user)
          (println "User packages:")
          (doseq [pkg user] (format-pkg pkg)))
        (when (seq project)
          (when (seq user) (println))
          (println "Project packages:")
          (doseq [pkg project] (format-pkg pkg))))))
  0)

(defn- run-config
  "pi handleConfigCommand — the resource configuration TUI (pi
   cli/config-selector.ts: a standalone TuiMainScreen hosting the config
   selector). LOCAL selects the starting write scope; the screen re-reads
   and writes the settings files directly. Escape/ctrl+c close with exit
   code 0."
  [{:keys [local]}]
  (let [config (cfg/init!)
        project-mode? (or local (fs/exists? (str (fs/path (fs/cwd) ".kmet"))))
        ;; custom themes for the config's :theme name (pi: initTheme after
        ;; the resource loader)
        _ (pkgs/load-themes!)
        terminal (term/create-terminal)
        tui (tui/create-tui terminal)
        ;; theme before the UI starts (pi: initTheme(settings.getTheme()))
        _ (theme/init-theme! (cfg/get-theme-name config))
        screen (resource-config/make-resource-config-screen
                :write-scope (if local :project :global)
                :project-mode? project-mode?
                :rows (try (max 10 (term/rows terminal)) (catch Exception _ 24))
                :on-close (fn [] (tui/tui-stop tui)))]
    (tui/tui-add-child tui screen)
    (tui/tui-set-focus tui screen)
    (tui/tui-start tui)
    (tui/tui-stop tui)
    0))

(defn run-package-command
  "Run a package subcommand (args includes the command word). Returns the
   process exit code (pi: the CLI prints and sets process.exitCode)."
  [args]
  (let [[raw-command & rest] args
        command (if (= raw-command "uninstall") "remove" raw-command)]
    (cond
      (nil? command) 0
      (= command "config")
      (let [opts (parse-command-args "config" rest)]
        (cond
          (:help opts) (do (print-config-help) 0)
          (:invalid-option opts) (err-unknown-option "config" (:invalid-option opts))
          (:invalid-argument opts) (err-usage "config"
                                              (str "Unexpected argument " (:invalid-argument opts) "."))
          :else (run-config opts)))

      :else
      (let [opts (parse-command-args command rest)]
        (cond
          (:help opts) (do (print-command-help command) 0)
          (:invalid-option opts) (err-unknown-option command (:invalid-option opts))
          (:invalid-argument opts) (err-usage command
                                              (str "Unexpected argument " (:invalid-argument opts) "."))
          (and (#{"install" "remove"} command) (nil? (:source opts)))
          (err-usage command (str "Missing " command " source."))
          (= command "install") (run-install opts)
          (= command "remove") (run-remove opts)
          (= command "list") (print-list)
          :else 0)))))
