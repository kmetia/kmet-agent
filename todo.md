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
  branch do not rewind definitions; a new session or a restarted process
  loads the files, and an already-built environment never refreshes.
- **Forget = delete.** Remove the file and unbind the live name; a flat
  `:drop` or `(eval/forget 'name)` is enough.
- **`run_code` is the throwaway tool; `eval` is the library.**
- **Isolation is a home**, not a scope: `KMET_CODING_AGENT_DIR` (or a
  profile) gives a separate library.

This supersedes the session/project scope rules of step 4 and the
project/global material it replaced. It landed as **step 4R** (see § Steps);
the test plan, limits and decisions below state the current model.

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
| Persistent eval and eval sources | `run_code` throws its context away per call, so a helper the model writes is re-pasted in full on every turn; extensions can contribute tools but not a definition the model can call from code | steps 1–4R done; **step 5 next** |

## Persistent eval and eval sources

Status: steps 1–4R done (the shared script layer, the file ops, the
memory-only service and the one-home library); step 5 next. The tool is opt-in
by default under S4.5; step 4R's tests exercise it directly rather than through
the default declared set. E17 moves the Clojure editing engine and the edit
verbs into core; the rest of the clojure extension is a post-track task at the
end of this section.
Decisions closed (E1–E18); independent.
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
| script sandbox | `kmet.app.tools.run-code`, one SCI ctx per call, ~1,000 lines of machinery | moves to `kmet.app.tools.script`; both tools use it |
| file ops | inline in `kmet.app.tools.edit` / `.write` `execute` | extracted as ops; tool and verb share them |
| per-agent services | `:shell` (atom), `:sandbox` (data), `:permissions` (memory) — the agents factory provides each | `:eval` joins them, with one environment per session over the home library |
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
| eval vocabulary | the `:eval` service (it needs library state) | `(eval/library)`, `(eval/sources)`, `(eval/help)`, `(eval/forget …)`, `(eval/patch …)`, `(eval/edit-form …)` | no |
| verbs | the `:core` eval source shipped by the eval tool (including the Clojure editing verbs after E17), and every mounted extension's own source | `edit`, `write-file`, `clj/edit-form`, `clj/replace-sexp`, `clj/paren-repair`; extension-specific verbs | yes (`{:core false}`) |

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

**The tool.** `:tool/eval` joins `:bundle/minimal` beside `:tool/run_code`,
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

`run_code` becomes "build a fresh context, run, discard"; `eval` becomes
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
(`(eval/help)` summarises all three tiers; `(eval/library)` lists the
definitions this environment loaded), the `/eval <verb>` builtin command for
the human — `list [pattern]`, `search <text>`, `drop <name>…`,
`sources`, `help`, plus verbs an eval source contributes through
`:source-commands`. Built-in verb names are reserved;
a source contributing one is refused. No system-prompt section: it would sit
in the cached prefix and vary per session, so it would invalidate the whole
prefix every time one changed; a source that needs the model to know
something unprompted registers a skill, the existing seam.

### Steps

Nine steps plus the cleanup step 4R. Steps 1–2 are behaviour-preserving and
every later step is additive. Estimated ~2,000 new lines, ~1,000 moved (step 1)
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
   added to `run_code`'s pointing at `eval`).
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
8. **The Clojure editing engine and verbs.** Extract
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
9. **Docs and catalog.** `docs/architecture.md` § Service catalog (`:eval`)
   and § Tools and the script sandbox (the eval tool, the extraction, the
   library and the scoping of "a `def` in one call is invisible to the next"
   to `run_code`); `bb generate-catalog`; `docs/pi-alignment.md` § codemode
   (the persistent `store`/`load` half and the extension analogue are no
   longer missing); `modules/app/src/kmet/design.md` § Open at HEAD 5 (one
   line: a persistent context makes Jolt's `eval-in` more attractive later;
   the decision is unchanged); the tier table and the clojure extension's
   remaining surface (E17 and the post-track task).

### Test plan

- **Step 1:** `test_run_code.clj` passes unmodified apart from the three
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
- Nothing is kept from `run_code`; nothing from `eval` is visible to
  `run_code`. Two homes never share; two agents or sessions in one home share
  the library files but each has its own live environment, so a definition is
  visible to another only when that environment is built again.
- The library is unbounded (the model can define as much as it wants); a
  written file is the record, not a compaction fold. The capture and result
  budgets are the shared script layer's.

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
leaves the live definition until a restart.

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
`run_code` cannot start an `eval` and an `eval` cannot start another.

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
like tool sources); auto-save on any definition is now the model rather than a
declined option; `:keep`, a project `<cwd>/.kmet/eval` scope, session
definition entries and compaction snapshots (superseded by E18).

**E15 — names closed.** `/eval` is the human command: `list`, `search`,
`drop`, `sources`, `help`, plus keyword-keyed verbs an eval source contributes
through `:source-commands` (a `:run` returns nil or a string); the built-in
names are reserved. `/eval sources` is the source report. `(eval/library)` is
the listing (the old `(eval/kept)` name is retired with the scope model);
`(eval/forget 'name)` is
the in-environment delete; `clj/*` for the Clojure verbs (core-provided after
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
the first call), so removing `:tool/eval` leaves the `/eval` library command
and its service in place — the command is a reader, unlike `:sandbox` without
`:tool/run_code`.

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
scope for code.

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
