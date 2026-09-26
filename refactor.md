# Refactor plan: decompose the god files

Goal: reduce the maintenance cost of kmet's largest source files without
touching behavior. The layer architecture (`libs → ai/tui → app → modes →
core`) is sound — the namespace graph is acyclic and the documented boundary
rules hold with zero violations. The problem is under-decomposition inside a
handful of files, not the layers themselves.

This plan deliberately excludes: `libs/*`, `app/ui/*` components, `loader/*`,
`ai/api/*` wire builders, and generated catalogs. They were reviewed and are
cohesive as-is.

## Ground rules

- **Behavior-preserving only.** These are moves, not rewrites. Do not combine
  a task with feature work or port alignment work.
- **Never violate the layer boundaries** in `src/kmet/README.md`. Extracted
  namespaces keep the layer of their parent (`kmet.modes.*`, `kmet.app.*`,
  `kmet.tui.*`).
- **Keep the require graph acyclic.** New sub-namespaces may require "down"
  (shared state/helpers) never back up into the file being split.
- **Keep public API stable.** `kmet.core` requires `kmet.modes.interactive`,
  `kmet.app.loop`, `kmet.app.extensions`; tests reach into some namespaces
  directly. Keep the original namespace as the entry point / facade until all
  consumers migrate.
- **One task = one commit series.** Run after every move:

  ```sh
  bb test-changed
  bb lint-changed
  bb format-check-changed
  ```

  Full gates (`bb test`, `bb test-ext`, `bb lint`, `bb format-check`) after a
  task completes, per the project workflow.
- **Update the topic docs in the same change** (AGENTS.md requirement):
  `src/kmet/README.md` project tree, `src/kmet/tui/tui.md` for TUI changes,
  `src/kmet/loader/loader.md` only if loader-adjacent.
- **Check for private-var references before moving anything:**

  ```sh
  grep -rn "#'kmet.modes.interactive/" test/
  grep -rn "#'kmet.tui.core/" test/
  grep -rn "#'kmet.app.loop/" test/
  grep -rn "#'kmet.app.extensions/" test/
  ```

  Known hit: `test/kmet/app/test_loop.clj:1042` derefs
  `#'kmet.app.loop/block-message-images` — re-home or export it explicitly.

## Baseline (measured)

| File | Lines | Defs | Churn (last 300 commits) | Verdict |
|---|---|---|---|---|
| `modes/interactive.clj` | 5,177 | ~200 private | 45 | god orchestrator |
| `app/extensions.cljc` | 2,755 | ~90 + 16 module atoms | 38 | mostly one concern + SCI machinery |
| `tui/core.clj` | 2,993 | 262 (134 aliases) | 33 | 4 concerns + facade |
| `app/loop.clj` | 2,530 | 89 | 14 | engine + policy clusters |
| `app/session.clj` | 1,457 | 88 | — | cohesive, leave |
| `app/packages.clj` | 1,413 | 98 | — | cohesive, leave |
| `app/ui.clj` | 133 | 60 aliases | — | inconsistent facade (T5) |

Suggested execution order: **T3 → T5 → T1 (staged) → T4 → T2**. T3 is a small
warm-up that proves the workflow; T1 is the highest-value move; T2 is the
riskiest because input and render state interlock and should go last.

---

## T1 — Split `src/kmet/modes/interactive.clj` (5,177 lines)

### Why

The hottest file in the repo (45 changes / 300 commits), the highest fan-out
(61 required namespaces), and six unrelated responsibilities in one namespace:
session/CLI resolution, OAuth login flows, command registration, session
administration (resume/import/tree/fork), timers/status, turn submission and
agent callbacks, layout construction, and the extension UI registry. Every
change risks conflicts and requires loading the whole file to navigate.

### Current state

Five top-level forms (`ns`, two config labels, `CoreState`, `run`), ~200
private `defn-`s, 24 comment-delimited sections:

| Lines | Section | Suggested home |
|---|---|---|
| 85–88 | Global config ref | `state` |
| 89–214 | Session helpers | `state` |
| 215–243 | Core state (`CoreState`) | `state` |
| 244–336 | Formatting helpers | `state` |
| 337–436 | Command handling | `commands` |
| 437–516 | Session info (/session) | `commands` |
| 517–592 | Share (/share) | `commands` |
| 593–772 | Login/logout | `auth` |
| 773–1006 | Provider options | `auth` |
| 1007–1575 | Builtin command registration + `/reload` | `commands` |
| 1576–2015 | Resume session | `session-admin` |
| 2016–2120 | Import (/import) | `session-admin` |
| 2121–2354 | Session tree navigation | `session-admin` |
| 2355–2456 | Fork / clone | `session-admin` |
| 2457–2522 | Animation timer | `status` |
| 2523–2630 | Status indicator swap | `status` |
| 2631–2834 | Pending messages display | `status` |
| 2835–2922 | Agent response handler | `turn` |
| 2923–3043 | Submit handler | `turn` |
| 3044–3408 | Message submission | `turn` |
| 3409–3410 | External editor (empty stub header) | drop or `turn` |
| 3411–3454 | Loaded resources | `layout` |
| 3455–4292 | Layout setup | `layout` |
| 4293–5031 | Extension UI registry | `ui-registry` |
| 5032–5177 | `run` | `interactive` (entry) |

### Proposed target

```
kmet/modes/interactive.clj            entry: run + wiring (keep public API)
kmet/modes/interactive/state.clj      CoreState, global config, session-dir and
                                      formatting helpers, mount/close-selector!
kmet/modes/interactive/auth.clj       login/logout + provider options
kmet/modes/interactive/commands.clj   builtin registration, /reload, /session,
                                      /share, help/tools text
kmet/modes/interactive/session_admin.clj  resume/import/tree/fork/clone
kmet/modes/interactive/status.clj     animation timer, status indicator,
                                      pending-messages display
kmet/modes/interactive/turn.clj       submit handler, message submission,
                                      agent callbacks
kmet/modes/interactive/layout.clj     loaded resources + layout build
kmet/modes/interactive/ui_registry.clj  extension UI registry impls
```

Dependency direction: `interactive` (entry) → all modules; `commands` and
`session-admin` → `auth`/`status`/`turn` as needed; everything may require
`state`. No module may require `kmet.modes.interactive`.

### Migration steps

1. **Pre-step — inventory shared private helpers.** Grep for cross-section
   usage before moving: `mount-selector!`/`close-selector!` (lines 600–613,
   used by auth and every selector flow), session helpers, formatting helpers,
   and the `global-config` atom. These move to `state` first, as a standalone
   commit, without changing call sites.
2. Move `state` (sections 85–336). Compile + tests. This alone shrinks the
   entry file by ~250 lines.
3. Move `auth` (593–1006). It is the most self-contained cluster (~415 lines)
   and exercises the shared-selector seam.
4. Move `status` (2457–2834, ~380 lines) and `turn` (2835–3408, ~575 lines).
   The two share callbacks (`on-agent-*` sets state read by the status
   driver) — verify the shared atoms live in `state`/`CoreState` before moving.
5. Move `session-admin` (1576–2456, ~880 lines). Its handlers reference
   status/turn helpers; add requires downward.
6. Move `commands` (337–592 + 1007–1575, ~825 lines) and `layout` +
   `ui_registry` (3411–5031, ~1,620 lines). `layout`/`ui_registry` are large
   enough to stage separately; if `layout` remains >800 lines, split "layout
   construction" from "overlay mounting" along the section comments.
7. Trim `interactive.clj` to `run` + the requires; keep every public var that
   existed on `kmet.modes.interactive` as a re-export (`def`) if any external
   consumer or test still reaches it.

### Risks / traps

- **`CoreState` is referenced everywhere.** Keep it in `state` and resist
  adding fields "while we're here".
- **Forward references between handler groups.** `register-builtin-commands!`
  (in `commands`) references handlers defined in `auth`, `session-admin`,
  `status`, `turn` — that is fine, but the reverse requires must not exist.
- **Closure capture.** Layout code closes over live components; moving it
  must preserve the exact construction order. Move code verbatim; do not
  untangle while relocating.
- `modes/print.clj` shares nothing with interactive except app-layer code —
  no impact.

### Verification

`bb test-changed` covers `test/kmet/modes/test_interactive.clj`,
`test/kmet/app/test_interactive_ui.clj`. Update `src/kmet/README.md` project
tree after the split.

---

## T2 — Split `src/kmet/tui/core.clj` (2,993 lines)

### Why

Four concerns in one library file: overlay stack/layout, the frame
diff/render engine, ANSI/Kitty input normalization, and lifecycle/facades.
33 changes / 300 commits. 134 of its tail defs are re-export aliases, so the
real logic is ~2,450 lines.

