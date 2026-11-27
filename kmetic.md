# kmetic — design record

**Status:** design record for a second, smaller, opinionated agent built on the
same kernel as kmet. Not implemented. This file records the decisions made so
far and the questions still open; it is not a task list for this repository.

The one-line version: **a server-first agent whose only model-facing tool is
`eval`, whose extensions are trusted plugin rows, and whose engine is a fork of
kmet's, not a dependency on kmet's app.**

## 1. Position

| | kmet | kmetic |
|---|---|---|
| Front end | TUI first, in process | server first; TUI, web and GUI are clients |
| Model surface | many tool rows | one tool: `eval` |
| Extensions | SCI-isolated extension host, ABI, manifests | plain plugins (srce rows), no sandbox |
| Composition | profiles/bundles/patches, generated catalog | one row vector + bundle vectors |
| Theming | theme registry | none; fixed styling |
| Process model | TUI process, RPC/server are modes | one daemon; every front end attaches; many sessions across directories |

kmetic is not a kmet profile. The ownership model, the protocol and the tool
surface differ enough that a profile would fight the app.

## 2. Shape

- **The server is the engine and a multi-workspace daemon.** One process owns a
  session registry, agents, jobs and plugins. Sessions live in different
  directories; the server is not tied to its startup cwd. Clients (TUI, web,
  GUI) speak one wire protocol and may attach to several sessions at once.
- **A session is the durable log; an agent is the live run.** One agent per
  session, **N attached clients**. The server serves many sessions concurrently;
  a session carries an agent only while it is live, and agents are created
  lazily. This is the deliberate departure from kmet's one-agent-per-connection
  server.
- **Three scopes, never confused:**
  - *process* — plugins, providers and credentials (one principal), the session
    index, process-wide defaults;
  - *session* — cwd, settings overlay, model, log, eval environment, jobs,
    subagents, approvals and the agent;
  - *connection* — the client id, its subscription set and UI state.
- **One model-facing tool: `eval`.** The verb table, not a tool schema, is the
  model's API.
- **Everything is a plugin row.** Built-ins, bundled extensions and third-party
  plugins use the same shape.
- **MCP is built in**, not an extension: a core service, with its tools exposed
  as eval sources/verbs.
- **Subagents and background tasks are eval verb families**, not tools.

## 3. Non-goals

- No extension host, per-extension SCI context, capability map, `extension.edn`,
  allowed-namespace ABI, or `kmet.loader` dependency. Extensions are plugins.
- No theme system. No keybinding-manager abstraction beyond the TUI's needs.
- No profiles/bundles/patches/catalog in v1. A plugin bundle is a vector of
  rows; composition is that vector.
- No remote bind before process-level isolation. Loopback plus a token first.
- No multi-user principals in v1.
- No second registry beside srce services. No row flag or service key without a
  reader.
- No copying of `kmet.libs.*`, `kmet.ai.*`, `kmet.srce.*`, `kmet.tui.*` or
  `kmet.tk.*`; depend on the whole projects.
- No reuse of `kmet.ui.*`; its TUI is bound to in-process app services.

## 4. Dependency posture

