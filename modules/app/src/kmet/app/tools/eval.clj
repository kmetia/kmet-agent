(ns kmet.app.tools.eval
  "The `eval` tool: model-written Clojure in the agent's persistent SCI
   environment (`kmet.app.eval`). Unlike `run_code`, the environment is kept
   across calls, so a definition from one call is visible to the next; the
   fresh-context, output-economy tool stays `run_code`."
  (:require [clojure.string :as str]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.script :as script]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.util :as tool-util]
            [kmet.libs.text :as text]
            [kmet.srce.core :as srce]))

(defn title
  "Quiet one-liner body for the eval tool: `eval $ ` and the first non-blank
   code line, shortened."
  [args]
  (when-let [code (tool-util/title-str-arg args :code)]
    (when-let [line (text/title-line code)]
      (str "eval $ " line))))

(defn execute
  "Evaluate the call in this agent's `:eval` service. The service comes from
   the run's ctx (`:srce-ctx`), so the tool is usable only inside a run; a
   direct extension call reports the missing service instead of starting a
   process-wide environment."
  [{:keys [code timeout max-output-bytes]} on-update signal ctx]
  (let [code (some-> code str)]
    (if (str/blank? code)
      {:content "No code provided." :is-error true}
      (if-let [service (some-> (:srce-ctx ctx) (srce/get :eval))]
        (try
          ((:eval! service) {:code code
                             :timeout timeout
                             :limits (script/resolve-limits max-output-bytes)
                             :signal signal
                             :on-update on-update
                             :ctx ctx})
          (catch Exception e
            {:content (str "Error evaluating: " (ex-message e)) :is-error true}))
        {:content "eval needs an agent's :eval service; call it as a tool in a run."
         :is-error true}))))

(def definition
  "The `eval` tool's record — what the `:tool/eval` row mounts."
  (tool/make-tool
   :name "eval"
   :label "Eval"
   :description "Evaluate Clojure forms in this agent's persistent environment. Definitions persist across calls for the agent's lifetime, and a successful call's namespace is re-entered by the next — use eval for a helper or state you will reuse, and run_code for a fresh, throwaway script. Output is the emitted text (or the final value of a silent success); stdout and stderr are discarded. Nothing is written to disk."
   :prompt-snippet "Eval Clojure with definitions that persist across calls"
   :prompt-guidelines
   ["Use eval when a definition should survive the call — a parser, a report fn, a scratch namespace — and run_code for a throwaway script."
    "Definitions persist across this agent's eval calls; nothing is written to disk."
    "Report with (emit value) or end on the value you want reported; discover the surface with (all-ns) and (ns-publics)."]
   :params {:code {:type :string
                   :description "Clojure forms. Definitions persist across calls for this agent."}
            :timeout {:type :number
                      :description (str "Timeout in seconds — like bash's :timeout: omit or 0 "
                                        "= no deadline; fractional ok, capped at a day")
                      :optional? true}
            :max-output-bytes {:type :number
                               :description "Maximum result bytes (default 16 KiB; capped at 50 KiB)"
                               :optional? true}}
   :execute execute
   :streams? true
   :contextual? true
   ;; the script tool: the transcript keeps the Elapsed/Took tail line
   ;; (tool-execution :status) for its whole life
   :status true
   :title title))

(srce/defplugin plugin
  {:name :tool/eval
   :inject [:tools]
   :apply (fn [ctx config]
            (registry/mount-tool! ctx definition config))})
