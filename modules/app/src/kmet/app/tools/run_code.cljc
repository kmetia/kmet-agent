(ns kmet.app.tools.run-code
  "The `run_code` tool — model-written Clojure in a per-call SCI sandbox with
   kmet's tools bridged in (design: docs/architecture.md § Tools and the script sandbox).

   Lifecycle: every call builds a fresh SCI context and discards it on
   return; the shared sandbox machinery (capabilities, the tools bridge,
   capture, the interrupt and the result assembly) lives in
   kmet.app.tools.script, so `eval` can run on a kept context. The module is
   deliberately run_code's own: the tool record, its description and its row."
  (:require [clojure.string :as str]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.script :as script]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.util :as tool-util]
            [kmet.libs.text :as text]
            [kmet.srce.core :as srce]))

;; ─── Tool record ──────────────────────────────────────────────────────────

(def ^:private description
  (str "Run a Clojure script to work over the environment and report a digest. Use it when the intermediate output would flood the context — scan or transform many files, run a search→read→verify chain, compute counts, matches or a summary — instead of chaining bash cat/head/rg. Use bash for one small command and read for one file.\n\n"
       "For definitions you want to reuse across calls, use eval; every run_code call starts from a fresh context.\n\n"
       "Inside the script the local primitives are already there and return values, not rendered text: (slurp path), fs/* (babashka.fs), the bare (sh ...), p/*, json, and (cwd), (env k), (now), (sleep ms), (spawn f).\n\n"
       "Call a tool from the script only when its result is too large to read while the useful part is small — a web fetch, an MCP call: @(tools/call \"name\" args) settles with {:content :is-error :details :truncation :images}; check :is-error, and the inner result never enters the conversation. A plain name is also a var — @(tools/read {:path \"x\"}) — while (tools/call! …) is one call derefed, throwing when it fails; (tools/ok? r), (tools/text r) and (tools/error r) read a result. (tools/list) returns the callable tools with their descriptions and parameters; (tools/describe \"name\") returns one tool's declaration.\n\n"
       "Report with (emit value), or end the script on the value you want reported: a successful script that emits nothing reports its final value. Strings print raw, other data pr-str, and stdout and stderr are always discarded. (emit-image \"shot.png\") attaches a supported image file (up to 3 MiB) or an {:data <base64> :mime-type <str>} block; at most 8.\n\n"
       "Sandbox: Clojure; on babashka a full babashka surface, on the JVM the java.lang/java.io/java.util subset. Require clojure.*/babashka.* namespaces or the bundled libs (rewrite-clj, cljfmt, edamame, parinferish); System/java.io.*/Throwable resolve on babashka (Jolt's SCI exposes no class table). Aliases: fs, str/set/edn/walk, json, p, sh, tools, sandbox. Discover the surface with (all-ns) and a namespace's vars with (ns-publics 'babashka.fs). :timeout is seconds (0/omitted = none). Output is capped at 16 KiB/2000 lines; :max-output-bytes raises it to 50 KiB."))

(defn title
  "Quiet one-liner body for the run_code tool: `run $ ` and the first
   non-blank code line, shortened."
  [args]
  (when-let [code (tool-util/title-str-arg args :code)]
    (when-let [line (text/title-line code)]
      (str "run $ " line))))

(defn create-tool
  "Build the script Tool record. OPTS wires the registry seams (the
   `:tool/run_code` row builds the composition's record via `definition`;
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
   :name "run_code"
   :label "Run code"
   :description description
   :prompt-snippet "Script the environment (fs/slurp/sh) and emit a compact digest"
   :prompt-guidelines
   ["Use run_code for Clojure — including one-liners — instead of writing bb -e/bb -f scripts through bash, unless the code needs the project classpath or its tests."
    "Use eval instead when a definition should persist across calls; run_code starts from a fresh context every time."
    "Use run_code when a command's output would flood the context: compute inside the script and (emit) only the digest — counts, matches, the lines that matter — instead of chaining bash cat/head/rg; println is discarded (emit the digest, or end on the value you want reported)."
    "Inside a script prefer (slurp path), fs/* and the bare (sh ...) for local work over the bash and read tools — they return values, not rendered text — and call a tool only when its result is too large to read (a web fetch, an MCP call), emitting the filtered part: @(tools/call \"name\" args), or its per-tool var @(tools/read {…})."
    "When the answer is an image (a screenshot, a chart, a rendered page), attach it with (emit-image path) rather than describing it."]
   :params {:code {:type :string :description "Clojure program body to run"}
            :timeout {:type :number
                      :description (str "Timeout in seconds — like bash's :timeout: omit or 0 "
                                        "= no deadline; fractional ok, capped at a day")
                      :optional? true}
            :max-output-bytes {:type :number
                               :description "Maximum result bytes (default 16 KiB; capped at 50 KiB)"
                               :optional? true}}
   :execute (fn [args on-update signal ctx]
              (let [code (some-> (:code args) str)]
                (if (str/blank? code)
                  {:content "No code provided." :is-error true}
                  (script/run {:tool "run_code"
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
   :title title))

;; ─── The tool and its row ──────────────────────────────────────────────────

(defn definition
  "The `run_code` tool's record for HANDLE — the table it lists and dispatches
   through, so a script's callable surface is the table its own agent sees."
  [handle]
  (create-tool {:get-all-tools #(registry/get-all-tools handle)
                :get-contributed-tools #(registry/get-contributed-tools handle)
                :select-tools registry/select-tools
                :execute-tool #(registry/execute-tool handle %1 %2 %3)}))

(srce/defplugin plugin
  {:name :tool/run_code
   :inject [:tools]
   :apply (fn [ctx config]
            (registry/mount-tool! ctx (definition (srce/get ctx :tools)) config))})
