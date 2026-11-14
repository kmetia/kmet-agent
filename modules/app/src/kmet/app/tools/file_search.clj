(ns kmet.app.tools.file-search
  "Ignore-aware file discovery and content search behind the eval sandbox's
   `glob` and `grep` verbs.

   A search inside a git work tree uses `git ls-files` for its candidate set,
   so tracked files and untracked files that git does not ignore are searched
   and `target/`, `.git/` and other ignored paths are skipped. An explicitly
   named ignored directory is still searched (the caller asked for it), and a
   directory outside git falls back to the shared safe traversal. `grep` skips
   binary and oversized files rather than trying to decode them."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [kmet.libs.glob :as glob]
            [kmet.libs.path :as path]))

(def ^:private default-max-file-bytes
  "Largest file `grep` reads."
  (* 5 1024 1024))

(def ^:private default-max-matches
  "Matches `grep` returns before marking the result truncated."
  100)

(def ^:private default-max-paths
  "Paths `glob` returns before marking the result truncated."
  500)

(def ^:private max-skipped
  "Skipped-file entries returned; the count always reports the full number."
  20)

(defn- positive-int
  "X as a positive integer, or FALLBACK when it is absent or not positive."
  [x fallback]
  (let [n (try (long x) (catch Exception _ nil))]
    (if (and n (pos? n)) n fallback)))

(defn- resolve-path
  "Resolve PATH against CWD; absolute paths and `~` pass through."
  [cwd path]
  (path/resolve-tool-path-ctx {:cwd (str (fs/absolutize cwd))}
                              (fs/expand-home (str (or path ".")))))

(defn- relative-pattern
  "PATTERN as a string, refusing an absolute pattern. OPTION names it in the
   error."
  [pattern option]
  (let [pattern (str pattern)]
    (when (fs/absolute? (fs/path (fs/expand-home pattern)))
      (throw (ex-info (str option " must be relative to :path")
                      {:type ::absolute-pattern
                       :option option
                       :pattern pattern})))
    pattern))

(defn- relative-path
  "FILE relative to BASE, `/`-separated."
  [base file]
  (str/replace (str (fs/relativize base file)) fs/file-separator "/"))

