# The kmet.extension contract

An extension is a Clojure namespace with an explicit contract: it depends on
`kmet.extension` (and, when it names its plugin var, the kernel's public
`kmet.srce.core`), and is loaded, reloaded and unloaded at runtime. This document is the authoritative reference for that contract —
the `kmet.extension` function table, the `api` and context map keys, the
allowed namespaces, and the lifecycle guarantees.

Related docs: the authoring guide [`docs/extensions.md`](../../../../docs/extensions.md)
covers layouts, manifests, loaders, dependency resolution, UI patterns and
the testing workflow; [`extensions/README.md`](../../../../extensions/README.md)
is the shipped catalog; [`docs/customization.md`](../../../../docs/customization.md)
covers user-facing setup and discovery. Keep this reference and the authoring
guide in sync whenever the described behavior changes.

The contract has **no compatibility promise before 1.0**: the names, the
allowed-namespace list and the `api` keys may change between releases, and the
in-repo extensions under `extensions/` are updated in the same change
(`AGENTS.md` § Pre-1.0: the extension ABI is not frozen).

## The contract

An extension **is a plugin**: its namespace defines a `plugin` var (with
`kmet.srce.core/defplugin`, or a plain map) whose `:apply` the loader calls
with the extension's ctx and a map of runtime-bound capabilities — register
everything there. `:apply` may answer `{:srce/setup p}` — a promise — for a setup
it cannot finish inline: the mount stays `:starting` (its names invisible,
dependents `:pending`), and the next settle decides it. Any other return value
is checked as an effect, so a body that starts background work answers `nil`
rather than the `future` it started (that is `:srce/bad-effect`). The map's
`:inject` decides when the mount activates.
Optionally define `(defn shutdown [api])` for teardown — it runs on
unload/reload, and **everything the extension registered is automatically
unregistered** afterwards. A `shutdown` that cannot finish inline returns an
*undelivered promise* instead of blocking: the registrations, the rows, the
fiber and the record still go immediately (nothing ever sees a half-unloaded
extension), and only the extension's own code — its namespaces and jars, which
a running `shutdown` may still be executing from — is released when the promise
settles. `kmet.libs.concurrent/after` is the chain for it.

