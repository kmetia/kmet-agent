# kmet extensions

An extension customises kmet with slash commands, tools, event handlers, hooks,
CLI flags, custom message/entry renderers, UI contributions, and more. Extensions
are **Clojure namespaces** with an explicit contract — they depend on exactly one
namespace, `kmet.extension`, and are loaded, reloaded and unloaded at runtime.

This document is the authoritative guide for writing extensions. Keep it up to
date whenever the described behavior changes.

Related docs: [`README.md`](README.md) (loading rules, layout, building UI) and
the shipped examples in `extensions/` (see the catalog table there).

## The contract

An extension is a Clojure namespace defining an `init` function. The loader
calls `(init api)` with a map of runtime-bound capabilities; register everything
inside `init`. Optionally define `(defn shutdown [api])` for teardown — it runs
on unload/reload, and **everything the extension registered is automatically
unregistered** afterwards.

```clojure
(ns my.hello-ext
  (:require [kmet.extension :as ext]))

(defn init [api]
  (ext/register-command! api
    {:name        "hello"
     :description "Say hello"
     :handler     (fn [ctx args] (str "Hello, " args "!"))}))

(defn shutdown [api]
  ;; optional teardown — deregistration is automatic
  nil)
```

`shutdown` is optional. Every `ext/...` wrapper takes `api` as its first
argument and dispatches to the runtime; extensions never require kmet internals.

## The shared TUI library

Extensions that contribute interactive components (`ui/custom`) build them with
`kmet.tui.*` — the same pi-tui-derived component library the kmet UI itself is
built on. The namespaces are shared **by reference**: extension components are
the same `IComponent` records the host renders, so keys, focus, theming and
caching behave identically, and there is nothing to reimplement.

```clojure
(ns my.dialog
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.text :as text]
            [kmet.tui.theme :as theme]
            [kmet.tui.macros :refer [defcomponent track!]]))

(defcomponent MyDialog [this width]
  (protocols/render [_ w]
    (track! this w)
    (container/->Container
     {:child (text/->Text "Hello from an extension")})))
```

What is available: the core protocol records (`container`, `box`, `text`,
`spacer`, `markdown`, `input`, `editor`, `editing`, `select-list`,
`settings-list`, `stack`, `v-stack`, `h-stack`, `expandable-text`, `image`,
`spinner`, ...), `kmet.tui.theme` (styling — including
`theme/get-settings-list-theme` for the selector look), `kmet.tui.macros`
(`track!`/`track-deps` reactive caching), `kmet.tui.keybindings`, `kmet.tui.keys`,
`kmet.tui.fuzzy` and `kmet.tui.autocomplete`. Extension components must follow
the duck-typed `{:render :handle-input :invalidate}` contract accepted by
`ui/custom` (returning a real record is also fine). A `kmet.tui.*` require that
is not part of the shared set (or a `kmet.app.*`/`kmet.modes.*`/`kmet.libs.*`
require) fails the load with an explicit error. The loader
(`kmet.loader*`) is host machinery — never part of the shared set, so
requiring it fails like any other internal.

## Where extensions live

Extensions load from the fixed auto roots — `~/.kmet/agent/extensions`
plus `.kmet/extensions` project-local (pi: `join(agentDir, type)` +
`join(cwd, CONFIG_DIR_NAME, type)`) — at startup and on `/reload`. The
agent dir honors the `KMET_CODING_AGENT_DIR` env override (pi:
`ENV_AGENT_DIR`). Extra paths load via top-level `:extensions` entries in
`settings.edn`: plain entries are files or directories resolved against
the settings scope root (the agent dir for global settings, `.kmet` for
project settings); `!glob` excludes, `+path`/`-path` force include/exclude
exact paths. Plain entries may also carry the same auto-root paths to
select which discovered items stay enabled (e.g.
`{:extensions ["extensions" "!extensions/noisy.clj"]}`). The
`kmet config` screen lists and toggles every discovered resource
(extensions, skills, prompts, themes) across both scopes.

### Built-in extensions (`extensions/`)

The repo ships a set of opt-in extensions in `extensions/` — nothing there is
loaded by default. Enable one by symlinking (or copying) it into the global
or project extensions dir:

```bash
ln -s "$PWD/extensions/tools.clj" ~/.kmet/agent/extensions/tools.clj
```

