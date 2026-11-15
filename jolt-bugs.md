# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix is verified on the Jolt
build kmet runs — normally `main`, ahead of the tags, because the project
builds and tests against the installed build. The `:jolt/min-version` floor is
the last tagged release (`0.8.27`); older tags are below what the tree needs,
since the workarounds for the dropped tickets went with their fixes.

Jolt tickets were last checked on 2026-11-15 against the installed
`v0.8.27-140-g9cf28bdb`. #1654's fix (`7dae6ed`, wake one waiter per monitor
release) is in and the entry is dropped; #1653 and #1646 still reproduce, and
#1031 is still open upstream. A source build wants a Jolt from `main`: the
latest dropped fix merged after the `v0.8.27` tag.

`jolt-lang/http-client` `main` (`b45e46f`, unchanged at this check) is the
version pinned by the project and has no open tickets; its HTTP, crypto and MCP
SSE suites pass on the installed build.

## Live issues

### [jolt#1653](https://github.com/jolt-lang/jolt/issues/1653) — native loader recompiles context-private source namespaces every load

Filed 2026-11-13 against the installed `v0.8.27-52-g7ed36439`. The native
loader reads and compiles private source namespaces on every load; a host
loading shipped extensions from `src/` pays ~3.7–4.6 s vs ~0.21 s on bb.
The eviction-repair reload of 91 `kmet.extensions.*` namespaces costs
~18.6 s (45 source namespaces: ~5.7 s), making
`test-shipped-extensions-load-from-src` the largest single full-suite
outlier. The issue asks for evicted-name reporting/precise restore and a
loader-scoped artifact cache (or precompiled roots). Re-checked on
`v0.8.27-140-g9cf28bdb`: three fresh loaders still take ~7.0 s each. Repro and
ticket text: `scripts/repros/jolt_native_loader_perf.clj` and
`scripts/repros/jolt_native_loader_perf.md`.

### [jolt#1646](https://github.com/jolt-lang/jolt/issues/1646) — rewrite-clj parsing ~17–27× slower than on the JVM/bb

Filed 2026-11-13 against the installed `v0.8.27-52-g7ed36439`.
`rewrite-clj.parser/parse-string` and `parse-string-all` are ~17–27× slower
than on the JVM or babashka; `clojure.tools.reader/read-string` is ~13–18×
slower, while `clojure.core/read-string` is at parity. The parser guards
(`test-submit-guard`, `test-self-contained`) pay it directly. Re-checked on
`v0.8.27-140-g9cf28bdb`: still ~17× bb for `parse-string`, ~16× for
`parse-string-all` and `tools.reader/read-string`; the build's changelog
records a partial 17–22% improvement, not a fix. Repro and ticket text:
`scripts/repros/rewrite_clj_parse_perf.clj` and
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
applies. The installed `v0.8.27-140-g9cf28bdb` still pins `vendor/sci` at
`32d62a5`.

