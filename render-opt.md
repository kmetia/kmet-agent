# The per-child render cost: watch at the read, probe at the parent

Design record for the work the frame benchmark attributes to `:parts` —
the chat history's walk over its message components. Status: **proposed**,
not started; the plan is §9.

Two predecessors were rejected and their reasoning is kept in §10: review.md
§14's "verify at write / parent probe" sketch, and a draft of this file that
closed a reaction hole by pushing invalidation from reactions into their
dependents. The second one is deferred rather than refuted — the hot path
holds no reactions (§1.1), so it bought nothing for the target workload and
carried a frame-scheduling hazard, a write-path regression and three
ordering rules.

When this lands, §2 and §4 collapse into `kmet.hikap.component`'s docstrings
and `tui.md` §3.2; this file is the design record, not permanent
documentation.

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

### 1.1 Why reactions are out of scope

The message components read **plain atoms only**. `theme-sub` is
`kmet.libs.theme/theme-atom` itself — a plain alias, not a compute
(`modules/app/src/kmet/ui/tui/subs.clj:16`) — as are
`image-settings-atom`, `:text-atom`, `:thinking-atom` and
`tools-expanded-atom`. `subs.clj` states the policy: the subs *are* the
source atoms precisely because "a reaction's per-read cache verification
costs about twice an atom deref, paid by every subscriber on every frame".
`compute`/`reaction`/`cursor` appear nowhere in the per-message path.

So a `track!` cache on this walk is `{:atoms … :rx {}}`, and the whole
physics of the hit is the `:atoms` half. Reaction caches keep today's
verification, unchanged — no new machinery, no new risk, no regression for
the components that do read reactions.

---

## 2. How the cache works today

`kmet.hikap.component/track-render`
(`modules/hikap/src/kmet/hikap/component.clj:128`):

**Miss path** (body runs):
1. `binding [reakt/*tracking-scope* [atoms rx]]` around `render-fn`; every
   `@ref` in the body is rewritten by the `track!` macro to
   `kmet.reakt.core/tracked-deref`, which records `{ref value}` in the
   bucket `ref-kind` selects (`reakt/core.clj:142`).
2. A watch on the *cache atom* itself flips `invalidated?` if anything nils
   the cache mid-body.
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

**Invalidation**: a ref watch fires on a *value change* (`identical?` fast
path, then `=`; equal-value resets are no-ops) and calls
`invalidate-cache` → `(reset! cache-atom nil)` + `schedule-frame!`.
Branch switches re-install watches for newly read refs and remove the
watches of refs no longer read.

**Reactions are different**: `reakt`'s dep handler marks the reaction
`:dirty` and enqueues it (`reakt/core.clj:458-461`); the reaction's own
watchers fire only when it re-derives to a *different value*. Hence
`rx-unchanged?`'s state test:

```clojure
(case state (:idle :busy :disposed) true false)   ; :unrun/:dirty must run, :failed must rethrow
```

**The one unsound edge** is step 3 of the miss path: watches are installed
after the body. A ref read *during* the body has no watch yet, so a
concurrent write is unobserved (the LLM stream appends to `text-atom` on a
worker thread while the render loop composes) and the cache is written with
the value as read. `atoms-unchanged?` is the compensation — the reason
`test-cache-verifies-values-even-without-watch`
(`modules/tui/test/kmet/tui/components/test_track.clj:102`) exists.

The precedent worth copying is what `reakt` already does for its own
dependencies: `record-dep!` records each dep *as read*, and the post-run
staleness check in `run-sync!` re-compares those values after the body,
because `add-watch` never replays a write that lands between the read and
the watch registration (`reakt/core.clj:127-140`). Change 1 is that same
discipline applied to the component cache.

---

## 3. Why a parent must call `render`

`Container.render`, `ChatHistory`'s `message-parts` and the document compose
flatten children through `protocols/cached-lines` / `concat-cached`
(`modules/tui/src/kmet/tui/protocols.clj:59,94`). The memo is
content-addressed: the parent reuses its previous line vector while every
child returns the **object** it returned last pass. The only way to learn
that is to call `render` on each child — which is the 6 µs hit above. The
parent's identity check is free; the child's hit is not.

---

## 4. The design

### 4.1 The invariant

