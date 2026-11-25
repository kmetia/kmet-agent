(ns kmet.app.projections
  "The `:projections` service: derived state over a session log, incrementally.

   The log is append-only (`:sessions`), so everything an agent knows about its
   own past is a reading of it. This namespace is the reading that must not be
   recomputed per call:

   - **the inbox** — the messages queued for an agent's next turn
     (`:next-turn`, delivered after the run settles) or its next step
     (`:next-step`, injected between tool batches). The queue is *the log*, not
     a second in-memory list: enqueueing appends a fact, delivering (or
     dropping) appends the fact that consumes it, and the pending set is the
     fold of the three. That is the durability change this row carries — a
     queued message survives a restart because it was never only in memory.
   - **the turn boundaries** — the entries at which a turn ended (an assistant
     entry with no tool calls) and its outcome, so a front end or a wire client
     can resume from the last one instead of scanning the log.
   - **the token meter** — the usage totals over the branch, folded per entry
     (`kmet.ai.usage/entry-usage`'s normalized shape).

   **The fold is incremental, and the cache's key is the prefix.** A projection
   is built over the branch root→leaf (`kmet.app.session.store/get-branch`),
   and the cache for a session remembers the entry it stopped at and how many it
   consumed. A branch that still *begins* with that fold continues from there —
   an append moves the leaf forward and keeps the prefix — while anything else
   (a branch switch, a fork arriving on another branch, a switch back past the
   cached length) starts over. The leaf id alone cannot be the key: every append
   moves it. `:extended` on the projection is how many entries this call folded,
   so the cache's behaviour is observable rather than assumed.

   **The queue-entry conventions are in this namespace, both directions.** An
   enqueue, a deliver and a drop are `:custom` entries with
   `:custom-type :queued-message` and an `:op` in `:data`; `:custom` is
   metadata to the context projection (`kmet.app.session.context`) and never
   reaches the model, so the queue cannot leak into a prompt. The loop's own
   append of the delivered user message stays what it always was — the queue is
   the intent, the user entry is the delivery."
  (:require [kmet.app.session.store :as store]
            [kmet.ai.usage :as usage]
            [kmet.srce.core :as srce]))

;; ── the queue convention ──────────────────────────────────────────────────

(def queued-type
  "The `:custom-type` a queued-message entry carries. One type for both ops,
   because an enqueue and a deliver are the two halves of one fact."
  :queued-message)

(def targets
  "Where a queued message is delivered: `:next-turn` (after the run settles)
   or `:next-step` (between tool batches — the legacy CLI's steering queue)."
  #{:next-turn :next-step})

(defn- queue-entry
  "The `:custom` entry recording OP with DATA: what both ops are made of."
  [op data]
  {:role :custom
   :custom-type queued-type
   :data (assoc data :op op)})

(defn enqueue-entry
  "The log entry that records MSG as queued for TARGET.

   MSG is a string (a plain user message) or a message map (a structured one,
   which the loop injects as a custom message); the entry keeps it under the
   same key, so the inbox returns what was queued. OPTS: `:target` is required
   and must be in `targets`; anything else rides along as it was given
   (`:display`, `:details`), because an extra option is the extension's, not
   this namespace's."
  [msg {:keys [target] :as opts}]
  (when-not (contains? targets target)
    (throw (ex-info (str "a queued message targets " (pr-str (sort targets))
                         ", not " (pr-str target))
                    {:type :kmet.app/bad-queue-target :target target})))
  (when-not (or (string? msg) (map? msg))
    (throw (ex-info (str "a queued message is a string or a message map, not "
                         (pr-str (type msg)))
                    {:type :kmet.app/bad-queued-message :message msg})))
  (queue-entry :enqueue
               (assoc (dissoc opts :target)
                      :target target
                      (if (string? msg) :text :message) msg)))

(defn deliver-entry
  "The log entry that consumes the queued entries IDS: the delivery fact, so
   the inbox stops listing them without any entry ever being rewritten."
  [ids]
  (when (empty? ids)
    (throw (ex-info "a delivery names at least one queued message"
                    {:type :kmet.app/bad-delivery :ids ids})))
  (queue-entry :deliver {:ids (vec ids)}))

(defn drop-entry
  "The log entry that consumes the queued entries IDS without delivering them:
   the queue was discarded (a session move or an editor restore), so the inbox
   stops listing them while the delivery fact stays truthful."
  [ids]
  (when (empty? ids)
    (throw (ex-info "a drop names at least one queued message"
                    {:type :kmet.app/bad-drop :ids ids})))
  (queue-entry :drop {:ids (vec ids)}))

(defn- queued-op [entry]
  (when (and (= :custom (:role entry))
             (= queued-type (:custom-type entry)))
    (get-in entry [:data :op])))

(defn- inbox-item
  "The inbox row for an enqueue ENTRY: what was queued, under the entry's own
   id — the id a delivery names."
  [entry]
  (-> (dissoc (:data entry) :op)
      (assoc :id (:id entry))))

;; ── turn boundaries ───────────────────────────────────────────────────────

(defn outcome
  "A turn's outcome from its assistant entry's `:stop-reason` — `:aborted`,
   `:error`, or `:completed`. The one implementation: the loop's turn-end
   dispatch reads it too (`kmet.app.loop/finish-turn!`), so a boundary this
   projection reports and the outcome the loop acts on cannot disagree."
  [entry]
  (case (:stop-reason entry)
    :aborted :aborted
    :error :error
    :completed))

(defn- turn-end?
  "Does ENTRY end a turn? An assistant entry with no tool calls is the batch
   the loop settles on; one with tool calls continues into another request."
  [entry]
  (and (= :assistant (:role entry))
       (empty? (:tool-calls entry))))

;; ── the fold ──────────────────────────────────────────────────────────────

(def empty-totals
  "The token meter's zero: every normalized usage key, so a session with no
   usage still reports the shape its consumers read."
  {:input 0 :output 0 :cache-read 0 :cache-write 0 :cost 0.0})

(def empty-projection
  "The projection of an empty branch. `:entries` counts the branch entries
   folded so far; `:pending` is what the inbox is materialized from."
  {:pending {:next-turn [] :next-step []}
   :boundaries []
   :tokens empty-totals
   :entries 0})

(defn- remove-ids
  "ITEMS without the queued entries IDS names."
  [items ids]
  (into [] (remove (fn [item] (contains? ids (:id item)))) items))

(defn- fold-entry
  "Extend STATE with ENTRY: the queue facts, a turn boundary, the entry's
   usage, and the count."
  [state entry]
  (let [pos (:entries state)
        state (update state :entries inc)
        state (if-let [u (usage/entry-usage (:usage entry))]
                (update state :tokens #(merge-with + % u))
                state)
        state (if (turn-end? entry)
                (update state :boundaries conj
                        {:entry-id (:id entry)
                         :index pos
                         :outcome (outcome entry)})
                state)]
    (case (queued-op entry)
      :enqueue (let [{:keys [target]} (:data entry)]
                 (if (contains? targets target)
                   (update-in state [:pending target] conj (inbox-item entry))
                   state))
      ;; a delivery or drop can only name entries earlier on the same branch
      ;; (the append order and the parent chain guarantee it), so filtering
      ;; the pending set is the whole of it — there is no delivered set to keep
      (:deliver :drop) (let [ids (set (get-in entry [:data :ids]))]
                         (-> state
                             (update-in [:pending :next-turn] remove-ids ids)
                             (update-in [:pending :next-step] remove-ids ids)))
      state)))

(defn- materialize
  "The projection a caller sees: the inbox nested under its target, the
   boundaries, the meter, and how many entries this call folded."
  [state extended]
  {:inbox (:pending state)
   :boundaries (:boundaries state)
   :tokens (:tokens state)
   :entries (:entries state)
   :extended extended})

(defn- prefix?
  "Does BRANCH begin with the branch CACHED folded? The cache is reusable
   exactly when the entry it stopped at is still at that position: an append
   moves the leaf forward and keeps the prefix, while a branch switch replaces
   it — and a switch *back* past the cached length is a rebuild too."
  [branch cached]
  (let [n (:n cached)]
    (and (<= n (count branch))
         (= (:leaf cached) (some-> (nth branch (dec n) nil) :id)))))

(defn project
  "PROJECTION of SESSION, folded through CACHE (an atom keyed by session id).

   The cache holds the entry the fold stopped at and how many entries it
   consumed: a branch that still begins with that fold continues from there,
   anything else starts over. Returns the projection, whose `:extended` is the
   count folded by *this* call."
  [cache session]
  (let [branch (store/get-branch session)
        sid (:id session)
        cached (get @cache sid)
        resume (when (and cached (prefix? branch cached)) cached)
        from (long (or (:n resume) 0))
        state (or (:state resume) empty-projection)
        state (reduce fold-entry state (subvec branch from))
        projection (materialize state (- (count branch) from))]
    ;; the branch's own last entry, not the session's leaf atom: `prefix?`
    ;; compares against a position in the branch, so the cache's key has to be
    ;; the entry that position holds
    (swap! cache assoc sid {:leaf (some-> branch last :id)
                            :n (count branch)
                            :state state})
    projection))

;; ── the service ───────────────────────────────────────────────────────────

(defn service
  "A `:projections` value over LOG (the `:sessions` service): `:enqueue!`,
   `:deliver!` and `:drop!` append the queue facts, `:project` answers the
   derived state and `:inbox` just its inbox. Each service value owns its own
   cache, so composing twice yields two caches and a disposed composition takes
   its cache with it.

   `:forget!` drops a session's cache entry: a deleted session is one this
   service no longer sees, so nothing would ever fold it again, and the entry
   would outlive the log it was built from."
  [log]
  (let [cache (atom {})
        append! (:append! log)]
    {:enqueue! (fn [session msg opts]
                 (append! session (enqueue-entry msg opts)))
     :deliver! (fn [session ids]
                 (append! session (deliver-entry ids)))
     :drop! (fn [session ids]
              (append! session (drop-entry ids)))
     :project (fn [session] (project cache session))
     :inbox (fn [session] (:inbox (project cache session)))
     :forget! (fn [session]
                (swap! cache dissoc (:id session))
                nil)}))

(defn- serve
  "The `:projections` row: the service over the injected log, provided into the
   mount site's scope so sibling rows see it while the entry stays this fiber's
   effect (the `:sessions` row's rule)."
  [ctx _config]
  (srce/provide! ctx :projections (service (srce/get ctx :sessions))
                 {:in (:parent ctx)}))

(srce/defplugin plugin
  {:name :projections
   :inject [:sessions]
   :provide #{:projections}
   :apply serve})
