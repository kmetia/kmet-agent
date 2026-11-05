---
name: clojure-edit
description: Choose the right Clojure editing surface — clojure_edit, clojure_edit_replace_sexp, clojure_paren_repair, clojure_eval, the generic edit tool, or the eval clj/* verbs. Use when editing Clojure files to keep edits structure-aware and avoid paren errors.
---

# Editing Clojure Files

## Pick the surface

The form-editing tools (`clojure_edit`, `clojure_edit_replace_sexp`) match
forms or expressions rather than exact text, validate delimiters, and refuse
duplicate top-level definitions. The generic `edit` tool is better for small
text changes and several disjoint hunks in one call; the eval `clj/*` verbs are
better when the edit belongs in a script.

| Change | Surface |
|---|---|
| Replace, delete or insert a whole top-level form by name | `clojure_edit` |
| Change an expression inside a form, or rename a symbol everywhere | `clojure_edit_replace_sexp` |
| Fix unbalanced delimiters after a broken edit | `clojure_paren_repair` |
| Small text changes, comments, several disjoint hunks | `edit` |
| Edit as part of a script or batch | `eval` with `clj/edit-form`, `clj/replace-sexp` or `clj/paren-repair` |
| Edit a definition already in the eval library | `eval` with `eval/edit-form` or `eval/patch` |
| Verify a change in a running nREPL | `clojure_eval` |

`clojure_edit_replace_sexp`, `clojure_paren_repair` and `clojure_eval` are
deferred: load them with `tool_search` when they are not declared. The eval
verbs are reached through the `eval` tool; the editing tools above do not
require it. When both are available, use the tool for a direct edit and the
verb when the edit belongs in a script — do not apply the same edit twice.

## clojure_edit

- Edits a whole top-level form by `form_type` + `form_identifier`. Form types
  are definition keywords (`defn`, `defn-`, `def`, `defmacro`, `defmethod`,
  `deftest`, `ns`, `s/def`, …); alias-qualified heads such as `t/deftest` and
  `s/def` match by their plain keyword.
- `replace` substitutes the form (an empty `content` deletes it);
  `insert_before`/`insert_after` add content beside it and take only the new
  content, never the anchor form. Prefer inserts for adding forms; use
  `replace` only to change an existing one.
- `content` must be one complete, balanced form; unbalanced delimiters are
  rejected, not auto-fixed.
- The inserted form lands outside the anchor's line: a same-line trailing
  comment stays with the anchor, and a comment on its own line stays with the
  next form.
- An edit that would leave two top-level definitions with the same name (or the
  same `defmethod` dispatch) is refused and nothing is written. Fix it by
  deleting or renaming the other definition; do not bypass the guard with text
  editing unless the duplicate is intentional. The guard covers literal
  top-level forms only, not `(comment …)` bodies or reader-conditional
  branches.
- `defmethod` identifiers: `area :rectangle`,
  `convert-length [:feet :inches]`, `shape/area :square`.

## clojure_edit_replace_sexp

- Replaces expressions inside forms by structure; whitespace and formatting are
  ignored.
- `match_form` and `new_form` need complete, balanced expressions; include the
  enclosing form for fragments such as `"...x]]"` or `:else [w j])`.
- `replace_all: true` replaces every occurrence; it is forced to false for
  insert operations.
- An empty `new_form` with `operation: "replace"` deletes the matched
  expression(s).

## eval verbs

- `(clj/edit-form args)`, `(clj/replace-sexp args)` and `(clj/paren-repair args)`
  take the same argument maps and return the same result shape as the tool
  calls; relative paths resolve against the eval call's cwd.
- `(eval/edit-form 'name new-source)` replaces a definition already in the eval
  library and re-evaluates it; `(eval/patch 'name old new)` replaces the first
  structurally equal subform. Both write only after a successful re-evaluation.
- The tools and the eval verbs share `kmet.libs.clj-edit`; do not hand-edit
  around either.

## Edit hygiene

- **Smaller edits succeed more often.** Long functions and batches of several
  functions usually fail; add one form at a time.
- Minimize comments; add them in separate edits when needed.
- Rejected unbalanced content is not auto-fixed — fix it and retry. If a file
  is badly broken, repair it with `clojure_paren_repair` (or
  `(clj/paren-repair {:file_path "…"})`); for persistent errors, break the
  function up and verify in a REPL first.

## New files

1. Write only the namespace declaration with the `write` tool.
2. Add each function one at a time with `clojure_edit` `insert_after`.
3. Test each function before adding the next.

## Verify in nREPL

After editing a namespace, verify it in a running nREPL with `clojure_eval`:

- Evaluate `(require '[my.ns :as ns] :reload)` and then call the changed function.
- The session persists, so vars and namespaces loaded by one call are visible
  to later calls; return to the same port to keep that state.
- Pass `ns` to evaluate in a target namespace (e.g. `ns: "my.app"`); otherwise
  the session's current namespace is used.
- Pass `port` when no project port file (`.nrepl-port`, shadow-cljs, CIDER)
  identifies the server, or when more than one nREPL is running. Port files are
  resolved from the session's working directory; when none identifies a live
  server, the common nREPL ports (7801, 7802, 7803, 7888, 1667) are probed.
- To stop a long evaluation, cancel the run; the connection is closed and the
  result says the evaluation was cancelled. Code already running on the server
  may still complete.
- Unbalanced delimiters in the code are repaired before evaluation and the
  result says so — fix the code instead of relying on it.
