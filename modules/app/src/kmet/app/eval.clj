(ns kmet.app.eval
  "The `:eval` service: one persistent SCI environment per agent session over
   the agent home's eval library.

   A session's environment is built lazily on its first eval by scanning
   `<agent-dir>/eval/<ns>/<name>.clj`. Every successful top-level definition
   writes its file immediately; a redefinition overwrites it. A new session or
   a restarted process loads the library; an already-built environment is a
   runtime cache and never refreshes. `:drop` and `(eval/forget 'name)` delete
   the file and unbind the live name. Sessions share the library but not the
   environment. A broken file is recorded under `:failures` and skipped, never
   fatal.

   `kmet.app.tools.eval` owns the model-facing tool; `kmet.app.eval.files`
   owns the reviewable files and `kmet.app.eval.definitions` the pure parse
   and address rules."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.config :as cfg]
            [kmet.app.eval.definitions :as defs]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.eval.patch :as patch]
            [kmet.app.eval.sources :as eval-sources]
            [kmet.app.extensions.core :as extensions]
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
  "The one eval library root for SERVICE: `<agent-dir>/eval`."
  [service]
  (str (fs/path (:agent-dir service) "eval")))

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

(defn- known-name?
  "A drop/forget target: loaded from the library, present as a file, a
   source-provided key, or a live definition that was not written (a failed
   write or an errored call)."
  [env name]
  (boolean
   (or (get @(:library env) name)
       (fs/exists? (eval-files/path-for-name (:root env) name))
       (contains? @(:source-keys env) name)
       (contains? @(:unwritten env) name))))

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
                     (select-keys result [:id :doc])))
                 results)
     :errors errors}))

(defn- source-var
  [ns-name name {:keys [value macro?]}]
  (let [ns (sci/create-ns ns-name)]
    (sci/new-var name value
                 (cond-> {:ns ns :name name}
                   macro? (assoc :macro true)))))

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
  (select-keys record [:name :ns :kind :file]))

(defn- library-entries
  "The service's `(eval/library)` value: one entry per loaded definition."
  [env]
  (->> (vals @(:library env))
       (map library-entry)
       (sort-by :name)
       vec))

(defn- source-line [source]
  (if-let [error (:error source)]
    (str "- " (:id source) ": " error)
    (str "- " (:id source) (when-let [doc (:doc source)] (str ": " doc)))))

(defn- environment-help
  "The `(eval/help)` text: sources, the loaded library, and the three lookup
   forms. Source errors are part of the source list, not hidden."
  [env]
  (let [sources @(:source-info env)
        entries (library-entries env)]
    (str "Eval sources:\n"
         (if (seq sources)
           (str/join "\n" (map source-line sources))
           "  (none)")
         "\nLibrary definitions: " (count entries)
         (when (seq entries)
           (str " (" (str/join ", " (map :name entries)) ")"))
         "\nFailures: " (count @(:failures env))
         "\nUse (eval/library), (eval/sources), (eval/forget 'name), "
         "(eval/patch 'name old new), (eval/edit-form 'name new-source).")))

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

(defn- load-library-file!
  "Read, parse and evaluate one library file into ENV. A read, parse or eval
   failure is recorded and skipped."
  [env {:keys [file content error]}]
  (if error
    (record-failure! (:failures env) {:file file :error error})
    (let [{:keys [parsed error]} (eval-content! env content)]
      (if error
        (record-failure! (:failures env) {:file file :error error})
        (doseq [record (:defs parsed)]
          (swap! (:library env) assoc (:name record)
                 (assoc record :file file)))))))

(defn- read-library!
  "Read and evaluate every definition file in ROOT into ENV, sorted by path."
  [env root]
  (doseq [file (eval-files/scan root)]
    (load-library-file! env file)))

(defn- definition-target!
  "Resolve RAW-NAME to the loaded definition, its file, and its record for
   LABEL (\"patch\" or \"edit\"), or throw the user-facing refusal. A source key
   has no file; an unwritten live definition has no authoritative file."
  [env raw-name label]
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
    (let [record (get @(:library env) name)
          path (or (:file record) (eval-files/path-for-name (:root env) name))]
      (when-not record
        (bad-request! (str "no loaded eval library definition named " name)
                      {:name name}))
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
    patched))

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
          (eval-files/write-content! (:root env) path patched)
          (catch Throwable t
            ;; the old file is still authoritative; keep the live definition
            ;; on it rather than on a file that did not land
            (eval-definition! env record)
            (bad-request! (str label " write failed: " (ex-message t))
                          {:name name :error (ex-message t)})))
        (swap! (:library env) assoc name (assoc patched-record :file path))
        (swap! (:change-lines env) conj
               (str "[eval: " verb " " name " → "
                    (eval-files/display-path cwd path) "]"))
        nil))))

