# kmet developer guide

This file documents the application package and its contributor workflow.
Implementation-specific references live beside their namespaces, not in the
user-facing `docs/` directory: see [`tui/tui.md`](tui/tui.md),
[`loader/loader.md`](loader/loader.md), [`extension.md`](extension.md),
[`development/pi-alignment.md`](development/pi-alignment.md). Temporary
analysis notes (`script.md`, `perf.md`, `jolt-bugs.md`, and `jolt-port.md`)
remain at the repository root by convention.

## Project structure

```
src/kmet/
├── core.clj            — CLI entry, arg parsing, mode dispatch
├── config.clj          — Configuration loading (settings.edn, env vars)
├── debug.clj           — Debug/error logging
├── extension.clj       — the extension contract root (`kmet.extension`)
├── package_manager.clj — the install/remove/list/config CLI
├── libs/               — Generic, self-contained helpers (edit diffing,
│                         process tree, SSE framing, the outbound-HTTP
│                         boundary, terminal protocol + images, YAML
│                         frontmatter, EDN store + file locks, highlighting,
│                         markdown, crypto, AWS SigV4, ...)
├── modes/              — Entry modes: interactive TUI + print mode
│   ├── interactive.clj — interactive TUI entry: run + session start-up
│   │                     (pi: modes/interactive/interactive-mode.ts)
│   └── interactive/    — sections split out of interactive.clj:
│       ├── state.clj   — CoreState record, config ref, session/format
│       │                 helpers, footer/title/border updaters
│       ├── auth.clj    — login/logout flows + provider options
│       ├── status.clj  — anim timer, status indicator, pending messages
│       ├── turn.clj    — agent events, submit/cancel, compaction queue
│       ├── session_admin.clj — renderers, resume/import, tree, fork/clone
│       ├── commands.clj — builtin commands, /session, /share, /reload
│       ├── resources.clj — loaded-resources sections
│       ├── layout.clj — agent event handler + build-layout widget tree
│       └── ui_registry.clj — ExtensionUIContext (widgets, dialogs, editor)
├── ai/                 — Provider/auth subsystem (pi: packages/ai — a standalone
│   │                     library the agent depends on; self-contained)
│   ├── models.clj      — Provider/model registry + committed EDN catalogs
│   │                     (model_data/), cost, catalog loading
│   ├── model_config.clj / provider_composer.clj — models.edn custom
│   │                     providers + config/extension layer composition
│   ├── model_gen.clj   — the catalog generator (bb generate-models)
│   ├── auth.clj        — env-var table, auth.edn, credential resolution
│   ├── oauth.clj       — OAuthAuth record, device-code + PKCE loopback flows
│   ├── llm.clj + api/  — LLM dispatcher + per-wire API builders
│   │                     (openai/anthropic/google/responses/codex/azure/
│   │                     bedrock/vertex/mistral; api/sse.clj holds the
│   │                     provider stream parsers over kmet.libs.sse)
│   ├── image_models.clj — image-generation registry + :openrouter-images
│   │                     wire (image_model_data/ catalog)
│   ├── google_adc.clj  — vertex Application Default Credentials
│   ├── usage.clj       — provider usage normalization (pi: normalizeUsage)
│   ├── edn_writer.clj / hash.clj — deterministic catalog EDN, pi-parity hashes
│   ├── attribution.clj / constrained_sampling.clj / hooks.clj —
│   │                     provider attribution headers, tool JSON-schema
│   │                     constraints, injectable provider-event slots
│   ├── http.clj        — provider streams over the kmet.libs.http boundary
├── app/                — App business logic (pi: dist/core/)
│   ├── loop.clj        — the agent loop; retry.clj — retry/error policy;
│   │                     loop_guard.clj — repeat-loop guard; session.clj — EDNL sessions
│   ├── commands.clj / keybindings.clj / packages.clj / extensions.cljc —
│   │                     slash commands, keymap, package + extension loading;
│   │                     extensions/context.cljc — per-extension SCI isolation
│   ├── model_resolver.clj — model pattern/CLI resolution
│   ├── tools/          — built-in tools: read, write, edit, bash
│   │                     (grep/find/ls ship as opt-in extensions in
│   │                     extensions/grep-tool.clj, find-tool.clj, ls-tool.clj)
│   └── ui/             — app TUI components (chat history, footer, ...)
├── tui/                — Generic TUI library (pi: @earendil-works/pi-tui;
│   │                     usage docs in src/kmet/tui/tui.md)
│   └── components/     — text, input, editor, markdown, select/settings
│                         lists, spinner, image, stack layouts, ...
```

