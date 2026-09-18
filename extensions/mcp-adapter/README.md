# mcp-adapter

MCP server access for kmet, ported from
[pi-mcp-adapter](https://github.com/nicobailon/pi-mcp-adapter)
(design contract: [`../../mcp-adapter.md`](../../mcp-adapter.md) — see
`extensions/README.md` for the extension system overview).

The point is context economy: instead of hundreds of tool definitions
burning your context window, you get one `mcp` proxy tool (~200 tokens).
The agent discovers what it needs on demand with `search`/`describe`, and
servers connect lazily — only when a tool is actually called.

## Enabling

Extensions load from `~/.kmet/agent/extensions/` (global) and
`.kmet/extensions/` (project-local) at startup and on `/reload`. Enable by
symlinking or copying this directory:

```bash
mkdir -p ~/.kmet/agent/extensions
ln -s "$PWD/extensions/mcp-adapter" ~/.kmet/agent/extensions/mcp-adapter
```

Restart kmet or run `/reload` to pick it up. On first load the extension
creates `~/.kmet/agent/mcp.edn` with a commented starter template when it
doesn't exist yet.

## Configuration (EDN only)

Exactly two files:

| File | Scope | Precedence |
|---|---|---|
| `~/.kmet/agent/mcp.edn` (the agent dir) | global | lower |
| `.kmet/mcp.edn` (project) | per-project | higher — the only file the extension writes |

The global file, the metadata cache and the plaintext OAuth token store
live in the agent dir — `~/.kmet/agent` by default, `KMET_CODING_AGENT_DIR`
moves it.

Per-field server merge, project wins. Keys are read in kebab or camel form,
so copying content from a pi-style JSON config is a light edit. A
higher-precedence source that repoints a server at a different `:url` does
**not** inherit the lower entry's credentials (`:headers`,
`:bearer-token`, `:bearer-token-env`, `:oauth`) — they are bound to the
url that supplied them.

```clojure
{:settings {:direct-tools false           ;; global default for direct tools
            :tool-prefix :server          ;; :server | :none | :short | :mcp
            :disable-proxy-tool false}
 :mcp-servers
 {"filesystem" {:command "npx"
                :args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                :lifecycle :lazy          ;; :lazy | :eager | :keep-alive
                :direct-tools false       ;; bool | [tool-name ...]
                :tool-prefix :server
                :request-timeout-ms 120000
                :disabled false
                :env {"KEY" "value"}
                :cwd "/path"}
  "remote" {:url "https://mcp.example.com/mcp"
            :auth :bearer                  ;; :bearer | :oauth | false
            :bearer-token-env "MY_TOKEN"   ;; or :bearer-token / :headers
            :http-transport :streamable-http ;; :streamable-http | :sse
            :lifecycle :lazy}
  "notion" {:url "https://mcp.notion.com/mcp"
            :auth :oauth
            :oauth {:flow :auto            ;; :auto | :pkce | :device
                    :scopes ["read"]}}
  "service" {:url "https://mcp.example.com/mcp"
             :auth :oauth
             :oauth {:grant :client-credentials ;; machine grant, no browser
                     :client-id "svc"          ;; auth: :client-secret-basic default
                     :client-secret "..."}}    ;; | :client-secret-post | :none
  "svc-jwt" {:url "https://mcp.example.com/mcp"
             :auth :oauth
             :oauth {:grant :jwt-bearer        ;; RFC 7523 signed assertion
                     :private-key-file "svc.pem" ;; PKCS#8/PKCS#1 PEM, or
                     :issuer "kmet"            ;; :private-key-jwk {..}
                     :audience "https://as.example/token"}}}}
```

Server keys: `:command`/`:args` (stdio; `:command` may be a vector = full
argv), `:url` (HTTP), `:env`, `:cwd`, `:headers`, `:auth` (`:bearer` |
`:oauth` | `false`), `:bearer-token`/`:bearer-token-env`,
`:oauth` (map — see below), `:http-transport`, `:lifecycle`,
`:direct-tools`, `:tool-prefix`, `:request-timeout-ms`, `:disabled` (only
literal `true` disables), `:include-tools`/`:exclude-tools` (globs over
the prefixed names, gate direct-tool registration), `:search-keywords`
(`{"server_*" ["capture"]}` — boosts proxy search ranking),
`:expose-resources` (default true — `read_<resource>` direct tools),
`:idle-timeout` (minutes, overrides the settings default). A server with
neither `:command` nor `:url` is skipped and shows as `misconfigured` in
status.

A stdio server **without** `:cwd` is spawned in the directory kmet was
launched in — where its definition was read from — and keeps that working
directory across session switches: connections persist, and `/mcp
reconnect` re-spawns in the launch dir too. That is deliberate (server
definitions are project configuration), but a server that resolves relative
paths itself, or that should land in the session's own project after
`/resume`/`/import`, must set `:cwd` explicitly.

