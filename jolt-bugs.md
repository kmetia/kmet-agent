# jolt-bugs — open upstream tickets

Every **open** jolt-side ticket whose fix requires a change in kmet or the
removal of a kmet workaround. Closed and unfiled findings are not tracked
here — recent closures (jolt v0.8.8-53 / http-client PR #21): #1011
(`Object.wait`/`notify`), #1007 (streaming HTTP), #1015/#1016
(`StringBuilder` `append`/`insert` char[]), #1017 (stream timeout), #1006
(SCI `copy-var*` host protocol), #1020 (host method arity), #1021
(`String/copyValueOf`), #1022 (`StringBuilder.getChars`). Their workarounds
are removed from kmet alongside this edit. Further closures (jolt PR #1030,
in v0.8.8-64-g705ac0b9): #992 (absolute FILE args), #991 (canonicalize drive
root), #989 (native loader fallback + reconcile). #992/#991 needed no kmet
workaround, so their sections are dropped below; #989 shares its `deps.edn`
block with still-open #990, so the block stays. `jolt-port.md` /
`jolt-tui.md` describe port state without ticket IDs.

**Unfiled but confirmed blockers** (not yet in jolt tracker):
*(none — the IVar gap is now filed as [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031))*

**Workarounds live next to their ticket below.** Each workaround block is the
removal checklist: when an upstream fix lands, delete the listed code (and the
block). Source comments describe the local *why* without ticket numbers —
grep this file to find what `file:line` belongs to which ticket.

Historical labels from the deleted `bb-jolt.md` map as: `JOLT-12`→#947,
`JOLT-13`→#944; git history has the full field reports.

## Open

### [jolt#990](https://github.com/jolt-lang/jolt/issues/990) — `crypto` / `ssl` / `z`: declare `:windows` candidates for the OpenSSL and libz natives

**Area:** `:jolt/native` declarations (`jolt-lang/crypto`, `jolt-lang/jolt-crypto`, `jolt-lang/http-client`)

The libraries kmet resolves for OpenSSL (`io.github.jolt-lang/crypto`, plus
`jolt-lang/jolt-crypto` riding `io.github.jolt-lang/http-client`'s own
deps.edn) and libz (`io.github.jolt-lang/http-client`) still declare only
`:darwin`/`:linux` candidates — the upstream declarations
([crypto#10](https://github.com/jolt-lang/crypto/pull/10),
[http-client#22](https://github.com/jolt-lang/http-client/pull/22)) are open
and the `main` branches carry no `:windows` key (checked 2026-09-16 against
the published `deps.edn` files and the pinned gitlibs checkouts).

The loader half is fixed upstream (jolt PR #1030, in v0.8.8-64-g705ac0b9,
closing jolt#989): a spec with no key for the running platform now falls back
to that platform's conventional spellings (`jolt.ffi/system-library-candidates`),
including a search-directory glob for version-suffixed DLLs on Windows — so
`crypto`/`ssl` resolve via `libcrypto-3-x64.dll` / `libssl-3-x64.dll` with no
app-side help — and same-`:name` specs now reconcile
(`jolt.deps/reconcile-natives`): the winner keeps every key it declares and a
later spec fills in only the missing platform keys. Neither half removes this
block: `zlib1.dll` is deliberately not derivable from `z` (no spelling/glob
fallback can reach it), so `z` still loads only via a declared `:windows`
candidate; and the verbatim `:darwin`/`:linux` mirrors stay until a
minimum-jolt floor is set, since pre-fix jolts still dedup winner-takes-all (a
`:windows`-only overlay there would break macOS/Linux).

**Workaround** (`deps.edn`): the project-level `:jolt/native` block declaring
`crypto`/`ssl`/`z` — the upstream `:darwin`/`:linux` candidate lists mirrored
verbatim (still mandatory for pre-fix jolts, harmless on fixed ones) plus the
Windows names jolt's own Maven fetcher already uses
(`stdlib/jolt/mvn_http.clj` crypto-names / ssl-names): `libcrypto-3-x64.dll`
(+ `-3.dll`, `-1_1-x64.dll` fallbacks), `libssl-3-x64.dll` (+ fallbacks),
`zlib1.dll` (+ `z.dll`). The DLLs are environment, not repo code — Git for
Windows supplies them (on PATH), and the loader also finds them beside
`jolt.exe` (executable-directory search). Removal: bump the
`io.github.jolt-lang/crypto` and `io.github.jolt-lang/http-client` git shas to
revisions carrying the `:windows` keys, delete the `:jolt/native` block, and
re-run `jolt -e '(println :ok)'` on Windows with the DLLs present.

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI `IVar` protocol missing `:getRawRoot` for `clojure.lang.Var`

**Area:** SCI vendored in jolt (`sci.impl.vars`), jolt's `clojure.lang.Var` shim

All shipped extensions fail to load on jolt with:
```
No implementation of method: :getRawRoot of protocol: #'sci.impl.vars/IVar
found for class: clojure.lang.Var
```
This is distinct from jolt#1006 (which was about `defrecord` over injected
host protocols — that one is closed). The `IVar` protocol in vendored SCI
0.13.53 expects `:getRawRoot` on `clojure.lang.Var`, but jolt's Var shim
doesn't implement it. This blocks mcp-adapter, lsp-adapter, review, and
clojure extensions on jolt.

**Workaround — retired on Jolt (2026-09-16).** Extension contexts no longer
run under SCI there: `kmet.app.extensions/create-loader` builds them on the
runtime's native loader (jolt#1039, loader.md §6.1), so the shipped
extensions share host protocols by reference instead of copying them into an
SCI env, and the `^:bb-only` gate on `test-shipped-extensions-load-from-src`
is gone (the clojure extension still stays bb-side, but for its own deps
closure — deps.edn excludes rewrite-clj, which bb bundles and Jolt does not).
The SCI shape itself is unchanged for any SCI-on-Jolt use. Note (2026-09-16):
upstream re-diagnoses this in PR jolt#1033 — `sci/copy-var*` never calls
`getRawRoot`; the failure is the missing embedder-side `IVar` extension, and a
host protocol copied into SCI as-is fails identically on JVM Clojure 1.12.5,
so no jolt-side fix unblocks that shape. #1033 makes the extension writable
(Var surface: `toSymbol`, `ns`/`sym`, `unbindRoot`, `set`, `fn`) but both
hosts then land on `Unable to resolve symbol` — the supported sharing shape is
the multimethod recipe in `test/chez/sci-functional-test.clj`.


