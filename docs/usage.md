# Usage

```sh
# Interactive TUI
bb start
jolt start                # ...or on the Jolt host

# Or invoke the checked-in entry script explicitly (works on Termux too)
bb kmet

# With options
bb start --model deepseek-v4.1-flash --provider opencode-go

# Non-interactive mode
bb start --print "list files in current directory"

# Attach files to the initial message (@file args)
bb start @tasks.md "summarize the tasks"
```

### Command-line options

```
  --profile <name>      Composition profile: tui (default), print, rpc, server
                        (see profiles.md)
  -d, --debug           Log diagnostics to logs/kmet-debug.log
  --list-models [search] List available models (optional fuzzy search)
  --generate-models     Fetch current provider + image model catalogs
                        (models.dev + live sources) into ~/.kmet/agent/ and exit
  -p, --print           Run one prompt and exit (--mode text)
  --mode <name>         The older spelling of --profile; text|edn run the
                        print profile and choose its output
  --patch <file>        Apply a composition layer from a file (repeatable)
  --model <id>          Model the run's agent uses, over its settings
  --provider <name>     Provider the run's agent uses, over its settings
  --models <a,b>        Models the session's scope offers (comma separated)
  --tools <list>        Session-only tool selection (comma separated; plain names
                        or only +name/-name modifiers)
  -nc, --no-context-files  Disable AGENTS.md/CLAUDE.md discovery
  -t, --thinking <level> Thinking level (off, minimal, low, medium, high, xhigh, max)
  --system-prompt <txt> Replace the system prompt (text, or a path to a file)
  --append-system-prompt <txt> Append to the system prompt (repeatable)
  --port <n>            --profile server's socket (0, the default, asks the OS)
  --socket <path>       --profile server on a unix-domain socket where the host serves one
  --token <secret>      --profile server's bearer token (generated and announced when absent)
  --max-sessions <n>    --profile server's connection cap (default 8, 0 = no cap)
  --queue-depth <n>     --profile server's records buffered per connection (default 256)
  --idle-timeout <s>    --profile server releases a connection silent this long (default 1800, 0 = never)
  -c, --continue        Continue this directory's last session (the TUI)
  -r, --resume          Pick a session to resume (the TUI)
  --session <id>        Open a session by id, prefix or path (TUI and print)
  --session-dir <dir>   Session storage directory
  --dump-config         Print the composed plugin rows and exit
  --dump-default-config
                        Print the built-in composition without user layers or --patch
  --dump-state          Boot the composition, print the ctx tree, and exit
  --version             Print the version and exit
  -h, --help            Show this help
```

Positional `@file` args attach file content to the initial message; the
remaining positional args form it. An unsupported `--flag` is exposed to
extensions (as `--ext-`-style data for the extension host), except the retired
`--app`, which is refused by name: there is one app, so nothing selects
between two.

`--profile` decides what the run mounts — the TUI, one prompt, the rpc
protocol, or the server — and [`profiles.md`](profiles.md) covers the profiles,
the composition a run mounts, and how `--patch` edits it.

`-p/--print` resolves sessions like the TUI: `-c/--continue` resumes the most
recent session for the current directory (falling back to a fresh one when
none exists) and `--session <id|path>` resumes a specific session, by id,
id prefix, or file path. A resumed session continues with its history and
its recorded working directory. `-r/--resume` browses sessions interactively
and is TUI-only; print refuses it by name. RPC and the server open a fresh
session per run and refuse all three flags instead of silently starting the
fresh session they exist to prevent. An explicit `--session` wins over both
continuation flags in every profile: `--continue` and `--resume` are ignored
when it is present.

`--mode edn` (implies `--print`) writes the machine-readable variant to
stdout: the session header line, then every public event as one EDN map
per line (the extension-only hooks — provider request payloads/headers, the
session-before events — stay in-process).
Streaming `:message-update` events carry only the delta — no cumulative
message snapshot — and `:agent-settled` ends the stream with the run's
outcome; the exit code follows it (0 completed, 1 error or aborted).

