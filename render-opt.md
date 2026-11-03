# The per-child render cost: watch at the read, probe at the parent

Design record for the work the frame benchmark attributes to `:parts` —
the chat history's walk over its message components. Status: **proposed**,
not started. The verdict this plan encodes (§7.1, §9): change 1 is a
correctness fix and lands regardless; change 2 is worth its risk and is the
payoff; change 3 is a measurement-driven maybe that defaults to *not
landing*.

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
→ 2.7 ms; plus the parts/flags loop → 3.9 ms. That is ~78 % of compose
(3.9 of 5.0) and 56 % of a typing frame. Almost all of the 2.7 ms is the
**per-ref walk** the hit performs (220 ns × the 12–30 refs a message reads),
not its 1157 ns of fixed overhead — which is why §7 splits the win across
commits 2 and 3 instead of crediting the probe with all of it.

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
physics of the hit is the `:atoms` half. Reaction *verification* keeps
today's `rx-unchanged?`, unchanged — no new machinery and no new risk for the
components that do read reactions (they gain only the ownership read every
hit gains, §4.4).

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

**Two edges** make a non-nil cache not yet a proof.

1. **Read before watch** (step 3): watches are installed after the body, so
   a **plain ref** read *during* the body has no watch yet and a concurrent
   write goes unobserved (the LLM stream appends to `text-atom` on a worker
   thread while the render loop composes); the cache is written with the
   value as read. The reaction half of the cache does not need this: a
   reaction marks itself `:dirty` and `rx-unchanged?`'s state test refuses
   the hit whatever the watch timing.
2. **Check before write** (step 4): the cache write is guarded by
   `(when-not @invalidated? …)` — a read-then-act. An `invalidate-cache` that
   lands between the flag read and the `reset!` is erased by the write that
   follows it, leaving a cache that disagrees with its refs. The flag cannot
   close it after the fact: it is set by the cache-atom *watch*, i.e. after
   the invalidating write has already landed, so a post-write re-check reads
   it late. The fix has to make the cache atom itself carry the signal (§4.3).

`atoms-unchanged?` is the compensation for both — the reason
`test-cache-verifies-values-even-without-watch`
(`modules/tui/test/kmet/tui/components/test_track.clj:102`) exists. Change 1
closes both; change 2 removes the net, so both must be closed first.

The precedent worth copying is what `reakt` already does for its own
dependencies: `record-dep!` records each dep *as read*, and the post-run
staleness check in `run-sync!` re-compares those values after the body,
because `add-watch` never replays a write that lands between the read and
the watch registration (`reakt/core.clj:127-140`). Change 1 is that same
discipline applied to the component cache: watch at the read, and commit
through a token the invalidator can only destroy (§4.3).

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
  both edges where a cache can exist without its invalidation edge (§2).

With that invariant a parent needs a handful of cheap reads per child
instead of a per-ref walk:

```clojure
(defn stable-lines
  "The lines COMPONENT would render at WIDTH if they are certainly still
   valid, else nil. No per-ref walk — see tui.md §3.2 for what this rests
   on. Public: the two flatten sites call it instead of `render` (§4.5)."
  [component width]
  ;; component-cache-atom (private) is nil for ComponentFn, reify and
  ;; duck-typed components — null-guard it, never `@nil`.
  (when-some [cache-atom (component-cache-atom component)]
    (when-some [c @cache-atom]
      ;; Shape, not presence: component-cache-atom also returns the legacy
      ;; :cache field, where Box/Container keep their concat-cached memo
      ;; ({:key :parts :lines}). Text keeps a real track! cache there, and
      ;; that is fine — `:atoms` identifies a track! cache regardless of the
      ;; field's name. It is the same pre-shape guard the hit path keeps for
      ;; a cache written by an nREPL reload, and it is also what rejects the
      ;; in-flight token the miss path parks here while a body runs
      ;; ({::rendering true}, §4.3). `:width` alone would not do: a concat
      ;; memo lacks `:width` today, so the probe would miss it by accident.
      (when (and (contains? c :atoms)
                 (empty? (:rx c))     ; a reaction dependency: nothing to trust here
                 (= width (:width c))
                 ;; Ownership: a hand-written cache has no entry and is never
                 ;; served. A component whose entry a stray unregister! or a
                 ;; dispose dropped misses here AND in the hit path (§4.4),
                 ;; so the body re-runs once and re-registers (§5).
                 (tracked? component))
        (:result c)))))
```

`tracked?` (`component.clj:284`) is defined below `track-render`, so both it
and `stable-lines` need a `(declare tracked?)` — the file already declares
`invalidate-cache`/`schedule-frame!` the same way.

