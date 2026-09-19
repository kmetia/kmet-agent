# kmet

A minimal coding agent built in Clojure/Babashka, featuring a terminal user interface (TUI)
with differential rendering, LLM integration, and tool execution.

Inspired by [pi](https://pi.dev) — a terminal-based AI coding agent.

## The name

*kmet* (Cyrillic **кмет**) is an old Slavic word. It goes back to Proto-Slavic `*kъmetь`
and is present in the oldest Slavic texts — Old Church Slavonic *къметь*, "a man of note",
the Old Russian *къметь*, "warrior, retainer" — and it survives in Serbian, Bulgarian,
Macedonian, Slovene, Czech, Slovak, Polish and Ukrainian.

Across the family it holds two opposite senses at once. Serbian has both: the *kmet* is the
village elder, the respected first man of a community, the people's judge in Montenegro — and
the *kmet* is also the bondsman, the serf bound to the land he works. The other languages
tend to keep one half: Bulgarian and Macedonian кмет is *mayor*; the Upper Sorbian *kmjeć* is
the "first peasant" who leads his neighbors; Polish *kmieć* is the established landholder;
Czech *kmet* and Slovak *kmeť* are the old men whose counsel the village keeps; Slovene *kmet*
is simply *farmer*, his *kmetija* the farm; Ukrainian *кміть* the working peasant.

So a kmet is the man of the ground from either end: the one who answers for the community,
and the one who works it.

A coding agent lives in both halves of that word. It leads the work in front of it — reads
the tree, orders the steps, dispatches the tools: the headman of the session. And it is a
servant: a retainer with its hands in the dirt of an estate it will never own. Holding both
stations in one word is the right posture for a tool given real autonomy — authority enough
to act on your behalf, never enough to forget whose code it is. (The italic **К** opening
the footer's first line is this word's initial.)

## Overview

kmet provides an interactive terminal UI where you can chat with any of 40
cataloged LLM providers (opencode-go, deepseek, anthropic, google, openai,
openrouter, mistral, bedrock, ...) — the model registry, catalogs, auth and
wire APIs are a port of pi's provider subsystem (`src/kmet/ai/`). The
built-in tools are read, write, edit and bash; skills, extensions and prompt
templates add more (the shipped `extensions/` add structural code tools, MCP
servers and LSP). Fully cross-platform: runs on Linux, macOS, Windows, WSL,
and Termux (Android).

### Features

- **TUI Framework** — differential rendering, component model, overlays, raw-mode input
  ([package docs](src/kmet/tui/tui.md))
- **Multi-line Editor** — word-wrap, undo/redo, kill-ring, history, paste markers
- **Chat History** — user/assistant/tool message display with scrolling
- **Agent Loop** — orchestrates LLM calls and tool execution with streaming
- **Config System** — `~/.kmet/agent/settings.edn` and `.kmet/settings.edn` overrides
- **Theme System** — customizable ANSI color themes from EDN files
- **Session Persistence** — EDNL files with branching support
- **Skills & Extensions** — markdown skills and Clojure extensions
- **Provider Subsystem** — 40 generated provider catalogs (a pi-faithful
  port), `--list-models`, model resolution/cycling, cost display, custom
  providers via `models.edn`, OAuth logins (Copilot, Codex, Anthropic,
  OpenRouter), and an image-model registry (`kmet.ai.image-models`)
- **Cross-platform** — Linux, macOS, Windows, WSL, and Termux (Android)

## Prerequisites

- [Babashka](https://babashka.org/) ≥ 1.13.222 (bundles JLine 4.4.0) — the
  primary host
- [Jolt](https://github.com/jolt-lang/jolt) ≥ v0.8.6-98 — optional: the same
  code runs natively on jolt (`jolt run -m kmet.core`, `jolt dist`; see
  `jolt-port.md`)
- API keys: `OPENCODE_API_KEY` (opencode-go/opencode), `DEEPSEEK_API_KEY`,
  `OPENAI_API_KEY` (openai), `XAI_API_KEY` (xai), `AZURE_OPENAI_API_KEY`
  (azure-openai-responses; base URL/deployment from `AZURE_OPENAI_BASE_URL` /
  `AZURE_OPENAI_RESOURCE_NAME` / `AZURE_OPENAI_DEPLOYMENT_NAME_MAP`),
  `COPILOT_GITHUB_TOKEN`,
  `ANTHROPIC_AUTH_TOKEN`/`ANTHROPIC_OAUTH_TOKEN`/`ANTHROPIC_API_KEY`
  (anthropic), `GEMINI_API_KEY` (google), `GROQ_API_KEY` (groq),
  `CEREBRAS_API_KEY` (cerebras), `HF_TOKEN` (huggingface),
  `MOONSHOT_API_KEY` (moonshotai), `XIAOMI_API_KEY` + `XIAOMI_TOKEN_PLAN_*_API_KEY`
  (xiaomi token plans), `QWEN_TOKEN_PLAN_API_KEY` (+ `_CN_`), `MINIMAX_API_KEY` /
  `MINIMAX_CN_API_KEY`, `NVIDIA_API_KEY` (nvidia), `OPENROUTER_API_KEY`
  (openrouter), `FIREWORKS_API_KEY` (fireworks), `AI_GATEWAY_API_KEY`
  (vercel-ai-gateway), `ZAI_API_KEY`/`ZAI_CODING_CN_API_KEY` (zai),
  `TOGETHER_API_KEY` (together), `BASETEN_API_KEY` (baseten),
  `ANT_LING_API_KEY` (ant-ling), `KIMI_API_KEY` (kimi-coding),
  `CMD_API_KEY` (commandcode), `CLOUDFLARE_API_KEY` +
  `CLOUDFLARE_ACCOUNT_ID`/`CLOUDFLARE_GATEWAY_ID`
  (cloudflare workers-ai / ai-gateway), `MISTRAL_API_KEY` (mistral),
  `GOOGLE_CLOUD_API_KEY` (google-vertex; or Application Default
  Credentials — `GOOGLE_APPLICATION_CREDENTIALS` + project + location) —
  or `/login` inside kmet to store `auth.edn` credentials; `/logout`
  removes them. Amazon Bedrock authenticates via the ambient AWS
  credential sources (`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`,
  `AWS_PROFILE`, `AWS_BEARER_TOKEN_BEDROCK`, ECS/IRSA vars). OAuth
  subscription logins (`/login <provider>`): GitHub Copilot device-code
  ("Sign in with an account"), OpenAI Codex (browser PKCE loopback or
  device code), Anthropic Claude Pro/Max (browser PKCE loopback), and
  OpenRouter (browser PKCE loopback, permanent key).

## Usage

```sh
# Interactive TUI
bb run
jolt run -m kmet.core     # ...or on the Jolt host

# Or via the checked-in entry script
./kmet

# With options
bb run --model deepseek-v4-flash --provider opencode-go

# Non-interactive mode
bb run --print "list files in current directory"

# Attach files to the initial message (@file args)
bb run @tasks.md "summarize the tasks"
```

### Command-line options

```
  -d, --debug           Log to debug.log
  -p, --print           Print response and exit (non-interactive)
  -c, --continue        Continue most recent session
  -r, --resume          Browse sessions
  --session <id|path>   Resume session by id/prefix or file path
  --session-dir <dir>   Session storage directory
  --model <id>          Model to use (pattern: provider/model[:thinking])
  --provider <name>     Provider (opencode-go, opencode, deepseek, github-copilot,
                        openai, xai, openai-codex, azure-openai-responses,
                        anthropic, google, groq, cerebras, huggingface,
                        moonshotai, xiaomi, qwen-token-plan, minimax, nvidia,
                        openrouter, fireworks, vercel-ai-gateway, ...)
  --models <patterns>   Comma-separated model patterns for Ctrl+P cycling
  --list-models [search] List available models (with optional fuzzy search)
  --generate-models     Fetch current provider catalogs (models.dev + live
                        sources) into ~/.kmet/agent/models-cache and exit;
                        used at startup when newer than the built-in data
  --system-prompt <txt> Replace the system prompt (or a path to read it from)
  --append-system-prompt <txt> Append to the system prompt (repeatable)
  -t, --thinking <level> Thinking level (off, minimal, low, medium, high, xhigh, max)
  --version             Print the version and exit
  -h, --help            Show this help
```

Positional `@file` args attach file content to the initial message; the
remaining positional args form it. Unknown `--flags` are exposed to extensions.

### Package subcommands

Install, remove, list and configure **packages** — extensions, skills,
prompt templates and themes bundled in a local directory or file (pi's
`install`/`remove`/`list`/`config` with local sources; npm/git package
installs are not ported):

```sh
kmet install ./path/to/package [-l]   # add a local dir/file package to settings
kmet remove ./path/to/package [-l]    # remove it again (alias: kmet uninstall)
kmet list                             # show configured packages
kmet config [-l]                      # enable/disable resources (TUI)
```

- A **file** source loads as a single extension; a **directory** loads as one
extension (contains `extension.edn`) or scans its conventional `extensions/`,
`skills/`, `prompts/`, `themes/` subdirectories (pi package rules).
- Sources are recorded in `:packages` in `~/.kmet/agent/settings.edn` (global)
or `.kmet/settings.edn` with `-l` (project), stored relative to the settings
file. Configured packages load at startup and on `/reload`, after the auto
resource dirs.
- `kmet config` opens a TUI listing every discovered resource — packages,
  top-level settings entries and the auto dirs — with a checkbox; space
  toggles, Tab switches global/project scope (project scope cycles
  inherit/load/unload), typing filters, escape closes. Package writes are
  per-type `+path`/`-path` filter entries on the package (pi's object
  entries); top-level/auto resources toggle via the scope's settings
  resource arrays (`:extensions`/`:skills`/`:prompts`/`:themes`).
  Single-extension packages (a file source or an `extension.edn` directory)
  ignore those filters, so their rows are marked *always loaded* and cannot
  be toggled.

### In-TUI commands

| Command | Description |
|---------|-------------|
| `/help` | Show available commands and shortcuts |
| `/hotkeys` | Show all keyboard shortcuts — the bindings the app actually wired, resolved live (keybindings.edn overrides and extension shortcuts included) |
| `/quit` | Exit kmet |
| `/model <provider:model[:thinking]>` | Switch model (Ctrl+L opens a selector; an unmatched term opens the selector pre-filled with it) |
| `/thinking [level]` | Set thinking level — bare: selector with search, ✓ current, `· default` marker (Enter selects, Ctrl+S sets as default); with a level arg: apply it directly |
| `/scoped-models` | Enable/disable/reorder the models Ctrl+P cycles through (Ctrl+S saves to settings) |
| `/settings` | Settings menu — thinking, display, tool display, steering/follow-up mode, HTTP transport + timeouts, auto-compact, retry, repeat guard, images, theme |
| `/tools` | List available tools with parameters |
| `/login [provider]` | Configure provider auth — API key, or OAuth: Copilot device-code, Codex browser/device, Anthropic & OpenRouter browser PKCE |
| `/logout [provider]` | Remove stored provider credentials |
| `/new` | Start a new session |
| `/resume` | Browse past sessions |
| `/continue` | Continue where the agent left off (e.g. after a network error) |
| `/followup <message>` | Queue a follow-up message (like Alt+Enter) |
| `/tree` | Navigate the session entry tree (switch branches) |
| `/fork` | Create a new fork from a previous user message |
| `/clone` | Duplicate the current session at the current position |
| `/name <name>` | Set the session display name |
| `/session` | Show session info and stats |
| `/export [path]` | Export the session to HTML |
| `/import <path>` | Import a session file into the sessions directory and resume it (confirm first; a taken name is suffixed) |
| `/share` | Share the session as a secret GitHub gist |
| `/copy` | Copy the last agent message to the clipboard |
| `/compact [instructions]` | Manually compact the session context |
| `/reload` | Reload settings, keybindings, extensions, skills, prompts, themes and context files |
| `/theme <name>` | Switch color theme |

`/skill:<name>` loads a skill on demand; any other `/command args` expands a
prompt template. kmet's sessions are EDN (`.ednl`), so `/import` takes a kmet
session file — pi's JSONL sessions are not importable.

Sessions record their working directory: resuming or importing a session from
another project moves the runtime there — tools resolve relative paths in it,
the system prompt's `Current working directory` line and the footer pwd follow,
path completion completes there, and new sessions land in that project's
sessions dir (pi: `createRuntime`'s cwd). A recorded directory that no longer
exists keeps the current one. The other project's *configuration* is not part
of that move: `.kmet/settings.edn`, project context files (AGENTS.md/CLAUDE.md),
project-scoped extensions/skills/prompts/themes and project packages stay with
the directory kmet was launched in — a switch imports the session's transcript
(and its working directory), never the other project's configuration.
`/reload` refreshes that launch-directory setup; it does not adopt the
active session's project.

### Keyboard shortcuts

| Key | Action |
|-----|--------|
| `Enter` | Submit message |
| `Escape` | Cancel the current turn / running bash |
| `Ctrl+C` | Clear editor (twice to quit) |
| `Ctrl+D` | Exit when editor is empty |
| `Ctrl+Q` | Quit kmet (works anywhere) |
| `Ctrl+L` | Select model |
| `Ctrl+P` / `Shift+Ctrl+P` | Cycle scoped models (`--models` / `/scoped-models`) |
| `Shift+Tab` | Cycle thinking level |
| `Ctrl+T` | Toggle thinking blocks |
| `Ctrl+O` | Cycle tool display — collapsed / expanded / quiet |
| `Ctrl+G` | Open the external editor |
| `Alt+Enter` / `Alt+Up` | Queue a follow-up message / restore queued messages |
| Mouse wheel / terminal scroll | Browse history — the transcript lives in the terminal's own scrollback |

Type `/hotkeys` for the full list — it resolves the live bindings the app
has actually wired (including `keybindings.edn` overrides) and adds a section
for extension-registered shortcuts.

## Project Structure

```
src/kmet/
├── core.clj            — CLI entry, arg parsing, mode dispatch
├── config.clj          — Configuration loading (settings.edn, env vars)
├── debug.clj           — Debug/error logging
├── extension.clj       — the extension contract root (`kmet.extension`)
├── package_manager.clj — the install/remove/list/config CLI
├── libs/               — Generic, self-contained helpers (diff, process tree,
│                         SSE parsing, the outbound-HTTP boundary, terminal
│                         protocol + images, YAML frontmatter, EDN store +
│                         file locks, hashing, highlighting, markdown,
│                         crypto, AWS SigV4, ...)
├── modes/              — Entry modes: interactive TUI + print mode
├── ai/                 — Provider/auth subsystem (pi: packages/ai — a standalone
│   │                     library the agent depends on; self-contained, see AGENTS.md)
│   ├── models.clj      — Provider/model registry + committed EDN catalogs
│   │                     (model_data/), cost, catalog loading
│   ├── model_config.clj / provider_composer.clj — models.edn custom
│   │                     providers + config/extension layer composition
│   ├── model_gen.clj   — the catalog generator (bb generate-models)
│   ├── auth.clj        — env-var table, auth.edn, credential resolution
│   ├── oauth.clj       — OAuthAuth record, device-code + PKCE loopback flows
│   ├── llm.clj + api/  — LLM dispatcher + per-wire API builders
│   │                     (openai/anthropic/google/responses/codex/azure/
│   │                     bedrock/vertex/mistral)
│   ├── image_models.clj — image-generation registry + :openrouter-images
│   │                     wire (image_model_data/ catalog)
│   ├── google_adc.clj  — vertex Application Default Credentials
│   ├── attribution.clj / constrained_sampling.clj / hooks.clj —
│   │                     provider attribution headers, tool JSON-schema
│   │                     constraints, injectable provider-event slots
│   ├── http.clj        — provider streams over the kmet.libs.http boundary
├── app/                — App business logic (pi: dist/core/)
│   ├── loop.clj        — the agent loop; session.clj — EDNL sessions
│   ├── commands.clj / keybindings.clj / packages.clj / extensions.cljc —
│   │                     slash commands, keymap, package + extension loading
│   ├── model_resolver.clj — model pattern/CLI resolution
│   ├── tools/          — built-in tools: read, write, edit, bash
│   │                     (grep/find/ls ship as opt-in extensions in
│   │                     extensions/grep-tool.clj, find-tool.clj, ls-tool.clj)
│   └── ui/             — app TUI components (chat history, footer, ...)
├── tui/                — Generic TUI library (pi: @earendil-works/pi-tui;
│   │                     usage docs in src/kmet/tui/tui.md)
│   └── components/     — text, input, editor, markdown, select/settings
│                         lists, spinner, image, stack layouts, ...
```

```
tasks/kmet/tasks/       — every bb-task implementation (a classpath root, not
                          part of the app): packagers (uberjar/dist/pack-extension),
                          the catalog generators, and the dev loop (changed,
                          test runner, clean, lint). Neither artifact carries
                          it — the uberjar walks src/, jolt embeds src/.
test/kmet/tasks/        — their tests
```

The full annotated layout (every file) lives in `AGENTS.md`.

## Configuration

Settings are loaded from:

1. `~/.kmet/agent/settings.edn` — user-wide settings
2. `.kmet/settings.edn` — project-local overrides
3. Environment variables: `KMET_PROVIDER`, `KMET_MODEL`

Example `~/.kmet/agent/settings.edn`:

```clojure
{:provider :opencode-go
 :model "deepseek-v4-flash"
 :theme "dark"
 :thinking :off
 :session-dir "~/.kmet/sessions"
 :http-idle-timeout-ms 300000   ; LLM stream idle + total deadline in ms; 0 disables
 :http-transport :platform      ; :platform (default) = babashka.http-client on both hosts,
                                ; curl only for SOCKS/https-scheme proxies; :curl = everything through curl
 :http-total-timeout-ms nil     ; whole-request deadline in ms; nil = the idle timeout, 0 disables
 :shell-command-prefix nil      ; line prepended to every bash command, e.g. "shopt -s expand_aliases"; nil = none
 :shell-path nil                ; custom shell binary for bash execution (e.g. Cygwin/Git Bash on Windows); a leading ~ expands
 :system-prompt "You are a helpful assistant."   ; replaces the default system prompt
 :append-system-prompt "Follow the project conventions." ; appended after it}
```

`kmet` reads its provider catalog from `src/kmet/ai/model_data/*.edn`
(40 providers: opencode-go, deepseek, anthropic, google, groq, cerebras,
openrouter, nvidia, moonshotai, qwen-token-plan, minimax, fireworks,
vercel-ai-gateway, zai, together, baseten, kimi-coding, cloudflare, mistral,
google-vertex, amazon-bedrock, ...; generated from models.dev + live
catalogs — `bb generate-models`). A user-level cache can be refreshed
without touching the repo: `kmet --generate-models` runs the same generator
into `~/.kmet/agent/models-cache/` (one `<provider>.edn` per provider +
`manifest.edn`, same layout as the built-ins), and that cache replaces the
built-in catalogs whenever it is strictly newer — so an upgrade shipping
newer bundled data wins over a stale cache until the next refresh.
Reruns with no upstream changes rewrite nothing. The image-model catalog
lives in `src/kmet/ai/image_model_data/image-models.edn`
(`bb generate-image-models`).
Models, base URLs and defaults are registry data; custom providers, API keys
and model overrides go in `~/.kmet/agent/models.edn`. OpenAI-completions
wire compatibility (thinking format, max-tokens field, reasoning
round-trip) auto-detects from the provider id and base URL; explicit
`:compat` entries in models.edn override the detected defaults.

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

`:http-idle-timeout-ms` (default 300000, pi: `httpIdleTimeoutMs`) is the LLM
stream deadline: a stream that receives no bytes for this long errors retryably
(undici bodyTimeout semantics), and the same value bounds the total request
(SDK `timeoutMs`). `0` disables both.

`/reload` re-reads settings, reloads extensions/skills/prompts/themes, re-discovers
context files, and rebuilds the system prompt (pi: `session.reload`). It refuses
while a response is streaming.

Compaction (pi-compatible): proactive compaction triggers before a run when
the measured context usage (latest response's reported usage + a chars/4
estimate of newer entries) comes within `:compact-reserve-tokens` (default
16384) of the model's context window. An explicit `:compact-token-threshold`
(estimated tokens) can override it. Context-overflow errors compact once then
retry. The pre-cut conversation is summarized via the LLM (structured
Goal/Progress/Next-Steps checkpoint, updated on subsequent compactions) and
replaced with a summary entry; `:keep-recent-tokens` (default 20000) sets how
many recent tokens to keep. `/compact [instructions]` triggers it manually.

## Themes

Create EDN theme files in `~/.kmet/agent/themes/`. See `examples/themes/` for format.

## Skills & Extensions

- **Packages**: share any mix of extensions, skills, prompt templates and
  themes as a local directory or file. `kmet install ./package [-l]` records
  the source in the settings `:packages` list; a package directory loads as a
  single extension (contains `extension.edn`) or scans its conventional
  `extensions/`, `skills/`, `prompts/`, `themes/` subdirectories. Filter what a
  package loads with the object form — `{:source "../pkg" :extensions
  ["extensions/*.clj" "!extensions/legacy.clj"]}` (plain globs include,
  `!` excludes, `+path`/`-path` force include/exclude, `[]` disables a type)
  or use `kmet config`. The same top-level arrays
  (`:extensions`/`:skills`/`:prompts`/`:themes`) select exactly which
  auto-root resources stay enabled without installing a package. A project entry with `:autoload false` is a delta over
  the global entry of the same package (pi's package model — same pattern
  semantics as pi's `packages.md`).
- **Skills**: Place `name/SKILL.md` directories (or flat `.md` files) with YAML frontmatter (`name`, `description`) in `~/.kmet/agent/skills/` or `.kmet/skills/` — listed in the system prompt as `<available_skills>`; `/skill:name` loads one on demand (Agent Skills standard, pi-compatible)
- **Prompt Templates**: Place `.md` files in `~/.kmet/agent/prompts/` or `.kmet/prompts/` — `/name args` expands to the template body with `$1`, `$@`, `${1:-default}`, `${@:N}` placeholders; unknown `/cmd` falls through to the agent (pi-compatible)
- **Extensions**: Place `.clj` files (or directories with `extension.edn`) in
  `~/.kmet/agent/extensions/` or `.kmet/extensions/` — the fixed auto roots;
  extra paths go in top-level `:extensions` settings entries (`KMET_CODING_AGENT_DIR`
  moves the agent dir). An extension is a Clojure
  namespace defining `(defn init [api])` (and optionally `(defn shutdown [api])`),
  depending only on `kmet.extension`. A manifest dir declares `{:name :entry
  :files}` — its own source deps. Loaded at startup, reloadable via `/reload`, and
  unloadable at runtime; unload runs shutdown + deregisters everything.
  Shipped opt-in extensions live in [`extensions/`](extensions/)
  ([usage](extensions/README.md)); writing your own:
  [`extensions/extensions.md`](extensions/extensions.md)

## Development

```sh
bb run             # Interactive TUI
bb test            # Run fast test suites (excludes ^:slow tests)
bb test-ext        # Run only the slow (^:slow) test suites
bb lint            # clj-kondo over both reader views (babashka + jolt);
                   # `jolt lint` runs the same gate
bb format          # cljfmt (fix) / bb format-check (verify)
bb clean           # Remove build artifacts, caches, logs (--dry-run: list only)
bb nrepl           # Start an nREPL server on port 1667 (blocks)
bb check           # Verify all source namespaces compile
bb generate-models     # Regenerate provider catalogs (network)
kmet --generate-models # Refresh the user-level catalog cache (network)
bb generate-image-models # Regenerate the image model catalog (network)
bb check-model-data      # Offline catalog validation
bb pack-extension <src-dir> [out.jar]  # Verify + pack an extension artifact root
bb help            # Show task help
```

The `*-changed` tasks are the iteration loop — they cover only the current
changes (git diff vs HEAD + untracked, plus the namespaces/tests that
transitively require them via the require graph); `jolt` runs the same tasks:

```sh
bb changed              # List changed files
bb test-changed         # Tests of affected namespaces (non-slow)
bb test-ext-changed     # ...only the slow ones
bb lint-changed         # Lint changed files plus affected dependents
bb format-check-changed # (or bb format-changed to write the fix)
```

The full gates — `bb test`, `bb test-ext`, `bb lint` (0 findings required) and
`bb format-check` — are slow; run them before committing.

## Building

kmet ships as self-contained executables, packaged by the host-dispatched
`dist` task: `bb dist` on babashka, `jolt dist` on jolt — same name, two
packagers, artifacts side by side in `dist/`.

On babashka the artifact is an official babashka release binary with the kmet
uberjar appended (babashka detects the appended zip at startup and runs
`kmet.core/-main` — see babashka's "Self-contained executable" wiki page).
Cross-builds work from any host because packaging is download + concat only.

```sh
bb uberjar              # Build target/kmet.jar (also runnable: bb target/kmet.jar)
bb dist                 # Executable for the current platform -> dist/
bb dist --all           # Every published platform
bb dist macos-aarch64   # Explicit platforms; --force re-downloads, --smoke runs the post-build check
```

On jolt the same task AOT-compiles the app through
`jolt build -m kmet.core` — runtime, stdlib, dependencies and kmet in one
native binary, with the model catalogs embedded:

```sh
jolt dist               # Native binary for the current platform -> dist/
jolt dist --dev         # Unoptimized build (--opt for optimized; release is the default)
jolt dist --target tarm64le --target-pack /tmp/pack   # Cross-compile (tools/cross-compile)
```

`jolt dist --help` lists every option: build modes, `--boot fast|small|plain`
(startup versus size), `--closed-world` and `--dynamic`, cross-compiling with
`--target`/`--target-pack`, `-o PATH`, `--force`, and `--smoke` to run the
freshly built binary (`--list-models` plus `--version`) before publishing.

Both hosts name artifacts by one scheme:
`kmet-<version>-<host><host-version>-<platform>[-dev]` (plus `.exe` on
Windows; `-dev` marks a jolt `--dev` build), so one `dist/` lines the hosts up
per platform — `kmet-0.8.0-56-g63374117-bb1.13.222-linux-amd64` next to
`kmet-0.8.0-56-g63374117-jolt0.8.6-86-g234f460b-linux-amd64`. The platform is
the jolt-style `<os>-<arch>` name (`linux-amd64`, `linux-aarch64`,
`macos-amd64`, `macos-aarch64`, `windows-amd64`; jolt can also cross-compile
`windows-aarch64`), shared by both packagers: babashka targets are its five
platforms, with babashka's statically linked linux release assets behind
`linux-amd64`/`linux-aarch64` (a release asset slug like
`linux-amd64-static` is accepted too). Babashka binaries are cached in
`target/build-cache/` (sha256-verified on download) and `bb dist` always
rebuilds a fresh `target/kmet.jar` first so artifacts never bundle stale
sources. Downloads use `curl` (preinstalled on Termux, macOS, Linux and
Windows 10+). Jolt compiles under `target/jolt/<platform>/<mode>/`; `--smoke` runs the freshly
built binary (`--list-models` plus `--version`) before it is announced.

Versioning: artifacts are stamped with **jolt's checkout rule**
(`kmet.libs.version`, jolt's `tools/version.sh`), so the base version reads
like the compiler's — the nearest `v<digit>` release tag as
`git describe --tags --dirty` reports it (`v0.8.0` on the tag,
`v0.8.0-56-g63374117` past it, `-dirty` with uncommitted edits), `dev-g<sha>`
when no release tag is reachable (a bare sha would read as version 0 to
anything parsing leading digits), and `dev` outside a git checkout. Rolling
tags such as `vnightly` never match. Artifact names carry the same string with
the `v` stripped; `kmet --version` prints it, from the version baked at build
time when running a packaged binary and from git in a source run (`bb run` /
`jolt run`).

**Termux/Android**: the glibc-linker problem applies to both hosts — a glibc
binary must be exec'd through Termux's glibc dynamic linker, which also
disables babashka's own appended-jar auto-detection. Building on a termux host
therefore additionally emits a companion `.sh` launcher next to the artifact
(e.g. `kmet-<version>-bb<bb-version>-<platform>.sh`) that unsets `LD_PRELOAD`, execs via
`$PREFIX/glibc/lib/ld-linux-*.so.1` (plus `--jar <self>` for the babashka
binary). It requires the termux glibc package (`pkg install glibc-repo && pkg
install glibc`).

## License

Copyright © 2026 — present Marko Kocic <marko@euptera.com>

Licensed under the Eclipse Public License 2.0 (EPL-2.0).
