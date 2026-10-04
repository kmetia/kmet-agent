# pi built-in tools and tool exposure

Reference for the kmet ↔ pi alignment work. Describes `packages/coding-agent` at
the pi checkout `~/src/cvstree/pi`, HEAD `200387122` (2026-10-04, v1.0.2 cycle).

Source of truth: `core/extensions/types.ts` (`ToolExposure`), `core/tools/index.ts`
(`createAllToolDefinitions`), `core/settings-manager.ts` (`DEFAULT_TOOL_NAMES`),
`core/agent-session.ts` (registry, loadout, callable set), and the built-in
extensions under `extensions/`.

## What exposure means

`ToolExposure = "direct" | "model-only" | "codemode" | "deferred" | "hidden"`
(`core/extensions/types.ts:509`). It separates three sets the runtime otherwise
conflates: **registered** (everything in the registry), **declared/active** (sent
to the model as tool declarations, `agent.state.tools`), and **callable** (what
`ctx.executeTool()` may run, e.g. from a codemode script).

| exposure | declared to the model | callable via `ctx.executeTool()` | activated on registration |
|---|---|---|---|
| `direct` | while active | while active | yes (unless `defaultActive: false`) |
| `model-only` | while active | never | yes (unless `defaultActive: false`) |
| `codemode` | only if explicitly activated | always while registered | no |
| `deferred` | only if explicitly activated | always while registered | no |
| `hidden` | never | never | no — activating has no effect |

Runtime resolution (`core/agent-session.ts`):

- `_getToolExposure` — `definition.exposure ?? "direct"`.
- `_isDeclarable` (`direct`/`model-only`) and `_isActivatedOnRegistration`
  (declarable and `defaultActive !== false`).
- `_applyToolLoadout` — active names → declared tools (drops `hidden`, runs every
  active tool's `prepareLoadout`).
- `_getCallableTools` — every registered `codemode`/`deferred` tool, plus active
  `direct` tools. `model-only` and `hidden` are never callable. Nested calls run
  through the normal tool pipeline (`_executeNestedToolCall`).
- `prepareLoadout` may rewrite declared descriptions and return
  `hiddenDeclarations` (active/callable, but omitted from requests; the transcript
  still records them, so the loadout survives `/tree`, resume, and fork).
- The system prompt follows the declared set: snippets, `promptGuidelines`, and
  conditional rules (bash/powershell/grep/find/ls) key off the active names.

Activation channels: `defaultTools` setting (supports `+name`/`-name`; default
`["read","bash","edit","write"]`), `--tools` / `-t` (replace selection),
`-xt`/`--exclude-tools`, `-nbt`/`--no-builtin-tools`, `-nt`/`--no-tools`,
`setActiveTools()`, and `defaultActive: false`. `/reload` activates tools newly
added to `defaultTools` only.

## Core built-ins (all `direct`)

Created by `createAllToolDefinitions()` (`core/tools/index.ts:182`); none sets
`exposure`, so all take the default `direct`.

| tool | exposure | active at startup? | notes |
|---|---|---|---|
| `read` | `direct` | yes | in `DEFAULT_TOOL_NAMES` |
| `bash` | `direct` | yes | in `DEFAULT_TOOL_NAMES` |
| `edit` | `direct` | yes | in `DEFAULT_TOOL_NAMES` |
| `write` | `direct` | yes | in `DEFAULT_TOOL_NAMES` |
| `grep` | `direct` | no | available; enable via `--tools`/`defaultTools` |
| `find` | `direct` | no | same |
| `ls` | `direct` | no | same |
| `powershell` | `direct` | no | Windows-only at execution (`utils/shell.ts:125` throws elsewhere) |

`DEFAULT_TOOL_NAMES = ["read", "bash", "edit", "write"]`
(`core/settings-manager.ts:215`). The other four are `direct` but not selected by
default.

## Built-in extension tools

`extensions/index.ts` ships four built-in extensions: `llama` (no tools; provider
and `/llama` command only), `codemode`, `tool-search`, and `mcp`.

| tool | exposure | active on registration? | notes |
|---|---|---|---|
| `codemode` | `model-only` | no | `defaultActive: false` (`extensions/codemode/index.ts:33`); scripts must not start scripts; has `prepareLoadout` and grammar-constrained sampling |
| `tool_search` | `model-only` | no | `defaultActive: false` (`extensions/tool-search/index.ts:14`); BM25 over `codemode`/`deferred` tools, loads matches into the active set |
| `mcp__<server>__<tool>` | from server config | `direct` only | registered by the MCP extension once a server connects |
| `list_mcp_resources` | widest exposure of servers with resources | `direct` only | registered only while a server with resources exists |
| `list_mcp_resource_templates` | same | `direct` only | same |
| `read_mcp_resource` | same | `direct` only | same |

MCP exposure mapping (`McpExposure` in `core/mcp-servers.ts:17`, mapped by
`extensions/mcp/tools.ts:45`; default is `codemode`):

| MCP `exposure` | → ToolExposure | declared? | reachable via |
|---|---|---|---|
| `codemode` (default) | `deferred` | no | codemode scripts (`searchTools()`, `describeTool()`, `ALL_TOOLS`); the server namespace is summarized, not each tool |
| `deferred` | `deferred` | only after `tool_search` loads it | scripts always; model after discovery |
| `direct` | `direct` | yes, on registration | model + scripts |
| `hidden` | `hidden` | never | unreachable |

Per-tool `toolExposure` overrides (exact names or `*` patterns) select exposure for
individual server tools. MCP `codemode` maps to ToolExposure `deferred` so codemode
does not list the tools; the MCP extension activates `codemode` when a server with
`codemode` exposure connects and `tool_search` for `deferred` exposure.

## Notes

- `model-only` means the model may call the tool but other tools may not — that is
  why `codemode` and `tool_search` themselves are not callable from scripts.
- The three MCP resource tools are registered as `hidden` when no enabled server
  exposes resources (`extensions/mcp/index.ts`, `syncResourceTools`).
- `packages/durable` has its own tool set (`bash`, `edit`, `read`, `write`,
  `image`, `env`), but that is a separate library surface, not the coding agent's
  model-facing built-ins.
- kmet currently has no exposure/loadout layer: every registered tool is
  effectively `direct`, selection is the `:enabled-tools` filter plus `/tools`,
  and script-visible tools come from the `run_code` tool-source mechanism. See
  `src/kmet/development/pi-alignment.md`.
