---
name: mcp
description: Access MCP (Model Context Protocol) servers through kmet's mcp gateway tool. Use when the user asks to work with MCP servers, MCP tools, or when you need tools from servers like filesystem, chrome-devtools, notion, or any other configured MCP server.
---

# MCP access via the `mcp` proxy tool

kmet talks to MCP servers through one lazy `mcp` proxy tool (~200 tokens)
instead of registering every server tool in your context. Servers are
configured in `~/.kmet/agent/mcp.edn` (global; the agent dir —
`KMET_CODING_AGENT_DIR` moves it) and `.kmet/mcp.edn`
(project) — see the mcp-adapter README for the full config reference.

## Workflow: search → describe → call

Never guess tool names. Discover first (reads the local cache, no server
spawn):

1. **Status** — `mcp({})` shows servers, states, tool counts.
2. **Search** — `mcp({ search: "screenshot" })` finds tools by name or
   description (name matches rank first). `regex: true` for regex search;
   `limit`/`offset` paginate; `includeSchemas: false` drops the parameter
   blocks.
3. **Describe** — `mcp({ describe: "chrome_devtools_take_screenshot" })`
   shows the full parameter list (types, required/optional, defaults,
   enums). If the name is ambiguous across servers, add `server: "..."`.
4. **Call** — `mcp({ tool: "chrome_devtools_take_screenshot", args: { format: "png" } })`.
   Servers connect lazily on first use — the first call may take a moment
   to spawn the server process. `args` is a JSON object; a JSON string is
   also accepted.

Also: `mcp({ server: "name" })` lists one server's tools,
`mcp({ connect: "name" })` connects now (+ refreshes metadata),
`mcp({ disconnect: "name" })` stops a server process.

## Direct tools (opt-in)

Servers with `:direct-tools true` (or a tool name list) in `mcp.edn`
register their tools directly as `server_toolname` — call them like normal
tools without the proxy. They register from cached metadata, so no server
spawns at startup. The `MCP_DIRECT_TOOLS` env var (`server1,server2`, or
`__none__` to disable all) overrides the config for the session.

`includeTools`/`excludeTools` globs on a server (`["server_*"]`) filter
which of its tools register as direct tools; `exposeResources: false`
disables the `read_<resource>` direct tools that are registered for the
server's MCP resources by default. Server `searchKeywords`
(`{"server_*" ["screenshot" "capture"]}`) boost proxy search ranking.

## Scripted MCP calls — with the `script` tool

MCP tools are contributed to kmet's builtin `script` sandbox, so several
MCP calls run in one request — loop, filter, chain, or fan out — and only
the script's output enters the conversation. The sandbox is Clojure with
the tools bridge; it has no host access and no `mcpScript` (the old
separate runtime retired into this engine):

```clojure
;; discovery: the cached MCP catalog is in the script's tool surface
(tools/list)                          ;; every callable name
(tools/describe "chrome_devtools_take_screenshot")
;; → {:name :label :description :parameters ...}   (never :execute)

;; call: every tools/call returns a promise — deref it, branch on :is-error
@(tools/call "chrome_devtools_take_screenshot" {:format "png"})
;; → the tool's own result map {:content "..." :is-error false :details ...}

;; fan out: start calls before derefing any of them
(let [a (tools/call "server_a_query" {:q "x"})
      b (tools/call "server_b_query" {:q "y"})]
  (println (:content @a))
  (println (:content @b)))
```

- MCP tools appear by their prefixed names (`server_toolname`) from the
  metadata cache — the first call connects lazily, and failure backoff,
  auth and the output guard match the `mcp` proxy. A server that has
  never been connected has no cache entry yet, so connect it once
  (`mcp({connect: "name"})`) before scripting it.
- Print with `println`; the script's return value is reported too.
  `(emit ...)`/`console.log` no longer exist — they were mcpScript's API.
- `timeout` (seconds; omit or 0 = no deadline, like bash) bounds the whole
  script; calls still in
  flight appear in `:details :calls` as `incomplete` with their elapsed
  time, and `:details :elapsed-ms` reports the measured total. Progress
  notifications stream into the output while a call runs.
- Ranked search lives in the proxy: `@(tools/call "mcp" {:search
  "screenshot"})`, or filter `(tools/list)` locally.
- A name claimed by two servers is absent from the surface (the default
  `:tool-prefix :server` keeps names unique).
- `:script-mode false` (settings) drops the MCP contribution: the sandbox
  then only has kmet's own tools.

## Prompts → slash commands

Every prompt a server advertises becomes a `/mcp__<server>__<prompt>`
command (from cached metadata, so they exist before any connect).
Arguments map positionally or by name, bash-style quoting supported:

    /mcp__demo__brief day=today "important tasks"

Missing required arguments produce a usage message; the prompt result is
sent into the conversation as a user message. `/mcp prompts` lists all
prompt commands.

## Commands

- `/mcp` — status; `/mcp search <q>`; `/mcp list [server]`; `/mcp prompts`
- `/mcp connect|disconnect <server>`
- `/mcp enable|disable <server>` — writes `.kmet/mcp.edn`; `/reload` to apply
- `/mcp refresh` — reload the EDN config without restarting
- `/mcp auth <server>` / `/mcp logout <server>` — OAuth login/logout for
  HTTP servers (PKCE loopback or device flow)
- `/mcp setup` — interactive panel: add a known server, add a custom
  server, import host configs, scaffold the project config
- `/mcp import` — headless host-config adoption (Cursor/Claude/Codex/
  opencode/windsurf/vscode mcp.json into `.kmet/mcp.edn`)

## Notes

- Search/describe are cache-only — no server is spawned for them.
- After a connect failure, the next use reconnects automatically.
- Servers idle past `:idle-timeout` minutes (settings, default 10; 0
  disables; `:keep-alive` servers never reap) are disconnected by a
  background reaper.
- Large tool results are truncated at 50 KiB / 2000 lines by default and
  the full text is spilled to a temp file; tune with settings
  `:output-guard {:max-bytes .. :max-lines ..}` or disable with
  `:output-guard false` (env kill switch `MCP_OUTPUT_GUARD=0`).
- OAuth tokens are stored in the OS keyring when available (macOS
  `security`, Linux `secret-tool`, Windows Credential Manager), else
  plaintext in the agent dir at `mcp-oauth.edn` (0600). Settings
  `:token-storage :keyring | :file | :auto` (default `:auto`); env
  `MCP_TOKEN_STORAGE` overrides.
- MCP config is **trusted code execution**: stdio servers run whatever
  `:command` you configure.
