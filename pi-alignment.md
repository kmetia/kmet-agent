# kmet ↔ pi alignment: feature gap analysis

Reference: `~/src/cvstree/pi/` — `packages/coding-agent/src/**`, `packages/tui/src/**`,
`packages/ai/**`, `packages/agent/**`. Kmet side: `src/kmet/**`.

Goal: enumerate what pi exposes that kmet does not, so a "full alignment" pass has a
concrete work list. "Aligned" means the behavior exists in kmet with equivalent
semantics (EDN/Clojure adaptation); gaps are pi features with no kmet counterpart.

## Summary

kmet is a close port of pi's interactive TUI + agent core: the component/TUI
framework, agent loop, tools, provider/auth subsystem (kmet.ai), sessions (EDNL),
compaction, skills, prompt templates, themes, and the extension API (hooks,
events, commands, tools, flags, renderers, agent control, tool hooks) are
functionally aligned. The remaining gaps cluster in the CLI surface, rendering
(mermaid/latex/search/images), the settings surface, the extension
`registerProvider` `streamSimple`/`refreshModels` (wire-layer custom
provider streaming + dynamic model refresh), and the 0.87.0 session-context
wave (append-only context edits, actionable `turn_end`/`agent_before_settle`
extension boundaries, `context_with_system`, per-model image input limits).

## Deliberately out of scope (locked decisions)

| Area | pi feature | kmet decision |
|---|---|---|
| Additional run modes | `--mode rpc` / `--mode json`, `pi server` / `pi client` / `pi rpc`, `packages/server`, `packages/client`, `packages/protocol` (CBOR), `docs/rpc.md`, `docs/sdk.md` | Not ported — kmet ships interactive + print modes only |
| Project trust | `core/project-trust.ts`, `core/trust-manager.ts`, `/trust`, `trust-selector.ts`, `--approve`/`--no-approve`, `defaultProjectTrust`, `trust.json` | Not ported — project settings/extensions load unconditionally (`-a`/`-na` parse as no-ops on the package commands) |
| Package sources: npm/git | `npm:`/`git:` installs (npm registry + git clone machinery in `core/package-manager.ts`), `pi update`/self-update, temporary `--extension` installs, per-package autoload deltas over npm/git identities | Not ported — kmet packages are local files/directories only (`kmet install ./dir`, `~`, absolute, relative; anything else errors with "Unsupported package source"). The remainder of the package manager IS ported (next section) |
| Session interop / JSONL | pi JSONL session files, `/export` `.jsonl`, `--export` from JSONL | EDNL-only by design (see `session.md`) — `/export` writes standalone HTML |

## Ported: the resource resolution & local-source package manager

pi `core/package-manager.ts` (the full resolve pipeline) +
`package-manager-cli.ts` + `cli/config-selector.ts`
→ `kmet.app.packages` + `kmet.package-manager` + `kmet.app.ui.resource-config`
(+ the runner call sites in `core.clj`, `modes/interactive.clj`, `modes/print.clj`).

`PackageManager.resolve()` is fully ported: top-level settings resource
entries (`:extensions`/`:skills`/`:prompts`/`:themes`, plain paths plus
`!glob`/`+path`/`-path` patterns), the fixed auto-dir scans
(`~/.kmet/agent/<type>` + `.kmet/<type>`), and `:packages`, all into pi's
insertion order (project-local → user-local → project-auto → user-auto →
packages) with pi's `toResolvedPaths` finalization — a stable sort by
`resourcePrecedenceRank` (project-local 0, project-auto 1, user-local 2,
user-auto 3, package 4) then dedupe by canonical path, first wins. Loading
is one unified enabled-path pass per type (`load-extensions!` /
`load-skills!` / `load-prompts!` / `load-themes!`).
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
re-enable them. Everything loads at startup, in print mode, and on
`/reload`.

Kmet adaptations (deliberate deviations):

- kmet's single-extension dir unit is a directory containing `extension.edn`
  (pi: `package.json` `pi` manifest or an implicit `extension.ts`); a bare
  directory without resource subdirs loads as an *extension container*
  (top-level `.clj`/`.jar`/`.zip`/`extension.edn` items) rather than one
  extension, and its items are individually filterable.
- No pi `package.json` manifest — packages use the convention subdirs only,
  and `extension.edn` dirs ignore per-type filters (the extension owns its
  bundle, pi file-source behavior); the config screen therefore marks those
  rows “always loaded” and rejects toggles.