(defn- compile-excludes
  "Compiled exclude matcher from EXCLUDE — nil, one pattern, or a seq."
  [exclude]
  (->> (if (string? exclude) [exclude] exclude)
       (mapv #(relative-pattern % ":exclude"))
       glob/compile-patterns))

(defn- git-ignored?
  "True when BASE is covered by git's ignore rules."
  [base]
  (try
    (zero? (:exit @(proc/process ["git" "-C" (str base) "check-ignore" "-q" "."]
                                 {:out :discard :err :discard})))
    (catch Exception _ false)))

(defn- git-files
  "Regular files under BASE according to git, or nil when git cannot answer.
   GITIGNORE? excludes ignored untracked files; without it they are included."
  [base gitignore?]
  (try
    (let [args (cond-> ["git" "-C" (str base) "ls-files" "-z" "--cached" "--others"]
                 gitignore? (conj "--exclude-standard"))
          result @(proc/process args {:out :string :err :discard})]
      (when (zero? (:exit result))
        (->> (str/split (:out result) #"\u0000")
             (remove str/blank?)
             (map #(fs/path base %))
             (filter fs/regular-file?))))
    (catch Exception _ nil)))

(defn- candidate-files
  "The files to search under BASE. An explicit ignored directory and a
   non-git directory use the safe walk; a git work tree uses its index."
  [base gitignore?]
  (cond
    (not (fs/directory? base)) [base]
    (and gitignore? (git-ignored? base)) (path/safe-file-seq base)
    :else (or (git-files base gitignore?)
              (path/safe-file-seq base))))

(defn- skip-file!
  "Record one skipped file in STATE."
  [state shown reason]
  (vswap! state update :skipped-count inc)
  (when (< (count (:skipped @state)) max-skipped)
    (vswap! state update :skipped conj {:path shown :reason reason})))

(defn- filtered-files
  "Candidate files under BASE matching MATCHER and not EXCLUDES, sorted."
  [base matcher excludes gitignore?]
  (->> (candidate-files base gitignore?)
       (filter #(glob/matches? matcher (relative-path base %)))
       (remove #(glob/matches? excludes (relative-path base %)))
       (sort-by str)))

(defn glob
  "List files matching PATTERN. OPTS: :path (default: current directory),
   :max (default 500), :exclude (glob or seq of globs relative to :path),
   :gitignore? (default true). PATTERN is relative to :path, which may be
   absolute or `~`. Returns {:paths :total :truncated?}; paths are relative to
   CWD, `~/...` under home, or absolute, `/`-separated."
  [cwd pattern {:keys [path max exclude gitignore?]
                :or {path "." max default-max-paths gitignore? true}}]
  (let [base (resolve-path cwd path)
        _ (when-not (fs/directory? base)
            (throw (ex-info (str "glob path is not a directory: " path)
                            {:type ::not-directory :path path})))
        pattern (relative-pattern pattern "PATTERN")
        max (positive-int max default-max-paths)
        matcher (glob/compile-pattern pattern)
        excludes (compile-excludes exclude)
        files (vec (filtered-files base matcher excludes gitignore?))
        total (count files)]
    {:paths (mapv #(path/display-path cwd %) (take max files))
     :total total
     :truncated? (> total max)}))

(defn- add-match!
  "Record one matching LINE at LINE-NUMBER in STATE."
  [state shown line-number line max]
  (vswap! state update :matches conj
          {:path shown :line line-number :text line})
  (when (> (count (:matches @state)) max)
    (vswap! state assoc :truncated? true)))

(defn- search-content!
  "Search CONTENT for RE, appending matches to STATE."
  [state re max shown content]
  (vswap! state update :files inc)
  (doseq [[i line] (map-indexed vector (str/split-lines content))
          :while (not (:truncated? @state))]
    (when (re-find re line)
      (add-match! state shown (inc i) line max))))

(defn- search-file!
  "Search FILE; skip it when oversized, binary or unreadable."
  [state re max max-file-bytes cwd file]
  (let [shown (path/display-path cwd file)
        size (try (fs/size file) (catch Exception _ nil))]
    (if (and size (> size max-file-bytes))
      (skip-file! state shown (str "larger than " max-file-bytes " bytes"))
      (try
        (let [content (slurp (fs/file file) :encoding "UTF-8")]
          (if (str/includes? content "\u0000")
            (skip-file! state shown "binary")
            (search-content! state re max shown content)))
        (catch Exception t
          (skip-file! state shown (or (ex-message t) (str t))))))))

(defn grep
  "Search file contents for PATTERN (a regex string). OPTS: :path (default:
   current directory; file or directory), :glob (default `**/*`, relative to
   :path), :ignore-case?, :max (default 100), :max-file-bytes (default 5 MiB),
   :exclude (glob or seq of globs relative to :path), :gitignore? (default
   true).
   Returns {:matches [{:path :line :text}] :skipped [{:path :reason}]
   :skipped-count int :truncated? bool :files int}; paths are displayed like
   `glob`'s. Binary and oversized files are skipped, not read."
  [cwd pattern {:keys [path glob ignore-case? max max-file-bytes exclude gitignore?]
                :or {path "." glob "**/*" max default-max-matches
                     max-file-bytes default-max-file-bytes gitignore? true}}]
  (let [base (resolve-path cwd path)
        directory? (fs/directory? base)
        _ (when-not (or directory? (fs/regular-file? base))
            (throw (ex-info (str "grep path not found: " path)
                            {:type ::not-found :path path})))
        pattern (str pattern)
        re (re-pattern (if ignore-case? (str "(?i)" pattern) pattern))
        glob (relative-pattern glob ":glob")
        matcher (glob/compile-pattern glob)
        excludes (compile-excludes exclude)
        max (positive-int max default-max-matches)
        max-file-bytes (positive-int max-file-bytes default-max-file-bytes)
        files (if directory?
                (filtered-files base matcher excludes gitignore?)
                [base])
        state (volatile! {:matches []
                          :skipped []
                          :skipped-count 0
                          :files 0
                          :truncated? false})]
    (doseq [file files :while (not (:truncated? @state))]
      (search-file! state re max max-file-bytes cwd file))
    (let [{:keys [matches skipped skipped-count files truncated?]} @state]
      {:matches (vec (take max matches))
       :skipped (vec skipped)
       :skipped-count skipped-count
       :truncated? (boolean truncated?)
       :files files})))
