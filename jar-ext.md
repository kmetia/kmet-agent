# Jar/zip extension distribution — implementation plan

Goal: extensions distribute as a single `.jar`/`.zip` (same bytes, either
suffix) that the loader serves **without expanding to disk** (no cache
dir). The archive root is a classpath root: `extension.edn` (+ optional
`deps.edn`) on top, everything else at strict namespace-munged paths,
resources accessed via `io/resource`. In-repo extensions restructure so
`extensions/<name>/src/` **is** the artifact root — packing is `zip src/*`,
installing is `unzip` **or** `ln -s .../src`.

Decisions locked in discussion (do not re-litigate in implementation):

1. **No expansion, no cache.** Per-call `ZipFile` open/read/close (the
   existing `jar-source` pattern) — no open handles survive unload.
2. **Strict ns-path layout for both dir and jar extensions.** Namespace
   `a.b/c` lives at `a/b/c.clj` (`.clj` → `.cljc` → `.bb` fallback). The
   `(ns ...)`-form index scan (`scan-ns-files`) goes away. All existing
   namespaces already satisfy this — conversion is pure file moves.
3. **No host-side directory listing inside jars.** The host never
   enumerates zip prefixes. Extensions know their own resource names and
   self-register content (`register-skill!` / `register-prompt!` / direct
   `theme/register-theme!`). Jar skills/prompts must be self-contained
   single files (no relative refs) in v1.
4. **`extensions/<name>/src/` is the artifact root.** Manifests, code,
   skills and (merged) resources live under `src/`; `README.md`, `bb.edn`,
   `test/`, `scripts/`, `SPEC.md` stay in the dev wrapper and never ship.

Single-file extensions (`extensions/tools.clj`, `deepseek-peak.clj` — bare
`.clj`, no manifest, no resources) are unaffected by all of this.

---

## 1. Artifact format

```
my-ext-1.0.0.jar                      ; .zip identical, loader treats both the same
├── extension.edn                     ; {:name "my-ext" :entry my.ext.main :loader [:jolt :sci]}
├── deps.edn                          ; {:deps {...}} — :deps key only, :paths ignored
├── my/ext/main.clj                   ; (ns my.ext.main ...)
├── my/ext/helper.clj
├── skills/mcp/SKILL.md               ; exact-name resources
└── kmet/extensions/x/queries/foo.edn
```

- `extension.edn` at archive root, **required**. Shape:
  `{:name "my-ext" :entry my.ext.main :loader [:jolt :sci]}` — `:entry` is
  a **namespace symbol** (cutover from the current file-path string; see
  §3), resolved through the same ns-path lookup as every other namespace;
  `:loader` lists the loader backends the extension supports (`:sci` /
  `:jolt`; the host picks its own preference — see `extensions.md` § Loader
  compatibility). `:name` defaults to the dir name / jar basename when
  absent, but jars should set it explicitly so versioned filenames
  (`my-ext-1.0.0.jar`) keep a stable identity.
- `deps.edn` at root, optional. Only `:deps` is read (via the existing
  `deps-of-dir` → `closure-jars` path); `:paths` is ignored — there is
  exactly one root. No bundled `lib/*.jar` inside the archive in v1:
  library deps come only from `deps.edn`, resolved into `~/.m2`/`~/.gitlibs`
  exactly as today.
- Strict layout: every `.clj` file's `(ns ...)` must equal the namespace
  derived from its path (dashes ↔ underscores, `/` ↔ `.`). The pack task
  verifies this (§6); the loader **does not** scan/index — it resolves each
  `require` by direct path probe.
