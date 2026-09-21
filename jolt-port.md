# Porting kmet to Jolt — open work

The port is staged and largely landed: kmet runs on Jolt — TUI (native
termios terminal backend), providers (HTTP via babashka.http-client over the
jolt-lang shims), packaging (`jolt dist`), and extensions (the native
loader, with SCI as the declared fallback). This file tracks **only what is
still open**; finished work lives in the code, in `jolt-tui.md` (TUI
adapter deep-dive: FFI ground rules, raw mode, input pipeline, key parser,
concurrency) and in `jolt-bugs.md` (upstream issues filed or tracked). The
item labels (B2, M5, …) are the original port report's ids.

## Windows — the last platform (M2)

- **Terminal backend**: `src/kmet/tui/terminal_native.cljc` has no Windows
  implementation; `create-terminal` throws the design note. The landing
  spot is binding kernel32 `GetConsoleMode`/`SetConsoleMode` + resize +
  input records.
- **Process** (B2): Windows falls back to Chez `open-process-ports`, where
  ^C cannot interrupt the child; `destroy-tree` behavior needs a Windows
  test.
- **Native libs**: the upstream crypto / http-client libraries now declare
  their own `:jolt/native` candidates (`:darwin`/`:linux`/`:windows`), so
  kmet's project-level mirror block in `deps.edn` is gone (crypto#11,
  http-client#23 closed — see `jolt-bugs.md`). Still owed: the Windows
  smoke (`jolt -e '(println :ok)'` with the libcrypto/libssl/libz DLLs
  beside `jolt.exe` or on PATH).
- Windows is the last platform to light up, after Unix parity.

## Process edges (B2)

- Probe: pipe-streaming without deadlock; timeout semantics (bb's `:timeout`
  reports exit 0 on kill — check Jolt matches); `setsid`/process-group kill;
  async stdin/stdout streams for MCP stdio servers; `destroy-tree` on all
  OSes.
- Compare the vendored `babashka.process` surface against kmet's use by file
  — the vendored sources carry no version constants.
- Landed context: the bash-tool path is green (stdin redirected to
  NUL//dev/null; `ProcessBuilder.redirectInput(File)` reaches the child as
  of `v0.8.6-98`, jolt#947). If `jolt.process` falls short, the fallback is
  direct `posix_spawn`/`waitpid`/`kill` FFI (the calls `process.ss` itself
  uses).

## Extension content on Jolt (B3)

The loader side is done: extension contexts default to the runtime's native
loader, and the `:sci` backend is the declared fallback. Open:

- **bb-port gap**: Jolt bundles no ports, so an extension needing
  `clojure.spec` or `clojure.data.xml` declares a Maven dep; a SCI context
  still cannot load spec.alpha (M11 below), a native context can.
  `rewrite-clj` and `edamame` are declared and load the Maven jars: the
  shipped `clojure` extension loads on the native loader and its tools work
  on both hosts — the classpath loader's syntax-quote misresolution that was
  its last blocker is fixed by jolt PR #1075.
- **Class graph (sci contexts)**: classes Jolt's class graph does not supply
  (e.g. a `^StringBuilder` hint) fail the load there — upstream.
- **M11**: `clojure.spec.alpha` injection for a SCI context.
- **Upstream**: jolt#1031 (SCI `IVar` `:getRawRoot`) is open in
  `jolt-bugs.md`.

## Tooling — remaining bb-only surfaces (M5/M6/M10)

- `kmet.tasks.build/uberjar*` and `pack-extension!` still throw `::bb-only`:
  Jolt packaging is done (`kmet.tasks.build-jolt`), **extension packing on
  Jolt is open**.
- Model generators (`generate-models` / `generate-image-models`) remain
  bb-only.

## Verification backlog

- **Session lock (M14)**: `app/session.clj`'s `ReentrantLock` file-mutation
  lock is assumed shimmed but has not been run on Jolt — exercise the lock
  path (the `Callable` site becomes a fn; `locking` covers the session
  lock).
- **JVM-surface audit (M15)**: per-site check of `java.net.URI`/`URL`/
  `URLEncoder`, `Normalizer`, `Charset`, `HexFormat`, `Instant`/
  `DateTimeFormatter`/`ZoneId`, `PushbackReader`, `StringReader`/`Writer`
  for every new call site. Most are verified.
- **Termux**: no `/tmp`, `~` expansion, IME paste paths. Jolt's
  `java.io.tmpdir` honors `$TMPDIR` (unlike bb) — keep the explicit-dir
  pattern anyway.
- **Perf**: measure the TUI frame loop and token streaming on a compiled
  `jolt build`.
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
