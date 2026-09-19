# Built-in extensions

Extensions shipped with kmet, living in the repo so they stay in sync with
the extension contract (`kmet.extension`). They are **opt-in**: nothing here
is loaded by default.

## Enabling an extension

Extensions load from the global dir (`~/.kmet/agent/extensions/`) and the
project-local dir (`.kmet/extensions/`) at startup and on `/reload`. Enable
a shipped extension by symlinking or copying it into one of those — or
install it as a **package** with `kmet install <path>` (records the source
in settings; see README “Package subcommands” and
`extensions/extensions.md`):

```bash
# one-time setup: make the shipped extension a package (user scope)
kmet install "$PWD/extensions/clojure/src"
# ...or project scope
kmet install "$PWD/extensions/clojure/src" --local

# classic way — global (all projects)
mkdir -p ~/.kmet/agent/extensions
ln -s "$PWD/extensions/tools.clj" ~/.kmet/agent/extensions/tools.clj
# directory extension: link its src/ (the artifact root)
ln -s "$PWD/extensions/clojure/src" ~/.kmet/agent/extensions/clojure
# ...or install a packed jar instead
bb pack-extension extensions/clojure/src /tmp/clojure.jar
cp /tmp/clojure.jar ~/.kmet/agent/extensions/clojure.jar

# or project-local
mkdir -p .kmet/extensions
ln -s "$PWD/extensions/tools.clj" .kmet/extensions/tools.clj
```

A symlink keeps the shipped copy updated with the repo; a copy is a
snapshot you can edit locally. Either way, restart kmet or run `/reload`
to pick it up.

## Repo layout: `src/` is the artifact root

Each shipped extension keeps everything that ships under `src/` — manifests,
code, skills, merged resources — while dev-only files stay outside:

```
extensions/clojure/              ; dev wrapper, never packaged
├── README.md  bb.edn  test/
└── src/                         ; === artifact root: zip as-is / symlink to install ===
    ├── extension.edn
    ├── deps.edn                 ; :deps only
    ├── edit_tool.clj
    ├── kmet/extensions/clojure/core.clj
    └── skills/clojure-edit/SKILL.md
```

Pack with `bb pack-extension extensions/<name>/src [out.jar]`; install with
`unzip my-ext.jar` or `ln -s .../src`. `bb.edn`/`test/`/`scripts/`/`README.md`
never ship.

## Layout rules

- **Single-file extensions** (`*.clj` at the top level of the directory) —
  one namespace defining `(defn init [api])`. They cannot carry a
  `deps.edn`, so they may only use `kmet.extension` plus the shared
  `kmet.tui.*` / `kmet.libs.*` library layers, the shared renderer
  namespaces (`kmet.app.ui.tool-renderers`, `kmet.app.keybindings`) and
  `clojure.*` /
  `babashka.*` builtins. They have
  **no tests** — the code is expected to
  stay small and self-contained; validate changes by loading the file
  against `kmet.extension/create-nullable-api` or the real runtime.
