# kmet specific guidelines Agent Guidelines

## Conventions

### Build & Run
- **Entry**: `bb run` — runs `kmet.core/-main`
- **Clean**: `bb clean` / `jolt clean` — removes build output (`target/`, `dist/`,
  `extensions/*/target/`), caches (`.cpcache`, `.jolt`, the clj-kondo cache),
  logs and dev residue; `--dry-run` lists without deleting. Backed by
  `kmet.tasks.clean` (`tasks/kmet/tasks/clean.clj`); tracked files, `.kmet/` and
  `.lsp/` are never touched.
- **nREPL**: `bb nrepl` — starts nREPL server on port 1667 for interactive development (blocks). Connect your editor/tool to `localhost:1667`.
  To stop: evaluate `(System/exit 0)` via nREPL (or `fuser -k 1667/tcp` from another terminal).
- **Lint**: `bb lint` / `jolt lint` — clj-kondo over BOTH reader views, in one report:
  the babashka view (the tree, with files carrying a `:bb` branch projected) and the jolt
  view (the files carrying `:jolt`, plus `jolt/` and `.jolt` sources). clj-kondo knows only the standard
  `:clj`/`:cljs` features, so `kmet.tasks.lint` (`tasks/kmet/tasks/lint.clj`) re-spells the view's own
  feature to `:clj` in a projection under `target/` (`target/bb-lint/`, `target/jolt-lint/`
  — stable paths, so clj-kondo's cache carries over) and layers
  `.clj-kondo-jolt/config.edn` on top of the project config for the jolt runtime seams
  (jolt.ffi/jolt.host, `__register-*`). The rewrite keeps branch order, and a reader takes
  the first matching branch in file order — so a `#?(:jolt X :clj Y)` (a leftover `:clj`
  branch) still selects what jolt selects. Both hosts run both views, so EITHER gate alone
  covers common, babashka and jolt code; findings the views
  share are reported once. The gate requires 0 errors, warnings, and info findings.
  Custom macros (`defcomponent`/`with-let`)
  are handled via analysis hooks in `.clj-kondo/hooks/`; keep them in sync when the macro shapes change.
- **Format**: `bb format` (fix) / `bb format-check` (verify) — cljfmt over `src`/`test`/`tasks`/`extensions`.
  The format tasks are one code path on both hosts (`kmet.tasks.format`):
  cljfmt is declared for babashka (`bb.edn` `:deps`) and for jolt (`deps.edn`).
  On jolt one extra dep is load-bearing: `org.clojure/spec.alpha` (jolt's
  resolver drops cljfmt's `org.clojure/clojure` dep and `cljfmt.config` needs
  spec). A `rewrite-clj` pin at the version babashka bundles keeps both hosts
  on the same parser (bb's built-in beats cljfmt's transitive 1.2.50).
  Throughput is not symmetric: jolt formats at
  ~1.6 s/file to babashka's ~0.12 s, so a full-tree `jolt format-check` runs
  minutes — use the changed-file task (`jolt format-check-changed`) or
  babashka for the whole tree. Nothing wraps the library: failures propagate
  as themselves.
  The generated EDN provider catalogs (`src/kmet/ai/model_data/`,
  `src/kmet/ai/image_model_data/`) are excluded: their exact bytes are
  sha256-manifested (`manifest.edn`, checked by `bb check-model-data`) and are
  owned solely by the generator (kmet.tasks.generate-models, delegating to
  `kmet.ai.model-gen`; `kmet --generate-models` runs the same pipeline into
  the user-level cache ~/.kmet/agent/models-cache/, preferred over the
  bundled catalogs when strictly newer).
  `cljfmt.edn` carries `:extra-indents` for the custom macros; default arg alignment is
  align-to-first-argument (modern cljfmt). Run `bb format` after structural edits (e.g. let merges).
- **Changed-file dev loop** (fast validation of only the current changes): the `bb *-changed`
  tasks are backed by `kmet.tasks.changed` (`tasks/kmet/tasks/changed.clj`) — a require-graph scan over
  the classpath source roots (src/, test/, tasks/, extensions/) with reverse-transitive closure, so a
  source change also re-runs the tests that
  transitively require it. `bb changed` lists files changed since the last commit
  (git diff vs HEAD + untracked; mtime-since-baseline fallback when the project has no git).
  `bb test-changed` runs the non-slow tests of affected namespaces and
  `bb test-ext-changed` the slow (^:slow) ones (full gates: `bb test` / `bb test-ext`);
  `bb lint-changed` lints changed files plus affected
  dependents (a changed signature is only flagged at the call site, and changed `jolt/`
  files join in) over both reader views — same on either host: `jolt lint-changed`. It
  falls back to a full lint when `.clj-kondo*` config/hooks changed; `bb format-check-changed` / `bb format-changed`
  cover just the changed files. Full gates stay `bb test`/`bb test-ext`/`bb lint`/`bb format-check`.
  Caveats: "changed" is since-last-commit, so a full gate without committing re-runs those
  files next time (over-inclusive, never under); green full `bb test`/`bb test-ext` runs
  (no filters) update the mtime baseline via `kmet.tasks.runner` → `kmet.tasks.changed/mark-validated!`,
  which is a no-op with git.
  `extensions/` is covered too: its .clj files (source **and any tests they carry**) are part
  of `bb lint`/`bb format`/`bb format-check` and the changed-file scan. Extension tests are
  separate projects — they run from inside their directory (own deps), never from the root
  runner; `bb test-changed` prints a hint instead of silently skipping them.
- **Deps**: no `deps.edn` entry for `babashka.fs` / `babashka.process` — babashka bundles them and
  jolt vendors the same namespaces (built-in, resolving ahead of any classpath copy; public
  surfaces `jolt.fs` / `jolt.process` — `jolt.fs` now carries zip/unzip/gzip too, on the
  runtime's `java.util.zip`). A Maven copy would only risk shadowing the vendored one,
  so kmet relies on both hosts' built-ins.
  Tooling deps (`cljfmt`, plus its `org.clojure/spec.alpha` and `rewrite-clj`
  companions on jolt) in `bb.edn` `:deps` and `deps.edn`; JLine **4.4.0** bundled with Babashka (see
  babashka `deps.edn`: `org.jline/jline-terminal`, `org.jline/jline-reader`) as the
  bb/JVM terminal backend — the Jolt terminal backend uses no dependency: termios /
  kernel32 through `jolt.ffi`. The Windows clipboard backend works the same way —
  `babashka.ffi` on babashka, `jolt.ffi` on jolt (`kmet.libs.clipboard-win`),
  no dependency and no subprocess.
- **Packaging** — the `dist` task is host-dispatched: `bb dist` runs `kmet.tasks.build`,
  `jolt dist` runs `kmet.tasks.build-jolt` (one task name on both hosts; a task may not be called
  `build` — jolt's built-in `build` owns that name, and a task either loses to it with a
  warning on every run or, with `:override-builtin`, makes a wrapper's own `jolt build` call
  re-enter itself forever).
  - babashka (`kmet.tasks.build`): `bb uberjar` → `target/kmet.jar` (the src/ tree
    + resolved dep jars — the non-bb-builtin Maven jars are `data.json` (JSON
    seam) and `cljfmt` (format task)); `bb dist [platforms|--all] [--force] [--smoke] [--test]`
    → self-contained executables in `dist/` (official bb release binary + appended uberjar,
    fresh uberjar always rebuilt first; artifacts `kmet-<ver>-bb<bb-ver>-<platform>`,
    version = jolt's checkout rule (`kmet.libs.version`: nearest `v<digit>` tag via
    `git describe --tags --dirty`, `dev-g<sha>` without a release tag, `dev` outside
    git; the uberjar bakes it as kmet/version.txt for `kmet --version`); platforms
    linux-aarch64, linux-amd64, macos-aarch64, macos-amd64, windows-amd64 — the
    linux ones from babashka's static release assets, the same platform
    vocabulary the jolt packager stamps). `--test` swaps in the test-runner artifact
    (`kmet-test-<ver>-bb<bb-ver>-<platform>`, Main-Class `kmet.tasks.test-main`): its jar
    carries src/ plus tasks/ + test/ + the generated entry under `target/kmet-test-entry/`
    (`kmet.tasks.build/generate-test-main!`), and the binary's `--test` / `--test-ext`
    flags run the packaged suite. Test classes exist only in that artifact — a plain build
    is src/ and nothing else. Termux: a `.sh` launcher next to the
    binary (glibc linker exec + `--jar <self>`; auto-detection breaks because `/proc/self/exe`
    resolves to `ld-linux`). Downloads cached + sha256-checked in `target/build-cache/`.
  - jolt (`kmet.tasks.build-jolt`): AOT-compiles via a `jolt build -m kmet.core` subprocess (no jar
    step, nothing to download) into `target/jolt/<platform>/<mode>/` — jolt's incremental build
    and its `.build/` payload dir stay out of `dist/` — then copies to
    `dist/kmet-<ver>-jolt<jv>-<platform>[-dev][.exe]`, bakes kmet/version.txt into it
    (from `target/kmet-version`, an embed root the packager writes; `kmet --version`
    reports the built-as version rather than describing whatever repo it runs in)
    and, with `--smoke`, runs it with `--debug`, `--list-models`, and `--version` from an
    empty temp dir with `JOLT_PWD` and `KMET_CODING_AGENT_DIR` pointed there. The temporary
    agent enables the bundled `clojure` directory extension, and the smoke asserts its
    debug line says `kind=resource-dir loader=jolt bundled=true` (embedded native root,
    not SCI). io/resource falls back to JOLT_PWD-relative source roots, so a run from the
    checkout would pass without `deps.edn :jolt/build {:embed ["src" "target/kmet-version"
    "target/kmet-bundled"]}` baking the model catalogs, version, and bundled extensions in
    — none of those roots is tasks/, so nothing under tasks/ rides in the binary even
    though it is on the classpath). `--test` builds the compiled
    test runner instead: the entry is the generated `kmet.tasks.test-main`, selected with
    `-A:kmet-test` (deps.edn: test/ plus the generated entry root), which statically
    requires every `kmet.tasks.runner/all-namespaces` namespace — the whole suite is
    AOT-compiled into `dist/kmet-test-<ver>-jolt<jv>-<platform>[-dev][.exe]`, whose
    `--test` / `--test-ext` flags run it (its `--smoke` runs `--test kmet.libs.test-num`
    from the empty dir); a plain app build never selects those roots. Build `--test`
    in the default release mode — a dev build's uncompressed fasl image can push the
    full suite's boot over Chez's LZ4 ceiling into a very slow gzip re-encode. Termux
    gets a `.sh` launcher through the glibc linker when the built binary is
    glibc-linked (the packager probes the ELF interpreter: a binary linked with
    the bionic cc, e.g. a local jolt build there, runs directly and gets none),
    like the bb one minus `--jar`. Flags:
    `--dev|--opt`, `--closed-world`, `--dynamic`, `--boot fast|small|plain`,
    `--target MACHINE --target-pack DIR`, `-o PATH`, `--force`, `--smoke`, `--test`,
    `--jolt PATH`.

