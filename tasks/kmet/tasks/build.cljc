(ns kmet.tasks.build
  "Build self-contained kmet executables (the babashka host's half of the
   `dist` task — `jolt dist` runs kmet.tasks.build-jolt instead).
   A binary is the official babashka release binary with target/uberjar-bb.jar
   (an uberjar of src + runtime deps) appended — babashka detects the appended
   zip at startup and runs the uberjar's -main (babashka wiki: Self-contained
   executable). One artifact per dist platform, laid out by the same scheme
   the jolt packager uses: a dist/kmet-<ver>-<platform>.zip holding one
   top-level folder — kmet-<ver>-<platform>/ with the bare kmet executable,
   its Termux launcher when one is written, and the repository LICENSE;
   cross-builds work from any host because packaging is just download + concat.

   Termux/Android: the glibc bb binary must be exec'd through the termux glibc
   dynamic linker, which also breaks bb's own appended-jar detection
   (/proc/self/exe resolves to ld-linux). For a termux host we therefore emit a
   companion launcher script that unsets LD_PRELOAD, execs via
   $PREFIX/glibc/lib/ld-linux-*.so.1 and passes --jar <self> explicitly.

   bb-only: the dist pipeline runs on babashka.classpath, which no other host
   provides — uberjar*, -main and the payload writer fail fast with ::bb-only
   off babashka, where the packagers are kmet.tasks.build-jolt (jolt
   AOT-compiles instead of appending an uberjar) and kmet.tasks.build-jvm (the
   JVM uberjar). The namespace itself loads on every host so those two can
   share the classpath-free halves — pack-extension! (kmet.libs.archive) and
   stage-bundled-extensions! (fs + slurp/spit)."
  (:require #?@(:bb [[babashka.classpath :as bcp]])
            [babashka.fs :as fs]
            [babashka.process :as p]
            [kmet.libs.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.libs.archive :as archive]
            [kmet.libs.host :as host]
            [kmet.libs.http :as http]
            [kmet.app.version :as version-lib]
            [kmet.tasks.modules :as modules]))

(def ^:private gh-api-url
  "https://api.github.com/repos/babashka/babashka/releases/latest")

(def ^:private cache-dir "target/build-cache")
(def ^:private dist-dir "dist")
(def ^:private jar-path "target/uberjar-bb.jar")
(def ^:private main-class "kmet.app.core")
(def ^:private test-jar-path "target/uberjar-bb-test.jar")

(def ^:private test-main-class "kmet.tasks.test-main")

(def ^:private license-path
  "The repository LICENSE shipped inside every dist zip, resolved against the
   repo root the dist task runs in (like dist/ and target/)."
  "LICENSE")

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
  "modules/app/src/kmet/bundled-extensions.edn")
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
  "Throw ::bb-only off the babashka host. kmet.tasks.build's dist pipeline is
   the babashka packager (babashka.classpath uberjar); jolt packages a
   self-contained image (`jolt build`) and the JVM gets kmet.tasks.build-jvm's
   uberjar — callers elsewhere get a fast, explicit failure rather than an
   unresolved-var crash. Functions that need no classpath (pack-extension!,
   stage-bundled-extensions!) do not call it."
  [what]
  (when-not (host/babashka?)
    (throw (ex-info (str what " is bb-only — this host has no "
                         "babashka.classpath")
                    {:type ::bb-only}))))

(defn- bb-classpath
  "The running babashka process's classpath, whose dependency jars the payload
   writer copies in. Reader-conditional because the reference is resolved when
   this namespace is compiled: the other hosts load the namespace for its
   classpath-free halves (stage-bundled-extensions!) and have no
   babashka.classpath at all."
  []
  (bb-only! "kmet.tasks.build/bb-classpath")
  #?(:bb (bcp/get-classpath)
     :default (throw (ex-info "kmet.tasks.build/bb-classpath is bb-only"
                              {:type ::bb-only}))))

;; ─── Target table ──────────────────────────────────────────────────────────
;;
;; Keyed by dist platform — the os-arch name both packagers stamp on the
;; release zips (kmet-<ver>-<platform>.zip here,
;; kmetj-<ver>-<platform>-<link>.zip in the jolt packager), so one dist/ holds
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
  "Artifact version string: kmet.app.version/artifact-version (jolt's
   checkout rule, `v` stripped) — the base version artifact names carry."
  []
  (version-lib/artifact-version))

