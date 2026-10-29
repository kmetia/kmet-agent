(ns kmet.app.test-core
  "kmet.app.core — kmet2's entry: argument parsing, the version/help paths,
   --dump-config over the composition (the same composition a boot would
   mount), and --dump-state over the tree that boot produces, including the
   errors an unreadable or malformed layer raises."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [kmet.app.config :as cfg]
            [kmet.app.debug :as debug]
            [kmet.app.version :as version]
            [kmet.ai.models :as models]
            [kmet.app.core :as core]
            [kmet.app.maintenance :as maintenance]
            [kmet.app.test-support :as support]
            [kmet.app.world :as world]))

(deftest a-process-start-runs-the-entry
  ;; The model catalog is not loaded here: it is part of the process world,
  ;; which loads into the composition's registries (kmet.app.test-world pins
  ;; that order). `run-process` is the seam 5n hangs its pre-parse flags on;
  ;; today it is the exit code around `main`.
  (with-redefs [core/main (fn [args] (is (= ["-p" "hi"] args)) 7)]
    (is (= 7 (core/run-process ["-p" "hi"])) "the exit code is main's")))

(def sample-plugin
  "A var-addressable plugin map — the shape a `defplugin` var holds."
  {:name :app/sample :inject {:tools nil} :provide [] :apply (fn [_ _] nil)})

(def failing-plugin
  "A plugin whose apply throws: a row that must not pass for a mount."
  {:name :app/failing :apply (fn [_ _] (throw (ex-info "boom" {})))})

(def pending-plugin
  "A plugin whose dependency nothing provides: a row that renders as `:pending`."
  {:name :app/pending :inject {:nothing nil} :apply (fn [_ _] nil)})

(def probe-mode-input
  "What the probe mode row was given: the entry's `mode-input` map, recorded so
   a test can read the world and the invocation the mode ran on."
  (atom nil))

(def probe-mode
  "A mode row that runs without a terminal or a model: it records the config
   the entry hands it and reports an exit, which is all a mode must do."
  {:name :mode/probe
   :apply (fn [_ config]
            (reset! probe-mode-input config)
            (when-let [report (:on-result config)]
              (future (report {:exit 0})))
            nil)})

(defn- scratch-patch!
  "Write CONTENT to a patch file under target/ (which /tmp-less hosts have)
   and return the path."
  [content]
  (let [path (str "target/test-app-patch-" (System/nanoTime) ".edn")]
    (io/make-parents path)
    (spit path content)
    path))

(defn- error-type [f]
  (try (f) nil (catch Exception e (:type (ex-data e)))))

(use-fixtures :each
  (fn [f]
    ;; Composition tests would read the developer's ambient layers otherwise:
    ;; an agent-dir or project patch.edn would change every row count below.
    (with-redefs [cfg/patch-paths (fn [] [])]
      (f))))

(deftest parse-args-defaults
  (let [opts (core/parse-args [])]
    (is (= :tui (:profile opts)) "the default profile")
    (is (= [] (:patch opts)))
    (is (= [] (:args opts))))
  (is (= ["hello" "world"] (:args (core/parse-args ["hello" "world"])))))

(deftest parse-args-flags
  (is (= :rpc (:profile (core/parse-args ["--profile" "rpc"]))))
  (is (= :print (:profile (core/parse-args ["--profile=print"])))
      "the --flag=value form")
  (is (= ["a.edn" "b.edn"] (:patch (core/parse-args ["--patch" "a.edn"
                                                     "--patch=b.edn"])))
      "patches stack")
  (is (true? (:dump-config? (core/parse-args ["--dump-config"]))))
  (is (true? (:dump-state? (core/parse-args ["--dump-state"]))))
  (is (true? (:version? (core/parse-args ["--version"]))))
  (is (true? (:help? (core/parse-args ["-h"])))))

