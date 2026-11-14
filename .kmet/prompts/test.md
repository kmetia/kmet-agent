---
description: Run the full gate (lint + format-check + bb test + bb test-ext + bb test-jvm + bb test-ext-jvm + jolt test + jolt test-ext) and fix failures
argument-hint: "[scope or extra instructions]"
---
Run the full validation gate for this project and fix any failures, strictly in this order:

1. `bb format-check` — if it fails, run `bb format` to fix the formatting, then re-check
2. `bb lint` — must pass with 0 errors, warnings, and info findings
3. `bb test` — the non-slow test suite on babashka
4. `bb test-ext` — the slow (^:slow) tests on babashka
5. `bb test-jvm` — the non-slow test suite on the JVM (needs the Clojure CLI; `^:bb-only` tests are skipped there, but an unloadable namespace fails the run)
6. `bb test-ext-jvm` — the slow (^:slow) tests on the JVM
7. `jolt test` — the non-slow test suite on jolt
8. `jolt test-ext` — the slow (^:slow) tests on jolt

Run the eight steps sequentially, one at a time, not just to the first failure. Fix findings as they appear, but never re-run work a fix did not invalidate:

- Iterate on a failure with the narrowest command: the failing test var or namespace (`bb test <var>`, `bb test-jvm <var>`, `jolt test <var>`, same with test-ext), or the changed-file task (`bb lint-changed`, `bb format-check-changed`, `bb test-changed`, `bb test-ext-changed`, `bb test-changed-jvm`, `bb test-ext-changed-jvm`). Do not repeat a whole step after every edit.
- A step that is green stays green. Re-run it only when a later edit touched files it covers: any edit invalidates format-check and lint for that file; a `src/` or `tasks/` edit can affect any test suite on any host; a `jolt/` edit affects jolt only; a test edit affects its own suite on every host that selects it; a `bb format` fix (whitespace only) invalidates format-check alone. Use `bb changed` to see what was touched.
- The hosts do not cover each other: babashka, the JVM and jolt have different classpaths and skip different tests (`^:bb-only` runs only on babashka), so a green step on one host never substitutes for its step on another. A changed-file JVM task re-verifies only the JVM steps; the bb changed-file tasks do not cover `bb test-jvm` or `bb test-ext-jvm`.
- Never restart the gate from step 1, and never re-run a step "just to be safe". Re-verify an invalidated step with its changed-file task — the closure covers the edit — and use the full step only when the changed task cannot cover it.
- Before finishing, every step must be green in the current state: either it passed and nothing it covers changed since, or it was re-verified after the last edit that touched it. Report what ran, what each result covers, and which steps you deliberately did not re-run.

If extra instructions were given after /test, apply them — e.g. a narrower scope like "lint only" or a specific test namespace. The full gate is the default; this command is the explicit instruction to run it.
