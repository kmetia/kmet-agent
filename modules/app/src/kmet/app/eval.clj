(ns kmet.app.eval
  "The `:eval` service: one persistent SCI environment per agent session over
   the agent home's global and cwd eval libraries.

   A session's environment is built lazily on its first eval by scanning the
   global `<agent-dir>/eval` root and the pinned cwd's
   `<agent-dir>/eval-scopes/<shared-cwd-key>` root. Every successful top-level
   definition writes its file immediately (cwd by default, global when
   requested or when it redefines an active global name); a redefinition
   keeps the record's existing file and preserves a file's sibling
   definitions. A name whose sanitized spelling would collide gets a short
   address digest, so distinct addresses never share a file. A new session or
   a restarted process loads both layers; an already-built environment is a
   runtime cache that refreshes only on `/eval reload`, which rebuilds it from
   both roots and replays the session-only definitions. `:drop` and
   `(eval/forget 'name)` resolve in the namespace the call ends in and delete
   the record's actual file, unbinding the live name and restoring a shadowed
   record when one exists. A
   file that also defines other addresses must be dropped with all of them. A
   mounted source's value is restored (reported as `restored`, not `forgot`)
   when its shadow is dropped. `(eval/move)` moves one record between layers.
   Sessions share the files but not the environment. A broken file is recorded
   under `:failures` and skipped, never fatal. One build is bounded by a
   per-file and an overall deadline; source functions themselves are ordinary
   extension code and are not interrupted.

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
            [kmet.app.tools.read :as read-tool]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.script :as script]
            [kmet.app.tools.util :as tool-util]
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

(def ^:private build-budget-ms
  "Bound on one whole environment build across all library files. Each file
   still gets at most `build-timeout-ms`, whichever deadline is sooner."
  30000)

(defn- rearm-build!
  "Point ENV's interrupt holder at the next rebuild deadline. The one-arity
   is a single file's bound; the two-arity also respects the build's overall
   DEADLINE."
  ([env] (rearm-build! env (+ (concurrent/monotonic-ms) build-timeout-ms)))
  ([env deadline]
   (script/rearm! (:interrupt env)
                  {:cancelled nil
                   :deadline (min deadline
                                  (+ (concurrent/monotonic-ms) build-timeout-ms))
                   :abort (atom nil)})))

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

(defn- target-scopes
  "The scopes a lookup considers, preferred first."
  [scope]
  (if scope [scope] [:cwd :global]))

