# Bundled extensions — implementation plan

Goal: ship the extensions in `extensions/` **with the application**, so they
are available in every run mode — `bb run`, `jolt run`, and the compiled
`bb`/`jolt` binaries — with no installation step. The user enables/disables
them from `kmet config` (default: disabled) in both settings scopes; they are
**not** installable/removable packages. Nothing is extracted to disk, ever.

Status: **Phase A implemented** (see git history). Deviations from the
original plan, all deliberate: the resource prefix is `extensions/` (the
checkout layout one-for-one, so a staged/jar/binary path reads exactly as
the repo path); the manifest stays at `src/kmet/bundled-extensions/`
(inside `src/`, so it resolves in dev where `extensions/` is not a
classpath root); and a `:resource-file` artifact's extension name is the
file name (what a user's own copy carries), with the manifest name kept as
the config screen's display name. **Phase B** (the Jolt embedded-root
loader) is pending upstream. The phases are independent; Phase A lands
first.

Related docs: `jar-ext.md` (extension artifact format, the "no expansion, no
cache" decision), `extensions/extensions.md` (the extension contract),
`extensions/README.md` (the shipped set), `src/kmet/loader/loader.md` (the
kmet Loader design and the Jolt native backend), `jolt-port.md` §B3
(extension isolation).

---

## 0. Decisions locked (do not re-litigate in implementation)

**D1. The bundled set is every artifact under `extensions/`**, enumerated in a
committed manifest. That means the five single-file extensions
(`tools.clj`, `grep-tool.clj`, `find-tool.clj`, `ls-tool.clj`,
`deepseek-peak.clj`) and the five directory extensions (`clojure`,
`lsp-adapter`, `mcp-adapter`, `review`, `tree-sitter` — `src/` is the artifact
root). `lsp-adapter` is included even though the README catalog did not list
it.

**D2. Bundled resources are a resolution layer, not packages.** They enter the
existing unified resolution as a fifth layer with `:origin :bundled`, never as
a `:packages` entry. Consequence: `kmet install`/`remove`/`list` cannot touch
them, and no settings file can "uninstall" one — only enable/disable it (or
drop the enable entry, which returns it to the default: disabled).

**D3. Default disabled.** Enablement lives in a dedicated settings key
`:bundled-extensions` in both scopes, using the same `+`/`-`/plain entry
vocabulary as the resource arrays, with tri-state (inherit/load/unload) cycling
in the config TUI. Details in §2.5.

**D4. No extraction, no cache directory, no writes of the app's own files.**
The artifact bytes live
in the application artifact (uberjar / jolt `:embed`) as resources; the loaders
read them in place. This is the whole point of the delivery design (§2.2), and
the reason Phase B exists: the Jolt **native** loader needs a real root, which
is solved by teaching `jolt.loader` an embedded root rather than extracting.
Dependency resolution is not extraction: it keeps writing only to the standard
`~/.m2`/`~/.gitlibs` caches, exactly as it does for every extension today
(§2.8).

**D5. One artifact-descriptor abstraction.** The runtime resolves each manifest
entry into a descriptor whose `:kind` is `:dir`/`:file` (a source checkout),
`:resource-dir`/`:resource-file` (a bb artifact, or a jolt artifact before
Phase B), with an optional `:native` embedded root (Phase B). Everything below
the descriptor (SCI source provider, jolt loader, resource lookup, discovery)
switches on that kind.

**D6. Phase A: resource artifacts are SCI-only.** Until the jolt embedded root
exists, `:resource-dir`/`:resource-file` artifacts load through the SCI backend
on every host (babashka already uses SCI; jolt uses its documented `:sci`
fallback). Directory/file artifacts from a checkout keep loading exactly as
today on both hosts.

**D7. Phase B: the jolt native loader gains an embedded root kind.** It is
implemented upstream in the jolt repo — this is the same loader kmet specifies
in `loader.md` §9 (jolt-lang/jolt#912/#1039) — then kmet prefers it for
`:resource-dir` artifacts on jolt. Details in §4.

**D8. Bundled artifacts are restricted.** They may carry a `deps.edn` for
external libraries (§2.8) but otherwise use the fixed bundled set and the
shared `kmet.tui.*`/`kmet.libs.*` layers; they may not rely on `:extension-dir`
(nil for resource artifacts; state goes under `get-agent-dir`), may reference
resources only by exact name, and every directory artifact's `extension.edn`
must declare `:sci` in `:loader`. A `deps.edn`, when present, must parse to a
map with only `:deps` and contain no `:local/root`. The bundle gate enforces
all of it.

**D9. Strict-layout and requires validation move to bundle time for resource
artifacts.** The runtime's per-load validation (`make-source-fn`'s ns check,
`validate-native-sources!`) needs to enumerate the artifact's own files; a
resource root cannot be listed. The `bb check-bundled-extensions` gate and the
dist staging step validate every bundled file (strict ns-path layout + the
extension-requires allowlist) against the checkout instead. Directory artifacts
from a checkout keep the per-load validation.

**D10. Single-file bundled artifacts always run SCI.** Even in Phase B, a
bundled single file (`tools.clj`, …) loads through the SCI backend on every
host: it has no resources and no isolation needs a native root would serve, and
this avoids `materialize-single-file!` writing a temp file for a bundled
artifact.

**D11. Same-name dedupe.** The bundled layer ranks last, so a user's own copy
(symlink, package, auto dir) loads first; a bundled artifact whose name is
already loaded is skipped with a note. This is the migration path off the
current "symlink the shipped extension" workflow.

**D12. The manifest is the gate.** `bb check-bundled-extensions` (and a test
calling the same validator) fails when an artifact under `extensions/` is not
listed, when a listed root is missing/invalid, or when a restriction from D8
is violated. An explicit `:exclude` list is the only way to keep a dev-only
artifact out of the bundle.

---

## 1. Acceptance criteria

- `bb run` and `jolt run` from the checkout: `kmet config` lists a
  "Bundled with kmet" group with all ten artifacts, all off by default.
- A `bb dist` binary and a `jolt dist` binary, run from an empty directory
  with an empty/default agent dir, do the same — no files next to the binary,
  nothing written under the agent dir, `KMET_CODING_AGENT_DIR` untouched.
- Enabling an entry (global or project scope) and restarting/`/reload` loads
  that extension: its tools/commands/skills are live. Disabling unloads it
  again.
- `kmet install`/`remove`/`list` cannot see or affect bundled extensions.
- `kmet remove <anything>` never removes a bundled extension.
- On jolt binaries after Phase B, directory artifacts load through the native
  loader (visible in `--debug` logging / `get-loaded-extensions`'s
  `:loader-kind`), not SCI.
- A bundled extension that carries a `deps.edn` resolves and downloads its
  closure at first enable, exactly like a user-installed extension — and, like
  one, fails with a warning when offline.
- `bb check-bundled-extensions` passes and `bb test-changed` / `bb lint-changed`
  are clean.

---

## 2. Architecture

### 2.1 The manifest

New committed file: `src/kmet/bundled-extensions/manifest.edn` (resource key
`kmet/bundled-extensions/manifest.edn`; it is inside `src/` so it exists in
every mode).

```clojure
{:schema 1
 ;; :root is relative to the repo's extensions/ directory, and is the
 ;; artifact root exactly as pack-extension/extensions.md define it.
 :artifacts
 [{:name "clojure"       :kind :dir  :root "clojure/src"}
  {:name "lsp-adapter"   :kind :dir  :root "lsp-adapter/src"}
  {:name "mcp-adapter"   :kind :dir  :root "mcp-adapter/src"}
  {:name "review"        :kind :dir  :root "review/src"}
  {:name "tree-sitter"   :kind :dir  :root "tree-sitter/src"}
  {:name "tools"         :kind :file :root "tools.clj"}
  {:name "grep-tool"     :kind :file :root "grep-tool.clj"}
  {:name "find-tool"     :kind :file :root "find-tool.clj"}
  {:name "ls-tool"       :kind :file :root "ls-tool.clj"}
  {:name "deepseek-peak" :kind :file :root "deepseek-peak.clj"}]
 ;; dev-only artifacts under extensions/ that deliberately do not ship
 :exclude []}
```

Why a manifest and not discovery: the repo layout is `extensions/<name>/src/`
(dev wrapper + artifact root), while the installed layout is the artifact root
itself — so a naive scan of `extensions/` cannot tell artifacts from dev
wrappers (`README.md`, `bb.edn`, `test/`, `scripts/`). Also, jars and jolt
embeds cannot be listed, so enumeration must be data.

### 2.2 Modes and descriptors

`kmet.app.bundled-extensions` turns each manifest entry into a descriptor per
mode:

| mode | detection | directory artifact | single-file artifact |
|---|---|---|---|
| checkout (`bb run`, `jolt run`, tests) | the manifest resource resolves to a `file:` URL, and the repo's `extensions/<root>` exists | `{:kind :dir :root "<repo>/extensions/<root>"}` | `{:kind :file :path "<repo>/extensions/<root>"}` |
| bb artifact / jolt artifact, Phase A | manifest resource is not a `file:` URL and `io/resource "extensions/<root>/extension.edn"` (dir) / `<root>` (file) exists | `{:kind :resource-dir :prefix "extensions/<root>"}` | `{:kind :resource-file :path "extensions/<root>"}` |
| jolt artifact, Phase B | as above, plus `:native` | same plus `:native "embed:extensions/<root>"` | unchanged (D10) |

All descriptors get `:name` (manifest name), `:bundled? true`, and a synthetic
`:path` used for identity/display (`<prefix>` or the real path).

If the manifest resource is not file-based **and** the resource probe misses
(a hand-made `jolt build` without the staging embed), the bundled set resolves
to `[]`; a `kmet.debug/log` note records why. Never an exception: the app must
start.

Repo root in checkout mode comes from the manifest's own URL: it must end with
`src/kmet/bundled-extensions/manifest.edn`; four `fs/parent` steps up give the
repo root, then `extensions/<root>` is the artifact. If the URL is not a file
URL but `<repo>/extensions` is reachable from the current directory *and* the
manifest also exists on disk there, use that (cwd fallback for a jolt dev run
that does not answer file URLs); otherwise artifact mode. Verify the jolt
URL shape early in Phase A and document what it does.

### 2.3 Resource keys and staging layout

Everything under one prefix so the two artifact modes mirror each other:

```
resource key                        source
kmet/bundled-extensions/manifest.edn  src/kmet/bundled-extensions/manifest.edn
extensions/clojure/src/…              extensions/clojure/src/…
extensions/tools.clj                 extensions/tools.clj
```

Staging for the artifacts (build-time only, gitignored under `target/`):

```
target/kmet-bundled/extensions/<root>/…
```

- `deps.edn`: `:jolt/build {:embed ["src" "target/kmet-version" "target/kmet-bundled"]}`.
  A missing root embeds nothing, so a direct `jolt build` without the packager
  still builds (and the runtime sees no bundled artifacts — same as today).
- `bb uberjar` and the test uberjar: `write-uberjar!` gains an **extra root**
  walked for *all* files (the current `**.{clj,cljc,edn}` filter would drop
  `skills/**/SKILL.md` and any other resource), passed as
  `"target/kmet-bundled"`; the resulting entries are exactly the resource keys
  above.
- No `:paths`/classpath changes: in a checkout the artifacts are read as real
  files; only binaries use resources.

### 2.4 Resolution layer (`kmet.app.packages`)

New production call chain:

```clojure
(defn resolve-configured-packages [agent-dir]
  (let [user (user-settings-map)
        project (project-settings-map)]
    (resolve-package-items user project agent-dir true
                           (bundled-items user project))))
```

- `bundled-items` delegates to `kmet.app.bundled-extensions/items` and returns
  `PackageItem`-shaped maps:
  `(->PackageItem path enabled :extensions {:origin :bundled :scope :user
   :source "bundled" :base-dir <artifact base> :display-name <name>
   :artifact <descriptor>})`.
- `resolve-package-items` gains a 5th positional argument `bundled-items`
  (default `[]`). **The existing 1–4 arities must not include bundled items**,
  so the ~60 existing resolution tests are untouched.
- A new layer function `resolve-bundled-layer` mirrors `resolve-auto-dir`:
  items appended last, so insertion order (and therefore `add-resource!`
  first-wins) is packages < bundled; `resource-rank` gets `5` for
  `:origin :bundled`.
- `load-extensions!` partitions the enabled items: descriptors with metadata
  `:artifact` go to `extensions/load-extension-descriptors!`, everything else
  to the existing path-based `load-extension-paths!`.
- `read-views` in the config screen passes the same `bundled-items` to its two
  `resolve-package-items` calls.

### 2.5 Enablement settings

**Key**: `:bundled-extensions`, a vector of entries using the existing
vocabulary (`pattern-target` strips `+`/`-`/`!`):

```edn
;; ~/.kmet/agent/settings.edn (global base)
{:bundled-extensions ["clojure" "-mcp-adapter"]}

;; .kmet/settings.edn (project delta over the global base)
{:bundled-extensions ["+mcp-adapter" "-clojure"]}
```

Semantics (one function, `bundled-entry-state`, later entry wins):

| entry | meaning |
|---|---|
| absent | inherit (project) / default disabled (global) |
| `name` or `+name` | enable |
| `-name` or `!name` | disable |

```clojure
(defn bundled-entry-state [entries name]   ; :load | :unload | :inherit
  ...)

(defn bundled-enabled? [name user-settings project-settings]
  (let [base (case (bundled-entry-state (:bundled-extensions user-settings) name)
               :load true :unload false :inherit false)
        proj (bundled-entry-state (:bundled-extensions project-settings) name)]
    (case proj :load true :unload false :inherit base)))
```

Writes (`packages.clj`, next to the other toggle functions):

- `apply-bundled-toggle! [item enabled]` — user settings. `enabled` → plain
  `name`; disabled → `-name`. Rewrite the vector in place (replace any entry
  targeting the name); drop the key when the vector empties.
- `apply-bundled-project-override! [item state]` — project settings, state
  `:load` → `+name`, `:unload` → `-name`, `:inherit` → remove. No plain anchor
  entry is needed (unlike `apply-project-top-level-override!`, which anchors an
  inherited user *path*): the name is scope-independent.
- `bundled-override-state-of [item]` — project entries →
  `:load`/`:unload`/`:inherit`.
- Guards: `apply-global-toggle!` and `apply-project-override!` must return
  `false`/throw for `:origin :bundled` items so no bogus `:packages` delta
  entry can ever be written with `source "bundled"`.

`kmet config` never re-resolves after a toggle (it patches the row, like the
other branches); a running interactive session picks the change up on
`/reload`, exactly like every other resource toggle.

### 2.6 Config UI (`kmet.app.ui.resource-config`)

- `build-groups` sort key gains an origin rank: bundled `0`, packages `1`,
  top-level `2` (bundled first — it is the app-provided set). Group label
  branch: `origin :bundled` → `"Bundled with kmet"` (no scope/path suffix).
- `display-name` prefers `(get-in item [:metadata :display-name])`; a
  resource-dir artifact's path basename is `src`, so this is required.
- `item-state-of` → `override-state-of` gets a bundled branch calling
  `pkgs/bundled-override-state-of`; `inherited-enabled`/`inherited?` need no
  change (items are `:scope :user`, matched by `item-key`).
- `toggle-selected!` gains the bundled branch:
  - global scope: `(pkgs/apply-bundled-toggle! item (not (:enabled row)))` +
    `update-row-state!`;
  - project scope: `next-override-state` with the row's `:inherited-enabled` →
    `apply-bundled-project-override!` + `update-row-state!`.
- `row-line` suffix: global scope `"  bundled"` (dim); project scope
  `"  bundled · global setting"` when the state is `:inherit`. Never the
  package "always loaded" marker.
- Search: `query-match?` already matches display name/type/path.

### 2.7 Extension runtime (`kmet.app.extensions`)

The descriptor kinds and their plumbing:

| concern | `:resource-dir` / `:resource-file` |
|---|---|
| manifest `extension.edn` | `(edn/read-string (slurp (io/resource (str prefix "/extension.edn"))))` |
| entry ns / own-ns probe (`artifact-source`, `artifact-owns-ns?`) | `(io/resource (str prefix "/" (ns-path ns) ext))` |
| extension's own `io/resource` (`extension-resource-fn`) | `(host-resource (str prefix "/" rel))`, then deps closure, then host |
| `extension-dir-of` | nil |
| `deps.edn` | `(edn/read-string (slurp (io/resource (str prefix "/deps.edn"))))` — the same resolver as today (§2.8) |
| unload cleanup | nothing to close (`loader/unload!` only) |
| per-load strict-layout/requires validation | skipped (D9) |
| single file | `(slurp (io/resource path))` + `ns-form-of-source` scan, as today; synthetic `:path` |

Specific changes in `src/kmet/app/extensions.cljc`:

1. `resolve-extension` → accept a path **or** a descriptor. Add the
   `:resource-dir` (manifest read from the resource) and `:resource-file`
   (name = manifest name, `:file` = the resource key) branches. Keep
   `:artifact {:kind :resource-dir :prefix …}`.
2. `artifact-source`, `artifact-owns-ns?`, `extension-resource-fn`: add the
   resource branches (no `jar-info`, no `track-cached-jar!`).
3. `load-extension!` → split into a private `load-extension*` that takes a
   descriptor, plus two public wrappers: `load-extension!` (path, unchanged
   signature/semantics) and `load-extension-descriptor!`. `load-extension-paths!`
   keeps working; add `load-extension-descriptors!`.
4. Loader selection: add `forced-loader-kind [artifact declared]` — when
   `(resource-artifact? artifact)` return `:sci` (Phase A); Phase B returns
   `:jolt` when `(:native artifact)`, the host is jolt, and
   `kmet.loader.jolt-loader/embedded-roots?` (a `(some? (resolve
   'jolt.loader/embedded-root?))` probe). `create-jolt-loader` uses the
   `:native` root as the loader root when it is chosen; `own-source-entries`/
   `validate-native-sources!` are not used for it (D9).
5. `Extension` record: add `:bundled?` (and keep the descriptor, e.g.
   `:artifact`), `get-loaded-extensions` exposes `:bundled`.
6. Name dedupe (D11): at the top of `load-extension*`, after
   `resolve-extension`, if an extension with the same `:name` is already in the
   registry, return
   `{:extension name :skipped true :reason :duplicate-name :path path}`;
   `load-extension-paths!`/`load-extension-descriptors!` print a note.
7. `kmet.debug/log` on a successful load (name, kind, loader kind, bundled?) —
   useful for the Phase B smoke assertion and for `--debug` diagnostics.
8. `deps-of-root`: a `:resource-dir` branch reading `deps.edn` from the
   resource tree (see §2.8). No other deps-machinery change: the deps map is
   all the resolver has ever taken.

### 2.8 Dependency loading (`deps.edn`) — allowed

A bundled artifact may carry a `deps.edn` exactly like any other extension;
this is not a new mechanism, it is one more way to produce the deps map:

- `deps-of-root` reads `(io/resource (str prefix "/deps.edn"))` for
  `:resource-dir` (jar-style resource read; absent → nil, as today).
- Everything downstream is untouched: `make-deps-resolver` → `jars-for`
  (cached per deps map) → `closure-jars` — `clojure.tools.deps/create-basis`
  on bb/JVM, `jolt.deps/resolve-deps` on jolt (whose docstring records that
  the engine is AOT'd into the jolt binary). Resolution is **eager at load**
  (forced in `load-extension!` before the SCI eval), so "on demand" means
  *when the extension is enabled and loaded*, not per namespace: the complete
  closure is fetched then, once.
- Downloads land in the standard `~/.m2` / `~/.gitlibs` caches (D4's
  clarification) and are reused by later loads and by the process-level
  `jars-cache`.
- Failure modes are the existing ones: an unresolvable dependency (offline,
  bad coordinates) fails that extension's load, rolls back, prints a warning,
  and leaves the app running. Default-disabled means nothing is fetched until
  the user enables something.
- Gate rules: `deps.edn` parses to a map whose only key is `:deps`; **no
  `:local/root`** (a resource artifact has no stable root in a binary, and the
  current resolver already resolves relative local roots against the process
  cwd — misleading in a bundle); declaring a member of the fixed bundled set
  is ignored/warned as it is today.
- Single-file artifacts still cannot carry a `deps.edn` (unchanged rule).

### 2.9 What does not change

- `kmet install/remove/list`, the `:packages` model, auto dirs, the top-level
  settings arrays, `resolve-package-items`' existing arities and semantics.
- Skills/prompts/themes resolution: bundled artifacts' bundled skills are
  extension-internal (they read `io/resource` in `init` and call
  `register-skill!`), not a new resource layer.
- `/reload`, print mode, and headless mode: they all go through
  `packages/load-extensions!`, so enabled bundled extensions behave exactly
  like any other extension.
- `kmet.config`: no change (the toggles write through `cfg/save-setting!` /
  `cfg/save-project-setting!` from `packages.clj`).

---

## 3. Phase A — kmet implementation, file by file

### 3.1 `src/kmet/bundled-extensions/manifest.edn` (new)

As in §2.1. Committed; generated by hand (ten entries).

### 3.2 `src/kmet/app/bundled_extensions.clj` (new)

Public surface:

```clojure
(defn manifest)            ; parsed map or nil (never throws; debug-logs)
(defn artifacts)           ; -> [descriptor] for the current mode (§2.2)
(defn items
  ([user-settings project-settings])
  ([user-settings project-settings agent-dir]))  ; -> [PackageItem-shaped maps]
```

- Read the manifest once (`delay`) via `(io/resource
  "kmet/bundled-extensions/manifest.edn")`; validate shape; on failure return
  no items + `debug/log`.
- Mode/descriptor resolution as §2.2; memoize per process (`items` may be
  called several times per resolution, e.g. `read-views`).
- Enabled state: `bundled-enabled?` from §2.5 (lives here or in `packages`;
  pick one home — keep the settings *semantics* in `packages.clj` with the
  other toggle code and have this ns call it, or the reverse; do not duplicate).
- Warnings go to `kmet.debug`, not stderr (a missing bundle in a hand-built
  binary must not nag).

### 3.3 `src/kmet/app/packages.clj`

Add (names per §2.4/§2.5):

- `bundled-items`, `resolve-bundled-layer`, the 5-arity of
  `resolve-package-items`, `resource-rank` 5, `bundled-item?`,
  `bundled-entry-state`, `bundled-enabled?`, `apply-bundled-toggle!`,
  `apply-bundled-project-override!`, `bundled-override-state-of`.
- `resolve-configured-packages` passes `(bundled-items (user-settings-map)
  (project-settings-map))`.
- `load-extensions!` partitions on `(get-in item [:metadata :artifact])`.
- Guards in `apply-global-toggle!` / `apply-project-override!`.
- Docstring updates: a paragraph in the ns docstring describing the bundled
  layer (it currently describes the four layers).

### 3.4 `src/kmet/app/ui/resource_config.clj`

Changes per §2.6, plus the ns docstring (it describes groups and toggle
branches).

### 3.5 `src/kmet/app/extensions.cljc`

Changes per §2.7. Also update the ns docstring (artifact kinds) and
`extensions/extensions.md` (§7).

### 3.6 `src/kmet/loader/jolt_loader.jolt`

Add `embedded-roots?` (Phase A: the probe returns false on all shipped jolt
releases, so it is inert; Phase B: true once the jolt change ships). No other
change — `classpath` already forwards root strings, so an `"embed:<prefix>"`
root needs no adapter change.

### 3.7 Build (`tasks/kmet/tasks/build.cljc`, `build_jolt.clj`, `deps.edn`, `bb.edn`)

- `build.cljc`:
  - `stage-bundled-extensions!` — read the manifest from disk
    (`src/kmet/bundled-extensions/manifest.edn`), run
    `validate-bundled-extensions!` (D8/D12), then copy **every file** under each
    artifact root to `target/kmet-bundled/extensions/<root>/…`
    (skip stale files first: delete `target/kmet-bundled` before staging).
  - `write-uberjar!` gains an `:extra-roots` option (walk `fs/glob root "**"`,
    files only, no extension filter), walked **after** the normal roots so the
    existing `seen` dedupe keeps `src/` winning any collision — `uberjar*` and
    `test-uberjar*` stage and pass `["target/kmet-bundled"]`.
  - `validate-bundled-extensions!` — the shared validator (also used by the
    gate task and the test): manifest completeness vs `extensions/` (minus
    `:exclude`), each `:dir` root has `extension.edn` with `:name`/`:entry`
    symbol/`:loader` containing `:sci`, a `deps.edn`, when present, parses to a
    map with only `:deps` and no `:local/root` (shape only — the gate stays
    offline and never resolves), every `.clj/.cljc/.bb`
    file's `(ns …)` matches its path (reuse `pack-verify!`'s loop), each
    `:file` root exists and reads as a single file extension.
- `build_jolt.clj`: call `stage-bundled-extensions!` before the `jolt build`
  subprocess (next to the `target/kmet-version` bake).
- `deps.edn`: `:jolt/build {:embed ["src" "target/kmet-version" "target/kmet-bundled"]}`.
- `bb.edn`: new task `check-bundled-extensions`
  (`:requires ([kmet.tasks.build :as build])`,
  `:task (build/check-bundled-extensions!)`) and a `help` line; update the
  `dist` docs to mention the bundle staging.

### 3.8 Gate + tests

- `bb check-bundled-extensions` (new task) — validates without building.
- New `test/kmet/app/test_bundled_extensions.clj`:
  - manifest validity gate (delegates to the tasks validator or re-checks it in
    test code),
  - `items` default disabled; `:bundled-extensions ["clojure"]` enables it;
    project `-clojure` disables it in the merged view,
  - descriptor mode detection with a synthetic manifest (call the internals
    with an injected manifest map/URL where possible — design `items` to take an
    optional manifest override, or expose the pure parts for tests),
  - origin/rank: bundled items sort after packages, dedupe by canonical path
    does not interact.
- Extend `test/kmet/app/test_packages.clj`:
  - the 5-arity adds exactly the bundled items; the 4-arity adds none,
  - `apply-bundled-toggle!` writes/removes entries; `apply-bundled-project-override!`
    cycles; `apply-global-toggle!` on a bundled item writes nothing to
    `:packages`,
  - `load-extensions!` loads an enabled bundled item through the descriptor
    (use a temp descriptor, not the real repo, under the pinned agent dir).
- Extend `test/kmet/app/ui/test_resource_config.clj`: the Bundled group renders
  (order, label, display names), global toggle writes settings, project cycle
  writes/removes the project entry.
- Extend `test/kmet/app/test_extensions.clj` (or the new file): a
  `:resource-dir` descriptor loads from resources (build a test resource tree
  under `test/` or use the staged tree), a duplicate name is skipped, and
  `get-loaded-extensions` reports `:bundled`.
- Extend `test/kmet/tasks/build_test.clj`: staging layout + validator failures.
- Register the new test namespace in `kmet.tasks.runner/all-namespaces`.

---

## 4. Phase B — jolt.loader: embedded roots (detailed)

Everything in this section is a change to the **jolt** repository
(`github.com/jolt-lang/jolt`), plus the small kmet switch in §4.8. It is the
same loader kmet specifies in `loader.md` §9 (implemented upstream in
`stdlib/jolt/loader.clj`, jolt-lang/jolt#912/#1039, with
`test/chez/loaderconf-test.clj` as its writ).

### 4.1 Scope

Only root resolution and source/resource reading change. Class/provider/type
tables, the private-namespace claim machinery, delegation policy, and the
ambient loader stay untouched (i.e. no M0–M4 work is pulled in).

The feature: a loader root may be an **embedded root** — a prefix into the
runtime's embedded-resource table (the store `:jolt/build :embed` bakes into a
binary, already used by `resolve-on-roots` and `ldr-read-source`).

