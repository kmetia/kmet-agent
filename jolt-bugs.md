# jolt-bugs — open upstream tickets

Every **open** jolt-side ticket whose fix requires a change in kmet. Closed and
unfiled findings are not tracked here. `jolt-port.md` / `jolt-tui.md` describe
port state without ticket IDs.

**Workarounds live next to their ticket below.** Each workaround block is the
removal checklist: when an upstream fix lands, delete the listed code (and the
block). Source comments describe the local *why* without ticket numbers —
grep this file to find what `file:line` belongs to which ticket.

Historical labels from the deleted `bb-jolt.md` map as: `JOLT-12`→#947,
`JOLT-13`→#944; git history has the full field reports.

## Open

### [jolt#983](https://github.com/jolt-lang/jolt/issues/983) — hinted instance call reports "No dependency provides" for a supplied class

**Area:** SCI interop / class graph

An interpreted source that carries a SHORT type hint naming a JDK class
Jolt's class graph does not model — the lsp-adapter's `^StringBuilder` is
the first hit — fails analysis with Jolt's own

```
No dependency provides java.lang.StringBuilder — a concrete implementation
of the JDK classes must be provided. A library supplies one by declaring
:jolt/provides in its deps.edn (RFC 0014).
```

instead of a "no such class" error. Without the hint the same code runs
(the instance path is dynamic). The extension loader registers hinted names
through `bb-imports` + `Class/forName` + `sci/add-class!` under both the
short and FQ symbol, which is not enough: the message is raised by Jolt's
analyzer when it resolves the hint, before SCI's class table is consulted
for the member call.

**Workaround:** none in kmet — the affected shipped extensions load on bb
only (`test-shipped-extensions-load-from-src` is gated there), and the
unified loader's core load/unload path is covered on both hosts.

### [jolt#978](https://github.com/jolt-lang/jolt/issues/978) — `java.lang.StringBuffer` exposes no constructor (`No matching ctor found for class StringBuffer`)

**Area:** java.lang / host classes

`StringBuffer` is a known class value on Jolt (`(println StringBuffer)` →
`java.lang.StringBuffer`; its ancestors are `CharSequence`, `Appendable`,
`Comparable` and `Object`), but **no constructor exists** — neither
`(StringBuffer.)` nor `(StringBuffer. "x")` — so no instance can be made and
the class is inert:

```
$ jolt -e '(StringBuffer.)'
Unhandled exception (IllegalArgumentException): No matching ctor found for class StringBuffer
```