- Entry-name hygiene for jars (mirror `kmet.libs.archive/entry-target`):
  normalize `\` → `/`, reject absolute names and `..` segments at
  load and pack time.
- Discovery (`load-extensions-from-dir`): top-level `*.jar` / `*.zip`
  files load alongside `*.clj` files and `extension.edn/` dirs. Sort order
  stays `sort-by str`. A jar without root `extension.edn` fails with an
  actionable error (same shape as "dir has no extension.edn").

## 2. Repo layout: `src/` becomes the artifact root

Per extension `<name>`: everything that ships moves under
`extensions/<name>/src/`; dev-only files stay in `extensions/<name>/`.

| Extension | Moves into `src/` | Stays (dev-only) |
|---|---|---|
| `clojure` | `extension.edn`, `deps.edn` (stripped to `{:deps ...}`, drop `:paths`), `src/edit_tool.clj` → `edit_tool.clj`, `src/edit_util.clj` → `edit_util.clj`, `src/sexp_tool.clj` → `sexp_tool.clj`, `src/paren_repair.clj` → `paren_repair.clj`, `src/kmet/.../core.clj` → `kmet/.../core.clj`, `skills/` → `skills/` | `bb.edn`, `test/`, `README.md` |
| `lsp-adapter` | `extension.edn`, `src/extensions/...` → `extensions/...` | `README.md`, `scripts/`, `.gitignore` (no `bb.edn`/`test/` today — none to keep) |
| `mcp-adapter` | `extension.edn`, `src/extensions/...` → `extensions/...`, `skills/` → `skills/` | `README.md`, `scripts/` (no `bb.edn`/`test/` today — none to keep) |
| `review` | `extension.edn`, `src/kmet/...` → `kmet/...` | `bb.edn`, `test/`, `README.md` |
| `tree-sitter` | `extension.edn`, (`deps.edn` is `{:paths [...] :deps {}}` → **delete**), `src/kmet/...` → `kmet/...`, `resources/kmet/...` merged into `kmet/...` (no collisions: code vs `.edn` queries/manifests) | `bb.edn`, `test/`, `README.md`, `SPEC.md` |

Result (example):

```
extensions/clojure/              ; dev wrapper, never packaged
├── README.md  bb.edn  test/
└── src/                         ; === artifact root, zipped as-is ===
    ├── extension.edn            ; {:name "clojure" :entry kmet.extensions.clojure.core}
    ├── deps.edn                 ; {:deps {...}} only
    ├── edit_tool.clj            ; (ns edit-tool) — already strict
    ├── kmet/extensions/clojure/core.clj
    └── skills/clojure-edit/SKILL.md
