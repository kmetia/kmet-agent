(ns kmet.app.core
  "The process entry point (`bb start`, `jolt start`, the packaged
   artifacts' Main-Class).

   The app resolves a profile's rows, patches them by row id and prints the
   result (`--dump-config`); a run without `--dump-config` composes the same
   rows and **boots** them (`kmet.app.runtime`), which is what proves a profile
   mounts. Two ambient layers apply to every profile — `~/.kmet/agent/patch.edn`
   then `.kmet/patch.edn` — and `--dump-default-config` prints the built-in
   table without them. `--dump-state` is the other half of an inspection: it
   boots the same rows and prints the ctx tree the boot produced
   (`kmet.app.inspect`), which is the one question a composition listing cannot
   answer — what is `:pending` and on what, what failed and with which error.

   A profile with a **mode row** runs it: the row is marked `:mode true`, and
   the entry point boots the composition with that row and the process world
   (`:world`, marked `:defer`) held back, mounts the world with the invocation,
   then mounts the mode with the invocation's input (the prompt, where it
   reports its exit, and the world it loaded). The mode owns its output and runs
   on its own thread; `main` waits for the exit and disposes the composition it
   booted. A mode mounts last because its `serve` starts the run, and the run
   must see the extensions and themes the world load installs. Print mode is the
   first (`kmet.app.modes.print`); a profile without a mode row still boots,
   reports what it mounted, and exits, which is what proves the rest of the rows
   mount — and loads no world, because a boot with nothing to run has nothing to
   read the machine's settings for.

   Three flags are maintenance rather than a run, and their order is part of
   their contract: `--debug` before any load (so the catalog trail is in the
   log), `--generate-models` before the process world (the run reads the caches
   it wrote), and `--list-models [filter]` after it (extension providers are
   catalog entries only then) — all three in `kmet.app.maintenance`.

   Package subcommands (`install`, `remove`, `update`, `list`, `config`) are
   commands rather than profiles: they run the same package CLI kmet1 uses
   (`kmet.app.packages-cli`) before the app's own flags, and the config
   command's terminal UI is resolved when it runs, so a session never pays for
   the TUI it does not start.

   Output and exit codes are separable on purpose: `main` returns the exit
   code and `-main` is the only form that calls `System/exit`, so tests read a
   command's output and status without forking a process."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kmet.app.config :as cfg]
            [kmet.app.debug :as debug]
            [kmet.app.packages-cli :as packages-cli]
            [kmet.app.maintenance :as maintenance]
            [kmet.app.version :as version]
            [kmet.app.catalog :as catalog]
            [kmet.app.inspect :as inspect]
            [kmet.app.invocation :as invocation]
            [kmet.app.profile :as profile]
            [kmet.app.runtime :as runtime]
            [kmet.srce.core :as srce]))

;; ── argument parsing ──────────────────────────────────────────────────────

(def ^:private bool-flags
  {"--version" :version?
   "--help" :help?
   "-h" :help?
   "--dump-config" :dump-config?
   "--dump-default-config" :dump-default-config?
   "--dump-state" :dump-state?
   ;; kmet1's session-lifecycle switches. They name which session a run opens
   ;; instead of the new one it would otherwise create; only a mode that opens
   ;; a session itself can honor them (`run-composed` answers for the rest).
   "-c" :continue?
   "--continue" :continue?
   "-r" :resume?
   "--resume" :resume?
   ;; kmet1's maintenance switches: neither runs a mode.
   "-d" :debug
   "--debug" :debug
   "--generate-models" :generate-models})

(def ^:private value-flags
  {"--profile" :profile
   "--mode" :mode
   "--patch" :patch
   "--model" :model
   "--provider" :provider
   "--port" :port
   ;; the server's transport limits (docs/serve.md): the bearer token a client
   ;; authenticates with, a unix-domain socket path, and the three caps
   "--token" :token
   "--socket" :socket
   "--max-sessions" :max-sessions
   "--queue-depth" :queue-depth
   "--idle-timeout" :idle-timeout
   "--session" :session
   ;; kmet1's other run flags. `--model` is a pattern and is resolved against
   ;; the catalog before a run (`kmet.app.invocation`); the rest are config
   ;; overrides the mode applies (`cfg/apply-cli-overrides`).
   "--models" :models
   "-t" :thinking
   "--thinking" :thinking
   "--system-prompt" :system-prompt
   "--append-system-prompt" :append-system-prompt
   "--session-dir" :session-dir})