;; ─── Naming ────────────────────────────────────────────────────────────────
;;
;; Every packager ships one platform-qualified zip in dist/
;; (<base>-<version>-<platform>[-<link>][-dev].zip) holding one top-level
;; folder named after the zip (artifact-dir) with the bare executable — kmet
;; on babashka, kmetj on jolt, kmetc on the JVM, no version in the name — its
;; Termux launcher when one is written, and the repository LICENSE. Each host
;; assembles that executable under target/dist/, and `--out DIR` additionally
;; copies it (with the launcher, but without the folder) into DIR. The names
;; are host-distinct on purpose: all three artifacts can be installed side by
;; side and run as different commands.

(defn windows-platform?
  "True for a dist platform whose executables carry the .exe suffix."
  [platform]
  (str/starts-with? (str platform) "windows-"))

(defn artifact-base
  "Artifact name without version or extension — one name per host, so all
   three artifacts can sit on PATH and run as different commands: kmet on
   babashka (:bb), kmetj on jolt (:jolt), kmetc on the JVM (:jvm, whose zip
   carries `jvm` in the platform slot). It is the name the artifact carries
   inside the zip's top folder and the base of the zip name, with -test for a
   --test build."
  [host {:keys [test?]}]
  (str (case host :bb "kmet" :jvm "kmetc" :jolt "kmetj") (when test? "-test")))

(defn executable-name
  "Executable file name for HOST's artifact on PLATFORM: artifact-base plus
   the .exe suffix on windows platforms. No version: the zip name carries it."
  [host platform opts]
  (str (artifact-base host opts) (when (windows-platform? platform) ".exe")))

(def ^:private staging-root
  "Where each packager assembles the bare executable it zips: a scratch root
   under target/, not dist/ — dist/ carries the released zips, and --out DIR
   copies the executable where the user asks."
  "target/dist")

(defn staged-executable
  "Assembly path of the bare executable for HOST on PLATFORM:
   target/dist/<platform>/kmet[.exe] (kmetj on jolt; the -test runner for a
   --test build). --out DIR copies this file; the zip in dist/ carries it at
   its bare name inside the artifact folder (artifact-dir)."
  [host platform opts]
  (str staging-root "/" platform "/" (executable-name host platform opts)))

(defn artifact-zip
  "The release zip in dist/, platform-qualified:
   <base>-<version>-<platform>[-<link>][-dev].zip — kmet-... on babashka,
   kmetj-... on jolt, kmet-test-/kmetj-test-... for --test builds. A jolt
   build passes its native link mode as :native-link (-static/-dynamic): a
   static artifact is self-contained while a dynamic one needs its native
   libraries on the host, so the two are different artifacts and the name
   must say which is which. The JVM artifact passes `jvm` as the platform:
   one jar runs everywhere, so there is nothing to cross-build and no
   --test/--dev/link variant. The version is kmet's, not the compiling
   host's; -dev marks a jolt --dev build, a different artifact under the same
   sources."
  [host ver platform {:keys [test? dev? native-link]}]
  (str dist-dir "/" (artifact-base host {:test? test?})
       "-" ver "-" platform
       (when native-link (str "-" (name native-link)))
       (when dev? "-dev") ".zip"))

(defn artifact-dir
  "Top-level folder inside the release zip: the zip's own stem, so extracting
   kmet-1.2.3-linux-amd64.zip yields kmet-1.2.3-linux-amd64/ holding the
   executable, its Termux launcher when one is written, and the LICENSE."
  [zip]
  (fs/file-name (fs/strip-ext (fs/path zip))))

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
         dep-jars (->> (str/split (bb-classpath)
                                  (re-pattern (System/getProperty "path.separator")))
                       (filter #(and (str/ends-with? % ".jar")
                                     (not (fs/directory? %)))))]
     (with-open [zos (java.util.zip.ZipOutputStream. (io/output-stream tmp))]
       (.putNextEntry zos (java.util.zip.ZipEntry. "META-INF/MANIFEST.MF"))
       (io/copy (.getBytes (str "Manifest-Version: 1.0\r\n"
                                "Main-Class: " main "\r\n\r\n")) zos)
       (.closeEntry zos)
       ;; the version this artifact is: kmet.app.core reads it back for
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
  "Create target/uberjar-bb.jar in THIS process — the app uberjar `bb dist`
   appends: Main-Class kmet.app.core, every src/ file, then the dependency
   jars and the staged bundled extensions (stage-bundled-extensions!). A
   babashka payload, not a JVM jar: kmet.tasks.build-jvm builds that one."
  []
  (bb-only! "kmet.tasks.build/uberjar*")
  (stage-bundled-extensions!)
  (write-uberjar! jar-path main-class (modules/module-src-paths)
                  {:extra-roots [bundled-staging-root]}))

