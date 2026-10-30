# kmet developer guide

This file is the contributor's map: the package layout, the composition data,
the service catalog, the layering rules and the workflow.
Implementation-specific references live beside their namespaces rather than in
the user-facing `docs/` directory: see
[`modules/tui/src/kmet/tui/tui.md`](../modules/tui/src/kmet/tui/tui.md),
[`modules/loader/src/kmet/loader/loader.md`](../modules/loader/src/kmet/loader/loader.md),
[`modules/srce/src/kmet/srce/srce.md`](../modules/srce/src/kmet/srce/srce.md),
[`modules/app/src/kmet/extension.md`](../modules/app/src/kmet/extension.md), and
[`pi-alignment.md`](pi-alignment.md). The generated composition catalog is
[`composition.md`](composition.md); the app's design record — the open seams,
the limits that shipped and the options declined — is
[`new-app.md`](../new-app.md); this file is the current map. Temporary analysis
notes (`perf.md`, `jolt-bugs.md`, `tcl.md`) remain at the
repository root by convention.

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
├── profile.clj         — profile → row layers, patches, rendering (data only)
├── profiles.edn        — the built-in bundle + profile table
├── catalog.clj         — rows as cards (`--dump-config`)
├── composition_gen.clj — `docs/composition.md`'s generator
├── presets.clj         — a bundle mounted under one agent's child ctx
├── registries.clj      — the registry row plugins and the router (one handle
│                         per tag; `process-handle` is the published one)
├── registry.clj        — the handle itself (tag + name over an atom)
├── agents.clj          — the `:agents` service: the handle factory + ops
├── extensions/         — the extension host: core (the bound services, the
│                         api, the registry tags), context (per-extension SCI),
│                         libs (the bundled set), bundled.clj (artifact
│                         discovery), rows.clj (an `:ext/<id>` source),
│                         layers.clj (the ambient patch.edn layers), and the UI
│                         request/respond pair
├── session/            — the log (core, store, listing, context, stats, tree,
│                         schema, export, backend), the runtime
│                         (`runtime.clj`: state, lifecycle, submission chain,
│                         queues, compaction, subscribers), the compaction
│                         queue and summary, and the shared lifecycle
├── sessions.clj        — the `:sessions` row (the log over its backend)
├── loop/               — the agent loop: message, state, completion,
│                         tool_exec, the stages, and `driver.clj`/`stages.clj`
├── compaction/         — the compaction row and stage
├── tools/              — tool, util, invoke, registry, bash (+executor,
│                         output), read, write, edit, run_code, config, shell
├── modes/              — the mode rows: print, rpc, server (+ server_unix), out
├── packages.clj, packages_cli.clj, git_packages.clj — package/resource
│                         resolution and the install/remove/update/list/config CLI
├── commands/           — the slash-command registry + the portable builtins
├── prompts.clj, skills.clj, skill_block.clj, system_prompt.clj — the resource
│                         registries and the prompt builder
├── events.clj, wire.clj, projections.clj — the event vocabulary, its wire
│                         projection, and the derived session state
├── permissions.clj     — the `:permissions` policy and `:approvals` seam
├── sandbox.clj         — the `:sandbox` realm a script's tool calls run in
├── llm.clj, ui.clj     — the `:llm` and `:ui` contracts
├── config.clj, config_schema.clj, project_context.clj — settings, their
│                         schema, and the project context
├── world.clj           — the process world: `load!` (catalogs, config, themes,
│                         CLI flags) and the deferred `:world` row that mounts it
├── root.clj            — the one process-root atom and the publish rule
├── front_end.clj       — the front end's published surface
├── inspect.clj         — `/units` and the ctx-tree dump
├── model_resolver.clj  — model patterns → catalog ids
├── keybindings.clj, retry.clj, loop_guard.clj — the key-hint table and the
│                         loop's two stage leaves
└── version.clj         — the version string and the source-run marker
```

Each row names its own file (`tools.clj`, `sessions.clj`, `extensions.clj`,
`agents.clj`, …): a row is a `srce` plugin whose `apply` provides its service,
and `registries.clj` is the router behind them. How the rows are composed is
§ Composition below; [`new-app.md`](../new-app.md) keeps the design record and
the items that did not ship.

### The front end (`modules/app/src/kmet/ui`)

```
kmet/ui/
├── tui.clj             — the TUI provider + mode row (one artifact: the
│                         `:ui` surface and its `:mode` entry)
├── headless.clj        — the `:ui/headless` provider (the ABI's own answers)
├── shared.clj          — the helpers the front ends share
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

## Composition

The app is assembled from EDN composition data, not code. `kmet/app/profiles.edn`
is the built-in table of **bundles** (reusable rows plus patches) and
**profiles** (the app entry: bundles stacked in order, then its own rows and
patches); `kmet.app.profile` resolves and patches it — data only, it never loads
a plugin or reads a process value; `kmet.app.catalog` describes rows as cards
for `--dump-config`; and `kmet.app.core` mounts the composed result. The
generated catalog of what the shipped profiles mount, rows and model-facing tool
text included, is [`composition.md`](composition.md) (`bb generate-catalog` /
`bb check-catalog`; `bb test-changed` runs the check, so a stale page cannot
pass).

- A **row** names a plugin **source** — a var (`kmet.app.tools.bash/plugin`) for
  a built-in, or `:ext/<name>` for an installed extension, resolved by
  `kmet.app.extensions.rows` before composition — plus its config:
  `{:id :tool/bash :plugin kmet.app.tools.bash/plugin :config {}}`. `:id` is the
  identity a patch targets and `--dump-config` prints; it defaults to the
  plugin's `:name`. A built-in tool is a row, so the model's table is what the
  composition mounted and `{:id :tool/bash :remove true}` is how a profile (or a
  preset) runs without bash.
- A **patch** targets one row: `{:id … :config {…}}` deep-merges the row's
  config, `{:id … :remove true}` drops it, and an unknown `:id` that carries a
  `:plugin` inserts a row (positioned by `:after`/`:before`, else appended). A
  patch that names an unknown row without a plugin is an error, and so is a
  duplicate id across stacked layers. Configuration *values* deep-merge;
  composition does not.
- Layers apply in order: the built-in table < `~/.kmet/agent/patch.edn` <
  `.kmet/patch.edn` < `--patch FILE` (repeatable). `--dump-default-config`
  prints the built-in table alone, and `kmet config` edits the two ambient
  layers through `kmet.app.extensions.layers`. A profile still comes only from
  the built-in table: there is no named home profile and no project-shipped
  profile table. (dsh applies its home-level patch *after* the profile's own
  rows and has no project layer; kmet puts both ambient layers before the
  profile's own rows and the `--patch` files, the usual project-over-user
  order.)
- Four row keys are not composition data. `:mode true` marks the row the entry
  runs; `:prompt true` makes the command line's remaining arguments the mode's
  input (an empty one is a usage error); `:session-lifecycle true` says the mode
  resolves `-c`/`-r`/`--session` itself; `:defer true` says a generic boot must
  not mount the row. `:defer` exists for rows only the entry can mount: the
  process world is the one today, so `runtime/boot` returns it in `:deferred`,
  `run-mode` mounts it with the invocation before the mode, and `--dump-state`
  reports `deferred: :world` instead of reading the machine's settings.
- A **preset** mounts a bundle under one agent's child ctx.
  `:agents/create!`'s `:setup` seam is the mount point, a mode row names its
  presets in `:config` (`{:presets [:bundle/gated]}`, `:profile tui-ask`), and
  the rows must be active when the mount settles: a preset that composes but
  does not mount throws before the agent exists and unwinds what did mount.
- Extensions are composition-only: a run loads exactly the `:ext/<id>` rows its
  composition names (`kmet.app.world` loads none from settings), the row's
  config arrives as `(:config api)`, and `/reload` unmounts and remounts each
  mounted row's extension with the same config without re-reading the
  composition. An extension's own `:rows` mount as children of its fiber, so
  they provide into that ctx only; a preset refuses an `:ext/…` row, which is
  why an `:ext/…` row belongs to a root profile rather than an agent preset.

## Service catalog

The application is its services: one key per seam, one implementation per
scope. A plugin writes `inject [:tools]`, never `[:registries]`, so a per-agent
override is per-seam rather than all-or-nothing. A service value is mostly a map
of fns, so a malli schema could only assert `fn?`: the catalog entry plus one
contract test per service is the check that pays (plugin *config* is still
schema-validated at load).

| Service | Value | Provider |
|---|---|---|
| `:world` | `{:config …}` — catalogs, models layer, settings, themes and extension flags as loaded | `kmet.app.world/plugin` (`:defer`, root only) |
| `:llm` | `{:send-message (fn [opts])}` | `kmet.app.llm/plugin`; builtin `kmet.ai.llm/send-message` |
| `:tools` | a `Registry` handle (tag + name) | `kmet.app.tools/plugin`; each tool row registers through it |
| `:commands` `:prompts` `:skills` | a `Registry` handle each | `kmet.app.registries/{commands,prompts,skills}` |
| `:events` | the event vocabulary registry | `kmet.app.events/plugin` |
| `:systemPrompt` | ordered sections assembled over `:skills` | `kmet.app.system-prompt/plugin` |
| `:sessionPersistence` | `{:create! :load :paths :append! :entries :delete! :header}` | `kmet.app.session.backend/plugin` (EDNL files) |
| `:sessions` | the log over the backend, plus the id index | `kmet.app.sessions/plugin` |
| `:projections` | derived state over the log: inbox, boundaries, token meter | `kmet.app.projections/plugin` |
| `:agents` | live handles, the factory seam, the send/control surface | `kmet.app.agents/plugin` |
| `:agentLoop` | the driver; registers the `:agents` factory | `kmet.app.loop.driver/plugin` |
| `:retry-policy` `:loop-guard` | the loop's stages | `kmet.app.loop.stages/{retry-policy-row,loop-guard-row}` |
| `:compaction` | the compaction stage's operations | `kmet.app.compaction/plugin` |
| `:permissions` | `{:policy :remember!}` — the tool-call decision | `kmet.app.permissions/permissions`, per agent |
| `:approvals` | `{:ask!}` — the one `:ask` delivery seam | `kmet.app.permissions/approvals` at the root; the TUI and server rows supply their own |
| `:shell` `:sandbox` | the agent's shell options; the realm names a script's tool calls are blocked from | provided per agent by the agents factory; a preset row that mounts first wins |
| `:ui` | the capability service (`kmet.app.ui`) | `kmet.ui.headless/plugin`, `kmet.ui.tui/provider-plugin` |
| `:extensions` | no service: the row binds the host's services (registry handle, ctx, resource ops, owner sweep) and publishes `process-handle` | `kmet.app.extensions/plugin` |

The `:llm` value stays one operation: `kmet.app.llm/send-message` attaches the
app's convertToLlm (`kmet.app.loop.message/to-llm-messages`) to each request as
`:convert-to-llm`, so the conversion is the app's, `kmet.ai` is handed
provider-neutral messages after the context hook (pi's emitContext →
convertToLlm order), and a replacing row receives the option rather than having
to know the roles.

`:models`, `:config` and `:auth` stay libraries on purpose: the model catalog
is files, credentials are read before a ctx exists, and a provider is registered
with `register-model-provider!` — a registry concern, not the request.
`:workspace`/`:fs` need no service either (`kmet.libs.path` resolves against
`(:cwd ctx)` and the run binds `*cwd*`), and `:subprocess` is the tool layer's
own spawn.

The kernel's placement rule decides where a value can mount: a child ctx cannot
shadow a service its ancestor provides, so a per-agent value exists only where
nothing above provides one. `:permissions`, `:shell` and `:sandbox` are
per-agent for that reason, and `:bundle/minimal` deliberately carries no
`:permissions` row — a policy at the root would be every agent's policy
forever. `:approvals` is the mirror case: it belongs in the bundle at the root,
because asking is the front end's job and one process-wide seam, and its default
denies ("nobody can ask" is not "yes").

## Multi-session server mode

**An agent is a child context plus a handle.** The child ctx extends the ctx
`create!` was called with (the app ctx, or another agent's for a subagent),
inherits the root's services, and is the `[:app :agent/<id>]` line
`--dump-state` prints. The handle is the driver's: `:agents` never learns what a
turn, a session or a model is. `create!` is serialized and its `:setup` composes
the agent's world before the handle is published, so nothing observes a
half-built agent; a failing setup (or preset) rolls the child ctx back and
leaves no handle. `dispose!` takes the handle and nothing else — the handle is
the capability — aborts the live turn, drains the loop at the turn boundary and
then releases the ctx, so no caller observes a half-disposed agent.

The handle's ops are the front-end surface, measured from the front ends: the
send surface (`send!`, `steer!`, `follow-up!`, `inject!`, `cancel!`,
`when-idle`, `status`, `inbox`), the settings setters (`set-model!`,
`set-thinking-level!`, the cycles, `set-config!`, the tool/compaction/steering
switches) and the session lifecycle (`new-session!`, `resume-session!`,
`switch-session!`, `fork!`, `branch-session!`, `clone!`, `set-session-name!`,
`export-html!`). Readers stay accessors over the handle (`:agent`, `:config`,
`:cwd`, `:turn-running?`, the queue atoms); every mutator is a registry op, so
a front end never reaches the driver.

The **server** makes that value multi-agent within one principal: accepting a
socket creates an agent, every record carries its `:agent-id`, and closing the
socket disposes it. A connection authenticates before it gets an agent and
brings its world (`:cwd`, `:config`, the run's overrides). Its writes are a
bounded per-connection lane that drops the **oldest** record and emits
`{:type :stream-gap :dropped n}`, so a client resyncs with `:get-state` instead
of silently losing a message. [`serve.md`](serve.md) is the connection rules;
[`rpc.md`](rpc.md) is the command table the rpc and server modes share.

## The event bus

One table. The listener table and the six non-awaiting dispatch modes are the
kernel's ([`srce.md`](../modules/srce/src/kmet/srce/srce.md) §5);
`kmet.app.events` owns what a kernel must not know — the vocabulary and the
owner a registration carries — and `kmet.app.wire` the projection a headless
consumer reads. `events/on!` registers through `srce/on` with the registrant's
ctx, so a listener is an effect of the registering fiber and hears only its own
subtree, and `emit!` dispatches by the mode the event declares.

- The vocabulary is a **registry**, like `:commands`: a plugin's `:events` map
  is registered at mount under its owner, so a plugin's own events dispatch by
  their declaration and unloading sweeps them. `emit!` refuses a mode the kernel
  does not implement, and an emission must name a ctx.
- **One event, one dispatch.** `:agent-settled` is emitted once per prompt and
  carries the run's `:outcome`; the boundary modes are the declaration's job,
  not a second dispatcher.
- **Extension-only events are a vocabulary property** (`:public false`): the
  provider bridges and the `:session-before-*` hooks carry request payloads, the
  `Authorization` header and an abort atom, none of it EDN. `on-every` is the
  public stream and skips them.
- Hooks are host registrations, not a service (`:kmet/hook` in the registry
  handle); the extension ABI registers on the host's ctx like any other
  listener. A front end that needs data not on the stream extends the event, not
  the reader.

## Permissions and approvals

Approvals are a policy plus one delivery seam, not two request APIs.
`:permissions` decides, synchronously and per agent; `:approvals/ask!` is the
one place an `:ask` goes, and it owns its timeout and abort (the run's `:signal`
lets a dialog give up; the gate imposes no deadline). The TUI answers with its
dialog, the server answers later over the wire, headless answers with its
configured default, and `:ui/request!` stays the generic dialog channel.

The gate is the loop's pre-tool waterfall and runs **after** the extension
hooks, so a hook's argument rewrite is judged by the policy instead of slipping
past it; `run_code`'s bridge composes the same gate into the tool hooks, so a
script's inner `tools/call` is gated too. A policy that throws, or answers
something that is not a verdict, is a deny — the failure mode of an approval
gate must never be allow. `:remember!` records an answer at `:once`, `:agent` or
`:workspace` scope in the memory the agent's ctx carries, which is what makes a
remote session usable without a prompt per call.

## Tools and the script sandbox

The tool layer is `kmet.app.tools`: one record per tool, mounted as a row
(`:tool/read`, `:tool/bash`, …) and dispatched through the `:tools` service. Two
things are worth knowing before changing a tool.

**One invocation pipeline.** `kmet.app.tools.invoke` is the only
prepare → execute → finish path:

- `prepare-tool-call` runs the before hook (pass, rewrite args, or block the
  call with a result);
- `execute-tool-call` executes under the caller's worker bindings and turns a
  throwing tool into an error result;
- `finish-tool-call` applies the after hook's `:content`/`:is-error` overrides,
  for blocked calls too.

The loop splits the phases so it can prepare sequentially, execute a batch
concurrently, and keep emitting events and appending to the session; the
`run_code` bridge runs all three inside one worker task and keeps its own
promise and call trace. A scripted call therefore meets the run's
before/after tool hooks exactly as a loop batch does — and the policy gate is
the outer `run_code` call, not an inner one, because a per-tool hook is not a
sandbox (a script can shell out through `babashka.process`).

**`run_code`** (`kmet.app.tools.run-code`, the `:tool/run_code` row) is
model-written Clojure in a per-call sandbox with the agent's tools bridged in.
*Why it exists*: a tool result is re-sent on every later turn, so its token
cost is paid repeatedly. The tool was measured before it was built — ~78% of
result tokens were the find/read workload, mostly leaking through `bash`
(`cat`/`rg`) rather than the `read` tool — and adoption is still the limiter
after it landed (0.5% of result tokens, with the bash/read split unchanged).
Its value is designed into the description and the batched surface, not assumed
from per-call cost.

- **The boundary is the ctx.** The loop hands every call the run's ctx as
  `:srce-ctx`; `run_code` replaces it with a `srce/isolate` **realm** of that
  ctx blocked for the names the agent's `:sandbox` value names (`:tools`,
  `:shell`, `:workspace` by default — `kmet.app.sandbox`). A tool a script
  calls cannot look the composition's services up from the ctx it was handed;
  what it may use — this call's tool surface, cwd, shell and skills — travels
  in the invocation's `:bindings`. The realm is per call and detached when the
  script returns.
- **Lifecycle.** One fresh SCI context per call, discarded on return. The
  host's `clojure.*`/`babashka.*` namespaces are copied in (public vars
  re-wrapped so macros survive), babashka gets the host class table, and the
  fixed bundled-extension set (`kmet.app.extensions.libs`) is shared by value
  the way an extension context gets it. `:load-fn` refuses anything else, so
  requiring a kmet namespace is the named boundary error. Jolt's SCI exposes no
  class table and the JVM host an explicit `java.lang`/`java.io`/`java.util`
  subset. A `def` in one call is invisible to the next, and redefining an
  injected var stays call-local.
- **The bridge.** `(tools/call name args)` and the four builtin sugars return a
  promise that settles with the record's verbatim result map
  (`{:content :is-error :details :truncation :images}`); `tools/call-many`
  validates and submits a batch, and `tools/await-all` derefs it in input order
  while polling the cancel signal. Calls run on a shared process-wide pool (16
  daemon workers), and a per-call admission lane gives **submission-order
  starts** and treats a `:sequential` tool as a barrier — the same
  `:execution-mode` the loop's own batches honour, so no built-in declares it
  and the guarantee is for extension/contributed tools. Discovery
  (`tools/list`, `tools/describe`) is a bridge-local read and never exposes
  `:execute`.
- **The callable set** is the loop's `select-tools` result: all tools ∩ the
  agent's enabled set, plus extension-contributed sandbox-only sources, minus
  the exclusions (`run_code` itself, so a bridge submission cannot nest). A
  source is registered on the `:kmet/tool-source` tag, resolved per call, and
  never joins the model's table — the mcp-adapter contributes its MCP catalog
  this way (`kmet.app.tools.registry/register-tool-source!`). Listing and
  dispatch therefore cannot drift.
- **Output is `emit` only.** `(emit v)` (also `sandbox/emit`, and the bare
  `emit` in the script namespace) is the one reported text channel: strings
  print raw, other data `pr-str`, and it returns nil. stdout, stderr and the
  final value are captured for the budget but never reported, so a script that
  wants the model to see something must emit it. `(emit-image path-or-block)`
  is the second channel, capped at 4 MiB of base64 per image and 8 images per
  call, accepting exactly what `read` accepts. Capture is bounded at 1 MiB
  (exceeding it aborts with `:output-limit`), printing is bounded
  (`*print-length*`/`*print-level*`), and the emitted body is truncated to
  16 KiB / 2,000 lines by default (`:max-output-bytes` may raise it to the
  shared 50 KiB tool cap).
- **Deadlines and cancellation are one signal.** Eval runs on a daemon thread
  with SCI's `:interrupt-fn`. `:timeout` is seconds (absent or 0 = no deadline,
  bash's semantics); Escape and the script's own abort (timeout/output-limit)
  both feed the bridge's signal, which is the read-only OR of the run's and the
  script's (`kmet.libs.concurrent/or-signal`), so an inner call is cancelled by
  either. A pool task checks that signal at admission and again after the before
  hook, so a slow hook cannot start a call past the abort. Per-tool coverage
  stays cooperative (bash kills its process tree; MCP calls have no signal
  plumbing), and a host-blocked script is abandoned after a 1.5 s interrupt
  grace rather than killed — the one accepted limitation of an in-process
  sandbox, with the process-isolation tier recorded in
  [`new-app.md`](../new-app.md) § Open at HEAD.
- **Result shape.** `:details` carries the effective `:timeout`, the measured
  `:elapsed-ms` (wall clock for the whole call, including the grace and the
  capture drain) and the call trace `{:calls [...]}` — one entry per inner
  call, `:incomplete` when a tool ignored the abort. Inner results never enter
  the transcript, the session or the context, and no tool-execution event is
  emitted for them; the only things the model sees are the emitted text, the
  images and the result map.

The bridge pool is process-wide **by decision**: it is a bound, not a setting,
and everything per-run travels in the submission's `:bindings`, so two agents
share the pool and still run in their own cwd and shell. The cell is in
`kmet.app.test-process-state`'s ledger with that reason.

## Pluggable UI

`:ui` is a capability service, not an inherited widget set. Required of every
provider: `:kind`, `:render-tree`, `:request!`, `:capabilities`; everything else
(`:widget-set`, `:input!`, `:notify`) is optional and must be named in
`:capabilities`, so a consumer asks what the provider it has can do instead of
probing. `kmet.app.ui/check` fails at the mount, naming the provider and the
missing key, and `port` gives a mode the ABI map it binds
(`kmet.app.front-end/install!`).

The providers the tree ships are `:ui/headless` — nothing renders and every
dialog answers the ABI's own default, so it is the base print/rpc/server stack
rather than a stub — and `:ui/terminal` (`kmet.ui.tui/provider-plugin`, half of
the TUI's two rows). A `ui-tk` provider and a web provider are separate
development.

A consumer declares `{:ui :optional}` so an absent provider does not hold its
fiber pending, and upgrades itself when a richer UI attaches. The upgrade is a
dispose-and-remount, so a UI consumer must be stateless: its state is the
session log, the agent handle and the widget tree, never its `apply` closure.
The one-line division to keep straight: **`kmet.tui` renders components;
`kmet.ui` decides which backend renders.**

## Modes

A mode is a row (`:mode true`) and the profile is the selector — there is no
`:modes` service and no listing registry, because `--dump-config` already prints
the mode rows. The headless modes are `kmet.app.modes/{print,rpc,server}`;
interactive is `kmet.ui.tui`, which is also the terminal `:ui` provider, and it
can be both because a row names a plugin by var (a rank-3 provider costs a
rank-2 mode no require). `kmet.test-layer-guard` holds the boundary in both
directions: a business namespace never requires `kmet.ui.**`, `kmet.tui.**`,
`kmet.tk.**` or a mode row, and a front end reaches the engine through the
session runtime rather than the loop, its stages or the retired session engine.
An extension cannot add a mode — the mode row is scanned before boot — but it
can contribute the commands and tools a mode offers.

## Reload and the turn boundary

The kernel is synchronous (a provider change runs the refresh pass to a fixpoint
before the call returns), so *when* the app allows a reload is the app's rule:
at the turn's closing boundary, never mid-turn.

- The boundary is `:agent-settled`. `kmet.app.loop/settle-boundary!` drains
  `kmet.app.runtime`'s queue **before** the emit, so the event means both "no
  turn in flight" and "no pending refresh": a tool thread's mount is invisible
  to the turn that submitted it and live when the event fires.
- `kmet.app.runtime/boundary!` admits **one** boundary at a time per root: turns
  run on futures, and two drains at once would mount plugin code concurrently
  (the pass flag does not prevent it — a `plugin!` that loses the pass runs its
  `apply` inline).
- A thread that must mount or provide **submits** (`submit!`) instead of
  mutating; `kmet.app.test-submit-guard` holds every structural kernel call site
  to a written owner-side reason and refuses one in the tool layer, the mode
  rows or the UI. The kernel's half is in `srce.md` §3–§4.
- **A request's tool list is one capture**: the wire list, the declared set and
  the dispatch resolve against the snapshot taken when the request was built, so
  a tool unloaded mid-request still runs for the request that offered it and a
  tool added mid-request is not dispatched.
- **Disposal never waits.** A teardown does not block on quiescence — the
  boundary is where quiescence is decided — and a teardown that finds work in
  flight cancels it. A remounted dependent is stateless, for the same reason the
  UI upgrade is: its state lives in a service handle, not in `apply`'s closure.

## Inspection

`--dump-config` answers "what is composed" without booting; `--dump-state` and
`/units` answer "what is mounted". One renderer (`kmet.app.inspect/units`), two
entry points, both handed the process root — so a command and a flag cannot
compute two listings that drift. One unit per line, path first, parents before
children, fibers in mount order:

- a **ctx** line is a path and its child count;
- a **fiber** line is its state, its `inject` (an optional dependency marked
  `?`), what it `provides` and its effect labels, plus the two facts a
  registration table could never show: a `:pending` fiber's `waiting-for` and a
  `:failed` one's error.

`--dump-state` boots a **diagnostic** composition (`{:diagnose? true}`), so a row
that stays `:pending` or ends `:failed` is a line in the tree instead of a boot
error — a dump that failed on the very state it exists to explain would be
useless exactly when it is wanted. It mounts every row except the `:defer` ones
and prints them as a trailing `deferred: …` line. Effect labels are the
inspection contract (`srce.md` §4): a catch-all label makes the line useless.

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
  `kmet.libs.*` plus standard/third-party code. Exactly one of its namespaces
  is a plugin row, and it is the transport: `kmet.ai.llm` is required by
  `kmet.app.llm` alone, which provides `:llm`, so replacing that row swaps
  every model call. The rest is data the host reads directly — the catalog
  (`kmet.ai.models`: `get-model`, `get-available`, the thinking levels, the
  loaders), credentials (`kmet.ai.auth`) and usage tallies
  (`kmet.ai.usage`). A replacing `:llm` row does not own that data: it returns
  provider-shaped messages and the app prices them with the same tallies.
  Extensions never see `kmet.ai`; the catalog reaches them as the `:models`
  capability map (also `ctx.models`), whose `register-provider!` records the
  deregister fn so an unload takes the registration with it. It knows no
  agent-context role: the app's convertToLlm arrives as the `:convert-to-llm`
  option and the dispatcher applies it after the context hook. It names no
  state root of its own: the agent dir (`KMET_CODING_AGENT_DIR`, else
  `~/.kmet/agent`) is the app's — `kmet.app.config/get-agent-dir` — and
  arrives as an argument at every `kmet.ai` entry point. The one remembered
  copy is `kmet.ai.auth/load-auth!`'s, because the credential writers are
  reached from `resolve-api-key` (an OAuth refresh), which carries no dir;
  they throw `:kmet.ai/no-auth-root` rather than invent one.
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
`clojure_eval`. Discovery checks the port files first, then probes 7801, 7802,
7803 and the historical defaults 7888/1667.

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

### Guards

The structural rules above are tests, not conventions. The ones that hold this
document are:

| Guard | Holds |
|---|---|
| `kmet.test-module-deps` | ranks stay strictly downward; a module's `deps.edn` is its `src` edge list in both directions |
| `kmet.test-modules-manifest` | `modules.edn`, `bb.edn` `:paths`, `deps.edn`'s embed list and the packagers name the same trees |
| `kmet.test-seam-requires` | a namespace requires no module above its own rank; `kmet.ai`/`kmet.libs` never reach up, `kmet.ai` names no `KMET_*` env var (the agent dir arrives as an argument), and the app names only the `kmet.ai` surface — never a wire builder |
| `kmet.test-layer-guard` | the business tree never requires the front ends (UI, TUI, Tk, a mode row); a front end reaches the runtime, not the loop or the retired engine |
| `kmet.test-front-end-policy` | a front end names the process's resource policy and expansion chain where it wires a run; the TUI reads a handle, not the engine |
| `kmet.test-no-global-node` | the retired process node and store names appear nowhere |
| `kmet.test-retirement` | no `kmet.agent.*` name but `kmet.agent.ui.tui.tool-renderers` (an extension ABI name) |
| `kmet.test-process-exit-guard` | `System/exit` only in `kmet.app.core/-main` and the two model generators' `-main`s |
| `kmet.test-time-boundary` | durations use `kmet.libs.concurrent/monotonic-ms`, never the wall clock |
| `kmet.app.test-process-state` | every remaining process cell has a written reason, checked both directions |
| `kmet.app.test-submit-guard` | structural kernel calls only from the owner's side |
| `bb check-catalog` | `docs/composition.md` matches the built-in composition |

The kernel's own gates (contract tests, self-containment, the no-`scope` ban)
are in [`srce.md`](../modules/srce/src/kmet/srce/srce.md).

The commit-msg hook installed by `sh scripts/install_hooks.sh` refuses
co-author trailers, AI session links, machine addresses and generated-with
footers at commit time; the attribution workflow
(`.github/workflows/attribution.yml`) checks the same rules over a pushed or
PR range. It is the repository's only CI check.
