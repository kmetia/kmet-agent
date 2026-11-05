;; clojure_edit_replace_sexp — s-expression replacement.
;;
;; The operation lives in the shared `kmet.libs.clj-edit` engine; this
;; namespace keeps the tool's schema, renderer and title.

(ns kmet.extensions.clojure.sexp-tool
  (:require [kmet.libs.clj-edit :as clj-edit]
            [kmet.ui.tui.tool-renderers :as renderers]))

(defn title
  [args]
  (clj-edit/title-sexp "clojure_edit_replace_sexp" args))

(defn execute
  "Tool entry point.  Returns {:content str :is-error bool}."
  ([args] (clj-edit/replace-sexp args))
  ([args _on-update _signal ctx]
   (clj-edit/replace-sexp (assoc args :cwd (:cwd ctx)))))

(defn definition
  "The clojure_edit_replace_sexp tool record — a refinement of clojure_edit,
   registered as a `:deferred` source so its schema is not sent until
   `tool_search` loads it (see `kmet.extensions.clojure.core/init`)."
  []
  {:name            "clojure_edit_replace_sexp"
   :label           "Clojure s-expression edit"
   :description
   "Replaces Clojure expressions in a file (only .clj/.cljs/.cljc/.cljd/.bb/.edn/.lpy files are accepted; other types are rejected).\n\nThis tool provides targeted replacement of Clojure expressions within forms. For complete top-level form operations, use clojure_edit instead.\n\nKEY BENEFITS:\n- Syntax-aware matching that understands Clojure code structure\n- Ignores whitespace differences by default, focusing on actual code meaning\n- Matches expressions regardless of formatting, indentation, or spacing\n- Prevents errors from mismatched text or irrelevant formatting differences\n- Can replace all occurrences with replace_all: true\n\nCONSTRAINTS:\n- match_form must contain one or more complete Clojure expressions\n- new_form must contain zero or more complete Clojure expressions; an empty new_form with operation \"replace\" deletes the match, like the edit tool's empty newText\n- Both must be valid Clojure code that can be parsed\n- Unbalanced delimiters in match_form/new_form are REJECTED — pass complete, balanced expressions exactly as they appear in the file.\n- An edit that would leave two top-level definitions of the same name (the same dispatch value for defmethod) is refused; no change is written.\n\nFor insert_before/insert_after, pass ONLY the new content (never repeat the matched form). The inserted form lands outside the match's own line: a same-line trailing comment stays with the matched form, and a comment on its own line stays with the next form.\n\nExamples:\n- Replace a calculation: match_form: (+ x 2)  new_form: (* x 2)\n- Rename a symbol everywhere: match_form: old-name  new_form: new-name  replace_all: true\n- Delete an expression: match_form: (println \"Debug\")  new_form: \"\" (empty string, like edit's empty newText)\n- Replace multiple expressions: match_form: (validate x) (transform x)  new_form: (-> x validate transform)"
   :render-call renderers/render-edit-call
   :render-result renderers/render-edit-result
   :render-shell :self
   :contextual? true
   :prompt-snippet "Replace s-expressions in Clojure code (find by content, replace all or one)"
   :prompt-guidelines
   ["Use clojure_edit_replace_sexp to change a specific expression inside a function without touching the surrounding code."
    "Use clojure_edit when replacing an entire top-level form (defn, defmethod, etc.)."
    "match_form must be a valid, parseable Clojure expression (not just comments or whitespace)."
    "new_form must be valid Clojure code; pass \"\" (empty string) with operation \"replace\" to delete the matched expression(s), like the edit tool's empty newText — insert operations require content."
    "replace_all is forced to false for insert_before and insert_after."
    "An edit that would introduce a duplicate top-level definition is refused and nothing is written — delete or rename the other copy instead of retrying."
    "Multiple consecutive expressions in match_form are matched as a sequence."]
   :parameters
   {:type       "object"
    :required   ["file_path" "match_form" "new_form" "operation"]
    :properties
    {"file_path"   {:type        "string"
                    :description "Path to a Clojure file (.clj/.cljs/.cljc/.cljd/.bb/.edn/.lpy) to edit"}
     "match_form"  {:type        "string"
                    :description "The s-expression to find (include # for anonymous functions)"}
     "new_form"    {:type        "string"
                    :description "The s-expression(s) to use for the operation. Pass \"\" (empty string) with operation \"replace\" to delete the matched expression(s), like the edit tool's empty newText; insert_before/insert_after require non-empty content."}
     "replace_all" {:type        "boolean"
                    :description "Whether to replace all occurrences (default: false)"}
     "operation"   {:type        "string"
                    :enum        ["replace" "insert_before" "insert_after"]
                    :description "The editing operation to perform (default: replace)"}}}
   :execute execute :title title})