(defn test-uberjar*
  "Create target/uberjar-bb-test.jar — the jar `bb dist --test` appends:
   Main-Class kmet.tasks.test-main, src/ + tasks/ + test/ and the generated
   test entry (generate-test-main!), plus the staged bundled extensions.
   Everything the runner can require is in the jar; on babashka it still
   loads test namespaces dynamically and tolerantly, the way `bb test` does."
  []
  (bb-only! "kmet.tasks.build/test-uberjar*")
  (generate-test-main!)
  (stage-bundled-extensions!)
  (write-uberjar! test-jar-path test-main-class
                  (into (modules/module-paths)
                        (concat (modules/roots) [test-entry-root]))
                  {:extra-roots [bundled-staging-root]}))

;; ─── Assembling artifacts ──────────────────────────────────────────────────

(defn- concat-files!
  "Binary-safe append of src files into dest — portable replacement for cat."
  [dest srcs]
  (fs/create-dirs (fs/parent dest))
  (with-open [out (io/output-stream (fs/file dest))]
    (doseq [src srcs]
      (io/copy (io/input-stream (fs/file src)) out))))

(defn termux-launcher-script
  "Termux launcher script text: exec BIN-NAME through the glibc LINKER.
   PAYLOAD? marks a babashka payload: it passes --jar <self> explicitly,
   because bb's appended-jar detection resolves /proc/self/exe to the dynamic
   linker under this invocation style, and `--` stops babashka's own option
   parsing. A self-contained binary (jolt) needs neither."
  [bin-name linker payload?]
  (format "#!/data/data/com.termux/files/usr/bin/sh
# kmet launcher (Termux): run the glibc-linked binary through the glibc
# dynamic linker; LD_PRELOAD (libtermux-exec) breaks non-bionic executables.
DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)
BIN=\"$DIR/%s\"
LD=\"$PREFIX/glibc/lib/%s\"
[ -x \"$LD\" ] || { echo \"termux glibc package required: pkg install glibc-repo && pkg install glibc\" >&2; exit 1; }
unset LD_PRELOAD
export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"
%s
exec \"$LD\" --library-path \"$PREFIX/glibc/lib\" \"$BIN\"%s \"$@\"
"
          bin-name linker
          (if payload?
            "# `--` stops babashka's own option parsing: `--version` (or any\n# other flag name babashka also owns) must reach the jar's main."
            "")
          (if payload? " --jar \"$BIN\" --" "")))

(defn assemble-one!
  "Produce the executable for PLATFORM under target/dist/<platform>/kmet[.exe]
   (kmet-test[.exe] for a --test build): the official babashka binary for the
   platform with the uberjar JAR appended. On a termux host, platforms that
   need the glibc linker also get a matching .sh launcher beside it. The zip
   packager and --out consume the returned {:exe path :launcher path-or-nil}."
  [platform {:keys [linker] :as target} bb-ver {:keys [test? jar]}]
  (let [bb-bin (ensure-bb-binary! bb-ver target)
        exe (fs/path (staged-executable :bb platform {:test? test?}))
        launcher (when (and (termux?) linker)
                   (fs/path (fs/parent exe)
                            (str (artifact-base :bb {:test? test?}) ".sh")))]
    (println "building" (str exe))
    (concat-files! exe [bb-bin (fs/path (or jar jar-path))])
    (when (and (not (windows-platform? platform)) (not (fs/windows?)))
      (fs/set-posix-file-permissions exe "rwxr-xr-x"))
    (when launcher
      (spit (str launcher) (termux-launcher-script (fs/file-name exe) linker true))
      (fs/set-posix-file-permissions launcher "rwxr-xr-x")
      (println "launcher:" (str launcher)))
    {:exe exe :launcher launcher}))

