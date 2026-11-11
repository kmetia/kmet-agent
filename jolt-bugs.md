# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix is verified on the Jolt
build kmet runs — normally `main`, ahead of the tags, because the project
builds and tests against the installed build. The `:jolt/min-version` floor is
the last tagged release (`0.8.26`); older tags are below what the tree needs,
since the workarounds for the dropped tickets went with their fixes.

Jolt tickets were last checked on 2026-11-07 against `v0.8.24` (tagged
2026-11-05, `a9b24d4`). Every earlier ticket this project filed except #1031 is
fixed and its entry dropped: #1433 (PR #1435, follow-up PR #1437), #1453/#1454
(PR #1472), #1466/#1469 (PR #1478) and #1467/#1468 (PR #1477). The release
also carries the `spit` in-place write (`83fe128e`, follow-up `3be07637`):
mode, symlink and hard-link behaviour are verified on the installed build, so
the floor item is closed.

`jolt-lang/http-client` `v0.1.7` (`dcc4822`) is now the version pinned by the
project; its HTTP, crypto and MCP SSE suites pass on the installed build.

## Live issues

### [jolt-lang/http-client#46](https://github.com/jolt-lang/http-client/issues/46) — `jolt.http.tls` shadows `clojure.core/chunk`

Open (filed 2026-11-11). Requiring `jolt.http.tls` prints
`WARNING: chunk already refers to: #'clojure.core/chunk in namespace:
jolt.http.tls, being replaced by: #'jolt.http.tls/chunk`. The cause is
`(def ^:private chunk 16384)` (`src/jolt/http/tls.clj:70`), which shadows the
`clojure.core` refer. Every process that opens a TLS connection prints it,
including `jolt run -m ... -p` output, where it mixes into the result; there is
no functional failure. Renaming the private constant to `chunk-size` (or
`(:refer-clojure :exclude [chunk])`) fixes it. Affects v0.1.7 (`dcc4822`, the
current pin) and v0.1.6 (`e9e0a39`), so the pin bump does not clear the
warning until the fix lands upstream.

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open (last upstream update 2026-10-09), as is the upstream
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) itself (no
update since 2026-09-16). It affects extensions that choose the SCI loader
backend on Jolt and copy or implement host protocols; shipped extensions
declaring `:jolt` use Jolt's native loader and are not blocked, which is why
`test-shipped-extensions-load-from-src` runs on both hosts. `jolt/deps.edn` and
`modules/app/deps.edn` pin yogthos/sci at `1295142` (the #1093 branch) for the
`:sci` fallback. Keep the pin until the change is released by Jolt; v0.8.24
vendors SCI at `32d62a5`, which the #1093 branch includes, so the pin still
applies.

### [jolt#1548](https://github.com/jolt-lang/jolt/issues/1548) — Array `seq` is eager

Open (filed 2026-11-08; reproduces on the installed
`v0.8.24-75-gac3934db`). `seq` on a Jolt array eagerly materializes the whole
array through `ja->list` + `list->cseq/k`
(`host/chez/java/natives-array.ss:820`) instead of wrapping it, at ~107 bytes
per element, so `(take 4 huge-byte-array)` allocates in proportion to the
entire array; a 94 MB array projects to ~10 GB. With the default ceiling this
raises `OutOfMemoryError` rather than returning the four elements. Strings
(`seq.ss:653`), vectors and maps are already lazy, so this is also a JVM-parity
gap (`clojure.lang.ArraySeq` is O(1) at creation).

This is what ended the 2026-11-08 jolt session: an eval did
`(take 4 (fs/read-all-bytes "~/bin/kmet"))` on the 94,210,175-byte binary and
the in-progress conversion hit the 2,983,138,304-byte heap ceiling at live
2,987,243,856. It is not a kmet leak and no shipped kmet path is affected —
`read` sniffs images with `alength`/`aget` — but eval or extension code that
seqs a large array is exposed. Workaround: use index access (`aget`, `nth`,
`count`) rather than `take`/`first`/`map`/`vec` on large arrays.