Settings keys: `:direct-tools`, `:tool-prefix`, `:disable-proxy-tool`,
`:script-mode` (default true — gates the mcpScript tool),
`:idle-timeout` (minutes, default 10, 0 disables reaping),
`:output-guard` (false disables, or `{:max-bytes :max-lines
:details-max-bytes}` tuning), `:token-storage` (`:auto` default |
`:keyring` | `:file`), `:host-config-discovery` (`:off` default |
`:on` — merges host mcp.json files at the lowest precedence).

OAuth config (`:oauth` map): `:client-id` (pre-registered client; omit →
RFC 7591 dynamic client registration), `:client-secret`, `:scopes`
(string or vector), `:flow` (`:auto` default | `:pkce` | `:device`),
`:grant` (`:authorization-code` default | `:client-credentials` |
`:jwt-bearer` — machine grants, no browser), `:token-endpoint` (explicit,
skips discovery), `:token-endpoint-auth-method` (`:client-secret-basic`
default with secret | `:client-secret-post` | `:none`),
`:private-key-file`/`:private-key-jwk` (jwt-bearer key: PEM path / JWK
map), `:algorithm` (`:RS256` default | `:ES256`),
`:issuer`/`:subject`/`:audience` (jwt-bearer claims; sub defaults to
issuer, aud to the token endpoint), `:redirect-uri`,
`:authorization-server-url` (fetch metadata directly, skips well-known
discovery), `:skip-issuer-metadata-validation`.

## The `mcp` tool

```
mcp({ search: "screenshot" })
mcp({ describe: "chrome_devtools_take_screenshot" })
mcp({ tool: "chrome_devtools_take_screenshot", args: { format: "png" } })
mcp({ server: "chrome-devtools" })   → list that server's tools
mcp({ connect: "filesystem" })       → connect now
mcp({ disconnect: "filesystem" })
mcp({})                              → status
```

Two calls instead of N tools cluttering the context. Servers are lazy by
default — they don't connect until you call one of their tools. Search and
describe read the local metadata cache only (no server spawn).

## Direct tools (opt-in)

Enable per server (`:direct-tools true` in `mcp.edn`), per server with a
name list (`:direct-tools ["tool-a" "tool-b"]`), globally via
`settings.direct-tools`, or via the `MCP_DIRECT_TOOLS` env var
(`MCP_DIRECT_TOOLS=server1,server2`, `__none__` disables all). Each tool is
registered as `server_toolname` (or bare name with
`:tool-prefix :none`/`:short`, `mcp_` prefix with `:mcp`), registered from
cached metadata so no server spawns at startup. Names are lowercased with
`[^a-z0-9_]` → `_`; collisions fall back to the server prefix.

## Commands

| Command | Description |
|---|---|
| `/mcp` | interactive McpPanel (pi mcp-panel.ts port): servers, lifecycle, tool counts, direct/proxy toggles, search, reconnect/auth — `ctrl+s` saves, `esc` clears/closes |
| `/mcp search <q> [regex]` | ranked search (name/description/keyword scoring) — text dialog |
| `/mcp list [server]` | servers, or one server's tools — text dialog |
| `/mcp prompts` | list the prompt slash-commands from cached metadata |
| `/mcp connect <server>` | connect now (+ metadata refresh + tool resync) |
| `/mcp disconnect <server>` | stop the server process |
| `/mcp enable\|disable <server>` | write `:disabled` into `.kmet/mcp.edn`; `/reload` to apply |
| `/mcp refresh` | reload `mcp.edn`, resync tools + prompt commands |
| `/mcp auth <server>` | run the OAuth flow now (PKCE loopback, device, or a machine-grant token fetch) |
| `/mcp logout <server>` | clear stored OAuth tokens + client info |
| `/mcp setup` | interactive setup panel: known-server presets, custom-server form (with connection test), host-config import, scaffold `.kmet/mcp.edn` |
| `/mcp import` | headless host-config adoption (see below) |

## Prompts, resources, mcpScript

- **Prompts** → slash commands: every prompt a server advertises becomes
  `/mcp__<server>__<prompt>`, registered from the metadata cache. Args map
  positionally or `key=value` (bash-style quoting); the formatted result
  is sent into the conversation.
- **Resources** → read tools: servers expose `read_<resource>` direct
  tools by default (`:expose-resources false` disables) that call
  `resources/read`.
