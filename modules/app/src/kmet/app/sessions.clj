(ns kmet.app.sessions
  "The `:sessions` service, and the `:sessionPersistence` contract under it.

   Two seams, one log. `:sessionPersistence` is the storage backend: it mints a
   session handle and answers seven ops over it. `:sessions` is the log the app
   reads — the same ops over the same handle, plus an id index, so a caller that
   created or loaded a session can find it again by id. A second storage
   backend shadows `:sessionPersistence`; nothing shadows `:sessions`, because a
   log with no storage layer is not a second thing, it is a bug.

   The **handle is the backend's** and the app never looks inside it. That is
   what lets the EDNL store (`kmet.app.session.*`) and the in-process
   `memory-backend` here answer one contract; `kmet.app.session.backend` is the EDNL one and
   `kmet.app.session.test-contract` runs one set of assertions against both.
   The ops are values, not a protocol: a backend is a plugin that provides this
   key, which is why `:sessions` needs no interface.

   Two rules the ops carry, stated once here because a backend that gets them
   wrong is indistinguishable from a working one for a while:

   - **an append is the write.** `append!` stamps the entry (`:id`,
     `:parent-id` from the session's leaf, `:timestamp`) and returns it; it
     never renumbers or reorders what came before, and a second append chains
     onto the first. `entries` returns every entry ever appended, in order —
     the branch walk over `:parent-id` is a reading, not a storage concern.
   - **nothing is stored until the first assistant entry.** A created session
     that has only user entries is memory-only (`paths` does not list it, no
     file exists), so a session that never got an answer leaves no litter.

   One delta is deliberate and temporary: the EDNL backend stamps
   `:result-tokens` on tool entries (it is `kmet.app.session.store`'s append
   policy today). The contract does not assert on tool entries, so the two
   backends agree on everything the contract covers; the stamping moves into
   this namespace with the layer itself (4c-i), where derived state over the log
   belongs. It is recorded here so the difference is a decision
   rather than a discovery."
  (:require [kmet.srce.core :as srce]))

;; ── the persistence contract ──────────────────────────────────────────────

(def persistence-ops
  "Every op a `:sessionPersistence` backend answers, in argument order. The
   constructor checks the key set once — a backend missing an op fails at mount
   naming the op, rather than at the first call inside a turn."
  [:create! :load :paths :append! :entries :delete! :header])

(def op-docs
  {:create! "(create! {:dir D :cwd C :parent-session P}) → session
             A new session handle: header `{:type :session :version 1 :id
             :created-at :cwd}`, id equal to `(:id (header session))`. Nothing is
             stored before the first assistant entry."
   :load "(load PATH) → session
             The handle for a stored session, header and entries read back."
   :paths "(paths DIR) → [path …]
             The stored sessions under DIR, newest first. A session that never
             took an assistant entry is not listed."
   :append! "(append! session entry) → entry
             Stamp `:id`, `:parent-id` (the session's leaf, nil for a root) and
             `:timestamp`; store it; return it. Never touches earlier entries."
   :entries "(entries session) → [entry …]
             Every appended entry, in append order."
   :delete! "(delete! session) → nil
             Drop the stored log. A later `paths` no longer lists it."
   :header "(header session) → map
             The session's header, as stored."})

(defn- incomplete!
  "Throw for a backend that cannot answer the contract. Names every missing op."
  [missing]
  (throw (ex-info (str "session persistence backend is missing "
                       (pr-str (vec missing)) " — see kmet.app.sessions/op-docs")
                  {:type :kmet.app/incomplete-backend
                   :missing (vec missing)})))

(defn- check-backend
  "BACKEND checked against `persistence-ops` once, at service construction."
  [backend]
  (when-not (map? backend)
    (incomplete! persistence-ops))
  (let [missing (filterv #(not (fn? (get backend %))) persistence-ops)]
    (when (seq missing) (incomplete! missing)))
  backend)

;; ── the log over a backend ────────────────────────────────────────────────

(defn service
  "A `:sessions` value over BACKEND: the contract ops plus the id index
   (`get`). Each service value owns its own index, so composing twice yields
   two logs, and a disposed composition takes its index with it."
  [backend]
  (let [backend (check-backend backend)
        header (:header backend)
        session-id (fn [session] (:id (header session)))
        by-id (atom {})
        track (fn [session]
                (when-let [id (session-id session)]
                  (swap! by-id assoc id session))
                session)]
    {:create! (fn [opts] (track ((:create! backend) opts)))
     :load (fn [path] (some-> ((:load backend) path) track))
     :get (fn [id] (get @by-id id))
     :paths (:paths backend)
     :append! (:append! backend)
     :entries (:entries backend)
     :delete! (fn [session]
                (when-let [id (session-id session)]
                  (swap! by-id dissoc id))
                ((:delete! backend) session))
     :header header}))

(defn- serve
  "The `:sessions` row: build the log over the injected storage backend and
   provide it into the mount site's scope, so sibling rows see it while the
   entry stays this fiber's effect (the `:tools` and `:events` rows' rule)."
  [ctx _config]
  (srce/provide! ctx :sessions (service (srce/get ctx :sessionPersistence))
                 {:in (:parent ctx)}))

(srce/defplugin plugin
  {:name :sessions
   :inject [:sessionPersistence]
   :provide #{:sessions}
   :apply serve})

;; ── an in-process backend ─────────────────────────────────────────────────

(defn memory-backend
  "A session backend that keeps everything in an atom: the second backend the
   contract test needs, and the fixture for anything that wants a log without
   files. It answers the two rules the EDNL backend answers — appends stamp and
   chain, and nothing is listed before the first assistant entry — and it
   carries the store's own shape (`:entries` and `:leaf-id` atoms), so a
   reading written against a stored session (the branch walk, the
   `:projections` fold) reads an in-process one too. A test written against it
   is a test against the contract, not against a mock."
  []
  (let [state (atom {:sessions {}})
        counter (atom 0)
        id! (fn [] (str "mem-" (swap! counter inc)))
        path-for (fn [dir id] (str dir "/" id ".ednl"))]
    (letfn [(stamped [session entry]
              (let [entries @(:entries session)
                    leaf @(:leaf-id session)
                    entry (assoc entry
                                 :id (str (:id session) "#" (count entries))
                                 :parent-id leaf
                                 :timestamp (str (java.time.Instant/now)))]
                (reset! (:entries session) (conj entries entry))
                (reset! (:leaf-id session) (:id entry))
                (when (= :assistant (:role entry)) (reset! (:stored? session) true))
                entry))]
      {:create!
       (fn [{:keys [dir cwd parent-session]}]
         (let [id (id!)]
           (swap! state assoc-in [:sessions id]
                  (cond-> {:id id
                           :dir dir
                           :path (path-for dir id)
                           :header (cond-> {:type :session
                                            :version 1
                                            :id id
                                            :created-at (str (java.time.Instant/now))
                                            :cwd (or cwd dir)}
                                     parent-session (assoc :parent-session parent-session))
                           :entries (atom [])
                           :leaf-id (atom nil)
                           :stored? (atom false)}))
           (get-in @state [:sessions id])))

       :load
       (fn [path]
         (or (some (fn [s] (when (= path (:path s)) s)) (vals (:sessions @state)))
             (throw (ex-info (str "no such stored session: " path)
                             {:type :kmet.app/no-such-session :path path}))))

       :paths
       (fn [dir]
         (->> (:sessions @state)
              vals
              (filter #(and (= dir (:dir %)) @(:stored? %)))
              (map :path)
              vec))

       :append! stamped

       :entries (fn [session] @(:entries session))

       :delete! (fn [session]
                  (swap! state update :sessions dissoc (:id session))
                  nil)

       :header (fn [session] (:header session))})))
