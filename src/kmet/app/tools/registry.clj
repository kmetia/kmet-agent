(ns kmet.app.tools.registry
  "Tool registry — built-in tool map, custom tool registration, schema conversion, execution."
  (:require [kmet.libs.json :as json]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.read :as read]
            [kmet.app.tools.write :as write]
            [kmet.app.tools.edit :as edit]
            [kmet.app.tools.bash :as bash]
            [kmet.app.tools.script :as script]))

(declare built-in-tools)

;; ─── Registry generation ────────────────────────────────────────────────────

(defonce ^:private registry-generation (atom 0))

(defn tool-registry-generation
  "Monotonic counter bumped on every registry mutation (register-tool!,
   unregister-tool!). The script sandbox's base-context cache keys on it, so
   a changed registry can never be served a stale sandbox surface."
  []
  @registry-generation)

;; ─── Built-in tools ─────────────────────────────────────────────────────────

(def ^:private base-tools
  "Built-in tools without registry seams; the script tool joins at the bottom
   (it dispatches through this namespace, so its record needs the fns)."
  {"read"  (tool/make-tool
            :name "read"
            :label "Read file"
            :description "Read the contents of a file. Supports text files and images (jpg, png, gif, webp, bmp). Images are sent as attachments. For text files, output is truncated to 2000 lines or 50KB (whichever is hit first). Use offset/limit for large files. When you need the full file, continue with offset until complete."
            :prompt-snippet "Read file contents"
            :prompt-guidelines ["Use read to examine files instead of cat or sed."]
            :params {:path   {:type :string :description "Path to the file to read (relative or absolute)"}
                     :offset {:type :number :description "Line number to start reading from (1-indexed)" :optional? true}
                     :limit  {:type :number :description "Maximum number of lines to read" :optional? true}}
            :execute read/execute
            :title read/title)
   "write" (tool/make-tool
            :name "write"
            :label "Write file"
            :description "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Automatically creates parent directories."
            :prompt-snippet "Create or overwrite files"
            :prompt-guidelines ["Use write only for new files or complete rewrites."]
            :params {:path    {:type :string :description "File path to write to (relative or absolute)"}
                     :content {:type :string :description "Content to write to the file"}}
            :execute write/execute
            :title write/title)
   "edit"  (tool/make-tool
            :name "edit"
            :label "Edit file"
            :description "Edit a single file using exact text replacement. Every edits[].oldText must match a unique, non-overlapping region of the original file. If two changes affect the same block or nearby lines, merge them into one edit instead of emitting overlapping edits. Do not include large unchanged regions just to connect distant changes."
            :prompt-snippet "Make precise file edits with exact text replacement, including multiple disjoint edits in one call"
            :prompt-guidelines ["Use edit for precise changes (edits[].oldText must match exactly)"
                                "When changing multiple separate locations in one file, use one edit call with multiple entries in edits[] instead of multiple edit calls"
                                "Each edits[].oldText is matched against the original file, not after earlier edits are applied. Do not emit overlapping or nested edits. Merge nearby changes into one edit."
                                "Keep edits[].oldText as small as possible while still being unique in the file. Do not pad with large unchanged regions."]
            :render-shell :self
             ;; Pi: editSchema — edits is an array of {oldText, newText}
            :parameters {:type "object"
                         :properties {"path" {:type "string"
                                              :description "Path to the file to edit (relative or absolute)"}
                                      "edits" {:type "array"
                                               :items {:type "object"
                                                       :properties {"oldText" {:type "string"
                                                                               :description "Exact text for one targeted replacement. It must be unique in the original file and must not overlap with any other edits[].oldText in the same call."}
                                                                    "newText" {:type "string"
                                                                               :description "Replacement text for this targeted edit."}}
                                                       :required ["oldText" "newText"]}
                                               :description "One or more targeted replacements. Each edit is matched against the original file, not incrementally. Do not include overlapping or nested edits. If two changes touch the same block or nearby lines, merge them into one edit instead."}}
                         :required ["path" "edits"]}
            :execute edit/execute
            :title edit/title)
   ;; Pi: createBashTool(cwd) with default options
   "bash"  (bash/create-tool)}
   ;; grep/find/ls are not builtins — they ship as opt-in extensions
   ;; (extensions/grep-tool.clj, find-tool.clj, ls-tool.clj)
  )