Single-file extensions ship without tests (they must stay small and
self-contained); directory-based extensions are separate projects with
their own tests. See [`README.md`](README.md).

### Single-file extensions

A `.clj` file in the extensions directory:

```
~/.kmet/agent/extensions/
└── hello_ext.clj        ; namespace must start with (ns ...) and define init
```

### Directory extensions (multi-file, `extension.edn`)

A directory containing an `extension.edn` manifest supports extensions with
their own source files. The directory root is a classpath root: namespace
`my-ext.main` lives at `my_ext/main.clj` (strict ns-path layout — dashes →
underscores, dots → slashes; `.clj` → `.cljc` → `.bb` fallback):

```
~/.kmet/agent/extensions/
└── my-ext/
    ├── extension.edn
    ├── deps.edn          # optional: external library dependencies
    ├── my_ext/
    │   ├── main.clj
    │   └── helper.clj
    └── skills/           # optional bundled resources (exact-name access)
        └── my-skill/SKILL.md
```

```clojure
;; extension.edn
{:name   "my-ext"
 :entry  my-ext.main
 :loader [:jolt :sci]}
```

```clojure
;; deps.edn (optional)
{:deps {cheshire/cheshire {:mvn/version "11.5.3"}}} ;; or just use kmet.libs.json
```

