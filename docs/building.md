# Building

kmet ships as self-contained executables, packaged by the host-dispatched
`dist` task: `bb dist` on babashka, `jolt dist` on jolt — same name, two
packagers, artifacts side by side in `dist/`.

## Babashka artifacts

On babashka the artifact is an official babashka release binary with the kmet
uberjar appended (babashka detects the appended zip at startup and runs
`kmet.core/-main` — see babashka's "Self-contained executable" wiki page).
Cross-builds work from any host because packaging is download + concat only.

```sh
bb uberjar              # Build target/kmet.jar (also runnable: bb target/kmet.jar)
bb dist                 # Executable for the current platform -> dist/
bb dist --all           # Every published platform
bb dist macos-aarch64   # Explicit platforms; --force re-downloads, --smoke runs the post-build check
```

## Jolt artifacts

On jolt the same task AOT-compiles the app through
`jolt build -m kmet.core` — runtime, stdlib, dependencies and kmet in one
native binary, with the model catalogs embedded:

```sh
jolt dist               # Native binary for the current platform -> dist/
jolt dist --dev         # Unoptimized build (--opt for optimized; release is the default)
jolt dist --target tarm64le --target-pack "$TMPDIR/pack"   # Cross-compile (use any writable pack dir)
```

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
static process-symbol loads for every file-backed `:jolt/native`. Windows
static builds also stage lz4/zlib and need an MSYS2 MINGW64 toolchain:

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
cross-compiling with `--target`/`--target-pack`, `-o PATH`, `--force`, and
`--smoke` to run the freshly built binary (`--list-models` plus `--version`)
before publishing.

## Artifact names and versions

Both hosts name artifacts by one scheme:
`kmet-<version>-<host><host-version>-<platform>[-dev]` (plus `.exe` on
Windows; `-dev` marks a jolt `--dev` build), so one `dist/` lines the hosts up
per platform — `kmet-0.8.0-56-g63374117-bb1.13.224-linux-amd64` next to
`kmet-0.8.0-56-g63374117-jolt0.8.6-86-g234f460b-linux-amd64`. The platform is
the jolt-style `<os>-<arch>` name (`linux-amd64`, `linux-aarch64`,
`macos-amd64`, `macos-aarch64`, `windows-amd64`; jolt can also cross-compile
`windows-aarch64`), shared by both packagers: babashka targets are its five
platforms, with babashka's statically linked linux release assets behind
`linux-amd64`/`linux-aarch64` (a release asset slug like
`linux-amd64-static` is accepted too). Babashka binaries are cached in
`target/build-cache/` (sha256-verified on download) and `bb dist` always
rebuilds a fresh `target/kmet.jar` first so artifacts never bundle stale
sources. Downloads use `curl` (preinstalled on Termux, macOS, Linux and
Windows 10+). Jolt compiles under `target/jolt/<platform>/<mode>/`; `--smoke` runs the freshly
built binary (`--list-models` plus `--version`) before it is announced.

Versioning: artifacts are stamped with **jolt's checkout rule**
(`kmet.version`, jolt's `tools/version.sh`), so the base version reads
like the compiler's — the nearest `v<digit>` release tag as
`git describe --tags --dirty` reports it (`v0.8.0` on the tag,
`v0.8.0-56-g63374117` past it, `-dirty` with uncommitted edits), `dev-g<sha>`
when no release tag is reachable (a bare sha would read as version 0 to
anything parsing leading digits), and `dev` outside a git checkout. Rolling
tags such as `vnightly` never match. Artifact names carry the same string with
the `v` stripped; `kmet --version` prints it, from the version baked at build
time when running a packaged binary and from git in a source run (`bb run` /
`jolt run`).

## Termux and Android

The glibc-linker problem applies to both hosts — a glibc
binary must be exec'd through Termux's glibc dynamic linker, which also
disables babashka's own appended-jar auto-detection. Building on a termux host
therefore additionally emits a companion `.sh` launcher next to the artifact
(e.g. `kmet-<version>-bb<bb-version>-<platform>.sh`) that unsets `LD_PRELOAD`, execs via
`$PREFIX/glibc/lib/ld-linux-*.so.1` (plus `--jar <self>` for the babashka
binary). It requires the termux glibc package (`pkg install glibc-repo && pkg
install glibc`).
