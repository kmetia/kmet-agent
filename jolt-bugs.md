# jolt-bugs — open upstream tickets

Every **open** jolt-side ticket whose fix requires a change in kmet or the
removal of a kmet workaround — plus the findings that needed neither, kept
as status notes below. Closed findings are not tracked
here — recent closures (jolt v0.8.8-53 / http-client PR #21): #1011
(`Object.wait`/`notify`), #1007 (streaming HTTP), #1015/#1016
(`StringBuilder` `append`/`insert` char[]), #1017 (stream timeout), #1006
(SCI `copy-var*` host protocol), #1020 (host method arity), #1021
(`String/copyValueOf`), #1022 (`StringBuilder.getChars`). Their workarounds
are removed from kmet alongside this edit. Further closures (jolt PR #1030,
in v0.8.8-64-g705ac0b9): #992 (absolute FILE args), #991 (canonicalize drive
root), #989 (native loader fallback + reconcile), #990 (`crypto`/`ssl`/`z`
:windows candidates — closed by the loader half of PR #1030, the spelling
fallback now resolves `crypto`/`ssl` without app-side help). The library-side
follow-through closed 2026-09-18: crypto#11 (crypto PR #10, main `8d821a9d`)
and http-client#23 (http-client PR #22, main `04ebbc03`) — both libs declare
their native candidates for `:darwin`/`:linux`/`:windows` themselves, so the
project-level `:jolt/native` block in `deps.edn` is removed alongside this
edit and both pins move to the fixed revisions. Still owed: the Windows smoke
(`jolt -e '(println :ok)'` with the libcrypto/libssl/libz DLLs beside
`jolt.exe` or on PATH) once a Windows host runs it. `jolt-port.md` /
`jolt-tui.md` describe port state without ticket IDs.

**Zip/unzip and archive loading (jolt PR #1044, merged 2026-09-18, in
v0.8.8-150-g2e19b70f).** Closes #988 (no dependency on the `unzip`
program), #1005 (Maven deps load from their jars in place — no extraction
directories, no `.jolt-ok` markers) and #916 (`java.util.zip`: Inflater,
Deflater, CRC32, Adler32, the deflate/GZIP streams, ZipInputStream,
ZipOutputStream, ZipFile, ZipEntry, and their exceptions; `jolt.loader`
accepted a jar root since the same wave — a `require` reads the central
directory, `io/resource` answers `jar:file:…!/entry` URLs, `*file*` carries
that spelling). The kmet workarounds this closed are removed alongside this
edit: `kmet.libs.archive` is portable (`extract-zip!` no longer fails
`::bb-only` on Jolt; the guard, its `kmet.libs.host` require and the
`^:bb-only` test gates are gone), and extension archives stay unexpanded on
Jolt — `kmet.app.extensions` roots a jar artifact at the archive, reads
entries through `java.util.zip.ZipFile` (not `java.util.jar.JarFile`, which
Jolt does not implement), and the native loader reads the jar in place; the
`unzip` materialization cache (`materialize-jar!`) is deleted. `jolt.fs`
exports `zip`/`unzip`/`gzip`/`gunzip` too. The packed-clojure roundtrip test
stays `^:bb-only` for its dependency closure, not for zip mechanics.

**libz claims (http-client PR #25, merged 2026-09-19, main `f517b2d4`).**
The library drops its libz shims and the stale `:jolt/provides` claims on the
`java.util.zip` GZIP/Inflater classes, so the per-run `upgrade
io.github.jolt-lang/http-client` warning is gone; its `:jolt/min-version` is
`0.8.9` (where java.util.zip entered the runtime), which the toolchain
(v0.8.9-7-gc6086cf5) meets. No kmet workaround to delete — the `deps.edn`
pin simply moves to the merge.

**Loader facade (jolt PR #1053, merged 2026-09-19, in
v0.8.9-17-g73e0bd4a).** A loader's classloader facade moves to a
`compare-and-set!` slot on the loader itself, so a reused `:id` can never
serve a previous context's facade; the id-keyed `facades` table (and its
retention of every loader ever constructed) is deleted, `reset-context-state!`
is left without a side table to clear, and `loaderconf` case 31 pins the
reload shape. The per-context `ext:<name>#N` counter workaround in
`kmet.app.extensions/create-jolt-loader` is removed alongside this edit (the
loader id carries only its diagnostic prefix again), and
`kmet.loader/loader.md` drops its stale `facades` references.

**http-client#26 (PR #27, merged 2026-09-19, main `281689c9`).**
`HttpRequest.timeout` no longer cuts a body already in flight, so a stalled
SSE body reaches the idle arm on both hosts. kmet's pin moves to the merge
and the `read-timeout-exception?` classification in
`kmet.libs.sse/make-idle-reader`, with its regression test, is removed
alongside this edit.

