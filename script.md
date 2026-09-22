# script.md — the script tool (design + measurement)

Question this file answers: **should kmet add a "code execution" tool (à la
maki's `code_execution`) so the model can scan/filter many files and put only
the distilled result in context — and if so, how?**

Scope: this file is about a **new** tool only. Existing tools and their
instructions are pi-aligned and are not modified by this plan (kmet's builtin
set is read/write/edit/bash; the grep/find/ls search tools ship as separate
opt-in extensions). The one thing already in place is measurement — per-tool
result-token attribution — so the build/no-build decision can be made on data.

Status: **T0 measured and analysed** (results below); **T1 design settled**
(tool bridge, capability table and implementation plan below: resolution
aligned with normal dispatch, inner calls always async promises, no tool-call
hooks). The numbers rewrote the premise rather than killed it: maki's
read-share did not transfer — bash dominates — but ~75% of all result tokens
are still the find/read workload; it leaks through bash (`cat`/`head`/`rg`)
instead of the `read` tool, and T1 is aimed there. T1 implementation not
started. T2 folds the mcp-adapter's mcpScript onto the same engine; T3 is the
self-exec/RPC tier.

## The idea and the economics (maki.sh)

The conversation is re-sent on every turn, so a tool result does not cost its
tokens once — it costs them again on every later turn until compaction. maki's
`code_execution` lets the model write a script that gathers/reads/greps many
files inside a sandbox and prints only the lines that matter; the rest never
enters the context window. Their reported split: read results were ~65% of all
billed tokens, bash ~12%. The script tool was built to compete with *reads*.

Their second observation: the model can already batch via `bash` + python/awk/
jq, but it doesn't *reliably choose to*. That is an argument for a dedicated
tool with a description that "nags" — but only if the data says kmet's context
is actually being eaten that way. kmet's distribution may differ, so the
question is measured before anything is built. It does differ — see
[Measurement](#measurement-t0--results).

## What kmet already has

| maki idea | kmet status |
|---|---|
| `index` (tree-sitter skeleton before reads) | ✅ tree-sitter extension: `list_symbols`, `get_symbol_body`, `find_definition`, `find_callers`, `find_callees` + `lsp` adapter |
| deferred MCP tool definitions (`tool_search`) | ✅ mcp-adapter: one `mcp` proxy tool, lazy servers, output guard |
| truncation everywhere | ✅ read (2000 lines/50KB), bash (line/byte caps + full-output file) |
| partial output on interrupt | ✅ bash returns streamed output + "Command aborted" |
| compaction | ✅ `kmet.app.compaction` (LLM summarization) |
| visibility (tokens/cost) | ✅ footer: ↑in ↓out R/W cache, $cost, context % |
| `mcpScript` (scripted MCP calls) | ✅ mcp-adapter: its own `bb`-subprocess JSON-lines RPC engine + output guard — T2 retires it into the shared script engine |
| `code_execution` (script + distilled output) | ❌ — the candidate change |
| per-tool token attribution | ✅ measurement: every tool-result entry carries `:result-tokens`, `/session` shows the per-tool breakdown |

## Measurement (T0) — results

Corpus: 370 session files under `~/.kmet/sessions`, **59,286 tool results**,
**~28.1M estimated result tokens** (chars/4, the compaction convention). The
last 16 sessions (Sep 18–20) carry the stamped `:result-tokens`; older sessions
are estimated from their stored result content. The T0-era column is the
current-behavior slice; all-time is the larger sample.

### Headline

| tool | calls | tokens | share | avg/call |
|---|---:|---:|---:|---:|
| **bash** | 44,786 | 15.42M | **54.8%** | 344 |
| **read** | 7,189 | 11.68M | **41.6%** | 1,625 |
| web search + fetch | 192 | 680k | 2.4% | 3.5k |
| everything else (edit, structural, lsp, mcp, grep ext) | ~7.1k | ~333k | ~1.2% | — |

- **T0 era (16 sessions):** bash **68.0%** (1.09M / 2,694 calls), read **29.4%**
  (472k / 301 calls).
- **Conversation-weighted** (tokens × later assistant turns, i.e. re-send cost):
  bash **71.0%**, read 26.1%.

Maki's 65%-reads did not transfer. Bash dominates by volume and by re-send cost.

### Inside bash, it is the same workload

Bash results by command category (share of bash tokens):

| category | all-time | T0 era |
|---|---:|---:|
| file-view (`cat`/`head`/`tail`/`sed`/`awk`) | 36.6% (5.64M) | 40.6% |
| search (`rg`/`grep`) | 29.6% (4.56M) | 25.6% |
| git | 11.1% | 13.8% |
| list (`ls`/`find`/`wc`) | 4.6% | 4.2% |
| test/build | 3.2% | 1.9% |
| bb-eval | 3.1% | 2.1% |
| other (incl. python/node, shell utils) | ~11% | ~12% |

Combine "find/read content" across tools:
**read 41.6% + bash file-view 20.1% + bash search 16.2% ≈ 78%** of all result
tokens (T0 era: **~75%**).

### Distribution

- Heavy tail: top 10% of bash calls = **50%** of bash tokens; top 10% of read
  calls = **45%** of read tokens.
- read: 6.8% of calls ≥5k tokens = **36%** of read tokens; **126 reads hit the
  50KB cap** (≥11.5k tokens) = 1.59M tokens (13.6% of read).
- bash: 31 results (0.1%) at the 50KB cap = 393k tokens; the biggest singles
  are broad `rg -n` dumps truncated at the cap.
- Repeated whole-file reads of the same few big files: `modes/interactive.clj`
  579 calls / 750k tokens; `app/extensions.clj` 223 / 423k; `app/loop.clj`
  268 / 375k; `tui/core.clj` 184 / 282k.

### What the data decides

- "reads dominate → build T1": **not met** (42% all-time, 29% T0).
- "reads are a small share → stop": **not met** either — the workload is there.
- "some other tool dominates → look there": **met** — and looking inside bash
  shows file-view + search ≈ two-thirds of it.

So the script tool's competition is **bash-as-file-reader and broad search**,
not primarily the `read` tool. The description should nag at exactly that:
scan/filter many files, print only the distilled result. The per-call averages
also say the waste is a heavy tail, not a steady drip: cheap interventions
(read offset/limit discipline, more precise searches) capture part of it
before any new engine; T1 must capture the rest.

### Caveats

- chars/4 estimates context volume, not billed tokens; both tools truncate
  (bash 50KB, read 2000 lines/50KB), so these are post-cap input numbers.
- The corpus is mostly kmet's own development sessions — biased toward
  browsing this large Clojure tree.
- T0 era is 16 sessions / ~3 days; all-time back-fills estimation.
- The weighted metric ignores compaction (which trims re-send cost).

### Reproducing

1. **`/session`** — the **Tool Results** section: per-tool call count and
   estimated result tokens, highest first, with a total. The **Tokens**
   section above it is the billed truth.
2. **`debug.log`** — run `kmet --debug`; each run appends a per-tool token
   report (highest first, with TOTAL).
3. **Session files** — each tool entry has `:result-tokens` and `:tool-name`.
   Here the analysis also estimated unstamped entries from content. `.ednl` is
   the recursive glob — sessions live in per-cwd subdirectories:

   ```bash
   bb -e '
   (require (quote [babashka.fs :as fs]) (quote [clojure.edn :as edn]) (quote [clojure.string :as str]))
   (let [totals (->> (fs/glob (str (fs/home) "/.kmet/sessions") "**.ednl")
                     (mapcat (fn [f] (keep #(try (edn/read-string %)
                                                 (catch Exception _ nil))
                                           (str/split-lines (slurp (str f))))))
                     (filter #(and (= :tool (:role %)) (integer? (:result-tokens %))))
                     (reduce (fn [acc e]
                               (update acc (:tool-name e) (fnil + 0) (:result-tokens e)))
                             {})
                     (sort-by val >))]
     (doseq [[tool tokens] totals] (println (format "%-10s %d" tool tokens))))'
   ```

## Design investigation (what was verified, not assumed)

The full discussion explored four ways to run model-written code. Recorded here
so T1–T3 don't re-litigate it:

1. **In-process eval on a thread + SCI.** bb and jolt both have `Thread`;
   thread termination does **not** exist on either host: `Thread.stop` throws
   `UnsupportedOperationException` on bb (JDK 20+) and isn't implemented on
   jolt ("No matching field found"); `suspend`/`resume`/`destroy` are gone;
   `Thread.interrupt` only wakes blocking calls, a tight loop survives;
   `future-cancel` returns true but the computation keeps running (all
   measured). Jolt fibers track `dead` only when the fiber's own body raises —
   no external kill. **However**: SCI's `:interrupt-fn` (called on every
   interpreted fn/loop entry) reliably aborts interpreted code on both hosts
   (verified: a 3M-iteration spin killed on bb; 666k on jolt through kmet's
   pinned sci). Caveats from SCI's own docs: host-native CPU calls escape it
   (`.pow` example), and "for hard guarantees it is best to run untrusted code
   in a separate process that can be killed."
2. **Self-exec subprocess** (`kmet --script`): process isolation and hard kill
   with no external interpreter (mcpScript currently needs `bb` on PATH). Needs
   a lean script mode + self-path resolution (packaged binaries easy; dev mode
   falls back to `bb -m`/`jolt -m`; Termux launcher caveat is already known in
   `kmet.tasks.build`).
3. **Warm daemon** (LSP-style): spawn lazily, one `kmet.loader` context per
   request (`:base` fork is designed for exactly this — see
   `kmet.loader.sci-loader` docstring), kill after idle. Per-call cost ≈
   fork + eval; keeps process-level caches (MCP, tree-sitter) warm. Costs a
   daemon manager + a framed protocol.
4. **RPC mode** (pi convergence): pi's `--mode rpc` is JSONL over stdio with
   commands (`prompt`, `bash`, `abort`, session ops) and streaming events.
   kmet already has the event vocabulary (`kmet.app.event_bus`) and a headless
   mode (`kmet.modes.print`); what's missing is command dispatch, serialization
   and a client. Only worth building when a **second consumer** exists
   (subagent tool, editor embedding) — not for token savings.

Loader facts: kmet.loader uses **SCI on babashka** and the **native
`jolt.loader` on jolt** (`.jolt` file; backend selected in
`kmet.app.extensions`). kmet pins SCI in `jolt/deps.edn`, so SCI is available
on jolt if a script evaluator wants `:interrupt-fn` uniformly.

## Tiers

- **T0 — measure (landed, tool-agnostic).** Per-tool result tokens: stamped on
  each tool-result entry, derived by `tool-usage`, shown in `/session` and
  logged with `--debug`. No existing tool behavior or instruction changes.
  Results above.
- **T1 — thin script tool.** One in-process SCI context per call, tools
  injected as fns, eval on a daemon thread, `:interrupt-fn` + timeout + output
  guard. No process, no daemon, no protocol. Inner calls resolve through the
  normal registry path, are always async, and fire no tool-call hooks (below).
  Known limitation: host-native runaway code can't be aborted (abandoned
  worker; the agent survives). The tool bridge and implementation plan are
  below.
