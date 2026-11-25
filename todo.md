# Architecture follow-ups

Only the open items remain; the landed eval-surface decisions and completed
quick wins are in git history. The 2026-11-18 review measured verbosity
0.011 and erosion 0.317 over 360 files / 90,877 SLOC, both below the human
reference means.

## Blocked

- **Saved-definition migration** — `github-issue-summary` is absent under
  `~/.kmet/agent`; the `fetch` verb and the rest of the eval-surface work
  landed (A–F).

## Open

- [ ] Extract `diff-frame!` (CC 79 / 230 SLOC, mass 1198 — 2x the next
      worst) and `render-frame!` (CC 32 / 200 SLOC) into per-case fns in
      `../tui/src/kmet/tui/frame.clj` (lines 457, 747). One sitting cuts
      the largest single mass in the tree.
- [ ] Add a file-budget guard (e.g. `kmet.test-file-budget`: no src file over
      ~1,500 LOC / ~150 defns) so god files cannot regrow — matches the repo's
      guard-test style; `eval.clj` and `tui/core.clj` would fail it today, so
      land it with the splits above or grandfather once.

## Deliberate, recorded (not wins)

- Live recomposition stays open (`design.md` §1).
- The sandbox's cooperative abort and the process-isolation tier stay open
  (`design.md` §5).
- App-module mass concentration and the doc-sync tax are structural; addressed
  only by the file-budget guard and the eval split.
