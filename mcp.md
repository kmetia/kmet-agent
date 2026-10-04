# mcp-adapter refactor plan (before the 2026-07-28 protocol revision)

Status: draft. Order is deliberate: **Phase 0 (names consolidation + name
assignment fix) → Phase 1 (extract `kmet.libs.mcp`) → Phase 2 (auth) →
Phase 3 (2026-07-28 protocol work) → Phase 4 (optional hygiene)** — Phases 0,
1 and 2 are landed, and Phase 3 is in progress (3.1–3.3 landed; 3.4–3.8 are
planned in full below — this section is their plan, there is no separate
plan file); phase 4 lands after it. The protocol revision lands before the
hygiene pass so the client is extracted and auth settled first.

Scope: `extensions/mcp-adapter/` and the shared `kmet.libs` layer. Phase 0 and
Phase 1 change no protocol behavior; they move code so the stateless revision
lands in the protocol layer instead of across five files.

## Why now

MCP `2026-07-28` removes the `initialize` handshake and `Mcp-Session-Id`,
requires per-request `_meta` (version/capabilities/clientInfo), adds
`server/discover`, `Mcp-Method`/`Mcp-Name` headers, `resultType`, and
`subscriptions/listen`. Today's `client.clj` mixes transports, JSON-RPC
correlation, and handshake/catalog semantics; era detection and request
decoration would otherwise cut across all three. The extension also has two
copies of tool naming that registration, display and call resolution each
depend on — and they can disagree.

## How changes land

- **Move first, change second.** Phase 0 lands as 0.1 (extract/switch, behavior
  identical) then 0.2 (assignment fix, behavior change). A landing that both
  moves code and changes behavior cannot be bisected or reverted cleanly.
- **Phase 1 lands one transport at a time, risk ascending**: `protocol` (pure)
  → `transport.http` → `transport.sse` → `transport.stdio` (the only
  behavior-adjacent piece, because of the jsonrpc swap). Each step leaves the
  extension scripts green.
- **Baseline first**: capture the six `scripts/validate-*.bb` outputs (or add
  `scripts/validate-all.bb`, one command wrapping them) before 0.1. Every
  landing must reproduce or intentionally update that baseline.
- **The jsonrpc substrate is shared with lsp-adapter**: MCP-specific needs
  (ping policy, progress routing, error translation, process tracking) live
  in the MCP adapter, never in jsonrpc. During Phase 1 the stdio transport
  briefly moved as-is under a time-box, then a follow-up landing folded it
  onto jsonrpc once the wrapper cost was clear — the adapter is thin.

## Decisions

| Area | Destination | Why |
|---|---|---|
| Transports (stdio, streamable HTTP, legacy SSE) + JSON-RPC client core + protocol constants/`_meta`/content | `kmet.libs.mcp.*` | Self-contained already (only `kmet.libs.*`, `babashka.process`, `io`, `core.async`); pi keeps them in a standalone `packages/mcp`; lib tests run under `bb test`/`jolt test` instead of manual scripts; the transport/era seam becomes an API boundary |
| SSE wire framing | `kmet.libs.sse` — **already exists, unchanged** | Generic `parse-sse-line` / `body->reader` / `make-idle-reader` / `stream-loop`; shared with `kmet.ai.api.sse`. The MCP transports use it, never re-implement it |
| MCP legacy SSE transport binding | `kmet.libs.mcp.transport.sse` | GET stream → `endpoint` event → POST URL + JSON-RPC correlation is MCP semantics, not generic SSE framing. **Frozen**: legacy SSE connections negotiate legacy protocol versions only, so Phase 3 must not touch it. Marked deprecated; removed when the HTTP+SSE off-ramp closes |
| Tool naming/selection + name *assignment* | `kmet.extensions.mcp-adapter.names` | Prefix modes, builtin collision, and include/exclude globs are adapter policy, not MCP protocol |
| Auth | extension now; `kmet.libs.mcp.auth` in Phase 2 | `kmet.libs.oauth` already holds discovery/PKCE/device/DCR/exchange; the rest is MCP-auth policy plus an issuer-keyed store |
| Config, metadata cache, catalog, direct tools, proxy, UI, prompts, output guard | extension | Host integration |

Constraints this plan must respect:

- Extensions may require only `kmet.extension`, `kmet.tui.*`, and `kmet.libs.*`;
  every new lib ns must be added to `libs-library-namespaces`
  (`src/kmet/app/extensions/context.cljc:222`) or the extension's require fails.
  Every lib is required once by the host at context build, so keep it lean.
