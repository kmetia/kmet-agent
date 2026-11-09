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
[`modules/app/src/kmet/design.md`](../modules/app/src/kmet/design.md); this file
is the current map. Temporary analysis notes (`perf.md`, `jolt-bugs.md`,
`tcl.md`) remain at the repository root by convention.

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
│                         output), read, write, edit, codemode, eval, config, shell
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
├── eval.clj            — the `:eval` persistent script environment
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

Each row names its own file (`sessions.clj`, `extensions.clj`, `agents.clj`,
`world.clj`, …): a row is a `srce` plugin whose `apply` provides its service,
and `registries.clj` is the router behind them. How the rows are composed is
§ Composition below; [`design.md`](../modules/app/src/kmet/design.md) keeps the
design record and the items that did not ship.

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
  prints the built-in table alone, and `kmet plugins` edits the two ambient
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
| `:tools` | a `Registry` handle (tag + name) | `kmet.app.registries/tools`; each tool row registers through it |
| `:commands` `:prompts` `:skills` | a `Registry` handle each | `kmet.app.registries/{commands,prompts,skills}` |
| `:resources` | `{:catalog :unavailable :available? :global-selected? :project-state :selected? :enabled? :enabled-names :set-global! :set-project!}` — selection over the three registries, read through the scoped `:resources` settings; `:unavailable` reports stored entries no provider offers, and `:set-global!` accepts `:inherit` to forget one | `kmet.app.resources/plugin` |
| `:events` | the event vocabulary registry | `kmet.app.events/plugin` |
| `:systemPrompt` | ordered sections assembled over `:skills`, filtered by the `:resources` selection | `kmet.app.system-prompt/plugin` |
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
| `:eval` | `{:eval! :drop! :move! :library :live :index :describe :manifest :sources :source-commands :failures :dispose!}` — the agent's persistent script environment over the global and cwd eval libraries, one per session; `:manifest` is the prompt listing that never builds an environment | provided per agent by the agents factory (`kmet.app.eval/value`); a preset row that mounts first wins |
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
nothing above provides one. `:permissions`, `:shell`, `:sandbox` and `:eval` are
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

The gate is the tool point's innermost `next` and runs **after** the extension
hooks, so a hook's argument rewrite is judged by the policy instead of slipping
past it — and a hook cannot rewrite what the policy already judged.
`codemode`'s bridge runs the same point with the run's checks, so a script's
inner `tools/call` is gated too, judged on the agent's ctx and not the sandbox
realm's. A policy that throws, or answers something that is not a verdict, is a
deny — the failure mode of an approval gate must never be allow. `:remember!` records an answer at `:once`, `:agent` or
`:workspace` scope in the memory the agent's ctx carries, which is what makes a
remote session usable without a prompt per call.

