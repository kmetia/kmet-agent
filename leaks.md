# Memory leaks, and the subscription-weakness fix

Part 1 is the audit (what leaks and why). Part 2 is the detailed, reviewed
design for the fix: **weak subscriptions + explicit resources**. Pass 1 and
Pass 2 (Stages A-D) are implemented; the Stage C gate said C was required
(2.8) and the post-C re-run reclaims the dropped tree.

- Part 1: findings with file/line references and five reproducible probes
  (three from the audit, one from the review, one from the design review of
  Part 2).
- Part 2: principles, the deterministic finding fixes (Pass 1), the
  `kmet.libs.weak` helper and the `track!`/`reakt` integrations (Pass 2),
  mode exit, guards, tests, rollout stages, risks.

## Status & implementation checklist

- **Design:** reviewed and frozen. The rejected alternatives and the
  reasoning are recorded under "Review corrections" (Part 1); do not
  re-propose them without re-checking that section.
- **Implementation:** Pass 1 complete (all findings 1-8 and 10; 9 excluded —
  see the result note below). Pass 2 Stages A-D complete. This file is the
  plan of record.
- **Order — findings first.** Pass 1 fixes the audited findings 1-8 and 10
  deterministically (2.4, 2.5, and the key fix in 2.2). Finding 9 is
  unbounded-by-design and excluded as a non-issue. Pass 2 (Stages A-D) adds
  the weak-subscription hardening on top, for drop sites that appear later.
- **Gates:** iterate with `bb changed`, `bb test-changed`, `bb lint-changed`,
  `bb format-check-changed`; full gates only on request. New test namespaces
  register in `kmet.tasks.runner/all-namespaces`.
- **Stage C gate:** recorded as required (2.8); Stage C is implemented (see
  the Stage C result) and the post-C gate re-run reclaims the tree on both
  hosts.

### Stage 0 — design (done)

- [x] Audit + probes (Part 1, findings 1-10; 9 recorded as a non-issue)
- [x] Review pass + corrections (Part 1, "Review corrections")
- [x] Detailed design (Part 2)

### Pass 1 — findings first (deterministic; fixes 1-8 and 10, 9 excluded)

The audited findings are fixed before `kmet.libs.weak` exists. Nothing here
depends on the weak layer; F4's unique-key fix is the precondition for the
Stage-B weak registry, so it must land first. Finding 9 is a non-issue
(unbounded by design) and is out of scope.

| # | finding | fix |
|---|---------|-----|
| 1 | `/reload` drops widgets/header/editor undisposed | tolerant `protocols/dispose-component!`; route the five `ui_registry` sites through it (2.4.1-2.4.2) |
| 2 | mode tree + chat history survive `interactive/run` | `ChatHistoryComponent.dispose` (2.4.4); `teardown-mode!` disposes dock/overlays/pending, then `tui-clear`s the tree (2.5) |
| 3 | transient status indicator dropped undisposed | swap/clear dispose the dropped `:status-current` indicator (2.4.3) |
| 4 | `track!` keys collide (`identityHashCode`) | race-safe key in the cache atom's metadata; meta-only `remove-track-watches!` (2.2, "Key allocation") |
| 5 | `kmet config` screen never disposed | `try/finally` + screen `dispose` in `run-config` (2.4.5) |
| 6 | theme watcher keeps polling after exit | `theme-ctrl/shutdown!`: `on-theme-change nil` + `stop-theme-watcher!` (2.5) |
| 7 | `stty`/`chcp` children abandoned on timeout | `read-bounded` destroys the child when the 2s deref returns nil (2.4.6) |
| 8 | branch-summary driver never stops | stopped-check in the poll loop; `Throwable` guard on the deliver (2.4.6) |
| 9 | unbounded by design | **excluded — non-issue** |
| 10 | global roots keep the mode alive | `cs-ref` hoist, `teardown-mode!`, `extensions/clear-runtime!`, `reakt/discard-queued!` (2.5) |

- [x] F1: `protocols/dispose-component!` (cda delegates); widgets `:reset`,
      header `:set-header`/`:reset`, editor `:set-editor-component`/`:reset`
      dispose the value they drop; never `hdr`/`sp1`/`ed`. The header fix
      also assigns `custom-header-atom` (it was never stored, so neither
      replace nor reset could find the header — see 2.4.2).
- [x] F2: `(dispose [this] (chat-history-clear! this))` on
      `ChatHistoryComponent`; `teardown-mode!` disposes the tree
      (`dispose-mode-tree!`: pending bash, dock, overlays, `tui-clear`)
- [x] F3: dispose the transient indicator; the working indicator stays alive
- [x] F4: `tracker-key` reads/writes `::watch-key` in the cache atom's
      metadata; nil-safe, allocation-free in `remove-track-watches!`; the
      `resource_config` rows watch key moved off `System/identityHashCode`
      to a `gensym` (2.4.6)
- [x] F5: `run-config` `try/finally` + `(protocols/dispose screen)`
- [x] F6+F10: `teardown-mode!` (isolated steps): abort signals,
      `ui-reset!`, tree disposal, `clear-runtime!`,
      `theme-ctrl/shutdown!`, `reakt/discard-queued!`
- [x] F7: `terminal_jline/read-bounded` destroys the child when the 2s
      deref returns nil (raw ProcessBuilder child: `.destroy` /
      `.destroyForcibly`, since `destroy-tree` needs a process record)
- [x] F8: `session-admin/start-branch-summary-render-driver!` stops on the
      TUI's `:running?` flag; the summarization future catches `Throwable`
- [x] tests: one per finding (2.8); existing `watch-registry` counts stay
      exact on dispose paths (test helpers migrated to identity lookups)
- [x] no new test namespaces, so no `kmet.tasks.runner/all-namespaces`
      change needed
- [x] `bb test-changed` (12.1k assertions), `bb lint-changed` (0/0),
      `bb format-check-changed` green; the `^:slow` `read-bounded` test runs
      under `bb test-ext`.

**Pass 1 result.** All above landed. Two implementation notes and one known
unrelated flake:

- The header site had a second bug: `custom-header-atom` was written
  nowhere, so the old duck-typed probe could not have worked even for
  records. `:set-header` now stores the factory result (never `hdr`).
- `test-loop-branch-summary-failure-reports-the-cause` fails in roughly 1/3
  of full-suite runs with an auth-resolution error; it reproduces on the
  pre-change baseline in the same `test-interactive-ui` + `test-keybindings`
  + `test-loop` combination and passes in isolation. Pre-existing, unrelated
  to Pass 1.

### Pass 2 — weak-subscription hardening (Stages A-D)

Backstop for drop sites that only appear later (third-party extensions,
future code). Stage letters keep their names.

#### Stage A — `kmet.libs.weak`

- [x] `src/kmet/libs/weak.clj`: registry + queue, `register!`, `subject`,
      `payload`, `unregister!`, `sweep!`, `live-count`, `entry-count` (2.1)
- [x] `test/kmet/libs/test_weak.clj`: register/refresh, identical-subject ref
      reuse, throw on a different live subject, `unregister!` payload,
      `sweep!` (concurrent + queue drain), `on-dead` isolation
- [x] register the test ns in `kmet.tasks.runner/all-namespaces`
- [x] changed-file gates green

**Stage A result.** Helper tests pass on both hosts —
`bb test kmet.libs.test-weak` and `jolt test kmet.libs.test-weak`, 8 tests /
33 assertions each — and the changed-file gates are green. Portability
probes on both hosts (`target/probe_weak.clj`, `target/probe_weak_ns.clj`):
`WeakReference` + `ReferenceQueue`, GC clearing/enqueuing, the deterministic
`.clear`/`.enqueue` test path, transient maps and `swap-vals!` all work. Two
notes: the namespace needs a `(declare sweep!)` because `register!` calls it
from above its definition, and Jolt's `WeakHashMap` did not expunge a
collected key under `System/gc` — the design does not use it, so that table
row is corrected below. Naming decision (2.12): `kmet.libs.weak`.

#### Stage B — weak `track!` + sweep points

Precondition: Pass 1 landed (unique keys, deterministic disposal). This
stage turns the registry weak and adds the sweep; it carries no key or
disposal work.

- [x] key-only handler + weak registry entry + `on-dead` unwatch (2.2),
      registering before installing watches
- [x] `(weak/sweep!)` in `run-render-loop!` and after a non-empty
      `reakt/flush!`; `macros/sweep-dead-watches!`, `macros/live-watch-count`
- [x] migrate the registry to the weak entry shape; existing count tests
      stay valid on explicit-dispose paths
