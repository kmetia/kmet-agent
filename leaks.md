# Memory leaks, and the subscription-weakness fix

Part 1 is the audit (what leaks and why). Part 2 is the detailed, reviewed
design for the fix: **weak subscriptions + explicit resources**. Part 2 is a
plan; nothing in it is implemented yet.

- Part 1: findings with file/line references and five reproducible probes
  (three from the audit, one from the review, one from the design review of
  Part 2).
- Part 2: principles, the `kmet.libs.weak` helper, the `track!` and `reakt`
  integrations, the deterministic leftovers, mode exit, guards, tests,
  rollout stages, risks.

## Status & implementation checklist

- **Design:** reviewed and frozen. The rejected alternatives and the
  reasoning are recorded under "Review corrections" (Part 1); do not
  re-propose them without re-checking that section.
- **Implementation:** not started. This file is the plan of record.
- **Gates:** iterate with `bb changed`, `bb test-changed`, `bb lint-changed`,
  `bb format-check-changed`; full gates only on request. New test namespaces
  register in `kmet.tasks.runner/all-namespaces`.
- **Stage C is conditional:** run the Stage-B gate test first (drop a
  `ComponentFn` tree, GC + sweep, check a `WeakReference` to its reaction —
  `live-reaction-count` does not exist yet, see 2.8). Record the result here
  before writing any of 2.3.

### Stage 0 — design (done)

- [x] Audit + probes (Part 1, findings 1-10)
- [x] Review pass + corrections (Part 1, "Review corrections")
- [x] Detailed design (Part 2)

### Stage A — `kmet.libs.weak`

- [ ] `src/kmet/libs/weak.clj`: registry + queue, `register!` (with a
      `:kind`), `subject`, `payload`, `unregister!`, `sweep!`, `live-count`,
      `entry-count` (kind-filtered; 2.1)
- [ ] `test/kmet/libs/test_weak.clj`: register/refresh, identical-subject ref
      reuse, throw on a different live subject, `unregister!` payload,
      `sweep!` (concurrent + queue drain), `on-dead` isolation
- [ ] register the test ns in `kmet.tasks.runner/all-namespaces`
- [ ] changed-file gates green

### Stage B — `track!` + deterministic leftovers + mode exit

Weak `track!`:

- [ ] unique `tracker-key` via cache-atom metadata (collision-free; the
      re-read is not a strict CAS — see 2.2; fixes finding 4)
- [ ] key-only handler + weak registry entry + `on-dead` unwatch (2.2)
- [ ] `remove-track-watches!` meta-only lookup, nil-safe for cache-less
      components (I4)
- [ ] `(weak/sweep!)` in `run-render-loop!` and after a non-empty
      `reakt/flush!`; `macros/sweep-dead-watches!`, `macros/live-watch-count`
      (kind-filtered on `:track!`)

Deterministic leftovers (2.4):

- [ ] tolerant `protocols/dispose-component!` + `cda` delegate +
      `tui-remove-child`/`tui-clear`; reconcile/containers stay strict (I8)
- [ ] five `ui_registry` sites with identity guards (`hdr`, default `ed`)
- [ ] status indicator disposal in all three swap sites (show / clear /
      activate-working; transient only — never the working one)
- [ ] `ChatHistoryComponent.dispose` + `declare`
- [ ] `run-config` try/finally + screen dispose

Mode exit (2.5):

- [ ] hoist `cs-ref` so `run`'s `finally` can reach the state (B5)
- [ ] `teardown-mode!` (isolated steps, best-effort abort signals)
- [ ] `extensions/clear-runtime!` (session/context/entry sinks)
- [ ] `theme-ctrl/shutdown!` (`on-theme-change nil` + watcher stop)
- [ ] `reakt/discard-queued!`
- [ ] guard counters + `--debug` survivor helpers (2.7)

Tests:

- [ ] migrate the 15 test namespaces off `#'macros/watch-registry` (4 also
      build the identity-hash key: `test_tool_execution.clj:771,790`,
      `test_container.clj:46`, `test_track.clj:95`); add a
      `macros/tracked?`/`component-watch-key` accessor for them
- [ ] per-site leak tests (widget reset, header/editor swap, status swap —
      show, clear AND activate-working; chat clear, duck-map `tui-clear`)
- [ ] mid-session `/reload` cycle: explicit-dispose counts flat (fast)
- [ ] dropped-anyway counts flat after GC + sweep (`^:slow`; track! entries
      only — reactions are the Stage-C gate)
- [ ] mode-exit test: `teardown-mode!` clears all roots, idempotent
- [ ] existing count assertions stay exact on dispose paths (via the new
      accessors or a kind-filtered `live-watch-count`)
- [ ] changed-file gates green

### Stage C — weak `reakt` (conditional)

- [ ] **Gate test result recorded here** (see 2.8): is C required? The gate
      test must use a `WeakReference` (or a marker captured by the body),
      not `live-reaction-count` — that counter is Stage C work.
- [ ] weak subject = reaction; payload `{:rx-watch-key :watching}` (B1, B2)
- [ ] key-only `dep-handler` (`auto-run?` moves into the cell; 2.3);
      `update-watching!` single writer (payload refresh only on a real set
      change); `-dispose` via `weak/unregister!`; registration at the end
      of `make-reaction`
- [ ] `track-render` `:rx` cache entries hold the reaction
      (`{cell [ref value]}`; 2.3) — without this a collected reaction's
      cell keeps validating a stale cache
- [ ] `flush!` sweep (reentrancy-safe); `live-reaction-count`
- [ ] liveness tests per creation shape (var, `with-let`, ComponentFn,
      cursor, dep graph, queued, track!-cache)
- [ ] `^:slow` GC tests on bb **and** Jolt (weak refs over reify reactions)
- [ ] perf measurement: dep-change overhead + frame time, within budget
- [ ] changed-file gates green

### Stage D — hardening & docs

- [ ] 2.4.6 minors: `stty`/`chcp` child destroy, branch-summary driver stop
      check, optional submenu replace, optional stale-theme prune
- [ ] `--debug` exit report including `timers/scheduled` (I7)
- [ ] `src/kmet/tui/tui.md` §5.1 + §3.1/§12; `src/kmet/extension.md`;
      `src/kmet/README.md` (`kmet.libs.weak` in the libs layer)
- [ ] check off Stages A-C here and record the Stage C decision

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

## 9. Unbounded by design (not bugs, but memory grows)

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
backends). Used by `kmet.tui.macros` and `kmet.libs.reakt`. (Sibling-lib
requires are what the enforced self-containment guard allows; the README
sentence that appears to forbid them is wrong and fixed in 2.11.)

Portability was verified before designing this:

| primitive | bb | Jolt |
|---|---|---|
| `java.lang.ref.WeakReference` | works | works |
| `java.lang.ref.ReferenceQueue` | works | works |
| `java.util.WeakHashMap` | works | works |
| `java.lang.ref.Cleaner` | works | **unavailable** (must declare `:jolt/provides`) |
| `alter-meta!` on an atom | works | works |

→ the design must be queue/sweep-based, not `Cleaner`-based.

### Shape

```clojure
(defonce ^:private registry (atom {}))
;; key -> {:ref    (java.lang.ref.WeakReference. subject queue)
;;         :kind   keyword              ; :track! / :reaction (counters, report)
;;         :payload map                 ; unsubscribe data; NEVER the subject
;;         :on-dead (fn [key payload])} ; unsubscribe + cleanup
(defonce ^:private queue (java.lang.ref.ReferenceQueue.))
```

### API

