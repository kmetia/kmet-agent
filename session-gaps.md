# session-gaps — pi alignment for the run lifecycle

Open work after the `subvec` crash fix (`fix(loop): re-base the run-message
window when the context is replaced`) and its follow-ups A (the run-message
accumulator), B (`:will-retry`) and C (retries as separate attempts). Together
they removed the crash class behind the reported

    Error: subvec index out of range: 300 62

and brought the `:agent-end` payload and the attempt/retry lifecycle in line
with pi. What remains is the post-run compaction dispatch (D): this note
tracks each gap with what pi does, what kmet does, the change, and how to
verify it.

Reference checkout: `~/src/cvstree/pi`, `packages/agent/src/agent-loop.ts`
(loop) and `packages/coding-agent/src/core/agent-session.ts` (session layer).
pi line numbers are from the 2026-10-06 checkout (`HEAD 200387122`); kmet line
numbers are indicative and drift.

| # | gap | touch | depends on |
|---|-----|-------|------------|
| A | ~~`:agent-end :messages` is a context slice, not the run's messages~~ | landed — see A below | — |
| B | ~~`:agent-end` carries no `willRetry`~~ | landed — see B below | C |
| C | ~~retries recur inside one run instead of being separate runs~~ | landed — see C below | — |
| D | compaction is dispatched from the loop, not the session layer | `app/loop.clj` + interactive mode | interacts with A |

Remaining: **D** (A, B, C landed).

## Background — why the payload question exists

The reported session ("config builting tools", `--C--src-my-kmet--/
2026-10-06T13-28-18-767Z_1a11166984d-d286621b.ednl`) compacted mid-run: the
compaction entry sits at 14:41:12.472Z, after a tool batch, before the run's
final assistant message. The in-memory context went from 300 messages (a long
run: the branch had 302, minus 2 abandoned attempts that never entered the
context) to 62. `agent-end` sliced the context with the count captured at run
start, ran past the end, and the run's catch reported it as an error.

The crash fix kept a baseline on the agent (`:run-msg-baseline`) and re-based it
whenever the context is replaced, so the slice was always valid — a fix, not
pi's model. A then replaced the baseline with an accumulator (see below). pi
never indexes the context:

