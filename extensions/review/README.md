# Review extension

Code review for kmet — port of [`earendil-works/pi-review`](https://github.com/earendil-works/pi-review) (MIT) to a shipped opt-in extension. The review rubric is generalized to be language-agnostic (the original is JS-flavored).

## Commands

`/review` — open the preset selector. Modes:

- **uncommitted** — staged + unstaged + untracked changes
- **branch &lt;name&gt;** — review against a base branch (uses upstream tracking when set, falls back to the branch)
- **commit &lt;sha&gt; [title]** — review a specific commit
- **folder &lt;paths…&gt;** — snapshot review of one or more paths (not a diff)

`/end-review` — return to the original session. Three actions:

- **Return only** — navigate back, no summary
- **Return and fix findings** — return, then queue a follow-up that implements the findings
- **Return and summarize** — return, summarize the review branch (structured handoff: scope, verdict, findings, fix queue, constraints, human callouts), seed the editor with a starter sentence

`/review --extra "..."` — applies the extra instruction to whichever mode is chosen.

## What was dropped from the upstream port

- **PR / GitHub CLI support.** The upstream flow needs `gh pr checkout`, which requires `gh` installed, `gh auth status` valid, and a clean work tree — environment-fragile. The same review is reachable with `branch <remote-branch>` once a PR's head is fetched.

## Per-project guidelines

If a `REVIEW_GUIDELINES.md` file sits beside the `.kmet` (or `.pi`) project marker, its contents are appended to the review prompt.

## Shared custom review instructions

The preset selector includes an "Add custom review instructions" row. The instructions are stored as a `review-settings` custom entry, applied to every review until removed. The entry is read session-wide (`get-all-entries`, pi: `sessionManager.getEntries`), not from the active branch, so it survives the fresh-session branch jump.

## State model

A single review is active at a time. The active review's origin (the leaf to return to) lives as a `review-session` custom entry on the active branch and is restored on `:session-start` / `:session-tree`. An empty session uses a `review-anchor` custom entry to seed a leaf before branching.

## Testing

```sh
cd extensions/review
bb test
```

Covers arg tokenization/parsing, prompt assembly, the language-agnostic rubric assertions, git helpers against throwaway repos, and the entry's init/lifecycle.
