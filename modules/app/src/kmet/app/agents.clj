(ns kmet.app.agents
  "The `:agents` registry: live agent handles, the creation factory seam, and
   the send surface a front end drives.

   **An agent is a child context plus a handle** (`docs/architecture.md` § Multi-session
   server mode). The child context is the registry's: it extends the ctx
   `create!` was called with, inherits the services the root provides, and is
   the tree line `--dump-state` prints (`[:app :agent/5f3a2b1c]`). That ctx is
   the app ctx, or another agent's ctx for a subagent — never a plugin's mount
   ctx, which a fiber's disposal drops out of the tree with its children still
   hanging on it. The handle is the factory's: what an agent *is* belongs to the
   driver that runs it, so this namespace never learns what a turn, a session or
   a model is.

   **The factory is the seam, and there is none until a loop mounts.** `:agents`
   exists as soon as the row mounts, but `create!` throws
   `:kmet.app/no-agent-factory` until a driver registers one
   (`register-factory!`, the disposer a plugin's `apply` returns) — dsh's
   `setFactory`. One factory per registry: a second registration while one is
   live is `:kmet.app/factory-taken`, because two drivers over one registry is
   two owners of one agent's teardown.

   **The handle is the capability.** `create!` resolves with it; `dispose!`
   takes it and nothing else, and a handle that is not the registered one (an
   id, a copy, a value a consumer assembled) is refused. `get` returns the bare
   agent, which is what a listing or a wire client needs and cannot dispose
   with.

   **A disposal takes the subtree with it.** The ctx tree under an agent's ctx
   is this registry's too, and a subagent created under it is a child ctx that
   the kernel's `detach!` would refuse to release around — so `dispose!`
   disposes the agents registered under it (deepest first) before its own. A
   release that fails is reported and the capability withdrawn anyway: a handle
   outliving its ctx is a handle nobody can use. Disposing a handle twice is
   `:kmet.app/unknown-agent`, not a no-op — the capability was spent by the
   first call.

   **Creation is serialized and rolls back.** A failed factory leaves no handle
   and no child context: the ctx is disposed and detached in the same breath, so
   nothing observes a half-built agent. `:setup` — a fn of `(ctx id)` — runs
   between the child ctx and the factory, which is where per-agent composition
   happens (`kmet.app.presets`); a setup that throws rolls back the same way. The kernel is single-owner-threaded
   (`srce.md` §8), so a create already in flight is a caller on another thread —
   it is refused (`:kmet.app/create-busy`) rather than interleaved; such a
   caller submits through `kmet.app.runtime/submit!`.

   **Sending is durable.** `send!` appends the queue fact through
   `:projections` and only then notifies the live handle (its `:send!`, the
   driver's in-memory queue): the log is the record, the atom is the wake-up.
   A message queued while the process was down is still in the inbox when the
   session resumes. A send carrying `:images` appends no queue fact: it either
   starts a run (the user message is the session log's own) or the handle
   refuses it, so nothing is ever queued with an image and a refusal cannot
   leave an undelivered entry behind."
  (:require [kmet.app.events :as events]
            [kmet.srce.core :as srce]))

;; ── the handle contract ───────────────────────────────────────────────────

(def handle-contract
  "What every handle the factory returns must be, key by key. A handle is a
   map the driver builds; this table is the whole of what the registry asks of
   it, and `validate-handle!` reads it rather than restating it:

     :id       the id the registry minted (passed to the factory in its opts)
     :ctx      the agent's child context — the registry's own
     :agent    the bare agent (non-nil), what `get` and `list` answer with
     :session  a thunk returning the live session (a session switch moves it)
     :dispose! the factory's teardown: abort the turn, drain the queue. The
               registry releases the child context after it returns.

   `:send!` is the one op called with more than the message: the registry
   hands it the queued entry too (`:entry` in the opts), because the driver is
   what knows when the message reached its run and must append the delivery
   fact the inbox folds."
  {:id (fn [id _ handle] (= id (:id handle)))
   :ctx (fn [_ child handle] (identical? child (:ctx handle)))
   :agent (fn [_ _ handle] (some? (:agent handle)))
   :session (fn [_ _ handle] (fn? (:session handle)))
   :dispose! (fn [_ _ handle] (fn? (:dispose! handle)))})

(def setting-ops
  "The settings, queue and compaction ops a handle answers by name, with the
   same call shape: `(op handle & args)` dispatches to the handle's fn. Listed
   once, because two lists (validate, dispatch) that must agree is the drift a
   registry exists to prevent."
  [:set-model! :cycle-model! :set-thinking-level! :cycle-thinking-level!
   :set-scoped-models! :set-auto-compaction! :set-steering-mode!
   :set-follow-up-mode! :set-block-images! :set-system-prompt!
   :apply-default-tools! :set-config! :set-run-options!
   :set-http-idle-timeout-ms! :set-http-total-timeout-ms! :clear-queues!
   :request-compaction! :abort-compaction! :queue-compaction-message!
   :clear-compaction-queue!])

(def session-ops
  "The session-lifecycle ops a handle answers by name: the moves a front end's
   /new, /session, fork, clone and branch commands need, plus the session's name
   and HTML export. `:resume-session!` loads a stored session and switches to
   it, as opposed to the registry's `:resume!`, which creates a new agent for
   one. Same call shape as `setting-ops` (`(op handle & args)`); each moves the
   session of the agent it was called with, and none installs it into the
   process's extension slot."
  [:new-session! :resume-session! :switch-session! :fork! :branch-session! :clone!
   :set-session-name! :export-html!])

(def run-ops
  "The run's own ops: send, the submission chain (`:submit!`, pi's
   session.prompt input event), the compaction-queue flush, the custom message
   (`:inject!`, pi's sendCustomMessage), cancel, the idle awaitable, and the
   status a listing shows."
  [:send! :submit! :flush-compaction-queue! :inject! :cancel! :when-idle
   :status])

(def handle-ops
  "The optional control surface a handle may answer. A caller asking for one a
   handle does not implement gets `:kmet.app/unsupported-op` naming it, never a
   silent nil.

   `run-ops` are the run's; `setting-ops` and `session-ops` are the rest — each
   writes that agent's state or moves its session, so two agents of one process
   can differ — measured from the interactive TUI's own call sites
   (docs/architecture.md § Multi-session server mode)."
  (into run-ops (concat setting-ops session-ops)))

(defn- validate-handle!
  "HANDLE checked against `handle-contract` for ID and CHILD; returns it. A
   factory that returns the wrong shape fails here, at creation, rather than
   inside the first caller."
  [id child handle]
  (let [problems (if-not (map? handle)
                   [:map]
                   (into (into [] (keep (fn [[k fits?]]
                                          (when-not (fits? id child handle) k)))
                               handle-contract)
                         ;; an op present but not callable is a shape problem
                         ;; too, so the caller gets the op's name instead of
                         ;; "not a function" from inside it
                         (keep (fn [op]
                                 (when (and (contains? handle op)
                                            (not (fn? (get handle op))))
                                   op))
                               handle-ops)))]
    (when (seq problems)
      (throw (ex-info (str "the agent factory returned a handle that does not fit the contract: "
                           (pr-str problems))
                      {:type :kmet.app/bad-agent-handle
                       :id id
                       :problems problems
                       :required (vec (keys handle-contract))
                       :optional handle-ops})))
    handle))

(defn- session!
  "HANDLE's live session, or a throw: the inbox is the log, so an agent without
   one has nowhere to queue and nothing to read — naming it here beats a nil
   deref from inside the backend."
  [handle]
  (or ((:session handle))
      (throw (ex-info (str "agent " (pr-str (:id handle))
                           " has no session to queue into")
                      {:type :kmet.app/agent-has-no-session
                       :id (:id handle)}))))

;; ── emission ──────────────────────────────────────────────────────────────

(defn- emit!
  "Tell SCOPE's listeners that an agent came or went, through the app's
   dispatcher — this plugin's `:events` declaration (registered at mount) is
   what decides how."
  [scope type event]
  (events/emit! scope (assoc event :type type)))

;; ── ids ───────────────────────────────────────────────────────────────────

(defn- mint
  "A short id not among HANDLES: `5f3a2b1c`-style, so the tree's
   `[:agent/5f3a2b1c]` line stays readable and two agents cannot share a path."
  [handles]
  (loop []
    (let [id (subs (str (random-uuid)) 0 8)]
      (if (contains? handles id) (recur) id))))

;; ── creation ──────────────────────────────────────────────────────────────

(defn- claim-busy!
  "Mark the registry busy with ID, or throw when another create is in flight.
   A CAS rather than a `swap!` side effect: the claim is the state transition."
  [state id]
  (loop []
    (let [v @state]
      (if-let [busy (:busy v)]
        (throw (ex-info (str "an agent creation is already in flight (" busy ")")
                        {:type :kmet.app/create-busy :creating busy}))
        (when-not (compare-and-set! state v (assoc v :busy id))
          (recur))))))

(defn- release-subtree!
  "Release CTX's mounts, then the ctx itself — the rollback a failed creation
   and a disposal share. `detach!` refuses while a mount remains, so the order
   is the kernel's rule, not a preference.

   A release that fails is reported and swallowed: its callers are rolling back
   a failed creation (whose error is the one to raise) or withdrawing a
   capability (which a ctx the kernel will not detach must not block)."
  [scope ctx]
  (try
    (srce/dispose-subtree! ctx)
    (srce/detach! ctx)
    (catch Throwable t
      (srce/log! scope :error "agent ctx release failed" t))))

(defn- composition-bug?
  "Is T the kernel saying *the composition* is wrong rather than that this
   creation failed? A composition bug keeps its own type through the rollback —
   the caller may act on it — and everything else is wrapped as a creation that
   failed, with its cause still attached. A preset names a bundle a profile
   author wrote, so an unknown one is the composition's error, like a preset
   that composes but does not mount."
  [t]
  (contains? #{:kmet.app/bad-agent-handle :kmet.app/preset-failed
               :kmet.app/unknown-bundle}
             (:type (ex-data t))))

(defn- setup-fn
  "The `:setup` option of a create: a fn of `(ctx id)`, or nil. Checked before
   anything is minted or extended, because a composition can carry a keyword
   where a fn belongs, and that deserves its own name rather than \"not a
   function\" from inside the creation catch."
  [opts]
  (when-let [setup (:setup opts)]
    (when-not (fn? setup)
      (throw (ex-info "a create! :setup is a fn of (ctx id)"
                      {:type :kmet.app/bad-setup :setup setup})))
    setup))

(defn- subtree-ctxs
  "CTX's descendant ctxs, parents before children and eagerly: a disposal
   mutates the tree it is walking, so the walk cannot be a lazy seq."
  [ctx]
  (reduce (fn [acc child]
            (into (conj acc child) (subtree-ctxs child)))
          []
          (srce/children ctx)))

(defn- agents-under
  "The live handles whose ctx is a descendant of CTX, deepest first — the
   agents a disposal takes with it."
  [{:keys [handles]} ctx]
  (let [by-ctx (into {} (map (juxt :ctx identity)) (vals handles))]
    (into [] (keep by-ctx) (reverse (subtree-ctxs ctx)))))

(defn- parent-ctx!
  "CTX checked as an agent's parent. A plugin's mount ctx is refused: disposing
   a fiber drops its mount ctx out of the tree with whatever child ctxs it
   still holds, and nothing can see them afterwards — an agent parented there
   would leak invisibly. The registry's scope (the app root) or another agent's
   ctx outlives what is created under it."
  [ctx]
  (when (srce/mount-fiber ctx)
    (throw (ex-info (str "an agent cannot be parented to a plugin's mount ctx ("
                         (pr-str (:name ctx)) "): name the app ctx or an agent ctx")
                    {:type :kmet.app/agent-parent-is-a-mount
                     :ctx (:name ctx)})))
  ctx)

(defn- factory-for
  "The registered factory, or a throw naming the seam that fills it."
  [state ctx]
  (or (:factory @state)
      (throw (ex-info "no agent factory is registered: mount a loop driver (`:agentLoop`) that registers one"
                      {:type :kmet.app/no-agent-factory :ctx (:name ctx)}))))

;; ── the service ───────────────────────────────────────────────────────────

(defn service
  "The `:agents` value serving SCOPE, over PROJECTIONS: a registry of live
   handles, the factory seam, and the control surface.

   Read ops (`list`, `get`, `roots`, `inbox`) take a ctx and check it is inside
   the scope the service serves — a registry answers for its own tree. The
   control ops take the handle, because the handle is the capability."
  [scope projections]
  (when-not (fn? (:enqueue! projections))
    (throw (ex-info "an :agents service needs the :projections service"
                    {:type :kmet.app/incomplete-service :missing :projections})))
  (let [state (atom {:handles {} :order [] :factory nil :busy nil})
        inside! (fn [ctx]
                  (when-not (srce/inside? ctx scope)
                    (throw (ex-info "this agent registry serves another ctx tree"
                                    {:type :kmet.app/outside-scope})))
                  ctx)
        summary (fn [handle]
                  {:id (:id handle)
                   :agent (:agent handle)
                   :status (when-let [f (:status handle)] (f))})
        live (fn [handle]
               (let [registered (get-in @state [:handles (:id handle)])]
                 (when-not (identical? registered handle)
                   (throw (ex-info (str "not a live agent handle: " (pr-str (:id handle)))
                                   {:type :kmet.app/unknown-agent
                                    :id (:id handle)})))
                 registered))
        unsupported (fn [op id]
                      (throw (ex-info (str "agent " (pr-str id) " does not answer " op)
                                      {:type :kmet.app/unsupported-op
                                       :op op :id id})))
        dispatch (fn [handle k args]
                   (let [handle (live handle)]
                     (if-let [f (get handle k)]
                       (apply f args)
                       (unsupported k (:id handle)))))]
    (letfn [(create! [ctx opts]
              (parent-ctx! (inside! ctx))
              (let [setup (setup-fn opts)
                    factory (factory-for state ctx)
                    id (mint (:handles @state))]
                (claim-busy! state id)
                (try
                  (let [child (srce/extend ctx {:name (keyword "agent" id)})
                        ;; one guarded region, three failure modes: a setup that
                        ;; throws, the factory throwing, and the factory
                        ;; returning a handle that does not fit the contract —
                        ;; all three must leave no child ctx behind
                        handle (try
                                 ;; setup composes the agent's scoped world on
                                 ;; its own ctx *before* the factory runs
                                 ;; (dsh's setup(agentCtx, agent)): a preset
                                 ;; mounts its rows here, so no agent observes a
                                 ;; half-built world
                                 (when setup (setup child id))
                                 (validate-handle! id child
                                                   ((:f factory) child
                                                                 (assoc (dissoc opts :setup) :id id)))
                                 (catch Throwable t
                                   (release-subtree! scope child)
                                   (if (composition-bug? t)
                                     (throw t)
                                     (throw (ex-info (str "agent creation failed: "
                                                          (or (ex-message t) (str t)))
                                                     {:type :kmet.app/agent-create-failed
                                                      :id id}
                                                     t)))))]
                    (swap! state (fn [s]
                                   (-> s
                                       (assoc-in [:handles id] handle)
                                       (update :order conj id))))
                    ;; the id and the agent, never the ctx: a ctx is cyclic,
                    ;; so a listener that logs its event would never return
                    (emit! scope :agent/created {:id id :agent (:agent handle)})
                    handle)
                  (finally
                    (swap! state assoc :busy nil)))))

            (send! [handle msg opts]
              (let [handle (live handle)
                    images (when (map? msg) (:images msg))]
                (if (seq images)
                  ;; an image prompt starts a run or is refused by the handle:
                  ;; nothing is ever queued with an image, so there is no
                  ;; pending entry to fold (the user message itself is the
                  ;; session log's), and a refusal must not leave one behind
                  (when-let [f (:send! handle)] (f msg opts))
                  (let [entry ((:enqueue! projections) (session! handle) msg opts)]
                    ;; the entry rides along: the driver is what knows when the
                    ;; message reached its run, so it appends the delivery the
                    ;; inbox folds
                    (when-let [f (:send! handle)]
                      (f msg (assoc opts :entry entry)))
                    entry))))

            (dispose! [handle]
              (let [handle (live handle)
                    id (:id handle)]
                ;; the agents under this one go first: a subagent's ctx is a
                ;; child of its parent's, and detach! refuses while a child ctx
                ;; hangs there. Deepest first, so each release leaves its
                ;; parent's ctx free.
                (doseq [child (agents-under @state (:ctx handle))] (dispose! child))
                (try
                  ((:dispose! handle))
                  (catch Throwable t
                    ;; a failed teardown is reported, not thrown: the ctx is
                    ;; released and the capability withdrawn either way, so
                    ;; nothing is left half-owned
                    (srce/log! scope :error "agent teardown failed" t)))
                (release-subtree! scope (:ctx handle))
                (swap! state (fn [s]
                               (-> s
                                   (update :handles dissoc id)
                                   (update :order (fn [order]
                                                    (into [] (remove #{id}) order))))))
                (emit! scope :agent/disposed {:id id :agent (:agent handle)})
                nil))]
      (merge
       {:register-factory!
        (fn [ctx factory]
          (inside! ctx)
          (when-not (fn? factory)
            (throw (ex-info "an agent factory is a fn of (ctx opts)"
                            {:type :kmet.app/bad-agent-factory})))
          (loop []
            (let [v @state]
              (if-let [taken (:factory v)]
                (throw (ex-info (str "an agent factory is already registered ("
                                     (:owner taken) ")")
                                {:type :kmet.app/factory-taken
                                 :owner (:owner taken)}))
                (if (compare-and-set! state v (assoc v :factory {:owner (:name ctx)
                                                                 :f factory}))
                 ;; the disposer removes *this* factory only: a restarted
                 ;; driver's old disposer must not clear its replacement
                  (fn []
                    (swap! state (fn [s]
                                   (if (identical? (:f (:factory s)) factory)
                                     (assoc s :factory nil)
                                     s)))
                    nil)
                  (recur))))))

        :create! create!

        :resume! (fn
                   ([ctx id] (create! ctx {:resume id}))
                  ;; a resumed agent is still that agent's composition, so the
                  ;; preset it was created with travels with the resume
                   ([ctx id opts] (create! ctx (assoc opts :resume id))))

        :get (fn [ctx id]
               (inside! ctx)
               (:agent (get-in @state [:handles id])))

        :list (fn [ctx]
                (inside! ctx)
                (let [{:keys [handles order]} @state]
                  (into [] (keep #(some-> (get handles %) summary)) order)))

        :roots (fn [ctx]
                 (inside! ctx)
                 (let [{:keys [handles order]} @state
                       agent-ctxs (into #{} (map (comp :ctx val)) handles)]
                   (into []
                         (keep (fn [id]
                                 (let [handle (get handles id)]
                                   (when-not (contains? agent-ctxs (:parent (:ctx handle)))
                                     (summary handle)))))
                         order)))

        :dispose! dispose!

        :send! send!

        :submit! (fn [handle text opts]
                   (let [handle (live handle)]
                     (dispatch handle :submit!
                               [text
                                ;; `:record?` is the caller saying its queue is
                                ;; durable (the wire's): the enqueue fact lands
                                ;; where the message is delivered, and the
                                ;; handle appends the delivery when it gets
                                ;; there. An image prompt starts a run — the
                                ;; user entry is the log's own — so it records
                                ;; nothing (see `send!`).
                                (if (and (:record? opts)
                                         (not (seq (get-in opts [:run-opts :images]))))
                                  (assoc opts
                                         :on-deliver! (fn [text target]
                                                        ((:enqueue! projections)
                                                         (session! handle) text
                                                         {:target target})))
                                  opts)])))
        :flush-compaction-queue! (fn [handle will-retry]
                                   (dispatch handle :flush-compaction-queue! [will-retry]))

        :steer! (fn [handle msg] (send! handle msg {:target :next-step}))

        :follow-up! (fn [handle msg] (send! handle msg {:target :next-turn}))

        :inbox (fn [ctx handle]
                 (inside! ctx)
                 ((:inbox projections) (session! (live handle))))

        :inject! (fn
                   ([handle msg] (dispatch handle :inject! [msg nil]))
                   ([handle msg opts] (dispatch handle :inject! [msg opts])))

        :cancel! (fn [handle opts] (dispatch handle :cancel! [opts]))

        :when-idle (fn [handle] (dispatch handle :when-idle []))

        :status (fn [handle] (dispatch handle :status []))}
      ;; the settings and session ops have one shape — take the handle, dispatch
      ;; by name — so the table is generated from the measured lists rather than
      ;; repeating the same closure per op
       (into {}
             (map (fn [op] [op (fn [handle & args] (dispatch handle op args))]))
             (concat setting-ops session-ops))))))

(defn- serve
  "The `:agents` row: the registry over the injected projections, provided into
   the mount site's scope so sibling rows see it."
  [ctx _config]
  (let [scope (:parent ctx)]
    (srce/provide! ctx :agents (service scope (srce/get ctx :projections))
                   {:in scope})))

(srce/defplugin plugin
  {:name :agents
   :inject [:projections]
   :provide #{:agents}
   :events
   {:agent/created {:mode :emit :domain :app
                    :doc "Fired after an agent's handle is published, carrying the
                          id and the bare agent — never the child context, which
                          is cyclic. The registry is the only emitter: a handle
                          exists before this fires."}
    :agent/disposed {:mode :emit :domain :app
                     :doc "Fired after an agent's child context is released and
                           its handle withdrawn, carrying the id and the bare
                           agent it was. A listener that still holds the handle
                           can no longer dispose it through the registry."}}
   :apply serve})