> **A `track!` component's cache atom is non-nil, its `:rx` map is empty,
> and the component still owns the cache (`component/tracked?`) ⇒
> `(:result @cache-atom)` is what `render` at `(:width @cache-atom)` would
> return.**

Three clauses, three reasons:

- **`:rx` empty** keeps reactions out of scope. A cache that read a reaction
  cannot be trusted on presence alone — an input write marks the reaction
  `:dirty` without notifying the component, so the cache stays non-nil until
  the reaction re-derives. Such a cache simply does not answer the probe and
  validates as today (`rx-unchanged?`). Nothing about reactions changes.
- **ownership** is not decoration. `kmet.hikap.*` and `kmet.reakt.*` are on
  the extension allow-list (`modules/app/src/kmet/extension.clj:6`), so "an
  atom holding `{:atoms … :width w :result …}`" is not the same thing as "a
  cache `track-render` wrote": a third-party component may hand-build the
  shape. `tracked?` is exactly "the weak registry's entry for this cache
  atom's key still resolves to this component"
  (`component.clj:284`).
- **presence** is the atom half's proof, and only because change 1 closes
  the one edge where a cache can exist without its invalidation edge (§2).

With that invariant a parent needs two cheap reads per child instead of a
per-ref walk:

```clojure
(defn stable-lines
  "The lines COMPONENT would render at WIDTH if they are certainly still
   valid, else nil. Two atom reads (the cache, the weak registry) and no
   per-ref walk — see tui.md §3.2 for what this rests on."
  [component width]
  (when-let [c @(component-cache-atom component)]   ; the private cache-atom helper
    ;; Shape, not presence: component-cache-atom also returns the legacy
    ;; :cache field, and Box/Container keep their concat-cached memo
    ;; ({:key :parts :lines}) there. `:atoms` positively identifies a track!
    ;; cache — the same pre-shape guard the hit path keeps for a cache
    ;; written by an nREPL reload. `:width` alone would not do: a concat
    ;; memo lacks `:width` today, so the probe would miss it by accident.
    (when (and (contains? c :atoms)
               (empty? (:rx c))     ; a reaction dependency: nothing to trust here
               (= width (:width c))
               ;; Ownership: a hand-written cache has no entry and is never
               ;; served; a component whose entry a sweep or a stray
               ;; unregister! dropped misses here, re-renders once and
               ;; re-registers (§5).
               (tracked? component))
      (:result c))))
```

`tracked?` is `@registry` + `get` + `WeakReference.get` + one identity check
(`weak.clj:100-104`) — ~0.2–0.4 µs, so the probe is ~0.6 µs, still ~10× under
the 6 µs hit it replaces.

Box is not a "component without a cache field": it has one, and the probe
rejects it on `:atoms` shape.

### 4.2 What has to change

Only one thing in the reasoning above is not already true: **presence must
imply validity for the atom half**, and today it does not (§2's unsound
edge). Everything else — the probe, the parents' `or`, the ownership check —
is consequence.

The second way a cache can outlive its invalidation edge, a registry entry
that disappeared while the component lives, is *healed* by the ownership
clause rather than prevented: the probe misses, the body re-runs,
`weak/register!` reinstates the entry and its watches.

### 4.3 Change 1 — watch at the first read

`reakt` gains one dynamic var and one line in `tracked-deref`:

```clojure
;; reakt/core.clj
(def ^:dynamic *on-tracked-read*
  "Called with each trackable REF just before it is deref'd inside a
   tracking scope; track-render installs that ref's watch there. Nil
   outside one. A var of its own, so *tracking-scope*'s shape — and its
   two binders in reakt's test and in the bench — stay as they are."
  nil)

(defn tracked-deref [ref]
  (let [kind (ref-kind ref)]
    (when (and kind *on-tracked-read*) (*on-tracked-read* ref))   ; install, then read
    (let [v (deref ref)]
      ...)))
```

Install-then-read is the whole idea: a write in the window fires the watch
(the mid-body guard then refuses the cache) and the read that follows sees
the new value — conservative in the right direction. Volatiles and delays
stay untracked because the hook sits inside the existing `kind` test.

`track-render` binds the hook to "record REF in this pass's watched set, and
install `watch-key`'s handler on it unless already there" (a `volatile!`
set; the body is single-threaded and runs only on a miss, so this
bookkeeping never touches a frame). Its miss path becomes, in order:

```
tracker-key → snapshot prev-watched → register! empty :watched
            → bind scope + hook → body → register! full :watched
            → unwatch the branch-switch difference → write the cache unless invalidated?
```

Two positions carry weight:

- **`prev-watched` is snapshotted before the first registration.** Today the
  branch-switch diff reads the previous payload after the body. Once
  registration moves before the body, that read would see the *new* empty
  payload and the diff would be empty against itself — every ref a previous
  branch read keeps its watch forever, invalidating the component on writes
  nobody consumes (the zombie-watch accumulation this file already guards
  against).
- **Register twice; do not mutate the payload.** `weak/register!` stays the
  only payload writer: an empty `{:watched #{}}` before the body (so a
  component collected mid-body still has an entry the sweep can claim and a
  key teardown can find) and the full set after the body, through the same
  call — `register!` is documented to refresh an existing entry
  (`weak.clj:80`). No new `weak` API. A side benefit: `register!` throws when
  the key is held by a *different* live subject, so the shared-cache-atom
  guard now fires before rendering instead of after.

**A throwing body must leave nothing behind.** This is new: today a body that
throws installs no watches at all, so it leaves no state. With
install-at-read, a throw mid-body leaves the refs it already read watched
under `watch-key`, while the payload teardown and sweep read is the empty
pre-registration — the watches are unreachable and fire forever, and
`tracked?` reports a component that never rendered. So the miss path tracks
completion (`completed?` set at the end of the `try`, not in the `finally`);
on failure the `finally` unwatches the partial set and `unregister!`s,
restoring today's post-throw state exactly.

No change to `*tracking-scope*`, so `reakt/core.clj`'s docstrings at
`:64-69`/`:109-112`, `modules/reakt/test/kmet/reakt/test_reakt.clj:53` and
`scripts/bench/kmet_reakt_bench.clj:47` are untouched.

### 4.4 Change 2 — trust presence for the atom half

The hit path becomes:

```clojure
(and cache
     (contains? cache :atoms)      ; keep the pre-shape reload guard
     (= (:width cache) width)
     (reduce-kv rx-unchanged? true (:rx cache)))
```

`atoms-unchanged?` is deleted. `rx-unchanged?` stays, unchanged, and now
guards exactly the caches the probe refuses. `track-render`'s docstring
changes with them: the recorded atom values are no longer read for
validation — the `:atoms` map remains as the cache shape and the ref set,
write-only.

Add `stable-lines` (§4.1). Keep `:rx` entries `[reaction value]`: the entry
**pins** the reaction — `reakt/watch-ref` does not keep a library ref alive,
so a collected reaction would silently lose the very watch the hit rests on
("pins" was already load-bearing for validation; it stays for liveness).

### 4.5 Change 3 — the parents probe

No new API: the two flatten sites with many children ask `stable-lines`
directly and fall back to the protocol method.

* `message-parts` (`modules/app/src/kmet/ui/tui/chat_history.clj:115`) — 447
  children, the whole win:

  ```clojure
  (let [lines (or (component/stable-lines (:component m) width)
                  (protocols/render (:component m) width))]
    ...)
  ```

* `Container.render` (`components/container.clj:13`) — children not already
  shadowed by a hitting `track!` parent: the same `or` inside the `mapv`.

Both namespaces gain a `kmet.hikap.component` require; `chat_history` already
requires `kmet.tui.hikap`. A `render-or-stable` helper in
`kmet.tui.protocols` is **not** added: that namespace is deliberately
dependency-free ("Extracted from core.clj to avoid circular dependencies"),
and a helper for two one-line `or`s is not worth inverting the layering.
`stable-lines`'s docstring carries the reasoning.

`compose-document` (`tui/core.clj:1988`) is **not** converted: it renders 1–3
top-level components, so the probe would add an ownership read to buy
nothing.

Only `track!` components with atom-only caches answer the probe; `Box`, DSL
roots, reify components, containers and reaction-bearing caches fall through
to `render`, keeping their own memos (a container's memo hit is
`@cache-atom` + width + key + parts identity — a handful of comparisons, not
a tracked-ref walk).

**Constraint — `ComponentFn` must keep rendering.** A fn component's `render`
runs `begin-pass!` + `with-store` (`kmet.hikap.core`'s `ComponentFn`,
`core.clj:457`, wrapped at `kmet.tui.hikap:525`); that wrapper must run every
pass or the with-let double-use guard (`component.clj:308`) misfires.
`ComponentFn` carries no `:cache-atom`, so `stable-lines` can never answer
for it — which makes the `:atoms` shape test load-bearing, not cosmetic. If a
fn component ever gains a `track!` cache, the probe must not skip its
wrapper.

