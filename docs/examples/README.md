# Examples

Copyable EDN examples for kmet. Files in this directory are documentation
only; kmet does not discover or load them automatically.

## Settings

[`settings.edn`](settings.edn) is a current, copyable starting point for
kmet's main settings. It shows the default provider/model, automatic theme
selection, compaction, retry and loop-guard settings, HTTP transport, shell
options, terminal image settings, and prompt overrides.

### Install a settings example

For global settings:

```sh
mkdir -p ~/.kmet/agent
cp docs/examples/settings.edn ~/.kmet/agent/settings.edn
```

For settings that apply only to the current project:

```sh
mkdir -p .kmet
cp docs/examples/settings.edn .kmet/settings.edn
```

If a settings file already exists, merge the desired keys into it rather than
overwriting it. Settings are deep-merged in this order:

1. built-in defaults
2. `~/.kmet/agent/settings.edn`
3. `.kmet/settings.edn`

A relative `:session-dir` is resolved against the settings file's scope. The
global agent directory can be changed with `KMET_CODING_AGENT_DIR`.

### Settings highlights

| Setting | Meaning |
|---------|---------|
| `:provider`, `:model` | Default provider and model. Custom provider definitions belong in `~/.kmet/agent/models.edn`, not in a `:providers` map. |
| `:theme` | A theme name, or `"light/dark"` to follow the terminal background. |
| `:thinking` | Startup thinking level: `off`, `minimal`, `low`, `medium`, `high`, `xhigh`, or `max`. |
| `:auto-compact` | Enables proactive context compaction. |
| `:compact-token-threshold` | Optional fixed estimated-token threshold; `nil` uses the model's context window and `:compact-reserve-tokens`. |
| `:compact-reserve-tokens`, `:keep-recent-tokens` | Context reserved for the response and recent context retained after compaction. |
| `:retry` | Agent-level transient-error retry policy. |
| `:loop-guard`, `:thinking-loop-guard-enabled` | Circuit breakers for repeated tool calls and repeated thinking output. |
| `:http-idle-timeout-ms`, `:http-total-timeout-ms` | Provider-stream idle and whole-request deadlines in milliseconds; `0` disables a deadline. |
| `:http-transport` | `:platform` uses the built-in HTTP client where possible and curl for proxy cases; `:curl` sends all requests through curl. |
| `:terminal`, `:images` | Inline-image display, image width, clear-on-shrink, and whether images are sent to providers. |
| `:shell-path`, `:shell-command-prefix` | Custom bash executable and a line prepended to every bash command. |
| `:system-prompt`, `:append-system-prompt` | Replace or extend the system prompt. A value naming an existing file is read as that file's contents. |

See the repository [configuration reference](../../README.md#configuration)
for discovery rules, provider model catalogs, resource paths, and the full
behavior of these options.

API keys do not belong in `settings.edn`. Use the provider environment
variables listed in the main [README](../../README.md#prerequisites) or store
credentials through `/login` in `~/.kmet/agent/auth.edn`.

## Themes

[`themes/dark.edn`](themes/dark.edn) and
[`themes/light.edn`](themes/light.edn) are complete, valid examples of kmet's
current EDN theme schema. They mirror the built-in palettes and are useful as
starting points for a custom theme.

### Install a custom theme

For a global custom theme:

```sh
mkdir -p ~/.kmet/agent/themes
cp docs/examples/themes/dark.edn ~/.kmet/agent/themes/my-theme.edn
```

Then change `:name` in the copied file to `"my-theme"` and select it with
`/theme my-theme` or the theme row in `/settings`. Keep the filename equal to
`:name` so the live file watcher can find it. Saving changes to the selected
global custom theme reloads it automatically.

Project themes live in `.kmet/themes/`:

```sh
mkdir -p .kmet/themes
cp docs/examples/themes/light.edn .kmet/themes/my-light.edn
```

Change `:name` to `"my-light"` before selecting it. An automatic theme pair can
use two custom names, for example `:theme "my-light/my-dark"`.

### Theme format

A current theme uses three top-level keys (the shape below is abbreviated):

```clojure
{:name "my-theme"
 :vars {"primary" "#00aaff"
        "muted" 242}
 :colors {"accent" "primary"
          "muted" "muted"
          ;; all other required color tokens ...
          }}
```

- `:name` is the selector name; when omitted, the loader uses the filename
  basename. It cannot contain `/` because that character separates the light
  and dark names in an automatic theme setting.
- `:vars` is an optional map of reusable values.
- `:colors` maps every theme token to a color value. The examples use pi's
  camelCase string keys; EDN kebab-case keyword keys are also accepted.
- All 54 current tokens are included in the examples. `thinkingMax` is the
  only optional token and falls back to `thinkingXhigh`; the other 53 are
  required when a theme file is loaded.

Color values may be:

| Value | Meaning |
|-------|---------|
| `"#rrggbb"` | 24-bit RGB color, approximated on terminals without truecolor support |
| `0`–`255` | xterm 256-color palette index |
| `"primary"` | Name of an entry in `:vars` |
| `""` | The terminal's default foreground or background |

A bare name such as `"red"` is a variable reference, not an ANSI color name.
Define it in `:vars` or use a hex or numeric value. Invalid EDN, a `:name`
containing `/`, or a missing required token is rejected; startup keeps the
built-in fallback, and live reload keeps the last valid theme.

The current settings shape no longer uses the retired `:providers` map,
`:compact-threshold`, or `:max-session-entries` keys from older examples.
Model/provider selection is top-level, and compaction limits are token counts
as shown in `settings.edn`.
