# Resource selection

Status: agreed design, not implemented. Replaces the earlier per-extension
resource-toggle proposal.

## Goal

One enablement model for tools and every other selectable asset. Composition
decides what exists in a run; one settings selection decides what that run
uses. Extension resources are ordinary resources with names, not a separate
selection system.

## Model

- Resource identity is `[type name]`: `[:tool "bash"]`, `[:skill "clojure-edit"]`.
- Selectable types: `:tools`, `:commands`, `:skills`, `:prompts`, `:themes`,
  `:eval`.
- Composition and discovery make resources **available**: plugin rows,
  packages, settings paths, auto dirs and `:resources-discover`.
- Selection makes available resources **enabled**: one settings key per scope.
- Mounting stays composition-only. There are no per-resource mounts.

The words:

- **available** — the run has the resource (registered, installed or
  discovered).
- **selected** — the user wants it used.
- **mounted** — a plugin row actually loaded the provider.

A resource can be available but not selected, or selected but unavailable
(provider disabled). Its stored selection survives either state.

## Store

One settings key, one entry grammar for every type:

```edn
;; ~/.kmet/agent/settings.edn
{:resources {:tools  ["-bash"]
             :skills ["-clojure-edit"]}}

;; .kmet/settings.edn
{:resources {:tools ["+bash" "+run_code"]}}
```

- A missing key or type inherits the next scope, then the type's defaults.
- A list with plain names starts from those names; a modifier-only list
  starts from the type's defaults.
- `+name` adds and `-name` removes, in list order.
- A modifier-only project list appends to the global list; a project list
  with any plain name replaces it.
- An empty list selects nothing.
- Entries for absent resources are inert and remain stored.

Effective precedence:

```
session tool selection  >  merged project+global entries  >  defaults
```

The session override exists only for tools (`/tools`, `set-active-tools!`).
Other resource types are file-selected only.

This is pi's `defaultTools` for `:tools`, extended to every resource type.
`:default-tools` and `:enabled-tools` are retired; there is one tool list.

## Service

`kmet.app.resources` is the only owner of selection state:

```clojure
(snapshot kind opts)       ; grouped rows for kmet config / /config
(enabled? kind name)       ; consumer read
(set! kind name on? opts)  ; global/project write
(merge-entries user project)
(resolve-entries entries available defaults)
```

A kind table replaces per-type conditionals:

```clojure
{:tool    {:default-on default-tool? :reserved #{}      :available tools/available}
 :command {:default-on all?          :reserved core?    :available commands/available}
 :skill   {:default-on all?          :reserved #{}      :available skills/available}
 :prompt  {:default-on all?          :reserved #{}      :available prompts/available}
 :theme   {:default-on all?          :reserved #{}      :available themes/available}
 :eval    {:default-on all?          :reserved builtin? :available eval/available}}
```

Registries keep their current responsibilities; they only report availability.
They do not own enablement.

### Consumer reads

Each consumer filters at read time; management APIs keep returning everything,
so a disabled resource can always be re-enabled:

- tools: declared schema, execution, `run_code` surface, `tool_search`
- commands: dispatch, autocomplete, help
- skills: prompt block, `/skill:` expansion
- prompts: template lookup and expansion
- themes: picker; disabling the active theme falls back to the default
- eval: environment values and `/eval` verbs

`get-all-*` and the selection snapshot read the unfiltered set.

## UI

One row shape for every type:

```clojure
{:type :skill
 :name "clojure-edit"
 :provider {:kind :extension :id :ext/clojure}
 :available? true
 :selected? true
 :reason nil}
```

The screen groups rows by `:provider` (bundled, package, file source,
extension). Grouping is display only; selection stays keyed by `[type name]`.

A selection row writes `resources/set!`. An extension row writes the
composition layer. `kmet config` owns both writes for the next start. `/config`
shows composition rows read-only with a restart-required hint; it writes only
selection.

### Disabled extensions

Disabling an extension row removes it from composition. It does **not** edit
`:resources`, and it does not unregister the extension's selection entries.

Its declared resources stay grouped in the UI, marked unavailable:

