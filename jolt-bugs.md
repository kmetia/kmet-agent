# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix is verified on the Jolt
build kmet runs — normally `main`, ahead of the tags, because the project
builds and tests against the installed build. The `:jolt/min-version` floor is
the last tagged release (`0.8.27`); older tags are below what the tree needs,
since the workarounds for the dropped tickets went with their fixes.

Jolt tickets were last checked on 2026-11-17 against the installed
`v0.8.27-237-gc721c3fe`. #1654's fix (`7dae6ed`, wake one waiter per monitor
release), #1653's loader cache (`9d2c5000`) and #1646's reader work
(`c3fd859`, PR #1693) are in, and all three entries are dropped; #1031 is
still open upstream. #1653's first load compiles (~7.9 s here), and the next
fresh loaders reuse the artifact (219–280 ms). The reader gap is smaller but
not gone: on this aarch64 host a warm `parse-string` ×5000 is ~13× bb,
`parse-string-all` ~10× and `tools.reader/read-string` ~11×, with
`clojure.core/read-string` at parity; #1713 tracks the remainder. A source
build wants a Jolt from `main`: the dropped fixes merged after the `v0.8.27`
tag.

`jolt-lang/http-client` `main` (`b45e46f`, unchanged at this check) is the
version pinned by the project and has no open tickets; its tests are green on
that commit (CI, 2026-11-14).

## Live issues

### [jolt#1713](https://github.com/jolt-lang/jolt/issues/1713) — rewrite-clj parsing is still ~10–14× slower than the JVM/bb after #1693

Filed 2026-11-17 against the installed `v0.8.27-237-gc721c3fe`. #1693 removed
the reader-layer costs that had a single cause, but the remaining per-call
overhead keeps `rewrite-clj.parser/parse-string` at ~13× bb, `parse-string-all`
at ~10× and `clojure.tools.reader/read-string` at ~11× on the test host
(warm; JVM ratios ~13×/12×/14×), while `clojure.core/read-string` stays at
parity. The parser guards (`test-submit-guard`, `test-self-contained`) still
pay it directly. Repro and ticket text:
`scripts/repros/rewrite_clj_parse_perf.clj` and
`scripts/repros/rewrite_clj_parse_perf.md`.

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open (last upstream update 2026-11-16), as is the upstream
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) itself (updated
2026-11-16; [yogthos/sci#2](https://github.com/yogthos/sci/pull/2) fixes its
CI, also open). It affects extensions that choose the SCI loader
backend on Jolt and copy or implement host protocols; shipped extensions
declaring `:jolt` use Jolt's native loader and are not blocked, which is why
`test-shipped-extensions-load-from-src` runs on both hosts. `jolt/deps.edn` and
`modules/app/deps.edn` pin yogthos/sci at `1295142` (the #1093 branch) for the
`:sci` fallback. Keep the pin until the change is released by Jolt; v0.8.27
vendors SCI at `32d62a5`, which the #1093 branch includes, so the pin still
applies. The installed `v0.8.27-237-gc721c3fe` still pins `vendor/sci` at
`32d62a5`.

