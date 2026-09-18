(ns kmet.app.tools.write
  "Write tool implementation — create or overwrite files.
   Pi: write.ts — success message matches pi's wording."
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.tools.util :as tool-util]))

(defn title
  "Quiet one-liner body for the write tool: verb + `~`-shortened path — pure data, or nil when the path is missing/empty (the quiet branch falls back to the tool name). Nil-safe over partial streaming args."
  [args]
  (when-let [raw-path (tool-util/title-path-arg args)]
    (str "write " (tool-util/shorten-title-path raw-path))))

(defn execute
  "Write content to a file (create or overwrite)."
  [{:keys [path content]}]
  (try
    ;; relative paths resolve against the runtime cwd (pi: resolveToCwd), not
    ;; the process cwd — a session resumed from another project
    (let [f (io/file (tool-util/resolve-tool-path path))]
      ;; Pi: mkdir(dir, {recursive: true}) — skip when the path has no parent
      (when-let [parent (fs/parent f)]
        (fs/create-dirs parent))
      (spit f content)
      {:content (str "Successfully wrote " (count content) " bytes to " path)})
    (catch Exception e
      {:content (str "Error writing to " path ": " (ex-message e)) :is-error true})))
