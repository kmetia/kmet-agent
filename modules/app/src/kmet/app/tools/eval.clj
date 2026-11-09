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

   The sandbox's own `user/read`, `user/write`, `user/edit` and `user/bash`
   verbs and the `tools` bridge are the file and tool surface; the row no
   longer mounts a separate `:core` source for them."
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
   process-wide environment. A call may carry only `:drop`, in which case it
   removes a library definition without evaluating forms."
  [{:keys [code timeout max-output-bytes drop scope]} on-update signal ctx]
  (let [code (some-> code str)]
    (if (and (str/blank? code) (empty? drop))
      {:content "No code or drop provided." :is-error true}
      (if-let [service (some-> (:srce-ctx ctx) (srce/get :eval))]
        (try
          (if (str/blank? code)
            ((:drop! service) {:drop drop
                               :scope scope
                               :cwd (or (:cwd ctx) (tool-util/cwd))})
            ((:eval! service) {:code code
                               :drop drop
                               :scope scope
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
   :description (str "Evaluate Clojure in this session's persistent SCI sandbox "
                     "(babashka surface: fs, sh, slurp, spit, require, tools). "
                     "Calls serialize: put independent work in one call and fan out with "
                     "(sandbox/spawn f), pmap, or tools/call promises. Eval creates verbs: "
                     "a documented top-level definition with a docstring is saved to the "
                     "eval library; an undocumented one is live-only. Local let/fn bindings "
                     "are scratch. Report with (emit value) or the final value; stdout and "
                     "stderr are discarded. (eval/help) lists the surface.")
   :prompt-snippet "Persistent Clojure sandbox; one call per batch; (eval/help) for the surface."
   :parameters {:type "object"
                :properties
                {"code" {:type "string"
                         :description "Clojure forms. Omit when only dropping definitions."}
                 "timeout" {:type "number"
                            :description "Seconds; omit or 0 = no deadline."}
                 "drop" {:type "array"
                         :items {:type "string"}
                         :description "Definition names to remove. May be used without code."}
                 "scope" {:type "string"
                          :enum ["cwd" "global"]
                          :description "cwd (default) or global."}
                 "max-output-bytes" {:type "number"
                                     :description "Max result bytes (default 16 KiB, max 50 KiB)."}}
                :required []}
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
