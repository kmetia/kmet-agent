# Todo

## Decision: one global eval library (2026-11-05)

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
  branch do not rewind definitions; a new session loads the files.
- **Forget = delete.** Remove the file and unbind the live name; a flat
  `:drop` or `(eval/forget 'name)` is enough.
- **`run_code` is the throwaway tool; `eval` is the library.**
- **Isolation is a home**, not a scope: `KMET_CODING_AGENT_DIR` (or a
  profile) gives a separate library.

This supersedes the session/project scope rules in step 4 and the
project/global material below. The step list is to be rewritten before step 4
work continues; the remaining steps (eval sources, patches, the Clojure
engine, docs) are otherwise unchanged.

**TODO:** clean up the rest of this file to match the decision — rewrite step
4, the test plan, the limits and E3/E4/E6/E10, and remove the session/project
scope prose. Deferred; not part of step 4 work yet.

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
(the `kmet config` tool rows), **S4.5** (the opt-in built-in defaults), **S5**
(the tool selection docs) and **S6** (the tool-selection review and simplify)
— is out of this file; it lives in git history. The context and token tracks
are dropped too — see § Deferred.

| track | what it fixes | status |
|---|---|---|
| Persistent eval and eval sources | `run_code` throws its context away per call, so a helper the model writes is re-pasted in full on every turn; extensions can contribute tools but not a definition the model can call from code | steps 1–4 done; **step 5 next** |

## Persistent eval and eval sources

Status: steps 1–4 done (the shared script layer, the file ops, the
memory-only service and the session store); step 5 next. The tool is opt-in by
default under S4.5; step 4's tests exercise it directly rather than through the
default declared set. E17 moves the Clojure editing engine and the edit verbs
into core; the rest of the clojure extension is a post-track task at the end of
this section.
Decisions closed (E1–E17); independent.
Independent of every other track; the `:on-tool` hook covers it for free (step
1 keeps the bridge on `kmet.app.tools.invoke`).
Reference: nodecode (`~/src/cvstree/nodecode/`) — an SBCL agent whose one tool
evaluates Lisp in the live image and keeps every definition
(`docs/README.md` § The layer, `docs/CELLS.md`, the `eval` page on its site).

### Why

`run_code` builds a fresh SCI context per call and discards it on return, on
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

- **per session, not per process.** The environment belongs to the session,
  not the process or the agent handle: a new session starts empty, a resume
  restores that session's definitions, and a subagent gets its own, never the
  parent's.
- **persistence is opt-in per definition.** Definitions stay in the session
  store; nothing is written into the user's repository unless a `:keep`
  entry names it, and a kept file is reviewable text.
- **extensions contribute definitions the way they contribute tools** — a
  registry tag, an owner path, one unload sweep. No new ABI shape.

### What exists today

| piece | today | this track |
|---|---|---|
| script sandbox | `kmet.app.tools.run-code`, one SCI ctx per call, ~1,000 lines of machinery | moves to `kmet.app.tools.script`; both tools use it |
| file ops | inline in `kmet.app.tools.edit` / `.write` `execute` | extracted as ops; tool and verb share them |
| per-agent services | `:shell` (atom), `:sandbox` (data), `:permissions` (memory) — the agents factory provides each | `:eval` joins them, with one environment per session |
| extension contribution | `register-tool!`, `register-tool-source!` (`:kmet/tool-source` on the `:tools` handle), commands, skills | `register-eval-source!` (`:kmet/eval-source`), same handle, same sweep |
| model's code tool | `run_code` only | `run_code` + `eval` |

`run_code`'s per-call context costs roughly 3 ms of namespace copying plus
9 ms of `sci/init` (its own comment); a per-session context pays that once
per session instead of once per call.

### Shape

Three tiers of what a script can call, and only the third is a source:

| tier | provided by | examples | removable |
|---|---|---|---|
| primitives | `kmet.app.tools.script`, shared with `run_code` | `slurp`, `spit`, `sh`, `p/*`, `fs/*`, `json`, `emit`, `emit-image`, `tools/list`, `tools/describe`, `tools/call`, `(cwd)`, `(env k)`, `(now)`, `(sleep ms)`, `(spawn f)` | no |
| eval vocabulary | the `:eval` service (it needs kept-definition state) | `(eval/kept)`, `(eval/sources)`, `(eval/help)`, `(eval/patch …)`, `(eval/forget …)`, `(eval/edit-form …)` | no |
| verbs | the `:core` eval source shipped by the eval tool (including the Clojure editing verbs after E17), and every mounted extension's own source | `edit`, `write-file`, `clj/edit-form`, `clj/replace-sexp`, `clj/paren-repair`; extension-specific verbs | yes (`{:core false}`) |