A repository is the unit tools.deps resolves. kmetic can reference **whole
projects only**, never a module inside the kmet repo (a subdirectory coordinate
would couple kmetic to kmet's history; do not use it).

### Depend on (whole projects)

| project | role |
|---|---|
| `kmet/srce` | the ctx kernel: services, inject, fibers, effects, events |
| `kmet/libs` | http, json, sse, oauth, edn-store, concurrent, log, process, mcp, clj-edit, ranking |
| `kmet/ai` | providers, auth, models, message and usage contracts |
| `kmet/tui` | TUI components/renderer, used by the TUI client |
| `kmet/tk` | Tk backend, used by the GUI client |

`kmet/loader` is not a dependency: there are no SCI extension contexts and no
hot-loaded artifacts. Eval uses SCI directly.

### Fork (copy) the engine

The engine is copied from a recorded kmet SHA and renamed to `kmetic.*`. It is a
fork, not a dependency:

- `events.clj`, `wire.clj`, `agents.clj` — event vocabulary and live handles.
- `session/*` — log, store, listing, context, tree, stats; add `:seq` and a
  format version.
- `loop/*` and `loop.clj` — message, state, completion, driver, stages; collapse
  tool execution to the one eval tool.
- `eval.clj` + `eval/*` and `tools/{script.cljc,bash_executor.clj,bash_output.clj}`
  — the sandbox, verbs and output ring. **Trim the surface**: no full
  sources/libraries/persistence/patch stack in v1.
- `sandbox.clj`, `llm.clj`, `model_resolver.clj`, `retry.clj`, `loop_guard.clj`.
- `system_prompt.clj`/`prompts.clj` — trimmed to the resources kmetic actually
  ships.
- `compaction/*` — defer unless a long-run need appears; `vcc.clj` alone is
  1.9k and is not v1.
- `modes/rpc.clj` — reuse the EDN line transport discipline, not the command
  set; the protocol is new.

Do **not** copy `kmet.app.extensions.*` (~4.7k source, ~4.1k tests),
`kmet.extension` (559), profiles/presets/catalog, `config_schema` or the world
loader.

### Prerequisites: two extractions in kmet

1. **Extract `kmet.libs` as a whole project.** It is rank 0 and guarded as
   extractable. Move the tree unchanged; move `test_terminal` to `tui` (the only
   cross-edge in its test tree); move its guards (self-contained, sandbox-safe,
   HTTP boundary, time boundary, exit) with it.
2. **Extract `kmet.ai` against that tag.** It already passes
   `test_self_contained`; move its catalogs and the generate/check tasks with
   it, and make `data-dir` relative to the new project root.
3. Start kmetic against both tags; dev uses sibling `../libs` and `../ai`
   checkouts.

The checks that must not be forgotten when those two leave: root `deps.edn`
pins and `:dev` roots, `bb.edn` paths, `modules.edn`, the five extension
`bb.edn` files, `:jolt/build :embed`, and
`tasks/kmet/tasks/build.cljc`'s `external-src-markers` (a missing marker makes
`bb dist` ship a binary that cannot resolve the library).

## 5. Composition and plugins

### Rows

Composition is a literal vector mounted by a small `mount!` over srce — not a
profiles/bundles/patches language. A row is:

```clojure
{:id :tool/eval                 ; stable, namespaced, the patch surface
 :plugin kmetic.eval/plugin     ; a var: srce plugin {:name ... :apply ...}
 :config {...}}
```

`inject` is the dependency system; row order is not. A child ctx can mount its
own rows (per-agent plugins), but a child cannot shadow a service an ancestor
provides — the kernel refuses a second provider on the chain.

### Extension = bundle, plugin = row

An extension is a distribution unit; a plugin is a lifecycle unit.

```clojure
(ns my.ext
  (:require [my.ext.mcp :as mcp]
            [my.ext.verbs :as verbs]))

(defn plugins
  [{:keys [servers verbs] :as _config}]
  [{:id :my/mcp   :plugin mcp/plugin   :config {:servers servers}}
   {:id :my/verbs :plugin verbs/plugin :config {:enabled verbs}}])
```

- Install names `my.ext/plugins` plus config; the composer expands the bundle.
- A bundle of one is normal. Make the bundle the contract from day one.
- Split a bundle when lifetimes differ, config is independently useful, or a
  row is useful alone.
- Keep one plugin when the parts share state and are never useful apart. A
  private back-channel between rows means it was one plugin.
- Install is atomic (unwind mounted rows on failure); composition is granular
  afterward (remove/patch one row); boot is tolerant (an inactive row is
  reported, the server keeps serving).
- **Plugins extend the interpreter, not the tool schema.** They contribute eval
  verbs/sources, services, modes, MCP servers and jobs — never a model-facing
  tool.
- Plugin UI is data: `:notice`/`:status`/`:custom` protocol events with a
  generic text/EDN rendering. Clients never load plugin code.

### Trust

There is no plugin sandbox. Installation is the trust boundary; execution is
not. The eval sandbox protects against model-written code, not plugin authors.