(deftest parse-args-takes-kmet1s-one-shot-spellings
  (is (= :print (:profile (core/parse-args ["-p" "hi"])))
      "-p is print mode, as in kmet1")
  (is (= :print (:profile (core/parse-args ["--print" "hi"]))))
  (is (= :text (:format (core/parse-args ["-p" "hi"])))
      "and text is what print mode prints by default")
  (is (= {:profile :print :format :text :args ["hi"] :patch []}
         (core/parse-args ["-p" "hi"]))
      "and nothing else is set")
  (is (= :edn (:format (core/parse-args ["--mode" "edn" "hi"])))
      "--mode names print mode's output format")
  (is (= :rpc (:profile (core/parse-args ["--mode" "rpc"])))
      "--mode with any other value names a profile")
  (is (= "deepseek-v4-flash" (:model (core/parse-args ["-p" "--model"
                                                       "deepseek-v4-flash" "hi"]))))
  (is (= :deepseek (:provider (core/parse-args ["-p" "--provider" "deepseek" "hi"])))
      "a provider is a keyword, as settings spell it"))

(deftest parse-args-reads-at-files-into-messages
  ;; kmet1 reads a file into the message at parse time, and the two entries
  ;; must agree: `-p @tasks.md` sends the file's contents, in the argument's
  ;; place. An unreadable file is a warning and an empty message (the rest of
  ;; the invocation still runs), an empty path is skipped — kmet1's three
  ;; outcomes, word for word.
  (let [path (str "target/test-at-file-" (System/nanoTime) ".md")]
    (spit path "from the file")
    (try
      (is (= ["from the file"] (:args (core/parse-args [(str "@" path)]))))
      (is (= ["before" "from the file" "after"]
             (:args (core/parse-args ["before" (str "@" path) "after"])))
          "the text takes the argument's place, in order")
      (finally (io/delete-file path true))))
  (let [err (java.io.StringWriter.)]
    (is (= [] (:args (binding [*err* err] (core/parse-args ["@"]))))
        "an empty path is not a file and not a message")
    (is (str/includes? (str err) "Warning: empty path after @")))
  (let [err (java.io.StringWriter.)]
    (is (= [""] (:args (binding [*err* err] (core/parse-args ["@target/does-not-exist.md"]))))
        "an unreadable file is an empty message, as in kmet1")
    (is (str/includes? (str err) "Warning: could not read target/does-not-exist.md"))))