- [x] guard counters (`macros/tracked?`, `weak/live-count`/`entry-count`);
      the `--debug` survivor report is Stage D
- [x] `^:slow` regression: dropped tree counts return to baseline after
      GC + sweep (`kmet.tui.components.test-track`); 50-reload mid-session
      cycle flat (`kmet.app.test-interactive-ui`)
- [x] changed-file gates green

**Stage B result.** The `track!` registry is the weak registry: atom
handlers capture the watch key only and look the component up through
`weak/subject` when they fire, `track-render` refreshes the entry per pass
and unwatches dropped refs, and `remove-track-watches!` unregisters. The
render loop and `reakt/flush!` sweep; `macros/sweep-dead-watches!`,
`macros/live-watch-count` and `macros/tracked?` are the test/debug
accessors. The existing `watch-registry` count helpers migrated to
`live-watch-count`; the `tracked?` helpers migrated to `macros/tracked?`
(no registry shape is open-coded in tests anymore).

**Stage C gate (run in Stage B, recorded here).** Required, not defensive.
`target/gate_stage_c.clj` renders a `hiccup/root` `ComponentFn` tree with
nested fn components that read an external atom through
`reakt/tracked-deref` and close over a marker `Object`; after dropping the
tree, a bounded `System/gc` loop + `weak/sweep!` on **bb and Jolt** leaves
the marker reachable (and the tree's compiled Text components alive as weak
entries). The strong reakt dep watches root the dropped tree, so Stage C is
required. The probe measured retention with a `WeakReference` marker
because `reakt/live-reaction-count` arrives with Stage C.

#### Stage C — weak `reakt` (conditional)

- [x] **Gate test result recorded here** (see 2.8): **C is required** — the
tree survives GC + sweep on bb and Jolt.
- [x] weak subject = reaction; payload `{:rx-watch-key :watching}` (B1, B2)
- [x] key-only `dep-handler`; `update-watching!` single writer; `-dispose`
      via `weak/unregister!`; registration at the end of `make-reaction`
- [x] `flush!` sweep (drain-gated, landed in Stage B); `live-reaction-count`
- [x] liveness tests per creation shape (var, `with-let`, ComponentFn,
      cursor, dep graph, queued)
- [x] `^:slow` GC tests on bb **and** Jolt (weak refs over reify reactions)
- [x] perf measurement: dep-change overhead + frame time, within budget
- [x] changed-file gates green

**Stage C result.** Dep watches are weak. `make-reaction` registers the
reaction in `kmet.libs.weak` (`weak/register! rx-key r {:rx-watch-key watch-key
:watching []}`) once, after `(reset! self r)`; the dep handler captures the
registry key only and resolves the live reaction with `weak/subject` when a
dep fires (a collected subject no-ops); `update-watching!` is the single
writer for the dep set — it reads the payload, watches/unwatches the diff,
keeps the cell's `:watching` as an introspection mirror, and refreshes the
payload only when the set actually changed; `-dispose` unregisters through
`weak/unregister!` (returning the payload) and unwatches from it. `auto-run?`
moved into the cell so the handler closes over nothing but the key.
`reakt/live-reaction-count` is the per-kind count: `weak/live-count` gained an
optional payload predicate, and `macros/live-watch-count` filters to
`{:watched …}` payloads so the two counters stay distinct. The track! entry
payload's watch-set key is `:watched` (renamed from `:atoms` in this stage —
it holds every tracked ref, reactions included, so teardown and the sweep can
unwatch them; the cache map's `:atoms` remains the plain-ref half).
`macros/track-render`'s `:rx` cache entries are `[reaction value]` — a live
cache pins the reactions it validates, so a collected reaction can never keep
validating a stale cache (review B1); the hit check reads the cell through the
pinned reaction.

**Gate re-run (post-C).** `target/gate_stage_c.clj`, bb and Jolt: after the
tree is dropped and a bounded GC loop + `weak/sweep!` runs, the marker, the
root record and the reaction are all collected; `live-reaction-count` and the
live weak-entry count return to 0. Pre-C the same probe left the marker and
the compiled Text components alive (2.8).

**Perf (bb, headless; before = pre-C HEAD; min-of-3 means).** Frame bench:
selection change on the strings frame 0.031 → 0.032 ms, on the elements frame
0.288 → 0.281 ms. Transcript bench: 2400 messages, streaming 2.9 → 2.7 ms
(record tree) and 2.8 → 3.0 ms (DSL container), idle 3.3 → 2.7 and 2.7 → 2.6
ms; the content-rebuilt worst case 108 → 111 ms. Every delta is within run
noise (≤ ~5% either way); the handler's registry lookup adds no measurable
dep-change overhead at frame scale.

**Tests.** Fast: registration lifecycle (a dep write enqueues through the
registry, dispose unwatches), held-shape sweep liveness (var, cursor, derive
parent), cache-pin structure, per-kind counters, `weak/live-count` predicate.
`^:slow`, bb + Jolt: a dropped reaction is collected and the sweep unsubscribes
its dep watch; a queued reaction is held until `flush!`; a cache-pinned
reaction survives GC until the cache is cleared. Each GC scenario runs wholly
inside one helper on purpose: a deftest body that evaluates any intermediate
(the reaction, the flush) roots it through the bb/SCI frame — see
`test-utils/await-collected`.

#### Stage D — hardening & docs

- [x] optional hardening: `settings_list/open-submenu!` disposes an
      existing submenu before replacing (unreachable today)
- [x] `--debug` exit report including `timers/scheduled` (I7)
- [x] `src/kmet/tui/tui.md` §5.1 + §3.1/§12; `src/kmet/extension.md`;
      `src/kmet/README.md` (`kmet.libs.weak` in the libs layer)
- [x] check off Pass 1 and Stages A-C here; record the Stage C decision

**Stage D result.** `settings_list/open-submenu!` disposes a submenu it
replaces (no keymap path reaches it today; a programmatic open cannot orphan
a live submenu's track! watches). The `--debug` exit path, after
`teardown-mode!`, logs one `leaks:` line naming live track! components, live
reactions and armed timers (`kmet.modes.interactive/survivor-report`); it
fires on any survivor of the three — a timer-only leak never trips either
counter — and the logged line is exception-isolated, so the exit path never
throws. Docs:
tui.md §5.1 states the two-layer ownership rule — deterministic dispose plus
the weak backstop, and what the backstop cannot release — and the
temp-component bullet no longer claims the registry strongly retains; §3.1
documents reaction liveness and the counters; §11 lists the weak-registry
counters and the exit report; §12 records the `^:slow` GC-test discipline
(helper-scoped scenarios, the bb/SCI frame caveat). extension.md tells
authors the backstop exists but does not replace `dispose`;
`src/kmet/README.md` places `kmet.libs.weak` in the libs layer (the
`java.lang.ref` exception) and corrects the libs rule — sibling-lib
composition is allowed and enforced by name (never non-libs `kmet.*`).

Not taken from 2.7: the optional `KMET_DEBUG_LEAKS=1` periodic render-loop
line, and the suggested `with-leak-check` test helper (tests snapshot the
same counters with explicit baselines). They remain available if a
long-session diagnosis asks for them.

---

# Part 1 — Audit findings

A review of component/resource lifetimes in kmet: is `dispose` always called,
and what else retains memory or threads longer than its owner?

Scope: `src/kmet/tui/**`, `src/kmet/app/**`, `src/kmet/modes/**`,
`src/kmet/libs/**`, and the shipped extensions. The audit targets
process-lifetime behavior (long sessions, `/reload`, embedded hosts, tests),
not a single-shot CLI run whose `System/exit` hides teardown gaps.

Method: read the lifecycle owners (`tui-stop`, `hiccup` ownership, `track!`,
dock/overlay, chat history, timers, futures/watches) and probed the running
code with three audit probes (a fourth, from the review, is in the appendix).
Probes confirmed the two retention mechanisms below; the rest are code-level
findings with file/line references.

**Verdict:** `dispose` is not always called, and — more importantly —
dropping the last owner's reference is not enough even when it is. The
`track!` registry and `reakt`'s dep watches keep dropped components alive
mid-session, and several process-global slots keep the whole mode alive after
it ends.

## Verified facts first

- **`track!` strongly retains components process-wide.**
  `kmet.tui.macros/watch-registry` (`src/kmet/tui/macros.clj:51`) holds
  `{:component … :atoms …}` for every live `track!` scope. Only
  `remove-track-watches!` (`macros.clj:212`), called from the
  `defcomponent`-generated `dispose`, removes an entry. Dropping the last
  local/atom reference to a component is **not** enough to release it.
- **`reakt` dep watches strongly retain reactions (and their subtrees).**
  Each watched dep atom holds `dep-handler` (`src/kmet/libs/reakt.clj:345`),
  which closes over the reaction's `cell` and `self`; the cell's body closure
  reaches the component's state, and the cached value reaches its rendered
  children. A dropped `ComponentFn` tree is therefore rooted even if its own
  `track!` subscriptions are removed.
- **A cell does not reference its reaction (probe 5).** `track-render`'s
  cache stores `{cell → value}` (`macros.clj:138-145`) and `rx-unchanged?`
  accepts a settled cell, including `:disposed` (`macros.clj:71-84`). Once
  dep watches are weak, a deref-only reaction can be collected while the
  cache still holds its cell: the cache then hits forever and nothing is
  left to invalidate it. Stage C must make the cache hold the reaction (2.3).
- **Probe 1:** a `hiccup/root` tree rendered once but never disposed keeps
  `watch-registry` at 1 entry after `tui-stop`; disposing it drops to 0.
- **Probe 2:** a chat history with 50 user + 50 assistant messages rendered →
  **150 live subscriptions retained**. Simulating today's shutdown (nothing
  disposes) still shows 150; `chat-history-clear!` takes it to 0. Each
  retained component also holds its atoms and rendered-line caches.