- **T2 — mcp-adapter rides the shared engine.** The adapter's `mcpScript`
  tool and runtime (`extensions/mcp-adapter/src/extensions/mcp_adapter/script.clj`:
  its own `bb`-subprocess JSON-lines RPC runtime, no host access) retire into
  T1's engine — the adapter contributes its MCP tools to the same sandbox and
  the same output contract instead of shipping its own runtime. The bridge
  must therefore accept extension-contributed tool sources, not only the kmet
  registry; mcpScript's `emit`/console output maps onto T1's result contract
  and `:script-mode` gating moves to the shared registration. Isolation
  consequence, recorded: mcpScript code runs in a fresh subprocess with no
  host access, while the shared engine is T1's in-process SCI — scripted MCP
  code gains the host surface the tool already has (like `bash`); the
  subprocess was the adapter's separate-runtime device, not a kmet sandbox
  guarantee. Surface change to carry over: mcpScript's `tools/call` derefs
  internally (blocking); the shared bridge hands the script the promise, so
  the MCP skill's call snippets gain an explicit deref.
- **T3 — `kmet --script` (self-exec) and/or `--mode rpc`.** Only with a
  measured need (boot cost/call frequency) or a second consumer. The tool
  contract (description/skill/API) should survive T1→T3 untouched.