`StringBuilder` is fully modeled (ctor, `.append`, `str`), so the gap is
StringBuffer-specific — the legacy synchronized builder was apparently never
registered in the host class graph. Any library on the JVM-family reader
path that still uses it fails; the first hit is rewrite-clj 1.2.50 (cljfmt
0.16.5's parser): `rewrite_clj/reader.cljc`'s `read-while` does
`(let [buf (StringBuffer.)] … (.append buf c) … (.toString buf))`, so
`cljfmt.core/reformat-string` — and therefore every `cljfmt.tool` entry
point, i.e. `jolt format` / `jolt format-check` — dies on the constructor.
On babashka the identical code works (bb ships a port of this surface).

Minimal repro (the two builders side by side):

```sh
jolt -e '(StringBuffer.)'
# IllegalArgumentException: No matching ctor found for class StringBuffer
jolt -e '(let [sb (StringBuilder.)] (.append sb "a") (println (str sb)))'
# a
```

Semantics to supply: the 0-arg (and the `String`) constructor over a
mutable char buffer, plus `append(char)` / `append(String)` and `toString`
— the members `read-while` calls. Synchronization is not observable at
Jolt's level for this use, so wrapping/aliasing StringBuilder's backing
store would satisfy the callers.

**Workaround** (`deps.edn`): `rewrite-clj/rewrite-clj {:mvn/version "1.2.57"}`
is declared alongside cljfmt, so jolt parses with the version babashka
already bundles (bb's built-in wins over cljfmt's transitive 1.2.50 there,
and 1.2.55 moved the JVM-family reader onto `StringBuilder`; the pin also
keeps both hosts on the same parser). Removal: when the runtime supplies the
ctor the pin is no longer load-bearing — dropping it restores cljfmt's
1.2.50 on jolt, while babashka keeps its bundled 1.2.57. Seeding: this host
could not fetch the jar at all, so it was downloaded through babashka's
resolver once (see jolt#979 below).

### [jolt#979](https://github.com/jolt-lang/jolt/issues/979) — `jolt.mvn-http` reads `ai_addr` at the glibc offset: on Android/bionic it gets NULL, `connect()` EFAULTs, fetching fails

**Area:** dependency resolution / ffi struct layout (Android/bionic)

Any artifact not already in the local Maven cache cannot be fetched; the
fetch never gets past TCP connect:

```
Error building classpath. The following artifacts could not be resolved:
  rewrite-clj/rewrite-clj 1.2.57 — could not be fetched: https://repo.clojars.org
  — connection refused: repo.clojars.org:443; https://repo1.maven.org/maven2
  — connection refused: repo1.maven.org:443
```

`jolt.mvn-http`'s `connect` (`stdlib/jolt/mvn_http.clj`) hardcodes the glibc
layout — `O-ai-addr 24` on every non-macOS/non-Windows platform — but
**bionic's `struct addrinfo` is BSD-ordered** (`ai_canonname` before
`ai_addr`, confirmed by the device header `$PREFIX/include/netdb.h`):

```c
struct addrinfo {
  int ai_flags; int ai_family; int ai_socktype; int ai_protocol;
  socklen_t ai_addrlen;          /* off 16 — same in both layouts   */
  char *ai_canonname;            /* off 24 — NULL without AI_CANONNAME      */
  struct sockaddr *ai_addr;      /* off 32 — read from off 24 instead  */
  struct addrinfo *ai_next;      /* off 40 — same in both layouts   */
};
```

So on Android `(ffi/read ai :pointer 24)` yields NULL and
`connect(fd, NULL, 16)` returns -1/errno 14 (**EFAULT, "Bad address"**). The
all-candidates-failed branch then labels the exhaustion
`connection refused: <host>:<port>` (`mvn_http.clj` ~line 167) and `jolt.deps`
prints it as "could not be fetched" — two layers of misdiagnosis over a NULL
pointer. The macOS offsets in the same file (`O-ai-addr 32`) are already the
ones bionic needs; the platform classification just treats Android as Linux.

Measured here (jolt `v0.8.1-392-gbe356e59`, Termux/Android aarch64, bionic from
`/apex/com.android.runtime/lib64/bionic/libc.so`):

| probe | result |
| --- | --- |
| `getaddrinfo` repo.clojars.org:443 | rc 0 — AF_INET `family 2` / AF_INET6 `family 10`, `socktype 1`, `protocol 6`, `addrlen` 16/28 |
| raw 48-byte node dump | off 24 = `00 00…` (NULL); off 32 = a pointer |
| `connect` with `(ffi/read ai :pointer 24)` | -1, errno 14 EFAULT — every entry, every host, a live 127.0.0.1 listener included |
| `connect` with `(ffi/read ai :pointer 32)` | 0 — success; sockaddr bytes `02 00 01 bb 97 65 01 80` = AF_INET, port 443, 151.101.1.128 |

`stdlib/jolt/socket.clj` builds its own `sockaddr` (`make-sockaddr`) and is
not affected — only the addrinfo-parsing code is. `curl` and babashka's JVM
resolver reach the same repos fine, so nothing about the network is at fault.

**Workaround:** none in kmet for the fetch — the one artifact this mattered
for (rewrite-clj 1.2.57) was seeded into `~/.m2/repository` through
babashka's resolver, and every cached artifact resolves offline. On bionic a
new dep needs the same seeding until the offsets are fixed.

## Closed — workarounds removed

Re-verified 2026-09-11 on the locally built **`v0.8.6-98-g23296732`** (the
fixes are `2bd77e53` / PR #959, beyond the `v0.8.6-86` build the previous
re-verification used). Every kmet workaround is gone; the ledger below is what
was removed and where the code stands now.

### [jolt#944](https://github.com/jolt-lang/jolt/issues/944) — `jolt build` binary dies on `unbound fn jolt.time.impl/register-type!`

**Area:** build / AOT

The class-scan and data-reader namespaces now load under the loader's order
hook, so a provider split across the app's deps and jolt's embedded stdlib
emits callee-before-caller consistently. No kmet workaround existed; nothing to
delete. Re-verify with a `jolt build -m kmet.core` smoke run.

### [jolt#947](https://github.com/jolt-lang/jolt/issues/947) — `ProcessBuilder.redirectInput(File)` silently ignored

**Area:** process

`redirectInput(File)` is `redirectInput(Redirect.from(file))` now (as are the
`redirectOutput`/`redirectError` `File`/`Path` overloads), so the redirect
reaches fd 0.

**Removed** (`src/kmet/app/bash_executor.clj`): the spawn's `:in :pipe` +
close-after-spawn workaround is gone for the non-`-s` transport. Stdin is
`(fs/file (if process/windows-os? "NUL" "/dev/null"))` — pi's stdio
`ignore` — and only the WSL `bash -s` transport keeps a real pipe (it writes
the command, then closes it). Regression test:
`kmet.app.test-tools/test-tool-bash-stdin-eof`.

### [jolt#949](https://github.com/jolt-lang/jolt/issues/949) — multi-arg `java.net.URI` constructors missing (only the `String` ctor)

**Area:** net / URI

The JDK's other four ctors exist (composing + quoting, like the JDK's).