`--profile rpc` runs the long-lived EDN protocol instead: commands on stdin,
responses and the same event stream on stdout, plus extension dialogs and
output channels. `--profile server` serves the same protocol on a socket, one
agent per connection, each with the cwd and settings its client's `:auth`
record names. See the [RPC guide](rpc.md) for the protocol and the command
reference, and [server mode](serve.md) for the handshake, the token, the
limits, and what happens to a client that stops reading.

### Package subcommands

Install, remove, update and list **packages** — extensions, skills, prompt
templates and themes bundled in a local directory or file, or in a git
repository (pi's `install`/`remove`/`update`/`list`/`config`; npm installs are
not ported). `kmet plugins` replaces pi's resource `config`: it composes
plugin rows (built-in tools and extensions), not packages:

```sh
kmet install ./path/to/package [-l]        # local dir/file package
kmet install git:github.com/user/repo [-l] # git repository (managed checkout)
kmet install git:github.com/user/repo//packages/foo  # a package dir inside it
kmet remove <source> [-l]                  # remove it (alias: kmet uninstall)
kmet update [source]                       # update installed git packages
kmet list                                  # show configured packages
kmet plugins [-l]                          # compose plugin rows (TUI)
```

`kmet config` is retired; it reports a one-line error naming `kmet plugins`.

- A **file** source loads as a single extension; a **directory** loads as one
extension (contains `extension.edn`) or scans its conventional `extensions/`,
`skills/`, `prompts/`, `themes/` subdirectories (pi package rules).
- A **git** source is `git:host/path[@ref]` (`git:git@host:path` and
  `https://…`/`ssh://…`/`git://…` URLs also work, with or without the `git:`
  prefix); `@ref`/`#ref` pins a checkout to a branch, tag, or commit. The
  repository is cloned into a managed checkout under the scope's `git/` root
  (`~/.kmet/agent/git/<host>/<path>`, or `.kmet/git/<host>/<path>` with
  `-l`) — kmet never expands or edits it.
- **Packages inside a repository**: append `//subdir` to name a directory (or
  a single file, which loads as one extension) in the checkout —
  `git:github.com/user/repo//packages/foo`,
  `git:github.com/user/repo@v1//packages/foo`. This is how a monorepo
  contributes several packages: every subdirectory source of one repository
  shares its checkout (one clone), while each `//subdir` is its own package
  with its own settings entry, filters and plugin row. `kmet install`
  verifies the directory exists, listing the checkout's directories on a
  typo — a checkout cloned solely for that failed install is removed again
  (one a sibling package or an earlier install uses stays).
- `kmet update` fetches and hard-resets a checkout: a pinned source to its
  configured ref, an unpinned one to its origin upstream (or default)
  branch. With no source, `kmet update` updates every installed git package;
  `kmet update <source>` accepts any URL form of the repository, and a
  repository URL updates all of its subdirectory packages (one fetch per
  checkout).
- `kmet remove` deletes the managed checkout together with the last settings
  entry that uses it — removing one `//subdir` package of a shared checkout
  keeps the checkout for its siblings. A configured git package that is not
  installed yet (or whose subdirectory is gone) is skipped with a warning at
  load — run `kmet install` or `kmet update`. kmet cannot self-update, and
  npm installs are not ported: pi's `--self`, `--models`, `--all`, `--force`
  and `--extension` have no equivalent (bare `kmet update` is the
  all-packages form).
- Sources are recorded in `:packages` in `~/.kmet/agent/settings.edn` (global)
or `.kmet/settings.edn` with `-l` (project); local paths are stored relative
to the settings file, git sources verbatim. Configured packages are
**discovery**: they make extensions available to the composition, and the
resources they contain (skills, prompts, themes) load as resources. Installing
an extension does not load it; compose its row (`kmet plugins` or the patch
file) and it applies on the next start.
- `kmet plugins` is a static composition editor. It lists the built-in tool
  rows (`:tool/<name>`) and the bundled and installed extension rows
  (`:ext/<id>`) with a checkbox; space composes or removes the selected row,
  Tab switches global/project scope, typing filters, escape closes. A project
  with a `.kmet/` dir starts in project-local scope; `-l` forces
  project-local. It never loads extension code and never edits settings or
  `:resources`: the global scope writes a row or a remove patch in
  `~/.kmet/agent/patch.edn` (an extension's row and config stay, so
  re-enabling is lossless; a built-in tool is restored by dropping its remove
  patch), and the project scope writes a project row, a restore patch or a
  remove patch in `.kmet/patch.edn`. The files apply on the next start;
  `/reload` re-applies the rows already mounted.
