# Run-lifecycle alignment plan (pi ↔ kmet)

## Purpose

Bring kmet's agent run lifecycle — the low-level attempt loop, the prompt-level
retry/overflow state machine, and the session-layer boundaries around them —
in line with pi, after the `subvec` crash fix and its follow-ups A/B/C.

This is an implementation handoff. It records the decisions taken and the
ordered work; treat it as the intended scope unless implementation findings
require revisiting a decision. It also owns the pi 0.87.0 session-context wave
moved here from
[`src/kmet/development/pi-alignment.md`](src/kmet/development/pi-alignment.md)
§7, which now points back at this file.

## Reference

`~/src/cvstree/pi` at `HEAD b9ab918c6` (v1.0.3):

- `packages/agent/src/agent-loop.ts` — low-level loop (`runLoop`, `finishTurn`, `prepareRequest`).
- `packages/coding-agent/src/core/agent-session.ts` — session layer (`_runAgentPrompt`, `_handlePostAgentRun`, boundaries).
- `packages/coding-agent/src/core/extensions/types.ts` — extension event shapes.

pi line numbers are from that checkout. (The original note named `200387122`;
it is an ancestor of `b9ab918c6`, touches neither file, and already contains
the 0.87.0 wave — its `_runAgentPrompt` is at 1775 too, so the note's old
"1820" figure was stale.) kmet line numbers are indicative and drift.

## Status

Landed baseline — do not redo:

- **A** — `:agent-end :messages` is the attempt's accumulated messages
  (`:run-messages` atom + `*run-token*` dynamic binding), not a slice of the
  live context. `append-message!` records; `record-abandoned-attempt!` covers
  the session-only errored/aborted message; `append-context-message!` is the
  context-only path for the post-settle bash flush.
