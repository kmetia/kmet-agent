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

## Floor review on the next tag

Not a ticket — a floor item, to be reviewed and raised with `:jolt/min-version`.
At `v0.8.23`, `spit` stages `<path>.spit-tmp-*` and renames it over the target,
so the file takes the temp's `0666 & ~umask`: a script edited through a tool
comes back 0644, a 0600 secret 0644, and a symlinked target is replaced by a
regular file while a hard link stops sharing its data. Dev `main` fixes all
three — `83fe128e` *Write spit's target in place, as FileOutputStream does*
(2026-10-21) — and that commit sits in no tag, so the floor still carries the
behaviour. On a `v0.8.23` build:
`chmod 755 f && jolt -e '(spit "f" "x")' && stat -c %a f` answers `644`.

kmet does not work around it: `kmet.app.tools.write`, `kmet.app.tools.edit` and
the clojure extension's `clojure_edit`/`clojure_paren_repair`/format all write
through `spit`, so on a floor Jolt they drop the bit (babashka and the JVM write
in place and are unaffected). When `0.8.24` is tagged, raise `:jolt/min-version`
in `deps.edn` and `jolt/deps.edn` — plus the places that name the floor
(`docs/building.md`, `test/kmet/tasks/build_jolt_test.clj`,
`jolt/src/jolt/kmet/README.md`) — and refresh the `kmet.libs.fs` ns docstring:
its "Why not spit? Jolt's spit is itself atomic" argument and the `jolt#1263`
rename retry both describe the pre-`83fe128e` `spit`.
