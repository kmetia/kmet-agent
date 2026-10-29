# lsp-adapter

Semantic code intelligence for kmet via Language Server Protocol servers —
one lazy `lsp` tool (~150 tokens), spawned only when a claimed file is
touched. Design source: dirge's `src/lsp/` (which mirrors opencode);
structural sibling of `extensions/mcp-adapter`.

## Enabling

```bash
ln -s "$PWD/extensions/lsp-adapter" ~/.kmet/agent/extensions/lsp-adapter
```

Restart or `/reload`. Requires the shared lib `kmet.libs.jsonrpc` (shipped
in-repo, no extra deps).

## Built-in servers

Install the binary; touch a claimed file — that's it.

| Server | Claims | Root markers |
|---|---|---|
| clojure-lsp | clj cljs cljc edn bb | deps.edn, project.clj, shadow-cljs.edn, bb.edn, .clj-kondo |
| typescript-language-server | ts tsx js jsx mjs cjs | package.json, tsconfig.json … (deno.json hands off) |
| pyright-langserver | py pyi | pyproject.toml, setup.py, requirements.txt … |
| rust-analyzer | rs | Cargo.toml |
| gopls | go | go.mod, go.work |
| clangd | c cc cpp h hpp … | compile_commands.json, .clangd, CMakeLists.txt |
| ruby-lsp | rb rake gemspec | Gemfile, Rakefile … |
| bash-language-server | sh bash zsh | (file's directory) |
| jdtls | java | pom.xml, build.gradle … |

A missing binary is reported once and remembered ("sticky broken") until
`/lsp restart <name>` or `/reload` — never retried per query.

## The lsp tool

```
lsp({operation: "definition", filePath: "src/x.clj", line: 42, character: 10})
```

Operations: `definition` `references` `hover` `documentSymbol`
`workspaceSymbol` (+`query`) `implementation` `prepareCallHierarchy`
`incomingCalls` `outgoingCalls` `diagnostics`. Aliases:
`goToDefinition` `findReferences` `goToImplementation`. Line/character are
**1-based** (editor convention).

Typical flow: `grep` finds candidates → `definition` jumps → `references`
checks blast radius before an edit → after editing, `diagnostics` reports
current errors.

## Configuration — `.kmet/lsp.edn` (project-local, optional)

Entries override same-id builtins or add custom servers. EDN only, used
verbatim:

```clojure
{:servers {"jdtls" {:disabled true}            ;; remove a builtin
           "rust-analyzer" {:request-timeout-ms 60000}
           "fennel" {:command ["fennel-ls"]    ;; custom server
                     :extensions ["fnl"]
                     :root-markers ["flxproject.ni"]}}}
```

Keys: `:command` (string/vector) `:args` `:env` `:extensions` (replaces
claims) `:extend-extensions` (adds) `:filenames` `:root-markers`
`:exclude-root-markers` `:root-dir` `:initialization-options`
`:request-timeout-ms` `:idle-timeout` (minutes; reaper) `:disabled`.
Settings block (`:settings`) accepts the same timeout/idle keys globally.
After editing: `/lsp refresh`.

## Footer

The footer shows `LSP <connected>/<total>` only while at least one
server is CONNECTED - same terse form and hide-when-idle rule as mcp's
`MCP <connected>/<enabled>`.

## Commands

`/lsp` — interactive panel (TUI): rows show each server's state
(● connected · ✗ broken + reason · ○ idle), `↑↓`/`k j` select,
`enter`/`r` restarts, `f` reloads `.kmet/lsp.edn`, `esc` closes.
Headless: same data as text.

`/lsp list` · `/lsp restart <server>` · `/lsp refresh`

## Notes

- Servers run with your user privileges and index your code — configure
  only servers you trust.
- `workspace/applyEdit` is refused by design: edits flow through kmet's
  own edit tools exclusively.
- ESC during a query abandons it promptly; the server process stays warm.
- Tests live in `test/` and run through the repository's runner:
  `bb test` covers detection, the wire helpers, result shaping/rendering,
  the runtime's broken set and reaper, the panel and the entry point;
  `bb test-ext` adds `e2e-test`, which spawns
  `scripts/fake-lsp-server.bb`.
