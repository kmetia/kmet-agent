# Windows host — open issues

Only open Windows-specific issues are recorded here; non-Windows upstream
bugs live in `jolt-bugs.md`. The fast suites (`bb test`, `jolt test`) were
last green on both hosts on 2026-10-01 (`jolt v0.8.15-46-g005d134b`).

### [jolt#1263](https://github.com/jolt-lang/jolt/issues/1263) — Windows: `spit` fails transiently when AV holds its temp file

Open (filed 2026-10-06, no fix yet). Windows real-time AV opens a freshly
written file to scan it; a rename landing in that window fails with
`permission denied`, and `jolt-spit` renames its temp microseconds after
`close-port`. The failed temp cannot be cleaned up either (the scanner holds
it), so every `spit` is exposed at that instant — on 2026-10-06 it lost the
first save of a session. `rename-replace!` covers only an existing
destination; the lock is on the source.

kmet-side: `kmet.libs.fs/publish!` writes the publish temp in place through
`io/output-stream` (explicit UTF-8) and renames it with `fs/move` — one
rename per publish on both hosts, where jolt's `spit` adds a second — and
retries both steps (5 attempts over ~0.8s) before a write may fail. The
session store and the MCP credential store (`kmet.libs.mcp.auth`) publish
through it.

Nothing to remove when the fix ships: kmet's own `fs/move` rename faces the
same OS behavior on either host and keeps its retry, and the in-place temp
write is now the intended implementation. Revisit only if a released Jolt
stops renaming on `spit` or its retry makes kmet's redundant; drop this
entry once #1263 is fixed and released.
