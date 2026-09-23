(ns kmet.libs.context
  "Project context files (AGENTS.md/CLAUDE.md) loaded into the system prompt.
   pi: core/resource-loader.js loadProjectContextFiles + loadContextFileFromDir
   + findShadowedContextFile + findGitPaths (footer-data-provider.js)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private context-file-names
  "Candidate names checked in order per directory (pi: loadContextFileFromDir)."
  ["AGENTS.override.md" "AGENTS.md" "AGENTS.MD" "CLAUDE.md" "CLAUDE.MD"])

(defn- load-context-file-from-dir
  "First existing context file in dir (pi: loadContextFileFromDir).
   Returns {:path str :content str} or nil. An unreadable file (no
   permission) is skipped like a missing one — pi warns and continues;
   a throw here would abort system-prompt building entirely."
  [dir]
  (some (fn [name]
          (let [f (str (fs/path dir name))]
            (when (and (fs/exists? f) (fs/regular-file? f))
              (try
                {:path f :content (slurp f)}
                (catch Exception _ nil)))))
        context-file-names))

(defn- norm-dir
  "Absolute, home-expanded directory string for stable dedupe."
  [d]
  (str (fs/absolutize (fs/expand-home (str d)))))

(defn- canonicalize
  "Resolve symlinks/realpath; fall back to the input (pi: canonicalizePath)."
  [p]
  (try (str (fs/real-path (str p))) (catch Exception _ (str p))))

(defn- find-git-paths
  "Walk up from DIR to the filesystem root looking for a .git entry (pi:
   findGitPaths). Returns {:repo-dir str :common-git-dir str :head-path str}
   or nil. A .git FILE whose content starts with \"gitdir: \" marks a
   worktree; the commondir file inside the gitdir (when present) points at
   the shared common git dir. A .git DIRECTORY with a HEAD file is an
   ordinary repo (common git dir = the .git dir itself). Broken .git entries
   (no HEAD) return nil so the walk continues upward — a nested repo hides
   an outer one."
  [dir]
  (loop [dir dir]
    (let [git-path (str (fs/path dir ".git"))
          result (try
                   (cond
                     (fs/regular-file? git-path)
                     (let [content (str/trim (slurp git-path))]
                       (if (str/starts-with? content "gitdir: ")
                         (let [git-dir (str (fs/absolutize (fs/path dir (str/trim (subs content 8)))))
                               head-path (str (fs/path git-dir "HEAD"))]
                           (when (fs/exists? head-path)
                             (let [commondir-path (str (fs/path git-dir "commondir"))]
                               {:repo-dir dir
                                :common-git-dir (if (fs/exists? commondir-path)
                                                  (str (fs/absolutize (fs/path git-dir (str/trim (slurp commondir-path)))))
                                                  git-dir)
                                :head-path head-path})))
                         nil))
                     (fs/directory? git-path)
                     (let [head-path (str (fs/path git-path "HEAD"))]
                       (when (fs/exists? head-path)
                         {:repo-dir dir :common-git-dir git-path :head-path head-path}))
                     :else nil)
                   ;; an unreadable/broken .git entry is skipped (pi:
                   ;; try/catch → return null — the walk continues upward)
                   (catch Exception _ nil))]
      (or result
          (let [parent (fs/parent dir)]
            (when (and parent (not= (str parent) (str dir)))
              (recur (str parent))))))))

(defn- find-shadowed-context-file
  "The main repo's context file that a nested linked worktree's own copy
   shadows: both occupy the same logical repository scope, so loading both
   applies that context twice (pi: findShadowedContextFile). Returns the
   canonical path of the main repo's context file, or nil when nothing is
   shadowed.

   The main repo root is dirname of the common git dir — valid only when the
   worktree's repo dir is a descendant of it AND the main repo's .git is the
   common git dir (a bare layout's dirname tracks nothing; a submodule's
   gitdir has no commondir, so it lands under .git/modules)."
  [cwd]
  (when-let [git-paths (find-git-paths cwd)]
    (let [common-git-dir (canonicalize (:common-git-dir git-paths))
          worktree-root (canonicalize (:repo-dir git-paths))
          main-repo-root (str (fs/parent common-git-dir))]
      ;; strictly inside: a literal "/" suffix never matched on Windows,
      ;; and fs/starts-with? alone would also accept the main repo itself
      (when (and (fs/starts-with? worktree-root main-repo-root)
                 (not= worktree-root main-repo-root))
        (when (= (canonicalize (str (fs/path main-repo-root ".git"))) common-git-dir)
          (when-let [context-file (load-context-file-from-dir worktree-root)]
            (str (fs/path main-repo-root (fs/file-name (:path context-file))))))))))

(defn load-project-context-files
  "Load context files for the system prompt (pi: loadProjectContextFiles):
   the agent dir's own AGENTS.md/CLAUDE.md first, then those found walking up
   from cwd to the filesystem root, deduped by path, nearest ancestor first.
   A nested worktree's context file shadows the main repo's same-named file
   (they share one logical repo scope); AGENTS.override.md takes precedence
   over AGENTS.md within a directory."
  [agent-dir cwd]
  (let [results (volatile! [])
        seen (volatile! #{})
        add! (fn [f]
               (when (and f (not (contains? @seen (:path f))))
                 (vswap! seen conj (:path f))
                 (vswap! results conj f)))]
    (add! (load-context-file-from-dir (norm-dir agent-dir)))
    (let [shadowed (find-shadowed-context-file (norm-dir cwd))]
      (loop [dir (fs/path (norm-dir cwd))]
        (let [context-file (load-context-file-from-dir dir)
              is-shadowed (and shadowed
                               (= (canonicalize (or (:path context-file) "")) shadowed))]
          (when (and context-file (not is-shadowed))
            (add! context-file)))
        (when-let [parent (fs/parent dir)]
          (when (not= (str parent) (str dir))
            (recur parent)))))
    @results))
