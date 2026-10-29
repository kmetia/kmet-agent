# Erosion reduction — decomposition targets

`erosion` is the SCBench high-CC mass share from `bb slop`
(`tasks/kmet/tasks/slop.clj`): mass(f) = CC(f) * sqrt(SLOC(f)), numerator =
the mass of functions with CC > 10.

Baseline (`bb slop .`, source only):

```
EROSION 0.350   316 of 8756 functions CC>10   max CC 69
                mass 36112.9 of 103097.1
VERBOSITY 0.013
```

After `parse-args` (landed below):

```
EROSION 0.348   315 of 8774 functions CC>10   max CC 69
                mass 35822.1 of 102939.2
VERBOSITY 0.013
```

After the clojure_edit `execute` (landed below):

```
EROSION 0.346   314 of 8777 functions CC>10   max CC 69
                mass 35568.8 of 102801.8
VERBOSITY 0.013
```

After the lsp-adapter `execute` (landed below):

```
EROSION 0.344   313 of 8784 functions CC>10   max CC 69
                mass 35307.8 of 102683.4
VERBOSITY 0.013
```

Two facts drive the ordering. CC is a cliff — a function at CC 11 contributes
100% of its mass, at CC 10 none — so crossing the line beats shrinking it;
and SLOC is `sqrt`-weighted, so splitting beats trimming. The five open
targets carry 3,265 of 102,937 mass (3.2%); landing all of them under CC 10
moves erosion to ~0.316.

## Criteria (every landing)

A target is done only when all four hold. Order matters: a decomposition that
fails (2) is reverted, however much it improves (1).

1. **Erosion down, at the cliff.** The function drops out of the `CC > 10`
   set (or, when that is impossible in one step, its mass falls by a measured
   amount). Re-run `bb slop .` and record EROSION / VERBOSITY here.
2. **No perf regression.** Benchmark the hot shape before and after *in one
   process*, against the pre-change file. Pull the old version in under a
   renamed namespace (`target/` is untracked) and time both over the same
   inputs, interleaved so drift hits both:

   ```sh
   git show HEAD:modules/libs/src/kmet/libs/markdown.clj \
     | sed 's/^(ns kmet\.libs\.markdown$/(ns kmet.libs.markdown-before/' \
     > target/before/kmet/libs/markdown_before.clj
   BABASHKA_CLASSPATH="target/before:modules/libs/src" bb -e '
   (require (quote [kmet.libs.markdown-before :as old])
            (quote [kmet.libs.markdown :as new])
            (quote [kmet.libs.concurrent :as conc]))
   (defn ms [f n]
     (let [t0 (conc/monotonic-ms)] (dotimes [_ n] (f)) (- (conc/monotonic-ms) t0)))
   (let [s (hot-input) n 30]
     (dotimes [_ 10] (old/parse-inline s) (new/parse-inline s))   ; warm both
     (println {:old (ms #(old/parse-inline s) n) :new (ms #(new/parse-inline s) n)}))'
   ```

   Measure the shapes the app actually runs, not the shape that flatters the
   change: a plain input, a realistic one, and the worst case the target's
   code path has. Anything beyond ~`+2%` is a regression.
   On bb/jolt, SCI charges ~0.2 µs per function call and ~0.4 µs per `case`
   dispatch (measured). A decomposition that moves per-element work out of
   inline code into calls therefore costs throughput even when it removes
   branches — see the `parse-inline` entry for a worked failure. A *cold* path
   (once per process, once per session) still gets measured and recorded: it is
   accepted with the absolute delta stated, never reported as "no regression".
3. **Gates green.** `bb test-changed` (or the affected test namespaces
   directly), `bb lint-changed` at zero findings, `bb format-check-changed`.
4. **Contracts intact.** The docstring's promises, the error messages and the
   export shape are unchanged unless the item says otherwise.

## Targets

Ordered by mass, descending.

- [ ] **`run-render-loop!`** — `modules/tui/src/kmet/tui/core.clj:2385`,
      CC 52 / 462 SLOC / mass 1117.7. Shape: phase machine in one fn.
      Fix: one `defn` per phase (activate, pump, render, diff-commit) plus a
      small state record; the loop's `cond` dispatches on phase, not on
      everything. **Read `modules/tui/src/kmet/tui/tui.md` first** — the
      render loop's redraw and terminal-negotiation rules are documented, and
      this function owns raw-mode activation and restore.

