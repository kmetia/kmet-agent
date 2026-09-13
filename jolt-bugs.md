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

### Type hints on JDK classes Jolt does not supply fail the extension load

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

### [jolt#967](https://github.com/jolt-lang/jolt/issues/967) — bare sonames miss the Termux prefix: `:jolt/native` / `load-library` bind Android BoringSSL

**Area:** ffi / native loading (Android)

An Android jolt process runs under `/system/bin/linker64`, which searches
`/system/lib64` and **not** `$PREFIX/lib`. A bare `libssl.so` therefore binds
BoringSSL (`/system/lib64/libssl.so`, SONAME `libssl.so`) — it exports
`SSL_new` but not `SSL_ctrl`, and its `SSL_CTX` layout disagrees with OpenSSL
3's — while the wanted `libssl.so.3` / `libcrypto.so.3` exist only under the
Termux prefix and so never load. jolt-crypto's `:jolt/native` specs (which
http-client depends on for the OpenSSL declarations) name exactly those bare
sonames, so the wrong library is bound at **startup**, before any project
code: `jolt generate-models` died on its first TLS fetch with
`foreign-procedure: no entry for "SSL_ctrl"`, and loading the real OpenSSL
alongside it faults with `invalid memory reference` plus a duplicate-symbol
report. `ffi-so-search-dirs` (behind `load-system-library`) misses the prefix
the same way.

**Workaround** (`deps.edn`): a project-level `:jolt/native` for `crypto` and
`ssl` naming the Termux OpenSSL absolute-first
(`/data/data/com.termux/files/usr/lib/lib{crypto,ssl}.so.3`). The `:name` keys
match jolt-crypto's, so `dedup-by native-key` keeps the project entry — and
because that entry **replaces** the spec rather than merging per-platform, the
`:darwin` arm must mirror jolt-crypto's Homebrew paths. The paths are inert
where the prefix does not exist. `LD_LIBRARY_PATH=$PREFIX/lib jolt …` is the
out-of-process equivalent.

### [jolt#968](https://github.com/jolt-lang/jolt/issues/968) — `java.math.BigDecimal` exposes no instance members

**Area:** java.math / host classes

BigDecimal's value model works (literals, ctor, `valueOf`/`ZERO`/`ONE`/`TEN`,
clojure.core arithmetic, `compare`, `str`/`double`/`long`, `=`), but the class
answers **no instance members** — no methods and no fields. Every
`(.something bd …)` raises: `No matching method <m> found taking N args` for
argument-taking members, `No matching field found: <m>` for zero-arg ones.
`host/chez/java/bigdec.ss` registers the ctor and statics but no instance
members. Hit while running `jolt generate-models`, whose cost renderer called
`(.movePointLeft (BigDecimal. (Math/round (* n 1000000.0))) 6)`.

**Workaround** (`src/kmet/libs/edn_writer.clj`): `num-str` builds the scale-6
value with the two-arg static, `(BigDecimal/valueOf (Math/round (* n 1000000.0))
(int 6))`, instead of `(.movePointLeft <bigdec> 6)` — same value, and jolt
implements `valueOf`. No other instance members are needed by kmet.

### [jolt#969](https://github.com/jolt-lang/jolt/issues/969) — `apply` ignores `IFn.applyTo` on deftype/record/reify callables, breaking >20-arg application

**Area:** runtime / IFn dispatch

`apply` funnels everything through `jolt-invoke`, which dispatches to a
non-host-`AFn` IFn implementer's `invoke` by arg count and never consults
`applyTo`; the 21-param varargs `invoke` is treated as a fixed arity. Host
fns (the `jolt-register-variadic!` path) are unaffected. SCI compiles any
call with ≥3 args to `(apply f args)`, and `sci.lang.Var` is such a value,
so a core-fn call with **more than 21 arguments** throws
`Wrong number of args (N+1) passed to: fn`. First hit: the mcp-adapter's
config template built with `(str …35 args…)`.