**The service.** `:eval` is provided per agent by the agents factory
(`kmet.app.loop.driver`, beside `:shell`/`:sandbox`/`:permissions`), so a
child ctx can never shadow it and a preset may configure it first. Value is a
map of fns (`docs/architecture.md` § Service catalog's convention):

```clojure
{:eval!    (fn [{:keys [code timeout keep drop signal on-update ctx]}])
 :kept     (fn [] [{:name "user/f" :scope :session
                    :shadowing {:scope :project :file "…"}} …])
 :sources  (fn [] [{:id :core :doc "…"} …])
 :failures (fn [] [{:file "…" :error "…"} …])
 :dispose! (fn [])}
```

It owns one environment per session — one SCI context, one interrupt holder,
one sticky current namespace (`sci/*ns*` is thread-local, so the last
namespace a call entered is re-entered at the start of the next), one
`Object` lock (a parallel batch serializes; the deadline clock starts *after*
the lock is taken) — keyed by the run's session and built lazily on the first
eval call there. A session switch selects that environment (rebuilding it
from the session's entries if it is not retained). The kept-definition index
tracks the session's definitions and the shared project/global files.

**The tool.** `:tool/eval` joins `:bundle/minimal` beside `:tool/run_code`,
with `:default-enabled? false` (S4.5): off until selected or enabled by
`/config`/`/tools`.

```jsonc
{"code": "Clojure forms. Definitions persist for this session; kept project/global definitions are shared across sessions. Omit when only :keep/:drop acts.",
 "timeout": 0,
 "keep": [["user/f1", "project"], ["user/f2", "global"]],
 "drop": ["user/old-fn"],
 "max-output-bytes": 0}
```

`code` is optional when `:keep` or `:drop` is present, so a tracked definition
can be promoted, moved or dropped without evaluating forms. `:keep` may also
name `:session`, demoting a shared definition back to this session.

Result content is the emitted text (or a silent success's final value, the
REPL rule) plus one line per kept, moved or shadowed definition — `[eval:
kept user/report → session]`, `[eval: kept user/report →
.kmet/eval/user/report.clj]`, `[eval: moved user/report
~/.kmet/agent/eval/… → .kmet/eval/…]`, `[eval: shadowing user/report
(session; file unchanged: .kmet/eval/…)]`; `:details` carries `:timeout
:elapsed-ms :kept :dropped :shadowed :sources :calls :error`. Deadline,
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

`run_code` becomes "build a fresh context, run, discard"; `eval` becomes
"rearm, seed, run" against the current session's environment. The context's
`:load-fn` and `:interrupt-fn` are fixed at `sci/init`, which is why the
interrupt and per-call namespaces are re-aimed through atoms rather than
rebuilt.

**Persistence.** Project/global definitions are one file per definition, the
verbatim source slice (`rewrite-clj.parser/parse-string-all`, its `:list`
children, `node/string`; rewrite-clj is already an app dependency and a
shared extension lib):

```
<project>/.kmet/eval/<ns>/<name>.clj        :scope :project   (cfg/project-dir)
~/.kmet/agent/eval/<ns>/<name>.clj          :scope :global    (cfg/get-agent-dir)

;; kmet eval: kept by the eval tool
(ns user)
(defn report [x] …)
```

Session definitions are `:custom` entries with `:custom-type
:eval-definitions`, one per successful call **that changes the session's
definition set** (the verbatim slices, keyed by `<ns>/<name>`). A call that
defines nothing and carries no `:keep`/`:drop` delta — a pure expression or
an output-only eval — writes no entry; a call whose only definitions are
source-provided keys writes none either; a no-op keep/drop writes none.

```clojure
{:role :custom
 :custom-type :eval-definitions
 :data {:version 1
        :defs [{:name "user/report" :ns "user" :kind :defn
                :source "(defn report [x] …)\n"}]
        :removed ["user/old-fn"]}}
```

They are read lazily on the first eval in the session, not when the session
opens: load the shared files, fold the active branch's entries in order
(`:defs` add/replace, `:removed` delete), filter source-provided keys, then
evaluate the folded definitions. The environment records the branch leaf it
was built from; a branch/reset/switch invalidates it and the next eval
rebuilds from the active branch. Entries are append-only and branch-scoped:
`fork-session` copies them, an imported or `fork-from` session folds them
like a resume, a branch to an earlier entry drops the later definitions, and
deleting the session deletes them.

On compaction (`:session-compact`, after the compaction entry), append one
snapshot entry:

```clojure
{:role :custom
 :custom-type :eval-definitions
 :data {:version 1
        :snapshot true
        :defs [{:name "user/report" :ns "user" :source "…"}]}}
```

The fold resets to a snapshot's `:defs` and ignores earlier definition
entries, so removed names and superseded sources drop out of the effective
state; old entries stay in the append-only log (they are branch parents).
A branch to before the snapshot folds the old entries, and a fork copies the
snapshot. Snapshot generation uses the pure branch fold, not the live SCI
context, so it also works before the session's first eval; a failed snapshot
append is reported and does not fail the compaction, and the old entries
remain loadable.

Kept forms: top-level `def`, `defn`, `defn-`, `defonce`, `defmulti`,
`defprotocol`, `defrecord`, `deftype`, `defmacro`, `declare` (keyed by the
defining symbol), and `defmethod` (keyed `<multi> <dispatch>`, file name or
store key sanitized). A def nested in `do`/`when`/`comment` is not kept —
nodecode keeps it, kmet deliberately does not, because the definition needs a
stable address (the file name in a shared scope, the session-store key in a
session).

Load order for a session: global files first, then project files, sorted by
path, then the session's folded definitions. Project wins a global name
collision, and the session definition wins a shared one. Shared files are
read only when the environment is built: another session sees a later
`:keep` only on its first eval, a rebuild (branch/reset/switch), resume, fork
or import — not on its next ordinary eval. Its own session definition of the
same name still wins. A file or session entry that throws is recorded under
`:failures` and reported by the next result and by `/eval-sources`; the agent
still works.

Every top-level definition is tracked in the session's index with its
verbatim source; a new definition is session-scoped and recorded as a
session entry, except source-provided keys (below). `:keep` is the only
writer of the shared scopes: an entry `[name scope]` saves a definition to
`:project` or `:global`, moves an existing shared definition (`:project` ↔
`:global`; write the new file first, then remove the old), or demotes one
with `[name :session]`. It may name a tracked definition from an earlier
call, so a definition can be promoted, moved or demoted without re-sending
its source. A redefinition without `:keep` updates the session copy; for a
shared name it creates a session shadow and leaves the file untouched
(reported; the session wins at load). `:drop` removes the session copy and
both shared files. A name in both `:keep` and `:drop`, or a `:keep` naming
neither a tracked definition nor one of this call's top-level defining
forms, is an error; validation happens before evaluation. A call with
`:keep`/`:drop` and no `:code` performs just those operations. Shared files
take the EDN store's file lock (`kmet.libs.edn-store/with-file-lock`),
because two sessions can save at once; session entries are appended under
the session's own lock (`append-entry`/`append-custom-entry!`). A failed
move rolls the new file back and reports, leaving the old file
authoritative.

Source-provided verb keys — the builtin `user/edit`, `user/write-file`, and
after E17 `clj/edit-form`, `clj/replace-sexp`, `clj/paren-repair`, or a name
an extension source contributes (`license/add!`) — may be redefined in the
live session but are never stored: `:keep` naming one is an error, no session
entry and no shared file is written, and `:drop` removes the redefinition so
the next `seed!` restores the source's value. The service remembers the keys
a source has contributed, so unloading the source does not make its keys
storable.

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
  stored or kept (see § Persistence).

**Discovery.** `(eval/sources)` and `(eval/kept)` inside the environment
(`(eval/help)` summarises all three tiers; `(eval/kept)` lists this session's
definitions and the shared files), and a read-only `/eval-sources` builtin
command for the human. No system-prompt section: it would sit in the cached
prefix and vary per session, so it would invalidate the whole prefix every
time one changed; a source that needs the model to know something unprompted
registers a skill, the existing seam.

### Steps

Nine steps, each one commit; steps 1–2 are behaviour-preserving and every
later step is additive. Estimated ~2,000 new lines, ~1,000 moved (step 1)
plus ~150 moved (step 2).

1. **`kmet.app.tools.script`.** Move the reusable sandbox out of
   `run_code.cljc`: constants, the three dynamic vars (`*enabled-tools-fn*`,
   `*tool-hooks*`, `*assistant-message*`), helpers, capture, capability
   namespaces, `host-class-map`, `sandbox-load-fn`, `context`,
   `call-namespaces`, `active-surface`, the bridge pool, `interrupt-fn`,
   `start-eval-thread!`, `assemble-result`, `realm`. New public seams as in
   § Shape; message text parameterized by the tool name so `run_code`'s
   strings stay byte-identical (`[run_code output truncated …]` is asserted).
   `script-tool-names` = `#{"run_code" "eval"}` — neither tool nests the
   other. Update the moved dynamic vars' bind sites: `kmet.app.loop.clj:1113`
   and `:1120`, `kmet.app.loop.tool_exec.clj:292`,
   `test_run_code.clj` (three `binding`s).
2. **The file ops.** `kmet.app.tools.edit/edit-file` (mutation lock, BOM,
   line endings, `edit-diff/apply-edits-to-normalized-content`, write, diff)
   public, `execute` = op + rendering; `kmet.app.tools.write/write-file`
   public, `execute` = op + success string. Stays in `kmet.app.tools.*`
   (E11) — no libs move, no lock move, tool tests unchanged.
3. **The service and the tool, memory only.** `kmet.app.eval` with `value`,
   lazy context, interrupt holder, sticky ns, lock, `:dispose!`; the driver
   provides the default like `:sandbox`; the handle's `:dispose!` calls the
   service's. `kmet.app.tools.eval` + the `:tool/eval` row in
   `:bundle/minimal`, a sharp description against `run_code` (and one line
   added to `run_code`'s pointing at `eval`). No persistence, no verbs.
4. **Session scope, lazy fold and `:keep`.** Replace the agent-wide context
   with one lazy environment per session, keyed by the run's session. Session
   definitions are `:custom` `:eval-definitions` entries, one per successful
   call that changes the session's definition set (`:defs`/`:removed`,
   source-provided keys excluded); a call with no definitions and no
   keep/drop delta writes no entry, and neither does a source-key-only
   redefinition. The first eval in a session loads shared files and folds the
   active branch, and a branch/reset/switch invalidates the environment so the
   next eval rebuilds. Shared files are load-time: another already-built
   session does not re-read them on each eval; it sees a keep only when its
   environment is built, and its own session copy still wins. On compaction (`:session-compact`) append a
   `:snapshot true` entry after the compaction entry, generated from the pure
   branch fold; the fold resets to the latest snapshot and ignores older
   definition entries, and a failed snapshot append does not fail the
   compaction. `:keep` promotes or moves to project/global, or
   demotes to session; `:drop` removes every copy; a shared name redefined
   without `:keep` becomes a session shadow. Result lines, `:details` and
   `(eval/kept)` report session vs file entries. Fork and import fold the
   source session's definitions like a resume; imported definitions run with
   the same authority, so the limit is documented. Code-less `:keep`/`:drop`
   calls. Tests and docs
   must select the opt-in `:tool/eval` row explicitly (S4.5).
5. **`:kmet/eval-source` and `:core`.** Registry fns
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
   records each source's keys as non-storable for the agent: a model
   redefinition shadows one in session scope, `:keep` refuses it and `:drop`
   restores the source's value. `(eval/sources)`, `(eval/help)` and
   `/eval-sources`.
6. **The extension ABI.** `kmet.extension/register-eval-source!` /
   `unregister-eval-source!`; the api keys in
   `kmet.app.extensions.core/create-extension-api`; `create-nullable-api`
   gains `:eval-sources`; `modules/app/src/kmet/extension.md` +
   `docs/extensions.md` (the `license` example, the `user` prohibition, "use
   the verbs within the call"). The loader's opaque `:meta` (S4) is the later
   static projection for extension-provided eval verbs, e.g. `:meta {:eval
   [{:name "clj/edit-form"}]}`; the live `:kmet/eval-source` registry stays
   authoritative, and the metadata reader lands only when a consumer needs it.
7. **The eval vocabulary.** `(eval/patch 'name old new)` (structural subform
   replacement through rewrite-clj, re-evaluate, write back to the same
   scope) and `(eval/forget 'name)`. Deferred out of v1 so the structural
   editor gets its own review.
8. **The Clojure editing engine and verbs.** Extract
   `kmet.extensions.clojure.edit-util` to `kmet.libs.clj-edit` — the shared
   engine (`edit-pipeline`, `edit-source`, `replace-form`,
   `format-form-in-isolation`, `repair-delimiters`, parse and duplicate
   checks) — and have both the clojure extension's tools and the eval core
   require it. The `:core` source always ships `clj/edit-form`,
   `clj/replace-sexp` and `clj/paren-repair` (map args and result parity with
   the tools), so the rich editor needs neither `:ext/clojure` nor a tool
   schema. Add the live `eval/edit-form`: find the definition's stored source
   (shared file or session entry), apply the edit to the source, re-evaluate,
   then write and update the index (`edit-source` is the pure step);
   `eval/patch` stays the minimal subform op. The extension keeps the tools,
   hooks and skill and drops its eval source (extension-specific verbs may
   return later); it is not added to `:bundle/minimal`. Resolve paths against
   the source `:cwd` in the verbs and `(:cwd ctx)` in the tools, which become
   `:contextual?`; no process-cwd assumption (E17).
9. **Docs and catalog.** `docs/architecture.md` § Service catalog (`:eval`) and
   § Tools and the script sandbox (the eval tool, the extraction, and the
   scoping of "a `def` in one call is invisible to the next" to `run_code`);
   `bb generate-catalog`; `docs/pi-alignment.md` § codemode (the persistent
   `store`/`load` half and the extension analogue are no longer missing);
   `modules/app/src/kmet/design.md` § Open at HEAD 5 (one line: a persistent
   context makes Jolt's `eval-in` more attractive later; the decision is
   unchanged); the tier table and the clojure extension's remaining surface
   (E17 and the post-track task).

### Test plan

- **Step 1:** `test_run_code.clj` passes unmodified apart from the three
  `binding`s — the strongest available proof that the extraction preserved
  behaviour. One test asserts the inner-call hook payload still carries the
  assistant message across the moved dynamic var.
- **Step 2:** a table test runs one edit through `registry/execute-tool` and
  through the verb and asserts identical file bytes, and the same
  `:edit-error` message for not-found, duplicate and no-change.
- **Step 3–4:** two `value` instances behave as two agents (definitions do
  not cross); two sessions of one agent do not share definitions; a new
  session starts empty, resume and switch restore that session's definitions,
  and a process restart restores them from the session log; a branch
  inherits its parent's definitions and a deleted session removes its store;
  project/global `:keep`s load into every session, with a session copy
  winning a shared name; `:keep` writes/moves the expected shared files and
  session definitions append only to the session log, never a project file; a
  definition can be promoted, moved or demoted in a later call without
  redefining it; load order is global→project→session; a broken file/entry
  becomes a `:failure` and the agent still evals; a saved name redefined
  without `:keep` leaves its shared file bytes unchanged and is reported as
  a session shadow; an explicit `:keep` then writes it; `:drop` removes the
  session copy and both shared files; a name in `:keep` and `:drop`, or a
  `:keep` for an unknown name, errors; a builtin verb can be redefined in
  session scope but `:keep` for it errors and writes nothing, and `:drop`
  restores the core seed; `defmethod` addresses are distinct per dispatch;
  `(eval/kept)` matches the session index and shows session entries with
  their shadows.
- **Step 4 (session EDNL):** nothing is folded before the first eval in a
  session; the first eval folds only the active branch; a branch/reset/switch
  invalidates the environment and rebuilds it; a compaction appends a
  snapshot, the fold resets to it and drops tombstones; a branch to before the
  snapshot sees the old entries; a fork copies the snapshot; a fork/import
  loads the source session's definitions; a snapshot append
  failure leaves the old entries loadable; definition entries never project
  into LLM context; malformed entries become `:failures`; a pure expression
  eval, a source-key-only redefinition and a no-op keep/drop append no
  session entry; a shared `:keep` is not visible to another already-built
  session on its next eval, only after that session rebuilds, and its session
  copy still wins.
- **Step 5–6:** a source's fns are callable on the next call; a source's
  `:verbs` and the environment's `user/edit` behave identically (assert by
  behaviour — same file bytes — not by identity); unload removes the names on
  the next call and leaves the model's definitions; a throwing source
  contributes nothing; a source contributing `user` or `clj`, or a builtin
  key, is refused; an extension verb can be redefined in session scope but
  `:keep` for it errors and writes nothing, and `:drop` restores the source
  value while mounted; a contributed key stays non-storable after its source
  unloads; a source that captures `:verbs` past its call is documented as
  unsupported (no test); the `/eval-sources` output names sources, kept
  definitions and failures.
- **Step 8:** the shared engine's tests; `(clj/edit-form …)` and
  `clojure_edit` on the same fixture produce identical bytes and the same
  bad-form message, with the verb core and the tool present only when
  `:ext/clojure` is mounted; `eval/edit-form` updates the live definition,
  writes only after a successful re-eval, and leaves the store/index
  untouched on failure; relative paths resolve under the source `:cwd` while
  the process cwd differs.
- **Both hosts:** the eval tests run under babashka and Jolt's views
  (`bb test-changed` plus `jolt test` where the tree has it); `sci/init`
  once per session and the `swap!`-based `seed!` are the two host-sensitive
  points.

### Limits, stated in the docs

- Same authority as `bash`. Kept shared definitions run in every session;
  a session definition runs when that session is resumed, forked or imported.
  That is the feature and the risk. Shared files are plain text in the project
  for review; session definitions live in the session EDNL log under the
  sessions dir, and no sandbox is claimed.
- A host-blocked call still hits the existing interrupt-grace limitation
  (no process isolation — `modules/app/src/kmet/design.md` § Open at HEAD 5).
- Nothing is kept from `run_code`; nothing from `eval` is visible to
  `run_code` or to a subagent; sessions have separate logs and environments,
  and two agents share neither.
- The session log and kept files are unbounded (the model can define as much
  as it wants); a compaction snapshot bounds the fold, not the bytes. The
  capture and result budgets are the shared script layer's.

### Decisions

**E1 — a second tool, not a `run_code` flag.** `run_code`'s fresh context is
load-bearing for its output economy and for 98% of its calls; a stateful mode
behind an argument would make every one of them stateful in the model's
reading. Two rows, two names, two contracts.

**E2 — `:eval` is a per-agent service from the factory, not a root row.** A
root row could never be narrowed for one agent (the kernel forbids a child
shadowing an ancestor's service) — the same reason `:shell`, `:sandbox` and
`:permissions` are factory-provided. A preset row may mount first and
configure it; the `:tool/eval` row is what a profile removes to hide the tool.

**E3 — session lifetime.** Definitions belong to the session: `new-session!`
starts with no session definitions (only the shared project/global ones),
`resume-session!` restores them by the lazy fold, switching sessions swaps
environments, and a branch inherits its parent's definitions up to the branch
point. Session scope is durable in the session EDNL log; a compaction
snapshot folds its history. `:keep` promotes a definition to project/global
for cross-session sharing. Subagents are their own agent and sessions, so
definitions never cross. Fork and import load the source session's
definitions like a resume; an imported file carries executable definitions.

**E4 — session scope is private; `:keep` writes shared files.** A
model-facing default of "write into the user's repository" is not a default
kmet takes. Every new definition is recorded as a session entry, never in
the project. Only `:keep` entries write `:project`/`:global`;
`:keep :session` demotes one back. Source-provided keys are never stored.
`:keep` may be used with no code, so a tracked definition can be promoted,
moved or demoted later; `:drop` removes every copy.

**E5 — one address per definition, verbatim source.** In a shared scope the
file is the address (`<ns>/<name>.clj`); in the session log the folded name
is. Lookup, deletion and review are address operations either way.
`pr-str` of the parsed form would normalise away reader sugar; the
rewrite-clj slice keeps the bytes the model wrote.

**E6 — session definitions are private; a shared redefinition shadows until
kept.** Every top-level definition is tracked with its verbatim source and
recorded as `:eval-definitions` session entries, except source-provided
keys. A redefinition without `:keep` updates the session copy; for a
project/global name it creates a session shadow and leaves the shared file
untouched (reported; the session wins at load). `:keep` writes/moves a shared
definition and appends a session removal, or demotes one with `:keep
:session`; there is no silent re-scope. `:drop` removes every copy, because a
silent delete is the one outcome a model cannot review.

**E7 — top-level defining forms only.** A def inside `do`/`when`/`comment` is
not kept, unlike nodecode: a computed definition has no stable address, and
the alternative (an aggregate file or session entry per call) makes
per-definition deletion and review impossible.

**E8 — `defmethod` keyed by `<multi> <dispatch>`.** Otherwise every method of
one multimethod lands on one address and the last one wins at load. The
sanitized dispatch in the shared file name (and the store key) is the
address; `:kept` names the full key.

**E9 — eval sources are values; neither tool nests the other.** The
contribution crosses the isolation boundary the way every other capability
does (functions and data), so no source can reach a host service it was not
given. `script-tool-names` excludes both tools from any script's surface, so
`run_code` cannot start an `eval` and an `eval` cannot start another.

**E10 — sources cannot redefine builtins; the agent's overrides are never
stored.** Later-registration-wins is app-wide, so an extension that took
`edit` would silently replace the built-in in every agent. The rule is
enforced by the service: a source contributing to `user`, `clojure.core`,
`tools`, `sandbox`, `eval`, `fs` or `clj`, or contributing a builtin key, is
refused and reported. The agent itself may redefine a builtin or extension
verb in session scope, but a source-provided key is never stored: `:keep`
naming one is refused, there is no session entry or project/global file, and
`:drop` restores the source's value.

**E11 — host operations cross as values, not as requirable namespaces.**
`sources-fn` receives `:verbs` — the same fns the script calls — so an
extension's own logic can compose `(edit …)` without `kmet.app.*` becoming
requirable and without moving the file ops or the mutation lock into `libs`.
A libs extraction happens when a consumer needs the op *outside* a source; E17
is that consumer for the Clojure engine (`kmet.libs.clj-edit`).

**E12 — discovery is in-code, plus one read-only command.** `(eval/sources)`,
`(eval/kept)`, `(eval/help)` and `/eval-sources`; no system-prompt section.
A dynamic per-session section sits in the cached prefix and varies per
session, so it would invalidate the whole prefix every time one changed. A
source that needs unprompted presence registers a skill, the existing seam.

**E13 — `:core` ships the language-neutral file verbs plus the Clojure editing
verbs.** `edit` and `write-file` are the two operations a bare profile (no
extensions mounted) needs to loop over files; `clj/edit-form`,
`clj/replace-sexp` and `clj/paren-repair` join them after E17 because `eval`
is a Clojure environment and the engine is bundled. Other tool-specific verbs
(search, nREPL, …) still come from the extension that owns the tool.
`read`/`ls`/`glob`/`grep` are deliberately absent: `slurp` and the `fs` alias
cover them, and a pure-Clojure grep would need gitignore and binary-file
rules we would get wrong (`rg` via `sh` is the better answer).

**E14 — declined.** Source strings evaluated in the eval context (would give
an extension `(edit …)` by name, and full authority over the environment for
nothing the value contract cannot express); `*`/`/` value chains across calls
(no REPL value history in SCI); `$` named strings (the tool's argument is the
code); `fork` (subagents, not this tool); an event vocabulary for kept
paragraphs (no reader); per-agent eval sources (the registry is app-wide,
like tool sources); auto-save on any definition.

**E15 — names closed.** `/eval-sources` (the alternative `/eval` is recorded
and declined for v1: a read-only report that looks like an evaluator invites
the wrong expectation); `clj/*` for the Clojure verbs (core-provided after
E17; not the tool names `clojure_*`, and never bare).

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
the first call), so removing `:tool/eval` leaves a service with no reader, the
same accepted shape as `:sandbox` without `:tool/run_code`.

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

## Post-track task: the rest of the clojure extension

After the eval tool and E17's core verbs land, reconsider the remaining
clojure extension surface: the `clojure_edit` and deferred
`clojure_edit_replace_sexp`/`clojure_paren_repair` tools, the paren-check
hooks, the `clojure-edit` skill, `clojure_eval` (nREPL), and the `:ext/clojure`
row itself. Decide with usage data (tool calls vs. eval verb calls,
schema/prompt cost, nREPL use), not in advance:

- **Promote** the rest into core (or a `:bundle/clojure` recommended by
  default) if the tools are expected everywhere and the eval verbs do not
  cover direct tool calls.
- **Drop** the extension if the eval verbs make the tools redundant and the
  hooks/skill/nREPL do not justify a separate artifact; keep the shared
  `kmet.libs.clj-edit` engine in core and move nREPL only if it has a home.

Do not duplicate the engine in either outcome. Record the decision here and
update `docs/extensions.md`/`extension.md` with the chosen surface.

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
| unbounded tools at the trigger (`run_code`, extensions) | 1.9% of tool-result chars |
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