- [ ] **`main-diff`** — same file, `:2685`, CC 69 / 215 SLOC / mass 1011.7.
      It is a local `fn` inside `run-render-loop!`, so the phase split above
      moves it to a top-level helper. Fix: the diff pass's `cond` on hint
      becomes three named helpers (prove-unchanged / scan-range / full-walk)
      selected by one `case`. The `::scan` / `nil` hint contract must keep its
      current meaning — it decides whether the document is repainted.

- [ ] **`parse-inline`** — `modules/libs/src/kmet/libs/markdown.clj:289`,
      CC 52 / 143 SLOC / mass 621.8. Shape: branchy parser.
      **Attempted and reverted — blocked by criterion 2.** The decomposition
      itself worked: seven `span` readers emitting in place, `INLINE-CONSTRUCT`
      as a `case` on the char, `STEP-INLINE` as the one step, `parse-inline`
      reduced to setup plus a three-branch loop (CC 52 → ~9); 78 tests / 173
      assertions green. It then measured slower than the `cond` it replaced:

      | shape | before | after | delta |
      |---|---|---|---|
      | prose (plain, 18 KB) | 1.10 ms | 1.10 ms | 0% |
      | doc line (realistic) | 0.13 ms | 0.13 ms | 0% |
      | construct-dense, no `@` | 25.6 ms | 28.4 ms | +10.8% |
      | construct-dense, with `@` | 25.4 ms | 27.8 ms | +9.3% |
      | prose with `@` (per-char path) | 45.4 ms | 56.6 ms | +24.7% |

      Cause, measured: SCI charges ~0.21 µs per call and ~0.41 µs per `case`
      dispatch, so the per-character path paid ~0.6 µs/char that the inline
      `cond` did not; a token-building closure per construct added the rest.
      The docstring's warning ("per-iteration cost is the whole game") is the
      real constraint here, not style.
      The way in, if it is revisited: remove the per-character work instead of
      *moving* it — the `@`-lines are per-char only because a bare email can
      start at any local-part character, so precompute the candidate positions
      for a line once (one pass, `str/index-of` over the `@`s) and let the loop
      chunk like the non-`@` path. That is a parser change, not an extraction,
      and it needs its own benchmark and tests.

- [x] **`parse-args`** — `modules/app/src/kmet/app/core.clj:176`,
      CC 31 / 88 SLOC / mass 290.8. Shape: argument/clause ladder.
      Landed as a parse table: nine `read-*` readers (each one argument class,
      each with its contract in its docstring), `flag-rows` (FLAG → row, built
      from the existing flag tables so there is no second list to drift),
      `shape-rows` (`@file`, the `--` extension rule, the single-dash typo, the
      positional fallback) and `finish-opts` for the mode and dump-count rules.
      `parse-args` is now CC 3.
      Three bugs the tests caught, worth knowing before touching this code: the
      readers must share one arity (5); row predicates test `flag` — the `=`
      form's left side — for the flag sets and `arg` for the `@` / `--` / `-`
      shapes (testing `arg` for the flag sets silently dropped every
      `--flag=value` form); and `print-flags` / `retired-flags` are sets, so
      `(keys …)` on them throws.
      Perf (criterion 2): 11-argument argv, 4000 parses in one process —
      12.5 µs before, 15.3 µs after (+22%, +2.75 µs). That residue is one
      reader call per argument, which is the table's shape; accepted as a cold
      path (once per process, against a startup in the hundreds of ms), and
      writing the readers back inline would restore the CC 31 function. The
      first version walked all nine rows per argument (+19 µs); indexing the
      flags as a lookup and swapping `(first (filter …))` for `some` brought it
      here.
      Was: a table of rows (`flag`, `arity`, `handler`) driven by one parse
      loop, the way the registries already do it. Behavior preserved (it is all
      in the docstring): positional paths into `:patch`, `@file` expansion into
      `:args`, `--mode` as kmet1's spelling of `--profile`, unknown `--flag` →
      `:ext-flags` while an unknown single-dash flag stays a usage error, and
      "more than one of the three dumps" still throwing.
      Verified: `bb lint-changed` 0/0, `bb format-check-changed`, and
      `kmet.app.test-core` 35 tests / 192 assertions green.