The probe is not two atom reads: `component-cache-atom` twice (once inside
`tracked?`), `@cache-atom`, the atom's metadata (`tracker-key`'s
`::watch-key`), `@registry` + `get` + `WeakReference.get`
(`weak.clj:100-104`) and a handful of keyword field lookups — which is what
bb charges for. Treat ~0.6 µs as an estimate the §9.3 gate confirms, not a
number to plan against: what it buys is only the protocol dispatch, the
`(fn [] body)` allocation and the hit's own checks, because change 2 has
already removed the per-ref walk from `render` by then (§7).

A nil `:result` (a body that renders nothing) makes the parent's `or` fall
through to `render`, which hits the same cache and returns nil — a wasted
call, not a wrong one.

Box is not a "component without a cache field": it has one, and the probe
rejects it on `:atoms` shape.

### 4.2 What has to change

Only one thing in the reasoning above is not already true: **presence must
imply validity for the atom half**, and today it does not (§2's two edges).
Everything else — the probe, the parents' `or`, the ownership check — is
consequence.

The second way a cache can outlive its invalidation edge — a registry entry
that disappeared while the component lives — is *healed* by the ownership
clause, but only because **both** cache readers test it: the probe (§4.1)
and the hit path (§4.4). A probe miss alone would heal nothing, and the
earlier draft of this file claimed it did: the probe's fallback is `render`,
which would find the still-valid cache, hit it, and never re-register. With
`tracked?` in the hit path an unowned cache is a miss, the body re-runs, and
`weak/register!` reinstates the entry and its watches. Today that role is
played by `atoms-unchanged?` (a changed ref forces a miss); the hit-path
check replaces it at ~0.2–0.4 µs, paid only by components nobody probes.

### 4.3 Change 1 — watch at the first read

`reakt` gains one dynamic var and one line in `tracked-deref`:

```clojure
;; reakt/core.clj
(def ^:dynamic *on-tracked-read*
  "Called with each trackable REF and its kind just before it is deref'd
   inside a tracking scope; track-render installs a :plain ref's watch
   there. Nil outside one. A var of its own, so *tracking-scope*'s shape —
   and its two binders in reakt's test and in the bench — stay as they are."
  nil)

(defn tracked-deref [ref]
  (let [kind (ref-kind ref)]
    (when (and kind *on-tracked-read*)
      (*on-tracked-read* ref kind))                      ; install, then read
    (let [v (deref ref)]
      ...)))
```

Install-then-read is the whole idea: a write in the window fires the watch,
the invalidate nils the token the pass parked in the cache atom, and the
commit (§ below) then refuses to cache the pass; the read that follows sees
the new value — conservative in the right direction. Volatiles and delays
stay untracked because the hook sits inside the existing `kind` test.

**The hook installs `:plain` refs only**, and the kind is passed for that
reason — implementation found the case, and it is a correctness matter, not
an optimisation: a reaction's first derive (nil → value) fires its watchers,
so a watch installed before the read invalidates the very pass that read it
— the component never caches its first render and `:rx` comes out empty.
The reaction half needs no install-at-read anyway: a dirty reaction misses
through `rx-unchanged?`'s state test, not through watch timing. Reaction
watches therefore stay in the post-body install, which shrinks to `:rx`
reads instead of disappearing (`@seen` still records them: it is the payload
and the branch-switch set).

`track-render` binds the hook to "record REF in this pass's watched set, and
install `watch-key`'s handler on it unless already there" (a `volatile!`
set; the body is single-threaded and runs only on a miss, so this
bookkeeping never touches a frame). Its miss path becomes, in order:

```
tracker-key → snapshot prev-watched → register! prev-watched
            → park the in-flight token → bind scope + hook → body
            → register! full :watched → unwatch the branch-switch difference
            → commit through the token
```

Three positions carry weight:

- **`prev-watched` is snapshotted before the first registration.** Today the
  branch-switch diff reads the previous payload after the body. Once
  registration moves before the body, that read would see the *new*
  payload and the diff would be empty against itself — every ref a previous
  branch read keeps its watch forever, invalidating the component on writes
  nobody consumes (the zombie-watch accumulation this file already guards
  against). It is also the set the failure path unwatches below.
- **Register twice; do not mutate the payload.** `weak/register!` stays the
  only payload writer: the *previous* pass's payload before the body (so an
  entry exists from the first instruction of the body and a dispose racing
  the body has something to unwatch) and the full set after the body,
  through the same call — `register!` is documented to refresh an existing
  entry (`weak.clj:80`). No new `weak` API. A side benefit: `register!`
  throws when the key is held by a *different* live subject, so the
  shared-cache-atom guard now fires before rendering instead of after. A
  dispose racing a live render is a lifetime bug either way (it re-registers
  the entry when the body finishes, exactly as today) — the payload exists
  so it is not a *leak*.