- Pattern matching implements minimatch's common subset (`*`, `?`, `**`,
  `[...]`; no extglobs/braces) against the same match forms as pi (relative
  path, file name, absolute path; skills also match their parent folder).
- `config` lists all layers (pi buildGroups: packages first, then
  top-level groups, user before project). Package items toggle via the
  package's settings entry; top-level/auto items toggle via the scope's
  settings resource array (pi toggleTopLevelResource /
  setProjectTopLevelOverride, including the inherited-global absolute-path
  entry). Project mode is available whenever `.kmet/` exists or `-l` was
  passed (no trust gate).
- No configurable resource dirs: the auto roots are fixed
  (`~/.kmet/agent/<type>` + `.kmet/<type>`, pi `join(agentDir, type)` +
  `join(cwd, CONFIG_DIR_NAME, type)`); the retired `:*-dir` settings keys
  warn at load and point at the top-level entries. The agent dir honors
  `KMET_CODING_AGENT_DIR` (pi `ENV_AGENT_DIR`).
- Remote (npm/git) entries in settings warn and are skipped at load instead
  of being auto-installed.
- `pi update` (extension updates, model-catalog refresh, self-update) is not
  ported; `kmet --generate-models` covers the model-catalog piece.

## Gap analysis

### 1. CLI surface

**Subcommands** — pi's package commands are ported for local sources (see
“Ported: the local-source package manager”): `kmet install/remove/uninstall/
list/config` dispatch from `core.clj` to `kmet.package-manager` (pi
`package-manager-cli.ts`) before generic arg parsing. Still missing:

| pi command | ref |
|---|---|
| `pi auth check` / `pi auth print-api-key` / `pi auth print-bearer-token` | `cli/auth-check.ts`, `cli/auth-command.ts`, `cli/credential-print.ts` |
| `pi update [source|self|--models|--all ...]` | `package-manager-cli.ts` |
| `pi --export <session-file> [path]` | `main.ts` (`--export`) |

**Flags** — kmet covers `-p/--print`, `-c/--continue`, `-r/--resume`, `--model`,
`--provider`, `--models`, `--list-models`, `--system-prompt`, `--append-system-prompt`,
`-t/--thinking`, `-d/--debug`, `-h/--help`, `@files`, positional messages
(`src/kmet/core.clj` vs pi `cli/args.ts`). Missing:

| flag | purpose |
|---|---|
| `--api-key` | API key for a model specified via `--model`/`--models` |
| `--exclude-tools`, `--tools`, `--no-tools`, `--no-builtin-tools` | tool selection for one run (kmet has in-TUI `/tools` only) |
| `--extension` | load an extension file/dir for one run |
| `--export` | export a session file to HTML/EDNL from the CLI |
| `--fork` | start from a fork of a previous user message |
| `--session`, `--session-dir`, `--session-id` | session selection (kmet has `--resume` browsing only) |
| `--no-context-files`, `--no-extensions`, `--no-prompt-templates`, `--no-skills`, `--no-themes`, `--no-session` | disable subsystems for one run |
| `--offline` (+ `PI_OFFLINE`) | skip all startup network operations |
| `--prompt-template` | use a prompt template for one run |
| `--skill` | invoke a skill for one run |
| `--theme` | theme for one run |
| `--tui-mode` | `regular` vs experimental `fullscreen` TUI — **postponed indefinitely** (the inline-scrollback model is deliberate; `tui.md` §15.1) |
| `--verbose` | verbose logging |
| `--version` | print version |

### 2. Interactive features & rendering

