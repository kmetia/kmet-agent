# session-gaps — pi alignment for the run lifecycle

Open work. Follow-up to `5255fbbc` (`fix(loop): re-base the run-message window
when the context is replaced`), which removed the crash class behind the
reported

    Error: subvec index out of range: 300 62

but left the `:agent-end` payload and the retry/compaction orchestration
differing from pi. This note tracks the four gaps with what pi does, what kmet
does, the change, and how to verify it.

Reference checkout: `~/src/cvstree/pi`, `packages/agent/src/agent-loop.ts`
(loop) and `packages/coding-agent/src/core/agent-session.ts` (session layer).
pi line numbers are from the 2026-10-06 checkout (`HEAD 200387122`); kmet line
numbers are as of `5255fbbc` and drift.

| # | gap | touch | depends on |
|---|-----|-------|------------|
| A | `:agent-end :messages` is a context slice, not the run's messages | `app/loop.clj` (append sites) | — |
| B | `:agent-end` carries no `willRetry` | `app/loop.clj`, UI | C |
| C | retries recur inside one run instead of being separate runs | `app/loop.clj` + interactive mode | — |
| D | compaction is dispatched from the loop, not the session layer | `app/loop.clj` + interactive mode | interacts with A |

Recommended order: **A → C → B → D**. A is small and removes the last reason
`agent-end` can disagree with what actually happened; B is meaningless until C
separates attempts.

## Background — why the payload question exists

The reported session ("config builting tools", `--C--src-my-kmet--/
2026-10-06T13-28-18-767Z_1a11166984d-d286621b.ednl`) compacted mid-run: the
compaction entry sits at 14:41:12.472Z, after a tool batch, before the run's
final assistant message. The in-memory context went from 300 messages (a long
run: the branch had 302, minus 2 abandoned attempts that never entered the
context) to 62. `agent-end` sliced the context with the count captured at run
start, ran past the end, and the run's catch reported it as an error.

`5255fbbc` keeps a baseline on the agent (`:run-msg-baseline`) and re-bases it
whenever the context is replaced, so the slice is always valid. That is a
fix — not pi's model. pi never indexes the context:

- `agent-loop.ts:111/143` — `newMessages` is a list owned by the loop
  (the run's initial messages, else empty), pushed on every append
  (prepared/queued messages ~215, assistant 243, tool results 276), emitted
  verbatim at 254/290/320.
- Mid-run compaction replaces `currentContext` (`prepareNextTurn` at 186,
  `prepareRequest` at 219; the session hooks that compact are
  `agent-session.ts:905` → `_compactBeforeNextAssistantResponse` 776-785) —
  `newMessages` is not affected, so the event still reports the messages that
  were summarized away.

kmet's window is therefore *smaller* than pi's payload in exactly the cases
that matter: mid-run compaction (summarized part dropped) and failed/aborted
attempts (`record-abandoned-attempt!` writes the session only). A replaces the
window with an accumulator and deletes `run-messages` /
`rebase-run-messages!` and their call sites.

## A. `:agent-end :messages` — accumulate the run's messages

Today: `run-messages` (`app/loop.clj:1286`) slices `@(:messages agent)` from
`:run-msg-baseline`; the baseline is set in `run-agent-turn` (1714) and re-based
by `sync-context-after-compaction!` (1302), `replace-context!` (1478) and
`restore-session-context!` (2074).

Change:

1. Add `run-messages` (name it `:run-log` or keep `:run-messages`) to
   `AgentState` + `make-agent-state` (144): `(atom nil)`.
2. Add one append helper and use it everywhere the context grows, so the
   context and the run list cannot diverge:
   `(defn- append-message! [agent msg] (swap! (:messages agent) conj msg) (record-run-message! agent msg))`,
   with `(defn- record-run-message! [agent msg] (when-let [rm (:run-messages agent)] (swap! rm conj msg)))`.
3. `run-agent-turn`: `(reset! (:run-messages agent) [])` at run start (same
   place the baseline reset is now), and emit `@(:run-messages agent)` at
   `agent-end`.
4. Append sites to convert (open-coded `swap! (:messages agent) conj` today):
   - `add-user-message!` (516) — prompt, steering, follow-up
   - `add-assistant-message!` (527)
   - `add-custom-message!` (568)
   - `execute-tool-calls-parallel!` (625) and `-sequential!` (743)
   - `tools-phase!` (1588; the suppressed-result append at ~1684)
   - `add-bash-result!` (1093) and `flush-pending-bash-messages!` (1107)
   - `add-context-message!` (1123)
5. `record-abandoned-attempt!` (545) keeps writing the session only for the
   *context* (pi's `_prepareRetry` drops the failed message from agent state,
   `agent-session.ts:3784-3785`) but must call `record-run-message!` — pi
   pushes it into `newMessages` before the stopReason check
   (`agent-loop.ts:243`), so it is in `agent_end.messages`.
6. Delete `run-messages`, `rebase-run-messages!`, the `:run-msg-baseline`
   field, and the three re-base calls.
7. Update the `:agent-end` doc in `app/event_bus.clj` (it currently explains
   the re-base) and the Appendix row in `development/pi-alignment.md`.

Acceptance:

- mid-run compaction: `:agent-end :messages` contains the run's *pre*-
  compaction messages too (the assistant tool-call and its tool result, and
  the user message), matching pi. This inverts
  `test-loop-agent-end-after-mid-run-compaction`, which today asserts the
  opposite — rewrite it to assert pi parity.
- cancelled run: the aborted partial (session-only today) appears.
- retried attempt (after C): the failed attempt's message appears.
- a plain tool run: user + assistant + tool result each appear exactly once.
- an extension listener on `:agent-end` sees the same vector as the UI.

Risks/notes:

- A missed append path silently shrinks the payload — the helper makes that a
  one-line mistake instead of a hand-written invariant.
- Appends outside a run (`!` bash while idle) must no-op: `:run-messages` is
  nil outside `run-agent-turn`.
- `make-agent-state` currently gets unknown-but-used keys (`:enabled-tools`,
  `:default-tools`) through the record's extension map — declare the new field
  in `defrecord AgentState` like `:run-msg-baseline` is.

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
  `verify_regression.bb` runs the new test with the pre-fix slicing simulated
  in-process (`alter-var-root` on the private helpers) and shows it failing.
- Real evidence: the session file above, compaction entry
  `2026-10-06T14:41:12.472Z`, `:tokens-before 166703`,
  `:first-kept-id 1a111a446c2-fa70b8b4`.
- Jolt reproduces the message exactly:
  `jolt -e "(try (subvec (vec (range 62)) 300) (catch Exception e (str e)))"`
  → `java.lang.IndexOutOfBoundsException: subvec index out of range: 300 62`
  (babashka's SCI throws the same exception without the message text).

## Docs to update as items land

- `src/kmet/app/event_bus.clj` — `:agent-end` payload (`:will-retry`, the
  accumulator instead of the re-base).
- `src/kmet/development/pi-alignment.md` — Appendix row
  `agent_start` / `agent_end` / `agent_settled`, the compaction row
  (`session_before_compact` / `session_compact`), and §2.7 if D lands.
- This file: strike items as they land; remove it when the table is empty.
