# Plan: Port pi-review to kmet

Port [`~/src/cvstree/pi-review`](https://github.com/earendil-works/pi-review)
(a standalone `/review` + `/end-review` extension for the pi coding agent) to
kmet as a shipped opt-in extension, with the review rubric made
**language-agnostic** (the pi original is JS-flavored).

Status: **shipped**. All 5 execution steps landed across
`72091ce` (host plumbing), `df6b0ae` (extension), `4881904`
(pi-alignment.md sync), and `5f6aae9` (test fix). The review extension
test suite passes (`bb test` in `extensions/review/`: 39 tests,
87 assertions, 0 failures).

## What we ported

`pi-review/review.ts` (~1600 lines) provides:

- `/review` with modes: **uncommitted**, **base branch**, **commit**,
  **folder(s)** (snapshot, not diff). **PR mode was dropped** — see
  [Deviations](#deviations-from-pi-review) below.
- Interactive preset selector with smart default (uncommitted → feature
  branch → commit), branch/commit pickers with fuzzy filter, custom shared
  review instructions (add/remove, persisted per session)
- `/review branch <name>`, `commit <sha>`, `folder <paths>`, `--extra "..."`
  (any mode). **`/review pr …` was dropped — see
  [Deviations](#deviations-from-pi-review).**
- Fresh-session review: branches the session tree at the first user message,
  labels it "code-review", shows a "review active" widget, returns via
  `/end-review` with three actions: **Return only** / **Return and fix
  findings** / **Return and summarize** (structured handoff: scope, verdict,
  findings, fix queue, constraints, human callouts)
- `REVIEW_GUIDELINES.md` project instructions (found beside the project
  marker dir, `.pi` → kmet `.kmet`), appended to every review prompt
- A detailed review rubric (from Codex's review_prompt.md): what to flag,
  clean-code guidelines, untrusted-user-input, comment guidelines, review
  priorities, fail-fast error handling, human reviewer callouts, priority
  levels, output format

## Deliverables

### 1. New dir extension `extensions/review/`

Mirrors the `extensions/clojure/` manifest-dir layout (own `bb.edn` + tests):

| File | Contents | Shipped in |
|---|---|---|
| `extension.edn` | `{:name "review" :entry "src/kmet/extensions/review/core.clj"}` | `df6b0ae` |
| `src/kmet/extensions/review/core.clj` | `/review` + `/end-review` commands, session state (custom entries), widget, restore on `:session-start`/`:session-tree`, fresh-session orchestration (no PR checkout — see [Deviations](#deviations-from-pi-review)) | `df6b0ae` |
| `src/kmet/extensions/review/prompts.clj` | `REVIEW_RUBRIC` (generalized), `REVIEW_SUMMARY_PROMPT`, `REVIEW_FIX_FINDINGS_PROMPT`, per-target prompts | `df6b0ae` |
| `src/kmet/extensions/review/git.clj` | `ext/exec` git helpers: merge-base, local branches, recent commits, pending-changes, default branch (no `gh` — see [Deviations](#deviations-from-pi-review)) | `df6b0ae` |
| `src/kmet/extensions/review/dialogs.clj` | Selector / input dialogs built on `kmet.tui.*` + `ui-custom` (kmet has no host `ctx.ui.select`/`editor`; pi does — we compose the equivalents) | `df6b0ae` |
| `test/…` + `bb.edn` | Unit tests: arg tokenize/parse, prompt assembly, git output parsing, rubric content assertions (39 tests, 87 assertions, 0 failures) | `df6b0ae` + `5f6aae9` |
| `README.md` | Port of pi-review's README | `df6b0ae` |

### 2. Host changes (pi-parity gaps the port needs)

Three small changes to kmet core (with tests) — all shipped in `72091ce`:

1. **`kmet.app.extensions` `api-session` facades** — added `:get-branch`,
   `:get-leaf-id`, `:get-entry` (pi `SessionManager.getBranch/getLeafId/
   getEntry`). Plus a `from-id` overload on `get-branch-entries` and
   nullable api stubs so extension tests can run isolated.
2. **`kmet.modes.interactive` `:navigate-tree` — accept `:label`** — pi's
   `navigateTree(..., {label})` tags the review branch "code-review"; kmet's
   tree-navigation prep already carried label, the extension-context fn
   was dropping it. Also fixed a fallback bug where a missing extension
   result dropped the fresh-review "code-review" label and any
   extension-provided label override (effective-label handling).
3. **Thread `:replace-instructions`** — pi's summary navigation replaces
   the default summary prompt with custom instructions
   (`replaceInstructions: true`); kmet always appended "Additional focus:".
   Threaded the flag through `navigate-tree!` →
   `branch-summarize-and-apply!` → `generate-branch-summary` →
   `compaction/branch-summary-messages`. Added a 2-arity BC overload on
   `branch-summary-messages` and a 3-arg `replaceInstructions` branch
   (replace vs append "Additional focus"). Without this the structured
   `REVIEW_SUMMARY_PROMPT` would have been mashed into the builtin summary
   format.

## Key design decisions (kmet differences, pi-equivalent behavior)

- **Waiting on summarize navigation**: kmet's `:navigate-tree` with
  `summarize: true` is fire-and-forget (summarization runs on a future).
  `/end-review` "return + summarize" therefore waits on a one-shot
  `:session-tree` listener (delivered on `:summary-entry` or the target
  leaf) with a timeout, showing the working indicator meanwhile (pi's
  BorderedLoader).
- **`ctx.ui.editor` / `ctx.ui.select`** don't exist in kmet → SelectList /
  Input / Editor dialogs composed via `ui-custom`. kmet's SelectList already
  has builtin fuzzy filtering, so the pi branch/commit filter UIs simplify.
- **REVIEW_GUIDELINES.md** resolution: walk up from `:cwd` until a `.kmet`
  dir is found, read the file beside it (pi: `.pi`).
- **Headless**: `(:mode ctx)` check → `ui-notify` error (pi: `hasUI` guard).
- **Review state persistence**: custom entries (`review-session` /
  `review-settings` / `review-anchor`) along the active branch, restored on
  `:session-start` / `:session-tree` (pi: sessionManager entries).
- **Rubric generalization** (the explicitly requested part): remove
  JS-specific examples (`isRecord`/`asString`), JSON-specific error-handling
  wording → language-agnostic parsing/decoding, `null`/`[]`/`false` fallback
  examples → generic "silent fallback values".

## Deviations from pi-review

- **PR mode dropped** (`/review pr <number|url>`, `gh pr checkout`,
  `gh pr view`, `gh pr diff`). The pi flow requires a `gh` CLI binary in
  PATH and a clean work tree to `gh pr checkout`, both
  environment-fragile. The same review is reachable via
  `/review branch <remote-branch>` once a PR's head is fetched
  (`git fetch origin pull/<n>/head:<branch>` then review the local
  branch). `git.clj` ships no `gh`-related helpers.
- **Entry file renamed** to `core.clj` (matches the `clojure` extension's
  convention; the plan listed `review.clj`).

## Execution order

Done.

1. Host changes (facades + navigate-tree opts + replace-instructions
   threading) with tests → `72091ce`
2. `prompts.clj` (the generalized rubric) → `df6b0ae`
3. `git.clj` → `dialogs.clj` → `core.clj` wiring → `df6b0ae`
4. Extension tests (`bb test` inside `extensions/review/`), README,
   `extensions/README.md` registration → `df6b0ae`; follow-up test fix
   `5f6aae9`; `pi-alignment.md` event/API appendix sync `4881904`
5. Final validation: changed-file gates passed per commit; full gates were
   not requested.

## Validation snapshot

- `bb test` in `extensions/review/`: 39 tests, 87 assertions, 0 failures
- `bb lint` in `extensions/review/`: 0 errors / 0 warnings / 0 info (per
  step-2 commit message)
- `bb format-check` in `extensions/review/`: clean (per step-2 commit
  message)
- Working tree: clean (`git status` empty)
