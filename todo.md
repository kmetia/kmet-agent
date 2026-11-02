# Todo

Seven tracks. The first three are independent; the four token tracks at the
end are ordered among themselves (§ Order across the token tracks), and one of
them depends on an item of the caching track. Each is a request/ABI-level
change with unit-testable surfaces.

| track | what it fixes | status |
|---|---|---|
| Prompt-caching parity with pi | kmet's wires never ask for prompt caching (except responses/mistral/bedrock), so cache-read sits at 0; the tool ranker serves MCP only, no source's tools can be declared, and a tool-set change re-sends the whole array | P0–P6, decisions closed |
| Around tool hooks | two hand-rolled hook chains + a doubled permission gate; no way to settle a call successfully | decided: **A** — one `:on-tool` around registration |
| Turn markers in the session and the tree | the log cannot distinguish a steering injection from a typed prompt, and nothing records where a turn begins, so the tree draws no boundary and no turn-granular mechanism can be built on the log | decided; independent, and **R**'s newest-turn boundary |
| **R** — the tool-result pruner | a tool result is never trimmed after it lands, so compaction condenses a window dominated by old output; `bash` is the only tool that keeps a retrievable copy | decisions closed; after **M** |
| **M** — the request envelope as one measurement | every estimate counts messages only — not the system prompt (unbounded `AGENTS.md`), the skill catalog or the tool schemas; `:context-window` is stale after a model switch; images are invisible | decisions closed; **land first** |
| **W** — warm-prefix summarization | the summarization call is a guaranteed cold prefix (fresh system prompt + re-serialized transcript + `:cache-retention :none`), so the largest call a session makes pays full input on the whole history | decisions closed; after **M** and caching **P0/P1** |
| **S** — per-result retention with a locator | only `bash` spills; `read`'s 50 KiB, `run_code`'s value and every extension result land at full size and stay, and `bash`'s locator points at a file deleted on exit | one decision open (**S6**); after **R** |

## Prompt-caching parity with pi

Status: plan, decisions closed — implementation can start at P0.
Owner: whoever takes the `:llm` row next.
Reference implementation: `~/src/cvstree/pi/packages/ai/src/api/` (pi).

### Why

kmet ports its wire APIs from pi, but the prompt-cache half of the port was
never finished: pi's `cache_control` / `prompt_cache_key` / affinity-header
placement exists in kmet's *compat layer* and is consumed by almost no wire.
The cost is paid on every round of every session, and it is invisible because
the accounting side is complete — `kmet.ai.usage` already parses
`:cache-read` / `:cache-write` and prices them, so a wire that never asks for
caching reports a valid cost with cache-read pinned at 0.

Measured surface (2026-11-01, `grep -rn cache_control modules/` → no hits
outside generated model data):

| wire | kmet today | pi |
|---|---|---|
| `anthropic-messages` | **no `cache_control` anywhere**, `:cache-retention` not even destructured | system block + last tool + last user message block; 5m default, 1h under `long` |
| `openai-completions` | nothing — no `prompt_cache_key`, no `prompt_cache_retention`, no anthropic-format markers | all three (key on api.openai.com, 24h under `long`, markers for OpenRouter `anthropic/*`) |
| `openai-responses` | `prompt_cache_key`, `prompt_cache_retention "24h"`, `prompt_cache_options {:mode "explicit"}`, affinity headers | same + `prompt_cache_options {:ttl "30m"}` for explicit-mode models; omits `prompt_cache_retention` for them |
| `openai-codex-responses` | `prompt_cache_key` gated on retention | same |
| `bedrock-converse-stream` | `cachePoint` with 1h TTL | same |
| `mistral-conversations` | `prompt_cache_key` + `x-affinity` header | same |
| google | implicit only | implicit only (parity) |
| usage / cost of cache read+write | complete (`kmet.ai.usage`) | complete (parity) |

Two further observations that make this cheap:

- `kmet.ai.api.shared/detect-openai-compat` **already computes** the three keys
  the wires ignore: `:cache-control-format` (line ~496), `:send-session-affinity-headers`,
  `:session-affinity-format`, `:supports-long-cache-retention` (~498-501).
- The generated model data already carries `:supports-cache-control-on-tools`,
  `:supports-long-cache-retention` and `:send-session-affinity-headers`
  (`kmet.ai.model-gen` ~1030 documents them as "data-only in kmet"; 26 rows in
  `model_data/fireworks.edn`).

So the work is wiring, not design: read the compat keys that exist, and place
the markers pi places.

### Non-goals

- No new compaction strategy. This plan only changes request bytes.
- No google explicit `cachedContent` (pi does not do it either).
- No live-API verification as a gate; the payload builders are pure and the
  assertions belong in unit tests (see § Test plan).

### Tasks

#### P0 — `anthropic-messages`: place markers (biggest win, smallest diff)

Files: `modules/ai/src/kmet/ai/api/anthropic_messages.clj`,
`modules/ai/src/kmet/ai/api/shared.clj`.

1. **Extract a pure payload builder.** `anthropic-request` (~line 202) builds
   its payload inside the `future`/`try`, so nothing can assert on it. Add
   `anthropic-payload [model-record effort messages tools model-id cache-retention]`
   returning the body, mirroring `openai-payload` / `responses-payload` /
   `mistral-payload`; `anthropic-request` calls it and keeps only the HTTP
   part. Destructure `cache-retention` in `anthropic-request`'s opts.
2. **`anthropic-compat`** — pi's `getAnthropicCompat` (`anthropic-messages.ts:224-236`)
   with its `??` defaults, beside the payload builder, since the anthropic
   wire currently reads `(:compat model-record)` ad hoc for two keys:
   `:supports-cache-control-on-tools` (default `true`),
   `:supports-long-cache-retention` (default `true`),
   `:send-session-affinity-headers` (default: OpenRouter only),
   `:session-affinity-format` (default `:openrouter` on OpenRouter).
   Steps 3-5 and D3 all read it.
3. **`cache-control` derivation** — pi's `getCacheControl`
   (`anthropic-messages.ts:83-98`): `retention = (or cache-retention :short)`;
   `:none` → no marker; otherwise `{:type "ephemeral"}`, plus `:ttl "1h"`
   when `long` and `:supports-long-cache-retention`.
4. **System block.** The payload already emits `:system [{:type "text" :text system}]`
   (~line 219) — put the marker on that block.
5. **Last tool.** The `mapv` over `tools` (~line 222): marker on the final
   element when `:supports-cache-control-on-tools` (`fireworks.edn` sets it
   false for 26 models; everything else takes the true default).
6. **Last message.** After `anthropic-messages` returns, mark the last
   `:role "user"` message's last content block (`:text` / `:image` /
   `:tool_result`), pi `:1529-1556`. Watch the string case: `anthropic-content`
   returns a plain string when there are no images, and the marker needs a
   block — wrap it as `[{:type "text" :text … :cache_control …}]` exactly as
   pi does.
7. **Budget.** Anthropic honours four breakpoints; we place three. Add a
   comment naming the count so a later addition does not silently exceed it.

8. **Price the 1h share** — only observable once `:long` can be requested, so
   it lands with P0 rather than after it. `kmet.ai.usage` carries
   `:cache-write-1h` from `cache_creation.ephemeral_1h_input_tokens`;
   `models.clj/calculate-cost` prices that share at 2x base input (pi
   `models.ts:1247-1248`) and the remainder at the configured `:cache-write`
   rate; one test mirroring pi's `anthropic-cache-write-1h-cost.test.ts`.
   Without it a 1h write is billed at the 5m rate — a 2x undercount on that
   share. Today's gap is documented at `models.clj:76` ("minus cacheWrite1h").

Done when: `anthropic-payload` emits `cache_control` on system + last tool +
last user block for a 2-round conversation with tools; `:none` emits none;
`long` emits `:ttl "1h"` on all three and the usage path prices it.

Note / decision D1 below: pi sets `ttl "1h"` and sends **no**
`extended-cache-ttl-2025-04-11` beta header — see D1, now decided.

#### P1 — `openai-completions`: key, retention, and anthropic-format markers

Files: `modules/ai/src/kmet/ai/api/openai_completions.clj`,
`modules/ai/src/kmet/ai/api/shared.clj`.

1. `openai-payload` (~line 11): thread `cache-retention` and `session-id` in
   (callers already pass both through `wires/request-fn`).
2. Add `prompt_cache_key` — pi `:811-815`: only when the base URL is
   `api.openai.com` and retention is not `:none`, or when `long` and
   `:supports-long-cache-retention`. The clamp helper exists but sits in the
   wrong namespace (`openai_responses.clj:170` `clamp-prompt-cache-key`) —
   move it to `shared.clj` beside the other wire helpers and require it from
   both.
3. Add `prompt_cache_retention "24h"` when `long` and supported (pi `:816`).
4. Add `shared/apply-anthropic-cache-control` for
   `(= :anthropic (:cache-control-format compat))` — pi `:1074-1103`:
   system prompt text, last tool, last conversation message (`user` /
   `assistant` / `tool`). Insertion order matters: it must run after the
   messages and tools vectors are built and before `:sampling-params`.
5. `openai-request-headers` (~line 53): add session-affinity headers when
   `(:send-session-affinity-headers compat)` — pi `:759-770`, which is a
   *different* set from the responses wire's: `x-session-id` alone for
   `:session-affinity-format :openrouter`; otherwise `x-client-request-id` +
   `x-session-affinity` + (`session_id` when the format is `:openai`). Gate on
   retention `:none` sending nothing, like `openai-responses`.