**Bionic build (jolt PR #1054, merged 2026-09-19, in
v0.8.9-17-g73e0bd4a).** Three Android/Termux gaps found building the
toolchain there, all fixed by the PR: a built app ran with **no heap
ceiling** (physical-memory detection tried only the glibc and Darwin
`sysconf` name pairs; bionic's are 39/98), leaving the kernel-kill failure
mode the ceiling exists to prevent; `jolt build` app links died on
`libiconv_open`/`libiconv_close` (the Linux link line never named `-liconv`,
and bionic has no iconv in libc), which is why Termux app builds needed a
`cc` shim appending it; and jolt's own Chez provisioning could not run at
all — makes' xPack GCC is a glibc binary the bionic loader cannot exec, and
Chez's `make install` hard-links petite/scheme-script, which app data
refuses — so `make` now builds the pinned release with the host compiler
and stages the install itself, static `libz.a`/`liblz4.a` beside the
kernel. No kmet workaround is tied to any of the three; the local Termux
build wrapper's `cc` shim and hand-rolled provisioning are redundant now
that the checkout carries the PR.

**Upstream status:** one open item — the SCI IVar gap, filed as
[jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) (`deferred`),
re-diagnosed upstream in jolt PR #1033 — with its fix at
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) (re-checked
2026-09-20: open, head `1295142f`, unchanged), so the `jolt/deps.edn` SCI pin
stays. Every other ticket this file tracked is closed.

**Workarounds live next to their ticket below.** Each workaround block is the
removal checklist: when an upstream fix lands, delete the listed code (and the
block). Source comments describe the local *why* without ticket numbers —
grep this file to find what `file:line` belongs to which ticket.

Historical labels from the deleted `bb-jolt.md` map as: `JOLT-12`→#947,
`JOLT-13`→#944; git history has the full field reports.

## Open

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI `IVar` protocol missing `:getRawRoot` for `clojure.lang.Var`

**Area:** SCI vendored in jolt (`sci.impl.vars`), jolt's `clojure.lang.Var` shim

All shipped extensions fail to load on jolt with:
```
No implementation of method: :getRawRoot of protocol: #'sci.impl.vars/IVar
found for class: clojure.lang.Var
```
This is distinct from jolt#1006 (which was about `defrecord` over injected
host protocols — that one is closed). The `IVar` protocol in vendored SCI
0.13.53 expects `:getRawRoot` on `clojure.lang.Var`, but jolt's Var shim
doesn't implement it. This blocks mcp-adapter, lsp-adapter, review, and
clojure extensions on jolt.

**Workaround — retired on Jolt (2026-09-16).** Extension contexts no longer
run under SCI there: `kmet.app.extensions/create-loader` builds them on the
runtime's native loader (jolt#1039, loader.md §6.1), so the shipped
extensions share host protocols by reference instead of copying them into an
SCI env, and the `^:bb-only` gate on `test-shipped-extensions-load-from-src`
is gone (the clojure extension still stays bb-side, but for its own deps
closure — deps.edn excludes rewrite-clj, which bb bundles and Jolt does not).
The SCI shape itself is unchanged for any SCI-on-Jolt use. Note (2026-09-16):
upstream re-diagnoses this in PR jolt#1033 — `sci/copy-var*` never calls
`getRawRoot`; the failure is the missing embedder-side `IVar` extension, and a
host protocol copied into SCI as-is fails identically on JVM Clojure 1.12.5,
so no jolt-side fix unblocks that shape. #1033 makes the extension writable
(Var surface: `toSymbol`, `ns`/`sym`, `unbindRoot`, `set`, `fn`) but both
hosts then land on `Unable to resolve symbol` — the supported sharing shape is
the multimethod recipe in `test/chez/sci-functional-test.clj`.

**Update (2026-09-17) — SCI is back as a declared fallback.** The
manifest `:loader` key revived the SCI path on Jolt for `:sci`-only
extensions: `jolt/deps.edn` pins yogthos/sci @ babashka/sci#1093,
which makes the copied-host-protocol `defrecord` shape work — verified with
the exact repro above through `kmet.app.extensions`. The native loader stays
Jolt's preference (`:jolt` declared ⇒ native), and SCI's `*out*`/`*err*`
need binding there (`kmet.app.extensions/with-sci-io`). Re-confirmed
2026-09-18: the issue was reopened after #1033 merged (the reporter's
extension still failed) and upstream's repro on current main lands back on
SCI as the gap — `babashka/sci#1093` (still open, no jolt-side fix). The
`jolt/deps.edn` pin stays until it merges and a release carries it.
Re-checked 2026-09-19: both still open — #1031 now carries the `deferred`
label, and PR #1093's head is still `1295142f`, so the pin is unchanged.
Re-checked 2026-09-20: unchanged — #1031 still `deferred`, PR #1093 still open
at the same head `1295142f`, so the pin stays.