- **Directory-based extensions** (a subdirectory with an `extension.edn`
  manifest `{:name ... :entry my.ext.main :loader [:jolt :sci]}`) — separate
  projects with a **strict ns-path layout** (namespace `a.b/c` at `a/b/c.clj`
  under the root). `:loader` lists the loader backends the extension
  supports (`:sci` / `:jolt`; the host picks its own preference — see
  `extensions.md` § Loader compatibility). They may carry a `deps.edn` for
  library dependencies (`:deps` only) and their own tests (run them from
  inside the directory, e.g. `bb test` against the extension's own deps).
- **Jar/zip extensions** — the same layout packed as a single archive
  (`extension.edn` + `deps.edn` at the root, code at ns paths, resources by
  exact name). The loader serves them unexpanded. See `extensions.md` for
  the format and `jar-ext.md` for the full plan.

All extension files — source **and any tests they carry** — are covered by
the repo gates: `bb lint` / `bb format` / `bb format-check` lint and format
`extensions/`, and the `bb *-changed` dev loop tracks extension files
(extension namespaces join the require graph for linting, but extension
tests are never selected by the root `bb test` runner — they run from
inside the extension directory).

## Building UI

Extensions build their own UI with the shared `kmet.tui.*` layer and mount
it with `ui-custom` (pi: `ctx.ui.custom`) — the api carries no host-built
dialogs. The factory receives `(tui theme keybindings close)` and returns a
component (a `defcomponent`, or a duck-typed map `{:render :handle-input
:invalidate}`); the host mounts it (overlay or editor dock), feeds it
input, and `close` dismisses it. Pattern (extensions/tools.clj,
extensions/mcp-adapter/src/extensions/mcp_adapter/panel.clj):

```clojure
(ext/ui-custom api
              (fn [tui th kb close]
                (my-component ... close))
              {:overlay true
               :overlay-options {:anchor :center :width 82}})
```

Only host-owned bridges remain api capabilities: `:custom`, `:notify`
(the flash — the TUI instance is host-owned), `:chat-info` (append an
`:info` message to the chat history — the `/session` display style: part
of the live transcript, nothing to dismiss, never sent to the LLM and
not persisted across restarts), and integrations with the
host layout/editor/status/theme-controller state. Theme lookups come from
`kmet.tui.theme` directly (`get-theme` / `get-all-themes` /
`get-theme-by-name` / `get-current-theme`). `ui-custom` forwards its opts
map untouched — `{:overlay ... :overlay-options {:anchor :center :width
82}}` matches pi's overlay placement.

## Shipped extensions

The extension authoring guide — the full `kmet.extension` API contract —
lives in [`extensions.md`](extensions.md); keep it up to date whenever the
contract changes.

| Extension | Description |
|-----------|-------------|
| `grep-tool.clj` | Opt-in `grep` tool — regex search over file contents, matching lines only (`file:line: text`, 100-match cap), runtime-cwd resolution, bash-style result rendering (not a builtin) |
| `find-tool.clj` | Opt-in `find` tool — regex match against file names/paths, path-only results (200 cap), runtime-cwd resolution, bash-style result rendering |
| `ls-tool.clj` | Opt-in `ls` tool — directory listing sorted by name, long form with type/size, runtime-cwd resolution, bash-style result rendering |
| `tools.clj` | Interactive `/tools` command to enable/disable tools, with selection persisted across session reloads and branch navigation (port of pi's example tools extension) |
| `deepseek-peak.clj` | `/deepseek-peak` — DeepSeek API peak/off-peak hours in your local time zone, shown as a `/session`-style chat info panel (flash fallback in headless mode) |
| `clojure/` | Clojure-aware editing tools ported from clojure-mcp: `clojure_edit`, `clojure_edit_replace_sexp`, `clojure_paren_repair`, plus the `clojure-edit` skill — see `clojure/README.md` |
| `mcp-adapter/` | MCP server access via one lazy `mcp` proxy tool: stdio + streamable-HTTP/SSE transports, EDN config (`~/.kmet/agent/mcp.edn` + `.kmet/mcp.edn`), OAuth (PKCE loopback + device flow), direct tools, `/mcp` command (port of pi-mcp-adapter — see `mcp-adapter/README.md`) |
| `tree-sitter/` | Structural code intelligence: `list_symbols`, `find_definition`, `get_symbol_body`, `find_callers`, `find_callees` over the official tree-sitter CLI + WASM grammars (auto-downloaded, sha-pinned; clojure/python/typescript/tsx), plus planned write/edit syntax-validation hooks — see `tree-sitter/README.md` |
| `review/` | `/review` + `/end-review` slash commands with a language-agnostic rubric (port of pi-review). Modes: uncommitted, base branch, commit, folder. Fresh-session branching with a `code-review` label, project `REVIEW_GUIDELINES.md` loading, shared custom instructions, structured review summary — see `review/README.md` |
