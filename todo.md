# Todo

Two tracks remain. Landed work — the `github-copilot` anthropic auth, the
prompt-caching parity pass, the around tool hook, the remaining openai-compat
keys, **P5** (tool exposure, the declared/callable split, the shared ranker and
`tool_search`), **P5.5** (the declared order and the one promotable
derivation), **P6** (mid-conversation tool changes without a full re-prefill),
**P6 residuals 1–3** (Codex's declared-set markers, the anchored wire deltas,
and `tool_search`'s recorded-loadout filter), the P6 unmount/remount residual,
**S1** (config and resolution for `:enabled-tools`), **S2** (the extension
ABI for tool selection) and **S3** (the `/tools` scope and save flow) — is out
of this file;
it lives in git history. The context and token tracks are dropped too — see
§ Deferred.

| track | what it fixes | status |
|---|---|---|
| **Tools: cross-session selection** (S) | `/tools` writes only to the session branch and `:default-tools` reaches built-ins only, so "extension tool X stays off in this repo" has no home | S3 done; **S4 next** |
| Persistent eval and eval sources | `run_code` throws its context away per call, so a helper the model writes is re-pasted in full on every turn; extensions can contribute tools but not a definition the model can call from code | steps 1–3 done; **step 4 next** |

The cross-session selection track's config review surface and docs (S4/S5)
remain; persistent eval lands whenever — it touches neither a wire nor the
tool registry.

## Tools: cross-session selection in both scopes

Uses the vocabulary that landed with P5/P6: `:enabled-tools` selects over the
set those tracks split into `callable` and `declared`, and a `/tools` row's
provenance only means something once those are two derivations. Exposure itself
stays out of this track: a per-tool or per-scope override would make a
`:sandbox` tool un-disableable.

### Why

`/tools` (bundled `tools` extension) records its selection in the session's
branch only — nothing survives into a new session — and `kmet config`'s `Tools`
group writes `:default-tools`, which reaches **built-ins only**
(`default-tool-exclusions` subtracts from `builtin-tool-names`,
`registry.clj:328-336`; `docs/configuration.md:144-146`). The one decision users
actually make here — "extension tool X stays off for this project" — has no
home. Add `:enabled-tools` as the config spelling of the runtime slot that
already exists, and give `/tools` the discovery plus a scope target.

### Decisions

- **`:enabled-tools` is absolute selection over all live tools**;
  `:default-tools` stays the built-in-only, pi-parity key. Precedence already
  exists: `selection-opts`/`tool-selection` prefer `enabled`
  (`state.clj:536-566`), so `:enabled-tools` wins and `:default-tools` is the
  fallback — no new rule.
- **Deltas, not snapshots.** `+name`/`-name` against everything mounted, so a
  tool that appears later defaults on and an MCP catalog that connects late is
  not silently dropped.
- **Two scopes, one per-row model**: global on/off, project
  load/unload/inherit (the `kmet config` vocabulary,
  `resource_config.clj:231-244`). No tab bar — a scope line with the target
  file plus per-row provenance; Tab switches, same as `kmet config` (`:23`,
  `:700-706`).
- **Exposure stays out.** It is a source-level property (P5's `:exposure`),
  and a project-scope exposure override would make a `:sandbox` tool
  un-disableable (`callable = … ∪ sandbox`).
- **`/tools` discovers, `kmet config` reviews.** The config screen never loads
  plugins (`tools/config.clj:58-60`), so it sees built-ins plus names a settings
  scope or an extension manifest names; `/tools` runs with the live registry and
  is the only place a first delta can be authored. A directory extension may
  carry an optional `:meta {:tools [{:name …} …]}` static projection for the
  config rows; the loader carries `:meta` opaquely and `/tools` stays
  authoritative. Single-file extensions and dynamically registered tools stay
  invisible until a settings scope names them.

### Steps

**S4 — `kmet config` review surface.**

- Carry the manifest's optional `:meta` through `manifest-info` →
  `resolve-extension` → `ext-rows/entry` (a new `:meta` key; the loader does
  not validate or interpret its contents). No plugin loading.
- `builtin-tool-items` (`:73-102`) → `tool-items`: built-in rows keep
  `:default-tools`; names in `:enabled-tools` (either scope) that are not
  built-ins get rows that write/clear the delta, shown even though the tool
  cannot be resolved statically.
- Add extension rows from `:meta :tools`: only a properly filled list — a
  sequential collection of maps with a non-empty string `:name` — produces
  rows; invalid entries are dropped, and absent/malformed/empty means no rows.
  No warning: `:meta` is opaque and this is a static projection, while
  `/tools` remains the live authority. Dynamic tools and single-file
  extensions stay invisible here unless a settings scope names them.
- `toggle-selected!` (`resource_config.clj:585-615`) gains the
  `:enabled-tools` branch.
- No plugin loading; `mounted-tool-names` (`:45-73`) unchanged.

**S5 — docs.**

- `docs/configuration.md:140-196` — `:enabled-tools`, both scopes, the delta
  rule, precedence; fix the `/reload` sentence at `:194-196` (it contradicts
  `builtins.clj:676-679`); and say that `tool_search` is a selection row like
  any other — naming it out of `:enabled-tools`/`:default-tools` orphans every
  `:deferred` tool, because nothing else can promote one (S1's warn is the
  other half).
- `docs/pi-alignment.md:261` (settings parity; CLI flags still the gap) and
  `:56` (no project trust — note the fail-closed argument).
- `modules/app/src/kmet/extension.md` + `docs/extensions.md` (the two new ABI
  functions, a stated break), `extensions/README.md` and the `tools.clj`
  docstring; also document the opaque `:meta` manifest key and that `:tools`
  is a static projection for config (only properly filled entries show,
  `/tools` is authoritative).

### Test plan

- `modules/app/test/kmet/ui/tui/test_resource_config.clj`: an `:enabled-tools`
  name that is not built-in renders a row; undo clears the entry; a built-in row
  still writes `:default-tools`.
- Manifest metadata: `resolve-extension`/`ext-rows/entry` carry `:meta`
  verbatim (absent → nil), a malformed value does not fail the load, and
  `kmet config` renders only valid `:meta :tools` names (absent/malformed → no
  rows; extra keys ignored).
- `modules/app/test/kmet/app/test_bundled_extensions.clj`: the `tools` artifact
  still validates after the ABI and dialog change.
- ABI: project write lands in `.kmet/settings.edn` as a delta; `tool-selection`
  reports the right `:origin` per scope; global keeps modifier shape.
- Gates: `bb test-changed`, `bb lint-changed`, `bb format-check-changed`.

### Done when

An extension tool turned off in project scope stays off in a fresh session in
that repo and on in another; `/tools` shows where each row's state comes from
and which file Tab will write; a built-in's toggle still writes
`:default-tools`; `kmet config` can undo a delta `/tools` wrote without loading
a plugin; and a directory extension with a valid `:meta :tools` shows those
rows while one without (or with a malformed list) shows none, with `/tools`
still listing the live set.

## Persistent eval and eval sources

Status: steps 1–3 done (the shared script layer, the file ops and the
memory-only eval service); step 4 next.
Decisions closed (E1–E16); independent.
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

- **per agent, not per process.** The environment belongs to the agent's
  child ctx and is disposed with it; a subagent gets its own, never the
  parent's.
- **persistence is opt-in per call.** Nothing is written into the user's
  repository unless the call asks for it, and the file is reviewable text.
- **extensions contribute definitions the way they contribute tools** — a
  registry tag, an owner path, one unload sweep. No new ABI shape.

### What exists today

| piece | today | this track |
|---|---|---|
| script sandbox | `kmet.app.tools.run-code`, one SCI ctx per call, ~1,000 lines of machinery | moves to `kmet.app.tools.script`; both tools use it |
| file ops | inline in `kmet.app.tools.edit` / `.write` `execute` | extracted as ops; tool and verb share them |
| per-agent services | `:shell` (atom), `:sandbox` (data), `:permissions` (memory) — the agents factory provides each | `:eval` joins them |
| extension contribution | `register-tool!`, `register-tool-source!` (`:kmet/tool-source` on the `:tools` handle), commands, skills | `register-eval-source!` (`:kmet/eval-source`), same handle, same sweep |
| model's code tool | `run_code` only | `run_code` + `eval` |

`run_code`'s per-call context costs roughly 3 ms of namespace copying plus
9 ms of `sci/init` (its own comment); a per-agent context pays that once per
agent instead of once per call.

### Shape

Three tiers of what a script can call, and only the third is a source:

| tier | provided by | examples | removable |
|---|---|---|---|
| primitives | `kmet.app.tools.script`, shared with `run_code` | `slurp`, `spit`, `sh`, `p/*`, `fs/*`, `json`, `emit`, `emit-image`, `tools/list`, `tools/describe`, `tools/call`, `(cwd)`, `(env k)`, `(now)`, `(sleep ms)`, `(spawn f)` | no |
| eval vocabulary | the `:eval` service (it needs kept-definition state) | `(eval/kept)`, `(eval/sources)`, `(eval/help)`, `(eval/patch …)`, `(eval/forget …)` | no |
| verbs | the `:core` eval source shipped by the eval tool, and every mounted extension's own source | `edit`, `write-file`; `clj/edit-form`, `clj/replace-sexp`, `clj/paren-repair` | yes (`{:core false}`) |

**The service.** `:eval` is provided per agent by the agents factory
(`kmet.app.loop.driver`, beside `:shell`/`:sandbox`/`:permissions`), so a
child ctx can never shadow it and a preset may configure it first. Value is a
map of fns (`docs/architecture.md` § Service catalog's convention):

```clojure
{:eval!    (fn [{:keys [code timeout scope drop signal on-update ctx]}])
 :kept     (fn [] [{:name "user/f" :scope :project :file "…"} …])
 :sources  (fn [] [{:id :core :doc "…"} …])
 :failures (fn [] [{:file "…" :error "…"} …])
 :dispose! (fn [])}
```

It owns one SCI context, one interrupt holder, one sticky current namespace
(`sci/*ns*` is thread-local, so the last namespace a call entered is
re-entered at the start of the next), one `Object` lock (a parallel batch
serializes; the deadline clock starts *after* the lock is taken), and the
kept-definition index. The context is built lazily on the first eval call —
never at agent creation.

**The tool.** `:tool/eval` joins `:bundle/minimal` beside `:tool/run_code`:

```jsonc
{"code": "Clojure forms. Definitions persist across calls and across sessions of this agent.",
 "timeout": 0,
 "scope": "project | global — omit = this agent only (memory)",
 "drop": ["user/old-fn"],
 "max-output-bytes": 0}
```

Result content is the emitted text (or a silent success's final value, the
REPL rule) plus one line per kept definition — `[eval: kept user/report →
.kmet/eval/user/report.clj]`; `:details` carries `:timeout :elapsed-ms :kept
:dropped :sources :calls :error`. Deadline, abort, the sandbox realm, capture
and the output budget are the shared script layer's, not a second copy.

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
"rearm, seed, run" against the agent's context. The context's `:load-fn` and
`:interrupt-fn` are fixed at `sci/init`, which is why the interrupt and
per-call namespaces are re-aimed through atoms rather than rebuilt.

**Persistence.** One file per definition, the verbatim source slice
(`rewrite-clj.parser/parse-string-all`, its `:list` children, `node/string`;
rewrite-clj is already an app dependency and a shared extension lib):

```
<project>/.kmet/eval/<ns>/<name>.clj        :scope :project   (cfg/project-dir)
~/.kmet/agent/eval/<ns>/<name>.clj          :scope :global    (cfg/get-agent-dir)

;; kmet eval: kept by the eval tool
(ns user)
(defn report [x] …)
```

Kept forms: top-level `def`, `defn`, `defn-`, `defonce`, `defmulti`,
`defprotocol`, `defrecord`, `deftype`, `defmacro`, `declare` (keyed by the
defining symbol), and `defmethod` (keyed `<multi> <dispatch>`, file name
sanitized). A def nested in `do`/`when`/`comment` is not kept — nodecode keeps
it, kmet deliberately does not, because the file name is the address and a
computed definition has no stable one.

Load at context creation: global files first, then project files, sorted by
path; project wins a name collision. A file that throws is recorded under
`:failures` and reported by the next result and by `/eval-sources`; the agent
still works. A name that is already saved **keeps its scope** on
redefinition, so a re-save cannot silently revert at the next start;
`:scope :agent` opts a new definition out and never deletes a file; `:drop`
unmaps and deletes in both scopes. Writes take the EDN store's file lock
(`kmet.libs.edn-store/with-file-lock`), because two agents of one process can
save at once.

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
  contributes (`license/add!`). Precedence: among sources, later registration
  wins; the agent's own definitions always win (the service records every
  `def*` target the model evaluated and `seed!` skips those names).

**Discovery.** `(eval/sources)` and `(eval/kept)` inside the environment
(`(eval/help)` summarises all three tiers), and a read-only `/eval-sources`
builtin command for the human. No system-prompt section: it would sit in the
cached prefix and vary per agent, so it would invalidate the whole prefix
every time one changed; a source that needs the model to know something
unprompted registers a skill, the existing seam.

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
4. **Persistence.** The store, the kept-form set, the verbatim slice, load
   order, failures, scope inheritance, `:drop`, `:details`. `(eval/kept)` and
   the file paths in the result land here.
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
   does. `(eval/sources)`, `(eval/help)` and `/eval-sources`.
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
8. **The clojure extension's verbs.** `clj/edit-form`, `clj/replace-sexp`,
   `clj/paren-repair`, sharing `edit_util.clj` (`edit-pipeline`,
   `replace-form`, `format-form-in-isolation`, `repair-delimiters`) with its
   tools; resolve paths against the source's `:cwd` (and fix the tools' own
   process-cwd assumption, which the `:contextual?` ctx already answers).
9. **Docs and catalog.** `docs/architecture.md` § Service catalog (`:eval`) and
   § Tools and the script sandbox (the eval tool, the extraction, and the
   scoping of "a `def` in one call is invisible to the next" to `run_code`);
   `bb generate-catalog`; `docs/pi-alignment.md` § codemode (the persistent
   `store`/`load` half and the extension analogue are no longer missing);
   `modules/app/src/kmet/design.md` § Open at HEAD 5 (one line: a persistent
   context makes Jolt's `eval-in` more attractive later; the decision is
   unchanged).

### Test plan

- **Step 1:** `test_run_code.clj` passes unmodified apart from the three
  `binding`s — the strongest available proof that the extraction preserved
  behaviour. One test asserts the inner-call hook payload still carries the
  assistant message across the moved dynamic var.
- **Step 2:** a table test runs one edit through `registry/execute-tool` and
  through the verb and asserts identical file bytes, and the same
  `:edit-error` message for not-found, duplicate and no-change.
- **Step 3–4:** two `value` instances behave as two agents (definitions do
  not cross); a definition survives a later call; a stale context is not
  visible to `run_code`; `:scope :project` and `:global` write the expected
  files and `:agent` writes none; load order is global→project with project
  winning; a broken file becomes a `:failure` and the agent still evals; a
  saved name redefined without `:scope` writes to its existing file; `:drop`
  deletes in both scopes; `defmethod` files are distinct per dispatch;
  `(eval/kept)` matches the store.
- **Step 5–6:** a source's fns are callable on the next call; a source's
  `:verbs` and the environment's `user/edit` behave identically (assert by
  behaviour — same file bytes — not by identity); unload removes the names on
  the next call and leaves the model's definitions; a throwing source
  contributes nothing; a source contributing `user` is refused; a source that
  captures `:verbs` past its call is documented as unsupported (no test);
  the `/eval-sources` output names sources, kept definitions and failures.
