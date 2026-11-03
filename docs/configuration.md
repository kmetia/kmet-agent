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
in a layer or toggle it in `kmet config`. Skills, prompts and themes remain
settings-backed resources. `:default-tools` still selects built-in tools in
settings (see [Tools](#tools)).

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

The built-in tools (`read`, `write`, `edit`, `bash`, `run_code`) start enabled.
Each is a composition row — `:tool/read`, `:tool/bash`, … in the built-in
bundle — so a profile or a patch layer can take one out of the run entirely
(`{:id :tool/bash :remove true}`: the tool is not in the model's schema and not
in the `run_code` surface). `:default-tools` is the runtime selection on top of
that: it selects among the mounted built-in tools; extension tools stay enabled
either way.

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

```clojure
:default-tools ["read" "bash"]   ; plain names: only these built-ins
:default-tools ["-bash"]         ; +name/-name modify the built-in set
:default-tools ["read" "+bash"]  ; mixed: plain names form the selection first
```

Plain names replace the built-in selection; `+name` adds a tool and `-name`
removes one, applied in list order. A mixed list uses its plain names as the
selection and then applies the modifiers. An empty list disables every
built-in tool (extension tools stay available), and an unset
`:default-tools` enables all of them. The built-in `run_code` sandbox only
exposes the tools that are active.

Project settings layer over user settings: a `.kmet/settings.edn` list of only
`+name`/`-name` entries modifies the user's selection, while a list containing
a plain name replaces it.

`kmet config` (the resource TUI) lists the built-in tools in the
**Bundled with kmet** group, under a `Tools` subgroup before `Extensions`,
and toggles this setting: the global scope keeps a modifier-only list's shape
(disabling writes `-name`) and rewrites a plain list as the effective
selection, while the project scope writes the usual inherit/load/unload
`+name`/`-name` delta over the user list. Each item names its row
(`:tool/bash`), and a tool whose row the ambient patch layers removed shows as
off — the run does not mount it, so the setting cannot bring it back; leave the
row in and turn it off here, or restore the row and re-enable it.

The selection applies at startup, on a session switch (`/resume`, `/import` —
pi recreates the runtime from settings there, so the outgoing session's
runtime selection does not carry over), and on `/reload`, which replaces the
selection: tools newly added become active and tools removed go inactive. A
runtime selection made with the `/tools` selector (bundled `tools` extension)
wins over the setting until the next switch or reload. A tool that is not
active is also not executable — a call the model makes for a disabled tool
settles as `Tool <name> not found` (pi: `prepareToolCall`) instead of running.
kmet has no CLI equivalent of pi's `--tools`/`--exclude-tools`/`--no-tools`/
`--no-builtin-tools` yet.

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