Operational rules:

- Bind `*out*`/`*err*` away from the protocol stream around plugin load/mount.
- Ban `System/exit` in plugin sources (source guard in plugin repos).
- Contain a failing plugin to its row/fiber; report inactive rows.
- Document that plugins must not block the server's writer thread.
- A plugin can still take the process down. That is what privileged code means.

### Plugin API

The stable surface is **`kmetic.plugin`**: the plugin shape, service keys and
protocols, the public event vocabulary, and eval-source registration. Not the
loop, session or config schema.

- In-tree plugins may require `kmetic.*`; keep internals private by convention.
- The first out-of-tree plugin forces `kmetic/plugin` to become a whole project
  that both the app and plugins depend on. Until then, write the facade down
  and enforce it in the repo's own plugins.
- No compatibility shims. Pre-1.0, a break is a break and in-repo consumers move
  in the same change.

## 6. Eval as the only tool

The verb table is the API. Each verb carries arglists, help text and an approval
class in one place (workspace-local vs. network/outside-cwd); `(help)` and the eval manifest are generated from it.
Otherwise the model cannot discover what it can call.

Initial verb families:

- files: read/write/edit/glob/grep (reuse the sandbox file ops; do not rebuild).
- `bash`/`sh`/process control.
- `mcp/*`: search, describe, call, status.
- `agent/*`: spawn, prompt, wait, result, list, kill.
- `task/*`: spawn, output, wait, kill, list.
- `help`.

Rules:

- Do not port the full kmet eval surface (7.3k LOC and its top churn sink).
  Ship the persistent environment, help, result ring, files and bash first;
  add libraries/sources/persistence only on a second consumer.
- Results stream: `emit` and form values interleave; keep the result ring and
  the output spill.
- The SCI context is the model-code boundary. Process isolation is the next
  tier and waits on a measured need or a second consumer.
- `eval` is not a tool with a giant schema; it is an interpreter with verbs.
  Tool discovery moves into `(help)` and the system prompt.

## 7. MCP, built in

- A core `:mcp` service with one row per server (lazy connect, health, OAuth,
  status), stdio and Streamable HTTP/SSE.
- The model sees MCP through eval verbs (`mcp/search`, `mcp/describe`,
  `mcp/call`), not through per-tool schemas. Discovery stays in the
  interpreter.
- Reuse `kmet.libs.mcp` (client, auth, transports); do not copy the
  mcp-adapter extension.
- MCP servers are external processes, not plugins; a plugin may contribute
  server config, but the client is core.

## 8. Subagents

- `agent/spawn` returns an id immediately; `agent/prompt`, `agent/wait`,
  `agent/result`, `agent/kill`, `agent/list`.
- A child agent runs on a child ctx with its own model, tool/verb subset and
  session file. The child cannot shadow an ancestor service; it can provide
  rows an ancestor does not. A child inherits the session's cwd unless it is
  given an explicit override.
- Only the final result (or a compact card) enters the parent's context by
  default. Child token streams go to clients as `:subagent/*` events.
- Blocking `wait` inside the parent's eval is acceptable v1; an async default
  waits for a measured need.

## 9. Background tasks

- `task/spawn` returns an id; `task/output`, `task/wait`, `task/kill`,
  `task/list`.
- Tasks run in the session's cwd and are owned by the session; disposing the
  session disposes its jobs. Output lives in an in-memory ring; start/exit
  facts go to the session log.
- The session owns disposal. On restart a process is `:lost`; do not fake
  recovery.
- Subagents and tasks share the registry/lifecycle shape, not one
  representation: an agent is an LLM job, a task is a process job.

## 10. Protocol

**Write `PROTOCOL.md` before the first subsystem.** It is the product contract,
and it is what lets three clients exist without three semantics.

Decisions:

1. **Ownership:** a session has one agent and one durable log; clients attach
   to sessions, not to the process. The server owns every run. Prompts for one
   session are serialized; different sessions run concurrently. Steering and
   follow-up queue per session as in kmet.
