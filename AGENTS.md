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
to their source package. The temporary notes `run_code.md`, `perf.md`,
`jolt-bugs.md`, and `windows.md` intentionally remain at the project root.

## Basic development flow

- `bb start` starts `kmet.core/-main`; `jolt start` is the native-host
  equivalent.
- `bb clean` removes build output and caches; `bb clean --dry-run` lists what
  it would remove. `jolt clean` is the native-host equivalent.
- `bb nrepl` starts the development server on port 1667, blocks, and writes
  no port file. `jolt nrepl-server [port]` (default 7888) starts the Jolt
  equivalent and writes `.nrepl-port` (gitignored). `clojure_eval` discovers
  port files first, then probes the common nREPL ports (7888, 1667), so a
  lone `bb nrepl` is found; when both run the port file wins — pass
  `port 1667` to target bb.
- `bb tasks` lists the one-line task summaries; `bb <task> --help` prints a
  task's full usage (the details `bb tasks` leaves out).
- Use the changed-file tasks for the normal loop:

  ```sh
  bb changed
  bb test-changed
  bb test-ext-changed
  bb lint-changed
  bb format-check-changed       # or bb format-changed
  ```

- `bb check` verifies source namespaces. Do not hand-edit generated provider
  or image-model catalogs: use `bb generate-models` / `bb check-model-data`
  and `kmet --generate-models` for the user-level caches.
- `sh scripts/install_hooks.sh` installs the commit-msg hook, which enforces
  the commit-message rules below. The attribution workflow enforces the same
  rules over a pushed or PR range — it is the repository's only CI check.

### Interactive development

- Prefer the running host's nREPL as the inner loop: edit, then `clojure_eval`
  a `(require 'my.ns :reload)` and call the changed function. `bb nrepl` is
  the bb reader view; `jolt nrepl-server` is the jolt view. Restart the server
  when structural redefinitions (protocols, records, macros) leave stale
  behavior.
- `clojure_eval` returns only the last form's value, and a `require` and the
  aliases it introduces must be in separate top-level forms.
- Use a fresh process — `bb -e`, a scratch script, or a test — when a cold
  start matters: namespace load order, extension/config discovery,
  reproducible bug reports (`scripts/repro_*.bb` is the place for a repro).
- The TUI cannot be driven from a REPL; use print mode or the tmux/pty capture
  scripts in `scripts/` for real terminal behavior.
- A REPL session does not replace the changed-file gates (`bb test-changed`,
  `bb lint-changed`, `bb format-check-changed`).

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

### Time and durations

- Durations and deadlines go through `kmet.libs.concurrent/monotonic-ms` —
  timeouts, sleep slices, backoff, idle reaping, elapsed counters.
  `System/currentTimeMillis` steps (NTP, Android radio time, VM resume), so a
  duration measured on it is wrong: a forward step fires timeouts early and
  kills work that has just started, a backward step stalls them (a TUI timer
  armed for 50ms can park for the step's length). Never mix the two clocks in
  one deadline.
- The wall clock stays right for absolute values and for comparisons against
  externally-produced wall-clock data: token and JWT expiry, epoch ids and
  timestamps, file mtimes, persisted cross-process cache TTLs.

### Errors and logging

- Use `ex-info` with a structured `:cause` or `:type` key. Let errors reach
  the top-level handler instead of silently swallowing them.
- Lifecycle and handled-error logging belongs to `kmet.debug`; use
  `kmet.debug/log` for opt-in debug output and `kmet.debug/log-error` for
  unhandled top-level errors.

### Shared layers are extended, not copied

- Call a facility where it lives; never re-implement it next to the caller.
  `kmet.tui.core` re-exports protocol-dispatching accessors (e.g.
  `editor-get-text`, `editor-set-text!`, `editor-add-to-history!`) precisely
  so upper layers never open-code a `(satisfies? …)` dispatch with a
  field-based fallback, and `kmet.libs.*` owns its generic helpers the same
  way.
- Needing a variant is the signal to extend the lower layer (or the nearest
  shared namespace) — a second implementation is a bug even when it works.
  The layer map is in [`src/kmet/README.md`](src/kmet/README.md); the
  dispatcher list is in [`src/kmet/tui/tui.md`](src/kmet/tui/tui.md).

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
- On Termux there is no `/tmp` directory. Never write scratch files there;
  put temporary projects and test artifacts under the repository (e.g.
  `target/` or a scratch dir beside the checkout), or use `$TMPDIR`/
  `$PREFIX/tmp` when a real temp directory is needed.

## Git and instruction hierarchy

- Commit messages describe the change and nothing else: no `Co-authored-by`
  trailers and no AI-assistant session links, machine addresses, or
  generated-with footers. The commit-msg hook and the attribution workflow
  enforce this.
- When instructions conflict, use this order: explicit user instructions,
  this file, the coding-agent defaults, then general best practices. Explain a
  conflict and ask for confirmation rather than silently overriding it.
- Before implementing a new feature, consult the relevant package document
  and the reference implementation in `~/src/cvstree/pi/` when useful.