### Current state

| Lines | Section | Suggested home |
|---|---|---|
| 39–59 | Protocol re-exports | `core` (keep) |
| 60–115 | Cursor, CSI 2026 sync | `core` (keep) |
| 116–290 | TUI record + flashes | `core` (keep) |
| 304–681 | Overlays: layout, visibility/focus, handle, chrome | `overlays` |
| 682–814 | Diff + Kitty image diff support | `render` |
| 815–873 | Crash + debug logs | `core` (keep) |
| 874–1190 | Input reader: Kitty negotiation, terminal responses | `input` |
| 1190–1613 | Input buffer: timeouts, dispatch lock | `input` |
| 1614–1940 | Input trace, Kitty duplicates, paste detection, normalization | `input` |
| 1941–2075 | Start / Stop | `core` (keep) |
| 2076–2780 | Render loop (`run-render-loop!`) | `render` |
| 2781–2993 | Re-exports (134 defs) | `core` (keep) |

### Proposed target

```
kmet/tui/overlays.clj   overlay stack, layout resolution, visibility/focus
                        state machine, OverlayHandle, default chrome (~380)
kmet/tui/render.clj     diff, kitty image diff, run-render-loop! (~950)
kmet/tui/input.clj      reader, buffer, lock, trace, kitty/paste
                        normalization, terminal-response interception (~1,070)
kmet/tui/core.clj       TUI record, cursor/CSI, flashes, crash logs,
                        start/stop/suspend/resume, public facade (keep)
```

### Migration steps

1. Extract `overlays` first (leaf cluster, ~380 lines). It only needs the
   `tui` record fields, which are keyword accessors — no function moves.
2. Extract the diff + Kitty-image helpers (~130 lines) into `render`, keeping
   `run-render-loop!` in `core` for now. This is the smallest safe step.
3. Extract `input` (~1,070 lines). It is the riskiest: `dispatch-input!`, the
   render loop, and `tui-suspend!` share the dispatch lock and per-TUI atoms.
   Move the pure normalization helpers (trace, kitty duplicates, paste
   detection, unit normalization) first, then the reader/buffer, then the
   lock ownership as a final step with the render-loop move.
4. Move `run-render-loop!` into `render` only after input is out; keep
   `tui-start`/`tui-stop` in `core` calling into it.
5. Keep `core` as the facade: every symbol currently re-exported stays
   re-exported (the 134 aliases are the stable public API). Alternatively add
   `kmet.tui.api` for aliases — do not do both in one task.

### Risks / traps

- **Lock interaction.** `acquire-dispatch-lock!` semantics (lines 1210–1223,
  1614 for trace) are documented in comments; move comments with code.
- **Per-TUI atom key names** are the de-facto interface between input and
  render (`:previous-lines`, `:previous-normalized-in/out`, etc.). Moving
  both in separate commits would hide breakage; keep one commit per
  cross-file atom or add a small accessor namespace.
- `tui.md` documents module-level behavior — update it with the new files.
- Tests: `test/kmet/tui/test_render_loop.clj` and related — run
  `bb test-changed` after each step.

---

## T3 — Extract retry + loop guard from `src/kmet/app/loop.clj` (2,530 lines)

### Why

The engine file mixes four policy clusters with orchestration. Retry
classification and the repeat-loop guard are nearly pure helpers (error
regexes, backoff, thinking-loop detection) and are the most test-able,
most-tweaked parts.

### Current state (anchors)

- Retry/policy: `non-retryable-error-regex` (436), `retryable-error-regex`
  (451), `overflow-error-regex` (511), `non-overflow-error-regex` (542),
  `retryable-error?` (552), `context-overflow?` (562), `backoff-sleep!`
  (1723), `llm-total-timeout-ms` (1737), `normalize-llm-result` (1754),
  `retry-decision` (1768).
- Loop guard: `loop-guard-exempt-tools` (577), `loop-guard-reset-tools`
  (583), `loop-guard-signature` (596), `canonical-json` (610),
  `thinking-loop-error`/`min-span`/`delimiters`/`ws` (632–652),
  `split-thinking-segments` (656), `thinking-loop?` (679),
  `loop-guard-filter` (695), `loop-guard-give-up!` (731).

### Proposed target

