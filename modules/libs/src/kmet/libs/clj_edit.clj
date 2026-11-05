;; kmet.libs.clj-edit — Shared Clojure source editing engine.
;;
;; File I/O, parsing, delimiter repair, cljfmt formatting, zipper form
;; replacement, duplicate-definition checks and the write pipeline used by the
;; clojure extension tools, the eval core verbs and the eval library's
;; `eval/edit-form`.

(ns kmet.libs.clj-edit
  (:require [babashka.fs :as fs]
            [cljfmt.core :as fmt]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [edamame.core :as e]
            [kmet.libs.edit-diff :as edit-diff]
            [kmet.libs.path :as path]
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

(def ^:private definition-heads
  "Top-level heads that define a named thing a file should define only once.
   Matched unqualified, so an alias-qualified macro (t/deftest) counts;
   `declare` is absent — a repeated forward declaration is harmless."
  #{"def" "defn" "defn-" "defmacro" "defmacro-" "defmulti" "defmethod"
    "defonce" "deftest" "deftest-" "definline" "defschema" "defprotocol"
    "defstruct" "defrecord" "deftype" "definterface"})

(defn- top-level-definition-key
  "The name a top-level definition NODE introduces (with the dispatch for
   defmethod), or nil when NODE is not one. The head is matched unqualified,
   so an alias-qualified macro matches its plain spelling."
  [node]
  (let [node     (unwrap-meta node)
        children (when (= :list (n/tag node))
                   (remove #(contains? #{:whitespace :comment :uneval}
                                       (n/tag %))
                           (n/children node)))
        head     (some-> children first child-sexpr)
        def-name (some-> children second child-sexpr)]
    (when (and (symbol? head) (symbol? def-name)
               (definition-heads (name head)))
      (let [dispatch (when (= "defmethod" (name head))
                       (some-> (nth children 2 nil) n/string str/trim))]
        (str def-name (when dispatch (str " " dispatch)))))))

(defn- top-level-definition-lines
  "Map of definition name to the ascending line numbers of its top-level forms
   in SOURCE. A definition inside a reader conditional or (comment …) is not
   a top-level list form, so it is not counted."
  [source]
  (let [zloc (z/of-string source {:track-position? true})
        ;; z/of-string starts at the first form; step out to the forms root
        ;; so the traversal sees every top-level sibling.
        root (if (= :forms (n/tag (z/node zloc))) zloc (z/up zloc))]
    (loop [loc (z/down root) acc {}]
      (if-not loc
        acc
        (let [k (top-level-definition-key (z/node loc))]
          (recur (z/right loc)
                 (if k
                   (update acc k (fnil conj []) (first (z/position loc)))
                   acc)))))))

(defn- introduced-duplicates
  "The [name lines] duplicates an edit would introduce: names that appear
   more than once in AFTER and more often than in BEFORE. A pre-existing
   duplicate is not the edit's fault and does not block it."
  [before after]
  (for [[def-name lines] after
        :when (and (> (count lines) 1)
                   (> (count lines) (count (get before def-name))))]
    [def-name lines]))

(defn- duplicate-error
  "The tool result refusing an edit that would leave DUPS — the definition
   names and their proposed line numbers."
  [file-path dups]
  {:content
   (str "Refusing the edit: it would leave duplicate top-level definitions in "
        file-path " (line numbers are from the edited result):\n"
        (str/join "\n"
                  (for [[def-name lines] dups]
                    (str "  - " def-name
                         " at lines " (str/join ", " lines))))
        "\n\nA namespace defines each form once. Delete or rename the other "
        "definition, then retry. No change was written.")
   :is-error true})