- **Probe 3:** extension widget values (both the hiccup-tree and the
  factory-map form) are `CustomDialogAdapter` / host **records with no
  `:dispose` key**, so `(:dispose w)` is `nil` for them.

## 1. Real in-session leak — `/reload` (extension UI reset) drops widgets, header, and custom editor without disposing

`src/kmet/modes/interactive/ui_registry.clj`:

- **Widgets, `:reset`, lines 771-776.** The loop does
  `(when-let [dispose (:dispose w)] (try (dispose) …))` and then
  `(reset! widgets-above-atom {})`. For every widget that is a record
  (`make-extension-widget-component` compiles trees / adapts duck-typed maps
  into records — probe 3), `(:dispose w)` is `nil`, so nothing is disposed and
  the map swap drops the last reference. Consequences:
  - their `track!` watches stay in the global registry forever;
  - their reactions / `with-let` cleanups never unwind;
  - compiled tree content may hold the extension's SCI closures, so the
    extension's isolated context stays reachable after
    `extensions/unload-extension!` (`src/kmet/app/extensions.cljc:1066`) —
    which explicitly promises "namespaces and jars become unreachable, nothing
    global is touched".
  - The **`ui-set-widget` replace/remove path is correct**: it goes through
    `dispose-dialog-component!` (`ui_registry.clj:73-77`, used at ~331), which
    handles records/reifies/maps. Only the reset path diverges.
- **Custom header, lines 354-358 and 783-791.** Same duck-typed `(:dispose
  …)`; the replacement additionally uses `container/container-clear`
  (`src/kmet/tui/components/container.clj:57`), which by design does **not**
  dispose. A header record returned by an `ui-set-header` factory is leaked on
  every replace and every `/reload`. (When the factory is `nil` the default
  `hdr`/`sp1` are deliberately re-added — leave those alone.)
- **Custom editor, lines 410-427 and 803-808.** `:set-editor-component` swaps
  `current-editor-atom`, and `/reload` resets it to the default editor, but the
  previous custom editor is never disposed. The dock is cleared
  (`dock/clear!`), yet the editor is not a dock entry — the dock area splices
  it foreign — so nothing else owns it.

## 2. Real shutdown leak — the mode's whole component tree survives `interactive/run`

`tui-stop` (`src/kmet/tui/core.clj:2024-2045`) disposes nothing: no
`tui-clear`, no child disposal, no overlay disposal. `tui-clear`
(`core.clj:243-248`) does dispose direct children, but nothing in the app calls
it. `interactive/run`'s teardown (`src/kmet/modes/interactive.clj:142-155`)
kills child processes and clears the extension registry, but never disposes the
tree — despite `src/kmet/tui/tui.md` §5.1 stating "Callers invoke it
unconditionally when a component leaves — overlay close, reconcile removal,
shutdown."

Consequences:

- Every track!-registered component stays in `watch-registry` forever after
  the mode ends: every message component, the chat info banner, header and
  loaded-resources `ExpandableText`, footer, pending-messages, selector lists.
  Probe 2 quantifies the transcript case (150 subscriptions for 100 messages),
  including each component's atoms and rendered-line caches.
- `ChatHistoryComponent` has **no `dispose`** (`src/kmet/app/ui/chat_history.clj:113`),
  so even `tui-clear` would not cascade into the messages. Only the explicit
  `chat-history-clear!` (`chat_history.clj:744`) does, and that is called on
  `/new`, `/clear` and rebuild — not at exit.
- Dock panels and `ui-custom` overlays open at quit are foreign spliced records:
  `tui-clear` never disposes them, and `extensions/clear-ui-registry!`
  (`extensions.cljc:379`) just resets the registry map, losing the only
  reference to an open dialog component.
- The CLI is shielded only by `System/exit 0` in `kmet.core/-main`. `run` is a
  documented library entry point and the existing test
  `run-clears-the-extension-ui-registry-on-mode-end`
  (`test/kmet/modes/test_interactive.clj:1164`) explicitly says "the CLI exits
  right after; embedded hosts and tests keep running". Each repeat
  `interactive/run` in-process therefore accumulates the previous run's whole
  UI history.

## 3. Latent — transient status indicators are dropped, not disposed

`status/show-status-indicator!` (`src/kmet/modes/interactive/status.clj:106-119`)
replaces `:status-current` after cancelling the driver, but never disposes the
previous `:indicator`, although its docstring says "pi: showStatusIndicator —
disposes the active indicator." Today's `Retry`/`Compaction`/`BranchSummary`
indicators and the share `Spinner` hold only atoms and no timers/`track!`, so
nothing leaks yet — the first indicator that uses `track!`, a timer or child
components will leak on every swap.

## 4. Latent — `track!` watch keys can collide

`macros/tracker-key` (`src/kmet/tui/macros.clj:38`) is

```clojure
(keyword (str "track!" (System/identityHashCode component)))
```

`identityHashCode` is a 32-bit identity hash, not a unique id. With N live
track!'d components the birthday probability of at least one collision is
≈ N²/2³³ (~1% at 8k components, ~10% at 27k; a few thousand messages is
already in the thousands of components). On a collision:

- `reakt/watch-ref` replaces the other component's watch on a plain atom (same
  keyword key);
- `swap! watch-registry assoc` clobbers the other entry;
- the loser's watches are then never removed on dispose (permanent retention
  plus zombie invalidations), and invalidations can reach the wrong component.

## 5. Minor — `kmet config` TUI never disposes its screen

`src/kmet/package_manager.clj:254-255` calls `tui-start`/`tui-stop` and
returns without disposing the screen. The screen's `dispose`
(`src/kmet/app/ui/resource_config.clj:706-710`) is the only thing that runs the
`remove-watch` cancel returned by `watch-terminal-rows!`
(`resource_config.clj:720-737`, stored at `:774`). The TUI and size-ref are
otherwise unreferenced, so the cycle is collectable and this is hygiene rather
than a live leak — but the cancel path is dead code in practice, and any reuse
of the TUI would retain the screen.

## 6. Minor — theme-file watcher never stopped at shutdown

`theme.clj:978-1013` starts a 1 s polling `future` (held in the global
`theme-watcher` atom); `stop-theme-watcher!` (`theme.clj:972`) is only called
when switching to a theme instance or starting a new watcher. Nothing in
`interactive/run` stops it, so an embedded host keeps polling forever and the
theme registry/dir stays reachable. The CLI only survives because `-main`
calls `System/exit`.

## 7. Minor — abandoned `stty`/`chcp` children on timeout

`src/kmet/tui/terminal_jline.clj` `run-stty` (line 13) and `run-chcp`
(line 45): on `(deref (future (slurp …)) 2000 nil)` timing out, the child is
never destroyed, and the future thread stays blocked in `slurp` until the child
exits. A hung `stty`/`chcp` therefore leaks both a process and a thread despite
the "bounded read" intent.

## 8. Minor — branch-summary frame driver has no stop condition

`src/kmet/modes/interactive/session_admin.clj:701-703` runs
`(while (not (realized? done)) (Thread/sleep 100) (tui-request-render …))`.
The driver only exits when `done` is delivered. The summarization future
catches `Exception`, not `Error`, and nothing checks the TUI state, so an
`Error` (or a mode stop without delivery) leaves a 100 ms poller running
forever.