(defn pack-artifact!
  "Zip an assembled artifact for distribution: one top-level folder named
   after the zip (artifact-dir) holding the artifact's files and the
   repository LICENSE. An artifact is either one executable plus its optional
   companion launcher ({:exe E :launcher L}) or an explicit :files coll of
   {:name NAME :file FILE :executable? BOOL} whose NAME is relative to the
   folder (the JVM jar ships kmetc.jar with its launchers). In-process
   through kmet.libs.archive/write-zip! — no external zip program — on every
   host. Returns ZIP."
  [zip {:keys [exe launcher files]}]
  (when-not (fs/regular-file? license-path)
    (throw (ex-info (str "dist packaging needs the repository LICENSE at "
                         license-path " — run from the repo root")
                    {:type ::pack-error :path license-path})))
  (let [root (artifact-dir zip)
        files (or files
                  (cond-> [{:name (str (fs/file-name exe))
                            :file (str exe) :executable? true}]
                    launcher (conj {:name (str (fs/file-name launcher))
                                    :file (str launcher) :executable? true})))]
    (archive/write-zip!
     zip
     (conj (mapv #(assoc % :name (str root "/" (:name %))) files)
           {:name (str root "/LICENSE") :file license-path})))
  (println "packed" (str zip))
  zip)

(defn install-artifact!
  "Copy an assembled artifact's executables into DIR — the `--out DIR` half
   of the dist task on either host. A Windows target, or a Windows host whose
   filesystem has no POSIX permissions, keeps whatever the copy gives it;
   elsewhere the executable and launcher copies stay executable. Returns DIR."
  [dir platform {:keys [exe launcher]}]
  (fs/create-dirs dir)
  (doseq [f (cond-> [exe] launcher (conj launcher))]
    (let [target (fs/path dir (fs/file-name f))]
      (when-not (= (str (fs/absolutize f)) (str (fs/absolutize target)))
        (fs/copy f target {:replace-existing true})
        (when (and (not (windows-platform? platform)) (not (fs/windows?)))
          (fs/set-posix-file-permissions target "rwxr-xr-x")))))
  (println "copied executable to" (str dir))
  (str dir))

(defn temp-run-dir
  "A throwaway dir to run an artifact from, on the platform's real temp root
   (java.io.tmpdir is unreliable on Termux: it hardcodes /tmp). NAME scopes
   the packager's scratch parent."
  [name]
  (let [root (or (not-empty (str (System/getenv "TMPDIR")))
                 (System/getProperty "java.io.tmpdir"))
        dir (fs/path root name)]
    (fs/create-dirs dir)
    (fs/create-temp-dir {:dir dir :prefix "smoke-"})))

(defn- smoke-version!
  "One entry's --version on the artifact: returns the reported string, throws
   ::smoke-failed unless it equals EXPECTED (the baked version, not a
   checkout). Both entries are checked because the --app hand-off is dynamic —
   the part packaging can break while every namespace still compiles."
  [run artifact app expected]
  (let [res (run "--app" app "--version")
        reported (str/trim (:out res))]
    (when-not (and (zero? (:exit res)) (= reported expected))
      (println (:err res))
      (throw (ex-info (str "smoke test failed for " artifact " — --app " app
                           " --version said " (pr-str reported)
                           ", expected " (pr-str expected))
                      {:type ::smoke-failed :exit (:exit res) :app app
                       :version reported :expected expected})))
    reported))

(defn- smoke-test!
  "Run the freshly built current-host artifact. An app artifact: --list-models
   must list the embedded catalogs (exit 0), and both entries' --version must
   report the same version the artifact was built as — the baked
   kmet/version.txt, not a checkout. A --test artifact: `--test kmet.libs.test-archive` must run that
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
         (let [dir (temp-run-dir "kmet-build-bb")
               run (fn [& args]
                     (apply p/shell {:out :string :err :string :continue true
                                     :dir (str dir)} cmd args))]
           (try
             (println "smoke test:" cmd "--test kmet.libs.test-archive")
             (let [res (run "--test" "kmet.libs.test-archive")]
               (if (and (zero? (:exit res))
                        (str/includes? (:out res) "Testing kmet.libs.test-archive"))
                 (println "smoke test passed: the packaged runner ran kmet.libs.test-archive")
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
           (println "smoke test:" cmd "--app old --version and --app new --version")
           (let [expected (str "kmet " (version-lib/checkout-version))
                 old (smoke-version! run artifact "old" expected)
                 new (smoke-version! run artifact "new" expected)]
             (if (= old new)
               (println "smoke test passed:" old "(both entries)")
               (throw (ex-info (str "smoke test failed for " artifact
                                    " — the two entries disagree: "
                                    (pr-str old) " vs " (pr-str new))
                               {:type ::smoke-failed :old old :new new}))))))))))

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
   selection vocabulary (kmet.app.extensions.core/select-loader-kind)."
  #{:sci :jolt})

