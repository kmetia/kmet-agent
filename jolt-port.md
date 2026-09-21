# Porting kmet to Jolt — open work

The port is staged and largely landed: kmet runs on Jolt — TUI (native
terminal backend: termios on Unix, kernel32 on Windows), providers (HTTP
via babashka.http-client over the jolt-lang shims), packaging (`jolt
dist`), and extensions (the native loader, with SCI as the declared
fallback). This file tracks **only what is still open**; finished work
lives in the code, in `jolt-tui.md` (TUI adapter deep-dive: FFI ground
rules, raw mode, input pipeline, key parser, concurrency) and in
`jolt-bugs.md` (upstream issues filed or tracked). The item labels (B2,
M5, …) are the original port report's ids.

## Windows — the last platform (M2)

- **Process** (B2): Windows falls back to Chez `open-process-ports`, where
  ^C cannot interrupt the child; `destroy-tree` behavior needs a Windows
  test.
- **Native libs**: still owed: the Windows smoke (`jolt -e '(println :ok)'`
  with the libcrypto/libssl/libz DLLs beside `jolt.exe` or on PATH).
- **Dev loop on Jolt/Windows**: `kmet.tasks.lint`'s target enumeration and
  `kmet.tasks.clean`'s `extensions/*/target` still spell `/`-separated glob
  patterns, which the vendored `fs/glob` misses on Windows (`jolt-bugs.md`
  finding 4), so a Jolt/Windows `lint`/`clean` cannot be trusted until those
  patterns are rewritten. None of this touches the terminal backend.
- Windows is the last platform to light up, after Unix parity.

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
- **Class graph (sci contexts)**: classes Jolt's class graph does not supply
  (e.g. a `^StringBuilder` hint) fail the load there — upstream.
- **M11**: `clojure.spec.alpha` injection for a SCI context.
- **SCI pin**: `jolt/deps.edn` pins yogthos/sci @ babashka/sci#1093 — move
  back to a Maven release once #1093 merges and a release carries it.
- **Upstream**: jolt#1031 (SCI `IVar` `:getRawRoot`) is open in
  `jolt-bugs.md`.

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
- **Stale `^:bb-only` gates**: `test-curl-compression` (its comment still
  says java.util.zip is Jolt-unavailable; jolt PR #1044 landed it) and
  `test-extension-gets-bundled-spec-port-and-file-seq` (the fixed bundled
  set loads the Maven spec.alpha, and the fixture's `:jolt` loader runs it)
  both pass under `jolt test` — drop the gates.
