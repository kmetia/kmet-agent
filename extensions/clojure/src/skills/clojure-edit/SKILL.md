---
name: clojure-edit
description: Guidelines for using the clojure_edit, clojure_edit_replace_sexp, clojure_paren_repair and clojure_eval tools. Use when editing Clojure files to ensure structure-aware editing, avoid paren errors, verify changes in a running REPL, and follow best practices for Clojure code generation.
---

# Use Clojure Structure-Aware Editing Tools

ALWAYS use the specialized Clojure editing tools rather than generic text editing.
These tools understand Clojure syntax and prevent common errors.

## Why Use These Tools?
- Avoid exact whitespace matching problems
- Get early validation for parenthesis balance
- Eliminate retry loops from failed text edits
- Target forms by name rather than trying to match exact text

## Core Tools to Use
- `clojure_edit` — Replace entire top-level forms (an empty `content` deletes them)
- `clojure_edit_replace_sexp` — Modify expressions within top-level forms (an empty `new_form` deletes them)
- `clojure_paren_repair` — Fix unbalanced delimiters (parens/brackets/braces) in a file
- `clojure_eval` — Evaluate code in a running nREPL server to verify your changes

`clojure_edit` and `clojure_edit_replace_sexp` REJECT unbalanced delimiters in
the content you pass (missing or extra parens are NOT auto-fixed) — you must
pass complete, balanced forms. When a file's delimiters get broken, run
`clojure_paren_repair` to fix them.

## CODE SIZE DIRECTLY IMPACTS EDIT SUCCESS
- **SMALLER EDITS = HIGHER SUCCESS RATE**
- **LONG FUNCTIONS ALMOST ALWAYS FAIL** — Break into multiple small functions
- **NEVER ADD MULTIPLE FUNCTIONS AT ONCE** — Add one at a time
- Each additional line exponentially increases failure probability
- 5-10 line functions succeed, 20+ line functions usually fail
- Break large changes into multiple small edits

## COMMENTS ARE PROBLEMATIC
- Minimize comments in code generation
- Comments increase edit complexity and failure rate
- Use meaningful function and parameter names instead
- If comments are needed, add them in separate edits

## insert_after / insert_before Semantics
- Pass ONLY the new content — never repeat the anchor form inside `content`
- The inserted form lands OUTSIDE the anchor's own line:
  - a same-line trailing comment stays with the anchor form (`(defn a ...) ;; note` keeps its note)
  - a comment on its own line leads the NEXT form and stays with it
- The inserted form is blank-line separated from surrounding forms

## Duplicate Definitions Are Refused
- `clojure_edit` and `clojure_edit_replace_sexp` refuse an edit that would
  leave two top-level definitions with the same name (or the same `defmethod`
  dispatch), and write nothing.
- Only a duplicate the edit introduces is refused; a pre-existing duplicate
  elsewhere in the file does not block an unrelated edit.
- Fix it by deleting or renaming the other definition — retrying the same
  edit hits the same guard. Do not bypass it with generic text editing unless
  the duplicate is intentional.
- The guard covers literal top-level definitions only (not a `def` inside
  `(comment …)` or the branches of a top-level reader conditional), so keep
  the namespace `require` check after a successful edit.

## Alias-Qualified Forms
- `(t/deftest my-test ...)` matches with plain `form_type: "deftest"`
  (the qualified name `t/deftest` also works)

## match_form Must Be Complete
- `clojure_edit_replace_sexp` match_form/new_form need COMPLETE expressions with balanced parens
- Unbalanced delimiters (a missing/extra paren, e.g. `"(+ x 1"`) are REJECTED with an error — pass the complete, balanced expression exactly as it appears in the file
- Fragments that cannot form a complete expression (`...x]]`, `:else [w j])`) are also rejected — include the full enclosing form

## Handling Parenthesis Errors
- Unbalanced parens in tool content are rejected with a clear error — fix the content yourself and retry
- If a repair leaves the code wrong, break complex functions into smaller, focused ones
- Start with minimal code and add incrementally
- When facing persistent errors, verify in REPL first
- Count parentheses in the content you're adding
- For deep nesting, use threading macros (`->`, `->>`)
- If a file is badly broken, run `clojure_paren_repair` on it

## Creating New Files
1. Start by writing only the namespace declaration using the `write` tool
2. Then add each function one at a time with `clojure_edit` using the "insert_after" operation
3. Test each function before adding the next

## Verifying Changes in the REPL
After editing a namespace, verify it in a running nREPL with `clojure_eval`:
- Evaluate `(require '[my.ns :as ns] :reload)` and then call the changed function.
- The session persists, so vars and namespaces loaded by one call are visible
  to later calls; return to the same port to keep that state.
- Pass `ns` to evaluate in a target namespace (e.g. `ns: "my.app"`);
  otherwise the session's current namespace is used.
- Pass `port` when no project port file (`.nrepl-port`, shadow-cljs, CIDER)
  identifies the server, or when more than one nREPL is running. Port files
  are resolved from the session's working directory; when none identifies a
  live server, the common nREPL ports (7801, 7802, 7803, 7888, 1667) are
  probed.
- To stop a long evaluation, cancel the run; the connection is closed and
  the result says the evaluation was cancelled. Code already running on the
  server may still complete.
- Unbalanced delimiters in the code are repaired before evaluation and the
  result says so — fix the code instead of relying on it.

## Working with Defmethod
Remember to include dispatch values:
- Normal dispatch: `form_identifier: "area :rectangle"`
- Vector dispatch: `form_identifier: "convert-length [:feet :inches]"`
- Namespaced: `form_identifier: "tool-system/validate-inputs :clojure-eval"`

## When to Use Which Tool
| Use case | Tool |
|---|---|
| Replace a whole function | `clojure_edit` |
| Delete a whole function/form | `clojure_edit` with `content: ""` (like edit's empty `newText`) |
| Change one expression inside a function | `clojure_edit_replace_sexp` |
| Delete an expression inside a function | `clojure_edit_replace_sexp` with `new_form: ""` (like edit's empty `newText`) |
| Insert a new function before/after another | `clojure_edit` |
| Rename a symbol everywhere in a file | `clojure_edit_replace_sexp` with `replace_all` |
| Edit an ns declaration | `clojure_edit` |
| Fix unbalanced delimiters after an errant edit | `clojure_paren_repair` |
| Verify a change in a running nREPL | `clojure_eval` |

**Rule of thumb:** prefer `insert_before`/`insert_after` for ADDING new forms
(no need to reproduce the anchor's text); use `replace` only to MODIFY an
existing form's body.