| Feature | pi ref | kmet status |
|---|---|---|
| **Mermaid diagram rendering** | `modes/interactive/components/mermaid.ts` + `markdown.mermaid` setting (`off`/`final`/`streaming`) | **Postponed indefinitely** (2026-09-10; rationale in `src/kmet/tui/tui.md` §15.1) |
| **LaTeX rendering** (`$…$`, `$$…$$`) | `packages/tui/src/latex.ts`, wired in `tui/src/components/markdown.ts` | **Postponed indefinitely** (same) |
| **Alt-screen search** (search overlay over the transcript) | `packages/tui/src/alt-screen-search.ts`, `tui-alt-screen.ts` | **Postponed indefinitely** (same — needs an alt-screen mode) |
| **Images in chat** | `terminal.showImages`, `terminal.imageWidthCells`, `images.autoResize`, `images.blockImages` settings; `show-images-selector.ts` | **Partial** — the TUI half done (`tui.md` §10): `:terminal {:show-images :image-width-cells}` settings, terminal-support-gated `/settings` rows, and inline images (or the `imageFallback` text indicator when off/unsupported) in tool results and user/custom messages. `images.blockImages` done: `:images {:block-images}` setting + ungated `/settings` row, stripped per request in `app/loop.clj/call-llm` (pi: convertToLlmWithBlockImages — placeholder text, consecutive dedupe, stored context untouched). **Missing**: `images.autoResize` and the per-model `inputLimits.images.resize` catalog metadata (pi #9631: resize profiles in `models.json`, applied to attachments, `read`, and tool-result images). Both need a resizer backend decision — pi runs a Photon WASM resize pipeline and babashka has no ImageIO/AWT (see `app/tools/read.clj`); the generated catalogs carry no `:input-limits` keys yet
| **Cache-miss notices** | `showCacheMissNotices` setting | Done — `:show-cache-miss-notices` setting, `session/detect-cache-miss` (pi detectMiss), notice at agent-end (≥ 20k tokens) |
| **Skill invocation presentation** | `components/skill-invocation-message.ts` | **Done** — `kmet.app.skills/parse-skill-block` + `kmet.app.ui.skill-message`: a `/skill:name` block renders as `[skill] name (ctrl+o to expand)` (collapsed) or the name + body as Markdown (expanded), with the trailing args as a normal user message below. Live and replay share the parse; the session still stores the expanded text |
| **Custom entry rendering** | `registerEntryRenderer` + `components/custom-entry.ts` | Done — `extensions/register-entry-renderer!` + live entry sink; rendered at replay and on append (pi registerEntryRenderer + CustomEntryComponent) |

### 3. Slash commands

kmet covers: settings, model, thinking, scoped-models, export (HTML), import, share, copy, name,
session, hotkeys, fork, clone, tree, login, logout, new, compact, resume, continue,
reload, quit, help, tools, theme — full parity with pi's built-in command set.

### 4. Settings

kmet (`config.clj`) covers: provider/model/thinking/theme/session-dir/
http-idle-timeout-ms/system-prompt/append-system-prompt/retry
(enabled/max-retries/base-delay-ms)/enabled-models/hide-thinking-block/
auto-compact/show-cache-miss-notices/steering-mode/follow-up-mode/
tree-filter-mode/output-pad/editor-padding-x/autocomplete-max-visible/
show-hardware-cursor/enable-skill-commands/extensions/skills/prompts/themes dirs,
compaction thresholds, terminal display (`:terminal` — show-images /
image-width-cells / clear-on-shrink), terminal progress
(`:show-terminal-progress`), provider image blocking (`:images` — block-images),
shell customization (`:shell-path` / `:shell-command-prefix` — applied to
every bash execution: tool, `!` commands, and factory-built tools),
`.kmet/SYSTEM.md` + `APPEND_SYSTEM.md` discovery, `KMET_PROVIDER`/
`KMET_MODEL` env vars.

Missing (pi `docs/settings.md`):

| setting | purpose |
|---|---|
| `enableInstallTelemetry`, `enableAnalytics`, `trackingId` | anonymous install/analytics pings (pi.dev infrastructure) |
| `doubleEscapeAction` | double-escape behavior: `tree` / `fork` / `none` |
| `tuiMode`, `fullscreenExitOutput`, `fullscreenScrollbar` | fullscreen TUI mode |
| `httpProxy` | proxy URL applied as HTTP(S)_PROXY (kmet reads proxy env vars only — `libs/http.cljc`) |
| `warnings.anthropicExtraUsage` | Anthropic subscription extra-usage warning |
| `branchSummary.reserveTokens`, `branchSummary.skipPrompt` | branch summarization config |
| `retry.provider.timeoutMs` / `maxRetries` / `maxRetryDelayMs` | provider/SDK retry tuning |
| `transport`, `websocketConnectTimeoutMs` | provider transport selection (`sse` / `websocket` / `auto`) — kmet's `/settings` "HTTP transport" row is a different axis (`http-client`/`curl`) |
| `images.autoResize` | image resize before sending (needs a resizer backend — babashka has no ImageIO/AWT; `images.blockImages` is done — see §2) |
| `markdown.codeBlockIndent`, `markdown.mermaid` | markdown rendering |
| `thinkingBudgets` | per-level thinking token budgets |

Done (previously missing): `terminal.clearOnShrink` (`:terminal :clear-on-shrink` setting +
row, live `tui-set-clear-on-shrink!`, env default preserved),
`terminal.showTerminalProgress` (row + live read; the indicator also clears
when the setting is disabled mid-turn and on `tui-stop`, like pi's `stop`),
`enableSkillCommands` (`:enable-skill-commands` + row, gates the
`/skill:name` autocomplete entries), and the theme row's pi shape (a
`ThemeSubmenu`: single names + the Automatic `light/dark` mode with
light/dark pickers and live preview — enabled by `SettingItem.submenu`
support in `kmet.tui.components.settings-list`).

### 5. Extension API

Aligned (`app/extensions.cljc` + `app/event_bus.clj`): input + before-agent-start hooks,
provider registration (incl. OAuth), `ctx.models.*` facades, UI registry
(select/confirm/input/notify/custom/widgets/footer/header/editor/theme/status/
working-indicator/terminal-input), session append-entry/message/labels,
entry + message renderers, markdown transformers, shortcuts, CLI flags, and
all agent/session/provider/resources-discover events (see Appendix).

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
| `sendUserMessage` (deliverAs steer/followUp) | **Done** — `extensions/send-user-message` → loop steer!/follow-up! |
| `setModel`, `getThinkingLevel`, `setThinkingLevel` | **Done** — via the ui registry (auth-gated setModel, validated levels) |
| `exec` | **Done** — `extensions/exec` (babashka.process, string capture) |
| `getActiveTools`/`getAllTools`/`setActiveTools` | **Done** — `:enabled-tools` filter on the agent state, applied to the wire `:tools`; `get-all-tools` returns the array |
| `registerProvider` `streamSimple`/`refreshModels` | Partial — `registerProvider` config registration is done (`extensions/register-provider!` / `models/register-provider-config!`); `streamSimple`/`refreshModels` (wire-layer custom provider streaming + dynamic model refresh) still missing |
| `context_with_system` event | **Missing** — pi runs it after every `context` handler over the full transcript (system messages included) and sends the result verbatim; dropping the leading system message is an extension error. kmet has one `:context` event over the outgoing message list (system prompt included — `call-llm` prepends it; tools travel separately, so a filtering handler cannot drop them), last non-nil `{:messages ...}` wins |
| Events: `resources_discover`, `session_before_switch`, `session_before_fork`, `session_before_compact`, `context`, `before_provider_request`, `before_provider_headers`, `after_provider_response` | **Done** — see Appendix (all ✅) |
| Events: `session_info_changed`, `thinking_level_select` | **Done** — emitted by `/name` and `set-thinking-level!` |
| Events: `tool_call`/`tool_result` (transform) | **Done** — `extensions/register-tool-call-hook!`/`register-tool-result-hook!` chained as the agent's `:before-tool-call`/`:after-tool-call` callbacks (block / arg-rewrite / result-rewrite). Note: the transform chain is wired into the agent callbacks rather than fired as event-bus events; the event bus still carries the execution lifecycle via `:tool-execution-start`/`:tool-execution-update`/`:tool-execution-end` (see Appendix) |

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
  features. The mermaid and tui-mode rows are postponed indefinitely with
  their features (`tui.md` §15.1)
- **Auth selector/dialog components** — pi `login-dialog.ts`, `oauth-selector.ts`,
  `session-selector-search.ts`; kmet's terminal `/login` covers the flows
- **`packages/agent` (`@earendil-works/pi-agent-core`)** — general-purpose agent library
  layer; kmet's `app/loop.clj` covers the coding-agent equivalent, so no port needed

### 7. Session context & agent-core (pi 0.87.0)

pi's "canonical session context" wave (`SessionManager` authoritative for
provider context, append-only context edits, actionable extension
boundaries) has no kmet counterpart. kmet's session/context model is its own
(EDNL `app/session.clj` + the `app/loop.clj` state atoms), so these are
design decisions, not mechanical ports:

