# Todo

## Task: docstring-gated eval saves (2026-11-06)

Status: done (2026-11-06). Supersedes the save-contract note in the persistent
eval plan: a new top-level definition is written only when it carries a
non-blank docstring; an undocumented one stays live for the session and is listed as
session-only. A `defmethod` is written with its loaded multimethod, since a
method has no docstring slot. A redefinition of a saved name without a
docstring is not written, so the saved file keeps its documented version.
The tool description, prompt guidelines, `(eval/help)` and the `:eval-library`
manifest state the rule; `docs/architecture.md` is authoritative.

## Task: Windows process-cancel tests (2026-11-06)

Status: todo. The sandbox's process-cancel tests (`sh`, `shell`, background
`process`) skip Windows via `when-not kprocess/windows-os?`, so the
`taskkill /T` branch of `kmet.libs.process/kill-process-tree!` and the
watcher's cancel-kill are untested there. Add a Windows-safe child-process
fixture (e.g. `cmd /c ...` with a delayed marker file) covering `sh`,
`shell`, a background `process`, and a timed host-blocked `codemode`/`eval`
call ending on Escape rather than at its timeout.

## Task: eval library interface discovery (10a, 2026-11-05)

Status: done (2026-11-05). This was the interface half of persistent-eval
step 10, pulled out and moved ahead of the cwd-scope task: it is independent
of scopes and avoids a second update of the exact `(eval/library)` maps the
scope work rewrites. The prompt-start manifest half (10b) landed in its own
task after the cwd-scope work.

The model must see the interface, not just a name: a saved definition exposes
its docstring and arglists, `(eval/describe 'name)` reads the interface and
implementation without a file, and built-in and extension-provided verbs carry
the same metadata.

### Changes

1. **Definition interface.** `kmet.app.eval.definitions` extracts `:doc` (the
   docstring, else attr-map or symbol metadata) and `:arglists` (for `defn`,
   `defn-`, `defmacro`, and the tail of `defmethod`) at parse time. Carry both
   through `eval-files/index`, `kmet.app.eval/library-entry`,
   `(eval/library)`, `(eval/help)` and `/eval list`. No evaluation and no
   migration: existing files re-parse and gain docs when their source has
   them.
2. **Source verbs.** A value in a source's `:ns`/`:macros` map may carry
   `:doc`, `:arglists` and `:source` metadata — the keys a var uses, plus the
   implementation text for a verb that has no library file. `normalize-source`
   keeps them, `source-var` puts `:doc`/`:arglists` on the SCI var, and
   `(eval/sources)`/`(eval/help)` list each contributed verb with its doc and
   arglists. Add the `:core` metadata for `user/edit`, `user/write-file`,
   `clj/edit-form`, `clj/replace-sexp` and `clj/paren-repair` (source for the
   wrapper); update the extension example to show `:doc`, `:arglists` and
   `:source`. A value without metadata still works and lists as
   `(no docstring)`.