A composition can mount an installed extension as a row (`:ext/<id>`, see
[`docs/profiles.md`](../../../../docs/profiles.md#extensions-as-rows)); the
row's `:config` then arrives as `(:config api)`.

```clojure
(ns my.hello-ext
  (:require [kmet.extension :as ext]
            [kmet.srce.core :as srce]))

(srce/defplugin plugin
  {:name  :ext/hello
   :apply (fn [_ctx api]
            (ext/register-command! api
              {:name        "hello"
               :description "Say hello"
               :handler     (fn [ctx args] (str "Hello, " args "!"))})
            nil)})

(defn shutdown [api]
  ;; optional teardown — deregistration is automatic
  nil)
```

`shutdown` is optional. Every `ext/...` wrapper takes `api` as its first
argument and dispatches to the runtime; extensions never require kmet internals.

The table of wrappers and the host's api map are contract-tested as a pair: a
key a wrapper reads (`(:notify (ui api))` reads `[:ui :notify]`) must exist in
the api the host builds, or an extension fails inside its plugin body with
a nil-invoke. `kmet.app.extensions.test-api-contract` reads every such path
out of this file, loads a real extension and asserts the host carries them all
— so adding a wrapper means adding the capability, in one change.

### Rows the extension mounts

An extension's plugin map may declare `:rows` — composition-shaped rows it
mounts **inside its own ctx**, after `:apply` returns:

```clojure
(srce/defplugin plugin
  {:name  :ext/review
   :apply (fn [ctx api]
            (ext/register-command! api {:name "review" :description "…"
                                        :handler (fn [_ _] nil)}))
   :rows  [{:id :review/store :plugin 'my.review.store/plugin
            :config {:dir "reviews"}}]})
```

This is the extension as a small composition of its own: each row gets the
kernel's `:inject` waiting, its `:config`, effects and disposal, instead of a
hand-written `srce/plugin!` in `:apply`. The rows are **children of the
extension's fiber** — unloading or reloading the extension disposes them in
reverse order — and a service a row provides is visible to the extension's
subtree, not to the app. The app-wide services belong to the profile's
composition, and the kernel refuses a second provider of a name already on the
chain, so an extension row cannot replace the tool registry or the UI.

The `:plugin` is a **quoted symbol** naming a plugin var — one of the
extension's **own** namespaces (resolved through its loader, which is how an
extension structures itself out of rows) or a namespace on the host classpath.
A var value works too. Refused at load, naming the reason: an `:ext/…` source
(an extension cannot compose another installed extension), a `:mode` flag (the
profile picks the run's mode), a row without a keyword `:id`, and any key
outside `{:id :plugin :config}`. A row that does not reach `:active` — an
`:inject` nothing provides, a typo — fails the load instead of leaving half an
extension mounted; the list is validated before the body runs, so a bad
declaration registers nothing.

## Allowed namespaces and isolation

Every extension runs in its own isolated context, forked from one shared base:

- Different extensions may use different versions of the same library — jars
  are served per extension, so there is no first-wins shadowing.
- Unloading releases everything it pulled in — its namespaces, closures and
  jars become unreachable; nothing global is touched.
- A version change takes effect on `/reload` — a fresh context is built and
  the deps re-resolve.
- Rebinding the *root* of a shared var (`alter-var-root`, `alter-meta!`) is
  not isolated across extensions — don't monkey-patch the shared layers.

What an extension may require is a fixed, enforced list:

- `kmet.extension` — the whole contract surface (every wrapper below).
- `kmet.srce.core` — the kernel's public plugin/ctx/service surface, so an
  extension can define plugins, provide services and use the ctx tree directly.
  Only this one namespace is shared; the `kmet.srce.*` sub-namespaces stay
  host machinery.
- The shared layers `kmet.tui.*`, `kmet.libs.*` and the `kmet.reakt.*` /
  `kmet.hikap.*` framework modules, shared by reference with
  the host (there is nothing to reimplement; the authoring guide's
  § Building UI covers the component library).
- The shared renderer namespaces `kmet.ui.tui.tool-renderers` and
  `kmet.app.keybindings`.
- `clojure.*` and `babashka.*` builtins, and the fixed bundled set below.

Any other kmet namespace — internals such as `kmet.app.*`, `kmet.srce.*`
(except `kmet.srce.core`),
and the loader `kmet.loader*` (host machinery) — fails the load with an
explicit error.

Two capabilities are routed rather than free, because they cross the host
boundary: outbound HTTP goes through `kmet.libs.http` (requiring
`babashka.http-client` fails the load), and background work through
`kmet.libs.concurrent/spawn` (the SCI host contexts have no
`future`/`pmap`/`pcalls`). Both are covered — options, examples and
rationale — in the authoring guide
([`docs/extensions.md`](../../../../docs/extensions.md)).

### Bundled extension libraries (fixed set)

kmet ships a fixed set of libraries in **both** artifacts and shares them by
reference with every extension context and `run_code` script: bb's bundled
ports serve the names on babashka, and `jolt dist` AOT-compiles kmet's pinned
Maven deps into the Jolt binary. On both hosts the library is loaded once
(extensions through `host-requires!`, scripts on the first `run_code` call),
so sharing costs nothing per user and keeps `identical?`/protocol identity.
Requiring a set member needs **no `deps.edn`** and no host-conditional code.
The set is enumerated in `kmet.app.extensions.libs`:

| Library | Namespaces | Notes |
|---|---|---|
| rewrite-clj | `rewrite-clj.*` | bb's bundled port; the pinned Maven jar on Jolt |
| edamame | `edamame.core` | reader-error/delimiter detection |
| clojure.tools.reader | `clojure.tools.reader*` | bb's reduced port; the Maven lib on Jolt |
| clojure.spec | `clojure.spec.alpha` | bb's port; the Maven lib on Jolt |
| cljfmt | `cljfmt.core`, `cljfmt.config` | 0.16.6 on both |
| parinferish | `parinferish.core` | pure Clojure, 0.8.0 on both |

Plus the libraries shared from the host itself: `clojure.*` (`core.async`
and `rrb-vector` included), `babashka.fs`, `babashka.process`, and the kmet
layers (`kmet.extension`, `kmet.tui.*`, `kmet.libs.*`, `kmet.reakt.*`,
`kmet.hikap.*` and the shared renderer namespaces).

The **guarantee is the namespace surface, not the version**: on babashka a
bundled port decides its own edition, while Jolt uses the version kmet pins
in `deps.edn`. Pinning a set member in an extension's `deps.edn` therefore
does **not** override the shared copy — declare only libraries **outside**
the set.

Everything else babashka bundles (cheshire, clj-yaml, http-kit, selmer,
transit, hiccup, timbre, nextjournal.markdown, bencode, …) is **not** part of
the portable surface: those are neither injected nor source-evaluable under
SCI — Java-backed copies fail on classes the native image does not expose —
and Jolt has no copies at all. Use `kmet.libs.json`, `kmet.libs.yaml`,
`kmet.libs.markdown` and `kmet.libs.http` instead.

## Runtime lifecycle

Extensions load when a composition row names them (`:ext/<id>`,
[kmet.app.extensions.rows](kmet/app/extensions/rows.clj)) and `/reload`
reloads the mounted rows with their configs. The core loader can also be
driven directly (from another extension or a REPL):

| Operation | Function | Effect |
|---|---|---|
| Load | `kmet.app.extensions.core/load-extension!` (path) | load one file or manifest dir; returns `{:extension name :error nil :loader-kind :sci\|:jolt :pending bool}`, `{:extension nil :error msg}` on failure, or `{:extension name :skipped true :reason :unsupported-loader …}` when the host offers none of the declared backends |
| Unload | `kmet.app.extensions.core/unload-extension!` (record) | run `shutdown`, dispose the extension's registrations, then — once an asynchronous `shutdown` settles — release its namespaces and jars; answers the release handle when there is one |
| Reload | `kmet.app.extensions.rows/reload-all!` (mounted rows); `kmet.app.extensions.core/reload-extensions!` (dirs) | unload and load again every row-owned extension with its config; the core dirs variant unloads all and reloads from the given container dirs |
| List | `kmet.app.extensions.core/get-loaded-extensions` | `[{:name .. :path .. :loader-kind .. :fiber-state ..}]` |

A composition row is the only thing that loads an extension in a run: the
settings files (`:extensions`, `:bundled-extensions`, packages) are
discovery, not a loader.

A failing plugin body is rolled back: the loader unloads whatever was
registered before the error and reports `{:extension nil :error msg}` — no
partial state lingers. A mount that fails immediately is turned back into a
load error. A mount that activates *later* (its dependency appeared after the
load returned) cannot be a load error any more — the kernel owns it, the fiber
is `:failed` with the throwable, and `get-loaded-extensions` reports that as
`:fiber-state :failed` plus `:error`.

Every extension is a kernel plugin, so its `:inject` (declared in the plugin
map, not the manifest — the app services it waits for,
[extensions.md](../../../../docs/extensions.md)) decides when it activates: with
an unsatisfied dependency the mount stays pending until the ctx provides the
names, and with `{}` it is active as soon as it loads. Unloading disposes the
fiber after `shutdown`, whatever state it was in.

What the plugin's `:apply` registers is **owned**: commands, tools and tool sources,
skills, prompts, hooks, renderers, flags, and the resources a
`:resources-discover` contribution loads land in the app's registries under
`[:app :extensions <name>]`, so unload deregisters them tag by tag — a failing
cleanup is reported and never skips the rest. What lives outside any registry
— event subscriptions, tag aliases, shortcuts, terminal-input listeners, model
providers, the removal of a contributed theme — records its deregister fn on
the extension, and unload runs those in reverse registration order after
`shutdown`.

A registration is addressed by name, and a contested name follows its
registry's rule: commands, prompt templates, skills and flags belong to the
first registration that took it (extensions load before the builtins, so an
extension's name beats a builtin's), while tools and tool sources layer — the
last definition shadows the earlier ones. Unloading a winner restores what a
shadowed registration was hiding.

Unload is registration-complete, not screen-complete. `unload-extension!`
runs `shutdown`, deregisters every registration and drops the extension's
namespaces, but a UI surface the extension commissioned through `ui/call`
(a still-mounted widget or dialog) keeps its component tree — and the
extension state its closures captured — reachable until that surface is
replaced or closed, or until `/reload` disposes it. Release what such a
surface holds in `shutdown` or when it closes; unload alone does not reach
it.

Implementation note: the entry points above live in `kmet.app.extensions.core`,
together with the registries they wire the API into. The isolation machinery
they drive — artifact/jar discovery, class seeding, the shared SCI base,
loader construction, and source evaluation — lives in
`kmet.app.extensions.context`.

## The API surface

`api` carries identity plus the capability maps. The `ext/...` wrappers call
into it; the `(:ui api)`, `(:models api)` and `(:session api)` maps are used via
the wrappers below or directly.

### Identity

```clojure
(:extension-name api)   ; "hello_ext.clj" or the manifest :name
(:extension-path api)   ; absolute path to the extension file/dir/jar
(:extension-dir api)    ; the extension's own directory — nil for jars
                        ; (a jar has no directory; use io/resource)
(:config api)           ; the composition row's :config when a profile mounts
                        ; the extension as :ext/<id>; nil otherwise
(:ctx api)              ; the app root ctx the extension's plugin mounts
                        ; under — pass it to kmet.srce.core for app-wide
                        ; `provide!`/`plugin!`; the plugin's own ctx arrives
                        ; as `:apply`'s first argument
(ext/get-agent-dir api) ; the host agent dir (KMET_CODING_AGENT_DIR-aware;
                        ; pi: getAgentDir) — keep extension state
                        ; (configs, caches) under it
```

### Commands and tools

```clojure
(ext/register-command! api
  {:name "cmd" :description "..." :argument-hint "<arg>"
   :get-argument-completions (fn [prefix] [{:value "a" :label "A"}])
   :handler (fn [ctx args] ...)})        ; ctx = extension context, args = trimmed argument string
(ext/unregister-command! api "cmd")
(ext/get-commands api)

(ext/register-tool! api
  {:name "my-tool" :description "..."
   :params {:path {:type :string :description "..."}}   ; or :parameters (raw JSON schema)
   :execute (fn [args] {:content "..." :is-error false})})
(ext/unregister-tool! api "my-tool")
(ext/get-all-tools api)
(ext/get-active-tools api)      ; nil = all; else set of enabled names
(ext/set-active-tools api ["read" "write"])
```

### Sandbox tool sources (`register-tool-source!`)

A tool source contributes a whole catalog of tools **only to the `run_code`
tool's sandbox surface**: the model never sees them in its tool schema and
`set-active-tools!` does not filter them. Use it for tools that are meant
to be looped over inside scripts but would bloat the model's context if
advertised (the mcp-adapter contributes its cached MCP catalog this way —
see `docs/architecture.md` § Tools and the script sandbox):

```clojure
(ext/register-tool-source! api :my-catalog
  (fn [] {"cat_tool_a" {:name "cat_tool_a"
                        :description "..."
                        :parameters {:type "object" :properties {...}}
                        :streams? true
                        :execute (fn [args & [on-update]] {:content "..."})}
           "cat_tool_b" {...}}))
(ext/unregister-tool-source! api :my-catalog)
```

- The 0-arg fn is called whenever the sandbox surface is built, so it must
  return the *current* catalog (read live state, not a snapshot).
- Re-registering under the same id replaces the source and bumps the
  registry generation, which invalidates cached script contexts — call it
  whenever the catalog changes.
- Contributed tools go through the same execution path as registry tools
  (arg normalization, `:prepare-arguments`, `:contextual?`/`:streams?`
  dispatch, error mapping). The registry shadows a colliding name, and the
  sandbox exclusion list still applies — a source can never contribute
  `run_code` itself.
- The registration is removed automatically when the extension unloads.

### Tool execute contract

By default `:execute` receives `(fn [args])`. A tool that declares
`:contextual? true` receives pi's full contract:

```clojure
(ext/register-tool! api
  {:name "my-tool" :description "..."
   :contextual? true
   :execute (fn [args on-update signal ctx]
              ;; args        — the validated tool args
              ;; on-update   — streaming updates (fn [partial-content])
              ;; signal      — the run's abort atom (true = user cancelled;
              ;;               poll it to abort long work, e.g. kill a child
              ;;               process — pi: AbortSignal)
              ;; ctx         — the extension context (pi: ToolExecuteContext),
              ;;               plus `:srce-ctx`: the ctx the run executes in
              ;;               (the agent's own, or the script's sandbox realm
              ;;               — see § Sandbox realms below)
              {:content "..."})})
```

`on-update`/`signal`/`ctx` are always passed to contextual tools (pi passes
signal+ctx unconditionally). Extension tools may also declare
`:render-call` / `:render-result` fns (pi: `renderCall`/`renderResult`) —
they replace the builtin transcript rendering for that tool's calls/results
and receive the same `ToolRenderContext` map the builtin renderers get
(args, tool-call-id, invalidate, state/set-state!, cwd, is-partial,
expanded, display-mode (the resolved mode: :collapsed | :expanded | :quiet),
is-error, show-images — whether images render: the
`:terminal :show-images` setting AND terminal image support). A tool may also declare
`:title (fn [args])` — the call's one-line plain-text name (nil-safe over
partial streaming args; nil/blank falls back to the tool name): the quiet
transcript line and the permission question's "allow this?" dialog both read
it, so it stays pure and cheap — no IO, no styling.
`:render-shell :self` lets the renderer own its outer
box, padding, and framing. The default shell renders a constant box body
and the component appends the tail state line (`state-result-nodes`) when
the tool opts in with `:status true` — the one line that carries
pending/success/error and the elapsed time. That line changes only its
foreground text (running is muted, success keeps the default color, error
uses the error color with a `(!)` marker) — never a background, so a settle
only rewrites the one line at the document end. `:status` defaults to nil:
no tail line (and no 1s elapsed ticker) in any phase — the built-in
content tools (`read`, `write`, `edit`) and the clojure extension's
edit-family tools (`clojure_edit`, `clojure_edit_replace_sexp`,
`clojure_paren_repair`) keep their outcome in the body alone. One fallback:
a tool whose call and result renderers both produce nothing renders the
tail line regardless of `:status` — it is the block's only presence, so a
running/errored block cannot vanish. The runner
tools opt in: `bash`, `run_code`, and the clojure extension's
`clojure_eval`, so they show Elapsed/Took and the error marker.
Whichever shell a renderer uses, its output must be stable above that
tail: once a line can scroll above the window it must never change again
(the TUI cannot repaint scrollback; a change there forces a clearing
rebuild). Do not encode run state in a body background either — it repaints
every line when the result settles. A renderer's returned component is
disposed when a later pass replaces it — to keep an instance across passes,
return the same one back (read it from `:last-component` and return it
unchanged); a renderer that returns a fresh component each pass gets the
previous one cleaned up automatically. The supported reusable built-in
renderer vars are in `kmet.ui.tui.tool-renderers`, including
`render-edit-call`, `render-edit-result`, `state-result-nodes` (the tail
state line), `render-bash-result` (a
plain-text output body: styled lines, a collapsed line window with an expand
hint and truncation warnings — the opt-in grep/find/ls and powershell tools
use it), `shell-call-renderer` (the shell call line for a supplied prompt —
bash is `(shell-call-renderer "$")`; the powershell tool uses `"ps $"`)
and `render-code-call` (the `run_code`/`clojure_eval` call line: the code
argument rendered verbatim in the call line's bold tool-title text, no syntax
highlighting, whatever the display mode; a leading blank line in the argument
is dropped so the prompt is never alone above an empty line);
the namespace is
explicitly shared with extensions. Path display helpers are public too:
`render-tool-path` (shortened, accent, hyperlinked path) and `link-path`
(wrap any styled text in a terminal hyperlink), for tools that render
locations (the lsp tool renderer uses both). `:streams? true` keeps the 2-arg `(fn [args
on-update])` contract (no signal/ctx).

Other pi tool fields:

```clojure
(ext/register-tool! api
  {:name "my-tool" :description "..."
   :prepare-arguments (fn [args] (assoc args "normalized" true))
   ;; pi prepareArguments — rewrite the raw tool args before schema
   ;; validation/execution; a throwing shim surfaces as a tool error.
   :execution-mode :parallel
   ;; pi executionMode — :sequential (default) or :parallel; the agent
   ;; loop runs parallel-capable tools concurrently with a sequential
   ;; fallback when a tool doesn't declare it.
   :status true
   ;; opt into the transcript's tail state line (pending/success/error +
   ;; Elapsed/Took) and its 1s ticker; default nil = no status line.
   :constrained-sampling {:type :json-schema :strict :prefer}
   ;; pi constrainedSampling — :json-schema strict ({:strict :prefer}
   ;; degrades silently when the provider/model can't do strict;
   ;; {:strict :require} throws :constrained-sampling-required instead)
   ;; or :grammar (helpers implemented, never activated — pi parity:
   ;; no model sets supportsOpenAIGrammarTools).
   :execute (fn [args] {:content "..."})})
```

#### Sandbox realms (the `:srce-ctx` a tool context carries)

Every tool call the loop runs hands its tool the run's own context under
`:srce-ctx` — the agent's ctx, or, inside a `run_code` script, that run's
**sandbox realm**: a child ctx where the agent's `:sandbox` value (`{:isolate
#{…}}`, `kmet.app.sandbox`) says which service names do not resolve. A script
therefore cannot read the composition's `:tools` table, `:shell` or
`:workspace` through a tool it calls, while the run's own values — its tool
surface, shell, cwd and skills — still reach the call through the tool
invocation bindings. A tool that needs a service reads it from `:srce-ctx`; the
key is absent when a caller runs a tool outside a run (an extension calling
`ext/execute-tool` directly), and absent means no ctx, not a process default.

#### Custom bash tools (`create-bash-tool`)

`ext/create-bash-tool` builds a bash tool with the spawn options pi exposes
through `createBashTool` — the built-in bash tool is the same constructor with
default options, so a tool registered under the name `bash` **replaces** it
and keeps the built-in rendering:

```clojure
;; pi: examples/extensions/bash-spawn-hook.ts — adjust command/cwd/env before
;; every spawn. The KMET_* session env is injected first, so the hook receives
;; it in :env (spread the map to preserve it).
(ext/register-tool! api
  (ext/create-bash-tool api
    {:spawn-hook (fn [{:keys [command cwd env]}]
                   {:command (str "source ~/.profile\n" command)
                    :cwd cwd
                    :env (assoc env "MY_HOOK" "1")})}))

;; A dedicated name + description gets default rendering (attach
;; kmet.ui.tui.tool-renderers' render-bash-call/render-bash-result for the
;; built-in look — that namespace is shared with extensions).
(ext/register-tool! api
  (ext/create-bash-tool api
    {:name "sandbox-bash" :description "Run a command inside the sandbox"
     :operations my-remote-ops              ; pi: BashOperations
     :shell-path "/bin/ash"                ; pi: shellPath
     :shell-args ["-c"]                     ; pi: ShellConfig args
     :command-prefix "export SANDBOX=1"     ; pi: commandPrefix
     :expose-session-env? false}))           ; pi: exposeSessionEnvironment
```

Options (all optional): `:spawn-hook` `(fn [{:keys [command cwd env]}] → same
map)` run before spawn, after the `KMET_*` session env is injected (pi:
`BashSpawnHook`); `:expose-session-env?` (default true) injects
`KMET_SESSION_ID`/`KMET_SESSION_FILE`/`KMET_PROVIDER`/`KMET_MODEL`/
`KMET_REASONING_LEVEL` into every command — and adds the matching prompt
guideline; `:command-prefix`/`:shell-path` default to the
`:shell-command-prefix`/`:shell-path` settings (a tool that passes its own,
e.g. the powershell extension, is unaffected by them); `:shell-args` is an
explicit argv template for the shell binary (pi: `ShellConfig` args) — the
command is appended as the final argv element, replacing the platform
heuristics (cmd `/c`, WSL `-s`, sh `-c`), so a PowerShell tool passes
`["-NoProfile" "-NonInteractive" "-ExecutionPolicy" "Bypass" "-Command"]`;
`:temp-file-prefix` names the spill file a truncated output is saved to
(pi: `tempFilePrefix`; default `"kmet-bash-"` — the powershell extension uses
`"kmet-powershell-"`); `:operations` replaces the
local spawn (a remote/SSH executor receiving
`{:command :cwd :on-data :signal :timeout :env}` and returning
`{:exit-code :cleanup}`); `:name` `:label` `:description` override the
tool-facing fields. The `:shell-command-prefix`/`:shell-path` settings are
applied to every bash execution — the built-in tool, `!` commands, and
factory-built tools alike — so `:command-prefix`/`:shell-path` only need to
be passed for a per-tool override.

### Events

```clojure
;; returns a deregister fn — call it to stop listening
(ext/on-event api :session-start (fn [ev ctx] ...))
;; handlers always receive (event ctx) — the extension context, built fresh
;; per event (pi: handler(event, ctx))
;; a custom type is allowed — the vocabulary is documented, not a gate; an
;; undeclared name is reported as a probable typo
(ext/emit-event! api {:type :my-event :data 1})
```

The listener table is the ctx tree's: a registration lands on the host's ctx
(the app root), so a handler hears every event an agent under it emits and
nothing outside the app. `emit-event!` emits at the same ctx, and its return
follows the event's declared mode — nil for a notification, the last non-nil
answer for a `:collect-last` event, the accumulated decision map for a
boundary.

Ten events are **extension-only**: the six provider/resource bridges
(`:context` `:context-with-system` `:before-provider-request`
`:before-provider-headers` `:after-provider-response` `:resources-discover`)
and the four `:session-before-*` hooks.

An extension's own plugin map may declare events too — `:events {:my/event
{:mode :emit :domain :app :doc "…"}}` on the `defplugin` map. The host
registers them at load, so `emit-event!` on the name dispatches by that
declaration, and unload sweeps them with the extension. A declaration is
public unless it says `:public false`, and a name may be re-declared only
identically — a plugin cannot change how a core event dispatches. They exist to let an extension shape a request or
veto a session move, carry request machinery (a payload, a header map) or a
control object, and are therefore not on the public stream a front end or the
wire takes (`kmet.app.events/on-every` skips them) — an extension subscribes
them by name, as above.

Event types: `:agent-start` `:agent-end` `:agent-settled` `:turn-start`
`:turn-end` `:agent-before-settle` `:message-start` `:message-update`
`:message-end`
`:tool-execution-start` `:tool-execution-prepared` `:tool-execution-update`
`:tool-execution-end`
`:status` `:error` `:session-start` `:session-shutdown`
`:session-info-changed` `:user-bash`
`:session-before-tree` `:session-before-switch` `:session-before-fork`
`:session-before-compact` `:session-tree` `:queue-update` `:model-select`
`:thinking-level-select` `:context-replaced` `:auto-retry-start`
`:auto-retry-end` `:summarization-retry-scheduled`
`:summarization-retry-attempt-start` `:summarization-retry-finished`
`:compaction-start` `:compaction-end`
`:session-compact` `:session-compact-failed` `:context` `:context-with-system`
`:before-provider-request` `:before-provider-headers`
`:after-provider-response` `:resources-discover` `:loop-guard`.

Most events are notification-only. Two things extend a run:

A message sent from an `:agent-end` handler — `send-user-message`, or
`send-message!` with `:trigger-turn` — queues (`:steer` / `:follow-up`) and
then starts a fresh attempt in the same prompt, with the queued messages as
that attempt's prompt (pi: `hasQueuedMessages` → `agent.continue`). The same
send from an `:agent-settled` handler stays queued for the next submission:
settle is the prompt's last event. Queueing on *every* `:agent-end`
therefore keeps extending the prompt — cancel (Escape) ends it, since
cancelling clears the queues and the continuation is abort-guarded.

The two actionable boundaries — `:turn-end` after every turn (including an
errored or aborted response, where it precedes `:agent-end`) and
`:agent-before-settle` once before the prompt settles — carry the boundary
state alongside `:entries []` / `:continue false`: the projected context
(`:context-entries` / `:context-messages`, plus `:llm-messages`, the
projection through `kmet.app.loop.message/to-llm-messages` — pi's
`convertToLlm`, the same function the request applies: bash executions,
custom messages and the summary roles flattened to user messages — pi:
`BoundaryContextPreview`), `:pending-messages` and `:can-continue`.
`:turn-end` additionally carries `:message-entry-id` (the persisted entry the
turn's assistant message became) and `:tool-result-entry-ids` (the turn's
tool results' entry ids, in call order) — the ids an edit draft can target
without reading the session. They honor what a handler returns:
`:entries` are session entry maps appended in order (`{:role :custom ...}`
extension state; `{:role :custom-message ...}` also enters the context, so
the next request sees it), and `:continue true` runs one more turn — honored
once the entries are committed, when the context can back it (`:can-continue`
is that check as the boundary stood) and dropped with a warning otherwise.
Handlers of the same boundary see each other's `:entries`/`:continue` merged
in (pi: `emitBoundary`).

A `{:role :context-edit :target-id <entry-id> :replacement ...}` entry is the
append-only per-message context edit (pi: `appendContextEdit`): `:replacement
nil` omits that earlier message from every future provider context, and
`{:content "text"}` / `{:content [{:type :text :text ...} ...]}` rewrites it
(for a tool result the result text is rewritten and the tool-call id is kept,
so the call/result pairing survives).
Only message entries that contribute editable model content can be targeted
(`:user`, `:assistant`, `:tool`, `:bash`, `:custom-message`) and only on the
active branch; anything else is reported and skipped. The raw session keeps
the target untouched — usage totals, replay and the TUI show the original —
only the projection changes, and the live context is rebuilt so the next
request already sees it. Entry ids come from the boundary payload
(`:message-entry-id` / `:tool-result-entry-ids`) or from `(:session ctx)`
(`get-branch`/`get-entry`). `kmet.app.loop.state/append-context-edit!` is the same
operation outside a boundary handler.

### Bundled resources (`io/resource` + self-registration)

An extension reads its own bundled files through `clojure.java.io/resource`
(which the host shadows per extension artifact — own artifact first, then
the `deps.edn` closure jars, then the host classpath), so the same code
works for dir installs, symlinked checkouts and unexpanded jars:

```clojure
(ns my-ext.main
  (:require [clojure.java.io :as io]
            [kmet.extension :as ext]
            [kmet.srce.core :as srce]))

