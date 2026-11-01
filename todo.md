# Todo

Three independent tracks. None blocks another; each is a request/ABI-level
change with unit-testable surfaces.

| track | what it fixes | status |
|---|---|---|
| Prompt-caching parity with pi | kmet's wires never ask for prompt caching (except responses/mistral/bedrock), so cache-read sits at 0; the tool ranker serves MCP only, no source's tools can be declared, and a tool-set change re-sends the whole array | P0–P6, decisions closed |
| Around tool hooks | two hand-rolled hook chains + a doubled permission gate; no way to settle a call successfully | decided: **A** — one `:on-tool` around registration |
| Eviction as the second context strategy | compaction always pays an LLM call and rewrites history; no deterministic, lossless way to shrink the window | ready; decisions closed |

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

- No new compaction/eviction strategy. This plan only changes request bytes.
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

## Eviction as the second context strategy

Status: decisions closed. Every automatic path honours the `:strategy`
setting; explicit `/compact` and `/evict` and branch summaries are fixed verbs.
Reference design: nodecode's eviction + recall
(`~/src/cvstree/nodecode/src/src/engine/context.lisp`,
`session/recall.lisp`, `engine/working-context.md`).

### Why

Compaction is kmet's only way to shrink the window, and it always costs a
second LLM round trip and always replaces history with a summary. It also
breaks the prefix it was trying to preserve: the summary sits where the
prefix sat. A deterministic strategy — cut whole turns, leave an addressable
stub, keep the log — is cheaper on every axis, and kmet already has every
piece except two functions.

### What already exists

| piece | where | what it buys |
|---|---|---|
| the durable entry shape | `compact-with-summary!` (`session/store`), `:first-kept-id` | a strategy-agnostic record: prefix excluded from context, kept in the file |
| the projection | `session/context.clj:102-119` `context-entries` → `[compaction, ...tail]` | "history excluded from context but stays in the file" is already the semantics |
| surgical omission | `append-context-edit!` (pi `ContextEditEntry`), `nil` replacement | omit one entry forever, log and transcript untouched, tree renders `[context omit: id]` |
| the trigger | `maybe-compact!` (`stage.clj:603`) measures the **projected** context, not the file | already correct for an append-only strategy |
| cache-miss detection | `detect-cache-miss` (`context.clj:251`) | a free "the prefix is already broken" trigger |
| the funnel | `compact-context!` (`stage.clj:515`) with a `reason` | one place to plug in, three callers already routed |

### The funnel (scope)

| entry point | caller | reason |
|---|---|---|
| `maybe-compact!` — proactive before a run | `loop.clj:431,579,901,914` | `:threshold` (`stage.clj:646`) |
| `compact-for-overflow!` — reactive after a provider overflow | `loop.clj:961` | `:overflow` (`stage.clj:658`) |
| manual queue — `/compact`, wire `compact` | `session/compaction.clj:36`, `commands/builtins.clj:167` | `:manual` (`stage.clj:554`) |

All three go through `compact-context!`, so all three follow the strategy.
That is deliberate: an evicting session that hits a provider 400 must not
suddenly summarize.

**Not affected: branch summaries.** `generate-branch-summary`
(`stage.clj:245`) is a *sibling*, not a caller — its own prompts
(`core/prepare-branch-entries`, `core/branch-summary-messages`), its own
budget, its own callers (TUI tree navigation → `session/runtime.clj:394` ←
`session_admin.clj:592`), its own entry role `:branch-summary`, which
`context-entries` treats separately from `:compaction` and which does not move
the floor. It also uses `stage-retry-policy` and `retry/retry-call!` itself —
which is why the row must keep them.

### The shape

One selector, four fixed paths. The strategy is a function of the *reason*
`compact-context!` already receives (`stage.clj:554`) and one setting:

| path | reason | strategy | knob |
|---|---|---|---|
| auto-compact, proactive | `:threshold` | **from settings** | `:strategy` in `settings.edn` |
| overflow, after a provider 400 | `:overflow` | the same setting | `:strategy` in `settings.edn` |
| `/compact` | `:manual` | always summarize | — |
| `/evict [percent]` | `:evict` (new) | always evict | the arg, default 50 |

`generate-branch-summary` (`stage.clj:245`) is not in the table: it is a
sibling function with its own prompts and its own entry role, and it always
summarizes.

The implementation is the smallest form of a strategy — extract what
`compact-context!` already does into a `:plan` and a `:body` pair, and pick the
pair from `(reason, settings)`:

```clojure
{:plan (fn [{:keys [projection branch window thresholds]}]
         ;; → {:first-kept-id id :kind :summary|:eviction} or nil
         )
 :body (fn [planned ctx] → string)}   ; the entry's text
```

- `:summarize`'s `:plan` keeps pi's cut (`find-projected-cut-point` over
  `keep-recent-tokens`); its `:body` is the existing `summarization-call` +
  retry.
- `:evict`'s `:plan` cuts at turn markers with hysteresis (trigger 0.85 → floor
  0.50); its `:body` renders a deterministic stub.

`compact-context!` becomes `plan → body → compact-with-summary!`. The
retry/abort/event machinery stays where it is and is never entered for
`:evict`, whose `:body` is synchronous.

