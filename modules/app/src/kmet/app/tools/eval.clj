(ns kmet.app.tools.eval
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
   `tools` namespace: an eval call cannot invoke any tool. The same
   `:tool/eval` row mounts the `/eval` slash command, so the tool and the
   command are registered and disposed together."
  (:require [clojure.string :as str]
            [kmet.app.commands :as commands]
            [kmet.app.commands.eval :as eval-command]
            [kmet.app.tools.registry :as registry]
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
   :description (str "Persistent Clojure/SCI sandbox. Runs in the session cwd; relative paths and shell commands resolve there (`(cwd)`; bash's :cwd overrides).\n"
                     "Verbs: (read \"p\" {:offset N :limit N}), (write \"p\" text), (edit \"p\" old new), (bash \"cmd\" {:timeout N :max-lines N :as :map}); (spawn f)/pmap for concurrency. Nonzero shell exits are normal.\n"
                     "Forms run in order; definitions/results persist. (emit v) for output; multi-form calls report one line per form; println is discarded. Result limit: 16 KiB (max 50 KiB); timeout per call (omit/0 = none). Discover: (help) index; (help :core) builtins; (sources) source verbs; (help :library) saved/live; (index \"text\") search saved/live/source; (describe 'name) contract. Source verbs: <id>/verb.\n"
                     "Example: (emit {:out (bash \"cmd\") :src (read \"p\")}).")
   :prompt-snippet "Runs in cwd. Verbs: (read), (write), (edit), (bash); independent eval calls may be issued together; one call for dependent forms; (emit v); (help)."
   :prompt-guidelines
   ["Independent: several eval calls in one message or several verbs in one call; one call for dependent forms. (spawn f)/pmap inside one call."
    "Bind intermediate output; keep reads bounded (:offset/:limit/:tail); emit only the summary. Multi-form calls report one line per form; println is discarded."
    "Don't assume host classes or libraries; (help :surface) lists this host's."]
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

(srce/defplugin plugin
  {:name :tool/eval
   :inject [:tools :commands]
   :apply (fn [ctx config]
            (registry/mount-tool! ctx definition config)
            (commands/mount-command! ctx eval-command/command :tool/eval)
            nil)})
