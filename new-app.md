# kmet2 — a Cordis-shaped kmet on shared modules

**Recovered design note and landing ledger.** It was deleted in `fc13faf1`
once every landing and loose end it tracked had shipped, and recovered at HEAD
`214b551b` (2026-10-27): the code still cites it by name, and the promises it
made that never shipped are recorded instead of lost (§ Reconciliation). Read
it as the design record and the
migration ledger, not as the current architecture — that map is
[`docs/architecture.md`](docs/architecture.md), the kernel is
[`srce.md`](modules/srce/src/kmet/srce/srce.md), and the extension contract is
[`modules/app/src/kmet/extension.md`](modules/app/src/kmet/extension.md).

Historical spellings are kept on purpose: kmet1/kmet2, `--app`, the
pre-retirement module ranks and `kmet.agent.*` name the state of their time.
§ Reconciliation states what HEAD has instead; the landings' statuses are
corrected in place, and superseded paragraphs say so where they stand.

kmet2 was a second application on kmet's library modules. It replaced
`kmet.agent.*`'s registration model — a process-global store node, sixteen
mutable process globals, and a per-extension capability map — with one Cordis-style
context: services keyed by name, dependencies declared with `inject`,
registrations reversible by construction, and plugins as the only unit of
assembly. Everything below the app was to be reused unchanged — the library
modules were; kmet1's `kmet.agent.*` no longer exists at HEAD.

The kernel module is **`srce`** (Cyrillic **срце**, IPA /sr̩̂t͡se/) — heart
(Serbian). Cordis is Latin for the same thing (`cor`, `cordis`), which is what
the kernel is to the system: the one thing every other part is wired through.
The name also stays clear of `kmet.kernel`, `--list-kernels` and prose about OS
kernels.

## Reconciliation (HEAD `214b551b`)

The ledger closed at `fc13faf1`; the tree moved once more before HEAD, and two
promises in this note were not delivered there: `:ext/…` composition rows and
the profile file layers beyond `--patch`. Both have since shipped (the layers
at `~/.kmet/agent/patch.edn` / `.kmet/patch.edn`; the rows through
`kmet.app.extensions.rows`). This section is the truth pass, so the sections
below can stay the design record of their time.

**One app, one entry.** kmet1 was retired in `5ff5d34d`, before this note was
deleted: `modules/agent` is gone, `--app` is `:kmet.app/bad-args`
(`kmet.app.core/parse-args`; pinned by `kmet.app.test-core`), and `bb start` /
`jolt start` run `kmet.app.core/-main` directly (the JVM entry is
`kmet.app.jvm-main`). Decisions #1, #2 and #15 below are therefore history.

**Final module map** (`modules.edn`, strictly upward):

| Rank | Modules |
|---|---|
| 0 | `reakt`, `libs`, `loader` |
| 1 | `hikap`, `ai`, `srce` |
| 2 | `tui`, `tk` |
| 3 | `app` |

The `ui` module that landing 5 created (rank 3, the TUI alone) merged back into
`app`: its files are `modules/app/src/kmet/ui` under the same `kmet.ui.*`
names, and the app/front-end split is the path rule in
`kmet.test-layer-guard` rather than a module edge. `app` moved to rank 3 for
it — it now sits above the component backend the widgets build on.

**Names follow modules again.** `kmet.tui.theme` and `kmet.tui.fuzzy` were the
last two names that did not: shared leaves living in `modules/libs` under a
`kmet.tui.*` prefix, kept because moves did not rename. They are
`kmet.libs.theme` and `kmet.libs.fuzzy` now (pre-1.0, and extensions are
plugin-side only, so the rename was free), which leaves the module/prefix rule
with no exception; the layer guard's libs carve-out went with them.

No `mode` module ever existed: the four modes are rows in `app` —
`kmet.app.modes.print`, `kmet.app.modes.rpc`, `kmet.app.modes.server`, and
`kmet.ui.tui` for interactive — selected by `--profile`. There is no `:modes`
service or listing registry; the profile row *is* the selector (the idea in
§ Pluggable modes was not built). `kmet.app.modes` also holds `out.clj`, the
output helper the wire modes share, not a mode registry.

**Namespaces at HEAD.** The app is `kmet.app.*` (the rename landed with 11),
the front ends are `kmet.ui.*`, the kernel `kmet.srce.*`, the extension ABI
`kmet.extension`. The one `kmet.agent.*` survivor is
`kmet.agent.ui.tui.tool-renderers`, kept because shipped extensions require it
by name; `test/kmet/test_retirement.clj` enforces that it stays the only one.

**Extensions are plugin-only.** The form landed after this note was deleted
(`7802fe97`, `3fc06fdd`, `a8516938`, `494adf9b`, `214b551b`). `extension.edn`
carries `:name`, `:plugin` and `:loader` — never `:inject`, and never an
`:entry`/`init` function: `:inject` is a key of the plugin map, and the
plugin's `:apply` is the extension body. A single-file extension defines
`plugin` in its own namespace. The manifest reader and the static checks
require the plugin var; § The extension host below records the additive form
this replaced.

**One event table.** `kmet.app.events` declares the vocabulary and its modes,
owns the owner a registration carries, and holds the vocabulary registry (a
plugin's `:events` is declared at mount, like `:commands`); `kmet.app.wire`
holds the wire projection. kmet1's process table and the "two tables" bridge are
gone (`6f5b7885`, `62e710d7`, and the kmet1 retirement), so any passage below
that describes a kmet1 bus alongside the app's is history.

**`:ext/…` composition rows (closed).** This was the one promise from
landing 4 that had not shipped. At the reconciliation pass,
`kmet.app.catalog/describe` named an `:ext/…` source and stopped, and
`kmet.app.runtime/row-source` refused it; the row's `:config` never reached the
extension. It now does, end to end:

- `kmet.app.extensions.rows` resolves an id from the installed set before boot
  (`kmet.app.packages/resolve-configured-packages`: top-level entries, auto
  dirs, packages, bundled) without loading anything. The id is the manifest
  `:name`, or a single file's basename without `.clj`; first in pi precedence
  wins.
- `catalog/describe` uses the resolver for the card and
  `runtime/row-source` returns the row's adapter plugin.
- The row's config arrives as `(:config api)` in the extension's api map, and
  the row unloads the extension when it disposes.
- Extensions are **composition-only** now: `kmet.app.world` no longer loads
  any from settings. The installed set (top-level `:extensions` paths, auto
  dirs, packages, bundled artifacts) is discovery; the retired
  `:bundled-extensions` key decides nothing. A run loads exactly the rows its
  composition names.
- `kmet.app.extensions.layers` reads the two ambient layers for the config
  screen and writes its toggles: global scope edits
  `~/.kmet/agent/patch.edn`, project scope writes a row or a `:remove` patch
  in `.kmet/patch.edn`. `/reload` unloads and reloads each mounted row's
  extension with the same config (`kmet.app.extensions.rows/reload-all!`); it
  does not re-read the composition.

The remaining limit: the extension's own plugin still mounts under the host
ctx (app-wide), so an `:ext/…` row belongs to a root profile, not an agent
preset.

## Decisions (locked)

| # | Question | Decision | Consequence |
|---|---|---|---|
| 1 | Where does kmet2 live? | **Same repo**, shared modules | One copy of ~45k library lines; `kmet` and `kmet2` are two entries over one workspace |
| 2 | Does kmet1 keep shipping? | **Yes** | `kmet.agent.*` is untouched; kmet1 is the regression oracle for every kmet2 landing |
| 3 | Server mode scope | **Remote, multi-session, phase 1** | Tokens, per-session ownership, bounded fan-out and idle reaping are phase-1 work, not follow-ups |
| 4 | Hosts | **bb, Jolt, JVM — all portable** | No host-private concurrency or FFI in `kmet.srce.*`, `kmet.app.*`, `kmet.mode.*`; threads/queues go through `kmet.libs.concurrent`, and the kernel is single-threaded with no awaitable of its own |
| 5 | Second UI provider | **Deferred** — `ui-tk` is its own development; web later | The `:ui` contract is proven by the headless and terminal providers (5k, 5l-iii); a second toolkit provider would prove it against `kmet.tk` (Tcl through `babashka.ffi` on bb and `jolt.ffi` on Jolt, absent on the JVM) |
| 6 | Tenancy | **Single principal in phase 1** | One bearer token per server, many clients; `:permissions` scoping is user-level control, not isolation. Multi-tenant later = token → principal → child ctx, one level above agents |
| 7 | The kernel's name | **`srce`** — heart; Cordis is Latin for the same | One prefix, `srce/` alias. `kernel`, `kkernel`, `zrno`, `zrnce` rejected |
| 8 | Plugin ABI | **A validated value**, no `IPlugin` protocol | Cordis's Base fields as a map (`:name :inject :config :provide :apply`), checked once at mount; loading polymorphism stays on `kmet.loader.core/Loader`, and no host class identity crosses the SCI boundary |
| 9 | Shared layer rank | **`app` at 2** (`srce` 1, `ui`/`mode` 3, `agent` 4) | kmet1's rank-4 adapters can call the lifted registries and the new UI; the legacy-above-new inversion is explicit and ends with `agent` |
| 10 | Namespaces on reuse | **Move the file, keep the namespace** | 354 `with-redefs` sites keep intercepting; the final rename is one `s/agent/app/` at retirement |
| 11 | Service keys | **One key per seam**, one implementation per scope | `inject [:tools]`, never `[:registries]`; per-agent overrides stay per-seam |
| 12 | Live agents vs the log | **`:agents`** (handles) + **`:sessions`** (append-only log) | No global "current session" key; an agent is a child context plus a handle |
| 13 | Where the loop lives | **`:agents` registry + `:agentLoop` driver** (dsh's split) | `:agents` is the live registry; the driver provides `:agentLoop`; a preset swaps the loop without touching the registry |
| 14 | Order of reuse | **bus → registry → tools → resources → session → loop → modes** | Each step produces what the next consumes; the first two landings leave kmet1 untouched |
| 15 | Shipping and the entry point | **One artifact, two apps; `--app new` selects kmet2** | `dist` needs no parameterization; the flag is the migration lever, and kmet1 stays the default until it is deleted |
| 16 | Kernel scope | **Light Cordis core** — ctx tree, services + inject + refresh, fiber, effects, events, plugin registry | Only the concepts this file needs; the deferred list and the parity appendix are in [`srce.md`](modules/srce/src/kmet/srce/srce.md), and `kmet.libs.scope`, the store this file's tree replaced, was deleted at 4e |
| 17 | Kernel concurrency | **One owner thread + an optimistic pass flag** | `srce/settle!` at the boundary applies whatever crossed threads; a foreign-thread `provide!` is late, never corrupting ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §3) |

Decision #9's fourth module (`mode`), #10's final rename and #15's flag are the
migration-period plans this note was written under; § Reconciliation maps them
to HEAD (a `mode` module was never built, the rename landed with 11, and the
flag was dropped with kmet1). Decisions #1/#2 likewise describe two entries;
the end state is one app.

"Remote" in phase 1 means **transport and concurrency, not tenancy**: one
principal per process, many clients, per-agent approval scoping (see
"Permissions and approvals"). Multi-tenant is additive because the seam is
already the shape of everything else — the token resolves to a principal, and
each principal gets a child context under the root, exactly as an agent does.

Kernel and application are separate notes: the kernel's ctx model, refresh
pass, thread rule and plugin ABI are [`srce.md`](modules/srce/src/kmet/srce/srce.md); this file is the
application side — services, agents, sessions, profiles, the artifact, and the
landings.

## Prior art: DSH

DeepSeek Harness (`dsh`, MIT, developer preview) is the reference harness built
on Cordis, and five of its decisions are folded in below: the
`:agents`/`:sessions` split (live handles vs the append-only log), the
loop-as-driver that provides `:agentLoop` so modes wait for it,
`--dump-config` (the composed row tree, assembled by the same patch algorithm
the boot uses — no boot, no plugin state, `!!js` left unevaluated; the booted
diagnostic is ours) instead of a flat list, `setup(child-ctx, agent)` composing
an agent's scoped world before it is published, and the
**profile/bundle/patch** composition model — ordered rows, patches by row id,
explicit plugin sources — with vars and extension ids in place of npm package
names, instead of a keyword plugin registry. What is not taken: web-first
(TUI first; a second provider — `ui-tk` — is separate development, web later)
and npm as the plugin boundary — kmet's isolated
SCI contexts with a per-extension `deps.edn` are the stronger isolation story,
and they already exist
(`modules/app/src/kmet/agent/extensions/context.cljc`).

## Inspection: `/units` and `--dump-state`

`--dump-config` answers "what is composed" without booting. The other half is
the booted view. kmet1's `/units` printed one flat `<owner> keys=[…]` line per
store owner, read from a session's own tree when it had one and from the app
root otherwise — the store was the only thing it could ask, so absence was all
it could report.

kmet2 renders the **tree** instead of the store, keeping the two properties
that made `/units` usable: one unit per line with no indentation (the path is
in the line), and a stable order (parents before children, fibers in mount
order).

```
[:app]                             ctx      children=3
[:app :commands]                   fiber#4  :active   effects=[command/units, listener :turn-end]
[:app :sessions]                   fiber#2  :active   provides=[sessions]
[:agent/7f3a]                      ctx      children=2
[:agent/7f3a :tool/read]           fiber#7  :active   inject=[tools]    effects=[tool/read]
[:agent/7f3a :tool/web-search]     fiber#12 :active   inject=[tools]    effects=[tool/web-search]
[:agent/7f3a :ui/terminal]         fiber#9  :pending  inject=[ui]       waiting-for=[ui]
[:agent/7f3a :ui/tk]               fiber#14 :failed   inject=[ui]       error="tcl not found"
```

- A **ctx** line is a path and its child count; a **fiber** line is its state,
  its `inject` (an optional dependency marked `?`, since an absent optional does
  not hold a fiber pending), what it `provides`, and its effect labels.
- **`:pending` and `:failed` are why the command exists at all.** The store
  could only report absence ("nothing registered under that key"); a fiber says
  *waiting for `[:ui]`* or *failed with this error*. `--dump-state` answers it
  for a booted tree too: its boot is a **diagnostic** one
  (`{:diagnose? true}`, `kmet.app.runtime/boot`), so a row that stays `:pending`
  or ends `:failed` is a line in the tree instead of a boot error — a dump that
  fails on the very state it exists to explain would be useless exactly when it
  is wanted. A boot that is meant to run keeps the hard failure; the option is
  the difference, not the mounting.
- **One renderer, two entry points:** `kmet.app.inspect/units` renders the
  subtree of the ctx the command was invoked in (the agent's, when run inside
  one) and `--dump-state` renders the root's. Same argument as
  `--dump-config`: a command and a flag that compute the listing separately
  drift.
- **Effect labels are the inspection contract** ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §4).
  `(srce/effects fiber)` is what the listing renders, so `:tool/read` and
  `:command/units` are load-bearing; a catch-all label makes the line useless.
- The accessors are the kernel's — `srce/children` and `srce/fiber-info` —
  and the projection carries the rest: `:state`, `:error`, the normalized
  `:inject`, the names it `:provides` and what it is `:waiting-for`
  ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §4), so the renderer is a
  flag, not a kernel change. No listing code below rank 3. (`srce/services`
  stays the accessor for "what can this ctx see"; a fiber's own `provides` is the
  answer from the other side and does not need it.)
- **The path is the tree's own.** `boot` names the root `:app`, a plugin mount
  names its ctx from the plugin's `:name`, and a ctx the app built without one
  renders as `:ctx/N` (its position among its siblings), so no two units share
  a line. The path runs from the root even when a subtree was asked for.

Landings: this is **4d**, and it landed. 1–2 leave `/units` alone. 3 was to add
the fiber/service section **beside** the store section and did not. 4c-ii-a made
`/units` **half store, half ctx** — a per-registry-service line beside the owner
walk — so the command stayed useful while the registries lived in the tree and
the store still held what has no seam yet. That was transitional on purpose, and
4d deleted both halves for one tree renderer: the store section could not go
before kmet1 registered in the tree instead of the store and resolved a root ctx
(4c), and the handler re-points at the invocation ctx only once
`runtime/agent-node` is gone. `agent-node` went at 4c-ii-b, so the pair 4d
replaced was the **app root's** store plus the registry lines.

## What the seams settle

Settled by the harness this port follows and by kmet1's own seam tests. The
kernel rows (`inject`, event modes, effect ordering, fiber failures, multiple
mounts) are in [`srce.md`](modules/srce/src/kmet/srce/srce.md).

| Question | Answer |
|---|---|
| `:agents` vs the loop | `:agents` is the live registry; the loop driver provides `:agentLoop` (dsh's `core/agent` / `core/agent-loop`). |
| Agent creation / teardown | `create!` is serialized and rolls back a failed `setup`; `dispose!` is a loop-coordinated awaitable (dsh's `agent/created` + teardown ordering). |
| Reload boundary | The turn boundary, exposed as `:agent-settled`; the kernel's `restart!` is synchronous and `settle!` applies anything that crossed threads, so the app decides when to call either (see "Reload and the turn boundary"). |
| Per-agent capability set | An agent preset (a row set on the child ctx) plus an `isolate` realm for seams that must not inherit. |
| Approvals | `:permissions` policy + `tools/pre-execute` waterfall + one `:approvals` delivery seam. |
| Execution seams | `:workspace`/`:fs`/`:shell`/`:subprocess`/`:sandbox`/`:permissions`, not one `:bash` bundle. |
| Profiles / HMR | Profiles stack bundles and patch by row id; HMR is a loader/profile feature, not the kernel. |
| Plugin sources | Explicit sources in rows; a row's card is read from the plugin map, never from a manifest beside it. |

Adopted from the start, because landings 4–6 cannot compose agents without
them: `:systemPrompt` (per-agent sections are what `setup` registers, and a
tool's prompt snippet belongs to the tool, not to one string builder) and the
session projections behind `:projections` (the inbox and the turn boundary are
derived state over the log). Deferred: dsh's session-format migration family,
the browser transport, and telemetry.

Still kmet-specific: the `:ui` capability contract, tenancy, the wire format,
EDNL sessions, the extension ABI/SCI boundary, and the kmet1 migration.

## Reload and the turn boundary

The kernel is synchronous: `restart!` is dispose-and-mount, and a provider
change runs the refresh pass to a fixpoint before the call returns — unless
another thread already holds the pass, in which case the change is that
owner's (and `settle!` returning with no owner means `:changes` is empty,
[`srce.md`](modules/srce/src/kmet/srce/srce.md) §3) — so *when the app allows a reload at all* is this
document's rule, a kmet rule rather than a borrowed one. Cordis reloads
immediately on an epoch change, and dsh serializes reloads through its HMR
queue (`ctx.hmr.runExclusive`), not through an agent boundary.

- The loop calls `restart!` only at the turn's closing boundary, so a service
  swapped between turns reaches the next attempt and never the one in flight —
  the snapshot rule kmet1's loop stages already follow, applied to services.
  The same rule governs what a request may call: **a request's tool list is one
  capture.** The wire list, the declared set and the dispatch resolve against
  the snapshot taken when the request was built, so a tool unloaded mid-request
  still runs for the request that offered it, and a tool added mid-request is
  not dispatched.
- The boundary is `:agent-settled`: kmet1 already emits it once per prompt from
  the loop's `finally`, and `session/runtime.clj` already treats it as terminal.
  It is synchronous fire-and-forget today, so making it the quiesce point gives
  the bus a serial/awaited path — landing 2 cannot both claim `emit-event!` is
  untouched and hand 7b-i an awaited boundary.
- One owner per concern: `:agentLoop` owns the turn and the queued reload
  (`request-reload!`), `:agents` answers `when-idle`, and `/reload` with no
  agent mid-turn reloads immediately.
- **The boundary is also the drain.** `srce/settle!` runs any provider change
  that landed while another thread held the pass, so `:agent-settled` means
  both "no turn in flight" and "no pending refresh". A tool thread that must
  mount or provide submits instead of mutating: its change is queued and
  applied at the boundary. Landed at 10b: `kmet.app.loop/settle-boundary!`
  drains *before* the emit, and `kmet.app.runtime/boundary!` admits one
  boundary at a time per root — turns run on futures, so two agents can settle
  on two threads, and two drains at once would mount plugin code concurrently
  (a `plugin!` that loses the pass runs its `apply` inline). The static half is
  `kmet.app.test-submit-guard`.
- **Disposal never waits.** The store this design replaced had no clock, and
  the rule survives the store: a teardown does not block on quiescence, because
  the boundary is where quiescence is decided. `:agent-settled` is the dispose
  point; a teardown that finds work in flight cancels it rather than waiting
  ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §4: `dispose!` flips the state, unwinds, settles).
- **A remounted dependent is stateless.** `{:ui :optional}`'s upgrade is
  dispose + mount ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §3), so a plugin's `apply` closure does
  not survive a provider swap. State lives in a service handle (`:agents`,
  `:sessions`); a consumer that needs history reads it from there.

## Why the three-way split has to go

kmet today holds plugin state in three disjoint mechanisms, and each of the new
requirements is blocked by a different one:

| Mechanism | Where | Size | Blocks |
|---|---|---|---|
| the store node | `kmet.libs.scope`, resolved through `kmet.agent.app_runtime/node` | 19 `app-runtime/node` calls in 13 source files; 43 files require the namespace | multi-session: every session resolves **the one** app node |
| process-global atoms | `modules/app/src/kmet/agent/extensions/core.cljc` (`extension-binding`, `extensions`, `session-atom`, `context-sink-atom`, `entry-sink-atom`, `hook-seq`, `cli-flag-values`), `bash-executor` (`shell-options`), `tools/read` (`skill-content-resolver`), `keybindings` (`theme-fns-atom`), `run_code` (`bridge-queue`), `runtime` (`app-node`) | 18 `defonce` vars, 16 of them mutable cells | pluggable UI/modes: the UI port and the mode are process state, not per-context state |
| the extension capability map | `kmet.extension` (461 lines) + `modules/app/src/kmet/agent/extensions/core.cljc` (1,586 lines) built once per `init` | 42 api keys | every new service today is another key on that map |

The kernel does not add a fourth mechanism; it replaces all three with one
context, and `kmet.libs.scope` — 288 lines of owner paths, LIFO disposal,
children-first teardown and closed-store guards, already tested — becomes the
effect half of it.

## Layer map

The final map at HEAD (`modules.edn` is the authority; this table is the same
list, with what each module became):

| Rank | Module | Role at HEAD |
|---|---|---|
| 0 | `reakt`, `libs`, `loader` | reactivity; utilities + libraries; plugin-source resolution |
| 1 | `hikap`, `ai`, `srce` | hiccup engine + components; providers/auth/models; context, services, plugins, events |
| 2 | `tui`, `tk`, `app` | component backends; the Tk backend and its widget sample; the app (services, session layer, extension host, modes, the entry) |
| 3 | `ui` | `kmet.ui.tui` and the front-end-side pieces (`kmet.ui.headless` included) |

The plan's promoted rows are gone: there is no `mode` module (the modes are
rows in `app`) and no `agent` module (kmet1 was deleted). The command is plain
`kmet`: `kmet.app.core/-main` parses args, builds the root context, mounts the
profile's rows, loads the world and runs the selected mode.

Ranks are `modules.edn`'s `:rank` guard: **a module may depend only on a
strictly lower rank** (`kmet.test-module-deps`). `srce` at 1 may require
`libs`, `reakt` and `loader`, and must not require `hikap`, `ai`, `tui`, `tk`
or anything above.

`app` sits at **3**, above the component backends: it holds the front end, and
`kmet.ui.*` builds on `tui` (2), which brings jline and, through hikap, reakt.
`tk` sits with `tui` at 2 with nothing depending on it. The plan's temporary
inversion — `agent` at 4 above `app`, plus a `mode` module at 3 — ended with
kmet1: the modes are rows in `app`, and no `mode` module exists. The `ui`
module landing 5 created is gone for the same reason it was thin: with one
front end, its only content was a rank edge, so it merged into `app` and the
split is a path rule now.

Two consequences of sharing rather than duplicating: `kmet.app.registry` was
kmet1's registry too, so the contract tests (one set of assertions, two
backends) were mandatory from the first landing — historical: kmet1's backend
is gone and the tests run against the ctx backend alone; and the shared layer
must not rename the addresses kmet1's `/units` printed — namespaces move,
addresses stay:
`[:kmet/tool "read"]` and `[:kmet/command "units"]` are the keys a registry
owns, and `[:app :tools]` / `[:app :commands]` are the paths the mount keeps
when the store becomes a ctx tree.

New modules are not `kmet.agent` namespaces: during the migration kmet1 kept
`kmet.agent.*`, and kmet2 claimed `kmet.srce.*`, `kmet.app.*` and `kmet.ui.*`
(the planned `kmet.mode.*` was never built — the modes are `kmet.app.modes.*`
rows). For new code the prefix was the version boundary, so a kmet1 and a kmet2
namespace could never shadow each other on one classpath; a lifted file kept
its `kmet.agent.*` name and moved modules instead (see "Two axes").

Wiring a module touches four manifests that the guard tests hold in step:
`modules.edn`, `bb.edn` `:paths`, `deps.edn` `:jolt/build :embed`, and
`modules/<name>/deps.edn` — plus the `:deps`/`:local/root` edge in the module
that depends on it.

## The extension host

An extension is a plugin whose source is resolved by the extension loader; this
section is how the two models meet.

**At HEAD every extension is a plugin.** The form landed after this note was
deleted (§ Reconciliation). The manifest

```clojure
{:name "my-ext" :plugin my-ext.core/plugin :loader [:sci :jolt]}
```

names a qualified var holding a `srce` plugin map; a single-file extension
defines that var as `plugin` in its own namespace. `:inject` is a key of the
plugin map (the manifest may not carry it), `:apply` is the extension body, and
an optional `shutdown` fn composes into the disposer. The host mounts the var
with `srce/plugin!` under the root ctx, so a pending `:inject` activates when
the last named service appears, and unload disposes the fiber after `shutdown`.

**The additive step this replaced (historical).** 4b-ii-a let `extension.edn`
carry an optional `:inject` and wrapped an `init` namespace as
`{:name (keyword ext-name) :inject (:inject manifest) :apply (fn [ctx cfg]
(init (api-for ctx)))}`; that form was retired when the plugin var became the
only entry — a manifest with `:entry` (or the retired `:init` shape) now fails
the load, and the loader says what to write instead.

```clojure
{:name   (keyword ext-name)
 :inject (:inject manifest)          ; historical: now the plugin map's key
 :apply  (fn [ctx cfg] (init (api-for ctx)))}
```

Without `:inject` an extension behaves as it always did — no waiting, no
reload-on-change — so no shipped or installed extension needed a source change
for the mount semantics, because `api` was always a map. With it, the extension
gets pending/activate/reload semantics for free, and its owner path still
disposes on unload.

**The host moves with the ABI.** The plugin host is not just
`kmet.extension`; it is `kmet.app.extensions.core` (1,586) +
`kmet.app.extensions.context` (1,568), and `create-extension-api` closes over
the app node and the api keys. All three files moved into module `app` at rank
2 (landing 4b); the ABI kept its `kmet.extension` name, and both host files
kept their `kmet.agent.*` names through the move (the "Two axes" rule) until
landing 11 renamed them to `kmet.app.extensions.*`.

- `kmet.extension` has no `kmet.*` requires, so its file move is mechanical;
  installed extensions keep `(require [kmet.extension …])`. The loader's name
  whitelist is **not** just `kmet.extension` + `kmet.libs.*` + `kmet.tui.*`: it
  also admits exactly two shared ABI namespaces,
  `kmet.agent.ui.tui.tool-renderers` (the one survivor of the old prefix) and
  `kmet.app.keybindings` (`extensions/context.cljc`). Both are ABI, both are
  required by shipped directory extensions, and both therefore constrained the
  UI move: a namespace that extensions may require cannot be renamed with a
  `sed`.
- The host takes `ctx` instead of `(app-runtime/node)`: 4b-ii-a did this where
  the tree was the only thing holding the ctx — the mount (`srce/inject!` on
  the app root) — and deleted the node-taking installs. 4b-ii-b's part 2a
  finished the job: the ctx, the registry handle and the resource registries
  all arrive through one bound value, so the host requires nothing from
  `agent` and its api is as per-agent as its binder's services are. kmet1
  (`agent`, rank 4) could require the host (rank 2) during the migration, and
  an extension's own `:require` resolves by name through the loader; at HEAD
  the host is `kmet.app.extensions`, and no host file reads a process default.
- A **key-set contract test** asserts every `(:key api)` the ABI wrappers call
  is present in kmet2's api map; a shipped directory extension and a
  single-file extension load on both hosts. `grep-tool` alone only proves the
  single-file path.
- `shutdown` composes into the disposer (landed): `unload-extension!` calls
  `shutdown` when the plugin's `:apply` ran, deregisters the extension's
  owners, then disposes the fiber. A plugin `:apply` returning a disposer does
  not replace `shutdown`.

This was landing-4 work; all of it landed, and the plugin-only form finished
the job (§ Reconciliation).

## Service catalog

This table is the application — the reason the architecture is worth the
rewrite.

| Service | Contents | Today |
|---|---|---|
| `:world` | the process world a run stands on: provider catalogs + `models.edn`, the config, the themes, the extension CLI flags | **landed** (`kmet.app.world`): the `:world` row provides what `load!` answered as `{:config …}`, is `:defer` (a generic boot never reads the machine's settings), and the entry mounts it with the invocation before the mode |
| `:llm` `:models` | provider call, model registry, auth status | `:llm` **landed** (`kmet.app.llm`: one `send-message` operation the loop and the compaction summarizer resolve through the agent's ctx, the `:llm` row providing `kmet.ai.llm`); `:models`/`:config`/`:auth` stay libraries — the catalog is files, a provider is `register-model-provider!`, and both are read before a ctx exists |
| `:tools` `:commands` `:prompts` `:skills` | registries: name → value, per-context | `kmet.app.registry` + one row per registry (`kmet.app.tools`, `kmet.app.registries`); modes are rows, not a registry |
| `:agents` | live handles: `create!` / `resume!` / `dispose!` / `get` / `list` / `roots`, plus the control surface a front end drives — `send!` / `followup!` / `steer!` / `inject!` / `cancel!` / `when-idle` / `status` / `inbox` (the handle's queued messages, read through `:projections`). A handle owns one agent, its session and its child context, and it is the **capability** that may tear it down; `get` returns the bare agent. The catalog is the seam, not the whole surface: the rest of a front end's ops (`set-model!`, the session lifecycle, compaction, steering modes, `clear-queues!`) are measured from the TUI's own call sites and land at landing 8 (§ "The remaining plan") | **landed** (`kmet.app.agents`, 4c-iii-b): the registry, the child ctx lifetime and the factory seam — what an agent *is* stays the factory's, and no factory mounts until 7a (`kmet.app.loop`'s `Engine` becomes the handle), and the front end's ops until 8 |
| `:agentLoop` | the driver: run a turn, abort, teardown ordering; it also registers the creation factory on `:agents` (dsh's `setFactory`), so `:agents` has no `create!` until a loop is mounted | the loop driver plugin (`kmet.app.loop`) |
| `:sessions` | the append-only session log and its in-memory index, one log per agent | `session/core.clj` (1.7k) |
| `:sessionPersistence` | the storage backend under `:sessions` (EDNL files first; a second backend shadows this key, not the log) | the file half of `session/core.clj` |
| `:projections` | incremental derived state over the log — the inbox, the turn boundaries, the token meter — with a cache behind it | **landed** (`kmet.app.projections`, 4c-iii-b): the inbox is the log (an enqueue and a delivery are two `:custom` facts), and the fold extends from the cached prefix |
| `:systemPrompt` | ordered sections, dynamic contexts, prompt variables and tool schemas, assembled by a waterfall before each request; per-agent composition lands here | **landed at 7a** as `kmet.app.system-prompt` (the app's pure builder over the `:skills` registry), provided as the builder itself; the loop state calls the one it names (`:system-prompt-fn`), an engine resolves caller → ctx row → suite, and `kmet.agent.skills/build-system-prompt` is the last stand-in for a context-free engine |
| `:extensions` | the extension host's own bindings: the registry its tags live in and the ctx an extension's `:inject` mounts under; a contribution with no bound resource registry is refused by name. The handle is a **tag router**: `:kmet/tool`/`:kmet/tool-source` resolve the composition's `:tools` service per call (the loop's table, `/tools` and run_code read only that), every other host tag — hooks, renderers, flags, the UI port, themes — stays in the row's handle. No service key: the binding is the effect, and the handle becomes a key when a consumer reads it | **landed** as `kmet.app.extensions`, with `kmet.app.registries/host-services` as the one service map (the row binds it, and kmet1's shim uses it as its fallback); `kmet.agent.extensions.services` retired at 5l-ii |
| `:events` | the vocabulary as a registry (a plugin's `:events` is declared at mount, like commands), three event domains (session/agent/capability) + six non-awaiting dispatch modes; hooks are host registrations, not a service | `kmet.app.events`, `kmet.app.wire`, `apply-input-hooks` |
| `:ui` | capability map: render, input, dialogs, notify, widget set | `extension-binding :ui`, lifted out |
| `:approvals` | the one delivery seam for `:permissions :ask`: dialog, wire request/response, or headless default | **landed** (`kmet.app.permissions`, 4c-iii-a): the loop's pre-tool waterfall consults it through `permissions/check`; the row mounts the deny-by-default seam, because there is no dialog yet |
| `:workspace` `:fs` `:shell` `:subprocess` `:sandbox` `:permissions` | cwd/project identity, filesystem, shell, process groups, sandbox policy, approval policy | `:permissions` **landed** (4c-iii-a, `kmet.app.permissions`: a policy value the gate reads per agent; no default policy is mounted — see below); `:shell` **landed** (9a, `kmet.app.tools.shell`: a value per agent) and `:sandbox` at 9c (`kmet.app.sandbox`); `:workspace`/`:fs` needed no service — `kmet.libs.path` already resolves against `(:cwd ctx)` and the run binds `*cwd*`, verified at 9d; `:subprocess` is the tool layer's own spawn (`tools/bash`, `bash-executor` — one spawn per execution, no cell) |
| `:config` `:auth` | layered settings, credentials | `config.clj`, `ai/auth.clj` |

**One key per seam, one implementation per scope.** Plugins write
`inject [:tools]`, never `[:registries]`. The registries share one machine
(`kmet.app.registry`: tag + name, 78 lines) but stay separate services, because
their consumers differ — the model sees `:tools`, the slash parser `:commands`,
the prompt builder `:prompts` — and because a per-agent override should be
per-seam rather than all-or-nothing. Cordis's `ctx.tools` / `ctx.llm` /
`ctx.sandbox` is the precedent.

**`:agents` is the registry; the loop is a driver.** dsh's split, adopted:
`core/agent` owns `:agents` (live handles + `agent/*` events) and
`core/agent-loop` owns `:agentLoop` (the driver implementing the interface).
`:agents` exists as soon as the registry is mounted, but it has no `create!`
until a loop registers the creation factory (dsh's `setFactory`), so
`inject [:agentLoop]` holds mode/creation callers pending until a driver is
mounted, and a preset that swaps the loop swaps one plugin — the teardown
ordering lives with the factory that implemented it. `check` covers a service
that becomes unready later.

**No runtime schemas on service values.** A service value is mostly a map of
fns, so a malli schema can only assert `fn?`; the catalog entry plus one
contract test per service is the check that pays. Plugin *config* is still
schema-validated at load.

## Permissions and approvals

Approvals are a **policy plus one delivery seam**, not two request APIs (dsh's
sandbox/approval split). `:permissions` decides; the front end that can ask
provides `:approvals`; the loop's blocking point is the `tools/pre-execute`
waterfall (kmet1's `before-tool-call` chain, ported), whose result is already
`{:block true :reason …}`:

```clojure
(srce/provide! ctx :permissions
  {:policy    (fn [ctx tool-call] :allow | :deny | :ask)  ; pure, per-agent
   :remember! (fn [ctx tool-call scope] …)})             ; :once | :agent | :workspace

(srce/provide! ctx :approvals
  {:ask! (fn [ctx request] …)})                          ; one delivery seam
```

- `:policy` is synchronous and per-agent (`setup` can shadow it), so the common
  case costs nothing.
- `:ask` goes to `:approvals/ask!`, which blocks with a timeout and is abortable
  through the run's `:signal`. The TUI provides `:approvals` with a dialog, the
  server answers later over the wire (the `:request`/`:response` pair), and
  headless answers with its configured default. `:ui/request!` is for generic
  dialogs, not a second approvals channel.
- `:remember!` records a decision at one of three scopes — that is what makes
  remote work without a prompt per call.
- A `:deny` becomes the waterfall result the loop already understands, so **no
  tool signature changes** anywhere.

**Landed at 4c-iii-a** (`kmet.app.permissions`). Two placement rules came out of
the kernel, and they are not obvious:

- **`:permissions` is not in `:bundle/minimal`.** One provider per name per
  realm path is the kernel rule ([`srce.md`](modules/srce/src/kmet/srce/srce.md)
  §3): a child ctx **cannot** shadow a service its ancestor provides, so a
  policy mounted at the app root would be every agent's policy, forever. A
  per-agent decision therefore mounts where nothing above provides one — the
  agent's own ctx, by the preset or `setup` that composes it. An agent ctx
  under a root with no policy allows, which is kmet1's status quo.
- **`:approvals` *is* in the bundle**, at the root: asking is the front end's
  job and it is one process-wide seam. A policy below can ask the seam above;
  the request carries the agent's ctx, so an approver can tell one agent from
  another (naming those ctxs is the `:agents` registry's job, iii-b).
  Until the TUI's dialog lands (5), the seam's default denies — "nobody can
  ask" is not "yes".

The gate is the loop's pre-tool waterfall and it runs **after** the extension
hooks, so a hook's argument rewrite is judged by the policy instead of sneaking
past it; the run_code bridge composes the same gate into `*tool-hooks*`, so a
script's `tools/call` is gated too. A policy that throws, or answers something
that is not a verdict, is a deny: the failure mode of an approval gate must
never be *allow*. `:remember!` stores an approver's scoped answer in the memory the agent's ctx
carries (`permissions/remembering` + `permissions/memory-key`, provided by the
driver over one workspace-wide set), so a tool a human already allowed is not
asked again — and the TUI's dialog is what offers those answers.

## Pluggable UI

`:ui` is a **capability service**, not an inherited widget set. Apps declare
what they need; providers supply what they can. Required of every provider:
`:kind`, `:render-tree`, `:request!`, `:capabilities`;
everything else is optional and declared in `:capabilities`:

```clojure
{:name :ui/terminal
 :apply (fn [ctx _]
          (srce/provide! ctx :ui
            {:kind        :tui
             :render-tree (fn [tree] …)             ; hikap tree → screen
             :widget-set  {:text … :input … :select-list … :markdown …}
             :input!      (fn [ctx ev] …)
             :request!    (fn [ctx req] …)           ; generic dialogs; approvals use :approvals
             :notify      …
             :capabilities #{:chat-history :footer :status :images}}))}
```

Approvals are not a UI capability: the mode (or headless) provides
`:approvals`, and `:request!` is for generic dialogs.

**The widget set is where portability lives.** App UI builds structure from
`:widget-set` constructors, each backend implementing them with its own
toolkit; anything that needs a widget only one toolkit has stays inside that
provider's namespace. That is the rule that keeps landing 5 a move rather than
a rewrite — `kmet.ui.tui` may keep TUI-only chrome, and only the structural
parts are extracted into shared code.

Today's providers. The second-toolkit provider that would prove the contract
against another backend — `ui-tk` — is deferred to its own development.
`:ui/headless` is the one the tree starts from (landing 5k): nothing renders,
dialogs answer by kind, and print/rpc/server stack it — the provider a promotion
starts from, not a stub, because its answers are the ABI's own defaults. The
providers that draw are:

1. `:ui/terminal` — components over `kmet.hikap.core`, which already
   takes its backend map as an argument.
2. `:ui/tk` — deferred to its own development: the same contract, second
   toolkit (Tk through `babashka.ffi`/`jolt.ffi`: bb, the JVM and Jolt),
   starting from the existing `kmet.gui-sample` widget skeleton (in
   `modules/tk`) rather than a blank page.
3. `:ui/web` — later; a third backend, no app change.

App plugins declare `:inject {:ui :optional}` and upgrade themselves when a
richer UI attaches; `(srce/inject! ctx :ui/terminal {:ui :optional} …)` is the
same mount without a plugin map, and its name is required because the listing's
path is built from it. The optional form (see [`srce.md`](modules/srce/src/kmet/srce/srce.md)) puts the
service in the epoch without holding the fiber pending. The upgrade is a
dispose-and-remount, so a UI consumer must be stateless: its state is the
session log, the agent handle and the widget tree, never its own `apply`
closure. The distinction to document once, on the first line of
each namespace: **`kmet.tui` renders components; `kmet.ui` decides which
backend renders.**

The cost is honest and larger than it looks: `modes/interactive/*` is 5,052
lines (`layout.clj` 918, `ui_registry.clj` 708), but the component library it
is written against — `kmet.agent.ui/` — is another 11,126 lines in 37 files (36
of them under `ui/tui/`), and that library is the part the rank guard actually
constrains. The Tk side starts from a working backend plus a widget skeleton
(`modules/tk/src/kmet/gui_sample/`, `kmet.gui-sample.*`: the `:ui/transcript`
scroll container over the extra-tag seam, no app content), so the second
provider needs a transcript, not a backend. Landing 5a moves `kmet.agent.ui.*` and
`modes/interactive/*` into `kmet.ui.tui` and extracts capabilities
incrementally; it does not rewrite them as view-model + renderer. Landing 5b
builds a second provider on that skeleton; that provider (`kmet.ui.tk`) is now
its own development, not a landing here.

## Pluggable modes

```clojure
{:name :mode/rpc
 :inject [:agents]
 :apply (fn [ctx cfg] (serve-stdio ctx cfg))}     ; returns the disposer
```

- A mode is a profile: kmet2's flag is `--profile` (`tui`, `print`, `rpc`,
  `server`), and the profile's rows include the mode plugin, so its `apply`
  starts serving only when that profile boots. `--mode <name>` is the kmet1
  compatibility alias (`--mode rpc` → `--profile rpc`) for one release; it is
  not a `require` in `kmet.agent.cli` (which hard-requires all three today).
- Third-party modes ship as extensions: the loader already isolates them, the
  contract exists, and a profile row resolves their `:ext/…` source (landed
  with the composition rows; § Reconciliation). There is no `:modes` listing
  registry either; the profile row is the only selector. A third-party mode is
  a plugin a profile row names by var, or an extension that contributes
  commands and tools.
- `mode-tui` = interactive, `mode-print` = one-shot, `mode-rpc` = stdio,
  `mode-server` = socket — all four drive the same `:agents` service; they
  already share `bootstrap`.
- `test_headless_guard`'s rule (print/rpc reach no interactive/TUI/Tk
  namespace) becomes a plugin-graph assertion: a headless mode's `inject` set
  contains no `:ui` provider.

## Multi-session server mode

**An agent is a child context + a handle.** The `Engine` record already holds
per-agent state (`agent-state`, `session`, `subscribers`, `:cwd` atom,
`turn-running?`); what is missing is that its *registrations* are global. In
kmet2 each agent is a child ctx, so its model, permission profile, tool set,
skills and extension set are service overrides — no new mechanism. Two keys,
borrowed from DSH: `:agents` is the live registry, `:sessions` the append-only
log.

The `:agents` service value:

```clojure
(agents/create! ctx {:cwd … :model … :session-id …
                     :setup (fn [agent-ctx agent] …)})  ; → handle
(agents/resume! ctx id)                                ; rebuild from the log
(agents/list ctx)   (agents/get ctx id)   (agents/roots ctx)
(agents/send! handle msg {:target :next-turn})         ; queue; :next-step steers
(agents/steer! handle msg)   (agents/follow-up! handle msg)
(agents/inject! handle msg)                            ; out-of-band context
(agents/cancel! handle {:kind :user})
(agents/when-idle handle)                              ; the idle awaitable
(agents/status handle)                                 ; :idle | :running
(agents/dispose! handle)                               ; loop-coordinated awaitable
```

The handle is the capability: `create!` resolves with it, `get` returns the
bare agent, and a consumer that did not create the agent cannot dispose it. The
two `send!` targets are the durable pending lists (`:next-turn` / `:next-step`)
reconstructed from the log by `:projections`, not a second in-memory queue.

`setup` runs before the handle is published: it composes the agent's scoped
world (its tools, prompt sections, listeners) on its own context, so nothing
observes a half-built agent — dsh's `setup(agentCtx, agent)` contract. Creation
is serialized (dsh's `agent/created`): a failed `setup` rolls the child ctx
back and leaves no handle. The loop driver provides `:agentLoop`; `:agents` is
the registry and exists independently.

**Teardown is loop-coordinated.** `dispose!` aborts the live turn, drains the
loop's queue at the turn boundary, then releases the child context; it returns
an awaitable, and no caller observes a half-disposed agent. The loop owns the
ordering (dsh: "unload retains resources through Agent teardown"); the scope
does only the final LIFO release. Idle reaping uses the same path.

**Agent scope: inherit by default, mutate explicitly.** `:agents/create!` loads
no plugins; the agent's child ctx inherits root services through the chain, and
per-agent composition happens in `setup` (a shadowed `:tools`, `:llm`,
`:permissions`), by applying an **agent preset** (a row set on the child ctx),
or by loading a plugin a profile marks `:per-agent true`; a seam that must not
inherit uses an `isolate` realm. Root plugins are mounted once and inherited by
the child ctx; a `:per-agent true` plugin is mounted again on each agent ctx —
a second fiber of the same source — which is the multi-mount case. Isolation is
by context, not by process, so two agents hold different tool tables for free.
A preset that shadows `:tools` starts from an **empty** table — the tool rows
mount on the root, and `:bundle/minimal`'s are what fills it — so a preset that
wants a different set mounts the tool rows it wants on the child (or leaves the
service inherited and selects with `:default-tools`).

`:sessions` is a facade now and a seam by declaration: its value is
`{:append! :read :list :delete :header}` over the existing EDNL files, so a
second backend is a plugin that shadows `:sessions` — no protocol, no
interface.

**Transport is a plugin; `rpc` is a degenerate `server`.** `docs/rpc.md`
already defines line-delimited `{:type … :id …}` EDN records with explicit
correlation. The correlation key becomes **`:agent-id`** (`:session-id` is
accepted as an alias for one release), the multi-agent surface adds
`:agent-create` / `:agent-list` / `:agent-attach` / `:agent-dispose`, and one
`:request`/`:response` pair answers approvals and dialogs (the channel from
"Permissions and approvals"). `--profile rpc` is `mode-server` with one
implicit agent on stdio (`--mode rpc` is the kmet1 alias); transports are a
unix socket where the host has one, TCP loopback otherwise. One protocol, one
implementation, no drift.

**Fan-out backpressure is the new engineering.** `event_bus` runs subscribers
on the emitting thread and requires them not to block; a stalled socket client
would stall the agent. Each client gets a bounded queue, **`:lag` by default**:
drop oldest, emit `{:type :stream-gap :dropped n}`, and let the client resync
with `:get-state`. Dropping the newest would corrupt message assembly silently,
and "the event stream is the API" means a gap has to be visible. The capacity is
the server's (`--queue-depth`, 10c); a per-client override would be an attach
record the protocol does not have yet.

**Process-state audit before claiming multi-session** — these are the
difference between "two sessions" and "two isolated sessions":

| State | Where | Fix |
|---|---|---|
| `shell-options` | `bash_executor.clj` `defonce` | **done in 9a**: a per-agent `:shell` value (`kmet.app.tools.shell`), bound by the run and by the `!` path |
| `srce/isolate` unused | the kernel had realms and no app caller | **done in 9c**: a per-agent `:sandbox` value; `run_code`'s bridge executes a script's inner calls in a realm of the run's ctx |
| `System/getProperty "user.dir"` fallback | `libs/path.clj` | **done** — it resolves against `(:cwd ctx)`; the remaining reads are defaults (see 9's audit) |
| `cli-flag-values`, `extension-binding`, `extensions` | `modules/app/src/kmet/app/extensions/core.cljc` | **superseded by 5i**: the host's bindings are process-wide by design, and the ctx is the per-agent world; landing 9 leaves them with a written reason |
| `bridge-queue` | `tools/run_code.cljc` | **decided in 9b, kept**: a bound, not a setting — settings travel per submission in `:bindings`, and a pool per agent multiplies the threads it exists to cap |
| `skill-content-resolver` | `tools/read` | **done in 9b**: a binding (`read/*skill-resolver*`) the run sets from the agent's `:skills`; the load-time push is gone |
| `theme-fns-atom` | `keybindings` | **done in 9b**: derived from the live theme at call time; nothing is installed |

The tool-layer rows are `modules/app/src/kmet/app/tools/` since 4f; the
extension rows are `modules/app/src/kmet/app/extensions/`. Moving a file did
not take a seam, but landing 9 did: `shell-options` is gone, `read`'s resolver
and the key-hint theme are derived, and the cells that remain are the ones
`kmet.app.test-process-state` holds with a reason each — the extension host's
own bindings, the debug switch, the caches, and the one published root.

Already per-session and safe: `Engine/:cwd`, tool `:cwd` resolution,
`subscribe!` delivery, session files (EDNL on disk).

**Phase-1 remote scope — landed at 10c** ([`docs/serve.md`](docs/serve.md)):
token auth (bearer, one principal), a unix socket where the host has one and TCP
loopback otherwise, `--max-sessions`, per-wire queue depth with the visible
`:stream-gap`, idle reaping on `kmet.libs.concurrent/monotonic-ms`, and
resume-by-id (the session store already supports it). Durability: sessions are
already EDN files; the server adds no new store.

## Reusing `kmet.agent`

### The measurement

`kmet.agent` is 104 source files and 39,765 lines. Exactly 20 touch process
state; the other 84 are already pure functions of their arguments.

| Bucket | Pattern | Files | Lines | Members |
|---|---|---|---|---|
| needs nothing | lift as-is | 84 | 28,178 | tool bodies (`write`/`edit`), the `event-types` vocabulary, and the session layer (2,500 lines across `store`/`listing`/`tree`/`stats`/`context`) — it needs no `ctx`, and its host interop (`ReentrantLock`, `InputStreamReader`, `Instant`) runs unchanged on bb, Jolt and JVM, so it is a file move rather than a re-host |
| one argument | parameterize | 7 | 4,076 | `registry` (3 `runtime/node` reads), `commands/builtins` (6), `modes/interactive/commands` (6), `session/runtime` (3), `loop/state` (2), `core` (1), `modes/interactive` (1) |
| registry-wired | re-point | 4 | 1,305 | `tools/registry`, `skills`, `prompts`, `commands` (4f took `tools/registry`; `skills`/`prompts`/`commands` remain) |
| owns state | re-host | 9 | 6,243 | `kmet.app.extensions.core` (7 `defonce`s), `debug` (3), `kmet.app.extensions.context` (2), `bash_executor`, `event_bus`, `keybindings`, `runtime`, `tools/read`, `tools/run_code` (1 each) |

The fourth row is the only real re-hosting: 6,243 of 39,765 lines (~16%), plus
another ~14% in the one-argument and registry-wired rows. The session layer is
not among them: it is a lift (its `ReentrantLock`, `InputStreamReader` and
`Instant` calls need no porting — verified by running create/append/load under
Jolt, and kmet1 has always run it on all three hosts), and its one non-mechanical
edge is the `kmet.app.debug` require the schema warnings add. It is the part that
owns state — precisely what the context replaces. The bus is the template:
roughly three-fifths of `event-bus.clj` is the `event-types` vocabulary (pure
data, lifts as-is), and the mechanism is one atom plus the three emitters
(`emit`, collect, boundary).

### Order

**bus → registry → tools → commands/prompts/skills → session → extension
host → loop → modes/UI.** Each step produces what the next consumes:

- the bus is one listener table plus the strategies and protocols over it; it
  unblocks the rest, because `srce/on` is how a plugin registers anything and
  `:events` is the first service the composition provides (landing 2: the
  vocabulary and the result protocols are `kmet.app.events`, the table and the
  three dispatch strategies are the kernel's, and kmet1's copy re-exports the
  names until the loop re-points);
- `registry` (78 lines) decides the ctx-addressed lookup shape for all four
  registries at once;
- `tools` is the first real service, and its shape is copied by the next three
  — their handlers already take a ctx-shaped map `{:runtime :ui :frontend}`, so
  that step is mostly renaming keys;
- `Engine` is already a record with operations, so `session` is a lift rather
  than a fork; only its singletons are re-hosted, and the log is a service
  before anything reads it (4a);
- the extension host moves after the session lift and before `Engine` (4a → 4b
  → 4c): it consumes the registries, the tool table, the session log and the
  config/debug services, and it is the landing's one *move*, so it separates
  cleanly from the re-host that follows it;
- loop and modes last, once the kernel has absorbed everything else.

A landing is done when kmet2 uses the new mechanism, kmet1 still uses the old
one, and one test holds both to the same assertions.

### Two axes: the module move and the namespace

Move the file; keep the namespace.

| Axis | Affects | When |
|---|---|---|
| module / classpath root | ranks, embed, packaging | at lift time |
| namespace name | call sites, `with-redefs` interception, the extension ABI | once, at retirement |

The repo already separates them: `reakt` holds `kmet.reakt.*` and `hikap`
holds `kmet.hikap.*`. A module is a classpath root; the namespace is a
name. The one rule is that the path below the root matches the namespace, so
`modules/app/src/kmet/agent/registry.clj` is a `kmet.app.registry` that
simply lives in the `app` module. Tests move the same way and keep their
namespace (`kmet.tasks.modules/ns-symbol` derives it from the path).

Renaming at lift time is the expensive option: `modules/agent/test` holds 354
`with-redefs` sites, and 14 test files reference `kmet.app.config` — the first clean
file anyone would lift. A rename owes a wrapper at the old name exposing
*every* var kmet1 uses, and that wrapper still cannot intercept calls made
*inside* the lifted code. Keeping the namespace keeps every interception point
valid (the move-by-name rule, read backwards: keep the name, never re-point
it), and it keeps the endgame mechanical: at retirement,
`s/kmet\.agent\./kmet.app./` over a known list that excludes
`kmet.extension` (the ABI), the deleted `kmet.agent.runtime`, and the
names re-hosted under `kmet.ui.*`/`kmet.mode.*`.

**Three exceptions.** The extension ABI keeps its name forever — installed
extensions write it in their `:require`, and it already reads `kmet.extension`
(it was renamed off `kmet.agent.extension` before kmet2 existed; nothing in the
repo names the old one now). So the host move is a file move with no ABI
change at all: the file lands in `app` and the namespace does not move with it.
Namespaces whose concept dies are deleted, not moved (`kmet.agent.runtime` has
no successor; its `node`/`*error-reporter*`/`/units` walk live on in
`kmet.agent.app_runtime` until 4c–4d). `kmet.app.loop` (and `kmet.app.loop.*`), `session/runtime` and
`modes/interactive/*` are re-hosted under new names; with `agent` at 4 kmet1
re-points at them instead of keeping a copy — `modes/interactive/*` becomes
`kmet.ui.tui` at landing 5 behind the `--mode` shim. New kmet2-only code uses
the new prefixes from day one.

| Now | At retirement |
|---|---|
| `kmet.app.registry`, `.tools.registry`, `.event-bus`, `.config`, `.skills`, `.prompts`, `.commands` | `kmet.app.*` — one prefix substitution |
| `kmet.extension` | unchanged — the ABI (the file moves to `app`, the name is already the final one) |
| `kmet.agent.runtime` | deleted (folded into `kmet.agent.app_runtime`) |
| `kmet.app.loop` (and subnamespaces), `session/runtime`, `modes/interactive/*` | new `kmet.app.*` / `kmet.ui.tui` |

### Per-lift checklist

1. **Remove the upward require.** `registry.clj` requires
   `kmet.agent.runtime` for `node`; while that edge exists the file cannot
   live at rank 2. Parameterize it to take `ctx` and drop any arity that
   resolves the root — *that commit is the extraction*. One signature defect
   to settle in the same commit: `clear!` already takes a node, as its **last**
   argument (`(clear! tag node)`), while `entry`/`entries`/`by-owner` take
   none — normalize to `ctx` first and update its call sites, or the module
   ships two conventions. kmet1's call sites pass `(runtime/node)`; a
   root-resolving wrapper, if one is needed, lives at rank 3, not in `app`.
2. Lift in reverse intra-module require order; compute it once with
   `kmet.tasks.changed/read-ns-form` + `ns-requires` — it is the only valid
   sequence.
3. `git mv` the file and its test; touch nothing else.
4. Wire the module into the four manifests (`modules.edn`, `bb.edn` `:paths`,
   `deps.edn` `:jolt/build :embed`, and `modules/<name>/deps.edn`), the
   `:deps`/`:local/root` edge in the parent module's `deps.edn`, and the three
   extension `bb.edn`s — `kmet.test-modules-manifest` names whichever you
   missed.
5. Run the changed-file gates and the module-based boundary guard.
6. Add the ledger line.

### Instruments

- **Contract tests, parameterized over both backends** — one assertion set run
  against the store-backed registry and the ctx-backed one, against
  `emit-event!` and `srce/emit!`. While kmet1 lived this is what made "kmet1
  stays green while the mechanism is replaced" a claim rather than a hope; at
  HEAD only the ctx backend remains, and the contract tests still hold the
  registry's and the bus's behavior.
- **A reuse ledger**, per landing: lines *lifted* / *adapted* / *re-hosted*. If
  re-hosted outgrows lifted for two landings running, stop — it usually means
  a state model is being ported instead of a mechanism.

## Profiles and presets

A composition is EDN data, not code, and it is a **row list plus patches** —
the shape dsh's `cordis.yml` + `cordis.patch.yml` uses, with Clojure forms
where Node uses package names:

```clojure
;; a bundle: reusable rows + patches (dsh: dsh-base)
{:bundle/minimal
 {:rows  [{:id :tool/bash :plugin kmet.app.tools.bash/plugin :config {}}
          {:id :tool/edit :plugin kmet.app.tools.edit/plugin :config {}}
          {:id :agent-loop :plugin kmet.app.loop/plugin
           :config {:max-turns 50}}
          {:id :permissions :plugin kmet.app.permissions/policy}]
  :patch [{:id :permissions :config {:policy :allow-all}}]}}

;; a profile: the app entry, stacking bundles (dsh: web / headless / sdk / acp)
{:profile/rpc
 {:bundles [[:bundle/minimal {}]]
  :rows    [{:id :mode/rpc :plugin kmet.mode.rpc/plugin}]}}
```

- A row's `:plugin` is the **source**: a var (`kmet.app.tools.bash/plugin`) for
  a built-in, or `:ext/grep-tool` for an installed extension by id
  (`kmet.app.extensions.rows`). `:id` is the row identity `--dump-config`
  prints and a patch targets; it defaults to the plugin's `:name`.
- **A built-in tool is a row.** `:bundle/minimal` mounts `:tool/read`,
  `:tool/write`, `:tool/edit`, `:tool/bash` and `:tool/run_code`, each injecting
  `:tools` and registering its record as its own fiber's effect
  (`kmet.app.tools.registry/mount-tool!`), so the model's table is what the
  composition mounted and `{:id :tool/bash :remove true}` is how a profile (or a
  preset) runs without bash. The id is the model's own spelling of the tool
  (`(port-name "run_code")` → `:tool/run_code`), so a row in `--dump-config`, a
  label in `--dump-state` and the name the model calls are one word — and
  `builtin-tool-names` is read back off those row ids
  (`registry/bundle-tool-names`), so a tool row added to the bundle is in the
  settings layer's list without a second list to edit. `:default-tools` stays
  the *runtime* selection over the mounted tools.

  A tool row's `:config` says what the model **reads**: `:label`,
  `:description`, `:prompt-snippet` and `:prompt-guidelines` merge over the
  definition (`mount-tool!` → `kmet.app.tools.tool/configure`, the dsh shape
  where a deployment overrides a tool package's model-facing text without
  replacing the package). The tool's behavior — its `:execute`, its
  `:parameters`, its `:name` — stays the plugin's, and an unknown key or a
  wrongly shaped value is a mount error rather than a default that silently
  stayed put.
- A **bundle** is a reusable row list + patches — the analog of dsh's
  `dsh.bundle`; a profile stacks bundles in order, then its own rows and
  patches. Presets (Standard / Minimal / PTC) are bundles.
- Patches are per row: `{:id … :config {…}}` deep-merges the row's config,
  `{:id … :remove true}` drops it, and a patch naming an unknown `:id` with a
  `:plugin` inserts a row (positioned by `:after`/`:before`, else appended).
  A patch that names an unknown row without a `:plugin` is an error; so is a
  duplicate id across stacked bundles. Layers at HEAD: the built-in table <
  `~/.kmet/agent/patch.edn` < `.kmet/patch.edn` < `--patch <file>`
  (repeatable), so a project layer overrides a global one and a flag overrides
  both. `--dump-default-config` prints the table alone. Config *values* still
  deep-merge as `settings.edn` does, composition does not.
- Profiles and bundles were to be EDN staged beside the binary the way
  `target/kmet-bundled` is, so a project could ship its own, and a profile
  under the agent dir could name out-of-tree plugins the way dsh's Harness home
  does. **Partly shipped:** the ambient `patch.edn` layers exist, but
  `kmet.app.profile` still reads only the built-in resource table plus the
  layers the entry point hands it; there is no named home profile and no
  project-shipped profile table.
- **Package subcommands are commands, not profiles.** `install`, `remove`,
  `update`, `list` and `config` run the shared package CLI
  (`kmet.app.packages-cli`, now `app`'s) before the app's own flags, exactly
  as kmet1 dispatches them. The `config` screen is a front end, not a row, so
  `kmet config` resolves it only when the command runs.

## One artifact, one app (historical: two)

**Closed at landing 11** (`5ff5d34d`): `dist` still ships one artifact per host
(`kmet` / `kmetj` / `kmetc`), but it carries one application —
`kmet.app.core/-main`, run directly by `bb start` / `jolt start`, with
`kmet.app.jvm-main` as the JVM entry. `--app` is now `:kmet.app/bad-args`. The
rest of this section is the two-app migration design, kept as the record.

`dist` keeps shipping one artifact per host, and during the migration that
artifact carried **both** applications — which it must, because landings 2–4
make kmet1 call the lifted `kmet.app.*` code. The entry point selected:

```sh
kmet --app new                  # kmet2 (development)
kmet --app new --profile print "…"
bb start --app new              # the same switch from a checkout
kmet                            # kmet1, the default, unchanged
```

- The entry stays `kmet.agent.core`: `build.cljc`'s `main-class` and
  `build_jolt.clj`'s `entry-ns` name it directly, while the JVM uberjar keeps
  its own `main-class` — `kmet.agent.jvm-main`, which `build_jvm.clj` AOT
  `:gen-class`es — and that class's `-main` must keep delegating to
  `core/-main`. That namespace becomes a **bootstrap**
  with no app requires; kmet1's `-main` moves to `kmet.agent.cli`. The
  bootstrap parses `--app` (`new` | `old`, `old` the default), strips the flag
  from the argv, and hands off dynamically — never a static require — to
  `(requiring-resolve 'kmet.agent.cli/-main)` or
  `(requiring-resolve 'kmet.app.core/-main)`. Neither app pays the other
  *entry's* load time — kmet1 does require the shared `app` module it has run on
  since 4c-ii-a, but not kmet2's boot or its modes — and a missing `kmet.app.core` (a `--dev` build, an older artifact)
  answers "this artifact carries only the kmet app" instead of a null
  dereference — the check is the app's source resource, and `dispatch!` reports
  it (`::missing-app`) while `-main` owns the exit code, so the message is a
  test, not a fork.
- `bb start` stays the only start task and keeps requiring `kmet.agent.core`
  (now the bootstrap); a `start2` would be a second entry point to keep in
  sync. If the typing grates, it is one line:
  `:task (apply core/-main "--app" "new" *command-line-args*)`.
- `bb check` requires both app entries (`[kmet.agent.cli]`,
  `[kmet.app.core]`) in its task body, because the dispatch is dynamic and the
  load is the assertion; its `:requires` names the bootstrap only.
- `dist` needs no parameterization: the two entry constants stay put
  (`kmet.agent.core` for the bb/jolt builds, `kmet.agent.jvm-main` for the JVM
  uberjar), the embed list is already every module's `src`, and the artifact
  names describe the host, not the app.
- The one smoke test runs **both** entries (`--app old --version`,
  `--app new --version`), because the dynamic hand-off is the part packaging
  can break. It is still one test, not a per-app test.

What this deletes: `--prefix`, a second `:jolt/build :embed` list, a
`kmet2j`/`kmet2c` naming scheme, and a second smoke test (the one test covers
both entries). What it keeps: one version string, one artifact, one PATH
entry. When kmet1 was retired, `--app new` became the default and the flag was
dropped in the same release — the flag *was* the migration lever, and landing
11 spent it.

## Landings

Each is independently shippable and gated.

| # | Landing | Ships |
|---|---|---|
| 0 | **landed** (`9c0b70ed`) — `modules/srce`: the light kernel — `ctx`/`fiber`/`event` behind the public `srce` | the contract + guard tests ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §12, 44 + 3 tests); no `kmet.agent.*` require, no `kmet.libs.scope` require |
| 1 | **landed** (`367efd49`) — module wiring + guards (module-based require, no-global-node), `--app new` dispatch (bootstrap split: `kmet.agent.core` + `kmet.agent.cli`) + `check` requiring both entries, `--dump-config` over the composed rows | `kmet.test-module-deps` / `test-modules-manifest` green; each guard fails on a deliberate violation |
| 2 | **landed** (`14ca4f0d`) — the bus: the six non-awaiting dispatch modes (`emit!`, `bail!`, `waterfall!`, `collect-last!`, `collect-all!`, `boundary!`) over the kernel's listener table, with `:data` on a registration and `{:in scope}` on `provide!`; `kmet.app.events` keeps the vocabulary (declared in the `:events` plugin's `:events` map), the owner, and the `:events` service; **the submission point** — `kmet.app.runtime`: `submit!` from any thread, `boundary!` drains and settles, `boot` mounts the composed rows | one contract test over both delivery paths (kmet1's bus and the app's), the app's own mode/service/declaration suite, and kmet1 green; a hook registered from another thread lands at the boundary |
| 3 | **landed** — `registry` parameterized then lifted (`kmet.app.registry` over a `Registry` handle); the `:tools` service row; commands/prompts/skills/tools/entry-renderers re-pointed at the handle | the contract test runs one set of registry assertions against both backends (atom and store); the `:tools` service is installed by one `provide!` and mutated through its handle (no re-provide); kmet1 green |
| 4a | **landed** (`c2861df0`) — the sessions seam: `:sessionPersistence` (seven ops: `create!` `load` `paths` `append!` `entries` `delete!` `header`), the `:sessions` log over it (`inject [:sessionPersistence]`: the same ops plus an id index), an in-process `memory-backend`, and kmet1's store as the first real backend (`kmet.app.session.backend`, a 34-line projection — `app` at rank 2 cannot name it yet) | the contract test runs one set of assertions against both backends, then composes the two halves as a profile does (`(service (ednl))`: 8 tests, 60 assertions); the row is `:pending` with `waiting-for=#{:sessionPersistence}` and activates when its backend mounts; kmet1's sessions and their files are byte-for-byte untouched; no composition row at 4a — `:sessions` joined the profile with the layer move (4c-i) |
| 4b | **The extension host**, in two halves. **4b-i landed** (`d01a0f44`) — `kmet.extension` (461 lines, zero requires) moved into `app` with its contract doc, and the **key-set contract test** landed: every api path an ABI wrapper reads (`(:notify (ui api))` reads `[:ui :notify]`) is asserted against the api the host actually builds for a loaded extension, 62 of them, and the test fails when one is renamed away. **4b-ii** splits by axis (§ "Landing 4b-ii: what the require graph says"): **4b-ii-a landed** (§ "Landing 4b-ii-a settled") — with the files still in `agent`: `extension.edn` gained the optional `:inject` and an extension declaring it mounts as the plugin `{:name :inject :apply}` (the manifest form was later replaced by the plugin map's `:inject`, and the whole `:entry`/`init` shape by the plugin var — § Reconciliation), pending until the ctx provides what it waits for; unload disposes the fiber after `shutdown`. The host stopped being store-shaped: hooks, themes, the UI port and the renderers/flags are registry entries, the session/sink mirrors are the process caches they always were, and `install-*`/`discover-resources!` lost their node parameter. **4b-ii-b** is the move of the host files into `app` keeping their namespaces, after the tool layer and `config` lift — now landed as **4f**, which leaves `app-runtime`, `commands`, `prompts` and `skills` as the host's only upward edges. The four leaf lifts landed (`debug`, `event-bus`, `config-schema`, `tools/util`). **4b-ii-b, part 1 landed** — the host's own four clean leaves moved into `app`: `extensions/context.cljc` (1,589 lines), `extensions/libs.clj`, `retry.clj` and `loop_guard.clj`, with `test_libs` and the six bundled-library coordinates following them into `modules/app/deps.edn` (and `sci` leaving `agent`, which no longer mints a context). **4b-ii-b, part 2a landed** — the host's upward edges are cut: it requires nothing from `agent` any more, so its move is a file move. The four edges it had were the process's, not the host's: `commands`, `prompts`, `skills` and `app-runtime` own the registries it registers into, the mount ctx it extends and the handles it resolves. They arrive as one bound value instead — `bind-services!` takes a thunk returning `{:registry :ctx :commands :prompts :skills :deregister-owner!}`, because binding happens while the app root is still being booted and a captured value would freeze whatever handle existed then; a host used before its owner binds throws `:kmet/no-host-services` rather than reading a process default, which is what lets it live below the process it serves. kmet1's binding was `kmet.agent.extensions.services` (retired at 5l-ii; `kmet.app.registries/host-services` is the one builder now; at 4b it was built from `(app-runtime/registry)` and `(app-runtime/ctx)`, its three resource namespaces resolved on first use — a static require would close the cycle through the shim that loads it) and `app-runtime` installs it as its namespace loads. `dispose-deregisters!` moved to `kmet.app.registry`, where the deregistration rules live. Unload now sweeps two lists: the host's own tags (`host-tags`) and the resources through the bound `:deregister-owner!` — and the split immediately found a leak the old all-tags sweep had been hiding by construction: `:kmet/tool-source` was in neither list, so an unloaded extension's sandbox-only tools survived (`kmet.agent.extensions.test-session` loads the mcp adapter, which is how the next namespace's run_code surface grew two exa tools). Pinned twice: `test-lifecycle`'s unload case asserts the source registry is empty (mutation-checked by dropping the tag), and `test-unload-sweeps-every-tag-kmet1-routes` holds the two sweep lists equal to the tags `kmet1` routes — the invariant whose absence caused the leak, checked rather than remembered. Two properties are tests of their own: `test-api`'s `test-the-host-registers-where-its-owner-points-it` binds a fresh handle and a bare ctx and shows the host registers there and not in the process's. **4b-ii-b, part 2 landed** — the host moved: `extensions/core.cljc` (1,624 lines) is `modules/app`'s now, its namespace unchanged and its tests where they were (test trees are cross-cutting). The require graph said it was one step once the services seam landed: `app`'s deps gained `kmet/hikap` — the ABI registers tag aliases — and `agent` dropped `kmet/loader`, whose only src user the host was. `kmet.test-module-deps` failed both ways before the deps were fixed, which is the guard doing the job the classpath union had hidden. The one dependent test that named the old path is `kmet.tasks.test-changed`'s lint-closure case (a change in `kmet.libs.process` must lint the host's call sites); it moved with the file. **4b-ii-b, part 3** finished the directory: `bundled.clj` (the shipped extension artifacts and their discovery) moved too, its only requires being `babashka.fs`, `clojure.*` and `kmet.app.debug` — so `modules/agent/src/kmet/agent/extensions/` is now `services.clj` alone, kmet1's binding, which is kmet1's by definition. **4b is complete**: `kmet.extension`, the host, its leaf dependencies and its isolation contexts are app's; what the host needs from the process arrives as a value | 4b-i: the key-set test is green, its own violation check fails by design, and kmet1's extension surface is unchanged (376 assertions across the extension suites). 4b-ii: an extension declaring `:inject` goes pending then active (test), nothing in the host reads the store node (the host's nine `(scope/` sites are gone), and the loader whitelist loses its `kmet.libs.scope` entry at 4b-ii-b |
| 4c | **The root ctx, the session layer, then `:agents`** — six steps that gate separately, in this order. **4c-i landed** (`aa72deef`, `88513298`) — the session layer moved into `app`: nine files, namespaces kept, nothing re-hosted; the `kmet.app.debug` require behind the schema warnings became a diagnostics flag the host's `--debug` sets; `:sessions` + `:sessionPersistence` joined `:bundle/minimal`, so a bare `kmet --app new` boots four rows. `:result-tokens` stamping stays in `store.clj` — see the 4c-i notes. **4c-ii landed in two halves.** **4c-ii-a** (`f1a446af`, `e38c5e05`) — kmet1 boots the app root ctx and its registrations live in the services: `app-runtime/registry` is one façade handle routing each tag to its service, the writers *and* readers moved together, the `node` parameter left their public fns (~200 call sites), and unload is one `deregister-owner!` per tag. **4c-ii-b landed** — the Engine, the modes and the bus take a ctx: `AgentState`'s `:node` is `:ctx` (the app root by default), `agent-node` is `agent-ctx`, `event_bus/visible` is `srce/inside?` over ctx scopes, and `child-node` is `child-ctx` — the loop's stages ride the root ctx's `:loop-stages` atom (boot's `:ctx-meta`), so `loop-stages`/`refresh-stages!` walk the chain and a child ctx carries its own
(7a moved that map to `kmet.app.loop.stages` and made a refresh target-only). The store sites that stayed then (`discover-resources!` ×3, the `install-*`/`dispose-owner!` set, `/units`' walk) were on the app root node; 4b-ii-a has since taken the host's share of them (§ "Landing 4b-ii-a settled") and the listing is 4d's. The mutable `defonce` cells split the same way: the host's are its process bindings (`extension-binding`, the three sinks, the extension registry), which stay until the host itself is a plugin (4b-ii-b — landed as `kmet.app.extensions`, which binds the host's services per composition and puts back what it replaced when it goes); three are the `:shell`/`:fs`/`:sandbox` seams the catalog names (landing 6); and `event_bus`'s table stays until the loop re-points (7b-i). `registry_store.clj` and `kmet.libs.scope` do **not** die here: the host held nine `scope/…` sites (hooks, themes, the UI port) and the Engine two — both gone at 4b-ii-a — the app node has five, and 4d takes the listing's two, so the store retires at **4e**. **4c-iii is three steps**, split on the dependency the row's gate needs. **4c-iii-a landed** — `:permissions`/`:approvals` (the policy pair) and the loop's pre-tool gate, no files moved. **4c-iii-b landed** — `:agents` (the registry, the factory the loop driver registers at 7) + `:projections` (the inbox as derived state over the log — a durability change for queued messages, so it needed its own gate; § "Landing 4c-iii-b settled"). **4c-iii-c**: `:systemPrompt` + per-agent tool tables, which is the row's own gate: two agents holding different tool tables needs `select-tools`/`active-tools` to read a ctx-sourced registry — the tool-registry lift that 4b-ii-b also waited on, landed as **4f**, which took the handle as a parameter and left kmet1's default as the one cell to remove | i: every session test green from `modules/app`, `kmet --app new` boots a session over EDNL files. ii: the Engine and the modes resolve a ctx, a session's tree is a child ctx, and the bus scopes by ancestry. iii: two agents in one process hold different tool tables, and an extension declaring `:inject {:sessions …}` goes pending then active — **iii-a closes the policy half**: two agents decide differently because each reads its own ctx's policy; the tool-table half is iii-c. **iii-c part 1 landed**: the prompt builder moved to `kmet.agent.system-prompt` (app — now `kmet.app.system-prompt`) with its registry-shaped inputs as arguments — the first piece of the row that is not the loop's, pinned by `kmet.agent.test-system-prompt`; `kmet.agent.skills/build-system-prompt` is kmet1's process-bound wrapper. **iii-c part 2 landed**: the loop resolves its tool table *and* its dispatcher from the agent ctx's `:tools` service (`loop.state/ctx-tool-table`, `tool-executor`; a ctx that provides none throws rather than reading a process default — the rule subagents will meet: a `child-ctx` of the app root inherits `:tools`, one built without a parent must provide its own), and `make-engine` resolves the ctx once so the prompt's table and the state's are the same one — so the row's gate now holds end to end, pinned by `loop.test-tools/test-two-agents-hold-two-tool-tables` (two ctxs, two `probe` records, and a third that settles "Tool probe not found"). Loop tests get theirs from `loop.test-support/test-ctx` — a bare ctx with its own `:tools` and stage map, so a test needs no composition boot. The `:systemPrompt` row is scheduled with its first consumer rather than built now: kmet2 has no loop driver to read it until 7a, and kmet1 builds its prompt through its own runtime, so a row written today would be a service nobody reads. What remains is that row, and two process-table reads that are *not* the loop's: the TUI's display read (`chat-history`'s `get-tool`, moving with the TUI at landing 5) and the extension ABI's `:get-all-tools` — which 4b-ii-b's services binding resolved: it reads the handle its owner bound, so kmet1 sees the process's table and an agent-bound host would see its own |
| 4d | **landed** — **Inspection from the tree**: `kmet.app.inspect/units` renders a ctx's subtree, `--dump-state` boots the composition and renders the root's, `/units` re-points at the invocation ctx's subtree, and both halves of the old listing (the store's owner walk and the per-registry `keys=[…]` lines) are deleted. One kernel addition came with it: `srce/detach!`, the lifetime a plain child ctx (`extend`/`isolate`) had no way to end — the tree listed a line per ctx ever extended, so `test_tool_policy` released its agent ctxs and 4c-iii-b gets the release an agent ctx needs | `--dump-state` and `/units` show a `:pending` fiber with its `waiting-for` and a `:failed` one with its error, and a disposed or detached ctx leaves no line; `kmet.app.test-inspect` (10 tests) plus two rewritten `/units` tests replace the five listing tests |
| 4e | **landed** — **Retire the store**: `kmet.libs.scope` (288 lines, with its two test namespaces), `kmet.agent.registry-store`, `app_runtime`'s `node`/`app-node`/`*error-reporter*` plumbing, the `cli.clj` force that only existed for it, and the loader whitelist entry are gone. Nothing still read it: 4b-ii-a took the host's nine sites, the Engine's two, 4d the `owner-listing` walk, and `registry_store` was its own last reader. `scope.md` went with the code in the same change: its surviving rules moved to the homes that implement them | `grep` finds no `(scope/` under `kmet.agent.*`, no `kmet.libs.scope` or `registry-store` require; `bb test` green; `kmet --app old` loads with no store in the process. 613 lines deleted, no source added |
| 4f | **landed** — **The tool layer into `app`**, in five shippable steps: `tools/bash-output`, `tools/bash-executor`, the seven tool bodies (`tool`, `bash`, `edit`, `write`, `read`, `invoke`, `run-code`), `kmet.app.config`, then `tools/registry` + `tools/config` + `tools/core`. The tool layer now lives in `app` with its namespaces kept — the one exception is kmet1's arity-less façade, which the review moved back to `agent`; `app` declares the SCI backend the sandbox mints contexts with. The one behavioral change: the registry took its handle as a first parameter, so no app namespace requires a rank-4 one, and kmet1's convenience arities moved to a rank-4 façade (`kmet.agent.tools.core`) that supplies `(app-runtime/registry)`. The run_code record is built per registry, so its sandbox seams close over the caller's handle | `kmet.test-module-deps` green after every step (both directions, plus the library view); `bb test` green; `kmet --app old --version`/`--list-models` load; `kmet.app.tools.registry` has no arity that resolves a handle it was not given |
| 5 | **Ten increments have landed — print mode as a row (§ "Landing 5a"), kmet1's one-shot flags + the edn wire (§ "Landing 5b"), the run's front-end surface (§ "Landing 5c"), rpc mode serving the protocol (§ "Landing 5d"), the rank-clean half of the TUI move into the new `ui` module (§ "Landing 5e") the transcript + its two neighbours after the parser and the tool lookup moved to the caller (§ "Landing 5f") the session runtime split into the value a front end reads and the driver that runs it (§ "Landing 5g") the seven selectors moved with no edits of their own (§ "Landing 5h") the registry handle became something the owner publishes, freeing four registries and the last UI file (§ "Landing 5i"), and the TUI became a row too, so `kmet --app new` starts a session (§ "Landing 5j")**. Neither the print mode nor the modes after it could be written before 7b-i: it is the first client of the `:agents` factory rather than the Engine, but its stream *and* its exit result are `:agent-settled` — kmet1's print mode is a `runtime/subscribe!` consumer, and kmet2 has no Engine to subscribe to. Then `:ui` service (required/optional capabilities, widget set) + `ui-headless` (**landed, 5k** — the contract, the provider, `:bundle/headless`) + `ui-terminal` (5l); the rest of the modes as plugins | print landed and at kmet1 parity for the one-shot flags it had (`-p`, `--mode text|edn`, `--model`, `--provider`: streams or prints the wire, writes its session log, exits 0); the `:ui` contract and its headless provider answered their gate (§ "5k"); rpc/tui parity via `scripts/capture/`; the TUI's own terminal provider landed at 5l-iii; a tk provider is separate development |
| 6 | **Complete.** `:agents` is a registry (4c-iii-b), **6a** gave the socket reader its queue (`kmet.libs.concurrent`), **6b** made a connection an agent (`mode-server`: rpc over a socket, `:agent-id` on every record, per-agent event scoping, `--port`), and **6c** put the dialog pair on the wire (a named `:ask-all` policy, a server-owned approvals row, an approval answered by the client) | two agents concurrently **met**, one stalling client **met**, an approval answered by a client **met** — all three in `kmet.app.modes.test-server` |
| 7a | loop re-hosted as a driver plugin (`:agentLoop`), registering the `:agents` factory; the `:systemPrompt` row with its first reader | two agents created through `create!` each run a turn and settle once; `--dump-state` shows the driver active | **landed**: the stage map, the leaves and the whole cluster are `app`'s (names kept), 7a-ii made the resource load and input expansion the caller's, the `:systemPrompt` row reads over `:skills`, and `:agent-loop` registers the `:agents` factory with a runnable handle; the composition-level gate is met too — `:extensions` binds the host, and a composed agent runs a turn (`kmet.app.test-composition-run`); the Engine's `commands` dispatch and its wrapper fallback are the two named residuals; see § "Landing 7a" |
| 7b-i | **landed** — **The events contract — the head of landing 5, not its tail**: the loop dispatches on the kernel from the run's own ctx, so `:agent-settled` is an event a front end subscribes to at the app's scope, while kmet1's process table keeps hearing the same events (the extension ABI registers there; kmet1 boots the composition, so a run cannot tell the two apps apart — only the listeners differ). A boundary threads **one** decision through both: the kernel's accumulated map seeds the table's chain. The composition test no longer listens on kmet1's table | a composed turn settles on the app's bus, and `kmet.app.test-composition-run` subscribes without kmet1's table — **met**; see § "Landing 7b-i settled" |
| 7b-ii | **landed** — presets are bundles: one composition language, two mount points. `runtime/mount-rows!` is the mount loop `boot` and a preset share, `kmet.app.presets` mounts a *bundle* under an agent's child ctx, and `:agents/create!` gained the `:setup` seam that runs between the child ctx and the factory — so per-agent composition happens before an agent exists, and a setup that throws rolls the ctx back the way a failing factory does. `:bundle/gated` (the policy row) is a bundle a profile stacks and a preset mounts; `:rpc-ask` is the profile that stacks it, and `:tui-ask` (T-3) is the first profile whose *agent* mounts it as a preset | pi-alignment green (no pi feature line moves: this is composition machinery); a tool swaps mid-session at the turn boundary — met by `a-tool-swaps-at-the-turn-boundary`; a profile stacking a preset bundle boots — met by `a-profile-may-stack-a-preset-bundle` + `a-preset-composes-one-agents-world`; see § "Landing 7b-ii" |
| 5k | **landed** — the `:ui` capability contract and its headless provider: `kmet.app.ui` (four required keys, checked at the mount, plus the ABI `:port` a mode binds), `kmet.ui.headless`, `:bundle/headless`, and print/rpc binding the port (the server stacks the bundle but binds none until 8's per-agent surface); § "The remaining plan" | every shipped provider answers `:kind :render-tree :request! :capabilities`; a mode's `(ui :request {:kind :confirm})` answers the kind's default (`false`) *from the row* and nil without it |
| 5l | **landed** — the TUI library is `ui`'s: the driver and the builtin command table (5l-i, namespaces kept), the process world's body, the rank-2 root publisher, the load at the entry and one registry publisher (5l-ii), and the interactive cluster, the mode row and the config command (5l-iii) — so a TUI run is kmet2's own front end, with the terminal `:ui` provider and the approval seam a terminal needs; § "The remaining plan" | pty capture parity on `--app new` **met** (same header, resources, extensions, footer); `modules/agent/src/kmet/agent/modes` holds only kmet1's three; an extension's command registered under `--app new` is in the ctx's `:commands`; a TUI profile's ctx provides `:ui` (`:kind :terminal`) and `:approvals` |
| 5n | **landed** — the invocation surface is kmet1's: the run flags (`-t`/`--thinking`, `--system-prompt`, `--append-system-prompt`, `--models`, `--session-dir`) parsed by the entry and resolved before a run, `@file` read at parse time, the maintenance flags (`-d`/`--debug` before the first load, `--generate-models` before the world, `--list-models [filter]` after it — one implementation in `kmet.app.maintenance` for both entries), extension `--flag` values in kmet1's three forms, the skill/prompt expansion chain in print and rpc, and the session-lifecycle flags (`-c`/`--continue`, `-r`/`--resume`, `--session <id>`) handed to the TUI row and refused by name where no factory opens a session (10a's); § "The remaining plan" | `-p @tasks.md` sends the file, `/skill:`-style expansion reaches the agent in print and rpc, `--list-models` is byte-identical to `--app old` bare and filtered, `--session-dir` places the session, `--app new -c` continues the last session, and `--model`/`--thinking` reach a TUI run — met |
| 8 | **done** — the handle is the value a front end reads: its settings/compaction surface (`479eb19d`), per-agent listeners and rpc's model/compaction setters (`c6723127`), a run binding the extension surface to its agent (`2ce32596`), the session lifecycle below both drivers with its ops on the handle (`5a314785`), the TUI's agent as a handle presented from loop events (`6e3b6ce6`), the TUI's session commands and the per-agent front-end surface/sinks plus the builtins' handle path (`8f712922`, `5e9bbc98`), and the gate tests (`44044636` 8g: the agent in the tree, per-agent settings, per-ctx settlement, the image path) | a TUI run's agent is in `:agents` (`:agent/created`), `/model` writes to that agent's ctx, and two print runs in one process settle their own promises (**the last is met at the listener level**: print and rpc subscribe on the agent's ctx, and `kmet.app.test-front-end-gates` settles two agents' promises in one booted composition; no test mounts two print rows in one composition) |
| 9 | **landed** — the execution seams: 9a made `:shell` a per-agent value, 9b made `read`'s skill resolver a binding (and the key-hint theme derived), 9c added the `:sandbox` realm value, 9d the two worlds and the process ledger; `:workspace`/`:fs` needed no service (`kmet.libs.path` resolves against `(:cwd ctx)`), and `:llm`/`:models`/`:config`/`:auth` stayed libraries rather than ctx services — a run's ctx carries `:models`/`:model`, and extension providers register through `models-register-provider!` (later: § Everything is a plugin landing 2 made `:llm` a row; `:models`/`:config`/`:auth` remain libraries) | two agents run shell in two cwds with two shells (`kmet.app.test-isolation`); a script's inner call resolves no host service (`kmet.app.test-sandbox`); the process cells are a ledger (`kmet.app.test-process-state`) — all **met** |
| 10 | **landed** — 10a (the whole wire table), 10b (the boundary drain at `:agent-settled`, one boundary at a time per root, `kmet.app.test-submit-guard` holding the call sites to the owner's), resume-by-id (the registry's `:resume!` resolves the id over the session dir and binds a new agent to the stored session), remembering (an approver's scoped answer kept in the agent's memory, one workspace set per composition), and 10c (the bearer token, the unix socket, `--max-sessions`, the per-wire bounded queue with `:lag`/`:stream-gap`, idle reaping, and the config a connection brings); § "Landing 10c settled" | an agent disposed, resumed by id and answered (**met**, `kmet.app.loop.test-driver`); a tool thread's mount lands at the next turn (**met**, `kmet.app.test-composition-run`); two sessions isolated by cwd, shell and model (**met**, `kmet.app.modes.test-server`); a connection authenticates before it gets an agent, and a client that stops reading gets a `:stream-gap` and resyncs (**met**, the same suite) |
| 11 | **landed** (`5ff5d34d`) — retirement: the `s/kmet\.agent\./kmet.app./` pass, kmet1's files deleted, `--app` flipped to the default and the flag dropped, the `agent` module row removed | the full gate set plus pi-alignment green with no `kmet.agent.*` in the tree except `kmet.agent.ui.tui.tool-renderers`, which `test/kmet/test_retirement.clj` holds as the ABI exception |

**The order is not the table's order.** 7b's events half precedes landing 5:
print mode is a listener, and the only stream kmet2 currently has is kmet1's
process bus — `when-idle` is a barrier, not an answer (`(deref @last-run)` says
the run ended, and `:agent-settled`'s `:outcome` is the only place that says
how). Landing the modes first would put every one of them, and landing 6's
server after them, on the wrong bus and pay the re-point twice.

4b-ii cannot precede 4c-ii: the host required eleven rank-4 namespaces — `commands`, `event-bus`, `prompts`,
`registry-store`, `app-runtime`, `session.core`, `skills`, `tools.core`,
`tools.bash`, `config`, `debug`, plus `extensions/context`, `extensions/libs`
and `kmet.libs.scope` — and it cannot sit at rank 2 until those names are ctx
services. That is the measured correction behind the split: the doc had claimed
the host move was mechanical, and it is mechanical for `kmet.extension` only.
4c-i has since removed the `session.core` edge and `debug`, and 4c-ii-a turned
four of the rest into services.

The practical order was **4a → 4c-i → 4b-i → 4c-ii-a → 4c-ii-b → 4b-ii-a →
4c-iii → 4d → 4e → 4f → 4b-ii-b (its clean leaves first)**, and the reasons are dependencies, not
preferences: 4c-i removed the host's session edge and gave the composition a
real log, 4b-i moved the ABI and pinned the api contract, 4c-ii-b gives the host
a ctx to take, 4b-ii-a puts the host on it, 4c-iii gives the api the agent
surface it exposes, 4d replaces the last listing that walked the store, 4e
deletes it, 4f puts the tool layer below `app`, and 4b-ii-b is the file move
that then becomes possible.
4d ran **before** 4c-iii-b and 4b-ii-b, both of which wait on the tool-registry
lift (4f): a row whose gate names only work it does not need moves up (4d
needed `agent-node` gone, which 4c-ii-b did, and nothing else). 4e ran before it
for the same reason — nothing it needed was still coming.

**4b-ii split again, by measurement** (§ "Landing 4b-ii: what the require
graph says"): the host files can only move into `app` once the ten
`kmet.agent.*` namespaces they reach have left `agent` — the whole tool layer
among them — so the landing's *behavior* (`:inject`, `shutdown` in the
disposer, the host's registrations leaving the store) shipped as
**4b-ii-a**, and the **move** is 4b-ii-b, after the tool layer and `config`
lift — which landed as **4f**. Four of the host's ten moved as leaf lifts
(`debug`, `event-bus`, `tools/util`, `config-schema`), the tool layer and
`config` moved at 4f, and what remains is four registry-wired namespaces
(`app-runtime`, `commands`, `prompts`, `skills`) plus the host's own files.

Two prerequisites have no row of their own and must not be forgotten inside
4c-iii: `:projections` (the inbox and turn boundaries as derived state over the
log, so `send!`'s `:next-turn`/`:next-step` lists exist) and `:systemPrompt`
(per-agent sections, which is what an agent's `setup` registers). `:projections`
landed with 4c-iii-b, inbox included; `:systemPrompt` is still a namespace
(`kmet.app.system-prompt`) without a row, scheduled with its first reader
(7a).
`:inject` moved from 4b-i to 4b-ii for the same reason it was split: a
declared dependency nothing honors is a lie in a manifest.

**Landing 4 is four landings** (4a–4d), split for two reasons. Size and reach:
as one it moved 3,615 lines of extension host with a 42-key api rewired, and
carried a 1,390-line `Engine` lift, 18 `defonce` cells (16 mutable) and 19
`app-runtime/node` readers in 13 files — against 474 net lines at landing 3,
and as the first landing that changes how *kmet1* resolves its world rather than
adding beside it. Composition: it was a lift (`:sessions`), a move (the host), a
re-host (`:agents`) and a renderer in one row, so the reuse ledger — the doc's
own stop rule — could not tell a ported mechanism from a ported state model, and
one extension-ABI failure would have blocked the session log and `/units` for no
reason. Separated, each keeps the one-mechanism-plus-one-contract-test shape and
retires its own `kmet.libs.scope` readers ([`srce.md`](modules/srce/src/kmet/srce/srce.md) §9).

Two consequences worth stating, because both were wrong in the single-landing
version: **no mode shipped in 4** — `kmet --app new` then reported `not-wired`
until landing 5 mounted the modes (historical: `--app` is gone and every mode
ships), so 4c-iii's observable is two differently-scoped
agents, not a prompt; and **4c is three landings in one row**, named as 4c-i,
4c-ii and 4c-iii in the table because each gates on its own — the session layer
moving into `app` needs the interop re-host but not the root ctx, the root ctx
cutover needs neither the session move nor a driver, and `Engine` → `:agents`
needs both. If i and ii turn out to move together, they merge then — with data
rather than a guess.

### Landing 7a — the loop as a driver

**Why 7 splits.** The row bundles three things that do not gate together: the
loop re-hosted as the `:agentLoop` driver, the `:events` re-point, and profiles
as row+patch layers with presets as bundles. And the driver is not last in the
queue any more — a mode plugin injects `:agentLoop`, and `boot` refuses a row
that never activates, so **every mode in landing 5 waits on the driver**. 5's
own order therefore moves behind 7a's first half. Its first increment moves
behind **7b-i** as well: the events contract is not a separate gate from the
modes, it is their prerequisite, because a mode is a listener and the loop
currently reaches kmet1's process bus (see the order note under "Landings").
Presets keep a gate of their own — they change composition, not dispatch, and
nothing listens to them.

**Step 1 landed: the loop's stage map left the process shim.** The two stages
(`:loop-guard`, `:retry-policy`) are `kmet.app.loop.stages` now — `defaults`,
`for-ctx` (the inherited read, once per attempt) and `refresh!` (a reload
re-seats the atom the ctx names, never an ancestor's) — so `kmet.app.loop`
and `kmet.app.compaction.stage` read their stage map from the ctx they were
handed instead of asking the process for it. `kmet.agent.app-runtime` keeps what
is genuinely the process's: the root's `:ctx-meta` seeding, `child-ctx`, and
`refresh-stages!` (the no-arg form `/reload` calls, which names the root). Two
consequences worth recording: a ctx's stage map is read **whole**, not merged
per stage (merging over the defaults is `child-ctx`'s job, as the new test
states), and `refresh!` no longer falls back to the app root when its target
carries no atom — a refresh names what it changes or does nothing.

**The Engine's ctx default is gone.** `bootstrap` used to fall back to the app
root; it now requires `:ctx` in its options, and kmet1's three front ends name
`(app-runtime/ctx)` — the same value, said out loud, which is what the kmet2
factory will pass instead. A missing ctx fails where the prompt is built, with
`kmet/no-tool-service`, not half-way through a turn.

**One edge remains, and it moves with the file.** `loop/state.clj`'s
`make-agent-state` still defaults `:ctx` to the app root. Cutting it means
naming a ctx at ~270 hand-built states in the loop tests, and the test support
already owns that seam (`loop.test-support/test-ctx`, `with-registry`), so the
cut belongs in the commit that moves the file — the support changes once and
the call sites follow it. Until then the cluster's only upward require is that
one line.

Gate, met: `bb test` 4,142 / 24,274 green; `bb lint` 0/0; `bb format-check`
clean; `bb check` compiles; the new `kmet.app.loop.test-stages` pins the read,
the inheritance and the refresh.

**Step 2a landed: three leaves the cluster was reaching for.** `kmet.app.model-resolver`
(259), `kmet.app.project-context` (125) and `kmet.app.git-packages` (546)
moved into `app` — files and tests, namespaces kept — with **no call-site
edits at all**, because nothing in them reaches upward and every caller is a
namespace in `agent` (which already depends on `app`). That is the whole test
of a lift: if the move needs edits, the edits are the part to think about.

**The other three are not lift candidates, and the measurement says so.**
`kmet.app.commands`, `.prompts` and `.skills` each reach
`(app-runtime/registry)` — the *process* handle — because they are kmet1's
façade over the app's `:commands`/`:prompts`/`:skills` services. Threading a
handle through their ~97 call sites would be a lift, but the Engine uses them
for **policy**: expanding slash commands and prompt templates inside `prompt!`,
loading resource directories in `bootstrap`, and building the system prompt
from the process registries. That policy is landing 5's — the modes own input
expansion and resource loading once they are plugins — so the lift would build
the coupling once in order to delete it. The Engine's cut is therefore the step
that overlaps 5, not a prerequisite of the loop's: `:systemPrompt` becomes an
option (the row's first reader), the loader calls leave `bootstrap`, and
command expansion moves to the callers — after which the Engine needs only the
cluster behind it.

Gate, met: `bb test` green (1,243 changed-namespace tests, 5,789 assertions),
`kmet.test-module-deps` 606 assertions hold the new module boundaries, `bb
check` compiles.

**Step 2b landed: a state names its ctx, and the prompt builder is an option.**
`loop/state.clj`'s last two upward edges are gone, and both cuts are semantic:

- **`:ctx` has no process default.** A state names the ctx whose `:tools` table
  and `:loop-stages` it reads; a state with none has no tool table, and
  `ctx-tool-table` says so rather than booting an application to find one. The
  Engine names the ctx it was handed; the loop test support installs a *test*
  process ctx (`*ctx*` / `process-ctx`, one `test-ctx` per suite) and
  `with-registry` binds its own for a block, so ~270 hand-built states name a
  ctx once — in the harness — instead of at every call site. The cut also
  surfaced a test that had been leaning on the process root: the ai module's
  trailing-usage wire regression drives the loop and now gives it a ctx.
- **The prompt rebuild is a `:system-prompt-fn`.** An active-tools change used
  to call `kmet.agent.skills/build-system-prompt` directly — the process-bound
  wrapper whose `:skills` default reads the registry. The state calls the fn it
  was given instead: the app's `kmet.app.system-prompt/build-system-prompt` by
  default, and kmet1's Engine passes the wrapper. That is the **`:systemPrompt`
  row's shape** (a builder the state calls) standing in front of the row, which
  is the order the row was waiting for; the wrapper's `:skills` default is the
  last process-bound piece and moves with the row.

Gate, met: full `bb test` 4,142 / 24,281 green; `bb lint` 0/0; `bb format-check`
clean; `bb check` compiles; `loop/state.clj`'s requires now reach only `app`,
`ai`, `libs` and `srce`.

**Step 2c landed: the first half of the cluster, as a move.** `kmet.app.loop.message`
(177), `kmet.app.loop.state` (1,064) and `kmet.app.compaction.core` (577)
are files in `app` now, namespaces kept — the two-axes rule again, so the move
is a `git mv` and the retirement pass renames them once. Their **tests stay
where they are**, and stay there until the process shim retires:
`loop.test-support` calls `kmet.app.loop` (app now, so that part is fine) but
also redefines `kmet.agent.app-runtime/registry` — the process façade — for its
tool-table tests, which is an agent-module namespace. `bb test-changed` still
finds them: the changed-file closure is the require graph, and the tests require
the moved namespaces. What is left on the other side is now only what still requires
the loop itself — `completion` (247), `tool-exec` (265), `compaction/stage`
(656), the loop (1,177) and the Engine (1,406) — and each of those requires
files already on this side of the line.

Gate, met: `kmet.test-module-deps` 606 assertions hold the three new file
boundaries, 1,323 changed-namespace tests green, `bb check` compiles.

**Step 2d landed: the rest of the loop cluster.** `kmet.app.loop.completion`
(247), `.loop.tool-exec` (265), `.compaction.stage` (656) and `kmet.app.loop`
itself (1,177) moved the same way — `git mv`, names kept — once 2c had put their
requires on the other side. The whole cluster (2,345 more lines) is now
`app`'s, and the only source left in `agent` that the loop *can't* take is the
Engine, whose edges are the policy cut that overlaps landing 5. The loop's tests
still live in `agent` beside the process shim they exercise (see step 2c).

The move needed one guard update, and the guard is the interesting part:
`kmet.agent.test-layer-guard` walked only `modules/agent/src/kmet/agent`, so
after the move it scanned no loop files and said so ("no loop sources
scanned") — a guard that silently stops covering what it was written for is
worse than no guard. It now walks both trees (the app module's `kmet.agent.*`
namespaces are the app's business logic too), and its DAG test covers the loop
at its new home: 109 assertions became 167.

Gate, met: full `bb test` 4,142 / 24,339 green; lint 0/0; format clean; `bb
check` compiles.

**A simplify-and-review pass closed 7a's first half.** Four things, one of them
a real hole in a test this landing wrote:

- **The refresh test was vacuous under a plausible mutation.** It redefined
  `stages/defaults`, refreshed, and read the root's map back — which also
  passes when the root seats *no* atom at all (a ctx with none reads the
  builtin map by inheritance, and the redefined map *is* the builtin one). The
  test now poisons the root's atom and refreshes it: nothing to re-seat is a
  failure, so the assertion does what a weak one only claimed.
- `test-runtime` and `kmet.app.loop.test-stages` were both asserting the map's
  shape; the split is now the seam's (the shim's root identity, the seated
  atom, the child merge and its non-leak) versus the map's (defaults, read,
  refresh, merge).
- Two guards in `kmet.app.loop.stages` re-checked what the kernel already
  treats as absent (`srce/attr` and `srce/ctx?` on nil), so they went; and the
  state's `:system-prompt-fn` field comment and its option docstring said the
  same five lines twice — the field now points at the option.
- One test assertion restated its own helper (`process-ctx` is
  `make-agent-state`'s default by construction); the two meaningful ones stayed.

**7a-ii starts: the Engine stops loading the process's resources.** 7a-ii is
the Engine's policy cut — the last edges it has into kmet1's process, and the
reason it cannot be the `:agents` factory yet. Inventory first: the Engine's
`kmet.agent.*` requires are `commands` ×3 (extension-command *dispatch*, which
is the extension ABI and stays), `prompts` ×2 and `skills` ×1 (input
expansion and the prompt wrapper), and `packages` ×2 (the bootstrap resource
load). So the cut is two options, not a lift:

- **`:load-resources!` (landed).** `bootstrap` no longer calls
  `packages/load-skills!`/`load-prompts!`; it calls the thunk the caller
  names, once, before the prompt is built, and an engine handed none loads
  none. kmet1's three front ends pass `packages/load-resources!` — the
  composition of the unified resolution's two loads, which the `/reload`
  builtin now calls too instead of the pair. The two-axes test bites here: the
  obvious home for that thunk was `app-runtime` (the process shim), and it is
  a require cycle — `packages` reaches `prompts`/`skills`, which reach
  `app-runtime` — so the composition lives with the resolution it composes and
  the *front ends* are what changed. Pinned by
  `test-session-runtime/test-bootstrap-loads-only-what-the-caller-names` (the
  caller's thunk runs once; an engine given none loads none), mutation-checked
  by restoring the unconditional pair.
- **`:expand-input` (landed).** The queue and submit paths expand through the
  chain the caller names — one fn, so the Engine keeps pi's *when* (queued
  text, the prompt's unknown-slash path, the extension `sendUserMessage`'s
  optional chain) and the caller owns the *what*. An engine given none queues
  text as written. kmet1's interactive and rpc front ends pass
  `packages/expand-input` (skill commands, then prompt templates). Print mode
  expands before it prompts and never uses the engine's chain, so it passes
  the loader and not the chain — and its inline copy of the chain became the
  same `packages/expand-input` call, which was the third copy of it. Pinned by
  `test-expansion-is-the-callers`: the caller's chain runs, and none means
  none.

  A review pass on the pair fixed two things and added one guard: the
  `:load-resources!` docstring still named the shim (`app-runtime`) as the
  loader's home, which the cycle had already moved to `packages`; the option
  surface got the rule it was missing (state options ride `:agent-opts`, the
  front end's policy is top-level — `:ctx` is the one that reads both, because
  it is both); and `kmet.test-front-end-policy` now holds the wiring statically
  — each front end's `bootstrap` map literal must name the loader, and the
  chain except print's — because a dropped key is silent: kmet1 would just
  stop loading skills or expanding commands, and the suite would stay green.
  Mutation-checked both ways (layout losing the chain, print gaining one).

  A simplify pass on the same pair tightened four things: the guard's
  `ns-requires` assertion went (lint already fails an unresolved
  `packages/load-resources!`, so it was the same check twice) along with its
  one-use key set; the options docstring now groups the front end's three
  (`:ctx`, `:expand-input`, `:load-resources!`) after the state options instead
  of scattering them, with the "none has a default" rule stated once; the
  expansion test gained the real chain (a registered prompt template expands on
  an engine that names `packages/expand-input` and passes through on one that
  names none — a fake chain proved the seam but not the wiring); and the loader
  now runs once the ctx is named rather than first, so a loader that registers
  into that ctx can read it. `extension.md`'s `:expand-prompt-templates?` entry
  says the host owns the chain, which is what the ABI actually promises.

**The `:systemPrompt` row landed, with a reader that already exists.**
`kmet.app.system-prompt` provides `:system-prompt` over the `:skills` registry:
the app's pure builder with the one input a service owns filled in, and every
other input left to the caller (a tool table read here would be a process
default, the thing the driver exists to replace). It mounts in
`bundle/minimal`, so the composition is eleven rows.

Then the reader: kmet1's Engine resolves `:system-prompt-fn` as **the caller's
own, then the ctx's `:systemPrompt` row, then the process wrapper** — kmet1
boots kmet2 behind its app root, so the row is the prompt policy the two
share, and the wrapper is now only the last stand-in for a bare test engine.
Two consequences: the row is exercised today by every engine test that passes
`(app/ctx)` (not by a synthetic test alone), and the row's `effective-entries`
read makes the prompt list one skill per name — the same skill a lookup
resolves — where the wrapper's raw read could list a duplicate.

Gate, met: full `bb test` 4,152 / 24,382 green; lint 0/0; format clean; `bb
check` compiles; `--dump-state` prints eleven rows.

**The driver landed: `:agent-loop`, and the run derivation moved down with
it.** `kmet.app.loop.driver` is the loop's driver in two halves.

`agent-state` is the whole option derivation a run starts from — provider and
model, the tool table from the ctx's `:tools`, the prompt (from the
`:systemPrompt` row), the compaction/retry/loop-guard settings, the extension
hook chains, the event sink — lifted out of kmet1's `bootstrap` and called by
it. That lift is the point: a run option wired in the Engine and forgotten in
the driver is the drift the app layer exists to prevent, and the alternative
(a second copy for the driver that outlives the Engine) would have built the
duplication exactly where it is hardest to see. The Engine's require list lost
`project-context` and `tools.registry` with it, and gained nothing.

The row registers the `:agents` factory, and the handle it publishes is a
runnable agent: the state over the registry's child ctx, its own session (over
the `:sessions` log, in the cwd-encoded directory the config names), the
row's builder, and a run that starts on the first message and settles on its
own thread. `send!` is the wake-up, never a block: while a run is live the
message goes into the loop's own queue (`:next-step` mid-turn, `:next-turn`
at the boundary). `--dump-state` shows twelve rows and `[:app :agent-loop]
fiber#11 :active`.

**Sending grew one thing, and the inbox stays a queue.** The registry's
`send!` appends the enqueue fact and then wakes the handle — but the delivery
fact is the *driver's* to append, because only it knows the message reached
its run. So the handle's `:send!` now receives the entry too (`:entry` in the
opts, additive), and the driver appends `:deliver!` when it hands the text to
the run or its queue. Without that, a server's inbox would grow forever: the
log is the record, but a record that never says "delivered" is a leak.

**Review of the pair.** One wrong-answer path and three test/doc tightenings:
`resume!` reached the driver as `{:resume <handle-id>}`, and the factory created
a **fresh session** — an agent brought back with a different conversation and
no warning. A handle id is not a session, so the factory refused it
(`:kmet.app/unsupported-op` under the registry's `:agent-create-failed`) until
resume-by-id landed; the factory now resolves the id over the session dir and
binds the handle to the stored session (`:kmet.app/unknown-session` when
nothing carries it), with `kmet.app.loop.test-driver`'s
`resume-binds-the-stored-session` as the gate. The queue test had lost its queue: it asserted the delivered inbox and
then carried a name promising the loop's queue, so it now asserts both (the
inbox drains, the follow-up queue holds the text the run will drain). A
pointless `:or {on-event nil}` went, and the prompt-row test's comment about
the ctx's `:tools` service — the state throws `kmet/no-tool-service` without
one, as the test's first attempt found — is in the fixture where it bites.

The simplify pass after it found one race and closed the "no default" rule's
last silent edge: `send!` read `running?` and *then* asked to start a run, so a
message arriving in that window was neither run nor queued — `start!` is the
CAS that decides, it now answers whether it claimed the run, and the caller
queues on a lost claim. `agent-state` also refuses a nil builder with
`:kmet.app/no-system-prompt` instead of an NPE at the build site, which is what
a no-default option owes its caller. And the handle's `:cancel!`/`:dispose!`
shared their body as one `stop!`.

**The composition runs a turn: `:extensions` is a row.** In a pure kmet2 boot
the extension host's services were unbound — kmet1's shim bound them — so the
loop's first hook lookup threw. `kmet.app.extensions` is that row: it builds the
registry its own tags live in, binds `{:registry … :ctx …}` for the composition
it runs in. It provides no service key: the binding is the effect, and the
handle becomes one when a consumer reads it (the modes' UI at landing 5) —
`:agent-loop` landed on the same rule. The resource registries
(`:commands`, `:prompts`, `:skills`) stay unbound on purpose: installing an
extension is still kmet1's, and those registries' loaders are policy the modes
take with them (landing 5) — so the host's binding made them **optional**, and a
contribution with nowhere to go fails as `kmet/no-resource-registries` naming
the kind, rather than calling nil. The binding is process-wide and one owner at
a time, so `bind-services!` now returns what it replaced and the row's disposer
puts it back: a composition (or a test) that goes away cannot leave the host
pointing at a dead root.

Gate, met end to end: `kmet.app.test-composition-run` boots the real thirteen-row
composition, creates an agent through `create!`, sends it a message, and the run
reaches the model and settles `:completed` with `[:user :assistant]` in its
context.

Two review notes, both about things that cannot fail today and would later.
Removing the row from the bundle makes `test-composition-run` fail exactly as
the unbound host did (`:error`, `[:user]`), so the gate is the row and not the
stub. And kmet1's hook tests (`extensions.test-inject`,
`agent.test-session-runtime`) call `apply-input-hooks`/`get-tool-call-hooks`
without binding, relying on kmet1's load-time binding: a composition test that
leaves its own binding behind hands them an app root's handle. Harmless while
the leaks are hook-free and the namespaces sort agent-first, but it is the kind
of order-dependence retirement should end — those tests should bind their own
services, as `extensions.test-api` already does.

The row also retired a fixture: the driver's tests had stood the binding up by
hand, with a comment saying that is what the row would do — they mount the row
now, so the composition they exercise is the one that ships. And because the
binding is process-wide, the tests put back what they found
(`bind-services!` returns it) rather than leaving the host pointing at a dead
root or unbound; the second test's real subject is that discipline: mount,
mount again, dispose — the first owner's handle answers again. Also found on the way: the loop
cannot run over `kmet.app.sessions/memory-backend`'s session (a nil-message NPE
from the append path) — it is a registry-shaped fixture, not a store session,
so the driver's test uses the real EDNL backend under `target/`.

Gate, met at the driver: two agents created through `create!` each run a turn
and settle exactly once, with their own contexts; `--dump-state` shows the
driver active. Full `bb test` 4,157 / 24,406 green; lint 0/0; format clean;
`bb check` compiles.

  What the Engine still reaches for in `kmet.agent.*` is now two things, and
  neither is a lift: `commands` ×3 for **extension-command dispatch** (the
  extension ABI; a re-point at retirement, not a policy cut) and `skills` for
  the **default prompt builder** — the stand-in the `:systemPrompt` row
  replaces, which is why it waits for the driver rather than moving now.

### Landing 7b-i settled — the loop emits on the app's layer

**Why the loop feeds both tables.** kmet1 boots the composition:
`kmet.agent.app-runtime/ctx` is `kmet.app.runtime/boot` with `:profile :tui`, so
a kmet1 process and a kmet2 process share the *same* root ctx. "Which app is
this" is therefore not a question a run can ask — what differs is who listens:
kmet1's modes subscribe through the Engine's `:on-event`, its extensions through
`kmet.app.event-bus`'s process table, and kmet2's front ends will subscribe on
the kernel. So the loop dispatches on both and does not pretend to choose. The
two tables hold different listeners, so nothing hears an event twice; the kernel
dispatch resolves from the run's own ctx upward, which is what makes a listener
at the app's scope hear what any agent under it did.

**Emitted from the run's own ctx, not from the app's scope.** The `:events`
row's service binds its dispatch to the app's scope, and the loop does *not*
use it: a run emits from the agent's ctx, so its listeners hear its subtree and
a sibling's do not — `test-a-runs-events-belong-to-its-subtree` holds both
halves (mutation-checked by walking to the root first, which leaves the app's
listener working and the run's own silent). The service stays what its
docstring says it is: the app-wide default for a caller with no narrower ctx.

**One boundary, one decision.** `:turn-end` and `:agent-before-settle` are the
two events whose handlers *return* something, and two tables that each thread
their own accumulation would hold two opinions that silently disagree — the
class of bug this migration keeps finding. `emit-boundary!` gained a third
arity: the loop runs the kernel's dispatch first and hands its accumulated map
to the extension table as the seed, so a front end's `:continue` is visible to
every extension handler that follows it, and the chain continues instead of
starting over. (The pure fold is pinned in `kmet.agent.test-event-bus`; the loop
case in `kmet.app.loop.test-boundaries/test-a-boundary-decision-crosses-both-event-layers`,
mutation-checked by passing a nil seed — the front end is still heard, the
extension handler sees a blank slate, and only the second assertion fails.)

Gate, met: `kmet.app.test-composition-run` now subscribes through
`kmet.app.events/on!` at the booted root and receives `:agent-settled
:completed` — the app's layer, not kmet1's process table — and the driver's
tests do the same. Dropping the loop's kernel dispatch makes that test fail
(`:completed` never arrives), so the subscription is testing the dispatch and
not a promise someone else kept.

**Which copy a front end gets.** `:agent-end` is emitted twice on purpose (pi:
`_emit` vs `_emitExtensionEvent`): the public copy carries `:will-retry`, the
extension runner's does not. The app's layer is a *public* sink — it is where a
kmet2 front end listens — so the kernel gets the decorated copy and only the
extension table gets the bare one; `test-loop-emit-reaches-three-sinks-with-two-
copies` holds all three (mutation-checked by handing the kernel the extension
copy, which fails only the front end's assertion). When the ABI moves onto the
kernel that layer becomes mixed and the split has to happen per listener — pi's
answer is the two emissions this arity already does.

**The paths and the vocabulary agree.** A plain event goes down one path and an
actionable one down the other, but the *declaration* is what says which mode a
name is dispatched with, and the two could drift silently: a name declared
`:collect-last` and emitted as an event would be folded differently on each
layer. `test-the-two-dispatch-paths-agree-with-the-vocabulary` runs a live turn,
asserts it reached both paths, and checks every emitted name against its
declared mode (mutation-checked by emitting `:context` where `:status` is).

**What this does not do.** kmet1's own emitters keep their table: the Engine's
`:session-before-switch`/`:user-bash` and the interactive mode's
`:session-start`/`:session-before-tree` are kmet1 code, and their kmet2
equivalents arrive with the modes at landing 5. The extension ABI's
*registration* (`kmet.app.extensions.core`'s `:on-event`) still points at the
process table, which is why extensions keep working in both apps; moving the ABI
to the kernel is retirement's, not this landing's. Presets are 7b-ii.

### Landing 5a — print mode as a row

**A mode is a profile's row, and its input is the invocation's.** The `:print`
profile composes one extra row, marked `:mode true`; `kmet.app.core` finds that
row, merges the command line into its config (`:prompt`, and `:on-result`) and
then boots. The mode's `apply` starts a thread and returns, so the composition
settles before the run starts; `main` waits for the exit code the mode reports
and disposes what it booted. Nothing about the mode is in `core` except that
merge — which is what makes `mode-rpc` and `mode-server` the same shape, and
why the flag is `--profile print` rather than a option the entry point parses.

**The mode owns its output; the entry point owns the process.** It prints the
streamed text and its own errors on stderr, and reports `{:exit n}` — the exit
code is the mode's verdict (`:completed` → 0, `:aborted`/`:error` → 1, and a
shutdown is neither, so it reports 1 without claiming a failure it did not see).
`--profile print "hello"` streams the model's text through the app's event layer
(7b-i's first real consumer), writes the session log the rest of the app reads,
and exits 0.

**A diagnostic mount runs nothing.** `--dump-state` mounts every row of the
profile, so the mode is mounted with no `:on-result`: it registers no listener
and starts no thread, and the dump shows the row `:active` without printing or
blocking. A mount without input is a *question about composition*, not a run —
and a run without a prompt is the named failure (`:kmet.app/no-prompt`), checked
before any listener is registered so a failed mount leaves nothing behind.

**Found on the way, and not fixed here**: a run whose model does not resolve
(no `:provider`/`:model` in the config) dies with a `NullPointerException`
inside the pre-run compaction check, so the prompt reports `:error` with no
message — `"Request failed"` where "no model configured" is the answer. It is
pre-existing (kmet1 reaches it too, e.g. `kmet -p …` against settings without a
model) and it belongs with a named failure where the run's model is resolved,
not with this row; recorded here because print mode is the first kmet2 path that
walks into it. The mode's own test stubs a resolvable model, the way the
composition test does.

**Found by running it: the `:extensions` row was taking kmet1's host away.**
`bb start` printed five `Failed to load extension … this owner bound no
<kind> registry` warnings. kmet1 boots this same composition as its root, so the
composition's `:extensions` row mounted *inside* kmet1 and replaced the binding
kmet1 had made at load — the one carrying the `:commands`/`:prompts`/`:skills`
registries its installers write to, and the registry router that routes a host
tag to the service that owns it. Everything loaded before the row was fine;
everything after it had nowhere to put a command. The row now **overlays**:
its `:registry` and `:ctx` win, and every other key the previous binding
supplied carries over (`extensions/host-binding` reads the binding unresolved —
resolving it during a boot would re-enter the ctx delay that is booting). A
composition with nothing bound still gets exactly its own two keys, so a
contribution with nowhere to go is still refused by name. Pinned by
`a-process-hosts-resource-registries-survive-the-row`; deleting the merge
reproduces the warning verbatim. The alternative — kmet1 rebinding after the
composition boots — would have been a process workaround for a row contract,
and it only works because kmet1's root is a delay it can append to.

**Its follow-up: extension tools were landing in a handle nobody reads.** The
row's `:registry` is a fresh handle, and the host registers an extension's tools
(and run_code-only tool sources) through that same handle — while a run's tool
table is the composition's `:tools` service. Everything looked loaded: `/tools`
listed `clojure_edit`, `clojure_eval`, the MCP tools, and the model saw none of
them. The extension's *skill* still appeared, because resources go through the
base's routed registries. The row now binds a **tag router** like kmet1's:
`:kmet/tool` and `:kmet/tool-source` resolve the mount site's `:tools` service
per call (the row mounts before the `:tools` row, and a bare composition may
mount none — those registrations stay in the row's handle), every other host tag
stays local. Pinned by `extension-tools-reach-the-compositions-tool-table`;
deleting the route reproduces the split — the tool registers, the table stays the
five built-ins — and the unload sweep is asserted on the same service.

### Landing 5b — kmet1's one-shot spellings, and the wire

**`--mode` is kmet1's word and it means two things**, so it keeps meaning both:
`text` and `edn` are print mode's output formats and select the print profile
with that `:format`; every other value names a profile, so `--mode rpc` is
`--profile rpc` (and today honestly reports that the rpc profile has no runnable
mode yet). `-p`/`--print` are `--mode text`. `--mode` and `--profile` choose the
same thing, so passing both is a usage error rather than a silent pick — the
same rule the two dumps already followed.

**The overrides ride the agent, not the process.** `--model`/`--provider` go
into the mode's input, become `create!` options, and the driver's factory applies
them to the settings it loads (`cfg/apply-cli-overrides`, the function kmet1 uses
for the same flags). `--model` is kmet1's **pattern**
(`provider/model[:thinking]`, provider inferred when absent), so the factory
resolves it against the catalog first (`model-resolver/resolve-cli-model`) and
takes the resulting provider, model id and thinking level: a pattern nothing
matches is `:kmet.app/unknown-model` *before* the run, with kmet1's own wording,
rather than a request that dies later with nothing to say. The `:model` in
settings stays a plain id — a flag is a pattern, a file says what it means. That is the seam landing 6 wants anyway: an agent's model is
an option *of the agent*, so two agents of one server can answer with different
models, and no run edits a process-global config. The test asserts what the
provider call received, not that a map was threaded: dropping the override leaves
the run on the settings' model.

**edn mode prints the wire.** The session header first — a consumer reads it for
the id its events belong to — then every event the app's vocabulary declares,
projected by `wire-event` (a `:message-update` carries the delta, not the
cumulative snapshot). A failure is an event, so `:edn` prints no error line on
stderr and the exit code is the verdict, while `:text` keeps its line. Two events
trail the settle in the stream (`:queue-update`, `:status`) because the mode
disposes its listeners when the settle arrives: the wire is what the loop said,
not what a mode wanted to hear. A failure is on the wire too, exactly once — the
`{:type :error :message …}` event — so edn mode adds no line of its own and the
exit code is the verdict; text mode keeps its stderr line. The projections stay in `kmet.app.event-bus`
(`app`) until the modes' move claims them — transport with the transport.

Not in this increment: kmet1's other run flags (`--thinking`, `--system-prompt`,
`--append-system-prompt`, `--models`, `-c/--continue`, `-r/--resume`,
`--session`, `@files`). They are run options rather than mode selection, and the
session ones need a session that can be resumed — which the driver refuses by
name today (`:kmet.app/unsupported-op`), so a `--session` would have nothing
honest to do with its argument yet. `@path` is refused the same way, and for the
same kind of reason: reading a file into the message is one branch of a mode's
input expansion (skill commands, prompt templates, `@files`), which kmet1 runs in
its print mode before prompting and kmet2's modes have not taken over. Sending
`@tasks.md` as the prompt would be a wrong answer; the refusal names
`--app old -p @tasks.md`, where it still reads files.

### Landing 5c — the run says what kind of front end it is

Print mode now binds the extension **front-end surface** while it runs —
`:mode :print`, `has-ui` false, the headless capability port — and puts the
dispatcher's default back when the run ends. kmet1 does the same a layer down,
from its session runtime, and the difference was observable: an extension
branching on `ctx.mode` saw nil under `--app new`, `(ui :set-theme "x")` got nil
instead of `{:success false}`, and `(ui :request {:kind :confirm})` got nil where
kmet1 answers with the kind's default ("ask is not yes"). A process's default
stays maximally inert — that is what a process with *no* front end is — and a
run that has one says so.

**Review note.** The first version put the surface back with
`unbind-extensions!`, which resets to the dispatcher's default — the same
"replace what you found" mistake the `:extensions` row was fixed for. A process
can have a front end *before* the run (an embedder's port, another mode in the
same process), and a run's teardown must not take it away: the mode reads
`front-end-surface` before it binds and binds that back afterwards. The bind also
moved after the listener setup, so a run that fails while subscribing leaves no
surface behind — the failure mode the `:extensions` row's own comment names.

A mode **binds** the surface rather than installing it (`install-front-end!`):
installing also registers the port under `[:app :ui]` so a listing can show who
holds it, which is right for a front end that owns a port for the process's life
(the TUI's) and wrong for a mode's ephemeral bind — an entry per run to sweep
afterwards. The binding is the read path either way.

**What is still kmet1's on that surface: the per-agent half.** `install-session!`,
the context and entry sinks, `bind-runtime-capabilities!` and the `:build-context`
capability all live in kmet1's session runtime; kmet2's runs bind the front-end
kind and port and nothing else, so an extension handler sees the host's own
defaults — `:model nil`, `:is-idle` true, the session facades answering nil.
That is not a front-end fact but an agent one, and it cannot be fixed before the
per-agent scoping it needs: the host's session slot is one process atom, which is
the same constraint that keeps print mode's event listeners at the app scope. It
is landing 6's line, with the per-agent listeners, and it is the piece that makes
a second mode in one process meaningful. (`8f` has since landed the per-agent
half: the front end publishes mode, port and sinks on the agent, and a run's
handlers prefer them over the process install — § row 8.) `mode-rpc` has its own
bind to remember when it lands: kmet1's rpc mode is `:mode :rpc`, `has-ui true`,
with a dialog port that forwards requests to the client — the headless port is
print mode's, not rpc's.

**The `:ui` service has its first provider.** § "Pluggable UI" has it as a
capability *service* (`:kind`, `:render-tree`, `:request!`, `:widget-set`,
`:capabilities`). 5k landed the contract and the headless provider, and every
profile that renders nothing stacks it: print and rpc bind the row's ABI port
(rpc adds the wire's capabilities on top). The modes *require* `:ui` (a mode
without a port cannot serve its extensions), while an app plugin that upgrades
itself when a richer UI attaches declares `{:ui :optional}`. The first provider
that *draws* is `ui-terminal`, which waits for 5l's move; the dialog surface rpc
still lacks is landing 10a's command table (the `:request`/`:response` pair over
the wire), which the row's port is the base for.

### Landing 5d — rpc mode serves the protocol

`:mode-rpc` is the second mode row and the first that is not headless. It boots
the same thirteen rows and then serves kmet1's protocol: an EDN command per
line on stdin, a record per line on stdout, and the run's events as the same
`wire-event` projection edn print mode carries. One agent per mode, created
before the first command and disposed when stdin closes.

**A prompt is accepted, not completed.** `{:disposition :started}` says the run
began; the answer arrives as `:message-update` deltas and the run ends at
`:agent-settled`. A prompt that arrives while a run is live must name
`:streaming-behavior` (`:steer` / `:follow-up`) — pi errors without one, because
the two queue targets mean different things and guessing would drop the message
or deliver it at the wrong boundary.

**The command set is what kmet2's services answer**, and nothing more: prompt,
steer, follow-up, abort, get-state, get-entries, get-available-models. Every
other command in kmet1's table answers a *named refusal* naming the landing that
fills it — `set-model` and the session lifecycle are landing 6's per-agent
surface and branch-aware log, `compact` is landing 7's seam, `respond` is
landing 6's `:request`/`:response` pair. A silent success would be a wrong
answer; `"Unknown command"` would read as a typo.

**The surface is a front end's, not headless.** The run binds `:mode :rpc`,
`has-ui true` and a port whose fire-and-forget channels travel the protocol
(`:notify`, `:set-status`, `:set-widget`, `:set-title`, `:chat-info`,
`:set-editor-text`/`:paste-to-editor`; widget content crosses only as string
lines). Dialogs stay the headless default: this mode has one agent and no
correlation for a pending question, and a bridged dialog nobody could answer
would be worse than the degraded answer kmet1's noOp port gives.

**An abort answers a read, not a wait.** The handle's cancel drops its run flag
on the calling thread, so the response says `{:idle true}` by the time the cancel
returns, and a front end whose cancel is only a request would answer
`{:idle false}` and let the client wait for the settle. kmet1 polls its engine
for the same flag plus a compaction it knows about; kmet2's handle has neither at
stake, and a wait here would occupy the reader thread — the one thread an abort
must not take.

**`--prompt true` is a row key, not a profile fact.** Print mode's input is the
command line's remaining arguments, so an empty one is a usage error *before
anything boots*; rpc mode has no such input, and a bare `--profile rpc` is legal
(it waits on stdin). The row says which it is, and `mode-input` omits a prompt
the invocation did not give rather than passing an empty string.

**Simplify pass.** `command-prompt`'s nested `if`/`cond` is one flat `cond` over
the values it reads once, and the two queue commands lost their one-line wrappers
to a shared `enqueue!`: the record's own `:type` is the behavior it queues for,
so `:steer` and `:follow-up` are one implementation and two table entries. The
surface-pair rule — install the front end, and put back what was there — moved
into `kmet.app.front-end/install!`, which returns the restore; print mode and rpc
mode call the same function instead of each writing the rule, so the review that
found the reset bug is now the code's docstring rather than two comments.

**Shared, not copied.** `kmet.app.events/on-every` is the one-subscription
"every declared event" (print's edn stream and rpc's stream are the same
subscription), and `kmet.app.modes.out` is the modes' output discipline — one
locked record per line on an explicit stream, one stderr line for the mode's own
failure. Print mode lost its third copy of both. The mode-test harness
(`with-mode-run`, `answering`, the session dir, `run-main-into`, `wait-until`)
moved to `kmet.app.test-support`, because print and rpc tests are the same boot
with a different mode.

**Testing a mode whose stdin ends its process.** Closing stdin ends the mode, and
a mode that ends with a run live cancels it — kmet1's rule, and right for a
client that left. A test that only wanted to read the answer therefore cannot use
`with-in-str`: it owns a loopback socket, keeps the mode's stdin open until the
wire shows `:agent-settled`, then closes it. Verified by running the real
command: `printf '{:type :get-state…}' | kmet --app new --profile rpc` answers
the live state, the loaded catalog (41 models) and a named refusal for an unknown
command.

**Review note: the invocation's model was not reaching the run.** The mode
created its agent with no options, so `--model` and `--provider` — which
`mode-input` hands every mode and print mode passes to `create!` — were ignored
in rpc mode, silently: the command line named a model and the run used the
settings'. The agent's options are the mode's to pass (`select-keys config
[:model :provider]`), and the driver resolves a pattern per creation as it does
for print. The test drives a real pattern (`--model
opencode-go/deepseek-v4-flash:high`) and reads the answer back from
`:get-state`; with the options dropped it reports `:off` where it must report
`:high`.

The mode also reads `*in*`/`*out*` where it stands rather than offering a
config-supplied reader: a caller drives the mode by binding them, and a
row-config seam nobody set was one more thing to get wrong.

**Review 2 adds two refusals and a transcript.** `:prompt` now refuses
`:images` by name like the queue commands do: the wire carries text, the handle
takes a string, and an image block would have been dropped between the two — a
client that sent one learns it did not arrive. And `:get-entries` — the only
transcript surface — is covered, which is what showed that the log's interleaving
is the threads': the delivery fact lands when the run takes the message, so it
may sit before or after the turn's first entry. The test asserts the facts (the
turn ends the log, the queue facts are in it, every entry has an id), not their
order; the first version asserted the order and failed one run in eleven.

Inherited, not fixed: a model record crosses the wire as the tagged literal
`#kmet.ai.models.Model{…}` (kmet1 prints the same), and a clean shutdown emits
`{:type :queue-update :reason :aborted}` because the handle's dispose cancels
unconditionally (kmet1's dispose does too).

### Landing 5e — the TUI moves in pieces, and the guard picks the pieces

The TUI's move is not one commit, and the reason is arithmetic rather than taste.
Its 47 files require 24 `kmet.agent.*` namespaces; fifteen already live in `app`
(rank 2), and **nine do not** — `session.runtime`, `app-runtime`, `commands`
(+ `builtins`), `prompts`, `skills`, `packages`, `tools.core`. A file in the new
`ui` module (rank 3) may reach only rank ≤ 2, so a wholesale move would fail the
rank guard, and a module whose namespaces are checked file-by-file cannot be
talked out of it.

So the cut is what the requires already say: **29 of the 47 files reach nothing
above `app`** — the component library, the dialogs, the renderers, the pickers
that take their data as arguments. 27 moved (two went back, below). The other
20 — 11 still under `kmet.agent.ui.tui.*` and the 9 `modes/interactive` files —
are the composition layer written against kmet1's runtime (`layout`,
`builtins`, `ui_registry`, `chat-history`, the selectors that fork or switch
sessions), and they stay in `agent` until their edge is parameterized: they are
landing 6's per-agent surface, where a UI reads a handle and a ctx instead of a
process.

**The move.** New module `ui` at rank 3, holding `modules/ui/src` with 27 source
namespaces (6,918 lines) and 22 test namespaces; `kmet.agent.ui.X` became
`kmet.ui.X` (`kmet.ui.tui.*` for the TUI's own). `kmet.app.keybindings` — the
one rank-4 file the components needed and that reached nothing itself — lifted to
`app`. Plumbing that the manifest guard checks: a
`modules/ui/deps.edn` naming exactly the six modules the moved files require, the
manifest row, the `bb.edn`/`deps.edn` literals, `agent`'s new `kmet/ui` edge, and
the three extension `bb.edn` classpaths (an extension that requires
`tool-renderers` must be able to find it).

**The one name a `sed` may not touch.** `kmet.agent.ui.tui.tool-renderers` keeps
its name in the new module: shipped extensions write it in their `:require`
(`extensions/clojure`, `extensions/lsp-adapter`), and the loader whitelist admits
it by name. The rename was written, tested against the extension tree, and
reverted — the file moves, the namespace does not. `kmet.app.keybindings` is the
same case for the same reason.

**Two files the guard caught that the first scan did not.** `external_editor` and
`suspend` require `kmet.agent.ui.tui.chat-history` — a *sibling* that is blocked,
not an external module, so a scan that only looks outside the package calls them
clean. They went back. The lesson is the guard's, not the scanner's: the edge is
the edge, whether the target is in the same package or another module.

**Path-scanning tests are part of the move.** `test-caching-conventions` enumerates
the component trees — five now, because the non-recursive `fs/list-dir` means the
module root alone would scan nothing — and the extension library-list test names
the two source files it expects to exist. Both now point at the files' homes.

**A latent lint finding, surfaced by the move.** `test_auth_selector` carried
`(:refer-clojure :exclude [selector])`, which excludes a var `clojure.core` does
not have: clj-kondo reports it, and no one had seen it because the file had not
been touched since the rule started applying. Removing the dead exclusion is the
fix. It is also the reminder that `lint-changed` is the frequent gate and
`lint` is the one that finds what a long-untouched file is carrying.

**Review: the guards that watched the tree have to watch both halves.** kmet1's
`test_layer_guard` scanned `modules/agent/src/kmet/agent/ui` for two rules — a
front end must not reach `kmet.app.loop`, and the app layer must not reach a
front end — and after the move it silently scanned 11 files instead of 38. The
rank guard takes over the second rule (rank 3 → 4 is upward), but **not the
first**: `kmet.app.loop` sits at rank 2, below `ui`, so a moved component could
require the loop driver and every guard would pass. Both rules now scan the
union of the trees the layer lives in, and `kmet.ui.` joined the forbidden
front-end prefixes. Mutation-checked: a `kmet.app.loop` require added to
`kmet.ui.tui.footer` fails `front-ends-drive-the-runtime-not-the-loop` by name.

The union was wrong at first, and the next read found it: the file that kept its
`kmet.agent.ui.*` name lives under `modules/ui/src/kmet/agent/ui`, so a walk of
`modules/ui/src/kmet/ui` covered everything that was renamed and nothing that was
not. Three roots, and the guard's own assertion now names each one it expects to
have scanned — which is what caught it.

The extension classpaths were the same question one level out: the three
extension projects list every module src root in their `bb.edn`, and
`test-modules-manifest` already holds them to the manifest
(`extension-bb-edn-paths-cover-the-module-trees`), so adding `modules/ui/src`
there was checked rather than remembered. A second version of that test was
written in this review and deleted — the guard existed.

The docs that name namespaces were updated too — `modules/tui/src/kmet/tui/tui.md`
(the TUI's rules doc) and the layer README say `kmet.ui.tui.*` where the component
moved, and name both halves where they describe the layer as a whole. The ledger
is history and keeps its wording.

**Evidence.** `--app old` boots and the TUI renders (tmux capture:
header, footer hints, the context/skills/prompts/extensions panels); `--app new`
runs rpc mode; `bb test` 4,193 / 24,669; `bb lint` (full tree, both views) 0
errors, 0 warnings, 0 findings; `bb check` compiles; format clean.

### Landing 6 — the server, and the queue it needs first

Landing 6's row is one sentence with five deliverables: `mode-server` (rpc as a
socket server, one agent per connection), `:agent-id` and the request/response
pair on the wire, approvals end to end, and the **queue primitive** in
`kmet.libs.concurrent` for the socket reader. Its gate is two agents concurrently,
one stalling client, and one approval answered by a client.

The queue comes first because nothing else can be written honestly without it. A
server's shape is decided by where the blocking read happens: one thread parks in
`read-line` per connection, and the owner thread — the only thread allowed to touch
an agent — consumes what it read. That crossing is
`kmet.libs.concurrent/queue`: any number of producers, **one consumer**, `close!`
to end it.

Three decisions worth the words:

- **One atom, one deciding swap.** The queue's state is `{:items :closed? :signal}`
  and a take is a *single* `swap!` that either hands out the next item or parks the
  taker on a promise a later `put!` delivers. Deciding outside the swap is the
  classic lost-wakeup bug (take reads "empty", put enqueues and delivers nothing,
  take parks forever), and it is the reason this is not a `PersistentQueue` in an
  atom plus an ad-hoc `when (seq …)`.
- **`closed` is a keyword, not nil.** A reader's `read-line` answers nil at end of
  input, so a consumer must be able to tell *nothing more will come* from *an empty
  line*. `close!` also drains: items already queued are still handed over, and only
  then does the take answer the marker — so a producer's last lines are not lost to
  a close.
- **A refused put is reported, not swallowed.** `put!` on a closed queue returns
  false; a caller that queues into a finished connection learns it at the call
  instead of believing it was sent.

Single consumer is the contract, not a courtesy: `take!` parks what it handed out
in the queue's own state for the duration of one swap, so two takers would race
that slot. Two consumers need two queues, and the server has one owner per
connection, so it never needs two.

**Evidence.** Seven new tests in `kmet.libs.test-concurrent`: order, a parked taker
woken by a put, 200 rounds of a put racing a taker, close-drains-then-ends, close
waking a parked taker, nil-as-a-value, and the one the review added — *a handed-out
item leaves nothing behind*. Each guard mutation-checked: removing `put!`'s
delivery fails the parked-taker test, removing `close!`'s fails the close-wake test,
answering nil for `closed` fails three, a `put!` that claims success after close
fails the drain test, and — the gap the first six missed — **removing the `:handed`
cleanup is invisible to them**: a non-parking take overwrites the stale slot, so
only a take that *parks* re-delivers it, and no earlier test parked one after a
successful take. That is a double delivery, the worst thing a queue can do, and it
now fails by name.

`jolt -e` runs the same sequence on the native host (`[:a :b ::closed]`). Full
gates green: `bb test` 4,203 / 25,191 at the landing; 4,206 / 25,228 after the
review's fixes and guards.

**Review found the queue was not extension-safe, and three older libs were not
either.** `kmet.libs.concurrent` is whitelisted into the extension SCI context —
"every lib is self-contained, so the whole prefix is whitelisted" — and the queue
named `clojure.lang.PersistentQueue/EMPTY`. SCI resolves static **methods** but has
no static-**field** access, so that symbol is `Unable to resolve symbol` in *every*
extension context, with or without `:classes {:allow :all}`, and `add-class!` does
not help. The host ran fine; the copy inside the sandbox threw. The queue holds its
items in a vector now (`conj`/`first`/`subvec`), which needs no host class at all.

Scanning the tree for the same shape found three pre-existing instances:
`TimeUnit/MILLISECONDS` in `kmet.libs.sse`, `Normalizer$Form/NFKC` in
`kmet.libs.edit-diff`, `ZoneOffset/UTC` in `kmet.libs.aws-sigv4`. All three are
static methods now (`(TimeUnit/valueOf "MILLISECONDS")`,
`(Normalizer$Form/valueOf "NFKC")`, `(ZoneOffset/ofTotalSeconds 0)`) — verified to
evaluate in SCI once the class is registered, which is what the extension loader's
retry does.

The three replacements first resolved the unit/form/offset per call, which is a
per-iteration allocation in `sse`'s 100 ms poll slice and a per-diff one in
`edit-diff` — the constant each had before. They are `def`s now (`time-unit-millis`,
`nfkc-form`, `utc`), resolved once at load, so the fix costs nothing and the comment
beside each says why the field form is unavailable rather than that the method form
is preferred.

Two guards, because a source rule and an executable rule catch different things:
`libs-name-no-static-fields` (read with rewrite-clj, so a mention in a docstring,
string or comment is prose and does not count — that distinction is the reason it is
a reader and not a regex) and `the-queue-runs-inside-a-sandbox`, which hands the
namespace's public vars to a bare `sci/init` and runs put/take/close inside it. Both
mutation-checked: a static field planted in `sse.clj`, and the queue put back on
`PersistentQueue`, each fail by name.

**Landing 6 is complete.** Its row named five deliverables and its gate has
three legs: the queue (6a), `mode-server` with `:agent-id` on every record (6b),
and the request/response pair with an approval answered by a client (6c) — two
agents concurrently, one stalling client, one approval answered, all pinned by
`kmet.app.modes.test-server`.

### Landing 6b — a connection is an agent

`mode-server` (`kmet.app.modes.server`) is rpc over a socket with one agent per
connection: `--profile server --port n` opens the socket, announces a
`:listening` record (so neither a test nor a client guesses a free port), and
then gives every accepted connection its own agent. The command language is rpc
mode's — the same records, the same answers, the same named refusals — which is
why the engine became `kmet.app.modes.rpc/handle-line`: a fn of the line and a
state (`{:agents :sessions :scope :handle}`) rather than of a stream, so stdio
and a socket serve one protocol instead of two copies of it.

Three decisions:

- **One waiter, many readers.** A connection's reader thread parks in
  `readLine` and hands the line to the server's `kmet.libs.concurrent` queue; one
  server thread takes from that queue and is therefore the only thread that
  creates, serves or disposes an agent. That is the queue's single-consumer
  contract being used as intended, and it is what a stalling client costs: one
  parked thread, and nothing else (the test writes half a record on one socket
  and the other client is served meanwhile).
- **An event belongs to the agent that emitted it.** The listener registers on
  the agent's own ctx (`events/on-every (:ctx handle) …`), so the kernel's
  subtree scoping routes a run's events to its connection and no other. That
  closes the residual "per-agent event scoping" for the wire: with the listener
  on the app scope instead, a second agent's events reach the first client —
  which is the mutation the stalled-run test catches.
- **A server is supervised.** It serves until its stdin closes — how a parent
  process stops one — then ends its queue, releases the connections it still
  holds and exits 0. `--port 0` (the default) asks the OS for the port the
  `:listening` record carries; a value that is not a number is a usage error
  before anything boots.

Every record on a connection is badged with its `:agent-id`, and a `:ready`
record names the agent before the client asks anything — six tests
(`kmet.app.modes.test-server`) pin two agents with sessions of their own, the
half-record stall, the stalled run that leaves the other agent's wire empty, the
closed connection that leaves the server serving, the diagnostic mount that opens
no socket, and the port usage error. Two mutations checked: dropping the badge,
and registering the listener on the app scope, each fail by name. What the row
still lacks is the dialog surface — a run's extension UI calls and approvals
answer headlessly until the approvals increment gives them a `:request`.

**Review found the server could be killed by one client.** `send!` wrote
straight to the connection's writer, and a client that vanished mid-write makes
that write throw. On the server thread the throw ended `serve-connections!` —
the server went on accepting and served nobody, silently, because `spawn` logs
and swallows — and on a run's thread the same throw escaped into the kernel's
emit, failing a turn for a reason that was not the run's. A connection now
carries a `:dead?` flag: the write is dropped instead of raised, the reader's
`:close` still releases the agent, `serve-connections!` reports a failing item
and keeps looping, and the accept loop closes a socket whose announcement the
closed queue refused rather than leaving it for a reader nothing will serve.

That mechanism is **not pinned by a test**, and the ledger should say so: a
socket client cannot force a mid-write failure deterministically, and the
behaviour the suite can check — the server keeps serving after a client leaves
— holds with or without the catch. The test that did come out of the review is
the opposite kind: a *refused* command (`:prompt` with no message) travels the
socket with the same name, the same error text and the same badge, which is what
"one engine, two transports" means.

**A named residual, closed at 10b.** The server thread calls
`create!`/`dispose!` off the root's thread, and `new-app.md` says the crossing
for a thread that is not the owner is `kmet.app.runtime/submit!` + `boundary!`.
Nothing drained the queue at 6b, so submissions had no drain point to ride and
a server mutation and a run's boundary were sequenced by the queue's single
consumer and by nothing else. 10b gave the queue its drain — the loop's
`:agent-settled` boundary, one at a time per root — so a tool thread's change
now lands at the next turn boundary in every mode, and the server thread's
serialization is a property of the *protocol* (a `:ready` names an agent that
must exist) rather than the only thing ordering the process.

### Landing 6c — an approval answered by a client

6c is the row's last deliverable, and it needed three things the composition
could not express before:

- **A policy a profile can name.** `:permissions`' `:policy` may be a fn or a
  keyword, because a composition is data: `profiles.edn` and `--patch FILE` are
  EDN, and a fn cannot cross that. `:allow-all` (the default) and `:ask-all` are
  the two names, and an unknown name fails at the mount, naming the ones there
  are, rather than at the first tool call.
- **An approvals row the server owns.** The bundle's default denies because
  nobody can be asked; on a server somebody can. `--profile server` patches the
  row's plugin to `:mode-server-approvals` — by data, like any other row, so no
  part of the composition is coded around the mode.
- **A route from a run's ctx to the connection that may answer for it.** The
  approvals row is mounted at the app scope, so the seam is process-wide by
  construction; the mode installs a router for its run and restores what was
  there on teardown, the same install/restore pair `kmet.app.front-end` uses for
  the extension surface. The router matches with `srce/inside?`, so a
  **subagent's** ask reaches its parent agent's client — the case the first draft
  got wrong by comparing ctxs with `identical?`, which would have sent every
  subagent's question to the headless deny.

An `:ask` becomes `{:type :request :id "req-…" :method :ask :tool-name … :args …}`
on its connection, and the client's
`{:type :response :id … :data {:decision …}}` resolves it. A `:response` from a
client is never a command (the server answers *those*), so the two directions
cannot be confused. The wait runs on the run's own thread — the one thread that
may wait for a human — with **no deadline**, because an unanswered question
blocking the call it is about is what a question means. It is not unbounded: the
run's own cancel atom ends it with a deny, and so does the connection closing, so
a client that walks away does not leave a run parked.

Simplified while reviewing: the line is parsed **once** (the server classifies
it — a `:response` is a dialog answer, anything else is a command — and
`rpc/handle-record` serves the parsed record), and the three places that settle
a dialog go through one `settle-dialog!`, which is also what makes "an id nobody
is waiting for" a single, logged case instead of three.

**Evidence**: `an-approval-is-answered-by-the-client` — a patch layer mounting
`:ask-all`, a stub whose first call asks for a `bash` tool call, a client that
answers `:allow`, and the assertion that matters: the `:tool-execution-end`
carries the tool's own output, not a block reason. Two mutations checked:
removing the profile's approvals swap (the bundle denies, no `:request` arrives)
and delivering a deny regardless of the client's answer; each fails by name.

### Landing 7b-ii — presets are bundles

Profiles as row+patch layers shipped with landing 1 and the composition has
carried every landing since (6c's approvals row is a *patch*, 7b-ii's gate is a
*bundle*). What was missing is the second mount point: `new-app.md`'s "an agent
preset is a row set on the child ctx" had no code, and `:agents/create!` had no
`setup` between the child ctx and the factory.

Three pieces, each the smallest thing that could carry the next:

- **`runtime/mount-rows!`** — the mount loop, public, over any ctx. `boot` is it
  on a fresh root; a preset is it on an agent's child ctx. The mount point, not
  the row shape, is the whole difference, which is why nothing about the row
  language changed. `inactive-rows` came with it: the "every row must be active"
  check is one function both callers use.
- **`kmet.app.presets`** — `mount!` composes a *bundle* through
  `kmet.app.profile` (so a preset is named like a bundle, not declared beside
  it) and mounts it under one ctx; `setup` is the same thing shaped for
  `create!`'s option. A row that composes but never becomes active is
  `:kmet.app/preset-failed` naming it, thrown before any agent exists.
- **`create!`'s `:setup`** — a fn of `(ctx id)`, called between the child ctx and
  the factory (dsh's `setup(agentCtx, agent)`). Its throw path is the factory's:
  the child ctx is released, no handle is published, and the caller sees
  `:kmet.app/agent-create-failed`.

Why the per-agent mount is the right home for a policy, in the kernel's words:
`permissions` documents that a child may not shadow a service its ancestor
provides, so a per-agent policy is not a *patch of the root's row* — it is the
only provider on that chain, mounted by whoever composes the agent. That is what
`:bundle/gated` is: one row, `{:policy :ask-all}`.

**Evidence** — `kmet.app.test-presets` holds one booted root with two agents,
one created with the preset and one without: the plain agent's call proceeds
(nil from `permissions/check`) and the gated agent's is the bundle default's
deny, while the root itself has no `:permissions` at all. The second test mounts
a bundle whose row waits on a name nothing provides and asserts both halves of
the rollback: `:kmet.app/preset-failed` and an empty `:agents/list`. Mutations
checked: dropping the `setup` call fails both preset tests; disabling the
`inactive-rows` check fails the rollback test; registering a tool under the
wrong tag fails the turn-boundary test.

The third gate leg — **a tool swaps mid-session at the turn boundary** — needed
no code: the request path already reads `loop-state/active-tools` per request,
so a tool registered between two turns is in the next turn's declaration. It is
now pinned (`a-tool-swaps-at-the-turn-boundary`: registered → offered, then
unregistered → not offered) rather than assumed, and the mutation that proves it
is the registry tag, not the loop.

What 7b-ii does **not** do: no `:per-agent true` plugin flag, no `isolate` realm
wiring, no second backend for `:sessions`. Those are the multi-session server's
work, and a preset is the seam they will use.

**Review — two leaks, both in the failure path.** The `:setup` call as first
written sat *outside* the guarded region (`setup` then `(try factory …)`), so a
setup that threw escaped `create!` with the child ctx still extended and its
mounted fibers still live: a tree line nobody owns, and the ledger's own claim
("its throw path is the factory's") was false. One guarded region now covers all
three failure modes — a throwing setup, a throwing factory, a handle that fails
the contract — because all three owe the same release.

The second: `presets/mount!` threw on a row that never became active *after*
mounting the rows before it. Under `create!` that was invisible (the rollback
releases the whole child ctx), but a caller mounting onto a ctx it means to keep
would get half a preset — and half a policy row set is precisely the state a gate
must not be left in. `mount!` now disposes what it mounted, in reverse, before
throwing.

Both were pinned only after the assertions were corrected: `srce/detach!`
**keeps the `:parent` chain** (documented — what changes is the tree), so a
released ctx is `inside?` its old root forever and only `srce/children` shows the
release. The first version of the test asserted `inside?` and passed the leak;
the mutation (setup moved back outside the guard) now fails by name, as does
deleting the unwinding loop.

**Review #2 — the option and the types.** Three more, all small: `:setup` is the
registry's option and was being forwarded to the factory (a factory that stored
its opts could re-run a preset), so `create!` now hands it `(dissoc opts :setup)`
— pinned by `a-setup-runs-before-the-factory-and-stays-out-of-its-opts`, which
also pins the ordering and that setup and factory see the same child ctx and the
minted id. A `:setup` that is not a fn — an EDN composition can carry a keyword
here — is `:kmet.app/bad-setup` before anything is minted or extended, instead of
"not a function" wrapped as a creation failure. And a `:kmet.app/preset-failed`
now keeps its own type through the rollback, like `:kmet.app/bad-agent-handle`:
both are composition bugs the caller may act on, which a generic "creation
failed" hides. The preset tests also stopped creating agents with a default
config — a test creates its session under `target/`, never in the agent dir a
developer's kmet reads.

**Simplify — two rules get names, and the resume carries them.** The two
predicates the review had spelled out inline are now named: `composition-bug?`
(which types survive the rollback, and why) and `setup-fn` (the option read and
checked in one place, before anything is minted). The built-in table is read
**once** per process in `kmet.app.presets` — a preset mount is per agent and a
server creates an agent per connection, so the delay turned a jar-entry parse per
connection into one per process (the preset tests went from 933 ms to 25 ms,
which is what a resource read per mount looks like). And the review found a gap
beside the reviews: `resume!` took no options, so a resumed agent could not be
given the preset it was created with — it now takes `create!`'s opts, and
`a-resumed-agent-keeps-its-composition` pins that the setup runs on that path
too.

### Landing 5j — the TUI is a row

`kmet --app new` starts a session now. The `:tui` profile has a mode row the way
print, rpc and the server do, and its plugin (`kmet.agent.modes.tui`, in the
`agent` module) mounts **kmet1's** interactive mode over the composition the
profile booted. Nothing moved and nothing was rewritten: the mode is the same one
`--app old` starts, which is what makes `bb start --app new` a real TUI rather
than a second implementation to drift.

Two seams were needed, and both are the mode's own requirements rather than
bridges:

- **The engine takes its ctx.** `session.driver/bootstrap` already requires
  `:ctx` — "the caller names the ctx whose `:tools` service is the table this
  engine lists … and whose `:loop-stages` the run reads" — but `layout/build-layout`
  passed `(app-runtime/ctx)`, the *process* root. Under `--app new` that root is
  not the composition's, so the run would have had no tool table and failed at
  the prompt. `build-layout` and `interactive/run` gained the ctx as their last
  argument (defaulting to the process root), and the mode row hands them its
  scope. A TUI run now lists and dispatches through the composition's `:tools`,
  reads its `:systemPrompt` row, and logs to its `:sessions`.
- **The startup is one list.** `kmet.agent.cli/-main`'s prelude — provider
  catalogs, the models.edn layer, the config, the extension packages, the theme
  paths, the extension CLI flags — became `cli/load-world!`, which `-main` and the
  TUI row both call. A process that boots a composition still needs the world a
  process has, and a second copy of that list is a drift waiting to happen.

Evidence: `--dump-config` shows `profile :tui rows=14` ending in
`:mode-tui … kmet.agent.modes.tui/plugin … :mode true`; a pty capture of
`bb start --app new` paints the header (`kmet (babashka)`), the context/skills/
extensions startup block, the footer with the model and cwd, and the `/`
autocomplete — and ctrl+c twice exits the process in about a second, the same as
`--app old`. On Jolt the same capture paints `kmet (jolt)` and exits the same
way. `--dump-state` mounts `[:app :mode-tui] :active` and starts **nothing**: a
diagnostic mount hands the row no `:on-result`, which is the same rule print
mode follows.

Mutations: removing `:mode true` from the row fails `a-bare-run-selects-the-tui`;
removing the mode row (a `:remove` patch) is what `a-bare-run-boots-the-
composition-and-says-what-is-missing` asserts over — the profile still boots, and
the message says no row is marked `:mode`; deleting the diagnostic guard fails
`a-diagnostic-mount-starts-nothing`; and the ctx threading is pinned by the
arities (a stub that hid it failed loudly as an arity error in
`test_interactive`, which is the with-redefs cost of a signature change).

**What 5j does not do.** The mode drives kmet1's engine rather than an `:agents`
handle: the registry stays empty for a TUI run and a preset mounted per agent
(7b-ii) is not on its path. The tree still lives in `agent` under
`kmet.agent.modes.*` — landing 5a's move to `kmet.ui.tui` is still owed, and with
it the `ui-terminal` provider over the `:ui` capability service (whose contract
and headless provider landed at 5k) that a mode injects and binds. Mounting the
mode as a row first makes that re-host a refactor of one namespace rather than a
change to how kmet2 boots.

**Review — one process, one tree.** The row as first shipped booted a *second*
composition, and the review found it: `kmet.agent.app-runtime/ctx` lazily boots
`{:profile :tui}` (that is how kmet1 gets its root) and `load-world!` called it, so
`--app new` mounted 14 rows and then 14 more behind them. Worse than the waste:
the TUI's engine ran on the first tree while every kmet1 shim — `/units`, the
façades, the extension host's no-node reads — read the second, so an extension
that registered a tool into `[:app …]` and a run that dispatched through `:tools`
could have been looking at different registries. `app-runtime/publish-root!` makes
the entry's own composition the process root, and `load-world!` publishes **before**
the first `ctx` read, so nothing boots behind it. Measured with a script that
stubs the world-loading leaves and keeps the publish path real: with the publish
the process root *is* the entry's tree (same 14 children, `identical?`); without
it a second 14-row composition appears. The shim's root moved from a `delay` to an
atom to make publication possible; `reset-root!`/`restore-root!` are the test seam
and they hand the old root back, because clearing it lets the next test boot a
composition that lacks the registrations the first carried — which is exactly how
the first attempt broke `test-search-tools-extensions`.

Two smaller ones from the same pass: the row declared no `:inject`, so a
composition that patched out `:tools` failed at the first prompt rather than at
the mount — it now declares `[:tools :system-prompt]`; and a throwable with a nil
message printed `Error: nil`, so the catch names the class when there is no
message.

What the review did **not** change: `--dump-config` resolves a row's plugin var, so
listing the `:tui` composition loads kmet1's whole TUI tree — a side effect of the
catalog's `requiring-resolve`, and an honest one (the row's card *is* that plugin);
and the pty harness cannot send Enter in raw mode, so `/quit` stays unverified
through it — ctrl+c twice exits in about a second under both entries, which is the
same assertion both ways.

### Landing 5i — the owner publishes, and the registries stop reaching up

`resource_config` was blocked by `kmet.app.packages`, which was blocked by
`prompts` and `skills`, which were blocked by **one call each**:

```clojure
(registry/register! (app-runtime/registry) :kmet/skill …)   ; ×7 across three files
```

Every registry with a name address — commands, prompts, skills, and the tool
layer — read the *process's* handle from `kmet1`'s shim. The shim built that handle
(a router over the app ctx's services) and published it to the extension host;
the registries then required the shim to get it back. **The direction was the
bug.** `kmet.agent.registry/current` is the rank-2 answer: the owner publishes
(`publish-process-handle!`, returning the previous handle so a second owner can
restore it), and a caller with no ctx in hand reads. Nobody having published is
not an error — the fallback is one empty atom registry per process, so a
registration made before the owner arrives is still visible to a later read (until
an owner publishes and takes over; that is the boot path, not a live swap).

The shim now publishes at load, next to the place its tags→service table lives,
and `commands`, `prompts` and `skills` no longer name it.

**Four files moved down, following the seam**: `commands`, `prompts`, `skills`,
then `packages` (which is what needed the other two). Names kept, tests with them.
Then `resource_config` — the last file in `agent`'s UI tree — into `ui` as
`kmet.ui.tui.resource-config`, which empties that tree and drops it from both
guards (`test_layer_guard`'s `ui-roots` is two roots now, each asserted;
`test-caching-conventions`' component dirs likewise).

**One move was speculative and got reverted.** `tools/core` looked rank-clean once
the seam existed, so it moved — and five loop tool tests failed. Its registry is
*process*-scoped on purpose: `app-runtime`'s `:tools` service is the table the
loop resolves through, and requiring the shim at load was what booted it in those
tests. The file's own docstring says it: "the one process handle kmet1 has lives
in a rank-4 namespace, where needing it is allowed." Reverted, and the seam kept
for the four registries whose handle really is name-addressed. Second time this
landing's family has been taught that **a require is a load side effect** (5f
bound the extension host the same way).

**Live check.** `kmet config` renders the moved screen: groups (the built-in tool
list, packaged extensions), per-row toggles and the `(1/20)` counter, from
`kmet.ui.tui.resource-config`.

**The seam has one publisher, and it is kmet1's.** `--app new` loads no
`app-runtime`, so today kmet2 code that registers a command, prompt or skill reads
the fallback: consistent (writer and reader agree) but invisible to kmet2's own
services. That is the seam's next obligation, not a bug yet — landing 6's driver
publishes when it takes over the engine, and only then can a kmet2 registration be
read by kmet2. Verified that no kmet2 namespace registers through these three
today, so nothing is silently landing in the wrong handle.

**The bare run's message was one landing behind.** `--app new` with no profile
still says its profile has no mode — true, `:tui` waits on the fork — but it said
so by promising that "landing 5 adds the interactive, rpc and server modes", and
`print` and `rpc` both ran by the time landing 5 closed. It names the profile it
was asked for (`profile tui has no runnable mode yet`) and lists the profiles that
do run, so the next landing only has to delete a line rather than discover the
whole message was a guess.

**One rule, written once.** `test_layer_guard` had grown two near-identical
loops — same filter, same assertion, different prefix list — because the driver
rule binds a narrower set of files than the loop rule. It is one table now
(`{:scope :prefixes :why}`), one loop, and the failure message carries the reason
instead of restating the rule: *"tree_selector requires
[\"kmet.app.session.driver\"] — the UI layer reads the engine; the driver starts
work, and a mode owns a run"*. Mutation-checked both scopes: a `kmet.app.loop`
require in `print.clj` and a driver require in a selector each fail by name, while
`print.clj`'s real driver require passes.

**The headline claim, counted.** Every `kmet.agent.*` a `ui` file requires, after
the move: `keybindings` 13, `session.runtime` 7, `config` 6, `session.core` 4,
`debug` 3, `tools.bash-output` 2, `model-resolver` 2, `extensions.core` 2, and six
singles — all rank 2 except `tool-renderers`, which keeps its name for the
extensions that require it. The UI layer's upward edges are gone; what is left is
the ABI by design.

**What is left is landing 6.** Fourteen files under `modules/agent/src` still
require some other `agent` namespace, and they are two different kinds:

- **The TUI fork's blockers, 9**: the eight `modes/*` files plus
  `commands/builtins`. Every one of them is blocked by
  `kmet.app.session.driver` — a mode drives it, which is legal and is exactly
  why they cannot move down yet — and by `app-runtime` and `commands/builtins`
  besides. Putting the driver below the modes is what landing 6 does when it
  becomes kmet2's `:agentLoop`.
- **kmet1's own process and core, 5**: `app_runtime`, `cli`, `jvm_main`,
  `tools/core`, and `extensions/services`' edge to `app_runtime`. They do not
  move; they are what retirement deletes.

So landing 5's own work is done: every UI namespace is in `ui`, and what is left
is the driver, which is landing 6's first deliverable.

**Evidence.** `bb test` 4,195 / 24,765; `bb test-ext` the same pre-existing
15 failures / 1 error (copilot end-to-end, jar/lib round-trips); full lint 0/0/0;
`bb check` compiles; format clean; `--app old` boots and `kmet config` renders.

### Landing 5h — seven selectors and no call-site edits: the cut pays off

The seven files landing 5g unblocked — `fork-selector`, `model-catalog`,
`model-selector`, `scoped-models-selector`, `settings-selector`,
`thinking-selector`, `tree-selector` and their six test namespaces — moved into
`ui` as `kmet.ui.tui.*`. No ABI names among them, so the names went with the
files.

**They needed no edits of their own.** That is the test of 5g's cut: their
requires were already `kmet.app.session.core`, `kmet.app.keybindings`,
`kmet.app.model-resolver` — all rank 2 — plus each other. The only call sites
that changed are the modes that assemble them (`kmet.agent.modes.interactive`
`builtins`/`layout`/`session-admin`), which is the legal upward direction: a mode
at rank 4 may require the UI at rank 3.

**A rename must key on the file that moved, not on the name that appears in a
file.** The blanket rewrite also renamed the ns form of
`test_resource_config.clj` — a file that *stayed* in `agent` — because its body
mentions `kmet.agent.ui.tui`. The suite then died before running anything:
discovery derives the namespace from the path, found
`kmet.agent.ui.tui.test-resource-config`, and `require`d a namespace whose own
`ns` form now claimed `kmet.ui.tui.test-resource-config`. Rename by the move set;
grep the rest.

`resource_config` is now the only file left in `agent`'s UI tree, still blocked
by `kmet.app.packages`. With it, **10 files remain** before the TUI is entirely
`ui`: that one, and the 9 `modes/interactive` files whose external edges are
`commands`, `prompts`, `skills`, `packages`, `app-runtime`, `tools.core`.

**The guard's third root was not asserted.** `front-ends-drive-the-runtime-not-the-loop`
checked the two `ui`-module roots by name and the `agent` one with a loose
`"/ui/"` substring — which the other roots satisfy. With `resource_config` now the
only file left in that tree, the next move would empty it and the guard would go on
scanning nothing there. It asserts each root in `ui-roots` now, and
`source-files-under` returns nothing for a missing directory instead of throwing:
a renamed root fails as `<root> not scanned` (mutation-checked — previously the
same mutation produced two uncaught `NoSuchFileException`s).

**The selectors were exercised live, not just in unit tests.** A module move is
invisible to unit tests: they construct their own keybindings and themes. Captured
from a real TUI on `--app old`: `/model` renders the dialog with its scope line,
provider tags, `✓` on the current model and the detail panel (`kmet.ui.tui.model-selector`
+ `model-catalog`), and `/settings` renders its 26 rows with search and detail
(`kmet.ui.tui.settings-selector` → `model-selector`). The status line's `• max`
is `thinking-selector`'s.

**Two allow-lists were empty and still had their plumbing.** `test_layer_guard`
carried `ui-temporary-leaks` and `temporary-leaks` — both `{}`, each wrapped in a
`remove`/`contains?` that is an identity function over an empty map. A reader has
to prove an empty table is inert before trusting the rule beside it; the rule now
says what it means, and the next violation fails with its name
(mutation-checked: a `kmet.ui.tui.` require in `kmet.app.config` reports
`["kmet.ui.tui.chat-history"]`, and a `kmet.agent.modes.` require in a selector
reports by file).

**`ui/deps.edn` named the wrong namespace as the thing that stays behind.** Its
comment listed "`kmet.app.session.runtime`" among what keeps a component in
`agent` — but since 5g that namespace is the engine value at rank 2, which a UI
file is *allowed* to read; the thing that stays behind is the driver. The comment
names the driver and the six other rank-4 edges, so the next component move reads
the true list.

**A guard clause was tautological.** `bootstrap-call?` in
`test_front_end_policy` compared `(namespace (symbol (name s)))` to nil on a
symbol built from its own name — always true, so it filtered nothing while
looking like it did. It matches the call name now, and renaming `bootstrap` in
`print.clj` still fails the policy (checked).

**The docs moved later than the code.** Two files named the selectors by their
old namespace after the move: `tui.md` pointed at
`kmet.agent.ui.tui.tree-selector` (and still described "the half that reaches
kmet1's runtime" as a half rather than one file), and the agent README's module
tree said the same. A namespace rename has three audiences — call sites, guards,
and prose — and the third is the one that never fails.

**Evidence.** `bb test` 4,195 / 24,751; full lint 0 errors / 0 warnings;
`bb check` compiles; format clean; `--app old --version` and `--app new --profile
rpc` both answer. One pre-existing defect found while re-running suites
individually: `kmet.agent.extensions.test-libs` fails 2 of 23 assertions when run
*alone* and passes in the suite — verified unchanged in a worktree at HEAD, so it
is older than this landing and is recorded here rather than fixed here.

### Landing 5g — the session runtime is two things, and only one of them drives

Fourteen of the 17 files landing 5f left behind read their state through
`kmet.app.session.runtime` — `session`, `cwd`, `model`, `thinking-level`,
`run-options`, `turn-running?`. That namespace was 96 defns and 1,335 lines
holding two different things:

```clojure
(defn agent-state    [engine] (:agent-state engine))            ; the value
(defn thinking-level [engine] @(:thinking (agent-state engine))) ; and its readers
(defn prompt!        [engine opts] …)                            ; the driver
(defn bootstrap      [opts] …)                                   ; that makes one
```

**The cut was measured, not chosen by taste.** A symbol-node scan of every form
asked which side each name is referenced from: **no form on the value side
referenced any name on the driver side**, so the split is acyclic by
construction. Only five forms needed a rank-4 namespace (`commands`, `skills` —
`bootstrap`, `refresh-prompt-cwd!`, and the three extension-command expanders);
their transitive callers pull the rest of the driving half along.

| half | where | size | what |
|---|---|---|---|
| `kmet.app.session.runtime` | `app` (rank 2), **name kept** | 50 defs, 354 lines | the `Engine` record, and every read/write a front end makes on it |
| `kmet.app.session.driver` | `agent` (rank 4), new | 47 defs, 1,009 lines | `bootstrap`, prompts and turns, steering, session switching, compaction dispatch, disposal |

**What the call sites say about the cut.** 34 files named the namespace. The UI
selectors needed **no edit at all** — they only read, so the alias they already
had now resolves one rank lower. Sixteen files swapped the driver calls
(`session-driver/prompt!`), which are exactly the files that start work.

The driver calls the value's accessors from 14 names, so it `:refer`s them rather
than growing 200 prefixes — and pays the one hazard of that choice: `entries`,
`model` and `provider` are shadowed by locals in three of its own functions, so
lint saw those refers as unused. Found by lint, not by tests, which is the right
way round.

**The boundary had to be guarded, not just written.** `test_layer_guard` forbids
`kmet.app.loop` and `kmet.app.compaction.stage` in every front end, and after
the split the namespace a *widget* must not reach is the driver: `ui-forbidden-prefixes`
holds `kmet.app.session.driver` for the three UI roots, while modes keep
reaching it — a mode owns a run and may drive it, a widget reads the engine. The
new rule is mutation-checked: requiring the driver from `kmet.ui.tui.thinking-selector`
fails the guard by name. Without it the seven selectors' new freedom is
unpinned — the exact edge landing 5h is about to move.

**A guard was watching the alias.** `test_front_end_policy` matched the *symbol*
`runtime/bootstrap` in three front ends, so the rename made it report "the
bootstrap options are not a map" — the guard was right that it found nothing and
wrong about what it was looking for. It matches the call by name now: which
options a front end names is the policy; which alias it uses is not.

**Evidence.** `--app old --version` boots; `--app new --profile rpc` answers
`:get-state` with the model and thinking level the engine now serves; `bb test`
4,195 / 24,692; full lint 0 errors / 0 warnings; `bb check` compiles; format
clean. The seven selectors now reach only `kmet.app.session.core`,
`kmet.app.keybindings` and `kmet.app.model-resolver` — all rank ≤ 2 — so
landing 5h can move them; `resource_config` still waits on `kmet.app.packages`.

### Landing 5f — the transcript moves, and the UI takes its lookups from the caller

Of the 20 files landing 5e left behind, **12 required the chat history**. It was
the hub, and it had exactly two upward edges — both of them one call deep:

```clojure
[kmet.app.skills     :as skills]   ; (skills/parse-skill-block text)      ×2
[kmet.agent.tools.core :as tools]    ; (tools/get-tool name)                ×1
```

**The parser was in the wrong place, not merely above.** `parse-skill-block` is
nineteen lines, needs `clojure.string` and nothing else: it reads the `<skill>`
wrapper that `/skill:name args` expands into and the session stores. That is the
*format*, and every layer that shows or replays a skill invocation needs it, so
it moved down to `kmet.app.skill-block` (rank 2) — one implementation, no
delegating alias. Its inverse, `expand-skill-command`, stays in
`kmet.app.skills`: it needs the skill registry and re-reads the SKILL.md, so it
is the app's, not the format's. The round-trip test moved to `kmet.agent.test-skills`
so the two directions keep being checked against each other.

**The registry lookup was the UI reaching out of its lane.** A tool message
renders five things off its definition map — `:render-call`, `:render-result`,
`:render-shell`, `:status`, `:title` — and the definitions live in a registry the
UI may not require. So the history takes the lookup as an option:

```clojure
(chat-history/make-chat-history :tool-fn tools/get-tool)
```

with `tool-fn` the same shape as the `cwd-fn` seam beside it. The default is
"none", and it is documented as such rather than guessed at: a caller that omits
it gets the builtin renderers, which is why **the tests that assert a tool
definition's own rendering now wire the registry themselves**: four of them, in
three namespaces (the transcript's own suite, the interactive handler's, and
compaction's) failed the first time the seam landed with its default. That is how
a seam proves it is real rather than decorative — and the same review pass wired
the two test CoreStates (the interactive-support engine and compaction's) the way
the layout is wired, so the stand-ins do not quietly render less than production.

Production has exactly one construction site, so
`layout/make-app-chat-history` was extracted to hold it: the wiring — session
cwd, thinking hidden, display mode, output pad, tool registry — is now asserted
without booting a terminal (mutating `:tool-fn` to `(constantly nil)` fails that
test by name).

**Two riders.** `external_editor` and `suspend` had `chat-history` as their only
blocked edge, so they moved with it. The transcript and its two neighbours are
`kmet.ui.tui.*` now.

**A test's side effect was load-bearing, and the move exposed it.** Three UI test
namespaces (`test-skill-message`, `test-user-message`,
`test-assistant-message`) render user messages, and rendering asks the extension
host for the markdown-transformers hook. They passed because some *other*
namespace had loaded `kmet.agent.app-runtime`, whose top-level `bind-services!`
binds the host process-wide — alphabetical luck, not design. Removing
`kmet.app.skills` from one of them removed the binding and turned eleven
assertions into errors when the namespace ran alone. `kmet.test-utils` now has
`with-extension-host` (loads the shim, names the side effect), and the three
namespaces declare it: they fail standalone for their own reasons from now on,
not their neighbour's.

**Simplification that came with it.** `make-component-for-msg` took six
positional arguments and both call sites picked the same six fields off the
record; it takes one `message-context` map now, read off the history once.

**What is left, and the edge that dominates it.** 17 files: 8 under
`kmet.agent.ui.tui` and the 9 `modes/interactive` ones. Seven of the eight reach
`kmet.app.session.runtime` — `run-options`, `config`, `scoped-models`,
`thinking-level`, `model-record`, `session`, `turn-running?` and the matching
setters. Those are *runtime state reads and writes*, the thing landing 6 replaces
with an agent handle, which is why the rest of the move waits for it: the UI
should read a handle, not a process. `resource_config` is the exception, blocked
by `kmet.app.packages`.

**Review.** The benchmarks count as call sites, and they were off by the seam:
the three bench harnesses built their transcript without `:tool-fn`, so
`kmet_render_bench` measured a session that production never renders — adding
the lookup took the same session from 852 rendered lines to 860 in one case.
Wiring them also keeps the numbers comparable across the move.

The names that moved had left stale company behind them: the skill
message's own docstring and the pi-alignment table still said
`kmet.agent.skills/parse-skill-block`, and the skill-message test's docstring
still promised parser coverage it no longer had (the parser is tested where it
lives now). Documentation that names a var is part of the var's move.

**The fixture had to say what it actually does.** `with-extension-host` first
claimed to *bind* the host for a test. A probe says otherwise: a fresh process has
`host-binding` nil, and `(require 'kmet.test-utils)` is enough to make it non-nil
— the binding is a load side effect of `kmet.agent.app-runtime`, reached
transitively from `kmet.app.skills`. So the fixture performs the load and the
docstring says so: the dependency is declared instead of inherited, which is the
part that survives `skills` moving down later. Mutation: drop the require from
`test-user-message` and it is back to eleven errors when run alone.

The same probe removed a fixture that was cargo: `test-tool-execution` renders
tool components, not user messages, so it never consults the host and does not
declare it. Three namespaces need the declaration; the fourth was imitating them.

**Evidence.** `--app old --version` boots; rpc mode answers `:get-state` on
`--app new`; `bb test` 4,195 / 24,686; `bb lint` (full tree, both views) 0
errors, 0 warnings; `bb check` compiles; format clean. The `ui` module is 30
source namespaces now; 8 files remain in `agent`'s UI tree.

**Found by running it: kmet2's process entry never loaded the model catalog.**
`kmet --app new -p "who are you"` answered `Error: Unknown provider:
commandcode` — for a settings file that works under `--app old`. `kmet.ai.models`'
registry starts empty, and kmet1 loads it at the top of its own `-main`
(`load-catalogs!` then `load-models-config!`); kmet2's entry loaded neither, so
*every* provider was unknown and the failure looked like a bad settings file. The
tests could not have caught it: every one of them stubs the LLM, and a stub never
asks the catalog whether the provider exists. `run-process` is the fix — catalog,
then `main`, with `-main` adding the exit code so the order is testable without
forking — and the load is the entry point's rather than a row's for the same
reason it is kmet1's: the registry is process-wide state in `ai`, not a service
in a ctx. kmet1's other process load, `load-image-catalogs!`, has no kmet2 reader
yet — no image row — and moves with one.

Two review notes. The mode's listeners are scoped to the **app**, not to its
agent, because print mode holds exactly one: a second concurrent agent in the
same process would settle this mode's promise. That is exact today (`--profile
print` is the only thing in a print process that creates an agent) and it is the
line landing 6's server moves — per-agent scoping, which the kernel already
supports by registering at the agent's ctx. And the exit code is tested in both
directions: `:completed` → 0, and a non-retryable model failure → 1 with the
failure named on stderr and nothing on stdout, mutation-checked by making
`exit-code` always answer 0.

Still to come in 5: `--mode` as kmet1's compatibility alias for `--profile`,
the `edn`/`json` print variant (it prints the wire events, not the text), and
`--model`/`--provider` overrides — kmet1's flags, none of which this increment
parses.

### Landing 4c-iii-b settled

**The registry cannot own what an agent is.** `kmet.app.agents` is generic by
construction: `create!` mints an id, extends the caller's ctx (`{:name
:agent/5f3a2b1c}` — the tree line 4d's renderer prints), calls the registered
factory with the child ctx and the caller's options, and publishes whatever
handle the factory returns once it fits a five-key contract (`:id :ctx :agent
:session :dispose!`, plus an optional control surface). The child ctx is the
registry's, the handle is the driver's, and `dispose!` takes the handle because
it **is** the capability — an id, a copy, or a value a consumer assembled is
refused (`:kmet.app/unknown-agent`). The two bus events are declared on the
`:agents` plugin itself (`:agent/created`, `:agent/disposed`) — the first plugin
besides `:events` to declare its own names, which is the per-plugin declaration
the bus design claims.

**The factory is a registration, not a service.** `register-factory!` lives on
the `:agents` handle, and `:agents` mounts without one — so `create!` names the
seam it is missing (`:kmet.app/no-agent-factory`, ":agentLoop") instead of
dereferencing nil, and a profile can mount the registry before any driver exists.
One factory per registry (`:kmet.app/factory-taken`): two drivers over one
registry are two owners of one teardown. The registration returns a disposer —
a plugin `:apply`'s first effect, so unmounting the driver takes its factory
with it — and the disposer removes *its own* factory only, so a restarted
driver's stale disposer cannot clear its replacement.

**Creation's two failure modes are one rollback.** The first cut wrapped only
the factory call, so a handle that *fit wrong* — `{}` — left the child ctx
attached: `:kmet.app/bad-agent-handle` with a live ctx under the root, which the
landing's own test found. Validation moved inside the rollback, and the two
types stay distinct: "the factory threw" (`:kmet.app/agent-create-failed`, the
factory's error as the cause) and "the factory returned the wrong shape"
(`:kmet.app/bad-agent-handle`, naming the missing keys) have different owners
and different fixes. A create already in flight is refused
(`:kmet.app/create-busy`) rather than interleaved: the kernel is single-owner-
threaded, so a second one is a caller on another thread, and that caller submits.

**The inbox is the log; the cache's key is the prefix, not the leaf.** An
enqueue appends `{:role :custom :custom-type :queued-message :data {:op
:enqueue :target …}}`, a delivery appends the fact that consumes it, and the
pending set is the fold of the two. `:custom` is metadata to the context
projection, so the queue cannot reach a prompt — which is why the convention is
a custom-type rather than a new entry role: the role would have been a schema
change in shared code to express something the projection already drops. The
first cache key was (leaf id, length), which a plain append always invalidates —
an append *moves the leaf* — so every call re-folded the branch and `:extended`
was never smaller than the branch. The invariant is the **prefix**: the entry
the cache stopped at must still sit at that position. Appends extend, branch
switches rebuild, and `:extended` reports how many entries the call folded,
which is what makes the cache's behaviour testable rather than assumed.

**The second backend was not branch-shaped.** `store/get-branch` reads
`:leaf-id`; `kmet.app.sessions/memory-backend` carried `:leaf`, so folding an
in-process session answered the empty branch. The field is `:leaf-id` now and
there is still one branch walk in the tree.

**The turn outcome had one implementation too many.** `loop/turn-outcome` was
the same three-line `case` a projected boundary needs, so the loop reads
`kmet.app.projections/outcome` now: the outcome a client resumes from and the
decision the loop acts on cannot disagree.

**Sending is append-then-notify.** `send!` appends the queue fact and only then
calls the handle's `:send!` (the driver's own wake-up): the log is the record,
the atom is the notification, and a message queued while the process was down is
still in the inbox when the session resumes. `steer!` and `follow-up!` are the
two targets; a refused target appends nothing and wakes nobody, and a dead
handle neither sends nor appends.

**Review found three holes, all in a lifetime.** `dispose!` ran the driver's
teardown and then the release, so an agent with a subagent under it — a plain
child ctx, which `detach!` refuses to release *around* — ended half-disposed:
the teardown had run, the handle was still registered, and the exception left no
caller able to finish the job. A disposal now cascades: the agents registered
under the ctx go first (deepest first), so each child's release leaves its
parent's ctx free; a release that still fails is reported and the capability is
withdrawn anyway, because a handle outliving its ctx is a handle nobody can use.

The same review caught a ctx riding an event: `:agent/created` carried the
child ctx, and a ctx is cyclic, so a listener that logged its event would never
return. The payload is `{:id :agent}` now — the id is the registry's key and the
bare agent is what a consumer needs, which is the rule service values already
follow ("nothing may carry a ctx").

The third was the cache outliving its log: a deleted session's projection would
never be folded again and would never be dropped, so `:forget!` names the drop
(and only that — the cache still grows with live sessions, which is the id
index's rule too).

Two claims the docstrings made and nothing pinned are tests now: a create
started from inside a create is refused (`:kmet.app/create-busy`) rather than
interleaved — the factory that tries it *is* the test — and `resume!` hands the
factory `{:resume id}` and publishes what comes back. A handle op that is
present but not callable is a contract problem too, not a "not a function" from
inside it.

One boundary is recorded rather than fixed: a remount of `:agents` (a
`:projections` provider swap restarts it) forgets its handles while their ctxs
stay under the root — exactly as a remounted `:sessions` loses its id index. The
driver is re-hosted with them at 7a, and resume-by-id is not a way to bring a
live agent back: it creates a *new* agent for a stored session, because the
registry is not the agent's durable record — the log is.

A second pass found the one thing the first pass had taken on trust: **a parent
ctx must outlive the agent**, and a plugin's mount ctx does not. Disposing a
fiber drops its mount ctx out of the tree with whatever child ctxs it still
holds — nothing in the tree can see them afterwards — so an agent parented to a
mode's ctx would leak invisibly on the mode's first teardown, and that is the
*normal* path (`kmet.mode.*` is a plugin). `create!` refuses a mount ctx now
(`:kmet.app/agent-parent-is-a-mount`, the same identity check the listing's
`mount-fiber` makes): the parent is the registry's scope or another agent's ctx,
never a mount. That constrains landing 6 in the right direction — a mode names
the app ctx.

Three smaller ones, all "name the problem where it is made": `register-factory!`
takes a ctx and now checks it is inside the registry's tree (a factory is not
something another tree can hand a driver); `send!` on a handle with no session
throws `:kmet.app/agent-has-no-session` instead of letting the backend deref
nil; and the handle contract requires a *non-nil* `:agent`, so `get`'s nil
cannot mean two things. The review also deleted state nothing read: the
projection's `:delivered` set. A delivery can only name entries earlier on the
same branch — append order and the parent chain guarantee it — so filtering the
pending set is the whole of it, and the invariant is written down instead of
stored.

**A simplification pass moved three things to where they belong.** The mount
predicate was written twice — `kmet.app.inspect`'s private `mount-fiber` and the
registry's `mount-ctx?` — so it is the kernel's now (`srce/mount-fiber`, with
the root and plain-child cases stated once) and both callers read it. A failed
release had two policies: the creation rollback rethrew (masking the factory's
own error when the release was what failed) and the disposal logged; one
`release-subtree!` reports and swallows for both, which is the rule the disposal
already followed. And `send!`/`inbox` gave different answers to "this agent has
no session" — one named it, the other dereferenced nil — so one `session!`
accessor names it for both: a defect the pass found rather than a rename. The
projection's cache key is the branch's own last entry now instead of the
session's leaf atom, so `prefix?` cannot disagree with the key it reads.

Gate, met. Two agents created through one registry hold their own sessions and
their own inboxes (a send to one appends to one log); a throwing factory and a
malformed handle both leave no handle and no child ctx; a disposed handle leaves
no tree line, takes its agent's own mounts *and the agents created under it*
with it, and cannot be disposed or sent to again; the inbox drops exactly the
delivered ids and follows the active branch; an incrementally projected log
equals a full fold of the same branch; and `kmet --app new --dump-state` shows
both rows `:active`.

Two drive-bys the changed-file gates surfaced, both left behind by `7d739cdb`
(4c-iii-c part 2): ten loop test namespaces still required
`kmet.agent.tools.core` without using it, and `test_tools` required
`tools-registry` twice — invisible while the loop was not in a changed closure,
red once it was. The three TUI selector tests' `(:refer-clojure :exclude
[selector])` — 4d's fix for Clojure 1.12's `core/selector` — is itself a lint
finding, because the linter does not know the var; the helper is `pick` and the
exclusion is gone, which is also what the 1.12 clash needed.

Ledger: **649 source lines in two new namespaces** (`kmet.app.agents` 395,
`kmet.app.projections` 254) and **663 test lines** (26 + 15 tests, 140
assertions); two rows in `:bundle/minimal` (8 → 10), `memory-backend`'s
`leaf-id` rename, the loop's one-line delegate, and ten kernel lines
(`srce/mount-fiber`) replacing two copies of the same identity check. Nothing
was lifted except the three-line outcome rule; everything else is new
bookkeeping. Every review fix has a test that fails without it: the cascade
(commented out), `:forget!` (turned into a no-op), the mount-ctx refusal, the
factory's scope check, and the session check (each dropped in turn).

### Landing 4d settled

**The renderer is a flag; the tree needed a lifetime.** `kmet.app.inspect` reads
`srce/children` and the kernel's fiber projection and adds no kernel API —
but the first run of it in kmet1's process printed six `:agent/*` lines that no
run owned. `srce/extend` attaches a child to its parent's `:children` and
nothing ever removed it: a fiber's disposal takes its own mount ctx with it, and
a plain child ctx had no such axis. So the kernel grew exactly one fn,
`srce/detach!` (refuses with `:srce/ctx-busy` while anything is still mounted
under the ctx, keeps the `:parent` chain so a value still holding it resolves as
before, and leaves only the tree). That is what 4c-iii-b needs anyway — an agent
is "a child context plus a handle", and releasing the child context is part of
disposal — and the leak was invisible until something rendered the tree.

**Two defects its own tests caught, both in the path column.** `(str [:app])`
prints `[:app]` on the JVM and `(:app)` in babashka (`toString` vs `pr`), so the
path is `pr-str`; and the accumulator was a `cons`, which yields a *seq*, so
`pr-str` would have printed `(:app)` on every host. The paragraph above about
hosts disagreeing was earned.

**The fiber atom, not the record, is the argument.** `srce/effects` derefs, so a
`fiber-details` that took the record threw a `ClassCastException` on `@fiber` —
which is how the listing first rendered every fiber with no `provides`,
`inject`, `waiting-for` or `error`. It takes the atom and derefs once for the
keys.

**The root's name belongs to boot.** The path's first segment cannot come from
the renderer: `boot` names the root `:app`, a mount names its ctx from the
plugin, and a ctx the app built without a name (a run's own `child-ctx`) renders
as `:ctx/N` by sibling position. `CTX-META` still wins, so a second tree can
name itself.

**`--dump-state` is the one flag that mounts.** `--dump-config` answers "what is
composed" without booting; `--dump-state` boots the same rows, prints the tree
and disposes them, exits 0, and writes nothing to stderr. It uses
`compose-config`, so `--profile` and `--patch` layer it exactly as they layer
the boot — the flag is a different renderer over the same composition, not a
second boot path.

**The flag could not answer its own question, so the boot grew a diagnostic
option.** `boot` throws when a row is not `:active` — right for a boot that is
meant to run, wrong for a dump of a boot that is not: the tree is exactly where
a `:pending` row's `waiting-for` and a `:failed` row's error are, so a dump that
fails on them hides the answer. `{:diagnose? true}` returns the same map with
`:failed` filled in instead of throwing; the mounting is identical and only the
failure *response* differs, so there is still one boot path. `--dump-config` and
`--dump-state` are also mutually exclusive now (`parse-args` refuses both): two
views of one composition, and picking one silently is the kind of flag that
changes nothing.

**What `/units` loses is named.** The registry `keys=[…]` lines are gone with
the store walk: the tree answers "what is mounted, in what state, waiting for
what", not "which commands are registered". That is the documented trade (one
renderer, both halves deleted) and the keys are still one `(srce/get ctx
:commands)` away for anyone who asks.

**The command test mounts a probe, and the loop's policy tests clean up.**
`/units`' pending case is asserted by mounting a fiber with an unsatisfiable
`inject` on the app root and disposing it — the state is the point, so the test
has to create it. `test_tool_policy`'s agent ctxs had been accumulating under
the process-wide app root since 4c-iii-a; they detach in a fixture now, and a
leak fails the fixture rather than the next namespace's listing.

**A drive-by the changed-file lint surfaced:** three kmet1 TUI test namespaces
define a helper named `selector`, which Clojure 1.12 added to `core` — they
exclude it explicitly now, so `bb lint-changed` is clean. (They go with kmet1 at
retirement either way.)

Ledger: **314 source + test lines in the two new files (164 + 150)**, and the
two listing halves deleted from `app_runtime` (−30 net) with their three tests.
Nothing was lifted; everything else is edits to files that already existed.
Gate: `/units` and `--dump-state` render one tree from two entry points; a
`:pending` fiber shows `waiting-for`, a `:failed` one its error, a disposed fiber
or detached ctx leaves no line, and no line is indented. Tests:
`kmet.app.test-inspect` (10/33), `kmet.srce.test-kernel`'s detach contract,
`kmet.app.test-runtime`'s diagnostic boot, two rewritten `/units` tests,
`--dump-state` in `kmet.app.test-core` (a mounted tree, a patch layer, and a
composition whose rows do not mount).

### Landing 4e settled

**The last live src edge was a comment.** `cli.clj` forced `(runtime/node)` with
the note that extension loading registers into the root's owners — true before
4b-ii-a, false after it, so the only thing keeping the store alive in src was a
stale rationale. It forces `(runtime/ctx)` now: the root the registries actually
resolve, at the point where the ordering matters (before any `init(api)`). With
that, nothing outside `registry_store.clj` reached the store; `app_runtime/node`
had zero src callers and the four test readers are on the registry handle.

**The reporter binding had no gap to fill.** §9 above expected cleanup-error
reporting to degrade until the `:logger` service landed, because the store's
`*error-reporter*` looked like a facility. It was a wrapper: the app bound it to
`kmet.agent.debug/log-error`, and its one non-store use
(`dispose-deregisters!`) caught straight through it. The catch calls
`debug/log-error` directly now — same destination, one indirection fewer, and
no dependency on the deferred logger.

**One test was about the shape of a thing that no longer has a shape.**
`test_commands`' "other registries share the store" asserted that a
`:tools/hooks` key sitting in the same store could not disturb the command
listing — the hazard existed because a store key is `[TAG name]` in one
namespace. With a service per tag, a command cannot be in the tool registry at
all. The case is "a registration lands in one tag only" now: the reading it
pinned survives, its machinery does not.

**`test_registry` lost its harness, not its assertions.** The two-backend
`doseq` + `testing` existed to hold one set of rules against both handles; with
one handle left, the file tests it directly — four tests, the same sixteen
assertions, no vestigial loop over a one-element vector.

**`scope.md` went with the code, and its surviving rules went where they run.**
Around forty docstrings and comments cited its sections for conventions that
outlived the store (owner paths, LIFO disposal, read-once-per-attempt), so the
file could not simply disappear: the rules moved to the homes that implement
them — ownership and display order into `kmet.app.registry`'s docstring, the
snapshot/tool-capture/disposal-never-waits rules into this document's "Reload
and the turn boundary", disposal order and reported failures into
[`srce.md`](modules/srce/src/kmet/srce/srce.md) §4 — and each citation either
got dropped (the sentence already states the rule) or repointed at one of those
homes. What died with the module is gone rather than archived: a doc describing
an API that no longer exists is a slower kind of stale reference.

Ledger: **613 lines deleted, no source added** — `kmet.libs.scope` 288, its two
test namespaces 262, `registry_store` 63 — plus `app_runtime` −42 net, `cli.clj`
−4, and the one loader allowlist entry; the rest is the four re-pointed test
spots, two guards' tripwire text, and the docs. Gate, met: `grep` finds no
`(scope/` under `kmet.agent.*`, no
`kmet.libs.scope` require and no `kmet.agent.registry-store` require anywhere;
`bb test` green; and `kmet --app old` loads (config, model catalog) with no
store in the process. The kernel's "never require the store" guard and the
kmet2 modules' banned-name list keep the names as tripwires, so a reintroduction
fails a test rather than a code review.

### Landing 4f settled

**Five steps, each one shippable.** The lift was written as one landing but
landed in five commits, because the require graph gives a free order and a
broken step is easier to find than a broken landing: `tools/bash-output`, then
`tools/bash-executor`, then the seven bodies (`tool`, `bash`, `edit`, `write`,
`read`, `invoke`, `run-code`), then `kmet.app.config`, then the registry.
Every step kept its namespace and every step ran `kmet.test-module-deps` and
`bb test-changed` before it was committed, so the guard's answer — not a
promise in this document — decided when the next file could move.

**The only real change is that the handle became a parameter.** At the last
step `tools/registry` still reached `(kmet.agent.app-runtime/registry)` at
seven sites, and `app` may not require `app-runtime` (it is kmet1's process
shim, and the store guard already bans it there). Review then killed the first
attempt at the seam, and the way it died is the finding:

**A process-global default cannot survive the extension sandbox.** The first
cut kept the arity-less forms and resolved an *installed* cell when the handle
was left out — a 0-arg fn, so that installing it did not boot the ctx. That
worked, kmet1 was green, and it was wrong: the sandbox loads kmet1's
namespaces a second time (the ABI, and the process shim the ABI reaches), so
`app-runtime`'s top-level install ran there too, with an SCI closure, and the
shared cell ended up holding a handle from the sandbox's copy of the process.
The suite found it as a `ClassCastException: Atom cannot be cast to IFn`
twelve namespaces later, and the class-name heuristic that looked like a fix
does not exist on babashka: every fn there is SCI-compiled, the host's own
included. **Nothing that runs in a sandbox may write process state, and a cell
written at namespace load is exactly that.**

**So the default lives where the process handle already lives, at rank 4.**
`kmet.app.tools.registry` has no default arity at all: the handle is the
first argument, full stop. `kmet.agent.tools.core` — kmet1's tool API, moved
back to `agent` — keeps the arities kmet1's ~270 call sites use and supplies
`(app-runtime/registry)`; it recreates a process default per call, holds no
state, and adds no arity to anything else. The extension host does not use it:
its five ABI tool wrappers pass `(app-runtime/registry)` straight to the
registry, so the host's edge is a downward one and 4b-ii-b keeps its short list.

**The run_code record is built per registry.** Its sandbox seams (`get-all-
tools`, `get-contributed-tools`, `execute-tool`) closed over the registry's
own fns, which is what made a static `built-in-tools` map possible; with the
handle as a parameter they would have had to resolve one. So `built-in-tools`
is a function of the registry, `builtin-tool-names` is derived from the static
half plus `run_code`, and the sandbox resolves against the handle its own
agent was built with — the property 4c-iii-c needs, obtained one landing early
because the alternative was a global. (The static half is gone at HEAD: the
built-in tools became composition rows, and `builtin-tool-names` is now read
back off `:bundle/minimal`'s `:tool/…` rows — `registry/bundle-tool-names`.)

**`tools/util`'s test had been left behind.** `util` lifted with the four leaf
dependencies at 4b-ii-a; its test namespace stayed in `agent` on the same
mistake `scope.md`'s citations were — nothing failed, because test trees are
cross-cutting. It moves with its module now, beside `bash-output`'s.

**SCI is the one new library edge.** `run-code` mints sandbox contexts, so
`app` declares `org.babashka/sci` with the pin `agent` and `jolt` already
carry (the loader's design puts the backend artifact on the caller, and the
guard's coordinate test holds the three to one sha). Everything else the tool
layer needs — `babashka/process`, `babashka/fs`, malli — `app` already
declared for the session layer.

**Review also found what booting the app root does to kmet1's rpc mode.** Four
runs in five, a full suite failed `kmet.agent.modes.test-rpc`'s terminal-event
test — `:agent-settled` reached the stream, then a `:status` the mode writes on
dispose — and the trigger was not the diff: `bb test
kmet.app.loop.test-tools kmet.agent.modes.test-rpc` reproduced it, because
the loop tests registered stand-in tools through the app root and so booted
kmet2's composition *before* the rpc namespace ran. The root is not the loop
tests' subject, so they now take a fresh handle
(`kmet.app.loop.test-support/with-registry`) and the flake returned to its
historical rate. The race itself is real and stays: a booted root shifts the
timing between the stubbed run's settle and dispose's status write, and
landing 5 re-hosts this mode on the app's bus, where that ordering must be
decided rather than timed.

**The seam test went with the seam; the property it was standing in for is
pinned instead.** The three claims a process default needed (a cleared cell
raises `::no-default-registry` from a read and a write, an installed fn is the
handle writes land in, `app-runtime`'s load points it at the façade) are gone
with the cell — the arity replaced them, since the registry's callers now get a
compile-time error rather than a runtime fallback. In their place,
`kmet.agent.tools.test-tool-tables` holds the property the refactor bought and
4c-iii-c needs: a registration belongs to the handle it was registered in, the
same name dispatches to each handle's own tool, the built-in table is built per
handle, and — the assertion that makes the other three mean something — the
run_code record's sandbox *surface* is built from its own handle, so a probe
registered in one handle is callable from that handle's script and refused as
inactive by another's. Mutation-checked: pointing the record's `get-all-tools`
seam at a static table fails it.

Ledger: **twelve source files moved into `app`** (3,891 lines), four test
namespaces with them (1,968 lines), one `deps.edn` line added; the registry
grew 319 → 354 lines (signatures and docstrings, no state) and the run_code
record's seams became closures, so `built-in-tools` is now a function of the
handle — ~9µs per `get-all-tools` where the constant map merged in ~0.1µs,
paid once per turn and once per tool call, which is the price of a table that
cannot be someone else's. One file moved the other way:
`kmet.agent.tools.core` is rank 4 again, rewritten as kmet1's façade (85
lines, no state). 26 call sites passed a handle or switched to the façade:
six in kmet1's source (the extension host's five ABI tool wrappers and
`skills`) and the rest in five test namespaces. The loop, the session runtime
and the TUI needed no edit at all — they were already calling through
`tools.core`, which is what a façade is for. Gate, met: `kmet.test-module-deps` green at every step and after
the review fixes, `bb test` green (4,089 tests / 24,050 assertions),
`bb test-ext` green apart from the pre-existing copilot-catalog failure,
`bb check` compiles, `kmet --app old --version` and `--list-models` load in the
same 0.85s as before the move, `--app new --dump-state` still boots its eight
rows, and `grep` finds no `kmet.agent.tools.*` outside `app` except the façade,
no `app` src requiring `kmet.agent.app-runtime`, and no process-global registry
in the tool layer — its remaining state is the shell seam landing 6 takes.

### Landing 4c-iii-a settled

4c-iii's row is `Engine` → `:agents` with `:permissions`/`:approvals` and the
two prerequisites `:projections` and `:systemPrompt`. Measured before writing
code, it is three landings, not one:

| Piece | Size | What it needs first |
|---|---|---|
| the policy pair + the loop's gate | 215 src, 281 test, **no files moved** | nothing — the pre-tool waterfall already exists |
| `:agents` + `:projections` | the Engine is 1,379 lines and 55 operations, bootstrapped on `kmet.app.loop` | a creation factory (`:agentLoop` registers it at 7a) and the log; the inbox as *log-derived* state changes what a queued message is (durable instead of memory-only), so kmet1's behaviour would move with it — its own gate |
| `:systemPrompt` + per-agent tool tables | `select-tools`/`active-tools` read a process-global registry; each tool carries a `:prompt-snippet` | the tool-registry lift — **landed as 4f**, which took the handle as a parameter — and the prompt waterfall, because the snippets are sections |

So **4c-iii-a landed first**, and it closes the row's *policy* half: two agents
in one process decide differently because each reads its own ctx's policy. The
row's other gate — two agents holding different tool tables — is iii-c's.

**The kernel shaped the placement, and not the way the design section assumes.**
One provider per name per realm path ([`srce.md`](modules/srce/src/kmet/srce/srce.md)
§3) means a child ctx **cannot** shadow a service its ancestor provides. A
`:permissions` row in `:bundle/minimal` would therefore be *every* agent's
policy with no way to vary it — the opposite of "per-agent by construction". So
the policy is not in the bundle: a policy mounts on the agent ctx that needs it
(by a preset or `setup`), and an agent ctx under a root with no policy allows,
which is exactly kmet1 today. `:approvals` *is* in the bundle, at the root:
asking is the front end's job and one process-wide seam; a policy below asks the
seam above, and the request carries the agent's ctx, so the approver knows who
is asking. Read "per-agent permission profile" in § *Service catalog* through
this rule from now on.

**Fail-closed, in four places.** A policy that throws, a policy that answers
something that is not a verdict, an approver that throws, and an `:ask` with no
seam all deny; none of them allows. The gate runs **after** the extension hooks
(so an argument rewrite is judged, not a way around the policy) and is composed
into run-code's `*tool-hooks*`, so a script's `tools/call` is gated like a loop
batch. `:remember!` stores an approver's scoped answer in the agent's memory, so
a tool is asked once per scope rather than once per call.

What the gate does **not** cover, named so it is a decision: a `!`/`!!` bash
line typed at the prompt is a mode action, not a tool call, so it never reaches
the waterfall; an extension that calls `tools/execute-tool` itself bypasses it
(extension code is inside the trust boundary the policy guards); the TUI's real
approver dialog is landing 5's, and until then an `:ask` in kmet1's composition
denies.

Two defects the landing's own tests caught, both from Clojure's shadowing: the
`:permissions` row destructured its config key `policy` and then called the
`policy` constructor (nil — a boot failure, not a silent one), and `check`
destructured `:reason` and then called the private `reason` helper. Both were
renamed; the rows are covered by `the-policy-row-carries-a-config`.

Ledger: **215 source + 281 test lines added, three files added and five
changed, nothing lifted**
— the extension-hook waterfall gained one stage and the app gained two rows'
worth of one namespace. Gate: `:permissions`/`:approvals` provided where they
belong; two agents under one root decide differently; a denied call never
reaches the executor; the scripted path is gated; the default approver denies.
Tests: `kmet.app.test-permissions` (17/38), `kmet.app.loop.test-tool-policy`
(5/13), plus the bundle goldens (rows 7 → 8, services gain `approvals`).

### Landing 4b-ii-a settled

The behavior landed with the files still in `agent`. Three things changed, and
the last one is what makes 4b-ii-b a file move rather than a rewrite.

**`:inject` is real.** `manifest-info` validates it (a vector of keywords, or a
map of keyword → nil/`:optional`) and carries it through the dir, jar and
bundled-resource resolvers, so a malformed value fails before any extension
code evaluates. `load-extension*` mounts a declaring extension with
`srce/inject!` under the app root as `(keyword name)`; the plugin's `:apply`
builds the api and runs `init`, so a pending mount has not initialized (unload
skips `shutdown`, disposes the fiber, and drops the loader). The mount's
failure is the kernel's, not the loader's — `activate!` marks the fiber
`:failed` and does not rethrow — so the host reads the fiber back and turns an
immediate failure into a load error; a mount that fails on activation *later*
cannot be one, and `get-loaded-extensions` carries `:fiber-state`
(`:pending`/`:active`/`:failed`) and the throwable instead, while the load
result carries `:pending` for the console's note. `grep-tool` cannot
demonstrate the key — it is a single-file extension, and only a manifest can
declare dependencies — so the fixture manifest (`test/fixtures/ext-dir`) does,
and `test/inject` covers pending, active, a rejected declaration, and the
failed mount.

**The host stopped being store-shaped.** Its nine `(scope/` sites are gone:

- hooks register in the app's registry under `:kmet/hook`, the owner riding in
  the value so `hooks-of` can keep the documented order (owners in
  first-registration order, each owner's in its own) — the atom backend lists
  by registration, which would otherwise interleave two extensions' hooks;
- themes register under `:kmet/theme`; the TUI-store removal is a tracked
  deregister fn on the contributing extension, so it still runs on unload (a
  host contribution, which has no owner to unload, comes out through
  `unregister-contributed-themes!` — the wholesale reset a fixture or
  `/reload` uses);
- the UI port registers under `:kmet/ui-port` at `[:app :ui]`;
- `cache-surface!` and the three `install-*` mirrors are deleted: the session,
  context and entry sinks were always the process caches — the store copy only
  existed for `/units` and for a teardown that cleared a value by identity
  (one live front end at a time, so last-wins is the contract).

`install-front-end!` and `discover-resources!` lost their node parameter,
`session/runtime`'s two `dispose-owner!` calls are deleted (`clear-runtime!`
and `unbind-extensions!` already restore the headless defaults), and
`app_runtime/dispose-owner!` — its last callers were those five — is gone.

**The api stayed process-level, deliberately.** `create-extension-api` takes
the extension, not a ctx: what an extension registers is visible to every
engine (hooks are read from one registry), so a ctx-scoped write and a
process-wide read would disagree. The ctx reaches the host at the mount; the
per-agent api is 4b-ii-b's, where the host is a plugin and its `:apply`
receives `(ctx cfg)` for real.

The tree-era tests changed with the contract: hook and theme assertions read
the registry (owner carried in the value), the UI-port test asserts the binding
plus the `[:app :ui]` registration, and "an older tree's teardown cannot clear a
newer install" became "last-wins" — the tree guard is exactly what went.

Ledger: **14 `(scope/` sites remain in 2 files** (`registry_store` 9,
`app_runtime` 5 — the node, the `/units` walk at 4d, and the `*error-reporter*`
at 4e). `session/runtime` and the host are off the store, so 4b-ii-b's move no
longer waits on the Engine's installs, and 4e's "delete the store" has two files
to sweep instead of four.

### Landing 4b-ii: what the require graph says

4b-ii is written as one move — the host files into `app`, keeping their
namespaces. The module guard reads *where the file lives*, so the move is legal
only when every module above `app` has left the host's requires. Measured on
`extensions/core.cljc` (the host's 1,586 lines), the upward edges were:

| Upward edge | Uses | What it needs first |
|---|---|---|
| `kmet.app.debug` | 1 | nothing — **lifted** |
| `kmet.app.event-bus` | 8 | nothing (debug + `kmet.app.events` + `srce`) — **lifted** |
| `kmet.app.config-schema`, `.tools.util` | — | nothing — **lifted** (not the host's edges: they blocked `config`) |
| `kmet.agent.app-runtime` | 11 | the node and the registry façade must be reachable from a ctx, not from an `agent` namespace |
| `kmet.app.commands` | 3 | the same — its only upward edge is `app-runtime` |
| `kmet.app.prompts` | 2 | the same |
| `kmet.app.skills` | 3 | `app-runtime`, `tools.read`, `tools.registry` |
| `kmet.agent.tools.core` | 5 | `tools.registry` + `tools.invoke` |
| `kmet.app.tools.bash` | 1 | the shell seam (landing 6): `bash-executor` is a `defonce` of process state |
| `kmet.app.config` | 1 | one call: `bash-exec/set-shell-options!` — the `:shell` seam in one line |
| `kmet.agent.registry-store` | 1 | a decision: `app` must not require the store it outlives (landing 3's note), so the theme key it builds has to become a registry address |

Two consequences, and they change the order rather than the goal:

- **The move is behind the tool layer.** `tools.registry` requires every tool
  body (read, write, edit, bash, run-code) plus `app-runtime`, so lifting it to
  `app` means lifting the tool layer's shell/filesystem seams first — i.e.
  most of landing 6. Waiting for that would hold `:inject` hostage to the
  shell services for no reason.
- **So the landing splits by axis, not by size.** 4b-ii-a ships the *behavior*
  — the host takes a ctx, `extension.edn` gains `:inject` and an extension
  mounts as the plugin `{:name :inject :apply}`, `shutdown` composes into the
  disposer — with the files still in `modules/agent`. 4b-ii-b is the *move*,
  after the tool layer and `config` lift. The landing rule still holds at each
  half: kmet2 uses the new mechanism, kmet1 keeps working, one test holds both.

The guard also grew its missing direction (`module-declarations-match-requires`):
`kmet.test-module-deps` used to check only that *written* edges flow downward,
so `modules/app/deps.edn` could omit `kmet/srce` for two landings while six
app sources required it. It holds `src`'s requires ⊆ declared ⊆ `src`'s
requires — a module's `:deps` is its src edge list, not its classpath wish
list — reading both hosts' reader branches. That reported three real defects
it fixed: `tui` and `agent` were missing `kmet/reakt` (their sources require
`kmet.reakt.core`, which the reakt module owns), and `tk` declared
`kmet/libs` without a single use.

The four leaf lifts are the ones with no upward edge at all — `debug` (required
by the host, the bus, `config`, `skills`, `bash-executor`), `event-bus` (the bus
is the host's biggest non-tool dependency), `config-schema` and `tools/util`
(`config`'s two other clean edges). They keep their namespaces, so no call site
changed; only the guard's answer did.

**After 4f the tool half of this table is gone.** Three of its rows —
`tools.core` (5 uses), `tools.bash` (1) and `config` (1) — were the tool layer
reaching up through `app-runtime`, and the tool layer is now below `app`:
those requires are downward, and `registry-store` died with the store at 4e.
What remains is four registry-wired namespaces — `app-runtime` (11 uses),
`commands` (3), `prompts` (2), `skills` (3) — plus the host's own two files,
and the constraint is unchanged: the four have to lift before the host's files
can, because each is a rank-4 file reaching the `:tools`/`:commands`/
`:prompts`/`:skills` services through the façade. The lift is a move plus a
handle, exactly as `tools/registry`'s was at 4f.

**Part 1 landed: the host's clean leaves.** Measuring the closure again after
4f — every `agent`-module namespace the two host files reach, transitively —
gave nine files, and four of them reach no rank-4 namespace themselves:
`extensions/context.cljc` (1,589 lines, the SCI/Jolt evaluation contexts),
`extensions/libs.clj` (44, the bundled library set), `retry.clj` (301) and
`loop_guard.clj` (165). They moved first, with `extensions/test_libs.clj` and
the ledger the move implies: `agent` stops declaring the six bundled-library
coordinates it no longer requires and `sci` (it mints no context now), and
`app` gains those six plus `kmet/loader`, which the evaluation contexts reach.
`kmet.test-module-deps` reported exactly that and nothing else, which is the
guard doing the job it was written for. `kmet.tasks.test-changed` had one stale
path (it named `extensions/context.cljc` under `modules/agent/src` as the
closure's example dependency); it points at the new home.

Ledger: four source files (2,099 lines) and one test namespace (219 lines)
moved, namespaces kept — five `R100` renames, not one rewritten line. `agent`
loses six library coordinates and `sci`, `app` gains those plus `kmet/loader`.
The only edited file is the rpc test above; its subject will move with the host,
which is why the test namespace went along rather than staying behind with
`kmet.app.skills` and `app-runtime` among its requires.

`app-runtime` is the ninth file of the closure and the one that cannot move:
it is kmet1's process shim — the root ctx, the boot behind it, the loop's stage
map — and `kmet.test-no-global-node` bans its name from `app` src precisely
because a rank-2 module must not reach a process boot. So the remaining work is
**cut, not moved**: the four registry-wired namespaces (`commands`, `prompts`,
`skills`, and the host's own `extensions/core.cljc`) take the `Registry` handle
as a parameter instead of reading the façade, and kmet1's callers supply
`(app-runtime/registry)` — the pattern `tools/registry` set at 4f. `prompts`,
`commands` and `skills` each reach the handle from a handful of private
readers; the host reaches it from ~20 sites spanning its api closures, its
theme/flag/renderer accessors and its unload path.

**One test moved from proxy to contract.** `kmet.agent.modes.test-rpc`'s
terminal-event test asserted that `:agent-settled` was the *last* record; it is
not reliably, and never was: `cancel-turn` signals the run and then emits
`:status`/`:queue-update` on dispose's thread while the run settles on its own,
so either can land after the terminal event (measured at `10c3f9ac` too — 1
flip in 6 attempts without the app root booted, more with it). The test now
pins what it names — the settle reached the stream before dispose dropped the
subscriber, `:aborted`, and nothing but UI noise after it — and the status
ordering is landing 5's, when the mode moves onto the app's bus.

**And the shell seam did not block it.** The table's "what it needs first" for
`tools.bash` said the shell seam (landing 6) had to come first, because
`bash-executor` is a `defonce` of process state. 4f moved it with the defonce
intact: a process-state cell is only a blocker for a *second* process, and
landing 6's job is to make it a service, not to make the file movable. The
correction matters because it was the reason 4b-ii split at all — the split
stays right (behaviour before file move), but the file move was never hostage
to landing 6.

### Landing 4c-ii-b settled

- **The stage map needed no new `app` API.** `kmet.app.runtime/boot` already took
  `{:ctx-meta …}`, so the root ctx is booted with `:loop-stages` and nothing at
  rank 2 learns what a loop stage is. The alternative — `srce/extend`ing the
  booted root inside `app-runtime` — would have put a second, phantom child under
  the root whose only purpose was to hold an atom, and 4d's tree would have had
  to explain it.
- **One mutable cell moves rather than dies, deliberately.** A ctx's meta is
  immutable, so a `refresh-stages!` that re-seats a reloaded guard needs an atom:
  the atom lives in the root ctx's meta and `srce/attr` finds the nearest one,
  which is exactly the inheritance a child ctx should get. Replacing the ctx
  instead would invalidate the value the mounted fibers already hold. 4e still
  deletes the store's mutable cells; this one belongs to the ctx.
- **The doc counted the wrong call sites.** `app_runtime/node` had 7 src readers,
  but the *accessor* `agent-node` had five more (`interactive/builtins`,
  `session_admin` ×2, `turn`, `ui_registry`) — a sweep that greps the resolved
  node misses every caller that asks the engine. What the sweep actually touched:
  12 src files and 8 test files (6 with the landing, 2 with the drive-by below).
- **The store-facing sites are not convertible, so they went to the app root.**
  `extensions/discover-resources!` and the `install-*`/`dispose-owner!` pair are
  store registrations until 4b-ii; handing them the engine's ctx would have
  silently dropped an engine's resource contributions. Before this landing the
  default engine already installed on the app root — only a hypothetical
  `:node`-passed child tree differed, and no src or test caller passed one — so
  the interim is honest: the engine's installs are app-root, and 4b-ii makes them
  the host's effects.
- **Two scoping tests were asserting the dead mechanism.** `test_event_bus`'s
  subtree and collect/boundary tests built `scope/node` trees; they build ctxs
  now, so "a scoped listener hears its own subtree" is asserted against
  `srce/inside?`, which is what ships. No listener in src registers a scope yet,
  which is why the switch is behavior-preserving for kmet1.
- **`loop-stages` inherits; `refresh-stages!` names its target.** The two read
  and write different things and the first pass had them both walking the chain:
  a refresh aimed at a ctx with no stage atom of its own would have re-seated an
  arbitrary ancestor's map from a child call. A refresh re-seats the ctx's own
  atom when it has one, the app root's otherwise, and a test says an isolated
  tree survives it. (7a went one step further when the map moved to
  `kmet.app.loop.stages`: a refresh names its target or does nothing, and only
  the process shim's no-arg form names the root.)
- **A scope is checked, because nothing else would catch it.** A store node
  walks `:parent` too, so `srce/inside?` would "work" on one and silently match
  the wrong tree. `on-event` and the three emitters reject a non-ctx scope with
  `:kmet.agent/bad-scope` — the check is where the mistake is made, not on the
  read.
- **`ctx` stopped meaning two things in the loop.** The attempt map the step
  functions thread through (`:agent`, `:stages`, `:text-buf`, …) was also named
  `ctx`, beside `(:ctx agent)` for the kernel ctx; it is `attempt` now, so the
  next landing can move agent handles through this code without a misread. The
  tool-call ctx the before/after hooks receive keeps its name — that one *is* a
  ctx in the extension API's sense.
- **`/units` lost its per-session variation.** `handle-units` walks the app root
  node by definition now; a session can no longer point it at its own tree. That
  is the cutover 4d was waiting for, and the test asserts the rendering, not the
  identity of the tree.
- **A drive-by the changed-file lint surfaced:** `test_packages` and
  `test_run_code` required `kmet.agent.app-runtime` without using it — left
  behind by 4c-ii-a and invisible to a lint that only reads its own changed
  files. Removed here.
- **The ledger.** Nothing re-hosted: 12 src files, 8 test files, and `(scope/…`
  sites 26 in 5 files → 25 in 4. What stays is the host's nine, the app node's
  five and the Engine's two.

### Landing 4c-ii-a settled

- **Writers and readers had to move in one change, and did.** A registry
  registration is one call and a read is another; moving only the reads (or
  only the writes) leaves a turn where a command is registered into the store
  and looked up in a service. So the four namespaces moved both, and their
  `node` parameters went with them — a parameter that no longer selects
  anything is the copy AGENTS.md forbids, and lint says so (`unused binding
  node`) at all nineteen sites.
- **The sweep was ~200 call sites, mostly tests**, and the full suite is what
  caught the ones a regex cannot: the package loaders silently passed the node
  as the *paths* argument, the interactive-mode reload tests stubbed
  `clear-skills!` at its old arity so `/reload` aborted mid-way (and the abort
  looked like a settings bug), and `run_code`'s tests registered through
  `tools.registry` under an alias the sweep did not know. Each was a real
  symptom of a real arity change, not a flake.
- **Owner-scoped removal is a registry operation, not a store trick.** The
  protocol had `clear!` (one tag, every owner) and per-name `deregister!`, so
  "everything this owner registered" was only expressible through the store —
  which is why the host relied on owner-path disposal and why resource-loaded
  skills survived unload once the registries left the store. `deregister-owner!`
  is the missing operation; unload is now one call per tag.
- **`/units` keeps both halves.** The store walk shows what still lives there;
  a registry line per service (`:commands keys=[…]`) shows what moved — read
  from the handles, which is the same surface from the other side and the
  starting point of 4d's renderer.
- **The ledger.** 4 commits this landing (the four registry services, the
  façade, and two test-side corrections folded into the ctx commit). Nothing was
  re-hosted: the registries moved from the store to services with their call
  sites, and the store kept only what has no seam yet.

### Landing 4b-i settled

- **The ABI moved, and it cost nothing.** `kmet.extension` had no `:require`,
  so the file and its 461-line contract doc moved into `app` unchanged; the
  twelve documents that named the old path were updated with it. Nothing else
  in the layer needed to move, because agent (4) may require app (2), and the
  loader serves the namespace by name from the classpath — verified by loading
  a real extension under bb and resolving the ABI under Jolt.
- **The api contract is now a test, not a hope.** The ABI is a function table
  over a map, so a wrapper reading a key the host stopped providing fails
  inside somebody's extension at the moment they use it — the one failure mode
  no unit test on either side catches. The test reads all 62 paths out of
  `kmet/extension.clj` (top-level reads and `(:key (accessor api))` reads,
  whose parent is the accessor's own name), loads the single-file fixture, and
  compares against the api the host built. Its own violation check was run: a
  renamed capability and a renamed top-level key both fail it, and the file
  restored green.
- **`:inject` waits for 4b-ii.** It was scoped to 4b-i on the grounds that it
  needs no ctx, but *honoring* it does: without a ctx there is no service to be
  pending on, so the manifest key would be a declaration nothing reads. It
  lands with the wrapper and grep-tool's demonstration, where pending →
  active means something.
- **A host accessor came out of it.** `extensions/extension-api` returns the
  api a loaded extension was handed. It exists because the api is the only
  thing that can be checked against the ABI, and because "why is my `:notify`
  nil" is a real question; it is documented as a diagnostic, not a surface.

### Landing 4c-i settled

- **A lift, not a re-host, and the docs say so now.** The session layer's
  `ReentrantLock`, `InputStreamReader` chunked reads and `Instant` calls run
  unchanged on bb, Jolt and JVM (verified by running create/append/load under
  Jolt before touching anything), so nine files and their tests moved with
  their namespaces intact — 2,370 source lines and 2,432 test lines — and the
  only code change the move required was the `debug` edge. `modules/app` gained
  its library edges (`libs`, `ai`, `babashka/fs`, `malli`), the session tests
  moved with the code, and the two host-side tests (`--debug` owns the strict
  pass) stayed in `agent`, where they die with kmet1.
- **The move exposed a real `--dump-config` bug.** The first row to declare the
  documented *vector* `:inject` form (`:inject [:sessionPersistence]`) broke the
  dump — `render` did `(into {} inject)` and a vector of keywords is not a map
  entry. The card now normalizes through `srce/normalize-inject`, so a card
  reads like a fiber record (`{name nil}`), a malformed declaration fails at
  compose time rather than at mount, and a regression test pins it. Worth
  remembering: every row so far had `:inject {}`, so nothing had exercised the
  vector form since landing 1.
- **`:result-tokens` stamping stays where it is.** Moving it into `:sessions`
  was in the plan; the contract test does not assert on tool entries, so the
  move buys nothing until `:projections` lands, and `:sessions` is where that
  derived state belongs. Recorded rather than done.
- **The ledger.** 9 source files and 10 test files moved (net +84 lines: the new
  row, the `:inject` normalization and its test), and 4 commits: the `debug`
  edge, the order correction, the move + rows + dump fix. Nothing was
  re-hosted, which is the point of calling this a lift.
- **4c-ii cannot delete the store, and that is a finding rather than a slip.**
  The cutover was to end with `kmet.libs.scope` gone — but the extension host
  held nine `(scope/…)` sites (owner paths for hooks, the UI port, themes), and
  those only left when the host itself moved, which needed the root ctx this
  landing created. They went at 4b-ii-a, and the Engine's two with them. So the
  store retires at **4e**, after 4d replaces the last two sites
  (`owner-listing`'s walk).
  [`srce.md`](modules/srce/src/kmet/srce/srce.md) §9's "all of it at the end of 4c" was
  wrong for the same reason.
- **The foundation landed first, and it is not speculative.** `:commands`,
  `:prompts` and `:skills` joined `:tools` as rows (`kmet.app.registries`: one
  shape, four declarations, `kmet.app.tools` delegating to it), so kmet2's
  composition provides the four registries the catalog lists. Nothing reads them
  yet — that is 4c-ii proper — but the boot and the dump do, and the rows are
  what kmet2 needs regardless of kmet1.

### Landing 4a settled

- **The seam is seven ops, not five.** `:sessions` needs a handle and a way to get one, so `create!`/`load` are part of the storage contract and `paths` replaces `list` (it lists paths, and the app never learns a filename convention). The doc's five names (`append!` `read` `list` `delete` `header`) were a sketch of the *log's* surface; `entries` reads better than `read` in Clojure and the id index adds `get`.
- **The backend is values, not a protocol.** A backend is a map of fns, checked once by `service` — a missing op throws `:kmet.app/incomplete-backend` naming it at mount, instead of `nil is not a function` halfway through a turn. The ops take no ctx: a backend is stateless (the handle carries the state) and per-context concerns arrive through the row's config.
- **Two rules are the contract.** An append is the write (it stamps `:id`/`:parent-id`/`:timestamp`, chains onto the leaf, and never renumbers), and nothing is stored before the first assistant entry (a session that never got an answer leaves no file). Both are observable through `paths`, so both backends have to answer them and the contract test says so.
- **One delta, recorded not discovered.** The EDNL backend stamps `:result-tokens` on tool entries (`session/store.clj`'s append policy). The contract does not assert on tool entries, so the backends agree where the contract speaks; the stamping moves into `:sessions` with the layer itself (4c-i), where derived state over the log belongs.
- **The session layer's interop needed no porting, and got none.** `ReentrantLock`, `InputStreamReader` with `char-array` chunks, `(String. buf 0 n)` and `Instant` all run on bb, Jolt and JVM — kmet1 has been running this layer on all three, and a Jolt run of create/append/load was the check before touching any of it. So 4c-i is a file move plus one require to remove (the `debug` edge), and the seam met the layer where it stood: `kmet.libs.concurrent` grew nothing, and `kmet.app.sessions` writes its one timestamp the way the store does (`(str (java.time.Instant/now))`). The speculative `lock`/`iso-now` helpers first written here were deleted again before the landing closed: a primitive with no consumer, for a portability problem that does not exist, is not a facility.
- **The ledger: 429 lines added, 4 changed, nothing lifted.** 190 for the service, 34 for the EDNL projection, 205 for the two test suites. A landing that settles a contract looks like this from the outside — it changes nothing a user can see, and the reuse it enables is 4c-i's. The rule still holds: if *re-hosted* outgrows *lifted* for two landings running, stop.

### Landing 3 settled

- **The mechanics take a handle, the rules stay in one place.** `kmet.app.registry`
  declares a five-method `Registry` protocol (`register!`, `deregister!`,
  `by-owner`, `ordered`, `clear!`) and the readings every backend must answer
  identically — `entry` (first in display order wins, so the owner that took a
  name keeps it), `entries`, `effective-entries` (one per name, so listing and
  lookup agree), `store-key`/`key?`. A backend cannot drift from the rules
  because there is nothing else to implement them with. `kmet.app.registry`
  did not exist as a per-backend copy: the file moved to `modules/app` and its
  scope-store half became `kmet.agent.registry-store` in `modules/agent`, so
  the app module never requires the store that 4e deletes.
- **Two backends, one contract.** `atom-registry` (an atom of
  `{:tag :name :owner :value}`, registration order is display order, a disposer
  removes exactly the entry it installed) is kmet2's; `store-registry` wraps
  kmet1's scope node. `kmet.agent.test-registry` runs the same assertions
  against both — register/entry/dispose, replace within an owner, first owner
  wins, deregister by owner and by tag, `clear!` across owners — so the store
  and its replacement answer one contract until the store is gone.
- **The `:tools` service is installation once, mutation by handle.** The row's
  `apply` provides an empty `atom-registry` into the app scope (`{:in …}`, like
  the events row); adding a tool is
  `(registry/register! handle :kmet/tool name tool owner)`, so a plugin adding a
  tool never re-provides, and the disposer it gets back is what a fiber effect
  runs on dispose. The tool *semantics* — built-in map, schema normalization,
  selection — stay in `kmet.app.tools.registry` (rank 4), whose reads gained
  an explicit-argument arity (`(get-all-tools reg)`, `(get-tool reg name)`,
  `(get-contributed-tools reg)`); the zero-argument forms ask the app root, so
  every kmet1 call site was unchanged until 4c-ii-a threaded the ctx.
- **One mutation path.** `register-tool!`/`unregister-tool!`, their source
  counterparts, `register-command!`/`unregister-command!`, the prompt-template
  loaders and `register-skill!` all go through the handle now (`tool-key`,
  `tool-source-key`, `command-key`, `prompt-key` and `skill-key` are gone), so
  kmet1 and kmet2 write registrations the same way, the tag/name addressing
  lives in the registry rather than in each caller, and those five namespaces
  no longer require `kmet.libs.scope` at all.

### Landing 2 settled

- **The vocabulary moved, the mechanism did not (yet).** `event-types`,
  `app-event-types`, `loop-event-types` are now `kmet.app.events`' — three
  fifths of `event-bus.clj`, pure data — and `kmet.app.event-bus` re-exports
  them, so
  every kmet1 call site and test is unchanged. kmet1 keeps its process-level
  table, its owner/scope and its three emitters until the loop re-points
  (7b-i): that is the landing rule ("kmet2 uses the new mechanism, kmet1
  still uses the old one"), and delegating now would have taught the app's bus
  kmet1's store-node scoping — a value that dies with the store at 4e.
  A contract test runs one set of delivery assertions against both backends, so
  the pair cannot drift while both exist. The wire projections stayed with
  kmet1's transport: they are serialization, not the bus.
- **All six non-awaiting modes are the kernel's, and they are the same shape.**
  `emit!` (nobody's answer), `bail!` (the first non-nil, short-circuiting),
  `waterfall!` (`(f event next)`; not calling `next` vetoes), `collect-last!`
  (the last non-nil), `collect-all!` (every non-nil result as `[data result]`)
  and `boundary!` (each listener sees the accumulated map merged into its event
  and returns the keys to replace). They live in `srce` because after
  `{:data …}` on a registration none of them needs an app concept: the owner a
  caller attaches is *opaque kernel data*, and the merge protocol is data
  handling. What stays above is the vocabulary and the owner *value* — a kernel
  may not name an app event — plus the one exception rule's home
  (`srce/invoke!`, which every mode calls) and `names`.
  `parallel`/`serial` stay absent, and not for lack of availability: `future`,
  `promise`, `deref` timeouts and `core.async` (including `go`) run on all three
  hosts. Their content *is* the await, so a sync kernel collapses them onto
  `collect-all!` and `bail!`; and awaiting by deref blocks the owner thread,
  while awaiting in a `go` block colors every caller. Async fan-out is the app's
  queue (`submit!`/`boundary!`), which is Cordis's `ctx.submit`; the kernel
  grows a mode only when an emitter needs *concurrent blocking* listeners
  (`srce.md` §5).
- **The registration is the record; there is no side table.** `on` takes
  `{:data …}`, the table stores `{:f :data}`, and `listeners` hands back
  `{:ctx :f :data}` — so `collect-all!` reports a contribution beside the data
  its registration attached, `once` carries it to its one delivery, and kmet1's
  `[owner result]` shape is that pair with the owner as the data. Scope needs no
  field at all: a listener hears emits from the ctx it registered on and that
  ctx's subtree — kmet1's node rule expressed by the tree instead of a stored
  node.
- **A plugin declares its own events, and the declaration is what is read.**
  `defplugin` grew `:events` (`{event {:mode :doc :domain}}`); `plugin!` checks
  the shape and puts it on the fiber record, so a mounted plugin's declarations
  are inspection data, a name re-declared by two mounted plugins must agree, and
  `kmet.app.events` derives `event-types`/`app-event-types`/`loop-event-types`/
  `event-modes` from the **declaration in code** — no projection in the path, and
  nothing to go stale. That is the harness's per-package declaration
  (`declare module … interface Events`) with kmet's declaration form.
  No projected catalog is in the path (landing 1's generated file was deleted;
  a row's card comes from its plugin map, and `--dump-config` resolves the
  sources it prints). The **wire projections** (`wire-event`,
  `wire-error`) stayed with kmet1's transport rather than moving with the bus:
  they are serialization, and `kmet.mode.print`/`kmet.mode.rpc` take them when
  the app's transports land (5). `srce/dispatch-modes` is the authority on which
  modes a declaration may name. Today the `:events` row declares all 43
  — it owns the vocabulary until the loop, session and mode plugins claim
  theirs — and the guards are: a declared mode must be one the kernel can
  dispatch (`:serial`/`:parallel` may not be declared while there is no
  awaitable), and every event carries a docstring.
- **`{:in ancestor}` is the one kernel addition the composition needed.**
  `provide!` registers into the *current* ctx's store, so a plugin's service is
  visible to its own subtree and to nothing else — right for a per-agent
  capability, wrong for the app's flat row model, where every row mounts as a
  sibling under the root. `(provide! ctx name value {:in scope})` puts the entry
  in the mount site's store while the effect and the provider record stay the
  providing fiber's: the `:events` row shares its service with the app and dies
  with its own fiber. The duplicate check reads from the target scope.
- **`kmet.app.runtime` is the boot and the submission point.** `boot` mounts a
  composed row list under one root ctx (a var symbol resolved off the
  classpath, a var, or a plugin map; an `:ext/…` id is refused at boot — the
  host landed, the row-source bridge did not) and throws
  `:kmet.app/boot-failed` naming every row
  whose fiber is not `:active` — its state, its error, and what it waits for.
  `submit!` appends a thunk to the root's queue from any thread; `boundary!`
  drains it (to a fixpoint, eight rounds) and settles the kernel, so a mount or
  a registration made on a tool thread lands at the boundary and never
  mid-pass. The queue is a ctx key, not a service: it has no plugin, no
  lifetime and nothing to inject.
- **The first row is in the composition.** `:bundle/minimal` carries
  `:events`; `--dump-config` prints it, and a bare `kmet --app new` boots it,
  reports what mounted, disposes it and still exits 1 ("no runnable mode yet").
  The boot is the point: it is what proves a profile's rows mount without a
  turn to run, and it is where every later landing's rows get their first
  end-to-end check.
- **A ctx is cyclic, so nothing prints one.** `ctx → :kernel → :runtimes →
  fiber → :ctx` closes the loop and no host's printer detects it — `pr-str` of a
  ctx, or of a value holding one, is a stack overflow. Nothing may carry a ctx
  in a service value (`kmet.app.events/service` binds it in a closure), and a
  report names states, counts and service names, never the value.

### Landing 1 settled

- **`modules/app` is rank 2 and `agent` moves to rank 4** in the manifest, from
  the first landing: the inversion is explicit before there is anything above
  it. The module carries `kmet.app.profile`, `kmet.app.catalog`,
  `kmet.app.core`, and `kmet.app.version` — the version file moved so both
  entries print one string, and its namespace did not (the two-axes rule). The
  three `kmet.app.version` assembly functions (`baked-version`,
  `source-run?`, `kmet-version`) moved with it out of `core.clj`; the marker
  names `kmet/agent/core.clj`, the bootstrap every artifact carries.
- **The bootstrap is one namespace with no `kmet.*` require.** `parse-app`
  strips `--app`/`--app=` before either app parses (kmet1 would read it as an
  extension flag); `dispatch!` resolves the entry through `requiring-resolve`
  only after `app-available?` finds the app's source resource, and returns
  `::missing-app` instead of exiting — `-main` owns the exit code. The
  two-direction load test runs real processes for the reason a test process
  cannot answer it: `bb test` has already loaded namespaces.
- **A row's card is the plugin map, and there is no manifest beside it.** A
  generated `kmet/app/plugin-catalog.edn` was landed here (and a
  `bb generate-plugins` scanner behind it) so `--dump-config` could print a
  built-in row's card without loading its namespace. It was deleted in landing
  2: dereferencing a `defplugin` var mounts nothing, so the card is read from
  the declaration itself, and the only thing the manifest bought — describing a
  row whose namespace *cannot load on this host* — has no row yet. When it does
  (a host-gated `:ui/tk` row), the decision belongs where the information is:
  the profile row carries its own label (profiles are data), or `describe`
  learns an "unresolvable here" marker. Until then a source that does not
  resolve is `:kmet.app/unknown-plugin` in `--dump-config` as well as in boot.
- **Rows arrive described.** `kmet.app.catalog/describe` turns a row into a
  card (`:id :name :inject :provide`) without mounting anything: a var source
  is resolved and read from its plugin map, an `:ext/…` keyword is resolved
  from the installed extensions by name (`kmet.app.extensions.rows`; it used
  to be an external marker at HEAD — § Reconciliation), and `:id` defaults to
  the plugin's `:name`. Composition is then pure data: bundle layers in order,
  config deep-merged by row id, `:remove`, and an insert — a patch naming an
  unknown id that carries a `:plugin` — positioned by `:after`/`:before`.
  A patch entry's allowed keys are checked, so a typo is an error and a patch
  that changes nothing is one too.
- **`--dump-config` prints the rows the boot would mount**, one line each, with
  the profile and the row/patch counts; `--profile` selects the profile and
  `--patch FILE` stacks a layer (repeatable). The built-in table ships the four
  profiles (`tui`, `print`, `rpc`, `server`) with an empty
  `:bundle/minimal`; each plugin landing adds its rows.
- **Guards**: `kmet.test-module-deps` gained the namespace-level require check
  built on the ns-form reader (a require may name only a strictly lower-ranked
  module's file — its own module is fine), `kmet.test-no-global-node` bans the
  store namespaces (`kmet.agent.app-runtime`, `kmet.agent.runtime`,
  `kmet.libs.scope`) and the `runtime/node` symbol from `srce`/`app`/`ui`/`mode`
  sources, and `kmet.test-app-dispatch` holds the bootstrap's static shape and
  the two load directions. Each was verified by injecting the violation it
  guards.

Landing 0 landed the kernel without touching kmet1, and landing 1 keeps that
property: it splits `kmet.agent.core` into a bootstrap and `kmet.agent.cli`
without touching the old app's logic. 2–4 lift kmet1 code without renaming it,
so kmet1 stays green throughout. 5 is the first landing that moves kmet1 code
*and* renames it (interactive → `kmet.ui.tui`). `agent` at 4 may require `ui`
at 3, so kmet1's dispatch re-points at `kmet.ui.tui` behind a `--mode` compat
shim: the old flag keeps working and the capture diff gates the switch.

**The one reversal that is not additive** is a provider that must *await* during
`apply` (a socket bind, an MCP handshake at boot) or must mutate the kernel from
a callback that cannot be marshalled (a Tcl/Tk callback that has to return
synchronously). Landings 5 and 6 are where it would show. Until it does: such a
plugin holds its own promise and the app awaits it at the boundary, and a
synchronous callback queues an intent that the next `settle!` applies. If that
proves insufficient, the kernel grows async `apply` — that is a landing of its
own, not a tweak.

## The remaining plan

**Closed.** The ledger above ends at 7b-ii; landings 5a–5n, 6, 8, T, 9 and 11
landed after it, and the last table row (`scripts/bench/`) is marked done. This
section is kept as the measurement record, and nothing in the ledger is still
open: `:ext/…` composition rows, the last one that was, shipped in the
reconciliation pass (§ Reconciliation).

### What is left, measured

Everything kmet1 carried is gone, and 9's execution seams are in: `:shell` and
`:sandbox` are per-agent services, `:workspace`/`:fs` needed no service
(`kmet.libs.path` resolves against `(:cwd ctx)`), and `:llm`/`:models` and
`:config`/`:auth` stayed libraries (a run's ctx carries `:models`/`:model`;
extension providers register through `models-register-provider!`; landing 2 of
§ Everything is a plugin later made `:llm` a service — `:models`/`:config`/
`:auth` remain libraries). `:modes`
is not a service at all — the modes are rows and the profile is the selector.
`kmet.app.test-process-state` is the ledger of the process cells that remain,
each with a written reason. 10c closed the server's
transport: the bearer token, `--socket`/`--max-sessions`/`--queue-depth`/
`--idle-timeout`, the `:lag` policy with its `:stream-gap`, and the config a
connection brings (its own page is [`docs/serve.md`](docs/serve.md)). The last
loose end — `scripts/bench/`'s three harnesses — is closed: they load and run
again after the require repoint above.

| What | Where | Why it is left | Cut |
|---|---|---|---|
| `scripts/bench/`'s three harnesses | `scripts/bench/kmet_frame_bench.clj`, `kmet_perf_bench.clj`, `kmet_render_bench.clj` | **done** — requires repointed at `kmet.ui.tui.interactive` / `kmet.app.tools.registry` + `kmet.app.registry/atom-registry`, extension host bound via `kmet.test-utils/with-extension-host`; all three load and run on `bb` again | — |

### The interactive front end first

The TUI is the front end the app is used through, so its completeness comes
before the wire's remaining table: nothing in 10a is user-facing on its own, and
every capability it would serve already exists on the handle the TUI drives.
The work is not new features — it is the last pieces of the re-host landing 8
finished, measured from the TUI's own code:

- **T-1. The interactive tests run on the composition's handle — landed.** The
  suite builds kmet1's `session.driver` engine (`tu/test-engine`); a handle-shaped
  fixture boots the `:tui` composition and creates through `:agents`, so a
  test fails when the handle stops answering an op the TUI calls. The fixture
  is `kmet.ui.tui.interactive.test-handle-support`: `with-boot` for a
  namespace (the only way a test gets a composition — the fixture owns the
  boot), `create-handle!` for the full creation (`:session`/`:cwd`/`:config`,
  with the boot map's ctx and agents), `handle-with` for the handle alone,
  `new-handle` for one with no options, and `core-state` for a CoreState — its
  options override the fields it builds and seed the handle's own atoms
  (`:session`, `:config`, `:cwd`, `:turn-running?`, `:messages`, `:model`,
  `:provider`, `:thinking`, `:scoped-models`, `:cfg`, `:compacting?`), with an
  explicit nil meaning none. Each boot owns a session directory, so two boots
  in one test (the two-agent shape landings 9 and 10 need) do not delete each
  other's sessions.

  Every file in `modules/ui/test` is converted: `tu/test-engine` appears nowhere
  in it. `test-handle-path` and `test-interactive` (including the `/new` pair,
  model/thinking cycles, share/summarization and the scrollback-heal fixtures),
  `test-commands` (`/name` and every settings/queue command), the compaction
  queue, `/import` and the switch tests, theme, layout and the settings panel.
  The double's hand-rolled run ops are gone with it: those tests read
  `runtime/*` and the handle's own atoms, and a test that needs a special
  session registers its state record the way a switch does. Two fixtures from
  the double survive as facts about the composition: boot publishes its
  registry as the process command registry (a converted test registers its
  builtins inside the fixture, after the boot), and a CoreState helper needs
  the caller's boot.

  Three namespaces outside `modules/ui/test` keep `tu/test-engine`:
  `modules/agent/test`'s `test-ui-port` and `test-runtime-capabilities` (their
  subject is the extension surface over a bare engine, not a mode driving a
  composition) and `modules/ai/test`'s `test-oauth` (rank 1 — it cannot reach
  the composition at all).

  The fixture boots the real composition, so a run loads the agent directory's
  resources the way the app does; commands are cleared per test and the tests
  that depend on a resource set pin it (`th/get-all-themes` in the settings
  panel), which is the price of proving the mode over the real registry.
- **T-2. The interactive suite moved to `modules/ui/test` — landed.** The ten
  files that test the TUI now live beside it: the interactive cluster — the
  support fixture, autocomplete, commands, the compaction queue, import,
  layout, state, theme, the mode entry and the overlay pty smoke, all under
  `kmet.ui.tui.interactive.test-*` — and the row (`kmet.ui.tui.test-tui`). `modules/agent/test/kmet/agent/modes` keeps
  only kmet1's front-end tests (`print`, `rpc`, `rpc-client`) and the
  headless-require guard, which is about those front ends. Discovery needed no
  registration: the module test roots find the files where they now are.
- **T-3. A preset reaches a TUI run's child ctx — landed.** `create!`'s
  `:setup` seam (7b-ii) now has its production caller: a mode row names the
  bundles in its config (`{:presets [:bundle/gated]}`), `run-opts` carries them
  into the run, and `build-layout`'s `create-opts` mounts them under the child
  ctx before the agent exists (`presets/setup` takes one name or a collection).
  `:profile tui-ask` is the built-in that names the gate — the TUI counterpart
  of `:rpc-ask`, without a root bundle. Verified on a pty: a bash tool call of a
  `tui-ask` agent asked `Allow bash?` through the TUI's own Yes/No dialog (and a
  cancelled dialog denied the call), which is 8's gate item for presets now
  met. `kmet.ui.tui.test-tui-ask` pins the profile, the row-to-run mapping and
  the per-agent policy (a plain agent's call proceeds; the root provides no
  `:permissions`).
- **T-4. The TUI's last driver edges — landed.** `session_admin`'s two
  `session.driver` calls are gone: the session-start emission is
  `kmet.app.session.lifecycle`'s (the shared lifecycle below both drivers),
  and the branch summary moved to **`kmet.app.session.summary`** — a new
  app-level name for the one-shot session work a front end runs about a session
  (`kmet.app.compaction.stage`'s call, named so no mode reaches the stage
  itself; the layer guard forbids that require). `kmet.ui.tui.*` names
  `session.driver` nowhere, and `kmet.test-front-end-policy` now holds it: the
  guard reads every TUI source's ns form and fails on the require.
- **T-5. The mode's docs describe the shipped contract — landed.** The row
  docstring's "does not do yet" paragraph described 5j, superseded by 8; it now
  describes the handle path and the preset seam. The ledger's two stale gate
  sentences (a `/new` that recreates an agent, presets that already reached a
  TUI run) say what the shipped code does.

Gate: every TUI code path a test drives creates its agent through `:agents`;
the interactive suite needs no `session.driver` and no `kmet.agent.modes.*`
namespace; a TUI run with a preset names the policy in its child ctx; and the
row's docstring describes the handle path a reader will find.

### The landings

| # | Touches | Size | Waits on | Gate |
|---|---|---|---|---|
| 5k | `app` (+1 ns), `ui` (+1 ns), `profiles.edn`, two modes | ~250 src, ~150 test | — | every provider answers the four required keys; `(ui :request {:kind :confirm})` answers from the row and nil without it |
| 5l | **landed** — `agent` → `app` (two moves + the world load), `agent` → `ui` (12 moves) | ~5,300 moved, ~120 changed | 5k | pty parity; `agent/modes` holds only kmet1's three; a kmet2 extension's command is in the ctx's `:commands`; a print run has extensions loaded; the TUI profile's ctx provides a terminal `:ui` and an approver |
| 5n | **landed** — `app` (the entry + the modes), no move | ~450 src, ~250 test | 5l | `-p @tasks.md`, `/skill:` expansion, `--thinking`/`--list-models`/`--debug`/`--generate-models`/`--session-dir`/`--my-ext-flag`, and `config` with no rank-4 load |
| 8 | `ui`, `app` (the handle's op set, the mode listeners) | ~900 changed | 5n | a TUI run's agent is in `:agents`; no `session.driver` mutator left in `kmet.ui.tui.*`; two print runs settle their own promises |
| T | **landed** — `ui`, `app` (the preset seam, the last driver edges, the tests' home) | ~450 src, ~4,800 tests moved | 8 | every TUI path a test drives creates through `:agents`; a preset reaches a run's child ctx; no `session.driver` in `kmet.ui.tui.*` |
| 9 | **landed** — `app` (`:shell`, `:sandbox`, the tool context's `:srce-ctx`), `libs` (verified) | ~1,500 adapted | 8 | two agents run shell in two cwds with two shells (`kmet.app.test-isolation`); a script's inner call resolves no host service (`kmet.app.test-sandbox`); the process cells are a ledger (`kmet.app.test-process-state`) |
| 10 | **landed** — 10a (the whole wire table), 10b (the boundary drain), resume-by-id, and 10c (the transport) — `app`; 10c also `libs` (the bounded lane) | ~1,700 | — | every `docs/rpc.md` command answers (or is a documented refusal); a tool thread's mount lands at the next turn; an agent resumed by id answers in its stored session; two sessions isolated; a connection authenticates before it gets an agent; a client that stops reading gets a `:stream-gap` and resyncs with `:get-state` |
| 11 | **landed** — all modules | −8,500, ~240 renames | 8, 10a | full gates + pi-alignment, no `kmet.agent.*` but the ABI |

**The execution order was T → 10a → 11 → 9 → 10b/10c, and all of it has
landed.** The interactive front end went first because it is what the app
is used through; 10a's table is invisible to a TUI user. Retirement followed as
soon as it could be taken: its hard prerequisites were a complete 8 (the TUI
reads a handle, so `session.driver`'s engine path could go — T-4 took the last
TUI call out; the engine itself was retired from `src` afterwards, and the tests
that drove it now use handles through `:agents`) and 10a (kmet1's rpc modes
were deleted by 11, and kmet2 must already answer the whole `docs/rpc.md` table,
or the protocol regresses). 9's
isolation seams landed after the rename pass, which kept that pass out of the
files 9 would touch. 10b and 10c were kmet2's own boundary and transport work —
the old app has no server to compare against — and 10c closed them.

`ui-tk` is not scheduled here: it is its own development, and when it resumes
it lands over the handle-based front end 8 produces — a second toolkit is worth
building over a front end that reads a handle, because otherwise `kmet.ui.tk`
would implement the `Engine` port that 8 deletes. 5n sits between 5l and 8: it
needs the world load at the entry (5l-ii, landed) and it extends the same
create-opts map that 8's handle ops read, so landing it first keeps 8 about the
port and not about flags.

### What kmet1 has, and where each piece lands

The audit behind this plan: every user-facing surface kmet1's entry or TUI has,
and the landing that keeps it. Anything not listed here either does not exist
(`--list-kernels`, `--dev`: prose only) or is dev-only (`scripts/`, `bb`/`jolt`
tasks, the Tk demo).

| kmet1 surface | Where it lands |
|---|---|
| the TUI: components, dialogs, selectors, image blocks, hotkeys, themes, external editor, suspend, `!`/`!!` bash | 5l-iii — the files move as they are |
| `/` commands (help, model, thinking, settings, tree, fork, resume, new, sessions, export, compact, reload, units, hotkeys, theme, login/logout, quit) | 5l-iii, with `/model`, `/thinking`, `/compact` re-pointed at handle ops, `/new` and `/session` at the handle's session ops, and `/resume` at the registry's `resume!` (8, then 10a) |
| the approvals dialog for an `:ask` policy | 5l-iii: the TUI's provider row supplies `:approvals` as well as `:ui` |
| `-p`/`--print`, `--mode text` / `--mode edn`, `--profile`, `--patch`, both dumps | landed (5a, 5b, 4d) |
| `--mode rpc` and the wire | served at 5d/6b/6c for the core seven; the rest of its table — reads, session lifecycle, model/thinking cycles, steering modes, `clear-queue`, compaction — is **10a**. `abort` answers a read instead of kmet1's bounded wait, which is 5d's deliberate difference. The *scripted* client (`modes/rpc_client.clj`) retires with kmet1 and `docs/rpc.md`'s examples re-point at the socket harness (`kmet.app.test-support`) |
| `-t`/`--thinking`, `--system-prompt`, `--append-system-prompt`, `--models`, `--session-dir` | **5n** — and the modes apply them, which the TUI row does not today |
| `-d`/`--debug`, `--generate-models`, `--list-models [filter]` | **5n** |
| extension `--flag value` → `(get-flag …)` | **5n** |
| `@file` messages, plus skill-command and prompt-template expansion | **5n** |
| `kmet config` | landed, re-homed at 5l-iii |
| `kmet install/remove/update/list` | landed (`kmet.app.core`'s package dispatch) |
| extension packages, themes, extension CLI flags, the image-model catalog | 5l-ii (the world load moves to the entry, for every profile) |
| session files, listing, tree, stats, export, fork | landed in `app`; the TUI reads them at 5l-iii. Startup continues/resumes a session now (`-c`/`-r`/`--session` reach the TUI row); switching sessions *from inside* one needs 8, then 10a's `resume!` |
| the per-agent extension surface (`install-session!`, the context/entry sinks, `bind-runtime-capabilities!`, `:build-context`) | **8** |
| image attachments on a prompt (kmet1's rpc started path) | **8** |

### 5k — the `:ui` contract and its headless provider

**Landed.** `kmet.app.ui` (rank 2) is the contract. `required-keys` is
`:kind :render-tree :request! :capabilities`; `check` fails at the mount with
`:kmet.app/ui-provider` naming the provider and the key it lacks, not at the
first extension call with `nil is not a function`; and `provide!` validates,
gives the provider the ABI `:port` a mode binds, and publishes it at `:ui` for
the mount site's siblings (the `:systemPrompt` row's pattern). `:request!` takes
the provider's ctx and the request; the port supplies the ctx, because the
extension host calls `:request` with the request alone. Everything optional
(`:widget-set`, `:notify`, `:input!`) is declared in `:capabilities`, so a
consumer can ask what the provider it has can do.

`kmet.ui.headless` (rank 3) is the first provider, and it *wraps* the ABI's
existing answers rather than deciding again what "ask is not yes" means:
`:request!` is `(extensions/ui-request-default (:kind request))`, so a headless
run and a terminal run agree on what an unanswered dialog means — the terminal
provider starts from the same table and only adds who answers. `:render-tree` is
a no-op, and `:capabilities` is empty. `:bundle/headless` is the row (`:id
:ui`); print, rpc, rpc-ask and the server stack it, and print and rpc inject
`:ui` and bind its port (`front-end/install!`), rpc adding the wire's
capabilities on top. The server stacks the bundle but binds no port: its
extension UI calls stay headless, and a per-connection port needs a per-agent
front-end binding, which the host's single process atom does not provide — so
the port is still out, after 8 and after 10b.

**Why first.** It is the smallest missing seam with a real consumer today: two
modes built a port by hand, and `front-end/install!` is the one place they hand
it over — so the re-point is a line per mode, and the contract test that makes
the seam worth having is written against the providers the tree ships (the TUI
together with the headless bundle; a tk provider would join the same table).

**Gate.** `kmet.ui.test-headless` enumerates every provider the tree ships and
asserts the four required keys — the 4b-i api key-set test's twin, failing when
a provider drops one — checks the mount error names the missing key, and boots
the print profile: the row's port answers `false` for `{:kind :confirm}` and nils
for `:select`, while a patch that removes the `:ui` row leaves `(srce/get ctx
:ui)` nil. `test_inspect` already holds the kernel's half: a required consumer
waits (`waiting-for=[ui]`), an `:optional` one never does, and both render
distinctly.

**Not in it.** The toolkit. `ui-terminal` waits for 5l's move; `ui-tk` is
separate development (it waited for 8). The dialog `:request`/`:response` pair
is landing 6c's; the server's per-connection port stayed out of 6 and out of
every landing since.

### 5l — the TUI library moves into `ui`

Three commits, each independently gated, because each has its own blocker.

**5l-i: the clean two move down.** **Landed.** `kmet.app.session.driver`
(1,009) and `kmet.app.commands.builtins` (159) moved into `app`, namespaces
kept (the two-axes rule: `git mv` plus one edit). That edit is `builtins`'
`/units`, the last `app-runtime` call in it: `register-runtime-commands!` now
takes a thunk that returns the process root, so the command is pure rank 2 and
the front end — which has the root in hand — supplies it. A thunk rather than
the ctx itself because `/units` reads it per invocation: a root booted after
registration is the one rendered, which is the behavior the old
`(app-runtime/ctx)` call had. The driver needed nothing; every one of its
requires already lived in `app` or below. `kmet.app.session.runtime` was
already at rank 2, so the pair the front ends read is one module again.

**5l-ii: the world load, and the publisher.** `kmet.agent.cli/load-world!` —
provider catalogs, the models.edn layer, config, extension packages, theme paths,
extension CLI flags — into `app` (`kmet.app.world`), with `kmet.agent.cli` calling
it, so kmet2's processes no longer load a rank-4 namespace to boot. **And it
moves to the entry, not to a row:** the entry boots the profile with its mode
row deferred, loads the world into that tree, and only then mounts the mode —
so every profile has extensions, themes and the loaded catalogs the way kmet1's
`-main` gives every mode them. `kmet.agent.modes.tui` stops loading its own
(it was the only caller, which is why print, rpc and server ran extension-less).

**Landed so far (5l-ii-a) — the body, and the root it publishes onto.**
`kmet.app.world/load!` owns the load; kmet1's `-main` called it through a
one-line delegate until the review removed it, and the TUI row read its result
until 5l-ii-b rewired the entry. The move needed two supporting pieces, and each is a leak the old location
was hiding:

- `kmet.tui.theme` moved to `libs`, its name kept. The load (in `app`, rank 2)
  reads `load-theme-paths!`, and the components that style themselves (`tui`,
  rank 2) read the rest — a peer may not require a peer, and the file's only
  dependencies are libraries. The extension shared-library test derives its
  universe from the module tree, so it scans `modules/libs/src` whole instead
  of assuming every file there is `kmet.libs.*`.
- The process root's atom moved to `kmet.app.root` (`publish!`, `ctx`, `boot!`,
  `reset-root!`, `restore-root!`); `kmet.agent.app-runtime/ctx` delegates to it,
  and the tests use the root ns directly, so the shim carries no
  publication pass-throughs. Without the move the world load cannot publish an
  entry's composition without requiring the rank-4 shim; with it, the publisher
  is rank 2 like the load. `boot!` takes the boot thunk, so kmet1's lazy
  `{:profile :tui}` boot stays in the shim — `kmet.app.root` never learns a
  profile.

**Landed (5l-ii-b) — the entry loads it, and the mode mounts after.**
`runtime/boot` takes `{:defer (fn [row])}` and returns the held-back rows in
`:deferred`; `mount-deferred!` mounts them, transformed, once the caller is
ready. That is what makes "after `boot`, before the run" a fact rather than a
hope: the mode row's `serve` *is* the run, so it cannot be in the tree while
the world loads into it. `run-mode` boots the profile with the mode deferred,
loads the world into that tree (`world/load!` publishes the ctx before the first
extension registers), resolves the invocation's model patterns — after the
load, because an extension provider is a catalog entry only once it has —
and mounts the mode with the prompt, the result callback and the loaded config.
The TUI row reads that `:config` instead of loading its own (and fails at the
mount naming it when the entry supplied none); print, rpc and the server ignore
it. `load-catalog!` retired into `world/load!`, so `run-process` loads nothing
and is now only the exit code around `main` — the seam 5n's pre-parse flags
will hang on. `--dump-state` still mounts every row at once: a diagnostic boot
starts nothing (each mode checks `:on-result`), and a dump that needed a world
load first would be answering a different question.

The gate: a pty capture on `--app new` lists the extensions in the startup block
(clojure, mcp-adapter, review, …), and `kmet.app.modes.test-print`'s
`the-entry-loads-the-world-before-it-mounts-the-mode` pins the order — the mode's
first model call can only come after the load, and the load sees the tree's
`:agents` row already mounted.

**The entry owns the load order, and the flags bracket it.** There is **one**
load now: `world/load!` runs inside `run-mode`, after the boot and before the
mode mounts; `load-catalog!` retired into it, and `run-process` has no pre-load
work. 5n puts the flags around it: `-d`/`--debug` applied on the `run-process`
side (so the catalog trail is in the log), `--generate-models` writing both user
caches and exiting there, and `--list-models` printing after the load — its
table includes the extension providers the load installed. `run-process` stays
the seam a test can call: flags first, then the world, then the run.

**Landed (5l-ii-c) — one publisher.** `kmet.app.registries` now owns the
tag→service table (`registry-tags`) and `process-handle`, a router over the
composition's services with one remainder for the tags that have none. The
`:extensions` row publishes that handle, binds the complete service map — the
handle, the ctx, the three resource operations (the app's own fns) and the
owner sweep `:deregister-owner!` — and restores both on dispose. So an
extension's command **is** the `:commands` service the run reads, and unloading
sweeps the same handle it registered through. `app_runtime/registry` is now
`kmet.agent.registry/current`, and the shim's own binding boots the root and
then re-reads the binding before it answers, so the first registration of a
kmet1 cold start is never served from the fallback.

That closed the last split: before this the host bound one handle and kmet1's
shim another, over two remainders, and a registration made through one was
invisible to a reader of the other. Seven tests that had been red since 4b-ii-b
— hook ownership, contributed resources, the UI port, the bootstrap's service
map — went green with the row, not with a test adjustment. One test did have to
be fixed: `test-api` restored `(host-services (rt/registry) (rt/ctx))`, and
argument order captured the handle *before* the `(rt/ctx)` that booted and
published it; it now saves and restores the binding it replaced.

The same pass retired `kmet.agent.extensions.services`: the row and kmet1's
fallback bind `registries/host-services`, the one builder, so the host's
service-key contract is asserted in one place. And a read of the root *while it
boots* is now `:kmet.app/root-boot-loop` rather than a second composition behind
the one being mounted.

Gate (met): under `--app new`, an extension registering a command is visible to
`(srce/get ctx :commands)` (`kmet.app.test-extensions`'s
`an-extensions-command-is-the-compositions-command`, and the shared reader
through the published handle); the fallback registry is only for a process that
has published nothing; and a `--profile print` run has the same extensions a TUI
run has (an extension's hook fires in both — the pty captures of `--app old` and
`--app new` list the same set). kmet1 unchanged (`--app old --version`, plus the
extension suites).

**5l-iii: the cluster moves.** The 10 interactive files (5,207) and `tui.clj`
(100) become `kmet.ui.tui.interactive.*` and `kmet.ui.tui` (the provider and the
mode row, one namespace), and `modes/config_command.clj` (57) becomes
`kmet.ui.tui.config-command` with `kmet.app.core/run-config` resolving the new
name; the seams land in the same commit as the move because they are the edits
the move needs: `build-layout`'s and `interactive/run`'s process-root arities go
(5j added both a ctx argument), `refresh-stages!` takes the ctx, and the two
tool-layer reads (`layout`'s `:tool-fn tools/get-tool`, `builtins`'
`resolve-default-tools`) take the table the mode passes. `kmet.agent.modes.tui`
is deleted, and the `:mode-tui` row names `kmet.ui.tui/plugin`. The provider row
supplies `:approvals` too (`:ask!` → its own confirm dialog), which is what
makes an `:ask-all` policy usable at a terminal — until now 4c-iii-a's "an
`:ask` in kmet1's composition denies" was the only possible answer.

**Landed** (`777099fb` for the move; `da14f5b9` for the provider): the seams are
the three the cut predicted, and the second tool-layer read needed no seam —
`resolve-default-tools` is a pure `app` function, so only `layout`'s lookup
moved, now a `tool-fn` built from the ctx's `:tools` registry handle. `/units`
and `/reload` read the run's ctx (`CoreState` carries it) instead of a process
root; `interactive/run` and `build-layout` have no default arity, and the
entry names the ctx. The TUI is also its own `:ui` provider: `kmet.ui.tui`'s
`provider` answers `:kind :terminal` and routes dialogs to the running mode
(`interactive/request!`), and `provider-plugin` supplies `:ui` and `:approvals`
together — the `:bundle/terminal` bundle replaces the minimal approvals row, so
an `:ask-all` policy at a terminal is answered by a Yes/No dialog instead of the
default deny. The interactive tests stay under `agent/test` (their namespaces
are path-derived there) until a follow-up moves them with the module.

Gate: a pty capture from `scripts/capture/pty_capture.py` on `--app new` shows
the same header, context/skills block, footer and `/` autocomplete as before the
move (that diff is the whole assertion — a module move is invisible to unit
tests); `--app old` still starts kmet1's TUI through the same namespace;
`modules/agent/src/kmet/agent/modes` holds only kmet1's three modes;
`kmet.agent.modes.test-headless-guard`'s rule is "no `kmet.ui.*` namespace in
print/rpc/rpc-client" (a static-require assertion, stronger than the planned
plugin-graph one); and `kmet.ui.shared`'s docstring stops naming
`kmet.agent.ui.tui.*` and a test namespace as its surfaces.

### 5n — the invocation surface

kmet1's entry accepts a set of run options and switches that kmet2's does not,
and `kmet.agent.config/apply-cli-overrides` (rank 2) already handles every run
option among them — `:thinking`, `:models`, `:session-dir`, `:system-prompt`,
`:append-system-prompt` — so the work is parsing and plumbing, not behaviour.
The run flags landed first: `kmet.app.invocation` resolves the model patterns
before a boot (kmet1's `resolve-cli-model-opts` order) and `create-opts` is the
map a `:agents` mode takes, while the TUI row hands kmet1's spellings to
`interactive/run`. `@file` landed with it, read at parse time as kmet1 reads
it. What is left is the maintenance flags (they need § 5l-ii's load order), the
extension flags, and the expansion chain (`/skill:`, prompt templates) for the
modes that create through `:agents`.

| kmet1 | kmet2 today | 5n |
|---|---|---|
| `-t`/`--thinking <level>` | — | **landed**: a create-opt the driver already merges per turn |
| `--system-prompt <text\|path>`, `--append-system-prompt <text\|path>` | — | **landed**: a create-opt; the caller's override is what replaces the `:systemPrompt` row's builder. The value is prompt text, or a path to read when it names an existing file — `cfg/get-custom-prompt` / `get-append-system-prompt` already answer that, so the flag hands on what kmet1 hands on. It is free text, so the argument after the flag may start with `-` (kmet1 reads the token either way). Append is repeatable and joins with blank lines |
| `--models <p1,p2>` | — | **landed**: the agent's model scope, which `/scoped-models` already reads. One argument split on commas (`model-resolver/resolve-model-scope`), not a repeated flag: `<pattern …>` in this table is a list inside one value, as kmet1 spells it |
| `--session-dir <path>` | — | **landed**: a config override, as kmet1 — applied **before** the session is resolved, because `-c`'s discovery (`state/find-session`) reads the same config |
| `-d`/`--debug` | — | `(debug/enable!)` **before the first load of any kind**, so the catalog trail is in the log too, not just the world load's; § 5l-ii moves the entry's catalog load behind the flag |
| `--generate-models` | — | the same user-cache pipeline (`ai.model-gen`, `ai.image-gen`), then exit — **before** the world loads, so the run reads the caches it just wrote and no extension host loads for a flag that ends the process |
| `--list-models [filter]` | — | the same table, printed after catalogs **and** extension providers; the filter is a following argument unless it starts with `-` or `@` (kmet1's rule) |
| unknown `--flag`, `--flag value`, `--flag=value` | `unknown option`, exit 1 | collected as `:ext-flags` and installed by the world load, so `(get-flag …)` answers — all three forms, as kmet1: `=` splits, a following non-`-` token is the value, nothing follows means `true` |
| `@file` (repeatable) | refused by name | **landed**: the entry reads the file at parse time, as kmet1 does, so each file's text is a message **in the argument's place** (not ahead of the positionals). An unreadable file is a warning and an empty message, an empty path a warning and no message — kmet1's three outcomes |
| `-c`/`--continue`, `-r`/`--resume`, `--session <id\|prefix\|path>` | refused as `unknown option` | **landed**: parsed by the entry, applied by the TUI row (its mode resolves the session from those keys already, and its row declares `:session-lifecycle`), and refused by name by every mode that opens no session until 10a gives the factory one |
| `config` | works, but resolves a rank-4 namespace | **landed**: `kmet.app.core/run-config` resolves `kmet.ui.tui.config-command` when the command runs (5l-iii's move), so no rank-4 namespace loads for a process that never runs it |

**Landed** (`kmet.app.maintenance` for the two table/cache flags): `-d`,
`--generate-models` and `--list-models` are the entry's, in kmet1's order —
`--debug` on before any load (so the catalog trail is in the log),
`--generate-models` before the world (the run reads the caches it just wrote),
and `--list-models` after the load, inside `run-mode`, because extension
providers are catalog entries only then; it prints and returns without mounting
the mode. The parser collects an unsupported `--flag` in kmet1's three forms
(`=` splits, a following non-`-` token is the value, nothing is `true`) under
`get-flag`'s dash-less name, and `world/load!` installs them. The expansion
chain is each mode's, before its agent sees the text — `packages/expand-input`
in print's send and rpc's prompt/queue — so `/skill:` and prompt templates
expand on both entries. `kmet.tui.fuzzy` moved to `libs` for the table's search
(the `--list-models` output was already byte-identical; app and tui are peers,
so the shared helper lives below both).

**`--model` and `--provider` are not rows above.** The entry parses them (5b)
and now resolves a `--model` pattern itself, before the boot
(`kmet.app.invocation/resolve-overrides`, kmet1's order), so every mode — the
TUI included — receives catalog ids: print/rpc/server hand them to `create!`,
where `resolve-create-opts` takes them again harmlessly (a plain id is a pattern
too, and the factory is also the path for an extension's `create!`); the TUI row
hands them to `interactive/run`. `--provider` is the same option one field over,
so it has no row of its own.

**The modes apply the invocation.** kmet1's `interactive/run` opens with
`(cfg/apply-cli-overrides config opts)`, so every run flag reaches a TUI run;
its session resolution reads `:session`, `:resume` and `:continue` from the same
map. kmet2's hand-off is the same map in kmet1's spellings: `mode-input` puts
the invocation's keys in the mode row's config, the TUI row maps them to
`interactive/run`'s opts (`kmet.ui.tui/run-opts`), and print, rpc and
the server hand `invocation/create-opts` — the run overrides and nothing of the
mode's own — to `:agents`, whose factory applies them over the settings it
loads. That is what makes `kmet --app new --model x` use x in every profile;
before this, the TUI dropped the map and ran the settings' model silently (5d's
review found the same defect in rpc one mode over). The hand-off is held per
mode — `kmet.app.test-invocation` for the resolution and `create-opts`,
`kmet.ui.tui.test-tui` for the TUI's mapping, `test-print` for what the
request actually carries, `test-rpc` for the state it reports — and
`test_front_end_policy`, which already asserts each front end names the loader
and the chain, will hold the expansion half when it lands.

**The expansion is the mode's, and it is one chain.** `packages/expand-input`
(skill commands, then prompt templates) and `packages/load-resources!` are
`app`'s and already exist — 7a-ii made them the caller's options — and kmet1's
front ends name them: print the loader (it expands before it prompts), the TUI
and rpc both. kmet2's modes name neither, so
`kmet --app new -p "/skill:foo"` sends the literal text where `--app old`
expands it. 5n gives every mode the pair kmet1's front ends pass (print expands
before it prompts; rpc and the TUI route it through `:expand-input`). `@file`
is *not* part of this chain — kmet1 reads it in its parser, which is where it
landed above; a mode's expansion is about skills and prompt templates.

**Gate.** `-p @tasks.md` sends the file's contents and `--app old` agrees
(**met**: the parse is `kmet.app.test-core`'s, the request is
`kmet.app.modes.test-print`'s). `-p "/skill:foo"` expands to the skill block.
`--thinking high` and `--system-prompt` reach the run, read back from the
provider request rather than from a threaded map (`kmet.app.modes.test-print`)
— **in the TUI too** (**met** by the run-flag increment: a pty run of
`--model opencode/deepseek-v4-flash:high --thinking high` reports the flag's
model and level where the settings' was `deepseek/deepseek-v4.1-flash • max`,
and `/settings` reads the same config). `--list-models` diffs clean against
`--app old`'s table. `--my-ext-flag x`, `--my-ext-flag=x` and a bare
`--my-ext-flag` all answer through `(get-flag …)` — the three forms kmet1
collects. `--debug` writes `kmet-debug.log` before the first load;
`--generate-models` writes both caches and exits 0; `--session-dir` moves the
run's session file; and `kmet --app new config` loads no rank-4 namespace
(asserted the way the two-direction load test is).

**Not in it.** The session lifecycle beyond opening one — `/new`, `/session`,
`/resume` switching the running agent — waits for 8 and 10a; the *startup*
flags are the TUI row's, which landed with the parse (a mode whose factory opens
no session refuses them by name). `--models` restricts what the selector offers
rather than changing the run's model.

### 8 — the TUI reads an agent handle

The move makes the cluster rank-clean; it does not make it kmet2's. This landing
closes the gap in six places:

- **The handle for the engine.** `create!` for a new session, `resume!` once 10
  gives it something to resume, `send!`/`steer!` for a prompt, `when-idle` for
  the turn boundary, `status` for the status line, `inbox` for pending messages,
  `cancel!` for escape, `dispose!` when a session ends. `:projections` already
  answers the inbox and the boundaries, and the transcript is the log — so what
  disappears is `session.runtime`'s *state* reads, not its accessors.
- **The settings and operations a front end drives.** `/model`, `/thinking` and
  a permission mode write `session.driver`'s engine today, and `/compact`
  (`commands/builtins`) reaches `request-compaction!` on it. A handle owns its
  agent, so the handle grows `set-model!`/`set-thinking!` and the compaction
  pair (`compact!`, `set-auto-compaction!`), each writing the agent's ctx or its
  driver state where the driver reads it per turn — which also lifts rpc mode's
  `:set-model`, `:compact` and `:set-auto-compaction` refusals from 5d, the same
  way 6c lifted the approvals one.
- **Per-agent listeners.** A mode subscribes on `(:parent ctx)` today (5f's note,
  print's `(:parent ctx)`), so a second agent in one process settles the first
  mode's promise. After 8 the subscription is on the agent's own ctx — the
  kernel already routes by subtree, which 6b proved for the server.
- **The prompt a front end sends.** `send!` carries text only, while kmet1's rpc
  started path sends `:run-opts {:images …}` and the loop already takes them
  (`loop.state`'s prompt has an `images` parameter, and `block-images` is a
  config setting). So the handle gains `:images`, rpc's `:images` refusal (5d)
  is lifted, and the TUI's paste path keeps working across the port instead of
  silently dropping what the user pasted.
- **A session switch is an agent lifecycle.** The TUI's `/new`, `/session`,
  `/resume` and fork switch the engine's session in place; a handle cannot. The
  mode's session commands become create/resume/dispose of agents, which is also
  what makes 10a's `resume!` testable from the one front end that has a
  selection UI.
- **The handle's op set is the Engine's front-end surface, measured.** The
  cluster, `commands/builtins` and kmet2's print/rpc name **58** distinct
  `session.driver`/`session.runtime` calls. Readers stay rank 2 — the handle's
  `:agent` *is* the state, so `model`, `provider`, `thinking-level`, `cwd`,
  `session`, `turn-running?`, `compacting?`, `queues`, `tool-table` keep their
  accessors — and every **mutator** becomes an `:agents` op:
  `new-session!` `switch-session!` `fork!` `branch-session!` `clone!`
  `set-session-name!` `export-html!`, `set-model!` `set-thinking-level!`
  `cycle-model!` `cycle-thinking-level!` `set-config!` `set-system-prompt!`
  `set-block-images!` `apply-default-tools!`, `set-steering-mode!`
  `set-follow-up-mode!`, `request-compaction!` `abort-compaction!`
  `queue-compaction-message!` `clear-queues!` `clear-compaction-queue!`,
  `continue!`, and `find-recent-session` for `-c`. That list is the landing's
  scope; a caller still reaching the driver is the mutation the gate looks for.
- **The per-agent half of the extension surface.** `install-session!`, the
  context and entry sinks, `bind-runtime-capabilities!` and `:build-context` are
  kmet1's session runtime (5c's note: a kmet2 run's extension sees `:model nil`
  and `:is-idle` true). **Landed** (`2ce32596`): a run binds `extensions/*agent-context*`
  for its prompt (the handle's live session thunk, its runtime capabilities and
  its context keys), and the host reads that binding before the process slot —
  so an extension event handler, tool hook or custom tool sees the agent it is
  running inside, while a command handler or an extension's own fiber still
  reads the process install. The append sinks stay the front end's (they are a
  UI notification, not an agent fact) until the TUI's per-agent port binds
  them; `bind-runtime-capabilities!` gains the handle's implementation of
  `:inject!`/`send-message!` rather than a second copy.

Gate: a TUI run's agent is in `:agents` (`:agent/created` fired, the dump shows
`[:app :agent/…]`), a `/model` change lands on that agent's ctx and leaves a
sibling agent's alone, two print runs in one process each settle their own
promise, an image in the prompt reaches the provider's request, and `/new`
moves that agent's session while the registry still lists one agent (the dump
shows one line, not two). **Two gate items were not met when the row was marked
done, and T owned them**: the `/new` sentence above now states what the shipped
path does (the handle's session op, not a second agent), and the preset item is
T-3's landing — a mode row's `:presets` reach `create!`'s `:setup` through
`build-layout`, and `:profile tui-ask` mounts the gate on its agent.

**Landed so far** (`479eb19d`, `c6723127`, `2ce32596`): the handle is the value a front
end reads — Engine-shaped (`:agent-state`, `:config`, `:cwd`,
`:turn-running?`, the queue atoms; `:agent` stays the contract's bare agent) —
and its settings ops write the agent they were called with: `set-model!`,
`cycle-model!`, `set-thinking-level!`, `cycle-thinking-level!`,
`set-scoped-models!`, `set-auto-compaction!`, `set-steering-mode!`,
`set-follow-up-mode!`, `set-block-images!`, `set-system-prompt!`,
`apply-default-tools!`, `set-config!`, `set-run-options!`, the two HTTP
timeouts, `clear-queues!`, and the manual-compaction four. `send!` carries
`:images`, and an image send is prompt-only: a queue's is refused by name and
the registry appends no queue fact, so a refusal leaves no undelivered entry.
The manual compaction queue moved to `kmet.app.session.compaction` (below the
driver and the handle alike), so the deferred-start rule has one copy and a
disposed value drops what is queued. Print and rpc subscribe on `(:ctx handle)`
after creating it: two runs in one process settle their own promises, and rpc's
`set-model`/`set-auto-compaction`/`compact` answer with kmet1's wire shapes
(an image prompt reaches the provider; a queue image stays refused).

**Landed** (`5a314785`): the session lifecycle moved below both drivers
(`kmet.app.session.lifecycle`) with `:install!` as the front end's hook, and
the handle answers the session ops the mode's commands need — `new-session!`,
`resume-session!` (load-and-switch; the registry's `resume!` is the different
act of creating an agent for a stored session), `switch-session!`, `fork!`,
`branch-session!`, `clone!`, `set-session-name!`, `export-html!` — listed as
`session-ops`, held to the handle by the drift test. A switch moves one agent:
kmet1's driver still installs into the process-wide extension slot, a handle
does not, and a sibling keeps its session. The handle's `:agent` stays the
record the factory returned — the contract narrowed to "the agent the handle
was created for", with live reads through `runtime` (rpc already reads that
way) — because the state record a switch replaces is the `:session` thunk's,
not the contract's.

**Landed** (`6e3b6ce6`): the TUI's agent is the registry's — `build-layout`
loads the process's resources and creates a handle through `:agents` instead
of bootstrapping an engine — and the run's presentation comes from the loop's
events on that agent's ctx (`:message-update` deltas, `:agent-settled` for the
completed teardown, `:error` for the failure) rather than the runtime's
callbacks; one `events/on-every` subscription per mode, disposed at teardown.
`start-agent-run!` is the handle's `:send!`, the submit/compaction/cancel paths
are its ops, and teardown cancels (waiting on the run's future), disposes the
agent through the registry and disposes the subscription. The loop's
no-API-key refusal now emits `:error` before it settles, so an event-only front
end shows the failure. A pty capture of both apps shows the streamed answer and
the idle footer, and the session log holds exactly `[:user :assistant]`: the
TUI calls the handle's own `:send!`, not the registry's durable-queue op, so a
local prompt records no queue fact.

**8f landed** (`8f712922`, `5e9bbc98`): the TUI's session commands move the
agent through the handle's ops (`:new-session!`, `:resume-session!`,
`:switch-session!`, `:branch-session!`, `:fork!`, `:clone!`, `:cancel!`,
`:request-compaction!`) and, because the handle installs nothing, the front end
re-installs the process-wide extension session after every move — an extension
command handler runs outside a run and must act on the session the user chose.
The front end also publishes its surface on the agent it serves (mode,
`:has-ui`, the UI port, the context/entry sinks) into the handle's `:front-end`
slot; a run binds it, and `ui-capability`, `build-extension-context` and the
append sinks answer with it instead of the process install — wholesale, so a
capability or sink that front end did not install is nil rather than another
front end's — so two agents under two front ends in one process each read their
own while a command handler still reads the front end's install. The shared builtins take either runtime: `/continue`
starts through the handle's `:send!` (so it runs with the agent's extension
binding, which it used to bypass) and `/compact` prefers the handle's
`:request-compaction!`, falling back to `session-driver` for kmet1's engine.

**8g, the gate tests** (`kmet.app.test-front-end-gates`), pins what a front end
may rely on at the composition level: a created agent is a child context of the
ctx the front end mounted on (the `[:app :agent/<id>]` line `--dump-state`
prints) and `:agent/created` announces it; two agents of one composition hold
their own model and thinking level, and a write to one leaves the sibling's
state and the context its handlers would build alone; two runs settle their own
promises on their own contexts; and an image prompt reaches the model as an
image block while a queued image is refused by name. The widget wiring itself
stays the modes' subject — those tests stub the handle. With that, landing 8 is
complete. A `-c`-style continue is `find-recent-session` plus a resume at the
caller, not a handle op: a query is not an agent's state.

**10a, the wire's table.** The first slice is the prompt. A slash line
dispatches through the registry with the command ctx
`{:runtime handle :ui command-ui :frontend :rpc}` — the portable builtins (the
mode registers them now, idempotently, as kmet1's rpc did) and extension
commands — and answers `{:disposition :handled}`; an unknown slash stays a
prompt. The idle prompt is the front end's submission chain, not a direct
send: the extension's input hooks see the text with `:mode :rpc`, the engine
expands it, and the handle starts or queues it. The chain gained
`:on-deliver!` and the handle ops `:record?`, so a caller whose queue is
durable (the wire; the TUI's local prompt is not) records the enqueue fact only
where a message is actually delivered, and the delivery fact follows through
the delivery's run-opts — `get-entries` after a run shows the pair kmet1's rpc
left, which is the residual landing 8 named. The delivery's run-opts also carry
the wire's images: a caller-named queue target refuses an image even while
idle, and a start that loses the race refuses rather than stripping it.
`get-commands` answers the same filtered view the dispatcher uses. The
builtins register idempotently with a thunk that reads the process root (so
`/units` answers the tree the entry mounted, as kmet1's rpc did) and falls back
to the mode's mount scope for a composition that published none. The server row
serves the same command language but has no per-connection command surface yet,
so it calls the same `register-commands!` and refuses a slash command by name
rather than running one with its reply dropped — a per-connection command
surface needs a per-connection `:ui` port, and that is not scheduled (10b was
the boundary drain, 10c is transport hardening). One named residual remains in
the delivery path: a prompt submitted while a compaction runs lands in the
compaction queue, which records no fact — the queue is memory, and recording at
submit time would leave a queue fact the delivery never folds, so whether to
record at the flush is still open.

The table is complete (10a's later slices). The session reads are the runtime's
and the log's (`get-messages`, `get-last-assistant-text`, `get-session-stats`,
`get-tree`, `export-html`), and `get-entries` gained its `:since` narrowing and
the `:leaf-id` a client that forked or switched reads; `get-state` answers
`:compacting?`, `:last-outcome` and the session's `:name`. The moves are the
handle's session ops (`new-session`, `switch-session`, `fork`, `clone`,
`set-session-name`), each re-installing the process-wide extension session on
the rpc row and none doing so on a server connection — one process, many
agents, and a slot a connection must not take; the server test proves a move on
one connection leaves the other agent where it was. The cycles, the levels and
the queue modes are the handle's setters, and `clear-queue` answers the texts it
dropped. `respond` answers nothing (pi writes no record for a valid answer),
which made a silent command a transport concern on both wires; kmet2's
extension ABI carries no host-built dialogs, so no dialog `:ui-request` is
produced and the live `:request`/`:response` pair is the server's approval
route. Two decisions worth keeping: a `steer`/`follow-up` on an idle agent
starts a run under the handle's one start-or-queue rule, so the answer says
`:started` rather than a `:queued` nothing would drain (kmet1 queued and waited
— a client that depended on that should send them only around a live run); and
an rpc row refuses the session-lifecycle flags by name — it opens no session
of its own (the TUI row resolves them, and the registry's `resume!` creates an
agent for a stored session).

### `ui-tk` — deferred to separate development

`kmet.ui.tk` — the four required keys over `kmet.tk`, starting from
`kmet.gui-sample`'s `:ui/transcript` — was landing 5m and is now its own
development, not part of this plan. The design is unchanged and small: the
provider in `modules/ui`, `modules/ui/deps.edn` gaining the rank-2 `kmet/tk`
edge, and one `--patch` file swapping `:ui/terminal` for `:ui/tk` as its gate
(the same session painting on bb and Jolt; the JVM refusing the row by name,
since it has no `babashka.ffi`). It still waits for 8, for the same reason: the
provider must implement the handle-based front end's contract, not the `Engine`
port retirement deletes.

### 9 — the execution seams

The audit, measured after the retirement (the table in § "Multi-session server
mode" was written against the module that is gone, so two of its rows were
stale and one was already true):

| seam | what stands in today | the slice |
|---|---|---|
| `:shell` / `:subprocess` | `bash_executor`'s `defonce shell-options`, applied inside `execute-bash` as a default; written once per process by `config/load-config`, read by the tool (`tools/bash.clj`) and the TUI's `!` path (`ui/…/turn.clj`) | a `:shell` row provides `{:shell-path :command-prefix}`; both call sites read the service and pass the values explicitly (which `execute-bash` already accepts, and pi reads them at tool creation too); the cell dies |
| `:workspace` / `:fs` | **already true**: `kmet.libs.path` resolves against `(:cwd ctx)`, the tool layer threads `*cwd*`, `execute-bash` takes `:cwd` | a verification, not a refactor — **9d** proves two agents in two cwds touch no common file; the remaining `System/getProperty "user.dir"` reads are *defaults* (a temp-file dir, a UI's base path) and get a written reason or a `:cwd` |
| `:sandbox` | nothing: `srce/isolate` exists in the kernel and no app code calls it | **landed at 9c**: `run_code`'s tool bridge mounts in an isolated realm, so the script cannot see the host's `:tools`/`:shell`/`:workspace`; the realm boundary is the `:sandbox` value |
| `:llm` / `:models` | the catalog is data, and a run's model lives on its handle | verification only: two agents on two models, no cell to move — asserted by `kmet.app.test-isolation` |
| `:config` / `:auth` | `config/load-config` is process-global (and is what writes the shell cell); auth is one token cache for one principal | **landed at 10c for the agent's half**: a connection's `:auth` record carries `:cwd` and `:config`, and `create!` takes a `:cwd`, so the config a connection brings belongs to the agent that connection created. `:auth` stays process-wide **by decision** (one principal, documented); the "process-global `load-config`" that remains is the default a context-free caller gets, which is what a process without a front end should have |
| `:modes` | a row since 5j | the server row creates one mode per connection (6b) — nothing to move |

**Three loose cells**, each measured to its setter's callers:

- `run_code`'s bridge queue (`tools/run_code.cljc`) — **settled in 9b, kept**: a
  fixed worker count is a bound, not a setting. Everything an inner call needs
  (cwd, shell, skill resolver, cancel signal, hooks) travels per submission in
  the pipeline's `:bindings`, so two agents sharing the pool still run in their
  own cwd and shell; a pool per agent would multiply the platform threads the
  bound exists to cap, and would have to be disposed with its agent. The reason
  is written at the cell.
- `read`'s skill-content resolver — **done in 9b**, as a binding rather than the
  predicted row config: the run sets `read/*skill-resolver*` from the agent's
  own `:skills` service (`skills/content-resolver`), and `invoke`'s `:bindings`
  carry it to `run_code`'s pool workers. A row config would have pinned every
  agent of a composition to one skills table — the per-agent property the shell
  seam had just established — and the binding also deletes the require cycle
  outright (read never names skills; skills never names read).
- `keybindings`' theme-fns cell — **done in 9b** by derivation: `key-hint` reads
  the live theme at render time (`theme/dim`, `theme/fg (get-current-theme)`),
  so neither `layout.clj` install site is needed and a theme change re-renders
  through the same path it always did.

**What the gate measures.** The landing was measured against `modules/app/src`,
which held 23 `defonce`s; three went at 9a/9b (`bash_executor`'s shell options,
`read`'s resolver, `keybindings`' theme cell), and 20 remain there — plus the
two `modules/ui/src` holds, the one terminal a process runs. The gate is
`kmet.app.test-process-state`, and it is a ledger rather than a count: every
cell is listed with the reason it is the process's, and both directions are
checked — a cell with no reason fails, and a reason whose cell is gone fails
too, so a stale entry cannot let the next cell of that name slip in. It reads
the sources instead of loading them, and fails loudly on a `(defonce` it cannot
read.

**By decision, with the reason written at the cell**: `run_code`'s bridge queue
is a thread *bound*, not a setting — the worker count caps concurrent tool
executions process-wide, and everything per-run travels in the submission's
`:bindings` (9b). **Still scheduled**: `event_bus`'s listener table (10b's
boundary drain, not a row) and `registry.clj`'s process-handle pair (the
fallback a rpc or server connection reads, the `:config`/`:auth` half of the
re-point). Both are in the ledger with those reasons.

**The slices — 9a, 9b, 9c and 9d are landed.** 9a made the shell settings `:shell`: a value
each agent carries on its own ctx, provided by the agents factory from that
agent's config, with `kmet.app.tools.bash/*shell*` bound by the run (and by the
`!` path, and conveyed to `run_code`'s bridge workers through the pipeline's
`:bindings`), so the tool resolves it at execution time. `bash_executor`'s atom
and `set-shell-options!` are gone; `config/load-config` carries the settings and
writes nothing; `/reload` re-seats the value through `shell/refresh!`.

The review of 9a found the regression the landing itself could not see: the
TUI's `!` command calls `execute-bash` **directly** (it is not a tool call), and
its command runs in a `future` — so it used to inherit the process atom and now
inherited nothing, losing a configured `:shell-path`/`:shell-command-prefix` for
every `!` command. It passes the value explicitly now (a future conveys no
dynamic bindings), and `kmet.ui.tui.interactive.test-turn` observes the options
the executor is handed. The run's own binding got a test too
(`kmet.app.loop.test-driver`'s `a-run-binds-its-agents-shell`, observed from
inside the model call): the ctx-value test proved the seam, not the wiring.

The first attempt mounted a root row and failed at the kernel's rule: **a child
may not shadow a service its ancestor provides**, so a root `:shell` could never
be overridden per agent — the same reason `:permissions` mounts per agent and
not at the root. The value moved to the factory, which already has the agent's
ctx and its config; the gate test creates two agents with two prefixes and runs
the tool under each (`kmet.app.tools.test-shell`).


**9b is landed too.** Three cells, three different answers, which is the point
of slicing them together:

- **`read`'s resolver became a binding.** `kmet.app.tools.read/*skill-resolver*`
  is set by each run from the agent's own `:skills` service
  (`kmet.app.skills/content-resolver`, built over the handle), and
  `kmet.app.tools.invoke` conveys it to `run_code`'s pool workers beside the
  cwd/shell/signal. The load-time push and its atom are gone, and with them the
  require cycle the old comment named: `skills` no longer requires `read`, and
  `read` never names `skills`. The predicted shape — the `:tools` row's config —
  would have pinned one composition's skills table onto every agent, the
  per-agent property 9a had just established; the run binds it for the same
  reason it binds the shell. Every link has a test where the value is actually
  in effect: `kmet.app.loop.test-driver` observes the binding from inside the
  model call (and from two compositions at once: one run resolves its own
  registry's skill and not the other composition's), `kmet.app.tools.test-read`
  holds the outside-a-run answer, and `kmet.app.test-run-code` calls `read` on
  an extension locator **from inside a script**, which is the bridge worker —
  a raw thread that conveys no bindings, so the `:bindings` threading is what
  it proves (mutation-checked: dropping the key fails it).
- **The key-hint theme became a derivation.** `key-hint` reads the live theme at
  call time (`theme/dim`, `theme/fg (get-current-theme)`), so the atom and both
  `layout.clj` install sites are deleted; a theme change re-renders the hint
  through the path it already used. `kmet.app.test-keybindings` switches themes
  and asserts the hint's muted color follows, mutation-checked.
- **The bridge queue stayed, on purpose.** A fixed worker count is a bound, not
  a setting: settings travel per submission, and a pool per agent multiplies the
  platform threads the bound exists to cap. The reason is written at the cell
  and the cell moves to the gate's kept list.

**9c is landed: `:sandbox`, and the realm the bridge runs in.** A script is
not a ctx — it is an SCI context with a tool surface — so the boundary around
it is the context its inner tool calls execute against. The seam is `:srce-ctx`
in the tool context: the loop hands every call the run's own ctx, and
`run_code` replaces it with a **realm** (`srce/isolate`) of that ctx blocked
for the names the agent's `:sandbox` value names — `:tools`, `:shell`,
`:workspace` by default (`kmet.app.sandbox`). A tool a script calls therefore
cannot read the composition's table or seams from the ctx it was handed, while
what a script *does* reach — its filtered surface, the run's shell, cwd and
skills — travels in the invocation pipeline's bindings, which is exactly what
9a/9b established them for. The realm is per call and detached when the
script returns; a fire-and-forget inner call keeps resolving, it just stops
being in the tree.

Placement follows the kernel rule 9a learned: a child may not shadow a service
its ancestor provides, so the value is **per agent**, like `:shell` and
`:permissions`. The agents factory provides the default on every agent's ctx
and a preset that mounts the `kmet.app.sandbox/plugin` row on the child ctx
first wins — a preset names a bundle, and a bundle is how a profile says what
one agent's world is. The safe default is the boundary, not no boundary: a
composition that mounted nothing still gives each script a realm, the way the
`:approvals` row's default denies rather than allows.

`kmet.app.test-sandbox` is the gate, mutation-checked at both links: a
`:contextual?` probe called by the loop sees the agent's `:tools`/`:shell`/
`:workspace`, and the same probe called from inside a script sees none of them
— dropping either the loop's `:srce-ctx` or the realm substitution fails it.
The tool context's new key is documented in the extension contract
(`extension.md` § Sandbox realms).

**9d is landed: the verification and the guard.** `kmet.app.test-isolation`
boots the app's own rows and creates two agents with two sessions (two cwds),
two shell prefixes and two models, then runs a turn on each asking for `bash`
(`pwd`) and `read` (a relative `marker.txt`): each agent answered in its own
shell, its own cwd and its own filesystem, and each read its own file. The
`:workspace`/`:fs` and `:llm`/`:models` rows of the audit are exactly this
test — nothing moved for them, and that is the finding. `kmet.app.test-process-state`
is the ledger described above.

Gate: two agents in one process run bash in two cwds with two shells and read
two filesystems (`kmet.app.test-isolation`); `kmet.app.test-process-state`
holds every remaining `defonce` with its reason, both directions, and a new
cell without a sentence fails it; a script's inner tool call resolves no host
service (`kmet.app.test-sandbox`).

### 10 — the server's phase 1

**10a landed before retirement** (it is the rpc parity kmet1's deletion must
not lose); **10b and 10c landed after it**, because they are kmet2's own server
work and nothing in the old app waits on them.

Three commits. **10a, the protocol's whole table**: kmet1's `docs/rpc.md`
declares ~30 commands and kmet2 serves seven, so "the protocol is served" is a
second landing's work, not a follow-up. The remaining ones are reads and
setters over services that exist: `get-messages`, `get-last-assistant-text`,
`get-session-stats`, `export-html`, `get-tree`, `get-commands` (`:sessions`,
`:projections`, `:commands`); `new-session`, `switch-session`, `fork`, `clone`,
`set-session-name` and resume-by-id (the agent record is the session header —
id, cwd, model, preset — which is the log, per 4c-iii-b); `set-model`,
`cycle-model`, `set-thinking-level`, `cycle-thinking-level`,
`get-available-thinking-levels`, `set-steering-mode`, `set-follow-up-mode`
(8's handle ops); `clear-queue` and the compaction pair. Return an image on a
*queue* and stay refused: that is kmet1's own limitation, documented on the
same page. **10c, the transport hardening — landed**: the bearer token (one
principal), a unix socket where the host has one and TCP loopback otherwise,
`--max-sessions`, the per-wire queue depth, idle reaping on
`kmet.libs.concurrent/monotonic-ms`, the fan-out `:lag` policy with a visible
`:stream-gap`, and the config a connection brings (§ "Landing 10c settled").

**10b, the boundary drain — landed.** The loop calls `runtime/boundary!` at
`:agent-settled` (`kmet.app.loop/settle-boundary!`), so the queue has a drain
point and 6b's residual is closed: a tool thread's mount is invisible to the
turn that submitted it and live when the settle event fires, because the drain
runs *before* the emit — `:agent-settled` means both "no turn in flight" and
"no pending refresh". Two points the plan left implicit:

- **The boundary itself is serialized.** Turns run on futures, so two agents in
  one process settle on two threads; two drains at once would mount plugin code
  concurrently, which the pass flag does not prevent (a `plugin!` that loses the
  pass runs its `apply` inline). `boundary!` holds the queue's own atom as a
  monitor, so a second boundary waits: one boundary at a time per root, no new
  state, and the kernel's "one thread runs plugin code" rule holds at the point
  the app decides to apply changes.
- **The server keeps its single thread, and that is now a distinct fact.** A
  connection's `create!`/`dispose!` run on the server thread because the
  protocol answers synchronously (`:ready` names the agent it created), not
  because the queue needs a consumer. The two orderings are independent: the
  boundary sequences what *other* threads submitted, the server thread
  sequences its own commands.

The static half is `kmet.app.test-submit-guard`: the five kernel calls that run
plugin code (`plugin!`, `restart!`, `dispose!`, `dispose-plugin!`,
`dispose-subtree!`) have a ledger of the call sites that are the owner's, with
a reason each, and the tool layer, the mode rows and the UI must contain none.
Both directions fail by name (a new call site, a stale row), and the scan is
over parsed nodes, so a docstring that names a mutator is prose.

Gate: every command in `docs/rpc.md` either answers or is one of the two
documented refusals (queue images, a token-less connection); an agent disposed,
resumed by id and answered; a mount submitted from a tool thread absent
mid-turn and present after it (**met**, `kmet.app.test-composition-run`); two
connections with different cwds, shells and models (**met**,
`kmet.app.modes.test-server`); a client that stops reading gets a `:stream-gap`
and resyncs with `:get-state` (**met**, the same suite); a connection that has
not authenticated never gets an agent (**met**).

### Landing 10c settled

The transport is the row's, and the decisions it made are the ones a client
sees; `docs/serve.md` is the user-facing half.

- **One token, one principal, always.** `--token` names it; absent, the server
generates one and announces it with the port in the `:listening` record on its
own stdout. The hello/`:auth`/`:ready` handshake is the connection's first
three records, and nothing else is served before it: a command first is refused
by name, a wrong token is refused, and either refusal closes the socket. No
agent exists for an unauthenticated connection, so the cap and the reaper
bound even a client that connects and says nothing. `:auth` (the credentials)
stays the process's, which is the phase-1 single-principal decision landing 9's
audit named — not a leftover.
- **The config a connection brings is its agent's.** The auth record may carry
`:cwd`, `:config` (settings merged over the process's) and the run overrides
(`:model`, `:provider`, `:thinking`, …). `:cwd` is a `create!` option now — one
line in the driver's factory, `(or (:cwd opts) cwd)` — and a cwd that is not an
existing directory is refused by name rather than creating an agent whose every
tool call resolves against a path nobody meant. Two connections of one server
run two worlds; the gate's "different cwds, shells and models" is
`a-connection-brings-its-own-world`.
- **The socket is where phase 1 says it is.** TCP is bound to loopback
explicitly (`127.0.0.1`, never every interface), and `--socket` serves the same
protocol on a unix-domain socket. The unix side lives in its own namespace
(`kmet.app.modes.server-unix`) because **loading it is the capability check**:
Jolt has no NIO unix API, so a static require would take the row down there;
the server resolves it at open time and falls back to TCP with a warning.
- **The fan-out is a bounded lane, in the shared layer.**
`kmet.libs.concurrent/bounded-queue` (with `offer!` and `take-dropped!`) is a
new primitive beside `queue`: `offer!` never blocks and drops the **oldest**
record when full, and each take reports how many were dropped since the last
one. The server's `send!` only offers; one writer thread per connection writes,
and emits `{:type :stream-gap :dropped n}` before the next record. Dropping the
newest would corrupt a message silently, and a visible gap is what lets a
client resync with `:get-state` (the gate sentence, pinned by
`a-client-that-stops-reading-gets-a-gap-and-resyncs` with a one-deep lane and a
tiny receive buffer, so the gap is not a timing coincidence).
- **The reaper only asks.** A connection silent past `--idle-timeout` (default
1800s, `0` disables) with no run or compaction in flight is released; the
reaper thread enqueues `{:op :reap}` and the server thread does the closing, so
no second thread ever touches the kernel. `--max-sessions` (default 8, `0` no
cap) refuses at `:open`, before an agent exists.
- **The row is still the row.** `--dump-state` mounts it inert (no `:on-result`,
no socket), the command language is still `kmet.app.modes.rpc`'s, and the
approvals route is unchanged. The `:hello`/`:ready`/`:stream-gap` records are
this row's vocabulary; `docs/rpc.md` keeps the command table the two share.

### 11 — retirement

**Landed** (`5ff5d34d`): `modules/agent` is deleted, the app is `kmet.app.*`,
`kmet.ui.*` is the front-end module, and `--app` is gone. The gate is
`test/kmet/test_retirement.clj`, which allows one `kmet.agent.*` name
(`kmet.agent.ui.tui.tool-renderers`, a shipped extension's require) and nothing
else. What follows is the plan as it was written, then the landed steps.

It waited on a complete 8 and on 10a, and on one mechanical move: the
interactive tests left `modules/agent/test` for `modules/ui/test` in this
landing's first step (the module they lived in was deleted with kmet1).

**Landed so far.** The test move happened with the T landing (T-2), and the
rename pass is three commits: the session tree is `kmet.app.session.*`; the
prompt builder folded into the `:systemPrompt` row that provides it (one
namespace, one test file, since the rename collided with the row's name); and
the other 41 namespaces are `kmet.app.*` under `modules/app/src/kmet/app`, with
their tests moved and renamed alongside, and the lint configs' `:config-in-ns`
entries following. Widening the layer guard from the emptying `kmet.agent` tree
to the whole app tree found four things, all fixed: the loop DAG's `stages`
edge; a namespace-from-path helper that knew only `modules/(agent|app)/src` and
so mangled every `ui`/`libs`/`tui` path; `world`/`maintenance` reading
`kmet.tui.theme`/`fuzzy`, which are rank-0 leaves in `modules/libs` (the rule is
about the module, so it exempts what the libs tree holds rather than a file
list); and the TUI's `/reload` reaching `kmet.app.loop.stages` (now
`kmet.app.session.runtime/refresh-loop-stages!`). Two process-registry tests
publish the composition service they mean for their own duration, because the
new run order reaches them before anything else published a handle. The gate's
first half is a test now — `kmet.test-retirement` refuses any `kmet.agent.*`
namespace in every module's sources except
`kmet.agent.ui.tui.tool-renderers`, the ABI name shipped extensions require —
and the four docstrings that still named the old prefix follow.

The test tree moves in slices. The loop cluster is the first: 13 namespaces
(everything under `kmet/agent/loop/`, including the shared support) moved to
`modules/app/test/kmet/app/loop` and renamed, and its three shim uses went with
them — the tool table is the ctx's `:tools` service (`support/tool-table`), a
run's own stage map is `support/test-ctx`, and the extension host is
`support/with-host`, a scoped binding over a fresh registry handle rather than
the load side effect of requiring kmet1's shim. `test_tools`' stand-ins register
through that handle; `test_tool_policy` keeps a booted composition as its base
because a policy turn needs the real tool table and the ask route.

The rest of the app-code tree followed in one slice: the compaction stage, the
six `extensions/*`, and the plain subjects (bundled extensions, commands,
compaction, event conformance, the extensions UI, keybindings, loop message, the
provider registry, session diagnostics, session runtime) — 17 namespaces. What
it needed is what any namespace that used the shim's `ctx`/`registry` needs:
`kmet.app.test-support/with-composition` (boot the composed app for the
namespace, dispose after) and `composition-ctx` for the root; `registry/current`
for the handle; `kmet.app.registries/deregister-owner!` for the sweep the shim
was the only owner of; and a scoped `with-host` for a bare test that needs the
host's hooks without a composition (the loop's support delegates to it, so one
definition serves both clusters). `kmet.test-utils/with-extension-host` binds
its own host instead of requiring a namespace for the side effect — the
load-order luck the fixture's docstring used to name is gone, and the message a
host throws when it is used unbound no longer claims kmet1 binds it at load.

What is left in `modules/agent/test` (1,848 lines): the four `modes/*` and
`test_app_ctx`/`test_runtime` die with kmet1 and its shim; `kmet.agent.test-tools`
dies with the façade; `test_ui_port` and `test_runtime_capabilities` reach the
composition through `tu/test-engine`, which an app test cannot require (rank 2
below ui's 3), so they take the handle fixture or stay until the endgame.

`test_layer_guard` moved first, to `test/kmet/test-layer-guard`: its subject is
the app and UI source trees, and it was still walking them with its own
copy of the walk. It reads `modules/app/src/kmet/app` through
`source-files-under` (a missing tree yields nothing, and the caller asserts the
roots it means), scans the mode rows beside kmet1's entry modes, and its
assertion that the modes tree was scanned now names the app rows — the old one
would have failed the day kmet1 was deleted. Its docstring and the DAG map's
described the pre-rename namespaces. Then
the `kmet.agent.tools.core` façade collapse, the deletion and the flag flip, and
the documents.

The façade's callers moved first, so that what is left of
`kmet.agent.tools.core` is kmet1's (and dies with it): thirteen files required
it, and none of them needs a process global — five `ai` tests wanted
`make-tool`/`map->Tool` (the record helpers, which live in
`kmet.app.tools.tool`) and one wanted the builtin table,
`kmet.test-utils/tool-registry`'s handle answers; five `ui` tests wanted the
chat history's `:tool-fn`, which `kmet.test-utils/tool-fn` builds over a handle;
`test_run_code` drives its own handle, one per namespace. That repoint also
removed the last load-order luck in those namespaces: requiring the façade
loaded `kmet.agent.app-runtime`, whose **load** binds the extension host to a
thunk that boots kmet1's composition — so a run that asked the host for a hook
got a booted composition instead of the `:kmet/no-host-services` error. Three
tests were passing on that accident (`test-run-code`'s turn and two chat-history
namespaces' render paths); they name their host now (`with-host`,
`with-extension-host`), which is what the T landing's fixture existed for. The
boundary case that required the façade inside the sandbox went with it: the
sandbox's own exclusion is already pinned by `kmet.app.loop`.

**One loose end the endgame left, recorded rather than fixed.**
`scripts/bench/`'s three files (the frame, perf and render harnesses) required
`kmet.agent.modes.interactive` and `kmet.agent.tools.core`; the interactive
cluster moved to `ui` at 5l-iii and the façade died with kmet1, so they did not
load. They are rewritten now against `kmet.ui.tui.interactive` and the
handle-taking registry (`kmet.app.tools.registry` over `kmet.app.registry/atom-registry`),
with the extension host bound via `kmet.test-utils/with-extension-host`; all three
load and run on `bb` again. And the server's user-facing page: `profiles.md` documents
`--profile server`/`--port` and `rpc.md` the protocol, but the connection rules
(one session per connection, the approval route, and 10c's token and
`--max-sessions`) belong on a page of their own once 10c settles them.

The deletion has guard work of its own, and each item is a guard that would
either fail or go silently narrow: `test-front-end-policy`'s table names kmet1's
print and rpc (the handle front ends it also names are the ones that survive);
`test-core`'s CLI section and `test-app-dispatch` are the flag's own tests, so
they go with it; `test-layer-guard`'s `mode-source-files` drops the
`modules/agent/src/kmet/agent/modes` root; and `test-no-global-node`'s ban list
keeps `kmet.agent.app-runtime`, because a name that no longer exists is what the
tripwire is for. `kmet.test-retirement` loses its `agent`-module exception and
becomes the whole-tree rule.

`kmet.app.test-support/with-host` delegates to `kmet.test-utils`'s fixture: the
loop tests and the root tree's bind the same host, and one fixture disposes the
ctx it created.

**The review of the endgame** found one vacuous guard and one real bug in the
repoint, both fixed: `test-seam-requires`'s "kmet.ai never reaches up" filtered
the `kmet.agent` prefix after the app had become `kmet.app`, so it had stopped
seeing anything (mutation-checked); and `test-run-code`'s slow suite passed a
string where the registry belonged, because a mechanical insertion over call
sites skipped the one written across two lines — the error only showed in
`bb test-ext`, which is why the slow suite is a gate and not a convenience.
Two slow tests were stale against the shapes they drive (`:running-turn?` on a
CoreState that carries the flag on its engine; a compaction request whose gate
was never handed to the stub). `kmet.app.root`'s boot-on-first-read — the
deleted shim's entry path — went with its two tests; the publish rule stays.
`kmet.app.event-bus`'s docstring described two tables "for the length of the
migration"; it now says what holds: the ABI's table plus the kernel's ctx
service, both fed by the loop, converging at 10b.
`kmet.app.core` refuses the retired `--app` by name instead of letting its
value become a prompt, and the help text no longer explains flags as "kmet1's
spelling".

**Landed: the endgame.** kmet1's module is gone — its six entry/process files,
its four modes, its tool façade, its eight test files (the 1,530 lines that
tested them) and the `agent` row with its four manifests; the single app's entry
is `kmet.app.core/-main`, with `kmet.app.jvm-main` as the JVM artifact's
`:gen-class` entry, and every packager, the jolt embed list, `deps.edn`'s root
(the module chain now hangs off `kmet/ui`) and the `bb*` tasks name it. The
`--app` flag is gone with the bootstrap that dispatched on it, so `bb start`,
`jolt start` and the artifacts all run the same composition. `test_ui_port` and
`test_runtime_capabilities` moved to `app` rather than dying: they test the
host's `:ui` port and the runtime capabilities, both of which ship. The bundled
manifest moved to `modules/app/src/kmet/bundled-extensions.edn` (a src root, so
it is still a classpath resource in every mode). Twelve tests were passing on a
load side effect of the deleted shim and now name their host. The module-deps
guard drops to 632 declarations.

 kmet1's files
deleted (the 6 entry/process files and its 4 modes), the `agent` module row and
its four manifests removed, `--app` flipped to the default and the flag dropped,
`kmet.agent.tools.core`'s façade arities collapsed into the handle-taking
registry, and `kmet.agent.app-runtime` + `session.driver`'s engine path deleted
with the last caller. The façade's reach is wider than its 93 lines: thirteen
files require it — five of `ai`'s tests, five of `ui`'s, `test_run_code`, the
`test-tools` file that dies with it, and two docstrings in `app`'s source — and
a handle-taking registry needs a handle, so the tool fixture (an
`atom-registry` over the real registry) lands in `kmet.test-utils` where every
module's test tree can bind it. **And the module's two documents move with
the code**: `kmet/README.md` and `agent/development/pi-alignment.md` are cited
five times in `AGENTS.md`'s topic map and rule sections, so a module deletion
that leaves them behind deletes the contributor entry point — they land in
`docs/` (or beside the module that inherits them) and the topic map is updated
in the same change. **And `docs/` gains what kmet2 never had**: no file under
`docs/` mentions `--app` today, so the flip is the first time a user meets the
new default — the landing also writes the profiles/`--patch` page, the server
protocol's user-facing half, and the extension-authoring page for a composition
that boots without kmet1.

Gate: the full set (`bb test`, `bb test-ext`, `bb lint`, `bb format-check`,
`bb check`) plus pi-alignment, and `grep kmet\.agent\.` over `src` returns the
ABI only (a test holds the prefix; `kmet.agent.ui.tui.tool-renderers` is the
one name it keeps). `bb test-ext` is **green** — 135 tests, 696 assertions —
so the gate is claimed against a suite that runs green, not against a baseline:
the 15 failures and 1 error that predated 5i went with the module that carried
their tests.

### Decisions this plan makes

- **The TUI is a `:ui` provider that is also a mode, and it lives in `ui`; no
  `mode` module is created.** Rows name plugins by *source*, resolved through
  `requiring-resolve`, so a rank-2 mode stacks a rank-3 provider without a
  require — the layer map's `mode` row would be a boundary the code can never
  cross (`ui` and `mode` are both rank 3, and equal rank is not "strictly
  lower"), which is a naming decision, not a guard. The three headless modes
  stay `kmet.app.modes.*` in `app` until their file count argues otherwise;
  watch it at 10c, when the server grows its transports.
- **`app` owns the `:ui` contract; `ui` owns the providers.** The contract is a
  small value constructor, the rank-2 mirror of `kmet.app.registry`; a provider
  at 3 reaches down for it, a consumer never needs it because it injects a
  keyword.
- **`:per-agent true` is dropped, not deferred.** A preset composes a per-agent
  world, `setup` shadows a service, `isolate` names a realm — a row flag nobody
  reads is exactly what landing 4b-ii refused to ship for `:inject`. `isolate`
  itself lands at 9, where `:sandbox` genuinely must not inherit.
- **No `:modes` listing registry** until something lists modes: `--dump-config`
  already prints the mode rows, and the `:systemPrompt` row waited for its first
  reader for the same reason.
- **`resume!`'s record is the session header.** The log is the durable record;
  adding an agent store would be a second one, and the server's own rule is
  "sessions are already EDN files; the server adds no new store".

### What this plan does not do

`:ui/web` (needs a second toolkit provider proven first, per the layer rule),
multi-tenancy (one principal in phase 1 is still the decision), the
pi-alignment deferred families (session-format migration, browser transport,
telemetry), and the rewrite of the TUI as view-model + renderer — 5l-iii moves
files and cuts two seams, it does not restructure the interactive loop.

### Drift found while planning

Each item was scheduled against a landing; all are resolved at HEAD (the
sections above record how), and the two statuses below say so explicitly.

- `kmet.ui.shared`'s docstring still says "actual surfaces live in
  `kmet.agent.ui.tui.*`" and names `kmet.agent.modes.test-headless-guard` — they
  are `kmet.ui.tui.*` since 5e. Fixed with 5l-iii.
- 5i's closing line, "what is left is landing 6", is stale: 6 is complete and
  the interactive cluster is now 5l's. The paragraph is kept as history; this
  section is the current answer.
- The landings table's 5 row listed "the tk provider on bb and Jolt still to
  come": with 5m dropped that is not a landing, so the row now says a tk
  provider is separate development (the rpc/TUI parity half is 5l-iii).
- `kmet.app.front-end`'s docstring named `kmet.app.extensions.core` as the
  home of the port's capability map; 5k moved the docstring with the re-point,
  and the base a run binds is the `:ui` provider's `:port` now.
- **`--app new --profile print|rpc|server` runs with no extensions loaded.**
  Not a doc drift but a behavioural one: `load-world!` is called only by the TUI
  row (and kmet1's `-main`), so the three headless profiles have no hooks, no
  contributed tools and no `get-flag`, where kmet1 loads the world for every
  mode. No test caught it because every mode test stubs its model and none
  installs an extension. Scheduled with 5l-ii (the load moves to the entry).
- **`kmet config` resolves a rank-4 namespace.** `kmet.app.core/run-config` does
  `(requiring-resolve 'kmet.agent.modes.config-command/run-config!)`, so
  retirement would take the command with it even though the screen it hosts is
  already `kmet.ui.tui.resource-config`. Scheduled with 5l-iii.
- **The `Engine`'s session lifecycle is invisible to the design.** `/new`,
  `/session`, `/resume` and fork are one engine switching sessions in place;
  `:agents` has no equivalent, so 8 has to decide that a switch is a
  create/resume/dispose — a decision the doc's `:agents` catalog does not make.
- **The TUI drops the invocation's run options.** `mode-input` hands a mode row
  `:model`/`:provider` (and 5n adds the rest), print and rpc pass them on, and
  `modes/tui.clj` reads `:on-result` and nothing else — so `kmet --app new
  --model x` runs the settings' model. Same defect shape as 5d's rpc review,
  one mode over, and no test caught it because no mode test asserts the
  *invocation's* model. Scheduled with 5n.
- **"rpc mode serves the protocol" was one third true.** kmet2 answers seven of
  `docs/rpc.md`'s ~30 commands (`prompt`, `steer`, `follow-up`, `abort`,
  `get-state`, `get-entries`, `get-available-models`); the other twenty-odd are
  named refusals pointing at landings that were never scoped to them. 10a now
  owns the table, and its gate reads "every command answers or is a documented
  refusal" rather than "resume works".
- **The handle's op list was a registry's, not a front end's.** The cluster,
  `commands/builtins` and kmet2's print/rpc name 58 distinct driver/runtime
  calls; the catalog's 14 ops cover the registry half. 8 measured the rest
  (mutators become ops, readers stay rank-2 accessors over `(:agent handle)`).
- **Three documentation paths pointed at files that moved.** `docs/rpc.md`
  named `modules/agent/src/kmet/modes/rpc_client.clj`, `tui.md` named
  `modules/agent/src/kmet/pi-alignment.md`, and this file's three-way-split
  table still had the extension host under `agent` — all three lost their
  `agent/` segment when 4b-ii-b and 5e moved the files. Fixed here; the scan
  that finds them (every `modules/*/src/…` path in every `.md`, stat'ed) is
  cheap enough to be a task if it grows again.
- **Retirement deletes the contributor entry point.** `AGENTS.md` cited
  `modules/agent/src/kmet/README.md` three times and
  `agent/development/pi-alignment.md` twice; both lived in the module landing
  11 removes. **Done at 11:** the docs moved, and the topic map points at
  `docs/architecture.md` / `new-app.md` instead.

- **Fixed in 9b: `kmet.app.skills` used to push its resolver into the read tool
  at load time.** `skills.clj` ended with `(read/set-skill-content-resolver! …)`
  and a comment offering the require cycle as the reason, so requiring
  `kmet.app.skills` for any reason — nine namespaces do, including `registries`
  and the TUI's `layout` — rewired the process's read tool, and a composition
  that never loaded skills still read the first one's table: the retired
  `kmet.agent.app-runtime` defect at a smaller size. 9b removed the push and the
  cell with it: the resolver is `read/*skill-resolver*`, bound by each run from
  the agent's own `:skills` service and conveyed to `run_code`'s workers through
  the pipeline's `:bindings`. Its test names the seam twice over
  (`kmet.app.loop.test-driver` observes the binding from inside the model call;
  `kmet.app.tools.test-read` holds the not-a-file answer outside a run), and 9d's
  process-state test can hold the cell's absence.

## Everything is a plugin: the five remaining landings

The composition can already replace the loop, tools, sessions, model access, the
process world, the four modes, the UI, the permission policy, approvals, the
prompt builder, the event bus and the extension host — every one of them a row,
and the generated list is [`docs/composition.md`](docs/composition.md). § Service
catalog above is the target table; what follows audits what is **not** a row at
HEAD — now only that catalog's `:models`, `:config` and `:auth` rows, which stay
libraries on purpose (the model catalog is files, credentials are read before a
ctx exists).

| # | Landing | State | Decides |
|---|---|---|---|
| 1 | `:compaction`, `:world`, loop stages | **landed** | `:world` as boot work or as a row — decided by the row: `:defer`, mounted by the entry with the invocation |
| 2 | `:llm` | **landed** | per-agent vs per-call — decided per call: the selection already rides the call |
| 3 | extension-contributed `:rows` | **landed** | the extension's rows, not the run's — decided by the kernel's bind-once rule |
| 4 | `:toolExecutor` | open | native as default, PTC as a bundle; worth doing when a second executor exists |
| 5 | composition editor + status | catalog guard **landed**; the tool is open | live recomposition or next start |

Landing 4 waits on a second executor to plug in; landing 5's tool waits on the
live-recomposition decision.

### 1. Rows for the remaining call sites

**Landed: the loop's stages.** `:retry-policy` and `:loop-guard` are rows
(`kmet.app.loop.stages/retry-policy-row` and `…/loop-guard-row`) in
`:bundle/minimal`, each providing its stage as a service into the mount site's
scope, so the loop and every agent ctx read what the composition mounted — a
profile replaces the guard or the retry policy by naming another plugin under
the same id, or removes the row. `stages/base` composes the services over the
builtins (a bare or nil ctx still reads `defaults`); `for-ctx` keeps its
contract — a run's own `:loop-stages` map wins *whole*, which is the subagent
override path — and `refresh!` re-seats from the services instead of the
builtin map. A row's `:config` is its stage's config, so a composition may set
the guard threshold or the retry budget without a settings file (the stage wins
where it speaks; a run's cfg fills what it leaves unset). What the same seam
still owes: `permissions/gate` is a call site in the loop, not a hook a row
installs.

**Landed: compaction.** `:compaction` is a service and the `:compaction` row
provides the builtin stage implementation; the four callers — the loop, the
session's manual compaction, the driver's compact command and the branch
summary — reach whatever is mounted, and a state outside a composition falls
back to the builtin, as the stage rows do. The row takes no `:config` (the
budgets are the `:compact-*` settings keys) and refuses one rather than
ignoring it. Unlike the stages, the contract is its own namespace
(`kmet.app.compaction` re-exports the eight operations) instead of a dispatcher
inside the implementation: that file is 700 lines of compaction logic with four
callers in three namespaces, so the seam is the namespace that names it.

**Landed: the process world, as a deferred row.** `:world` is a row in
`:bundle/minimal` (`kmet.app.world/plugin`) and it provides the `:world`
service — `{:config …}`, what the load answered. Two facts shaped it. First, it
must not run on the paths that only inspect (`--dump-config` never boots;
`--dump-state` boots a composition and asserts a quiet stderr), and a test that
boots the composition for its rows must not read the developer's settings.
Second, it needs the invocation, and only the entry has that. So a row declares
**`:defer`**, and `runtime/boot` treats that as its default deferral rule: the
row is composed but not mounted, and comes back in `:deferred`. The entry's run
path (`run-mode`) then mounts it with `{:invocation opts}` as the row's config —
its `apply` calls `world/load!` through the var, so the tests that stub the load
still intercept it — and reads `(:config (srce/get ctx :world))` to build the
mode's input. `mount-deferred!` grew the pick predicate that makes "the world
first, then the mode" expressible; there are three mounts in order now: the
composition's rows, the world with the invocation, the mode whose `serve`
starts the run.

What the deferral settles at the edges: `--dump-state` mounts every row
**except** the `:defer` ones and prints a trailing `deferred: :world` line, so
the tree explains why it is a row shorter than `--dump-config`; a profile with
no mode row (`boot-and-report`) boots without a world, because a boot with
nothing to run has nothing to read the machine's settings for, and the report
names the `deferred` row; and a run whose composition patched the row out
refuses by name (`:kmet.app/no-world`) instead of starting with no catalogs or
settings. The row mounts at the root only (`:kmet.app/world-not-root`):
`:bundle/minimal` is nameable as a preset, and a process world inside one agent
is not a world.

Gate: `--dump-config` names the new rows (and `test-core`'s row counts move
with the composition), a stage with no row mounted reads its builtin — the
patch-out path, held by `test-stages` with nothing provided — and the rest is
`kmet.app.loop.test-stages`: the defaults, the services-over-builtins merge, a
sibling row reading the stage from the shared scope, a run's own map still
winning whole, and a non-map `:config` refused rather than ignored.
`test-composition-run` adds the end-to-end half: a booted agent's ctx reads the
stage the row provided.

### 2. `:llm` — model access as a service — landed

`:llm` is a service and the `:llm` row provides it. The contract is
`kmet.app.llm` (one operation, `send-message`, plus the row); the builtin is
`kmet.ai.llm/send-message`, and the **two** callers — the loop's completion and
the compaction summarizer — resolve it through the agent's ctx, so a state
outside a composition falls back to the library exactly as compaction does. It
is a small landing because the request was already one function taking one opts
map: no `stream`/`route` split was needed, and `kmet.ai.models` stays a library
(the catalog is files; a provider is registered with
`register-model-provider!`, which is a registry concern, not the request).

The planned decision was resolved by the code: **per call, not per-agent**. The
provider, model, thinking level and endpoints already ride the call's opts and
the agent state, so the service carries no selection state and a mounted
implementation never tracks it — the alternative (a per-agent provider service)
would have been a scope question the kernel does not allow anyway, since the
composition provides `:llm` once for the whole tree.

One detail worth keeping: the row's value calls the library **through its var**
rather than capturing the fn, so a reloaded library (or a test's `with-redefs`)
reaches the next request — the tests that stub `kmet.ai.llm/send-message` kept
working unchanged, which is the sign the seam is in the right place.

### 3. Extension-contributed rows — landed, and narrower than planned

An extension **is** a plugin (`:ext/<id>` row) and can validly provide a
service (`kmet.srce.core` is shared by reference, and `mount-plugin!`'s `assoc`
carries `:provide` through). It now also mounts **its own** rows: `:rows` in
the plugin map, one row per entry, mounted as children of the extension's fiber
right after `:apply` — so `:inject` waiting, `:config`, effects and disposal all
work, and unloading disposes them in reverse order.

This note originally called them "rows an extension contributes to the run".
The kernel says otherwise, and the code follows the kernel. A row mounted under
the extension's ctx provides *into that ctx*: its service is visible to the
extension's subtree, not to the app, because the app-wide services belong to
the profile's composition and `provide!` refuses a name already held on the
chain (`:srce/duplicate-service`). So an extension still cannot add a mode
(`mode-row` scans composition rows before boot), replace `:ui`, or join the
run's row list. What it gains is composing **itself** out of rows instead of a
hand-written `srce/plugin!`.

The load-time half is the contract: an `:ext/…` source (an extension cannot
compose another installed extension), a `:mode` flag, a non-keyword `:id`, a
key outside `{:id :plugin :config}`, and a row that never reaches `:active` all
fail the load with the reason, rather than half-mounting. `presets.clj`'s
`check-extension-rows!` still refuses an `:ext/…` row in a per-agent preset: the
extension still mounts app-wide; only its rows are the extension's own.

### 4. `:toolExecutor` — native and PTC as rows

`run_code` is an ordinary tool with a script bridge and the loop always
executes natively, so the execution strategy is not composable. dsh makes it
two packages and reserves the name: `run_code` there is not a tool but the PTC
mode's presentation transport.

The landing: a `:toolExecutor` service whose contract is the one
`tools/invoke` already implements (`prepare` → `execute` → `finish`), with
today's parallel batches as `:bundle/native` (the default) and a `:bundle/ptc`
row that mounts `run_code` as the only model-facing transport and bridges the
rest. `select-tools` stays the selection seam for both, so nothing about
tool tables, rows or `:default-tools` changes; the reserved-name rule arrives
with PTC.

### 5. The composition editor, and the vocabulary to report it

Composition is edited by humans — the `kmet config` process and the patch files
— and has no model-facing surface. dsh's `plugin_manager` edits the same kind
of profile and reports `applied` or `restart-required` with a reason and a
`nextStep` sentence; live application is HMR's, not the tool's.

**Landed: the catalog guard.** `docs/composition.md` is generated by
`kmet.app.composition-gen` — bundles and profiles from the built-in table
described through `kmet.app.catalog`, the tool section from a real minimal boot,
so a tool's model-facing text is read rather than copied — and
`bb generate-catalog` / `bb check-catalog` write and verify it (`bb test-changed`
runs the same check, so a stale page cannot pass). It replaced three
hand-maintained copies of the same fact: `docs/profiles.md`'s row snippet
(`rows=14` for a 22-row composition), `test-profile`'s row-by-row literal (now
an invariant derived from the bundle), and the settings layer's built-in tool
names, which `registry/bundle-tool-names` had already made derived.

**Open: the composition tool.** A `:tool/kmet_manage`-shaped row over the writers
  that already exist (`kmet.app.extensions.layers`, `kmet.app.tools.config`,
  `kmet.app.packages-cli`), returning `:applied`/`:restart-required` with the
  reason, permission-classed like `plugin_manager` (danger-full-access or an
  approval for the call).

The decision it inherits: **live recomposition**. Diffing the new row list
against mounted fibers and mounting/disposing the delta is dsh's HMR path, not
a flag — kernel-shaped, and worth scoping first to leaf rows (`:ext/*`,
`:tool/*`) whose disposal no dependent re-resolves. Without it the tool answers
`:restart-required` on every enable, which is dsh's no-HMR behavior and an
honest place to stop.

## The event bus: one table

**Landed.** The app's process-level listener table (`kmet.app.event-bus`) is
gone; the extension ABI registers on the host's ctx — the app root, already the
scope an extension's `:inject` mounts under — and every emission dispatches
through `kmet.app.events/emit!`, which picks the fold from the event's declared
`:mode`. The vocabulary moved from re-export to a registry (below) and the wire
projections are `kmet.app.wire`.

What the split was hiding, and the landing fixed:

- `:session-before-compact` was declared `:emit` while `ask-before-compact!`
  read its return value — the app table ignored modes, so the declaration was
  never dispatched by. It is `:collect-last` now, and `emit!` refuses a mode the
  kernel does not implement.
- An emission must name a ctx (`:kmet.app/bad-event-ctx`); the six
  process-wide emissions (the provider bridges, `discover-resources!`) take the
  request's or the run's ctx, so the events stay in the subtree they belong to.
- `loop.state/emit`'s two-copy agent_end split (`:will-retry` for the front end,
  a bare copy for extensions) is one emission: one table carries one copy.
  `loop/emit-boundary` is `state/emit` — the boundary mode is the declaration's
  job, not a second dispatcher.
- Extension-only events are a vocabulary property now (`:public false`):
  `:context` / `:context-with-system` / `:before-provider-request` /
  `:before-provider-headers` / `:after-provider-response` /
  `:resources-discover` and the four `:session-before-*` hooks. Unifying the
  tables had put the provider bridges — a full request payload and the
  `Authorization` header among them — on the public stream that print-edn,
  rpc and server write, and the before-hooks carry an abort atom that is not
  EDN. `on-every` is the public stream and skips them; `wire-event` also drops
  `:signal` as a belt. The wire gained the app's session lifecycle events,
  which `docs/rpc.md` already promised.
- The vocabulary is a registry, like `:commands`: a plugin's `:events` map is
  declared at mount (the `:events` service is the handle, `:kmet/event` the
  tag), so `kmet.app.agents`' `:agent/created` dispatches by its own
  declaration and an extension's events are swept on unload with its owner.
  `emit!` reads one declaration from it; `on-every` reads the public subset.
  A row's declaration is an effect of the mounting fiber, so disposing the
  mount (a preset's agent, `dispose-plugin!`) takes its vocabulary with it;
  `on-every`'s set is fixed at subscription time.
- The projection namespace is `kmet.app.wire` (`wire-event`/`wire-error`):
  `kmet.app.event-bus` had stopped being a bus and is gone.
- Tests name the ctx they subscribe on (`events/on!`), and the old
  `clear-event-listeners!` reset is `clear-listeners! ctx`.

## Gates

- `bb test`, `bb test-ext`, `bb lint` (zero findings), `bb format-check`,
  `bb check` — unchanged, plus `bb test-changed` / `bb lint-changed` /
  `bb format-check-changed` while iterating.
- `kmet.test-module-deps` — ranks stay strictly downward.
- `kmet.test-modules-manifest` — `modules.edn`, `bb.edn`, `deps.edn` and the
  embed list stay in step.
- New: the **module-based require guard** (a namespace may require only a
  strictly lower-ranked module's file — built on `kmet.test-seam-requires`'s
  ns-form reader and `kmet.test-module-deps`'s ranks), the no-global-node guard
  (`runtime/node` and kmet1's store names banned in `modules/srce/src`,
  `modules/app/src` and `modules/ui/src`), and the front-end policy guard
  (`kmet.test-front-end-policy`: a handle front end names the process's resource
  policy and never reaches the engine constructor).
- New: the **submission point** (landing 2) — a change made on another thread
  reaches the kernel only through `kmet.app.runtime/submit!` and is applied by
  `boundary!`, asserted end to end (a listener registered and a plugin mounted
  from a `future`, invisible before the boundary, live after it), with the
  drain's order and its throw isolation. The **submit guard** proper landed at
  10b: `kmet.app.test-submit-guard` holds every structural kernel call site to a
  written owner-side reason and refuses one in the tool layer, the mode rows or
  the UI, in both directions; `kmet.app.test-runtime` adds the one-boundary-at-
  a-time check (a second boundary cannot start its thunk while the first holds
  one open) and `kmet.app.test-composition-run` the end-to-end one (a tool
  thread's mount absent mid-turn, present at `:agent-settled`). The kernel's own
  half — two threads providing at once run one pass owner and `settle!` applies
  the other's change — is `two-threads-provide-at-once` in
  `modules/srce/test/kmet/srce/test_kernel.clj`.
- The kernel's own gates — the contract tests, srce self-containment, and the
  no-`scope` require ban — are in [`srce.md`](modules/srce/src/kmet/srce/srce.md).
- The server's transport rules are a test, not a promise
  (10c): `kmet.app.modes.test-server` holds the token handshake, the session
  cap, the idle reaper, the `:stream-gap` a stalled client sees, the unix/TCP
  transports, and two connections running two worlds (`:cwd`/`:config`);
  `kmet.libs.test-concurrent` holds the drop-oldest lane's contract (capacity,
  drop-oldest, the dropped count per take, close).
- New: a profile test — layers patch by row id, `--dump-config` resolves to
  the same rows the boot uses, and an unknown plugin source fails at load.
- **Retired with the kmet1 entry: the two-direction load test.** It asserted
  that `kmet --app new --version` loads kmet2 without any kmet1 *runtime*
  namespace, that `kmet --app old --version` loads kmet1's runtime (and
  `kmet.app.*`, which kmet1 runs on since 4c-ii-a — the old direction asserted
  that module stayed unloaded, which stopped being true then and the slow guard
  went red un-noticed), and that `kmet.agent.core`'s ns form requires neither
  app. It existed to keep someone from turning the dispatch into a static
  `:require`; with the dispatch gone, `kmet.test-retirement` holds the surviving
  half (no `kmet.agent.*` outside the ABI) and the artifact smoke test runs the
  one entry.

## Not doing

- Forking the repo, or rewriting `kmet.libs.*` / `kmet.ai.*` / `kmet.tui.*` /
  `kmet.tk.*` / `kmet.loader.*`.
- Multi-user accounts, quotas, or an ACL model in phase 1.
- Renaming `kmet.agent.*` namespaces at lift time, or inventing intermediate
  prefixes (`kmet.shared.*`, `kmet.services.*`): every rename is paid once more
  at retirement, and the last one is meant to be a `sed`.
- A web UI before a second toolkit provider has proven the capability contract.
- Deleting kmet1's `kmet.agent.*` (done: landing 11, `5ff5d34d`).
- The kernel's own exclusions (JS syntax, the loader/HMR/console packages, the
  plugin market, async stack splicing) are listed in [`srce.md`](modules/srce/src/kmet/srce/srce.md).
