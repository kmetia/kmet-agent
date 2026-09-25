# jolt-bugs — open upstream tickets

Only currently open Jolt upstream issues relevant to kmet are listed here.
Closed tickets and completed work are intentionally omitted. Status checked
against GitHub on 2026-09-25.

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
This affects the Jolt development CLI/runtime path. kmet's default Windows
`dist` artifact no longer takes it: its OpenSSL members come from the staged
static archives and `--smoke` runs with only Windows `System32` on `PATH`.
`jolt dist --dynamic` deliberately takes the runtime path again. Keep the issue
open for the Jolt executable's own dynamic-native path.

### Unfiled — Windows static-native builds need dependency-aware preload and link flags

Verified on `jolt v0.8.12-24-gfe2a4ab2`, Windows 11, 2026-09-25. Jolt preloads
each `:static {:archive …}` independently, so `libssl.a` cannot resolve the
`libcrypto.a` symbols during its build-time preload DLL. Its Windows launcher
link also omits OpenSSL's CryptoAPI dependency, and a drive-rooted missing
`<out>.build` reached `bld-mkdir-p` with a non-string parent. kmet's
`kmet.tasks.build-jolt` works around all three by precreating the build dir,
prepending a build-only `cc` shim, and staging static lz4/zlib in the native
directory. The final executable was smoke-run without the OpenSSL/lz4 DLLs and
its PE imports contained Windows system DLLs only. Recheck when filing upstream;
remove the shim once Jolt handles dependent native archives and their link
libraries directly.
