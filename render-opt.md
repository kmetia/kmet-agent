# The per-child render cost: push invalidation + a per-node probe

Design record for the work the frame benchmark now attributes to
`:parts` (the chat history's walk over its message components). Status:
**proposed**, not started. Supersedes the earlier "verify at write / parent
probe" sketch (review.md §14's follow-up), which was rejected on
correctness grounds — see §10.

---

## 1. The cost, measured

Host: bb on Termux, virtual terminal. Session: 447 messages, 18,243
rendered lines, `:tool-display-mode :expanded` (a real 3.9 MB session).
Bench: `scripts/bench/kmet_frame_bench.clj` after the measurement fixes
(`1d7d5560`).

| scenario | compose | **parts** | lines | diff+emit | frame total |
|---|---|---|---|---|---|
| calm (no change) | 4.9 | **4.0** | 0.8 | 0.1 | 5.8 |
| typing | 5.0 | **3.9** | 1.8 | 0.2 | 7.0 |
| stream (append/frame) | 6.6 | **5.0** | 1.9 | 0.6 | 9.1 |
| cold (first frame) | 3770 | **3769** | 14 | 481 | 4265 |

(medians, ms; cold is the first frame of the session — all bodies, i.e.
markdown reflow, not this work.)

Per-component cache-hit cost (`track!` hit, 30-line body):

| shape | cost |
|---|---|
| fixed overhead (field lookup, `@cache-atom`, `contains?`, width `=`, two `reduce-kv`s) | **1157 ns** |
| each plain tracked atom (deref + `identical?`/`=`) | **220 ns** |
| one tracked reaction (cell deref + destructure + `case` + compare) | **1240 ns** |
| raw `@cache-atom` only | **236 ns** |

Real message components (447 of them, three passes, `p50 = 7 µs`):

| role | n | mean hit |
|---|---|---|
| user | 9 | 3.9 µs |
| assistant | 200 | 4.8 µs |
| tool | 237 | 7.9 µs |
| compaction | 1 | 24 µs |

So: one `track-render` hit per message per frame, ~6 µs each, 447 messages
→ 2.7 ms; plus the parts/flags loop → 3.9 ms. That is 75 % of compose and
57 % of a typing frame.

The hit stops the descent: a hit returns the message's lines, so the
message's Box/Container/Markdown children are *not* rendered. One hit per
message, not per node.

## 2. How the cache works today

`kmet.hikap.component/track-render` (`modules/hikap/src/kmet/hikap/component.clj:128`):

**Miss path** (body runs):
1. `binding [reakt/*tracking-scope* [atoms rx]]` around `render-fn`; every
   `@ref` in the body is rewritten by the `track!` macro to
   `kmet.reakt.core/tracked-deref`, which records `{ref value}` in the
   bucket `ref-kind` selects (`reakt/core.clj:142`).
2. A watch on the *cache atom* itself flips `invalidated?` if anything
   nils the cache mid-body.
3. `weak/register!` the component under its watch key; then install the
   ref watches (`reakt/watch-ref` with a key-only handler).
4. Write `{:width :atoms :rx :result}` into `:cache-atom` — the recorded
   values are the ones **as read**, so a mid-body write leaves them
   disagreeing with the atom.

**Hit path** (no body):
```clojure
(and cache
     (contains? cache :atoms)          ; guards a pre-shape cache (nREPL reload)
     (= (:width cache) width)
     (reduce-kv atoms-unchanged? true (:atoms cache))   ; 220 ns per atom
     (reduce-kv rx-unchanged? true (:rx cache)))        ; 1240 ns per reaction
```

**Invalidation**: a ref watch fires on a *value change* (identical? fast
path, then `=`; equal-value resets are no-ops) and calls
`invalidate-cache` → `(reset! cache-atom nil)` + `schedule-frame!`.
Branch switches re-install watches for newly read refs and remove the
watches of refs no longer read.

**Reactions are different**: `reakt`'s dep handler marks the reaction
`:dirty` and enqueues it (`reakt/core.clj:455-460`); the reaction's own
watchers fire only when it re-derives to a *different value*. Hence
`rx-unchanged?`'s state test:

```clojure
(case state (:idle :busy :disposed) true false)   ; :unrun/:dirty must run, :failed must rethrow
```

## 3. Why a parent must call `render`

`Container.render`, `ChatHistory`'s `message-parts` and the document
compose flatten children through `protocols/cached-lines` /
`concat-cached` (`modules/tui/src/kmet/tui/protocols.clj:59,94`). The
memo is content-addressed: the parent reuses its previous line vector
while every child returns the **object** it returned last pass. The only
way to learn that is to call `render` on each child — which is the 6 µs
hit above. The parent's identity check is free; the child's hit is not.

## 4. The design

### 4.1 Invariant

> **A `track!` component's cache atom is non-nil ⇒ `(:result @cache-atom)`
> is what `render` at `(:width @cache-atom)` would return.**

With that invariant, a parent needs one atom read per child instead of a
full validation:

```clojure
(defn stable-lines
  "The lines COMPONENT would render at WIDTH if they are certainly still
   valid, else nil. One atom read: the cache's presence IS the validity
   proof (see the invariant above). nil for a component without a cache
   field (Box, containers, DSL roots, hand-rolled components)."
  [component width]
  (when-let [c @(cache-atom-of component)]   ; the existing private
                                             ; component-cache-atom helper
    (when (= width (:width c))
      (:result c))))
```

### 4.2 Why the invariant does not hold today — two holes

**Hole 1 — watches are installed after the body.**
`weak/register!` + `watch-ref` run after `render-fn` returns. A ref read
*during* the body has no watch yet, so a concurrent write (the LLM stream
appends to `text-atom` on a worker thread while the render loop composes)
is not observed; the cache is written with the value as read. The per-hit
`atoms-unchanged?` re-check is the compensation — and the documented
reason `test-cache-verifies-values-even-without-watch` exists.

**Hole 2 — reactions report value changes, not dirtiness.**
An input write marks a reaction `:dirty` but does not notify the
component watching it; only the re-derive does, and only if the value
changed. Between the mark and the flush, the component's cache is still
non-nil while its input has changed. `rx-unchanged?`'s `:dirty` (and
`:unrun`/`:failed`) branch is the compensation.