### API Preferences (avoid Java interop)
- **`babashka.fs`** over `java.io.File` for all file operations
- `babashka.process` over `java.lang.ProcessBuilder` for subprocesses
- `clojure.string` over Java `.startsWith()`, `.contains()`, `.indexOf()`
- `clojure.java.io`  over `java.io.*`
- Avoid `^String`, `^java.io.File`, `^java.io.Reader` etc. type hints — stay Babashka-compatible.
- No `java.io.*` or `java.nio.file.*` imports — everything is available via `babashka.fs` and `slurp`/`spit`
- **All outbound HTTP goes through `kmet.libs.http`** — the single
  transport-neutral boundary (babashka.http-client + curl, proxy env vars).
  No other namespace may require `babashka.http-client` or spawn `curl`;
  the retired `kmet.libs.proxy` / `kmet.ai.proxy` are deleted (enforced by
  `kmet.test-http-boundary`). Provider streams use the `kmet.ai.http`
  decorator on top.

### Code Style
- **Records, not deftype**: use `defrecord` + `map->` constructors
- **Protocols** for extension: `IComponent` (render/handle-input/invalidate), `IFocusable` (focused/set-focused!)
- **State**: atoms for mutable state (component children, input listeners, render flags)
- **Agent state model** (`kmet.app.loop/AgentState`): per-field atoms ARE
  the concurrency model — independent cells (`signal`, `active-call`,
  queues) stay separate; runtime-tunable knobs live in one `:cfg` atom
  holding an immutable map (`swap!`-assoc). No single app-db atom:
  swap-retry storms at token-streaming rates.
