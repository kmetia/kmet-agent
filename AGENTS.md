# kmet agent guidelines

The short, cross-cutting rulebook. Detailed design and implementation rules
live beside the package they govern: read the relevant topic document before
changing that area, and update it in the same change when behavior changes.

Functionality follows pi (`~/src/cvstree/pi/`; gap list
[`docs/pi-alignment.md`](docs/pi-alignment.md)); application architecture
follows DeepSeek Harness on Cordis (`~/src/cvstree/deepseek-harness/`) — one
context tree, services found by key, dependencies declared with `inject`,
effects that unwind, declared event modes, and **everything is a plugin**: the
loop, tools, sessions, model access (the `:llm` service, `kmet.app.llm`, with
the request as `kmet.ai.llm` behind the row) and modes are replaceable rows, and
an extension mounts beside them instead of patching a privileged core. Module
map, composition and service catalog: [`docs/architecture.md`](docs/architecture.md);
open seams (a composition edit still has no `applied` / `restart-required`
vocabulary among them): [`new-app.md`](new-app.md) § Open at HEAD; kernel
departures from Cordis: [`srce.md`](modules/srce/src/kmet/srce/srce.md) §5/§10.
**New behavior attaches to a documented seam — a plugin row, a service, an
event, or the extension contract — not to a branch in the entry or a new
global.**

## Topic map

| Area | Authoritative reference |
|------|-------------------------|
| Application layout and contributor workflow | [`docs/architecture.md`](docs/architecture.md) |
| Kernel: ctx tree, services, inject, fibers, effects, events | usage: [`modules/srce/README.md`](modules/srce/README.md); contracts: [`modules/srce/src/kmet/srce/srce.md`](modules/srce/src/kmet/srce/srce.md) |
| kmet2 application: composition, services, agents, sessions, tools and the script sandbox | [`docs/architecture.md`](docs/architecture.md); open seams and declined options in [`new-app.md`](new-app.md) |
| TUI, components, Hiccup, reactivity, input, and rendering | [`modules/tui/src/kmet/tui/tui.md`](modules/tui/src/kmet/tui/tui.md) |
| Tk backend and its widget sample (`kmet.tk.*`, `kmet.gui-sample.*`) | [`modules/tk/src/kmet/tk/README.md`](modules/tk/src/kmet/tk/README.md) |
| Extension contract (`kmet.extension` API, allowed namespaces, lifecycle) | [`modules/app/src/kmet/extension.md`](modules/app/src/kmet/extension.md) |
| Extension authoring guide (layouts, loaders, deps, UI, testing) | [`docs/extensions.md`](docs/extensions.md) |
| pi alignment and feature-gap analysis | [`docs/pi-alignment.md`](docs/pi-alignment.md) |
| Architecture lineage: pi functionality, dsh's Cordis "everything is a plugin" model | [`new-app.md`](new-app.md) § Prior art: DSH and § Reconciliation; [`modules/srce/src/kmet/srce/srce.md`](modules/srce/src/kmet/srce/srce.md) |
| Loader design and backends | [`modules/loader/src/kmet/loader/loader.md`](modules/loader/src/kmet/loader/loader.md) |
| Jolt provider scaffolding and host integration | [`jolt/src/jolt/kmet/README.md`](jolt/src/jolt/kmet/README.md) |
| Platforms (Linux, macOS, Windows, WSL, Termux), building and distribution | [`docs/building.md`](docs/building.md) |
| User guides, examples, settings/theme/provider samples | [`docs/README.md`](docs/README.md), [`docs/examples/README.md`](docs/examples/README.md) |

User-facing documentation belongs in `docs/` (keep the root
[`README.md`](README.md) short and user-facing); implementation notes belong
next to their source package. The temporary notes `perf.md`, `jolt-bugs.md`,
`tcl.md` and `new-app.md` (the kmet2 design record: the open seams, the limits
that shipped and the declined options) intentionally remain at the project
root.

## Basic development flow

- `bb start` starts `kmet.app.core/-main`, the process entry; `jolt start` is
  the native-host equivalent. There is one app — kmet1 was retired with its
  module and the `--app` flag is gone, so every host runs the same composition.
- `bb clean` removes build output and caches (`--dry-run` lists what it would
  remove; `jolt clean` is the native-host equivalent). `bb tasks` lists the
  one-line task summaries; `bb <task> --help` prints a task's full usage.
- nREPL: `bb nrepl` (7801, the bb reader view, writes no port file),
  `bb nrepl-jvm [port]` (7803, the JVM view) and jolt's `jolt nrepl-server 7802`
  (the Jolt view). The JVM and Jolt servers share one `.nrepl-port` file, so
  pass `port` when both run — ports and discovery are in
  [`docs/architecture.md`](docs/architecture.md) § Development workflow.
- The changed-file tasks are the normal loop: `bb changed`,
  `bb test-changed`, `bb test-ext-changed`, `bb lint-changed`,
  `bb format-check-changed` (or `bb format-changed`).
- `bb check` verifies source namespaces. Never hand-edit a generated catalog:
  the provider/image-model ones (`bb generate-models` / `bb check-model-data`,
  and `kmet --generate-models` for the user-level caches) or the composition
  one (`bb generate-catalog` / `bb check-catalog` for
  [`docs/composition.md`](docs/composition.md)).

### Interactive development

- The running host's nREPL is the inner loop: edit, `clojure_eval` a
  `(require 'my.ns :reload)`, call the changed function. Restart the server
  when structural redefinitions (protocols, records, macros) leave stale
  behavior. A REPL session does not replace the changed-file gates.