(defn- patch-definition!
  "Replace the first subform structurally equal to OLD with NEW inside NAME's
   own definition form, re-evaluate the definition, then write the whole file.
   The definition name and namespace must stay. A source-provided key or an
   unwritten live definition has no authoritative file and is refused; a
   failed re-evaluation restores the old live definition and writes nothing."
  [env cwd raw-name old new]
  (let [{:keys [name record path]} (definition-target! env raw-name "patch")
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
  [env cwd raw-name new-source]
  (when-not (string? new-source)
    (bad-request! (str "edit-form needs the new definition source, got "
                       (pr-str new-source))
                  {:name raw-name :form new-source}))
  (let [{:keys [name record path]} (definition-target! env raw-name "edit-form")
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
  (let [raw (->name name)]
    (when-not raw
      (bad-request! (str "forget needs a definition name, got " (pr-str name))
                    {:name name}))
    (when (and (str/includes? raw "/")
               (not (contains? @(:parsed-names env) raw))
               (not (known-name? env raw)))
      (bad-request! (str "no eval library definition named " raw)
                    {:name raw}))
    (swap! (:forgotten env) conj raw)
    raw))

(defn- build-environment
  "Build a fresh environment: the SCI context, the library index, and every
   library definition evaluated into it."
  [service cwd]
  (let [root (library-root service)
        failures (atom [])
        library (atom {})
        env {:root root
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
             :forgotten (atom #{})
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
                                         (patch-definition! @env-ref cwd name old new))
                                'edit-form (fn [name new-source]
                                             (edit-form-definition! @env-ref cwd
                                                                    name new-source))
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
          (read-library! env root)
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
  "Validate NAMES against KNOWN?: no duplicates, every name known."
  [known? names]
  (when-not (= (count names) (count (distinct names)))
    (bad-request! "drop names a definition twice" {:drop names}))
  (doseq [name names]
    (when-not (known? name)
      (bad-request! (str "no eval library definition named " name)
                    {:name name :drop true}))))

(defn- validate-requests!
  "Validate a drop before anything evaluates: duplicate entries, unknown
   names, and a name the call itself defines. Forget requests are validated
   when `(eval/forget …)` runs and again when changes apply."
  [env parsed drop]
  (validate-drop! #(known-name? env %) drop)
  (let [parsed-names (set (map :name (:defs parsed)))]
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
  [env parsed]
  (doseq [record (defs/collapse (:defs parsed))
          :when (not (get @(:library env) (:name record)))
          :when (interned? env record)]
    (if (contains? @(:source-keys env) (:name record))
      (swap! (:model-shadows env) conj (:name record))
      (swap! (:unwritten env) conj (:name record)))))

(defn- restore-source!
  "Seed NAME's active source value, if it has one, and return the seed error."
  [env name]
  (when (and (contains? @(:active-source-keys env) name)
             (get @(:source-values env) name))
    (seed-source-value! env name (get @(:source-values env) name))))

(defn- drop-one!
  "Delete NAME's library file and unbind its live var. A source-provided key
   has no file: its live value (or the model's shadow of it) is unbound, and
   the source value is seeded again while that source is active."
  [env name]
  (let [record (get @(:library env) name)
        source? (contains? @(:source-keys env) name)
        shadow? (contains? @(:model-shadows env) name)
        live? (interned? env (or record {:name name}))
        file (when-not source?
               (eval-files/delete-name! (:root env) name))
        unbind-error (when (and live? (or (not source?) shadow?))
                       (unbind! env name record))
        restore-error (when source? (restore-source! env name))]
    (swap! (:library env) dissoc name)
    (swap! (:unwritten env) disj name)
    (when source? (swap! (:model-shadows env) disj name))
    {:name name :file file :error (or unbind-error restore-error)}))

(defn- apply-definition!
  "Write RECORD to the library and index it. A source-provided key stays live
   but is never written, and is remembered as a model shadow so later source
   reads do not overwrite it. A failed write is recorded and reported; the
   live definition remains."
  [env cwd record]
  (let [name (:name record)]
    (if (contains? @(:source-keys env) name)
      (do
        (swap! (:model-shadows env) conj name)
        {:line (str "[eval: redefined " name " (source; not stored)]")})
      (try
        (let [path (eval-files/write-definition! (:root env) record)]
          (swap! (:library env) assoc name (assoc record :file path))
          (swap! (:unwritten env) disj name)
          {:line (str "[eval: defined " name " → " (eval-files/display-path cwd path) "]")
           :entry (library-entry (assoc record :file path))})
        (catch Throwable t
          (swap! (:unwritten env) conj name)
          (record-failure! (:failures env) {:name name :error (ex-message t)})
          {:line (str "[eval: define failed " name ": " (ex-message t) "]")
           :error (ex-message t)})))))

(defn- apply-changes!
  "Apply the successful call's changes: drop/forget first, then write every
   definition the call made. Returns the report the tool result carries; a
   failure keeps the partial report."
  [env cwd parsed drop forgotten]
  (let [forgotten (mapv #(qualify-name (env-ns env) %) forgotten)
        drop (distinct (concat drop forgotten))
        parsed-names (set (map :name (:defs parsed)))
        lines (atom (let [changes @(:change-lines env)]
                      (reset! (:change-lines env) [])
                      changes))
        defined (atom [])
        forgotten-names (atom [])
        write-errors (atom 0)
        unbind-errors (atom 0)]
    (try
      (doseq [name drop]
        (when (contains? parsed-names name)
          (bad-request! (str "a definition cannot be defined and dropped in one call: "
                             name)
                        {:name name})))
      (validate-drop! #(known-name? env %) drop)
      (doseq [name drop]
        (let [{:keys [error]} (drop-one! env name)]
          (swap! lines conj (if error
                              (str "[eval: forgot " name
                                   " (unbind failed: " error ")]")
                              (str "[eval: forgot " name "]")))
          (swap! forgotten-names conj name)
          (when error
            (swap! unbind-errors inc)
            (record-failure! (:failures env) {:name name :error error}))))
      (doseq [record (defs/collapse (:defs parsed))]
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

(defn- finish-call!
  "Merge the script result with the definition report: content lines after
   the emitted output, and the defined/forgotten vocabulary in :details."
  [service env cwd parsed drop forgotten base started]
  (let [base (base-result base started)
        changes (apply-changes! env cwd parsed drop forgotten)
        _ (when (:error changes) (mark-transient! env parsed))
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
  [service env cwd {:keys [code timeout limits signal on-update ctx drop]}]
  (let [code (some-> code str)
        lock (:lock env)]
    (locking lock
      (when @(:disposed? service)
        (throw (ex-info "the agent's eval service is disposed"
                        {:type :eval/disposed})))
      (let [started (concurrent/monotonic-ms)
            drop (mapv #(qualify-name (env-ns env) %) (normalize-drop drop))
            {:keys [parsed error]} (try-parse-code code env)
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
            (validate-requests! env parsed drop)
            (if (str/blank? code)
              (bad-request! "eval needs code or drop" {})
              (do
                (reset! (:forgotten env) #{})
                (reset! (:change-lines env) [])
                (reset! (:parsed-names env) (set (map :name (:defs parsed))))
                (let [result (run-code)]
                  (if (:is-error result)
                    (do
                      (when-not (contains? #{:timeout :aborted :incomplete}
                                           (get-in result [:details :error]))
                        (mark-transient! env parsed))
                      (error-result service env result started))
                    (finish-call! service env cwd parsed drop
                                  @(:forgotten env) result started)))))))))))

(defn- drop-one-request!
  "Drop NAME in ENV (or just its file when no environment exists) and return
   `{:name :error}` — an error is reported without hiding the other names."
  [env root name]
  (try
    (let [error (if env
                  (:error (drop-one! env name))
                  (do (eval-files/delete-name! root name) nil))]
      {:name name :error error})
    (catch Throwable t
      {:name name :error (ex-message t)})))

(defn- drop-result
  [started results]
  (let [forgotten (mapv :name results)
        failures (vec (keep #(when-let [error (:error %)]
                               {:name (:name %) :error error})
                            results))]
    {:content (str/join "\n"
                        (concat (map #(str "[eval: forgot " % "]") forgotten)
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
   live names. Returns the tool/command result map, or throws (the caller
   reports)."
  ([service session names] (drop-request! service session names nil))
  ([service session names cwd]
   (when @(:disposed? service)
     (throw (ex-info "the agent's eval service is disposed"
                     {:type :eval/disposed})))
   (let [session (or session (extensions/get-session))
         _ (when-not session
             (throw (ex-info "eval needs a session" {:type :eval/no-session})))
         started (concurrent/monotonic-ms)
         lock (session-lock (:session-locks service) (:id session))]
     (locking lock
       (let [env (get @(:envs service) (:id session))
             _ (when env
                 (refresh-sources! service env
                                   (or cwd (tool-util/cwd)) nil))
             root (library-root service)
             names (mapv #(qualify-name (or (some-> env env-ns) "user") %)
                         (normalize-drop names))
             known? (if env
                      #(known-name? env %)
                      #(fs/exists? (eval-files/path-for-name root %)))]
         (validate-drop! known? names)
         (drop-result started
                      (mapv #(drop-one-request! env root %) names)))))))

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
   and searches, independent of any session's environment."
  [service]
  (eval-files/index (library-root service)))

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
        cwd (or (:cwd opts) (get-in opts [:ctx :cwd]) (tool-util/cwd))]
    (if (and (str/blank? (str (:code opts))) (seq (:drop opts)))
      (drop-request! service session (:drop opts) cwd)
      (let [lock (session-lock (:session-locks service) (:id session))]
        (locking lock
          (let [env (ensure-environment! service session cwd)]
            (eval!* service env cwd opts)))))))

(defn value
  "The `:eval` service for AGENT-CTX. The environments are keyed by session
   and owned here; the `:tools` handle is resolved from AGENT-CTX on the first
   call, so the script surface is the agent's own table.

   OPTS: `:agent-dir` pins the library root (tests, a sandboxed
   composition); it defaults to `cfg/get-agent-dir`."
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
      :drop! (fn [{:keys [session drop cwd]}]
               (drop-request! service session drop cwd))
      :library #(service-library service)
      :index #(service-index service)
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
