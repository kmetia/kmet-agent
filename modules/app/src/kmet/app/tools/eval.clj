(ns kmet.app.tools.eval
  "The `eval` tool: model-written Clojure in the agent session's persistent
   SCI environment (`kmet.app.eval`). Unlike `run_code`, the environment is
   kept across calls, so a definition from one call is visible to the next.
   The tool is `:sequential`: a loop batch containing an eval call runs in
   source order, so that visibility never depends on which future wins the
   session's call lock. Every successful definition is written to the agent
   home's eval library — the session cwd's scope by default, the global scope
   when requested — and loaded by every session; a new session or a restart
   sees both layers. The
   fresh-context, output-economy tool stays `run_code`.

   The row also registers the `:core` eval source, which puts the shared
   `read-file`, `edit` and `write-file` operations under `user` and the
   Clojure editing verbs under `clj`; removing the row leaves the `/eval`
   command and the service, but no core verbs."
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
   :description (str "Evaluate Clojure in this session's persistent SCI script sandbox: "
                     script/sandbox-summary
                     " Eval creates verbs: a top-level definition with a non-blank docstring is "
                     "written to the agent's eval library, so it persists across "
                     "calls and new sessions and a restart load it. A new definition "
                     "without a docstring stays live for this session only and is not "
                     "saved; add a docstring and re-evaluate to keep it. A defmethod is "
                     "saved with its loaded multimethod. A saved "
                     "definition redefined without a docstring is not written; the saved "
                     "file is unchanged. Local "
                     "let/fn bindings are scratch; only a documented top-level "
                     "definition is a library write. An already-running session is "
                     "a cache and does not pick up another session's new files. New "
                     "definitions are saved in the cwd scope unless scope is global; "
                     "a redefinition stays in the active definition's scope and keeps "
                     "its file. Drop deletes the definition's actual file, unbinds "
                     "this session's live name, and resolves unqualified names in "
                     "the namespace the call ends in; a file that also defines "
                     "other names must be dropped with all of them. "
                     "(eval/move 'name :global|:cwd) moves a definition between "
                     "scopes. A call that ends in a "
                     "normal error writes nothing; its definitions stay live and "
                     "can be forgotten. A successful call's namespace is "
                     "re-entered by the next. Output is the emitted text (or the "
                     "final value of a silent success); stdout and stderr are "
                     "discarded. One call runs a whole script: forms run in order, "
                     "independent work can overlap — start (sandbox/spawn f) or "
                     "(tools/call …) and deref them to join — and eval is "
                     "sequential: calls in one batch serialize in source order, "
                     "so pack a multi-step task into one call. This is the "
                     "sandbox, not the project's nREPL.")
   :prompt-snippet "Persistent Clojure/SCI sandbox (fs/sh/tools); eval creates saved verbs from documented top-level definitions; pack multi-step work in one call"
   :prompt-guidelines
   ["eval is the full script sandbox, not a bare REPL: fs, sh, slurp, tools and require work as in a babashka script; (eval/help) lists sources, loaded definitions and the surface."
    "Read files with (user/read-file path) or (user/read-file path offset limit); it shares the read tool's 1-indexed offset/limit and truncation. For images use (emit-image path)."
    "Call the run's tools through the `tools` namespace: (tools/list) lists them, (tools/describe \"name\") shows one declaration, and (tools/call \"name\" args) returns a promise to deref — or use (tools/call! \"name\" args) to settle and throw on failure; a plain name is a var too, @(tools/name args)."
    "Pack independent work into one eval call: forms run in order and independent work overlaps — `(sandbox/spawn f)` for local work, and starting several `(tools/call …)` promises before dereferencing any of them. Eval is sequential: calls in one batch serialize in source order, so a second call buys no concurrency; use one call for a multi-step task instead of one call per operation."
    "Define what should survive the call: a documented top-level definition is written to the agent's eval library as a verb, while an undocumented one stays live for this session only. Use local bindings for throwaway code."
    "Eval creates verbs: give a top-level defn/defmacro/def/etc. a non-blank docstring to save it to the library, where later calls and sessions can use it; a new definition without one is live-only and not saved. Add a docstring and re-evaluate to keep it; a defmethod is saved with its loaded multimethod. A saved definition redefined without a docstring is not written; the saved documented file is unchanged. The docstring is the interface later calls see, with its arglists; (eval/describe 'name) reads it; a patch or edit-form result cannot remove it."
    "New sessions and a restart load the library; an already-running session is a cache and does not see another session's new files. The prompt previews the saved or loaded definitions; the first eval reports any loaded definitions and files that failed. A call that ends in a normal error writes nothing; its definitions stay live and can be forgotten."
    "A redefinition keeps its file; drop or (eval/forget 'name) deletes the definition's actual file and unbinds this session's live name, resolving unqualified names in the namespace the call ends in. A file that also defines other names must be dropped with all of them. A code-less call may carry only drop (plus scope)."
    "New definitions default to the cwd scope; pass scope global to save a new name globally. An existing active name keeps its scope, and the scope option may only name its active layer. Move a definition with (eval/move 'name :global|:cwd) or /eval move; a move does not re-evaluate. Use (eval/patch 'name old new) to replace one subform in a named definition; it re-evaluates before writing, and the definition name and docstring must stay (a defmethod is covered by its multimethod). Use (eval/edit-form 'name new-source) to replace the whole definition; edit files structurally with clj/edit-form, clj/replace-sexp and clj/paren-repair."
    "Use (eval/describe 'name) for one definition's kind, docstring, arglists, file, scope and source; (eval/describe 'name key) returns one field. (eval/sources) stays the registered-source listing, including each contributed verb's interface."
    "Report with (emit value) or end on the value you want reported; stdout and stderr are discarded."]
   :parameters {:type "object"
                :properties
                {"code" {:type "string"
                         :description "Clojure forms. A top-level definition with a non-blank docstring is written to the agent's eval library; an undocumented one stays live for this session only. Omit when only drop acts."}
                 "timeout" {:type "number"
                            :description (str "Timeout in seconds — like bash's :timeout: omit or 0 "
                                              "= no deadline; fractional ok, capped at a day")}
                 "drop" {:type "array"
                         :items {:type "string"}
                         :description "Definition names to remove: deletes the library file and unbinds the live name. May be used without code."}
                 "scope" {:type "string"
                          :enum ["cwd" "global"]
                          :description "Library layer for a new definition or a drop: cwd (default for new names) or global. A redefinition keeps its active layer."}
                 "max-output-bytes" {:type "number"
                                     :description "Maximum result bytes (default 16 KiB; capped at 50 KiB)"}}
                :required []}
   :execute execute
   :streams? true
   :contextual? true
   ;; the script tool: the transcript keeps the Elapsed/Took tail line
   ;; (tool-execution :status) for its whole life
   :status true
   ;; The session lock serializes calls but does not order them. Eval is
   ;; stateful, so the batch classifier must run calls one at a time in the
   ;; order the model emitted them.
   :execution-mode :sequential
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
