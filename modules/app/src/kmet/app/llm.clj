(ns kmet.app.llm
  "The `:llm` contract: how a run reaches the model.

   One operation — `send-message`, the streaming request `kmet.ai.llm`
   implements — resolved through the agent's composition. The service value is
   `{:send-message (fn [opts] …)}`; the `:llm` row provides the builtin, so a
   profile replaces model access by naming another plugin under the same id, and
   a state outside a composition (a test, a hand-built run) falls back to the
   library.

   The conversion is not part of that value: `send-message` attaches the app's
   convertToLlm to every request as `:convert-to-llm`
   (`kmet.app.loop.message/to-llm-messages`), so the run keeps its roles,
   `kmet.ai` applies it after the `:on-context` hook (pi's emitContext →
   convertToLlm order), and a replacing implementation receives the option
   rather than having to know the roles. The boundary preview reads the same
   function — one conversion, two readers.

   Per-call, not per-agent: the provider, model, thinking level and endpoints
   already ride the call's opts and the agent state, so the service carries no
   selection state and a mounted implementation never has to track it. The
   model *catalog* stays a library (`kmet.ai.models`), and an extension adds a
   provider through `register-model-provider!` — this seam is the request, not
   the registry."
  (:require [kmet.ai.llm :as llm]
            [kmet.app.loop.message :as message]
            [kmet.srce.core :as srce]))

(def builtin
  "The builtin model access: `kmet.ai.llm/send-message`, called through the var
   rather than captured — a reloaded library (or a test's `with-redefs`) reaches
   the next request."
  {:send-message (fn builtin-send-message [opts] (llm/send-message opts))})

(defn- impl
  "AGENT's model access: the `:llm` service its ctx can see, or the builtin. A
   ctx-less state (nil, a hand-built run) reads nil from `srce/get`."
  [agent]
  (or (srce/get (:ctx agent) :llm) builtin))

(defn send-message
  "Send OPTS as one streaming request and return the future that completes when
   the stream ends — `kmet.ai.llm/send-message`'s contract, through whatever the
   agent's composition mounted, with the app's provider-neutral message view
   attached."
  [agent opts]
  ((:send-message (impl agent))
   (assoc opts :convert-to-llm message/to-llm-messages)))

;; ─── The row ─────────────────────────────────────────────────────────────

(defn apply-row
  "Provide the builtin as the `:llm` service, into the mount site's scope so
   every agent below it resolves model access. No `:config`: the model, provider
   and thinking level are per-call and per-agent state, and the provider
   catalogs are files — a row that silently ignored a config key would read like
   an applied override."
  [ctx config]
  (when (some? config)
    (throw (ex-info (str "the :llm row takes no :config — model, provider and"
                         " thinking level are per-call state, and providers are"
                         " registered with kmet.ai.models/register-provider!")
                    {:type :kmet.app/bad-llm-config
                     :config config})))
  (srce/provide! ctx :llm builtin {:in (:parent ctx)}))

(srce/defplugin plugin
  {:name :llm
   :provide #{:llm}
   :apply apply-row})