(srce/defplugin plugin
  {:name  :ext/my-ext
   :apply (fn [_ctx api]
            (ext/register-skill!
             api (slurp (io/resource "skills/my-skill/SKILL.md"))
             {:location "my-ext:skills/my-skill/SKILL.md"})
            (ext/register-prompt!
             api {:name "my-prompt"
                  :content (slurp (io/resource "prompts/my-prompt.md"))
                  :location "my-ext:prompts/my-prompt.md"})
            nil)})
```

Rules: resources are referenced by exact shipped names — directory listing
inside jars is unsupported. Jar installs are read in place, no extraction:
sources are opened per call, resources through `jar:` URLs, and kmet
releases the extension's own archive handle at unload — a jar can be
replaced or removed once the extension is unloaded. Skills/prompts registered this way are
self-contained single files (no relative refs); the host stores the body in
memory and serves extension skills through the `read` tool under their
`name:path` location (pass the `<location>` verbatim) as well as via
`/skill:name` expansion. Themes need no new api:
`kmet.libs.theme` is shared by reference — call `make-theme` +
`register-theme!` in the plugin body and `unregister-theme!` on unload. Every
registration goes under the extension's owner, so unload disposes it; the
returned deregister fn drops it earlier.

### Contributing resource paths (`resources_discover`)

After `:session-start` (startup, `/new`, `/reload`), kmet fires
`:resources-discover` (pi: resources_discover — fired after session_start).
The one-shot `--print`/json runs create a runtime without emitting
`:session-start`, so neither these handlers nor their paths reach them — an
extension that must add a skill there should register it in the plugin's `:apply`
(`register-skill!`) instead.
so extensions (and user config) can contribute skill, prompt-template and
theme paths (directories). Session *switches* (`/resume`, `/import`,
`/fork`, `/clone`) fire neither event by design: they import the session's
transcript and working directory, never the other project's configuration or
resources — the payload `:cwd` and the contributed paths stay those of the
directory kmet was launched in.

```clojure
(ext/on-event api :resources-discover
  (fn [ev ctx]  ; {:cwd .. :reason :startup | :reload}
    {:skill-paths  ["/abs/path/to/skills"]
     :prompt-paths ["/abs/path/to/prompts"]
     :theme-paths  ["/abs/path/to/themes"]}))