- `kmet.libs.test-self-contained` today scans `src/kmet/libs/` only **one
  directory level** (its `lib-files` maps a directory's immediate children).
  Phase 1 introduces `src/kmet/libs/mcp/transport/*.clj`, so the guard must be
  fixed to recurse (a nested `transport/` dir is otherwise unchecked).
- `extension.edn` is `:loader [:jolt :sci]`: stick to plain maps/functions, no
  `defprotocol`/`deftype`, no new Java interop.
- The mcp-adapter has no test project; its suite is the six
  `scripts/validate-*.bb` files plus `scripts/e2e.bb`. Keep their public surface
  intact (a facade in `client.clj`) so they stay usable as integration tests.
- Confirm `jolt test` and the libs gates work **before** Phase 1 — don't
  discover a host gap after the extraction.

---

## Phase 0 — names consolidation + assignment fix (extension-local)

Status: **landed** (0.1, 0.2, 0.3) — `validate-all.bb` runs the eight-script
suite (names, client, config, oauth, panel, script, e2e, protocol), all green.

Goal: one source of truth for tool naming, and **display == registered name**
for every collision case. Today `core.clj` (registration) and `tool_proxy.clj`
(display/resolution) each own a copy, only `core.clj`'s has the builtin
collision fallback, and the registration `seen` set depends on the
direct-tools filter — so toggling `:direct-tools` can rename a tool, and with
`:tool-prefix :none` a colliding tool displays as `tool` but registers as
`server_tool`.

### 0.1. Move: `kmet.extensions.mcp-adapter.names` (`names.clj`), behavior identical

Pure primitives (moved, semantics preserved):

| Fn | Source |
|---|---|
| `builtin-tool-names` | `core.clj:35` (`read bash edit write grep find ls mcp`) |
| `sanitize-tool-name` | `core.clj:290`, `tool_proxy.clj:90` |
| `sanitize-server-name` | `tool_proxy.clj:112` |
| `resource-tool-name` | `tool_proxy.clj:96` |
| `prefix-for [server-name mode]` | `core.clj:296` (`:server`/`:short`/`:mcp`/`:none`) |
| `effective-mode [definition settings]` | pure replacement for `tool_proxy.clj:322` (no state deref) |
| `prefixed-name [server-name tool-name mode]` | composition without collision state (`tool_proxy.clj:329`) |
| `registration-name [server-name tool-name mode taken]` | today's collision-aware `prefixed-tool-name` (`core.clj:307`), kept through 0.1 and replaced by `assign-names` in 0.2 |
| `tool-name-candidates` | `tool_proxy.clj:117` (raw + legacy dash→underscore + every prefix spelling; kept for accepting legacy call spellings) |
| `tool-allowed?` | `tool_proxy.clj:162` (include/exclude globs; glob fns private) |

Caller switches in this landing (all behavior-identical):

- `core.clj`: `direct-tools-specs` — `names/tool-allowed?` (370),
  `names/registration-name` (371; today's collision-aware
  `prefixed-tool-name`), `names/resource-tool-name` (389, 399).
- `tool_proxy.clj`: `find-tool` (586), `search-text` (565), `describe-text`
  (613), `list-text` (664/669/671), `script-tool-records` (796),
  `resolve-search-keywords` (200) → `names/` (still `format-tool-name`
  semantics here; 0.2 replaces it with the assignment lookup).
- `prompts.clj`: prompt-command prefix (36, 39) → `names/sanitize-server-name` /
  `names/prefix-for`; keep `proxy/ensure-lazy-connected` and
  `proxy/truncate-at-word`.
- Delete the moved originals from `core.clj` and `tool_proxy.clj` (no
  re-export; scripts only use `proxy/call-mcp-tool`, verified).
- No changes in `panel.clj`, `setup.clj`, `tool_source.clj`, `auth.clj`,
  `config.clj`, `metadata.clj`.

### 0.2. Fix: deterministic name assignment (behavior change)

```clojure
(defn assign-names
  "Deterministic assigned name for every catalog entry.
   ENTRIES: [{:server s :kind :tool :id raw-tool-name}] plus resource entries
   {:server s :kind :resource :id uri} whose candidate base is
   read_<resource-tool-name>, over all configured, non-disabled servers.
   MODE-FOR: (fn [server] mode) — per-server :tool-prefix over settings.
   Returns {[server kind id] assigned-name}."
  [entries mode-for])
```

Rules:

1. Candidate = `prefixed-name` under the server's mode.
2. **Global collision detection, not first-claim.** Group entries by candidate.
   A candidate shared by more than one entry — or a bare candidate that is a
   builtin tool name — sends **every** entry in the group to the fallback.
   Order-independent: adding a colliding tool never renames an existing one and
   the assignment does not depend on cache/listing order. (Removing a colliding
   tool does not restore the plain name until the next refresh — accepted; it
   matches pi.)
3. Fallback = `sanitize(server) + "_" + sanitized`. If the fallback itself is
   shared by several entries (two server names that sanitize the same), append
   a short identity digest `(format "%08x" (hash [server kind id]))` to every
   entry in that group — pi's `createMcpToolName` suffix idea, shortened.
4. The assignment covers the **full enabled catalog** and is **independent of
   the direct-tools/include/exclude filters** — so changing which tools are
   registered never renames a tool.
5. Resource entries key on `[server :resource uri]` but their candidate base is
   the composed `read_<resource-tool-name>` — otherwise tool↔resource
   collisions slip through.

Wiring:

- `core.clj`: `sync-direct-tools!` builds the ordered entries once, calls
  `names/assign-names`, stores the result as `:tool-names` in the state map
  (init `{}` in `init-state`), and builds the direct specs from it (replacing
  the `seen` atom in `direct-tools-specs`).
- `tool_proxy.clj`: a single `display-name state server kind raw` helper
  (`(get (:tool-names state) [server kind raw])`, falling back to
  `names/prefixed-name` with `names/effective-mode`) replaces every
  `format-tool-name` call site. `find-tool` matches an assigned name before
  falling back to `tool-name-candidates`.

### 0.3. Tests and gates

- New `scripts/validate-names.bb` (pure, no servers):
  - display name == assigned name for a `:tool-prefix :none` collision and for
    a builtin collision (the divergence being fixed);
  - global collision rule: all members of a colliding group fall back, and
    assignment is order-independent (shuffled input, same result);
  - fallback equality for sanitized-server-name collisions gets the digest
    suffix;
  - assignment stable when the direct-tools filter changes;
  - resource candidate base `read_<...>` participates in collisions;
  - per-server `:tool-prefix` over settings; `:short` trims `-mcp`;
    `tool-name-candidates` still resolves legacy spellings;
  - glob include/exclude; `resource-tool-name` edge cases.
- New `scripts/validate-all.bb` wrapping the six existing scripts (baseline +
  per-landing check).
- Run the scripts that assert concrete names: `validate-panel.bb`,
  `validate-script.bb` (`fake_echo`), `e2e.bb` (`e2e_echo`,
  `e2e_read_project_files`, `e2e_read_issues_all`), `validate-config.bb`.
- `bb lint-changed`, `bb format-check-changed` (or `bb format-changed`).
- Baseline: run `validate-all.bb` before 0.1 and after each landing.

---

## Phase 1 — `kmet.libs.mcp` hierarchy (protocol + client + transports)

Extract the transport-neutral MCP client from `client.clj` — stdio (80),
streamable-http (151), request core (381), handshake/discovery (747), result
formatting (918) — plus the legacy SSE transport. Behavior preserved; era
neutrality deliberate (Phase 3 is 2026-07-28).

Status: **landed** (1.0-1.5), plus the follow-up stdio swap: the transport
now runs on `kmet.libs.jsonrpc` — line framing, id allocation and response
correlation are shared with lsp-adapter, with MCP policy (ping replies,
progress routing, error translation, tree-kill + pid tracking) as a thin
adapter. Request ids are transport-owned as a result (the client no longer
allocates them), and the client repoints the transport's `:conn-ref` at the
final conn so internal callbacks see `:capabilities`. `scripts/validate-all.bb`
and the repo gates are green on both hosts.

### 1.0. `kmet.libs.jsonrpc` additions (stdio substrate)

The stdio transport was to use the shared lib instead of the bespoke stdio
transport (`stdio-argv`, `drain-stdout`, `drain-stderr`, `write-stdio-msg!`,
channel wait). It already provides line framing, pending map with timeouts,
`:on-request`, `:on-notification`, stderr tail, `last-used`, `alive?`,
`close!`, and the `connect-streams` injection seam.

One additive change was needed: put the request `:id` in the
timeout/request-error ex-data (`::timeout` / `::request-error`), so the MCP
client can send `notifications/cancelled` with the abandoned request's id.

Per-request progress routing stays in the MCP client layer — the client
installs each in-flight request's callback as the conn's
`:progress-callback` and serializes stdio requests, so one slot is
unambiguous (pi's `progressRequests`, simplified by that serialization).

If this needs more than the `:id` addition, stop and move the current stdio
transport into the lib unchanged, then revisit. Outcome: Phase 1's first
landing did exactly that; the follow-up landing folded stdio onto jsonrpc
with no further jsonrpc changes — the wrapper passes its own `:kill-fn`,
`:on-request` and `:on-notification`, and translates `::jsonrpc/*` failures.

The jsonrpc side is covered in `test/kmet/libs/test_jsonrpc.clj` (the
`:id`/`:method` payload on every failure kind, next to the existing framing
and close semantics), and lsp-adapter is unaffected (additive) —
`extensions/lsp-adapter/scripts/validate.bb` is the check. The cancellation
itself is the adapter's, tested in `test_transport_stdio.clj`
(`timeout-cancels-the-abandoned-request`) and end to end by
`validate-client.bb`'s FAKE_LOG assertion.

### 1.1. Layout and namespaces

```
src/kmet/libs/mcp/protocol.clj            kmet.libs.mcp.protocol
src/kmet/libs/mcp/client.clj              kmet.libs.mcp.client
src/kmet/libs/mcp/transport.clj           kmet.libs.mcp.transport
src/kmet/libs/mcp/transport/stdio.clj     kmet.libs.mcp.transport.stdio
src/kmet/libs/mcp/transport/http.clj      kmet.libs.mcp.transport.http
src/kmet/libs/mcp/transport/sse.clj       kmet.libs.mcp.transport.sse
```

- `protocol`: `default-request-timeout-ms`, `initialize-timeout-ms`,
  `list-page-timeout-ms`, `protocol-version`, `supported-protocol-versions`,
  `client-info`, `mcp-error`, `format-result` (content blocks →
  `{:text :is-error}`). This is where the era map, `_meta` decoration,
  `resultType`, and `-32022` handling land in Phase 3.
- `client`: `progress-token`, `request!`, `notify!`, `close!`, `alive?`,
  `last-used`, `establish!` (handshake + capability-gated catalog fetch),
  `connect!` (all three transports), `list-all-tools`, `list-all-prompts`,
  `get-prompt`, `list-all-resources`, `list-all-resource-templates`,
  `read-resource`, `expand-uri-template`. **Connection-shape hygiene**: the lib
  consumes an explicit connection subset (`:command :args :env :cwd :url
  :http-transport :request-timeout-ms`) or a `:connection` sub-map; the adapter
  maps its config onto it — no `:lifecycle`/`:direct-tools`/`:tool-prefix` keys
  leak into the lib. Auth stays injected via
  `:auth-headers`/`:on-401`/`:on-notification` (unchanged contract, so Phase 2
  slots in).
- `transport`: the conn contract (documented keys + the fns each transport
  provides: send request, notify, reply, cancel, close, alive?, last-used) and
  shared `header-value`.
- `transport.http`: current streamable-HTTP code (`base-http-headers`,
  `http-post!`, `parse-http-response`, response-body SSE parsing),
  `terminate-http-session!`, over `kmet.libs.http`. Response-body SSE parsing
  uses `kmet.libs.sse/parse-sse-line` instead of the hand-rolled loop in
  `read-sse-response`. Add a `:request-fn` injection point (pi injects `fetch`)
  so fast unit tests need no socket.
- `transport.sse`: the legacy binding (`sse-endpoint-url`, `drain-sse-stream`,
  `open-sse-stream!`, `request-sse!`), over `kmet.libs.sse/parse-sse-line` (and
  `stream-loop` where its idle/abort/cleanup semantics fit). Migrated as-is, no
  behavior improvements; docstring marks it deprecated and points at streamable
  HTTP.
- `transport.stdio`: MCP policy over `kmet.libs.jsonrpc` — ping replies,
  per-request progress routing, `::jsonrpc/*` → `:mcp-error` translation,
  tree-kill + host pid tracking, `:conn-ref` hand-off.

`uri-escape` stays private to the client.

### 1.2. Extension facade

`client.clj` keeps its namespace and public surface as a **pure re-export** of
the lib (all three transports now live in the lib), so `core.clj`,
`tool_proxy.clj`, `auth.clj`, `prompts.clj`, and all six scripts are untouched:
`protocol-version`, `supported-protocol-versions`, `client-info`,
`progress-token`, `default-request-timeout-ms`, `initialize-timeout-ms`,
`list-page-timeout-ms`, `header-value`, `connect!`, `request!`, `notify!`,
`close!`, `alive?`, `last-used`, `list-all-*`, `get-prompt`, `read-resource`,
`expand-uri-template`, `format-result`. Use plain `(def x lib/x)` aliases.
The facade can be deleted in Phase 3 with callers pointed at the lib directly.

### 1.3. Wiring

- Add all six lib ns symbols to `libs-library-namespaces`
  (`src/kmet/app/extensions/context.cljc:222`).
- Fix `test/kmet/libs/test_self_contained.clj` `lib-files` to walk
  `src/kmet/libs` recursively (e.g. `(fs/glob "src/kmet/libs" "**")` filtered
  to `.clj`/`.cljc`/`.clj?`), so `mcp/transport/*.clj` is checked.
- Register new test namespaces in `tasks/kmet/tasks/runner.clj`
  (`all-namespaces`, pattern: `kmet.libs.test-jsonrpc` at :156).

### 1.4. Tests

- `test/kmet/libs/mcp/test_protocol.clj` — versions; `mcp-error` shape;
  `format-result` for text/error/image/`structuredContent`/empty; template
  escaping and expansion (missing var left in place).
- `test/kmet/libs/mcp/test_client.clj` — `expand-uri-template` (escaping,
  missing vars), the capability-gated `establish!` flow (request!/notify!
  redefined): advertised capabilities only, unsupported revision rejection,
  -32601 template tolerance, and the `connect!` `:conn-ref` hand-off.
  Subprocess cases stay in `scripts/validate-client.bb` / `e2e.bb` or get
  `^:slow`.
- `test/kmet/libs/mcp/test_transport_stdio.clj` — ping policy, progress
  routing, error translation, the timeout→`notifications/cancelled` payload,
  plus a `^:slow` end-to-end run against a real `bb`-spawned JSON-RPC server
  (initialize → list with a progress event routed to the callback → server
  ping auto-reply).
- `test/kmet/libs/mcp/test_transport_http.clj` — JSON and SSE response bodies,
  content-type branching, header lookup, 401 retry hook, via `:request-fn`
  injection. Socket-level end-to-end stays in `validate-client.bb` /
  `fake-http-mcp-server.bb`.
- `test/kmet/libs/mcp/test_transport_sse.clj` — endpoint event resolution
  (path vs JSON), message assembly, stream drop, against an injected stream;
  the existing SSE section of `validate-client.bb` covers the real server.

### 1.5. Gates

`bb test` and `bb test-ext` (new lib namespaces included), `bb lint`,
`bb format-check`, `bb check`, `bb check-bundled-extensions`; then the full
extension suite (`scripts/validate-all.bb`, or individually):

```
# `bb -cp` replaces the classpath; org.clojure/data.json is bb-bundled
# (1.13.224+).
bb -cp ../../src:src scripts/validate-client.bb scripts/fake-mcp-server.bb scripts/fake-http-mcp-server.bb
bb -cp ../../src:src scripts/validate-config.bb
bb -cp ../../src:src scripts/validate-panel.bb
bb -cp ../../src:src scripts/validate-oauth.bb scripts/fake-oauth-server.bb
bb -cp ../../src:src scripts/validate-script.bb scripts/fake-mcp-server.bb
bb -cp ../../src:src scripts/e2e.bb scripts/fake-mcp-server.bb
```

Also `extensions/lsp-adapter/scripts/validate.bb` (jsonrpc change) and
`jolt test` (or the host's gate) for the libs.

---

## Phase 2 — auth

`kmet.libs.mcp.auth`: challenge parse/record, RFC 8707 resource
canonicalization, the issuer-keyed credential store incl. keyring
backends, discovery, the flow steps, pre-emptive refresh + 401-once retry
header provider — built on `kmet.libs.oauth`. The extension keeps config
mapping, status, the interaction map and callback server, `/mcp
auth|logout`. Status: **landed** (2.1–2.6), including the 2026 hardening:
issuer binding (SEP-2352, with the `mcp-oauth.edn` migration from
server-keyed entries), `iss` validation (RFC 9207), DCR
`application_type` (SEP-837).

Landing order (same discipline as Phase 1; `validate-oauth.bb` is the
baseline):

- **2.1 pure policy — landed**: `canonical-resource-uri`,
  `parse-www-authenticate`, the challenge record plus
  `challenge`/`clear-challenges!`, `scopes-string`, `effective-scopes`,
  `effective-resource`, with `test/kmet/libs/mcp/test_auth.clj`. The
  extension keeps `def` aliases for the two names `validate-oauth.bb`
  calls (`canonical-resource-uri`, `parse-www-authenticate`) and goes
  straight to the lib everywhere else.
- **2.2 the store — landed**: `configure-storage!` (host-supplied
  `:path`), `storage-kind`/`keyring-available?`, the `:file` backend
  (atomic write, 0600) and the `:keyring` backend (macOS `security`,
  Linux `secret-tool`, Windows Credential Manager P/Invoke; `:auto`
  dispatch), plus `server-entry`/`store-server!`/`logout!` — with store
  tests in `test/kmet/libs/mcp/test_auth.clj`. The extension keeps
  `store-path` (agent-dir policy), the `MCP_TOKEN_STORAGE` env override,
  and the `logout!` wrapper that also drops the in-memory machine-token
  cache; `def` aliases keep `server-entry`/`store-server!` callable from
  `validate-oauth.bb`, which now configures `:file` explicitly with the
  redefined `store-path` (a temp file, not the agent-dir store). Store
  robustness rode along: the file backend's read-modify-write is locked
  (concurrent logins no longer lose entries), a blank path is rejected,
  a parentless path is accepted, and a timed-out keyring tool is reaped.
- **2.3 discovery + token lifecycle — landed**: discovery moved in too
  (refresh and machine grants need metadata at request time):
  `origin-of`/`issuer-matches?`/`server-headers`/`protected-resource-meta`/
  `discover-document`/`discover-meta`/`required-endpoint`; then
  `tokens->store`/`store-tokens!`/`token-expired?`, `bearer-token`,
  `oauth-header`/`refresh-tokens!`/`oauth-header-after-401`, the machine
  grants (`grant-of`/`machine-grant?`/`fetch-machine-token!`, the cache
  with `machine-token-cached?`/`clear-machine-tokens!`), and finally
  `make-auth-fns` — the transport's `:auth-headers`/`:on-401` seam. The
  extension keeps `auth-status`, the flows and the callback server, plus
  `def` aliases for the script surface (`make-auth-fns`, `discover-meta`,
  `machine-token-cached?`, `logout!`); the script's raw
  `machine-token-cache` resolution became `machine-token-cached?`.
  Hardening on top: the machine cache is keyed by server + connection
  fingerprint (`:url`/`:oauth`) so a config edit re-fetches instead of
  reusing a token minted for the old target; a token response without
  `expires_in` is kept without an expiry (used until a 401); and
  `:auth :bearer` with no token throws instead of sending
  `Authorization: Bearer `.
- **2.4 flow split — landed**: the flow steps moved into the lib:
  `resolve-flow`, `verify-pkce-support!`, `resolve-client-id!` (issuer-bound
  registration), `prepare-pkce-flow` / `complete-pkce-flow!`,
  `begin-device-flow!` / `complete-device-flow!`, and `application-type`.
  The extension's `run-flow!` keeps the interaction map, the callback
  server + manual-paste race, and the status text. `validate-oauth.bb`
  exercises the same public surface as before (plus the 2026 checks).
- **2.5 2026 hardening — landed**:
  - the `:file` store is issuer-keyed (`:version 2`: `:servers` holds the
    server → issuer binding, `:issuers` the shared client registration and
    per-server tokens) with a read-side migration — an unstamped
    pre-SEP-2352 entry is back-stamped to the first issuer resolved on
    authenticated use (its registration is claimed only when the issuer
    has none, so a later legacy entry joins the shared registration
    instead of replacing it);
  - `resolve-client-id!` re-registers when the authorization server
    changed and throws `:oauth-issuer-mismatch` for pre-registered
    credentials instead of reusing them; `refresh-tokens!` never sends a
    refresh token to a different issuer;
  - RFC 9207 `iss` validation (`validate-authorization-response!`, keyed on
    `authorization_response_iss_parameter_supported`; no normalization, and
    error text is surfaced only for an authentic response);
  - DCR sends the SEP-837 `application_type` (loopback → "native", remote
    → "web", mixed → omitted; `:oauth {:application-type ...}` overrides).
    The `:keyring` backend keeps per-server entries, stamped with the issuer
    for the same cross-AS isolation.
- **2.6 gates — landed**: `validate-oauth.bb` (SEP-2352 store/binding,
  SEP-837 application_type, RFC 9207 accept/reject/missing) and
  `validate-client.bb` (401 retry) green; `bb test`, jolt, lint/format,
  and `check-bundled-extensions` green. `bb test-ext` is clean apart from
  the pre-existing overlay-input smoke, which needs lsp-adapter in the
  host's extension set (it fails the same way without this change).

## Phase 3 — 2026-07-28 protocol work (the end goal)

Status: **in progress** — 3.1–3.3 landed; 3.4–3.8 below, in order; each leaves
`scripts/validate-all.bb` and the repo gates green. Depends on Phase 1's
era-neutral seam and Phase 2's auth plumbing (both landed).

No pi reference: pi's current main still negotiates only `2025-11-25` and
earlier (`packages/mcp/src/protocol/types.ts`, `SUPPORTED_PROTOCOL_VERSIONS`),
and its conformance README says "2026-07-28 is not covered: it is a stateless
protocol pi does not implement." Phase 3 is spec-driven — the lifecycle and
versioning page, the stdio and streamable-HTTP bindings, `server/discover`,
subscriptions and MRTR are the reference. Nothing to port; the fakes (3.1)
are what pins the wire.

### Protocol delta

| # | Delta | Spec | Client work |
|---|---|---|---|
| 1 | `initialize`/`notifications/initialized` removed; every request/notification carries `_meta` (`io.modelcontextprotocol/protocolVersion`, `.../clientInfo`, `.../clientCapabilities`) | SEP-2575 | `protocol` meta helpers + `request!`/`notify!` decoration (3.2) |
| 2 | `server/discover` → `{:resultType :supportedVersions :capabilities :instructions}` plus `_meta.io.modelcontextprotocol/serverInfo`; modern capabilities/identity source; stdio probe | SEP-2575 | `client/discover!`; stdio detection (3.3) |
| 3 | `Mcp-Session-Id` and the GET stream removed | SEP-2567 | no session capture/echo on modern conns (3.4) |
| 4 | `Mcp-Method`/`Mcp-Name` required on POSTs (`params.name`/`params.uri`; Base64 sentinel when not header-safe); `-32020` on mismatch | SEP-2243 | `transport.http` headers (3.4) |
| 5 | `x-mcp-header` tool params mirrored to `Mcp-Param-*`; invalid annotations ⇒ the tool is excluded from `tools/list` (HTTP) | SEP-2243 | `transport.http` helpers + `list-all-tools` filtering (3.6) |
| 6 | Every result carries `resultType` (`complete`/`input_required`); missing = `complete`; `input_required` = MRTR (`inputRequests`/`requestState`) | SEP-2322 | envelope check; `requestState`-only rounds retried, `inputRequests` refused (3.2) |
| 7 | List/read/discover results carry `ttlMs`/`cacheScope` | SEP-2549 | hint only; optionally cap metadata-cache freshness (3.7) |
| 8 | `subscriptions/listen` replaces the GET stream and `resources/subscribe`; ack-first; notifications tagged `_meta.io.modelcontextprotocol/subscriptionId` | SEP-2575 | `client/listen!` + transports (3.5) |
| 9 | New error codes: `-32020` HeaderMismatch, `-32021` MissingRequiredClientCapability, `-32022` UnsupportedProtocolVersion (`data.supported`/`data.requested`) | changelog minor 12 | `protocol/modern-error-codes`; negotiation (3.2) |
| 10 | `ping`, `logging/setLevel`, resumability (`Last-Event-ID`) removed; `notifications/cancelled` is stdio-only | SEP-2575 | cancel guards; no resumption code to write (3.4) |

No work for the removed server→client requests (`roots/list`,
`sampling/createMessage`, `elicitation/create`): the client declares `{}`
capabilities and already refuses them with `-32601`.

Era model (spec, Versioning and Compatibility): **modern** = per-request
metadata (`2026-07-28`+); **legacy** = `initialize` handshake (`2025-11-25`
and earlier, the existing `protocol/supported-protocol-versions`);
**dual-era** = both. Era is a property of the server (stdio process / HTTP
origin), cacheable per config, re-probed on failure. Detection must not be
keyed to one error code: stdio probes `server/discover` and falls back on
anything that is not a *recognized modern error* (`-32020`/`-32021`/`-32022`);
HTTP attempts a modern request and inspects the body of a `400`.
`transport.sse` never detects: legacy HTTP+SSE negotiates legacy versions
only, so it is frozen out of every landing below.

### 3.1. Baseline: modern fakes + `scripts/validate-protocol.bb`

- `scripts/fake-mcp-server.bb` and `scripts/fake-http-mcp-server.bb` gain an
  additive modern mode (stdio: argv flag/env — `--era modern` /
  `KMET_FAKE_ERA`; HTTP: `?era=modern`): `server/discover` with
  `DiscoverResult`; `_meta` required on every request (missing ⇒ `-32602`;
  version not in `supportedVersions` ⇒ `-32022` with
  `{:supported [...] :requested ...}`); every result
  `resultType "complete"`; `subscriptions/listen` ack-first with
  `io.modelcontextprotocol/subscriptionId` stamping; HTTP mode also
  validates `MCP-Protocol-Version`/`Mcp-Method`/`Mcp-Name` (mismatch/absent
  ⇒ `400` `-32020`), never sees a session header, answers GET/DELETE
  `405`, and advertises cache fields.
- Both fakes need a deterministic change trigger for the listen tests: the
  stdio fake emits `notifications/tools/list_changed` on a later
  `tools/call` (one channel); the HTTP fake needs a shared
  pending-notification queue and a non-blocking request loop so a trigger
  POST can deliver onto an already-open listen stream.
- Legacy modes stay byte-for-byte what today's scripts assert — the modern
  mode exists so the unchanged client still passes `validate-all.bb`
  (nothing probes yet).
- New `scripts/validate-protocol.bb` is the phase's end-to-end suite; add it
  to `validate-all.bb`'s `runs` (both fakes) as the eighth script. In this
  landing it asserts the fakes' own wire shape only (discover, `-32022`
  data, header rejection, session/GET/DELETE behavior); client assertions
  accumulate per landing.
- Capture the baseline before 3.2: `validate-all.bb` output, and the
  `validate-client.bb` "unsupported revision rejected" check — the fake's
  `initialize` answers `2026-07-28` and the legacy parser must keep
  refusing it (a legacy handshake must never select a modern revision).
- No lib/adapter changes. Gates: `bb lint-changed`,
  `bb format-check-changed` (or `bb format-changed`); `validate-all.bb`.

Status: **landed** — both fakes speak modern additively (stdio `--era
modern` / `KMET_FAKE_ERA=modern`, HTTP `?era=modern`), and
`validate-protocol.bb` is the eighth script in `validate-all.bb`. It pins
the modern wire (discover result, `-32022` data, header rejection with
`400` + `-32020`, `404` for unimplemented methods, `405` GET/DELETE, no
session minting, ack-first listen with `subscriptionId` stamping and
subscription-scoped list_changed, Base64 `Mcp-Name`) and proves the legacy
shapes untouched. Baseline captured: eight scripts, zero failures.

### 3.2. Era core in `kmet.libs.mcp` (behavior-neutral)

`protocol.clj`:

- `modern-protocol-version "2026-07-28"`, `modern-supported-versions`
  (the revisions the client can send), and `probe-timeout-ms` (10–15s),
  next to the existing legacy `supported-protocol-versions` (whose
  docstring loses "not reachable from here").
- `meta-ns`/`meta-key` and `modern-request-meta` — the `_meta` map:
  version, `io.modelcontextprotocol/clientInfo` = `client-info`, and
  `io.modelcontextprotocol/clientCapabilities` `{}` (kmet implements no
  elicitation/sampling/roots, so a conforming server can never send us an
  `inputRequests` entry — see MRTR in 3.2).
- `modern-error-codes` = `#{-32020 -32021 -32022}` and `(modern-error? ex-data)`.
- `(negotiate-version supported)` → `{:era :modern|:legacy :version rev}`
  for the newest mutually supported revision (our modern list first, then
  the legacy list; the server's ordering is ignored) or nil when there is
  no overlap. A server that answers `server/discover` but advertises only
  legacy revisions is dual-era: fall back to `initialize` instead of
  erroring (3.3/3.4).
- a `result-type` accessor: absent or `"complete"` → `"complete"`, else the
  raw value.

`client.clj`:

- Every conn carries an `:era` atom (`nil` = legacy until proven
  otherwise). Transports do not change: `connect!` assocs it like it
  already does `:capabilities`, and the `:conn-ref` repoint keeps
  transport internals consistent. Public `(modern? conn)`.
- `request!`/`notify!` merge `modern-request-meta` into `params._meta` when
  the conn is modern (the spec carries `_meta` inside `params` on stdio and
  HTTP alike).
- After a transport result: missing/`"complete"` passes through; a modern
  conn + `resultType "input_required"` is MRTR:
  - **no `inputRequests`** (a `requestState`-only round) → retry the same
    request with `requestState` echoed byte-for-byte, capped at two rounds,
    then error. A capability-less client can serve this pattern.
  - **with `inputRequests`** → throw `mcp-error` naming the keys and
    methods (`{:result-type :input-required :input-requests ...}`). kmet
    declares `{}` client capabilities, so a conforming server can never
    send elicitation/sampling/roots requests; this fires only on
    non-conformance, and refusing loudly beats looping. `inputResponses`
    is never sent.
- `notifications/cancelled` remains the stdio cancellation mechanism; on
  modern HTTP conns it is suppressed (3.4) — there, closing the response
  stream is the cancellation and the modern core defines no client→server
  notification over HTTP. The stdio timeout path sends it from
  `transport.stdio/translate-exception` via `jrpc/notify!`, bypassing
  `client/notify!`, so that path must merge `modern-request-meta` itself on
  a modern conn.

Behavior-neutral: nothing sets an era to `:modern` yet, so every existing
path is unchanged. Tests: `test_protocol.clj` (meta map, negotiation —
including a server advertising only unknown revisions, error-code set,
result-type accessor); `test_client.clj` (`_meta` merged on requests and
notifications, none on a legacy conn; MRTR — `requestState`-only retry
bounded, `inputRequests` refusal; `complete`/absent passthrough) with the
transports' `request!` redefined as in Phase 1. Gates: `bb test` (lib
namespaces), lint/format.

Status: **landed** — `protocol.clj` owns the modern revision list,
`probe-timeout-ms`, the `_meta` key/map builders and the pure negotiation
helpers (`modern-conn?`, `conn-meta`, `negotiate-version`, `result-type`,
`modern-error-codes`); every conn carries an `:era` atom, added by
`connect!` and still nil on every path; `request!`/`notify!` merge the era
`_meta`, and MRTR retries a `requestState`-only `input_required` twice
before erroring while refusing `inputRequests` with the keys and methods
named; the stdio timeout cancellation merges the `_meta` itself (it
bypasses `client/notify!`). Behavior stays neutral — nothing records an
era yet — and the eight-script extension suite plus `bb test` are green.

### 3.3. stdio probe + modern establish

- `client/connect!` gains an era-establish step before the catalog work:
  - explicit hint (`:protocol-era` opt, 3.7) → skip detection: a modern
    hint runs `discover!` directly, a legacy hint runs `initialize!`;
    era-inconsistent failures fall into the shared re-probe (3.7);
  - else `detect-stdio-era!`: send `server/discover` with `_meta` forced
    (the era is still unknown — an internal `request-with-meta!`):
    - a `DiscoverResult` → `negotiate-version` over `:supportedVersions`: a
      modern result continues modern, a legacy result falls back to
      `initialize!`, nil errors;
    - a JSON-RPC error in `modern-error-codes` → modern; on `-32022`
      negotiate over `data.supported` (retry with the negotiated modern
      revision, or the legacy fallback — nil errors), on `-32020`/`-32021`
      surface the error;
    - any other error, process death, or timeout → legacy.
  - the probe uses a dedicated `protocol/probe-timeout-ms` (10–15s), not
    the 60s initialize timeout: a legacy server that answers unknown
    methods (the common case) resolves immediately, and a silent one
    stalls seconds rather than a minute. Cold-start recovery: when the
    probe timed out and `initialize!` then fails with an *error* (not its
    own timeout), run the modern `discover!` once more with the full
    timeout before surfacing — a modern server that was still booting
    comes back modern. This is the same "re-probe once on an era-ambiguous
    failure" helper 3.7 needs.
  - record `{:era :modern|:legacy :version rev}` in `@(:era conn)` and the
    version in `@(:protocol-version conn)`.
- `establish!` dispatches: modern → `discover!` supplies the capabilities
  and identity (`:server-info` from
  `_meta.io.modelcontextprotocol/serverInfo`; `:instructions` is read and
  dropped — no adapter consumer today) — its result is reused when
  detection just ran, and run here on a cached-modern hint — then the
  existing capability-gated catalog fetches; legacy → `initialize!` exactly
  as today.
- A legacy `initialize` answer outside the legacy list still fails as
  today — the only recovery here is the cold-start re-probe above; a
  wrong cached hint is 3.7's.
- Tests: `test_client.clj` detection table (discover / modern error /
  `-32601` / timeout ⇒ legacy / timeout + failed `initialize` ⇒ recovery
  re-probe), no `_meta` and `initialize` on the legacy path, no
  `initialize` on the modern path; `test_transport_stdio.clj` `^:slow`
  fake-server run covers probe → discover → list → call. Scripts:
  `validate-protocol.bb` stdio modern + legacy; `validate-client.bb`,
  `validate-script.bb`, `e2e.bb` prove the legacy fallback end to end.

Status: **landed** — `establish-era!` resolves the era before the catalog
work and `record-era!` runs before the fetches so their requests carry the
era `_meta`. A `:protocol-era` hint skips detection; a stdio conn is
probed by `detect-stdio-era!` (DiscoverResult negotiation, `-32022`
data.supported retry/fallback, `-32601`/death/timeout ⇒ legacy,
`-32020`/`-32021` surface) and every non-stdio transport runs the
handshake; the probe's round trip is reused by the modern establish, a
timed-out probe plus a handshake error earns the one-shot modern recovery
re-probe, and `connect!` now carries a `:protocol-version` atom on stdio
conns too. Tests: the detection table and the `^:slow` modern subprocess
run. The eight-script extension suite stays green on the legacy fallback,
and a manual smoke connects the lib client to `fake-mcp-server.bb --era
modern` (9 tools, `_meta`-guarded tools/call).

### 3.4. Streamable HTTP modern path

`transport.http`:

- `Mcp-Method` on every modern POST; `Mcp-Name` for `tools/call`
  (`params.name`), `resources/read` (`params.uri`), `prompts/get`
  (`params.name`); `encode-header-value` implements the spec's Base64
  sentinel (`=?base64?…?=`, including a plain value that matches the
  sentinel).
- The probe carries `MCP-Protocol-Version` too: seed
  `@(:protocol-version conn)` with the probe version (a cached hint's
  revision, else `protocol/modern-protocol-version`) before
  `server/discover`, clear it when detection falls back to `initialize!`
  (a legacy initialize POST must not carry a modern version), and reseed
  with the negotiated revision on a `-32022` retry.
- After detection, every modern POST takes `MCP-Protocol-Version` from
  `@(:protocol-version conn)` at the negotiated revision. Modern conns
  never capture, echo or DELETE `Mcp-Session-Id`: the capture in
  `http/request!` (today from any response) becomes era-aware, and
  `terminate-http-session!` stays a no-op.
- `parse-http-response`'s non-2xx path parses a JSON-RPC error body into the
  thrown ex-info's ex-data (`:status :code :message :data`) instead of a
  text-only message — the `400`-body inspection the HTTP era detection
  needs, and generally more useful errors.
- `transport/cancel!` skips `notifications/cancelled` for modern HTTP conns:
  closing the response stream is the cancellation signal there, and the
  modern core defines no client→server notification over HTTP (stdio keeps
  it).
- Auth is untouched: the probe goes through the same
  `:auth-headers`/`:on-401` seam as every POST.

`client/detect-http-era!`: attempt the modern `server/discover` POST (with
the seeded version header, above).

- result → `negotiate-version` over `:supportedVersions` (modern continues,
  legacy falls back to `initialize!`, nil errors);
- `400` whose body is a recognized modern error → modern: on `-32022`
  negotiate over `data.supported` (retry the probe with the negotiated
  modern revision, or the legacy fallback — nil errors), on `-32020`/`-32021`
  surface the error;
- anything else (empty/non-modern body, `404`/`405`, non-modern JSON-RPC
  error, other status) → legacy → `initialize!`;
- `401`/`403` after the auth retry abort detection with the auth error —
  authentication is not an era signal.

`:http-transport :sse` is excluded — it keeps building a legacy conn and
initializing.

Tests: `test_transport_http.clj` header table (method always, name per
method, base64 encoding, no session echo or capture, probe version header),
`400`-body ex-data, socket-free via `:request-fn` injection;
`test_client.clj` detection outcomes (including 401 abort). Scripts:
`validate-protocol.bb` HTTP modern happy path (fake validates the
headers), `-32022` negotiation, legacy fallback; the 3.1 modern-rejection
baseline unchanged.

### 3.5. `subscriptions/listen` lifecycle

- Lib: `client/listen! [conn filter & [{:keys [on-restored]}]]` opens one
  long-lived subscription per conn and routes every notification to the
  conn's `:on-notification` (the adapter's existing
  `handle-list-changed!` resync then works unchanged). `filter` is the
  spec filter: `:toolsListChanged`, `:promptsListChanged`,
  `:resourcesListChanged`, `:resourceSubscriptions [uri ...]`.
  - stdio: the listen request's result arrives only at graceful close, so
    it must neither hold the per-conn `:req-lock` (that would block tool
    calls for the session's life) nor time out. `jrpc/request!` today does
    `(or timeout-ms default-timeout-ms)` and drops the pending entry on
    timeout, and its pending map is private — a transport-local slot
    cannot register a correlation, so the small additive jsonrpc change is
    mandatory, not a time-boxed option: interpret a `:timeout-ms` sentinel
    as "no deadline" (`(deref p)`), with a jsonrpc test. `stdio/listen!`
    runs it on a spawned thread and bypasses `client/request!` (which
    takes the lock). Notifications demux on
    `_meta.io.modelcontextprotocol/subscriptionId` (one subscription makes
    this trivial).
  - streamable HTTP: `http/listen!` POSTs (with `:timeout nil`, which
    disables curl's `--max-time`) and keeps the SSE response body open on
    a background reader, dispatching notifications; the stream ends on the
    graceful-close result or a drop. SSE comment lines (`:` keepalives)
    yield no event in `kmet.libs.sse` and must not end the reader.
  - `close!` stops the listener first (abort the HTTP stream; stdio sends
    `notifications/cancelled`, which is the stdio cancellation mechanism),
    and the idle reaper must not reap a conn with a live listener —
    `touch!` on every line read, keepalives included (a comment yields no
    frame), or an explicit `:listening?` exemption.
  - stream end while the caller has not stopped the listener re-establishes
    it with a bounded backoff (immediate, then 1s/5s/15s) rather than
    waiting for the next connect. After a gap, invoke `:on-restored` once
    (the adapter passes a callback that spawns the same generation-gated
    `refresh-server-catalog!` the list_changed handler uses), and after
    the budget mark the conn closed so `ensure-connected!` rebuilds it.
    `close!` sets the stop flag before aborting so the loop exits.
  - the ack (`notifications/subscriptions/acknowledged`) reaches
    `:on-notification` like any other; the client checks it is the first
    frame and (via `kmet.debug` in the adapter) notes requested types the
    server dropped from the filter.
- Adapter: after a modern connect, `connect-with-auth` starts one
  subscription from the advertised `listChanged` capabilities
  (`:tools`/`:prompts`/`:resources`) and passes the `:on-restored` resync
  hook; `resourceSubscriptions` stays empty (kmet does not watch
  individual resources). Legacy conns keep receiving the old
  `notifications/*/list_changed` messages as today. Teardown
  (`disconnect-server!`, session shutdown) closes the listener through
  `client/close!`.
- Non-goals here: multiple concurrent subscriptions, MRTR
  elicitation/sampling/roots (3.2 refuses `inputRequests`),
  `resources/subscribe` emulation (removed for modern; unused today), and
  stream replay (resumability was removed).
- Tests: `test_transport_http.clj` listen frames (ack first, notification,
  graceful close, drop → re-listen → `:on-restored`, abort, keepalive
  ignored), `test_transport_stdio.clj` subscription-id routing;
  `validate-protocol.bb` end to end: the modern fake emits
  `tools/list_changed` on the listen stream and the catalog resyncs. The
  jsonrpc no-deadline change gets its own test;
  `extensions/lsp-adapter/scripts/validate.bb` is re-run.

### 3.6. `x-mcp-header` mirroring + invalid-definition filtering

`x-mcp-header` is a MUST for streamable-HTTP clients (SEP-2243), so it lands
before the era cache:

- Pure helpers in `transport.http`: `x-mcp-param-headers [input-schema
  arguments]` walks the `properties`-only chain, converts
  string/integer/boolean per the spec, omits null/absent parameters, and
  Base64-encodes unsafe values; `valid-x-mcp-header?` enforces the token
  syntax, uniqueness, primitive type and static reachability.
- `client/request!` opts gain `:http-headers`, threaded to the HTTP
  transport (stdio/SSE ignore it). That changes the `request!` contract
  every transport implements: update `kmet.libs.mcp.transport`'s docstring
  and the three implementations in the same landing. The adapter's
  tool-call path needs the resolved tool record's `:inputSchema` — verify
  it survives into the proxy's record, and extend the record if not — and
  passes `:http-headers (http/x-mcp-param-headers input-schema
  arguments)`.
- A tool definition with an invalid `x-mcp-header` is excluded from
  `tools/list` for streamable-HTTP conns (the lib filters in
  `list-all-tools`; the SHOULD-log is dropped there because `kmet.libs.*`
  may not require `kmet.debug`).
- Tests: pure derivation table (plain, non-ASCII, whitespace, sentinel
  round-trip, nested properties, null/absent), invalid definitions dropped;
  `validate-protocol.bb` calls an annotated tool through the HTTP fake and
  asserts the `Mcp-Param-*` header arrived. If the adapter cannot supply
  the schema at some call site, the degradation is "no custom headers"
  (a conforming server then answers `-32020` for that annotated tool) —
  keep schema lookup beside the call.

### 3.7. Era caching in the metadata cache

- `metadata.clj`: the entry gains `:protocol-era` (`{:era :modern|:legacy
  :version rev}`). `update-entry!` stores it; `server-entry` returns it;
  no version bump — an entry without the key means "unknown, probe". Both
  callers change in the same landing: `core.clj`'s
  `refresh-after-connect!` and `scripts/validate-config.bb`; the ns
  docstring's cache shape is updated too.
- `core.clj`: `connect-with-auth` passes the cached
  `:protocol-era` as a connect opt (like the auth fns, it is adapter policy
  mapped onto the lib's option map); `refresh-after-connect!` stores the
  era the connect actually used. A hinted modern connect seeds
  `@(:protocol-version conn)` with the cached revision before `discover!`
  (3.4). The fingerprint already covers `:command`/`:args`/`:url`, so a
  different server binary/origin is a different entry — the era is per
  process/origin by construction.
- Lib: `connect!` honors `:protocol-era` as a hint; when the era-specific
  establish fails era-inconsistently it closes, discards the hint and runs
  the shared full-probe-once helper ("re-probe on any failure instead of
  trusting the cached assumption"): hinted modern but `server/discover`
  answers `-32601`/a legacy-shaped error ⇒ re-probe; hinted legacy but
  `initialize` answers a modern error/`-32022` ⇒ re-probe. The result
  always carries the era actually used; a failed connect writes nothing,
  so the next attempt probes again.
- Optional: `establish!` can carry the `ttlMs`/`cacheScope` of the list and
  discover results into the entry and cap the 7-day freshness at `ttlMs`
  in `server-entry`. It is a hint, not a conformance requirement — do it
  only after 3.4/3.5 are green and drop it if it spreads.
- Tests: `validate-protocol.bb` seeds an entry with the wrong era and
  asserts the re-probe; `validate-config.bb` keeps covering the
  fingerprint.

### 3.8. Facade deletion, docs, gates

- Delete `extensions/mcp-adapter/src/kmet/extensions/mcp_adapter/client.clj`
  (Phase 1.2's pure re-export); point `core.clj`, `tool_proxy.clj`,
  `auth.clj`, `prompts.clj` at `kmet.libs.mcp.client` /
  `kmet.libs.mcp.protocol`.
- `scripts/validate-client.bb` is the only script requiring the facade —
  switch it to `kmet.libs.mcp.client` (scripts run with `-cp ../../src:src`,
  so the lib is present). Check the other scripts' transitive use in the
  same pass.
- `extensions/mcp-adapter/README.md`: replace the "stateless `2026-07-28` …
  not reachable from here" paragraph with the era model (modern spoken,
  legacy kept, detection and its failure modes).
- Gates: `bb test`, `jolt test` (lib additions), `bb lint`,
  `bb format-check`, `bb check`, `bb check-bundled-extensions`,
  `bb test-ext`, `validate-all.bb` (eight scripts), plus
  `extensions/lsp-adapter/scripts/validate.bb` if 3.5 touched jsonrpc.

### Phase 3 risks

- **No pi reference** (above): the spec plus the 3.1 fakes are the only
  oracle; a wire detail the fakes encode wrong would pass our suite while
  failing real servers. Keep the fakes strict (reject missing `_meta`,
  validate headers) so conformance drift shows up locally.
- **stdio `listen` needs a pending request that neither times out nor
  blocks serialized stdio requests** — the jsonrpc no-deadline sentinel is
  mandatory (its pending map is private, so there is no transport-local
  fallback); it is additive and lsp-adapter is unaffected.
- **HTTP `listen` is a long-lived stream** over the curl-backed
  `kmet.libs.http` (`:timeout nil` disables `--max-time`): the idle reaper
  (per-request `last-used`) would reap it — touch on every line read —
  auth can only retry once at open, and a drop is not resumable: the
  bounded re-listen plus `:on-restored` resync (3.5) is the recovery,
  falling back to a full reconnect when the budget runs out.
- **Probe latency vs cold starts**: the short `probe-timeout-ms` keeps
  silent legacy servers fast but can misclassify a still-booting modern
  server; the timeout-triggered recovery re-probe (3.3) is what makes that
  acceptable.
- **Detection false negatives are accepted per spec**: a timeout means
  legacy, and if the hint was wrong the re-probe (3.7) is the recovery.
  Never key the fallback on one code.
- **Baseline updates are deliberate**: the 3.1 modern-rejection check must
  stay green through 3.4 unless a fake's response shape is consciously
  changed; record any such change in the landing.
- **ttlMs/cacheScope is optional** — don't let it grow 3.7.

## Phase 4 — optional hygiene

`core.clj` lifecycle → `server.clj` / `direct_tools.clj` / `commands.clj`;
`tool_proxy.clj` → `search.clj` + `status.clj`; drop
`kmet.libs.mcp.transport.sse` once the deprecation window closes. Lands
after Phase 3 — hygiene must not block or precede the protocol work.

---

## Non-goals

- No `config`, `metadata`, `output_guard`, or `tool_proxy` in `kmet.libs`.
- No server-side MCP in `kmet.libs.mcp` (client use only).
- Don't start Phase 4 hygiene before Phase 3 lands unless something there
  actually blocks it.
- Don't fix the collision-display divergence anywhere but 0.2.
- Don't invest in `transport.sse` beyond the migration (deprecated).

## Risks

- `kmet.libs.*` is injected by reference into every extension context and
  required once per host start: keep the lib free of UI/tool/config deps (it is
  today).
- The `test-self-contained` recursion fix is part of Phase 1 — without it the
  new `mcp/transport/` files escape the guard.
- The lib API becomes public for extensions; keep internals private and
  document the surface in the ns docstrings from the start.
- The jsonrpc change is shared with lsp-adapter — additive only, with its own
  tests and its validation script.
- Phase 0 changes registered/display names in collision cases only; the scripts
  that assert names (`e2e.bb`, `validate-panel.bb`, `validate-script.bb`) must
  be green at each step.
- `assign-names` changes names for collision groups on every catalog refresh —
  correct, but `script-tool-records` names can change mid-session when a server
  advertises a new colliding tool. Accepted; if it ever bites, pin names per
  server at first sight instead of recomputing.

## Ordered checklist

- [x] baseline: run the six scripts, add `scripts/validate-all.bb`
- [x] 0.1 `names.clj` primitives + callers switched + originals deleted (move only)
- [x] 0.2 `assign-names` + `:tool-names` + proxy `display-name` (the fix)
- [x] 0.3 `validate-names.bb` + six extension scripts + lint/format
- [x] 1.0 jsonrpc `:id` in ex-data + tests; lsp validation green
- [x] 1.1 lib files: protocol → transport → stdio/http/sse → client
- [x] 1.2 extension facade (pure re-export)
- [x] 1.3 whitelist + self-contained guard recursion + test registration
- [x] 1.4 lib tests (24 tests: protocol, client, http/sse/stdio transports)
- [x] 1.5 full gates + extension scripts + lsp validation
- [x] 1.6 stdio on `kmet.libs.jsonrpc` (transport-owned ids, `:conn-ref` hand-off)
- [x] 2.1 auth policy in the lib (challenge/resource/scope + tests)
- [x] 2.2 store: file/keyring/auto backends + logout in the lib
- [x] 2.3 discovery + token lifecycle + `make-auth-fns` in the lib
- [x] 2.4 flow split: lib step functions, host interaction stays in the extension
- [x] 2.5 2026 hardening: issuer-keyed store + migration (SEP-2352), RFC 9207 `iss`, SEP-837 `application_type`
- [x] 2.6 gates: `validate-oauth.bb` + `validate-client.bb` (401 retry), `bb test`, jolt, lint/format, `check-bundled-extensions` (test-ext clean apart from the pre-existing lsp-adapter-dependent overlay smoke)
- [x] 3.1 fakes modern mode + `validate-protocol.bb` harness (baseline)
- [x] 3.2 era core: meta helpers, conn `:era`, `_meta` decoration, MRTR (`requestState`-only retry, `inputRequests` refusal) (behavior-neutral)
- [x] 3.3 stdio: `server/discover` probe + modern establish + legacy fallback
- [ ] 3.4 streamable HTTP: routing headers, 400-body detection, `-32022` negotiation, no session
- [ ] 3.5 `subscriptions/listen`: lib listen + HTTP long-lived stream + adapter wiring
- [ ] 3.6 `x-mcp-header` mirroring + invalid-definition filtering
- [ ] 3.7 `:protocol-era` in the metadata cache + re-probe on failure
- [ ] 3.8 facade deletion + README + full gates
- [ ] 4 optional hygiene (after 3)