**Removed** (`src/kmet/ai/api/azure_openai_responses.clj`): the hand-rebuilt
`scheme://[userinfo@]host[:port]/openai/v1` string. `normalize-azure-base-url`
now derives it through the 7-arg `(java.net.URI. scheme userInfo host port path
query fragment)` ctor.

### [jolt#950](https://github.com/jolt-lang/jolt/issues/950) — `java.net.http.HttpTimeoutException` has no constructor

**Area:** net

The class (and its `HttpConnectTimeoutException` subclass) has a ctor now,
both plain `IOException` subclasses as on the JDK.

**Removed:** the RFC 0014 registration in `jolt/` — the ctor shim + the
`IOException` hierarchy edge are gone, and the `:jolt/provides` claim is empty.
The runtime *implements* the class now, so a claim would be refused at startup
anyway (`jolt.kmet.providers claims host classes … which the runtime already
provides`). The provider lib stays as empty scaffolding
(`jolt/src/jolt/kmet/providers.clj`, `jolt/deps.edn`).

### [jolt#951](https://github.com/jolt-lang/jolt/issues/951) — `LinkedBlockingQueue` has no constructor

**Area:** java.util.concurrent

**Removed** (`src/kmet/libs/sse.clj`): the fixed-capacity
`(ArrayBlockingQueue. 65536)` in the SSE body reader's idle-deadline queue is
`(LinkedBlockingQueue.)` again — unbounded, so the daemon no longer
backpressures. `stop` interrupts the daemon, which still releases a blocked
`put`/`poll`.

### [jolt#954](https://github.com/jolt-lang/jolt/issues/954) — `SocketOutputStream.write(byte[])` throws a cast error

**Area:** net / sockets

The 1-arg write dispatches on its argument, so the whole-array overload works.

**Removed:** the 3-arg `(.write out b 0 (alength b))` workarounds —
`src/kmet/libs/oauth.clj` now writes `head-bytes`/`body-bytes` whole, and the
`sock-write` helpers in `test/kmet/libs/test_http.clj` and
`test/kmet/ai/test_llm.clj` are deleted (the test servers write directly); a
`test/kmet/libs/test_oauth.clj` comment and the `out-write` helper went with
them.

