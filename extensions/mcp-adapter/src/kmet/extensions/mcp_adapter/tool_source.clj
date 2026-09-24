(ns kmet.extensions.mcp-adapter.tool-source
  "The MCP catalog as a script-sandbox tool source (script.md T2): the
   adapter contributes its cached MCP tools to kmet's builtin `script` tool
   instead of shipping a runtime of its own (the retired mcpScript tool and
   its bb-subprocess JSON-lines engine).

   The contribution is a tool source — register-tool-source! on the
   extension api — so MCP tools appear in the script sandbox's surface
   (tools/list, tools/describe, tools/call) without joining the model's
   tool set: the `mcp` proxy stays the model's single gateway. Records are
   built by tool-proxy/script-tool-records from the metadata cache, and
   calls go through proxy/call-mcp-tool, so lazy connect, failure backoff,
   auth and the output guard apply unchanged.

   Scripting consequence (script.md T2): scripted MCP code now runs in the
   shared in-process SCI sandbox like every other script — print with
   println or use `(sandbox/emit value)`, deref tools/call explicitly
   (`@(tools/call \"server_tool\" args)`), and branch on the kmet result
   map (:is-error/:content), not on mcpScript's {:ok :data} envelope."
  (:require [kmet.extensions.mcp-adapter.tool-proxy :as proxy]
            [kmet.extension :as ext]))

(def source-id
  "The tool-source id the adapter registers under (replaces the retired
   mcpScript tool as the scripted-MCP entry point)."
  :mcp)

(defn sync!
  "Register (or drop) the MCP tool source for the current config: present
   when settings :script-mode is not false, removed otherwise. Called at
   init and on every catalog/config sync; re-registering bumps the registry
   generation, so cached script surfaces rebuild against the new catalog."
  [api state]
  (if (not= false (:script-mode (:settings (:config @state))))
    (ext/register-tool-source! api source-id (fn [] (proxy/script-tool-records @state)))
    (ext/unregister-tool-source! api source-id)))