Done when: an OpenRouter `anthropic/*` model gets all three markers and
`x-session-id`; an `api.openai.com` model gets `prompt_cache_key`; a
non-openai compat provider gets neither key nor markers; `:none` strips all of
it.

#### P2 — compat keys become the single source, not per-wire hardcoding

Files: `modules/ai/src/kmet/ai/api/shared.clj` (`detect-openai-compat`,
`resolved-openai-compat`), `modules/ai/src/kmet/ai/api/anthropic_messages.clj`,
`modules/ai/src/kmet/ai/api/openai_completions.clj`.

- Confirm `:cache-control-format`, `:supports-cache-control-on-tools`,
  `:supports-long-cache-retention`, `:send-session-affinity-headers`,
  `:session-affinity-format` all survive `resolved-openai-compat`'s key-wise
  merge (an explicit `false` in `models.edn` must win over the detected
  default — that is what the "default is present so an explicit compat value
  survives" comment in `detect-openai-compat` is about).
- Delete the hardcoded family map in `openai-responses.clj`
  (`responses-affinity-headers`, ~line 154) in favour of the compat keys it
  duplicates, keeping `responses-payload`'s `:none` gate. Share the *decision*
  (read the two compat keys), not the literal header map: pi's three wires
  emit three different sets from the same keys — anthropic `x-session-affinity`
  / `x-session-id`, completions `x-session-id` or (`x-client-request-id` +
  `x-session-affinity` + optional `session_id`), responses `x-session-id` or
  (`session_id` + `x-client-request-id`). If one function serves all three it
  takes a shape argument; if not, each keeps its own map and only the compat
  read is shared. One spelling per *behaviour*, three shapes because the
  providers want three.

Done when: no wire branches on `models/provider-family` for caching or
affinity, and `model_gen`'s "data-only in kmet" note can be deleted.

#### P3 — make `long` reachable, and expose `:none` where it belongs

Files: `modules/ai/src/kmet/ai/llm.clj`, `modules/ai/src/kmet/app/config.clj`,
`modules/ai/src/kmet/ai/api/shared.clj`.

1. Resolve retention once, in `kmet.ai.llm` (beside the `:cache-retention`
   docstring, ~line 76): `(or (:cache-retention opts) (shared/getenv
   "KMET_CACHE_RETENTION") :short)` — `kmet.ai.llm` already requires
   `kmet.ai.api.shared`, so no new dependency. Env name: pi's is
   `PI_CACHE_RETENTION`; pick a `KMET_` name and say so in the docstring.
   Update that docstring: the key is no longer "ignored by the other apis".
2. Pass `:cache-retention :none` for every non-turn call whose output nobody
   caches: summarization already does (`kmet.app.compaction.stage:135`);
   audit the rest (branch summaries, title generation, guards).
3. No per-model retention key: resolved once in `kmet.ai.llm` from the call's
   `:cache-retention`, then `KMET_CACHE_RETENTION`, then `:short` (D2, decided).

Done when: `KMET_CACHE_RETENTION=long` produces the 1h/24h variants everywhere
the compat supports them, and no non-turn call leaves caching on.

#### P4 — responses fine points (do last, small)

Files: `modules/ai/src/kmet/ai/api/openai_responses.clj`.

- pi `:133-141`: when `:supports-explicit-prompt-cache-mode`, `long` sends
  `prompt_cache_options {:ttl "30m"}` and **omits** `prompt_cache_retention`;
  kmet currently sends `"24h"` unconditionally on `long`. Align.
- Keep `{:mode "explicit"}` for `:none` (already correct).

Done when: the explicit-mode model sends `prompt_cache_options` and no
`prompt_cache_retention`.

#### P5 — pi's exposure semantics, one ranker, and a deferred source

Independent of P0–P4 and, unlike them, not a wire change: it touches the tool
registry. It replaces an earlier "defer MCP tool schemas" idea that turned out
to be unnecessary — kmet's MCP adapter already registers **one** `mcp` proxy
tool with `search`/`describe`/`call` modes
(`extensions/mcp-adapter/src/kmet/extensions/mcp_adapter/core.clj:697,1315`), so
N servers already cost one schema and `mcp search` already exists. What is
missing is that the ranker only serves MCP, and that no source's tools can ever
be declared.

Reference: pi's `ToolExposure` (five values,
`packages/coding-agent/src/core/extensions/types.ts:536`) and its built-in
`tool_search` (`packages/coding-agent/src/extensions/tool-search/tool.ts`).
kmet adopts pi's semantics and pi's names, and adds one value (`:sandbox`) for
the contributed-source behaviour it already has.

Files: `modules/app/src/kmet/app/tools/registry.clj`,
`modules/app/src/kmet/app/loop/state.clj`,
`modules/app/src/kmet/app/tools/run_code.cljc` (the callable surface),
a new `modules/app/src/kmet/app/tools/search.clj`,
`modules/app/src/kmet/app/session/context.clj` (the deferred-source listing),
`extensions/mcp-adapter/src/kmet/extensions/mcp_adapter/search.clj`,
`modules/app/src/kmet/extension.clj`.

1. **Use the seam that exists.** `registry.clj:144-188` already registers
   contributed tool sources (`register-tool-source!` / `get-contributed-tools`),
   and nothing calls it: `active-tools-for` (`state.clj:472-489`) never passes
   `:contributed`, so contributed tools are sandbox-only and no extension has
   ever contributed one. Give the registration an option map instead of being
   implicitly sandbox-only:
   `{:exposure :sandbox}` (today's behaviour: callable, never declared;
   searchable once `tool_search` is active) | `{:exposure :deferred}` (callable
   and searchable, so the source's tools join the model-facing table and
   `:enabled-tools` / `:default-tools` resolve over them like any registered
   tool). One key on a function with no in-repo consumer, so the ABI rule costs
   nothing here.
2. **Four exposure values on the tool record, with pi's meanings.** `:exposure`
   on a tool record is one of:
   - `:direct` (default, absent = direct, so every `make-tool` call and
     extension is unchanged) — declared and callable;
   - `:deferred` — callable, not declared, searchable; a `tool_search` match
     declares it on the next request;
   - `:model-only` — declared, never callable from `run_code` (pi's
     `model-only`; pi's own `tool_search` is one, `tool.ts:320`, because kmet's
     `run_code` can otherwise call any declared tool);
   - `:sandbox` — callable, not declared (today's contributed sources; pi's
     `codemode` exposure). `:sandbox` tools are searchable when `tool_search`
     is active, as pi's `codemode` ones are (`isSearchable`,
     `tool.ts:280-282`), but they do not by themselves cause `tool_search` to
     be declared.

   `:hidden` stays out: kmet can unregister a tool and exclude it with
   `:enabled-tools`, and pi needs `hidden` only because it cannot unregister
   (`docs/extensions.md` § Tool Exposure).
3. **Callable and declared are two derivations, not one.** pi's `ToolLoadout`
   carries `declared` and `callable` separately (`types.ts:571-583`), and a
   `codemode` script reaches tools that were never declared
   (`docs/mcp.md:228`). kmet's current invariant — the wire list and the
   `run_code` surface resolve through one function — is exactly what would make
   a deferred tool uncallable until loaded. Split it:
   - `callable = (selection − model-only) ∪ contributed :sandbox sources` —
     the `run_code` surface, passed through `*enabled-tools-fn*`
     (`loop.clj:1113`);
   - `declared = (selection ∩ (direct ∪ model-only)) ∪ (loaded ∩ selection)` —
     the wire list, from `active-tools-for` (`state.clj:467`).

   `selection` is the existing `:enabled-tools` / `:default-tools` resolution;
   `loaded` is a per-session ordered set of promoted names recorded as a
   session entry (invisible to the wire), so resume, `/tree`, fork and replay
   all see the same declared set. `loaded ⊆ selection`: disabling a loaded tool
   hides it without clearing the load, and re-enabling restores it.
4. **Move the ranker into the app.** `mcp_adapter/search.clj` is already a
   general ranker (weighted `{:name 12 :original-name 10 :server 8
   :description 5 :keywords 5}`, regex mode, paging) that happens to read one
   metadata cache. Add `kmet.app.tools.search`: documents built the way pi
   builds them (`tool.ts:20-40`, `:119-129`: name, description, `:parameters`
   property names and descriptions, namespace text) from any tool map plus an
   optional extra-text map; rank, page, render. The MCP adapter keeps the
   cache, `:search-keywords`, server names and its output shape, and calls the
   shared ranker. Deliberate deviation: pi scores with BM25
   (`tool.ts:288-302`), kmet keeps its weighted scorer so `mcp search`'s output
   and arguments do not change; the ranker's *contract* (candidates, limit,
   document fields) is pi's.
5. **One `tool_search`, shaped like pi's.** `{query, limit?}` — one call that
   ranks the not-declared searchable surface (`:deferred` tools, plus `:sandbox`
   ones when `tool_search` is declared; pi's candidates are
   `isSearchable(exposure) && !active`, `tool.ts:288-302`), loads **every**
   returned match (default 8, `DEFAULT_TOOL_SEARCH_LIMIT`), and replies in pi's
   shape: `Loaded N tools. They are available from your next call:` then
   `- name: first line`, with the loaded names in the result details
   (`tool.ts:328-334`). No `query`/`select` split and no summaries-only mode.
   The tool's own description is static (`tool.ts:305-308`), so registering a
   source never rewrites it; it carries pi's snippet ("Search for tools that
   are not loaded yet and load the matches"), is registered `:model-only` (pi:
   `exposure: "model-only"`, `tool.ts:320`), and is declared only when at least
   one `:deferred` tool exists — the rule pi's MCP extension uses
   (`docs/mcp.md:206`), which one `:sandbox` source alone cannot meet.
