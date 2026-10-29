# srce — a light Cordis kernel in Clojure

Implementation note for the `kmet.srce.*` module, beside its code. kmet2's
application architecture — composition, services, agents, sessions — is in
`docs/architecture.md`; this file is the kernel only: its
ctx model, the lifecycle, the plugin contract, the contracts the tests pin, and
what was deliberately left out. User-facing usage (what srce is for, the five
principles and a how-to) is [`modules/srce/README.md`](../../../README.md); this
file is the reference its contracts and internal decisions live in.

The name is **`srce`** (Cyrillic **срце**, IPA /sr̩̂t͡se/) — heart (Serbian).
Cordis is Latin for the same thing (`cor`, `cordis`), which is what the kernel
is to the system: the one thing every other part is wired through. It stays
clear of `kmet.kernel`, `--list-kernels` and prose about OS kernels.

## What "light" means

Port the **concepts** that make Cordis a meta-framework, not the vendored
member list. A mechanism is in the kernel only if all three hold:

1. `docs/architecture.md` needs it (a service, an agent, a profile, a UI
   provider),
2. it is what separates a kernel from a service locator, and
3. it cannot be added later without changing every caller.

Those three conditions are Cordis's own **five ideas** — the vendored tree's
own introduction ([Cordis Primer](https://deepseek-harness.github.io/deepseek-harness/en/reference/cordis-primer),
`docs/cordis-primer.md` in the harness) — minus what kmet already owns. That
mapping is the light kernel's whole justification:

| # | Cordis idea (primer) | the light kernel | § |
|---|---|---|---|
| 1 | a plugin is an object that implements `Service` — a function with optional `inject` and `apply(ctx)` | a **validated map** with `:inject`/`:apply`; the Service-subclass half is cut (§10) | §6 |
| 2 | a context is a repository of services; a service claims a stable `ctx.x` key, others find it by key instead of importing an implementation | `provide!`/`get` by keyword over the ctx tree, realms via `isolate` | §2–§3 |
| 3 | declare dependencies via `inject`; a plugin waits until those services exist, so load order is requirements, not boot sequencing | required + `:optional` inject, the refresh pass — **boot order stops mattering** | §3 |
| 4 | typed events; dispatch as `emit`/`waterfall`/`parallel`/`serial`/`bail` | **one listener table** plus six modes over the resolved list (`emit`, `bail`, `waterfall`, `collect-last`, `collect-all`, `boundary`), all in the kernel; `serial`/`parallel` wait for an awaitable. The **declaration** half is the app's — a plugin declares its own events (`:events` on `defplugin`, checked by `plugin!` and carried on the fiber), because a kernel must not name an app event; the three `:srce/*` names are documented, not catalogued | §5 |
| 5 | registrations are reversible **effects**, so reload and teardown unwind predictably | the fiber's effect list, LIFO, owning the `provide!` and the listener | §4 |

Idea 3 is the load-bearing one for kmet2: extensions, profile rows and UI
providers mount in arbitrary order (a preset bundle, an extension directory, a
`--mode` flag), and "the plugin waits for its services" is what makes that
safe. Idea 5 is what makes the wait reversible: every registration a fiber made
comes back when its provider goes away.

Everything else is **deferred, not dropped**: §10 records each mechanism
with the Cordis member it came from and the seam that makes it additive, so
re-adding one is a lookup rather than a re-read of `vendor/cordis/src/*.ts`.
The Parity matrix survives as the appendix, one row per Cordis member that has a
decision, tagged `keep` / `deferred` / `never`.

| | full port (retired) | light |
|---|---|---|
| namespaces | 10 (`ctx` `reflect` `service` `fiber` `registry` `event` `logger` `awaitable` `utils` `config`) | 4 (`ctx` `fiber` `event` + the public `srce`) |
| kernel lines | ~4k | ~930 |
| conformance | 74 tests generated from 82 matrix rows | 41 contract tests (164 assertions) + the 3 guard tests (§12) |
| definition of done | Cordis parity | the contracts in §3–§8 |
| async | awaitables, `inertia`, epochs, drain owner | synchronous kernel, thread rule (§8) |

The cut is ordered so the light surface is a **subset** of the full one:
`isolate` labels, `intercept`, the logger service, internal events and config
transforms all attach to an existing seam. The one exception is §13's
synchronous `apply`.

## 1. The kernel

```
modules/srce/
├── deps.edn           — rank 1: third-party code today; add the kmet.libs.* /
│                        kmet.loader.* :local/root edges when a namespace needs one
├── test/kmet/srce/    — the contract tests (§12)
└── src/kmet/
    └── srce/
        ├── core.clj   — the public API (below); the one require app code needs
        ├── srce.md    — this file
        ├── ctx.clj    — the ctx value, the tree, the service store, isolate, the effect list
        ├── fiber.clj  — mount/unmount, states, the refresh pass, plugin runtimes
        └── event.clj  — on/once, the listener table, the resolved list, and
                          the three non-awaiting dispatch strategies
```

`kmet.srce.*` is rank 1 — it may require rank-0 code (`kmet.libs.*`, and
`kmet.loader.*` when it resolves sources), nothing at its own rank or above;
the subnamespaces are internal and nothing above the kernel requires them.
The module's `modules.edn` row — `:name "srce"`, the source/test paths,
`:rank 1` — is also its classpath and embed entry: the manifest is the classpath
order *and* the rank source `test/kmet/test_module_deps.clj` reads, and the
three extension `bb.edn`s list the module sources too.
Consumers require exactly one namespace — the kernel's surface is one contract,
not a set of modules whose callers each pick their own:

```clojure
(ns kmet.app.session
  (:require [kmet.srce.core :as srce]))

(srce/root)                              ; a root ctx — boot holds one, tests build their own
(srce/root {:base-url "…"})              ; same, with ctx keys merged in (nothing is read by the kernel)
(srce/extend ctx {:name :agent/7f3a})    ; a child ctx with metadata
(srce/isolate ctx :tools :llm)           ; a child ctx that is a realm for those names
(srce/inside? child ctx)                 ; is child in ctx's subtree? (inclusive)
(srce/get ctx :tools)                    ; the provider value, or nil
(srce/provider ctx :tools)               ; {:name :fiber :value :check}, or nil
(srce/services ctx)                      ; {name {:name :fiber :value :check}}, realm-aware
(srce/children ctx)                      ; this ctx's child ctxs, creation order
(srce/mount-fiber ctx)                   ; the fiber whose mount created ctx, or nil (a plain child, the root)
(srce/detach! child)                     ; drop a plain child ctx from the tree (refuses while mounts remain)
(srce/provide! ctx :tools table)         ; → disposer; a second visible provider throws
(srce/provide! ctx :events bus {:in app}) ; → the app's scope, this fiber's effect
(srce/on ctx :srce/status f)             ; → disposer
(srce/once ctx :srce/status f)
(srce/emit! ctx :my/event ev {:filter f}); sync, filtered, results ignored
(srce/bail! ctx :my/event ev)            ; the first non-nil answer, then stop
(srce/waterfall! ctx :my/event ev)       ; (f ev next); not calling next vetoes
(srce/listeners ctx :my/event {:filter f}) ; [{:ctx c :f f} …] resolved up the chain, dispatch order
(srce/plugin-source src resolver)        ; a var as-is, or the resolver's value for a keyword
(srce/plugin! ctx #'web-search {:max-results 5})   ; a var, a keyword, or a plugin map → fiber
(srce/inject! ctx :ui/terminal {:ui :optional} h) ; plugin! sugar; the name is required
(srce/normalize-inject deps)             ; → {name nil|:optional}; throws :srce/bad-inject
(srce/runtime ctx web-search)            ; the runtime, or nil
(srce/runtime? ctx web-search)           ; is a runtime mounted for this source?
(srce/runtimes ctx)                      ; [runtime …], mount order
(srce/dispose! fiber)                    ; effects LIFO → {:disposed :failed}
(srce/restart! fiber)                    ; dispose then mount again → a new fiber
(srce/update! fiber cfg)                 ; config change in place (needs :update)
(srce/dispose-plugin! ctx web-search)    ; every fiber of one source
(srce/dispose-subtree! ctx)              ; every fiber under ctx, children first
(srce/effect ctx f)                      ; → disposer (single-shot, sync)
(srce/effects fiber)                     ; the effect labels, creation order
(srce/log! ctx :info "…")                ; through the :logger service; silent when absent
(srce/settle! root)                      ; run any pending change to a fixpoint
```

`defplugin` and `ctx?` round it out. There is no `defservice`, `accessor`,
`mixin`, `set-service!`, `notify!`, `intercept`, `update!`, `fiber-await` or
`drain-await` — see §10.

`root` builds a root ctx; it is a value, not a process singleton. Boot holds one
and passes it down, a test builds its own, and the root-owned state (runtimes,
mounted fibers, the uid counter, the pass flag, the change set) lives in one
shared `:kernel` map that every child inherits — the atoms are references, so
two roots never see each other, no kernel state is process-global, and no ctx
needs a pointer back to the root. An optional map is merged into the ctx as-is,
which is how boot sets `:base-url` and `:resolver` (§2); the kernel reads the
resolver only when a plugin source is a keyword (§6).

Two public names shadow `clojure.core` — `get` and `extend` — so the kernel's
own namespaces carry `(:refer-clojure :exclude [get extend])` rather than
spelling `clojure.core/get` at every call site. They are Cordis's names
(`ctx.get`, `ctx.extend`; the §11 parity matrix keeps both): the kernel follows
that vocabulary instead of renaming around a Clojure-specific collision. The
shadowing stays confined to the kernel's own namespaces — callers either
qualify (`srce/get`) or exclude the two names explicitly.

## 2. The ctx value

A ctx is a value: a map with fixed keys, no proxy, no service methods on it.
`(srce/ctx? x)` is a marker key, replacing Cordis's `Context.is` brand.

| key | meaning |
|---|---|
| `:kernel` | the shared root state: `{:runtimes :mounted :counter :pass :changes}` — one map, inherited by every child, read through `(:kernel ctx)` |
| `:parent` `:children` | the tree (`:children` is an atom); a child comes from `extend`/`isolate` |
| `:name` | this ctx's own name — a plugin mount names its ctx from the plugin's `:name`, and the unit listing derives a path from the chain |
| `:fiber` | the **atom** holding the fiber that owns this ctx (the root fiber has `:uid 0`); `extend`/`isolate` inherit the parent's, a plugin mount installs its own |
| `:isolate` | the set of names this ctx declares a realm for (not inherited) |
| `:store` | this ctx's own `{name → {:name :fiber :value :check}}` atom |
| `:listeners` | this ctx's `{event-name → [listener …]}` atom |
| `:base-url` | resolution base for a source resolver; the kernel carries it and never reads it — what it means, and whether the nearest non-nil value wins or boot passes one attribute, is the extension host's call; nil until `root`/`extend` supplies it |
| `:resolver` | inherited key: resolves a keyword plugin source (§6). The kernel only calls it, and only when the source is a keyword |
| `:runtimes` `:mounted` `:counter` `:pass` `:changes` | **inside `:kernel`**: plugin runtimes, mounted fibers in mount order, the uid allocator, the optimistic pass flag and its change set |

**Four accessors answer the whole-tree questions**: `(children ctx)`,
`(services ctx)` — the realm-aware merge of what the ctx can see, which is what
an inspection view needs — `(listeners ctx name opts)` — the resolved listener
list, which is what the app's dispatch modes walk (§5) — and `(event-names
ctx)`, the registered names on the chain. `services` is the accessor a caller
would otherwise re-derive per name with `provider`; `listeners` is the accessor
that keeps the app's dispatchers from needing a second registry; `event-names`
is what reporting a subscription list reads instead of reaching into the
`:listeners` atoms.

**A ctx is cyclic, so never print one.** `ctx → :kernel → :runtimes → fiber →
:ctx` closes the loop, and no host's default printer detects it: `pr-str` of a
ctx, or of any value holding one, is a stack overflow. Report names, counts and
states (`(:state @fiber)`, `(children ctx)` counted, a service's *name*), never
the value — which is also why a service value must not carry its ctx (the app's
`:events` service is a registry handle for the same reason).

**The kernel owns its tree.** `kmet.srce.*` must not require
`kmet.libs.scope`: the kernel needs a parent/child walk and a per-fiber effect
list, not a keyed multi-owner store with four views. The store is deleted as of
landing 4e, so the ban is a tripwire now, not a live dependency; a guard test
enforces it.

`extend` and `isolate` copy: a parent is never mutated (beyond the `:children`
entry), extra keys are merged (the kernel reads `:name` directly and
`:base-url`/`:resolver` as inherited keys — nearest non-nil up the chain, no
realm stop), and `isolate` names become this ctx's `:isolate` set, which is the
names *declared here*: a child does not inherit the set, so the lookup walk
passes through the realm and stops at the ctx that declared it. A second
`isolate` for the same name is a deeper boundary, not a redefinition: the inner
subtree sees only its own provider for that name and the outer subtree is
unaffected.

**`get` resolves up the tree, stopping at a realm.** Walk from the reading ctx:

- this ctx's store has the name → that provider;
- this ctx isolates the name → nil (the realm boundary blocks the outside);
- else the parent.

A provider whose fiber is not `:active` reads as `nil` — the light kernel has
one `get`, no `strict` flag. There is no `fiber.store` snapshot: inspection is
`(srce/provider ctx :name)`, which says who provides it now.

**A child ctx has a lifetime, and only a mount gets one for free.** A fiber's
`dispose!` removes its own mount ctx from the parent's `:children`. A plain
child — `extend`, `isolate`, a run's own ctx — has no fiber, so nothing ended
it, and the tree grew a line per `extend` for the life of the process. So
`(detach! child)` removes it, refusing with `:srce/ctx-busy` while anything is
still mounted under it: a listing that hides a live unit is worse than one that
lingers. The detach keeps `:parent`, so a value that still holds the ctx
resolves services exactly as before; what changes is the tree, which is what
the unit listing renders (`docs/architecture.md`, "Inspection").

**Which kind of ctx this one is has one answer.** `(mount-fiber ctx)` is the
fiber a mount's own ctx names, and `nil` for a plain child (which inherits its
parent's `:fiber`, so the identity check fails) and for a root (which has a
fiber of its own but no parent). Both the listing and the app's agent registry
ask this question — the listing to render a fiber line instead of a ctx line,
the registry to refuse a parent whose disposal would drop an agent out of the
tree invisibly — so the predicate is the kernel's rather than each caller's.

A detach releases the **tree line**, not the registrations: `:fiber` is
inherited, so what a plain child ctx provides or listens for is the nearest
fiber's effect and unwinds with it. That is one more reason a plain child is a
scope rather than a unit, and why the app composes an agent on a fiber (the
mount) and keeps the `extend` for the seams it must override.

## 3. Services and refresh

Cordis's reflect layer, minus everything the light kernel doesn't need.

- **One provider per name per realm path.** The store is per ctx (§2), so the
  guard is *visibility*, not a global name table: `provide!` throws
  `{:type :srce/duplicate-service}` when the name is already held on the way up —
  this ctx's store, or an ancestor's before any realm boundary. The check is on
  the entry, not on `get`'s `:active` filter, so a pending provider still
  occupies its name. A nested `extend` therefore cannot shadow an outer
  provider — that is the Cordis rule — while a second instance needs a second
  realm, which is `isolate`, which is why `isolate` is in the light set and
  labels are not. Two *siblings* under one realm may each provide the name: the
  nearest visible provider wins per subtree, the listing (which walks the ctxs)
  shows both, and `services` from above shows neither. **A per-agent value
  therefore mounts where nothing above it provides one** — the agent's own ctx,
  never as a shadow of an app-wide row; the app follows this for
  `:permissions` (`docs/architecture.md` § *Permissions and approvals*), and a service that
  really needs a second instance over a provider it cannot remove wants
  `isolate`, not a child. A root-level `{realm-key name → impl}` store is Cordis's shape and would
  make that a throw instead; the per-ctx atom is chosen because the realm then
  *is* the ctx — no symbol table, no labels (§10).
- **`provide!` is an effect.** It registers into the current ctx's store and
  pushes a disposer onto the fiber's effect list; disposer order is the fiber's
  order. A provider's disposer deletes its entry and records the name as a
  change, so the outer `settle!` remounts the dependents.
- **`:in` is the scope, when it is not this ctx.** `(provide! ctx name value
  {:in ancestor})` puts the entry in ANCESTOR's store while the effect and the
  provider record stay CTX's fiber — "share with my siblings". Without it a
  plugin's service is visible only to its own subtree, which is right for a
  per-agent capability and wrong for an app-wide one: the app's rows mount as
  siblings under the root, so `kmet.app.events` provides its service
  `{:in (:parent ctx)}`. The duplicate check reads from the target scope, and
  the entry still dies with the providing fiber.
- **`check` is a thunk answering whether the value is available now.**
  `(provide! ctx :ui v {:check ready?})` — `(ready?)` is called on every read
  and at every pass; false or a throw reads as "unavailable" for `get` and for
  inject. A pass always re-evaluates `:pending` fibers (even with an empty
  change set), so a check that turns true is noticed at the next `settle!`. A
  check that turns **false** on an active provider is not a revocation: dispose
  or re-provide to revoke.
- **`provider` is a read**, not state: it returns the
  `{:name :fiber :value :check}` record, including one whose fiber is not yet
  active.

### inject

```clojure
[:tools]                          ; required
{:tools nil :llm nil}             ; required
{:ui :optional}                   ; optional: never holds the fiber pending
```

The object form's values are **reserved**. In Cordis a non-nil value *is*
intercept config merged into the service; the light kernel cut `intercept`, so
anything but `nil` or `:optional` throws `ex-info` — `{:type :srce/bad-inject}`
plus `:problems` — rather than silently configuring. That keeps the door
open: intercept comes back as the meaning of those values, not as a new
spelling. One shape, thrown — `normalize-inject` is not total, and
`plugin-schema` (§6) lets the throw surface as the plugin's own
`:srce/bad-plugin`.

An optional dependency participates in the **provider set** like a required
one: absent contributes `:srce/absent`, present contributes the provider fiber's
`uid`. The set is the epoch, and the epoch is what makes `{:ui :optional}`
"upgrade when a richer UI attaches" (`docs/architecture.md`, "Pluggable
UI").

### refresh: a fixpoint pass, not an epoch race

The full port needed the epoch to *drop* a stale `_reload` that lost a race on
another thread. The light kernel has one owner thread and one pass flag (§8), so
a provider change runs one synchronous pass that a second thread cannot split:

1. a change (provide, provider dispose, provider state crossing `:active`)
   appends its names to the pass's change set;
2. for each mounted fiber that injects one of those names, compute its provider
   set; unchanged → skip, unsatisfiable → `:pending`, changed → a *reload*
   (unload and re-activate the same fiber, so the uid stays);
3. a fiber is remounted **at most once per round** (a second provider change
   in a later round reloads it again — that is the fixpoint); the pass repeats
   until the change set is empty, with a budget of 8 rounds after which it warns
   (`:srce/churn`) and clears the set. Clearing is safe because round 1
   recomputes provider sets from the store, not incrementally: nothing is
   forgotten, only the remaining remounts wait for the next change to one of
   the names. A `provide!` issued from a plugin's own `apply` joins the pass's
   change set and is processed after that `apply` returns — a fiber never
   remounts itself. That is true unconditionally because **the pass never runs
   reentrantly**: `plugin!`, `restart!`, `update!`, `dispose!`,
   `dispose-plugin!` and `dispose-subtree!` hold the pass flag for their whole
   body and drain once at the end, so a mount's own `provide!` only records. A fiber is never a
   candidate for its own pass: it is either not yet mounted or no longer
   `:active`. Remounting replaces the fiber's `:mounted` entry in place, so the
   listing's "fibers in mount order" is stable; only a fresh `plugin!` appends.

That replaces `notify!`, the epoch and the drain queue with one loop. The
observable rule the app depends on survives: a dependent sees the new
provider, and never a half-provided one.

**The pass owns the pass.** §8 says mutations run on the owner thread, but
kmet1 does not hold that rule today: the TUI input handler and tool threads
call the registries while the loop runs, and the registry's atom makes each call
safe per operation. A fixpoint pass is not per-operation safe — two threads
entering one would remount a fiber twice. So the kernel's single concurrency
primitive is an optimistic pass flag:

```clojure
(defn settle!
  [ctx]
  (let [{:keys [pass changes]} (:kernel ctx)]
    (loop []
      (when (compare-and-set! pass nil :running)
        (try (run-pass! ctx)
             (finally (reset! pass nil))))
      ;; a change recorded while we owned the flag, or between rounds, is ours
      (when (and (seq @changes) (nil? @pass))
        (recur)))))