- **Dispatch**: explicit tables/maps over multimethods — extensions register
  at runtime through calls, so compile-time `defmethod` registries would
  fork the mechanism (see the tool renderer registry)
- **Reader conditionals name the host**: `:bb` selects on babashka, `:jolt`
  on jolt; plain code is what both run. The two features are disjoint —
  babashka skips `:jolt`, jolt skips `:bb` — so no branch order can make one
  host run the other's code. **`:clj` is not used in kmet source**: it matches
  BOTH hosts (and a plain JVM), and a reader takes the first matching branch in
  file order, so `#?(:clj A :jolt B)` gives *both* hosts A (on jolt `:clj` comes
  first) — a `:clj` branch meant for babashka silently runs on jolt. When a
  host-specific carve-out needs a value for the other host, write
  `#?(:jolt X :default Y)`: `:default` is what every reader falls back to.
  Both lint gates check every view (see Lint).
- **Private vars**: use `defn-` / `def-` for implementation details not part of public API

### Git
- Do not add `Co-authored-by` trailers to commit messages.

## Editing
- For Clojure, Babashka and EDN files (`.clj` `.cljs` `.cljc` `.bb` `.edn`), prefer the
  structure-aware extension tools over the generic `write`/`edit` tools:
  `clojure_edit` finds a definition by `form_type` + `form_identifier` and
  `replace`/`insert_before`/`insert_after`s it; `clojure_edit_replace_sexp`
  replaces an s-expression by content match (`replace_all` to rename a symbol
  file-wide). They validate structure, reject unbalanced delimiters, and
  format with cljfmt. Keep the generic `edit`/`write` tools for plain textual
  changes (comments, docstrings, non-form text).