- **The commit is a token-checked `swap!`**, not the current
  `(when-not @invalidated? (reset! …))` (§2 edge 2). The miss path parks a
  fresh token in the cache atom before the body and commits through it:

  ```clojure
  (swap! cache-atom (fn [cur] (if (identical? cur token) m cur)))
  ```

  `m` is the `{:width :atoms :rx :result}` map; the token is
  `{::rendering true}` — a map **without** `:atoms`, so both readers reject
  it by shape (§4.1) and nothing else in the codebase has to learn about it.

  Why this and not a flag: **the flag cannot be read at the right time, in
  either position.** Check-then-write is today's bug (§2). Write-then-re-check
  is worse — the render's own write fires the cache-atom watch, so
  `invalidated?` is true immediately after any write and the cache would
  never be stored. And a flag set by the invalidating *watch* lands after the
  invalidating write, so even a clever re-read races it. The token needs no
  flag and no timing argument, only the atom itself:

  - nothing invalidated → `cur` is still the token → `m` lands;
  - an invalidate landed mid-body → the atom is nil → `cur` ≠ token → the nil
    stands;
  - an invalidate lands concurrently with the commit → both writers go
    through the same atom: `swap!` retries and keeps nil if the nil came
    first, and the nil clobbers `m` if it came second.

  An invalidate that lands just *before* the token is parked is harmless: the
  body's own reads are fresh, so the cache it commits agrees with the refs it
  read. Nothing else reads a `track!` cache atom's value — the only other
  consumer, `protocols/cached-lines`, owns a different atom on the components
  it serves (`Box`/`Container`), and markdown keeps its own — so the token is
  observable only to another render of the same component, which the
  single-threaded render loop does not do. The park is the first form inside
  the `try`, so a throw anywhere after it still reaches the failure path
  below. This also deletes machinery — the `invalidated?` atom,
  `cache-watch-key`, the `add-watch`/`remove-watch` pair on the cache atom
  and the `finally` that removed it all go away, for one `reset!` and one
  `swap!` per miss. `invalidate-cache` keeps writing nil; that nil is exactly
  what makes the token check work.

**A throwing body must leave nothing behind.** Two things break that are not
true today:

- **Zombie watches.** Today a body that throws installs no watches, so a
  first-pass failure leaves no state. With install-at-read, the refs read
  before the throw are watched under `watch-key`. If the `finally` unwatched
  only those and unregistered, the refs the *previous* pass watched would
  stay installed while the payload naming them disappears with the entry —
  permanently unreachable, firing `invalidate-cache` on writes nobody reads,
  exactly the accumulation the branch-switch diff exists to prevent. Hence
  the union.
- **A rejected cache left for a presence check.** A render body only runs
  after the hit check rejected the cache, so a throwing body leaves a
  *rejected* cache in the atom. Today `atoms-unchanged?` re-rejects it on the
  next hit; after change 2 nothing would, and presence would silently
  reverse the rejection. So the failure path must clear the cache atom, not
  just the watches.
- The fix: the miss path tracks completion (`completed?` set at the end of
  the `try`, not in the `finally`), and on failure the `finally` `reset!`s the
  cache to nil (clearing the token), unwatches `(into prev-watched @seen)`
  (every ref this pass could have watched, old and new) and `unregister!`s.
  No watches, no entry, no cache, no token: the first-pass-failure state,
  which is what "nothing behind" has to mean once presence is the proof. It
  is *not* today's state after a later-pass failure (today: the previous
  entry and its watches survive) — and that difference is deliberate,
  because the surviving watches are what change 2 can no longer verify.

The pre-body `register!` sits **outside** the try: it throws
`:kmet.reakt.weak/subject-conflict` on a shared cache atom, and a `finally`
that unregisters that key would delete the *other* live subject's entry.

No change to `*tracking-scope*`: its docstring (`reakt/core.clj:64-69`), the
two binders (`modules/reakt/test/kmet/reakt/test_reakt.clj:53`,
`scripts/bench/kmet_reakt_bench.clj:47`) and `ref-kind`'s (`:104-116`) stay as
they are; only `tracked-deref`'s docstring gains a sentence.

### 4.4 Change 2 — trust presence for the atom half

The hit path becomes:

```clojure
(and cache
     (contains? cache :atoms)      ; keep the pre-shape reload guard
     (= (:width cache) width)
     (reduce-kv rx-unchanged? true (:rx cache))
     (tracked? component))         ; presence proves the atom half only for
                                   ; a cache the component still owns (§4.2)
```