### 4.2 Root marker and public API

**Spelling**: a root string starting with `"embed:"`; the prefix is the rest
(no trailing slash; keys are `<prefix>/<name>`).

- `host/chez/loader.ss`: private helpers
  `ldr-embedded-root?` / `ldr-embedded-root-prefix`.
- `stdlib/jolt/loader.clj`: **public** `(defn embedded-root? [root])` and
  private `embedded-root-prefix`. `embedded-root?` doubles as kmet's capability
  probe (a var that only exists on a jolt that supports the feature).

Rejected alternative: a map descriptor (`{:kind :embedded :prefix …}`). Roots
are strings throughout (`roots-loader`, `status`, `self-first`,
`delegating`, `pool`, `validate-root!`), and widening the API is not worth it
for a string prefix that cannot legitimately occur as a path on any supported
host (`:` is illegal in Windows paths; a POSIX directory named `embed:` is not
a thing).

### 4.3 `host/chez/loader.ss`

1. Add `ldr-embedded-root?` / `ldr-embedded-root-prefix` next to
   `ldr-root-file`.
2. `ldr-root-file`: embedded branch **first** (before the jar/dir branches, and
   it must not touch `root-jar-index`/`root-path-abs`):

   ```scheme
   (define (ldr-root-file root name)
     (cond
       ((ldr-embedded-root? root)
        (let ((k (string-append (ldr-embedded-root-prefix root) "/" name)))
          (and (embedded-resource-has? k) k)))
       ((root-jar-index root) ...)   ; existing
       (else ...)))                  ; existing
   ```

   The return value is the embedded **key** (not a path). `ldr-read-source`
   already reads such keys (`embedded-resource-ref`), so the read path is
   complete with this one branch.
