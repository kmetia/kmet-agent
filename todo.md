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

After the bash executor (landed below):

```
EROSION 0.339   310 of 8801 functions CC>10   max CC 69
                mass 34651.9 of 102324.7
VERBOSITY 0.012
```

After the five AI stream parsers (landed below):

```
EROSION 0.329   305 of 8837 functions CC>10   max CC 69
                mass 33494.6 of 101723.6
VERBOSITY 0.012
```

Two facts drive the ordering. CC is a cliff — a function at CC 11 contributes
100% of its mass, at CC 10 none — so crossing the line beats shrinking it;
and SLOC is `sqrt`-weighted, so splitting beats trimming. The three open
targets carry 2,751 of 102,324 mass (2.7%). Both large ones are blocked by
criterion 2 (see their entries); landing the TUI pair under CC 10 anyway
would move erosion to ~0.321, and `parse-inline` to ~0.315.

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

Both large targets are blocked by criterion 2 (see their entries), so what is
left is the mid-size set: functions over the cliff for a *dispatch* reason
rather than a per-element one, where a split costs nothing per frame or per
byte. Their numbers are in § Adjacent.

- [ ] **`run-render-loop!` + `main-diff`** (one job) —
      `modules/tui/src/kmet/tui/core.clj:2385` (CC 52 / 462 SLOC / mass 1117.7)
      and the local `main-diff` at `:2685` (CC 69 / 215 SLOC / mass 1011.7).
      **Attempted and reverted — blocked by criterion 2**, like `parse-inline`.
      The decomposition worked and every behaviour held: the frame split into
      `frame-context` (document pass), `full-redraw-reason` (the renderer
      choice), `diff-frame!` plus one fn per branch (`render-region!`,
      `render-tail-delete!`, `render-shrink-above!`, `render-unchanged-tail!`,
      `changed-range`/`scan-changed-range`, the three window predicates),
      `commit-frame!`, `run-scrollback-heal!`, and the loop's own `park-loop!` /
      `stop-render-loop!` / `publish-terminal-size!` / `blank-frame-state!` —
      all sharing one 35-key per-frame context map. `run-render-loop!` fell to
      ~6 decisions and `main-diff` disappeared; file erosion 0.378 from 0.666
      (mass 1276.5 from 3154.8, 11 of 221 CC>10 from 9 of 197). 27/27
      render-loop tests (146 assertions) and 303 TUI tests (1700 assertions)
      passed, lint and format clean, on the first splice.
      It then measured slower than the `let` it replaced — a typing frame's
      renderer tail (end of the document pass → terminal write), p50 µs,
      three runs per side, each in a fresh process:

      | run | before | after | delta |
      |---|---|---|---|
      | 1 | 260 | 357 | +37% |
      | 2 | 290 | 342 | +18% |
      | 3 | 305 | 364 | +19% |

      Cause, measured: the frame body became ~12 extra function calls and a
      35-key map built and destructured several times per frame, and a frame's
      *total* work is 1.1-1.3 ms on this phone — so a ~70 µs tax is +5% of the
      frame, not the +0.2% a once-per-frame call would cost. The same lesson as
      `parse-inline`: on a per-frame path the call/lookup tax is not noise.
      The `:stream` scenario is not usable as evidence: there the document
      outgrows the window, the scrollback dirty latches, and the heal's forced
      follow-up frame makes the old and new render counts differ — measure
      `:typing` (or a frame count that does not include a heal) instead.
      Way in, next attempt: keep the frame body one `let` (closures over the
      frame's locals stay the cheap form) and split only the *dispatch* — a
      handful of local closures, three or four extra calls per frame, no
      context map. Budget the regression as ≤2% of the tail (~6 µs) and measure
      before writing the second half. **Read `modules/tui/src/kmet/tui/tui.md`
      first** — the loop owns raw-mode activation, restore, and the redraw
      rules; `scripts/bench/kmet_frame_bench.clj` needs a session file, so the
      render-loop test harness (virtual terminal, `bf-end → write` window) is
      the practical A/B rig.

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

- [x] **Five AI stream parsers** (one batch) —
      `parse-responses-event` (`modules/ai/src/kmet/ai/api/sse.clj:216`,
      CC 27 / 76 SLOC / 235.4), `parse-mistral-event` (`:553`, CC 29 / 65 /
      233.8), `bedrock-frame-event` (`:786`, CC 31 / 50 / 219.2),
      `bedrock-messages` (`api/bedrock_converse_stream.clj:100`, CC 25 / 88 /
      234.5) and `detect-openai-completions-compat` (`model_gen.clj:1481`,
      CC 30 / 61 / 234.3) — 1,157 mass. All run once per stream event, tool
      call or model detection, so the per-event budget is µs.
      The three SSE parsers became handler tables keyed by the wire's event
      name (`responses-event-handlers`, `bedrock-frame-handlers`) or by delta
      kind (`mistral-content-events` / `mistral-tool-call-event`), each row a
      fn over the parsed payload; the frame logic that was inline in the
      `case`/`cond` is now one fn per event kind (`responses-item-added`,
      `responses-args-delta`, `responses-args-done`, `bedrock-tool-use-start`,
      `bedrock-content-delta`, `bedrock-usage`, …). `bedrock-messages` split
      into one fn per role (bash / tool / assistant / user) plus
      `bedrock-reasoning-block` for the thinking-signature rule, and the loop
      in `bedrock-conversation` only dispatches. `detect-openai-completions-
      compat` became a family table (`compat-families`: provider keywords and
      URL markers per family) plus `compat-flags`, with the policy lists
      (`:non-standard?`, `:supports-strict-mode`, …) as data read by
      `any-flag?`, so a provider joining a group is a row edit.
      File masses: sse.clj 1483.3 → 794.9, bedrock_converse_stream.clj 422.8 →
      188.3, model_gen.clj 1263.0 → 1028.7 (`bedrock-request` CC 23 and
      `parse-anthropic-event` CC 25 remain, not targets).
      Perf (criterion 2): not measured per event — these are once-per-stream-
      event paths whose cost is dominated by the JSON parse and the HTTP read,
      and the decompositions add at most one table lookup per event (no
      per-element work moves). Recorded as such rather than as a number.
      Verified: `kmet.ai.test-sse` 32 / 100, `test-llm-bedrock`,
      `test-llm-mistral`, `test-llm-openai`, `test-models`, `test-api-tools`,
      `test-model-data`, `test-llm-models`, `test-model-config`,
      `test-provider-composer` — the affected AI suites, all green (the
      provider-detection test covers every family's strict-mode and
      thinking-format answer). `bb lint-changed` 0/0, `bb format-check-changed`,
      and the flagged-line counts are unchanged in all three files.

- [x] **`fn@123` + `execute-bash`** (bash executor) —
      `modules/app/src/kmet/app/tools/bash_executor.clj:123` (CC 37 / 79 SLOC /
      mass 328.9, the spawn closure inside `create-default-ops`) and `:271`
      (CC 19 / 201 SLOC / mass 269.4). Shape: a spawn with two watchers, and a
      stream walk with a duplicated finalize.
      Landed as thirteen fns, each with one job: `stdin-transport?` /
      `shell-argv` / `write-command-to-stdin!` / `track-spawned-pid!` on the
      argv side; `watch-timeout!` and `watch-signal!` for the two tree kills;
      `pump-stream!`, `await-readers!`, `close-reader!`, `drain-streams!` for
      the pipes; `finish-spawn` for the abort/timeout classification; and
      `new-run-state` (the call's atoms and decoders), `collect-text!`,
      `make-decoder`, `spill-chunk!`, `handle-raw-bytes!`, `clean-tail`,
      `truncate-and-persist!`, `finalize-output`, `settle-grace!` and
      `failure-result` on the stream side. `execute-bash` is the call sequence.
      The duplicated finalize (success path and catch path both cleaned,
      truncated and persisted the tail) collapsed into the helpers, which is
      why the file's flagged lines fell from 13 to 4.
      The file now has **0 of 37 functions over CC 10** (max CC 10, erosion
      0.000 from 0.292): both targets gone in one landing, 598.3 mass.
      Perf (criterion 2): the per-chunk streaming path (decode → sanitize →
      rolling tail, 2000 × 1 KiB chunks per call) measured 111.98 → 113.53 µs
      per chunk at landing (+1.4%, min of five rounds) and 103.27 → 102.34 µs
      (−0.9%, same rig) after the follow-up simplification — the first reading
      was noise-dominated, and the honest summary is parity. A whole
      `execute-bash` call is within run-to-run noise either way: it pays a
      process spawn and the 100 ms grace poll that dwarf the streaming work.
      Verified: a new `kmet.app.tools.test-bash-executor` (12 ^:slow tests, real
      subprocesses: exit code, stdout+stderr, truncation and its spill file,
      timeout tree kill, cancel signal, missing cwd, spawn hook + env merge,
      command prefix, the `shell-args` argv transport, and the up-front
      shell-path check) plus `test-shell`, `test-bash-output`, `test-read`,
      `test-mutation`, `test-tool-tables`, `test-util` and the TUI turn test —
      69 tests / 249 assertions, all green. `bb lint-changed` 0/0,
      `bb format-check-changed`. The tests were written against the pre-change
      code first, which is how the one real slip showed up: a decoder's
      `:finish` was read instead of called, appending two fn objects to the
      output.

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
`process-input-buffer!` (CC 45 / 480.5, `kmet.tui.core` — the largest mass
left in the tree, and the input reader's hot path: the `parse-inline`
per-element argument applies to its inner byte walk, while the chunk-level
ladder around it is landable), `agent-handle` (CC 20 / 297.3,
`kmet.app.loop.driver` — once per agent turn), `run-bash` (CC 26 / 249.4,
`kmet.app.tools.bash` — once per tool call), `score-tool-match` (CC 26 /
245.3, mcp search), `load-extension*` (CC 23 / 237.9, once per load),
`parse-kitty-sequence` (CC 34 / 235.6, per escape sequence),
`interactive/run` (CC 37 / 395.1, once per process start), `myers-ops`
(CC 26 / 206.4, per edit), `parse-anthropic-event` (CC 25 / 188.7, once per
stream event — the last of the sse.clj parsers), `bedrock-request` (CC 23 /
188.3, once per request) and `validate-groups!` (CC 22 / 160.2, once per
catalog generation).

The once-per-event outliers, which are the cheap landings now that both large
targets are blocked: `build-frame-lines` (`kmet.tui.core:143`, CC 13 /
mass 129.3 — once per frame, and its branches are the line-memo QUICK? path),
`dispatch-input!` (`:1332`, CC 14 / 103.8 — per keystroke),
`resolve-overlay-layout` (`:527`, CC 15 / 97.2 — per overlay layout),
`shape-result` (`extensions/lsp-adapter/.../tools.clj:161`, CC 12 / 63.5 —
once per tool result, the `execute` work's leftovers) and
`find-top-level-form` (`extensions/clojure/.../edit_tool.clj:64`, CC 13 /
76.9 — once per edit).