## T1 design — the tool bridge

Decisions from the design pass, so implementation doesn't re-derive them.

### Callable set

- **All active tools**, i.e. `registry ∩ (:enabled-tools agent)` (nil = whole
  registry), minus an exclusion list (`script` itself, and later any nested
  sandbox). Active is exactly what the model sees in its tool schema — this
  respects `set-active-tools!`, picks up extension tools automatically, and
  keeps discovery equal to callability. The set is fixed when the base is
  built (see Lifecycle), so a script's surface is stable for its whole run.
- Resolution is the normal path (landed as T1 prep): `get-tool` now reads
  through `get-all-tools` (custom shadows built-in, pi's registry layering),
  so the name the script sees listed is the record `execute-tool` dispatches —
  no separate bridge-side lookup.
- The gate lives in the **bridge**, not in `execute-tool` (`execute-tool` is
  also called by the user-bash path, extensions and tests; global enforcement
  would change their behavior). Unknown/inactive names return
  `{:is-error true}` with the active list — not an SCI unresolved-symbol.
- `list`/`describe` report the **active set only**.

### API

```
(t/call "name" args)                  ; every active tool by name — returns a promise
(t/read args) (t/write args)          ; builtins, sugar over t/call (same promise)
(t/edit args) (t/bash args)
(t/list) (t/describe "name")          ; discovery; synchronous bridge-local reads
```

- **Call fns return promises.** The call returns immediately with a promise
  that settles with the verbatim result map; the script derefs when it needs
  the value (`@(t/call "bash" {...})`). The mechanism is the mcpScript child
  runtime's existing one (per-id `pending` promises, one reader thread;
  concurrent calls from `future`s are safe) ported in-process — except the
  bridge hands the script the promise instead of derefing internally, which
  makes fan-out idiomatic: fire N calls, scan files locally meanwhile, deref.
  One script call, one context entry, N inner calls that never enter context.
