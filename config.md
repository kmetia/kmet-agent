# Resource selection

Status: agreed design, not implemented. Replaces the earlier per-extension
resource-toggle proposal.

## Model

One enablement model for tools and the other selectable assets: skills and
prompts. Composition decides what exists in a run; one settings selection
decides what that run uses. Selectable extension resources are ordinary
resources with names.

Commands, eval sources and themes are not selectable in v1. Composition and
extension enablement already decide whether commands and eval sources exist;
per-command and per-source selection would add a second selection layer without
a clear benefit. Themes remain governed by the active `:theme` setting and the
picker. All three can be added later without changing this model.

- Resource identity: `[type name]`, types `:tool`, `:skill`, `:prompt`;
  settings keys `:tools`, `:skills`, `:prompts`.
- A name is a canonical non-empty string. A path is provider metadata, never
  selection state.
- Names are first-wins across providers: two providers offering the same
  `[type name]` produce one resource, owned by the first available provider.
- Composition and discovery make resources **available**: plugin rows,
  packages, settings paths, auto dirs and `:resources-discover`.
- Selection makes available resources **selected**. A consumer uses a resource
  only when it is available and selected: **enabled**.
- Mounting stays composition-only. There are no per-resource mounts.
- A resource can be available but not selected, or selected but unavailable
  (provider disabled). Stored selection survives either state.

## Store

One settings key per scope, one entry grammar for every type:

```edn
;; ~/.kmet/agent/settings.edn
{:resources {:tools  ["-bash"]
             :skills ["-clojure-edit"]}}

;; .kmet/settings.edn
{:resources {:tools ["+bash" "+run_code"]}}
```

Within a scope: a missing list inherits; plain names are absolute; a
non-empty modifier-only list starts from defaults; an empty list is nothing.

Across scopes: a missing project list inherits the global selection; any
project list replaces it. The project list resolves in its own scope, so a
modifier-only project list starts from defaults, not from the global list.
Skills and prompts default on; tools follow the rule below.

The settings loader must merge `:resources` per type with these rules, not
deep-merge it.

Effective precedence:

```
/tools session selection (tools only)  >  merged persisted selection  >  defaults
```

`/tools` is the session-only surface and never writes `:resources`. Saving
`/config` clears the override when the effective `:tools` selection changes.

### Tool defaults

A tool is on by default unless it is a built-in row marked
`:default-enabled? false` (`run_code`, `eval`). Extension tools are on by
default, matching current behavior where `:default-tools` filters built-ins and
leaves extension tools enabled. A plain `:resources :tools` list is absolute
over every available tool, so it also turns extension tools off unless named.

This is a settings break from `:default-tools` and `:enabled-tools`:

- both keys are retired, not read, and not translated;
- startup warns once when either key is present;
- users re-select in `/config`, or edit `settings.edn` by hand.

The old keys cannot be translated without choosing one of their meanings: old
`:default-tools` was built-in-only, old `:enabled-tools` was absolute over the
live set. One list has one meaning, so the break is explicit.

## Service

`kmet.app.resources` owns persisted selection. The tool session override stays
with the tool adapter (`set-active-tools!`); this service reports persisted
selection only.

```clojure
(catalog kind opts)                  ; rows for /config
(selected? kind name opts)           ; stored intent, independent of availability
(enabled? kind name opts)            ; available and selected
(set-selected! kind name state opts) ; write the scope's selection
```

- `state` is `true`, `false` or `nil` (nil = remove/inherit). The write keeps a
  nil or modifier-only list's shape (enabling a default-off resource writes
  `+name`; disabling a default-on resource writes `-name`); otherwise it
  rewrites the list as its effective plain selection. Both scopes use this
  rule; a missing project list is materialized from the effective selection so
  the write replaces global explicitly.
- `opts` carries `:scope` (`:global` or `:project`; default project when the
  cwd has `.kmet/`, else global). The tool adapter applies the `/tools` session
  override on top of the persisted selection.
