# Eval verb surface — reviewed proposal

Status: reviewed; decisions recorded. A (fetch), B (grep `:context`), C (glob `:stat`), D (json alias and `kmet.libs` removal), E (prompt/help lines) and F (verification) are implemented. The only open item is the blocked saved-definition migration: `github-issue-summary` is absent under `~/.kmet/agent`.

## Decisions

1. **fetch** — add a built-in `core` verb backed by `kmet.libs.http/request`.
   It must be host-side, not a saved library definition: the eval sandbox will
   no longer expose `kmet.libs.*`, so a saved definition could not require the
   backend.
2. **grep context** — mirror pi's grep tool: one `:context N` option (lines
   before and after each match; default 0), no separate `:before`/`:after`.
   Matches still count toward `:max`; context lines do not. Defaults unchanged.
3. **glob :stat** — add `:stat true`; additive `:entries`, defaults unchanged.
4. **read prompt line** — say that `read` takes an absolute path argument,
   `grep`/`glob` take an absolute `:path`, and `read` with `:as :lines` returns
   `{:n :text}` maps; use them instead of `sed -n`/`nl`/`cat`.
5. **alias prompt block** — one line listing the pre-bound aliases with targets:
   fs, str, set, edn, walk, json (`clojure.data.json`), p (`babashka.process`);
   no require needed.
6. **json alias** — repoint from `kmet.libs.json` to `clojure.data.json`.
7. **kmet.libs exposure** — drop all four ports from the shared script sandbox
   base; extension contexts keep them. Rationale: the ports were the only
   `kmet.*` namespaces admitted by the sandbox boundary. `json` becomes the
   standard `clojure.data.json` alias and HTTP becomes the built-in `fetch`
   verb, so neither needs a project namespace; YAML and Markdown are dropped
   with no replacement in v1. The result is one boundary rule: host-standard
   `clojure.*`/`babashka.*`, the fixed bundled set, and built-in verbs — no
   kmet implementation namespace is require-able from a script.
8. **process -> p** — keep `p` as the babashka.process alias (current surface);
   do not add a `process` alias; document `p`. Resolved: keep `p`.
9. **Shared base** — `eval` and `codemode` both build from the same
   `script/base-namespaces`, so both lose the ports. Do not scope the filter to
   eval; the two sandboxes stay identical.
10. **Boundary errors** — keep the error contract (`:type :script/unreadable`,
    one directed message), but update the surface facts it names; do not expand
    the namespace surface.
11. **fetch lifecycle (proposed)** — resolve `:timeout` as per-call > positive agent
    `:http-total-timeout-ms` > 120 s script default; do not reuse
    `:http-idle-timeout-ms` as a total. Pass the call's cancel signal; curl
    aborts, the native transport is bounded by the timeout. JSON error
    responses follow the 2xx/JSON-content-type rule in A.

## Review findings / risks

- **fetch vs. dropping kmet.libs.http** is compatible only if fetch is built
  in. Implement `fetch-verb` in `kmet.app.tools.script`; the host namespace may
  require `kmet.libs.http`, while the sandbox copy list must not include it.
- **github-issue-summary will break.** It is a saved global verb using
  `kmet.libs.http/request` and `kmet.libs.json/parse-string`. Migrate it to
  `fetch` with `:throw? true` (preserving the current 4xx/5xx throw), keeping
  the `Accept` and `User-Agent` headers. The file lives under `~/.kmet/agent`,
  so the migration is user state, not a repo file.
- **The json repoint is a breaking alias change.** Code using
  `json/parse-string` or `json/generate-string` breaks; clojure.data.json uses
  `read-str`/`write-str`. Also: `kmet.libs.json` preserves namespaced keyword
  keys and disables slash/unicode escaping, while direct data.json does not;
  the boolean `(parse-string s true)` becomes `(read-str s :key-fn keyword)`.
  Audit saved definitions, tests and docs.
- **YAML/Markdown access disappears from the sandbox by decision.** No
  replacement in v1; extension contexts still expose the ports.
- **fetch reads the whole body into memory.** The eval result limit bounds the
  display, not the download; no streaming in v1. Document it.
- **Keep platform-owned checks.** `system-namespaces`,
  `reserved-source-aliases` and `reserved-source-keys` still refuse kmet.libs
  definitions after exposure removal; add `core/fetch` to the last.
- **The alias backend must be copied.** Removing the `kmet.libs.*` requires
  from `require-host-namespaces!` while making `fetch` lazy can leave
  `clojure.data.json` uncopied when `alias-prelude` requires it. Require it
  explicitly (top-level in `script.cljc` or in `require-host-namespaces!`).
