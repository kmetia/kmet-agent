(ns kmet.extensions.find-tool
  "find — find files whose name or path matches a pattern (opt-in extension).

   The discovery half of an opt-in search example: match file
   names/paths, return paths only, capped at 200 results. Relative paths
   resolve against the runtime cwd from the tool context (:contextual? ctx),
   falling back to the process cwd. Results render through the bash result
   renderer: plain-text tool output, collapsed to a line window with an
   expand hint.

   Enable by symlinking/copying into ~/.kmet/agent/extensions/ or
   .kmet/extensions/, then restart or /reload — see extensions/README.md."

  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.extension :as ext]
            [kmet.app.ui.tool-renderers :as renderers]))

(def ^:private max-traverse-files
  "Traversal cap shared with the retired builtin (10k files)."
  10000)

(def ^:private max-results
  "Paths shown before the result is marked truncated."
  200)

(defn- resolve-path
  "PATH against the runtime cwd from the tool context; absolute paths pass
   through, no ctx (or no :cwd) falls back to the process cwd."
  [ctx path]
  (let [p (str (or path "."))]
    (if (fs/absolute? p)
      p
      (str (fs/normalize
            (fs/path (or (:cwd ctx) (System/getProperty "user.dir")) p))))))

(defn- safe-file-seq
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

(defn- execute
  [{:keys [pattern path]} _on-update _signal ctx]
  (try
    (let [re (re-pattern pattern)
          dir (resolve-path ctx path)
          results (volatile! [])]
      (doseq [file (safe-file-seq dir)]
        (let [name (fs/file-name file)]
          (when (or (re-find re name)
                    (re-find re (str file)))
            (vswap! results conj (str file)))))
      (let [r @results]
        (if (empty? r)
          {:content (str "No files matching \"" pattern "\"")}
          {:content (str/join "\n" (take max-results r))
           :truncated (> (count r) max-results)})))
    (catch Exception e
      {:content (str "Error finding: " (ex-message e)) :is-error true})))

(defn init
  "Register the find tool (an opt-in search tool)."
  [api]
  (ext/register-tool! api
                      {:name "find"
                       :label "Find files"
                       :description "Find files whose name or path matches a pattern (regex). Returns matching file paths. Prefer over listing directories by hand."
                       :prompt-snippet "Find files by name/path pattern"
                       :params {:pattern {:type :string :description "Pattern (regex) matched against file names and paths"}
                                :path {:type :string :description "Directory to search (default: current directory)" :optional? true}}
                       :contextual? true
                       ;; plain-text output — the bash result body fits
                       :render-result renderers/render-bash-result
                       :execute execute}))