6. **Keep the MCP proxy.** Promotion *adds* a declared schema; `mcp` still needs
   `describe`/`call`/`list`/resources for everything never promoted, and
   `mcp search` stays useful for inspecting without loading. `mcp search` and
   `mcp describe` read the metadata cache only and never spawn a server;
   invocation connects at call time, so a promoted tool is safe to declare
   before its server has ever launched.
7. **Let the model see what is deferred.** pi lists MCP servers in an
   `mcp_servers` system-prompt section — exposure and one line per source — and
   when it changes it *appends the new section as a message* instead of
   rewriting the declarations (`docs/mcp.md:204-210`). Without a listing the
   model must search blind for a capability it cannot name. kmet: append a
   `:custom-message` entry (`session/context.clj:140-152`, written by
   `append-custom-message-entry!`, `store.clj:456`, and projected as a
   user-role message) naming each deferred source, its one-line description,
   and that its tools are callable from `run_code` and declared after
   `tool_search`. Write it when the set of sources or their descriptions
   changes, never on a promotion. Never rebuild the leading system message for
   a source connecting; that is a full prefix miss.

Deferred to P6: pi's message-anchored tool changes (`tool_addition` /
`tool_removal` + the placeholder). Until then a promotion changes the tools
array and costs one full prefix miss on that turn — cheaper than carrying every
schema in every prompt, which is the alternative it replaces.

Done when: a source registered with `:exposure :deferred` contributes nothing
to the wire until `tool_search` promotes it but is callable from `run_code`
throughout; a `:model-only` tool is declared and not callable from `run_code`;
a direct tool and every existing extension are byte-identical in the request;
`mcp search` and `tool_search` rank through the same function; `tool_search`
loads every match it returns and replies with pi's text; a deferred source is
named in an appended `:custom-message` and the leading system prompt is
unchanged; a promotion survives resume and a fork.

#### P6 — mid-conversation tool changes without a full re-prefill

Depends on P5: the promotions are what make a mid-session tool change ordinary
rather than a deliberate user action, and both items share the session record.
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
   resumed session says it was sent.
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

### Test plan

Pure builders, no network, no `^:slow`:

- `modules/ai/test/kmet/ai/test_llm_anthropic.clj` — new deftest over
  `anthropic-payload`: three markers `:short`; none `:none`; `:ttl "1h"` on
  `long`; string-content message wrapped; `:supports-cache-control-on-tools
  false` drops the tool marker.
- `modules/ai/test/kmet/ai/test_llm_openai.clj` — `openai-payload` /
  `openai-request-headers` for: OpenRouter `anthropic/*` (three markers +
  `x-session-id`), `api.openai.com` (`prompt_cache_key`, no markers), a plain
  compat provider (neither), `:none` (nothing).
- `modules/ai/test/kmet/ai/test_llm_models.clj` — an explicit `false` in
  `:compat` wins over the detected default for each of the three cache keys
  (this is where `detect-openai-compat` / `resolved-openai-compat` are
  already under test).
- Existing byte-stability tests: assert that two consecutive payload builds
  from the same messages are `pr-str`-equal, so a marker cannot leak
  per-round churn.
- P5, registry: a source registered with `:exposure :deferred` contributes
  nothing to `active-tools` but its tools stay callable from `run_code`; a
  `:sandbox` source keeps today's behaviour; `:model-only` is declared and not
  callable from `run_code`; a promotion adds the tool for the next request;
  `:enabled-tools` disabling a loaded tool hides it without clearing the load,
  and re-enabling restores it with no second search; a `:direct` tool and every
  existing extension render byte-identical to today.
- P5, tool_search: loads every match it returns, caps at 8 by default, replies
  in pi's `Loaded N tools. … - name: first line` shape with `details.loaded`,
  never ranks an already-declared tool, is declared only when a `:deferred`
  tool exists, and its own description is byte-identical before and after a
  source registers.
- P5, listing: registering, changing or unregistering a deferred source appends
  one `:custom-message` entry naming the source and never rewrites the leading
  system prompt; a promotion appends none.
- P5, ranker: one function serves Tool maps and the MCP metadata cache — a
  name match outranks a description match at the current weights, regex mode
  and paging behave, a tool with no `:description` does not throw, and
  `mcp search`'s output is unchanged for the adapter's existing tests.
- P5, session: a promotion is recorded as a session entry and replays — a
  resumed session and a fork made after the promotion both advertise the same
  set; a fork made before it does not.
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

Run: `bb test-changed`, `bb lint-changed`, `bb format-check-changed`.

### Order and size

P0 and P1 are independent and each is a single-file change plus a test.
Recommended sequence: P0 → P1 → P2 → P4 → P3 (P3 resolves a decided setting via
`KMET_CACHE_RETENTION`, D2). P2 last-if-busy: it deletes duplication, it does
not change bytes. P5 is independent of all of them — it touches the tool
registry, not a wire — so it lands whenever, and is the one item whose payoff
is measured in prompt tokens rather than cache reads. P6 depends on P5 and wants
P0/P1 landed so the cache it protects is actually being taken. All decisions
are closed; nothing waits on one.

### Decisions

**D1 — `:long` on anthropic (decided: match pi, no beta header).** pi sends
`cache_control: {:type "ephemeral" :ttl "1h"}` and no `extended-cache-ttl…`
beta — the string appears nowhere in pi — and it *relies* on the result:
`usage.cache_creation.ephemeral_1h_input_tokens` is parsed and priced at 2x base
input (`models.ts:1247-1248`, covered by
`anthropic-cache-write-1h-cost.test.ts`). nodecode's beta
(`providers/anthropic.lisp:420`) is the only divergence and reads as carried
over from when the header was required.

Prerequisite kmet lacks: it drops the 1h split entirely. `models.clj:76` says
"minus cacheWrite1h" and `usage.clj:29` normalises
`cache_creation_input_tokens` but not its `:ephemeral_1h_input_tokens` member,
so a 1h write would be priced at the 5m rate. Landed with P0 (step 8).

**D2 — retention resolution (decided: env now).** Resolve in `kmet.ai.llm`:
`(or (:cache-retention opts) (getenv "KMET_CACHE_RETENTION") :short)`. No
per-model or per-provider key — the per-model shape has no consumer, and a
`settings.edn` key can follow if one appears. The per-call `:cache-retention`
stays the override that compaction already uses for `:none`.

**D3 — affinity on anthropic (decided: in P0).** `anthropic-compat` already
answers `:send-session-affinity-headers` / `:session-affinity-format`, and the
wire already makes the `request-headers` call, so the headers cost nothing
extra. Default OpenRouter-only, as pi's `getAnthropicCompat:230` has it.

**D4 — exposure follows pi, including `:model-only` (decided: correcting the
earlier two-value claim).** The tool record carries
`:direct | :deferred | :model-only | :sandbox` with pi's meanings
(`ToolExposure`, `types.ts:536`); `:hidden` is unregistration/exclusion in
kmet. `:model-only` is not academic: kmet's `run_code` can call any declared
tool, and pi registers `tool_search` as `exposure: "model-only"`
(`tool.ts:320`) — as `:direct` a script could call it and change the wire
mid-script. `:sandbox` is pi's `codemode`: callable, undeclared, searchable
once `tool_search` is active.

**D5 — `tool_search` is pi's, not samizdat's (decided).** One `{query, limit?}`
call that ranks **and** loads every match (default 8), replies `Loaded N
tools. …` with one line per tool and `details.loaded`, and has a static
description (`tool.ts:305-334`). No `query`/`select` split and no
summaries-only mode. Candidates are the not-declared searchable tools
(`tool.ts:288-302`); the tool is declared only when a `:deferred` tool exists,
matching the MCP extension's activation rule (`docs/mcp.md:206`), not merely
a `:sandbox` source.

**D6 — declarations are recorded by value (decided).** `:tool-change :added`
carries `{:name :description :parameters}`; `:removed` carries names. pi records
`Tool[]` and replays definitions, so a schema change between sessions cannot
silently alter what a resumed request sends (`types.ts:572-575`,
`getDeclaredTools`). The runtime `:execute` fn and friends never enter the
session entry.

**D7 — the deferred-source listing is an appended entry (decided).** kmet has no
mid-conversation system-message role, so the listing is a `:custom-message`
entry projected as a user-role message (`session/context.clj:140-152`), not a
system-prompt rebuild. It names a source and one line, not its tools.

**D8 — the Kimi row is in scope (decided).** P6 covers three wires, not two:
anthropic, responses and `openai-completions`. `:deferred-tools-mode :kimi` is
already in the generated data; the missing compat key is
`:supports-mid-convo-tool-additions`.

### Verification beyond tests

On a real session against Anthropic with `-message` logging on, confirm the
second round's usage chunk reports `cache_read_input_tokens > 0` for all but
the newest message. That is the only assertion that proves the markers are
accepted; everything above proves they are sent.

## Around tool hooks: pre/post → one filter

Status: decided — **A**: one `:on-tool` around registration, the old pair
removed, the two in-repo consumers migrated in the same change (§ A1),
fail-closed on a hook's own error (§ A2). One implementation question remains
(§ A3, where the primitive lives).
Reference: nodecode `~/src/cvstree/nodecode/src/src/organism/hook.lisp` (the
`:tool` point, `call-hooked`, `call-isolated`).

