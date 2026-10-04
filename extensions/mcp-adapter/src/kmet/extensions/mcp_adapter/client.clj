(ns kmet.extensions.mcp-adapter.client
  "The mcp-adapter's view of the shared MCP client (kmet.libs.mcp.*).

   The implementation moved to the libs layer so it can be tested and
   reused outside the extension context; this namespace stays as a facade
   over the lib surface the adapter actually consumes, so the other
   extension namespaces and the validation scripts keep their imports and
   call sites unchanged. See mcp.md Phase 1.

   Note for REPL work: these defs capture the lib function values, so after
   editing the lib, reload this namespace too — `(require
   'kmet.libs.mcp.client :reload)` alone leaves stale values here."
  (:require [kmet.libs.mcp.client :as mcp]
            [kmet.libs.mcp.protocol :as protocol]
            [kmet.libs.mcp.transport :as transport]))

(def progress-token protocol/progress-token)
(def header-value transport/header-value)
(def request! mcp/request!)
(def close! mcp/close!)
(def alive? mcp/alive?)
(def last-used mcp/last-used)
(def list-all-tools mcp/list-all-tools)
(def list-all-prompts mcp/list-all-prompts)
(def get-prompt mcp/get-prompt)
(def list-all-resources mcp/list-all-resources)
(def list-all-resource-templates mcp/list-all-resource-templates)
(def read-resource mcp/read-resource)
(def expand-uri-template mcp/expand-uri-template)
(def connect! mcp/connect!)
(def modern? mcp/modern?)
(def listen! mcp/listen!)
(def listening? mcp/listening?)
(def ack-method protocol/ack-method)
(def format-result protocol/format-result)
