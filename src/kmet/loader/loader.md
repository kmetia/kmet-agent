# kmet Loader — context/classloader interface (design + implementation plan)

Goal: one portable **Loader** abstraction that gives kmet's extension system
real isolation on every host — bb, JVM Clojure and Jolt — and that can be
promoted to a standalone library afterwards. It is the answer to the
**extension-isolation** workstream in `jolt-port.md` §B3 (a hard blocker for
the port).

Status: design locked; implementation staged (see §9). **Phases 0, 1 and 2
are implemented**: `src/kmet/loader/core.clj` (protocol, generic body,
combinators, host root), `src/kmet/loader/memory_loader.clj` (in-memory backend),
`src/kmet/loader/sci_loader.clj` (SCI code backend: locate/read/eval, SCI's
`require` routed through the loader, injected share list), the conformance
suites (`test/kmet/loader/test_core.clj`, `test/kmet/loader/test_sci_loader.clj`)
and the extension runtime rewiring (`kmet.app.extensions` builds one
loader per extension; the loader lives outside the shared `kmet.libs.*`
layer — host machinery, not contract). The library keeps this doc and a
self-containment guard (`kmet.loader.test-self-contained`) so
`src/kmet/loader/` can be extracted as a whole.

Phase 2, the **native Jolt backend**, is implemented in the *Jolt* repo
(`stdlib/jolt/loader.clj`), tracked in jolt-lang/jolt#912 (the
classloader-lite request) and jolt-lang/jolt#1039 (the implementation),
with `test/chez/loaderconf-test.clj` as its writ — the 20-case suite
`make loaderconf` runs, baseline empty. kmet consumes it through its own
adapter, `src/kmet/loader/jolt_loader.jolt`, and the extension system evaluates
natively on Jolt through it when the manifest declares `:jolt` (the fallback
to the SCI backend for `:sci`-only manifests is the Phase 1 backend, on Jolt
too — see §9 Phase 2 for what landed, on both sides,
and what remains of §6.1's M0–M4 plan).

Not implemented yet: Phase 3 (JVM native backend + hybrid — deliberately
last: the JVM host already gets isolation through SCI) and Phase 4
(promotion).

Related docs (repo-root relative): `jar-ext.md` (extension artifact
format — the loader it touches), `jolt-port.md` §B3,
`extensions/extensions.md` (the extension contract), `src/kmet/tui/tui.md`
(house style for a package reference doc).

---

## 0. Decisions locked (do not re-litigate in implementation)

1. **Protocol name `Loader`**, namespace `kmet.loader.core`. Methods:
   `find`, `resolve`, `load`, `parent`, `unload!`. (`close` is the *host-view*
   spelling, see §6.4.)
2. **There is no hardcoded parent.** Delegation is a slot, implemented by a
   **resolve-fn**; `:parent` is sugar for one. Delegates are loaders
   themselves (`->loader` lifts a plain fn), so policies compose.
3. **The resolution body is generic**: *already-linked → delegate → locate
   (own roots/attachments) → miss*. The resolve-fn implements the **delegate
   step** only; it never links, installs or memoizes. Those are the generic
   body's job.
4. **Links are memoized, resources are not.** "Ask once, install the mapping,
   read the table thereafter" — a resolve-fn that answers differently at two
   moments is worse than none. Resources are re-walked every call (JVM
   semantics; caching them makes unload unobservable).
5. **`:denied` ≠ miss.** A blacklisted request raises; it must not fall
   through to another tier or to the ctx's own roots.
6. **Two resolution tiers**: *linkage* is directed by the **defining ctx**
   (lexical — code carries its loader, like the JVM's constant pool through
   the defining loader); *dynamic loading* (`require`/`eval` reached
   indirectly, REPL forms, framework resource probes) is directed by the
   **ambient ctx** (thread parameter, `with-loader`, TCCL analogue).
7. **Dispatch follows the value.** Protocol/`isa?`/`instance?`/record shape
   resolve through the ctx that *created* the value, regardless of which
   ctx's code is running. (JVM: virtual dispatch through the receiver's
   loader; static linkage through the referrer's.)
8. **Requests are typed**: `:ns`, `:var`, `:class`, `:resource` — because
   real loaders use different order and policy per kind.
9. **sci is the portable baseline on all three hosts**; native backends are
   upgrades layered on the same protocol, never a prerequisite. The same sci
   backend file serves bb/JVM/Jolt with a host seam for where bytes come from.
10. **`unload!` follows the Java `close` + OSGi `stop` precedent**: no new
    loads through the loader, already-resolved definitions stay live,
    acquired host resources are released, teardown errors are *reported*
    (never thrown), call is idempotent. Usage *after* unload throws. No
    unmapping, no refcounting; reclamation is "drop the references".
11. **Never call a user resolve-fn under a lock**; per-ctx, per-request
    in-flight marks (the JVM's `getClassLoadingLock` analogue) prevent
    recursive/double loads.
12. **Vocabulary is fixed**: `loader` = the abstraction; `context` = the
    host's own object underneath (sci ctx, jolt tables), reached via an
    escape hatch; `ClassLoader` = the host's real classloader, where one
    exists, reached via `as-classloader`.
13. **Hits are pure data; opening is the loader's job.** `find` locates and
    can be cached/compared/logged; nothing is read, compiled or opened until
    `load`, and a caller holding a hit can pass it to `load` to skip
    re-resolution (§4.4). `load` is the only place that reads.
14. **`unload!` never blocks** (the Java `close` contract); it records what
    it raced instead (§5). Blocking is an opt-in drain for later, if a
    consumer asks.
15. **Diagnostics**: `unloaded?` is the one behavioral predicate;
    `(status l)` is a map documented as implementation-visible (§4.5). No
    lifecycle enum — a transient `:unloading` state is only observable from
    another thread and any test for it is flaky.

---

## 1. Vocabulary

| word | means | where it appears |
|---|---|---|
| **loader** | the portable abstraction: resolve/link/find resources/unload | `Loader`, `(loader/…)` |
| **resolve-fn** | one implementation of the *delegate* step: answers what to find and where | ctx opts |
| **context** | the host object underneath (sci ctx, jolt ctx) | `(context l)` escape hatch |
| **ClassLoader** | a *real* host classloader, where the host has one | `(as-classloader l)` host view only |

`ClassLoader` never names the protocol. That keeps "ClassLoader-shaped, not
ClassLoader-compatible" enforceable rather than aspirational: you cannot
accidentally believe you have `defineClass` when the word only appears after
you have explicitly asked for a host view.

---

## 2. The resolution algorithm

```
linked?(ctx, req)                 ; the link table — what `resolve` answers from
  → delegate(ctx, req)            ; = resolve-fn (parent, pool, allow/deny, …)
  → locate(ctx, req)              ; own roots / attached artifacts (a hit)
  → miss

;; public mapping: `find` = linked? + delegate + locate, WITHOUT installing;
;; `resolve` = linked? only; `load` = the whole algorithm + read + initialize.
```

| step | who | memoized? |
|---|---|---|
| already linked in this ctx | generic body | yes (the link table) |
| delegate | **resolve-fn** (user code) | per §0.4: the *answer* is consulted once and installed |
| locate own roots | generic body via ctx's attached sources | yes, once linked; the **hit is data** (§4.4) |
| resources | delegate → own roots, in order | **no** — re-walked every call |

The JVM's own `ClassLoader.loadClass` is exactly this shape —
`findLoadedClass` → parent → `findClass` — verified: a `proxy` overriding
`findClass` is not consulted when the parent can answer, and is consulted for
a name only it can answer. Our design is that skeleton with the delegate
generalized from "a parent" to "a resolve-fn".

### What a resolve-fn answers, per kind

Answers are **hits** — data, never opened (§4.4).

- `:ns` → the source's **location** (file path / artifact entry) or
  "already linked in another loader" (by-reference delegation, §4.3)
- `:var` → the **var cell** (shared by reference — this is how
  `kmet.extension`, `kmet.tui.*`, `kmet.libs.*` are injected today)
- `:class` → a class registration/token (jolt) or a real `Class` (JVM)
- `:resource` → the resource's **location** (a URL or an embedded entry
  name — jolt has two kinds, and both open on demand); the loader wraps it
  with `open-hit`

### Policies (combinators, all trivial)

| combinator | meaning |
|---|---|
| `(->loader f)` | lift a plain resolve-fn into a delegate loader |
| `(delegating a b)` | try a then b (needs a total resolve-fn) |
| `(allow l #{'x})` / `(deny l #{'y})` | whitelist/blacklist over a delegate → `:denied` |
| `(self-first l #{'ns.prefix})` | own roots first for those names, delegate still consulted |
| `(isolated)` | `(constantly nil)` — hermetic; the root is not visible |
| `(pool [l1 l2])` | sibling sharing |
| `(url-search roots)` | the URLClassLoader analogue |

---

## 3. The two tiers

**Linkage — defining ctx (lexical).** Code compiled for ctx1 resolves
through ctx1 for its whole life, whoever calls it and on whatever thread.
This is the load-bearing rule: a function defined in ctx1 that lazily
`require`s something minutes later does it **in ctx1**, even when the host's
event loop calls it. A callback registered by ctx1 and driven by the root
still runs in ctx1. A macro defined in ctx1 expands in ctx1.

**Dynamic loading — ambient ctx.** Requests with no lexical home (`require`
reached indirectly, `eval`, REPL forms, framework resource probes) follow a
thread parameter (`with-loader`), inherited by threads and fibers. This is
the TCCL analogue and the weaker, best-effort tier.

On the JVM the same split exists: linkage goes through the defining loader
(constant pool), `RT.load`/`require` use `baseLoader`/TCCL.

**Limit to document, not fix**: dynamic loading reached through a value
(`(apply require […])`, a var holding `require`, `requiring-resolve`) cannot
carry the lexical ctx; it falls back to the ambient one. Emitted call sites
pass the ctx explicitly where the op is statically recognizable.

---

## 4. API

### 4.1 Protocol

```clojure
(ns kmet.loader.core
  (:refer-clojure :exclude [find resolve load]))

(defprotocol Loader
  (find    [l req])   ; ordered hits, [] on miss, throws on :denied — LOCATES only
                      ; (no read, no compile, no open, no install); hits are data
  (resolve [l req])   ; the linked definition for this ctx from the link table,
                      ; or nil — does NOT read source, does not install
  (load    [l req])   ; resolve (or accept a hit) + READ + initialize + link;
                      ; returns the handle; the only method that reads
  (parent  [l])       ; the delegate loader, or nil (bootstrap/root)
  (unload! [l]))      ; idempotent, non-blocking; returns a report map;
                      ; never throws for teardown errors
```

The arity split *is* the semantics: `find` = `findClass`/`getResource`
(probe, no side effects), `resolve` = `findLoadedClass` + the loaded-classes
record (memoized definition, no I/O), `load` = `loadClass` + `RT.load`'s "also
evaluate". Someone who knows `ClassLoader` guesses all three correctly.

### 4.2 Constructors

```clojure
(loader/root)                       ; the host: today's global world, as a loader
(loader/classpath roots)            ; (url-search …) — n roots, in order
(loader/isolated)                   ; hermetic
(loader/delegating a b)             ; composed delegates
(loader/allow host #{'kmet.tui})    ; policy over a delegate
(loader/self-first host)
(context l)                         ; host escape hatch (sci ctx, jolt ctx)
(unloaded? l)                       ; post-unload predicate (§5)
(status l)                          ; diagnostics map (§4.5)
(open-hit l hit)                    ; hit → opened handle (§4.4)
(as-classloader l)                  ; host view where the host has one
```

### 4.3 Request and answer shapes

```clojure
;; request
{:kind :ns|:var|:class|:resource   ; required
 :name "clojure.string"            ; required (string, not symbol — hash-stable)
 :load? false}                     ; :ns only: load exactly like require, or load one namespace alone

;; hits (find → vector, ordered; first-wins for the singular forms)
;; DATA ONLY — no open streams, no closures, no compiled artifacts (see below)
{:kind :ns     :file "/roots/a/b.clj" :loader <who answered>}
{:kind :var    :cell <var-cell>}
{:kind :class  :registration {:class "java.time.Instant" :statics … :ctors …} :loader <l>}
{:kind :resource :url "file:/roots/a/cfg.edn" :loader <l>}

;; denied
(ex-info "…denied by policy…" {:loader/denied true :kind :ns :name "…"})
```

`:loader` on a hit exists for provenance/debug and for the cross-ctx
identity rules (§6). A `:var` hit carries the **cell**, so sharing is by
reference and `identical?` holds across ctxs — that is what the current
sci `:namespaces` injection relies on and it must not regress.

Resource hits name a **location** (`:url` or an embedded entry); the loader
turns it into an opened handle on demand, so nothing holds a stream or a
file handle between calls. An earlier draft put the opener fn *in the hit*
(`{:open (fn [] …)}`); that is rejected (§12.4) — a closure in a hit is not
printable, comparable or serializable, and it defeats the AOT/read-note
bookkeeping the opener must run. Hits stay data; the loader owns opening.

### 4.4 `find` locates, `load` reads

`find` finds *where* the source is; it does not read, compile or open it.
`load` does that, and is the **only** place a read happens:

```clojure
(load l req)   ; resolves (or reuses) the hit, reads, initializes, links
(load l hit)   ; accepts a hit directly — skips re-resolution, closes the
                ; TOCTOU window between find and load
(open-hit l hit)  ; resources: hit → an opened handle (stream/opener), or nil
```

Consequences, all of them good: hits are printable and comparable; errors
surface at `load`, where a read actually failed, rather than at a probe;
the loader's own **construction** is where eager validation lives (an
unreadable jar or a missing root fails at `(loader/classpath …)`, not at
first load); and the AOT read-note bookkeeping (jolt) stays inside the
loader instead of leaking into data.

