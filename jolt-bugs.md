# jolt-bugs — live upstream workarounds

Only live kmet-side workarounds for Jolt upstream issues are listed here,
with exactly what to remove or update. Windows-specific issues live in
`windows.md`. A ticket is done only when its fix reaches a tagged release:
closed upstream but unreleased still counts as live, because the workaround
protects the declared floor (the latest tagged release). Status checked
against GitHub and the re-fetched `origin/main` on 2026-10-06, with the
`jolt v0.8.18` build installed (latest tag: v0.8.18; `origin/main` at
`2dd4fe81`).

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

