# Clojure Extension — Implementation Plan

## Overview

A kmet extension providing Clojure-aware tools, ported from [clojure-mcp](https://github.com/bhauman/clojure-mcp) and [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light).

## Architecture

```
extensions/clojure/
├── bb.edn                — standalone `bb test` task (4 namespaces)
├── extension.edn         — {:name "clojure" :entry "src/kmet/extensions/clojure/core.clj"}
├── README.md             — usage info with examples
├── skills/
│   └── clojure-edit/
│       └── SKILL.md      — editing guidelines for all 3 tools (port of clojure_form_edit.md)
├── src/
│   ├── edit_util.clj     — shared: edamame detect + parinferish repair + cljfmt + zipper helpers
│   ├── edit_tool.clj     — ns edit-tool (= clojure_edit)
│   ├── sexp_tool.clj     — ns sexp-tool (= clojure_edit_replace_sexp)
│   ├── paren_repair.clj  — ns paren-repair (= clojure_paren_repair) + write-reject/edit-warn hooks
│   └── kmet/extensions/clojure/
│       └── core.clj      — ns kmet.extensions.clojure.core (registers 3 tools, contributes skill via :resources-discover)
└── test/
    ├── edit_util_test.clj — delimiter-error? / repair-delimiters / lint-repair + formatting
    ├── edit_tool_test.clj — clojure_edit matrix
    ├── sexp_tool_test.clj — clojure_edit_replace_sexp matrix
    └── paren_repair_test.clj — repair + hooks (write reject, edit warn)
```

Each extension runs in an isolated SCI context. Dependencies are declared per-extension in a `deps.edn`, resolved by `borkdude.deps` in-process — this extension needs none: rewrite-clj, cljfmt, edamame and parinferish are all in kmet's fixed bundled set (extensions.md § Bundled extension libraries), shared by reference on both hosts. The shared library layers `kmet.tui.*` and `kmet.libs.*` are likewise injected by reference.

Tool namespaces use the tool name (e.g. `clojure_edit` → ns `edit-tool`). The entry point `kmet.extensions.clojure.core` requires tool namespaces and calls their `register!` functions.

## Completed

### clojure_edit (edit-tool.clj)

Structure-aware Clojure form editing. Full port of clojure-mcp `form_edit/{core,pipeline,tool}.clj`.

**Tool metadata** — matches clojure-mcp exactly:
- Name: `clojure_edit`
- Description with examples (replace, insert_before, insert_after, ns, defmethod)
- prompt-snippet, prompt-guidelines aligned
- JSON schema with string keys, all 5 params required
- Enum on operation: `replace`, `insert_before`, `insert_after`

**Features ported:**
- Form finding by type (defn, defmethod, def, defmacro, deftest, ns, ...) + name via BFS zipper traversal (max-depth 3)
- defmethod dispatch-value matching (`"area :rectangle"`, `"shape/area :square"`)
- defmethod dispatch extraction from replacement content when not in form_identifier
- replace / insert_before / insert_after operations
- Comment-leading replacement (special `;` handling — absorbs preceding comments)
- Delimiter repair (edamame detect + parinferish fix) on replacement content before editing
- Partial formatting (format replacement in isolation via cljfmt, re-indent to column)
- Full-file cljfmt formatting after edit
- Similar-match suggestions when form not found (namespace-qualified alternatives)
- Unified diff output via `/usr/bin/diff -u`

**Pipeline (matches clojure-mcp):**
1. Reject unbalanced replacement content (edamame `delimiter-error?`) — no auto-repair
2. Enhance defmethod name from replacement content
3. Load source file (UTF-8)
4. Parse source into rewrite-clj zipper
5. Find form by type + name (BFS with max-depth 3)
6. Capture form column position
7. Format replacement in isolation, re-indent to column
8. Re-edit with formatted content
9. Format whole file via cljfmt
10. Write file
11. Generate unified diff

**Not ported (kmet-specific):**
- File timestamp tracking (`file-timestamps`) — clojure-mcp uses this for multi-edit safety; kmet's edit tool doesn't have it
- Config-based cljfmt toggle (`:partial`, `true`, `false`) — kmet always uses full formatting
- Write-file-guard — not applicable without nREPL integration

### clojure_edit_replace_sexp (sexp-tool.clj)

S-expression level replacement. Full port of clojure-mcp `form_edit/{core,pipeline,tool}.clj` (sexp variant).

**Tool metadata** — matches clojure-mcp exactly:
- Name: `clojure_edit_replace_sexp`
- Description with examples (replace, rename, remove, multi-expression)
- prompt-snippet, prompt-guidelines aligned
- JSON schema with string keys, 4 params required: `file_path`, `match_form`, `new_form`, `operation`
- Enum on operation: `replace`, `insert_before`, `insert_after`

**Features ported:**
- Find s-expressions by normalized content match (ignores whitespace differences)
- `*match-clean*` fallback: when normal match fails, strips comments too
- replace / insert_before / insert_after operations
- `replace_all` — replace all occurrences in the file
- Multi-expression matching: match consecutive expressions as a sequence
- Truncation strategy: for multi-match, truncate to first expr then replace
- Delimiter repair on both match_form and new_form before editing
- Full-file cljfmt formatting after edit
- Unified diff output
- `dry_run` parameter (`"diff"` / `"new-source"`)

**Validation (matches clojure-mcp):**
- `match_form` must be parseable and contain at least one S-expression (not just comments/whitespace)
- `new_form` must be parseable Clojure code
- `replace_all` forced to false for insert_before/insert_after operations

**Not ported:**
- `whitespace_sensitive` parameter — always uses normalized matching (the common case; original doesn't use it in the sexp pipeline either)
- File timestamp tracking — kmet-specific omission
- Partial formatting — sexp pipeline doesn't use it

### clojure-edit skill (skills/clojure-edit/SKILL.md)

Editing guidelines for the Clojure tools. Port of clojure-mcp `clojure_form_edit.md` prompt.

**Content:**
- "ALWAYS use specialized Clojure editing tools"
- "SMALLER EDITS = HIGHER SUCCESS RATE"
- "NEVER ADD MULTIPLE FUNCTIONS AT ONCE"
- "COMMENTS ARE PROBLEMATIC"
- Parenthesis error handling strategies
- Creating new files workflow
- defmethod dispatch value examples
- "When to Use Which Tool" decision table

**Adapted for kmet:**
- Replaced `file_edit`/`file_write` with kmet `edit`/`write`
- Removed MCP prefix instructions
- Added tool comparison table

## Planned

### clojure_read_file

Smart Clojure file reader with collapsed view and pattern-based expansion. Port of clojure-mcp `unified_read_file/pattern_core.clj`.

**Feasibility: HIGH** — pure rewrite-clj + clojure.string, same stack as the existing edit tools. Straight port; the only adaptation is dropping the nREPL write-file-guard timestamp tracking (kmet doesn't track file mtimes).

**Features:**
- Collapsed view: show only form signatures (e.g., `(defn foo [x] ...)`) for large files
- Pattern matching: `name_pattern` regex to expand matching forms, `content_pattern` for content search
- Full view: when no patterns provided, return full file content
- defmethod support: match on combined `"method-name dispatch-value"` strings
- Reader conditional support: `#?(:clj ...)` forms extracted and displayed with platform tags
- Form name collection: list all top-level form names for reference

**Tool parameters:**
- `path` — file path
- `collapsed` — boolean, force collapsed view
- `name_pattern` — regex to match form names
- `content_pattern` — regex to match form content

### clojure-nrepl-eval (from clojure-mcp-light)

nREPL evaluation tool. Port of clojure-mcp-light `nrepl_eval.clj`.

**Feasibility: HIGH** — verified end-to-end in an SCI context: a bencode-based client (using bb's bundled `bencode.core`, injected by reference) talking to a real babashka nREPL server successfully evaluated `(+ 1 2)` → `3`. The official `nrepl.core` Maven client does NOT run: it hard-requires `nrepl.tls`, which needs `java.security.cert.Certificate` — a class missing from bb's `babashka.classes/all-classes` (everything else it needs is present). So the client must be the ~150-line bencode-based one from clojure-mcp-light (`nrepl_client.clj`), which has zero deps beyond bundled `bencode.core` + `java.net.*`. Port that; drop its timbre/stats/tmp session-file bits (kmet has no session persistence) or keep a minimal in-memory session cache.

**Features:**
- Connect to running nREPL server
- Auto-discover ports via `.nrepl-port` file and `lsof`
- Persistent sessions per host:port
- Auto-repair delimiters before evaluation (parinferish)
- Environment type detection (clj, bb, shadow-cljs, basilisp)
- Timeout handling

**Tool parameters:**
- `port` — nREPL port (auto-discover if omitted)
- `code` — Clojure code to evaluate
- `timeout` — milliseconds

### clojure-paren-repair

Delimiter repair tool. Port of clojure-mcp `paren_repair/{core,tool}.clj` (file-based) — the clojure-mcp-light `paren_repair.clj` CLI (stdin mode) is not applicable as a kmet tool, but its `fix-delimiters` algorithm is the model.

**Feasibility: HIGH — and the single most valuable addition**: it enables real delimiter auto-repair, replacing the current `lint-repair` stub in edit_util.clj (which returns `[code false]` and lets broken delimiters surface as errors). Verified in an SCI context: `parinferish 0.8.0` (pure Clojure, single `.cljc`, zero deps) evaluates and runs — `(parse s {:mode :indent})` + `flatten` correctly repaired `"(defn foo [x] (+ x 1"` → `"(defn foo [x] (+ x 1))"`. Declared in the extension's deps.edn it resolves via borkdude.deps like cljfmt does. NOT feasible: `parinfer 0.4.0` (com.oakmac/parinfer, the JVM lib clojure-mcp uses) — extension contexts evaluate jar sources under SCI and only expose bb-bundled classes; a Java lib needs real JVM classes. edamame's role stays detection (`:edamame/opened-delimiter` in ex-data).

**IMPLEMENTED (this session):**
- `edit_util/delimiter-error?` — detection; `clojure_edit` and `clojure_edit_replace_sexp` REJECT unbalanced content outright (no auto-repair). `repair-delimiters` (parinferish) remains only for the dedicated `clojure_paren_repair` tool.
- `paren_repair.clj` — standalone `clojure_paren_repair` tool (detect → repair → verify → cljfmt → write → diff), registered in core.clj
- `core.clj` — also contributes `skills/clojure-edit` via the `:resources-discover` event (skill was previously dead content — never wired into discovery)
- Host fix in `kmet.app.extensions`: `:extension-dir` for a directory extension was computed as the PARENT of the extension dir (wrong), which broke `:extension-dir`-relative resource discovery for ALL dir extensions (clojure + mcp-adapter skills silently never loaded). Now the extension's own directory; `get-loaded-extensions` exposes it; regression tests added
- deps.edn/bb.edn: + parinferish 0.8.0
- Tests: edit-util-test (repair/detection), paren-repair-test (tool behavior incl. format toggle, .edn, diffs)

**Pipeline (matches clojure-mcp `repair-file!`):**
1. edamame `delimiter-error?` on the file content (or on replacement content for lint-repair)
2. parinferish indent-mode repair → flattened text
3. verify the result parses (no delimiter error)
4. cljfmt format (respecting project cljfmt.edn via edit-util)
5. write file + unified diff

**Tool parameters:**
- `file_path` — path to .clj/.cljs/.cljc/.bb/.edn file to repair
- `format` — boolean, cljfmt after repair (default true)

### Additional skills (from clojure-mcp prompts)

- `clojure_repl_form_edit.md` — REPL-driven development workflow (EXPLORE → DEVELOP → CRITIQUE → BUILD → EDIT → VERIFY)
- `create_project_summary.md` — project summary generation prompt

## Dependencies

All provided by kmet's fixed bundled set (no extension `deps.edn`):

| Library | Purpose |
|---|---|
| rewrite-clj 1.2.57 | Zipper-based Clojure code parsing and transformation |
| cljfmt 0.16.5 | Clojure code formatting |
| edamame | Delimiter error detection (parser) |
| parinferish 0.8.0 | Delimiter repair (indent-mode; pure Clojure, SCI-compatible) |
| clojure.spec.alpha | injected: `cljfmt.config`'s require |
| bencode (bundled in bb) | nREPL wire encoding (clojure-nrepl-eval) |

Note: `parinfer 0.4.0` (com.oakmac/parinfer, the JVM lib clojure-mcp uses) is NOT usable — extension contexts serve jar sources under SCI and only expose bb-bundled classes. parinferish is the drop-in pure-Clojure replacement (same as clojure-mcp-light).

## Alignment with clojure-mcp

### What aligns exactly
- Tool names, descriptions, prompt-snippet, prompt-guidelines
- JSON schema format (string keys, required arrays, enum)
- Core algorithms: BFS form finding, multi-sexp matching, comment handling
- Delimiter repair pipeline (edamame detect → parinferish fix)
- Formatting pipeline (cljfmt with community indent style)
- Validation logic (parseable forms, non-empty sexprs)

### Intentional differences (kmet-specific)
- No file timestamp tracking — kmet doesn't track modification timestamps
- No write-file-guard — not applicable without nREPL integration
- No config-based cljfmt toggle — always uses full formatting
- No `whitespace_sensitive` parameter — always normalized matching
- Diff via `babashka.process` shell instead of `com.github.difflib` Java lib
- File I/O via `babashka.fs` instead of `java.io.File`
- Extension isolation via SCI context instead of MCP server