(defn edit-source
  "Apply FIND-EDIT-FN to SOURCE — the pure half of the edit pipeline. The
   zipper edit must return nil (not found), `{:error msg :similar-matches …}`,
   or `{:zloc updated-zloc}`; the changed top-level forms are formatted with
   the project's cljfmt config and an edit that would introduce a duplicate
   definition is refused. Returns `{:content <formatted source> :diff <str>}`
   or an error result; the caller decides whether to write."
  [source file-path find-edit-fn]
  (try
    (let [zloc          (z/of-string source {:track-position? true})
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
                           (project-fmt-opts file-path))
                dups      (introduced-duplicates
                           (top-level-definition-lines source)
                           (top-level-definition-lines formatted))]
            (if (seq dups)
              (duplicate-error file-path dups)
              {:content formatted
               :diff (edit-diff/generate-display-diff source formatted)})))))
    (catch Exception e
      {:content (str "Error editing " file-path ": " (ex-message e))
       :is-error true})))

(defn edit-definition-source
  "Replace RECORD's top-level form in SOURCE with NEW-SOURCE, which must be
   exactly one complete form. Formats the changed form, refuses a duplicate
   definition, and returns `{:content formatted :diff str}` or an error
   result. This is the pure step behind eval/edit-form."
  [source file-path record new-source]
  (let [parsed (try (p/parse-string-all new-source)
                    (catch Throwable t
                      (throw (ex-info (str "cannot parse replacement: "
                                           (ex-message t))
                                      {:type :eval/edit-form}))))
        forms (vec (remove n/whitespace-or-comment? (n/children parsed)))]
    (when-not (= 1 (count forms))
      (throw (ex-info (str "edit-form needs exactly one top-level form, got "
                           (count forms))
                      {:type :eval/edit-form})))
    (let [new-node (first forms)
          find-edit (fn [zloc]
                      (let [root (loop [loc zloc]
                                   (if (= :forms (n/tag (z/node loc)))
                                     loc
                                     (recur (z/up loc))))
                            target (loop [loc (z/down root)]
                                     (when loc
                                       (if (= (:source record) (n/string (z/node loc)))
                                         loc
                                         (recur (z/right loc)))))]
                        (when target
                          {:zloc (z/replace target new-node)})))]
      (edit-source source file-path find-edit))))

(defn- write-edited!
  "Write RESULT's formatted content to FILE-PATH and return the tool result,
   or pass an error result through unchanged."
  [file-path result]
  (if (:is-error result)
    result
    (do
      (spit-utf8 file-path (:content result))
      {:content "Edit applied."
       :details (when-let [diff-str (:diff result)] {:diff diff-str})})))

(defn edit-pipeline
  "Read FILE-PATH, apply FIND-EDIT-FN through `edit-source`, and write the
   formatted result. Returns the tool result map; `:content` is \"Edit
   applied.\" on success and the error report otherwise."
  [file-path find-edit-fn]
  (try
    (let [original (slurp-utf8 file-path)]
      (write-edited! file-path (edit-source original file-path find-edit-fn)))
    (catch Exception e
      {:content (str "Error editing " file-path ": " (ex-message e))
       :is-error true})))

;; ─── Top-level definition edit (clojure_edit and clj/edit-form) ─────────────

(def ^:private op-kinds
  "The operations the tool publishes, and the keyword the edit pipeline
   switches on. An unknown operation falls back to `:replace`, which is how the
   tool has always read it."
  {"replace" :replace
   "insert_before" :insert-before
   "insert_after" :insert-after})

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

(defn- edit-top-level-form [zloc tag dname content-str edit-type]
  (let [{:keys [zloc similar-matches]} (find-top-level-form zloc tag dname)]
    (if-not zloc
      {:error           true
       :message         (str "Could not find form '" dname "' of type '" tag "'")
       :similar-matches similar-matches}
      (let [updated (case edit-type
                      ;; an empty content with :replace deletes the form
                      ;; (edit tool convention); z/remove also trims the
                      ;; surrounding whitespace so no blank line is left
                      :replace       (if (str/blank? content-str)
                                       (z/remove zloc)
                                       (replace-form zloc content-str))
                      :insert-before (insert-before-form zloc content-str)
                      :insert-after  (insert-after-form zloc content-str))]
        {:zloc updated :similar-matches similar-matches}))))

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

