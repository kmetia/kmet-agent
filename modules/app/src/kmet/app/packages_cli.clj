(ns kmet.app.packages-cli
  "CLI entry for the package subcommands (pi: package-manager-cli.ts —
   install/remove/uninstall/list/update, plus kmet's `plugins` composition
   editor in place of pi's resource `config`): local directory/file
   sources and git repositories (managed checkouts; npm is not ported, and
   `update` covers git packages only). Usage and error texts mirror pi's
   with the kmet naming; exit code 1 on errors, 0 on success (pi
   process.exitCode = 1 + `true` handling). kmet has no project-trust
   model, so -l/--local always works and the -a/--approve and
   -na/--no-approve flags parse as no-ops (they exist for pi CLI parity;
   project files load unconditionally, see src/kmet/pi-alignment.md)."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.git-packages :as git]
            [kmet.app.packages :as pkgs]))

;; ─── Parsing (pi: parsePackageCommand — the install/remove/list subset) ───

(def ^:private commands
  "Recognized first-argument commands. `plugins` replaces pi's resource
   `config`; kmet's update is git-only and has no self-update."
  #{"install" "remove" "uninstall" "list" "update" "plugins"})

(def ^:private retired-commands
  "Commands retired in favor of a replacement. They stay recognized so the
   CLI reports the replacement instead of treating the word as a prompt; they
   are not aliases."
  {"config" "plugins"})

(defn package-command?
  "True when ARGS starts with a package subcommand (pi main.ts checks the
   package commands before generic arg parsing). A retired command counts too,
   so it reports its replacement instead of falling through to a prompt."
  [args]
  (let [command (first args)]
    (or (contains? commands command)
        (contains? retired-commands command))))

(defn- usage-for
  "pi getPackageCommandUsage."
  [command]
  (case command
    "install" "kmet install <source> [-l] [-a|--approve|--no-approve]"
    "remove" "kmet remove <source> [-l] [-a|--approve|--no-approve]"
    "update" "kmet update [source] [--extensions] [-a|--approve|--no-approve]"
    "list" "kmet list [-a|--approve|--no-approve]"
    "plugins" "kmet plugins [-l] [-a|--approve|--no-approve]"))

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
        (println "  kmet install git:github.com/user/repo")
        (println "  kmet install git:github.com/user/repo@v1.2.0")
        (println)
        (println "Sources:")
        (println "  A local file or directory (a file loads as a single extension;")
        (println "  a directory loads extensions, skills, prompts and themes from")
        (println "  its conventional subdirectories).")
        (println "  A git repository: git:host/path[@ref][//subdir], an https/ssh/git")
        (println "  URL, or any of those behind the git: prefix. Checkouts are managed")
        (println "  under the scope's git/ root; @ref/#ref pins a checkout, and //subdir")
        (println "  names a directory (or a single file) in the repository (one checkout")
        (println "  is shared by every subdirectory package of the repository).")
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
    "update"
    (do (println "Fetch and reset installed git packages to their configured ref")
        (println "(their upstream branch for an unpinned source). With no source,")
        (println "every installed git package is updated.")
        (println)
        (println "kmet cannot self-update; npm packages are not supported.")
        (println)
        (println "Options:")
        (println "  --extensions       Update all installed git packages (the default)")
        (println "  -a, --approve      Trust project-local files for this command")
        (println "  -na, --no-approve  Ignore project-local files for this command")
        (println)
        (println "Examples:")
        (println "  kmet update")
        (println "  kmet update git:github.com/user/repo")
        (println "  kmet update git:github.com/user/repo@v1.2.0"))
    "list"
    (do (println "List installed packages from user and project settings.")
        (println)
        (println "Options:")
        (println "  -a, --approve      Trust project-local files for this command")
        (println "  -na, --no-approve  Ignore project-local files for this command"))))