`atoms-unchanged?` is deleted. `rx-unchanged?` stays, unchanged, and now
guards exactly the caches the probe refuses. `track-render`'s docstring
changes with them: the recorded atom values are no longer read for
validation — the `:atoms` map remains as the cache shape and the ref set,
write-only. Say so in a comment, or a later reader will "restore" the check
that change 2 exists to remove; if that feels too fragile, store the ref set
instead of the values (the `tracked-deref` bucket write is needed either
way, the cache need not retain them).

The ownership read is the one cost change 2 adds to every hit. On the
447-message walk the probe answers first, so those components never pay it;
the components that do are the ones nobody probes (a message's Box/Container
children, single-child containers, headers).

Keep `:rx` entries `[reaction value]`: the entry **pins** the reaction —
`reakt/watch-ref` does not keep a library ref alive, so a collected reaction
would silently lose the very watch the hit rests on ("pins" was already
load-bearing for validation; it stays for liveness).

### 4.5 Change 3 — the parents probe

`stable-lines` (§4.1) plus the two call sites — and nothing else. It is
deliberately its own commit (§9.3), because it is the only part whose value
the benchmark has to prove, and abandoning it leaves no dead code behind.

No new API: the two flatten sites with many children ask `stable-lines`
directly and fall back to the protocol method.

* `message-parts` (`modules/app/src/kmet/ui/tui/chat_history.clj:115`) — 447
  children, the only site where the probe can pay:

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
`core.clj:460`, wrapped at `kmet.tui.hikap:525`); that wrapper must run every
pass or the with-let double-use guard (`component.clj:317-331`) misfires.
`ComponentFn` carries no `:cache-atom`, so `stable-lines` can never answer
for it — which makes the `:atoms` shape test (and the `when-some` null guard
in §4.1) load-bearing, not cosmetic. If a fn component ever gains a `track!`
cache, the probe must not skip its wrapper.

---

## 5. Correctness case analysis (change 2 in place)

