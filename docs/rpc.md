# RPC mode

`--mode rpc` runs kmet headless and long-lived: commands arrive as EDN maps
on stdin, protocol records go out as EDN maps on stdout. It is the transport
for editor plugins, test harnesses, and any program that wants streaming
access to a session without a terminal — and the first front end to answer
extension dialogs asynchronously.

```sh
kmet --mode rpc                          # packaged binary
bb start --mode rpc                      # source checkout (babashka)
jolt start --mode rpc                    # ...or Jolt
```

`--profile server` serves this same protocol over a socket, one agent per
connection. The connection rules — the bearer token, the `:hello`/`:auth`/
`:ready` handshake, the session cap, the per-wire queue and its `:stream-gap`,
and the world a connection brings — are in [server mode](serve.md); everything
below is the record vocabulary the two share.

A mode run opens a fresh session. The session-lifecycle flags are refused by
name (`--continue needs a mode that opens a session`, exit 1) rather than
silently starting the fresh session they exist to prevent; a client that wants
a stored session asks the protocol (`switch-session`) or resumes by id once
that landing lands. `--model`, `--provider`, `--session-dir`,
`--system-prompt`, and `--append-system-prompt` all apply.

## Transport

- One record per line, LF-terminated, in both directions. Every line is one
  EDN map; JSON is not used.
- stdout carries protocol records only. The child's diagnostics (warnings, a
  startup failure) go to stderr.
- A command is any map with a `:type`. An optional string `:id` is echoed by
  the matching response; correlate by `:id`, never by order — an active run
  interleaves events with responses.
- A malformed line answers
  `{:type :response :command :parse :success false :error "..."}` with no
  `:id` (there is nothing to correlate it with) and the loop keeps serving.
- An unknown `:type` and a missing `:type` fail as responses, never as a
  crashed process.

```clojure
;; stdin
{:type :get-state :id "req-1"}

;; stdout — the response
{:type :response :id "req-1" :command :get-state :success true :data {...}}
;; stdout — a run's events arrive in the same stream
{:type :message-update :delta {:type :text :content "hi"}}
{:type :agent-settled :outcome :completed}
```

Events are the print-mode wire projection (`kmet.app.wire/wire-event`):
the same `{:type ...}` vocabulary as the session stream, with the cumulative
`:message-update` snapshot dropped and error payloads stringified. Streaming
consumers watch `:message-update` deltas and stop at `:agent-settled`, whose
`:outcome` is `:completed`, `:error`, or `:aborted`.

The stream carries the **public** events: the loop's, and the app's session
lifecycle and UI events. The extension-only hooks (`:context`,
`:before-provider-*`, `:after-provider-response`, `:resources-discover`, the
`:session-before-*` pair) never cross: they carry request payloads, headers, or
a control object, and only an in-process extension subscribes them.

## Discipline

- **Accepted is not completed.** A `prompt` response reports the disposition
  (`:started`, `:queued`, or `:handled`) as soon as the engine accepts the
  message; the client waits for `:agent-settled` to know how it ended. A
  `:handled` prompt (a slash command such as `/name`) starts no run, so no
  settle follows it.
- **A command failure is a response, a run failure is an event.** A rejected
  command answers `:success false :error "..."`; a crashing run settles with
  `outcome :error` and its error reaches the stream (and the session).
- **Commands are served serially.** A command that settles a run
  (`new-session`, `switch-session`) delays the next line until it has;
  `abort` does not — it answers a read and the client waits for
  `:agent-settled`. Run work streams on its own thread.
- **stdin EOF requests shutdown.** The engine cancels an in-flight run,
  streams its terminal `:agent-settled` (`:aborted`), then disposes and
  exits 0. The session file is appended as entries stream, so an abrupt exit
  loses nothing. A `:status` or `:queue-update` the cancel path writes can
  land after the settle (they are emitted on the cancelling thread while the
  run settles on its own): the settle, not stream closure, is the run's end.
- No SIGTERM/SIGHUP handlers are installed yet; signal an orderly shutdown
  with stdin EOF.

## Commands

### Prompt and queues

| Command | Fields | Response data |
|---|---|---|
| `prompt` | `:message` (required), `:images`, `:streaming-behavior` (`:steer`/`:follow-up`) | `{:disposition :started|:queued|:handled}` |
| `steer` | `:message` | `{:disposition :queued|:started}` |
| `follow-up` | `:message` | `{:disposition :queued|:started}` |
| `abort` | — | `{:idle true|false}` (a read, not a wait) |
| `clear-queue` | — | `{:steering [...] :follow-up [...]}` (the dropped texts) |