## 9. Unbounded by design — non-issue (excluded from the fix plan)

Recorded for completeness only: this is intended policy, not a leak. Finding
9 is out of scope for Part 2 — a long session is expected to grow, and `/new`
or a compaction policy is the answer.

- `Session` `:entries` (`src/kmet/app/session.clj:150`) and the chat history's
  `messages-atom` grow for the whole session. Compaction trims only the
  context projection, while transcript components keep their rendered-line
  caches. A very long session without `/new` grows linearly.
- `theme/themes` (`src/kmet/tui/theme.clj:827`) keeps custom theme entries by
  name after a package is removed.
- `jars-cache` (`extensions/context.cljc`), `trigger-spec-cache`
  (`tui/components/editor.clj:378`), `normalized-id-cache` (`tui/keys.clj:131`)
  and the autocomplete snapshot cache are bounded in practice (file cache is
  capped at 8 entries).

## 10. Process-global roots survive mode exit

Even with every component disposed, three global slots keep the mode reachable
after `interactive/run` returns. They are why a weak layer alone cannot
reclaim a stopped mode, and why the exit path must clear them.

- **Extension runtime slots.** `extensions/session-atom`,
  `context-sink-atom`, `entry-sink-atom` (`extensions.cljc:174-176`, setters
  `:559-562`). `interactive/run`'s `finally` only calls
  `clear-ui-registry!` (`interactive.clj:153-155`); the sinks still hold the
  session and closures over `@(:agent-state cs)` / `cs`. The test helper
  `clear-installed-context!` resets them; production teardown does not.
- **Theme-change callback.** `theme/theme-change-callback`
  (`theme.clj:951`, `on-theme-change` at `:1024-1028`) holds
  `#(notify-changed! ctrl)`; the controller captures `:ui` (the TUI) plus
  `:show-error`/`:on-changed` closures over the TUI/footer
  (`layout.clj:725-737`). Nothing clears it.
- **reakt batch queue.** `tui-stop` (`core.clj:2034-2038`) clears the frame and
  enqueue hooks but not `reakt/queue` (`reakt.clj:162`). A reaction enqueued
  at stop is never flushed or purged, so it and its closure survive.
  `-dispose` purges queued entries (`reakt.clj:519-525`), but only for
  reactions that get disposed.

## Review corrections (design precedents)

Recorded so the fix does not re-propose them:

- **`hiccup/adopt` (stamping a foreign record) does not work.**
  `parse-node` classifies every record as `::record` with `mkey` = the record,
  regardless of `:dsl/meta` (`hiccup.clj:784`); only `itemize-prev` on raw
  container-lens children sees the stamp. A body-spliced stamped record never
  matches its previous item and its item's `owned` flag stays false — probe:
  a widget spliced from a fn body shows `:constructs 1, :disposals 0` across
  passes, i.e. never owned and silently dropped on replacement. Making adopt
  real needs reconciler changes or a prop-change-aware wrapper; dropped from
  the plan.
- **A weak `reakt` alone does not make `track-render` caches safe**: a cell
  does not reference its reaction (probe 5), so a cell-keyed cache can keep
  validating a collected reaction forever (a stale render, past even the
  `on-dead` unwatch). The cache must hold the reaction; the fix is in 2.3,
  not an alternative to the weak layer.
- **A weak `track!` registry alone does not release leaked trees/panels**: the
  `reakt` dep-handler chain (fact 2 above) still roots them. Weak `reakt` is
  what makes "forgot dispose" non-fatal for ComponentFn-shaped leaks.
- **`tui-start` auto-teardown is unsafe and behavior-changing**: its joins are
  `(deref f 3000 nil)` (`core.clj:2941,2944`), the incomplete-flush timer is
  not cleared on stop, and disposing in the primitive would forbid restart.
  Teardown belongs to the two app owners instead.
- **`container-clear` semantics change is not a drive-by fix**: three call
  sites are "move" cases that re-add what they cleared
  (`session_admin.clj:472` re-adds `pm`; `ui_registry.clj:360,787` re-add
  `hdr`/`sp1`). Disposing-on-clear would kill live components; a move verb
  would be needed first. Dropped from the plan.

## What is already sound

Verified while auditing — no action needed:

- `timers/cancel-all!` on stop, plus per-component `timers/cancel!` in
  `dispose` for tool-execution, bash-execution, scroll-view, session-selector,
  alt-screen-flash.
- `kmet.libs.reakt`: disposed reactions are purged from the batch queue,
  dep watches are unwatched, last-watcher auto-dispose for manual reactions.
- `container-replace-children!` disposal; `hiccup` reconcile ownership
  (`:dsl/meta`), ref clearing, and `retire-item!` disposal; foreign records
  deliberately not disposed.
- Chat history: `chat-history-remove-streaming-placeholder!` disposes removed
  placeholder/status lines; info-banner replacement disposes the previous
  banner; `chat-history-clear!`/`chat-history-rebuild!` dispose messages.
- Dock/overlay: leave-before-dispose invariant (`dock/dispose!`), membership
  removal, focus-guard watches, borrowed entries never disposed; `/new` and
  `/reload` dispose pending bash components and clear the pending-messages
  container correctly.
- `bash_executor`: signal/timeout pollers exit via the shared `done` flag,
  pids are untracked on every exit path, pipe draining is bounded.
- `libs.http`: client cache keyed by `[proxy mode]` (small), curl temp files
  deleted, cancel-watch threads are daemons that exit with the process.
- `tui.core`: negotiation/terminal-response/OSC-11 timers are generation- or
  timeout-guarded and short-lived; input buffers (`paste-burst`, kitty
  printable) are bounded; the reakt enqueue and frame hooks are cleared on
  stop.
- Extension load/unload tracks deregistration fns (`extensions.cljc:627-630`,
  `unload-extension!`) so event listeners, commands, renderers, flags and
  provider hooks are removed on unload; shipped MCP/LSP/review/clojure
  extensions implement `shutdown` and kill their processes/servers.

---

# Part 2 — Systemic fix design: weak subscriptions, explicit resources

## 2.0 Principle and layers

Target invariant:

> **No subscription keeps its subscriber alive. Every non-memory resource has
> exactly one owner that disposes it.**

Two layers:

| | Layer 1 — automatic (memory, mid-session) | Layer 2 — deterministic (resources) |
|---|---|---|
| mechanism | weak subscription registry + sweep | explicit `dispose`; mode-exit root clearing |
| memory reclaimed | yes (GC timing) | yes, immediately |
| `with-let` cleanups | **no** — GC cannot run code | yes |
| timers / processes / files | **no** — the timer registry roots its target | yes |
| third-party / future drop sites | yes | only where they dispose |

Vocabulary:

- **subject** — a component (track! layer) or a reaction (reakt layer).
- **subscription** — an atom watch installed on a dep on the subject's behalf.
- **registry entry** — `{key → {:ref WeakReference :payload … :on-dead f}}`.
  The payload holds exactly what unsubscribe needs (watched refs, watch key);
  it must never reference the subject.
- **sweep** — claim entries whose subject was collected, run their `on-dead`
  (unsubscribe), drain the queue.

Why this is the systemic answer: it does not require finding the drop site.
Any component/reaction that becomes unreachable — audited path, future path,
or third-party extension — stops being a root, and its subscriptions are
removed on the next sweep. Deterministic `dispose` remains only where GC
cannot act (resources, cleanup bodies).

## 2.1 `kmet.libs.weak` — the helper

A new self-contained `kmet.libs` namespace; the **only** namespace allowed to
touch `java.lang.ref` (the platform-dependency exception, like the terminal
backends). Used by `kmet.tui.macros` and `kmet.libs.reakt`.

Portability was verified before designing this:

| primitive | bb | Jolt |
|---|---|---|
| `java.lang.ref.WeakReference` | works | works |
| `java.lang.ref.ReferenceQueue` | works | works |
| `java.util.WeakHashMap` | works | not used — Jolt's did not expunge a collected key in the Stage A probe |
| `java.lang.ref.Cleaner` | works | **unavailable** (must declare `:jolt/provides`) |
| `alter-meta!` on an atom | works | works |

→ the design must be queue/sweep-based, not `Cleaner`-based.

### Shape

```clojure
(defonce ^:private registry (atom {}))
;; key -> {:ref    (java.lang.ref.WeakReference. subject queue)
;;         :payload map                 ; unsubscribe data; NEVER the subject
;;         :on-dead (fn [key payload])} ; unsubscribe + cleanup
(defonce ^:private queue (java.lang.ref.ReferenceQueue.))
```

