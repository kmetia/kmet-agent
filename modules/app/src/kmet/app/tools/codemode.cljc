(ns kmet.app.tools.codemode
  "The `codemode` tool — model-written Clojure in a per-call SCI sandbox with
   kmet's tools bridged in (design: docs/architecture.md § Tools and the script sandbox).

   Lifecycle: every call builds a fresh SCI context and discards it on
   return; the shared sandbox machinery (capabilities, the tools bridge,
   capture, the interrupt and the result assembly) lives in
   kmet.app.tools.script, so `eval` can run on a kept context. The tool
   declares no execution mode: its calls use the parallel batch, since a fresh
   context means definitions cannot outlive the call. A script's own
   `tools/call` fan-out stays concurrent too. The module is deliberately
   codemode's own: the tool record, its description and its row."
  (:require [clojure.string :as str]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.script :as script]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.util :as tool-util]
            [kmet.libs.text :as text]
            [kmet.srce.core :as srce]))

;; ─── Tool record ──────────────────────────────────────────────────────────

(def ^:private description
  (str "Run one Clojure script in a fresh SCI sandbox with kmet's tools bridged in. "
       "Use it to complete a whole phase: inspect inputs, write files, run commands or tests, and emit only the compact result. "
       "Use it when the intermediate output would flood the context (several files, a search→read→verify chain, or a compute-and-summarize task), not for a single trivial command. "
       "Definitions do not persist between calls; stdout and stderr are discarded, and only the script's output reaches the conversation. "
       "Callable tools: read(path, offset?, limit?), write(path, content), edit(path, edits), bash(command, timeout?). "
       "Prefer (slurp path), (spit path text), fs/*, and (sh ...) for whole-file and shell work; bare (read path), (write path text), (edit path old new), and (bash command) are there when you need the matching tool's own behavior. "
       "(tools/list) lists every current declaration; @(tools/call \"name\" args) calls any other tool. "
       "Report with (emit value) or end on the value; :timeout is seconds; output is capped at 16 KiB/2000 lines, :max-output-bytes raises it to 50 KiB. "
       "Sandbox: SCI with the babashka surface; (tools/list) lists tools and (all-ns) namespaces."))

(defn title
  "Quiet one-liner body for the codemode tool: `codemode $ ` and the first
   non-blank code line, shortened."
  [args]
  (when-let [code (tool-util/title-str-arg args :code)]
    (when-let [line (text/title-line code)]
      (str "codemode $ " line))))

(defn create-tool
  "Build the script Tool record. OPTS wires the registry seams (the
   `:tool/codemode` row builds the composition's record via `definition`;
   tests may build their own):
     :get-all-tools — 0-arg registry tool map (the model's tools)
     :get-contributed-tools — 0-arg extension-contributed sandbox tool map
                              (contributed sandbox sources; optional, defaults to none)
     :select-tools  — (fn [all {:keys [enabled contributed exclude]}]) — the
                      registry's surface filter, so a script's callable set
                      resolves exactly like the loop's schema
     :execute-tool  — (fn [name args opts]) dispatch"
  [{:keys [get-all-tools get-contributed-tools select-tools execute-tool]}]
  (tool/make-tool
   :name "codemode"
   :label "Codemode"
   :description description
   :prompt-snippet "One script per phase: read/write/run/verify, then emit only the digest."
   :prompt-guidelines
   ["One codemode call should complete a phase: read inputs, write files, run commands/tests, and emit a compact digest. Use it when the work spans several steps or the output would flood the context; do not use it as a step-by-step REPL."
    "Prefer one call per phase; run independent work inside it with (sandbox/spawn f), pmap, or tools/call promises."
    "Prefer (slurp path), (spit path text), fs/*, and (sh ...) for whole-file and shell work; bare (read path), (write path text), (edit path old new), and (bash command) are available when you need the matching tool's own behavior. Use (tools/list) for other tools and full declarations."
    "Emit only what matters: (emit value) or end on the value; stdout/stderr are discarded. Attach images with (emit-image path)."]
   :params {:code {:type :string :description "One Clojure script; definitions do not persist."}
            :timeout {:type :number
                      :description "Seconds; omit or 0 = no deadline."
                      :optional? true}
            :max-output-bytes {:type :number
                               :description "Max result bytes (default 16 KiB, max 50 KiB)."
                               :optional? true}}
   :execute (fn [args on-update signal ctx]
              (let [code (some-> (:code args) str)]
                (if (str/blank? code)
                  {:content "No code provided." :is-error true}
                  (script/run {:tool "codemode"
                               :code code
                               :timeout (:timeout args)
                               :limits (script/resolve-limits (:max-output-bytes args))
                               :signal signal
                               :ctx ctx
                               :on-update on-update
                               :get-all-tools get-all-tools
                               :get-contributed-tools get-contributed-tools
                               :select-tools select-tools
                               :execute-tool execute-tool}))))
   :streams? true
   :contextual? true
   ;; the script tool: the transcript keeps the Elapsed/Took tail line
   ;; (tool-execution :status) for its whole life
   :status true
   ;; No :execution-mode: calls share the workspace but use the parallel batch.
   ;; Definitions are call-local, so codemode code is not examined by the
   ;; classifier; the script's own tools/call fan-out is unaffected.
   :title title))

;; ─── The tool and its row ──────────────────────────────────────────────────

(defn definition
  "The `codemode` tool's record for HANDLE — the table it lists and dispatches
   through, so a script's callable surface is the table its own agent sees."
  [handle]
  (create-tool {:get-all-tools #(registry/get-all-tools handle)
                :get-contributed-tools #(registry/get-contributed-tools handle)
                :select-tools registry/select-tools
                :execute-tool #(registry/execute-tool handle %1 %2 %3)}))

(srce/defplugin plugin
  {:name :tool/codemode
   :inject [:tools]
   :apply (fn [ctx config]
            (registry/mount-tool! ctx (definition (srce/get ctx :tools)) config))})
