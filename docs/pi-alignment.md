# kmet ↔ pi alignment: feature gap analysis

Reference: `~/src/cvstree/pi/` at **v1.3.0** — `packages/coding-agent` (the app),
`packages/ai`, `packages/tui`, `packages/agent` (pi-agent-core), plus the newer
frontier packages `packages/chord`, `packages/durable`, `packages/env`,
`packages/codemode`, `packages/mcp`, `packages/telemetry`, `packages/evals`.

> The paths and namespace names below are as of the landing that wrote them:
> kmet1's module (`modules/agent`, `kmet.agent.*`) has since been retired, and
> the trees it names live under `app/` and `ui/` with `kmet.app.*` /
> `kmet.ui.*` names. [`architecture.md`](architecture.md) is the current map;
> this document is the gap analysis, not a path index.

Kmet side: `modules/agent` (`kmet.agent.*`), `modules/ai` (`kmet.ai.*`),
`modules/tui`, `modules/tk`, `modules/libs`, `modules/loader`, `modules/reakt`,
`modules/hikap`.

Goal: enumerate what pi exposes that kmet does not, so a "full alignment" pass has a
concrete work list. "Aligned" means the behavior exists in kmet with equivalent
semantics (EDN/Clojure adaptation); gaps are pi features with no kmet counterpart.