---

## 5. Correctness case analysis (change 2 in place)

| case | outcome |
|---|---|
| a tracked atom is written | its watch fires → `invalidate-cache` → cache nil → probe misses → body re-runs. |
| equal-value reset | watch no-ops (`identical?`/`=`) → cache stays → probe hits with the same result. Correct: the output would be identical. |
| mid-body write, ref read earlier this pass | watch installed at the read fires → mid-body guard refuses the cache → probe misses next pass. |
| mid-body write, ref first read after the write | the watch is installed at that first read and the read sees the new value; the cache is consistent. |
| mid-body write, refs read before the watch existed (first ever read) | impossible: install happens before the deref. |
| branch switch: a newly read ref is written *during* the same body | installed at its first read → fires → cache refused. |
| the body throws after reading refs | the partial watches are unwatched and the entry unregistered in the `finally` → no zombie watches, `tracked?` false, a retry re-registers cleanly. |
| body reads a reaction, on any branch | `:rx` non-empty → probe refuses → `render` runs `rx-unchanged?` exactly as today (`:dirty`/`:unrun`/`:failed`/`:disposed` all still caught). |
| reaction re-derives to an equal value | unchanged: `rx-unchanged?` keeps the hit. No extra body run, no frame requested. |
| body reads a reaction on one branch only | the cache describes the pass that wrote it; a pass that read none records `:rx {}` and answers the probe honestly. |
| one ref's watch is lost (wrong key, a manual `remove-watch` inside `track!` code) | **no longer compensated** — the component stays stale until something else invalidates it. This is the trade, and the residual class: reachable only through `reakt/unwatch-ref` misuse, since `track-render` watches every ref under its one lock-allocated key. |
| the registry entry is lost (a sweep of a live component, a stray `unregister!`) | **healed, not just caught**: the ownership clause misses → the body re-runs → `weak/register!` reinstates the entry and its watches. Today's net only masks this case. |
| a foreign cache atom — an extension or duck-typed component writes `{:atoms … :width … :result …}` by hand | no registry entry for its key → `tracked?` false → `render`. Presence is not proof. |
| width change | `stable-lines` compares width → miss → render rebuilds. |
| component disposed / collected | probes and watches die with it; no probe can reach it (the parent's memo is dropped with the child). |
| `Container`/`Box`/`DSL`/`ComponentFn` child | probe returns nil → `render` (unchanged), wrapper included. |

---

## 6. Semantics change that must be stated

`test-cache-verifies-values-even-without-watch` (`test_track.clj:102`) exists
to pin the lost-watch safety net. It must be **replaced**, not deleted
silently:

* new: a body that writes one of its own tracked atoms is not cached
  (install-at-read makes the write observable);
* new: a body that throws leaves no watches and no registry entry
  (`live-watch-count` unchanged, `tracked?` false);
* new: removing a watch manually now leaves the component stale — document
  the new failure mode next to the test, so nobody re-derives the invariant
  from the old comment;
* new: `stable-lines` refuses — width mismatch, nil cache, a concat-cached
  memo in a legacy `:cache` field, a hand-written cache atom, a non-track
  component, and a cache with `:rx` entries;
* new: a component whose registry entry was `unregister!`ed re-renders and
  re-registers;
* keep: equal-value reset keeps the cache; a dirty reaction still forces its
  component to re-render (through `render`, not the probe).

`modules/tui/src/kmet/tui/tui.md` §3.2 (the cache contract) must say: *the
cache trusts its invalidation edges; every tracked read is watched from the
read; there is no value re-verification. A parent may serve a child's cached
lines only after proving the cache is the child's own, and only while the
cache holds no reaction dependency — reactions keep `rx-unchanged?`.* §12
(the idle-frame invariant) is untouched by this design and needs no edit;
say so in the commit message, which must also name the guarantee change.

---

## 7. What it buys

| | now | after |
|---|---|---|
| message walk (`:parts`) | 3.9 ms | ~1.5 ms |
| compose | 4.9 ms | ~2.5 ms |
| typing frame | 7.0 ms | ~4.5 ms |
| stream frame | 9.1 ms | ~6.5 ms |
| cold frame | 3.8 s | 3.8 s (bodies; unaffected) |

The `parts` phase is 2.7 ms of `track-render` hits **plus** a 1.2 ms
parts/flags loop, and change 3 replaces only the hits: 2.7 → 447 probes ×
~0.6 µs ≈ 0.27 (ownership included), so the phase lands near 1.5 ms. The win
is a real **~2.4 ms of a 7 ms typing frame** — the largest single item in
compose; the remaining 1.2 ms is flatten/flag work this design does not
touch.

These are bb numbers. The same code is interpreted under jolt (similar shape
expected) and compiled on the JVM (where the per-hit cost is a fraction; the
probe still wins because it skips a call, but the absolute saving is small).
Measure both before and after; do not chase the JVM.

---

## 8. What this does not touch

* **The write path.** No change: no work moves to the mutating thread, so no
  `stream`-frame or append-latency regression is possible.
* **Reactions.** `rx-unchanged?` and `reakt`'s reaction lifecycle are
  unchanged.
* **The frame pass's allocations** (document copies, the write buffer,
  normalize copies) — the source of the GC pauses the benchmark reports; a
  separate axis.