The complement: a **failed load installs nothing**. The link is installed
only on success, and a backend that creates host state while reading must
undo it when the read throws — SCI creates the namespace before it
evaluates the body, so a source that throws mid-namespace would otherwise
be re-used by the next `load` (its vars unbound) instead of being retried
(`kmet.loader.sci-loader/drop-ns!`; conf case 15).

This is also what jolt already does internally — `find-ns-file` locates,
`ldr-read-source` reads; `resolve-resource` returns a URL that opens on
demand — so the native backend maps onto it without rework.

### 4.5 `status` (diagnostics only)

```clojure
(status l)
;=> {:loader-id "ext:foo#3" :roots ["/w/foo/src"] :parent-loaded? true
;    :loaded-namespaces 12 :in-flight 1 :unloaded? false
;    :delegate <the parent's status, one level>}
```

The map is explicitly **implementation-visible**: keys may be added freely,
and no behavior may depend on a key that is not in this document. It exists
for debugging ("which loader still holds this namespace?") and for the
conformance suite. The only *behavioral* predicate is `unloaded?`.

---

## 5. `unload!` semantics

Precedent-checked against both systems:

- **Java `URLClassLoader.close`**: "no longer be used to load *new* classes or
  resources that are defined by this loader"; parents still accessible;
  "any classes or resources that are **already loaded**, are still
  accessible"; closes files it opened; "calling close on an already closed
  loader has **no effect**"; rethrows the first `IOException` with the rest
  suppressed.
- **OSGi** splits it in two: `stop` = deactivate (activator stop, framework
  auto-unregisters services/listeners, state → RESOLVED, restartable; bounds
  the wait and throws `BundleException` on timeout); `uninstall` = terminal
  UNINSTALLED while **"capabilities provided by the old in use bundle wirings
  must remain available … until the bundle is refreshed"**. A teardown
  failure during uninstall fires an ERROR event and uninstall still
  continues.

Both say the same thing, and it is the opposite of "yank the namespace out
from under existing code":

> unload = **no new loads through this loader** + release acquired host
> resources + report teardown errors. Already-resolved definitions stay live;
> reclamation is whoever-holds-references' business.

That is also the right behaviour for extensions specifically: an in-flight
agent turn, running timer, or async callback registered by the extension
keeps working after `/reload` swapped in the new revision (OSGi's
in-use-wirings guarantee).

```clojure
;; Idempotent. Never throws for teardown failures — reports them.
;; NEVER BLOCKS: it marks closed first (so new loads fail fast) and lets an
;; in-flight load finish; "finish" means it may complete into a closed
;; loader, which is well-defined below.
{:unloaded true      ; postcondition held: no new loads, resources released
 :already  false     ; true on the second call
 :released {:namespaces 12 :resources 3 :registrations 5}  ; best-effort counts
 :in-flight 1        ; loads still running when the mark was set
 :raced    true      ; true iff any load was in flight (see below)
 :errors   []}       ; teardown errors, in order (Java's suppressed, as data)
```

- **Report, don't throw.** Symmetric with `load-extension!`'s
  `{:extension … :error …}`, which `/reload` already collects and renders.
