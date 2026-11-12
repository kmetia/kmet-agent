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
   `tools` namespace: an eval call cannot invoke any tool."
  (:require [clojure.string :as str]
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
  "The `eval` tool's record — what the `:tool/eval` row mounts."
  (tool/make-tool
   :name "eval"
   :label "Eval"
   :description (str "Persistent Clojure/SCI script sandbox (Babashka-style; no tools namespace).\n"
                     "Files: (read \"p\") or (read \"p\" {:offset N :limit N :footer false :tail N}); (write \"p\" text); (edit \"p\" old new) or (edit \"p\" pairs).\n"
                     "Shell: (bash \"cmd\" {:timeout N}) -> output + status; a nonzero exit is normal. OPTS: :cwd, :max-lines, :as :map.\n"
                     "Batch independent reads/tests; (emit v) for full output.\n"
                     "Example: (emit {:spec (read \"SPEC-v2.txt\") :tests (bash \"bash test-v1.sh; bash test-v2.sh\")}).\n"
                     "Definitions/results persist; println is discarded. Mounted source verbs: <id>/verb. (help :surface) lists this host's require-able namespaces, classes and limits; (help) indexes verbs and topics.")
   :prompt-snippet "Files: (read \"p\"), (write \"p\" text), (edit \"p\" old new); shell: (bash \"cmd\" {:timeout N}). Batch independent reads/tests in one call; (emit v) for full output; (help)."
   :prompt-guidelines
   ["Batch independent reads, commands, and tests into one eval call: compose shell commands with &&/; in one (bash ...), put several read/bash forms in one call, or emit a map of the values; not one call per file, command, or test suite."
    "Return the final value or (emit value); a multi-form call reports one line per form; println is discarded."
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
   :inject [:tools]
   :apply (fn [ctx config]
            (registry/mount-tool! ctx definition config)
            nil)})
