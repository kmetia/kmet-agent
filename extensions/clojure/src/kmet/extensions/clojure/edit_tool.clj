;; clojure-edit — Structure-aware Clojure form editing for kmet.
;;
;; Full port of clojure-mcp form_edit/{core,pipeline,tool}.clj.
;; Uses rewrite-clj zippers to find forms by type+name and
;; replace/insert them, then formats with cljfmt.

(ns kmet.extensions.clojure.edit-tool
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.extensions.clojure.edit-util :as util]
            [kmet.app.ui.tool-renderers :as renderers]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: form matching
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- node-string [zloc]
  (when zloc
    (let [node (z/node zloc)
          tag  (n/tag node)]
      (str/trim
       (if (= tag :meta)
         (some-> zloc z/down z/right z/node n/string)
         (n/string node))))))

(defn- parse-form-name [form-name]
  (if (string? form-name)
    (let [normalized (-> form-name str/trim (str/replace #"\s+" " "))]
      (str/split normalized #"\s+" 2))
    [form-name nil]))

(defn- tag-match? [expected actual]
  (or (= actual expected)
      (= actual (str expected "-"))))

(defn- check-tag [first-elem tag]
  (let [actual (str/trim (n/string (z/node first-elem)))]
    (when (or (tag-match? tag actual)
              ;; alias-qualified macros (t/deftest, s/def, my/defmethod) match
              ;; by unqualified name — consistent with collect-similar
              (when-let [sym (try (z/sexpr first-elem) (catch Exception _ nil))]
                (and (symbol? sym) (tag-match? tag (name sym)))))
      first-elem)))

(defn- matches-dispatch? [dispatch-elem expected]
  (when (and dispatch-elem expected)
    (= (node-string dispatch-elem) expected)))

(defn- is-top-level-form? [zloc tag dname]
  (try
    (some-> zloc
            z/down
            (check-tag tag)
            z/right
            (#(let [[exp-name exp-dispatch] (parse-form-name dname)]
                (when (= (node-string %) exp-name)
                  (if exp-dispatch
                    (some-> % z/right (matches-dispatch? exp-dispatch))
                    true)))))
    (catch Exception _ false)))

(defn- find-top-level-form
  ([zloc tag dname] (find-top-level-form zloc tag dname 3))
  ([zloc tag dname max-depth]
   (let [similar   (atom [])
         queue     (atom [[zloc 0]])
         base-name (first (parse-form-name dname))]
     (letfn [(collect-similar [loc]
               (try
                 (let [sexpr (z/sexpr loc)]
                   (when (and (list? sexpr) (> (count sexpr) 1))
                     (let [ftag  (first sexpr)
                           fname (second sexpr)]
                       (when (and (symbol? ftag) (symbol? fname)
                                  (tag-match? tag (name ftag))
                                  (= (name fname) base-name))
                         (swap! similar conj {:form-name     dname
                                              :qualified-name fname
                                              :tag            ftag})))))
                 (catch Exception _ nil)))]
       (loop []
         (if-let [[loc depth] (first @queue)]
           (do (swap! queue rest)
               (if (is-top-level-form? loc tag dname)
                 {:zloc loc :similar-matches @similar}
                 (do
                   (collect-similar loc)
                   (when-let [r (z/right loc)]
                     (swap! queue conj [r depth]))
                   (when (< depth max-depth)
                     (loop [child (z/down loc)]
                       (when child
                         (swap! queue conj [child (inc depth)])
                         (recur (z/right child)))))
                   (recur))))
           {:zloc nil :similar-matches @similar}))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: edit operations
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- edit-top-level-form [zloc tag dname content-str edit-type]
  (let [{:keys [zloc similar-matches]} (find-top-level-form zloc tag dname)]
    (if-not zloc
      {:error           true
       :message         (str "Could not find form '" dname "' of type '" tag "'")
       :similar-matches similar-matches}
      (let [updated (case edit-type
                      :replace       (util/replace-form zloc content-str)
                      :insert-before (util/insert-before-form zloc content-str)
                      :insert-after  (util/insert-after-form zloc content-str))]
        {:zloc updated :similar-matches similar-matches}))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; defmethod helpers
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- extract-dispatch-from-defmethod [source-str]
  (try
    (let [zloc  (z/of-string source-str)
          sexpr (z/sexpr zloc)]
      (when (and (list? sexpr)
                 (= (first sexpr) 'defmethod)
                 (>= (count sexpr) 3))
        [(name (second sexpr))
         (pr-str (nth sexpr 2))]))
    (catch Exception _ nil)))

(defn- enhance-defmethod-name [def-type def-name content]
  (if (= def-type "defmethod")
    (let [parts (str/split def-name #"\s+")]
      (if (= (count parts) 1)
        (if-let [[_ dispatch-str] (extract-dispatch-from-defmethod content)]
          (str def-name " " dispatch-str)
          def-name)
        def-name))
    def-name))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Similar-match formatting
;; ═══════════════════════════════════════════════════════════════════════════════

(defn title
  [args]
  (util/title "clojure_edit" args))

(defn execute
  "Tool entry point.  Returns {:content str :is-error bool}."
  [{:keys [file_path form_type form_identifier content operation]
    :or   {operation "replace"}}]
  (let [op-kw (case operation
                "replace"       :replace
                "insert_before" :insert-before
                "insert_after"  :insert-after
                :replace)]
    (cond
      (str/blank? file_path)
      {:content "Missing required parameter: file_path" :is-error true}

      (not (util/clojure-file? file_path))
      {:content (util/not-clojure-file-msg "clojure_edit" file_path)
       :is-error true}

      (str/blank? form_type)
      {:content (str "Missing required parameter: form_type "
                     "(e.g. \"defn\", \"defmethod\", \"def\")")
       :is-error true}

      (str/blank? form_identifier)
      {:content "Missing required parameter: form_identifier" :is-error true}

      (str/blank? content)
      {:content "Missing required parameter: content" :is-error true}

      (not (fs/exists? file_path))
      {:content (str "File not found: " file_path) :is-error true}

      (= form_type "comment")
      {:content (str "Form type 'comment' is not supported. "
                     "Use the edit tool for comment blocks.")
       :is-error true}

      :else
      ;; Reject any unparsable content up front, stating exactly what is
      ;; wrong and where — unbalanced delimiters AND other reader errors
      ;; (which used to surface later as a confusing pipeline failure).
      (if-let [problem (util/parse-problem "content" content)]
        {:content (str "Invalid " form_type " content: "
                       (case (:kind problem)
                         :delimiter "unbalanced delimiters"
                         :syntax "invalid syntax")
                       ".\n"
                       (:report problem)
                       "\nThe content must be a complete, balanced " form_type
                       " form, e.g. ("
                       (case form_type
                         "defn" "defn my-fn [args] body"
                         "def" "def my-var value"
                         "deftest" "deftest my-test (is ...)"
                         "ns" "ns my.namespace"
                         "defmethod" "defmethod my-fn :dispatch [args] body"
                         (str form_type " ...")) ").")
         :is-error true}
        (let [shape-error (util/validate-form-shape form_type content)]
          (if shape-error
            {:content (str "Invalid " form_type " content: " shape-error
                           "\nPass the complete " form_type " form explicitly.")
             :is-error true}
            (let [enhanced-name (enhance-defmethod-name form_type form_identifier content)
                  find-edit (fn [zloc]
                              (let [result (edit-top-level-form zloc form_type enhanced-name
                                                                content op-kw)]
                                (if (:error result)
                                  result
                                  (let [found-zloc (:zloc result)
                                        form-col   (second (z/position found-zloc))
                                ;; Re-indent the replacement to the found
                                ;; form's column, then re-edit
                                        content'' (if form-col
                                                    (util/format-form-in-isolation
                                                     content form-col
                                                     (util/project-fmt-opts file_path))
                                                    content)
                                        result2 (edit-top-level-form
                                                 zloc form_type enhanced-name
                                                 content'' op-kw)]
                                    result2))))]
              (util/edit-pipeline file_path find-edit))))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Tool registration
;; ═══════════════════════════════════════════════════════════════════════════════

(defn register!
  "Register clojure_edit as a kmet tool."
  [api]
  ((:register-tool! api)
   {:name            "clojure_edit"
    :label           "Clojure form edit"
    :description
    "Edits a top-level form (`defn`, `def`, `defmethod`, `ns`, `deftest`) in a Clojure file using the specified operation.\n\nPREFER this tool over generic file editing tools for Clojure files (`.clj` `.cljs` `.cljc` `.cljd` `.bb` `.edn` `.lpy`). It rejects other file types.\n\nThis tool MAKES it EASIER to match a definition that exists in the file AS you only have to match the type of definition `form_type` and the complete identifier `form_identifier` of the definition. This prevents the repeated mismatch errors that occur when trying match an entire string of text for replacement.\nThis tool validates the structure of the Clojure code that is being inserted into the file and will provide linting feedback for things such as parenthetical errors.\n\nNOTE: `content` must be a complete, balanced Clojure form. Unbalanced delimiters are REJECTED with an error — pass the complete form exactly as it should appear in the file.\n\nOperations:\n- \"replace\": Replaces the form with new content\n- \"insert_before\": Inserts content before the form\n- \"insert_after\": Inserts content after the form\n\nFor insert_before/insert_after, pass ONLY the new content (never repeat the anchor form). The inserted form lands outside the anchor's own line: a same-line trailing comment stays with the anchor form, and a comment on its own line stays with the next form.\n\nThe form is identified by its type (defn, def, deftest, s/def, ns, defmethod etc.) and complete identifier. Alias-qualified macros (t/deftest, s/def) match by their plain keyword (deftest, def).\n\nExample: Replace a function definition:\n- file_path: \"/path/to/file.clj\"\n- form_identifier: \"example-fn\"\n- form_type: \"defn\"\n- operation: \"replace\"\n- content: \"(defn example-fn [x] (* x 2))\"\n\nExample: Insert a helper function before a function:\n- file_path: \"/path/to/file.clj\"\n- form_identifier: \"example-fn\"\n- form_type: \"defn\"\n- operation: \"insert_before\"\n- content: \"(defn helper-fn [x] (* x 2))\"\n\nExample: Edit a namespace declaration (form_identifier is the namespace name):\n- file_path: \"/path/to/file.clj\"\n- form_identifier: \"my.app.core\"\n- form_type: \"ns\"\n- operation: \"replace\"\n- content: \"(ns my.app.core (:require [clojure.string :as str]))\"\n\nFor `defmethod` forms, include the dispatch value (`area :rectangle`) in `form_identifier`.\nMany `defmethod` definitions have qualified names like `shape/area`, so use the complete identifier.\n\nExample: Replace a specific `defmethod` implementation:\n- form_identifier: \"shape/area :square\"\n- form_type: \"defmethod\"\n- operation: \"replace\"\n- content: \"(defmethod shape/area :square [{:keys [w h]}] (* w h))\"\n\nThe tool returns a diff showing the changes made to the file."
    :render-call renderers/render-edit-call
    :render-result renderers/render-edit-result
    :render-shell :self
    :prompt-snippet "Structure-aware Clojure form editing (replace, insert_before, insert_after)"
    :prompt-guidelines
    ["Use clojure_edit instead of the generic edit tool for Clojure files when targeting a specific form by name."
     "form_type must be the Clojure definition keyword: defn, defmacro, def, defmethod, deftest, ns, s/def, etc."
     "form_identifier is the complete identifier; for defmethod use 'method-name dispatch-value' (e.g. 'area :rectangle')."
     "Many defmethod forms use qualified names (e.g. 'shape/area :square') — always use the complete identifier."
     "When replacing a defmethod, the dispatch value is extracted from content if not in form_identifier."
     "content must be a complete, balanced Clojure form — unbalanced delimiters are rejected with an error."
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
                         :description "New content to use for the operation"}}}
    :execute execute :title title}))
