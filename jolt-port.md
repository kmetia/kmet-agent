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
`deps.edn`; `jolt-bugs.md` tracks the live upstream workarounds). No open
work remains: the tracked test flakes are fixed or no longer reproduce.
