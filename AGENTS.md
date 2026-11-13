# kmet agent guidelines

The short, cross-cutting rulebook. Detailed design and implementation rules
live beside the package they govern: read the relevant topic document before
changing that area, and update it in the same change when behavior changes.

Functionality follows pi (`~/src/cvstree/pi/`; gap list
[`docs/pi-alignment.md`](docs/pi-alignment.md)); application architecture
follows DeepSeek Harness on Cordis (`~/src/cvstree/deepseek-harness/`): one
context tree, services by key, `inject` dependencies, unwinding effects,
declared event modes, and **everything is a plugin** — the loop, tools,
sessions, model access (`:llm`/`kmet.app.llm`, with the request as
`kmet.ai.llm` behind the row) and modes are replaceable rows, and extensions
mount beside them instead of patching a privileged core. Module map, composition
and service catalog: [`docs/architecture.md`](docs/architecture.md); open seams
(a composition edit still has no `applied`/`restart-required` vocabulary):
[`design.md`](modules/app/src/kmet/design.md) § Open at HEAD; kernel departures
from Cordis: [`srce.md`](modules/srce/src/kmet/srce/srce.md) §5/§10.
**New behavior attaches to a documented seam — a plugin row, a service, an
event, or the extension contract — not to a branch in the entry or a new
global.**

## Topic map

| Area | Authoritative reference |
|------|-------------------------|
| Application layout, composition, services and contributor workflow | [`docs/architecture.md`](docs/architecture.md); open seams and declined options in [`modules/app/src/kmet/design.md`](modules/app/src/kmet/design.md) |
| Kernel: ctx tree, services, inject, fibers, effects, events | usage: [`modules/srce/README.md`](modules/srce/README.md); contracts: [`modules/srce/src/kmet/srce/srce.md`](modules/srce/src/kmet/srce/srce.md) |
| Agent efficiency benchmark (eval vs bash, prompt/guideline/tool variants) | [`scripts/bench/README.md`](scripts/bench/README.md) |
| TUI, components, Hiccup, reactivity, input, and rendering | [`modules/tui/src/kmet/tui/tui.md`](modules/tui/src/kmet/tui/tui.md) |
| Tk backend and its widget sample (`kmet.tk.*`, `kmet.gui-sample.*`) | [`modules/tk/src/kmet/tk/README.md`](modules/tk/src/kmet/tk/README.md) |
| Extension contract (`kmet.extension` API, allowed namespaces, lifecycle) and authoring guide (layouts, loaders, deps, UI, testing) | [`modules/app/src/kmet/extension.md`](modules/app/src/kmet/extension.md); [`docs/extensions.md`](docs/extensions.md) |
| Architecture lineage and pi alignment | [`design.md`](modules/app/src/kmet/design.md) § Prior art: DSH; [`modules/srce/src/kmet/srce/srce.md`](modules/srce/src/kmet/srce/srce.md); [`docs/pi-alignment.md`](docs/pi-alignment.md) |
| Loader design and backends | [`modules/loader/src/kmet/loader/loader.md`](modules/loader/src/kmet/loader/loader.md) |
| Jolt provider scaffolding and host integration | [`jolt/src/jolt/kmet/README.md`](jolt/src/jolt/kmet/README.md) |
| Platforms (Linux, macOS, Windows, WSL, Termux), building and distribution | [`docs/building.md`](docs/building.md) |
| User guides, examples, settings and theme samples | [`docs/README.md`](docs/README.md), [`docs/examples/README.md`](docs/examples/README.md) |

User-facing docs belong in `docs/` (root [`README.md`](README.md) short and
user-facing); implementation notes sit beside their source. `perf.md`,
`jolt-bugs.md` and `tcl.md` stay at the root by convention; the design record
(open seams, limits, declined options) is
[`modules/app/src/kmet/design.md`](modules/app/src/kmet/design.md).

## Basic development flow

- `bb start` starts `kmet.app.core/-main`, the process entry; `jolt start` is
  the native-host equivalent. There is one app — kmet1 was retired with its
  module and the `--app` flag — so every host runs the same composition.