### Why

kmet has two hook *types* — before (rewrite args / block) and after (rewrite
result) — each a single `fn [ctx]` in an atom, and the chains that run them are
hand-rolled in the loop driver. nodecode has one *around* shape at one point:
`(fn [op next])`, where not calling `next` settles the call, calling it with a
rebuilt `op` rewrites the input, and transforming its return rewrites the
output. Converting would delete four duplications and add one capability kmet
cannot express today (§ What it buys).

### What is there today

| site | what it is |
|---|---|
| `loop/driver.clj:38-60` | `extension-before-tool-call` — manual `loop/recur` threading `:args` through hooks, first `:block` wins |
| `loop/driver.clj:62-77` | `extension-after-tool-call` — `reduce` with a per-hook `try/catch` and a key-by-key result merge |
| `permissions.clj:251-268` | `gate` — "run the hook, then judge the *effective* args"; exists only because the hook shape cannot express judge-then-run |
| `loop.clj:1121-1124` | the same `(gate (fn [ctx] …@(:before-tool-call agent)…))` pair built a **second time** for the `run_code` sandbox, behind a thunk whose only job is re-reading the atom |
| `tools/invoke.clj:71,131` | `prepare-tool-call` / `finish-tool-call` — the one pipeline both paths share (loop batches and the sandbox bridge, `tools/run_code.cljc:854-880`) |

So the pipeline is already shared; only the chain and the gate are duplicated.

### The shape

```clojure
(fn [op next] ...)   ; op = {:tool-name :args :tool-call-id :assistant-message}
                     ; next takes a rebuilt op, returns the result map
```

- do not call `next` → settle the call
- `(next (assoc op :args rewritten))` → today's before-rewrite
- `(next op)` then transform the return → today's after-rewrite
- both sides → wrap (timing, retry, cache)

Keep `{:content :is-error :terminate :details}` as the point's result
conventions so the loop's handling is unchanged and every migrated extension
hook is a two-line change. `:terminate` stays a *batch* signal; the per-call
chain does not replace it.

The chain becomes one value built once per run and shared by the loop and the
sandbox — nodecode's actual lesson: one chain per name, one dispatch
(`call-hooked`). `state.clj:207`'s two atoms collapse to one chain fn.

### Constraints

1. **The gate is pinned, not an element.** The policy today judges the args the
   hooks produced. If it became just another chain element, a later-registered
   hook could rewrite args *after* the judge — a permission bypass. The chain
   must sit **outside** the gate, with `next` descending through `check`
   immediately before the body: `chain → gate → execute`. This is the one
   structural requirement of the conversion; it also lets `loop.clj:1121` go.
2. **Fail-closed (decided, § A2).** nodecode's `call-isolated`
   (`hook.lisp:183-201`) swallows a hook's own error, steps over it and stands
   it on the board — for a guard that is fail-open: a crashing veto lets the
   write through. kmet settles the call as an error instead, and the rule needs
   two clauses because the around shape has two failure positions:
   - **a throw before `next`** → the call is settled unexecuted with
     `{:content "tool hook error: …" :is-error true}` (today's
     `prepare-tool-call` behaviour);
   - **a throw after `next`** (`next` already ran) → the result is replaced by
     `{:content "tool hook error: …" :is-error true}` (today's
     `finish-tool-call` behaviour). The tool ran; the model is told the call
     failed rather than handed a silently unmodified result.

   An exception raised *inside* `next` is the operation's own and stays
   untouched — `execute-tool-call` already turns a throwing tool into an error
   result, and that is not a hook failure. `:terminate` is never set by a
   failure; it stays the mark of a deliberate block. The cost of fail-closed is
   that a persistently broken hook blocks every call — acceptable because the
   error names the hook and reaches the model, and the escape hatch (disable
   after repeated failure) is the separate, later change nodecode's idea is
   worth here.
3. **Preparation stays sequential, execution concurrent.** The chain runs
   inside each call's future, where `finish-tool-call!` runs today;
   `:tool-execution-start` / `:tool-execution-prepared` keep firing in the
   sequential prepare phase for pi parity and UI ordering.
4. **Batch scope is not call scope.** `has-sequential-tool-call?` is a rule
   over the whole batch. A per-call chain cannot express "any write in this
   batch" — if that ever becomes a hook it is a separate `:tool-batch` point.
5. **Events stay events.** `:tool-execution-*` belong to the front end, not to
   hooks.

### What it buys

- **One concern, one function.** `extensions/clojure/src/kmet/extensions/clojure/paren_repair.clj:146,163`
  and `extensions/tree-sitter/src/kmet/extensions/tree_sitter/hooks.clj:69,~90`
  each implement the same *pair* — veto a `write` whose content does not parse,
  warn when an `edit` left a file that does not parse — duplicating
  `write-path` / content extraction across two callbacks. That pair is one
  around filter.
- **Cross-side state without an atom.** "If the result is too large, retry
  once with narrower args" needs `:tool-call-id`-keyed state today; around it
  is a `let`.
- **A capability that does not exist:** settle **success** without executing
  (cache hit, dry run, stub). `invoke/blocked-result` can only produce
  `{:is-error true}` with the reason text, so a mocking or dry-run tool hook is
  impossible by construction.
- **Timing.** Before and after cannot share a clock; the loop emits its own
  durations, an extension cannot measure one call on its own.

### Options considered (decided: A)

`:on-tool-call` / `:on-tool-result` are the published extension API
(`extension.clj:402`, `docs/extensions.md`,
`modules/app/src/kmet/extension.md`) and pi parity (pi's `beforeToolCall` /
`afterToolCall`).

