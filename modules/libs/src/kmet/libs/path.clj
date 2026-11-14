(ns kmet.libs.path
  "Shared helpers for filesystem exploration used by opt-in search tools."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private max-traverse-files
  "Traversal cap shared with the retired builtin (10k files)."
  10000)

(defn safe-file-seq
  "Like file-seq with symlink-cycle protection, a max-files cap and `.git`
   skipped — a repo's object store is never source-search material."
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

(defn resolve-tool-path-ctx
  "Resolve PATH against the runtime working directory carried in tool CTX.
   Absolute paths pass through; missing ctx or :cwd falls back to the
   process cwd."
  [ctx path]
  (let [p (str (or path "."))]
    (if (fs/absolute? p)
      p
      (str (fs/normalize
            (fs/path (or (:cwd ctx) (System/getProperty "user.dir")) p))))))

(defn home-relative
  "PATH with the user's home directory folded to `~` (the display form tool
   output and pickers use). Only an absolute string inside home folds — a
   relative path, a blank value, a non-string or a sibling whose name merely
   starts with the home string passes through unchanged. The folded part is
   `/`-separated on every platform, and home itself renders as `~`."
  [path]
  (if-not (and (string? path) (not (str/blank? path)) (fs/absolute? path))
    path
    (let [home (fs/absolutize (str (fs/home)))
          abs (fs/absolutize path)]
      (if (or (= abs home) (fs/starts-with? abs home))
        (let [rel (str/replace (str (fs/relativize home abs))
                               fs/file-separator "/")]
          (if (empty? rel) "~" (str "~/" rel)))
        path))))

(defn display-path
  "A path for a tool result line: relative to CWD when inside it, `~/...` under
   home, else absolute. Separators are `/` on every platform."
  [cwd file]
  (let [file (fs/normalize (fs/absolutize file))
        cwd (fs/normalize (fs/absolutize cwd))]
    (if (fs/starts-with? file cwd)
      (let [rel (str/replace (str (fs/relativize cwd file))
                             fs/file-separator "/")]
        (if (str/blank? rel) "." rel))
      (home-relative (str file)))))
