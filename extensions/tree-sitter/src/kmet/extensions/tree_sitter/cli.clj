(ns kmet.extensions.tree-sitter.cli
  "Locating, installing and spawning the tree-sitter CLI binary.
   A KMET_TREE_SITTER_BIN override wins over the pinned download; it must be
   an executable tree-sitter CLI with WASM support (0.26.11 through 0.28.x).
   The pinned path downloads the release once, verifies its sha256 against the
   shipped manifest, emits the Termux glibc launcher next to the binary (same
   pattern as kmet.tasks.build) and smoke-runs --version before declaring the
   install good. A failed smoke is remembered for this process so repeated
   tool calls do not download the same blob again. exec!/process! never throw
   for infrastructure reasons: spawn failures and timeouts come back as
   {:error ...} maps so callers (hooks!) can treat them as pass-through."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [kmet.extensions.tree-sitter.fetch :as fetch]
            [kmet.extensions.tree-sitter.paths :as paths]))

(def default-timeout-ms 30000)

(defn termux?
  "True when running under Termux (Android)."
  []
  (str/includes? (str (System/getenv "PREFIX")) "/com.termux/"))

(def ^:private system-version-min
  "Oldest external CLI tested against this extension's parse contract."
  [0 26 11])

(def ^:private system-version-max
  "First external CLI minor line this extension has not tested."
  [0 29 0])

(defn configured-binary
  "KMET_TREE_SITTER_BIN (with a leading ~ expanded), or nil when unset or
   blank. Public so tests can substitute the environment without mutating the
   process."
  []
  (when-let [v (not-empty (str/trim (str (System/getenv "KMET_TREE_SITTER_BIN"))))]
    (str (fs/expand-home v))))

(defn- executable-path
  "Resolve V as an existing executable path, or nil. A bare name is looked up
   on PATH; a value containing a separator must exist as given."
  [v]
  (let [p (fs/path v)
        candidate (if (or (fs/exists? p)
                          (str/includes? v "/")
                          (str/includes? v "\\"))
                    p
                    (fs/which v))]
    (when (and candidate
               (fs/regular-file? candidate)
               (or (fs/windows?) (fs/executable? candidate)))
      (fs/absolutize candidate))))

(def ^:private digit-values
  "Character -> digit value for ASCII decimal digits."
  (zipmap "0123456789" (range 10)))

(defn- parse-digits
  "Parse a decimal digit string without reader or host numeric parsers."
  [s]
  (reduce (fn [n c] (+ (* n 10) (digit-values c))) 0 s))

