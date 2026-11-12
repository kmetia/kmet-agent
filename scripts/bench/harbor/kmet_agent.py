"""Harbor installed-agent adapter for kmet.

Harbor runs an agent inside the task container. This adapter installs (or
uploads) a kmet executable, runs it in headless print mode with the task
instruction, and converts kmet's EDN session into Harbor's token/cost metrics
and ATIF trajectory.

No Harbor or Terminal-Bench patch is needed: pass this module to Harbor as a
custom agent import path::

    uv run --project ~/src/cvstree/harbor harbor run \
      -p ~/src/cvstree/terminal-bench/tasks/hello-world \
      -e podman \
      -a kmet_agent:KmetAgent \
      -m openai/gpt-5.4-mini \
      --ak kmet_checkout=~/src/kmetia/kmet-agent

or pass a prebuilt archive with ``--ak kmet_zip=dist/kmet-<ver>-<platform>.zip``.

Put this directory on PYTHONPATH (or install it) so ``kmet_agent`` is
importable, and set the provider API key in the environment Harbor sees.
"""

from __future__ import annotations

import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile
from dataclasses import replace
from pathlib import Path
from typing import Any, Literal, override

from harbor.agents.agent_auth_resolution import ModelConnectionSpec, ProviderAccess
from harbor.agents.capabilities import AgentCapabilities
from harbor.agents.installed.base import BaseInstalledAgent, with_prompt_template
from harbor.agents.options import InstalledAgentOptions
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext
from harbor.models.model_api_format import ModelApiFormat
from harbor.models.trajectories import (
    Agent,
    FinalMetrics,
    Metrics,
    Observation,
    ObservationResult,
    Step,
    ToolCall,
    Trajectory,
)
from harbor.utils.trajectory_utils import format_trajectory_json

try:
    import kmet_edn
except ImportError:  # loaded as a package instead of a top-level module
    from . import kmet_edn

_REMOTE_ROOT = "/opt/kmet"
_REMOTE_BIN = f"{_REMOTE_ROOT}/kmet"
_REMOTE_CHECKOUT = "/opt/kmet-checkout"
_REMOTE_BB_DIR = "/opt/kmet-bb"
_REMOTE_BB = f"{_REMOTE_BB_DIR}/bb"
_DEFAULT_BB_VERSION = "1.13.229"
_REMOTE_LINK = "/usr/local/bin/kmet"
_DEFAULT_COMMAND = "$HOME/bin/kmet.sh"
_REMOTE_AGENT_DIR = "/logs/agent/kmet-agent"
_REMOTE_SESSION_DIR = "/logs/agent/kmet-sessions"
_REMOTE_INSTRUCTION = "/logs/agent/kmet-instruction.md"
_REMOTE_OUTPUT = "/logs/agent/kmet-output.txt"

K = kmet_edn.Keyword

_API_FORMATS = {
    ModelApiFormat.OPENAI_CHAT_COMPLETIONS: "openai-completions",
    ModelApiFormat.OPENAI_RESPONSES: "openai-responses",
    ModelApiFormat.ANTHROPIC_MESSAGES: "anthropic-messages",
    ModelApiFormat.GOOGLE_GENERATE_CONTENT: "google-generative-ai",
    ModelApiFormat.BEDROCK_CONVERSE: "bedrock-converse-stream",
}


class KmetOptions(InstalledAgentOptions):
    kmet_checkout: str | None = None
    kmet_command: str | None = None
    kmet_zip: str | None = None
    kmet_bin: str | None = None
    bb_version: str | None = None
    model_api: Literal[
        "openai-completions",
        "openai-responses",
        "anthropic-messages",
        "google-generative-ai",
        "bedrock-converse-stream",
    ] | None = None
    kmet_provider: str | None = None
    tools: str | None = None
    context_window: int = 128000
    max_tokens: int = 16384
    reasoning: bool = True
    no_context_files: bool = False
    litellm_metadata: bool = True


