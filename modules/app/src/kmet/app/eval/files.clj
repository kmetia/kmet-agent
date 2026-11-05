(ns kmet.app.eval.files
  "The eval library files: one verbatim source slice per definition under a
   global root (`<agent-dir>/eval`) or a cwd root
   (`<agent-dir>/eval-scopes/<shared-cwd-key>`).

   The file is the address and stays reviewable text. Every successful
   top-level definition writes its file; loading scans each root. Writes and
   deletes take the EDN store's directory lock, because two sessions in one
   home can save at once; a move takes both locks in fixed order."
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

(defn- read-root
  "Read one eval ROOT for SCOPE: parsed entries and failures, both carrying
   the scope."
  [root scope]
  (reduce (fn [acc {:keys [file content error] :as entry}]
            (cond
              (:root entry)
              (update acc :failures conj (assoc entry :scope scope))

              error
              (update acc :failures conj {:file file :error error :scope scope})

              :else
              (try
                (let [parsed (defs/parse content "user")]
                  (update acc :entries into
                          (map #(assoc (select-keys % [:name :ns :kind :doc :arglists])
                                       :file file :scope scope)
                               (:defs parsed))))
                (catch Throwable t
                  (update acc :failures conj {:file file :error (ex-message t)
                                              :scope scope})))))
          {:entries [] :failures []}
          (scan root)))

(defn index
  "Read ROOTS without evaluating anything: ROOTS is `{:global root :cwd root}`.
   Returns the parsed definitions from both layers, one per name and scope
   (the last file wins), each with `:scope` and `:active` set; a cwd entry
   shadows a global entry with the same name. Used by `/eval` for its disk
   view (the built environment's pinned roots when one exists)."
  [roots]
  (let [{global-entries :entries global-failures :failures}
        (read-root (:global roots) :global)
        {cwd-entries :entries cwd-failures :failures}
        (read-root (:cwd roots) :cwd)
        entries (vals (reduce (fn [acc entry]
                                (assoc acc [(:name entry) (:scope entry)] entry))
                              {}
                              (concat global-entries cwd-entries)))
        cwd-names (set (map :name (filter #(= :cwd (:scope %)) entries)))
        active? (fn [entry]
                  (or (= :cwd (:scope entry))
                      (not (contains? cwd-names (:name entry)))))
        entries (mapv #(assoc % :active (active? %)) entries)]
    {:entries (vec (sort-by (juxt :name #(if (= :cwd (:scope %)) 0 1)) entries))
     :failures (into (vec global-failures) cwd-failures)}))

(defn fingerprint
  "A cheap change fingerprint for ROOTS (`{:global root :cwd root}`): each
   definition file's scope, path, size and modification time, sorted. The
   prompt manifest cache compares it without reading or parsing file content."
  [roots]
  (vec
   (mapcat (fn [scope]
             (let [root (get roots scope)]
               (when (fs/exists? root)
                 (map (fn [file]
                        (let [path (str file)]
                          (try
                            [scope path (fs/size file)
                             (str (fs/last-modified-time file))]
                            (catch Throwable _
                              [scope path :unreadable]))))
                      (sort (map str (fs/glob root "**/*.clj")))))))
           [:global :cwd])))

(defn- with-move-locks
  "Hold both library locks in the fixed global-then-cwd order, so opposite
   moves cannot deadlock."
  [roots f]
  (let [global (:global roots)
        cwd (:cwd roots)]
    (fs/create-dirs global)
    (fs/create-dirs cwd)
    (if (= (str global) (str cwd))
      (with-root-lock* global f)
      (with-root-lock* global (fn [] (with-root-lock* cwd f))))))

(defn move-definition!
  "Move RECORD's file from its recorded scope to TARGET-SCOPE under ROOTS
   (`{:global root :cwd root}`). Both locks are taken in fixed order; the
   target is published first, then the source is deleted, and the target is
   removed again if that delete fails. Throws on a missing source, an
   existing target, or a failed delete. Returns the target path."
  [roots record target-scope]
  (let [source-scope (or (:scope record) :global)
        source-root (get roots source-scope)
        target-root (get roots target-scope)]
    (when-not source-root
      (throw (ex-info (str "unknown eval scope " source-scope)
                      {:type :eval/bad-scope :scope source-scope})))
    (when-not target-root
      (throw (ex-info (str "unknown eval scope " target-scope)
                      {:type :eval/bad-scope :scope target-scope})))
    (when (= source-scope target-scope)
      (throw (ex-info (str "the definition is already in the " (name source-scope)
                           " scope")
                      {:type :eval/move-same-scope :scope source-scope})))
    (let [source (or (:file record) (definition-path source-root record))
          target (definition-path target-root record)]
      (with-move-locks roots
        (fn []
          (when (fs/exists? target)
            (throw (ex-info (str "the target library file already exists: " target)
                            {:type :eval/move-collision :file target})))
          (when-not (fs/exists? source)
            (throw (ex-info (str "the source library file is missing: " source)
                            {:type :eval/move-missing :file source})))
          (let [content (slurp source)]
            (kfs/publish! target content)
            (let [deleted? (try
                             (fs/delete-if-exists source)
                             (catch Throwable t
                               (fs/delete-if-exists target)
                               (throw t)))]
              (when-not deleted?
                (fs/delete-if-exists target)
                (throw (ex-info (str "could not delete " source)
                                {:type :eval/move-delete-failed :file source}))))
            target))))))

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
