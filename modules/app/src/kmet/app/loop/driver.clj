(ns kmet.app.loop.driver
  "The loop's driver: the agent state a run starts from, and the `:agentLoop`
   row that registers the `:agents` factory.

   **The state is derived once, here.** `agent-state` is the whole option
   derivation a run needs — provider and model resolution, the tool table, the
   compaction and retry settings, the prompt — and it is the one place it
   happens. kmet1's engine calls it with the ctx its front end named; kmet2's
   driver calls it with the child ctx the registry minted. A second copy would
   be a run option wired in one place and forgotten in the other, which is the
   drift the app layer exists to prevent.

   What the state does *not* take from here is its event sink: `:on-event` is
   the caller's, because the front end owns what it renders and the loop only
   reports."
  (:require [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.loop :as loop]
            [kmet.app.loop.state :as loop-state]
            [kmet.app.packages :as packages]
            [kmet.app.permissions :as permissions]
            [kmet.app.model-resolver :as resolver]
            [kmet.app.project-context :as context]
            [kmet.app.sandbox :as sandbox]
            [kmet.app.session.compaction :as manual]
            [kmet.app.session.lifecycle :as lifecycle]
            [kmet.app.session.core :as session]
            [kmet.app.session.runtime :as runtime]
            [kmet.app.session.submission :as submission]
            [kmet.app.tools.registry :as tools]
            [kmet.app.tools.shell :as shell]
            [kmet.ai.models :as models]
            [kmet.srce.core :as srce]))

;; ── extension hook chains ─────────────────────────────────────────────────

(defn- extension-before-tool-call
  "Chain extension tool-call hooks (pi: beforeToolCall) — the runtime wires
   them for every run, so write-reject / edit-warn hooks fire headless too.
   Every hook runs; the value is nil | {:args ...} | the first blocking hook's
   result verbatim, so its :terminate reaches the call's block result (a later
   block or rewrite is ignored once blocked)."
  [ctx]
  (loop [hooks (extensions/get-tool-call-hooks)
         blocked nil
         args (:args ctx)]
    (if-let [hook (first hooks)]
      (let [r (try (hook (assoc ctx :args args))
                   (catch Exception e
                     {:block true
                      :reason (str "tool-call hook error: " (ex-message e))}))]
        (cond
          (:block r)
          (recur (next hooks) (or blocked r) args)
          (contains? r :args)
          (recur (next hooks) blocked (:args r))
          :else
          (recur (next hooks) blocked args)))
      (or blocked (when (not= args (:args ctx)) {:args args})))))

(defn- extension-after-tool-call
  "Chain extension tool-result hooks (pi: afterToolCall). Returns the
   (possibly rewritten) result."
  [ctx]
  (reduce (fn [result hook]
            (if-let [r (try (hook (assoc ctx :result result
                                         :is-error (:is-error result false)))
                            (catch Exception e
                              {:content (str "tool-result hook error: " (ex-message e))
                               :is-error true}))]
              (cond-> result
                (:content r) (assoc :content (:content r))
                (contains? r :is-error) (assoc :is-error (:is-error r)))
              result))
          (:result ctx)
          (extensions/get-tool-result-hooks)))

;; ── the state ─────────────────────────────────────────────────────────────

(defn- prompt-options
  "The options the run's prompt is built from: what the config says, the run's
   cwd, the project context files, and the tool table the state will resolve
   through its ctx."
  [config ctx {:keys [cwd default-tools]}]
  {:custom-prompt (cfg/get-custom-prompt config)
   :append-prompt (cfg/get-append-system-prompt config)
   :cwd cwd
   :context-files (context/load-project-context-files
                   (cfg/get-agent-dir) (str (fs/cwd)))
   :tools (loop-state/active-tools-for
           (loop-state/ctx-tool-table ctx)
           {:default-tools default-tools})})

