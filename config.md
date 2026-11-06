# Resource selection

Status: steps 1–2 implemented (`kmet.app.resources`, the per-type settings
merge, the `:resources` row and settings-schema validation; the runtime now
reads `enabled-names :tool` and keeps only the session active-tool override).
Migration steps 3–8 remain. The legacy `kmet config` screen still reads the
retired `:default-tools`/`:enabled-tools` keys until steps 4–5 replace it. This
file stays at the
repository root as the working design and plan until the migration lands;
moving it beside the source and design record is a separate cleanup task.

## Three surfaces

| Surface | Scope | Owns | Does not |
|---|---|---|---|
| `kmet plugins` | static composition | plugin rows (`:ext/<id>`) in global/project patch layers | load extension code, list resources, write `:resources` |
| `/config` | live session | `:resources` selection for providers available in the current project run | edit composition, manage disabled plugins, write plugin rows |
| `/tools` | live session only | the in-memory active-tool set | write settings, scope, save, persist selection |

`kmet plugins` is the static composition editor for plugin rows. `/config` is
the live resource selector. `/tools` is the session-only tool selector.

## Model

- Resource identity is `[type name]`, types `:tool`, `:skill`, `:prompt`;
  settings keys are `:tools`, `:skills`, `:prompts`.
- A name is the exact non-empty registry name. A path is provider metadata,
  never selection state.
- A provider makes resources **available**: plugin rows, packages, settings
  paths, auto dirs, and `:resources-discover` contributions.
- Selection makes available resources **selected**. A consumer uses a
  resource only when it is available and selected: **enabled**.
- Availability is always computed from the current project run, not from a
  settings scope. A provider is available only when it is mounted and active
  in that run.
- Mounting stays composition-only. There are no per-resource mounts.
- Commands, eval sources and themes are not selectable in v1. Composition and
  extension enablement decide whether commands and eval sources exist; themes
  remain governed by the active `:theme` setting and the picker.
- `:sandbox` tool sources are not selectable resources. Their tools stay
  callable from scripts as they are today; they are not `/config` rows.

## Store

One settings key per scope, one entry grammar for every selectable type:

```edn
;; ~/.kmet/agent/settings.edn
{:resources {:tools  ["-bash"]
             :skills ["-clojure-edit"]}}

;; .kmet/settings.edn
{:resources {:tools ["+bash"]}}
```

### Global scope: enable/disable

- A missing type list inherits defaults.
- An empty list selects nothing.
- A non-empty modifier-only list starts from defaults and applies `+name` and
  `-name` in order.
- A list with plain names is absolute: only the named resources are selected,
  plus any `+name` and minus any `-name` applied after the plain base.
- A mixed list keeps the pi rule: plain names form the base, then modifiers
  apply in list order. `/config` does not create mixed lists; it reads them for
  compatibility with hand-edited settings.

### Project scope: inherit/enable/disable

- A project type list contains only `+name` and `-name` entries.
- A missing or empty project list means every resource inherits the global
  selection.
- `+name` explicitly enables the resource in the project.
- `-name` explicitly disables the resource in the project.
- No entry for a name means inherit.
- A plain name in a project list is invalid. Warn once and ignore the whole
  project list; the project inherits global.
- Apply entries in list order; the last entry for a name decides that name.

The project list is a delta over the effective global selection, not a
replacement and not a base resolved from defaults.

### Merge and precedence

```
defaults
  -> global selection
  -> project delta
  -> /tools session override (tools only)
```

`/tools` is a session-only absolute tool set. It never writes `:resources`, has
no scope or save control, and disappears with the session. A successful tool
write in `/config` clears the `/tools` override so the persisted choice takes
effect.

### Examples

```edn
;; global: bash off, everything else default
{:resources {:tools ["-bash"]}}

;; project: bash on here, the rest inherits global
{:resources {:tools ["+bash"]}}

;; a project list with plain names is invalid: warn once, ignore the list
{:resources {:tools ["read"]}}
```

