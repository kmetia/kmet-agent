(ns kmet.tasks.lint
  "The `lint` / `lint-changed` tasks: clj-kondo over BOTH reader views.

   kmet's .cljc files branch between its two hosts on the :bb and :jolt reader
   features (plain code is what both run; :clj — the JVM-ish dialect, matched
   by both hosts — is left for code that must not run on a non-JVM reader).
   clj-kondo resolves reader conditionals for the standard dialects only: it
   rejects :bb and :jolt as configured features and skips those tokens in a
   file, so a plain run never sees a host branch. kmet lints two views of the
   tree and merges the findings:

     babashka view — the tree, with every file carrying a :bb branch projected
       into target/bb-lint/ where :bb is re-spelled :clj (the one feature
       clj-kondo matches), under the project config: babashka's selection.
     jolt view — the files whose selection differs from a raw read — those
       carrying :jolt (projected the same way, under the .clj-kondo-jolt
       overlay) or :bb (read raw: clj-kondo skips an unknown feature exactly as
       jolt skips it) — plus jolt/ (jolt-only code the babashka view never
       reads) and every .jolt source (Jolt-only by extension): jolt's
       selection.

   The re-spelling reproduces each host exactly: the rewrite keeps branch
   order, and a reader takes the first matching branch in file order — jolt
   scans jolt/clj/default, clj-kondo the projection's single clj — so the same
   branch wins either way, whether the source pairs :jolt with a :clj branch
   or with a :default fallback. It rewrites feature keys only — a :bb in data,
   in a string or in a namespaced keyword is left alone — so the projection is
   the source with one feature
   renamed, and a :jolt one is padded to keep every following column in place
   (a :bb one grows its line by one: there is no 3-char feature clj-kondo
   knows). Findings therefore carry the real coordinates, and one both views
   produce compares equal — it is reported once. Mirrors live under target/
   (gitignored, never read as source) at stable paths, which is what lets
   clj-kondo's cache carry unchanged files over to the next run.

   BOTH `bb lint` and `jolt lint` run both views, so either gate alone covers
   common, babashka AND jolt code."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [kmet.tasks.changed :as changed]
            [kmet.libs.json :as json]))

;; ─── reader views ─────────────────────────────────────────────────────────

(def default-paths
  "The lint corpus: the shared tree on both hosts (src/, test/, tasks/) plus
   jolt/ — the provider lib is jolt-only code, excluded from the babashka view
   and linted by the jolt one."
  ["src" "test" "tasks" "extensions" "jolt"])