- For `insert_before`/`insert_after`, pass ONLY the new content — never
  re-include the anchor form. The inserted form lands outside the anchor's
  own line: a same-line trailing comment stays with the anchor, and a
  comment on its own line stays with the next form.
- Alias-qualified forms (`(t/deftest ...)`, `(s/def ...)`) match with the
  plain `form_type` keyword (`deftest`, `def`) — the qualified name also
  works.
- `clojure_edit_replace_sexp` `match_form`/`new_form` must be COMPLETE
  expressions with balanced parens; fragments like `:else [w j])` are
  rejected.
- When an `edit` call fails because of unbalanced parens, try the `clojure_paren_repair` tool first; if that doesn't help, split the change into smaller focused edits.

## File layout
```
src/kmet/
├── libs/     — Generic, self-contained code that would be a third-party library
│              on the JVM (Babashka-compatible reimplementations)
├── loader/   — The loader library (kmet.loader.core + the backends
│              memory-loader / sci-loader / jolt-loader): the
│              portable Loader protocol + backends. Extraction-ready: no
│              kmet.* requires beyond kmet.loader.* (guarded), design doc
│              and conformance suite ride along (src/kmet/loader/loader.md)
├── modes/    — Entry modes (pi: dist/modes/)
├── ai/       — Provider/auth subsystem (pi: packages/ai — a standalone library
│              the agent depends on; enforced by the
│              kmet.ai.test-self-contained guard: only kmet.libs.* deps)
│   ├── api/               — Per-wire LLM API builders (pi: packages/ai/src/api/)
│   ├── model_data/        — committed provider catalogs + manifest (bb generate-models)
│   └── image_model_data/  — committed image-model catalog (bb generate-image-models)
├── app/      — App-level business logic (pi: dist/core/)
│   ├── tools/  — Tool implementations (one file per tool)
│   └── ui/     — App-specific TUI components (Pi's coding-agent layer)
├── bundled-extensions/ — the committed bundled-extensions manifest (a
│              resource, not a namespace: kmet/bundled-extensions/manifest.edn,
│              read by kmet.app.bundled-extensions; see extension-bundle.md).
│              The shipped set lives in extensions/ at the repo root and is
│              staged by the packagers under the same relative paths
│              (target/kmet-bundled/extensions/<root>)
└── tui/      — Generic TUI library (Pi's @earendil-works/pi-tui)
    │           Usage + development docs: src/kmet/tui/tui.md — MUST be kept up to date
    │           with any behavior change they describe
    │           terminal.clj = the ITerminal protocol + shared ANSI/query
    │           logic + host dispatch; terminal_jline.clj (bb/JVM),
    │           terminal_native_unix.jolt and terminal_native_win.jolt
    │           (Jolt termios / kernel32 FFI) are the only namespaces that
    │           touch platform deps
    └── components/ — TUI leaf components (Container, Box, Text, ...)

tasks/kmet/tasks/ — EVERY bb-task implementation (bb.edn `:requires`/entry
              points), on both hosts: build.cljc (bb uberjar / bb dist /
              pack-extension), build_jolt.clj (the `jolt dist` branch),
              generate_models.clj + generate_image_models.clj (the bb
              generate-models / generate-image-models / check-model-data
              entries over kmet.ai.model-gen), and the dev loop — changed.clj
              (bb changed + the *-changed tasks), runner.clj (bb test /
              bb test-ext), clean.clj (bb clean), lint.clj (bb lint /
              bb lint-changed, over both reader views), format.clj
              (bb format / format-check + the *-changed variants — the same
              cljfmt path on both hosts). tasks/ is a classpath
              root (`bb.edn`/`deps.edn` :paths) but NOT part of the app: the
              uberjar walks src/ only and jolt embeds its :embed roots, so
              neither artifact carries any of it. The task tests are the
              siblings in test/kmet/tasks/ (build_test.clj, test_changed.clj,
              test_lint.clj, format_test.clj, ...).

extensions/ — Shipped opt-in extensions (single .clj files or manifest dirs;
              pi: examples/extensions). Namespaces align with the layout: a
              single file is kmet.extensions.<name> (stem), a manifest dir
              kmet.extensions.<name>.* with the entry kmet.extensions.<name>.core.
              The whole set is bundled with the app (disabled by default,
              enabled via `kmet config` / the :bundled-extensions settings
              key — extension-bundle.md), enumerated in
              src/kmet/bundled-extensions/manifest.edn and validated by
              `bb check-bundled-extensions`; the packagers stage each
              artifact root under target/kmet-bundled/extensions/<root>.
              Extension authoring guide (the full
              kmet.extension contract): extensions/extensions.md — MUST be
              kept up to date with any behavior it describes

jolt/      — kmet's RFC 0014 provider scaffolding (Jolt-only; see the contract
              below). Own deps.edn + src/jolt/kmet/providers.clj, pulled in
              from the root deps.edn as {:local/root "jolt"}. Empty by design
              (v0.8.6-98 supplies every gap it was built for) but kept so the
              next JDK gap has its slot. Inert on bb/JVM: no bb
              classpath namespace requires jolt.* and the babashka view excludes
              it; the jolt view lints it (see Lint above).

Root-level files: core.clj (CLI entry, arg parsing, mode dispatch), config.clj
(configuration loading), debug.clj (debug/error logging), extension.clj (the
extension contract root: namespaces extensions depend on, init/shutdown, api).
```