Global and project lists merge per type. The settings loader must not deep-merge
`:resources` across scopes.

### Defaults

- Skills and prompts default on.
- A tool defaults on unless it is a built-in row marked
  `:default-enabled? false` (`run_code`, `eval`).
- Extension tools default on.
- A global plain `:tools` list is absolute over every available tool, so it
  also turns extension tools off unless they are named.

This is a settings break from `:default-tools` and `:enabled-tools`:

- both keys are retired, not read, and not translated;
- startup warns once when either key is present;
- users re-select in `/config`, or edit `settings.edn` by hand.

The old keys cannot be translated without choosing one of their meanings: old
`:default-tools` was built-in-only, old `:enabled-tools` was absolute over the
live set. One list has one meaning, so the break is explicit.

## Service

`kmet.app.resources` provides the `:resources` service. It is mounted by a
plugin row near the registry rows, injects the live `:tools`, `:skills` and
`:prompts` registries, and reads/writes selection through `kmet.app.config`.

```clojure
(catalog kind)                    ; available rows with both scope states
(available? kind name)
(global-selected? kind name)
(project-state kind name)         ; :inherit | :enabled | :disabled
(selected? kind name)             ; effective persisted selection, ignore availability
(enabled? kind name)              ; available and selected
(enabled-names kind)              ; available and selected; consumers use this

(set-global! kind name enabled?)
(set-project! kind name state)    ; :inherit | :enabled | :disabled
```

`catalog` returns **available rows only**. A row carries both scope states and
the effective result:

```clojure
{:kind :resource
 :type :skill
 :name "clojure-edit"
 :provider {:kind :extension :id :ext/clojure}
 :global? true
 :project-state :inherit
 :selected? true
 :enabled? true}
```

- `selected?` is the effective persisted selection: global when the project
  state is `:inherit`, otherwise the project override. Availability is not
  consulted.
- `enabled?` is `(and available? selected?)`.
- `enabled-names` is the read-time filter for consumers.
- A stored entry whose provider is unavailable is not a row, is not returned by
  `catalog`, and is not counted in a hint. It survives in settings and applies
  when the provider becomes available again.
- Collision behavior stays the registry's. Tools keep custom shadowing and
  last-registration wins; skills keep first-wins; prompts keep their current
  registration behavior. `:resources` does not define a second provider
  precedence.
- `set-global!` preserves an absent or modifier-only global list's shape;
  enabling a default-off resource writes `+name`, disabling a default-on
  resource writes `-name`. Removing the last modifier removes the key, restoring
  defaults. A plain or mixed global list is rewritten as its effective plain
  selection; an emptied plain selection is written as `[]` (nothing).
- `set-project!` writes only the project delta. `:inherit` removes entries for
  the name; `:enabled` writes `+name` and removes `-name`; `:disabled` writes
  `-name` and removes `+name`. A write drops any plain entries from the type
  list. An emptied project type list is removed, which means inherit all.
- `resolve-scope` and the entry merge stay internal. There is no public raw
  merge API.

## Availability and visibility

- `/config` lists resources of providers mounted and active in the current
  project run, in both global and project scope.
- If a plugin is disabled in the project, its resources are not available and
  are not listed. There is nothing to manage for that provider in this run.
- A built-in tool whose composition row was removed is not available and is
  not listed.
- Stored selection for an unavailable provider is invisible and inert. It is
  not pruned. Re-enabling the provider makes the stored selection apply again.
- `kmet plugins` changes composition statically. Its changes apply on the next
  start; `/reload` does not re-read composition layers.
- A selected resource whose provider becomes unavailable stops being used at
  read time, but its stored entry remains.

## Consumers

Each consumer filters at read time with `enabled-names` or `enabled?`.
Stored selection is never pruned; a selected resource whose provider is
unavailable is inert and becomes active again when the provider is re-enabled.

- **tools**: model-facing declared schema, execution, `tool_search` candidates,
  and the registry-tool portion of the `run_code` bridge.