```clojure
(defn register!
  "Create or refresh KEY's entry for SUBJECT. KIND tags the entry for the
   kind-filtered counters and the exit report. PAYLOAD is everything the
   sweep needs to unsubscribe (watched refs, watch keys); it must not
   reference SUBJECT. ON-DEAD receives (key payload) after the entry is
   removed and runs exception-isolated, outside the registry swap.

   Reuses the existing WeakReference while SUBJECT is identical (a streaming
   render pass would otherwise allocate one ref per pass). Opportunistically
   sweeps when the queue signals a death — sweep! short-circuits on an empty
   queue, so this is one .poll per registration.

   Throws when KEY is already held by a DIFFERENT live subject: that means two
   components share a cache atom (track!) or two reactions share a registry key,
   which would silently steal each other's watches. Loud beats corrupt."
  [key subject kind payload on-dead] ...)

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

(defn live-count  "Live entries (tests/guards); all kinds or KIND only."
  ([] ...) ([kind] ...))
(defn entry-count "All entries, including dead-until-swept (all kinds or KIND)."
  ([] ...) ([kind] ...))
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
- The outlined sweep re-checks `.get` for the claimed set in a second pass;
  a CAS loop could collect the claimed `[k e]` pairs in one pass, but the
  two-pass version only walks the entries when something died and is
  simpler.
- `swap-vals!` (used elsewhere in the codebase, e.g. `tool_execution`) keeps
  the claim pure; a concurrent `register!` for a dead key cannot happen
  (keys are never reused — see the key schemes below).
- `on-dead` runs on the caller's thread. Callers keep it cheap and
  non-blocking: `unwatch-ref` per dep. Never `flush!`, never render.
- `live-count`/`entry-count` are O(entries); tests and debug only.

## 2.2 `track!` integration

### Key allocation (fixes finding 4)

Replace `System/identityHashCode` with a unique key stored in the component's
cache atom metadata (`:cache-atom`, or legacy `:cache`; both are atoms and
both are per-component by construction):

```clojure
(defn- tracker-key
  "The component's stable watch key, allocated once in its cache atom's
   metadata. The cache atom is the component's one per-instance cell, so the
   key dies with the component and can never collide with another live
   component's key (finding 4). A component must own its cache atom;
   register! throws on the second live user (shared-atom bug).

   The re-read is not a strict CAS: two threads rendering the same component
   can each allocate a key and one meta write wins. Both entries weakly
   reference the same subject and both are swept on its death, so the loser
   self-heals; renders of one component are single-threaded in-tree, so this
   is theoretical. Keys themselves are unique (gensym), which is what
   finding 4 needs. (The cache atom itself would be a race-free key, but the
   registry would then hold the atom — and its last cached render — strongly
   until sweep; the keyword keeps dead payloads small.)"
  [component]
  (when-let [a (component-cache-atom component)]
    (or (::watch-key (meta a))
        (let [k (keyword (str (gensym "track!")))]
          (alter-meta! a assoc ::watch-key k)
          (::watch-key (meta a))))))   ; re-read: a concurrent winner's key wins
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
  (weak/register! watch-key component :track! {:atoms atoms}
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
`{key {:component … :atoms …}}` to the weak entry shape, and
`macros/watch-registry` is replaced by `kmet.libs.weak`'s registry. The 15
test namespaces that read the private var (and the 4 identity-hash key
builders) must migrate to `macros/tracked?`/`live-watch-count` — see the
checklist.

## 2.3 `reakt` integration

The dominant risk, deliberately its own stage (2.9-C).

### Retention chain being broken

`dep-handler` (`reakt.clj:345`) closes over `cell` and `self`; the dep atom's
watch map holds the handler. New handler captures the registry key and the
dep key only — `auto-run?` moves into the cell, so nothing in the handler
reaches the reaction or its subtree. The subject is the **reaction** (not the
cell — a live `track-render` cache can hold a cell in its `:rx` map, and a
cell with no reaction can neither be invalidated usefully nor re-run; see
review B1).

```clojure
;; in make-reaction, before the reify:
;;   rx-key    (gensym "rx")          registry key (the existing watch-key
;;                                    works too — it is already unique)
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
(weak/register! rx-key r :reaction {:rx-watch-key watch-key :watching []} on-dead)
```

### Single writer for the dep set

`update-watching!` (`reakt.clj:365-383`) becomes the one writer: it computes
`added`/`dropped` from the **payload's** `:watching`, installs/uninstalls the
watches, and refreshes the payload **only when `added`/`dropped` is
non-empty** — an unconditional registry write per reaction run is a hot-path
cost for nothing. The cell's `:watching` stays as an introspection mirror (or
is dropped and `reaction-state` reads the payload) — decide once, and keep a
debug assertion that the two agree. Today `-dispose` unwatches from
`(:watching @cell)` (`reakt.clj:517-522`); it becomes:

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
cannot die before it runs — the sweep does not need a queue purge. At mode end,
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

