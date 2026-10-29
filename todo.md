# Architecture review follow-up

Actionable items from the architecture review. Baseline at review time:
`bb lint` 0 errors / 0 warnings, `bb format-check` clean, `bb check-catalog`
current, targeted runtime/kernel/guard suites green.

## Done

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

### P1.3 — Split the design record — **landed**

Not the `docs/design/` move this item proposed, and no content rewrite:
`new-app.md` is now the design record only — the open seams, the limits that
shipped, the decisions that still constrain a change, § Prior art: DSH, the
"not doing" list — 252 lines instead of 5,225. The landed ledger (every landing,
its gates and the drift found while planning) is deleted; git history holds it.
`run_code.md` (1,029 lines) followed it: the shipped tool design is
`docs/architecture.md` § Tools and the script sandbox, and the evaluator /
process-isolation decision it left open is § Open at HEAD 6 of `new-app.md`.
The current architecture moved into `docs/architecture.md`: composition
(profiles, bundles, rows, patches, layers, presets, extension rows), the service
catalog, the multi-agent server, the event bus, permissions and approvals, the
`:ui` contract, modes, the reload/turn boundary, inspection, and the guard
table. Links were repointed — the AGENTS topic map, `README.md`, `srce.md` and
its README, `docs/profiles.md`, `modules.edn`, and every source docstring that
cited a removed section.

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

## Open — P2

### P2.2 — Watch: the remaining large units

`run-render-loop!` (723 lines), `build-extension-ui-registry` (520),
`build-layout` (433), `agent-handle` (318); 73 of 268 source files exceed 400
lines. Not a defect — the codebase has 4,685 defns and only 34 reach 100 lines
— but these are the onboarding cliff. Extract only when touching them for a
real change.

## Out of scope (recorded)

- **Renaming `srce/get` / `srce/extend`** — dropped: the kernel's vocabulary
  follows Cordis (`ctx.get`, `ctx.extend`, `ctx.provide`, `ctx.isolate`), and
  the §11 parity matrix keeps both names; the Clojure core shadowing is a host
  artifact confined to the kernel's own namespaces.
- **CI for tests/lint/format** — declined; the gates stay local.
- **Extension ABI version** — deferred until after 1.0.0.
- **Splitting `app` and `ui` back into modules** — declined.
- **Deciding the Tk backend's future** — declined.