- **B** — `:agent-end :will-retry`, delivered to the UI callback only, not the
  extension bus (pi's `_emit` decoration precedes `_emitExtensionEvent`).
- **C** — retries and overflow recovery are separate attempts:
  `run-agent-turn` owns the state machine, `run-attempt!` is the low-level
  loop, one `:agent-start`/`:agent-end` pair per attempt, one `:agent-settled`
  per prompt.
- **E** — a message an `:agent-end` handler queues starts a fresh attempt in
  the same prompt. `run-agent-turn`'s `:settled` branch drains
  steering-else-follow-up into the context (`drain-next-queue!`, mirroring
  `Agent.continue`) and begins the next attempt; `on-done` moved to the
  prompt's settle point so one prompt still tears down once. Also fixed the
  after-turn-stop settled outcome's missing `:status`, which resumed the
  follow-up drain with a nil turn index and crashed the next turn.
- **J** — `peek-queued-messages`: a non-destructive, mode-aware preview of
  the next batch (steering wins), carried by the F/G boundaries as
  `:pending-messages`.
- **F** — the actionable `:turn-end` boundary: the loop hook is pi's
  `finishTurn` (`:end`/`:continue`), handlers return
  `{:entries [...] :continue bool}`, and an errored or aborted response emits
  `:turn-end` too, before `:agent-end`.
- **G** — the actionable `:agent-before-settle` boundary: `:outcome`,
  `:pending-messages`, `:can-continue`, committed entries, and
  `{:continue true}` → one more attempt before `:agent-settled`.
- **D** — the post-run compaction check: `run-agent-turn` runs
  `maybe-compact!` in the `:settled` and `:error` arms (pi:
  `_handlePostAgentRun` → `_checkCompaction`), and `:compaction-blocked`
  stands the automatic checks down after an aborted or failed compaction in
  the same prompt. The Phase 1 status finding landed alongside it.
- **H** — the pending custom-message queues: a custom message deferred
  while streaming lands at the end of the turn (context-only), `:next-turn`
  is injected with the next prompt, and the interactive registry appends
  exactly once per delivery mode (the busy follow-up double append is gone).
- **I** — the per-request checkpoint: a `:prepare-request` hook runs before
  every provider request and may replace the in-flight context (and the
  model / thinking / system) without a turn boundary. The session-side
  canonical install is deferred to K.
- **L** — `:context` sees the conversation only (the prompt is re-attached
  to a replacement) and the new `:context-with-system` runs after it over
  the full transcript, its result sent verbatim.

Open: **K**'s pi-parity remainder — the append-only context-edit layer
itself landed (see Phase 5), but three pieces that make the loop *use* it are
still open: the request-time canonical install (pi:
`_installAgentRequestProjection`), the retry/overflow recovery omission (pi:
`_omitRecoveryAttempt`), and the boundaries' entry ids + context preview
(the Phase 2 finding).

## Target behavior (pi)

The prompt pipeline (`_runAgentPrompt`, `agent-session.ts:1775`):

1. `agent.prompt()` / `agent.continue()` runs one low-level run (`runLoop`),
   which drains steering and follow-up and then emits `agent_end`
   (`agent-loop.ts:320`). `newMessages` is the attempt's own list.
2. `_handlePostAgentRun` (1806), in order:
   - retryable error → `_prepareRetry` + backoff, then `continue`;
   - terminal errored message → `auto_retry_end`;
   - post-run compaction `_checkCompaction` (1837);
   - messages queued by `agent_end` handlers → `hasQueuedMessages()` →
     `continue` (1841).
3. `_runBeforeSettleBoundary` (1846) — actionable `agent_before_settle`.
4. settle: flush pending bash/custom messages, emit `agent_settled` once
   (prompt `finally`).

Inside the loop, `finishTurn` (`agent-loop.ts:252` error/aborted, `286`
normal) runs before `turn_end` and may return `{action: "continue" | "end"}`;
`prepareRequest` (219) installs canonical context before every provider
request. The session wraps `finishTurn` (`_installAgentBoundaryHooks`, 858) to
make `turn_end` actionable, and `prepareNextTurnWithContext` (877) to run the
per-turn compaction.

## Scope & decisions

In scope: D–L.

Out of scope: per-model image input limits (stay in `pi-alignment.md` §2),
RPC/JSON modes, project trust, and the pi-alignment.md locked decisions.

Decisions taken for this plan:

1. Boundary handler results are session entries, appended through the session
   (`{:entries [...] :continue bool}`), matching pi's `SessionBoundaryDraft`.
2. Exactly one `:agent-settled` per prompt, emitted last; it stays
   notification-only.
3. A low-level attempt stays the unit of `:agent-start`/`:agent-end` (from C).
4. D must carry the abort guard found necessary in the reverted attempt (see
   D); do not re-land it unconditionally.

## Dependency order

| phase | items | rationale |
|-------|-------|-----------|
| 1 | E, J | localized to `run-agent-turn`; no boundary machinery needed |
| 2 | F, G (landed) | F establishes the boundary result shape; G builds on it |
| 3 | D (landed) | post-run dispatch needs E's queue point and G's pre-settle position, plus the abort guard |
| 4 | H, I, L (landed) | context ingestion; H flushes at F's `turn_end`, L depends on I's request assembly |
| 5 | K | session context model, largest and most independent |

---

## Phase 1 — post-run continuation (E, J landed)

### E. Messages queued by an `:agent-end` handler start a fresh attempt

**Landed.** Two notes beyond the sketch. The next attempt *drains before it
starts* (`drain-next-queue!` mirrors `Agent.continue`: steering first, then
follow-up, per mode), so the queued message is that attempt's prompt instead
of costing an extra empty request; a second batch still queued then is picked
up by the attempt's own follow-up poll, as pi's `runLoop` does. And `on-done`
moved from `run-attempt!` to the prompt's settle point — the UI's
`running-turn?` teardown keys on it, so a per-attempt call would mark the
prompt finished between attempts. The registry's "run vs queue" branches now
key on `state/turn-running?` too (pi: `isStreaming` outlives the last
attempt); the sites still on the status atom are the finding below.

**Goal.** A follow-up/steer queued during the `:agent-end` extension dispatch
runs in the same prompt instead of waiting for the next submit.

**pi.** The low-level loop drains both queues before `agent_end`
(`agent-loop.ts:301-308`, final emit 320), but `agent_end` handlers run after
that drain. `_handlePostAgentRun` therefore ends with
`return !abort && this.agent.hasQueuedMessages()` (`agent-session.ts:1841-1843`)
and `_runAgentPrompt` answers with `agent.continue()` (1775-1794). Note
`:agent-settled`-handler messages stay queued in pi too (settle fires in the
prompt `finally`) — the entry point is `:agent-end`.

**kmet.** `run-attempt!` emits `:agent-end` and returns `{:status :settled}`;
`run-agent-turn` treats `:settled` as the end of the prompt, so the message
sits until the next user submit.

**Change.**

1. In `run-agent-turn`'s `case :settled`, before leaving the loop, check
   `has-queued-messages?`; when true, `begin-attempt!` and `recur`.
2. Keep the per-prompt accumulator reset and the single `:agent-settled`.
3. Do not consume `:agent-settled`-handler messages (pi parity).

**Acceptance.** A test where an `:agent-end` listener queues a follow-up:
two `:agent-start`/`:agent-end` pairs in one prompt, one `:agent-settled`.

### J. `peekQueuedMessages`

**Landed.** `peek-queued-messages` (`src/kmet/app/loop.clj`): non-destructive,
mode-aware, steering wins over follow-up. The pending display keeps using the
raw `queued-messages` — that is what pi's `updatePendingMessagesDisplay`
does (`getAllQueuedMessages`, not `peekQueuedMessages`).

**Goal.** Preview the next queued batch without draining it, for the F/G
boundary previews and the UI pending display.

**pi.** `peekQueuedMessages` (`packages/agent/src/agent.ts:330`) is
non-destructive; `_getPendingBoundaryMessages` (`agent-session.ts:967`) also
folds in `_pendingCustomMessages`.

**kmet.** `has-pending-messages` checks only the two string queues and never
previews.

**Change.** Add `peek-queued-messages` over `:steering`/`:follow-up` (and the
pending-custom queue added in H), used by F/G and the pending display.

**Acceptance.** Peeking does not empty the queues and returns the same messages
the next drain consumes.

### Phase 1 finding: the settle window reported "idle" (fixed with D)

The `:status` atom reads `:idle` from the attempt tail on, so between that
point and the prompt's finally the agent looked idle while a prompt was in
flight — pi's `isStreaming` (`_isAgentRunActive`) stays true for the whole
prompt. E fixed the two sites the continuation depends on. D widened the
window (the post-run compaction), so the remaining sites now key on the run
flag — `state/turn-running?` (pi: `isStreaming`), with `:compacting?` folded
in where pi's `isIdle` includes it — instead of the status atom:

- `ui_registry.clj` `:is-idle` (ext ctx, pi `isIdle`), `:wait-for-idle`,
  `:abort` (pi `abort()` works while a run is active, and aborts a
  compaction too)
- `commands.clj` `/compact` and `/reload` "wait for the current response"
  guards

---

## Phase 2 — turn and settle boundaries (F, G landed)

### F. Actionable `turn_end` + `finishTurn` (error/aborted coverage, continue/end)

**Landed.** `:turn-end` is actionable and fires for every completed turn,
including a response that errored or was aborted (before `:agent-end` and
`:auto-retry-start`). The loop hook is pi's `finishTurn`: the agent field
`:should-stop-after-turn` became `:finish-turn` and returns `:end` /
`:continue` / nil; the boundary result `{:entries [...] :continue bool}`
merges with it the way pi's wrapper does — `:end` wins, a boundary
`:continue` is honored only when the context can back one more request (pi's
`canContinue`), and a rejected one is reported and dropped. Entries are
session entry maps appended in order; their context projection is a
context-only append, so committed entries stay out of `:agent-end :messages`.

Three notes beyond the sketch. Errored and aborted turns run the hook and the
boundary but their decision is dropped, as pi's low-level loop does;
`:prepare-next-turn` still runs only for completed turns (pi runs
`prepareNextTurn` at the next turn's start). The mid-run compaction now runs
after the turn boundary, matching pi's next-turn preparation. And a terminal
error returns an `:error` attempt outcome (a failed overflow compaction uses
the same continuation path), so the prompt still reaches the pre-settle
boundary — `on-done` stays unsent.

**Goal.** `:turn-end` becomes actionable and runs consistently, including on
errored and aborted attempts.

**pi.** `finishTurn` runs for normal (`agent-loop.ts:286`), error, and aborted
(`:252`) responses, before `turn_end` (`:253`, `:287`), and returns
`{action: "continue" | "end"}` (`:289`, `:294`). The session wraps it
(`_installAgentBoundaryHooks`, `agent-session.ts:858`) so
`_dispatchTurnEndBoundary` (818) sets `_lastActivityOutcome` (414/822) and
hands `turn_end` handlers `{entries, continue}`; entries are committed
(`_commitBoundaryDrafts`, 996); `continue` forces one more request.

**kmet.** `:turn-end` return values are ignored (`tools-phase!`,
`final-phase!`); `after-turn!` (`app/loop.clj:1561`) runs
`prepare-next-turn`/`should-stop-after-turn`, returns a boolean, and is
reached only on the normal tool/final paths. Errored/aborted attempts emit no
`:turn-end` at all.

**Change.**

1. Emit `:turn-end` for the errored/aborted assistant message, before
   `:agent-end`/`:auto-retry-start`.
2. Run the turn hooks on the error and aborted paths too.
3. Adopt the `finishTurn` result shape (`:continue`/`:end`) from both the loop
   hook and the `:turn-end` boundary; let the boundary return
   `{:entries [...] :continue bool}` and commit entries in order.

**Acceptance.** An errored attempt emits `:turn-end` before `:agent-end` and
`:auto-retry-start`; a `:turn-end` handler returning `:continue` forces one
more request; returned `:entries` persist in order.

### G. Actionable `agent_before_settle` boundary

**Landed.** `:agent-before-settle` fires once per settle, after the queued-
message continuation and before `:agent-settled`, carrying `:outcome` (the
run's last turn outcome, tracked in an agent atom reset per prompt),
`:pending-messages` and `:can-continue`. Handlers commit entries and
`{:continue true}` runs one more attempt; a handler that queues a message
continues as well (pi's `shouldContinue` includes `hasQueuedMessages`), and
the next attempt starts from the queues (pi: `agent.continue`). A cancelled
prompt never reaches the boundary, and a cancel during the handlers drops the
continuation (pi: `_abortDuringBeforeSettle`). Both boundaries dispatch
through a new `event-bus/emit-boundary!`, which threads each handler's
`:entries`/`:continue` into the next handler's event (pi: `emitBoundary`).

**Goal.** A pre-settle boundary lets handlers append entries and force one
final request before the prompt settles.

**pi.** `_runBeforeSettleBoundary` (`agent-session.ts:1846`), after
`_handlePostAgentRun` decides not to continue: emits `agent_before_settle`
with `outcome: _lastActivityOutcome` and a boundary context preview
(`_buildBoundaryContext`, 973); handlers return `{entries, continue}`
(`types.ts:992`, 1604); entries are committed and `continue` runs one more
request before `agent_settled`.

**kmet.** `:agent-settled` is a notification from `run-agent-turn`'s `finally`;
no pre-settle boundary, no outcome payload.

**Change.**

1. Emit an actionable `:agent-before-settle` before `:agent-settled`, carrying
   the run's outcome (`:completed`/`:error`/`:aborted`).
2. Run handlers, commit returned entries, honor `{:continue true}` by starting
   one more attempt (reuse F's boundary machinery).
3. Keep `:agent-settled` terminal and notification-only.

**Acceptance.** A handler returning `{:continue true}` produces one more
attempt before `:agent-settled`; entries persist in order; `outcome` reflects
the last attempt's stop reason.

### Phase 2 finding: the boundary payloads carry no entry ids or context preview

pi hands the boundaries the persisted entry ids (`messageEntryId`,
`toolResultEntryIds`) and a `BoundaryContextPreview` (`_buildBoundaryContext`:
the projected entries/messages, pending messages, `canContinue`). kmet's
boundaries carry `:pending-messages` and `:can-continue` — the two parts a
handler decides with — but no ids: K's `{:role :context-edit ...}` drafts
(pi's `{:type: "context_edit" ...}`) are usable today by reading ids from
`(:session ctx)` (`get-branch`/`get-entry`), but H's `turn_end` flush and
pi's own `_omitRecoveryAttempt` resolve them from the payload, so the ids +
preview stay open under K's remaining list.

---

## Phase 3 — post-run dispatch (D landed)

### D. Compaction dispatch belongs to the session layer

**Landed.** `run-agent-turn` runs `maybe-compact!` in the `:settled` and
`:error` arms, after the attempt's `:agent-end` and before
`continue-prompt?` — pi's `_handlePostAgentRun` order (retry → compaction →
queued-message continue → before-settle boundary). The result is not a
continuation (pi's `_checkCompaction` returns `hasQueuedMessages` for the
threshold case, so a `:threshold` compaction just settles), and an errored
attempt is checked too (no usable usage, so the estimate decides — pi:
sessions that hit persistent API errors can still compact). The guard is
`:compaction-blocked`: `compact-context!` sets it when the attempt aborts
(including an extension cancel) or fails, `maybe-compact!` stands down while
it is set, and `prepare-run!` clears it per prompt, so the unchanged context
is not retried within the same prompt. The Phase 1 status finding landed in
the same change, since D widens the settle window.

**Goal.** Run the automatic compaction check after a settled attempt, as pi
does, not only at the next prompt's pre-run check.

**pi.** Dispatched outside the low-level loop: before a prompt
(`_checkCompaction(lastAssistant, false)`, `agent-session.ts:2009`), per turn
(`_compactBeforeNextAssistantResponse`, 748, called at 877), and after the run
(`_handlePostAgentRun` → `_checkCompaction`, 1837; `_checkCompaction` 2900,
`skipAbortedCheck` 2902/2909; overflow `_runAutoCompaction` 2969/2997;
`compact()` 2717).

**kmet.** `maybe-compact!` (1432) from `prepare-run!` (1545) and `tools-phase!`
(1588); `compact-for-overflow!` (1469) in the retry branch. The per-turn check
already matches pi; only the post-run check is deferred to the next prompt.

**Change.**

1. Expose the post-run check to the caller: call it after `agent-end` and
   before the F/G settle boundary.
2. A `:threshold` compaction just settles (pi does not retry for threshold).
3. Place the call so D, E, and G follow pi's order in `_handlePostAgentRun`:
   retry → compaction → queued-message continue → before-settle boundary.

**Risks / prior art.** A first attempt called `maybe-compact!` after a settled
attempt and re-triggered forever when the compaction was aborted or failed
(context still over threshold → next run retries the same compaction), hanging
`test-loop-abort-compaction-keeps-run` (a `:compact-token-threshold` context
with a never-finishing summarization stub). pi's `skipAbortedCheck` does not
cover a successful run whose compaction was separately aborted. Land it only
with a guard that suppresses the check after an aborted/failed compaction in
the same prompt (or carry pi's `_overflowRecoveryAttempted`/`skipAbortedCheck`
state explicitly).

**Acceptance.** A run whose final assistant message crosses the threshold
emits `agent-end` → `compaction-start` → `compaction-end` in that order, with
no extra run; an aborted/failed compaction in the same prompt does not
re-trigger; event-order tests for both. Landed as
`test-loop-post-run-threshold-compaction` (order + no extra run) and
`test-loop-post-run-compaction-not-retriggered-after-failure` (a failed
pre-run compaction is not retried post-run); the aborted case extends
`test-loop-abort-compaction-keeps-run`.

---

## Phase 4 — context ingestion

### H. Pending custom-message queue + `:next-turn`

**Landed.** New agent-state queues `:pending-custom` and
`:pending-next-turn` (pi: `_pendingCustomMessages` /
`_pendingNextTurnMessages`). A custom message deferred while streaming is
appended once the turn's tool results are in: at every turn end
(`finish-turn!`), at the pre-settle boundary after its entries commit, and
by the prompt's settle flush (`run-agent-turn`'s `finally`); the append is
context-only, so it stays out of `:agent-end :messages`. The next-turn queue
is injected with the next prompt, after its user message and before the
before-agent-start messages. A message the loop consumes from a
steering/follow-up queue is persisted and appended there, as it joins the
context (pi: message_end persistence) — the interactive registry now queues
without appending, so every delivery mode appends exactly once (the busy
`{:trigger-turn true :deliver-as :follow-up}` send used to append immediately
*and* again on the drain). The boundary preview's `:pending-messages`
includes the deferred queue (pi: `_getPendingBoundaryMessages`) and
`:can-continue` counts it as runnable (pi: `pendingCustomContext`), so a
handler can ask for a continuation the boundary's flush will make possible.
An idle send
that triggers a run appends before the run starts, so it is not part of that
attempt's `:agent-end :messages` (pi's prompt carries it). The headless
fallback still appends immediately through the sinks — it has no queue of its
own.

**Goal.** A custom message sent mid-stream cannot land between a tool call and
its result, and `:next-turn` delivery is real.

**pi.** `sendCustomMessage` (`agent-session.ts:2246`) defers a non-triggering
streaming message into `_pendingCustomMessages` (`:384`, pushed 2278) —
*"Appending now would put the message between an assistant tool call and its
result … Defer to the end of the turn."* — flushed by
`_flushPendingCustomMessages` (2300) at `turn_end` (1165) and before settle
(1801). `:next-turn` goes to `_pendingNextTurnMessages` (`:382`), injected with
the next prompt (2041).

**kmet.** Interactive `:send-message!` (`modes/interactive/ui_registry.clj`)
calls `add-context-message!` unconditionally, before the deliver-as branch.
No pending-custom queue; `:next-turn` (advertised at `app/extensions.cljc:472`)
collapses into `follow-up!`.

**Change.**

1. Add a pending-custom queue, flushed at `turn_end` and at the settle flush
   (`run-agent-turn`'s `finally`).
2. Add the `:next-turn` queue injected with the next prompt.
3. Make the interactive registry append exactly once per delivery mode.
4. Fix the related double-append: for
   `{:trigger-turn true :deliver-as :follow-up}` while busy, the registry
   appends immediately *and* queues, and `run-attempt!`'s follow-up drain
   re-adds the map — the message lands in context twice. Only the headless
   path is tested today.

**Acceptance.** A custom message sent while a tool is executing is absent from
context until the turn's results are in; a `:follow-up` custom message appears
exactly once; `:next-turn` is injected with the following prompt.

### I. `prepareRequest` per-request checkpoint

**Landed.** A `:prepare-request` agent hook (pi: `config.prepareRequest`)
runs before every provider request — `run-attempt!`, after the queued
messages joined the context and before `call-llm`. It receives `{:context
:model :thinking :system}` and returns an update map with
`apply-next-turn-update!`'s keys; `:context` replaces the in-flight
conversation for this request and the rest of the attempt (pi:
`currentContext = requestUpdate.context`), in memory only — the session keeps
its own entries, so the projection is not persisted. pi's session-side
install also replaces the request context with the session projection; that
half waits for K: kmet keeps an errored or abandoned attempt session-only and
pi omits it from the projection with a context edit (`_omitRecoveryAttempt`),
so a blind rebuild would resurrect it.

**Goal.** Install the canonical context immediately before every provider
request, so a context mutation is reflected without a turn boundary.

**pi.** `prepareRequest` (`agent-loop.ts:219`) may return
`{context, model, thinkingLevel}`. `prepareNextTurn` (186) is the turn-boundary
counterpart.

**kmet.** No per-request checkpoint; `transform-context` and the `:context`
event transform messages, but the canonical install happens only at
`prepare-next-turn`/compaction.

**Change.** Add a per-request hook with the `apply-next-turn-update!` shape
(`:context`/`:model`/`:thinking`/`:system`), invoked just before each
`call-llm`.

**Acceptance.** A hook replacing `:context` is honored on the next request
with no turn boundary between.

### L. `context_with_system` event

**Landed.** `:context-with-system` fires after `:context` with the full
transcript (system prompt included) and its result is sent verbatim;
`:context` now sees the conversation only, with the leading system message
re-attached to a replacement (pi: `restoreSystemMessages`), so a pruning
handler cannot drop the prompt. A `:context-with-system` result that drops the
leading system message is reported as an extension warning and honored, as
pi does. Both phases run for every `llm/send-message` call, the compaction
summarization included: kmet's "each LLM call" contract, while pi routes
summarization outside `transformContext`.

**Goal.** Per-request system-message transformations over the full transcript.

**pi.** After `context` handlers run over the conversation only, after every
`context_with_system` handler runs over the full transcript (system messages
included) and the result is sent verbatim; dropping the leading system message
is an extension error.

**kmet.** One `:context` event over the outgoing messages (`call-llm` prepends
the system prompt; tools travel separately, so a filtering handler cannot drop
them); last non-nil `{:messages ...}` wins.

**Change.** Add `:context-with-system` after `:context`, over the full
transcript, with the leading-system-message validation.

**Acceptance.** A `:context-with-system` handler sees the system message; a
result that drops it is reported and honored (pi: `emitError` then the
handler's output); ordering is `:context` then `:context-with-system`.

---

## Phase 5 — session context model

### K. Append-only per-message context edits

**Goal.** Omit or rewrite one arbitrary message in future provider context
without touching raw history, usage, or UI history.

**pi.** `ContextEditEntry` / `appendContextEdit`: `replacement: null` omits one
message from future provider context, a content replacement swaps its text.

**kmet.** No per-message edit layer; compaction is the only append-only context
change and replaces the projection wholesale, so it cannot omit a single
message.

**Landed (core).** New `:context-edit` session entries —
`{:role :context-edit :target-id id :replacement nil | {:content ...}}` —
appended by `session/append-context-edit!`, which enforces pi's contract: the
replacement is nil or carries string/block content, the target must exist, be
on the active branch, and be a message entry contributing editable model
content (`:user`, `:assistant`, `:tool`, `:bash`, `:custom-message` — kmet's
`:tool`/`:bash` stand in for pi's toolResult), and a string replacement becomes
one text block. The projection is `session/project-context` /
`session/build-context-messages` (pi: `buildSessionProjection`): the latest
edit per target wins, a nil replacement drops the message, a replacement swaps
its content, and only the newest compaction contributes a summary message
(pi's `index > 0` rule — kmet's `context-entries` can retain an older
compaction whose id fell inside the newest retained range, which
`context-messages` alone kept projecting). Both context rebuilds now read the
single projection through `refresh-context-from-session!` (pi:
`_refreshFinalizedContext`). `kmet.app.loop/append-context-edit!` appends an
edit and refreshes the live context, and the actionable boundaries accept
`:context-edit` drafts in `:entries` — validated through the session API,
reported and skipped when invalid — rebuilding the live context once the batch
commits, so an extension can omit or rewrite a message mid-prompt. The
compaction measurement follows the projection when edits are present
(`compaction/projected-context-tokens`, pi:
`estimateProjectedContextTokens`): the usage-based measurement stands only
while its source message comes after the latest edit or compaction, otherwise
the projection is estimated. `:context-edit` entries render in the session
tree as pi does (`[context omit|replace: id]`).

**Remaining.** The three pieces that put the layer on the loop's own paths:

1. *Request-time canonical install* (pi: `_installAgentRequestProjection`):
   `prepare-request!` installs `projection.messages` as the request context
   before the `:prepare-request` hook runs, so an edit reaches the request even
   when no rebuild followed it. Deferred from I: kmet keeps an errored or
   abandoned attempt session-only, so a blind rebuild would resurrect it —
   hence (2) first.
2. *Recovery omission* (pi: `_omitRecoveryAttempt`): `_prepareRetry` and the
   overflow branch append a nil edit for the errored assistant message (and
   its tool results) before the retry, so the projection-based request never
   re-sends it. pi's comment: "Keep the failed attempt in raw history while
   durably omitting it from model projection."
3. *Boundary ids and context preview* — the Phase 2 finding: `:turn-end`
   carries `messageEntryId`/`toolResultEntryIds` (pi resolves them from
   `_entryIdsByMessage`) and both boundaries carry pi's
   `BoundaryContextPreview` (`contextEntries`/`contextMessages`/`llmMessages`);
   the shape itself is settled by the `context_entries`/`context_messages`
   projection, so this is payload plumbing, not new machinery.
4. *Projection-aware compaction input* (pi: `findProjectedCutPoint` +
   `getMessagesFromProjectedEntryForCompaction`): `compaction/prepare`
   summarizes the raw context entries, so an omitted message is still
   summarized, a rewritten one is summarized with its original text, and the
   compaction entry's reported `:tokens-before` counts the omitted messages.
   The threshold/overflow *measurement* already follows the projection
   (`projected-context-tokens`); only `prepare`'s input and its reported
   count do not.

**Acceptance.** A `:replacement nil` edit drops exactly that message from the
next provider context; the raw branch, usage totals, and replay are unchanged.
(The projection half is covered by `kmet.app.test-session`'s context-edit
tests, the loop half by `test-loop-append-context-edit-refreshes-the-context`
and `test-loop-turn-end-boundary-context-edit`; the remaining items land with
their own tests as in D.)

---

## Verification

- Iterate with `bb changed`, `bb test-changed`, `bb lint-changed`,
  `bb format-check-changed`. Full gates only on request.
- Prefer a fresh process for event-order tests (`bb -e`/scratch script) when
  namespace load order matters.
- Repro material from the original report (gitignored, under `target/`):
  `check_subvec*.bb` recompute the 300/62 counts from the real session file;
  `verify_regression.bb` disables the run-message accumulator in-process
  (`alter-var-root` on `record-run-message!`) and shows the pre-A payload
  failures. Real evidence: session
  `…2026-10-06T13-28-18-767Z_1a11166984d-d286621b.ednl`, compaction entry
  `2026-10-06T14:41:12.472Z`, `:tokens-before 166703`,
  `:first-kept-id 1a111a446c2-fa70b8b4`.

## Docs to update as items land

- `src/kmet/app/event_bus.clj` — A/B/E/F/G done (E reshaped the
  `:agent-start`/`:agent-end` descriptions; F/G added `:agent-before-settle`
  and the actionable `:turn-end`, plus `emit-boundary!`); D adds no events
  (the compaction payloads were already pi-shaped; `:agent-settled` now
  names the post-run compaction in its description); H widened the boundary
  `:pending-messages` to include deferred custom messages; L added
  `:context-with-system` and reshaped `:context`; K documented the
  `:context-edit` draft on the boundary `:entries`.
- `src/kmet/extension.md` — done with E/F/G (the `:agent-end` queueing
  paragraph, the two actionable boundaries, and the `send-user-message`
  note); K documented the `:context-edit` boundary draft and the
  `append-context-edit!` API.
- `src/kmet/development/pi-alignment.md` — A/B/C done and §7 moved here; the
  Appendix rows for the agent/turn/context boundaries point at this file and
  follow E/F/G/J. The compaction row (`session_before_compact` /
  `session_compact`) follows D, done: it now records the dispatch parity and
  the residual missing success `:session_compact` event; K added the
  `ContextEditEntry` / `appendContextEdit` row (landed core, remainder
  listed).
- This file — strike items as they land; remove it when the open list is empty.
