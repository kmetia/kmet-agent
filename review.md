# Review: `~/src/kmetia/kmet-agent`

Scope: whole tree (~205k LOC Clojure; `modules/{app,ai,libs,tui,tk,srce,loader,reakt,hikap}`,
`extensions/`, `tasks/`). Read for state/concurrency correctness, lifecycle, resource handling,
performance of the hot paths, and duplication — not style. Lint is clean (1 info), and the
codebase's own guardrails (subprocess arg lists, resolve-inside-try, jar extensions, no
`getPath` simulation) hold up.

**Status of each finding**

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| 1 | Stale run clears a new run's `running?` flag | high (concurrency) | **fixed** |
| 2 | LLM deadline timeout does not abort the request | high (token burn / garbled UI) | **fixed** |
| 3 | Shell spawn failure reported as success | high (agent misled) | **fixed** |
| 4 | `settle-grace!` has no ceiling | medium (hang) | **fixed** |
| 5 | `append-entry` mutates memory before the file write | medium (data loss) | **fixed** |
| 6 | MCP refresh keeps a stale connection after a definition change | medium | **fixed** |
| 7 | Compaction start is check-then-act | medium (race) | **fixed** |
| 8 | Pending-bash flush can duplicate entries | medium (data) | **fixed** |
| 9 | Extensions leak root-ctx services/plugins on unload | high (leak) | **fixed** |
| 10 | Unbounded error-body read | low/medium (memory) | **fixed** |
| 11 | Keyring secret in argv | low/medium (exposure) | verified by reading |
| 12 | `run_code` sandbox exposes `future`/`future-call` | — | **dropped** (not a finding) |
| 13 | `tui-resume!` bypasses the render-crash guard | high (silent hang) | verified by reading |
| 14+ | Structural: frame copying, render loop size, duplicated policies | low (maintainability) | verified by reading |

Findings 1, 2, 3, 6, 8 and 9 are fixed (one commit each, each with a regression test); 12 is dropped.
The rest stand as written.

---

## 1. A stale run can clear a new run's `running?` flag — **fixed**

`modules/app/src/kmet/app/loop/driver.clj:333,449-462`, `modules/app/src/kmet/app/session/lifecycle.clj:123-135`

`running?` on the handle is the same atom the engine exposes as `:turn-running?`
(`driver.clj:331`). `cancel-run!` aborts the loop's turn and then immediately does
`(reset! (:turn-running? engine) false)` — but the aborted run's future is still unwinding,
and it later fires `on-done`/`on-error` → `settle!` → `(reset! running? false)` again
(`driver.clj:333, 461-462`).

If a new submission wins the `compare-and-set!` at `driver.clj:449` in that window, the old
run's `settle!` clears the *new* run's flag. Consequences: two runs over the same agent, or a
live run reporting `:idle` so `:when-idle` (`driver.clj:~500`) no longer blocks callers.

Fix: ownership token. Capture `(Object.)` at claim time in an atom; `settle!` clears the flag
only when `(identical? @owner token)`. Alternatively keep `running?` meaning "a run exists"
and add a separate `:cancelling?`.

Test: start a run, cancel it, submit immediately, assert `:status` stays `:running` and the
first run's settle does not clear it.

**Fixed:** the flag holds the claiming run's token; `start!` claims it, the run releases it
through a new `:on-settle` callback (after the settle boundary, before `:agent-settled`), and
`release-turn!` is a CAS so a stale settle cannot clear a newer claim. `cancel-run!` no longer
drops it — a submission during the abort queues — with a bounded watchdog for a run that never
settles, and a session switch force-releases one it abandons.

## 2. LLM per-call deadline returns `:timeout` without aborting the request — **fixed**

`modules/app/src/kmet/app/loop.clj:614-621`, `modules/app/src/kmet/app/loop/completion.clj:150-176`

`(deref promise deadline :timeout)` gives up on the promise, but nothing signals the in-flight
HTTP/SSE call. The stream keeps running until the transport deadline; meanwhile the loop
classifies `:timeout`, may retry, and the orphan's `:on-text`/`:on-thinking` callbacks keep
firing — they are guarded only by `(realized? done-promise)` (`completion.clj:165`), which the
deref timeout does not realize. So the abandoned attempt can append into a fresh streaming
buffer and emit `:message-update` events after the retry started.

Note the thinking-loop guard (`completion.clj:176`) has the same guard shape: it delivers the
promise, which is what correctly stops the stream — that is the model to copy.

