# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix is verified on the Jolt
build kmet runs — `main`, ahead of the tags — because the project builds and
tests against the installed dev build. The `:jolt/min-version` floor stays on
the last tagged release (`0.8.23`) and rises to `0.8.24` when that tag lands;
in between, a `v0.8.23` Jolt is below what the tree needs, since the
workarounds for the dropped tickets went with their fixes.

Status checked on 2026-11-03 against the installed `v0.8.23-312-g7896ae40` dev
build (`main` HEAD at the check) and the tagged `v0.8.23` (released 2026-10-17,
still the newest tag). Every ticket this project filed except #1031 is fixed on
the dev build and its entry dropped: #1433 (PR #1435, follow-up PR #1437),
#1453/#1454 (PR #1472), #1466/#1469 (PR #1478) and #1467/#1468 (PR #1477).
Also checked: `jolt-lang/http-client` `v0.1.2` (`00455b8`, unchanged; `HEAD` is
that same revision), the version pinned by the project.

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
