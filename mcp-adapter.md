# kmet mcp-adapter extension — implementation plan (Phase 1)

Status: **implemented (Phase 1 + Phase 2)** — see §15 for implementation
notes and recorded deviations. Rev 2: OAuth (RFC 8414/7591, PKCE loopback
+ RFC 8628 device flow) added to Phase 1 scope on request. Rev 3: the
Phase-2 OAuth machine grants (client-credentials RFC 6749 §4.4 +
jwt-bearer RFC 7523, §15.17-20) implemented. Rev 4 (this revision): the
rest of the original Phase-2 list implemented — OS-keyring token storage,
prompts → slash commands, resources → read tool, scripted MCP (T2: the
mcpScript runtime retired into kmet's shared `script` tool), setup wizard +
host-config adoption, include/exclude/
searchKeywords globs, idle-timeout reaping, output guards, streaming
tool-call progress (§15.23-34). `/mcp serve` was **dropped from the plan
by request** (the user asked to drop it and implement the rest).
This document is the design contract; deviations during implementation must
be reflected back here.

## 1. Goal

Give kmet the pi-mcp-adapter capability: access to MCP servers **without
burning context** on tool definitions. One lazy `mcp` proxy tool (~200
tokens) plus opt-in direct tools, over stdio **and** HTTP MCP servers,
configured from kmet's own EDN files.

**Design source: [pi-mcp-adapter](https://github.com/nicobailon/pi-mcp-adapter)** — local checkout
`~/src/cvstree/pi-mcp-adapter/` is the authoritative reference for this port
(read the .ts modules named in §4 before implementing each counterpart).
Everything below is a faithful port of its architecture and behavior — the
proxy tool surface (`proxy-modes.ts`), direct-tool registration
(`direct-tools.ts`), server lifecycle (`server-manager.ts`), metadata cache
(`metadata-cache.ts`), and command surface (`commands.ts`) — adapted to
kmet's extension contract and babashka (sync, no async/await, EDN config,
no third-party MCP SDK: JSON-RPC is spoken directly). Deviations are
explicit and justified by the Phase-1 scope (§3) or babashka limits (§14),
never by convenience.

## 2. Locked decisions

| Decision | Choice |
|---|---|
| Design source | pi-mcp-adapter — behavioral parity wherever feasible in the Phase-1 subset (§1) |
| Scope | Phase 1 only (see §3 for in/out) |
| Tool name | `mcp` |
| Direct tools | Fully match pi-mcp-adapter semantics (§10.5) |
| Config format | EDN only — JSON never read at runtime |
| Config sources | exactly two: global `~/.kmet/agent/mcp.edn`, project `.kmet/mcp.edn` |
| Porting | none — no pi `mcp.json` reading, no host-config discovery |
| Template | create global `mcp.edn` at init when missing; project file on first enable/disable write |
| HTTP | in Phase 1: streamable HTTP + legacy SSE; auth: static bearer/headers + OAuth (RFC 8414 discovery, RFC 7591 DCR, PKCE loopback + device flow) |
| OAuth machinery | new generic lib `kmet.libs.oauth`, extracted from `kmet.ai.oauth` (device-code poll, PKCE, callback server) + RFC 8414/7591 additions; extension `auth.clj` is a thin adapter; `kmet.ai.oauth` refactored onto the lib — one implementation, no duplication |
| `/mcp serve` | out of scope |
| Entry ns | `kmet.extensions.mcp-adapter.core` |

## 3. Scope

### In scope (Phase 1)

- EDN config loading/merging/template/write-back (§6)
- MCP client: stdio JSON-RPC, streamable HTTP, legacy SSE (§7)
- OAuth client auth for HTTP servers: RFC 8414 metadata discovery, RFC 7591
  dynamic client registration, PKCE loopback + RFC 8628 device flows, token
  refresh, plaintext token store with 0600 perms (§7.8; machinery in the
  new generic lib `kmet.libs.oauth`)
- Proxy tool `mcp` with status/search/describe/call/connect/disconnect (§9)
- Direct tools, pi-parity (§10.5)
- Metadata cache for offline search/describe + startup direct-tool registration (§8)
- Server lifecycle: lazy/eager/keep-alive, reconnect-on-use, cleanup on
  session shutdown / extension unload
- `/mcp` command: status, search, list, connect, disconnect, enable, disable,
  refresh, auth, logout (+ argument completions)
- Usage skill contributed via `:resources-discover`, README

### Out of scope

- `/mcp serve` (expose kmet as an MCP server) — **dropped from the plan by
  request** (Rev 4).

All other Phase-2 items (keyring storage, prompts, resources, scripted MCP,
setup wizard / host-config adoption, include/exclude/searchKeywords globs,
idle-timeout reaping, output guards, streaming tool-call progress) are
implemented — §15.23-34 record the build notes and deviations.

## 4. Port mapping (pi-mcp-adapter → kmet)

| pi-mcp-adapter module | kmet file | Ported behavior |
|---|---|---|
| `index.ts` (installMcpAdapter) | `core.clj` | init/shutdown, state, tool registration, events, lifecycle generations simplified to per-server locks |
| `config.ts` (loadMcpConfig, writeProjectServerDisabledOverride) | `config.clj` | precedence merge, disabled override — EDN sources instead of mcp.json, no imports/host discovery (Phase 1) |
| `server-manager.ts` | `client.clj` | server lifecycle, lazy connect, reconnect, stdio+streamable-http+sse transports (no sampling/elicitation handlers) |
| `mcp-auth.ts`, `mcp-oauth-provider.ts`, `oauth-handler.ts`, `mcp-auth-flow.ts`, `mcp-callback-server.ts` | `kmet.libs.oauth` + `auth.clj` | generic machinery → `kmet.libs.oauth` (extracted from `kmet.ai.oauth`, extended with RFC 8414 discovery + RFC 7591 DCR + token exchange/refresh); `auth.clj` is a thin adapter (config → lib calls, token-store file, browser open, status). Extensions cannot require `kmet.ai.*` — the lib is the shared seam; plaintext store instead of OS keyring |
| `proxy-modes.ts` (executeSearch/Describe/Call/Connect/Status) | `proxy.clj` | proxy tool dispatch, search ranking, describe, status text |
| `direct-tools.ts` (resolveDirectTools, createDirectToolExecutor) | `core.clj` (§10.5) | per-server direct-tools opt-in, MCP_DIRECT_TOOLS env, tool-prefix naming, cache-backed registration |
| `metadata-cache.ts` (loadMetadataCache, isServerCacheValid) | `metadata.clj` | persistent cache, config fingerprint, 7-day freshness |
| `commands.ts` (`/mcp` subcommands) | `core.clj` (§10.6) | status/search/list/connect/disconnect/enable/disable/auth/logout (+ refresh); no setup panels (Phase 1) |
| `init.ts` (updateStatusBar, lazyConnect) | `core.clj` | status text, lazy/eager/keep-alive connect, description rebuild |
| `types.ts` (ServerEntry, McpSettings, ToolPrefix) | `config.clj` (§6.2) | same field semantics, kebab-case EDN + tolerant camel reading |
| `mcp-output-guard.ts` | `output_guard.clj` | result bounding: max-bytes/max-lines truncation + temp-file spill + details bound (string-content adaptation) |
| `prompts.ts` | `prompts.clj` | prompt slash-commands: bash-style arg parsing, positional/named resolution, role-marked formatting, cache-driven registration |
| `mcp-code.ts` / `mcp-script-worker.mjs` | `tool_source.clj` | retired into the shared script engine (script.md T2): the cached MCP catalog is contributed to kmet's builtin `script` tool as a tool source — `register-tool-source!` + `tool-proxy/script-tool-records`, calls through `call-mcp-tool` |
| `mcp-setup-panel.ts` | `setup.clj` + `core.clj` | setup panel: known-server presets, custom-server form with connection test, host-config import, project scaffolding |
| `config.ts` IMPORT_PATHS / extractServers | `config.clj` | host-config discovery + adoption (JSON host files only — no TOML reader in bb; codex config.json covered, config.toml not) |
| `search-ranking.ts` | `proxy.clj` | full weighted ranking port (name/original/server/description/keywords + coverage gates) replacing the Phase-1 name-over-description ranking |
| `types.ts` includeTools/excludeTools/searchKeywords, idleTimeout | `config.clj` + `proxy.clj` + `core.clj` | glob-filtered direct-tool registration, keyword-boosted search, idle reaper daemon |
| `mcp-auth.ts` keyring storage | `auth.clj` | OS-keyring backends (macOS security / Linux secret-tool / Windows Credential Manager P/Invoke) with plaintext fallback; settings :token-storage / env MCP_TOKEN_STORAGE |
| skills/ (mcp-scripting) | `skills/mcp/SKILL.md` | usage skill, contributed via `:resources-discover` |

Deliberately not ported (Rev 4): `mcp-panel.ts` (ported as panel.clj),
`mcp-setup-panel.ts` full desktop layout (setup.clj implements the same
actions on kmet's component model), `mcp-code.ts`/`mcp-script-worker.mjs`
(the standalone runtime — retired into kmet's shared `script` engine,
script.md T2; the flat `tools.<name>` shorthand never existed in Clojure),
`tool-approval.ts` (kmet has no approval UI; `:approve-tools` is not
implemented), `mcp-output-guard.ts` image pass-through (kmet tool results
are string content), `ui-*.ts`, `mcp-script-worker.mjs` vm isolation
(irrelevant — the shared sandbox is in-process SCI),
`prompts.ts` live-metadata
subsystems (the cache is the live source after connects), `resource-tools.ts`
(ported inline in the entry), `tool-result-renderer.ts` (kmet renders tool
results natively).

## 5. File layout

```
extensions/mcp-adapter/
├── README.md                     usage, config reference, /mcp reference, security note
├── scripts/
│   ├── fake-mcp-server.bb        fake stdio MCP server (validation)
│   ├── fake-http-mcp-server.bb   fake streamable-HTTP MCP server (validation)
│   ├── fake-oauth-server.bb      fake OAuth AS: discovery/DCR/token/authorize/device (validation)
│   ├── validate-client.bb        transports + prompts/resources/progress checks
│   ├── validate-config.bb        config + metadata + host-adoption + extension load
│   ├── validate-oauth.bb         OAuth flows (PKCE/device/redirect-uri/machine grants)
│   ├── validate-panel.bb         McpPanel/TextDialog/prompt component checks
│   ├── validate-script.bb        scripted-MCP end-to-end against the fake server
│   └── e2e.bb                    headless proxy-tool smoke
└── src/                          artifact root
    ├── extension.edn             {:name "mcp-adapter" :entry kmet.extensions.mcp-adapter.core}
    ├── skills/mcp/SKILL.md       usage skill (contributed via :resources-discover)
    └── kmet/extensions/mcp_adapter/core.clj          entry: init/shutdown, state, registration, /mcp
        kmet/extensions/mcp_adapter/config.clj        EDN config + host-config discovery/adoption
        kmet/extensions/mcp_adapter/client.clj        transports + JSON-RPC + prompts/resources calls
        kmet/extensions/mcp_adapter/auth.clj          OAuth: discovery, DCR, PKCE/device flows, token store + keyring
        kmet/extensions/mcp_adapter/metadata.clj      cache (tools + prompts + resources)
        kmet/extensions/mcp_adapter/tool_proxy.clj    proxy tool executor + search ranking
        kmet/extensions/mcp_adapter/output_guard.clj  result bounding (pi mcp-output-guard.ts)
        kmet/extensions/mcp_adapter/prompts.clj       prompt slash-commands (pi prompts.ts)
        kmet/extensions/mcp_adapter/tool_source.clj   script-sandbox tool source (script.md T2)
        kmet/extensions/mcp_adapter/setup.clj         setup panel (pi mcp-setup-panel.ts)
```

This plan lives at the repo root (`mcp-adapter.md`), not inside the
extension directory — deviation recorded in §15.21.

Plus two new core files (outside the extension dir):
`src/kmet/libs/oauth.clj` — generic OAuth machinery extracted from
`kmet.ai.oauth` (device-code poll, PKCE, loopback callback server), extended
with RFC 8414 discovery + RFC 7591 DCR + token exchange/refresh + the
machine grants (client-credentials, jwt-bearer); the transport is
`kmet.libs.http` (the single outbound-HTTP boundary, proxy-aware); the
caller supplies the token store and interaction fns.
`src/kmet/libs/crypto.clj` — generic crypto for bb (no bundled crypto
lib): base64url, a minimal DER reader/writer, private-key parsing (PEM
PKCS#8/PKCS#1, JWK RSA/EC) and JWT signing (RS256/ES256); used by
`kmet.libs.oauth` (jwt-bearer) and reusable by anything else.
Unlike the extension files both sit inside the `bb lint` / `bb test` gates
and must satisfy `kmet.libs.test-self-contained`.

Dependencies allowed: `kmet.extension`, `kmet.tui.*`, `kmet.libs.*`
(including `kmet.libs.http` — the outbound-HTTP boundary; direct
`babashka.http-client` requires are rejected), `clojure.*`, `babashka.*`,
`clojure.data.json` + `clojure.core.async`. **No `deps.edn` needed** (all libs
are bb-bundled and resolved from the bb classpath). Reused existing libs:
`kmet.libs.process` (stdio spawn + tree-kill), `kmet.libs.sse` (SSE parsing,
both transports), `kmet.libs.file-lock` conventions (atomic cache/token
writes).

## 6. Config — `config.clj`

### 6.1 Sources & precedence

- Global: `~/.kmet/agent/mcp.edn`
- Project: `<cwd>/.kmet/mcp.edn` — highest precedence, per-field server
  merge + per-key settings merge (later wins). This is the only file the
  extension writes.
- No other sources. No `--mcp-config` flag.

### 6.2 EDN format

```clojure
{:settings {:direct-tools false           ;; global default for direct tools
            :tool-prefix :server          ;; :server | :none | :short | :mcp
            :disable-proxy-tool false}
 :mcp-servers
 {"chrome-devtools" {:command "npx"
                     :args ["-y" "chrome-devtools-mcp@1.6.0"]
                     :lifecycle :lazy     ;; :lazy | :eager | :keep-alive
                     :direct-tools false  ;; bool | [tool-name ...]
                     :tool-prefix :server
                     :request-timeout-ms 120000
                     :disabled false
                     :env {"KEY" "value"}
                     :cwd "/path"}
  "remote" {:url "https://mcp.example.com/mcp"
            :headers {"Authorization" "Bearer ..."}   ;; or :auth :bearer + :bearer-token
            :auth :bearer
            :bearer-token "sk-..."                    ;; or :bearer-token-env "ENV_VAR"
            :http-transport :streamable-http          ;; :streamable-http | :sse
            :lifecycle :lazy}
  "notion" {:url "https://mcp.notion.com/mcp"
            :auth :oauth                              ;; triggers the §7.8 OAuth flow
            :oauth {:flow :auto                       ;; :auto | :pkce | :device
                    :scopes ["read"]}}
  "service" {:url "https://mcp.example.com/mcp"
             :auth :oauth
             :oauth {:grant :client-credentials       ;; machine grant, no browser
                     :client-id "svc"                 ;; auth: :client-secret-basic (default)
                     :client-secret "..."}}           ;; | :client-secret-post | :none
  "svc-jwt" {:url "https://mcp.example.com/mcp"
             :auth :oauth
             :oauth {:grant :jwt-bearer               ;; RFC 7523 signed assertion
                     :private-key-file "svc.pem"      ;; PKCS#8/PKCS#1 PEM, or
                     :issuer "kmet"                    ;; :private-key-jwk {..}
                     :audience "https://as.example/token"}}}}
```

Server key meaning:

| Key | Type | Notes |
|---|---|---|
| `:command` | string or vector | stdio; vector = full argv (merged with `:args`) |
| `:args` | vector of strings | stdio |
| `:env` | map | merged over `System/getenv` (bb `:env` replaces — must merge manually) |
| `:cwd` | string | spawn dir, default cwd |
| `:url` | string | HTTP server (streamable-http or sse) |
| `:headers` | map | static HTTP headers |
| `:auth` | `:bearer` \| `:oauth` \| `false` (default) | auth mode; `:oauth` runs the §7.8 flow |
| `:bearer-token` / `:bearer-token-env` | string | token or env var name; sets `Authorization: Bearer …` when `:auth :bearer` and no explicit header |
| `:oauth` | map \| `false` | `:client-id`/`:client-secret` (omit → RFC 7591 DCR), `:scopes` (string or vector), `:flow` (`:auto` default \| `:pkce` \| `:device`), `:grant` (`:authorization-code` default \| `:client-credentials` \| `:jwt-bearer` — machine grants, §7.8.6), `:token-endpoint` (explicit, skips discovery), `:token-endpoint-auth-method` (`:client-secret-basic` default with secret \| `:client-secret-post` \| `:none`), `:private-key-file` / `:private-key-jwk` (jwt-bearer key: PEM path / JWK map), `:algorithm` (`:RS256` default \| `:ES256`), `:issuer`/`:subject`/`:audience` (jwt-bearer claims; sub defaults to issuer, aud to the token endpoint), `:redirect-uri`, `:authorization-server-url`, `:skip-issuer-metadata-validation`; explicit `false` disables |
| `:http-transport` | `:streamable-http` (default) \| `:sse` | forced transport |
| `:lifecycle` | `:lazy` (default) \| `:eager` \| `:keep-alive` | see §10.3 |
| `:direct-tools` | bool \| `[name ...]` | opt-in surface; list = only those tools |
| `:tool-prefix` | `:server` \| `:none` \| `:short` \| `:mcp` | overrides settings |
| `:request-timeout-ms` | number | overrides the 120s default |
| `:disabled` | bool | only literal `true` disables |

A server with neither `:command` nor `:url` is skipped at registration and
surfaces in status as `misconfigured`.

### 6.3 Key normalization

Known keys accepted in camel or kebab form so a file copied from a pi-style
JSON config works with light edits: `:mcpServers`/`:mcp-servers`,
`:directTools`/`:direct-tools`, `:toolPrefix`/`:tool-prefix`,
`:requestTimeoutMs`/`:request-timeout-ms`, `:bearerToken`/`:bearer-token`,
`:bearerTokenEnv`/`:bearer-token-env`, `:httpTransport`/`:http-transport`,
`:disableProxyTool`/`:disable-proxy-tool`, plus `:settings`; nested under
`:oauth`: `:clientId`/`:client-id`, `:clientSecret`/`:client-secret`,
`:redirectUri`/`:redirect-uri`, `:authorizationServerUrl`/`:authorization-server-url`,
`:skipIssuerMetadataValidation`/`:skip-issuer-metadata-validation`, plus the
machine-grant keys `:grantType`/`:grant`, `:tokenEndpoint`/`:token-endpoint`,
`:tokenEndpointAuthMethod`/`:token-endpoint-auth-method`,
`:privateKeyFile`/`:private-key-file`, `:privateKeyJwk`/`:private-key-jwk`.
`:lifecycle` / `:tool-prefix` values accept string or keyword. Unknown keys
pass through unmodified.

### 6.4 Template creation

At init: if `~/.kmet/agent/mcp.edn` does not exist, write it with a
schema-commented starter (both stdio and url examples, settings block).
Project `.kmet/mcp.edn` is never auto-created by init — only by the first
`enable`/`disable` write (§6.5).

### 6.5 enable/disable write

Port of pi's `writeProjectServerDisabledOverride` semantics, onto EDN:
- Read existing `.kmet/mcp.edn` (EDN; parse error → throw with path).
- Disable: set `:disabled true` on the server entry (create entry if absent).
- Enable: remove the `:disabled` key; if the merged *lower* config (global
  file) has the server disabled, write `:disabled false` instead; if the
  entry is now empty, remove the server key.
- Write pretty EDN (`pr-str`, trailing newline); return `{:path :changed}`.
- The command then tells the user to run `/reload` to apply (config is
  loaded at init; a live `refresh` subcommand is the no-reload alternative).

## 7. Client — `client.clj`

### 7.1 Transport abstraction

Every connection is a map with `:transport` (`:stdio` | `:streamable-http` |
`:sse`), `:id-counter`, and transport-specific fields. Public protocol:

```
request!   (conn method params & [{:keys [timeout-ms]}]) → :result map
notify!    (conn method params)                            → nil
close!     (conn)                                          → nil
alive?     (conn)                                          → bool
```

`request!` throws `ex-info` on JSON-RPC error, timeout, or transport death.
Notifications received mid-request are consumed/dropped; stale responses
(non-matching `:id`) are dropped and the loop continues.

### 7.2 stdio transport

- Spawn `(apply proc/process (into argv args) {:in :stream :out :stream
  :err :stream :dir cwd :env (merge (System/getenv) env)})`; pid via
  `(.pid (:proc p))`; `alive?` via `proc/alive?`.
- Reuse `kmet.libs.process` for process management: `track-pid!` on spawn,
  `collect-descendant-pids` + `kill-process-tree!` on disconnect/shutdown
  (npx-style servers spawn children — a bare `proc/stop` orphans them).
- Reader thread: buffered stdout lines → `clojure.data.json` parse → push to a
  core.async channel (capacity 128); `::eof` marker + close on EOF.
- Stderr drained into a bounded tail (last 20 lines) for diagnostics.
- `request!`: write newline-delimited JSON with `:id`, `alts!!` until the
  matching id or deadline; distinguish timeout (`alts!!` returns the timeout
  channel) from process exit (channel closed / `::eof`).

### 7.3 streamable-http transport

- Per-request POST via `kmet.libs.http`:
  `{:headers {"Content-Type" "application/json" "Accept" "application/json, text/event-stream"
              (+ "Mcp-Session-Id" after initialize) (+ auth headers)}
    :body (json/generate-string msg) :as :stream}`.
- `initialize` response: capture `Mcp-Session-Id` (and `Mcp-Protocol-Version`
  if present); remember for subsequent requests.
- Response handling by content type:
  - `application/json`: parse body → it is the response.
  - `text/event-stream`: read lines, collect `data:` payloads via
    `kmet.libs.sse/parse-sse-line` (same parser as §7.4), parse each as
    JSON, keep the one matching our `:id` (notifications/other events
    dropped); the server closes the stream after the result.
- Blocking read happens inside a `future`; deref with deadline. On timeout:
  close the HTTP client, throw. **Documented limitation**: the server may
  still complete the call; the result is lost (no abort transport in bb).
- `close!`: close the underlying client.

### 7.4 legacy SSE transport

- GET the `:url` with auth headers; background reader thread parses SSE
  events (`event:` / `data:` lines, reusing `kmet.libs.sse` if its API fits,
  else a ~30-line local parser):
  - `endpoint` event → the POST URL for requests (assume the data is a bare
    path unless it parses as JSON; append relative to the GET url).
  - `message` event → JSON-RPC message → push to the channel (same design as
    stdio).
- `request!`/`notify!`: POST to the endpoint URL (with `Mcp-Session-Id`
  after initialize); responses arrive on the stream channel; id-matched
  waits as in §7.2.
- Session-id and reconnect on stream drop: on channel EOF, next `request!`
  re-GETs the stream and re-runs `initialize` (bounded retry, then throw).

### 7.5 JSON-RPC protocol (all transports)

- Handshake: `initialize` `{:protocolVersion "2025-06-18" :capabilities {}
  :clientInfo {:name "kmet-mcp-adapter" :version "0.1.0"}}` (60s timeout) →
  `notifications/initialized`; record negotiated `protocolVersion` +
  `serverInfo`.
- `tools/list` with cursor pagination (`nextCursor` loop, 30s per page).
- `tools/call` `{:name … :arguments …}` with `:request-timeout-ms` (default
  120s).

### 7.6 Result formatting

Flatten content blocks: `text`/`error` blocks joined with newlines; `image`
→ `[image: <mimeType>, <n> bytes — not rendered]`; empty content with
`structuredContent` → pretty JSON of it; fallback `(no text content)`.
`isError: true` surfaces as `:is-error true` in the tool result.

### 7.7 Errors & diagnostics

Every transport failure includes the stderr tail / HTTP status in the
`ex-info` data. Message pattern:
`MCP connect failed: <cause> (stderr: …)` / `MCP error <code>: <message>` /
`MCP request timed out after Nms: <method>`. / `MCP auth failed: <cause>`
(§7.8).

### 7.8 OAuth (HTTP servers) — `auth.clj` over `kmet.libs.oauth`

Trigger: server config `:auth :oauth` (or an `:oauth` map). Faithful port of
pi's `mcp-auth.ts` / `mcp-oauth-provider.ts` / `oauth-handler.ts` /
`mcp-auth-flow.ts` / `mcp-callback-server.ts`. The extension cannot require
`kmet.ai.*`, so the generic machinery lives in a new shared lib,
`kmet.libs.oauth` — extracted from `kmet.ai.oauth` (device-code poll, PKCE,
loopback callback server, existing raw `java.net.ServerSocket` design) and
extended with RFC 8414 discovery, RFC 7591 DCR and token
exchange/refresh; transport via `kmet.libs.http`.
`kmet.ai.oauth` is refactored onto the same lib (single implementation).
The extension's `auth.clj` is a thin adapter: server config → lib calls,
token-store file wiring, browser open, status text.

1. **Token lookup** (pi `oauth-handler.ts`): per-server entry in
   `~/.kmet/agent/mcp-oauth.edn` — `{:tokens {:access … :refresh … :expires
   ms :scope …} :client-info {:client-id … :redirect-uris […]}}`. Expired →
   refresh (§5); missing/refresh-failed → full flow. Plaintext file, 0600
   perms (bb has no OS keyring — documented tradeoff; pi uses the keyring);
   fixed path, no `MCP_OAUTH_DIR` override in Phase 1.
2. **Discovery** (RFC 8414): GET
   `{url}/.well-known/oauth-authorization-server` →
   `authorization_endpoint` / `token_endpoint` / `registration_endpoint` /
   `device_authorization_endpoint`; fallback to
   `/.well-known/oauth-protected-resource`. Config
   `:authorization-server-url` (or explicit endpoints) skips discovery.
3. **Client registration** (RFC 7591): `:oauth {:client-id …}` in config →
   use it, no DCR (pi `configPreRegistered` path). Otherwise POST
   `registration_endpoint` `{:redirect_uris [loopback-uri]
   :token_endpoint_auth_method "none" :grant_types ["authorization_code"
   "refresh_token"] :response_types ["code"] :client_name "kmet"}` →
   `client_id`; persist with the entry, register once.
4. **Authorization**: default `:flow :auto` — PKCE (S256) with a loopback
   redirect (`http://127.0.0.1:<os-assigned-port>/callback`, port 0 → OS
   assigns; pi `ensureCallbackServer` strictPort=false) when the metadata
   supports it; RFC 8628 device flow (port of `kmet.ai.oauth` device-code
   polling incl. `slow_down`/`interval` handling) when the metadata exposes
   `device_authorization_endpoint` but loopback is unusable (remote/headless
   hosts), or when forced via `:oauth {:flow :device}`. Browser opened via
   the platform helper (`xdg-open` / `open` / `termux-open-url`); device
   flow falls back to printing the URL + polling in place. Manual
   paste-the-redirect-URL path is Phase 2 (pi `auth-complete`).
5. **Tokens**: `grant_type=authorization_code` + `code_verifier` (or device
   grant) → store access/refresh/expires/scope. Requests attach
   `Authorization: Bearer <access>` (§7.3/§7.4); on 401 with a stored
   refresh token, refresh once and retry the request once; on expiry,
   refresh silently before sending. Refresh failure → re-run the flow.
6. **Machine grants** (`kmet.libs.crypto` + `kmet.libs.oauth`, §7.8.6):
   `:grant :client-credentials` (RFC 6749 §4.4) or `:jwt-bearer` (RFC
   7523) skip the interactive flows entirely. A token is fetched on
   demand from the token endpoint (RFC 8414 discovery, or an explicit
   `:token-endpoint` — no issuer check when explicit), cached in memory
   with its expiry, and re-fetched on expiry or 401 — the re-fetch IS the
   refresh (no refresh token is expected). Nothing is persisted: the
   token store stays for the interactive grants; `logout` clears the
   cache (a machine token returns on the next request by design).
   client-credentials authenticates via `Authorization: Basic`
   (`:client-secret-basic`, default when a secret is present),
   `:client-secret-post`, or public `:none`; jwt-bearer signs a JWT
   assertion (RS256 default, ES256) with the configured key — PEM file
   (`:private-key-file`, PKCS#8 or PKCS#1) or inline JWK map
   (`:private-key-jwk`) — with `:issuer` (required), `:subject`
   (defaults to issuer), `:audience` (defaults to the token endpoint).
   `:flow` is ignored for machine grants; `/mcp auth` validates the
   config and warms the cache; status shows `client-credentials` /
   `jwt-bearer` (§9.5).
7. **Surfaces**: `/mcp auth <server>` forces a fresh flow; `/mcp logout
   <server>` clears the stored entry (§10.6). Status shows
   logged-in/expired/none (§9.5).

## 8. Metadata cache — `metadata.clj`

- Path: `~/.kmet/agent/mcp-cache.edn`.
- Shape: `{:version 1 :servers {name {:config-fingerprint str
  :fetched-at ms :tools [{:name :description :inputSchema}]}}}`.
- Freshness: 7 days (`fetched-at`). `server-entry` returns nil when stale or
  fingerprint mismatch → callers fall back to a live connect.
- Fingerprint: `pr-str` of sorted server names + `:command/:args/:url/
  :disabled/:direct-tools/:tool-prefix` + relevant settings. A config change
  invalidates cached metadata.
- API: `load-cache`, `save-cache!`, `server-entry`, `update-entry!`,
  `all-tools` (fresh + non-disabled, across servers, `{:server :tool}`).
- Writes via temp-file + rename (atomic-ish), mirroring `kmet.libs.file-lock`
  conventions if a lock is needed (single process — plain write is fine).

## 9. Proxy tool — `proxy.clj`

### 9.1 Tool parameters (JSON schema)

```
tool      (string, optional)  — MCP tool name to call
args      (object, optional)  — arguments as a JSON object
server    (string, optional)  — scope to / disambiguate a server
search    (string, optional)  — search tools by name/description
regex     (boolean, optional)— treat search as regex (default false)
includeSchemas (boolean, optional) — schemas in search output (default true)
describe  (string, optional)  — show a tool's parameters
connect   (string, optional)  — connect a server now (+ refresh)
disconnect(string, optional)  — disconnect a server
list      (string, optional)  — list a server's tools
limit     (number, optional)  — search limit (default 12)
offset    (number, optional)  — search offset (default 0)
```

No params → status. `args` is declared `object`; the registry's
`normalize-args` also accepts a JSON string defensively. Every param is
type-checked at the dispatch boundary (`validate-params`): a non-string in
a string param, a non-number in `limit`/`offset`, or a non-boolean in
`regex`/`includeSchemas` returns a readable error instead of surfacing a
raw ClassCastException from the string fns; `regex`/`includeSchemas` also
accept the strings `"true"`/`"false"` (LLMs commonly send them as
strings).

### 9.2 Dispatch

Precedence: `search` → `describe` → `tool` → `connect` → `disconnect` →
`list` → `server` (list that server's tools) → status. Search/describe read
the cache only (no spawn); call/connect ensure a live connection.

### 9.3 Search

Substring (case-insensitive) or regex (`regex: true`, invalid regex →
message). Name matches rank above description matches; both sorted by name;
server-then-name for ties. `limit`/`offset`. Output lines per hit:

```
server_name: tool_name — one-line description
  format (string, default: png)
  fullPage (boolean)
```

(omit the param block when `includeSchemas: false`).

### 9.4 Describe

Full listing: server, tool name, description, each param with type,
required/optional, description, enum/default when present. Ambiguous
(same tool name on multiple servers) → instruct to add `server`.

### 9.5 Status text

Per server: name, lifecycle, state (`idle`/`connecting`/`connected`/
`failed`/`disabled`/`misconfigured`/`unsupported-transport`), auth state
(`bearer` | `oauth` logged-in/expired/none) when configured, tool count
(from cache or live), error tail when failed, cache age. Plus global
settings line (direct-tools default, tool-prefix) and cache file age.

## 10. Entry — `kmet/extensions/mcp_adapter/core.clj`

### 10.1 State

```clojure
{:api api
 :config {:mcp-servers {} :settings {}}
 :cache <cache-map-or-nil>
 :servers {name {:definition entry-map
                 :conn (atom nil)      ;; client conn or nil
                 :error (atom nil)     ;; last failure message
                 :lock (Object.)}}     ;; connect mutex
 :registered-direct (atom #{})        ;; registered direct tool names
 :ensure-connected-fn (fn [name])     ;; set at init; used by proxy/direct executors
 :disconnect-fn (fn [name])}
```

### 10.2 init / shutdown

`init` (in order):
1. Load config (`config/load-config`), ensure global `mcp.edn` exists
   (§6.4), build `:servers` from `:mcp-servers`.
2. Register proxy tool (§10.4).
3. Sync direct tools from cache (§10.5).
4. Register `/mcp` command + argument completions (§10.6).
5. Register events: `:session-start`, `:session-shutdown`,
   `:resources-discover` (§10.7).

`shutdown`: close all connections, kill process trees. Idempotent.

### 10.3 Connection lifecycle

- `ensure-connected!` (locked, idempotent): connected + `alive?` → return;
  dead → clear, retry; else connect (dispatch stdio/http by definition),
  handshake, `tools/list`; on success: record `:conn`, clear `:error`,
  refresh cache + save, resync direct tools, rebuild proxy description
  (re-register tool). On failure: record `:error`, throw.
- `disconnect-server!`: `close!`, clear `:conn`; used by `/mcp disconnect`
  and shutdown.
- `:session-start` → background `future` connects of `:eager`/`:keep-alive`
  servers (never blocks session start).
- `:session-shutdown` and extension `shutdown` → disconnect all.
- All servers reconnect-on-use after failure regardless of lifecycle;
  `:eager`/`:keep-alive` differ only in starting at session start.

### 10.4 Proxy tool registration

Plain tool map (runtime consumes by keyword access):

```clojure
{:name "mcp"
 :label "MCP"
 :description (build-proxy-description)   ;; dynamic, see below
 :prompt-snippet "MCP gateway — status, search, describe, and single MCP tool calls"
 :parameters {…§9.1…}
 :execute (fn [params] (proxy/execute @state params))}
```

`build-proxy-description` from config + cache:
`MCP gateway: status, search, describe, call. Servers: chrome-devtools (26
tools), filesystem (5 tools). Servers connect lazily on first use.
search: "screenshot" · tool: "server_tool" args: {...}.`
Re-registered (replaces by name) after every connect/refresh so the system
prompt sees current availability.

### 10.5 Direct tools — pi parity

Opt-in resolution (pi: `MCP_DIRECT_TOOLS` env / per-server / settings):
1. `MCP_DIRECT_TOOLS` env set (not `__none__`) → only listed servers, config
   ignored.
2. `MCP_DIRECT_TOOLS=__none__` → none.
3. Else server `:direct-tools` (bool | name list; list = only those tools),
   else settings `:direct-tools`, else `false`.

Naming (pi `getServerPrefix`): `:server` default → `server_toolname`;
`:mcp` → `mcp_toolname`; `:none`/`:short` → bare toolname with collision
fallback to server prefix. Sanitization: lowercase, `[^a-z0-9_]` → `_`.

Registration:
- From the metadata cache only (never spawns); skip disabled servers and
  url-less/misconfigured ones.
- Tool map: `{:name prefixed :label "MCP: <orig>" :description (or desc
  "(no description)") :prompt-snippet (truncate desc 100) :parameters
  normalized-input-schema :execute (fn [args] (proxy/call-mcp-tool @state
  server orig-tool args opts))}`.
- Schema normalization: ensure `{:type "object" :properties {}}` base;
  pass through properties/required as-is.
- Diff-based resync (`sync-direct-tools!`): register new/updated, unregister
  removed; track names in `:registered-direct`. Runs at init and after every
  connect/refresh.

### 10.6 `/mcp` command

Subcommands (empty = status):

| Subcommand | Behavior |
|---|---|
| `status` (or empty) | **McpPanel** in UI mode — the pi mcp-panel.ts port (§10.6 panel): server/tool rows with direct/proxy toggles, name + description search, reconnect/auth keys, failure display, unsaved-changes discard confirm, 60s inactivity cancel. `ctrl+s` persists the toggles (write-direct-tools! §6.6) and applies them live. No servers → §9.5 text |
| `search <q> [regex]` | §9.3 — text dialog (multi-line) |
| `list [server]` | all servers, or one server's tools (cache; live fallback on `connect` only) — text dialog |
| `connect <server>` | ensure-connected (+ cache refresh + tool resync) |
| `disconnect <server>` | close + kill |
| `enable\|disable <server>` | §6.5 write, notify "run /reload to apply" |
| `refresh` | reload EDN config, add/remove servers (disconnecting dropped ones), resync tools, rebuild description |
| `auth <server>` | run the OAuth flow now — fresh login, replaces stored tokens (§7.8) |
| `logout <server>` | clear stored OAuth tokens + client info |

Output: `ui-notify` (flash) for single-line messages, the extension's own
scrollable TextDialog (panel.clj — a kmet.tui component mounted via
ui-custom) for multi-line ones, `println` headless. The McpPanel and
TextDialog are built directly on the shared kmet.tui.* layer (pi-mcp-adapter
ships mcp-panel.ts the same way) — the extension api carries only the
ui-custom mount point, no host-built dialogs. Completions: subcommands,
then server names for connect/disconnect/enable/disable/auth/logout.

Handler order: kmet dispatches extension command handlers as
`(handler ctx args)` — the extension context first, then the args
string (pi passes `(args ctx)`; kmet's contract and dispatch use
`(ctx args)`, see `test-extensions` "ctx dispatch"). The handler binds
them in that order.

### 10.7 Events

- `:session-start` → background eager/keep-alive connects (§10.3).
- `:session-shutdown` → disconnect all.
- `:resources-discover` → `{:skill-paths [(str (:extension-dir api)
  "/skills/mcp")]}`.
- No tool-call/result hooks needed (tools return `{:content … :is-error}`).

## 11. Skill + README

- `skills/mcp/SKILL.md`: frontmatter (`name`, `description`), body teaching
  proxy usage (search → describe → call), direct-tool naming, lazy-connect
  note, config reference pointer.
- `README.md`: enabling (symlink `extensions/mcp-adapter` into
  `~/.kmet/agent/extensions/`), first-run template note, EDN config
  reference, `/mcp` reference, direct-tools + env var, HTTP/bearer/OAuth
  notes (PKCE/device flows; plaintext token store at
  `~/.kmet/agent/mcp-oauth.edn`), security warning ("MCP config is trusted
  code execution"), Phase-2
  roadmap.

## 12. Validation plan

1. **Fake stdio server** (`scripts/fake-mcp-server.bb`): bb script
   implementing initialize (negotiation), notifications/initialized echo,
   tools/list (with a cursor page), tools/call (echo + error path), a
   notification mid-request, and clean exit.
2. **Fake HTTP server** (`scripts/fake-http-mcp-server.bb`): minimal
   streamable-http endpoint (POST JSON → JSON or SSE response; session-id;
   a slow call to exercise the timeout path). Also a legacy-SSE variant.
3. **Client tests** (bb script, no test framework needed): stdio +
   streamable-http + sse connect/handshake/pagination/call; timeout;
   process-exit error; disconnect kills the process tree.
4. **Config tests**: precedence merge (global → project), camel/kebab
   normalization, template creation, enable/disable write round-trip
   (including the lower-source-disabled case).
5. **OAuth tests**: `fake-oauth-server.bb` (or extend `fake-http-mcp-server.bb`)
   with `/.well-known/oauth-authorization-server`, registration, token,
   authorize (loopback) and device endpoints — DCR → PKCE loopback → token →
   authenticated request → 401-refresh → device flow → logout. The lib's
   unit tests are registered in `kmet.tasks.runner/all-namespaces` (inside the
   normal gates, unlike extension files); the existing `kmet.ai` OAuth tests
   must stay green unchanged, proving the extraction is behavior-neutral.
6. **Panel tests** (`scripts/validate-panel.bb`): McpPanel render (borders,
   statuses, toggle icons, stats, hints), navigation/expand, toggle →
   discard confirm → keep & close, discard-cancel, ctrl+s save result,
   name search, TextDialog scroll/close, OAuth prompt submit, and the
   inactivity-timer cancellation regression (an interrupted timer must not
   fire done).
7. **Extension load**: load the entry against
   `kmet.extension/create-nullable-api` — assert proxy tool, direct tools,
   `/mcp` command, event registrations; then a live smoke in `bb run` with a
   real `mcp.edn` pointing at the fake servers (TUI: status/search/describe/
   call/connect/disconnect/enable/disable/refresh/auth/logout).
7. **Quality**: clj-kondo over the extension files (manual — `bb lint` only
   covers the source roots: src/test/tasks/extensions), cljfmt check, plan/README consistency.

## 13. Implementation order

1. `kmet.libs.oauth` — extract generic machinery from `kmet.ai.oauth`
   (device-code poll, PKCE, callback server), add RFC 8414 discovery + RFC
   7591 DCR + token exchange/refresh; refactor `kmet.ai.oauth` onto it.
   Validated by the existing `kmet.ai` OAuth tests staying green — the
   shared foundation lands first, de-risked before any extension work.
2. `config.clj` (+ template + tests)
3. `client.clj` stdio transport (+ fake stdio server validation)
4. `client.clj` http transports (+ fake http server validation)
5. `auth.clj` adapter (+ fake OAuth server validation)
6. `metadata.clj`
7. `proxy.clj`
8. entry `kmet/extensions/mcp_adapter/core.clj` (state, tools, direct tools, `/mcp`, events)
9. skill + README, full validation pass (§12)

## 14. Risks & notes

- bb `:env` replaces the environment — merge with `System/getenv` manually.
- `:pid` is absent on bb process records — use `(.pid (:proc p))`.
- HTTP abort is not possible in bb — timeout abandons the read; the server
  may complete the call (documented; tool output lost).
- OAuth tokens are stored plaintext (no OS keyring in bb) — `mcp-oauth.edn`
  written with 0600 perms; `logout` clears. Loopback callback binds an
  OS-assigned local port and is unusable on remote/headless hosts —
  `:flow :device` fallback (auto-selected when the metadata exposes a
  device endpoint but loopback is unavailable). Some servers pre-register
  clients / block DCR — `:oauth {:client-id …}` skips registration. 401s
  without `WWW-Authenticate` are handled by pre-emptive token refresh when
  `:auth :oauth` is configured.
- `kmet.libs.oauth` sits inside the `bb lint` / `bb test` gates and must
  pass `kmet.libs.test-self-contained` (no kmet.* requires) — unlike the
  extension files. The extraction must be behavior-neutral for
  `kmet.ai.oauth`'s provider flows (they go through `kmet.libs.http`;
  the lib is transport-agnostic).
- Some servers negotiate an older protocol version — accept the server's
  response version; never send `notifications/initialized` before
  `initialize` succeeds.
- Parallel tool calls (kmet default `:execution-mode :parallel`) race on
  connect — the per-server lock serializes; requests after connect are
  id-matched and safe to pipeline.
- Extension dir layout: `extensions/` is outside the `bb lint` gate — lint
  the files manually; keep them clj-kondo-clean anyway.

## 15. Implementation notes & recorded deviations

Implemented per this contract; the following notes record where the build
landed and every deliberate deviation from the text above.

1. **`kmet.libs.oauth`** (`src/kmet/libs/oauth.clj`) — the generic machinery
   (device-code poll, PKCE, loopback callback server, parse/wait helpers,
   RFC 8414 discovery, RFC 7591 DCR, token exchange/refresh, RFC 8628
   device start) plus a `fetch-json` delegating the transport to
   `kmet.libs.http` (proxy-aware). `kmet.ai.oauth` was refactored onto it:
   the provider flows use `kmet.libs.http` directly; the poll fn
   gained a `:sleep` option so `kmet.ai.oauth`'s `abortable-sleep` var
   (with-redef'd by tests) stays effective through its wrapper — the
   existing `kmet.ai.test-oauth` suite passes unchanged.
2. **Callback port**: the extension owns a process-wide loopback callback
   server bound once on an OS-assigned port (auth.clj
   `ensure-callback-server!`); every flow and the DCR reuse the same
   redirect URI, so "port 0 → OS assigns" and "register once" both hold.
   `:redirect-uri` config (loopback with explicit port) overrides the
   default `/callback` path.
3. **Issuer validation** is deliberately lenient: the metadata `:issuer`
   must share the server URL's origin (or equal the full URL), not match
   byte-for-byte — real servers vary issuer paths. `:skip-issuer-
   metadata-validation` disables it entirely.
4. **`:mcp` tool-prefix** produces `mcp_<tool>` (plan §10.5); the
   collision fallback to the server prefix applies in every mode, not only
   `:none`/`:short`. The proxy surface (search/describe/list output and
   the `tool:` parameter) uses the PREFIXED display names (per the §10.4
   example `tool: "server_tool"`); the cache stores raw wire names and
   `mcp({tool: ...})` resolves either spelling to the raw name for the
   call. An empty search query without a server is an error; with a
   server it lists that server's tools (pi executeSearch).
5. **`notify!`** swallows transport errors (fire-and-forget, pi parity).
   Related discovery: `java.net.http` reports a connection closed without
   a response as "HTTP/1.1 header parser received no bytes" — the fake
   servers answer notifications with an empty 200, and the client treats
   an empty 2xx response to a request as an error.
6. **`:registered-direct`** in the §10.1 state is a map `name →
   fingerprint` (diff-based resync) rather than a plain set.
7. **State/atom convention**: the §10.1 state map lives in an atom;
   `:ensure-connected-fn`/`:disconnect-fn` close over it. `init` stores it
   in a private `state-atom` so `shutdown` can disconnect-all + close the
   OAuth callback server (idempotent).
8. **Status text** uses `(name state)` labels: idle/connecting/connected/
   failed/disabled/misconfigured (no `unsupported-transport` in Phase 1 —
   an invalid `:http-transport` falls back to streamable-http).
9. **Validation** lives in `scripts/` (bb, no test framework):
   `fake-mcp-server.bb` (stdio; cursor-paginated tools/list, error paths,
   mid-request notification, slow call), `fake-http-mcp-server.bb`
   (streamable HTTP JSON + SSE responses, session-id, slow endpoint,
   legacy SSE with endpoint/message events), `fake-oauth-server.bb`
   (well-known discovery incl. RFC 8414 path insertion, DCR, authorize,
   token incl. device pending/slow_down, device start), and
   `validate-client.bb` / `validate-config.bb` / `validate-oauth.bb`
   (27 + 24 + 18 checks, all green). The lib's unit tests are registered
   in `kmet.tasks.runner/all-namespaces` (`kmet.libs.test-oauth`, HTTP mocked
   via with-redefs — no network).
10. **Extension load** was verified against `kmet.extension/create-
    nullable-api` (proxy tool, `/mcp` command + completions, events,
    skill path); the gates (`bb test`, `bb test-ext`, `bb lint`, `bb
    format-check`) pass.
11. **README** updated: OAuth moved out of the Phase-2 roadmap into the
    Phase-1 reference; the `:oauth` config table, security note and
    development/validation section were added.
12. **Extension sci-context constraints** (discovered while wiring the
    real loader, §12.6): the extension context cannot resolve `future`,
    `slurp`/`spit`, direct stream methods (`.write`/`.flush`), or Process
    methods (`.pid`/`.isAlive`/`.toHandle`). The extension therefore uses
    a `spawn` daemon-thread helper (replacing `future`),
    `read-text`/`write-text` helpers over `babashka.fs`
    (`read-all-lines`/`write-bytes`, replacing `slurp`/`spit`),
    `clojure.java.io/copy` (replacing `.write`), and a new host-side
    `process-pid` accessor in `kmet.libs.process` (the process record has
    no `:pid` key and `.pid` is not callable from sci — the lib is the
    shared seam, injected by reference). `proc/alive?` works as-is.
13. **Review-round fixes** (post-implementation review): the request
    timeout is an overall deadline — notifications/stale responses no
    longer reset it (wait-for-response uses a deadline, not a fresh
    timeout per iteration); a configured `:oauth {:redirect-uri}` now
    binds the callback server's port AND path (first flow wins — the
    server binds once process-wide, later flows derive the URI from the
    bound server, so a DCR'd client's registered URI stays valid);
    `/mcp enable|disable` creates the project `.kmet/` directory before
    writing; RFC 7591 DCR preserves an existing token-store entry.
14. **pi-parity additions** (post-Phase-1 review): the OAuth interaction
    map carries `:abort-prompt!` — the flows' finally dismisses the
    pending manual-paste dialog and unblocks its prompt when the browser
    callback wins (pi `manualAbort.abort` / `inputController.abort()`);
    this required a new `ui-close-dialog` extension capability
    (`:close-dialog` in the interactive registry, `api-ui`, the
    `kmet.extension` wrapper, and `create-nullable-api`). Connect
    failures now follow pi's 60s backoff (`FAILURE_BACKOFF_MS`): a
    failed connect records `:failed-at` + message, lazy uses (proxy tool
    calls, direct tools) report `Server "x" not available (last failed
    Ns ago)` inside the window instead of retrying, explicit
    `mcp({connect})`/`/mcp connect` bypass the window and clear the
    failure on success, and status shows `failed Ns ago — reason`
    (reverting to the normal label after expiry).
15. **`clojure.data.json` is injected into extension contexts** — the loader's
    context-injection filter includes the `clojure.data.json` prefix
    (`src/kmet/app/extensions.clj`). data.json's Maven copy runs fine under
    SCI, so it is injected by reference for identity sharing.
    This was validated by loading the extension through the real
    `kmet.app.extensions/load-extension!` in a headless runner and driving
    the `mcp` tool against the fake stdio server from inside the sci
    context (status → connect → call → search → describe → disconnect).
16. **Direct-tools bootstrap** — pi's `init.ts` connects configured
    direct-tool servers that are missing from the metadata cache at
    startup (`direct-tools-bootstrap`, after the eager/keep-alive
    connects); §10.5's cache-only registration made this invisible in the
    plan text, and the first port skipped it. Consequence: after adding a
    `:direct-tools` server to the config, its tools did not register
    (and so did not appear in `/tools`) until the server was connected
    once by hand. Fixed in `kmet/extensions/mcp_adapter/core.clj` with
    `bootstrap-direct-tools!` — background-connects direct-tool servers
    without a fresh cache entry at init and after `/mcp refresh`; each
    connect refreshes the cache and resyncs direct tools via
    `refresh-after-connect!`. Selection matches pi
    (`MCP_DIRECT_TOOLS` env → listed servers, `__none__` → none, else
    server `:direct-tools` / settings `:direct-tools`; disabled and
    misconfigured servers skipped); failures are recorded and swallowed,
    leaving the tools unregistered until a later connect.
17. **Phase-2 OAuth grants implemented** (client-credentials RFC 6749 §4.4
    + jwt-bearer RFC 7523; §3 moved them in, keyring stays out). The
    signing machinery lives in a NEW generic lib `src/kmet/libs/crypto.clj`
    (base64url, DER reader/writer, PEM PKCS#8/PKCS#1 + JWK RSA/EC key
    parsing, `sign-jwt` RS256/ES256) — `kmet.libs.oauth` keeps only the
    flow + token-endpoint logic and calls `crypto/sign-jwt` for the
    jwt-bearer assertion. Registered in `kmet.tasks.runner/all-namespaces` as
    `kmet.libs.test-crypto`.
18. **bb crypto constraints** (why the DER code exists): babashka's fixed
    class registry lacks `RSAPrivateCrtKeySpec` / `ECPrivateKeySpec` /
    `ECNamedCurveSpec` (`Class/forName` fails), and instance-method
    interop on key impl classes is unavailable (`.getModulus` throws
    `NoSuchFieldException`). Keys are therefore constructed ONLY via
    `PKCS8EncodedKeySpec` (which resolves): PKCS#1 and JWK keys are
    DER-wrapped into a PKCS#8 PrivateKeyInfo inside `kmet.libs.crypto` —
    pure byte assembly (a ~60-line DER writer; OIDs for rsaEncryption,
    id-ecPublicKey, secp256r1/384r1/521r1). ES256 signatures are
    converted from java's DER form to JWT's raw r||s. Test keys are
    embedded fixtures (a throwaway RSA-2048 + P-256 keypair) because bb
    cannot extract CRT components at runtime; the PKCS#8 path is also
    covered with runtime-generated keys.
19. **Extension sci ordering**: bb (sci) resolves symbols at analysis
    time, so extension fns must reference only earlier-defined vars —
    the machine-grant helpers and the `machine-token-cache` atom were
    placed before their callers (`logout!`, `make-auth-fns`);
    `oauth-bearer-header` moved ahead of the machine section.
20. **Machine-grant wiring** (§7.8.6): `auth.clj` caches machine tokens
    in an in-memory atom (nothing persisted — the store stays for
    interactive grants); `:on-401` forces a re-fetch (the cached token
    was rejected); `run-flow!` for machine grants fetches + caches
    (validating config) and returns `:logged-in`; `auth-status` returns
    the grant keyword and the proxy status shows `client-credentials` /
    `jwt-bearer`; `logout!` clears the cache. `discover-meta` gained an
    explicit `:token-endpoint` shortcut (no discovery, no issuer check).
    Config: `:grant`, `:token-endpoint`, `:token-endpoint-auth-method`,
    `:private-key-file`/`:private-key-jwk`, `:algorithm`, `:issuer` /
    `:subject`/`:audience` (+ camel aliases `:grantType`,
    `:privateKeyFile`, `:privateKeyJwk`, `:tokenEndpoint`,
    `:tokenEndpointAuthMethod`). Validation: `fake-oauth-server.bb`
    gained `client_credentials` (fixed client id, `access-cc-` tokens)
    and jwt-bearer (assertion header/claims check, `access-jwt-` tokens)
    grants; `validate-oauth.bb` gained two flows — all green (30
    checks), and the existing PKCE/device/redirect-uri checks still
    pass.
21. **Plan location deviation** (§5): the plan file lives at the repo
    root (`mcp-adapter.md`), not at
    `extensions/mcp-adapter/mcp-adapter.md` as the §5 layout tree
    states. The README links it from the extension dir via
    `../../mcp-adapter.md`; the tree entry was removed and this note
    records the deviation (the config.clj docstring reference was
    updated to match).
22. **TUI live smoke performed** (§12.6): the interactive `bb run` pass was
    run in tmux against the fake servers (project `.kmet/mcp.edn` in the
    repo root, throwaway; removed after). Stdio round: McpPanel render +
    expand + toggle + ctrl+s save (writes `:direct-tools` to the project
    file and applies live — direct count 7→2, server row flips to
    `(not cached)`), status/search/list/connect/disconnect/enable/disable/
    refresh/auth/logout flashes and dialogs, enable/disable project-file
    writes (`:disabled true` add / remove), refresh reloading config into
    the panel (`e2e (not cached) disabled`), auth/logout error paths on a
    stdio server, subcommand + server-name tab completions, clean exit
    with no orphaned server processes. HTTP/OAuth round (fake oauth +
    fake http servers, separate origin via
    `:authorization-server-url` pointing at the metadata document +
    `:skip-issuer-metadata-validation`): `/mcp auth` device flow
    (verification-URI flash → in-place polling pending/slow_down →
    success, tokens stored), PKCE flow (authorize-URL TextDialog + the
    manual-paste prompt dialog — completed by typing the fake server's
    `fake-code` into the prompt; exchange + store + abort-prompt! closing
    the prompt), `/mcp connect` over streamable HTTP and legacy SSE
    (tools listed), status panel auth states (`needs auth` before login,
    gone after), `/mcp logout` clearing the store entry (live session
    stays connected — pi parity). **Bug found and fixed**: the
    TextDialog's close-fn invoked the host ui-custom `close` callback
    with 0 args while the host contract is `(fn [result] ...)` — Escape
    on a search/list dialog threw `Wrong number of args (0) passed to:
    sci.impl.fns/fun/arity-1--1465`, the overlay stayed open and the next
    input errored. `make-text-dialog` now adapts (`(fn [] (close nil))`),
    and validate-panel.bb passes a 1-arity close (the host contract) so
    the regression is caught headlessly. **Same bug class found again in
    the OAuth prompt dialog**: `build-interaction`'s `:prompt` stored the
    raw host close and `finish`/`abort-prompt!` called it with 0 args —
    submitting the manual code threw the same arity error AFTER the DCR,
    so the flow died before the token exchange (the client-info stayed,
    tokens never stored). Fixed by adapting the close in the ui-custom
    factory (`host-close` → 0-arity wrapper); headless coverage would
    need a full state/ctx harness, so this one is recorded rather than
    unit-tested (the live smoke re-verified the complete PKCE flow).
    `describe` is not a `/mcp` subcommand (proxy-tool-only, per §10.6) —
    `/mcp describe` correctly reports `Unknown /mcp subcommand`; the
    plan's §12.6 wording implied a TUI describe path that does not exist.
    Note: e2e.bb must be invoked with the fake-server script argument
    (`bb -cp ../../src:src scripts/e2e.bb scripts/fake-mcp-server.bb`) —
    without it the server path is `.../nil` and the connect-dependent
    checks fail.

23. **Phase 2 implemented (Rev 4)** — the remaining out-of-scope list was
    implemented on request; `/mcp serve` was dropped from the plan (see
    §3). Everything below records where the Phase-2 build landed.
24. **Keyring token storage** (§7.8.1): `auth.clj` gained `:token-storage`
    settings (default `:auto`; env `MCP_TOKEN_STORAGE` overrides) with
    platform backends — macOS `security` (add/find/delete-generic-password),
    Linux `secret-tool` (libsecret), Windows Credential Manager via a
    PowerShell P/Invoke script (CredWrite/CredRead/CredDelete; embedded in
    the extension, untested on real Windows — recorded limitation). Secrets
    are per-server, service `kmet-mcp` / account `oauth:<server>`, payload
    = pr-str of the entry (compact — gnome-keyring GKeyFile corrupts
    multiline secrets, pi parity). `:auto` probes the tool and falls back
    to the plaintext 0600 file when absent (Termux has none — the Phase-1
    tradeoff holds exactly there). The `run-tool` helper derefs the
    process (bb has no `process-done`), and the PowerShell body is
    inlined via a top-level `(reset! ...)` (sci evaluates in order).
25. **Prompts → slash commands** (`prompts.clj`, pi prompts.ts): every
    advertised prompt becomes `/mcp__<server>__<prompt>` from the metadata
    cache (registered at init, diff-resynced after connects/refreshes).
    The handler ports parsePromptArgs (bash-style quoting — the tokenizer
    only splits on whitespace OUTSIDE quotes; quotes stay in the token and
    stripQuotes removes them), resolvePromptArgs (named wins per slot,
    undeclared named args pass through, missing required → usage message),
    and formatPromptResult (lone user message bare, mixed roles keep
    `[role]` markers). Delivery is `send-user-message` (pi
    sendUserMessage). `/mcp prompts` lists them. A discovered quirk: sci
    evaluates SET LITERALS as maps — `#{a b}` throws "Duplicate key" when
    `a` and `b` evaluate equal — `(hash-set a b)` is the safe form (also
    fixed in proxy.clj's tool-name-candidates).
26. **Resources → read tools** (pi init.ts/direct-tools.ts resource
    tools): servers advertise `read_<resource>` direct tools by default
    (`:expose-resources false` disables), named via the resourceNameToToolName
    port (sanitize + digit/empty guard), registered from the cache like
    tools and executed via `proxy/read-mcp-resource` (resources/read;
    text/string contents joined, blobs summarized). The metadata cache now
    stores `:prompts`/`:resources` per server and the fingerprint covers
    `:include-tools`/`:exclude-tools`/`:expose-resources`.
27. **Scripted MCP rides the shared script engine** (`tool_source.clj`,
    script.md T2 — retires pi mcp-code.ts + mcp-script-worker.mjs and the
    adapter's own runtime): the extension contributes its cached MCP
    catalog to kmet's builtin `script` tool as a **tool source**
    (`kmet.app.tools.registry/register-tool-source!` +
    `tool-proxy/script-tool-records`) instead of registering a tool of its
    own. Sandbox-only: contributed tools are in the script surface
    (`tools/list`/`tools/describe`/`tools/call`) but never in the model's
    tool set, so the `mcp` proxy stays the model's gateway. Records are
    built from the metadata cache (no spawn), calls go through
    `proxy/call-mcp-tool` — lazy connect, failure backoff, auth and the
    output guard unchanged — and `sync-direct-tools!` re-registers the
    source on every catalog sync (generation bump invalidates cached
    sandbox contexts). Settings `:script-mode false` drops the
    contribution. The old surface changes shipped with it: kmet result
    maps + explicit `@` deref instead of `{:ok :data}` envelopes,
    `println` instead of `emit`/`console.*`, the shared in-process SCI
    sandbox instead of a `bb` subprocess, and the mcpScript renderer's
    place taken by the shared script renderer. Loading `:env` on stdio
    servers was fixed in the same pass (`connect-stdio` used
    babashka.process's variadic form, which silently drops the opts map).
28. **Setup wizard + host-config adoption** (`setup.clj` + `/mcp setup`,
    `/mcp import`, pi mcp-setup-panel.ts + config.ts IMPORT_PATHS):
    known-server presets (deepwiki/context7/notion/github/chrome-devtools,
    pi KNOWN_SERVER_PRESETS), a custom-server form (name, command-or-url,
    args, auth; Enter advances, Esc backs out; add-server tests the
    connection), host-config import with space-toggled kinds, and project
    scaffolding. Host discovery covers cursor/claude-code/claude-desktop/
    codex/opencode/windsurf/vscode paths — JSON files only (codex
    config.toml has no TOML reader in bb; its config.json is covered);
    string commands are split into command+args; entries without
    command/url are dropped. Adoption writes string-keyed servers into
    the project file (the file's convention — keyword/string collisions
    are detected by name) and skips existing names; `:host-config-discovery
    :on` in settings merges host configs at the LOWEST precedence in
    load-config (the merged settings decide, like pi).
29. **include/exclude/searchKeywords globs** (pi types.ts
    matchesToolSelector + search-ranking.ts): `:include-tools`/
    `:exclude-tools` (glob or exact, matched against every prefix-form
    candidate + legacy dash→underscore spellings) gate DIRECT-tool
    registration only (pi parity — the proxy search is not filtered);
    the collision-aware legacy-candidate index was simplified away.
    `:search-keywords` values boost proxy search via the full
    scoreToolMatch port (FIELD_WEIGHTS name 12/original 10/server 8/
    description 5/keywords 5, phrase/exact/prefix/token scoring, coverage
    gate, first-token + whole-field-exact bonuses) — the Phase-1
    name-over-description ranking was replaced.
30. **Idle-timeout reaping** (pi init.ts): settings `:idle-timeout`
    minutes (default 10, 0 disables), server `:idle-timeout` overrides,
    `:keep-alive` servers default to no reaping (pi
    persistsAfterFirstSpawn → 0). Every request!/notify! touches
    `:last-used` on the conn; a daemon thread (spawn, checked every 30s,
    stopped at shutdown) disconnects idle servers. The reaper-stop flag
    lives in the state map — the entry's shutdown must deref the state
    atom first (`(:reaper-stop @state)` — state-atom holds the ATOM,
    not the map).
31. **Output guards** (`output_guard.clj`, pi mcp-output-guard.ts,
    adapted to kmet's string-content results): settings `:output-guard`
    (false disables; map tunes `:max-bytes` 50 KiB / `:max-lines` 2000 /
    `:details-max-bytes` 16 KiB), env kill switch `MCP_OUTPUT_GUARD`.
    Oversized text is truncated to a head-preview with a notice pointing
    at the temp-file spill (0600); the raw MCP result in details is kept
    when its JSON fits, else replaced by a compact summary + spill.
    Applied to proxy calls, resource reads, and scripted MCP calls.
    Deviation: `fs/create-temp-dir` is broken in this bb version
    (NoSuchFileException) and Termux's `/tmp` is read-only — temp dirs
    are built from `TMPDIR` (fallback java.io.tmpdir) + nanoTime.
32. **Streaming tool-call progress**: `request!` gained
    `:on-notification`; `notifications/progress` events are forwarded
    (stdio/SSE via the wait loop, streamable-http via the SSE body
    reader) instead of being dropped. The mcp proxy tool, direct tools,
    and the contributed MCP script tools declare `:streams?` and stream
    progress as partial content (`[progress 42/100 — message]`) while a
    call runs — the host
    shows it live and the final result replaces it. `connect!` only
    queries prompts/resources when the server advertises the capability
    (an unadvertised method errors with -32601).
33. **Validation** (Rev 4): the fake servers gained prompts/resources/
    progress (stdio: cursor pagination for tools, brief/review prompts,
    README/schema resources, slow tool emitting 25/50/75 progress; HTTP:
    prompts/resources + progress events in the SSE response body);
    validate-client.bb grew prompts/resources/progress checks for both
    transports (30 checks), validate-config.bb grew include/exclude/
    keywords/idle-timeout normalization, cache prompts/resources,
    host-discovery + adoption + write-server-entry + presets (30 checks),
    and validate-script.bb drives the contributed MCP source end-to-end
    through create-nullable-api + the real script tool (17 checks). All
    suites plus e2e.bb pass. A headless REAL-loader smoke (kmet.app.extensions/
    load-extension!, sci context) passes: extension load, proxy connect/
    call, scripted MCP calls through the real tool registry.
34. **Unload NPE — resolved, not a host bug**: the headless real-loader
    smoke's unload failure (a `(deref nil)` NPE inside unload-extension!)
    was a bug in the TEST HARNESS, not the host: `load-extension!` returns
    `{:extension name :error nil}` (the NAME), while `unload-extension!`
    takes the Extension RECORD (from the registry / unload-all- extensions!)
    — the smoke passed `(:name result)` = nil. The host was hardened
    anyway: `unload-extension!` now treats nil as a no-op (the cryptic
    deref-nil NPE becomes silence), with a regression test
    (`test-unload-extension-nil-noop` in kmet.app.test-extensions). The
    corrected loader smoke passes end-to-end including clean unload.
