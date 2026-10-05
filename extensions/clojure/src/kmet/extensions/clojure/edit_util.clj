;; kmet.extensions.clojure.edit-util — Shared utilities for the Clojure extension tools.
;;
;; Common file I/O, diff, formatting, and zipper helpers used by both
;; kmet.extensions.clojure.edit-tool (clojure_edit) and
;; kmet.extensions.clojure.sexp-tool (clojure_edit_replace_sexp).

(ns kmet.extensions.clojure.edit-util
  (:require [babashka.fs :as fs]
            [cljfmt.core :as fmt]
            [clojure.edn :as edn]
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
   a delimiter-only repair can fix delimiters, syntax errors need
   manual edits."
  [what source]
  (when-let [details (parse-error-details source)]
    (if (or (contains? details :edamame/opened-delimiter)
            (contains? details :edamame/expected-delimiter))
      {:kind :delimiter
       :report (delimiter-report what details source)}
      {:kind :syntax
       :report (syntax-report what details)})))

;; ── Delimiter-only repair ─────────────────────────────────────────────────
;; parinferish indent mode is made for interactive editing: when a matching
;; closer is followed by deeper-indented continuation lines it *moves* that
;; closer past them, pulling tokens into the inner form — e.g.
;; ((var status/show-status-indicator!) cs :compaction
;;  (status-indicator/make-compaction-status-indicator)) becomes
;; ((var status/show-status-indicator! cs :compaction ...)), breaking the
;; call. Repair must never restructure valid code, so parinferish is used
;; only as a tokenizer ({:mode nil} never rebalances) and the
;; indentation-based closing decisions live here: explicit matched pairs
;; are authoritative — never moved, closed early, or dropped — while
;; stray/mismatched closers are dropped and openers with no explicit
;; closer are closed at the end of their indentation block (or before
;; trailing whitespace/comments at EOF).

(defn- delim-end
  "The closing delimiter for OPEN, or nil when OPEN is not an opener.
   Openers include the `#{` set literal."
  [open]
  (get {"(" ")" "[" "]" "{" "}" "#{" "}"} open))

(defn- flat-tokens
  "S's tokens as a vector, in order, with parinferish's :line/:column/:indent
   and :whitespace? metadata. `:mode nil` builds the tree from the explicit
   delimiters without rebalancing, so this is exactly S's token sequence."
  [s]
  (letfn [(leaves [x]
            (if (and (vector? x) (= :collection (first x)))
              (mapcat leaves (rest x))
              [x]))]
    (vec (mapcat leaves
                 (parinferish/flatten identity (parinferish/parse s {:mode nil}))))))

(defn- close-delim? [s] (contains? #{")" "]" "}"} s))

(defn- matched-delims
  "Which delimiter tokens form an explicit matched pair: [OPENS CLOSES],
   sets of token indices from a plain stack scan. Matched pairs are
   authoritative — repair never moves, drops, or closes them early."
  [tokens]
  (loop [i 0 st [] opens #{} closes #{}]
    (if (= i (count tokens))
      [opens closes]
      (let [s (str/join (rest (nth tokens i)))]
        (cond
          (delim-end s)
          (recur (inc i) (conj st i) opens closes)

          (close-delim? s)
          (if (and (seq st) (= (delim-end (str/join (rest (nth tokens (peek st))))) s))
            (recur (inc i) (pop st) (conj opens (peek st)) (conj closes i))
            (recur (inc i) st opens closes))

          :else
          (recur (inc i) st opens closes))))))

(defn- balance-delimiters
  "Delimiter-only repair: explicit matched pairs are kept exactly where the
   author wrote them; unmatched closers are dropped; an opener with no
   explicit closer is closed at the end of its indentation block (or before
   trailing whitespace/comments at EOF). No existing delimiter or token is
   ever moved."
  [s]
  (let [tokens (flat-tokens s)
        [matched-opens matched-closes] (matched-delims tokens)]
    (loop [i 0 out [] trivia [] stack []]
      (if (< i (count tokens))
        (let [t (nth tokens i)
              txt (str/join (rest t))
              tag (first t)]
          (cond
            (and (= :delimiter tag) (delim-end txt))
            (recur (inc i)
                   (into (into out trivia) [t])
                   []
                   (conj stack {:open txt
                                :indent (:indent (meta t))
                                :matched? (matched-opens i)}))

            (and (= :delimiter tag) (close-delim? txt))
            (if (matched-closes i)
              (recur (inc i) (into (into out trivia) [t]) [] (pop stack))
              (recur (inc i) out trivia stack))

            (= :newline-and-indent tag)
            (let [indent (:indent (meta t))
                  [stack closers]
                  (loop [stack stack closers []]
                    (if-let [top (peek stack)]
                      (if (and (not (:matched? top)) (< indent (:indent top)))
                        (recur (pop stack) (conj closers (delim-end (:open top))))
                        [stack closers])
                      [stack closers]))]
              (recur (inc i)
                     (into out (mapv (fn [c] [:delimiter c]) closers))
                     (conj trivia t)
                     stack))

            (true? (:whitespace? (meta t)))
            (recur (inc i) out (conj trivia t) stack)

            :else
            (recur (inc i) (into (into out trivia) [t]) [] stack)))
        (let [pending (loop [st stack acc []]
                        (if (empty? st)
                          acc
                          (recur (pop st) (conj acc (delim-end (:open (peek st)))))))]
          (str/join ""
                    (map (fn [t] (str/join (rest t)))
                         (into (into out (mapv (fn [c] [:delimiter c]) pending)) trivia))))))))

(defn repair-delimiters
  "Fix unbalanced delimiters in S. Returns [text fixed?]:
   - no delimiter error → [s false]
   - balanced by inserting/deleting delimiter characters → [repaired true]
   - still unbalanced (e.g. unterminated string) → [s false]"
  [s]
  (if (delimiter-error? s)
    (try
      (let [repaired (balance-delimiters s)]
        (if (delimiter-error? repaired)
          [s false]
          [repaired (not= s repaired)]))
      (catch Exception _ [s false]))
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

(defn- unwrap-meta
  "NODE with leading metadata wrappers removed (stacked ^:a ^:b ...): for
   a `:meta` node, the value it wraps (its last child), unwrapped
   recursively; otherwise NODE itself. Lets the structure checks see
   `^long [x]` as the argument vector it is."
  [node]
  (if (= :meta (n/tag node))
    (if-some [value (last (n/children node))]
      (recur value)
      node)
    node))

(defn- first-form-child
  "The first child node of NODE that survives reading, or nil: whitespace,
   comments, and reader-discarded #_ forms are skipped."
  [node]
  (first (remove #(contains? #{:whitespace :comment :uneval} (n/tag %))
                 (n/children node))))

(defn- arg-vector-node?
  "True for an argument vector node, directly or behind metadata
   (^long [x])."
  [node]
  (= :vector (n/tag (unwrap-meta node))))

(defn- reader-conditional-node?
  "True for a #? / #?@ reader conditional. Its platform branches cannot
   be resolved here, so it counts as an unverifiable arglist or clause
   rather than a shape error."
  [node]
  (and (= :reader-macro (n/tag node))
       (str/starts-with? (n/string node) "#?")))

(defn- arity-clause-node?
  "True for one multi-arity clause: a list whose first form child is an
   argument vector, e.g. ([x] ...) or (^long [x] ...). A reader
   conditional in clause position is accepted unverified."
  [node]
  (let [node (unwrap-meta node)]
    (or (reader-conditional-node? node)
        (and (= :list (n/tag node))
             (some-> (first-form-child node) arg-vector-node?)))))

(defn- defn-args-present?
  "True when the defn-form children after the name include an argument
   vector, either directly ([x] body), as the head of every multi-arity
   clause ((defn foo ([x] ...) ([x y] ...))), or as an unverifiable
   reader conditional (#?(:bb ([x] x) :clj ([x y] y))). Handles the whole
   defn grammar around the arglists: reader-discarded #_ forms, an
   optional docstring, attribute maps (before the arglist or after the
   last arity), and metadata on the argument vectors or arity clauses
   (^long [x])."
  [children]
  (let [;; discarded forms are absent by the time defn sees the form —
        ;; dropping them first keeps the name/docstring/arglist positions
        ;; aligned
        children (remove #(= :uneval (n/tag %)) children)
        fdecl (rest (rest children))
        ;; the optional docstring; a single-line string is a :token node,
        ;; a multi-line string a :multi-line node, so test the value
        fdecl (if (and (seq fdecl)
                       (string? (child-sexpr (first fdecl))))
                (rest fdecl)
                fdecl)
        ;; attr-maps are metadata, not arities, wherever defn allows them
        ;; (front or trailing); a map inside a clause is body code and is
        ;; not a direct child
        fdecl (remove #(map? (child-sexpr %)) fdecl)]
    (cond
      (empty? fdecl) false
      (or (arg-vector-node? (first fdecl))
          (reader-conditional-node? (first fdecl))) true
      (every? arity-clause-node? fdecl) true
      :else false)))

(defn form-head
  "The name of the head symbol of the first top-level form in S, or nil
   when S does not start with a list form or its head is not a symbol —
   the type to validate content against when the expected type comes
   from the content itself rather than from the caller."
  [s]
  (let [children (form-children s)]
    (when (seq children)
      (let [head (child-sexpr (first children))]
        (when (symbol? head)
          (name head))))))

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

(def ^:private fmt-config-files
  "Config file names cljfmt discovers at each directory, in its order. The
   .clj variants are absent: cljfmt's safe mode does not read executable
   configs unless explicitly asked, and nothing here ever asks."
  [".cljfmt.edn" "cljfmt.edn"])

(defn- find-fmt-config
  "The nearest cljfmt config file at DIR or one of its ancestors, or nil."
  [dir]
  (loop [d (fs/absolutize dir)]
    (when d
      (or (some (fn [n]
                  (let [f (fs/file d n)]
                    (when (fs/exists? f) f)))
                fmt-config-files)
          (recur (fs/parent d))))))

(defn- read-fmt-config
  "EDN config with cljfmt's `#re` reader."
  [f]
  (edn/read-string {:readers {'re re-pattern}} (slurp f)))

(defn- convert-legacy-fmt-keys
  "cljfmt's `:legacy/merge-indents?` rename."
  [config]
  (cond-> config
    (:legacy/merge-indents? config)
    (-> (assoc :extra-indents (:indents config))
        (dissoc :legacy/merge-indents? :indents))))

(defn project-fmt-opts
  "cljfmt options for FILE-PATH mirroring cljfmt.tool/fix (what `bb format`
   produces): the project's cljfmt.edn — walked up from the file's directory,
   .clj configs not read (cljfmt's safe mode) — merged over cljfmt's
   defaults, so :extra-indents/:indents/:alias-map etc. apply to custom
   macros (defcomponent, with-let, ...). Falls back to plain defaults when no
   config file exists or the file's directory does not exist."
  [file-path]
  (let [parent (when file-path (fs/parent file-path))
        start  (if (and parent (fs/exists? parent)) parent ".")]
    (-> (merge fmt/default-options
               (some-> (find-fmt-config start) read-fmt-config))
        (convert-legacy-fmt-keys))))

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

(defn- common-prefix-len
  "Number of leading strings equal in A and B."
  [a b]
  (loop [i 0]
    (if (and (< i (count a)) (< i (count b))
             (= (nth a i) (nth b i)))
      (recur (inc i))
      i)))

(defn- common-suffix-len
  "Number of trailing strings equal in A and B, up to LIMIT."
  [a b limit]
  (loop [i 0]
    (if (and (< i limit)
             (= (nth a (- (count a) 1 i))
                (nth b (- (count b) 1 i))))
      (recur (inc i))
      i)))

(defn format-changed-forms
  "Format the top-level forms of an edited source with cljfmt, touching only
   the forms that differ between ORIG-CHILDREN and NEW-CHILDREN (the root
   node children of the source before and after the edit).  Returns the new
   source string.

   The common prefix and suffix are emitted byte-for-byte; every inner node
   in the differing window is reformatted with OPTS, while the whitespace
   between top-level forms stays as the edit produced it.  For a file the
   project formatter leaves clean this is the whole-file cljfmt result, at
   the cost of formatting the changed forms instead of the whole file."
  [orig-children new-children opts]
  (let [new-children (vec new-children)
        orig-strs    (mapv n/string orig-children)
        new-strs     (mapv n/string new-children)
        limit        (min (count orig-strs) (count new-strs))
        prefix       (common-prefix-len orig-strs new-strs)
        suffix       (common-suffix-len orig-strs new-strs (- limit prefix))
        end          (- (count new-strs) suffix)]
    (->> new-strs
         (map-indexed
          (fn [i s]
            (if (and (>= i prefix) (< i end) (n/inner? (nth new-children i)))
              (try (n/string (fmt/reformat-form (nth new-children i) opts))
                   (catch Exception _ s))
              s)))
         (apply str))))

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
   or {:zloc updated-zloc}), format the top-level forms the edit changed
   with the project's cljfmt config, write the file back, and return the
   tool result map.
   FIND-EDIT-FN may return nil instead of {:error ...} — that becomes the
   not-found error."
  [file-path find-edit-fn]
  (try
    (let [original      (slurp-utf8 file-path)
          zloc          (z/of-string original {:track-position? true})
          orig-children (n/children (z/root zloc))
          result        (find-edit-fn zloc)]
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
          (let [new-zloc  (:zloc result)
                formatted (format-changed-forms
                           orig-children
                           (n/children (z/root new-zloc))
                           (project-fmt-opts file-path))]
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