- **`ContextEditEntry` / `appendContextEdit`** — append-only per-message
  context edits: `replacement: null` omits one message from future provider
  context, a content replacement swaps its text; raw history, usage, and UI
  history stay untouched. kmet has no context-edit layer (compaction rewrites
  the stored context; nothing can omit a single message without editing
  history).
- **Actionable `turn_end` / `agent_before_settle` boundaries** — extension
  handlers can return `{:entries [...] :continue bool}` to persist
  structural entries in order and ensure one next provider request without
  changing steering/follow-up scheduling; kmet's `:turn-end` /
  `:agent-settled` are notification-only.
- **`finishTurn` / `prepareRequest` / `peekQueuedMessages`** (`packages/agent`)
  — `finishTurn` replaces `shouldStopAfterTurn` (breaking change) and runs
  for normal, error, and aborted responses, with its decision applied after
  `turn_end`; `prepareRequest` installs canonical context before every
  provider request; `peekQueuedMessages` previews the next queued batch.
  kmet's `loop.clj` keeps its own adaptation of the pre-0.87 hooks
  (`:prepare-next-turn` then `:should-stop-after-turn`, boolean, not run for
  error/aborted responses) — close, but the hook shape and coverage differ.
- **Per-model image input limits** — see §2 (catalog `inputLimits` metadata +
  enforcement).

