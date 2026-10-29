# srce (❤️)

**srce** (Cyrillic **срце**, IPA /sr̩̂t͡se/, roughly "SIR-tseh") is "heart" in
Serbian — the thing every other part is wired through. It is the kernel kmet
mounts itself on: a context tree, services claimed by keyword, plugins as data,
one listener table, and registrations that unwind.

It has no third-party dependencies and one public namespace,
`kmet.srce.core`.

## Why a kernel

kmet runs the same process in many shapes. A profile picks the rows, a mode
adds its own, extensions load at runtime, and every agent gets a child context
of its own. Those parts do not arrive in one order and they do not live
forever: a row may mount before the service it needs, an extension unloads, a
run ends, `/reload` replaces a configuration.

Three rules make that survivable, and they are what srce is:

- **Names, not imports.** A capability claims a keyword (`:tools`, `:ui`,
  `:permissions`) and everyone else asks for the keyword. Nothing imports the
  implementation, so a test, a profile or an extension can supply another one
  without touching the consumer.
- **Requirements, not boot order.** A plugin declares the services it needs;
  it waits (`:pending`) until they exist and becomes `:active` when they do.
  Mounting is safe in any order, and a service that appears later upgrades its
  dependents automatically.
- **Disposal is an operation.** Every registration a plugin makes is an effect
  of its fiber. Unmounting unwinds them LIFO, so reload and teardown are one
  call rather than a convention every subsystem has to remember.

The kernel stays small on purpose: four namespaces, no protocols, no records,
no dynamic vars, no queue, no clock. What it does not do is listed in
[`srce.md`](src/kmet/srce/srce.md) §10 with the mechanism it came from and the
seam that would make adding it back additive.

## Roots: Cordis

The design is **Cordis's** — the plugin meta-framework the DeepSeek Harness is
built on — reduced to the concepts kmet actually mounts. The name is the same
word: Cordis is Latin for heart.

Nothing is vendored. srce re-implements the semantics in Clojure and keeps a
deliberate subset: a plugin is a map instead of a `Service` subclass, events
have six synchronous modes instead of awaiting ones, and the kernel has no
logger subsystem, no interception chain and no drain thread. Every Cordis
member that got a decision — `keep`, `deferred` or `never` — is in
[`srce.md`](src/kmet/srce/srce.md) §11, so re-adding a mechanism is a lookup
rather than a port.

## The five principles

srce keeps Cordis's five ideas one for one. They are the whole design; the
sections below are usage, and
[`srce.md`](src/kmet/srce/srce.md) §3–§8 are the contracts.

### 1. A plugin is data

A plugin is a validated map, not an object with a lifecycle protocol:

```clojure
{:name    :tool/web-search     ; required — the runtime name and the mount's ctx name
 :inject  {:tools nil}         ; required deps; nil = required, :optional = upgrade when present
 :config  (fn [cfg] cfg)       ; optional validator; runs on every mount and remount
 :provide #{:web-search}       ; optional claim, checked after apply
 :apply   (fn [ctx config] …)} ; required — the effect body
```

`defplugin` gives the map a name and checks it as it loads; `plugin!` checks it
on every mount. Nothing to implement, and an inspection tool can read a plugin
without mounting it.

### 2. A context is a service registry

A ctx is a plain value: a node in a tree, a store of names, a listener table
and a fiber. A provider claims a keyword with `provide!`; a consumer finds it
by keyword with `get`, walking up the tree. Nothing imports an implementation,
and `isolate` makes a subtree a realm so two agents can each have their own
`:permissions` without a global name table.

### 3. Dependencies are declared, not sequenced

`:inject` names what a plugin needs. A required name holds the fiber in
`:pending`; an optional one participates in the provider set, so the plugin
upgrades when a richer implementation attaches. A provider change runs one
fixpoint pass: dependents reload in place, and **mount order stops mattering**.
This is the principle the composition leans on hardest — a profile bundle, an
extension directory and a `--mode` flag all mount in arbitrary order.

### 4. Events are one listener table, walked six ways

