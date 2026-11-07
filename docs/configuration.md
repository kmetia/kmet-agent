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
settings-backed resources. Tool selection is `:resources` at runtime; the
retired `:default-tools`/`:enabled-tools` keys below are ignored (the live
`/config` screen is not implemented yet).

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

Like pi, prompt files are discovered when the config keys are unset:

- `.kmet/SYSTEM.md` or `~/.kmet/agent/SYSTEM.md` — replaces the system prompt
- `.kmet/APPEND_SYSTEM.md` or `~/.kmet/agent/APPEND_SYSTEM.md` — appended to it

Config values win over files; a config value naming an existing file is read as
content. CLI flags `--system-prompt <txt>` and `--append-system-prompt <txt>`
(repeatable) override everything.

## Tools

> **Migration note:** the runtime selection is now `kmet.app.resources`, fed by
> the `:resources` settings key. The `:default-tools` / `:enabled-tools` shapes
> documented below are retired: the runtime ignores those keys, and the
> standalone editor is now `kmet plugins`, which edits extension composition
> rows only. The builtin `/tools` command is a session-only selector; `/config`
> (the live resource selector) is not implemented yet.

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
enable them, while `/tools` enables them for the current session only.

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

The builtin `/tools` selector is session-only: its toggles apply immediately
and do not write settings, and a session switch clears the override.

`kmet plugins` is the composition editor: it lists extension rows and writes
`:ext/<id>` rows and remove patches, not tools. The builtin `/tools` selector
lists the live tool set and toggles the session override. The persisted
`:resources` selection is read by the runtime and applies at startup, on a
session switch and on `/reload`; it has no TUI editor until `/config` lands.

The runtime rebuilds the prompt over the `:resources` selection at startup,
on a session switch (`/resume`, `/import` — pi recreates the runtime from
settings there, so the outgoing session's runtime selection does not carry
over), and on `/reload`. A
runtime selection made with the builtin `/tools` selector is a separate
override: `/reload` does not clear it, so it still decides the
set until it is reset or a session switch rebuilds the runtime — the prompt is
rebuilt over that active set. A tool that is not active is also not executable
— a call the model makes for a disabled tool settles as `Tool <name> not
found` (pi: `prepareToolCall`) instead of running.
kmet has no CLI equivalent of pi's `--tools`/`--exclude-tools`/`--no-tools`/
`--no-builtin-tools` yet.

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
promoted; it is rebuilt when the selection changes (`/tools`), on `/reload` and
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
