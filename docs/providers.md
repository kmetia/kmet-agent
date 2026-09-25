# Providers and authentication

kmet supports many hosted LLM providers. Use `--list-models` to see the models
available with the credentials in the current environment or `auth.edn`:

```sh
bb run --list-models
bb run --provider <provider> --model <model>
```

The default `opencode-go` provider reads `OPENCODE_API_KEY`:

```sh
export OPENCODE_API_KEY='your-api-key'
bb run --list-models
```

Credentials entered with `/login` are stored in
`~/.kmet/agent/auth.edn` (or the agent directory selected by
`KMET_CODING_AGENT_DIR`). Environment variables are never written to the
settings file. `/logout [provider]` removes a stored credential.

## API-key environment variables

Set the variable required by the provider you choose. The commonly used names
are:

- `OPENCODE_API_KEY` (opencode-go/opencode), `DEEPSEEK_API_KEY`,
  `OPENAI_API_KEY` (openai), `XAI_API_KEY` (xai),
  `COPILOT_GITHUB_TOKEN`,
  `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_OAUTH_TOKEN` / `ANTHROPIC_API_KEY`
  (anthropic), `GEMINI_API_KEY` (google), `GROQ_API_KEY` (groq),
  `CEREBRAS_API_KEY` (cerebras), `HF_TOKEN` (huggingface),
  `MOONSHOT_API_KEY` (moonshotai), `MISTRAL_API_KEY` (mistral),
  `OPENROUTER_API_KEY` (openrouter), and `NVIDIA_API_KEY` (nvidia).
- `AZURE_OPENAI_API_KEY` for Azure OpenAI Responses, with the base URL and
  deployment selected by `AZURE_OPENAI_BASE_URL`,
  `AZURE_OPENAI_RESOURCE_NAME`, and `AZURE_OPENAI_DEPLOYMENT_NAME_MAP`.
- `XIAOMI_API_KEY` plus the applicable `XIAOMI_TOKEN_PLAN_*_API_KEY` values;
  `QWEN_TOKEN_PLAN_API_KEY` (also `_CN_`); `MINIMAX_API_KEY` or
  `MINIMAX_CN_API_KEY`; `ZAI_API_KEY` or `ZAI_CODING_CN_API_KEY`;
  `TOGETHER_API_KEY`; `BASETEN_API_KEY`; `KIMI_API_KEY`;
  `CMD_API_KEY`; and `ANT_LING_API_KEY`.
- `FIREWORKS_API_KEY` (fireworks), `AI_GATEWAY_API_KEY`
  (vercel-ai-gateway), and `CLOUDFLARE_API_KEY` with
  `CLOUDFLARE_ACCOUNT_ID` / `CLOUDFLARE_GATEWAY_ID` for Cloudflare Workers AI
  or AI Gateway.
- `GOOGLE_CLOUD_API_KEY` (google-vertex), or Application Default Credentials
  through `GOOGLE_APPLICATION_CREDENTIALS` plus the project and location
  settings.

Amazon Bedrock uses its ambient AWS credential sources, such as
`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, `AWS_PROFILE`,
`AWS_BEARER_TOKEN_BEDROCK`, and ECS/IRSA variables. Provider-specific
variables are the source of truth; use `--list-models` to confirm the
resulting account and available models.

## OAuth and subscription logins

`/login <provider>` starts a supported browser or device-code flow. Current
flows include:

- GitHub Copilot device-code sign-in.
- OpenAI Codex through a browser PKCE loopback or device code.
- Anthropic Claude Pro/Max through a browser PKCE loopback.
- OpenRouter through a browser PKCE loopback (with a permanent key).

If a provider offers no OAuth flow, use its API-key environment variable or
`/login` credential entry.

## Custom providers and models

Models, base URLs, and defaults are registry data. Put custom provider
definitions and model overrides in `~/.kmet/agent/models.edn` rather than in
a settings `:providers` map. See the [configuration guide](configuration.md)
for the settings shape and the project agent directory.

OpenAI-completions compatibility (thinking format, max-token field, and
reasoning round-trip) is detected from the provider id and base URL. An
explicit `:compat` entry in `models.edn` overrides the detected defaults.

## Model catalogs

The built-in catalog is stored in `src/kmet/ai/model_data/*.edn` and covers
providers including opencode-go, deepseek, anthropic, google, groq, cerebras,
openrouter, nvidia, moonshotai, qwen-token-plan, minimax, fireworks,
vercel-ai-gateway, zai, together, baseten, kimi-coding, cloudflare, mistral,
google-vertex, and amazon-bedrock. The catalogs are generated with
`bb generate-models`.

A user-level cache can be refreshed without modifying the repository:

```sh
kmet --generate-models
```

The refresh is written to `~/.kmet/agent/models-cache/` and takes precedence
over the bundled catalog when it is strictly newer. Rerunning it without an
upstream change rewrites nothing. The image-model catalog is separate and is
refreshed with `bb generate-image-models`.
