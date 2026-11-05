# Open design: per-resource toggles for extension-provided resources

Status: discussed, not implemented. This section records the design gap and
constraints for a later pass; the current behavior is described first.

### Current behavior

- An extension artifact is one plugin and one composition row. Disabling the
  row unloads the extension and therefore all its resources.
- A directory/jar manifest's `:meta {:tools [{:name "…"}]}` is read by
  `kmet config` as a static projection; those tool rows toggle the global
  `:enabled-tools` selection without loading the plugin
  (`kmet.app.tools.config`, `kmet.ui.tui.resource-config`).
- No other extension contribution is projected from the manifest. Skills,
  commands, prompts, themes and eval verbs/commands are registered by the
  extension at load (`kmet.extension/register-skill!`, `register-command!`,
  `register-eval-source!`, …) and are all-or-nothing with the extension.
- Single-file extensions have no manifest, so they cannot declare resource
  granularity; they stay atomic.

### Desired behavior

- When an extension row is enabled, `kmet config` lists every resource the
  extension declares in its manifest, without loading the extension.
- Each declared resource can be enabled/disabled independently while the
  extension itself stays mounted.
- Disabling the extension row disables all its resources; re-enabling it does
  not silently re-enable resources the user turned off.
- Toggles apply live in a running session, not only on the next start.

### Constraints

- `:config` is composition data, not live state. It is resolved at boot and
  passed to the plugin's `:apply` as `(:config api)`; changing it after mount
  does nothing.
- `/reload` re-reads settings and remounts extension rows, but
  `kmet.app.extensions.rows/reload-all!` reuses the entry and config captured
  when the row mounted. It does not re-read the patch layers, so a change to a
  row's `:config` is not picked up by `/reload`.
- `kmet config` is a standalone process. Its writes are visible to the next
  kmet start; it cannot update a running kmet process. Live toggling therefore
  needs an in-session surface (for example a real `/config` command reusing the
  resource-config component) or “live” must mean “after `/reload`”.
- Live toggles need read-time filtering in the registries
  (`get-commands`/`find-command`, `get-skills`, prompt/template lookup, eval
  sources), not only a skip at registration time; otherwise a toggle is
  cosmetic until the extension reloads.

### Proposed shape

Manifest catalog (static; read without loading; same validation rule as
`:tools` — a sequential list of maps with a non-empty string `:name`):

```edn
:meta {:resources
       {:tools    [{:name "clojure_edit"}]
        :commands [{:name "clojure"}]
        :skills   [{:name "clojure-edit"}]
        :prompts  [{:name "review"}]
        :themes   [{:name "my-theme"}]
        :eval     [{:name "clj/edit-form"}]}}
```

`:meta {:tools …}` stays as the legacy top-level spelling during migration.

Resource classes:

| Class | Individually toggleable |
|-------|--------------------------|
| tools, commands, skills, prompts, themes, eval sources/verbs | yes |
| hooks, renderers, flags, keybindings/shortcuts, UI ports, services | no — extension-level only |

Selection and persistence:

- Prefer persisting the selection in settings, keyed by extension id, rather
  than in the extension row's `:config`; `/reload` re-reads settings but not
  composition layers.
- Runtime state should be a live selection service the registries read on
  every access. Tools already have live selection through `:enabled-tools`;
  the other resource types need the equivalent filter.
- Decide whether tools keep their existing `:enabled-tools` path or join the
  unified per-extension selection. Keeping both would give one tool two
  independent disable switches.
- Resource rows group with the extension that declares them; a disabled
  extension row shows its resources off, and their stored selection survives
  so re-enabling does not reset it.

### Open questions

1. Live surface: add an in-session `/config` (recommended for “apply live”), or
   accept that toggles apply after `/reload`/next start?
2. Selection schema: one settings key keyed by extension id and resource type,
   or per-type keys? Define global/project merge semantics either way.
3. How to handle resources an extension registers dynamically but does not
   declare in `:meta`: remain invisible/untoggleable until declared, or list
   them from a live registry when the extension is mounted?
4. Extend `:meta` to a general `:resources` map, or keep top-level keys
   (`:tools`, `:skills`, …)?
5. Should `/reload` learn to re-read composition layers and remount changed
   extension rows, independent of this feature?

Relevant code: `modules/app/src/kmet/app/extensions/context.cljc`
(`manifest-info`, `:meta`), `modules/app/src/kmet/app/extensions/rows.clj`
(`item-meta`, `card`, `load-entry!`), `modules/app/src/kmet/app/extensions/core.cljc`
(`mount-plugin!`, `create-extension-api`),
`modules/app/src/kmet/app/tools/config.clj` (static `:meta :tools` projection),
`modules/app/src/kmet/ui/tui/resource_config.clj` (config rows/toggles), and
`modules/app/src/kmet/extension.clj` (registration wrappers).