| | shape | cost | verdict |
|---|---|---|---|
| **A. Break** | one `:on-tool` around registration | migrate `paren_repair` and `tree_sitter` in the same change; record the divergence in `docs/pi-alignment.md`; name the break in the commit (the project's pre-1.0 rule: no shims, fix the in-repo consumers) | **chosen** |
| **B. Adapters** | around chain + compile the old pair into it | zero extension churn, but two documented ways to do one thing — the erosion the rules warn about | rejected |
| **C. Don't convert** | extract only `around-chain` over the existing hook protocol | deletes the two hand-rolled loops, no ABI change, no new capability | rejected: the two capabilities are wanted |

A is taken for the two things the pre/post split cannot express: **settling a
call successfully without executing it** (cache hit, dry run, stub — `:block`
can only produce `{:is-error true}`), and **state that spans both sides of one
call** (timing, retry-with-narrower-args, a dedup switch — a `let` instead of a
`:tool-call-id`-keyed atom). The line-count saving alone would not have
justified the break.

### Steps

1. **The primitive.** `kmet.app.hooks/around` — ~15 lines, pure:
   `(reduce (fn [next h] (fn [op] (h op next))) identity fns)`, registration
   order, first registered outermost (stated in the docstring).
2. **Pin the gate.** Move the policy check out of `permissions/gate`'s
   composition and into the innermost `next` of the tool point:
   `chain → gate → execute`. Delete the `(gate (fn [ctx] …@(:before-tool-call
   agent)…))` pair at `loop.clj:1121-1124` and pass the same chain the loop
   builds.
3. **The point.** `loop/tool_exec.clj`: one chain value per run
   (`loop/state.clj:207`'s two atoms collapse to one), `op` =
   `{:tool-name :args :tool-call-id :assistant-message}`, result conventions
   unchanged (`{:content :is-error :terminate :details}`). The chain runs
   inside each call's future, where `finish-tool-call!` runs today;
   `:tool-execution-start` / `-prepared` stay in the sequential prepare phase.
4. **Collapse the pipeline.** `tools/invoke.clj`'s `prepare-tool-call` /
   `finish-tool-call` become "run the chain"; the sandbox bridge
   (`tools/run_code.cljc:854-880`) follows because it calls the same two fns.
5. **Migrate the consumers.** `extensions/clojure/…/paren_repair.clj` and
   `extensions/tree-sitter/…/hooks.clj` each become one function
   (veto a `write` that will not parse; warn when an `edit` left one), which
   also removes the duplicated `write-path` / content extraction across the
   two callbacks.
6. **Docs and the break.** One `:on-tool` entry in `kmet.extension`;
   `modules/app/src/kmet/extension.md` and `docs/extensions.md` rewritten;
   `docs/pi-alignment.md` records the deliberate divergence from pi's
   `beforeToolCall` / `afterToolCall`; the commit body names the surface that
   moved.

### Delta

Roughly: `kmet.app.hooks/around` ~15 lines in; `tool_exec.clj` ≈40 deleted,
`driver.clj` ≈40, `invoke.clj` ≈30, `loop.clj:1121-1124` deleted, and
`state.clj`'s two atoms become one — about 95 lines net out before the two
extensions shrink (each pair becomes one function).

### Test plan

- `modules/app/test/kmet/app/loop/test_tools.clj` — ~10 deftests assert the
  two-hook semantics (block, rewrite args, terminate, mixed batch, hook throws,
  after-rewrite, after-sets-error, after-throws, parallel after-settles-once):
  rewrite each to the around shape and keep one test per original guarantee.
- `modules/app/test/kmet/app/test_session_runtime.clj:490-510` — builds the
  hook pair by hand; switch to the chain fn.
- `extensions/clojure/test/kmet/extensions/clojure/paren_repair_test.clj` —
  the hook unit tests call `on-tool-call` / `on-tool-result` directly; they
  become one function's tests.
- New: a gate-bypass test — a hook registered after the gate attempts to change
  `:args` and the policy verdict must still apply to the executed call. This is
  the test that pins constraint 1.
- New: a settle-success test — a chain that returns `{:content …}` without
  calling `next` leaves the body unexecuted and appends a normal result.
- New: fail-closed, both positions. A hook that throws before `next` leaves the
  body unexecuted and settles `{:is-error true}` naming the hook; a hook that
  throws after `next` keeps the tool's side effects but replaces the result
  with `{:is-error true}`; a throw from the *tool* still produces the ordinary
  `Error executing <tool>: …` result and is not reported as a hook failure;
  neither case sets `:terminate`.
- New: a consumer test — `paren_repair`'s single function still blocks an
  unparseable `write` and still warns on an unparseable `edit` result, i.e.
  the two migrated extensions keep their behaviour across the break.

Run: `bb test-changed`, `bb test-ext-changed`, `bb lint-changed`,
`bb format-check-changed` (the extensions tree needs `test-ext`).

### Decisions

**A1 — A, B or C (decided: A).** The two capabilities are wanted; B is
rejected; C was the fallback if they were not.

**A2 — error policy (decided: fail-closed).** Two clauses, one per failure
position in the around shape — throw before `next` settles the call
unexecuted, throw after `next` replaces an already-produced result — each as
`{:content "tool hook error: …" :is-error true}`. Deliberately *not* nodecode's
`call-isolated`, which swallows the hook's error and continues: a guard that
crashes must not let the call through. The disable-after-repeated-failure
report stays a separate, later change.

**A3 — where the chain lives (decided: `kmet.app.hooks`).** New namespace,
pure, no deps: `kmet.app.hooks/around` for the primitive. The registration
lists stay in `kmet.app.extensions.core` where they already are, and the tool
point lives in `kmet.app.loop.tool-exec`. `kmet.libs` is rejected unless a
non-app caller needs the primitive.

## Turn markers in the session and the tree

Status: decided. Independent of any context strategy — extracted from the
removed eviction track (§ Order across the token tracks), which needed turn
boundaries and found the log could not supply them.

### Why

The entry stream cannot say where turns begin. `inject-queued-messages!`
(`loop/state.clj:619-637`) appends a `:user` entry for every steering message
with nothing on it distinguishing it from a typed prompt. The loop knows; the
log does not.

Two consequences, and only the first is cosmetic:

- the tree selector has no turn boundary to draw, so a branch's shape reads as
  an undifferentiated run of entries;
- no turn-granular mechanism can be built on the log without guessing — a
  strategy, a per-turn budget, a per-turn report. Inferring "a `:user` entry
  starts a turn" is wrong for steering injections, which join a live turn.
  The pruner already needs this boundary to leave the newest turn alone
  (**R4**).

So record it. The format already carries pure state roles (`:model-change`,
`:thinking-level-change`, `:label`, `:context-edit`) and `context-messages`
projects unknown roles to `[]`.

### The marker

```clojure
{… :role :turn-start
    :turn-id "<uuid>"        ; branch-safe identity — never a counter
    :reason :prompt | :continue}
```

- **Written before the user message, by the same writer**, so `append-entry`'s
  `:parent-id` = leaf (`session/store.clj:88-89`) chains them. One writer, one
  pair, no second path racing the loop's appends.
- **Steering and follow-up injections write nothing** while they join the live
  turn; a `continue` that starts a new run writes one — pi's own turn semantics
  rather than an approximation of it.
- **The ordinal is computed from position.** A stored counter drifts the moment
  two branches fork from one turn: the tree counts `:turn-start` ancestors on
  the current path and renders `── turn N ──`.
- **A compaction cut may land on a marker, and the marker survives it**:
  `context-entries` keeps `first-kept-id`'s entry (`session/context.clj:102-119`),
  so a marker at the kept boundary stays in the context vector while projecting
  to nothing — a later turn-granular caller still sees where turns begin.
- **The unmarked prefix is one turn.** No migration, no heuristic: nothing
  before the first marker is a boundary, and the next prompt writes one. A
  session file written before the role existed loads (the strict pass reports
  and skips it), so the prefix is simply one long turn.
- **One surface shows it, two do not.** The tree selector renders the
  separator; the chat replay skips it (the transcript is messages) and the
  projection never sends it. Without the selector clause it would fall through
  to a `[:turn-start]` row, which is why the clause is not optional.
- **Branching needs no special case.** Markers are ordinary parents, so fork,
  resume and tree navigation carry them; a branch point inside a turn splits
  that turn, which is the correct answer for that branch.

Cost — one schema clause, one separator, one skip:

| site | today, for an unknown role | action |
|---|---|---|
| `session/schema.clj:83` | reported and skipped, strict load pass only (`--debug`) | add the clause |
| `session_admin.clj:274` | `(replay-plain! cs e)` → marker rendered as a chat row | **skip clause**: the transcript is messages, not markers |
| `tree_selector.clj:169-217` `entry-display-text` | falls through to the default `[:turn-start]` | **render the `turn N` separator** (decided) |
| `tree_selector.clj:222` | default `nil`, no copy text | none — a separator is not copyable |
| `tree_selector.clj:267` | adds nothing to the search haystack | none |
| `session/context.clj:140` | default `[]` | none |
| `session/stats.clj:175` | `:else "unknown"` bucket | audit so a marker is not counted as a model |

### Steps

1. **The role.** `:turn-start` in `session/schema.clj`; `store/append-entry`
   carries it like the other state roles.
2. **The writer.** The prompt path writes one before the user message
   (`:reason :prompt`); the `continue` path writes one when a new run starts
   (`:reason :continue`); `inject-queued-messages!` writes none.
3. **The skip.** `session_admin.clj:274`.
4. **The separator.** `tree_selector.clj:169-217`, ordinal counted on the path.
5. **The audit.** `session/stats.clj:175`, so a marker never lands in the
   per-model bucket.
6. Docs: `docs/architecture.md`'s session-entry list (the roles with no wire
   projection).

### Test plan

- A prompt writes exactly one `:turn-start` whose `:parent-id` is the previous
  leaf and whose next entry is the user message.
- A steering injection writes none; a `continue` that starts a new run writes
  one.
- A marker projects to no wire message (`session/build-context-messages`), adds
  no chat row, and renders as `turn N` in the tree selector with N counted from
  its ancestors — two branches from one turn both read `turn N`, and the next
  marker on each reads `turn N+1`.
- A session file written before the role existed still loads with the strict
  pass off and its unmarked prefix behaves as one turn.
- A compaction or context edit that lands a marker at the kept boundary leaves
  it in `context-entries`.
- Stats: two sessions, one with markers, produce identical per-model totals.

Run: `bb test-changed`, `bb lint-changed`, `bb format-check-changed`.

### Decisions

**T1 — record the turn rather than infer it.** A `:user` entry is not a turn
boundary (steering injects one), and `drop-incomplete-tool-calls`
(`loop/completion.clj`) stays the defensive net it is today, not the
correctness argument.

**T2 — one writer, chained.** The marker and the user message come from the
same call, so `:parent-id` chaining is a property of the append, not of a
second path racing it.

**T3 — the ordinal is computed, never stored.** Position on the current path;
a stored counter would drift across forks.

**T4 — invisible to the model.** The marker is a log fact for the tree and for
future turn-granular callers; it is never a wire message and never a chat row.

**T5 — no migration.** An unmarked prefix is one turn; the next prompt starts
the first real boundary.

## Retaining less: the tool-result pruner (**R**)

Status: plan, decisions closed (R1–R7). Depends on **M** for an honest
re-measurement.
Reference: dsh `compaction-tool-result-pruner`
(`~/src/cvstree/deepseek-harness/packages/compaction/compaction-tool-result-pruner/`).

### Why

kmet's only tool-output policy runs at capture, and only in `bash`:
`truncate-and-persist!` (`tools/bash_executor.clj:459`) keeps the tail, spills
the rest to a temp file and appends the footer. Every other result is retained
whole for the life of the entry:

| result | bound today |
|---|---|
| `read` | 2000 lines / 50 KiB per call (`tools/read.clj:375`), then retained — ≈12k tokens for one legitimate call |
| `run_code` | the *reported* output is tail-bounded (`bash-output/truncate-tail`); the value it returns is not |
| extension tools | nothing |
| images on any tool result | nothing, and not even counted (`estimate-tokens`, `compaction/core.clj:54`, counts text blocks) |

Compaction then pays an LLM call whose input is dominated by exactly those
results, and a summary of a transcript full of dead output is a worse summary.

dsh trims first, with **no model call**, and re-measures: once a trigger
qualifies, every tool result over 8,192 characters becomes its first 4,096 + a
`middle pruned` marker + its last 1,024; if that relieves the pressure, the
summarization call never happens. The full original stays in the log.

### What already exists

| piece | where | what it gives |
|---|---|---|
| the replacement mechanism | `session/context.clj:62` `append-context-edit!` (`nil` omits, `{:content …}` rewrites), applied over the whole projection by `project-entries` (`:217`) | an append-only trim that needs no new role and leaves the log, usage totals and the UI untouched |
| the precedent | `stage.clj:302` `omit-recovery-attempt!` | the exact two-step shape: append the edit, then `state/refresh-context-from-session!` |
| the tool-block shape | `loop/message.clj:34` `tool-result-message` | `:content [{:type :tool_result :tool_use_id … :content str}]`, plus `:images`, `:truncation`, `:details` |
| a trim that keeps pairing | `session/context.clj:182` `replace-message-content` | for a `:tool` entry it rewrites only the `:tool_result` block's text — `:tool_use_id`, `:images` and `:details` survive |
| the unit | `session/store.clj:353` `estimate-result-tokens` — every tool entry is stamped with `:result-tokens` at append (`:369`) | select and report by size with no re-measurement; `/session` already aggregates per tool |
| the trigger | `stage.clj:603` `maybe-compact!` → `:472` `compact-with-session!` → `:390` `prepare-compaction` | one place to prune, before the cut is chosen |
| the re-measurement | `compaction/core.clj:129` `projected-context-tokens` | a context edit newer than the usage anchor already forces a pure estimate |

### Shape

1. One pure fn beside `truncate-tail` (`tools/bash_output.clj:121`):
   `truncate-middle [s {:keys [head tail]}]` → `{:content :trimmed?
   :chars-before :chars-after}` — head + marker + tail, sliced in Unicode code
   points, with `head + marker + tail < threshold` part of the accepted
   configuration so a second pass is a no-op.
2. `compaction/core.clj` grows `prunable [projected opts]` → the entries to
   trim, in projection order, skipping entries that already carry a context
   edit (an omit included), skipping the newest turn, and skipping entries
   under `:prune-threshold-chars`.
3. `stage.clj` grows `prune!`, called from `compact-with-session!` **before**
   `prepare-compaction`, for reasons `:threshold` and `:overflow` only. It
   appends one `context-edit` per entry, oldest first, stopping as soon as
   `projected-context-tokens` falls below the resolved trigger (or the list is
   exhausted), then refreshes the live context and emits one event.
4. Settings resolved through `get-compaction-settings` (`config.clj:662`), so a
   per-model override comes free: `:prune-tool-results` (default `true`),
   `:prune-threshold-chars` `8192`, `:prune-head-chars` `4096`,
   `:prune-tail-chars` `1024`.
5. The model-facing marker is one line, and it must not promise what it does
   not have:

```text
[... 41,802 characters pruned from the middle of this result]
```

   Once **S** has landed the marker carries the spill path instead (`full
   text: <path> — read it with offset/limit`), which is strictly better for
   the model than re-running the tool. Until then it says only "pruned":
   naming a path we did not write is the failure dsh names explicitly.
6. `:tool-results-pruned {:count :chars-before :chars-after :entry-ids}`, and
   `compact-context!`/`maybe-compact!` answer `:pruned` when the trim alone
   relieved the pressure — no `:compaction-start` bracket and no summary. All
   four callers ignore the return value (`loop.clj:431,579,901,914`), so no
   caller changes.

### Steps

1. `truncate-middle` + tests.
2. `prunable` + `prune!`, wired into `compact-with-session!`; `:pruned`
   threaded through `compact-context!` and `maybe-compact!`.
3. The four settings fields.
4. The event, plus the `/compact` and `maybe-compact!` reporting.
5. Docs: `docs/pi-alignment.md` §8 (deviation: pi has no pruner), the
   compaction section of `docs/architecture.md`, `docs/configuration.md` for
   the keys.

### Test plan

- `truncate-middle`: ASCII; multibyte (CJK, emoji) boundaries never split; the
  marker text; the no-growth invariant; a threshold smaller than the marker
  keeps the original.
- `prunable`: order, size filter, skips an edited entry, skips the newest
  turn, honours `:prune-tool-results false`.
- `prune!`: a 60 KiB `read` result in a scripted session drops the measured
  pressure below the trigger; `maybe-compact!` answers `:pruned`; no
  `summarization-call` runs (stub the retry fn and assert it was not called);
  no `:compaction-start` is emitted.
- Idempotence: a second `maybe-compact!` in the same prompt appends no second
  edit.
- Projection: the pruned entry keeps `:tool_use_id`, `:images`, `:details`;
  `session/build-context-messages` shows the marker; the raw branch entry is
  byte-identical (`pr-str` equality) to before the prune.
- Resume: a resumed session projects the trimmed entry, because the edit is a
  durable entry.
- `:manual` never prunes.

Run: `bb test-changed`, `bb lint-changed`, `bb format-check-changed`.

### Pruning is not a relief valve

Pruning runs only when a compaction trigger has **already** qualified — the
boundary where the prefix is going to be rewritten anyway. It adds no break;
when it works it *removes* the summary call, and with it the break the summary
would have caused. **S** never edits history at all: it bounds a result as it
arrives, when the append already changes the prefix. Neither is a per-round
shave: no trigger, no write.

The one cost R accepts is that the model cannot read what was dropped — kmet
has no recall read. The prune notice therefore stays honest (R5), and the
newest turn is never touched (R4).

### Measured: what actually forces a compaction

288 parseable session files in `~/.kmet/sessions`, 22 with real `:compaction`
entries, 44 compaction windows, 7.09 M wire chars. Composition of the
projection **at the moment compaction ran**:

| component | chars | share |
|---|---|---|
| assistant thinking (re-sent every request) | 3,299,491 | **47%** |
| tool results | 1,835,312 | **26%** |
| tool-call arguments (re-sent every request) | 1,379,831 | **19%** |
| assistant text | 329,731 | 5% |
| previous summary | 243,937 | 3% |
| user text | 2,489 | 0.03% |

Windows run 148–260 KB (≈40–65k chars/4 tokens); per-session mixes vary (one
session was 91% tool output, another 64%). So R addresses roughly a quarter of
the pressure at the trigger — it is the cheapest lens, not the largest, and
this is the number that a "one big result forces a summary" argument would have
been wrong about.

**Declined: eliding old thinking and old tool-call arguments (R7).** The two
together are 66% of the same pressure and neither would need a model call, but
both reference harnesses elide reasoning only when the *producing* model is not
the target model, never by age: pi drops every thinking block when
`provider`/`api`/`model` differ and keeps them otherwise
(`packages/ai/src/api/transform-messages.ts:112-142`), dsh's pi-ai adapter
matches that rule, and dsh's native DeepSeek adapter replays prior reasoning
verbatim regardless. Neither rewrites historical tool-call arguments except to
repair malformed JSON (`{}`). Age-based elision would be a departure from
**both** harnesses, and it is the one change in this file with no counterpart in
the reference: R, M, W and S each borrow a dsh technique (tool-result pruner,
token meter, warm-prefix summary, spill policy), so the rule these tracks
follow is "borrow from dsh where pi has nothing" — not "invent past both".

Stated plainly, because it bounds what R is worth: R addresses the 26%
tool-result share only. The 47% thinking and 19% argument shares stay in the
context, so the number of compactions does not fall — what falls is the amount
of work each compaction *replaces*, and W makes the summary call itself mostly
a cache read. The window pressure is reduced only by the summary.

Recorded for whoever revisits it: a `context-edit` cannot clear `:thinking`
today — `replace-message-content` (`session/context.clj:182`) rewrites only
`:content` — so any future field-level elision needs its own durable fact, the
in-flight turn preserved (Anthropic rejects a continuation whose assistant
message lost its thinking), and `:reasoning-content-fill`
(`shared.clj:296-331`) for routes that require the field.

### Decisions

**R1 — prune before the cut, never instead of it.** Trim, then re-measure,
summarize only if still over. The trim is bounded by the trigger, so a
conversation under no pressure is never edited.

**R2 — `:manual` does not prune.** `/compact` means "summarize now"; a verb
that silently did something else first is worse than a slightly more
expensive call.

**R3 — replace through `context-edit`, not a rewrite.** The log, the tree
view, usage totals and every extension event keep the original bytes; only the
projection changes. Same mechanism as `omit-recovery-attempt!`, no schema
change.

**R4 — never prune the newest turn.** kmet has no per-entry notion of "still
being reasoned about", and a result the model just received is the likeliest
to be re-read. The boundary is the newest `:turn-start` marker (the
marker track); until that lands, the last `:user` entry of the projection —
which over-counts a turn whenever a steering message was injected, so the
marker is the correct definition and the stopgap is deliberately the wider
one. The cost of being conservative is one more summarized tail, not a wrong
answer.

**R5 — the marker never names a path unless a file was written.**

**R6 — head and tail, not tail only.** `truncate-tail` exists because a
command's answer is at the end; a read's shape and a search's matches are at
the front. No setting beyond the two sizes.

**R7 — no age-based elision (pi alignment).** Historical `:thinking` and
tool-call arguments stay in the projection. pi drops reasoning only when the
producing model differs from the target (`transform-messages.ts:112-142`, and
kmet mirrors that in `shared.clj:206,276` and `anthropic_messages.clj:120-133`);
dsh's pi-ai adapter does the same and its native DeepSeek adapter replays it
verbatim. The 47% / 19% shares in § Measured are the case *against* this
decision, kept on the record: revisiting it is a deliberate departure from pi,
not a tuning knob.

## The request envelope as one measurement (**M**)

Status: plan, decisions closed (M1–M5). **Land first.**
Reference: dsh `token-meter` (`docs/subsystems/token-meter.md` there) and
`compaction-basic`'s pressure policy.

### Why

Three readers share one name, and only one of them measures a request:

| reader | call | what it counts |
|---|---|---|
| `maybe-compact!` (`stage.clj:633`) | `context-tokens` (`compaction/core.clj:97`) | the latest provider anchor (`assistant-usage-tokens`, `:84`) + chars/4 of the messages after it |
| the footer (`ui/tui/footer_data_provider.clj:171`) | the same | the same |
| `/compact`'s `:estimated-tokens-after` (`stage.clj:354`) | `estimated-tokens` (`:76`) | messages only |

The anchor is honest: `input + cache-read + cache-write` is what the provider
reported for the whole request — system prompt and tool schemas included —
plus its output. Every **estimate** branch is not, and those branches are not
rare:

- the explicit `:compact-token-threshold` path (`stage.clj:638`);
- after any context edit — **R**'s prunes included — `projected-context-tokens`
  (`core.clj:129`) falls back to a whole-projection estimate;
- after a compaction `context-tokens` answers nil until the next response, so
  the next figure is the fallback estimate;
- the footer shows `?/window` until the first response.

What every estimate omits:

- the live system prompt (`state.clj:180`), which carries the project
  instructions — `system_prompt.clj:74` renders **every** `AGENTS.md` that
  `project_context.clj:100` found (agent dir, then cwd → root) with **no size
  bound**;
- the `<available_skills>` catalog (`system_prompt.clj:39`);
- the declared tool schemas, sent on every request (`completion.clj:145`);
- images, on user messages and tool results alike.

Two further facts the same measurement fixes:

- **`:context-window` is captured once**, at state build (`driver.clj:148`).
  `set-model!` (`state.clj:773`) and `cycle-model!` (`:862`) append a
  `:model-change` entry and emit, and no path refreshes the window — so after a
  switch to a smaller model the proactive trigger keeps comparing against the
  old window and the session finds out from a provider overflow.
- **an unbounded `AGENTS.md` is a fixed cost on every request.** 200 KiB of
  instructions ≈ 50k tokens spent before the first message, and no setting can
  bound it today.

### Shape

One pure function, three callers:

```clojure
(kmet.app.compaction.core/envelope-tokens
  {:usage-anchor n-or-nil   ; the provider's measured whole-request total
   :system str              ; the live system prompt
   :tools [tool]            ; the declared set, in the wire shape
   :messages [message]      ; the projected messages
   :images-n 0})            ; occurrences still on the wire
```

- **Anchor present → return it.** Never add overhead to a measurement; that is
  dsh's `baseline.kind: 'usage'` rule and the reason its surface deltas are
  signed rather than re-added.
- **No anchor →** `chars/4` over system + each declaration's JSON + message
  text, plus the image term.
- Tool cost is the **wire bytes**:
  `(kmet.libs.json/generate-string (select-keys tool [:name :description :parameters]))`,
  one per declared tool — not `pr-str`, whose keywords and commas inflate
  unevenly, because this number decides when to compact.
- The image term is the heuristic dsh falls back to when a route declares no
  pricing: `(w*h)/750` when the entry carries dimensions, else a flat 1,100 per
  occurrence — and **zero** for occurrences the request will strip
  (`:block-images`, `loop/message.clj:88`). Overestimating is the safe
  direction.
- `context-tokens` and `projected-context-tokens` keep their anchor rule and
  take the same overhead; `envelope-tokens` is the only implementation.
- Callers: `maybe-compact!`, `fdp-context-tokens`, and `:tokens-before` /
  `:estimated-tokens-after` on the compaction entry. One number in three
  places, which is the point.
- **Model switch:** `set-model!`/`cycle-model!` refresh `:context-window` from
  the destination record (`models/get-model`). No new compaction path.

### Steps

1. `envelope-tokens` + both estimators delegating to it; switch the footer and
   `maybe-compact!` in the same change, so no window ever shows two numbers.
2. Tool cost (one fn over `state/active-tools`, `state.clj:481`) + a test that
   it follows a tool add/remove.
3. The image term in `estimate-tokens`.
4. Window refresh on both switch paths + the test that a smaller window
   triggers compaction on the next run.
5. `:context-files-max-bytes` (default `65536`) and the budget in
   `format-project-context` (`system_prompt.clj:74`): keep the most specific
   files in list order, truncate the last kept one, drop the rest, render one
   `Workspace instruction budget …` notice naming what was omitted and
   truncated. Applies on `/reload` too — that path passes `:context-files`
   from the same loader (`builtins.clj:692`).
6. Docs: `docs/configuration.md` (the key), `docs/pi-alignment.md` (deviation:
   pi does not bound project instructions).

### Test plan

- `envelope-tokens`: an anchor wins unchanged; no anchor adds system + tools +
  messages; a 60 KiB AGENTS.md moves the number by ≈15k; a large `:parameters`
  schema moves it; `:block-images` on drops the image term; two calls are
  `pr-str`-equal.
- One test over the same projection: the footer figure and `maybe-compact!`'s
  reading cross the threshold on the same turn.
- Model switch: after `set-model!` to a model with a smaller `:context-window`,
  `maybe-compact!` compacts before the next request and `:cfg`'s window equals
  the destination's.
- Instruction budget: fitting files render byte-identically; over budget drops
  the broadest first, truncates the last kept file, and the notice names the
  paths; an empty chain contributes no frame; `/reload` applies the same
  budget.
- `:estimated-tokens-after` equals the envelope of the post-compaction
  projection.

### Decisions

**M1 — one function, three callers.** A second estimator is how the footer and
the trigger drift apart.

**M2 — an anchor is never adjusted.** It measures the whole request; adding
overhead double-counts it.

**M3 — tool cost from the wire shape.** Compaction decisions should be made on
what the provider bills.

**M4 — no pre-request destination check.** `maybe-compact!` already runs
immediately before a run; refreshing the window so that trigger sees the
destination is the whole fix. A per-request check inside `completion.clj`
would duplicate the abort/retry machinery for a case the pre-run trigger
covers.

**M5 — one instruction chain, one budget.** dsh splits user-global from project
because it loads both; kmet has one chain (agent dir + project walk), so one
`maxBytes` with "drop the broadest first" is the whole rule.

## Warm-prefix summarization (**W**)

Status: plan, decisions closed (W1–W5). Depends on the caching track's **P0**
and **P1** (the markers) and on **M** (the measurement that proves the saving).
Reference: dsh `compaction-basic` § "Summarization reuses the provider's warm
prefix".

### Why

`summarize!` (`stage.clj:206`) builds `core/summarization-messages`
(`core.clj:437`): a **fresh** fixed `summarization-system-prompt`, plus one
`user` message whose text is `<conversation>` around
`serialize-conversation` of the pre-cut messages (`core.clj:417`, `:290`). The
call also passes `:cache-retention :none` (`stage.clj:135`).

So the largest call a session ever makes is guaranteed to be a **cold
prefix**: the whole transcript is billed at full input (or cache-write) rate,
on exactly the sessions where the history is long. kmet already measures the
damage — `detect-cache-miss` (`session/context.clj:251`) and the `R`/`W`
columns in the export (`session/export.clj:287`) — and today it is simply
never spent on the summary.

dsh replays the live system prompt, the last request's tools and the
shadowed-region messages byte-for-byte and appends only the instruction, so
the provider's warm prefix is reused up to the instruction; only the
instruction and the summary output are uncached.

### What already exists

| piece | where | what it gives |
|---|---|---|
| the pre-cut messages | `compaction/core.clj:222` `prepare` → `:messages` | exactly the messages about to be shadowed (system messages removed) |
| the request shape to copy | `loop/completion.clj:113-145` | `[system] + messages` plus `:tools request-tools` — the prefix a normal call sends |
| the provider anchor | `kmet.ai.llm/send-message` `:cache-retention` | a per-call retention, which the caching track's **P3** resolves once |
| the proof | `session/context.clj:251` `detect-cache-miss` | a before/after number from a real session |
| the deviation record | `docs/pi-alignment.md:516` §8 | where a departure from pi is stated |

### Shape

1. **Two lists from one projection.** `prep :messages` stays as the
   *serializer* input (pi parity — `branch-summary-messages` still needs it).
   A new `prep :replay-messages` is the same projection sliced at the cut
   instead of filtered: `system` + `projection[0..cut)`. It must include two
   things `:messages` drops today:
   - the previous compaction's summary message (`core.clj:252` skips
     `:compaction` sources), because that message **is** in the context and a
     prefix missing it is not a prefix;
   - any in-history system message, in position (kmet appends none today;
     keeping them costs one `remove` and protects the invariant if an
     extension ever adds one).
