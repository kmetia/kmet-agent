# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix reaches a tagged release.
Status checked on 2026-10-29 against the tagged `v0.8.23` (released 2026-10-17)
and the installed `v0.8.23-199-g0ac561e1` dev build on `main`, which
`:jolt/min-version` now names (`0.8.23`). The tag carries the fixes for #1379,
#1380 and #1395 ([PR #1382](https://github.com/jolt-lang/jolt/pull/1382),
[PR #1396](https://github.com/jolt-lang/jolt/pull/1396)), so those entries are
dropped and the `sort-by str` path workaround that outlived them went with the
floor. Also checked: `jolt-lang/http-client` `v0.1.2` (`00455b8`, unchanged;
`HEAD` is that same revision), the version pinned by the project.

Filing: [`#1433`](https://github.com/jolt-lang/jolt/issues/1433) (shim enums
lack `valueOf`/`values`) was filed 2026-10-24 against the installed
`v0.8.23-47-g75c8b610` dev build, where it reproduced, and closed on `main`
2026-10-26 by [PR #1435](https://github.com/jolt-lang/jolt/pull/1435) (merge
`4fd288a2`, follow-up
[#1437](https://github.com/jolt-lang/jolt/pull/1437)), verified on
`v0.8.23-154-g8ba11735`. No tag carries it yet, so the entry and its workaround
stay. `#1031` is unchanged: still open, as is
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093).

Filing: [`#1453`](https://github.com/jolt-lang/jolt/issues/1453) and
[`#1454`](https://github.com/jolt-lang/jolt/issues/1454) were filed 2026-10-29
against the installed `v0.8.23-199-g0ac561e1` dev build, where both
reproduced. They are the writer and reader halves of one defect — non-BMP
characters cannot cross Jolt's JSON boundary intact in either direction — and
neither is fixed or tagged yet.

Filing: [`#1466`](https://github.com/jolt-lang/jolt/issues/1466),
[`#1467`](https://github.com/jolt-lang/jolt/issues/1467),
[`#1468`](https://github.com/jolt-lang/jolt/issues/1468) and
[`#1469`](https://github.com/jolt-lang/jolt/issues/1469) were filed 2026-10-31
against the installed `v0.8.23-232-g94da94fc` dev build, where all four
reproduced. `#1466` and `#1469` are loading behavior (`*file*` and
`jolt.loader`), `#1467`/`#1468` are `java.nio.file`/`java.net.URI`
conformance; none is fixed or tagged yet. Each entry's "Remove with the fix"
names the kmet code that goes when the ticket's fix is tagged.

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open (last upstream update 2026-10-09), as is the upstream
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) itself (no
update since 2026-09-16). It affects extensions that choose the SCI loader
backend on Jolt and copy or implement host protocols; shipped extensions
declaring `:jolt` use Jolt's native loader and are not blocked, which is why
`test-shipped-extensions-load-from-src` runs on both hosts. `jolt/deps.edn` and
`modules/app/deps.edn` pin yogthos/sci at `1295142` (the #1093 branch) for the
`:sci` fallback. Keep the pin until the change is released by Jolt.

### [jolt#1433](https://github.com/jolt-lang/jolt/issues/1433) — `valueOf`/`values` missing from Jolt's shim-provided enums

Static *fields* on enum classes resolve, but the JVM-synthesized static
methods `valueOf`/`values` do not, for the enums Jolt's dependency shims
provide (`java.util.concurrent`, `java.nio.file`, `java.text`). Enums from
the fuller `java.time` implementation work, and static methods generally
work, so this is the shim layer rather than interop as a whole.

```clojure
;; works: static fields, static methods, java.time enums
java.util.concurrent.TimeUnit/MILLISECONDS          ; => MILLISECONDS
java.nio.file.StandardOpenOption/WRITE              ; => #object[:object]
java.text.Normalizer$Form/NFKC                      ; => NFKC
(java.time.ZoneOffset/ofTotalSeconds 0)             ; => Z
(java.time.DayOfWeek/valueOf "MONDAY")              ; => MONDAY
(java.time.DayOfWeek/values)                        ; => [MONDAY ... SUNDAY]
(java.lang.Math/abs -1)                             ; => 1

;; fails: the same shape on shim-provided enums
(java.util.concurrent.TimeUnit/valueOf "MILLISECONDS")
;; IllegalArgumentException: No matching field or method: java.util.concurrent.TimeUnit/valueOf
(java.util.concurrent.TimeUnit/values)
(java.nio.file.StandardOpenOption/valueOf "WRITE")
(java.nio.file.StandardOpenOption/values)
(java.text.Normalizer$Form/valueOf "NFKC")
(java.text.Normalizer$Form/values)

;; and the interface itself is not provided at all:
(java.lang.Enum/valueOf java.time.DayOfWeek "MONDAY")
;; No dependency provides java.lang.Enum — a concrete implementation of the JDK
;; classes must be provided ... (RFC 0014)
```

Expected: either resolve them the way the JVM does, or fail with a message
that names the class as shim-provided (the RFC 0014 text above is a good
model) instead of reporting a missing member.

Impact: `kmet.libs.sse` polls a queue with a `TimeUnit` and
`kmet.libs.edit-diff` normalizes with a `Normalizer$Form`; writing those as
the portable static-method form (`(TimeUnit/valueOf "MILLISECONDS")`) loaded
fine on babashka and inside an extension's SCI but threw at *load* on Jolt —
`jolt start` died in `kmet.libs.edit_diff`. The reverse spelling is no better:
extension SCI resolves static methods but has no static-field access, so a
field read inside a function body throws there.

Workaround (kmet): read the field once into a top-level `def` initializer —
that code runs on the host and the sandbox receives the value by reference, so
one spelling serves both hosts — and `kmet.libs.test-self-contained` /
`libs-name-no-static-fields-in-sandbox-code` fails a static field anywhere a
sandbox would execute it (mutations checked: `defn` body, a lambda and a
`fn` nested in a `def` initializer all fail by name; a field as the initializer
itself passes). Closed 2026-10-26 by [PR #1435](https://github.com/jolt-lang/jolt/pull/1435)
(merge `4fd288a2`; follow-up
[#1437](https://github.com/jolt-lang/jolt/pull/1437)), verified on the
installed `v0.8.23-154-g8ba11735` dev build: `TimeUnit`, `StandardOpenOption`
and `Normalizer$Form` answer `valueOf`/`values`, `(.isEnum TimeUnit)` is true,
and `Enum/valueOf`/`getEnumConstants` exist. The tag the floor names,
`v0.8.23`, predates the fix, so the entry and the workaround stay live until a
tag carries it.

### [jolt#1453](https://github.com/jolt-lang/jolt/issues/1453) — `data.json` writes non-BMP code points as 5–6 digit `\u` escapes

Jolt strings are code-point based and a Jolt `char` holds a full scalar, so
`clojure.data.json`'s writer — which escapes non-ASCII through a fixed-width
`\u%04x` of the character's integer value — emits 5 or 6 hex digits for
anything above U+FFFF instead of the surrogate pair RFC 8259 requires. BMP
text is unaffected (`é` → `\u00e9`); an astral code point is not (U+1F44D →
`\u1f44d`). A JSON `\u` escape carries exactly four hex digits, so a peer's
parser reads the first four (`1f44`) as the character and the leftover fifth
digit (`d`) as literal text.

Impact, observed end to end: a chat message containing U+1F44D U+1F3FB left
kmet as `\u1f44d\u1f3fb` in a request body and the transcript persisted
`U+1F44 "d" U+1F3F "b"` — silent, not a visible error.

Workaround (kmet): `kmet.libs.json/base-write-opts` passes
`:escape-unicode false`, so payloads go out as raw UTF-8 — valid JSON on every
host, and the escape branch is never entered. `astral-round-trip` in
`kmet.libs.test-json` pins it and fails on Jolt without the option. The option
and the test stay past the fix (raw UTF-8 is also the smaller encoding); the
entry retires when a tag carries a fixed writer.

### [jolt#1454](https://github.com/jolt-lang/jolt/issues/1454) — `data.json` rejects valid surrogate-pair escapes

The read half of the same defect: the reader decodes every `\u` escape through
`char`, which correctly refuses surrogate code units (`(char 0xD83D)` throws
`Value out of range for char: 55357`), and never combines a pair — so valid
JSON that escapes a non-BMP character as `\ud83d\udc4d` cannot be parsed at
all. It also accepts the malformed 5-digit form and splits it into two
characters, which is what turned #1453's output into corrupted data instead of
a loud parse error.

Impact (unmitigated): any peer that escapes astral characters in its JSON —
Python's `json.dumps` default, Jackson, PHP, .NET — makes the parse throw on
Jolt, while payloads carrying raw UTF-8 work, so the failure depends on the
peer's serializer rather than on the data. Provider responses, MCP and tool
results all cross this seam.

Not worked around: kmet keeps `parse-string` as the data.json alias. The
narrow fix, if taken before upstream lands, is a `:jolt`-only pre-pass in
`kmet.libs.json` that recombines surrogate escape pairs while tracking escaped
backslashes; it duplicates parsing logic at the seam and goes away with the
reader fix.

### [jolt#1466](https://github.com/jolt-lang/jolt/issues/1466) — `*file*` is root-relative, and the requirer's on a warm artifact cache

Inside a namespace loaded by `require`, `*file*` is not that namespace's source
path. From source it is the location the loader found — the namespace's name
below its root (`demo2/one.clj`), where the reference's `Compiler.load` binds
the absolute path. On a warm AOT cache it is not the namespace's file at all:
the compiled entrance (`ldr-load-body`'s artifact branch →
`ldr-with-compiled-ns-vars` → `jolt-with-ns-load-vars`) pushes only
`*warn-on-reflection*`, `*assert*` and `*unchecked-math*`, not
`*file*`/`*source-path*`, so the forms run with the *requiring* file's binding.
A top-level `(def file *file*)` therefore answers the absolute path on
babashka/the JVM, `demo2/one.clj` on the first Jolt run and the requiring
script's path on every run after it; purging the cache entry brings the first
value back. Silent in both cases — and `clojure.test` reads `*file*` for its
file reporting, so test locations name the requirer.

Impact (kmet): the extension test helpers derived their extension root from
`*file*`; on a warm cache they pointed at `$HOME` and every fixture path
missed. Workaround (kmet): both extension test utils locate their root by
searching the checkout for their own file instead of trusting `*file*`. Remove
with the fix: `this-file` and `extension-root` in
`extensions/mcp-adapter/test/kmet/extensions/mcp_adapter/test_util.clj` and
`extensions/lsp-adapter/test/kmet/extensions/lsp_adapter/test_util.clj` go, and
`extension-dir` derives from `*file*` again. The `scripts/*.bb` fixtures are
unaffected either way — bb binds `*file*` as the JVM does. The entry retires
when a tag binds `*file*` to the source path on both entrances.

### [jolt#1467](https://github.com/jolt-lang/jolt/issues/1467) — `Path#toUri` answers `File#toURI`'s shape

`Path#toUri` produces what `File#toURI` produces: `file:/x/plain.clj` (no
authority) and `file:/x/ünï%20cödé.clj` (non-ASCII left raw), where the
reference answers `file:///x/plain.clj` and
`file:///x/%C3%BCn%C3%AF%20c%C3%B6d%C3%A9.clj` — empty authority and the path
percent-encoded as UTF-8 bytes, which is what the JVM's `UnixUriUtils.toUri`
builds. `getPath` and `getRawPath` agree between the hosts, so only the string
form differs. The 0.8.9 entry "`Path.toUri` … builds the same object [as
`File.toURI`]" matches this build — the fix landed as stated, and the reference's
`Path.toUri` is not `File.toURI`'s shape.

Impact (kmet): the lsp-adapter's round-trip test compared `(str (.toUri p))`
against `path->uri`. Workaround (kmet): that test's oracle is host-conditional
now (`kmet.extensions.lsp-adapter.lsp-test/path-uri-round-trip`, the
`host/jolt?` branch — the `host` require exists only for it). Remove with the
fix: drop the branch, keep the equality assertion, and drop the `host` require
with it. `path->uri` itself is not a workaround for this and stays: it is
hand-built because the extension's sci sandbox cannot call Path methods at all.
The entry retires when a tag matches the reference on both hosts.

### [jolt#1468](https://github.com/jolt-lang/jolt/issues/1468) — `URI#toASCIIString` leaves non-ASCII unencoded

`toASCIIString` is the method whose contract is "the content of this URI as a
US-ASCII string" and the only correct way to put a URI on a wire; on Jolt it
answers the same string as `toString` — non-ASCII is never percent-encoded, in
any component. `(java.net.URI. "file" nil "/x/ünï cödé.clj" nil)` gives
`file:/x/ünï%20cödé.clj` from both methods, where the reference gives
`file:/x/%C3%BCn%C3%AF%20c%C3%B6d%C3%A9.clj` from `toASCIIString`; a URI built by
parsing (`http://exämple.com/päth?q=ü#frä`) behaves the same in host, path, query
and fragment. The ASCII-illegal set is escaped at construction (`%20`), so the
URI is not entirely unencoded — the non-ASCII class simply never is. RFC 3986
permits only ASCII in a URI, so anything persisted or transmitted (an LSP
`textDocument.uri`, a JSON field, an HTTP request line) carries characters the
protocol says cannot occur; `toURL` is no way around it (it keeps the raw form
on the reference too).

Impact (kmet): none yet — the one place kmet builds a file URI
(`kmet.extensions.lsp-adapter.lsp/path->uri`) percent-encodes by hand for the
sandbox reason above, and nothing in the tree calls `toASCIIString`. Remove with
the fix: nothing today — the entry is a heads-up; if a consumer starts
hand-encoding a URI for this reason before a tag lands, that encoder goes with
it. The entry retires when a tag encodes.

### [jolt#1469](https://github.com/jolt-lang/jolt/issues/1469) — `unload!` leaves the namespaces it unmapped in `*loaded-libs*`

`unload!` unmaps the namespaces a loader owned (it counts them:
`:released {:namespaces n}`) but the runtime's loaded mark survives, so `require`
answers "already loaded" for a namespace `find-ns` answers nil for: a plain
`require` is a silent no-op, and `(require ns :reload)` re-reads the entry
namespace only — its dependencies stay marked and never load, so it comes back
with aliases to namespaces that have no vars (`Unknown class <dep>` on the first
call, or `No such namespace: <dep>` where the dependency resolves at compile
time). The loader docstring notes the mark surviving `remove-ns` for the
*eviction* case and promises `:reload` restores the name; after an unload the
namespaces are unmapped and their cells unreachable, so that does not hold, and
the result is the state `host/chez/loader.ss` names as a defect elsewhere —
"wedged loaded-but-empty for the life of the process and every later require of
it no-op'd".

Impact (kmet): whenever an extension's sources are also on the host classpath
(tests, development), unloading it leaves the host's copies marked loaded but
unmapped, and no later `require` brings them back. Found when the
shipped-extensions check unloaded every extension in the test process and left
the extension suites holding unbound namespace vars. Workaround (kmet): that
check runs in a child host process
(`kmet.app.extensions.test-session/test-shipped-extensions-load-from-src`, now
`^:slow`). Remove with the fix: the check goes back to loading and unloading the
shipped extensions in-process — `shipped-check-probe` and
`shipped-check-results` go, with the `clojure.edn`/`babashka.process` requires
they added, and the var loses `^:slow`. The entry retires when a tag clears the
marks (or ships a documented `forget!`).

