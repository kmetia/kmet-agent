# kmet agent guidelines

This is the short, cross-cutting rulebook for the repository. Detailed design
and implementation rules live beside the package they govern. Read the
relevant topic document before changing that area, and update it in the same
change when behavior changes.

## Topic map

| Area | Authoritative reference |
|------|-------------------------|
| Application layout and contributor workflow | [`src/kmet/README.md`](src/kmet/README.md) |
| TUI, components, Hiccup, reactivity, input, and rendering | [`src/kmet/tui/tui.md`](src/kmet/tui/tui.md) |
| Extension contract and authoring | [`src/kmet/extension.md`](src/kmet/extension.md) |
| Loader design and backends | [`src/kmet/loader/loader.md`](src/kmet/loader/loader.md) |
| Jolt provider scaffolding and host integration | [`jolt/src/jolt/kmet/README.md`](jolt/src/jolt/kmet/README.md) |
| User guides and examples | [`docs/README.md`](docs/README.md), [`docs/examples/README.md`](docs/examples/README.md) |
| Building and distribution | [`docs/building.md`](docs/building.md) |

User-facing documentation belongs in `docs/`. Implementation notes belong next
to their source package. The temporary notes `script.md`, `perf.md`,
`jolt-bugs.md`, and `jolt-port.md` intentionally remain at the project root.

## Basic development flow

- `bb run` starts `kmet.core/-main`; `jolt run -m kmet.core` is the native-host
  equivalent.
- `bb clean` removes build output and caches; `bb clean --dry-run` lists what
  it would remove. `jolt clean` is the native-host equivalent.
- `bb nrepl` starts the development server on port 1667 and blocks.
- Use the changed-file tasks for the normal loop:

  ```sh
  bb changed
  bb test-changed
  bb test-ext-changed
  bb lint-changed
  bb format-check-changed       # or bb format-changed
  ```

- `bb check` verifies source namespaces. Do not hand-edit generated provider
  catalogs: use `bb generate-models` / `bb check-model-data`, the image-model
  generator, and `kmet --generate-models` for the user cache.

## Editing rules

- Prefer the structure-aware Clojure editing tools for `.clj`, `.cljc`,
  `.cljs`, `.bb`, and `.edn` files. They validate balanced forms and format
  edits; use `clojure_paren_repair` for a file that is already unbalanced.
- For a targeted expression change, replace complete s-expressions rather than
  text fragments. For a definition, use the definition-aware editor.
- Use ordinary text editing for Markdown, comments, and prose.
- Add a docstring only when it explains intent, a contract, side effects, or
  an exception; document public vars, protocol methods, and record types whose
  behavior is not obvious.
- Keep edits small. After structural edits, run the changed-file formatter.

## Cross-cutting implementation rules

### Portable Clojure

- Avoid Java interop. Use `babashka.fs` for files, `babashka.process` for
  subprocesses, `clojure.string` for string operations, and
  `clojure.java.io` where appropriate. Do not import `java.io.*` or
  `java.nio.file.*`, and avoid Java type hints.
- All outbound HTTP goes through `kmet.libs.http`. No other namespace may
  require `babashka.http-client` or spawn `curl`.
- Reader conditionals name the host: `:bb` for Babashka and `:jolt` for Jolt.
  Do not use `:clj` in kmet source because it matches both hosts. Use
  `#?(:jolt X :default Y)` for a host-specific value with a portable default.
- Both host lint views must remain valid for shared code.
- The layer boundaries, task packaging rules, portability notes, and
  application state conventions are maintained in
  [`src/kmet/README.md`](src/kmet/README.md). Keep that document current
  when those rules change.

### Errors and logging

- Use `ex-info` with a structured `:cause` or `:type` key. Let errors reach
  the top-level handler instead of silently swallowing them.
- Lifecycle and handled-error logging belongs to `kmet.debug`; use
  `kmet.debug/log` for opt-in debug output and `kmet.debug/log-error` for
  unhandled top-level errors.

## Testing

- Tests use `clojure.test` and mirror `src/kmet/` under `test/kmet/`.
- Mark tests that use real sleeps, network calls, terminal timeouts, or
  subprocesses with `^:slow`; the runner separates them between `bb test` and
  `bb test-ext`. Extension tests are separate projects and run from their own
  directory.
- Register every new test namespace in
  `kmet.tasks.runner/all-namespaces`.
- Use `bb test-changed`, `bb lint-changed`, and
  `bb format-check-changed` while iterating. Full gates are `bb test`,
  `bb test-ext`, `bb lint`, and `bb format-check`; do not run them during
  routine iteration unless explicitly requested.
- Lint must have zero errors, warnings, and informational findings.

## Documentation and platform references

- Keep the root [`README.md`](README.md) short and user-facing. Put extensive
  user documentation in `docs/`.
- Keep implementation and design documentation with its package, as listed in
  the topic map; temporary project notes stay at the repository root.
- TUI rules (components, Hiccup, `track!`, lifecycle/input, theming, and
  full-redraw behavior) live in [`src/kmet/tui/tui.md`](src/kmet/tui/tui.md).
- Jolt claims, guarded requires, loader capability floors, and native
  packaging live in [`jolt/src/jolt/kmet/README.md`](jolt/src/jolt/kmet/README.md)
  and [`docs/building.md`](docs/building.md).
- Extension behavior lives in [`src/kmet/extension.md`](src/kmet/extension.md);
  shipped-extension user setup remains in [`extensions/README.md`](extensions/README.md).
- Supported platforms are Linux, macOS, Windows, WSL, and Termux. Start with
  [`docs/building.md`](docs/building.md) for platform setup and distribution;
  settings/theme/provider examples are in [`docs/examples/`](docs/examples/).

## Git and instruction hierarchy

- Do not add `Co-authored-by` trailers.
- When instructions conflict, use this order: explicit user instructions,
  this file, the coding-agent defaults, then general best practices. Explain a
  conflict and ask for confirmation rather than silently overriding it.
- Before implementing a new feature, consult the relevant package document
  and the reference implementation in `~/src/cvstree/pi/` when useful.