`on` and `once` register; `emit!`, `bail!`, `waterfall!`, `collect-last!`,
`collect-all!` and `boundary!` are folds over the same resolved list. A
registration may carry opaque `:data`, so no mode needs an app concept, and a
listener hears events emitted from its own ctx and its descendants. The kernel
does not own the vocabulary of an application — a plugin declares its own
events.

### 5. Every registration is a reversible effect

`provide!`, `on`, `once` and `effect` all return disposers and all belong to a
fiber. Disposal unwinds them LIFO, one effect at a time, last created first.
That is what makes reload, extension unload and per-agent teardown predictable:
the thing that registered something is the thing that removes it.

## Install

srce is a workspace module, not a published library. In this checkout it is
already on every host's classpath: the workspace manifest lists it, `bb.edn`
puts it on the babashka path, and the module graph carries it to the JVM and
Jolt. Elsewhere, put `modules/srce/src` on the classpath and require one
namespace:

```clojure
(:require [kmet.srce.core :as srce])
```

There is nothing else to add: the kernel requires no sibling module and no
third-party library.

## Quick start

```clojure
(ns my.greeter
  (:require [kmet.srce.core :as srce]))

(srce/defplugin greeter
  {:name :greet/greeter
   :provide #{:greeter}
   :apply (fn [ctx _config]
            ;; {:in (:parent ctx)} shares the service with the mount's
            ;; siblings; leave it out to keep it inside this plugin's subtree.
            (srce/provide! ctx :greeter
                           (fn [name] (str "hello, " name))
                           {:in (:parent ctx)}))})

(def ctx (srce/root))                    ; a root ctx is a value, not a singleton

(def fiber (srce/plugin! ctx #'greeter)) ; mount; returns the fiber atom
(:state @fiber)                          ; => :active
(:state (srce/fiber-info fiber))         ; the same fact, as plain data

((srce/get ctx :greeter) "world")        ; => "hello, world"

(srce/dispose-plugin! ctx #'greeter)     ; unmount; the service goes with it
(srce/get ctx :greeter)                  ; => nil
(:state @fiber)                          ; => :disposed
```

A test builds its own `(srce/root)`; boot holds one and passes it down. Two
roots never share state.

## Usage

### Provide and read services

```clojure
(srce/provide! ctx :tools toolset)                  ; visible to ctx's subtree
(srce/provide! ctx :events bus {:in (:parent ctx)}) ; visible to siblings
(srce/provide! ctx :ui impl {:check (fn [] ready?)}) ; conditional on each read
```

- `get` returns the value, or nil when the name is absent, blocked by a realm,
  or currently unavailable.
- `provider` returns the provider record `{:name :fiber :value :check}` — also
  for a provider whose fiber is not active yet.
- `services` returns `{name provider-record}` for everything the ctx can see.
- `:check` is a thunk evaluated on every read and at each pass; false or a
  throw reads as "unavailable". It is not a revocation — dispose or re-provide
  to revoke.
- `{:in ancestor}` puts the entry in an ancestor's store while the lifetime
  stays the providing fiber. This is how a plugin mounted at the app's scope
  shares a service with its siblings. Without it the service is private to the
  plugin's own subtree, which is the right default for a per-agent capability.
- A second visible provider for the same name throws
  `:srce/duplicate-service`. Use a child ctx or `isolate` for an instance.

### Declare dependencies

```clojure
{:inject [:tools :llm]}              ; required, vector form
{:inject {:tools nil :llm nil}}      ; required, object form
{:inject {:tools nil :ui :optional}} ; :ui upgrades the plugin when it appears
```

Object-form values are reserved: they must be `nil` or `:optional`, and
anything else throws `:srce/bad-inject`. A plugin whose required names are
missing is `:pending`, with the missing names on `(:waiting-for @fiber)`.
Mounting the provider later settles it:

```clojure
(def consumer (srce/plugin! ctx #'needs-tools))
(:state @consumer)        ; => :pending

(srce/plugin! ctx #'tools) ; provides :tools into the mount site
(:state @consumer)        ; => :active
```