- `list`/`describe` are the exception: bridge-local reads of the active-set
  registry (no dispatch), so they stay synchronous.
- Built-in fns are generated for the four builtins unconditionally (fixed,
  doc-able surface); if one is disabled, the gate produces the clear "not
  active" error.
- Extension tools stay generic-only: they register/unregister at runtime, are
  re-registered by `/reload`, MCP catalogs appear after connect, and names may
  not be symbol-safe — a var surface would go stale or be unrepresentable.
- `describe` returns `{:name :label :description :parameters}` — never
  `:execute` (that would hand the script a bare execution path around the
  bridge).

### Dispatch and result contract

- One bridge over `kmet.app.tools.core/execute-tool` — the loop's own seam.
  It already provides arg normalization, `:prepare-arguments`,
  `:contextual?`/`:streams?` dispatch, and exception → `{:is-error true}`.
- **Never through `loop/execute-tool-calls-*`**: that path emits
  `:tool-execution-*` events, runs hooks, and appends `:role :tool` entries to
  context and session — exactly the cost the script tool exists to avoid.
  Inner results stay out of context; only the script's output enters.
- Result map settled **verbatim**: `{:content :is-error :details :truncation
  :images}`. Scripts branch on `:is-error` instead of catching; do not unwrap
  `:content` (it can be a block vector) or hide `:details` (exit codes, diff,
  full-output path).