A tool call's events split at that seam: `:tool-execution-start` fires before
the chain (the call's block exists while the question is up), and
`:tool-execution-prepared` fires once the chain descended past the check, just
before the body runs — a front end starts the tool's elapsed timer there, so a
human's thinking time is not counted as the tool's. The TUI's ask shows the
call's own one-line title (`$ <command>`, the path) in the question, because
the transcript block behind the dialog can be scrolled out of the window,
keeps `app.tools.expand` live while the question waits, and answers a run
cancelled under the dialog with a deny rather than parking the run thread.

## Tools and the script sandbox

The tool layer is `kmet.app.tools.*`: one record per tool, mounted as a row
(`:tool/read`, `:tool/bash`, …) and dispatched through the `:tools` service. Two
things are worth knowing before changing a tool.

**Declared and callable are two derivations.** A tool record carries an
`:exposure` (`kmet.app.tools.tool`): `:direct` (the default) and `:model-only`
are declared while selected; `:deferred` is callable but declared only once
`tool_search` loads it; `:sandbox` (a contributed source's catalog) is callable
and never declared. `kmet.app.loop.state/declared-tools-for` builds the live
candidate set from the resources selection (`kmet.app.resources/enabled-names
:tool`, read live) and the loaded set; the selection is re-resolved on each read,
so a tool that mounts later defaults on under a modifier-only list, while a
plain list is absolute. `sync-tool-declarations!` records an explicit selection change and
`sync-resumed-declarations!` reconciles the recorded loadout before each
request; both append a `:tool-change` entry and its wire marker to the live
context. The request-time reconcile folds the context's markers, not the whole
branch, so a compaction that dropped them re-declares the current loadout, and
a change raised while a tool batch is executing is recorded at the next request
boundary — never between a call and its result.
`active-tools` returns the branch's recorded fold joined with the live records — name-sorted, because
past array-map size a map's order is hash order and a replay of the recorded
names must reproduce the array. A
`tool_search`-named tool is declared only while the selection can promote
something — a selected `:deferred` tool or any contributed `:sandbox` tool
(`kmet.app.tools.registry/promotable-tools`, the same derivation the search
itself ranks) — so a direct-only setup pays no schema for it and a selection
that excludes every selected deferred tool declares none; sandbox tools are not
selectable and cannot be excluded. The
bundled clojure and tree-sitter extensions are the shipped users of that: each
declares its family's entry point directly (`clojure_edit`,
`list_symbols`/`find_definition`) and contributes its refinements as one
`:deferred` source, so a default composition declares 6 schemas fewer and
announces the two sources instead. The clojure extension's edit-family tool records are
schema/render wrappers over the shared `kmet.libs.clj-edit` engine; the same
engine backs eval's core `clj/edit-form`, `clj/replace-sexp` and
`clj/paren-repair` verbs, so structural editing does not require
`:ext/clojure`. The
advertised set is a session fact. The selection mutation (`set-active-tools!`)
records the live selection with `sync-tool-declarations!`;
the loop reconciles the recorded loadout before every request with
`sync-resumed-declarations!`; a promotion uses the same resumed reconcile. Both
write the delta by value — name, description, parameters and constrained
sampling, never the runtime record.

`active-tools` folds those entries and joins each declaration with the live
registry for its runtime fields, so a resumed session replays what it was sent
rather than re-deriving it from a registry that may have changed: a recorded
tool the live registry no longer holds stays declared (an MCP server still
connecting), a tool this state watched unmount is removed while it stays gone
(a remount restores it unless the persisted selection excludes it), and an
extension tool registered after the record joins the next request. `:tool-load` entries
remain the promotion log, so disabling a loaded tool hides it without
forgetting the load. `callable-tool-names` is the other half — the selection
minus the `:model-only` tools.

**A tool change is a transcript message, not a rebuilt array.** Each
`:tool-change` entry projects to a wire-only `:role :tool-change` marker at its
recorded position (`session/context-messages`); the chat replay and export
still skip it (the tree keeps its compact `[tools: …]` line). The first marker
is the initial declaration the
top-level tools array carries; later markers ride in place. Anthropic models
with `:supports-mid-convo-system-messages` and
`:supports-mid-convo-tool-changes` declare the initial tools once, add a
deferred placeholder and the `inline-tools-2026-09-15` beta, hold a marker
until the next assistant message, and send later markers as `tool_addition` /
`tool_removal` blocks. Responses models use `additional_tools` items at the
marker's position (or a synthetic tool-search pair where only additions are
supported), and Kimi completions (`:deferred-tools-mode :kimi` or
`:supports-mid-convo-tool-additions`) emit a tool-bearing system message there.
Every request's message list therefore extends the previous one — the delta
sits where it was recorded, and the cached prefix survives. Every other wire
drops the markers and sends the current declared set top-level
(`kmet.ai.llm/send-message`), which is a fallback, not a regression. The
request-time reconcile folds the markers in the live context, not the whole
branch, so a compaction that drops them re-declares the current loadout. The
history is appended, never rewritten, so a resume or fork keeps what it
recorded.

