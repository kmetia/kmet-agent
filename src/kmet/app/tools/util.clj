(ns kmet.app.tools.util
  "Shared utilities for tool implementations — safe file traversal,
   quiet-title path shortening, etc."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private max-traverse-files 10000)

(defn safe-file-seq
  "Like file-seq but with symlink cycle protection and a max-files limit.
   Skips `.git` directories — a repo's object store is never source-search
   material."
  [dir-path]
  (let [visited (atom #{})]
    (take max-traverse-files
          (filter fs/regular-file?
                  (tree-seq
                   (fn [f]
                     (and (fs/directory? f)
                          (not= ".git" (fs/file-name f))
                          (let [cp (fs/canonicalize f)]
                            (when-not (contains? @visited cp)
                              (swap! visited conj cp)
                              true))))
                   (fn [d] (fs/list-dir d))
                   (fs/file dir-path))))))

;; ─── Runtime working directory ────────────────────────────────────────────

(def ^:dynamic *cwd*
  "The working directory tool paths resolve against (pi: the cwd the tool
   definitions were created with — createAllToolDefinitions(cwd, …); pi's
   read/write/edit resolve relative paths against the runtime cwd, not the
   process cwd). Bound by kmet.app.loop around each agent run and by the
   interactive ! flow, from the active session's cwd. nil outside those
   paths — cwd falls back to the process cwd."
  nil)

(defn cwd
  "The runtime working directory: *cwd* when a run or command bound one,
   else the process cwd."
  []
  (or *cwd* (System/getProperty "user.dir") "."))

(defn resolve-tool-path
  "Resolve PATH against the runtime working directory — pi: resolveToCwd.
   Absolute paths pass through; a relative path becomes CWD/PATH, so the
   file tools follow the session's cwd instead of the process cwd (a
   session resumed or imported from another project)."
  [path]
  (let [p (str path)]
    (if (fs/absolute? p)
      p
      (str (fs/normalize (fs/path (cwd) p))))))

(defn shorten-title-path
  "Home-relative display path for a quiet title (pi: shortenPath).
   Non-string input passes through — callers guard with string?/seq."
  [p]
  (let [home (System/getProperty "user.home" "")]
    (if (and (string? p) (seq home)
             (or (= p home) (str/starts-with? p (str home "/"))))
      (str "~" (subs p (count home)))
      p)))

(defn title-path-arg
  "First non-blank string among the :path / :file_path arg spellings, or
   nil. Nil-safe over partial streaming args (non-map, missing, blank)."
  [args]
  (when (map? args)
    (let [v (or (:path args) (:file_path args))]
      (when (and (string? v) (seq v)) v))))

(defn title-str-arg
  "Non-blank string value of arg K, or nil. Accepts keyword and string
   keys; nil-safe over partial streaming args."
  [args k]
  (when (map? args)
    (let [v (or (get args k) (get args (name k)))]
      (when (and (string? v) (seq v)) v))))