3. `ldr-root-source` needs no change (it calls `ldr-root-file`).
4. Do **not** touch `resolve-on-roots` / `ldr-install-file?` /
   `ldr-builtin-ns-rel?`: the global `require` path keys embedded sources by
   their bare root-relative path, and a prefixed bundle key can never collide
   with those probes.
5. `jolt.host/root-file` (the Clojure-facing host fn registered in
   `host/chez/host-table.ss`) forwards `ldr-root-file`; update its docstring to
   say the answer may be an embedded key. No registration change should be
   needed — verify.
6. Expose one predicate to the Clojure side: `jolt.host/embedded-resource?`
   (wraps `embedded-resource-has?`). This is the only new host surface; the
   existing `io/resource` already answers embedded keys in a built binary (see
   `build-app`'s `--resloader` case), so no new reader/opener host fn is
   required.

### 4.4 `stdlib/jolt/loader.clj`

1. `embedded-root?` / `embedded-root-prefix` (§4.2).
2. `validate-root!`: embedded branch first — a non-blank prefix is the only
   check (no eager existence check: keys are probed per request; a wrong prefix
   degrades to an ordinary miss). Keep `jar-root?` from ever seeing the marker.
3. `roots-loader`: do not `(mapv str roots)` destructively — roots are strings,
   keep the marker strings; `:roots` in `status` then renders the marker.
4. `root-file` (the Clojure wrapper): an embedded key must **not** be
   absolutized:

   ```clojure
   (defn- root-file [root name]
     (when-let [p (jolt.host/root-file (str root) name)]
       (cond
         (embedded-root? root) p
         (str/starts-with? p "jar:file:") p
         :else (str (fs/absolutize (fs/file p))))))
   ```

5. `roots-locate`:

   ```clojure
   :ns (into [] (for [root roots, rel (ns-source-paths (:name req))
                      :let [f (root-file root rel)], :when f]
                  {:kind :ns :file f}))
   :resource (into [] (for [root roots
                            :let [f (root-file root (:name req))], :when f]
                        {:kind :resource
                         :url (cond (embedded-root? root) f
                                    (str/starts-with? f "jar:file:") f
                                    :else (str "file:" f))
                         :embedded? (embedded-root? root)}))
   ```

   `:ns` hits need no marker: the key is unambiguous and `eval-namespace-source`
   branches on it (below). Resource hits carry `:embedded?` so opening does not
   fall back to the host-by-name path.
6. `default-open` / `hit-url`: today the `:else` branch re-resolves
   `(:name hit)` against the host — wrong for a prefixed root (and a latent bug
   for any aliased resource hit). Prefer the hit's own locator:

   ```clojure
   (cond
     (str/starts-with? url "file:")     (io/input-stream (file-url-path url))
     (str/starts-with? url "jar:file:") (io/input-stream url)
     (:embedded? hit)                   (io/input-stream (host-resource url))
     :else                              (io/input-stream (host-resource (:name hit))))
   ```

   Mirror the same branch in `hit-url`. (`host-resource` already exists and is
   the loader's own host-resolver wrapper — do not use the ambient 1-arity
   `io/resource` here.)
7. Source reading: `source-roots-ns-load` passes `(:file hit)` to
   `eval-namespace-source`, which does `(io/reader file)`. Add a reader helper
   used by `eval-namespace-source`:

   ```clojure
   (defn- open-source-reader [file]
     (if (jolt.host/embedded-resource? file)
       (io/reader (host-resource file))
       (io/reader file)))
   ```

   and wrap it in the existing `PushbackReader`. Keep `*file*` bound to the key
   and document it (a `def` reading `*file*` sees the embedded key; relative
   resource references were never supported for jars either — exact names are
   the resource rule, see `extensions.md`).
8. `status`: no code change; markers appear in `:roots`.
9. `declares-data-readers?` calls `(fs/file root …)` on the roots: an embedded
   marker simply misses — add a `(when-not (embedded-root? …))` guard for
   clarity.
10. Update the `loader.clj` ns docstring (the request/hit section at the top)
    with the embedded root kind, the `:embedded?` hit field, and the read
    rules. `hit-payload-keys` must NOT gain `:embedded?` (it is a modifier, not
    a payload; a request carrying only `:embedded?` must not look like a hit).

### 4.5 Host seam summary

| fn | side | change |
|---|---|---|
| `ldr-embedded-root?` / `-prefix` | Scheme | new |
| `ldr-root-file` | Scheme | embedded branch |
| `jolt.host/root-file` | Scheme→Clojure host table | docstring only (verify forwarding) |
| `jolt.host/embedded-resource?` | host table | new (wraps `embedded-resource-has?`) |
| everything else | — | unchanged |

### 4.6 Tests

**a) Fixture-app smoke (primary — this is the only place a built binary with
baked sources exists).** Follow the `test/chez/build-app` + `build-smoke.sh`
pattern (`--resloader` is the precedent: only a built binary has an embedded
resource, so that is where the claim is checked):

