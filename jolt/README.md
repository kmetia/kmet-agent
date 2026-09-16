# jolt/ — kmet's RFC 0014 provider lib

kmet's RFC 0014 provider scaffolding (jolt-port.md §9). This directory is a
self-contained library: `jolt/deps.edn` declares its source root and its
`:jolt/provides` (RFC 0014); kmet's root `deps.edn` pulls it in as
`{:local/root "jolt"}`. It is **inert on bb/JVM** — nothing on the bb
classpath ever requires a `jolt.kmet.*` namespace, and the class
registrations below are jolt-only hooks.

**EMPTY BY DESIGN.** Every JDK gap this lib was created for is runtime
surface now (jolt-bugs.md records the closures): `HttpTimeoutException`'s
ctor, the multi-arg `java.net.URI` ctors, `ProcessBuilder`'s `File`
redirects, `SocketOutputStream.write(byte[])`, `LinkedBlockingQueue` and the
Base64 MIME pair all come from the runtime, so nothing is claimed and
nothing is registered. The scaffolding stays because RFC 0014 is how a JDK
gap gets filled: the next one adds its class to `:jolt/provides` and its
member registration to `jolt.kmet.providers/install!`.

**Scope:** what the jolt runtime lacks and jolt.crypto does not cover.
RSA is jolt.crypto's — it provides `Signature` / `KeyPairGenerator` /
`KeyFactory` for RSA (and EC) and claims those classes itself. A *member*
of a class the runtime IMPLEMENTS but does not fully supply cannot be
claimed at all; that needs the guarded-require convention instead
(AGENTS.md's `jolt/` section). When even a registration cannot back the
member — `java.lang.Object`'s `wait`/`notify` pair (a gap until v0.8.8-53,
now runtime surface — `kmet.tui.wake` parks on the object monitor).

**Re-verified 2026-09-11 on `v0.8.6-98-g23296732`:** all six shims the lib
used to carry are live in a bare Jolt — `(java.net.http.HttpTimeoutException. "x")`,
`(java.net.URI. …7 args…)`, `redirectInput(File)`, `.write` a `byte[]` to a
socket, `(LinkedBlockingQueue.)`, and both `URI` arities — so the lib holds
no provisions.

## No `jolt.crypto` require

`jolt.kmet.providers` requires nothing at all while empty (not even
`jolt.host`). crypto's classes resolve through crypto's own
`:jolt/provides` claims on the first reference: a declared provider resolves
its class whatever loaded first, owns the members it registers, and is
attributed to itself — so no require is needed to pin an order.

That the require is gone changes nothing about the crypto classes' load
order requirements:

- jolt.crypto's `deps.edn` declares `:jolt/native` — libcrypto/libssl load
  with crypto itself; jolt dedupes natives by `:name`, so an app that also
  pulls http-client shares the one copy.
- jolt.crypto's `install!` registers the crypto `java.*` classes as its
  install namespace loads, and RFC 0014 makes what a declared provider
  implements its own (a registration that would replace one of its
  members is dropped).
- jolt.crypto's own `:jolt/provides` claims `Signature` / `KeyPairGenerator`
  / `KeyFactory` (EC and RSA) and the spec classes — a dependent referencing
  one of those autoloads crypto directly, with no kmet involvement.

## What is provided

Nothing. The table is empty while the lib is a no-op; a future shim adds a
row and the matching `install!` registration.

## Verification

```sh
# the lib loads (no-op install):
jolt -e "(require 'jolt.kmet.providers) (println :ok)"
# the runtime supplies what the lib used to shim:
jolt -e "(println (class (java.net.http.HttpTimeoutException. \"x\")))"
jolt -e "(println (java.util.concurrent.LinkedBlockingQueue.))"
```