- **Additive option shapes.** `grep` keeps `:matches` unchanged and adds
  `:lines` only when `:context > 0`; `glob :stat` adds `:entries` without
  changing `:paths`. Default results must stay byte-compatible.
- **Prompt and catalog gates.** The planned alias line, fetch entry and longer
  read guideline exceed the 1300-char prompt target. Keep 1300 as the goal but
  temporarily allow 1500 in the test; add a follow-up to trim back. The eval
  description change also requires regenerating `docs/composition.md`.
- **Test fallout is wider than the plan says.** `test_codemode.clj:271` uses
  `json/generate-string`; `test_eval_library.clj:304` defines a helper using
  `json/parse-string`. Both fail after the alias repoint.

## Tasks

### A. fetch core verb
- [x] Add `fetch-verb` to `modules/app/src/kmet/app/tools/script.cljc` beside
      the other core verbs.
  - Contract: `(fetch url)` / `(fetch url {:keys [method headers body as throw?
    timeout follow-redirects]})`.
  - Defaults: `:method :get`, `:as :string`, `:throw? false`; `:timeout` in
    seconds, converted to the backend's ms.
  - `:as :json` parses with `clojure.data.json/read-str` and `:key-fn keyword`;
    an empty body parses to nil. A parse failure is an explicit script error
    (`ex-info` with a `:type`); the message names the URL and status, not the
    whole body. With `:throw? false`, parse only a non-empty body whose status
    is 2xx or whose content type is JSON; otherwise return the raw string.
  - A map `:body` is JSON-encoded; set `Content-Type: application/json` when
    the caller did not set it. Match header names case-insensitively and do not
    overwrite an explicit `:content-type`/`"Content-Type"`.
  - Return `{:status :headers :body}`; pass through `:follow-redirects` and
    `:timeout` (seconds → ms), and `:method`, `:headers`, `:body`.
  - Timeout resolution: per-call `:timeout`; else the agent's positive
    `:http-total-timeout-ms`; else the script default 120 s. Do not use
    `:http-idle-timeout-ms` as a total — it is a per-byte stream idle bound,
    and `:http-total-timeout-ms` defaults to 0 (disabled). Threading the agent
    setting into the eval/codemode call state is a small wiring change; if not
    wired in v1, keep the 120 s script default and document it. Document the
    curl/native difference.
  - Cancellation: pass `(:cancelled *call-state*)` to `kmet.libs.http/request`
    as `:signal`; it aborts the curl transport. The native transport cannot be
    interrupted mid-request, so the timeout is the bound there.
  - Backend: `kmet.libs.http/request`. Require the backend explicitly on the
    host (top-level in `script.cljc` or lazily in `fetch`) and make sure
    `clojure.data.json` is loaded before `base-namespaces` copies it; the alias
    prelude cannot depend on an incidental transitive load.
  - If the app setting is honored, thread `:http-total-timeout-ms` into the
    eval/codemode script state and test nil/0/positive values; do not read the
    config from the sandbox.
- [x] Add `fetch` to `core-verb-vars`, `core-refer-verbs` and `core-summary`;
      update the arglists metadata.
- [x] Add `(fetch "url" {...})` to the Verbs line in
      `modules/app/src/kmet/app/eval/tool.clj` and a prompt guideline.
- [x] Tests: local HTTP fixture (see the proposal below; no external network).
      Cover: GET 200 string; 404 with `:throw? false`; `:throw? true` throws;
      headers; `:as :json`; empty body → nil; parse failure; non-2xx
      non-JSON body stays raw; POST map body and Content-Type (including
      caller-supplied); timeout conversion and default via `with-redefs` (a
      real timeout test is `^:slow`); `:signal` pass-through; `:follow-redirects`
      pass-through. Reset the HTTP transport in a fixture.
- [x] Fixture proposal: extract the one-shot server helpers (`respond`,
      `read-request`, `start-server`) into `test/kmet/test_http_server.clj`
      next to `kmet.test-tmp`, make them public, and require that namespace
      from both `modules/libs/test/kmet/libs/test_http.clj` and the new app
      fetch tests. Keep the transport-reset fixture local to each test
      namespace (or move it too if it is identical). This avoids duplicating
      the server and avoids reaching into another test namespace's private
      vars.
