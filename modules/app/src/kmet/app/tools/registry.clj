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
   `:kmet/tool` (and `:kmet/tool-source` for contributed catalogs,
   `:kmet/eval-source` for the values an eval source contributes), owned by an
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

;; ─── Extension-contributed tool sources ────────────────────────────────────
;; A source contributes a whole catalog without registering each tool. Its
;; `:exposure` decides where the catalog lands (pi: a server's exposure):
;; `:sandbox` (the default, pi's `codemode`) is callable from a script sandbox only
;; and never declared; `:deferred` joins the model-facing table undeclared and
;; becomes declared once `tool_search` loads one of its tools. The mcp-adapter
;; contributes its cached MCP catalog as a `:sandbox` source
;; (docs/architecture.md § Tools and the script sandbox).

(def ^:private tool-source-exposures
  "The exposures a contributed tool source may take. A source's exposure is
   the source's property, never a settings key: a per-project exposure override
   could make a `:sandbox` tool un-disableable."
  #{:sandbox :deferred})

(defn- registered-tool-sources
  "REGISTRY's tool-source registrations in display order, one per id
   (`registry/effective-entries`: the owner that took a name keeps it)."
  [reg]
  (mapv second (registry/effective-entries reg :kmet/tool-source)))

(defn- source-tools
  "SOURCE's catalog, or {} when its fn throws or returns a non-map — a broken
   extension must not take the sandbox's surface or the tool table down."
  [source]
  (let [tools (try ((:tools-fn source)) (catch Exception _ nil))]
    (if (map? tools) tools {})))

(defn- sources-with-exposure [reg exposure]
  (filterv #(= exposure (:exposure %)) (registered-tool-sources reg)))

(defn- deferred-sources
  "REGISTRY's `:deferred` tool sources in display order."
  [reg]
  (sources-with-exposure reg :deferred))

(defn- source-owner
  "The owner that registered SOURCE under `:kmet/tool-source`, or nil."
  [reg source]
  (some (fn [[owner value]] (when (= value source) owner))
        (registry/by-owner reg :kmet/tool-source (:id source))))

(defn- merge-source-tools
  "SOURCES' catalogs merged in registration order."
  [sources]
  (reduce (fn [acc source] (merge acc (source-tools source))) {} sources))

(defn- deferred-source-tools
  "The tools REGISTRY's `:deferred` sources contribute, each tagged with the
   source's exposure and named by its key — the part of the model-facing table
   no `register-tool!` call installed."
  [reg]
  (into {}
        (for [[name tool] (merge-source-tools (deferred-sources reg))]
          [name (tool/normalize-tool-definition
                 (assoc tool :name (or (:name tool) name) :exposure :deferred))])))

(defn deferred-tool-owners
  "A map of model-facing tool name -> the owner of the `:deferred`
   source that contributes it. The resolution matches `get-all-tools`:
   sources merge in registration order, so a later source wins a
   colliding name, and a `:kmet/tool` registration shadows a source
   with the same name. `/config` reads this because a deferred tool is
   not itself a tool registration."
  [reg]
  (let [registered (set (keys (registered-tools reg)))]
    (into {}
          (mapcat (fn [source]
                    (let [owner (source-owner reg source)]
                      (map (fn [name] [name owner])
                           (remove registered
                                   (keys (source-tools source)))))))
          (deferred-sources reg))))

(defn register-tool-source!
  "Register (or replace) an extension-contributed tool source on REGISTRY. ID
   identifies the source; TOOLS-FN is a 0-arg fn returning {name → tool map}
   — the same shape a registered tool map has (:name :label :description
   :parameters :execute, optional :streams?/:contextual?/:prepare-arguments)
   — and must return the *current* catalog, not a snapshot.

   OPTS:
     :exposure    — `:sandbox` (the default): callable from a script
                    sandbox only, never declared; `:deferred`: also joins the
                    model-facing table, undeclared until `tool_search` loads
                    the tool.
     :description — one line naming the source, for the deferred-source listing
                    the model reads; never used for its individual tools.
     :owner       — the registration's owner path; an extension passes its own
                    so unloading it disposes the source.

   Re-registering under the same ID replaces the source; the next call resolves
   the new catalog. A registered tool shadows a contributed name. Returns the
   deregister fn."
  ([reg id tools-fn] (register-tool-source! reg id tools-fn nil))
  ([reg id tools-fn opts]
   (let [{:keys [exposure description owner] :or {exposure :sandbox}} opts]
     (when-not (contains? tool-source-exposures exposure)
       (throw (ex-info (str "unknown tool-source exposure " (pr-str exposure)
                            " for " (pr-str id) " — one of "
                            (pr-str (vec (sort tool-source-exposures))))
                       {:type :kmet.app/bad-tool-source-exposure
                        :source id
                        :exposure exposure})))
     (registry/register! reg :kmet/tool-source id
                         {:id id
                          :exposure exposure
                          :description description
                          :tools-fn tools-fn}
                         (or owner tools-owner)))))

(defn unregister-tool-source!
  "Remove REGISTRY's contributed tool source by ID, from whichever owner
   registered it, or — with OWNER — only that owner's registration. Returns
   nil."
  ([reg id] (unregister-tool-source! reg id nil))
  ([reg id owner]
   (registry/deregister! reg :kmet/tool-source id owner)
   nil))

(defn get-contributed-tools
  "Merge every `:sandbox` source registered in REGISTRY into its current
   tools — the extra surface a script sandbox adds to its callable set. A
   `:deferred` source is not here: its tools are in `get-all-tools`."
  [reg]
  (merge-source-tools (sources-with-exposure reg :sandbox)))

(defn get-deferred-sources
  "The `:deferred` tool sources REGISTRY holds, in registration order, as
   [{:id :description}] — what the model is told exists without being declared
   yet. The catalog is not part of the report: a listing names a source, one
   line, never its tools. OPTS `:selected` is a set or map of tool names; when
   supplied, a source is kept only when it contributes at least one selected
   tool. nil reports every source."
  ([reg] (get-deferred-sources reg nil))
  ([reg {:keys [selected]}]
   (into []
         (keep (fn [source]
                 (when (or (nil? selected)
                           (some #(contains? selected %)
                                 (keys (source-tools source))))
                   (select-keys source [:id :description]))))
         (deferred-sources reg))))

;; ─── Eval sources ──────────────────────────────────────────────────────────
;; An eval source contributes live values to every eval environment: its
;; `sources-fn` is read when an environment is built and on later calls,
;; returning the current namespaces, macros and `/eval` verbs. The registration lives on the same `:tools` handle as the
;; tool sources, so unloading an owner sweeps both. The core source is the row
;; `:tool/eval` itself; extensions register through their own owner path.

(def eval-sources-owner
  "The owner `register-eval-source!` registers under when a caller names none."
  [:app :eval-sources])

(defn- registered-eval-sources
  "REGISTRY's eval-source registrations in display order, one per id."
  [reg]
  (mapv second (registry/effective-entries reg :kmet/eval-source)))

(defn register-eval-source!
  "Register (or replace) an eval source on REGISTRY. ID identifies the source;
   SOURCES-FN is a 1-arg fn receiving the call context and returning the
   current contribution:

     {:doc      \"one line\"
      :ns       {'license {'add! (fn [path] ...)}}
      :macros   {'license {'with-license (fn [_form _env & args] ...)}}
      :commands {:lint {:description \"Lint\" :run (fn [{:keys [args]}] ...)}}}

   The service reads it when an eval environment is built and on each later
   eval call (the build's read is reused by the first call); `/eval sources`
   and help read it too. It receives
   `{:cwd :project-dir :agent-dir :signal :verbs}`; a throwing source or a
   bad shape contributes nothing and is reported. OPTS may carry `:owner` —
   an extension passes its own owner path so unload disposes the source.
   Returns the deregister fn."
  ([reg id sources-fn] (register-eval-source! reg id sources-fn nil))
  ([reg id sources-fn opts]
   (when-not (fn? sources-fn)
     (throw (ex-info (str "eval source " (pr-str id) " needs a sources-fn")
                     {:type :kmet.app/bad-eval-source :source id})))
   (registry/register! reg :kmet/eval-source id
                       {:id id :sources-fn sources-fn}
                       (or (:owner opts) eval-sources-owner))))

(defn unregister-eval-source!
  "Remove REGISTRY's eval source by ID, from whichever owner registered it,
   or — with OWNER — only that owner's registration. Returns nil."
  ([reg id] (unregister-eval-source! reg id nil))
  ([reg id owner]
   (registry/deregister! reg :kmet/eval-source id owner)
   nil))

(defn get-eval-sources
  "Every eval source registered in REGISTRY, in display order, as
   `[{:id :sources-fn}]`. The service reads this per call; a source's current
   values are the ones its fn returns then."
  [reg]
  (registered-eval-sources reg))

;; ─── The model-facing table ────────────────────────────────────────────────

(defn get-all-tools
  "Every tool REGISTRY's — the `:tools` service handle's — table holds, name →
   tool: the tools a `:deferred` source contributes, then the composition's
   tool rows, then whatever a later owner registered. A registration that
   reuses a name already in the table replaces it (pi: the extension registry
   is layered over the base definitions, custom wins), so unloading the shadow
   leaves the tool it shadowed.

   A deferred source's tools are here (tagged `:exposure :deferred`) because
   they are model-facing: the resources selection applies to them like any
   registered tool, and `tool_search` promotes one from deferred to declared.
   A `:sandbox` source's tools are deliberately not here
   (`get-contributed-tools`), so no selection can declare them."
  [reg]
  (merge (deferred-source-tools reg) (registered-tools reg)))

(defn get-tool
  "Get a tool by NAME from REGISTRY's view — resolved through get-all-tools, so
   the map a caller executes is exactly the map the registry lists."
  [reg name]
  (get (get-all-tools reg) name))

;; ─── Callable surfaces ─────────────────────────────────────────────────────

(defn select-tools
  "Filter ALL (a name → Tool map, e.g. get-all-tools) to a caller's surface:
   ENABLED names (nil = every tool in ALL), plus CONTRIBUTED tools (a
   `:sandbox` source's catalog — not in ALL, so exposure is not consulted here;
   a colliding name keeps ALL's record), minus EXCLUDE names (the script
   surface hides its own tools). The result is a map with ALL's keys (its
   order is the map's — a table past array-map size does not keep insertion
   order). The loop's schema and the run_code bridge's callable set both
   resolve through this, so what a caller lists and what it can dispatch
   cannot drift."
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

(defn promotable-tools
  "The tools a search can promote: ALL's `:deferred` tools plus CONTRIBUTED (a
   `:sandbox` source's catalog). Deferred tools are filtered by the selection
   (ENABLED names, nil = everything); contributed tools are not selectable and
   are never filtered by it. A deferred tool the selection leaves out is not
   promotable: loading it could not declare it. A contributed name that ALL
   holds too keeps ALL's record and is not promotable either.

   This is the one answer to \"what can a search reach\": the discovery gate
   (`kmet.app.loop.state/declared-tools-for`) declares `tool_search` from it
   and `tool_search` ranks its own candidate list through it, so a request
   cannot carry a search for nothing and a search cannot promise a tool the
   wire would never declare. Returns name → Tool."
  [all contributed {:keys [enabled]}]
  (let [contributed (apply dissoc (or contributed {}) (keys all))
        deferred (into {} (filter (fn [[_ t]] (= :deferred (tool/exposure t))) all))
        kept? (fn [name]
                (or (nil? enabled) (contains? enabled name)))]
    (into {}
          (filter (fn [[name t]]
                    (or (not= :deferred (tool/exposure t))
                        (kept? name)))
                  (merge deferred contributed)))))

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

(defn bundle-default-tool-names
  "The model-facing tool names BUNDLE's rows declare **on by default**: the
   `:tool/<name>` ids whose row does not carry `:default-enabled? false`. A
   marked row is mounted but off until selected; the marker is the only
   difference from `bundle-tool-names`. Data only, like that function."
  [bundle]
  (into [] (keep (fn [row]
                   (when-not (false? (:default-enabled? row))
                     (tool-row-name (:id row)))))
        (:rows bundle)))

(def ^:private minimal-bundle
  "The composition bundle a run mounts first; its tool rows are the built-in
   tool declarations."
  (profile/bundle (profile/builtin) :bundle/minimal))

(defn builtin-tool-rows
  "The `:bundle/minimal` rows whose ids are tool rows (`:tool/<name>`), in
   composition order. Data only: a row carries its plugin source, so a
   composition editor can re-insert it into a later layer."
  []
  (filterv (comp tool-row-name :id) (:rows minimal-bundle)))

(def builtin-tool-names
  "Names of the built-in tools, in composition order — the full set: a
   removal patch targets one, and resume logic distinguishes them from
   extension tools. A row marked
   `:default-enabled? false` is still here; only `default-tool-names` leaves it
   out of the default selection.

   Derived from `:bundle/minimal`'s rows, not restated beside them: the
   composition is where a built-in tool is declared, so adding a tool row adds
   its name here and patching one out of the bundle takes it away, with no
   second list to keep in step. Reading the table is static (`kmet.app.profile`
   is composition data and resolves no plugin), so the settings layer can still
   ask which names exist before any composition mounts."
  (bundle-tool-names minimal-bundle))

(def default-tool-names
  "The default built-in tool names: `:bundle/minimal`'s tool rows without
   `:default-enabled? false` (run_code and eval opt out). Used by
   `default-off-tool?` and the runtime's built-in default; the resources
   selection starts from this set."
  (bundle-default-tool-names minimal-bundle))

(defn default-off-tool?
  "True when built-in tool NAME carries `:default-enabled? false`: mounted, but
   off until a selection names it. False for an extension tool (the live set
   defaults on) and for a default-on built-in."
  [name]
  (and (contains? (set builtin-tool-names) name)
       (not (contains? (set default-tool-names) name))))

(defn tool-selection-modifier?
  "True when a selection entry is a +name/-name modifier (pi:
   isToolModifier). The definition is kmet.app.tools.util's, re-exported
   for callers of the registry."
  [entry]
  (tool-util/tool-selection-modifier? entry))

(defn resolve-selection
  "Resolve a `+name`/`-name` selection entry list over BASE-NAMES (pi:
   SettingsManager.resolveDefaultTools). Plain names replace the base set;
   +name adds and -name removes in list order; a list of only modifiers
   modifies the base set; an empty list selects nothing. Unknown names pass
   through — they select nothing; callers match against the live registry. A
   non-list value resolves empty and non-string entries are dropped. nil when
   ENTRIES is nil: no setting, every base name stays selected. Shared by the
   tool and resource selection paths."
  [entries base-names]
  (when entries
    ;; a non-array value is treated as [] before resolving; settings files are
    ;; not validated
    (let [entries (if (sequential? entries) (filterv string? entries) [])
          plain (filterv (complement tool-selection-modifier?) entries)
          base (cond
                 (seq plain) plain
                 (empty? entries) []
                 :else base-names)]
      (reduce (fn [names entry]
                (if (tool-selection-modifier? entry)
                  (let [n (subs entry 1)]
                    (if (str/starts-with? entry "+")
                      (if (some #(= n %) names) names (conj names n))
                      (filterv #(not= n %) names)))
                  names))
              (vec base)
              entries))))