- **Step 8:** in `extensions/clojure/test`, `(clj/edit-form …)` and
  `clojure_edit` on the same fixture produce identical bytes; a bad form
  produces the tool's message.
- **Both hosts:** the eval tests run under babashka and Jolt's views
  (`bb test-changed` plus `jolt test` where the tree has it); `sci/init`
  once per agent and the `swap!`-based `seed!` are the two host-sensitive
  points.

### Limits, stated in the docs

- Same authority as `bash`. Saved definitions run in the agent's next start;
  that is the feature and the risk. Files are plain text in the project for
  review, and no sandbox is claimed.
- A host-blocked call still hits the existing interrupt-grace limitation
  (no process isolation — `modules/app/src/kmet/design.md` § Open at HEAD 5).
- Nothing is kept from `run_code`; nothing from `eval` is visible to
  `run_code` or to a subagent; two agents in one process have two stores and
  two contexts.
- The kept index is unbounded (the model can define as much as it wants);
  the capture and result budgets are the shared script layer's.

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

**E3 — agent lifetime.** Definitions survive `new-session!` /
`resume-session!` (the handle is the agent; a session is a log), and are not
shared with subagents: a subagent is its own child ctx, and inheriting the
parent's environment would make two agents' definitions interfere. A shared
environment would be a separate decision, not a default.