- [ ] Migrate the saved `github-issue-summary` after fetch lands (confirmed
      to exist at `~/.kmet/agent/eval/user/github-issue-summary.clj`, but
      absent in this environment — the saved library has `signal-send-me`
      (global) and the cwd-scoped `bb` and `stat`, but no `github-issue-summary`):
      redefine it to call `fetch` with `:as :json` and `:throw? true` (the
      current wrapper throws on HTTP >= 400), keep the GitHub `Accept` and
      `User-Agent` headers, then `select-keys` the summary fields; verify
      `(library)` and a fresh-session load. Migrate it; do not drop it. This
      file is user state under `~/.kmet/agent`, not a repo file.

### B. grep context
- [x] `modules/app/src/kmet/app/tools/file_search.clj`: add non-negative
      `:context N` only, mirroring pi's grep tool; default 0/nil means no
      context. Do not add `:before`/`:after`.
- [x] Shape: keep `:matches` as match-only entries and add `:lines` only when
      `:context > 0`, ordered by file/line and formatted exactly like the grep
      tool — `path:line: text` for matches and `path-line- text` for context;
      paths keep the verb's existing display form. Once `:max` is reached, a
      match inside the last allowed match's trailing context is emitted as
      context. Reject text-only: eval verbs are values-first, and scripts need
      to filter and count matches; `:lines` gives the model the grep-tool
      rendering without losing the structured result.
- [x] `:max` still limits matches, not context lines; `:truncated?` remains
      match-count based; keep the last allowed match's trailing context.
      Document the result-size multiplication.
- [x] Extract a `context-lines` helper so `grep` stays inside the CC budget.
- [x] Update the `grep` docstring and `grep-verb` `:arglists` in `script.cljc`.
- [x] Tests in `modules/app/test/kmet/app/tools/test_file_search.clj`, mirroring
      pi's `grep-operations.test.ts`: `context 1` produces the expected
      before/match/after lines; default shape unchanged; boundaries; after
      `:max`, a match inside the last allowed match's trailing context is
      context; `:glob`/`:exclude`; binary skip unchanged.

### C. glob :stat
- [x] `modules/app/src/kmet/app/tools/file_search.clj`: add `:stat` boolean.
      When true, add `:entries [{:path :size :modified}]` with the same
      count/order as `:paths`; `:paths` stays strings; `:total` and
      `:truncated?` unchanged.
- [x] Stat only the returned/taken paths; `:modified` in epoch milliseconds;
      nil on a stat failure; do not fail the listing.
- [x] Update the `glob` docstring and `glob-verb` `:arglists` in `script.cljc`.
- [x] Tests: default shape unchanged; `:stat` entries, size and modified
      time; 0-byte file; `:max` and `:truncated?`; no `:entries` without
      `:stat`; `:entries` order matches `:paths`.
      - For mtime, set an explicit value aligned to 2 s (for example
        `2021-01-01T00:00:00Z`) with `fs/set-last-modified-time`, then assert
        `:modified` is within 2 s of it rather than exact: filesystems differ in
        timestamp granularity.
      - Add a `with-redefs` case where `fs/size`/`fs/last-modified-time` throw;
        assert the listing still succeeds and the stat-failure fields are nil.

### D. json alias and kmet.libs removal
- [x] `script.cljc` `standard-aliases`: `["json" 'clojure.data.json]`; keep
      `["p" 'babashka.process]`.
- [x] Remove `script-lib-namespaces`, its clause in `copyable-namespace?`, the
      `:libs` group in `surface-namespaces`, and the kmet.libs line in
      `surface-help` (`modules/app/src/kmet/app/eval.clj`). Update the stale
      docstrings/comments at `script.cljc` (~720-725, 734, 760-766, 841-847,
      875-880, 942-951).
- [x] Remove the four `(require 'kmet.libs...)` lines from
      `require-host-namespaces!`; require `kmet.libs.http` in the host
      `script.cljc` for fetch. Add an explicit `(require 'clojure.data.json)`
      (or an equivalent guaranteed host load) so `alias-prelude` can require
      it after the copy filter is built.
