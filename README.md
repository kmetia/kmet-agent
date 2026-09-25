# kmet

A minimal coding agent built in Clojure/Babashka, with a terminal user interface,
streaming LLM conversations, and a local tool loop.

Inspired by [pi](https://pi.dev), kmet runs on Linux, macOS, Windows, WSL, and
Termux. The primary runtime is [Babashka](https://babashka.org/); native
[Jolt](https://github.com/jolt-lang/jolt) builds are also supported.

## Highlights

- Interactive terminal UI with differential rendering, overlays, and a
  multi-line editor.
- Built-in `read`, `write`, `edit`, and `bash` tools.
- Provider, authentication, model-catalog, OAuth, and custom-model support.
- Local extensions, skills, prompt templates, themes, and packages.
- EDNL sessions with branching, compaction, import/export, and sharing.
- Cross-platform source runs and self-contained executables.

## Quick start

Install [Babashka](https://babashka.org/) 1.13.224 or newer, then run kmet
from a checkout:

```sh
git clone https://github.com/kmetia/kmet-agent.git
cd kmet-agent
export OPENCODE_API_KEY='your-api-key'
bb run
```

List available models or run a one-shot request with:

```sh
bb run --list-models
bb run --print "summarize the project"
```

Use `jolt run -m kmet.core` on the optional Jolt host. A packaged executable
can be run as `kmet`; see [Getting started](docs/getting-started.md) for
provider setup, sessions, and installation details.

## Documentation

Start with the [user documentation](docs/README.md):

- [Getting started](docs/getting-started.md) — requirements, setup, and first run.
- [Usage](docs/usage.md) — command-line options, slash commands, shortcuts, and sessions.
- [Configuration](docs/configuration.md) — settings, prompts, compaction, and resource paths.
- [Providers and authentication](docs/providers.md) — API keys, OAuth, and custom models.
- [Customization](docs/customization.md) — themes, skills, prompts, packages, and extensions.
- [Building](docs/building.md) — Babashka and Jolt distributions.
- [Examples](docs/examples/README.md) — copyable settings and theme files.

## The name

*kmet* (Cyrillic **кмет**) is an old Slavic word with two complementary
meanings: a community leader and a land-bound worker. The name captures the
agent's posture—acting with authority on your behalf while staying responsible
to the code and people who own it. The longer etymology is in
[About kmet](docs/about.md).

## Project layout

```text
src/kmet/       — the application and its libraries
extensions/     — opt-in shipped extensions
jolt/           — Jolt provider scaffolding
docs/           — user-facing documentation
test/           — test suites
tasks/          — development and packaging tasks
```

Implementation and development references are kept close to their packages;
start with [`src/kmet/README.md`](src/kmet/README.md) for contributor
information.

## License

Copyright © 2026 — present Marko Kocic <marko@euptera.com>

Licensed under the Eclipse Public License 2.0 ([EPL-2.0](LICENSE)).
