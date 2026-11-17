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
                             :ctx ctx
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
   :description (str "Babashka/Clojure SCI sandbox in session cwd; paths/shell resolve (`(cwd)`; bash :cwd overrides).\n"
                     "File/shell/HTTP: (read \"p\") -> text; (read \"p\" {:offset N :limit N}); (read \"p\" {:start N :end N :as :lines}) -> {:lines [{:n :text}] ...}; (write \"p\" text) -> message; (edit \"p\" old new|pairs); (bash \"cmd\" {:timeout N :max-lines N :max-bytes N :as :map}) -> output|map; (glob \"p\") -> {:paths :total :truncated?}; (grep \"p\") -> {:matches ...}; (fetch \"url\" {:as :json}) -> {:status :headers :body}. Nonzero exits are normal.\n"
                     "Forms run in order; definitions/results persist; (emit v); one line/form; println discarded. Limit 16 KiB (max 50 KiB); timeout (omit/0 = none). Discover: (help) index; (help :core); (sources); (help :library) saved/live; (index \"text\") saved/live/source; (describe 'name). Source verbs: <id>/verb.")
   :prompt-snippet "Runs in cwd; independent eval calls may be issued together."
   :prompt-guidelines
   ["Run several eval calls in one message; keep dependent forms in one call."
    "Bind intermediate output; bound reads; emit summary; println is discarded."
    "`read` returns text; `:as :lines` -> map with `:lines [{:n :text}]`, 1-based; grep/glob take `:path`; avoid sed/head/tail/cat and bash rg/find."
    "Aliases: fs,str,set,edn,walk,json (clojure.data.json),p (babashka.process)."
    "Don't assume libs; (help :surface) lists them."]
   :parameters {:type "object"
                :properties
                {"code" {:type "string"
                         :description "The Clojure forms to evaluate, in order."}
                 "timeout" {:type "number"
                            :description "Seconds; omit or 0 = no deadline."}
                 "max-output-bytes" {:type "number"
                                     :description "Max result bytes (default 16 KiB, max 50 KiB)."}}
                :required ["code"]}
   :execute execute
   :streams? true
   :contextual? true
   :status true
   :serial-group :eval
   :title title))