### API

```clojure
(defn register!
  "Create or refresh KEY's entry for SUBJECT. PAYLOAD is everything the sweep
   needs to unsubscribe (watched refs, watch keys); it must not reference
   SUBJECT. ON-DEAD receives (key payload) after the entry is removed and runs
   exception-isolated, outside the registry swap.

   Reuses the existing WeakReference while SUBJECT is identical (a streaming
   render pass would otherwise allocate one ref per pass). Opportunistically
   sweeps when the queue signals a death — sweep! short-circuits on an empty
   queue, so this is one .poll per registration.

   Throws when KEY is already held by a DIFFERENT live subject: that means two
   components share a cache atom (track!) or two reactions share a registry key,
   which would silently steal each other's watches. Loud beats corrupt."
  [key subject payload on-dead] ...)

(defn subject  "The live subject for KEY, or nil." [key] ...)
(defn payload  "KEY's payload, or nil (dead/unregistered)." [key] ...)

(defn unregister!
  "Remove KEY's entry and return its payload. ON-DEAD does NOT run — the
   caller is disposing deterministically and unsubscribes itself. Idempotent."
  [key] ...)

(defn sweep!
  "Drain the queue; when anything was enqueued, claim every entry whose ref is
   cleared (one pure swap-vals! so concurrent sweeps cannot double-run
   on-dead), run the claimed on-dead fns, and return the count. Refs that were
   unregistered between clear and sweep are simply dropped by the drain.
   Safe from any thread; cheap when nothing died."
  [] ...)

(defn live-count  "Entries whose subject is still alive (tests/guards)." [] ...)
(defn entry-count "All entries, including dead-until-swept." [] ...)
```

### Sweep outline

```clojure
(defn sweep! []
  (let [drained (loop [n 0] (if (.poll queue) (recur (inc n)) n))]
    (if (zero? drained)
      0
      (let [[old _] (swap-vals! registry
                                (fn [reg]
                                  (persistent!
                                   (reduce-kv (fn [m k e]
                                                (if (nil? (.get (:ref e))) m (assoc! m k e)))
                                              (transient {}) reg))))]
        (doseq [[k e] old :when (nil? (.get (:ref e)))]
          (try ((:on-dead e) k (:payload e))
               (catch Throwable t
                 (binding [*out* *err*]
                   (println "kmet.libs.weak on-dead error:" (ex-message t))))))
        (count (filter (fn [[_ e]] (nil? (.get (:ref e)))) old))))))
```

Notes:

- The `.poll` drain both triggers the scan and removes queue garbage from
  entries that were unregistered while their ref was pending.
- `swap-vals!` (used elsewhere in the codebase, e.g. `tool_execution`) keeps
  the claim pure; a concurrent `register!` for a dead key cannot happen
  (keys are never reused — see the key schemes below).
- Implementation (Stage A): `sweep!` claims `old` minus `new` — exactly the
  entries its swap removed. Re-checking `old` for cleared refs after the
  swap can also claim an entry that cleared in between, which the next
  sweep would then claim again (a second `on-dead`).
- `on-dead` runs on the caller's thread. Callers keep it cheap and
  non-blocking: `unwatch-ref` per dep. Never `flush!`, never render.
- `live-count`/`entry-count` are O(entries); tests and debug only.

## 2.2 `track!` integration

Two independent pieces: the unique-key fix (Pass 1, finding 4) and the weak
registry (Pass 2, Stage B).

### Key allocation (fixes finding 4 — Pass 1)

This piece is independent of the weak layer and lands in Pass 1; the per-pass
flow below keeps the strong registry until Stage B. Replace
`System/identityHashCode` with a unique key stored in the component's cache
atom metadata (`:cache-atom`, or legacy `:cache`; both are atoms and both are
per-component by construction):

```clojure
(defn- tracker-key
  "The component's stable watch key, allocated once in its cache atom's
   metadata. The cache atom is the component's one per-instance cell, so the
   key dies with the component and can never collide with another live
   component's key. A component must own its cache atom; in Stage B
   weak/register! throws on the second live user (shared-atom bug)."
  [component]
  (when-let [a (component-cache-atom component)]
    (or (::watch-key (meta a))
        (let [k (keyword (str (gensym "track!")))]
          ;; preserving fn: atomic and convergent, unlike a plain assoc (a
          ;; later allocator must not overwrite an earlier reader's key)
          (alter-meta! a (fn [m] (if (::watch-key m) m (assoc m ::watch-key k))))
          (::watch-key (meta a))))))
```

`defcomponent`-generated `dispose` calls `remove-track-watches!` on every
component, including ones with no cache atom (Container, Spacer, …): the new
lookup must no-op on nil rather than allocate (finding-4 regression guard).

### Per-pass flow (miss path of `track-render`)

Current shape: handler closes over `component`, installs watches under the
identity-hash key, records `{component atoms}` (`macros.clj:128-181`). New
shape:

```clojure
(let [watch-key (tracker-key component)          ; nil for a cache-less record
      _ (when-not watch-key (throw …))            ; track! requires a cache atom
      handler (let [k watch-key]                 ; captures the KEY only
                (fn [_ _ old new]
                  (when-not (or (identical? old new) (= old new))
                    (when-let [c (weak/subject k)]   ; dead → no-op
                      (invalidate-cache c)))))
      atoms (set (keys tracked-map))
      prev-atoms (:atoms (weak/payload watch-key))]
  ;; register FIRST: a throw here (a shared cache atom) must not leave
  ;; watches installed under a key remove-track-watches! cannot find
  (weak/register! watch-key component {:atoms atoms}
                  (fn [k {:keys [atoms]}]            ; on-dead: unsubscribe
                    (doseq [a atoms] (reakt/unwatch-ref a k))))
  (doseq [a atoms] (reakt/watch-ref a watch-key handler))
  (doseq [a prev-atoms] (when-not (contains? atoms a)
                          (reakt/unwatch-ref a watch-key)))
  …)
```

Properties:

- the atom handler no longer captures the component, so it cannot root it;
- a dead component's invalidation is a no-op (subject nil);
- the set of subscriptions is exactly `atoms`, updated per pass (dropped refs
  are unwatched immediately, dead refs at the sweep);
- `remove-track-watches!` becomes:

```clojure
(defn remove-track-watches!
  "Deterministic teardown: unregister and unsubscribe. Idempotent; a no-op for
   components that never ran track! or already died."
  [component]
  (when-some [k (existing-tracker-key component)]      ; meta read only
    (when-some [{:keys [atoms]} (weak/unregister! k)]
      (doseq [a atoms] (reakt/unwatch-ref a k))))
  nil)
```

### Sweep points and counters

- `kmet.tui.core/run-render-loop!`: `(weak/sweep!)` next to `(timers/pump!)`
  (`core.clj:2300`), before the render gate — runs once per wake, including
  idle heartbeats (≤100 ms).
- `reakt/flush!`: after a non-empty drain (`reakt.clj:224`), so headless
  consumers that never start a TUI still sweep.
- Public for tests/debug: `macros/sweep-dead-watches!` (thin wrapper),
  `macros/live-watch-count`, and `hiccup`/`weak` counters as needed.

Migration: the registry map's shape changes from
`{key {:component … :atoms …}}` to the weak entry shape (the strong
`watch-registry` atom is gone). Test helpers that counted
`@'macros/watch-registry` migrated to `macros/live-watch-count`, and the
helpers that scanned entries for `:component` to `macros/tracked?`; no test
open-codes the registry shape anymore.

## 2.3 `reakt` integration

The dominant risk, deliberately its own stage (2.9-C).

### Retention chain being broken

`dep-handler` (`reakt.clj:345`) closes over `cell` and `self`; the dep atom's
watch map holds the handler. New handler captures only the registry key; the
subject is the **reaction** (not the cell — a live `track-render` cache can
hold a cell in its `:rx` map, and a cell with no reaction can neither be
invalidated usefully nor re-run; see review B1).

```clojure
;; in make-reaction, before the reify:
;;   rx-key    (gensym "rx")          registry key
;;   watch-key (RxKey. (gensym "rx")) dep-watch key (existing)
;;   cell      :auto-run? (the option) — so the handler does not capture it
dep-handler
(fn [_key _ref old new]
  (try
    (when-let [r (weak/subject rx-key)]        ; dead reaction → no-op
      (let [cell (-cell r)
            auto-run? (:auto-run? @cell)]
        (when (and (changed? old new)
                   (not= :dirty (:state @cell))
                   (not= :disposed (:state @cell)))
          (swap! cell assoc :state :dirty :caught nil)
          (if (fn? auto-run?)
            (auto-run? r)
            (enqueue! r)))))
    (catch Throwable e
      (binding [*out* *err*]
        (println "kmet.libs.reakt dep-handler error:" (ex-message e))))))
```