Plus one operator verb: `/evict [percent]`, default 50, separate from
`/compact` (`commands/builtins.clj:167`) because it means something different
to a user. nodecode treats the default percent as an operator-command contract,
not a tunable.

### Settings

Exactly one new key, modelled on `:auto-compact` — the existing precedent for
"a compaction behaviour the user owns".

| | |
|---|---|
| key | `:strategy` — `:summarize` (default) or `:evict` |
| scope | `settings.edn` (user, then project, merged by `kmet.app.config`) |
| read | a field in `get-compaction-settings`' return map (`config.clj:662`), beside `:enabled` / `:reserve-tokens` / `:keep-recent-tokens`, resolved per call |
| effect | both automatic paths — `:threshold` and `:overflow` — and nothing else |
| written | `cfg/save-setting!`, like the other settings |
| shown | one row beside "Auto-compact" (`settings_selector.clj:365`) |
| gate | unchanged: `:auto-compact false` still means no proactive compaction, whatever `:strategy` says |

`config-schema.clj`'s map is `:closed false` and `:auto-compact` is not in it,
so a schema clause is optional; the default has to be stated in
`get-compaction-settings` either way.

**`:summarize` is the default, and it stays it.** Eviction is opt-in, reached
only by setting `:strategy :evict` or by the explicit `/evict`. This plan does
not change the default — no measurement, no deprecation, no "promote later"
step.

Deliberately *not* settings: the 0.85 / 0.50 ratios and the 50% default.
`/evict [percent]` is the per-invocation knob, and a default an operator can
reach from the command line does not need a config key. If measurement later
shows a machine-specific ratio matters, it joins the same
`get-compaction-settings` map — one place, resolved per call — not a new
mechanism.

### What it deliberately does not do

No new persistence layer, no change to `context-entries` or
`append-context-edit!`, no change to `:generate-branch-summary`, no strategy in
the `:compaction` row's `:config` (the row keeps naming one implementation and
`impl` keeps answering `builtin`), no second row, no evict-then-summarize
escalation, no ratio settings. One new entry role is required — see below.

### Recording the turn

Eviction needs to know where turns begin, and the entry stream cannot say:
`inject-queued-messages!` (`loop/state.clj:619-637`) appends a `:user` entry for
every steering message with nothing on it distinguishing it from a typed
prompt. The loop knows; the log does not. So record it — the format already
carries pure state roles (`:model-change`, `:thinking-level-change`, `:label`,
`:context-edit`) and `context-messages` projects unknown roles to `[]`.

```clojure
{… :role :turn-start
    :turn-id "<uuid>"        ; branch-safe identity — never a counter
    :reason :prompt | :continue}
```

- **Written before the user message, by the same writer**, so `append-entry`'s
  `:parent-id` = leaf (`session/store.clj:88-89`) chains them. One writer, one
  pair, no second path racing the loop's appends.
- **Steering and follow-up injections write nothing** while they join the live
  turn; a `continue` that starts a new run writes one — pi's own semantics
  rather than an approximation of it.
- **The ordinal is computed from position**, as nodecode computes its turn
  numbers. A stored counter drifts the moment two branches fork from one turn.
- **A cut at a marker survives later plans**: `context-entries` keeps
  `first-kept-id`'s entry (`session/context.clj:102-119`), so the marker stays
  in the context vector while projecting to nothing — the next eviction still
  sees where turns begin. Cutting at the user entry loses that.
- **The unmarked prefix is one immutable turn.** No migration, no heuristic:
  nothing before the first marker is evictable, and the next prompt writes one.
- **One surface shows it, two do not.** The tree selector renders it as a
  separator (`── turn 3 ──`, the ordinal counted from the `:turn-start`
  ancestors on the path, never stored), so the operator can see turn
  boundaries where they branch and fold. The chat replay skips it (the
  transcript is messages) and the projection never sends it. Without the
  selector clause it would fall through to a `[:turn-start]` row, which is why
  the clause is not optional.
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

### Decisions

**E1 — the strategy is selectable for automatic compaction only (decided).**
Explicit `/compact`, explicit `/evict` and branch summaries are fixed verbs;
the setting decides every automatic path: `:threshold` and `:overflow`. That is
why this is a selector inside `compact-context!` and not a row swap: six of the
row's eight operations never differ between strategies, and
`:generate-branch-summary` must keep summarizing inside whatever row is
mounted.

**E2 — reason → strategy is code, not configuration (decided).** `:manual` →
summarize, `:evict` → evict, `:threshold` → the setting, `:overflow` → the
setting. No `{:reason :strategy}` map, no row config.

**E3 — the recall read ships with the first path that can evict (decided).**
nodecode pairs eviction with `recall-log` / `recall-entry`; kmet has neither,
and a stub that says "still on disk" with no way to read it is a lie the model
cannot act on. So the read lands in the same change as `/evict` and the
`:strategy` key, and until it exists nothing evicts. Consequence: the plan may
cut any **completed turn** — not only obviously dead content (completed tool
output, resolved exploration) — because the model can always get it back.

