# Todo

Four tracks, in the order to take them. All independent; each is a
request/ABI-level change with unit-testable surfaces. The context and token
tracks that used to close this file are dropped — see § Deferred.

| track | what it fixes | status |
|---|---|---|
| **`github-copilot` anthropic auth** | every `github-copilot/claude-*` model failed with `400 missing required Authorization header` — the anthropic wire sent `x-api-key` to an endpoint that only accepts `Authorization: Bearer` | **landed** (oauth marker → Bearer; verified live) |
| Prompt-caching parity with pi | kmet's wires never ask for prompt caching (except responses/mistral/bedrock), so cache-read sits at 0; the tool ranker serves MCP only, no source's tools can be declared, and a tool-set change re-sends the whole array | **P0 landed** (anthropic breakpoints + 1h pricing); P1–P6 planned, decisions closed |
| Around tool hooks | two hand-rolled hook chains + a doubled permission gate; no way to settle a call successfully | decided: **A** — one `:on-tool` around registration |
| Persistent eval and eval sources | `run_code` throws its context away per call, so a helper the model writes is re-pasted in full on every turn; extensions can contribute tools but not a definition the model can call from code | decisions closed (E1–E16); independent |

## `github-copilot`: its anthropic models cannot authenticate

**Landed and verified live.** Every `github-copilot/claude-*` model failed with
`400 missing required Authorization header`: the anthropic wire sent
`x-api-key` to Copilot's bearer-only `/v1/messages`. `resolve-provider-auth`'s
oauth branch now carries `:oauth true`, and `anthropic-auth-headers` sends
`Authorization: Bearer` for that marker (`auth.clj` + `anthropic_messages.clj`).
The distinction is where the token came from, not which provider it is: pi
routes Copilot's anthropic requests to Bearer in its client builder and detects
Anthropic's `sk-ant-oat` oauth tokens by string; kmet knows the credential's
origin, so the marker is provenance-based and covers any oauth login on this
wire.

Verified: `github-copilot/claude-haiku-4.5` streams through `llm/send-message`
(previously the 400), and the same raw request returns 200 with
`Authorization: Bearer` where `x-api-key` returns 400. Tests pin the marker
(`test_auth.clj`), the bearer header (`test_llm_anthropic.clj`) and the
resolution shape (`test_oauth.clj`).

## Prompt-caching parity with pi

Status: plan, decisions closed. **P0 landed and verified against the live API**
(anthropic breakpoints, the usage merge it uncovered, the 1h cache-write
pricing, and D2's corrected resolution seam). Next: P1.
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

**Done, and verified against the live API.** `anthropic-compat`,
`anthropic-cache-control`, `anthropic-payload` (system + last tool + last user
block) and `anthropic-affinity-headers` are in; `kmet.ai.usage` carries
`:cache-write-1h` and `models/calculate-cost` prices it at 2x base input.

Live check — `bb scripts/live_cache_check.bb` (three requests, claude-haiku-4.5,
~15.6k-token prefix; payload and stream handling are kmet's, only the socket is
curl):

| round | retention | cache_creation | cache_read | 1h share |
|---|---|---|---|---|
| 1, cold prefix | `:short` | 15626 | 0 | 0 |
| 2, same prefix | `:short` | 14 | **15626** | 0 |
| 3, cold prefix | `:long` | 15628 | 0 | **15628** |

Round 3's cost came out at 15628 × 2 × input, so the 1h pricing is right too.

Notes from doing it:

- The payload builder takes `model-record` (not `provider-record`) and reads
  `(:provider model-record)` for the replay context — the same value
  `(:id provider-record)` carried, and the shape `openai-payload` uses.
- **The usage gap this found is fixed in the same pass** (pi accumulateUsage):
  `parse-anthropic-event` now reports `message_delta`'s usage,
  `merge-anthropic-usage` folds it field-wise over `message_start`'s (a field
  the delta omits is preserved — proxies drop `input_tokens`, and a gateway may
  report cache usage only there), and `anthropic-events-handler` emits the
  merged map once per usage-bearing chunk. Live: two reports per request, the
  last carrying the delta's real `output_tokens` (4) instead of `message_start`'s
  placeholder 1.
- **Found here, since fixed:** `github-copilot`'s anthropic models could not
  authenticate (bearer-only endpoint, `x-api-key` sent); see §
  `github-copilot` above.

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

   Exposure is not user-settable: an override belongs to the source, never to
   per-tool settings (§ Tools: cross-session selection in both scopes) — a
   project exposure override could make a `:sandbox` tool un-disableable.
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

P0 (landed) and P1 are independent and each is a single-file change plus a
test. Recommended sequence: P1 → P2 → P4 → P3 (P3 resolves a decided setting
through `KMET_CACHE_RETENTION`, D2 — read it in `kmet.app.config`, never in
`kmet.ai`, which may not name a host variable). P2 last-if-busy: it deletes
duplication, it does not change bytes. P5 is independent of all of them — it touches the tool
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

**D2 — retention resolution (decided: env now; location corrected while landing
P0).** The environment is read in `kmet.app.config/get-cache-retention` and the
resolved value arrives as the call's `:cache-retention`;
`kmet.ai.llm/resolve-cache-retention` only defaults an absent one to `:short`.
The plan said `kmet.ai.llm` would read the variable, but `test_seam_requires`
(`ai-names-no-app-state-root`) forbids a `kmet.ai` namespace from naming any
`KMET_*` variable — host state arrives as an argument — and the guard is right.
No per-model or per-provider key: the per-model shape has no consumer, and a
`settings.edn` key can follow if one appears. The per-call `:cache-retention`
stays the override compaction uses for `:none`.

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

## Tools: cross-session selection in both scopes

Depends on nothing in the prompt-caching section, but shares its tool-registry
files; exposure (§ P5) stays out of it deliberately.

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
- **Exposure stays out.** It is a source-level property (P5), and a
  project-scope exposure override would make a `:sandbox` tool un-disableable
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
  `builtins.clj:676-679`).
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

## Persistent eval and eval sources

Status: plan, decisions closed (E1–E16) — implementation can start at step 1.
Independent of every other track; the hooks track's eventual `:on-tool`
covers it for free (step 1 keeps the bridge on `kmet.app.tools.invoke`).
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
cached prefix and vary per agent, which is the opposite of the caching
track's goal; a source that needs the model to know something unprompted
registers a skill, the existing seam.

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
   `new-app.md` § Open at HEAD 5 (one line: a persistent context makes Jolt's
   `eval-in` more attractive later; the decision is unchanged).

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
  (no process isolation — `new-app.md` § Open at HEAD 5).
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
A dynamic per-agent section sits in the cached prefix and varies per agent —
the opposite of the caching track. A source that needs unprompted presence
registers a skill, the existing seam.

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
