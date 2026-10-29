# kmet developer guide

This file is the contributor's map: the package layout, the layering rules and
the workflow. Implementation-specific references live beside their namespaces
rather than in the user-facing `docs/` directory: see
[`modules/tui/src/kmet/tui/tui.md`](../modules/tui/src/kmet/tui/tui.md),
[`modules/loader/src/kmet/loader/loader.md`](../modules/loader/src/kmet/loader/loader.md),
[`modules/srce/src/kmet/srce/srce.md`](../modules/srce/src/kmet/srce/srce.md),
[`modules/app/src/kmet/extension.md`](../modules/app/src/kmet/extension.md), and
[`pi-alignment.md`](pi-alignment.md). The app's design record and migration
ledger — reconciled to HEAD, and the only place that records what did *not*
ship — is [`new-app.md`](../new-app.md); this file is the current map.
Temporary analysis notes (`run_code.md`, `perf.md`, `jolt-bugs.md`, `tcl.md`)
remain at the repository root by convention.

## Project structure

```
modules/
├── reakt/  — reactive primitives                    (kmet.reakt.*)
├── hikap/  — toolkit-agnostic hiccup UI framework   (kmet.hikap.*)
├── libs/   — generic, self-contained helpers        (kmet.libs.*)
├── tui/    — terminal backend over hikap            (kmet.tui.*; tui.md)
├── ai/     — provider/auth library                  (kmet.ai.*)
├── srce/   — the kernel: ctx, services, fibers      (kmet.srce.*; srce.md)
├── loader/ — extension loader                       (kmet.loader.*; loader.md)
├── app/    — the application + its TUI front end   (kmet.app.*, kmet.ui.*)
└── tk/     — Tk backend + widget sample            (kmet.tk.*, kmet.gui-sample.*)
```

`modules.edn` is the workspace manifest: it lists the module trees and the
extra source roots, and the tasks read it instead of repeating the paths
(`kmet.tasks.modules`). A module's namespaces sit under the root it is
named for: `reakt` holds `kmet.reakt.*`, `hikap` holds `kmet.hikap.*`. `app`
is the one module with two prefixes: its business tree is `kmet.app.*`, and
`kmet.ui.*` is the TUI front end that merged into it (it was the `ui` module).

Ranks are the manifest's layering guard (`kmet.test-module-deps`): a module
may depend only on strictly lower-ranked ones, and a *namespace* may require
only a file in a strictly lower-ranked module — `reakt`/`libs`/`loader` 0,
`hikap`/`ai`/`srce` 1, `tui`/`tk` 2, `app` 3. A leaf that both the app and the
components read therefore lives in `libs`, never in either of them:
`kmet.libs.theme` (the world load reads it, the widgets style from it) and
`kmet.libs.fuzzy` (the selectors and the `--list-models` filter) are the two.