* **Cold start** (3.8 s of markdown reflow) — bodies, not the walk.
* **The `Container`/`Box`/`ChatHistory` memo shapes** — they already work and
  are O(children), not O(tracked refs).

---

## 9. Plan

Three commits, in order; each lands with its tests. §5 is the acceptance
list for commit 2. **Measure between 2 and 3**: commit 2 trades the safety
net for presence, so the bench on a 400+ message session must show the
probe's saving before commit 3 spends it at the call sites. Commit 3's
counting-child test proves the probe is *used*, not that it is *worth* it.

### 9.1 Commit 1 — watch at first read

`modules/reakt/src/kmet/reakt/core.clj`

* add `*on-tracked-read*` — dynamic, nil, one sentence naming its one user
  and why it is not part of `*tracking-scope*`;
* `tracked-deref`: call the hook **before** `deref`, inside the existing
  `kind` test; one line added to each of the two docstrings.

`modules/hikap/src/kmet/hikap/component.clj`

* `track-render` miss path in the §4.3 order; the hook as described there;
  `handler` keeps its key-only shape but moves **above** the body (it closes
  over `watch-key` only, so hoisting is mechanical);
* the post-body `doseq` of `watch-ref` and the
  `(into (set (keys atom-vals)) (keys rx-refs))` payload derivation go away —
  `@seen` is that union; `:atoms`/`:rx` are still written to the cache;
* the failure path: `completed?` volatile set at the end of the `try`; the
  `finally` unwatches `@seen` and `unregister!`s when it is unset;
* no change to `weak.clj`, `remove-track-watches!`, `sweep-dead-watches!`.

Tests (`modules/tui/test/kmet/tui/components/test_track.clj`, plus a hikap
case): a write during the body is not cached; the watch exists during the
body; a re-read of the same ref adds no second watch; a throwing body leaves
nothing; a branch switch stops the previous branch's atom from invalidating;
`test-live-watch-count-follows-render-and-dispose` and `test-tracker-key-*`
keep passing.

### 9.2 Commit 2 — the probe

`modules/hikap/src/kmet/hikap/component.clj`

* delete `atoms-unchanged?`; the hit path per §4.4;
* add `stable-lines`, public, exactly as §4.1;
* keep `:rx` entries `[reaction value]` and `rx-unchanged?` with its
  docstring;
* `track-render`'s docstring: the new contract — presence proves the atom
  half, `:atoms` is the ref set only, reactions still verify, `tracked?`
  gates the probe.

Tests: the §6 list — replace `test-cache-verifies-values-even-without-watch`;
keep `test-equal-value-reset-keeps-cache`, `test-cache-pins-its-reactions`,
`test-cache-pinned-reaction-survives-…`. `tui.md` §3.2 gains the two
sentences in §6; the commit message names the guarantee change.

**Gate before commit 3:**
`bb scripts/bench/kmet_frame_bench.clj <session> 100 30 calm,typing,stream`,
recorded in §7. If `:parts` does not drop, stop here — commits 1 and 2 are
worth keeping on their own and nothing depends on 3.

### 9.3 Commit 3 — the parents

