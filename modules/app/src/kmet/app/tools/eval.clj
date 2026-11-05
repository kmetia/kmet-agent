(ns kmet.app.tools.eval
  "The `eval` tool: model-written Clojure in the agent session's persistent
   SCI environment (`kmet.app.eval`). Unlike `run_code`, the environment is
   kept across calls, so a definition from one call is visible to the next.
   Every successful definition is written to the agent home's eval library
   and loaded by every session; a new session or a restart sees it. The
   fresh-context, output-economy tool stays `run_code`.

   The row also registers the `:core` eval source, which puts the shared
   `edit`/`write-file` operations under `user`; removing the row leaves the
   `/eval` command and the service, but no core verbs."
  (:require [clojure.string :as str]
            [kmet.app.eval.sources :as eval-sources]
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
   process-wide environment. A call may carry only `:drop`, in which case it
   removes a library definition without evaluating forms."
  [{:keys [code timeout max-output-bytes drop]} on-update signal ctx]
  (let [code (some-> code str)]
    (if (and (str/blank? code) (empty? drop))
      {:content "No code or drop provided." :is-error true}
      (if-let [service (some-> (:srce-ctx ctx) (srce/get :eval))]
        (try
          (if (str/blank? code)
            ((:drop! service) {:drop drop})
            ((:eval! service) {:code code
                               :drop drop
                               :timeout timeout
                               :limits (script/resolve-limits max-output-bytes)
                               :signal signal
                               :on-update on-update
                               :ctx ctx
                               :cwd (or (:cwd ctx) (tool-util/cwd))}))
          (catch Exception e
            {:content (str "Error evaluating: " (ex-message e)) :is-error true}))
        {:content "eval needs an agent's :eval service; call it as a tool in a run."
         :is-error true}))))

(def definition
  "The `eval` tool's record — what the `:tool/eval` row mounts."
  (tool/make-tool
   :name "eval"
   :label "Eval"
   :description "Evaluate Clojure forms in this session's persistent environment. Definitions persist across calls for the session and are written to the agent's eval library, so every session and a restart load them; use run_code for a fresh, throwaway script. A successful call's namespace is re-entered by the next. A redefinition overwrites its library file; drop deletes the file and unbinds the live name. Output is the emitted text (or the final value of a silent success); stdout and stderr are discarded."
   :prompt-snippet "Eval Clojure with definitions that persist across sessions"
   :prompt-guidelines
   ["Use eval when a definition should survive the call — a parser, a report fn, a scratch namespace — and run_code for a throwaway script."
    "Every top-level definition is written to the agent's eval library and loaded by every session; a redefinition overwrites its file."
    "drop removes a definition from the library and unbinds it here; a code-less call may carry only drop."
    "Use (eval/patch 'name old new) to replace one subform in a named definition; it re-evaluates before writing, and the definition name must stay."
    "Report with (emit value) or end on the value you want reported; discover the surface with (all-ns) and (ns-publics), and list loaded definitions with (eval/library)."]
   :parameters {:type "object"
                :properties
                {"code" {:type "string"
                         :description "Clojure forms. Top-level definitions are written to the agent's eval library. Omit when only drop acts."}
                 "timeout" {:type "number"
                            :description (str "Timeout in seconds — like bash's :timeout: omit or 0 "
                                              "= no deadline; fractional ok, capped at a day")}
                 "drop" {:type "array"
                         :items {:type "string"}
                         :description "Definition names to remove: deletes the library file and unbinds the live name. May be used without code."}
                 "max-output-bytes" {:type "number"
                                     :description "Maximum result bytes (default 16 KiB; capped at 50 KiB)"}}
                :required []}
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
            (registry/mount-tool! ctx definition config)
            (srce/effect ctx
                         #(registry/register-eval-source!
                           (srce/get ctx :tools)
                           eval-sources/core-source-id
                           eval-sources/core-source
                           {:owner registry/eval-sources-owner})
                         :eval-core-source)
            nil)})
