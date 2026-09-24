# jolt-bugs — open upstream tickets

Only currently open Jolt upstream issues relevant to kmet are listed here.
Closed tickets and completed work are intentionally omitted. Status checked
against GitHub on 2026-09-24.

## Open

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

The issue affects extensions that choose the SCI loader backend on Jolt and
copy/implement host protocols. Shipped extensions using Jolt's native loader
are not blocked. The fix is proposed in
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093), still open at
head `1295142f`. `jolt/deps.edn` pins that SCI revision for the `:sci`
fallback; keep the pin until the change is released by Jolt.

### [jolt#1059](https://github.com/jolt-lang/jolt/issues/1059) — Very slow Jolt build

The report describes Jolt and kmet app builds taking minutes on Termux;
`jolt dist --test` ran for over an hour before it was stopped. The latest
upstream reply says the maintainer will investigate for a future release and
suggests using `jolt -M:run` during development instead of rebuilding a binary
each time. That is a development-loop alternative, not a fix for slow compiled
builds; this affects kmet's `dist` workflow.

### [jolt#1110](https://github.com/jolt-lang/jolt/issues/1110) — Windows path rendering remains deferred

The remaining open portion is `File`/`Path` string rendering and separators
(`/` rather than `\\` on Windows), a known Jolt/JVM divergence. As checked on
2026-09-24, [jolt PR #1124](https://github.com/jolt-lang/jolt/pull/1124) is
still open and the installed Jolt `v0.8.11-19-g6a224b0b` still renders
`File/separator` as `/` and Windows paths with `/`. kmet normalizes paths where
it needs a host-independent spelling; no app workaround is waiting to be
removed.

### [jolt#1127](https://github.com/jolt-lang/jolt/issues/1127) — Windows native `libssl` lookup warning

Jolt reports that it cannot find `ssl` even when `libssl-3-x64.dll` is beside
`jolt.exe`; startup eventually succeeds but continues to emit the warning.
This could affect kmet's Windows crypto/HTTPS startup because the Jolt
dependency graph declares OpenSSL native libraries. Direct TLS-context and RSA
signature lookups succeed on the installed
`v0.8.11-19-g6a224b0b`, but that is not an end-to-end reproduction of the
reported app-startup search path. No kmet workaround is identified; keep this
open pending upstream investigation.