;; ─── Tool schema helpers ────────────────────────────────────────────────────

;; ─── Extended tool registry ────────────────────────────────────────────────

(defonce ^:private custom-tools (atom {}))

(defn register-tool!
  "Register a custom tool after normalizing its provider-facing schema."
  [tool]
  (let [tool (tool/normalize-tool-definition tool)]
    (swap! custom-tools assoc (:name tool) tool)
    (swap! registry-generation inc)))

(defn unregister-tool!
  "Remove a custom tool."
  [name]
  (swap! custom-tools dissoc name)
  (swap! registry-generation inc))

(defn get-all-tools
  "Get all available tools (built-in + custom). A custom tool that reuses a
   built-in's name shadows it (pi: the extension registry is layered over the
   base definitions, custom wins)."
  []
  (merge built-in-tools @custom-tools))

(defn get-tool
  "Get a tool by name — resolved through get-all-tools, so the map a caller
   executes is exactly the map the registry lists (custom shadows built-in)."
  [name]
  (get (get-all-tools) name))

;; ─── Execution ─────────────────────────────────────────────────────────────

(defn- normalize-args
  "Guard so tool execute fns always receive map args. Defense in depth — the
   stream accumulator in loop.clj already degrades unparseable tool-call
   arguments to {} (pi: parseStreamingJson); this catches any other path
   where string args reach execute-tool so tools report a validation error
   instead of a ClassCastException from assoc/merge on a string."
  [args]
  (if (map? args)
    args
    (try
      (let [parsed (json/parse-string args true)]
        (if (map? parsed) parsed {}))
      (catch Exception _ {}))))

(defn execute-tool
  "Execute a tool by name with given arguments.
   opts — {:on-update (fn [partial]) streaming callback (passed to the
   tool's execute when it declares :streams?); :signal — cancel atom; :ctx
   — extension context map for :contextual? tools (pi: execute(toolCallId,
   params, signal, onUpdate, ctx)). A :contextual? tool's execute always
   receives 4 args (fn [args on-update signal ctx]) — pi passes the signal
   and ctx unconditionally; other tools keep (fn [args]) / (fn [args
   on-update]). Returns {:content str :is-error bool}."
  [tool-name args & [opts]]
  (let [{:keys [on-update signal ctx]} (or opts {})]
    (if-let [tool (get-tool tool-name)]
      (try
        ;; pi prepareToolCallArguments: a tool's :prepare-arguments shim
        ;; rewrites the raw args before schema validation/execution
        (let [args (normalize-args args)
              args (if-let [prepare (:prepare-arguments tool)]
                     (prepare args)
                     args)]
          (cond
            (:contextual? tool)
            ((:execute tool) args on-update signal ctx)

            (and on-update (:streams? tool))
            ((:execute tool) args on-update)

            :else
            ((:execute tool) args)))
        (catch Exception e
          {:content (str "Error executing " tool-name ": " (ex-message e))
           :is-error true}))
      {:content (str "Unknown tool: " tool-name) :is-error true})))

;; ─── The full built-in map ─────────────────────────────────────────────────

(def built-in-tools
  "Map of tool name → Tool record for all built-in tools. The script tool
   carries this namespace's seams: it lists through get-all-tools and
   dispatches inner calls through execute-tool, so script resolution is the
   same as the model's."
  (assoc base-tools
         "script"
         (script/create-tool {:get-all-tools get-all-tools
                              :execute-tool execute-tool
                              :generation-fn tool-registry-generation})))
