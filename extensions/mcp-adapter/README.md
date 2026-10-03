# mcp-adapter

MCP server access for kmet, ported from
[pi-mcp-adapter](https://github.com/nicobailon/pi-mcp-adapter).
See `extensions/README.md` for the extension system overview.

The point is context economy: instead of hundreds of tool definitions
burning your context window, you get one `mcp` proxy tool (~200 tokens).
The agent discovers what it needs on demand with `search`/`describe`, and
servers connect lazily — only when a tool is actually called.

## Protocol

Requests `2025-11-25` (the current handshake-based revision) and accepts
the older handshake revisions `2025-06-18`, `2025-03-26` and `2024-11-05` —
a server on an older SDK answers `initialize` with its own latest revision
and stays usable. A server that selects anything else fails the connect with
*unsupported protocol version*. After the handshake, streamable-HTTP POSTs
carry the negotiated `MCP-Protocol-Version` header alongside
`Mcp-Session-Id`. The stateless `2026-07-28` revision removes the
`initialize` handshake entirely, so it is not reachable from here.

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
`:script-mode` (default true — contributes the MCP catalog to the script
sandbox, see below),
`:idle-timeout` (minutes, default 10, 0 disables reaping),
`:output-guard` (false disables, or `{:max-bytes :max-lines
:details-max-bytes}` tuning), `:token-storage` (`:auto` default |
`:keyring` | `:file`), `:host-config-discovery` (`:off` default |
`:on` — merges host mcp.json files at the lowest precedence).

OAuth config (`:oauth` map): `:client-id` (pre-registered client; omit →
RFC 7591 dynamic client registration), `:client-secret`,
`:application-type` (`"native"` | `"web"` — SEP-837 OIDC DCR client type;
default inferred from the redirect URI, loopback → `"native"`), `:scopes`
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
discovery), `:resource` (RFC 8707 resource indicator override; default
is the server url, canonicalized), `:skip-issuer-metadata-validation`,
`:skip-pkce-verification` (proceed against an authorization server that
does not advertise `code_challenge_methods_supported`).
`:token-endpoint` and `:authorization-server-url` also skip the RFC 9728
protected-resource probe, so a pinned configuration makes no discovery
requests at all.

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

## Prompts, resources, scripted MCP

- **Prompts** → slash commands: every prompt a server advertises becomes
  `/mcp__<server>__<prompt>`, registered from the metadata cache. Args map
  positionally or `key=value` (bash-style quoting); the formatted result
  is sent into the conversation.
- **Resources** → read tools: servers expose `read_<resource>` direct
  tools by default (`:expose-resources false` disables) that call
  `resources/read`. Parameterized resources from
  `resources/templates/list` register the same way — one `read_<name>`
  tool whose parameters are the template's `{var}` placeholders,
  expanded into the URI (`file:///{path}` + `{path "src/main.clj"}` →
  `file:///src/main.clj`; `/` survives inside a variable, `? # %` and
  spaces are escaped). Templates are listed by `/mcp list <server>`.
- **Scripted MCP** (settings `:script-mode false` stops the
  contribution): the adapter contributes its cached MCP catalog to
  kmet's builtin **`run_code`** tool as a tool source
  (`tool_source.clj`) — MCP tools are in the sandbox surface by their
  prefixed names, `(tools/list)`/`(tools/describe)` discover them, and
  `@(tools/call "server_tool" args)` runs one (a promise settling with
  the kmet result map `{:content :is-error :details}`, not the old
  `{:ok :data}` envelope). Calls go through `proxy/call-mcp-tool`, so
  lazy connect, failure backoff, auth and the output guard match the
  proxy; progress notifications stream while a call runs. The separate
  `mcpScript` tool and its `bb`-subprocess runtime retired
  (run_code.md T2) — scripts are the one scripted-MCP surface. See
  `skills/mcp/SKILL.md`.
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
- **Streaming progress**: every `tools/call` sends
  `_meta.progressToken` (the opt-in that makes a server emit progress at
  all), and `notifications/progress` events stream as partial content
  into the tool output (all transports).
- **Server→client messages**: incoming JSON-RPC is dispatched rather
  than dropped — `ping` is answered with an empty result, a client
  feature we do not implement (`sampling/createMessage`,
  `elicitation/create`, `roots/list`) is refused with `-32601` instead
  of being left to time out server-side, and `notifications/tools|prompts|
  resources/list_changed` re-lists that catalog and resyncs the cache,
  direct tools and prompt commands with no manual `/mcp connect`. One
  resync runs at a time (the transport serializes requests per
  connection); a change notification landing mid-resync triggers exactly
  one deferred pass, so nothing is lost and a server that re-notifies on
  every list cannot loop. A request abandoned on timeout is cancelled
  with `notifications/cancelled` (sent in the background on the HTTP
  transports), and closing a streamable-HTTP connection releases the
  server session with an HTTP `DELETE` (also sent in the background;
  skipped at session shutdown and for a connection already dead from a
  timeout, so a stalled server cannot delay the next use or app exit).