class KmetAgent(BaseInstalledAgent):
    """Run a kmet executable inside a Harbor task environment."""

    capabilities = AgentCapabilities(
        base_url=True,
        reasoning_effort=True,
        atif=True,
    )
    MODEL_CONNECTION = ModelConnectionSpec(
        default_provider="openai",
        agent_provider_access=ProviderAccess(
            api_key_candidate_names=["OPENAI_API_KEY"],
            base_url_candidate_names=["OPENAI_BASE_URL", "OPENAI_API_BASE"],
        ),
        api_key_destination_names={"default": "KMET_HARBOR_API_KEY"},
        base_url_destination_names={"default": None},
        default_api_format_by_provider={
            "openai": ModelApiFormat.OPENAI_CHAT_COMPLETIONS,
            "openai-responses": ModelApiFormat.OPENAI_RESPONSES,
            "anthropic": ModelApiFormat.ANTHROPIC_MESSAGES,
            "gemini": ModelApiFormat.GOOGLE_GENERATE_CONTENT,
            "google": ModelApiFormat.GOOGLE_GENERATE_CONTENT,
            "amazon-bedrock": ModelApiFormat.BEDROCK_CONVERSE,
            "bedrock": ModelApiFormat.BEDROCK_CONVERSE,
            "default": ModelApiFormat.OPENAI_CHAT_COMPLETIONS,
        },
        supported_api_formats=list(_API_FORMATS),
    )

    options_model = KmetOptions
    options: KmetOptions

    @staticmethod
    @override
    def name() -> str:
        return "kmet"

    def _command(self) -> str:
        return self.options.kmet_command or _DEFAULT_COMMAND

    def _path_prefix(self) -> str:
        return f"PATH={_REMOTE_BB_DIR}:$PATH " if self.options.kmet_checkout else ""

    @override
    def get_version_command(self) -> str | None:
        return f'{self._path_prefix()}"{self._command()}" --version'

    @override
    def parse_version(self, stdout: str) -> str:
        lines = [line for line in stdout.splitlines() if line.strip()]
        if not lines:
            return "unknown"
        last = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", lines[-1]).strip()
        return last.removeprefix("kmet ").removeprefix("v")

    @override
    async def install(self, environment: BaseEnvironment) -> None:
        # A provided command is the caller's own kmet (a mounted checkout, a
        # prebuilt image, or a wrapper); do not replace it.
        if self.options.kmet_command:
            return

        if self.options.kmet_checkout:
            await self._install_checkout(
                environment, Path(self.options.kmet_checkout)
            )
            return

        await self.exec_as_root(environment, command=f"mkdir -p {_REMOTE_ROOT}")

        if self.options.kmet_bin:
            await self._link_binary(environment, self.options.kmet_bin)
            return

        if self.options.kmet_zip:
            await self._install_archive(environment, Path(self.options.kmet_zip))
            return

        await self._install_release(environment)

    async def _install_checkout(
        self, environment: BaseEnvironment, source: Path
    ) -> None:
        """Upload a live checkout and Babashka, then use its kmet.sh wrapper."""
        source = source.expanduser().resolve()
        if not (source / "kmet.sh").is_file():
            raise FileNotFoundError(f"kmet checkout not found: {source}")

        bb = _babashka_binary(source, self.options.bb_version)
        await self.exec_as_root(
            environment,
            command=(
                f"rm -rf {_REMOTE_CHECKOUT} {_REMOTE_BB_DIR} && "
                f"mkdir -p {_REMOTE_CHECKOUT} {_REMOTE_BB_DIR}"
            ),
        )
        await environment.upload_file(bb, _REMOTE_BB)
        await self.exec_as_root(environment, command=f"chmod 755 {_REMOTE_BB}")

        with tempfile.TemporaryDirectory(prefix="kmet-harbor-") as directory:
            archive = Path(directory) / "checkout.tar.gz"
            _archive_checkout(source, archive)
            remote = "/opt/kmet-checkout.tar.gz"
            await environment.upload_file(archive, remote)
        await self.exec_as_root(
            environment,
            command=(
                f"tar -xzf {remote} -C {_REMOTE_CHECKOUT} && "
                f"rm -f {remote} && "
                f"chmod -R a+rwX {_REMOTE_CHECKOUT} && "
                f"test -x {_REMOTE_CHECKOUT}/kmet.sh"
            ),
        )

        # kmet.sh follows symlinks to resolve its checkout, so a symlink at
        # ~/bin/kmet.sh keeps the script itself unmodified.
        script = f"{_REMOTE_CHECKOUT}/kmet.sh"
        await self.exec_as_root(
            environment,
            command=f"mkdir -p /root/bin && ln -sf {script} /root/bin/kmet.sh",
        )
        await self.exec_as_agent(
            environment,
            command=(
                'mkdir -p "$HOME/bin" && '
                f'ln -sf {script} "$HOME/bin/kmet.sh"'
            ),
        )
        # Warm the Maven/git dependency caches during setup, while the task's
        # baseline network policy is active. The agent phase may be restricted.
        await self.exec_as_agent(
            environment,
            command=(
                f"PATH={_REMOTE_BB_DIR}:$PATH "
                f"{_REMOTE_BB} --config {_REMOTE_CHECKOUT}/bb.edn -e nil"
            ),
        )

    async def _install_archive(self, environment: BaseEnvironment, source: Path) -> None:
        source = source.expanduser()
        if not source.is_file():
            raise FileNotFoundError(f"kmet archive not found: {source}")
        remote = f"{_REMOTE_ROOT}/kmet-upload"
        if source.suffix == ".zip":
            # Extract on the host: task images need not have unzip, and a
            # package-manager probe on a Podman compose provider can return
            # its banner on stdout (Harbor reads it as the manager name).
            with tempfile.TemporaryDirectory(prefix="kmet-harbor-") as directory:
                with zipfile.ZipFile(source) as archive:
                    member = next(
                        (
                            name
                            for name in archive.namelist()
                            if Path(name).name in ("kmet", "kmet.exe", "kmet.sh")
                        ),
                        None,
                    )
                    if member is None:
                        raise RuntimeError(f"no kmet executable found in {source}")
                    local = Path(directory) / Path(member).name
                    local.write_bytes(archive.read(member))
                await environment.upload_file(local, remote)
        else:
            await environment.upload_file(source, remote)
        await self._link_binary(environment, remote)

    async def _install_release(self, environment: BaseEnvironment) -> None:
        version = _release_version(self._version)
        archive = _download_release(version)
        await self._install_archive(environment, archive)

    async def _link_binary(self, environment: BaseEnvironment, binary: str) -> None:
        wrapper = (
            "#!/bin/sh\n"
            f"exec {_REMOTE_BIN} \"$@\"\n"
        )
        await self.exec_as_root(
            environment,
            command=(
                f"cp {shlex.quote(binary)} {_REMOTE_BIN} && "
                f"chmod 755 {_REMOTE_BIN} && "
                f"ln -sf {_REMOTE_BIN} {_REMOTE_LINK} && "
                f"mkdir -p /root/bin && "
                f"printf %s {shlex.quote(wrapper)} > /root/bin/kmet.sh && "
                f"chmod 755 /root/bin/kmet.sh"
            ),
        )
        # The run command uses ~/bin/kmet.sh, so put the wrapper in the
        # effective agent user's home too (root above, agent here).
        await self.exec_as_agent(
            environment,
            command=(
                'mkdir -p "$HOME/bin" && '
                f"printf %s {shlex.quote(wrapper)} > \"$HOME/bin/kmet.sh\" && "
                'chmod 755 "$HOME/bin/kmet.sh"'
            ),
        )
        await self.exec_as_agent(
            environment, command=f"{shlex.quote(_REMOTE_LINK)} --version"
        )

    @override
    async def run(
        self,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
    ) -> None:
        # Upload the prompt instead of putting it on the command line: task
        # instructions contain newlines and shell metacharacters.
        await self._upload_config_text(
            environment,
            content=instruction,
            remote_path=_REMOTE_INSTRUCTION,
            filename="kmet-instruction.md",
        )
        await self.exec_as_agent(
            environment,
            command=(
                f"mkdir -p {shlex.quote(_REMOTE_AGENT_DIR)} "
                f"{shlex.quote(_REMOTE_SESSION_DIR)}"
            ),
        )

        connection = self.model_connection
        env = dict(connection.env)
        provider, model_id, model_config = self._connection(
            connection, env
        )
        if model_config is not None:
            await self._write_models(environment, model_config)

        flags = self._kmet_flags(provider, model_id)
        instruction_arg = f'"$(cat {shlex.quote(_REMOTE_INSTRUCTION)})"'
        command = (
            f"KMET_CODING_AGENT_DIR={shlex.quote(_REMOTE_AGENT_DIR)} "
            f"{self._path_prefix()}"
            f'"{self._command()}" --mode text '
            f"--session-dir {shlex.quote(_REMOTE_SESSION_DIR)} "
            f"{flags}"
            f"{instruction_arg} 2>&1 | tee {shlex.quote(_REMOTE_OUTPUT)}"
        )
        await self.exec_as_agent(environment, command=command, env=env)

    def _connection(
        self, connection: Any, env: dict[str, str]
    ) -> tuple[str | None, str, dict[str, Any] | None]:
        """Return (provider, model id, custom models.edn config or None)."""
        model_id = connection.model_id or (
            self.model_name.rsplit("/", 1)[-1] if self.model_name else ""
        )
        if not model_id:
            raise ValueError("kmet needs --model <provider>/<model-id>")

        if self.options.kmet_provider:
            return self.options.kmet_provider, model_id, None

        api_format = self.options.model_api or _API_FORMATS.get(
            connection.model_api_format
        )
        base_url = connection.execution_base_url or connection.candidate_base_url
        if not base_url or not api_format:
            provider = connection.provider
            if not provider:
                raise ValueError(
                    "kmet needs a provider endpoint or --ak kmet_provider=<name>"
                )
            return provider, model_id, None

        provider = "harbor"
        api_key = connection.api_key
        if api_key is not None:
            env["KMET_HARBOR_API_KEY"] = api_key
        model = self._model_entry(model_id)
        provider_config: dict[Any, Any] = {
            K(":name"): "Harbor",
            K(":base-url"): base_url,
            K(":api"): api_format,
            K(":models"): [model],
        }
        if api_key is not None:
            provider_config[K(":api-key")] = "$KMET_HARBOR_API_KEY"
        config: dict[Any, Any] = {
            K(":providers"): {K(f":{provider}"): provider_config}
        }
        return provider, model_id, config

    def _model_entry(self, model_id: str) -> dict[str, Any]:
        context_window = self.options.context_window
        max_tokens = self.options.max_tokens
        cost = None
        if self.options.litellm_metadata:
            info = _litellm_info(self.model_name)
            context_window = int(
                info.get("max_input_tokens") or info.get("max_tokens") or context_window
            )
            max_tokens = int(info.get("max_output_tokens") or max_tokens)
            rates = {
                "input": info.get("input_cost_per_token"),
                "output": info.get("output_cost_per_token"),
                "cache-read": info.get("cache_read_input_token_cost"),
                "cache-write": info.get("cache_creation_input_token_cost"),
            }
            if all(isinstance(value, (int, float)) for value in rates.values()):
                cost = {key: float(value) * 1_000_000 for key, value in rates.items()}
        entry: dict[Any, Any] = {
            K(":id"): model_id,
            K(":name"): model_id,
            K(":reasoning"): self.options.reasoning,
            K(":context-window"): context_window,
            K(":max-tokens"): max_tokens,
            K(":input"): [K(":text"), K(":image")],
        }
        if cost is not None:
            entry[K(":cost")] = {K(f":{key}"): value for key, value in cost.items()}
        return entry

    async def _write_models(
        self, environment: BaseEnvironment, config: dict[str, Any]
    ) -> None:
        content = _edn(config) + "\n"
        await self._upload_config_text(
            environment,
            content=content,
            remote_path=f"{_REMOTE_AGENT_DIR}/models.edn",
            filename="models.edn",
        )

    def _kmet_flags(self, provider: str | None, model_id: str) -> str:
        flags: list[str] = []
        if provider:
            flags.extend(["--provider", shlex.quote(provider)])
        flags.extend(["--model", shlex.quote(model_id)])
        if self.options.reasoning_effort:
            flags.extend(["--thinking", shlex.quote(self.options.reasoning_effort)])
        if self.options.tools:
            flags.extend(["--tools", shlex.quote(self.options.tools)])
        if self.options.no_context_files:
            flags.append("--no-context-files")
        return " ".join(flags) + " "

    @override
    def populate_context_post_run(self, context: AgentContext) -> None:
        session = self._latest_session()
        if session is None:
            self.logger.warning("kmet session file not found; metrics unavailable")
            return
        try:
            header, entries = _read_session(session)
            trajectory, metrics = _trajectory(
                entries,
                self.model_name or "unknown",
                version=self.version() or "unknown",
                session_id=header.get(":id") if header else None,
            )
        except Exception:
            self.logger.exception("failed to convert kmet session %s", session)
            return

        if trajectory is not None:
            path = self.logs_dir / "trajectory.json"
            path.write_text(format_trajectory_json(trajectory.to_json_dict()))
            self.logger.debug("wrote kmet trajectory to %s", path)

        context.n_input_tokens = sum(metric.prompt_tokens or 0 for metric in metrics)
        context.n_output_tokens = sum(metric.completion_tokens or 0 for metric in metrics)
        context.n_cache_tokens = sum(metric.cached_tokens or 0 for metric in metrics)
        costs = [metric.cost_usd for metric in metrics if metric.cost_usd is not None]
        context.cost_usd = sum(costs) if costs else None

    def _latest_session(self) -> Path | None:
        found = sorted(
            (self.logs_dir / "kmet-sessions").rglob("*.ednl"),
            key=lambda path: path.stat().st_mtime_ns,
            reverse=True,
        )
        return found[0] if found else None