**E4 — nothing is written unless the call says `:scope`.** A model-facing
default of "write into the user's repository" is not a default kmet takes;
`:project` and `:global` are explicit, and the result always names the file.

**E5 — one file per definition, verbatim source.** The file is the address
(`<ns>/<name>.clj`), so lookup, deletion and review are path operations.
`pr-str` of the parsed form would normalise away reader sugar; the
rewrite-clj slice keeps the bytes the model wrote.

**E6 — a saved name keeps its scope; deletion is `:drop`.** A redefinition
without `:scope` must not create the state where memory and disk disagree
until the next start. `:scope :agent` opts a *new* definition out; it never
deletes, because a silent delete is the one outcome a model cannot review.

**E7 — top-level defining forms only.** A def inside `do`/`when`/`comment` is
not kept, unlike nodecode: a computed definition has no stable file name, and
the alternative (an aggregate file per call) makes per-definition deletion
and review impossible.

**E8 — `defmethod` keyed by `<multi> <dispatch>`.** Otherwise every method of
one multimethod lands on one file and the last one wins at load. The
sanitized dispatch in the file name is the address; `:kept` names the full
key.

**E9 — eval sources are values; neither tool nests the other.** The
contribution crosses the isolation boundary the way every other capability
does (functions and data), so no source can reach a host service it was not
given. `script-tool-names` excludes both tools from any script's surface, so
`run_code` cannot start an `eval` and an `eval` cannot start another.