3. **`(eval/describe 'name [key])`.** One lookup for the interface and the
   implementation. With no key or `nil`, return
   `{:name :kind :doc :arglists :file :scope :source}`; with a key return that
   field's value, e.g. `(eval/describe 'name :source)`. Resolve library
   definitions and source-provided keys, qualifying an unqualified name
   against the sticky namespace; a `defmethod` address and each name of a
   multi-name `declare` work as elsewhere. `:source` is the stored defining
   form for a written definition (no generated `(ns …)` header), the source's
   `:source` metadata for a source-provided key (nil when not supplied), and
   the source from the retained in-memory record for an unwritten live
   definition; `:file` is nil for source-provided and unwritten entries.
   `:kind` is the defining form's kind or `:verb`/`:macro` for a
   source-provided value. Keep the whole
   record of an unwritten live definition (a failed write or a call that ended
   in a normal error) in an `:unwritten-records` map beside `:unwritten`; a
   successful write or drop clears it. The plural `(eval/sources)` keeps its
   current meaning (the registered eval sources); document the distinction.
   Reserve `eval/describe` in `kmet.app.eval.sources/reserved-source-keys`;
   mention it in `(eval/help)`.
4. **Save contract.** The eval guideline asks for a docstring; a saved
   definition without one still succeeds, but its result line says
   `(no docstring)`. An undocumented entry lists kind/arglists, or
   `(no docstring)` when neither exists.
   `(eval/describe 'name :doc)` is nil.
5. **Tests.** Extraction per form (docstring, attr-map, symbol metadata,
   multi-arity, `defmethod`); `(eval/describe 'name)` with no key and with
   `nil` returns the full map, each key returns its field, and an unknown
   name errors; `:source` covers a loaded definition, a redefinition, a
   `defmethod`, a multi-name `declare`, a source-provided key with `:source`
   metadata, a source-provided name without it (nil), and an unwritten live
   definition from a failed write/errored call (cleared by a successful
   write or drop); source `:doc`/`:arglists` metadata on the SCI var and
   `:source` on the contributed value; an undocumented value lists as
   `(no docstring)`; exact `(eval/library)` maps updated once, ahead of the
   cwd-scope step that rewrites them.
6. **Docs.** `docs/architecture.md` eval section, the eval tool prompt/help,
   `modules/app/src/kmet/extension.md` and `docs/extensions.md` (the per-verb
   metadata example), and the Discovery note in the persistent-eval plan.

### Non-goals

- No prompt-start `:eval-library` custom message, sync, or first-eval loaded
  report: 10b, after the cwd-scope task.
- No scope labels in the listing; the cwd task adds `:scope`/active/shadowed
  in its step 7, and 10b consumes them.

## Task: cwd-scoped eval library (2026-11-05)

Status: done (2026-11-05). Step 0 (the shared cwd key), the eval scopes in
steps 1–9, and step 10a (interface discovery) are landed. Supersedes the
one-global-library decision below; that decision stays as the step-4R
history.

The eval environment keeps one durable visibility layer per session cwd, with
the home library as an explicit global layer. One definition per address is
live: cwd when present, else global. No project-root detection and no scratch
scope.

### Why

The observed eval-library usage is ten analysis helpers created in a
`kmet-agent` session. They were created for one session's analysis but went
into the one home library; the model then treated them as scratch and deleted
them. A single global library has no safe default for a helper that is useful
for a few calls but should not follow every project. Cwd scope is the same key
`.kmet/` and session storage already use, and it removes the non-project
fallback to global.

### Model

```
~/.kmet/agent/eval/                               ; global, explicit only
~/.kmet/agent/eval-scopes/<shared-cwd-key>/       ; cwd, default for new names
```

- Every session has a cwd scope. A session without a project still writes to
  its cwd scope; it never falls back to global.
- Global is explicit for new names: `:scope :global` on an eval call, or
  `eval/move`. Redefining an active global name keeps it global.
- The cwd key is the normalized absolute cwd at environment build, produced
  by the shared, unambiguous encoder from step 0. The same key names the
  session directory and the eval scope; there is no second scheme.
- Pin the scope when the session's eval environment is built. A later call's
  cwd must not move the library root; `:cwd` still resolves the call's file
  verbs and sources.
- One live layer per name: cwd if present, else global. Shadowed records stay
  addressable for drop/restore but are not live.
- Storage stays under the agent home. `<cwd>/.kmet/eval` stays ignored:
  project-supplied code is not loaded and there is no project-trust gate.
- No scratch root or scratch namespace. Local `let`/`fn` bindings remain the
  only throwaway scope; a top-level definition writes to the cwd scope.

### Precedence and addresses

- Load global first, then cwd; cwd shadows global by definition address.
- `:library` is a per-name stack in precedence order `[global cwd]`; the last
  record is active. Keep shadowed records with their source so a drop can
  restore the next layer.
- No scope-qualified name syntax. Names stay `ns/name` and
  `ns/multi dispatch`; scope is an explicit `:scope` option.
- Unqualified reads, redefinitions, drops and forgets resolve the active
  layer (cwd first, then global). `:scope` targets a specific layer.
- Writes: a new name defaults to cwd; redefining an existing active name
  keeps its current scope. For an existing name, `:scope` is accepted only
  when it names the active layer; a new name may choose either. Defining,
  patching, editing or moving a non-active layer is refused.
- Drop may target either layer. Dropping the active record deletes its file,
  unbinds the live name, and re-evaluates the next record in the stack.
  Dropping a non-active record deletes only its file, and the active
  definition stays live. This holds with or without an environment; a
  no-environment drop resolves the active file (cwd first), and `:scope`
  selects the other.
- `:failures`, `/eval list|search|drop|help`, `(eval/library)` and
  `(eval/help)` report `:scope` and whether an entry is active or shadowed.
- A broken cwd file is reported and skipped; the global record that loaded
  first stays active. A broken global file is reported and leaves the cwd
  layer, if any, active.
- Load-order limits: global definitions cannot depend on cwd definitions;
  shadowed globals still run their top-level side effects; moving a global
  definition to cwd can break other global files. A shadowed `defmulti` is a
  documented limit: dropping its shadow re-evaluates the next record for that
  address, but methods from the other layer may need a rebuild.

### Changes

0. **Shared cwd key (prerequisite).** Make
   `kmet.app.session.store/encode-cwd` injective and reversible for both
   session dirs and eval scopes:
   - normalize to an absolute path (`expand-home`, `absolutize`, `normalize`),
     separators as `/`, strip the leading `/`;
   - escape `~` to `~~`, `-` to `~-`, `:` to `~:`, then `/` to `-`;
   - wrap as `--<encoded>--`; add `decode-cwd`.
   The current encoder stays as `legacy-encode-cwd` for reading old session
   dirs only. New sessions use the new key; `find-recent-session` checks the
   new dir and the legacy dir and returns the newest match; `list-sessions`
   and `list-sessions-info` already walk both. No automatic rename. Eval has
   no legacy scopes. Update `test-encode-cwd`, the `session-dir-for-cwd`
   expectations, and `docs/pi-alignment.md` (a named departure from pi's
   `<--cwd-->` layout).
1. Root resolution: `library-root` becomes both roots;
   `kmet.app.eval/value` takes the session cwd/base input; `build-environment`
   stores the pinned `:cwd`, roots and scope key; `ensure-environment!` keeps
   the first build's pin. Use the shared encoder for `eval-scopes`.
2. Data model: `:library` becomes a per-name stack; add an active resolver
   and update `known-name?`, `definition-target!`, `drop-one!`,
   `apply-definition!`, `commit-definition!`/patch/edit-form, `service-index`,
   `eval-files/scan`/`index` and `library-entries`. Keep each record's source
   and root.
3. Writes: new name to cwd; existing active name to its current scope;
   explicit `:scope` for a new name or the active layer; non-active
   write/patch/edit-form is refused. `apply-definition!` selects the target
   root and lock (not `(:root env)`) and reports the scope in lines and
   `:details`.
4. Drop/forget: unqualified means active; `:scope` is optional; a
   no-environment drop searches both roots and resolves cwd-first instead of
   refusing ambiguous names. Dropping the active record deletes the file,
   unbinds the live name and re-evaluates the next record; dropping a
   non-active record deletes only its file. Source and unwritten behavior is
   unchanged.
5. Patch/edit-form: active only; `:scope` is optional; non-active,
   source-provided and unwritten targets are refused; write through the
   record's root lock; keep the re-evaluate-then-write contract.
6. New `eval/move` and `/eval move <name> global|cwd`: active only, no
   `--force`. Add `eval-files/move-definition!`: take both root locks once in
   fixed order (global then cwd), publish the target, delete the source, and
   roll back the target if the delete fails. Update `:file`/`:scope` without
   re-evaluating; refuse collisions, same-scope moves, source-provided and
   unwritten definitions. `/eval move` must work without building an
   environment. Add `eval/move` to the reserved source keys and `move` to the
   reserved command names.
7. Index and commands: `:index` takes `{:cwd …}`; list/search/sources pass
   it and mark entries `(cwd)`/`(global)` and active/shadowed. Add `:move!`
   to the service map and the architecture catalog. `(eval/help)` shows the
   pinned scope and the move verb. Add `scope` to the eval tool schema and
   validate it; a code-less call may carry only `drop` plus `scope`.
8. Tests: update the existing eval suite (paths, exact `(eval/library)` maps
   — now including 10a's `:doc`/`:arglists` — one-arg `write-definition!`,
   command helpers). Add coverage for the shared
   encoder round-trip and collision cases, legacy session-dir fallback, new
   dir keys, default cwd for new names, existing-active scope retention,
   explicit global and `:scope` on a new name, cwd shadow load, library
   stack/scopes/active, drop active restoring the global, drop non-active
   leaving the active live, no-environment list/drop across both roots, a
   pinned cwd after a later call with another cwd, patch/edit-form non-active
   refusal, source/unwritten refusal, broken cwd shadow versus global,
   defmethod shadows, move success/collision/no-force/same-scope/source
   refusal/delete-failure rollback/lock order, legacy global files, restart
   loading both layers, and worktree/subdirectory separation. Run both host
   views.
9. Docs: `docs/architecture.md` (service catalog, eval section, session
   layout), `modules/app/src/kmet/design.md`,
   `modules/app/src/kmet/extension.md`, `docs/extensions.md`, the eval tool
   prompt/help, `docs/pi-alignment.md` (session-dir departure), and this
   file's E4/E18 notes. Replace the one-home library wording with the
   global+cwd model; keep `KMET_CODING_AGENT_DIR` as whole-home isolation.

### Migration

No automatic eval move. Existing `~/.kmet/agent/eval/<ns>/<name>.clj` files
stay the global layer; new definitions write cwd scopes, and redefining an
active global name keeps it global. Promote a helper with `/eval move`.
Session dirs use the step-0 key for new sessions; legacy dirs stay readable
and are found by `find-recent-session`, with no automatic rename. Worktrees
and subdirectories are separate cwd scopes by design; symlinks are separate
because the key uses the normalized path, not `realpath`; helpers do not
follow across them. `KMET_CODING_AGENT_DIR` still isolates the whole home.

### Non-goals

- No `:keep` metadata, no project-root detection (git common dir, manifest),
  no `<cwd>/.kmet/eval`, no scratch root, no session-definition entries, no
  snapshots or branch fingerprints.
- No change to `codemode`, the source registry, or extension-provided verbs
  beyond the reserved `move`/`eval/move` names.
- No `--force` moves and no scope-qualified definition names.
- No automatic session-dir rename or `/session migrate` in this task.
- No automatic cleanup of `/tmp` or stale cwd scopes in this task.
- No deeper `defmulti` method restoration across layers than re-evaluating
  the matching record; document that limit.

## Superseded decision: one global eval library (2026-11-05)

> Superseded by [the cwd-scoped task](#task-cwd-scoped-eval-library-2026-11-05).
> The bullets below describe step 4R as landed; they are no longer the target
> model.

The eval scope model is collapsed to **global only**. One library per agent
home, auto-written:

```
~/.kmet/agent/eval/<ns>/<name>.clj
```

- **Define = write.** A successful top-level definition writes its file
  immediately (nodecode's "defining is keeping"). No `:keep`, no
  `<project>/.kmet/eval`, no session entries.
- **Load = scan.** An environment is built by reading the library. No fold,
  snapshot, branch fingerprint, or project overlay.
- **Sessions are runtime caches** over the one library. Resume, fork and
  branch do not rewind definitions; a new session or a restarted process
  loads the files, and an already-built environment never refreshes.
- **Forget = delete.** Remove the file and unbind the live name; a flat
  `:drop` or `(eval/forget 'name)` is enough.
- **`codemode` is the throwaway tool; `eval` is the library.**
- **Isolation is a home**, not a scope: `KMET_CODING_AGENT_DIR` (or a
  profile) gives a separate library.

This supersedes the session/project scope rules of step 4 and the
project/global material it replaced. It landed as **step 4R** (see § Steps);
the test plan, limits and decisions below state the step-4R model.

Reference: nodecode keeps one layer per home (`~/.nodecode/cells/<identity>/`),
one file per definition, and has no session or project scope for code; its
only project filter is metadata on memories/skills, not loaded definitions.

One track remains: persistent eval. Landed work — the `github-copilot`
anthropic auth, the prompt-caching parity pass, the around tool hook, the
remaining openai-compat keys, **P5** (tool exposure, the declared/callable
split, the shared ranker and `tool_search`), **P5.5** (the declared order and the one promotable
derivation), **P6** (mid-conversation tool changes without a full re-prefill),
**P6 residuals 1–3** (Codex's declared-set markers, the anchored wire deltas,
and `tool_search`'s recorded-loadout filter), the P6 unmount/remount residual,
**S1** (config and resolution for `:enabled-tools`), **S2** (the extension
ABI for tool selection), **S3** (the `/tools` scope and save flow), **S4**
(the retired tool rows), **S4.5** (the opt-in built-in defaults), **S5**
(the tool selection docs) and **S6** (the tool-selection review and simplify)
— is out of this file; it lives in git history. The context and token tracks
are dropped too — see § Deferred.

| track | what it fixes | status |
|---|---|---|
| Persistent eval and eval sources | `codemode` throws its context away per call, so a helper the model writes is re-pasted in full on every turn; extensions can contribute tools but not a definition the model can call from code | steps 1–9, 10a and 10b done; cwd-scope task done |
| Eval library interface discovery (10a) | saved definitions and source-provided verbs expose an interface and implementation through `(eval/describe)`, not just names | **done** (2026-11-05) |
| Cwd-scoped eval library | the one-home library makes every helper global; a model cleaning up "scratch" deletes user state | **done** (2026-11-05) — cwd default, explicit global, shadow/drop, move, pinned scope |
| Clojure extension surface review | the extension's tools now overlap E17's core editing engine and its eval `clj/*` verbs; decide the remaining surface from usage | **measuring** — description and skill trimmed 2026-11-05; eval cwd-scope and 10b done, so the decision gate is open |

## Persistent eval and eval sources

Status: steps 1–9 done (the shared script layer, the file ops, the one-home
library, the source registry and ABI, `eval/patch`, the shared Clojure editing
engine with its core verbs, and the docs/catalog pass); step 10 (the interface
and source-verb docs, the discovery manifest and first-eval report) and the
cwd-scope task are done. The scope
material below is step-4R history; the cwd-scoped task above supersedes it,
while the sandbox, source and editing material still applies. The tool is opt-in
by default under S4.5; step 4R's tests exercise it directly rather than through
the default declared set. E17 moved the Clojure editing engine and the edit
verbs into core; the rest of the clojure extension is a post-track task at the
end of this section.
Decisions closed (E1–E18); independent.
Independent of every other track; the `:on-tool` hook covers it for free (step
1 keeps the bridge on `kmet.app.tools.invoke`).
Reference: nodecode (`~/src/cvstree/nodecode/`) — an SBCL agent whose one tool
evaluates Lisp in the live image and keeps every definition
(`docs/README.md` § The layer, `docs/CELLS.md`, the `eval` page on its site).

### Why

`codemode` builds a fresh SCI context per call and discards it on return, on
purpose (`docs/architecture.md` § Tools and the script sandbox). Its economy
is output: compute in code, send a digest, never re-send the data — measured
at 689 calls, 98% of which never touch the bridge. It has no answer for the
other half of code-mode work: code the model wants to *reuse*. A parser for
this repo's odd log format, a `report` fn that renders the project's state, a
scratch namespace — today each is re-pasted in full on every turn, and the
only way to keep one is to write it to a file and `load` it through the
sandbox's require gate, which refuses everything outside the fixed namespace
surface.

Nodecode's `eval` is the existence proof of the alternative: one live image,
one file per definition kept on disk, loaded at the next start, so the agent
grows a library instead of re-typing one. Its cells are the second half: an
optional folder that teaches the agent something — tools, skills, prose —
and is loaded because it is present.

kmet is not one process with one image. Agents are child contexts inside one
process, extensions are isolated and contribute by value, and the sandbox
boundary is a realm around a tool call. So the port takes three shapes that
the original does not need:

- **per home, not per process.** The library belongs to the agent home; a
  session's environment is a runtime cache over it. A new session or a
  restarted process loads the files, and a subagent in the same home sees them.
- **definitions persist by default; isolation is a home.** Every successful
  top-level definition is written immediately, and the reviewable file is the
  record. `KMET_CODING_AGENT_DIR` (or a profile) gives a separate library.
- **extensions contribute definitions the way they contribute tools** — a
  registry tag, an owner path, one unload sweep. No new ABI shape.

### What exists today

| piece | today | this track |
|---|---|---|
| script sandbox | `kmet.app.tools.codemode`, one SCI ctx per call, ~1,000 lines of machinery | moves to `kmet.app.tools.script`; both tools use it |
| file ops | inline in `kmet.app.tools.edit` / `.write` `execute` | extracted as ops; tool and verb share them |
| per-agent services | `:shell` (atom), `:sandbox` (data), `:permissions` (memory) — the agents factory provides each | `:eval` joins them, with one environment per session over the home library |
| extension contribution | `register-tool!`, `register-tool-source!` (`:kmet/tool-source` on the `:tools` handle), commands, skills | `register-eval-source!` (`:kmet/eval-source`), same handle, same sweep |
| model's code tool | `codemode` only | `codemode` + `eval` |

`codemode`'s per-call context costs roughly 3 ms of namespace copying plus
9 ms of `sci/init` (its own comment); a per-session context pays that once
per session instead of once per call.

### Shape

Three tiers of what a script can call, and only the third is a source:

| tier | provided by | examples | removable |
|---|---|---|---|
| primitives | `kmet.app.tools.script`, shared with `codemode` | `slurp`, `spit`, `sh`, `p/*`, `fs/*`, `json`, `emit`, `emit-image`, `tools/list`, `tools/describe`, `tools/call`, `(sandbox/cwd)`, `(sandbox/env k)`, `(sandbox/now)`, `(sandbox/sleep ms)`, `(sandbox/spawn f)` | no |
| eval vocabulary | the `:eval` service (it needs library state) | `(eval/library)`, `(eval/sources)`, `(eval/help)`, `(eval/describe …)`, `(eval/forget …)`, `(eval/patch …)`, `(eval/edit-form …)` | no |
| verbs | the `:core` eval source shipped by the eval tool (including the Clojure editing verbs after E17), and every mounted extension's own source | `edit`, `write-file`, `clj/edit-form`, `clj/replace-sexp`, `clj/paren-repair`, `clj/nrepl-eval`; extension-specific verbs | yes (`{:core false}`) |

**The service.** `:eval` is provided per agent by the agents factory
(`kmet.app.loop.driver`, beside `:shell`/`:sandbox`/`:permissions`), so a
child ctx can never shadow it and a preset may configure it first. Value is a
map of fns (`docs/architecture.md` § Service catalog's convention):

```clojure
{:eval!    (fn [{:keys [code timeout drop signal on-update ctx]}])
 :library  (fn [] [{:name "user/f" :ns "user" :kind :defn
                    :file "…"} …])
 :index    (fn [] {:entries [{:name "user/f" …}] :failures [{:file "…" :error "…"}]})
 :drop!    (fn [{:keys [session drop]}])
 :sources  (fn [] [{:id :core :doc "…"} …])
 :source-commands (fn [] {:lint {:description "…" :run (fn [ctx])}})
 :failures (fn [] [{:file "…" :error "…"} …])
 :dispose! (fn [])}
```

It owns one environment per session — one SCI context, one interrupt holder,
one sticky current namespace (`sci/*ns*` is thread-local, so the last
namespace a call entered is re-entered at the start of the next), one
`Object` lock (a parallel batch serializes; the deadline clock starts *after*
the lock is taken) — keyed by the run's session and built lazily on the first
eval call there by scanning the home library. A session switch selects that
environment; an already-built environment is not refreshed. The library index
tracks the definitions the environment loaded; `:index` is the on-disk
library, read without evaluating, and is what `/eval list` and `/eval search`
use so they show the library rather than one session's cache.

**The tool.** `:tool/eval` joins `:bundle/minimal` beside `:tool/codemode`,
with `:default-enabled? false` (S4.5): off until selected or enabled by
`/config`/`/tools`.

```jsonc
{"code": "Clojure forms. Top-level definitions are written to the agent's eval library and loaded by every session. Omit when only :drop acts.",
 "timeout": 0,
 "drop": ["user/old-fn"],
 "max-output-bytes": 0}
```

`code` is optional when `:drop` is present, so a definition can be removed
without evaluating forms. `:drop` deletes the file and unbinds the live name;
`(eval/forget 'name)` is the in-environment spelling.

Result content is the emitted text (or a silent success's final value, the
REPL rule) plus one line per written definition or removal — `[eval: defined
user/report → ~/.kmet/agent/eval/user/report.clj]`, `[eval: forgot
user/old-fn]`, and `[eval: redefined user/edit (source; not stored)]` for a
source-provided key; `:details` carries `:timeout :elapsed-ms :defined
:forgotten :sources :calls :error`, and `:failures` names any broken library
file or failed write. A failed write makes the result an error, leaves the
live definition in place, and leaves the previous file authoritative. Deadline,
abort, the sandbox realm, capture and the output budget are the shared script
layer's, not a second copy.

**The script core.** `kmet.app.tools.script` is the extraction, not a new
sandbox:

```clojure
(prepare opts)        ; per-call state: surface, bridge, emit buffers, abort, trace
(base-namespaces)     ; the host's babashka/clojure namespaces, copied by value
(call-namespaces s)   ; the per-call half: core patches, process wrappers, tools, emit
(context nss ifn tool) (seed! ctx state) (interrupt-state tool) (rearm! h signal deadline)
(run {:sci-ctx :code :state :prelude :extra-details})   ; capture → eval thread → wait → result
```

`codemode` becomes "build a fresh context, run, discard"; `eval` becomes
"rearm, seed, run" against the current session's environment. The context's
`:load-fn` and `:interrupt-fn` are fixed at `sci/init`, which is why the
interrupt and per-call namespaces are re-aimed through atoms rather than
rebuilt.

**Persistence.** The library is one file per definition, the verbatim source
slice (`rewrite-clj.parser/parse-string-all`, its `:list` children,
`node/string`; rewrite-clj is already an app dependency and a shared
extension library):

```
<agent-dir>/eval/<ns>/<name>.clj          (cfg/get-agent-dir)

;; kmet eval: written by the eval tool
(ns user)
(defn report [x] …)
```

Every successful call that makes top-level definitions writes each one
immediately; a redefinition overwrites the same file. A call that defines
nothing writes nothing, and a call that ends in error applies no writes or
drops. The file is the address: lookup, deletion and review are address
operations either way. A source-provided key (a builtin verb, or a key an
extension source contributes) may be redefined live but is never written.

Kept forms: top-level `def`, `defn`, `defn-`, `defonce`, `defmulti`,
`defprotocol`, `defrecord`, `deftype`, `defmacro`, `declare` (keyed by the
defining symbol), and `defmethod` (keyed `<multi> <dispatch>`, file name
sanitized). Dropping a `defmethod` address removes only that method; the
multimethod and its other methods stay live. A def nested in `do`/`when`/
`comment` is not written — nodecode writes it, kmet deliberately does not,
because the definition needs a stable address (the file name).

Load order for a session: every file under the library root, sorted by path;
the generated `(ns …)` form runs first, then the standard aliases, then the
body. A broken file is recorded under `:failures` and skipped; the agent still
works. A new session, resume, fork, import or restarted process builds from
the library. An already-built environment does not refresh: two sessions in
one home share the files but not the live definitions, and a definition
written by one is visible to another only when that session is built again.
Branches do not rewind definitions.

Session entries from the old model (`:eval-definitions`), project
`<cwd>/.kmet/eval` files and compaction snapshots are ignored, never loaded or
migrated. Global files already in `<agent-dir>/eval` are the library.

`:drop` deletes the addressed file and unbinds the live name; `(eval/forget
'name)` is the same operation from code. A name may not be defined and dropped
in one call. A name that is a source-provided key has no file; dropping it
restores the source seed. Writes and deletes take the EDN store's file lock
(`kmet.libs.edn-store/with-file-lock`), because two sessions can save at once.
A failed write is reported, leaves the live definition in place and still
forgettable, and leaves the previous file authoritative for the next build.
A call that ends in a normal error writes nothing; definitions it evaluated
before the error stay live and are tracked as unwritten, so `:drop`/
`(eval/forget …)` can remove them. A timed-out or aborted call may still have
its interpreter thread running, so its partial definitions are not tracked.
A code-less `:drop` deletes the files directly and unbinds only when the
session already has an environment; it never builds one. A failed delete or
unbind is reported, and a failed unbind can leave the live definition until
the process restarts.

**Eval sources.** The extension-visible half, and the cell analogue:

```clojure
(ext/register-eval-source! api :license
  (fn [{:keys [cwd project-dir agent-dir signal verbs]}]
    (let [{:keys [edit]} verbs]
      {:doc "Add the SPDX header to a source file."
       :ns     {'license {'add! (fn [path] (edit (str (fs/path cwd path))
                                                  "(ns " ";; SPDX-License-Identifier: MIT\n(ns "))}}
       :macros {}})))
```

- Registry tag `:kmet/eval-source` on the same app-wide `:tools` handle as
  `:kmet/tool-source` (`kmet.app.registries/registry-tags` → `:tools`), swept
  by the owner path on unload (`kmet.app.extensions.core/host-tags`).
- `sources-fn` is read per call, so live state is the normal case. It receives
  the call's `:cwd`, the eval roots, the cancel `:signal` (an atom, for a
  source that loops), and `:verbs` — keyword-keyed (`{:edit f :write-file g}`),
  holding **the very fn values the script gets** under `user`, built per call.
  That is how an extension's own logic calls `(edit …)` without requiring a
  host namespace: capabilities cross the boundary as values, as every other
  part of the extension API does.
- Values only: functions, data and explicitly-listed macros. A source that
  throws, or returns a bad shape, contributes nothing and is reported; it
  never fails an eval call.
- `user` is reserved for `:core`; an extension source must namespace what it
  contributes (`license/add!`). An extension source cannot contribute a
  builtin key (`user/edit`, `clj/edit-form`, …); the source is refused and
  reported, even if the key's namespace would otherwise be allowed.
  Precedence: among sources, later registration wins; the session's own
  definitions always win (the service records every `def*` target the model
  evaluated and `seed!` skips those names). A source-provided key is never
  written (see § Persistence); a model redefinition shadows it live, and
  `:drop`/`(eval/forget …)` restores the source's value.

**Discovery.** `(eval/sources)` and `(eval/library)` inside the environment
(`(eval/help)` summarises all three tiers), the `/eval <verb>` builtin command
for the human — `list [pattern]`, `search <text>`, `drop <name>…`,
`sources`, `help`, plus verbs an eval source contributes through
`:source-commands`. Built-in verb names are reserved; a source contributing
one is refused. No system-prompt section: it would sit in the cached prefix
and vary per session, so it would invalidate the whole prefix every time one
changed; a source that needs the model to know something unprompted registers
a skill, the existing seam.

A name alone is not discoverability: it says something exists but not how to
call it or what it does, so the model still has to open the file. Step 10
turns the listing into an interface listing. **10a** (records, source metadata,
`(eval/describe)`, docs), **10b** (the prompt manifest and first-eval report)
and the cwd-scope task are done.

- **Record the interface.** `kmet.app.eval.definitions` extracts `:doc` (the
  docstring, else attr-map or symbol metadata) and `:arglists` (for `defn`,
  `defn-`, `defmacro`, and the tail of `defmethod`) at parse time. No
  evaluation and no migration: `eval-files/index`, `(eval/library)` and
  `/eval list` carry both, so an existing file gains docs retroactively when
  its source has them.
- **One detail lookup, with optional fields.** `(eval/describe 'name)` with
  no key or `nil` returns
  `{:name :kind :doc :arglists :file :scope :source}`;
  `(eval/describe 'name key)` returns that field, e.g. `:source`. It resolves
  a library definition or a source-provided key. For a written definition,
  `:source` is the stored defining form (no generated `(ns …)` header); for a
  source-provided key, it is the source's `:source` metadata, nil when not
  supplied; for an unwritten live definition, it is the source from the
  retained in-memory record. `:file` is nil for source-provided and unwritten
  entries; a nil `:source` means no source was provided. The plural
  `(eval/sources)` keeps its current meaning.
- **Source-provided verbs carry their own interface.** A value in a source's
  `:ns`/`:macros` map may carry `:doc`, `:arglists` and `:source` metadata —
  the keys a var uses, plus the implementation text. `normalize-source` keeps
  them, `source-var` puts `:doc`/`:arglists` on the SCI var so
  `(meta #'license/add!)` works, and `(eval/sources)` and `(eval/help)` list
  each contributed verb with its one-line doc and arglists.
  `(eval/describe 'name)` resolves a source key as well as a library
  definition; `(eval/describe 'name :source)` returns the `:source` metadata
  when supplied. The `:core` source gains metadata for `user/edit`,
  `user/write-file`, `clj/edit-form`, `clj/replace-sexp`,
  `clj/paren-repair` and `clj/nrepl-eval`; extension sources declare theirs
  in the same place. A
  source value without metadata is still valid and lists as
  `(no docstring)`. The prompt manifest stays library-only because sources
  are resolved per eval call and may have side effects; it points at
  `(eval/help)` for the source verbs.
- **A hidden append-only `:eval-library` custom message** is synced at prompt
  start, mirroring `sync-tool-sources-listing!`: deterministic text, one line
  per definition as `name [arglists] — first doc line`, bounded with
  "… N more; (eval/library) lists them", appended only when the branch's last
  listing changed, and only while the `eval` tool is selected. It never
  rebuilds the system prompt.
- **Loaded is not saved.** With an environment, list its loaded records (the
  session's snapshot, with `:scope`/active state);
  without one, list the disk index as "saved; loads on first eval". Never
  advertise a disk-only name as live in an already-built session, and never
  build an environment just to produce the message. The first eval call
  reports the loaded set and load failures, correcting any drift.
- **Docstrings are the save contract.** The eval guideline asks for one; a
  definition without one still saves, but its result line says
  `(no docstring)`. An undocumented entry lists kind and arglists, or
  `(no docstring)` when neither exists. `(eval/describe 'name)`
  returns kind, arglists, docstring, file, scope and source; a nil `:source`
  means none.

### Steps

Nine implemented steps plus the cleanup step 4R. Step 10 is split: 10a is the
interface task at the top of this file (before the cwd-scope task); 10b is the
manifest follow-up below (after it). Both are done. Steps 1–2 are behaviour-preserving and
every later step is additive. Estimated ~2,000 new lines, ~1,000 moved (step 1)
plus ~150 moved (step 2).

1. **`kmet.app.tools.script`.** Move the reusable sandbox out of
   `codemode.cljc`: constants, the three dynamic vars (`*enabled-tools-fn*`,
   `*tool-hooks*`, `*assistant-message*`), helpers, capture, capability
   namespaces, `host-class-map`, `sandbox-load-fn`, `context`,
   `call-namespaces`, `active-surface`, the bridge pool, `interrupt-fn`,
   `start-eval-thread!`, `assemble-result`, `realm`. New public seams as in
   § Shape; message text parameterized by the tool name so `codemode`'s
   strings stay byte-identical (`[codemode output truncated …]` is asserted).
   `script-tool-names` = `#{"codemode" "eval"}` — neither tool nests the
   other. Update the moved dynamic vars' bind sites: `kmet.app.loop.clj:1113`
   and `:1120`, `kmet.app.loop.tool_exec.clj:292`,
   `test_codemode.clj` (three `binding`s).
2. **The file ops.** `kmet.app.tools.edit/edit-file` (mutation lock, BOM,
   line endings, `edit-diff/apply-edits-to-normalized-content`, write, diff)
   public, `execute` = op + rendering; `kmet.app.tools.write/write-file`
   public, `execute` = op + success string. Stays in `kmet.app.tools.*`
   (E11) — no libs move, no lock move, tool tests unchanged.
3. **The service and the tool, memory only.** `kmet.app.eval` with `value`,
   lazy context, interrupt holder, sticky ns, lock, `:dispose!`; the driver
   provides the default like `:sandbox`; the handle's `:dispose!` calls the
   service's. `kmet.app.tools.eval` + the `:tool/eval` row in
   `:bundle/minimal`, a sharp description against `codemode` (and one line
   added to `codemode`'s pointing at `eval`).
4. **Step 4R (done): the one home library.** Every successful top-level
   definition is written immediately under `<agent-dir>/eval/<ns>/<name>.clj`
   (one verbatim source slice, atomic publication under the root lock); a
   redefinition overwrites it. A session's environment is built lazily on its
   first eval by scanning that root (sorted by path) and evaluating every file;
   an already-built environment is a runtime cache and never refreshes.
   `:drop` and `(eval/forget 'name)` delete the file and unbind the live name;
   a source-provided key is never written. No session `:eval-definitions`
   entries, no project `<cwd>/.kmet/eval`, no fold, no snapshot, no branch
   fingerprint, no shadowing. The compaction snapshot hook is removed; legacy
   entries and project files are ignored. `(eval/library)` replaces
   `(eval/kept)`. Result lines and `:details` report `:defined` and
   `:forgotten`; a failed write is reported without rolling back the live
   definition or other writes. The engine-portable builtins register the
   `/eval` command (`list`, `search`, `drop`, `sources`, `help`), independent
   of the `:tool/eval` row; source-contributed command verbs arrive in step
   5.
5. **Step 5 (done): `:kmet/eval-source` and `:core`.** Registry fns
   (`register-eval-source!` / `unregister-` / `get-eval-sources`) in
   `kmet.app.tools.registry`; `registry-tags` + `host-tags`; the service
   resolves sources per call, builds the per-call verb table (`edit`,
   `write-file` over step 2's ops, cwd-resolved), seeds it through `:core`
   (`{:ns {'user {'edit f 'write-file g}}}`, registered by `:tool/eval`'s
   `:apply`), and passes `{:cwd :project-dir :agent-dir :signal :verbs}` —
   keyword-keyed — to every `sources-fn`.
   The eval thread binds the run's dynamic values (`tool-util/*cwd*`,
   `bash-tool/*shell*`, `bash-tool/*session-env-fn*`,
   `read-tool/*skill-resolver*`) so a verb resolves paths exactly as its tool
   does. A source contributing a builtin key is refused, and the service
   records each source's keys as non-storable: a model redefinition shadows
   one live, `:drop`/`(eval/forget …)` restores the source's value and writes
   no file. `(eval/sources)`, `(eval/help)` and `/eval sources`. Sources also
   return `:commands` (`{:lint {:description … :run (fn [ctx])}}`) for the
   `/eval` command; a `:run` returns nil or a string, and a non-blank string
   is printed as the command's output. Built-in verb names (`list`, `search`,
   `drop`, `sources`, `help`) are reserved and a source contributing one is
   refused.
6. **Step 6 (done): the extension ABI.** `kmet.extension/register-eval-source!` /
   `unregister-eval-source!`; the api keys in
   `kmet.app.extensions.core/create-extension-api`; `create-nullable-api`
   gains `:eval-sources` (the API capability, not a command);
   `modules/app/src/kmet/extension.md` +
   `docs/extensions.md` (the `license` example, the `user` prohibition, "use
   the verbs within the call"). The loader's opaque `:meta` (S4) is the later
   static projection for extension-provided eval verbs, e.g. `:meta {:eval
   [{:name "clj/edit-form"}]}`; the live `:kmet/eval-source` registry stays
   authoritative, and the metadata reader lands only when a consumer needs it.
7. **Step 7 (done): the eval vocabulary.** `(eval/patch 'name old new)` (structural subform
   replacement through rewrite-clj, re-evaluate, write the library file).
   `(eval/forget 'name)` landed with step 4R. The structural editor gets its
   own review.
8. **Step 8 (done): the Clojure editing engine and verbs.** Extract
   `kmet.extensions.clojure.edit-util` to `kmet.libs.clj-edit` — the shared
   engine (`edit-pipeline`, `edit-source`, `replace-form`,
   `format-form-in-isolation`, `repair-delimiters`, parse and duplicate
   checks) — and have both the clojure extension's tools and the eval core
   require it. The `:core` source always ships `clj/edit-form`,
   `clj/replace-sexp` and `clj/paren-repair` (map args and result parity with
   the tools), so the rich editor needs neither `:ext/clojure` nor a tool
   schema. Add the live `eval/edit-form`: find the definition's library file,
   apply the edit to the source, re-evaluate, then write the same file and
   update the index (`edit-source` is the pure step); `eval/patch` stays the
   minimal subform op. The extension keeps the tools, hooks and skill and
   drops its eval source (extension-specific verbs may return later); it is
   not added to `:bundle/minimal`. Resolve paths against the source `:cwd` in
   the verbs and `(:cwd ctx)` in the tools, which become `:contextual?`; no
   process-cwd assumption (E17).
9. **Step 9 (done): docs and catalog.** `docs/architecture.md` § Service catalog (`:eval`)
   and § Tools and the script sandbox (the eval tool, the extraction, the
   library and the scoping of "a `def` in one call is invisible to the next"
   to `codemode`); `bb generate-catalog`; `docs/pi-alignment.md` § codemode
   (the persistent `store`/`load` half and the extension analogue are no
   longer missing); `modules/app/src/kmet/design.md` § Open at HEAD 5 (one
   line: a persistent context makes Jolt's `eval-in` more attractive later;
   the decision is unchanged); the tier table and the clojure extension's
   remaining surface (E17 and the post-track task).
10. **Step 10b (done 2026-11-05): the discovery manifest
    and first-eval report.** Step 10a supplies `:doc`/`:arglists`/`:source`
    through `(eval/describe)` for library definitions and source verbs; 10b
    landed the manifest half of the Discovery design:
    - `kmet.app.session.context`: the `:eval-library` custom type and the
      bounded, deterministic formatter (doc lines elided, whole definitions
      dropped behind `… N more; (eval/library) lists them`, with an
      `(eval/help)` pointer).
    - `kmet.app.loop.state`/`loop`: `sync-eval-library-listing!` beside
      `sync-tool-sources-listing!`, with the loaded/saved split, change
      comparison and selection gate.
    - Loaded is not saved: with an environment the message lists its records
      with `:scope`/active state; without one it lists the disk index as
      "saved; loads on first eval". It never advertises a disk-only name as
      live in an already-built session and never builds an environment for
      the message. The first environment build reports the loaded set and
      load failures in the call's result (`:details :loaded`/`:failures` plus
      report lines).
    - Source verbs stay out of the manifest (sources are resolved per eval
      call and may have side effects); the message points at `(eval/help)`.
    - Tests: manifest determinism, bounding, no duplicate, update and empty
      correction; loaded vs saved before and after the first eval; a
      cross-session save not advertised as live; selection gate; the manifest
      never builds an environment or invokes a source; first-eval loaded-set
      and failure reporting.
    - Docs: `docs/architecture.md` eval section, the eval tool prompt/help,
      and the generated composition catalog.

### Test plan

- **Step 1:** `test_codemode.clj` passes unmodified apart from the three
  `binding`s — the strongest available proof that the extraction preserved
  behaviour. One test asserts the inner-call hook payload still carries the
  assistant message across the moved dynamic var.
- **Step 2:** a table test runs one edit through `registry/execute-tool` and
  through the verb and asserts identical file bytes, and the same
  `:edit-error` message for not-found, duplicate and no-change.
- **Steps 3–4R:** two `value` instances with separate homes behave as two
  agents; two sessions in one home build separate environments from the same
  library; a new session, resume or restarted process loads the library; an
  already-built session does not refresh and its stale view is reported as a
  cache, not a bug; `define=write` leaves the expected file bytes and reports
  one defined line per definition, including a redefinition (which overwrites
  the file); a pure expression writes nothing, and a call that ends in error
  writes nothing; `:drop`/`(eval/forget 'name)` deletes the file and unbinds
  the live name; unknown names, duplicate drops and a name defined and dropped
  in one call error before evaluating; `defmethod` addresses are distinct per
  dispatch; a broken file becomes a `:failure`, is reported once, and the
  agent still evals; files load sorted by path; a source-provided key redefined live is never
  written and dropping it restores the seed; a failed write is reported and
  the live definition remains — still droppable/forgettable — with the old
  file authoritative after a restart; a definition evaluated before an error
  is live and forgettable, while one after the error is not; an unqualified
  drop qualifies against the sticky namespace and removes its library entry;
  an unqualified `(eval/forget …)` resolves in the namespace the call ends in;
  a qualified define+forget in one call reports the conflict, not an unknown
  name; a partial drop reports what landed before the failure, including an
  unbind or delete failure; project files and legacy
  `:eval-definitions` entries are ignored; `(eval/library)` matches the loaded
  index; two parallel definitions on one session both survive; the `/eval`
  command lists, searches and drops from the library without building an
  environment, and its registration is engine-portable, independent of the
  `:tool/eval` row.
- **Step 5–6:** a source's fns are callable on the next call; a source's
  `:verbs` and the environment's `user/edit` behave identically (assert by
  behaviour — same file bytes — not by identity); unload removes the names on
  the next call and leaves the model's definitions; a throwing source
  contributes nothing; a source contributing `user` or `clj`, or a builtin
  key, is refused; an extension verb can be redefined in session scope but is
  never written, and `:drop`/`(eval/forget …)` restores the source value while
  mounted; a contributed key stays non-storable after its source unloads; a
  source that captures `:verbs` past its call is documented as unsupported (no
  test); the `/eval` command lists, searches, drops, reports sources and
  dispatches a source verb; a verb's non-blank return string is printed; an
  unknown verb and a reserved source verb are reported; `/eval sources`
  names sources, loaded definitions and failures.
- **Step 7:** `(eval/patch 'name old new)` replaces the first structurally
  equal subform, updates the live definition and the file; an unknown old
  form, a changed definition name, or a failed re-evaluation leaves the file
  and the live definition untouched; a source-provided key is refused.
- **Step 8:** the shared engine's tests; `(clj/edit-form …)` and
  `clojure_edit` on the same fixture produce identical bytes and the same
  bad-form message, with the verb core and the tool present only when
  `:ext/clojure` is mounted; `eval/edit-form` updates the live definition,
  writes only after a successful re-eval, and leaves the file/index untouched
  on failure; relative paths resolve under the source `:cwd` while the
  process cwd differs.
- **Step 10a:** `:doc` and `:arglists` are extracted from docstrings,
  attr-maps, symbol metadata, multi-arity forms and `defmethod` tails, and
  survive the disk index and `(eval/library)`; `(eval/describe 'name)` with
  no key and with `nil` returns the full map, each key returns its field, and
  an unknown name errors; `:source` covers a written definition, a
  redefinition, a `defmethod`, a multi-name `declare`, a source-provided key
  with `:source` metadata, a source-provided name without it (nil), and the
  retained record of an unwritten live definition (cleared by a successful
  write/drop); it never reads a stale file; a source value's
  `:doc`/`:arglists` metadata shows in `(eval/sources)`, `(eval/help)` and
  `(meta #'license/add!)`, while a value without metadata lists as
  `(no docstring)`; a library saved before this change re-parses with its
  existing docstrings.
- **Step 10b:** the `:eval-library` message is identical for an unchanged
  library, updates once when a definition is added or dropped, is bounded, and
  is silent when nothing was listed before; a session with an environment
  lists only its loaded records while a new session lists the saved preview; a
  sibling session's later save is not advertised as live; the first eval
  reports the loaded set and failures; the message is absent when `eval` is
  not selected; the manifest never invokes a source fn.
- **Both hosts:** the eval tests run under babashka and Jolt's views
  (`bb test-changed` plus `jolt test` where the tree has it); `sci/init`
  once per session and the `swap!`-based `seed!` are the two host-sensitive
  points.

### Limits, stated in the docs

- Same authority as `bash`. Every definition in a home's library runs in every
  session built from that home; a source-provided key is live but not written.
  That is the feature and the risk. Library files are plain text under
  `~/.kmet/agent/eval` for review; no sandbox is claimed.
- A host-blocked call still hits the existing interrupt-grace limitation
  (no process isolation — `modules/app/src/kmet/design.md` § Open at HEAD 5).
- Nothing is kept from `codemode`; nothing from `eval` is visible to
  `codemode`. Two homes never share; two agents or sessions in one home share
  the library files but each has its own live environment, so a definition is
  visible to another only when that environment is built again.
- The library is unbounded (the model can define as much as it wants); a
  written file is the record, not a compaction fold. The capture and result
  budgets are the shared script layer's.
- Discovery is a bounded preview, not a source dump: the prompt message
  truncates long docs and stops after the budget; `(eval/library)` is the full
  view. A definition with neither a docstring nor an arglist still has its
  source available through `(eval/describe 'name :source)`.
- A source-provided verb is documented or source-readable only when the
  source attaches `:doc`/`:arglists`/`:source` metadata; there is no defining
  form to parse. The source-level `:doc` describes the source, not each verb.
  A value without metadata lists as `(no docstring)` and has `:source nil`.
- `(eval/describe 'name :source)` returns a definition form, not a library
  file: the generated `(ns …)` header is not stored source, carries no stored
  requires, and is omitted. A source-provided verb returns its `:source`
  metadata when supplied; without it, `(eval/describe 'name)` still has its
  `:doc`/`:arglists`. A failed write or a call that ended in a normal error
  can leave a live unwritten definition; its in-memory record is described,
  while `:file` is nil (no authoritative file).

### Decisions

**E1 — a second tool, not a `codemode` flag.** `codemode`'s fresh context is
load-bearing for its output economy and for 98% of its calls; a stateful mode
behind an argument would make every one of them stateful in the model's
reading. Two rows, two names, two contracts.

**E2 — `:eval` is a per-agent service from the factory, not a root row.** A
root row could never be narrowed for one agent (the kernel forbids a child
shadowing an ancestor's service) — the same reason `:shell`, `:sandbox` and
`:permissions` are factory-provided. A preset row may mount first and
configure it; the `:tool/eval` row is what a profile removes to hide the tool.

**E3 — one library per home; a session is a cache.** Definitions belong to the
agent home: `new-session!` starts with a fresh environment built from the
library, `resume-session!`/fork/import build from it the same way, and
switching sessions swaps environments. An already-built environment is not
refreshed, so a definition written by one session is visible to another only
when that session is built again. There is no session definition log, no
branch rewind and no per-session persistence. `KMET_CODING_AGENT_DIR` (or a
profile) isolates a home; subagents, agents and sessions in one home share the
files but not the live environment.

**E4 — define = write; forget = delete; no scopes.** Every successful
top-level definition is written immediately to
`<agent-dir>/eval/<ns>/<name>.clj`; a redefinition overwrites it. There is no
`:keep`, no project scope, no move or demotion. `:drop` or `(eval/forget
'name)` deletes the file and unbinds the live name. A source-provided key is
never written. A failed write is reported and the live definition remains
(and can still be forgotten), with the previous file authoritative for the
next build. A call that ends in a normal error writes nothing; its evaluated
definitions remain live and forgettable (a timed-out/aborted call is not
tracked, because its interpreter may still be running). A code-less drop never
builds an environment. A failed delete or unbind is reported; a failed unbind
leaves the live definition until a restart. The cwd-scoped task above keeps
the write/forget rules and replaces the no-scope part.

**E5 — one address per definition, verbatim source.** The file is the address
(`<ns>/<name>.clj`); lookup, deletion and review are address operations.
`pr-str` of the parsed form would normalise away reader sugar; the rewrite-clj
slice keeps the bytes the model wrote. Session keys from the old model are
gone.

**E6 — a redefinition overwrites; there is no shadow.** A model redefinition
of any library name writes the same file (or, for a source-provided key, only
the live environment). There is no session copy to shadow a shared one and no
silent re-scope. A failed write leaves the old file authoritative and reports
the failure.

**E7 — top-level defining forms only.** A def inside `do`/`when`/`comment` is
not written, unlike nodecode: a computed definition has no stable address, and
the alternative (an aggregate file per call) makes per-definition deletion and
review impossible.

**E8 — `defmethod` keyed by `<multi> <dispatch>`.** Otherwise every method of
one multimethod lands on one address and the last one wins at load. The
sanitized dispatch in the file name is the address; `(eval/library)` names the
full key.

**E9 — eval sources are values; neither tool nests the other.** The
contribution crosses the isolation boundary the way every other capability
does (functions and data), so no source can reach a host service it was not
given. `script-tool-names` excludes both tools from any script's surface, so
`codemode` cannot start an `eval` and an `eval` cannot start another.

**E10 — sources cannot redefine builtins; the agent's overrides are never
written.** Later-registration-wins is app-wide, so an extension that took
`edit` would silently replace the built-in in every agent. The rule is
enforced by the service: a source contributing to `user`, `clojure.core`,
`tools`, `sandbox`, `eval`, `fs` or `clj`, or contributing a builtin key, is
refused and reported. The agent itself may redefine a builtin or extension
verb live, but a source-provided key is never written: there is no library
file, and `:drop`/`(eval/forget …)` restores the source's value.

**E11 — host operations cross as values, not as requirable namespaces.**
`sources-fn` receives `:verbs` — the same fns the script calls — so an
extension's own logic can compose `(edit …)` without `kmet.app.*` becoming
requirable and without moving the file ops or the mutation lock into `libs`.
A libs extraction happens when a consumer needs the op *outside* a source; E17
is that consumer for the Clojure engine (`kmet.libs.clj-edit`).

**E12 — discovery is in-code, plus commands.**
`(eval/sources)`, `(eval/library)`, `(eval/help)`, the `/eval` command
(`list`, `search`, `drop`, `sources`, `help`, plus source verbs); no
system-prompt section. A dynamic per-session section sits
in the cached prefix and varies per session, so it would invalidate the whole
prefix every time one changed. A source that needs unprompted presence
registers a skill, the existing seam.

**E13 — `:core` ships the language-neutral file verbs plus the Clojure editing
verbs.** `edit` and `write-file` are the two operations a bare profile (no
extensions mounted) needs to loop over files; `clj/edit-form`,
`clj/replace-sexp` and `clj/paren-repair` join them after E17 because `eval`
is a Clojure environment and the engine is bundled. Other tool-specific verbs
(search, …) still come from the extension that owns the tool; nREPL is the
exception — `clj/nrepl-eval` shares `kmet.libs.clj-eval` with the
`clojure_eval` tool.
`read`/`ls`/`glob`/`grep` are deliberately absent: `slurp` and the `fs` alias
cover them, and a pure-Clojure grep would need gitignore and binary-file
rules we would get wrong (`rg` via `sh` is the better answer).

**E14 — declined.** Source strings evaluated in the eval context (would give
an extension `(edit …)` by name, and full authority over the environment for
nothing the value contract cannot express); `*`/`/` value chains across calls
(no REPL value history in SCI); `$` named strings (the tool's argument is the
code); `fork` (subagents, not this tool); an event vocabulary for kept
paragraphs (no reader); per-agent eval sources (the registry is app-wide,
like tool sources); auto-save on any definition is now the model rather than a
declined option; `:keep`, a project `<cwd>/.kmet/eval` scope, session
definition entries and compaction snapshots (superseded by E18).

**E15 — names closed.** `/eval` is the human command: `list`, `search`,
`drop`, `sources`, `help`, plus keyword-keyed verbs an eval source contributes
through `:source-commands` (a `:run` returns nil or a string); the built-in
names are reserved; the cwd-scoped task adds `move`. `/eval sources` is the
source report. `(eval/library)` is the listing (the old `(eval/kept)` name is
retired with the scope model); `(eval/forget 'name)` is the in-environment
delete; `clj/*` for the Clojure verbs (core-provided after E17; not the
tool names `clojure_*`, and never bare).

**E16 — the service is built-in; the verbs are the extension point.** `:eval`
is provided by the agents factory and its engine lives in `kmet.app.eval` — an
extension cannot own either: per-agent provisioning is the factory's (no ABI
lets an extension provide a service on every agent's child ctx), and the
engine needs `kmet.app.tools.script`, `kmet.app.tools.registry`,
`kmet.app.tools.edit/edit-file` and the loop's bindings, none of which an
extension may require. The tool is a **row** (`:tool/eval` in
`:bundle/minimal`) and the report a **builtin command**, which is what
built-in means here: a profile removes the row
(`{:id :tool/eval :remove true}`) or replaces its plugin exactly as it can for
`bash`, so nothing privileged is involved. `:ext/eval` would buy none of what
an extension is for (its isolation is the sandbox the engine already is, its
context is already lazy, its replaceability the row already gives) and would
cost a new provisioning seam, a second dependency tree, and — since bundled
extensions are never loaded by default — every profile naming the row anyway.
The service is provided unconditionally by the factory (lazy, zero cost until
the first call), so removing `:tool/eval` leaves the `/eval` library command
and its service in place — the command is a reader, unlike `:sandbox` without
`:tool/codemode`.

**E17 — the Clojure editing engine and the edit verbs are core; the tools stay
an extension.** `eval` is a Clojure environment, and the engine's deps
(rewrite-clj, cljfmt, edamame, parinferish) are already bundled, so the
language/dependency case for keeping the editor extension-only does not hold.
The engine moves to `kmet.libs.clj-edit`; the `:core` source always provides
`clj/edit-form`, `clj/replace-sexp`, `clj/paren-repair` and the live
`eval/edit-form` (write only after a successful re-eval). The clojure
extension keeps `clojure_edit` and its deferred refinements as tools, plus the
hooks and skill; mounting it is no longer required for the verbs, and it is
not added to `:bundle/minimal`. This supersedes E11's "no libs extraction"
condition and E13's Clojure clause. The rest of the extension is reconsidered
by the post-track task below.

**E18 — one global eval library (2026-11-05).** The eval scope model is global
only: one auto-written library per agent home, every successful top-level
definition writes its file immediately, a session's environment is built by
scanning the library, sessions are runtime caches, and forget deletes the file
and unbinds the live name. This supersedes E3, E4 and E6 and amends E5, E10,
E12, E14 and E15; the landed step 4R is its implementation. The reference is
nodecode: one layer per home, one file per definition, no session or project
scope for code. Superseded and replaced by § Task: cwd-scoped eval library
(done 2026-11-05).

## Post-track task: the rest of the clojure extension

Status: measuring (2026-11-05; measured again 2026-11-06). Landed: the
`clojure_edit` description and guidelines are cut from 4.3 KB to 1.7 KB (the
description alone 3.1 KB →
1.1 KB); the `clojure-edit` skill now states the real split — the tool for
whole forms, generic `edit` for small or multi-hunk text, the `clj/*` eval
verbs for scripted edits, `clojure_eval` for live verification — instead of
"ALWAYS use the tools"; the extension README matches it; and the extension's
own `bb.edn` test task is fixed (its classpath was missing the engine's test
root after the E17 move). No tool was added, removed or promoted, and nothing
joined a default bundle.

Baseline from the session logs (432 session files, 2026-11-05):

| surface | calls / sessions |
|---|---|
| `clojure_edit` | 762 / 118 (Sep 410 → Oct 181 → Nov 171 on 2–4; 0 on Nov 5) |
| `clojure_edit_replace_sexp` | 349 / 40 (264 → 81 → 4, already deferred) |
| `clojure_paren_repair` | 75 / 21 (10 → 63 → 2) |
| `clojure_eval` (nREPL) | 21 / 7, all in kmet's own development |
| generic `edit` on `.clj`-family files | 3,684 / 174 (1,796 whole-form hunks) |
| paren-check hooks | 37 blocked writes / 19 sessions |

Interim measurement (2026-11-06; EDNL logs 2026-11-04..06): 20 normal
sessions (not eval-only subagents), of which 6 had both `eval` and
`clojure_edit` mounted; none of the 6 started after 10b. In those 6:
`clojure_edit` 2 calls (0 errors), `clj/edit-form`/`clj/replace-sexp`/
`clj/paren-repair` 0 calls, generic `edit` on Clojure-family files 21 calls /
46 hunks / 6 whole-form hunks (4 unique after retries), 2 exact-match
failures, both retried. Across all 20 normal sessions: `clojure_edit` 74
calls (7 errors, 4 retried), eval verbs 0, generic whole-form definition
hunks 36. The eval verbs were used only in the 24 eval-only subagents (66
`clj/edit-form`, 27 `clj/replace-sexp`, 0 `clj/paren-repair`, 22
`user/edit`). The retirement gate is not met: the `clojure_edit` share is 25%
of whole-form edits in the both-mounted sample and 67% across all normal
sessions (both above ~10%), and the weekly rate is not below ~5. Keep the
trimmed direct tool; the both-mounted sample is still short of ~20 sessions,
so continue measuring and re-check after 10b accrues normal sessions.
`clojure_edit_replace_sexp` had 0 calls and `clojure_paren_repair` 2; keep
both pending normal-session usage.

The options remain: keep the opt-in extension with the trimmed surface (the
current lean), promote the rest into a core/bundle row, or drop the extension
if the hooks/skill/nREPL no longer justify it. The measurement decides; the
engine stays in core.

Follow-ups:

- After the cwd-scope and 10a/10b work, measure ~20 normal sessions (not
  eval-only subagents) with both surfaces mounted: `clojure_edit` calls vs
  `clj/edit-form`/`clj/replace-sexp`/`clj/paren-repair` calls, whole-form hunks
  through generic `edit`, and `.clj` edit failures/retries.
- Apply the gate: if `clojure_edit` stays below ~10% of whole-form edits and
  ~5 calls/week, retire it from the tool surface (keep the engine, hooks,
  skill and nREPL; point the skill at the eval verbs). At or above that, keep
  the trimmed direct tool.
- Retire `clojure_edit_replace_sexp` once `clj/replace-sexp` carries the use;
  its calls have already collapsed.
- If `clojure_paren_repair` is retired, update the `edit` warning hint in
  `paren_repair.clj` and the skill's recovery line, which both name the tool.
- Record the outcome here and update `docs/extensions.md`/`extension.md` with
  the chosen surface. Keep `:ext/clojure` opt-in unless the measurement shows
  the tools are expected everywhere: no core promotion and no default bundle
  without that evidence.
- Do not duplicate `kmet.libs.clj-edit` in any outcome.

## Deferred: context and token tracks

The tool-result pruner (**R**), the request-envelope estimate (**M**),
warm-prefix summarization (**W**) and per-result retention with a locator
(**S**) were dropped from this file on 2026-11-02. The full plans stay in git
history; a future revisit should start from measurement rather than re-derive
them.

Measured before the drop (292 session files, 47,366 tool results, 44
compaction windows; the projection reconstruction matches the recorded
`:tokens-before` to ~1%):

| fact | number |
|---|---|
| projection at a compaction trigger | 45.9M chars ≈ 11.5M tokens over 44 windows |
| tool results / thinking / tool-call args | 45.3% / 37.3% / 14.8% |
| tool-result bytes by tool | bash 66.4%, read 29.1%, everything else 4.5% |
| unbounded tools at the trigger (`codemode`, extensions) | 1.9% of tool-result chars |
| ceiling every tool already observes | ~50 KiB (read/bash pi's cap, MCP adapter's output guard) |
| R candidates (≥8 KiB, outside the newest turn) | 352 results = 26.8% of trigger tool chars; trimming saves ≈8% of the projection |
| S budget 3,125 / 6,250 / 12,500 tok | 119 / 31 / 3 unbounded results clipped |

Two corrections for whoever picks this up. The R note's composition table
(47% thinking / 26% tool results / 19% args, 7.09M chars) does not match the
projection above — it measures a ~6x smaller quantity, most likely the
2000-char-truncated summarization input. And pi has no counterpart for any of
the four (no pruner, no envelope estimate, no warm-prefix summary, no
per-result retention), while dsh has one for each.

Pi parity note: any revisit lands a row in `docs/pi-alignment.md` §8 and a
sentence in the commit body naming the departure.