```

Unlike the provider events, **every** handler's result is collected (pi
collects each contribution). The paths load under the owner that contributed
them — the extension's `[:app :extensions <name>]` — so unloading the
extension takes its skills, prompts and themes with it, and discovery firing
again (it follows every session start) re-registers the same names in place
instead of duplicating them: the registry's one-value-per-owner-and-name rule
is the dedup, so there is no applied-path set to reset (pi: mergePaths dedup).

### Cancellable session before-events

Three events fire **before** session mutations; handlers may return
`{:cancel true}` to abort the operation (the session stays untouched):

```clojure
;; before /new and /resume switch the session
(ext/on-event api :session-before-switch
  (fn [ev ctx] (when (and (= :resume (:reason ev)) (not (trusted?))) {:cancel true})))

;; before forking (or cloning) at a message
(ext/on-event api :session-before-fork
  (fn [ev ctx] ...))   ; {:entry-id .. :position :at}

;; before context compaction (manual, threshold, or overflow) — the run's
;; abort signal rides in the event; return {:cancel true} to skip it, or
;; {:compaction {:summary .. :first-kept-id .. :tokens-before .. :usage ..
;;               :details ..}} to supply the content instead of the
;; summarizer (pi: SessionBeforeCompactResult — the entry then records
;; :from-hook and the events carry :from-extension)
(ext/on-event api :session-before-compact
  (fn [ev ctx] ...))   ; {:preparation .. :branch-entries .. :custom-instructions
                       ;  :reason .. :will-retry .. :signal ..}
                       ; the preparation is the projection-aware pi
                       ; CompactionPreparation: :messages are the projected
                       ; messages to summarize (an omitted message is absent,
                       ; a rewritten one carries its replacement),
                       ; :tokens-before counts the projection, and :settings
                       ; is the resolved CompactionSettings
                       ; ({:enabled :reserve-tokens :keep-recent-tokens})