### 4.3 The four changes

**Step 1 — `reakt`: watch at first read.**
The tracking scope grows from `[atoms rx]` to a map that also carries the
component's weak-registry key and its invalidation handler:

```clojure
;; reakt/core.clj — *tracking-scope*
{:atoms (atom {})          ; ref -> value as read
 :rx    (atom {})          ; reaction -> value as read
 :on-read (fn [ref kind] nil)}   ; track-render's hook: register the watch
```

`tracked-deref` calls `:on-read` for every trackable ref it records. Order
matters: **install the watch, then deref.**

```clojure
(defn tracked-deref [ref]
  (let [kind (ref-kind ref)]
    (when (and kind *tracking-scope*) ((:on-read *tracking-scope*) ref kind))
    (let [v (deref ref)]
      (when kind (record-in-scope! ref v kind))
      (record-dep! ref v)
      v)))
```

Install-then-read means a write in the window fires the watch (the
mid-body guard then refuses the cache) and the read that follows sees the
new value — conservative in the right direction. The handler must be
idempotent per ref (`add-watch` replaces the same key, but the hot path
should not re-add per read): `track-render` keeps the already-watched set
and `:on-read` checks it.

`track-render` therefore moves `weak/register!` **before** the body (with
an empty `:watched` that grows as refs are read), and the payload's
`:watched` grows during the body. Teardown/sweep already read
`:watched` from the weak payload — that contract is unchanged, only the
timing of population.

Closes hole 1.

**Step 2 — `reakt`: notify dependents when a reaction stops being
trustworthy.**
A reaction's watchers fire only when its value *changes*, and stop firing
for good once it is disposed. Both are "the cache can no longer be
trusted" events, so give reactions one keyed registry for them:

```clojure
;; reakt/core.clj — keyed, so a component can unsubscribe; the existing
;; unkeyed :on-dispose vector stays as it is
:dependents {key f}   ; f is called when the reaction goes :dirty or is disposed

(defn add-dependent!    [r key f])   ; (swap! (-cell r) assoc-in [:dependents key] f)
(defn remove-dependent! [r key])
```

and call it from both trigger points:

```clojure
;; dep handler, the :dirty transition (reakt/core.clj:455-460)
(swap! c assoc :state :dirty :caught nil)
(doseq [[_ f] (:dependents @c)] (f r))   ; ← new: dependents invalidate now
(if (fn? auto-run?) (auto-run? r) (enqueue! r))

;; dispose path (reakt/core.clj:645-660), after the state flips
(doseq [[_ f] (:dependents @c)] (f r))   ; ← new: never-re-notified dependents invalidate
```

`track-render` subscribes with "nil my cache" under its own watch key the
first time it reads a reaction, and unsubscribes on the branch-switch
unwatch and in `remove-track-watches!` — the same lifecycle the ref
watches already have. Keyed, because an unremoved hook would invalidate a
component forever (the zoo of zombie watches this file already guards
against). The hook is key-only and resolves the component through
`kmet.reakt.weak` exactly like the existing invalidation handler, so it
cannot root a component.

An input change now invalidates the component immediately, whether or not
the reaction's value ends up different, and a disposed dependency cannot
leave a non-nil cache behind. The `:failed` case falls out of dirty: a
failed run was dirty first, so the cache is already nil, and the next
render's deref rethrows as today.

