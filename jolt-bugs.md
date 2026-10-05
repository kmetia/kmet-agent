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

### [jolt#1245](https://github.com/jolt-lang/jolt/issues/1245) — ClojureScript externs baked into built binaries

Open upstream (filed 2026-10-05). `bld-dep-resources` (`host/chez/build.ss`)
skips `.class` and `.cljs` but bakes `*.ext.js` externs, so any app whose
dependencies carry `cljsjs`-style artifacts ships compiler-only bytes; when
kmet still resolved `cljs.java-time`, 12.97 MB of `flat.ss` was the js-joda
externs. No kmet-side workaround is carried now — jolt-lang/time#19's
upstream exclusion keeps that artifact off the classpath — and the fix
arrives with the `:jolt/min-version` floor bump when a tagged Jolt release
carries it.
