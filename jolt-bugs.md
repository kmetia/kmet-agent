# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix is verified on the Jolt
build kmet runs — normally `main`, ahead of the tags, because the project
builds and tests against the installed build. The `:jolt/min-version` floor is
the last tagged release (`0.8.24`); `v0.8.23` is below what the tree needs,
since the workarounds for the dropped tickets went with their fixes.

Status checked on 2026-11-07 against the installed `v0.8.24` (tagged
2026-11-05, `a9b24d4`). Every ticket this project filed except #1031 is fixed
and its entry dropped: #1433 (PR #1435, follow-up PR #1437), #1453/#1454
(PR #1472), #1466/#1469 (PR #1478) and #1467/#1468 (PR #1477). The release
also carries the `spit` in-place write (`83fe128e`, follow-up `3be07637`):
mode, symlink and hard-link behaviour are verified on the installed build, so
the floor item is closed. Also checked: `jolt-lang/http-client` `v0.1.6`
(`e9e0a39`), now the version pinned by the project — its HTTP, crypto and MCP
SSE suites pass on the installed build.

## Live issues

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
