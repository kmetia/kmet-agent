(ns kmet.tasks.build-jolt
  "Build the kmet executable for the jolt host — the jolt half
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
   - the native link mode: static by default on Windows, dynamic elsewhere,
     with `--static` / `--dynamic` overriding either default; static builds
     stage and verify the archives named by deps.edn;
   - a smoke test that runs the artifact away from the checkout, which is what
     proves the model catalogs were embedded and not merely found on disk;
   - a Termux launcher, like the babashka packager's, when the local link is
     glibc: a glibc-linked binary needs the glibc dynamic linker on Android,
     while a jolt linked with the bionic cc runs directly and needs none.

   The compile runs as a `jolt build` SUBPROCESS, not in this process:

   - compiling in-process (jolt.host/build-binary) would mean reimplementing
     jolt.main's private build path — resolve-current, encode-natives for the
     :jolt/native specs, the output-path rules — against internal vars. The
     subprocess speaks the documented CLI instead, which also leaves `jolt
     build` itself the compiler command (`jolt build -m NS --opt` still works
     for one-off non-Windows builds in this repo; on Windows use `jolt dist`,
     which stages and verifies the required static archives).
   - the task is `dist` on both hosts, never `build`: a task called `build`
     either loses to jolt's built-in (jolt warns about the shadowed task on
     every `jolt build`) or, with :override-builtin true, displaces it — and
     then this wrapper's own `jolt build` call re-enters the task forever."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
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

(def ^:private bundled-native-log-lines
  "Successful-load lines proving both a bundled directory root and a mapped
   single-file resource used Jolt's native loader, not the SCI fallback."
  ["extension loaded: clojure kind=resource-dir loader=jolt bundled=true"
   "extension loaded: deepseek-peak.clj kind=resource-file loader=jolt bundled=true"])

(defn- static-native-root
  "Project-relative directory for one platform's staged :static archive paths."
  [platform]
  (fs/path "target/jolt-native" platform))

(def ^:private openssl-static-libs
  "The complete set of file-backed OpenSSL :jolt/native entries."
  ["libcrypto.a" "libssl.a"])

(def ^:private windows-static-runtime-libs
  "Static lz4/zlib archives Jolt's Windows launcher link needs. Staging them
   beside OpenSSL lets its -L prefer .a files over MSYS2 import libraries."
  ["liblz4.a" "libz.a"])

(def ^:private static-native-system-link-flags
  "OpenSSL's static archive uses Winsock, CryptoAPI, and zlib. Jolt's Windows
   runtime link list carries the first and third but not CryptoAPI; append all
   three after the static native archives so their symbols and transitive
   dependencies resolve."
  ["-lws2_32" "-lcrypt32" "-lz"])

(def ^:private static-cc-wrapper-root
  "Build-only PATH entry whose cc shim delegates to the discovered compiler
   and appends static-native's system dependencies. It is target/ scratch and
   never becomes an application resource."
  "target/jolt-native/windows-toolchain")

