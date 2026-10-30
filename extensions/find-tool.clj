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
            [kmet.srce.core :as srce]
            [kmet.libs.path :as path]
            [kmet.ui.tui.tool-renderers :as renderers]))

(def ^:private max-results
  "Paths shown before the result is marked truncated."
  200)

(defn- execute
  [{:keys [pattern path]} _on-update _signal ctx]
  (try
    (let [re (re-pattern pattern)
          dir (path/resolve-tool-path-ctx ctx path)
          results (volatile! [])]
      (doseq [file (path/safe-file-seq dir)]
        (let [name (fs/file-name file)]
          (when (or (re-find re name)
                    (re-find re (str file)))
            (vswap! results conj (renderers/display-path file)))))
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

;; the plugin the loader mounts; its body is `init` above
(srce/defplugin plugin
  {:name :ext/find-tool
   :apply (fn [_ctx api] (init api) nil)})