## 2.4 Deterministic leftovers (exact edits)

Resources need these; the weak layer is not a substitute.

### 2.4.1 One tolerant disposal verb

New `kmet.tui.protocols/dispose-component!` — the shape handling currently
living in `kmet.app.ui.custom-dialog-adapter` (`cda/dispose-component!`
becomes a delegating alias so app callers keep working):

- plain maps (not records): their `:dispose` key, isolated;
- sequences: dispose each element (a widget value can be a multi-root
  compiled tree — `hiccup/compile-tree` can return a vector);
- everything else (records, reifies): the `dispose` multimethod — dispatch
  works even where the SCI `satisfies?` check lies.

Exceptions are isolated (a broken foreign component must not take the frame
down) but **logged** via `kmet.debug/log-error`, so a real bug in a
DSL-owned component is still visible; the strict call sites
(reconcile/containers) keep throwing loudly. Route through it:

- `tui-remove-child` / `tui-clear` (`core.clj:240,245`) — they can receive
  duck-typed map components via extension footer/widget paths; today
  `protocols/dispose` would throw on a raw map.
- the `ui_registry` sites below.

**Keep reconcile and container disposal strict** (`retire-item!`,
`container-replace-children!`): a throwing `dispose` on a DSL-owned child is a
real bug. The tolerant half is for foreign values only.

### 2.4.2 `ui_registry.clj` sites

| site | change |
|---|---|
| `:reset` widgets, 771-776 | `(dispose-dialog-component! w)` for every value; then reset the maps |
| `:set-header`, 354-358 | `(dispose-dialog-component! @custom-header-atom)` when it is not `hdr` |
| `:reset` header, 783-791 | same; never dispose `hdr` |
| `:set-editor-component`, 410-427 | dispose the previous `@current-editor-atom` when it is not `ed` |
| `:reset` editor, 803-808 | same, before resetting to `ed` |

Validation: a leak test per site (see 2.8).

### 2.4.3 Status indicators

All three `:status-current` writers — `show-status-indicator!`
(`status.clj:106-119`), `activate-working-indicator!` (`:122-143`) and
`clear-status-indicator!` (`:170-191`) — dispose the **transient** indicator
being dropped (the old `:status-current` value). Put the swap in one helper
so a fourth site cannot regress. Never touch `(:status-indicator cs)` — the
working indicator is reused, not dropped. Add tests that
`clear-status-indicator!` and `activate-working-indicator!` leave the working
indicator alive.

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

- `terminal_jline.clj` `run-stty`/`run-chcp`: on a nil deref result, destroy
  the child — `(.destroyForcibly p)` on the `java.lang.Process` (these use
  ProcessBuilder, not babashka.process; destroying closes stdout, so the
  blocked `slurp` future also returns) — instead of abandoning it.
- `session_admin.clj:701-703`: add `@(:running? (:tui cs))` (or a stopped
  check) to the driver loop and catch `Throwable` around the deliver.
- `resource_config.clj:728`: the rows watch key also uses
  `System/identityHashCode` — same collision class as finding 4; use a
  `gensym`.