- **Always async.** The bridge never runs a tool's `:execute` on the
  interpreter thread: each call dispatches to a worker and returns a promise
  the script derefs when it wants the value. In-flight calls don't block
  dispatch — scripts fire calls, do local work (fs scans) meanwhile, then
  deref — and the run `:signal` reaches the inner call, so Escape cancels it.
- **Settlement.** Every dispatched promise settles when its tool returns —
  success or `{:is-error true}`; a tool that observes the run `:signal` (bash
  does) settles on cancel. The one non-settling case is a tool implementation
  that neither returns nor honors `:signal`: a deref of that promise blocks
  host-side, and the run's deadline abandons the interpreter thread (T1's
  accepted host-native-runaway limitation).
- **No tool-call hooks.** Inner calls never fire `on-tool-call` /
  `on-tool-result` — no synthetic tool-call ids, no hook routing. An
  extension that must gate scripted tool use gates the outer `script` call
  itself.
- Inner-call opts: `:signal` (the run cancel — Escape must kill inner bash
  children), `:ctx` (`build-extension-context`), `:on-update` collected into a
  call trace exposed at `:details {:calls [...]}` (mcpScript precedent), **no**
  per-call UI events. The trace is an atom — concurrent calls (the normal
  fan-out case now that every call is a promise) append to it safely.

### Plumbing

- The tool surface is a base-cache input, not a dynamic var (see Lifecycle).
  The values that stay per run keep the loop's existing conveyance:
  `bash-tool/*cancel-signal*`, `*session-env-fn*`, `tools-util/*cwd*` are
  bound at run level and reach the tool future; the bridge closes over the
  signal and the run's cwd when the fork is built.

### Lifecycle

