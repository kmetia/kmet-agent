(ns kmet.tasks.build-jolt
  "Build the self-contained kmet executable for the jolt host — the jolt half
   of the `dist` task (bb.edn branches on *jolt-version*: `jolt dist` lands
   here, `bb dist` lands in kmet.tasks.build).

   Where the babashka packager downloads the official babashka binary and
   appends target/kmet.jar, this one drives jolt's own AOT build, which links
   the runtime, clojure.core, the stdlib, every dependency and the app into one
   native executable. There is no jar step and nothing to download here — the
   compile is the whole build — so the packager owns what the CLI does not:

   - the version-stamped artifact name in dist/
     (`kmet-<ver>-jolt<jv>-<platform>`, jolt in the slot kmet.tasks.build fills
     with bb<version>, under the platform vocabulary shared with it, so one
     dist/ carries both hosts' artifacts side by side);
   - a stable scratch dir under target/jolt/, so jolt's incremental build (and
     its <out>.build payload dir) survives across runs and never lands in dist/;
   - a smoke test that runs the artifact away from the checkout, which is what
     proves the model catalogs were embedded and not merely found on disk;
   - a Termux launcher, like the babashka packager's: a glibc-linked binary
     needs the glibc dynamic linker on Android.

   The compile runs as a `jolt build` SUBPROCESS, not in this process:

   - compiling in-process (jolt.host/build-binary) would mean reimplementing
     jolt.main's private build path — resolve-current, encode-natives for the
     :jolt/native specs, the output-path rules — against internal vars. The
     subprocess speaks the documented CLI instead, which also leaves `jolt
     build` itself the compiler command (`jolt build -m NS --opt` still works
     for one-off builds in this repo).
   - the task is `dist` on both hosts, never `build`: a task called `build`
     either loses to jolt's built-in (jolt warns about the shadowed task on
     every `jolt build`) or, with :override-builtin true, displaces it — and
     then this wrapper's own `jolt build` call re-enters the task forever."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            ;; shared with the babashka packager: one artifact-version rule
            ;; (kmet.libs.version) and one termux probe
            [kmet.tasks.build :as build]
            [kmet.libs.version :as version-lib]))

(def ^:private dist-dir "dist")
(def ^:private scratch-root "target/jolt")
(def ^:private entry-ns "kmet.core")
(def ^:private test-entry-ns "kmet.tasks.test-main")

(def ^:private bake-root
  "Embed root (deps.edn :jolt/build :embed) the packager writes the version
   resource into before the build: kmet/version.txt, what `kmet --version`
   reports out of the binary. A missing root embeds nothing, so a direct
   `jolt build` — no packager, no bake — still builds and falls back to the
   checkout's describe at run time."
  "target/kmet-version")

(def ^:private smoke-args
  "The model half of the smoke test: kmet.core prints the model table and
   exits, and the table is built from the embedded catalogs — a missing embed
   fails here. The version half runs --version and is checked against the
   bake."
  ["--list-models"])

;; ─── naming ───────────────────────────────────────────────────────────────

(defn host-platform
  "Dist platform for the machine we're running on, or :unknown-platform. The
   vocabulary is kmet.tasks.build/platform-for — the babashka packager stamps
   the same string, so one dist/ carries both hosts' artifacts for a machine
   under one platform name."
  []
  (or (build/platform-for (System/getProperty "os.name") (System/getProperty "os.arch"))
      :unknown-platform))

(def ^:private machine-platforms
  "Chez machine string -> dist platform, for `jolt build --target MACHINE`
   (tools/cross-compile/README.md). A machine missing from the table keeps its
   own string on the artifact, which is still unique and honest."
  {"ta6le" "linux-amd64"
   "tarm64le" "linux-aarch64"
   "ta6osx" "macos-amd64"
   "tarm64osx" "macos-aarch64"
   "ta6nt" "windows-amd64"
   "tarm64nt" "windows-aarch64"})

(defn target-platform
  "Dist platform for a build: the host, or TARGET (a Chez machine string)
   mapped through machine-platforms."
  [target]
  (if (str/blank? (str target))
    (host-platform)
    (get machine-platforms target target)))

