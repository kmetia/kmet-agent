# Clojure Extension

Clojure-aware tools for kmet, ported from [clojure-mcp](https://github.com/bhauman/clojure-mcp).

## Tools

### `clojure_edit`

Structure-aware form editing. Finds Clojure definitions by type and name, then replaces or inserts around them.

```
clojure_edit:
  file_path: "src/my_app/core.clj"
  form_type: "defn"
  form_identifier: "my-function"
  content: "(defn my-function [x y] (+ x y))"
  operation: "replace"
```

**Parameters:**
- `file_path` — path to .clj/.cljs/.cljc/.bb/.edn file
- `form_type` — `defn`, `defmethod`, `def`, `defmacro`, `deftest`, `ns`, `s/def`, etc.
- `form_identifier` — form name; for defmethod use `"method-name dispatch-value"`
- `content` — replacement Clojure source code
- `operation` — `replace` (default), `insert_before`, `insert_after`

The tools always apply their edits; preview-only and unified-diff modes are not supported.

**Features:**
- defmethod dispatch-value matching (`"shape/area :square"`)
- Unbalanced delimiter rejection (edamame detection — content must be balanced)
- cljfmt formatting
- Similar-match suggestions when form not found

### `clojure_edit_replace_sexp`

S-expression replacement. Changes a specific expression without touching surrounding code.

```
clojure_edit_replace_sexp:
  file_path: "src/my_app/core.clj"
  match_form: "(+ x 2)"
  new_form: "(+ x 10)"
  operation: "replace"
```

**Parameters:**
- `file_path` — path to file
- `match_form` — s-expression(s) to find
- `new_form` — replacement s-expression(s)
- `replace_all` — replace all occurrences (default false)
- `operation` — `replace`, `insert_before`, `insert_after` (required)

The tools always apply their edits; preview-only and unified-diff modes are not supported.

**Features:**
- Whitespace-normalized matching (ignores formatting differences)
- Multi-expression matching (consecutive expressions)
- `replace_all` for renaming symbols across a file
- Standard edit-style file call, full numbered diff, and colored result rendering

Both tools reuse the host's `render-edit-call` and `render-edit-result` renderers.
Their normal result stores the numbered display diff in `:details :diff`.

### `clojure_paren_repair`

Delimiter repair for Clojure files. Detects unbalanced parens/brackets/braces with
edamame and repairs them with parinferish (indent mode), then formats with cljfmt.

```
clojure_paren_repair:
  file_path: "src/my_app/core.clj"
  format: true
```

**Parameters:**
- `file_path` — path to .clj/.cljs/.cljc/.bb/.edn file to repair
- `format` — cljfmt after repair (default true)

**Features:**
- edamame detection of unclosed openers / stray closers
- edamame-based rejection of unbalanced content (content must be balanced)
- cljfmt formatting honoring the project's cljfmt.edn
- Unified diff of the changes

`clojure_edit` and `clojure_edit_replace_sexp` reject unbalanced delimiters
in replacement/match content via the shared `kmet.extensions.clojure.edit-util` pipeline — pass
complete, balanced forms. Use `clojure_paren_repair` to fix a file whose
delimiters are broken.

## Planned: nREPL evaluation

A future `clojure-nrepl-eval` tool would port the nREPL evaluator from
[clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light). It is not
currently implemented.

The official `nrepl.core` Maven client is not usable in the extension SCI
context because it requires `nrepl.tls` and the unavailable
`java.security.cert.Certificate` class. The planned implementation uses the
small bencode-based client from clojure-mcp-light instead, with only bundled
`bencode.core` and `java.net.*` dependencies. The port can omit that project's
timbre/statistics/temp-session-file behavior; kmet has no corresponding
session persistence.

Planned features:

- Connect to a running nREPL server.
- Discover ports through `.nrepl-port` and `lsof`.
- Keep persistent sessions per host and port.
- Repair delimiters with `parinferish` before evaluation.
- Detect Clojure, Babashka, Shadow-CLJS, and Basilisp environments.
- Apply evaluation timeouts.

Planned parameters:

- `port` — nREPL port; auto-discover when omitted.
- `code` — Clojure code to evaluate.
- `timeout` — timeout in milliseconds.

## Skill

### `clojure-edit`

Editing guidelines pulled on demand when working with Clojure files. Covers:
- Why use structure-aware tools
- "Smaller edits = higher success rate"
- Parenthesis error handling
- Creating new files workflow
- defmethod dispatch value examples
- When to use which tool

## When to use which

| Use case | Tool |
|---|---|
| Replace a whole function | `clojure_edit` |
| Change one expression inside a function | `clojure_edit_replace_sexp` |
| Insert a new function before/after another | `clojure_edit` |
| Rename a symbol everywhere in a file | `clojure_edit_replace_sexp` with `replace_all` |
| Edit an ns declaration | `clojure_edit` |
| Fix unbalanced delimiters after an errant edit | `clojure_paren_repair` |

## Dependencies

No `deps.edn` — everything is served by kmet's fixed bundled set
(`extensions.md` § Bundled extension libraries), shared by reference on both
hosts:

- cljfmt 0.16.5 — code formatting (`cljfmt.edn` discovery and its `#re`
  reader live in `kmet.extensions.clojure.edit-util`, so `cljfmt.config` is only loaded for its
  `default-config` var)
- rewrite-clj 1.2.57 — form parsing/zippers
- edamame — delimiter error detection
- parinferish 0.8.0 — delimiter repair (pure Clojure; parinfer is a JVM lib
  and can't run in SCI contexts)
- clojure.spec.alpha — injected because `cljfmt.config` requires it
  (port on bb, the pinned Maven lib on Jolt)

## Skills

- `clojure-edit` — editing guidelines (when to use which tool, paren handling, small edits)
- Ported from clojure-mcp's `clojure_form_edit.md` system prompt
