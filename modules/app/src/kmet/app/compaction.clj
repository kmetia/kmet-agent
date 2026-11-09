(ns kmet.app.compaction
  "The `:compaction` contract: the operations the loop and the session handle
   call, resolved through the agent's composition.

   The builtin implementation is `kmet.app.compaction.stage`, and the
   `:compaction` row provides it as a service over the injected
   `:compactor/llm` and `:compactor/vcc` strategies; the `:compactor` setting
   selects one per compaction. A profile replaces the whole orchestrator by
   naming another plugin under the same id, and every caller here reaches
   whatever is mounted without knowing which. A state outside a composition (a
   hand-built run, a test) falls back to the builtin, as the loop's stage map
   does for its two rows."
  (:require [kmet.app.compaction.stage :as stage]
            [kmet.srce.core :as srce]))

(def builtin
  "The builtin compaction implementation: one fn per operation the callers use.
   This is what a state with no composition resolves; the `:compaction` row
   builds the same operations over the injected compactor strategies."
  stage/builtin)

(defn- impl
  "AGENT's compaction implementation: the `:compaction` service its ctx can
   see, or the builtin when the state has no composition. A ctx-less state
   reads nil from `srce/get`."
  [agent]
  (or (srce/get (:ctx agent) :compaction) builtin))

(defn reset-compaction-blocked!
  "Clear the per-prompt compaction gate."
  [agent]
  ((:reset-compaction-blocked! (impl agent)) agent))

(defn abort-compaction!
  "Abort the in-flight compaction without cancelling the run."
  [agent]
  ((:abort-compaction! (impl agent)) agent))

(defn stage-retry-policy
  "The flat retry policy the compaction paths spend: the run's options under the
   ctx's `:retry-policy` stage config. The 2-arity reuses a stage read the
   caller already made."
  ([agent]
   ((:stage-retry-policy (impl agent)) agent))
  ([agent stages]
   ((:stage-retry-policy (impl agent)) agent stages)))

(defn generate-branch-summary
  "LLM summary of abandoned branch entries for tree navigation."
  [agent entries & args]
  (apply (:generate-branch-summary (impl agent)) agent entries args))

(defn omit-recovery-attempt!
  "Durably omit a failed attempt's entry from every future model projection."
  [agent entry]
  ((:omit-recovery-attempt! (impl agent)) agent entry))

(defn compact-context!
  "Compact the context now: summarize the pre-cut entries and replace the live
   context with the summary (the `/compact` path and the automatic ones)."
  [agent & args]
  (apply (:compact-context! (impl agent)) agent args))

(defn maybe-compact!
  "Compact proactively before a run when the measured usage passes the
   threshold. Returns true when compaction happened."
  [agent]
  ((:maybe-compact! (impl agent)) agent))

(defn compact-for-overflow!
  "Force-compact after a context-overflow error so the retried call fits."
  [agent]
  ((:compact-for-overflow! (impl agent)) agent))

;; ─── The row ─────────────────────────────────────────────────────────────

(defn apply-row
  "Provide the compaction orchestrator as the `:compaction` service, over the
   injected :compactor/llm and :compactor/vcc strategies, into the mount
   site's scope so the loop and every agent ctx below it resolve it.
   No `:config`: the compactor selection and budgets are settings keys
   (`:compactor`, `:compact-reserve-tokens`, `:keep-recent-tokens`,
   `:compact-token-threshold`, … and the per-model overrides), and a row that
   silently ignored one would read like an applied override."
  [ctx config]
  (when (some? config)
    (throw (ex-info (str "the :compaction row takes no :config — the compactor and"
                         " budgets are settings keys, not composition")
                    {:type :kmet.app/bad-compaction-config
                     :config config})))
  (let [strategies {:llm (srce/get ctx :compactor/llm)
                    :vcc (srce/get ctx :compactor/vcc)}]
    (srce/provide! ctx :compaction
                   (if (= strategies stage/default-strategies)
                     stage/builtin
                     (stage/make-implementation strategies))
                   {:in (:parent ctx)})))

(srce/defplugin plugin
  {:name :compaction
   :inject {:compactor/llm nil :compactor/vcc nil}
   :provide #{:compaction}
   :apply apply-row})
