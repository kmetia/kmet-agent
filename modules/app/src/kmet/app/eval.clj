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
            [kmet.app.extensions.core :as extensions]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.script :as script]
            [kmet.app.tools.util :as tool-util]
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

(defn- load-library-file!
  "Read, parse and evaluate one library file into ENV. The original ns form
   runs first (preserving its requires), then the standard aliases, then the
   rest of the file, so aliases are not reset by the ns form. A read, parse or
   eval failure is recorded and skipped."
  [env ns-atom {:keys [file content error]}]
  (if error
    (record-failure! (:failures env) {:file file :error error})
    (let [parsed (try
                   (defs/parse content "user")
                   (catch Throwable t
                     (record-failure! (:failures env)
                                      {:file file :error (ex-message t)})
                     nil))]
      (when parsed
        (let [{:keys [ns-form body]} (defs/split-ns content)
              source (str (or ns-form (str "(ns " (:ns parsed) ")"))
                          "\n" script/alias-prelude "\n" body)]
          (rearm-build! env)
          (let [result (script/eval-source! (:sci-ctx env) source ns-atom)]
            (if (:error result)
              (record-failure! (:failures env)
                               {:file file :error (:error result)})
              (doseq [record (:defs parsed)]
                (swap! (:library env) assoc (:name record)
                       (assoc record :file file))))))))))

(defn- read-library!
  "Read and evaluate every definition file in ROOT into ENV, sorted by path."
  [env root]
  (let [ns-atom (atom nil)]
    (doseq [file (eval-files/scan root)]
      (load-library-file! env ns-atom file))
    (reset! ns-atom nil)))

(defn- prime-state
  "A prepared state for evaluating library definitions at rebuild: the current
   tool surface is installed so a definition that names a tool var compiles,
   with an inert dispatcher and a discarded emit channel until a real call
   binds its state."
  [service cwd]
  (let [handle (srce/get (:agent-ctx service) :tools)]
    (script/prepare {:tool "eval"
                     :cwd cwd
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
             :parsed-names (atom #{})
             :unwritten (atom #{})
             :restore-source (atom nil)
             :forgotten (atom #{})
             :sticky-ns (atom nil)
             :lock (Object.)}
        interrupt (script/interrupt-holder)
        library-fn (fn [] (library-entries env))
        forget-fn (fn [name] (request-forget! env name))
        sci-ctx (script/context
                  (assoc (script/base-namespaces)
                         'eval {'library library-fn
                                'forget forget-fn})
                  (script/interrupt-fn interrupt "eval")
                  "eval")
        env (assoc env :sci-ctx sci-ctx :interrupt interrupt)]
    (script/with-prime-context
      sci-ctx (prime-state service cwd)
      (fn []
        (read-library! env root)))
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
   written, so drop/forget can unbind them."
  [env parsed]
  (doseq [record (defs/collapse (:defs parsed))
          :when (not (get @(:library env) (:name record)))
          :when (interned? env record)]
    (swap! (:unwritten env) conj (:name record))))

(defn- drop-one!
  "Delete NAME's library file and unbind its live var. A source-provided key
   has no file; its source seed is restored when the source layer provides
   one."
  [env name]
  (let [record (get @(:library env) name)
        source? (contains? @(:source-keys env) name)
        live? (and (not source?) (interned? env (or record {:name name})))
        file (when-not source?
               (eval-files/delete-name! (:root env) name))
        error (when live? (unbind! env name record))]
    (swap! (:library env) dissoc name)
    (swap! (:unwritten env) disj name)
    (when source?
      (when-let [restore @(:restore-source env)] (restore name)))
    {:name name :file file :error error}))

(defn- apply-definition!
  "Write RECORD to the library and index it. A source-provided key stays live
   but is never written. A failed write is recorded and reported; the live
   definition remains."
  [env cwd record]
  (let [name (:name record)]
    (if (contains? @(:source-keys env) name)
      {:line (str "[eval: redefined " name " (source; not stored)]")}
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
        lines (atom [])
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
                                                        {:type :eval/no-tools})))]
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
   and, when an environment exists, unbind the live names. Returns the
   tool/command result map, or throws (the caller reports)."
  [service session names]
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
            root (library-root service)
            names (mapv #(qualify-name (or (some-> env env-ns) "user") %)
                        (normalize-drop names))
            known? (if env
                     #(known-name? env %)
                     #(fs/exists? (eval-files/path-for-name root %)))]
        (validate-drop! known? names)
        (let [forgotten (atom [])
              failures (atom [])]
          (doseq [name names]
            (try
              (let [error (if env
                            (:error (drop-one! env name))
                            (do (eval-files/delete-name! root name) nil))]
                (swap! forgotten conj name)
                (when error
                  (swap! failures conj {:name name :error error})))
              (catch Throwable t
                (swap! failures conj {:name name :error (ex-message t)}))))
          (let [failures @failures]
            {:content (str/join "\n"
                                (concat (map #(str "[eval: forgot " % "]")
                                             @forgotten)
                                        (map #(str "[eval: failed " (:name %) ": "
                                                   (:error %) "]")
                                             failures)))
             :details (cond-> {:forgotten @forgotten
                               :elapsed-ms (- (concurrent/monotonic-ms) started)}
                        (seq failures) (assoc :failures failures))
             :is-error (boolean (seq failures))}))))))

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
      (drop-request! service session (:drop opts))
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
      :drop! (fn [{:keys [session drop]}]
               (drop-request! service session drop))
      :library #(service-library service)
      :index #(service-index service)
      :sources (constantly [])
      :source-commands (constantly {})
      :failures #(service-failures service)
      :dispose! (fn []
                  (reset! disposed? true)
                  (reset! envs {})
                  (reset! session-locks {})
                  (reset! failures [])
                  nil)})))