(defn- print-plugins-help
  "The `plugins` subcommand's help."
  []
  (println "Usage:")
  (println "  kmet plugins [-l] [-a|--approve|--no-approve]")
  (println)
  (println "Open the composition TUI to compose bundled and installed extension")
  (println "plugin rows. Writes :ext/… rows and remove patches to")
  (println "~/.kmet/agent/patch.edn or .kmet/patch.edn. It loads no extension code")
  (println "and never edits settings or :resources. Starts in project-local mode")
  (println "when .kmet/ exists, global otherwise; Tab switches between the two scopes.")
  (println)
  (println "Options:")
  (println "  -l, --local       Edit the project composition layer (.kmet/patch.edn)")
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
  "Parse the args after the command (pi parsePackageCommand). Returns
   {:command :local :extensions :help :invalid-option :invalid-argument
   :unsupported :source}; :unsupported carries a pi update option kmet
   cannot honor (self-update, model refresh, a single-package flag)."
  [command args]
  (loop [args args
         opts {:command command :local false :extensions false :help false
               :invalid-option nil :invalid-argument nil :unsupported nil
               :source nil}]
    (if-let [arg (first args)]
      (let [rest (rest args)]
        (cond
          (#{"-h" "--help"} arg)
          (recur rest (assoc opts :help true))

          (#{"-l" "--local"} arg)
          (if (contains? #{"install" "remove" "plugins"} command)
            (recur rest (assoc opts :local true))
            (recur rest (assoc opts :invalid-option (or (:invalid-option opts) arg))))

          (= "--extensions" arg)
          (if (= "update" command)
            (recur rest (assoc opts :extensions true))
            (recur rest (assoc opts :invalid-option (or (:invalid-option opts) arg))))

          (contains? #{"--self" "--models" "--all" "--force"} arg)
          (if (= "update" command)
            (recur rest (assoc opts :unsupported (or (:unsupported opts) arg)))
            (recur rest (assoc opts :invalid-option (or (:invalid-option opts) arg))))

          (= "--extension" arg)
          (if (= "update" command)
            (let [value (first rest)
                  remaining (if (and value (not (str/starts-with? value "-")))
                              (drop 1 rest)
                              rest)]
              (recur remaining (assoc opts :unsupported (or (:unsupported opts) arg))))
            (recur rest (assoc opts :invalid-option (or (:invalid-option opts) arg))))

          (#{"-a" "--approve" "-na" "--no-approve"} arg)
          ;; project trust is not ported — accepted as a no-op (kmet loads
          ;; project files unconditionally)
          (recur rest opts)

          (str/starts-with? arg "-")
          (recur rest (assoc opts :invalid-option (or (:invalid-option opts) arg)))

          (and (= "update" command) (#{"self" "pi"} arg) (nil? (:source opts)))
          (recur rest (assoc opts :unsupported (or (:unsupported opts) arg)))

          (nil? (:source opts))
          (recur rest (assoc opts :source arg))

          :else
          (recur rest (assoc opts :invalid-argument (or (:invalid-argument opts) arg)))))
      opts)))

(def ^:private update-unsupported
  "Actionable errors for the pi update options kmet cannot honor (no
   self-update, no npm, no model refresh through the package command)."
  {"--self" "kmet does not support self-update. `kmet update` updates installed git packages."
   "self" "kmet does not support self-update. `kmet update` updates installed git packages."
   "pi" "kmet does not support self-update. `kmet update` updates installed git packages."
   "--all" "`--all` includes a self-update, which kmet does not support. `kmet update` updates every installed git package."
   "--models" "kmet refreshes model catalogs with `kmet --generate-models`; `kmet update` updates git packages."
   "--force" "`--force` applies to self-update, which kmet does not support."
   "--extension" "use `kmet update <source>` to update a single package."})

(defn- unsupported-message
  [arg]
  (get update-unsupported arg (str "Unsupported option " arg " for \"update\".")))

(declare no-matching-message)

(defn- configured-packages
  "Every configured package entry across both settings scopes, parsed
   ({:source :scope :parsed})."
  []
  (for [[scope entries] [[:user (pkgs/user-packages)]
                         [:project (pkgs/project-packages)]]
        entry entries
        :let [source (pkgs/source-of entry)]]
    {:source source :scope scope :parsed (pkgs/parse-source source)}))

(defn- local-input-identity
  "The identity of a local update input: its path resolved against the cwd
   (a configured entry resolves against its own scope base —
   kmet.app.packages)."
  [source]
  (str "local:" (pkgs/resolve-path (:path (pkgs/parse-source source)) (str (fs/cwd)))))

(defn- checkout-of
  "The managed checkout path of a configured git package ENTRY
   ({:parsed :scope})."
  [{:keys [parsed scope]}]
  (git/install-path (pkgs/git-install-root scope) (:git parsed)))

(defn- checkout-targets
  "MATCHED git entries deduped by their managed checkout — one target per
   checkout, because `//subdir` packages of one repository share it.
   Insertion order is kept (a hash-map reduce would not)."
  [matched]
  (loop [entries (seq matched)
         seen #{}
         out []]
    (if-let [entry (first entries)]
      (let [checkout (checkout-of entry)]
        (if (contains? seen checkout)
          (recur (next entries) seen out)
          (recur (next entries) (conj seen checkout) (conj out entry))))
      out)))

(defn- checkout-used?
  "True when any configured package in SCOPE's settings uses the same git
   checkout as the parsed git SOURCE. Consulted by remove after the entry is
   gone from settings (a `//subdir` sibling keeps the checkout) and by
   install before the entry is added (a failed subdirectory check must not
   delete a checkout a sibling already uses)."
  [source scope]
  (let [repo-id (git/repo-identity source)
        entries (if (= scope :project) (pkgs/project-packages) (pkgs/user-packages))]
    (boolean
     (some (fn [entry]
             (let [parsed (pkgs/parse-source (pkgs/source-of entry))]
               (and (= :git (:kind parsed))
                    (= repo-id (git/repo-identity (:git parsed))))))
           entries))))

(defn- run-update
  "pi: update's git half — update the git package named by SOURCE, or every
   configured git package. A git input matches configured entries by
   repository (host/path), so any URL form or ref of the repository — with
   or without a `//subdir` — targets the one shared checkout; a local input
   matches by resolved path (pi's identity rule). A pinned source is reset
   to its configured ref, an unpinned one to its upstream/default branch
   (kmet.app.git-packages). A matched local package has nothing to
   update; npm sources are not supported."
  [{:keys [source]}]
  (if source
    (let [parsed (pkgs/parse-source source)]
      (case (:kind parsed)
        :remote
        (err (str "Unsupported package source \"" source "\": kmet updates "
                  "git packages only (npm installs are not ported)"))

        :git
        (let [configured (configured-packages)
              repo-id (git/repo-identity (:git parsed))
              matched (filter (fn [{:keys [parsed]}]
                                (and (= :git (:kind parsed))
                                     (= repo-id (git/repo-identity (:git parsed)))))
                              configured)]
          (if (empty? matched)
            (err (no-matching-message source configured))
            (do (doseq [{:keys [source scope parsed]} (checkout-targets matched)]
                  (println (str "Updating " source "..."))
                  (git/update! (:git parsed) (pkgs/git-install-root scope)))
                (println (str "Updated " source))
                0)))

        :local
        (let [configured (configured-packages)
              identity (local-input-identity source)]
          (if (some #(= identity (pkgs/package-identity (:source %) (:scope %)))
                    configured)
            (do (println (str "Local packages load from disk; nothing to update: " source))
                0)
            (err (no-matching-message source configured))))))

    (let [git-targets (checkout-targets
                       (filter #(= :git (:kind (:parsed %))) (configured-packages)))]
      (if (empty? git-targets)
        (do (println "No git packages installed.")
            0)
        (do (doseq [{:keys [source scope parsed]} git-targets]
              (println (str "Updating " source "..."))
              (git/update! (:git parsed) (pkgs/git-install-root scope)))
            (println "Updated packages")
            0)))))

(defn- no-matching-message
  "pi buildNoMatchingPackageMessage — with the shorthand suggestion when a
   configured git package's host/path names SOURCE (e.g. the input omitted
   the git: prefix)."
  [source packages]
  (let [trimmed (str/trim (str source))
        suggestion (some (fn [{:keys [source parsed]}]
                           (when (= :git (:kind parsed))
                             (let [g (:git parsed)
                                   shorthand (str (:host g) "/" (:path g))
                                   with-ref (when (:ref g) (str shorthand "@" (:ref g)))]
                               (when (or (= trimmed shorthand) (= trimmed with-ref))
                                 source))))
                         packages)]
    (if suggestion
      (str "No matching package found for " source ". Did you mean " suggestion "?")
      (str "No matching package found for " source))))

;; ─── Command implementations (pi handlePackageCommand) ────────────────────

(defn- run-install
  "pi: installAndPersist — clone a git source into its managed checkout or
   validate a local source exists, then persist the source to the scope's
   settings :packages; prints `Installed <source>`."
  [{:keys [source local]}]
  (let [parsed (pkgs/parse-source source)]
    (case (:kind parsed)
      :local
      ;; pi install resolves the source against the cwd (the settings base
      ;; dir only defines what a stored relative source means)
      (let [resolved (pkgs/resolve-path (:path parsed) (str (fs/cwd)))]
        (if-not (fs/exists? resolved)
          (err (str "Path does not exist: " resolved))
          (do (pkgs/add-package-to-settings! source {:local local})
              (println (str "Installed " source))
              0)))

      :git
      (let [scope (if local :project :user)
            root (pkgs/git-install-root scope)
            checkout (git/install-path root (:git parsed))
            ;; a checkout we clone during this install and that no sibling
            ;; package uses may be removed again when the dir check fails —
            ;; one that already existed (or is shared) is left alone
            fresh? (not (fs/exists? checkout))]
        (try
          (println (str "Installing " source "..."))
          (git/install! (:git parsed) root)
          (if (git/installed? (:git parsed) root)
            (do (pkgs/add-package-to-settings! source {:local local})
                (println (str "Installed " source))
                0)
            (do (when (and fresh? (not (checkout-used? (:git parsed) scope)))
                  (git/remove! (:git parsed) root))
                (err (git/package-dir-error (:git parsed) root))))
          (catch Exception e
            (err (ex-message e)))))

      (err (str "Unsupported package source \"" source "\": kmet installs local "
                "directories/files and git: repositories (npm installs are not "
                "ported)")))))

(defn- run-remove
  "pi: removeAndPersist — remove matching entries from the scope's settings
   :packages, deleting a git source's managed checkout when no other package
   in that scope still uses it; prints `Removed <source>` or fails when
   nothing matched."
  [{:keys [source local]}]
  (if (pkgs/remove-package-from-settings! source {:local local})
    (do (let [parsed (pkgs/parse-source source)
              scope (if local :project :user)]
          (when (and (= :git (:kind parsed))
                     (not (checkout-used? (:git parsed) scope)))
            (git/remove! (:git parsed) (pkgs/git-install-root scope))))
        (println (str "Removed " source))
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

(defn run-package-command
  "Run a package subcommand (args includes the command word). OPTS carries
   :run-plugins!, the `plugins` subcommand's terminal UI injected by the CLI
   composition root (kmet.ui.tui.plugins-command/run-plugins!) — the package
   manager itself stays headless. Returns the process exit code
   (pi: the CLI prints and sets process.exitCode; a command error prints
   `Error: …` and returns 1 like pi's catch-all)."
  ([args] (run-package-command args nil))
  ([args {:keys [run-plugins!]}]
   (let [[raw-command & rest] args
         command (if (= raw-command "uninstall") "remove" raw-command)]
     (try
       (cond
         (nil? command) 0
         (contains? retired-commands command)
         (err (str "`kmet " command "` is retired; use `kmet "
                   (get retired-commands command) "`"))
         (= command "plugins")
         (let [opts (parse-command-args "plugins" rest)]
           (cond
             (:help opts) (do (print-plugins-help) 0)
             (:invalid-option opts) (err-unknown-option "plugins" (:invalid-option opts))
             (:invalid-argument opts) (err-usage "plugins"
                                                 (str "Unexpected argument " (:invalid-argument opts) "."))
             :else (if run-plugins!
                     (run-plugins! opts)
                     (err "plugins requires a terminal UI"))))

         :else
         (let [opts (parse-command-args command rest)]
           (cond
             (:help opts) (do (print-command-help command) 0)
             (:unsupported opts) (err (unsupported-message (:unsupported opts)))
             (:invalid-option opts) (err-unknown-option command (:invalid-option opts))
             (:invalid-argument opts) (err-usage command
                                                 (str "Unexpected argument " (:invalid-argument opts) "."))
             (and (#{"install" "remove"} command) (nil? (:source opts)))
             (err-usage command (str "Missing " command " source."))
             (= command "install") (run-install opts)
             (= command "remove") (run-remove opts)
             (= command "update") (run-update opts)
             (= command "list") (print-list)
             :else 0)))
       (catch Exception e
         (err (ex-message e)))))))
