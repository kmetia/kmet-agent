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
            [kmet.app.tools.glob :as glob]
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
  "PATTERN as a `/`-separated string, refusing rooted, drive-qualified and
   home-relative forms. Globs are text, not host filesystem paths: Windows
   path parsers reject their metacharacters. OPTION names the error."
  [pattern option]
  (let [pattern (str pattern)]
    (when (re-find #"^(?:[/\\]|[A-Za-z]:|~(?:[/\\]|$))" pattern)
      (throw (ex-info (str option " must be relative to :path")
                      {:type ::absolute-pattern
                       :option option
                       :pattern pattern})))
    (str/replace pattern fs/file-separator "/")))

(defn- relative-path
  "FILE relative to BASE, `/`-separated."
  [base file]
  (str/replace (str (fs/relativize base file)) fs/file-separator "/"))

(defn- stat-entry
  "FILE's `:stat` entry: size in bytes and modified time in epoch milliseconds.
   Either value is nil when that stat call fails."
  [file]
  {:size (try (fs/size file) (catch Exception _ nil))
   :modified (try (fs/file-time->millis (fs/last-modified-time file))
                  (catch Exception _ nil))})

(defn- compile-excludes
  "Compiled exclude matcher from EXCLUDE — nil, one pattern, or a seq."
  [exclude]
  (->> (if (string? exclude) [exclude] exclude)
       (mapv #(relative-pattern % ":exclude"))
       glob/compile-matcher))

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
   :gitignore? (default true), :stat (when true, add :entries with the same
   count and order as :paths). PATTERN is relative to :path, which may be
   absolute or `~`. Returns {:paths :total :truncated?}; with :stat also
   {:entries [{:path :size :modified}]}, modified in epoch milliseconds and nil
   when the stat fails. Paths are relative to CWD, `~/...` under home, or
   absolute, `/`-separated."
  [cwd pattern {:keys [path max exclude gitignore? stat]
                :or {path "." max default-max-paths gitignore? true}}]
  (let [base (resolve-path cwd path)
        _ (when-not (fs/directory? base)
            (throw (ex-info (str "glob path is not a directory: " path)
                            {:type ::not-directory :path path})))
        pattern (relative-pattern pattern "PATTERN")
        max (positive-int max default-max-paths)
        matcher (glob/compile-matcher pattern)
        excludes (compile-excludes exclude)
        files (vec (filtered-files base matcher excludes gitignore?))
        total (count files)
        taken (vec (take max files))
        paths (mapv #(path/display-path cwd %) taken)]
    (cond-> {:paths paths
             :total total
             :truncated? (> total max)}
      stat (assoc :entries
                  (mapv (fn [file shown]
                          (assoc (stat-entry file) :path shown))
                        taken paths)))))

(defn- record-match!
  "Add MATCH to STATE while fewer than MAX matches are kept; otherwise mark
   the result truncated. Returns true when the match was kept."
  [state match max]
  (if (< (count (:matches @state)) max)
    (do (vswap! state update :matches conj match) true)
    (do (vswap! state assoc :truncated? true) false)))

(defn- context-lines
  "Render the context windows of ascending MATCH-LINES in LINES, emitted once
   in line order: a kept match renders `path:line: text`, every other line
   `path-line- text`. A match past the kept set (for example one beyond :max
   inside the last kept match's trailing context) renders as context."
  [shown lines match-lines context]
  (let [total (count lines)
        matches (set match-lines)]
    (->> match-lines
         (mapcat (fn [line]
                   (range (max 1 (- line context))
                          (inc (min total (+ line context))))))
         distinct
         (mapv (fn [line]
                 (let [text (nth lines (dec line))]
                   (if (contains? matches line)
                     (str shown ":" line ": " text)
                     (str shown "-" line "- " text))))))))

(defn- search-content!
  "Search LINES for RE, appending kept matches and their context to STATE."
  [state re {:keys [max context]} shown lines]
  (vswap! state update :files inc)
  (let [kept (volatile! [])]
    (doseq [[i line] (map-indexed vector lines)
            :while (not (:truncated? @state))]
      (when (re-find re line)
        (when (record-match! state {:path shown :line (inc i) :text line} max)
          (vswap! kept conj (inc i)))))
    (when (pos? context)
      (vswap! state update :lines into
              (context-lines shown lines @kept context)))))

(defn- search-file!
  "Search FILE; skip it when oversized, binary or unreadable."
  [state re {:keys [max-file-bytes cwd] :as opts} file]
  (let [shown (path/display-path cwd file)
        size (try (fs/size file) (catch Exception _ nil))]
    (if (and size (> size max-file-bytes))
      (skip-file! state shown (str "larger than " max-file-bytes " bytes"))
      (try
        (let [content (slurp (fs/file file) :encoding "UTF-8")]
          (if (str/includes? content "\u0000")
            (skip-file! state shown "binary")
            (search-content! state re opts shown (str/split-lines content))))
        (catch Exception t
          (skip-file! state shown (or (ex-message t) (str t))))))))

(defn grep
  "Search file contents for PATTERN (a regex string). OPTS: :path (default:
   current directory; file or directory), :glob (default `**/*`, relative to
   :path), :ignore-case?, :max (default 100), :max-file-bytes (default 5 MiB),
   :exclude (glob or seq of globs relative to :path), :gitignore? (default
   true), :context (default 0; lines shown before and after each match; a
   negative count means 0). Returns {:matches [{:path :line :text}]
   :skipped [{:path :reason}] :skipped-count int :truncated? bool :files int};
   with :context > 0 also :lines, the match and context lines as
   `path:line: text` and `path-line- text`, ordered by file/line and
   deduplicated across overlapping windows. :max limits matches, not context
   lines, so the result can grow to up to :max * (2 * :context + 1) lines.
   Paths are displayed like `glob`'s. Binary and oversized files are skipped,
   not read."
  [cwd pattern {:keys [path glob ignore-case? max max-file-bytes exclude
                       gitignore? context]
                :or {path "." glob "**/*" max default-max-matches
                     max-file-bytes default-max-file-bytes gitignore? true
                     context 0}}]
  (let [base (resolve-path cwd path)
        directory? (fs/directory? base)
        _ (when-not (or directory? (fs/regular-file? base))
            (throw (ex-info (str "grep path not found: " path)
                            {:type ::not-found :path path})))
        pattern (str pattern)
        re (re-pattern (if ignore-case? (str "(?i)" pattern) pattern))
        glob (relative-pattern glob ":glob")
        matcher (glob/compile-matcher glob)
        excludes (compile-excludes exclude)
        max (positive-int max default-max-matches)
        max-file-bytes (positive-int max-file-bytes default-max-file-bytes)
        context (positive-int context 0)
        files (if directory?
                (filtered-files base matcher excludes gitignore?)
                [base])
        state (volatile! {:matches []
                          :lines []
                          :skipped []
                          :skipped-count 0
                          :files 0
                          :truncated? false})
        opts {:max max
              :max-file-bytes max-file-bytes
              :context context
              :cwd cwd}]
    (doseq [file files :while (not (:truncated? @state))]
      (search-file! state re opts file))
    (let [{:keys [matches lines skipped skipped-count files truncated?]} @state]
      (cond-> {:matches (vec matches)
               :skipped (vec skipped)
               :skipped-count skipped-count
               :truncated? (boolean truncated?)
               :files files}
        (pos? context) (assoc :lines lines)))))
