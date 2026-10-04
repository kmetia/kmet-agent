# run_code.md — the `run_code` tool (design + measurement)

Question this file answers: **should kmet add a "code execution" tool (à la
maki's `code_execution`) so the model can scan/filter many files and put only
the distilled result in context — and if so, how?**

Scope: this file is about a **new** tool only. Existing tools and their
instructions are pi-aligned and are not modified by this plan (kmet's builtin
set is read/write/edit/bash; the grep/find/ls search tools ship as separate
opt-in extensions). The one thing already in place is measurement — per-tool
result-token attribution — so the build/no-build decision can be made on data.

Status: **T1, T2 and T4 implemented** in this tree —
`src/kmet/app/tools/run_code.cljc`, a builtin (read/write/edit/bash + `run_code`),
`kmet.app.test-run-code` (52 tests: 39 fast + 13 `^:slow`, smoke-verified on
babashka and jolt); the mcp-adapter's `mcpScript` tool and `bb`-subprocess
runtime retired into the same engine (its catalog joins the sandbox as a
contributed tool source — T2 below). T4 shares the invocation pipeline with
the loop (`kmet.app.tools.invoke`), so scripted calls fire the run's tool
hooks and a script abort cancels inner calls. The last plan item, the
post-adoption T0 re-measurement, is recorded below as well (✅): run_code is
still used rarely, so the bash find/read workload has not moved yet.

A follow-up efficiency pass is also landed: `sandbox/emit` provides a
single compact result path, `tools/call-many`/`tools/await-all` shorten
ordered fan-out, the result byte budget defaults to 16 KiB (tunable per
call, capped at 50 KiB), the capture budget is fixed at 1 MiB, and the
provider-facing description/guidelines are substantially shorter.

**Native Jolt evaluator now unblocked.** `jolt.loader/eval-in` landed
upstream after v0.8.14, closing the gap the loader investigation below
flagged. A probe verified the whole T1 engine shape natively — per-call
context, require gate, bridge, capture, native abort; the mechanics are
ready, but adopting it drops the capability restriction, so the contract
decision is recorded rather than taken ("Native script evaluator" below).
The loader-eviction fix the per-call case needs is on branch
`fix/loader-evict-unloaded` in the jolt checkout.

**T0 measured and analysed** (results below); the numbers rewrote the premise
rather than killed it: maki's read-share did not transfer — bash dominates —
but ~75% of all result tokens are still the find/read workload; it leaks
through bash (`cat`/`head`/`rg`) instead of the `read` tool, and T1 is aimed
there. **T1 design settled** (tool bridge, capability table and implementation
plan below: resolution aligned with normal dispatch, inner calls always async
promises, no tool-call hooks). T3 is the self-exec/RPC tier.

## The idea and the economics (maki.sh)

The conversation is re-sent on every turn, so a tool result does not cost its
tokens once — it costs them again on every later turn until compaction. maki's
`code_execution` lets the model write a script that gathers/reads/greps many
files inside a sandbox and prints only the lines that matter; the rest never
enters the context window. Their reported split: read results were ~65% of all
billed tokens, bash ~12%. The run_code tool was built to compete with *reads*.

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
| truncation everywhere | ✅ read (2000 lines/50KB), bash (line/byte caps + full-output file), script (16 KiB/2000-line default result + capture budget) |
| partial output on interrupt | ✅ bash returns streamed output + "Command aborted" |
| compaction | ✅ `kmet.app.compaction` (LLM summarization) |
| visibility (tokens/cost) | ✅ footer: ↑in ↓out R/W cache, $cost, context % |
| `mcpScript` (scripted MCP calls) | ✅ mcp-adapter: retired into the shared `run_code` tool — the catalog is a contributed sandbox tool source (T2) |
| `code_execution` (script + distilled output) | ✅ builtin `run_code` (T1, landed — plan and re-measurement below) |
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

So the run_code tool's competition is **bash-as-file-reader and broad search**,
not primarily the `read` tool. The description leads with what only the tool
can do — run a multi-step task as one call, fan calls out in parallel, keep N
tool results out of context — and nags at the measured workload beneath it:
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

### Post-adoption re-measurement (T1–T4 landed)

Same method as T0 (stamped `:result-tokens` when present, else chars/4 from
the stored content), corpus refreshed to 465 session files / 67,878 tool
results / ~32.9M estimated result tokens. The adoption boundary is T1's
landing (`590fe97`, 2026-09-22 22:02 UTC); `run_code` entries are zero before
it.

| tool | calls | tokens | share | avg/call |
|---|---:|---:|---:|---:|
| **bash** | 50,869 | 18.15M | **55.2%** | 356 |
| **read** | 8,119 | 13.28M | **40.4%** | 1,635 |
| **run_code** | 204 | 171k | **0.5%** | 839 |
| web search + fetch | 274 | 886k | 2.7% | 3.2k |
| everything else (edit, structural, lsp, mcp, …) | 8,412 | 383k | 1.2% | — |

- **The T0 headline split has not moved** (54.8%/41.6% → 55.2%/40.4%):
  run_code has not displaced bash-as-reader. Post-adoption alone it is 4.4% of
  the 3.88M result tokens (bash 55.7%, read 34.6%).
- **Inside bash, the find/read workload is unchanged**: with one classifier
  applied to both sides (absolute levels are not comparable to T0's
  categorizer), file-view moves 49.6% → 49.8% of bash tokens and search
  43.0% → 43.4%. read + bash file-view + bash search is ~86.5% of
  post-adoption result tokens.
- **run_code's average is a heavy tail, not a steady drip**: mean 839, median
  229 (read averages 1,635). 13 results ≥3.5k estimated tokens carry 44% of
  script tokens; 9 hit the output cap (54k tokens, 32%). Two are 50 KiB-era
  `println` dumps of whole `cat` outputs, from before `f3bb0b6` lowered the
  default to 16 KiB — since 2026-09-26 every truncated script result is
  ≤~4.1k estimated tokens.
- **The per-invocation view is one-sided**: the `/session` Tool Results
  section counts result content only — never a call's `:code` argument
  (~500 estimated tokens per run_code call, on the input side) nor the inner
  calls the sandbox keeps out of context (43 of 204 results carry a trace,
  88 inner calls, 86 of them bash; most scripts scan with `fs/` in-process).
  Conversation-weighted (tokens × later assistant turns): run_code 2.1%, bash
  58.6%, read 36.1%.

Verdict: the T0 decision stands on the current corpus, with a different
limiter — in aggregate the tool is cheap because it is used rarely (0.3% of
calls), and its per-call average describes a handful of output-heavy calls
rather than the design. The post-adoption corpus is kmet's own development
sessions, including the tool's own development.

## Design investigation (what was verified, not assumed)

The full discussion explored four ways to run model-written code. Recorded here
so T1–T3 don't re-litigate it:

1. **In-process eval on a thread + SCI.** Neither host has a force-kill:
   `Thread.stop` throws `UnsupportedOperationException` on bb (JDK 20+) and
   isn't implemented on jolt ("No matching field found");
   `suspend`/`resume`/`destroy` are gone; `Thread.interrupt` only sets the
   lock-wait flag and wakes blocking calls — a tight loop survives it
   (re-verified on `jolt v0.8.13-54-gc80ccdf6`: `.interrupt` on a
   1e12-iteration spin left it running); `future-cancel` returns true but the
   computation keeps running. Abortability now differs by host:
   - **jolt** has a runtime-level cooperative interrupt
     (`host/chez/java/concurrency.ss`, exposed as `jolt.host`): a token from
     `make-interrupt`, set from any thread with `interrupt!`, aborts the thunk
     wrapped in `run-interruptible` via the Chez engine timer (`set-timer` +
     thread-local `timer-interrupt-handler`, polled at procedure calls / loop
     back-edges). Verified on `jolt v0.8.13-54-gc80ccdf6`: a
     1e12-iteration tight loop aborted within the poll interval; the eval
     throws `ex-info "Evaluation interrupted" {:jolt/interrupted true}` and
     the thread is REUSED, not abandoned. It reaches Scheme/jolt CPU code,
     which SCI's `:interrupt-fn` cannot — but it is not a force-kill: a
     thread blocked in a foreign call (`Thread/sleep`, socket recv, blocking
     file I/O) only sees it on return to Scheme, the wrapper is opt-in per
     eval, and the API is `jolt.host` (a `#?(:jolt …)` seam).
   - **bb** has no equivalent in-process abort; `Thread.interrupt` sets a
     flag only.
   SCI's `:interrupt-fn` (called on every interpreted fn/loop entry) remains
   the cross-host mechanism the shipped T1 uses: it reliably aborts
   interpreted code (verified: a 3M-iteration spin killed on bb; 666k on jolt
   through kmet's pinned sci). Caveats from SCI's own docs: host-native CPU
   calls escape it (`.pow` example), and "for hard guarantees it is best to
   run untrusted code in a separate process that can be killed."
   Consequence: on jolt the thread-stop blocker for a native-eval script path
   is gone; on bb it stands, and the hard guarantee still points at T3.
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
`kmet.app.extensions`). The run_code tool builds a fresh per-call `sci/init`
context (the T1 original forked a cached base through
`kmet.loader.sci-loader`) and evaluates with `sci/eval-string*` directly;
kmet pins SCI in `jolt/deps.edn`, so the same evaluator is available on
jolt. Whether the eval
itself could move behind `kmet.loader` is investigated next.

### Scripts on kmet.loader (feasibility, verified)

Prompted by "could the run_code tool stop using SCI directly and ride
kmet.loader?" The answer differs by host: `load` evaluates what it loads,
and a policy only covers code that goes through it.

- **No eval API is needed — the question is where the block is installed.**
  `Loader` is `find` / `resolve` / `load` / `parent` / `unload!`, and `load`
  reads and evaluates *namespace sources*; `kmet.loader.sci-loader`'s
  `:eval-fn` (default `sci/eval-string*`) is an internal hook, not part of the
  contract. Blocking is exactly what the loader's policies do (`find` /
  `allow` / `deny` / `host-view`), but a policy only sees requests that go
  **through the loader**. On bb they do: SCI's `require` calls back into the
  loader, so the script's `sci/eval-string*` on the loader's context is
  already inside the block — the direct call is a one-line ownership seam,
  not a behavior change.