- Optional: `settings_list/open-submenu!` disposes an existing submenu before
  replacing (unreachable today; one line).
- Optional: prune stale custom themes on `/reload` (finding 9).

## 2.5 Mode exit

Two owners: `interactive/run` and `package_manager/run-config`. Order matters.

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

(defn teardown-mode!
  "Best-effort release of everything that roots the mode after the TUI
   stopped. Each step is isolated: a throw here must not mask the original
   exception on the finally path. Memory is reclaimed by the weak registry
   either way; this is the deterministic half."
  [cs]
  (try (when-let [ag @(:agent-state cs)] (reset! (:signal ag) true)) (catch Throwable _))
  (try (when-let [s (:bash-signal cs)] (reset! s true)) (catch Throwable _))
  (try (extensions/ui-reset!) (catch Throwable _))          ; dialogs + extension surfaces
  (try (extensions/clear-runtime!) (catch Throwable _))     ; sinks: session/context/entry
  (try (when-let [tc (:theme-controller cs)] (theme-ctrl/shutdown! tc)) (catch Throwable _))
  (try (reakt/discard-queued!) (catch Throwable _))
  nil)
```

New pieces:

- `extensions/clear-runtime!` — `set-session! nil`, `set-context-sink! nil`,
  `set-entry-sink! nil` (idempotent).
- `theme-ctrl/shutdown!` — `(theme/on-theme-change nil)` +
  `(theme/stop-theme-watcher!)`.
- `reakt/discard-queued!` — `(reset! queue [])`, teardown-only, never runs
  bodies.

Deliberately **not** in this pass: tree disposal (no `dock/clear!`,
`chat-history-clear!`, `tui-clear`), quiescing the agent turn, taint for late
appends. Rationale: with the roots cleared, everything only the mode reaches
becomes collectable — but **Stage B alone does not free the component tree**:
a `ComponentFn`'s reaction is still rooted by process-global deps (theme
atoms, shared computes) through the strong `dep-handler → self` chain, so the
tree stays reachable until weak `reakt` (Stage C) lands. A late agent append
lands in a still-live chat whose components are reclaimed when the agent
future ends. Accepted consequence, documented: `with-let` cleanups on
app-tree components do not run at exit (there are none in-tree; extension
surfaces are disposed by `ui-reset!`, which is also what resolves open
`ui-custom` promises so extension flows can finish).

`run-config` has no `cs`: its exit is `(protocols/dispose screen)` inside a
`try/finally` around `tui-start`/`tui-stop` (2.4.5).

## 2.6 Walkthroughs — what reclaims each leak class

| scenario | reclaimed by |
|---|---|
| `/reload` widgets/header/editor (finding 1) | 2.4.2 immediately; weak `track!` + weak `reakt` even if a site regresses |
| dropped lead record with `track!` (future drop sites) | weak `track!` + sweep |
| dropped `ComponentFn`/panel/compute (third-party, future) | weak `reakt` + sweep (Stage C) |
| transient status indicator (finding 3) | 2.4.3, falls back to weak |
| mode exit (finding 2) | 2.5 root clearing (Stage B) + weak `reakt` (Stage C) + GC — Stage B alone leaves the tree rooted by global dep atoms |
| global roots (finding 10) | 2.5 explicitly; weakness cannot help while they root |
| dropped component with an armed timer | **not reclaimed** until its `dispose` cancels the timer — deliberate; surfaced by the guard |
| transcript / session growth (finding 9) | by design; `/new` or compaction policy |

## 2.7 Guard and observability

- Counters: kind-filtered `weak/live-count` / `weak/entry-count`,
  `macros/live-watch-count` (`:track!` only), `reakt/live-reaction-count`
  (`:reaction` only), plus existing `timers/scheduled` and
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

Deterministic (fast suite):

- `kmet.libs.weak` unit tests: register/subject/payload; identical-subject
  reuse; unregister returns payload; `register!` throws on a different live
  subject for the same key; `on-dead` isolation; kind filtering.
- `track!`: explicit dispose returns `live-watch-count` to baseline; widget
  `:reset`, header and editor swaps, status swap (show, clear and
  activate-working), chat clear; a duck-map child in `tui-clear` disposes via
  the tolerant verb.
- `reakt`: dispose unwatches and purges the queue; `discard-queued!` empties;
  reaction liveness per creation shape (var, `with-let`, ComponentFn, cursor,
  dep graph, queued); a `track!` cache entry pins its reaction (the `:rx`
  value holds it), so a collected reaction can never validate a cache.
- Mode exit: `teardown-mode!` clears the four global slots, the callback, the
  watcher and the queue; calling it twice is a no-op.
- Test migration: the 15 `#'macros/watch-registry` users move to
  `macros/tracked?`/`live-watch-count`; the 4 identity-hash key builders
  (`test_tool_execution.clj:771,790`, `test_container.clj:46`,
  `test_track.clj:95`) move to the accessor.

