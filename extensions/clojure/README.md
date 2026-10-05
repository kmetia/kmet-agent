# Clojure Extension

Clojure-aware tools for kmet, ported from [clojure-mcp](https://github.com/bhauman/clojure-mcp)
(the nREPL client follows [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light)).

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
- `content` — replacement Clojure source code; pass `""` with `operation: "replace"` to delete the form (same convention as the edit tool's empty `newText`)
- `operation` — `replace` (default), `insert_before`, `insert_after`

The tools always apply their edits; preview-only and unified-diff modes are not supported.

**Features:**
- defmethod dispatch-value matching (`"shape/area :square"`)
- Unbalanced delimiter rejection (edamame detection — content must be balanced)
- Deletion: an empty `content` with `operation: "replace"` removes the form
- cljfmt formatting — only the forms the edit changed are reformatted;
  untouched forms keep their bytes
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
- `new_form` — replacement s-expression(s); pass `""` with `operation: "replace"` to delete the match (same convention as the edit tool's empty `newText`)
- `replace_all` — replace all occurrences (default false)
- `operation` — `replace`, `insert_before`, `insert_after` (required)

The tools always apply their edits; preview-only and unified-diff modes are not supported.

**Features:**
- Whitespace-normalized matching (ignores formatting differences)
- Multi-expression matching (consecutive expressions)
- `replace_all` for renaming symbols across a file
- Deletion: an empty `new_form` with `operation: "replace"` removes the matched expression(s), including multi-expression matches and with `replace_all`
- cljfmt formatting — only the forms the edit changed are reformatted;
  untouched forms keep their bytes
- Standard edit-style file call, full numbered diff, and colored result rendering

Both tools reuse the host's `render-edit-call` and `render-edit-result` renderers.
Their normal result stores the numbered display diff in `:details :diff`.

### `clojure_paren_repair`

Delimiter repair for Clojure files. Detects unbalanced parens/brackets/braces with
edamame and repairs them delimiter-only (parinferish's tokenizer with
indentation-based closing; existing code is never moved or restructured),
then formats with cljfmt.

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

### `clojure_eval`

Evaluate Clojure code in a running nREPL server. The server must already be
running: `bb nrepl`, `clj -M:nrepl`, `lein repl`, or the project's REPL.

```
clojure_eval:
  code: "(require '[my.app :as app] :reload)"
  port: 7888          # optional — port files, then the common nREPL ports
  host: "127.0.0.1"   # optional
  ns: "my.app"        # optional target namespace
  timeout: 120000     # optional read timeout in milliseconds
```

**Parameters:**
- `code` — Clojure code to evaluate (required; one or more forms)
- `port` — nREPL port; auto-discovered when omitted
- `host` — nREPL host (default `127.0.0.1`)
- `ns` — target namespace; omitted means the session's current namespace
- `timeout` — read timeout in milliseconds (default 120000)

**Features:**
- Port discovery through `.nrepl-port`, `.shadow-cljs/nrepl.port`,
  `.shadow-cljs/.nrepl-port`, `.cider-nrepl.port` and `nrepl-port`, resolved
  from the session's working directory; when no port file identifies a live
  server, the common nREPL ports (7888, 1667) are probed; every candidate is
  validated with an nREPL `describe` before use
- Persistent sessions per host:port (in memory for the kmet process) — vars
  and loaded namespaces survive across calls
- The target namespace is set through the nREPL `ns` op, with an
  `(in-ns 'ns)` fallback for servers that do not implement it (babashka's
  nREPL answers `unknown-op`)
- Delimiter repair before evaluation (the same delimiter-only repair as
  `clojure_paren_repair`), reported explicitly when it fires instead of
  silently correcting the code
- Output interleaved with values in arrival order (`=> v` lines), including
  stdout/stderr
- Evaluation failures are an explicit `Eval error (class …)` result
- On timeout the tool sends an nREPL `interrupt`; the environment (`:clj`,
  `:bb`, `:shadow`, `:basilisp`) is detected from `describe` and reported in
  `:details`. The connect budget and the read deadline use `Socket.connect`'s
  timeout and `Socket.setSoTimeout`, so a silent server is cut off at the
  deadline (on Jolt's Windows build the read timeout is stored but not
  enforced — blocking sockets; an explicit cancel still closes the connection
  and unblocks it)
- Cancelling the run (abort) closes the connection and returns an
  `Evaluation cancelled (abort requested)` result instead of waiting for the
  timeout

**Why the client is in-tree:** the official `nrepl.core` needs `nrepl.tls`
(`java.security.cert.Certificate`, unavailable in the extension context), and
`nrepl/bencode` — even declared through a `deps.edn` — fails SCI source
evaluation (`definline`, `clojure.lang` imports). The extension therefore
carries a small pure-Clojure bencode codec and socket client in
`kmet.extensions.clojure.nrepl`. No `deps.edn` means the shipped extension
stays loadable in `jolt dist` builds.

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
| Verify a change in a running nREPL | `clojure_eval` |

## Dependencies

No `deps.edn` — everything is served by kmet's fixed bundled set
(`src/kmet/extension.md` § Bundled extension libraries), shared by reference on both
hosts:

- cljfmt 0.16.6 — code formatting (`cljfmt.edn` discovery and its `#re`
  reader live in `kmet.extensions.clojure.edit-util`, so `cljfmt.config` is only loaded for its
  `default-config` var)
- rewrite-clj 1.3.58 — form parsing/zippers
- edamame — delimiter error detection
- parinferish 0.8.0 — delimiter-repair tokenizer (pure Clojure; parinfer is a JVM lib
  and can't run in SCI contexts)
- clojure.spec.alpha — injected because `cljfmt.config` requires it
  (port on bb, the pinned Maven lib on Jolt)

## Skills

- `clojure-edit` — editing guidelines (when to use which tool, paren handling, small edits, REPL verification with `clojure_eval`)
- Ported from clojure-mcp's `clojure_form_edit.md` system prompt
