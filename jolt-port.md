# Porting kmet to Jolt — open work

The port is staged and largely landed: kmet runs on Jolt — TUI (native
terminal backend: termios on Unix, kernel32 on Windows), providers (HTTP
via babashka.http-client over the jolt-lang shims, including the Windows
transport from http-client v0.0.15), packaging (`jolt dist`), and extensions
(the native loader, with SCI as the declared fallback). This file tracks
**only what is still open**; finished work lives in the code and in
`jolt-bugs.md` (upstream issues filed or tracked). The item labels (B2,
M5, …) are the original port report's ids.

## Windows — the last platform (M2)

The runtime gaps closed in jolt v0.8.11 (PR #1112): Windows process
spawns and `java.net` sockets. Follow-up jolt PR #1123 fixes the reader
handle leak, Windows file URLs and mtime handling; it is present in
`v0.8.11-18-g79bf6d6e`. The Windows HTTP transport is fixed in
http-client v0.0.15, now pinned by kmet. Remaining work here is validation:
run the bash tool, `jolt lint` (clj-kondo), both native and curl HTTP
transports on Windows, and give `destroy-tree` its Windows test.

## Process edges (B2)

- Probe: pipe-streaming without deadlock; timeout semantics (bb's `:timeout`
  reports exit 0 on kill — check Jolt matches); `setsid`/process-group kill;
  async stdin/stdout streams for MCP stdio servers; `destroy-tree` on all
  OSes.
- Compare the vendored `babashka.process` surface against kmet's use by file
  — the vendored sources carry no version constants.
- If `jolt.process` falls short, the fallback is direct
  `posix_spawn`/`waitpid`/`kill` FFI (the calls `process.ss` itself uses).

## Extension content on Jolt (B3)

The loader side is done: extension contexts default to the runtime's native
loader, and the `:sci` backend is the declared fallback. Open:

- **bb-port gap**: Jolt bundles no ports, so an extension needing
  `clojure.spec` or `clojure.data.xml` declares a Maven dep; a SCI context
  still cannot load spec.alpha (M11 below), a native context can.
- **M11**: `clojure.spec.alpha` injection for a SCI context.
- **SCI pin**: a host-protocol gap keeps `jolt/deps.edn`'s sci on a git
  pin — move it back to a Maven release once the fix merges and a release
  carries it.

## Tooling — remaining bb-only surfaces (M5/M6/M10)

- `kmet.tasks.build/uberjar*` and `pack-extension!` still throw `::bb-only`:
  Jolt packaging is done (`kmet.tasks.build-jolt`), **extension packing on
  Jolt is open**.
- Model generators (`generate-models` / `generate-image-models`) remain
  bb-only.

## Verification backlog

- **JVM-surface audit (M15)**: per-site check of `java.net.URI`/`URL`/
  `URLEncoder`, `Normalizer`, `Charset`, `HexFormat`, `Instant`/
  `DateTimeFormatter`/`ZoneId`, `PushbackReader`, `StringReader`/`Writer`
  for every new call site. Most are verified.
- **Termux**: no `/tmp`, `~` expansion, IME paste paths. Jolt's
  `java.io.tmpdir` honors `$TMPDIR` (unlike bb) — keep the explicit-dir
  pattern anyway.
- **On each Jolt upgrade**: re-run `jolt test` / `jolt test-ext`; the
  vendored `babashka.fs` / `babashka.process` carry no pins, so re-verify
  the surface kmet uses.

## Tests

- **Flaky (Jolt-only, unpinned)**:
  `tui.test-compute/compute-change-invalidates-subscriber-and-schedules-frame`
  and `tui.test-hiccup/dep-change-schedules-a-frame-through-the-hook` each
  failed once in ~18 full runs ("rendering itself does not poke the hook",
  fired = 2); never standalone; mechanism unpinned.
- **Flaky under load**: `libs.test-http/test-curl-redirect-slow-second-hop`
  — curl 97 "Connection reset by peer" through the test SOCKS proxy while
  the suite is loaded; green standalone.
- **`modes.test-overlay-input-smoke`** is `^:bb-only` (its driver spawns
  `bb run`, so on Jolt it would exercise bb's TUI); a Jolt-host pty variant
  is the follow-up.