(deftest parse-args-rejects-bad-flags
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--profile"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--version=1"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--print=1"])))
      "a one-shot flag takes no value either")
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["-x"])))
      "a single-dash unknown flag is a usage error (there are no short ext flags)")
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--app" "new"])))
      "the retired app selector is refused, not read as an extension flag whose
       value becomes the prompt")
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--app=new"])))
      "in either spelling")
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--profile="])))
      "an empty value is no value")
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["-p" "--profile" "print"])))
      "choosing print mode twice, two ways, is a usage error")
  (is (= :kmet.app/bad-args
         (error-type #(core/parse-args ["--dump-config" "--dump-state"])))
      "the two dumps are different views; asking for both is a usage error")
  (is (= :kmet.app/bad-args
         (error-type #(core/parse-args ["--dump-default-config" "--dump-config"])))
      "the built-in dump is a third view; it excludes the other two"))

(deftest the-maintenance-flags-run-where-their-contracts-put-them
  ;; `--debug` before any load, `--generate-models` before the world and without
  ;; it, `--list-models` after it. The order is each flag's contract, so the
  ;; test reads the order rather than a threaded map.
  (let [events (atom [])]
    (with-redefs [debug/enable! (fn [] (swap! events conj :debug))
                  debug/log (fn [& _] nil)
                  world/load! (fn [& _] (swap! events conj :load) {})
                  maintenance/generate-models! (fn [] (swap! events conj :generate) 0)
                  maintenance/list-models (fn [_] (swap! events conj :list))]
      (is (= 0 (:code (support/run-main ["-d" "--generate-models"]))))
      (is (= [:generate] @events)
          "--generate-models exits before the debug flag takes effect, and loads nothing")
      (reset! events [])
      (support/run-main ["-d" "--dump-config"])
      (is (= [:debug] @events) "--debug is on before a dump")
      (reset! events [])
      (support/run-main ["-d" "--profile" "print" "--list-models"])
      (is (= [:debug :load :list] @events)
          "debug first, the process world, then the table"))))

(deftest parse-args-takes-kmet1s-maintenance-flags
  ;; `-d` and `--generate-models` run before the process world, `--list-models`
  ;; after it; the entry owns all three (kmet.app.maintenance).
  (is (true? (:debug (core/parse-args ["-d"]))))
  (is (true? (:debug (core/parse-args ["--debug"]))))
  (is (true? (:generate-models (core/parse-args ["--generate-models"]))))
  (is (true? (:list-models (core/parse-args ["--list-models"]))))
  (is (= "flash" (:list-models (core/parse-args ["--list-models" "flash"])))
      "a following non-flag argument is the filter")
  (is (= "flash" (:list-models (core/parse-args ["--list-models=flash"])))
      "the --flag=value form")
  (is (true? (:list-models (core/parse-args ["--list-models" "--profile" "print"])))
      "a following flag is not a filter")
  (is (= :print (:profile (core/parse-args ["--list-models" "--profile" "print"]))))
  (is (= [] (:args (core/parse-args ["--list-models" "flash"])))
      "the filter does not become a prompt"))

(deftest list-models-works-for-a-composition-with-no-mode
  ;; The table is the entry's, not a mode's: a profile whose mode row is patched
  ;; out still loads the world and lists. The mode-less boot report must not win
  ;; the branch.
  (let [listed (atom false)
        path (scratch-patch! "{:rows [] :patch [{:id :mode-tui :remove true}]}\n")]
    (try
      (with-redefs [world/load! (fn [& _] {})
                    maintenance/list-models (fn [filter]
                                              (reset! listed [true filter]))]
        (let [{:keys [code]} (support/run-main
                              ["--patch" path "--list-models" "flash"])]
          (is (= 0 code))
          (is (= [true "flash"] @listed)
              "the filtered table printed after the load, with no mode to mount")))
      (finally (io/delete-file path true)))))

(deftest the-entry-hands-the-invocation-to-the-world-row
  ;; The world row cannot read the command line: the entry merges the parsed
  ;; invocation into its config (`{:invocation opts}`), which is where the
  ;; extension `--flags` the load installs come from — and the reason the row is
  ;; deferred, since only the entry has them.
  (let [seen (atom nil)]
    (with-redefs [world/load! (fn [opts _ctx] (reset! seen opts) {:model "x"})
                  maintenance/list-models (fn [_] nil)]
      (let [{:keys [code]} (support/run-main ["--list-models" "--probe" "1"])]
        (is (zero? code))
        (is (= {"probe" "1"} (:ext-flags @seen))
            "the parsed extension flags reach the world load")))))

(deftest a-run-refuses-a-composition-with-no-world-row
  ;; The world is a row, so a patch can remove it — and then a run has no
  ;; provider catalogs, settings or themes. The refusal names the row instead of
  ;; failing at the first model request with nothing to say.
  (let [path (scratch-patch! "{:rows [] :patch [{:id :world :remove true}]}\n")]
    (try
      (let [{:keys [code out err]} (support/run-main ["--patch" path "--list-models"])]
        (is (= 1 code))
        (is (= "" out) "nothing runs, so nothing is printed")
        (is (str/includes? err "mounts no :world row")))
      (finally (io/delete-file path true)))))

(deftest the-mode-runs-on-the-world-the-row-loaded
  ;; The mode's input carries the world the world row loaded, not a second load:
  ;; a front end runs on the composition's world. The probe mode records what it
  ;; was handed, so a run is observable without a terminal or a model.
  (let [path (scratch-patch!
              (str "{:rows [{:id :mode-probe :plugin kmet.app.test-core/probe-mode"
                   " :mode true}]"
                   " :patch [{:id :mode-tui :remove true}]}\n"))]
    (try
      (reset! probe-mode-input nil)
      (with-redefs [world/load! (fn [_ _] {:model "probe"})]
        (let [{:keys [code]} (support/run-main ["--patch" path "hi"])]
          (is (zero? code) "the probe mode reports the run's exit")
          (is (= {:model "probe"} (:config @probe-mode-input))
              "the mode's config is the world the world row loaded")
          (is (= "hi" (:prompt @probe-mode-input))
              "and the invocation's input rides the same map")))
      (finally (io/delete-file path true)))))

(deftest a-mode-row-that-also-declares-defer-still-runs
  ;; `:defer` is "a boot does not mount me", not "the entry's last mount skips
  ;; me": the entry mounts the deferred rows that are not the mode first, then
  ;; the mode. A mode row that declared `:defer` therefore gets its input
  ;; instead of leaving the entry waiting on a report that never comes.
  (let [path (scratch-patch!
              (str "{:rows [{:id :mode-probe :plugin kmet.app.test-core/probe-mode"
                   " :mode true :defer true}]"
                   " :patch [{:id :mode-tui :remove true}]}\n"))]
    (try
      (reset! probe-mode-input nil)
      (with-redefs [world/load! (fn [_ _] {})]
        (let [result (deref (future (support/run-main ["--patch" path "hi"]))
                            20000 ::timeout)]
          (is (not= ::timeout result) "the entry returns instead of waiting forever")
          (is (zero? (:code result)))
          (is (= "hi" (:prompt @probe-mode-input))
              "the mode ran with the invocation's input")))
      (finally (io/delete-file path true)))))

(deftest parse-args-collects-extension-flags
  ;; kmet1's three forms: `=` splits, a following non-`-` token is the value,
  ;; and nothing following is true. The key drops the dashes — the name
  ;; `get-flag` reads.
  (is (= {"my-ext-flag" true} (:ext-flags (core/parse-args ["--my-ext-flag"]))))
  (is (= {"my-ext-flag" "v"} (:ext-flags (core/parse-args ["--my-ext-flag" "v"]))))
  (is (= {"my-ext-flag" "v"} (:ext-flags (core/parse-args ["--my-ext-flag=v"]))))
  (is (= {"my-ext-flag" true}
         (:ext-flags (core/parse-args ["--my-ext-flag" "--profile" "print"])))
      "a following flag is not a value")
  (is (= {"p" true} (:ext-flags (core/parse-args ["--p"])))
      "an unsupported flag is data, not a usage error — kmet1's rule"))

(deftest parse-args-takes-kmet1s-session-lifecycle-flags
  ;; Which session a run opens is the mode's, but the flags are the command
  ;; line's: they are kmet1's, and the TUI row applies them (the modes that
  ;; cannot honor them refuse them by name — the next test).
  (is (true? (:continue? (core/parse-args ["-c"]))))
  (is (true? (:continue? (core/parse-args ["--continue"]))))
  (is (true? (:resume? (core/parse-args ["-r"]))))
  (is (true? (:resume? (core/parse-args ["--resume"]))))
  (is (= "1a001ea0a00" (:session (core/parse-args ["--session" "1a001ea0a00"]))))
  (is (= "1a001ea0a00" (:session (core/parse-args ["--session=1a001ea0a00"])))
      "the --flag=value form")
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--session"])))
      "--session takes a value")
  (is (= :tui (:profile (core/parse-args ["-c"])))
      "continuing needs no mode: the default profile is the TUI, which has one"))

(deftest parse-args-takes-kmet1s-run-flags
  ;; kmet1's run flags, in kmet1's spellings: the thinking level, the prompt
  ;; overrides (one replaces, the other stacks), the session's model scope and
  ;; its storage dir. The entry parses them; what they mean to a run is
  ;; `kmet.app.invocation`'s (the patterns resolved before the boot, the rest
  ;; applied by the mode).
  (is (= :high (:thinking (core/parse-args ["-t" "high"]))))
  (is (= :low (:thinking (core/parse-args ["--thinking=low"])))
      "the --flag=value form")
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--thinking"])))
      "a thinking level needs a value")
  (is (= "be brief" (:system-prompt (core/parse-args ["--system-prompt" "be brief"]))))
  (is (= "- be concise" (:system-prompt (core/parse-args ["--system-prompt" "- be concise"])))
      "a prompt is free text: a dash-led line is the value, not the next flag")
  (is (= ["a" "b"]
         (:append-system-prompt
          (core/parse-args ["--append-system-prompt" "a"
                            "--append-system-prompt" "b"])))
      "repeatable: the pieces stay separate for `get-append-system-prompt`")
  (is (= ["p/a" "p/b"] (:models (core/parse-args ["--models" " p/a , p/b "])))
      "one comma-separated argument, trimmed, as kmet1 reads it")
  (is (= "target/sessions" (:session-dir (core/parse-args ["--session-dir" "target/sessions"]))))
  (is (= :kmet.app/bad-args (error-type #(core/parse-args ["--session-dir"]))))
  (is (not (contains? (core/parse-args ["-p" "hi"]) :thinking))
      "a flag the invocation did not give stays absent"))

(deftest the-invocations-run-flags-reach-the-mode-row
  ;; The hand-off is the entry's: the mode's row config carries what the
  ;; command line asked for, and a mode applies it (the TUI row hands the map
  ;; to kmet1's `interactive/run`; print/rpc/server hand `create-opts` to
  ;; `:agents`), and the process world's loaded config rides the same map so a
  ;; mode that runs on it (the TUI) needs no second load. Only the keys the
  ;; invocation gave are there.
  (let [mode-input @#'core/mode-input
        opts (core/parse-args ["-p" "--thinking" "high"
                               "--system-prompt" "SYS"
                               "--append-system-prompt" "MORE"
                               "--session-dir" "target/sessions"
                               "--models" "a,b"])]
    (is (= {:format :text :thinking :high :system-prompt "SYS"
            :append-system-prompt ["MORE"] :session-dir "target/sessions"
            :models ["a" "b"] :prompt "hi" :on-result :result
            :config {:model "x"}}
           (mode-input opts {:model "x"} "hi" :result))))
  (is (= {:format :text :on-result :result :config {}}
         (@#'core/mode-input (core/parse-args ["-p"]) {} "" :result))
      "a bare -p gives the mode nothing it did not ask for"))

(deftest a-model-the-catalog-does-not-have-is-reported-before-the-mode-mounts
  ;; The entry resolves the invocation's model patterns after the process world
  ;; loads (an extension provider is a catalog entry only once it has) and
  ;; before the mode mounts, so a bad one is a configuration error naming the
  ;; pattern — not a session and then a request that dies with nothing to say.
  (with-redefs [models/get-provider (fn [p] {:id p})
                models/get-models (fn [& _] [])
                world/load! (fn [_ _] {})]
    (let [{:keys [code out err]} (support/run-main ["-p" "--model" "nope" "hi"])]
      (is (= 1 code))
      (is (= "" out) "nothing runs, so nothing is printed")
      (is (str/includes? err "Model \"nope\" not found")))))

(deftest session-flags-need-a-mode-that-opens-a-session
  ;; Print, rpc and the server create their agent through `:agents`, whose
  ;; factory takes no session option yet. Accepting the flag
  ;; there would start the fresh session it exists to prevent, so it is refused
  ;; by name, before anything boots.
  (doseq [[args flag profile] [[["-p" "hi" "-c"] "--continue" "print"]
                               [["--profile" "rpc" "--resume"] "--resume" "rpc"]
                               [["--profile" "rpc" "--session" "abc"]
                                "--session" "rpc"]]]
    (let [{:keys [code err]} (support/run-main args)]
      (is (= 1 code) (pr-str args))
      (is (str/includes? err (str flag " needs a mode that opens a session"))
          "the flag is named, not silently dropped")
      (is (str/includes? err (str "profile " profile))
          "and so is the profile whose mode cannot honor it")
      (is (str/includes? err "kmet -c")
          "and the invocation that can"))))

(deftest version-prints-the-shared-string
  (with-redefs [version/kmet-version (constantly "v9.9.9")]
    (let [{:keys [code out]} (support/run-main ["--version"])]
      (is (zero? code))
      (is (= "kmet v9.9.9\n" out)))))

(deftest help-prints-kmet2-flags
  (let [{:keys [code out]} (support/run-main ["--help"])]
    (is (zero? code))
    (is (str/includes? out "Usage: kmet "))
    (is (str/includes? out "--dump-config"))
    (is (str/includes? out "--dump-state"))
    (is (str/includes? out "--profile"))
    (is (str/includes? out "-c, --continue")
        "the session-lifecycle flags are listed with the profile they belong to")
    (is (str/includes? out "--session <id>"))
    (is (str/includes? out "--thinking <level>"))
    (is (str/includes? out "--system-prompt <text>"))
    (is (str/includes? out "--append-system-prompt <text>"))
    (is (str/includes? out "--models <a,b>"))
    (is (str/includes? out "--session-dir <dir>"))
    (is (str/includes? out "install <source>")
        "the package subcommands the entry dispatches are listed too")
    (is (str/includes? out "config             Compose extensions / toggle resources"))))

(deftest package-subcommands-run-the-shared-cli
  ;; `config` is a command, not a profile: the entry hands the args to the
  ;; package CLI kmet1 uses, and injects the config screen it cannot require.
  (let [seen (atom nil)]
    (with-redefs [core/run-config (fn [opts] (reset! seen opts) 0)]
      (let [{:keys [code err]} (support/run-main ["config" "-l"])]
        (is (zero? code) err)
        (is (true? (:local @seen))
            "the parsed -l reaches the injected screen"))))
  (let [{:keys [code out]} (support/run-main ["config" "--help"])]
    (is (zero? code))
    (is (str/includes? out "kmet config [-l]"))
    (is (str/includes? out "resource configuration TUI")))
  (let [{:keys [code out]} (support/run-main ["install" "--help"])]
    (is (zero? code))
    (is (str/includes? out "kmet install <source>")
        "every package subcommand comes from the same CLI"))
  (let [{:keys [code err]} (support/run-main ["config" "--nope"])]
    (is (= 1 code))
    (is (str/includes? err "Unknown option --nope"))
    (is (str/includes? err "kmet config")
        "a command's bad option names the command's usage")))

(deftest dump-config-prints-the-built-in-composition
  (let [{:keys [code out]} (support/run-main ["--dump-config"])]
    (is (zero? code))
    (is (str/starts-with? out "profile :tui rows=25 patches=1")
        (str "the bundle: the logger, the process world, the extension host, the bus, the"
             " four registries, the prompt builder, the session log and its"
             " storage, the projections, the agent registry, the loop's two"
             " stage rows, the loop driver, the five tool rows, and the"
             " terminal provider that replaces the approval seam"))
    (is (str/includes? out ":world")
        "the process world is a row like any other")
    (is (str/includes? out "provide=[:world]"))
    (is (str/includes? out ":events"))
    (is (str/includes? out ":sessions"))
    (is (str/includes? out "inject={:sessionPersistence nil}")
        "a vector :inject prints in the kernel's normalized form")
    (is (str/ends-with? out "\n")))
  (let [{:keys [out]} (support/run-main ["--dump-config" "--profile" "rpc"])]
    (is (str/starts-with? out "profile :rpc")
        "--profile selects the composition")))

(deftest dump-config-composes-a-patch-layer
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/sample-plugin"
                   " :config {:a 1}}]\n"
                   " :patch [{:id :app/sample :config {:b {:c 2}}}]}\n"))]
    (try
      (let [{:keys [code out]} (support/run-main ["--dump-config" "--patch" path])]
        (is (zero? code))
        (is (str/starts-with? out "profile :tui rows=26 patches=2"))
        (is (str/includes? out ":app/sample"))
        (is (str/includes? out "inject={:tools nil}"))
        (is (str/includes? out "config={:a 1, :b {:c 2}}")
            "the row's config and the patch's deep-merge"))
      (finally (io/delete-file path true)))))

(deftest dump-config-reports-a-bad-layer
  (let [path (scratch-patch! "{:rows [] :nope 1}\n")]
    (try
      (let [{:keys [code err]} (support/run-main ["--dump-config" "--patch" path])]
        (is (= 1 code))
        (is (str/includes? err "a patch file holds")))
      (finally (io/delete-file path true))))
  (let [{:keys [code err]} (support/run-main ["--dump-config" "--profile" "nope"])]
    (is (= 1 code))
    (is (str/includes? err "unknown profile")))
  (let [{:keys [code err]} (support/run-main ["--dump-config" "--patch"
                                              "target/definitely-missing-patch.edn"])]
    (is (= 1 code))
    (is (str/includes? err "cannot read patch file"))))

(deftest dump-config-composes-user-layers-in-order
  (let [global (scratch-patch!
                (str "{:rows [{:plugin kmet.app.test-core/sample-plugin"
                     " :config {:a 1}}] :patch []}\n"))
        local (scratch-patch! "{:rows [] :patch [{:id :app/sample :config {:a 2}}]}\n")
        cli (scratch-patch! "{:rows [] :patch [{:id :app/sample :config {:a 3}}]}\n")]
    (try
      (with-redefs [cfg/patch-paths (fn [] [global local])]
        (let [{:keys [code out]} (support/run-main ["--dump-config"])]
          (is (zero? code))
          (is (str/starts-with? out "profile :tui rows=26 patches=2")
              "the global layer inserts the row; the project layer patches it")
          (is (str/includes? out "config={:a 2}")
              "the project layer applies over the global one"))
        (let [{:keys [code out]} (support/run-main ["--dump-config" "--patch" cli])]
          (is (zero? code))
          (is (str/includes? out "config={:a 3}")
              "a --patch flag applies last")))
      (finally
        (doseq [p [global local cli]] (io/delete-file p true))))))

(deftest dump-config-reports-a-bad-user-layer
  (let [path (scratch-patch! "{:rows [] :nope 1}\n")]
    (try
      (with-redefs [cfg/patch-paths (fn [] [path])]
        (let [{:keys [code err]} (support/run-main ["--dump-config"])]
          (is (= 1 code))
          (is (str/includes? err "a patch file holds"))))
      (finally (io/delete-file path true)))))

(deftest dump-default-config-ignores-user-layers-and-patch-flags
  (let [path (scratch-patch! "{:rows [] :nope 1}\n")]
    (try
      (with-redefs [cfg/patch-paths (fn [] [path])]
        (let [{:keys [code out]} (support/run-main ["--dump-default-config"
                                                    "--patch" path])]
          (is (zero? code) "neither the ambient layer nor --patch is read")
          (is (str/starts-with? out "profile :tui rows=25 patches=1"))))
      (finally (io/delete-file path true)))))

(deftest dump-state-prints-the-booted-tree
  ;; A dump must not read the machine's settings: the process world is deferred
  ;; out of it, so a load that throws is never reached.
  (with-redefs [world/load! (fn [& _] (throw (ex-info "a dump loaded the world" {})))]
    (let [{:keys [code out err]} (support/run-main ["--dump-state"])]
      (is (zero? code))
      (is (str/starts-with? out "[:app]")
          "the root frame, at the origin of every unit path")
      (is (str/includes? out "children=24")
          (str "the twenty-two bundle rows the world is deferred out of, then the"
               " TUI's mode row — mounted, not run"))
      (is (str/includes? out ":active"))
      (is (str/includes? out "provides=[commands]"))
      (is (str/includes? out "deferred: :world")
          "the row a dump holds back is named, so the tree explains the count")
      (is (str/ends-with? out "\n"))
      (is (= "" err) "a dump boots and disposes; it reports nothing else"))))

(deftest dump-state-composes-a-patch-layer
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/sample-plugin}]"
                   " :patch []}\n"))]
    (try
      (let [{:keys [code out]} (support/run-main ["--dump-state" "--patch" path])]
        (is (zero? code))
        (is (str/includes? out "[:app :app/sample]")
            "the patched row mounts, so the tree shows it"))
      (finally (io/delete-file path true)))))