- `clojure_eval` returns only the last form's value, and a `require` and the
  aliases it introduces must be in separate top-level forms.
- Use a fresh process — `bb -e`, a scratch script, or a test — when a cold
  start matters: namespace load order, extension/config discovery, reproducible
  bug reports (`scripts/repros/` is where a repro goes).
- The TUI cannot be driven from a REPL; use print mode or the tmux/pty capture
  scripts in `scripts/capture/` for real terminal behavior.

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

- Avoid Java interop: `babashka.fs` for files, `babashka.process` for
  subprocesses, `clojure.string` for string operations, `clojure.java.io` where
  appropriate. No `java.io.*` or `java.nio.file.*` imports, no Java type hints.
- All outbound HTTP goes through `kmet.libs.http`. No other namespace may
  require `babashka.http-client` or spawn `curl`.
- Reader conditionals name the host: `:bb` for Babashka, `:jolt` for Jolt.
  Never `:clj` in kmet source — it matches both hosts. Use
  `#?(:jolt X :default Y)` for a host-specific value with a portable default.
  Both host lint views must remain valid for shared code.
- On Termux there is no `/tmp`: put scratch projects and test artifacts under
  the repository (`target/` or a scratch dir beside the checkout), or use
  `$TMPDIR` / `$PREFIX/tmp` when a real temp directory is needed.
- Layer boundaries, task packaging, the remaining portability notes and
  application state conventions are in [`docs/architecture.md`](docs/architecture.md)
  — keep that document current when those rules change.

### Time and durations

Durations and deadlines go through `kmet.libs.concurrent/monotonic-ms` —
timeouts, sleep slices, backoff, idle reaping, elapsed counters. The wall clock
steps (NTP, Android radio time, VM resume), so a duration measured on it is
wrong: a forward step fires timeouts early and kills work that has just
started, a backward step stalls them (a TUI timer armed for 50ms can park for
the step's length). Never mix the two clocks in one deadline.

The wall clock stays right for absolute values and for comparisons against
externally-produced wall-clock data: token and JWT expiry, epoch ids and
timestamps, file mtimes, persisted cross-process cache TTLs.

### Errors and logging

- Use `ex-info` with a structured `:cause` or `:type` key. Let errors reach
  the top-level handler instead of silently swallowing them.
- Lifecycle and handled-error logging belongs to `kmet.app.debug`: `log` for
  opt-in debug output, `log-error` for unhandled top-level errors.

### Shared layers are extended, not copied

- Call a facility where it lives; never re-implement it next to the caller.
  `kmet.tui.core` re-exports protocol-dispatching accessors (e.g.
  `editor-get-text`, `editor-set-text!`, `editor-add-to-history!`) precisely so
  upper layers never open-code a `(satisfies? …)` dispatch with a field-based
  fallback, and `kmet.libs.*` owns its generic helpers the same way.
- Needing a variant is the signal to extend the lower layer (or the nearest
  shared namespace) — a second implementation is a bug even when it works.
  The layer map is in [`docs/architecture.md`](docs/architecture.md); the
  dispatcher list is in [`modules/tui/src/kmet/tui/tui.md`](modules/tui/src/kmet/tui/tui.md).

### Complexity is a budget, not a scoreboard

`bb slop` measures both ways a tree erodes: verbosity (clone and rule-flagged
lines over SLOC) and erosion (the `CC × √SLOC` mass held by functions with
CC > 10). Both grow one reasonable branch at a time, so hold them while
editing, not after. It is an on-demand whole-tree scan, not part of the
changed-file loop.

- A function already past CC 10 does not gain another branch — extract the
  case. Mass is not line count, so lifting a branch out lowers it.
- Extend the shared helper instead of writing the near-copy; `bb slop --dups`
  is what finds it.
- Retire, in the same change, the path you replace. A second way to do one
  thing is erosion the metric cannot see.

## Testing

- Tests use `clojure.test` and live beside their module
  (`modules/<module>/test/kmet/...`); cross-cutting and task-runner tests live
  under the root `test/kmet/` (task tests under `test/kmet/tasks/`). An
  extension project declares its `test/` tree in `modules.edn` so the root
  runner discovers those namespaces too, and keeps its own `bb.edn` `test` task
  for running one project in place.
- Test namespaces are discovered, not registered:
  `kmet.tasks.runner/all-namespaces` derives from the manifest test roots
  (`kmet.tasks.modules/test-namespaces`), so a new namespace need only live
  under a module/extension `test/` tree;
  `test/kmet/test_test_ns_registration.clj` holds the derivation honest.
- Mark tests that use real sleeps, network calls, terminal timeouts, or
  subprocesses with `^:slow`; the runner separates them between `bb test` and
  `bb test-ext`.
- Use the changed-file tasks above while iterating. Full gates are `bb test`,
  `bb test-ext`, `bb lint`, and `bb format-check`; do not run them during
  routine iteration unless explicitly requested. Lint must have zero errors,
  warnings, and informational findings.

## Git and instruction hierarchy

- Commit messages describe the change and nothing else: no `Co-authored-by`
  trailers and no AI-assistant session links, machine addresses, or
  generated-with footers. The commit-msg hook (`sh scripts/install_hooks.sh`)
  and the attribution workflow — the repository's only CI check — enforce this.
- When instructions conflict, use this order: explicit user instructions, this
  file, the coding-agent defaults, then general best practices. Explain a
  conflict and ask for confirmation rather than silently overriding it.