```
Extension: clojure (disabled)
  [x] skill clojure-edit      unavailable: extension disabled
  [ ] command clojure
```

Stored selection survives disable, re-enable and `/reload`. Re-enabling the
extension restores exactly the user's prior choices. No automatic pruning on
disable. An uninstalled extension loses its declared rows; its inert selection
entries remain harmless.

## Manifest catalog

An extension may declare its resources for the standalone `kmet config`
process, which must list them without loading the extension:

```edn
:meta {:resources [{:type :tool  :name "my_tool"}
                   {:type :skill :name "my-skill"}]}
```

The catalog lives under `:meta`. There is no top-level `:resources` manifest
key: top-level manifest keys are the runtime contract (`:name`, `:plugin`,
`:loader`), while the catalog is host metadata read only by `kmet config`.
The settings selection key is also `:resources`; nesting the catalog under
`:meta` keeps the two shapes distinct.

- A flat list is the one declaration shape.
- Each entry needs a valid `:type` and a non-empty string `:name`.
- Invalid entries are skipped with a warning; they never fail an extension
  load.
- The declaration is a catalog, not the authority. In-session, live
  registrations are authoritative; dynamically registered resources are
  visible to `/config` without a declaration.
- `:meta {:tools ...}` is removed in the same change. Update bundled
  extensions, docs and tests; no legacy alias.

## Per-type rules

| Type | Defaults | Reserved |
|---|---|---|
| tools | on, except `:default-enabled? false` | none; `tool_search`/deferred rules still apply |
| commands | on | core commands the UI needs to recover (`/reload`, `/quit`, `/config`) |
| skills | on | none |
| prompts | on | none |
| themes | on | active theme falls back if disabled |
| eval | on | built-in `user` and `clj` sources |

Selected resources are read-time filtered; disabling never unregisters a
resource. Tool selection keeps its existing `:tool-change` session recording
and declaration replay as a tool-only adapter.

## Surfaces

- `kmet config` — standalone, edits files for the next start. Lists declared
  extension resources, file resources and package resources.
- `/config` — in-session, live selection over the live registries. Composition
  changes remain restart-required.
- `/tools` — a `:tools` view over the same store; the session toggle stays a
  tool-only runtime scope.
- `/reload` — re-reads settings and selection, reloads extensions/resources.
  It does not re-read composition layers.

Discovery paths and package filters are installation, not per-item
enablement. Per-item checkboxes write `:resources` for every type; package
patterns remain bulk/advanced discovery filters.

## Remove

- `:default-tools` and `:enabled-tools`
- `kmet.app.tools.config` selection and both writers
- per-item path writers in `kmet.app.packages`
- per-type toggle branches in `kmet.ui.tui.resource-config`
- `:meta {:tools ...}`

Keep the separate registries: they have different consumers. Only selection is
shared.

## Migration

1. Add `kmet.app.resources` (`resolve-entries`, merge, kind table, snapshot,
   set!) with tests; adapt the existing tool settings behind it.
2. Switch `kmet config` and `/tools` to the service; delete the duplicate tool
   writers.
3. Replace `:default-tools`/`:enabled-tools` with `:resources :tools`; update
   examples, docs and tests.
4. Add the other kinds and their read-time filters; retire per-item path
   writers.
5. Add `:meta :resources`, remove `:meta :tools`, update bundled extensions and
   docs.
6. Add the live `/config` surface; keep `kmet config` as the next-start editor.

Replace extension API `set-tool-enabled!`/`tool-selection` with generic
`set-resource-enabled!`/`resource-selection` and update in-repo consumers in
the same change. `set-active-tools!` remains the tool session override.

Tests: resolution and scope merge per type; disable/re-enable and `/reload`
preserve selection; reserved commands/sources cannot be disabled; unavailable
rows are distinct from unselected rows; manifest validation; changed-file
gates.

## Out of scope

- Live composition recomposition (`design.md` § Open at HEAD 1).
- Per-eval-verb toggles; selection is at source granularity.
- MCP server enablement; that stays with the MCP adapter.
- A per-extension selection key.
