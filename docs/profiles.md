# Profiles and composition

A profile is the set of services a kmet run mounts. `--profile` picks one; the
default is `tui`.

```sh
kmet                                   # the TUI (--profile tui)
kmet --profile print "list the files"  # one prompt, then exit
kmet --profile rpc                     # EDN command stream on stdin/stdout
kmet --profile server --port 7777      # the same protocol on a socket, one agent per connection
```

`--mode` is the older spelling of the same switch: `--mode rpc` runs the rpc
profile, and `--mode text` / `--mode edn` run the print profile and choose its
output — `-p`/`--print` is `--mode text`.

## What a run mounts

Every profile composes the same kind of thing: **rows**. A row is one service —
the extension host, the event bus, the tool registry, the session log, the
agent factory, the system-prompt builder, the mode that runs the whole thing —
and the row list is what a boot mounts, in order.

The built-in composition — every bundle and profile, row by row, with what each
row injects and provides and the tools the model is offered — is the generated
[`composition.md`](composition.md). Do not edit it: it is what the build ships,
and `bb check-catalog` fails when it falls behind.

For your own machine, `--dump-config` prints the same list with your layers
applied, without booting anything:

```sh
kmet --dump-config
```

Each line names the row id, the namespace that provides it, what the row
injects from other rows, what it provides, and its config. `--dump-state`
answers the other question — what a boot actually built: it boots the same
rows and prints the ctx tree, with each fiber's state, what it provides and
what it is waiting for. A row that declares `:defer` (`:world`, the process
world the entry mounts for a run) is not mounted by a dump — a dump must not
read your settings — so the tree is a row shorter, and a trailing
`deferred: …` line names what was held back.

## Patching a composition

A **layer** is plain EDN — the same shape the built-in profiles use. Two files
(below) apply a layer to every profile, and `--patch FILE` adds one to a
single run:

```clojure
{:rows  [{:id     :my-service
          :plugin my.extension/service-row}]   ; a row to insert
 :patch [{:id :tools :remove true}             ; and edits to existing ones
         {:id :agentLoop :config {:max-steps 8}}]}
```

A patch entry addresses a row by `:id` and does one of three things:

- **change its config** — `{:id :agentLoop :config {...}}` deep-merges into the
  row's config;
- **replace its source** — `{:id :tools :plugin my.ns/plugin}`, with the card
  keys `:name :inject :provide` if the replacement needs them;
- **remove it** — `{:id :tools :remove true}`.

A row without an `:id` in `:patch` is a **new row**: it takes `:after`/`:before`
to say where it goes (or appends), and must carry a `:plugin`. Its `:id`
defaults to the plugin's name.

### Your own layers

Two files apply to every profile, so a run needs no `--patch` flag to carry
them:

1. `~/.kmet/agent/patch.edn` — global (`KMET_CODING_AGENT_DIR` relocates it);
2. `.kmet/patch.edn` — project-local, resolved against the current directory.

A missing file is not a layer; an existing one is read and validated like a
`--patch` file, so a malformed file fails the composition by name rather than
being ignored.

The application order is **built-in table, global, project, then each
`--patch` flag in the order given**, so a project layer overrides a global one
and a `--patch` flag overrides both. This is kmet's usual scope order (project
over user), applied to composition; [`architecture.md`](architecture.md) § Composition
records the difference from dsh, whose home-level patch is applied after the
profile's own and no project layer exists.

A project layer is project code: a row names a plugin var or an installed
`:ext/<id>`, so a `.kmet/patch.edn` in a cloned repository can mount code at
boot. kmet has no project-trust prompt (project settings and resources load
unconditionally, as in pi), so review the file like any other project
configuration before running in that directory.

### Extensions as rows

Extensions are plugins: a run loads exactly the ones its composition names,
and nothing else loads an extension. A layer names one by id:

```clojure
{:rows [{:id :ext/grep-tool           ; the row identity a patch targets
         :plugin :ext/grep-tool        ; the installed extension's id
         :config {:roots ["src"]}}]}   ; reaches the extension as (:config api)
```

