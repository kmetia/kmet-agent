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
   "Edits a top-level form (`defn`, `def`, `defmethod`, `ns`, `deftest`) in a Clojure file using the specified operation.\n\nPREFER this tool over generic file editing tools for Clojure files (`.clj` `.cljs` `.cljc` `.cljd` `.bb` `.edn` `.lpy`). It rejects other file types.\n\nThis tool MAKES it EASIER to match a definition that exists in the file AS you only have to match the type of definition `form_type` and the complete identifier `form_identifier` of the definition. This prevents the repeated mismatch errors that occur when trying match an entire string of text for replacement.\nThis tool validates the structure of the Clojure code that is being inserted into the file and will provide linting feedback for things such as parenthetical errors.\n\nNOTE: `content` must be a complete, balanced Clojure form. Unbalanced delimiters are REJECTED with an error — pass the complete form exactly as it should appear in the file.\n\nAn edit that would leave two top-level definitions of the same name (the same dispatch value for `defmethod`) is refused before anything is written; delete or rename the other definition and retry.\n\nOperations:\n- \"replace\": Replaces the form with new content (pass \"\" to delete the form)\n- \"insert_before\": Inserts content before the form\n- \"insert_after\": Inserts content after the form\n\nFor insert_before/insert_after, pass ONLY the new content (never repeat the anchor form). The inserted form may be of any type — form_type names the anchor, and the content is validated against its own head (a def may be inserted beside a defn). The inserted form lands outside the anchor's own line: a same-line trailing comment stays with the anchor form, and a comment on its own line stays with the next form.\n\nThe form is identified by its type (defn, def, deftest, s/def, ns, defmethod etc.) and complete identifier. Alias-qualified macros (t/deftest, s/def) match by their plain keyword (deftest, def).\n\nExample: Replace a function definition:\n- file_path: \"/path/to/file.clj\"\n- form_identifier: \"example-fn\"\n- form_type: \"defn\"\n- operation: \"replace\"\n- content: \"(defn example-fn [x] (* x 2))\"\n\nExample: Insert a helper function before a function:\n- file_path: \"/path/to/file.clj\"\n- form_identifier: \"example-fn\"\n- form_type: \"defn\"\n- operation: \"insert_before\"\n- content: \"(defn helper-fn [x] (* x 2))\"\n\nExample: Edit a namespace declaration (form_identifier is the namespace name):\n- file_path: \"/path/to/file.clj\"\n- form_identifier: \"my.app.core\"\n- form_type: \"ns\"\n- operation: \"replace\"\n- content: \"(ns my.app.core (:require [clojure.string :as str]))\"\n\nFor `defmethod` forms, include the dispatch value (`area :rectangle`) in `form_identifier`.\nMany `defmethod` definitions have qualified names like `shape/area`, so use the complete identifier.\n\nExample: Replace a specific `defmethod` implementation:\n- form_identifier: \"shape/area :square\"\n- form_type: \"defmethod\"\n- operation: \"replace\"\n- content: \"(defmethod shape/area :square [{:keys [w h]}] (* w h))\"\n\nThe tool returns a diff showing the changes made to the file."
   :render-call renderers/render-edit-call
   :render-result renderers/render-edit-result
   :render-shell :self
   :contextual? true
   :prompt-snippet "Structure-aware Clojure form editing (replace, insert_before, insert_after)"
   :prompt-guidelines
   ["Use clojure_edit instead of the generic edit tool for Clojure files when targeting a specific form by name."
    "form_type must be the Clojure definition keyword: defn, defmacro, def, defmethod, deftest, ns, s/def, etc."
    "form_identifier is the complete identifier; for defmethod use 'method-name dispatch-value' (e.g. 'area :rectangle')."
    "Many defmethod forms use qualified names (e.g. 'shape/area :square') — always use the complete identifier."
    "When replacing a defmethod, the dispatch value is extracted from content if not in form_identifier."
    "content must be a complete, balanced Clojure form — unbalanced delimiters are rejected with an error. Pass \"\" (empty string) with operation \"replace\" to delete the form; insert operations require content."
    "An edit that would introduce a duplicate top-level definition is refused and nothing is written — delete or rename the other copy instead of retrying."
    "Use the edit tool instead when making multiple small text replacements across a file."
    "Prefer clojure_edit for targeted changes to a known function, macro, or spec definition."]
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