```

`provide!`, `dispose!` and the rest record into `:changes` and then call
`settle!`. A caller that loses the flag returns immediately — its change is in
the set, and the owner's last round or its retry picks it up. The trailing
`when` closes the window the six-line version left open: a change recorded
between the last round's snapshot and the flag reset would otherwise sit until
some *other* `settle!`, so `settle!` returning could not mean "nothing pending"
and `docs/architecture.md`'s "the boundary is also the drain" would be false. The
invariant now is: **when `settle!` returns and no other thread owns the pass,
`:changes` is empty.** A change that lands while another thread owns the flag
is that owner's, which is the "late, never corrupting" rule below.
`compare-and-set!` on an atom is available on both hosts (checked on bb and
Jolt), so the primitive is portable, and it is still eight lines rather than a
queue and an awaitable.

## 4. Fibers and effects

A fiber is one mount of one plugin source. States: **`:pending`** (waiting for
inject), **`:active`**, **`:failed`** (a throwing `apply` or config), and
`:disposed` (the record stays for inspection; the uid stays too — nothing
depends on uid reuse).

The record is an **atom holding a plain map** — one `swap!` per transition, so
a reader never sees a half-updated record. **App code reads it through
`srce/fiber-info` (one fiber) and `srce/fibers` (the root's mount list, mount
order)**: `:uid :name :source :config :state :error :inject :events :provides
:waiting-for :effects :ctx` as a map, so a field the kernel adds stays the
kernel's, and `/units`, `--dump-state` and a reconciliation read one shape
instead of destructuring the atom. `@fiber` remains the record itself, for
tests and the kernel's own code. There is no `fiber.store`; the map's own keys
are the accessor:

| key | meaning |
|---|---|
| `:uid` `:name` `:config` `:state` `:parent` `:runtime` | identity, raw config, lifecycle (the cordis fields, kept) |
| `:resolved` | the config `:config` validated to on the last activation — what the current mount received, and the old value `update!` hands its hook |
| `:ctx` | the child ctx it mounted |
| `:inject` | the **normalized** deps (each name maps to `nil` or `:optional`) — what the listing prints as `inject=[…]` |
| `:provides` | the names this fiber has provided and not released — the listing's `provides=[…]`, maintained by `provide!`'s disposer, so a shadowed realm's provider is still attributable |
| `:waiting-for` | the names of `:inject` the provider set cannot satisfy, recomputed by the pass — the listing's `waiting-for=[…]`; empty when `:active` |
| `:error` | the thrown value when `:failed`, nil otherwise |

- **The root fiber exists**, `:uid 0`, `:active`. It is the owner of anything
  the app registers before any plugin (root tools, scratch test registrations),
  which is why no registration in the light kernel can be fiber-less.
- `plugin!` creates the fiber, its child ctx (named from the plugin's `:name`,
  which is what gives the unit listing a readable path), registers it on the
  source's runtime, adds it to `:mounted`, then activates: unsatisfied →
  `:pending`; satisfied → `:active` and `apply` runs with `(ctx config)`.
- **`apply` is synchronous** and its return value is its first effect: a
  disposer, a seq of them, or nil (see §13 — the one cut that is not
  additive). It is registered last, so it unwinds first.
- **Effects.** `(effect ctx f)` requires an `:active` fiber (`:srce/inactive-effect`
  otherwise) and returns a single-shot disposer. Within an effect the returned
  disposers unwind LIFO; across the fiber's effects, unwinding is LIFO by
  creation order — one effect at a time, last created first, so a service that
  must release before another effect does is expressible by effect order. A
  lazy seq is collected as produced. `(effects fiber)` returns the labels of
  the effects still installed, in creation order (the reverse of unwinding); a
  disposer run early drops its entry, so the list never reports a registration
  that is gone. The label is an **inspection contract** (`:tool/read`,
  `:command/units`): it is what the booted listing renders, so a vague label is
  a line that says nothing.
- **Disposal order is part of the contract.** `dispose!` is: flip the state to
  `:disposed` (so `get` already reads `nil`), record its `:provides` names as a
  change, unwind the effects LIFO (where a service entry is removed and its
  dependents are re-evaluated), then
  `settle!` once at the end. Flipping **before** unwinding is what makes the
  closed-store guard real without a sixth state: a registration from inside a
  disposer (an effect, a `provide!`, an `on`) throws `:srce/inactive-effect` instead
  of arming cleanup that will never run, and dependents are never remounted
  against a half-torn-down service. `:disposing` stays internal to those four
  steps; it is never a value of `:state`.
- **Disposal runs everything and never skips.** A throwing disposer is reported
  through `:logger` at `:error` and the remaining disposers still run. The
  unwind also *collects* them: `dispose!` answers `{:disposed n :failed [{:label
  … :error … :message …}]}` (`dispose-plugin!`/`dispose-subtree!` aggregate the
  same shape), so a caller can report a teardown failure instead of leaving it
  only in a log that is silent without `--debug`: `:error` is the throwable and
  `:message` its rendered text. The outermost unwind collects: a dispose
  performed *by* a disposer contributes to its caller's report and answers an
  empty one itself.
- **A failed fiber is a state, not an exception.** `apply` throws → the fiber is
  marked `:failed` with `:error e` **first**, so an effect or `provide!` from
  inside its own disposers throws `:srce/inactive-effect`; then its partial effects
  unwind LIFO (same logging as `dispose!`), its store entries are deleted so
  the names are free again, `:srce/failure` is emitted, dependents stay
  `:pending`, and the plugin is reported through `:logger`. A failed fiber is
  not retried by the pass (its provider set is unchanged), so `restart!` is the
  retry: a config/shape error keeps failing, a transient one recovers.
- **`unload!`/`activate!` are the primitives; `dispose!`/`restart!` are the
  public pairs.** A pass *reloads*: it sets `:pending`, unwinds (a disposer can
  no longer register), and activates again on the same fiber — same uid, same
  cfg re-validated. `dispose!` is the terminal one (state `:disposed`, that uid
  never returns); `restart!` is `dispose!` + `plugin!`, so it allocates a new
  uid. `:pending` doubles as the unloading state, because only `:active` may
  register.
- **`:mounted` in reverse is children-first.** Mount order is topological (a
  child always mounts after its parent), so `dispose-subtree!` is the reverse
  mount list filtered by `inside?` — no tree sort.
- **Keep related registrations in one effect.** The primer's own practical
  rule, and what makes disposal order meaningful at all: a tool and its prompt
  section, a provider and its listener, belong to the same effect, so teardown
  follows registration order. Two effects are two LIFO runs with no guarantee
  between them.
- **`restart!` is `dispose!` + `plugin!`** with the same source and config,
  returning the new fiber — a new uid, so dependents remount.
- **`update!` changes a config without a restart**, and that is why it is the
  narrow one: it requires an `:active` fiber and a plugin that declares
  `:update` (`:srce/no-update`/`:srce/inactive-update` otherwise), validates the
  new config with `:config` exactly as a mount would, runs `(hook ctx
  old-config resolved-config)` and stores the raw config last — so a throw
  leaves the fiber as it was, and what the hook registered before throwing is
  unwound. The uid stays, so no dependent remounts and a dependent
  keeps the value it resolved until its own reload: an in-place config change
  is a snapshot, not a provider swap. A hook that provides a *new* name is a
  normal provider-set change (dependents reload at the next `settle!`); the
  name the plugin already holds cannot be re-provided
  (`:srce/duplicate-service`), so a hook updates the value it owns and
  replacing a service is `restart!`.

The disposal axes stay distinct, as in the full port: the **fiber**
(`dispose!`), the **effect** (its disposer), the **ctx subtree**
(`dispose-subtree!` — every fiber under a ctx, children first, an agent's
release), and the **plugin** (`dispose-plugin!`, every fiber of one source).
The root fiber's disposal is an ordinary disposal: it unwinds every root effect,
so the app tree is gone and the app remounts the tree to restart. It never tears
down the process, and there is no implicit restart — `dispose!` answers
`{:disposed 0 :failed []}` on a second call instead of restarting anything.

## 5. Events

The kernel needs a notification path, not an event framework:

- `(on ctx name f)`, `(once ctx name f)`, `(emit! ctx name ev {:filter f})`,
  `(bail! …)`, `(waterfall! …)`.
  A listener is `(fn [event])`; its own ctx is visible to the `:filter` and in
  the resolved list, never as a call argument — one shape for every mode but
  `waterfall`, whose whole point is the `next` continuation (`(f event next)`).
- `(names ctx)` enumerates what is registered on the chain, sorted: the kernel
  owns the table, so reporting a subscription list is the kernel's read, not a
  second atom anything can drift from.
- A ctx's listeners run for emits on that ctx **and its descendants**, so a
  subtree scopes naturally; `:filter` receives each listener's own ctx (a
  listener cannot opt out), which is the shape the node-scoped bus needs.
- Registration order, `once` disposes before invoking, and the disposer reports
  whether it removed something.
- A throwing listener is reported through `:logger` and reads as nil for its own
  rung; the dispatch carries on. `invoke!` is that one rule, and every mode —
  the kernel's three and the app's protocols — calls it instead of its own
  try/catch.

**One listener table, two layers.** Registration is `on`/`once` on the ctx and
nothing else; the *modes* are different walks over one resolved list, and the
seam between the layers is that list, not the table. Cordis splits it the same
way in `events.ts` (`dispatch` returns the filtered, bound list; each mode walks
it); the difference is only where the layer line falls — see "strategy vs.
protocol" below.

**A mode is a fold over the resolved list, so the kernel owns all six.** Once
a registration may carry `{:data x}` (below), no mode needs a name or an app
concept: `emit` ignores results, `bail` takes the first non-nil, `waterfall`
threads `next`, `collect-last` takes the last non-nil, `collect-all` pairs each
non-nil result with its registration's data, and `boundary` merges returned maps
into the event. That is Cordis's cut — the dispatch mechanism in the core — with
one difference in where the line falls: Cordis's core *uses* `bail` and
`waterfall` for its own interception seams (`internal/listener` claiming,
`internal/config`), while kmet's light kernel has no such seam (§10), so the
modes are there for the app rather than for the kernel.

**Why `serial`/`parallel` are absent — and it is not "ordering".** Every mode
already walks the list in dispatch order, so ordering is not a choice any
dispatcher makes. What `serial` and `parallel` add over `bail` and `emit` in
Cordis is *awaiting a Promise*: `serial` awaits each listener before deciding
whether it answered (and short-circuits on the first answer), `parallel` starts
them all and awaits the array of results. Strip the await out of a synchronous
world and the two collapse exactly onto the modes we have: `serial` → `bail!`
(stop at the first non-nil, in order) and `parallel` → `collect-all!` (call
everyone, keep every answer). A declaration naming `:serial` would therefore be
an *alias* promising something it cannot deliver, which is why the set is
`srce/dispatch-modes` (`emit`, `bail`, `waterfall`, `collect-last`,
`collect-all`, `boundary`) and not a superset.

What the await itself would cost, concretely — and it is not availability:
`future`, `promise` and `(deref x ms default)` are core Clojure on bb, the JVM
and Jolt alike, and `core.async` is fully usable on all three (`go`, `thread`,
`<!`, `alts!` verified on Jolt too), so the kernel *could* await today. The
problem is that a deref
**blocks the thread that dispatched**, and the kernel has one such thread — the
owner (§8). A listener that needs the owner (the documented way to do async
work is submit to the loop and let the boundary drain it) then waits for work
the blocked owner will never run: a deadlock by construction, where a JS
`await` merely yields the single thread. That is a scheduling decision, not a
missing capability.

Channels do not change that, and they change something worse: `<!`/`alts!` are
only legal inside a `go` block, and the kernel's entry points are plain
functions called from any thread (`emit!` from a tool thread, `bail!` from the
loop, an `apply` body). Awaiting inside a go block colors every caller —
dispatch, plugin bodies, `plugin!`, `dispose!`, `settle!`, the app — which is
the full-port design §8 rejected; awaiting outside one is `<!!`, a thread block
with the same owner-thread deadlock. Channels also do not compose with the fold
shapes: `waterfall!`'s `next` is a synchronous continuation, `bail!`
short-circuits on a value, `boundary!` merges maps — with a suspending listener
each `next` becomes a take and the listener contract bifurcates. What channels
*would* buy when the trigger arrives is cancellation (close), timeouts
(`alts!` + `timeout`) and backpressure — scheduler policy, which is the app's
queue, and the reason core.async is the better substrate than futures if a
kernel mode ever lands. Today `core.async` is exactly where it belongs:
`kmet.libs.mcp.transport`'s SSE pipelines, above the kernel.

Half of `parallel` already exists, too: `collect-all!` calls every listener and
keeps what each returned, so listeners that return futures or channels are *all
started* before the caller awaits them — `(mapv (fn [[data f]] [data @f])
(collect-all! …))`, or `(a/<!! f)` for a channel, at the call site. The kernel does not need a mode to call functions in order.
Only `serial` has semantics the kernel lacks — invoke, deref, stop at the first
answer — and that is precisely the one that institutionalizes blocking the
owner between listeners. If it is ever needed, it is `bail!` with the deref
inside the short-circuit test, and the one exception rule extends to "a deref
that throws reads as nil, so the next listener runs" — a semantic choice, not a
mechanical one.

Unlocking them is therefore an app-side landing, not a kernel one: a scheduler
that runs listener work off the owner thread and settles it at the boundary —
which is `kmet.app.runtime/submit!` + `boundary!`, Cordis's `ctx.submit` — with
the disposal-aware cancellation, deadline and interrupt policy that threads
need. The trigger is the first event whose emitter needs results from
*concurrent blocking* listeners — e.g. `:resources-discover` against several
slow remote providers where the serial sum is user-visible. Until then async
work goes through the app's queue (§8), not through an event mode.

| mode | where | what it returns |
|---|---|---|
| `emit` | `srce/emit!` | nothing: every listener runs, results ignored |
| `bail` | `srce/bail!` | the first non-nil answer, short-circuiting the rest (a veto) |
| `waterfall` | `srce/waterfall!` | the outermost listener's value; `(f event next)`, and not calling `next` vetoes |
| `collect-last` | `srce/collect-last!` | the last non-nil answer (kmet1's `emit-event!`: a later `:cancel`/`:summary` wins) |
| `collect-all` | `srce/collect-all!` | every non-nil result as `[data result]` — the registration's `:data` beside what it returned |
| `boundary` | `srce/boundary!` | the accumulated map: each listener sees the previous results merged into its event and returns the keys to replace (pi: emitBoundary — `waterfall`'s data-shaped sibling) |
| `parallel` / `serial` | — | awaited: they need an awaitable the kernel does not have (§8); the app's submission queue is a drain, not an awaitable, so they stay deferred (§10) |

`(srce/dispatch-modes)` is that table as a set, and it is the authority an app
reads when it declares which mode dispatch an event wants: a declaration naming
something outside it is a lie, so the app's guard asserts it. The **wire
projections** (`wire-event`, `wire-error` — the EDN a headless consumer reads)
are *not* here: they are transport, and they live with the transport that
serializes.

**A registration is a record, and `:data` is where a layer puts its own.**
`(on ctx name {:data x} f)` stores `{:f f :data x}`, `listeners` returns
`[{:ctx c :f f :data d} …]`, `collect-all!` pairs `d` with the result, and a
`once` registration carries its data to its one delivery. The kernel never looks
inside `:data` — the app puts an owner (an extension path, `:loop`) there, and
Cordis's equivalent is the `Hook` record's `ctx`/options. *Scope* needs no field
at all: a listener hears what is emitted from the ctx it registered on and that
ctx's subtree — the same rule as kmet1's node scoping, expressed by the tree
instead of a stored node.

**The app owns the vocabulary; the kernel owns the table.** The *vocabulary*
(three fifths of what was `event-bus.clj`, pure data) lives in
`kmet.app.events`, declared as `:events` on a plugin map and registered into
the `:events` service at mount (not a generated projection — one was tried and
deleted: a file in the load path for data that is already in code). The
dispatcher is `kmet.app.events/emit!`, which picks the fold from the event's
declared `:mode`; `kmet.app.wire` holds the wire projection only. The extension
ABI registers on the host's ctx (the app root), so an extension's subscription
is an ordinary listener — no second table, and one contract test over the app
layer (plus the kernel's own) holds the delivery behaviors.

The retirement settled the two rules the split left open: a subscription's
scope is the ctx it registers on (the kernel's walk up, no process-wide
special case), and an emission must name a ctx — `events/emit!` refuses a nil
one (`:kmet.app/bad-event-ctx`). The extension ABI needed no new mechanism for
either: the host's ctx is the app root, which is an ancestor of every agent.

**Declaration lives above the kernel.** `:events` on a plugin is the
declaration form: `plugin!` checks the shape (`{event {:mode :doc …}}`), the
fiber carries it (`:events`, inspection data), two mounted plugins may re-declare
a name only identically (a boot-time check), and the vocabulary accessors read
the declaring namespace's own map — so nothing may dispatch through a
projection. The kernel's own event names are three —
`:srce/status` (fiber state transition, `{:fiber f :from s :to s}`, emitted on
the mounting ctx), `:srce/service` (a provider installed/removed,
`{:name n :value v}`, emitted on the providing ctx), `:srce/failure` (`apply`
threw, `{:fiber f :error e}`) — Cordis's nine `internal/*` events collapsed to
the three with a consumer. `kmet.app.events/event-types` is rank 2, so it can never
list `:srce/*`: the kernel's vocabulary is documented here and pinned by its
emit sites, while the app's events get the catalog. That asymmetry is the price
of the rank cut, and it is cheaper than a rank-3 require in the kernel.

## 6. Plugins

**No `IPlugin` protocol.** A plugin is a validated value — Cordis's Base fields
as a map, shape-checked at compile time by `defplugin` and once at mount by
`plugin!`:

```clojure
{:name    :tool/web-search     ; required — the runtime name and the mount's ctx name
 :inject  {:tools nil}         ; optional, default {} — required deps, nil values
 :config  (fn [cfg] …)         ; optional, default identity — validator, cfg or throw
 :provide #{:web-search}       ; optional, default #{} — declared, checked after apply
 :update  (fn [ctx old new] …) ; optional — absorb a config change in place (`update!`)
 :apply   (fn [ctx cfg] …)}    ; required — the effect body
```

`:name` and `:apply` are the only required keys. `plugin-schema` runs once, in
`plugin!` (and at compile time in `defplugin`); a bad map throws `ex-info`
`{:type :srce/bad-plugin :problems […]}` — a caller bug, not a `:failed` fiber,
which means the plugin *ran* and threw. `:config` runs on **every** mount and
remount, from the raw config, and its return is what `apply` receives;
`(:config fiber)` stays raw, so a restart always revalidates. `:provide` is a
claim, checked after a successful `apply` against the fiber's `:provides`
(§4): a mismatch in either direction is logged at `:warn` through `:logger`
(a plugin may provide conditionally, so it is not a failure), which is what
keeps the `--dump-config` card honest instead of aspirational. `:update` is
the opt-in for `update!` (§4): without it a config change is `restart!`, and
the hook is what a plugin declares when its service can absorb one.

| Cordis | srce |
|---|---|
| `Plugin.Function (ctx, config)` | — (one map shape; the body is `:apply`) |
| `Plugin.Object { apply }` | the plugin map |
| `Plugin.Constructor` | not ported — a record is built inside `:apply` |
| `Plugin.Base` (`name`/`Config`/`inject`/`provide`) | the map's keys |
| `Plugin.Transform` (`{schema, Config}`) | `:config` is a validator fn, so `(m/compiled …)` is just a fn |
| `IPlugin` type | `plugin-schema`, checked once (`{:type :srce/bad-plugin :problems …}`) |
| `registry.resolve` | `srce/plugin-source` — a var as-is; a keyword looked up by the caller's resolver |
| `registry.get`/`.has`/`.delete` | `srce/runtime` / `srce/runtime?` / `srce/dispose-plugin!` |
| `registry.keys`/`.size` | `srce/runtimes` (the inspection surface `--dump-config` reads) |
| `registry.counter` | the root's uid allocator, internal |
| `Inject.resolve` | `srce/normalize-inject` (`:optional` sentinel) |
| plugin *source* polymorphism | the caller's resolver — `kmet.loader.core/Loader` (SCI / Jolt native / classpath) is the one kmet2 passes |

**Source resolution is a seam, not a dependency.** `plugin-source` normalizes
what the caller hands `plugin!`: a var passes through, a keyword is looked up by
the resolver, a miss is `{:type :srce/bad-source}`. `srce.*` may require
`kmet.loader.*` (rank 0, below the kernel) and `kmet.loader.core/Loader` is the
resolver kmet2 passes, but the kernel never requires it and never decides what
a keyword means — how an extension source is resolved is the extension host's
implementation detail, not a kernel contract.

`defplugin` is sugar over the map: it gives the source var, `:name` metadata, a
compile-time shape check, and the card `--dump-config` prints. A profile row
names the **source**, not a keyword, and the card is the plugin map's own keys —
name, `:inject`, `:provide` — read by resolving that source. There is no
manifest of declarations beside the code: a generated projection was tried and
deleted, because dereferencing a plugin var mounts nothing, so the only thing it
bought was describing a row whose namespace *cannot load on this host* — and no
profile has such a row yet. When one does (a host-gated `:ui/tk` row), the
decision is made where the information is: either the profile row carries its
own label (profiles are data) or `describe` learns an "unresolvable here"
marker.

**One runtime, many fibers.** `plugin!` creates a fresh fiber per call — its own
config, ctx, effects and lifecycle — and records it on the source's runtime.
`inject!` is `plugin!` with `{:name name :inject deps :apply cb}`, and it takes the name
first — `(inject! ctx :ui/terminal deps cb)` — because a Clojure fn carries no
reliable name to derive one from (Cordis uses the callback's), and an unnamed
mount has no ctx path, no runtime name and an anonymous listing line. A runtime
is keyed by the resolved source, so two keywords resolving to the same var
share one runtime; the runtime's `:name` always comes from the plugin map, not
from the keyword. A runtime is dropped when its last fiber unloads, so a later
`plugin!` recreates it.

## 7. `:logger` is a service, not a subsystem

The kernel writes nothing to stdout. `(srce/log! ctx level & args)` resolves
`:logger` and calls it; absent means silent. The composition's `:logger` row
(`kmet.app.debug/plugin`) provides `kmet.app.debug/logger`: an `:error` always
writes the diagnostics error log (`logs/kmet-error.log` under the process cwd
unless `KMET_LOG_DIR` names another directory), the other levels follow
`--debug`.

`kmet.srce.*` may require only rank-0 code — in practice `kmet.libs.*` and, for
source resolution, `kmet.loader.*` — plus third-party code, so the kernel
cannot call `kmet.app.debug` directly — which is also why the full port's
logger service (exporters, formatters, ANSI palette, ring buffer, `:logger`
intercept config) is deferred in whole: kmet already has `kmet.app.debug`,
and a second logger is a copy.

## 8. Scheduling: one thread, no queue

Cordis runs every transition inline on the caller's stack, which is safe in JS
because there is one thread and a microtask queue. kmet2 has neither: a
transition can be entered from the TUI thread, a `spawn`ed tool thread, the
loop, or a socket reader. The full port answered with a drain-owner thread and
an awaitable queue. The light kernel answers with a rule:

- **The kernel has no lock; the pass has a flag.** Every mutation runs on the
  thread that owns the root ctx. The exception is the class of mutations that
  only *append*: `provide!`, `on`/`once` and `effect` on an already-active
  fiber touch an atom or the effect vector, so a foreign-thread call is *safe
  but late*, never corrupting — it records and returns, and the owner's next
  `settle!` (or the turn boundary) applies it. Everything that runs plugin code
  — `plugin!`, `restart!`, `dispose!`, `dispose-plugin!`, `dispose-subtree!` —
  stays on the owner thread: a disposer may re-enter the kernel, and a
  concurrent pass could otherwise remount a dependent mid-teardown. That is the
  whole distinction; "safe but late" is not a licence for `dispose!`.
- **The app marshals.** Plugin work, reloads and approvals are submitted to the
  loop; a tool thread that must mutate the kernel asks the loop to do it. This
  is app policy, recorded in `docs/architecture.md`, and
  `kmet.app.test-submit-guard` holds it: the six calls that run plugin code
  (`plugin!`, `restart!`, `update!`, `dispose!`, `dispose-plugin!`,
  `dispose-subtree!`)
  have a ledger of the owner-side call sites, each with a written reason, and
  the tool layer, the mode rows and the UI must contain none. The drain is the
  loop's `:agent-settled` boundary (`kmet.app.runtime/boundary!`, one at a time
  per root) — landed at 10b.
- **No awaitable, no `drain-await`.** `dispose!` and `plugin!` are synchronous,
  so there is nothing in flight to drain. The turn-boundary quiesce is the
  loop's own awaitable (`:agents`' `when-idle`), not a kernel primitive.
- **Async teardown is the plugin's own promise**, awaited by the app at the
  boundary. The kernel never awaits a disposer.

Alternatives, and why not:

| Alternative | Why not |
|---|---|
| drain-owner thread + queue (the full port) | correct, but ~250 lines of kernel, an awaitable module and a drain test to serialize something the app already serializes: every plugin mutates the kernel from `apply`, on the loop |
| one global lock around inline passes | deadlocks when a transition waits on another thread's `provide!`; cannot express "this fiber is mid-unload" |
| one thread per fiber | `apply` may block, so fiber-per-thread makes every plugin author a concurrency author |

## 9. Deleting `kmet.libs.scope` — done at 4e

`kmet.libs.scope` was kmet1's hand-rolled fiber: with no lifecycle object, it
invented owner paths and a keyed store to stand in for one. The light kernel
supplies the lifecycle, so the store lost its reason to exist — but only once
the registries that read it had moved. The kernel never depended on it (a guard
test bans the require), and the sites went with their landings, counted from
`(scope/…` call sites: landing 3 took commands, prompts, skills and tools off it
(a `kmet.app.registry` handle, with the store as kmet1's backend), 4b-ii-a
took the extension host's nine, 4c-ii-b took the event bus's `in-subtree?`, and
4d took `owner-listing`'s walk.

Landing **4e** deleted what was left:

| Deleted | Size |
|---|---|
| `kmet.libs.scope` (+ `test-scope`, `test-scope-self-contained`) | 288 lines + 2 test namespaces |
| `kmet.agent.registry-store` (the handle's store backend) | 63 lines |
| `app_runtime`'s `node`/`app-node`/`with-reported-cleanups`, and the `*error-reporter*` rebinding | ~25 lines |
| `cli.clj`'s `(runtime/node)` force (the comment claimed extension loading needed the store; it stopped doing so at 4b-ii-a) | 1 call |
| `extensions/context.cljc`'s `kmet.libs.scope` allowlist entry | 1 line |

Two things went differently than the plan above recorded, both measured:

- **Cleanup-error reporting did not degrade.** The plan expected the
  `*error-reporter*` rebinding to leave a gap until the `:logger` service
  (§10). It does not: `dispose-deregisters!` caught through the reporter only to
  call `kmet.app.debug/log-error`, so it now calls that directly — same
  destination, one indirection fewer.
- **Four test namespaces, not twelve.** `test_commands`' "other registries share
  the store" case was about the store's key shape and became unrepresentable
  once each tag owns a service; it is now "a registration lands in one tag
  only". `test_skills` (×2), `extensions/test_session` and
  `extensions/test_resources` read through the registry handle
  (`registry/by-owner` / `app-runtime/deregister-owner!`), and `test_registry`
  lost its second backend and its `doseq`. `test_utils` was already off the
  store.

What the app loses with `ordered-registrations` is per-tag display order written
in one place: each registry now owns its own (the atom handle's registration
order), and `deregister-owner!` walks tags instead of matching key heads. The
value-guarded stale deregister is the handle's entry-identity disposer. `/units`
was not lost: 4d re-rendered it from the ctx tree (`docs/architecture.md`,
"Inspection"). `scope.md` went with the code in the same change: its surviving
rules moved to the homes that implement them (ownership and display order into
`kmet.app.registry`'s docstring, the snapshot and tool-capture rules into
`docs/architecture.md`'s "Reload and the turn boundary", disposal order into §4 above),
and the citations either dropped the pointer or repointed at one of those.

Gate, met: no `(scope/` under `kmet.agent.*`, no `kmet.libs.scope` or
`kmet.agent.registry-store` require anywhere, `bb test` green, and `kmet --app
old` loads with no store at all.

## 10. Deferred

Recorded so that re-adding one is a lookup. "Additive" means the light surface
grows a keyword or a key, not a new shape.

| Mechanism | Cordis member | Why out | How it comes back |
|---|---|---|---|
| Logger service (facades, exporters, formatters, palette, buffer) | `logger.ts` | kmet already has `kmet.app.debug`; a second logger is a copy | `:logger` is a service — provide a richer one |
| `intercept` chain | `ctx.intercept`, `[Service.resolveConfig]` | unused by the app; labels and realms carry the isolation it was needed for | inject values are reserved: they become intercept config |
| `Service` base / `defservice` | `service.ts` (`:check :invoke :extend :resolve-config`) | `:check` is a `provide!` option; `:invoke`/`:extend`/`:resolveConfig` are class ergonomics (the last needs `intercept`, which is out too) | a map with `:check` already exists on `provide!`; `:extend` is a second `provide!` |
| `accessor`, `mixin`, `set-service!` | `reflect.ts` | no proxy to feed; a service re-provides, which is one effect | new keys on `provide!` |
| isolate **labels** | `ctx.isolate(name, label)` | siblings sharing one realm nest under a common isolating ctx | a label argument on `isolate` |
| 9 `internal/*` events, `internal/listener` claiming | `events.ts` | 3 have a consumer (§5); the rest are debug/inspection plumbing with none | new names on the existing emit |
| `parallel` / `serial` | `events.ts` | awaited, so they need the app's awaitable — the kernel has none (§8), and the app's submission queue is a drain, not an awaitable | `kmet.app.events` over `srce/listeners` once something needs them (`bail` and `waterfall` landed there: §5) |
| Lazy config resolution, `{:schema :transform}` | `config.ts` | `apply` validates its own map; a validator fn composes | `:config` accepting a schema map |
| Fiber lifecycle beyond 4 states (`:loading` `:unloading`, `inertia`, epoch drop-rule) | `fiber.ts` | one thread makes the race unrepresentable | states on `:state`; the epoch is the provider set |
| `fiber.store` snapshot, `fiber.await`, `assert-active!` | `fiber.ts` | sync: there is nothing to await; the per-fiber facts the listing needs are `:provides`/`:inject`/`:waiting-for` on the record (§4), not a store snapshot | derived from `:state` and the record's own keys |
| awaitable, drain owner, `drain-await` | (kmet2 addition) | a queue and a thread to serialize what the app already serializes | the optimistic pass flag + `settle!` (§3) are the replacement; an awaitable, if ever needed, is the app's |
| `DisposableList`, `EffectMeta` labels, `utils.ts` traceable | `utils.ts` | a vector and a map cover it | `:label` on `effect` is already accepted |
| JS syntax: `Proxy`, decorators, `instanceof`, prototype splicing | — | not semantics | never |

`update!` came back as this table said it would — an `:update` hook on the
plugin map, with `srce/update!` over it (§4/§6). The rest of `fiber.update` did
not: its config waterfall (a veto chain over the new config) is app policy, and
the kernel's validator fn already is the place a config is checked.

## 11. Parity matrix

Every public member of the vendored tree that has a decision, with the light
kernel's state (`EventsService.dispatch`/`register`/`unregister`,
`ReflectService.props`, and the JS inspect hooks are implementation detail, not
rows). `keep` = ported in the light kernel, `deferred` = §10, `never` = JS-only.
The conformance suite is written from §3–§8 and this table's `keep` rows.

### context.ts

| Cordis | srce | light |
|---|---|---|
| `new Context()` | `srce/root` | keep |
| `ctx.extend(meta)` | `srce/extend` | keep |
| `ctx.isolate(name, label?)` | `srce/isolate` (no label) | keep |
| `ctx.intercept(name, config)` | — | deferred |
| `ctx.root` | `:kernel` (shared state), no root ctx pointer | keep (shape differs) |
| `ctx.baseUrl` | `:base-url` | keep |
| `ctx.events` / `.logger` / `.reflect` / `.registry` | `:listeners` / `:logger` service / `provider` / `:runtimes` | keep |
| `ctx.fiber` | `:fiber` | keep |
| `Context.is` | `srce/ctx?` | keep |
| `Context.effect` / `filter` / `isolate` symbols | `:srce/effect`, `:filter`, `:isolate` | keep |
| `Context.intercept` symbol | — | deferred |
| get / set / has traps | `srce/get`, `provide!`, `srce/provider` | keep |
| reserve names (`isSpecialProperty`) | — | never |
| `nodejs.util.inspect.custom` | `print-method` | deferred |

### reflect.ts

| Cordis | srce | light |
|---|---|---|
| `ctx.get(name, strict?)` | `srce/get` (no strict flag) | keep |
| `ctx.set(name, value)` | — (`provide!` again) | deferred |
| `ctx.provide(name, value?, check?)` | `srce/provide!` | keep |
| `ctx.accessor(name, {get, set})` | — | deferred |
| `ctx.mixin(source, keys)` | — | deferred |
| `reflect.notify(names, filter?)` | the refresh pass (§3); no `filter` argument — the provider-set recompute subsumes Cordis's isolate filter | keep (internal) |
| `reflect.props` | — | deferred |
| `reflect.store` | the ctx's `:store` atom | keep (shape differs) |
| `Impl {name, fiber, value, check}` | `{:name :fiber :value :check}` | keep |
| `reflect.trace` / `reflect.bind` | — (explicit `ctx`) | never |
| `internal/get` / `internal/set` waterfalls | — | deferred |

### fiber.ts

| Cordis | srce | light |
|---|---|---|
| `FiberState` (6 states) | `:pending :active :failed :disposed` | keep (reduced) |
| `fiber.uid` / `.name` / `.config` / `.state` / `.error` | same, plus `:inject` / `:provides` / `:waiting-for` (§4) | keep |
| `fiber.store` / `.inertia` | `:provides` (§4) / — | deferred (the listing fields are on the record instead) |
| `fiber.ctx` / `.parent` / `.runtime` | `:ctx` / `:parent` / `:runtime` | keep |
| `fiber.effect(body, label)` | `srce/effect` | keep |
| `fiber.getEffects()` | `srce/effects` | keep |
| `fiber.await()` | — | deferred |
| `fiber.restart()` | `srce/restart!` (dispose + mount) | keep |
| `fiber.update(config, noSave)` | — (`restart!`) | deferred |
| `fiber.dispose()` | `srce/dispose!` (sync) | keep |
| `fiber.assertActive()` | `:srce/inactive-effect` on `effect` | keep |
| `{effect: …}` metadata on a disposer | `:label` on `srce/effect` | keep |
| `rootFiber.dispose()` (restarts) | `dispose!` on the root fiber; the app remounts, no implicit restart | keep (differs) |
| `ValidationError` (config/plugin shape) | the plugin's `:config` throw, or `:srce/bad-plugin` for the map itself | keep (shape differs) |
| `CordisError/INACTIVE_EFFECT` | `{:type :srce/inactive-effect}` | keep |
| `resolveConfig(runtime, config)` | the plugin's `:config` fn | keep |

### registry.ts

| Cordis | srce | light |
|---|---|---|
| `ctx.plugin(plugin, config?)` | `srce/plugin!` | keep |
| `ctx.inject(deps, callback)` | `srce/inject!` | keep |
| `registry.resolve(plugin)` | `srce/plugin-source` | keep |
| `registry.get` / `.has` / `.delete` | `srce/runtime` / `srce/runtime?` / `srce/dispose-plugin!` | keep |
| `registry.keys` / `.values` / `.size` | `srce/runtimes` | keep |
| `registry.entries` / `.forEach` | `srce/runtimes` (fiber → runtime via `:runtime`) | keep (folded) |
| `registry.counter` | the root's uid allocator | keep (internal) |
| `Plugin.Runtime {name, fibers, callback, Config}` | `{:name :fibers :source :config}` | keep |
| `Inject.resolve` | `srce/normalize-inject` | keep |
| `@Inject` (class) | `:inject` metadata | keep |
| `@Inject` (method) | — | never |
| `Plugin.Function` (the plugin *is* a fn) | — (one map shape; the body is `:apply`) | deferred |
| `Plugin.Object` | the plugin map | keep |
| `Plugin.Constructor` | — | never |
| `Plugin.Transform` | `:config` as a validator fn | keep |

### events.ts

| Cordis | srce | light |
|---|---|---|
| `ctx.emit` | `srce/emit!` | keep |
| `EventsService.dispatch` (resolve + filter) | `srce/listeners` | keep |
| `ctx.parallel` / `.serial` | — (they need an awaitable) | deferred |
| `ctx.bail` | `srce/bail!` | keep |
| `ctx.waterfall` | `srce/waterfall!` (`(f event next)`) | keep |
| `ctx.on` / `.once` | `srce/on` / `srce/once` (a `once` registration keeps the listener's metadata) | keep |
| `{prepend, global}` | — | deferred |
| dispatch `thisArg` filter | `{:filter (fn [listener-ctx] …)}` | keep |
| disposer returning removal status | same | keep |
| `isBailed` | `srce/bail!` walks `(some? result)` inline; `false` bails (a Clojure value is not an absence) | keep (reduced) |
| `AggregateError` from `parallel` | — | deferred |
| `internal/listener` claiming a registration | — | deferred |
| nine `internal/*` events | three `:srce/*` names | keep (reduced) |

### service.ts, logger.ts, utils.ts

| Cordis | srce | light |
|---|---|---|
| `class Service` + constructor registration | — | deferred |
| `[Service.check]` | `{:check fn}` on `provide!` | keep |
| service registered in a realm's store, seen by siblings | `{:in ancestor}` on `provide!` (the entry lands in that store, the lifetime stays the provider's fiber) | keep |
| `[Service.filter]` (realm comparison) | the realm walk in `get` / the refresh pass | keep (internal) |
| `[Service.invoke]` (callable) | — | deferred |
| `[Service.extend]` | — | deferred |
| `[Service.resolveConfig]` | — | deferred |
| `logger.ts` (`ctx.logger`, exporters, formatters, palette, buffer) | `srce/log!` → the `:logger` service | deferred |
| `DisposableList` / `EffectMeta` labels | a vector and a map | keep (internal) |
| `isBailed` / `isObject` | `(not (nil? v))` at call sites / `map?` | deferred |
| `getTraceable` / `Tracker` / `withProps` / `createCallable` | — (explicit `ctx`) | never |
| `symbols`, `isConstructor`, `getPropertyDescriptor`, `composeError` | keyword keys, `:srce/*` meta, `ex-info` | never |

## 12. Gates

- 60 contract tests (237 assertions) — one per bullet in §2–§8 that can fail,
  one per `keep` row of §11, table-driven where the rows are uniform. The behaviors: realm
  resolution and the boundary, including two **siblings** under one realm
  providing the same name (nearest visible wins, no throw — §3); duplicate
  provider in one realm path; `check`; inject required/optional and the
  `:srce/bad-inject` refusal; a bad plugin map throwing `:srce/bad-plugin` while a
  throwing `apply` is a `:failed` fiber; `inject!` with and without a name; the
  refresh fixpoint (2-plugin chain, provider swap, absent→present upgrade,
  churn budget clears the set, a `provide!` inside `apply` does not remount its
  own fiber); effect LIFO within and across effects; a throwing disposer still
  runs the rest; `:srce/inactive-effect`, including a registration from **inside a
  disposer**; `restart!` replaces the uid and is the retry for a failed fiber;
  `dispose-plugin!` ends every fiber and drops the runtime; `:failed` on a
  throwing `apply` unwinds the effects it registered and frees the names it
  provided, with dependents `:pending`; `{:in ancestor}` providing into a
  scope the fiber does not own, visible to a sibling and gone with the fiber;
  listener order, `once`, `:filter`, a throwing listener, `{:data}` riding a
  registration and reported by `collect-all!` (including through `once`),
  `dispatch-modes` naming exactly the modes with a fn behind them, `bail!`
  short-circuiting, `waterfall!` wrapping and vetoing, a throwing
  listener reading as nil in every mode, `event-names` over the chain, and
  `listeners` returning the resolved list every mode walks; `inside?`; `log!`
  with no `:logger`; a plain child ctx detaching (and `:srce/ctx-busy` while
  anything is still mounted under it, with a fiber's disposal making it
  detachable); the fiber record's
  `:inject`/`:provides`/`:waiting-for` matching the store after a mount, a
  provide and a dispose; the pass flag — two threads entering `provide!` at
  once run one pass owner, both changes land, and `settle!` returning with no
  other owner means `:changes` empty, answering `{:rounds 1}` for an empty
  pass; `update!` refusing a plugin without `:update` (`:srce/no-update`) and a
  non-active fiber (`:srce/inactive-update`), passing the hook the old and the
  resolved new config, keeping the uid so no dependent remounts, storing
  nothing when the validator throws, and unwinding what the hook registered
  when it throws; a hook that provides a new name reloading a dependent; and a
  teardown reporting the disposer that threw (with its label, its throwable and
  its message) while the rest still ran, a dispose performed by a disposer
  reporting into the outer run, `dispose!` answering `{:disposed 0 :failed []}`
  a second time, and `dispose-subtree!` aggregating the same shape; and a
  non-fn `:update` rejected as `:srce/bad-plugin`.
- srce self-containment: `kmet.srce.*` may require only rank-0 code
  (`kmet.libs.*`, `kmet.loader.*`, `kmet.reakt.*`) and third-party code — no
  `kmet.agent.*`, no `tui`/`tk`, nothing at its own rank or above.
- **No `kmet.libs.scope` require in `kmet.srce.*`** (§9), and after 4e no
  `kmet.agent.*` require either — the deletion gate.
- No leak: `scope`-free equivalent — a test disposes a 3-plugin tree and
  asserts `(srce/runtimes ctx)` empty, every `:children` empty, and the service
  store empty.

## 13. Open decisions

| Question | Recommendation |
|---|---|
| **Synchronous `apply`** — the one cut that is not additive | yes. Reversing it changes `plugin!`'s return from a fiber to an awaitable, so every caller. Async work is the plugin's own promise |
| `get` on a name provided by a non-active fiber | nil, one `get`, no `strict` flag |
| `(:tools ctx)` through `ILookup` | not ported. `srce/get` is explicit; destructuring and `contains?` stay honest about nil |
| One provider per name: realm-level or visibility-level | visibility (per ctx store + the walk). A realm-level store is Cordis's shape: it needs a realm-key symbol table (the labels §10 defers) and makes a sibling provider throw mid-tree. Two siblings may provide the same name — `isolate` is how a subtree gets its own |
| `bail` in the kernel or in the app | settled at landing 2: **the kernel**, and `collect-last`/`collect-all`/`boundary` with it. With `{:data …}` on a registration no mode needs an app concept (the owner is opaque kernel data), so all six folds sit with the table they walk — Cordis's cut. The app keeps the vocabulary and the owner value |
| Whether the thread rule needs an assertion in the kernel | no — a portable thread identity does not exist on all three hosts; the app's guard test asserts the submission instead. §8 distinguishes append-only mutations (late-safe) from ones that run plugin code |
| `:config` validator: fn, or a schema map | fn. malli compiles to a fn; the profile layer composes. It runs per mount, raw config in, resolved config out |
| `:name` on a plugin: keyword or symbol | keyword, with the var path as `:source`. Required — there is no fn name to fall back to |
| What `dispose!` on the root fiber does | disposes (unwinds every root effect); the app remounts. No implicit restart — a restart after dispose is `plugin!` again |
| Does a pass reload reuse the fiber? | yes — `unload!` + `activate!` on the same fiber and uid. `restart!` is the new-uid operation. Otherwise a provider swap would churn uids and dependents would remount twice |
| `:resolver`: ctx key or service? | inherited ctx key (`(root {:resolver f})`), not a service: it is policy carried to the loader seam, and a service would need provide/dispose for something the kernel only forwards |
| What does an in-place config update tell dependents? | nothing — they keep the value they resolved until their own reload. A config change does not churn the tree, and "a dependent sees the new provider" (§3) stays a rule about provider *sets*: `update!` cannot change the name the fiber holds, so replacing a service is `restart!` |

## 14. Not doing

- Porting JavaScript syntax rather than semantics: TS declaration merging,
  decorators, prototype property mixins, the `Proxy`-based context,
  `Symbol.hasInstance`.
- Porting the separate `@cordisjs/plugin-loader` / `plugin-hmr` /
  `plugin-logger-console` packages: profiles/bundles/patches are kmet's loader,
  HMR is deferred until the kernel settles, and the console exporter is
  `kmet.app.debug`.
- The Koishi plugin market / `package.json` service manifest.
- Re-deriving a mechanism kmet already owns: the event modes (`agent/event-bus`
  is the *dispatcher layer* over `srce/listeners`, not a second registry), the
  logger (`agent/debug`), the loader (`loader/core`). The kernel composes them,
  it does not reimplement them.
- Chasing parity with the vendored tree. §11 is the record; §10 is the
  justification; `keep` rows are the contract.

## 15. Kernel sketch

The shapes the four namespaces are built from. Not a full listing — §3–§8 are
the contracts and the tests pin them — but every load-bearing mechanism, with
the numbers the "light" claim rests on.

| namespace | lines | what it owns |
|---|---|---|
| `ctx.clj` | 240 | ctx tree, store, isolate, `get`/`services`, the effect list, `log!` |
| `event.clj` | 170 | listener table, `listeners`, `on`/`once`, `names`, `invoke!`, and the six modes `emit!`/`bail!`/`waterfall!`/`collect-last!`/`collect-all!`/`boundary!` |
| `fiber.clj` | 474 | inject, the refresh pass, mount/unmount, dispose axes, runtimes |
| `core.clj` | 161 | the public aliases and `defplugin` |

`ctx.clj` requires no sibling; `event.clj` requires `ctx`, `fiber.clj` requires
both, `kmet.srce.core` aliases the three. The effect primitive lives in `ctx.clj`,
not `fiber.clj`, because a plugin mount registers listeners (`event` → `effect`)
and a listener must be reversible: with `effect` in `fiber`, those three form a
require cycle.

```clojure
;; a ctx is a plain map; root state is shared, never pointed back at
{:srce/ctx true
 :parent ctx :children (atom []) :name nil
 :isolate #{:tools}                          ; realm for these names
 :store (atom {name {:name :fiber :value :check}})
 :listeners (atom {event-name [f …]})
 :fiber (atom {…})                           ; one atom per fiber, one swap! per transition
 :kernel {:runtimes (atom []) :mounted (atom []) :counter (atom 0)
          :pass (atom nil) :changes (atom [])}}
```

```clojure
;; 2 — resolve up the tree, stop at a realm
(defn provider [ctx name]
  (loop [c ctx]
    (when c
      (or (clojure.core/get @(:store c) name)
          (when-not (contains? (:isolate c) name) (recur (:parent c)))))))

;; 5 — one effect = one entry; LIFO; single-shot; the state gate is the
;; closed-store guard
(defn effect [ctx f & [label]]
  (let [fiber (:fiber ctx) state (:state @fiber)]
    (when-not (= :active state)
      (throw (ex-info "inactive fiber" {:type :srce/inactive-effect :state state})))
    (let [undos (atom []) live? (atom true)
          undo (fn []
                 (when (compare-and-set! live? true false)
                   (reduce (fn [_ d]
                             (try (d) (catch Throwable t (log! ctx :error "disposer threw" t))))
                           nil (rseq @undos))))]
      (swap! fiber update :effects conj {:label (or label :effect) :undo undo})
      (let [r (f)]
        (swap! undos into (cond (nil? r) [] (sequential? r) r :else [r])))
      undo)))

;; 1+3 — the epoch is the provider set; a change reloads the same fiber.
;; An unchanged set is a no-op, so a failed fiber is never retried by the pass.
(defn- refresh! [fiber]
  (let [{:keys [ctx inject state epoch]} @fiber]
    (when-not (= :disposed state)
      (let [next-epoch (providers ctx inject)]
        (when (not= next-epoch epoch)
          (reload! fiber next-epoch))))))

;; 3 — one pass flag; the pass never runs reentrantly
(defn settle! [ctx]
  (let [{:keys [pass changes]} (:kernel ctx)]
    (loop []
      (when (compare-and-set! pass nil :running)
        (try (run-pass! ctx) (finally (reset! pass nil))))
      (when (and (seq @changes) (nil? @pass)) (recur)))))

;; 4 — one table, resolved once; the modes are walks over this list
(defn listeners [ctx name {:keys [filter]}]
  (loop [c ctx acc []]
    (if-not c acc
      (recur (:parent c)
             (into acc (for [f (get @(:listeners c) name)
                             :when (or (nil? filter) (filter c))]
                         {:ctx c :f f}))))))
```

- A **listener** is `(fn [event])`; the listener's own ctx is visible only to
  the `:filter`, which is what keeps the table one shape for every mode but
  `waterfall` (`(f event next)`, where the extra argument is the mechanism).
- **`:srce/*` keywords are explicit** (`:srce/inactive-effect`,
  `:srce/duplicate-service`, `:srce/bad-plugin`, `:srce/bad-inject`,
  `:srce/bad-source`, `:srce/churn`) — a public error keyword should not move
  when a namespace does.
- **`provide!`** is a `provider` duplicate check plus one effect; the effect's
  body inserts the entry and adds to the fiber's `:provides`, its disposer
  removes both and records the change. `:check` is stored with the entry.
- **`activate!`** sets `:active` *before* `apply` runs (so `apply` may register),
  and a throw sets `:failed` + `:error` *before* unwinding the partial effects,
  then emits `:srce/failure`.
- **`dispose!`** flips to `:disposed` first, unwinds, detaches from `:mounted`,
  drops the runtime when its last fiber is gone, and settles.
- **No protocols, records, multimethods or dynamic vars.** One macro
  (`defplugin`), three `compare-and-set!`s (the pass flag, each effect's
  single-shot flag, the change-set drain), no Java interop, no awaitable, no
  clock, no queue.
- **Not built**: a `:root` pointer, a `:disposing` state, a realm-key table,
  a schema library, a logger, HMR. Each is a §10 row, not an omission.