- `bb clean` removes build output and caches (`--dry-run` lists them;
  `jolt clean` is the equivalent); `bb tasks` lists summaries and
  `bb <task> --help` full usage.
- nREPL: `bb nrepl` (7801, no port file), `bb nrepl-jvm [port]` (7803) and
  `jolt nrepl-server 7802`; JVM/Jolt share `.nrepl-port`, so pass a port when
  both run (see [`docs/architecture.md`](docs/architecture.md) § Development
  workflow).
- The changed-file tasks are the normal loop: `bb changed`, `bb test-changed`,
  `bb test-ext-changed`, `bb lint-changed`, `bb format-check-changed` (or
  `bb format-changed`).
- `bb check` verifies source namespaces. Never hand-edit generated catalogs:
  `bb generate-models`/`bb check-model-data` (and `kmet --generate-models` for
  user caches), and `bb generate-catalog`/`bb check-catalog` for
  [`docs/composition.md`](docs/composition.md).

### Interactive development

- The running host's nREPL is the inner loop: edit, reload the namespace, call
  the changed function (`clojure_eval` with the Clojure extension, else eval's
  `clj/nrepl-eval`); restart after protocol/record/macro redefinitions leave
  stale behavior. `clojure_eval` returns every form's value interleaved with
  stdout/stderr; a `require` and its aliases need separate top-level forms. A
  REPL session does not replace the changed-file gates.
- Use a fresh process (`bb -e`, scratch script or test) for cold-start matters:
  load order, extension/config discovery, reproducible bugs (`scripts/repros/`).
- The TUI cannot be driven from a REPL; use print mode or the tmux/pty capture
  scripts in `scripts/capture/` for real terminal behavior.

## Editing rules

- Prefer structure-aware Clojure editing for Clojure/EDN: `clojure_edit` /
  `clojure_edit_replace_sexp` (Clojure extension) or eval's `clj/edit-form` /
  `clj/replace-sexp`; both validate balanced forms and format edits. Repair with
  `clojure_paren_repair`/`clj/paren-repair`; replace whole s-expressions, never
  text fragments, and use the definition-aware operation for a definition.
- Use ordinary text editing for Markdown, comments and prose. Add a docstring
  only for intent, a contract, side effects or an exception; document public
  vars, protocol methods and record types whose behavior is not obvious. Keep
  edits small; run the changed-file formatter after structural edits.

## Cross-cutting implementation rules

### Portable Clojure

- Avoid Java interop: `babashka.fs` for files, `babashka.process` for
  subprocesses, `clojure.string` for strings and `clojure.java.io` where
  appropriate; no `java.io.*`/`java.nio.file.*` imports and no Java type hints.
- All outbound HTTP goes through `kmet.libs.http`; no other namespace may
  require `babashka.http-client` or spawn `curl`.
- Name the host in reader conditionals: `:bb` for Babashka, `:jolt` for Jolt,
  never `:clj` (it matches both). Use `#?(:jolt X :default Y)` for a host
  carve-out with a portable default; both lint views must stay valid.
- On Termux there is no `/tmp`: keep scratch projects and test artifacts under
  the repo (`target/` or a scratch dir) or use `$TMPDIR` / `$PREFIX/tmp`.
- Layer boundaries, task packaging, portability notes and application state
  conventions live in [`docs/architecture.md`](docs/architecture.md); keep it
  current.

### Option maps and arglists

- An option map is part of the call surface: destructure its keys in the
  arglist — `[pattern {:keys [path max]}]` — instead of leaving an anonymous
  `opts`/`args` parameter whose keys exist only in prose. When the real call
  surface differs from the implementation (a factory or wrapper), put the
  same destructuring in `:arglists` metadata.
- The keys must be visible in `(help)`, the eval library manifest and a
  tool's published parameters; a docstring alone is not enough for
  discoverability.

### Time and durations