`auto-run?` moves into the cell so the handler truly captures only the key;
otherwise a callback closing over the component would keep rooting it until
the sweep. The only in-tree `:auto-run?` today is ComponentFn's bare
`(fn [_] (macros/schedule-frame!))` (`hiccup.clj:877`), which closes over
nothing — but the cell keeps the property true for future callers.

### Payload (the sweep's unsubscribe data)

The sweep cannot read a dead cell, so the payload carries the dep set and the
watch key:

```clojure
{:rx-watch-key watch-key     ; the RxKey the dep watches are registered under
 :watching     [dep …]}     ; refs to unwatch; must NOT contain the reaction
```

`on-dead`:

```clojure
(fn [_k {:keys [rx-watch-key watching]}]
  (doseq [dep watching] (unwatch-ref dep rx-watch-key)))
```

Registration happens at the end of `make-reaction`, after `(reset! self r)`:

```clojure
(weak/register! rx-key r {:rx-watch-key watch-key :watching []} on-dead)
```

### Single writer for the dep set

`update-watching!` (`reakt.clj:365-383`) becomes the one writer: it computes
`added`/`dropped` from the **payload's** `:watching`, installs/uninstalls the
watches, and refreshes the payload **only when `added`/`dropped` is
non-empty** — an unconditional registry write per reaction run is a hot-path
cost for nothing. The cell's `:watching`
stays as an introspection mirror (or is dropped and `reaction-state` reads the
payload) — decide once, and keep a debug assertion that the two agree. Today
`-dispose` unwatches from `(:watching @cell)` (`reakt.clj:517-522`); it becomes:

```clojure
(-dispose [_]
  (let [payload (weak/unregister! rx-key)]
    (doseq [dep (or (:watching payload) (:watching @cell))]
      (unwatch-ref dep watch-key))
    (swap! queue (fn [q] (filterv #(not (identical? % @self)) q)))
    (swap! cell assoc :state :disposed :watching #{} :watches {} :value nil :caught nil)
    (doseq [f (:on-dispose @cell)] (f @self))
    nil))
```

Idempotent: the second call gets a nil payload and an empty cell.

### Queue

`enqueue!` holds the reaction strongly until flush, so a queued reaction
cannot die before it runs — the sweep does not need a queue purge. At mode end (Pass 1),
`reakt/discard-queued!` (new, teardown-only) resets the queue so reactions
dirtied at stop are not retained by it. It must never run bodies.

### Semantics (document and test)

- **A reaction is kept alive by its owner/derefers; dependencies are kept alive
  by the reactions that read them.** Verify each creation shape:
  `hiccup/compute` under `with-let` (store holds it), shared `def` computes
  (var), `ComponentFn` `:rx`, cursors (hold their inner `rx`), `derive`
  callers, another reaction's `:watching` payload, and `enqueue!` until flush.
- **`track-render` must hold the reactions its cache validates.** Today `:rx`
  maps `cell → value`, and a cell does not reference its reaction (probe 5):
  under weak dep watches a deref-only reaction would be collected while the
  cache still holds its cell, and `rx-unchanged?` would keep accepting the
  settled cell — the component then serves a stale render forever with
  nothing left to invalidate it. Change the cache to `{cell [ref value]}`
  (keyed by cell — reaction hashing is unreliable per the Identity plumbing;
  the value pins the reaction) and read `@(reakt/-cell ref)` in the `:rx`
  hit check (`rx-unchanged?`, `macros.clj:71-84`). A live cache entry then
  holds its reaction, which is exactly the liveness invariant, and the sweep
  only ever sees reactions no derefer actually holds. This is a Stage C
  change.
- No public API change. `watch-ref` holders must keep a reference to the
  reaction (documented in `watch-ref`).
- `:auto-run? false` last-watcher self-dispose is unchanged.
- GC may now collect a reaction that only its deps' watches referenced; that
  is correct (nothing could deref it), but it is a behavior change for any
  caller relying on that — there are none in-tree (audited).

## 2.4 Deterministic fixes (Pass 1 — findings 1, 3, 5, 7, 8)

These land before the weak layer; they fix the audited findings directly and
stay the resource-correct half afterwards.

### 2.4.1 One tolerant disposal verb

New `kmet.tui.protocols/dispose-component!` (tolerant: try the value's
`:dispose`, else the `dispose` multimethod, isolate exceptions) — the shape
handling currently living in `kmet.app.ui.custom-dialog-adapter`
(`cda/dispose-component!` becomes a delegating alias so app callers keep
working). Route through it:

- `tui-remove-child` / `tui-clear` (`core.clj:240,245`) — they can receive
  duck-typed map components via extension footer/widget paths; today
  `protocols/dispose` would throw on a raw map.
- the `ui_registry` sites below.

**Keep reconcile and container disposal strict** (`retire-item!`,
`container-replace-children!`): a throwing `dispose` on a DSL-owned child is a
real bug and should stay loud. The tolerant verb is for foreign values.

### 2.4.2 `ui_registry.clj` sites

| site | change |
|---|---|
| `:reset` widgets, 771-776 | `(dispose-dialog-component! w)` for every value; then reset the maps |
| `:set-header`, 354-358 | store the factory result in `custom-header-atom` (it was never assigned — nothing could find the header after a swap); dispose the previous value unless it is `hdr` |
| `:reset` header, 783-791 | same; never dispose `hdr` |
| `:set-editor-component`, 410-427 | dispose the previous `@current-editor-atom` when it is not `ed` |
| `:reset` editor, 803-808 | same, before resetting to `ed` |

Validation: a leak test per site (see 2.8).

### 2.4.3 Status indicators

All three `:status-current` writers — `show-status-indicator!`
(`status.clj:118`), `activate-working-indicator!` (`:135`) and
`clear-status-indicator!` (`:184`) — dispose the **transient** indicator
being dropped (the old `:status-current` value), through one
`dispose-transient-indicator!` helper. Never touch `(:status-indicator cs)` —
the working indicator is reused, not dropped. Tests: `clear-status-indicator!`
and `activate-working-indicator!` leave the working indicator alive.

### 2.4.4 `ChatHistoryComponent.dispose`

Add `(declare chat-history-clear!)` before the record
(`chat_history.clj:113`; `chat-history-clear!` is at `:744`) and:

```clojure
(dispose [this] (chat-history-clear! this))
```

so `tui-clear`/container disposal cascades into messages. Idempotent with the
explicit clear calls.

### 2.4.5 `kmet config` screen

`package_manager.clj:254-255` → wrap in `try/finally` and
`(protocols/dispose screen)` after `tui-stop`, so a `tui-start` throw doesn't
skip the size-ref watch cancel.

### 2.4.6 Minor hardening

- `terminal_jline.clj`: `run-stty`/`run-chcp` share `read-bounded`, which
  destroys the child on a nil deref result. The child is a raw
  `ProcessBuilder` process, so the destroy is `.destroy` + `.destroyForcibly`
  (`babashka.process/destroy-tree` needs a process record).
- `resource_config.clj:728`: the rows watch key used
  `System/identityHashCode` — same collision class as finding 4; now a
  `gensym`.
- `session_admin.clj`: the poll loop is
  `start-branch-summary-render-driver!`, which exits when the TUI's
  `:running?` flag is false or `done` is delivered; the summarization
  future catches `Throwable`.
- Optional: `settings_list/open-submenu!` disposes an existing submenu before
  replacing (unreachable today; one line).
- Not in scope: stale-custom-theme pruning — finding 9 is a non-issue (the
  registry growth is by design).

## 2.5 Mode exit (Pass 1 — fixes findings 2 and 10)

Findings first: exit disposes the tree deterministically; the weak layer
(Pass 2) later becomes a backstop for drop sites, not the mode-exit answer.
Root clearing is still required — disposal alone cannot release the
process-global slots (finding 10).

Two owners: `interactive/run` and `package_manager/run-config`. Order:
abort signals → extension surfaces (`ui-reset!`: dialogs resolve, extension
components dispose) → pending bash → dock (leave-before-dispose) →
remaining overlays → `tui-clear` (chat history cascades into messages via
2.4.4) → global roots. Tree disposal runs
`with-let` cleanups, so app-tree cleanups now run at exit; extension surfaces
were already disposed by `ui-reset!`.