(def ^:private static-native-process-line
  "Jolt emits this process-symbol load for a :process native and for every
   native compiled from a :static archive. A runtime :req/:opt native instead
   carries its DLL candidate list, so counting these lines proves Jolt did not
   silently fall back to dynamic loading."
  #"\(jolt-build-load-native\s+'\(\)\s+#f\s+#t\)")

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

(defn- static-native-libs
  "All archives the requested platform's static build must stage."
  [platform]
  (cond-> (vec openssl-static-libs)
    (windows-platform? platform) (into windows-static-runtime-libs)))

(defn- native-platform
  "Jolt's :jolt/native platform key for a dist platform."
  [platform]
  (cond
    (windows-platform? platform) :windows
    (str/starts-with? platform "macos-") :darwin
    (str/starts-with? platform "linux-") :linux
    :else nil))

(defn- static-link
  "The static link Jolt will choose for SPEC on PLATFORM, or nil. A flat
   :static map applies everywhere; a platform map contributes that key."
  [platform spec]
  (let [static (:static spec)]
    (cond
      (:process spec) :process
      (or (:archive static) (:lib static)) static
      (map? static) (get static platform)
      :else nil)))

(defn- project-natives
  "The reconciled :jolt/native graph Jolt will pass to `jolt build`. nil on
   Babashka, where Jolt's resolver namespace does not exist."
  []
  (when-let [resolve-project (requiring-resolve 'jolt.deps/resolve-project)]
    (:natives (resolve-project (or (System/getenv "JOLT_PWD") ".")))))

(defn- validate-static-build!
  "Refuse a static build unless every file-backed native has a static link for
   this platform. Process entries are exempt: they bind the executable's own
   symbols."
  [platform natives]
  (let [key (native-platform platform)
        unlinked (remove #(static-link key %) natives)
        names (mapv #(or (:name %) "?") unlinked)]
    (when (seq unlinked)
      (throw (ex-info (str "static artifact has native libraries without a "
                           (or key :platform)
                           " :static link: "
                           (str/join ", " names))
                      {:type ::non-static-native
                       :native-platform key
                       :natives names})))))

(defn- native-link-mode
  "The requested native link mode, defaulting per platform: Windows static,
   every other platform dynamic."
  [platform requested]
  (or requested
      (if (windows-platform? platform) :static :dynamic)))

(defn- cross-static-build?
  "True when a static build names a platform other than the current host."
  [platform target]
  (and target (not= platform (host-platform))))

(defn- validate-build!
  "Apply the platform rules the raw Jolt CLI cannot express. Jolt cannot
   cross-link target-architecture native archives yet; a static build must
   also have a static declaration for every file-backed native."
  [platform target native-link]
  (when (and (= :static native-link)
             (cross-static-build? platform target))
    (throw (ex-info "static-native cross builds are not supported; build for the current host platform"
                    {:type ::usage :target target :platform platform})))
  (when (= :static native-link)
    (validate-static-build! platform (project-natives))))

(defn- static-library-source
  "Locate FILENAME without guessing at ABI compatibility. An explicit
   KMET_OPENSSL_STATIC_DIR wins, then CC's own answer, then pkg-config's
   OpenSSL libdir (Homebrew on macOS). The result matches the toolchain and
   package configuration Jolt will use for the final link."
  [cc filename]
  (let [override (not-empty (str/trim (str (System/getenv "KMET_OPENSSL_STATIC_DIR"))))
        override-source (when override (fs/path override filename))]
    (or (when (and override-source (fs/regular-file? override-source))
          override-source)
        (let [{:keys [exit out]}
              (p/sh {:continue true :out :string :err :string}
                    cc (str "-print-file-name=" filename))
              printed (str/trim (str out))]
          (when (and (zero? exit)
                     (seq printed)
                     (not= filename printed)
                     (fs/regular-file? printed))
            (fs/path printed)))
        (when-let [pkg-config (fs/which "pkg-config")]
          (let [{:keys [exit out]}
                (p/sh {:continue true :out :string :err :string}
                      pkg-config "--variable=libdir" "openssl")
                libdir (str/trim (str out))
                candidate (when (seq libdir) (fs/path libdir filename))]
            (when (and (zero? exit)
                       candidate
                       (fs/regular-file? candidate))
              candidate))))))

(defn- ensure-static-native-libs!
  "Stage this platform's static archives under the project-relative paths in
   deps.edn. The toolchain must provide archives matching the compiler Jolt
   uses for the final link."
  [platform]
  (let [cc (fs/which "cc")
        packages (if (windows-platform? platform)
                   "mingw-w64-x86_64-gcc mingw-w64-x86_64-openssl mingw-w64-x86_64-lz4"
                   "a C compiler with static libcrypto.a and libssl.a")
        _ (when-not cc
            (throw (ex-info (str "static-native builds need cc on PATH; install "
                                 packages)
                            {:type ::missing-static-toolchain :platform platform})))
        libs (static-native-libs platform)
        sources (into {}
                      (map (fn [filename]
                             [filename (static-library-source cc filename)]))
                      libs)
        missing (filterv (fn [filename]
                           (not (some-> (get sources filename) fs/regular-file?)))
                         libs)
        _ (when (seq missing)
            (throw (ex-info (str "cc could not locate the static native archive(s): "
                                 (str/join ", " missing)
                                 "; set KMET_OPENSSL_STATIC_DIR for OpenSSL and make cc find the remaining archives")
                            {:type ::missing-static-archive
                             :platform platform
                             :missing missing :cc (str cc)})))
        root (static-native-root platform)
        destinations (mapv #(fs/path root %) libs)]
    (fs/create-dirs root)
    (doseq [filename libs]
      (fs/copy (get sources filename) (fs/path root filename)
               {:replace-existing true}))
    (println "staged static native archives:" (str/join ", " (map str destinations)))
    destinations))

(defn- compiler-driver
  "Resolve a cc alias to the real driver beside it. The w64devkit cc.exe (and
   common cc symlinks) derive their library prefix from argv[0]; calling one
   through a shim named cc would otherwise make it search target/ instead of
   the toolchain. A cc that is already a driver is returned unchanged."
  [cc]
  (let [siblings (map #(fs/path (fs/parent cc) %) ["gcc.exe" "clang.exe"])]
    (or (first (filter fs/regular-file? siblings)) cc)))

(defn- compiler-wrapper-command
  "POSIX-sh command for the compiler shim. \"$@\" preserves Jolt's complete
   link invocation. The staged directory is added because Jolt's archive
   preload command has no -L; the SSL preload DLL also receives libcrypto.a
   because Jolt creates each static preload independently. The final executable
   already links both archives in order."
  [cc crypto-archive]
  (let [driver (str/replace (str cc) "\\" "/")
        archive (str/replace (str crypto-archive) "\\" "/")
        libdir (str/replace (str (fs/parent crypto-archive)) "\\" "/")
        flags (str/join " " static-native-system-link-flags)
        support (str "-L\"" libdir "\" " flags)]
    (str "#!/bin/sh\n"
         "case \" $* \" in\n"
         "  *-shared*libssl.a*)\n"
         "    exec \"" driver "\" \"$@\""
         " -Wl,--whole-archive \"" archive "\" -Wl,--no-whole-archive " support "\n"
         "    ;;\n"
         "  *)\n"
         "    exec \"" driver "\" \"$@\" " support "\n"
         "    ;;\n"
         "esac\n")))

(defn- windows-static-build-env
  "PATH for Jolt's Windows static build subprocess. The wrapper delegates to
   the same CC used to find the archives and supplies OpenSSL's
   Winsock/CryptoAPI/zlib dependencies to the final link."
  [platform]
  (let [cc (fs/which "cc")
        root (fs/path static-cc-wrapper-root)
        wrapper (fs/path root "cc")]
    (when-not cc
      (throw (ex-info "Windows static-native builds need cc on PATH"
                      {:type ::missing-static-toolchain})))
    (fs/create-dirs root)
    (spit (str wrapper) (compiler-wrapper-command
                         (compiler-driver cc)
                         (fs/absolutize (fs/path (static-native-root platform)
                                                 "libcrypto.a"))))
    (p/sh "chmod" "+x" (str wrapper))
    {"PATH" (str root ";" (System/getenv "PATH"))}))

(defn- verify-static-native-build!
  "Check Jolt's generated Scheme after a static compile. Every validated
   native must have become a process-symbol load backed by a static archive;
   a runtime candidate load would mean an older Jolt, a stale incremental
   build, or an ignored :static entry produced a non-static artifact."
  [bin natives]
  (let [flat (fs/path (str bin ".build") "flat.ss")
        expected (count natives)
        actual (if (fs/regular-file? flat)
                 (count (re-seq static-native-process-line (slurp (str flat))))
                 -1)]
    (when-not (= expected actual)
      (throw (ex-info (str "jolt build did not statically link every native "
                           "(expected " expected ", found " actual "); retry with --force on Jolt >= 0.8.11")
                      {:type ::non-static-build
                       :flat (str flat)
                       :expected expected
                       :actual actual})))
    true))

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

(defn- set-native-link
  "Record an explicit --static/--dynamic choice, rejecting the conflicting pair."
  [opts mode option]
  (when-let [existing (:native-link opts)]
    (when (not= existing mode)
      (throw (ex-info (str "choose either --static or --dynamic, not both (already chose "
                           (name existing) ")")
                      {:type ::usage
                       :option option
                       :native-link existing}))))
  (assoc opts :native-link mode))

(defn parse-args
  "CLI args -> options map. Unknown options and bare arguments throw ex-info
   with :type ::usage — unlike the babashka packager there are no positional
   targets, because a jolt cross build needs a target pack rather than a
   download."
  [args]
  (loop [args args
         opts {:mode "release" :flags [] :native-link nil :boot nil :target nil
               :target-pack nil :out nil :jolt nil :force? false
               :no-smoke? true :test? false :help? false}]
    (if-some [arg (first args)]
      (let [more (rest args)]
        (case arg
          ;; mode selects the artifact name and jolt's own emission mode
          "--dev" (recur more (assoc opts :mode "dev"))
          "--opt" (recur more (assoc opts :mode "optimized"))
          ;; forwarded verbatim: knobs the packager has no opinion about
          ("--closed-world" "--tree-shake" "--direct-link" "--no-direct-link")
          (recur more (update opts :flags conj arg))
          "--static" (recur more (set-native-link opts :static arg))
          "--dynamic" (recur more (set-native-link opts :dynamic arg))
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
   parse-args validates them instead. Dynamic natives are forwarded as
   `--dynamic`; static is Jolt's own build default. A --test build selects
   :kmet-test first:
   test/ and the generated entry root (deps.edn) join the project's own roots,
   so the suite reaches the require closure the AOT walk starts from."
  [{:keys [mode flags native-link boot target target-pack test?] :as opts}]
  (cond-> (vec (concat (when test? ["-A:kmet-test"])
                       ["build"
                        "-m" (or (:entry opts) (if test? test-entry-ns entry-ns))
                        "-o" (:out opts)]))
    (= mode "dev") (conj "--dev")
    (= mode "optimized") (conj "--opt")
    (seq flags) (into flags)
    (= :dynamic native-link) (conj "--dynamic")
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
  "Run the compile, streaming jolt's output. ENV is merged into the build
   subprocess; Windows uses it for the static-native cc shim. Throws ::no-jolt
   when the executable can't be started and ::build-failed on a non-zero exit."
  [jolt argv env]
  (println "$" (str/join " " (cons jolt argv)))
  (let [{:keys [exit]}
        (try
          (apply p/sh {:continue true :out :inherit :err :inherit :extra-env env}
                 jolt argv)
          (catch Exception e
            (throw (ex-info (str "cannot run " jolt " — is jolt on PATH? (--jolt PATH overrides)")
                            {:type :no-jolt :jolt jolt} e))))]
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

(defn- binary-contains?
  "True when the raw bytes of FILE contain the ASCII NEEDLE. Streamed in
   64 KiB chunks with a small carry, so a needle split across a chunk
   boundary still matches and a whole artifact never lands in memory."
  [file needle]
  (let [keep (max 1 (dec (count needle)))]
    (with-open [in (io/input-stream (fs/file file))]
      (loop [tail ""]
        (let [buf (byte-array 65536)
              read (.read in buf)]
          (if (neg? read)
            false
            (let [s (str tail (String. buf 0 read))]
              (if (str/includes? s needle)
                true
                (recur (if (>= (count s) keep) (subs s (- (count s) keep)) s))))))))))

(defn- glibc-linked?
  "True when ARTIFACT's ELF interpreter is a glibc loader — its PT_INTERP
   path contains \"ld-linux\". A binary linked with the Termux bionic cc
   (what a local jolt build there uses) carries \"linker64\" instead and
   runs directly, no loader and no LD_PRELOAD dance."
  [artifact]
  (binary-contains? artifact "ld-linux"))

(defn- write-launcher!
  "On a Termux host, write and return the .sh launcher for ARTIFACT when the
   binary is glibc-linked (nil otherwise: a bionic-linked local build runs
   directly, and smoke-test! then runs the artifact itself). Only the host's
   own platform can run its launcher, so cross builds get none."
  [artifact platform]
  (when (and (build/termux?) (= platform (host-platform)))
    (let [linker (if (str/includes? (str platform) "aarch64")
                   "ld-linux-aarch64.so.1"
                   "ld-linux-x86-64.so.2")
          launcher (fs/path (fs/parent artifact) (str (fs/file-name artifact) ".sh"))]
      (if (glibc-linked? artifact)
        (do (spit (str launcher) (wrapper-script (str (fs/file-name artifact)) linker))
            (fs/set-posix-file-permissions launcher "rwxr-xr-x")
            launcher)
        ;; a stale launcher from an earlier glibc-linked build of the same
        ;; path would shadow the (directly running) artifact for users and
        ;; smoke runs alike
        (do (fs/delete-if-exists launcher) nil)))))

(defn- temp-run-dir
  "A throwaway dir to run the artifact from, on the platform's real temp root
   (java.io.tmpdir is unreliable on Termux: it hardcodes /tmp)."
  []
  (let [root (or (not-empty (str (System/getenv "TMPDIR")))
                 (System/getProperty "java.io.tmpdir"))
        dir (fs/path root "kmet-build-jolt")]
    (fs/create-dirs dir)
    (fs/create-temp-dir {:dir dir :prefix "smoke-"})))

(defn- prepare-smoke-agent-dir
  "Create an isolated agent dir under the smoke run directory with a bundled
   directory extension and a bundled single-file extension enabled. The app's
   first smoke command runs with --debug, so their load lines prove both
   embedded-root and exact-source native loader selection."
  [dir]
  (let [agent-dir (str (fs/path dir "agent"))]
    (fs/create-dirs agent-dir)
    (spit (str (fs/path agent-dir "settings.edn"))
          (pr-str {:bundled-extensions ["clojure" "deepseek-peak"]}))
    agent-dir))

(defn- windows-smoke-path
  "PATH for a Windows artifact smoke run. System32 is enough for Windows'
   own DLLs; excluding the Git/Jolt directories makes a missing libcrypto or
   libssl archive fail at runtime instead of being supplied from the build
   machine's OpenSSL DLLs."
  []
  (str (str/replace (or (not-empty (System/getenv "SystemRoot")) "C:/Windows")
                    "/" "\\")
       "\\System32"))

(defn- smoke-test!
  "Run the freshly built current-host artifact. An app artifact: run it with
   --debug and the --list-models smoke args, require exit 0, and assert an
   enabled bundled directory and single-file resources loaded through Jolt's
   native roots; then require --version to report the version the artifact was baked
   as. A --test artifact: `--test kmet.libs.test-num` must run that
   namespace (exit 0 and the Testing header) — the suite is statically
   required into the AOT image, so a run from the empty dir proves it was
   compiled in, not read from disk. Only the host's own platform can run here.

   The run happens in an empty temp dir with JOLT_PWD pointed at it: io/resource
   falls back to JOLT_PWD-relative source roots, so a run from the checkout
   would pass on the tree even when the catalog was never embedded — and the
   empty dir is what makes the --version check prove the bake, since the
   checkout's describe is unreachable there. A Windows static build also runs
   with PATH reduced to System32, so the build machine's OpenSSL DLLs cannot
   mask a missed static link; a dynamic build keeps PATH for its runtime DLLs."
  [artifact platform {:keys [no-smoke? test? static-link?]}]
  (when (and (not no-smoke?) (= platform (host-platform)))
    (let [launcher (fs/path (fs/parent artifact) (str (fs/file-name artifact) ".sh"))
          cmd (if (and (build/termux?) (fs/exists? launcher))
                (str launcher)
                (str artifact))
          dir (temp-run-dir)
          run (let [agent-dir (prepare-smoke-agent-dir dir)]
                (fn [& args]
                  (apply p/sh {:continue true :out :string :err :string
                               :dir (str dir)
                               :extra-env (cond-> {"JOLT_PWD" (str dir)
                                                   "KMET_CODING_AGENT_DIR" agent-dir}
                                            (and (windows-platform? platform) static-link?)
                                            (assoc "PATH" (windows-smoke-path)))}
                         cmd args)))]
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
            (println "smoke test:" cmd "--debug" (str/join " " smoke-args))
            (let [res (apply run "--debug" smoke-args)]
              (if (zero? (:exit res))
                (do
                  (println "smoke test passed:" (count (str/split-lines (:out res))) "models listed")
                  (let [log-file (str (fs/path dir "debug.log"))
                        log-text (if (fs/regular-file? log-file) (slurp log-file) "")
                        missing-native-logs (remove #(str/includes? log-text %)
                                                    bundled-native-log-lines)]
                    (if (empty? missing-native-logs)
                      (println "smoke test passed: bundled directory and single-file resources loaded through Jolt's native mapped roots")
                      (do (binding [*out* *err*]
                            (println "debug log:" log-text)
                            (println "missing native load lines:" missing-native-logs))
                          (throw (ex-info (str "smoke test failed for " artifact
                                               " — bundled resources did not load through Jolt's native mapped roots")
                                          {:type ::smoke-failed :exit (:exit res)
                                           :missing missing-native-logs}))))))
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

   Build kmet's Jolt executable into dist/: one native binary with the
   runtime, clojure.core, the stdlib, the dependencies and kmet itself
   compiled in (the entry is kmet.core). A static build is self-contained; a
   dynamic one needs its native libraries on the host.

   --test builds the compiled test runner instead: the same pipeline with the
   generated kmet.tasks.test-main entry, every test namespace statically
   required into the image. The artifact is
   kmet-test-<ver>-jolt<jv>-<platform> and takes `--test` / `--test-ext`
   [filters] — the same slow/fast split as `bb test` / `bb test-ext`, run as
   compiled code.

   This is the Jolt half of the project's `dist` task: `bb dist` builds the
   same app for the babashka host and names its artifact for the same
   platform, so one dist/ can carry both hosts' binaries side by side.

   Native libraries default to static linking on Windows and dynamic linking
   on every other platform (Linux/WSL, macOS, Termux). `--static` and
   `--dynamic` override that default; they are mutually exclusive. A static
   build stages `libcrypto.a` and `libssl.a` from `cc` and verifies Jolt's
   generated loads. Windows static builds additionally need MSYS2's `gcc`,
   OpenSSL, and lz4 packages and no OpenSSL DLL beside the artifact; a dynamic
   build needs its platform's native libraries on the host at run time.

   Options:
     --dev                     unoptimized build, quickest to produce; vars
                               stay redefinable (a development build)
     --opt                     optimized build: smaller and faster, but the
                               binary cannot render Clojure backtraces
                               (the default is a release build: the same
                               optimizations, with backtraces)
     --closed-world            drop definitions unreachable from the entry
                               point (alias: --tree-shake) — a smaller binary
     --static                  link :jolt/native archives into the binary.
                               Default on Windows; requires `cc` and matching
                               static OpenSSL archives on any platform
     --dynamic                 load :jolt/native libraries at run time (the
                               default everywhere except Windows). The binary
                               then needs those libraries where it runs and
                               is no longer self-contained; the build lists them
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
                               env var). A static native target must be the
                               current host platform; Jolt cannot yet link
                               target-architecture archives from another host
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
                               an empty directory with a temporary agent dir,
                               list the models, assert bundled directory
                               and single-file resources used native mapped
                               roots, and check --version against the version
                               baked in. A Windows static build also runs with
                               PATH reduced to System32, so a missed static
                               OpenSSL link cannot be masked by the build host
                               — the check to run before publishing. A --test
                               artifact runs `--test kmet.libs.test-num`
                               instead (proving the suite is compiled in). A
                               cross build skips it (only this host's own
                               platform can run here)
     --no-smoke                skip that runtime verification (the default, so
                               a plain `jolt dist` does not launch the artifact)
     --jolt PATH               the jolt executable that performs the compile
                               (default: jolt from PATH)
     -h, --help                this text

   Examples:
     jolt dist                          release build for this machine
     jolt dist --smoke                  ... and verify the artifact runs
     jolt dist --dynamic                force runtime native loading
     jolt dist --static                 force archive linking
     jolt dist --test                   compiled test runner (release)
     jolt dist --test --smoke           ... and smoke-run its suite
     jolt dist --dev -o dist/kmet-dev   quick development build, own path
     jolt dist --boot small             smallest artifact to ship
     jolt dist --target tarm64le --target-pack ~/packs/tarm64le
                                        cross build for linux-aarch64

   Artifacts land in dist/ as kmet-<ver>-jolt<jv>-<platform>[-dev][.exe]
   (kmet-test-<ver>-... for --test builds). On a Termux host the build also
   writes a .sh launcher next to a glibc-linked binary (run that instead of
   the binary); a jolt linked with the bionic cc runs directly, and a cross
   build gets none either."
  [& args]
  (let [{:keys [mode native-link target target-pack out force? no-smoke? jolt help? test?] :as opts}
        (parse-args args)]
    (when help?
      (println (:doc (meta #'-main)))
      (System/exit 0))
    (let [jolt-bin (or jolt "jolt")
          jver (jolt-version)
          platform (target-platform target)
          link-mode (native-link-mode platform native-link)
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
      (validate-build! platform target link-mode)
      (println (format "kmet%s %s | jolt %s | %s | %s%s"
                       (if test? "-test" "") ver jver platform (name link-mode)
                       (if (= mode "release") "" (str " | " mode))))
      (when force?
        (println "clearing scratch:" (str (fs/parent bin)))
        (fs/delete-tree (fs/parent bin)))
      (fs/create-dirs (fs/parent bin))
      (when test?
        (println "generating" (str (build/generate-test-main!))))
      ;; bundled extensions are embedded through the staged root deps.edn
      ;; lists ("target/kmet-bundled"); staging
      ;; validates the manifest first, so a broken bundle fails the build
      (build/stage-bundled-extensions!)
      (when (= :static link-mode)
        (ensure-static-native-libs! platform))
      ;; Jolt's recursive mkdir reaches a #f parent for a drive-rooted
      ;; Windows scratch path when <out>.build is absent; precreate it for
      ;; both native link modes.
      (when (windows-platform? platform)
        (fs/create-dirs (fs/path (str bin ".build"))))
      ;; the version resource the build bakes in (kmet --version reports it);
      ;; removed again afterwards so a later direct `jolt build` cannot bake a
      ;; previous run's version from the leftover file
      (let [baked (bake-version!)]
        (try
          (run-jolt-build! jolt-bin
                           (build-argv (assoc opts
                                              :out (str (fs/absolutize bin))
                                              :native-link link-mode))
                           (if (and (windows-platform? platform)
                                    (= :static link-mode))
                             (windows-static-build-env platform)
                             {}))
          (finally
            (fs/delete-if-exists baked))))
      (when-not (fs/exists? bin)
        (throw (ex-info (str "jolt build reported success but " bin " is missing")
                        {:type ::no-binary :path (str bin)})))
      (when (= :static link-mode)
        (verify-static-native-build! bin (project-natives)))
      (assemble! bin artifact platform)
      (when-let [launcher (write-launcher! artifact platform)]
        (println "launcher:" (str launcher)))
      (smoke-test! artifact platform
                   {:no-smoke? no-smoke?
                    :test? test?
                    :static-link? (= :static link-mode)})
      (println "built:" (str artifact)))))