- `agent-loop.ts:111/143` — `newMessages` is a list owned by the loop
  (the run's initial messages, else empty), pushed on every append
  (prepared/queued messages ~215, assistant 243, tool results 276), emitted
  verbatim at 254/290/320.
- Mid-run compaction replaces `currentContext` (`prepareNextTurn` at 186,
  `prepareRequest` at 219; the session hooks that compact are
  `agent-session.ts:905` → `_compactBeforeNextAssistantResponse` 776-785) —
  `newMessages` is not affected, so the event still reports the messages that
  were summarized away.

kmet's window was therefore *smaller* than pi's payload in exactly the cases
that matter: mid-run compaction (summarized part dropped) and failed/aborted
attempts (`record-abandoned-attempt!` used to write the session only).

## A. (landed) `:agent-end :messages` — the run's accumulated messages

`AgentState` now carries `:run-messages` (an atom of `{run-token [message…]}`,
declared in the record) plus `:run-token` (the in-flight run's token, or nil
outside a run). `append-message!` appends to the live context and to the run
list in one place, and `record-run-message!` covers the session-only
`record-abandoned-attempt!` (pi pushes the errored/aborted message into
`newMessages` before the stopReason check, `agent-loop.ts:243`).
`run-agent-turn` publishes a fresh token and an empty list at run start,
`agent-end` emits this run's list (`(get @:run-messages run-token)`), and the
`finally` drops only this run's entry — so a cancelled run whose unwind races
a following run can neither report nor clear the newer run's list. The
post-settle bash flush appends through `append-context-message!`, so those
entries are not part of the payload (pi flushes after `agent_end` too; the
context-only append is explicit rather than a consequence of the reset
order). `record-run-message!` no-ops when `:run-token` is nil, so idle `!`
bash results stay context-only.
`run-messages`, `rebase-run-messages!`, `:run-msg-baseline` and the three
re-base call sites are gone, as is the `subvec` — nothing indexes the live
context any more.

Covered by tests: `test-loop-agent-end-after-mid-run-compaction` (rewritten —
the run's user message is dropped from the live context by the mid-run
compaction and still appears in the event exactly once, with the tool-call
assistant, the tool result, and the post-compaction answer, in order),
`test-loop-retry-records-failed-attempt` (the errored attempt is in the
payload) and `test-loop-cancel-records-aborted-attempt` (the aborted partial
is). `target/verify_regression.bb` stubs `record-run-message!` and shows the
seven payload assertions failing without the accumulator.

Landed as well: the payload is the attempt's own list for both the UI and the
extension bus — the message vector stays shared; B below splits off only
`:will-retry` (pi's extension and public `agent_end` both carry `messages`).

## B. (landed) `:agent-end` carries `willRetry`

pi: the session decorates the event for its public listeners —
`this._emit({ ...event, willRetry: this._willRetryAfterAgentEnd(event) })`
(`agent-session.ts:1140`). `_willRetryAfterAgentEnd` (1197) walks
`event.messages` backwards to the last assistant message and reports whether it
is a retryable error with attempts left. Note the extension-runner event is
emitted *before* the decoration and carries `{type, messages}` only (1287-1288),
so extensions never see `willRetry`.

kmet: `run-attempt!` emits `:agent-end` with `:will-retry` when its retry
decision is `:backoff` (a retryable error with budget left) and `false` on
every other exit. `emit` (`app/loop.clj`) drops the key from the copy handed to
`event-bus/emit-event!`, so the UI callback sees it and extension listeners do
not — the split pi makes explicit at its two emit sites.

Covered by `test-loop-auto-retry-succeeds` (`:will-retry [true true false]`),
`test-loop-auto-retry-exhausted` (`[true false]`, the terminal `:agent-end`
carries `:error`) and `test-loop-agent-end-will-retry-ui-only` (the UI sees the
key, the extension bus does not).

## C. (landed) Retries are separate attempts

pi: `_runAgentPrompt` (`agent-session.ts:1820-1845`) runs
`agent.prompt(...)`, then loops on `_handlePostAgentRun` (1851-1889):
`_prepareRetry` (3760) emits `auto_retry_start`, omits the failed attempt from
the projection (3785), sleeps with backoff (abortable via `abortRetry`, 3805),
and `agent.continue()` starts a *new* low-level run with a fresh
`newMessages` — so there is one `agent_end` per attempt. `agent_settled` fires
once, in the `finally` of the whole prompt loop.

kmet: `run-agent-turn` is now the prompt-level state machine; the low-level
loop moved to `run-attempt!`. `run-agent-turn` publishes a fresh run token and
accumulator per attempt, runs `prepare-run!` once (the submitted message,
before-agent-start hooks, pre-run compaction — pi: `prompt()` vs `continue()`),
and the attempt emits its own `:agent-start`/`:agent-end`. `:retry` runs the
backoff (`retry/backoff-sleep!`, abortable by `cancel-turn`) and starts the
next attempt; `:overflow` compacts then retries; `:terminal` and `:aborted`
settle. Exactly one `:agent-settled` is emitted after the loop. Option 1 of the
original note was chosen — the public signature did not change, so print mode
and the interactive submit path are untouched.

Covered by `test-loop-auto-retry-succeeds` / `-exhausted` (N attempts → N
`:agent-end`, one `:agent-settled`; `:auto-retry-start`/`:agent-end` bracket
each backoff), `test-loop-retry-records-failed-attempt`,
`test-loop-cancel-records-aborted-attempt` and
`test-loop-retry-cancel-during-backoff`.

## D. Compaction dispatch belongs to the session layer

pi dispatches automatic compaction outside the low-level loop:

- before a prompt: `_checkCompaction(lastAssistant, false)`
  (`agent-session.ts:2054`);
- per turn: `prepareNextTurnWithContext` → `_compactBeforeNextAssistantResponse`
  (776-785, called at 905) — this is what kmet's `tools-phase!`
  `maybe-compact!` (`app/loop.clj:1588`, check at ~1668) mirrors;
- after the run settles: `_handlePostAgentRun` → `_checkCompaction` (1882),
  which may `agent.continue()` (overflow: 3014/3042), and `compact()` (2762).

kmet: `maybe-compact!` (1432) from `prepare-run!` (1545) and `tools-phase!`
(1588); `compact-for-overflow!` (1469) inside the retry branch. Behaviour on
the wire is close; the differences are:

1. pi checks compaction *after* `agent_end`; kmet defers that check to the next
   prompt's pre-run check. Same effect on the next request, later in time.
2. pi's overflow retry is a new run (via C); kmet now also starts a new
   attempt (`:overflow` → `compact-for-overflow!` → next `run-attempt!`) —
   only when the compaction actually succeeded. A failed or aborted
   compaction settles the prompt with the overflow surfaced (pi:
   `_checkCompaction` returns false), matching pi instead of the old
   unconditional in-run retry.
3. kmet emits `:compaction-start`/`:compaction-end`/`:context-replaced`
   (kmet-only events); pi's extension events are
   `session_before_compact`/`session_compact` (`pi-alignment.md` Appendix marks
   that row `~`).

Change (only if 1 is wanted): expose a post-run check to the caller, call it
after `agent-end` and before `:agent-settled`; a `:threshold` compaction just
settles the run (pi does not retry for threshold). Leave the per-turn check
where it is — it already matches pi.

**Not landed deliberately.** A first attempt called `maybe-compact!` in the
wrapper after a settled attempt. That re-triggers immediately when a run's
compaction was aborted or failed (the context is still over the threshold, so
the next run's post-run check retries the same compaction), which hung
`test-loop-abort-compaction-keeps-run` — a `compact-token-threshold` context
with a never-finishing summarization stub. pi guards the post-run check with
`skipAbortedCheck`, which does not cover a *successful* run whose compaction
was separately aborted. Re-introduce it only with a guard that suppresses the
check after an aborted/failed compaction in the same prompt (or carry pi's
`_overflowRecoveryAttempted`/`skipAbortedCheck` state explicitly).

Acceptance (if D is picked up): a run whose final assistant message crosses the
threshold emits `agent-end` → `compaction-start` → `compaction-end` in that
order, with no extra run; an aborted/failed compaction in the same prompt does
not re-trigger; event-order tests for both.

## Verification harness from the original report

- Scratch repro scripts (gitignored, under `target/`): `check_subvec*.bb`
  recompute the 300/62 counts from the real session file;
  `verify_regression.bb` disables the run-message accumulator in-process
  (`alter-var-root` on `record-run-message!`) and shows the agent-end payload
  assertions failing — the pre-A behavior.
- Real evidence: the session file above, compaction entry
  `2026-10-06T14:41:12.472Z`, `:tokens-before 166703`,
  `:first-kept-id 1a111a446c2-fa70b8b4`.
- Jolt reproduces the message exactly:
  `jolt -e "(try (subvec (vec (range 62)) 300) (catch Exception e (str e)))"`
  → `java.lang.IndexOutOfBoundsException: subvec index out of range: 300 62`
  (babashka's SCI throws the same exception without the message text).

## Docs to update as items land

- `src/kmet/app/event_bus.clj` — done (A: the accumulator; B: the `:will-retry`
  key and the per-attempt `:agent-start`/`:agent-end` / once-per-prompt
  `:agent-settled`).
- `src/kmet/development/pi-alignment.md` — done (A/B/C: the Appendix row
  `agent_start` / `agent_end` / `agent_settled`); the compaction row
  (`session_before_compact` / `session_compact`) and §2.7 follow with D.
- This file: strike items as they land; remove it when the table is empty.
