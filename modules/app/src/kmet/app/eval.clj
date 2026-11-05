(ns kmet.app.eval
  "The `:eval` service: one persistent SCI environment per agent session over
   the agent home's global and cwd eval libraries.

   A session's environment is built lazily on its first eval by scanning the
   global `<agent-dir>/eval` root and the pinned cwd's
   `<agent-dir>/eval-scopes/<shared-cwd-key>` root. Every successful top-level
   definition writes its file immediately (cwd by default, global when
   requested or when it redefines an active global name); a redefinition
   overwrites the active layer's file. A new session or a restarted process
   loads both layers; an already-built environment is a runtime cache and
   never refreshes. `:drop` and `(eval/forget 'name)` delete the active
   layer's file and unbind the live name, restoring a shadowed record when one
   exists; `(eval/move)` moves one record between layers. Sessions share the
   files but not the environment. A broken file is recorded under `:failures`
   and skipped, never fatal.

   `kmet.app.tools.eval` owns the model-facing tool; `kmet.app.eval.files`
   owns the reviewable files and `kmet.app.eval.definitions` the pure parse
   and address rules."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.config :as cfg]
            [kmet.app.eval.definitions :as defs]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.eval.format :as eval-format]
            [kmet.app.eval.patch :as patch]
            [kmet.app.eval.sources :as eval-sources]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.session.store :as session-store]
            [kmet.app.tools.bash :as bash-tool]
            [kmet.app.tools.edit :as edit-tool]
            [kmet.app.tools.read :as read-tool]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.script :as script]
            [kmet.app.tools.util :as tool-util]
            [kmet.app.tools.write :as write-tool]
            [kmet.libs.clj-edit :as clj-edit]
            [kmet.libs.concurrent :as concurrent]
            [kmet.srce.core :as srce]
            [sci.core :as sci]))

;; ─── Session and root ────────────────────────────────────────────────────

(defn- resolve-session [session]
  (or session (extensions/get-session)))

(def ^:private build-timeout-ms
  "Bound on one library file evaluated during a rebuild. Interpreted code is
   interrupted at the deadline and recorded as a failure; host-blocked code
   still has the sandbox's documented limitation."
  10000)

(defn- rearm-build!
  "Point ENV's interrupt holder at a fresh rebuild deadline."
  [env]
  (script/rearm! (:interrupt env)
                 {:cancelled nil
                  :deadline (+ (concurrent/monotonic-ms) build-timeout-ms)
                  :abort (atom nil)}))

(defn- library-root
  "The global eval library root for SERVICE: `<agent-dir>/eval`."
  [service]
  (str (fs/path (:agent-dir service) "eval")))

(defn- cwd-library-root
  "The cwd eval library root for SERVICE and CWD:
   `<agent-dir>/eval-scopes/<shared-cwd-key>`."
  [service cwd]
  (str (fs/path (:agent-dir service) "eval-scopes"
                (session-store/encode-cwd cwd))))

(defn- roots-for
  "The two eval library roots for SERVICE and CWD."
  [service cwd]
  {:global (library-root service)
   :cwd (cwd-library-root service cwd)})

;; ─── Names ────────────────────────────────────────────────────────────────

(defn- ->name [x]
  (cond
    (string? x) x
    (symbol? x) (str x)
    (keyword? x) (subs (str x) 1)
    :else nil))

(defn- bad-request! [message data]
  (throw (ex-info message (assoc data :type :eval/bad-request))))

(defn- env-ns [env]
  (or (some-> @(:sticky-ns env) str) "user"))

(defn- qualify-name [ns name]
  (if (str/includes? name "/") name (str ns "/" name)))

(defn- normalize-scope
  "Normalize an optional scope option to `:cwd`, `:global`, or nil."
  [scope]
  (if (nil? scope)
    nil
    (let [s (cond
              (or (keyword? scope) (symbol? scope)) (name scope)
              (string? scope) scope
              :else nil)]
      (case s
        "cwd" :cwd
        "global" :global
        (bad-request! (str "scope must be cwd or global, got " (pr-str scope))
                      {:scope scope})))))

(defn- scope-label [scope]
  (name (or scope :active)))

(defn- record-scope [record]
  (or (:scope record) :global))

(defn- stack-for
  "The loaded records for NAME in precedence order, `[global cwd]`."
  [env name]
  (get @(:library env) name))

(defn- active-record [env name]
  (peek (stack-for env name)))

(defn- root-for [env scope]
  (get-in env [:roots (or scope :global)]))

(defn- file-present?
  "Does the ROOTS map's SCOPE hold NAME's file?"
  [roots scope name]
  (fs/exists? (eval-files/path-for-name (get roots scope) name)))

(defn- ensure-no-siblings!
  "Refuse a move whose file also defines other addresses."
  [name siblings]
  (when-let [siblings (seq siblings)]
    (bad-request! (str "cannot move " name
                       ": its library file also defines "
                       (str/join ", " siblings))
                  {:name name :siblings (vec siblings)})))

(defn- missing-definition!
  ([name scope] (missing-definition! name scope nil))
  ([name scope extra]
   (bad-request! (if scope
                   (str "no eval library definition named " name
                        " in the " (scope-label scope) " scope")
                   (str "no eval library definition named " name))
                 (merge {:name name :scope scope} extra))))

(defn- target-scope
  "The scope a new record for NAME writes to: the active layer when NAME is
   loaded, else REQUESTED, else `:cwd`. A REQUESTED scope that names a
   non-active layer is refused."
  [env name requested]
  (let [active (active-record env name)
        current (some-> active record-scope)]
    (cond
      (and current requested (not= current requested))
      (bad-request! (str name " is active in the " (scope-label current)
                         " scope; cannot target " (scope-label requested))
                    {:name name :scope requested :active-scope current})
      current current
      requested requested
      :else :cwd)))