- [x] Update the `namespace-boundary` messages: kmet's own namespaces are
      excluded; roots are clojure.*, babashka.* and the bundled libs; no
      kmet.libs mention. Add directed hints for the removed ports
      (`kmet.libs.json` → "use the json alias"; `kmet.libs.http` → "use
      fetch"), and change `surface-help`'s "other kmet.* namespaces" to
      "kmet.* namespaces".
- [x] Update `core-summary`: remove the kmet.libs ports sentence; add fetch.
- [x] Keep `system-namespaces`, `reserved-source-aliases` and the eval-source
      forbidden-name checks; add `core/fetch` to
      `modules/app/src/kmet/app/eval/sources.clj` `reserved-source-keys`.
- [x] Update `modules/app/src/kmet/app/eval/tool.clj` (alias/read lines) and
      `eval.clj` help text.
- [x] Docs: `docs/architecture.md` eval-sandbox section (~386, 536-538,
      722-746); `docs/extensions.md` (~468-470); `modules/app/src/kmet/extension.md`
      (~197-198, not ~483-484 — the latter is the reserved-host-namespace list
      and stays). `docs/configuration.md` only gives examples, so no change is
      expected there.
- [x] Regenerate `docs/composition.md` after the eval/codemode description
      edits (`bb generate-catalog`); it embeds the tool descriptions at
      ~414-417 and ~358-364.
- [x] Tests:
  - [x] `test_eval_interface.clj`: alias line `json -> clojure.data.json` and
        `p -> babashka.process`; assert kmet.libs is absent; assert
        `(help :core)` includes `fetch`; re-check the 4600-char index cap.
  - [x] `test_script.clj:88-89`: extend the arglists checks for `:context`,
        `:stat` and `fetch`.
  - [x] `test_codemode.clj` (~303-309, ~793-796, ~811): kmet.libs ports are no
        longer require-able; `kmet.libs.edn-store` is rejected as a kmet
        namespace; `(all-ns)` no longer contains `kmet.libs.json`; replace the
        `json/parse-string` assertion with `json/read-str`.
  - [x] `test_codemode.clj:271` and `:311`: `json/generate-string` →
        `json/write-str`, `json/parse-string` → `json/read-str`; check the
        capability expectation at `:282`.
  - [x] `test_eval_library.clj:304`: the saved `parse-json` helper uses
        `json/parse-string`; switch it to `json/read-str` (default string keys).
  - [x] Any eval/codemode surface tests asserting core-summary or description
        text.
  - [x] `test_eval_sources` kmet.libs source rejection still passes.
- [x] Audit the saved eval library, tests and docs for `kmet.libs.`,
      `json/parse-string` and `json/generate-string`; migrate
      `github-issue-summary` (A).
- [x] Commit body must name this as an ABI break: removed require-able
      `kmet.libs` ports and the `json` alias's `parse-string`/`generate-string`
      surface.

### E. Prompt and help lines
- [x] `modules/app/src/kmet/app/eval/tool.clj` `:prompt-guidelines` add:
      `"Pre-bound aliases: fs, str, set, edn, walk, json (clojure.data.json),
      p (babashka.process); no require needed."`
- [x] Replace the existing read/search guideline, but keep the `sed`, `head`,
      `tail`, `rg`/`find` words the prompt tests assert: `read` takes a
      path (absolute or cwd-relative); `grep`/`glob` take a `:path`;
      `read` with `:as :lines` returns `{:n :text}` maps, not numbered strings;
      use them instead of `sed -n`/`nl`/`cat` or bash `rg`/`find`.
- [x] Add fetch to the eval Verbs line; optionally show
      `(grep "p" {:context N})` and `(glob "p" {:stat true})`.
- [x] Update `modules/app/src/kmet/app/tools/codemode.cljc` (description and
      prompt guidelines) for the alias change and `fetch`; codemode has no
      `(help)` namespace, so the prompt is the only discovery surface.
- [x] Keep 1300 chars as the eval-prompt goal; temporarily raise
      `modules/app/test/kmet/app/test_eval.clj:291` to 1500 so the alias/fetch
      work can land, and add a follow-up to trim back. Re-check the index cap at
      `test_eval_interface.clj:312` (4600).
- [x] Follow-up: trim the eval prompt back under 1300 chars
      (`test_eval.clj:291`); it is 1292 now and the cap is back to 1300.
- [x] Verify `(help :core)` and `(help :surface)` pick up the new verb, alias
      target and removed kmet.libs line from `script.cljc`.

### F. Verification
- [x] `bb test-changed`
- [x] `bb test-ext-changed` (the slow gitignore test still passes)
- [x] `bb lint-changed`
- [x] `bb format-check-changed`
- [x] `bb generate-catalog` (or `bb check-catalog`)
- [x] `bb check` for source namespaces
- [x] Extension-context guard: `modules/app/test/kmet/app/extensions/test_resources.clj`
      (~702) still proves an extension context can require `kmet.libs.yaml`;
      run the relevant extension test or `bb test` for it.
- [x] Optionally `bb slop --dups` if a test HTTP server is copied — none was.
- [x] Fresh-session manual checks:
  - [x] `(help :surface)` shows `json -> clojure.data.json`,
        `p -> babashka.process`, and no kmet.libs.
  - [x] `(fetch ...)` returns status/body; use a local server for the offline
        check.
  - [x] `(grep "needle" {:context 2})` and `(glob "**/*.clj" {:stat true})`
        shapes.
  - [x] `(read "/absolute/path" {:start 1 :end 3 :as :lines})` returns
        `{:n :text}` maps.
  - [x] The saved library loads the three present definitions (`user/bb`,
        `user/stat` cwd; `user/signal-send-me` global); the migrated
        `github-issue-summary` is absent, so that part stays blocked with A.

## Open questions
- **fetch timeout**: resolved for v1 — per-call `:timeout` > 120 s script
  default. The agent's `:http-total-timeout-ms` is not threaded into the
  eval/codemode state yet; wire it later if the setting should bound fetch.
- **Grep validation**: resolved in the implementation — negative,
  non-numeric and nil `:context` clamp to 0 (pi's behavior); no rejection.

## Architecture review quick wins (2026-11-18)

Measured with `bb slop` over 360 files / 90,877 SLOC: verbosity 0.011,
erosion 0.317, both below the human reference means. The wins below target
the CC>10 hotspots and the clone groups the metric correctly fingers.
Ordered by value/effort.

- [ ] Extract `diff-frame!` (CC 79 / 230 SLOC, mass 1198 — 2x the next
      worst) and `render-frame!` (CC 32 / 200 SLOC) into per-case fns in
      `modules/tui/src/kmet/tui/frame.clj` (lines 457, 747). One sitting cuts
      the largest single mass in the tree.
- [x] Add a `with-captured-err` test helper and replace the ~50 hand-rolled
      `(binding [*err* (java.io.StringWriter.)]` blocks — densest in
      `modules/app/test/kmet/app/loop/test_compact.clj`, also
      `test_custom.clj`, `test_models.clj`, `test_boundaries.clj`,
      `extensions/test_session.clj`. Removes raw interop from the test tree.
- [x] Unify the selector family's open/close/escape wiring into one shared
      helper (extend the existing dock/state helper, do not copy):
      `settings_selector.clj` is 18.1% clone-flagged, `tree_selector.clj`,
      `fork_selector.clj`, `thinking_selector.clj` and `interactive/auth.clj`
      duplicate the same close/escape/restore blocks
      (`bb slop --dups` lists the groups). Done with
      `dock/show-panel!` (focus/cover) + `dock/close-atom!`; every selector
      open/close goes through that pair directly.
- [x] Split `kmet.app.eval` along its directory seams: the result ring
      (`eval/results.clj`), library listing (`eval/library.clj`), source seed
      helpers (`eval/seed.clj`) and persistence writes
      (`eval/persistence.clj`), with the record/scope model in `eval/env.clj`.
      eval.clj is 2,736 LOC (from 3,214); the facade surface is unchanged and
      the eval tests pin it. The save path was split into `save-record`,
      `save-doc`, `save-source` and `save-edit`; the eval directory now has no
      function over CC 10.
- [x] Table-drive `detect-openai-compat` (CC 47 in 89 SLOC,
      `modules/ai/src/kmet/ai/api/shared.clj:465`) and `parse-inline`
      (CC 54, `modules/libs/src/kmet/libs/markdown.clj:289`) — rule tables
      instead of nested conditionals in two high-traffic files.
- [x] Merge the two near-identical `cond` tables in
      `modules/libs/src/kmet/libs/ranking.clj` (18.1% flagged) into one
      `score-values!` helper; clone and rule flags are now 0.
- [ ] Add a file-budget guard (e.g. `kmet.test-file-budget`: no src file over
      ~1,500 LOC / ~150 defns) so god files cannot regrow — matches the repo's
      guard-test style; `eval.clj` and `tui/core.clj` would fail it today, so
      land it with the splits above or grandfather once.
- [x] Close design.md §3 (the compaction queue records no fact): record at
      submit, fold at flush, drop on a clear; the engine now owns the flush.
      (`modules/app/src/kmet/design.md` § Decisions that still constrain a
      change).

Not wins (deliberate, recorded): live recomposition stays open (design.md
§1); the sandbox's cooperative abort and the process-isolation tier stay
open (§5); the app-module mass concentration and the doc-sync tax are
structural, addressed only by the file-budget guard and the eval split.