**`tool_search`** (`kmet.app.tools.search`, the `:tool/tool_search` row) is the
tool that closes the loop: it ranks the searchable tools that are not declared
yet with `kmet.libs.ranking` — the same scorer `mcp search` reads the MCP cache
with — and loads every match, so the next request declares them. Its
description is a constant (registering a source never rewrites a cached schema)
and it is `:model-only`, because a script that could call it would change what
the model sees mid-script. A deferred source is announced to the model by one
appended `:custom-message` entry — its id and one line, written when the set or
a description changes (`state/sync-tool-sources-listing!`), never on a
promotion and never by rebuilding the leading system prompt (pi's appended
`mcp_servers` section; D7). The text is bounded (`session/context.clj`): a
description elides past 250 characters and the whole text at 4096, where the
descriptions shrink to share the room — every source stays listed, because being
listed is the point — and only the head lines outgrowing the room drops the last
sources into one `… N more sources` line, as pi's section does. A source is
announced only when at least one of its tools is selected, and the intro names
only the discovery paths the selection actually has — codemode/eval
(`tools/list`) and/or `tool_search`; with neither, nothing is appended.

**One invocation pipeline.** `kmet.app.tools.invoke` is the only
chain → check → execute path:

- `tool-point` runs the call's around chain — `kmet.app.hooks/around` over the
  registered `:on-tool` hooks, first registered outermost, fail-closed — around
  the body. A hook that does not call `next` settles the call (nothing runs),
  and a `{:block true …}` result becomes the canonical blocked error;
- the permission check is the chain's innermost `next`, so the policy judges
  the arguments every hook settled on;
- `execute-tool-call` executes under the caller's worker bindings and turns a
  throwing tool into an error result.

The loop emits a call's start event sequentially and runs its chain and body on
its own worker, keeping the batch concurrent while every call's clock starts
with its own body; the `codemode` bridge runs the same point inside one worker
task and keeps its own promise and call trace. A scripted call therefore meets
the run's chain exactly as a loop batch does — and the policy judges it too —
while a hook still is not a sandbox (a script can shell out through
`babashka.process`).

**`codemode`** (`kmet.app.tools.codemode`, the `:tool/codemode` row) is
model-written Clojure in a per-call sandbox with the agent's tools bridged in.
*Why it exists*: a tool result is re-sent on every later turn, so its token
cost is paid repeatedly. Measured over the session logs at the time of the
change (689 `codemode` calls): 98% never touch the bridge — 628 `emit`, 342
`slurp`, 327 `sh`, 235 regular-expression, 0 `pmap`, 0 futures — and the 14
that do are all in kmet's own sessions: 8 one web fetch (a tool with no local
twin), 3 redundant `bash` calls, 3 bridge probes. The batch helper had one
probe and no real use, and is gone;
`scripts/bench/codemode_adoption.bb` reproduces the numbers.

- **The boundary is the ctx.** The loop hands every call the run's ctx as
  `:srce-ctx`; `codemode` replaces it with a `srce/isolate` **realm** of that
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
  the way an extension context gets it, as are the four portable `kmet.libs`
  ports (`kmet.libs.json`, `kmet.libs.http`, `kmet.libs.yaml`,
  `kmet.libs.markdown`) — the documented replacements for host-only data and
  HTTP libraries. `:load-fn` refuses the rest, so requiring any other kmet
  namespace is the named boundary error. Jolt's SCI exposes no
  class table and the JVM host an explicit `java.lang`/`java.io`/`java.util`
  subset. A `def` in one call is invisible to the next (`eval` is the
  persistent-context counterpart below), and redefining an injected var stays
  call-local.
- **Ordering.** The tool declares no execution mode, so its calls use the
  default parallel batch. `codemode` code is not part of eval's definition
  classifier: a script's top-level `def` is call-local, so the batch stays
  parallel. That classifier does not reach the bridge either: a script's own
  `tools/call` fan-out still runs concurrently and has no barrier.