The guard holds the declaration in both directions: every module its `src`
requires must be in the module's `deps.edn`, and nothing may be there that
`src` does not use — a module's `:deps` is its src edge list, module edges and
third-party libraries alike. A table (`lib-namespaces`) maps each library to
the namespaces it provides, so a library required without a declaration and a
declaration nothing requires both fail; two are host-provided and exempt from
the floor — `org.babashka/ffi` (the JVM's standalone FFI, added by the `:jvm`
alias; babashka and Jolt supply their own) and `org.babashka/sci` (the
loader's caller-supplied backend artifact, loader.md §6.5). A library pinned
in more than one manifest must carry the same coordinate everywhere, which
keeps the repeated pins from drifting. Test-only libraries belong to the test
project (`deps.edn`'s `:dev` alias; babashka bundles the ones the suites use
and Jolt vendors them): a module `deps.edn` is a `:local/root` dependency, and
tools.deps never applies a dependency's aliases, so a test alias there could
not reach a test classpath. Test trees are still cross-cutting (`libs`'
tests require `tui`) and often point upward; the classpath is their union, so they
use modules no module `deps.edn` declares. It reads both hosts' reader
branches, so a `#?(:jolt …)` require counts.

The extension contract sits above the app tree, beside it:
`modules/app/src/kmet/extension.clj` (`kmet.extension`) is the one kmet
namespace an extension may depend on; `kmet.app.extensions` is its runtime.

### The app tree (`modules/app/src/kmet/app`)

One application, top of the module ladder (rank 3) and holding its front end.
Its parts, coarse to fine:

```
kmet/app/
├── core.clj            — the process entry: parse, compose a profile, boot it,
│                         run the mode row, report the exit code
├── jvm_main.clj        — the JVM artifact's AOT entry point (`:gen-class`)
├── maintenance.clj     — --list-models / --generate-models (the flags whose
│                         order matters), --version's printing
├── invocation.clj      — the invocation surface: patterns resolved against the
│                         catalog, the run flags applied to the loaded config
├── profile.clj         — profile → rows composition, layer patches, rendering
├── registries.clj      — the registry router (one handle per tag;
│                         `process-handle` is the published one)
├── agents.clj          — the `:agents` service: the handle factory + ops
├── extensions/         — the extension host: core (the bound services, the
│                         api, the registry tags), context (per-extension SCI),
│                         libs (the bundled set), bundled.clj (artifact
│                         discovery), and the UI request/respond pair
├── session/            — the log (core, store, listing, context, stats, tree,
│                         schema, export, backend), the runtime
│                         (`runtime.clj`: state, lifecycle, submission chain,
│                         queues, compaction, subscribers), and the shared
│                         lifecycle (the bare engine is gone; tests drive
│                         handles)
├── loop/               — the agent loop: message, state, completion,
│                         tool_exec, the stages, and `driver.clj`/`stages.clj`
├── compaction/         — the compaction subsystem (`core`, `stage`)
├── tools/              — tool, util, invoke, registry, bash (+executor,
│                         output), read, write, edit, run_code, config
├── modes/              — the mode rows: print, rpc, server, out
├── packages.clj, packages_cli.clj — package/resource resolution and the
│                         install/remove/update/list/config CLI
├── commands/           — the slash-command registry + the portable builtins
├── prompts.clj, skills.clj, skill_block.clj, system_prompt.clj — the resource
│                         registries and the prompt builder
├── event_bus.clj, events.clj, projections.clj — the event stream
├── config.clj, config_schema.clj — settings and their schema
├── world.clj           — the process world: `load!` (catalogs, config, themes,
│                         CLI flags) and the deferred `:world` row that mounts it
├── root.clj            — the one process-root atom and the publish rule
├── front_end.clj       — the front end's published surface
├── inspect.clj         — `/units` and the ctx-tree dump
└── version.clj         — the version string and the source-run marker
```

Each row names its own file (`tools.clj`, `sessions.clj`, `extensions.clj`,
`agents.clj`, …): a row is a `srce` plugin whose `apply` provides its service,
and `registries.clj` is the router behind them.

[`new-app.md`](../new-app.md) is the app's design record and migration ledger:
why the rows are what they are, what each landing shipped, and the items that
did not ship (`:ext/…` composition rows, the profile file layers beyond
`--patch`). Its "Reconciliation" section maps the historical names to HEAD;
this file stays the current map.

### The front end (`modules/app/src/kmet/ui`)

```
kmet/ui/
├── tui.clj             — the TUI provider + mode row (one artifact: the
│                         `:ui` surface and its `:mode` entry)
└── tui/
    ├── interactive/    — the interactive cluster: layout, turn, session admin,
    │                     builtins, state (CoreState), status, auth, resources,
    │                     the UI registry (ExtensionUIContext)
    ├── chat-history.clj, tool-execution.clj, bash-execution.clj — the
    │                     transcript and the tool/bash render paths
    ├── *_selector.clj, *_dialog.clj — model/auth/session/tree/fork/… pickers
    └── footer.clj, dock.clj, theme_controller.clj, *_block.clj
```

One file keeps a pre-rename name on purpose:
`kmet/agent/ui/tui/tool_renderers.clj` — shipped extensions require
`kmet.agent.ui.tui.tool-renderers` by that name, so the tree carries the old
prefix at the path a require resolves.

```
tasks/kmet/tasks/       — every bb-task implementation (a classpath root, not
                          part of the app): packagers (uberjar/dist/pack-extension),
                          the catalog generators, and the dev loop (changed,
                          test runner, clean, lint). Neither artifact carries
                          it — the uberjar walks src/, jolt embeds src/.
test/kmet/tasks/        — their tests
docs/examples/          — copyable, non-auto-loaded settings and complete theme EDN
```

The tree is a working map: update it in the same change that moves a
namespace, and keep detailed behavior in the topic documents listed in the
root `AGENTS.md` topic map.

## Architecture and portability

### Layer boundaries

- `kmet.libs.scope` **was** the store and node lifetimes (in `modules/libs`,
  rank 0): registrations keyed by owner path, with LIFO, isolated disposal. It
  and its backend `kmet.agent.registry-store` were deleted at landing 4e, and so
  was `scope.md`; the owner-path/LIFO/ordering conventions it established live
  on in the ctx tree (`srce` effects, `kmet.app.registry` handles) and are
  documented with them.
- `kmet.loader.*` is an outer module (`modules/loader`); it may use
  third-party deps and `kmet.loader.*`, but no other `kmet.*` namespace.
- `kmet.libs.*` is generic and self-contained; it may not require non-libs
  `kmet.*` namespaces (app, TUI, mode, AI, the framework modules).
  Sibling-lib composition is fine and expected (`http →
  concurrent/json/process`, `oauth → concurrent/crypto/http/json`);
  `modules/libs/test/kmet/libs/test_self_contained.clj` enforces exactly this.
- `kmet.reakt.*` is the standalone reactive engine (`modules/reakt`, rank 0):
  reactions, cursors and batching over plain atoms. `kmet.reakt.weak` is the
  one namespace allowed to touch `java.lang.ref` — a platform exception like
  the terminal backends.
- `kmet.hikap.core` is the toolkit-agnostic UI framework owned by
  `modules/hikap` (rank 1, so it may require only `kmet.libs.*` and
  `kmet.reakt.*`): the hiccup engine (`kmet.hikap.core`), the component
  runtime (`kmet.hikap.component`) and the alias registry
  (`kmet.hikap.alias`). `kmet.tui.*` and `kmet.tk.*` are backends over
  it — they pass their backend map in, the engine never requires them.
- `kmet.ai.*` is a standalone provider/auth library and depends only on
  `kmet.libs.*` plus standard/third-party code.
- `kmet.tui.*` is the generic UI layer (the terminal backend for
  `kmet.hikap.core`). It may depend on `kmet.libs.*`, `kmet.reakt.*` and
  `kmet.hikap.*`, never on app, LLM, or session concepts.
- `kmet.tk.*` is the Tk GUI backend (kmet.tk.core with the
  classic `:tk/…` widget set, kmet.tk.ttk the `:ttk/…` set, kmet.tk.dsl
  their shared machinery and kmet.tk.options the Tk option vocabulary —
  plus kmet.tk.tcl, the host FFI adapter). Like `kmet.tui.*`, `kmet.tk.*`
  builds on `kmet.libs.*` / `kmet.reakt.*` / `kmet.hikap.*` and nothing
  above it. The module also carries the `kmet.gui-sample.*` widget skeleton
  (the `:ui/…` widgets, today `:ui/transcript`), which is dependency-free
  like the backend; no app module requires the sample or Tk.
- `kmet.app.*` is the application, and `kmet.app.modes.*` is its mode-row layer:
  a mode row is what a profile runs, and the row mounts the front end that does
  the work. The front end (`kmet.ui.**`) lives in this module too, so the
  direction rule is enforced by path rather than by rank
  (`kmet.test-layer-guard`): the business tree never requires `kmet.ui.**`,
  `kmet.tui.**`, `kmet.tk.**` or a mode row.
  `kmet.app.core` is the composition root: it owns the maintenance flags
  (`--version`, `--list-models`, `--generate-models`, whose order matters),
  parses the invocation and boots the composed profile, then runs the profile's
  mode row. There is one app — kmet1 was retired with its module — so the
  `--app` flag and the bootstrap that dispatched on it are gone.
- `kmet.tasks.*` lives under `tasks/kmet/tasks/`, is never required by shipped
  code, and is not packaged. Task tests live under `test/kmet/tasks/`.
- Facilities are extended at the layer that owns them, never copied into the
  caller: upper layers call the `kmet.tui.*` / `kmet.libs.*` API. The
  `kmet.tui.core` dispatchers (`editor-get-text`, `editor-set-text!`,
  `editor-add-to-history!`, …) exist so app and mode code never open-codes the
  `satisfies?`/field-based fallback pair; a variant belongs in the lower
  layer.

Prefer `defrecord` over `deftype`, protocols for extension points, and atoms
for mutable state. `AgentState` deliberately uses independent per-field atoms
rather than one application-database atom. Prefer explicit runtime
registration tables/maps over compile-time multimethod registries. Use
`defn-` and `def-` for implementation details that are not public API.

The registries a front end or extension can reach — commands, tools, skills and
prompt templates — live in the app root **ctx**, one service per registry
(`:tools`, `:commands`, `:prompts`, `:skills`). The process handle
(`kmet.app.registries/process-handle`) routes each tag to its service, and
`kmet.app.registry/current` reads the one the composition published. Adding a
tool or a command is a
registry write on that handle and a plugin that adds one never re-provides.
What has no service of its own yet —
hooks, markdown transformers, themes, the UI port, and the renderers and flags
the ABI registers — is an entry in the same registry mechanism on the façade's
remainder handle (tags `:kmet/hook`, `:kmet/theme`, `:kmet/ui-port`,
`:kmet/entry-renderer`, `:kmet/message-renderer`, `:kmet/flag`), under an owner
path: `[:app :extensions <name>]` for an extension's own registrations,
including the skills, prompts and themes a `:resources-discover` contribution
loads (P5c). Nothing the host registers is a store entry since 4b-ii-a (P2),
and the store itself is gone since 4e (P2), so
no home is a process-global atom copied into each engine: unload is one
`deregister-owner!` per tag. `/units` prints the unit tree instead: one flat
line per ctx and per mounted fiber, with its state, `inject`, `provides` and
effect labels — plus the two facts the store's owner walk could never show, a
pending fiber's `waiting-for` and a failed one's error (`kmet.app.inspect`, 4d).
The runtime-owned surfaces are process caches
now: `bootstrap` installs the live session, its sinks and the headless UI port,
`dispose!` clears them (`clear-runtime!` + `unbind-extensions!`), and the
`extensions` atoms stay the read cache for extension code with no node in hand
(P4).

### Session runtime and front ends

The session-runtime split puts one session runtime behind every
front end:

- `kmet.app.session.runtime` owns the agent state, the session lifecycle
  (`new-session!`, `resume!`, `fork!`, `clone!`, `switch-session!`), the
  submission chain, the steer/follow-up queues, compaction (a manual request
  arriving mid-turn is deferred until the turn settles —
  `request-compaction!`), and the extension runtime handle. Front ends create
  an agent through `:agents` and drive its handle through the runtime's
  operations: the TUI keeps its `CoreState` view state plus the handle, and
  print/RPC modes construct no agent state at all.
  The front ends (the mode rows and `kmet.ui.**`) use the runtime's front-end
  surface only — they never require `kmet.app.loop` or the driver — and
  `kmet.test-layer-guard` enforces that boundary.
- The event stream is the API. `subscribe!` delivers plain EDN events on the
  emitting thread (fast, non-blocking) and the vocabulary — `event-types` /
  `app-event-types` / `loop-event-types`, declared as `:events` on a plugin and
  registered into the `:events` service (a vocabulary registry, like
  `:commands`), so a plugin's own events dispatch by their declaration — is the
  shared contract; there is one listener table (the ctx tree), one registration
  (`events/on!`) and one mode-driven dispatcher (`events/emit!`), and the
  extension ABI registers on the host's ctx like any other listener. The wire
  projection is `kmet.app.wire`. `:agent-settled` carries the
  run's `:outcome` (`:completed` / `:error` / `:aborted`). A front end that
  needs data not on the stream extends the event, not the reader.
- Extension UI splits into runtime capabilities (installed by `bootstrap`,
  cleared by `dispose!`) and the front-end UI port
  (`extensions/install-front-end!`, registered under `[:app :ui]`). Dialogs
  ask through
  `request-ui!`/`respond!`, so an asynchronous client can answer later; the
  degraded headless answers and the per-front-end capability table are in
  [`extension.md`](../modules/app/src/kmet/extension.md).
- The RPC front end ([`rpc.md`](rpc.md)) is the headless consumer;
  `kmet.test-layer-guard` asserts the front ends reach no interactive, TUI, or
  Tk namespace at all.

Tier 1 malli boundary schemas for session entries, config, and auth landed.
The GUI live milestone remains the final GUI-dependent work.

### Session storage façade

`kmet.app.session.core` re-exports the storage API (`store`, `listing`,
`context`, `stats`, `tree`) as `(def name impl/name)` aliases; dependents
require the façade, and `modules/app/test/kmet/app/session/test_facade.clj`
pins its exact public surface. Two consequences for tests and REPL work:

- The aliases capture values at load time — after redefining an
  implementation var, reload `core` (or the whole tree) too.
- A `with-redefs` on a façade alias cannot intercept a call made inside an
  implementation namespace. `context`, `stats` and `tree` call
  `store/get-branch`, `store/get-entry` and `store/append-entry` directly
  (`context/derive-context-settings`, `context/append-context-edit!`,
  `context/build-context`, `stats/get-prompt-history`,
  `stats/get-last-assistant-text`, `tree/common-ancestor-id`,
  `tree/fork-session`), so stubbing `session/get-branch` only affects callers
  that go through the façade — redef the implementation var when the code
  under test runs inside those namespaces.

### Portable host rules

- Prefer `babashka.fs`, `babashka.process`, `clojure.string`, and
  `clojure.java.io`; avoid `java.io.*`/`java.nio.file.*` imports and Java type
  hints. All outbound HTTP goes through `kmet.libs.http`.
- Durations and deadlines use `kmet.libs.concurrent/monotonic-ms` (timeouts,
  sleep slices, backoff, idle reaping, elapsed counters) — never
  `System/currentTimeMillis`, which steps. The wall clock is only for absolute
  values and for comparisons with externally-produced wall-clock data
  (token/JWT expiry, epoch ids, file mtimes, persisted cross-process TTLs).
- Host reader features are `:bb` and `:jolt`, never `:clj`. For a portable
  host carve-out use `#?(:jolt X :default Y)`.
- `kmet.app.core/-main` ends with `System/exit` on the entry's exit code: Jolt
  waits for non-daemon threads before exiting, as the JVM does, so the agent
  pool behind `future` would otherwise hold the process open after `/quit`; the
  explicit exit also covers any thread stuck outside that pool. bb exits on its
  own and the call is harmless there.
- `babashka.fs` and `babashka.process` are built into babashka and vendored by
  Jolt, so only the JVM resolves the Maven copies. Test-only libraries go in
  the root `deps.edn`'s `:dev` alias (a module's `:deps` is its `src` edge
  list); tooling-only dependencies belong in `bb.edn`/`deps.edn`.
