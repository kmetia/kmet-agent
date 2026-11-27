# jolt-bugs — reported upstream issues

Open issues tracked by kmet. Last checked on 2026-11-26 with the installed
`v0.8.30-84-gc4ca59ca` (Windows x86_64).

The latest tagged release is
[Jolt v0.8.30](https://github.com/jolt-lang/jolt/releases/tag/v0.8.30).
The `:jolt/min-version` floor in [`deps.edn`](deps.edn) and
[`jolt/deps.edn`](jolt/deps.edn) is still `0.8.27`.

kmet pins `jolt-lang/http-client` at `b45e46f`, the
[v0.1.9 tag](https://github.com/jolt-lang/http-client/releases/tag/v0.1.9)
(CI green on 2026-11-14). Upstream `main` has moved to `bf1485eb` (SSE
support, PR #50); its tests passed on 2026-11-24 and there are no open tickets.

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host protocols on Jolt (`IVar/getRawRoot`)

Still blocked on [babashka/sci#1093](https://github.com/babashka/sci/pull/1093);
[yogthos/sci#2](https://github.com/yogthos/sci/pull/2), the CI fix for that PR,
is also open. Jolt v0.8.30, the installed build and upstream `main` all
vendor SCI at `32d62a5`.

On the installed Jolt, a SCI `defrecord` implementing a copied host
protocol still fails with `IVar/getRawRoot` when run against `32d62a5`.
With kmet's SCI pin (`1295142`, the #1093 branch), the same record loads and
host-side protocol dispatch works. Keep the pin in `jolt/deps.edn` and
`modules/app/deps.edn` until Jolt releases the fix. This affects the `:sci`
loader fallback; shipped extensions declare `:jolt` and use the native
loader on Jolt.

### [jolt#1911](https://github.com/jolt-lang/jolt/issues/1911) — Unix-domain sockets (`UnixDomainSocketAddress` and NIO channels)

Open (Backlog, 2026-11-27). Jolt v0.8.31 has TCP sockets but no `AF_UNIX` in
`stdlib/jolt/socket/native.clj`, no `java.net.UnixDomainSocketAddress` /
`StandardProtocolFamily/UNIX`, and no `java.nio.channels` surface at all.
kmet's `:mode-server` row isolates the unix transport in
`modules/app/src/kmet/app/modes/server-unix.clj`, and `--socket` falls back to
TCP loopback with a warning where the host cannot serve one.

When #1911 lands, check `server-unix/available?`: Jolt registers classes
lazily, so `Class/forName "java.net.UnixDomainSocketAddress"` throws until the
defining stdlib namespace loads — the same is true of `java.net.ServerSocket`
before `jolt.socket`. The probe must trigger that load, or upstream must make
`Class/forName` do it, before the row can use the unix transport.