- Add a fixture tree under `test/chez/build-app/` (e.g.
  `assets/bundled/fixture/{entry.clj,lib.clj,data.txt}`), and add `"assets"` to
  the app's `:jolt/build {:embed [...]}`.
- Add an `--embedloader` case to `app.core/-main`:
  - `l/classpath ["embed:assets/bundled"] {:parent (l/isolated)}` constructs;
  - `find`/`load` of the fixture ns resolves, and a `:require` of a second ns
    inside the same root loads through the context;
  - a var resolves and evaluates to the expected value;
  - `(io/resource "fixture/data.txt")` inside `l/with-loader*` reads the
    embedded copy (via `io/resource`, `RT/baseLoader`/`as-classloader`, and
    `open-hit` on a `find` hit);
  - the fixture namespace is invisible through `(l/root)` while the context is
    live (private-name rule) and the context is unloaded afterwards;
  - a wrong prefix yields a miss and a `:loader/unreadable` load error naming
    the prefix.
  Print one `embedloader:` line with the combined checks (the `--resloader`
  style), and assert the expected line in `host/chez/build-smoke.sh`.
- Add the app/smoke to the Makefile's smoke list if it is not already covered
  by `buildsmoke` (it is the same fixture, so `buildsmoke` already builds it).

**b) `test/chez/loaderconf-test.clj` (source-mode additions, no embedded
content needed):**

