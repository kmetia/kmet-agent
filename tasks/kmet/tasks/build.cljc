(ns kmet.tasks.build
  "Build self-contained kmet executables (the babashka host's half of the
   `dist` task — `jolt dist` runs kmet.tasks.build-jolt instead).
   A binary is the official babashka release binary with target/kmet.jar (an
   uberjar of src + runtime deps) appended — babashka detects the appended zip
   at startup and runs the uberjar's -main (babashka wiki: Self-contained
   executable). One artifact per dist platform, named by the same scheme the
   jolt packager uses — kmet-<ver>-bb<bb-ver>-<platform>; cross-builds work
   from any host because packaging is just download + concat.

   Termux/Android: the glibc bb binary must be exec'd through the termux glibc
   dynamic linker, which also breaks bb's own appended-jar detection
   (/proc/self/exe resolves to ld-linux). For a termux host we therefore emit a
   companion launcher script that unsets LD_PRELOAD, execs via
   $PREFIX/glibc/lib/ld-linux-*.so.1 and passes --jar <self> explicitly.

   bb-only: packaging runs on babashka.classpath and java.util.zip, which the
   jolt host does not provide — the entry points (uberjar*, -main,
   pack-extension!) fail fast with ::bb-only under jolt, where the packager is
   kmet.tasks.build-jolt (jolt AOT-compiles instead of appending an uberjar), see
   jolt-port.md M5/M6."
  (:require #?@(:bb [[babashka.classpath :as bcp]])
            [babashka.fs :as fs]
            [babashka.process :as p]
            [kmet.libs.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.libs.archive :as archive]
            [kmet.libs.http :as http]
            [kmet.libs.version :as version-lib]))

(def ^:private gh-api-url
  "https://api.github.com/repos/babashka/babashka/releases/latest")

(def ^:private cache-dir "target/build-cache")
(def ^:private dist-dir "dist")
(def ^:private jar-path "target/kmet.jar")
(def ^:private main-class "kmet.core")
(def ^:private test-jar-path "target/kmet-test.jar")
(def ^:private test-main-class "kmet.tasks.test-main")
(def ^:private test-entry-root
  "Source root the packagers generate the kmet-test entry under
   (generate-test-main!). A target/ scratch root — nothing here is shipped
   from the checkout, and `bb clean` removes it. deps.edn's :kmet-test alias
   puts the same root on a `jolt dist --test` build's classpath."
  "target/kmet-test-entry")
(def ^:private test-entry-file "target/kmet-test-entry/kmet/tasks/test_main.clj")

;; bundled extensions: the committed manifest and the
;; build-time staging root the packagers embed/walk
(def ^:private bundled-manifest-path
  "The committed manifest, read straight from the checkout (it is inside
   src/, so the runtime sees it as a classpath resource in every mode)."
  "src/kmet/bundled-extensions/manifest.edn")
(def ^:private bundled-staging-root
  "Build-time staging root the packagers embed/walk as an extra root: its
   tree is exactly the runtime's resource keys,
   extensions/<manifest :root>/…"
  "target/kmet-bundled")
(def ^:private bundled-resource-prefix
  "Resource prefix the staged tree is keyed under — the repo's extensions/
   directory, so a resource path matches the checkout path
   (extensions/<manifest :root>)."
  "extensions")