(defn agent-state
  "The loop's AgentState for CTX from CONFIG and the run's resolved pieces:

   :session         the session the run is over (its cwd line and its log)
   :system-prompt-fn the builder the state calls when the prompt must be
                    rebuilt (an active-tools change) — the caller's, never a
                    process default
   :on-event        the run's event sink, or nil (the loop reports; the front
                    end decides what to do with it)
   :model/:provider overrides; the config's when absent
   :cwd             the runtime cwd override; the session's, else the process's
   :agent-opts      extra/overriding make-agent-state options (the escape
                    hatch, applied last but for the ctx)

   Returns {:state <AgentState> :cwd <runtime cwd>} — the cwd is the engine's
   to keep for the next prompt rebuild, and the state carries the rest."
  [config ctx {:keys [session model provider cwd system-prompt-fn on-event
                      agent-opts]}]
  ;; no default is the rule, so a missing builder is a named failure here
  ;; rather than an NPE where the prompt is built
  (when-not (fn? system-prompt-fn)
    (throw (ex-info "a run's state needs a prompt builder: name one (the ctx's :systemPrompt row, or a caller's own)"
                    {:type :kmet.app/no-system-prompt})))
  (let [provider (or provider (cfg/get-provider config))
        model (or model (models/resolve-config-model config))
        model-rec (models/get-model provider model)
        default-tools (tools/resolve-default-tools (cfg/get-default-tools config))
        ;; the prompt's cwd line is the runtime cwd (the resumed session's,
        ;; or a subagent's), but everything project-scoped — context files
        ;; included — stays with the launch dir
        runtime-cwd (or cwd (session/session-cwd session) (str (fs/cwd)))
        prompt-opts (prompt-options config ctx {:cwd runtime-cwd
                                                :default-tools default-tools})
        system-prompt (apply system-prompt-fn (mapcat identity prompt-opts))
        thinking (let [raw (:thinking config :off)]
                   (if (:reasoning model-rec)
                     (models/clamp-thinking-level model-rec raw)
                     raw))
        retry-settings (cfg/get-retry-settings config)
        loop-guard-settings (cfg/get-loop-guard-settings config)
        ;; select-keys omits an absent setting but keeps an explicit nil, so
        ;; make-agent-state's own defaults survive an absent one
        timeout-opts (select-keys config [:http-idle-timeout-ms :http-total-timeout-ms])
        state (apply loop/make-agent-state
                     (mapcat identity
                             (merge
                              {:model model
                               :provider provider
                               :system system-prompt
                               :system-prompt-opts prompt-opts
                               :system-prompt-fn system-prompt-fn
                               :session session
                               :default-tools default-tools
                               :context-window (or (:context-window model-rec)
                                                   (:context-window config))
                               :compact-reserve-tokens (or (:compact-reserve-tokens config)
                                                           16384)
                               :compact-model-overrides (:compact-model-overrides config)
                               :branch-summary-reserve-tokens
                               (or (:reserve-tokens (cfg/get-branch-summary-settings config))
                                   16384)
                               :compact-token-threshold (:compact-token-threshold config)
                               :auto-compact (get config :auto-compact true)
                               :steering-mode (or (:steering-mode config) :all)
                               :follow-up-mode (or (:follow-up-mode config) :one-at-a-time)
                               :keep-recent-tokens (or (:keep-recent-tokens config) 20000)
                               :block-images (cfg/get-block-images config)
                               :thinking thinking
                               ;; pi: retry settings (settings.edn :retry block —
                               ;; enabled gates max-retries to 0)
                               :max-retries (if (:enabled retry-settings)
                                              (:max-retries retry-settings)
                                              0)
                               :base-delay-ms (:base-delay-ms retry-settings)
                               :max-agent-delay-ms (:max-agent-delay-ms retry-settings)
                               ;; Repeat-loop guard (kmet-specific): settings.edn
                               ;; :loop-guard block — enabled gates threshold to 0
                               :loop-guard-enabled (:enabled loop-guard-settings)
                               :loop-guard-threshold (:threshold loop-guard-settings)
                               :thinking-loop-guard-enabled
                               (get config :thinking-loop-guard-enabled true)
                               :before-tool-call extension-before-tool-call
                               :after-tool-call extension-after-tool-call
                               :on-event on-event}
                              timeout-opts
                              agent-opts
                              ;; the state reads the ctx resolved above, so its
                              ;; tool table and the prompt's are the same table
                              {:ctx ctx})))]
    ;; the session's scoped-model list is the config's (pi: resolveModelScope at
    ;; runtime creation): seeded here, so kmet1's engine and every handle start
    ;; with the same scope rather than only the bootstrapped one
    (loop-state/init-scoped-models! state config)
    {:state state :cwd runtime-cwd}))