- The **Built-in tools** group lists the base composition's tool rows; remove
  one to run without that tool (a project layer can restore a globally
  removed one). The **Bundled with kmet** group lists the app-provided bundled
  extensions. Skills and prompts are not rows here: `/config` edits their
  stored selection, while themes stay discovery resources and `/tools` remains
  the session tool selector.

### In-TUI commands

| Command | Description |
|---------|-------------|
| `/help` | Show available commands and shortcuts |
| `/hotkeys` | Show all keyboard shortcuts — the bindings the app actually wired, resolved live (keybindings.edn overrides and extension shortcuts included) |
| `/quit` | Exit kmet |
| `/model <provider:model[:thinking]>` | Switch model (Ctrl+L opens a selector; an unmatched term opens the selector pre-filled with it) |
| `/thinking [level]` | Set thinking level — bare: selector with search, ✓ current, `· default` marker (Enter applies to this session, Ctrl+S also saves it as the default); with a level arg: apply it and save it as the default |
| `/scoped-models` | Enable/disable/reorder the models Ctrl+P cycles through (Ctrl+S saves to settings) |
| `/settings` | Settings menu — thinking, display, tool display, steering/follow-up mode, HTTP transport + timeouts, auto-compact, retry, repeat guard, images, skill commands, terminal progress, clear on shrink, theme (single or automatic light/dark) |
| `/tools` | Select tools for this session — Enter/Space toggles, search filters by name, Esc closes; the selection is not persisted |
| `/config` | Edit the persistent resource selection — rows grouped by provider extension and labelled `type/name`, starting in project scope; Tab switches global/project scope, Enter/Space cycles inherit/enabled/disabled in project scope (an inherited row names the effective global state) and toggles in global scope, search filters, Esc closes; stored entries whose provider is unavailable this run appear in a dimmed `Stored, unavailable` group and Space forgets them |
| `/eval <list\|search\|describe\|drop\|move\|reload\|sources\|help> [args]` | List, search, describe or drop eval library definitions, rebuild the session's sandbox from the files, list eval sources, or show the command's verbs; eval creates verbs from documented top-level definitions, while an undocumented one stays session-only; eval sources may contribute verbs |
| `/units` | List the process's unit tree — one flat line per ctx and per mounted plugin fiber, with its state, what it injects and provides, its effect labels, and what a pending fiber waits for or why a failed one failed |
| `/login [provider\|base#label]` | Configure provider auth — API key, or OAuth: Copilot device-code, Codex browser/device, Anthropic & OpenRouter browser PKCE. A `base#label` reference creates a second account alias when needed (see [Providers](providers.md#multiple-accounts)) |
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
| `/reload` | Reload settings, keybindings, extensions, skills, prompts, themes, context files and the builtin loop stages |
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
| `Ctrl+T` | Cycle thinking display — visible / stream-last / hidden |
| `Ctrl+O` | Cycle tool display — collapsed / expanded / quiet |
| `Ctrl+G` | Open the external editor |
| `Ctrl+Z` | Suspend to background — `fg` resumes (Unix only) |
| `Alt+Enter` / `Alt+Up` | Queue a follow-up message / restore queued messages |
| Mouse wheel / terminal scroll | Browse history — the transcript lives in the terminal's own scrollback |

Type `/hotkeys` for the full list — it resolves the live bindings the app
has actually wired (including `keybindings.edn` overrides) and adds a section
for extension-registered shortcuts.
