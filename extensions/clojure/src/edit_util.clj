;; edit-util — Shared utilities for the Clojure extension tools.
;;
;; Common file I/O, diff, formatting, and zipper helpers used by both
;; edit-tool (clojure_edit) and sexp-tool (clojure_edit_replace_sexp).

(ns edit-util
  (:require [babashka.fs :as fs]
            [cljfmt.config :as config]
            [cljfmt.core :as fmt]
            [clojure.string :as str]
            [edamame.core :as e]
            [kmet.libs.edit-diff :as edit-diff]
            [parinferish.core :as parinferish]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; File I/O (UTF-8)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn slurp-utf8 [f]
  (slurp f :encoding "UTF-8"))

(defn clojure-file?
  "True for Clojure-related file paths (.clj .cljs .cljc .cljd .bb .edn .lpy)."
  [file-path]
  (when file-path
    (let [lower (str/lower-case file-path)]
      (or (str/ends-with? lower ".clj")
          (str/ends-with? lower ".cljs")
          (str/ends-with? lower ".cljc")
          (str/ends-with? lower ".cljd")
          (str/ends-with? lower ".bb")
          (str/ends-with? lower ".edn")
          (str/ends-with? lower ".lpy")))))

(defn not-clojure-file-msg
  "Rejection message for TOOL-NAME operating on a non-Clojure FILE-PATH.
   Names the offending extension so the actual reason is visible ('.txt',
   '.bak', or 'no extension' for extensionless paths)."
  [tool-name file-path]
  (let [ext (when file-path (fs/extension (str file-path)))]
    (str "Not a Clojure file: " file-path
         (if (seq ext)
           (str " has extension '." ext "'")
           " has no file extension")
         " — " tool-name
         " only operates on .clj/.cljs/.cljc/.cljd/.bb/.edn/.lpy files.")))

(defn spit-utf8 [f content]
  (spit f content :encoding "UTF-8"))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Lint
;; ═══════════════════════════════════════════════════════════════════════════════

(defn parse-error-details
  "Nil when S parses cleanly; otherwise the edamame ex-data map of the
   first reader error (:type :edamame/error, carrying :row :col — plus
   :edamame/expected-delimiter / :edamame/opened-delimiter /
   :edamame/opened-delimiter-loc and a :message string when the failure is
   a delimiter imbalance). Failures that are not edamame reader errors
   (raw exceptions) return nil."
  [s]
  (try
    (e/parse-string-all s {:all true
                           :features #{:bb :clj :cljs :cljr :default}
                           :read-cond :allow
                           :readers (fn [_tag] (fn [data] data))
                           :auto-resolve name})
    nil
    (catch clojure.lang.ExceptionInfo ex
      (when (= :edamame/error (:type (ex-data ex)))
        (assoc (ex-data ex) :message (ex-message ex))))
    (catch Exception _ nil)))

(defn delimiter-details
  "Nil when S has no DELIMITER error; otherwise the edamame ex-data map
   with :row :col :edamame/expected-delimiter :edamame/opened-delimiter
   and :edamame/opened-delimiter-loc. Only delimiter errors are returned —
   unbalanced parens/brackets/braces and unterminated strings. Other parse
   failures (bad token) return nil; use parse-error-details to see those."
  [s]
  (when-let [data (parse-error-details s)]
    (when (or (contains? data :edamame/opened-delimiter)
              (contains? data :edamame/expected-delimiter))
      data)))

(defn delimiter-error?
  "True when S has a delimiter error (unbalanced parens/brackets/braces).
   Detection via edamame: parse with all reader features enabled; an
   :edamame/error carrying an unclosed opener or an unexpected closer is a
   delimiter error. Non-delimiter parse failures (e.g. bad token) and
   unknown exceptions are NOT delimiter errors."
  [s]
  (boolean (delimiter-details s)))

;; ── Parse problem reports ─────────────────────────────────────────────────
;; Shared rejection/warning text for every consumer of parse validation:
;; the three tools (clojure_edit, clojure_edit_replace_sexp,
;; clojure_paren_repair) and the write/edit hooks. Every report states WHAT
;; failed (file path or argument label), WHERE (line/col from edamame) and
;; WHY (expected vs opened delimiter, or the reader's message).

(defn- char-at-pos
  "The character at 1-based ROW/COL in SOURCE, or nil when out of range."
  [source row col]
  (when (and source row col (pos? row) (pos? col))
    (let [line (get (vec (str/split-lines source)) (dec row))]
      (when (and line (<= col (count line)))
        (subs line (dec col) col)))))

(defn delimiter-report
  "Human-readable report for DETAILS (from delimiter-details) about the
   text SOURCE, labeled WHAT — a file path or an argument label like
   \"content\". SOURCE supplies the offending character: on an unexpected
   closer edamame reports BLANK delimiter strings — truthy empty strings
   that would render as '' — so the report reads the character at the error
   position instead. Includes expected vs opened delimiters and opened-at
   location when available, so the agent can pinpoint the imbalance."
  [what details source]
  (let [row        (:row details)
        col        (:col details)
        expected   (:edamame/expected-delimiter details)
        opened     (:edamame/opened-delimiter details)
        opened-loc (:edamame/opened-delimiter-loc details)
        orow       (:row opened-loc)
        ocol       (:col opened-loc)
        ;; blank strings are truthy in Clojure — test blankness, not nil
        non-blank? #(and (string? %) (not (str/blank? %)))
        expected?  (non-blank? expected)
        opened?    (non-blank? opened)
        found      (when-not (or expected? opened?)
                     (char-at-pos source row col))]
    (str "Unbalanced delimiters in " what
         (when (and row col)
           (str " at line " row ", col " col))
         (cond
           (and expected? opened?) (str ": expected '" expected "' to close '" opened "'")
           expected?               (str ": unexpected '" expected "'")
           opened?                 (str ": unclosed '" opened "'")
           found                   (str ": unexpected '" found "'")
           :else                   ": unexpected closing delimiter")
         (when (and orow ocol)
           (str " (opened at line " orow ", col " ocol ")"))
         ".")))

(defn- syntax-report
  "Human-readable report for a non-delimiter reader error DETAILS,
   labeled WHAT: \"Syntax error in <what> at line r, col c:
   <reader message>.\""
  [what details]
  (let [loc (when (and (:row details) (:col details))
              (str " at line " (:row details) ", col " (:col details)))
        msg (:message details)]
    (if (str/blank? msg)
      (str "Syntax error in " what loc ".")
      ;; the reader's message carries its own punctuation
      (str "Syntax error in " what loc ": " msg))))

(defn parse-problem
  "The first parse problem in SOURCE, labeled WHAT (a file path or an
   argument label like \"content\"): {:kind :delimiter|:syntax :report str},
   or nil when SOURCE parses cleanly. :kind separates repair strategies —
   parinferish can fix delimiters, syntax errors need manual edits."
  [what source]
  (when-let [details (parse-error-details source)]
    (if (or (contains? details :edamame/opened-delimiter)
            (contains? details :edamame/expected-delimiter))
      {:kind :delimiter
       :report (delimiter-report what details source)}
      {:kind :syntax
       :report (syntax-report what details)})))

(defn- parinferish-repair
  "Repair delimiters in S with parinferish indent mode. Returns the
   repaired string, or nil when repair failed (or produced code that still
   has delimiter errors)."
  [s]
  (try
    (let [repaired (parinferish/flatten (parinferish/parse s {:mode :indent}))]
      (when (and (some? repaired) (not (delimiter-error? repaired)))
        repaired))
    (catch Exception _ nil)))

(defn repair-delimiters
  "Fix unbalanced delimiters in S. Returns [text fixed?]:
   - no delimiter error → [s false]
   - error repaired → [repaired true]
   - error but repair failed → [s false] (the error surfaces downstream)"
  [s]
  (if (delimiter-error? s)
    (if-let [repaired (parinferish-repair s)]
      [repaired true]
      [s false])
    [s false]))

(defn form-children
  "The immediate child nodes of the FIRST top-level form in S, as a seq of
   rewrite-clj nodes, or nil when S does not start with a list form (a
   bare token, vector, or multiple forms)."
  [s]
  (try
    (let [zl (z/of-string s)
          root (z/node zl)]
      (when (= :list (n/tag root))
        (let [dl (z/down zl)]
          (loop [c dl acc []]
            (if c
              (recur (z/right c) (conj acc (z/node c)))
              acc)))))
    (catch Exception _ nil)))

(defn- child-sexpr [node]
  (try (z/sexpr (z/of-node node)) (catch Exception _ nil)))

(defn- defn-args-present?
  "True when the defn-form children after the name include an argument
   vector, either directly ([x] body), as the head of every multi-arity
   clause ((defn foo ([x] ...) ([x y] ...))), or after an optional
   docstring."
  [children]
  (let [after-name (rest (rest children))
        after-doc (if (and (seq after-name)
                           (= :token (n/tag (first after-name)))
                           (string? (child-sexpr (first after-name))))
                    (rest after-name)
                    after-name)]
    (if (some #(= :vector (n/tag %)) after-doc)
      true
      (and (seq after-doc)
           (every? #(= :list (n/tag %)) after-doc)
           (every? (fn [node]
                     (let [first-child (some-> (z/of-node node) z/down z/node)]
                       (= :vector (n/tag first-child))))
                   after-doc)))))

(defn validate-form-shape
  "Validate that S is structurally a well-formed instance of FORM-TYPE
   (defn, def, deftest, ns, ...). Returns nil when valid, or an error
   message string when the content parses but is missing required parts —
   e.g. `(defn- filter-kind)` is a defn without an arg vector, which is
   what parinferish produces from the fragment `(defn- filter-kind`.
   Detects the agent mistake of passing an incomplete form."
  [form-type s]
  (let [children (form-children s)]
    (when (seq children)
      (let [tag (first children)
            tag-sexpr (child-sexpr tag)
            tag-str (when (symbol? tag-sexpr) (name tag-sexpr))]
        (cond
          ;; the form tag must match the requested type (allow defn-/defmacro- etc.)
          (not (and tag-str
                    (or (= tag-str form-type)
                        (str/starts-with? tag-str (str form-type "-")))))
          (str "Content does not start with a '" form-type "' form (found '" tag-str "'). "
               "Pass a complete " form-type " form, e.g. ("
               (case form-type
                 "defn" "defn my-fn [args] body"
                 "def" "def my-var value"
                 "deftest" "deftest my-test (is ...)"
                 "ns" "ns my.namespace"
                 "defmethod" "defmethod my-fn :dispatch [args] body"
                 (str form-type " ...")) ").")

          ;; ns must have a namespace name
          (and (= form-type "ns")
               (< (count children) 2))
          (str "Incomplete 'ns' form: missing the namespace name. "
               "An ns needs (ns name ...), e.g. (ns my.namespace).")

          ;; defn/defn-/defmacro must have an arg vector (direct or multi-arity)
          (and (or (= form-type "defn") (= form-type "defn-")
                   (= form-type "defmacro") (= form-type "defmacro-"))
               (not (defn-args-present? children)))
          (str "Incomplete '" form-type "' form: missing the argument vector. "
               "A " form-type " needs (name [args] body...), e.g. ("
               form-type " my-fn [x] (* x 2)).")

          ;; def must have a value (or at least a name + something)
          (and (= form-type "def")
               (< (count children) 3))
          (str "Incomplete 'def' form: expected (def name value), found "
               (count (rest children)) " element(s) after 'def'.")

          :else nil)))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Formatting (cljfmt, honoring the project's cljfmt.edn)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn project-fmt-opts
  "cljfmt options for FILE-PATH mirroring cljfmt.tool/fix (what `bb format`
   produces): the project's cljfmt.edn — walked up from the file's directory —
   merged over cljfmt's defaults, so :extra-indents/:indents/:alias-map etc.
   apply to custom macros (defcomponent, with-let, ...). Falls back to plain
   defaults when no config file exists or the file's directory does not exist
   (load-config cannot search a nonexistent directory)."
  [file-path]
  (let [parent (when file-path (fs/parent file-path))]
    (if (and parent (fs/exists? parent))
      (config/load-config (str parent))
      (config/load-config "."))))

(defn format-source-string
  "Format a complete Clojure source string.  Returns formatted string,
   or the original on parse error.  With no opts, resolves the project
   config from the current directory."
  ([s] (format-source-string s (project-fmt-opts ".")))
  ([s opts]
   (try (fmt/reformat-string s opts)
        (catch Exception _ s))))

(defn re-indent-to-column
  "Re-indent all lines after the first to TARGET-COL (1-based)."
  [form-str target-col]
  (if (<= target-col 1)
    form-str
    (let [lines      (str/split form-str #"\n" -1)
          indent-str (apply str (repeat (dec target-col) " "))]
      (str/join "\n"
                (cons (first lines)
                      (map (fn [l] (if (str/blank? l) l (str indent-str l)))
                           (rest lines)))))))

(defn format-form-in-isolation
  "Format FORM-STR with cljfmt, then re-indent to TARGET-COL."
  ([form-str target-col]
   (format-form-in-isolation form-str target-col (project-fmt-opts ".")))
  ([form-str target-col opts]
   (try
     (let [formatted (fmt/reformat-string form-str opts)]
       (re-indent-to-column formatted target-col))
     (catch Exception _ form-str))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: navigation
;; ═══════════════════════════════════════════════════════════════════════════════

(defn walk-back-to-non-comment
  "Walk backward from ZLOC past whitespace and comments."
  [zloc]
  (z/find-next zloc z/prev*
               (fn [z] (not (#{:whitespace :comment} (n/tag (z/node z)))))))

(defn walk-forward-past-trailing-comments
  "Walk forward from ZLOC past whitespace and comments sitting on the same
   line — the form's own trailing trivia (e.g. `(defn a ...) ;; note`).
   Stops before any node that starts a new line: a comment there leads the
   NEXT form and must stay with it. Returns the last node of the same-line
   run, which is the right anchor for insert-after."
  [zloc]
  (loop [loc zloc]
    (let [nxt (z/right* loc)]
      (if-not nxt
        loc
        (let [tag (n/tag (z/node nxt))]
          (cond
            (= tag :comment) (recur nxt)
            (= tag :whitespace)
            (if (str/includes? (n/string (z/node nxt)) "\n")
              loc
              (recur nxt))
            :else loc))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: form replacement
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- remove-whitespace-and-comments
  "Remove consecutive whitespace and comment nodes starting at ZLOC."
  [zloc]
  (loop [loc zloc]
    (if (n/whitespace-or-comment? (z/node loc))
      (recur (z/next* (z/remove* loc)))
      loc)))

(defn replace-form
  "Replace FORM-ZLOC with CONTENT-STR.  When CONTENT-STR is a comment
   (starts with ;), walks back and removes adjacent comments first."
  [form-zloc content-str]
  (if (-> content-str str/trim (str/starts-with? ";"))
    (-> (walk-back-to-non-comment form-zloc)
        (z/next*)
        (remove-whitespace-and-comments)
        (z/replace (p/parse-string-all content-str)))
    (z/replace form-zloc (p/parse-string-all content-str))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: insert helpers (shared by clojure_edit and clojure_edit_replace_sexp)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn insert-before-form
  "Insert CONTENT-STR as a new form immediately before ZLOC, separated by a
   blank line, returning the inserted form's zipper location."
  [zloc content-str]
  (-> zloc
      walk-back-to-non-comment z/next*
      (z/insert-left* (p/parse-string-all "\n\n"))
      z/left
      (z/insert-left* (p/parse-string-all content-str))
      z/left))

(defn insert-after-form
  "Insert CONTENT-STR as a new form immediately after ZLOC (past the form's
   own same-line trailing trivia), separated by a blank line, returning the
   inserted form's zipper location. A comment anchor already ends its line,
   so it takes a single \"\\n\" before (blank line) and after (line
   terminator) the content."
  [zloc content-str]
  (let [anchor (walk-forward-past-trailing-comments zloc)
        comment-anchor? (= :comment (n/tag (z/node anchor)))
        sep (p/parse-string-all (if comment-anchor? "\n" "\n\n"))
        at-content (-> anchor
                       (z/insert-right* sep)
                       z/right
                       (z/insert-right* (p/parse-string-all content-str))
                       z/right)]
    (if comment-anchor?
      (z/insert-right* at-content (p/parse-string-all "\n"))
      at-content)))

(defn edit-pipeline
  "Run the shared edit pipeline: load FILE-PATH, call FIND-EDIT-FN with the
   parsed zipper (which must return either {:error msg :similar-matches ...}
   or {:zloc updated-zloc}), format the result with the project's cljfmt
   config, write it back, and return the tool result map.
   FIND-EDIT-FN may return nil instead of {:error ...} — that becomes the
   not-found error."
  [file-path find-edit-fn]
  (try
    (let [original (slurp-utf8 file-path)
          zloc     (z/of-string original {:track-position? true})
          result   (find-edit-fn zloc)]
      (if (and result (:error result))
        (let [similar (:similar-matches result)
              similar-note (when (seq similar)
                             (str "\n\nSimilar forms found (check the form name/type and dispatch value):\n"
                                  (str/join "\n" (map (fn [m]
                                                        (str "  - (" (:tag m) " " (:qualified-name m) " ...)"))
                                                      similar))))]
          {:content  (str (:message result) similar-note)
           :is-error true})
        (if-not result
          {:content  (str "Could not find the target in " file-path
                          "\nThe match is content-based — whitespace/newlines are ignored, but the structure (parens, brackets, braces, keywords, symbols) must match.")
           :is-error true}
          (let [new-source (z/root-string (:zloc result))
                fmt-opts   (project-fmt-opts file-path)
                formatted  (format-source-string new-source fmt-opts)]
            (spit-utf8 file-path formatted)
            (let [diff-str (edit-diff/generate-display-diff original formatted)]
              {:content "Edit applied."
               :details (when diff-str {:diff diff-str})})))))
    (catch Exception e
      {:content (str "Error editing " file-path ": " (ex-message e))
       :is-error true})))

(defn- title-home-path
  [p]
  (let [home (System/getProperty "user.home" "")]
    (if (and (seq home)
             (or (= p home) (str/starts-with? p (str home "/"))))
      (str "~" (subs p (count home)))
      p)))

(defn- title-nonblank
  [v]
  (when (and (string? v) (seq v)) v))

(defn title-arg
  [args k]
  (when (map? args)
    (or (get args k) (get args (name k)))))

(defn title
  [tool-name args]
  (when-let [p (title-nonblank (title-arg args :file_path))]
    (str tool-name
         " " (or (title-nonblank (title-arg args :operation)) "replace")
         (when-let [ft (title-nonblank (title-arg args :form_type))] (str " " ft))
         (when-let [fi (title-nonblank (title-arg args :form_identifier))] (str " " fi))
         " in " (title-home-path p))))

(defn title-sexp
  [tool-name args]
  (when-let [p (title-nonblank (title-arg args :file_path))]
    (let [m (title-arg args :match_form)
          pv (when (string? m)
               (first (remove str/blank? (map str/trim (str/split-lines m)))))]
      (str tool-name
           " " (or (title-nonblank (title-arg args :operation)) "replace")
           (when (title-nonblank pv)
             (str " " (if (> (count pv) 60) (str (subs pv 0 60) "…") pv)))
           " in " (title-home-path p)))))