Closes hole 2.

**Step 3 — `kmet.hikap.component`: drop the pull checks, add the probe.**
The hit becomes:

```clojure
(and cache
     (contains? cache :atoms)      ; keep the pre-shape reload guard
     (= (:width cache) width))
```

`atoms-unchanged?` and `rx-unchanged?` are deleted. `track-render`'s
docstring changes with them: the recorded values are no longer read for
validation — the maps stay as the ref set the teardown/sweep needs, plus
the result.

Add `stable-lines` (§4.1). Keep `:rx` entries `[reaction value]`: the
entry **pins the reaction** — `reakt/watch-ref` does not keep a library
ref alive, so a collected reaction would silently lose the very watch the
invariant rests on ("pins" was already load-bearing in the old design for
validation; it stays for liveness).

**Step 4 — the parents probe.**
`kmet.tui.protocols` gains

```clojure
(defn render-or-stable
  "Render C at WIDTH: a track! component that can prove its cached lines are
   still valid returns them without re-checking its inputs (one atom read);
   everything else renders."
  [c width]
  (or (component/stable-lines c width)
      (render c width)))
```

used at the three flatten sites:

* `Container.render` (`components/container.clj`),
* `message-parts` (`ui/tui/chat_history.clj:91`),
* `compose-document` (`tui/core.clj:1988`).

Only `track!` components answer the probe; `Box`, DSL roots, reify
components and containers fall through to `render` (they keep their own
memos — a container's memo hit is `@cache-atom` + width + key + parts
identity, a handful of comparisons, not a tracked-ref walk).

## 5. Correctness case analysis (step 3 in place)