- On Termux use `$TMPDIR` or another explicit writable directory rather than
  `/tmp`; `java.io.tmpdir` is unreliable there. Normalize zip entry separators
  before containment checks and normalize `fs/relativize` results with
  `fs/normalize` before comparing or using them as path segments.
- The shell setting resolves a custom path first, then `/bin/bash`, `which
  bash`, and `sh`; Windows uses Git Bash and WSL uses the WSL shell.

## Development workflow

The task implementation lives in `tasks/kmet/tasks/`; packaging and
cross-host build details are covered in the user-facing
[`docs/building.md`](building.md) guide.

```sh
bb start           # Interactive TUI
bb test            # Run fast test suites (excludes ^:slow tests)
bb test-ext        # Run only the slow (^:slow) test suites
bb lint            # clj-kondo over both reader views (babashka + jolt);
                   # `jolt lint` runs the same gate
bb format          # cljfmt (fix) / bb format-check (verify)
bb clean           # Remove build artifacts, caches, logs (--dry-run: list only)
bb nrepl           # Start an nREPL server on port 7801 (blocks)
bb nrepl-jvm       # Start a JVM nREPL server on port 7803 (blocks)
bb check           # Verify all source namespaces compile
bb generate-models     # Regenerate provider + image model catalogs (network)
kmet --generate-models # Refresh the user-level catalog caches (network)
bb check-model-data      # Offline catalog validation (providers + images)
bb slop                # SCBench verbosity (clones+rules)/erosion vs. reference rows, outliers only; --dups lists duplicate groups (source trees; --test adds tests)
bb pack-extension <src-dir> [out.jar]  # Verify + pack an extension artifact root
bb help            # Show task entry points
```

`bb tasks` prints one-line summaries; `bb <task> --help` (the same task works
under `jolt`) prints a task's full usage, arguments and notes.

The `bb nrepl` task starts a Babashka nREPL on port 7801 and writes no port
file; `bb nrepl-jvm [port]` starts the JVM equivalent (default 7803, under the
app's JVM aliases and with the test paths) and writes `.nrepl-port`
(gitignored), which `clojure_eval` discovers first. Jolt's built-in
`jolt nrepl-server 7802` is the Jolt server (its own default is 7888, so pass
7802 or set `JOLT_NREPL_PORT=7802`) and writes the same `.nrepl-port` file;
when the JVM and Jolt servers both run, pass an explicit `port` to
`clojure_eval`.

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
`bb format-check` — are slow. Use the changed-file tasks while iterating and
run the full gates when requested or before release.

The commit-msg hook installed by `sh scripts/install_hooks.sh` refuses
co-author trailers, AI session links, machine addresses and generated-with
footers at commit time; the attribution workflow
(`.github/workflows/attribution.yml`) checks the same rules over a pushed or
PR range. It is the repository's only CI check.
