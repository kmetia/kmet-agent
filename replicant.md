# Borrowing from Replicant — working plan

Source study: [cjohansen/replicant](https://github.com/cjohansen/replicant)
(renderer library: whole-tree hiccup in, keyed diff out, node-attached
lifecycle, alias registry). Study artifacts and benchmarks live in
`target/replicant-study/` — disposable, not tracked.

This file is a plan, not a decision record: item 1 is **implemented**
(result in its section), item 2 is an accepted direction, item 3 is
**TO REVIEW** and must not be implemented before an explicit decision.

Measured headless via `target/replicant-study/bench.bb` (bb, this checkout):
a `:v-stack` of N keyed `[:text]` children inside an uncached fn body
(`:rerun-without-deps?`), 200 passes after a warm render:

| N | current pass | equal-tree `=` walk | cached-line render floor |
|---|---|---|---|
| 1000 | 8.6 ms | 0.19 ms | 0.61 ms |
| 4000 | 35.7 ms | 0.48 ms | 2.45 ms |

Counters per 200 passes at N=1000: `{bodies-run 200, constructs 0,
reuses 400200}` — the whole cost is parse + item-map allocation + bucket
diffing, paid even though not one node changed.

---

## Interaction with `leaks.md` (the memory plan)

`leaks.md` fixes reachability (weak `track!`/`reakt`, mode-exit teardown,
deterministic disposal): Pass 1 + Stages A-D are implemented (see its
Stage C/D results). **None of the three items below obsoleted any
`leaks.md` topic** —
this plan fixes re-diffing cost, `leaks.md` fixes retention — but each item
couples in exactly one place, and item 2 adds one requirement to that plan:

| item | coupling |
|---|---|
| 1 — skip | Adds strong self-retention (`ComponentFn.last-tree`, container `:nodes`): fields of the component itself, so no new GC root and no new leak class. It *enlarged the interim leak weight* of any tree still rooted by a stale reakt watch by roughly one tree copy until Stage C landed; the skip was never allowed to argue the weak work was unnecessary. |
| 2 — aliases | A new process-global root — a new entry in finding 10's "global roots" class. Extension aliases must register through the existing deregistration path (`extensions.cljc` load/unload) so `/reload`/unload removes them, or stale alias fns keep old extension state and old code alive (`leaks.md` review correction: a weak registry alone does not release what a strong root holds). Register top-level fns, not closures. |
| 3 — hooks/memory | A third cleanup-declaration site. If it ever lands: every resource acquired in `on-mount` must be released on *every* removal path (unmount hook, dispose, display-leaf rebuild); node memory is a strong stamp field and therefore not GC-managed — release is the node's disposal; and it must satisfy `leaks.md`'s Layer-2 rule (one owner, explicit dispose). The bar set by the dropped `hiccup/adopt` and `container-clear` alternatives applies. |

Cross-updates (recorded as the work landed):

- `leaks.md` Stage D's `--debug` exit report prints the weak-registry
  survivor counts (live track! components, live reactions, armed timers),
  not `hiccup/counters`; item 1's `:skips` rides `hiccup/counters` for
  tests and debug inspection.
- The retain-previous-input rule is documented in tui.md §2.3/§2.5
  (`last-tree`, the stamp's `:nodes`), together with the skip.
- `leaks.md` §2.12's open extension-unload question gains its first
  concrete client under item 2 (not started).

### Recommended execution order (these two plans combined)

`leaks.md`'s dependency chain is satisfied and the memory plan is closed:
Stages C and D and replicant item 1 are implemented (see their result
sections). What remains is the aliases item, which merges only after one
real consumer validates the API, and the item-3 review gate:

1. **`leaks.md` Stage C** — done (weak `reakt`).
2. **`leaks.md` Stage D** — done (exit report, hardening, docs).
3. **Item 1** — done (the skip; see its result).
4. **Item 2** — aliases, with extension deregistration from the start;
   merge after one real consumer validates the API.
5. **Item 3** — only if the review gate passes; not scheduled.

Escape hatch: item 1 has no functional dependency on C and may go first
if the perf win is wanted early — the only cost is that trees already
rooted before Stage C provisionally retain one more copy. Never
interleave C and item 1 in one change; each carries its own measurements
and gates. **Taken:** item 1 landed first (see its result section), then
C and D closed the memory plan in order.

---

## 1. Unchanged-subtree skip — "do not parse what hasn't changed"

**Priority: first. Implemented** (result below). Localized to
`kmet.tui.hiccup`, testable headless.

### Motivation

Replicant stores the original hiccup sexp on every vdom node and skips
parse + diff for a whole subtree when the new node is `=` to it
(`replicant.core/unchanged?`; their perf notes: "Do not parse hiccup nodes
known to not have changed" and "Keep fully realized vdom around between
renders"). We re-parse and re-diff every node on every pass. That is
exactly the cost profile of the pattern `tui.md` §2.5 blesses — an
uncached body re-runs on every frame, and a parent re-render with equal
children re-diffs the subtree. The equal-tree check is ~1% of the cost it
can avoid; on a changed tree it is cheaper still (mismatch usually ends
the walk early).

The skip is *not* a replacement for reaction caching: reactions skip the
body (idle UI), the skip avoids re-diffing a body's output when it ran
anyway and produced an equal tree.

### Design

Two storage points, no new public API:

1. **`ComponentFn`** — add a `last-tree` atom (built in
   `make-component-fn`, field added to the `defcomponent`). In the
   reaction body, before `reconcile!`:

   ```clojure
   ;; keep the reaction's return value convention: the body still returns
   ;; the kept items, so nothing that observes the reaction output changes
   (if (= tree @last-tree)
     (do (bump! :skips) @kids)
     (let [kept (reconcile! kids tree)]
       (reset! last-tree tree)
       kept))
   ```

2. **Containers** (tags with `:lens`) — store the raw children on the
   `:dsl/meta` stamp as `:nodes` (alongside `:props`). Stamp creation in
   `stamp!` gains the field; `construct-item` writes it after the initial
   `reconcile-into`. In `reuse-or-build`'s container branch:

   ```clojure
   (if (= nodes (:nodes (stamped-meta (:c prev))))
     (bump! :skips)                       ;; children untouched — still apply props below
     (do (reconcile-into ...)
         (swap! (:dsl/meta (:c prev)) assoc :nodes nodes)))
   ```

   Props are still applied through `:apply` when they changed; the skip
   covers children only, and `props-same?` keeps its existing meaning.

Rules and edge cases to pin:

- **Skip the diff, never the render.** The render loop still calls
  `protocols/render` on every kept item at the new width; a resize must
  reflow even when the tree is `=`. This falls out of the design, but
  needs a test.
- **Equality is `=` on the raw tree**, the same value the body built.
  Trees containing fresh fn literals (a new `(fn …)` per pass) never
  compare equal — the existing hoisting guidance (`tui.md` §2.5) is now
  also what makes the skip fire. Callbacks in `:on-submit` etc. are the
  usual offenders.
- **`reconcile!` (`hiccup.clj`) is a public fn** called by tests/tools;
  keep its contract (diff + install + return kept items). The skip lives
  in the `ComponentFn` body, not inside `reconcile!`, so a caller that
  genuinely wants a diff still gets one. (Alternative: an extra arity
  taking the last-tree atom; not needed for v1.)
- First pass: `last-tree` nil vs `tree` nil — skipping an empty tree is
  harmless; no special case.
- Foreign spliced records / entry maps are unaffected: their identity is
  already the equality.
- `compile-tree` / `render-lines` fresh-compile paths are unaffected.
- Duplicate-key validation is skipped on an equal tree — it already
  passed for that exact tree, so nothing is lost.

### Implementation checklist

- `src/kmet/tui/hiccup.clj`: `zero-counters` + `bump!` gain `:skips`;
  `ComponentFn` field + `make-component-fn`; container `:nodes` in
  `stamp!` / `construct-item` / `reuse-or-build`.
- `src/kmet/tui/tui.md`: §2.3 (container reuse rule), §2.5 (skip + the
  hoisting caveat), §11 counters list.
- Tests in `test/kmet/tui/test_hiccup.clj`:
  - same tree twice → `:skips` grows, `:reuses` stays flat, kids
    `identical?`;
  - changed leaf inside an otherwise equal tree → only that subtree
    rebuilds/re-applies, skip does not mask a change;
  - keyed reorder still reuses records (no regression);
  - width change with equal tree → lines re-render at the new width;
  - container `:apply` still runs when props change but nodes are equal;
  - uncached fn body returning an equal fresh tree per pass → one pass
    diffed, subsequent passes skipped.
- Bench re-run (`target/replicant-study/bench.bb`) before/after; record
  numbers in the commit message. Target: the 4000-node case drops from
  ~36 ms to near the 2.5 ms render floor.

### Risks

- **Retained tree memory**: we keep the previous raw tree per component
  and per container, where today only records are retained. Bounded by
  the UI tree (message content stays in records); acceptable, but worth
  a note if a transcript-scale tree ever keeps two full copies. Not a
  new GC root — the fields belong to the component being collected, so
  `leaks.md`'s weak-layer work was unaffected; the interim footprint that
  grew before Stage C is reclaimed now (see the interaction section).
- **`=` on huge equal trees is O(n)**, ~0.5 ms per 4000 nodes — paid to
  save ~33 ms. Bounded loss on changed trees; no pathological case.
- The skip can hide a *non-idempotent tree builder*: a body that mutates
  state while building and then returns an equal tree no longer triggers
  a re-diff. Bodies are already required to be pure per pass (§2.5), so
  this only fails loudly in code that was already wrong.

### Acceptance

No behavior change beyond performance; all existing hiccup tests pass;
new `:skips` counter exercised; measured gain on the bench.

### Result (implemented)

- `unchanged-tree?` (=-equal + key-aware metadata walk), `last-tree` on
  `ComponentFn`, raw `:nodes` on the container stamp, `:skips` counter.
  Tests: `unchanged-tree-takes-the-skip`,
  `changed-child-diffs-unchanged-siblings-skip`,
  `metadata-key-change-defeats-the-skip`,
  `equal-children-still-apply-container-props`,
  `skip-still-rerenders-at-a-new-width`,
  `skipped-subtree-still-updates-tracked-fn-children` in
  `test/kmet/tui/test_hiccup.clj` (92 tests / 299 assertions green).
- Bench (`target/replicant-study/bench*.bb`, 200 passes after a warm
  render): unchanged 1000-node tree 8.1 → 1.7 ms/pass; 4000-node
  35.0 → 6.7 ms/pass (5.2×; the floor is ~2.5 ms of cached-item render).
  Worst case — only the last node changed, so the `=` walk runs full
  length before failing — 4000 nodes 35.0 → 37.0 ms (+~5%, one extra
  walk).
- Docs: `tui.md` §2.3 (both layers, the key caveat, the render-vs-diff
  rule), §2.5 (the uncached-body path), §11 (`:skips`).
- Known consequence recorded in the interaction section: pre-Stage-C
  (`leaks.md`) a stale-watch-rooted tree now retains one more tree copy.
- Review pass: fixed a pre-existing ref race found while testing the skip.
  `diff-items` constructs a keyed remount (filling the element's `:ref`)
  before retiring the leftover, so the unguarded clear wiped the fresh
  fill — the remount's ref stayed nil forever; the same wipe hit handles
  handed between elements in one pass. Clears now go through
  `release-ref!`, which clears only while the handle still targets the
  component letting it go (`DslRef/-ref-target`). Tests:
  `ref-survives-a-key-change-remount`,
  `ref-handles-swapped-between-elements`,
  `abandoned-ref-handle-still-clears` (all three fail on the unguarded
  code).

---

## 2. Aliases — qualified-keyword tags resolved through a registry

**Priority: second.** Clear design seam, needs one concrete extension use
case validated before merge.

### Motivation

Replicant aliases: a namespaced keyword like `[:ui/btn props children]`
resolves through a registry to `(fn [attrs children] hiccup)`. It gives
reusable tag-level components defined anywhere, without coupling the tree
site to the provider's namespace. We have exactly two head kinds today —
closed-table host keywords and fn **values** (deliberately not symbols,
since trees are runtime data and implicit symbol resolution couples the
DSL to `*ns*`). A **qualified keyword cannot collide with a host tag**
and needs no `*ns*` resolution if the lookup is an explicit registry, so
it fits the same rationale.

What it buys us:

- **Extensions contribute composable UI by name**: a panel/widget can
  embed `[:fox/status-chip {:model m} child]` in its tree without
  requiring the provider's var. Today the only options are a foreign
  record splice (caller constructs it) or a fn value (caller requires
  the namespace). `ui-set-widget` already establishes a name registry
  for whole panels; aliases extend the same idea to subtrees.
- **Stable identity for data-described UI**: the alias keyword is the
  match kind, so keyed reuse and `with-let`/reaction state survive
  reorders exactly like host tags.
- Replicant's `defalias` var trick also works for us: the macro defines a
  var whose *value* is the alias keyword, so `[fox/chip …]` in Clojure
  code evaluates the var → keyword → registry, keeping go-to-definition
  and explicit requires where the author wants them. No symbol ever
  reaches the hiccup layer.

### Design (v1, deliberately thin)

New namespace `kmet.tui.alias` (no dependencies; `hiccup` requires it):

```clojure
(defonce registry (atom {}))
(defn register! [alias-kw f])        ; f: (fn [attrs children] tree)
(defn registered [] @registry)       ; registry snapshot (tests/did-you-mean)
(defmacro defalias [name & forms])   ; registers :<ns>/<name>, defs var = keyword
```

Alias fns receive `attrs` (the props map minus `:key`/`:ref`, like fn
heads) and `children` (a flat vector of raw child nodes, `[]` when none).
Resolution in `parse-node`'s vector branch, before `parse-host`:

- Qualified keyword → registry lookup.
  - Found → a `::fncomp`-shaped desired item with `:mkey` = `tag`
    (alias keyword, or `{::user-key k ::kind ::alias}` when keyed),
    `:props`/`:ref` handling identical to fn heads, and `:f` a small
    wrapper that dispatches both `(f p)` / `(f p ct)` arities to
    `(alias-fn p (or ct []))`. **Children must ride `:ctree`, not a
    closure** — on reuse the wrapper keeps its previous `:f`, so a
    closure capturing children would freeze them (this is the one
    non-obvious trap in the implementation).
  - Missing → throw `ex-info` naming the alias and the registered
    aliases, consistent with the unknown-host-tag did-you-mean contract.
- Unqualified keyword → `parse-host` as today.

Because the result is an ordinary `::fncomp`, everything composes for
free: reactions/`track!` inside alias bodies, `with-let` state,
`hiccup/ref`, keyed reuse, nesting (an alias body may contain aliases),
and — once item 1 lands — the unchanged-tree skip.

Explicitly **not** in v1: Replicant's `.class`/`#id` suffix parsing (no
terminal classes), attr merging of the caller's tag with the alias's
root element, `:aliases` per-render option (global registry only), and
per-alias exception isolation (loud-crash contract; revisit only if
extension aliases ship and a broken one can take down a long session).

### Implementation checklist

- New `src/kmet/tui/alias.clj`; `hiccup.clj` requires it, `parse-node`
  gains the qualified-keyword branch; `tui.md` §1 namespace table, §2.1
  head kinds, §2.2 note that aliases are not host elements.
- `src/kmet/extension.md`: extension authors register aliases in their
  load path; document the `(fn [attrs children])` contract, the
  `defalias` var trick, and the no-class-suffix rule.
- Extension lifecycle (`leaks.md` finding 10): registration must go
  through the existing extension deregistration fns (`extensions.cljc`)
  so unload/`/reload` removes stale aliases; `register!` overwrites;
  document "register top-level fns, not closures over extension state".
- Tests: new `test/kmet/tui/test_alias.clj` registered in
  `kmet.tasks.runner/all-namespaces` — resolution, children delivery,
  keyed reuse/with-let state across passes, nested alias, unknown alias
  throws with did-you-mean, `defalias` var equals the keyword, registry
  isolation fixture (`reset!` the registry between tests).
- One real consumer: convert one existing app/ext component to an alias
  to validate ergonomics before declaring the API stable.

### Risks

- A third head kind is surface area; mitigate by keeping the registry
  global and the fn signature identical to fn heads.
- Global mutable registry: test isolation, load-order (an extension
  alias used before its namespace loads throws at reconcile — the error
  message must say which alias and, when possible, which provider), and
  reload — without deregistration the registry is a new global root
  (`leaks.md` finding 10).
- Registering the same keyword twice: `register!` should overwrite
  deliberately (last wins) and be documented; no silent merging.

---

## 3. Node lifecycle hooks + per-node memory — **TO REVIEW**

> **Status: TO REVIEW. Do not implement before a decision.** This item
> has open questions that can change the lifecycle contract; it stays a
> proposal until a concrete need is demonstrated.

### What Replicant does

Lifecycle behavior attached to *any node* as data — `:replicant/on-mount`,
`:replicant/on-unmount`, `:replicant/on-render` props receiving a map with
the node, the lifecycle kind, and a `remember` fn; `:replicant/memory` is
the previous value (a WeakMap keyed by DOM node). It replaces components
as the place for mount/unmount side effects.

Our analog is thinner: `with-let` (per fn-component instance) and record
`dispose` — both component-shaped. There is no way to say "when *this
element* mounts/leaves, do X" on a leaf.

### Sketch (for discussion only)

- Namespaced props on any element, e.g. `:tui/on-mount`,
  `:tui/on-unmount`, `:tui/on-render`; callback receives
  `{:tui/trigger :tui.trigger/life-cycle, :tui/life-cycle
  (:tui.lifecycle/mount | /unmount | /update), :tui/node <record>,
  :tui/remember f, :tui/memory v}`.
- Timing maps to the reconciler: mount at `construct-item`, unmount at
  `retire-item!`, update after props/children application. No
  next-frame deferral (that exists in Replicant for CSS transitions).
- Memory stored on the existing `:dsl/meta` stamp atom — per-instance
  state with the node's lifetime, no WeakMap needed.

### Open questions (the review checklist)

1. **Interplay with existing lifecycle paths.** `with-let` init/finally,
   record `dispose`, and `hiccup/dispose-tree!` already cover the same
   ground. Is a fourth lifecycle path worth it, or is the real need
   better served by an alias (item 2) whose body owns the resource?
2. **Fresh-closure trap.** Props equality drives leaf rebuild and
   container patches; `:tui/on-mount (fn …)` built inline per pass makes
   every pass a "change", churning mount/unmount on display leaves. Do
   hook props get excluded from props equality (special-cased in the
   tag table), or do we force hoisting like other callbacks? Either is a
   contract change to §2.3.
3. **Memory across rebuild.** Display leaves rebuild on prop change; a
   record rebuild loses stamp memory. Is memory guaranteed to survive a
   leaf rebuild, and if so, where is it carried?
4. **Ordering.** Hooks vs. child reconciliation vs. ref filling on
   mount; children-first on unmount (matching dispose order); batches
   within one frame — all unspecified until written down. `construct-item`
   fills refs *after* construction, so on-mount must run later than ref
   fill to be useful for ref-based effects.
5. **Headless rendering.** Does `render-lines` / `compile-tree` fire
   mount/unmount hooks? Tests would then run side effects, which is
   probably wrong but must be decided explicitly.
6. **Error policy.** A throwing hook: crash the loop (loud contract) or
   log + continue? Relationship to the timer registry's
   log-and-swallow policy for thunks.
7. **Perf.** Hooks are closures in props on hot paths; what do the
   counters show, and does the item-1 skip still fire with them?
8. **Naming.** `:tui/*` vs `:kmet/*`; reserved namespaces and
   documentation placement (§2.2 prop tables vs a new section).
9. **`leaks.md` invariants.** Resources acquired in a hook must be
   released on every removal path (unmount hook, dispose, display-leaf
   rebuild); node memory is a strong stamp field, hence not GC-managed
   (release is the node's disposal); hooks must not grow into a second,
   unguarded cleanup path beside `with-let`/dispose.
10. **Post-dispose record semantics.** A disposed record is currently
   inert-but-readable: its fields — and the stamp's `:props` duplicate —
   still hold text, callbacks and cached state. Clearing stamp `:props`
   alone releases nothing (the ctor destructured the same values into
   record fields; `ComponentFn` keeps `:props`/`:ctree`/`:kids` in atoms;
   containers keep `@children`). Decide whether disposal is final and
   blanks these, or whether dead records stay readable; either way it is
   a deliberate contract change, not a drive-by (recorded from the item-1
   review).

### Decision gate

Implement only if a concrete case appears that neither `with-let`, an
alias, nor a record can express cleanly — candidates seen so far:
per-node ticker start/stop, scroll-into-view after mount, clearing an
autocomplete/file cache when a widget leaves, extension integration with
an external resource. Until then, keep the two lifecycle paths we have.

---

## Not borrowed (recorded, so the analysis is not redone)

- **Data-driven event handlers / global dispatch** — against tui.md §7:
  no declarative input props, ever; input is imperative focus + widgets.
- **`:replicant/mounting` / `:unmounting` + async transition unmount** —
  no CSS transitions; async unmount would fight deterministic dispose and
  the editor dock's ordered close (§5).
- **Dropping components / no local state** — Replicant substitutes hooks
  + memory; ours is strictly more capable where it matters. Do not
  regress `with-let`, record state, refs, or apply paths.
- **The three-representation (hiccup/headers/vdom) tuple machinery** —
  the transferable part is the unchanged-tree isolation (item 1), not
  the encoding.
- **`extend-via-metadata` protocol dispatch** — our foreign-component
  handling (stamps, duck typing, `ui-custom` adapter) already covers it,
  without the SCI risk.