```
kmet/app/retry.clj       error tables + retryable-error? + context-overflow?
                         + backoff-sleep! + llm-total-timeout-ms +
                         normalize-llm-result + retry-decision (~250 lines)
kmet/app/loop_guard.clj  exemptions/reset tables + signature + canonical-json
                         + thinking-loop detection + loop-guard-filter
                         (~220 lines)
kmet/app/loop.clj        AgentState, queues, tool scheduling, LLM call,
                         compaction, run-agent-turn (unchanged content)
```

### Migration steps

1. Move the pure functions first: error regexes + predicates +
   `normalize-llm-result` + `loop-guard-signature` + `canonical-json` +
   thinking-loop detection + `loop-guard-filter`. These take plain values.
2. Reshape the two stateful helpers instead of moving the agent coupling:
   - `backoff-sleep!` reads `(:signal agent)` — change to
     `(backoff-sleep! signal delay-ms)` at the new site, with the loop
     passing `(:signal agent)`. `llm-total-timeout-ms` reads `(:cfg agent)` —
     pass the cfg map.
   - `loop-guard-give-up!` resets `(:loop-guard agent)` and calls `emit` —
     keep it in `loop.clj` (it is orchestration), calling the moved pure
     helpers. Do not move `emit`.
3. Update `loop.clj` requires and the call sites; run tests.
4. Optional phase 2 (only after T1/T2 establish the workflow): extract tool
   scheduling (`execute-tool-calls-parallel!`/`-sequential!`,
   `await-all-tool-results!`, ~250 lines) into `kmet.app.tool-scheduler`.
   It depends on `emit` + session writes, so it is less clean; skip unless
   the file still feels crowded.

### Risks / traps

- Tests reach `#'kmet.app.loop/block-message-images`
  (`test/kmet/app/test_loop.clj:1042`) — out of scope here, but grep for
  `#'kmet.app.loop/` before moving anything else.
- Keep `run-agent-turn` the single public entry; make new ns vars public only
  where `loop.clj` needs them.

### Verification

`bb test-changed` (covers `test/kmet/app/test_loop.clj`), plus
`bb lint-changed`. No doc update needed beyond the `src/kmet/README.md` tree
if files move.

---

## T4 — Split `src/kmet/app/extensions.cljc` (2,755 lines)

### Why

The second-hottest code file (38 changes / 300 commits) contains three layers
of machinery: runtime registries/state (16 module-level atoms), the extension
API construction, and ~2,000 lines of isolated SCI/classpath/shared-context
machinery. Only the third is genuinely large and rarely changed.

### Current state

| Lines | Section |
|---|---|
| 81–119 | Provider-event bridges |
| 120–151 | Extension records + registries (the 16 atoms) |
| 152–227 | Input / before-agent-start hooks |
| 228–265 | Renderers + tool hooks |
| 266–316 | Shortcuts + markdown transformers |
| 317–345 | CLI flags |
| 346–444 | UI registry |
| 445–486 | Agent control |
| 487–579 | Model / session facades |
| 580–794 | Extension API construction |
| 795–1972 | Isolated extension contexts (sci): per-extension loaders, jars, classpath, source eval |
| 1973–2755 | Shared base context + load/unload/discover/reload entry points |

### Proposed target

```
kmet/app/extensions.cljc          public entry + registries + hooks +
                                  renderers + flags + ui registry + agent
                                  control + model/session facades +
                                  API construction (keep public fns here)
kmet/app/extensions/context.cljc  private SCI context machinery: per-extension
                                  loaders, jar/artifact resolution, class
                                  registration, source eval, shared base
                                  context (~1,900 lines)
```

If `extensions.cljc` still exceeds ~1,200 lines after that, a second split
along line 580 gives `kmet.app.extensions.api` (API construction) but treat
that as optional.

### Migration steps

1. Identify the public entry points that must stay in `kmet.app.extensions`
   for tests and callers: `load-extension!`, `load-extension-descriptor!`,
   `unload-extension!`, `unload-all-extensions!`, `get-loaded-extensions`,
   `discover-resources!`, `clear-extensions!`, `registered-extensions`,
   `extension-artifact-paths`, `load-extension-paths!`,
   `load-extension-descriptors!`, `load-extensions-from-dir`,
   `reload-extensions!` (lines ~2512–2755). Keep these; have them delegate
   into `context`.