(defn- guard-args
  "The usage error ARGS earns, or nil. Order is the contract: a required
   parameter before the operation's content rule, the content rule before the
   target file, and the file's existence before its form type — so a call
   missing two things names the first one in this list. CHECK-FILE? adds the
   existence check; the pure source operation leaves it to its caller."
  [{:keys [file_path form_type form_identifier operation]}
   op-kw missing-content? blank-insert? check-file?]
  (cond
    (str/blank? file_path)
    {:content "Missing required parameter: file_path" :is-error true}

    (not (clojure-file? file_path))
    {:content (not-clojure-file-msg "clojure_edit" file_path)
     :is-error true}

    (str/blank? form_type)
    {:content (str "Missing required parameter: form_type "
                   "(e.g. \"defn\", \"defmethod\", \"def\")")
     :is-error true}

    (str/blank? form_identifier)
    {:content "Missing required parameter: form_identifier" :is-error true}

    ;; Deletion follows the edit tool's convention: content stays required, and
    ;; a blank value with "replace" removes the form; a missing content is
    ;; still an error.
    missing-content?
    {:content (str "Missing required parameter: content"
                   (if (= op-kw :replace)
                     " — pass \"\" (empty string) to delete the form."
                     " — insert_before/insert_after need the content to insert."))
     :is-error true}

    blank-insert?
    {:content (str "content cannot be empty for operation \"" operation
                   "\" — insert_before/insert_after need the content to insert. "
                   "An empty content deletes only with operation \"replace\".")
     :is-error true}

    (and check-file? (not (fs/exists? file_path)))
    {:content (str "File not found: " file_path) :is-error true}

    (= form_type "comment")
    {:content (str "Form type 'comment' is not supported. "
                   "Use the edit tool for comment blocks.")
     :is-error true}))

(def ^:private form-examples
  "The balanced-form example a rejected content's message ends with, by form
   type; anything else gets `(FORM_TYPE …)`."
  {"defn" "defn my-fn [args] body"
   "def" "def my-var value"
   "deftest" "deftest my-test (is ...)"
   "ns" "ns my.namespace"
   "defmethod" "defmethod my-fn :dispatch [args] body"})

(defn- content-error
  "The error CONTENT earns for FORM-TYPE, or nil: unparsable content is rejected
   up front, stating what is wrong and where — unbalanced delimiters AND other
   reader errors, which used to surface later as a confusing pipeline failure —
   then the form's own shape. On \"replace\" the content must produce the
   anchor's type; an inserted form is independent of the anchor, so it is
   checked against its own head (a def may be inserted beside a defn anchor)."
  [form_type content op-kw]
  (if-let [problem (parse-problem "content" content)]
    {:content (str "Invalid " form_type " content: "
                   (case (:kind problem)
                     :delimiter "unbalanced delimiters"
                     :syntax "invalid syntax")
                   ".\n"
                   (:report problem)
                   "\nThe content must be a complete, balanced " form_type
                   " form, e.g. ("
                   (get form-examples form_type (str form_type " ...")) ").")
     :is-error true}
    (let [content-type (if (= op-kw :replace)
                         form_type
                         (or (form-head content) form_type))]
      (when-let [shape-error (validate-form-shape content-type content)]
        {:content (str "Invalid " content-type " content: " shape-error
                       "\nPass the complete " content-type " form explicitly.")
         :is-error true}))))

