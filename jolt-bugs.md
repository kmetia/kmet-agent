# jolt-bugs — open upstream tickets

Every **open** jolt-side ticket whose fix requires a change in kmet. Closed and
unfiled findings are not tracked here. `jolt-port.md` / `jolt-tui.md` describe
port state without ticket IDs.

**Workarounds live next to their ticket below.** Each workaround block is the
removal checklist: when an upstream fix lands, delete the listed code (and the
block). Source comments describe the local *why* without ticket numbers —
grep this file to find what `file:line` belongs to which ticket.

Historical labels from the deleted `bb-jolt.md` map as: `JOLT-12`→#947,
`JOLT-13`→#944; git history has the full field reports.

## Open

### [jolt#992](https://github.com/jolt-lang/jolt/issues/992) — Windows: `jolt C:/…/file.clj` is read as project-relative — `open-input-file` fails for `./C:/…`

**Area:** `jolt-core/jolt/main.clj` (`file-arg`)

`file-arg` (main.clj:320-327) recognizes POSIX absolutes only — `"-"`,
`(str/starts-with? x "/")`, `(str/starts-with? x "./")` — so every drive-absolute
argument lands in the `:else` join `(str (project-dir) "/" x)`. `project-dir`
is `JOLT_PWD` or `"."` (main.clj:9), so the prebuilt binary tries
`./C:/Users/x/hello.clj` and both `jolt <file>` and `jolt run <file>` fail with
`open-input-file: … invalid argument`, from any cwd and for either separator
spelling. Relative and `./`-relative arguments work, which is why it is easy to
miss.

Same Windows path family as jolt#991 (the canonicalize half); both want the
classification `jolt.deps/native-path-kind-for` already does
(deps.clj:195-214: drive-absolute `C:/x`, UNC `//server/share`, root-relative
`/x`, drive-relative `C:x`), or `io.ss`'s `jfile-abs`.

