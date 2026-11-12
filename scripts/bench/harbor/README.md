# Terminal-Bench / Harbor adapter for kmet

Terminal-Bench no longer carries an agent runner. Its current harness is
[Harbor](https://harborframework.com/docs), and Harbor already supports local
custom agents through an import path. **No patch to Harbor or Terminal-Bench is
needed to benchmark kmet.** Put this directory on `PYTHONPATH` and pass
`-a kmet_agent:KmetAgent`.

The adapter:

1. installs the kmet executable in the task container, or leaves a provided
   command alone (`--ak kmet_command=...`);
2. runs kmet's headless print mode with the task instruction;
3. writes `agent/trajectory.json` in ATIF v1.7 and fills Harbor's token/cost
   fields from kmet's `.ednl` session.

By default it runs and versions kmet through `~/bin/kmet.sh`. When the adapter
installs a binary, it creates that wrapper for root and for the effective agent
user, so the same command works for both root and `user = "agent"` tasks.

## Prerequisites

- Harbor (`uv tool install 'harbor[modal]'`, or the local Harbor checkout).
- A container runtime. Docker is Harbor's default. Podman works with
  `-e podman` and a working compose frontend (`uv tool install podman-compose`).
- The task environment can reach the model endpoint. Harbor's `agent.setup()`
  uses the task's baseline network policy; the adapter uploads the executable
  rather than downloading it, so only the model endpoint is required at run
  time.

## Run the current kmet build

Build the self-contained release zip from the checkout, then pass it to the
adapter. This benchmarks exactly the current source:

```sh
cd ~/src/kmetia/kmet-agent
bb dist linux-amd64                 # or: bb dist (host platform)
```

```sh
cd ~/src/cvstree/harbor
OPENAI_API_KEY=... \
PYTHONPATH=~/src/kmetia/kmet-agent/scripts/bench/harbor \
uv run harbor run \
  -p ~/src/cvstree/terminal-bench/tasks/hello-workdir \
  -e podman \
  -a kmet_agent:KmetAgent \
  -m openai/<model-id> \
  --ak kmet_zip=~/src/kmetia/kmet-agent/dist/kmet-0.3.1-linux-amd64.zip \
  -o ~/src/kmetia/kmet-agent/target/harbor-jobs \
  --job-name kmet-smoke
```

Omit `--ak kmet_zip=...` to download the latest GitHub release instead. Pin a
release with `--ak version=0.3.1`.

## Run the live checkout through `~/bin/kmet.sh`

Pass the checkout and the adapter does the rest:

```sh
cd ~/src/cvstree/harbor
OPENAI_API_KEY=... \
PYTHONPATH=~/src/kmetia/kmet-agent/scripts/bench/harbor \
uv run harbor run \
  -p ~/src/cvstree/terminal-bench/tasks/hello-workdir \
  -e podman \
  -a kmet_agent:KmetAgent \
  -m openai/<model-id> \
  --ak kmet_checkout=~/src/kmetia/kmet-agent
```

In checkout mode the adapter:

1. uploads a Babashka binary (the host `bb` when it is a compatible Linux
   build, otherwise the `:min-bb-version` static release, cached in
   `~/.cache/kmet-harbor/`);
2. archives the checkout without `.git`, `target/`, `dist/`, `.jolt/`, tool
   caches and logs, and extracts it to `/opt/kmet-checkout`;
3. symlinks `/opt/kmet-checkout/kmet.sh` to `~/bin/kmet.sh` for root and the
   effective agent user, leaving the script unmodified;
4. warms the Babashka/Maven caches during setup, while the task's baseline
   network policy is active;
5. runs `~/bin/kmet.sh` and detects the version with
   `~/bin/kmet.sh --version`.

Use `--ak bb_version=1.13.229` to pin Babashka. This mode is the development
loop: it benchmarks uncommitted source with no `bb dist` step, but uploads
Babashka plus the checkout for every trial. For a full benchmark run, build the
dist zip once (or bake the checkout and Babashka into a task image).

A prebuilt wrapper can also be supplied directly, without the adapter touching
the container:

```sh
--ak skip_install=true --ak kmet_command=/path/in/container/kmet.sh
```

Mount the checkout and Babashka with `--mounts`, or bake them into the image.

## Model routing

Harbor resolves the provider, model ID, API key and base URL. The adapter writes
that route into `$KMET_CODING_AGENT_DIR/models.edn` as a `:harbor` custom
provider, so kmet does not need the model in its own catalog.

- Known provider defaults work with `-m provider/model` and the provider's
  usual key (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY`, ...).
- For an OpenAI-compatible gateway, pass
  `--base-url https://.../v1 --api-format openai_chat_completions`.
- `--effort <level>` maps to kmet's `--thinking <level>`.
- Force a kmet-native provider instead with
  `--ak kmet_provider=<provider>` (then kmet uses its own catalog and
  credentials).
- `--ak max_tokens=...` and `--ak context_window=...` override the model
  metadata; `--ak litellm_metadata=false` turns off the LiteLLM lookup.
- `--ak tools=eval` (for example) exercises a kmet tool surface.

## Tasks and results

The Terminal-Bench repository is only tasks plus the dataset manifest, so run
local tasks directly:

```sh
# one task
uv run harbor run -p ~/src/cvstree/terminal-bench/tasks/batched-eval-parity ...

# all tasks in the local checkout
uv run harbor run -p ~/src/cvstree/terminal-bench/tasks ...
```

A trial writes:

```
<jobs>/<job>/<task>__<id>/
  agent/kmet-output.txt
  agent/kmet-sessions/<cwd-key>/*.ednl
  agent/kmet-agent/models.edn
  agent/trajectory.json
  verifier/reward.txt              # 1.0 or 0.0
  result.json                      # agent_result: tokens/cost
```

`harbor view <jobs>` opens the local results viewer.

## Upstream registration (optional)

Keep the adapter local unless you want `-a kmet` and leaderboard visibility.
Then the Harbor patch is small:

1. Add `KMET = "kmet"` to `src/harbor/models/agent/name.py`.
2. Add
   `AgentName.KMET: "harbor.agents.installed.kmet:KmetAgent"` to
   `AgentFactory._AGENT_MAP`.
3. Move this adapter to `src/harbor/agents/installed/kmet.py`, add tests and
   the agent docs page.

Terminal-Bench itself needs no change: it has no agent registry. A result
submission only needs the job directory and a leaderboard entry, and Harbor's
`harbor upload` handles sharing.

## Verified smoke test

On this host (Podman 5.4.2 + `podman-compose` 1.6.0, Harbor 0.25.0), both
modes ran against `examples/tasks/hello-workdir` with a local mock OpenAI
endpoint:

- `--ak kmet_zip=kmet-0.3.1-linux-amd64.zip`: reward `1.0`
- `--ak kmet_checkout=~/src/kmetia/kmet-agent` (live source, `bb 1.13.229`):
  reward `1.0`

Each produced three ATIF steps and `agent_result.n_input_tokens = 220`,
`n_output_tokens = 22`. The checkout run executed the unmodified
`~/bin/kmet.sh` wrapper inside the container.

A note on this host: task verifiers that download `uv` with `curl` fail because
the sandbox does not trust the host's MITM CA. Tasks with local verifiers work;
for network-dependent verifiers, configure the proxy CA in the task image or via
Harbor's environment settings.