- **The bridge.** `(tools/call name args)` returns a promise that settles with
  the record's verbatim result map (`{:content :is-error :details :truncation
  :images}`). Calls run on a shared process-wide pool (16 daemon workers) with
  no ordering guarantee between themselves, and a script's call to a tool that
  declares `:sequential` is not a barrier — that classifier belongs to the
  loop's own batches. There is no batch helper: a script starts the calls it
  wants and derefs the promises. Three conveniences sit on that same dispatch,
  never beside it: `(tools/call! name args)` is the call derefed, with a failed
  result promoted to a throw (a script that cannot continue stops, and the
  trace still names the call); one var per active tool
  (`@(tools/read {:path "x"})`, for names the reader keeps whole — a name it
  would take apart keeps `call` as its handle); and the result readers
  `tools/ok?`, `tools/text` and `tools/error`. The namespace's own names
  (`call`, `call!`, `list`, `describe`, the readers) are never shadowed by a
  tool var, so a tool that is itself called `text` stays reachable through
  `call`. Discovery (`tools/list`, `tools/describe`) is
  a bridge-local read and never exposes `:execute`; listing carries each tool's
  name, description and parameter schema, so a script chooses a call without
  one `describe` per candidate.
- **The callable set** is the loop's `select-tools` result: all tools ∩ the
  agent's enabled set, plus extension-contributed sandbox sources, minus the
  exclusions (`codemode` itself, so a bridge submission cannot nest) and minus
  the `:model-only` tools (declared to the model, never callable from a
  script — `tool_search` is one). A source is registered on the
  `:kmet/tool-source` tag and resolved per call. Its `:exposure` decides where
  its catalog lands: `:sandbox` (the default) stays out of the model's table —
  the mcp-adapter contributes its MCP catalog this way — while `:deferred`
  joins the table undeclared and becomes declared once `tool_search` loads a
  tool (`kmet.app.tools.registry/register-tool-source!`). Listing and dispatch
  therefore cannot drift.
- **Output is `emit` (or a silent script's value).** `(emit v)` (also
  `sandbox/emit`, and the bare `emit` in the script namespace) is the curated
  text channel: strings print raw, other data `pr-str`, and it returns nil.
  A script that emits nothing and finishes normally reports its final value
  instead (the REPL rule, bounded like emit); stdout and stderr are captured
  for the budget but never reported. `(emit-image path-or-block)`
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
  either. The caller polls the signal whether or not a deadline is set, so
  Escape returns the call after the grace rather than after the model's
  timeout. A pool task checks that signal before the call starts and again
  inside the execute step, so a slow hook cannot start a call past the abort.
  Per-tool coverage stays cooperative (bash and the sandbox's
  `sh`/`shell`/`process` kill their process tree; MCP calls have no signal
  plumbing), and other host-blocked script code is abandoned after a 1.5 s
  interrupt grace rather than killed — the in-process sandbox's accepted
  limitation; process isolation stays a recorded open seam in
  [`design.md`](../modules/app/src/kmet/design.md) § Open at HEAD.
- **Result shape.** `:details` carries the effective `:timeout`, the measured
  `:elapsed-ms` (wall clock for the whole call, including the grace and the
  capture drain) and the call trace `{:calls [...]}` — one entry per inner
  call, `:incomplete` when a tool ignored the abort. Inner results never enter
  the transcript, the session or the context, and no tool-execution event is
  emitted for them; the only things the model sees are the emitted text (or a
  silent success's final value), the images and the result map.

**`eval`** (`kmet.app.tools.eval`, the `:tool/eval` row) is the persistent
counterpart. It shares `kmet.app.tools.script` — capability namespaces, capture,
the tools bridge, deadline/abort and result assembly — but the state belongs to
the session, not the call: `kmet.app.eval/value` owns one SCI context per
session (built lazily on the first call), its interrupt holder, sticky namespace
and call lock. `seed!` installs the call's namespaces into that context each
time, so the tool surface follows the agent's registry while definitions stay.
A definition from one call is visible to the next in the same session, and the
last namespace a successful call ended in is re-entered at the start of the
next. Because a call that defines a verb — persisted or live-only — makes
later calls order-dependent, the loop examines each eval call's code before
execution and runs a batch containing a top-level definition in source order
instead of letting the call futures race for the session's call lock;
definition-free eval calls keep the default parallel batch, where the eval
tool's `:serial-group` queues its own calls in source order. A queued eval is
not prepared until the previous one finished, so its transcript clock does not
run while it waits for the session lock. Eval creates verbs:
a successful top-level definition with a non-blank docstring is written
immediately to one of two durable layers: the global
`<agent-dir>/eval/<ns>/<name>.clj`, or the session cwd's
`<agent-dir>/eval-scopes/<shared-cwd-key>/<ns>/<name>.clj` (one verbatim
source slice per generated definition; a hand-written file can hold several;
`kmet.app.eval.files`). A new definition without a docstring stays live in
the session and is not written; `(eval/help)` lists it as session-only. A
`defmethod` is written with its loaded multimethod, since a method has no
docstring slot. Existing `<agent-dir>/eval`
files are the global layer; nothing is moved automatically. The key is the
shared injective session-dir encoder, pinned when the environment is built: a
later
call's `:cwd` still resolves file verbs and sources, but never moves the
library root. Global loads first, then cwd; a name's stack is `[global cwd]`
and the last record is live, so cwd shadows global. New names default to cwd;
redefining an active name keeps its layer and its file. `:scope :global` or `:cwd` chooses
a new name's layer and is accepted for an existing name only when it names
the active layer. A session's first eval scans and evaluates both layers; a
new session or a restarted process loads them, while an already-built
environment is a runtime cache and never refreshes. Sessions share the files
but not the environment. `:drop` and `(eval/forget 'name)` target the active
record by default; a scoped drop targets either layer. Drop/forget resolve an
unqualified name in the namespace the call ends in. Dropping a record deletes
its actual `:file`, unbinds the live name, and re-evaluates the next record
(restoring a shadowed global); dropping a non-active record deletes only its
file and leaves the live definition. All targets resolve before anything is
deleted, and a file that also defines other addresses must be requested with
all of them (a partial file is refused). A mounted source's shadow is
reported as `restored`, not `forgot`. A rejected drop leaves the call's
definitions live but unwritten, like any other change failure. A code-less
drop needs no environment: it reads the disk index and resolves cwd first, or
`:scope`.
`(eval/move 'name :global|:cwd)` and `/eval move <name> global|cwd` move an
active definition without
re-evaluating; they refuse collisions, same-scope moves, and source-provided
or unwritten targets, take both root locks in the fixed global-then-cwd order,
and roll back the target if the source delete fails. The engine-portable
builtins register `/eval` (`list`, `search`, `describe`, `drop`, `move`,
`sources`, `help`, plus verbs an eval source contributes), independent of the
`:tool/eval` row,
so removing the row leaves the command and its service. An unknown leading
name that resolves to a definition or source verb is a `describe` shorthand;
an unresolvable one keeps the unknown-verb hint. The row also
registers the `:core` eval source (the shared
`read-file`/`edit`/`write-file` ops under `user`, and `clj/edit-form`,
`clj/replace-sexp`, `clj/paren-repair` from the shared `kmet.libs.clj-edit`
engine), so removing it leaves the command
without those verbs. The clojure extension's tools wrap the same engine; the
core Clojure verbs do not require the extension. The eval tool's description,
guidelines and `(eval/help)` name the shared script surface (aliases, the
`tools` namespace, require-able namespaces and bundled libs) without depending
on `codemode`, since either row can be selected alone; host-specific sandbox
differences stay in this section, not in the model-facing text. `list` and
`search` read the disk index across both roots (the built environment's pinned
roots when the session has one, else the command cwd), so they show the
library rather than one session's cache and mark each entry `(cwd)` or
`(global)` and shadowed. `list` then appends the session's live-only
definitions after the saved entries, in the order global, cwd, live, when that
session already has a built environment. `drop` deletes the files directly
and unbinds the live name only when that session already has an environment,
so a drop never builds one. The environment build
seeds the call vocabulary and the standard aliases before evaluating the
library, and each definition's per-call primitives (`emit`, `cwd`, the process
wrappers and the tool bridge) read the current call's state rather than the
call that compiled them. Parallel calls serialize on a per-session lock, the
deadline starts after it is taken, and `:dispose!` clears every environment;
the tool's guidelines therefore steer a multi-step task into one call, where
inner tool calls fan out as promises and local work can use `spawn`. A
broken library file is reported under `:failures` and skipped. The pure parse
and address rules live in `kmet.app.eval.definitions`.