Durations and deadlines go through `kmet.libs.concurrent/monotonic-ms`:
timeouts, sleep slices, backoff, idle reaping, elapsed counters. The wall
clock steps (NTP, Android radio time, VM resume), so a duration measured on it
fires early or stalls; never mix the two clocks in one deadline. The wall clock
is right for absolute values and externally-produced wall-clock data:
token/JWT expiry, epoch ids and timestamps, file mtimes, persisted
cross-process cache TTLs.

### Errors and logging

- Use `ex-info` with a structured `:cause` or `:type` key; let errors reach the
  top-level handler. Lifecycle and handled-error logging belongs to
  `kmet.app.debug`: `log` for debug output, `log-error` for unhandled errors.

### Shared layers are extended, not copied

- Call a facility where it lives; never re-implement it next to the caller.
  `kmet.tui.core` re-exports protocol-dispatching accessors so upper layers
  never open-code a `(satisfies? …)` dispatch with a field fallback;
  `kmet.libs.*` owns its generic helpers the same way.
- A variant means extending the lower layer (or nearest shared namespace); a
  second implementation is a bug even when it works. The layer map is in
  [`docs/architecture.md`](docs/architecture.md); the dispatcher list is in
  [`modules/tui/src/kmet/tui/tui.md`](modules/tui/src/kmet/tui/tui.md).

### Pre-1.0: the extension ABI is not frozen

The extension ABI carries **no compatibility promise** before 1.0:
`kmet.extension`, its allowed shared namespaces, `extension.edn` and the
`kmet.app.extensions.context` allow-list (some literals) may change freely.

- **Fix in-repo consumers in the same change.** Everything under `extensions/`
  — bundled set and single-file tools — is an ABI consumer; update its
  sources, tests and build files with the host.
- **No compatibility shims.** No alias namespace, second allow-list spelling
  or forwarding `def`. When the last pre-rename name moved
  (`kmet.agent.ui.tui.tool-renderers` → `kmet.ui.tui.tool-renderers`), its
  consumers moved too; a guard states the new rule, never exempts the old.
- **Say it is a break.** A third-party-visible change is a break: the commit
  body names the moved surface — like any other change, no changelog file.

Update [`modules/app/src/kmet/extension.md`](modules/app/src/kmet/extension.md)
and [`docs/extensions.md`](docs/extensions.md) in the same change; they are
authoritative.

### Complexity is a budget, not a scoreboard

`bb slop` measures verbosity (clone/rule-flagged lines over SLOC) and erosion
(`CC × √SLOC` mass of functions with CC > 10). Hold both while editing; it is
an on-demand whole-tree scan, not the changed-file loop.

- A function past CC 10 does not gain another branch — extract the case, which
  lowers mass because mass is not line count.
- Extend the shared helper instead of writing a near-copy (`bb slop --dups`),
  and retire the path you replace; a second way is erosion the metric cannot
  see.

## Testing

- Tests use `clojure.test` and live beside their module
  (`modules/<module>/test/kmet/...`); cross-cutting/task-runner tests are under
  `test/kmet/` (tasks in `test/kmet/tasks/`). An extension declares its `test/`
  tree in `modules.edn` and keeps its own `bb.edn` `test` task.
- Test namespaces are discovered, not registered:
  `kmet.tasks.runner/all-namespaces` derives from
  `kmet.tasks.modules/test-namespaces`, so a new test namespace needs only a
  module/extension `test/` tree;
  `test/kmet/test_test_ns_registration.clj` holds the derivation honest.
- Mark tests using real sleeps, network calls, terminal timeouts or subprocesses
  `^:slow`; `bb test` excludes them and `bb test-ext` runs only them.
- Use the changed-file tasks while iterating. Full gates: `bb test`,
  `bb test-ext`, `bb lint`, `bb format-check` — not during routine iteration
  unless requested. Lint must have zero errors, warnings and informational
  findings.

## Git and instruction hierarchy

- Commit messages describe the change only: no `Co-authored-by` trailers, AI
  session links, machine addresses or generated-with footers. The commit-msg
  hook (`sh scripts/install_hooks.sh`) and attribution workflow (the only CI
  check) enforce this.
- On conflict: explicit user instructions, this file, coding-agent defaults,
  general best practices. Explain the conflict and ask rather than silently
  overriding it.