```
tasks/kmet/tasks/       — every bb-task implementation (a classpath root, not
                          part of the app): packagers (uberjar/dist/pack-extension),
                          the catalog generators, and the dev loop (changed,
                          test runner, clean, lint). Neither artifact carries
                          it — the uberjar walks src/, jolt embeds src/.
test/kmet/tasks/        — their tests
docs/examples/          — copyable, non-auto-loaded settings and complete theme EDN
```

The tree above is a working map. Keep detailed behavior in the topic documents
listed in the root `AGENTS.md` topic map.

## Architecture and portability

### Layer boundaries

- `kmet.loader.*` is an extraction candidate: it may use third-party deps and
  `kmet.loader.*`, but no other `kmet.*` namespace.
- `kmet.libs.*` is generic and self-contained; it may not require app, TUI,
  mode, AI, or sibling-lib namespaces.
- `kmet.ai.*` is a standalone provider/auth library and depends only on
  `kmet.libs.*` plus standard/third-party code.
- `kmet.tui.*` is the generic UI layer. It may depend on `kmet.libs.*`, never
  on app, LLM, or session concepts.
- `kmet.modes.*` is the entry-mode layer; `kmet.app.*` is business logic;
  `kmet.app.ui.*` is app-specific UI; `kmet.core` only parses arguments and
  dispatches.
- `kmet.tasks.*` lives under `tasks/kmet/tasks/`, is never required by shipped
  code, and is not packaged. Task tests live under `test/kmet/tasks/`.

Prefer `defrecord` over `deftype`, protocols for extension points, and atoms
for mutable state. `AgentState` deliberately uses independent per-field atoms
rather than one application-database atom. Prefer explicit runtime
registration tables/maps over compile-time multimethod registries. Use
`defn-` and `def-` for implementation details that are not public API.

### Portable host rules

- Prefer `babashka.fs`, `babashka.process`, `clojure.string`, and
  `clojure.java.io`; avoid `java.io.*`/`java.nio.file.*` imports and Java type
  hints. All outbound HTTP goes through `kmet.libs.http`.
- Host reader features are `:bb` and `:jolt`, never `:clj`. For a portable
  host carve-out use `#?(:jolt X :default Y)`.
- Do not add Maven entries for `babashka.fs` or `babashka.process`; both hosts
  provide their built-in equivalents. Tooling-only dependencies belong in
  `bb.edn`/`deps.edn`.
- On Termux use `$TMPDIR` or another explicit writable directory rather than
  `/tmp`; `java.io.tmpdir` is unreliable there. Normalize zip entry separators
  before containment checks and normalize `fs/relativize` results with
  `fs/normalize` before comparing or using them as path segments.
- The shell setting resolves a custom path first, then `/bin/bash`, `which
  bash`, and `sh`; Windows uses Git Bash and WSL uses the WSL shell.

## Development workflow

The task implementation lives in `tasks/kmet/tasks/`; packaging and
cross-host build details are covered in the user-facing
[`docs/building.md`](../../docs/building.md) guide.

```sh
bb run             # Interactive TUI
bb test            # Run fast test suites (excludes ^:slow tests)
bb test-ext        # Run only the slow (^:slow) test suites
bb lint            # clj-kondo over both reader views (babashka + jolt);
                   # `jolt lint` runs the same gate
bb format          # cljfmt (fix) / bb format-check (verify)
bb clean           # Remove build artifacts, caches, logs (--dry-run: list only)
bb nrepl           # Start an nREPL server on port 1667 (blocks)
bb check           # Verify all source namespaces compile
bb generate-models     # Regenerate provider catalogs (network)
kmet --generate-models # Refresh the user-level catalog cache (network)
bb generate-image-models # Regenerate the image model catalog (network)
bb check-model-data      # Offline catalog validation
bb slop                # SCBench verbosity/erosion vs. reference rows, outliers only
bb pack-extension <src-dir> [out.jar]  # Verify + pack an extension artifact root
bb help            # Show task help
```

The `*-changed` tasks are the iteration loop — they cover only the current
changes (git diff vs HEAD + untracked, plus the namespaces/tests that
transitively require them via the require graph); `jolt` runs the same tasks:

```sh
bb changed              # List changed files
bb test-changed         # Tests of affected namespaces (non-slow)
bb test-ext-changed     # ...only the slow ones
bb lint-changed         # Lint changed files plus affected dependents
bb format-check-changed # (or bb format-changed to write the fix)
```

The full gates — `bb test`, `bb test-ext`, `bb lint` (0 findings required) and
`bb format-check` — are slow. Use the changed-file tasks while iterating and
run the full gates when requested or before release.
