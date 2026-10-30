# Built-in extensions

Extensions shipped with kmet, living in the repo so they stay in sync with
the extension contract (`kmet.extension`). They are **opt-in**: nothing here
is loaded by default.

## Enabling an extension

The shipped set is **bundled with kmet**, so no download or install is
needed: `kmet config` lists every extension under “Bundled with kmet” (all
off by default), and space composes the selected one into
`~/.kmet/agent/patch.edn` (global) or `.kmet/patch.edn` (project). Write the
row directly if you prefer:

```edn
;; ~/.kmet/agent/patch.edn
{:rows [{:id :ext/clojure :plugin :ext/clojure}]}
```

Extensions are plugins: the composition is the only thing that loads them.
The global dir (`~/.kmet/agent/extensions/`), the project-local dir
(`.kmet/extensions/`), top-level `:extensions` entries and `kmet install`ed
packages are discovery; compose an `:ext/<id>` row to load one. Use a
symlink or copy into one of those dirs for local development, or install it
as a **package** with `kmet install <path>` (a local directory/file, or a git
repository such as `git:github.com/user/repo`; records the source in
settings — see
[`docs/usage.md`](../docs/usage.md#package-subcommands) and
[`docs/extensions.md`](../docs/extensions.md)):

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
snapshot you can edit locally. Either way, compose the extension's row
(`kmet config`, or the patch file above) and restart so the new composition
mounts; `/reload` reloads an already composed extension's code with its
config.

## Repo layout: `src/` is the artifact root

Each shipped extension keeps everything that ships under `src/` — manifests,
code, skills, merged resources — while dev-only files stay outside:

```
extensions/clojure/              ; dev wrapper, never packaged
├── README.md  bb.edn  test/
└── src/                         ; === artifact root: zip as-is / symlink to install ===
    ├── extension.edn            ; {:name "clojure" :plugin kmet.extensions.clojure.core/plugin}
    ├── deps.edn                 ; :deps only
    ├── kmet/extensions/clojure/
    │   ├── core.clj
    │   └── edit_tool.clj
    └── skills/clojure-edit/SKILL.md
```

Pack with `bb pack-extension extensions/<name>/src [out.jar]`; install with
`unzip my-ext.jar` or `ln -s .../src`. `bb.edn`/`test/`/`scripts/`/`README.md`
never ship.

## Layout rules

- **Single-file extensions** (`*.clj` at the top level of the directory) —
  one namespace defining a `plugin` var (whose `:apply` is the extension
  body), namespaced `kmet.extensions.<name>` (the file's stem). They cannot
  carry a `deps.edn`, so they may only use `kmet.extension`, `kmet.srce.core`
  plus the shared
  `kmet.tui.*` / `kmet.libs.*` library layers, the shared renderer
  namespaces (`kmet.ui.tui.tool-renderers`, `kmet.app.keybindings`),
  `clojure.*` / `babashka.*` builtins and the fixed bundled set
  (rewrite-clj, edamame, clojure.tools.reader, clojure.spec.alpha, cljfmt,
  parinferish — see `modules/app/src/kmet/extension.md` § Bundled extension libraries). They
  have **no tests** — the code is expected to
  stay small and self-contained; validate changes by loading the file
  against `kmet.extension/create-nullable-api` or the real runtime.
- **Directory-based extensions** (a subdirectory with an `extension.edn`
  manifest `{:name ... :plugin my.ext.main/plugin :loader [:jolt :sci]}`) — separate
  projects with a **strict ns-path layout** (namespace `a.b/c` at `a/b/c.clj`
  under the root). Shipped directories namespace their code
  `kmet.extensions.<name>.*`, the manifest entry at
  `kmet.extensions.<name>.core`. `:loader` lists the loader backends the extension
  supports (`:sci` / `:jolt`; the host picks its own preference — see
  [`docs/extensions.md`](../docs/extensions.md#loader-compatibility-loader)). They may carry a `deps.edn` for
  library dependencies (`:deps` only) and their own tests (run them from
  inside the directory, e.g. `bb test` against the extension's own deps).
- **Jar/zip extensions** — the same layout packed as a single archive
  (`extension.edn` + `deps.edn` at the root, code at ns paths, resources by
  exact name). The loader serves them unexpanded. See
  [`docs/extensions.md`](../docs/extensions.md#jarzip-extensions-no-expansion) for
  the format.

All extension files — source **and any tests they carry** — are covered by
the repo gates: `bb lint` / `bb format` / `bb format-check` lint and format
`extensions/`, and the `bb *-changed` dev loop tracks extension files
(extension namespaces join the require graph for linting and testing, and
extension tests now run through the root `bb test` runner — they can still be
run from inside the extension directory against their own deps).

## Building UI

Extensions build their own UI with the shared `kmet.tui.*` layer and mount
it with `ui-custom` (pi: `ctx.ui.custom`) — the api carries no host-built
dialogs. The shared component library and its patterns are covered in
[`docs/extensions.md`](../docs/extensions.md#building-ui). The factory receives
`(tui theme keybindings close)` and returns a
component (a `defcomponent`, or a duck-typed map `{:render :handle-input
:invalidate}`); the host mounts it (overlay or editor dock), feeds it
input, and `close` dismisses it. Pattern (extensions/tools.clj,
extensions/mcp-adapter/src/kmet/extensions/mcp_adapter/panel.clj):

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
`kmet.libs.theme` directly (`get-theme` / `get-all-themes` /
`get-theme-by-name` / `get-current-theme`). `ui-custom` forwards its opts
map untouched — `{:overlay ... :overlay-options {:anchor :center :width
82}}` matches pi's overlay placement. Floating overlays get a full
border and a themed background fill by default (kmet, not pi), so a
component renders opaque over the transcript; a component that draws its
own frame passes `:border :none` (and `:background false` to keep the
terminal background).

## Shipped extensions

The extension authoring guide — layouts, manifests, loaders, dependencies, UI
patterns, and testing — lives in [`docs/extensions.md`](../docs/extensions.md).
The authoritative `kmet.extension` contract (the fn table, api map keys,
allowed namespaces, lifecycle guarantees) lives in
[`modules/app/src/kmet/extension.md`](../modules/app/src/kmet/extension.md);
keep both up to date whenever the contract changes.

| Extension | Description |
|-----------|-------------|
| `grep-tool.clj` | Opt-in `grep` tool — regex search over file contents, matching lines only (`file:line: text`, 100-match cap), runtime-cwd resolution, bash-style result rendering (not a builtin) |
| `find-tool.clj` | Opt-in `find` tool — regex match against file names/paths, path-only results (200 cap), runtime-cwd resolution, bash-style result rendering |
| `ls-tool.clj` | Opt-in `ls` tool — directory listing sorted by name, long form with type/size, runtime-cwd resolution, bash-style result rendering |
| `powershell.clj` | Opt-in `powershell` tool (Windows) — port of pi's powershell tool: same bash engine and renderers with a `ps $` prompt, PowerShell 7 preferred over Windows PowerShell, `-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command`, UTF-8 output prefix; errors with pi's message on other hosts |
| `tools.clj` | Interactive `/tools` command to enable/disable tools, with selection persisted across session reloads and branch navigation (port of pi's example tools extension) |
| `deepseek-peak.clj` | `/deepseek-peak` — DeepSeek API peak/off-peak hours in your local time zone, shown as a `/session`-style chat info panel (flash fallback in headless mode) |
| `clojure/` | Clojure-aware tools ported from clojure-mcp: `clojure_edit`, `clojure_edit_replace_sexp`, `clojure_paren_repair`, `clojure_eval` (nREPL evaluation), plus the `clojure-edit` skill — see `clojure/README.md` |
| `mcp-adapter/` | MCP server access via one lazy `mcp` proxy tool: stdio + streamable-HTTP/SSE transports, EDN config (`~/.kmet/agent/mcp.edn` + `.kmet/mcp.edn`), OAuth (PKCE loopback + device flow), direct tools, `/mcp` command (port of pi-mcp-adapter — see `mcp-adapter/README.md`) |
| `tree-sitter/` | Structural code intelligence: `list_symbols`, `find_definition`, `get_symbol_body`, `find_callers`, `find_callees` over the official tree-sitter CLI + WASM grammars (auto-downloaded, sha-pinned; clojure/python/typescript/tsx), plus planned write/edit syntax-validation hooks — see `tree-sitter/README.md` |
| `review/` | `/review` + `/end-review` slash commands with a language-agnostic rubric (port of pi-review). Modes: uncommitted, base branch, commit, folder. Fresh-session branching with a `code-review` label, project `REVIEW_GUIDELINES.md` loading, shared custom instructions, structured review summary — see `review/README.md` |