- case: `embedded-root?` recognition and `validate-root!` rejecting a blank
  prefix (`:loader/bad-root`);
- case: an embedded root over a nonexistent prefix misses in `find` and fails
  `load` with `:loader/unreadable` naming the prefix (no filesystem error);
- case: `status` reports the marker root string;
- case: a resource hit carrying its own location + `:embedded?` opens through
  the hit, not the request name (use a `->loader` delegate whose resolve-fn
  returns such a hit for a resource that exists on the host, e.g. an existing
  jolt resource; keep it source-mode-safe).

These cases must be green with the default (empty) baseline — the
`loaderconf.sh` gate compares verdicts exactly. Do not add content-dependent
embedded cases here: `make loaderconf` runs through a built release binary
whose embed set is the install roots, not a fixture.

**c) Gate:** `make loaderconf` (cases count must match) plus a hand run of
`build-smoke.sh` against the rebuilt jolt, per the jolt repo's own validation
order (the change touches `host/chez/loader.ss`, so the jolt gates listed in
`loader.md` §9 apply: `corpus`, `unit`, `cts`, `sbperf`, plus `loaderconf`;
`remint` only if a seed-listed file is touched).

### 4.7 Docs (jolt side)

- `stdlib/jolt/loader.clj` ns docstring + `classpath`/`url-search` docstrings.
- `host/chez/loader.ss` comments around `ldr-root-file` (the embedded-key
  resolution, and that `ldr-read-source` already reads such keys).