(deftest dump-state-renders-a-row-that-could-not-mount
  ;; The booted view is worth most exactly when a row does not mount, so the
  ;; dump is a diagnostic boot: the failure is a line in the tree, not an exit
  ;; code that hides which row and why.
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/failing-plugin}"
                   "         {:plugin kmet.app.test-core/pending-plugin}]"
                   " :patch []}\n"))]
    (try
      (let [{:keys [code out]} (support/run-main ["--dump-state" "--patch" path])]
        (is (zero? code))
        (is (str/includes? out "[:app :app/failing]"))
        (is (str/includes? out ":failed"))
        (is (str/includes? out "error=\"boom\""))
        (is (str/includes? out "[:app :app/pending]"))
        (is (str/includes? out ":pending"))
        (is (str/includes? out "waiting-for=[nothing]"))
        (is (str/includes? out "[:app :tools]")
            "the rows that did mount are still in the tree"))
      (finally (io/delete-file path true)))))

(deftest a-bare-run-still-fails-hard-on-a-row-that-could-not-mount
  ;; The diagnostic option belongs to the dump: a boot that is meant to run
  ;; reports the same composition as an error.
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/pending-plugin}]"
                   " :patch []}\n"))]
    (try
      (let [{:keys [code err]} (support/run-main ["--patch" path])]
        (is (= 1 code))
        (is (str/includes? err "kmet2 boot failed")))
      (finally (io/delete-file path true)))))