(defn- bb-only!
  "Throw ::bb-only when invoked under the jolt host. kmet.tasks.build is the
   babashka packaging pipeline (babashka.classpath classpath, java.util.zip
   uberjar); jolt has neither, and `jolt build` packages a self-contained
   image instead — callers on jolt get a fast, explicit failure rather than
   an unresolved-var or zip-ctor crash."
  [what]
  (when (boolean (find-var 'clojure.core/*jolt-version*))
    (throw (ex-info (str what " is bb-only — the jolt host has no classpath/zip machinery")
                    {:type ::bb-only}))))

;; ─── Target table ──────────────────────────────────────────────────────────
;;
;; Keyed by dist platform — the os-arch name both packagers stamp on
;; artifacts (kmet-<ver>-bb<bb-ver>-<platform> here,
;; kmet-<ver>-jolt<jv>-<platform> in the jolt packager), so one dist/ holds
;; both hosts' artifacts for a machine under one platform string.
;; :asset    babashka release asset slug backing the platform
;;           (babashka-<version>-<asset>.(tar.gz|zip) + .sha256 sibling). The
;;           names differ for linux: every linux platform is built from the
;;           statically linked release, which runs on glibc and musl alike —
;;           babashka's glibc-dynamic linux-amd64 asset is not used.
;; :ext      archive kind to extract
;; :bin-name executable name inside the archive
;; :linker   glibc loader file name, for the termux launcher script

(def ^:private target-table
  {"linux-aarch64" {:asset "linux-aarch64-static"
                    :ext :tar.gz :bin-name "bb" :linker "ld-linux-aarch64.so.1"}
   "linux-amd64"   {:asset "linux-amd64-static"
                    :ext :tar.gz :bin-name "bb" :linker "ld-linux-x86-64.so.2"}
   "macos-aarch64" {:asset "macos-aarch64" :ext :tar.gz :bin-name "bb"}
   "macos-amd64"   {:asset "macos-amd64" :ext :tar.gz :bin-name "bb"}
   "windows-amd64" {:asset "windows-amd64" :ext :zip :bin-name "bb.exe"}})

(defn platform-for
  "Dist platform name for an os.name/os.arch pair (case-insensitive), or nil
   when the pair is not one we name. This is the platform vocabulary both
   packagers share — the same machine yields the same string on babashka and
   jolt, so their artifacts differ only by the host in the name."
  [os arch]
  (let [os (str/lower-case (str os))
        arch (str/lower-case (str arch))
        os (cond (str/includes? os "linux") "linux"
                 (or (str/includes? os "mac")
                     (str/includes? os "darwin")) "macos"
                 (str/includes? os "windows") "windows"
                 :else nil)
        arch (cond (#{"aarch64" "arm64"} arch) "aarch64"
                   (#{"amd64" "x86_64"} arch) "amd64"
                   :else nil)]
    (when (and os arch)
      (str os "-" arch))))

(defn normalize-target
  "Accept a dist platform (linux-amd64) or the babashka release asset backing
   it (linux-amd64-static) and return the platform; nil when unknown."
  [target]
  (cond
    (contains? target-table target) target
    :else (some (fn [[platform {:keys [asset]}]]
                  (when (= asset target) platform))
                target-table)))

(defn host-target
  "Target-table platform for the machine we're running on, or :unknown-platform
   when it is not a babashka platform we package."
  []
  (let [platform (platform-for (System/getProperty "os.name")
                               (System/getProperty "os.arch"))]
    (if (contains? target-table platform)
      platform
      :unknown-platform)))

(defn termux?
  "True when running under Termux (Android)."
  []
  (str/includes? (str (System/getenv "PREFIX")) "/com.termux/"))

;; ─── Version ───────────────────────────────────────────────────────────────

(defn version
  "Artifact version string: kmet.libs.version/artifact-version (jolt's
   checkout rule, `v` stripped) — the base version artifact names carry."
  []
  (version-lib/artifact-version))

(defn artifact-base
  "Dist artifact base name without extension:
   kmet-<ver>-bb<bb-ver>-<platform>, or kmet-test-... for a --test build."
  ([ver bb-ver platform] (artifact-base ver bb-ver platform {}))
  ([ver bb-ver platform {:keys [test?]}]
   (str (if test? "kmet-test-" "kmet-") ver "-bb" bb-ver "-" platform)))

;; ─── Downloading & extraction ──────────────────────────────────────────────

(defn- asset-url [version asset ext]
  (format "https://github.com/babashka/babashka/releases/download/v%s/babashka-%s-%s.%s"
          version version asset (name ext)))

(defn- latest-bb-version
  "Latest babashka release version from the GitHub API, e.g. \"1.13.219\"."
  []
  (let [out (:body (http/get gh-api-url {}))
        tag (:tag_name (json/parse-string out true))
        v (str/replace (str tag) #"^v" "")]
    (when-not (seq v)
      (throw (ex-info "GitHub API returned no tag_name" {:type ::bad-release})))
    v))

(defn- sha256
  "Hex sha256 digest of a file, streamed."
  [path]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream (fs/file path))]
      (loop [n (.read in buf)]
        (when (pos? n)
          (.update md buf 0 n)
          (recur (.read in buf)))))
    (->> (.digest md) (map #(format "%02x" %)) (str/join))))

(defn- download!
  "Download url to dest via streamed http/get (temp file, then atomic
   move — GitHub release assets are served through redirects, which the
   wrapper follows by default)."
  [url dest]
  (fs/create-dirs (fs/parent dest))
  (let [tmp (str dest ".part")
        resp (http/get url {:as :stream})]
    (try
      (with-open [in (:body resp)]
        (io/copy in (fs/file tmp)))
      (fs/move (fs/path tmp) (fs/path dest) {:replace-existing true})
      (finally
        (when (fs/exists? tmp) (fs/delete tmp))
        ;; reap the transport (curl: untrack pid + delete temp files) —
        ;; a mid-stream cut surfaces here as a transport error
        (http/close! resp)))))

(defn- extract-archive!
  "Extract a .tar.gz (tar CLI) or .zip (kmet.libs.archive, so windows
   targets unpack without unzip installed) into dir; returns path of the bb
   executable inside."
  [{:keys [ext bin-name]} archive dir]
  (fs/create-dirs dir)
  (case ext
    :tar.gz (p/shell "tar" "xzf" (str archive) "-C" (str dir))
    :zip (archive/extract-zip! archive dir))
  (let [bin (fs/path dir bin-name)]
    (when-not (fs/exists? bin)
      (throw (ex-info (str "archive did not contain " bin-name)
                      {:type ::bad-archive :archive (str archive)})))
    bin))

(defn- ensure-bb-binary!
  "Path of the extracted bb binary for [bb-version target], downloading and
   sha256-verifying the target platform's babashka release asset into the build
   cache when not already there."
  [bb-version {:keys [asset ext] :as target}]
  (let [asset-file (str "babashka-" bb-version "-" asset "." (name ext))
        archive (fs/path cache-dir bb-version asset-file)
        dir (fs/path cache-dir bb-version asset)
        bin (fs/path dir (get {:tar.gz "bb" :zip "bb.exe"} ext))]
    (if (fs/exists? bin)
      (println "cached:" asset-file)
      (do
        (print "downloading" asset-file "...") (flush)
        (download! (asset-url bb-version asset ext) archive)
        (download! (str (asset-url bb-version asset ext) ".sha256") (str archive ".sha256"))
        (let [actual (sha256 archive)
              expected (first (str/split (slurp (str archive ".sha256")) #"\s+"))]
          (when-not (= actual expected)
            (throw (ex-info (str "sha256 mismatch for " asset-file)
                            {:type ::checksum-mismatch :expected expected :actual actual}))))
        (extract-archive! target archive dir)
        (println "ok")))
    bin))

(defn generate-test-main!
  "Write the generated entry namespace for a `dist --test` artifact under
   target/kmet-test-entry/ (deps.edn's :kmet-test alias puts that root on a
   jolt --test build's classpath) and return the file path. The entry derives
   its static requires from kmet.tasks.runner/all-namespaces — under :jolt
   only, so jolt's require-closure walk pulls the whole suite into the AOT
   image, while on babashka the entry requires just the runner and loads test
   namespaces from the jar dynamically and tolerantly, the way `bb test`
   does. -main dispatches --test/--test-ext onto kmet.tasks.runner/-main."
  []
  (let [test-nss (sort (var-get (requiring-resolve 'kmet.tasks.runner/all-namespaces)))
        src (str "(ns " test-main-class "\n"
                 "  \"Generated by kmet.tasks.build/generate-test-main! — the\n"
                 "   kmet-test entry namespace; do not edit.\"\n"
                 "  (:require [kmet.tasks.runner :as runner]\n"
                 "            #?@(:jolt [\n"
                 (apply str (map #(str "                      " % "\n") test-nss))
                 "                      ]\n"
                 "                      :default [])))\n\n"
                 "(defn -main [& args]\n"
                 "  (let [[flag & filters] args]\n"
                 "    (cond\n"
                 "      (= flag \"--test\") (apply runner/-main false filters)\n"
                 "      (= flag \"--test-ext\") (apply runner/-main true filters)\n"
                 "      (contains? #{\"-h\" \"--help\"} flag)\n"
                 "      (do (println \"usage: kmet-test (--test | --test-ext) [test-var | ns/var | namespace ...]\")\n"
                 "          (System/exit 0))\n"
                 "      :else (do (binding [*out* *err*]\n"
                 "                  (println \"usage: kmet-test (--test | --test-ext) [test-var | ns/var | namespace ...]\"))\n"
                 "                (System/exit 2)))))\n")]
    (fs/create-dirs (fs/parent test-entry-file))
    (spit test-entry-file src)
    (fs/canonicalize test-entry-file)))

;; ─── Uberjar ───────────────────────────────────────────────────────────────

;; forward declaration: stage-bundled-extensions! lives in the bundled-extension
;; section below (it needs strict-ns-for-path / extension-loader-kinds), while
;; the uberjar writers above already stage before walking the extra root
(declare stage-bundled-extensions!)

(defn- write-uberjar!
  "Write JAR (its manifest Main-Class MAIN) in THIS process — no nested bb
   interpreter (a second ~200MB babashka under memory pressure is what gets
   the whole Termux app, tmux server included, killed by Android's
   low-memory killer). Layout: META-INF/MANIFEST.MF, a baked kmet/version.txt
   (what `kmet --version` reports out of a built artifact), every
   .clj/.cljc/.edn file under each of ROOTS (relativized from its root, so a
   generated root under target/ lands at its namespace path), then the
   entries of each dependency jar already on the classpath (the Maven jars —
   data.json for the JSON seam, cljfmt for the format task — aren't
   bb-builtin; keeping all jars is simpler than filtering). OPTIONS
   :extra-roots are walked after the normal roots, every regular file (no
   extension filter — bundled skills and other resources ship too), so the
   `seen` dedupe keeps the normal roots winning any collision. Dependency
   manifests and signatures are skipped so ours wins. Returns the absolute
   jar path."
  ([jar main roots] (write-uberjar! jar main roots nil))
  ([jar main roots {:keys [extra-roots]}]
   (fs/create-dirs (fs/parent jar))
   (let [tmp (str jar ".part")
         seen (volatile! #{})
         ;; path.separator is ";" on Windows and ":" on Unix — the old
         ;; #"::?" split only worked on Unix and glued all Windows
         ;; classpath entries into one string, so no dep jar ever landed in
         ;; the uberjar (data.json etc. were missing)
         dep-jars (->> (str/split (bcp/get-classpath)
                                  (re-pattern (System/getProperty "path.separator")))
                       (filter #(and (str/ends-with? % ".jar")
                                     (not (fs/directory? %)))))]
     (with-open [zos (java.util.zip.ZipOutputStream. (io/output-stream tmp))]
       (.putNextEntry zos (java.util.zip.ZipEntry. "META-INF/MANIFEST.MF"))
       (io/copy (.getBytes (str "Manifest-Version: 1.0\r\n"
                                "Main-Class: " main "\r\n\r\n")) zos)
       (.closeEntry zos)
       ;; the version this artifact is: kmet.core reads it back for
       ;; `kmet --version` (a built binary has no checkout to describe)
       (.putNextEntry zos (java.util.zip.ZipEntry. "kmet/version.txt"))
       (io/copy (.getBytes (str (version-lib/checkout-version) "\n")) zos)
       (.closeEntry zos)
       (doseq [root roots
               p (sort-by str (fs/glob root "**.{clj,cljc,edn}"))]
         (let [rel (str (fs/relativize root p))
               ;; jar entries must use / separators — fs/relativize yields \ on
               ;; Windows, which breaks bb's classpath lookup (kmet/core.clj
               ;; would not resolve from the appended jar)
               entry (str/replace rel "\\" "/")]
           (when-not (contains? @seen entry)
             (vswap! seen conj entry)
             (.putNextEntry zos (java.util.zip.ZipEntry. entry))
             (with-open [in (io/input-stream (fs/file p))]
               (io/copy in zos))
             (.closeEntry zos))))
       ;; bundled-extension staging: every file (skills and other resources
       ;; ship too, hence no extension filter); src/ walks first, so a
       ;; collision keeps the normal root's entry
       (doseq [root extra-roots
               :when (fs/directory? root)
               p (sort-by str (fs/glob root "**"))
               :when (fs/regular-file? p)]
         (let [entry (str/replace (str (fs/relativize root p)) "\\" "/")]
           (when-not (contains? @seen entry)
             (vswap! seen conj entry)
             (.putNextEntry zos (java.util.zip.ZipEntry. entry))
             (with-open [in (io/input-stream (fs/file p))]
               (io/copy in zos))
             (.closeEntry zos))))
       (doseq [j dep-jars]
         (with-open [zf (java.util.zip.ZipFile. (fs/file j))]
           (doseq [e (enumeration-seq (.entries zf))
                   :when (not (.isDirectory e))]
             (let [n (.getName e)]
               (when-not (or (str/starts-with? n "META-INF/")
                             (contains? @seen n))
                 (vswap! seen conj n)
                 (.putNextEntry zos (java.util.zip.ZipEntry. n))
                 (with-open [in (.getInputStream zf e)]
                   (io/copy in zos))
                 (.closeEntry zos)))))))
     (fs/move (fs/path tmp) (fs/path jar) {:replace-existing true})
     (fs/canonicalize jar))))

(defn uberjar*
  "Create target/kmet.jar in THIS process — the app uberjar `bb dist`
   appends: Main-Class kmet.core, every src/ file, then the dependency jars
   and the staged bundled extensions (stage-bundled-extensions!)."
  []
  (bb-only! "kmet.tasks.build/uberjar*")
  (stage-bundled-extensions!)
  (write-uberjar! jar-path main-class ["src"]
                  {:extra-roots [bundled-staging-root]}))

(defn test-uberjar*
  "Create target/kmet-test.jar — the jar `bb dist --test` appends:
   Main-Class kmet.tasks.test-main, src/ + tasks/ + test/ and the generated
   test entry (generate-test-main!), plus the staged bundled extensions.
   Everything the runner can require is in the jar; on babashka it still
   loads test namespaces dynamically and tolerantly, the way `bb test` does."
  []
  (bb-only! "kmet.tasks.build/test-uberjar*")
  (generate-test-main!)
  (stage-bundled-extensions!)
  (write-uberjar! test-jar-path test-main-class
                  ["src" "tasks" "test" test-entry-root]
                  {:extra-roots [bundled-staging-root]}))

;; ─── Assembling artifacts ──────────────────────────────────────────────────

(defn- concat-files!
  "Binary-safe append of src files into dest — portable replacement for cat."
  [dest srcs]
  (fs/create-dirs (fs/parent dest))
  (with-open [out (io/output-stream (fs/file dest))]
    (doseq [src srcs]
      (io/copy (io/input-stream (fs/file src)) out))))

(defn- wrapper-script
  "Termux launcher script text: exec through the glibc linker and pass
   --jar <self> explicitly, because bb's appended-jar detection resolves
   /proc/self/exe to the dynamic linker under this invocation style."
  [bin-name linker]
  (format "#!/data/data/com.termux/files/usr/bin/sh
# kmet launcher (Termux): run the glibc babashka binary through the glibc
# dynamic linker; LD_PRELOAD (libtermux-exec) breaks non-bionic executables.
DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)
BIN=\"$DIR/%s\"
LD=\"$PREFIX/glibc/lib/%s\"
[ -x \"$LD\" ] || { echo \"termux glibc package required: pkg install glibc-repo && pkg install glibc\" >&2; exit 1; }
unset LD_PRELOAD
export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"
# `--` stops babashka's own option parsing: `--version` (or any other flag
# name babashka also owns) must reach the jar's main, not babashka, which
# would print its own version and exit.
exec \"$LD\" --library-path \"$PREFIX/glibc/lib\" \"$BIN\" --jar \"$BIN\" -- \"$@\"
"
          bin-name linker))

(defn assemble-one!
  "Produce dist/kmet-<ver>-bb<bb-ver>-<platform>[.exe] (kmet-test-... for a
   --test build): the official babashka binary for platform with the uberjar
   JAR appended. On a termux host, platforms that need the glibc linker also
   get a matching .sh launcher script. Returns the artifact path."
  ([ver platform target bb-ver] (assemble-one! ver platform target bb-ver {}))
  ([ver platform {:keys [linker] :as target} bb-ver {:keys [test? jar]
                                                     :or {jar jar-path}}]
   (let [bb-bin (ensure-bb-binary! bb-ver target)
         base (artifact-base ver bb-ver platform {:test? test?})
         windows? (str/starts-with? platform "windows")
         artifact (fs/path dist-dir (cond-> base windows? (str ".exe")))]
     (println "building" (str artifact))
     (concat-files! artifact [bb-bin (fs/path jar)])
     (when-not windows?
       (fs/set-posix-file-permissions artifact "rwxr-xr-x"))
     (when (and (termux?) linker)
       (let [w (fs/path dist-dir (str base ".sh"))]
         (spit (str w) (wrapper-script (fs/file-name artifact) linker))
         (fs/set-posix-file-permissions w "rwxr-xr-x")
         (println "launcher:" (str w))))
     artifact)))

(defn- temp-run-dir
  "A throwaway dir to run an artifact from, on the platform's real temp root
   (java.io.tmpdir is unreliable on Termux: it hardcodes /tmp)."
  []
  (let [root (or (not-empty (str (System/getenv "TMPDIR")))
                 (System/getProperty "java.io.tmpdir"))
        dir (fs/path root "kmet-build-bb")]
    (fs/create-dirs dir)
    (fs/create-temp-dir {:dir dir :prefix "smoke-"})))

(defn- smoke-test!
  "Run the freshly built current-host artifact. An app artifact: --list-models
   must list the embedded catalogs (exit 0), and --version must report the
   version the artifact was built as — the baked kmet/version.txt, not a
   checkout. A --test artifact: `--test kmet.libs.test-num` must run that
   namespace from an empty work dir (exit 0 and the Testing header), proving
   the runner and its tests travel in the artifact. Skipped for cross-built
   platforms."
  ([artifact platform] (smoke-test! artifact platform {}))
  ([artifact platform {:keys [test?]}]
   (when (= (host-target) platform)
     (let [launcher (fs/path (fs/parent artifact)
                             (str (fs/file-name artifact) ".sh"))
           cmd (str (fs/canonicalize (if (and (termux?) (fs/exists? launcher))
                                       launcher
                                       artifact)))]
       (if test?
         (let [dir (temp-run-dir)
               run (fn [& args]
                     (apply p/shell {:out :string :err :string :continue true
                                     :dir (str dir)} cmd args))]
           (try
             (println "smoke test:" cmd "--test kmet.libs.test-num")
             (let [res (run "--test" "kmet.libs.test-num")]
               (if (and (zero? (:exit res))
                        (str/includes? (:out res) "Testing kmet.libs.test-num"))
                 (println "smoke test passed: the packaged runner ran kmet.libs.test-num")
                 (do (println (:err res))
                     (throw (ex-info (str "smoke test failed for " artifact
                                          " — the artifact did not run the test runner")
                                     {:type ::smoke-failed :exit (:exit res)})))))
             (finally
               (fs/delete-tree dir))))
         (let [run (fn [& args]
                     (apply p/shell {:out :string :err :string :continue true} cmd args))]
           (println "smoke test:" cmd "--list-models")
           (let [res (run "--list-models")]
             (if (zero? (:exit res))
               (println "smoke test passed:" (count (str/split-lines (:out res))) "models listed")
               (do (println (:err res))
                   (throw (ex-info (str "smoke test failed for " artifact)
                                   {:type ::smoke-failed :exit (:exit res)})))))
           (println "smoke test:" cmd "--version")
           (let [res (run "--version")
                 reported (str/trim (:out res))
                 expected (str "kmet " (version-lib/checkout-version))]
             (if (and (zero? (:exit res)) (= reported expected))
               (println "smoke test passed:" reported)
               (do (println (:err res))
                   (throw (ex-info (str "smoke test failed for " artifact
                                        " — --version said " (pr-str reported)
                                        ", expected " (pr-str expected))
                                   {:type ::smoke-failed :exit (:exit res)
                                    :version reported :expected expected})))))))))))

;; ─── Extension packaging ──────────────────────────────────────────────────

(defn- strict-ns-for-path
  "The namespace symbol a strict-layout .clj file at REL (slash-separated,
   relative to the artifact root) must declare (dashes, dots for slashes),"
  [rel]
  (symbol (-> rel
              (str/replace #"\.(cljc|clj|bb)$" "")
              (str/replace "_" "-")
              (str/replace "/" "."))))

(def ^:private extension-loader-kinds
  "Loader backend kinds an extension manifest may declare — the runtime's
   selection vocabulary (kmet.app.extensions/select-loader-kind)."
  #{:sci :jolt})

(defn- pack-verify!
  "Verify SRC-DIR is a packable extension artifact root; throw ex-info
   with :type ::pack-error otherwise. Checks: extension.edn present with
   :name + symbol :entry + a :loader vector of known backend kinds; the
   :entry ns-path file exists; every .clj/.cljc/.bb file's (ns ...) matches
   its path (strict layout); deps.edn parses and carries only :deps.
   Returns {:name :entry-ns}."
  [src-dir]
  (let [root (fs/canonicalize src-dir)
        manifest (io/file (str root) "extension.edn")]
    (when-not (fs/regular-file? manifest)
      (throw (ex-info (str "no extension.edn in " src-dir) {:type ::pack-error})))
    (let [m (edn/read-string (slurp manifest))
          entry-ns (:entry m)
          loaders (:loader m)]
      (when-not (and (:name m) (symbol? entry-ns))
        (throw (ex-info (str "extension.edn needs :name + symbol :entry, got: " (pr-str m))
                        {:type ::pack-error :manifest m})))
      (when-not (and (sequential? loaders)
                     (seq loaders)
                     (every? extension-loader-kinds loaders))
        (throw (ex-info (str "extension.edn needs :loader — a non-empty vector of "
                             (vec (sort extension-loader-kinds)) " — got: " (pr-str loaders))
                        {:type ::pack-error :manifest m})))
      (let [base (str/replace (namespace-munge (str entry-ns)) "." "/")
            entry-file (some (fn [ext]
                               (let [f (io/file (str root) (str base ext))]
                                 (when (.exists f) f)))
                             [".cljc" ".clj" ".bb"])]
        (when-not entry-file
          (throw (ex-info (str "extension.edn :entry not found: " entry-ns)
                          {:type ::pack-error :entry entry-ns}))))
      (doseq [f (sort-by str (mapcat #(fs/glob root (str "**." %)) ["clj" "cljc" "bb"]))]
        (let [rel (str/replace (str (fs/relativize root f)) "\\" "/")
              expected (strict-ns-for-path rel)
              actual (with-open [r (java.io.PushbackReader. (io/reader (fs/file f)))]
                       (second (read r)))]
          (when-not (= expected actual)
            (throw (ex-info (str "strict layout violation: " rel
                                 " declares " actual ", expected " expected)
                            {:type ::pack-error :file rel})))))
      (let [deps-file (io/file (str root) "deps.edn")]
        (when (fs/regular-file? deps-file)
          (let [deps (edn/read-string (slurp deps-file))]
            (when-not (map? deps)
              (throw (ex-info "deps.edn must be an EDN map" {:type ::pack-error})))
            (when (seq (dissoc deps :deps))
              (binding [*out* *err*]
                (println "Warning: deps.edn keys besides :deps are ignored:"
                         (pr-str (keys (dissoc deps :deps)))))))))
      {:name (:name m) :entry-ns entry-ns})))

(defn pack-extension!
  "Verify SRC-DIR (an extension artifact root) and zip it to OUT-PATH
   (default <name>.jar in the cwd). Deterministic sorted order, / entry
   separators, no META-INF. Returns the output path string."
  [src-dir & [out-path]]
  (bb-only! "kmet.tasks.build/pack-extension!")
  (let [{:keys [name]} (pack-verify! src-dir)
        root (fs/canonicalize src-dir)
        out (str (or out-path (str name ".jar")))]
    (fs/create-dirs (fs/parent (fs/canonicalize out)))
    (with-open [zos (java.util.zip.ZipOutputStream. (io/output-stream out))]
      (doseq [f (sort-by str (filter #(fs/regular-file? %) (fs/glob root "**")))]
        (let [rel (str/replace (str (fs/relativize root f)) "\\" "/")]
          (when (or (str/starts-with? rel "/")
                    (some #(= ".." %) (str/split rel #"/")))
            (throw (ex-info (str "unsafe entry name: " rel) {:type ::pack-error})))
          (.putNextEntry zos (java.util.zip.ZipEntry. rel))
          (with-open [in (io/input-stream (fs/file f))]
            (io/copy in zos))
          (.closeEntry zos))))
    (println "packed" out)
    out))

;; ─── Bundled extensions ────────────────────────────────────────────────────

(defn- bundle-error!
  [msg]
  (throw (ex-info msg {:type ::bundle-error})))

(defn- discover-bundled-roots
  "Every shippable artifact root under DIR (default: the checkout's
   extensions/), relative to it: top-level .clj files and <name>/src
   directories carrying extension.edn. Development wrappers outside an
   artifact root are not artifacts."
  ([] (discover-bundled-roots "extensions"))
  ([dir]
   (let [d (io/file dir)]
     (when-not (fs/directory? d)
       (bundle-error! (str "no " dir " directory at " (fs/cwd))))
     (into #{}
           (keep (fn [entry]
                   (let [path (str entry)
                         name (fs/file-name path)]
                     (cond
                       (and (fs/regular-file? entry) (str/ends-with? path ".clj")) name
                       (and (fs/directory? entry)
                            (fs/exists? (io/file path "src" "extension.edn")))
                       (str name "/src")
                       :else nil))))
           (fs/list-dir d)))))

(defn- validate-bundled-dir!
  "D8 checks for one :dir artifact root: extension.edn with :name + symbol
   :entry + a :loader vector that includes :sci, every source file's (ns ...)
   matching its strict path, and a deps.edn (when present) carrying only
   :deps with no :local/root."
  [name root]
  (let [manifest-file (io/file root "extension.edn")]
    (when-not (fs/regular-file? manifest-file)
      (bundle-error! (str "bundled extension " name ": no extension.edn in " root)))
    (let [m (edn/read-string (slurp manifest-file))
          entry-ns (:entry m)
          loaders (:loader m)]
      (when-not (and (:name m) (symbol? entry-ns))
        (bundle-error! (str "bundled extension " name
                            ": extension.edn needs :name + symbol :entry, got " (pr-str m))))
      (when-not (and (sequential? loaders) (seq loaders)
                     (every? extension-loader-kinds loaders))
        (bundle-error! (str "bundled extension " name
                            ": extension.edn needs a non-empty :loader vector of "
                            (vec (sort extension-loader-kinds)))))
      (when-not (some #{:sci} loaders)
        (bundle-error! (str "bundled extension " name
                            ": :loader must include :sci — bundled resource artifacts
   load through the SCI backend until the Jolt embedded-root feature exists")))
      (let [base (str/replace (namespace-munge (str entry-ns)) "." "/")
            entry-file (some (fn [ext]
                               (let [f (io/file (str root) (str base ext))]
                                 (when (.exists f) f)))
                             [".cljc" ".clj" ".bb"])]
        (when-not entry-file
          (bundle-error! (str "bundled extension " name ": :entry not found: " entry-ns))))
      (doseq [f (sort-by str (mapcat #(fs/glob root (str "**." %)) ["clj" "cljc" "bb"]))]
        (let [rel (str/replace (str (fs/relativize root f)) "\\" "/")
              expected (strict-ns-for-path rel)
              actual (with-open [r (java.io.PushbackReader. (io/reader (fs/file f)))]
                       (second (read r)))]
          (when-not (= expected actual)
            (bundle-error! (str "bundled extension " name " strict layout violation: " rel
                                " declares " actual ", expected " expected)))))
      (let [deps-file (io/file root "deps.edn")]
        (when (fs/regular-file? deps-file)
          (let [deps (edn/read-string (slurp deps-file))]
            (when-not (map? deps)
              (bundle-error! (str "bundled extension " name ": deps.edn must be an EDN map")))
            (when-not (= #{:deps} (set (keys deps)))
              (bundle-error! (str "bundled extension " name
                                  ": deps.edn may only carry :deps, got " (pr-str (keys deps)))))
            (when (some (fn [[_ spec]]
                          (and (map? spec)
                               (or (contains? spec :local/root)
                                   (contains? spec :local-root))))
                        (:deps deps))
              (bundle-error! (str "bundled extension " name
                                  ": deps.edn must not use :local/root — a bundled artifact
   has no stable root in a binary")))))))))

(defn- validate-bundled-file!
  "A :file artifact is a single-file extension: the file must exist and
   start with an (ns ...) form."
  [name root]
  (when-not (fs/regular-file? (io/file root))
    (bundle-error! (str "bundled extension " name ": missing file " root)))
  (let [form (with-open [r (java.io.PushbackReader. (io/reader (fs/file root)))]
               (read r))]
    (when-not (and (list? form) (= 'ns (first form)))
      (bundle-error! (str "bundled extension " name ": " root
                          " does not start with an (ns ...) form")))))

(defn validate-bundled-extensions!
  "Validate the committed bundled-extensions manifest against the checkout:
   every artifact under extensions/ is listed (minus :exclude), every
   listed root exists and passes its kind's D8 checks (see
   validate-bundled-dir! / validate-bundled-file!). Returns the parsed
   manifest; throws ex-info with :type ::bundle-error on any violation.
   Shared by bb check-bundled-extensions, the packagers' staging step and
   the test gate — offline and side-effect free."
  []
  (let [manifest (edn/read-string (slurp bundled-manifest-path))
        entries (:artifacts manifest)
        listed (set (map :root entries))
        excluded (set (:exclude manifest))]
    (doseq [root (sort (discover-bundled-roots))]
      (when-not (or (contains? listed root) (contains? excluded root))
        (bundle-error! (str "extensions/" root " is not listed in " bundled-manifest-path
                            " — add it to :artifacts or :exclude"))))
    (doseq [{:keys [name kind root]} entries
            :let [path (str (fs/path "extensions" root))]]
      (when-not (contains? #{:dir :file} kind)
        (bundle-error! (str "bundled extension " name ": :kind must be :dir or :file")))
      (when (contains? excluded root)
        (bundle-error! (str "bundled extension " name ": listed and excluded at once (" root ")")))
      (case kind
        :dir (validate-bundled-dir! name path)
        :file (validate-bundled-file! name path)))
    manifest))

(defn check-bundled-extensions!
  "bb check-bundled-extensions — validate the bundle without building.
   Prints the artifact count; throws on the first violation."
  []
  (let [m (validate-bundled-extensions!)]
    (println "Bundled extensions OK:" (count (:artifacts m)) "artifact(s)")
    (count (:artifacts m))))

(defn stage-bundled-extensions!
  "Validate the bundle, then stage it for the artifacts: every file of
   every artifact root is copied to
   target/kmet-bundled/extensions/<root>/…, which is exactly
   the resource-key layout the runtime probes (see
   kmet.app.bundled-extensions). The staging root is the extra root the bb
   uberjar walks and the jolt :jolt/build :embed list embeds; deleting it
   first keeps stale files out. Returns the staging root path. Host-neutral
   (fs + slurp/spit), so `jolt dist` can share it."
  []
  (let [manifest (validate-bundled-extensions!)
        dest-root (fs/path bundled-staging-root)]
    (when (fs/exists? dest-root)
      (fs/delete-tree dest-root))
    (doseq [{:keys [kind root]} (:artifacts manifest)
            :let [src (fs/path "extensions" root)
                  dest (fs/path dest-root bundled-resource-prefix root)]]
      (if (= kind :dir)
        (doseq [f (filter fs/regular-file? (fs/glob src "**"))]
          (let [target (fs/path dest (fs/relativize src f))]
            (fs/create-dirs (fs/parent target))
            (fs/copy f target {:replace-existing true})))
        (do (fs/create-dirs (fs/parent dest))
            (fs/copy src dest {:replace-existing true}))))
    (println "staged bundled extensions in" (str dest-root))
    (str dest-root)))

;; ─── CLI ───────────────────────────────────────────────────────────────────

(defn parse-args
  "CLI args => {:targets [...] :all? :force? :no-smoke? :test? :help?}.
   Unknown targets/options throw ex-info with :type ::usage."
  [args]
  (loop [args args
         opts {:targets [] :all? false :force? false :no-smoke? true
               :test? false :help? false}]
    (if-some [arg (first args)]
      (cond
        (= "--all" arg) (recur (rest args) (assoc opts :all? true))
        (= "--force" arg) (recur (rest args) (assoc opts :force? true))
        (= "--smoke" arg) (recur (rest args) (assoc opts :no-smoke? false))
        (= "--no-smoke" arg) (recur (rest args) (assoc opts :no-smoke? true))
        (= "--test" arg) (recur (rest args) (assoc opts :test? true))
        (= "--help" arg) (recur (rest args) (assoc opts :help? true))
        (str/starts-with? arg "--") (throw (ex-info (str "unknown option: " arg)
                                                    {:type ::usage}))
        :else (if-some [platform (normalize-target arg)]
                (recur (rest args) (update opts :targets conj platform))
                (throw (ex-info (str "unknown target: " arg)
                                {:type ::usage :known (vec (sort (keys target-table)))}))))
      opts)))

(defn -main
  "bb dist [target ...|--all] [--force] [--smoke] [--test]   (the bb.edn
   task's babashka branch; the jolt branch runs kmet.tasks.build-jolt/-main)

   Build self-contained kmet executable(s) in dist/: the official babashka
   release binary with an uberjar appended, named
   kmet-<ver>-bb<bb-ver>-<platform> — the jolt packager's scheme with babashka
   in its slot. --test swaps in the test-runner artifact instead:
   kmet-test-<ver>-bb<bb-ver>-<platform>, Main-Class kmet.tasks.test-main,
   whose --test/--test-ext flags run the packaged suite. Targets are dist
   platforms (linux-aarch64, linux-amd64, macos-aarch64, macos-amd64,
   windows-amd64; a release asset slug like linux-amd64-static is accepted
   too); they default to the current platform. --all builds every published
   platform, --force re-downloads cached babashka binaries, and --smoke runs
   the current-host artifact after building (--list-models plus --version, or
   the packaged test runner for --test; skipped by default to keep local
   dist builds fast and side-effect free). A fresh uberjar
   (target/kmet.jar, or target/kmet-test.jar for --test) is always rebuilt
   first so artifacts never bundle stale sources."
  [& args]
  (bb-only! "kmet.tasks.build/-main (the bb half of the dist task)")
  (let [{:keys [targets all? force? no-smoke? test? help?]} (parse-args args)]
    (when help?
      (println (:doc (meta #'-main)))
      (System/exit 0))
    (when force?
      (when (fs/exists? cache-dir)
        (fs/delete-tree cache-dir)))
    (let [ver (version)
          jar (if test? test-jar-path jar-path)
          _ (do (println "building" (if test? "test uberjar" "uberjar") jar)
                (if test? (test-uberjar*) (uberjar*)))
          bb-ver (latest-bb-version)
          targets (cond
                    all? (sort (keys target-table))
                    (seq targets) targets
                    :else (let [platform (host-target)]
                            (when-not (string? platform)
                              (throw (ex-info "cannot determine host platform; pass explicit targets"
                                              {:type ::usage :reason platform})))
                            [platform]))]
      (println (format "%s %s | babashka %s | targets: %s"
                       (if test? "kmet-test" "kmet") ver bb-ver (str/join ", " targets)))
      (doseq [platform targets]
        (let [artifact (assemble-one! ver platform (get target-table platform) bb-ver
                                      {:test? test? :jar jar})]
          (when-not no-smoke?
            (smoke-test! artifact platform {:test? test?}))))
      (println "done:" dist-dir))))