- [x] **`execute`** (lsp-adapter) —
      `extensions/lsp-adapter/src/kmet/extensions/lsp_adapter/tools.clj:334`,
      CC 29 / 81 SLOC / mass 261.0. Shape: validation ladder over an operation
      table.
      Landed as `read-request` (operation → unlisted operation → abort →
      filePath, the published order, and the request map with `:position-op?`),
      `check-shape` (numeric 1-based position, symbol query), `op-params`, `run-request`
      (`runtime/for-file` + the hierarchy prepare/walk), `shape-results`,
      `diagnostics-result` and `run-op`; `execute` is the `or` of them. CC:
      `read-request` 6, `check-shape` 6, `run-op` 6, `run-request` 3, the rest
      smaller; only `shape-result` (CC 12, not a target) remains an outlier in
      the file.
      Perf (criterion 2): abort path 1.8 → 1.2 µs per call, unknown-operation
      path 38 → 36 µs, shape-guard path within run-to-run noise (both dominated
      by building the `ex-info`); a real operation is milliseconds of LSP
      traffic. All five usage messages and the abort map compare byte-identical
      against the pre-change code.
      Verified: `kmet.extensions.lsp-adapter.tools-test` 12 tests / 48
      assertions, `e2e-test` 2 tests / 18 assertions (the live request path —
      `tools-test` never opens a connection, which is how the first cut passed
      `tools-test` while calling `for-file` with one argument too few; lint
      caught it), `bb lint-changed` 0/0, `bb format-check-changed`.
      Step 2 (not done): the `op-params` `cond` still decides the wire shape
      outside the `operations` table. Moving `:params` / `:position` /`:report`
      into the rows would make a row describe its whole request, but it changes
      the table's contract and earns its own commit.

- [x] **`execute`** (clojure edit tool) —
      `extensions/clojure/src/kmet/extensions/clojure/edit_tool.clj:154`,
      CC 27 / 88 SLOC / mass 253.3. Shape: guard chain over an operation
      `case`, with the work in a closure.
      Landed as four pieces: `guard-args` (the eight parameter checks, **order
      preserved** — a call missing two things still names the first), `content-error`
      (parse problem, then form shape; the form-type `case` became a lookup, so
      a new form type just gets the generic example), `find-edit` (the
      edit → re-indent → re-edit pass, now a top-level fn taking the request
      map), and `execute` = `or` over the three. `execute` is CC ~5, `guard-args`
      9, `content-error` 6, `find-edit` 4; the file's only remaining outlier is
      `find-top-level-form` (CC 13), which was not a target.
      Perf (criterion 2): guard path 0.6 → 1.0 µs per call, content-error path
      0.7 → 1.05 µs — one extra call, against a tool call that reads and writes
      a file. Both paths return byte-identical maps (asserted in the bench).
      Verified: `kmet.extensions.clojure.edit-tool-test` 54 tests / 153
      assertions, `bb lint-changed` 0/0, `bb format-check-changed`, VERBOSITY
      unchanged at 4 flagged lines (an inlined `enhanced-name` binding kept a
      fifth `redundant-let` out).
      Correction to the earlier note: the two `execute` functions do **not**
      share a guard chain worth a shared lib — only the shape. This one returns
      `{:content … :is-error true}` with `file_path`/`form_type`/`content`; the
      lsp one throws `::usage` ex-infos with `filePath`/`showOutput`. Unifying
      them would change both tools' published error surfaces for ~5 lines of
      helper, so each extension keeps its own guards.

## Guardrails

- Extraction has a cost the other metric charges for: near-identical helper
  bodies raise `verbosity` (the clone detector normalizes identifiers and
  erases literals). After a split, `bb slop --dups` must show no new clone
  group, and the fix is to extend the shared lower layer — `kmet.tui.core`'s
  re-exports, `kmet.libs.*` — never to re-implement beside the caller.
- A target whose decomposition cannot meet criterion 2 is **reverted, not
  accepted**: the entry records the attempt, the numbers and the cause, so the
  next person starts from the measurement instead of repeating it.
- `bb slop` exits non-zero only on usage errors today. A `--max-erosion N`
  flag (or a recorded baseline compared on review) is the missing ratchet.

## Adjacent, same shapes (not in this batch)

Remaining outliers whose fixes are the treatments above:
`process-input-buffer!` (CC 45 / 114, `kmet.tui.core`, phase + input ladder),
`agent-handle` (CC 20 / 221, `kmet.app.loop.driver`), `execute-bash` and its
inner `fn` (CC 19 / 201 and CC 37 / 79,
`kmet.app.tools.bash-executor` — the nested fn is the cliff case), `run-bash`
(CC 26 / 92, `kmet.app.tools.bash`).
