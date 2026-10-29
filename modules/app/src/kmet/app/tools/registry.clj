(ns kmet.app.tools.registry
  "Tool registry — the table the model's tools resolve through, registration,
   schema conversion, execution.

   **A tool is a registration, and a built-in tool is a row.** `:bundle/minimal`
   mounts five tool rows (`:tool/read`, `:tool/write`, `:tool/edit`,
   `:tool/bash`, `:tool/run_code`), each injecting `:tools` and calling
   `mount-tool!`, so the table is exactly what the composition mounted: the rows
   a profile kept, minus the ones a patch removed, plus whatever an extension
   registered. There is no static table under it, and nothing sees a tool it did
   not compose.

   Registrations live in a `kmet.app.registry/Registry` handle under TAG
   `:kmet/tool` (and `:kmet/tool-source` for sandbox-only sources), owned by an
   owner path: visible to every engine, outliving one, and disposed with their
   owner rather than copied into an engine and reset by hand. A tool row's
   registration is its fiber's effect (`mount-tool!`); the extension ABI
   registers under its own owner path, so unload disposes what either added.

   **The handle is a parameter.** Every reader and writer takes the registry it
   works on, first, so two agents can hold different tool tables with nothing
   shared between them (docs/architecture.md § Service catalog) and nothing below the app
   can name a process-wide one.

   There is deliberately no default. An earlier draft resolved an installed
   cell when the handle was left out, and a sandboxed load broke it: the
   extension sandbox required a process-bound namespace a second time, so that
   load ran the install with an SCI closure and the shared cell ended up holding
   a handle from the sandbox's copy of the process. A parameter cannot be
   written from a sandbox. A caller that has no handle of its own resolves the
   process one where it lives — `kmet.app.registry/current` — which is what
   kmet1's arity-less façade does while it survives."
  (:require [clojure.string :as str]
            [kmet.libs.json :as json]
            [kmet.app.profile :as profile]
            [kmet.app.registry :as registry]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.util :as tool-util]
            [kmet.srce.core :as srce]))

;; ─── The table ─────────────────────────────────────────────────────────────

