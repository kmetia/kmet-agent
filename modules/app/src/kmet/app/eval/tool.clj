(ns kmet.app.eval.tool
  "The `eval` tool: model-written Clojure in the agent session's persistent
   SCI environment (`kmet.app.eval`). Unlike `codemode`, the environment is
   kept across calls, so a definition from one call is visible to the next.
   The tool declares no execution mode: the loop examines each call's code
   before executing a batch, and a batch containing a verb definition runs in
   source order, so that visibility never depends on which future wins the
   session's call lock. Every successful definition is written to the agent
   home's eval library — the session cwd's scope by default, the global scope
   when requested — and loaded by every session; a new session or a restart
   sees both layers. The
   fresh-context, output-economy tool stays `codemode`.

   The shared `read`, `write`, `edit` and `bash` verbs (also
   available as `core/...`) are the file and shell surface. The sandbox has no
   `tools` namespace: an eval call cannot invoke any tool. The
   `kmet.app.eval.plugin` row owns this tool and the `/eval` slash command, so
   the two are registered and disposed together."
  (:require [clojure.string :as str]
            [kmet.app.tools.script :as script]
            [kmet.app.tools.session-recall :as session-recall]
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
   process-wide environment. Every call carries code; library definitions are
   managed with the sandbox's `save`, `forget` and `move` verbs."
  [{:keys [code timeout max-output-bytes strings]} on-update signal ctx]
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
                             :ctx ctx
                             :strings strings
                             :session-recall
                             (when-let [sess (:session-record ctx)]
                               (fn [params]
                                 (session-recall/run-recall
                                  sess (session-recall/normalize-params params))))
                             :cwd (or (:cwd ctx) (tool-util/cwd))})
          (catch Exception e
            {:content (str "Error evaluating: " (ex-message e)) :is-error true}))
        {:content "eval needs an agent's :eval service; call it as a tool in a run."
         :is-error true}))))

(def definition
  "The `eval` tool's record — one of the `:tool/eval` row's two effects."
  (tool/make-tool
   :name "eval"
   :label "Eval"
   :description "Babashka/Clojure SCI sandbox in cwd (`(cwd)`; bash :cwd overrides); forms run in order.\nFile/shell: (read \"p\" {:offset N :limit N}); (write \"p\" text); (edit \"p\" old new|pairs); (bash \"cmd\" {:timeout N :as :map}); (glob \"p\"); (grep \"p\"); (fetch \"url\" {:as :json}). (help) lists the rest.\nReturn values; (emit v) adds output."
   :prompt-snippet "Runs in cwd; one phase per call."
   :prompt-guidelines
   ["One eval call = one phase: batch independent reads and the dependent edit→test→verify as forms in a single code block and return a map of results. Do not make one call per file, command, or test suite."
    "Return values; println/print/prn are discarded. Use (emit v) for long text."
    "Bind intermediates; reads page 1-based; grep/glob take :path. Dense text: strings {...}; ($ \"name\") reads."]
   :parameters {:type "object"
                :properties
                {"code" {:type "string"
                         :description "Clojure forms, evaluated in order."}
                 "strings" {:type "object"
                            :additionalProperties {:type "string"}
                            :description "Named literals; ($ \"name\") reads one."}
                 "timeout" {:type "number"
                            :description "Seconds; 0 = none."}
                 "max-output-bytes" {:type "number"
                                     :description "Result byte cap (default 16 KiB, max 50 KiB)."}}
                :required ["code"]}
   :execute execute
   :streams? true
   :contextual? true
   :status true
   :serial-group :eval
   :title title))
