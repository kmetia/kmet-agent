# Architecture review follow-up

Actionable items from the architecture review. Baseline at review time:
`bb lint` 0 errors / 0 warnings, `bb format-check` clean, `bb check-catalog`
current, targeted runtime/kernel/guard suites green.

## Done — P0

### P0.1 — Bound and relocate diagnostics logs — **landed**

`kmet.app.debug` now writes `kmet-debug.log` / `kmet-error.log` under
`kmet.log.dir` (the test runner's isolation), `KMET_LOG_DIR`, else `logs/`
under the process cwd; the files rotate to `.1` at `max-log-bytes` (5 MiB),
the error log stays always-on but bounded, and the first write failure is
reported once on stderr. `kmet.tasks.runner` points a test run at
`target/test-logs`, so the suite never accumulates diagnostics in the
checkout's `logs/`. Tests: `kmet.app.test-debug`, `kmet.libs.test-log`.
Docs: `docs/usage.md`, `tui.md`, `srce.md`.

### P0.2 — Retire the test-only session engine — **landed**

`kmet.app.session.driver` left `src` (the tests used it through a fixture at
first; P2.3 deleted the fixture once the tests drove handles). The builtin
commands are handle-only (`:send!` / `:request-compaction!`); an engine
without either reports the front end cannot do the op instead of falling
through to a driver. `kmet.test-layer-guard` now fails on any module source
requiring the retired namespace. Stale pointers in `runtime.clj`,
`lifecycle.clj`, `submission.clj`, `extensions/core.cljc`, `deps.edn`,
`docs/architecture.md` and `new-app.md` were updated.

### P0.3 — Only entry points exit — **landed**

The TUI throws `:kmet.app/unknown-session` instead of exiting (the mode row
reports exit 1), and the headless extension context's `:shutdown` is a no-op
(the TUI installs its own). `kmet.test-process-exit-guard` scans every module
source and allows `System/exit` only in `kmet.app.core` and the two model
generators' `-main`s.

### P0.4 — No upward resolution from `kmet.libs` — **landed**

`kmet.libs.log` is the lower layers' sink (silent until installed);
`kmet.app.debug/enable!` installs its `log` and `disable!` clears it.
`kmet.libs.concurrent`, `kmet.libs.fs` and `kmet.libs.host` use it instead of
`requiring-resolve 'kmet.app.debug/log`, and
`kmet.libs.test-self-contained` now fails on a literal upward
`requiring-resolve`. The namespace joined the shared extension library list.

### P0.5 — Stray tracked file — **landed**

`modules/ai/src/kmet/ai/llm.clj.bak` is removed, and `.gitignore` covers
`*.bak` / `*.orig` / `*.rej`.

### P2.3 — Shrink the session-engine fixture — **landed**

The test-only engine is gone. `kmet.app.test-session-runtime` now drives a
real handle from the composition's `:agents` factory
(`support/create-agent-handle!`): 29 tests cover the accessors, run options,
compaction queue, model/settings ops, branch summary, session dirs, app-wide
registries and tool hooks. The engine-only tests went with the engine —
bootstrap options, subscriber lists, the engine UI-request pair,
`resolve-run-session`, `wait-idle!`, one-builder and subscription-outlives.
`test-runtime-capabilities` was deleted (its queue/settle case moved into the
handle suite; the running-agent capability is covered by `loop/test-driver`),
`test-ui-port` keeps only the front-end binding tests, `test-import` calls
`lifecycle/apply-session-cwd!`, and `kmet.test-utils/test-engine` builds only
the `Engine` record the mode tests need. The fixture file
`modules/app/test/kmet/app/session/test_engine.clj` is deleted; the layer
guard stays as the retirement belt.

Verification: `bb test` 4,348 tests / 26,453 assertions, `bb test-ext` 139 /
709, `bb lint` 0/0, `bb format-check` clean, `bb check` compiles all source
namespaces.

## Open — P1

### P1.1 — Put the shipped adapters under a gate

`extensions/mcp-adapter` is 5,268 lines across 21 files and
`extensions/lsp-adapter` is 1,831 lines; neither has a `test/` tree, and neither
is in `modules.edn`'s extension test projects. They ship `scripts/*.bb` with
fake servers (`validate-all.bb`, `validate.bb`), but nothing in `bb.edn` or the
runner invokes them.

Preferred: add `test/` trees and register both projects in `modules.edn`
extensions (plus the root `bb.edn` / `deps.edn` paths), so `bb test` discovers
them like clojure/tree-sitter/review. Minimum: a `bb test-adapters` task that
runs each adapter's validate script against its fake server, documented in the
dev loop. MCP coverage first: modern vs legacy era negotiation,
`x-mcp-header` filtering, cache-hint invalidation, OAuth flow, config parsing.

### P1.2 — Extend the process-state ledger below the app

`modules/app/test/kmet/app/test_process_state.clj` audits `modules/app/src`
only, but `kmet.ai` holds 15 `defonce` cells (`models.clj:116,120,463,464`,
`auth.clj:108`, `image_models.clj:42,93`, caches and credential tails, …).
Add the `kmet.ai` tree (and optionally `tui`/`tk`) to the ledger, each cell
with a written reason and a note on how a test resets it. Follow-up: give the
registries explicit reset APIs so tests can stop using `with-redefs`.

### P1.3 — Split the design record

`new-app.md` is 5,225 lines / 374 KB; `perf.md` is 2,667 lines and
`run_code.md` 1,028 — roughly 10k lines of root notes against 3,654 lines of
user docs. Move to `docs/design/`: a short `index.md` for current decisions and
`history/landings.md` for the append-only ledger; keep `docs/architecture.md`
the current map and update the AGENTS topic map and cross-links. Mechanical
move (`git mv`) plus link fixes — no content rewrite.

## Open — P2

### P2.1 — Rename `srce/get` / `srce/extend`

`modules/srce/src/kmet/srce/core.clj:11` excludes `get` and `extend` from
`clojure.core`; 44 namespaces require `srce` and 11 already add the exclusion.
Add `service` / `extend-ctx` as the canonical names, keep the old names as
aliases for one cycle (or do one mechanical pass pre-1.0.0), and update the
kernel docs and tests.

### P2.2 — Watch: the remaining large units

`run-render-loop!` (723 lines), `build-extension-ui-registry` (520),
`build-layout` (433), `agent-handle` (318); 73 of 268 source files exceed 400
lines. Not a defect — the codebase has 4,685 defns and only 34 reach 100 lines
— but these are the onboarding cliff. Extract only when touching them for a
real change.

## Out of scope (recorded)

- **CI for tests/lint/format** — declined; the gates stay local.
- **Extension ABI version** — deferred until after 1.0.0.
- **Splitting `app` and `ui` back into modules** — declined.
- **Deciding the Tk backend's future** — declined.