Every library record carries its model-visible interface, parsed from the
defining form without evaluation: `:doc` (the docstring, else an attr-map or
symbol `:doc`) and `:arglists` (for `defn`/`defn-`/`defmacro`, and the
parameter vector of a `defmethod`). `(eval/library)`, `(eval/help)` and
`/eval list` show them, so an existing file gains its interface when its
source has one. `(eval/describe 'name)` resolves a library definition, a
source-provided key, or a live definition that was never written (a failed
write or an errored call) and returns `{:name :kind :arglists :file :scope
:source}` by default: `:doc` is omitted from an entry with a `:source`, and
nil-valued fields are omitted; `(eval/describe 'name key)` returns one field,
including `:doc`. `:source` is
the stored defining form for a written definition (no generated `(ns …)`
header) or the retained in-memory form for an unwritten one, and a source
verb's `:source` metadata when the source supplies it. `:file` is nil for
source-provided and unwritten entries; a written definition reports `:scope
:global` or `:cwd`, and a shadowed global record is visible in
`(eval/library)` and the disk listings with `:active false`. Load, write and
unbind failures carry `:scope` too. Source verbs carry the same keys as
metadata on their values (`with-meta`), which `normalize-source` validates and
`source-var` puts on the SCI var, so `(meta #'license/add!)` works; the plural
`(eval/sources)` stays the registered-source listing and includes each verb's
doc and arglists. A non-blank docstring is the save contract: an undocumented new
definition stays live for the session and is not written; its result line
says `live only, not saved`, `(eval/help)` lists it under session-only
definitions, and `(eval/describe 'name :doc)` is nil. A redefinition of a
saved name without a docstring is not written; the saved file keeps its
documented version. A `defmethod` is covered by its multimethod.

