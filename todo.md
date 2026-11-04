# Todo

Three tracks. The order below is the order to take them; only the two tool
tracks are ordered against each other, because they touch the same selection
functions and share one ABI document. Landed work — the `github-copilot`
anthropic auth, the prompt-caching parity pass, the around tool hook, the
remaining openai-compat keys and **P5** (tool exposure, the declared/callable
split, the shared ranker and `tool_search`) plus **P5.5** (the declared order
and the one promotable derivation) — is out of this file; it lives in git
history. The context and token tracks are dropped too — see § Deferred.

| track | what it fixes | status |
|---|---|---|
| **Tool exposure and declared sets** (P5–P6) | a tool-set change re-sends the whole array and is recorded nowhere | **P5 + P5.5 landed**; **P6 → S** |
| **Tools: cross-session selection** (S) | `/tools` writes only to the session branch and `:default-tools` reaches built-ins only, so "extension tool X stays off in this repo" has no home | plan; **after** the exposure track |
| Persistent eval and eval sources | `run_code` throws its context away per call, so a helper the model writes is re-pasted in full on every turn; extensions can contribute tools but not a definition the model can call from code | decisions closed (E1–E16); independent |

Exposure (P5 + P5.5 → P6) → S. That order is a decision about where the
effort goes, not a dependency: P5 pays on its own — every schema not sent is a
win, and no provider has to honour anything. Eval lands whenever; it touches
neither a wire nor the tool registry.

## Tool exposure and declared sets

**P5 landed** (`3d828162`, `a0bd12ec`, `fb09f804`, `dd0b6ddd`), shipped with
three decisions that differ from the plan below where the code disagreed with
it; they are recorded here because P6 builds on them:

- The scorer is `kmet.libs.ranking`, not a function in
  `kmet.app.tools.search`. An extension context may only require the shared
  layers (`kmet.extension`, `kmet.srce.core`, `kmet.tui.*`, `kmet.libs.*`,
  `kmet.reakt.*`, `kmet.hikap.*` — `kmet.app.extensions.context`), and the
  mcp-adapter is sandboxed, so an app namespace could not be shared with it;
  the lib is also where a generic ranker belongs.
- A promotion is recorded as a `:custom` session entry of type `:tool-load`
  (`{:names […]}`), not a new session role: `append-custom-entry!` and
  `get-custom-entries` already did exactly that, invisibly to the wire.
- `tool_search` reaches the run through one dynamic var (`search/*run*`,
  `{:declared f :load! f}`) bound beside `run_code`'s, because a tool body has
  no other path to the agent's session.

The lookup table is `scripts/live_tool_exposure_check.bb`: `registry/*` and
`loop.state/declared-tools-for` (the derivation the wire list really comes
from) drive three live `kmet.ai.llm/send-message` rounds on the
`:anthropic-messages` wire, and each request's payload is captured with
`:on-payload`, so the check reads the tools array Anthropic received — the
plan's "`-message` logging on" is not needed, and nothing has to be parsed out
of a log file.

Run on 2026-11-04, `claude-haiku-4-5` family, on both anthropic-wire
credentials this machine holds — first `:commandcode`, then `:github-copilot`
once that credential authenticated here (leftover 1). The three rounds produce
the same numbers on either:

| round | tools declared | cache_creation | cache_read |
|---|---|---|---|
| 1. deferred source registered, nothing loaded | 3 (`probe_one`, `probe_two`, `tool_search`) | 7,670 | 0 |
| 2. same prefix, same array | 3, identical | 14 | 7,670 |
| 3. one tool promoted (`.kmet` load recorded) | 4 (`cat_alpha` + the three) | 7,755 | 0 |

