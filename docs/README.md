# kmet documentation

This directory contains the documentation intended for people using kmet.
Implementation, API, design, and performance notes live next to the code they
describe instead of being mixed into this user guide. The temporary root notes
`perf.md`, `jolt-bugs.md`, `tcl.md`, `fix.md` and `new-app.md`
are the deliberate exception.

## Start here

- [Getting started](getting-started.md) — install a runtime, configure a
  provider, and make the first request.
- [Usage](usage.md) — run the TUI or print mode, use slash commands, and
  navigate sessions.
- [Profiles and composition](profiles.md) — what a run mounts, `--profile`, and
  how `--patch` and the global/project `patch.edn` layers edit the row list.
- [Composition catalog](composition.md) — every built-in bundle and profile row
  by row, and the tools the model is offered (generated).
- [RPC mode](rpc.md) — drive a long-lived session over EDN stdin/stdout:
  protocol, commands, and extension UI.
- [Server mode](serve.md) — the same protocol on a socket, one agent per
  connection: the token, the session cap, the per-wire queue, and approvals.
- [Configuration](configuration.md) — settings files, prompts, compaction,
  HTTP, and resource locations.

## Guides and reference

- [Providers and authentication](providers.md) — model discovery, API keys,
  OAuth, custom providers, and catalog refreshes.
- [Customization](customization.md) — themes, skills, prompt templates,
  packages, and extensions.
- [Writing extensions](extensions.md) — the authoring guide: layouts, loaders,
  dependencies, UI contributions, and testing.
- [Building](building.md) — create self-contained Babashka, Jolt, and JVM
  artifacts.
- [About kmet](about.md) — the meaning and history behind the name.
- [Examples](examples/README.md) — copyable settings and complete theme EDN
  files.

For extension authors, [Writing extensions](extensions.md) is the authoring
guide, the shipped extension overview is in
[`extensions/README.md`](../extensions/README.md), and the authoritative
`kmet.extension` contract is in [`modules/app/src/kmet/extension.md`](../modules/app/src/kmet/extension.md).

Contributor and implementation documentation is kept beside the source it
describes; the root [`README.md`](../README.md) links to the developer guide.