2. **Multi-session, one connection.** A connection may attach to N sessions
   across N directories. Every command names its `:session`; every record
   carries `:session`. `list-sessions {:cwd?}` returns id, cwd, title (stored or
   derived from the first prompt), model, updated-at and `:running?`;
   `create-session {:cwd, :model?, :config?, :session-dir?}` refuses a directory
   that does not exist; `attach {:session id :since seq}` replays then goes
   live; `detach` drops the subscription without touching the agent.
3. **Lazy agents.** A session exists as a log; the agent is created by the
   first prompt/steer, not by attach. Idle reaping disposes an idle agent and
   keeps the log; a run in flight is never reaped. The next prompt recreates
   the agent from the log and header. A run keeps going when every client
   detaches — the log is authoritative.
4. **Cursor:** every record carries a per-session monotonic `:seq`; entries
   carry stable ids. Replay is per session; reconnect must be boring.
5. **Backpressure:** a bounded per-connection queue; drop oldest and emit
   `:stream-gap`; replay is authoritative, so a gap is recoverable.
6. **Approvals:** broadcast to clients attached to that session; the first
   answer wins; the answer carries the client id. With no attached client the
   default is deny with a recorded reason — never an unbounded wait.
7. **Settlement:** a command failure is a response; a run failure is
   `:agent-settled :outcome :error`. Accepted is not completed.
8. **Stdout is protocol only.** Diagnostics go to stderr. No `println` in the
   engine or plugins.
9. **One agent per session also means one writer:** events are emitted from the
   engine, never from a client.

Protocol conformance tests drive the server with a fake model and a fake client;
every client must pass them. Do not let the TUI define the protocol by accident.

## 11. Session storage

- Append-only EDN log per session, one entry per line, as kmet already does.
- One user-level sessions root by default (`~/.kmetic/sessions`). A server may
  set `:session-dir`, and `create-session` may override it per session, to use a
  project-local store. The header records the id and `:cwd`, so listing spans
  workspaces and any client can attach to any session.
- A session's cwd is fixed at creation in v1; moving a session between
  directories is not a supported operation.
- The header carries a **format version from v1** and its `:cwd`; no migration
  machinery.
- Add `:seq` and stable entry ids for client replay.
- No second store. Jobs, subagents and approvals are events.
- **The process cwd is not a session cwd.** Every path in the engine resolves
  from the session's `:cwd` — files, bash, eval, project settings, context
  files. Nothing reads `user.dir` or calls `fs/cwd` as a fallback.
- An abrupt exit loses nothing already appended; a crash during a tool call is
  an open attempt, not a phantom delivery.

## 12. Clients

Order matters:

1. **TUI** over the wire — prompt, transcript, cancel, approval, reconnect.
   Reuse `kmet.tui` components; write the renderers and the client fresh. Never
   reuse `kmet.ui.tui`.
2. **Web** — thin client, read + prompt first; only after the event contract
   survives a second renderer. Loopback + token until process isolation.
3. **tk GUI** — same protocol, no engine changes.

A client's state is per attachment: one TUI can show several sessions (tabs)
and one connection can carry several attachments. Do not bind a client process
to one session.

One binary with subcommands is the nicest distribution: `kmetic` serves by
default, `kmetic tui`/`web`/`gui` attach, and a client may auto-spawn a server
when none is listening. On Termux, prefer a host with unix sockets (BB/JVM) for
the server; Jolt lacks them and falls back to TCP loopback.

## 13. Build order

| step | deliverable | acceptance |
|---|---|---|
| 0 | `PROTOCOL.md` + conformance skeleton | a fake client replays a fake run |
| 1 | `kmet/libs` extracted and tagged | three hosts green; kmet `bb dist` |
| 2 | `kmet/ai` extracted against the libs tag | self-contained guard; catalogs move |
| 3 | kmetic skeleton: rows, plugin mount, eval, session, stdio | prompt → eval → response headless |
| 4 | server: socket, auth, session registry, lazy agents, multi-attach | two sessions in two directories run concurrently; attach replays each independently; reconnect mid-run; gap recovery |
| 5 | TUI client | real transcript over the wire; approval round-trip |
| 6 | tasks, then subagents | ids, output rings, kill, lost-on-restart |
| 7 | MCP built in | lazy connect, OAuth, verbs, `/mcp`-style status |
| 8 | web, then tk | same conformance suite |
| 9 | side-by-side benchmark | `scripts/bench` on eval-heavy tasks |