(defn- pack-verify!
  "Verify SRC-DIR is a packable extension artifact root; throw ex-info
   with :type ::pack-error otherwise. Checks: extension.edn present with
   :name + a qualified :plugin var + a :loader vector of known backend kinds;
   the plugin var's namespace has its ns-path file; every .clj/.cljc/.bb
   file's (ns ...) matches its path (strict layout); deps.edn parses and
   carries only :deps. Returns {:name :entry-ns}."
  [src-dir]
  (let [root (fs/canonicalize src-dir)
        manifest (io/file (str root) "extension.edn")]
    (when-not (fs/regular-file? manifest)
      (throw (ex-info (str "no extension.edn in " src-dir) {:type ::pack-error})))
    (let [m (edn/read-string (slurp manifest))
          plugin (:plugin m)
          entry-ns (when (and (symbol? plugin) (namespace plugin))
                     (symbol (namespace plugin)))
          loaders (:loader m)]
      (when-not (and (:name m) entry-ns)
        (throw (ex-info (str "extension.edn needs :name + a qualified :plugin var "
                             "(my-ext.core/plugin), got: " (pr-str m))
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
          (throw (ex-info (str "extension.edn plugin/entry namespace not found: " entry-ns)
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
  (let [{:keys [name]} (pack-verify! src-dir)
        root (fs/canonicalize src-dir)
        out (str (or out-path (str name ".jar")))]
    (archive/write-zip!
     out
     (for [f (sort-by str (filter #(fs/regular-file? %) (fs/glob root "**")))]
       {:name (str (fs/relativize root f)) :file (str f)}))
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

(defn- file-forms
  "Every top-level form of the Clojure file at PATH, read with reader
   conditionals allowed; nil when the file cannot be read."
  [path]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader (fs/file path)))]
      (loop [acc []]
        (let [form (read {:eof ::eof :read-cond :allow :features #{:bb}} r)]
          (if (= ::eof form) acc (recur (conj acc form))))))
    (catch Exception _ nil)))

(defn- defines-var?
  "Does FORMS define VAR-NAME — as `(def name …)` or `(<alias>/defplugin name
   …)`? The static half of the loader's `:plugin` requirement."
  [forms var-name]
  (boolean
   (some (fn [form]
           (when (list? form)
             (let [head (first form)]
               (and (symbol? head)
                    (contains? #{"def" "defplugin"} (clojure.core/name head))
                    (= var-name (second form))))))
         forms)))

(defn- validate-bundled-dir!
  "D8 checks for one :dir artifact root: extension.edn with :name + a qualified
   :plugin var + a :loader vector that includes :sci, every source file's
   (ns ...) matching its strict path, and a deps.edn (when present) carrying
   only :deps with no :local/root."
  [name root]
  (let [manifest-file (io/file root "extension.edn")]
    (when-not (fs/regular-file? manifest-file)
      (bundle-error! (str "bundled extension " name ": no extension.edn in " root)))
    (let [m (edn/read-string (slurp manifest-file))
          plugin (:plugin m)
          entry-ns (when (and (symbol? plugin) (namespace plugin))
                     (symbol (namespace plugin)))
          loaders (:loader m)]
      (when-not (and (:name m) entry-ns)
        (bundle-error! (str "bundled extension " name
                            ": extension.edn needs :name + a qualified :plugin var, got "
                            (pr-str m))))
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
          (bundle-error! (str "bundled extension " name
                              ": plugin/entry namespace not found: " entry-ns)))
        (when-not (defines-var? (file-forms entry-file)
                    (symbol (clojure.core/name plugin)))
          (bundle-error! (str "bundled extension " name ": " entry-file
                              " does not define " (clojure.core/name plugin)
                              " — the manifest's :plugin must name a var the "
                              "namespace defines"))))
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
  "A :file artifact is a single-file extension: the file must exist, start with
   an (ns ...) form, and define its `plugin` var — a single file has no
   extension.edn to name one."
  [name root]
  (when-not (fs/regular-file? (io/file root))
    (bundle-error! (str "bundled extension " name ": missing file " root)))
  (let [forms (file-forms root)
        first-form (first forms)]
    (when-not (and (list? first-form) (= 'ns (first first-form)))
      (bundle-error! (str "bundled extension " name ": " root
                          " does not start with an (ns ...) form")))
    (when-not (defines-var? forms 'plugin)
      (bundle-error! (str "bundled extension " name ": " root
                          " does not define a plugin var (def plugin {…})")))))

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
   kmet.app.extensions.bundled). The staging root is the extra root the bb
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
  "CLI args => {:targets [...] :all? :force? :no-smoke? :test? :out :help?}.
   --out DIR additionally copies the built executable into DIR. Unknown
   targets/options throw ex-info with :type ::usage."
  [args]
  (loop [args args
         opts {:targets [] :all? false :force? false :no-smoke? true
               :out nil :help? false}]
    (if-some [arg (first args)]
      (cond
        (= "--all" arg) (recur (rest args) (assoc opts :all? true))
        (= "--force" arg) (recur (rest args) (assoc opts :force? true))
        (= "--smoke" arg) (recur (rest args) (assoc opts :no-smoke? false))
        (= "--no-smoke" arg) (recur (rest args) (assoc opts :no-smoke? true))
        (#{"-o" "--out"} arg)
        (let [dir (second args)]
          (when (str/blank? (str dir))
            (throw (ex-info (str arg " needs a directory") {:type ::usage :option arg})))
          (recur (drop 2 args) (assoc opts :out dir)))
        (= "--help" arg) (recur (rest args) (assoc opts :help? true))
        (str/starts-with? arg "--") (throw (ex-info (str "unknown option: " arg)
                                                    {:type ::usage}))
        :else (if-some [platform (normalize-target arg)]
                (recur (rest args) (update opts :targets conj platform))
                (throw (ex-info (str "unknown target: " arg)
                                {:type ::usage :known (vec (sort (keys target-table)))}))))
      opts)))

(defn -main
  "bb dist [target ...|--all] [--force] [--smoke] [--test] [--out DIR]
   (the bb.edn task's babashka branch; the jolt branch runs
   kmet.tasks.build-jolt/-main)

   Build a self-contained kmet executable for each target platform: the
   official babashka release binary with an uberjar appended. Each build
   assembles the bare `kmet` (kmet.exe on Windows) under
   target/dist/<platform>/ and packages it as dist/kmet-<ver>-<platform>.zip
   with one top-level folder named after the zip (artifact-dir) holding the
   executable, its Termux launcher when one is written, and the repository
   LICENSE — so the executable carries no version while the zip name carries
   the version and the platform. --test swaps in the test-runner artifact
   instead: kmet-test and dist/kmet-test-<ver>-<platform>.zip, Main-Class
   kmet.tasks.test-main, whose
   --test/--test-ext flags run the packaged suite. Targets are dist
   platforms (linux-aarch64, linux-amd64, macos-aarch64, macos-amd64,
   windows-amd64; a release asset slug like linux-amd64-static is accepted
   too); they default to the current platform. --all builds every published
   platform, --force re-downloads cached babashka binaries, --out DIR
   additionally copies the executable into DIR, and --smoke runs the
   current-host artifact after building (--list-models plus --version, or
   the packaged test runner for --test; skipped by default to keep local
   dist builds fast and side-effect free). A fresh uberjar
   target/uberjar-bb.jar is always rebuilt
   first so artifacts never bundle stale sources."
  [& args]
  (bb-only! "kmet.tasks.build/-main (the bb half of the dist task)")
  (let [{:keys [targets all? force? no-smoke? out help?]} (parse-args args)]
    (when help?
      (println (:doc (meta #'-main)))
      (System/exit 0))
    (when force?
      (when (fs/exists? cache-dir)
        (fs/delete-tree cache-dir)))
    (let [ver (version)
          jar jar-path
          _ (do (println "building uberjar" jar)
                (uberjar*))
          bb-ver (latest-bb-version)
          targets (cond
                    all? (sort (keys target-table))
                    (seq targets) targets
                    :else (let [platform (host-target)]
                            (when-not (string? platform)
                              (throw (ex-info "cannot determine host platform; pass explicit targets"
                                              {:type ::usage :reason platform})))
                            [platform]))
          _ (when (and out (> (count targets) 1))
              (throw (ex-info "--out copies one platform's executable; name a single target"
                              {:type ::usage :targets (vec targets)})))]
      (println (format "%s %s | babashka %s | targets: %s"
                       "kmet" ver bb-ver (str/join ", " targets)))
      (doseq [platform targets]
        (let [built (assemble-one! platform (get target-table platform) bb-ver
                                   {:jar jar})]
          (pack-artifact!
           (artifact-zip :bb ver platform {})
           built)
          (when out
            (install-artifact! out platform built))
          (when-not no-smoke?
            (smoke-test! (:exe built) platform {}))))
      (println "done:" dist-dir))))

