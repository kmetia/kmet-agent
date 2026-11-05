(ns kmet.app.eval
  "The `:eval` service: one persistent SCI environment per agent session.

   An environment is built lazily on a session's first eval from the shared
   project/global definition files and the session's `:eval-definitions`
   entries, and is reused while the active branch's definition entries are
   unchanged. A branch, reset, switch or compaction snapshot invalidates it;
   the next eval rebuilds from the active branch. One lock per environment
   serializes parallel calls, and the deadline clock starts after it is taken.

   Definitions made by a call are session-private: each successful call that
   changes the set appends one `:eval-definitions` entry. `:keep` promotes a
   definition to the project/global files (or demotes it back to the session),
   `:drop` removes every copy, and a shared name redefined without `:keep`
   becomes a session shadow. A broken shared file or stored entry is recorded
   under `:failures` and skipped, never fatal.

   `kmet.app.tools.eval` owns the model-facing tool; `kmet.app.eval.files`
   owns the reviewable files and `kmet.app.eval.definitions` the pure parse
   and fold."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.config :as cfg]
            [kmet.app.debug :as debug]
            [kmet.app.eval.definitions :as defs]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.session.core :as session]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.script :as script]
            [kmet.app.tools.util :as tool-util]
            [kmet.libs.concurrent :as concurrent]
            [kmet.srce.core :as srce]
            [sci.core :as sci]))

;; ─── Session and branch ───────────────────────────────────────────────────

(defn- resolve-session [session]
  (or session (extensions/get-session)))

(defn- definition-entries [session]
  (filter #(= defs/eval-definitions-type (:custom-type %))
          (session/get-branch session)))

(defn- fingerprint [session]
  (defs/definition-ids (session/get-branch session)))

;; ─── Roots ────────────────────────────────────────────────────────────────

(defn- eval-roots
  "The two shared scopes for CWD. Loading order is global then project;
   project wins a name collision."
  [agent-dir cwd]
  {:global {:root (str (fs/path agent-dir "eval")) :scope :global}
   :project {:root (str (fs/path cwd ".kmet" "eval")) :scope :project}})

(defn- root-for [roots scope]
  (get-in roots [scope :root]))

(defn- display [cwd path]
  (eval-files/display-path cwd path))

;; ─── Kept index ───────────────────────────────────────────────────────────

(defn- scope-rank [scope]
  (case scope :session 0 :project 1 :global 2 3))

(defn- session-kept
  "A session definition; when a shared file has the same name, the shared
   entry it shadows."
  [shared record]
  (cond-> {:name (:name record)
           :scope :session
           :ns (:ns record)
           :kind (:kind record)}
    (get shared (:name record))
    (assoc :shadowing (select-keys (get shared (:name record)) [:scope :file]))))

(defn- shared-kept [record]
  {:name (:name record)
   :scope (:scope record)
   :ns (:ns record)
   :kind (:kind record)
   :file (:file record)})

