(ns kmet.app.compaction.llm
  "The builtin LLM compactor's row.

   The strategy itself lives in kmet.app.compaction.stage next to the
   orchestration it shares (summarization call, retry reporting, output cap);
   this namespace only provides it as the `:compactor/llm` service, so the
   `:compaction` row can inject both builtin strategies and /settings can
   switch between them."
  (:require [kmet.app.compaction.stage :as stage]
            [kmet.srce.core :as srce]))

(def strategy
  "The builtin LLM compactor."
  stage/llm-strategy)

(defn apply-row
  "Provide the LLM compactor as the `:compactor/llm` service. No :config: it
   has no settings of its own (the token budgets are top-level settings)."
  [ctx config]
  (when (some? config)
    (throw (ex-info "the :compactor/llm row takes no :config"
                    {:type :kmet.app/bad-compactor-config
                     :config config})))
  (srce/provide! ctx :compactor/llm strategy {:in (:parent ctx)}))

(srce/defplugin plugin
  {:name :compactor/llm
   :inject {:llm nil}
   :provide #{:compactor/llm}
   :apply apply-row})