### jolt/ — the RFC 0014 provider contract

The bundled-extension native path requires a Jolt runtime exposing
`jolt.loader/embedded-root?`; until the first tagged release carrying it lands,
that capability probe is the floor and kmet falls back to SCI. Single-file
bundled resources stay on SCI because an embedded root addresses a prefix, not
one exact file key.
`jolt/` is kmet's RFC 0014 provider slot (details: jolt/README.md). **Empty by design**: the JDK gaps it was built for —
the `java.net.http.HttpTimeoutException` ctor, the multi-arg
`java.net.URI` ctors, `ProcessBuilder` File redirects,
`SocketOutputStream.write(byte[])`, `LinkedBlockingQueue` and the Base64
MIME pair — are all runtime surface as of `v0.8.6-98`, so `:jolt/provides`
is `{}` and `install!` is a no-op. The scaffolding stays for the next gap:
add the class to `:jolt/provides` and its member registration to
`install!`.
RSA is not in this list — jolt.crypto provides
`Signature`/`KeyPairGenerator`/`KeyFactory` for RSA and EC and claims those
classes in its own `:jolt/provides` (a class may have a single provider);
the JWK bigint→DER conversion lives in `kmet.libs.crypto/bigint->bytes`
(portable, both hosts); `java.util.Base64` is runtime surface (the MIME pair
included), so nothing here.
`jolt.kmet.providers` requires nothing while empty (not even `jolt.host`):
crypto's classes resolve through `jolt.crypto`'s own `:jolt/provides`
claims, a declared provider resolving its class whatever loaded first and
being attributed to itself.
Convention: a src ns whose forms reference a member of a class the runtime
IMPLEMENTS but does not fully supply adds the guarded require as its first
form after the ns:

```clojure
(when (find-var 'clojure.core/*jolt-version*)
  (require 'jolt.kmet.providers))
```

Such a member cannot be `:jolt/provides`-claimed (jolt refuses claims on
implemented classes), so nothing autoloads and the guard is the only install
path — no kmet namespace needs it today. Classes declared in
`:jolt/provides` (none today; jolt.crypto's
`Signature`/`KeyPairGenerator`/`KeyFactory`) need no guard: the claimer's
install namespace loads on the first reference, whatever loaded first.

### Layer boundaries
- **`kmet.loader.*`** — the loader library, extraction candidate: may require
  third-party deps and `kmet.loader.*`, nothing else under `kmet.*`. Enforced
  by `kmet.loader.test-self-contained`. App code may depend on it like
  `kmet.libs.*`.
- **`kmet.libs.*`** — generic, self-contained. **Must not require any kmet.*
  namespace outside `kmet.libs.*`** (no app, tui, modes, ai, or sibling-lib
  deps beyond the libs tree itself). Each lib is a portable
  unit: only stdlib + third-party deps, and any bundled assets (scripts) live in
  the lib directory. Enforced by `kmet.libs.test-self-contained`.
- **`kmet.ai.*`** — provider/auth subsystem (pi: `packages/ai`). **Must not require
  any other kmet.* namespace beyond `kmet.libs.*`** — a standalone library the
  agent depends on. Enforced by `kmet.ai.test-self-contained`.
- **`kmet.tui.*`** — generic. No dependency on app, LLM, or session concepts.
  May depend on `kmet.libs.*`.
- **`kmet.modes.*`** — entry modes. Depends on `kmet.app.*`, `kmet.tui.*`, `kmet.config`.
- **`kmet.app.ui.*`** — app-specific. Builds on `kmet.tui.*`; imports `track!` from `kmet.tui.macros`.
- **`kmet.app.*`** (non-ui) — business logic. Never imports `kmet.tui.*` or `kmet.app.ui.*`.
  May depend on `kmet.libs.*` and `kmet.ai.*`.
- **`kmet.core`** — entry only: args + dispatch. Never contains app logic.
- **`kmet.tasks.*`** — bb-task implementations, never required by shipped code
  and never packaged. Every one lives in `tasks/kmet/tasks/` — a classpath root
  (`bb.edn`/`deps.edn` `:paths`) outside `src/`, which is what keeps them out of
  both artifacts structurally: the uberjar walks `src/` only, and jolt embeds
  its `:embed` roots (`["src"]`) rather than its `:paths`. Their tests are the
  siblings under `test/kmet/tasks/`. They may depend on anything they
  orchestrate; nothing in `src/` may require them.