(defn- kept-entries
  "The service's `(eval/kept)` value: every shared file and every session
   definition, a session entry carrying the shared file it shadows."
  [env]
  (let [shared @(:shared env)]
    (->> (concat (map #(session-kept shared %) (vals @(:session-defs env)))
                 (map shared-kept (vals shared)))
         (sort-by (juxt :name scope-rank))
         vec)))

(defn- tracked
  "The effective definition for NAME: the session copy wins a shared file."
  [env name]
  (or (get @(:session-defs env) name)
      (get @(:shared env) name)))

(defn- effective-failures [service env]
  (into (vec @(:service-failures service))
        (when env @(:failures env))))

;; ─── Environment build ────────────────────────────────────────────────────

(defn- record-failure! [failures failure]
  (swap! failures conj failure))

(defn- load-shared-file!
  "Read, parse and evaluate one shared file into ENV. A read, parse or eval
   failure is recorded and skipped."
  [env ns-atom {:keys [file scope content error]}]
  (if error
    (record-failure! (:failures env)
                     {:file file :scope scope :error error})
    (let [parsed (try
                   (defs/parse content "user")
                   (catch Throwable t
                     (record-failure! (:failures env)
                                      {:file file :scope scope :error (ex-message t)})
                     nil))]
      (when parsed
        (let [result (script/eval-source! (:sci-ctx env) content ns-atom)]
          (if (:error result)
            (record-failure! (:failures env)
                             {:file file :scope scope :error (:error result)})
            (doseq [record (:defs parsed)]
              (swap! (:shared env) assoc (:name record)
                     (assoc record :scope scope :file file)))))))))

(defn- read-shared!
  "Read and evaluate the shared definition files into ENV."
  [env roots]
  (let [ns-atom (atom nil)]
    (doseq [file (eval-files/scan [(:global roots) (:project roots)])]
      (load-shared-file! env ns-atom file))
    (reset! ns-atom nil)))

(defn- load-session-definitions!
  "Fold the active branch and evaluate its definitions into ENV. A definition
   that throws is recorded and skipped; the rest still load."
  [env session]
  (let [folded (defs/fold-entries (definition-entries session))
        ns-atom (atom nil)]
    (doseq [failure (:failures folded)]
      (record-failure! (:failures env) failure))
    (doseq [record (:defs folded)]
      (let [source (str "(ns " (:ns record) ")\n" (:source record) "\n")
            result (script/eval-source! (:sci-ctx env) source ns-atom)]
        (if (:error result)
          (record-failure! (:failures env)
                           {:name (:name record) :error (:error result)})
          (swap! (:session-defs env) assoc (:name record) record))))
    (reset! ns-atom nil)))

(defn- build-environment
  "Build a fresh environment for SESSION: the SCI context, the kept index, and
   the shared and session definitions evaluated into it."
  [service session cwd]
  (let [roots (eval-roots (:agent-dir service) cwd)
        failures (atom [])
        shared (atom {})
        session-defs (atom {})
        env {:session session
             :session-id (:id session)
             :session-file (:file session)
             :failures failures
             :shared shared
             :session-defs session-defs
             :sticky-ns (atom nil)
             :built-entries (atom nil)
             :lock (Object.)}
        interrupt (script/interrupt-holder)
        kept-fn (fn [] (kept-entries env))
        sci-ctx (script/context
                  (assoc (script/base-namespaces)
                         'eval {'kept kept-fn})
                  (script/interrupt-fn interrupt "eval")
                  "eval")
        env (assoc env :sci-ctx sci-ctx :interrupt interrupt)]
    (read-shared! env roots)
    (load-session-definitions! env session)
    (reset! (:built-entries env) (fingerprint session))
    env))

(defn- ensure-environment!
  "The current environment for SESSION, rebuilt when the branch's definition
   entries changed or when it was never built."
  [service session cwd]
  (let [id (:id session)
        current (fingerprint session)
        existing (get @(:envs service) id)]
    (if (and existing
             (= (:session-file existing) (:file session))
             (= @(:built-entries existing) current))
      existing
      (let [env (build-environment service session cwd)]
        (swap! (:envs service) assoc id env)
        env))))

;; ─── Request normalization ────────────────────────────────────────────────

(defn- ->name [x]
  (cond
    (string? x) x
    (symbol? x) (str x)
    (keyword? x) (subs (str x) 1)
    :else nil))

