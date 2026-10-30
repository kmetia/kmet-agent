# kmet2 — the design record: open seams, limits, declined options

**The record of what did not ship.** kmet2 was a second application on kmet's
library modules. It replaced `kmet.agent.*`'s registration model — a
process-global store node, sixteen mutable process globals and a per-extension
capability map — with one Cordis-style context: services keyed by name,
dependencies declared with `inject`, registrations reversible by construction,
and plugins as the only unit of assembly. Every landing in its plan landed; the
architecture it produced is [`docs/architecture.md`](docs/architecture.md), and
the landing ledger lives in git history (this file was deleted at `fc13faf1`
once the ledger closed, recovered at `214b551b`, and trimmed here to the part
that is still open).

The kernel is [`srce.md`](modules/srce/src/kmet/srce/srce.md) — **`srce`**
(Cyrillic **срце**, IPA /sr̩̂t͡se/), heart; Cordis is Latin for the same, which is
what the kernel is to the system. The extension contract is
[`modules/app/src/kmet/extension.md`](modules/app/src/kmet/extension.md).
Historical spellings (kmet1/kmet2, `--app`, `kmet.agent.*`, the pre-retirement
module ranks) name the state of their time; § Reconciliation maps them to HEAD.

## Reconciliation (HEAD `214b551b`)

**One app, one entry.** kmet1 was retired in `5ff5d34d`: `modules/agent` is
gone, `--app` is `:kmet.app/bad-args` (`kmet.app.core/parse-args`; pinned by
`kmet.app.test-core`), and `bb start` / `jolt start` run
`kmet.app.core/-main` directly (the JVM entry is `kmet.app.jvm-main`).
Decisions that described two entries — "same repo, two apps", "kmet1 keeps
shipping", "`--app new` selects kmet2" — are history.

| Rank | Modules |
|---|---|
| 0 | `reakt`, `libs`, `loader` |
| 1 | `hikap`, `ai`, `srce` |
| 2 | `tui`, `tk` |
| 3 | `app` |

`app` holds the front end (`modules/app/src/kmet/ui`, under the same
`kmet.ui.*` names); the app/front-end split is the path rule in
`kmet.test-layer-guard`, not a module edge. The `ui` module landing 5 created
merged back for that reason: with one front end its only content was a rank
edge.

**Names follow modules again.** `kmet.tui.theme` / `kmet.tui.fuzzy` were the
last shared leaves living in `modules/libs` under a `kmet.tui.*` prefix; they
are `kmet.libs.theme` / `kmet.libs.fuzzy`, which leaves the module/prefix rule
with no exception.

**No `mode` module ever existed.** The four modes are rows in `app` —
`kmet.app.modes.print`, `kmet.app.modes.rpc`, `kmet.app.modes.server` and
`kmet.ui.tui` — selected by `--profile`. There is no `:modes` service or
listing registry; the profile row *is* the selector. `kmet.app.modes` also
holds `out.clj`, the output helper the wire modes share.

**Namespaces at HEAD.** The app is `kmet.app.*`, the front ends `kmet.ui.*`,
the kernel `kmet.srce.*`, the extension ABI `kmet.extension`. No namespace
carries kmet1's `kmet.agent.*` prefix — the last survivor, the tool renderers,
was renamed to `kmet.ui.tui.tool-renderers` once the extensions ABI stopped
being a compatibility promise before 1.0 — and `test/kmet/test_retirement.clj`
enforces that.

**Extensions are plugin-only.** `extension.edn` carries `:name`, `:plugin` and
`:loader` — never `:inject`, and never an `:entry`/`init` function: `:inject`
is a key of the plugin map, and the plugin's `:apply` is the extension body. A
single-file extension defines `plugin` in its own namespace. The manifest reader
and the static checks require the plugin var; the additive `:inject` +
`init`-wrapping form that landed at 4b-ii-a was retired with it.

**One event table.** `kmet.app.events` declares the vocabulary and its modes,
owns the owner a registration carries, and holds the vocabulary registry;
`kmet.app.wire` holds the wire projection. kmet1's process table and the
"two tables" bridge are gone.

**Both deferred promises shipped.** The `:ext/…` composition rows resolve
before boot (`kmet.app.extensions.rows`), describe as cards
(`kmet.app.catalog/describe`) and mount as the row's adapter plugin
(`runtime/row-source`) with the row's config as `(:config api)`; and the ambient
composition layers exist (`~/.kmet/agent/patch.edn`, `.kmet/patch.edn`). Both
are now documented in [`docs/architecture.md`](docs/architecture.md)
§ Composition.