### ANSI escape codes
- **Never use raw ANSI escape codes (`\u001b[...`) outside `src/kmet/tui/` and
  `src/kmet/libs/terminal.clj`** (the protocol library, where they belong by
  design).
  All terminal styling goes through `kmet.tui.theme` functions (`theme/fg`, `theme/bg`,
  `theme/bold`, `theme/dim`, `theme/italic`, etc.) which use attribute-specific resets
  (`\u001b[22m` for bold/dim, `\u001b[23m` for italic, `\u001b[39m` for fg, `\u001b[49m` for bg)
  instead of catch-all `\u001b[0m`, so nested styles (e.g. bold inside a `theme/fg` wrapper)
  compose correctly without losing attributes.

## Testing
- **Framework**: `clojure.test`
- **Layout**: `test/kmet/` mirrors `src/kmet/`
- **Run**: `bb test` — all tests except those marked `^:slow`.
  Use **`bb test-ext`** to run only the `^:slow` tests (tests that wait
  real wall-clock time: sleeps, terminal-query timeouts; real network
  calls; and subprocess spawns — bash tool, shell commands, git). Mark
  slow tests with `^:slow` on the deftest; selection happens per test var
  in `kmet.tasks.runner`.
- New test namespaces must be registered in `kmet.tasks.runner/all-namespaces` (the full run loads
  exactly that list).
- During development, validate only what changed with `bb test-changed` / `bb lint-changed` /
  `bb format-check-changed` (see Build & Run), or run individual test namespaces with filters.

### Final validation
`bb lint` and `bb format-check` are slow — don't run them during iterative
development. **Do not run the full gates unless explicitly told to**: `bb lint` +
`bb format-check` + `bb test` + `bb test-ext` (plus `bb test` inside any extension
directory that carries its own tests) are only run when the user explicitly asks
for a full gate. The default validation loop is the changed-file tasks above.
`bb lint` must pass with 0 errors, warnings, and info findings.

## Platform

- **SCI gotcha**: `(satisfies? SomeProto reify-instance)` can return false
  under Babashka even when methods are registered — dispatch through the
  protocol's multimethod instead (see tui.md §5.1).
- **Fully supported**: Linux, macOS, Windows, WSL, Termux (Android)
- **Primary dev environment**: Termux on Android — glibc babashka via `ld-linux-aarch64.so.1 --library-path`.
  Do not set `LD_LIBRARY_PATH` globally; use the glibc linker directly when on Termux.
- **No `/tmp` on Termux**: there is no `/tmp` directory — `$TMPDIR` is `$PREFIX/tmp`
  (`/data/data/com.termux/files/usr/tmp`). Don't rely on `/tmp` existing in code or scripts.
- **`java.io.tmpdir` is unreliable on Termux**: this babashka hardcodes the
  `java.io.tmpdir` system property to `/tmp` at startup, ignoring `$TMPDIR` — so
  `java.io.File/createTempFile` (no dir arg) fails with "No such file or
  directory", and `babashka.fs/temp-dir` resolves to the same bogus `/tmp`
  (it does NOT honor `$TMPDIR` here). Pass an explicit dir built from
  `(or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))` — the
  `kmet.libs.http/temp-dir` pattern — or use `babashka.fs/create-temp-file`
  with an explicit `:dir`.
- **Zip entries may use `\` as a path separator** (zip spec allows both). On
  Unix, `babashka.fs/canonicalize` resolves only `/`-separated `..` — `\` is a
  plain filename char, so `..\evil` is written as a literal filename and
  passes `starts-with?` containment checks. Normalize entry names (`\` → `/`)
  before containment checks (see `kmet.tasks.build/extract-archive!`).
- **`fs/relativize` is not normalized the same way on both hosts**: bb's
  (java.nio `Path.relativize`) collapses a `./` segment, jolt's keeps it
  (`(fs/relativize cwd "/abs/./a/b.cljc")` → `a/b.cljc` vs `./a/b.cljc`), so
  any result that is compared or used as a path segment needs an explicit
  `fs/normalize` (see `kmet.tasks.lint/repo-relative`).
- **clj-kondo `--config` on the CLI works** (e.g. `--config
  '{:linters {:namespace-name-mismatch {:level :off}}}'`), but there is no
  blanket `:all` linter key, and the finding type for "X already refers to
  #'clojure.core/get" is `:redefined-var` (not `:shadowed-var`) — get the
  exact type from `--config '{:output {:format :json}}'`. Scope a suppression
  to one defn with `#_{:clj-kondo/ignore [:redefined-var]}` on the line before
  the form; only add it for deliberate API-name collisions, not to silence
  code smells.
- **Shell resolution** (`kmet.app.bash-executor`): the `:shell-path` setting
  (pi: `shellPath`, a leading `~` expands) overrides, else
  `/bin/bash` → `which bash` → `sh`.
  On Windows this resolves through Git Bash; under WSL the WSL shell is used.

