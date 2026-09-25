# kmet documentation

This directory contains the documentation intended for people using kmet.
Implementation, API, design, and performance notes live next to the code they
describe instead of being mixed into this user guide. The temporary root notes
`script.md`, `perf.md`, `jolt-bugs.md`, and `jolt-port.md` are the deliberate
exception.

## Start here

- [Getting started](getting-started.md) — install a runtime, configure a
  provider, and make the first request.
- [Usage](usage.md) — run the TUI or print mode, use slash commands, and
  navigate sessions.
- [Configuration](configuration.md) — settings files, prompts, compaction,
  HTTP, and resource locations.

## Guides and reference

- [Providers and authentication](providers.md) — model discovery, API keys,
  OAuth, custom providers, and catalog refreshes.
- [Customization](customization.md) — themes, skills, prompt templates,
  packages, and extensions.
- [Building](building.md) — create self-contained Babashka and Jolt binaries.
- [About kmet](about.md) — the meaning and history behind the name.
- [Examples](examples/README.md) — copyable settings and complete theme EDN
  files.

For extension authors, the shipped extension overview is in
[`extensions/README.md`](../extensions/README.md), and the authoritative
`kmet.extension` contract is in [`src/kmet/extension.md`](../src/kmet/extension.md).

Contributor and implementation documentation is kept beside the source it
describes; the root [`README.md`](../README.md) links to the developer guide.