```clojure
;; interactive/run — hoist cs so the finally can see it (review B5)
(let [cs-ref (atom nil)]
  (try
    (let [cs (layout/build-layout config session)]
      (reset! cs-ref cs)
      …
      (tui/tui-start (:tui cs))
      …)
    (catch …)
    (finally
      (when-let [cs @cs-ref] (teardown-mode! cs))
      (extensions/clear-ui-registry!))))

(defn- dispose-mode-tree!
  "Finding 2: release the layout tree's components after the TUI stopped.
   Pending bash components and open overlays live outside the TUI child
   list, so they are disposed explicitly first; then dock entries and the
   TUI's direct children follow. Idempotent, best-effort per step."
  [cs]
  (try (bash-execution/dispose-pending-bash! @(:pending-bash-components cs))
       (catch Throwable _))
  (try (reset! (:pending-bash-components cs) []) (catch Throwable _))
  (try (dock/clear! cs) (catch Throwable _))
  (try (doseq [ov @(:overlays (:tui cs))]
         (protocols/dispose-component! (:component ov)))
       (catch Throwable _))
  (try (tui/tui-clear (:tui cs)) (catch Throwable _))
  nil)

(defn teardown-mode!
  "Best-effort release of everything that roots the mode after the TUI
   stopped. Each step is isolated: a throw here must not mask the original
   exception on the finally path. Order is signal → surfaces → tree → roots.
   Idempotent."
  [cs]
  (try (when-let [ag @(:agent-state cs)] (reset! (:signal ag) true)) (catch Throwable _))
  (try (reset! (:bash-signal cs) true) (catch Throwable _))
  (try (extensions/ui-reset!) (catch Throwable _))          ; dialogs + extension surfaces
  (try (dispose-mode-tree! cs) (catch Throwable _))         ; finding 2
  (try (extensions/clear-runtime!) (catch Throwable _))     ; sinks: session/context/entry
  (try (theme-ctrl/shutdown! (:theme-controller cs)) (catch Throwable _))
  (try (reakt/discard-queued!) (catch Throwable _))
  nil)
```

New pieces:

- `extensions/clear-runtime!` — `set-session! nil`, `set-context-sink! nil`,
  `set-entry-sink! nil` (idempotent).
- `theme-ctrl/shutdown!` — `(theme/on-theme-change nil)` +
  `(theme/stop-theme-watcher!)` (fixes finding 6).
- `reakt/discard-queued!` — `(reset! queue [])`, teardown-only, never runs
  bodies.

Deliberately **not** in this pass: quiescing the agent turn (best-effort
abort signals only) and taint for late appends. A late append lands in the
disposed chat history; the agent future that can still append is the one
being abandoned, and the weak layer (Pass 2) backstops anything it still
reaches.

`run-config` has no `cs`: its exit is `(protocols/dispose screen)` inside a
`try/finally` around `tui-start`/`tui-stop` (2.4.5).

## 2.6 Walkthroughs — what reclaims each leak class

| scenario | reclaimed by |
|---|---|
| `/reload` widgets/header/editor (finding 1) | 2.4.2 immediately; weak `track!` + weak `reakt` even if a site regresses |
| dropped lead record with `track!` (future drop sites) | weak `track!` + sweep |
| dropped `ComponentFn`/panel/compute (third-party, future) | weak `reakt` + sweep (Stage C) |
| transient status indicator (finding 3) | 2.4.3, falls back to weak |
| mode exit (finding 2) | Pass 1 `teardown-mode!` (tree disposal + root clearing); CLI exits anyway |
| global roots (finding 10) | Pass 1 `teardown-mode!` explicitly; weakness cannot help while they root |
| dropped component with an armed timer | **not reclaimed** until its `dispose` cancels the timer — deliberate; surfaced by the guard |
| transcript / session growth (finding 9) | non-issue (excluded): by design; `/new` or a compaction policy |

## 2.7 Guard and observability

- Counters: `weak/live-count`, `weak/entry-count`, `macros/live-watch-count`,
  `reakt/live-reaction-count`, plus existing `timers/scheduled` and
  `hiccup/counters`.
- `--debug` exit report: after `teardown-mode!`, log the survivor types/counts
  when `live-watch-count` or `live-reaction-count` is non-zero (log, never
  throw on the exit path). Include `timers/scheduled` so the timer-held
  exception is visible.
- Optional `KMET_DEBUG_LEAKS=1`: one periodic line (counts only) from the
  render loop for long-session diagnosis.
- Test helper `with-leak-check`: snapshot → run → compare on explicit-dispose
  paths (deterministic).

## 2.8 Testing strategy

Pass 1 (fast suite; one regression test per finding):

- F1: widget `:reset`, header swap and editor swap run the dropped value's
  `dispose`; `watch-registry` returns to baseline.
- F2: chat history cascades through `tui-clear`; `teardown-mode!` clears the
  global slots, the callback, the watcher and the queue, and a second call is
  a no-op.
- F3: `clear-status-indicator!` and `activate-working-indicator!` leave the
  working indicator alive.
- F4: unique keys for distinct components; `remove-track-watches!` no-ops on
  a cache-less record; concurrent first renders converge on one key.
- F5: `run-config` disposes the screen when `tui-start` throws.
- F6/F10: watcher stopped, `on-theme-change nil`, sinks nil, queue empty.
- F7: a nil `stty` deref destroys the child.
- F8: the driver exits when the TUI stops; a `Throwable` in the
  summarization future still delivers.
- Existing tests: `macros/watch-registry` count assertions stay exact on
  explicit-dispose paths; migrate any that relied on identity-hash keys.

Pass 2 (weak layer, fast suite):

- `kmet.libs.weak` unit tests: register/subject/payload; identical-subject
  reuse; unregister returns payload; `register!` throws on a different live
  subject for the same key; `on-dead` isolation.
- Weak-layer tests assert per-key outcomes, not registry-wide counts:
  subjects from earlier tests become unreachable and are collected at
  arbitrary times, so a `sweep!` count can legitimately include stale
  entries (the Stage A concurrency test learned this the hard way).
- `track!`: explicit dispose returns `live-watch-count` to baseline; a
  duck-map child in `tui-clear` disposes via the tolerant verb.
- `reakt`: `discard-queued!` empties (Pass 1); `-dispose` purges; reaction
  liveness per creation shape (var, `with-let`, ComponentFn, cursor, dep
  graph, queued); a `track!` cache entry pins its reaction (the `:rx` value
  holds it), so a collected reaction can never validate a cache.

GC-based (`^:slow`, both hosts):

- `await-collected` helper: bounded `System/gc` loop + `weak/sweep!` with a
  deadline; assert a dropped subject disappears and its atom watch key is gone
  from `(.getWatches atom)`.
