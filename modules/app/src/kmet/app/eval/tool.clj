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
   :description (str "Babashka/Clojure SCI sandbox in cwd (`(cwd)`; bash :cwd overrides); forms run in order, definitions persist.\n"
                     "File/shell/HTTP: (read \"p\") -> text; (read \"p\" {:offset 1 :limit N}); (read \"p\" {:start 1 :end N :as :lines}); (write \"p\" text); (edit \"p\" old new|pairs); (bash \"cmd\" {:timeout N :max-lines N :max-bytes N :as :map}); (glob \"p\"); (grep \"p\"); (fetch \"url\" {:as :json}). Nonzero exits are normal.\n"
                     "Output: emitted text and form values are interleaved in order (earlier values one line each, last value full); (emit v) adds full output. stdout/stderr (println/print/prn) discarded. Limit 16 KiB (50 KiB with :max-output-bytes); timeout seconds, omit/0 = none.\n"
                     "Discover: (help), (help :core), (sources), (help :library), (index \"text\"), (describe 'name); source verbs <id>/verb.")
   :prompt-snippet "Runs in cwd; independent eval calls may be issued together."
   :prompt-guidelines
   ["Run several eval calls in one message; keep dependent forms in one call."
    "Use (emit v) to add output; println/print produce nothing."
    "Bind intermediate output; bound reads."
    "`read` paging is 1-based (`{:offset 1 :limit N}`); `:as :lines` -> map with `:lines [{:n :text}]`; grep/glob take `:path`; avoid sed/head/tail/cat and bash rg/find."
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
