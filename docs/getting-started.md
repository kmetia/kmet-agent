# Getting started

The commands below use `bb start` from a source checkout. If you built or
installed a self-contained executable, use the same options with `kmet` in
place of `bb start` (or unzip the built `dist/kmet-*.zip`, whose `kmet-*/`
folder holds the `kmet` executable and the LICENSE; on Jolt it is `kmetj`).

### 1. Run from a checkout

Install Babashka first, then clone and start kmet:

```sh
git clone https://github.com/kmetia/kmet-agent.git
cd kmet-agent
bb start
```

For a packaged build, use `bb dist` (or `jolt dist` on the Jolt host) and
unzip the artifact from `dist/`; `bb dist --out ~/bin` (or `jolt dist --out
~/bin`) also puts the bare executable where you want it. See
[Building](building.md) for the options and artifact layout.

### 2. Configure a provider

The default `opencode-go` provider reads `OPENCODE_API_KEY`:

```sh
export OPENCODE_API_KEY='your-api-key'
bb start --list-models
bb start --provider opencode-go --model deepseek-v4-flash
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
bb start --print "summarize the project"
```

Use `@path/to/file` before the message to attach file contents to the first
request. See [In-TUI commands](usage.md#in-tui-commands) for interactive commands and
[Configuration](configuration.md) for persistent settings, sessions, and
resource locations.

## Prerequisites

- [Babashka](https://babashka.org/) ≥ 1.13.225 (bundles JLine 4.4.6) — the
  primary host
- [Jolt](https://github.com/jolt-lang/jolt) ≥ v0.8.20 — optional: the same
  code runs natively on Jolt (`jolt start`, `jolt dist`). kmet targets the
  latest tagged Jolt release.
- A provider API key or supported OAuth credential. See [Providers and
  authentication](providers.md) for the environment-variable and `/login`
  reference.
