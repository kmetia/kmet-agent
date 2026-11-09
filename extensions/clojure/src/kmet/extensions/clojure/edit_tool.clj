;; clojure-edit — Structure-aware Clojure form editing for kmet.
;;
;; The operation lives in the shared `kmet.libs.clj-edit` engine; this
;; namespace keeps the tool's schema, renderer and title.

(ns kmet.extensions.clojure.edit-tool
  (:require [kmet.libs.clj-edit :as clj-edit]
            [kmet.ui.tui.tool-renderers :as renderers]))

(defn title
  [args]
  (clj-edit/title "clojure_edit" args))

(defn execute
  "Tool entry point.  Returns {:content str :is-error bool}."
  ([args] (clj-edit/edit-form args))
  ([args _on-update _signal ctx]
   (clj-edit/edit-form (assoc args :cwd (:cwd ctx)))))

(defn definition
  "The clojure_edit tool record — the family's entry point, registered
   directly (see `kmet.extensions.clojure.core/init`)."
  []
  {:name            "clojure_edit"
   :label           "Clojure form edit"
   :description
   "Edits a top-level form (`defn`, `def`, `defmethod`, `ns`, `deftest`, …) in a Clojure file by form type and identifier instead of exact text.\n\nUse this tool for whole-form changes: replace, insert or delete a top-level form. Use the generic edit tool for small text changes or several disjoint hunks; use the eval `clj/edit-form` verb for scripted edits, or the eval `tools` bridge when the tool chain is wanted.\n\nOperations:\n- \"replace\": replace the form with `content` (pass \"\" to delete the form)\n- \"insert_before\" / \"insert_after\": insert `content` beside the form — pass only the new content, never the anchor form\n\n`content` must be a complete, balanced Clojure form; unbalanced delimiters are rejected. An edit that would leave two top-level definitions with the same name (or the same `defmethod` dispatch) is refused and nothing is written.\n\nFor `defmethod`, include the dispatch value in `form_identifier` (e.g. \"area :rectangle\", \"shape/area :square\"). Alias-qualified heads such as `t/deftest` match with the plain form type.\n\nReturns a diff of the change."
   :render-call renderers/render-edit-call
   :render-result renderers/render-edit-result
   :render-shell :self
   :contextual? true
   :prompt-snippet "Structure-aware Clojure form editing (replace, insert_before, insert_after)"
   :prompt-guidelines
   ["Use clojure_edit to replace, insert or delete a whole top-level form; it matches by form_type and form_identifier, not by exact text."
    "Use the generic edit tool for small text changes or several disjoint hunks in one file."
    "content must be a complete, balanced Clojure form; unbalanced delimiters are rejected rather than repaired."
    "For defmethod, include the dispatch value in form_identifier; when replacing, the dispatch is read from content if omitted."
    "Pass \"\" with operation \"replace\" to delete the form; insert operations require content."
    "An edit that would introduce a duplicate top-level definition is refused and nothing is written."]
   :parameters
   {:type       "object"
    :required   ["file_path" "form_identifier" "form_type" "operation" "content"]
    :properties
    {"file_path"       {:type        "string"
                        :description "Path to a Clojure file (.clj/.cljs/.cljc/.cljd/.bb/.edn/.lpy) containing the form to edit"}
     "form_identifier" {:type        "string"
                        :description "Complete identifier of the form (e.g. function name, shape/area, or convert-length [:feet :inches])"}
     "form_type"       {:type        "string"
                        :description "Type of the form (e.g. defn, def, ns, defmethod, s/def)"}
     "operation"       {:type        "string"
                        :enum        ["replace" "insert_before" "insert_after"]
                        :description "The editing operation to perform"}
     "content"         {:type        "string"
                        :description "New content to use for the operation. Pass \"\" (empty string) with operation \"replace\" to delete the form (same convention as the edit tool's empty newText); insert_before/insert_after require non-empty content."}}}
   :execute execute :title title})
