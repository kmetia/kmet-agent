# Building

kmet ships as self-contained executables, packaged by the host-dispatched
`dist` task: `bb dist` on babashka, `jolt dist` on jolt. Each build writes a
platform-qualified zip into `dist/` — `kmet-<version>-<platform>.zip` from
babashka, `kmetj-<version>-<platform>.zip` from jolt — holding one top-level
folder with the version-less executable (`kmet` / `kmetj`) and the LICENSE.

## Babashka artifacts

On babashka the artifact is an official babashka release binary with the kmet
uberjar appended (babashka detects the appended zip at startup and runs
`kmet.core/-main` — see babashka's "Self-contained executable" wiki page).
Cross-builds work from any host because packaging is download + concat only.

```sh
bb uberjar              # Build target/kmet.jar (also runnable: bb target/kmet.jar)
bb dist                 # dist/kmet-<version>-<platform>.zip
bb dist --all           # Every published platform
bb dist macos-aarch64   # Explicit platforms; --force re-downloads, --smoke runs the post-build check
bb dist --out ~/bin     # additionally copy the executable there
```

## Jolt artifacts

On jolt the same task AOT-compiles the app through
`jolt build -m kmet.core` — runtime, stdlib, dependencies and kmet in one
native binary, with the model catalogs embedded. Builds default to Jolt's
optimized emission (`--opt`, no inspector/procedure-source information) with
its fast boot image; `--release` keeps Clojure backtraces at a larger size:

```sh
jolt dist               # Optimized + fast-boot build → dist/kmetj-<version>-<platform>.zip
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
static process-symbol loads for every file-backed `:jolt/native`. A Windows
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

A direct `jolt build -m kmet.core` is still the one-off compiler command on
Unix. Use `jolt dist` when you want kmet's mode defaults, archive staging, and
post-build verification.

`jolt dist --help` lists every option: build modes, `--boot fast|small|plain`
(startup versus size), `--closed-world`, `--static` / `--dynamic`,
cross-compiling with `--target`/`--target-pack`, `--out DIR`, `--force`, and
`--smoke` to run the freshly built binary (`--list-models` plus `--version`)
before publishing.

## Artifacts

Both hosts write one platform-qualified zip into `dist/`, using the
`<os>-<arch>` platform vocabulary they share (`linux-amd64`, `linux-aarch64`,
`macos-amd64`, `macos-aarch64`, `windows-amd64`; jolt can also cross-compile
`windows-aarch64`):

| Host | Zip | Folder inside |
|------|-----|---------------|
| babashka | `dist/kmet-<version>-<platform>.zip` | `kmet-<version>-<platform>/kmet` (`kmet.exe` on Windows) |
| jolt | `dist/kmetj-<version>-<platform>.zip` | `kmetj-<version>-<platform>/kmetj` (`kmetj.exe` on Windows) |

Each zip holds one top-level folder named after the zip (its file name
minus `.zip`), so extracting never scatters files: the folder carries the
version-less executable, its Termux launcher when the build wrote one, and
the repository `LICENSE`. The executable carries no version — the zip name
carries version and platform — so `bb dist --all` keeps one zip per platform,
and both hosts' artifacts line up in one `dist/` (e.g.
`dist/kmet-0.8.0-linux-amd64.zip` next to
`dist/kmetj-0.8.0-linux-amd64.zip`). The executable is assembled under
`target/dist/<platform>/` (scratch, not part of the release layout); `--out
DIR` copies it — and its Termux launcher, when the build wrote one — into
`DIR` without the folder. A `--test` build swaps in the test runner
(`kmet-test` / `kmetj-test`, zipped as
`kmet-test-<version>-<platform>.zip` /
`kmetj-test-<version>-<platform>.zip`), and a jolt `--dev` build tags its zip
`-dev` (`kmetj-<version>-<platform>-dev.zip`).

The executable bit survives extraction: neither host's zip API can record
POSIX modes (babashka's bundled JDK and Jolt's runtime alike expose no
`ZipEntry.setUnixMode`), so `write-zip!` stamps the central directory — the
field extractors read — after writing the archive. `--out DIR` copies the
bare executable as well.

Babashka binaries are downloaded through kmet's HTTP layer and cached in
`target/build-cache/` (sha256-verified); `bb dist` always rebuilds a fresh
`target/kmet.jar` first so artifacts never bundle stale sources. Jolt
compiles under `target/jolt/<platform>/<mode>/`; `--smoke` runs the freshly
built binary (`--list-models` plus `--version`) before it is announced.

Versioning: artifacts are stamped with **jolt's checkout rule**
(`kmet.version`, jolt's `tools/version.sh`), so the base version reads
like the compiler's — the nearest `v<digit>` release tag as
`git describe --tags --dirty` reports it (`v0.8.0` on the tag,
`v0.8.0-56-g63374117` past it, `-dirty` with uncommitted edits), `dev-g<sha>`
when no release tag is reachable (a bare sha would read as version 0 to
anything parsing leading digits), and `dev` outside a git checkout. Rolling
tags such as `vnightly` never match. Artifact zip names carry the same
string with the `v` stripped; `kmet --version` prints it, from the version
baked at build time when running a packaged binary and from git in a source
run (`bb start` / `jolt start`).

## Termux and Android

The glibc-linker problem applies to both hosts — a glibc
binary must be exec'd through Termux's glibc dynamic linker, which also
disables babashka's own appended-jar auto-detection. Building on a termux host
therefore additionally emits a companion `.sh` launcher next to the artifact
(`kmet.sh` / `kmetj.sh`) that unsets `LD_PRELOAD`, execs via
`$PREFIX/glibc/lib/ld-linux-*.so.1` (plus `--jar <self>` for the babashka
binary). The launcher ships inside the zip too. It requires the termux
glibc package (`pkg install glibc-repo && pkg install glibc`).
