# Customization

Use the package commands in [Usage](usage.md#package-subcommands) to manage
packages, then use the guides below to customize the resources they
provide.

## Themes

Create EDN theme files in `~/.kmet/agent/themes/` for global use or
`.kmet/themes/` for a project. The
[examples guide](examples/README.md#themes) documents the current schema,
color values, installation, and live reload; complete starting points are in
[`examples/themes/`](examples/themes/).

## Skills & Extensions

- **Packages**: share any mix of extensions, skills, prompt templates and
  themes as a local directory or file, or a git repository
  (`kmet install ./package [-l]`, `kmet install git:github.com/user/repo`
  or `git:github.com/user/repo//packages/foo` for a package inside a
  repository; git checkouts update with `kmet update`). `kmet install`
  records the source in the settings `:packages` list; a package directory
  loads as a
  single extension (contains `extension.edn`) or scans its conventional
  `extensions/`, `skills/`, `prompts/`, `themes/` subdirectories. Filter what a
  package loads with the object form — `{:source "../pkg" :extensions
  ["extensions/*.clj" "!extensions/legacy.clj"]}` (plain globs include,
  `!` excludes, `+path`/`-path` force include/exclude, `[]` disables a type)
  `kmet plugins` composes extension rows only; package filters and the other
  resource arrays stay settings. The same top-level arrays
  (`:extensions`/`:skills`/`:prompts`/`:themes`) name auto-root resources
  without installing a package: the skills, prompts and themes arrays select
  which ones stay enabled, while an `:extensions` path only widens the
  discovered set — an extension loads when a composition row names it (see the
  Extensions bullet). A project entry with `:autoload false` is a delta over
  the global entry of the same package (pi's package model — same pattern
  semantics as pi's `packages.md`).
- **Skills**: Place `name/SKILL.md` directories (or flat `.md` files) with YAML frontmatter (`name`, `description`) in `~/.kmet/agent/skills/` or `.kmet/skills/` — listed in the system prompt as `<available_skills>`; `/skill:name` loads one on demand (Agent Skills standard, pi-compatible)
- **Prompt Templates**: Place `.md` files in `~/.kmet/agent/prompts/` or `.kmet/prompts/` — `/name args` expands to the template body with `$1`, `$@`, `${1:-default}`, `${@:N}` placeholders; unknown `/cmd` falls through to the agent (pi-compatible)
- **Extensions**: Place `.clj` files (or directories with `extension.edn`) in
  `~/.kmet/agent/extensions/` or `.kmet/extensions/` — the fixed discovery
  roots; extra paths go in top-level `:extensions` settings entries
  (`KMET_CODING_AGENT_DIR` moves the agent dir). Every extension — single file
  or manifest dir — is a plugin: its namespace defines a `plugin` var (a
  `kmet.srce.core/defplugin` map, or a plain map) whose `:apply` receives the
  extension's ctx and its api map; it may also define `(defn shutdown [api])`.
  A manifest dir declares `{:name :plugin :loader}` (`:plugin` is that var),
  and `deps.edn` carries its own source deps. Extensions depend on
  `kmet.extension` and `kmet.srce.core`.
  **Discovery is not loading.** A run mounts exactly the extensions its
  composition names as `:ext/<id>` rows, so a file in a discovery root does
  nothing until a row names it. `kmet plugins` is the editor for those rows:
  the global scope composes a row in `~/.kmet/agent/patch.edn` and a
  disable writes a `:remove` patch that keeps the row (and its `:config`),
  while the project scope writes a project row or a remove patch in
  `.kmet/patch.edn`, cycling inherit/load/unload over the global composition.
  No settings key decides whether an extension loads. A toggle lands in the layer
  file, so the next run mounts it — `/reload` re-loads the rows already
  mounted, and unloading runs the extension's shutdown then disposes
  everything it registered.
  Shipped opt-in extensions live in [`extensions/`](../extensions/)
  ([usage](../extensions/README.md)); writing your own:
  [the authoring guide](extensions.md) and the
  [`kmet.extension` contract](../modules/app/src/kmet/extension.md).