Prompt discovery. Prompt start appends one hidden `:eval-library` custom
message (`kmet.app.session.context/eval-library-listing`,
`kmet.app.loop.state/sync-eval-library-listing!`) only when its text changed
since the branch's last one and only while the `eval` tool is selected. The
message starts with the save rule (`Eval creates verbs: a documented top-level
definition is saved here; a new undocumented one stays session-only.`) and is a
bounded, deterministic preview — `name [arglists] (scope[, shadowed]) — first
doc line` (or `(no docstring)` for a legacy entry), with whole definitions
dropped behind `… N more; (eval/library)
lists them` — never a source dump and never a system-prompt
rebuild, so listing a definition costs no prefix miss. Loaded is not saved: a
session with a built environment lists its loaded records with scope and
active state, while one without lists the disk index as saved and says it
loads on the first eval. The sync never builds an environment and never
invokes an eval source; source verbs stay out of the manifest, which points at
`(eval/help)`. The first call that builds an environment reports a non-empty
loaded set and every load failure in its result: `:details :loaded` is a
bounded preview with the full `:loaded-count`, `:failures` carries every
failure, and the result lines bound long lists, correcting any drift. A
library that has always been empty is silent; when a previously listed set
empties, the next prompt gets one correction.

Limits: a definition file is the defining form only — its `(ns …)` requires are
not stored, so the standard aliases are replayed and anything else must be
self-contained. A file's generated `(ns …)` form runs before the aliases.
Files load sorted by path within each layer, global before cwd; a `defmethod`
file needs its `defmulti` file, and interdependent files can load out of
order. A global definition cannot depend on a cwd definition (the cwd layer
loads later), a shadowed global still runs its top-level side effects, and
moving a global definition to cwd can break other global files. Dropping a
shadowed `defmulti` re-evaluates the next record for that address, but
methods from the other layer may need a rebuild. A move updates the file and
the record only; it never re-evaluates. Dropping a `defmethod` address
deletes its actual file and removes only that method; the multimethod and its
other methods stay live. A multi-name `declare` writes its whole source under
each name; dropping or moving one is refused unless every address that file
defines is in the request. A hand-written file with several top-level
definitions behaves the same way: dropping or moving one address refuses
until all of them are requested, and a redefinition edits the named form in
place so the siblings survive. Distinct addresses never share a file: a
spelling that the readable sanitizer would collapse (`a?`/`a!`) gains a short
address digest. A source-provided key may be redefined live but is never
written. A definition without a docstring is live-only: it has no library
file, so patch/edit-form/move refuse it, and drop/forget unbinds it as
unwritten. A
call that ends in a normal error writes nothing; definitions it evaluated
before the error stay live and are forgettable. `(eval/patch 'name old new)`
replaces the first structurally equal subform inside the named definition,
re-evaluates the definition, and only then writes the file; the report line
joins the call's result lines. An unknown old form, a changed definition name,
a result without a docstring, or a failed re-evaluation leaves the file and
the live definition untouched.
A source-provided key or an unwritten live definition (whose file is not
authoritative) is refused. `(eval/edit-form 'name new-source)` replaces the
whole definition through the same engine, re-evaluates it, and only then
writes the file; a failed parse, changed name/namespace, result without a
docstring, failed re-evaluation, or failed write leaves the file and the live
definition untouched. A timed-out
or aborted call is not tracked, because its interpreter
may still be running. A failed write is
reported and the live definition remains until the process ends, with the old
file authoritative for the next build. A failed delete or unbind is reported;
a failed unbind leaves the live definition until a restart. A session environment and its lock are
retained until the agent is disposed; deleting a session does not release them,
and two sessions in one home share the files but not the live environment.
A cwd scope is one layer per normalized cwd, shared by sessions with that
cwd; worktrees and subdirectories are separate scopes by design, and
symlinks are separate because the key uses the normalized path, not
`realpath`. `(eval/library)`
lists every record the current environment loaded, active and shadowed.
Build failures are reported once, on the next result, and then cleared;
`/eval list` still shows them from the disk index.
Interpreted build code is bounded by a per-file deadline and one overall
build budget; host-blocked top-level code still has the sandbox's
interrupt-grace limitation. Source functions are synchronous extension code
and are not interrupted—keep them fast. Definition report lines are appended
after the script's output budget, not counted inside it.

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
  A disposer that cannot finish inline hands back an undelivered promise instead
  of blocking: `dispose!` reports it as `:undoing` and the kernel never derefs
  it (`srce.md` §4). Two places wait: **exit** — `shutdown!` derefs the
  subtree's handles against `teardown-timeout-ms` — and the **extension host**,
  where a wholesale unload and a reload before it remounts wait for the outgoing
  code's release (`unload-timeout-ms`). Both expire into a warning: the teardown
  never throws, and nothing else is late for anything.
- **A setup may be asynchronous too, and the mount is what waits.** A row whose
  `apply` returns a promise stays `:starting` (its names invisible, dependent
  rows `:pending`), and `runtime/mount-rows!` awaits the `:setup` handles
  against `startup-timeout-ms` before returning — so `boot`, a deferred row and
  `presets/mount!` all see decided fibers without repeating the wait. A row
  still `:starting` after that is a `:kmet.app/boot-failed` naming the setup
  that did not settle, not a hang. The kernel still awaits nothing — the
  owner's next `settle!` is what advances a delivered setup.

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
- Extension UI splits into runtime capabilities (the front end installs the
  selected agent's map with its surface; a run binds its own over it) and the
  front-end UI port (`extensions/install-front-end!`, registered under
  `[:app :ui]`). Dialogs ask through `request-ui!`/`respond!`, so an
  asynchronous client can answer later; the
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
| `kmet.test-retirement` | no namespace carries the retired `kmet.agent.*` prefix |
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