(defn- find-edit
  "The zipper edit for one pass: a blank content under \"replace\" is a deletion,
   so nothing is parsed or re-indented; otherwise the replacement is re-indented
   to the found form's column and the edit re-run, because one pass would leave
   the content at column 0."
  [zloc {:keys [file_path form_type content op-kw blank-content? enhanced-name]}]
  (if blank-content?
    (edit-top-level-form zloc form_type enhanced-name content op-kw)
    (let [result (edit-top-level-form zloc form_type enhanced-name content op-kw)]
      (if (:error result)
        result
        (let [found-zloc (:zloc result)
              form-col (second (z/position found-zloc))
              content' (if form-col
                         (format-form-in-isolation content form-col
                                                   (project-fmt-opts file_path))
                         content)]
          (edit-top-level-form zloc form_type enhanced-name content' op-kw))))))

(defn- resolved-args
  "ARGS with `:file_path` resolved against its `:cwd` (the tool's run cwd or
   the eval source's call cwd). Absolute paths pass through."
  [args]
  (if-let [path (or (:file_path args) (get args "file_path"))]
    (let [path (str path)]
      (assoc args :file_path
             (if (fs/absolute? path)
               path
               (str (fs/normalize
                     (fs/path (or (:cwd args)
                                  (get args "cwd")
                                  (System/getProperty "user.dir")
                                  ".")
                              path))))))
    args))

(defn- edit-args
  "The operation keyword and content flags shared by the pure and writing
   entry points."
  [args]
  (let [op-kw (get op-kinds (:operation args) :replace)
        missing-content? (nil? (:content args))
        blank-content? (and (not missing-content?) (str/blank? (:content args)))]
    {:op-kw op-kw
     :missing-content? missing-content?
     :blank-content? blank-content?
     :blank-insert? (and blank-content? (not= op-kw :replace))}))

(defn edit-form-source
  "Apply the clojure_edit operation described by ARGS to SOURCE, without
   reading or writing a file. `:file_path` only supplies the project's fmt
   config; the caller owns the file."
  [source {:keys [form_type content] :as args}]
  (let [{:keys [op-kw missing-content? blank-content? blank-insert?]} (edit-args args)]
    (or (guard-args args op-kw missing-content? blank-insert? false)
        (content-error form_type content op-kw)
        (edit-source
         source
         (:file_path args)
         (fn [zloc]
           (find-edit zloc {:file_path (:file_path args)
                            :form_type form_type
                            :content content
                            :op-kw op-kw
                            :blank-content? blank-content?
                            :enhanced-name (enhance-defmethod-name
                                            form_type (:form_identifier args) content)}))))))

(defn edit-form
  "The clojure_edit operation as a plain function: ARGS is its map, including
   `:file_path`; returns the tool result map and writes the file. A relative
   `:file_path` resolves against `:cwd` when supplied."
  [args]
  (let [args (resolved-args args)
        file_path (:file_path args)
        {:keys [op-kw missing-content? blank-insert?]} (edit-args args)]
    (or (guard-args args op-kw missing-content? blank-insert? true)
        (try
          (write-edited! file_path
                         (edit-form-source (slurp-utf8 file_path) args))
          (catch Exception e
            {:content (str "Error editing " file_path ": " (ex-message e))
             :is-error true})))))

;; ─── S-expression edit (clojure_edit_replace_sexp and clj/replace-sexp) ─────

(def ^:dynamic *match-clean* false)

(defn- semantic-nodes? [node]
  (if *match-clean*
    (not (n/whitespace-or-comment? node))
    (not (n/whitespace? node))))

(defn- normalize-whitespace-node [node]
  (if (= :forms (n/tag node))
    (n/forms-node (map normalize-whitespace-node (n/children node)))
    (if (n/inner? node)
      (let [children (n/children node)
            filtered (->> children
                          (remove n/whitespace?)
                          (map normalize-whitespace-node)
                          (interpose (n/spaces 1))
                          vec)]
        (n/replace-children node filtered))
      node)))

(defn- normalize-and-clean-node [node]
  (cond
    (not (semantic-nodes? node)) nil
    (= :forms (n/tag node))
    (n/forms-node (->> (n/children node)
                       (map normalize-and-clean-node)
                       (filter some?)))
    (n/inner? node)
    (let [children (n/children node)
          cleaned  (->> children
                        (map normalize-and-clean-node)
                        (filter some?)
                        (interpose (n/spaces 1))
                        vec)]
      (n/replace-children node cleaned))
    :else node))

(defn- node->match-expr [node]
  (when-let [n (if *match-clean*
                 (normalize-and-clean-node node)
                 (normalize-whitespace-node node))]
    (n/string n)))

(defn- zchild-match-exprs [zloc]
  (let [nodes (if (= :forms (z/tag zloc))
                (n/children (z/node zloc))
                (->> (iterate z/right* zloc)
                     (take-while some?)
                     (map z/node)))]
    (->> nodes
         (filter (bound-fn* semantic-nodes?))
         (keep (bound-fn* node->match-expr)))))

(defn- str-forms->sexps [str-forms]
  (zchild-match-exprs (z/of-node (p/parse-string-all str-forms))))

(defn- match-multi-sexp [match-sexprs zloc]
  (let [len         (count match-sexprs)
        zloc-sexprs (zchild-match-exprs zloc)
        matched     (map = match-sexprs zloc-sexprs)]
    (and (every? identity matched)
         (= (count matched) len))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Zipper: multi-sexp operations
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- iterate-to-n [f x n]
  (->> (iterate f x) (take n) last))

(defn- zright-n [zloc n]
  (iterate-to-n z/right zloc n))

(defn- remove-match-expr
  "Remove the matched expression at ZLOC with REMOVE-FN — z/remove* while
   truncating for a replacement; z/remove for deletion, which also trims
   the surrounding whitespace so a deleted expression takes its line with
   it instead of leaving a blank one. Whitespace between the scan start
   and the match is removed raw."
  [zloc match-exp & [remove-fn]]
  (let [remove-fn (or remove-fn z/remove*)]
    (loop [zloc' zloc]
      (when (and zloc' (not (z/end? zloc')))
        (let [node (z/node zloc')]
          (when (and (semantic-nodes? node)
                     (not= match-exp (node->match-expr node)))
            (throw (ex-info "Bad match state" {:node      (n/string node)
                                               :match-exp match-exp})))
          (if (and (semantic-nodes? node)
                   (= match-exp (node->match-expr node)))
            (remove-fn zloc')
            (recur (-> zloc' z/remove* z/next*))))))))

(defn- remove-match-exprs
  "Remove each expression in MATCH-EXPRS starting at ZLOC, optionally with
   REMOVE-FN (default z/remove*; deletion passes z/remove so the removed
   expressions take their lines with them)."
  [zloc match-exprs & [remove-fn]]
  (let [end (last match-exprs)]
    (reduce
     (fn [zloc' match-expr]
       (let [zl (remove-match-expr zloc' match-expr remove-fn)]
         (cond
           (= match-expr end) zl
           (not (z/end? zl)) (z/next* zl))))
     zloc
     match-exprs)))

(defn- truncate-matched-expression [zloc [start & match-exprs]]
  (when-let [after-truncated (remove-match-exprs (z/right* zloc) match-exprs)]
    (z/find after-truncated z/prev*
            (fn [zloc]
              (let [node (z/node zloc)]
                (and (semantic-nodes? node)
                     (= start (node->match-expr node))))))))

(defn- replace-multi-helper [zloc match-exprs content-str]
  (if (= 1 (count match-exprs))
    (replace-form zloc content-str)
    (let [truncated-zloc (truncate-matched-expression zloc match-exprs)]
      (replace-form truncated-zloc content-str))))

(defn- replace-multi [zloc match-sexprs content-str]
  (if (or (nil? content-str) (zero? (count content-str)))
    ;; deletion: z/remove also trims the surrounding whitespace, so the
    ;; removed expression(s) take their line(s) with them
    (let [after-loc (remove-match-exprs zloc match-sexprs z/remove)]
      {:edit-span-loc after-loc :after-loc after-loc})
    (let [edit-span-loc (replace-multi-helper zloc match-sexprs content-str)]
      {:edit-span-loc edit-span-loc
       :after-loc      (or (z/right edit-span-loc)
                           (z/next edit-span-loc))})))

(defn- insert-before-multi [zloc _match-sexprs replacement-node]
  ;; Same shape as clj-edit/insert-before-form: a "\n\n" separator then
  ;; the content, both inserted raw (z/insert-left would auto-space and
  ;; glue the forms onto the anchor's line).
  (let [edit-loc (-> zloc
                     walk-back-to-non-comment z/next*
                     (z/insert-left* (p/parse-string-all "\n\n"))
                     z/left
                     (z/insert-left* replacement-node)
                     z/left)]
    {:edit-span-loc edit-loc
     :after-loc     (-> edit-loc z/splice
                        (zright-n (count (n/child-sexprs replacement-node))))}))

(defn- insert-after-multi [zloc match-sexprs replacement-node]
  (let [anchor (-> (take (count match-sexprs) (iterate z/right zloc))
                   last
                   ;; past the match's own same-line trailing trivia, so a
                   ;; trailing comment stays with the matched form
                   walk-forward-past-trailing-comments)
        comment-anchor? (= :comment (n/tag (z/node anchor)))
        sep-node (p/parse-string-all (if comment-anchor? "\n" "\n\n"))
        edit-loc (-> anchor
                     (z/insert-right* sep-node)
                     z/right
                     (z/insert-right* replacement-node)
                     z/right)
        ;; a comment anchor already ends its line — terminate the inserted
        ;; content's line too, or it glues to the next form
        edit-loc (if comment-anchor?
                   (z/insert-right* edit-loc (p/parse-string-all "\n"))
                   edit-loc)]
    {:edit-span-loc edit-loc
     :after-loc     (-> edit-loc z/splice
                        (zright-n (count (n/child-sexprs replacement-node))))}))

(defn- find-multi-sexp [zloc match-sexprs]
  (->> (iterate z/next zloc)
       (take-while (complement z/end?))
       (filter z/sexpr-able?)
       (filter #(match-multi-sexp match-sexprs %))
       first))

(defn- find-and-edit-one-multi-sexp
  ([zloc operation match-form new-form]
   (find-and-edit-one-multi-sexp zloc operation match-form new-form nil))
  ([zloc operation match-form new-form _reindent-fn]
   (when-not (and (str/blank? new-form) (#{:insert-before :insert-after} operation))
     (let [match-sexprs (str-forms->sexps match-form)]
       (when-let [found-loc (find-multi-sexp zloc match-sexprs)]
         (let [new-node (when-not (str/blank? new-form)
                          (p/parse-string-all new-form))]
           (condp = operation
             :insert-before (insert-before-multi found-loc match-sexprs new-node)
             :insert-after  (insert-after-multi found-loc match-sexprs new-node)
             (replace-multi found-loc match-sexprs new-form))))))))

(defn- find-and-edit-all-multi-sexp [zloc operation match-form new-form]
  (when-not (and (str/blank? new-form) (#{:insert-before :insert-after} operation))
    (loop [loc zloc
           locations []]
      (if-let [{:keys [after-loc edit-span-loc]}
               (find-and-edit-one-multi-sexp loc operation match-form new-form)]
        (recur after-loc (conj locations edit-span-loc))
        (when (seq locations)
          {:zloc loc :locations locations})))))

(defn- find-and-edit-multi-sexp* [zloc match-form new-form {:keys [operation all?]}]
  (if all?
    (find-and-edit-all-multi-sexp zloc operation match-form new-form)
    (when-let [{:keys [after-loc edit-span-loc]}
               (find-and-edit-one-multi-sexp zloc operation match-form new-form)]
      {:zloc after-loc :locations [edit-span-loc]})))

(defn- find-and-edit-multi-sexp [zloc match-form new-form opts]
  (or (find-and-edit-multi-sexp* zloc match-form new-form opts)
      (binding [*match-clean* true]
        (find-and-edit-multi-sexp* zloc match-form new-form opts))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Main execute
;; ═══════════════════════════════════════════════════════════════════════════════

(defn- sexp-op [operation]
  (case operation
    "replace"       :replace
    "insert_before" :insert-before
    "insert_after"  :insert-after
    :replace))

(defn- sexp-file-error [file_path]
  (cond
    (str/blank? file_path)
    {:content "Missing required parameter: file_path" :is-error true}

    (not (clojure-file? file_path))
    {:content (not-clojure-file-msg "clojure_edit_replace_sexp" file_path)
     :is-error true}

    (not (fs/exists? file_path))
    {:content (str "File not found: " file_path) :is-error true}))

(defn- sexp-match-error [match_form]
  (if (str/blank? match_form)
    {:content "Missing required parameter: match_form" :is-error true}
    (or (when-let [problem (parse-problem "match_form" match_form)]
          {:content (str "match_form must be complete and balanced"
                         " (unbalanced delimiters are rejected, not auto-repaired):\n"
                         (:report problem)
                         "\nPass the complete S-expression(s), e.g. \"(+ x 1)\".")
           :is-error true})
        (let [parsed (try (p/parse-string-all match_form)
                          (catch Exception _ nil))]
          (when (or (nil? parsed)
                    (zero? (count (n/child-sexprs parsed))))
            {:content "match_form must contain at least one valid S-expression (not just comments or whitespace)"
             :is-error true})))))

(def ^:private sexp-definition-head-pattern
  #"^\((defn|defn-|defmacro|defmacro-|def|deftest|deftest-|defmethod|defprotocol|defrecord|ns)([ )])")

(defn- sexp-new-missing-error [new_form op-kw]
  (when (nil? new_form)
    {:content (str "Missing required parameter: new_form"
                   (if (= op-kw :replace)
                     " — pass \"\" (empty string) to delete the matched expression(s)."
                     " — insert_before/insert_after need the content to insert."))
     :is-error true}))

(defn- sexp-new-blank-error [new_form operation op-kw]
  (when (and (some? new_form) (str/blank? new_form) (not= op-kw :replace))
    {:content (str "new_form cannot be empty for operation \"" operation
                   "\" — insert_before/insert_after need the content to insert. "
                   "An empty new_form deletes only with operation \"replace\".")
     :is-error true}))

(defn- sexp-new-shape-error [content]
  (when content
    (let [head (second (re-find sexp-definition-head-pattern (str/trim content)))]
      (when head
        (when-let [shape-error (validate-form-shape head content)]
          {:content (str "Invalid new_form — not a well-formed Clojure definition:\n"
                         shape-error
                         "\nPass a complete, valid expression.")
           :is-error true})))))

(defn- sexp-new-error [new_form operation op-kw]
  (let [content (when-not (or (nil? new_form) (str/blank? new_form)) new_form)]
    (or (sexp-new-missing-error new_form op-kw)
        (sexp-new-blank-error new_form operation op-kw)
        (when-let [problem (when content (parse-problem "new_form" content))]
          {:content (str "new_form must be complete and balanced"
                         " (unbalanced delimiters are rejected, not auto-repaired):\n"
                         (:report problem)
                         "\nPass the complete S-expression(s), e.g. \"(* x 2)\".")
           :is-error true})
        (sexp-new-shape-error content))))

(defn replace-sexp
  "The clojure_edit_replace_sexp operation as a plain function: ARGS is its
   map, including `:file_path`; returns the tool result map and writes the
   file. A relative `:file_path` resolves against `:cwd` when supplied."
  [raw-args]
  (let [{:keys [file_path match_form new_form replace_all operation]}
        (resolved-args raw-args)
        op-kw (sexp-op operation)
        ;; insert operations never fan out
        replace-all? (and (boolean replace_all)
                          (not (contains? #{:insert-before :insert-after} op-kw)))
        content-form (when-not (or (nil? new_form) (str/blank? new_form))
                       new_form)]
    (or (sexp-file-error file_path)
        (sexp-match-error match_form)
        (sexp-new-error new_form operation op-kw)
        (edit-pipeline
         file_path
         (fn [zloc]
           (find-and-edit-multi-sexp
            zloc match_form content-form
            {:operation op-kw :all? replace-all?}))))))

;; ─── Delimiter repair (clojure_paren_repair and clj/paren-repair) ──────────

(defn repair-string
  "Repair delimiters in SOURCE and optionally format with cljfmt using
   FMT-OPTS (resolved from the file's directory by the caller, so the
   project's cljfmt.edn rules apply). Returns {:content str
   :delimiter-fixed? bool :formatted? bool}."
  [source format? fmt-opts]
  (let [[repaired delimiter-fixed?] (repair-delimiters source)
        formatted (if format?
                    (format-source-string repaired fmt-opts)
                    repaired)]
    {:content formatted
     :delimiter-fixed? delimiter-fixed?
     :formatted? (not= repaired formatted)}))

(defn repair-file!
  "Repair delimiter errors in FILE-PATH and optionally format.
   Returns {:success bool :message str :diff str-or-nil
            :delimiter-fixed? bool :formatted? bool}.
   A delimiter error that delimiter-only repair cannot fix (or a file that does
   not parse at all) is an explicit failure carrying the precise report —
   never a silent \"no changes\" success."
  [file-path format?]
  (cond
    (not (fs/exists? file-path))
    {:success false :message (str "File does not exist: " file-path)}

    (not (clojure-file? file-path))
    {:success false :message (not-clojure-file-msg "clojure_paren_repair" file-path)}

    :else
    (try
      (let [original (slurp-utf8 file-path)
            fmt-opts (project-fmt-opts file-path)
            result (repair-string original format? fmt-opts)
            final-content (:content result)
            changed? (not= original final-content)]
        (if changed?
          (do (spit-utf8 file-path final-content)
              (let [diff-str (edit-diff/generate-display-diff original final-content)
                    status-parts (cond-> []
                                   (:delimiter-fixed? result) (conj "delimiter-fixed")
                                   (:formatted? result) (conj "formatted"))]
                {:success true
                 :message (str "Fixed [" (str/join ", " status-parts) "]")
                 :delimiter-fixed? (:delimiter-fixed? result)
                 :formatted? (:formatted? result)
                 :diff diff-str}))
          ;; Nothing changed — either the file is clean or the problem is
          ;; beyond indent-mode repair. Say which, with the actual error.
          (if-let [problem (parse-problem file-path final-content)]
            {:success false
             :message (str "Could not fix " file-path " — it still does not parse:\n"
                           (:report problem)
                           "\n"
                           (case (:kind problem)
                             :delimiter "delimiter-only repair inserts missing closers and drops stray ones — fix this one manually (e.g. with the edit tool)."
                             "Fix the syntax error manually (e.g. with the edit tool)."))
             :delimiter-fixed? false
             :formatted? false
             :diff nil}
            {:success true
             :message "No changes needed (parses cleanly; no delimiter errors)"
             :delimiter-fixed? false
             :formatted? false
             :diff nil})))
      (catch Exception e
        {:success false
         :message (str "Error: " (ex-message e))
         :delimiter-fixed? false
         :formatted? false
         :diff nil}))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Tool execute
;; ═══════════════════════════════════════════════════════════════════════════════

(defn paren-repair
  "The clojure_paren_repair operation as a plain function: ARGS is its map,
   including `:file_path`; returns the tool result map and writes the file. A
   relative `:file_path` resolves against `:cwd` when supplied."
  [raw-args]
  (let [{:keys [file_path format]} (resolved-args raw-args)
        format? (if (nil? format) true (boolean format))]
    (cond
      (str/blank? file_path)
      {:content "Missing required parameter: file_path" :is-error true}

      :else
      (let [result (repair-file! file_path format?)]
        (if (:success result)
          (cond-> {:content (:message result)}
            (:diff result) (assoc :details {:diff (:diff result)}))
          {:content (:message result) :is-error true})))))

(defn- title-home-path
  [p]
  (path/home-relative p))

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
