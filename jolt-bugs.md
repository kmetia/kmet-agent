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
edit and both pins move to the fixed revisions. The Windows smoke ran
2026-09-22 on the installed v0.8.10-71-gbbb7d34d build (the DLLs in `libs/`
beside `jolt.exe`): RSA sign/verify (libcrypto), `SSLContext/getInstance
"TLS"` (libssl) and a `java.util.zip` GZIP round trip (libz) all load and
work. Ticket reports live here only: `jolt-port.md` is the open-work plan
and `jolt-tui.md` the TUI adapter deep-dive — neither carries them.

**Windows leftovers closed by jolt PR #1090 (merged 2026-09-21).** #1086
(`fs/glob` patterns containing a separator matched nothing on Windows —
`**/*.clj` returned `()`) and #1087 (`java.lang.ProcessHandle` absent on
every platform) were both closed by that PR, verified 2026-09-22 on
v0.8.10-71-gbbb7d34d. The kmet checks this unblocked: `jolt clean --dry-run`
lists `extensions/*/target`, `kmet.tasks.lint`'s `*.{…}` + `**/*.{…}` pair
and `kmet.tasks.format`'s globs enumerate the Jolt/Windows tree, and
`KMET_TUI_WRITE_LOG=<dir> jolt -e "(require 'kmet.libs.terminal)"` loads
(the pid name builds). `kmet.tasks.changed/dir-clj-files` drops the
jolt-broken rationale — the single `**.{…}` pattern is now simply the
shorter spelling. One residual divergence, no kmet impact: on Windows bb's
`**` matches dot-prefixed entries (`Files/isHidden` reads the attribute)
while jolt's treats a leading dot as hidden; no source root carries one.
The #1074 workaround is retired: `kmet.libs.http/curl-available?` is
`(fs/which "curl")` again (jolt's `fs/which`/`exec-paths` answer the
`;`-separated Windows PATH correctly, 37 entries, same as bb).

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

**`jl/classpath` syntax quotes (jolt PR #1075, merged 2026-09-21; in the
installed v0.8.10-34-gf2ee1dd7).**
`eval-namespace-source` bulk-read every form before evaluating any, so a
syntax quote in a source-loaded macro resolved in the CALLER's namespace
(`user/v`, `No such namespace: bb`) — rewrite-clj's
`custom-zipper.switchable` macros poisoned its generated fns, and the
clojure extension's `clojure_edit` / `clojure_edit_replace_sexp` failed on
Jolt with `<ns>/custom-zipper?`. The loader now reads form by form, after
the file's `ns` form has run, in the file's own namespace and aliases
(loaderconf case 32). The clojure extension loads and all three tools work
on Jolt; `test-shipped-extensions-load-from-src` no longer excludes it,
and a Maven `spec.alpha` loads through the loader too — the circular
spec.alpha/spec.gen.alpha require that pushed kmet off `cljfmt.config`. No
kmet workaround — the loader constructor was the broken piece.