```

Consequences:

- `bb.edn :paths` in each wrapper stays `["src" "test" "../../src"]`
  (`"src"` now means the artifact root; flat test namespaces like
  `edit-util-test` still resolve). Tests referencing moved paths update:
  skill dir strings (`"skills/..."` → `"src/skills/..."`),
  tree-sitter `resources/` references → `src/kmet/...`.
- Install = two spellings of one thing:
  `unzip my-ext.jar -d ~/.kmet/agent/extensions/my-ext` ≈
  `ln -s $PWD/extensions/clojure/src ~/.kmet/agent/extensions/clojure`.
  The loader only ever sees artifact roots; the repo wrapper is invisible.
- `test/fixtures/ext-*` already mimic artifact roots — they need only the
  `:entry`-as-symbol cutover (§8), nothing structural.

## 3. Loader changes (`src/kmet/app/extensions.clj`)

### 3.1 `Extension` record + identity

- Add `:kind` (`:file` | `:dir` `:jar`). `:path` stays the canonicalized
  install path (file, dir, or jar file).
- `extension-dir-of`: `nil` for `:jar` (there is no directory); unchanged
  for `:file`/`:dir`. `get-loaded-extensions` gains `:kind` and keeps
  `:extension-dir` (`nil` for jars). `:extension-path` in the api stays the
  jar file path for jars.

### 3.2 `resolve-extension` → artifact resolution

Handle three shapes; return `{:name :kind :entry-ns}` where `:entry-ns` is
a **symbol**:

- `:file` — plain `.clj` path (unchanged behavior, name = filename).
- `:dir` — requires root `extension.edn`; `:entry` must be a symbol
  (no legacy string-path support — atomic cutover with fixtures and all
  five in-repo manifests).
- `:jar` — `*.jar`/`*.zip` regular file; open `ZipFile`, read root
  `extension.edn` + `deps.edn` entries; `:entry` must be a symbol; reject
  missing manifest and absolute/`..` entry names.

Delete `scan-ns-files`. Keep `read-ns-form` but add a string-source
variant (validation parses sources just loaded through the artifact, for
both dir and jar — no longer by pre-scanning files).

### 3.3 Source provider (one code path for dir + jar)

New primitive (replaces the `ns-files` map + `jar-source` split):

- `artifact-source [artifact ns-sym]` → `{:source str :display str}` or
  nil. Dir: probe `<root>/<ns-path>.clj[jc|bb]`, `slurp`. Jar: per-call
  `ZipFile` open, probe the three entry names, `slurp` the stream, close
  (never hold the handle).
- `make-load-fn [ext-name artifact deps-resolver tui-ns libs-ns]` order:
  1. own artifact, 2. `deps.edn` closure jars (existing lazy
     `make-deps-resolver`/`jars-for`), 3. host `io/resource` fallback for
     non-`kmet.*` namespaces, 4. actionable `ex-info` (keep current
     messages verbatim).
- Every own-artifact source is require-validated on load (as today).
  `validate-entry-requires!` loses its `ns-files` allowlist param: with
  strict layout the own-namespace set is derived **from paths, not file
  contents** — dir: file-exists probe; jar: entry-name set collected once
  at load (cheap: list entry names, reverse-map `a/b/c.clj` → `a.b/c`).
  Non-`kmet.*` requires pass validation and fail at resolution time with
  the existing "not declared in deps.edn" error. `babashka.http-client`
  and non-shared `kmet.*` rejections stay exactly as-is.
- `eval-forms!` generalizes from "read file" to "eval source string with
  display name" (`{:source :display}`); entry evaluation uses the
  artifact source, and `read-ns-sym` runs on the same string (single read).

### 3.4 `create-context` / `load-extension!`

- `create-context` takes the artifact descriptor (not `ns-files`) and
  wires the `clojure.java.io` shadow (§4) into `build-context-namespaces`.
- `load-extension!` flow: resolve artifact → read `deps.edn` `:deps` →
  build context → validate entry requires → warn on `bb-bundled-libs`
  pins (unchanged) → eval entry source → find `init` → `create-extension-api`
  → `init`. Rollback semantics unchanged.
- `load-extensions-from-dir`: add the `*.jar`/`*.zip` branch. Keep the
  "only dirs with `extension.edn`" guard (so a symlinked `src/`'s
  subdirs never double-load).
- `unload-extension!`: unchanged (drop `:ctx`/`:jars`; no zip handles are
  held). `extension-jars` introspection unchanged.

## 4. Resources via per-context `io/resource` (no expansion)

Today `clojure.java.io` is shared wholesale by reference (via the `all-ns`
scan in `build-context-namespaces`), so `io/resource` inside an extension
sees only kmet's classpath. Change to a per-extension copy: every var
delegates to the host **except `resource`**, which becomes a closure over
the extension artifact:

```
own artifact (dir file URL | jar:file:...!/entry URL)
  → deps.edn closure jars (jar:file:...!/entry URL on first hit)
  → host io/resource (fallback)
