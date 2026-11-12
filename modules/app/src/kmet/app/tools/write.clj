(ns kmet.app.tools.write
  "Write tool implementation — create or overwrite files.
   `write-file` is the shared op the tool and the eval verbs call; `execute`
   adds the tool's argument handling and success string.
   Pi: write.ts — success message matches pi's wording."
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.tools.mutation :as mutation]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.util :as tool-util]
            [kmet.libs.path :as path]
            [kmet.srce.core :as srce]))

(defn title
  "Quiet one-liner body for the write tool: verb + `~`-shortened path — pure data, or nil when the path is missing/empty (the quiet branch falls back to the tool name). Nil-safe over partial streaming args."
  [args]
  (when-let [raw-path (tool-util/title-path-arg args)]
    (str "write " (path/home-relative raw-path))))

(defn write-file
  "Write CONTENT to PATH under the file's mutation lock, creating parent
   directories. PATH resolves against the runtime cwd. Returns the character
   count the success message reports as bytes (pi wording) — the tool and the
   eval verbs share this op."
  [path content signal]
  (let [resolved (tool-util/resolve-tool-path path)
        f (io/file resolved)]
    ;; Pi: mkdir(dir, {recursive: true}) — skip when the path has no parent.
    ;; Outside the lock on purpose: it prepares the directory, not the file,
    ;; and create-dirs is idempotent under a concurrent creator.
    (when-let [parent (fs/parent f)]
      (fs/create-dirs parent))
    ;; one writer per file at a time (pi: withFileMutationQueue), so a
    ;; parallel batch cannot interleave two writes of the same file
    (mutation/with-file-lock
      resolved
      signal
      (fn []
        (spit f content)
        (count content)))))

(defn execute
  "Write content to a file (create or overwrite)."
  [{:keys [path content]} _on-update signal _ctx]
  (try
    (let [characters (write-file path content signal)]
      {:content (str "wrote " characters " "
                     (if (= 1 characters) "byte" "bytes")
                     " to " path)})
    (catch Exception e
      {:content (str "Error writing to " path ": " (ex-message e)) :is-error true})))

;; ─── The tool and its row ──────────────────────────────────────────────────

(def definition
  "The `write` tool's record — what the `:tool/write` row mounts."
  (tool/make-tool
   :name "write"
   :label "Write file"
   :description "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Automatically creates parent directories."
   :prompt-snippet "Create or overwrite files"
   :prompt-guidelines ["Use write only for new files or complete rewrites."]
   :params {:path    {:type :string :description "File path to write to (relative or absolute)"}
            :content {:type :string :description "Content to write to the file"}}
   :execute execute
   ;; the cancel signal is the third execute argument (see execute)
   :contextual? true
   :title title))

(srce/defplugin plugin
  {:name :tool/write
   :inject [:tools]
   :apply (fn [ctx config] (registry/mount-tool! ctx definition config))})