Provider changes are applied by a fixpoint pass. `plugin!`, `dispose!` and
`restart!` settle themselves; `(srce/settle! ctx)` runs any pending change at a
boundary the app defines. When a required provider is disposed, its dependents
go back to `:pending` with the name on `:waiting-for`; when an optional one
disappears, the dependent reloads and stays `:active`.

### Realms: per-agent instances

`isolate` makes a child ctx a realm for the names given. Lookups and providers
for those names stop at the boundary, so an inner subtree sees only its own
provider and the outer tree is unaffected:

```clojure
(def app (srce/root))
(srce/provide! app :permissions :app-wide)

(def agent-ctx (srce/isolate app :permissions))
(srce/provide! agent-ctx :permissions :agent-one)

(srce/get agent-ctx :permissions) ; => :agent-one
(srce/get app :permissions)       ; => :app-wide
```

Two siblings under one realm may each provide the same name; the nearest
visible provider wins per subtree. A repeated `isolate` for the same name is a
deeper boundary, not a redefinition.

### Effects

An effect is one reversible registration, run now and undone LIFO when its
fiber unloads:

```clojure
(srce/effect ctx
             (fn []
               (register! …)
               (fn [] (unregister! …)))   ; the disposer this effect returns
             :command/hello)              ; the label inspection renders
```

- An effect's body may return one disposer, a seq of them, or nil.
- The returned disposer is single-shot; calling it twice does nothing.
- `effects` returns the labels in creation order — the reverse of unwinding.
  Labels are an inspection contract, so make them say what was registered.
- **Keep related registrations in one effect.** A tool and its prompt section,
  a provider and its listener: one effect means teardown follows registration
  order. Two effects are two independent LIFO runs.
- A throwing disposer is logged through `:logger` and the remaining disposers
  still run.

Registrations require an `:active` fiber (`:srce/inactive-effect` otherwise),
including a registration attempted from inside a disposer. Register from
`:apply`, not from teardown.

### Events

```clojure
(def unsubscribe
  (srce/on ctx :my/event (fn [event] …)))  ; an effect of the current fiber

(srce/once ctx :my/event (fn [event] …))   ; disposed before it runs

(srce/emit! ctx :my/event {:x 1})          ; everyone runs, results ignored

(srce/bail! ctx :my/event event)           ; the first non-nil answer stops the walk

(srce/waterfall! ctx :my/event event)      ; (f event next); not calling next vetoes
```

| mode | use it when |
|---|---|
| `emit!` | notifications: every listener runs, the results do not matter |
| `bail!` | a veto or the first answer: a later listener cannot change it |
| `waterfall!` | around-middleware: each listener wraps the rest via `next` |
| `collect-last!` | a later answer overrides an earlier one |
| `collect-all!` | every contribution, paired with its registration's `:data` |
| `boundary!` | data accumulation: listeners merge returned maps into the event |

A listener hears emits on its own ctx **and its descendants**, so registering
on a subtree scopes it naturally. `{:filter (fn [listener-ctx] …)}` on a dispatch
receives each listener's own ctx. A listener that throws is logged and reads as
nil for its own rung; the dispatch carries on. `(srce/listeners ctx name
opts)` is the resolved list every mode walks (`opts` is nil or `{:filter f}`),
and `(srce/event-names ctx)` is what is registered on the chain.

A registration may carry opaque data:

```clojure
(srce/on ctx :resources-discover {:data {:owner :ext/lsp}}
         (fn [request] …))

(srce/collect-all! ctx :resources-discover request)
;; => [[{:owner :ext/lsp} result] …]
```

The kernel never looks inside `:data`; the app puts an owner there.

**Declaring events.** A plugin may declare the events it dispatches. The kernel
checks the shape (keyword name, keyword `:mode`, string `:doc`), carries the
declaration on the fiber for inspection, and does not interpret it — the
vocabulary belongs to the app:

```clojure
(srce/defplugin session-events
  {:name :events/session
   :events {:session-compact {:mode :emit
                              :doc "Fired after a successful compaction."}}
   :apply (fn [ctx _] …)})
```

The kernel's own three events are `:srce/status` (a fiber state transition),
`:srce/service` (a provider installed or removed) and `:srce/failure` (a
plugin's `apply` threw).

### Mount, restart, dispose

```clojure
(def fiber (srce/plugin! ctx #'web-search {:max-results 5}))
```

The source may be a var, a plugin map, or a keyword for the ctx's `:resolver`
(`(srce/root {:resolver f})`); `(srce/plugin-source source resolver)` is the
normalization on its own.

A fiber is an atom holding a plain map. Its state is one of **`:pending`**
(waiting for inject), **`:active`**, **`:failed`** (a throwing `apply` or
config) or **`:disposed`**. Code reads it through the kernel's projection:
`(srce/fiber-info fiber)` returns a map — `:uid :name :source :config :state
:error :inject :events :provides :waiting-for :effects :ctx` — and
`(srce/fibers ctx)` does the same for the root's mount list, in mount order,
which is what an inspection, a front end or a reconciliation walks.
`(srce/active? fiber)` is the one-state question. The record itself (`@fiber`)
is the kernel's: it also carries `:parent`, `:runtime`, `:plugin` and `:epoch`.

- A throwing `apply` does **not** throw out of `plugin!`: the fiber becomes
  `:failed`, its partial effects unwind, the names it provided are freed, and
  `:srce/failure` is emitted. `(srce/restart! fiber)` is the retry — a config
  or shape error keeps failing, a transient one recovers.
- `(srce/dispose! fiber)` unwinds everything and detaches the mount. It is
  idempotent and answers `{:disposed n :failed [{:label … :error … :message
  …}]}` — a throwing disposer is logged and listed (throwable and text), and
  the rest still run. The state flips first, so `get` already reads nil while
  the disposers run.
- `(srce/restart! fiber)` is dispose + mount again; it returns a new fiber with
  a new uid, so dependents remount.
- `(srce/update! fiber new-config)` changes a config **in place** — same fiber,
  same uid, so no dependent remounts and a dependent keeps the value it
  resolved until its own reload. It needs a plugin that declares `:update`
  (`(fn [ctx old-config new-config] …)`, `:srce/no-update` otherwise) and an
  `:active` fiber; `:config` validates the new value first, and a throw leaves
  the fiber as it was, unwinding what the hook registered. A plugin that cannot
  absorb a change is `restart!`.
- `(srce/dispose-plugin! ctx source)` ends every fiber of one source.
- `(srce/dispose-subtree! ctx)` ends every fiber under a ctx, children first.
  Both answer the same `{:disposed n :failed […]}` aggregate as `dispose!`.
- `(srce/settle! ctx)` applies pending provider changes and answers
  `{:rounds n}` (the fixpoint rounds that call ran).
- `(srce/runtimes ctx)` lists the plugin runtimes in mount order;
  `(srce/runtime ctx source)` / `(srce/runtime? ctx source)` ask about one.

Disposal axes stay distinct: the **fiber** (`dispose!`), an **effect** (its
disposer), the **ctx subtree** (`dispose-subtree!`) and the **plugin**
(`dispose-plugin!`). The root fiber is an ordinary fiber; disposing it unwinds
every root registration, and the app remounts the tree to restart.

### Child contexts

`(srce/extend ctx {:name :scratch})` makes a child ctx with metadata; it is not
a mount and has no fiber of its own (it uses the nearest one). Such a child has
no automatic lifetime, so `(srce/detach! child)` removes it from the tree when
you are done — it refuses with `:srce/ctx-busy` while anything is still mounted
under it. The ctx keeps its `:parent` chain, so a value that still holds it
resolves services as before.

- `(srce/children ctx)` — children in creation order.
- `(srce/inside? child ctx)` — is child in ctx's subtree?
- `(srce/mount-fiber ctx)` — the fiber whose mount created this ctx, or nil for
  a plain child and the root.
- `(srce/attr ctx :my/key)` — the nearest non-nil value on the chain.

### Logging

The kernel writes nothing to stdout. `(srce/log! ctx level & args)` calls the
`:logger` service; absent means silent. Provide one at boot:

```clojure
(srce/provide! ctx :logger (fn [level & args] …))
```

Kernel failures, throwing disposers and throwing listeners all go there.

### Never print a ctx

A ctx is cyclic (`ctx → :kernel → :runtimes → fiber → :ctx`), and no host's
default printer detects it: `pr-str` of a ctx, or of any value holding one,
recurses until the host fails. Report names, counts and states —
`(:state @fiber)`, `(count (srce/children ctx))`, a service's name — and never
the value. The same rule is why a service value must not carry its ctx: bind it
in a closure when a service needs one.

## Threading rule

- The kernel has no lock. Every mutation that runs plugin code — `plugin!`,
  `restart!`, `dispose!`, `dispose-plugin!`, `dispose-subtree!` — stays on the
  thread that owns the root ctx. A disposer may re-enter the kernel, and a
  concurrent pass could otherwise remount a dependent mid-teardown.
- Registrations that only append — `provide!`, `on`/`once`, `effect` on an
  already-active fiber — are safe from any thread, but **late**: they record
  and return, and the owner's next `(srce/settle! ctx)` applies them.
- The app marshals: plugin work, reloads and approvals are submitted to its
  loop, which settles at the turn boundary. `(srce/settle! ctx)` is the call.

Async work is a plugin's own promise, awaited by the app, never by the kernel.
`apply` is synchronous, and synchronous `apply` is the one cut that is not
additive — see [`srce.md`](src/kmet/srce/srce.md) §13.

## Errors

| `:type` | thrown when | fix |
|---|---|---|
| `:srce/duplicate-service` | `provide!` for a name already held on the way up from the target scope | provide in a child ctx or `isolate`; dispose the old provider first |
| `:srce/inactive-effect` | a registration on a fiber that is not `:active` | register from `:apply`, not from a disposer or after dispose |
| `:srce/bad-plugin` | the plugin map fails its shape check (`:problems` says which keys) | fix `:name` (keyword), `:apply` (fn), `:inject`, `:config` or `:provide` |
| `:srce/bad-inject` | an inject value other than `nil` or `:optional` (thrown by `normalize-inject`; `plugin!` reports it as `:srce/bad-plugin` with the offending keys in `:problems`) | use the two supported values; other values are reserved |
| `:srce/bad-source` | a keyword source with no `:resolver`, or an unsupported source value | pass a resolver to `root`, or mount a var/map |
| `:srce/ctx-busy` | `detach!` while child ctxs remain | dispose the mounts under it first |

A pass that cannot reach a fixpoint within 8 rounds logs `:srce/churn` through
`:logger` and clears the pending set; nothing is forgotten, and the remaining
remounts wait for the next change.

## What srce deliberately does not do

No async dispatch modes (`parallel` and `serial` need an awaitable the kernel
does not have), no interception chain, no `Service` base class or accessors, no
isolate labels, no logger subsystem, no HMR, no update-in-place (`restart!` is
the config change), no drain thread. Each is a row in
[`srce.md`](src/kmet/srce/srce.md) §10 rather than an omission, with the
mechanism it came from and how it would come back additively. The parity matrix
in §11 records the rest.

## Read next

| Document | What it covers |
|---|---|
| [`src/kmet/srce/srce.md`](src/kmet/srce/srce.md) | the implementation note: contracts, thread rule, parity matrix, deferred list |
| [`new-app.md`](../../new-app.md) | the app's design record: open seams, shipped limits, declined options |
| [`docs/architecture.md`](../../docs/architecture.md) | module map, ranks and the contributor workflow |
| [`modules/app/src/kmet/extension.md`](../app/src/kmet/extension.md) | the extension contract, which mounts on this kernel |
| [`docs/extensions.md`](../../docs/extensions.md) | authoring extensions |