## Prior art: DSH

DeepSeek Harness (`dsh`, MIT, developer preview) is the reference harness built
on Cordis, and five of its decisions are folded into the app: the
`:agents`/`:sessions` split (live handles vs the append-only log), the
loop-as-driver that provides `:agentLoop` so modes wait for it, a
`--dump-config` that composes the row tree with the same patch algorithm the
boot uses (no boot, no plugin state) rather than a flat list,
`setup(child-ctx, agent)` composing an agent's scoped world before it is
published, and the **profile/bundle/patch** composition model — ordered rows,
patches by row id, explicit plugin sources — with vars and extension ids in
place of npm package names.

What is not taken: web-first (the TUI is the front end; a second toolkit
provider is separate development and the web comes later), npm as the plugin
boundary (kmet's isolated SCI contexts with a per-extension `deps.edn` are the
stronger isolation story), and dsh's `plugin_manager`-style live
recomposition — the composition tool is open (§ Open at HEAD 1).

## Decisions that still constrain a change

These are load-bearing and not obvious from the code:

- **One key per seam, one implementation per scope.** A plugin writes
  `inject [:tools]`, never `[:registries]`; a per-agent override is per-seam
  rather than all-or-nothing.
- **`:per-agent true` is declined, not deferred.** A preset composes a
  per-agent world, `setup` shadows a service, `isolate` names a realm. A row
  flag nobody reads is exactly what the kernel refuses to ship for `:inject`.
- **No `:modes` listing registry** until something lists modes: `--dump-config`
  already prints the mode rows.
- **`resume!`'s record is the session header.** The log is the durable record;
  an agent store would be a second one, and the server's own rule is "sessions
  are already EDN files; the server adds no new store".
- **`:auth` is process-wide by decision** (phase 1 is one principal). A
  connection brings `:cwd` and `:config`, not credentials. Multi-tenancy later
  is `token → principal → child ctx`, one level above agents.
- **`:models` / `:config` / `:auth` stay libraries.** The model catalog is
  files, a provider is registered with `register-model-provider!` (a registry
  concern, not the request), and credentials are read before a ctx exists.
- **The kernel's vocabulary follows Cordis** (`ctx.get`, `ctx.extend`,
  `ctx.provide`, `ctx.isolate`); the Clojure core shadowing is a host artifact
  confined to the kernel's own namespaces.
- **A teardown and a setup may both hand work back; the kernel still never
  waits.** A disposer that cannot finish inline returns an undelivered promise,
  `dispose!` answers it as `:undoing [{:label :handle}]`, and the lifetime's
  owner awaits — `shutdown!` at exit, the extension host for an async `shutdown`
  (only its code release is deferred; registrations and the record still go
  immediately), a reload before it remounts. A body that cannot finish inline
  answers `{:srce/setup p}`: the fiber is `:starting` with its names invisible
  and the promise on `:setup`, and the pass decides when it settles
  (`srce.md` §4). The marker is explicit where any other fresh return value
  would be read as an effect — a body that starts a thread answers `nil`. What
  stays declined is the state machine behind `:loading`/`:unloading`,
  `fiber.await` and a kernel-owned drain — they need a resume path the app
  already implements once, as its submission queue (`srce.md` §8, §10).

## Everything is a plugin: the rows that are not

The composition can replace the loop, tools, sessions, model access, the
process world, the four modes, the UI, the permission policy, approvals, the
prompt builder, the event bus and the extension host — every one of them a row,
and the generated list is [`docs/composition.md`](docs/composition.md).

Two things are deliberately not rows:

- `:models`, `:config` and `:auth` stay libraries: the catalog is files,
  credentials are read before a ctx exists, and a provider is registered with
  `register-model-provider!` — a registry concern, not the request.
- The tool *executor* is not a seam: `run_code` is an ordinary tool with a
  promise bridge, and the loop executes tools natively.

## Open at HEAD

### 1. The composition tool, and the vocabulary to report it

Composition is edited by humans — `kmet config` and the patch files — and has no
model-facing surface. The **catalog guard landed**: `docs/composition.md` is
generated by `kmet.app.composition-gen`, `bb generate-catalog` /
`bb check-catalog` write and verify it, and `bb test-changed` runs the same
check so a stale page cannot pass.

