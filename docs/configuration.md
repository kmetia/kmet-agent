# Configuration

Provider and credential details are covered in [Providers and
authentication](providers.md).

## Settings

Settings are loaded from:

1. `~/.kmet/agent/settings.edn` — user-wide settings
2. `.kmet/settings.edn` — project-local overrides
3. Environment variables: `KMET_PROVIDER`, `KMET_MODEL`,
   `KMET_CACHE_RETENTION` (see [Prompt caching](#prompt-caching))

With the default paths, global settings live in
`~/.kmet/agent/settings.edn`, credentials entered with `/login` live in
`~/.kmet/agent/auth.edn`, and session transcripts live in `~/.kmet/sessions/`
unless `:session-dir` changes that location. Project settings and resources
can live under `.kmet/`; the global agent directory can be relocated with
`KMET_CODING_AGENT_DIR`.

Example `~/.kmet/agent/settings.edn`:

```clojure
{:provider :opencode-go
 :model "deepseek-v4.1-flash"
 :theme "dark"
 :thinking :off
 :thinking-mode :visible        ; thinking blocks: :visible (default), :stream-last (the label above the live
                                ; block's tail while the model thinks, the label alone once it ends), :hidden
 :session-dir "~/.kmet/sessions"
 :http-idle-timeout-ms 120000   ; LLM stream idle timeout in ms; 0 disables idle timeout
 :http-transport :platform      ; :platform (default) = babashka.http-client on both hosts,
                                ; curl for SOCKS/https-scheme proxies; :curl = everything through curl
 :http-total-timeout-ms 0       ; whole-request deadline in ms; 0 disables, nil follows idle
 :steering-mode :all
 :follow-up-mode :one-at-a-time
 :shell-command-prefix nil      ; line prepended to every bash command, e.g. "shopt -s expand_aliases"; nil = none
 :shell-path nil                ; custom shell binary for bash execution (e.g. Cygwin/Git Bash on Windows); a leading ~ expands
 :system-prompt "You are a helpful assistant."   ; replaces the default system prompt
 :append-system-prompt "Follow the project conventions." ; appended after it}
```

Both shell settings are settings the **agent** carries rather than process-wide
switches: each agent's shell comes from the config it was created with, so two
agents in one process (a server's connections) can run two shells, and a
`/reload` re-seats the value for the next run.

A copyable starting point with the current keys is available in
[`examples/settings.edn`](examples/settings.edn); see the
[examples guide](examples/README.md#settings) for installation and notes.

## Composition vs settings

`settings.edn` holds **values** — provider, model, theme, tool selection and
the other per-run knobs. What a run **mounts** (services, modes, extensions,
and the plugin rows a profile stacks) is composition data: the built-in table
plus a global `~/.kmet/agent/patch.edn` and a project `.kmet/patch.edn`. See
[Profiles and composition](profiles.md) for the layer order and the patch
shape.

Extensions are plugins: an extension loads only when a composition row names
it (`:ext/<id>`). Settings still install and discover extensions (top-level
`:extensions` paths, `:packages`, auto dirs) and the retired
`:bundled-extensions` key no longer decides what loads; compose the extension
in a layer or toggle it in `kmet plugins`. Skills, prompts and themes remain
settings-backed resources. Built-in tool rows (`:tool/<name>`) compose the
same way, and `kmet plugins` removes or restores them; their live selection
is `:resources` at runtime. The retired `:default-tools`/`:enabled-tools`
keys below are ignored (`/config` edits the live `:resources` selection).

## Provider catalogs

`kmet` reads its provider catalog from `modules/ai/src/kmet/ai/model_data/*.edn`
(41 providers: opencode-go, deepseek, anthropic, google, groq, cerebras,
openrouter, nvidia, moonshotai, qwen-token-plan, minimax, fireworks,
vercel-ai-gateway, open-design, zai, together, baseten, kimi-coding,
cloudflare, mistral, google-vertex, amazon-bedrock, ...; generated from
models.dev + live catalogs — `bb generate-models`).
A user-level cache can be refreshed without touching the repo:
`kmet --generate-models` runs the same generators
into `~/.kmet/agent/models-cache/` for the provider catalogs (one
`<provider>.edn` per provider + `manifest.edn`, same layout as the
built-ins) and `~/.kmet/agent/image-models-cache/` for the image catalog,
and each cache replaces the corresponding built-in data whenever it is
strictly newer — so an upgrade shipping newer bundled data wins over a
stale cache until the next refresh. Reruns with no upstream changes rewrite
nothing. The image-model catalog lives in
`modules/ai/src/kmet/ai/image_model_data/image-models.edn`.
Models, base URLs and defaults are registry data; custom providers, API keys
and model overrides go in `~/.kmet/agent/models.edn`. OpenAI-completions
wire compatibility (thinking format, max-tokens field, reasoning
round-trip) auto-detects from the provider id and base URL; explicit
`:compat` entries in models.edn override the detected defaults.

### Accounts

`models.edn` can declare account aliases under `:accounts` so a provider is
usable with more than one credential:

```clojure
{:accounts {:github-copilot#work {}          ; base inferred from base#label
            :deepseek#work       {:name "DeepSeek (work)"
                                  :api-key "$DEEPSEEK_WORK_API_KEY"}
            :copilot-enterprise  {:provider :github-copilot}}}
```

An entry mirrors the provider named by `:provider`, or by the part before
`#` in the alias id. Alias ids are plain ASCII provider ids — letters, digits,
`-`, `_` and `.`, with `#` before the label — and never namespaced or
containing `/`, which separates the provider and model in model references.
`:name` overrides the display name (default:
`Base name (label)`), and `:api-key` is the same raw config value a provider
entry accepts (`"literal"`, `"$ENV_VAR"`, or `"!command"`). Credentials
entered with `/login` are stored per alias in `auth.edn`. `/login base#label`
adds a missing account declaration to the global `models.edn`.

The alias inherits the base provider's models, base URL and non-auth headers,
but no credential: base env vars, a base `:api-key`, and credential-carrying
headers (`Authorization`, `*-api-key`, `*-token`, …) stay with the base
account. Ambient auth (`google-vertex` ADC, `amazon-bedrock`'s AWS credential
chain) does not carry over either, so an alias of those needs its own
`:api-key`. A bare model id becomes ambiguous once the base and an alias are
both authenticated; see the
[providers guide](providers.md#multiple-accounts).

## System prompts

The system prompt (pi-compatible) is built from: the default (or `:system-prompt`)
base, the active tools with one-line snippets, guidelines, `:append-system-prompt`,
the project context (`AGENTS.md`/`CLAUDE.md` from `~/.kmet/agent` and the cwd's
ancestors), the `<available_skills>` block, and the current working directory.
Pass `-nc`/`--no-context-files` to skip project-context discovery for one run.

Like pi, prompt files are discovered when the config keys are unset:

- `.kmet/SYSTEM.md` or `~/.kmet/agent/SYSTEM.md` — replaces the system prompt
- `.kmet/APPEND_SYSTEM.md` or `~/.kmet/agent/APPEND_SYSTEM.md` — appended to it

Config values win over files; a config value naming an existing file is read as
content. CLI flags `--system-prompt <txt>` and `--append-system-prompt <txt>`
(repeatable) override everything.

## Resource selection

Tools, skills and prompt templates share one persisted selection under
`:resources`; a resource is identified by its type and exact registry name.
Settings keep one list per type:

```edn
;; ~/.kmet/agent/settings.edn
{:resources {:tools  ["-bash"]
             :skills ["-clojure-edit"]}}

;; .kmet/settings.edn
{:resources {:tools ["+bash"]}}
```

The global list selects or modifies the defaults; the project list is a delta
over the effective global selection:

- A missing global type list inherits the defaults; an empty list selects
  nothing.
- A modifier-only global list starts from defaults and applies `+name` and
  `-name` in order.
- A global list with plain names is absolute: only the named resources are
  selected, then modifiers apply. A mixed list uses its plain names as that
  base, then applies the modifiers.
- A plain global `:tools` list is absolute over every available tool, so it
  also excludes extension tools that are not named.
- A project type list accepts only `+name` and `-name`; a missing or empty
  list inherits the global selection. `+name` enables and `-name` disables
  that resource in the project, a resource with no entry inherits, and the
  last entry wins.
- A plain name in a project list is invalid: the whole list for that type is
  ignored with one warning, and the project inherits global.
- Project scope is the `.kmet/settings.edn` of the directory kmet started in;
  a resumed session's recorded cwd does not move it.

Merge order, per type:

```
defaults -> global selection -> project delta -> /tools session override
```

The `/tools` override applies to tools only.

Defaults: skills and prompt templates are on. A built-in tool is on unless
its row carries `:default-enabled? false` (`run_code` and `eval`); extension
tools are on. A modifier-only or unset selection never turns `run_code` or
`eval` on; a plain name or `+name` does.

Availability is separate from selection. A resource is available when a
provider mounted and active in the current project run registers it (a tool
row, an extension, a package or a discovery path). A consumer uses it only
when it is both available and selected. `/config` lists available rows, plus a
dimmed `Stored, unavailable` group for the stored entries no mounted provider
offers (a removed tool row, an uninstalled extension, a package-filtered
resource, a `:sandbox` source). Such a row is inert, but Space forgets its
entry from the scope being edited: a forgotten global entry that empties the
list clears the type key (defaults apply again), while a forgotten project
entry falls back to inheriting global. A stored entry that is not forgotten
applies again when the provider returns. A built-in tool whose composition row
was removed is unavailable until the row is restored with `kmet plugins`. A
package filter shapes availability too: a resource it excludes has no
available row and cannot be selected. `:sandbox` tool sources are not
selectable: their tools stay callable from scripts, including MCP tools.

The three surfaces are:

- `kmet plugins` — static composition editor for `:ext/<id>` and
  `:tool/<name>` rows in the global and project patch layers. It never writes
  `:resources`, never loads extension code, and its changes apply on the next
  start.
- `/config` — live selector for the persisted `:resources` selection over the
  providers available in the current run. Rows are grouped by provider and
  labelled `type/name` (a `:deferred` tool source's tools sit under the
  extension that registered the source); it starts in project scope, and Tab
  switches between project and global. Project rows cycle
  inherit/enabled/disabled and name the inherited global state
  (`inherited global (enabled)` or `(disabled)`); global rows toggle
  enabled/disabled. Stored entries whose provider is unavailable this
  run appear dimmed in `Stored, unavailable`; Space forgets them from the
  current scope. A tool write clears the `/tools` session override and
  refreshes the live tool set; a skill write rebuilds the prompt;
  prompt-template lookup reads the selection live. An unchanged write is a
  no-op, and a write does not remount providers or re-run discovery.
- `/tools` — session-only absolute tool set. It never writes settings and
  disappears with the session. `/reload` does not clear the override; a
  `/config` tool write or a session switch does.

The selection applies at startup, on `/reload`, and on a session switch
(`/resume`, `/import`), where the runtime is recreated from settings and the
outgoing session's `/tools` override does not carry over. A tool that is not
active is not executable: a call the model makes for it settles as
`Tool <name> not found`.

`--tools <list>` is the command line's session override (pi's flag of the same
name): a comma-separated list of exact tool names. A plain list is an absolute
allowlist (`--tools read,edit,bash`); a list of only `+name`/`-name` entries
changes the effective persisted selection (`--tools -bash,+eval` keeps the
other selected tools, removes `bash`, and turns on the opt-in `eval`). Mixing
plain names with modifiers is a usage error, as in pi. The override is applied
like `/tools`: it is not written to settings, it survives `/reload`, and a
session switch or a `/config` tool write clears it. `--exclude-tools`,
`--no-tools` and `--no-builtin-tools` have no kmet flag yet.

A disabled skill is left out of the prompt's `<available_skills>` block and
`/skill:name` does not expand it; a disabled prompt template is neither
offered in autocomplete nor expanded. A skill marked
`disable-model-invocation` still expands through `/skill:name` even though the
prompt never lists it.

## Tools

> **Migration note:** `:default-tools` and `:enabled-tools` are retired and
> ignored; selection lives in `:resources` (see [Resource
> selection](#resource-selection)). `kmet plugins` edits composition rows,
> `/config` edits the persistent selection, and `/tools` is the session-only
> tool selector. The retired shapes are recorded under [Retired selection
> shapes](#retired-selection-shapes) for migration reference.

The built-in tools are `read`, `write`, `edit`, `bash`, `tool_search`,
`run_code` and `eval`. All but the last two start enabled; `run_code` and
`eval` are mounted but opt-in (see below).
Each is a composition row — `:tool/read`, `:tool/bash`, … in the built-in
bundle — so a profile or a patch layer can take one out of the run entirely
(`{:id :tool/bash :remove true}`: the tool is not in the model's schema and not
in the `run_code` surface). The retired `:default-tools` key was the selection
on top of that; the runtime now applies the `:resources` selection instead.

A tool row's `:config` changes what the model **reads**, never what the tool
does. Four keys are accepted — `:label`, `:description`, `:prompt-snippet` and
`:prompt-guidelines` — and merge over the tool's own text, so a deployment can
restate a tool for its context without replacing it:

```clojure
{:id :tool/bash
 :config {:description "Run a shell command on this host's bash 5."}}
```

The tool's behavior stays the plugin's: its `:name` (the row id and the model's
spelling of the tool), its parameters and its execution are not configurable. An
unknown key or a wrongly shaped value fails the mount by name rather than
leaving the default in place as if the override had applied.

### Retired selection shapes

The `:default-tools` and `:enabled-tools` keys are ignored. For migration
reference, they used to resolve as follows.

```clojure
:default-tools ["read" "bash"]   ; plain names: only these built-ins
:default-tools ["-bash"]         ; +name/-name modify the built-in set
:default-tools ["read" "+bash"]  ; mixed: plain names form the selection first
```

Plain names replaced the built-in selection; `+name` added a tool and `-name`
removed one, applied in list order. A mixed list used its plain names as the
selection and then applied the modifiers. An empty list disabled every
default-on built-in tool (extension tools stayed available). An unset
`:default-tools` selected `default-tool-names` — every built-in row without
`:default-enabled? false` — so `run_code` and `eval` stayed off. Those two rows
carry that marker: they are mounted, and the retired settings selection could
enable them, while `/config` enables them persistently and `/tools` enables
them for the current session only.

`:enabled-tools` was the absolute selection over **every live tool**, built-in
and extension alike, and it won over `:default-tools`:

```clojure
:enabled-tools ["read" "grep"]       ; only these live tools
:enabled-tools ["-bash" "+my_tool"]  ; the live set, minus bash, plus my_tool
```

A plain list was absolute: a tool that mounted later was not added. A
modifier-only list was re-resolved against the live table, so a tool that
mounted later defaulted on (an extension or MCP catalog that connects after
startup was not silently dropped). An empty list selected no model-facing tools
(a `:sandbox` source's tools stay callable from `run_code`). Project settings
layered over user settings for both keys: a `.kmet/settings.edn` list of only
`+name`/`-name` entries modified the user's list, while a list containing a
plain name replaced it.

An extension tool source may be *deferred*: its tools are callable from
`run_code` but not declared to the model until the built-in `tool_search`
loads one. `tool_search` itself is declared only while the selection can
promote something — a deferred tool or a contributed sandbox tool — so a setup
with no such source pays no schema for it. It is a selection row like any
other: naming it out of the `:resources` `:tools` selection (or removing its
row with `{:id :tool/tool_search :remove true}`) leaves every deferred source
callable from scripts but no longer discoverable by the model. Nothing else
can promote one, so the run warns about the orphaned sources at startup.

The leading system prompt's Available-tools list is not rebuilt when a tool is
promoted; it is rebuilt when the selection changes (`/config`, `/tools`), on `/reload` and
on a session switch. A promoted tool therefore appears in the conversation's
appended source listing and in the request's tool declarations, but not in that
list until one of those happens.

The declared set is recorded in the session (`:tool-change` entries carrying
the declarations by value), so a resumed or forked session sends what it was
sent rather than re-deriving the list from the current registry. Each entry
projects to a wire-only marker at its recorded position. On Anthropic models
that accept mid-conversation tool changes, on Responses models with
`additional_tools`/tool search, and on Kimi completions, the tool array is
declared once at the front of the cached prefix and a later change rides where
it was recorded (`tool_addition`/`tool_removal`, `additional_tools`, or a
tool-bearing system message), so the array itself is never re-sent and the
message list stays a growing prefix. Every other wire drops the markers and
re-sends the current array; that is a fallback, not a regression.

## HTTP and reload

`:http-idle-timeout-ms` (default 120000, pi: `httpIdleTimeoutMs`) is the LLM
stream idle deadline: a stream that receives no bytes for this long errors
retryably (undici bodyTimeout semantics). It also bounds the wait for the
response itself (pi: undici headersTimeout = `httpIdleTimeoutMs`), so a
provider that accepts the request but never starts responding fails after the
idle deadline instead of hanging the turn. `0` disables both.

`:http-total-timeout-ms` is the whole-request deadline. The default `0`
disables it; `nil` tracks the idle timeout, and a positive value sets an
independent deadline. Disabling the total deadline does not disable the
per-byte idle timeout or the response-start deadline.

`/reload` re-reads settings, reloads extensions/skills/prompts/themes, re-discovers
context files, and rebuilds the system prompt (pi: `session.reload`). It refuses
while a response is streaming.

## Prompt caching

Every request asks the provider to cache its prefix, so a repeated
conversation re-reads from the cache instead of re-billing the whole
prompt. What is sent depends on the wire: `anthropic-messages` places
`cache_control` breakpoints on the system block, the last tool and the last
user block (three of Anthropic's four); `openai-responses` sends
`prompt_cache_key`; `openai-completions` sends `prompt_cache_key` on
`api.openai.com` and, for OpenRouter's `anthropic/*` models, the same three
`cache_control` breakpoints in the Anthropic wire format; the retained cache
window is 5 minutes by default.

`KMET_CACHE_RETENTION` changes that host-wide. `long` asks for the longer
retention where the model supports it (`ttl: "1h"` on the Anthropic wires
and OpenRouter's anthropic-format models, `prompt_cache_retention: "24h"` on
`openai-responses` and `openai-completions`; a model whose compat declares
`:supports-explicit-prompt-cache-mode` asks for a 30-minute window in
`prompt_cache_options` instead). An Anthropic 1-hour cache write is priced at
twice the base input rate. `none` turns caching off — no markers, no cache
key, no session-affinity headers (an explicit-mode model still sends its
`{mode "explicit"}` option). Compaction summaries always ask for `none`: they
are read once.

The cache half of the accounting was already there — `cache_read` and
`cache_write` tokens and their cost — so `/cost`, the footer and the
session totals show what the cache actually saved.

## Context compaction

Compaction (pi-compatible): proactive compaction triggers before a run when
the measured context usage (latest response's reported usage + a chars/4
estimate of newer entries) comes within `:compact-reserve-tokens` (default
16384) of the model's context window. An explicit `:compact-token-threshold`
(estimated tokens) can override it. Context-overflow errors compact once then
retry. The pre-cut conversation is summarized via the LLM (structured
Goal/Progress/Next-Steps checkpoint, updated on subsequent compactions) and
replaced with a summary entry; `:keep-recent-tokens` (default 20000) sets how
many recent tokens to keep. The summary's own output is capped at
`0.8 ×` the effective reserve, plus the session level's thinking budget
when the model reasons, bounded by the model's maximum output tokens (pi:
`adjustMaxTokensForThinking`). The token budgets can be tuned per model
with `:compact-model-overrides`, a map keyed by the exact
`"provider/modelId"` (e.g. `"opencode-go/deepseek-v4.1-flash"`; internal
slashes are part of the key). Each entry takes `:reserve-tokens` and/or
`:keep-recent-tokens` and falls back per field to the ordinary setting and
then the built-in default (pi: `compaction.modelOverrides`) — a 1M-window
model can reserve room for its responses without changing every other
model:

```edn
{:compact-model-overrides
 {"commandcode/deepseek/deepseek-v4.1-flash" {:reserve-tokens 400000}}}
```

`/compact [instructions]` triggers it manually. A `/compact` that arrives
while a turn is running is deferred and starts when the turn settles
(the RPC `compact` command answers `:scheduled true` for it).
Escape aborts an in-progress compaction without cancelling the running turn
(the turn continues on the pre-compaction context, pi: `abortCompaction`).