**E10 — `user` is reserved for `:core`; extensions namespace their verbs.**
Later-registration-wins is app-wide, so an extension that took `edit` would
silently replace the built-in in every agent. The rule is enforced by the
service (a source contributing to `user`, `clojure.core`, `tools`,
`sandbox`, `eval` or `fs` is refused and reported), not only documented.

**E11 — host operations cross as values, not as requirable namespaces.**
`sources-fn` receives `:verbs` — the same fns the script calls — so an
extension's own logic can compose `(edit …)` without `kmet.app.*` becoming
requirable and without moving the file ops or the mutation lock into `libs`.
A libs extraction happens only if a consumer needs the op *outside* a source
(a non-extension caller), which none does today.

**E12 — discovery is in-code, plus one read-only command.** `(eval/sources)`,
`(eval/kept)`, `(eval/help)` and `/eval-sources`; no system-prompt section.
A dynamic per-agent section sits in the cached prefix and varies per agent,
so it would invalidate the whole prefix every time one changed. A source that
needs unprompted presence registers a skill, the existing seam.

**E13 — `:core` ships file verbs only.** `edit` and `write-file` are the
two operations a bare profile (no extensions mounted) needs to loop over
files; everything tool-specific comes from the extension that owns the tool —
Clojure editing from the clojure extension, search verbs from a future one.
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
the wrong expectation); `clj/*` for the clojure extension's verbs (not the
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
the first call), so removing `:tool/eval` leaves a service with no reader, the
same accepted shape as `:sandbox` without `:tool/run_code`.

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
