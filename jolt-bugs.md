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

### [jolt#992](https://github.com/jolt-lang/jolt/issues/992) — Windows: `jolt C:/…/file.clj` is read as project-relative — `open-input-file` fails for `./C:/…`

**Area:** `jolt-core/jolt/main.clj` (`file-arg`)

`file-arg` (main.clj:320-327) recognizes POSIX absolutes only — `"-"`,
`(str/starts-with? x "/")`, `(str/starts-with? x "./")` — so every drive-absolute
argument lands in the `:else` join `(str (project-dir) "/" x)`. `project-dir`
is `JOLT_PWD` or `"."` (main.clj:9), so the prebuilt binary tries
`./C:/Users/x/hello.clj` and both `jolt <file>` and `jolt run <file>` fail with
`open-input-file: … invalid argument`, from any cwd and for either separator
spelling. Relative and `./`-relative arguments work, which is why it is easy to
miss.

Same Windows path family as jolt#991 (the canonicalize half); both want the
classification `jolt.deps/native-path-kind-for` already does
(deps.clj:195-214: drive-absolute `C:/x`, UNC `//server/share`, root-relative
`/x`, drive-relative `C:x`), or `io.ss`'s `jfile-abs`.

**Workaround:** none needed in kmet — kmet drives jolt through `run`/tasks, not
`jolt <absolute file>`; a user hitting it can pass a cwd-relative path
(`jolt Users/x/hello.clj` from `C:\` works). Removal: nothing to delete when
`file-arg` stops joining a drive-absolute path to `project-dir`.

### [jolt#991](https://github.com/jolt-lang/jolt/issues/991) — Windows: `File.getCanonicalPath` prepends `/` to a drive-absolute path — and the value it returns cannot be used for I/O

**Area:** `host/chez/java/io.ss` (`jfile-canonical`, the no-`realpath` fallback)

`realpath(3)` is unbound on Windows, so `jfile-canonical` falls back to
`path-parent` / `jfile-fold-dots` — POSIX models that split on `/` only, keep
`C:` as an ordinary segment, and rejoin every segment as `"/" + segment`.
`File.getCanonicalPath`, `File.getCanonicalFile` and `babashka.fs/canonicalize`
therefore answer `/C:/Users/…` for every spelling of a drive-absolute path
(backslash input has no recognised separator at all), and any read or write
with the returned value fails against `C:/C:/…`. Two spellings of the same file
also canonicalize to different strings, so the identity comparison the realpath
switch exists for (jolt#693) still fails on Windows.

kmet hits it on every session write: `kmet.app.session/create-session` stamps
the session file with `(str (fs/canonicalize file))`, and print mode dies before
the first token with `open-output-file: failed for
C:/C:\Users\…\.kmet\sessions\….ednl.tmp.spit-tmp-…: invalid argument`.

**Workaround:** none in kmet — every `babashka.fs/canonicalize` consumer is hit
(session-file identity, package/extension path identity); the
`{:nofollow-links true}` spelling dodges the fallback but drops symlink
resolution, so it is not a drop-in. Removal: nothing to delete when the fallback
preserves the drive/UNC root and folds both separators — the call sites work
unchanged; verify with
`jolt -e '(println (str (babashka.fs/canonicalize "C:/…")))'`, then a Windows
`jolt run -p`. (The sibling file-argument defect is jolt#992.)

### [jolt#989](https://github.com/jolt-lang/jolt/issues/989) — Windows: a `:jolt/native` spec with no `:windows` candidates searches for nothing — and the diagnostic can't say so

**Area:** native loading / `:jolt/native` platform keys (Windows)

`jolt.main/load-natives!` (`jolt-core/jolt/main.clj:75-99`) reads candidates
from exactly one key — the one `current-platform` selects. On Windows a spec
declaring only `:darwin`/`:linux` yields `cands []`, so `some` returns nil
without a single `jolt.ffi/load-native` call, and the message says
`not found — tried [] for windows` about a search that never happened. The
libraries are not missing: `jolt-lang/crypto`, `jolt-lang/jolt-crypto` and
`jolt-lang/http-client` declare no `:windows` candidate, so the DLLs (beside
`jolt.exe`, or Git for Windows on PATH) are never asked for. Strict entry
points throw before app code runs (`jolt -e`, `run -m`, `build`); the task
path warns on every invocation. The issue carries a `user32.dll` minimal repro
(always in `system32`, still "not found") and the full code trace.

kmet hit it as three warnings from `jolt run -p 'say hello'` (crypto, ssl, z)
and a fatal `jolt -e`. An app cannot just add the missing key: the project's
own `:jolt/native` is first through the dedup (`jolt.deps` `:natives`,
deps.clj:1794, keyed on `:name` by `native-key`, deps.clj:1000), so a
windows-only project entry would dedup the deps' linux/darwin specs away and
break those platforms instead.

**Workaround** (`deps.edn`): the project-level `:jolt/native` block declaring
`crypto`/`ssl`/`z` — the upstream `:darwin`/`:linux` candidate lists mirrored
verbatim (mandatory, see the dedup above) plus the Windows names jolt's own
Maven fetcher already uses (`stdlib/jolt/mvn_http.clj` crypto-names /
ssl-names): `libcrypto-3-x64.dll`, `libssl-3-x64.dll`, `zlib1.dll`. The DLLs
are environment, not repo code — Git for Windows supplies them (on PATH), and
the loader also finds them beside `jolt.exe` (executable-directory search).
Verified on v0.8.7/Windows 11: the three warnings disappear, a strict
`jolt -e` in the checkout runs, and OpenSSL works (`MessageDigest` SHA-256
through the loaded DLL). Removal: delete the `:jolt/native` block from
`deps.edn` and re-run `jolt -e '(println :ok)'` on Windows with the DLLs
present — no complaint means the loader/library side has caught up. (`z` may
outlive the jolt fix — its gap is the `zlib1.dll` name, which no generic
spelling/glob fallback can derive; filed as jolt#990 below.)

### [jolt#990](https://github.com/jolt-lang/jolt/issues/990) — `crypto` / `ssl` / `z`: declare `:windows` candidates for the OpenSSL and libz natives

**Area:** `:jolt/native` declarations (`jolt-lang/crypto`, `jolt-lang/jolt-crypto`, `jolt-lang/http-client`)

The libraries kmet resolves for OpenSSL (`io.github.jolt-lang/crypto`, plus
`jolt-lang/jolt-crypto` riding `io.github.jolt-lang/http-client`'s own
deps.edn) and libz (`io.github.jolt-lang/http-client`) declare only
`:darwin`/`:linux` candidates, so on Windows jolt#989's empty-candidate path
fires for all three. Filed in the jolt tracker so the declarations land
together with the loader fix; the candidates are Git for Windows' DLL names —
the same ones jolt's own Maven fetcher already uses
(`stdlib/jolt/mvn_http.clj`): `libcrypto-3-x64.dll` (+ `-3.dll`,
`-1_1-x64.dll` fallbacks), `libssl-3-x64.dll` (+ fallbacks), `zlib1.dll`
(+ `z.dll`). Fixing this alone makes the kmet-side block redundant; fixing
only jolt#989 does not. Both crypto/ssl declarers must carry the key — the
same-`:name` dedup keeps the first spec through the graph, so a single fixed
repo may still lose to the other's unix-only entry.

**Workaround** (`deps.edn`): the same project-level `:jolt/native` block as
jolt#989 — one block, one removal step. Removal: bump the
`io.github.jolt-lang/crypto` and `io.github.jolt-lang/http-client` git shas to
revisions carrying the `:windows` keys, delete the `:jolt/native` block, and
re-run `jolt -e '(println :ok)'` on Windows with the DLLs present. If only
jolt#989 has landed by then, the block still has to stay for `z`.

### [jolt#985](https://github.com/jolt-lang/jolt/issues/985) — `(instance? java.lang.Object <java.time value>)` is false; `Class.cast` to `Object` throws, breaking SCI interop with java.time arguments

**Area:** java.time shim / instance? arms / SCI interop

Every `java.time.*` shim value (`ZoneId`, `ZoneOffset`, `ZonedDateTime`, …)
answers **false** to `(instance? java.lang.Object v)`, and `(.cast Object v)`
throws `class java.time.ZoneId cannot be cast to class java.lang.Object`.
`jolt.time.impl`'s value-semantics seam registers an instance-check arm
(`stdlib/jolt/time/impl.clj:126`) that answers a definitive `false` for any
class name a type's `:classes` set lacks — and no java.time type lists
`Object`/`java.lang.Object` (zone-id carries only `java.time.ZoneId`,
`ZoneId`, `Serializable`). Registered checks are consulted before the generic
"every non-nil value is an Object" root arm
(`host/chez/java/records-interop.ss`), and a non-nil answer wins, so the
false sticks; `Class.cast`
(`host/chez/java/host-static-classes.ss:2346`, whose comment documents that
casting to `Object` must be the identity for interpreted calls) then reports
it.

Compiled code never casts, so the invariant lie stays invisible until SCI:
extensions are SCI-evaluated, `sci.impl.reflector/box-arg` casts every
interop argument to the reflected parameter type, and jolt reports every
parameter as `java.lang.Object` — so any interpreted interop call with a
java.time value as an **argument** dies; receivers are not boxed
(`(.getId zone)` works, `(.withZoneSameInstant zdt zone)` does not).

kmet's `/deepseek-peak` extension is the first hit: its panel computes
`(java.time.ZonedDateTime/now @local-zone)` — a ZoneId argument — and the TUI
reports

```
input dispatch: class java.lang.ClassCastException:class java.time.ZoneId cannot be cast to class java.lang.Object
```

Repro without SCI — the invariant fails, and the cast throws the same
error:

```sh
$ jolt -e '(require (quote jolt.time))
            (println (instance? java.lang.Object (java.time.ZoneId/systemDefault)))'
false
```

Any SCI eval of `(java.time.ZonedDateTime/now (java.time.ZoneId/systemDefault))`
fails identically (checked on `v0.8.7-38-g9a7cb178`, and from a `v0.8.6-86`
build). Putting `java.lang.Object`/`Object` into the zone-id type's
`:classes` flips both `instance?` and `Class.cast`, and the SCI repro then
evaluates — the suggested one-line fix is adding those two names to the
arm's whitelist.

**Workaround:** none in kmet — the extension uses java.time exactly as it
does on bb/JVM, so there is nothing local to rephrase; the failure is
entirely runtime-side. When the upstream fix lands, no kmet code changes are
needed (there is nothing to delete here): `extensions/deepseek-peak.clj`
starts working as written — verify by running the command under jolt.

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

ps`
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