- **Per invocation**: one fresh `sci/fork` of the cached base, per-call state
  merged in (the bridge closure: deadline, signal, trace, the call's surface),
  discarded when the call returns — a context is never reused between scripts.
  The fork is the isolation boundary; verified: a `def` in one fork is
  invisible to sibling forks and the base, and redefining (or
  `alter-var-root`ing) a base-injected var stays fork-local.
- **Base cache, keyed by the tool surface**: `[registry-generation
  enabled-tool-set]`. The base is rebuilt when the registry generation changes
  (`register-tool!`/`unregister-tool!` — extension load/unload/`/reload`;
  T2's contributed MCP catalogs join the same counter) or when
  `set-active-tools!` changes the enabled set. A small keyed cache (a few
  entries) keeps sibling sessions from thrashing; the normal case is one base.
- **Why the surface lives in the base**: the gate and `t/list`/`t/describe`
  then read a surface fixed for the call — the same set the model saw for the
  turn — instead of a dynamic var threaded through the tool future. `t/call`
  dispatch still resolves through `execute-tool`, so the record executed is
  the registry's (the key guarantees it was the listed one when the context
  was built).

### Capabilities

Settled surface. Verified against both SCI hosts (bb's bundled SCI and the
jolt pin): value-shared `babashka.*` namespaces work in a context with **no
`:classes`/`:imports` at all** — the extension context's `:classes {:allow
:all}` is exactly what the script sandbox must not copy.

| capability | surface | notes |
|---|---|---|
| `clojure.core` | SCI's builtin core (atoms, regex, `try`/`catch`, `with-out-str`, `promise`/`deliver`/`deref`) + host patches `slurp` `spit` `file-seq` | SCI's core lacks slurp/spit/file-seq, `future`, `pmap`, `sleep`, all `System/*` |
| string/collections | `clojure.string` `clojure.set` `clojure.edn` `clojure.walk` | SCI builtins — `require` resolves out of the box |
| fs | `babashka.fs` + `slurp`/`spit`/`file-seq` | the tools' own fs layer; bulk scanning is the tool's reason to exist |
| process/shell | `babashka.process`: `sh` `shell` `process` | `$` is a macro — value-sharing can't carry it; `sh`/`shell` cover the need. This is what makes scan-and-filter work without context spill (`rg` output stays in the script) |
| async | tool calls are promises (the API itself); host `future`/`pmap` injected as plain fns (`spawn` thunk, `pmap`) | SCI has **no** `future`/`pmap` and kmet's bb SCI ships no `sci.async`; `clojure.core.async` is unusable this way (`go`/`thread` are macros) |
| json | `kmet.libs.json` `parse-string`/`generate-string` | the same codec the tools use |
| cwd/env | the runtime cwd (`tool-util/*cwd*`) and `(env "HOME")` | SCI has no `System/getenv`; scripts must resolve relative paths like the tools do |
| time | `now`/`sleep` host fns | any polling or scan-timeout loop needs them |
| reader features | the host's own feature (`:bb` or `:jolt`) | kmet's convention — `:clj` matches both hosts and is not exposed |
| output | `*out*`/`*err*` captured + the script's return value | T2 carries mcpScript's `emit`/console on top |

Excluded deliberately: Java interop (empty `:classes`/`:imports` — host fns
hide their own), `kmet.app.*`/`kmet.tui.*`/`kmet.extension` (the extension
contract is not the script contract), `kmet.loader`, arbitrary `require` of
Maven deps (dependency resolution is an extension-manifest feature), and
network (`kmet.libs.http` stays out until measured; the single-boundary rule
would still apply if it lands).

Mechanics: cached stdlib base + per-call fork (the extensions'
`shared-context` pattern; `kmet.loader.sci-loader`'s `:base` handles the
merge-opts pitfalls). `babashka.fs`/`.process` must be host-`require`d before
their public fns are value-shared (bb preloads them, jolt does not — both
verified). The bridge namespace closes over per-call state (deadline, signal,
trace; the surface comes from the base — Lifecycle), which is why it is
merged into the fork rather than cached.

The description must teach the boundary: **bulk scanning via
`babashka.fs`/`slurp`/`sh`** (no truncation, no per-call overhead); tools are
for semantic queries (lsp/structural) and mutations.

### Settled decisions

- **Tool resolution = normal tools.** One resolution path: `get-tool` reads
  through `get-all-tools` (custom shadows built-in), so listing and dispatch
  can never disagree. Landed as T1 prep.
- **Inner calls always async, never inline.** `t/call` (and its sugars)
  returns a promise the script derefs; dispatch never blocks the interpreter,
  and the run `:signal` reaches the inner call so Escape cancels it. Scripts
  fan out freely — one `script` call can run N inner calls in parallel.
- **No hooks.** `on-tool-call` / `on-tool-result` do not see script-inner
  calls (they stay a turn-level mechanic). Consequence: an extension's
  per-tool gate — e.g. a bash guard — does not apply to scripted calls; gate
  the `script` tool itself to block the whole surface. Mutation tools ride the
  same bridge; the callable set is every active tool, not a read-only subset.

## T1 implementation plan

Placement: **builtin** (`src/`), not an opt-in extension — the measured
workload is ~75% of all result tokens; an opt-in tool would not move it. The
builtin set grows by one (read/write/edit/bash + `script`); existing tool
descriptions are untouched. No settings gate in v1 (mcpScript's `:script-mode`
precedent is available if the prompt impact measures badly).

1. **Tool** — `src/kmet/app/tools/script.clj`: a `script` record
   (name/label/description, params `code` + `timeoutMs`, `:streams? true`),
   registered in `src/kmet/app/tools/registry.clj`'s built-in map. The
   description nags at the measured workload (scan/filter many files, distilled
   output only; promises + deref; fs/`sh` for bulk, tools for semantic queries
   and mutations), with the usual `:prompt-snippet`/`:prompt-guidelines` shape.
   Default timeout 30s (mcpScript parity; `timeoutMs` overrides).
2. **Engine** — same namespace (split only if it grows): base cache keyed by
   `[registry-generation enabled-set]` (a `defonce` atom, the extensions'
   `shared-context` pattern; the generation bumps on register/unregister) +
   per-call fork through `kmet.loader.sci-loader`'s `:base` (it handles the
   merge-opts pitfalls) with the per-call bridge merged in.
3. **Capabilities** — the table above as value maps: host-`require`
   `babashka.fs`/`babashka.process` before sharing their public fns (bb
   preloads, jolt does not); `slurp`/`spit`/`file-seq` patches on
   `clojure.core`; `clojure.string`/`set`/`edn`/`walk`; json; cwd/env/now/sleep;
   `:features #{:bb}` or `#{:jolt}`; **no** `:classes`/`:imports`.
4. **Bridge** — `t/call` + the builtin sugars + `t/list`/`t/describe`; the
   active-set gate (the fork's injected surface, `script` excluded;
   unknown/inactive → `{:is-error true}` with the active list); dispatch on a
   host worker returning a promise; `:signal`/`:ctx` passed to `execute-tool`;
   trace atom at `:details {:calls [...]}`; never `execute-tool-calls-*`,
   never the hooks.
5. **Deadlines** — eval on a daemon thread; `:interrupt-fn` checks the deadline
   and the run signal; on timeout the tool returns partial output +
   `:is-error` and abandons the thread (the documented limitation); inner
   promises settle when their own tools settle.
6. **Output** — capture `*out*`/`*err*` + the return value; cap with the tools'
   50KB byte convention and the truncation metadata shape
   (`kmet.app.bash-executor`'s truncation + the mcp-adapter guard's semantics
   are the precedents — no extension dependency); stream partial output through
   `on-update` like bash.
7. **Tests** — `test/kmet/app/test_script.clj` (registered in
   `kmet.tasks.runner/all-namespaces`): gate, promise fan-out/deref, capability
   smoke (fs/slurp/sh/json/spawn), no-hooks proof, spin-loop abort, output +
   trace contract, excluded surface (`System/*`, `kmet.app.*`, arbitrary
   `require`).
8. **Verify** — after adoption, re-run the T0 measurement (`/session` Tool
   Results against the 54.8%/41.6% split; the `--debug` per-tool report): bash
   file-view/search share is the number the tool exists to move.
9. **T2 seam** — keep the tool source parameterized (registry today,
   extension-contributed catalogs later) so the mcp-adapter convergence is an
   addition, not a refactor.

## References

- `src/kmet/app/tools/core.clj`, `src/kmet/app/tools/registry.clj` —
  `execute-tool` (the bridge seam), `built-in-tools`, `get-all-tools`,
  `get-tool`.
- `src/kmet/app/loop.clj` — run-level bindings + `active-tools` /
  `set-active-tools!`; `execute-tool-calls-*` (what the bridge must not route
  through); the `--debug` per-tool report at agent end.
- `src/kmet/app/extensions.cljc` — `shared-context` / `shared-var-map` /
  `build-context-namespaces` (SCI injection pattern), `build-extension-context`.
- `src/kmet/extension.clj` — tool registration (`register-tool!`,
  `create-bash-tool`, `on-tool-call` / `on-tool-result`).
- `src/kmet/app/session.clj` — `:result-tokens`, `tool-usage`,
  `tool-usage-report`.
- `src/kmet/modes/interactive.clj` — the `/session` **Tool Results** section.
- `src/kmet/app/event_bus.clj` — event vocabulary shared with the TUI (the
  serialization seam a future RPC mode would use).
- `kmet.loader.sci-loader` — `:base` fork (per-call contexts for T3's daemon),
  `:interrupt-fn` support.
- `extensions/mcp-adapter/src/extensions/mcp_adapter/script.clj` and
  `src/skills/mcp/SKILL.md` — mcpScript's subprocess runtime and tool surface
  (the T2 convergence target).
- pi: `~/src/cvstree/pi/packages/coding-agent/docs/rpc.md` (stdio RPC mode) and
  `packages/protocol` (transport-neutral CBOR, remote sessions).