| case | outcome |
|---|---|
| a tracked atom is written | its watch fires → `invalidate-cache` → cache nil → probe misses → body re-runs. |
| equal-value reset | watch no-ops (`identical?`/`=`) → cache stays → probe hits with the same result. Correct: the output would be identical. |
| mid-body write, ref read earlier this pass | watch installed at the read fires → mid-body guard refuses the cache → probe misses next pass. |
| mid-body write, refs read before the watch existed (first ever read) | impossible: install happens before the deref. |
| branch switch: a newly read ref is written *during* the same body | installed at its first read → fires → cache refused. |
| reaction input changes (dirty) | the reaction notifies its `:dependents` before enqueueing → cache nil → probe misses → body re-runs and settles the reaction. |
| reaction re-derives to an equal value | cache was already nil'd at dirty → one extra body run. (Old behavior: the rx value check kept the hit. This is the one new cost: equal-value re-derives now re-render.) |
| reaction fails | dirty first → cache nil → render re-runs the body → the deref rethrows, as today. |
| reaction disposed | the dispose path notifies its `:dependents`, which nils the cache → probe misses → re-render. Required by step 2: a disposed reaction never fires its value watch again. |
| a watch is lost (bug: wrong key, manual `remove-watch`, registry sweep of a live component) | **no longer compensated** — the component/subtree stays stale until something else invalidates it. This is the trade. |
| width change | `stable-lines` compares width → miss → render rebuilds. |
| component disposed / collected | probes and watches die with it; no probe can reach it (the parent's memo is dropped with the child). |
| `Container`/`Box`/DSL child | probe returns nil → `render` (unchanged). |

## 6. Semantics change that must be stated

`test-cache-verifies-values-even-without-watch`
(`modules/tui/test/kmet/tui/components/test_track.clj:102`) exists to pin
the lost-watch safety net. It must be **replaced**, not deleted silently:

* new test: a body that writes one of its own tracked atoms is not cached
  (install-at-read makes the write observable);
* new test: removing a watch manually now leaves the component stale —
  document the new failure mode next to it, so nobody re-derives the
  invariant from the old comment;
* new test: a dirty reaction invalidates its component *before* it
  re-derives (the `:dependents` notification);
* keep: equal-value reset keeps the cache.

`modules/tui/src/kmet/tui/tui.md` §3.2 (the cache contract) and §12 (the
idle-frame invariant) must say: *the cache trusts its invalidation edges;
every tracked read is watched from the read; there is no value
re-verification.* The commit message must name the guarantee change.

## 7. What it buys

| | now | after |
|---|---|---|
| message walk (`:parts`) | 3.9 ms | ~0.15 ms (447 probes × ~0.3 µs) |
| compose | 4.9 ms | ~1.2 ms |
| typing frame | 7.0 ms | ~3.3 ms |
| stream frame | 9.1 ms | ~4.5 ms |
| cold frame | 3.8 s | 3.8 s (bodies; unaffected) |

After it, `lines` (1.8 ms) is the largest phase in a typing frame.

These are bb numbers. The same code is interpreted under jolt (similar
shape expected) and compiled on the JVM (where the per-hit cost is a
fraction; the probe still wins because it skips a call, but the absolute
saving is small). Measure both before and after; do not chase the JVM.

## 8. Cost on the write path

Step 2 moves work from the render thread to the mutating thread: every
input write now walks the reactions' `:dependents` hooks. The streaming
append (`swap! text-atom`) is exactly this path. Measure it separately —
the frame bench measures frames, not append latency. A micro-benchmark:
N components subscribed to one reaction/atom, time `(swap! a inc)` with
and without the hooks; the regressions to watch are `stream` frame time
and the append-to-frame delay.

## 9. Work items

Each step is one commit with its tests; the benchmark runs at 3 and 4.

| # | change | files | tests |
|---|---|---|---|
| 1 | watch at first read (scope gains `:on-read`; `tracked-deref` install-then-deref; `weak/register!` before the body; payload `:watched` grows during the body) | `modules/reakt/src/kmet/reakt/core.clj`, `modules/hikap/src/kmet/hikap/component.clj` | mid-body write is not cached; first-read watch fires; idempotent re-read; teardown unwatches what the body read |
| 2 | keyed `:dependents` on reactions, notified on `:dirty` and dispose, subscribed/unsubscribed by `track-render` | `modules/reakt/src/kmet/reakt/core.clj`, `modules/hikap/.../component.clj` | dirty nils the cache; equal-value re-derive still nils it once; failed reaction rethrows; dispose nils; a branch-switch unsubscribe stops the invalidation (no zombie hook); the hook does not root the component |
| 3 | drop `atoms-unchanged?`/`rx-unchanged?`, add `stable-lines`, keep the `:rx` pin, rewrite the lost-watch test, docs | `modules/hikap/.../component.clj`, `test_track.clj`, `tui.md` | the §5 table's cases; `stable-lines` nil on width mismatch / nil cache / non-track child |
| 4 | `render-or-stable` + the three call sites; before/after bench on a 400+ message session | `modules/tui/src/kmet/tui/protocols.clj`, `components/container.clj`, `ui/tui/chat_history.clj`, `tui/core.clj` | container/chat-history/compose still identical on unchanged input and correct after a child change; probe used (a counting child) |

Gates: `bb test-changed`, `bb lint-changed`, `bb format-check-changed`,
plus `bb test kmet.tui.test-reakt-integration kmet.tui.components.test-track
kmet.tui.test-frame kmet.hikap.test-core` (the reactive layer's suites).

## 10. Alternatives, and why not

* **Verify at write, drop the hit check** (review.md's option A): move the
  value check from every hit to every miss. Measured saving ~27 % of a
  message hit (the atom half) — and the per-hit check *is* the lost-watch
  net, so this gives the guarantee up anyway. Two delicate changes for
  27 %, with no invariant to show for it.
* **Parent probe on cache non-nil without closing the holes** (option B):
  unsound — an unobserved mid-body write or a dirty reaction freezes a
  subtree, and the lost-watch net is bypassed. Rejected.
* **Record the reaction's cell map and `identical?` it** (A′): defeated in
  the hot case — `-add-watch` does `(swap! cell assoc-in [:watches key] …)`
  (`reakt/core.clj:620`), and every new message registration touches the
  shared `theme-sub` cell, so the fast path is bypassed exactly while
  streaming. Would need `:watches` moved out of the cell first — a
  `reakt` refactor that only buys ~28 % and leaves the holes.
* **Consolidate the shared toggles (pad/hide/label) into one atom**: 4
  atom refs → 1, ~12 % of a hit. App-wide setter churn; keep as a
  possible follow-up, not a substitute.
* **Subtree dirty flags / chunked document**: bigger designs; the chunked
  document would also remove the remaining O(L) copy when a later part
  changes, but needs `build-frame-lines`/`diff-frame!` to walk chunks.

## 11. Non-goals

* The frame pass's allocations (document copies, the write buffer,
  normalize copies) — the source of the GC pauses the benchmark now
  reports; a separate axis.
* Cold start (3.8 s of markdown reflow) — bodies, not the walk.
* The `Container`/`Box`/`ChatHistory` memo shapes — they already work and
  are O(children), not O(tracked refs).

## 12. Appendix — reproducing the numbers

```
bb scripts/bench/kmet_frame_bench.clj <session> 100 30 calm,typing,stream
bb scripts/bench/kmet_frame_bench.clj <session> 100 30 cold
```

Any session with 400+ messages works; the numbers above come from a 3.9 MB
session in `~/.kmet/sessions/` (447 messages, 18,243 lines at width 100).
The benchmark excludes frames that overlap a global pause (a > 5 ms
sampler stall) and says how many it excluded; when every frame is
excluded it says so instead of giving numbers.