;; after a successful compaction — the appended rollup entry
(ext/on-event api :session-compact
  (fn [ev ctx] ...))   ; {:compaction-entry .. :from-extension .. :reason
                       ;  :will-retry ..}
```

Provider events fire around each LLM call (pi: context / context_with_system /
before_provider_request / before_provider_headers / after_provider_response);
for each, the **last non-nil handler result wins**:

```clojure
;; replace the conversation — :context sees it without the system prompt,
;; which is re-attached to a replacement (rewrite the prompt itself in
;; :context-with-system); :context-with-system then sees the full transcript
;; and its result is sent verbatim
(ext/on-event api :context (fn [ev ctx] {:messages [...]}))
(ext/on-event api :context-with-system (fn [ev ctx] {:messages [...]}))
;; replace the assembled request payload
(ext/on-event api :before-provider-request (fn [ev ctx] new-payload))
;; replace the request headers (return the map; a nil header value deletes
;; that header — pi mutates in place, Clojure maps are immutable)
(ext/on-event api :before-provider-headers (fn [ev ctx] new-headers-map))
;; observe the response (no result used)
(ext/on-event api :after-provider-response (fn [ev ctx] ...))
```

### Hooks

```clojure
;; intercept/rewrite user input before the agent runs
(ext/on-input api (fn [{:keys [text source streaming-behavior images]}]
                    ;; return nil (pass), {:action :handled}, or
                    ;; {:action :transform :text new-text :images new-images}
                    nil))

;; override the system prompt / inject context per run
(ext/on-before-agent-start api
  (fn [{:keys [prompt system-prompt]}]
    ;; return nil, or {:system-prompt ...} / {:message msg-map}
    nil))

;; transform tool calls/results (pi: tool_call / tool_result)
(ext/on-tool-call api
  (fn [{:keys [tool-name args]}]
    ;; nil (pass), {:block true :reason "..."}, or {:args transformed}
    ;; a blocked call may add :terminate true — when EVERY call in the
    ;; batch is blocked with :terminate the run stops after the batch
    ;; (no follow-up LLM call; the follow-up queue still drains)
    nil))
(ext/on-tool-result api
  (fn [{:keys [tool-name result is-error]}]
    ;; nil, or {:content ...} / {:is-error ...} overrides
    nil))
