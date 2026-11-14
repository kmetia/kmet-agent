(ns kmet.extensions.local.files
  "File discovery and content search for the project-local extension."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.libs.path :as path]))

(defn- resolve-path
  "Resolve PATH against CWD; absolute paths and `~` pass through."
  [cwd path]
  (path/resolve-tool-path-ctx {:cwd (str (fs/absolutize cwd))}
                              (fs/expand-home (str (or path ".")))))

(defn- display-path
  "A path for a result line: relative under CWD, `~/...` under home, else
   absolute."
  [cwd file]
  (let [file (fs/normalize (fs/absolutize file))
        cwd (fs/normalize (fs/absolutize cwd))]
    (if (fs/starts-with? file cwd)
      (let [relative (str (fs/relativize cwd file))]
        (if (str/blank? relative) "." relative))
      (path/home-relative (str file)))))

(defn- relative-pattern
  "Return PATTERN as a string, throwing when it is absolute. OPTION names the
   argument in the error."
  [pattern option]
  (let [pattern (str pattern)]
    (when (fs/absolute? (fs/path (fs/expand-home pattern)))
      (throw (ex-info (str option " must be relative to :path")
                      {:type ::absolute-pattern
                       :option option
                       :pattern pattern})))
    pattern))

(defn- glob-patterns
  "PATTERN plus its direct-child form when it starts with `**/`. Java's glob
   makes `**/` skip files directly under the base."
  [pattern]
  (cond-> [pattern]
    (and (str/starts-with? pattern "**/")
         (seq (subs pattern 3)))
    (conj (subs pattern 3))))

(defn- matching-files
  "Regular files under BASE matching glob PATTERN."
  [base pattern]
  (->> (glob-patterns pattern)
       (mapcat #(fs/glob base %))
       (filter fs/regular-file?)
       distinct
       sort))

(defn- glob*
  "List files matching PATTERN. OPTS: :path (default: current directory),
   :max (default 500). PATTERN is relative to :path, which may be absolute or
   `~`. Returns {:paths :total :truncated?}; paths are relative to CWD,
   `~/...` under home, or absolute."
  [cwd pattern {:keys [path max]
                :or {path "." max 500}}]
  (let [pattern (relative-pattern pattern "PATTERN")
        base (resolve-path cwd path)
        paths (->> (matching-files base pattern)
                   (map #(display-path cwd %))
                   sort
                   vec)
        total (count paths)]
    {:paths (vec (take max paths))
     :total total
     :truncated? (> total max)}))

(defn- larger-than?
  [file bytes]
  (try (> (fs/size file) bytes)
       (catch Exception _ false)))

(defn- grep*
  "Search file contents for PATTERN (a regex string). OPTS: :path (default:
   current directory; file or directory), :glob (default: all files, relative
   to :path), :ignore-case?, :max (default 100), :max-file-bytes (default
   5 MiB).
   Returns {:matches [{:path :line :text}] :skipped [{:path :reason}]
   :truncated? bool :files int}; paths are displayed like `glob`'s."
  [cwd pattern {:keys [path glob ignore-case? max max-file-bytes]
                :or {path "." glob "**/*" max 100 max-file-bytes 5242880}}]
  (let [base (resolve-path cwd path)
        files (if (fs/regular-file? base)
                [base]
                (matching-files base (relative-pattern glob ":glob")))
        re (re-pattern (if ignore-case? (str "(?i)" pattern) pattern))
        state (volatile! {:matches [] :skipped [] :files 0 :truncated? false})]
    (doseq [file files :while (not (:truncated? @state))]
      (let [shown (display-path cwd file)]
        (if (larger-than? file max-file-bytes)
          (vswap! state update :skipped conj
                  {:path shown
                   :reason (str "larger than " max-file-bytes " bytes")})
          (try
            (doseq [[i line] (map-indexed vector (fs/read-all-lines (fs/file file)))
                    :while (not (:truncated? @state))]
              (when (re-find re line)
                (vswap! state update :matches conj
                        {:path shown :line (inc i) :text line})
                (when (> (count (:matches @state)) max)
                  (vswap! state assoc :truncated? true))))
            (vswap! state update :files inc)
            (catch Exception t
              (vswap! state update :skipped conj
                      {:path shown
                       :reason (or (ex-message t) (str t))}))))))
    (let [{:keys [matches skipped files truncated?]} @state]
      {:matches (vec (take max matches))
       :skipped (vec skipped)
       :truncated? (boolean truncated?)
       :files files})))

(defn glob-verb
  "Return the local/glob eval verb bound to CWD."
  [cwd]
  (with-meta
    (fn
      ([pattern] (glob* cwd pattern nil))
      ([pattern opts] (glob* cwd pattern opts)))
    {:doc (:doc (meta #'glob*))
     :arglists '([pattern] [pattern {:keys [path max]}])}))

(defn grep-verb
  "Return the local/grep eval verb bound to CWD."
  [cwd]
  (with-meta
    (fn
      ([pattern] (grep* cwd pattern nil))
      ([pattern opts] (grep* cwd pattern opts)))
    {:doc (:doc (meta #'grep*))
     :arglists '([pattern] [pattern {:keys [path glob ignore-case? max max-file-bytes]}])}))