- **jolt's native loader blocks only what compiles through it.** The M4
  ambient binding (`with-loader*`) covers `io/resource`, `RT/baseLoader` and
  TCCL, but not the eval funnel: the context-carrying rewrite is a
  **compile-time hook** (`jolt.host/*invoke-rewrite*`), bound by the loader
  only while a context's own source is evaluated (`eval-namespace-source`),
  and M1 (analyzer-visible loader state) was never built (`loader.md` §9
  Phase 2). Verified on `jolt v0.8.13-54-gc80ccdf6`: `eval` of a `require`
  under `with-loader*` with an `isolated` loader is not gated at all —
  `clojure.zip`, absent from the process, loaded from the host roots anyway,
  and a nonexistent namespace fell through to the host source roots ("Could
  not locate zzz/nope.jolt (or .clj/.cljc) on the source roots"). The gate
  fires when the hook is bound: the supported path is a source `load`ed
  through a restricted context (verified: with a classpath root over a
  miss-not-denial host view — kmet's `host-view` / `jl/->loader` shape — a
  source requiring `clojure.set` loads and one requiring `clojure.string`
  fails `:loader/unreadable`); the next bullet covers binding the hook
  yourself around `eval`. `allow` is the wrong host filter for the view: its
  denial propagates and denies the context's own namespaces too (verified).
- **The file write is the supported path's cost, not an eval requirement.**
  `load` reads a hit's `:file` (or jar/embedded key); there is no inline
  `:source` hit (`hit-payload-keys`), so the public path needs a file. The
  gate itself is only the compiler hook, and both it and the
  context-carrying ops are public (`jolt.host/*invoke-rewrite*`,
  `jolt.loader/__require-in` …, with the context registered by id): a
  hand-written `context-rewriter`-shaped fn bound around `eval` in a created
  namespace gates without a file — verified: `clojure.string` and
  `jolt.host` refused, `clojure.set` allowed. That route leans on private
  internals (`context-rewriter` / `qualify-symbol` are `defn-`; the
  private-load claim/eviction is private) and creates the namespace by hand,
  so it is a fork of the compile path, not a supported seam. The shape to
  ask upstream for is an eval entry (or the ambient loader covering the eval
  funnel, which the loader's own docstring promises but does not implement
  yet) — prototyped as `jolt.loader/eval-in` in the jolt checkout on branch
  `loader-eval-in-context` (`6c032c0d`, loaderconf cases 38–39); **landed
  upstream** as `a95c0eb5` (PR #1181, 2026-09-29) plus the read-order follow-up
  `24cb1312` — merged before the v0.8.15 tag, which carries `eval-in`, and
  kmet's floor is the latest release, so no capability probe is needed (the
  `embedded-root?` precedent, now retired).
- **What a native path would still cost.** `eval-in` answers the wrapping
  question (a source string evaluated in the context, form by form, last-form
  value) and `run-interruptible` (item 1) answers the abort half, so what
  remains is semantic, not mechanical: the native evaluator cannot enforce
  the capability table below (class resolution is the host's), the require
  gate is advisory because direct fully-qualified references bypass it, host
  var mutation is global where an SCI fork kept it local, and scripts diverge
  by host. The per-call loader retention `loader.md` flags is fixed by the
  eviction branch ("Native script evaluator" below); until that lands, a
  native per-call context retains one loader per call (small, but unbounded).
- **Verdict.** On bb, moving the eval behind `kmet.loader` is a small,
  behavior-neutral seam and the right cleanup. On jolt, the native evaluator
  is now constructible and probed (below), but it trades the capability
  restriction for native fidelity, speed and abort coverage — a contract
  decision, not an engineering blocker, and one the measurement does not
  make either way (context economics are identical). The SCI fork stays the
  default until that decision lands, or until T3's process isolation is
  wanted for a real boundary (there the native evaluator is the evaluator
  and the process is the sandbox).

### Native script evaluator — eval-in landed (probe, 2026-09-29)

`eval-in` makes the whole T1 engine shape constructible without SCI. Probed
on `v0.8.14-6-g5cfe9b16` with `target/eval_in_probe.clj` (scratch under
`target/`):

- **Context** — one `jl/classpath []` over a host-view parent per call, a
  fresh namespace name each time; `unload!` unmaps it. The whitelist gates
  `require`: a name outside it fails `:loader/unreadable`.
- **Bridge** — a host namespace with dynamic vars, `require`d through the
  gate and bound around `eval-in`; promises deref; the binding conveys into
  `future` (so `sandbox/spawn` needs no re-binding).
- **Capture** — plain `binding` of `*out*`/`*err*`/print limits; the
  `sci/binding` Jolt branch disappears.
- **Abort** — `run-interruptible` around `eval-in`, `interrupt!` from the
  control thread: 1–4 ms, the thread is reused, and the continuation escape
  cannot be caught by the script (stronger than SCI's `:interrupt-fn`). A
  blocked foreign call still sees it only on return, as with the JVM.
- **Cost** — ~5–8 ms per call (prelude requires + compile + unload); there
  is no base context to cache.

What the SCI path uniquely provided does not survive:

- **No capability restriction.** A script resolves classes and loaded
  namespaces directly: `System/currentTimeMillis`, `Thread`,
  `java.io.File`, `Throwable`, `eval`, and loaded kmet namespaces by
  fully-qualified reference. `test-run-code-boundary` asserts the opposite.
- **The gate is advisory.** `(require 'clojure.pprint)` is refused, but
  `(clojure.pprint/cl-format …)` works once the host has loaded it: the
  context rewrite covers the nine `clojure.core` loading/resolve calls, not
  direct references.
- **Isolation is weaker.** SCI kept `alter-var-root`/redefinitions
  fork-local; natively a host var mutation is global.
- **Host divergence.** A Jolt script can use what bb's SCI context lacks;
  the description's "No Java interop / `Throwable` unavailable" would hold
  only where SCI runs.

**The per-call loader.** A native context is one loader; `loaders-by-id`
kept every loader ever constructed reachable, so a per-request process
accumulated them (vs. a throwaway SCI fork). The fix is drafted upstream on
branch `fix/loader-evict-unloaded` in the jolt checkout: `unload!` swaps the
entry for a closed stand-in carrying the id and an unloaded state, so the
loader graph is collectable while a stale call site still fails
`:loader/unloaded` (loaderconf case 40, a `WeakReference` + `gc-full!`
assertion). Verified against the pre-fix runtime by `target/evict_probe.clj`:
before the swap the loader is not collectable and the stale call reports
`:loader/unloaded`; after it the loader is collected and the diagnosis is
unchanged. The stand-ins themselves are not reclaimed — that waits for
per-context var tables.

**Implementation shape, if the contract decision goes native:** a portable
`script-bridge` host namespace whose dynamic vars carry the per-call bridge
(the gate admits it), a `#?(:jolt)` evaluator over `kmet.loader.jolt-loader`
(`classpath []` + `host-view`) and `eval-in`, and `run_code.cljc` selecting it
when `(resolve 'jolt.loader/eval-in)` is present — SCI stays for bb and
older Jolt. Not started: it changes the tool's contract, so the decision
comes first.

## Tiers

- **T0 — measure (landed, tool-agnostic).** Per-tool result tokens: stamped on
  each tool-result entry, derived by `tool-usage`, shown in `/session` and
  logged with `--debug`. No existing tool behavior or instruction changes.
  Results above.
- **T1 — thin run_code tool.** One in-process SCI context per call, tools
  injected as fns, eval on a daemon thread, `:interrupt-fn` + timeout + output
  guard. No process, no daemon, no protocol. Inner calls resolve through the
  normal registry path, are always async, and fire no tool-call hooks (below).
  Known limitation: host-native runaway code can't be aborted (abandoned
  worker; the agent survives) — on bb that stands; on jolt the engine-timer
  interrupt now makes a native-eval path abortable (see the loader
  investigation above). The tool bridge and implementation plan are below.
- **T2 — mcp-adapter rides the shared engine. ✅ landed.** The adapter's
  `mcpScript` tool and its `bb`-subprocess JSON-lines runtime are gone
  (`extensions/mcp-adapter/src/kmet/extensions/mcp_adapter/tool_source.clj` is
  now ~30 lines of contribution glue). What shipped:
  - **Core: extension-contributed tool sources.**
    `kmet.app.tools.registry/register-tool-source!` /
    `unregister-tool-source!` (id → a 0-arg fn returning {name → tool map})
    and `get-contributed-tools`; the sandbox resolves the surface per call,
    so the next run_code call sees a newly registered source.
    `run-code/create-tool` gained the
    `:get-contributed-tools` seam; `active-surface` merges contributions
    after the enabled filter (sandbox-only tools are not in the model's
    tool set, so `set-active-tools!` must not hide them; the registry
    shadows a colliding name) and still applies the exclusion list — a
    source can never contribute `run_code` itself. Dispatch stays one path:
    the bridge passes its surface as `execute-tool`'s `:tools` opt, so a
    contributed name goes through the same normalization/`:streams?`/
    `:contextual?` handling without joining `get-tool`/`get-all-tools`.
    The extension API (`:register-tool-source!`/`:unregister-tool-source!`,
    tracked for unload) is documented in `src/kmet/extension.md`.
  - **Adapter: the catalog as a source.**
    `tool-proxy/script-tool-records` builds records from the metadata cache
    (prefixed name → `{:name :label :description :parameters :streams?
    :execute}`; `:execute` calls `proxy/call-mcp-tool`, so lazy connect,
    failure backoff, auth and the output guard are unchanged); a name
    claimed by two servers is dropped. `tool_source/sync!` re-registers on
    every `sync-direct-tools!` (init/connect/refresh) and drops the source
    when settings `:script-mode false`. The mcpScript-only helpers in
    `tool_proxy.clj` (search/describe envelopes, TS-shape renderer,
    rank-suggestions, paginate) were deleted with the runtime.
  - **Surface carried over:** kmet result map + explicit `@` deref instead of
    `{:ok :data}` envelopes; `println` plus the compact `sandbox/emit` helper
    (the old unqualified `emit`/`console.*` surface is retired);
    progress notifications still stream (the bridge now passes the script's
    `on-update` to inner `:streams?` calls). Isolation consequence as
    recorded: scripted MCP code is T1's in-process SCI — strictly sandboxier
    than the old `bb` child carrying the host classpath — at the cost of T1's
    documented host-native-runaway limitation.
  - **Docs/validation:** the mcp skill, adapter README, and the `mcp`
    proxy description teach the script surface;
    `scripts/validate-script.bb` was rewritten to drive the contributed
    source through the real run_code tool (17 checks).
- **T3 — `kmet --script` (self-exec) and/or `--mode rpc`.** Only with a
  measured need (boot cost/call frequency) or a second consumer. The tool
  contract (description/skill/API) should survive T1→T3 untouched.
- **T4 — shared invocation core + hooks + cancellation. ✅ landed.** The
  bridge stopped owning a private execution path: `kmet.app.tools.invoke`
  serves the loop and the sandbox alike, scripted calls fire the run's
  tool hooks, a script abort cancels what it can reach, and surface
  selection unifies through `registry/select-tools`. Details below.

## T1 design — the tool bridge

Decisions from the design pass, so implementation doesn't re-derive them.

### Callable set

- **All active tools**, i.e. `registry ∩ (:enabled-tools agent)` (nil = whole
  registry), minus an exclusion list (`run_code` itself, and later any nested
  sandbox). Active is exactly what the model sees in its tool schema — this
  respects `set-active-tools!`, picks up extension tools automatically, and
  keeps discovery equal to callability. The set is fixed when the call
  starts (see Lifecycle), so a script's surface is stable for its whole run.
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
(tools/call "name" args)               ; every active tool by name — returns a promise
(tools/call-many [{:name "name" :args args} ...]) ; submit a batch, preserving order
(tools/await-all promises)              ; deref submitted calls in input order
(tools/read args) (tools/write args)    ; builtins, sugar over tools/call (same promise)
(tools/edit args) (tools/bash args)
(tools/list) (tools/describe "name")    ; discovery; synchronous bridge-local reads
(sandbox/cwd) (sandbox/env k)           ; runtime cwd; env vars
(sandbox/now) (sandbox/sleep ms)        ; time
(sandbox/emit value)                    ; print one compact result; returns nil
(sandbox/spawn f)                       ; host future (derefable)
```

The namespace is `tools` (mcpScript's surface, so T2's skill snippets survive);
`sandbox` carries the small host helpers. **Aliases are preloaded** — `fs`
(babashka.fs), `str`/`set`/`edn`/`walk` (clojure.*), `json`
(kmet.libs.json), `p` (babashka.process) — so a script is body-only: nothing
to `require` beyond that vocabulary. The script's printed output and its
return value are reported together — strings raw, other data `pr-str` (a
Clojure sandbox reports Clojure data; T2 formats MCP envelopes itself).
For a single compact result, `(sandbox/emit value)` prints the value and
returns `nil`, so the final expression does not duplicate it.

- **Call fns return promises.** The call returns immediately with a promise
  that settles with the verbatim result map; the script derefs when it needs
  the value (`@(tools/call "bash" {...})`). `call-many` submits a sequence
  of descriptors and returns their promises; the complete batch is validated
  before any descriptor is dispatched. `await-all` derefs them in input order
  after all have been submitted, polling the combined cancel signal so a
  timeout/Escape is observed even when a tool ignores cancellation. The
  mechanism is the mcpScript child
  runtime's existing one (per-id `pending` promises, one reader thread;
  concurrent calls from `future`s are safe) ported in-process — except the
  bridge hands the script the promise instead of derefing internally, which
  makes fan-out idiomatic: fire N calls, scan files locally meanwhile, deref.
  One run_code call, one context entry, N inner calls that never enter context.
- `list`/`describe` are the exception: bridge-local reads of the active-set
  registry (no dispatch), so they stay synchronous.
- Built-in fns are generated for the four builtins unconditionally (fixed,
  doc-able surface); if one is disabled, the gate produces the clear "not
  active" error. `tools/call` takes the args map optionally.
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
  context and session — exactly the cost the run_code tool exists to avoid.
  Inner results stay out of context; only the script's output enters.
- Result map settled **verbatim**: `{:content :is-error :details :truncation
  :images}`. Scripts branch on `:is-error` instead of catching; do not unwrap
  `:content` (it can be a block vector) or hide `:details` (exit codes, diff,
  full-output path).
- **Always async.** The bridge never runs a tool's `:execute` on the
  interpreter thread: each call queues a task on the shared bridge worker pool
  (16 daemon workers, started lazily; concurrency is bounded, overflow waits
  FIFO) and returns a promise the script derefs when it wants the value.
  In-flight calls don't block dispatch — scripts fire calls, do local work (fs
  scans) meanwhile, then deref — and the run `:signal` reaches the inner call,
  so Escape cancels it. A script that fans out far more calls than workers and
  derefs them in reverse waits for the queue ahead of it; bridge submissions
  cannot nest (run_code is excluded from the surface), so the pool cannot
  deadlock on itself.
- **Settlement.** Every dispatched promise settles when its tool returns —
  success or `{:is-error true}`; a tool that observes the cancel signal (bash
  does) settles on cancel. The one non-settling case is a tool implementation
  that neither returns nor honors the signal: a deref of that promise blocks
  host-side, and the run's deadline abandons the interpreter thread (T1's
  accepted host-native-runaway limitation). The signal inner calls see is
  **combined** (T4): true when the run signal (Escape) fires *or* the script
  aborts (`:timeout`, `:output-limit`), so a deadline cancels in-flight calls
  the way Escape does; a queued call picked up after the abort settles as
  cancelled and never executes. Tools that ignore the signal still linger,
  and the trace reports them `incomplete`. A script that catches the
  interrupt exception cannot outlive its abort either: the result reports
  the abort reason, not `:ok`.
- **Tool-call hooks (T4).** Inner calls run through the run's agent-level
  before/after hooks (`run-code/*tool-hooks*`): block/arg rewrite and
  `:content`/`:is-error` overrides apply, for blocked calls too. An inner
  call has a synthetic `:tool-call-id` but carries the batch's
  `:assistant-message` (`run-code/*assistant-message*`), and its `:terminate`
  hint is dropped; it still emits no tool-execution event or
  transcript/session entry. A per-tool hook is policy, not a sandbox (the
  script can shell out via `babashka.process`), so gating the outer `run_code`
  call is the only real block.
- Inner-call opts: `:signal` (the combined cancel signal — Escape and the
  script's own abort both kill inner bash children), `:ctx`
  (`build-extension-context`), `:on-update` collected into a call trace
  exposed at `:details {:calls [...]}` (mcpScript precedent), `:timeout`
  (seconds; nil = no deadline) and `:elapsed-ms` (measured total) always, **no**
  per-call UI events. The trace is an atom — concurrent calls (the normal
  fan-out case now that every call is a promise) append to it safely.

### Plumbing

- The enabled set rides a run-level thunk (`run-code/*enabled-tools-fn*`, bound
  in `loop.clj` next to `bash-tool/*cancel-signal*`; nil outside loop runs =
  all tools) and is resolved once per call: the surface, the bridge and
  the discovery fns all read that one computed map, fixed for the call.
  The loop's other run values (`bash-tool/*cancel-signal*`,
  `*session-env-fn*`, `tools-util/*cwd*`) reach the bridge the same way. The
  worker threads that run inner calls restore them explicitly — a pool worker
  conveys no dynamic bindings — which is what lets Escape cancel an inner
  bash and relative inner tool paths resolve against the session cwd.

### Lifecycle

- **Per invocation**: one fresh `sci/init` context, per-call state merged in
  (the bridge closure: deadline, signal, trace, the call's surface),
  discarded when the call returns — a context is never reused between
  scripts. The fresh context is the isolation boundary; a `def` in one call
  is invisible to the next, and redefining (or `alter-var-root`ing) an
  injected var stays call-local. A fresh init is sub-millisecond (measured:
  20 inits over the `babashka.fs` share list in 0.84 ms), so there is no base
  to cache or fork.
- **Why the surface is fixed per call**: the context injects `tools/list`
  and `tools/describe` for the call's surface, and the bridge closes over
  the same map — gate, discovery and dispatch read one surface fixed for the
  call (the same set the model saw for the turn), not a dynamic var threaded
  through the tool future. `tools/call` dispatch still resolves through
  `execute-tool`, so the record executed is the registry's (the surface was
  built from it at call start).

### Capabilities

Settled surface. Verified against both SCI hosts (bb's bundled SCI and the
jolt pin): value-shared `babashka.*` namespaces work in a context with **no
`:classes`/`:imports` at all** — the extension context's `:classes {:allow
:all}` is exactly what the run_code sandbox must not copy.

| capability | surface | notes |
|---|---|---|
| `clojure.core` | SCI's builtin core (atoms, regex, `try`/`catch`, `with-out-str`, `promise`/`deliver`/`deref`) + host patches `slurp` `spit` `file-seq` `pmap` | SCI's core lacks slurp/spit/file-seq, `future`, `pmap`, `sleep`, all `System/*` |
| string/collections | `clojure.string` `clojure.set` `clojure.edn` `clojure.walk` | SCI builtins — `require` resolves out of the box |
| fs | `babashka.fs` + `slurp`/`spit`/`file-seq` | the tools' own fs layer; bulk scanning is the tool's reason to exist |
| process/shell | `babashka.process`: `sh` `shell` `process` | `$` is a macro — value-sharing can't carry it; `sh`/`shell` cover the need. This is what makes scan-and-filter work without context spill (`rg` output stays in the script). The wrappers default `:dir` to the runtime cwd, normalize babashka.process's opts-first/opts-last shapes, and pid-track `process` |
| async | tool calls are promises (the API itself); `sandbox/spawn` returns a host future and `pmap` is injected into core | SCI has **no** `future`/`pmap` and kmet's bb SCI ships no `sci.async`; `clojure.core.async` is unusable this way (`go`/`thread` are macros). Host futures are daemon-backed on both hosts, so an underefed `spawn` cannot keep kmet alive |
| json | `kmet.libs.json` `parse-string`/`generate-string`, aliased as `json` | the same codec the tools use |
| cwd/env | `(sandbox/cwd)` (the runtime cwd), `(sandbox/env k)` | SCI has no `System/getenv`. slurp/spit/file-seq and sh/shell/process resolve relative paths against the runtime cwd like the tools; **babashka.fs stays process-relative** (no wrapper can safely guess which args are paths) — build paths from `(sandbox/cwd)` |
| time | `(sandbox/now)` / `(sandbox/sleep ms)` | any polling or scan-timeout loop needs them |
| reader features | the host's own feature (`:bb` or `:jolt`) | kmet's convention — `:clj` matches both hosts and is not exposed |
| output | `*out*`/`*err*` captured + the script's return value | captured to bounded temp files: a monitor thread polls them, streams throttled `on-update` tails while output is arriving, and aborts the run past the fixed 1 MiB capture budget, so a print loop can't OOM or fill the disk. The final result scans each file once for the exact byte/line totals and the 128 KiB retained tail. The result defaults to a 16 KiB/2000-line tail; `:max-output-bytes` can raise the byte cap up to the 50 KiB tool-wide cap |

Excluded deliberately: class access (empty `:classes`/`:imports` — host fns
hide their own; SCI's default reflective instance-method calls on host values
still work, and are harmless without class resolution),
`kmet.app.*`/`kmet.tui.*`/`kmet.extension` (the extension contract is not the
script contract), `kmet.loader`, arbitrary `require` of Maven deps (dependency
resolution is an extension-manifest feature — a missing require fails with the
boundary error), and network (`kmet.libs.http` stays out until
measured; the single-boundary rule would still apply if it lands).

Mechanics: one fresh `sci/init` per call with the capability map, the
per-call bridge and the surface's discovery fns merged in — no cached base,
no fork. `babashka.fs`/`.process` must be host-`require`d before
their public fns are value-shared (bb preloads them, jolt does not — both
verified). The prelude of aliases is evaluated **on the eval thread**, right
before the user code: SCI's `*ns*` is per-thread, so aliases registered on
the calling thread can land in a different namespace than the script's. The
bridge namespace closes over per-call state (deadline, signal, trace, the
surface — Lifecycle), so it is constructed per call.

The description must teach the boundary and the batching: **one call can
orchestrate many tool calls** (fan out, loop, chain) so N results cost one
round trip; **bulk scanning via `babashka.fs`/`slurp`/`sh`** (no truncation,
no per-call overhead); tools are for semantic queries (lsp/structural) and
mutations. A harness-level guideline in `kmet.app.skills/build-guidelines`
teaches the same at the turn level — batch independent tool calls in one
message, since the loop runs them in parallel by default — and the script
tool's own guidelines spell out the bash/script split. Both are kmet
additions; pi has no parallel-tool-call guidance.

### Settled decisions

- **Tool resolution = normal tools.** One resolution path: `get-tool` reads
  through `get-all-tools` (custom shadows built-in), so listing and dispatch
  can never disagree. Landed as T1 prep.
- **Inner calls always async, never inline.** `tools/call` (and its sugars)
  returns a promise the script derefs; `call-many` validates and submits a
  batch, and `await-all` polls cancellation while waiting in input order.
  Dispatch never blocks the interpreter, and the run `:signal` reaches the
  inner call so Escape cancels it. Scripts fan out freely — one `run_code` call
  can run N inner calls in parallel.
- **Hooks (T4).** Script-inner calls run through the *same* agent-level
  before/after hooks as the loop's batches (`run-code/*tool-hooks*` and the
  per-batch `run-code/*assistant-message*`, bound in `run-agent-turn` and
  `execute-tool-calls!`):
  the before hook can block (the call settles with its reason and never
  executes) or rewrite args, the after hook can override `:content` /
  `:is-error` — for blocked calls too, loop parity. Caveats: an inner call
  carries a synthetic `:tool-call-id`, its `:terminate` hint is ignored
  (there is no batch), and it still produces no
  tool-execution event, transcript or session entry. Mutation tools ride the
  same bridge; the callable set is every active tool, not a read-only subset.
  A per-tool gate is policy, not a sandbox — the script can also shell out
  via `babashka.process` — so gating `run_code` itself is the only real block.

## T1 implementation (landed)

Placement: **builtin** (`src/`), not an opt-in extension — the measured
workload is ~75% of all result tokens; an opt-in tool would not move it. The
builtin set grows by one (read/write/edit/bash + `run_code`); existing tool
descriptions are untouched. No settings gate in v1 (mcpScript's `:script-mode`
precedent is available if the prompt impact measures badly).

The plan above is now the record of what landed:

1. ✅ **Tool** — `src/kmet/app/tools/run_code.cljc` (`.cljc` for the
   jolt/bb `sci/binding` split), a `run_code` record (params `code` +
   `timeout` in seconds — bash's unit — plus an optional result byte
   budget, `:streams? true`, `:contextual? true`), registered at the
   bottom of `registry.clj` — after the registry fns — carrying seams
   (`:get-all-tools`, `:get-contributed-tools`, `:select-tools`,
   `:execute-tool`) so it never requires the registry back.
   No deadline by default (bash parity: `:timeout` omitted or 0 = none); a
   positive value is seconds, fractional, capped at a day.
2. ✅ **Engine** — one fresh `sci/init` per call: the capability namespaces,
   the per-call bridge and the surface's discovery fns in one merged map
   (`context-namespaces`); `*enabled-tools-fn*` is the loop-bound thunk the
   surface resolves through. No base cache, no fork, no registry generation.
3. ✅ **Capabilities** — the table above as value maps; `pmap` patched into
   `clojure.core` alongside slurp/spit/file-seq; json aliased as `json`; a
   per-call prelude (on the eval thread) aliases fs/str/set/edn/walk/json/p so
   scripts are body-only; `sandbox` for cwd/env/now/sleep/emit/spawn; the
   process wrappers add the cwd default and pid tracking; `tools/call-many`
   validates a complete batch before dispatch, while `tools/await-all` polls
   cancellation while preserving input order; `:features` is the host's own;
   no `:classes`/`:imports`.
4. ✅ **Bridge** — `tools/call` (+ variadic args), ordered `tools/call-many` /
   `tools/await-all`, and the four sugars over `execute-tool` on the shared
   bridge worker pool (16 daemon workers + FIFO queue; `concurrent/spawn` runs
   the workers, a task is `.offer`ed per call); the gate over the surface;
   `:signal`/`:ctx` passed through; the trace collected at `:details {:calls
   [...]}`; never `execute-tool-calls-*`, never the hooks.
5. ✅ **Deadlines** — eval on a daemon thread; `:interrupt-fn` checks the
   abort atom (signal/deadline/output-limit) and throws; when `:timeout` is
   set the host waits that deadline + a 1.5 s interrupt grace, then abandons
   the thread. With no deadline the wait is unbounded except for Escape: a
   host-blocked script (which the interrupt cannot reach) is abandoned after
   the same 1.5 s grace once the cancel signal fires. `await-all` polls the
   combined signal while waiting, so it does not hold the interpreter until a
   non-cooperative tool finishes. The result reports the effective `:timeout`
   (seconds, nil = no deadline) and the measured total `:elapsed-ms` in
   `:details` — wall clock for the whole call, so it includes that grace and
   the capture drain, not just script runtime.
6. ✅ **Output** — `*out*`/`*err*` bound to temp files; one monitor thread
   polls them: it emits throttled `on-update` tails while output is
   arriving and sets the abort to `:output-limit` once both files pass the
   fixed 1 MiB capture budget. `finish-capture!` stops the monitor and scans
   each file once for the exact byte/line totals and the retained 128 KiB
   tail, which the result assembler then caps with
   `bash-executor/truncate-tail`. The result body defaults to a 16 KiB /
   2000-line tail; `:max-output-bytes` can raise the byte cap up to the
   shared 50 KiB tool cap. `(sandbox/emit value)` provides a compact result path that
   returns `nil` and avoids stdout/return-value duplication. Printing is
   bounded (`*print-length*` 100000, `*print-level*` 30 — host vars on
   babashka, `sci/print-*` on Jolt), so an infinite seq cannot print host
   code past the deadline. On Jolt the writers are bound with `sci/binding`
   (the `with-sci-io` pattern); on babashka the host `*out*`/`*err*` bindings
   are enough. UI: a builtin renderer
   (`render-run-code-call`/`render-run-code-result`) shows the code header
   (collapsed head + expand hint), the output preview, a muted inner-call
   summary from `:details :calls` and the elapsed/took line, and strips the
   model-facing truncation notice in favor of its own warn line.
7. ✅ **Tests** — `test/kmet/app/test_run_code.clj`, registered in
   `kmet.tasks.runner/all-namespaces`: 37 fast tests and 16 `^:slow` tests
   covering return/output, compact `sandbox/emit` (including spawned output),
   ordered `call-many` / cancellation-aware `await-all`, batch validation,
   per-call output budgets and the fixed capture budget, errors, timeout, signal abort,
   a caught interrupt still reporting its abort, capabilities (incl. the
   preloaded aliases and an explicit `require`), cwd resolution, stderr,
   truncation, streaming, promise fan-out, the gate, discovery (with
   `:execute` sanitized), the excluded surface, context isolation, the capture
   edges (incl. a multi-byte tail cut and idle streaming), the print bounds
   and the tool-name forms, plus the subprocess and
   contributed-source cases.
8. ✅ **Verify** — the post-adoption T0 re-measurement is recorded above:
   the 54.8%/41.6% split and the bash file-view/search shares are unchanged,
   so adoption is the limiter, not per-call cost. The compact output path is
   visible after `f3bb0b6`: every truncated script result is ≤~4.1k estimated
   tokens (the two 50 KiB-era dumps predate the 16 KiB default).
9. ✅ **T2 seam** — the tool takes its registry through seams; T2 passes
   extension-contributed sources through the same map instead of a new path.

### Landed notes (edge cases)

The probes that found the capture bugs left a set of deliberate behaviors:

- **Aborted-but-finished** — an abort the interrupt never observed (Escape
  lands as the script's inner call is cancelled and the script then returns
  normally) reports success; the cancelled call still shows in
  `:details :calls`. The abort reason only wins when the interrupt actually
  fired (`assemble-result`).
- **Catching** — `(catch Exception e ...)` works, interrupts included;
  `Throwable`/`:default` are not resolvable (the sandbox has no classes).
- **Uninterruptible host code** — `interrupt-fn` runs on interpreted frames;
  a host primitive that runs long is abandoned at the deadline (or, with no
  deadline, at the next Escape) + 1.5 s grace
  and keeps its thread until it finishes (the print limits are what keep that
  window small). jolt's `run-interruptible` reaches host-native CPU code but
  not a blocked foreign call, and bb has no equivalent.
- **Coercions** — `:timeout` (seconds, bash's unit; fractional rounds to
  the nearest ms, minimum 1, capped at a day) absent, 0, negative or
  non-numeric → **no deadline**, exactly bash's semantics;
  `:code` `nil`/empty → "No code provided." (a result without
  `:timeout`/`:elapsed-ms`); a non-string `:code` is stringified.
- **stderr** is fused after stdout under a `[stderr]` marker, not
  interleaved; `(binding [*out* *err*] ...)` writes to that stream.
- **Tool names** — strings, keywords and symbols all resolve (`:read` →
  `read`); discovery normalizes and dispatch gates on the same name.
- **Long single lines** — the body cap is line-oriented
  (`bash-executor/truncate-tail`): a huge line followed by shorter lines is
  dropped in favor of those, as in bash. When only the *totals* exceed the
  cap the truncation is reported as `:truncated-by :capture`.

## T4 — shared invocation core, hooks, cancellation (landed)

The bridge no longer owns a private execution path. `kmet.app.tools.invoke`
(`prepare-tool-call` / `execute-tool-call` / `finish-tool-call`) is the one
pipeline both callers use:

- **Loop** (`execute-tool-calls-parallel!` / `-sequential!`) splits the
  phases the way it always ran them — prepare sequentially with the start
  event, execute in the batch futures, finish at finalize — and keeps its
  own events, `await-all`, context/session append and terminate handling.
  The old `before-/after-tool-hook-result` helpers and `run-tool-call!` are
  gone. The sequential path now honors a before-hook arg rewrite (it
  previously ignored one while parallel honored it).
- **Script** runs prepare → execute → finish inside its pool task; a blocked
  call settles with the hook's reason (its `:terminate` hint is dropped — no
  batch) and the after hook still runs, matching the loop. Promises, trace,
  gate and discovery stay bridge-local.
- **Hooks reach the script** through `run-code/*tool-hooks*`, bound in
  `run-agent-turn` as thunks over the agent's
  `:before-tool-call`/`:after-tool-call` (the mode-built chains over
  `extensions/get-tool-call-hooks`/`get-tool-result-hooks`), and the batch's
  assistant message through `run-code/*assistant-message*`, bound around
  `execute-tool-calls!` — so an inner call's hook payload matches the outer
  run_code call's, except for the synthetic `:tool-call-id` (`run_code-<n>`).
  Both are captured on the tool thread and passed to the pool workers by
  value (raw worker threads convey no bindings). An inner call still emits
  no tool-execution event and no transcript/session entry — gate the
  `run_code` tool itself for policy (a per-tool gate is not a sandbox: the
  script can shell out via `babashka.process`).
- **Cancellation**: the bridge's per-call signal is a read-only OR-view
  (`kmet.libs.concurrent/or-signal` — the same helper behind the loop's
  provider-stream guard), true when the run signal (Escape) fired or the
  script aborted (timeout/output-limit). It reaches in-flight tools as
  `*cancel-signal*`/`:signal` (bash's poller kills the process tree) and is
  checked twice by a pool task — at admission and again after its before
  hook, so a slow hook cannot start a call past the abort: such a call
  settles as `{:is-error true :content "run_code cancelled before …"}` and
  never executes (the trace records `:error "cancelled"`). A normally
  finished script leaves the signal false, so its queue still drains
  (underefed `tools/call` keeps its fire-and-forget meaning) — only its
  `on-update` stream stops with the result, so a late-started call cannot
  emit progress for a tool call the loop already finished. Per-tool
  coverage stays cooperative — MCP calls have no signal plumbing, and
  host-blocked code remains unkillable until T3's process isolation.
- **Surfaces** unify through `registry/select-tools` (ALL ∩ ENABLED, plus
  contributed sandbox tools, minus exclusions), used by `loop/active-tools`
  and the sandbox, so what a caller lists and what it can dispatch cannot
  drift.

## References

- `src/kmet/app/tools/run_code.cljc` — the T1 implementation: capabilities,
  bridge, capture, deadline, combined cancel signal;
  `test/kmet/app/test_run_code.clj` the tests. `run_code.cljc` (not `.clj`)
  because of the `#?(:jolt … :default …)` `sci/binding` — clj-kondo allows
  reader conditionals only in `.cljc`, and the split must be read out on
  babashka (the macro expands to private `sci.impl` fns babashka's nested SCI
  cannot resolve).
- `src/kmet/app/tools/invoke.clj` — the T4 shared invocation pipeline
  (`prepare-tool-call` / `execute-tool-call` / `finish-tool-call`);
  `test/kmet/app/test_tools.clj` covers its hook/binding semantics, and
  `test/kmet/app/test_loop.clj` the loop side (block, arg rewrite in both
  batch modes, after-hook overrides, and hook propagation into a scripted
  call).
- `src/kmet/libs/concurrent.clj` — `or-signal` (the read-only OR-view of
  cancel signals used by the run_code bridge and the loop's provider guard)
  next to the extension `spawn` helper.
- `src/kmet/app/tools/core.clj`, `src/kmet/app/tools/registry.clj` —
  `execute-tool` (the bridge seam), `select-tools` (the shared surface
  filter), `built-in-tools`, `get-all-tools`, `get-tool`.
- `src/kmet/app/loop.clj` — run-level bindings (incl.
  `run-code/*enabled-tools-fn*`, `run-code/*tool-hooks*` and the per-batch
  `run-code/*assistant-message*`) + `active-tools` / `set-active-tools!`;
  `execute-tool-calls-*` (what the bridge must not route through); the
  `--debug` per-tool report at agent end.
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
  `:eval-fn` (the default `sci/eval-string*`), `:interrupt-fn` support.
- jolt upstream, checked on `v0.8.14-6-g5cfe9b16`:
  `host/chez/java/concurrency.ss` (`run-interruptible` / `make-interrupt` /
  `interrupt!` — the engine-timer cooperative abort) and
  `stdlib/jolt/loader.clj` (`eval-in` — per-context string eval, loaderconf
  cases 38–39 — the native loader's ambient binding and its M0–M3 limits —
  `loader.md` §9 Phase 2). The eviction fix lives on branch
  `fix/loader-evict-unloaded` in the `~/jolt` checkout (`unload!` swaps the
  id→loader registry entry for a closed stand-in; loaderconf case 40 asserts
  collectability). The earlier probes in this file were taken on
  `v0.8.13-54-gc80ccdf6`; `target/eval_in_probe.clj` and
  `target/evict_probe.clj` are the scratch probes behind the native-evaluator
  subsection.
- `extensions/mcp-adapter/src/kmet/extensions/mcp_adapter/tool_source.clj` and
  `tool_proxy.clj` (`script-tool-records`) — the contributed MCP catalog
  (T2); `src/skills/mcp/SKILL.md` — the scripted-MCP surface taught to the
  model (the retired `script.clj` subprocess runtime and its tool surface
  are the T2 convergence target that landed).
- pi: `~/src/cvstree/pi/packages/coding-agent/docs/rpc.md` (stdio RPC mode) and
  `packages/protocol` (transport-neutral CBOR, remote sessions).