- `test/chez/README.md` if it lists the fixture apps.
- Also update **kmet's** `src/kmet/loader/loader.md`: §6.1.2/§6.1.3 roots row
  (embedded root kind), §8 conformance list, §9 Phase 2 "what landed" section
  (mirroring how the earlier native-backend landing is recorded).

### 4.8 kmet switch (the entire Phase B kmet diff)

1. `kmet.loader.jolt-loader/embedded-roots?` — returns true when
   `(some? (resolve 'jolt.loader/embedded-root?))` (already added in Phase A).
2. `kmet.app.bundled-extensions`: for `:resource-dir` descriptors, set
   `:native "embed:extensions/<root>"` when the host is jolt and
   `embedded-roots?` is true (do not set it elsewhere — the SCI path must not
   see a jolt-only root).
3. `kmet.app.extensions/forced-loader-kind`: `:resource-dir` + `:native` +
   jolt + `embedded-roots?` → `:jolt`; otherwise `:resource-*` → `:sci`.
4. `create-jolt-loader`: when the chosen artifact is `:resource-dir` with a
   native root, build `(loader-jolt/classpath [native-root] …)`; skip
   `own-source-entries`/`validate-native-sources!` (D9).
5. Record the jolt version floor (the release carrying the change) in
   `jolt/README.md` and the AGENTS.md jolt notes, and add a Phase B smoke
   assertion: `jolt dist --smoke` (or a small extension of the existing smoke)
   runs the binary from an empty dir with a temp agent dir whose settings enable
   one bundled **directory** extension, and asserts the `--debug` load line
   reports `:loader-kind :jolt` (native, not SCI).

