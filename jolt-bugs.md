# jolt-bugs — live upstream workarounds

Only live kmet-side workarounds for Jolt upstream issues are listed here,
with exactly what to remove or update. A ticket is done only when its fix
reaches a tagged release: closed upstream but unreleased still counts as
live, because the workaround protects the declared floor (the latest tagged
release). Status checked against GitHub and the re-fetched `origin/main` on
2026-10-05, with the `jolt v0.8.17` build installed (latest tag: v0.8.17;
`origin/main` at `bb30dc89`).

## Live workarounds

Each entry below records the kmet-side workaround (if any) and exactly what
to remove or update, whether the upstream ticket is still open or its fix is
awaiting a release.

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open (labeled `deferred`; last upstream activity 2026-09-18). It affects
extensions that choose the SCI loader backend on Jolt and
copy/implement host protocols. Shipped extensions using Jolt's native loader
are not blocked. The fix is proposed in
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093), still open at
head `1295142f`. `jolt/deps.edn` pins that SCI revision for the `:sci`
fallback; keep the pin until the change is released by Jolt.

### [jolt-lang/http-client#32](https://github.com/jolt-lang/http-client/issues/32) — stale `CompletableFuture` claim

**Closed upstream** by http-client PR #34 (`d99af98`, 2026-10-02): the
stale claim is gone and the library's `:jolt/min-version` is 0.8.16.
Jolt v0.8.16 was tagged 2026-10-03 and carries
`java.util.concurrent.CompletableFuture` (jolt-lang/jolt@9dad0359), so
both `:jolt/min-version` declarations are now 0.8.16 and the revision's
floor no longer blocks. http-client v0.1.0 was tagged 2026-10-04 at
`d99af98`, exactly the pinned commit, so the dropped-claim warning is gone
and the pin can move to the tag.

The floor bump was load-bearing, because the claim was what autoloaded the
shim for a bare `CompletableFuture` reference — verified on v0.8.15 with
the class removed: a bare `CompletableFuture/completedFuture` fails with
"No dependency provides java.util.concurrent.CompletableFuture", while
babashka.http-client's sync/`:async`/websocket paths still work (they
autoload the shim through `HttpClient`/`HttpRequest`). The release is here:
http-client `v0.1.0` carries `d99af98`, so move the
`io.github.jolt-lang/http-client` pin to the tag and drop the note in
`deps.edn` and `jolt-port.md`.

### [jolt-lang/time#19](https://github.com/jolt-lang/time/issues/19) — dead `cljs.java-time` on the classpath

Open upstream (filed 2026-10-05). `io.github.jolt-lang/time` → `juxt/tick`
→ `com.widdindustries/cljc.java-time` → `com.widdindustries/cljs.java-time`
(a hard POM dependency): an 11.75 MB jar that is 11.74 MB of ClojureScript
externs (`cljsjs/js-joda/common/js-joda-dup.ext.js`), unusable on Jolt.

kmet's workaround is the `:exclusions [com.widdindustries/cljs.java-time]`
entry on the `io.github.jolt-lang/time` pin in `deps.edn`. Remove it — with
its comment — when that pin moves to a revision excluding the artifact
upstream. Measured effect: −0.76 MB on the optimized default executable, and
the full 11.7 MB off resolution/download.

### [jolt#1245](https://github.com/jolt-lang/jolt/issues/1245) — ClojureScript externs baked into built binaries

Open upstream (filed 2026-10-05). `bld-dep-resources` (`host/chez/build.ss`)
skips `.class` and `.cljs` but bakes `*.ext.js` externs, so any app with a
`cljsjs`-style dependency carries compiler-only bytes; before the time#19
exclusion, 12.97 MB of kmet's `flat.ss` was the js-joda externs.

The same `deps.edn` exclusion is kmet's workaround for the embedding half
too. No separate removal: when the fix reaches a tagged Jolt release the
`:jolt/min-version` floor bump brings it, but keep the exclusion until
time#19 lands — it still saves the download, so a released jolt#1245 is not
a reason to drop it.
