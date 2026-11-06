(ns kmet.tasks.changed
  "Dev-loop helper backing the `bb changed` / `bb *-changed` tasks: finds
   changed files and computes, via the require graph, which namespaces are
   affected. Test namespaces map 1:1 to source namespaces (test/kmet/x/test_y.clj
   ↔ src/kmet/x/y.clj, and test/kmet/tasks/y_test.clj ↔ tasks/kmet/tasks/y.clj),
   so a source change must also re-run the tests that transitively require it.

   The source roots are src/, test/, tasks/ and extensions/ (see AGENTS.md
   § File layout — tasks/ holds the task implementations). Their files are
   listed gitignore-aware — git in a repo, a pruned walk otherwise (see
   project-files) — so a new root must be added to `source-roots` here.

   extensions/ is first-class: its .clj files (source and tests) are part of
   the lint/format gates and the changed-file scan, join the require graph so
   contract changes pull them into the lint closure, and run through the
   root test runner alongside the rest of the suite.

   Change detection: git diff vs HEAD + untracked files when the project is a
   git repo; otherwise a mtime comparison against a baseline file written by
   the test gates (`bb test` / `bb test-ext`, when green and unfiltered)
   — everything newer than the last full test run counts as changed."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.tasks.modules :as modules]))

(def baseline-file
  ".kmet-changed-baseline")

(def ^:private source-roots
  "The classpath source roots the changed-file scan and the require graph
   cover, in listing order — modules.edn's module trees, extra roots and the
   extension tree (kmet.tasks.modules/scan-roots). test/ is a root too but
   holds only tests — its files reach the graph through the same scan (see
   project-files for how the roots are enumerated)."
  (modules/scan-roots))

(def ^:private changed-path-re
  "Changed-file paths that are lint/format/scan material: a .clj[c] file under
   one of `source-roots`."
  (re-pattern (str "(?:" (str/join "|" (map #(java.util.regex.Pattern/quote %)
                                            source-roots))
                   ")/.*\\.(?:clj[c]?|jolt)")))

(defn- git-repo?
  []
  (fs/exists? ".git"))

(defn- shell-out
  [& args]
  (:out (apply proc/shell {:out :string :err :string :continue true} args)))

(defn- git-changed-files
  "Tracked changes vs HEAD (staged + unstaged) plus untracked files, minus
   deleted ones. With no commits yet, `git diff HEAD` yields nothing and every
   file shows up as untracked, which is the correct bootstrap."
  []
  (->> (str/split-lines (str (shell-out "git" "diff" "--name-only" "HEAD")
                             "\n"
                             (shell-out "git" "ls-files" "--others" "--exclude-standard")))
       (remove str/blank?)
       (filter fs/exists?)
       distinct
       sort))

(defn- slashify
  "PATH with / separators. fs/glob returns the platform separator — a
   backslash on Windows — while every consumer here (path->ns, the
   changed-path regex, the extensions/ prefix checks) is written in / terms,
   so the graph keys would be `src\\kmet\\…` without this."
  [path]
  (str/replace (str path) "\\" "/"))

(def ^:private fallback-skip-dirs
  "Directory names the no-git fallback never descends into. A git repo uses
   the real ignore rules instead."
  #{"target" ".git" "node_modules" ".cpcache" "dist" ".jolt" ".lsp" ".bb"})

(defn- git-listed-files
  "The tracked plus untracked, non-ignored files under ROOTS as /-separated
   strings, or nil when git cannot answer (not a repo, git unavailable,
   absolute roots)."
  [roots]
  (when (every? #(not (fs/absolute? %)) roots)
    (try
      (let [res (apply proc/shell {:out :string :err :string :continue true}
                       "git" "ls-files" "-z" "--cached" "--others" "--exclude-standard"
                       "--" roots)]
        (when (zero? (:exit res))
          (->> (str/split (:out res) #"\u0000")
               (remove str/blank?)
               (map slashify)
               (filter #(fs/exists? %)))))
      (catch Exception _ nil))))

(defn- fallback-files
  "Files under DIR, /-separated, never descending into fallback-skip-dirs or
   symlinked directories."
  [dir]
  (letfn [(walk [d]
            (mapcat (fn [f]
                      (cond
                        (and (fs/directory? f)
                             (not (fs/sym-link? f))
                             (not (contains? fallback-skip-dirs (str (fs/file-name f)))))
                        (walk f)

                        (fs/regular-file? f)
                        [(slashify f)]

                        :else []))
                    (fs/list-dir d)))]
    (walk dir)))

(defn project-files
  "Every existing file under ROOTS with an extension in EXTS, /-separated,
   sorted, hidden entries skipped (the `fs/glob` default). A git repo lists
   through git — tracked plus untracked, ignored excluded — so gitignored
   directories (target/, caches, VCS) are never traversed; without git the
   walk prunes the standard build directories instead."
  [roots exts]
  (let [exts (set exts)
        roots (mapv str roots)
        listed (git-listed-files roots)
        files (or listed (mapcat fallback-files roots))]
    (->> files
         (filter #(contains? exts (fs/extension %)))
         (remove #(some (fn [seg] (str/starts-with? seg "."))
                        (str/split % #"[\\/]")))
         distinct
         sort
         vec)))

(defn- source-clj-files
  "The source roots' .clj/.cljc/.jolt files, gitignore-aware (see
   project-files)."
  []
  (project-files source-roots ["clj" "cljc" "jolt"]))

(defn- mtime-changed-files
  "The source-roots' .clj files modified after the baseline timestamp (mtime
   fallback without git; a missing baseline means everything changed)."
  []
  (let [base (try (Long/parseLong (str/trim (slurp baseline-file)))
                  (catch Exception _ 0))]
    (->> (source-clj-files)
         (filter #(> (.toMillis (fs/last-modified-time %)) base))
         (map slashify)
         sort)))

(defn changed-files
  "All changed, still-existing files (git mode: everything vs HEAD; fallback:
   source-root .clj files modified since the last full validation)."
  []
  (if (git-repo?)
    (git-changed-files)
    (mtime-changed-files)))

(defn changed-clj-files
  "Changed .clj/.cljc files under the source roots (src/, test/, tasks/,
   extensions/)."
  []
  (filter #(re-matches changed-path-re %) (changed-files)))

(defn mark-validated!
  "Record 'all gates green as of now' for the mtime fallback. No-op with git."
  []
  (when-not (git-repo?)
    (spit baseline-file (str (System/currentTimeMillis)))))

(defn config-changed?
  "True when clj-kondo config or hook files changed (either the project config
   or the jolt view's .clj-kondo-jolt overlay) — those force a full lint."
  []
  (boolean (some #(str/starts-with? % ".clj-kondo") (changed-files))))

(def ^:private module-root-re
  "Matches a `modules/<name>/{src,test}/` root after `path->ns` has dotted
   the path. Derived from modules.edn (like source-roots) so a new module
   cannot silently stop having its root stripped."
  (re-pattern (str "^modules\\.(?:"
                   (str/join "|" (map #(java.util.regex.Pattern/quote (str (:name %)))
                                      (modules/modules)))
                   ")\\.(?:src|test)\\.")))

(defn path->ns
  "Source/test file path to its namespace symbol
   (modules/app/src/kmet/ui/tui/model_selector.clj → kmet.ui.tui.model-selector;
   tasks/kmet/tasks/build.cljc → kmet.tasks.build;
   extensions/clojure/src/kmet/extensions/clojure/core.clj → kmet.extensions.clojure.core;
   extensions/grep-tool.clj → kmet.extensions.grep-tool). Accepts either
   separator — fs/glob yields backslashes on Windows."
  [path]
  (let [p (slashify path)
        p (cond
            (re-matches #"extensions/[^/]+\.clj" p)
            (str "kmet/extensions/" (str/replace (subs p (count "extensions/")) #"\.clj$" ".clj"))

            (re-find #"^extensions/[^/]+/(?:src|test)/" p)
            (str/replace-first p #"^extensions/[^/]+/(?:src|test)/" "")

            :else p)]
    (symbol
     (-> p
         (str/replace #"\.(?:clj[c]?|jolt)$" "")
         (str/replace "_" "-")
         (str/replace "/" ".")
         (str/replace module-root-re "")
         (str/replace #"^test\.|^tasks\." "")))))

(defn read-ns-form
  "First form of PATH (the ns form), or nil when unreadable. Public for the
   seam-direction guard (kmet.test-seam-requires), which reads edges this
   namespace's graph does not need."
  [path]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader path))]
      (read {:eof ::eof :read-cond :allow} r))
    (catch Exception _ nil)))

(defn ns-requires
  "The kmet.* namespace symbols NS-FORM requires. Handles vector entries,
   prefix-list entries (`(kmet.libs [a :as x] b)`) and bare symbols.
   Returns #{} for a valid ns form without a :require clause (the namespace
   still joins the graph), nil for non-ns forms."
  [ns-form]
  (when (and (seq? ns-form) (= 'ns (first ns-form)))
    (let [clause (some #(when (and (seq? %) (= :require (first %))) (rest %))
                       ns-form)]
      (if clause
        (letfn [(libs [form prefix]
                  (cond
                    (vector? form) (when (symbol? (first form))
                                     [(symbol (str prefix (when prefix ".") (first form)))])
                    (list? form) (mapcat #(libs % (first form)) (rest form))
                    (symbol? form) [(symbol (str prefix (when prefix ".") form))]
                    :else []))]
          (->> (mapcat #(libs % nil) clause)
               (filter #(str/starts-with? (str %) "kmet."))
               distinct
               set))
        #{}))))

(defonce ^:private scan-cache (atom nil))

(defn- source-stamp
  "[[path mtime-ms] ...] identifying the scanned source tree — the cache
   validity key for scan-graph."
  [files]
  (mapv (fn [f] [(str f) (.toMillis (fs/last-modified-time f))]) files))

(defn- scan-graph
  "The require graph (ns → set of required kmet.* nss) and ns → file path,
   from every .clj file under the source roots. Extension namespaces join the
   graph so changes to the extension contract (kmet.extension, kmet.tui.*,
   kmet.libs.*) pull dependent extension files into the lint closure.
   Cached per source-tree stamp: the affected-* helpers scan repeatedly in
   one process (bb test-changed/test-ext-changed and their tests) and the
   read/parse is the expensive part."
  []
  (let [files (source-clj-files)
        stamp (source-stamp files)]
    (if (= stamp (:stamp @scan-cache))
      (:scan @scan-cache)
      (let [scan (reduce (fn [acc f]
                           (let [path (str f)
                                 ns-sym (path->ns path)
                                 form (read-ns-form path)
                                 reqs (and form (ns-requires form))]
                             (if reqs
                               (-> acc
                                   (update :graph assoc ns-sym reqs)
                                   (update :paths assoc ns-sym path))
                               acc)))
                         {:graph {} :paths {}}
                         files)]
        (reset! scan-cache {:stamp stamp :scan scan})
        scan))))

(defn- reverse-graph
  "ns → set of namespaces that require it."
  [graph]
  (reduce-kv (fn [acc ns-sym reqs]
               (reduce (fn [a r] (update a r (fnil conj #{}) ns-sym)) acc reqs))
             {} graph))

(defn- closure
  "ROOTS plus every namespace reachable from them through EDGES — the
   reverse graph walks to dependents, the forward graph to dependencies."
  [roots edges]
  (loop [frontier (seq roots) seen (set roots)]
    (if-let [n (first frontier)]
      (let [deps (edges n #{})]
        (recur (concat (rest frontier) (remove seen deps))
               (into seen deps)))
      seen)))

(defn- test-ns?
  "True for a test namespace: the conventional test-* last segment, or the
   -test suffix (kmet.tasks.build-test / kmet.tasks.build-jolt-test mirror their src
   namespace, so the suffix is the only marker they carry — without it the
   changed-file loop never selects them, even when they themselves changed)."
  [ns-sym]
  (let [last-seg (last (str/split (str ns-sym) #"\."))]
    (or (str/starts-with? last-seg "test-")
        (str/ends-with? last-seg "-test"))))

(defn affected-test-nss-by
  "Test namespaces affected by CHANGED-NSS: the changed ones plus every test
   transaction closure reach test namespaces that transitively require them,
   including the extension tests that now run through the root runner."
  [changed-nss]
  (let [{:keys [graph paths]} (scan-graph)
        rev (reverse-graph graph)
        root? (fn [ns-sym]
                (graph ns-sym))
        roots (filter root? changed-nss)
        tests (set (filter (fn [ns-sym]
                             (test-ns? ns-sym))
                           (keys paths)))]
    (->> (closure roots rev) (filter tests) sort)))

(defn affected-test-nss
  "Test namespaces affected by the currently changed files."
  []
  (affected-test-nss-by (map path->ns (changed-clj-files))))

(defn affected-lint-files-by
  "Files to lint for CHANGED-PATHS: the paths themselves, every affected
   dependent (a changed signature is only flagged at the call site), and the
   transitive dependencies of that set — clj-kondo resolves qualified vars
   across the files of one invocation only, so a file linted without its
   requires reports their vars as unresolved."
  [changed-paths]
  (let [{:keys [graph paths]} (scan-graph)
        rev (reverse-graph graph)
        roots (filter graph (map path->ns changed-paths))
        dependents (closure roots rev)
        lint-set (closure dependents graph)]
    (->> (concat changed-paths (keep paths lint-set))
         distinct
         sort)))

(defn affected-lint-files
  "Files to lint for the currently changed files."
  []
  (affected-lint-files-by (changed-clj-files)))

(defn extension-changed-files
  "Changed .clj files under extensions/. Their tests run from inside the
   extension directory, never from the root runner — the `bb *-changed`
   test tasks print a hint instead of silently skipping them."
  []
  (filter #(str/starts-with? % "extensions/") (changed-clj-files)))