GC-based (`^:slow`, both hosts):

- `await-collected` helper: bounded `System/gc` loop + `weak/sweep!` with a
  deadline; assert a dropped subject disappears and its atom watch key is gone
  from `(.getWatches atom)`.
- **Stage C gate test:** drop a `hiccup/root` `ComponentFn` tree with nested
  fn components; after Stage B, hold a `WeakReference` to the tree's reaction
  (`@(:rx comp)`, or to a marker atom the body captured) and check `.get`
  after GC — `live-reaction-count` does not exist until Stage C. If the
  reference clears, Stage C is purely defensive; if not (expected), it is
  required. Record the result here.
- Mid-session regression: 50 `/reload` cycles with a widget tree → counts
  flat (Stage B).

Performance (Stage C): dep-change overhead and frame time with an 8–10k-line
transcript, before/after; budget agreed before merging.

## 2.9 Rollout stages

Live tracking is the checklist at the top of this file; the table below is the
scope summary.

| stage | contents | acceptance |
|---|---|---|
| **A** | `kmet.libs.weak` + unit tests; no behavior change | probes pass on bb and Jolt; helper tests green |
| **B** | unique `track!` keys + weak `track!` registry; loop/flush sweep; 2.4.1-2.4.5; 2.5 root clearing; guard counters; test migration | audited track!/deterministic leaks have regression tests; 50-reload mid-session test flat; full suite green |
| **C** | weak `reakt` dep watches + `track-render` cache holds reactions + `discard-queued!` | Stage-C gate test result recorded; liveness tests; GC tests on both hosts; perf within budget |
| **D** | 2.4.6 minors; `--debug` exit report; docs | zero-finding lint/format; docs updated |

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
| shared cache atom → key collision | `register!` throws on a different live subject; keys are unique (gensym) so collisions cannot happen; the meta re-read is not a strict CAS but its loser self-heals at sweep (2.2) |
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
- This file — stage checkboxes and results (Stage C gate).

## 2.12 Open questions

- Stage C go/no-go: decide from the gate test, not from principle.
- Should a later pass add deterministic surface teardown (quiesce + taint) for
  embedded hosts, after all? Deferred; the weak layer covers memory.
- Extension unload: `unload-extension!` deregisters fns but not the UI
  surfaces commissioned through `ui-call`, so a mounted widget keeps its
  extension reachable until `/reload` (2.4.2 disposes on reset). Give
  surfaces an extension owner and clear them on unload, or document the
  `/reload` requirement? Out of scope for this pass; noted so the docstring's
  "namespaces and jars become unreachable" is not read as "immediately".
- Should the timer registry grow an ownership hint for the exit report?
  Minimum is the count.
- Naming: `kmet.libs.weak` vs `kmet.libs.subscriptions` — pick at Stage A.

---

# Appendix — probe scripts (repro)

Run with `bb <file>` from the repository root (or `jolt <file>` for the host
comparison). Keep them under `target/` (gitignored).

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
