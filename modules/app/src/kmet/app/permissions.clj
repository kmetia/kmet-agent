(ns kmet.app.permissions
  "The `:permissions` policy and the `:approvals` delivery seam — one decision,
   two keys.

   `:permissions` decides whether a tool call may run and costs nothing when it
   says so:

     {:policy    (fn [ctx tool-call] :allow | :deny | :ask
                                   | {:decision … :reason …})
      :remember! (fn [ctx tool-call scope])}   ; optional; scope is :once,
                                               ; :agent or :workspace

   `:approvals` answers the `:ask` verdict, and it is the only thing it does —
   a generic dialog belongs to `:ui`:

     {:ask! (fn [ctx request] :allow | :deny | {:decision … :scope …})}

   The request an `:ask` delivers is the tool call plus the run's `:signal` and,
   when the policy gave one, its `:reason` — the dialog can say why it is
   asking. `ask!` owns its own timeout and abort: the signal lets a dialog give
   up, but the gate does not impose a deadline — a provider that never returns
   blocks the call it was asked about, which is what an unanswered question
   means.

   The split is the point: deciding is synchronous and per-agent — the policy
   is provided on the agent's own ctx by whatever composes that agent, a preset
   or `setup`, because the kernel forbids a child shadowing a service its
   ancestor provides — while asking a human is I/O that blocks and belongs to
   whichever front end can answer. `:deny` becomes the hook result the loop
   already understands, so no tool signature changes.

   **A remembered answer is asked once.** `remembering` wraps a policy so a tool
   this agent (or this workspace) already allowed is allowed without asking
   again, and an approver's answer at a scope is recorded in the agent's own
   memory service (`memory-key`, provided per agent by the driver over one
   workspace-wide set). The row mounts it by default; a `:remember!` in the row's
   config replaces the built-in memory with the caller's.

   **The gate is the loop's pre-tool waterfall, and it runs last.** Extension
   tool-call hooks run first (they may rewrite the arguments), then the policy
   judges the effective call, so a rewrite cannot smuggle a call past it. A
   policy that throws, or returns something that is not a verdict, is a deny —
   the failure mode of an approval gate must never be \"allow\".

   A ctx with no `:permissions` allows: the gate exists only where an app
   mounted it. A ctx with a policy but no `:approvals` denies an `:ask` —
   \"nobody can ask\" is not \"yes\"."
  (:require [kmet.app.debug :as debug]
            [kmet.srce.core :as srce]))

;; ── the values ────────────────────────────────────────────────────────────

