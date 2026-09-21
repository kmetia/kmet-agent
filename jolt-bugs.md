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

**`set!` on a compiler-flag var outside a load frame — fix in review as
[jolt PR #1079](https://github.com/jolt-lang/jolt/pull/1079) (filed
2026-09-21).** The number this finding was earlier recorded under, #1074,
went to the Windows runtime seams issue below — no set! ticket existed until
this one. The PR runs every user entry — `-m`/`run -m`, `-X`/`-T`, code
tasks, and a built binary's launcher — under clojure.main's compiler-flag
frame, and brackets `jolt.loader`'s source eval per file like the host
loader, so the `load-extension!` workaround below is removable once it
merges.
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
with a runtime `(require 'dep)` prints on. Workaround:
`kmet.app.extensions/load-extension!` wraps the load in a no-op
`(binding [*warn-on-reflection* *warn-on-reflection*])` — the dep's `set!`
writes the thread frame and the pop leaves the root untouched. Remove the
binding when `-m`/loader evaluation carries clojure.main's entry bindings.
Re-verified 2026-09-21 on the installed v0.8.10-34-gf2ee1dd7: `jolt -m app`
and the loader repro both still throw the IllegalStateException, `-e` still
works. The #1079 branch fixes them (both repros, the task/`-X` entries and a
built binary verified in it); the installed build predates it.

