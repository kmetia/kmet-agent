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
   `(forget 'name)` resolve in the namespace the call ends in and delete
   the record's actual file, unbinding the live name and restoring a shadowed
   record when one exists. A
   file that also defines other addresses must be dropped with all of them. A
   mounted source's value is restored (reported as `restored`, not `forgot`)
   when its shadow is dropped. `(move)` moves one record between layers.
   Sessions share the files but not the environment. A broken file is recorded
   under `:failures` and skipped, never fatal. One build is bounded by a
   per-file and an overall deadline; source functions themselves are ordinary
   extension code and are not interrupted. A session-only definition is saved
   without resending it by `(save ...)`; every call's full body is kept
   in a bounded ring read by `(result ...)`.

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
            [kmet.libs.clj-eval :as clj-eval]
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

(defn- env-ns
  "The namespace the current call ends in; every new call starts again in
   user."
  [env]
  (or (some-> @(:call-ns env) sci/ns-name str) "user"))

(defn- user-ns
  [env]
  (or (sci/find-ns (:sci-ctx env) 'user)
      (sci/create-ns 'user)))

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

(defn- source-result-details
  "The source portion of an eval result's :details: the current source INFO
   only when its id set differs from the last eval call's, plus any source
   errors. Returns `[details ids]`; the caller records IDS with the result."
  [env sources]
  (let [info (:info sources)
        ids (mapv :id info)]
    [(cond-> (if (not= ids @(:reported-source-ids env))
               {:sources info}
               {})
       (seq (:errors sources))
       (assoc :source-errors (:errors sources)))
     ids]))

;; ─── Library index ────────────────────────────────────────────────────────

(defn- library-entry [record]
  (cond-> (select-keys record [:name :ns :kind :doc :arglists :file :scope])
    (= :failed (:load-status record))
    (assoc :load-status :failed :load-error (:load-error record))))

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

(defn- failed-entry?
  "True when ENTRY is a saved definition whose file failed to load."
  [entry]
  (= :failed (:load-status entry)))

(defn- library-counts
  "The saved/loaded/failed split for library ENTRIES, plus the
   `N saved (L loaded, K failed)` text."
  [entries]
  (let [saved (count entries)
        failed (count (filter failed-entry? entries))
        loaded (- saved failed)]
    {:loaded loaded
     :failed failed
     :text (str saved " saved (" loaded " loaded"
                (when (pos? failed) (str ", " failed " failed")) ")")}))

(def ^:private result-ring-size
  "How many recent eval results a session keeps for (eval/result)."
  8)

(def ^:private result-read-default-chars
  "How much of a stored result (eval/result) returns by default. Kept
   below the tool's 16 KiB default so a map of escaped text still fits."
  6000)

(def ^:private result-read-max-chars
  "The most (eval/result) returns in one slice."
  50000)

(defn- result-head
  "The first line of a stored result, clipped for a result list."
  [content]
  (let [line (str/trim (or (first (str/split-lines content)) ""))]
    (if (> (count line) 100)
      (str (subs line 0 100) "…")
      line)))

(defn- store-result!
  "Keep RESULT in ENV's result ring and return its id. The content is the
   full pre-truncation body; the ring is bounded and oldest-first."
  [env result]
  (let [id (swap! (:result-next env) inc)]
    (swap! (:results env)
           (fn [results]
             (let [results (conj (vec results) (assoc result :id id))]
               (subvec results (max 0 (- (count results) result-ring-size))))))
    id))

(defn- result-list
  "The compact (eval/result) listing, newest first."
  [env]
  (->> @(:results env)
       reverse
       (mapv (fn [{:keys [id content status error? elapsed-ms chars lines truncated?]}]
               (cond-> {:id id :status status :chars chars :lines lines
                        :truncated? truncated? :head (result-head content)}
                 error? (assoc :error? true)
                 elapsed-ms (assoc :elapsed-ms elapsed-ms))))))

(defn- result-read
  "Read one stored result, or a slice of it. OPTS is {:offset N :limit N}."
  [env id & [opts]]
  (let [entry (first (filter #(= id (:id %)) @(:results env)))]
    (when-not entry
      (bad-request! (str "no eval result " id "; (result) lists them")
                    {:id id}))
    (let [content (:content entry)
          total (count content)
          offset (max 0 (long (or (:offset opts) 0)))
          limit (max 1 (min result-read-max-chars
                            (long (or (:limit opts) result-read-default-chars))))
          start (min offset total)
          end (min total (+ start limit))]
      (cond-> {:id id :status (:status entry) :chars total :offset start
               :content (subs content start end)}
        (:error? entry) (assoc :error? true)
        (:truncated? entry) (assoc :truncated? true)
        (< end total) (assoc :next-offset end)))))

(defn- latest-result-id
  "The newest stored result id, or nil when the ring is empty."
  [env]
  (:id (peek @(:results env))))

(defn- resolve-result-id
  "Resolve :last, -1, or \"last\" to ENV's newest result id."
  [env id]
  (if (or (= id :last)
          (= id -1)
          (and (string? id) (= "last" (str/lower-case id))))
    (latest-result-id env)
    id))

(defn- result-command
  "The (eval/result) command: no args lists; ID reads (:last, -1 or
   \"last\" reads the newest); the remaining args are an opts map or
   keyword pairs (:offset N :limit M). A call using (eval/result) is not
   stored back into the ring, so slicing a large result cannot evict it."
  [env & args]
  (reset! (:skip-result-store? env) true)
  (case (count args)
    0 (result-list env)
    1 (let [id (resolve-result-id env (first args))]
        (when (nil? id)
          (bad-request! "no eval results yet" {:id (first args)}))
        (result-read env id))
    (let [[raw-id & more] args
          id (resolve-result-id env raw-id)
          opts (cond
                 (and (= 1 (count more)) (map? (first more))) (first more)
                 (and (= 1 (count more)) (nil? (first more))) {}
                 (odd? (count more))
                 (bad-request! "result needs an opts map or keyword pairs"
                               {:args (vec args)})
                 :else (apply hash-map more))]
      (when (nil? id)
        (bad-request! "no eval results yet" {:id raw-id}))
      (result-read env id opts))))

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
           (str "; … " more " more; (library) for all"))
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
        shadow (when (false? (:active entry)) ", shadowed")
        failed (when (failed-entry? entry) ", failed")]
    (str "  - " (eval-format/display-name (:name entry)) " "
         (when kind (name kind))
         (eval-format/arglists-text arglists)
         " (" (scope-label scope) shadow failed ") — "
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
  {"core/read" {:name "core/read" :kind :verb
                :doc "Read PATH as text; OPTS may carry :offset and :limit (lines) — read a long file in ranges rather than slurping it whole."
                :arglists '([path] [path opts])}
   "core/write" {:name "core/write" :kind :verb
                 :doc "Write CONTENT to PATH whole, creating parents; success is silent."
                 :arglists '([path content])}
   "core/edit" {:name "core/edit" :kind :verb
                :doc "Replace the one occurrence of OLD-TEXT with NEW-TEXT in PATH; zero or several occurrences is an error."
                :arglists '([path old-text new-text])}
   "core/bash" {:name "core/bash" :kind :verb
                :doc "Run COMMAND through the bash tool and return its output with a status footer; a nonzero exit is a normal result. Batch independent commands with && or ; in one command. Pass {:timeout N} when a deadline is wanted."
                :arglists '([command] [command opts])}
   "core/emit" {:name "core/emit" :kind :verb
                :doc "Report VALUE as the call's result; without emit, a silent success reports its final value."
                :arglists '([value])}
   "core/emit-image" {:name "core/emit-image" :kind :verb
                      :doc "Attach an image file (PNG, JPEG, GIF or WebP) or a {:data :mime-type} map to the call."
                      :arglists '([x])}
   "core/cwd" {:name "core/cwd" :kind :verb
               :doc "The call's working directory."
               :arglists '([])}
   "core/env" {:name "core/env" :kind :verb
               :doc "The environment variable K, or nil."
               :arglists '([k])}
   "core/now" {:name "core/now" :kind :verb
               :doc "The current wall-clock time in milliseconds."
               :arglists '([])}
   "core/sleep" {:name "core/sleep" :kind :verb
                 :doc "Sleep MS milliseconds."
                 :arglists '([ms])}
   "core/spawn" {:name "core/spawn" :kind :verb
                 :doc "Run F on a daemon thread and return a derefable result; bindings do not cross threads."
                 :arglists '([f])}
   "core/session-recall" {:name "core/session-recall" :kind :verb
                          :doc "Recall this session's history: no args, a params map, a query string, or a query plus keyword options. Returns the recall text."
                          :arglists '([] [params] [query] [query & opts])}
   "clj/edit-form" {:name "clj/edit-form" :kind :verb
                    :doc "Apply a structured whole-form edit (same arguments as the clojure_edit tool)."
                    :arglists '([args])}
   "clj/replace-sexp" {:name "clj/replace-sexp" :kind :verb
                       :doc "Replace one form with another (same arguments as clojure_edit_replace_sexp)."
                       :arglists '([args])}
   "clj/paren-repair" {:name "clj/paren-repair" :kind :verb
                       :doc "Repair unbalanced delimiters (same arguments as clojure_paren_repair)."
                       :arglists '([args])}
   "clj/nrepl-eval" {:name "clj/nrepl-eval" :kind :verb
                     :doc "Evaluate Clojure code in a running nREPL server (same arguments as the clojure_eval tool)."
                     :arglists '([args])}
   "eval/library" {:name "eval/library" :kind :verb
                   :doc "List every loaded library definition with its scope; (help :library) also lists session-only definitions."
                   :arglists '([])}
   "eval/save" {:name "eval/save" :kind :verb
                :doc "Save a session-only definition, adding DOC when the live form has none; a defmethod uses its address string and saved multimethod."
                :arglists '([name] [name doc])}
   "eval/result" {:name "eval/result" :kind :verb
                  :doc "List recent eval results, or read one result or slice (ID may be :last; :offset N :limit M or a map); a call using it is not stored."
                  :arglists '([] [id] [id & opts])}
   "eval/sources" {:name "eval/sources" :kind :verb
                   :doc "List the mounted eval sources and their contributed verbs."
                   :arglists '([])}
   "eval/describe" {:name "eval/describe" :kind :verb
                    :doc "Describe one definition or source verb (also bare describe); KEY selects one field."
                    :arglists '([name] [name key])}
   "eval/forget" {:name "eval/forget" :kind :verb
                  :doc "Forget a saved definition or restore a source value."
                  :arglists '([name])}
   "eval/patch" {:name "eval/patch" :kind :verb
                 :doc "Replace one subform of a saved definition and keep it."
                 :arglists '([name old new])}
   "eval/edit-form" {:name "eval/edit-form" :kind :verb
                     :doc "Replace a saved definition with a whole new top-level form."
                     :arglists '([name new-source])}
   "eval/move" {:name "eval/move" :kind :verb
                :doc "Move a saved definition between the cwd and global scopes."
                :arglists '([name scope])}
   "eval/help" {:name "eval/help" :kind :verb
                :doc "The eval manual (also bare help): index, topic, or one definition."
                :arglists '([] [what])}})

(def ^:private bare-builtin-help
  "Built-in verbs keyed by their unqualified call surface."
  (into {} (map (fn [[name entry]]
                  [(eval-format/display-name name) entry]))
        builtin-verb-help))

(def ^:private system-namespaces
  #{"core" "clj" "eval" "clojure" "clojure.core"
    "babashka.process" "kmet.libs.http" "kmet.libs.json"
    "kmet.libs.markdown" "kmet.libs.yaml"})

(defn- name-namespace
  [name]
  (namespace (symbol (str name))))

(defn- system-namespace?
  "Is NAME in a platform-owned namespace? Those definitions are refused:
   user and other model namespaces are the only persisted surface."
  [name]
  (let [ns (name-namespace name)]
    (or (contains? system-namespaces ns)
        (str/starts-with? (str ns) "clojure.")
        (str/starts-with? (str ns) "babashka.")
        (str/starts-with? (str ns) "kmet.libs."))))

(defn- extension-name?
  "Is NAME in an extension-owned namespace? Those definitions stay live-only."
  [name]
  (str/starts-with? (str (name-namespace name)) "ext."))

(defn- describe-entry
  "The interface and implementation of RAW-NAME, resolved against the same
   names the rest of the eval vocabulary uses: a library definition, a
   source-provided key, or a live definition that was never written (a failed
   write or a call that ended in a normal error). A source key has no file and
   no scope; an unwritten definition has no authoritative file."
  [env raw-name]
  (let [raw-name (->name raw-name)
        name (when raw-name (qualify-name (env-ns env) raw-name))
        bare-builtin (when (and raw-name
                                (nil? (namespace (symbol raw-name))))
                       (get bare-builtin-help (eval-format/display-name raw-name)))]
    (when-not name
      (bad-request! (str "describe needs a definition name, got "
                         (pr-str raw-name))
                    {:name raw-name}))
    (let [unwritten? (contains? @(:unwritten env) name)
          unwritten-record (get @(:unwritten-records env) name)
          library-record (active-record env name)
          source-key (or (when (contains? @(:source-values env) name) name)
                         (when (contains? @(:source-values env) (str "ext." name))
                           (str "ext." name)))
          source-entry (get @(:source-values env) source-key)]
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
        (cond-> {:name name
                 :kind (:kind library-record)
                 :doc (:doc library-record)
                 :arglists (:arglists library-record)
                 :file (:file library-record)
                 :scope (record-scope library-record)
                 :source (:source library-record)}
          (failed-entry? library-record)
          (assoc :load-status :failed :load-error (:load-error library-record)))

        (contains? @(:active-source-keys env) source-key)
        {:name source-key
         :kind (if (:macro? source-entry) :macro :verb)
         :doc (:doc source-entry)
         :arglists (:arglists source-entry)
         :file nil
         :scope nil
         :source (:source source-entry)}

        :else
        (or (get builtin-verb-help name)
            bare-builtin
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

(defn- builtin-help-line
  []
  "Built-in verbs: core and eval verbs are unqualified, clj verbs keep their
   namespace. (help) lists each with its summary; (help 'name) shows its
   contract.")

(defn- verb-family
  [name]
  (case (namespace (symbol (str name)))
    "core" 0
    "clj" 1
    "eval" 2
    3))

(defn- first-sentence
  [text]
  (let [s (-> (str text) (str/replace #"\s+" " ") str/trim)
        i (str/index-of s ". ")]
    (if i (subs s 0 (inc i)) s)))

(def ^:private help-topic-keys
  "The built-in topics, in the order the index names them."
  [:core :library :persistence :results])

(defn- builtin-verb-lines
  []
  (map (fn [[name {:keys [doc arglists]}]]
         (str "  " (eval-format/display-name name)
              (or (eval-format/arglists-text arglists) "")
              " - " (first-sentence (or doc ""))))
       (sort-by (fn [[name _]] [(verb-family name) name]) builtin-verb-help)))

(defn- indent-text "TEXT with every line indented four spaces." [text] (str/join "\n" (map (fn [line] (str "    " line)) (str/split-lines (str text)))))

(defn- source-topic
  "SOURCE's id as a help-topic keyword, or nil."
  [{:keys [id]}]
  (let [topic (eval-sources/source-id-name id)]
    (when-not (str/blank? topic) (keyword topic))))

(defn- source-summary-line
  "One index line for SOURCE: its first sentence, verb count and the topic
   that expands the full description and verb catalog."
  [{:keys [error doc verbs] :as source}]
  (let [id (eval-format/source-id source :keyword)
        topic (source-topic source)
        n (count verbs)]
    (if error
      (str "- " id ": " error)
      (str "- " id ": " (first-sentence (or doc "(no description)"))
           " — " n " verb" (when (not= 1 n) "s")
           "; (help " topic ")"))))

(defn- source-help
  [env]
  (let [sources (clojure.core/deref (:source-info env))]
    (str "Eval sources:\n"
         (if (seq sources)
           (str/join "\n" (map source-summary-line sources))
           "  (none)"))))

(def ^:private eval-refer-prelude
  "The eval verbs referred unqualified into the working namespace."
  "(require '[eval :refer [library sources save forget patch edit-form move result]])")

(def ^:private reserved-source-aliases
  "Names the standard sandbox aliases and namespace roots already use."
  #{"fs" "str" "set" "edn" "walk" "json" "p" "tools"
    "clojure" "babashka" "kmet.libs" "core" "clj" "eval" "user"})

(defn- reserved-source-alias?
  "Does ALIAS name a standard sandbox alias, namespace or bundled library?"
  [alias]
  (or (contains? reserved-source-aliases alias)
      (str/starts-with? alias "clojure.")
      (str/starts-with? alias "babashka.")
      (str/starts-with? alias "kmet.libs.")
      (contains? #{"rewrite-clj" "cljfmt" "edamame" "parinferish"} alias)))

(defn- public-source-alias
  "SOURCE-ID's short alias when it is readable and not reserved."
  [id]
  (let [alias (eval-sources/source-id-name id)]
    (when (and (re-matches #"[A-Za-z_][A-Za-z0-9_.-]*" alias)
               (not (reserved-source-alias? alias)))
      alias)))

(defn- source-alias-prelude
  "The require forms that expose a mounted source's ext.<id> namespace as
   <id>/verb. Values keep their ext.<id> home; only the call surface is short."
  [env]
  (let [namespaces (->> (keys @(:source-values env))
                        (keep (fn [key]
                                (when-let [i (str/index-of key "/")]
                                  (subs key 0 i))))
                        set)]
    (->> @(:source-info env)
         (keep (fn [{:keys [id]}]
                 (when-let [alias (public-source-alias id)]
                   (let [namespace (str "ext." alias)]
                     (when (contains? namespaces namespace)
                       (str "(require '[" namespace " :as " alias "])"))))))
         (str/join "\n"))))

(defn- eval-prelude
  "The standard aliases, the eval verbs, and one alias per mounted source."
  [env]
  (str script/alias-prelude
       "\n" eval-refer-prelude
       "\n" (source-alias-prelude env)))

(defn- scope-help [env] (str "Scope: cwd " (get-in env [:roots :cwd]) " (shadows the global layer)" "\n       global " (get-in env [:roots :global]) "\nNew definitions write to cwd; (move 'name :global) moves a saved " "definition to the global layer. A redefinition keeps the active layer; a failed file is repaired in place."))

(defn- library-help
  [env]
  (let [entries (library-entries env)
        session-only (session-only-entries env)
        {:keys [loaded failed]} (library-counts entries)]
    (str "Library definitions: " (count entries)
         " (" loaded " loaded"
         (when (pos? failed) (str ", " failed " failed")) ")"
         (when (seq entries)
           (str "\n" (str/join "\n" (map definition-line entries))))
         "\nSession-only definitions (live, not saved): " (count session-only)
         (when (seq session-only)
           (str "\n" (str/join "\n" (map definition-line session-only))))
         "\n" (scope-help env))))

(defn- persistence-help
  []
  (str "Persistence: eval creates verbs. A top-level definition with a non-blank "
       "docstring is written to the library and loads in later sessions; a new "
       "definition without one stays live for this session only and is not saved. "
       "Add a docstring and re-evaluate, or use (save 'name \"doc\"), to keep it; "
       "a defmethod is saved with its loaded multimethod. A redefinition of a saved "
       "definition with a docstring is written automatically to the file that "
       "defines it, keeping that file's scope; a failed file is still the file for "
       "its names, so redefining repairs it. A saved definition redefined without a "
       "docstring is not written; the saved file is unchanged. A call that ends in a "
       "normal error writes nothing; its definitions stay live and can be forgotten. "
       "System namespaces (core, clj, eval) are read-only; ext.* definitions stay "
       "live-only. (forget 'name) drops a saved definition (including a failed "
       "one) and (move 'name :global|:cwd) moves one, replacing a failed target; "
       "both resolve unqualified names in the namespace the call ends in, and a "
       "file that also defines other addresses must be dropped with all of them. The "
       "prompt previews saved definitions with loaded and failed counts; the first "
       "eval reports any loaded definitions and files that failed. A result line "
       "names the definition and scope; (describe 'name :file) names its file."))

(defn- results-help
  []
  (str "Results: (emit value) appends curated output; a silent successful "
       "call reports its final value instead. stdout and stderr are discarded. "
       "The result is bounded at 16 KiB / 2,000 lines by default; "
       ":max-output-bytes raises it to 50 KiB. A truncated result keeps its "
       "tail and names its result id. A multi-form call reports one bounded "
       "line per form; use (emit value) for a full value. The session's "
       "recent-result ring keeps each eval call's output/error body: (result) lists "
       "them, (result :last) reads the newest, (result ID) reads the "
       "first 6,000 characters, and (result ID {:offset N :limit M}) "
       "reads a slice. A call using result is not stored back. The ring holds "
       "the script body, not the final [eval: ...] change report or error status. Errors begin "
       "with ERROR and, in a multi-form call, name the failing form and what "
       "ran. (emit-image path) attaches an image."))

(defn- core-help
  []
  (str "Core: Clojure/SCI — " script/core-summary
       "\n" (builtin-help-line)
       "\nUnqualified read is the file reader; clojure.core/read remains "
       "available. Read a long file in ranges with "
       "(read path {:offset N :limit N}) instead of slurping it whole."
       "\nShell: (bash \"cmd\") returns output; a nonzero exit is a normal "
       "result. Batch shell work in one command: (bash \"cat r.txt && cd /tmp "
       "&& bb run st\"). Batch reads with several forms in one call."
       "\nLocal let/fn bindings are scratch; every call starts in user."
       "\nOwnership: define in user; core, clj and eval are read-only; "
       "ext.* is live-only."
       "\nEval verbs: (library), (sources), (describe 'name), "
       "(describe 'name key), (forget 'name), (save 'name [doc]), "
       "(patch 'name old new), (edit-form 'name new-source), "
       "(move 'name :global|:cwd), (result [id opts])."
       "\nDiscovery: (all-ns) lists namespaces; (ns-publics 'ns) lists a "
       "namespace's vars, including session-only definitions. A patch or "
       "edit-form result must keep a docstring."
       "\nReporting: (emit value) or end on the final value; stdout and stderr "
       "are discarded; (help :results) has the result contract."))

(defn- doc-field
  "The help detail's doc field: one line when DOC is one line, otherwise the
   whole docstring indented under `doc:`."
  [doc]
  (let [doc (str/trim (str (or doc "")))]
    (cond
      (str/blank? doc) "  doc: (no docstring)"
      (str/includes? doc "\n") (str "  doc:\n" (indent-text doc))
      :else (str "  doc: " doc))))

(defn- inspect-hint
  "Where to read ENTRY-NAME's defining form or file, when it has either."
  [entry-name source file]
  (when (or (seq (str source)) file)
    (str "\n  inspect: (describe '" entry-name " :source) or "
         "(describe '" entry-name " :file).")))

(defn- help-entry-text
  [env entry]
  (let [{:keys [kind doc arglists file scope source load-status load-error]
         entry-name :name} entry
        arg (eval-format/arglists-text arglists)
        display-name (eval-format/display-name entry-name)
        inspect-name (if (and (str/starts-with? entry-name "user/")
                              (not= "user" (env-ns env)))
                       entry-name
                       display-name)
        scope-label (cond
                      scope (str "(" (name scope) ")")
                      (str/blank? (str source)) "(built-in)"
                      :else "(source)")]
    (str display-name " " (when kind (name kind)) (or arg "") " " scope-label
         "\n" (doc-field doc)
         (when (= :failed load-status)
           (str "\n  status: failed"
                (when load-error (str ": " (truncate-report-error load-error)))))
         (inspect-hint inspect-name source file))))

(defn- source-topics
  "The mounted sources' ids as help-topic keywords, sorted."
  [env]
  (->> @(:source-info env) (keep source-topic) sort))

(defn- source-for-topic
  "The mounted source whose id names TOPIC, or nil."
  [env topic]
  (some #(when (= topic (source-topic %)) %) @(:source-info env)))

(defn- topic-for
  "The topic STRING names, case-insensitively, or nil. A string is a topic,
   never a definition name."
  [env string]
  (let [w (-> string str/trim str/lower-case)]
    (some (fn [topic]
            (when (= w (str/lower-case (name topic)))
              topic))
          (concat help-topic-keys (source-topics env)))))

(defn- help-topic-text
  [env topic]
  (case topic
    :library (library-help env)
    :persistence (persistence-help)
    :results (results-help)
    :core (core-help)
    (if-let [source (source-for-topic env topic)]
      (str "Eval source " (name topic) ":\n"
           (eval-format/source-line source))
      (bad-request! (str "unknown eval help topic " (pr-str topic)
                         "; use " (str/join ", " (map str help-topic-keys))
                         ", or a mounted source id")
                    {:topic topic}))))

(defn- help-index
  [env]
  (let [entries (library-entries env)
        session-only (session-only-entries env)
        topics (source-topics env)]
    (str "Eval help: (help) index, (help :topic) or (help \"topic\") a topic, "
         "or (help 'name) one verb or definition."
         "\nTopics: :core sandbox, :library definitions and scopes, "
         ":persistence save/drop, :results output"
         (when (seq topics)
           (str ", source topics " (str/join ", " (map str topics))))
         "."
         "\n" (source-help env)
         "\nBuilt-in verbs:\n" (str/join "\n" (builtin-verb-lines))
         "\nLibrary: " (:text (library-counts entries))
         ", " (count session-only) " session-only"
         "; (help :library) lists both and the scope roots."
         "\n(help 'name) describes one definition or verb.")))

(defn- help-or-index
  "Run F; a bad help request returns MISSING plus the index instead of an
   error, so a typo costs one call rather than two."
  [env missing f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if (= :eval/bad-request (:type (ex-data e)))
        (str missing "; the index:\n" (help-index env))
        (throw e)))))

(defn- environment-help
  "The `(eval/help)` text: an index, a topic, or one definition's interface.
   A keyword or string names a topic; a symbol names a definition. `help` and
   `describe` are the bare aliases."
  ([env] (help-index env))
  ([env what]
   (cond
     (nil? what) (help-index env)
     (keyword? what)
     (help-or-index env (str "No help for topic " (pr-str what))
                    #(help-topic-text env what))
     (string? what)
     (if-let [topic (topic-for env what)]
       (help-topic-text env topic)
       (str "No help for topic " (pr-str what) "; the index:\n"
            (help-index env)))
     (symbol? what)
     (help-or-index env (str "No help for " (pr-str what))
                    #(help-entry-text env (describe-entry env what)))
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
                      "\n" (eval-prelude env) "\n" body)]
      (rearm-build! env deadline)
      (let [result (script/eval-source! (:sci-ctx env) source (atom nil))]
        (if (:error result)
          {:error (:error result) :parsed parsed}
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
  "Read, parse and evaluate one library file into ENV at SCOPE. A read or parse
   failure has no address and is recorded and skipped; an evaluation failure is
   also indexed under the file's parsed addresses with `:load-status :failed`,
   so a redefinition keeps the file's scope and repairs it."
  [env scope deadline {:keys [file content error]}]
  (if error
    (record-failure! (:failures env) {:file file :error error :scope scope})
    (let [{:keys [parsed error]} (eval-content! env content deadline)
          base {:file file :scope scope :root (root-for env scope)}]
      (when error
        (record-failure! (:failures env) {:file file :error error :scope scope}))
      (doseq [record (:defs parsed)]
        (add-record! env (cond-> (merge record base)
                           error (assoc :load-status :failed
                                        :load-error error)))))))

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
   (:sci-ctx env) (str (eval-prelude env) "\n" (:source record))
   (atom (or (sci/find-ns (:sci-ctx env) (symbol (:ns record)))
             (sci/create-ns (symbol (:ns record)))))))

(defn- commit-definition!
  "Re-evaluate PATCHED-RECORD, write PATCHED to PATH, and update the library.
   A failed evaluation or write restores RECORD in the live environment and
   leaves the old file authoritative; LABEL names the operation in errors.
   VERB is the report line's past-tense verb."
  [env {:keys [name record path patched patched-record label verb]}]
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
                    ")]"))
        nil))))

(defn- patch-definition!
  "Replace the first subform structurally equal to OLD with NEW inside NAME's
   own definition form, re-evaluate the definition, then write the whole file.
   The definition name and namespace must stay. A source-provided key or an
   unwritten live definition has no authoritative file and is refused; a
   failed re-evaluation restores the old live definition and writes nothing."
  [env raw-name old new scope]
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
    (commit-definition! env {:name name
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
  [env raw-name new-source scope]
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
    (commit-definition! env {:name name
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

(defn- drop-failed-target!
  "Delete TARGET-RECORD's file and library entry so a move can replace it.
   Refuses when the file also defines other addresses."
  [env name record]
  (ensure-no-siblings! name (file-siblings env record))
  (let [root (root-for env (record-scope record))
        path (or (:file record) (eval-files/definition-path root record))]
    (eval-files/delete-file! root path)
    (remove-record! env name record)))

(defn- move-record-line!
  "Move RAW-NAME's active definition to TARGET scope without re-evaluating.
   The live name stays; only the file and the library record move. Refuses a
   source key, an unwritten definition, a file that also defines another
   address, a same-scope move, and a target layer that already has the name
   unless that target is a failed record, which is replaced. Returns the
   report line."
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
      (let [current (record-scope record)
            target-record (some #(when (= target (record-scope %)) %)
                                (stack-for env name))
            replace? (and target-record (failed-entry? target-record))]
        (check-move! name current target (file-siblings env record)
                     (and target-record (not replace?)))
        (when replace?
          (drop-failed-target! env name target-record))
        (let [path (eval-files/move-definition! (:roots env) record target)]
          (put-record! env name current
                       (assoc record :file path :scope target
                              :root (root-for env target)))
          (str "[eval: moved " name " (" (scope-label current) " → " (scope-label target) ")]"))))))

(defn- prime-state
  "A prepared state for evaluating library definitions at rebuild: no tool
   surface is installed, and a discarded emit channel holds until a real
   call binds its state."
  [cwd source-namespaces]
  (script/prepare {:tool "eval"
                   :cwd cwd
                   :source-namespaces source-namespaces
                   :tools? false}))

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

(declare save-definition!)

(defn- build-environment
  "Build a fresh environment: the SCI context, the library index, and every
   library definition evaluated into it. SIGNAL is the first call's cancel
   signal, passed to the sources so a source value captured at build time
   (and reused by that first call) still observes cancellation."
  [service cwd signal]
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
             ;; the ids the result renderer last reported; the eval-source
             ;; line appears only when this set changes
             :reported-source-ids (atom nil)
             :fresh-sources? (atom true)
             :change-lines (atom [])
             :unwritten (atom #{})
             :unwritten-records (atom {})
             :build-report (atom nil)
             :forgotten (atom #{})
             :call-scope (atom nil)
             :call-ns (atom nil)
             :results (atom [])
             :result-next (atom 0)
             :skip-result-store? (atom false)
             :lock (Object.)}
        interrupt (script/interrupt-holder)
        env-ref (atom nil)
        library-fn (fn [] (library-entries @env-ref))
        forget-fn (fn [name] (request-forget! @env-ref name))
        sources-fn (fn [] @(:source-info @env-ref))
        help-fn (fn [& [what]] (environment-help @env-ref what))
        describe-fn (fn
                      ([name] (describe @env-ref name))
                      ([name key] (describe @env-ref name key)))
        save-fn (fn [name & [doc]] (save-definition! @env-ref name doc))
        result-fn (fn [& args] (apply result-command @env-ref args))
        sci-ctx (script/context
                  (assoc (update (script/base-namespaces) 'clojure.core merge
                                 {'help help-fn 'describe describe-fn})
                         'clj {'nrepl-eval
                               (fn [args]
                                 (clj-eval/evaluate
                                  (assoc args :cwd (or (:cwd script/*call-state*)
                                                       cwd))
                                  (:cancelled script/*call-state*)))}
                         'eval {'library library-fn
                                'forget forget-fn
                                'save save-fn
                                'result result-fn
                                'patch (fn [name old new]
                                         (patch-definition! @env-ref name old new
                                                            @(:call-scope @env-ref)))
                                'edit-form (fn [name new-source]
                                             (edit-form-definition! @env-ref
                                                                    name new-source
                                                                    @(:call-scope @env-ref)))
                                'move (fn [name scope]
                                        (let [line (move-record-line! @env-ref name scope)]
                                          (swap! (:change-lines @env-ref) conj line)
                                          nil))
                                'describe describe-fn
                                'sources sources-fn
                                'help help-fn})
                  (script/interrupt-fn interrupt "eval")
                  "eval")
        env (assoc env :sci-ctx sci-ctx :interrupt interrupt)
        _ (reset! env-ref env)
        sources (refresh-sources! service env cwd signal)
        deadline (+ (concurrent/monotonic-ms) build-budget-ms)]
    (binding [tool-util/*cwd* cwd
              bash-tool/*shell* bash-tool/*shell*
              bash-tool/*session-env-fn* bash-tool/*session-env-fn*
              read-tool/*skill-resolver* read-tool/*skill-resolver*]
      (script/with-prime-context
        sci-ctx (prime-state cwd (:namespaces sources))
        (fn []
          (read-library! env (:global roots) :global deadline)
          (read-library! env (:cwd roots) :cwd deadline)
          (swap! (:model-shadows env)
                 into
                 (->> (library-entries env)
                      (remove failed-entry?)
                      (map :name)
                      (filter #(contains? @(:source-keys env) %))))
          (reset! (:build-report env)
                  {:entries (filterv #(not (failed-entry? %))
                                     (library-entries env))
                   :failures (vec @(:failures env))}))))
    env))

(defn- ensure-environment!
  "The current environment for SESSION, built from the library on first use.
   An existing environment is reused until an explicit `/eval reload`: a session
   is a runtime cache, not a branch-scoped definition log."
  [service session cwd signal]
  (let [id (:id session)]
    (or (get @(:envs service) id)
        (let [env (build-environment service cwd signal)]
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
  (let [fresh (build-environment service (:cwd old) nil)
        build-failures (vec @(:failures fresh))]
    (if (seq build-failures)
      {:env old :kept? true :failures build-failures}
      (let [replay-failures (replay-live! fresh old)]
        (if (seq replay-failures)
          {:env old :kept? true :failures replay-failures}
          (do
            (reset! (:build-report fresh) nil)
            ;; the reload had no call signal; let the next eval call re-read
            ;; sources with its own
            (reset! (:fresh-sources? fresh) false)
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

(defn- apply-definition! "Write RECORD to its scope's library and index it. A source-provided key\n   stays live but is never written, and is remembered as a model shadow so\n   later source reads do not overwrite it. A definition in an extension\n   namespace is live-only for the same reason. An undocumented definition\n   stays live for the session and is not written, so a saved definition\n   redefined without a docstring keeps its saved file unchanged. A defmethod\n   of a loaded multimethod is written with it. A failed write is recorded and\n   reported; the live definition remains." [env record] (let [name (:name record) scope (or (:scope record) :cwd) record (assoc record :scope scope)] (cond (contains? (clojure.core/deref (:source-keys env)) name) (do (swap! (:model-shadows env) conj name) {:line (str "[eval: redefined " name " (source; not stored)]")}) (extension-name? name) (do (swap! (:model-shadows env) conj name) (swap! (:unwritten env) conj name) (swap! (:unwritten-records env) assoc name record) {:line (str "[eval: defined " name " (extension; live-only)]"), :entry (assoc (library-entry record) :active true)}) (not (documented? env record)) (let [saved? (some? (existing-record env name scope))] (swap! (:unwritten env) conj name) (swap! (:unwritten-records env) assoc name record) {:line (live-only-line record scope saved?), :entry (assoc (library-entry record) :active true)}) :else (try (let [root (root-for env scope) [path record] (write-redefinition! env root record) record (assoc record :file path :root root)] (add-record! env record) (clear-unwritten! env name) {:line (str "[eval: defined " name " (" (scope-label scope) ")]"), :entry (assoc (library-entry record) :active true)}) (catch Throwable t (swap! (:unwritten env) conj name) (swap! (:unwritten-records env) assoc name record) (record-failure! (:failures env) {:name name, :error (ex-message t), :scope scope}) {:line (str "[eval: define failed " name " (" (scope-label scope) "): " (ex-message t) "]"), :error (ex-message t)})))))

(defn- save-definition!
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
    (cond
      (contains? @(:source-keys env) name)
      (bad-request! (str name " is provided by a source; source definitions stay live-only")
                    {:name name})

      (extension-name? name)
      (bad-request! (str name " is an extension definition; extensions stay live-only")
                    {:name name})

      :else
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

          (and (not method?)
               (not (defs/docstring-kind? (:kind record))))
          (bad-request! (str name " is a " (name (:kind record))
                             "; that form has no docstring slot")
                        {:name name})

          :else
          (let [doc (when-not method?
                      (or (some-> doc str/trim not-empty)
                          (some-> (:doc record) str/trim not-empty)))]
            (when (and (not method?) (not doc))
              (bad-request! (str "save needs a docstring for " name)
                            {:name name}))
            (let [source (if (and (not method?) (str/blank? (:doc record)))
                           (or (defs/add-docstring (:source record) doc)
                               (bad-request! (str "cannot add a docstring to " name)
                                             {:name name}))
                           (:source record))
                  record (if method?
                           record
                           (assoc record :doc doc :source source))
                  result (apply-definition! env record)]
              (if-let [error (:error result)]
                (bad-request! (str "save failed: " error) {:name name})
                (do (swap! (:change-lines env) conj (:line result))
                    nil)))))))))

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
  [env records drop forgotten scope]
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
        (let [{:keys [line entry error]} (apply-definition! env record)]
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

(defn- try-parse-code [code _env]
  (try
    {:parsed (defs/parse code "user")}
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
  [env records drop forgotten scope base started]
  (let [base (base-result base started)
        report (take-build-report! env)
        changes (apply-changes! env records drop forgotten scope)
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
  [service env cwd {:keys [code timeout limits signal source-signal abort
                           on-update ctx drop scope session-recall]}]
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
            _ (when-let [bad (first (filter #(system-namespace? (:name %)) records))]
                (bad-request! (str "eval " (:name bad)
                                   " is system-owned; define it in user or a model namespace")
                              {:name (:name bad)}))
            _ (reset! (:skip-result-store? env) false)
            _ (reset! (:call-ns env) (user-ns env))
            codemode (fn []
                       (let [sources (if (and (compare-and-set! (:fresh-sources? env) true false)
                                              (= @(:source-ids env)
                                                 (mapv :id (source-registrations service))))
                                       (current-source-seed env)
                                       (refresh-sources! service env cwd source-signal))
                             [extra-details ids] (source-result-details env sources)
                             result (script/run {:tool "eval"
                                                 :code code
                                                 :timeout timeout
                                                 :limits limits
                                                 :signal signal
                                                 :abort abort
                                                 :ctx ctx
                                                 :cwd cwd
                                                 :on-update on-update
                                                 :sci-ctx (:sci-ctx env)
                                                 :interrupt (:interrupt env)
                                                 :prelude (eval-prelude env)
                                                 :ns-atom (:call-ns env)
                                                 :split-forms? true
                                                 :result-store (fn [result]
                                                                 (when-not @(:skip-result-store? env)
                                                                   (store-result! env result)))
                                                 :source-namespaces (:namespaces sources)
                                                 :session-recall session-recall
                                                 :extra-details extra-details
                                                 :tools? false})]
                         (reset! (:reported-source-ids env) ids)
                         result))]
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
                    (finish-call! env records drop
                                  @(:forgotten env) scope result started)))
                (finally
                  (reset! (:call-scope env) nil)
                  (reset! (:call-ns env) (user-ns env)))))))))))

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
        (let [env (ensure-environment! service session (or cwd (tool-util/cwd)) nil)
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
                                   (or cwd (tool-util/cwd)) nil)
                 ;; the drop had no call signal; let the next eval call
                 ;; re-read sources with its own
                 (reset! (:fresh-sources? env) false))
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
   environment and never invokes a source; a source contributes only its
   registration-time `:description`. The disk view is cached per cwd until the
   files' paths, sizes or modification times change."
  [service {:keys [session cwd]}]
  (let [session (or session (extensions/get-session))
        env (when session (get @(:envs service) (:id session)))
        sources (mapv #(select-keys % [:id :description])
                      (source-registrations service))]
    (if env
      {:loaded? true
       :entries (library-entries env)
       :session-only (session-only-entries env)
       :sources sources}
      (let [cwd (or cwd (tool-util/cwd))
            roots (roots-for service cwd)
            fingerprint (eval-files/fingerprint roots)
            cached (get @(:manifest-cache service) cwd)
            manifest (if (= fingerprint (:fingerprint cached))
                       (:manifest cached)
                       (let [{:keys [entries]} (eval-files/index roots)
                             manifest {:loaded? false :entries entries}]
                         (swap! (:manifest-cache service) assoc cwd
                                {:fingerprint fingerprint :manifest manifest})
                         manifest))]
        (assoc manifest :sources sources)))))

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
          ;; the eval timeout aborts the script through ABORT; sources and
          ;; extension verbs see the same signal the script's host calls do
          (let [abort (atom nil)
                source-signal (concurrent/or-signal (:signal opts) abort)
                env (ensure-environment! service session cwd source-signal)]
            (eval!* service env cwd (assoc opts :abort abort
                                           :source-signal source-signal))))))))

(defn value
  "The `:eval` service for AGENT-CTX. The environments are keyed by session
   and owned here. The sandbox has no `tools` namespace: an eval call cannot
   invoke any tool.

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
