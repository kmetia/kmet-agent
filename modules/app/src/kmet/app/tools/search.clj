(ns kmet.app.tools.search
  "Deferred-tool discovery: `tool_search`, the tool that makes a `:deferred`
   tool reachable.

   A `:deferred` tool (or a `:sandbox` source's tool) is callable from a script
   without being declared to the model. `tool_search` ranks the searchable,
   not-yet-declared tools with `kmet.libs.ranking` — the same scorer `mcp
   search` reads its cache with — and *loads* every match it returns, so the
   next request declares them (`kmet.app.loop.state/declared-tools-for`). pi:
   `extensions/tool-search/tool.ts`.

   The tool is `:model-only` (a script that could call it would change what the
   model sees mid-script), its description is a constant — registering a source
   never rewrites a schema the prefix caches — and it is declared only while
   the table holds a `:deferred` tool to find."
  (:require [clojure.string :as str]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.tool :as tool]
            [kmet.libs.ranking :as ranking]
            [kmet.srce.core :as srce]))

(def tool-search-name
  "The model-facing name of the discovery tool. Its record is the
   `:tool/tool_search` row; the declared set reads the name to decide whether
   the tool is worth declaring at all (pi: the MCP extension activates
   `tool_search` only for a server with `deferred` exposure)."
  "tool_search")

(def default-limit
  "How many matches one call loads when the model names no `limit` (pi:
   DEFAULT_TOOL_SEARCH_LIMIT)."
  8)

(defn deferred-tool?
  "Does TOOL wait for `tool_search` before it is declared?"
  [tool]
  (= :deferred (tool/exposure tool)))

(def ^:dynamic *run*
  "The run's tool-set view, bound by `kmet.app.loop` for the whole prompt:
   {:declared (fn [] → the declared name set)
    :load!    (fn [names] → record the promotion)}
   `tool_search` reads `:declared` to skip what the model already has and calls
   `:load!` with its matches. A tool body cannot reach the agent, so this is
   the same seam `run_code` uses for the run state a tool needs. nil outside a
   run (a direct call, a test): a match that cannot be recorded is not loaded,
   so the call reports none (pi: `options.tools` absent)."
  nil)

(defn- schema-text
  "The descriptions and property names of SCHEMA, recursively — the words a
   model searches with (pi: schemaText)."
  [schema]
  (when (map? schema)
    (concat (when (string? (:description schema)) [(:description schema)])
            (mapcat (fn [[name property]]
                      (cons (str name) (schema-text property)))
                    (:properties schema))
            (schema-text (:items schema))
            (mapcat #(schema-text (get schema %)) [:anyOf :oneOf :allOf]))))

(defn- document
  "The ranker document for NAME/TOOL: the name, and the description plus the
   parameter names and descriptions joined into the searchable text (pi:
   createToolSearchDocument)."
  [{:keys [name description parameters]}]
  {:name name
   :description (str/join " " (remove str/blank? (cons description (schema-text parameters))))})

(defn- candidates
  "The tools a search may load: TABLE's `:deferred` tools and CONTRIBUTED (a
   `:sandbox` source's catalog), minus the names DECLARED already — a
   contribution that collides with a table tool resolves to the table's
   record, as it does everywhere else."
  [table contributed declared]
  (->> (concat (filter (comp deferred-tool? val)
                       (remove (fn [[name _]] (contains? declared name)) table))
               (remove (fn [[name _]] (or (contains? declared name) (contains? table name)))
                       contributed))
       (mapv (fn [[name t]] [name (assoc t :name (or (:name t) name))]))))

(defn- search-and-load
  "Rank CANDIDATES for QUERY and load every match, up to LIMIT (pi:
   searchAndLoad). Returns [{:name :description}] for the reply and the
   `:details` — the tool's own description, first line only. Nothing is loaded
   outside a run: a promotion that cannot be recorded is not a load."
  [run candidates query limit]
  (if (nil? run)
    []
    (let [by-name (into {} candidates)
          matches (ranking/rank (map (comp document second) candidates) query limit)]
      (when (seq matches)
        ((:load! run) (mapv :name matches)))
      (mapv (fn [{:keys [name]}]
              {:name name :description (or (:description (get by-name name)) "")})
            matches))))

(defn- render
  "The reply text: pi's shape — a count and where the tools came from, then one
   line per tool (`- name: first line of its description`)."
  [matches]
  (if (empty? matches)
    "No matching tools found."
    (str "Loaded " (count matches) " tool" (when (not= 1 (count matches)) "s")
         ". They are available from your next call:\n"
         (str/join "\n"
                   (map (fn [{:keys [name description]}]
                          (str "- " name ": "
                               (str/trim (or (first (str/split-lines (str description))) ""))))
                        matches)))))

(defn- bad-limit [limit]
  {:content (str "limit must be a positive integer, got " (pr-str limit))
   :is-error true})

(defn- resolve-limit
  "LIMIT as a positive count, DEFAULT when absent, nil when it is not one."
  [limit]
  (if (nil? limit)
    default-limit
    (let [n (when (number? limit) (long limit))]
      (when (and n (pos? n) (== n limit)) n))))

(defn- search
  "Execute one `tool_search` call: validate, rank, load, render."
  [get-all-tools get-contributed-tools query limit]
  (if (str/blank? (str query))
    {:content "query must not be empty" :is-error true}
    (if-let [limit (resolve-limit limit)]
      (let [run *run*
            declared (if run ((:declared run)) #{})
            matches (search-and-load run
                                     (candidates (get-all-tools) (get-contributed-tools) declared)
                                     query limit)]
        {:content (render matches)
         :is-error false
         :details {:loaded (mapv :name matches)}})
      (bad-limit limit))))

(def description
  "The tool's static description (pi's TOOL_SEARCH_DESCRIPTION, without the
   markdown heading): a constant that names itself and no namespace, so
   registering a source never changes it and never invalidates a cached
   prefix."
  (str "Searches over deferred tool metadata and exposes matching tools for the next model call.\n\n"
       "Some of the tools, such as tools of MCP servers, may not have been provided to you upfront, "
       "and you should use this tool (`" tool-search-name "`) to search for the required tools. "
       "For MCP tool discovery, always use `" tool-search-name "`."))

(defn create-tool
  "Build the `tool_search` Tool record. OPTS wires the registry seams (the
   `:tool/tool_search` row builds the composition's record via `definition`;
   tests build their own):
     :get-all-tools          — 0-arg registry tool map (the model's table)
     :get-contributed-tools  — 0-arg sandbox sources' tool map"
  [{:keys [get-all-tools get-contributed-tools]}]
  (tool/make-tool
   :name tool-search-name
   :label "Tool search"
   :description description
   :prompt-snippet "Search for tools that are not loaded yet and load the matches"
   :exposure :model-only
   :params {:query {:type :string :description "Search query for deferred tools."}
            :limit {:type :number
                    :description (str "Maximum number of tools to return. Defaults to "
                                      default-limit ".")
                    :optional? true}}
   :execute (fn [args]
              (search get-all-tools get-contributed-tools (:query args) (:limit args)))))

;; ─── The tool and its row ──────────────────────────────────────────────────

(defn definition
  "The `tool_search` record for HANDLE — the table it searches and the sandbox
   sources it can promote from."
  [handle]
  (create-tool {:get-all-tools #(registry/get-all-tools handle)
                :get-contributed-tools #(registry/get-contributed-tools handle)}))

(srce/defplugin plugin
  {:name :tool/tool_search
   :inject [:tools]
   :apply (fn [ctx config]
            (registry/mount-tool! ctx (definition (srce/get ctx :tools)) config))})