Revision note: this pass refreshes the analysis against pi v1.3.0 (the previous
snapshot was ~0.87). It records **no new scope decisions**: the frontier packages
that appeared since the last audit are analysed in
[pi since 0.87](#pi-since-087-the-new-package-frontier-analysis-only) with
`decision: open`, and the historical decisions below still stand except where
marked *reopened*.

## Summary

kmet is a close port of pi's *stable* interactive TUI + agent core: the
component/TUI framework, agent loop, tools, provider/auth subsystem (kmet.ai),
sessions (EDNL), compaction, skills, prompt templates, themes, and the
extension API (hooks, events, commands, tools, flags, renderers, agent
control, tool hooks) are functionally aligned. The 0.87.0 session-context wave
is fully landed — see §7 — and since the last revision kmet also landed
`--mode rpc` (`docs/rpc.md` + `kmet.agent.modes.rpc-client`), the builtin
`run_code` sandbox, `kmet.libs.mcp` + the `mcp-adapter` extension, the loader
module, and the Tk GUI.

The remaining stable-line gaps cluster in the CLI surface (§1), rendering
(mermaid/latex/search/images and the terminal-override settings — §2), the
settings surface (§4), and custom-provider streaming (pi's
`registerNativeProvider` and virtual models — §5). Separately, pi 1.3.0 ships
a frontier of packages kmet has no counterpart for — chord, durable, env,
codemode, mcp, telemetry, evals — analysed without decisions below.

## Historical decisions (recorded before the pi 1.3.0 audit)

These were deliberate scope decisions. They still stand unless marked
*reopened*; the frontier items below are undecided and do not change them.

| Area | pi feature | kmet decision |
|---|---|---|
| Additional run modes | `--mode json`, `--export`, `pi server` / `pi client` / `pi rpc`, `packages/server` / `packages/client` / `packages/protocol` (CBOR — removed in pi 1.3.0), `docs/sdk.md` | *Reopened, no decision recorded* — kmet now ships `--mode rpc` (EDN stdio, `docs/rpc.md`); interactive + print + RPC are the shipped modes. JSON mode, CLI `--export`, the SDK, and any remote/multi-client direction are undecided |
| Project trust | `core/project-trust.ts`, `core/trust-manager.ts`, `/trust`, `trust-selector.ts`, `--approve`/`--no-approve`, `defaultProjectTrust`, `trust.json` | Not ported — project settings/extensions load unconditionally (`-a`/`-na` parse as no-ops on the package commands) |
| Package sources: npm/git | `npm:`/`git:` installs (npm registry + git clone machinery in `core/package-manager.ts`), `pi update`/self-update, temporary `--extension` installs, per-package autoload deltas over npm/git identities | **Git ported, npm not.** kmet installs git repositories as managed checkouts (`kmet install git:github.com/user/repo[@ref]`, `kmet.app.git-packages`, pi's parseGitUrl/clone/updateGit) and `kmet update` updates them (all or one source; no self-update, model refresh, `--force` or `--extension`). npm sources and temporary `--extension` installs are not ported; other remote sources error with "Unsupported package source". **kmet extension beyond pi:** `//subdir` names a package directory inside a repository (`git:host/repo//packages/foo`) — several packages may share one managed checkout, pi's `GitSource` has no subdirectory. The remainder of the package manager IS ported (next section) |
| Session interop / JSONL | pi JSONL session files, `/export` `.jsonl`, `--export` from JSONL | EDNL-only by design (see [Session storage and alignment](#session-storage-and-alignment)) — `/export` writes standalone HTML |

## pi since 0.87: the new package frontier (analysis only)

pi 1.3.0 ships packages that have no 0.87-era counterpart in this document.
They are recorded here for a future decision; **nothing below is a kmet scope
decision**.

| Package | What it is (pi 1.3.0) | kmet counterpart / status | Decision |
|---|---|---|---|
| `packages/chord` | Typed host runtime for hot-reloadable plugins: objects with addresses and principals, TypeBox contracts, replicated JSON state (snapshot + deltas), hot reload, and RPC over JSON links (memory / IPC / WebSocket), plus child host processes, a transactional delta tracker, and bounded output streams | None. `kmet.reakt` is an in-process reactive store and `kmet.hikap` a component runtime; the extension runtime + event bus cover the single-process part | open |
| `packages/durable` (experimental) | Commit-before-visible harness: conversations, entries, tasks, submissions, and documents are committed atomically before anything is observable; interrupted attempts and tool calls recover on reopen; per-conversation agents, code mode, subagents / child tasks, a task graph, and an `ExecutionEnv` boundary; storage adapters (memory, SQLite) | `kmet.app.session.runtime` + `session.core`: one in-memory authoritative engine, EDNL entries appended as they are created, no open-attempt status or recovery, a single run per engine, no tasks/subagents | open |
| `packages/env` | Remote execution: a Rust daemon over a framed stdin/stdout protocol plus `RemoteExecutionEnv` next to `NodeExecutionEnv`; SSH with host-key verification, sha-pinned daemon deployment; Linux/macOS/Termux/Windows | `kmet.app.tools.bash-executor` and the tools' direct `babashka.fs`/`babashka.process` use — local only | open |
| `packages/codemode` | QuickJS sandbox where the only capability is tool calls; scripts get `tools`, `text`, `image`, `exit`, `store`, `load`, `models.classify()`, `models.generateImages()`. coding-agent 1.3 ships it as the built-in `codemode` extension with `tool_search`, `codemode.mode`, and `codemode.inlineBudget` | builtin `run_code`: SCI sandbox, full babashka/jolt surface, `emit`-only text plus `emit-image`, submission-order inner calls, extension-contributed tool sources (design: [`architecture.md`](architecture.md) § Tools and the script sandbox). Missing: persistent `store`/`load`, the `models` facade, a `tool_search` equivalent | open |
| `packages/mcp` | Standalone MCP client (stdio + Streamable HTTP). coding-agent 1.3 ships built-in MCP support: exposure control (`direct` / `model-only` / `codemode` / `deferred` / `hidden`), resource tools, OAuth, `/mcp`, `--no-mcp`, and the `registerMcpServer` / `getMcpServers` extension API | `kmet.libs.mcp` + the `mcp-adapter` extension: one lazy `mcp` proxy tool, EDN config, stdio + Streamable HTTP/SSE, OAuth, `/mcp`. The exposure model and the extension registration API have no kmet equivalent | open |
| `packages/telemetry` | Vendor-neutral telemetry contracts: callback-based context/span, NOOP and in-memory reference adapters, conformance tests, typed schemas; no global state or exporter | `kmet.app.debug` logging plus the measured notes in `perf.md`; no spans or contracts | open |
| `packages/evals` | Behavioral evals (`vitest-evals`): documentation-lift and host evals, docker arms, lift reporting | `clojure.test` suites (fast / `^:slow`) and `scripts/repro_*`; no behavioral eval harness | open |
| `packages/agent` | pi-agent-core: standalone agent runtime (see §6) | `kmet.app.loop` covers the coding-agent equivalent | no port needed (unchanged) |

Two earlier trees are gone as of 1.3.0: `packages/client`, `packages/server`,
and `packages/protocol` (the CBOR routed-envelope stack) no longer exist, and
the experimental client/server service slices under
`coding-agent/src/experimental/` were removed with them. pi's RPC stays in
coding-agent as `--mode rpc` (JSONL), and the remote-link story now lives in
chord. Do not chase those trees when re-checking this document.

**Subagents (P7, deferred).** The durable row's subagent gap has a kmet
sketch: a subagent tool runs on a child ctx (`kmet.agent.app-runtime/child-ctx`)
carrying its own loop stage map, with its own model, tool subset and hooks in
the child's state, and its registrations isolate by ctx under that
node. Missing: the child-state builder (agent state from the child's model,
stages, tool subset and hooks), a child session setup, and the tool-subset
rule (the child state's `:default-tools`/active set — the tool registry reads
the app root, so a child does not isolate it). This is the record the retired
cells plan carried as P7.

## Session storage and alignment

`kmet.app.session.core` follows pi v3 `SessionManager` semantics in EDN/Clojure,
rather than importing pi's file format. It is intentionally EDNL-only: there
is no JSONL codec, import/export, or reading and writing of pi session files,
and no v1→v2/v2→v3 migration machinery. The v4 harness concepts (lanes, a
shared sequence/mutation log, records, writer claims, and session search) are
out of scope. The EDNL header's `:version` is informational; legacy
headerless files still load with `:header nil`.

### Storage and mutation model

- Each session is line-delimited EDN. The first line is a header map carrying
  `:type`, `:version`, `:id`, `:created-at`, `:cwd`, and optional
  `:parent-session`; later entries carry `:id`, `:parent-id`, `:role`,
  `:content`, `:timestamp`, and role-specific fields.
- Files live under `~/.kmet/sessions/<--cwd-->/` with timestamped `.ednl`
  names. Creation is lazy: no file is written until the first assistant
  message.
- Branching changes the in-memory leaf; subsequent appends create a new
  branch while the existing entries remain in the file and tree. Fork and
  clone retain entry ids, record `:parent-session`, and re-chain labels.
- A lock serializes mutations to prevent concurrent appends from creating
  orphaned siblings. Entry ids combine a time-ordered prefix with a
  collision-checked random component.
- Loading streams files in bounded chunks, skips malformed non-tail lines,
  repairs a torn final line, and publishes through a temporary file and
  rename. Session-info listing is likewise streamed, bounded, concurrent,
  and reports progress for the resume overlay.

### Context and extension state

- Context is built along the active root-to-leaf path. Model and thinking
  settings are derived from persisted change entries; compaction appends a
  summary entry while retaining summarized history, and context projects the
  latest summary plus the retained tail. Branch summaries become context
  messages on resume.
- `custom` entries hold extension state and never enter LLM context;
  `custom_message` entries project as `:custom`-role messages and use their
  display flag for TUI rendering. Labels are tree entries with latest-wins
  lookup. The extension API exposes append/get custom entries, custom
  messages, labels, and the live session.
- The optional pi query APIs (`findEntries`/`findEntriesOnBranch`) are not
  ported; kmet exposes branch/tree queries instead.

### Session commands and export

`/session`, `/export`, `/share`, and `/copy` are implemented. Export is a
standalone, escaped HTML document with session statistics; `/share` exports
through `gh gist`; `/copy` copies the last assistant text with platform
clipboard support and an OSC 52 fallback. JSONL export/import remains out of
scope by the EDNL-only decision.

## Ported: the resource resolution & package manager

pi `core/package-manager.ts` (the full resolve pipeline) +
`package-manager-cli.ts` + `cli/config-selector.ts`
→ `kmet.app.packages` + `kmet.app.packages-cli` + `kmet.agent.ui.tui.resource-config`
(+ the runner call sites in `core.clj`, `modes/interactive.clj`, `modes/print.clj`).

`PackageManager.resolve()` is fully ported: top-level settings resource
entries (`:extensions`/`:skills`/`:prompts`/`:themes`, plain paths plus
`!glob`/`+path`/`-path` patterns), the fixed auto-dir scans
(`~/.kmet/agent/<type>` + `.kmet/<type>`), and `:packages`, all into pi's
insertion order (project-local → user-local → project-auto → user-auto →
packages) with pi's `toResolvedPaths` finalization — a stable sort by
`resourcePrecedenceRank` (project-local 0, project-auto 1, user-local 2,
user-auto 3, package 4) then dedupe by canonical path, first wins. Loading
is one unified enabled-path pass per type (`load-skills!` /
`load-prompts!` / `load-themes!`). Extensions diverge: they are composition
rows, so only an `:ext/<id>` row loads one (`kmet.app.extensions.rows`); the
resolution above is discovery.
Sources are local **files** (load as one extension) or **directories**
(one `extension.edn` extension, or conventional `extensions/` `skills/`
`prompts/` `themes/` subdirectories, or a bare dir as an extension container).
`kmet install <source> [-l]` validates and records the source in the
settings `:packages` (stored relative to the scope base dir, pi
`normalizePackageSourceForSettings`); `remove`/`uninstall` matches by
resolved identity (pi `packageSourcesMatch`); `list` shows user + project
sections with installed paths; `config` opens the resource TUI — groups per
package (scope in the label), per-type subgroups, search, space/enter
toggles, Tab global/project scope switching with the inherit/load/unload
tri-state and dimmed inherited rows, escape/ctrl+c close.

Object entries are fully supported with pi's semantics:
`{:source ... :extensions [...] :skills [...] :prompts [...] :themes [...]}`
(plain globs include, `!glob` excludes, `+path`/`-path` exact force
include/exclude, `[]` disables all of a type, absent key loads all) and
project entries with `:autoload false` apply as deltas over the global entry
of the same identity. Top-level entries split into plain paths (resolved
and expanded — a file loads itself, a directory its discovered items) and
pattern entries (filter the collected files; auto-dir items are adjusted by
`!`/`+`/`-` overrides only, pi `isEnabledByOverrides`). Filtered-out items
stay in the resolution with `:enabled false` so the config list can
re-enable them. Skills, prompts and themes load at startup, in print mode and
on `/reload`; extensions load when a composition row mounts (print and the
other profiles compose the same ambient layers), and `/reload` reloads the
mounted rows.

Kmet adaptations (deliberate deviations):

- kmet's single-extension dir unit is a directory containing `extension.edn`
  (pi: `package.json` `pi` manifest or an implicit `extension.ts`); a bare
  directory without resource subdirs loads as an *extension container*
  (top-level `.clj`/`.jar`/`.zip`/`extension.edn` items) rather than one
  extension, and its items are individually filterable.
- No pi `package.json` manifest — packages use the convention subdirs only,
  and `extension.edn` dirs ignore per-type filters (the extension owns its
  bundle, pi file-source behavior); the composition decides whether the
  extension loads, so the config screen toggles it into the patch layer.
- Pattern matching implements minimatch's common subset (`*`, `?`, `**`,
  `[...]`; no extglobs/braces) against the same match forms as pi (relative
  path, file name, absolute path; skills also match their parent folder).
- `config` lists all layers (pi buildGroups: packages first, then
  top-level groups, user before project). Extension items toggle the
  composition layer (`~/.kmet/agent/patch.edn` / `.kmet/patch.edn`), not
  settings; skills/prompts/themes keep the settings toggles (package entry
  and top-level resource array, pi toggleTopLevelResource /
  setProjectTopLevelOverride). Project mode is available whenever `.kmet/` exists or `-l` was
  passed (no trust gate), and the screen starts in project-local scope when
  `.kmet/` exists — project settings and auto dirs load unconditionally, so
  project-defined resources show enabled by default (pi starts global
  because its project mode requires trust).
- No configurable resource dirs: the auto roots are fixed
  (`~/.kmet/agent/<type>` + `.kmet/<type>`, pi `join(agentDir, type)` +
  `join(cwd, CONFIG_DIR_NAME, type)`); the retired `:*-dir` settings keys
  warn at load and point at the top-level entries. The agent dir honors
  `KMET_CODING_AGENT_DIR` (pi `ENV_AGENT_DIR`).
- Remote sources other than git (npm) in settings warn and are skipped at
  load instead of being auto-installed; a git entry resolves to its managed
  checkout when installed and warns when not (run `kmet install`/`kmet update`).
- `pi update`'s self-update, model-catalog refresh, `--force` and
  `--extension` are not ported; the git-package half is `kmet update` and
  `kmet --generate-models` covers the model-catalog piece.

## Gap analysis

### 1. CLI surface

**Subcommands** — pi's package commands are ported for local and git
sources (see “Ported: the resource resolution & package manager”):
`kmet install/remove/uninstall/update/list/config` dispatch from `core.clj`
to `kmet.app.packages-cli` (pi `package-manager-cli.ts`) before generic
arg parsing. Still missing:

| pi command | ref |
|---|---|
| `pi auth check` / `pi auth print-api-key` / `pi auth print-bearer-token` | `cli/auth-check.ts`, `cli/auth-command.ts`, `cli/credential-print.ts` |
| `pi update --self/--models/--all/--force/--extension` | `package-manager-cli.ts` — self-update, model refresh and temporary installs are not ported; the git-package half is `kmet update` (bare = all, one source, `--extensions`; no-op `-a`/`-na`) |
| `pi mcp list` / `add` / `remove` / `login` (server management) | `docs/mcp.md` |
| `pi --export <session-file> [path]` | `main.ts` (`--export`) |

**Flags** — kmet covers `--mode text|edn|rpc`, `-p/--print`, `-c/--continue`,
`-r/--resume`, `--session <id|path>`, `--session-dir`, `--model`, `--provider`,
`--models`, `--list-models`, `--system-prompt`, `--append-system-prompt`,
`-t/--thinking` (pi 1.3 moved `-t` to `--tools` — see below), `-d/--debug`,
`-h/--help`, `-v/--version`, `--generate-models`, `@files`, and positional
messages (`modules/agent/src/kmet/agent/core.clj` vs pi `cli/args.ts`).
Missing, by pi's cli.md groups:

| flag | purpose |
|---|---|
| `--api-key` | API key for a model specified via `--model`/`--models` |
| `--session-id` | open an exact project session id (or create it) |
| `--fork <path|id>` | fork an existing session from the CLI (kmet has `/fork` in the TUI) |
| `--no-session` | in-memory session that is not persisted |
| `-n/--name` | session display name at startup (kmet has `/name`) |
| `-t/--tools`, `-xt/--exclude-tools`, `-nbt/--no-builtin-tools`, `-nt/--no-tools` | tool selection for one run (kmet has the `:default-tools` setting and the in-TUI `/tools` selector only) |
| `-e/--extension`, `-ne/--no-extensions` | load extensions / disable discovered ones for one run |
| `--no-mcp` | disable MCP for one run |
| `--skill`, `-ns/--no-skills` | load skills / disable discovered ones for one run |
| `--prompt-template`, `-np/--no-prompt-templates` | load prompt templates / disable discovered ones for one run |
| `--theme`, `--use-theme`, `--no-themes` | load / select / disable themes for one run |
| `-nc/--no-context-files` | disable `AGENTS.md`/`CLAUDE.md` discovery |
| `--mode json` | JSONL event stream (kmet print mode has `--mode text|edn`) |
| `--export` | export a session file to HTML from the CLI |
| `--tui-mode` | `regular` vs `fullscreen` TUI (pi 1.3 defaults to fullscreen) — **postponed indefinitely** (the inline-scrollback model is deliberate; `tui.md` §15.1) |
| `--verbose` | verbose startup information (kmet has `-d/--debug` logging) |
| `-a/--approve`, `-na/--no-approve` | project trust (historical decision); kmet parses these as no-ops on the package commands |
| `--offline` (`PI_OFFLINE`) | skip automatic network activity at startup |

### 2. Interactive features & rendering

| Feature | pi ref | kmet status |
|---|---|---|
| **Mermaid diagram rendering** | `modes/interactive/components/mermaid.ts` + `markdown.mermaid` setting (`off`/`final`/`streaming`) | **Postponed indefinitely** (2026-09-10; rationale in `modules/tui/src/kmet/tui/tui.md` §15.1) |
| **LaTeX rendering** (`$…$`, `$$…$$`) | `packages/tui/src/latex.ts`, wired in `tui/src/components/markdown.ts` | **Postponed indefinitely** (same) |
| **Alt-screen search** (search overlay over the transcript) | `packages/tui/src/alt-screen-search.ts`, `tui-alt-screen.ts` | **Postponed indefinitely** (same — needs an alt-screen mode) |
| **Images in chat** | `terminal.showImages`, `terminal.imageWidthCells`, `images.autoResize`, `images.blockImages` settings; `show-images-selector.ts` | **Partial** — the TUI half done (`tui.md` §10): `:terminal {:show-images :image-width-cells}` settings, terminal-support-gated `/settings` rows, and inline images (or the `imageFallback` text indicator when off/unsupported) in tool results and user/custom messages. `images.blockImages` done: `:images {:block-images}` setting + ungated `/settings` row, stripped per request in `kmet.app.loop.completion/call-llm` (pi: convertToLlmWithBlockImages — placeholder text, consecutive dedupe, stored context untouched). **Missing**: `images.autoResize` and the per-model `inputLimits.images.resize` catalog metadata (pi #9631: resize profiles in `models.json`, applied to attachments, `read`, and tool-result images). Both need a resizer backend decision — pi runs a Photon WASM resize pipeline and babashka has no ImageIO/AWT (see `kmet.app.tools.read`); the generated catalogs carry no `:input-limits` keys yet
| **Terminal protocol overrides** | `terminal.hyperlinks`, `terminal.images`, `terminal.trueColor` settings | Missing — kmet's `kmet.libs.terminal` auto-detects hyperlinks, inline-image protocol, and true color; no per-setting override |
| **Cache-miss notices** | `showCacheMissNotices` setting | Done — `:show-cache-miss-notices` setting, `session/detect-cache-miss` (pi detectMiss), notice at agent-end (≥ 20k tokens) |
| **Skill invocation presentation** | `components/skill-invocation-message.ts` | **Done** — `kmet.app.skill-block/parse` + `kmet.ui.tui.skill-message`: a `/skill:name` block renders as `[skill] name (ctrl+o to expand)` (collapsed) or the name + body as Markdown (expanded), with the trailing args as a normal user message below. Live and replay share the parse; the session still stores the expanded text |
| **Custom entry rendering** | `registerEntryRenderer` + `components/custom-entry.ts` | Done — `extensions/register-entry-renderer!` + live entry sink; rendered at replay and on append (pi registerEntryRenderer + CustomEntryComponent) |

### 3. Slash commands

kmet covers: settings, model, thinking, scoped-models, export (HTML), import, share, copy, name,
session, hotkeys, fork, clone, tree, login, logout, new, compact, resume, continue,
reload, quit, help, tools, theme — plus the mcp-adapter's `/mcp` and
`/skill:name` autocomplete. pi 1.3.0's built-in set adds `/bug`, `/changelog`,
`/llama`, and `/skill` (kmet has no `/skill` command; skills load through
`/skill:name` and `enable-skill-commands`). `/trust` stays out of scope with
project trust. pi's built-in MCP (`/mcp`) is feature-aligned through the
extension.

One flow difference: pi's manual compaction aborts the running operation first
(`compact()` → `await this.abort()`), so `/compact` mid-response stops the
response and compacts. kmet defers instead: a `/compact` (or an extension's
`ctx.compact`) that arrives while a turn is streaming is queued and starts
when the turn settles — its cancel is cooperative, and the manual path clears
the run signal so a stale Escape cannot abort the compaction, which makes
abort-then-compact unsafe without an idle wait. The deferred path reports
`Compaction scheduled — it will run when the current response finishes.`; the
RPC `compact` command answers `{:accepted true :scheduled true}`.

### 4. Settings

kmet (`config.clj`) covers: provider/model/thinking/theme/session-dir/
http-idle-timeout-ms/system-prompt/append-system-prompt/retry
(enabled/max-retries/base-delay-ms/max-agent-delay-ms)/enabled-models/hide-thinking-block/
auto-compact/show-cache-miss-notices/
tree-filter-mode/output-pad/editor-padding-x/autocomplete-max-visible/
show-hardware-cursor/enable-skill-commands/extensions/skills/prompts/themes dirs,
compaction thresholds + per-model token overrides (`:compact-model-overrides`
— pi `compaction.modelOverrides`, exact `"provider/modelId"` keys), branch
summarization (`:branch-summary` —
`:reserve-tokens`/`:skip-prompt`), terminal display (`:terminal` — show-images /
image-width-cells / clear-on-shrink), terminal progress
(`:show-terminal-progress`), provider image blocking (`:images` — block-images),
shell customization (`:shell-path` / `:shell-command-prefix` — applied to
every bash execution: tool, `!` commands, and factory-built tools),
built-in tool selection (`:default-tools` — pi `defaultTools`; plain names
replace the built-in set, `+name`/`-name` modify it, scopes layer and
`/reload` re-resolves it),
`.kmet/SYSTEM.md` + `APPEND_SYSTEM.md` discovery, `KMET_PROVIDER`/
`KMET_MODEL` env vars.

Missing (pi v1.3.0 `docs/settings.md`):

| setting | purpose |
|---|---|
| `modelThinkingLevels` | per-model startup thinking levels, keyed `provider/modelId` |
| `thinkingBudgets` | per-level thinking token budgets |
| `externalEditor` | command opened by the external-editor keybinding (kmet detects an editor; no setting) |
| `codemode.mode`, `codemode.inlineBudget` | the built-in `codemode` tool's presentation and description budget — no kmet counterpart (kmet's `run_code` has fixed budgets) |
| `terminal.hyperlinks`, `terminal.images`, `terminal.trueColor` | terminal capability overrides (kmet auto-detects only) |
| `images.autoResize` | image resize before sending (needs a resizer backend — babashka has no ImageIO/AWT; `images.blockImages` is done — see §2) |
| `markdown.codeBlockIndent`, `markdown.mermaid` | markdown rendering |
| `transport`, `websocketConnectTimeoutMs` | provider transport selection (`sse` / `websocket` / `websocket-cached` / `auto`) — kmet's `/settings` "HTTP transport" row is a different axis (`http-client`/`curl`) |
| `cacheWarming` | prompt cache warming (`off` / `streaming` / `idle`, pi #9668 / `core/cache-warmer.ts`) — no kmet counterpart |
| `httpProxy` | proxy URL applied as HTTP(S)_PROXY (kmet reads proxy env vars only — `libs/http.cljc`) |
| `warnings.anthropicExtraUsage` | Anthropic subscription extra-usage warning |
| `quietStartup` | disable verbose printing at startup |
| `doubleEscapeAction` | double-escape behavior: `tree` / `fork` / `none` |
| `collapseChangelog` | condensed changelog after updates — kmet has no changelog surface (pi also has `/changelog`) |
| `enableInstallTelemetry`, `enableAnalytics` | anonymous install/analytics pings (pi.dev infrastructure) |
| `tuiMode`, `fullscreenExitOutput`, `fullscreenScrollbar`, `fullscreenCopyOnSelect`, `fullscreenWheelScrollLines` | fullscreen TUI mode (pi 1.3 defaults to `fullscreen`) — **postponed indefinitely** with the mode |
| `sessionShareDestination` | default destination for `/share` |
| `npmCommand`, `defaultProjectTrust` | npm package lookup (n/a — local packages only) and project trust (historical decision) |

Done (previously missing): `terminal.clearOnShrink` (`:terminal :clear-on-shrink` setting +
row, live `tui-set-clear-on-shrink!`, env default preserved),
`terminal.showTerminalProgress` (row + live read; the indicator also clears
when the setting is disabled mid-turn and on `tui-stop`, like pi's `stop`),
`enableSkillCommands` (`:enable-skill-commands` + row, gates the
`/skill:name` autocomplete entries), the theme row's pi shape (a
`ThemeSubmenu`: single names + the Automatic `light/dark` mode with
light/dark pickers and live preview — enabled by `SettingItem.submenu`
support in `kmet.tui.components.settings-list`), and `branchSummary`
(`:branch-summary` — `:reserve-tokens` budgets the branch entries a branch
summary covers to the model's context window minus it, `:skip-prompt` skips
the "Summarize branch?" question and defaults to no summary).

Layer difference, not a gap: pi's `retry.provider.*` block
(`timeoutMs`/`maxRetries`/`maxRetryDelayMs`) configures retries *inside* the
provider request. kmet has no transport-level retry — the agent-level
`:retry` policy re-runs the whole assistant call with the same
classification (429/5xx/timeouts retryable, quota/billing terminal) and
bounded backoff, and `:http-total-timeout-ms` is the per-request deadline
pi's `timeoutMs` sets (`retry.provider.timeoutMs ?? httpIdleTimeoutMs`): `nil`
follows the idle deadline, while an explicit `0` disables the total deadline.
Like pi's dispatcher (`headersTimeout` = `httpIdleTimeoutMs`), the idle
deadline also bounds the wait for the response headers, so disabling the total
deadline never leaves a stalled response start (or the turn's spinner) hung.

### 5. Extension API

Aligned (`kmet.agent.extensions` + `kmet.app.events`): input + before-agent-start hooks,
provider registration (incl. OAuth), `ctx.models.*` facades, the front-end
binding (`extensions/install-front-end!`, over the raw `bind-extensions!`
swap — pi `bindExtensions` +
`ExtensionMode`: `ctx.mode`/`ctx.has-ui` per front end, a headless
`noOpUIContext`-equivalent port, and the `request-ui!`/`respond!` host-dialog
pair), the runtime capabilities (`bind-runtime-capabilities!` — pi
`bindCore`: `sendUserMessage`, `sendCustomMessage`, `setModel`/thinking,
`getActiveTools`/`setActiveTools` work in every front end including
headless), UI capabilities (notify/custom/widgets/footer/header/editor/
theme/status/working-indicator/terminal-input — no host select/confirm/
input: extensions build dialogs with `:custom`), session
append-entry/message/labels, entry + message renderers, markdown
transformers, shortcuts, CLI flags, and all agent/session/provider/
resources-discover events (see Appendix).

Full extension API surface (pi `core/extensions/types.ts`) — one remaining gap:

| API | purpose |
|---|---|
| `registerTool` | **Done** — `extensions/register-tool!` (Tool record or `make-tool` kwargs) |
| `registerCommand` / `getCommands` | **Done** — `extensions/register-command!` / `get-commands` (slash registry wrapper) |
| `registerShortcut` | **Done** — `extensions/register-shortcut!` dispatches through the ui registry into `modes/interactive.clj` `:register-shortcut!` (installed as a priority editor action checked before every builtin binding); no-op headless |
| `registerFlag`/`getFlag` | **Done** — unknown `--flag [value]` collected by `core.clj`, `extensions/register-flag!`/`get-flag` with :type coercion + defaults |
| `registerMessageRenderer` | **Done** — `extensions/register-message-renderer!` overrides the custom-message info box at replay + live |
| `registerMarkdownTransformer` | **Done** — `extensions/register-markdown-transformer!` (applied in registration order, idempotent, errors skipped) |
| `registerEntryRenderer` | **Done** — `extensions/register-entry-renderer!` (custom entry types, live + replay) |
| `sendUserMessage` (deliverAs steer/followUp) | **Done** — `extensions/send-user-message` dispatches through the runtime capabilities: idle starts a run (`prompt!`), streaming steers or queues a follow-up; works headless |
| `sendCustomMessage` (deliverAs steer/followUp/nextTurn) | **Done** — `extensions/send-message!` → the runtime capability's pi branch logic: `:next-turn` queues for the next prompt, a streaming send queues into the run (steer/follow-up) or defers to the end of the turn (`:pending-custom`), an idle send appends and optionally starts a turn. Without a runtime binding (bare library/test use) it appends immediately via the sinks |
| `setModel`, `getThinkingLevel`, `setThinkingLevel` | **Done** — runtime capabilities (auth-gated setModel with the position-preserving thinking switch, validated levels) |
| `exec` | **Done** — `extensions/exec` (babashka.process, string capture) |
| `getActiveTools`/`getAllTools`/`setActiveTools` | **Done** — runtime-bound (`get-active-tools`/`set-active-tools`): `:enabled-tools` (runtime selection, pi: setActiveTools) and `:default-tools` (pi: defaultTools) filter the agent state, applied to the wire `:tools` and the run_code sandbox surface; `get-active-tools` returns the effective active names, `get-all-tools` the array |
| `registerProvider` (`streamSimple`/`refreshModels` at 0.87; `registerNativeProvider` + virtual models at 1.3) | Partial — provider *config* registration is done (`extensions/register-provider!` / `models/register-provider-config!`); wire-layer custom provider streaming (`registerNativeProvider`), dynamic model refresh, and `registerVirtualModel`/`unregisterVirtualModel` are missing |
| `registerToolRenderer` | Missing — kmet tools carry their own renderer; there is no resolver chain like pi's `ToolRendererResolver` (load-order resolvers choosing how tool calls draw) |
| `registerMcpServer` / `unregisterMcpServer` / `getMcpServers` | Missing — the `mcp-adapter` extension owns its `mcp.edn` config; extensions cannot contribute servers |
| `onProviderStreamEvent` (`provider_stream_event`) | Missing — kmet's per-call provider hooks stop at `:on-context` / `:on-payload` / `:on-headers` / `:on-response` (`kmet.ai.llm/send-message`) |
| `getSettings`, `setSessionName`/`getSessionName` | Partial — no settings read facade and no session-name setter as extension API (kmet's `/name` command is the only path); label APIs (`set-label`) are done |
| `context_with_system` event | **Done** — runs after `:context` over the full transcript and its result is sent verbatim; a result that drops the leading system message is reported and honored (see §7). kmet also has the single `:context` event over the conversation, where the last non-nil `{:messages ...}` wins |
| Events: `resources_discover`, `session_before_switch`, `session_before_fork`, `session_before_compact`, `context`, `before_provider_request`, `before_provider_headers`, `after_provider_response` | **Done** — see Appendix (all ✅) |
| Events: `session_info_changed`, `thinking_level_select` | **Done** — emitted by `/name` and `set-thinking-level!` |
| Events: `mcp_servers_change`, `provider_stream_event`, `cache_warming_decision`, `ui_prompt_start`/`ui_prompt_end` | **Missing** — no extension MCP registration, no provider stream-event bridge, no cache warming, and no UI-prompt start/end pair (kmet's `request-ui!`/`respond!` answers dialogs without them) |
| Events: `tool_call`/`tool_result` (transform) | **Done** — `extensions/register-tool-call-hook!`/`register-tool-result-hook!` chained as the agent's `:before-tool-call`/`:after-tool-call` callbacks (block / arg-rewrite / result-rewrite). Note: the transform chain is wired into the agent callbacks rather than fired as event-bus events; the event bus still carries the execution lifecycle via `:tool-execution-start`/`:tool-execution-prepared`/`:tool-execution-update`/`:tool-execution-end` (see Appendix) |

### 6. Smaller gaps

- **Tree filter keybindings** — pi has standalone `app.tree.filter.*` bindings; kmet has
  the filter modes in `/tree` (`tree-filter-modes`, `cycle-filter!`) — **done**: the 7
  `app.tree.filter.*` ids + `app.tree.editLabel` are now registered keybindings the
  tree selector resolves through the keybindings manager (rebindable)
- **Widget keys through the keybindings manager** — pi's generic components
  (`select-list.ts`, `input.ts`, `settings-list.ts`, editor navigation) call
  `getKeybindings()` for `tui.select.*`/`tui.input.*`/`tui.editor.*` — **done**
  (`tui.md` §7): SelectList, Input, SettingsList and the editor resolve
  their ids through the manager (the editor prefers an injected one), the
  editor's `tui.editor.historyPrevious/Next` sit between interrupt/exit and the
  remaining app actions (pi: custom-editor), and the TUI definition table was
  aligned to pi's `TUI_KEYBINDINGS` (`historyPrevious/Next`,
  `jumpForward/Backward`, `yankPop`, `ctrl+left/right`, `ctrl+home/end`,
  `ctrl+pageUp/Down`), plus a fixed set of kmet deltas — folded alias chords
  (`ctrl+h`, `ctrl+i`, `ctrl+p`/`ctrl+n`, `ctrl+enter`/`alt+enter`,
  `ctrl+shift+]`, the tree's legacy `L`) and kmet-only ids (`tui.editor.redo`,
  `tui.editor.killLine`, `tui.select.first`/`last`,
  `tui.settings.cycleBackward`/`cycleForward`). The app panels followed:
  `app.thinking.save` (a pi id kmet
  was missing — the thinking selector matched Ctrl+S raw) and the config
  screen's `tui.select.*`/`tui.input.tab` legs (pi's raw space/ctrl+c kept).
  What stays raw is pi's own raw matching (space, config/scoped ctrl+c,
  scoped escape, auth `j`/`k`) or order-dependent kmet extras (SelectList's
  shift+pageUp/Down, the thinking selector's clear-then-cancel ctrl+c, the
  editor's late ctrl+p/ctrl+n history fallback) — `tui.md` §7
- **`/settings` menu breadth** — the panel now carries pi's current row
  vocabulary minus the missing features above: auto-compact, show
  images / image width (terminal-gated), block images, skill commands,
  steering/follow-up mode, HTTP idle/timeout/transport, cache-miss
  notices, tree filter, thinking, hide thinking, clear on shrink, terminal
  progress, editor/output padding, autocomplete max items, hardware
  cursor, theme (pi's ThemeSubmenu — single names + Automatic light/dark),
  plus the kmet-only rows (tool display, retry, repeat guards, HTTP total
  timeout). Rows carry pi's `:description` lines. pi's remaining selector
  submenus (warnings, per-model thinking) are still missing with their
  features. The mermaid and fullscreen (TUI mode) rows are postponed
  indefinitely with their features (`tui.md` §15.1)
- **Auth selector/dialog components** — pi `login-dialog.ts`, `oauth-selector.ts`,
  `session-selector-search.ts`; kmet's terminal `/login` covers the flows
- **Built-in tool packaging** — pi 1.3 ships `grep`/`find`/`ls`/`powershell` as
  built-ins and `codemode`/`tool_search` as built-in extensions; kmet keeps
  read/write/edit/bash + `run_code` built in and ships the search tools as
  opt-in extensions. MCP is built-in in pi 1.3 and an extension in kmet.
  Feature-aligned, different packaging.
- **Per-path file-mutation queue** — pi's `write`/`edit` serialize mutations of
  the same file through `withFileMutationQueue` (different files still run in
  parallel), so two same-file writes cannot interleave in a parallel batch.
  **Ported** as `kmet.app.tools.mutation/with-file-lock`: both tools take the
  lock for the file's real path around their read-modify-write, so a parallel
  loop batch or `run_code` fan-out cannot interleave two mutations of one
  file. kmet goes two steps past pi: `read` takes the same lock (so a read
  cannot observe a write's truncated half), and the wait polls the run's
  cancel signal (so a cancelled run does not park a worker on another call's
  write). `write`/`edit` stay parallel-classified (different files overlap),
  and the `run_code` admission lane additionally orders tools that declare
  `:sequential`.
- **Changelog / update surface** — pi has per-package CHANGELOGs, `/changelog`,
  and `collapseChangelog`; kmet has none.
- **`packages/agent` (`@earendil-works/pi-agent-core`)** — general-purpose agent library
  layer; kmet's `kmet.app.loop` covers the coding-agent equivalent, so no port needed

### 7. Session context & agent-core (pi 0.87.0)

Landed. This wave was tracked in a separate plan file while in progress; its
shape now:

- one `:agent-start`/`:agent-end` pair per attempt, with `:agent-end
  :messages` accumulating that attempt's messages and `:will-retry` exposed
  to public listeners only; exactly one `:agent-settled` per prompt;
- a message an `:agent-end` handler queues starts a fresh attempt in the same
  prompt, drained before that attempt starts;
- the actionable `:turn-end` / `:agent-before-settle` boundaries carrying
  `{:entries [...] :continue bool}`, the persisted entry ids
  (`:message-entry-id` / `:tool-result-entry-ids`) and the projection preview
  (`:context-entries` / `:context-messages` / `:llm-messages`);
- the post-run compaction check between `:agent-end` and the pre-settle
  boundary, guarded per prompt against a compaction that aborted or failed in
  the same prompt;
- the pending custom-message queue (flushed at every turn end, at the
  pre-settle boundary, and before a new prompt) and `:next-turn` messages
  injected with the next prompt;
- the per-request checkpoint (`config.prepareRequest`) wrapped by the
  canonical session-projection install, and `peekQueuedMessages`;
- append-only per-message context edits, durable omission of a recovered
  attempt's message, and a compaction that cuts and counts over the
  projection;
- `context_with_system` running after `context` over the full transcript.

Residuals are named in the Appendix rows (`ContextEditEntry` /
`appendContextEdit`, `turn_start` / `turn_end`). Per-model image input limits
remain in §2.

### 8. Summarization calls (compaction / branch summary)

Full pi parity in `loop/summarize!` and `loop/generate-branch-summary` (pi
`generateSummaryWithUsage` / `generateBranchSummary`, both through
`completeSummarization`):

| pi | kmet |
|---|---|
| `retryAssistantCall(produce, retry, signal, callbacks)` wraps every summarization call so transient stream drops reuse the retry policy | ✅ `retry/retry-call!` with the configured policy. The initial call never counts as a retry; a non-retryable result returns immediately; an abort during the backoff normalizes the last result to `:aborted` (pi: an aborted response) |
| `summarization_retry_scheduled` / `_attempt_start` / `_finished` (`_summarizationRetryCallbacks`; session events, not extension events) | ✅ `:summarization-retry-scheduled` (`:attempt`, `:max-attempts`, `:delay-ms`, `:error-message`; the UI shows the error line + the retry countdown), `:summarization-retry-attempt-start` (`:source :compaction` with `:reason`, or `:source :branch-summary`; re-shows that call's status indicator), `:summarization-retry-finished` |
| `getSummarizationFailure` — `<label> failed: <errorMessage or Unknown error>`, `<label> failed: generation hit the token cap and the summary is incomplete` | ✅ `compaction/summarization-failure` (labels `Summarization` / `Branch summarization`), plus pi's tool-call guard (`… attempted to call a tool`) |
| call-site failure text: `Compaction failed: …` (manual), `Auto-compaction failed: …` (threshold), `Context overflow recovery failed: …` (overflow) | ✅ `compaction/compaction-failure-message` — the cause rides `:compaction-end`, `:session-compact-failed`, and `/compact`'s error path (kmet previously emitted a fixed string with no cause) |
| `createSummarizationOptions` — `reasoning = thinkingLevel` unless `off` (compaction); branch summaries pass no reasoning | ✅ `summarize!` passes the session thinking level, the branch summary none |
| output cap `min(floor(0.8 * reserveTokens), model.maxTokens)` (compaction); a flat `4096` (branch) | ✅ same, and the compaction cap adds the session level's thinking budget when the model reasons (`loop/summarization-max-tokens` via `shared/thinking-budget-for` + `clamp-reasoning` — pi `adjustMaxTokensForThinking`). pi adds it inside the anthropic/Bedrock builders; kmet adds it in the compaction cap, so openai-completions — where reasoning and the summary share one ceiling — keeps the summary's allowance too (deviation: pi sends only the text cap there, which a deep-thinking model can exhaust, stopping the summary `length`) |
| branch entries budgeted to `contextWindow - branchSummary.reserveTokens`, newest whole entries first, a summary entry still joining under 90% (`prepareBranchEntries`); nothing fits → `"No content to summarize"` with no call | ✅ `compaction/prepare-branch-entries` (kmet collects no file lists) |
| an empty response is appended (compaction) / yields the preamble alone (branch) | ✅ same, with a debug log |
| a manual compaction of an already-compacted session fails with `Already compacted`; the automatic paths stay silent | ✅ same |
| a `length` stop reason means the summary is incomplete → failure | ✅ |
| `compact()` / `_runAutoCompaction` catch any error and still emit `compaction_end` + `session_compact_failed` (no error message when the abort signal fired), rethrowing only to the caller | ✅ an escaping exception is caught the same way (`:failed`, or `:aborted` when a cancel signal had fired), logged via `debug/log-error`, and reported through the same events |
| a summarization call that *throws* (auth resolution, the context hook, request setup) propagates out of `retryAssistantCall` unretried into that catch | deviation: `summarization-call` normalizes the throw to an `:error` result, so the retry classifier applies — the call re-resolves auth per attempt, so a transient throw (a token refresh over a dead proxy) is worth retrying |

Two deviations are kept in this area: the synchronous-throw normalization
above, and the thinking headroom added to the compaction output cap for
every API (pi adds it only in its anthropic/Bedrock builders). Otherwise
the summarization auth decision is delegated to the LLM layer (`No API key
for …` surfaces as the cause) rather than a pre-call guard, matching pi's
thrown auth error while also covering an oauth-bearer credential.

## Appendix: Event type vocabulary

pi events (`core/extensions/types.ts`) → kmet status (`kmet.app.events` `event-types`).

| pi event | kmet | notes |
|---|---|---|
| `session_start` | ✅ `:session-start` | reason startup/reload/new/resume/fork; kmet lacks `reload` reason flag granularity; the one-shot `--print`/json runs create a runtime without emitting it (pi emits on every runtime creation), so extension startup handlers and contributed resources do not reach them |
| `session_info_changed` | ✅ `:session-info-changed` | emitted by `/name` |
| `session_before_switch` / `session_before_fork` | ✅ `:session-before-switch` / `:session-before-fork` | both emitted by `/switch` and `/fork` (reason :user/:auto), cancelable — handlers return {:cancel true} |
| `session_before_compact` / `session_compact` / `session_compact_failed` | ✅ `:session-before-compact` / `:session-compact` / `:session-compact-failed` | the before-event is cancelable and may supply the content (`{:compaction {:summary … :first-kept-id … :tokens-before … :usage … :details …}}` — the entry then records `:from-hook`, and every event carries `:from-extension`; the preparation's `:settings` is the resolved CompactionSettings (`:enabled` + the per-model token budget) — pi: `preparation.settings`); `:session-compact` carries the appended entry, and `:session-compact-failed` the cause behind pi's reason prefix (`Compaction failed: …` / `Auto-compaction failed: …` / `Context overflow recovery failed: …`) with `:will-retry false`; `:compaction-end` carries the pi-shaped result map. Dispatch parity: the threshold check also runs post-run, between `:agent-end` and the pre-settle boundary (pi: `_handlePostAgentRun` → `_checkCompaction`); the summarization call parity (retry policy, events, thinking level, caps, budget) is in §8 |
| `session_before_tree` / `session_tree` | ✅ `:session-before-tree` / `:session-tree` | incl. cancel/summary/extension-summary results |
| `ContextEditEntry` / `appendContextEdit` | ✅ | the append-only per-message context edit: a `:context-edit` session entry omits (`:replacement nil`) or rewrites (`{:content …}`) one earlier message in the provider projection while the raw branch, usage totals, replay and the TUI keep it; committed through the actionable boundaries' `:entries` or `kmet.app.loop.state/append-context-edit!`, applied by `session/build-context-messages` (pi: `buildSessionProjection`), installed as the request context before every provider request (pi: `_installAgentRequestProjection`), durably omitted for a recovered attempt (pi: `_omitRecoveryAttempt`), and followed by the compaction input and its reported count (pi: `findProjectedCutPoint` / `getMessagesFromProjectedEntryForCompaction`); the boundaries carry `:message-entry-id`/`:tool-result-entry-ids` plus the projection preview (pi: `BoundaryContextPreview`). Residuals: the preview is computed once per dispatch (pi rebuilds it per handler with the accumulated drafts), `:llm-messages` is pi's `convertToLlm` (`kmet.app.loop.message/to-llm-messages`), and `findProjectedCutPoint`'s recovery-omission-suffix advance is not ported |
| `session_shutdown` | ✅ `:session-shutdown` | emitted by `/reload` (reason reload) and `/new` (reason new, target-session-file) before the extension runtime is torn down (pi: teardownCurrent / session.reload) |
| `context` | ✅ `:context` | fired before each LLM call with the conversation only — the leading system message is re-attached to a replacement (pi: `restoreSystemMessages`); handlers return `{:messages [...]}` to replace (last non-nil wins). Fires for every LLM call, the compaction summarization included — pi routes that outside `transformContext` |
| `context_with_system` | ✅ `:context-with-system` | runs after `:context` over the full transcript and its result is sent verbatim; a result that drops the leading system message is reported and honored (pi 0.87.0 — landed; see §7) |
| `before_agent_start` | ✅ | hook, not event |
| `agent_start` / `agent_end` / `agent_settled` | ✅ `:agent-start` / `:agent-end` / `:agent-settled` | one `:agent-start`/`:agent-end` pair per attempt (`agent.prompt` then `agent.continue`, so auto-retry and overflow recovery each get their own); `:agent-end :messages` accumulates that attempt's messages (pi: `newMessages`) — a mid-run compaction does not shrink them, a cancelled/errored attempt is included, and `:will-retry` marks a retryable failure (added for public listeners only, as pi does — `_emitExtensionEvent` precedes the decoration); exactly one `:agent-settled` per prompt; a message an `:agent-end` handler queues starts a fresh attempt in the same prompt, with the queued messages as its prompt (pi: `hasQueuedMessages` → `agent.continue`) |
| `agent_before_settle` | ✅ `:agent-before-settle` | actionable; fires once before `:agent-settled` with the run's `:outcome`, commits returned entries, and `{:continue true}` runs one more attempt (see §7); carries the boundary state `:pending-messages`/`:can-continue` and the projection preview `:context-entries`/`:context-messages`/`:llm-messages` (pi: `BoundaryContextPreview`) |
| `turn_start` / `turn_end` | ✅ `:turn-start` / `:turn-end` | pi 0.87.0's actionable `turn_end`: handlers return `{:entries [...] :continue bool}`, entries are committed in order, `:continue` forces one more request; also fires for an errored or aborted response, before `:agent-end` (see §7); carries `:message-entry-id`/`:tool-result-entry-ids` and the projection preview |
| `message_start` / `message_update` / `message_end` | ✅ | kmet `:message-update` carries `:delta` incl. tool-call |
| `tool_execution_start` / `_update` / `_end` | ✅ | kmet adds `:tool-execution-prepared` between start and execution: the call passed its hooks and the permission gate, so front ends time the tool from there (a permission `:ask` would otherwise be counted in it) |
| `tool_call` / `tool_result` | ~ (mechanism differs) | kmet does **not** emit `:tool-call`/`:tool-result` events on the event bus; the transform chain (block / arg-rewrite / result-rewrite) is wired into the agent's `:before-tool-call`/`:after-tool-call` callbacks via `register-tool-call-hook!`/`register-tool-result-hook!` instead. See §5. Event-bus execution lifecycle events are `:tool-execution-start`/`:tool-execution-prepared`/`:tool-execution-update`/`:tool-execution-end` (`:tool-execution-prepared` is kmet's: pi has no approval gate between start and execution) |
| `model_select` | ✅ `:model-select` | kmet adds `:source` (:set/:cycle) |
| `thinking_level_select` | ✅ `:thinking-level-select` | emitted by `set-thinking-level!` on actual change |
| `user_bash` | ✅ `:user-bash` | |
| `input` | ✅ | input hooks (transform/handled) |
| `before_provider_request` / `before_provider_headers` / `after_provider_response` | ✅ | defined in `kmet.app.events` and bridged to the per-call provider hooks (`kmet.agent.extensions.core/provider-hooks` → `kmet.ai.llm/send-message` opts) — the runtime reads them once per attempt: `events/emit!` at the request's ctx → hook apply → last non-nil result |
| `resources_discover` | ✅ `:resources-discover` | fired after `:session-start`; `extensions/discover-resources!` applies contributed path sets into the skills/prompts/themes registries, each under the owner that contributed it (re-discovery replaces in place, unload takes them with the extension) |
| `mcp_servers_change` | ❌ | pi 1.3 emits it when an extension registers/unregisters an MCP server; kmet has no extension MCP registration (see §5) |
| `provider_stream_event` | ❌ | kmet's per-call provider hooks stop at `:on-context` / `:on-payload` / `:on-headers` / `:on-response` (`kmet.ai.llm/send-message`); per-stream events are not bridged |
| `cache_warming_decision` | ❌ | no cache warming in kmet |
| `ui_prompt_start` / `ui_prompt_end` | ❌ | kmet's `request-ui!`/`respond!` dialogs emit no start/end pair |
| `project_trust` | — | out of scope (historical decision) |

kmet-only additions: `:status`, `:error`, `:queue-update`, `:context-replaced`,
`:auto-retry-start`, `:auto-retry-end`, `:compaction-start`, `:compaction-end`.