## Appendix: Event type vocabulary

pi events (`core/extensions/types.ts`) → kmet status (`app/event_bus.clj` `event-types`).

| pi event | kmet | notes |
|---|---|---|
| `session_start` | ✅ `:session-start` | reason startup/reload/new/resume/fork; kmet lacks `reload` reason flag granularity |
| `session_info_changed` | ✅ `:session-info-changed` | emitted by `/name` |
| `session_before_switch` / `session_before_fork` | ✅ `:session-before-switch` / `:session-before-fork` | both emitted by `/switch` and `/fork` (reason :user/:auto), cancelable — handlers return {:cancel true} |
| `session_before_compact` / `session_compact` | ~ | kmet emits `:compaction-start`/`:compaction-end` (reason manual/threshold/overflow/auto) |
| `session_before_tree` / `session_tree` | ✅ `:session-before-tree` / `:session-tree` | incl. cancel/summary/extension-summary results |
| `session_shutdown` | ✅ `:session-shutdown` | emitted by `/reload` (reason reload) and `/new` (reason new, target-session-file) before the extension runtime is torn down (pi: teardownCurrent / session.reload) |
| `context` | ✅ `:context` | fired before each LLM call with the outgoing messages (system prompt included — `call-llm` prepends it; tools travel separately); handlers return {:messages [...]} to replace (last non-nil wins). pi 0.87.0: `context` handlers see the conversation only (pi re-applies prompt sections + tool declarations) and `context_with_system` runs after them over the full transcript |
| `context_with_system` | — | missing (pi 0.87.0 per-request system-message transformations over the full transcript) |
| `before_agent_start` | ✅ | hook, not event |
| `agent_start` / `agent_end` / `agent_settled` | ✅ `:agent-start` / `:agent-end` / `:agent-settled` | pi 0.87.0 adds the actionable `agent_before_settle` boundary (missing) |
| `turn_start` / `turn_end` | ✅ `:turn-start` / `:turn-end` | notification-only; pi 0.87.0 makes `turn_end` actionable (`{:entries [...] :continue bool}`) |
| `message_start` / `message_update` / `message_end` | ✅ | kmet `:message-update` carries `:delta` incl. tool-call |
| `tool_execution_start` / `_update` / `_end` | ✅ | |
| `tool_call` / `tool_result` | ~ (mechanism differs) | kmet does **not** emit `:tool-call`/`:tool-result` events on the event bus; the transform chain (block / arg-rewrite / result-rewrite) is wired into the agent's `:before-tool-call`/`:after-tool-call` callbacks via `register-tool-call-hook!`/`register-tool-result-hook!` instead. See §5. Event-bus execution lifecycle events are `:tool-execution-start`/`:tool-execution-update`/`:tool-execution-end` |
| `model_select` | ✅ `:model-select` | kmet adds `:source` (:set/:cycle) |
| `thinking_level_select` | ✅ `:thinking-level-select` | emitted by `set-thinking-level!` on actual change |
| `user_bash` | ✅ `:user-bash` | |
| `input` | ✅ | input hooks (transform/handled) |
| `before_provider_request` / `before_provider_headers` / `after_provider_response` | ✅ | defined in `event_bus.clj` and bridged to the ai-layer hooks (`ai/hooks.clj`) in `app/extensions.cljc`: emit-event! → hook apply → last non-nil result |
| `resources_discover` | ✅ `:resources-discover` | fired after `:session-start`; `extensions/discover-resources!` applies contributed path sets into skills/prompts/themes registries (reloads dedup via `applied-resource-paths`) |
| `project_trust` | — | out of scope (see locked decisions) |

kmet-only additions: `:status`, `:error`, `:queue-update`, `:context-replaced`,
`:auto-retry-start`, `:auto-retry-end`, `:compaction-start`, `:compaction-end`.