# ─── Release resolution (host side) ──────────────────────────────────────────


def _release_version(requested: str | None) -> str:
    if requested and requested not in ("latest", "dev"):
        return requested.removeprefix("v")
    with urllib.request.urlopen(
        "https://api.github.com/repos/kmetia/kmet-agent/releases/latest", timeout=30
    ) as response:
        payload = json.load(response)
    return str(payload["tag_name"]).removeprefix("v")


def _platform_name() -> str:
    # Harbor task containers are Linux, even when the host is macOS or Windows.
    machine = platform.machine().lower()
    if machine in ("x86_64", "amd64"):
        return "linux-amd64"
    if machine in ("aarch64", "arm64"):
        return "linux-aarch64"
    raise RuntimeError(f"unsupported architecture: {machine}")


_CHECKOUT_EXCLUDES = {
    ".git",
    ".jolt",
    ".lsp",
    ".clj-kondo",
    ".cpcache",
    ".m2",
    ".nrepl-port",
    "__pycache__",
    "target",
    "dist",
    "logs",
}


def _archive_checkout(source: Path, target: Path) -> None:
    """Archive a live checkout without build output, VCS, or tool caches."""
    with tarfile.open(target, "w:gz") as archive:
        for root, dirs, files in os.walk(source):
            dirs[:] = sorted(name for name in dirs if name not in _CHECKOUT_EXCLUDES)
            relative = Path(root).relative_to(source)
            if relative != Path("."):
                archive.add(root, arcname=relative.as_posix(), recursive=False)
            for name in sorted(files):
                if name.endswith((".pyc", ".pyo")):
                    continue
                path = Path(root) / name
                archive.add(
                    path,
                    arcname=(relative / name).as_posix(),
                    recursive=False,
                )