**`java.text.Normalizer` perf (#1066, closed by jolt PR #1067).**
`normalize` now answers ASCII runs from a quick check instead of delegating
every string to Chez, and `isNormalized` checks the Unicode quick-check
tables instead of normalizing and comparing: NFKC over 1.09M ASCII chars
went 155.52 ms → **1.9 ms** (bb 2.0 ms, same box), and `isNormalized` on a
decomposed 200k-char string ~10 ms → **0.43 ms**. Verified on `9786b7fa`
(main 2026-09-21; 16 commits past the merge `0e2bcc2`, 0 behind). No kmet
workaround waited on it — the `edit-diff` `[\x00-\x7F]*` guard stays, it
skips NFKC on its own merits. The Unicode-16/17 table skew the issue also
recorded (U+A7F1 → `"S"` on jolt; the JDK's older tables leave it alone) is
not part of the fix and remains upstream's policy call.

**`set!` on a compiler-flag var outside a load frame — fixed (jolt PR
#1079, merged `70133c55`; the nested-load follow-up
[jolt PR #1085](https://github.com/jolt-lang/jolt/pull/1085), merged
`7223b36d`).** The number this finding was earlier recorded under, #1074,
went to the Windows runtime seams issue (jolt#1074, since closed) — no set!
ticket existed until this one. The fix runs every user entry — `-m`/`run -m`,
`-X`/`-T`, code tasks, and a built binary's launcher — under clojure.main's
compiler-flag frame, and brackets `jolt.loader`'s source eval per file like
the host loader.
`clojure.main` wraps every entry — repl, `-e`, `-m`, a script — in
`with-bindings` for the vars sources commonly `set!` (`*warn-on-reflection*`
and friends; main.clj:78-83), so a dependency loaded at runtime from `-main`
still has a frame. Jolt binds them for `-e` and around its compiled loads
(0012f725), but not for `-m`/`run -m`, so code running *after* the load sees
the root-only state:

```
$ cat src/app.clj
(ns app)
(defn -main [& _]
  (set! *warn-on-reflection* true)
  (println :ok))
$ jolt -m app          # same with: jolt run -m app
Unhandled exception (IllegalStateException): Can't change/establish root
binding of: *warn-on-reflection* with set
```

This is not about `Var.set` semantics — the JVM refuses a root-only `set!`
identically (`(def ^:dynamic *d* 1) (set! *d* 2)` throws on both hosts), and
a top-level `(set! *warn-on-reflection* true)` works on jolt because the
load frame binds it. The gap is the missing entry binding, and it reaches
the loader too: kmet's extension deps load through `jolt.loader` from
`-main`, where a source dep opening with that set! throws (a plain `require`
from `-main` does not — jolt's load path binds the flags itself, 0012f725;
the loader's source-eval path does not).

Minimal loader repro: `dep.clj` = `(ns dep) (set! *warn-on-reflection* true)
(def v 42)`; `app2.clj` = `(ns app2 (:require [jolt.loader :as jl])) (defn
-main [& _] (jl/load (jl/classpath ["src"]) {:kind :ns :name "dep"}))` —
`jolt -m app2` throws the same exception, while the JVM `clojure.main -m`
with a runtime `(require 'dep)` prints on. The workaround was
`kmet.app.extensions/load-extension!` wrapping the load in a no-op
`(binding [*warn-on-reflection* *warn-on-reflection*])` — the dep's `set!`
wrote the thread frame and the pop left the root untouched. It is removed
alongside this edit: v0.8.10-71-gbbb7d34d passes both repros (`jolt -m
app`, the loader dep) and a single-file extension whose source opens with
`(set! *warn-on-reflection* true)` loads through `load-extension!` on both
loader kinds (:jolt and :sci) — re-verified 2026-09-22.

**Upstream status:** four filed open items — the SCI IVar gap
[jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) (`deferred`), with
its fix at [babashka/sci#1093](https://github.com/babashka/sci/pull/1093)
(re-checked 2026-09-22: open, head `1295142f`, unchanged), so the
`jolt/deps.edn` SCI pin stays; and the three Windows runtime gaps found while
running the smoke, filed 2026-09-22 as
[jolt#1107](https://github.com/jolt-lang/jolt/issues/1107) (sockets),
[jolt#1108](https://github.com/jolt-lang/jolt/issues/1108) (process spawning)
and [jolt#1109](https://github.com/jolt-lang/jolt/issues/1109)
(`PushbackReader.close`). The Windows runtime seams (#1074/#1077), the
`set!`/entry-binding gap (#1079/#1085), the glob separator (#1086) and
ProcessHandle (#1087) are all fixed upstream, with their kmet workarounds
removed and the Windows smoke run. Every other ticket this file tracked is
closed.

**Workarounds are retired, not tracked here.** Every workaround block this
file carried was the removal checklist for one ticket; each landed with its
fix, so none remain. Source comments describe the local *why* without ticket
numbers; the closure notes above carry the ticket mapping for the removed
code.

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
Re-checked 2026-09-22: unchanged — same state (the issue's last activity is
2026-09-18, the PR's 2026-09-16), so the pin stays.

### Windows runtime gaps — filed as jolt#1107/#1108/#1109

**Area:** `stdlib/jolt/socket.clj` + `stdlib/jolt/io_poller.clj`,
`host/chez/java/process.ss`, `host/chez/java/host-static-classes.ss`.

All three re-verified 2026-09-22 on the installed v0.8.10-71-gbbb7d34d
Windows build, and filed the same day. None has a kmet workaround, and the
first two are why a Jolt/Windows host cannot reach the network or spawn a
program at all — whatever the proxy environment says: the failures sit below
the transport, before any request is routed.

**4. Windows sockets —
[jolt#1107](https://github.com/jolt-lang/jolt/issues/1107). The `java.net`
socket layer never initializes Winsock and polls POSIX only.** `jolt.socket`
is documented as "POSIX socket support" and calls no
`WSAStartup`; only `jolt.nrepl` (`ensure-winsock!`, nrepl.clj:84) and
`jolt.mvn-http` (mvn_http.clj:112-121) initialize Winsock, which is why
Maven resolution and nREPL work on Windows while everything else fails:

```
$ jolt -e '(let [s (java.net.Socket.)] ...)'
Unhandled exception (IOException): socket() failed
$ jolt -e '(println (java.net.InetAddress/getByName "localhost"))'
Unhandled exception (IOException): unknown host: localhost
$ jolt -e '(println (java.net.InetAddress/getLocalHost))'
Unhandled exception (RuntimeException): foreign-procedure: no entry for "getifaddrs"
```

A manual `WSAStartup` proves the split: name resolution starts working, and
the next POSIX assumption surfaces — `guard-fd!` (socket.clj:136) →
`io_poller/nonblock!` (io_poller.clj:94) calls `fcntl F_GETFL/F_SETFL`:

```
$ jolt -e '(…WSAStartup… (java.net.InetAddress/getByName "localhost"))'
#object[java.net.Inet4Address "localhost/127.0.0.1"]
Unhandled exception (RuntimeException): foreign-procedure: no entry for "fcntl"
```

`jolt-lang/http-client`'s own transport (`jolt.http.net`, its own POSIX
`getaddrinfo`) fails the same way — no `WSAStartup`, so
`babashka.http-client` cannot reach any host:

```
$ jolt -M -e '(require (quote [babashka.http-client :as http])) (http/get "https://example.com")'
Unhandled exception (UnknownHostException): example.com
```

Fix: initialize Winsock once in the runtime (ws2_32 is linked by
`build.ss:486`; `jolt.nrepl`'s explicit `load-library "ws2_32.dll"` is the
shape) and add a Windows branch for the fd helpers — `ioctlsocket(FIONBIO)`
for `nonblock!`, `select`/`WSAPoll` in place of kqueue/epoll. Until then
any `java.net` socket use (and every kmet transport) is Windows-dead.

**5. Windows process spawning —
[jolt#1108](https://github.com/jolt-lang/jolt/issues/1108). The spawn path
hands `cmd.exe` a POSIX shell string.** Where the fd-level spawn's FFI
surface is missing — Windows machine types
(process.ss:20-21) — `jolt.process` falls back to Chez's
`open-process-ports` and passes it `proc-build-shell-command`'s string
(process.ss:291-304), which opens with `exec ` and uses `cd … &&`/`env -i`
(the `/bin/sh` shape). `cmd.exe` rejects the first token, so every spawn
fails — exit 0, error on the child's stderr, no program run:

```
$ jolt -M -e '(require (quote [babashka.process :as p])) (p/shell {:out :string :err :string :continue true} "git" "--version")'
{:exit 0, :out "", :err "'exec' is not recognized as an internal or external command,\r\noperable program or batch file.\r\n"}
```

`ProcessBuilder` builds through the same string, so `babashka.process`,
`clojure.java.shell` and every kmet subprocess are unusable on
Jolt/Windows: the bash tool, `jolt lint` (clj-kondo), the `:curl` HTTP
transport, `jolt test-ext`'s subprocess tests. Fix: a Windows spawn path —
`CreateProcess` directly, or a `cmd /c`-compatible command string with the
POSIX `exec`/`env` shorthands dropped.

**6. `java.io.PushbackReader.close` —
[jolt#1109](https://github.com/jolt-lang/jolt/issues/1109). The method is a
no-op.** The method table registers `(cons "close" (lambda (self) jolt-nil))`
(host-static-classes.ss:1138), so closing the PushbackReader never closes
the reader it wraps — the JVM's `PushbackReader.close` closes the
underlying stream, and `alias-host-methods!` gives
`clojure.lang.LineNumberingPushbackReader` the same no-op. Invisible on
POSIX; on Windows the wrapped file stays open until process exit and a
delete only marks it pending (the name stays in its directory):

```
$ # with-open over (java.io.PushbackReader. (io/reader "src/dep.clj")), then:
$ jolt -e '(… (fs/delete "src/dep.clj") (println (mapv str (fs/list-dir "src"))))'
[src/dep.clj]
```

Consequence in kmet: `jolt.loader`'s source eval (loader.clj:1268) leaves
every loaded source file open on Jolt/Windows, so an extension directory
cannot be deleted while it is loaded *or after*
`unload-all-extensions!` — several `kmet.app.test-extensions` tests fail
their `fs/delete-tree` teardown there — and
`kmet.tasks.changed/read-ns-form` leaks one handle per scanned file. Fix:
`close` should close `(vector-ref (jhost-state self) 0)`.

