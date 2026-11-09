# Agent tool-mode benchmark

`bb bench-tools` measures how much work an agent spends to finish a small
task, not how fast a function runs. It is the regression loop for prompt,
guideline and tool-surface changes, especially `--tools eval` versus
`--tools bash`. `eval` is one declared tool with `read`, `write`, `edit`,
`bash` and `sh` verbs inside it; `bash` is one declared shell tool. The
comparison is between those two model-facing surfaces, not a claim that eval
has no file or shell access.

```sh
bb bench-tools list
bb bench-tools --tasks surface --repeats 1 --model <pattern>
bb bench-tools --repeats 3 --model <pattern> --thinking max --label eval-baseline
bb bench-tools compare target/bench-tools/<baseline>.edn target/bench-tools/<candidate>.edn
```

A run writes `<out>.edn` and `<out>.md`; workspaces and session logs stay
under `<out>.work/`.

## What one run measures

Every task runs in a clean workspace, in print mode, with no project context
files, the same model and thinking level, and a fresh session directory. The
harness reads the session EDNL back through `kmet.app.session.stats`, so the
numbers are the ones `/session` shows:

| field | meaning |
|---|---|
| `turns` | assistant/model round trips in the session |
| `tool-calls` | calls the model made, including calls inside eval scripts' bridge |
| `tokens` | provider input, output, cache-read and cache-write totals (and their sum) |
| `cost` | provider-attributed USD from the per-message usage |
| `first-prompt-tokens` | the first request's full prompt: base prompt, tool declarations, guidelines and the task prompt |
| `tool-result-tokens` | estimated result tokens (characters/4), the amount re-sent on later turns |
| `by-tool` | calls and result tokens per tool name |
| `eval.actions` | how often eval code uses `read`, `slurp`, `bash`, `sh`, `emit`, `eval/help`, the tools bridge, etc. |
| `errors` / `error-kinds` | failed tool results, coarsely categorized |
| `ok?` | the task's `check.sh` passed and the process exited 0 |

The suite contains a `surface` calibration task: it asks for one word, so its
`first-prompt-tokens` isolates the fixed prefix (system prompt + tool
declarations + guidelines). Use it for cheap prompt/tool-schema iterations; use
the full suite for behavior. The calibration run is included in the suite
totals, so pass a `--tasks` list without `surface` when you want behavior-only
aggregates.

## Apples to apples

- **Cold prefix.** Each run appends a unique nonce to the system prompt so
  one run cannot read another run's provider cache. Cache use inside a run is
  still measured.
- **Same model and thinking.** Pass the model pattern with its provider and
  the thinking level explicitly; the report records the models it observed. A
  comparison across different models is not a tool comparison.
- **Clean workspace per run.** The task's `workspace/` is copied to a fresh
  directory; the check lives outside it.
- **No project instructions.** `--no-context-files` keeps `AGENTS.md` from
  changing the prefix between modes.
- **Paired repetitions.** `--repeats 3` runs each task/mode three times and
  pairs them by task, mode and repetition in the comparison. Use medians and
  the per-task rows; a single run is a smoke test, not a result.
- **Interleaved order.** Modes alternate per repetition so a provider slowdown
  does not always hit the same mode.

The harness does not sandbox the process beyond the fresh workspace. If an
ambient `patch.edn` or settings file would change the composition, pass
`--agent-dir` with an isolated agent directory (containing the auth file and
the settings the run should use).

## Changing the prompt, guidelines or tools

Those are composition data, not benchmark options. Put the candidate change in
a patch file and pass it to both sides of a comparison:

```clojure
;; eval-guidelines.edn
{:patch [{:id :tool/eval
          :config {:prompt-guidelines
                   ["Use (read path start end) for long files."
                    "Use bash for shell syntax; sh runs one command."]}}]}
```

```sh
bb bench-tools --modes eval=eval --repeats 3 --patch eval-guidelines.edn \
  --label eval-guidelines --model <pattern>
bb bench-tools compare target/bench-tools/<baseline>.edn target/bench-tools/<candidate>.edn
```

For a source change, run the baseline from a clean worktree or record the git
revision in the result (the report includes it). The comparison treats the
first file as the reference and the second as the candidate.

## Adding a task

A task is a directory under `scripts/bench/agent-suite/tasks/`:

```
tasks/<id>/
  task.edn       {:id "..." :prompt "..." :tags [...] :timeout-seconds 300}
  workspace/     copied into the run's clean working directory (optional)
  check.sh       run from the workspace after the agent settles
```

`check.sh` must exit 0 for a pass. It receives:

- `KMET_BENCH_WORKSPACE` — the workspace
- `KMET_BENCH_STDOUT`, `KMET_BENCH_STDERR` — captured kmet output
- `KMET_BENCH_SESSION` — the session file, or empty
- `KMET_BENCH_RUN_DIR`, `KMET_BENCH_TASK`, `KMET_BENCH_MODE`

Keep checks outside the workspace and deterministic. A check should test the
behavior the prompt asked for, not the exact command sequence.

## Reading a result

`bb bench-tools analyze <result.edn>` prints the stored report;
`bb bench-tools analyze <session.ednl>` (or a session directory) prints the
same metrics for an ad-hoc session. The diagnostics section is deliberately
heuristic: it points at the next measurement, not at a guaranteed cause.

Common readings:

- Higher `first-prompt-tokens` on `surface` means the fixed prompt or tool
  declarations grew; trim there first.
- Higher `turns` with flat `tool-calls` means the model needed more attempts
  per action (discovery, syntax errors, unusable results).
- Higher `tool-result-tokens` means later turns pay more context; prefer ranged
  reads and bounded output.
- A rising `eval.actions` count for `eval/help`, `tools/*` or `sh` with a
  matching error category is a prompt/usability signal, not a model-quality
  judgment.

## Limits

- Tasks are small and deterministic; the suite measures iteration cost, not
  long-horizon maintenance.
- Provider nondeterminism remains. Use three or more repetitions and read the
  medians and per-task rows.
- Token estimates for tool results are characters/4, the compaction convention;
  provider usage is exact.
- A pass/fail check is not a code-quality score. Review the workspaces when a
  result surprises you; they live under `<out>.work/`.

For a heavier, realistic long task, the external `tiginim-bench` repository
runs the same tool presets through v1/v2 phases. This suite is the fast,
in-repo regression loop; use both when a change is meant to affect real
sessions.
