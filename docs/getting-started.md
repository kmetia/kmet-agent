# Getting started

The commands below use `bb run` from a source checkout. If you built or
installed a self-contained executable, use the same options with `kmet` in
place of `bb run` (or invoke the generated `dist/kmet-*` file directly).

### 1. Run from a checkout

Install Babashka first, then clone and start kmet:

```sh
git clone https://github.com/kmetia/kmet-agent.git
cd kmet-agent
bb run
```

For a packaged build, use `bb dist` (or `jolt dist` on the Jolt host) and
run the resulting executable in `dist/`; see [Building](building.md) for the
options and artifact names.

### 2. Configure a provider

The default `opencode-go` provider reads `OPENCODE_API_KEY`:

```sh
export OPENCODE_API_KEY='your-api-key'
bb run --list-models
bb run --provider opencode-go --model deepseek-v4-flash
```

You can also start kmet and use `/login` to store a credential in
`~/.kmet/agent/auth.edn`, or use `/login <provider>` for a supported OAuth
provider. Environment variables are not written to the settings file. Use
the provider and model names shown by `--list-models` when selecting a
different account or model.

### 3. Automate a request

For a one-shot response, use print mode from the checkout or a packaged
executable:

```sh
bb run --print "summarize the project"
```

Use `@path/to/file` before the message to attach file contents to the first
request. See [In-TUI commands](usage.md#in-tui-commands) for interactive commands and
[Configuration](configuration.md) for persistent settings, sessions, and
resource locations.

## Prerequisites

- [Babashka](https://babashka.org/) ≥ 1.13.224 (bundles JLine 4.4.5) — the
  primary host
- [Jolt](https://github.com/jolt-lang/jolt) ≥ v0.8.9 — optional: the same
  code runs natively on Jolt (`jolt run -m kmet.core`, `jolt dist`). For
  Windows jar resources and extension-source cleanup,
  use a build containing PR #1123 (verified on
  `v0.8.11-18-g79bf6d6e`) or newer.
- A provider API key or supported OAuth credential. See [Providers and
  authentication](providers.md) for the environment-variable and `/login`
  reference.