2. `summarization-call` gains the shape: `:messages (into [{:role :system …}]
   replay-messages)` + the instruction as the final `:user` message + `:tools
   (state/active-tools agent)`. The instruction is the existing prompt text
   with its `<conversation>` wrapper gone, and **without** the
   `<previous-summary>` block when the summary already rides the prefix (W2).
3. Retention becomes the run's effective value instead of `:none` (W3), and
   the breakpoints land at the **end of the replayed prefix** — system, last
   tool, last replayed message — never after the instruction, so the read
   covers the reused bytes and the new tail is the only write.
4. Branch summaries keep the cold path (`stage.clj:245`
   `generate-branch-summary`) — they summarize entries the provider never saw,
   so there is no warm prefix to reuse (W5).
5. Delete the cold compaction path rather than keeping it behind a flag (W4):
   the request shape is an ordinary request, so a provider without caching
   simply pays what it paid before.

### Steps

1. `prepare` returns `:replay-messages` (same projection, different slice) and
   `:session-before-compact` carries it, so an extension sees both.
2. `summarize!` builds the replay request; `core/summarization-messages` and
   `summarization-request` go away; `serialize-conversation` stays (branch
   summaries use it).
3. Retention + breakpoint placement, after **P0**/**P1**.
4. **Narrow the caching track's P3 bullet**: "summarization already does
   `:none`" becomes "branch summaries do; compaction reuses a prefix and must
   not" — the P3 rationale was written when the summarization prefix was cold.
5. `docs/pi-alignment.md` §8 gains the deviation row, with the before/after
   cache numbers from one real session.

### Test plan

- `:replay-messages`: system first; the previous compaction's message present;
  a split-turn cut still ends at a legal message boundary; equal to the last
  request's list minus the retained tail on a scripted conversation.
- `summarize!` request: one system message, no `<conversation>` wrapper, the
  instruction last, `:tools` equal to the active set, retention ≠ `:none`.
- A second compaction: the summary message is in the replayed prefix and the
  instruction does not restate it.
- Branch summary unchanged: `:none`, serialized, its own system prompt.
- Byte stability: two consecutive payload builds from the same messages are
  `pr-str`-equal up to the instruction, so the breakpoint claim holds.
- Usage: the compaction entry's `:usage` reports a positive `:cache-read` on a
  replayed prefix (a stub transport asserting the request carries the same
  prefix bytes is the unit-level version of this).

### Decisions

**W1 — replay the projection, do not re-serialize.** The serialized
`<conversation>` is a second, differently-shaped rendering of the same bytes,
and a prefix match on it is impossible by construction.

**W2 — the previous summary rides the prefix; the update instruction does not
restate it.** Otherwise the summary is paid twice in one request.

**W3 — breakpoints at the end of the replayed prefix.** pi's placement rule
(system + last tool + last user message) is written for a request whose last
user message is the conversation; here it is the instruction, which is the
part we want uncached.

**W4 — delete the cold compaction path.** No flag, no second spelling; a
non-caching provider gets the same request shape and pays the same input rate
it always did.

**W5 — branch summaries stay cold.** They summarize an abandoned branch the
provider never received, so there is nothing to reuse.

## Per-result retention with a locator (**S**)

Status: plan, one decision open (**S6**: the default budget). Depends on **R**'s
`truncate-middle`.
Reference: dsh `spill-policy` + `spill-local` (`docs/subsystems/spill.md`
there).

### Why

`bash` is the only tool that leaves a retrievable copy, and that copy is a
`delete-on-exit` temp file (`tools/bash_executor.clj:38` `create-temp-file`:
`TMPDIR` + `fs/delete-on-exit`), so a resumed session's locator names a dead
path. Everything else either truncates with no path or has no bound:

| tool | today |
|---|---|
| `read` | 2000 lines / 50 KiB + a pagination footer (`tools/read.clj:375`); the model can page, but a 50 KiB first read is already in history |
| `run_code` | the reported output is tail-bounded; the returned value is not |
| extension tools | nothing (`tools/invoke.clj:131` `finish-tool-call` may rewrite the result but does not bound it) |
| `bash` | tail-bounded + a temp-file locator that dies with the process |

dsh applies one per-result budget to text and images alike: over budget, the
model keeps ordered head/tail halves, the complete text is written to durable
storage, and the result carries a locator with retrieval guidance. Two rules
worth copying verbatim: a notice-only result is allowed, and if the notice
itself cannot fit the budget the **original stays** — never substitute an
unreadable path.

### Shape

1. A `kmet.app.tools.retention` namespace (pure + `babashka.fs`): `head-tail`
   (R's `truncate-middle`), `spill!` (write once, return the path) and
   `notice` (the model-facing text). One implementation, two consumers (R and
   this policy).
2. **Path:** `<dir of the session file>/spill/<tool>-<call-id>.txt`. The
   session file is `(:file sess)` (`session/store.clj:170`) under
   `~/.kmet/sessions/--cwd--/`, and the loader only reads `*.ednl` entries, so
   a `spill/` directory beside them is invisible to session listings. Fallback
   when a session has no file yet: `<agent-dir>/spill/…` (`config.clj:310`).
   Never `TMPDIR` — Termux has no `/tmp` (AGENTS.md).
3. **One application point:** a `tool-exec/append-tool-result!` helper used by
   the three sites that build a `:tool` entry (`tool_exec.clj:226`, `:253`,
   `loop.clj:564`), where `agent` is in scope. Applied once at entry creation,
   not in a projection — a policy that runs per projection would re-decide on
   every request and could not be idempotent.
4. **Exempt:** `read` (it is the retrieval mechanism and already paginates),
   `bash` (already bounded at capture; only its file moves — step 5), results
   already marked spilled in `:details`.
5. `bash`'s spill file moves under the same durable directory and drops
   `delete-on-exit`; its footer text comes from `notice`, so the model reads
   one wording for one idea. (Its streaming spill stays: the file exists
   because the output was never fully buffered.)
6. **Images are out of scope** until an attachment store exists: kmet embeds
   base64 in `:images` (`tools/read.clj:227` `image-block`) and has no path to
   name. The text budget ships now; the image budget becomes possible with the
   same storage change **S4** describes.
7. `:truncation` (`loop/message.clj:34` already carries it, and the TUI
   renders it) gains `:spill-path`, so a tool card can reveal the file without
   a new field.

### Steps

1. `retention` namespace: `spill!` + `notice` + a test with a fake session
   directory.
2. `append-tool-result!` and the three call sites.
3. The budget: `:tool-result-max-tokens` (S6) read from the live config at
   call time (it is not a compaction setting).
4. Wording: one notice, with retrieval guidance kmet can honour — `read` with
   `offset`/`limit`, or `grep` through `bash`.
5. `bash`'s file relocation + the shared footer.
6. Docs: `docs/pi-alignment.md` deviation, `docs/configuration.md`.

### Test plan

- Under budget: the entry is byte-identical (`pr-str` equality).
- Over budget: head and tail present, notice within budget, one file written,
  the path reads back through the `read` tool, and the text shrinks.
- A notice that cannot fit (tiny budget): the original stays.
- Spill failure (unwritable dir): `debug/log` a warning and keep the original —
  never a dead path.
- Idempotence: an already-spilled result is not spilled twice; the parallel
  path and the repeat-guard path (`loop.clj:564`) produce the same entry.
- Resume: the locator still resolves after a process restart.
- Exempt: `read` and `bash` results pass through the generic policy untouched.

### Decisions

**S1 — durable session-dir spill, never `TMPDIR`.** A locator the model cannot
use after a restart is worse than no locator.

**S2 — one entry-creation point, not a projection filter.** The policy decides
once, on the bytes that become the entry.

**S3 — exempt `read`, `bash` and already-spilled results.** `read` is the
retrieval path; `bash` is bounded at capture; re-spilling a spill is a loop.

**S4 — images wait for an attachment store.** The notice names paths that
exist; until images have paths, they are not spilled.

**S5 — fail open.** Any storage failure keeps the original text.

**S6 — open: the default budget.** dsh's example is `maxInlineTokens: 12500`,
chosen for its context windows. kmet's catalog spans 4k–192k windows (most
commonly 8k–131k), and `:compact-reserve-tokens` alone defaults to 16384 — a
flat 12,500 would not fit a 16k window, which is why the default should be
window-relative. Candidates: `(min 12500 (quot (or window 131072) 16))`; or a
fraction of `contextWindow - reserve`; or a flat 12,500 with a floor that
disables retention below some window. Needs one measured session before
choosing; the setting ships either way.

## Order across the token tracks

**M → R → W → S.**

- **M** first: R's re-measurement is a lie without it (the pure-estimate path
  ignores the system prompt and the schemas, so a pruned history can still
  overflow), and `/compact`'s reported numbers are wrong today.
- **R** second: it needs only M plus the existing `context-edit` machinery, and
  it makes W cheaper as well as rarer (a pruned history is a smaller summary
  request).
- **W** third: it needs the caching track's **P0**/**P1** landed, or the
  replayed prefix carries no breakpoint and the cache is never read. Its
  payoff is measurable with `detect-cache-miss` and the export's `R` column.
