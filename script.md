# script.md — script execution & tool-token measurement

Question this file answers: **should kmet add a "code execution" tool (à la
maki's `code_execution`) so the model can scan/filter many files and put only
the distilled result in context — and if so, how?**

Status: **T0 landed** (guidance + grep/find re-enabled + per-tool token
attribution). T1/T2 are documented contingencies, not scheduled. The next step
is measuring — see [Measuring](#measuring).

## The idea and the economics (maki.sh)

The conversation is re-sent on every turn, so a tool result does not cost its
tokens once — it costs them again on every later turn until compaction. maki's
`code_execution` lets the model write a script that gathers/reads/greps many
files inside a sandbox and prints only the lines that matter; the rest never
enters the context window. Their reported split: read results were ~65% of all
billed tokens, bash ~12%. The script tool was built to compete with *reads*.

A simpler observation from the same source: the model can already batch via
`bash` + python/awk/jq, but it doesn't *reliably choose to*. maki's claim is
that tool descriptions that "nag" change that choice. That claim is what T0
tests — cheaply.

## What kmet already has

| maki idea | kmet status |
|---|---|
| `index` (tree-sitter skeleton before reads) | ✅ tree-sitter extension: `list_symbols`, `get_symbol_body`, `find_definition`, `find_callers`, `find_callees` + `lsp` adapter |
| deferred MCP tool definitions (`tool_search`) | ✅ mcp-adapter: one `mcp` proxy tool, lazy servers, output guard |
| truncation everywhere | ✅ read (2000 lines/50KB), bash (line/byte caps + full-output file) |
| partial output on interrupt | ✅ bash returns streamed output + "Command aborted" |
| compaction | ✅ `kmet.app.compaction` (LLM summarization) |
| visibility (tokens/cost) | ✅ footer: ↑in ↓out R/W cache, $cost, context % |
| `code_execution` (script + distilled output) | ❌ — and **the paved search path was missing too: `grep`/`find` were disabled in `registry.clj`** |
| per-tool token attribution | ❌ (T0 adds it) |

So the real gap was two-fold: no paved "search, don't read" tools, and no data
on where kmet's tokens actually go. maki's 65%-reads number is *their* usage;
kmet ships structural navigation and truncation, so the distribution may
differ. Measure before building a script engine.

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

- **T0 — steer existing tools + measure (this change).** Re-enable `grep`/
  `find`, add batching/navigation guidance, record per-tool result tokens.
  Answers: do reads dominate in kmet, and does guidance change behavior?
- **T1 — thin script tool (~a day).** One in-process SCI context per call,
  tools injected as fns, eval on a daemon thread, `:interrupt-fn` + timeout +
  output guard. No process, no daemon, no protocol. Known limitation:
  host-native runaway code can't be aborted (abandoned daemon thread; the
  agent survives).
- **T2 — `kmet --script` (self-exec) and/or `--mode rpc`.** Only with a
  measured need (boot cost/call frequency) or a second consumer. The tool
  contract (description/skill/API) should survive T1→T2 untouched.

Default plan: **do T0, measure, and only then decide.** If guidance alone
moves the read share, T1 may be unnecessary; if it doesn't, T1's justification
is exactly that measurement.

## T0 changes (landed)

- **`grep`/`find` re-enabled** (`kmet.app.tools.registry`). Both now resolve
  relative paths against the runtime cwd (`tool-util/resolve-tool-path`, the
  same rule read/write/edit use) instead of the process cwd, and
  `util/safe-file-seq` skips `.git` (a repo's object store is never
  source-search material). `ls` stays disabled.
- **Guidance**: bash gains a batching guideline ("prefer one command or
  embedded script that prints only the relevant lines over many separate read
  calls"); grep's description/guideline pushes "locate first, read only what
  you need"; read's description says to use grep to locate things across
  files. The existing pi rule ("Use bash for file operations…") now fires only
  when bash is the sole exploration tool, which is what pi does.
- **Attribution**: every tool-result session entry carries `:result-tokens`
  (chars/4, the compaction convention). `session/tool-usage` derives
  `{tool-name {:calls n :tokens t}, :total {...}}` from the entries
  (unstamped entries — legacy files, rebuilt contexts — are estimated on the
  fly); `session/tool-usage-report` formats it. With `--debug`, the agent-end
  path logs the report to `debug.log`, once per run.

## Measuring

Two channels, no `/usage` command needed:

1. **`debug.log`** — run `kmet --debug`; each run appends an estimated
   per-tool token report (highest first, with TOTAL).
2. **Session files** — each tool entry has `:result-tokens` and `:tool-name`.
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
- reads still dominate results → build **T1** (script tool), with the T1
  design above.
- reads dropped / bash-script batching took over → **stop**; T0 was the win.
- some other tool dominates (e.g. bash output or MCP) → optimize that
  instead; maki's 65%-reads did not transfer.

## References

- `src/kmet/app/tools/registry.clj` — built-in tool set + descriptions/guidelines.
- `src/kmet/app/tools/grep.clj`, `find.clj` — re-enabled implementations.
- `src/kmet/app/session.clj` — `:result-tokens`, `tool-usage`, `tool-usage-report`.
- `src/kmet/app/event_bus.clj` — event vocabulary shared with the TUI (the
  serialization seam a future RPC mode would use).
- `kmet.loader.sci-loader` — `:base` fork (per-call contexts for T2's daemon),
  `:interrupt-fn` support.
- pi: `~/src/cvstree/pi/packages/coding-agent/docs/rpc.md` (stdio RPC mode) and
  `packages/protocol` (transport-neutral CBOR, remote sessions).
