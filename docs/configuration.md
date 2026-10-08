# Configuration

Provider and credential details are covered in [Providers and
authentication](providers.md).

## Settings

Settings are loaded from:

1. `~/.kmet/agent/settings.edn` — user-wide settings
2. `.kmet/settings.edn` — project-local overrides
3. Environment variables: `KMET_PROVIDER`, `KMET_MODEL`

With the default paths, global settings live in
`~/.kmet/agent/settings.edn`, credentials entered with `/login` live in
`~/.kmet/agent/auth.edn`, and session transcripts live in `~/.kmet/sessions/`
unless `:session-dir` changes that location. Project settings and resources
can live under `.kmet/`; the global agent directory can be relocated with
`KMET_CODING_AGENT_DIR`.

Example `~/.kmet/agent/settings.edn`:

```clojure
{:provider :opencode-go
 :model "deepseek-v4-flash"
 :theme "dark"
 :thinking :off
 :session-dir "~/.kmet/sessions"
 :http-idle-timeout-ms 120000   ; LLM stream idle timeout in ms; 0 disables idle timeout
 :http-transport :platform      ; :platform (default) = babashka.http-client on both hosts,
                                ; curl for SOCKS/https-scheme proxies; :curl = everything through curl
 :http-total-timeout-ms 0       ; whole-request deadline in ms; 0 disables, nil follows idle
 :shell-command-prefix nil      ; line prepended to every bash command, e.g. "shopt -s expand_aliases"; nil = none
 :shell-path nil                ; custom shell binary for bash execution (e.g. Cygwin/Git Bash on Windows); a leading ~ expands
 :system-prompt "You are a helpful assistant."   ; replaces the default system prompt
 :append-system-prompt "Follow the project conventions." ; appended after it}
```

A copyable starting point with the current keys is available in
[`examples/settings.edn`](examples/settings.edn); see the
[examples guide](examples/README.md#settings) for installation and notes.

## Provider catalogs

`kmet` reads its provider catalog from `src/kmet/ai/model_data/*.edn`
(40 providers: opencode-go, deepseek, anthropic, google, groq, cerebras,
openrouter, nvidia, moonshotai, qwen-token-plan, minimax, fireworks,
vercel-ai-gateway, zai, together, baseten, kimi-coding, cloudflare, mistral,
google-vertex, amazon-bedrock, ...; generated from models.dev + live
catalogs — `bb generate-models`). A user-level cache can be refreshed
without touching the repo: `kmet --generate-models` runs the same generators
into `~/.kmet/agent/models-cache/` for the provider catalogs (one
`<provider>.edn` per provider + `manifest.edn`, same layout as the
built-ins) and `~/.kmet/agent/image-models-cache/` for the image catalog,
and each cache replaces the corresponding built-in data whenever it is
strictly newer — so an upgrade shipping newer bundled data wins over a
stale cache until the next refresh. Reruns with no upstream changes rewrite
nothing. The image-model catalog lives in
`src/kmet/ai/image_model_data/image-models.edn`.
Models, base URLs and defaults are registry data; custom providers, API keys
and model overrides go in `~/.kmet/agent/models.edn`. OpenAI-completions
wire compatibility (thinking format, max-tokens field, reasoning
round-trip) auto-detects from the provider id and base URL; explicit
`:compat` entries in models.edn override the detected defaults.

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
`:default-tools` changes that selection. It selects among built-in tools;
extension tools stay enabled either way.

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
`+name`/`-name` delta over the user list.

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
retryably (undici bodyTimeout semantics). `0` disables the idle deadline.

`:http-total-timeout-ms` is the whole-request deadline. The default `0`
disables it; `nil` tracks the idle timeout, and a positive value sets an
independent deadline. Disabling the total deadline does not disable the
per-byte idle timeout.

`/reload` re-reads settings, reloads extensions/skills/prompts/themes, re-discovers
context files, and rebuilds the system prompt (pi: `session.reload`). It refuses
while a response is streaming.

## Context compaction

Compaction (pi-compatible): proactive compaction triggers before a run when
the measured context usage (latest response's reported usage + a chars/4
estimate of newer entries) comes within `:compact-reserve-tokens` (default
16384) of the model's context window. An explicit `:compact-token-threshold`
(estimated tokens) can override it. Context-overflow errors compact once then
retry. The pre-cut conversation is summarized via the LLM (structured
Goal/Progress/Next-Steps checkpoint, updated on subsequent compactions) and
replaced with a summary entry; `:keep-recent-tokens` (default 20000) sets how
many recent tokens to keep. `/compact [instructions]` triggers it manually.
Escape aborts an in-progress compaction without cancelling the running turn
(the turn continues on the pre-compaction context, pi: `abortCompaction`).