(deftest a-bare-run-boots-the-composition-and-says-what-is-missing
  ;; Every built-in profile has a mode row now, so the report path is what a
  ;; composition that patched its mode out gets: the profile still boots (that
  ;; is the point — the rows mount) and then says what it could not run.
  (let [path (scratch-patch! "{:rows [] :patch [{:id :mode-tui :remove true}]}\n")]
    (try
      (let [{:keys [code err]} (support/run-main ["--patch" path "hello"])]
        (is (= 1 code))
        (is (str/includes? err
                           (str "booted 23 plugin rows (23 fibers, 1 deferred: :world, services:"
                                " agents approvals commands compaction events llm"
                                " logger loop-guard projections prompts retry-policy"
                                " sessionPersistence sessions skills system-prompt"
                                " tools ui)"))
            "the boot is the point: a run with no mode still proves the rows mount,
             and names the row it held back")
        (is (str/includes? err "profile tui has no runnable mode")
            "the profile is named, and the row that would have run it")
        (is (str/includes? err "--profile print") "and so is a profile that runs")
        (is (str/includes? err "--profile rpc"))
        (is (str/includes? err "--dump-config"))
        (is (str/includes? err "--dump-state")))
      (finally (io/delete-file path true)))))

(deftest a-bare-run-selects-the-tui
  ;; The default profile's mode row is the TUI: the entry point finds it and
  ;; hands it the invocation, so a bare `kmet` is a TUI session rather
  ;; than a boot report. The row itself is not started here — a test has no
  ;; terminal.
  (let [opts (core/parse-args ["hello"])
        mode (last (:rows (core/compose-config opts)))]
    (is (= :tui (:profile opts)))
    (is (= :mode-tui (:id mode)) "the last row is the mode")
    (is (= 'kmet.ui.tui/plugin (:plugin mode)) "and it is the TUI")
    (is (true? (:mode mode)) "marked as the row the entry point hands the run to")
    (is (true? (:session-lifecycle mode))
        "and as the row that opens the session, so -c/-r/--session reach it")))

(deftest a-boot-failure-is-reported-as-a-configuration-error
  (let [path (scratch-patch!
              (str "{:rows [{:plugin kmet.app.test-core/failing-plugin}]\n"
                   " :patch []}\n"))]
    (try
      (let [{:keys [code err]} (support/run-main ["--patch" path])]
        (is (= 1 code))
        (is (str/includes? err "kmet2 boot failed")))
      (finally (io/delete-file path true)))))