- `selected?` is stored intent, so an unavailable resource still reports
  `true`. `enabled?` is `(and available? selected?)`.
- `catalog` reads availability from the kind table and returns available
  rows only. A saved entry whose provider is absent is not a row; `/config`
  counts it in a hint.
- `resolve-scope` and `apply-modifiers` stay internal. There is no public
  raw-entry merge API.

Kind table:

```clojure
{:tool   {:available tools/available   :default-enabled? default-tool?}
 :skill  {:available skills/available}
 :prompt {:available prompts/available}}
```

`default-enabled?` defaults to true. The settings key is derived from the type
(`:tool` -> `:tools`, ...). Keep the separate registries: they have different
consumers. Only selection is shared.

Each consumer filters at read time; management APIs keep returning everything,
so a disabled resource can always be re-enabled:

- tools: declared schema, execution, `run_code` surface, `tool_search`
- skills: prompt block, `/skill:` expansion
- prompts: template lookup and expansion

Discovery and loaders register every **available** resource. They do not prune
by selection: disabling never unregisters, and a selection change is applied by
`/reload` (or the next run), not at registration time. Selected resources are
read-time filtered. Tool selection keeps its existing `:tool-change` session
recording and declaration replay as a tool-only adapter.

## UI

```clojure
{:kind :resource
 :type :skill
 :name "clojure-edit"
 :provider {:kind :extension :id :ext/clojure}
 :selected? true}
```

Rows are available resources only. A saved entry whose provider is absent is
not a row; it is counted in the `/config` hint. While a `/tools` session
override is active, `/config` shows it in the header hint, not as a row state.
Composition rows use `{:kind :composition ...}` and do not use
`[type name]`. Grouping by `:provider` is display only; selection is keyed by
`[type name]`.

### Surfaces

- `kmet plugins` — standalone composition editor. Reads installed extension
  manifests for the composition rows and writes the composition layers (which
  plugin/extension rows load); it does not load extension code, list selectable
  resources, or write `:resources`. Resource selection is `/config`-only in v1.
- `/config` — in-session resource toggle over live selectable resources only,
  and the only resource-selection editor. It enables and disables, has the
  global/project scope switch, and writes `:resources` in that scope. It clears
  the tool session override when the effective `:tools` selection changes,
  then applies the change. Initially it forces the existing full `/reload`
  path; the follow-up below replaces that with a selection-only refresh. It
  does not edit composition. Saved entries whose provider is absent stay
  stored and are counted in a hint.
- `/tools` — the builtin session-only tools view. In TUI it opens the
  selector; headless/print mode keeps the existing `/tools` listing. Its toggles
  call `set-active-tools!` and record the session branch entry; it never writes
  `:resources` and has no save/scope controls. Use `/config` to persist a tool
  selection. Saving a changed tool selection in `/config` clears the `/tools`
  override.
- `/reload` — re-reads settings and selection and reloads extensions and
  resources. It does not re-read composition layers.

`/config` selects resources; `/settings` remains the scalar-settings command.

Discovery paths and package filters are installation, not per-item enablement.
Package filters decide availability only: a resource they exclude has no row
and cannot be selected. Per-item checkboxes in `/config` write
`:resources` for every selectable type.

A disabled extension's selectable resources are unavailable and not listed.
Its stored selection remains inert; re-enable the extension in `kmet plugins`,
then use `/config` to edit it. No pruning on disable.

## Migration

1. Add `kmet.app.resources` (`resolve-scope`, `apply-modifiers`, kind table,
   catalog, `selected?`, `enabled?`, `set-selected!`) with tests.
2. Rename the `kmet config` CLI to `kmet plugins`; make it composition-only:
   remove its selectable-resource rows and writers and keep the composition
   rows. Delete `kmet.app.tools.config` selection and both writers, and remove
   the per-type resource toggle branches from `kmet.ui.tui.resource-config`.
   Replace the builtin `/tools` listing command with the selector in TUI,
   keeping the listing for headless mode. Move that selector from
   `extensions/tools.clj` to the builtin: keep `set-active-tools!` and the
   session branch restore, remove `Ctrl+S`, the scope tab, and the
   `set-tool-enabled!`/`tool-selection` calls. Remove the bundled `:ext/tools`
   row and the extension file; update the bundled set, build tests and
   docs/examples that point at it.