(defn windows-platform?
  "True for a platform whose binary carries the .exe suffix (jolt appends it
   for an nt target, and the packager must name the file it will find)."
  [platform]
  (str/starts-with? (str platform) "windows-"))

(defn jolt-version
  "The compiling jolt's version, filename-safe and without the leading v —
   e.g. \"0.8.6-86-g234f460b\". nil off the jolt host, so the pure naming
   helpers stay callable under babashka (the tests)."
  []
  (some-> (find-var 'clojure.core/*jolt-version*)
          deref
          (str/replace #"^v" "")
          (str/replace #"[^A-Za-z0-9.+_-]" "_")))

(defn artifact-base
  "Dist artifact base name, no extension: kmet-<ver>-jolt<jolt-ver>-<platform>,
   or kmet-test-... for a --test build. The scheme
   kmet.tasks.build/artifact-base uses, with jolt in bb's slot — version, host
   version, platform — so one dist/ lines both hosts up per platform. A dev
   build is tagged, because it is a different artifact under the same
   sources."
  [ver jolt-ver platform {:keys [dev? test?]}]
  (str (if test? "kmet-test-" "kmet-") ver "-jolt" (or jolt-ver "dev") "-" platform
       (when dev? "-dev")))

(defn- scratch-bin
  "Where jolt compiles the binary: a path per (platform, mode) under
   target/jolt/, so jolt's <out>.build dir — the emitted Scheme, the boot
   image, and the fasl caches that make a rebuild incremental — lives there
   and not in dist/. A --test build gets its own name and dir: a different
   entry namespace is a different program, so its incremental state must not
   be mixed with the app's."
  ([platform mode] (scratch-bin platform mode {}))
  ([platform mode {:keys [test?]}]
   (fs/path scratch-root (str platform) (str mode (when test? "-test"))
            (cond
              (windows-platform? platform) (if test? "kmet-test.exe" "kmet.exe")
              test? "kmet-test"
              :else "kmet"))))

(defn- default-artifact
  "The dist artifact label for a build: dist/kmet-<ver>-jolt<jv>-<platform>[-dev][.exe]
   (kmet-test-... for a --test build). An nt platform takes the suffix, the
   way jolt's own output path does — the file has to be executable by name on
   Windows. A /-joined string, not a Path: the label is printed and tested,
   and str of a Path renders with backslashes on Windows."
  ([ver jolt-ver platform mode] (default-artifact ver jolt-ver platform mode {}))
  ([ver jolt-ver platform mode {:keys [test?]}]
   (str dist-dir "/"
        (artifact-base ver jolt-ver platform
                       {:dev? (= mode "dev") :test? test?})
        (when (windows-platform? platform) ".exe"))))

;; ─── CLI ──────────────────────────────────────────────────────────────────

(defn- opt-value
  "The argument following an option that takes a value, or a ::usage error
   naming the option (jolt's own CLI would read a missing value as the next
   option, or as nothing at all)."
  [flag more]
  (let [v (first more)]
    (when (str/blank? (str v))
      (throw (ex-info (str flag " needs a value") {:type ::usage :option flag})))
    v))

(defn parse-args
  "CLI args -> options map. Unknown options and bare arguments throw ex-info
   with :type ::usage — unlike the babashka packager there are no positional
   targets, because a jolt cross build needs a target pack rather than a
   download."
  [args]
  (loop [args args
         opts {:mode "release" :flags [] :boot nil :target nil :target-pack nil
               :out nil :jolt nil :force? false :no-smoke? true :test? false
               :help? false}]
    (if-some [arg (first args)]
      (let [more (rest args)]
        (case arg
          ;; mode selects the artifact name and jolt's own emission mode
          "--dev" (recur more (assoc opts :mode "dev"))
          "--opt" (recur more (assoc opts :mode "optimized"))
          ;; forwarded verbatim: knobs the packager has no opinion about
          ("--closed-world" "--tree-shake" "--dynamic" "--direct-link" "--no-direct-link")
          (recur more (update opts :flags conj arg))
          "--boot" (let [v (opt-value arg more)]
                     (when-not (#{"fast" "small" "plain"} v)
                       (throw (ex-info "--boot needs fast, small or plain"
                                       {:type ::usage :boot v})))
                     (recur (rest more) (assoc opts :boot v)))
          "--target" (recur (rest more) (assoc opts :target (opt-value arg more)))
          "--target-pack" (recur (rest more) (assoc opts :target-pack (opt-value arg more)))
          "-o" (recur (rest more) (assoc opts :out (opt-value arg more)))
          "--out" (recur (rest more) (assoc opts :out (opt-value arg more)))
          "--jolt" (recur (rest more) (assoc opts :jolt (opt-value arg more)))
          "--force" (recur more (assoc opts :force? true))
          "--test" (recur more (assoc opts :test? true))
          "--smoke" (recur more (assoc opts :no-smoke? false))
          "--no-smoke" (recur more (assoc opts :no-smoke? true))
          ("-h" "--help") (recur more (assoc opts :help? true))
          (if (str/starts-with? arg "-")
            (throw (ex-info (str "unknown option: " arg) {:type ::usage}))
            (throw (ex-info (str "unexpected argument: " arg
                                 " (cross builds take --target MACHINE --target-pack DIR)")
                            {:type ::usage :argument arg})))))
      opts)))

(defn- build-argv
  "The `jolt build` argv for OPTS: the CLI's own flags, with the entry and
   output pinned by the packager. Passed to the CLI undeclared flags stay
   undeclared — jolt would skip an unknown option silently, which is why
   parse-args validates them instead. A --test build selects :kmet-test first:
   test/ and the generated entry root (deps.edn) join the project's own roots,
   so the suite reaches the require closure the AOT walk starts from."
  [{:keys [mode flags boot target target-pack test?] :as opts}]
  (cond-> (vec (concat (when test? ["-A:kmet-test"])
                       ["build"
                        "-m" (or (:entry opts) (if test? test-entry-ns entry-ns))
                        "-o" (:out opts)]))
    (= mode "dev") (conj "--dev")
    (= mode "optimized") (conj "--opt")
    (seq flags) (into flags)
    boot (into ["--boot" boot])
    target (into ["--target" target])
    target-pack (into ["--target-pack" target-pack])))

;; ─── the build ────────────────────────────────────────────────────────────

(defn- bake-version!
  "Write the version resource into the bake root — the file `jolt build` bakes
   into the binary, which is where a built kmet reads `kmet --version` from.
   Returns the file written, so the caller can drop it after the build."
  []
  (let [f (fs/path bake-root "kmet" "version.txt")]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str (version-lib/checkout-version) "\n"))
    f))

(defn- run-jolt-build!
  "Run the compile, streaming jolt's output. Throws ::no-jolt when the
   executable can't be started and ::build-failed on a non-zero exit."
  [jolt argv]
  (println "$" (str/join " " (cons jolt argv)))
  (let [{:keys [exit]}
        (try
          (apply p/shell {:continue true :out :inherit :err :inherit} jolt argv)
          (catch Exception e
            (throw (ex-info (str "cannot run " jolt " — is jolt on PATH? (--jolt PATH overrides)")
                            {:type ::no-jolt :jolt jolt} e))))]
    (when-not (zero? exit)
      (throw (ex-info (str "jolt build failed (exit " exit ")")
                      {:type ::build-failed :exit exit :argv (vec argv)})))))

(defn- assemble!
  "Copy the compiled binary out of the scratch dir to its dist artifact,
   keeping the scratch for the next incremental build. Returns the artifact
   path. PLATFORM decides the executable bit: a windows artifact keeps whatever
   the filesystem does with it (setting POSIX permissions there fails)."
  [bin artifact platform]
  (fs/create-dirs (fs/parent artifact))
  (fs/copy bin artifact {:replace-existing true})
  (when-not (windows-platform? platform)
    (fs/set-posix-file-permissions artifact "rwxr-xr-x"))
  artifact)

(defn- wrapper-script
  "Termux launcher script: exec through the glibc dynamic linker (the built
   binary is glibc-linked; LD_PRELOAD — libtermux-exec — breaks non-bionic
   executables, so it is unset first). Same shape as the babashka packager's,
   minus bb's --jar: a jolt binary carries its payload itself."
  [bin-name linker]
  (format "#!/data/data/com.termux/files/usr/bin/sh
# kmet launcher (Termux): run the glibc-linked binary through the glibc
# dynamic linker; LD_PRELOAD (libtermux-exec) breaks non-bionic executables.
DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)
BIN=\"$DIR/%s\"
LD=\"$PREFIX/glibc/lib/%s\"
[ -x \"$LD\" ] || { echo \"termux glibc package required: pkg install glibc-repo && pkg install glibc\" >&2; exit 1; }
unset LD_PRELOAD
export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"
exec \"$LD\" --library-path \"$PREFIX/glibc/lib\" \"$BIN\" \"$@\"
"
          bin-name linker))

(defn- write-launcher!
  "On a Termux host, write and return the .sh launcher for ARTIFACT (nil
   otherwise). Only the host's own platform can run its launcher, so cross
   builds get none."
  [artifact platform]
  (when (and (build/termux?) (= platform (host-platform)))
    (let [linker (if (str/includes? (str platform) "aarch64")
                   "ld-linux-aarch64.so.1"
                   "ld-linux-x86-64.so.2")
          launcher (fs/path (fs/parent artifact) (str (fs/file-name artifact) ".sh"))]
      (spit (str launcher) (wrapper-script (str (fs/file-name artifact)) linker))
      (fs/set-posix-file-permissions launcher "rwxr-xr-x")
      launcher)))

(defn- temp-run-dir
  "A throwaway dir to run the artifact from, on the platform's real temp root
   (java.io.tmpdir is unreliable on Termux: it hardcodes /tmp)."
  []
  (let [root (or (not-empty (str (System/getenv "TMPDIR")))
                 (System/getProperty "java.io.tmpdir"))
        dir (fs/path root "kmet-build-jolt")]
    (fs/create-dirs dir)
    (fs/create-temp-dir {:dir dir :prefix "smoke-"})))

(defn- smoke-test!
  "Run the freshly built current-host artifact. An app artifact: run it with
   the --list-models smoke args and require exit 0, printing how many models
   it listed; then require --version to report the version the artifact was
   baked as. A --test artifact: `--test kmet.libs.test-num` must run that
   namespace (exit 0 and the Testing header) — the suite is statically
   required into the AOT image, so a run from the empty dir proves it was
   compiled in, not read from disk. Only the host's own platform can run here.

   The run happens in an empty temp dir with JOLT_PWD pointed at it: io/resource
   falls back to JOLT_PWD-relative source roots, so a run from the checkout
   would pass on the tree even when the catalog was never embedded — and the
   empty dir is what makes the --version check prove the bake, since the
   checkout's describe is unreachable there."
  [artifact platform {:keys [no-smoke? test?]}]
  (when (and (not no-smoke?) (= platform (host-platform)))
    (let [launcher (fs/path (fs/parent artifact) (str (fs/file-name artifact) ".sh"))
          cmd (if (and (build/termux?) (fs/exists? launcher))
                (str launcher)
                (str artifact))
          dir (temp-run-dir)
          run (fn [& args]
                (apply p/sh {:continue true :out :string :err :string
                             :dir (str dir) :extra-env {"JOLT_PWD" (str dir)}}
                       cmd args))]
      (try
        (if test?
          (do
            (println "smoke test:" cmd "--test kmet.libs.test-num")
            (let [res (run "--test" "kmet.libs.test-num")]
              (if (and (zero? (:exit res))
                       (str/includes? (:out res) "Testing kmet.libs.test-num"))
                (println "smoke test passed: the packaged runner ran kmet.libs.test-num")
                (do (binding [*out* *err*] (println (:err res)))
                    (throw (ex-info (str "smoke test failed for " artifact
                                         " — the artifact did not run the compiled test runner")
                                    {:type ::smoke-failed :exit (:exit res)}))))))
          (do
            (println "smoke test:" cmd (str/join " " smoke-args))
            (let [res (apply run smoke-args)]
              (if (zero? (:exit res))
                (println "smoke test passed:" (count (str/split-lines (:out res))) "models listed")
                (do (binding [*out* *err*] (println (:err res)))
                    (throw (ex-info (str "smoke test failed for " artifact
                                         " — the artifact did not run (see jolt-bugs.md: a gitlib-only"
                                         " java.time class must resolve during the build)")
                                    {:type ::smoke-failed :exit (:exit res)})))))
            (println "smoke test:" cmd "--version")
            (let [res (run "--version")
                  reported (str/trim (:out res))
                  expected (str "kmet " (version-lib/checkout-version))]
              (if (and (zero? (:exit res)) (= reported expected))
                (println "smoke test passed:" reported)
                (do (binding [*out* *err*] (println (:err res)))
                    (throw (ex-info (str "smoke test failed for " artifact
                                         " — --version said " (pr-str reported)
                                         ", expected " (pr-str expected))
                                    {:type ::smoke-failed :exit (:exit res)
                                     :version reported :expected expected})))))))
        (finally
          (fs/delete-tree dir))))))

;; ─── entry point ──────────────────────────────────────────────────────────

(defn -main
  "jolt dist [options]

   Build kmet's self-contained Jolt executable into dist/: one native binary
   with the runtime, clojure.core, the stdlib, the dependencies and kmet
   itself compiled in (the entry is kmet.core). Nothing has to be installed on
   the machine that runs it — no jolt, no jar, no classpath.

   --test builds the compiled test runner instead: the same pipeline with the
   generated kmet.tasks.test-main entry, every test namespace statically
   required into the image. The artifact is
   kmet-test-<ver>-jolt<jv>-<platform> and takes `--test` / `--test-ext`
   [filters] — the same slow/fast split as `bb test` / `bb test-ext`, run as
   compiled code.

   This is the Jolt half of the project's `dist` task: `bb dist` builds the
   same app for the babashka host and names its artifact for the same
   platform, so one dist/ can carry both hosts' binaries side by side.

   Options:
     --dev                     unoptimized build, quickest to produce; vars
                               stay redefinable (a development build)
     --opt                     optimized build: smaller and faster, but the
                               binary cannot render Clojure backtraces
                               (the default is a release build: the same
                               optimizations, with backtraces)
     --closed-world            drop definitions unreachable from the entry
                               point (alias: --tree-shake) — a smaller binary
     --dynamic                 load :jolt/native libraries at run time instead
                               of linking their archives in; the binary then
                               needs those libraries where it runs, so it is
                               no longer self-contained (the build lists them)
     --direct-link             direct linking is already on in release and
                               optimized builds — this is a redundant alias.
                               With it a plain def is frozen into the binary
                               (^:redef or ^:dynamic keeps a var redefinable);
                               --no-direct-link opts back out
     --boot fast|small|plain   how the boot image is shipped (default: fast,
                               env var JOLT_BOOT):
                                 fast   prebuilt image — quickest startup,
                                        most disk
                                 small  gzip-compressed image — smallest to
                                        ship, still an image (fast startup)
                                 plain  no image — slowest startup
     --target MACHINE          cross-compile for another Chez machine, named
                               by the kmet platform it maps to: ta6le
                               (linux-amd64), tarm64le (linux-aarch64), ta6osx
                               (macos-amd64), tarm64osx (macos-aarch64), ta6nt
                               (windows-amd64), tarm64nt (windows-aarch64).
                               Needs --target-pack DIR (or the JOLT_TARGET_PACK
                               env var)
     --target-pack DIR         the prepared pack for --target MACHINE
     -o, --out PATH            write the artifact here instead of
                               dist/kmet-<ver>-jolt<jv>-<platform>[-dev][.exe]
     --test                    build the compiled test runner instead
                               (kmet-test-<ver>-jolt<jv>-<platform>; entry
                               kmet.tasks.test-main, the whole suite compiled
                               in). The binary takes --test | --test-ext
                               [filters]; release mode (the default) is the
                               one to use — its compressed fasl image links
                               far faster than --dev's uncompressed one for
                               the full suite
     --force                   discard the incremental build state under
                               target/jolt/ and compile from scratch
     --smoke                   verify the artifact after building: run it from
                               an empty directory, list the models and check
                               --version against the version baked in — the
                               check to run before publishing. A --test
                               artifact runs `--test kmet.libs.test-num`
                               instead (proving the suite is compiled in). A
                               cross build skips it (only this host's own
                               platform can run here)
     --no-smoke                skip that verification (the default, so a plain
                               `jolt dist` is quick and side-effect free)
     --jolt PATH               the jolt executable that performs the compile
                               (default: jolt from PATH)
     -h, --help                this text

   Examples:
     jolt dist                          release build for this machine
     jolt dist --smoke                  ... and verify the artifact runs
     jolt dist --test                   compiled test runner (release)
     jolt dist --test --smoke           ... and smoke-run its suite
     jolt dist --dev -o dist/kmet-dev   quick development build, own path
     jolt dist --boot small             smallest artifact to ship
     jolt dist --target tarm64le --target-pack ~/packs/tarm64le
                                        cross build for linux-aarch64

   Artifacts land in dist/ as kmet-<ver>-jolt<jv>-<platform>[-dev][.exe]
   (kmet-test-<ver>-... for --test builds). On a Termux host the build also
   writes a .sh launcher next to the binary — the binary is glibc-linked, so
   run the launcher; a cross build gets none."
  [& args]
  (let [{:keys [mode target target-pack out force? no-smoke? jolt help? test?] :as opts}
        (parse-args args)]
    (when help?
      (println (:doc (meta #'-main)))
      (System/exit 0))
    (let [jolt-bin (or jolt "jolt")
          jver (jolt-version)
          platform (target-platform target)
          ver (build/version)
          bin (scratch-bin platform mode {:test? test?})
          artifact (if out
                     (fs/absolutize out)
                     (fs/absolutize (default-artifact ver jver platform mode {:test? test?})))]
      (when (= :unknown-platform platform)
        (throw (ex-info "cannot determine host platform; cross builds need --target MACHINE --target-pack DIR"
                        {:type ::usage :reason platform})))
      (when (and target (nil? target-pack) (str/blank? (str (System/getenv "JOLT_TARGET_PACK"))))
        (throw (ex-info "--target needs a target pack: --target-pack DIR (or $JOLT_TARGET_PACK)"
                        {:type ::usage :target target})))
      (println (format "kmet%s %s | jolt %s | %s%s" (if test? "-test" "") ver jver platform
                       (if (= mode "release") "" (str " | " mode))))
      (when force?
        (println "clearing scratch:" (str (fs/parent bin)))
        (fs/delete-tree (fs/parent bin)))
      (fs/create-dirs (fs/parent bin))
      (when test?
        (println "generating" (str (build/generate-test-main!))))
      ;; bundled extensions are embedded through the staged root deps.edn
      ;; lists ("target/kmet-bundled", extension-bundle.md §2.3); staging
      ;; validates the manifest first, so a broken bundle fails the build
      (build/stage-bundled-extensions!)
      ;; the version resource the build bakes in (kmet --version reports it);
      ;; removed again afterwards so a later direct `jolt build` cannot bake a
      ;; previous run's version from the leftover file
      (let [baked (bake-version!)]
        (try
          (run-jolt-build! jolt-bin (build-argv (assoc opts :out (str (fs/absolutize bin)))))
          (finally
            (fs/delete-if-exists baked))))
      (when-not (fs/exists? bin)
        (throw (ex-info (str "jolt build reported success but " bin " is missing")
                        {:type ::no-binary :path (str bin)})))
      (assemble! bin artifact platform)
      (when-let [launcher (write-launcher! artifact platform)]
        (println "launcher:" (str launcher)))
      (smoke-test! artifact platform {:no-smoke? no-smoke? :test? test?})
      (println "built:" (str artifact)))))
