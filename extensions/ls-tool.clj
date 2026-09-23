(ns kmet.extensions.ls-tool
  "ls — list directory contents (opt-in extension).

   The listing half of an opt-in exploration example: entries
   sorted by name, `long: true` adds type and size. Relative paths resolve
   against the runtime cwd from the tool context (:contextual? ctx),
   falling back to the process cwd; no path lists the project directory.
   Results render through the bash result renderer: plain-text tool output,
   collapsed to a line window with an expand hint.

   Enable by symlinking/copying into ~/.kmet/agent/extensions/ or
   .kmet/extensions/, then restart or /reload — see extensions/README.md."

  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.extension :as ext]
            [kmet.app.ui.tool-renderers :as renderers]))

(defn- resolve-path
  "PATH against the runtime cwd from the tool context; absolute paths pass
   through, no ctx (or no :cwd) falls back to the process cwd."
  [ctx path]
  (let [p (str (or path "."))]
    (if (fs/absolute? p)
      p
      (str (fs/normalize
            (fs/path (or (:cwd ctx) (System/getProperty "user.dir")) p))))))

(defn- execute
  [{:keys [path] :as args} _on-update _signal ctx]
  (try
    (let [dir (io/file (resolve-path ctx path))]
      (if-not (fs/directory? dir)
        {:content (str "Not a directory: " (or path ".")) :is-error true}
        (let [entries (sort-by fs/file-name (fs/list-dir dir))
              result (str/join "\n"
                               (map (fn [f]
                                      (let [name (fs/file-name f)
                                            type (if (fs/directory? f) "d" "-")
                                            size (try (fs/size f) (catch Exception _ 0))]
                                        (if (:long args)
                                          (str type " " (format "%10d" size) " " name)
                                          name)))
                                    entries))]
          {:content (str "Contents of "
                         (renderers/display-path (fs/canonicalize dir))
                         ":\n" result)})))
    (catch Exception e
      {:content (str "Error listing: " (ex-message e)) :is-error true})))

(defn init
  "Register the ls tool (an opt-in listing tool)."
  [api]
  (ext/register-tool! api
                      {:name "ls"
                       :label "List directory"
                       :description "List directory contents (default: the project directory). Entries are sorted by name; long form (`long: true`) shows type and size. Prefer find to locate files by pattern."
                       :prompt-snippet "List directory contents"
                       :params {:path {:type :string :description "Directory to list (default: current directory)" :optional? true}
                                :long {:type :boolean :description "Show type and size per entry" :optional? true}}
                       :contextual? true
                       ;; plain-text output — the bash result body fits
                       :render-result renderers/render-bash-result
                       :execute execute}))
