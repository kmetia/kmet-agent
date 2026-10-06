# jolt-bugs — live upstream workarounds

Only live kmet-side workarounds for Jolt upstream issues are listed here,
with exactly what to remove or update. A ticket is done only when its fix
reaches a tagged release: closed upstream but unreleased still counts as
live, because the workaround protects the declared floor (the latest tagged
release). Status checked against GitHub and the re-fetched `origin/main` on
2026-10-06, with the `jolt v0.8.18` build installed (latest tag: v0.8.18;
`origin/main` at `2dd4fe81`).

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

### [jolt#1263](https://github.com/jolt-lang/jolt/issues/1263) — Windows: `spit` fails transiently when AV holds its temp file

Open (filed 2026-10-06, no fix yet). Windows real-time AV opens a freshly
written file to scan it; a rename landing in that window fails with
`permission denied`, and `jolt-spit` renames its temp microseconds after
`close-port`. The failed temp cannot be cleaned up either (the scanner holds
it), so every `spit` is exposed at that instant — on 2026-10-06 it lost the
first save of a session. `rename-replace!` covers only an existing
destination; the lock is on the source.

kmet-side: `kmet.libs.fs/publish!` writes the publish temp in place through
`io/output-stream` (explicit UTF-8) and renames it with `fs/move` — one
rename per publish on both hosts, where jolt's `spit` adds a second — and
retries both steps (5 attempts over ~0.8s) before a write may fail. The
session store and the MCP credential store (`kmet.libs.mcp.auth`) publish
through it.

Nothing to remove when the fix ships: kmet's own `fs/move` rename faces the
same OS behavior on either host and keeps its retry, and the in-place temp
write is now the intended implementation. Revisit only if a released Jolt
stops renaming on `spit` or its retry makes kmet's redundant; drop this
entry once #1263 is fixed and released.