What is open is the tool: a `:tool/kmet_manage`-shaped row over the writers that
already exist (`kmet.app.extensions.layers`, `kmet.app.tools.config`,
`kmet.app.packages-cli`), returning `:applied` / `:restart-required` with the
reason, permission-classed like dsh's `plugin_manager` (danger-full-access, or
an approval per call).

It inherits an undecided question: **live recomposition**. Diffing a new row
list against mounted fibers and mounting/disposing the delta is dsh's HMR path,
not a flag — kernel-shaped, and worth scoping first to leaf rows (`:ext/*`,
`:tool/*`) whose disposal no dependent re-resolves. Without it the tool answers
`:restart-required` on every enable, which is dsh's no-HMR behavior and an
honest place to stop.

### 2. `permissions/gate` is a call site, not a row

The loop's pre-tool gate is a function the loop calls
(`kmet.app.permissions/gate`), not a hook a row installs, so a profile cannot
replace the gate's *shape* the way it replaces `:retry-policy` or `:loop-guard`.
The policy and the approvals seam are services; the gate's placement is not
yet.

### 3. The compaction queue records no fact

A prompt submitted while a compaction runs lands in the compaction queue, which
records no fact. Recording at submit time would leave a queue fact the delivery
never folds, so whether to record at the flush is still open. It is the one named
residual in the delivery path; every other queue fact is recorded where the
message is actually delivered (`:on-deliver!`, the handle's `:record?`).

### 4. No per-connection command surface on the server

The server serves rpc's command language but has no per-connection command
surface: it registers the builtins and refuses a slash command by name rather
than running one whose reply would be dropped. A per-connection surface needs a
per-connection `:ui` port, and the host's single process atom does not provide
one. Neither is scheduled; the per-agent extension UI port is out for the same
reason.

### 5. The script sandbox's evaluator, and process isolation

`run_code` evaluates on SCI on every host (`docs/architecture.md` § Tools and
the script sandbox). On Jolt, the native loader's `eval-in` (landed upstream)
makes a native evaluator constructible — per-call context, require gate,
bridge, capture and an engine-timer abort — but adopting it trades the
capability restriction for native fidelity, speed and stronger abort coverage:
class resolution becomes the host's, the require gate is advisory for fully
qualified references, and a host var mutation becomes global. That is a
contract decision, not an engineering blocker, and the measurement does not
force it (context economics are identical); SCI stays the default until it is
taken.

The hard-isolation tier is a separate process — a lean `kmet --script` mode or
an RPC-shaped script mode — which is the only way to make a runaway script
killable. It waits on a measured need (boot cost per call) or a second consumer
(a subagent tool, an editor embedding), not on token savings. A native
per-call context would also retain one loader per call until the upstream
eviction fix lands.

### 6. Limits that are part of the design

- **A command handler outside a run reads the process front-end install.** A run
  publishes its surface into the handle's `:front-end` slot and the host reads
  that first, but a command handler (or an extension's own fiber) still reads
  the process install — which is why a connection must not take the slot.
- **An `:ext/…` row belongs to a root profile**, not to an agent preset: the
  extension still mounts app-wide, and only its own `:rows` are its subtree's.
- **A profile comes only from the built-in table.** The ambient patch layers
  exist, but there is no named home profile and no project-shipped profile
  table.
- **A remounted dependent is stateless**; its state lives in a service handle,
  never in a plugin's `apply` closure.

## Not doing

- Forking the repo, or rewriting `kmet.libs.*` / `kmet.ai.*` / `kmet.tui.*` /
  `kmet.tk.*` / `kmet.loader.*`.
- Multi-user accounts, quotas or an ACL model in phase 1 (one principal, a
  documented decision).
- `:ui/web` before a second toolkit provider proves the capability contract;
  `ui-tk` is its own development.
- The pi-alignment deferred families: session-format migration, browser
  transport, telemetry.
- Rewriting the TUI as view-model + renderer — the front-end work was file moves
  and two seams, not a restructure.
- A row flag or service key with no reader, and a second registry beside the
  services (`:registries`, `:modes`, a per-process tool table).
- A `:bundle/ptc` / a second `:toolExecutor`: the bridge serves 2% of `run_code`
  calls and no batching, so a second presentation mode has no consumer
  (`docs/architecture.md` § Tools and the script sandbox).
- The kernel's own exclusions (JS syntax, the loader/HMR/console packages, the
  plugin market, async stack splicing) are listed in
  [`srce.md`](modules/srce/src/kmet/srce/srce.md).