The id is the manifest `:name`, or a single file's basename without `.clj`
(`grep-tool.clj` is `:ext/grep-tool`); `:id` defaults to the source, so it can
be omitted until another layer needs to patch the row. Resolution reads the
installed set — top-level settings paths, `~/.kmet/agent/extensions` and
`.kmet/extensions`, packages, and the bundled artifacts — without loading
anything, so `--dump-config` names the row and an unknown id is a composition
error before boot. The first copy in pi precedence wins, so a user's copy
shadows a bundled one.

Settings no longer enable an extension: `:extensions` paths and package
sources are discovery, and the retired `:bundled-extensions` key does not
decide what loads. The row must mount after the `:extensions` host row (the
first row `:bundle/minimal` actually mounts — the deferred `:world` row above
it is the entry's, not the boot's — so an appended layer already follows);
disposing the row unloads the extension, and `/reload` reloads each mounted
row's extension with the same config (a changed composition needs a restart).

`kmet config` is the editor for these rows: space composes the selected
extension in `~/.kmet/agent/patch.edn` or disables it with a remove patch
(the row and its `:config` stay, so re-enabling is lossless); the project
scope writes a project row or a remove patch in `.kmet/patch.edn`. A write
re-renders the file's EDN and keeps its leading comments (inline comments
inside the map are not preserved). The config screen also lists skills,
prompts, themes and built-in tools, but those keep their settings-backed
toggles.

The extension's own plugin still mounts under the host ctx, so an `:ext/…` row
belongs to a root profile, not an agent preset. Built-in tools are rows too
(`:tool/read`, `:tool/bash`, … in the built-in bundle), so a layer can remove
one from the composition; their runtime selection is still `:default-tools` in
`settings.edn`, which `kmet config` toggles and which only filters the tools the
composition mounted.

A row-mounted extension initializes during boot, before the process world loads
its provider catalogs and `models.edn`; the world then recomposes provider
registrations, so registering one is safe, but reading the catalog at init is
not.

A patch that targets an id an earlier layer *removed* is a no-op (a project
layer disabling a row the global layer already disabled does not fail); an id
that was never present is still an error. A `:remove` patch therefore needs a
row to target somewhere earlier in the composition: `kmet config` keeps a
disabled extension's row (with its config) and only adds the remove patch, so
if you delete that row by hand, delete its remove patch too.

Every mistake is refused by name before anything boots: an unknown row id, a
duplicate id, a patch that changes nothing, a `:remove` that also sets config,
an unknown patch key, or a file that is not `{:rows … :patch …}` — each names
the offending entry (`:kmet.app/unknown-row`, `:kmet.app/bad-patch`,
`:kmet.app/bad-patch-file`, …).

## Checking a layer before you run it

`--dump-config` composes and prints without booting, so it is the cheap way to
see what a layer did:

```sh
kmet --dump-config | grep my-service
kmet --dump-config --patch ./layer.edn | grep my-service
kmet --dump-state  --patch ./layer.edn      # …and what the boot made of it
```

`--dump-default-config` prints the shipped table alone — no global or project
layer, no `--patch` — which is what to diff against when you want to see what
your files changed:

```sh
diff <(kmet --dump-default-config) <(kmet --dump-config)
```

`--dump-config`, `--dump-default-config` and `--dump-state` are separate dumps;
passing more than one is a usage error rather than a silent pick.

## Profiles this build ships

| profile | what it does |
|---|---|
| `tui` | the interactive terminal UI (the default) |
| `print` | runs one prompt and exits; `--mode text` streams the answer, `--mode edn` prints the session header and events as EDN |
| `rpc` | the long-lived EDN protocol on stdin/stdout — see [`rpc.md`](rpc.md) |
| `server` | the same protocol on a socket, one agent per connection; `--port` (0 asks the OS) or `--socket`, a bearer token, `--max-sessions` — see [`serve.md`](serve.md) |

A profile whose mode row a layer removed is not an error: the boot mounts the
remaining rows, reports what it mounted, and exits, which is how you inspect a
composition without running an agent (and without loading the process world,
which only a run needs).