**Workaround:** avoid >21-arg direct calls — the extension's three large
string-building `(str …)` forms became `(apply str […])` (the `apply` is a
2-arg call, and host `apply` does reach the target's `applyTo`):

- `extensions/mcp-adapter/src/extensions/mcp_adapter/config.clj:263` (`template-edn`)
- `extensions/mcp-adapter/src/extensions/mcp_adapter/auth.clj:189` (`windows-cred-body`)
- `extensions/mcp-adapter/src/extensions/mcp_adapter/script.clj:79` (`runtime-source`)

The bug also hits **macro invocation**: SCI applies a macro to `&form`,
`&env` and the call's arguments, so any macro call with **≥19 arguments**
fails too (`cond` with ≥10 clauses, `case` with ≥10 pairs). Three such
sites exist in the extension and are **not yet rewritten** — they are
harmless until the protocol blockers (#970/#971) land, since the load dies
earlier:

- `extensions/mcp-adapter/src/extensions/mcp_adapter.clj` — `case` (14 pairs)
- `extensions/mcp-adapter/src/extensions/mcp_adapter/panel.clj` — `cond` (15 clauses)
- `extensions/mcp-adapter/src/extensions/mcp_adapter/tool_proxy.clj` — `cond` (10 clauses)

Rewrite these split/nested (or via a helper) when the extension can load
to completion on jolt.

### [jolt#970](https://github.com/jolt-lang/jolt/issues/970) — `clojure.lang.MultiFn` has no JVM-compatible constructor

**Area:** runtime / multimethods

SCI's `defprotocol` expands to `defmulti`, which builds the multifn with
`(new clojure.lang.MultiFn name dispatch default hierarchy)`. jolt models
multimethods as the custom `jolt-multifn` record and registers no ctor for
the class name, so `host-new` throws `No matching ctor found for class
clojure.lang.MultiFn`. Every `defprotocol` under SCI on jolt therefore
fails. Plain jolt `defprotocol`/`defmulti` (the compiler path) is fine.

**Workaround:** none in kmet — blocked until jolt registers the ctor. The
mcp-adapter (`defcomponent` → `defrecord` implementing a protocol) stays
unloadable on jolt.

### [jolt#971](https://github.com/jolt-lang/jolt/issues/971) — `extend-protocol` onto a deftype/record mutates the class

**Area:** runtime / protocols

jolt folds an `extend-protocol` implementation into the target type's class
(the method plus `__jolt_extend__` shows up in `.getDeclaredMethods`), so
`instance?` against the protocol interface is true for an extended-only
type. The JVM leaves the class untouched (`satisfies?` true, `instance?`
false). SCI's `eval-node?` is `(instance? sci.impl.types.Eval x)` and relies
on the JVM rule, so it returns true for `sci.lang.Var` (which SCI only
`extend-protocol`s onto `Eval`) and `eval-resolve` then discards every
resolved value: `resolve`/`ns-resolve` return nil inside a jolt SCI context,
and `defrecord`/`deftype` protocol lookup dies with `Protocol not found`.
Blocks every component-defining extension (mcp-adapter's panel) even after
#970 lands.

**Workaround:** none in kmet — blocked until jolt stops mutating the class.

### [jolt#972](https://github.com/jolt-lang/jolt/issues/972) — `clojure.core.async` is interned at startup with a partial var set (`all-ns` exposes an incomplete namespace)

**Area:** runtime / namespaces (vendored stdlib)

In a fresh process `clojure.core.async` is already in `all-ns` / `find-ns`
with only 34 of its 130 vars; `require` interns the remaining ~96 into the
same namespace object. Every other vendored built-in (`babashka.fs`,
`babashka.process`, …) is absent from `find-ns` until required. Code that
snapshots namespace contents from `all-ns` — kmet's loader injects the
shared namespaces into each SCI context by reference (`sci/copy-var*`
over `ns-interns`) — captured the 34-var namespace, shadowing the real one,
so an extension's `(async/alts!! …)` failed with `Unable to resolve symbol:
async/alts!!` even though the `:require` itself resolved.

**Workaround** (`src/kmet/app/extensions.cljc:1652`): `host-requires!` does
`(require 'clojure.core.async)` before building contexts, so the `all-ns`
scan sees the complete namespace. Inert on bb/JVM, where it is already
loaded.

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

**Workaround:** none in kmet — the format tasks are one code path on both
hosts and this library/runtime error is left to surface (upstream
rewrite-clj 1.2.55 moved JVM-family readers to `StringBuilder`, but kmet
does not carry a version override for it). Formatting runs on `bb` until the
runtime provides the ctor.

### `jolt.mvn-http` reads `ai_addr` at the glibc offset: on Android/bionic it gets NULL, `connect()` EFAULTs, fetching fails

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

