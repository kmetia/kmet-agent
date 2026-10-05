# Windows host — test status

Recorded 2026-09-29 on Windows 11 26200 in Windows Terminal (`WT_SESSION`
set), Jolt `v0.8.14-29-gc7c6b33e`; re-verified 2026-10-01 on
`jolt v0.8.15-46-g005d134b`, after
[jolt-lang/jolt#1210](https://github.com/jolt-lang/jolt/pull/1210) closed
every tracked Windows bug (#1203, #1205, #1206, #1207, #1208). The fast suites
are green on both hosts:

- `bb test` — 2784 tests / 18099 assertions, 0 failures + 0 errors
- `jolt test` — 2764 tests / 18000 assertions, 0 failures + 0 errors

No open Windows-host failures. The only open upstream ticket is
[jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) (SCI host
protocols); it is not Windows-specific and is tracked in `jolt-bugs.md`.

## Resolved 2026-09-29 … 2026-10-01

Each was rerun standalone on both hosts; all green afterwards.

- **Path separators** — `tasks.test-slop/source-discovery-…`,
  `tasks.build-test/stage-bundled-extensions-…` and
  `tasks.format-test/test-source-paths` compared `fs/file`/`fs/relativize`
  output raw; they now compare through `kmet.test-utils/slash` (`e43d97b`
  and its follow-up). `test-time-boundary/libs-measure-durations-monotonically`
  compared its walk to a `/`-spelled allowlist raw; it slashes the walk too
  (2026-10-01).
- **POSIX permissions** — `install-artifact!`, `assemble-one!` and the jolt
  `assemble!` skipped the chmod only for a Windows TARGET, so a Windows host
  cross-packaging a linux artifact died with `UnsupportedOperationException`;
  the guard now asks the host (`fs/windows?`) too (`e43d97b`).
- **CRLF capture** — `tasks.test-help/with-help-test` compared bb's CRLF
  `with-out-str` output raw; now through the new `out-str-lf` (`e43d97b`).
- **`file:` resource paths** (jolt#1203) — `jolt.loader` dropped the scheme
  with `(subs s 5)`, so a `file:/C:/…` resource hit failed to open; the
  runtime now converts through the file-URI handling. No kmet workaround
  existed; `loader.test-jolt-loader/adapter-host-view-is-the-extension-contract`
  passes unchanged.
- **curl 56 through the test SOCKS proxy** (jolt#1208) — the proxy called
  `Socket.shutdownOutput` for each pump but on the output STREAM (a no-op on
  both hosts), so the workaround briefly joined the client→target pump before
  closing. Jolt now has the half-close members, `pump` half-closes the socket
  itself, and the join is gone; 0/40 jolt and 0/10 bb reruns.

  **Watch**: `libs.test-http/test-curl-redirect-slow-second-hop` — the
  under-load sibling in the same proxy family; curl 97 "Connection reset by
  peer" through the test SOCKS proxy while the suite is loaded. Not
  reproduced on `v0.8.15-46`: 4/4 standalone and 6/6 under a full fast-suite
  load. The half-close removes the reset path; keep an eye on it.
- **Windows static-native build gaps** (jolt#1205/#1206/#1207) — the
  build-only `cc` shim, the precreated `<out>.build` directory, and the
  staged lz4/zlib archives are all gone. `jolt dist --smoke` builds the
  static artifact and runs it with `PATH` limited to `System32`, proving no
  OpenSSL/lz4/zlib DLL is needed. The staged directory now uses Jolt's
  native-platform key (`windows`), matching the paths in deps.edn.
- **Terminal hyperlinks** — the six tool-call-pairing assertions in
  `app.test-loop`, `app.ui.test-chat-history` and `modes.test-interactive`
  only fired when terminal detection enabled hyperlinks (`WT_SESSION`),
  because the shared `tu/strip-ansi` helper stripped CSI but not the OSC 8
  sequences `tool-renderers/link-path` emits. Fixed in `ce402e5` (delegates
  to `kmet.tui.utils/strip-ansi-codes`).