3. Reserve builtin command names: an extension command cannot shadow a builtin,
   including `/tools` and the new `/config`. Update the command registry and
   its tests.
4. Add `:resources :tools` and `/config` as the only resource-selection editor
   (a new generic resource screen over `kmet.app.resources`). `/config` writes
   `:resources`, clears the tool session override when the effective `:tools`
   selection changes, and initially calls the full `/reload` path. Retire
   `:default-tools`/`:enabled-tools`: stop reading them and warn once when
   present. Treat this as a settings break; old keys are not translated.
5. Add `:skills` and `:prompts` and their read-time filters. Loaders register
   every available resource; selection filters reads. Retire package per-item
   `:enabled` filtering and path writers; package filters become discovery-only.
6. Remove the remaining `:meta {:tools ...}` projection and update docs and
   tests. No `:meta {:resources ...}` catalog in v1. The follow-up section
   replaces `/config`'s full reload with a selection-only refresh.

Remove extension API `set-tool-enabled!`/`tool-selection` in the same change;
the old tools extension was their only in-repo consumer. Do not add a generic
`set-resource-enabled!`/`resource-selection` pair until an extension needs one.
`set-active-tools!` remains the tool session override.

Tests: type/name normalization and first-wins collisions; missing versus
present project lists; modifier-only lists resolve from defaults; empty selects
nothing; plain versus modifier tool lists, including extension tools and
`:default-enabled? false`;
`/config` initially writes then reloads, writes the chosen scope, and clears the
tool override only when the effective `:tools` selection changes; `/tools` is
builtin, applies a runtime override without writing `:resources`, and works
without the tools extension; builtin command names cannot be shadowed; package
filters as availability only; `:meta :tools` removal; changed-file gates;
`kmet plugins` writes composition only and no `:resources`; CLI usage/help
names `kmet plugins`.

## Follow-up: partial selection refresh

After the migration steps land, replace `/config`'s full `/reload` with a
selection-only refresh. Preconditions: loaders register every available
resource (step 5), and every consumer reads `selected?`/`enabled?` at read
time.

Add one app event, `:resources-change`, emitted by the `/config` save path
after all of its writes complete. `set-selected!` stays a pure write. `/config`
clears the tool session override when the effective `:tools` selection
changes, emits the event once, and does not:

- unmount or remount extension rows;
- re-run `:resources-discover`;
- reload models, keybindings or context files;
- clear or re-register skills or prompts.

Each consumer refreshes only its derived view:

| type | refresh |
|---|---|
| tools | reapply the selection; rebuild the declared schema, declarations and `tool_search` candidates |
| skills | rebuild the system-prompt skill block |
| prompts | rebuild template lookup and expansion |

Keep full `/reload` as the fallback for anything not covered, and as the manual
"reload everything" command. Guard the `/config` path with an
effective-selection diff: an unchanged save does not emit `:resources-change`
or reload.

Tests: a selection change rebuilds the affected views without unmounting
extensions or re-running `:resources-discover`; unselected resources stay
registered; a non-tool save preserves the `/tools` override and a tool change
clears it; an unchanged save is a no-op; full `/reload` still works.

## Out of scope

- Per-command, per-eval-source and per-theme selection. Commands and eval
  sources are composition-controlled; themes stay with the active `:theme`
  setting and the picker. Add them later if needed.
- Offline resource selection, including a manifest catalog. Resource
  selection is `/config`-only in v1; add offline browsing later if needed.
- Live composition recomposition (`design.md` § Open at HEAD 1).
- MCP server enablement; that stays with the MCP adapter. Individual MCP tools
  are ordinary `:tool` resources.
- A per-extension selection key.
- A generic extension API for persisted selection; add one when a consumer
  exists.