## Error handling
- Use `ex-info` with a `:cause` or `:type` key for structured errors
- Let errors propagate up to the top-level handler rather than swallowing silently

## Logging
- **Module**: `kmet.debug` — minimal file logging, no external library
- **Debug log** (`debug.log`, cwd): opt-in via `--debug` flag. Logs lifecycle events (submit, cancel, agent turns, commands) and handled exceptions with full stack traces. Uses `kmet.debug/log`.
- **Error log** (`kmet.error.log`, cwd): written unconditionally on unhandled exceptions in the `-main` catch block. Uses `kmet.debug/log-error`.
- Both `log` and `log-error` accept Exception objects and expand them to class name, message, and full stack trace.
- Log format: `[ISO_TIMESTAMP] [ERROR: ]message\n`

## Debugging scripts (`scripts/`)
For debugging terminal rendering issues — modify them (sizes, timing, input)
to reproduce the issue at hand. The kmet scripts hardcode the Termux path.

- Capture a session's raw output: `tmux_capture.sh <session> <send-after> <text> <timeout> <outfile> <cmd...>`,
  `tmux_repro.sh <name> <outfile> <cmd...>` (fixed sequence incl. resize),
  `pty_capture.py` (tmux-free pty; `--cols/--rows/--text/--timeout/--out`).
- Analyze: `term_dump.py <raw-capture>` replays the bytes through a minimal
  ANSI emulator and dumps frames (at 2026 sync boundaries) with colors.
- kmet scenarios: `kmet_sanity.sh <outfile>` (startup + wheel scroll + exit),
  `kmet_verify.sh <outfile>` (flicker metric while streaming + scrolled up).

Workflow: run a capture script → `python3 scripts/term_dump.py out.raw`.

## Docstrings
- No trivial docstrings — a docstring must add information beyond the name (intent, contract, args/return, side effects, exceptions). Skip it when the name is self-explanatory.
- Where behavior isn't obvious, document: public vars, protocol methods, and `defrecord` types
- Optional on private vars — use when the intent isn't obvious from the name

## Instruction hierarchy
When guidelines conflict, priority is (highest first):
1. Explicit user instructions in the current conversation
2. This `AGENTS.md` file
3. The pi coding agent harness defaults
4. General best practices

If the user asks for something that contradicts AGENTS.md, explain the conflict and ask for confirmation.

### Component architecture
- **All UI components are defined with `defcomponent`** (never a bare
  `defrecord` implementing IComponent — extra protocols like IFocusable go
  in separate `extend-type` forms after the call, e.g. select-list.clj).
  Enforced by `test-caching-conventions`.
- Each message type has its own `defrecord` implementing `IComponent`:
- `UserMessage` — user text in a `Box` with `user-message-bg`
- `AssistantMessage` — assistant text + thinking (italic + `thinking-text` color)
- `ToolExecution` — tool call/result in `Box` with status background
- `CustomMessage` — info/custom messages in `Box` with `custom-message-bg`
- `ChatHistory` — data-driven: holds plain message maps in one `messages-atom`
  (each carrying its `:component`); render derives the tree, persistence reads
  the atom directly (no component reverse-engineering)

Type dispatch is kind-as-data: `defcomponent` stamps KIND as the record's
first field; dispatch reads `(:kind component)` (no IComponentKind
protocol — retired in DSL stage 2, see tui.md §8).

### UI style — hiccup preferred
- **New UI is hiccup, not `make-*`**: new screens, dialogs, selectors and
  chrome in `kmet.app.ui.*` are hiccup trees (`hiccup/root` for mounted
  reactive roots, `h/compile-tree` for static frames) over the closed tag
  set in `kmet.tui.hiccup` (`:box` / `:container` / `:text` /
  `:truncated-text` / `:spacer` / `:dynamic-border`, ...). Imperative
  `make-*` + `container-add-child` / `container-replace-children!` is
  legacy — do not extend it; migrate the touched rows/frame when you are
  already editing a hybrid file.
- **Hybrid rule**: DSL-owned chrome with stateful children spliced foreign
  (focused `Input`, `SelectList` / `SettingsList`, long-lived `Spinner` —
  reconcile preserves their identity and disposes nothing foreign). Rows
  are data-derived `[:text ...]` / `[:truncated-text ...]` seqs
  re-derived from a state atom (the `session_selector` pattern), never a
  rebuilt `Container` of `make-text`. Focus/input/dispose stay imperative
  (`tui-set-focus` on the foreign child, `handle-input` forwarding,
  `dispose` unwinding the root).
- **Do NOT rewrite for its own sake**: the transcript records
  (`chat_history`, message / tool-execution components) and string-direct
  `track!` leaves (`footer`, `pending_messages`, `loaded_resources`, list
  / row builders) stay as they are — see `src/kmet/tui/tui.md` §14 for the
  composition rules and §15 for the non-goals.

