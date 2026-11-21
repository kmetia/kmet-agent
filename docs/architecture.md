# kmet developer guide

This file is the contributor's map: the package layout, the composition data,
the service catalog, the layering rules and the workflow.
Implementation-specific references live beside their namespaces rather than in
the user-facing `docs/` directory: see
[`modules/tui/src/kmet/tui/tui.md`](../modules/tui/src/kmet/tui/tui.md),
[`loader.md`](https://github.com/kmetia/loader/blob/master/src/kmet/loader/loader.md),
[`srce.md`](https://github.com/kmetia/srce/blob/master/src/kmet/srce/srce.md),
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
├── libs/   — generic, self-contained helpers        (kmet.libs.*)
├── tui/    — terminal backend over hikap            (kmet.tui.*; tui.md)
├── ai/     — provider/auth library                  (kmet.ai.*)
├── sklop/  — composition: rows, bundles, profiles    (kmet.sklop.*; sklop README)
└── app/    — the application + its TUI front end   (kmet.app.*, kmet.ui.*)
```

`modules.edn` is the workspace manifest: it lists the module trees and the
extra source roots, and the tasks read it instead of repeating the paths
(`kmet.tasks.modules`). The loader, reakt, hikap, srce and tk are the
exceptions: `kmet.loader.*`, `kmet.reakt.*`, `kmet.hikap.*`, `kmet.srce.*` and
`kmet.tk.*` are separate projects, pinned in `deps.edn` (tk in the `:jvm` alias)
and checked out as `../loader`, `../reakt`, `../hikap`, `../srce` and `../tk` in
dev.

A module's namespaces sit under the root it is
named for: `libs` holds `kmet.libs.*`, `ai` holds `kmet.ai.*`. `app` is the
one module with two prefixes: its business tree is `kmet.app.*`, and
`kmet.ui.*` is the TUI front end that merged into it (it was the `ui` module).

Ranks are the manifest's layering guard (`kmet.test-module-deps`): a module
may depend only on strictly lower-ranked ones, and a *namespace* may require
only a file in a strictly lower-ranked module — `libs` 0, `ai` 1,
`tui`/`sklop` 2, `app` 3. The external `kmet.loader.*`, `kmet.reakt.*`,
`kmet.hikap.*`, `kmet.srce.*` and `kmet.tk.*` libraries are pinned below all of
them. A leaf that both the app
and the components read therefore lives in `libs`, never in either of them:
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
├── profiles.clj        — the committed table's reader and default profile
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
patches); `kmet.sklop.core` owns the row language and the mount primitives —
the language is data only, it never loads a plugin or reads a process value;
`kmet.app.profiles` owns the committed table, `kmet.app.catalog` the app's
source policy for cards, and `kmet.app.runtime` mounts the composed result and
owns boot/teardown; `kmet.app.core` is the entry. The generated catalog of
what the shipped profiles mount, rows and model-facing tool text included, is
[`composition.md`](composition.md) (`bb generate-catalog` / `bb check-catalog`;
`bb test-changed` runs the check, so a stale page cannot pass).

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
  duplicate id across stacked layers — except in the two ambient layers, which
  compose leniently and drop such a patch with a warning. Configuration
  *values* deep-merge; composition does not.
- Layers apply in order: the built-in table < `~/.kmet/agent/patch.edn` <
  `.kmet/patch.edn` < `--patch FILE` (repeatable). `--dump-default-config`
  prints the built-in table alone, and `kmet plugins` edits the two ambient
  layers through `kmet.app.extensions.layers`. Before the composition is
  described, the entry drops from the two ambient layers any row or patch
  insert naming an `:ext/<id>` no installed extension answers to, and the
  remove or config patches that only targeted those rows — with a warning,
  saving the file — so a retired extension's row heals instead of failing the
  boot. A patch naming, or anchoring on, a row the composition does not have is
  dropped for that run with a warning and the file is left alone — the id may
  be a typo or a row from another version — but a `kmet plugins` save drops the
  remove and config patches among them, and a `--patch` file keeps failing,
  since it is not kmet's to edit. A profile still comes only from the
  built-in table: there is no named home
  profile and no project-shipped profile table. (dsh applies its home-level patch *after* the profile's own
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
| `:resources` | `{:catalog :unavailable :available? :global-selected? :project-state :selected? :selected-names :enabled? :enabled-names :set-global! :set-project!}` — selection over the three registries plus the eval sandbox's externally supplied verbs (`:verbs`), read through the scoped `:resources` settings; `:unavailable` reports stored entries no provider offers, and `:set-global!` accepts `:inherit` to forget one | `kmet.app.resources/plugin` |
| `:events` | the event vocabulary registry | `kmet.app.events/plugin` |
| `:systemPrompt` | ordered sections assembled over `:skills`, filtered by the `:resources` selection | `kmet.app.system-prompt/plugin` |
| `:sessionPersistence` | `{:create! :load :paths :append! :entries :delete! :header}` | `kmet.app.session.backend/plugin` (EDNL files) |
| `:sessions` | the log over the backend, plus the id index | `kmet.app.sessions/plugin` |
| `:projections` | derived state over the log: inbox, boundaries, token meter | `kmet.app.projections/plugin` |
| `:agents` | live handles, the factory seam, the send/control surface | `kmet.app.agents/plugin` |
| `:agentLoop` | the driver; registers the `:agents` factory | `kmet.app.loop.driver/plugin` |
| `:retry-policy` `:loop-guard` | the loop's stages | `kmet.app.loop.stages/{retry-policy-row,loop-guard-row}` |
| `:compactor/llm` `:compactor/vcc` | the two builtin compaction strategies (`{:prepare :summarize :supports-custom-instructions?}`); the LLM one summarizes with the model, the VCC one deterministically | `kmet.app.compaction.{llm,vcc}/plugin` |
| `:compaction` | the compaction stage's operations over the injected compactors, dispatching on the `:compactor` setting | `kmet.app.compaction/plugin` |
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
kernel's ([`srce.md`](https://github.com/kmetia/srce/blob/master/src/kmet/srce/srce.md) §5);
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

The eval sandbox is split by ownership: `core` holds the runtime verbs,
`clj` the structure verbs and `eval` the library API; all three are
system-owned. `codemode` alone mounts the `tools` bridge; `eval` has no
`tools` namespace and cannot call any tool. `user` is model-owned and
persisted; extension sources may contribute only to `ext.<id>`. A definition
in a system namespace is refused, and a definition in `ext.*` is live-only.

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
engine backs the sandbox's direct `clj/edit-form`, `clj/replace-sexp` and
`clj/paren-repair` verbs and is reachable through the `tools` bridge
(`tools/clojure_edit`, `tools/clojure_edit_replace_sexp`,
`tools/clojure_paren_repair`) when the extension is selected. Its
`clojure_eval` tool is likewise a schema/render wrapper: the evaluation path
is `kmet.libs.clj-eval` over `kmet.libs.nrepl`, which an eval call exposes
directly as `clj/nrepl-eval`. The advertised set is a session fact. The selection mutation (`set-active-tools!`)
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
only the discovery paths the selection actually has — `codemode`
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
  re-wrapped so macros survive), and the
  fixed bundled-extension set (`kmet.app.extensions.libs`) is shared by value
  the way an extension context gets it. Scripts pre-bind `fs`, `str`, `set`,
  `edn`, `walk`, `json` (`clojure.data.json`) and `p`
  (`babashka.process`), and `fetch` is the HTTP verb; the `kmet.libs`
  ports stay available to extension contexts but are not require-able from a
  script. `:load-fn` refuses the rest, so requiring any other kmet
  namespace is the named boundary error. Classes are the host's whole table
  on bb, Jolt's modeled classes on Jolt, and an explicit
  `java.lang`/`java.io`/`java.util` subset on the JVM. A `def` in one call is
  invisible to the next (`eval` is the
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
- **Output is emitted text and form values in event order.** `(emit v)`, also
  `(emit v)`, adds curated text: strings print raw, other data `pr-str`, and
  it returns nil. Emitted text and form values are reported in event order: a
  single form reports its value in full (the REPL rule, bounded like emit);
  with several top-level forms, earlier non-definition values are one bounded
  line each and the last value is reported in full; an error names the failing
  form and what ran. stdout and stderr are captured for the budget but never
  reported.
  `(emit-image path-or-block)`
  is the second channel, capped at 4 MiB of base64 per image and 8 images per
  call, accepting exactly what `read` accepts. Capture is bounded at 1 MiB
  (exceeding it aborts with `:output-limit`), printing is bounded
  (`*print-length*`/`*print-level*`), and the result body is truncated to
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
  emitted for them; the only things the model sees are emitted text and form
  values, the images and the result map.

**`eval`** (`kmet.app.eval.tool`, mounted by the `kmet.app.eval.plugin` row)
is the persistent counterpart. It shares `kmet.app.tools.script` — capability namespaces, capture,
deadline/abort and result assembly — but not the tools bridge, and the state
belongs to the session, not the call: `kmet.app.eval/value` owns one SCI
context per session (built lazily on the first call), its interrupt holder,
per-call namespace and call lock. `seed!` installs the call's namespaces into
that context each time, while definitions stay. The sandbox has no `tools`
namespace, so an eval call cannot invoke a tool.
A definition from one call is visible to the next in the same session. Every
call starts in `user`; a namespace a call enters holds only for that call, so
the next call starts in `user` again. Because a call that defines a verb — persisted or live-only — makes
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
the session and is not written; `(help :library)` lists it as
session-only. A written definition's result line names its name and scope
only; `(describe 'name :file)` names its file. A `defmethod` is written
with its loaded multimethod, since a method has no docstring slot. Existing
`<agent-dir>/eval` files are the global layer; nothing is moved
automatically. The key is the
shared injective session-dir encoder, pinned when the environment is built: a
later
call's `:cwd` still resolves file verbs and sources, but never moves the
library root. Global loads first, then cwd; a name's stack is `[global cwd]`
and the last record is live, so cwd shadows global. New names default to cwd;
redefining an active name keeps its layer and its file. New names default to
cwd; `(move 'name :global|:cwd)` moves a saved definition between the
layers. The model-facing eval tool has no `:scope` or `:drop` parameters; the
sandbox verbs and `/eval` carry those operations. A session's first eval scans and evaluates both layers; a
new session or a restarted process loads them, while an already-built
environment is a runtime cache that refreshes only on an explicit
`/eval reload`. The reload builds a fresh sandbox, replays the session-only
definitions, and keeps the old environment when a file fails to load. A file
that parses but fails to evaluate is still indexed under its parsed addresses
as a failed record in its layer; its error is reported on the first eval, the
prompt and help show `N saved (L loaded, K failed)`, and redefining one of its
names rewrites that file and clears the failure. Sessions
share the files but not the environment. `(forget 'name)` and `/eval
drop` target the active record by default; a scoped drop targets either layer.
Drop/forget resolve an unqualified name in the namespace the call ends in.
Dropping a record deletes its actual `:file`, unbinds the live name, and
re-evaluates the next record
(restoring a shadowed global); dropping a non-active record deletes only its
file and leaves the live definition. All targets resolve before anything is
deleted, and a file that also defines other addresses must be requested with
all of them (a partial file is refused). A mounted source's shadow is
reported as `restored`, not `forgot`. A rejected drop leaves the call's
definitions live but unwritten, like any other change failure. A code-less
`/eval drop` needs no environment: it reads the disk index and resolves cwd
first, or an explicit scope.
`(move 'name :global|:cwd)` and `/eval move <name> global|cwd` move an
active definition without
re-evaluating; they refuse collisions with a loaded target, same-scope moves,
and source-provided or unwritten targets, replace a failed target, take both
root locks in the fixed global-then-cwd order,
and roll back the target if the source delete fails. The `:tool/eval` row
registers the eval tool and `/eval` (`list`, `index`, `describe`, `drop`,
`move`, `reload`, `sources`, `help`, plus verbs an eval source contributes)
as its two effects, so removing the row removes both. The verb spellings,
arguments and summaries live in one table (`kmet.app.eval.command-verbs`);
dispatch, help, the argument hint, completion and the source reserved-name
guard read it. An unknown `/eval` verb names the nearest spelling before the
unknown-verb hint. The command keeps no
front-end tag and stays engine-portable. `/eval help` and `/eval index` need
a live environment; `list`, `describe`, `sources`, `drop` and `move` also work
from disk and prefix `Eval inactive` to their output. An unknown leading
name that resolves to a definition or source verb is a `describe` shorthand;
an unresolvable one keeps the unknown-verb hint. The system already provides
`read`, `write`, `edit`, `bash`, `glob`, `grep` and `session-recall` (also `core/...`);
the row does not mount a separate eval source for them, and there is no
`tools` namespace. The built-in help index is generated from the verb vars'
docstrings and arglists, so `(help)` follows the call surface instead of a
separate table. `session-recall` calls the same `run-recall`
implementation as the `session_recall` tool.
`bash` is the plain-output shell verb: its result carries the command output
and a status footer, and a nonzero exit is a normal result. Its options add
`:cwd`, `:max-lines`, `:max-bytes` and `:as :map` (exit code, shown output,
truncation and spill path; failures also return the map). The namespaced
`babashka.process/sh` remains for callers that need the process map
(`:out`, `:err`, `:exit`). `help` and `describe` are installed in
`clojure.core`, so they resolve unqualified and a model definition can shadow
them. The alias prelude refers the core verbs unqualified, so `(read ...)` and
`(core/read ...)` are one call. `read` accepts `:offset`/`:limit` or
`:start`/`:end` (inclusive, 1-indexed), `:tail`, and `:as :lines`, returning
`{:path :start :end :total-lines :lines [{:n :text}] :truncated?}`; a bounded
read omits the continuation footer by default and `:as :lines` pages at 400.
The options are verb-only — the `read` tool keeps pi's raw-text contract.
`glob` and `grep` are the file-search verbs. `glob` lists paths matching a
glob pattern; `grep` searches file contents and returns
`{:matches [{:path :line :text}] :skipped :skipped-count :truncated? :files}`.
Inside a git work tree both use git's file list, so `.gitignore` is
honored (an explicitly named ignored directory is still searched); outside
git they use the shared safe traversal. `grep` skips binary and oversized
files instead of decoding them, and both accept `:exclude` globs. Patterns
and excludes are compiled by `kmet.libs.glob`; it matches `/`-separated paths,
with `*` within a segment and `**` across segments. The verbs validate patterns
as text, never as filesystem paths (Windows rejects glob metacharacters in a
path), and normalize native separators before matching; `/` works on every
platform and Windows also accepts `\`. Patterns, `:glob` filters and excludes
must be relative: rooted, drive-qualified, UNC and `~/...` forms belong in
`:path`, not a pattern. Result paths use `/` on every platform.
The same prelude refers the eval verbs (`library`, `sources`, `index`, `save`,
`forget`, `patch`, `edit-form`, `move`, `result`), so the sandbox has one
unqualified call surface. `clj` verbs stay namespaced (`clj/edit-form`), and a
mounted source whose values live
under `ext.<id>` is called as `<id>/verb` (`mcp/search`) while its values keep
their internal `ext.<id>` home. The
clojure extension's tools wrap the shared
`kmet.libs.clj-edit` engine; an eval script can use the direct `clj/edit-form`,
`clj/replace-sexp` and `clj/paren-repair` verbs without the extension. The
`clojure_eval` tool shares `kmet.libs.clj-eval` with the direct
`clj/nrepl-eval` verb, so nREPL evaluation likewise needs no extension. The
eval tool's description and `(help)` name the shared script surface
(aliases, require-able namespaces and bundled libs) without depending on
`codemode`, since either row can be selected alone. The host-specific half is
`(help :surface)`: the environment records the base namespace map and the

[Showing lines 1-748 of 1329 (50.0KB limit). Use offset=749 to continue.]