;; ── the handle ────────────────────────────────────────────────────────────

(defn- resolve-create-opts
  "CREATE-OPTS with kmet1's `--model` pattern resolved against the catalog:
   `provider/model[:thinking]`, the provider inferred when it is absent, as
   `kmet --model` has always meant it. A pattern the catalog does not have fails
   here by name — kmet1 prints the same error and exits 1 — instead of becoming a
   run that dies inside its first request."
  [opts]
  (if-let [pattern (:model opts)]
    (let [{:keys [provider model-id thinking-level warning error]}
          (resolver/resolve-cli-model (:provider opts) pattern (models/get-models))]
      (when error
        (throw (ex-info error {:type :kmet.app/unknown-model :model pattern})))
      (when warning
        (binding [*out* *err*] (println "Warning:" warning)))
      (cond-> opts
        provider (assoc :provider provider)
        model-id (assoc :model model-id)
        (and thinking-level (nil? (:thinking opts))) (assoc :thinking thinking-level)))
    opts))

(defn- session-for
  "A fresh session for a new agent over LOG (the `:sessions` service), placed
   the way kmet1's engine places one: the cwd-encoded directory under the
   config's sessions dir."
  [log config cwd]
  (let [dir (session/session-dir-for-cwd (cfg/get-session-dir config) cwd)]
    (fs/create-dirs dir)
    ((:create! log) {:dir dir :cwd cwd})))

(defn- resume-session
  "The stored session ID (or a session file path) names over LOG: the session
   its file holds, loaded through the log so the service indexes it by id.

   Throws :kmet.app/unknown-session when nothing matches. An agent brought back
   as a fresh session would answer a different conversation and say nothing,
   which is the failure this replaces."
  [log config id]
  (let [path (session/resolve-session-path id (cfg/get-session-dir config))]
    (or (when path ((:load log) path))
        (throw (ex-info (str "no stored session is " (pr-str id))
                        {:type :kmet.app/unknown-session
                         :id id
                         :session-dir (cfg/get-session-dir config)})))))