Fix: `call-llm` should return the call with a per-call abort (it already returns `:partials`);
on the deref timeout call it, then deliver `{:cancelled true :timeout true}` so the straggler
guard trips immediately and partials are preserved.

**Fixed:** `call-llm` returns an `:abort` that trips the call's own stream signal, and the
deadline path cuts the call before delivering the promise, so the transport stops reading and
the straggler guards trip; the compaction summarization's backstop got the same cut.

## 3. A shell that fails to spawn is reported as success — **fixed**

`modules/app/src/kmet/app/tools/bash_executor.clj:511-522`, `modules/app/src/kmet/app/tools/bash.clj:156`

`failure-result` (spawn failure, reader death, unexpected throw) returns `:exit-code nil`. The
classifier is `(or timed-out (and exit-code (not= exit-code 0)))`, so `nil` means "no error":
the model gets a successful tool result whose text merely *contains* `Error: …`. Branching
agents then proceed as if the command ran.

Fix: carry `:failed true` from `failure-result` and make `bash.clj` treat it as `:is-error`
(the status line already exists for that path).

**Fixed:** `failure-result` marks the call `:failed`, and `bash.clj` treats any missing exit
code as an error (pi's "Command terminated without an exit code"), with no status line when
the output already carries the failure.

## 4. `settle-grace!` can spin without a ceiling — **fixed**

`modules/app/src/kmet/app/tools/bash_executor.clj:496-509`

Grace polling waits for in-flight stream data: each growth of the tail buys another 100 ms,
with no total cap. A command that keeps writing after its "operations complete" (`yes`, a
spinner, a tailed build log) makes the grace loop unbounded, so the turn never settles.

Fix: cap the total grace (e.g. a single 1 s budget recomputed with `min`), keeping the
"growth buys another poll" behaviour inside it.

**Fixed:** the grace is one 1 s budget; each growth of the tail buys another 100 ms
slice, and the deadline is `min` of the extension and the cap. A fake-clock regression
test grows the tail forever and asserts the wait ends inside the budget.

## 5. `append-entry` mutates memory before the file write — **fixed**

`modules/app/src/kmet/app/session/store.clj:360-381`

`swap! :entries` and `reset! :leaf-id` happen *before* `kfs/append-line!`. A throw (disk full,
EIO) leaves the in-memory session ahead of disk; a torn write leaves a corrupt line for the
next boot. `replace-entries!` immediately below does it correctly (temp file + rename), so the
two paths disagree about the invariant.

Fix: `build-entry` needs only the current entries and leaf, so build first, append to the file,
*then* `swap!`/`reset!`. Return the entry only when the write succeeded.

**Fixed:** the append writes first (the line, or the whole file on the lazy-creation path) and
moves the session atom after; `persist-if-needed!` now takes the branch as it will be, so the
first assistant entry can still publish a file. A regression test makes both writes throw and
asserts memory, the leaf and the file are untouched.

## 6. MCP `/mcp refresh` keeps a stale connection after a definition change — **fixed**

`extensions/mcp-adapter/src/kmet/extensions/mcp_adapter/core.clj:61-79`

`rebuild-servers` preserves `:conn`/`:error`/`:lock`/resync state for every server whose
*name* survives a config reload. Editing a server's `:command`, `:url` or `:args` and refreshing
therefore keeps the old connection until a full restart.

Fix: preserve state only when `(= (:definition old) definition)`; otherwise start from a fresh
state map so the server reconnects with the new definition.

**Fixed:** `rebuild-server` keeps a server's live entry only while its definition is unchanged;
an edited one closes its old connection through `disconnect-server!` and starts fresh, so the
reload applies and the old failure's backoff window does not carry over.

## 7. Compaction start is check-then-act — **fixed**

`modules/app/src/kmet/app/compaction/stage.clj:552-571`

```clojure
(if @(:compacting? agent)
  false
  ... (reset! (:compacting? agent) true) ...)
```

A manual compaction and the automatic check on settle can both pass the test; the second then
resets `(:compaction-signal agent)` under the first, so an Escape aimed at one aborts the other
(or neither). The MCP adapter already does this correctly with a CAS (`state-for`); use the same
shape here: `(when (compare-and-set! (:compacting? agent) false true) …)`.

**Fixed:** the start is that CAS, taken before any compaction state is touched and
released by the same `finally` — a caller that loses returns `false` and leaves the
winner's signal alone. A regression test blocks a manual compaction between its claim
and its signal reset, then asserts the second caller loses and exactly one
`:compaction-start` fires.

## 8. Pending-bash flush can append the same entries twice — **fixed**

`modules/app/src/kmet/app/loop/state.clj:565-579`

`flush-pending-bash-messages!` appends each entry to the session *inside* the `doseq`, then
CASes the atom to `[]`. On CAS loss (a result recorded mid-flush) it `recur`s and re-reads an
atom that still holds the old batch plus the new entry — and appends the old batch a second
time. That duplicates messages in both the context and the persisted session.

Fix: take the snapshot once with `swap-vals!`, append that snapshot exactly once, and leave
anything added meanwhile queued for the next flush.

**Fixed:** each flush takes and clears its batch in one `swap-vals!`, so an entry recorded
mid-flush is appended once instead of the whole batch landing twice, and the session is read
once per flush so a swap cannot split a batch.

## 9. Extensions that follow the docs leak on unload — **fixed**

`modules/app/src/kmet/extension.md:255-280`, `modules/app/src/kmet/app/extensions/core.cljc:1160-1178,1766-1796`

The extension guide invites this:

```clojure
(:ctx api) ; the app root ctx the extension's plugin mounts under — pass it to
           ; kmet.srce.core for app-wide `provide!`/`plugin!`
```

and `create-extension-api` hands out `(host-ctx)` — the app **root**. But
`release-registrations!` only disposes `(:rows ext)` and `(:fiber ext)`; nothing disposes a
service or plugin the extension mounted directly on the root.

Reproduction (`/tmp/srce-root-leak-test.clj`, run against the repo):

```
before unload: app-svc = :from-ext owned-svc = :from-ext2 root children ctxs = 3
after unload:  app-svc = :from-ext owned-svc = nil       root children ctxs = 1
```

The sanctioned ownership pattern (`(srce/provide! fctx … {:in root})`) is released correctly;
root-mounting leaks its service and one child ctx, every reload. In a long-lived process that
is an unbounded accumulation of services, fibers and plugin records — and a reload can then see
two providers for one key.

Fix: hand each extension a per-extension child ctx as `:ctx` and dispose it during
`release-registrations!` (everything it mounts there unwinds for free); keep the root available
under a distinct key such as `:app-root`, documented as not released on unload. Keep the `{:in
root}` pattern for intended app-wide effects.

**Fixed:** `create-extension-api` now hands out the extension's own mount ctx as `:ctx`
(with the app root under `:app-root`), and `release-registrations!` disposes that ctx's subtree
(rows, the mount, and anything the body mounted through it) instead of only rows + fiber. The
documented app-wide form is `(srce/provide! (:ctx api) name value {:in (:app-root api)})`, whose
entry is an effect of the extension's fiber and unwinds with it.

## 10. Unbounded error-body read — **fixed**

`modules/libs/src/kmet/libs/http.cljc:221-231`

`error-body-text` `slurp`s an `InputStream` body whole and only then caps it at
`max-error-body-chars`. A proxy or provider returning a large body on error (an HTML error page,
a streaming endpoint misconfigured as JSON) is fully buffered to build a 2000-character message.

Fix: read at most `max+1` chars from the stream, then truncate.

**Fixed:** the stream is read through an `InputStreamReader` into a
`max-error-body-chars`-sized buffer and stops there; a string body still passes through to the
caller's cap. A regression test feeds a stream that throws past 5000 bytes and asserts the
message is the capped body — the old `slurp` read to the throw and produced nothing.

## 11. Keyring secret in the process argument list

`modules/libs/src/kmet/libs/mcp/auth.clj:416-432`

`keyring-write!` passes the payload to `security add-generic-password … -w <payload>` on macOS,
and embeds it in the PowerShell script text for the Windows credential manager. Either way the
credential is in an argv for the lifetime of the call, readable by any same-user process
(`ps`). The Linux branch already passes it on stdin to `secret-tool`, which is safe.

Fix: keep the Linux shape; for macOS/Windows use a 0600 temp file or sealed per-user store, or
document the exposure explicitly if the platforms offer no alternative.

## 12. `run_code` sandbox exposes thread-spawning primitives — **dropped**

`modules/app/src/kmet/app/tools/run_code.cljc:661-666`

`extra-core-vars` injects `future` and `future-call` into the sandbox core so scripts feel like
babashka. Each call creates a host thread outside any bound the tool applies — a script can
spawn unbounded threads and outlive the tool call's timeout/interrupt.

Dropped, not a finding: the sandbox is the full babashka script surface by design and already
resolves `java.lang.Thread`, `System`, `Runtime` and every `babashka.*` namespace (processes
included), so `future` is no escalation — the tool's own docstrings state that host-native code
is not interruptible.

## 13. `tui-resume!` bypasses the render-crash guard, so a crash hangs the app

`modules/tui/src/kmet/tui/core.clj:3079-3083, 3129-3142, 3145-3150, 3191`

`tui-start` launches the loop guarded: `(try (run-render-loop! tui) (catch Throwable t …write
kmet-render-crash.log… (tui-stop tui)))`. `tui-resume!` launches `(future (run-render-loop!
tui))` bare. `run-render-loop!`'s `finally` sets `:running?` false but never `:stopped?`, and
`tui-start`'s wait loop exits only on `:stopped?` — so after `$EDITOR` or Ctrl+Z, any exception
in the render body restores the terminal, writes no log, and the process sleeps forever.

Fix: one `launch-render-loop!` helper used by both entry points. Test: resume, make a component
throw, assert `:stopped?` and the crash log.

## 14. Structure and hot paths

- **Frame rebuild copies the whole document** (`modules/tui/src/kmet/tui/core.clj:225-226`):
  the equal-chunk path is `(reduce conj! acc (subvec prev-out a b))` — one `conj!` per line —
  while the docstring promises whole-chunk reuse. `assoc` the walked range, `conj` the appended
  tail, `subvec` a shrink; an unchanged frame can then return the same vector.
- **The flat document is rebuilt at every container level**: `Container.render` is
  `(protocols/render-all @children width)` with no cache (`modules/tui/src/kmet/tui/components/container.clj:9-11`),
  and the layout nests chat → chat-container → document-container → TUI. A `[width
  child-identity] → lines` cache makes unchanged containers return their previous vector.
  In the same path, quiet mode re-parses every user message's content with `skill-block/parse`
  on each pass — compute it once at insertion.
- **`run-render-loop!` is ~720 lines** with eight closures over the whole frame (ends at
  `core.clj:3114`), testable only through private-var binding or a fake terminal. Extract a
  `kmet.tui.frame` namespace with `build-frame`/`diff-frame`/`emit-frame` over an explicit
  frame-state map; leave terminal lifecycle, timers, locks and negotiation in the loop.
- **Frame state is ~17 independent atoms** updated field by field (`core.clj:284-311`,
  force-redraw at 2506-2516, commit at 2994-3016): one `:frame` atom holding a previous-frame
  map removes the two lists that must stay in sync.
- **Two session-deletion policies**: the TUI selector's default tries `trash` then unlink
  (`session_selector.clj:266-290, 938-939`), the session service deletes permanently
  (`session/listing.clj:360-364`, exposed as backend `:delete!`). Move the policy into the
  service and default the selector to it.
- **List navigation is hand-rolled in four selectors** (`session_selector.clj:837-847`,
  `fork_selector.clj:82-100`, `auth_selector.clj:147-168`, `resource_config.clj:751-776`) with
  small divergences; `filter_panel.clj` already owns the behaviour. Extract a shared
  `handle-list-navigation`.
- **Duplicated MCP reload**: `mcp start-server!` and `mcp/server-mcp-list.clj` carry the same
  path twice.
- **`(System/getenv "TERMUX_VERSION")` runs on every frame** (`core.clj:2563`) — hoist to a
  `def`; the environment cannot change in-process.
- **Dead code**: `center-offset` (`list_screen.clj:31`) has no callers.
- **The dispatch lock is process-global** (`core.clj:1450-1490`) while every other piece of TUI
  state is per-instance: create it in `create-tui` and read it from the record.

---

## Suggested order of work

1. ~~Small and testable, one commit each: 3, 7, 8, 6, 5.~~ all done.
2. Then the concurrency pair with regression tests: 1, 2 — 1 needs the decision in its note
   first, 2 is a small isolated commit.
3. ~~Then the lifecycle fix with a test asserting empty root after unload: 9.~~ done.
4. ~~Then robustness: 4, 5, 7, 10, 11, and the TUI guard (13)~~ — 4, 5, 7 and 10 done;
   11 and 13 remain, plus the TUI `!` path's nil exit code found while fixing 3.
5. Structural work (14) as its own series, frame extraction first.