- **Never blocks** (decision 14). Java's own contract is "if another thread
  is loading a class when close is invoked, then the result of that load is
  undefined"; OSGi's bounded wait is rejected here because on jolt a wait
  would park inside `loader.ss`'s claim protocol (§7), and because kmet's
  `/reload` already refuses to run while the agent is busy
  (`interactive.clj` "Wait for the current response to finish before
  reloading"), which serializes the extension path by construction.
- **An in-flight load completing into a closed loader is defined, not
  undefined**: the load finishes and its definitions stay usable
  (already-loaded resources remain accessible, per Java), but it does not
  re-open the loader — the next `find`/`resolve`/`load` still throws.
  `:raced` exists so a caller that cares can detect it.
- **But usage after unload throws** (OSGi's rule, better than the JVM's
  `NoClassDefFoundError`/empty enumerations): `find`/`resolve`/`load` on an
  unloaded loader → `IllegalStateException`.
- Two things deliberately **not** promised: a "live references remaining"
  count (not computable on jolt's var cells; a lie on at least one host) and
  a boolean return (`nil` loses diagnostics, `false` implies unload can
  fail — it cannot; the postcondition always holds).
- **Opt-in drain, later**: `{:drain-ms N}` if a post-promotion consumer asks.
  It needs its own analysis against jolt's non-recursive loader mutex and
  in-flight claim marks before it can be offered (it must never wait while
  holding loader state).
- Ordering, matching OSGi: **shutdown → deregister → `unload!`**. kmet's
  existing `unload-extension!` already does exactly this.

Mapping:

| OSGi | Java | here |
|---|---|---|
| `BundleActivator.stop` | — | extension `shutdown` (called before unload) |
| `Bundle.stop` (deactivate/unregister) | `URLClassLoader.close` | `unload!` |
| `Bundle.uninstall` (terminal, remove storage) | — | out of scope v1 (`remove!` later if a loader owns files) |
| `Bundle.getState` | — | `(unloaded? l)` + `(status l)` |
| in-use wirings stay live | "already loaded … still accessible" | same rule |

- **The host root is not a context**: `unload!` on it throws
  `:loader/bad-request` instead of tearing down. Closing the root would leave
  the process with no world to load from — every later `(root)` operation,
  including the extension system's host view, would fail. Case 21; both
  implementations enforce it (kmet's generic teardown and Jolt's, which the
  adapter inherits).

---

## 6. Hosts and backends

| host | sci backend | native backend | what native adds | what native cannot do |
|---|---|---|---|---|
| **bb** | ✓ (what kmet does today) | ✗ | — | `proxy` whitelist rejects `java.lang.ClassLoader`; `URLClassLoader`/`DynamicClassLoader` ctors throw `MissingReflectionRegistrationError`; `Thread.setContextClassLoader` throws `ClassCastException` (verified) |
| **JVM Clojure** | ✓ | ✓ `proxy [ClassLoader]` | real `Class` objects, `defineClass`, jar handles, `Class/forName(name,false,l)` | **no namespace isolation** — `clojure.lang.Namespace`/Var tables are per-runtime (needs a second `clojure.jar`, which breaks value identity across the boundary) |
| **Jolt** | ✓ (`vendor/sci`; `make sci` 412/424, `scifunctional` green) | ✓ native ctx | compiled (not interpreted) extensions; full ns+class+resource isolation; unload | no `defineClass` bytecode path — a class is a name + registration; boot `java.*` classes stay global |

**sci is the portability baseline, not a fallback**: every host can run it, so
every host gets the same semantics for free. The generic backend is one
implementation with a host seam for *where bytes come from*
(`babashka.classes`/resource on bb, classpath on the JVM,
`jolt.host/ns-source` on jolt) and the **share list** supplied by the app
layer (the lib must not know `kmet.extension`/`kmet.tui.*`/`kmet.libs.*`).

Verified JVM facts (probed, not assumed): `proxy [java.lang.ClassLoader]`
works and is the only viable route — `defineClass` is protected/final and
**not reachable via Clojure reflection** (`No matching method defineClass`),
so a Java shim (or `DynamicClassLoader`) is required for that one method;
overridden `getResource`/`getResources` are seen by `clojure.java.io/resource`
and `Class/forName(name, false, l)`.

### 6.1 Jolt native backend

Jolt already has the *shape*: `the-classloader` is a `jhost` record with a
registered method table (`getResource`, `getResources`, `getParent` → nil,
`getResourceAsStream` — `host/chez/java/io.ss:1492`), `getSystemClassLoader`,
`clojure.lang.RT/baseLoader` (io.ss:1546) and `Thread.getContextClassLoader`
(io.ss:1624) all return it, `java.lang.ClassLoader` is already in the class
hierarchy (`class-hierarchy.ss:864`), and `resolve-resource` (io.ss:1438) is
the single resource funnel. It is one global object; the work is making it
per-ctx and wiring the compiler to it.

**Status — shipped, by a different route than the stages below.** The
implementation in the Jolt repo does not move the analyze/emit pipeline
onto a per-loader record (M0/M1), and does not give classes, providers or
type tables a per-loader home (M2). It builds contexts *out of* the one
global registry instead, and pays for it with the documented limits — see
§9 Phase 2 for what landed, what of M0–M4 remains, and which parts of this
section are therefore still design only.

Evolution stages are M0–M4 (§9 Phase 2); the subsections below are the
detailed design for the unbuilt remainder.

#### 6.1.1 Naming: "ctx" is taken inside jolt

`host-contract.ss:20` already has `chez-actx` — the *analyze* context — and
`make-analyze-ctx` (host-contract.ss:21) is constructed at
`compile-eval.ss:459,619`, `emit-image.ss:252,336,368`, `build.ss:690` and
host-contract.ss:947. Use **`chez-loader`** for the host-side loader record
and `*loader*`/`current-loader` for the ambient binding, so "ctx" keeps
meaning what it means today in that tree.

The analyze ctx **gains a `loader` field** (defaulted to the root loader):
`hc-resolve-cell` (host-contract.ss:233) and `hc-resolve-global`
(host-contract.ss:489) then consult the loader's link tables instead of the
globals — one field, defaulted, at a record already threaded everywhere.

#### 6.1.2 State inventory

| state | site | native scope | stage |
|---|---|---|---|
| `var-table`, `ns-cells-index`, `ns-has-vars-set` | rt.ss:893,939,1137 | per-ctx | M0 |
| `ns-registry` | ns.ss:22 | per-ctx | M0 |
| `ns-alias-table`, `ns-refer-table`, `ns-refer-all-table`, `-exclude-table`, `ns-core-exclude-table` | ns.ss:44–150 | per-ctx | M0 |
| `source-roots`, `ldr-ns-replacements` | loader.ss:15,101 | per-ctx | M1 |
| `rdr-features`, `*data-readers*` scan | reader.ss:984, loader.ss:126 | per-ctx (read-time) | M1 |
| `loaded-ns` + load state/claims | loader.ss:385,1463 | per-ctx | M1 |
| AOT memos, read notes | loader.ss:599–955 | per-ctx | M3 |
| `class-statics-tbl`, `class-ctors-tbl`, `host-methods-tbl`, `mutable-statics-tbl` | host-static.ss:26,60,150,309 | per-ctx (boot classes on root) | M2 |
| class extensions | class-extensions.ss | per-ctx | M2 |
| provider tables: `lib-class-providers`, `lib-pending-claims-tbl`, `lib-provider-owned-tbl`, latches | host-static.ss:337–700 | per-ctx | M2 |
| `type-registry`, `jolt-proto-epoch`, record descriptors | protocols.ss:66,143; records*.ss | per-ctx | M2 |
| `global-hierarchy`, multimethod tables | refs (clojure.core var), multimethods.ss | per-ctx + dispatch rule (§6.1.5) | M2 |
| `jch` class hierarchy, reader builtins, embedded source store | class-hierarchy.ss, reader.ss, loader.ss | **global** (boot) | — |
| `*ns*`, dyn bindings, current source/positions | dyn-binding.ss, compile-eval.ss | per-thread (unchanged) | — |

#### 6.1.3 The pipeline: where the loader plugs in

read → analyze → emit → eval, with one funnel already in place:

- **eval**: `jolt-compile-eval-form` / `-*` (compile-eval.ss:660,665) is the
  single top-level entry — bind the ambient loader here (M1);
  `jolt-compile-eval` (compile-eval.ss:714) and `load-string` follow.
- **analyze**: the analyze ctx carries the loader (§6.1.1). Resolution reads
  the loader's tables: cells, aliases, refers (hc-resolve-cell), classes
  (hc-resolve-global), and — on a miss — the loader's provider latch (M2).
- **read**: `resolve-on-roots`/`find-ns-file`/`ldr-read-source`
  (loader.ss:340–380) walk the loader's roots; `rdr-features` bind per read.
- **emit**: `host-static-call`/`host-static-ref`
  (backend_scheme.clj:2712–2715,3063) take a ctx when the form needs one
  (§6.1.4); var sites are already hoisted (`hoist-var-cell`,
  backend_scheme.clj:1010–1030).
- **load**: `load-namespace`/`require` (loader.ss) run against the loader;
  the call site passes it or falls back to ambient (§6.1.4).

#### 6.1.4 Two mechanisms: ambient loader + defining capture

**Ambient** (thread parameter, like `chez-current-ns`): bound around the
analyze+eval of each top-level form and around each file load (M1). This
covers *everything that happens during the defining evaluation* — nested
requires, data readers, `deftype`/`defrecord` registration, `defmethod`,
`register-class-statics!`, provider autoload, `intern`. Those host fns read
`(current-loader)` at load time and land in the defining loader without any
API change: the def's init runs inside the ctx by construction.

**Defining capture** covers code that runs *later*. It rides the existing
per-def cell scope: `emit-with-cells` (backend_scheme.clj:694–712) already
wraps a def's init in `let*` for the const pool and hoisted cells — add one
more binding, read once at def time:

```scheme
(define jv$foo
  (let* ((_ctx$N (current-loader))   ; NEW — same let*, same pool
         (cell$1 (jolt-var "ns" "dep")))
    (lambda (x) (host-static-call _ctx$N "Foo" "bar" cell$1 x))))
```

Correctness: the def-init evaluates *inside* the defining loader, so
`(current-loader)` at that moment is the right one; nested lambdas close over
it through ordinary lexical scope, and re-evaluating the def in another
loader rebinds it. A closure created later (a fn returned from a fn) still
closes over the outer def's `_ctx$N`.

**Screening** (the cost gate): walk the IR and bind `_ctx$N` only when the
form contains `:host-static`/`:host-new`/`:host-static-ref`, a **non-hoisted**
`:var` site (the `var-cache?`-off paths — seed mint, some built images), or a
direct call to a dynamic-load var (`require`, `load`, `load-file`,
`requiring-resolve`, `eval`). Plain arithmetic/seq closures emit byte-identically
to today. Expect this to be the same shape as the existing `repeat-ops`
gate (backend_scheme.clj:740) — a predicate, not a rewrite.

**Where capture is impossible** (documented degradation, same as Clojure's
`baseLoader`): `(apply require …)`, a var holding `require`, a
`requiring-resolve` reached indirectly. Those use the ambient loader.

**Seed-fixpoint constraint**: the seed mint runs with `set-var-cache!` off
(backend_scheme.clj:232) and must stay byte-deterministic — the `_ctx$N`
binding follows `*const-pool*` (backend_scheme.clj:559), so it must be emitted
only under the same conditions the mint already tolerates. Gate: `selfhost`,
`make remint`.

#### 6.1.5 Classes, providers, types

- **Boot globals** (shared by delegation, as on the JVM): the core providers
  `jolt.time.base` / `jolt.socket` (host-static.ss:337), the `jch` hierarchy,
  every class the runtime implements (`String`, `Base64`, …).
- **Per-loader**: `class-statics-tbl`/`class-ctors-tbl`/`host-methods-tbl`,
  mutable statics, class extensions, `type-registry` + protocol epoch +
  record descriptors, and the RFC 0014 tables — `lib-class-providers`,
  `lib-pending-claims-tbl`, `lib-provider-owned-tbl`, the one-shot latches.
  `register-class-provider!` (and `main.clj:101`'s
  `register-class-providers!`) targets the loader being created; boot
  providers register on the root.
- **Provider autoload** (`lib-try-autoload!`, host-static.ss §600–700) consults
  the loader's pending table, and the install namespace loads *in that
  loader*; `provider-claim-drop!`/`hold!` keep their meaning per loader.
- **Cross-loader identity**: two loaders' "same" class/type give distinct
  tokens; `instance?`/`=` across them is false on purpose (§6.3).
- **Dispatch is value-directed** (§0.7): `isa?`/`instance?` derive the loader
  from their (tagged) arguments, not from the caller. Each loader has its own
  hierarchy; a `derive` in loader A is invisible to loader B. Multimethod
  tables need nothing extra — a `multifn` lives in a var cell, and cells are
  per-loader — except for multimethods defined in *shared* (root) namespaces,
  which stay shared as expected. Until M2 lands, derives leak through the
  global `global-hierarchy` cell: record it as a divergence and have
  `run-case-isolation.ss`'s reset as the stopgap.

#### 6.1.6 Load protocol, concurrency, fibers

- The loader's claim protocol (loader.ss:1463–1936: `ldr-load-mu`, marks,
park/wake, cycle detection, `ldr-mark-loaded!`/`ldr-unmark-loaded!` at
1211/1216, rollback at 1888) is keyed **(loader, namespace)** — one mark per
loader, so two loaders may load the same namespace concurrently and each
rolls back independently.
- Lock discipline is preserved, not relaxed: the "a LOAD MUST NEVER ACQUIRE
`stm-lock`" rule and the `ldr-libs-mu` ordering (loader.ss:440–470) stay;
per-loader mutexes remove cross-loader serialization but every existing edge
remains. `make lock-check` / `park-lock-check` are the gates.
- **Fibers**: a load can park (fiber backend), and `dyn-binding.ss` documents
why thread parameters alone are not enough across a fiber resume (the winder
pushes a second frame). The ambient loader must survive a fiber parking and
resuming on a different carrier — this is the one jolt-internal mechanism
that needs an explicit test (case 9 executed from a `go` block). Reuse
whatever `chez-current-ns` does; if it is insufficient, the loader binding
needs the same push/pop treatment the dynamic bindings have.

#### 6.1.7 Resources and the ClassLoader facade

- `resolve-resource` (io.ss:1438) resolves within the ambient loader's roots;
  `io-note-file-read!` records into that loader's AOT notes.
- `the-classloader` becomes `(loader->classloader l)` — a `jhost` carrying the
  loader in its state (like the thread handle carries its id, io.ss:1596), so
  `getResource`/`getResources`/`getResourceAsStream` resolve in *that* loader
  and `getParent` returns the delegate's facade (nil at root).
- Statics: `getSystemClassLoader` → root's facade; `RT/baseLoader` → ambient
  loader's facade; `Thread.getContextClassLoader` (io.ss:1624) → ambient;
  `Class.getClassLoader` → the loader that registered the class (carried on
  the class registration).
- `io/resource`'s 2-arity (io.ss:1466: "jolt has a single \"classloader\" …
  so the argument is accepted and ignored") becomes meaningful: an argument
  that is one of our loader jhosts resolves in its loader; any other value (a
  library passing some real loader object) keeps today's ambient behavior.

#### 6.1.8 deps and entry points

`jolt.deps/resolve-deps` (deps.clj:1016) is already the per-loader root
resolver. `apply-project!` (main.clj:120) becomes "build the project loader
from the resolved map":

```clojure
{:roots … :provides … :replaces … :features … :natives …}
  → (make-loader …)   ; roots → source search; provides → claims; …
```

The root loader is the install roots (loader.ss:38 `ldr-install-roots`).
jolt.host seams to extend or re-target: `set-source-roots!`/`source-roots`,
`ns-source`, `load-namespace`, `replace-builtin-ns!`,
`add-reader-features!`, `register-class-provider!` (loader argument),
`ctx-for-ns` (host-contract.ss:947). New host vars need
`jolt-host-manifest.txt` lines and `make manifestcheck`:
`current-loader`, `with-loader` (if hosted), `loader-roots`,
`loader-classloader`, `unload-loader!`.

#### 6.1.9 Gotchas (all verified in-tree)

- **Gates that will fail if forgotten**: `make manifestcheck` (any new
  `jolt.host` var); `make gambitgen`/`gambitgencheck` (`host/gambit/rt-core.ss`
  mirrors rt.ss, `records-gambit.ss` mirrors records.ss via
  `gen-records.ss` — M0/M2 touch both mirrors); `make mirrordrift`
  (`host/chez/mirror-drift-check.sh`); `make portcheck`, `deadhost`,
  `lockcheck`, `parkcheck` on the M0–M2 host edits (`host/chez/portability-check.sh`,
  `dead-host-check.sh`, `lock-check.sh`, `park-lock-check.sh`); `make remint` only if a
  seed-listed file changes (only `reader.ss` among these is on the seed list —
  but an accidental `jolt-core/**` or `clojure.core` edit is not).
- **AOT cache (M3)**: `aot-cacheable-file` (loader.ss:947) resolves through
  the *global* `find-ns-file` — must resolve within the loader;
  `aot-own-key-memo` is keyed by namespace name — key it by
  (loader, namespace) or by resolved file; `:jolt/features` /
  `:jolt/replaces` fold into the key (they change what was *compiled*).
- **Embedded sources**: `embedded-resource-has?`/`resolve-on-roots`
  (loader.ss:340–360) key by root-relative path — two roots holding
  `foo/bar.clj` collide. Root-qualify the keys, or declare multi-root
  loaders source-mode-only in v1.
- **Tree shaking**: `dce.ss` walks `get-source-roots` — build-time, today
  single-loader; a multi-loader build needs the closure walked per loader.
  Note it, don't build it in v1.
- **State images**: closures are name references
  (`register-code-value!`/state-image.ss:70–74). An image restored in a
  different loader would resolve names in the wrong one — record the home
  loader, or refuse a cross-loader restore.
- **Direct linking**: a `direct-link?` build emits bare Scheme bindings for
  app fns (`dl-name`, backend_scheme.clj:3284+), which are loader-independent
  by construction. Multi-loader evaluation must therefore run with
  direct-linking off (runtime eval already is; a *built* multi-loader app is
  not expressible in v1 — record it).
- **Natives stay global**: `dlopen` is process-wide and jolt dedupes
  `:jolt/native` by name (deps.clj:1683) — two loaders' lib versions share one
  `.so`. Declare it; anything with per-version C state is out of scope.

#### 6.1.10 Stages, files, gates

| stage | content | files | gates |
|---|---|---|---|
| **M0** indirection | `chez-loader` record owning the M0 rows of §6.1.2; accessors read `(current-loader)`; root loader wraps today's globals; analyze ctx gains the field (defaulted) | rt.ss, ns.ss, host-contract.ss, compile-eval.ss, emit-image.ss, build.ss, loader.ss | `corpus` `unit` `cts` `sbperf` + `manifestcheck` + `gambitgencheck` + `mirrordrift`; `remint` only if a seed-listed file was touched |
| **M1** loaders + propagation | ambient binding at the eval funnel; per-loader roots/loaded-ns/data-readers; defining capture in `emit-with-cells` + screening; `find`/`resolve`/`load` split mapped onto `resolve-on-roots`/link table/`load-namespace` | loader.ss, compile-eval.ss, backend_scheme.clj, reader.ss | above + suite cases 1,2,3,5,6,9,10 (9 from a `go` block too) |
| **M2** classes | per-loader class/provider/type tables; value-directed dispatch; ctx-tagged tokens; divergence entry | host-static.ss, protocols.ss, records*.ss, multimethods.ss, class-hierarchy.ss (read-only) | above + cases 4,10 + provider autoload inside a loader |
| **M3** unload + caches | teardown against the `run-case-isolation.ss` list; AOT/embedded/class-path fixes from §6.1.9 | loader.ss, io.ss, build.ss, dce.ss | case 7, 11, 12 + world byte-compare + `aot-cache-smoke` |
| **M4** host API | per-loader classloader jhost, `RT/baseLoader`, TCCL, `io/resource` 2-arity, `jolt.host` seams (§6.1.8) + `as-classloader` | io.ss, loader.ss, main.clj, jolt.deps.clj | full suite on jolt + `sci`/`scifunctional` stay green |

Each stage is independently mergeable and each must keep the sci backend's
suite green: the native backend is an upgrade layered on the same protocol,
never a second semantics.

### 6.2 Per-ctx deps resolution

**Not the loader's job.** A loader receives its roots/sources; resolving a
`deps.edn` to roots is a host seam: `borkdude.deps` on bb/JVM (today's
`closure-jars`/`jars-for`), `jolt.deps/resolve-deps` on jolt (which already
returns `{:roots … :natives … :provides … :libs …}` per graph). The loader
backend takes an injected resolve-deps fn. Version-qualified dependency
roots (the jars themselves, read in place) already make v1/v2 coexist on
disk in both systems.

### 6.3 Cross-ctx identity rules (write down, don't discover later)

- Two ctxs loading "the same" class/type ⇒ distinct tokens; `instance?`/`=`
  across them is **false** on purpose (JVM: `ClassCastException`).
- Var cells from a *shared* loader are the same object (by-reference
  injection relies on it).
- Dispatch follows the value (§0.7).

### 6.4 `as-classloader`

Where the host has a real `ClassLoader`, expose one: `loadClass` → `find` +
`load`; `getResource(s)`/`getResourceAsStream` → `find` then `open-hit`;
`getParent` → `(parent l)`'s facade; `close` → `unload!`. On the JVM this is
a `proxy [ClassLoader]`. On jolt, the per-ctx `jhost` loader with the method
table above, which also makes `(io/resource n loader)`'s currently-ignored
2-arity argument start meaning something, and keeps
`(take-while identity (iterate #(.getParent %) l))` terminating. The same
object is what the thread's context classloader answers inside `with-loader`
(case 22).

### 6.5 Which sci artifact (jolt)

bb has `sci.core` built in — nothing to add — and **jolt's stdlib bundles
it too** (`make sci` / `scifunctional` pin the vendored copy, and
`kmet.loader.sci-loader` runs on jolt via `jolt test kmet.loader.test-sci-loader`). So
the *library* declares **no sci dependency at all**: no maven jar, no git
pin, no vendoring, no reader conditional. (kmet's own `jolt/deps.edn` — the
jolt-only RFC 0014 slot, not the lib's deps — does pin `org.babashka/sci`
0.13.53, because the jolt-gated version is what runs the SCI backend's suite
under `jolt test`; extension contexts on jolt no longer go through sci at
all.) The one unproven assumption of the
phase-1 plan turned out to be a non-issue. A consumer that requires only
`kmet.loader.core` never loads sci anyway — the require lives in the
backend's own namespace.

---

## 7. Concurrency and lifecycle rules

- **Never call a resolve-fn under a lock** — it may `require` into the same
  ctx (cycle). Per-ctx, per-request in-flight marks; a nested load on the
  same thread must be detected, not deadlocked. Reuse the loader's existing
  claim protocol on jolt; `getClassLoadingLock` semantics elsewhere.
- **A ctx is inherited** by threads/fibers spawned inside it (ambient tier).
  Linkage needs nothing — it is lexical.
- **Unload does not unmap** (§5). Drop references; the host reclaims.
- **State images / serialized closures**: a closure that travels through a
  state image is a name reference, not a value. In a multi-ctx world an
  image must record its home ctx, or cross-ctx restore must be refused.
  (Jolt-only concern; cheap to enforce early.)

---

## 8. Conformance suite (this list *is* the spec)

Host-agnostic, written against the protocol; must pass on every backend
that serves the kind in question. The **executable spec** is
`test/chez/loaderconf-test.clj` in the Jolt repo — 27 cases, `make
loaderconf`, empty baseline — mirrored on the kmet side by
`test/kmet/loader/test_core.clj` (data-path cases, any backend) and
`test/kmet/loader/test_sci_loader.clj` (code-path cases, SCI). Cases marked
*(host)* need a host-global registry and live only in the Jolt suite.

1. **v1/v2 isolation** — two ctxs, one mvn lib at v1 and v2: `ctx1/foo` ≠
   `ctx2/foo`, both correct.
2. **Hermetic** — `(isolated)`: a name the root has must **not** resolve.
3. **Shared by reference** — delegate a var: the **same cell** comes back
   (`identical?`), not a copy.
4. **Deny ≠ miss** — denying a name raises, and does not fall through to the
   ctx's own roots.
5. **Self-first** — a ctx shadows a lib its delegate also has, for the
   declared prefix only.
6. **Composed delegates** — host + pool; `(parent …)` walks the graph.
7. **Unload** — post-condition holds (no new loads), already-resolved
   definitions still work, second `unload!` is a no-op, usage after unload
   throws, teardown errors are reported not thrown.
8. **Resources** — `find`/`open-hit`/`getResource(s)`/`getResourceAsStream`
   against a ctx root; `RT/baseLoader` (or the host equivalent) inside the
   ctx resolves to the ctx's loader; the 2-arity `io/resource` honors the
   loader.
9. **Defining-ctx inheritance** — ctx1 defines `f` that lazily requires
   `lib1`; ctx2 (with a different `lib1`) *calls* `f`; assert `f` saw
   **ctx1's** `lib1`.
10. **Dispatch follows the value** — a value created in ctx2, passed to ctx1
    code, still dispatches through ctx2's type tables.
11. **Hits are data** — `find` opens nothing (a hit survives `pr-str` and `=`;
    probing a loader leaves no file handles), and `(load l hit)` on a hit
    from `find` produces the same result as `(load l req)`.
12. **Eager construction, lazy load** — a loader built on an unreadable
    root/jar fails at the constructor; a *load* that reads a file that
    disappeared after `find` fails at `load`, not silently.
13. **Concurrent context loads** — several contexts load one namespace
    name at once, each with its own source: every context ends with its own
    definition (no cross-talk through shared bookkeeping).
14. **Unload releases what it installed** — `unload!` counts its links out,
    the loader refuses new loads, definitions already resolved stay
    callable, a *sibling* loader holding the same name is untouched, and a
    fresh loader loads the name again. *(host)* a registration the loader
    no longer owns survives the unload.
15. **A failed load installs nothing** — a source that throws, or a nested
    require that cannot be served, leaves no link, releases the in-flight
    claim, and leaves no half-built namespace behind: the retry reads the
    fixed source instead of re-using the broken one.
16. **A context's own names are private** — a name the context loaded is
    invisible through the root (namespace and var), while its owner still
    resolves it. *(host)* the process-global registry is left clean too.
17. *(host)* **Host namespaces load on demand** — a namespace the host can
    load but has not is located by the root without reading, loaded through
    the host's own loader (AOT cache included), and linked in the context.
18. **Requirements resolve through the loader** — evaluated source that
    requires a namespace the loader cannot serve fails the load with an
    actionable `:loader/unreadable` and links nothing; the same source
    loads once the requirement can be served.
19. *(native)* **Dashed namespace names** — a namespace maps to its file the
    way Clojure does (dots to slashes, dashes to underscores per segment), so
    `lib-one.core` loads from `lib_one/core.clj`.
20. *(native)* **Resources follow the ambient loader** — inside `with-loader`,
    the 1-arity `(io/resource "x")` resolves against the bound loader's roots;
    outside one it keeps the host answer.
21. **The host root is not unloadable** — `unload!` on it is a bad request
    (`:loader/bad-request`), not a teardown: closing the host's own world
    would leave the process with nothing to load from. Every other loader —
    contexts, views, combinators — unloads normally.
22. *(native)* **TCCL follows the ambient loader** — inside `with-loader`, the
    thread's context classloader is the context's own classloader and resolves
    its roots; outside one it is the host's. Only a classloader-shaped ambient
    answer is taken (a jhost or a tagged table): anything a library rebound
    `RT/baseLoader` to keeps the historical host answer. There is no
    `setContextClassLoader` — the getter is ambient-derived, not per-thread
    state.
23. *(native)* **`require`'s options, and `:reload`** — a runtime `(require
    '[lib :as h])` materializes the alias in the DEFINING namespace (and
    `:refer`/`:rename` with it); `:reload` re-reads through the loader into the
    installed namespace, so definitions other code already links to see the new
    roots; a requirement the loader cannot serve fails `:loader/unreadable`.