| case | outcome |
|---|---|
| a tracked atom is written | its watch fires → `invalidate-cache` → cache nil → probe misses → body re-runs. |
| equal-value reset | watch no-ops (`identical?`/`=`) → cache stays → probe hits with the same result. Correct: the output would be identical. |
| mid-body write, ref read earlier this pass | watch installed at the read fires → the invalidate nils the pass's token → the commit refuses the cache → probe misses next pass. |
| mid-body write, ref first read after the write | the watch is installed at that first read and the read sees the new value; the cache is consistent. |
| mid-body write, refs read before the watch existed (first ever read) | impossible: install happens before the deref. |
| branch switch: a newly read ref is written *during* the same body | installed at its first read → fires → cache refused. |
| an invalidate lands concurrently with the cache commit | the token-checked `swap!` (§4.3) cannot lose it: `swap!` retries and keeps the nil if the invalidate wrote first, and the nil clobbers `m` if it wrote second. |
| the body throws after reading refs | the `finally` clears the cache, unwatches `(into prev-watched @seen)` and unregisters → no zombie watches, no rejected cache left for a presence check, `tracked?` false, a retry is a real miss and re-registers cleanly. |
| body reads a reaction whose first derive falls in this pass | its watch is installed **after** the body (§4.3 — a reaction's derive fires its own watchers, so an at-read install would invalidate the pass that read it); `rx-unchanged?` gates the hit on the cell state, not on watch timing. |
| body reads a reaction, on any branch | `:rx` non-empty → probe refuses → `render` runs `rx-unchanged?` exactly as today (`:dirty`/`:unrun`/`:failed`/`:disposed` all still caught). |
| reaction re-derives to an equal value | unchanged: `rx-unchanged?` keeps the hit. No extra body run, no frame requested. |
| body reads a reaction on one branch only | the cache describes the pass that wrote it; a pass that read none records `:rx {}` and answers the probe honestly. |
| one ref's watch is lost (wrong key, a manual `remove-watch` inside `track!` code) | **no longer compensated** — the component stays stale until something else invalidates it. This is the trade, and the residual class: reachable only through `reakt/unwatch-ref` misuse, since `track-render` watches every ref under its one lock-allocated key. |
| the registry entry is lost (a stray `unregister!`, a dispose-then-re-render) | **healed, not just caught**: both the probe and the hit path test `tracked?` (§4.2), so the cache misses, the body re-runs, and `weak/register!` reinstates the entry and its watches. Today `atoms-unchanged?` is what heals it; the hit-path ownership check is its replacement. (A sweep cannot claim a live subject — only a stray unregister can reach this.) |
| a foreign cache atom — an extension or duck-typed component writes `{:atoms … :width … :result …}` by hand | no registry entry for its key → `tracked?` false → `render`. Presence is not proof. |
| width change | `stable-lines` compares width → miss → render rebuilds. |
| component disposed / collected | probes and watches die with it; no probe can reach it (the parent's memo is dropped with the child). |
| `Container`/`Box`/`DSL`/`ComponentFn` child | probe returns nil (no cache atom, or a non-`:atoms` memo) → `render` (unchanged), wrapper included. |

---

## 6. Semantics change that must be stated

`test-cache-verifies-values-even-without-watch` (`test_track.clj:102`) exists
to pin the lost-watch safety net. It must be **replaced**, not deleted
silently. First, the one test that replaces the net itself:

* new: **the differential staleness test** — the oracle the net used to be,
  moved into the suite. For each message component family (user, assistant,
  tool, custom, and a `track-deps` user like `ExpandableText`): render it,
  mutate an input the body reads (`text-atom`, `theme-sub`, the pad atom, a
  tool's `content`/`ended-at`), render again, and compare against a **fresh**
  component built from the same inputs. The mutated case must equal the
  fresh render; the unmutated case must be `identical?` and equal. A stale
  cache shows up as a diff, which the benchmark cannot produce.

Then the rest:

* new: a body that writes one of its own tracked atoms is not cached **and
  requests a frame** (install-at-read makes the write observable — see §8);
* new: a body that throws leaves no watches, no registry entry and **no
  cache** (`live-watch-count` unchanged, `tracked?` false, a stale
  pre-throw cache is gone);
* new: removing a watch manually now leaves the component stale — document
  the new failure mode next to the test, so nobody re-derives the invariant
  from the old comment;
* new: `stable-lines` refuses — width mismatch, nil cache, a component with
  no cache atom at all (a `ComponentFn`, a reify), a concat-cached memo in a
  legacy `:cache` field, a hand-written cache atom, a non-track component,
  and a cache with `:rx` entries;
* new: a component whose registry entry was `unregister!`ed re-renders and
  re-registers — **through the hit path**, not only through the probe (§4.2);
* keep: equal-value reset keeps the cache; a dirty reaction still forces its
  component to re-render (through `render`, not the probe).

The commit race (§2 edge 2) is argued rather than tested: the token check
(§4.3) makes it a property of one atom with two writers, not a timing
window, but a test that exercises it would need a writer thread interleaved
with the commit. Its *consequence* is covered by the differential test above
and by the throw tests (a cache that fails either is never served).

A reload caveat: `contains? :atoms` guards shape, not provenance, and the
shape does not change. A session that reloads this namespace keeps serving
caches written by the **old** code — which could be stale at write time, the
very edge change 1 closes — until their next invalidation. Restart before
benchmarking (§9.2), or clear the caches.

`tui.md` §3.2's contract sentence ("every tracked read is watched from the
read") and its self-mutating-atom rule land with **1a**, where the
frame-request behavior arrives; the rest of the §3.2 sentences land with
commit 2 *the cache trusts its invalidation edges, there is no value
re-verification, and the cache is served only while the component still owns
it; a parent may serve a child's cached lines only after proving the cache
is the child's own, and only while it holds no reaction dependency —
reactions keep `rx-unchanged?`*. §12 (the idle-frame invariant) is untouched
by this design and needs no edit; say so in commit 2's message, which must
also name the guarantee change.

---

## 7. What it buys

The win is split across commits 2 and 3 and must be measured that way: **the
saving is the per-ref walk, which commit 2 removes; commit 3 only removes the
call and the residuals around it.** The predictions below are for the gate in
§9.2 to confirm or kill, not measurements.

| | now | after commit 2 (predicted) | after commit 3 (predicted) |
|---|---|---|---|
| message walk (`:parts`) | 3.9 ms | ~1.6–1.8 ms | ~1.4–1.5 ms |
| compose | 4.9 ms | ~2.6–2.8 ms | ~2.5 ms |
| typing frame | 7.0 ms | ~4.7–4.9 ms | ~4.5 ms |
| stream frame | 9.1 ms | ~6.7–6.9 ms | ~6.5 ms |
| cold frame | 3.8 s | 3.8 s | 3.8 s (bodies; unaffected) |

The `parts` phase is 2.7 ms of `track-render` hits plus a 1.2 ms parts/flags
loop. Commit 2 removes the walk: a hit keeps its 1157 ns of fixed overhead
(`@cache-atom`, `contains?`, width, the now-empty `reduce-kv`s, plus the new
ownership read), so 2.7 ms → ~447 × 1.2–1.5 µs ≈ 0.55–0.7 ms and the phase
lands near 1.7 ms. Commit 3 then removes the protocol dispatch, the
`(fn [] body)` allocation and that residual check: ~447 × 0.5 µs ≈ 0.2 ms.

So the design's real payoff — **~2.2 ms of a 7 ms typing frame**, the largest
single item in compose — belongs to commits 1+2, and commit 3 buys ~3 % of
the frame. That is the number the §9.3 gate has to justify the probe against,
and the remaining 1.2 ms is flatten/flag work this design does not touch.

These are bb numbers. The same code is interpreted under jolt (similar shape
expected) and compiled on the JVM (where the per-hit cost is a fraction; the
probe still wins because it skips a call, but the absolute saving is small).
Measure both before and after; do not chase the JVM.

### 7.1 Is it worth building?

| change | worth it? | because |
|---|---|---|
| 1 — watch at the read, atomic commit, failure path | **yes, unconditionally** | it closes a real race (a worker thread writes while the render loop composes) and is the only sound closure of it. Ship it even if nobody ever deletes `atoms-unchanged?`. |
| 2 — trust presence | **yes, after 1 has run in the app** | ~2.2 ms of 7 ms for a three-line deletion plus its docstrings, on the one session shape the benchmark models. |
| 3 — the probe | **no, by default** | ~0.2 ms (3 %) for the most exposed code in the change: a new public fn, two call sites, an ownership read on every hit, a `ComponentFn`-shaped footgun. |

Three things would change that answer, and they are worth knowing before
committing to change 2:

- **What is the *typical* session, not the benchmark's?** 447 messages /
  18 243 lines is the measured case; the doc does not say it is p99. `:parts`
  is O(messages), so at ~100 messages change 2 buys ~0.5 ms and the honest
  answer becomes "land change 1, keep the net". At 400+ it is a clear win,
  and sessions only get longer.
- **Which host matters?** The case rests on bb/Termux, where the walk is
  interpreted and worth 2.2 ms. Under jolt expect a similar shape; on the JVM
  the absolute saving is small ("do not chase the JVM").
- **How much `track!` does the ecosystem write?** Change 2 replaces a *local*
  net with a *global* invariant (§9.5). The more extension components that
  exist, the more that invariant has to be held up by documentation rather
  than by code — still worth doing, but the `tui.md` rule must land with it.

The risk is not "too risky": it is concentrated in one function
(`track-render`) and it is reducible by sequencing (§9 lands change 1 twice
with the net still up) and by one test the benchmark cannot replace — the
differential staleness test in §6.

---

## 8. Blast radius: what changes beyond the cache

The cache work leaves the hot-path budgets alone; one frame-scheduling
consequence does travel outside it.

* **The write path.** No change: no work moves to the mutating thread, so no
  `stream`-frame or append-latency regression is possible.
* **Frame requests from self-writing bodies — this one *does* change.** A
  render body that writes a ref it also reads tracked now fires its own
  watch: the cache is skipped for that pass and a frame is requested. Today
  such a body silently relies on the next hit check to miss. Converging
  bodies pay one extra compose once (`custom_message.clj:45-46` writes
  `applied-theme-atom` in the body → two full composes per theme switch;
  `tool_execution.clj:291-292`'s `:timer-id` swap → one extra frame per tool
  start). A body that writes a *new* value on every pass would spin the
  frame loop instead of only missing its cache — a loud version of a latent
  bug, which is the right trade, but it is a behavior change and §6 asks
  `tui.md` §3.2 to say so.
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

Four commits: **1a**, **1b**, **2**, and an optional **3**. The split is the
risk control, not bookkeeping:

- change 1 is a correctness fix, and it lands in two steps **with
  `atoms-unchanged?` still in place**, so each step is validated against the
  old, verified behavior: the hit path and the invalidation mechanism must not
  move, and the only intended behavior change at 1a is the §8 frame request
  from a body that writes a ref it reads;
- commit 2 is the trust change, and its gate is a **test** (the differential
  staleness test, §6), because the benchmark can only prove the perf
  hypothesis and says nothing about soundness;
- commit 3 is the probe, and it lands only if its *marginal* gain clears the
  noise. §7.1 says why it defaults to not landing.

§5 is the acceptance list for commit 2. Measured against a committed `now`
figure for the same session, on a restarted session (a reloaded one still
serves caches written by the old code — §6).

### 9.1a Commit 1a — watch at the first read

`modules/reakt/src/kmet/reakt/core.clj`

* add `*on-tracked-read*` — dynamic, nil, one sentence naming its one user
  and why it is not part of `*tracking-scope*`;
* `tracked-deref`: call the hook **before** `deref`, inside the existing
  `kind` test; its docstring gains a sentence naming the hook and the
  install-then-read ordering.

`modules/hikap/src/kmet/hikap/component.clj`

* `track-render` binds the hook; `handler` keeps its key-only shape but moves
  **above** the body (it closes over `watch-key` only, so hoisting is
  mechanical);
* the pre-body registration: snapshot `prev-watched`, then `register!
  prev-watched` **outside the `try`** (a `subject-conflict` throw must not let
  the `finally` delete the other subject's entry) — §4.3;
* the post-body `watch-ref` loop narrows to the `:rx` reads and the
  `(into (set (keys atom-vals)) (keys rx-refs))` payload derivation goes away
  (`@seen` is that union) — install-at-read is for `:plain` only, because a
  reaction's first derive fires its own watchers (§4.3); `:atoms`/`:rx` are
  still written to the cache;
* **keep** `invalidated?`, `cache-watch-key`, the cache-atom watch, the
  check-then-`reset!` write and `atoms-unchanged?`. Step 1b replaces them;
  keeping them is what isolates this step — the hit path and the invalidation
  mechanism stay as they are, so any suite or bench movement comes from
  install-at-read alone;
* no change to `weak.clj`, `remove-track-watches!`, `sweep-dead-watches!`.

Expected behavior change, and the only one: a body that writes a ref it reads
now fires its own watch, so that pass is not cached and a frame is requested
(§8 — `custom_message.clj:45`, `tool_execution.clj:291`). The *render* path is
untouched, so `:parts`, compose and the frame numbers on the bench scenarios
must be flat (they do not switch themes or start tools); a movement there
means the rewrite is not a no-op.

Tests (`modules/tui/test/kmet/tui/components/test_track.clj`, plus a hikap
case): the watch exists during the body; a re-read of the same ref adds no
second watch; a branch switch stops the previous branch's atom from
invalidating; `test-live-watch-count-follows-render-and-dispose` and
`test-tracker-key-*` keep passing; `test-cache-verifies-values-even-without-watch`
still passes (the net is still there).

### 9.1b Commit 1b — atomic commit and the failure path (still with the net)

* delete `invalidated?`, `cache-watch-key` and the cache-atom
  `add-watch`/`remove-watch` pair: the in-flight token (§4.3) replaces them,
  parked as the first form inside the `try`;
* the cache write becomes the token-checked `swap!` (§4.3);
* the failure path: `completed?` volatile set at the end of the `try`; the
  `finally` clears the token, unwatches `(into prev-watched @seen)` and
  `unregister!`s when it is unset;
* `(declare tracked?)` next to the existing `(declare invalidate-cache
  schedule-frame!)` — `tracked?` is defined below `track-render` and is used
  by the hit path from commit 2 on.

Expected behavior changes, both stated in the commit message: a body that
writes a ref it reads now schedules a frame (§8 — arrived at 1a, kept here),
and a throwing body clears the cache and unregisters instead of leaving a
rejected cache behind. With the net still in place the *rendered output* is
unchanged (a rejected cache and a nil cache both force a re-run); commit 2 is
what makes the clear load-bearing. The bench must stay flat: the walk is
still there.

Tests: a mid-body write leaves no cache; a throwing body leaves no watches,
no entry and no cache (a second render re-runs the body); a throwing body
with a *previous* pass's cache leaves no cache and no zombie watches (the
union unwatch — the case the first draft of this file got wrong).

### 9.2 Commit 2 — trust presence (the payoff)

`modules/hikap/src/kmet/hikap/component.clj`

* delete `atoms-unchanged?`; the hit path per §4.4 (ownership included);
* keep `:rx` entries `[reaction value]` and `rx-unchanged?` with its
  docstring;
* `track-render`'s docstring: the new contract — presence proves the atom
  half, `:atoms` is the ref set only, reactions still verify, `tracked?`
  gates both readers.

**Soundness gate — the differential staleness test (§6).** For each message
component family: render it, mutate an input, render again, and compare with
a *fresh* component built from the same inputs; the no-change case must be
identical (and `identical?` for the parent's memo). This is the net that
`atoms-unchanged?` used to be, moved into the suite — the benchmark cannot
find a stale cache. Then the rest of the §6 list: replace
`test-cache-verifies-values-even-without-watch`; keep
`test-equal-value-reset-keeps-cache`, `test-cache-pins-its-reactions`,
`test-cache-pinned-reaction-survives-…`. `tui.md` §3.2 gains the sentences in
§6; the commit message names the guarantee change.

**Perf gate:** `bb scripts/bench/kmet_frame_bench.clj <session> 100 30
calm,typing,stream` on a fresh session, recorded in §7 against the `now`
column. Expect `:parts` at ~1.7 ms (the walk is gone). If it has *not*
dropped, commit 2 does not pay and reverts — nothing else depends on it.

**Then let it bake** in the app before commit 3: the residual failure mode is
silent, so real use (streaming, theme switches, tool lifecycles) is the test
the suite approximates. Commit 3 can wait for it; nothing in 1a/1b/2 needs
it.

### 9.3 Commit 3 — the parents (default: not landed)

`stable-lines` (§4.1) plus the two call sites. **Lands only if** the probe's
marginal gain over commit 2's numbers is above the noise — say ≥ 0.15 ms of
`:parts` — measured on the same session, on a restarted app, after commit 2
has baked. Below that, this file records the numbers and the commit does not
happen: a 3 % frame win does not buy the most exposed code in the change
(§7.1).

* `modules/hikap/src/kmet/hikap/component.clj`: add `stable-lines`, public,
  as §4.1 (the `when-some` null guard is not optional — a `ComponentFn`
  child has no cache atom);
* `components/container.clj`: the `or` inside the existing `mapv`; add the
  `kmet.hikap.component` require;
* `modules/app/src/kmet/ui/tui/chat_history.clj` (`message-parts`, line 115):
  the same `or`; add the require;
* no `kmet.tui.protocols` change, no `compose-document` change.

Tests: a counting `track!` child **whose `render` (not body) increments a
counter** — the second pass must leave it at 1, which a body counter cannot
show (a cache hit runs no body either); `stable-lines` refuses (§6 list); a
child write still re-renders; a reaction-bearing child re-renders; a
`ComponentFn` child still renders (the `begin-pass!` constraint);
`ChatHistory` output is byte-identical on an unchanged input. Then bench
again and put the before/after in §7.

### 9.4 Gates

`bb test-changed`, `bb lint-changed`, `bb format-check-changed` per commit;
plus `bb test kmet.tui.test-reakt-integration kmet.tui.components.test-track
kmet.tui.test-frame kmet.hikap.test-hikap-core kmet.hikap.test-hikap-component`
(the reactive layer's suites), and the chat-history suite (`kmet.ui.tui.test-chat-history`)
for commit 3. Commits 1a and 1b additionally require a
**flat bench** (they are not supposed to move the numbers), and commit 2
requires the differential staleness test (§6) ahead of the perf gate.

### 9.5 The trade, and how to back it out

Accepted: a ref watch lost through `reakt/unwatch-ref` misuse leaves the
component stale until something else invalidates it. That is the only
residual class, and it is narrow because `track-render` watches every ref
under its one lock-allocated key. The registry-entry class is *healed*, not
accepted: both readers test `tracked?` (§4.2).

What change 2 actually trades is bigger than that one class, and it should be
stated plainly: it replaces a **local** net (re-deref every atom, every hit —
correct even if a watch is lost, and it cannot decay) with a **global**
invariant ("every tracked read is watched from the read, nothing else nils
the cache, no component hand-builds one") held up by comments and tests
across `hikap`, `tui` and every extension. What the net catches today, it
catches without anyone having to think about it: a lost or never-installed
watch, an invalidation a branch switch dropped, a write that raced the pass —
the next hit re-derives. After change 2 those serve stale lines until the
*next* something else invalidates the component, and nothing reports it. The
counter-argument is why it is still right: the net exists *only* because of
the read→watch gap, and once that gap is closed (1a/1b), keeping it makes the
rule unknowable — "the cache trusts its watches" is a rule a component
author can hold; "…except it also re-derefs everything" is not. The
compensating control is the differential test (§6), which must live in the
suite, not in the benchmark.

Backing out: commit 3 reverts alone (one new fn, two call sites, no behavior
change); commit 2 reverts to `atoms-unchanged?` on the same hit path; commits
1a and 1b are a correctness fix and should be kept regardless — they close
the read→watch window *and* the check→write window, and they remove the
cache-atom watch machinery either way. Equivalently: **1a + 1b + 2 with no
commit 3 is the default end state** (§7.1, §10), and it is what ships unless
the §9.3 gate clears.

---

## 10. Alternatives, and why not

* **Stopping at commit 2** — presence trust with no probe. Not a fallback but
  the **default**: it is worth ~2.2 ms of the ~2.4 ms this file is about
  (§7, §7.1), with none of the probe's cost, and §9.3 has to justify
  departing from it. This file should end up recording which of the two
  shipped.
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
