# jolt-bugs — open upstream tickets

Every **open** jolt-side ticket whose fix requires a change in kmet or the
removal of a kmet workaround. Closed and unfiled findings are not tracked
here — recent closures (jolt v0.8.8-53 / http-client PR #21): #1011
(`Object.wait`/`notify`), #1007 (streaming HTTP), #1015/#1016
(`StringBuilder` `append`/`insert` char[]), #1017 (stream timeout). Their
workarounds are removed from kmet alongside this edit. `jolt-port.md` /
`jolt-tui.md` describe port state without ticket IDs.

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

### [jolt#1006](https://github.com/jolt-lang/jolt/issues/1006) — SCI cannot implement a `copy-var*`-injected host protocol from `defrecord`/`extend-type` (follow-up to #1000, which closed as recipe-only)

**Area:** SCI interop / `defprotocol` representation

Upstream closed #1000 with merge #1003 (`6e76671b`: `~@` lazy past the first
splice + `:sigs` on protocol values — in the built `v0.8.1-443-gd55ec029`,
verified on-device). What landed: SCI's `deftype` analysis ordering is fixed and
the babashka recipe (multimethods on `sci.impl.types/type-impl` + a SCI-side
protocol map + the host protocol extended to `SciRecord`/`SciType`, pinned by
jolt's `sci-functional-test` gate) runs end to end. What did NOT land: the
transparent flow — `(defrecord R [] p/P (m …))` over a `copy-var*`-injected host
protocol — still fails under SCI on jolt with `Unable to resolve symbol:
<method>` (phase `analysis`) and works on bb/JVM. Minimal repro on the built
binary: inject `kmet.tui.protocols` by reference (`copy-var*` per var, as
`shared-var-map` does) and eval
`(defrecord R [x] kmet.tui.protocols/IComponent (render …) …)` →
`FAIL: Unable to resolve symbol: render`. Upstream's own commit message declares
this flow unsupported ("fails on the JVM too"); the recipe is the supported
path. SCI-defined protocols are fine on jolt (verified: `defprotocol` +
`deftype`/`defrecord` in one context, cross-namespace via load-fn, and aliased
method calls all evaluate).

This is the `defcomponent` blocker: `defcomponent` expands to exactly that
`defrecord` over the injected `kmet.tui.protocols/IComponent`, so every
extension component fails. Deep probe of the real loader context (`eval-source!`
chain): `extensions/mcp-adapter/src` fails at
`extensions/mcp_adapter/panel.clj:450` (`(defcomponent McpPanel …)`) with
`Unable to resolve symbol: render`. `lsp-adapter` (`panel.clj:61` `LspPanel`)
and `review` (`dialogs.clj:93,108` plus `extend-type … IFocusable` at `:101,116`)
fail with the same surface. NOTE: `lsp-adapter` is no longer blocked by #999 —
`(java.net.URLDecoder/decode …)` resolves in SCI on the built binary (verified
directly, via the loader's retry path); it dies earlier in its panel.
`tree-sitter` (no components) loads fine, which corroborates.

(#998 `Matcher` and #999 `URLEncoder`/`URLDecoder` class tokens landed with
merge #1002 and are verified fixed on-device — `Class/forName`, `instance?`,
`class`, and the SCI shapes all answer — so their sections are pruned; neither
blocks any shipped extension anymore.)

**Workaround** (`test/kmet/app/test_extensions.clj:1056`): the `^:bb-only` gate
on `test-shipped-extensions-load-from-src` stays for #1006 (`mcp-adapter` /
`review` / `lsp-adapter` panels) plus the clojure extension's unfiled
Maven-chain gaps below — none of the three former tickets covers clojure
anymore. Error-attribution pitfall: `load-extension!` names the ENTRY file in
the error string (`…/core.clj`, `…/mcp_adapter.clj`); the real failure is the
deepest `ex-data :file` in the cause chain. — `extensions/clojure/src`
(unfiled, needs triage — not a jolt runtime ticket yet): its `deps.edn` closure
pulls the raw Maven sources (`rewrite-clj 1.2.57`, `tools.reader 1.5.2`, `cljfmt
0.16.5`) because jolt has no bundled ports for them (`bundled-port-namespaces` /
`bb-shared-namespaces` are bb-only by design; `host-requires!` skips them on
jolt). Requiring the closure in the real context gives: `reader-types` OK,
`edamame.core` OK, `rewrite-clj.reader` FAIL at `tools.reader
impl/inspect.clj:49` (`defmethod inspect* clojure.lang.PersistentVector$ChunkedSeq`
— the `$ArrayMap$Seq` / `$NodeSeq` methods below it are the same shape),
`rewrite-clj.node` / `cljfmt.core` FAIL at `rewrite-clj interop.cljc:42`
(`(instance? clojure.lang.IMeta data)`). All three classes exist on jolt
(`Class/forName` OK) and answer once `sci/add-class!`-registered (verified
individually) — but dep-closure sources bypass both seed paths
(`register-source-classes!` runs only for own-artifact sources in `make-load-fn`;
`eval-source-with-retry!` covers only the entry source), so each miss is fatal
single-attempt. Behind those sits a harder wall: `tools.reader
reader_types.clj:14-15` imports `java.io InputStream/BufferedReader/Closeable`
and implements `Closeable` in `deftype` positions (`:75,100,166`) — SCI rejects
host interfaces in `deftype` on BOTH hosts (`defrecord/deftype currently only
support protocol implementations`, verified on bb too), which bb never hits
because it injects its port. Fix directions: kmet-side (extend seeding/retry to
dep-closure sources; covers `IMeta`/`ChunkedSeq`) and upstream-or-port for the
`Closeable`-in-`deftype` wall. Removal: when #1006 lands (or
kmet reworks `defcomponent`/injection to the recipe) AND the clojure chain
loads, drop `^:bb-only`, run
`jolt test kmet.app.test-extensions/test-shipped-extensions-load-from-src`, and
delete this block.

### [jolt#1020](https://github.com/jolt-lang/jolt/issues/1020) — host method tables silently ignore extra trailing arguments; `String/valueOf(char[], offset, count)` ignores offset/count

**Area:** host method dispatch (`host/chez/java/natives-str.ss`'s
`jolt-string-method` and other `rest`-taking tables): extra args are dropped
instead of raising the JVM's arity error, so
`(String/valueOf (char-array [\a \b \c]) 1 2)` returns `"abc"` instead of
`"bc"`. Found while sweeping the char[] API family around #1015/#1016.

**Workaround:** none in kmet — no call sites; tracked because the
silent-result class can mask user errors.

### [jolt#1021](https://github.com/jolt-lang/jolt/issues/1021) — `String/copyValueOf` missing (both arities)

**Area:** `java.lang.String` static table — `(String/copyValueOf (char-array …))`
and the 3-arg form throw `No matching field or method: String/copyValueOf`;
they are identical to the matching `String/valueOf` overloads on the JVM (and
pair with #1020).

**Workaround:** none in kmet — no call sites.

### [jolt#1022](https://github.com/jolt-lang/jolt/issues/1022) — `StringBuilder`/`StringBuffer` `getChars(int, int, char[], int)` missing

**Area:** `string-builder-methods` (`host/chez/java/host-static-classes.ss`) has
no `getChars`, so `(.getChars (StringBuilder. "abc") 0 3 dst 0)` throws
`No matching method getChars found`; `String.getChars` works. The char[] family
companion to #1015/#1016.

**Workaround:** none in kmet — no call sites.