- **Host-config adoption**: `:host-config-discovery :on` in settings
  merges Cursor/Claude/Codex/opencode/windsurf/vscode `mcp.json` files at
  the lowest precedence; `/mcp import` (or the setup panel) adopts their
  servers into `.kmet/mcp.edn` (JSON only — codex `config.toml` is not
  read).

## HTTP auth

- **Bearer**: `:auth :bearer` with `:bearer-token` or `:bearer-token-env`
  (or an explicit `:headers {"Authorization" ...}`).
- **OAuth**: `:auth :oauth` (+ optional `:oauth` map). The flow follows
  RFC 9728 protected-resource metadata for the authorization-server
  location (the `resource_metadata` URL from a 401 `WWW-Authenticate`
  challenge when one has been seen, else the well-known probes), then
  RFC 8414 / OpenID Connect authorization-server metadata; RFC 7591
  dynamic client registration (or a config `:client-id`); and the PKCE
  loopback flow (browser → local callback on an OS-assigned port) or the
  RFC 8628 device flow (`:flow :device`, or auto-selected when
  headless). The issuer is checked against the URL the metadata came
  from (the authorization server — a split-origin deployment is fine),
  and the static `:headers` are only sent to the MCP server's own origin,
  never to an authorization server or metadata host it names. Requests
  carry the RFC 8707 `resource` indicator — the canonical server URI —
  in both authorization and token requests, and the scopes come from
  config, else the server's `WWW-Authenticate` challenge, else
  `scopes_supported`. The authorization response's `iss` is validated
  against the issuer recorded when the flow started (RFC 9207; a missing
  one is rejected when the AS advertises
  `authorization_response_iss_parameter_supported`), and credentials are
  keyed by the authorization server's issuer (SEP-2352) — a client
  registered with one AS is never reused at another, and an AS change
  re-registers (pre-registered `:client-id` credentials surface an error
  instead). PKCE support is verified before the PKCE flow
  authorizes (`:skip-pkce-verification` overrides; the device flow sends
  no challenge and is never refused for missing PKCE metadata). Tokens
  refresh silently on expiry; a 401 with a stored refresh token refreshes
  once and retries (never against a different issuer).
  Tokens are stored in the **OS keyring when available** — macOS
  `security`, Linux `secret-tool`, Windows Credential Manager (PowerShell
  P/Invoke) — and fall back to **plaintext** `~/.kmet/agent/mcp-oauth.edn`
  (0600 perms) on hosts without a keyring tool (e.g. Termux). The file is
  issuer-keyed (`:version 2`): one client registration per authorization
  server, shared by the servers resolving to it, and tokens per server; a
  pre-upgrade per-server file is migrated on first authenticated use.
  Settings `:token-storage :keyring | :file | :auto` (default `:auto`);
  env `MCP_TOKEN_STORAGE` overrides; `logout` clears the entry.
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
McpPanel/TextDialog/prompt components, scripted-MCP end-to-end) plus an
end-to-end smoke of the proxy-tool surface (`e2e.bb`, headless — see plan
§15.22):

```bash
# `bb -cp` replaces the classpath; org.clojure/data.json is bb-bundled
# (1.13.224+), so the tree's src roots are all the classpath needs.
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
list (keyring storage, prompts, resources, scripted MCP, setup wizard +
host-config adoption, include/exclude globs, idle-timeout reaping, output
guards, streaming progress) is implemented — see the plan's §15.23-34 for
the recorded deviations.

### Deliberately out of scope

Only what the `2025-11-25` specification requires of a client is
implemented. Left out on purpose:

- **Tasks** (SEP-1686, experimental) — no `tasks/get|result|cancel`, no
  `execution.taskSupport`, no `experimental` capability negotiation.
- **Optional client features** — no `sampling/createMessage`,
  `elicitation/create` or `roots/list`; the client declares no such
  capabilities and refuses those methods with `-32601` rather than
  leaving a server waiting out its timeout.
- **Resource subscriptions** — `subscribe` is an explicitly optional
  capability, so no `resources/subscribe` and no handling of
  `notifications/resources/updated`.
- **Logging** — `logging/setLevel` is a client MAY;
  `notifications/message` is not collected.
- **Completion** — `completion/complete` needs an interactive argument
  editor the adapter has no surface for.
- **Streamable HTTP GET stream** — a client MAY; there is no
  server→client channel outside a POST response, hence no
  `Last-Event-ID` resumption.
- **Client ID Metadata Documents** (SEP-991, a SHOULD) — needs an
  HTTPS-hosted metadata document; RFC 7591 dynamic registration stays.
- **Tool `annotations` / `outputSchema` / `icons`** — optional display and
  safety metadata, not cached.