(def ^:private text-flags
  "Value flags whose value is free text, so the argument after them may start
   with `-`: a prompt override is lines of prose (`--system-prompt \"- be
   concise\"`), and kmet1 reads the token after the flag whichever way it
   starts. Every other value flag keeps the stricter rule, which is what turns
   `--model --thinking` into a usage error instead of a model named
   \"--thinking\"."
  #{"--system-prompt" "--append-system-prompt"})

(def ^:private retired-flags
  "Flags whose meaning moved away, answered by name instead of falling through
   to the extension-flag rule: `--app` selected the app before the one app was
   left alone, and treating it as extension data would turn its value (`new`)
   into the prompt."
  #{"--app"})

(def ^:private print-flags
  "kmet1's one-shot spelling: `-p`/`--print` run the print profile, as `kmet -p
   \"hi\"` does."
  #{"-p" "--print"})

(def ^:private print-formats
  "kmet1's print-mode output values: text (the default) streams the answer, edn
   prints the session header and every event as EDN for a machine to read."
  #{"text" "edn"})

(defn- bad-args [message]
  (ex-info message {:type :kmet.app/bad-args}))

(defn- split-flag
  "ARG split into `[flag value]`; value is ::missing when the form carries no
   `=`."
  [arg]
  (if-let [i (and (str/starts-with? arg "--") (str/index-of arg "="))]
    [(subs arg 0 i) (subs arg (inc i))]
    [arg ::missing]))

(defn- flag-value
  "The value of value flag FLAG from VALUE (the `=` form's) or the next argument
   in IN → `[value remaining]`. Throws :kmet.app/bad-args when the value is
   missing, empty, or looks like the next flag — unless FLAG is free text
   (`text-flags`), where the next argument is the value however it starts: a
   prompt override may legitimately be `- be concise`, and kmet1 reads the
   token after the flag either way."
  [flag value in]
  (let [[value in] (if (= ::missing value)
                     (let [v (second in)]
                       (when (or (nil? v)
                                 (and (not (contains? text-flags flag))
                                      (str/starts-with? v "-")))
                         (throw (bad-args (str flag " needs a value"))))
                       [v (nnext in)])
                     [value (next in)])]
    (when (str/blank? value)
      (throw (bad-args (str flag " needs a value"))))
    [value in]))

(defn- resolve-mode
  "OPTS with kmet1's `--mode` applied: `text` and `edn` are print mode's output
   formats, so they select the print profile with that `:format`; every other
   value names a profile (`--mode rpc` is `--profile rpc`). `--mode` and
   `--profile` choose the same thing, so asking for both is a usage error rather
   than a silent pick."
  [opts]
  (if-let [mode (:mode opts)]
    (let [opts (dissoc opts :mode)]
      (when (:profile opts)
        (throw (bad-args "--mode (or -p) and --profile choose the same thing; pass one")))
      (if (contains? print-formats mode)
        (assoc opts :profile :print :format (keyword mode))
        (assoc opts :profile (keyword mode))))
    opts))

(defn parse-args
  "Parse kmet2's argv → `{:profile kw :patch [path …] :args [rest …] :version?
   :help? :dump-config? :dump-default-config? :dump-state? :format kw :model str
   :provider kw :thinking kw :models [pattern …] :system-prompt str
   :append-system-prompt [str …] :session-dir path :continue? :resume?
   :session str :debug :generate-models :list-models str-or-true
   :token str :socket path :max-sessions long :queue-depth long
   :idle-timeout long :ext-flags {name value-or-true}}`.
   `:profile` defaults to `profile/default-profile`; `--mode` is kmet1's spelling
   of it (`resolve-mode`), and the run flags are kmet1's: `--model`/`--provider`
   are the agent's overrides, `--thinking`/`--system-prompt`/
   `--append-system-prompt`/`--models`/`--session-dir` the run's (resolved and
   then applied by the mode — `kmet.app.invocation`). An `@file` argument is
   read into `:args` as kmet1 reads it. An unsupported `--flag` is an extension
   flag (`:ext-flags`), kmet1's three forms; a single-dash unknown flag stays a
   usage error. The three dumps are different views of one composition, so
   asking for more than one is a usage error rather than a silent pick."
  [args]
  (loop [in (seq args) opts {:patch [] :args []}]
    (if-not in
      (let [opts (-> (resolve-mode opts)
                     (update :profile #(or % profile/default-profile)))]
        (when (> (count (filter opts [:dump-config? :dump-default-config?
                                      :dump-state?]))
                 1)
          (throw (bad-args "--dump-config, --dump-default-config and --dump-state are separate dumps; pass one")))
        opts)
      (let [arg (first in)
            [flag value] (split-flag arg)]
        (cond
          (contains? bool-flags flag)
          (if (= ::missing value)
            (recur (next in) (assoc opts (bool-flags flag) true))
            (throw (bad-args (str flag " takes no value"))))

          ;; kmet1's maintenance flag: the filter is the next argument unless
          ;; it starts with `-` or `@`, and a bare flag lists everything
          (= flag "--list-models")
          (let [[v in] (if (= ::missing value)
                         (let [nxt (second in)]
                           (if (and nxt
                                    (not (str/starts-with? nxt "-"))
                                    (not (str/starts-with? nxt "@")))
                             [nxt (nnext in)]
                             [true (next in)]))
                         [value (next in)])]
            (recur in (assoc opts :list-models v)))

          (contains? value-flags flag)
          (let [k (value-flags flag)
                [v in] (flag-value flag value in)]
            (recur in (case k
                        :patch (update opts :patch conj v)
                        (:profile :provider :thinking) (assoc opts k (keyword v))
                        ;; kmet1's spelling for the scope: one argument split on
                        ;; commas (`--models a,b`), resolved by
                        ;; `invocation/resolve-overrides` before the run
                        :models (assoc opts k (mapv str/trim (str/split v #",")))
                        ;; repeatable; `cfg/get-append-system-prompt` resolves
                        ;; the pieces and joins them like pi
                        :append-system-prompt (update opts k (fnil conj []) v)
                        ;; a socket port and the server's limits are numbers: a
                        ;; value that is not one is a usage error here rather
                        ;; than a boot failure, and a mode that read a nil cap
                        ;; would serve without one
                        (:port :max-sessions :queue-depth :idle-timeout)
                        (assoc opts k (or (parse-long v)
                                          (throw (bad-args (str flag " needs a number, got " (pr-str v))))))
                        (assoc opts k v))))

          (contains? print-flags flag)
          (if (= ::missing value)
            (recur (next in) (assoc opts :mode "text"))
            (throw (bad-args (str flag " takes no value"))))

          (str/starts-with? arg "@")
          ;; kmet1 reads a file into the message at parse time, and kmet2 keeps
          ;; that: the file's text is the message the mode gets, one per @file
          ;; in the order given. A file that cannot be read is a warning and an
          ;; empty message, as in kmet1 — the rest of the invocation still runs
          (let [path (subs arg 1)]
            (if (seq path)
              (let [text (try (slurp path)
                              (catch Exception _
                                (binding [*out* *err*]
                                  (println "Warning: could not read" path))
                                ""))]
                (recur (next in) (update opts :args conj text)))
              (do (binding [*out* *err*] (println "Warning: empty path after @"))
                  (recur (next in) opts))))

          (contains? retired-flags flag)
          (throw (bad-args (str flag " is gone: there is one app now, so nothing "
                                "selects between two — drop the flag")))

          (and (str/starts-with? arg "--")
               (not (str/blank? (subs flag 2))))
          ;; kmet1's extension-flag rule: an unsupported `--flag` is data for
          ;; the extension host, not a usage error — `=` splits, a following
          ;; token that does not start with `-` is the value, and nothing
          ;; following is `true`. The key drops the dashes, as `get-flag` reads
          ;; it.
          (let [name (subs flag 2)]
            (cond
              (not= ::missing value)
              (recur (next in) (assoc-in opts [:ext-flags name] value))

              (and (seq (next in))
                   (not (str/starts-with? (second in) "-")))
              (recur (nnext in) (assoc-in opts [:ext-flags name] (second in)))

              :else
              (recur (next in) (assoc-in opts [:ext-flags name] true))))

          (str/starts-with? arg "-")
          (throw (bad-args (str "unknown option " arg)))

          :else (recur (next in) (update opts :args conj arg)))))))

;; ── --dump-config, --dump-default-config and --dump-state ────────────────

(defn- read-layer
  "Read a composition layer file: a `--patch FILE` or an ambient patch.edn."
  [path]
  (let [layer (try
                (edn/read-string (slurp path))
                (catch Exception e
                  (throw (ex-info (str "cannot read patch file " path ": " (ex-message e))
                                  {:type :kmet.app/bad-patch-file :path path} e))))]
    (if (profile/layer? layer)
      layer
      (throw (ex-info (str "a patch file holds {:rows […] :patch […]}: " path)
                      {:type :kmet.app/bad-patch-file :path path :content layer})))))

(defn compose-config
  "Compose OPTS' profile plus its layers, describing every row through the
   catalog on the way: the built-in table, then the ambient user layers
   (`:user-layer-paths`, resolved by `main`), then the `--patch` files. Returns
   `{:profile kw :rows … :patches n}`; composition errors are `:kmet.app/*`
   ex-infos."
  [opts]
  (let [chosen (:profile opts)
        table (profile/builtin)
        layers (concat (profile/layers table chosen)
                       (map read-layer (:user-layer-paths opts))
                       (map read-layer (:patch opts)))
        composed (profile/compose (map catalog/describe-layer layers))]
    (assoc composed :profile chosen)))

(defn- apply-user-layers
  "OPTS with the ambient layer paths a run applies: the agent dir's patch.edn
   then the project's. `--dump-default-config` asks for the built-in table
   alone, so it drops them and the `--patch` flags."
  [opts]
  (if (:dump-default-config? opts)
    (assoc opts :user-layer-paths [] :patch [])
    (assoc opts :user-layer-paths (cfg/patch-paths))))

(defn- dump-config
  "The `--dump-config` text: the composed rows the boot would mount, without
   booting anything."
  [opts]
  (let [{:keys [rows patches profile]} (compose-config opts)]
    (profile/render profile {:rows rows :patches patches})))

(defn- deferred-note
  "One line naming the rows a dump held back, or `\"\"`. `--dump-state` does
   not mount a `:defer` row — the process world is the entry's to mount, and a
   dump must not read the machine's settings — so the tree is smaller than
   `--dump-config` lists, and this line is why."
  [deferred]
  (if (empty? deferred)
    ""
    (str "deferred: " (str/join " " (map #(pr-str (:id %)) deferred))
         " (a run mounts it with the invocation; a dump does not)\n")))

(defn- dump-state
  "The `--dump-state` text: the booted root's unit tree, and the rows the boot
   held back. The booted view needs a boot, so this is the one flag that mounts
   the composition — and disposes it again, so the host binding and the
   published registry are the ones it found and a dump leaves no tree behind.
   The boot is a diagnostic one: a row that stays `:pending` or ends `:failed`
   is a line in the tree (with what it waits for, or why it failed) instead of a
   boot error, because that tree is the answer to the question the flag asks."
  [opts]
  (let [booted (runtime/boot (compose-config opts) {:diagnose? true})]
    (try
      (str (inspect/units (:ctx booted)) "\n"
           (deferred-note (:deferred booted)))
      (finally
        (runtime/shutdown! booted)))))

;; ── package subcommands ──────────────────────────────────────────────────

(defn run-config
  "The `config` subcommand's terminal UI: the resource-config screen, resolved
   when the command runs. The screen is a front end, not a row, so a process
   that never runs `config` loads no TUI. Returns the screen's exit code."
  [opts]
  ((requiring-resolve 'kmet.ui.tui.config-command/run-config!) opts))

(defn- package-command
  "ARGS start with a package subcommand: the shared CLI runs it, with the
   config screen it cannot require injected."
  [args]
  (packages-cli/run-package-command args {:run-config! run-config}))

;; ── entry ─────────────────────────────────────────────────────────────────

(defn- report [message]
  (binding [*out* *err*] (println "Error:" message) (flush)))

(defn- print-usage []
  (println "Usage: kmet [options] [@files...] [messages...]")
  (println)
  (println "Options:")
  (println "  --profile <name>   Composition profile: tui (default), print, rpc, server
                     (see docs/profiles.md)")
  (println "  -d, --debug        Log diagnostics to logs/kmet-debug.log")
  (println "  --list-models [search]  List available models (optional fuzzy search)")
  (println "  --generate-models  Fetch provider and image-model catalogs into ~/.kmet/agent/ and exit")
  (println "  -p, --print        Run one prompt and exit (--mode text)")
  (println "  --mode <name>      The older spelling of --profile; text|edn run the")
  (println "                     print profile and choose its output")
  (println "  --patch <file>     Apply a composition layer from a file (repeatable)")
  (println "  --model <id>       Model the run's agent uses, over its settings")
  (println "  --provider <name>  Provider the run's agent uses, over its settings")
  (println "  --models <a,b>     Models the session's scope offers (comma separated)")
  (println "  -t, --thinking <level>  Thinking level (off, minimal, low, medium, high, xhigh, max)")
  (println "  --system-prompt <text>  Replace the system prompt (text, or a path to a file)")
  (println "  --append-system-prompt <text>  Append to the system prompt (repeatable)")
  (println "  --port <n>         --profile server's socket (0, the default, asks the OS)")
  (println "  --socket <path>    --profile server on a unix-domain socket where the host serves one")
  (println "  --token <secret>   --profile server's bearer token (generated and announced when absent)")
  (println "  --max-sessions <n> --profile server's connection cap (default 8, 0 = no cap)")
  (println "  --queue-depth <n>  --profile server's records buffered per connection (default 256)")
  (println "  --idle-timeout <s> --profile server releases a connection silent this long (default 1800, 0 = never)")
  (println "  -c, --continue     Continue this directory's last session (the TUI)")
  (println "  -r, --resume       Pick a session to resume (the TUI)")
  (println "  --session <id>     Open a session by id, prefix or path (the TUI)")
  (println "  --session-dir <dir> Session storage directory")
  (println "  --dump-config      Print the composed plugin rows and exit")
  (println "  --dump-default-config")
  (println "                     Print the built-in composition without user layers or --patch")
  (println "  --dump-state       Boot the composition, print the ctx tree, and exit")
  (println "  --version          Print the version and exit")
  (println "  -h, --help         Show this help")
  (println)
  (println "Package subcommands:")
  (println "  install <source>   Add a package (local dir/file or git: repo) to settings")
  (println "  remove <source>    Remove a package (alias: uninstall)")
  (println "  update [source]    Update installed git packages (all, or one source)")
  (println "  list               List installed packages")
  (println "  config             Compose extensions / toggle resources (TUI)")
  (println)
  (println "  kmet --profile print \"list the files\"   run one prompt and exit"))

(defn- not-wired
  "What a profile without a mode row says: which profile it was, and which ones
   do run. Every built-in profile has a mode row now (the TUI — kmet1's
   interactive mode as a row — print, rpc and the server), so this is the answer
   for a composition that patched one out: a profile is a set of rows, and a set
   with no mode mounts, reports, and runs nothing."
  [profile args]
  (binding [*out* *err*]
    (println (str "profile " (name profile) " has no runnable mode — no row is marked :mode"))
    (println "Profiles that run today:")
    (println "  kmet                                    the TUI")
    (println "  kmet --profile print \"list the files\"   one prompt, then exit")
    (println "  kmet --profile rpc                       a command stream on stdin/stdout")
    (println "  kmet --profile server [--port n]         the same protocol on a socket, per connection")
    (println "  kmet --dump-config / --dump-default-config / --dump-state / --version")
    (when (seq args) (println "Arguments:" (pr-str (vec args))))
    (flush))
  1)

(defn- boot-summary
  "One line naming what the boot mounted: the rows, their fibers, the rows it
   held back (a `:defer` row is composed, but only its owner mounts it), and the
   service names they provide."
  [{:keys [fibers rows deferred]}]
  (let [services (->> fibers (mapcat (comp :provides deref)) distinct sort (map name))
        held (when (seq deferred)
               (str (count deferred) " deferred: "
                    (str/join " " (map #(pr-str (:id %)) deferred)) ", "))]
    (str "booted " rows " plugin row" (when (not= 1 rows) "s")
         " (" (count fibers) " fiber" (when (not= 1 (count fibers)) "s")
         ", " (or held "")
         "services: " (if (seq services) (str/join " " services) "none") ")")))

(defn- mode-row
  "The composed row a profile marked as its mode, or nil."
  [rows]
  (some #(when (:mode %) %) rows))

(defn- mode-input
  "What the command line asks a mode for: the prompt and where to report, plus
   what the mode hands its run — the output `:format`, the agent's
   `:model`/`:provider`/`:thinking` overrides, the scope it offers
   (`:models`), its prompt and session-dir overrides (`:system-prompt`,
   `:append-system-prompt`, `:session-dir`), the socket `:port`, the server's
   transport and limits (`:socket`, `:token`, `:max-sessions`, `:queue-depth`,
   `:idle-timeout`), the session the run opens (`:session`, `:resume`,
   `:continue`, in kmet1's spelling because the TUI row hands them to kmet1's
   `interactive/run`), and the process world's loaded config (`:config`, which
   the TUI drives `interactive/run` with). A value the invocation did not give
   stays absent (`select-keys`), so a mode sees what was asked for rather than a
   map of every option it could have been told."
  [opts config prompt on-result]
  (cond-> (assoc (select-keys opts [:format :model :provider :thinking :models
                                    :system-prompt :append-system-prompt
                                    :session-dir :port :socket :token
                                    :max-sessions :queue-depth :idle-timeout
                                    :session])
                 :on-result on-result
                 ;; the process world's loaded config: the TUI runs on it, and a
                 ;; mode that reads settings itself ignores the key
                 :config config)
    (:continue? opts) (assoc :continue true)
    (:resume? opts) (assoc :resume true)
    ;; a profile with no prompt to give hands the mode no prompt at all
    (not (str/blank? prompt)) (assoc :prompt prompt)))

(defn- world-config
  "The config the composition's `:world` row loaded, or a configuration error:
   a run on a composition that patched the row out has no provider catalogs,
   settings or themes to stand on, and starting it anyway would fail at the
   first model request with nothing to say."
  [ctx]
  (if-let [loaded (srce/get ctx :world)]
    (:config loaded)
    (throw (ex-info (str "the composition mounts no :world row — a run loads its"
                         " provider catalogs, settings and themes through it"
                         " ({:id :world :plugin kmet.app.world/plugin}), and a"
                         " patch removed this one")
                    {:type :kmet.app/no-world}))))

(defn- run-mode
  "Boot COMPOSED's rows, load the process world through the deferred `:world`
   row, then mount MODE — the row `mode-row` found — with the invocation's
   input, wait for it to report, and return its exit code.

   Three mounts, in this order, and each step is a contract: the composition's
   rows (with the world and the mode held back), the world — `world/load!`
   installs the provider catalogs, settings and themes a run stands on, and the
   row provides what it loaded as `:world` — and then the mode, whose `serve`
   starts the run, so the run must see the world the load installed. The model
   patterns resolve after the load for the same reason: an extension provider
   is a catalog entry only then. `--list-models` returns after the load and
   before the mode mount, so MODE may be nil (a profile that patched its mode
   row out). The mode owns what it prints; the entry point owns the process,
   the boot, the load and the teardown."
  [composed mode opts prompt]
  (let [result (promise)
        ;; the mode is the boot's second deferral, whatever else a row declared:
        ;; a `:defer` row is still deferred, and the mode is what phase 2 mounts
        mode? #(= (:id mode) (:id %))
        booted (runtime/boot composed {:defer mode?})]
    (try
      (let [booted (runtime/mount-deferred!
                    booted
                    (complement mode?)
                    (fn [row] (update row :config merge {:invocation opts})))
            config (world-config (:ctx booted))
            opts (invocation/resolve-overrides opts)]
        (if (:list-models opts)
          ;; after the load: the table includes the providers the catalogs and
          ;; the extensions installed, which is kmet1's order too
          (do (maintenance/list-models (when (string? (:list-models opts))
                                         (:list-models opts)))
              0)
          (let [input (mode-input opts config prompt #(deliver result %))]
            (runtime/mount-deferred! booted mode?
                                     (fn [row] (update row :config merge input)))
            (:exit (deref result) 1))))
      (finally
        (runtime/shutdown! booted)))))

(defn- no-prompt
  "The usage error of a mode that takes the command line as its input
   (`:prompt true` on its row) and was given none: named before anything boots."
  [profile]
  (binding [*out* *err*]
    (println (str "No message provided. Usage: kmet --profile "
                  (name profile) " \"your message\""))
    (flush))
  1)

(defn- boot-and-report
  "The path a profile without a mode row takes: boot the composition a run would
   mount, report what it mounted, dispose it, and say that this profile has no
   runnable mode yet. The boot is the point — it is what proves a profile's rows
   actually mount, without a turn to run.

   The process world stays deferred: a boot with nothing to run has nothing to
   read the machine's settings for, and the report names the row it held back."
  [composed profile args]
  (let [booted (runtime/boot composed)]
    (try
      (binding [*out* *err*] (println (boot-summary booted)) (flush))
      (finally
        (runtime/shutdown! booted)))
    (not-wired profile args)))

(defn- session-flag
  "The session-lifecycle flag OPTS asked for, in its long spelling, or nil.

   `--session` wins, as it does in `interactive/run`'s own `cond`: the two
   switches are answering the same question, and the explicit id is the more
   specific answer."
  [opts]
  (cond
    (:session opts) "--session"
    (:resume? opts) "--resume"
    (:continue? opts) "--continue"))

(defn- no-session-lifecycle
  "The usage error of a mode that does not open its own session, given the flag
   that asked: named after composition, before anything boots. Print, rpc and
   the server create their agent through `:agents`, whose factory takes no
   session option yet, so accepting the flag there
   would silently start the fresh session the flag exists to prevent."
  [profile flag]
  (binding [*out* *err*]
    (println (str flag " needs a mode that opens a session; profile "
                  (name profile) " creates its own, so it has none to continue"))
    (println "  kmet -c   continues this directory's last session in the TUI")
    (flush))
  1)

(defn- run-composed
  "A run with neither `--dump-…` flag: the profile's mode, when it has one, else
   the boot report. A mode's model patterns resolve against the catalog inside
   the run (after the process world loads, so extension providers are real
   catalog entries); the invocation's other checks happen here, before anything
   boots. A mode honors the session-lifecycle flags when its row says it owns
   the session (`:session-lifecycle`); the rest refuse them by name."
  [opts args]
  (let [composed (compose-config opts)
        mode (mode-row (:rows composed))
        prompt (str/join " " args)]
    (cond
      ;; the maintenance flag is the entry's: it needs the loaded world
      ;; (extension providers) and no mode, and a print profile without a
      ;; prompt — or a composition with no mode row at all — is a legal
      ;; invocation for it
      (:list-models opts) (run-mode composed mode opts prompt)

      (nil? mode) (boot-and-report composed (:profile opts) args)

      :else
      (let [flag (session-flag opts)]
        (cond
          (and flag (not (:session-lifecycle mode)))
          (no-session-lifecycle (:profile opts) flag)

          (and (:prompt mode) (str/blank? prompt))
          (no-prompt (:profile opts))

          :else (run-mode composed mode opts prompt))))))

(defn- configuration-error? [e]
  (str/starts-with? (str (:type (ex-data e))) ":kmet."))

(defn- enable-debug!
  "Turn on kmet-debug.log before any load of any kind, so the catalog trail is in
   the log too (kmet1's order: help, version and the cache pipeline answer
   without it)."
  [opts]
  (when (:debug opts)
    (debug/enable!)
    (debug/log "kmet started with --debug")))

(defn main
  "Run kmet2 with ARGS and return the process exit code. A package subcommand
   runs before the app's flags do, as kmet1 dispatches it."
  [args]
  (try
    (if (packages-cli/package-command? args)
      (package-command args)
      (let [{:keys [version? help? dump-config? dump-default-config? dump-state?]
             leftover :args :as opts} (parse-args args)]
        (cond
          version? (do (println (str "kmet " (version/kmet-version))) (flush) 0)
          help? (do (print-usage) (flush) 0)
          ;; the model-cache pipeline needs no registry, config or world — and
          ;; the run afterwards reads the caches it just wrote
          (:generate-models opts) (maintenance/generate-models!)
          :else
          (do
            (enable-debug! opts)
            (let [opts (apply-user-layers opts)]
              (cond
                (or dump-config? dump-default-config?)
                (do (print (dump-config opts)) (flush) 0)

                dump-state? (do (print (dump-state opts)) (flush) 0)
                :else (run-composed opts leftover)))))))
    (catch Exception e
      (cond
        (= :kmet.app/bad-args (:type (ex-data e)))
        (do (report (ex-message e)) (print-usage) 1)

        (configuration-error? e)
        (do (report (ex-message e)) 1)

        :else (throw e)))))

(defn run-process
  "What a process start does before it exits: ARGS through `main`; `-main` adds
   the exit code, so a test drives the entry without forking.

   The model catalog is not loaded here — it is part of the process world,
   which `run-mode` mounts as the `:world` row after the composition's other
   rows are up. The maintenance flags follow that split: `-d` and
   `--generate-models` before the load, `--list-models` after it."
  [args]
  (main args))

(defn -main
  "Process entry: the CLI's exit code becomes the process's."
  [& args]
  (System/exit (run-process args)))
