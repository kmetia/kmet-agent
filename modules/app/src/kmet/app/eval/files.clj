(ns kmet.app.eval.files
  "The eval library files: one verbatim source slice per definition under
   `<agent-dir>/eval/<ns>/<name>.clj`.

   The file is the address and stays reviewable text. Every successful
   top-level definition writes its file; loading scans the root. Writes and
   deletes take the EDN store's directory lock, because two sessions in one
   home can save at once."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.eval.definitions :as defs]
            [kmet.libs.edn-store :as edn-store]
            [kmet.libs.fs :as kfs]))

(def header
  "The first line of every library file."
  ";; kmet eval: written by the eval tool")

(defn definition-path
  "The file for RECORD under eval ROOT."
  [root record]
  (str (fs/path root
                (defs/ns-dir (:ns record))
                (str (defs/sanitize (defs/name-part record)) ".clj"))))

(defn path-for-name
  "The file a definition address NAME would occupy under ROOT — the delete
   path for a drop, independent of the record that named it."
  [root name]
  (let [i (str/index-of name "/")
        ns (if i (subs name 0 i) "user")
        part (if i (subs name (inc i)) name)]
    (str (fs/path root (defs/ns-dir ns)
                  (str (defs/sanitize part) ".clj")))))

(defn file-content
  "The reviewable file body: the header, the namespace form, and RECORD's
   verbatim source."
  [record]
  (let [source (str (:source record))]
    (str header "\n(ns " (:ns record) ")\n"
         source
         (when-not (str/ends-with? source "\n") "\n"))))

(defn- lock-path [root]
  (str (fs/path root ".lock")))

(defn- with-root-lock* [root f]
  (fs/create-dirs root)
  (edn-store/with-file-lock (lock-path root) f))

(defmacro ^:private with-root-lock
  "Run BODY with ROOT's eval-file lock held. Creates the root first: the lock
   is a directory beside the definitions."
  [root & body]
  `(with-root-lock* ~root (fn [] ~@body)))

(defn write-definition!
  "Write RECORD to ROOT and return the path. A failed write leaves the old
   file in place (atomic publication)."
  [root record]
  (with-root-lock root
    (let [path (definition-path root record)]
      (kfs/publish! path (file-content record))
      path)))

(defn write-content!
  "Write CONTENT to PATH under ROOT's file lock, atomically publishing it.
   Used by the structural editor, which patches the whole reviewable file
   rather than reconstructing it from one definition. Returns PATH."
  [root path content]
  (with-root-lock root
    (kfs/publish! path content)
    path))

(defn delete-name!
  "Remove NAME's file under ROOT, returning the deleted path or nil."
  [root name]
  (let [path (path-for-name root name)]
    (with-root-lock root
      (when (fs/delete-if-exists path) path))))

(defn scan
  "Read every definition file under ROOT. Returns `[{:file :content}]`,
   sorted by path; an unreadable file is reported as `{:file :error}`."
  [root]
  (if-not (fs/exists? root)
    []
    (try
      (mapv (fn [file]
              (try
                {:file file :content (slurp file)}
                (catch Throwable t
                  {:file file :error (ex-message t)})))
            (sort (map str (fs/glob root "**/*.clj"))))
      (catch Throwable t
        [{:root root :error (ex-message t)}]))))

(defn index
  "Read ROOT without evaluating anything: the definitions the files name and
   the files that could not be parsed. Used by `/eval` before an environment
   exists."
  [root]
  (reduce (fn [acc {:keys [file content error] :as entry}]
            (cond
              (:root entry)
              (update acc :failures conj entry)

              error
              (update acc :failures conj {:file file :error error})

              :else
              (try
                (let [parsed (defs/parse content "user")]
                  (update acc :entries into
                          (map #(select-keys (assoc % :file file)
                                             [:name :ns :kind :file])
                               (:defs parsed))))
                (catch Throwable t
                  (update acc :failures conj {:file file :error (ex-message t)})))))
          {:entries [] :failures []}
          (scan root)))

(defn display-path
  "A path for a result line: under `~` shown as `~/.kmet/agent/...`, else
   relative to CWD when it is inside it, else absolute."
  [cwd path]
  (let [path (fs/absolutize path)
        cwd (fs/absolutize cwd)
        home (fs/absolutize (fs/home))]
    (cond
      (fs/starts-with? path cwd) (str (fs/relativize cwd path))
      (fs/starts-with? path home) (str "~/" (fs/relativize home path))
      :else (str path))))