- **S** last: its payoff is per-call rather than per-turn, and it consumes R's
  primitive.

Each track is independently shippable: M alone fixes the numbers, R alone
avoids summaries, W alone cuts the summary call's input, S alone bounds one
large result.

One cross-track edit is part of these plans, not a follow-up: the caching
track's **P3** bullet about summarization no longer sending `:cache-retention
:none` is narrowed to branch summaries (W, step 4).

The **turn-marker** track is independent of all four: it neither reads nor
changes a projection, and it is what any future turn-granular mechanism would
build on rather than infer. **R** is its first consumer (the newest-turn
boundary, **R4**), with the last `:user` entry as a stopgap if R lands first.

The eviction strategy (nodecode's cut-whole-turns + recall) was removed from
this file after the measurement in § R: the context that forces a compaction is
47% assistant thinking, 26% tool results and 19% tool-call arguments, so a
mechanism whose distinctive value is preserving conversation verbatim
(5% assistant text, 0.03% user text) while dropping turns wholesale was aimed
at the smallest component, at the largest build cost, and needed a recall read
that does not exist. The measured facts and the design record stay in git
history; a future proposal should start from that table.

Age-based elision of that 47% and 19% was declined for pi alignment (**R7**):
pi and dsh elide reasoning on a model change, never by age. The measurement
stays next to the decision so the cost of that alignment is not forgotten.

Pi parity note: all four are deviations from pi, which has none of them
(pi's compaction summarizes a serialized transcript, its tools bound output at
capture, and it has no request-envelope estimate). Each lands with a row in
`docs/pi-alignment.md` §8 and a sentence in the commit body naming the
departure.