- **sandbox-only tools**: remain callable from `run_code`; they are not
  selectable and not filtered by `/config`.
- **skills**: system-prompt skill block and `/skill:` expansion.
  `disable-model-invocation` still excludes a skill from the prompt block but
  not from `/skill:` expansion.
- **prompts**: template lookup, expansion and autocomplete.
- **unavailable resources**: stored selection is inert; consumers ignore them.

Discovery and loaders register every available resource. They do not prune by
selection; selection is applied by the read-time filters.

## UI

### `kmet plugins`

Static composition editor for plugin rows only.

- Lists bundled and installed extension plugin rows (`:ext/<id>`).
- Reads installed extension manifests and composition layers.
- Writes plugin rows and remove patches to `~/.kmet/agent/patch.edn` or
  `.kmet/patch.edn`.
- Supports global/project scope.
- Does not load extension code.
- Does not list tools, skills, prompts, themes or packages.
- Never writes `:resources`.

Package installation stays with `kmet install`, `kmet remove`, `kmet update`
and `kmet list`.

The CLI changes from `kmet config [-l]` to `kmet plugins [-l]`. Update the
command set, help text, core dispatch, tests and docs. The old `config` command
is retired, not aliased.

### `/config`

Live in-session resource selector.

- Rows are `catalog` output: resources of providers available in the current
  project run.
- Scope switch: global / project.
- Global scope: enable/disable checkbox.
- Project scope: tri-state `inherit | enable | disable`.
- Space cycles inherit -> enable -> disable -> inherit. The row also shows the
  effective state.
- Writes through `set-global!`/`set-project!`.
- Never edits composition.
- A plugin disabled in the project has no rows.
- Stored selections for unavailable providers are not shown.
- A tool write clears the `/tools` session override.
- A save is a no-op when it would not change the normalized stored selection
  (entries and project state): no event, no refresh.
- A save refreshes selection only: emit `:resources-change` with affected
  types; tools re-resolve, record the normal `:tool-change` when the declared
  set changes, and rebuild schema/declarations/`tool_search`; skills rebuild
  the system-prompt block; prompts rebuild lookup and autocomplete. Do not
  remount providers, re-run `:resources-discover`, reload models or keybindings,
  or clear registrations.
- Full `/reload` remains the fallback and the manual reload-everything command.

`/settings` remains the scalar-settings command.

### `/tools`

Builtin, session-only selector over active tools.

- Lists tools available in the live session.
- Toggles the in-memory set through `set-active-tools!`.
- No global/project scope.
- No save key.
- No `:resources` writes.
- No separate `tools-config` branch entry.
- A resumed or switched session initializes from persisted `:resources`; a
  previous `/tools` override is not restored. The normal `:tool-change` record
  remains declaration history and replay data, not a saved selection.
- An extension cannot shadow the builtin `/tools` name.
- TUI opens the selector; non-TUI front ends may print the active tool list.

Remove `extensions/tools.clj`, the bundled `tools` artifact, its tests, docs
and examples. Remove the extension API `set-tool-enabled!` and
`tool-selection`; keep `get-active-tools` and `set-active-tools!`.

### `/reload`

Re-reads settings and selection and reloads extensions/resources. It does not
re-read composition layers. Use it as the full fallback; `/config` uses the
selection-only path above.

## Warnings and settings breaks

- `:default-tools` and `:enabled-tools`: warn once at startup; ignore.
- Plain names in a project `:resources` list: warn once; ignore the project
  list for that type; inherit global.
- Top-level `:skills`, `:prompts` and `:extensions` paths remain discovery
  sources. Signed entries are no longer selection; warn once and ignore them.
  Selection belongs in `:resources`.
- Package filters remain availability-only. A resource a package filter
  excludes has no row and cannot be selected.
- `:meta {:tools ...}` is removed from the extension manifest contract.

## Migration

1. Add the `kmet.app.resources` row and service (`:resources`), the per-type
   settings merge, the API above and settings-schema validation.