(def ^:private views
  "The two reader views of the tree; every gate runs both.

   :token is the reader feature the view selects, re-spelled :clj in its
   projection. :dir is the mirror root — under target/, stable across runs so
   clj-kondo's cache carries over (a fresh path would re-lint everything).
   :scope :all lints every given file; :conditional only the host-conditional
   ones (the other view's pass covers the rest, whose selection cannot
   differ). :include-dir is a tree the view always reads, token or not;
   :include-ext/:exclude-ext do the same for the .jolt extension — the Jolt
   runtime's own source extension, so a .jolt file is Jolt code by
   construction: the babashka view must never read it and the jolt view must
   always read it, token or not. :overlay is the extra config dir layered on
   through CLJ_KONDO_EXTRA_CONFIG_DIR."
  [{:name "babashka"
    :token ":bb"
    :dir "target/bb-lint"
    :scope :all
    :exclude-ext #{"jolt"}}
   {:name "jolt"
    :token ":jolt"
    :dir "target/jolt-lint"
    :scope :conditional
    :overlay ".clj-kondo-jolt"
    :include-dir "jolt/"
    :include-ext #{"jolt"}}])

(def ^:private mirror-prefix-re
  ;; findings name mirror files; strip the prefix so they read as real paths.
  ;; One prefix per view, and every finding goes through here — which is also
  ;; what makes the two views' shared findings compare equal.
  #"target[\\/](?:bb|jolt)-lint[\\/]")

;; ─── reader-conditional feature rewriting ────────────────────────────────

(def ^:private token-enders
  "Characters that end a symbol/keyword token."
  #{\space \tab \newline \return \, \( \) \[ \] \{ \} \" \;})

(defn- skip-token
  "Index after the token starting at I."
  [s i]
  (let [n (count s)]
    (loop [j i]
      (if (or (>= j n) (contains? token-enders (nth s j)))
        j
        (recur (inc j))))))

(defn- skip-string
  "Index after the string literal whose opening quote is at I."
  [s i]
  (let [n (count s)]
    (loop [j (inc i)]
      (cond
        (>= j n) j
        (= (nth s j) \\) (recur (+ j 2))
        (= (nth s j) \") (inc j)
        :else (recur (inc j))))))

(defn- skip-char-literal
  "Index after the character literal starting at I (the backslash); \\( and
   friends are one delimiter char, \\newline a name."
  [s i]
  (let [n (count s)
        j (inc i)]
    (cond
      (>= j n) j
      (contains? token-enders (nth s j)) (inc j)
      :else (skip-token s j))))

(defn- skip-comment
  [s i]
  (let [n (count s)]
    (loop [j i]
      (if (or (>= j n) (= (nth s j) \newline)) j (recur (inc j))))))

(defn- close-frame
  "Pop STACK for a closing delimiter. A frame that was some conditional's
   branch is that branch's end, so the enclosing conditional expects a feature
   key again."
  [stack]
  (let [popped (peek stack)
        st (pop stack)]
    (if (and (:branch? popped) (= :cond (:type (peek st))))
      (conj (pop st) (assoc (peek st) :expect :feature))
      st)))

(defn- feature-spans
  "The [start end) spans of TOKEN where it is a reader-conditional feature key
   (#?(:token …) / #?@(:token …)). Walks the text once: strings, comments and
   character literals are skipped, and within a conditional the feature and
   branch positions alternate, so a :bb in data, in a namespaced keyword
   (:bb/x) or inside a branch is left alone."
  [s token]
  (let [n (count s)]
    (loop [i 0, stack [], spans []]
      (if (>= i n)
        spans
        (let [c (nth s i)
              frame (peek stack)
              feature-position? (and (= :cond (:type frame)) (= :feature (:expect frame)))
              branch-position? (and (= :cond (:type frame)) (= :branch (:expect frame)))]
          (cond
            (= c \") (recur (skip-string s i) stack spans)
            (= c \;) (recur (skip-comment s i) stack spans)
            (= c \\) (recur (skip-char-literal s i) stack spans)
            (contains? #{\space \tab \newline \return \,} c) (recur (inc i) stack spans)

            (= c \#) (let [j (inc i)]
                       (if (and (< j n) (= (nth s j) \?))
                         (let [k (if (and (< (inc j) n) (= (nth s (inc j)) \@))
                                   (+ j 2)
                                   (inc j))]
                           (if (and (< k n) (= (nth s k) \())
                             (recur (inc k)
                                    (conj stack {:type :cond :expect :feature
                                                 :branch? branch-position?})
                                    spans)
                             (recur j stack spans)))
                         (recur j stack spans)))

            (contains? #{\[ \{ \(} c)
            (recur (inc i) (conj stack {:type :form :branch? branch-position?}) spans)

            (contains? #{\] \} \)} c)
            (recur (inc i) (close-frame stack) spans)

            :else
            (let [end (skip-token s i)
                  matched? (and feature-position? (= token (subs s i end)))]
              (recur end
                     (cond
                       ;; the token took the feature slot: the next form is
                       ;; the branch
                       feature-position? (conj (pop stack) (assoc frame :expect :branch))
                       ;; a branch that is a bare token (nil, :a, 1) ends here
                       branch-position? (conj (pop stack) (assoc frame :expect :feature))
                       :else stack)
                     (cond-> spans matched? (conj [i end]))))))))))

(defn- rewrite-features
  "CONTENT with TOKEN re-spelled REPLACEMENT where it is a reader-conditional
   feature key, and nothing else. A shorter replacement (:jolt -> :clj) is
   padded with a space, which holds every following column in place; a longer
   one (:bb -> :clj) cannot be, so code after it on that same line shifts by
   one — findings still name the right row, message and file."
  [content token replacement]
  (let [spans (feature-spans content token)]
    (if (empty? spans)
      content
      (reduce (fn [acc [start end]]
                (let [pad (max 0 (- (- end start) (count replacement)))]
                  (str (subs acc 0 start)
                       replacement
                       (when (pos? pad) (apply str (repeat pad \space)))
                       (subs acc end))))
              content
              (reverse spans)))))

;; ─── files ────────────────────────────────────────────────────────────────

(def ^:private lint-exts
  ;; what clj-kondo picks up from a directory scan (.edn files are data; .jolt
  ;; is the Jolt runtime's own source extension — clj-kondo reads it as
  ;; Clojure, and the views route it to the jolt pass)
  #{"clj" "cljc" "cljs" "cljd" "clj_kondo" "jolt"})

(def ^:private glob-pattern "**.{clj,cljc,cljs,cljd,clj_kondo,jolt}")

(defn- lintable?
  [path]
  (contains? lint-exts (fs/extension (str path))))

(defn- lintable-files
  "The lintable files under DIR, recursively — one `**.{…}` pattern matches
   top-level and nested files (`**` crosses separators), the same spelling
   kmet.tasks.changed/dir-clj-files uses. target/ is build output — and this
   namespace's own mirrors — never source."
  [dir]
  (->> (fs/glob dir glob-pattern)
       (map str)
       (remove #(str/includes? % "/target/"))
       sort))

(defn- target-files
  "The files a lint run covers for PATHS: a directory expands to the lintable
   files under it, any other path is passed through as given — clj-kondo
   reports a missing file the way it always has."
  [paths]
  (->> paths
       (mapcat (fn [path]
                 (let [path (str path)]
                   (cond
                     (not (fs/exists? path)) [path]
                     (fs/directory? path) (lintable-files path)
                     :else [path]))))
       distinct
       vec))

(defn- repo-relative
  "PATH as a project-relative, normalized /-separated string. The normalize
   is what makes the two hosts agree: bb's fs/relativize (java.nio
   Path.relativize) drops a leading \"./\" itself, jolt's keeps it — and
   mirror paths and finding paths are compared literally. The separator must
   be / too: fs/normalize keeps the platform's own, so a Windows run would
   compare `src\\kmet\\x` against the /-spelled include dirs and mirror
   sources."
  [path]
  (str/replace (str (fs/normalize (fs/relativize (fs/cwd) (fs/absolutize (str path)))))
               "\\" "/"))

(defn- in-project?
  "True when PATH lives under the cwd — the only files a view can project
   (its config and cache are the project's)."
  [path]
  (not (str/starts-with? (repo-relative path) "..")))

;; ─── projections ──────────────────────────────────────────────────────────

(defn- mirror-path
  [view path]
  (when-not (in-project? path)
    (throw (ex-info (str "kmet.tasks.lint: cannot project " path " — outside the project")
                    {:path path})))
  (str (fs/file (:dir view) (repo-relative path))))

(defn- mirror-source
  "The source file a mirror copy projects, or nil when FILE is not one.
   /-separated like repo-relative — a mirror copy maps back to its source by
   a literal path comparison."
  [view file]
  (let [rel (str/replace (str (fs/normalize (fs/relativize (:dir view) (str file))))
                         "\\" "/")]
    (when-not (str/starts-with? rel "..") rel)))

(defn- mirror-files
  [view]
  (fs/glob (:dir view) glob-pattern))

(defn- projection?
  "True when CONTENT reads differently under VIEW than clj-kondo reads it raw:
   it carries the view's feature key. (Content carrying only the OTHER host's
   feature needs no projection — that feature is unknown to clj-kondo and is
   skipped, which is what the other host does too.) The str/includes? gate
   keeps the reader-conditional scan off the files that never mention the
   token."
  [view content]
  (and (str/includes? content (:token view))
       (seq (feature-spans content (:token view)))))

(defn- include-dir?
  [view file]
  (boolean (and (:include-dir view)
                (str/starts-with? (repo-relative file) (:include-dir view)))))

(defn- file-ext [file]
  (fs/extension (str file)))

(defn- included-in?
  "True when FILE belongs to VIEW besides its reader-conditional status
   (:include-dir trees, :include-ext extensions like .jolt)."
  [view file]
  (or (include-dir? view file)
      (contains? (set (:include-ext view)) (file-ext file))))

(defn- excluded-from?
  "True when FILE is out of VIEW by extension (:exclude-ext) — a .jolt file
   is Jolt-only code, so the babashka view never reads it."
  [view file]
  (contains? (set (:exclude-ext view)) (file-ext file)))

(defn- file-info
  "FILE as the views need it: its content read once, and the views whose
   reading differs from a raw read."
  [file]
  (let [content (slurp file)]
    {:file file
     :content content
     :views (filterv #(projection? % content) views)}))

(defn- projected?
  [info view]
  (boolean (some #(= (:name view) (:name %)) (:views info))))

(defn- write-mirror!
  "FILE's projection for VIEW, written unless the copy already matches — an
   untouched mtime is what keeps clj-kondo's cache entry valid."
  [view file content]
  (let [projected (rewrite-features content (:token view) ":clj")
        dest (mirror-path view file)]
    (when (or (not (fs/exists? dest)) (not= projected (slurp dest)))
      (fs/create-dirs (fs/parent dest))
      (spit dest projected))
    dest))

(defn- projected-file
  "FILE's lint target for VIEW: its mirror when the view projects it, the file
   itself otherwise."
  [view {:keys [file content] :as info}]
  (if (projected? info view) (write-mirror! view file content) file))

(defn- prune-view!
  "Drop the mirrors VIEW no longer needs: the source is gone, or it stopped
   carrying the feature. The mirror is a projection of the tree, not an
   append-only log."
  [view]
  (when (fs/directory? (:dir view))
    (doseq [file (mirror-files view)]
      (let [src (mirror-source view file)]
        (when-not (and src (fs/exists? src) (projection? view (slurp src)))
          (fs/delete file))))))

(defn- pass-targets
  "VIEW's clj-kondo targets for the file INFOS, mirrors written as needed. A
   :conditional view takes the host-conditional files only (plus its
   :include-dir trees and :include-ext extensions): everything the views read
   alike is already covered by the :all view's pass, and a file carrying just
   the other host's feature is read raw, which is this host's reading of it.
   :all drops :exclude-ext files — .jolt is Jolt-only by construction."
  [view infos]
  (let [scoped (if (= :all (:scope view))
                 (if (seq (:exclude-ext view))
                   (remove #(excluded-from? view (:file %)) infos)
                   infos)
                 (filterv (fn [info] (or (seq (:views info)) (included-in? view (:file info))))
                          infos))]
    (mapv #(projected-file view %) scoped)))

;; ─── clj-kondo ────────────────────────────────────────────────────────────

(def ^:private json-output
  "clj-kondo's findings as JSON: the two views' findings are merged, deduped
   and rendered here, where a plain text run would print two reports and a
   shared finding twice."
  "{:output {:format :json}}")

(defn- normalize-filename
  "The path a finding is reported under, project-relative. Both views must
   spell a shared finding the same way for it to dedupe — a mirror always
   yields the project-relative form, however the caller spelled the path — and
   it reads as the file a developer edits. Paths outside the project (nothing
   projects those) keep the spelling they were given."
  [filename]
  (let [path (str/replace (str filename) mirror-prefix-re "")]
    (try
      (let [rel (repo-relative path)]
        (if (str/starts-with? rel "..") path rel))
      (catch Exception _ path))))

(defn- run-clj-kondo
  "Run clj-kondo over TARGETS with JSON output; EXTRA-ENV adds to the child
   environment (a view's overlay config dir). clj-kondo's stderr passes
   through, errors included."
  [extra-env targets]
  (let [res (apply p/shell {:continue true :out :string :err :string :extra-env extra-env}
                   "clj-kondo" "--config" json-output "--lint" (mapv str targets))
        out (str (:out res))
        err (str (:err res))]
    (when (seq err)
      (binding [*out* *err*] (println (str/replace err mirror-prefix-re ""))))
    (let [parsed (try (json/parse-string out true) (catch Exception _ nil))]
      (if (:findings parsed)
        (mapv (fn [f] (-> f (update :filename normalize-filename) (update :level keyword)))
              (:findings parsed))
        (throw (ex-info (str "clj-kondo did not return JSON findings (exit " (:exit res) ")")
                        {:exit (:exit res) :stdout out :stderr err}))))))

(defn- finding-key
  [{:keys [filename row col level message type]}]
  [filename row col level message type])

(defn- dedupe-findings
  [findings]
  (->> findings
       (reduce (fn [acc f]
                 (let [k (finding-key f)]
                   (if (contains? acc k) acc (assoc acc k f))))
               {})
       vals))

(defn- summarize
  [findings]
  (let [levels (frequencies (map :level findings))]
    {:errors (get levels :error 0)
     :warnings (get levels :warning 0)
     :info (get levels :info 0)}))

(defn- print-report!
  "Print the findings in clj-kondo's own line format, sorted by position, then
   its summary line (clj-kondo reports errors and warnings; info only when
   there are any)."
  [findings duration-ms]
  (doseq [f (sort-by (fn [f] [(str (:filename f)) (or (:row f) 0) (or (:col f) 0)])
                     findings)]
    (println (str (:filename f) ":" (or (:row f) 0) ":" (or (:col f) 0) ": "
                  (name (:level f)) ": " (:message f))))
  (let [{:keys [errors warnings info]} (summarize findings)]
    (println (str "linting took " duration-ms "ms, errors: " errors ", warnings: " warnings
                  (when (pos? info) (str ", info: " info))))))

;; ─── entry points ─────────────────────────────────────────────────────────

(defn lint-paths!
  "Lint PATHS (files or directories) over both reader views, print the report
   and return the findings (clj-kondo's own shape, mirror paths normalized,
   each one once). Never exits — see lint! for the task entry point."
  [paths]
  (if (empty? paths)
    []
    (let [start (System/currentTimeMillis)
          infos (mapv file-info (target-files paths))
          _ (run! prune-view! views)
          findings (-> (mapcat (fn [view]
                                 (let [targets (pass-targets view infos)]
                                   (if (empty? targets)
                                     []
                                     (run-clj-kondo (when-let [overlay (:overlay view)]
                                                      {"CLJ_KONDO_EXTRA_CONFIG_DIR"
                                                       (str (fs/absolutize overlay))})
                                                    targets))))
                               views)
                       dedupe-findings
                       vec)]
      (print-report! findings (- (System/currentTimeMillis) start))
      findings)))

(defn- exit-on-findings!
  "Exit like clj-kondo does on findings: 3 with errors, 2 with warnings or
   info, 0 when clean."
  [findings]
  (let [{:keys [errors warnings info]} (summarize findings)]
    (when (pos? (+ errors warnings info))
      (flush)
      (System/exit (if (pos? errors) 3 2)))))

(defn lint!
  "The `lint` task: lint PATHS (default: the whole tree) in both reader views
   and exit non-zero on any finding."
  [paths]
  (exit-on-findings! (lint-paths! (if (seq paths) paths default-paths))))

(defn- changed-corpus-files
  "Changed lintable files outside the changed-file scan's source roots but
   inside the lint corpus (jolt/)."
  []
  (->> (changed/changed-files)
       (filter #(and (str/starts-with? % "jolt/") (lintable? %)))
       sort))

(defn lint-changed!
  "The `lint-changed` task: lint the changed files and their affected
   dependents in both views, or the whole tree when the clj-kondo config
   changed (the config governs both views, so a change to it re-lints all)."
  []
  (if (changed/config-changed?)
    (do (println "clj-kondo config/hooks changed — linting all files.")
        (lint! nil))
    (let [paths (distinct (concat (changed/affected-lint-files) (changed-corpus-files)))]
      (if (seq paths)
        (lint! paths)
        (println "No changed files to lint.")))))