**E4 — record the turn rather than infer it (decided).** A `:turn-start`
marker written by the prompt path (§ Recording the turn). The cut predicate
then collapses to `(= :turn-start (:role e))`, and "never split a tool call
from its result" becomes a cheap assertion over the chosen boundary instead of
the primary rule: a marker is only ever written at a prompt boundary, and a
prompt is only accepted between rounds. `drop-incomplete-tool-calls`
(`loop/completion.clj`) stays the defensive net it is today, not the
correctness argument.

**E5 — `:overflow` honours the setting (decided).** A provider 400 under
`:strategy :evict` evicts, for the same reason auto-compact does: the user
asked for no summarization on this box, and eviction is at least as effective
at shrinking the same prefix, since it drops the prefix rather than replacing
it. The consequence to keep explicit: when the eviction plan finds no legal
cut, "nothing to compact" stays today's outcome — the turn reports the
provider error — and there is no summarize fallback to escalate to. Decided
*against*: always summarizing on overflow (a stronger remedy for a wrong
estimate) and evict-then-summarize escalation.

### Steps

1. **Extract `:summarize` with zero behaviour change.** `:plan` and `:body`
   for today's path, `compact-context!` calling them, still the only strategy.
   Acceptance: the three compaction paths and the branch-summary tests stay
   green, byte-identical entry shapes. This step is also what keeps `/compact`
   and overflow honest while the rest lands.
2. **Record the turn.** `:turn-start` in `session/schema.clj`, written by the
   prompt path before the user message; the `session_admin.clj:274` skip
   clause; the tree selector's `── turn N ──` separator
   (`tree_selector.clj:169-217`, ordinal counted on the path); the `stats`
   audit (§ Recording the turn). Prerequisite for step 3 — the plan reads the
   markers.
3. **Add the `:evict` pair and the selector.** Marker-aligned `:plan` with
   hysteresis; `:body` renders the stub (the turns that left, by ordinal, and
   the fact that they are still on disk). Never touches the newest turn.
   `compact-context!` picks the pair from `(reason, settings)`.
4. **A `recall` read** over the branch entries — the read the stub advertises
   (E3). Lands before anything can evict, so the stub is never a promise the
   model cannot keep.
5. **The `:strategy` setting.** `get-compaction-settings` field, default
   `:summarize`, `save-setting!`, the `settings_selector` row, docs
   (§ Settings).
6. **`/evict [percent]`** as the explicit verb, reason `:evict`.

### Test plan

- `modules/app/test/kmet/app/test_compaction.clj` and the compaction queue
  tests — unchanged expectations through step 1; they are the regression net
  for the extraction.
- New, selector: `(reason, settings)` → pair. `:manual` summarizes whatever the
  setting says; `:evict` evicts whatever it says; `:threshold` follows it;
  `:overflow` honours the setting too; `:summarize` stays byte-identical to
  today for every reason.
- New, settings: `get-compaction-settings` answers `:strategy :summarize` when
  the key is absent, and the `:auto-compact` gate is unchanged (false = no
  proactive compaction regardless of `:strategy`); the selector row persists
  through `save-setting!`.
- New, marker (`session/schema.clj` + the three decided sites): a prompt
  writes exactly one `:turn-start` whose `:parent-id` is the previous leaf and
  whose next entry is the user message; a steering injection writes none; a
  `continue` writes one; a marker projects to no wire message, adds no chat
  row, and renders as `turn N` in the tree selector with N counted from its
  ancestors (two branches from one turn both read `turn N`, and the next
  marker on each reads `turn N+1`); a session file written before the role
  existed still loads (strict pass off) and its unmarked prefix is one
  immutable turn.
- New, plan: `:plan` with `:evict` over a scripted branch — cuts only at
  markers, newest turn retained, hysteresis (one eviction from 0.85 to 0.50,
  then no further eviction until the trigger is crossed again), and the marker
  at `:first-kept-id` still present in `context-entries` for the next plan.
- New, branching: a fork taken mid-turn gives each branch its own marker set;
  neither branch's plan reaches across the fork point.
- New: `:body` for `:evict` is deterministic — two calls on the same plan
  produce byte-identical text, so refolds and cache reasoning hold.
- New: with `:strategy :evict`, an overflow evicts (no summarize call, no
  `:summarization-retry-*` events); with `:summarize` it summarizes exactly as
  today; and a branch summary in the same session still summarizes either way.
- New: with `:strategy :evict`, an overflow whose plan finds no legal cut
  leaves the context untouched and the turn reports the provider error — no
  fallback to summarization.
- Extensions/gates: `bb test-changed`, `bb lint-changed`,
  `bb format-check-changed`.

### Notes

- Cache economics (see the first track): the cut lands in a durable entry, so
  every later request rebuilds the identical message list — byte-stable between
  breaks. Hysteresis makes the break one planned event, not a per-round shave.
  `detect-cache-miss` can gate it to a break that already happened.
- Do not add the "thin old tool results" refinement (nodecode's
  `LIVE-RELIEF-PLAN`) up front: it edits history exactly when the cache is
  warm, and it is the part with the worst cost/benefit. Cut whole turns first.
