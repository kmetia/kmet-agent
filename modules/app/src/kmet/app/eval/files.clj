(ns kmet.app.eval.files
  "Shared eval definition files: one verbatim source slice per file under
   `<project>/.kmet/eval/<ns>/<name>.clj` (project scope) or
   `<agent-dir>/eval/<ns>/<name>.clj` (global scope).

   The file is the address and stays reviewable text. Writes and moves take
   the EDN store's directory lock per root, because two sessions can save at
   once; a move locks both roots in path order."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.eval.definitions :as defs]
            [kmet.libs.edn-store :as edn-store]
            [kmet.libs.fs :as kfs]))

(def header
  "The first line of every kept definition file."
  ";; kmet eval: kept by the eval tool")

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

(defn- with-root-locks* [roots f]
  (let [roots (->> roots (map str) distinct sort)]
    (letfn [(run [roots]
              (if-let [root (first roots)]
                (with-root-lock* root #(run (rest roots)))
                (f)))]
      (run roots))))

(defmacro ^:private with-root-locks
  "Run BODY with every ROOTS lock held, acquired in sorted path order so two
   moves between the same roots cannot deadlock."
  [roots & body]
  `(with-root-locks* ~roots (fn [] ~@body)))

(defn write-definition!
  "Write RECORD to ROOT and return the path. A failed write leaves the old
   file in place (atomic publication)."
  [root record]
  (with-root-lock root
    (let [path (definition-path root record)]
      (kfs/publish! path (file-content record))
      path)))

(defn restore-definition!
  "Restore PATH to OLD-CONTENT, or delete it when OLD-CONTENT is nil. Used to
   roll a promote back when the session entry cannot be appended."
  [path old-content]
  (if (some? old-content)
    (kfs/publish! path old-content)
    (fs/delete-if-exists path)))

(defn delete-name!
  "Remove NAME's file under every ROOT, holding all locks. The delete path is
   derived from the address, so a losing copy in the other scope is removed
   too."
  [roots name]
  (let [roots (filter fs/exists? roots)]
    (with-root-locks roots
      (into []
            (keep (fn [root]
                    (let [path (path-for-name root name)]
                      (when (fs/delete-if-exists path) path))))
            roots))))

(defn scan
  "Read every definition file under ROOTS (a seq of `{:root :scope}`), global
   or project order as given. Returns `[{:file :scope :content}]`, sorted by
   path inside a root; an unreadable file is reported as `{:file :error}`."
  [roots]
  (into []
        (mapcat
         (fn [{:keys [root scope]}]
           (when (fs/exists? root)
             (try
               (for [file (sort (map str (fs/glob root "**/*.clj")))]
                 (try
                   {:file file :scope scope :content (slurp file)}
                   (catch Throwable t
                     {:file file :scope scope :error (ex-message t)})))
               (catch Throwable t
                 [{:root root :scope scope :error (ex-message t)}])))))
        roots))

(defn display-path
  "A path for a result line: project files relative to CWD
   (`.kmet/eval/user/f.clj`), global files under `~` (`~/.kmet/agent/...`)."
  [cwd path]
  (let [path (fs/absolutize path)
        cwd (fs/absolutize cwd)
        home (fs/absolutize (fs/home))]
    (cond
      (fs/starts-with? path cwd) (str (fs/relativize cwd path))
      (fs/starts-with? path home) (str "~/" (fs/relativize home path))
      :else (str path))))