2. Replace the runtime settings path: initialize the runtime from
   `enabled-names :tool`; keep only the session active-tool override (rename
   its atom to `:active-tools` if that avoids confusion). Remove
   `merge-tool-entries`, `get-default-tools`, `get-enabled-tools`, the
   settings-derived `:default-tools` state and persisted `:enabled-tools`
   selection path, `apply-default-tools!`, `apply-enabled-tools!`,
   `enabled-tool-entries` and `default-tool-exclusions`; update
   `config.clj`, `loop/driver.clj`, `loop/state.clj`, `session/runtime.clj`,
   `session/lifecycle.clj`, `loop.clj` and the TUI builtins.
3. Replace the builtin `/tools` listing with the session selector, remove the
   `extensions/tools.clj` artifact and bundled entry, and remove the
   `set-tool-enabled!`/`tool-selection` extension API. Update
   `kmet.extension`, `extension.md`, `docs/extensions.md`, build/staging tests
   and examples.
4. Rename `kmet config` to `kmet plugins`; make it a static plugin-row editor.
   Remove resource rows and writers from the standalone screen. Update
   `packages_cli.clj`, `core.clj`, CLI help, tests and every doc that names
   `kmet config`.
5. Add `/config` as the live resource screen over `kmet.app.resources`, with
   global enable/disable, project inherit/enable/disable, the scope switch,
   override clearing and the `:resources-change` selection-only refresh.
   Declare `:resources-change` in `kmet.app.events` and update the event
   documentation. Reserve `/tools` and `/config` as builtin command names and
   update the command registry tests.
6. Add skills/prompts read-time filters. Retire top-level signed selection and
   package per-item selection writers; package filters become availability-only.
7. Remove `:meta {:tools ...}`, add the warnings above, and update docs,
   examples and generated composition docs if required.
8. Run the changed-file gates; finish with `bb test`, `bb test-ext`, `bb lint`
   and `bb format-check`.

## Tests

- Global lists: missing, empty, modifier-only, plain, mixed; empty versus
  missing.
- Project lists: inherit, `+name`, `-name`, multiple entries, last entry wins,
  plain entry invalid and warned; emptied project list inherits.
- Merge: project delta applies over global, not defaults; global empty selects
  nothing; project empty inherits.
- Defaults: built-in opt-ins (`run_code`, `eval`), extension tools default on,
  plain global list is absolute over extension tools.
- Availability: disabled project plugin has no rows; built-in removed by
  composition has no row; stored selection survives and applies after
  re-enable.
- Service: `selected?` ignores availability; `enabled?` requires both;
  `enabled-names` is the consumer filter.
- `/config`: writes global and project scopes, cycles project tri-state,
  clears the `/tools` override on tool writes, treats an unchanged save as a
  no-op, refreshes only affected views, does not remount or re-run
  `:resources-discover`.
- `/tools`: builtin, session-only, no settings or `:resources` writes, works
  without the tools extension, cannot be shadowed by an extension.
- Consumers: tools schema/execution/tool_search, sandbox-only tools still
  callable from `run_code`, skills prompt block and `/skill:` expansion with
  `disable-model-invocation`, prompts lookup and expansion.
- `kmet plugins`: static plugin rows only, no extension loading, no
  `:resources` writes; CLI usage names `kmet plugins`.
- Settings breaks and warnings; removed extension API and artifact; changed-file
  gates.

## Out of scope

- Per-command, per-eval-source and per-theme selection.
- Project-scope absolute selection lists. Project selection is always a delta
  over global.
- Offline resource selection and unavailable-resource cleanup in `/config`.
- Live composition recomposition. `kmet plugins` changes apply on the next
  start.
- MCP server enablement; that stays with the MCP adapter. MCP tools remain
  `:sandbox` contributions in v1 and are not `/config` rows.
- A generic extension API for persisted selection; add one when a consumer
  exists.
- Moving this file. `config.md` stays at the repository root as both the design
  record and the implementation plan until the migration is done; relocating it
  is a separate cleanup task.