While a run is active a `prompt` must name its `:streaming-behavior` (`:steer`
or `:follow-up`); pi errors without one, so an unqualified second prompt
fails instead of silently queueing. `:images` ride the started path into the
run; the steer/follow-up queues carry no image blocks yet and reject them.

A `prompt` whose text begins with `/` first dispatches through the command
registry (see [Portable slash commands](#portable-slash-commands)); only when
no command matches does the text reach the model.

A `steer` or `follow-up` on an **idle** agent starts a run in kmet2 (the
handle's one start-or-queue delivery rule) and answers `:started`; while a run
is live they queue and answer `:queued`. kmet1 queued in both cases and waited
for a run, so a client that depended on that should send them only around a
live run.

An `abort` while a run is in flight drops the steering/follow-up queues (kmet
persists the drop as an `:aborted` `:queue-update`). Send `clear-queue` first
if the queued texts matter. A manual compaction is cancelled as well — it is
work nobody waits for once the client asks to stop — while the answer's
`:idle` speaks for the turn. The answer is a read of the turn flag, not a wait:
the run releases that flag in its own settle path, so aborting a live run
reports whatever it reads at that moment — `:idle false` while the run unwinds,
`:idle true` when the cancel settled it first — and the client waits for
`:agent-settled`.

### Session

| Command | Fields | Response data |
|---|---|---|
| `new-session` | — | `{:cancelled bool :session-id ... :session-file ... :was-running bool}` |
| `get-state` | — | model, provider, thinking level, queue modes, `:turn-running?`, `:compacting?`, `:last-outcome`, pending count, session summary (`:message-count` counts message entries, as pi's does) |
| `get-messages` | — | `{:messages [...]}` (the live model context) |
| `get-last-assistant-text` | — | `{:text ...}` |
| `get-session-stats` | — | token/tool totals (the `/session` numbers) |
| `export-html` | `:output-path` (optional) | `{:path ...}` |
| `switch-session` | `:session-path` (required) | `{:cancelled bool}` |
| `fork` | `:entry-id` (required) | `{:cancelled bool :text ...}` for the client editor |
| `clone` | — | `{:cancelled bool}` |
| `get-entries` | `:since` (optional entry id) | `{:entries [...] :leaf-id ...}` — the entries, or those after `:since` |
| `get-tree` | — | `{:tree [...] :leaf-id ...}` |
| `set-session-name` | `:name` (required) | — |

Session switches emit their lifecycle events on the same stream, so a
subscribed client sees the swap happen.

### Model, thinking, and queue modes

| Command | Fields | Response data |
|---|---|---|
| `set-model` | `:provider`, `:model-id` (required) | the switched model record |
| `cycle-model` | — | `{:model ... :thinking-level ... :scoped? ...}` or nil |
| `get-available-models` | — | `{:models [...]}` (authenticated models) |
| `set-thinking-level` | `:level` | — |
| `cycle-thinking-level` | — | `{:level ...}` or nil |
| `get-available-thinking-levels` | — | `{:levels [...]}` (the model's set) |
| `set-steering-mode` | `:mode` (`:all`/`:one-at-a-time`) | — |
| `set-follow-up-mode` | `:mode` (`:all`/`:one-at-a-time`) | — |

### Compaction

| Command | Fields | Response data |
|---|---|---|
| `compact` | `:custom-instructions` (optional), `:keep-user-turns` (optional non-negative integer) | `{:accepted true}`, plus `:scheduled true` when deferred |
| `set-auto-compaction` | `:enabled` (boolean) | — |

`compact` launches on a future and answers `:accepted` immediately — the
outcome arrives as `:compaction-end` / `:session-compact(-failed)` events
(kmet's manual compaction stays escape-cancellable). `:keep-user-turns`
overrides the cut for that compaction (0 compacts everything). With
`:compactor :vcc`, free-form `:custom-instructions` are rejected before the
compaction runs; use `:keep-user-turns` or switch to `:llm`. A run in flight defers
the compaction instead of aborting it: the request is queued and starts when
the run settles, and the response carries `:scheduled true`. `cycle-model` and
`cycle-thinking-level` answer nil data when the model offers fewer than two
choices.

### Commands registry

| Command | Fields | Response data |
|---|---|---|
| `get-commands` | — | `{:commands [{:name :source :description? :argument-hint?}]}` |

`get-commands` lists the registry filtered to this front end — the portable
builtins (`:source :builtin`), extension commands (`:extension`), prompt
templates (`:prompt`), and skills (`:skill`). Interactive-only commands are
filtered out.

## Portable slash commands

A `prompt` whose trimmed text is `/<name> ...` dispatches through the same
registry the TUI uses, with the command context
`{:runtime engine :ui rpc-command-ui :frontend :rpc}`:

- `/continue`, `/name`, `/export`, `/compact`, `/units` — the engine-portable builtins
- extension commands registered for any front end

The response is `{:disposition :handled}`; nothing reaches the model. Unknown
slash text stays a normal prompt. `get-commands` reports this same filtered
view, so a client can build a command palette without guessing.

## Extension UI

The RPC port binds `{:mode :rpc :has-ui true}`: an extension's `ctx.mode` is
`:rpc` and `ctx.has-ui` is true. Dialogs and output channels cross the wire as
`:ui-request` records carrying their own `:id`.

### Dialogs (request/response)

kmet's extension ABI (`kmet.extension`) carries no host-built dialogs —
`select`, `confirm`, `input` and `editor` are not capabilities a kmet extension
can call — so no `:ui-request` of those kinds is produced, and `respond`
resolves nothing. It is still accepted, silently, because the id is what
correlates an answer (pi writes no response record for a valid one); a
`respond` without a string `:id` fails as a response.

The `:request`/`:response` pair that *is* live is the **server** row's
approval route ([server mode](serve.md) has the handshake and the connection
rules): a run whose policy asks sends the question on the connection
that owns its agent, and the client answers it on the same records.

```clojure
;; stdout, on the server
{:type :request :id "req-…" :method :ask :tool-name "bash" :args {...}}

;; stdin — answer it
{:type :response :id "req-…" :data {:decision :allow}}
;; :data {:decision :deny :reason "…"} declines; a connection that goes away
;; answers its open questions with a deny
```

A client that leaves a question unanswered blocks the run that asked it (that
is what a question means), so a UI that cannot ask should keep the bundle's
default approver. `:custom` has no client surface (pi limitation) and keeps
the headless default.

### Output channels (fire-and-forget)

| `:method` | Fields | Meaning |
|---|---|---|
| `:notify` | `:message`, `:notify-type?` | toast/warning (`ui.notify`) |
| `:set-status` | `:status-key`, `:status-text` | footer status channel |
| `:set-widget` | `:widget-key`, `:widget-lines`, `:widget-placement?` | string lines only; widget hiccup stays TUI-only |
| `:set-title` | `:title` | window/terminal title |
| `:chat-info` | `:label?`, `:content` | informational chat entry |
| `:set-editor-text` | `:text` | put text in the client's editor |
| `:paste-to-editor` | `:text` | as `:set-editor-text` |

TUI-only capabilities without a wire form stay inert (or degrade to the
headless default) and are listed in
[`modules/app/src/kmet/extension.md`](../modules/app/src/kmet/extension.md); the client can ignore
fire-and-forget records it does not render.

## Compatibility with pi

The command names and record shapes follow pi's RPC mode. Supported:
`prompt`, `steer`, `follow_up`, `abort`, `clear_queue`, `new_session`,
`get_state`, `get_messages`, `get_last_assistant_text`, `get_session_stats`,
`export_html`, `switch_session`, `fork`, `clone`, `get_entries`, `get_tree`,
`set_session_name`, `set_model`, `cycle_model`, `get_available_models`,
`set_thinking_level`, `cycle_thinking_level`,
`get_available_thinking_levels`, `set_steering_mode`, `set_follow_up_mode`,
`compact`, `set_auto_compaction`, `get_commands`, `respond` — the whole
table.

Deferred (no kmet equivalent yet): `bash`, `abort_bash`, `set_auto_retry`,
`abort_retry` — kmet's bash path is the interactive `!` flow, and the retry
knobs have no live setter.

Known differences to account for in a client:

- A running `abort` drops the steering/follow-up queues (kmet's cancel
  semantics); send `clear_queue` first to capture the texts.
- `steer`/`follow_up` on an idle agent start a run and answer `:started` (see
  § Prompt and queues).
- `startup` session flags are refused rather than honored (see § Transport);
  a stored session is opened with `switch_session`.
- Dialog methods are not produced (kmet's extension ABI has no such
  capabilities); `respond` is accepted and resolves nothing.
- `steer`/`follow_up` do not run extension input hooks (kmet's hooks live in
  the submit path) and reject `:images` until the queues carry image blocks.
- `get_state`'s model is the id plus provider, not a full model object;
  `get_available_models` fills that in.
- Commands are served serially: `new_session`/`switch_session` wait for the
  run they settle, while `abort` answers immediately and reports `:idle`.
- `compact` answers `:accepted` and reports the outcome via events rather
  than awaiting a `CompactionResult` in the response.
- No SIGTERM/SIGHUP handlers; end with stdin EOF.