(def tools-owner
  "The owner `register-tool!` registers under when a caller names none — the
   app's address for the tools it adds by code. A tool **row** owns its own
   registration instead (`mount-tool!`: the row's `:tool/<name>`), so this is
   not \"the owner of the built-in tools\"."
  [:app :tools])

(defn port-name
  "The plugin and row name for the model-facing tool NAME — `:tool/read`. One
   derivation, so the row `--dump-config` prints, the label `--dump-state`
   shows and the model's own spelling of the tool are the same word."
  [name]
  (keyword "tool" name))

(defn tool-row-name
  "The model-facing tool name a row or patch id names — `:tool/read` →
   `\"read\"` — or nil for an id that is not a tool row. The inverse of
   `port-name`, so a row id is read back rather than re-parsed at each
   call site."
  [id]
  (when (and (keyword? id) (= "tool" (namespace id)))
    (name id)))

(defn- registered-tools
  "REGISTRY's tools as {name tool}, in registration order: a tool row's
   registration first, a later owner's same-name registration after it, so the
   later one wins (see `get-all-tools`)."
  [reg]
  (into {} (registry/entries reg :kmet/tool)))

(defn mount-tool!
  "Mount TOOL into CTX's `:tools` handle as this row's effect — what a tool
   plugin's `:apply` calls with the row's `:config`. The registration is owned
   by and labelled `(port-name (:name tool))`, so disposing the fiber takes the
   tool with it and the mount's effect line names the tool the model sees.

   CONFIG is the composition's say over the record, applied through
   `kmet.app.tools.tool/configure`: the model-facing presentation
   (`:label`, `:description`, `:prompt-snippet`, `:prompt-guidelines`), merged
   over the tool's defaults and validated here — an unknown key or a wrongly
   shaped value throws rather than leaving the default in place as if the
   override had landed. A nil CONFIG mounts TOOL as the plugin wrote it, which
   is what every row without a `:config` gets.

   Returns nil on purpose: a row whose `:apply` returns a value registers a
   second, empty effect (the kernel labels it `apply <row>`), so the caller ends
   with this call and nothing else."
  ([ctx tool] (mount-tool! ctx tool nil))
  ([ctx tool config]
   (let [tool (tool/configure tool config)
         handle (srce/get ctx :tools)
         name (:name tool)
         id (port-name name)]
     (when-not handle
       (throw (ex-info (str "a tool row needs the :tools service: " id
                            " mounted on a ctx that provides none")
                       {:type :kmet/no-tool-service :tool name})))
     (srce/effect ctx
                  (fn []
                    (registry/register! handle :kmet/tool name
                                        (tool/normalize-tool-definition tool)
                                        id))
                  id)
     nil)))

;; ─── Registration ──────────────────────────────────────────────────────────

(defn register-tool!
  "Register a custom tool in REGISTRY after normalizing its provider-facing
   schema, owned by OWNER — `[:app :tools]` by default; an extension passes its
   own owner path so unloading it takes the tool with the owner. Returns the
   registration's deregister fn."
  ([reg tool] (register-tool! reg tool tools-owner))
  ([reg tool owner]
   (let [tool (tool/normalize-tool-definition tool)]
     (registry/register! reg :kmet/tool (:name tool) tool owner))))

(defn unregister-tool!
  "Remove REGISTRY's custom tool by name, from whichever owner registered it,
   or — with OWNER — only that owner's registration (an extension dropping one
   of its own; another owner's same-name tool survives). Returns nil."
  ([reg name] (unregister-tool! reg name nil))
  ([reg name owner]
   (registry/deregister! reg :kmet/tool name owner)
   nil))

(defn get-all-tools
  "Every tool REGISTRY's — the `:tools` service handle's — table holds, name →
   tool: the composition's tool rows first, then whatever a later owner
   registered. A registration that reuses a name already in the table replaces
   it (pi: the extension registry is layered over the base definitions, custom
   wins), so unloading the shadow leaves the tool it shadowed."
  [reg]
  (registered-tools reg))

(defn get-tool
  "Get a tool by NAME from REGISTRY's view — resolved through get-all-tools, so
   the map a caller executes is exactly the map the registry lists."
  [reg name]
  (get (get-all-tools reg) name))

;; ─── Extension-contributed tool sources ────────────────────────────────────
;; Sandbox-only tools: a source contributes tool maps into the run_code tool's
;; surface without joining get-all-tools (the model's tool set). The
;; mcp-adapter contributes its cached MCP catalog this way
;; (docs/architecture.md § Tools and the script sandbox).

(defn- registered-tool-sources
  "REGISTRY's contributed tool sources in registration order: [[id fn] …].
   A source re-registered under the same ID replaces the earlier one."
  [reg]
  (mapv vec (registry/entries reg :kmet/tool-source)))

(defn register-tool-source!
  "Register (or replace) an extension-contributed tool source on NODE,
   owned by OWNER — the app's registry by default; an extension passes its
   own owner path so unloading it disposes the source. ID
   identifies the source; TOOLS-FN is a 0-arg fn returning {name → tool map}
   — the same shape a registered tool map has (:name :label :description
   :parameters :execute, optional :streams?/:contextual?/:prepare-arguments).
   Contributed tools join the run_code sandbox's surface only; the registry
   shadows a colliding name. Re-registering under the same ID replaces the
   source; the next run_code call resolves the new catalog. Returns cells'
   deregister fn."
  ([reg id tools-fn] (register-tool-source! reg id tools-fn tools-owner))
  ([reg id tools-fn owner]
   (registry/register! reg :kmet/tool-source id tools-fn owner)))

(defn unregister-tool-source!
  "Remove REGISTRY's contributed tool source by ID, from whichever owner
   registered it, or — with OWNER — only that owner's registration. Returns
   nil."
  ([reg id] (unregister-tool-source! reg id nil))
  ([reg id owner]
   (registry/deregister! reg :kmet/tool-source id owner)
   nil))

(defn get-contributed-tools
  "Merge every source registered in REGISTRY into its current tools. A source that throws or returns a non-map
   contributes nothing — a broken extension must not take the sandbox's
   surface down."
  [reg]
  (reduce (fn [acc [_ tools-fn]]
            (let [tools (try (tools-fn) (catch Exception _ nil))]
              (if (map? tools) (merge acc tools) acc)))
          {} (registered-tool-sources reg)))

;; ─── Callable surfaces ─────────────────────────────────────────────────────

(defn select-tools
  "Filter ALL (a name → Tool map, e.g. get-all-tools) to a caller's surface:
   ENABLED names (nil = every tool in ALL), plus CONTRIBUTED sandbox-only
   tools (not in get-all-tools; a colliding name keeps ALL's record), minus
   EXCLUDE names (a sandbox must not expose itself). Preserves ALL's order.
   The loop's schema and the run_code bridge's callable set both resolve
   through this, so what a caller lists and what it can dispatch cannot
   drift."
  [all {:keys [enabled contributed exclude]}]
  (let [enabled (or enabled (set (keys all)))
        contributed (apply dissoc (or contributed {}) (keys all))
        keep? (fn [n]
                (and (not (contains? (or exclude #{}) n))
                     (or (contains? enabled n)
                         (contains? contributed n))))]
    (->> (merge all contributed)
         (filter (fn [[n _]] (keep? n)))
         (into (empty all)))))

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
  "Execute REGISTRY's tool by name with given arguments.
   opts — {:on-update (fn [partial]) streaming callback (passed to the
   tool's execute when it declares :streams?); :signal — cancel atom; :ctx
   — extension context map for :contextual? tools (pi: execute(toolCallId,
   params, signal, onUpdate, ctx)), carrying the run's ctx under :srce-ctx
   when the call belongs to one (`kmet.app.loop.tool-exec`, and the sandbox
   realm `run_code` substitutes for a script's inner calls — `kmet.app.sandbox`);
   :tools — a name → tool map override for
   the run_code sandbox, which passes its own surface so the script's listing
   is authoritative for dispatch (contributed sandbox tools are not in
   get-all-tools). A :contextual? tool's execute always receives 4 args
   (fn [args on-update signal ctx]) — pi passes the signal and ctx
   unconditionally; other tools keep (fn [args]) / (fn [args on-update]).
   Returns {:content str :is-error bool}."
  [reg tool-name args & [opts]]
  (let [{:keys [on-update signal ctx tools]} (or opts {})
        tool (if tools (get tools tool-name) (get-tool reg tool-name))]
    (if tool
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

;; ─── Default tool selection (pi: defaultTools) ─────────────────────────────

(defn bundle-tool-names
  "The model-facing tool names BUNDLE's rows declare, in composition order: the
   `:tool/<name>` row ids read back through `tool-row-name`, so `{:id :tool/ls}`
   contributes `\"ls\"` and a row that is not a tool contributes nothing. Data
   only — a row's `:plugin` is never resolved."
  [bundle]
  (into [] (keep (comp tool-row-name :id)) (:rows bundle)))

(def builtin-tool-names
  "Names of the built-in tools, in composition order — the base a modifier-only
   `:default-tools` list modifies, and the set the setting can exclude.
   Pi: DEFAULT_TOOL_NAMES; kmet ships every built-in active by default, so the
   base is the full built-in set (docs/configuration.md).

   Derived from `:bundle/minimal`'s rows, not restated beside them: the
   composition is where a built-in tool is declared, so adding a tool row adds
   its name here and patching one out of the bundle takes it away, with no
   second list to keep in step. Reading the table is static (`kmet.app.profile`
   is composition data and resolves no plugin), so the settings layer can still
   ask which names exist before any composition mounts."
  (bundle-tool-names (profile/bundle (profile/builtin) :bundle/minimal)))

(defn tool-selection-modifier?
  "True when a :default-tools entry is a +name/-name modifier (pi:
   isToolModifier). The definition is kmet.app.tools.util's, re-exported
   for callers of the registry."
  [entry]
  (tool-util/tool-selection-modifier? entry))

(defn resolve-default-tools
  "Resolve a merged :default-tools entry list to the initial built-in tool
   selection (pi: SettingsManager.resolveDefaultTools). Plain names replace
   the default built-in set; +name adds and -name removes in list order; a
   list of only +name/-name entries modifies the default set; an empty list
   selects no built-ins (extension tools are unaffected). Unknown names pass
   through — they select nothing; callers match against the live registry.
   A non-list value resolves empty and non-string entries are dropped (pi:
   getDefaultTools). nil when ENTRIES is nil: no setting, every tool stays
   active."
  [entries]
  (when entries
    ;; pi: getDefaultTools filters to strings and treats a non-array value
    ;; as [] before resolving; settings files are not validated
    (let [entries (if (sequential? entries) (filterv string? entries) [])
          plain (filterv (complement tool-selection-modifier?) entries)
          base (cond
                 (seq plain) plain
                 (empty? entries) []
                 :else builtin-tool-names)]
      (reduce (fn [names entry]
                (if (tool-selection-modifier? entry)
                  (let [n (subs entry 1)]
                    (if (str/starts-with? entry "+")
                      (if (some #(= n %) names) names (conj names n))
                      (filterv #(not= n %) names)))
                  names))
              (vec base)
              entries))))

(defn default-tool-exclusions
  "Built-in tool names a resolved :default-tools SELECTION leaves out — the
   :exclude list a caller passes to select-tools. nil when SELECTION is nil
   (no setting; every built-in stays available)."
  [selection]
  (when selection
    (let [selected (set selection)]
      (into #{} (remove selected) builtin-tool-names))))