```

Parallel batches run tool bodies concurrently: `on-tool-call` hooks fire
sequentially during preparation, while `on-tool-result` hooks run when
their tool finishes, on that tool's worker thread — they may overlap with
each other, and so may `:tool-execution-end` listeners. Keep shared
extension state in atoms.

Scripted tool calls run through the same hooks: a `run_code` call's inner
`tools/call` fires the before hook (block with `{:block true :reason ...}`,
or rewrite with `{:args ...}`) and the after hook (override `:content` /
`:is-error`). Two caveats: an inner call carries a synthetic `:tool-call-id`
(but the batch's assistant message), and a blocked call's `:terminate` hint
rides through to the script's result (a script has no batch to stop). Inner
calls still produce no tool-execution events and no transcript/session
entries.

Hooks run **before** the app's permission policy (`:permissions`, an
app-level service no extension can mount), so a hook can rewrite a call but
not allow one the policy denies — and a hook is an interception, not a
sandbox: the run_code sandbox can also shell out through `babashka.process`
(`p/sh`/`p/shell`/`p/process`), which no per-tool hook covers, so blocking
the `run_code` tool itself is what a hook can do.

### Shortcuts and markdown transformers

```clojure
;; register a keyboard shortcut (pi: registerShortcut). KEY is a raw key
;; string ("ctrl+alt+x", "f5", ...). The handler receives the extension
;; context. Extension shortcuts are checked BEFORE every builtin app
;; binding (escape/app.interrupt included — pi: onExtensionShortcut runs
;; first); the last registration of the same key wins. One exception:
;; app.quit (ctrl+q by default) is handled in the TUI input-listener chain
;; before focus dispatch, so a shortcut on its chord never runs — rebind
;; app.quit if the chord is needed.
(ext/register-shortcut! api "ctrl+alt+x"
  {:description "Do the thing"
   :handler (fn [ctx] ...)})

;; transform user/assistant message markdown before rendering (pi:
;; registerMarkdownTransformer). The transformer receives the markdown and
;; {:message-type :user|:assistant :is-streaming bool :available-width int}
;; and returns the transformed string. Transformers run in registration
;; order and MUST be idempotent — they re-run per render, so streaming
;; chunks re-transform the accumulated text; a throwing transformer is
;; skipped.
(ext/register-markdown-transformer! api
  (fn [md ctx] (str/replace md "TODO" "**TODO**")))
```

### Custom messages and agent control

```clojure
;; send a custom message (pi: sendCustomMessage): persisted as a custom_message
;; session entry, injected into the LLM context (sent as a user message),
;; rendered in the chat when :display. :next-turn queues it for the next
;; prompt. While streaming, :trigger-turn (default true) queues it into the
;; run (:steer into the current turn, :follow-up after it) and :trigger-turn
;; false defers it to the end of the turn, so it cannot land between a tool
;; call and its result. While idle, :trigger-turn starts a run with the
;; message as its prompt; without it the message is appended without running.
(ext/send-message! api
  {:custom-type :note :content "remember this" :display true :details {:x 1}}
  {:trigger-turn true :deliver-as :next-turn})
```

### CLI flags

```clojure
(ext/register-flag! api "my-flag" {:type :boolean :default false})
;; or {:type :string :default "..."}
(ext/get-flag api "my-flag")
```

Values come from `--my-flag`, `--my-flag value` or `--my-flag=value` on the
command line (string flags consume the following non-dash arg; bare flags are
boolean true).

### Custom renderers

```clojure
;; custom ENTRY (extension state, never in LLM context) — hidden unless a
;; renderer is registered; renderer returns a chat message map (or bare
;; component). A renderer that throws renders
;; `[my-state] renderer failed: message` (pi: CustomEntryComponent)
(ext/register-entry-renderer! api "my-state"
  (fn [entry] {:role :info :content (pr-str (:data entry)) :label "My state"}))

;; custom MESSAGE (participates in LLM context) — overrides the default
;; labeled info box; a renderer that returns nil/throws keeps the default
;; box (pi: CustomMessageComponent fallback)
(ext/register-message-renderer! api "my-message"
  (fn [msg] {:role :info :content "rendered" :label "My message"}))
```

A renderer that returns a **bare component** owns it: the transcript hands
its shared output-pad atom to the components it builds itself, so a
component you construct keeps the padding you gave it and does not follow
the output-padding setting. Return a message map (as above) when you want
the transcript's current padding.

### Agent control

```clojure
(ext/set-model api model)                 ; a Model record from (:models api)
                                        ; → true on success, false when the
                                        ; model has no configured auth (pi:
                                        ; Promise<boolean>)
(ext/get-thinking-level api)
(ext/set-thinking-level api :high)
(ext/send-user-message api "text" {:deliver-as :steer})  ; :steer | :follow-up
(ext/send-user-message api "/skill:name args" {:expand-prompt-templates? true})
```

`send-user-message` always triggers a turn when the agent is idle; while
streaming, `:deliver-as` controls whether the message is injected mid-run
(`:steer`) or queued (`:follow-up`, the default) — the loop drains the queue
at the next turn boundary, or as a fresh attempt in the same prompt when the
prompt is already settling.
With `:expand-prompt-templates?`, the message runs through the submit
chain first — extension commands execute immediately (consuming the
message), then the host's expansion runs (pi: expandPromptTemplates; kmet1
expands skill commands and prompt templates, and a host that names no chain
expands nothing — the option is the host's policy, not the ABI's).

Agent control is **runtime-bound** (pi: `bindCore` actions): the session
runtime installs it at bootstrap, so it works in every front end —
including headless print/json mode — and not only inside the interactive
TUI. The UI capabilities below are the opposite: the front end owns and
installs them, and they are inert headless.

### UI

The `:ui` capability map dispatches through the **front-end binding**: one
map per process, bound by the runtime for headless runs and replaced by a
front end once its widgets exist (`extensions/install-front-end!`, which also
registers the port under the tree's `[:app :ui]` owner). Branch on
`(:mode ctx)` — `:interactive` (TUI), `:gui`, `:rpc`, `:print`, `:json` —
and on `(:has-ui ctx)`. A headless front end binds a degraded port: widget,
status and dialog calls are inert, getters return neutral values
(`:get-editor-text` → `""`, `:get-tools-expanded` → `false`), `:set-theme`
reports `{:success false :error "UI not available"}`, and `ui-custom`
returns an already-resolved nil promise instead of mounting anything — an
extension UI call cannot hang a headless run. Calls made before any
binding exists (library use outside a runtime) stay inert no-ops.

There are **no host-built dialogs**: dialogs/selectors/editors are components you
compose yourself from the shared `kmet.tui.*` layer and mount with
`ui-custom`; theme objects come from `kmet.libs.theme` directly
(`get-theme`/`get-all-themes`/`get-theme-by-name`/`get-current-theme`), not
from the api.

```clojure
(ext/ui-set-status api "my-ext" "loaded")   ; footer status; nil clears
(ext/ui-notify api "Done" :info)            ; :info | :warning | :error

;; widgets take HICCUP ELEMENT TREES (or a factory fn → component):
(ext/ui-set-widget api "my-widget"
                   [:container {}
                    [:text {:padding-x 1 :padding-y 0} "line 1"]
                    [:text {:padding-x 1 :padding-y 0} "line 2"]]
                   {:placement :above-editor})
(ext/ui-set-widget api "my-widget" nil)          ; removes the widget (disposes it)
;; compiled trees are real components — the host disposes them on replace
;; and removal, so with-let cleanups / reactive subtrees never leak