24. *(native)* **`use` and `refer`** — both load through the loader and act in
    the defining namespace, filters (`:only`/`:exclude`/`:rename`) included.
25. *(native)* **`load`/`load-file` are refused** — a host-file path would step
    outside the context's roots; the error says so and names the alternatives.
26. *(native)* **Per-context data readers fail loudly** — a `#tag` the host's
    `*data-readers*` does not know fails the load naming the tag; when the
    context's roots ship a `data_readers.clj` the message says so, and when they
    do not it blames no file (per-context readers are not supported: the
    runtime's reader resolves tags before the loader sees the form).
27. *(native)* **`:reload` reaches a delegate** — a namespace served through a
    delegate re-reads there, in place, because the reload intent is keyed by
    name; and the delegate's own link table holds the namespace's var links, not
    just the namespace.

Cases 1–8, 10, 11, 13–16, 18 and 21 are expressible against SCI on bb and jolt
today, which is the point: pin the semantics before any runtime work, the
way the corpus does for `clojure.core`. Case 9 needs a code backend and
lives in the SCI suite; case 12 has a data-path version in the core suite
and a read-path one there too. Cases 22–26 *(native)* are the Jolt host seams
and reader rules (TCCL, the rewritten load/alias ops, the host-file refusal,
the data-reader failure); kmet's `test/kmet/loader/test_jolt_loader.clj`
mirrors 22 through the adapter. Cases 17, 19 and 20 *(native)* are rules the
native reader and the host registry state — kmet's root answers loaded host
namespaces only (`root`'s docstring), so a code backend injects shared names
and fails an unservable require (case 18) instead; the SCI backends never map
namespaces to paths (their source provider is asked by symbol) and shadow
`io/resource` with an artifact-scoped fn rather than consulting an ambient
loader. Cases 14 and 16 have *host* strengthenings in the Jolt suite that
the portable cases cannot express (a replaced registration surviving
`unload!`; a context-owned name evicted from the process-global registry),
and `test/kmet/loader/test_jolt_loader.clj` mirrors the adapter's own contract
(host view, var links, unload, ambient resources) on the kmet side.

---

## 9. Implementation plan

Order follows the user-visible payoff, not the elegance: the protocol + sci
backend deliver extension isolation on all hosts first; native backends are
upgrades that must satisfy the same suite.

### Phase 0 — protocol, policies, suite (no kmet integration yet)

- `src/kmet/loader/core.clj` — protocol, request/answer shapes, combinators
  (`->loader`, `delegating`, `allow`, `deny`, `self-first`, `isolated`,
  `pool`, `url-search`), `with-loader`/`current-loader`, link table,
  in-flight marks, generic `find`/`resolve`/`load` body, `open-hit`,
  `unloaded?`/`status`, `unload!` report.
- `src/kmet/loader/memory_loader.clj` (ns `kmet.loader.memory-loader`) — an
  in-memory loader over a source map, used by the suite and as the demo
  loader (no sci yet).
- `test/kmet/loader/test_core.clj` (ns `kmet.loader.test-core`) — cases 1–8,
  10 (case 9 needs a code backend). Register in `kmet.tasks.runner/all-namespaces`.
- Gate: `bb test-changed`, `bb lint-changed`, `bb format-check-changed`.
- Constraint: the tree must stay extractable —
  `kmet.loader.test-self-contained` (no `kmet.*` requires beyond
  `kmet.loader.*`; `kmet.libs.*` may not reach into it either) stays green.

### Phase 1 — sci backend + kmet extension wiring (all hosts, sci)

- `src/kmet/loader/sci_loader.clj` (ns `kmet.loader.sci-loader`) — a Loader whose
  generic body delegates compile/eval to sci (`:load-fn`, `:namespaces`,
  `:classes`), with the **share list injected** by the caller. No sci
  dependency is declared: babashka and jolt bundle `sci.core` (§6.5), and
  a plain JVM needs `org.borkdude/sci` on its classpath. A caller that mints
  many short-lived loaders over the same share list can pass **`:base`**: the
  loader forks that context (`sci/fork` + `sci/merge-opts`) instead of
  `sci/init`, sharing the injected namespaces and seeded classes, with only
  `:load-fn` / `:namespaces` overrides and definitions per fork. The
  extension runtime uses this — one base per shared-namespace set, forked
  per extension (`kmet.app.extensions/shared-context`).
- `src/kmet/app/extensions.cljc` — replace `create-context` +
  `make-load-fn` + `jars-for` plumbing with `loader/sci-loader` + policies
  (`allow` over the root for the shared layers; per-extension deps resolver
  injected). `load-extension!` / `unload-extension!` keep their shape
  (`{:extension … :error …}`; shutdown → deregister → `unload!`).
  The four `"Extensions not supported on Jolt"` guards collapse into backend
  selection — **done**: the guards are gone, the jolt branch resolves dep
  roots through `jolt.deps/resolve-deps`, and the Jolt host no longer needs
  SCI for extension isolation at all (see Phase 2 — the native backend is
  what runs there now; `kmet.loader.sci-loader` remains the bb/JVM backend, which is
  the only host left that needs an interpreter).
- `src/kmet/extension.clj` — **no re-exports** (decided in Phase 1):
  extensions never see the loader at all. `kmet.app.extensions` uses
  `kmet.loader.*` directly, and a require of it from extension code fails
  with an actionable "host machinery" error; the extension-facing surface is
  unchanged, so promotion still breaks nothing.
- Tests: existing extension/jar-ext tests stay green; add case 1 (v1/v2)
  as a real extension fixture; case 9 on sci.
- Gate: `bb test-changed` (plus `bb test` for the extensions suites if
  touched broadly), `bb format-check-changed`.

### Phase 2 — Jolt native backend — **implemented** (Jolt repo)

**Design and per-stage detail: §6.1** (state inventory §6.1.2, the two
ctx-propagation mechanisms §6.1.4, gotchas §6.1.9, stage table §6.1.10).
Shipped in the Jolt repo rather than here: `stdlib/jolt/loader.clj` plus
the host seams (`clojure.java.io/resource` 2-arity, `RT/baseLoader`, the
tagged-table classloader facade), with `test/chez/loaderconf-test.clj` as
the writ — `make loaderconf`, 27 cases, empty baseline. Tracked in
jolt-lang/jolt#912 and jolt-lang/jolt#1039.

**What landed, and how it differs from M0–M4.** The substrate is one
global namespace registry (rt.ss's var-table), so a context is built *out
of* it rather than beside it: a namespace located on a loader's own roots
is private — any installed version is evicted before the source is
evaluated, so the evaluation makes fresh cells, and compiled references
are direct cell links, so evicted cells stay live; the root hides owned
names (and vars in them); a hit located by a delegate is linked at its
home loader too; requires are pre-loaded through the loader, so a
requirement the loader cannot serve fails the load instead of leaking to
the runtime's global `require`; `require`/`resolve`/`ns-resolve`/
`find-var` in evaluated source are rewritten to context-carrying forms,
and a quoted `resolve`/`find-var` target is qualified with the defining
namespace at rewrite time (at call time `*ns*` is the caller's). The
evict-evaluate-snapshot window mutates global state under one name, so it
is serialized by a per-name claim shared by every loader (waiters park on
a promise, so fibers stay parkable; same-thread re-entry is
`:loader/circular`), and `unload!` unmaps what it installed while the slot
is still its own.

So M1's analyzer-visible loader state (per-loader roots/loaded-ns/data
readers, the analyze-ctx field, defining capture in `emit-with-cells`) was
not built. Nor was M2: classes, provider tables and type tables stay
process-global, and `:class` requests have no backend on jolt (the SCI
backend answers them from its injected class map). Nor M3: private sources
compile fresh (only the host-root path uses the AOT cache), embedded
sources key by root-relative path, `dce` and state images remain
single-loader, and the limits are recorded in the namespace docstring — a
shadowing context evicts the host's registration (the runtime's loaded
mark survives `remove-ns`, so a plain `require` does not restore it and
`:reload` does), a host-side in-place reload of a context-owned name
reuses that context's object and cells, and the per-name claim orders
writers, not readers. Two more are deliberate and recorded here because a
review will ask: `loaders-by-id` (plus each loader's facade) keeps every
loader ever constructed reachable — evaluated source finds its owning loader
by id, including after close, so `:loader/unloaded` stays answerable — which
is fine at one loader per extension and would want a closed-marker if a host
ever minted one per request; and `jar-namespaces` is scanned a second time
for a jar artifact when the SCI backend builds its resource fn (O(zip
entries), once per load) — the price of not passing an unused argument
through the Jolt branch. M4 is complete now: `io/resource` 2-arity,
`RT/baseLoader`, the facade and `as-classloader` as before, and TCCL — inside
`with-loader` the thread's context classloader IS that context's classloader
(case 22), so a dependency that finds its own resources the Java way lands in
the context's roots.

**Hardened since (cases 22–26).** The rewrite grew from four ops to nine, with
the semantics Clojure gives them: `require`/`use`/`refer` load through the
loader and apply `:as`/`:as-alias`/`:refer`/`:only`/`:exclude`/`:rename` in
the DEFINING namespace (a runtime require's alias used to vanish, `Unknown
class h`), `:reload` re-reads through the loader into the *installed*
namespace so definitions other code already links to pick up the new roots.
The intent is keyed by name, so it reaches wherever the namespace is served
from — the context's own roots or a delegate's, which reloads in place too; the
host root is the exception (it loads through the host's own loaded-mark, so a
host namespace's reload re-links and re-applies its effects without a re-read).
A reload that THROWS keeps
the installed namespace — the "a failed load leaves nothing behind" rule is
for fresh loads; for a reload, that namespace is what already-linked code is
holding — a requirement the loader cannot serve fails `:loader/unreadable` instead of
leaking to the runtime's global require, and `load`/`load-file` are refused as
host-file operations. A hit served through a delegate is linked at its home
with its var links as well as its namespace link (a delegate's table used to
answer `resolve` for none of its vars). The test harness's per-row reset now also drops the
loader's own bookkeeping (`reset-context-state!`, called from
`run-case-isolation.ss`). Per-context `data_readers.clj` stays unsupported —
the runtime's reader resolves `#tag` against the host's `*data-readers*`
before the loader ever sees the form — but no longer as a cryptic compiler
error: the load names the tag and the reason (case 26).

**Remaining, if class-level isolation is ever wanted**: M0/M1 (the
analyzer-visible loader state a full M2 would need), M2 (the per-loader
class/provider/type tables, value-directed dispatch, cross-ctx identity —
§6.1.5, §6.3; this is also why `:class` requests have no Jolt backend), and
M3's cache work (AOT keyed by (loader, ns) or resolved file, `dce` per
loader, state-image home loader — the embedded-source root qualification
already landed, and the harness prune list is now wired). Those
untouched host edits are also why §6.1.9's extra gates (`gambitgencheck`,
`mirrordrift`, `portcheck`/`deadhost`/`lockcheck`/`parkcheck`, a possible
`remint`) are not in play yet.

Re-checked against the tree, so the list above is a state, not a memory:
nothing in `rt.ss`, `compile-eval.ss`, `analyze.ss`, `ns.ss` or `emit.ss`
reads a loader (no analyzer-visible loader state); the class/provider/type
tables are the process-global ones (`host-static-classes.ss`'s
`tagged-methods-tbl`, `protocols.ss`'s type registry — no per-loader tables
anywhere in the host); the only `jolt.host/load-namespace` call is the
host-root path (private sources read source, so nothing is AOT-keyed);
`run-case-isolation.ss` rolls back the host's `loaded-ns` dedup
(`ldr-unmark-loaded!`) and knows nothing of the loader's own
`loaders-by-id`/`private-ns-owners`/claims/facades (it now calls the
loader's `reset-context-state!`, which is what retires them between rows);
and `Thread/getContextClassLoader` answers with the ambient loader's facade
(io.ss, case 22).

Gates for the M-stages, when they land: the corpus/unit/cts/sbperf set
(plus the jolt gates in §6.1.9) and the conformance cases named in
§6.1.10; `make sci` / `scifunctional` must stay green throughout — they
pin the sci path the extension backend uses.

**kmet's side of the native backend.** `src/kmet/loader/jolt_loader.jolt` (a
`.jolt` source, so Jolt-only by extension and free of reader conditionals)
forwards
kmet's protocol to the Jolt loader — protocols do not unify, so it adapts
rather than aliases — and adds what the extension contract needs and the raw
surface does not express: `host-view` (a *miss-not-denial* filter of the host
root to the shared namespace names, with `kmet.loader.*` rejected as host
machinery) and the ambient binding. `kmet.app.extensions/create-loader` picks
it on Jolt: the extension's own sources are read by the native reader from its
artifact root — a single-file extension is materialized at its munged ns path
first, and every own source is validated up front, because the native reader
never calls back into kmet — dep roots are the `jolt.deps` resolution's
sources (jars, read in place, plus `:local/root` directories),
and the shared contract arrives as the filtered host root instead of copied
vars. Every callback an extension registers, and init/shutdown themselves, run
wrapped in `with-loader*` — every fn inside a registration *map* too
(`:get-argument-completions`, `:render-call`/`:render-result`, `:title`, …),
not just its handler, since the app calls each of them long after the load:
that is what the ambient tier is for, and it is how
`(io/resource "x")` inside an extension resolves against its own roots at call
time (conformance case 20 — the 1-arity follows the ambient loader, which is
also why the runtime's own host views resolve through the host resolver
explicitly rather than through the ambient one). Renderer factories are the
one exception: they are stored and compared by identity.

### Phase 3 — JVM native backend + hybrid (last)

- `kmet.loader.jvm` (Clojure-only): `proxy [java.lang.ClassLoader]`
  overriding `findClass`/`getResource(s)`/`findResources`; `parent` from the
  proxy's own parent; `close`/`unload!` mapped both ways;
  `as-classloader` returns the proxy itself.
- **Java shim** for `defineClass` (or `DynamicClassLoader`) if class
  definition is needed — reflection cannot reach it (verified).
- `loader/hybrid` — native classloader for `:class`/`:resource` + attached
  sci ctx for `:ns`/`:var`; documented as the only way to get both kinds of
  isolation on the JVM.
- Suite runs on the JVM for cases 1–8, 10; case 3 (shared var) via the sci
  half.
- **Deliberately last**: the JVM host already gets isolation through SCI
  (phase 1), so this is the upgrade for class-level isolation, not a
  blocker. It may land after promotion (phase 4), as a separate artifact.

### Phase 4 — promotion to a standalone library

- Move `src/kmet/loader/` (+ its doc and suite) out unchanged; add its own
  `deps.edn`. The tree already lives outside `kmet.libs.*` with no `kmet.*`
  requires (guarded by `kmet.loader.test-self-contained`) and extensions never
  see the loader (option B), so promotion is a move — no API break.
- jolt's `stdlib/jolt/loader.clj` (native backend) moves/stays jolt-side;
  the JVM backend stays a separate artifact.

---

## 10. Rollout order and validation

1. Phase 0 green on bb (fast, no kmet behavior change). **Done.**
2. Phase 1 on bb: `/reload`, extension fixtures, jar-ext suites unchanged.
   **Done.**
3. Phase 1 on Jolt: `jolt -e "(require 'kmet.loader.core)"`, the suite, then
   kmet's extension tests. **Done** — `jolt test
   kmet.loader.test-core kmet.loader.test-sci-loader` and `kmet.app.test-extensions`
   are green on jolt.
4. Phase 2 (Jolt native) — **done** in the Jolt repo, 27/27. Phase 3 (JVM,
   plus hybrid) is last and may follow promotion. Each backend must pass
   the *same* suite; a native backend that fails a case is a bug in the
   backend, not a permitted divergence (§6.3 excepted, recorded in the
   registry).
5. Changed-file gates (`bb test-changed` / `lint-changed` /
   `format-changed`) during development; full gates only on request
   (AGENTS.md).

## 11. Non-goals (v1)

- Bytecode definition on non-JVM hosts (`defineClass` is JVM-only; on jolt
  "define" = register a class token).
- Real `Class` objects / `ClassCastException` / verifier semantics outside
  the JVM.
- Namespace isolation on the JVM without sci or a second `clojure.jar`.
- Sandboxing: contexts are not a security boundary — anything shared by
  reference is fully mutable by the child (same caveat as today's sci
  injection).
- Per-context reader features / replaces policy beyond what a ctx's loader
  already carries (record the policy change when M1 lands).

## 12. Resolved questions (alternatives considered, kept for the record)

### 12.1 Where the sci backend lives / how the dep arrives — **A**

*Decision*: a direct `sci.core` require in `kmet.loader.sci-loader` (its own
namespace file); jolt gets sci via a git pin (§6.5). Protocol-only consumers
pay nothing at runtime — requiring `kmet.loader.core` does not load
`loader.sci` — and the only cost is a classpath entry.

Note the premise: the loader is *not* "no third-party deps". Its guard
(`kmet.loader.test-self-contained`) forbids `kmet.*` requires beyond
`kmet.loader.*` and says nothing about third-party deps; `deps.edn` already
carries `babashka.http-client`, `deps.clj`, `data.json` and two jolt-lang
git deps, and the sci backend requires `sci.core`. "Dep-light" is a
promotion-quality argument, not an enforced rule.

| alt | pros | cons | verdict |
|---|---|---|---|
| **A: direct dep, own ns** | simplest; one home; matches `libs.http`; namespace laziness isolates the cost | jolt needs a sci artifact (git pin) | **chosen** |
| B: injected backend SPI | lib truly dep-free; impls evolve separately | a second contract designed and documented before anyone needs it | rejected — premature abstraction |
| C: impl in the app layer | zero new libs deps | promotion becomes a move, not a rename; loader code in two layers now | rejected |
| D: dynamic `requiring-resolve` | no classpath change | missing dep surfaces at first use, not load; contradicts fail-early | rejected — anti-pattern |

### 12.2 `unload!` blocking — **never block** (A), report the race

*Decision*: mark closed first (new loads fail fast), let an in-flight load
finish and stay usable, record `:in-flight`/`:raced` in the report. Opt-in
`{:drain-ms N}` later, only with its own analysis against jolt's
non-recursive loader mutex and claim marks.

| alt | pros | cons | verdict |
|---|---|---|---|
| **A: best-effort / undefined (JVM)** | trivial; exactly `URLClassLoader.close`'s contract | post-promotion footgun unless the race is *reported* | **chosen + `:raced`** |
| B: bounded drain (OSGi) | predictable; catches late lazy `require`s | timeout knob + failure mode; on jolt a wait parks inside the claim protocol (deadlock risk); needs slow/timing tests | deferred (opt-in later) |
| C: refuse if busy | no waiting; matches kmet's `/reload` refusal; caller decides | racy (busy is momentary); caller would need a retry loop anyway | rejected as the contract |
| D: force + discard late results | never blocks; deterministic postcondition | partial-install rollback is genuinely complex | rejected |

Mitigating fact for A: `/reload` already refuses while the agent is busy
(`interactive.clj` "Wait for the current response to finish before
reloading"), so the extension path is serialized by construction today.

### 12.3 State exposure — **`unloaded?` + `(status l)`**

*Decision*: `unloaded?` is the only behavioral predicate; `(status l)` is a
map documented as implementation-visible and free to grow keys (§4.5). No
lifecycle enum.

| alt | pros | cons | verdict |
|---|---|---|---|
| **A: `unloaded?`** | one predicate; covers the postcondition and tests | says nothing mid-unload | **chosen** |
| B: `(state l)` enum | OSGi-comprehensible | freezes a keyword vocabulary; `:unloading` is transient and any test for it is flaky | rejected |
| C: nothing | smallest API | callers can't check | rejected |
| **D: `(status l)` map** | best debugging story; matches house style (`get-loaded-extensions`, `:initialized?`) | shape becomes API (mitigated: diagnostics-only) | **chosen, alongside A** |

### 12.4 Lazy vs eager `find` — **C: data hits + loader-owned opening**

*Decision*: hits are pure data (no streams, no closures, no compiled
artifacts); `find` locates, `load` reads, `(open-hit l hit)` opens resources,
and `load` accepts a hit from `find` to skip re-resolution (§4.4). Loader
*construction* is the one eager-validation point.

| alt | pros | cons | verdict |
|---|---|---|---|
| A: eager (read at find) | simple; read errors surface at find | every probe reads; `getResources` reads all hits for one result; repeated on every resource call (§0.4); jar churn | rejected |
| B: lazy opener closure in the hit | cheap probes | hits not printable/comparable/serializable; defeats AOT read-note bookkeeping | rejected |
| **C: data hit + `open-hit` + `load` takes hit** | cheap probes; hits printable/comparable; loader owns opening (policy, read notes); validation has a home | `load` must accept a hit (small API addition) | **chosen** |

Matches jolt internals (`find-ns-file` locates / `ldr-read-source` reads;
`resolve-resource` returns a URL that opens on demand), so the native backend
maps onto it without rework.

### 12.5 Still open

- Does the conformance suite ship with the lib at promotion (a `-test`
  artifact), or stay in kmet's `test/` as the reference implementation of
  the spec? (Phase 4 detail; the suite is the spec, so this is a packaging
  question, not a design one.) The portable half needs almost nothing to
  travel: `test_core.clj` (data-path cases, any backend) and
  `test_sci_loader.clj` (code-path cases, SCI) require only `kmet.loader.*`
  — plus sci, which is that backend's suite's own need — and
  `test_jolt_loader.clj` is the Jolt adapter's (Jolt-only at runtime, skips
  elsewhere). `test_self_contained.clj` should travel with the tree it
  guards, pointed at the new root: it is what keeps promotion a move. The
  Jolt-side suite is Jolt's own (`make loaderconf`) and never ships. The one
  thing kmet's copy gets from kmet that a `-test` half would not is an entry
  point — `kmet.tasks.runner` registers the namespaces, and the lib would
  carry a `bb test`-style task or a clojure.test main in its own alias.
- `remove!` (OSGi's terminal uninstall: delete owned files/storage) — v1
  leaves it out; revisit if a loader ever owns a cache directory. No loader
  owns one today: kmet's backends and Jolt's never write anything —
  `jar-entry-source`'s "opens and closes per call, no handles are held, so
  unload needs no cleanup" is the stance throughout — and `unload!` takes
  back namespaces, not files. The caches that do exist belong to someone
  else: Jolt's host AOT cache (the host-root path) and the extension
  system's single-file temp dir (`kmet-ext-src`, keyed by the materialized
  source's content), reusable by design and left to the OS temp reaper.
