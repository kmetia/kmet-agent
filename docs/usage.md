# Usage

```sh
# Interactive TUI
bb run
jolt run -m kmet.core     # ...or on the Jolt host

# Or invoke the checked-in entry script explicitly (works on Termux too)
bb kmet

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
| `/settings` | Settings menu — thinking, display, tool display, steering/follow-up mode, HTTP transport + timeouts, auto-compact, retry, repeat guard, images, skill commands, terminal progress, clear on shrink, theme (single or automatic light/dark) |
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