(defn- parse-version-numbers
  "Major/minor/patch integers from the first X.Y.Z triple in S, or nil."
  [s]
  (some->> (re-find #"(\d+)\.(\d+)\.(\d+)" (str s))
           rest
           (mapv parse-digits)))

(defn- version-parts
  "Major/minor/patch integers from a `tree-sitter X.Y.Z` line, or nil."
  [s]
  (when (re-find #"tree-sitter\s+\d+\.\d+\.\d+" (str s))
    (parse-version-numbers s)))

(defn- version-text
  [parts]
  (str/join "." parts))

(defn- supported-system-version?
  "True for CLI versions this extension has exercised (0.26.11 through
   0.28.x); newer minor lines need a test run before they are accepted."
  [parts]
  (and (some? parts)
       (not (neg? (compare parts system-version-min)))
       (neg? (compare parts system-version-max))))

(defn- clip
  [s n]
  (let [s (str/trim (str s))]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- result-detail
  "One-line diagnosis of a failed process result."
  [res]
  (->> [(when-let [error (:error res)]
          (str error
               (when-let [reason (not-empty (str (:reason res)))]
                 (str ": " reason))))
        (when (some? (:exit res)) (str "exit " (:exit res)))
        (when-let [err (not-empty (clip (:err res) 500))]
          (str "stderr: " err))
        (when-let [out (not-empty (clip (:out res) 500))]
          (str "stdout: " out))]
       (remove nil?)
       (str/join "; ")))

(defn- loader-hint
  "Actionable hint when a failed spawn looks like a missing or incompatible
   ELF interpreter or wrapper, the Nix/non-FHS case."
  [res]
  (let [text (str (:reason res) " " (:err res))]
    (when (re-find #"(?i)no such file or directory|error while loading shared libraries|exec format error|cannot execute binary file|command not found"
                   text)
      (str "The tree-sitter binary cannot run on this system (missing or "
           "incompatible ELF loader). Set KMET_TREE_SITTER_BIN to a tree-sitter "
           "CLI built for this system; on Nix/NixOS, build one with "
           "`tree-sitter.override { wasmSupport = true; }` and point "
           "KMET_TREE_SITTER_BIN at its bin/tree-sitter."))))

(defn- linker-for
  "glibc dynamic loader file name for the host arch (Termux launcher)."
  []
  (let [arch (str/lower-case (str (System/getProperty "os.arch")))]
    (cond (#{"aarch64" "arm64"} arch) "ld-linux-aarch64.so.1"
          (#{"amd64" "x86_64"} arch) "ld-linux-x86-64.so.2"
          :else (throw (ex-info (str "unsupported termux arch: " arch)
                                {:type ::unsupported-platform :arch arch})))))

(defn launcher-text
  "Termux launcher script text: exec the downloaded glibc binary through the
   glibc dynamic linker; LD_PRELOAD (libtermux-exec) breaks non-bionic
   executables."
  [{:keys [bin-name linker]}]
  (format "#!/data/data/com.termux/files/usr/bin/sh
# tree-sitter launcher (Termux): run the glibc CLI binary through the glibc
# dynamic linker; LD_PRELOAD breaks non-bionic executables.
DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)
LD=\"$PREFIX/glibc/lib/%s\"
[ -x \"$LD\" ] || { echo \"termux glibc package required: pkg install glibc-repo && pkg install glibc\" >&2; exit 1; }
unset LD_PRELOAD
export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"
exec \"$LD\" --library-path \"$PREFIX/glibc/lib\" \"$DIR/%s\" \"$@\"
" linker bin-name))

(defn emit-launcher!
  "Write + chmod the Termux launcher next to the binary; returns its path."
  ([] (emit-launcher! nil))
  ([base]
   (let [w (paths/launcher-path base)]
     (spit (str w) (launcher-text {:bin-name (fs/file-name (paths/bin-path base))
                                   :linker (linker-for)}))
     (fs/set-posix-file-permissions w "rwxr-xr-x")
     w)))

(defn resolve-invocation
  "What to exec: KMET_TREE_SITTER_BIN when set (resolved when possible),
   else the Termux launcher when present, else the raw binary path."
  ([] (resolve-invocation nil))
  ([base]
   (let [configured (configured-binary)]
     (or (some-> configured executable-path)
         configured
         (let [launcher (paths/launcher-path base)]
           (if (fs/exists? launcher) launcher (paths/bin-path base)))))))

(defn- capture
  "Post-exit stream content as a string (process versions differ on whether
   :string buffers come back as streams or ready strings)."
  [v]
  (if (instance? java.io.InputStream v) (slurp v) (str v)))

(defn process!
  "Generic never-throw process runner: run cmd vector, capture out/err as
   strings. Opts: {:timeout-ms n :env {extra env vars}}. Returns
   {:exit n :out s :err s}, or {:error :timeout} / {:error :spawn-failure
   :reason s}."
  [cmd {:keys [timeout-ms env]}]
  (try
    (let [proc (p/process cmd
                          {:out :string
                           :err :string
                           :env (merge {} (System/getenv) env)})
          res (deref proc (or timeout-ms default-timeout-ms) ::timed-out)]
      (if (= res ::timed-out)
        (do (p/destroy-tree proc)
            ;; reap so no zombie lingers after the kill
            (try (deref proc 5000 nil) (catch Exception _))
            {:error :timeout})
        {:exit (:exit res)
         :out (capture (:out res))
         :err (capture (:err res))}))
    (catch Exception e
      {:error :spawn-failure
       :reason (str/trim (str (ex-message e)))})))

(defn exec!
  "Spawn the configured (KMET_TREE_SITTER_BIN) or cached tree-sitter CLI
   with args. Opts: {:base dir :timeout-ms n :env {...}}. Same result
   contract / never-throw guarantee as process!."
  ([args] (exec! args nil))
  ([args {:keys [base] :as opts}]
   (process! (into [(str (resolve-invocation base))] args) opts)))

(defn smoke-version!
  "Run --version and require output `tree-sitter <version>`; throws
   ::smoke-failed with the resolved path, stderr/exit detail and, for a
   missing ELF interpreter, an override hint."
  [base version]
  (let [res (exec! ["--version"] {:base base})
        got (when (zero? (or (:exit res) 1))
              (version-parts (str (:out res) " " (:err res))))]
    (if (and got (= got (parse-version-numbers version)))
      res
      (throw (ex-info (str "installed tree-sitter binary failed --version "
                           "smoke check (" (resolve-invocation base) ")"
                           (when-let [detail (not-empty (result-detail res))]
                             (str ": " detail))
                           (when-let [hint (loader-hint res)]
                             (str "\n" hint)))
                      {:type ::smoke-failed :version version :result res})))))

(def ^:private executable-perms "rwxr-xr-x")

(defn install-binary!
  "Download the pinned release zip for this platform into the cache,
   sha-verify it against the manifest, extract the executable, discard the
   zip, chmod, materialize the manifest copy, emit the Termux launcher.
   Returns bin path."
  ([] (install-binary! nil))
  ([base]
   (let [{:keys [version targets]} (fetch/binary-release)
         target-key (fetch/host-target)
         {:keys [url sha256 binary-sha256]} (or (get targets target-key)
                                                (throw (ex-info (str "no release asset for target: "
                                                                     target-key)
                                                                {:type ::no-asset-for-target
                                                                 :target target-key
                                                                 :version version})))
         bin-dir (paths/bin-dir base)
         bin (paths/bin-path base)
         zip (fs/path bin-dir "release.zip")]
     (fetch/download+verify! url zip sha256)
     (let [extracted (fetch/extract-zip! zip bin-dir)]
       (fs/delete zip)
       (when-not (some #{(str bin)} (map str extracted))
         (throw (ex-info (str "release archive did not contain expected entry "
                              (fs/file-name bin))
                         {:type ::unexpected-archive :entries extracted}))))
     (when-not (= (fetch/sha256 bin) binary-sha256)
       (fs/delete-if-exists bin)
       (throw (ex-info "extracted binary failed sha256 verification"
                       {:type ::sha-mismatch :path (str bin)})))
     (when-not (fs/windows?)
       (fs/set-posix-file-permissions bin executable-perms))
     (spit (str (paths/manifest-copy-path base)) (fetch/manifest-text))
     (when (termux?)
       (emit-launcher! base))
     bin)))

(def ^:private verified-installs
  "bin-path -> [size mtime-ms] of binaries already sha-verified this
   process; lets repeated tool calls skip the multi-MB re-hash. Any change
   to the file (corruption, replacement) changes the stamp and forces the
   full check again."
  (atom {}))

(defn- stamp
  "Identity of a file on disk cheap enough for hot-path checks."
  [path]
  [(fs/size path) (.toMillis (fs/last-modified-time path))])

(def ^:private verified-system-binaries
  "path-string -> {:stamp [size mtime] :result {...}} for external CLIs
   already probed this process."
  (atom {}))

(def ^:private failed-installs
  "bin-path + [version target] -> {:message s :data m} after a failed
   pinned-install smoke; avoids re-downloading the same blob on every tool call.
   Process-local: a restart retries once, after which the operator can fix the
   environment."
  (atom {}))

(defn- verify-system-binary!
  "Probe an external CLI: version range, then WASM support. Returns
   {:path path :version s :system? true}; throws a diagnostic ex-info
   otherwise. Successful probes are cached by path + size/mtime."
  [path]
  (let [path (fs/absolutize path)
        sig (stamp path)
        cached (get @verified-system-binaries (str path))]
    (if (= sig (:stamp cached))
      (:result cached)
      (let [res (process! [(str path) "--version"] nil)
            parts (when (zero? (or (:exit res) 1))
                    (version-parts (str (:out res) " " (:err res))))]
        (when-not parts
          (throw (ex-info (str "KMET_TREE_SITTER_BIN is not a working tree-sitter CLI: "
                               path
                               (when-let [detail (not-empty (result-detail res))]
                                 (str ": " detail))
                               (when-let [hint (loader-hint res)]
                                 (str "\n" hint)))
                          {:type ::invalid-system-binary
                           :path (str path)
                           :result res})))
        (when-not (supported-system-version? parts)
          (throw (ex-info (str "KMET_TREE_SITTER_BIN reports tree-sitter "
                               (version-text parts) " at " path
                               "; supported versions are >=0.26.11 and <0.29.0")
                          {:type ::unsupported-system-version
                           :path (str path)
                           :version (version-text parts)})))
        (let [help (process! [(str path) "parse" "--help"] nil)
              help-text (str (:out help) " " (:err help))
              ok? (zero? (or (:exit help) 1))
              wasm? (and ok? (str/includes? help-text "--wasm"))]
          (when-not wasm?
            (throw (ex-info (if ok?
                              (str "tree-sitter " (version-text parts) " at "
                                   path " was built without WASM support; on "
                                   "Nix/NixOS use `pkgs.tree-sitter.override { "
                                   "wasmSupport = true; }` and point "
                                   "KMET_TREE_SITTER_BIN at its bin/tree-sitter")
                              (str "could not verify WASM support of "
                                   (version-text parts) " at " path
                                   (when-let [detail (not-empty (result-detail help))]
                                     (str ": " detail))))
                            {:type ::system-binary-no-wasm
                             :path (str path)
                             :version (version-text parts)
                             :result help}))))
        (let [result {:path path :version (version-text parts) :system? true}]
          (swap! verified-system-binaries assoc (str path)
                 {:stamp sig :result result})
          result)))))

(defn- ensure-system-binary!
  "Resolve and probe the KMET_TREE_SITTER_BIN override. No cache download is
   attempted; an unusable override fails rather than silently using the pin."
  [raw]
  (let [path (or (executable-path raw)
                 (throw (ex-info (str "KMET_TREE_SITTER_BIN does not name an "
                                      "executable: " raw)
                                 {:type ::invalid-system-binary :value raw})))]
    (verify-system-binary! path)))

(defn- ensure-pinned-binary!
  "Pinned-release branch: cached+sha-verified binary -> reuse (no spawn);
   missing or corrupt -> download once (zip verified, then the extracted
   executable re-verified against its own pin) and smoke-run --version.
   A failed smoke is memoized per version/target for this process so repeated
   tool calls do not re-download; the blob is deleted so a later process can
   retry. Returns {:path bin-path :version}."
  [base]
  (paths/ensure-dirs! base)
  (let [bin (paths/bin-path base)
        {:keys [version targets]} (fetch/binary-release)
        target-key (fetch/host-target)
        {:keys [binary-sha256]} (or (get targets target-key)
                                    (throw (ex-info (str "manifest has no entry for target: "
                                                         target-key)
                                                    {:type ::no-asset-for-target
                                                     :target target-key
                                                     :version version})))
        failure-key [version target-key (str bin)]]
    (when-let [{:keys [message data]} (get @failed-installs failure-key)]
      (throw (ex-info (or message "tree-sitter install failed") data)))
    (let [sig (when (fs/exists? bin) (stamp bin))]
      (if (and sig (= (get @verified-installs (str bin)) sig))
        ;; proven earlier this process: skip the multi-MB hash + spawn;
        ;; auxiliary files are rewritten anyway (cheap, self-healing)
        (do (spit (str (paths/manifest-copy-path base)) (fetch/manifest-text))
            (when (termux?)
              (emit-launcher! base)))
        (do (if (and sig (= (fetch/sha256 bin) binary-sha256))
              ;; cached on disk: verify auxiliaries into place
              (do (spit (str (paths/manifest-copy-path base))
                        (fetch/manifest-text))
                  (when (termux?)
                    (emit-launcher! base)))
              ;; fresh install: download, then prove it actually runs
              (do (install-binary! base)
                  (try
                    (smoke-version! base version)
                    (catch Exception e
                      (swap! failed-installs assoc failure-key
                             {:message (ex-message e) :data (ex-data e)})
                      ;; drop the blob so a later process can retry cleanly
                      (fs/delete-if-exists bin)
                      (fs/delete-if-exists (paths/launcher-path base))
                      (throw e)))))
            (swap! verified-installs assoc (str bin)
                   (when (fs/exists? bin) (stamp bin))))))
    {:path bin :version version}))

(defn ensure-binary!
  "Resolve the tree-sitter CLI. A KMET_TREE_SITTER_BIN override wins and is
   probed for a supported version and WASM support; it never triggers a
   download. Otherwise the pinned release is installed/verified as described
   in ensure-pinned-binary!. Returns {:path path :version s}, plus
   :system? true for an override. Opts: {:base dir}."
  ([] (ensure-binary! nil))
  ([{:keys [base]}]
   (if-let [raw (configured-binary)]
     (ensure-system-binary! raw)
     (ensure-pinned-binary! base))))