2. Move the private machinery from 795–2511 into `context`, threading state
   explicitly. The 16 module atoms stay put as the shared registry — they are
   the deliberate runtime registration tables (AGENTS.md). Do **not**
   consolidate them into one application-database atom.
3. Keep `.cljc` reader conditionals intact (`#?(:jolt ...)`); both lint views
   must pass: `bb lint-changed` runs babashka + jolt.
4. Check the adjacent namespaces (`app/bundled_extensions.clj`,
   `app/extension_libs.clj`, `loader/sci_loader.clj`) for required private
   helpers before moving.

### Risks / traps

- SCI context construction depends on `kmet.loader.*`; the new `context.cljc`
  may require loader namespaces but must not require app/modes/tui layers
  beyond existing allowances (`app` may require `loader`).
- Jolt guarded requires: follow the existing `#?(:jolt ...)` pattern for
  `loader/jolt-loader`.
- Tests: `test_extensions.clj`, `test_extensions_ui.clj`,
  `test_bundled_extensions.clj` — run the changed-file task after each step.
- Update `src/kmet/extension.md` (authoritative doc for this area) and the
  `src/kmet/README.md` tree.

---

## T5 — Resolve the `src/kmet/app/ui.clj` facade inconsistency (133 lines)

### Why

`kmet.app.ui` is documented as "re-exports for all agent UI components" but
re-exports only **13 of 34** child namespaces. 40 source files require
children directly; 5 require the facade — and 4 of those 5 are themselves
children (`external_editor`, `settings_selector`, `fork_selector`,
`tree_selector`), producing two idioms for the same thing.

### Current pattern (measured)

- Re-exported: `assistant-message`, `bash-execution`, `chat-history`,
  `custom-message`, `footer`, `footer-data-provider`, `loaded-resources`,
  `model-selector`, `pending-messages`, `scoped-models-selector`,
  `status-indicator`, `tool-execution`, `user-message`.
- Not re-exported (21): `auth-selector`, `custom-dialog-adapter`, `dialogs`,
  `dock`, `external-editor`, `fork-selector`, `hotkeys`, `image-block`,
  `login-dialog`, `model-catalog`, `resource-config`, `session-selector`,
  `settings-selector`, `settings-submenu`, `skill-message`, `subs`,
  `summary-message`, `theme-submenu`, `thinking-selector`, `tool-renderers`,
  `tree-selector`.
- `modes/interactive.clj` uses the `ui/` alias ~309 times.

### Decision

Prefer **dropping the facade**: direct child requires are already the majority
idiom (40 files), and the facade adds an indirection hop without preventing
anything. This folds naturally into T1 — while splitting `interactive.clj`,
give each new module its direct child aliases (`chat-history`, `footer`, …)
instead of `ui/…`.

Alternatives, if the facade is kept: complete it (add ~21 alias blocks, or
better, a small macro/`import-vars`-style helper) and migrate the 4 child
consumers to it, then require it consistently. Do not leave it half-covered.

### Migration steps (drop variant)

1. In `modes/interactive.clj` (already scheduled to split in T1), replace
   `ui/` call sites section by section as each section moves to its new
   namespace, adding only the child requires that section needs.
2. Migrate the 4 child consumers of `kmet.app.ui` to direct requires and
   delete `app/ui.clj`.
3. Update `src/kmet/README.md` (remove `app/ui.clj` from the tree).

If executed independently of T1, step 1 is a mechanical alias replacement in
one file; still do it as its own commit to isolate review noise.

### Risks / traps

- Deleting a namespace that tests or extensions might require: grep
  `kmet.app.ui\b` across `src/`, `test/`, `extensions/` before removal.
- The facade is not on any extension contract path (extensions get
  `kmet.extension`, not `kmet.app.ui`) — verify before deleting.

---

## Progress checklist

- [x] T3 — `app.retry` + `app.loop_guard` extracted; loop tests pass
- [x] T5 — `app.ui` facade decision executed; tree updated
- [x] T1 — `interactive.clj` reduced to `run` + wiring (150 lines);
      modules: `state`, `auth`, `status`, `turn`, `session_admin`, `commands`,
      `resources`, `layout`, `ui_registry`
- [ ] T4 — `extensions.cljc` split; `extension.md` updated
- [ ] T2 — `tui/core.clj` reduced to lifecycle + facade; `tui.md` updated
- [ ] Full gates after the last task: `bb test`, `bb test-ext`, `bb lint`,
      `bb format-check`