(defn- known-in-scope?
  "Is NAME present in SCOPE, either loaded or as a file? A source-provided
   key and an unwritten definition with no library stack have no layer, and
   keep their existing drop behavior for any scope."
  [env name scope]
  (boolean
   (or (some #(= scope (record-scope %)) (stack-for env name))
       (file-present? (:roots env) scope name)
       (contains? @(:source-keys env) name)
       (and (contains? @(:unwritten env) name)
            (empty? (stack-for env name))))))

(defn- known-name?
  "A drop/forget target: loaded from either layer, present as a file in either
   root, a source-provided key, or a live definition that was not written (a
   failed write or an errored call)."
  [env name]
  (boolean
   (or (seq (stack-for env name))
       (some #(file-present? (:roots env) % name) [:cwd :global])
       (contains? @(:source-keys env) name)
       (contains? @(:unwritten env) name))))

(defn- target-known?
  "Is a drop/forget target present in ENV?"
  [env {:keys [name scope]}]
  (if scope
    (known-in-scope? env name scope)
    (known-name? env name)))

(defn- drop-target-known?
  "Is a drop target present in ENV, or as a file under ROOTS without one?"
  [env roots {:keys [name scope] :as target}]
  (if env
    (target-known? env target)
    (if scope
      (file-present? roots scope name)
      (or (file-present? roots :cwd name)
          (file-present? roots :global name)))))

(declare unbind!)

;; ─── Sources ──────────────────────────────────────────────────────────────

(defn- source-registrations [service]
  (if-let [handle (srce/get (:agent-ctx service) :tools)]
    (registry/get-eval-sources handle)
    []))

(defn- source-verbs
  "The per-call verb table a `sources-fn` receives. The functions are the
   shared file operations; cwd resolution comes from the dynamic bindings
   around the eval thread (and around the source call itself)."
  [signal]
  {:edit (fn [path old-text new-text]
           (edit-tool/edit-file path [{:old-text (str old-text)
                                       :new-text (str new-text)}]
                                signal))
   :write-file (fn [path content]
                 (write-tool/write-file path (str content) signal))})

(defn- call-source
  [service source cwd signal]
  (let [args {:cwd cwd
              :project-dir (str (fs/path cwd ".kmet"))
              :agent-dir (:agent-dir service)
              :signal signal
              :verbs (source-verbs signal)}]
    (try
      (binding [tool-util/*cwd* cwd
                bash-tool/*shell* bash-tool/*shell*
                bash-tool/*session-env-fn* bash-tool/*session-env-fn*
                read-tool/*skill-resolver* read-tool/*skill-resolver*]
        (eval-sources/normalize-source (:id source) ((:sources-fn source) args)))
      (catch Throwable t
        {:id (:id source) :error (or (ex-message t) (str t))}))))

(defn- resolve-sources
  "Read every registered source once for CWD/SIGNAL. Sources are merged in
   registration order, so a later source wins a shared key. A throwing or
   invalid source contributes nothing; its error is reported in :info and
   :errors."
  [service cwd signal]
  (let [registrations (source-registrations service)
        results (mapv #(call-source service % cwd signal)
                      registrations)
        good (filterv #(contains? % :values) results)
        errors (mapv #(select-keys % [:id :error])
                     (filterv #(contains? % :error) results))
        values (reduce (fn [acc source] (merge acc (:values source))) {} good)
        commands (reduce (fn [acc source] (merge acc (:commands source))) {} good)]
    {:ids (mapv :id registrations)
     :values values
     :keys (set (keys values))
     :commands commands
     :info (mapv (fn [result]
                   (if-let [error (:error result)]
                     {:id (:id result) :error error}
                     (select-keys result [:id :doc :verbs])))
                 results)
     :errors errors}))

(defn- source-var
  [ns-name name {:keys [value macro? doc arglists]}]
  (let [ns (sci/create-ns ns-name)]
    (sci/new-var name value
                 (cond-> {:ns ns :name name}
                   macro? (assoc :macro true)
                   (some? doc) (assoc :doc doc)
                   (some? arglists) (assoc :arglists arglists)))))

(defn- source-namespaces
  "The SCI vars for VALUES not excluded by SKIP?. A source value and a fixed
   namespace (the core `user` helpers) merge in one map."
  [values skip?]
  (reduce-kv
   (fn [acc key entry]
     (if (skip? key)
       acc
       (let [i (str/index-of key "/")
             ns-name (symbol (subs key 0 i))
             name (symbol (subs key (inc i)))]
         (assoc-in acc [ns-name name] (source-var ns-name name entry)))))
   {}
   values))

(defn- source-known?
  "Does the model own NAME in this environment (a library definition, an
   unwritten live definition, or a live shadow of a source key)? A source
   never overwrites it."
  [env name]
  (or (get @(:library env) name)
      (contains? @(:unwritten env) name)
      (contains? @(:model-shadows env) name)))

(defn- seed-source-value!
  "Restore one source value after a model shadow is dropped. Returns an error
   string or nil."
  [env name entry]
  (try
    (doseq [[ns-name vars] (source-namespaces {name entry} (constantly false))]
      (sci/add-namespace! (:sci-ctx env) ns-name vars))
    nil
    (catch Throwable t (ex-message t))))

(defn- refresh-sources!
  "Resolve sources for this call and make the environment's live source layer
   match them: remove keys a now-unloaded source contributed (unless the model
   owns them), remember every key ever contributed as non-storable, and build
   the SCI namespace additions for a new call. Returns the namespaces, info,
   errors and commands."
  [service env cwd signal]
  (let [{:keys [ids values keys commands info errors]}
        (resolve-sources service cwd signal)
        stale (remove keys @(:active-source-keys env))]
    (doseq [name stale
            :when (not (source-known? env name))]
      (unbind! env name nil))
    (swap! (:source-keys env) into keys)
    (reset! (:active-source-keys env) keys)
    (reset! (:source-ids env) ids)
    (reset! (:source-values env) values)
    (reset! (:source-info env) info)
    (reset! (:source-errors env) errors)
    {:namespaces (source-namespaces values #(source-known? env %))
     :commands commands
     :info info
     :errors errors}))

(defn- current-source-seed
  "The source layer already resolved for ENV, without invoking its fns again.
   Used by the first call after a rebuild: the build read the source layer to
   compile the library, so re-reading it immediately would double every
   source's work (and its side effects)."
  [env]
  {:namespaces (source-namespaces @(:source-values env)
                                  #(source-known? env %))
   :info @(:source-info env)
   :errors @(:source-errors env)
   :commands {}})

;; ─── Library index ────────────────────────────────────────────────────────

(defn- library-entry [record]
  (select-keys record [:name :ns :kind :doc :arglists :file :scope]))

(defn- library-entries
  "The service's `(eval/library)` value: every loaded record, with `:scope`
   and `:active` set. A cwd record shadows the global record of the same
   name."
  [env]
  (->> @(:library env)
       (mapcat (fn [[_ stack]]
                 (map-indexed (fn [i record]
                                (assoc (library-entry record)
                                       :active (= i (dec (count stack)))))
                              stack)))
       (sort-by (juxt :name #(if (= :cwd (:scope %)) 0 1)))
       vec))

(defn- source-line [source]
  (if-let [error (:error source)]
    (str "- " (:id source) ": " error)
    (str "- " (:id source)
         (when-let [doc (:doc source)] (str ": " doc))
         (when (seq (:verbs source))
           (str "\n" (str/join "\n" (map eval-format/verb-line (:verbs source))))))))

(defn- definition-line
  "One loaded definition's interface line: scope, kind and arglists, the
   first docstring line, or the file when there is no other interface."
  [{:keys [kind doc arglists file] :as entry}]
  (let [doc-line (eval-format/doc-line doc)
        scope (or (:scope entry) :global)
        shadow (when (false? (:active entry)) ", shadowed")]
    (str "  - " (:name entry) " " (when kind (name kind))
         (eval-format/arglists-text arglists)
         " (" (scope-label scope) shadow ") — "
         (cond
           doc-line doc-line
           (seq arglists) "(no docstring)"
           file (str "(no docstring; see " file ")")
           :else "(no docstring)"))))

(defn- environment-help
  "The `(eval/help)` text: sources and their verbs, the loaded library with
   each definition's interface, the common script surface, and the
   persistence/reporting rules."
  [env]
  (let [sources @(:source-info env)
        entries (library-entries env)]
    (str "Eval sources:\n"
         (if (seq sources)
           (str/join "\n" (map source-line sources))
           "  (none)")
         "\nLibrary definitions: " (count entries)
         "\nScope: cwd " (get-in env [:roots :cwd])
         " (shadows the global layer)"
         "\n       global " (get-in env [:roots :global])
         "\nNew definitions write to cwd; pass :scope :global for a new global "
         "name. A redefinition keeps the active layer."
         (when (seq entries)
           (str "\n" (str/join "\n" (map definition-line entries))))
         "\nFailures: " (count @(:failures env))
         "\nSandbox: Clojure/SCI — " script/sandbox-summary
         "\nPersistence: top-level definitions are written to the library; "
         "let/fn bindings are not. New sessions and restarts load the library; "
         "this session's environment is a cache. A call that ends in a normal "
         "error writes nothing; its definitions stay live and can be forgotten."
         "\nDiscover: (all-ns) lists namespaces, (ns-publics 'ns) lists a "
         "namespace's vars, and (eval/library) lists loaded definitions. "
         "(eval/sources) lists the registered sources and their verbs; "
         "(eval/describe 'name) describes one definition or source verb "
         "(kind, docstring, arglists, file, scope, source), and "
         "(eval/describe 'name key) returns one field."
         "\nReporting: (emit value) or end on the final value; stdout and "
         "stderr are discarded."
         "\nUse (eval/library), (eval/sources), (eval/describe 'name), "
         "(eval/forget 'name), (eval/patch 'name old new), "
         "(eval/edit-form 'name new-source), "
         "(eval/move 'name :global|:cwd).")))

(defn- describe-entry
  "The interface and implementation of RAW-NAME, resolved against the same
   names the rest of the eval vocabulary uses: a library definition, a
   source-provided key, or a live definition that was never written (a failed
   write or a call that ended in a normal error). A source key has no file and
   no scope; an unwritten definition has no authoritative file."
  [env raw-name]
  (let [raw-name (->name raw-name)
        name (when raw-name (qualify-name (env-ns env) raw-name))]
    (when-not name
      (bad-request! (str "describe needs a definition name, got "
                         (pr-str raw-name))
                    {:name raw-name}))
    (let [unwritten? (contains? @(:unwritten env) name)
          unwritten-record (get @(:unwritten-records env) name)
          library-record (active-record env name)
          source-entry (get @(:source-values env) name)]
      (cond
        unwritten?
        {:name name
         :kind (:kind unwritten-record)
         :doc (:doc unwritten-record)
         :arglists (:arglists unwritten-record)
         :file nil
         :scope (record-scope unwritten-record)
         :source (:source unwritten-record)}

        library-record
        {:name name
         :kind (:kind library-record)
         :doc (:doc library-record)
         :arglists (:arglists library-record)
         :file (:file library-record)
         :scope (record-scope library-record)
         :source (:source library-record)}

        (contains? @(:active-source-keys env) name)
        {:name name
         :kind (if (:macro? source-entry) :macro :verb)
         :doc (:doc source-entry)
         :arglists (:arglists source-entry)
         :file nil
         :scope nil
         :source (:source source-entry)}

        :else
        (bad-request! (str "no eval definition named " name) {:name name})))))

(defn- describe
  ([env name] (describe-entry env name))
  ([env name key]
   (let [entry (describe-entry env name)]
     (if (nil? key) entry (get entry key)))))

(defn- take-failures!
  "Failures are reported once: return the current list and clear the
   environment's entries. Service-level failures stay."
  [service env]
  (let [failures (into (vec @(:service-failures service))
                       (when env @(:failures env)))]
    (when env (reset! (:failures env) []))
    failures))

;; ─── Environment build ────────────────────────────────────────────────────

(defn- record-failure! [failures failure]
  (swap! failures conj failure))

(defn- eval-content!
  "Parse and evaluate CONTENT into ENV without indexing it. The original ns
   form runs first (preserving its requires), then the standard aliases, then
   the rest of the file. Returns `{:parsed …}` or `{:error …}`; a failed
   evaluation leaves the caller to decide what to report."
  [env content]
  (try
    (let [parsed (defs/parse content "user")
          {:keys [ns-form body]} (defs/split-ns content)
          source (str (or ns-form (str "(ns " (:ns parsed) ")"))
                      "\n" script/alias-prelude "\n" body)]
      (rearm-build! env)
      (let [result (script/eval-source! (:sci-ctx env) source (atom nil))]
        (if (:error result)
          {:error (:error result)}
          {:parsed parsed})))
    (catch Throwable t
      {:error (ex-message t)})))

(defn- put-record!
  "Replace NAME's REMOVE-SCOPE record with RECORD, keeping `[global cwd]`
   order. One record per name and scope; the last load or write wins."
  [env name remove-scope record]
  (swap! (:library env) update name
         (fn [stack]
           (conj (vec (remove #(= remove-scope (record-scope %)) stack))
                 record))))

(defn- add-record!
  "Index RECORD as the newest layer for its name and scope."
  [env record]
  (put-record! env (:name record) (record-scope record) record))

(defn- load-library-file!
  "Read, parse and evaluate one library file into ENV at SCOPE. A read, parse
   or eval failure is recorded and skipped."
  [env scope {:keys [file content error]}]
  (if error
    (record-failure! (:failures env) {:file file :error error :scope scope})
    (let [{:keys [parsed error]} (eval-content! env content)]
      (if error
        (record-failure! (:failures env) {:file file :error error :scope scope})
        (doseq [record (:defs parsed)]
          (add-record! env (assoc record :file file :scope scope
                                  :root (root-for env scope))))))))

(defn- read-library!
  "Read and evaluate every definition file in ROOT into ENV at SCOPE, sorted
   by path."
  [env root scope]
  (doseq [file (eval-files/scan root)]
    (load-library-file! env scope file)))

(defn- active-scope! [env name requested label]
  (let [record (active-record env name)
        scope (some-> record record-scope)]
    (when-not record
      (bad-request! (str "no loaded eval library definition named " name)
                    {:name name}))
    (when (and requested (not= requested scope))
      (bad-request! (str name " is active in the " (scope-label scope)
                         " scope; cannot " label " the " (scope-label requested)
                         " scope")
                    {:name name :scope requested :active-scope scope}))
    record))

(defn- definition-target!
  "Resolve RAW-NAME to the active loaded definition, its file, and its record
   for LABEL (\"patch\" or \"edit\"), or throw the user-facing refusal. A
   source key has no file; an unwritten live definition has no authoritative
   file. A non-active SCOPE is refused."
  [env raw-name label scope]
  (let [raw-name (->name raw-name)
        name (when raw-name (qualify-name (env-ns env) raw-name))]
    (when-not name
      (bad-request! (str label " needs a definition name, got " (pr-str raw-name))
                    {:name raw-name}))
    (when (contains? @(:source-keys env) name)
      (bad-request! (str "cannot " label " source-provided " name
                         "; it has no library file")
                    {:name name :source true}))
    (when (contains? @(:unwritten env) name)
      (bad-request! (str "cannot " label " " name
                         ": its live definition is not written; re-evaluate it first")
                    {:name name :unwritten true}))
    (let [record (active-scope! env name scope label)
          root (root-for env (record-scope record))
          path (or (:file record) (eval-files/path-for-name root name))]
      (when-not (fs/exists? path)
        (bad-request! (str "the library file for " name " is missing: " path)
                      {:name name :file path}))
      {:name name :record record :path path})))

(defn- patched-record!
  [name record parsed label]
  (let [patched (some #(when (= name (:name %)) %) (:defs parsed))]
    (when-not patched
      (bad-request! (str label " changed the definition name of " name)
                    {:name name}))
    (when-not (= (:ns record) (:ns patched))
      (bad-request! (str label " changed the namespace of " name)
                    {:name name}))
    (assoc patched
           :scope (record-scope record)
           :root (:root record))))

(defn- eval-definition!
  [env record]
  (rearm-build! env)
  (script/eval-source!
   (:sci-ctx env) (:source record)
   (atom (or (sci/find-ns (:sci-ctx env) (symbol (:ns record)))
             (sci/create-ns (symbol (:ns record)))))))

(defn- commit-definition!
  "Re-evaluate PATCHED-RECORD, write PATCHED to PATH, and update the library.
   A failed evaluation or write restores RECORD in the live environment and
   leaves the old file authoritative; LABEL names the operation in errors.
   VERB is the report line's past-tense verb."
  [env cwd {:keys [name record path patched patched-record label verb]}]
  (let [{:keys [error]} (eval-definition! env patched-record)]
    (if error
      (do
        ;; best effort: put the old source back in the live environment; the
        ;; file was never touched
        (eval-definition! env record)
        (bad-request! (str label " evaluation failed: " error)
                      {:name name :error error}))
      (do
        (try
          (eval-files/write-content! (:root patched-record) path patched)
          (catch Throwable t
            ;; the old file is still authoritative; keep the live definition
            ;; on it rather than on a file that did not land
            (eval-definition! env record)
            (bad-request! (str label " write failed: " (ex-message t))
                          {:name name :error (ex-message t)})))
        (add-record! env (assoc patched-record :file path))
        (swap! (:change-lines env) conj
               (str "[eval: " verb " " name " (" (scope-label (record-scope patched-record))
                    ") → " (eval-files/display-path cwd path) "]"))
        nil))))

(defn- patch-definition!
  "Replace the first subform structurally equal to OLD with NEW inside NAME's
   own definition form, re-evaluate the definition, then write the whole file.
   The definition name and namespace must stay. A source-provided key or an
   unwritten live definition has no authoritative file and is refused; a
   failed re-evaluation restores the old live definition and writes nothing."
  [env cwd raw-name old new scope]
  (let [{:keys [name record path]} (definition-target! env raw-name "patch" scope)
        content (slurp path)
        patched (try (patch/patched-content content record old new)
                     (catch Throwable t
                       (bad-request! (ex-message t) {:name name})))
        parsed (try (defs/parse patched "user")
                    (catch Throwable t
                      (bad-request! (str "patched " name " does not parse: "
                                         (ex-message t))
                                    {:name name :file path})))
        patched-record (patched-record! name record parsed "patch")]
    (commit-definition! env cwd {:name name
                                 :record record
                                 :path path
                                 :patched patched
                                 :patched-record patched-record
                                 :label "patch"
                                 :verb "patched"})))

(defn- edit-form-definition!
  "Replace NAME's whole library definition with NEW-SOURCE, re-evaluate it,
   then write the file. The definition name and namespace must stay; nothing
   is written when the replacement does not parse, changes the name, or fails
   to evaluate."
  [env cwd raw-name new-source scope]
  (let [new-source (if (string? new-source) new-source (pr-str new-source))
        {:keys [name record path]} (definition-target! env raw-name "edit-form" scope)
        content (slurp path)
        patched-result (try (clj-edit/edit-definition-source content path record new-source)
                            (catch Throwable t
                              (bad-request! (ex-message t) {:name name})))
        _ (when (:is-error patched-result)
            (bad-request! (:content patched-result) {:name name :file path}))
        patched (:content patched-result)
        parsed (try (defs/parse patched "user")
                    (catch Throwable t
                      (bad-request! (str "edited " name " does not parse: "
                                         (ex-message t))
                                    {:name name :file path})))
        patched-record (patched-record! name record parsed "edit-form")]
    (commit-definition! env cwd {:name name
                                 :record record
                                 :path path
                                 :patched patched
                                 :patched-record patched-record
                                 :label "edit-form"
                                 :verb "edited"})))

(defn- source-siblings
  "Other names named by RECORD's own source form (a multi-name `declare`)."
  [record]
  (->> (:defs (defs/parse (:source record) (:ns record)))
       (map :name)
       (remove #{(:name record)})
       distinct))

(defn- sibling-names
  "Other names sharing RECORD's file."
  [record others]
  (->> others
       (keep (fn [other]
               (when (and (= (:file other) (:file record))
                          (not= (:name other) (:name record)))
                 (:name other))))
       distinct))

(defn- file-siblings
  "Other definitions sharing RECORD's library file or source form — a file or
   a multi-name form must not be moved for one address."
  [env record]
  (distinct
   (concat (source-siblings record)
           (sibling-names record (mapcat identity (vals @(:library env)))))))

(defn- move-record-line!
  "Move RAW-NAME's active definition to TARGET scope without re-evaluating.
   The live name stays; only the file and the library record move. Refuses a
   source key, an unwritten definition, a file that also defines another
   address, a same-scope move, and a target layer that already has the name.
   Returns the report line."
  [env raw-name target]
  (let [raw-name (->name raw-name)
        name (when raw-name (qualify-name (env-ns env) raw-name))
        target (normalize-scope target)]
    (when-not name
      (bad-request! (str "move needs a definition name, got " (pr-str raw-name))
                    {:name raw-name}))
    (when-not target
      (bad-request! "move needs a target scope: cwd or global" {:name name}))
    (when (contains? @(:source-keys env) name)
      (bad-request! (str "cannot move source-provided " name
                         "; it has no library file")
                    {:name name :source true}))
    (when (contains? @(:unwritten env) name)
      (bad-request! (str "cannot move " name
                         ": its live definition is not written; re-evaluate it first")
                    {:name name :unwritten true}))
    (let [record (active-record env name)]
      (when-not record
        (bad-request! (str "no loaded eval library definition named " name)
                      {:name name}))
      (ensure-no-siblings! name (file-siblings env record))
      (let [current (record-scope record)]
        (when (= current target)
          (bad-request! (str name " is already in the " (scope-label target) " scope")
                        {:name name :scope target}))
        (when (some #(= target (record-scope %)) (stack-for env name))
          (bad-request! (str "the " (scope-label target) " scope already has " name)
                        {:name name :scope target}))
        (let [path (eval-files/move-definition! (:roots env) record target)]
          (put-record! env name current
                       (assoc record :file path :scope target
                              :root (root-for env target)))
          (str "[eval: moved " name " (" (scope-label current) " → " (scope-label target) ")]"))))))

(defn- prime-state
  "A prepared state for evaluating library definitions at rebuild: the current
   tool surface is installed so a definition that names a tool var compiles,
   with an inert dispatcher and a discarded emit channel until a real call
   binds its state."
  [service cwd source-namespaces]
  (let [handle (srce/get (:agent-ctx service) :tools)]
    (script/prepare {:tool "eval"
                     :cwd cwd
                     :source-namespaces source-namespaces
                     :get-all-tools (if handle
                                      #(registry/get-all-tools handle)
                                      (constantly []))
                     :get-contributed-tools (if handle
                                              #(registry/get-contributed-tools handle)
                                              (constantly {}))
                     ;; a rebuild compiles definitions, not calls them: keep
                     ;; every registry tool in the surface so a definition
                     ;; naming a currently-disabled tool still loads
                     :select-tools (fn [all opts]
                                     (registry/select-tools all (assoc opts :enabled nil)))
                     :execute-tool (fn [& _]
                                     {:content "tools are not callable during rebuild"
                                      :is-error true})})))

(defn- request-forget!
  "Record `(eval/forget 'NAME)` for the caller to apply after evaluation. An
   unqualified name resolves in the namespace the call ends in; the lookup
   itself happens in `apply-changes!`, after the call has run."
  [env name]
  (let [raw (->name name)
        scope @(:call-scope env)]
    (when-not raw
      (bad-request! (str "forget needs a definition name, got " (pr-str name))
                    {:name name}))
    (when (and (str/includes? raw "/")
               (not (contains? @(:parsed-names env) raw))
               (not (target-known? env {:name raw :scope scope})))
      (missing-definition! raw scope))
    (swap! (:forgotten env) conj {:name raw :scope scope})
    raw))

(defn- build-environment
  "Build a fresh environment: the SCI context, the library index, and every
   library definition evaluated into it."
  [service cwd]
  (let [roots (roots-for service cwd)
        failures (atom [])
        library (atom {})
        env {:roots roots
             :cwd cwd
             :scope-key (session-store/encode-cwd cwd)
             :failures failures
             :library library
             :source-keys (atom #{})
             :active-source-keys (atom #{})
             :model-shadows (atom #{})
             :source-values (atom {})
             :source-info (atom [])
             :source-errors (atom [])
             :source-ids (atom [])
             :fresh-sources? (atom true)
             :change-lines (atom [])
             :parsed-names (atom #{})
             :unwritten (atom #{})
             :unwritten-records (atom {})
             :forgotten (atom #{})
             :call-scope (atom nil)
             :sticky-ns (atom nil)
             :lock (Object.)}
        interrupt (script/interrupt-holder)
        env-ref (atom nil)
        library-fn (fn [] (library-entries @env-ref))
        forget-fn (fn [name] (request-forget! @env-ref name))
        sources-fn (fn [] @(:source-info @env-ref))
        help-fn (fn [] (environment-help @env-ref))
        sci-ctx (script/context
                  (assoc (script/base-namespaces)
                         'eval {'library library-fn
                                'forget forget-fn
                                'patch (fn [name old new]
                                         (patch-definition! @env-ref cwd name old new
                                                            @(:call-scope @env-ref)))
                                'edit-form (fn [name new-source]
                                             (edit-form-definition! @env-ref cwd
                                                                    name new-source
                                                                    @(:call-scope @env-ref)))
                                'move (fn [name scope]
                                        (let [line (move-record-line! @env-ref name scope)]
                                          (swap! (:change-lines @env-ref) conj line)
                                          nil))
                                'describe (fn
                                            ([name] (describe @env-ref name))
                                            ([name key] (describe @env-ref name key)))
                                'sources sources-fn
                                'help help-fn})
                  (script/interrupt-fn interrupt "eval")
                  "eval")
        env (assoc env :sci-ctx sci-ctx :interrupt interrupt)
        _ (reset! env-ref env)
        sources (refresh-sources! service env cwd nil)]
    (binding [tool-util/*cwd* cwd
              bash-tool/*shell* bash-tool/*shell*
              bash-tool/*session-env-fn* bash-tool/*session-env-fn*
              read-tool/*skill-resolver* read-tool/*skill-resolver*]
      (script/with-prime-context
        sci-ctx (prime-state service cwd (:namespaces sources))
        (fn []
          (read-library! env (:global roots) :global)
          (read-library! env (:cwd roots) :cwd)
          (swap! (:model-shadows env)
                 into
                 (filter #(contains? @(:source-keys env) %)
                         (keys @(:library env)))))))
    env))

(defn- ensure-environment!
  "The current environment for SESSION, built from the library on first use.
   An existing environment is reused: a session is a runtime cache, not a
   branch-scoped definition log."
  [service session cwd]
  (let [id (:id session)]
    (or (get @(:envs service) id)
        (let [env (build-environment service cwd)]
          (when @(:disposed? service)
            (throw (ex-info "the agent's eval service is disposed"
                            {:type :eval/disposed})))
          (get (swap! (:envs service)
                      #(if (contains? % id) % (assoc % id env)))
               id)))))

;; ─── Request normalization ────────────────────────────────────────────────

(defn- normalize-drop [drop]
  (mapv (fn [entry]
          (or (->name entry)
              (bad-request! (str "drop entries are definition names, got "
                                 (pr-str entry))
                            {:drop entry})))
        (or drop [])))

;; ─── Validation ───────────────────────────────────────────────────────────

(defn- validate-drop!
  "Validate drop TARGETS (`{:name :scope}`) against KNOWN?: no duplicates,
   every name present in the requested layer or, when SCOPE is nil, in either
   layer."
  [known? targets]
  (when-not (= (count targets) (count (distinct targets)))
    (bad-request! "drop names a definition twice" {:drop targets}))
  (doseq [{:keys [name scope] :as target} targets]
    (when-not (known? target)
      (missing-definition! name scope {:drop true}))))

(defn- validate-requests!
  "Validate a drop before anything evaluates: duplicate entries, unknown
   names, and a name the call itself defines. Forget requests are validated
   when `(eval/forget …)` runs and again when changes apply."
  [env records drop scope]
  (validate-drop! (partial target-known? env)
                  (map #(hash-map :name % :scope scope) drop))
  (let [parsed-names (set (map :name records))]
    (doseq [name drop]
      (when (contains? parsed-names name)
        (bad-request! (str "a definition cannot be defined and dropped in one call: "
                           name)
                      {:name name})))))

;; ─── Applying a call's changes ────────────────────────────────────────────

(defn- address-parts
  "ADDRESS → `[namespace var-symbol dispatch]`; DISPATCH is the printed
   defmethod dispatch, nil for an ordinary definition."
  [address]
  (let [i (str/index-of address "/")
        ns (if i (subs address 0 i) "user")
        part (if i (subs address (inc i)) address)
        [sym dispatch] (str/split part #" " 2)]
    [ns sym dispatch]))

(defn- unbind!
  "Remove NAME's live var from ENV's SCI context. A defmethod removes only
   its method; an ordinary definition unmaps the var. Returns an error string
   or nil."
  [env name record]
  (let [[ns sym dispatch] (address-parts (or (:name record) name))
        form (if dispatch
               (str "(clojure.core/remove-method " sym " " dispatch ")")
               (str "(clojure.core/ns-unmap *ns* '" sym ")"))]
    (try
      (:error (script/eval-source! (:sci-ctx env) form (atom (symbol ns))))
      (catch Throwable t (ex-message t)))))

(defn- interned?
  "Is RECORD live? An ordinary definition must be in its namespace's own map
   (not merely inherited from clojure.core); a defmethod must be present in
   its multimethod."
  [env record]
  (let [[ns sym dispatch] (address-parts (:name record))
        form (if dispatch
               (str "(contains? (clojure.core/methods " sym ") " dispatch ")")
               (str "(contains? (clojure.core/ns-interns *ns*) '" sym ")"))]
    (try
      (true? (:val (sci/eval-string+ (:sci-ctx env) form {:ns (symbol ns)})))
      (catch Throwable _ false))))

(defn- mark-transient!
  "After a failed call, remember parsed definitions that are live but not
   written, so drop/forget can unbind them. A source key's live redefinition
   is a model shadow instead: it is non-storable and a drop restores the
   source value."
  [env records]
  (doseq [record records
          :when (not (seq (stack-for env (:name record))))
          :when (interned? env record)]
    (if (contains? @(:source-keys env) (:name record))
      (swap! (:model-shadows env) conj (:name record))
      (do
        (swap! (:unwritten env) conj (:name record))
        (swap! (:unwritten-records env) assoc (:name record) record)))))

(defn- restore-source!
  "Seed NAME's active source value, if it has one, and return the seed error."
  [env name]
  (when (and (contains? @(:active-source-keys env) name)
             (get @(:source-values env) name))
    (seed-source-value! env name (get @(:source-values env) name))))

(defn- remove-record!
  "Remove RECORD's scope from NAME's stack, dissociating the name when the
   stack is empty."
  [env name record]
  (let [scope (record-scope record)]
    (swap! (:library env)
           (fn [library]
             (let [stack (vec (remove #(= scope (record-scope %))
                                      (get library name)))]
               (if (seq stack)
                 (assoc library name stack)
                 (dissoc library name)))))))

(defn- clear-unwritten!
  "Forget NAME's unwritten live-definition record."
  [env name]
  (swap! (:unwritten env) disj name)
  (swap! (:unwritten-records env) dissoc name))

(defn- drop-record!
  "Drop RECORD from its layer: delete the file, unbind the live var when it
   is the active record, and re-evaluate the next layer after an active drop."
  [env name record]
  (let [active? (= record (active-record env name))
        scope (record-scope record)
        file (eval-files/delete-name! (root-for env scope) name)
        unbind-error (when active? (unbind! env name record))
        _ (remove-record! env name record)
        next (when active? (active-record env name))
        restore-error (when (and active? next)
                        (some-> (eval-definition! env next) :error))]
    (when active?
      (clear-unwritten! env name))
    {:name name
     :file file
     :scope scope
     :active active?
     :error (cond
              restore-error (str "could not restore the next layer: " restore-error)
              unbind-error unbind-error)}))

(defn- drop-unwritten!
  "Unbind and forget NAME's unwritten live definition. Returns the unbind
   error or nil."
  [env name]
  (let [live? (interned? env {:name name})
        error (when live? (unbind! env name nil))]
    (clear-unwritten! env name)
    error))

(defn- roots-file-scope
  "The layer holding NAME's file under ROOTS: SCOPE when given, else cwd first
   and then global. Nil when neither root has the file."
  [roots name scope]
  (or scope
      (when (file-present? roots :cwd name) :cwd)
      (when (file-present? roots :global name) :global)))

(defn- drop-one!
  "Delete NAME's library file and unbind its live var. A source-provided key
   has no file: its live value (or the model's shadow of it) is unbound, and
   the source value is seeded again while that source is active. SCOPE
   selects a layer; nil means the active layer. A file without a loaded record
   (a broken or not-yet-loaded file) can still be dropped, and an unwritten
   live definition with no layer is dropped as before."
  [env name scope]
  (let [source? (contains? @(:source-keys env) name)
        unwritten? (contains? @(:unwritten env) name)
        record (if scope
                 (some #(when (= scope (record-scope %)) %) (stack-for env name))
                 (active-record env name))]
    (cond
      source?
      (let [shadow? (contains? @(:model-shadows env) name)
            live? (interned? env {:name name})
            unbind-error (when (and live? shadow?)
                           (unbind! env name nil))
            restore-error (restore-source! env name)]
        (swap! (:library env) dissoc name)
        (clear-unwritten! env name)
        (swap! (:model-shadows env) disj name)
        {:name name :scope :source :error (or unbind-error restore-error)})

      (nil? record)
      (let [target-scope (roots-file-scope (:roots env) name scope)
            file? (and target-scope (file-present? (:roots env) target-scope name))
            drop-unwritten? (and unwritten?
                                 (or (nil? scope)
                                     (empty? (stack-for env name))))]
        (when-not (or file? drop-unwritten?)
          (missing-definition! name scope))
        (let [file (when file?
                     (eval-files/delete-name! (root-for env target-scope) name))
              error (when drop-unwritten? (drop-unwritten! env name))]
          {:name name :file file :scope (or (when file? target-scope) :unwritten)
           :error error}))

      :else (drop-record! env name record))))

(defn- apply-definition!
  "Write RECORD to its scope's library and index it. A source-provided key
   stays live but is never written, and is remembered as a model shadow so
   later source reads do not overwrite it. A failed write is recorded and
   reported; the live definition remains."
  [env cwd record]
  (let [name (:name record)
        scope (or (:scope record) :cwd)
        record (assoc record :scope scope)]
    (if (contains? @(:source-keys env) name)
      (do
        (swap! (:model-shadows env) conj name)
        {:line (str "[eval: redefined " name " (source; not stored)]")})
      (try
        (let [root (root-for env scope)
              path (eval-files/write-definition! root record)
              record (assoc record :file path :root root)]
          (add-record! env record)
          (clear-unwritten! env name)
          {:line (str "[eval: defined " name " (" (scope-label scope) ") → "
                      (eval-files/display-path cwd path)
                      (when-not (:doc record) " (no docstring)") "]")
           :entry (assoc (library-entry record) :active true)})
        (catch Throwable t
          (swap! (:unwritten env) conj name)
          (swap! (:unwritten-records env) assoc name record)
          (record-failure! (:failures env) {:name name :error (ex-message t)
                                            :scope scope})
          {:line (str "[eval: define failed " name " (" (scope-label scope) "): "
                      (ex-message t) "]")
           :error (ex-message t)})))))

(defn- apply-changes!
  "Apply the successful call's changes: drop/forget first, then write every
   definition the call made. Returns the report the tool result carries; a
   failure keeps the partial report."
  [env cwd records drop forgotten scope]
  (let [forgotten (mapv (fn [{:keys [name scope]}]
                          {:name (qualify-name (env-ns env) name)
                           :scope scope})
                        forgotten)
        drop (distinct (concat (map #(hash-map :name % :scope scope) drop)
                               forgotten))
        parsed-names (set (map :name records))
        lines (atom (let [changes @(:change-lines env)]
                      (reset! (:change-lines env) [])
                      changes))
        defined (atom [])
        forgotten-names (atom [])
        write-errors (atom 0)
        unbind-errors (atom 0)]
    (try
      (doseq [{:keys [name]} drop]
        (when (contains? parsed-names name)
          (bad-request! (str "a definition cannot be defined and dropped in one call: "
                             name)
                        {:name name})))
      (validate-drop! (partial target-known? env) drop)
      (doseq [{:keys [name scope]} drop]
        (let [{:keys [error] actual-scope :scope} (drop-one! env name scope)]
          (swap! lines conj
                 (if error
                   (str "[eval: forgot " name " (" (scope-label actual-scope)
                        ") (unbind failed: " error ")]")
                   (str "[eval: forgot " name " (" (scope-label actual-scope)
                        ")]")))
          (swap! forgotten-names conj name)
          (when error
            (swap! unbind-errors inc)
            (record-failure! (:failures env) {:name name :error error
                                              :scope actual-scope}))))
      (doseq [record records]
        (let [{:keys [line entry error]} (apply-definition! env cwd record)]
          (swap! lines conj line)
          (when entry (swap! defined conj entry))
          (when error (swap! write-errors inc))))
      {:lines @lines
       :defined @defined
       :forgotten @forgotten-names
       :write-errors @write-errors
       :unbind-errors @unbind-errors}
      (catch Throwable t
        {:lines (conj @lines (str "[eval: failed: " (ex-message t) "]"))
         :defined @defined
         :forgotten @forgotten-names
         :write-errors @write-errors
         :unbind-errors @unbind-errors
         :error (ex-message t)}))))

;; ─── The call ─────────────────────────────────────────────────────────────

(defn- current-ns [env]
  (some-> @(:sticky-ns env) sci/ns-name str))

(defn- try-parse-code [code env]
  (try
    {:parsed (defs/parse code (or (current-ns env) "user"))}
    (catch Throwable t
      {:error t})))

(defn- base-result [base started]
  (let [base (or base {:content "" :details {}})
        details (merge {:timeout nil
                        :sources []}
                       (:details base)
                       {:elapsed-ms (- (concurrent/monotonic-ms) started)})]
    (assoc base :details details)))

(defn- result-parts
  "Carry the script's truncation and image payloads onto a rewritten result."
  [base result]
  (cond-> result
    (:truncation base) (assoc :truncation (:truncation base))
    (:images base) (assoc :images (:images base))))

(defn- error-result
  "A failed call reports its error, never applies definitions or drops, and
   reports the build failures collected for this attempt."
  [service env base started]
  (let [base (base-result base started)
        failures (take-failures! service env)]
    (result-parts
     base
     (assoc base
            :is-error true
            :details (cond-> (merge (:details base)
                                    {:defined [] :forgotten []})
                       (seq failures) (assoc :failures failures))))))

(defn- scoped-records
  "Collapse PARSED's definitions and resolve each one's write scope from the
   active layer and the call's requested SCOPE."
  [env scope parsed]
  (mapv (fn [record]
          (assoc record :scope (target-scope env (:name record) scope)))
        (defs/collapse (:defs parsed))))

(defn- finish-call!
  "Merge the script result with the definition report: content lines after
   the emitted output, and the defined/forgotten vocabulary in :details."
  [service env cwd records drop forgotten scope base started]
  (let [base (base-result base started)
        changes (apply-changes! env cwd records drop forgotten scope)
        _ (when (:error changes) (mark-transient! env records))
        lines (:lines changes)
        content (str/join "\n" (remove str/blank?
                                       [(:content base) (str/join "\n" lines)]))
        failures (take-failures! service env)
        write-errors (pos? (:write-errors changes 0))
        unbind-errors (pos? (:unbind-errors changes 0))
        failed? (or (:error changes) write-errors unbind-errors)]
    (result-parts
     base
     (assoc base
            :content (if (str/blank? content) "(no output)" content)
            :is-error (boolean failed?)
            :details (cond-> (merge (:details base)
                                    (select-keys changes [:defined :forgotten]))
                       (seq failures) (assoc :failures failures)
                       failed? (assoc :error
                                      (cond
                                        (:error changes) :eval/change-failed
                                        write-errors :eval/write-failed
                                        :else :eval/unbind-failed)))))))

(defn- eval!*
  [service env cwd {:keys [code timeout limits signal on-update ctx drop scope]}]
  (let [code (some-> code str)
        scope (normalize-scope scope)
        lock (:lock env)]
    (locking lock
      (when @(:disposed? service)
        (throw (ex-info "the agent's eval service is disposed"
                        {:type :eval/disposed})))
      (let [started (concurrent/monotonic-ms)
            drop (mapv #(qualify-name (env-ns env) %) (normalize-drop drop))
            {:keys [parsed error]} (try-parse-code code env)
            records (when parsed (scoped-records env scope parsed))
            run-code (fn []
                       (let [handle (or (srce/get (:agent-ctx service) :tools)
                                        (throw (ex-info "the eval service needs the agent's :tools service"
                                                        {:type :eval/no-tools})))
                             sources (if (and (compare-and-set! (:fresh-sources? env) true false)
                                              (= @(:source-ids env)
                                                 (mapv :id (source-registrations service))))
                                       (current-source-seed env)
                                       (refresh-sources! service env cwd signal))]
                         (script/run {:tool "eval"
                                      :code code
                                      :timeout timeout
                                      :limits limits
                                      :signal signal
                                      :ctx ctx
                                      :cwd cwd
                                      :on-update on-update
                                      :sci-ctx (:sci-ctx env)
                                      :interrupt (:interrupt env)
                                      :ns-atom (:sticky-ns env)
                                      :source-namespaces (:namespaces sources)
                                      :extra-details (cond-> {:sources (:info sources)}
                                                       (seq (:errors sources))
                                                       (assoc :source-errors (:errors sources)))
                                      :get-all-tools #(registry/get-all-tools handle)
                                      :get-contributed-tools #(registry/get-contributed-tools handle)
                                      :select-tools registry/select-tools
                                      :execute-tool #(registry/execute-tool handle %1 %2 %3)})))]
        (if error
          (error-result service env (run-code) started)
          (do
            (validate-requests! env records drop scope)
            (if (str/blank? code)
              (bad-request! "eval needs code or drop" {})
              (do
                (reset! (:forgotten env) #{})
                (reset! (:change-lines env) [])
                (reset! (:parsed-names env) (set (map :name records)))
                (reset! (:call-scope env) scope)
                (try
                  (let [result (run-code)]
                    (if (:is-error result)
                      (do
                        (when-not (contains? #{:timeout :aborted :incomplete}
                                             (get-in result [:details :error]))
                          (mark-transient! env records))
                        (error-result service env result started))
                      (finish-call! service env cwd records drop
                                    @(:forgotten env) scope result started)))
                  (finally
                    (reset! (:call-scope env) nil)))))))))))

(defn- drop-one-request!
  "Drop one `{:name :scope}` target in ENV (or just its file when no
   environment exists) and return `{:name :scope :error}` — an error is
   reported without hiding the other names."
  [env roots {:keys [name scope]}]
  (try
    (if env
      (let [{:keys [error] actual-scope :scope} (drop-one! env name scope)]
        {:name name :scope actual-scope :error error})
      (let [scope (or (roots-file-scope roots name scope) :global)
            root (get roots scope)
            file (eval-files/delete-name! root name)]
        {:name name :scope (when file scope) :error nil}))
    (catch Throwable t
      {:name name :scope scope :error (ex-message t)})))

(defn- drop-result
  [started results]
  (let [forgotten (mapv :name results)
        failures (vec (keep #(when-let [error (:error %)]
                               {:name (:name %) :error error
                                :scope (:scope %)})
                            results))]
    {:content (str/join "\n"
                        (concat (map #(str "[eval: forgot " (:name %)
                                           " (" (scope-label (:scope %)) ")]")
                                     results)
                                (map #(str "[eval: failed " (:name %) ": "
                                           (:error %) "]")
                                     failures)))
     :details (cond-> {:forgotten forgotten
                       :elapsed-ms (- (concurrent/monotonic-ms) started)}
                (seq failures) (assoc :failures failures))
     :is-error (boolean (seq failures))}))

(defn- session-lock
  "The lock for one session's environment selection and evaluation. The map
   keeps one lock per session id until disposal; two threads racing to create
   it agree on the map's final value."
  [locks id]
  (or (get @locks id)
      (let [lock (Object.)]
        (get (swap! locks #(if (contains? % id) % (assoc % id lock))) id))))

(defn- drop-request!
  "Drop NAMES for SESSION without building an environment: delete the files
   and, when an environment exists, refresh its source layer and unbind the
   live names. SCOPE selects a layer; nil means the active layer (cwd first).
   Returns the tool/command result map, or throws (the caller reports)."
  ([service session names] (drop-request! service session names nil nil))
  ([service session names cwd] (drop-request! service session names cwd nil))
  ([service session names cwd scope]
   (when @(:disposed? service)
     (throw (ex-info "the agent's eval service is disposed"
                     {:type :eval/disposed})))
   (let [session (or session (extensions/get-session))
         _ (when-not session
             (throw (ex-info "eval needs a session" {:type :eval/no-session})))
         started (concurrent/monotonic-ms)
         lock (session-lock (:session-locks service) (:id session))
         scope (normalize-scope scope)]
     (locking lock
       (let [env (get @(:envs service) (:id session))
             _ (when env
                 (refresh-sources! service env
                                   (or cwd (tool-util/cwd)) nil))
             roots (if env (:roots env) (roots-for service (or cwd (tool-util/cwd))))
             names (mapv #(qualify-name (or (some-> env env-ns) "user") %)
                         (normalize-drop names))
             targets (mapv #(hash-map :name % :scope scope) names)]
         (validate-drop! (partial drop-target-known? env roots) targets)
         (drop-result started
                      (mapv #(drop-one-request! env roots %) targets)))))))

(defn- current-environment [service]
  (when-let [session (extensions/get-session)]
    (get @(:envs service) (:id session))))

(defn- service-library
  "The definitions the current environment loaded."
  [service]
  (if-let [env (current-environment service)]
    (library-entries env)
    []))

(defn- service-failures
  [service]
  (into (vec @(:service-failures service))
        (when-let [env (current-environment service)]
          @(:failures env))))

(defn- service-index
  "The library on disk, read without evaluating anything — what `/eval` lists
   and searches. OPTS may carry `:session` (use its built environment's pinned
   roots) and `:cwd` (the scope for a session without an environment)."
  ([service] (service-index service nil))
  ([service opts]
   (let [env (when-let [session (:session opts)]
               (get @(:envs service) (:id session)))]
     (eval-files/index
      (or (:roots env)
          (roots-for service (or (:cwd opts) (tool-util/cwd))))))))

(defn- move-request*
  [service {:keys [name scope cwd]} session]
  (let [cwd (or cwd (tool-util/cwd))
        env (when session (get @(:envs service) (:id session)))
        raw (->name name)
        name (when raw (qualify-name (or (some-> env env-ns) "user") raw))
        target (normalize-scope scope)]
    (when-not name
      (bad-request! (str "move needs a definition name, got " (pr-str name))
                    {:name name}))
    (when-not target
      (bad-request! "move needs a target scope: cwd or global" {:name name}))
    (if env
      (let [line (move-record-line! env name target)]
        {:content line
         :details {:moved {:name name :scope target}}
         :is-error false})
      (let [roots (roots-for service cwd)
            index (eval-files/index roots)
            entries (filterv #(= name (:name %)) (:entries index))
            source (or (first (filterv #(= :cwd (:scope %)) entries))
                       (first (filterv #(= :global (:scope %)) entries)))]
        (when-not source
          (bad-request! (str "no eval library definition named " name)
                        {:name name}))
        (ensure-no-siblings! name (sibling-names source (:entries index)))
        (let [current (:scope source)]
          (when (= current target)
            (bad-request! (str name " is already in the " (scope-label target)
                               " scope")
                          {:name name :scope target}))
          (when (some #(= target (:scope %)) entries)
            (bad-request! (str "the " (scope-label target) " scope already has " name)
                          {:name name :scope target}))
          (let [path (eval-files/move-definition! roots source target)]
            {:content (str "[eval: moved " name " (" (scope-label current) " → "
                           (scope-label target) ")]")
             :details {:moved {:name name :from current :to target :file path}}
             :is-error false}))))))

(defn- move-request!
  "Move NAME to SCOPE for the `/eval move` command and the service `:move!`
   reader. With a built environment the active record moves and the live var
   stays; without one the disk index supplies the record and no environment
   is built. A session's move takes its eval lock so it cannot race a call or
   a drop."
  [service opts]
  (when @(:disposed? service)
    (throw (ex-info "the agent's eval service is disposed"
                    {:type :eval/disposed})))
  (let [session (or (:session opts) (extensions/get-session))
        lock (when session (session-lock (:session-locks service) (:id session)))]
    (if session
      (locking lock (move-request* service opts session))
      (move-request* service opts nil))))

(defn- service-sources
  "The current source report for the `/eval sources` command and the
   service's `:sources` reader. Invokes each source with CWD and no signal;
   source errors are part of the report."
  [service cwd]
  (:info (resolve-sources service (or cwd (tool-util/cwd)) nil)))

(defn- service-source-commands
  "The source-contributed `/eval` verbs, read per call."
  [service cwd]
  (:commands (resolve-sources service (or cwd (tool-util/cwd)) nil)))

(defn- eval-call! [service opts]
  (when @(:disposed? service)
    (throw (ex-info "the agent's eval service is disposed"
                    {:type :eval/disposed})))
  (let [session (resolve-session (:session opts))
        _ (when-not session
            (throw (ex-info "eval needs a session"
                            {:type :eval/no-session})))
        cwd (or (:cwd opts) (get-in opts [:ctx :cwd]) (tool-util/cwd))
        scope (normalize-scope (:scope opts))
        opts (assoc opts :scope scope)]
    (if (str/blank? (str (:code opts)))
      (if (seq (:drop opts))
        (drop-request! service session (:drop opts) cwd scope)
        (bad-request! "eval needs code or drop" {}))
      (let [lock (session-lock (:session-locks service) (:id session))]
        (locking lock
          (let [env (ensure-environment! service session cwd)]
            (eval!* service env cwd opts)))))))

(defn value
  "The `:eval` service for AGENT-CTX. The environments are keyed by session
   and owned here; the `:tools` handle is resolved from AGENT-CTX on the first
   call, so the script surface is the agent's own table.

   OPTS: `:agent-dir` pins the agent home, hence both library roots (tests, a
   sandboxed composition); it defaults to `cfg/get-agent-dir`."
  ([agent-ctx] (value agent-ctx nil))
  ([agent-ctx {:keys [agent-dir]}]
   (let [envs (atom {})
         failures (atom [])
         session-locks (atom {})
         disposed? (atom false)
         service {:agent-ctx agent-ctx
                  :agent-dir (or agent-dir (cfg/get-agent-dir))
                  :envs envs
                  :service-failures failures
                  :session-locks session-locks
                  :disposed? disposed?}]
     {:eval! #(eval-call! service %)
      :drop! (fn [{:keys [session drop cwd scope]}]
               (drop-request! service session drop cwd scope))
      :move! #(move-request! service %)
      :library #(service-library service)
      :index (fn
               ([] (service-index service))
               ([opts] (service-index service opts)))
      :sources (fn
                 ([] (if @disposed? [] (service-sources service (tool-util/cwd))))
                 ([opts] (if @disposed? [] (service-sources service (:cwd opts)))))
      :source-commands (fn
                         ([] (if @disposed? {} (service-source-commands service (tool-util/cwd))))
                         ([opts] (if @disposed? {} (service-source-commands service (:cwd opts)))))
      :failures #(service-failures service)
      :dispose! (fn []
                  (reset! disposed? true)
                  (reset! envs {})
                  (reset! session-locks {})
                  (reset! failures [])
                  nil)})))
