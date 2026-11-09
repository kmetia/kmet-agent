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
  (str "Run a Clojure script to work over the environment and report a digest. "
       "Use it when the intermediate output would flood the context — scan or transform many files, run a search→read→verify chain, compute counts, matches or a summary — instead of chaining bash cat/head/rg. Use bash for one small command and read for one file.\n\n"
       "Every codemode call starts from a fresh context; definitions do not persist.\n\n"
       "Inside the script the local primitives return values, not rendered text; prefer them over bash/read for local work. Call a tool from the script only when its result is too large to read while the useful part is small — a web fetch, an MCP call: @(tools/call \"name\" args) settles with {:content :is-error :details :truncation :images}; check :is-error, and the inner result never enters the conversation. A plain name is also a var — @(tools/read {:path \"x\"}) — while (tools/call! …) is one call derefed, throwing when it fails; (tools/ok? r), (tools/text r) and (tools/error r) read a result. (tools/list) returns the callable tools with their descriptions and parameters; (tools/describe \"name\") returns one tool's declaration.\n\n"
       "Callable tools include read(path, offset?, limit?), write(path, content), edit(path, edits), and bash(command, timeout?); (core/read path), (core/write path text), (core/edit path old new), and (core/bash command) call the matching implementations. Prefer (slurp path), (spit path text), fs/*, and (core/sh ...) for whole-file and shell work.\n\n"
       "Report with (core/emit value), or end the script on the value you want reported: a successful script that emits nothing reports its final value. Strings print raw, other data pr-str, and stdout and stderr are always discarded. (core/emit-image \"shot.png\") attaches a supported image file (up to 3 MiB) or an {:data <base64> :mime-type <str>} block; at most 8.\n\n"
       "Sandbox: SCI with the babashka surface; (tools/list) lists tools and (all-ns) namespaces. :timeout is seconds (0/omitted = none). Output is capped at 16 KiB/2000 lines; :max-output-bytes raises it to 50 KiB."))

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
   ["Use codemode for Clojure — including one-liners — instead of writing bb -e/bb -f scripts through bash, unless the code needs the project classpath or its tests."
    "Use codemode when a fresh context matters: definitions do not persist between calls."
    "Use codemode when a command's output would flood the context: compute inside the script and (core/emit) only the digest — counts, matches, the lines that matter — instead of chaining bash cat/head/rg; println is discarded (core/emit the digest, or end on the value you want reported)."
    "Inside a script prefer (slurp path), fs/* and (core/sh ...) for local work over the bash and read tools — they return values, not rendered text — and call a tool only when its result is too large to read (a web fetch, an MCP call), emitting the filtered part: @(tools/call \"name\" args), or its per-tool var @(tools/read {…}). The core/read, core/write, core/edit and core/bash wrappers are available when you need the matching implementation's behavior."
    "When the answer is an image (a screenshot, a chart, a rendered page), attach it with (emit-image path) rather than describing it."]
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