---

## 5. Sequencing

- **Phase A** (this repo only): §2 + §3. Ships the feature end-to-end on every
  host (resource artifacts via SCI on jolt). Independently valuable and
  testable.
- **Phase B** (jolt repo, then this repo): §4. The jolt change can be developed
  and released in parallel; the kmet switch is the small diff in §4.8 and is
  inert on jolt releases without the feature (probe false → SCI).
- Either order of *development* works; Phase A must be **released** before
  Phase B's kmet switch, because the switch changes loader selection under the
  assumption that `embedded-roots?` answers truthfully.

---

## 6. Risks, edge cases, open questions

- **Jolt dev URL shape**: the checkout-mode detection relies on the manifest
  resource answering a `file:` URL on jolt too. Verify early (Phase A); the cwd
  fallback in §2.2 and the resource mode keep the app working either way.
- **Resource probing across classpath roots**: `:resource-*` descriptors probe
  by prefix, so a dependency jar containing a colliding
  `extensions/...` entry could shadow a bundled file. The prefix
  is app-specific; `write-uberjar!` also dedupes by entry name (src first), so
  the bundled copy wins in the uberjar. A test can assert the probe resolves to
  the expected content.
- **`lsp-adapter`/`tree-sitter` first use**: enabling them does not imply their
  external prerequisites (language servers, tree-sitter CLI downloads) — that
  is unchanged from today and orthogonal.
