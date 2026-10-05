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
