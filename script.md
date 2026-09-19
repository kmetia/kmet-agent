# script.md — the script tool (design + measurement)

Question this file answers: **should kmet add a "code execution" tool (à la
maki's `code_execution`) so the model can scan/filter many files and put only
the distilled result in context — and if so, how?**

Scope: this file is about a **new** tool only. Existing tools and their
instructions are pi-aligned and are not modified by this plan (kmet's builtin
set is read/write/edit/bash; the grep/find/ls search tools ship as separate
opt-in extensions). The one thing already in place is measurement — per-tool
result-token attribution — so the build/no-build decision can be made on data.

Status: design investigation done, measurement in place. T1/T2 are documented
contingencies, not scheduled. The next step is collecting data — see
[Measuring](#measuring).

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
question is measured before anything is built.

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

So the open question is data, not features: do reads dominate kmet's context
tokens? maki's 65%-reads number is *their* usage; kmet ships structural
navigation and truncation, so the distribution may differ. Measure before
building a script engine.

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

- **T0 — measure (landed, tool-agnostic).** Record estimated result tokens per
  tool-result session entry; derive per-tool totals; show them in `/session`
  and log them to `debug.log` with `--debug`. No existing tool behavior or
  instruction changes — the measurement works for whatever tools are
  installed. Answers: do reads dominate in kmet?
- **T1 — thin script tool (~a day).** One in-process SCI context per call,
  tools injected as fns, eval on a daemon thread, `:interrupt-fn` + timeout +
  output guard. No process, no daemon, no protocol. Known limitation:
  host-native runaway code can't be aborted (abandoned daemon thread; the
  agent survives). Only built if T0 shows the need.
- **T2 — `kmet --script` (self-exec) and/or `--mode rpc`.** Only with a
  measured need (boot cost/call frequency) or a second consumer. The tool
  contract (description/skill/API) should survive T1→T2 untouched.

Default plan: **measure, and only then decide.** If reads are a small share of
context, no script tool is needed and the investigation stops at T0; if they
dominate, T1's justification is exactly that measurement.

## Measuring

Three channels, no separate `/usage` command needed:

1. **`/session`** — the **Tool Results** section is the live per-tool view:
   each tool's call count and estimated result tokens, highest first, with a
   total. The tokens are context-volume estimates (chars/4), not billed
   tokens; the **Tokens** section above it is the billed truth.
2. **`debug.log`** — run `kmet --debug`; each run appends an estimated
   per-tool token report (highest first, with TOTAL).
3. **Session files** — each tool entry has `:result-tokens` and `:tool-name`.
   A quick aggregate over the default session dir (`~/.kmet/sessions`;
   `--session-dir` overrides it). `**.ednl` is the recursive glob — sessions
   live in per-cwd subdirectories:

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

**Decision criteria** (after a few real sessions with T0):
- reads dominate results → build **T1** (script tool), with the T1 design
  above.
- reads are a small share / bash-script batching already takes the load →
  **stop**; no script tool.
- some other tool dominates (e.g. bash output or MCP) → look there instead;
  maki's 65%-reads did not transfer.

## References

- `src/kmet/app/session.clj` — `:result-tokens`, `tool-usage`, `tool-usage-report`.
- `src/kmet/modes/interactive.clj` — the `/session` **Tool Results** section.
- `src/kmet/app/loop.clj` — the `--debug` per-tool report at agent end.
- `src/kmet/app/event_bus.clj` — event vocabulary shared with the TUI (the
  serialization seam a future RPC mode would use).
- `kmet.loader.sci-loader` — `:base` fork (per-call contexts for T2's daemon),
  `:interrupt-fn` support.
- pi: `~/src/cvstree/pi/packages/coding-agent/docs/rpc.md` (stdio RPC mode) and
  `packages/protocol` (transport-neutral CBOR, remote sessions).