```

- Dir: `(io/as-url (io/file root rel))` when the file exists.
- Jar: `(java.net.URL. (str "jar:" (.toURL (.toURI (io/file jar-path))) "!/" rel))`
  when the entry exists (per-call `ZipFile` probe, no handle held).
- Host `slurp` opens both URL kinds via `openStream`, so extension code
  keeps the exact tree-sitter pattern unchanged:
  `(slurp (io/resource "kmet/extensions/tree_sitter/bin_manifest.edn"))`.
- Implementation: after the `all-ns` scan, `assoc` a patched
  `'clojure.java.io` map — `(assoc (ns-interns 'clojure.java.io) 'resource
  custom-fn)` — into the `:namespaces` passed to `sci/init`.
- Explicitly unsupported: listing inside jars (`file-seq`/`list-dir` over
  zip — SCI can't do that interop anyway). Extensions reference resources
  by exact shipped names.
- `:extension-dir` is `nil` for jars. Migrate the three in-repo
  concat-users to `io/resource`: `clojure` + `mcp-adapter` skill
  contributions (via §5 instead of paths), `tree-sitter/paths.clj`
  `bundled-resource` (drop the `extension-dir` atom, pure `io/resource`
  lookup; update `paths-test` accordingly).

## 5. Self-registration: skills, prompts, themes

Keep `:resources-discover` + `discover-resources!` + `load-*-from-dir` for
user config dirs and back-compat. Our extensions stop contributing paths
and register content in `init` (strictly earlier than the first
`discover-resources!`, so first-prompt timing is safe; the loop rebuilds
the prompt per turn anyway).

### 5.1 Skills (`src/kmet/app/skills.clj`)

- Split `load-skill-from-file` into file IO + pure
  `parse-skill-content [raw fallback-name location]` (frontmatter parse,
  name/description validation, first-wins collision rules shared).
- Skill map gains `:body` (full `SKILL.md` text), `:location` (display,
  e.g. `my-ext:skills/mcp/SKILL.md`), `:extension` (owner name); `:file-path`
  /`:base-dir` stay for file-backed skills, `nil` for extension skills.
- New host fn `register-extension-skill! [raw-content {:keys [location
  fallback-name extension]}]` → deregister fn. Same validation/collision
  diagnostics (stderr warnings) as dir loading.
- `expand-skill-command`: prefer in-memory `:body`, fall back to slurping
  `:file-path`. `format-skills-for-prompt`: render `:location` (either
  kind). Extension skills are served through the `read` tool from memory
  under their `name:path` location (pass `<location>` verbatim) as well as
  via `/skill:name` expansion — the preamble says so (follow-up fix:
  the original plan disclosed extension skills via expansion only, but the
  `<location>` the prompt exposes is only loadable when `read` serves it).
- Existing `register-skill!` (programmatic name+description) stays.

### 5.2 Prompts (`src/kmet/app/prompts.clj`)

- Same split: `make-template-from-content [raw name location]` shared by
  `load-template-from-file` and new `register-prompt-template! [{:keys
  [name content description argument-hint location extension]}]` →
  deregister fn. Expansion (`expand-prompt-template`) and autocomplete
  (`as-command-maps`) are already content-based — no further changes.

### 5.3 Themes (`src/kmet/tui/theme.clj`)

- No new api needed (`kmet.tui.theme` is shared by reference): extensions
  call `make-theme` + `register-theme!` directly. Add the missing
  `unregister-theme! [name]` for clean unload; extension tracks the
  deregister fn like any other registration.

### 5.4 Contract (`src/kmet/extension.clj`)

- Api map: `:extension-dir` may be `nil` (document: jar artifacts);
  add `:register-skill!` and `:register-prompt!` capabilities (tracked
  deregisters via `track-deregister!` in `create-extension-api`).
- Add `ext/register-skill!` / `ext/register-prompt!` wrappers with
  docstrings (intent + content-string contract + single-file constraint).
- `create-nullable-api`: `:extension-dir "test"` stays; capture skill /
  prompt registrations in `:state` (assertable like commands/tools).

## 6. Packaging: `bb pack-extension`

- New `bb pack-extension <src-dir> [out.jar]` task; implementation lives
  in `kmet.tasks.build` (already owns archive/zip concerns) or a small sibling
  ns it requires — either way host-evaluated bb code using
  `java.util.zip.ZipOutputStream` (deterministic sorted order, no
  `META-INF`, no permission preservation needed).
- Verify-then-zip: `extension.edn` present with `:name` + symbol `:entry` +
  a `:loader` vector of known backend kinds (`:sci` / `:jolt`);
  `:entry` ns-path file exists in the root; **every** `.clj` file's
  `(ns ...)` matches its path; `deps.edn` parses and carries only `:deps`
  (warn on `:paths`); no absolute/`..`/backslash-ambiguous names.
  Input is the artifact root (`extensions/<name>/src/`); output defaults
  to `<name>.jar` in cwd.

## 7. In-repo extension migrations (code changes)

- `clojure/core.clj`: replace `:resources-discover`/`(:extension-dir ...)`
  with `(ext/register-skill! api (slurp (io/resource
  "skills/clojure-edit/SKILL.md")) ...)` in `init` (requires
  `clojure.java.io` — allowed: `clojure.*` shared).
- `mcp-adapter`: same for `skills/mcp/SKILL.md`; drop the
  `:resources-discover` handler.
- `tree-sitter`: `paths.clj` → pure `io/resource` (delete `extension-dir`
  atom + `set-extension-dir!`); `core.clj` drops the setter call;
  `fetch.clj` unchanged (goes through `bundled-resource`).
- `lsp-adapter`, `review`: moves only (no resource-path code found).
- Both `SKILL.md` files verified self-contained (89/126 lines, no relative
  refs) — no content changes needed.

## 8. Fixtures + tests

- `test/fixtures/ext-*/extension.edn`: `:entry` path-string → symbol
  (`src/main.clj` → `cljfmt-ext.main`-style per fixture); `src/main.clj`
  namespaces already strict — no renames.
- New jar fixture: build the `.jar` on the fly in the test (via
  `ZipOutputStream`, following `fetch-test/make-zip!`) — no binary
  fixtures checked in. Cover: code load + `init` from a jar entry,
  `io/resource` read of a jar resource, `deps.edn` closure alongside the
  own jar, unload, `.zip` suffix equivalence, `load-extensions-from-dir`
  picking up jars, `:extension-dir nil` for jars, and strict-layout
  rejection (sloppy ns → actionable load error).
- `test_extensions.clj`: update `:entry` strings in the inline-spit tests;
  `:resources-discover` test stays (host path flow unchanged); add
  content-registration tests (skill/prompt parse + expand-from-memory +
  collision + deregister-on-unload) and `unregister-theme!` coverage.
- Extension self-tests run from their wrappers unchanged
  (`bb.edn :paths ["src" "test" ...]` still resolves); only moved-path
  references update.
- `tasks/kmet/tasks/changed.clj`: no change needed (globs already cover
  `extensions/**/*.clj`; flat root files like `edit_tool.clj` map to
  their namespaces correctly).

## 9. Docs (same change, per AGENTS.md)

- `extensions/extensions.md`: artifact format (§1), strict layout,
  `:entry`-as-symbol, `io/resource` access + no-listing rule,
  `register-skill!`/`register-prompt!`/theme pattern, single-file
  constraint, `:extension-dir` nil for jars, install (unzip ≈ symlink
  `src/`).
- `extensions/README.md`: `src/`-as-artifact-root layout, what stays out,
  pack/install instructions.
- `src/kmet/extension.clj`: api-key docs + wrapper docstrings.
- Check `src/kmet/tui/tui.md` only if theme behavior text is affected
  (expected: not).

## 10. Rollout order + validation

1. Host loader (§3) + `io/resource` shadow (§4) — atomic with fixture
   `:entry` cutover; `bb test kmet.app.test-extensions`.
2. Registries + contract (§5); nullable-api assertions.
3. Extension moves + init migrations (§7); per-extension `bb test` in
   each wrapper + root `bb test`.
4. Pack task (§6); round-trip: pack `extensions/clojure/src` → load jar
   → skill expands → unload.
5. Docs (§9); full gates only on explicit request (`bb lint` /
   `bb format-check` / `bb test` / `bb test-ext` per AGENTS.md; default
   loop is the `bb *-changed` tasks).

## 11. Non-goals (v1)

No cache/expansion, no `lib/*.jar` bundling, no `META-INF`/signing
handling, no version ranges beyond `deps.edn`, no zip listing APIs, no
multi-file jar skills, no `deps.edn` `:paths`.