- **bb/SCI frame caveat:** in a `deftest` body, never evaluate the subject
  (e.g. `(weak/subject key)`, or any component value) directly — bb/SCI keeps
  the frame's value slots alive, which roots the component and defeats the
  collection assertion. Look the subject up inside a helper fn that returns a
  boolean (the track! GC test's `live-subject?`).
- **Stage C gate test:** drop a `hiccup/root` `ComponentFn` tree with nested
  fn components; after Stage B, hold a `WeakReference` to the tree's reaction
  (`@(:rx comp)`, or a marker captured by the body) and check `.get` after GC
  + sweep — `live-reaction-count` does not exist until Stage C. If the
  reference clears, Stage C is purely defensive; if not (expected), it is
  required. Record the result here.
  **Result (Stage B): required — measured with a `WeakReference` marker on
  both hosts (`target/gate_stage_c.clj`), because the reaction counter
  arrives with Stage C: the dropped tree's marker and its compiled Text
  components survive GC + sweep.** Post-C re-run: reclaimed — marker, root
  and reaction all collected on both hosts (see the Stage C result).
- Mid-session regression: 50 `/reload` cycles with a widget tree → counts
  flat (Stage B).

Performance (Stage C): dep-change overhead and frame time with an 8–10k-line
  transcript, before/after; budget agreed before merging.

## 2.9 Rollout stages

Live tracking is the checklist at the top of this file; the table below is the
scope summary.

| stage | contents | acceptance |
|---|---|---|
| **Pass 1** | findings 1-8 + 10 deterministic fixes: 2.4, 2.5 (`teardown-mode!`, tree disposal, root clearing), F4 unique keys; 9 excluded | one regression test per finding; existing suites green |
| **A** | `kmet.libs.weak` + unit tests; no behavior change | probes pass on bb and Jolt; helper tests green |
| **B** | weak `track!` registry; loop/flush sweep; guard counters | Pass-1 fixes still have regression tests; 50-reload mid-session test flat; full suite green |
| **C** | weak `reakt` dep watches | Stage-C gate test result recorded; liveness tests; GC tests on both hosts; perf within budget |
| **D** | optional hardening; `--debug` exit report; docs | zero-finding lint/format; docs updated |

Dropped from the design (precedents in Part 1): owning-atom framework,
`defcomponent :owns`, `hiccup/adopt`, `tui-start` auto-teardown,
`container-clear` semantics.

## 2.10 Risks and mitigations

| risk | mitigation |
|---|---|
| `reakt` liveness change collects a reaction that should live | per-shape liveness tests; the invariant "held by derefers"; `track-render`'s cache pins its reactions (2.3); Stage C gate + review |
| a collected reaction leaves a settled cell that still validates a `track!` cache | 2.3: the cache holds the reaction, so this state is unreachable |
| GC tests flaky | deterministic tests in the fast suite; GC tests `^:slow` with bounded waits |
| sweep cost on the frame path | queue-gated scan (empty queue = one `.poll`); measure in Stage B |
| shared cache atom → key collision | unique key per cache atom, race-safe (Pass 1); Stage B `weak/register!` throws on a different live subject |
| Jolt weak refs on reify reactions | probe first; if unsound, Stage C is bb-only behind a host check (record the divergence) |
| double disposal | weak layer never calls `dispose`; deterministic paths are idempotent |
| hot-path overhead | handler does one `.get` + map lookup; measure in Stage C |
| `on-dead` throwing on the loop thread | isolated per entry, logged to stderr |

## 2.11 Docs to update

- `src/kmet/tui/tui.md` §5.1 — ownership + the two layers; the weak rule; the
  limits (cleanups/timers not GC-managed). §3.1/§12 — counters and sweep.
- `src/kmet/extension.md` — widget/header/editor lifecycle; cleanups still
  require `dispose`; the weak backstop.
- `src/kmet/README.md` — `kmet.libs.weak` in the libs layer (the one
  `java.lang.ref` user). Also fix the layer-boundary sentence: it currently
  forbids sibling-lib requires, while the enforced guard
  (`test/kmet/libs/test_self_contained.clj`) allows them and the tree
  composes libs everywhere (`http → concurrent/json/process`,
  `oauth → crypto/http/json`). The rule is "never non-libs kmet namespaces".
- This file — pass/stage checkboxes and results (Pass 1, then the Stage C
  gate).

## 2.12 Open questions

- Stage C go/no-go: **decided — required** (the Stage-B gate result in 2.8:
  the dropped tree's reaction survives GC + sweep on both hosts). Implemented
  in Stage C; the post-C gate re-run reclaims the tree on both hosts.
- Extension unload: `unload-extension!` deregisters fns but not the UI
  surfaces commissioned through `ui-call`, so a mounted widget keeps its
  extension reachable until `/reload` (2.4.2 disposes on reset). Give
  surfaces an extension owner and clear them on unload, or document the
  `/reload` requirement? Out of scope for this pass; noted so the docstring's
  "namespaces and jars become unreachable" is not read as "immediately".
  First concrete client landed with replicant item 2: tag aliases register
  through the extension api (`ext/register-alias!`) and their deregister fn
  rides the existing `track-deregister!` list, so unload removes stale
  aliases — the registry is not a standing strong root for unloaded code.
  (An extension that used `kmet.tui.alias/defalias` directly would bypass
  that path; the docs rule it out.) The mounted-surface half of this
  question is unchanged.
- Should a later pass add turn quiescing + taint for late appends? Deferred;
  Pass 1 disposes the tree and the weak layer covers memory.
- Should the timer registry grow an ownership hint for the exit report?
  Minimum is the count.
- Naming: **`kmet.libs.weak`** (decided at Stage A).

---

# Appendix — probe scripts (repro)

Run with `bb <file>` from the repository root (or `jolt <file>` for the host
comparison). Keep them under `target/` (gitignored). Probes 1-4 are the
pre-fix repros: they read the strong `macros/watch-registry`, which Stage B
replaced with the weak registry (`macros/live-watch-count` is the equivalent
counter today).

Probe 1 — `tui-stop` does not dispose (`:watchers-after-stop 1`):

```clojure
(require '[kmet.tui.core :as core]
         '[kmet.tui.hiccup :as h]
         '[kmet.tui.macros :as macros]
         '[kmet.tui.protocols :as protocols])

(defn watchers [] (count @(deref #'macros/watch-registry)))

(def root (h/root [:container {}
                   [:text {:text "hello" :padding-x 0 :padding-y 0}]]))
(def tui (core/create-tui nil))
(core/tui-add-child tui root)
(protocols/render root 40)
(prn :watchers-after-render (watchers))
(core/tui-stop tui)
(prn :watchers-after-stop (watchers) "component tree still retained")
(protocols/dispose root)
(prn :watchers-after-dispose (watchers))
```

Probe 2 — a 100-message transcript retains 150 subscriptions after a
disposal-less shutdown; `chat-history-clear!` drops to 0:

```clojure
(require '[kmet.app.ui.chat-history :as ch]
         '[kmet.tui.core :as core]
         '[kmet.tui.macros :as macros]
         '[kmet.tui.protocols :as protocols])

(defn watchers [] (count @(deref #'macros/watch-registry)))
(def h (ch/make-chat-history))
(dotimes [i 50]
  (ch/chat-history-add-message! h {:role :user :content (str "message " i)})
  (ch/chat-history-add-message! h {:role :assistant :content (str "reply " i)}))
(protocols/render h 80)
(prn :watchers-after-render (watchers))
(prn :watchers-after-"shutdown" (watchers) "still retained")
(ch/chat-history-clear! h)
(prn :watchers-after-clear (watchers))
```

Probe 3 — extension widget values have no `:dispose` key
(`:has-:dispose false`, `:is-IComponent true`):

```clojure
(require '[kmet.modes.interactive.ui-registry :as ui-registry]
         '[kmet.tui.protocols :as protocols])
(let [w ((var ui-registry/make-extension-widget-component) nil
         [:text {:padding-x 1 :padding-y 0 :text "widget"}])]
  (prn :class (class w) :has-:dispose (boolean (:dispose w))
       :is-IComponent (satisfies? protocols/IComponent w)))
(let [w ((var ui-registry/make-extension-widget-component) nil
         (fn [_ _] {:render (fn [_] ["duck"]) :dispose (fn [] :ok)}))]
  (prn :factory-map-class (class w) :has-:dispose (boolean (:dispose w))))
```

Probe 4 (review) — an `adopt`-stamped record spliced from a fn body is never
owned: `:constructs 1, :disposals 0` across passes, i.e. `adopt` cannot work by
stamping alone.

```clojure
(require '[kmet.modes.interactive.ui-registry :as ui-registry]
         '[kmet.tui.hiccup :as hiccup]
         '[kmet.tui.protocols :as protocols])
(def above (atom {}))
(def root (hiccup/root ((var ui-registry/make-widget-area-above) above)))
(swap! above assoc :w1 ((var ui-registry/make-extension-widget-component) nil
                        [:text {:padding-x 1 :padding-y 0 :text "widget one"}]))
(hiccup/reset-counters!)
(protocols/render root 40)
(prn :pass1 (hiccup/counters))
(protocols/render root 40)
(prn :pass2 (hiccup/counters))   ; :disposals 0 — foreign, never owned
```

Probe 5 (design review) — a reaction's cell does not reference its reaction, so
without a dep watch the reaction is collected while the cell lives. This is the
fact the Stage C `track-render` cache change handles (2.3).

```clojure
(require '[kmet.libs.reakt :as r])
(defn gc! [] (dotimes [_ 5] (System/gc) (Thread/sleep 50)))
(defn probe
  []
  (let [rx (r/make-reaction (fn [] 42))     ; no deps → no watches anywhere
        cell (r/-cell rx)
        wr (java.lang.ref.WeakReference. rx)]
    @rx
    [wr cell]))
(let [[wr cell] (probe)]
  (gc!)
  (prn :reaction-alive (some? (.get wr)) :cell-state (:state @cell)))
;; => :reaction-alive false, :cell-state :idle — the cell survives alone.
;; With a dep watch the reaction stays alive: the dep atom's watch map holds
;; dep-handler, which closes over `self` (Part-1 fact 2).
```
