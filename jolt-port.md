# Porting kmet to Jolt — open work

The port is staged and largely landed: kmet runs on Jolt — TUI (native
terminal backend: termios on Unix, kernel32 on Windows), providers (HTTP
via babashka.http-client over the jolt-lang shims, including the Windows
transport from http-client v0.0.15), packaging (`jolt dist`), and extensions
(the native loader, with SCI as the declared fallback). This file tracks
**only what is still open**; finished work lives in the code, and upstream
issues filed or tracked live in `jolt-bugs.md` and are not repeated here.

Status checked against `jolt v0.8.17` (2026-10-05), the latest tagged
release (kmet's declared floor is `:jolt/min-version` in the root
`deps.edn`; `jolt-bugs.md` tracks the live upstream workarounds).

## Dependency pins

The git pins track their upstream releases (checked 2026-10-05):

- `jolt-lang/jolt-crypto` — `v0.0.10` (`feb25f70`): the `(bytes, off, len)`
  overload fixes and the quadratic `MessageDigest.update` fix (PR #13)
  plus the macOS stub/libcrypto `:link-libs` change (PR #14). Keyed
  `jolt-lang/jolt-crypto` to match the lib name upstream http-client
  depends on, so this root pin supersedes the transitive v0.0.9.
- `io.github.jolt-lang/http-client` — `v0.1.0` (`d99af98`, PR #34's
  merge, 2026-10-02): drops the stale `:jolt/provides` claim on
  `java.util.concurrent.CompletableFuture` and floors the library at jolt
  v0.8.16 (now kmet's floor), and exits its test runners via jolt's exit
  wait while bumping jolt-crypto to v0.0.10 (PR #33, already kmet's pinned
  revision). v0.0.17 (`77d7e310`) carried the interrupted-connect and
  TLS-release fixes (PRs #30, #31) but still claimed the class, so every
  Jolt run warned; the v0.1.0 pin retires that.

## Tests

- **Flaky (Jolt-only, unpinned)**:
  `tui.test-compute/compute-change-invalidates-subscriber-and-schedules-frame`
  and `tui.test-hiccup/dep-change-schedules-a-frame-through-the-hook` each
  failed once in ~18 full runs ("rendering itself does not poke the hook",
  fired = 2); never standalone. Not reproduced on `v0.8.15-77`: 6 full
  `jolt test` runs and 4 full `bb test` runs green, plus 10 solo reruns
  each; mechanism still unpinned.
- **Flaky under load (Jolt)**:
  `app.test-tools/test-tool-bash-background-pipe-closed` (12.5 s against its
  8 s window) and `app.test-run-code/test-run-code-await-all-honors-timeout`
  (13.7 s against its 1 s window) each missed once in a loaded `jolt
  test-ext`; both pass standalone and on bb. Not reproduced on
  `v0.8.15-77`: 5/5 standalone, 5/5 with two concurrent full fast suites,
  and a full `jolt test-ext` green; the windows stay tight under load.
- **Flaky under load**: `libs.test-http/test-curl-redirect-slow-second-hop`
  — curl 97 "Connection reset by peer" through the test SOCKS proxy while
  the suite is loaded. Not reproduced on `v0.8.15-46`: 4/4 standalone and
  6/6 under a full fast-suite load. The test proxy now half-closes each
  direction with a real `Socket.shutdownOutput` (jolt#1208), which removes
  the reset path; keep an eye on it.
