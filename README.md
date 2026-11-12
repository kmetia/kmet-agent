# kmet

A minimal coding agent built in Clojure, with a terminal user interface,
streaming LLM conversations, and a local tool loop.

kmet is inspired by two reference harnesses:

- **[pi](https://pi.dev)** — the functionality: minimalism, a terminal-first
  TUI with a multi-line editor and differential rendering, streaming
  conversations, one local tool loop, and behavior carried by prompts and
  tools instead of a privileged core.
- **[DeepSeek Harness](https://deepseek-harness.github.io/deepseek-harness/)** —
  the architecture: *everything is a plugin*. The loop, tools, sessions, model
  access, and modes are replaceable rows on one context tree, with services
  found by key, dependencies declared with `inject`, effects that unwind, and
  extensions that mount beside the core instead of patching it.

The gap to pi is tracked in [pi alignment](docs/pi-alignment.md); the plugin
architecture is in the [developer guide](docs/architecture.md).

kmet runs on Linux, macOS, Windows, WSL, and Termux from one source tree on
three hosts: [Babashka](https://babashka.org/) (the primary runtime), native
[Jolt](https://github.com/jolt-lang/jolt) builds, and the JVM (JDK 22+ enables
the native clipboard backend; older JDKs still run the app).

## Highlights

- Interactive terminal UI with differential rendering, overlays, and a
  multi-line editor.
- Built-in `read`, `write`, `edit`, and `bash` tools, plus tool search and
  session recall; opt-in `codemode` and `eval` add a sandboxed Clojure tool
  environment.
- Profiles and `--patch` layers that compose the plugin rows a run mounts.
- Headless print, RPC, and socket-server modes for one-shot runs, editors,
  and scripts.
- Provider, authentication, model-catalog, OAuth, and custom-model support.
- Local extensions, skills, prompt templates, themes, and packages (local
  or git).
- EDNL sessions with branching, compaction, import/export, and sharing.
- Cross-platform source runs on Babashka, Jolt, and the JVM, plus
  self-contained executables.

## Quick start

Install [Babashka](https://babashka.org/) 1.13.229 or newer, then run kmet
from a checkout:

```sh
git clone https://github.com/kmetia/kmet-agent.git
cd kmet-agent
export OPENCODE_API_KEY='your-api-key'
bb start
```

List available models or run a one-shot request with:

```sh
bb start --list-models
bb start --print "summarize the project"
```

The same checkout runs on the optional hosts: `jolt start` on
[Jolt](https://github.com/jolt-lang/jolt) and `bb start-jvm` on JVM Clojure,
which needs the [Clojure CLI](https://clojure.org/guides/install_clojure) (and
Java 22+ for the clipboard's native backend). The JVM host mirrors the test
and packaging tasks (`bb test-jvm`, `bb dist-jvm`).

Self-contained release binaries skip the Babashka install: download the zip
for your platform from
[GitHub Releases](https://github.com/kmetia/kmet-agent/releases) — releases
ship `kmet-<version>-<platform>.zip` (the latest release is `v0.3.1`), with
`<platform>` one of `linux-amd64`, `linux-aarch64`, `macos-amd64`,
`macos-aarch64`, and `windows-amd64`. Unzip it and run the executable inside
the extracted folder, which is named after the zip:

```sh
unzip kmet-0.3.1-linux-amd64.zip
./kmet-0.3.1-linux-amd64/kmet --list-models
```

On Windows run `kmet.exe`; on Termux run the bundled `kmet.sh` launcher. The
other hosts follow the same scheme with their own prefix and command: jolt's
`kmetj-<version>-<platform>-<link>.zip` (`<link>` is `static` or `dynamic`)
runs as `kmetj`, and the JVM's `kmetc-<version>-jvm.zip` ships an uberjar with
launchers, needs a Java runtime, and runs as `kmetc`, so all three can be
installed side by side and invoked by different names.

See [Getting started](docs/getting-started.md) for provider setup, sessions,
and installation details.

## Documentation

Start with the [user documentation](docs/README.md):

- [Getting started](docs/getting-started.md) — requirements, setup, and first
  run.
- [Usage](docs/usage.md) — command-line options, slash commands, shortcuts,
  and sessions.
- [Profiles and composition](docs/profiles.md) — profiles, `--patch` layers,
  and the generated [composition catalog](docs/composition.md).
- [RPC](docs/rpc.md) and [server](docs/serve.md) modes — drive a session over
  EDN stdin/stdout or a socket.
- [Configuration](docs/configuration.md) — settings, prompts, compaction, and
  resource paths.
- [Providers and authentication](docs/providers.md) — API keys, OAuth, and
  custom models.
- [Customization](docs/customization.md) — themes, skills, prompts, packages,
  and extensions.
- [Writing extensions](docs/extensions.md) — the authoring guide for extension
  packages.
- [Building](docs/building.md) — Babashka, Jolt, and JVM distributions.
- [Examples](docs/examples/README.md) — copyable settings and theme files.

## The name

*kmet* (Cyrillic **кмет**) is an old Slavic word with two complementary
meanings: a community leader and a land-bound worker. The name captures the
agent's posture—acting with authority on your behalf while staying responsible
to the code and people who own it. The longer etymology is in
[About kmet](docs/about.md).

## Project layout

```text
modules/        — application modules and libraries (app, ai, libs, srce, tui,
                  loader, tk, reakt, hikap); each is a standalone classpath root
extensions/     — bundled opt-in extensions and single-file tools
jolt/           — Jolt provider scaffolding
docs/           — user guides and references
test/           — cross-cutting tests (module tests sit beside each module)
tasks/          — development and packaging tasks
scripts/        — repo tooling (git hooks, repros, capture and benchmark helpers)
```

Implementation and development references sit beside the source they describe
— for example, the kernel and TUI design notes — and
[`docs/architecture.md`](docs/architecture.md) is the contributor's map.

## License

Copyright © 2026 — present Marko Kocic <marko@euptera.com>

Licensed under the Eclipse Public License 2.0 ([EPL-2.0](LICENSE)).
