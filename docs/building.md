# Building

kmet ships as self-contained executables, packaged by the host-dispatched
`dist` task: `bb dist` on babashka, `jolt dist` on jolt, `bb dist-jvm` for the
JVM. Each build writes a platform-qualified zip into `dist/` —
`kmet-<version>-<platform>.zip` from babashka,
`kmetj-<version>-<platform>-<link>.zip` from jolt, and
`kmetc-<version>-jvm.zip` for the JVM — holding one top-level folder with the
version-less executable (`kmet` / `kmetj` / `kmetc`) and the LICENSE. The
names are host-distinct so all three can be installed side by side and run
as different commands. The JVM host's artifact is one platform-independent
uberjar (`bb uberjar-jvm`), because there is no native kmet binary to ship —
a JVM runs the jar (see [JVM artifacts](#jvm-artifacts)).

## Babashka artifacts

On babashka the artifact is an official babashka release binary with the kmet
uberjar appended (babashka detects the appended zip at startup and runs
`kmet.app.core/-main`, the process entry — see babashka's "Self-contained
executable" wiki page).
Cross-builds work from any host because packaging is download + concat only.

```sh
bb uberjar              # Build target/uberjar-bb.jar (also runnable: bb target/uberjar-bb.jar)
bb dist                 # dist/kmet-<version>-<platform>.zip
bb dist --all           # Every published platform
bb dist macos-aarch64   # Explicit platforms; --force re-downloads, --smoke runs the post-build check
bb dist --out ~/bin     # additionally copy the executable there
```

## Jolt artifacts

On jolt the same task AOT-compiles the app through
`jolt build -m kmet.app.core` — runtime, stdlib, dependencies and kmet in one
native binary, with the model catalogs embedded. Builds default to Jolt's
optimized emission (`--opt`, no inspector/procedure-source information) with
its fast boot image; `--release` keeps Clojure backtraces at a larger size:

```sh
jolt dist               # Optimized + fast-boot build → dist/kmetj-<version>-<platform>-<link>.zip
jolt dist --release     # Release build: optimized plus Clojure backtraces
jolt dist --dev         # Unoptimized build (quickest to produce, redefinable vars)
jolt dist --out ~/bin   # additionally copy the executable there
jolt dist --target tarm64le --target-pack "$TMPDIR/pack"   # Cross-compile (use any writable pack dir)
```

kmet's Jolt floor is the **latest tagged release** — `:jolt/min-version` in
the root `deps.edn`, mirrored by `jolt/deps.edn`; the host is young and moves
fast, so the floor follows each release tag
(`jolt/src/jolt/kmet/README.md` § Bundled-extension loader floor). Building
or running kmet from source therefore needs a Jolt at or above it; a
`kmetj` artifact embeds its runtime and needs no Jolt on the target.

On Termux, set `TMPDIR` to the Termux temporary directory (or choose another
writable path); `/tmp` is not available there.

### Native linking

`jolt dist` supports `--static` and `--dynamic`. They are mutually exclusive
and override the platform default:

| Platform | Default | Override |
|----------|---------|----------|
| Windows | `--static` | `jolt dist --dynamic` for runtime loading |
| Linux/WSL, macOS, Termux, other | `--dynamic` | `jolt dist --static` for archive linking |

A static build asks `cc` for `libcrypto.a` and `libssl.a`, stages them under
`target/jolt-native/<platform>/`, and verifies Jolt's generated Scheme uses
static process-symbol loads for every file-backed `:jolt/native`. The link
mode is part of the zip's name (`kmetj-<version>-<platform>-static.zip` vs
`-dynamic.zip`) because the two are different artifacts: a static build is
self-contained, a dynamic one needs its native libraries where it runs, so a
`dist/` can hold both and a release page tells them apart. A Windows
static build also needs an MSYS2 MINGW64 toolchain — Jolt's Windows link
names `-llz4`/`-lz`, which those packages supply:

```sh
pacman -S --needed mingw-w64-x86_64-gcc mingw-w64-x86_64-openssl mingw-w64-x86_64-lz4
jolt dist --smoke                 # Windows default: static
jolt dist --dynamic --smoke       # Windows override: dynamic
```

Set `KMET_OPENSSL_STATIC_DIR` to override static OpenSSL archive discovery.
Static native cross-builds are rejected: Jolt cannot yet link
target-architecture archives while compiling on another host.

A dynamic build passes `--dynamic` to Jolt, needs no C toolchain during
packaging, and loads the native libraries named in Jolt's build output at run
time; the binary is not self-contained. Its smoke test keeps `PATH`, since
those runtime libraries must be reachable. A Windows static smoke test instead
limits `PATH` to `System32`, proving no OpenSSL/lz4/zlib DLL is needed beside
the artifact.

A direct `jolt build -m kmet.app.core` is still the one-off compiler command on
Unix. Use `jolt dist` when you want kmet's mode defaults, archive staging, and
post-build verification.

`jolt dist --help` lists every option: build modes, `--boot fast|small|plain`
(startup versus size), `--closed-world`, `--static` / `--dynamic`,
cross-compiling with `--target`/`--target-pack`, `--out DIR`, `--force`, and
`--smoke` to run the freshly built binary (`--list-models` plus `--version`)
before publishing.

## JVM artifacts

The JVM host's artifact is an ordinary uberjar — no native binary — so one
file serves every platform and there is nothing to cross-build. `bb
uberjar-jvm` assembles it through tools.build (under the `deps.edn` `:build`
alias, so the task needs the Clojure CLI; it shells out, so any host can run
it):

```sh
bb uberjar-jvm                      # Build target/uberjar-jvm.jar
java -jar target/uberjar-jvm.jar    # ...and run it
```

The jar is AOT-compiled behind `kmet.app.jvm-main`, the `:gen-class` entry
(a manifest Main-Class must name a Java class, which a namespace only becomes
through `:gen-class`), carries the model catalogs, the version baked into
`kmet/version.txt`, and the bundled extensions staged under the same
`extensions/<name>/src` resource keys `bb dist` and `jolt dist` embed — the
set is identical across hosts. Extensions run on SCI there: babashka and the
JVM are the SCI-only hosts (`modules/loader/src/kmet/loader/loader.md`). Every
build ends with a smoke test (`java -jar … --version`), the check a jar that
assembles but cannot start fails. A source checkout needs no build at all:
`bb start-jvm` runs `clojure -M:jvm:clj-pin -m kmet.app.core`; `bb nrepl-jvm
[port]` opens an nREPL server on the same JVM classpath (default 7803) and
writes `.nrepl-port` for `clojure_eval`.

The Clojure version is pinned once, in `deps.edn`'s `:clj-pin` alias — the dev
run and the artifact resolve through it, so a tools.build or Clojure-CLI bump
cannot silently change the runtime a release ships. The `:jvm` alias beside it
adds what only this host lacks: `org.clojure/tools.deps`, the in-process
resolver for extension `:deps` (babashka bundles it; Jolt has `jolt.deps`), and
`org.babashka/ffi`, the host FFI the Windows clipboard backend uses (babashka
bundles `babashka.ffi`; Jolt has `jolt.ffi`). The alias also grants native
access (`--enable-native-access=ALL-UNNAMED`), and the packaged jar carries the
same grant as an `Enable-Native-Access` manifest attribute, so `java -jar`
needs no flag. The FFI library needs the Class-File/FFM API of JDK 22+; an
older JVM still runs the app, with the clipboard falling back to the platform
tools.
`bb start-jvm`, the JVM test tasks and the packager all resolve through
`:jvm :clj-pin`, so the dev loop, the gate and the artifact share one
classpath.

The test suites run on the JVM with the same runner and selection as the bb
gate — the four tasks mirror `bb test` / `bb test-ext` / `bb test-changed` /
`bb test-ext-changed`, take the same filters and the same directly changed or
paired namespace set, and run under the JVM aliases:

```sh
bb test-jvm               # fast suites (non-^:slow), filters as `bb test`
bb test-ext-jvm           # only the ^:slow suites
bb test-changed-jvm       # fast suites of the changed files' namespaces
bb test-ext-changed-jvm   # slow suites of the changed files' namespaces
```

`^:bb-only` tests (babashka's bundled SCI ports, `babashka.classpath`, Maven
resolution through babashka's bundled tools.deps) are skipped off babashka, so
the JVM run selects the portable surface; a namespace that cannot load still
fails the run, as on every host.

`bb dist-jvm` packs the release zip with the same conventions as the other
packagers: one top-level folder named after the zip holding the artifact and
the LICENSE, with `jvm` in the platform slot because one jar serves every OS.
The `kmetc-` prefix keeps the JVM command distinct from babashka's `kmet` and
jolt's `kmetj`, so all three install side by side:

```sh
bb dist-jvm            # dist/kmetc-<version>-jvm.zip
unzip -l dist/kmetc-0.1.3-jvm.zip
#   kmetc-0.1.3-jvm/kmetc.jar    the uberjar
#   kmetc-0.1.3-jvm/kmetc        POSIX launcher (java -jar, KMET_JAVA_OPTS)
#   kmetc-0.1.3-jvm/kmetc.sh     the same script under its .sh name
#   kmetc-0.1.3-jvm/kmetc.bat    cmd.exe launcher (the .cmd copy is identical)
#   kmetc-0.1.3-jvm/kmetc.cmd    cmd.exe launcher
#   kmetc-0.1.3-jvm/kmetc.ps1    PowerShell launcher (pwsh too)
#   kmetc-0.1.3-jvm/LICENSE
```

`bb uberjar` is a different artifact, not the JVM one without AOT: it builds
`target/uberjar-bb.jar`, the payload `bb dist` appends to a babashka release
binary. That jar carries no Clojure runtime and none of babashka's built-in
namespaces (their sources are compiled into the binary), so only babashka can
load it.

## Artifacts

The executable packagers write one platform-qualified zip each into `dist/`,
using the `<os>-<arch>` platform vocabulary they share (`linux-amd64`,
`linux-aarch64`, `macos-amd64`, `macos-aarch64`, `windows-amd64`; jolt can also
cross-compile `windows-aarch64`). The JVM packager's zip is platform-
independent, so its single slot reads `jvm`. Each host names its artifact
so the three commands (`kmet`, `kmetj`, `kmetc`) can be installed together:

| Host | Zip | Folder inside |
|------|-----|---------------|
| babashka | `dist/kmet-<version>-<platform>.zip` | `kmet-<version>-<platform>/kmet` (`kmet.exe` on Windows) |
| jolt | `dist/kmetj-<version>-<platform>-<link>.zip` (`<link>` is `static` or `dynamic`) | `kmetj-<version>-<platform>-<link>/kmetj` (`kmetj.exe` on Windows) |
| JVM | `dist/kmetc-<version>-jvm.zip` | `kmetc-<version>-jvm/kmetc.jar` + launchers (`kmetc`, `kmetc.sh`, `kmetc.bat`/`kmetc.cmd`, `kmetc.ps1`) |

Each zip holds one top-level folder named after the zip (its file name
minus `.zip`), so extracting never scatters files: the folder carries the
version-less executable (the jar for the JVM), its companion launcher when the
build wrote one, and the repository `LICENSE`. The executable carries no version — the zip name
carries version and platform — so `bb dist --all` keeps one zip per platform,
and every host's artifacts line up in one `dist/` (e.g.
`dist/kmet-0.8.0-linux-amd64.zip` next to
`dist/kmetj-0.8.0-linux-amd64-static.zip` and
`dist/kmetc-0.8.0-jvm.zip`). The executable is assembled under
`target/dist/<platform>/` (scratch, not part of the release layout); `--out
DIR` copies it — and its Termux launcher, when the build wrote one — into
`DIR` without the folder. A `--test` build swaps in the test runner
(`kmet-test` / `kmetj-test`, zipped as
`kmet-test-<version>-<platform>.zip` /
`kmetj-test-<version>-<platform>-<link>.zip`), a jolt `--dev` build tags its
zip `-dev` (`kmetj-<version>-<platform>-<link>-dev.zip`), and every jolt zip
names its native link mode.

The executable bit survives extraction: neither host's zip API can record
POSIX modes (babashka's bundled JDK and Jolt's runtime alike expose no
`ZipEntry.setUnixMode`), so `write-zip!` stamps the central directory — the
field extractors read — after writing the archive. `--out DIR` copies the
bare executable as well.

Babashka binaries are downloaded through kmet's HTTP layer and cached in
`target/build-cache/` (sha256-verified); `bb dist` always rebuilds a fresh
`target/uberjar-bb.jar` first so artifacts never bundle stale sources. Jolt
compiles under `target/jolt/<platform>/<mode>/`; `--smoke` runs the freshly
built binary (`--list-models` plus `--version`) before it is announced.

Versioning: artifacts are stamped with **jolt's checkout rule**
(`kmet.app.version`, jolt's `tools/version.sh`), so the base version reads
like the compiler's — the nearest `v<digit>` release tag as
`git describe --tags --dirty` reports it (`v0.8.0` on the tag,
`v0.8.0-56-g63374117` past it, `-dirty` with uncommitted edits), `dev-g<sha>`
when no release tag is reachable (a bare sha would read as version 0 to
anything parsing leading digits), and `dev` outside a git checkout. Rolling
tags such as `vnightly` never match. Artifact zip names carry the same
string with the `v` stripped; `kmet --version` prints it, from the version
baked at build time when running a packaged binary and from git in a source
run (`bb start` / `jolt start` / `bb start-jvm`). The JVM uberjar carries no
version in its file name — it reports the one baked into `kmet/version.txt`.

## Termux and Android

The glibc-linker problem applies to both hosts — a glibc
binary must be exec'd through Termux's glibc dynamic linker, which also
disables babashka's own appended-jar auto-detection. Building on a termux host
therefore additionally emits a companion `.sh` launcher next to the artifact
(`kmet.sh` / `kmetj.sh`) that unsets `LD_PRELOAD`, execs via
`$PREFIX/glibc/lib/ld-linux-*.so.1` (plus `--jar <self>` for the babashka
binary). The launcher ships inside the zip too. It requires the termux
glibc package (`pkg install glibc-repo && pkg install glibc`).
