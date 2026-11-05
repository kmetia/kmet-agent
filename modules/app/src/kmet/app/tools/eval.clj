(ns kmet.app.tools.eval
  "The `eval` tool: model-written Clojure in the agent session's persistent
   SCI environment (`kmet.app.eval`). Unlike `run_code`, the environment is
   kept across calls, so a definition from one call is visible to the next;
   definitions are session-private unless `:keep` promotes one to the
   project/global files the next session loads. The fresh-context,
   output-economy tool stays `run_code`."
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
   process-wide environment. A call may carry only `:keep`/`:drop`, in which
   case it promotes, moves, demotes or drops an existing definition without
   evaluating forms."
  [{:keys [code timeout max-output-bytes keep drop]} on-update signal ctx]
  (let [code (some-> code str)]
    (if (and (str/blank? code) (empty? keep) (empty? drop))
      {:content "No code, keep or drop provided." :is-error true}
      (if-let [service (some-> (:srce-ctx ctx) (srce/get :eval))]
        (try
          ((:eval! service) {:code code
                             :keep keep
                             :drop drop
                             :timeout timeout
                             :limits (script/resolve-limits max-output-bytes)
                             :signal signal
                             :on-update on-update
                             :ctx ctx
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
   :description "Evaluate Clojure forms in this session's persistent environment. Definitions persist across calls for the session; use run_code for a fresh, throwaway script. A successful call's namespace is re-entered by the next. Definitions are private to the session unless keep promotes one to the shared project/global files that every session loads; drop removes every copy. Output is the emitted text (or the final value of a silent success); stdout and stderr are discarded."
   :prompt-snippet "Eval Clojure with definitions that persist for this session"
   :prompt-guidelines
   ["Use eval when a definition should survive the call — a parser, a report fn, a scratch namespace — and run_code for a throwaway script."
    "Definitions persist across this session's eval calls; other sessions see only definitions promoted with keep."
    "keep is [[\"user/f\" \"project\"]] or [[\"user/f\" \"global\"]]: the file is written under .kmet/eval or ~/.kmet/agent/eval and survives restarts. keep [[\"user/f\" \"session\"]] demotes a shared definition back to this session; drop removes the session copy and both shared files."
    "A code-less call with keep or drop promotes, moves, demotes or drops a definition from an earlier call without re-sending its source."
    "Report with (emit value) or end on the value you want reported; discover the surface with (all-ns) and (ns-publics), and list persisted definitions with (eval/kept)."]
   :parameters {:type "object"
                :properties
                {"code" {:type "string"
                         :description "Clojure forms. Definitions persist for this session. Omit when only keep/drop acts."}
                 "timeout" {:type "number"
                            :description (str "Timeout in seconds — like bash's :timeout: omit or 0 "
                                              "= no deadline; fractional ok, capped at a day")}
                 "keep" {:type "array"
                         :items {:type "array"
                                 :items {:type "string"}}
                         :description "Definitions to persist: [name, scope] pairs, scope session|project|global. Project/global write a reviewable file; session demotes a shared definition back. May be used without code."}
                 "drop" {:type "array"
                         :items {:type "string"}
                         :description "Definition names to remove everywhere: the session copy and both project/global files. May be used without code."}
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
            (registry/mount-tool! ctx definition config))})
