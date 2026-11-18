(ns kmet.app.eval.persistence
  "Writing a definition to its scope's library file and indexing the
   record: redefinitions edit the existing file so sibling definitions
   survive, an undocumented definition stays live, and `save` adds a
   docstring without re-evaluating. The environment's record model and
   root lookup come from `kmet.app.eval.env`."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.eval.definitions :as defs]
            [kmet.app.eval.env :refer [->name active-record add-record! bad-request!
                                       clear-unwritten! env-ns extension-name? qualify-name
                                       record-failure! record-scope root-for scope-label
                                       stack-for]]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.eval.library :refer [library-entry]]
            [kmet.libs.clj-edit :as clj-edit]))

(defn- source-siblings
  "Other names named by RECORD's own source form (a multi-name `declare`)."
  [record]
  (->> (:defs (defs/parse (:source record) (:ns record)))
       (map :name)
       (remove #{(:name record)})
       distinct))

(defn sibling-names
  "Other names sharing RECORD's file."
  [record others]
  (->> others
       (keep (fn [other]
               (when (and (= (:file other) (:file record))
                          (not= (:name other) (:name record)))
                 (:name other))))
       distinct))

(defn file-siblings
  "Other definitions sharing RECORD's library file or source form — a file or
   a multi-name form must not be moved for one address."
  [env record]
  (distinct
   (concat (source-siblings record)
           (sibling-names record (mapcat identity (vals @(:library env)))))))

(defn- existing-record
  "The loaded record for NAME in SCOPE, or nil."
  [env name scope]
  (some #(when (= scope (record-scope %)) %) (stack-for env name)))

(defn- write-redefinition!
  "Write a redefinition to its existing file. A file that also defines other
   addresses is edited structurally so the siblings survive; otherwise the
   file is replaced with the clean generated content. Returns `[path record]`,
   where a structural edit's record carries the file's formatted source."
  [env root record]
  (let [existing (existing-record env (:name record) (:scope record))
        path (:file existing)
        siblings (when existing (file-siblings env existing))]
    (if (and path (fs/exists? path))
      (if (seq siblings)
        (let [content (slurp path)
              result (try (clj-edit/edit-definition-source
                           content path existing (:source record))
                          (catch Throwable t
                            (bad-request! (ex-message t) {:name (:name record)})))]
          (when (:is-error result)
            (bad-request! (:content result) {:name (:name record) :file path}))
          (let [content (:content result)
                stored (some #(when (= (:name record) (:name %)) %)
                             (:defs (defs/parse content "user")))]
            (eval-files/write-content! root path content)
            [path (if stored
                    (assoc record
                           :source (:source stored)
                           :doc (:doc stored)
                           :arglists (:arglists stored)
                           :kind (:kind stored))
                    record)]))
        [(eval-files/write-definition! root record path) record])
      [(eval-files/write-definition! root record) record])))

(defn- defmethod-multimethod
  "The multimethod address of a defmethod record name: the part before the
   printed dispatch."
  [name]
  (let [i (str/index-of name " ")]
    (if i (subs name 0 i) name)))

(defn- documented?
  "A record may be written when it carries a non-blank docstring, or when it
   is a defmethod of a loaded multimethod. A method has no docstring slot; it
   belongs to its documented multimethod."
  [env record]
  (or (not (str/blank? (:doc record)))
      (and (= :defmethod (:kind record))
           (some? (active-record env (defmethod-multimethod (:name record)))))))

(defn- live-only-line
  "The result line for a definition that stays live without a library file:
   an undocumented definition (new or redefining a saved name), or a method
   whose multimethod is not saved."
  [record scope saved?]
  (str "[eval: defined " (:name record) " (" (scope-label scope)
       "; live-only: "
       (cond
         (= :defmethod (:kind record)) "its multimethod is not saved"
         saved? (str "no docstring; the saved file is unchanged; (save '"
                     (:name record) " \"doc\") updates it")
         :else (str "no docstring; (save '" (:name record)
                    " \"doc\") saves it"))
       "]"))

(defn apply-definition!
  "Write RECORD to its scope's library and index it. A source-provided key
   stays live but is never written, and is remembered as a model shadow so
   later source reads do not overwrite it. A definition in an extension
   namespace is live-only for the same reason. An undocumented definition
   stays live for the session and is not written, so a saved definition
   redefined without a docstring keeps its saved file unchanged. A defmethod
   of a loaded multimethod is written with it. A failed write is recorded and
   reported; the live definition remains."
  [env record]
  (let [name (:name record)
        scope (or (:scope record) :cwd)
        record (assoc record :scope scope)]
    (cond
      (contains? @(:source-keys env) name)
      (do (swap! (:model-shadows env) conj name)
          {:line (str "[eval: redefined " name " (source; not stored)]")})

      (extension-name? name)
      (do (swap! (:model-shadows env) conj name)
          (swap! (:unwritten env) conj name)
          (swap! (:unwritten-records env) assoc name record)
          {:line (str "[eval: defined " name " (extension; live-only)]")
           :entry (assoc (library-entry record) :active true)})

      (not (documented? env record))
      (let [saved? (some? (existing-record env name scope))]
        (swap! (:unwritten env) conj name)
        (swap! (:unwritten-records env) assoc name record)
        {:line (live-only-line record scope saved?)
         :entry (assoc (library-entry record) :active true)})

      :else
      (try
        (let [root (root-for env scope)
              [path record] (write-redefinition! env root record)
              record (assoc record :file path :root root)]
          (add-record! env record)
          (clear-unwritten! env name)
          {:line (str "[eval: defined " name " (" (scope-label scope) ")]")
           :entry (assoc (library-entry record) :active true)})
        (catch Throwable t
          (swap! (:unwritten env) conj name)
          (swap! (:unwritten-records env) assoc name record)
          (record-failure! (:failures env)
                           {:name name :error (ex-message t) :scope scope})
          {:line (str "[eval: define failed " name " (" (scope-label scope) "): "
                      (ex-message t) "]")
           :error (ex-message t)})))))

(defn- save-record
  "NAME's unwritten record when it can be saved, with `:method?`; throws the
   directed refusal for a missing or already-saved record, a method whose
   multimethod is not saved, or a kind with no docstring slot."
  [env name]
  (let [record (get @(:unwritten-records env) name)
        method? (= :defmethod (:kind record))
        parent-saved? (and method?
                           (some? (active-record env
                                                 (defmethod-multimethod name))))]
    (cond
      (nil? record)
      (bad-request! (if (active-record env name)
                      (str name " is already saved; use patch or edit-form")
                      (str "no session-only definition named " name))
                    {:name name})

      (and method? (not parent-saved?))
      (bad-request! (str name " is a defmethod; keep its multimethod first")
                    {:name name})

      (and (not method?) (not (defs/docstring-kind? (:kind record))))
      (bad-request! (str name " is a " (name (:kind record))
                         "; that form has no docstring slot")
                    {:name name})

      :else {:record record :method? method?})))

(defn- save-doc
  "DOC trimmed, else RECORD's docstring; throws when a non-method has none."
  [record method? doc]
  (let [doc (when-not method?
              (or (some-> doc str/trim not-empty)
                  (some-> (:doc record) str/trim not-empty)))]
    (when (and (not method?) (not doc))
      (bad-request! (str "save needs a docstring for " (:name record))
                    {:name (:name record)}))
    doc))

(defn- save-source
  "RECORD's source with DOC inserted when the live form has no docstring."
  [record doc]
  (if (str/blank? (:doc record))
    (or (defs/add-docstring (:source record) doc)
        (bad-request! (str "cannot add a docstring to " (:name record))
                      {:name (:name record)}))
    (:source record)))

(defn- save-edit
  "RECORD with DOC added as its docstring and its source edited; a defmethod
   is saved unchanged under its multimethod."
  [record method? doc]
  (let [doc (save-doc record method? doc)]
    (if method?
      record
      (assoc record :doc doc :source (save-source record doc)))))

(defn save-definition!
  "Save RAW-NAME's live-only definition, adding DOC as its docstring when
   the live form has none. The source is edited structurally; nothing is
   re-evaluated, because the live definition already runs. A defmethod is
   saved with its multimethod when that multimethod is already saved.
   Refuses a name that is already saved, source-provided, an extension, a
   method whose multimethod is not saved, or a kind with no docstring slot."
  [env raw-name doc]
  (let [name (when-let [raw (->name raw-name)]
               (qualify-name (env-ns env) raw))]
    (when-not name
      (bad-request! (str "save needs a definition name, got " (pr-str raw-name))
                    {:name raw-name}))
    (when (contains? @(:source-keys env) name)
      (bad-request! (str name " is provided by a source; source definitions stay live-only")
                    {:name name}))
    (when (extension-name? name)
      (bad-request! (str name " is an extension definition; extensions stay live-only")
                    {:name name}))
    (let [{:keys [record method?]} (save-record env name)
          result (apply-definition! env (save-edit record method? doc))]
      (if-let [error (:error result)]
        (bad-request! (str "save failed: " error) {:name name})
        (do (swap! (:change-lines env) conj (:line result))
            nil)))))