## 14. Guards and budgets

- **File/CC budget from commit one**: no source file over ~500 LOC, no function
  over CC 10. kmet is paying for this guard at 90k SLOC; it is free at 5k.
- **Require guard**: every `kmet.*` require is a whole-project prefix
  (`kmet.srce.*`, `kmet.libs.*`, `kmet.ai.*`, `kmet.tui.*`, `kmet.tk.*`); any
  `kmet.app.*` or `kmet.ui.*` fails. Rename the fork to `kmetic.*` so the guard
  is trivial.
- **Plugin contract test**: a conformance plugin uses only `kmetic.plugin`.
- **Protocol conformance test**: a fake client and a fake model, independent of
  the TUI.
- **Generated docs**: protocol reference and row/service map generated; hand
  prose only for invariants and decisions.
- **Cwd guard**: no engine source reads `user.dir`/`fs/cwd`; every path comes
  from the session. A test runs two sessions in different directories and
  asserts each tool call resolves in its own tree.
- **Session format version** checked by a test.
- No warnings: lint and format are gates, not suggestions.

## 15. Size targets

| piece | target |
|---|---|
| server + engine copy (trimmed) | 12–18k LOC |
| TUI client | 3–5k |
| web client | 2–3k |
| GUI client | 2–3k |

A subsystem that pushes past its budget earns a module or waits for its second
consumer.

## 16. Open questions

1. **`kmetic/plugin` timing** — extract at the first out-of-tree plugin, or
   earlier if a second team needs it. Write the facade now either way.
2. **Plugin API contents** — service keys, event vocabulary, eval-source
   registration; what exactly is stable.
3. **Compaction** — copy kmet's queue/stage, defer to a measured long-run need,
   or write a smaller summarizer. `vcc.clj` is not v1.
4. **Approval routing** — broadcast/first-wins is the default; whether the
   initiating client gets a veto or a timeout is undecided.
5. **Subagent execution** — blocking `wait` in v1; whether spawn should stream
   into the parent transcript or only return on demand.
6. **Web isolation** — the web client is the point where a local tool becomes a
   network service; decide the process-isolation tier before exposing it.
7. **Catalog ownership** — the ai project owns generation and the committed
   catalogs; kmet/kmetic keep only the runtime user-cache path.
8. **Config** — one EDN file + env + flags, parsed in one place; the exact
   schema is v1 work.
9. **Hot reload** — `require :reload` + dispose/reapply is possible without the
   extension host; whether it is a feature or a dev-only trick is open.
10. **MCP sharing** — one client per server config shared across sessions, or a
    client per session; which settings are process-wide vs. per-cwd.
11. **Session directory policy** — global root with `:cwd` in the header is the
    default; whether a project-local `.kmetic/sessions` should win when present.
12. **Plugin renderers** — recommend no: plugins emit data events, clients render
    generically.

## 17. Relationship to kmet

**Keep from kmet:** the srce kernel and the row model; the design-record habit
(including a "Not doing" section); the test gates and changed-file loop; the
append-only session log; the eval sandbox and result ring; `kmet.libs` and
`kmet.ai` as whole projects.

**Drop:** the extension host and ABI, the loader dependency, profiles/bundles/
patches/catalog, themes, the god files, and the assumption that the front end is
the engine.

**Fork hygiene:** record the source SHA in `FORK.md`, list the copied
namespaces, and cherry-pick behavior fixes only. If a subsystem later proves
shared by kmet and kmetic, extract it from the simpler side as a whole project —
after the second consumer exists, not before.

## 18. The one-sentence test

If a proposed feature needs a second registry, an ABI allow-list, a manifest, a
tool schema, or a client that knows engine internals, it is wrong for kmetic:
the answer is a row, an eval verb, or a protocol event.
