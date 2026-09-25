# tree-sitter extension

Structural code intelligence for kmet: five navigation tools powered by the
official `tree-sitter` CLI loading WASM grammars as data, plus syntax
validation hooks that block broken writes and warn on broken edits.

Feature parity target: rab's native tree-sitter extension and pi's
`pi-tree-sitter` package. Design facts and validated constraints live in
[`SPEC.md`](src/SPEC.md) for the design and implementation specification.

## Tools

| Tool | What it does |
|---|---|
| `list_symbols` | All definitions (functions, classes, vars, methods, types…) in a file with line numbers and kinds |
| `find_definition` | Where a named symbol is defined across the project (structural match, not text grep) |
| `get_symbol_body` | The complete source body of one definition |
| `find_callers` | Every call site of a named function/method across the project, shown as `caller(file:line)` |
| `find_callees` | What one function calls — unique callees with their first call site |

All five prefer structure over text: definitions are matched by parsed node
shape per language rule set, and every call records its nearest enclosing
definition, which is what makes caller/callee queries work.

## Supported languages

| Language | Extensions | Grammar source |
|---|---|---|
| Clojure | `.clj .cljs .cljc .edn` | Zed extension registry (`clojure` 0.2.2) |
| Python | `.py` | Official `tree-sitter-python` release (v0.25.0) |
| TypeScript | `.ts` | Official `tree-sitter-typescript` release (v0.23.2) |
| TSX | `.tsx` | Official `tree-sitter-typescript` release (v0.23.2) |

Grow the table by adding an entry to
`src/kmet/extensions/tree_sitter/libs_manifest.edn` (pinned URL +
sha256 + probe snippet) and a rule set under
`src/kmet/extensions/tree_sitter/queries/`.

## Auto-download (first use)

Nothing is fetched at install time. On first use the extension downloads,
sha256-verifies against pinned manifests, and caches:

1. the `tree-sitter` CLI binary (v0.26.13) into
   `~/.kmet/agent/tree-sitter/bin/` — on Termux a glibc launcher script is
   generated next to it;
2. each language's grammar WASM into `~/.kmet/agent/tree-sitter/libs/`
   (~0.1–1.5 MB per language), verified by actually parsing a tiny probe
   snippet before it is kept.

Expect a one-time latency of a few seconds per artifact; everything after
that is local and fast (parses run in short-lived subprocesses). Corrupted
cache entries are detected via sha256 and re-fetched automatically. Offline
with a cold cache, tool calls fail with a clear error and retry on the next
call — no partial state.

**Privacy:** files are parsed locally by the locally-cached `tree-sitter`
binary. Source code never leaves the machine; only manifest metadata
(version pins) references public GitHub/Zed URLs at download time.

## Syntax validation hooks

`write` calls whose content does not parse are **blocked** with line/column
detail and a fix hint; `edit` results that leave a file unparseable get a
⚠️ warning appended to the tool result (non-blocking). Clojure-family files
defer to the clojure extension when it is enabled, falling back to a
bracket-balance check otherwise; files with no known grammar are never
validated.

Note: tree-sitter error recovery is tolerant — most syntax mistakes produce
parse errors the hooks catch, but a few recoverable ones may slip through.
See [`SPEC.md`](src/SPEC.md) §Hook policy.
