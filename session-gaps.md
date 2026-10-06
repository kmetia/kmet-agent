# session-gaps — pi alignment for the run lifecycle

Open work after the `subvec` crash fix (`fix(loop): re-base the run-message
window when the context is replaced`) and its follow-up A (the run-message
accumulator). Together they removed the crash class behind the reported

    Error: subvec index out of range: 300 62

and brought the `:agent-end` payload in line with pi. What remains is the
retry/compaction orchestration (B–D): this note tracks each gap with what pi
does, what kmet does, the change, and how to verify it.

Reference checkout: `~/src/cvstree/pi`, `packages/agent/src/agent-loop.ts`
(loop) and `packages/coding-agent/src/core/agent-session.ts` (session layer).
pi line numbers are from the 2026-10-06 checkout (`HEAD 200387122`); kmet line
numbers are indicative and drift.

| # | gap | touch | depends on |
|---|-----|-------|------------|
| A | ~~`:agent-end :messages` is a context slice, not the run's messages~~ | landed — see A below | — |
| B | `:agent-end` carries no `willRetry` | `app/loop.clj`, UI | C |
| C | retries recur inside one run instead of being separate runs | `app/loop.clj` + interactive mode | — |
| D | compaction is dispatched from the loop, not the session layer | `app/loop.clj` + interactive mode | interacts with A |

Recommended order: **C → B → D** (A landed). B is meaningless until C
separates attempts.

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

Still open (deliberately): the payload is one list for both the UI and the
extension bus — an extension listener sees the same vector as the UI (pi
decorates the public event with `willRetry` and leaves the extension event
alone — item B).

## B. `:agent-end` carries no `willRetry`

pi: the session decorates the event for its public listeners —
`this._emit({ ...event, willRetry: this._willRetryAfterAgentEnd(event) })`
(`agent-session.ts:1140`). `_willRetryAfterAgentEnd` (1197) walks
`event.messages` backwards to the last assistant message and reports whether it
is a retryable error with attempts left. Note the extension-runner event is
emitted *before* the decoration and carries `{type, messages}` only (1287-1288),
so extensions never see `willRetry`.

kmet today: `:agent-end` carries `{:messages ... :error ...}` only; the UI
learns about a retry from `:auto-retry-start`/`:auto-retry-end`, which fire
*inside* the run (before `:agent-end`).

Change (after C): compute the flag where the retry decision is made and add it
to `:agent-end`. Decide whether kmet's single bus (`emit` reaches both the UI
callback and the extension bus) should expose it to extensions too, or split as
pi does.

Acceptance: a run that ends in a retryable error emits
`:agent-end {:error msg :will-retry true}` and is followed by another run; a
terminal failure emits `:will-retry false`.

## C. Retries should be separate runs

pi: `_runAgentPrompt` (`agent-session.ts:1820-1845`) runs
`agent.prompt(...)`, then loops on `_handlePostAgentRun` (1851-1889):
`_prepareRetry` (3760) emits `auto_retry_start`, omits the failed attempt from
the projection (3785), sleeps with backoff (abortable via `abortRetry`, 3805),
and `agent.continue()` starts a *new* low-level run with a fresh
`newMessages` — so there is one `agent_end` per attempt. `agent_settled` fires
once, in the `finally` of the whole prompt loop.

kmet: `run-agent-turn` owns the attempt loop. The `:backoff` branch
(`app/loop.clj:1894`/`1953`) emits `:auto-retry-start`, sleeps
(`retry/backoff-sleep!`) and recurs; `:overflow-recover` (1947) does the same
for overflow. One `:agent-end` covers every attempt, and `:agent-settled`
immediately follows it.

Options:

1. **Align.** `run-agent-turn` returns when an attempt settles with a
   retryable error (no in-run recur). The caller — the interactive submit path
   in `modes/interactive/turn.clj` and print mode — keeps the retry state
   machine: `retry/retry-decision`, `retry/backoff-sleep!`,
   `:auto-retry-start`/`:auto-retry-end`, then calls `run-agent-turn` again
   with `message nil`. Per attempt: one `:agent-end`; after the last attempt:
   one `:agent-settled`.
2. **Document** the deviation instead (kmet-only `:auto-retry-*` placement,
   one `agent-end` per prompt).

Acceptance (option 1): N retries produce N `:agent-end` events and exactly one
`:agent-settled`; `:auto-retry-start`/`end` still bracket each backoff; the
failed attempt stays visible in the transcript, with the retried stream opening
a fresh message below it (pi's TUI behavior).

Risks: the print mode's single result value; `cancel-turn` between attempts;
steering/follow-up queues (kmet drains them in the inner loop, pi drains before
`agent_end` — check `has-queued-messages?` consumers); tests that pin the
current `:auto-retry-*`/`:agent-end` ordering.

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
2. pi's overflow retry is a new run (via C); kmet recurs in-run.
3. kmet emits `:compaction-start`/`:compaction-end`/`:context-replaced`
   (kmet-only events); pi's extension events are
   `session_before_compact`/`session_compact` (`pi-alignment.md` Appendix marks
   that row `~`).

Change (only if 1/2 are wanted): expose a post-run check to the caller, call it
after `agent-end` and before `:agent-settled`; a `:threshold` compaction just
settles the run (pi does not retry for threshold), overflow keeps its
continue-style retry. Leave the per-turn check where it is — it already matches
pi.

Acceptance: a run whose final assistant message crosses the threshold emits
`agent-end` → `compaction-start` → `compaction-end` in that order, with no extra
run; an overflow error compacts and then starts a fresh attempt; event-order
tests for both.

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

- `src/kmet/app/event_bus.clj` — done (A: the accumulator; the `:will-retry`
  key when B lands).
- `src/kmet/development/pi-alignment.md` — done (A: the Appendix row
  `agent_start` / `agent_end` / `agent_settled`); the compaction row
  (`session_before_compact` / `session_compact`) and §2.7 follow with D.
- This file: strike items as they land; remove it when the table is empty.