**Workaround:** none needed in kmet — kmet drives jolt through `run`/tasks, not
`jolt <absolute file>`; a user hitting it can pass a cwd-relative path
(`jolt Users/x/hello.clj` from `C:\` works). Removal: nothing to delete when
`file-arg` stops joining a drive-absolute path to `project-dir`.

### [jolt#991](https://github.com/jolt-lang/jolt/issues/991) — Windows: `File.getCanonicalPath` prepends `/` to a drive-absolute path — and the value it returns cannot be used for I/O

**Area:** `host/chez/java/io.ss` (`jfile-canonical`, the no-`realpath` fallback)

`realpath(3)` is unbound on Windows, so `jfile-canonical` falls back to
`path-parent` / `jfile-fold-dots` — POSIX models that split on `/` only, keep
`C:` as an ordinary segment, and rejoin every segment as `"/" + segment`.
`File.getCanonicalPath`, `File.getCanonicalFile` and `babashka.fs/canonicalize`
therefore answer `/C:/Users/…` for every spelling of a drive-absolute path
(backslash input has no recognised separator at all), and any read or write
with the returned value fails against `C:/C:/…`. Two spellings of the same file
also canonicalize to different strings, so the identity comparison the realpath
switch exists for (jolt#693) still fails on Windows.

kmet hits it on every session write: `kmet.app.session/create-session` stamps
the session file with `(str (fs/canonicalize file))`, and print mode dies before
the first token with `open-output-file: failed for
C:/C:\Users\…\.kmet\sessions\….ednl.tmp.spit-tmp-…: invalid argument`.

**Workaround:** none in kmet — every `babashka.fs/canonicalize` consumer is hit
(session-file identity, package/extension path identity); the
`{:nofollow-links true}` spelling dodges the fallback but drops symlink
resolution, so it is not a drop-in. Removal: nothing to delete when the fallback
preserves the drive/UNC root and folds both separators — the call sites work
unchanged; verify with
`jolt -e '(println (str (babashka.fs/canonicalize "C:/…")))'`, then a Windows
`jolt run -p`. (The sibling file-argument defect is jolt#992.)

### [jolt#989](https://github.com/jolt-lang/jolt/issues/989) — Windows: a `:jolt/native` spec with no `:windows` candidates searches for nothing — and the diagnostic can't say so

**Area:** native loading / `:jolt/native` platform keys (Windows)

`jolt.main/load-natives!` (`jolt-core/jolt/main.clj:75-99`) reads candidates
from exactly one key — the one `current-platform` selects. On Windows a spec
declaring only `:darwin`/`:linux` yields `cands []`, so `some` returns nil
without a single `jolt.ffi/load-native` call, and the message says
`not found — tried [] for windows` about a search that never happened. The
libraries are not missing: `jolt-lang/crypto`, `jolt-lang/jolt-crypto` and
`jolt-lang/http-client` declare no `:windows` candidate, so the DLLs (beside
`jolt.exe`, or Git for Windows on PATH) are never asked for. Strict entry
points throw before app code runs (`jolt -e`, `run -m`, `build`); the task
path warns on every invocation. The issue carries a `user32.dll` minimal repro
(always in `system32`, still "not found") and the full code trace.

kmet hit it as three warnings from `jolt run -p 'say hello'` (crypto, ssl, z)
and a fatal `jolt -e`. An app cannot just add the missing key: the project's
own `:jolt/native` is first through the dedup (`jolt.deps` `:natives`,
deps.clj:1794, keyed on `:name` by `native-key`, deps.clj:1000), so a
windows-only project entry would dedup the deps' linux/darwin specs away and
break those platforms instead.

**Workaround** (`deps.edn`): the project-level `:jolt/native` block declaring
`crypto`/`ssl`/`z` — the upstream `:darwin`/`:linux` candidate lists mirrored
verbatim (mandatory, see the dedup above) plus the Windows names jolt's own
Maven fetcher already uses (`stdlib/jolt/mvn_http.clj` crypto-names /
ssl-names): `libcrypto-3-x64.dll`, `libssl-3-x64.dll`, `zlib1.dll`. The DLLs
are environment, not repo code — Git for Windows supplies them (on PATH), and
the loader also finds them beside `jolt.exe` (executable-directory search).
Verified on v0.8.7/Windows 11: the three warnings disappear, a strict
`jolt -e` in the checkout runs, and OpenSSL works (`MessageDigest` SHA-256
through the loaded DLL). Removal: delete the `:jolt/native` block from
`deps.edn` and re-run `jolt -e '(println :ok)'` on Windows with the DLLs
present — no complaint means the loader/library side has caught up. (`z` may
outlive the jolt fix — its gap is the `zlib1.dll` name, which no generic
spelling/glob fallback can derive; filed as jolt#990 below.)

### [jolt#990](https://github.com/jolt-lang/jolt/issues/990) — `crypto` / `ssl` / `z`: declare `:windows` candidates for the OpenSSL and libz natives

**Area:** `:jolt/native` declarations (`jolt-lang/crypto`, `jolt-lang/jolt-crypto`, `jolt-lang/http-client`)

The libraries kmet resolves for OpenSSL (`io.github.jolt-lang/crypto`, plus
`jolt-lang/jolt-crypto` riding `io.github.jolt-lang/http-client`'s own
deps.edn) and libz (`io.github.jolt-lang/http-client`) declare only
`:darwin`/`:linux` candidates, so on Windows jolt#989's empty-candidate path
fires for all three. Filed in the jolt tracker so the declarations land
together with the loader fix; the candidates are Git for Windows' DLL names —
the same ones jolt's own Maven fetcher already uses
(`stdlib/jolt/mvn_http.clj`): `libcrypto-3-x64.dll` (+ `-3.dll`,
`-1_1-x64.dll` fallbacks), `libssl-3-x64.dll` (+ fallbacks), `zlib1.dll`
(+ `z.dll`). Fixing this alone makes the kmet-side block redundant; fixing
only jolt#989 does not. Both crypto/ssl declarers must carry the key — the
same-`:name` dedup keeps the first spec through the graph, so a single fixed
repo may still lose to the other's unix-only entry.

**Workaround** (`deps.edn`): the same project-level `:jolt/native` block as
jolt#989 — one block, one removal step. Removal: bump the
`io.github.jolt-lang/crypto` and `io.github.jolt-lang/http-client` git shas to
revisions carrying the `:windows` keys, delete the `:jolt/native` block, and
re-run `jolt -e '(println :ok)'` on Windows with the DLLs present. If only
jolt#989 has landed by then, the block still has to stay for `z`.
