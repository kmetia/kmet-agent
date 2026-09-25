# Customization

Use the package commands in [Usage](usage.md#package-subcommands) to manage
local packages, then use the guides below to customize the resources they
provide.

## Themes

Create EDN theme files in `~/.kmet/agent/themes/` for global use or
`.kmet/themes/` for a project. The
[examples guide](examples/README.md#themes) documents the current schema,
color values, installation, and live reload; complete starting points are in
[`examples/themes/`](examples/themes/).

## Skills & Extensions

- **Packages**: share any mix of extensions, skills, prompt templates and
  themes as a local directory or file. `kmet install ./package [-l]` records
  the source in the settings `:packages` list; a package directory loads as a
  single extension (contains `extension.edn`) or scans its conventional
  `extensions/`, `skills/`, `prompts/`, `themes/` subdirectories. Filter what a
  package loads with the object form — `{:source "../pkg" :extensions
  ["extensions/*.clj" "!extensions/legacy.clj"]}` (plain globs include,
  `!` excludes, `+path`/`-path` force include/exclude, `[]` disables a type)
  or use `kmet config`. The same top-level arrays
  (`:extensions`/`:skills`/`:prompts`/`:themes`) select exactly which
  auto-root resources stay enabled without installing a package. A project entry with `:autoload false` is a delta over
  the global entry of the same package (pi's package model — same pattern
  semantics as pi's `packages.md`).
- **Skills**: Place `name/SKILL.md` directories (or flat `.md` files) with YAML frontmatter (`name`, `description`) in `~/.kmet/agent/skills/` or `.kmet/skills/` — listed in the system prompt as `<available_skills>`; `/skill:name` loads one on demand (Agent Skills standard, pi-compatible)
- **Prompt Templates**: Place `.md` files in `~/.kmet/agent/prompts/` or `.kmet/prompts/` — `/name args` expands to the template body with `$1`, `$@`, `${1:-default}`, `${@:N}` placeholders; unknown `/cmd` falls through to the agent (pi-compatible)
- **Extensions**: Place `.clj` files (or directories with `extension.edn`) in
  `~/.kmet/agent/extensions/` or `.kmet/extensions/` — the fixed auto roots;
  extra paths go in top-level `:extensions` settings entries (`KMET_CODING_AGENT_DIR`
  moves the agent dir). An extension is a Clojure
  namespace defining `(defn init [api])` (and optionally `(defn shutdown [api])`),
  depending only on `kmet.extension`. A manifest dir declares `{:name :entry
  :files}` — its own source deps. Loaded at startup, reloadable via `/reload`, and
  unloadable at runtime; unload runs shutdown + deregisters everything.
  Shipped opt-in extensions live in [`extensions/`](../extensions/)
  ([usage](../extensions/README.md)); writing your own:
  [`src/kmet/extension.md`](../src/kmet/extension.md)