**Upstream status:** three open items — the SCI IVar gap, filed as
[jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) (`deferred`),
re-diagnosed upstream in jolt PR #1033 — with its fix at
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) (re-checked
2026-09-21: open, head `1295142f`, unchanged), so the `jolt/deps.edn` SCI pin
stays — the `set!`/entry-binding gap, fix in review as
[jolt PR #1079](https://github.com/jolt-lang/jolt/pull/1079) (filed
2026-09-21; the `load-extension!` binding workaround is removable once it
merges) — and the Windows runtime seams, filed 2026-09-21 as
[jolt#1074](https://github.com/jolt-lang/jolt/issues/1074). Every other
ticket this file tracked is closed.

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
Re-checked 2026-09-21: unchanged — same state (the issue's last activity is
2026-09-18, the PR's 2026-09-16), so the pin stays.

### [jolt#1074](https://github.com/jolt-lang/jolt/issues/1074) — Windows runtime seams: atomic `spit`, `path.separator`, and program resolution

**Area:** `host/chez/java/io.ss` (`jolt-spit`, the `File` statics),
`host/chez/java/host-static-methods.ss` (`path.separator`/`file.separator`),
`host/chez/java/process.ss` (`proc-on-path?`, `proc-program-resolvable?`,
`proc-path-join`).

All three verified on the official v0.8.10 Windows build, filed 2026-09-21,
and all three are still on `main` at the re-check (`f2ee1dd7`, the PR #1075
merge, 2026-09-21). Jolt's CI is `ubuntu-latest`-only, so no gate covers
them: a Jolt/Windows process cannot overwrite an existing file or spawn
anything but a `/`-rooted child. kmet's curl transport (temp
config + `spit` + spawn of `curl`) hits all three in one request.

**1. `spit` over an existing file throws.** `jolt-spit` (atomic since
8ff1644a) writes `<target>.spit-tmp-<ms>-<n>` and renames it over the
target. Chez's `rename-file` on Windows refuses an existing destination
where POSIX `rename(2)` replaces, so the second spit to any path fails:

```
$ jolt -e "(do (spit \"t.txt\" \"a\") (spit \"t.txt\" \"b\"))"
Unhandled exception (IOException): rename-file: cannot rename
  "…\t.txt.spit-tmp-108-2" to "…\t.txt": file exists
```

A target freshly created by `File/createTempFile` fails on the *first*
spit. `spit :append true` and `java.io.FileOutputStream` work (they open
the target in place); `io/writer` does not — jolt's file writer spits at
close. Fix: on Windows delete the target before the rename, or
`MoveFileEx(..., MOVEFILE_REPLACE_EXISTING)`. The same replace-rename
pattern in `loader.ss` (`rename-file tmp-scm scm` / `tmp-so so` in the
AOT/publish paths) fails identically whenever the artifact already exists.

**2. `path.separator` answers `":"` on Windows.** `host-static-methods.ss`
hardcodes `"path.separator" ":"` and `"file.separator" "/"`, and the
`File` statics (`io.ss`: `separatorChar`/`separator`/`pathSeparator`/
`pathSeparatorChar`) are POSIX values too. `babashka.fs` reads them:

```
$ jolt -e '(require (quote [babashka.fs :as fs])) (println (pr-str (fs/split-paths "C:/a;C:/b")))'
[#object[java.nio.file.Path "C"] #object[java.nio.file.Path "/a;C"] #object[java.nio.file.Path "/b"]]
$ jolt -e '(require (quote [babashka.fs :as fs])) (println (pr-str (fs/which "curl")))'
nil           ;; curl.exe is on PATH
```

`fs/exec-paths`/`split-paths` return garbage and `fs/which` never finds
anything — which is also why `babashka.process`'s Windows resolver throws
`Cannot resolve program: …` before a spawn is even attempted. Fix: answer
per `sa-os-family`, with `path.separator*` `";"` on Windows.
`File/separator*` can stay `"/"` if the tree relies on it (Windows accepts
it); the PATH separator cannot.

**3. `ProcessBuilder` cannot start a Windows program.** `proc-on-path?`
splits PATH with `(str-literal-split path ":")`,
`proc-program-resolvable?` treats only a leading `/` as absolute, and
`proc-path-join` only knows `/`:

```
$ jolt -e '(require (quote [babashka.process :as proc])) (proc/process ["curl" "--version"])'
Unhandled exception: Cannot resolve program: curl
$ jolt -e '(require (quote [babashka.process :as proc])) (proc/process ["C:/Windows/System32/curl.exe" "--version"])'
Unhandled exception (IOException): Cannot run program "C:/Windows/System32/curl.exe": error=2, No such file or directory
$ jolt -e '(require (quote [babashka.process :as proc])) (proc/process ["/Windows/System32/curl.exe" "--version"])'
… exit 0
```

Bare names and drive-letter paths are rejected; only `/`-rooted (current
drive) and slash-bearing relative programs pass. Fix: split `;` (and try
PATHEXT) on Windows, accept `X:/` and `X:\` as absolute, and let
`proc-path-join` keep the separator style it is given.

**kmet workaround:** `kmet.libs.http/curl-available?` splits PATH itself
(`path-dirs`) and calls `babashka.fs/which` with explicit `:paths`; once
finding 2 is fixed that collapses back to `(fs/which "curl")`. Findings 1
and 3 have no kmet workaround — the jolt fixes are what let the curl
transport (or any child process) run on Windows.

### More Windows seams: `fs/glob` separator patterns and `ProcessHandle` (unfiled)

**Area:** the vendored `babashka.fs` glob primitive,
`host/chez/java/process.ss`. Both verified on the official v0.8.10 Windows
build (2026-09-21); both are Windows-only.

**4. `fs/glob` patterns that spell a `/` match nothing.** The `**` in the
vendored glob never crosses the platform separator on Windows, so `*`,
`*.clj` and `**/*.clj` all return `()`, while separator-free `**` and
`**.clj` work:

```
$ jolt -e '(require (quote [babashka.fs :as fs])) (println (count (fs/glob "src" "**/*.clj")) (count (fs/glob "src" "**.clj")))'
0 152
```

bb matches the same patterns normally. Fix: match the pattern's `/` against
both separators. kmet workaround: `kmet.tasks.changed/dir-clj-files` uses
one `**.{clj,cljc,jolt}` pattern (verified to select the same files as the
six old globs on bb), and `kmet.tasks.format/source-paths` normalizes
before its exclusion regex. Everything else enumerating with
separator-bearing patterns — `kmet.tasks.lint`'s `*.{…}` + `**/*.{…}` pair,
`kmet.tasks.clean`'s `extensions/*/target` — still silently sees an empty
tree on Jolt/Windows, so a Jolt/Windows `lint`/`clean` needs this fixed (or
the patterns rewritten) before it can be trusted.

**5. `java.lang.ProcessHandle` is absent.**

```
$ jolt -e '(println (.pid (java.lang.ProcessHandle/current)))'
Unhandled exception (IllegalArgumentException): No dependency provides java.lang.ProcessHandle — a concrete implementation of the JDK classes must be provided.
```

`kmet.libs.terminal/capture-log-path` builds `<prefix>-<timestamp>-<pid>.log`
when `KMET_TUI_WRITE_LOG` / `KMET_TUI_INPUT_LOG` points at a directory, so
that path crashes the app at namespace load on Jolt/Windows (pointing the
env var at a file works — the timestamped name is only built for a
directory). Fix: a `ProcessHandle` shim in `process.ss` (jolt-port.md listed
one at `process.ss:1009`; it is not reachable on this build), kmet
workaround: avoid the pid in log names on Windows.