(def verdicts
  "Every answer a policy or an approver may give."
  #{:allow :deny :ask})

(def scopes
  "Where a remembered approval applies: this call, this agent, this workspace."
  #{:once :agent :workspace})

(defn- check-fns!
  "Throw for a service value that cannot answer its contract. Names the op, so
   a bad provider fails at the mount rather than at the first tool call."
  [service required optional kind]
  (when-not (map? service)
    (throw (ex-info (str kind " service must be a map, got " (pr-str service))
                    {:type :kmet.app/bad-service :service kind})))
  (doseq [k required]
    (when-not (fn? (get service k))
      (throw (ex-info (str kind " service is missing " (pr-str k))
                      {:type :kmet.app/bad-service :service kind :missing k}))))
  (doseq [k optional]
    (when-some [f (get service k)]
      (when-not (fn? f)
        (throw (ex-info (str kind " service has a non-fn " (pr-str k))
                        {:type :kmet.app/bad-service :service kind :bad k})))))
  service)

(defn policy
  "A `:permissions` value over POLICY, with an optional REMEMBER! (ctx
   tool-call scope) that records a decision an `:ask` returned a scope for."
  ([decide] (policy decide nil))
  ([decide remember!]
   (check-fns! {:policy decide :remember! remember!} [:policy] [:remember!]
               "permission")))

(defn approver
  "An `:approvals` value over ASK!."
  [ask!]
  (check-fns! {:ask! ask!} [:ask!] [] "approval"))

(defn allow-all
  "The default policy: nothing is gated. What a ctx that mounted no policy
   would do anyway — installing it says so explicitly."
  [_ctx _tool-call]
  :allow)

(defn no-approver
  "The default approver: there is nobody to ask, so an `:ask` is a deny. A
   headless run and a server without a dialog both get this until a front end
   provides `:approvals`."
  [_ctx _request]
  {:decision :deny
   :reason "no approval channel is mounted — this run cannot ask"})

(def cancelled-answer
  "What a front end answers an open question with once its run was cancelled:
   the question is about a run that is gone, so the call is denied rather than
   parked. The wire ask and the TUI's dialog both answer this one value."
  {:decision :deny :reason "the run was cancelled"})

(def policies
  "The policies a composition can name as data, beside the fn form: a profile or
   a `--patch` layer is EDN, so mounting the gate means naming one of these."
  {:allow-all allow-all
   :ask-all (fn [_ctx _tool-call] :ask)})

(defn- verdict
  "The keyword VERDICT of an answer, or nil when it is not one. A map answer
   carries `:decision`; anything else is a bug in the provider."
  [answer]
  (let [v (if (map? answer) (:decision answer) answer)]
    (when (contains? verdicts v) v)))

(defn- reason-of
  "The reason an answer carries, if any."
  [answer]
  (when (map? answer) (:reason answer)))

(defn- block
  "The hook-shaped block result the loop settles a call with."
  [reason]
  {:block true :reason reason})

;; ── remembering an answer ─────────────────────────────────────────────────

(def memory-key
  "Where an agent's remembered approvals live: a service on the agent's own ctx,
   provided by whatever composes the agent (the driver provides one per agent,
   over one workspace-wide set shared by the whole composition).

   A service rather than a cell of this namespace's, because remembering must be
   per agent (`:agent`) and per composition (`:workspace`) at once, and a
   process-global set would answer for every agent in the process."
  :approvals-memory)

(defn memory
  "A memory for `remembering`: AGENT's own decisions plus WORKSPACE, the set the
   caller shares with every agent of one composition."
  [workspace]
  {:agent (atom #{}) :workspace workspace})

(defn remember!
  "Record REQUEST's tool at SCOPE in CTX's memory: `:agent` for this agent,
   `:workspace` for the composition, `:once` for nothing. A ctx without a memory
   service records nothing."
  [ctx request scope]
  (when-let [m (srce/get ctx memory-key)]
    (let [tool (:tool-name request)]
      (cond
        (= :agent scope) (swap! (:agent m) conj tool)
        (= :workspace scope) (swap! (:workspace m) conj tool)
        :else nil))))

(defn remembered?
  "Is REQUEST's tool already allowed on CTX's chain — by this agent, or by anyone
   in this workspace?"
  [ctx request]
  (when-let [m (srce/get ctx memory-key)]
    (let [tool (:tool-name request)]
      (or (contains? @(:workspace m) tool)
          (contains? @(:agent m) tool)))))

(defn remembering
  "DECIDE wrapped so a human is asked once per tool per scope: a call the policy
   would *ask* about, whose tool this agent (or this workspace) already allowed,
   is allowed without asking — and a decision an approver gave a scope to is
   recorded where it applies.

   The memory only replaces the question, never the policy: a policy that denies
   still denies, because a remembered `always allow` is an answer to `may this
   run?`, not a second policy on top of it.

   The tool is the key, not the call: `always allow bash` means bash with any
   arguments, which is what a scope names. A ctx with no memory service behaves
   exactly as DECIDE."
  [decide]
  (policy (fn [ctx request]
            (let [answer (decide ctx request)]
              (if (and (= :ask (verdict answer)) (remembered? ctx request))
                :allow
                answer)))
          remember!))

;; ── the gate ──────────────────────────────────────────────────────────────

(defn decide
  "POLICY's verdict for REQUEST, normalized to `:allow`, `:deny` or `:ask`.
   A policy that throws or answers something else is a deny, named so a broken
   provider is diagnosable from the tool result."
  [permission-service ctx request]
  (let [answer (try ((:policy permission-service) ctx request)
                    (catch Throwable t {:decision :deny
                                        :reason (str "permission policy error: "
                                                     (ex-message t))}))]
    (if-some [v (verdict answer)]
      {:decision v :reason (reason-of answer)}
      {:decision :deny
       :reason (str "permission policy returned " (pr-str answer)
                    " — expected :allow, :deny or :ask")})))

(defn check
  "The pre-tool gate: nil when REQUEST may proceed, otherwise the block result
   the loop settles the call with. REQUEST is a tool call —
   `{:tool-name … :args … :signal … :tool-call-id …}` — so the policy and the
   approver see the same thing.

   `:permissions` and `:approvals` are read from CTX, nearest on the chain, so
   an agent's child ctx decides for that agent. No policy at all allows; a
   policy with no approver denies an `:ask`."
  [ctx request]
  (when-let [permission-service (srce/get ctx :permissions)]
    (let [{:keys [decision reason]} (decide permission-service ctx request)]
      (case decision
        :allow nil
        :deny (block (or reason "denied by the permission policy"))
        :ask (let [ask (cond-> request
                         reason (assoc :reason reason))
                   answer (try ((:ask! (or (srce/get ctx :approvals) {:ask! no-approver}))
                                ctx ask)
                               (catch Throwable t
                                 {:decision :deny
                                  :reason (str "approval request failed: "
                                               (ex-message t))}))]
               (if (= :allow (verdict answer))
                 (do (when-let [remember! (:remember! permission-service)]
                       ;; only a defined scope is remembered: an approver that
                       ;; invents one gets its answer honored, not persisted.
                       ;; A provider that cannot record the decision does not
                       ;; lose it — the human already said yes.
                       (when-some [scope (:scope answer)]
                         (when (contains? scopes scope)
                           (try (remember! ctx request scope)
                                (catch Throwable t
                                  (debug/log "permissions: remember! failed for "
                                             (:tool-name request) " at scope "
                                             scope ": " t))))))
                     nil)
                 (block (or (reason-of answer) "the approval request was denied"))))))))

(defn gate
  "HOOK with the permission check after it: HOOK may rewrite the arguments or
   block, and then the policy judges the effective call at CTX. SIGNAL rides
   the request so an `:ask` can be aborted with the run.

   Returns nil when there is no hook and the policy allows — the shape
   `invoke/prepare-tool-call` and the run_code tool hooks expect."
  [hook ctx signal]
  (fn [payload]
    (let [rewritten (when hook (hook payload))
          ;; `contains?`, not truthiness: a hook may rewrite the arguments to
          ;; nil (a tool that takes none), and the policy must judge that call
          request (cond-> (assoc payload :args (if (contains? rewritten :args)
                                                 (:args rewritten)
                                                 (:args payload)))
                    signal (assoc :signal signal))]
      (or (when-not (:block rewritten) (check ctx request))
          rewritten))))

;; ── the rows ──────────────────────────────────────────────────────────────

(defn- policy-fn
  "NAMED as a policy fn: a fn is itself, a keyword names one of `policies`, and
   anything else is a bad row — named now, not at the first tool call."
  [named]
  (if (fn? named)
    named
    (or (get policies named)
        (throw (ex-info (str "unknown permission policy " (pr-str named)
                             " — name one of " (pr-str (vec (keys policies)))
                             ", or pass a fn")
                        {:type :kmet.app/bad-service
                         :service :permissions
                         :policy named})))))

(defn- serve-policy
  "The `:permissions` row: provide a policy (the row's `:config` may name a
   stricter one) into the mount site's scope, so siblings and child ctxs see it
   while the entry stays this fiber's effect.

   A composition is data (`profiles.edn` and `--patch FILE` are EDN), so the
   policy may also be **named**: `:allow-all` and `:ask-all` are the two a
   profile can mount without code."
  [ctx config]
  (let [decide (policy-fn (or (:policy config) :allow-all))
        ;; a caller that supplies its own :remember! keeps the decision where it
        ;; wants it; the default is the per-agent/per-workspace memory the
        ;; driver provides
        remember! (:remember! config)]
    (srce/provide! ctx :permissions
                   (if (fn? remember!)
                     (policy decide remember!)
                     (remembering decide))
                   {:in (:parent ctx)})))

(defn- serve-approvals
  "The `:approvals` row: provide the delivery seam. The default has no one to
   ask, so it denies. A front end supplies the real one through this row's
   config (`{:id :approvals :config {:ask! …}}`), or replaces the row
   wholesale — the TUI's `:bundle/terminal` does, with the provider that shows
   the Yes/No dialog on the running terminal (`kmet.ui.tui`). Neither provides
   the key beside this row, which the kernel refuses as a duplicate."
  [ctx config]
  (srce/provide! ctx :approvals
                 (approver (or (:ask! config) no-approver))
                 {:in (:parent ctx)}))

(srce/defplugin permissions
  {:name :permissions
   :provide #{:permissions}
   :apply serve-policy})

(srce/defplugin approvals
  {:name :approvals
   :provide #{:approvals}
   :apply serve-approvals})