What that shows, and what it leaves: a deferred source contributes **no**
schema before a load (the two deferred names are absent from round 1's array)
and `tool_search` is declared because the tool exists to find (gate 1); the
tools array sits *inside* the cached prefix (round 2 reads all 7,670 written
tokens), which is the measured reason a tool change is expensive; and a
promotion re-sends the array and pays a full prefix miss (round 3: read 0,
re-write 7,755) — the cost P6 exists to remove. Round 2 also proves the
declaration is byte-stable when nothing changed (gate 3 for the setup the
rounds ran in).

The composed half is `scripts/live_tool_exposure_composed_check.bb` (leftover
2): it boots `--profile print` with the same probe installed in a scratch agent
dir and mounted as the composition row `:ext/p5_probe`, and reads each
request's tools array from the extension's own `:before-provider-request`
listener. Run on 2026-11-04 against `:github-copilot`/`claude-haiku-4.5`: round
1 declared 17 tools with both deferred names absent and `tool_search` present,
the model's `tool_search` call promoted `cat_alpha` and `cat_beta` into round 2
(19 tools, every round-1 name still declared), the model called `cat_alpha`
("cat_alpha ok"), and round 3's array was identical to round 2's item for item.
The mount rule cost the first attempts: a file in a discovery root is
discovered, not mounted (`docs/extensions.md` § Where extensions live and what
loads them), which is why the probe is both installed *and* patched in as a row.

### Leftovers from P5

1. **The github-copilot run itself — closed 2026-11-04.** The landing
   fixture was replaced by a real credential on this machine (a `ghu_…` refresh
   token with a `tid=…` access token, ten hours to expiry), so
   `bb scripts/live_tool_exposure_check.bb` now runs its default
   `github-copilot`/`claude-haiku-4.5` invocation here and produced the three
   rows above unchanged. No substitute is needed.
2. **A composed-app run of the same check — closed 2026-11-04** by
   `scripts/live_tool_exposure_composed_check.bb`. The other script builds its
   tool table with `register-tool!` / `register-tool-source!` rather than
   booting the composition, so it proves the derivation and the wire, not the
   row mount; this one installs the probe in a scratch agent dir, mounts it as
   `:ext/p5_probe` through `--patch`, runs `--profile print`, and reads the
   arrays from the extension's listener. It passes, and it measured the
   promotion's placement inside the array (see § P5.5).
3. **The prompt's Available-tools list for a promoted tool — closed with
   P5.5.** The leading system prompt is never rebuilt for a promotion (by
   design), so a promoted tool never appears in that list — it appears in the
   appended source listing and in the declarations. One paragraph now says so
   in `docs/configuration.md` § Tools and one sentence in
   `modules/app/src/kmet/extension.md` § Tool sources; S5 keeps the paragraph
   when it rewrites the Tools section. The composed run shows the other side of
   it: asked to "Say exactly: mount-test", the model answered from the
   appended listing — "I see you've mentioned '1 deferred tool source'" —
   because that listing was the only place the source existed.

The listing is bounded (`session/context.clj`): a description elides past 250
characters, and past 4096 characters the last sources drop into one `… N more
sources` line — pi's shape for `mcp_servers`, so an MCP `:deferred` source per
server cannot grow the message without limit. `tool_search`'s description names
the tool inline, as pi's does.

### P5.5 — the declared order (landed)

The order P6's acceptance criterion is stated in. An **unchanged** set
was already byte-stable — round 2 above measured it, because hash order is
deterministic for one key set — so this was not a live cache bug. It is
that a *change* moves the entries it does not touch: `get-all-tools`
merges a deferred source's tools *first*, `registry/select-tools` returns a
map, and past array-map size the wire order is hash order (the composed
run put two promoted names at positions 2 and 17 of 19).

Two changes land here; P6 builds on both:

1. **One wire order.** `state/declared-tools-for` now returns a name-sorted
   map (`(into (sorted-map) …)`) instead of a hash map. `active-tools`'
   `vals` — the once-per-request capture `loop.clj:612` reads and threads —
   is therefore name-sorted, and so are the prompt options' tool list and
   `set-active-tools!`'s rebuild, which read the same derivation. Sorting at
   the derivation is what makes the order survive: a sort inside
   `select-tools` would be collapsed by `declared-tools-for`'s `into`, and a
   sort in `active-tools` alone would leave the prompt's list in hash order.
   A replay recomputes the wire order from the recorded names, so P6 step 1
   records no order of its own. Costs one one-time reordering against
   today's wire, nothing else.
2. **One answer to "what can search reach".** `registry/promotable-tools`
   (`[all contributed {:enabled :exclude}]`) is the selection over the
   `:deferred` table tools and the contributed `:sandbox` catalog, and it is
   read by both the discovery gate (`declared-record`'s `deferred?` is gone)
   and `tool_search`'s candidate list (`search.clj`). The tool receives the
   run's selection through `*run*`'s new `:selection` key, bound by the loop
   to `state/tool-selection` — the same `selection-opts` the declared surface
   uses. A deferred tool the selection excludes declares no `tool_search`; a
   sandbox source alone declares it; and a search cannot promise a tool the
   wire would not declare. Two decisions taken while landing:
   - **The gate ignores the loaded set.** It asks whether the catalog can be
     promoted, not whether anything is still unloaded, so loading the last
     deferred tool leaves `tool_search` declared — a further search returns
     "No matching tools found", which is honest — and a promotion is a pure
     addition, one direction for P6's delta.
   - **A contributed name the table holds is not promotable.** `from-sandbox`
     now reads the shared map instead of the raw catalog, so a hand-crafted
     `:tool-load` naming a shadowed contribution no longer declares the
     phantom; a search never loaded one anyway.

One interaction S inherits: the bundled `/tools` extension persists a
*snapshot* of the names that were declared when it last toggled, so a restored
selection can exclude every promotable tool. The gate then drops `tool_search`
for that run (nothing a search could reach is selected) — the loss S1's orphan
warn exists to report and S3's delta selection removes. Before this change the
same snapshot let a search load a deferred tool that the wire would then not
declare, which was worse.

Tests in `modules/app/test/kmet/app/test_tool_exposure.clj`:
`test-the-declared-set-is-name-sorted` (12 tools, past array-map size:
sorted, stable across two resolutions, and an addition leaves every other
entry in place),
`test-a-deferred-tool-the-selection-excludes-declares-no-search` and
`test-a-contributed-sandbox-tool-can-justify-the-search`.
`modules/app/test/kmet/app/test_tool_search.clj` adds
`test-the-selection-narrows-the-candidates` (`with-run` now binds
`:selection`). The S1 warn is not here — it lands where the selection is
resolved (`state/tool-selection` runs per request, so the warning is emitted
once at `loop.driver`), because `:enabled-tools` does not exist yet.

`session/schema.clj`'s `Entry` union stays for P6 step 1: `:custom-message`
joins with `:tool-change` — same file, same test.

Done when `select-tools`' map is unchanged but one table always produces one
wire order, a tool nothing can promote declares nothing, and no request
carries a `tool_search` it cannot use. Gates: `bb test-changed`,
`bb lint-changed`, `bb format-check-changed` — all green.

### P6 — mid-conversation tool changes without a full re-prefill

Depends on P5 and P5.5: the promotions are what make a mid-session tool change
ordinary rather than a deliberate user action, both items share the session
record, and P5.5's name-sorted wire is the order the delta criterion is stated
in (a recorded declaration list replays to the same array).
Two reasons, one about the cache and one about fidelity:

- **Cache.** Tools sit at the front of the prefix (Anthropic caches
  `tools → system → messages`), so *any* change to the declared set invalidates
  the **whole** prompt, not just the tail.
- **Fidelity.** kmet computes the tool list per request from the live registry
  and the live selection (`state/active-tools`, `state.clj:481-491`) and records
  neither, so a resumed session can advertise a different set than the run it
  resumes — silently. pi records the change in the transcript, so `/tree`,
  resume and fork replay what was actually sent.

Files: `session/schema.clj`, `session/context.clj`,
`ai/api/anthropic_messages.clj`, `ai/api/openai_responses.clj`,
`ai/api/openai_completions.clj`, `ai/model_gen.clj` + `ai/edn_writer.clj`,
`loop/state.clj`, `loop/driver.clj`.

1. **Record the advertised set as session state, declarations included.** A
   `:tool-change` role `{:role :tool-change :added [declared] :removed [names]}`,
   where a `declared` item is the wire declaration
   `{:name :description :parameters}` — pi's `SystemMessage.toolsAdded` is
   `Tool[]`, complete definitions (`packages/ai/src/types.ts:572-575`), and
   `toolsRemoved` is references. The runtime map (`:execute`, `:streams?`, …)
   stays in the registry. Appended by the one place that changes the set
   (`set-active-tools!`, `apply-default-tools!`, a P5 promotion, an extension
   mount/unmount), projecting to nothing (`context-messages`' default `[]`) and
   skipped by the chat replay exactly like the `:turn-start` marker. The
   *current* advertised set is a fold over the branch by name (pi:
   `getDeclaredTools`, `utils/transcript.ts:170-177`), so a re-added name
   replaces its earlier definition; the registry can no longer change what a
   resumed session says it was sent. The `Entry` union gains `:custom-message`
   beside the new role — with the strict load pass on (`--debug`) every custom
   message is currently reported and skipped, and the new role would fail the
   same way; one line plus a test.
2. **wire — anthropic.** Declare the tools once, on the first request, when the
   model has both mid-conversation capabilities **and** at least one initial
   tool (`supportsMidConvoSystemMessages && supportsMidConvoToolChanges &&
   initialTools.length > 0`, `anthropic-messages.ts:1194-1196`; with no initial
   tool the current list is sent top-level). Under those conditions put the
   never-visible placeholder after the last initial tool, which carries the
   cache breakpoint (`:1259-1265`, definition at `:215-218`), and send the
   `inline-tools-2026-09-15` beta (`:205`, pushed at `:1149`). Later deltas are
   `tool_addition` / `tool_removal` **content blocks** carrying the definition
   by value (`:1354-1393`).
3. **wire — openai-responses.** pi's ladder in `getResponsesToolSupport`
   (`openai-responses-shared.ts:167-174`): `"changes" | "additions" | "none"`.
   `"changes"` (`supportsMidConvoSystemMessages && supportsAdditionalTools`)
   sends `additional_tools` items and, because a removal cannot be native,
   announces removals as text and restates the still-unavailable tools before
   later additions (`renderIncrementalToolsNotice`, `:182-190`). `"additions"`
   (`supportsToolSearch`) synthesizes a `tool_search_call` +
   `tool_search_output` pair with `defer_loading: true` (`:257-277`, `:456`,
   `:469`). `"none"` sends the current list top-level.
4. **wire — openai-completions (Kimi).** pi's third row: with
   `supportsMidConvoSystemMessages && supportsMidConvoToolAdditions`, additions
   ride as a `system` message carrying `tools` (`openai-completions.ts:797-801`,
   `:1236-1245`); otherwise the current list. kmet's generated catalog already
   carries `:deferred-tools-mode :kimi` on five rows
   (`fireworks.edn:425,451,477`, `moonshotai*.edn`) with no consumer — use that
   key for the mode rather than adding a second spelling.
5. **compat.** Read the keys instead of hardcoding, and add them where kmet has
   none today: `:supports-mid-convo-system-messages`,
   `:supports-mid-convo-tool-changes` (anthropic) and
   `:supports-mid-convo-tool-additions` (chat completions) enter `model_gen` +
   `edn_writer` the way `:supports-tool-search` / `:supports-additional-tools`
   already do (`edn_writer.clj:32`). kmet's catalog carries none of the three
   mid-convo keys at all.
6. **The floor, stated.** A provider supporting none of the modes re-declares
   the array and pays the full miss — today's behaviour everywhere, so it is a
   fallback rather than a regression, and the record from step 1 still fixes
   the fidelity half.
7. **Not adopted: `prepareLoadout`.** pi can also rewrite a declared tool's
   description and hide declarations of *active* tools while keeping them
   callable (`ToolLoadoutChanges`, `types.ts:585-598`; `prepareLoadout`,
   `:658`). That is where pi's core/deferred *presentation* lives. kmet has no
   such hook and this plan does not add one: `:deferred` is the
   declaration-level mechanism, and `system_prompt.clj:168` remains the
   presentation. A samizdat-style summary block would need this hook first.

Done when: a tools toggle or a promotion sends the delta instead of the array,
the request is byte-identical to the previous one up to the delta point, a
resumed or forked session replays the recorded declarations rather than the
current registry, the Kimi wire anchors additions as a `system` message, an
anthropic request with no initial tool falls back to the top-level list, and an
unsupported provider degrades to today's full re-declaration.

### Test plan (P6)

- P6, delta: two requests that differ only by a tool change send an unchanged
  tools array — the delta rides as `tool_addition` / `tool_removal` blocks on
  anthropic (with `inline-tools-2026-09-15` in `anthropic-beta`), as an
  `additional_tools` item plus the incremental notice on responses, and as a
  `system` message carrying `tools` on Kimi completions; the placeholder is
  present from the first request when the wire supports native changes **and**
  the initial tool list is non-empty, and absent otherwise.
- P6, responses modes: `"changes"` restates the still-unavailable tools after
  an addition and announces removals as text; `"additions"` emits a
  `tool_search_call` + `tool_search_output` pair with `defer_loading: true`;
  `"none"` sends the current list top-level.
- P6, fidelity: `:tool-change` entries project to no wire message and no chat
  row; folding the branch yields the advertised set, definitions included; a
  registry schema change after the entry does not alter the replayed request;
  resume replays it and a fork taken before a change keeps the older set; an
  unsupported provider re-declares the array, byte-identical to today.

Run: `bb test-changed`, `bb test-ext-changed`, `bb lint-changed`, `bb format-check-changed`.

## Tools: cross-session selection in both scopes

Depends on § Tool exposure and declared sets — not on its code, on its
vocabulary: `:enabled-tools` selects over the set that track splits into
`callable` and `declared`, and a `/tools` row's provenance only means something
once those are two derivations. Exposure itself stays out of this track: a
per-tool or per-scope override would make a `:sandbox` tool un-disableable.

### Why

`/tools` (bundled `tools` extension) records its selection in the session's
branch only — nothing survives into a new session — and `kmet config`'s `Tools`
group writes `:default-tools`, which reaches **built-ins only**
(`default-tool-exclusions` subtracts from `builtin-tool-names`,
`registry.clj:328-336`; `docs/configuration.md:144-146`). The one decision users
actually make here — "extension tool X stays off for this project" — has no
home. Add `:enabled-tools` as the config spelling of the runtime slot that
already exists, and give `/tools` the discovery plus a scope target.

### Decisions

- **`:enabled-tools` is absolute selection over all live tools**;
  `:default-tools` stays the built-in-only, pi-parity key. Precedence already
  exists: `active-tools-for` prefers `enabled` (`state.clj:472-489`), so
  `:enabled-tools` wins and `:default-tools` is the fallback — no new rule.
- **Deltas, not snapshots.** `+name`/`-name` against everything mounted, so a
  tool that appears later defaults on and an MCP catalog that connects late is
  not silently dropped.
- **Two scopes, one per-row model**: global on/off, project
  load/unload/inherit (the `kmet config` vocabulary,
  `resource_config.clj:231-244`). No tab bar — a scope line with the target
  file plus per-row provenance; Tab switches, same as `kmet config` (`:23`,
  `:700-706`).
- **Exposure stays out.** It is a source-level property (§ Tool exposure), and
  a project-scope exposure override would make a `:sandbox` tool un-disableable
  (`callable = … ∪ sandbox`).
- **`/tools` discovers, `kmet config` reviews.** The config screen never loads
  plugins (`tools/config.clj:58-60`), so it sees only built-ins and names
  already in settings; `/tools` runs with the live registry and is the only
  place a first delta can be authored.

### Steps

**S1 — config and resolution (no UI).**

- `kmet.app.config`: generalize `merge-default-tools` → `merge-tool-entries`
  (one rule for both keys); update its call sites (`load-config` `:264-278`,
  `tools/config.clj:97`) and tests. No alias, no shim.
- `load-config`: apply the same special-case to `:enabled-tools`; add
  `get-enabled-tools` beside `get-default-tools` (`:465-471`).
- `kmet.app.tools.registry`: generalize `resolve-default-tools` →
  `resolve-selection [entries base-names]`; keep `resolve-default-tools` as the
  built-in call and add `resolve-enabled-tools` over live names. The `[]` =
  empty-selection branch (`:314-317`) already has the right meaning for both.
- `loop.driver`: resolve `:enabled-tools` beside `:121`, thread through
  `prompt-options` (`:85-93`) and `agent-state` (`:127`, `:147`).
- **Warn when a selection orphans every `:deferred` tool** — no discovery tool
  left to promote one. `:enabled-tools` can drop `tool_search` exactly as
  `:default-tools` can, and the loss is silent today. pi's shape
  (`mcp/index.ts:509-520` warns when `tool_search` exists but cannot be
  activated): an explicit selection still wins, it just reports.

Done when a config with no `:enabled-tools` produces a byte-identical request,
and one with `:enabled-tools` wins over `:default-tools`.

**S2 — extension ABI (two functions).**

- `kmet.app.tools.config` implements, `kmet.extension` exposes:
  - `(ext/tool-selection api)` → `{:scope • :entries {:global […] :project
    […]} :enabled #{…} :origin {name :global|:project|:session|:default}}`,
    one call for the dialog;
  - `(ext/set-tool-enabled! api name enabled {:scope :global|:project})`,
    `enabled` nil = inherit.
- Reuse `modifier-style?` (`tools/config.clj:120-126`) and the delta logic of
  `apply-builtin-tool-project-override!` (`:148-161`) — one writer, shared with
  `kmet config`.
- Default scope: project when `.kmet/` exists, else global — `run-config!`'s
  rule (`config_command.clj:26-27`).
- Wire into the extension API map; the allowed-namespace list in
  `context.cljc` is **unchanged** (no app internal is exposed).

**S3 — `/tools`.**

- Scope line in the header (target file + per-scope counts), Tab to flip; rows
  carry a provenance suffix (`global`, `project`, `session`, `default`).
- Toggle = runtime + branch entry, as today
  (`extensions/tools.clj:39-43,66-95`); `s` persists the selection as a delta
  through `set-tool-enabled!`, so nothing is written to a settings file without
  an explicit act.
- Headless path unchanged (`ui-notify`, `:97-107`).

**S4 — `kmet config` review surface.**

- `builtin-tool-items` (`:73-105`) → `tool-items`: built-in rows keep
  `:default-tools`; names in `:enabled-tools` (either scope) that are not
  built-ins get rows that write/clear the delta, shown even though the tool
  cannot be resolved statically. Default-on extension tools remain invisible
  here — the documented limit.
- `toggle-selected!` (`resource_config.clj:588-615`) gains the
  `:enabled-tools` branch.
- No plugin loading; `mounted-tool-names` (`:45-73`) unchanged.

**S5 — docs.**

- `docs/configuration.md:140-196` — `:enabled-tools`, both scopes, the delta
  rule, precedence; fix the `/reload` sentence at `:194-196` (it contradicts
  `builtins.clj:676-679`); and say that `tool_search` is a selection row like
  any other — naming it out of `:enabled-tools`/`:default-tools` orphans every
  `:deferred` tool, because nothing else can promote one (S1's warn is the
  other half).
- `docs/pi-alignment.md:261` (settings parity; CLI flags still the gap) and
  `:56` (no project trust — note the fail-closed argument).
- `modules/app/src/kmet/extension.md` + `docs/extensions.md` (the two new ABI
  functions, a stated break), `extensions/README.md` and the `tools.clj`
  docstring.

### Test plan

There is no direct coverage of selection resolution today
(`merge-default-tools`/`resolve-default-tools` appear only in
`test_resource_config.clj` and `interactive/test_import.clj`; `active-tools-for`
has none), so:

- new `modules/app/test/kmet/app/test_tool_selection.clj`: scope merge (project
  modifier list amends, plain list replaces), `[]` = none, `:enabled-tools` over
  `:default-tools`, unknown names select nothing, resolution over live names
  including an extension/MCP name.
- `modules/app/test/kmet/ui/tui/test_resource_config.clj`: an `:enabled-tools`
  name that is not built-in renders a row; undo clears the entry; a built-in row
  still writes `:default-tools`.
- `modules/app/test/kmet/app/test_bundled_extensions.clj`: the `tools` artifact
  still validates after the ABI and dialog change.
- ABI: project write lands in `.kmet/settings.edn` as a delta; `tool-selection`
  reports the right `:origin` per scope; global keeps modifier shape.
- Gates: `bb test-changed`, `bb lint-changed`, `bb format-check-changed`.

### Done when

An extension tool turned off in project scope stays off in a fresh session in
that repo and on in another; `/tools` shows where each row's state comes from
and which file Tab will write; a built-in's toggle still writes
`:default-tools`; a config without `:enabled-tools` sends the same request bytes
as today; `kmet config` can undo a delta `/tools` wrote without loading a
plugin.

## Persistent eval and eval sources

Status: plan, decisions closed (E1–E16) — implementation can start at step 1.
Independent of every other track; the `:on-tool` hook covers it for free (step
1 keeps the bridge on `kmet.app.tools.invoke`).
Reference: nodecode (`~/src/cvstree/nodecode/`) — an SBCL agent whose one tool
evaluates Lisp in the live image and keeps every definition
(`docs/README.md` § The layer, `docs/CELLS.md`, the `eval` page on its site).

### Why

`run_code` builds a fresh SCI context per call and discards it on return, on
purpose (`docs/architecture.md` § Tools and the script sandbox). Its economy
is output: compute in code, send a digest, never re-send the data — measured
at 689 calls, 98% of which never touch the bridge. It has no answer for the
other half of code-mode work: code the model wants to *reuse*. A parser for
this repo's odd log format, a `report` fn that renders the project's state, a
scratch namespace — today each is re-pasted in full on every turn, and the
only way to keep one is to write it to a file and `load` it through the
sandbox's require gate, which refuses everything outside the fixed namespace
surface.

Nodecode's `eval` is the existence proof of the alternative: one live image,
one file per definition kept on disk, loaded at the next start, so the agent
grows a library instead of re-typing one. Its cells are the second half: an
optional folder that teaches the agent something — tools, skills, prose —
and is loaded because it is present.

kmet is not one process with one image. Agents are child contexts inside one
process, extensions are isolated and contribute by value, and the sandbox
boundary is a realm around a tool call. So the port takes three shapes that
the original does not need:

- **per agent, not per process.** The environment belongs to the agent's
  child ctx and is disposed with it; a subagent gets its own, never the
  parent's.
- **persistence is opt-in per call.** Nothing is written into the user's
  repository unless the call asks for it, and the file is reviewable text.
- **extensions contribute definitions the way they contribute tools** — a
  registry tag, an owner path, one unload sweep. No new ABI shape.

### What exists today

| piece | today | this track |
|---|---|---|
| script sandbox | `kmet.app.tools.run-code`, one SCI ctx per call, ~1,000 lines of machinery | moves to `kmet.app.tools.script`; both tools use it |
| file ops | inline in `kmet.app.tools.edit` / `.write` `execute` | extracted as ops; tool and verb share them |
| per-agent services | `:shell` (atom), `:sandbox` (data), `:permissions` (memory) — the agents factory provides each | `:eval` joins them |
| extension contribution | `register-tool!`, `register-tool-source!` (`:kmet/tool-source` on the `:tools` handle), commands, skills | `register-eval-source!` (`:kmet/eval-source`), same handle, same sweep |
| model's code tool | `run_code` only | `run_code` + `eval` |

`run_code`'s per-call context costs roughly 3 ms of namespace copying plus
9 ms of `sci/init` (its own comment); a per-agent context pays that once per
agent instead of once per call.

### Shape

Three tiers of what a script can call, and only the third is a source:

| tier | provided by | examples | removable |
|---|---|---|---|
| primitives | `kmet.app.tools.script`, shared with `run_code` | `slurp`, `spit`, `sh`, `p/*`, `fs/*`, `json`, `emit`, `emit-image`, `tools/list`, `tools/describe`, `tools/call`, `(cwd)`, `(env k)`, `(now)`, `(sleep ms)`, `(spawn f)` | no |
| eval vocabulary | the `:eval` service (it needs kept-definition state) | `(eval/kept)`, `(eval/sources)`, `(eval/help)`, `(eval/patch …)`, `(eval/forget …)` | no |
| verbs | the `:core` eval source shipped by the eval tool, and every mounted extension's own source | `edit`, `write-file`; `clj/edit-form`, `clj/replace-sexp`, `clj/paren-repair` | yes (`{:core false}`) |

**The service.** `:eval` is provided per agent by the agents factory
(`kmet.app.loop.driver`, beside `:shell`/`:sandbox`/`:permissions`), so a
child ctx can never shadow it and a preset may configure it first. Value is a
map of fns (`docs/architecture.md` § Service catalog's convention):

```clojure
{:eval!    (fn [{:keys [code timeout scope drop signal on-update ctx]}])
 :kept     (fn [] [{:name "user/f" :scope :project :file "…"} …])
 :sources  (fn [] [{:id :core :doc "…"} …])
 :failures (fn [] [{:file "…" :error "…"} …])
 :dispose! (fn [])}
```

It owns one SCI context, one interrupt holder, one sticky current namespace
(`sci/*ns*` is thread-local, so the last namespace a call entered is
re-entered at the start of the next), one `Object` lock (a parallel batch
serializes; the deadline clock starts *after* the lock is taken), and the
kept-definition index. The context is built lazily on the first eval call —
never at agent creation.

**The tool.** `:tool/eval` joins `:bundle/minimal` beside `:tool/run_code`:

```jsonc
{"code": "Clojure forms. Definitions persist across calls and across sessions of this agent.",
 "timeout": 0,
 "scope": "project | global — omit = this agent only (memory)",
 "drop": ["user/old-fn"],
 "max-output-bytes": 0}
```

Result content is the emitted text (or a silent success's final value, the
REPL rule) plus one line per kept definition — `[eval: kept user/report →
.kmet/eval/user/report.clj]`; `:details` carries `:timeout :elapsed-ms :kept
:dropped :sources :calls :error`. Deadline, abort, the sandbox realm, capture
and the output budget are the shared script layer's, not a second copy.

**The script core.** `kmet.app.tools.script` is the extraction, not a new
sandbox:

```clojure
(prepare opts)        ; per-call state: surface, bridge, emit buffers, abort, trace
(base-namespaces)     ; the host's babashka/clojure namespaces, copied by value
(call-namespaces s)   ; the per-call half: core patches, process wrappers, tools, emit
(context nss ifn tool) (seed! ctx state) (interrupt-state tool) (rearm! h signal deadline)
(run {:sci-ctx :code :state :prelude :extra-details})   ; capture → eval thread → wait → result
```

`run_code` becomes "build a fresh context, run, discard"; `eval` becomes
"rearm, seed, run" against the agent's context. The context's `:load-fn` and
`:interrupt-fn` are fixed at `sci/init`, which is why the interrupt and
per-call namespaces are re-aimed through atoms rather than rebuilt.

**Persistence.** One file per definition, the verbatim source slice
(`rewrite-clj.parser/parse-string-all`, its `:list` children, `node/string`;
rewrite-clj is already an app dependency and a shared extension lib):

```
<project>/.kmet/eval/<ns>/<name>.clj        :scope :project   (cfg/project-dir)
~/.kmet/agent/eval/<ns>/<name>.clj          :scope :global    (cfg/get-agent-dir)

;; kmet eval: kept by the eval tool
(ns user)
(defn report [x] …)
```

Kept forms: top-level `def`, `defn`, `defn-`, `defonce`, `defmulti`,
`defprotocol`, `defrecord`, `deftype`, `defmacro`, `declare` (keyed by the
defining symbol), and `defmethod` (keyed `<multi> <dispatch>`, file name
sanitized). A def nested in `do`/`when`/`comment` is not kept — nodecode keeps
it, kmet deliberately does not, because the file name is the address and a
computed definition has no stable one.

Load at context creation: global files first, then project files, sorted by
path; project wins a name collision. A file that throws is recorded under
`:failures` and reported by the next result and by `/eval-sources`; the agent
still works. A name that is already saved **keeps its scope** on
redefinition, so a re-save cannot silently revert at the next start;
`:scope :agent` opts a new definition out and never deletes a file; `:drop`
unmaps and deletes in both scopes. Writes take the EDN store's file lock
(`kmet.libs.edn-store/with-file-lock`), because two agents of one process can
save at once.

**Eval sources.** The extension-visible half, and the cell analogue:

```clojure
(ext/register-eval-source! api :license
  (fn [{:keys [cwd project-dir agent-dir signal verbs]}]
    (let [{:keys [edit]} verbs]
      {:doc "Add the SPDX header to a source file."
       :ns     {'license {'add! (fn [path] (edit (str (fs/path cwd path))
                                                  "(ns " ";; SPDX-License-Identifier: MIT\n(ns "))}}
       :macros {}})))
```

- Registry tag `:kmet/eval-source` on the same app-wide `:tools` handle as
  `:kmet/tool-source` (`kmet.app.registries/registry-tags` → `:tools`), swept
  by the owner path on unload (`kmet.app.extensions.core/host-tags`).
- `sources-fn` is read per call, so live state is the normal case. It receives
  the call's `:cwd`, the eval roots, the cancel `:signal` (an atom, for a
  source that loops), and `:verbs` — keyword-keyed (`{:edit f :write-file g}`),
  holding **the very fn values the script gets** under `user`, built per call.
  That is how an extension's own logic calls `(edit …)` without requiring a
  host namespace: capabilities cross the boundary as values, as every other
  part of the extension API does.
- Values only: functions, data and explicitly-listed macros. A source that
  throws, or returns a bad shape, contributes nothing and is reported; it
  never fails an eval call.
- `user` is reserved for `:core`; an extension source must namespace what it
  contributes (`license/add!`). Precedence: among sources, later registration
  wins; the agent's own definitions always win (the service records every
  `def*` target the model evaluated and `seed!` skips those names).

**Discovery.** `(eval/sources)` and `(eval/kept)` inside the environment
(`(eval/help)` summarises all three tiers), and a read-only `/eval-sources`
builtin command for the human. No system-prompt section: it would sit in the
cached prefix and vary per agent, so it would invalidate the whole prefix
every time one changed; a source that needs the model to know something
unprompted registers a skill, the existing seam.

### Steps

Nine steps, each one commit; steps 1–2 are behaviour-preserving and every
later step is additive. Estimated ~2,000 new lines, ~1,000 moved (step 1)
plus ~150 moved (step 2).

1. **`kmet.app.tools.script`.** Move the reusable sandbox out of
   `run_code.cljc`: constants, the three dynamic vars (`*enabled-tools-fn*`,
   `*tool-hooks*`, `*assistant-message*`), helpers, capture, capability
   namespaces, `host-class-map`, `sandbox-load-fn`, `context`,
   `call-namespaces`, `active-surface`, the bridge pool, `interrupt-fn`,
   `start-eval-thread!`, `assemble-result`, `realm`. New public seams as in
   § Shape; message text parameterized by the tool name so `run_code`'s
   strings stay byte-identical (`[run_code output truncated …]` is asserted).
   `script-tool-names` = `#{"run_code" "eval"}` — neither tool nests the
   other. Update the moved dynamic vars' bind sites: `kmet.app.loop.clj:1113`
   and `:1120`, `kmet.app.loop.tool_exec.clj:292`,
   `test_run_code.clj` (three `binding`s).
2. **The file ops.** `kmet.app.tools.edit/edit-file` (mutation lock, BOM,
   line endings, `edit-diff/apply-edits-to-normalized-content`, write, diff)
   public, `execute` = op + rendering; `kmet.app.tools.write/write-file`
   public, `execute` = op + success string. Stays in `kmet.app.tools.*`
   (E11) — no libs move, no lock move, tool tests unchanged.
3. **The service and the tool, memory only.** `kmet.app.eval` with `value`,
   lazy context, interrupt holder, sticky ns, lock, `:dispose!`; the driver
   provides the default like `:sandbox`; the handle's `:dispose!` calls the
   service's. `kmet.app.tools.eval` + the `:tool/eval` row in
   `:bundle/minimal`, a sharp description against `run_code` (and one line
   added to `run_code`'s pointing at `eval`). No persistence, no verbs.
4. **Persistence.** The store, the kept-form set, the verbatim slice, load
   order, failures, scope inheritance, `:drop`, `:details`. `(eval/kept)` and
   the file paths in the result land here.
5. **`:kmet/eval-source` and `:core`.** Registry fns
   (`register-eval-source!` / `unregister-` / `get-eval-sources`) in
   `kmet.app.tools.registry`; `registry-tags` + `host-tags`; the service
   resolves sources per call, builds the per-call verb table (`edit`,
   `write-file` over step 2's ops, cwd-resolved), seeds it through `:core`
   (`{:ns {'user {'edit f 'write-file g}}}`, registered by `:tool/eval`'s
   `:apply`), and passes `{:cwd :project-dir :agent-dir :signal :verbs}` —
   keyword-keyed — to every `sources-fn`.
   The eval thread binds the run's dynamic values (`tool-util/*cwd*`,
   `bash-tool/*shell*`, `bash-tool/*session-env-fn*`,
   `read-tool/*skill-resolver*`) so a verb resolves paths exactly as its tool
   does. `(eval/sources)`, `(eval/help)` and `/eval-sources`.
6. **The extension ABI.** `kmet.extension/register-eval-source!` /
   `unregister-eval-source!`; the api keys in
   `kmet.app.extensions.core/create-extension-api`; `create-nullable-api`
   gains `:eval-sources`; `modules/app/src/kmet/extension.md` +
   `docs/extensions.md` (the `license` example, the `user` prohibition, "use
   the verbs within the call").
7. **The eval vocabulary.** `(eval/patch 'name old new)` (structural subform
   replacement through rewrite-clj, re-evaluate, write back to the same
   scope) and `(eval/forget 'name)`. Deferred out of v1 so the structural
   editor gets its own review.
8. **The clojure extension's verbs.** `clj/edit-form`, `clj/replace-sexp`,
   `clj/paren-repair`, sharing `edit_util.clj` (`edit-pipeline`,
   `replace-form`, `format-form-in-isolation`, `repair-delimiters`) with its
   tools; resolve paths against the source's `:cwd` (and fix the tools' own
   process-cwd assumption, which the `:contextual?` ctx already answers).
9. **Docs and catalog.** `docs/architecture.md` § Service catalog (`:eval`) and
   § Tools and the script sandbox (the eval tool, the extraction, and the
   scoping of "a `def` in one call is invisible to the next" to `run_code`);
   `bb generate-catalog`; `docs/pi-alignment.md` § codemode (the persistent
   `store`/`load` half and the extension analogue are no longer missing);
   `modules/app/src/kmet/design.md` § Open at HEAD 5 (one line: a persistent
   context makes Jolt's `eval-in` more attractive later; the decision is
   unchanged).

### Test plan

- **Step 1:** `test_run_code.clj` passes unmodified apart from the three
  `binding`s — the strongest available proof that the extraction preserved
  behaviour. One test asserts the inner-call hook payload still carries the
  assistant message across the moved dynamic var.
- **Step 2:** a table test runs one edit through `registry/execute-tool` and
  through the verb and asserts identical file bytes, and the same
  `:edit-error` message for not-found, duplicate and no-change.
- **Step 3–4:** two `value` instances behave as two agents (definitions do
  not cross); a definition survives a later call; a stale context is not
  visible to `run_code`; `:scope :project` and `:global` write the expected
  files and `:agent` writes none; load order is global→project with project
  winning; a broken file becomes a `:failure` and the agent still evals; a
  saved name redefined without `:scope` writes to its existing file; `:drop`
  deletes in both scopes; `defmethod` files are distinct per dispatch;
  `(eval/kept)` matches the store.
- **Step 5–6:** a source's fns are callable on the next call; a source's
  `:verbs` and the environment's `user/edit` behave identically (assert by
  behaviour — same file bytes — not by identity); unload removes the names on
  the next call and leaves the model's definitions; a throwing source
  contributes nothing; a source contributing `user` is refused; a source that
  captures `:verbs` past its call is documented as unsupported (no test);
  the `/eval-sources` output names sources, kept definitions and failures.
- **Step 8:** in `extensions/clojure/test`, `(clj/edit-form …)` and
  `clojure_edit` on the same fixture produce identical bytes; a bad form
  produces the tool's message.
- **Both hosts:** the eval tests run under babashka and Jolt's views
  (`bb test-changed` plus `jolt test` where the tree has it); `sci/init`
  once per agent and the `swap!`-based `seed!` are the two host-sensitive
  points.

### Limits, stated in the docs

- Same authority as `bash`. Saved definitions run in the agent's next start;
  that is the feature and the risk. Files are plain text in the project for
  review, and no sandbox is claimed.
- A host-blocked call still hits the existing interrupt-grace limitation
  (no process isolation — `modules/app/src/kmet/design.md` § Open at HEAD 5).
- Nothing is kept from `run_code`; nothing from `eval` is visible to
  `run_code` or to a subagent; two agents in one process have two stores and
  two contexts.
- The kept index is unbounded (the model can define as much as it wants);
  the capture and result budgets are the shared script layer's.

### Decisions

**E1 — a second tool, not a `run_code` flag.** `run_code`'s fresh context is
load-bearing for its output economy and for 98% of its calls; a stateful mode
behind an argument would make every one of them stateful in the model's
reading. Two rows, two names, two contracts.

**E2 — `:eval` is a per-agent service from the factory, not a root row.** A
root row could never be narrowed for one agent (the kernel forbids a child
shadowing an ancestor's service) — the same reason `:shell`, `:sandbox` and
`:permissions` are factory-provided. A preset row may mount first and
configure it; the `:tool/eval` row is what a profile removes to hide the tool.

**E3 — agent lifetime.** Definitions survive `new-session!` /
`resume-session!` (the handle is the agent; a session is a log), and are not
shared with subagents: a subagent is its own child ctx, and inheriting the
parent's environment would make two agents' definitions interfere. A shared
environment would be a separate decision, not a default.

**E4 — nothing is written unless the call says `:scope`.** A model-facing
default of "write into the user's repository" is not a default kmet takes;
`:project` and `:global` are explicit, and the result always names the file.

**E5 — one file per definition, verbatim source.** The file is the address
(`<ns>/<name>.clj`), so lookup, deletion and review are path operations.
`pr-str` of the parsed form would normalise away reader sugar; the
rewrite-clj slice keeps the bytes the model wrote.

**E6 — a saved name keeps its scope; deletion is `:drop`.** A redefinition
without `:scope` must not create the state where memory and disk disagree
until the next start. `:scope :agent` opts a *new* definition out; it never
deletes, because a silent delete is the one outcome a model cannot review.

**E7 — top-level defining forms only.** A def inside `do`/`when`/`comment` is
not kept, unlike nodecode: a computed definition has no stable file name, and
the alternative (an aggregate file per call) makes per-definition deletion
and review impossible.

**E8 — `defmethod` keyed by `<multi> <dispatch>`.** Otherwise every method of
one multimethod lands on one file and the last one wins at load. The
sanitized dispatch in the file name is the address; `:kept` names the full
key.

**E9 — eval sources are values; neither tool nests the other.** The
contribution crosses the isolation boundary the way every other capability
does (functions and data), so no source can reach a host service it was not
given. `script-tool-names` excludes both tools from any script's surface, so
`run_code` cannot start an `eval` and an `eval` cannot start another.

**E10 — `user` is reserved for `:core`; extensions namespace their verbs.**
Later-registration-wins is app-wide, so an extension that took `edit` would
silently replace the built-in in every agent. The rule is enforced by the
service (a source contributing to `user`, `clojure.core`, `tools`,
`sandbox`, `eval` or `fs` is refused and reported), not only documented.

**E11 — host operations cross as values, not as requirable namespaces.**
`sources-fn` receives `:verbs` — the same fns the script calls — so an
extension's own logic can compose `(edit …)` without `kmet.app.*` becoming
requirable and without moving the file ops or the mutation lock into `libs`.
A libs extraction happens only if a consumer needs the op *outside* a source
(a non-extension caller), which none does today.

**E12 — discovery is in-code, plus one read-only command.** `(eval/sources)`,
`(eval/kept)`, `(eval/help)` and `/eval-sources`; no system-prompt section.
A dynamic per-agent section sits in the cached prefix and varies per agent,
so it would invalidate the whole prefix every time one changed. A source that
needs unprompted presence registers a skill, the existing seam.

**E13 — `:core` ships file verbs only.** `edit` and `write-file` are the
two operations a bare profile (no extensions mounted) needs to loop over
files; everything tool-specific comes from the extension that owns the tool —
Clojure editing from the clojure extension, search verbs from a future one.
`read`/`ls`/`glob`/`grep` are deliberately absent: `slurp` and the `fs` alias
cover them, and a pure-Clojure grep would need gitignore and binary-file
rules we would get wrong (`rg` via `sh` is the better answer).

**E14 — declined.** Source strings evaluated in the eval context (would give
an extension `(edit …)` by name, and full authority over the environment for
nothing the value contract cannot express); `*`/`/` value chains across calls
(no REPL value history in SCI); `$` named strings (the tool's argument is the
code); `fork` (subagents, not this tool); an event vocabulary for kept
paragraphs (no reader); per-agent eval sources (the registry is app-wide,
like tool sources); auto-save on any definition.

**E15 — names closed.** `/eval-sources` (the alternative `/eval` is recorded
and declined for v1: a read-only report that looks like an evaluator invites
the wrong expectation); `clj/*` for the clojure extension's verbs (not the
tool names `clojure_*`, and never bare).

**E16 — the service is built-in; the verbs are the extension point.** `:eval`
is provided by the agents factory and its engine lives in `kmet.app.eval` — an
extension cannot own either: per-agent provisioning is the factory's (no ABI
lets an extension provide a service on every agent's child ctx), and the
engine needs `kmet.app.tools.script`, `kmet.app.tools.registry`,
`kmet.app.tools.edit/edit-file` and the loop's bindings, none of which an
extension may require. The tool is a **row** (`:tool/eval` in
`:bundle/minimal`) and the report a **builtin command**, which is what
built-in means here: a profile removes the row
(`{:id :tool/eval :remove true}`) or replaces its plugin exactly as it can for
`bash`, so nothing privileged is involved. `:ext/eval` would buy none of what
an extension is for (its isolation is the sandbox the engine already is, its
context is already lazy, its replaceability the row already gives) and would
cost a new provisioning seam, a second dependency tree, and — since bundled
extensions are never loaded by default — every profile naming the row anyway.
The service is provided unconditionally by the factory (lazy, zero cost until
the first call), so removing `:tool/eval` leaves a service with no reader, the
same accepted shape as `:sandbox` without `:tool/run_code`.

## Deferred: context and token tracks

The tool-result pruner (**R**), the request-envelope estimate (**M**),
warm-prefix summarization (**W**) and per-result retention with a locator
(**S**) were dropped from this file on 2026-11-02. The full plans stay in git
history; a future revisit should start from measurement rather than re-derive
them.

Measured before the drop (292 session files, 47,366 tool results, 44
compaction windows; the projection reconstruction matches the recorded
`:tokens-before` to ~1%):

| fact | number |
|---|---|
| projection at a compaction trigger | 45.9M chars ≈ 11.5M tokens over 44 windows |
| tool results / thinking / tool-call args | 45.3% / 37.3% / 14.8% |
| tool-result bytes by tool | bash 66.4%, read 29.1%, everything else 4.5% |
| unbounded tools at the trigger (`run_code`, extensions) | 1.9% of tool-result chars |
| ceiling every tool already observes | ~50 KiB (read/bash pi's cap, MCP adapter's output guard) |
| R candidates (≥8 KiB, outside the newest turn) | 352 results = 26.8% of trigger tool chars; trimming saves ≈8% of the projection |
| S budget 3,125 / 6,250 / 12,500 tok | 119 / 31 / 3 unbounded results clipped |

Two corrections for whoever picks this up. The R note's composition table
(47% thinking / 26% tool results / 19% args, 7.09M chars) does not match the
projection above — it measures a ~6x smaller quantity, most likely the
2000-char-truncated summarization input. And pi has no counterpart for any of
the four (no pruner, no envelope estimate, no warm-prefix summary, no
per-result retention), while dsh has one for each.

Pi parity note: any revisit lands a row in `docs/pi-alignment.md` §8 and a
sentence in the commit body naming the departure.