* `components/container.clj`: the `or` inside the existing `mapv`; add the
  `kmet.hikap.component` require;
* `modules/app/src/kmet/ui/tui/chat_history.clj` (`message-parts`, line 115):
  the same `or`; add the require;
* no `kmet.tui.protocols` change, no `compose-document` change.

Tests: a counting `track!` child rendered twice runs its body once and the
parts object is identical; a child write still re-renders; a
reaction-bearing child re-renders; a `ComponentFn` child still renders (the
`begin-pass!` constraint); `ChatHistory` output is byte-identical on an
unchanged input. Then bench again and put the before/after in §7.

### 9.4 Gates

`bb test-changed`, `bb lint-changed`, `bb format-check-changed` per commit;
plus `bb test kmet.tui.test-reakt-integration kmet.tui.components.test-track
kmet.tui.test-frame kmet.hikap.test-core` (the reactive layer's suites), and
the chat-history suite for commit 3.

### 9.5 The trade, and how to back it out

Accepted: a ref watch lost through `reakt/unwatch-ref` misuse leaves the
component stale until something else invalidates it (the registry-entry class
self-heals through the ownership clause — §5). Nothing else in the design can
produce a stale frame.

Backing out: commit 3 reverts alone (call sites); commit 2 reverts to
`atoms-unchanged?` and deletes `stable-lines`; commit 1 stands on its own as
a correctness fix — it is the only sound close of the read→watch window — and
should be kept regardless.

---

## 10. Alternatives, and why not

* **Push invalidation from reactions to their dependents** (a `:dependents`
  registry on `reakt` reactions, notified when a reaction goes `:dirty` or is
  disposed): sound, and it would let reaction-bearing caches answer the probe
  too. Deferred, not rejected — the hot path holds no reactions (§1.1), and
  the design carried real cost: the notification runs on the mutating thread,
  where `invalidate-cache` would request a frame for every equal-value
  re-derive (a self-sustaining loop for any frame-rate write into a reaction
  that stays equal, breaking the idle-frame invariant); each hook needs its
  own `try`/`catch` or a throwing one strands its siblings; and
  `remove-dependent!` must precede every `unwatch-ref`, because `-remove-watch`
  self-disposes manual reactions (`reakt/core.clj:629-634`), so a branch
  switch would otherwise invalidate the component that just left. If a
  profile ever shows reaction-bearing caches in this walk, that is the design,
  on that evidence.
* **Verify at write, drop the hit check** (review.md's option A): does not
  close the hole it looks like it closes — a write can land *after* the
  post-body check and *before* the watch is installed. The window shrinks; it
  does not close. Install-at-read is the sound version of the same idea.
* **Parent probe on cache non-nil without closing the hole** (option B):
  unsound — an unobserved mid-body write freezes a subtree.
* **Record the reaction's cell map and `identical?` it** (A′): defeated in
  the hot case — `-add-watch` does `(swap! cell assoc-in [:watches key] …)`
  (`reakt/core.clj:620`), and every new message registration touches the
  shared `theme-sub` cell during streaming.
* **Grow `*tracking-scope*` into a map carrying the hook**: same
  functionality, but it changes a shape four other places read (two binders,
  two docstrings) for no gain over a var of its own.
* **A `render-or-stable` helper in `kmet.tui.protocols`**: see §4.5 — that
  namespace is deliberately dependency-free, and two `or`s do not justify a
  new edge.
* **Consolidate the shared toggles (pad/hide/label) into one atom**: 4 atom
  refs → 1, ~12 % of a hit. App-wide setter churn; a possible follow-up, not a
  substitute.
* **Subtree dirty flags / chunked document**: bigger designs; the chunked
  document would also remove the remaining O(L) copy when a later part
  changes, but needs `build-frame-lines`/`diff-frame!` to walk chunks.

---

## 11. Appendix — reproducing the numbers

```
bb scripts/bench/kmet_frame_bench.clj <session> 100 30 calm,typing,stream
bb scripts/bench/kmet_frame_bench.clj <session> 100 30 cold
```

Any session with 400+ messages works; the numbers above come from a 3.9 MB
session in `~/.kmet/sessions/` (447 messages, 18,243 lines at width 100). The
benchmark excludes frames that overlap a global pause (a > 5 ms sampler
stall) and says how many it excluded; when every frame is excluded it says
so instead of giving numbers.