;; richer/reactive widgets: return a factory whose result is a compiled
;; hiccup tree — the host disposes it automatically on replace and removal
;; (with-let cleanups and reactions unwind via kmet.tui.hikap ownership):
;;
;;   (require '[kmet.tui.hikap :as h])
;;   (ext/ui-set-widget api "clock"
;;     (fn [tui theme]
;;       (h/compile-tree [:container {}
;;                        [:text {:padding-x 1 :padding-y 0} "tick"]])))
;;
;; ui-custom factories may also return element trees directly — they are
;; compiled and wrapped (input goes nowhere; interactive customs should
;; still return components/maps).

;; append an :info message to the chat history (the /session display style):
;; LABEL renders bracketed above CONTENT; part of the live transcript,
;; never sent to the LLM, not persisted across restarts
(ext/ui-chat-info api "Label" "content")

(ext/ui-set-theme api "light")
(ext/ui-set-editor-text api "text")
(ext/ui-get-editor-text api)
(ext/ui-paste-to-editor api "text")
(ext/ui-set-footer api (fn [tui theme footer-data] comp-or-nil))
(ext/ui-set-header api (fn [tui theme] comp-or-nil))
(ext/ui-set-title api "kmet")
(ext/ui-set-working-indicator api {:frames ["⠋" "⠙"] :interval-ms 80})
(ext/ui-set-working-message api "Working...")
(ext/ui-set-working-visible api true)
(ext/ui-set-hidden-thinking-label api "…")
(ext/ui-set-editor-component api (fn [tui theme keybindings] comp))
;; The default editor embeds the session status (working/retry/compaction/
;; branch-summarization spinner) in its own first line — the top border.
;; A custom editor is plain: it has no top-border hook, so the standalone
;; status layer above the dock keeps rendering the status with it.
;; The editor's text moves with the swap, collapsed pastes included: text and
;; paste bindings cross both ways, so a marker still expands to its content in
;; either editor. A component with no paste store of its own receives the
;; expanded text instead — a marker never outlives its content.
(ext/ui-add-autocomplete-provider api (fn [base-provider] wrapped-or-nil))
(ext/ui-on-terminal-input api (fn [data] nil-or-{:consume true :data d}))
(ext/ui-set-tools-expanded api true)   ; boolean shim: expanded? <=> mode :expanded
(ext/ui-get-tools-expanded api)
(ext/ui-set-tool-display-mode api :quiet)  ; :collapsed | :expanded | :quiet (ctrl+o cycles)
(ext/ui-get-tool-display-mode api)

;; mount your own component — THE way to show any dialog/panel. The factory
;; receives (tui theme keybindings close); close delivers its result to the
;; returned promise and dismisses the dialog. Opts: {:overlay bool
;; :overlay-options {...} :on-handle fn}; {:anchor :center :width 82} is a
;; typical overlay-options map. Floating overlays get a full border and a
;; themed background fill by default (kmet, not pi) so they are never
;; transparent over the transcript: :border :none and :background false
;; turn them off, :padding-x/:padding-y inset the content (see
;; kmet.tui.core/tui-show-overlay for the full option set). The factory may
;; return the component OR a promise of one (deref'd with a 5s timeout);
;; when the component carries a :dispose fn, it is called when the dialog
;; closes (pi: dispose?()) — same for widgets, custom footer/header and the
;; :reset path (extension reload). A non-overlay dialog mounts in the
;; editor's slot; the draft it covered is restored when the dialog closes.
;; A submit clears the editor before the command dispatches (pi: the
;; editor clears before onSubmit), so a command-opened dialog saves and
;; restores an empty draft — closing /tools cannot put "/tools" back into
;; the editor.
(ext/ui-custom api (fn [tui theme kb close] (my-selector comp close))
                {:overlay true :overlay-options {:anchor :center :width 82}})
```

Headless/print mode has no layout: check `(:has-ui ctx)` (or `(:mode ctx)`
for a finer split) in command/event handlers and fall back to `ui-notify` or
a plain status line.

#### UI requests (front-end plumbing)

A front end answers host UI requests through the runtime's request/response
pair (extension runtime UI port), not through the event stream: the runtime delivers
`{:type :ui-request :id ... :kind ...}` to the port's `:request` capability,
and the front end either returns the answer in-process (blocking dialogs are
allowed inside extension handlers) or forwards it to its client and answers
later with `kmet.app.session.runtime/respond!`. Kinds without a dedicated
surface auto-answer — `:select` → nil, `:confirm` → false,
`:input`/`:editor`/`:custom` → nil — and `dispose!` resolves any request still
pending with the request's default. No built-in capability routes through
this yet: extensions mount their own components with `ui-custom`, and the
protocol is what a front end uses for host dialogs (a future wire mode
forwards the requests and answers asynchronously; such a port owns its own
timeout policy and answers with the default when its deadline elapses).

##### RPC mode

The RPC front end (`kmet.app.modes.rpc`) binds `{:mode :rpc :has-ui true}` and
bridges the methods the protocol can carry (pi: `rpc-extension-ui.md`):

- **dialogs** — `:select`/`:confirm`/`:input`/`:editor` requests become
  `{:type :ui-request :id ... :method ...}` records; the client answers
  with `{:type :respond :id ... :value/:confirmed/:cancelled ...}`. A valid
  answer is silent (pi writes no `extension_ui_response` record). A request
  carrying `:timeout` auto-resolves with its default when the deadline
  elapses; `dispose!` does the same for anything still pending.
- **output channels** — `ui-notify`, `ui-set-status`, `ui-set-title`,
  `ui-chat-info`, `ui-set-editor-text` and `ui-paste-to-editor` write
  fire-and-forget records. `ui-set-widget` forwards only string lines; a
  hiccup tree cannot cross the wire.
- **TUI-only (inert no-ops)** — `set-working-message`,
  `set-working-visible`, `set-working-indicator`,
  `set-hidden-thinking-label`, `set-footer`, `set-header`,
  `add-autocomplete-provider`, `set-editor-component`,
  `set-tools-expanded`.
- **degraded** — everything else keeps the headless answers: `ui-custom`
  resolves nil, terminal input is a no-op unsubscriber, `get-editor-text`
  returns `""`, theme switching reports no UI, and the tool
  expansion/display getters are neutral. `:custom` is not a dialog kind
  either (pi's limitation).

The engine-portable builtins (`/continue`, `/name`, `/export`, `/compact`)
are dispatchable over `prompt`: a single-line `/<name>` runs through the
command registry with the RPC command-UI value, whose output capabilities
write the records above. The transport serves commands serially, so a
command handler that blocks on a dialog parks the reader until the answer
(or the request's `:timeout`) — answer dialogs asynchronously, or give the
request a timeout.

#### Tag aliases — named tag-level components

`ext/register-alias!` registers a **qualified-keyword tag** for any hiccup
tree the host or another extension parses (`kmet.hikap.alias`, tui.md §2.9).
Use it to contribute UI by name: a tree site embeds
`[:my-ext/chip {:label "ready"}]` without requiring the provider.

```clojure
;; the var trick: the var holds the KEYWORD, so your own tree sites can
;; write [chip …] and go-to-definition still works
(def chip :my-ext/chip)

(defn chip-view
  "The alias fn: (fn [attrs children] tree)."
  [{:keys [label]} _children]
  [:text {:padding-x 0 :padding-y 0} label])

(srce/defplugin plugin
  {:name  :ext/my-ext
   :apply (fn [_ctx api]
            (ext/register-alias! api chip chip-view)
            (ext/ui-set-widget api "chip" [:container {} [chip {:label "ready"}]])
            nil)})
```

- `attrs` is the element's props map without `:key`/`:ref`; `children` is a
  flat vector of the raw child nodes (`[]` when the element has none). An
  alias is an ordinary fn component underneath — reactions, `with-let`,
  refs, keyed reuse and the unchanged-tree skip all apply.
- Register **top-level fns, never closures over extension state**: the
  registration keeps the fn (and what it closes over) reachable until
  unload. Re-registration replaces it (last wins); rendering resolves the
  current registration at call time, so reload never strands the old fn —
  the new fn takes effect on the component's next body run.
- The registration is removed automatically when the extension unloads
  (the alias registry is process-global, so unload runs the deregister fn it
  returned).
  Do **not** use `kmet.hikap.alias/defalias` in an extension — it registers
  directly and would survive the unload.
- Names must be qualified keywords that no rendering backend declares as
  a host tag (unqualified keywords are always host tags), and there is no
  `.class`/`#id` tag-suffix syntax. A body run after the
  registration is gone (unload) throws loudly; a cached body keeps its
  last output until its next run.

### Models

```clojure
(def models (ext/models api))
((:get-all models))                       ; all registered models
((:get-available models))                 ; models with configured auth
((:find models) provider-id model-id)
((:has-configured-auth models) model)
((:get-provider-auth-status models) provider-id)
((:get-api-key-and-headers models) model)
((:get-registered-provider-config models) provider-id)
((:get-registered-provider-ids models))
(ext/models-register-provider! api :my-provider
                               {:base-url "https://..." :api :openai-completions
                                :api-key "sk-..." :models [{:id "my-model"}]})
(ext/models-unregister-provider! api :my-provider)
```

Provider registrations are removed automatically when the extension
unloads (like tools, commands, skills and prompts — every registration
records its deregister fn). Same-id registrations from two extensions
clobber on unload (last-wins), matching commands/tools behavior.

Providers can register an **OAuth login block** instead of (or alongside)
`:api-key` (pi: `registerProvider` oauth block — the `/login` command then
offers the OAuth flow):

```clojure
(models/register-provider! api :my-provider
                           {:base-url "https://..." :api :openai-completions
                            :models [{:id "my-model"}]
                            :oauth {:name "My SSO"
                                    :is-subscription? true
                                    :login (fn [interaction]
                                             ;; interaction: {:signal :prompt
                                             ;;               :abort-prompt!
                                             ;;               :notify}
                                             {:type :oauth :access "..."
                                              :refresh "..." :expires 0})
                                    :refresh-token (fn [credential _signal]
                                                     credential)
                                    :to-auth (fn [credential]
                                               {:api-key (str "Bearer "
                                                              (:access credential))})}})
```

`:login` returns the credential to persist in auth.edn (pi
`OAuthCredentials`), `:refresh-token` refreshes expired credentials
(default: pass through), and `:to-auth` converts a credential into the API
key used for provider requests (pi `getApiKey`). A config missing
`:login`/`:to-auth` throws at register time. On unregister the provider's
oauth block goes away with it.

The extension **context** carries the current selection as `(:model ctx)`:
in interactive and print mode it is the registered Model record — not the
model ID string — so `:provider`, `:base-url` and the model's configured
`:headers` are available, and the value can be passed straight to
`:get-api-key-and-headers`. Read the ID with `(:id (:model ctx))`. Each new
context resolves the current provider/model pair at build time, so it follows
model and session switches; it is nil when the selection is not registered
(and in a bare headless context with no running agent). Because the provider
and model are stored separately, a context built while a switch is being
applied can momentarily resolve the old model id under the new provider
(usually nil).

The `:model-select` event carries the same records for `:model` and
`:previous-model` (`:previous-model` nil when the previous model is not
registered), so event handlers get provider and id without a registry
lookup.

`(:scoped-models ctx)` is the session's scoped entry list resolved to
`[{:model Model} ...]` (pi: `ScopedModel[]`) — empty when no scoping is
configured, deduplicated by model, and entries that no longer resolve drop
out. Resolution uses the registered catalogue, not the auth-filtered set
Ctrl+P cycles over, so a configured model without credentials still appears.
kmet drops scoped thinking levels, so the maps carry no `:thinking-level`
key.

The context carries the read-only registry facade as `(:models ctx)` (pi:
`ctx.modelRegistry`): the same read fns as `(ext/models api)` — `:get-all`,
`:get-available`, `:find`, `:has-configured-auth`, `:get-provider-auth-status`,
`:get-api-key-and-headers`, `:get-registered-provider-config`,
`:get-registered-provider-ids` — in both interactive and headless modes.
The facade deliberately omits pi's `getProvider(s)`, `refresh()` and
`getError()`: provider records carry raw configured keys, a refresh is a full
catalogue reload, and config errors surface at startup — auth lookups go
through `:get-provider-auth-status` / `:get-api-key-and-headers`.

### Session

```clojure
(def sess (ext/session api))
(sess/append-entry! "my-state" {:n 1})    ; durable extension state (not in LLM context)
(sess/append-message! "my-msg" "content" true {:details ...})  ; in LLM context; :display controls rendering
(sess/get-entries "my-state")     ; :custom entries of this type on the active branch (pi: getBranch)
(sess/get-all-entries "my-state") ; same, across the whole session incl. abandoned branches (pi: getEntries)
(sess/set-label! entry-id "bookmark")
(sess/get-label entry-id)
(sess/set-name! "my session")
(sess/get-name)
```

The same facades are available on the extension **context** as `(:session
ctx)` (pi: `ctx.sessionManager`) — command and event handlers that only
receive `ctx` can read session state. The ctx map itself is a fresh merge of
a headless default, the running front end's live `:build-context` capability,
and — inside an agent run — the agent's own keys: the run binds the session,
the runtime capabilities and the context for the prompt's lifetime, so a
handler sees the agent it is running inside (`:model`, `:is-idle`, `:abort`,
`:compact`, the session facades) rather than whichever session the process
last installed. Outside a run (a command handler, an extension's own fiber)
the process binding is still the answer (pi: `createContext()`; the ctx
`:model` contract is under Models). `get-entries` follows the active branch (a
settings entry appended before a tree navigation is not on the new branch);
use `get-all-entries` for extension settings that must outlive branch jumps
(the review extension's custom instructions do).

### Shell

```clojure
(ext/exec api "sh" ["-c" "echo hi"] {:dir "/tmp" :timeout-ms 5000})
;; => {:exit 0 :out "hi\n" :err ""}
```

The child runs in the runtime working directory unless `:dir` overrides it
(pi: `options?.cwd ?? cwd`) — the active session's project after a switch,
so a session imported from elsewhere does not leak the launch directory into
extension shell commands. Headless runs fall back to the process cwd.

## Testing extensions

Extensions are testable in isolation with the **nullable API** — a test fixture
that captures every registration into a state atom, with no kmet runtime
involved. Call the plugin's `:apply` with it and assert what was registered:

```clojure
(ns my.hello-ext-test
  (:require [clojure.test :refer [deftest is]]
            [kmet.extension :as ext]
            [my.hello-ext :as sut]))

(deftest the-plugin-registers-hello-command
  (let [{:keys [api state]} (ext/create-nullable-api)]
    ((:apply sut/plugin) nil api)
    (is (contains? (:commands @state) "hello"))
    ;; deregister fns remove registrations (unload replay)
    (is (= 1 (count (get-in @state [:handlers :session-start]))))))
```

State shape: `:commands` `:tools` `:handlers` `:flags` `:shortcuts`
`:markdown-transformers` `:entry-renderers` `:message-renderers` `:aliases`
`:tool-call-hooks` `:tool-result-hooks` `:input-hooks`
`:before-agent-start-hooks` `:ui-calls` `:emitted` `:model-calls`. Every
registration function returns a deregister fn; the nullable api's deregister
fns remove the corresponding registration.

For runtime integration tests, `load-extension!` + `unload-extension!` against
the real registries (see `modules/app/test/kmet/app/extensions/`).