def _min_bb_version(checkout: Path) -> str | None:
    try:
        config = kmet_edn.loads((checkout / "bb.edn").read_text())
    except Exception:
        return None
    value = config.get(":min-bb-version") if isinstance(config, dict) else None
    return str(value) if value else None


def _version_tuple(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", version))


def _host_babashka() -> tuple[Path, str] | None:
    host = shutil.which("bb")
    if not host or platform.system().lower() != "linux":
        return None
    try:
        result = subprocess.run(
            [host, "--version"], capture_output=True, text=True, timeout=10
        )
    except OSError:
        return None
    match = re.search(r"(\d+\.\d+\.\d+)", result.stdout or "")
    return (Path(host), match.group(1)) if match else None


def _babashka_binary(checkout: Path, requested: str | None) -> Path:
    host = _host_babashka()
    minimum = _min_bb_version(checkout)
    if requested:
        version = requested.removeprefix("v")
        if host is not None and host[1] == version:
            return host[0]
        return _download_babashka(version)
    if host is not None and (
        minimum is None or _version_tuple(host[1]) >= _version_tuple(minimum)
    ):
        return host[0]
    return _download_babashka(minimum or _DEFAULT_BB_VERSION)


def _download_babashka(version: str) -> Path:
    cache = Path(
        os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")
    ) / "kmet-harbor"
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / f"bb-{version}-{_platform_name()}"
    if target.is_file():
        return target
    name = f"babashka-{version}-{_platform_name()}-static.tar.gz"
    archive_path = cache / name
    if not archive_path.is_file():
        url = (
            "https://github.com/babashka/babashka/releases/download/"
            f"v{version}/{name}"
        )
        temporary = archive_path.with_suffix(".tmp")
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as out:
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
        temporary.replace(archive_path)
    with tarfile.open(archive_path) as archive:
        member = next(
            (item for item in archive.getmembers() if Path(item.name).name == "bb"),
            None,
        )
        if member is None:
            raise RuntimeError(f"no bb executable in {archive_path}")
        file = archive.extractfile(member)
        if file is None:
            raise RuntimeError(f"cannot read bb from {archive_path}")
        temporary = target.with_suffix(".tmp")
        temporary.write_bytes(file.read())
    temporary.chmod(0o755)
    temporary.replace(target)
    return target


def _download_release(version: str) -> Path:
    cache = Path(
        os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")
    ) / "kmet-harbor"
    cache.mkdir(parents=True, exist_ok=True)
    name = f"kmet-{version}-{_platform_name()}.zip"
    target = cache / name
    if target.is_file():
        return target
    url = f"https://github.com/kmetia/kmet-agent/releases/download/v{version}/{name}"
    temporary = target.with_suffix(".tmp")
    with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as out:
        while chunk := response.read(1024 * 1024):
            out.write(chunk)
    temporary.replace(target)
    return target


# ─── models.edn rendering ────────────────────────────────────────────────────


def _edn(value: Any) -> str:
    if isinstance(value, (kmet_edn.Keyword, kmet_edn.Symbol)):
        return str(value)
    if isinstance(value, dict):
        return "{" + " ".join(f"{_edn(key)} {_edn(item)}" for key, item in value.items()) + "}"
    if isinstance(value, list):
        return "[" + " ".join(_edn(item) for item in value) + "]"
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "nil"
    return str(value)


# ─── Session conversion ──────────────────────────────────────────────────────


def _read_session(
    path: Path,
) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    header: dict[str, Any] | None = None
    entries: list[dict[str, Any]] = []
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith(";"):
            continue
        value = kmet_edn.loads(line)
        if not isinstance(value, dict):
            continue
        if value.get(":type") == ":session":
            header = value
        else:
            entries.append(value)
    return header, entries


def _usage(usage: Any) -> Metrics | None:
    if not isinstance(usage, dict):
        return None
    read_details = usage.get(":prompt_tokens_details") or usage.get(
        ":input_tokens_details"
    ) or {}
    cache_read = _int(
        read_details.get(":cached_tokens")
        if isinstance(read_details, dict)
        else None
    )
    cache_read = cache_read or _int(usage.get(":prompt_cache_hit_tokens"))
    cache_read = cache_read or _int(usage.get(":cache_read_input_tokens"))
    cache_write = _int(
        read_details.get(":cache_write_tokens")
        if isinstance(read_details, dict)
        else None
    )
    cache_write = cache_write or _int(usage.get(":cache_creation_input_tokens"))
    prompt = _int(usage.get(":prompt_tokens"))
    input_tokens = _int(usage.get(":input_tokens"))
    # OpenAI Responses and Bedrock report input_tokens with cache already
    # included; Anthropic-style input_tokens excludes it.
    cached_in_input = "input_tokens_details" in usage or "cache_write_input_tokens" in usage
    if not prompt:
        if not input_tokens:
            prompt = 0
        elif cached_in_input:
            prompt = input_tokens
        else:
            prompt = input_tokens + cache_read + cache_write
    completion = _int(usage.get(":completion_tokens")) or _int(
        usage.get(":output_tokens")
    )
    cost = usage.get(":cost")
    cost_usd = None
    if isinstance(cost, dict):
        total = cost.get(":total")
        cost_usd = float(total) if isinstance(total, (int, float)) else None
    elif isinstance(cost, (int, float)):
        cost_usd = float(cost)
    if not any((prompt, completion, cache_read, cache_write)):
        return None
    return Metrics(
        prompt_tokens=prompt or None,
        completion_tokens=completion or None,
        cached_tokens=cache_read or None,
        cost_usd=cost_usd if cost_usd and cost_usd > 0 else None,
    )


def _int(value: Any) -> int:
    return int(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else 0


def _plain(value: Any) -> Any:
    if isinstance(value, kmet_edn.Keyword):
        return str(value).removeprefix(":")
    if isinstance(value, kmet_edn.Symbol):
        return str(value)
    if isinstance(value, dict):
        return {_plain(key): _plain(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_plain(item) for item in value]
    return value


def _trajectory(
    entries: list[dict[str, Any]],
    fallback_model: str,
    *,
    version: str = "unknown",
    session_id: Any = None,
) -> tuple[Trajectory | None, list[Metrics]]:
    steps: list[Step] = []
    call_steps: dict[str, Step] = {}
    metrics: list[Metrics] = []
    model_name = fallback_model

    for entry in entries:
        role = entry.get(":role")
        timestamp = entry.get(":timestamp")
        if role == ":model-change":
            provider = entry.get(":provider")
            model = entry.get(":model")
            if provider and model:
                model_name = f"{_plain(provider)}/{_plain(model)}"
            continue
        if role == ":user":
            message = kmet_edn.text_of(entry.get(":content")) or str(entry.get(":content") or "")
            steps.append(
                Step(
                    step_id=len(steps) + 1,
                    timestamp=timestamp,
                    source="user",
                    message=message.strip(),
                    extra={"_kmet_entry_id": _plain(entry.get(":id"))},
                )
            )
            continue
        if role == ":custom-message":
            message = kmet_edn.text_of(entry.get(":content")) or str(entry.get(":content") or "")
            steps.append(
                Step(
                    step_id=len(steps) + 1,
                    timestamp=timestamp,
                    source="user",
                    message=message.strip(),
                    extra={"kmet_custom_type": _plain(entry.get(":custom-type"))},
                )
            )
            continue
        if role == ":compaction":
            summary = str(entry.get(":summary") or "").strip()
            if not summary:
                continue
            metric = _usage(entry.get(":usage"))
            if metric is not None:
                metrics.append(metric)
            steps.append(
                Step(
                    step_id=len(steps) + 1,
                    timestamp=timestamp,
                    source="agent",
                    model_name=model_name,
                    message=summary,
                    metrics=metric,
                    llm_call_count=1,
                    extra={"kmet_entry_id": _plain(entry.get(":id")), "kmet_compaction": True},
                )
            )
            continue
        if role == ":assistant":
            provider = entry.get(":provider")
            model = entry.get(":model")
            if provider and model:
                model_name = f"{_plain(provider)}/{_plain(model)}"
            text = kmet_edn.text_of(entry.get(":content"))
            thinking = entry.get(":thinking")
            tool_calls: list[ToolCall] = []
            for call in entry.get(":tool-calls") or []:
                if not isinstance(call, dict):
                    continue
                call_id = call.get(":id")
                name = call.get(":name")
                if not isinstance(call_id, str) or not isinstance(name, str):
                    continue
                arguments = _plain(call.get(":arguments") or {})
                if not isinstance(arguments, dict):
                    arguments = {"value": arguments}
                tool_calls.append(
                    ToolCall(
                        tool_call_id=call_id,
                        function_name=name,
                        arguments=arguments,
                    )
                )
            if not text and not thinking and not tool_calls:
                continue
            metric = _usage(entry.get(":usage"))
            if metric is not None:
                metrics.append(metric)
            step = Step(
                step_id=len(steps) + 1,
                timestamp=timestamp,
                source="agent",
                model_name=model_name,
                message=text.strip(),
                reasoning_content=thinking if isinstance(thinking, str) and thinking.strip() else None,
                tool_calls=tool_calls or None,
                observation=Observation(results=[]) if tool_calls else None,
                metrics=metric,
                llm_call_count=1,
                extra={
                    "kmet_entry_id": _plain(entry.get(":id")),
                    "kmet_provider": _plain(provider),
                    "kmet_model": _plain(model),
                },
            )
            steps.append(step)
            for call in tool_calls:
                call_steps[call.tool_call_id] = step
            continue
        if role == ":tool":
            content = entry.get(":content")
            call_id = None
            text = ""
            if isinstance(content, list):
                for part in content:
                    if not isinstance(part, dict):
                        continue
                    if part.get(":type") == ":tool_result":
                        call_id = part.get(":tool_use_id")
                        text = str(part.get(":content") or "")
                        break
            if not call_id:
                call_id = entry.get(":tool-call-id")
            if not isinstance(call_id, str):
                continue
            step = call_steps.get(call_id)
            if step is None:
                continue
            if step.observation is None:
                step.observation = Observation(results=[])
            step.observation.results.append(
                ObservationResult(
                    source_call_id=call_id,
                    content=text,
                    extra={
                        "kmet_tool_name": _plain(entry.get(":tool-name")),
                        "is_error": bool(entry.get(":is-error", False)),
                    },
                )
            )

    if not steps:
        return None, metrics
    trajectory = Trajectory(
        schema_version="ATIF-v1.7",
        session_id=session_id,
        agent=Agent(name="kmet", version=version, model_name=model_name),
        steps=steps,
        final_metrics=FinalMetrics(
            total_prompt_tokens=sum(metric.prompt_tokens or 0 for metric in metrics),
            total_completion_tokens=sum(
                metric.completion_tokens or 0 for metric in metrics
            ),
            total_cached_tokens=sum(metric.cached_tokens or 0 for metric in metrics),
            total_cost_usd=(
                sum(metric.cost_usd or 0 for metric in metrics)
                if any(metric.cost_usd for metric in metrics)
                else None
            ),
            total_steps=len(steps),
        ),
    )
    return trajectory, metrics


# ─── LiteLLM metadata ────────────────────────────────────────────────────────


def _litellm_info(model_name: str | None) -> dict[str, Any]:
    if not model_name:
        return {}
    try:
        from litellm import get_model_info

        info = get_model_info(model_name)
        return info if isinstance(info, dict) else {}
    except Exception:
        return {}
