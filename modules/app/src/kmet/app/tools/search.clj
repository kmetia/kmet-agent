(ns kmet.app.tools.search
  "Deferred-tool discovery: the `tool_search` tool and the name the declared
   set is narrowed by.

   A `:deferred` tool (or a `:sandbox` source's tool) is callable without being
   declared. `tool_search` ranks the searchable, not-yet-declared tools with
   `kmet.libs.ranking` and loads every match, so the next request declares
   them. The tool is `:model-only` — a script may not change what the model
   sees — and is declared only while a `:deferred` tool exists to find."
  (:require [kmet.app.tools.tool :as tool]))

(def tool-search-name
  "The model-facing name of the discovery tool. Its record is the `:tool/
   tool_search` row; the declared set reads the name to decide whether the tool
   is worth declaring (pi: the MCP extension activates `tool_search` only for a
   server with `deferred` exposure)."
  "tool_search")

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
   run (a direct call, a test): a match that cannot be recorded is not loaded."
  nil)