(defn- index-entry-for
  "The disk index entry for NAME in SCOPE, preferring cwd when SCOPE is nil."
  [entries name scope]
  (let [matching (filter #(= name (:name %)) entries)]
    (if scope
      (first (filter #(= scope (:scope %)) matching))
      (or (first (filter #(= :cwd (:scope %)) matching))
          (first (filter #(= :global (:scope %)) matching))))))

(defn- canonical-file-target
  "The canonical file for NAME in ROOTS when no record or index entry names it.
   A file that parses but defines other addresses is refused: it is not this
   definition's file. A broken canonical file is still deletable, preserving a
   broken definition's drop path."
  [roots name scope]
  (let [candidates (->> (target-scopes scope)
                        (mapcat
                         (fn [s]
                           (let [root (get roots s)
                                 files (distinct
                                        [(eval-files/path-for-name root name)
                                         (eval-files/legacy-path-for-name root name)])]
                             (keep (fn [file]
                                     (when (fs/exists? file)
                                       (let [{:keys [defs error]}
                                             (eval-files/file-defs file)]
                                         {:scope s :file file :defs defs :error error})))
                                   files))))
                        vec)
        valid (first (filter #(some (fn [d] (= name (:name d))) (:defs %))
                             candidates))
        empty-defs (first (filter #(and (not (:error %)) (empty? (:defs %)))
                                  candidates))]
    (or valid
        (first (filter :error candidates))
        empty-defs
        (when-let [{:keys [file defs scope]} (first candidates)]
          (bad-request!
           (str "the library file for " name " does not define it: " file
                (when (seq defs)
                  (str " (defines " (str/join ", " (map :name defs)) ")")))
           {:name name :file file :scope scope :defs (mapv :name defs)})))))

(defn- target-source-flags
  "The source and unwritten flags for a target."
  [env name]
  {:source? (boolean (and env (contains? @(:source-keys env) name)))
   :unwritten? (boolean (and env (contains? @(:unwritten env) name)))})

(defn- record-drop-target
  "A resolved target backed by a loaded record; its `:file` is authoritative."
  [env name record]
  (assoc (target-source-flags env name)
         :name name
         :record record
         :file (:file record)
         :scope (record-scope record)))

(defn- file-drop-target
  "A resolved target backed by a file with no loaded record."
  [env name file scope]
  (assoc (target-source-flags env name)
         :name name :file file :scope scope))

(defn- marker-drop-target
  "A source-only or unwritten-only target, or nil when it has neither flag."
  [env name]
  (let [{:keys [source? unwritten?]} (target-source-flags env name)]
    (cond
      source? {:name name :scope :source :source? true :unwritten? unwritten?}
      unwritten? {:name name :scope :unwritten :source? false :unwritten? true})))

(defn- resolve-drop-target
  "Resolve one drop target to its live record and/or file. A record's `:file`
   is authoritative; the disk index and the canonical path are fallbacks for
   files that are not loaded. Returns nil when nothing names NAME."
  [env roots entries name scope]
  (let [record (when env
                 (if scope
                   (some #(when (= scope (record-scope %)) %) (stack-for env name))
                   (active-record env name)))]
    (or (when record (record-drop-target env name record))
        (when-let [entry (index-entry-for entries name scope)]
          (file-drop-target env name (:file entry) (:scope entry)))
        (when-let [canonical (canonical-file-target roots name scope)]
          (file-drop-target env name (:file canonical) (:scope canonical)))
        (when env (marker-drop-target env name)))))

(defn- resolve-drop-targets
  "Resolve TARGETS (`{:name :scope}`) against ENV and the disk index. Throws
   for an unknown name or a canonical file that belongs to other addresses,
   before the caller changes anything."
  [env roots entries targets]
  (mapv (fn [{:keys [name scope]}]
          (or (resolve-drop-target env roots entries name scope)
              (missing-definition! name scope)))
        targets))

(defn- validate-drop-targets!
  "Refuse a request that names the same address twice."
  [targets]
  (when-not (= (count targets) (count (distinct targets)))
    (bad-request! "drop names a definition twice" {:drop targets})))

(defn- file-record-names
  "The names the in-memory library or the disk index associates with FILE."
  [env entries file]
  (if env
    (->> (mapcat identity (vals @(:library env)))
         (filter #(= file (:file %)))
         (map :name)
         set)
    (->> entries (filter #(= file (:file %))) (map :name) set)))

(defn- check-drop-coverage!
  "Refuse to delete a file whose definitions the request does not cover. A
   file that parses with other addresses must be dropped with them (or edited
   directly); a broken file has no parsed addresses and keeps its old
   deletable path."
  [env entries resolved]
  (let [file-targets (filter :file resolved)
        target-names (set (map :name resolved))
        target-files (reduce (fn [acc {:keys [name file]}]
                               (update acc name (fnil conj #{}) file))
                             {} file-targets)]
    (doseq [[file targets] (group-by :file file-targets)]
      (let [library-names (file-record-names env entries file)
            parsed (:defs (eval-files/file-defs file))
            parsed-names (set (map :name parsed))
            missing (for [name (into library-names parsed-names)
                          :when (not (if (contains? library-names name)
                                       (contains? (get target-files name #{}) file)
                                       (contains? target-names name)))]
                      name)]
        (when (seq missing)
          (let [name (:name (first targets))]
            (bad-request!
             (str "cannot drop " name
                  ": its library file also defines "
                  (str/join ", " (sort missing))
                  "; drop them in the same request or edit the file")
             {:name name :file file :siblings (vec (sort missing))})))))))

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
           (script/edit-result path old-text new-text signal))
   :write-file (fn [path content]
                 (script/write-result path content signal))
   :read-file (fn
                ([path] (script/read-text path nil signal))
                ([path offset] (script/read-text path {:offset offset} signal))
                ([path offset limit]
                 (script/read-text path {:offset offset :limit limit} signal)))})

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

(def ^:private build-report-name-limit
  "Definitions named in the first eval's loaded-set line before `… N more`."
  12)

(def ^:private build-report-error-chars
  "Characters of one load failure's error in the first eval's report."
  300)

(def ^:private build-report-failure-limit
  "Load failures named in the first eval's report before a summary line."
  10)

(defn- report-entry-label
  "One definition in the first eval's loaded-set line."
  [{:keys [name scope active]}]
  (str name " (" (scope-label scope) (when (false? active) ", shadowed") ")"))

(defn- truncate-report-error
  [error]
  (let [error (-> error str (str/replace #"\s+" " ") str/trim)]
    (if (> (count error) build-report-error-chars)
      (str (str/trimr (subs error 0 (dec build-report-error-chars))) "…")
      error)))

(defn- loaded-set-line
  "The first eval's one-line loaded-set report, naming at most
   `build-report-name-limit` definitions."
  [entries]
  (let [n (count entries)
        more (max 0 (- n build-report-name-limit))]
    (str "[eval: loaded " n " library definition"
         (when (not= 1 n) "s") ": "
         (str/join ", " (map report-entry-label
                             (take build-report-name-limit entries)))
         (when (pos? more)
           (str "; … " more " more; (eval/library) lists them"))
         "]")))

(defn- failure-line
  "One load failure's result line."
  [{:keys [file error scope] :as failure}]
  (str "[eval: load failed " (if scope (name scope) "library") " "
       (or file (:name failure) "<library>") ": "
       (truncate-report-error error) "]"))

(defn- build-report-lines
  "The first eval after an environment build reports the loaded set and load
   failures in its result: one bounded loaded-set line, then one line per
   failure (bounded the same way)."
  [entries failures]
  (let [shown (take build-report-failure-limit failures)
        more (max 0 (- (count failures) build-report-failure-limit))]
    (concat
     (when (seq entries) [(loaded-set-line entries)])
     (map failure-line shown)
     (when (pos? more)
       [(str "[eval: … " more " more load failure"
             (when (not= 1 more) "s") "]")]))))

(defn- report-details
  "The first-eval report's machine-readable half: a bounded loaded preview and
   the full count."
  [{:keys [loaded loaded-count]}]
  {:loaded loaded :loaded-count loaded-count})

(defn- take-build-report!
  "The first call after an environment build consumes the pending report — a
   bounded loaded preview, its full count, and the result lines. Nil when the
   build loaded nothing and had no failures, and for every later call."
  [env]
  (when-let [{:keys [entries failures]} @(:build-report env)]
    (reset! (:build-report env) nil)
    (when (or (seq entries) (seq failures))
      {:loaded (vec (take build-report-name-limit entries))
       :loaded-count (count entries)
       :lines (vec (build-report-lines entries failures))})))

(defn- definition-line
  "One loaded definition's interface line: scope, kind and arglists, and the
   first docstring line (or `(no docstring)`)."
  [{:keys [kind doc arglists] :as entry}]
  (let [doc-line (eval-format/doc-line doc)
        scope (or (:scope entry) :global)
        shadow (when (false? (:active entry)) ", shadowed")]
    (str "  - " (:name entry) " " (when kind (name kind))
         (eval-format/arglists-text arglists)
         " (" (scope-label scope) shadow ") — "
         (or doc-line "(no docstring)"))))

(defn- session-only-entries
  "The session's unwritten live definitions as listing entries: they have no
   library file, so they are discoverable through (eval/help) and
   (eval/describe) but not through (eval/library)."
  [env]
  (->> @(:unwritten-records env)
       (map (fn [[name record]]
              (assoc (library-entry record) :name name :active true)))
       (sort-by :name)
       vec))

(def ^:private builtin-verb-help
  {"user/read" {:name "user/read" :kind :verb
                :doc "Read PATH with the read tool's implementation; OPTS may carry :offset and :limit."
                :arglists '([path] [path opts])}
   "user/write" {:name "user/write" :kind :verb
                 :doc "Write CONTENT to PATH with the write tool's implementation."
                 :arglists '([path content])}
   "user/edit" {:name "user/edit" :kind :verb
                :doc "Replace OLD-TEXT with NEW-TEXT in PATH with the edit tool's implementation."
                :arglists '([path old-text new-text])}
   "user/bash" {:name "user/bash" :kind :verb
                :doc "Run COMMAND with the bash tool's implementation; OPTS may carry the tool's command options."
                :arglists '([command] [command opts])}
   "user/sh" {:name "user/sh" :kind :verb
              :doc "Run COMMAND through the process shell; OPTS may carry :in, :dir, or :out."
              :arglists '([command] [command opts])}
   "clj/edit-form" {:name "clj/edit-form" :kind :verb
                    :doc "Apply a structured whole-form edit (same arguments as the clojure_edit tool)."
                    :arglists '([args])}
   "clj/replace-sexp" {:name "clj/replace-sexp" :kind :verb
                       :doc "Replace one form with another (same arguments as clojure_edit_replace_sexp)."
                       :arglists '([args])}
   "clj/paren-repair" {:name "clj/paren-repair" :kind :verb
                       :doc "Repair unbalanced delimiters (same arguments as clojure_paren_repair)."
                       :arglists '([args])}})

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
        (or (get builtin-verb-help name)
            (bad-request! (str "no eval definition named " name)
                          {:name name}))))))

(defn- describe-view
  "The default `(eval/describe 'name)` view: nil-valued fields are omitted,
   and `:doc` is omitted from an entry with a `:source`. The full entry stays
   reachable through an explicit key."
  [entry]
  (let [entry (if (str/blank? (str (:source entry)))
                entry
                (dissoc entry :doc))]
    (reduce-kv (fn [view k v]
                 (if (nil? v) view (assoc view k v)))
               {} entry)))

(defn- describe
  ([env name] (describe-view (describe-entry env name)))
  ([env name key]
   (let [entry (describe-entry env name)]
     (if (nil? key) (describe-view entry) (get entry key)))))

(defn- builtin-help-line [] "Built-in verbs: user/read, user/write, user/edit, user/bash and user/sh call the tool implementations directly; clj/edit-form, clj/replace-sexp and clj/paren-repair call kmet.libs.clj-edit directly (they do not depend on the tool selection or the clojure extension); tools/call, tools/list, tools/describe use the active tool surface; (emit v), (emit-image x); (sandbox/spawn f).")

(defn- indent-text "TEXT with every line indented four spaces." [text] (str/join "\n" (map (fn [line] (str "    " line)) (str/split-lines (str text)))))

(defn- source-help [env] (let [sources (clojure.core/deref (:source-info env))] (str "Eval sources:\n" (if (seq sources) (str/join "\n" (map (fn [source] (eval-format/source-line source :keyword)) sources)) "  (none)"))))

(defn- library-help
  [env]
  (let [entries (library-entries env)
        session-only (session-only-entries env)]
    (str "Library definitions: " (count entries)
         (when (seq entries)
           (str "\n" (str/join "\n" (map definition-line entries))))
         "\nSession-only definitions (live, not saved): " (count session-only)
         (when (seq session-only)
           (str "\n" (str/join "\n" (map definition-line session-only)))))))

(defn- scope-help [env] (str "Scope: cwd " (get-in env [:roots :cwd]) " (shadows the global layer)" "\n       global " (get-in env [:roots :global]) "\nNew definitions write to cwd; pass :scope :global for a new global " "name. A redefinition keeps the active layer."))

(defn- persistence-help [] (str "Persistence: eval creates verbs. A top-level definition with a " "non-blank docstring is written to the library and loads in later sessions; a " "new definition without one stays live for this session only and is " "not saved. Add a docstring and re-evaluate to keep it; a defmethod is " "saved with its loaded multimethod. A saved " "definition redefined without a docstring is not written; the saved " "file is unchanged. A call that " "ends in a normal error writes nothing; its definitions stay live and " "can be forgotten. Drop/forget resolve unqualified names in the " "namespace the call ends in; a file that also defines other addresses " "must be dropped with all of them. The prompt previews the saved or " "loaded definitions; the first eval reports any loaded definitions " "and files that failed."))

(defn- sandbox-help
  []
  (str "Sandbox: Clojure/SCI — " script/sandbox-summary
       "\n" (builtin-help-line)
       "\nFor stdin or process options, pass a command vector and an opts map: "
       "(sh [\"cmd\" \"arg\"] {:in text})."
       "\nLocal let/fn bindings are scratch; the next call starts in the "
       "namespace the previous call ended in."
       "\nEval verbs: (eval/library), (eval/sources), (eval/describe 'name), "
       "(eval/describe 'name key), (eval/forget 'name), "
       "(eval/patch 'name old new), (eval/edit-form 'name new-source), "
       "(eval/move 'name :global|:cwd)."
       "\nDiscovery: (all-ns) lists namespaces; (ns-publics 'ns) lists a "
       "namespace's vars, including session-only definitions. A patch or "
       "edit-form result must keep a docstring."
       "\nReporting: (emit value) or end on the final value; stdout and "
       "stderr are discarded."))

(defn- help-entry-text
  [entry]
  (let [{:keys [kind doc arglists file scope source] entry-name :name} entry
        arg (eval-format/arglists-text arglists)
        scope-label (cond
                      scope (str "(" (name scope) ")")
                      (str/blank? (str source)) "(built-in)"
                      :else "(source)")
        doc-line (or (eval-format/doc-line doc) "(no docstring)")]
    (str entry-name " " (when kind (name kind)) (or arg "") " " scope-label
         "\n  doc: " doc-line
         (when file (str "\n  file: " file))
         (when-not (str/blank? (str source))
           (str "\n  source:\n" (indent-text source))))))

(defn- help-topic-text
  [env topic]
  (case topic
    :library (library-help env)
    :persistence (persistence-help)
    :sandbox (sandbox-help)
    (bad-request! (str "unknown eval help topic " (pr-str topic)
                       "; use :library, :persistence, :sandbox")
                  {:topic topic})))

(defn- help-index
  [env]
  (let [entries (library-entries env)
        session-only (session-only-entries env)]
    (str "Eval help: (eval/help), (eval/help :library), "
         "(eval/help :persistence), (eval/help :sandbox), "
         "or (eval/help 'name)."
         "\n" (source-help env)
         "\n" (builtin-help-line)
         "\nLibrary: " (count entries) " saved, "
         (count session-only) " session-only; (eval/help :library) lists both."
         "\n" (scope-help env)
         "\nFailures: " (count @(:failures env))
         "\n(eval/help 'name) describes one definition or verb.")))

(defn- environment-help
  "The `(eval/help)` text: an index, a topic, or one definition's interface.
   Topics: :library, :persistence, :sandbox."
  ([env] (help-index env))
  ([env what]
   (cond
     (nil? what) (help-index env)
     (keyword? what) (help-topic-text env what)
     (or (symbol? what) (string? what))
     (help-entry-text (describe-entry env what))
     :else (bad-request! (str "eval help needs a topic or name, got "
                              (pr-str what))
                         {:help what}))))

(defn- file-source
  "The stored defining form of ADDRESS in FILE, or nil when the file is gone
   or does not define it."
  [file address]
  (when file
    (some (fn [record]
            (when (= address (:name record)) (:source record)))
          (:defs (eval-files/file-defs file)))))

(defn- take-failures!
  "Failures are reported once: return the current list and clear the
   environment's entries."
  [env]
  (let [failures (if env @(:failures env) [])]
    (when env (reset! (:failures env) []))
    failures))

;; ─── Environment build ────────────────────────────────────────────────────

(defn- record-failure! [failures failure]
  (swap! failures conj failure))

(defn- eval-content!
  "Parse and evaluate CONTENT into ENV without indexing it. The original ns
   form runs first (preserving its requires), then the standard aliases, then
   the rest of the file. Returns `{:parsed …}` or `{:error …}`; a failed
   evaluation leaves the caller to decide what to report. DEADLINE is the
   build's overall interrupt deadline."
  [env content deadline]
  (try
    (let [parsed (defs/parse content "user")
          {:keys [ns-form body]} (defs/split-ns content)
          source (str (or ns-form (str "(ns " (:ns parsed) ")"))
                      "\n" script/alias-prelude "\n" body)]
      (rearm-build! env deadline)
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
  [env scope deadline {:keys [file content error]}]
  (if error
    (record-failure! (:failures env) {:file file :error error :scope scope})
    (let [{:keys [parsed error]} (eval-content! env content deadline)]
      (if error
        (record-failure! (:failures env) {:file file :error error :scope scope})
        (doseq [record (:defs parsed)]
          (add-record! env (assoc record :file file :scope scope
                                  :root (root-for env scope))))))))

(defn- read-library!
  "Read and evaluate every definition file in ROOT into ENV at SCOPE, sorted
   by path, under the build's DEADLINE."
  [env root scope deadline]
  (doseq [file (eval-files/scan root)]
    (load-library-file! env scope deadline file)))

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
          path (or (:file record) (eval-files/definition-path root record))]
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
    (when-not (or (not (str/blank? (:doc patched)))
                  (= :defmethod (:kind patched)))
      (bad-request! (str label " left " name " without a docstring; add one to keep the library documented")
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

(defn- check-move!
  "The shared refusals for moving NAME from CURRENT to TARGET: sibling
   definitions, a same-scope move, and a target layer that already has the
   name. TARGET-PRESENT? answers the last one."
  [name current target siblings target-present?]
  (ensure-no-siblings! name siblings)
  (when (= current target)
    (bad-request! (str name " is already in the " (scope-label target) " scope")
                  {:name name :scope target}))
  (when target-present?
    (bad-request! (str "the " (scope-label target) " scope already has " name)
                  {:name name :scope target})))

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
      (let [current (record-scope record)]
        (check-move! name current target (file-siblings env record)
                     (some #(= target (record-scope %)) (stack-for env name)))
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
             :unwritten (atom #{})
             :unwritten-records (atom {})
             :build-report (atom nil)
             :forgotten (atom #{})
             :call-scope (atom nil)
             :sticky-ns (atom nil)
             :lock (Object.)}
        interrupt (script/interrupt-holder)
        env-ref (atom nil)
        library-fn (fn [] (library-entries @env-ref))
        forget-fn (fn [name] (request-forget! @env-ref name))
        sources-fn (fn [] @(:source-info @env-ref))
        help-fn (fn [& [what]] (environment-help @env-ref what))
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
        sources (refresh-sources! service env cwd nil)
        deadline (+ (concurrent/monotonic-ms) build-budget-ms)]
    (binding [tool-util/*cwd* cwd
              bash-tool/*shell* bash-tool/*shell*
              bash-tool/*session-env-fn* bash-tool/*session-env-fn*
              read-tool/*skill-resolver* read-tool/*skill-resolver*]
      (script/with-prime-context
        sci-ctx (prime-state service cwd (:namespaces sources))
        (fn []
          (read-library! env (:global roots) :global deadline)
          (read-library! env (:cwd roots) :cwd deadline)
          (swap! (:model-shadows env)
                 into
                 (filter #(contains? @(:source-keys env) %)
                         (keys @(:library env))))
          (reset! (:build-report env)
                  {:entries (library-entries env)
                   :failures (vec @(:failures env))}))))
    env))

(defn- ensure-environment!
  "The current environment for SESSION, built from the library on first use.
   An existing environment is reused until an explicit `/eval reload`: a session
   is a runtime cache, not a branch-scoped definition log."
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

(defn- replay-live!
  "Evaluate OLD's session-only records into ENV, keeping them live-only. A
   failure leaves that name out of ENV and is returned; the caller keeps the
   old environment rather than swapping in a partial one."
  [env old]
  (reduce (fn [failures [name record]]
            (let [{:keys [error]} (eval-definition! env record)]
              (if error
                (conj failures {:name name :error error
                                :scope (or (:scope record) :cwd)})
                (do
                  (swap! (:unwritten env) conj name)
                  (swap! (:unwritten-records env) assoc name record)
                  failures))))
          []
          (sort-by key @(:unwritten-records old))))

(defn- reload-environment!
  "Build a fresh environment for OLD's pinned cwd and replay OLD's
   session-only records. Returns `{:env fresh :replayed n}` on success; a build
   or replay failure returns `{:env old :kept? true :failures [...]}`, so a
   broken file cannot empty a working session. The fresh environment's
   first-eval build report is cleared: the reload itself reports the outcome."
  [service old]
  (let [fresh (build-environment service (:cwd old))
        build-failures (vec @(:failures fresh))]
    (if (seq build-failures)
      {:env old :kept? true :failures build-failures}
      (let [replay-failures (replay-live! fresh old)]
        (if (seq replay-failures)
          {:env old :kept? true :failures replay-failures}
          (do
            (when-let [sticky (some-> @(:sticky-ns old) sci/ns-name)]
              (reset! (:sticky-ns fresh) (sci/find-ns (:sci-ctx fresh) sticky)))
            (reset! (:build-report fresh) nil)
            {:env fresh :replayed (count @(:unwritten-records old))}))))))

;; ─── Request normalization ────────────────────────────────────────────────

(defn- normalize-drop [drop]
  (mapv (fn [entry]
          (or (->name entry)
              (bad-request! (str "drop entries are definition names, got "
                                 (pr-str entry))
                            {:drop entry})))
        (or drop [])))

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

(defn- active-source-value?
  "Is NAME currently provided by a mounted eval source?"
  [env name]
  (and (contains? @(:active-source-keys env) name)
       (contains? @(:source-values env) name)))

(defn- restore-source!
  "Seed NAME's active source value, if it has one, and return the seed error."
  [env name]
  (when (active-source-value? env name)
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

(defn- restore-next-layer!
  "After an active record was removed, re-evaluate the next record for NAME.
   Returns `{:next? bool :error msg}`."
  [env name active?]
  (if-not active?
    {:next? false}
    (if-let [next (active-record env name)]
      {:next? true
       :error (some-> (eval-definition! env next) :error)}
      {:next? false})))

(defn- drop-record!
  "Drop RECORD from its layer: delete its actual file, unbind the live var
   when it is the active record, and re-evaluate the next layer after an
   active drop. A missing file is not an error: the record and live name are
   still removed."
  [env name record]
  (let [active? (= record (active-record env name))
        scope (record-scope record)
        root (root-for env scope)
        path (or (:file record) (eval-files/definition-path root record))
        file (eval-files/delete-file! root path)
        unbind-error (when active? (unbind! env name record))]
    (remove-record! env name record)
    (let [{:keys [next? error]} (restore-next-layer! env name active?)]
      (when active?
        (clear-unwritten! env name))
      {:name name
       :file file
       :scope scope
       :active active?
       :next? next?
       :action :forgot
       :error (cond
                error (str "could not restore the next layer: " error)
                unbind-error unbind-error)})))

(defn- drop-unwritten!
  "Unbind and forget NAME's unwritten live definition. Returns the unbind
   error or nil."
  [env name]
  (let [live? (interned? env {:name name})
        error (when live? (unbind! env name nil))]
    (clear-unwritten! env name)
    error))

(defn- drop-file-target!
  "Delete the resolved file of a target that has no loaded record. Returns the
   result map; a missing file is not an error."
  [roots name file scope]
  (let [root (get roots scope)]
    (when-not root
      (bad-request! (str "unknown eval scope " scope)
                    {:name name :scope scope}))
    {:name name
     :file (eval-files/delete-file! root file)
     :scope scope
     :action :forgot}))

(defn- drop-source!
  "Drop NAME's live source shadow and restore the mounted source value. The
   action is `:restored` only while the source is still mounted; an unloaded
   source's leftover live value is unbound instead."
  [env name]
  (let [active? (active-source-value? env name)
        shadow? (contains? @(:model-shadows env) name)
        live? (interned? env {:name name})
        unbind-error (when (and live? (or shadow? (not active?)))
                       (unbind! env name nil))
        restore-error (when active? (restore-source! env name))]
    (swap! (:model-shadows env) disj name)
    (clear-unwritten! env name)
    {:name name
     :scope :source
     :action (if active? :restored :forgot)
     :error (or unbind-error restore-error)}))

(defn- drop-unwritten-follow-up!
  "Unbind a live unwritten definition when the dropped record was not active."
  [env name unwritten? active]
  (when (and unwritten? (not active))
    (drop-unwritten! env name)))

(defn- restore-source-follow-up!
  "Seed NAME's source value when a source shadow is what was dropped."
  [env name source? restore?]
  (when (and source? restore?)
    (restore-source! env name)))

(defn- drop-record-resolved!
  "Drop NAME's RECORD and its source/unwritten follow-ups. Source restoration
   only follows a drop that left no lower record to restore."
  [env name record source? unwritten?]
  (let [{:keys [active next? error] :as result} (drop-record! env name record)
        source-dropped? (and source? active (not next?))
        unbind-error (drop-unwritten-follow-up! env name unwritten? active)
        _ (when active (swap! (:model-shadows env) disj name))
        restore-error (restore-source-follow-up! env name source? source-dropped?)
        restored? (and source-dropped? (active-source-value? env name))]
    (assoc result
           :action (if restored? :restored :forgot)
           :error (or error unbind-error restore-error))))

(defn- drop-file-resolved!
  "Drop a target's file and, when it also had an unwritten live definition,
   unbind that definition."
  [env roots name file scope unwritten?]
  (let [{:keys [error] :as result} (drop-file-target! roots name file scope)
        unbind-error (when unwritten? (drop-unwritten! env name))]
    (assoc result :error (or error unbind-error))))

(defn- drop-resolved-target!
  "Apply one resolved drop target: its record, its file, a source shadow, or
   an unwritten live definition."
  [env roots {:keys [name record file source? unwritten? scope]}]
  (cond
    record (drop-record-resolved! env name record source? unwritten?)
    file (drop-file-resolved! env roots name file scope unwritten?)
    source? (drop-source! env name)
    unwritten? {:name name :scope :unwritten :action :forgot
                :error (drop-unwritten! env name)}
    :else (missing-definition! name scope)))

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

(defn- doc-note
  "The result-line note for a written definition: a method is covered by its
   multimethod."
  [record]
  (if (= :defmethod (:kind record)) " (method)" ""))

(defn- live-only-line
  "The result line for a definition that stays live without a library file:
   an undocumented definition (new or redefining a saved name), or a method
   whose multimethod is not saved."
  [record scope saved?]
  (str "[eval: defined " (:name record) " (" (scope-label scope)
       "; live only, not saved: "
       (cond
         (= :defmethod (:kind record)) "its multimethod is not saved"
         saved? "no docstring — the saved file is unchanged; add one and re-evaluate to update it"
         :else "no docstring — add one and re-evaluate to keep it")
       "]"))

(defn- apply-definition!
  "Write RECORD to its scope's library and index it. A source-provided key
   stays live but is never written, and is remembered as a model shadow so
   later source reads do not overwrite it. An undocumented definition stays
   live for the session and is not written, so a saved definition redefined
   without a docstring keeps its saved file unchanged. A defmethod of a loaded
   multimethod is
   written with it. A failed write is recorded and reported; the live
   definition remains."
  [env cwd record]
  (let [name (:name record)
        scope (or (:scope record) :cwd)
        record (assoc record :scope scope)]
    (if (contains? @(:source-keys env) name)
      (do
        (swap! (:model-shadows env) conj name)
        {:line (str "[eval: redefined " name " (source; not stored)]")})
      (if (not (documented? env record))
        (let [saved? (some? (existing-record env name scope))]
          (swap! (:unwritten env) conj name)
          (swap! (:unwritten-records env) assoc name record)
          {:line (live-only-line record scope saved?)
           :entry (assoc (library-entry record) :active true)})
        (try
          (let [root (root-for env scope)
                [path record] (write-redefinition! env root record)
                record (assoc record :file path :root root)]
            (add-record! env record)
            (clear-unwritten! env name)
            {:line (str "[eval: defined " name " (" (scope-label scope) ") → "
                        (eval-files/display-path cwd path)
                        (doc-note record) "]")
             :entry (assoc (library-entry record) :active true)})
          (catch Throwable t
            (swap! (:unwritten env) conj name)
            (swap! (:unwritten-records env) assoc name record)
            (record-failure! (:failures env) {:name name :error (ex-message t)
                                              :scope scope})
            {:line (str "[eval: define failed " name " (" (scope-label scope) "): "
                        (ex-message t) "]")
             :error (ex-message t)}))))))

(defn- drop-report-line
  "One drop result line. A restored source value is named as such; a record
   drop that also restored a source says both."
  [{:keys [name scope action error]}]
  (cond
    error (str "[eval: failed " name ": " error "]")
    (and (= :restored action) (= :source scope))
    (str "[eval: restored " name " (source)]")
    (= :restored action)
    (str "[eval: forgot " name " (" (scope-label scope) "); source restored]")
    :else
    (str "[eval: forgot " name " (" (scope-label scope) ")]")))

(defn- apply-changes!
  "Apply the successful call's changes: drop/forget first, then write every
   definition the call made. Drop names resolve in the namespace the call ends
   in, like `(eval/forget …)`. All targets resolve before anything is deleted.
   Returns the report the tool result carries; a failure keeps the partial
   report."
  [env cwd records drop forgotten scope]
  (let [env-ns (env-ns env)
        forgotten (mapv (fn [{:keys [name scope]}]
                          {:name (qualify-name env-ns name) :scope scope})
                        forgotten)
        targets (distinct (concat (map #(hash-map :name (qualify-name env-ns %) :scope scope)
                                       drop)
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
      (doseq [{:keys [name]} targets]
        (when (contains? parsed-names name)
          (bad-request! (str "a definition cannot be defined and dropped in one call: "
                             name)
                        {:name name})))
      (validate-drop-targets! targets)
      (when (seq targets)
        (let [roots (:roots env)
              entries (:entries (eval-files/index roots))
              resolved (resolve-drop-targets env roots entries targets)]
          (check-drop-coverage! env entries resolved)
          (doseq [target resolved]
            (let [result (drop-resolved-target! env roots target)
                  {:keys [name error] actual-scope :scope} result]
              (swap! lines conj (drop-report-line result))
              (swap! forgotten-names conj name)
              (when error
                (swap! unbind-errors inc)
                (record-failure! (:failures env) {:name name :error error
                                                  :scope actual-scope}))))))
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

(defn- content-with-lines
  "BASE-CONTENT followed by LINES, separated by newlines; blank parts are
   omitted."
  [base-content lines]
  (str/join "\n" (remove str/blank? [base-content (str/join "\n" lines)])))

(defn- error-result
  "A failed call reports its error, never applies definitions or drops, and
   reports the build failures collected for this attempt. The first call after
   an environment build also carries the loaded-set report."
  [env base started]
  (let [base (base-result base started)
        report (take-build-report! env)
        failures (take-failures! env)
        content (content-with-lines (:content base) (:lines report))]
    (result-parts
     base
     (assoc base
            :content (if (str/blank? content) (:content base) content)
            :is-error true
            :details (cond-> (merge (:details base)
                                    {:defined [] :forgotten []})
                       report (merge (report-details report))
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
  [env cwd records drop forgotten scope base started]
  (let [base (base-result base started)
        report (take-build-report! env)
        changes (apply-changes! env cwd records drop forgotten scope)
        _ (when (:error changes) (mark-transient! env records))
        lines (concat (:lines report) (:lines changes))
        content (content-with-lines (:content base) lines)
        failures (take-failures! env)
        write-errors (pos? (:write-errors changes 0))
        unbind-errors (pos? (:unbind-errors changes 0))
        failed? (or (:error changes) write-errors unbind-errors)]
    (result-parts
     base
     (assoc base
            :content (if (str/blank? content) "(no output)" content)
            :is-error (boolean failed?)
            :details (cond-> (merge (:details base)
                                    (select-keys changes [:defined :forgotten])
                                    (when report (report-details report)))
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
            drop (normalize-drop drop)
            {:keys [parsed error]} (try-parse-code code env)
            records (when parsed (scoped-records env scope parsed))
            codemode (fn []
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
          (error-result env (codemode) started)
          (if (str/blank? code)
            (bad-request! "eval needs code or drop" {})
            (do
              (reset! (:forgotten env) #{})
              (reset! (:change-lines env) [])
              (reset! (:call-scope env) scope)
              (try
                (let [result (codemode)]
                  (if (:is-error result)
                    (do
                      (when-not (contains? #{:timeout :aborted :incomplete}
                                           (get-in result [:details :error]))
                        (mark-transient! env records))
                      (error-result env result started))
                    (finish-call! env cwd records drop
                                  @(:forgotten env) scope result started)))
                (finally
                  (reset! (:call-scope env) nil))))))))))

(defn- drop-result
  "The tool/command result for a code-less drop. Every target reports its own
   line; a failed target does not hide the others."
  [started results]
  (let [forgotten (mapv :name results)
        failures (vec (keep #(when-let [error (:error %)]
                               {:name (:name %) :error error
                                :scope (:scope %)})
                            results))]
    {:content (str/join "\n" (map drop-report-line results))
     :details (cond-> {:defined []
                       :forgotten forgotten
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

(defn- counted
  "N and SINGULAR, pluralized when N is not one."
  [n singular]
  (str n " " singular (when (not= 1 n) "s")))

(defn- reload-result
  "The command result for a completed reload: the count line, any failures,
   and the machine-readable details."
  [verb n live-count failures]
  {:content (str/join
             "\n"
             (cons (str "[eval: " verb " " (counted n "library definition")
                        (when (pos? live-count)
                          (str "; replayed "
                               (counted live-count "live-only definition")))
                        "]")
                   (map failure-line failures)))
   :details (cond-> {:reloaded true :loaded n}
              (pos? live-count) (assoc :replayed live-count)
              (seq failures) (assoc :failures failures))
   :is-error (boolean (seq failures))})

(defn- reload-request!
  "The `/eval reload` service: rebuild SESSION's environment from both library
   roots in a fresh sandbox, replaying its session-only definitions. A build or
   replay failure keeps the current environment and reports the failures, so a
   broken file cannot empty a working session. Without a built environment this
   is the first build. Returns the command result map."
  [service {:keys [session cwd]}]
  (when @(:disposed? service)
    (throw (ex-info "the agent's eval service is disposed"
                    {:type :eval/disposed})))
  (let [session (resolve-session session)
        _ (when-not session
            (throw (ex-info "eval needs a session"
                            {:type :eval/no-session})))
        lock (session-lock (:session-locks service) (:id session))]
    (locking lock
      (if-let [old (get @(:envs service) (:id session))]
        (let [{:keys [env kept? failures replayed]}
              (reload-environment! service old)]
          (if kept?
            {:content (str/join
                       "\n"
                       (cons "[eval: reload failed; the current environment is unchanged]"
                             (map failure-line failures)))
             :details {:reloaded false :failures failures}
             :is-error true}
            (let [n (count (library-entries env))]
              (swap! (:envs service) assoc (:id session) env)
              (reload-result "reloaded" n (or replayed 0) nil))))
        (let [env (ensure-environment! service session (or cwd (tool-util/cwd)))
              n (count (library-entries env))
              failures (take-failures! env)]
          ;; the command reports this build; do not repeat it as the first eval
          (reset! (:build-report env) nil)
          (reload-result "loaded" n 0 failures))))))

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
             targets (mapv #(hash-map :name % :scope scope) names)
             entries (:entries (eval-files/index roots))]
         (validate-drop-targets! targets)
         (let [resolved (resolve-drop-targets env roots entries targets)]
           (check-drop-coverage! env entries resolved)
           (drop-result
            started
            (mapv (fn [target]
                    (try
                      (drop-resolved-target! env roots target)
                      (catch Throwable t
                        {:name (:name target)
                         :scope (:scope target)
                         :action :forgot
                         :error (ex-message t)})))
                  resolved))))))))

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
  "The current environment's unreported load failures, if it has one."
  [service]
  (if-let [env (current-environment service)]
    @(:failures env)
    []))

(defn- service-live
  "The current environment's live-only definitions. Empty when the session
   has no built environment."
  ([service] (service-live service nil))
  ([service opts]
   (if-let [session (or (:session opts) (extensions/get-session))]
     (if-let [env (get @(:envs service) (:id session))]
       (session-only-entries env)
       [])
     [])))

(defn- service-index
  "The library on disk, read without evaluating anything — the saved half of
   what `/eval` lists and searches. OPTS may carry `:session` (use its built
   environment's pinned roots) and `:cwd` (the scope for a session without an
   environment)."
  ([service] (service-index service nil))
  ([service opts]
   (let [env (when-let [session (:session opts)]
               (get @(:envs service) (:id session)))]
     (eval-files/index
      (or (:roots env)
          (roots-for service (or (:cwd opts) (tool-util/cwd))))))))

(defn- describe-indexed
  "The disk view of NAME for `/eval describe`: the active saved record with
   its source read from the file. Nil when NAME is not saved."
  [service session cwd name]
  (let [entries (:entries (service-index service {:session session :cwd cwd}))
        entry (first (filter #(and (= name (:name %)) (:active %)) entries))]
    (when entry
      (-> (select-keys entry [:name :kind :doc :arglists :file :scope])
          (assoc :source (file-source (:file entry) name))))))

(defn- describe-source
  "A mounted source's view of NAME, read without an environment. Nil when no
   active source contributes it."
  [service cwd name]
  (when-let [entry (get (:values (resolve-sources service (or cwd (tool-util/cwd))
                                                  nil))
                        name)]
    {:name name
     :kind (if (:macro? entry) :macro :verb)
     :doc (:doc entry)
     :arglists (:arglists entry)
     :file nil
     :scope nil
     :source (:source entry)}))

(defn- describe-request
  "The service's `:describe` reader: NAME as `(eval/describe)` resolves it in
   the session's built environment, else the saved disk index, else a mounted
   source's verb, else a built-in user verb. Never builds an environment."
  [service {:keys [session cwd name]}]
  (let [session (resolve-session session)
        env (when session (get @(:envs service) (:id session)))
        raw-name (->name name)
        _ (when-not raw-name
            (bad-request! (str "describe needs a definition name, got "
                               (pr-str name))
                          {:name name}))
        env-entry (when env
                    (try
                      (describe-entry env raw-name)
                      (catch clojure.lang.ExceptionInfo e
                        (if (= :eval/bad-request (:type (ex-data e)))
                          nil
                          (throw e)))))]
    (or env-entry
        (describe-indexed service session cwd raw-name)
        (describe-source service cwd raw-name)
        (get builtin-verb-help (str raw-name))
        (bad-request! (str "no eval definition named " raw-name)
                      {:name raw-name}))))

(defn- service-manifest
  "The prompt manifest for SESSION: the built environment's loaded records when
   it has one, else the disk index described as saved. Never builds an
   environment and never invokes a source. The disk view is cached per cwd
   until the files' paths, sizes or modification times change."
  [service {:keys [session cwd]}]
  (let [session (or session (extensions/get-session))
        env (when session (get @(:envs service) (:id session)))]
    (if env
      {:loaded? true
       :entries (library-entries env)}
      (let [cwd (or cwd (tool-util/cwd))
            roots (roots-for service cwd)
            fingerprint (eval-files/fingerprint roots)
            cached (get @(:manifest-cache service) cwd)]
        (if (= fingerprint (:fingerprint cached))
          (:manifest cached)
          (let [{:keys [entries]} (eval-files/index roots)
                manifest {:loaded? false :entries entries}]
            (swap! (:manifest-cache service) assoc cwd
                   {:fingerprint fingerprint :manifest manifest})
            manifest))))))

(defn- move-indexed-record!
  "Move NAME in the disk index when SESSION has no built environment. No
   value is evaluated; only the file and the move result are produced."
  [service name target cwd]
  (let [roots (roots-for service cwd)
        index (eval-files/index roots)
        entries (filterv #(= name (:name %)) (:entries index))
        source (or (first (filterv #(= :cwd (:scope %)) entries))
                   (first (filterv #(= :global (:scope %)) entries)))]
    (when-not source
      (bad-request! (str "no eval library definition named " name)
                    {:name name}))
    (let [current (:scope source)]
      (check-move! name current target (sibling-names source (:entries index))
                   (some #(= target (:scope %)) entries))
      (let [path (eval-files/move-definition! roots source target)]
        {:content (str "[eval: moved " name " (" (scope-label current) " → "
                       (scope-label target) ")]")
         :details {:moved {:name name :from current :to target :file path}}
         :is-error false}))))

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
      (move-indexed-record! service name target cwd))))

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
         session-locks (atom {})
         manifest-cache (atom {})
         disposed? (atom false)
         service {:agent-ctx agent-ctx
                  :agent-dir (or agent-dir (cfg/get-agent-dir))
                  :envs envs
                  :session-locks session-locks
                  :manifest-cache manifest-cache
                  :disposed? disposed?}]
     {:eval! #(eval-call! service %)
      :drop! (fn [{:keys [session drop cwd scope]}]
               (drop-request! service session drop cwd scope))
      :move! #(move-request! service %)
      :reload! #(reload-request! service %)
      :library #(service-library service)
      :live (fn
              ([] (service-live service))
              ([opts] (service-live service opts)))
      :describe #(describe-request service %)
      :index (fn
               ([] (service-index service))
               ([opts] (service-index service opts)))
      :manifest #(service-manifest service %)
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
                  (reset! manifest-cache {})
                  nil)})))
