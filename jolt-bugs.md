# jolt-bugs — reported upstream issues

A ticket remains live here until its fix reaches a tagged release. Status
checked on 2026-10-10 against Jolt `v0.8.20` (`143c371`, still the latest tag;
the #1292 and #1294 fixes merged into `main` after it) and
`jolt-lang/http-client` `v0.1.2` (`00455b8`), the versions now pinned by the
project.

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open. It affects extensions that choose the SCI loader backend on Jolt
and copy or implement host protocols; shipped extensions using Jolt's native
loader are not blocked. `jolt/deps.edn` pins the SCI revision from
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) for the `:sci`
fallback. Keep the pin until the change is released by Jolt.

### [jolt#1292](https://github.com/jolt-lang/jolt/issues/1292) — make the GC stall report opt-in

Closed 2026-10-09 by [PR #1293](https://github.com/jolt-lang/jolt/pull/1293)
(merge `123157fd`, on `main` after the pinned `v0.8.20`), which keeps the
entry live until a tag carries it. The report — stderr, once per collection
that waits two seconds for a thread parked in a foreign call that is not
`:blocking` (`host/chez/rt.ss`, installed by `sa-gc-install-stall-watch!`) —
interleaved with a full-screen TUI's frames, and a program could not
intercept it: the reporting thread is the rendezvous waiter, so a
thread-level `(parameterize ((current-error-port …)))` did not reach it, and
on Windows the console error port is a console HANDLE rather than the CRT
stderr fd. The watch still installs and the collection still stalls; only
the report needs `JOLT_GC_STALL=1` for the two-second threshold, or
`JOLT_GC_STALL=<seconds>` for another. The runtime's other warnings (provider
claim, duplicate native symbol, data-reader load failure, AOT worker) need
`JOLT_WARNINGS=1`; the entropy fallback still warns unconditionally.
`jolt.ffi/on-gc-stall` hands the report to a callback instead, for a host
whose stderr is not jolt's — it runs with every other thread stopped, so it
may write to a file but must not wait. The companion
[PR #1294](https://github.com/jolt-lang/jolt/pull/1294) (merge `83db9530`,
also `main`-only) patches Chez so a collect-safe `:blocking` call no longer
takes the tc mutex — the cost behind the stall itself.

No kmet-side workaround to remove: the `target/jolt-test-ext.log` sighting
predates the pinned `v0.8.20` and was the Windows pipe-close shape fixed
there (#1283), and kmet's own Windows terminal FFI already marks every
parking call `:blocking`. Bump the floor when the next tag lands — the TUI
then stays quiet by default, and `on-gc-stall` is the seam if a stall should
still reach `kmet.debug`.

### [jolt#1299](https://github.com/jolt-lang/jolt/issues/1299) — `instance?` against modeled ref types costs 1–2 µs

Measured 2026-10-10 on Jolt `v0.8.20-13-gb0b3355b` (Termux/aarch64). A type
check against a native class is fast — `(instance? String "x")` 0.04 µs,
`(instance? Object x)` 0.14 — but the modeled Clojure core types go through
the synthesized class graph and cost ~30–45x more: `clojure.lang.Atom`
**1.06 µs**, `clojure.lang.IRef` **1.84**, `clojure.lang.IDeref` **1.82**
(bb's SCI: `IRef` ~0.16 µs, `IDeref` ~0.49). The work happens in
`instance-check`'s registry arms → `instance-check-base` / `case-string`
(`host/chez/java/records-interop.ss`), which walks the hierarchy as string
comparisons per call; the `Atom`/`IRef` arm order is part of the cost.

kmet hit this on the reakt hot path: `trackable-ref?` is
`(or (instance? clojure.lang.IRef ref) (satisfies? RXRef ref))`, and
`tracked-deref` calls it twice per tracked read inside a tracking scope, so
a plain-atom read costs 4.06 µs on jolt against 1.36 on bb (perf.md §14.2).
Filed 2026-10-10 with the reduced repro, the kind-key control group
(vector/seq/map answers 0.25–0.29 µs against the atom's 1.06) and a
suggested fix: `class-fast-name` returns `#f` for atom/var/ref/delay, so
`instance-kind-key` has no key — every check re-walks the arm chain and
the per-site inline cache is skipped. The kmet-side workaround has landed
(classify once per deref and keep IRef first: both checks run on a miss,
so RXRef-first is actually slower for the common atom; perf.md §14.4
item 3), bringing a plain-atom read in a tracking scope from 4.9 to
2.3 µs. An upstream fast path for the core-type arms would fix it for
every library.

Repro: `jolt scripts/kmet_reakt_bench.clj` — the four `instance?` rows at
the top of the sweep.

## Resolved in the pinned releases

These issues no longer need kmet-side workarounds:

- [jolt#1281](https://github.com/jolt-lang/jolt/issues/1281), Windows 8.3
  short-name canonicalization, and
  [jolt#1283](https://github.com/jolt-lang/jolt/issues/1283), closing a
  Windows process pipe with a read in flight, are fixed in Jolt `v0.8.20`
  ([PR #1287](https://github.com/jolt-lang/jolt/pull/1287)).
- [jolt#1284](https://github.com/jolt-lang/jolt/issues/1284), concurrent
  protocol dispatch in `--opt` builds, is fixed in Jolt `v0.8.20`
  ([PR #1286](https://github.com/jolt-lang/jolt/pull/1286)).
- [jolt#1282](https://github.com/jolt-lang/jolt/issues/1282), HTTP request
  timeout exception parity, is fixed in `jolt-lang/http-client` `v0.1.2`
  ([PR #36](https://github.com/jolt-lang/http-client/pull/36)). Its existing
  v0.0.14 behavior also keeps request deadlines from
  cutting off an SSE body after response headers arrive.

Regression coverage includes runtime-cwd canonicalization
(`kmet.app.test-extensions/test-exec-runs-in-the-runtime-cwd`), HTTP request
and SSE idle timeouts (`kmet.ai.test-llm/test-llm-request-timeout-follows-idle`,
`test-llm-body-stall-idle-timeout-completes`), and the detached-process pipe
drain (`kmet.app.test-tools/test-tool-bash-background-pipe-closed`).