(defn- agent-handle
  "The handle the registry publishes for one agent: the value a front end
   reads, the run it owns, and the control surface
   `kmet.app.agents/handle-contract` requires.

   The value is `kmet.app.session.runtime`'s shape — `:agent-state`,
   `:session`, `:config`, `:cwd`, `:turn-running?` — so a front end that reads
   a handle through that namespace's accessors (`model`, `session`,
   `thinking-level`, `compacting?`, `queues`, …) needs no second accessor set.
   `:agent` is the registry contract's bare agent; `:session` is the thunk the
   contract asks for because a switch replaces the live state record.

   A run starts on the first message and settles on its own thread — the
   registry's `send!` is the wake-up, never a block. A message that arrives
   while a run is live goes into the loop's own queue (`:next-step` mid-turn,
   `:next-turn` at the next boundary), which the loop drains itself. Images
   are a prompt's, not a queue's: a send that carries them either starts a run
   or is refused, because the loop's queues carry text (pi: prompt(text,
   images) vs steer/followUp(text)).

   The settings ops write the same atoms the accessors read, so a front end
   changes one agent and never the process: `/model` on one handle leaves a
   sibling's alone.

   A run binds the extension surface for its prompt (`extensions/*agent-context*`):
   the agent's session, the runtime capabilities (`extension-runtime`), the
   ExtensionContext keys only the loop knows (`extension-context`), and — when
   a front end published one on this agent — that agent's front-end surface
   (:mode, the UI port, the context/entry sinks). An extension event handler,
   tool hook or custom tool called by the run therefore sees the agent it is
   running inside, not another agent's front end or the process's; outside a
   run a command handler or an extension's own fiber still reads the process
   binding."
  [child {:keys [id session config prompt-fn projections approvals-memory]}]
  (let [;; The shell settings are this agent's, on this agent's ctx: a per-agent
        ;; value has to be the only provider on its chain (the kernel forbids a
        ;; child shadowing an ancestor's service), which is why this is not a
        ;; root row — the same reason :permissions is not one
        ;; (docs/architecture.md § Service catalog).
        _ (srce/provide! child :shell (atom (shell/value config)))
        ;; The script boundary is this agent's too (9c), and the same rule
        ;; applies: a root row could never be narrowed for one agent. A
        ;; preset runs in `:setup`, before this factory, so a preset that
        ;; named a boundary already provided it — the factory fills in the
        ;; default for every agent that did not (kmet.app.sandbox).
        _ (when-not (srce/get child :sandbox)
            (srce/provide! child :sandbox sandbox/default-value))
        ;; What a human already allowed for this agent (`:agent`) and for this
        ;; workspace, which every agent of the composition shares: the permission
        ;; row's remembering policy reads and writes it, and it is a service for
        ;; the same reason :shell is — one provider on this agent's chain, so two
        ;; agents remember separately while one workspace remembers together.
        _ (srce/provide! child permissions/memory-key
                         (or approvals-memory
                             (permissions/memory (atom #{}))))
        engine-ref (atom nil)
        {:keys [state] runtime-cwd :cwd}
        (agent-state config child
                     {:session session
                      :system-prompt-fn prompt-fn
                      ;; a manual compaction deferred by a running prompt
                      ;; starts when the prompt settles (the handle's
                      ;; `:request-compaction!`, rpc's `compact`); the loop's
                      ;; own post-run check has run by then
                      :on-event (fn [ev]
                                  (when (= :agent-settled (:type ev))
                                    (manual/start-pending-compactions! @engine-ref)))})
        live (atom state)
        running? (atom false)
        last-run (atom nil)
        disposed? (atom false)
        ;; the front end's value over the agent state: the same field atoms a
        ;; session engine holds, so `kmet.app.session.runtime` reads it and
        ;; the settings ops below write it without a second derivation
        engine (runtime/map->Engine
                {:agent-state live
                 ;; the surface a front end published for this agent (its
                 ;; mode, UI port and context/entry sinks), when one owns the
                 ;; agent rather than the process; a run binds it so its
                 ;; handlers read their own front end
                 :front-end (atom nil)
                 :session (fn [] (:session @live))
                 :config (atom config)
                 :subscribers (atom [])
                 :last-run last-run
                 :disposed? disposed?
                 :cwd (atom runtime-cwd)
                 ;; the process's expansion chain: a queued or submitted text
                 ;; reaches the run with its skill commands and prompt
                 ;; templates replaced, the same way kmet1's engine does
                 :expand-input packages/expand-input
                 :turn-running? running?
                 :compaction-queued (atom [])
                 :pending-compaction (atom [])
                 :pending-ui (atom {})
                 :run-callbacks (atom nil)})]
    (reset! engine-ref engine)
    (letfn [(settle! [] (reset! running? false))
            (queue! [text target]
              (if (= :next-step target)
                (loop-state/steer! @live text)
                (loop-state/follow-up! @live text)))
            ;; start a run if this call claims it, else queue behind the one
            ;; another caller claimed. Answers which happened: the submission
            ;; chain reports it, and a caller that only wakes the agent ignores
            ;; it.
            ;; the one start-or-queue rule: an idle call starts, a call behind
            ;; a live run queues. RUN-OPTS are the caller's delivery additions:
            ;; rpc's images (the queues carry text, so an image prompt that
            ;; cannot start is refused rather than silently stripped) and the
            ;; queue fact's entry, which folds away once the message is
            ;; delivered.
            (record-delivery! [run-opts]
              (when-let [entry (:entry run-opts)]
                ((:deliver! projections) (:session @live) [(:id entry)])))
            (refused-images! [target]
              (ex-info (str "images can only start a prompt: a queued message ("
                            (name target) ") carries text")
                       {:type :kmet.app/unsupported-images :target target}))
            (deliver! [text target run-opts]
              (let [images (vec (:images run-opts))]
                (if (and (not @running?) (start! text images))
                  (do (record-delivery! run-opts) :started)
                  (if (seq images)
                    (throw (refused-images! target))
                    (do (queue! text target)
                        (record-delivery! run-opts)
                        :steered)))))
            ;; an extension's sendCustomMessage (pi: ctx.sendCustomMessage):
            ;; the four branches driver/send-message-capability has, over this
            ;; agent's live state — a trigger while idle starts a run, a
            ;; streaming message steers/follows up or defers, and next-turn
            ;; never triggers
            (inject! [message opts]
              (manual/reject-submit! engine)
              (let [msg {:role :custom
                         :custom-type (or (:custom-type message) :custom)
                         :content (:content message)
                         :display (if (nil? (:display message)) true (:display message))
                         :details (:details message)}
                    busy? @running?]
                (cond
                  (= :next-turn (:deliver-as opts))
                  (loop-state/queue-next-turn-message! @live msg)

                  (and busy? (not (false? (:trigger-turn opts))))
                  (if (= :follow-up (:deliver-as opts))
                    (loop-state/follow-up! @live msg)
                    (loop-state/steer! @live msg))

                  (:trigger-turn opts)
                  (do (loop-state/append-custom-message! @live msg)
                      (start! nil nil))

                  busy? (loop-state/defer-custom-message! @live msg)

                  :else (loop-state/append-custom-message! @live msg))
                nil))
            ;; the runtime-owned extension capabilities (pi: bindCore) as this
            ;; agent answers them: an extension's setModel / sendUserMessage /
            ;; sendCustomMessage / active-tool calls act on this agent through
            ;; the same fns a front end calls, never on a process-global slot
            (extension-runtime []
              {:set-model (fn [model]
                            (when (and model (models/has-configured-auth model))
                              (runtime/switch-model! engine model nil)
                              true))
               :get-thinking-level (fn [] (runtime/thinking-level engine))
               :set-thinking-level (fn [level]
                                     (when (models/valid-thinking-level? level)
                                       (runtime/set-thinking-level! engine level))
                                     nil)
               :send-user-message
               (fn [text {:keys [deliver-as expand-prompt-templates?]}]
                 (let [text (if expand-prompt-templates?
                              (packages/expand-input text)
                              text)]
                   (when text
                     (deliver! text (if (= :steer deliver-as) :next-step :next-turn) nil)))
                 nil)
               :send-message! (fn [message opts] (inject! message opts))
               :get-active-tools (fn []
                                   (mapv :name (loop-state/active-tools @live)))
               :set-active-tools (fn [names]
                                   (loop-state/set-active-tools! @live names)
                                   nil)})
            ;; the ExtensionContext keys a run owns — the live agent's model,
            ;; scoped set, thinking level, cwd and pending state, and the
            ;; controls that act on this agent. Everything else (:mode,
            ;; :has-ui, the front end's session flows) still comes from the
            ;; front end's `:build-context` or the headless default.
            (extension-context []
              (merge (runtime/extension-context engine)
                     {:cwd (runtime/cwd engine)
                      :is-idle (fn []
                                 (and (not @running?)
                                      (not (runtime/compacting? engine))))
                      :has-pending-messages (fn [] (boolean (seq (runtime/queues engine))))
                      :abort (fn [] (lifecycle/cancel-run! engine))
                      :compact (fn [& [instructions]]
                                 (manual/request-compaction! engine instructions nil))}))
            ;; true when this call claimed the run: the CAS is what decides, so
            ;; a message arriving as another thread starts a run is queued by
            ;; the caller rather than dropped. A start that throws before it
            ;; has a run to settle must not leave the handle claiming one:
            ;; every later message would queue behind a run that does not exist.
            ;;
            ;; The prompt binds the extension surface for its whole lifetime,
            ;; and Clojure's binding conveyance carries it into the run's
            ;; future: a handler, hook or custom tool the loop calls sees the
            ;; agent it is running inside, not the process's last install.
            (start! [text images]
              (boolean
               (when (compare-and-set! running? false true)
                 (try
                   (binding [extensions/*agent-context*
                             {:session #(runtime/session engine)
                              :runtime (extension-runtime)
                              :context extension-context
                              :front-end (some-> (:front-end engine) deref)}]
                     (reset! last-run
                             (loop/run-agent-turn
                              @live
                              {:message text
                               :images images
                               :on-done (fn [_] (settle!))
                               :on-error (fn [_] (settle!))})))
                   (catch Throwable t
                     (settle!)
                     (throw t))))))]
      (-> engine
          (assoc
           :id id
           :ctx child
           :agent state
           ;; the front end's submission chain over this handle: input
           ;; hooks, expansion, compaction queueing, then the handle's own
           ;; start-or-queue rule (pi: session.prompt). A queued message is
           ;; not recorded in the inbox here: the TUI is a single front end
           ;; and the loop's log carries the message when it is consumed; the
           ;; server's wire submit (10a) records the fact where the queue is
           ;; durable.
           :submit! (fn [text opts]
                      (submission/submit! engine text (assoc opts :deliver! deliver!)))
           :flush-compaction-queue! (fn [will-retry]
                                      (submission/flush-compaction-queue!
                                       engine will-retry
                                       {:start! (fn [text] (start! text nil))}))
           :send! (fn [msg opts]
                    (let [text (if (map? msg) (:text msg) msg)
                          target (:target opts :next-turn)
                          images (vec (:images msg))]
                      ;; a caller that names a queue target means "queue this",
                      ;; so an image cannot ride it even while idle; a start
                      ;; request (:next-turn) may refuse when it loses the race
                      (when (and (seq images) (not= :next-turn target))
                        (throw (refused-images! target)))
                      ;; the entry is the caller's durable queue fact: the
                      ;; delivery (or the queue) folds it away, so a long-lived
                      ;; server's inbox stays a queue rather than a log of
                      ;; everything sent
                      (deliver! text target
                                (cond-> (when (map? msg) (select-keys msg [:images]))
                                  (:entry opts) (assoc :entry (:entry opts))))))
           :inject! (fn
                      ([message] (inject! message nil))
                      ([message opts] (inject! message opts)))
           :cancel! (fn [_opts] (lifecycle/cancel-run! engine))
           :when-idle (fn [] (when @running? (deref @last-run)))
           :status (fn [] (if @running? :running :idle))
           :dispose! (fn []
                       (reset! disposed? true)
                       (lifecycle/cancel-run! engine)))
          ;; the rest is the front end's settings and compaction surface: each
          ;; op is the value's own fn behind a wrapper that supplies this
          ;; handle, so the thinking-level rank rule, the model-change entry and
          ;; the deferred compaction queue keep one implementation each
          (merge (into {}
                       (map (fn [[op f]]
                              [op (fn [& args] (apply f engine args))]))
                       {;; settings
                        :set-model! runtime/set-model!
                        :cycle-model! runtime/cycle-model!
                        :set-thinking-level! runtime/set-thinking-level!
                        :cycle-thinking-level! runtime/cycle-thinking-level!
                        :set-scoped-models! runtime/set-scoped-models!
                        :set-auto-compaction! runtime/set-auto-compact!
                        :set-steering-mode! runtime/set-steering-mode!
                        :set-follow-up-mode! runtime/set-follow-up-mode!
                        :set-block-images! runtime/set-block-images!
                        :set-system-prompt! runtime/set-system-prompt!
                        :apply-default-tools! runtime/apply-default-tools!
                        :set-config! runtime/set-config!
                        :set-run-options! runtime/set-run-options!
                        :set-http-idle-timeout-ms! runtime/set-http-idle-timeout-ms!
                        :set-http-total-timeout-ms! runtime/set-http-total-timeout-ms!
                        :clear-queues! runtime/clear-queues!
                        ;; the manual compaction queue: a request while a prompt
                        ;; runs is deferred to the settle (the state's :on-event
                        ;; drains it), a disposal drops what is queued
                        :request-compaction! manual/request-compaction!
                        :abort-compaction! manual/abort-compaction!
                        :queue-compaction-message! manual/queue-compaction-message!
                        :clear-compaction-queue! manual/clear-compaction-queue!
                        ;; the session lifecycle moves this handle's session in
                        ;; place. No `:install!`: the process-wide extension
                        ;; session is kmet1's front end, and a run's own binding
                        ;; already covers handlers. A switch replaces the live
                        ;; state record the `:session` thunk reads.
                        :new-session! lifecycle/new-session!
                        ;; load-and-switch: the registry's `:resume!` is a
                        ;; different act (create an agent for a stored session)
                        :resume-session! lifecycle/resume!
                        :switch-session! lifecycle/switch-session!
                        :fork! lifecycle/fork!
                        :branch-session! lifecycle/branch-session!
                        :clone! lifecycle/clone!
                        :set-session-name! runtime/set-session-name!
                        :export-html! runtime/export-html!}))))))

;; ── the row ───────────────────────────────────────────────────────────────

(defn- serve
  "The `:agentLoop` row: register the `:agents` factory, so `create!` has one
   and the handle it publishes is a runnable agent. The disposer is the
   registration's, so the factory goes with the fiber.

   The run config is loaded per creation (a settings change reaches the next
   agent) unless the caller names one (`:config` in the create options).

   A create option that is kmet1's CLI override (`:model`, `:provider`) is
   applied the way `kmet --model` applies it, pattern and all
   (`resolve-create-opts`) — an agent's model is an option of the agent, not of
   the process, so two agents of one server can differ.

   `:resume` creates an agent for a *stored session*: the id (or path) is
   resolved over the composition's session dir, and the handle is bound to the
   session its file holds — entries, cwd and all, because `agent-state` takes
   its cwd from the session. An id nothing answers to is a named failure
   (:kmet.app/unknown-session), never a fresh conversation wearing the id.

   `:cwd` places the new session — and so the run's working directory, its
   shell's and every relative path it resolves — in that directory instead of
   the process's. It is the option a front end that serves several worlds needs
   (the server's one agent per connection, each with the cwd its client named);
   a resumed session keeps the cwd its file holds, because that is the world
   the conversation happened in."
  [ctx _config]
  (let [scope (:parent ctx)
        agents (srce/get ctx :agents)
        log (srce/get ctx :sessions)
        projections (srce/get ctx :projections)
        prompt-fn (srce/get ctx :system-prompt)
        cwd (str (fs/cwd))
        ;; the decisions that apply to everything this composition runs: one set
        ;; per driver row, shared by every agent its factory creates
        remembered-workspace (atom #{})]
    ((:register-factory! agents) scope
                                 (fn [child opts]
                                   (let [config (-> (or (:config opts) (cfg/load-config))
                                                    (cfg/apply-cli-overrides
                                                     (resolve-create-opts opts)))
                                         session (or (some->> (:resume opts)
                                                              (resume-session log config))
                                                     (:session opts)
                                                     (session-for log config
                                                                  (or (:cwd opts) cwd)))]
                                     (agent-handle child
                                                   {:id (:id opts)
                                                    :config config
                                                    :session session
                                                    :prompt-fn prompt-fn
                                                    :projections projections
                                                    :approvals-memory
                                                    (permissions/memory
                                                     remembered-workspace)}))))))

(srce/defplugin plugin
  {:name :agent-loop
   :inject [:agents :sessions :projections :system-prompt]
   :apply serve})