(defn- ->scope [x]
  (cond
    (keyword? x) x
    (symbol? x) (keyword (name x))
    (string? x) (keyword (str/replace x #"^:" ""))
    :else nil))

(def ^:private scopes #{:session :project :global})

(defn- bad-request! [message data]
  (throw (ex-info message (assoc data :type :eval/bad-request))))

(defn- normalize-keep [keep]
  (mapv (fn [entry]
          (when-not (and (sequential? entry) (= 2 (count entry)))
            (bad-request! (str "keep entries are [name scope] pairs, got "
                               (pr-str entry))
                          {:keep entry}))
          (let [[name scope] entry
                name (->name name)
                scope (->scope scope)]
            (when-not name
              (bad-request! "keep needs a definition name" {:keep entry}))
            (when-not (contains? scopes scope)
              (bad-request! (str "keep scope must be :session, :project or :global, got "
                                 (pr-str scope))
                            {:keep entry}))
            {:name name :scope scope}))
        (or keep [])))

(defn- normalize-drop [drop]
  (mapv (fn [entry]
          (or (->name entry)
              (bad-request! (str "drop entries are definition names, got "
                                 (pr-str entry))
                            {:drop entry})))
        (or drop [])))

;; ─── Validation ───────────────────────────────────────────────────────────

(defn- known? [env parsed name]
  (or (some #(when (= name (:name %)) %) (:defs parsed))
      (tracked env name)))

(defn- validate-requests!
  "Validate every keep/drop before anything evaluates: unknown names, a name
   in both lists, duplicate list entries, and a scope outside the three."
  [env parsed keep drop]
  (doseq [{:keys [name]} keep]
    (when-not (known? env parsed name)
      (bad-request! (str "no tracked eval definition named " name)
                    {:name name :keep true})))
  (doseq [name drop]
    (when-not (known? env parsed name)
      (bad-request! (str "no tracked eval definition named " name)
                    {:name name :drop true})))
  (when-not (= (count keep) (count (distinct (map :name keep))))
    (bad-request! "keep names a definition twice" {:keep keep}))
  (when-not (= (count drop) (count (distinct drop)))
    (bad-request! "drop names a definition twice" {:drop drop}))
  (let [keep-names (set (map :name keep))]
    (doseq [name drop]
      (when (contains? keep-names name)
        (bad-request! (str "a definition cannot be both kept and dropped: " name)
                      {:name name})))))

;; ─── Applying a call's changes ────────────────────────────────────────────

(defn- add-record [records record]
  (let [i (.indexOf ^java.util.List records (:name record))]
    (if (neg? i) (conj records record) (assoc records i record))))

(defn- parsed-map [parsed]
  (into {} (map (juxt :name identity)) (:defs parsed)))

(defn- delete-shared!
  "Delete NAME's project/global files, returning the removed paths."
  [roots name]
  (eval-files/delete-name! [(root-for roots :project) (root-for roots :global)] name))

(defn- write-shared!
  "Write RECORD to SCOPE under ROOTS, removing the old shared file when the
   definition moves scopes. Returns the new path and the old file, if any."
  [roots shared name record scope]
  (let [target (root-for roots scope)
        old (get @shared name)]
    (if (and old (not= (:scope old) scope))
      (let [path (eval-files/move-definition!
                  (root-for roots (:scope old)) target record)]
        (swap! shared dissoc name)
        (swap! shared assoc name (assoc record :scope scope :file path))
        {:path path :moved-from (:file old)})
      (let [path (eval-files/write-definition! target record)]
        (swap! shared assoc name (assoc record :scope scope :file path))
        {:path path :moved-from nil}))))

(defn- apply-changes!
  "Apply the successful call's definition deltas: new session definitions,
   keeps, and drops. Returns the report the tool result carries."
  [service env session cwd parsed keep drop]
  (let [roots (eval-roots (:agent-dir service) cwd)
        shared (:shared env)
        session-defs (:session-defs env)
        parsed-by-name (parsed-map parsed)
        keep-by-name (into {} (map (juxt :name identity)) keep)
        drop-names (set drop)
        adds (atom [])
        removes (atom #{})
        kept (atom [])
        dropped (atom [])
        shadowed (atom [])
        lines (atom [])]
    (letfn [(add-line! [line] (swap! lines conj line))
            (add-remove! [name] (swap! removes conj name))
            (add-session! [record] (swap! adds add-record record))
            (drop! [name]
              (when (contains? @session-defs name)
                (add-remove! name))
              (delete-shared! roots name)
              (swap! shared dissoc name)
              (swap! dropped conj name)
              (add-line! (str "[eval: dropped " name "]")))
            (ordinary! [record]
              (let [name (:name record)]
                (when-not (or (contains? keep-by-name name)
                              (contains? drop-names name))
                  (let [session-record (get @session-defs name)
                        shared-record (get @shared name)]
                    (cond
                      session-record
                      (when (not= (:source session-record) (:source record))
                        (add-session! record))

                      shared-record
                      (do (add-session! record)
                          (swap! shadowed conj name)
                          (add-line! (str "[eval: shadowing " name
                                          " (session; file unchanged: "
                                          (display cwd (:file shared-record)) ")]")))

                      :else
                      (add-session! record))))))
            (keep-session! [name record shared-record]
              (when shared-record
                (delete-shared! roots name)
                (swap! shared dissoc name))
              (let [session-record (get @session-defs name)]
                (when (or (nil? session-record)
                          (not= (:source session-record) (:source record)))
                  (add-session! record)))
              (swap! kept conj {:name name :scope :session})
              (add-line! (if shared-record
                           (str "[eval: moved " name " "
                                (display cwd (:file shared-record)) " → session]")
                           (str "[eval: kept " name " → session]"))))
            (keep-shared! [name scope record session-record]
              (let [{:keys [path moved-from]} (write-shared! roots shared name record scope)]
                (when session-record
                  (add-remove! name)
                  (swap! session-defs dissoc name))
                (swap! kept conj {:name name :scope scope})
                (add-line! (if moved-from
                             (str "[eval: moved " name " "
                                  (display cwd moved-from) " → " (display cwd path) "]")
                             (str "[eval: kept " name " → " (display cwd path) "]")))))
            (keep! [name scope]
              (let [record (or (get parsed-by-name name)
                               (get @session-defs name)
                               (get @shared name))
                    session-record (get @session-defs name)
                    shared-record (get @shared name)]
                (when-not record
                  (bad-request! (str "no tracked eval definition named " name)
                                {:name name}))
                (if (= scope :session)
                  (keep-session! name record shared-record)
                  (keep-shared! name scope record session-record))))
            (commit! []
              (let [new-defs (defs/collapse @adds)
                    removed (vec (sort @removes))]
                (when (or (seq new-defs) (seq removed))
                  (session/append-custom-entry!
                   session defs/eval-definitions-type
                   {:version 1
                    :defs (mapv #(select-keys % [:name :ns :kind :source]) new-defs)
                    :removed removed})
                  (swap! session-defs
                         (fn [m]
                           (apply dissoc
                                  (reduce (fn [m r] (assoc m (:name r) r)) m new-defs)
                                  removed))))
                (reset! (:built-entries env) (fingerprint session))
                {:lines @lines
                 :kept @kept
                 :dropped @dropped
                 :shadowed @shadowed}))]
      (doseq [name drop] (drop! name))
      (doseq [record (:defs parsed)] (ordinary! record))
      (doseq [{:keys [name scope]} keep] (keep! name scope))
      (commit!))))

;; ─── The call ─────────────────────────────────────────────────────────────

(defn- current-ns [env]
  (some-> @(:sticky-ns env) sci/ns-name str))

(defn- parse-code! [code env]
  (try
    (defs/parse code (or (current-ns env) "user"))
    (catch Throwable t
      (throw (ex-info (str "eval could not read the code: " (ex-message t))
                      {:type :eval/invalid-code} t)))))

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
  "A failed call reports its error, never applies keep/drop, and still names
   the build failures the next attempt would see."
  [service env base started]
  (let [base (base-result base started)
        failures (effective-failures service env)]
    (result-parts
     base
     (assoc base
            :is-error true
            :details (cond-> (merge (:details base)
                                    {:kept [] :dropped [] :shadowed []})
                       (seq failures) (assoc :failures failures))))))

(defn- finish-call!
  "Merge the script result with the definition report: content lines after
   the emitted output, and the kept/dropped/shadowed vocabulary in :details."
  [service env session cwd parsed keep drop base started]
  (let [base (base-result base started)
        changes (try
                  (apply-changes! service env session cwd parsed keep drop)
                  (catch Throwable t
                    (reset! (:built-entries env) ::invalid)
                    {:lines [(str "[eval: failed: " (ex-message t) "]")]
                     :error (ex-message t)
                     :kept [] :dropped [] :shadowed []}))
        lines (:lines changes)
        base-content (:content base)
        content (cond
                  (empty? lines) base-content
                  (or (empty? base-content) (= "(no output)" base-content))
                  (str/join "\n" lines)
                  :else (str base-content "\n" (str/join "\n" lines)))
        failures (effective-failures service env)
        is-error (boolean (:error changes))]
    (result-parts
     base
     (assoc base
            :content (if (str/blank? content) "(no output)" content)
            :is-error is-error
            :details (cond-> (merge (:details base)
                                    (select-keys changes [:kept :dropped :shadowed]))
                       (seq failures) (assoc :failures failures)
                       (:error changes) (assoc :error :eval/change-failed))))))

(defn- eval!*
  [service env session cwd {:keys [code timeout limits signal on-update ctx keep drop]}]
  (let [code (some-> code str)
        lock (:lock env)]
    (locking lock
      (when @(:disposed? service)
        (throw (ex-info "the agent's eval service is disposed"
                        {:type :eval/disposed})))
      (let [started (concurrent/monotonic-ms)
            parsed (parse-code! code env)
            keep (normalize-keep keep)
            drop (normalize-drop drop)]
        (validate-requests! env parsed keep drop)
        (if (str/blank? code)
          (if (or (seq keep) (seq drop))
            (finish-call! service env session cwd parsed keep drop nil started)
            (bad-request! "eval needs code, keep or drop" {}))
          (let [handle (or (srce/get (:agent-ctx service) :tools)
                           (throw (ex-info "the eval service needs the agent's :tools service"
                                           {:type :eval/no-tools})))
                result (script/run {:tool "eval"
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
                                    :execute-tool #(registry/execute-tool handle %1 %2 %3)})]
            (if (:is-error result)
              (error-result service env result started)
              (finish-call! service env session cwd parsed keep drop result started))))))))

(defn- session-environment!
  "The environment for SESSION, built under the service's build lock."
  [service session cwd]
  (let [lock (:build-lock service)]
    (locking lock
      (when @(:disposed? service)
        (throw (ex-info "the agent's eval service is disposed"
                        {:type :eval/disposed})))
      (ensure-environment! service session cwd))))

(defn- current-environment [service]
  (when-let [session (extensions/get-session)]
    (get @(:envs service) (:id session))))

(defn- service-kept [service]
  (if-let [env (current-environment service)]
    (kept-entries env)
    []))

(defn- service-failures [service]
  (into (vec @(:service-failures service))
        (when-let [env (current-environment service)]
          @(:failures env))))

(defn- snapshot-session! [service session]
  (when (and session (not @(:disposed? service)))
    (let [entries (definition-entries session)
          folded (defs/fold-entries entries)]
      (when (seq entries)
        (try
          (session/append-custom-entry!
           session defs/eval-definitions-type
           {:version 1
            :snapshot true
            :defs (mapv #(select-keys % [:name :ns :kind :source])
                        (:defs folded))})
          (catch Throwable t
            (swap! (:service-failures service) conj
                   {:session (:id session)
                    :error (ex-message t)})
            (debug/log "eval snapshot after compaction failed: " t)
            nil))))))

(defn- eval-call! [service opts]
  (when @(:disposed? service)
    (throw (ex-info "the agent's eval service is disposed"
                    {:type :eval/disposed})))
  (let [session (resolve-session (:session opts))
        _ (when-not session
            (throw (ex-info "eval needs a session"
                            {:type :eval/no-session})))
        cwd (or (:cwd opts) (get-in opts [:ctx :cwd]) (tool-util/cwd))
        env (session-environment! service session cwd)]
    (eval!* service env session cwd opts)))

(defn value
  "The `:eval` service for AGENT-CTX. The environments are keyed by session
   and owned here; the `:tools` handle is resolved from AGENT-CTX on the first
   call, so the script surface is the agent's own table.

   OPTS: `:agent-dir` pins the global eval root (tests, a sandboxed
   composition); it defaults to `cfg/get-agent-dir`."
  ([agent-ctx] (value agent-ctx nil))
  ([agent-ctx {:keys [agent-dir]}]
   (let [envs (atom {})
         failures (atom [])
         build-lock (Object.)
         disposed? (atom false)
         service {:agent-ctx agent-ctx
                  :agent-dir (or agent-dir (cfg/get-agent-dir))
                  :envs envs
                  :service-failures failures
                  :disposed? disposed?
                  :build-lock build-lock}]
     {:eval! #(eval-call! service %)
      :kept #(service-kept service)
      :sources (constantly [])
      :failures #(service-failures service)
      :snapshot! #(snapshot-session! service %)
      :dispose! (fn []
                  (locking build-lock
                    (reset! disposed? true)
                    (reset! envs {})
                    (reset! failures [])
                    nil))})))