- **mcpScript** (`mcpScript` tool, settings `:script-mode false`
  disables): trusted Clojure in a sandboxed `bb` subprocess (no host
  access) with a `tools` bridge — `(tools/search {...})`,
  `(tools/describe {:path ...})`, `(tools/call path args)` returning
  `{:ok true :data ...}` / `{:ok false :error {:code :message
  :suggestions}}`, plus `emit` and `console.log`. `tools/describe`
  returns `:inputTypeScript` (the tool's input schema as a compact TS
  shape). Result details carry a `calls` trace of every
  search/describe/call with outcome and duration; calls still in flight
  at a timeout appear as `{:ok false :error "incomplete"}` with the
  elapsed time. See `skills/mcp/SKILL.md`.
- **include/exclude globs**: server `:include-tools`/`:exclude-tools`
  (`["server_*"]`) filter which tools register as direct tools;
  `:search-keywords {"server_*" ["capture"]}` boost proxy search.
- **Idle reaping**: settings `:idle-timeout` minutes (default 10, 0
  disables; server `:idle-timeout` overrides; `:keep-alive` servers never
  reap) — a background daemon disconnects idle servers.
- **Output guard**: results over 50 KiB / 2000 lines are truncated with a
  head-preview and the full text spilled to a temp file; tune via
  settings `:output-guard {:max-bytes .. :max-lines ..
  :details-max-bytes ..}` or disable with `:output-guard false`
  (`MCP_OUTPUT_GUARD=0`).
- **Streaming progress**: `notifications/progress` events during a call
  stream as partial content into the tool output (all transports).
- **Host-config adoption**: `:host-config-discovery :on` in settings
  merges Cursor/Claude/Codex/opencode/windsurf/vscode `mcp.json` files at
  the lowest precedence; `/mcp import` (or the setup panel) adopts their
  servers into `.kmet/mcp.edn` (JSON only — codex `config.toml` is not
  read).

## HTTP auth

- **Bearer**: `:auth :bearer` with `:bearer-token` or `:bearer-token-env`
  (or an explicit `:headers {"Authorization" ...}`).
- **OAuth**: `:auth :oauth` (+ optional `:oauth` map). The flow follows
  RFC 8414 metadata discovery, RFC 7591 dynamic client registration (or a
  config `:client-id`), and the PKCE loopback flow (browser → local
  callback on an OS-assigned port) or the RFC 8628 device flow (`:flow
  :device`, or auto-selected when headless). Tokens refresh silently on
  expiry; a 401 with a stored refresh token refreshes once and retries.
  Tokens are stored in the **OS keyring when available** — macOS
  `security`, Linux `secret-tool`, Windows Credential Manager (PowerShell
  P/Invoke) — and fall back to **plaintext** `~/.kmet/agent/mcp-oauth.edn`
  (0600 perms) on hosts without a keyring tool (e.g. Termux). Settings
  `:token-storage :keyring | :file | :auto` (default `:auto`); env
  `MCP_TOKEN_STORAGE` overrides; `logout` clears the entry.
- **Machine grants**: `:oauth {:grant :client-credentials ...}` (RFC 6749
  §4.4) or `:grant :jwt-bearer` (RFC 7523) skip the browser: a token is
  fetched from the token endpoint on demand and cached in memory,
  re-fetched on expiry or 401 (no refresh token). client-credentials
  authenticates with `Authorization: Basic` by default; jwt-bearer signs
  a JWT (RS256 default, ES256 supported) with the configured PEM/JWK
  key. Nothing is persisted — `logout` just clears the cache.

## Security

MCP config is **trusted code execution**: stdio servers run whatever
`command` you configure, and HTTP bearer tokens are sent to the `:url` you
configure. Only add servers you trust.

## Development

The `scripts/` directory carries fake MCP/OAuth servers and six
validation scripts (client transports, config/extension load, OAuth flow,
McpPanel/TextDialog/prompt components, mcpScript end-to-end) plus an
end-to-end smoke of the proxy-tool surface (`e2e.bb`, headless — see plan
§15.22):

```bash
bb -cp ../../src:src scripts/validate-client.bb scripts/fake-mcp-server.bb scripts/fake-http-mcp-server.bb
bb -cp ../../src:src scripts/validate-config.bb
bb -cp ../../src:src scripts/validate-panel.bb
bb -cp ../../src:src scripts/validate-oauth.bb scripts/fake-oauth-server.bb
bb -cp ../../src:src scripts/validate-script.bb scripts/fake-mcp-server.bb
bb -cp ../../src:src scripts/e2e.bb scripts/fake-mcp-server.bb
```

## Phase-3 roadmap

Only `/mcp serve` (expose kmet as an MCP server) remains out of scope —
dropped from the plan by request. Everything else on the original Phase-2
list (keyring storage, prompts, resources, mcpScript, setup wizard +
host-config adoption, include/exclude globs, idle-timeout reaping, output
guards, streaming progress) is implemented — see the plan's §15.23-34 for
the recorded deviations.