### Reactive render cache (track!)
- **Default**: wrap a component's render body with `(track! this width ...)`
  and give the record a `:cache-atom` field. Every `@atom` read is recorded;
  when any of them changes, the cache invalidates automatically — setters
  become plain `reset!`/`swap!` with no manual `(protocols/invalidate comp)`
  call. Enforcement: `test-caching-conventions` requires every component with a
  `render` method to either use track! or be on the documented uncached
  allowlist.
- **`defcomponent` generates the cache-clearing `invalidate`**: when the
  render calls track!, the macro adds `(invalidate [this]
  (invalidate-cache this))` unless a custom method is given — in which case
  the cache clear is prepended automatically. Components only write an
  `invalidate` method for extra side effects (delegating to children,
  firing `request-render-fn`).
- **`track-deps`** declares dependencies inside a track! body: atoms whose
  changes must invalidate the cache even though their values don't appear in
  the body — `(track-deps @theme-atom @content-atom)` (wrapper components
  re-rendering cached children).
- **Sound only when the output is a function of atoms the render derefs**:
  leaves (Text, Spacer, Markdown, Image, ...) and self-contained composites
  whose mutation paths touch their own atoms (messages, tool executions,
  footer, status line, expandable header, ...). When a child's internal state
  affects the output, the render must deref it too (e.g. StatusLine and
  ExpandableText deref the inner component's text atom). Atoms that the
  render body itself mutates (e.g. tool_execution's last-component atoms,
  Image's id allocation) are read through non-tracking helpers so they don't
  self-invalidate the cache.
- **Do NOT use track!** for: transparent parents (Container, Box, HStack,
  VStack, ChatHistory, ScrollView — children change independently and the
  parent cannot track that; they stay cheap by relying on children's caches;
  Box additionally memoizes its padding/bg composition), time-animated
  output (Spinner, status indicators, flashes — must render fresh every
  pass), and focused input widgets (input, editor). Time-animated content
  must live at the document bottom (spinner/status) or be cached so it only
  ticks with real updates (the tool-execution elapsed counter ticks via its
  own 1s invalidate interval while a tool is partial — pi: setInterval →
  context.invalidate — so a silent long-running tool still updates steadily).
- A render body that invalidates itself mid-run (a render fn calling
  `:invalidate`, or `set-state!` on tracked state) does not cache its stale
  result: track-render watches the cache atom, so the next render re-runs
  the body with the fresh state.
- **Full redraws emit `\u001b[3J` (erase scrollback)**: a true full redraw
  re-emits the whole transcript, so the scrollback must be cleared or the
  history duplicates (pi issue #6050). Windows Terminal (and Termux) scroll
  to the top on 3J; they are only reached for events that genuinely need a
  re-emit — resize, an explicit forced rebuild, and a shrink that starts
  above the window (see `do-full-redraw` in `kmet.tui.core`). A change that
  starts *above* the window no longer triggers one (same-height and growing
  cases): the diff is clamped to the window top and only visible lines are
  repainted in place (a terminal has no addressable scrollback, so the stale
  lines above are left alone), and a same-height change entirely above the
  window emits nothing at all. That keeps 3J out of the streaming path — otherwise every
  reflow/edit-preview update above the window yanked the viewport to the top
  while the user was reading (microsoft/terminal#20370, pi #4506/#6502). An
  *explicit* global reflow (the tool-output / thinking toggles, the
  set-tools-expanded extension API, a theme switch) is not that automatic
  path: it is a discrete user action, so the app forces the clearing rebuild
  immediately (`request-global-reflow-render!`,
  `kmet.app.theme-controller/notify-changed!`) rather than leave the
  scrollback showing pre-reflow content until the next heal. The
  TUI flags the resulting above-window staleness (`tui-scrollback-dirty?`)
  and rebuilds the scrollback with one clearing full redraw via
  `tui-heal-scrollback!` — the app calls it at the end of a turn
  (`on-agent-done` / `on-agent-error`, gated on nothing else streaming) and
  on idle input, a streaming-free boundary right after the turn's last
  write, so the clear's jump lands on a screen transition rather than
  mid-stream.

## Reference
- **Loader docs**: `src/kmet/loader/loader.md` — the Loader design,
  resolution semantics, conformance cases and phase status. It lives with the
  library so it can be extracted with it; keep it up to date.
- **TUI package docs**: `src/kmet/tui/tui.md` is the usage and development
  reference for `kmet.tui.*` (Hiccup DSL, fn components, reactivity/track!,
  state model, lifecycle, scheduling, input boundary) and for building UI
  on it in `kmet.app.ui.*` (§14 composition rules, §15 design non-goals).
  It must be kept up to date: a change to TUI behavior described there
  updates the doc in the same change.
- Consult `~/src/cvstree/pi/` for implementation patterns before building new features — e.g., study its TUI component model before adding new components, or its diff rendering approach before implementing a diff view.