- **Bundled deps need the network on first enable**: an enabled bundled
  extension with an uncached `deps.edn` dependency fails to load offline
  (warning, rollback, app continues — existing extension behavior). A binary is
  therefore not guaranteed to load *every* bundled extension offline; the
  default-disabled state plus the fixed set keep the offline guarantee for
  everything that declares no deps.
- **`:local/root` in a bundled `deps.edn`** is forbidden by the gate: the
  current resolver resolves relative local roots against the process cwd, and a
  resource artifact has no stable root in a binary.
- **Jolt binaries + deps**: `closure-jars`' docstring says `jolt.deps` is AOT'd
  into the jolt binary, so runtime resolution should work there; pin it with a
  Phase A jolt-binary check (enable a bundled artifact with one small
  `:mvn/version` dep in a temp agent dir and assert the load succeeds) — mark
  it `^:slow` / manual if the network makes it unsuitable for the default
  gates.
- **Extension registry conflicts**: two enabled extensions may still register
  the same tool/command name; that is existing behavior. Bundled-vs-user
  duplicates are handled by D11.
- **Manifest drift**: a new extension under `extensions/` fails the gate until
  added (D12) — intentional, per "include all".
- **Phase B migration window**: kmet binaries built on a jolt without embedded
  roots still work (SCI); a jolt with the feature but an older kmet is
  unaffected (kmet never passes a marker unless it computed one).
- **`kmet config` live reload**: toggles do not hot-load; `/reload` or a
  restart applies. Same as every existing resource. If live application is
  wanted later it belongs in a separate change.

## 7. Non-goals

- No npm/git sources, no remote bundles, no per-extension versions.
- No extraction/cache directory, no `~/.m2`-style staging at runtime.
- No bundled `lib/*.jar` inside an artifact, no vendored dependency jars, and
  no offline guarantee for a bundled extension that declares uncached
  `deps.edn` dependencies — those resolve and download exactly like any other
  extension's do.
- No AOT/compilation of bundled extensions into the host app (isolation and
  per-extension contexts stay).
- No change to `kmet install/remove/list`, package filters, or the auto dirs.
- No new `:paths`/classpath entries (delivery goes through the manifest +
  bundled layer, not the app's classpath).
