# script.md — the script tool (design + measurement)

Question this file answers: **should kmet add a "code execution" tool (à la
maki's `code_execution`) so the model can scan/filter many files and put only
the distilled result in context — and if so, how?**

Scope: this file is about a **new** tool only. Existing tools and their
instructions are pi-aligned and are not modified by this plan (kmet's builtin
set is read/write/edit/bash; the grep/find/ls search tools ship as separate
opt-in extensions). The one thing already in place is measurement — per-tool
result-token attribution — so the build/no-build decision can be made on data.

Status: **T0 measured and analysed** (results below). The numbers rewrite the
premise rather than kill it: maki's read-share did not transfer — bash
dominates — but ~75% of all result tokens are still the find/read workload; it
leaks through bash (`cat`/`head`/`rg`) instead of the `read` tool. T1 is
re-aimed at that workload, and its tool-bridge design is recorded below.
Implementation not started.

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
so T1/T2 don't re-litigate it:

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
  guard. No process, no daemon, no protocol. Known limitation: host-native
  runaway code can't be aborted (abandoned daemon thread; the agent survives).
  The tool bridge is specified below.
- **T2 — `kmet --script` (self-exec) and/or `--mode rpc`.** Only with a
  measured need (boot cost/call frequency) or a second consumer. The tool
  contract (description/skill/API) should survive T1→T2 untouched.

## T1 design — the tool bridge

Decisions from the design pass, so implementation doesn't re-derive them.

### Callable set

- **All active tools**, i.e. `registry ∩ (:enabled-tools agent)` (nil = whole
  registry), minus an exclusion list (`script` itself, and later any nested
  sandbox). Active is exactly what the model sees in its tool schema — this
  respects `set-active-tools!`, picks up extension tools automatically, and
  keeps discovery equal to callability.
- The gate lives in the **bridge**, not in `execute-tool` (`execute-tool` is
  also called by the user-bash path, extensions and tests; global enforcement
  would change their behavior). Unknown/inactive names return
  `{:is-error true}` with the active list — not an SCI unresolved-symbol.
- `list`/`describe` report the **active set only**.

### API

```
(t/call "name" args)                  ; every active tool by name — the universal path
(t/read args) (t/write args)          ; builtins, sugar over t/call (identical dispatch)
(t/edit args) (t/bash args)
(t/list) (t/describe "name")          ; discovery; describe is sanitized
```

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
- Result map returned **verbatim**: `{:content :is-error :details :truncation
  :images}`. Scripts branch on `:is-error` instead of catching; do not unwrap
  `:content` (it can be a block vector) or hide `:details` (exit codes, diff,
  full-output path).
- Inner-call opts: `:signal` (the run cancel — Escape must kill inner bash
  children), `:ctx` (`build-extension-context`), `:on-update` collected into a
  call trace exposed at `:details {:calls [...]}` (mcpScript precedent), **no**
  per-call UI events. A script may call tools from `future`s; the trace is an
  atom.

### Plumbing

- The enabled set rides a dynamic var bound in `loop.clj`'s run-level
  `binding`, next to `bash-tool/*cancel-signal*`, `*session-env-fn*`,
  `tools-util/*cwd*` — conveyed into the tool future. The bridge computes
  names live from the registry, so mid-session register/unregister behaves
  like `active-tools` and `set-active-tools!`'s next-turn semantics hold.

### Injection

- Cached stdlib base + per-call fork (the extensions' `shared-context`
  pattern; `kmet.loader.sci-loader`'s `:base` handles the merge-opts pitfalls).
  Inject `slurp`/`spit`/`file-seq` (SCI's builtin core lacks them — same trick
  as `extensions.cljc`), `babashka.fs`, `clojure.string`/`edn`/`set`, json.
  The bridge namespace closes over per-call state (deadline, signal, trace),
  which is why it is merged into the fork rather than cached.
- The description must teach the boundary: **bulk scanning via
  `babashka.fs`/`slurp`** (no truncation, no per-call overhead); tools are for
  semantic queries (lsp/structural) and mutations.

### Open items

- **Tool-call hooks.** `on-tool-call`/`on-tool-result` hooks live in the loop,
  not `execute-tool` — inner calls bypass them, so an extension that blocks
  `bash` is bypassed by a script. Option A: v1 exposes a read-only surface
  (read + structural + lsp + fs libs): no bypass, and it matches the measured
  workload. Option B: route inner calls through the hooks with synthetic
  `:tool-call-id`s and the outer assistant message. Decide before scripts get
  mutation tools.
- **`get-tool` vs `get-all-tools` precedence.** Names come from
  `get-all-tools` (custom wins via `merge`) but dispatch resolves through
  `get-tool` (built-in wins via `or`) — a shadowed name would execute a
  different tool than the one listed. Fix custom-wins before relying on
  name→record parity.
- **Abort/timeout.** `:interrupt-fn` aborts interpreted code; the deadline is
  checked before each dispatch and the run signal is passed to inner calls; a
  host-native call already in flight is not preemptible (same limitation as
  host-native runaway code).

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
- `kmet.loader.sci-loader` — `:base` fork (per-call contexts for T2's daemon),
  `:interrupt-fn` support.
- pi: `~/src/cvstree/pi/packages/coding-agent/docs/rpc.md` (stdio RPC mode) and
  `packages/protocol` (transport-neutral CBOR, remote sessions).