The manifest lists the initial namespace — `:entry`, a namespace symbol
whose namespace must define `init` — and the loader backends the extension
supports — `:loader` (see
[Loader compatibility](#loader-compatibility-loader)). Everything else is
required from there:
internal namespaces resolve by strict ns-path lookup under the extension
root, and declared library dependencies resolve to the jars in `deps.edn`
(see [External library dependencies](#external-library-dependencies-depsedn)).
Internal dependencies are declared in each file's `ns` form:

```clojure
;; my_ext/helper.clj
(ns my-ext.helper)

(defn tool-name [] "my-ext-tool")
```

```clojure
;; my_ext/main.clj
(ns my-ext.main
  (:require [my-ext.helper :as helper]   ; internal dependency
            [kmet.extension :as ext]))   ; kmet core, resolved normally

(defn init [api]
  (ext/register-tool! api {:name (helper/tool-name)
                           :description "..."}))
```

Requires that don't resolve to a file under the extension root (kmet core
namespaces, built-ins, declared libraries) are left to the normal classpath.

An extension's **own** namespaces always resolve internally — regardless of
their prefix. A manifest-dir extension named `tree-sitter` may therefore
have its entry require `kmet.extensions.tree-sitter.tools`: the loader
probes the ns path under the extension root and resolves those requires
before the shared-namespace allowlist is consulted.
The extension name defaults to the directory name (`:name` in the manifest
overrides it).

### Loader compatibility (`:loader`)

The manifest declares which loader backends the extension supports:

```clojure
{:name   "my-ext"
 :entry  my-ext.main
 :loader [:jolt :sci]}      ; both backends — see the table below
```

- `:jolt` — Jolt's native loader (real Jolt namespaces; Jolt's default).
- `:sci` — the SCI backend (babashka's and the JVM's only backend; Jolt can
  run it as a fallback).

The host picks from the declared set by its own preference — **the order in
the manifest is not a ranking**:

| host | prefers | selected |
|---|---|---|
| babashka / JVM | `:sci` | `:sci`, when declared |
| Jolt | `:jolt`, then `:sci` | `:jolt` when declared, else `:sci` |

An extension declaring none of the host's backends is **skipped**, not
failed: it is discovered but not loaded, with a note (and a line in the
`/reload` summary). One artifact directory therefore stays portable across
hosts — declare what you support and each host uses its best backend.

A manifest without `:loader` (a legacy extension) is treated as
`[:sci :jolt]` with a warning — exactly the backend it used before the key
existed. A single-file `.clj` extension has no manifest and implicitly
supports both. `bb pack-extension` requires `:loader` for new artifacts.

What the two backends mean in practice is in
[Host support (Jolt)](#host-support-jolt) below.

### Jar/zip extensions (no expansion)

A `.jar` (or `.zip` — same bytes, either suffix) with the directory layout
above at its root loads exactly like the directory: `extension.edn` (+
optional `deps.edn`) on top, code at ns paths, resources by exact name.
The archive is **never expanded on either host** — code and manifests are
served from the zip (babashka probes entries per call through `ZipFile`; on
Jolt the archive is the loader's source root, read through its central
directory), and resources resolve through the archive (babashka:
an artifact-scoped `io/resource`; Jolt: the loader's own resource
resolution, a `jar:file:…!/entry` URL). Nothing is written outside
`~/.m2`/`~/.gitlibs`.
Discovery picks up top-level `*.jar`/`*.zip` files
alongside `*.clj` files and manifest dirs. Pack one with
`bb pack-extension <src-dir> [out.jar]` (verify-then-zip: `:entry`
resolves, every `.clj` ns matches its path, `deps.edn` carries only
`:deps`).

### External library dependencies (`deps.edn`)

An extension directory may declare its own library dependencies in a
`deps.edn`; the extension's `ns` form can then require them normally
(`[clojure.data.json :as json]` or simply `[kmet.libs.json :as json]`). kmet resolves the declared dependencies — the
complete transitive closure — and serves them **only to that extension's
evaluation context**. Every extension runs in its own isolated context:

- **Different extensions can use different versions of the same library** —
  jars are served per extension, so there is no first-wins shadowing.
- **Unloading an extension releases everything it pulled in** — its
  namespaces, closures and jars become unreachable; nothing global is
  touched.
- **Version changes take effect on `/reload`** — a fresh context is built
  and the deps re-resolve.
- kmet core and `clojure.*` / `babashka.*` namespaces are shared references
  (never re-evaluated), so extension registrations always reach the real
  kmet registries. Extensions may depend only on `kmet.extension` plus the
  shared `kmet.tui.*` / `kmet.libs.*` libraries; other kmet internals are
  not resolvable from an extension context (the load fails with an explicit
  error) — the loader, `kmet.loader*`, is one of those internals.
- **Contexts are forks of one shared base** — the shared layers and the
  seeded classes are built once and forked per extension, so your
  definitions live only in your context (two extensions may even declare
  the same internal namespace name). Rebinding the *root* of a shared var
  (`alter-var-root`, `alter-meta!`) is not isolated across extensions —
  don't monkey-patch the shared layers.

### Outbound HTTP (`kmet.libs.http`)

All outbound HTTP from an extension goes through `kmet.libs.http` — the
single proxy-aware boundary shared by reference with the host. Requiring
`babashka.http-client` directly fails the load with an actionable error.

```clojure
(ns my-ext.main
  (:require [kmet.libs.http :as http]))

(http/get "https://api.example.com/status" {:timeout-ms 5000})
;; => {:status 200 :headers {...} :body "..."}
```

- `request` / `get` / `post` / `request-json` with `:as` (:string/:bytes/
  :stream), `:timeout-ms`, `:throw?` (default true — HTTP >= 400 throws
  `{:type :http-error :status :headers :body}`), `:follow-redirects`,
  `:signal` (cancel atom, curl transport) and `:proxy` (:env by default —
  HTTPS_PROXY/HTTP_PROXY/SOCKS_PROXY/ALL_PROXY/NO_PROXY are honored
  automatically, curl semantics; `:proxy :none` forces a direct
  connection). Stream responses (`:as :stream`) must be finished with
  `http/close!`.
- Transport failures throw `{:type :transport-error}`; errors carry stable
  retryable message tokens ("network error" for connect/DNS/timeout/RST).

Dep resolution happens **in-process** (via `clojure.tools.deps`, bundled
with babashka — no extra dependency, no JVM) — no subprocess, and nothing
is written outside the normal Maven/Git caches (`~/.m2`, `~/.gitlibs`). A
library an extension requires without declaring it in `deps.edn` fails with
a clear error unless it is part of the fixed bundled set below.

### Bundled extension libraries (fixed set)

kmet ships a fixed set of libraries in **both** artifacts and shares them by
reference with every extension context: bb's bundled ports serve the names on
babashka, and `jolt dist` AOT-compiles kmet's pinned Maven deps into the Jolt
binary — on both hosts the library is already loaded, so it costs nothing per
extension and keeps `identical?`/protocol identity. Requiring a set member
needs **no `deps.edn`** and no host-conditional code. The set is enumerated in
`kmet.app.extension-libs`:

| Library | Namespaces | Notes |
|---|---|---|
| rewrite-clj | `rewrite-clj.*` | bb's bundled port; the pinned Maven jar on Jolt |
| edamame | `edamame.core` | reader-error/delimiter detection |
| clojure.tools.reader | `clojure.tools.reader*` | bb's reduced port; the Maven lib on Jolt |
| clojure.spec | `clojure.spec.alpha` | bb's port; the Maven lib on Jolt |
| cljfmt | `cljfmt.core`, `cljfmt.config` | 0.16.5 on both |
| parinferish | `parinferish.core` | pure Clojure, 0.8.0 on both |

Plus the libraries shared from the host itself: `clojure.*` (`core.async`
and `rrb-vector` included), `babashka.fs`, `babashka.process`, and the kmet
layers (`kmet.extension`, `kmet.tui.*`, `kmet.libs.*` and the shared
renderer namespaces).

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

### Background work (`kmet.libs.concurrent/spawn`)

Under the SCI host contexts (bb and the JVM, and a `:sci`-only extension on Jolt) extension code runs without `future`/`pmap`/`pcalls` — SCI is a pure interpreter with no bundled `Executor`, while `babashka` injects `future` only at the host level (`sci/init {:namespaces {'clojure.core {'future …}}}`). `kmet` deliberately does **not** forward it; use the whitelisted helper `kmet.libs.concurrent/spawn` (the same daemon-`Thread` helper the shipped adapters already copied):

```clojure
(ns my-ext.main
  (:require [kmet.libs.concurrent :as concurrent]))

(concurrent/spawn (fn [] (try (do-work) (catch Throwable _ nil))))
;; => Thread (started, daemon=true); exceptions swallowed, interrupt/join via the returned Thread
```

- Whitelisting `future` would pull in an implicit global pool, let extension work survive `unload-extension!`/`reload-extensions!` and keep `kmet` alive past shutdown, enable unbounded submission with no backpressure, and need the rest of the family (`future-call`/`future-cancel`/`pmap`/`pcalls`) for consistency. An explicit daemon `Thread` keeps the lifecycle obvious and keeps unload/reload clean. On Jolt's native loader there is no interpreter in the way — the extension runs in the runtime — so this is a convention there rather than an enforced absence (a `:sci` fallback context on Jolt has the same absence as bb); the lifecycle reasoning is the same, because `unload` still cannot stop work you started. Keep `Thread` use through `kmet.libs.concurrent/spawn` — don't construct `Thread.` directly in extensions.

#### Limits (inherent to Babashka)

- Babashka only runs libraries it supports: pure-Clojure code using classes
  it exposes. Libraries needing `definterface`, `deftype` with non-protocol
  interfaces, or unexposed Java classes fail to load — in plain bb too.
- The fixed bundled set (see Bundled extension libraries) is injected by
  reference: omit those libs from `deps.edn` (a declared copy is ignored).
- Libraries babashka implements natively (cheshire, clj-yaml, http-kit,
  selmer, ...) usually cannot be replaced by their raw Maven versions — the
  Java classes are missing or not registered for reflection — and are not
  in the set; kmet warns when an extension pins one. Use the `kmet.libs.*`
  seams instead. The `clojure.data.xml` family is bb-only: the port is
  shared there, Jolt needs a declared Maven dep if you want it.
- Single-file extensions (plain `.clj` files, no directory) cannot carry a
  `deps.edn` — they can use the fixed set and the shared libraries, and
  nothing else.

#### Host support (Jolt)

Jolt prefers its own loader: an extension's context there is a set of real
Jolt namespaces, read by the native reader from the extension's own sources
plus its declared deps, shared host layers coming back by reference, and
teardown unmapping what it loaded (`kmet.loader.jolt-loader` adapts the
runtime's loader to the same protocol the bb/JVM SCI backend implements).
An extension declaring `:sci` without `:jolt` instead runs through the SCI
backend on Jolt — the same one babashka uses — as a fallback for extensions
written against the SCI environment. Host differences are implementation,
not author-visible:

- **Classes.** Classes resolve through the compiler, against the runtime's
  class graph — there is no lazily-registered class map (the SCI backend
  builds one: `{:classes {:allow :all}}` delegates instance calls,
  `bb-imports` covers statics/ctors/hints). A class Jolt's graph does not
  supply (e.g. some JDK classes reachable only through a type hint) fails
  the load.
- **SCI.** Jolt's *fallback* backend: an extension whose manifest declares
  `:sci` without `:jolt` evaluates through the same SCI context as on
  babashka. `jolt/deps.edn` pins the SCI build that fixes
  `defrecord`/`extend-type` over host protocols copied with `sci/copy-var*`
  (jolt#1031 / babashka/sci#1093), reader features follow the host (`:jolt`
  on Jolt, so `#?(:bb … :jolt …)` selects what the host selects natively),
  and SCI's `*out*`/`*err*` are bound around evaluation and registered
  callbacks.
- **Deps.** bb resolves the closure to jars and serves them with `ZipFile`;
  Jolt uses `jolt.deps/resolve-deps`, which returns the dependency jars
  unexpanded (Maven/git) or `:local/root` paths — both are read in place
  (`extension-jars` returns jars on both hosts).
- **Jars.** Neither host expands an extension archive: babashka probes
  entries per call with `ZipFile`; Jolt passes the archive to the native
  loader as a source root, so a `require` reads the central directory and
  resources answer `jar:file:…!/entry` URLs.
- **Bundled libraries.** The fixed bundled set (Bundled extension
  libraries) is shared on both hosts: bb's ports serve the namespaces
  there, Jolt compiles kmet's pinned Maven deps into the binary and the
  host view shares them. `clojure.data.xml` stays bb-only — Jolt has no
  copy and it is not in the set, so an extension needing it declares a
  Maven dep there (on bb the port still serves the require).

## Runtime lifecycle

Extensions load at startup and via `/reload`. They can also be managed at
runtime (e.g. from another extension or a REPL):

| Operation | Function | Effect |
|---|---|---|
| Load | `kmet.app.extensions/load-extension!` (path) | load one file or manifest dir; returns `{:extension name :error nil :loader-kind :sci\|:jolt}`, `{:extension nil :error msg}` on failure, or `{:extension name :skipped true :reason :unsupported-loader …}` when the host offers none of the declared backends |
| Unload | `kmet.app.extensions/unload-extension!` (record) | run `shutdown`, deregister everything, remove namespaces |
| Reload | `kmet.app.extensions/reload-extensions!` (dirs) | unload all, reload from the given container dirs |
| List | `kmet.app.extensions/get-loaded-extensions` | `[{:name .. :path .. :loader-kind ..}]` |

A failing `init` is rolled back: the loader unloads whatever was registered
before the error and reports `{:extension nil :error msg}` — no partial state
lingers.

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

A tool source contributes a whole catalog of tools **only to the `script`
tool's sandbox surface**: the model never sees them in its tool schema and
`set-active-tools!` does not filter them. Use it for tools that are meant
to be looped over or fanned out inside scripts but would bloat the model's
context if advertised (the mcp-adapter contributes its cached MCP catalog
this way — script.md T2):

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
  `script` itself.
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
              ;; ctx         — the extension context (pi: ToolExecuteContext)
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
`:title (fn [args])` — pure plain-text data for that quiet line (nil-safe over
partial streaming args; nil/blank falls back to the tool name).
`:render-shell :self` lets the renderer own its outer
box, padding, and status background. A renderer's returned component is
disposed when a later pass replaces it — to keep an instance across passes,
return the same one back (read it from `:last-component` and return it
unchanged); a renderer that returns a fresh component each pass gets the
previous one cleaned up automatically. The supported reusable built-in
renderer vars are in `kmet.app.ui.tool-renderers`, including
`render-edit-call`, `render-edit-result` and `render-bash-result` (a
plain-text output body: styled lines, a collapsed line window with an expand
hint, truncation and elapsed lines — the opt-in grep/find/ls tools use it);
the namespace is explicitly
shared with extensions. Path display helpers are public too:
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
   :constrained-sampling {:type :json-schema :strict :prefer}
   ;; pi constrainedSampling — :json-schema strict ({:strict :prefer}
   ;; degrades silently when the provider/model can't do strict;
   ;; {:strict :require} throws :constrained-sampling-required instead)
   ;; or :grammar (helpers implemented, never activated — pi parity:
   ;; no model sets supportsOpenAIGrammarTools).
   :execute (fn [args] {:content "..."})})
```

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
;; kmet.app.ui.tool-renderers' render-bash-call/render-bash-result for the
;; built-in look — that namespace is shared with extensions).
(ext/register-tool! api
  (ext/create-bash-tool api
    {:name "sandbox-bash" :description "Run a command inside the sandbox"
     :operations my-remote-ops              ; pi: BashOperations
     :shell-path "/bin/ash"                ; pi: shellPath
     :command-prefix "export SANDBOX=1"     ; pi: commandPrefix
     :expose-session-env? false}))           ; pi: exposeSessionEnvironment
```

Options (all optional): `:spawn-hook` `(fn [{:keys [command cwd env]}] → same
map)` run before spawn, after the `KMET_*` session env is injected (pi:
`BashSpawnHook`); `:expose-session-env?` (default true) injects
`KMET_SESSION_ID`/`KMET_SESSION_FILE`/`KMET_PROVIDER`/`KMET_MODEL`/
`KMET_REASONING_LEVEL` into every command — and adds the matching prompt
guideline; `:command-prefix`/`:shell-path` default to the
`:shell-command-prefix`/`:shell-path` settings; `:operations` replaces the
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
(ext/emit-event! api {:type :my-event :data 1})
```

Event types: `:agent-start` `:agent-end` `:agent-settled` `:turn-start`
`:turn-end` `:message-start` `:message-update` `:message-end`
`:tool-execution-start` `:tool-execution-update` `:tool-execution-end`
`:status` `:error` `:session-start` `:session-shutdown`
`:session-info-changed` `:user-bash`
`:session-before-tree` `:session-before-switch` `:session-before-fork`
`:session-before-compact` `:session-tree` `:queue-update` `:model-select`
`:thinking-level-select` `:context-replaced` `:auto-retry-start`
`:auto-retry-end` `:compaction-start` `:compaction-end`
`:session-compact-failed` `:context`
`:before-provider-request` `:before-provider-headers`
`:after-provider-response`.

### Bundled resources (`io/resource` + self-registration)

An extension reads its own bundled files through `clojure.java.io/resource`
(which the host shadows per extension artifact — own artifact first, then
the `deps.edn` closure jars, then the host classpath), so the same code
works for dir installs, symlinked checkouts and unexpanded jars:

```clojure
(ns my-ext.main
  (:require [clojure.java.io :as io]
            [kmet.extension :as ext]))

(defn init [api]
  (ext/register-skill! api (slurp (io/resource "skills/my-skill/SKILL.md"))
                       {:location "my-ext:skills/my-skill/SKILL.md"})
  (ext/register-prompt! api {:name "my-prompt"
                             :content (slurp (io/resource "prompts/my-prompt.md"))
                             :location "my-ext:prompts/my-prompt.md"}))
```

Rules: resources are referenced by exact shipped names — directory listing
inside jars is unsupported. Skills/prompts registered this way are
self-contained single files (no relative refs); the host stores the body in
memory and serves extension skills through the `read` tool under their
`name:path` location (pass the `<location>` verbatim) as well as via
`/skill:name` expansion. Themes need no new api:
`kmet.tui.theme` is shared by reference — call `make-theme` +
`register-theme!` in `init` and `unregister-theme!` on unload. Every
registration returns a deregister fn tracked for automatic unload.

### Contributing resource paths (`resources_discover`)

After `:session-start` (startup, `/new`, `/reload`), kmet fires
`:resources-discover` (pi: resources_discover — fired after session_start)
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
collects each contribution). The paths load into the skills/prompts/theme
registries; already-applied paths are skipped on later discoveries (pi:
mergePaths dedup — discovery fires after every session start, but a
reload re-applies after the registries clear).

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
;; abort signal rides in the event
(ext/on-event api :session-before-compact
  (fn [ev ctx] ...))   ; {:preparation .. :branch-entries .. :reason .. :signal ..}
```

Provider events fire around each LLM call (pi: context /
before_provider_request / before_provider_headers / after_provider_response);
for each, the **last non-nil handler result wins**:

```clojure
;; replace the outgoing messages
(ext/on-event api :context (fn [ev ctx] {:messages [...]}))
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
;; send a custom message (pi: sendMessage): persisted as a custom_message
;; session entry, injected into the LLM context (sent as a user message),
;; rendered in the chat when :display. :trigger-turn starts a run when the
;; agent is idle; while streaming, :deliver-as queues it (:steer injects
;; into the current run, :follow-up/:next-turn defer to the next turn).
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
(`:steer`) or queued until the run settles (`:follow-up`, the default).
With `:expand-prompt-templates?`, the message runs through the submit
chain first — extension commands execute immediately (consuming the
message), then skill commands and prompt templates expand (pi:
expandPromptTemplates; kmet defaults to no expansion).

### UI

The `:ui` capability map dispatches through the runtime registry — calls are
inert before the interactive layout exists and in headless/print mode. There
are **no host-built dialogs**: dialogs/selectors/editors are components you
compose yourself from the shared `kmet.tui.*` layer and mount with
`ui-custom`; theme objects come from `kmet.tui.theme` directly
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
;; (with-let cleanups and reactions unwind via kmet.tui.hiccup ownership):
;;
;;   (require '[kmet.tui.hiccup :as h])
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
;; :reset path (extension reload).
(ext/ui-custom api (fn [tui theme kb close] (my-selector comp close))
                {:overlay true :overlay-options {:anchor :center :width 82}})
```

Headless/print mode has no layout: check `(:mode ctx)` in command/event
handlers (`:interactive` vs headless) and fall back to `ui-notify`.

### Models

```clojure
(def models (ext/models api))
(models/get-all api)                      ; all registered models
(models/get-available api)                ; models with configured auth
(models/find api provider-id model-id)
(models/has-configured-auth api model)
(models/get-provider-auth-status api provider-id)
(models/get-api-key-and-headers api model)
(models/get-registered-provider-config api provider-id)
(models/get-registered-provider-ids api)
(models/register-provider! api :my-provider
                           {:base-url "https://..." :api :openai-completions
                            :api-key "sk-..." :models [{:id "my-model"}]})
(models/unregister-provider! api :my-provider)
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
a headless default and the interactive mode's live `:build-context`
capability per call (pi: `createContext()`). `get-entries` follows the
active branch (a settings entry appended before a tree navigation is not
on the new branch); use `get-all-entries` for extension settings that must
outlive branch jumps (the review extension's custom instructions do).

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
involved. Load the extension's `init` against it and assert what was
registered:

```clojure
(ns my.hello-ext-test
  (:require [clojure.test :refer [deftest is]]
            [kmet.extension :as ext]
            [my.hello-ext :as sut]))

(deftest init-registers-hello-command
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (sut/init api)
    (is (contains? (:commands @state) "hello"))
    ;; deregister fns remove registrations (unload replay)
    (is (= 1 (count (get-in @state [:handlers :session-start]))))))
```

State shape: `:commands` `:tools` `:handlers` `:flags` `:shortcuts`
`:markdown-transformers` `:entry-renderers` `:message-renderers`
`:tool-call-hooks` `:tool-result-hooks` `:input-hooks`
`:before-agent-start-hooks` `:ui-calls` `:emitted` `:model-calls`. Every
registration function returns a deregister fn; the nullable api's deregister
fns remove the corresponding registration.

For runtime integration tests, `load-extension!` + `unload-extension!` against
the real registries (see `../test/kmet/app/test_extensions.clj`).

## Example

A complete extension showing the common pieces:

```clojure
(ns my.ext
  "Example: command + tool + event handler + status."
  (:require [clojure.string :as str]
            [kmet.extension :as ext]))

(defn init [api]
  (ext/ui-set-status api "my-ext" "loaded")

  (ext/register-command! api
    {:name "uppercase"
     :description "Uppercase a string"
     :handler (fn [ctx args] (str/upper-case args))})

  (ext/register-tool! api
    {:name "my-echo"
     :description "Echo the text argument"
     :params {:text {:type :string :description "Text to echo"}}
     :execute (fn [args] {:content (str "echo: " (:text args))})})

  (ext/on-event api :agent-end
    (fn [ev ctx] (ext/ui-notify api "Turn finished" :info)))

  (ext/on-input api
    (fn [{:keys [text]}]
      (when (str/starts-with? text "!magic")
        {:action :transform :text (str "The magic word is " text)}))))

(defn shutdown [api]
  (ext/ui-set-status api "my-ext" nil))
```
