# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix is verified on the Jolt
build kmet runs — normally `main`, ahead of the tags, because the project
builds and tests against the installed build. The `:jolt/min-version` floor is
the last tagged release (`0.8.27`); older tags are below what the tree needs,
since the workarounds for the dropped tickets went with their fixes.

Jolt tickets were last checked on 2026-11-13 against the installed
`v0.8.27-36-g829f1710`. Every earlier ticket this project filed except #1031 is
fixed and its entry dropped: #1433 (PR #1435, follow-up PR #1437), #1453/#1454
(PR #1472), #1466/#1469 (PR #1478), #1467/#1468 (PR #1477), #1548 (PR #1550),
#1611 (PR #1626, follow-up #1636) and #1628 (PR #1634). The `spit` in-place
write (`83fe128e`, follow-up `3be07637`) is also closed: mode, symlink and
hard-link behaviour are verified on the installed build.

`jolt-lang/http-client` `main` (`b45e46f`, unchanged at this check) is the
version pinned by the project and has no open tickets; its HTTP, crypto and MCP
SSE suites pass on the installed build. The #46 entry is dropped: PR #47
(v0.1.8, `8405dc0`) renamed the shadowing TLS constant, and requiring
`jolt.http.tls` is silent on the pinned commit.

## Live issues

### [jolt#1654](https://github.com/jolt-lang/jolt/issues/1654) — lock + blocking file I/O from many futures is ~7–11× slower than the JVM

Filed 2026-11-13 against the installed `v0.8.27-52-g7ed36439`. Four hundred
futures taking a `ReentrantLock` and appending one line through
`io/output-stream :append true` take 196–320 ms on Jolt vs 26–30 ms on the
JVM and 16–17 ms on bb. Lock-only and no-lock appends are only a few times
slower; the combination is the outlier. kmet's session journal uses the same
pattern, and `test-session-concurrent-appends` is 331–541 ms vs 43 ms on bb.
Repro and ticket text: `scripts/repros/jolt_lock_io_perf.clj` and
`scripts/repros/jolt_lock_io_perf.md`.

### [jolt#1653](https://github.com/jolt-lang/jolt/issues/1653) — native loader recompiles context-private source namespaces every load

Filed 2026-11-13 against the installed `v0.8.27-52-g7ed36439`. The native
loader reads and compiles private source namespaces on every load; a host
loading shipped extensions from `src/` pays ~3.7–4.6 s vs ~0.21 s on bb.
The eviction-repair reload of 91 `kmet.extensions.*` namespaces costs
~18.6 s (45 source namespaces: ~5.7 s), making
`test-shipped-extensions-load-from-src` the largest single full-suite
outlier. The issue asks for evicted-name reporting/precise restore and a
loader-scoped artifact cache (or precompiled roots). Repro and ticket text:
`scripts/repros/jolt_native_loader_perf.clj` and
`scripts/repros/jolt_native_loader_perf.md`.

### [jolt#1646](https://github.com/jolt-lang/jolt/issues/1646) — rewrite-clj parsing ~17–27× slower than on the JVM/bb

Filed 2026-11-13 against the installed `v0.8.27-52-g7ed36439`.
`rewrite-clj.parser/parse-string` and `parse-string-all` are ~17–27× slower
than on the JVM or babashka; `clojure.tools.reader/read-string` is ~13–18×
slower, while `clojure.core/read-string` is at parity. The parser guards
(`test-submit-guard`, `test-self-contained`) pay it directly. Repro and
ticket text: `scripts/repros/rewrite_clj_parse_perf.clj` and
`scripts/repros/rewrite_clj_parse_perf.md`.

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open (last upstream update 2026-10-09), as is the upstream
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) itself (no
update since 2026-09-16). It affects extensions that choose the SCI loader
backend on Jolt and copy or implement host protocols; shipped extensions
declaring `:jolt` use Jolt's native loader and are not blocked, which is why
`test-shipped-extensions-load-from-src` runs on both hosts. `jolt/deps.edn` and
`modules/app/deps.edn` pin yogthos/sci at `1295142` (the #1093 branch) for the
`:sci` fallback. Keep the pin until the change is released by Jolt; v0.8.27
vendors SCI at `32d62a5`, which the #1093 branch includes, so the pin still
applies.

