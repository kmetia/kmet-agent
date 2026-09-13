(ns kmet.libs.highlight
  "Lexical syntax tokenizer for terminal code highlighting.

   Produces [scope text] token pairs (scope is a keyword or nil) from source
   text using per-language configs. Pure data — no ANSI codes, no theme, no
   kmet dependencies — so the TUI layer owns scope→color mapping, mirroring
   pi's split between utils/syntax-highlight.js (hljs + scope extraction) and
   theme.js (scope → ANSI).

   Languages are grouped by lexical shape (lisp-like, ALGOL-like, shell,
   data, markup, diff); each config is a small table over one generic
   scanner. Friction cases — regex literals, string interpolation, heredocs,
   raw/triple-quoted strings — are intentionally not modeled; they degrade
   to plain or string coloring.

   No auto-detection: tokenize returns nil for unknown languages so callers
   can fall back to plain code-block styling, like pi's highlightCode."
  (:require [clojure.string :as str]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Character sets
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private letters
  "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

(def ^:private word-algol
  "ALGOL-family identifier chars: letters, digits, underscore."
  (into #{} (str letters "0123456789_")))

(def ^:private word-lisp
  "Lisp symbol constituent chars: letters, digits, and the symbol chars
   allowed in Clojure/CL/Scheme names (e.g. + - * / ? ! < > = & $ % . : ~ ^)."
  (into #{} (str letters "0123456789*+!-_?<>=/&$%.:~^")))

(def ^:private word-hyphen
  "Identifiers that may contain '-' (yaml/toml/ini keys, css selectors)."
  (into #{} (str letters "0123456789_-")))

(def ^:private word-markup
  "HTML/XML name chars (tag/attribute names, namespaces)."
  (into #{} (str letters "0123456789-_:")))

(def ^:private word-algol-dot
  "Identifiers that may contain '.' (R names like data.frame)."
  (into word-algol "."))

(def ^:private word-qbang
  "Identifiers that may end in '?' or '!' (Elixir predicates/macros)."
  (into word-algol "?!"))

(def ^:private upper-letters
  "Uppercase variable triggers (Erlang/Prolog variables start uppercase)."
  (into #{} "ABCDEFGHIJKLMNOPQRSTUVWXYZ"))

(def ^:private operators-algol (into #{} "!%&*+-/<=>?@^|~"))
(def ^:private operators-shell (into #{} "<>&|;"))
(def ^:private operators-sql (into #{} "=<>*"))
(def ^:private punct-algol (into #{} "()[]{};:,"))
(def ^:private punct-lisp (into #{} "()[]{},'`"))
(def ^:private punct-shell (into #{} "()[]{}"))
(def ^:private punct-data (into #{} "{}[],:"))
(def ^:private punct-toml (into #{} "{}[],.=:"))
(def ^:private punct-sql (into #{} "(),;"))
(def ^:private punct-css (into #{} "{};:,"))

;; ═══════════════════════════════════════════════════════════════════════════
;; Scanning helpers
;; ═══════════════════════════════════════════════════════════════════════════

(defn- char-at [s i] (nth s i))

(defn- starts-with-at?
  "True when SUB occurs in S at index I."
  [s sub i]
  (let [end (+ i (count sub))]
    (and (<= end (count s)) (= sub (subs s i end)))))

(defn- scan-word-end
  "End index of the run of WORD-CHARS starting at I."
  [s i word-chars]
  (let [n (count s)]
    (loop [j i]
      (if (and (< j n) (contains? word-chars (char-at s j)))
        (recur (inc j))
        j))))

(defn- scan-string-end
  "End index (past the close quote) of a string starting at START with quote
   CHAR. Backslash escapes skip the next char. Ends at a newline unless
   MULTILINE? (lisp strings may span lines)."
  [s start char multiline?]
  (let [n (count s)]
    (loop [j (inc start)]
      (cond
        (>= j n) n
        (= (char-at s j) \\) (recur (min n (+ j 2)))
        (= (char-at s j) char) (inc j)
        (and (not multiline?) (= (char-at s j) \newline)) j
        :else (recur (inc j))))))

(def ^:private hex-chars (into #{} "0123456789abcdefABCDEF"))

(defn- hex-digit? [c]
  (contains? hex-chars c))

(defn- scan-number-end
  "End index of a number starting at I, or nil when I is not a number start.
   Handles decimals, fractions, exponents, and 0x/0b/0o radix prefixes."
  [s i]
  (let [n (count s)]
    (when (< i n)
      (let [c (char-at s i)
            nxt (when (< (inc i) n) (char-at s (inc i)))
            radix (cond
                    (and (= c \0) (contains? #{\x \X} nxt)) 16
                    (and (= c \0) (contains? #{\b \B} nxt)) 2
                    (and (= c \0) (contains? #{\o \O} nxt)) 8
                    :else nil)]
        (cond
          radix
          (let [start (+ i 2)
                j (loop [j start]
                    (if (and (< j n)
                             (if (= radix 16)
                               (hex-digit? (char-at s j))
                               (Character/isDigit (char-at s j))))
                      (recur (inc j))
                      j))]
            (when (> j start) j))

          (Character/isDigit c)
          (let [j (loop [j i]
                    (if (and (< j n) (Character/isDigit (char-at s j)))
                      (recur (inc j))
                      j))
                j (if (and (< j n) (= (char-at s j) \.)
                           (< (inc j) n) (Character/isDigit (char-at s (inc j))))
                    (loop [k (inc j)]
                      (if (and (< k n) (Character/isDigit (char-at s k)))
                        (recur (inc k))
                        k))
                    j)
                j (if (and (< j n) (contains? #{\e \E} (char-at s j)))
                    (let [k (if (and (< (inc j) n)
                                     (contains? #{\+ \-} (char-at s (inc j))))
                              (+ j 2)
                              (inc j))]
                      (if (and (< k n) (Character/isDigit (char-at s k)))
                        (loop [m k]
                          (if (and (< m n) (Character/isDigit (char-at s m)))
                            (recur (inc m))
                            m))
                        j))
                    j)]
            j)

          (and (= c \.) (< (inc i) n) (Character/isDigit (char-at s (inc i))))
          (loop [j (inc i)]
            (if (and (< j n) (Character/isDigit (char-at s j)))
              (recur (inc j))
              j))

          :else nil)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Rule matchers — each returns {:scope scope :n count} or nil
;; ═══════════════════════════════════════════════════════════════════════════

(defn- special-match
  "Language-specific regex rules (:specials) anchored at I. Each special has a
   :triggers char set so the regex only runs at relevant positions."
  [cfg s i]
  (let [c (char-at s i)]
    (some (fn [{:keys [re scope triggers]}]
            (when (contains? triggers c)
              (let [m (re-matcher re (subs s i))]
                (when (.lookingAt m)
                  {:scope scope :n (count (.group m))}))))
          (:specials cfg))))

(defn- line-comment-end [cfg s i]
  (let [n (count s)]
    (some (fn [lc]
            (when (and (= (first lc) (char-at s i))
                       (starts-with-at? s lc i))
              (or (str/index-of s "\n" (+ i (count lc))) n)))
          (:line-comments cfg))))

(defn- block-comment-end [cfg s i]
  (some (fn [[cs ce]]
          (when (and (= (first cs) (char-at s i))
                     (starts-with-at? s cs i))
            (let [start (+ i (count cs))
                  end (str/index-of s ce start)]
              (if end (+ end (count ce)) (count s)))))
        (:block-comments cfg)))

(defn- comment-match [cfg s i]
  ;; block first: block delimiters are more specific (lua --[[ vs --)
  (when-let [end (or (block-comment-end cfg s i)
                     (line-comment-end cfg s i))]
    {:scope :comment :n (- end i)}))

(defn- string-match [cfg s i]
  (when (contains? (:strings cfg) (char-at s i))
    (let [end (scan-string-end s i (char-at s i) (:multiline-strings? cfg))]
      {:scope :string :n (- end i)})))

(defn- hash-match
  "# reader macros: the :hash map entry for the next char selects :string,
   :line-comment, or [:block-comment close]; anything else gets :hash-default
   (:meta for lisp, :line-comment for julia). Disabled when cfg has no :hash
   key."
  [cfg s i]
  (when (and (:hash cfg) (< (inc i) (count s)) (= (char-at s i) \#))
    (let [action (get (:hash cfg) (char-at s (inc i)) (:hash-default cfg :meta))
          kind (if (vector? action) :block-comment action)]
      (case kind
        :string
        (let [end (scan-string-end s (inc i) \" (:multiline-strings? cfg))]
          {:scope :string :n (- end i)})
        :line-comment
        (let [end (or (str/index-of s "\n" (+ i 2)) (count s))]
          {:scope :comment :n (- end i)})
        :block-comment
        (let [close (if (vector? action) (second action) "|#")
              end (str/index-of s close (+ i 2))]
          {:scope :comment :n (if end (+ (- end i) (count close)) (- (count s) i))})
        {:scope :meta :n 2}))))

(defn- lookahead-scope
  "Scope for a word followed (after spaces/tabs) by a char in :lookahead —
   e.g. ALGOL calls foo( → :function, yaml keys key: → :attr."
  [cfg s end]
  (let [n (count s)
        j (loop [k end]
            (if (and (< k n) (contains? #{\space \tab} (char-at s k)))
              (recur (inc k))
              k))]
    (when (< j n)
      (get (:lookahead cfg) (char-at s j)))))

(defn- word-match [cfg s i]
  (when (contains? (:word-chars cfg) (char-at s i))
    (let [end (scan-word-end s i (:word-chars cfg))
          word (subs s i end)
          kw (if (:case-sensitive? cfg) word (str/lower-case word))
          scope (cond
                  (contains? (:keyword-prefixes cfg) (first word)) :symbol
                  (contains? (:literals cfg) kw) :literal
                  (contains? (:keywords cfg) kw) :keyword
                  :else (lookahead-scope cfg s end))]
      {:scope scope :n (- end i)})))

(defn- operator-match [cfg s i]
  (when (contains? (:operators cfg) (char-at s i))
    (let [end (loop [j i]
                (if (and (< j (count s)) (contains? (:operators cfg) (char-at s j)))
                  (recur (inc j))
                  j))]
      {:scope :operator :n (- end i)})))

(defn- punct-match [cfg s i]
  (when (contains? (:punctuation cfg) (char-at s i))
    {:scope :punctuation :n 1}))

(defn- number-match [s i]
  (when-let [end (scan-number-end s i)]
    {:scope :number :n (- end i)}))

(defn- match-at [cfg s i]
  (or (special-match cfg s i)
      (hash-match cfg s i)
      (comment-match cfg s i)
      (string-match cfg s i)
      (number-match s i)
      (word-match cfg s i)
      (operator-match cfg s i)
      (punct-match cfg s i)
      {:scope nil :n 1}))

(defn- scan-generic
  "Single-pass char scanner driven by CFG's rule matchers. Accumulates
   into a transient (persistent conj per token costs ~2x on bb, ~1.2x on
   jolt at these sizes)."
  [s cfg]
  (loop [i 0, toks (transient [])]
    (if (>= i (count s))
      (persistent! toks)
      (let [{:keys [scope n]} (match-at cfg s i)]
        (recur (+ i n) (conj! toks [scope (subs s i (+ i n))]))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Custom scanners (markup, line-based: diff/dockerfile/markdown)
;; ═══════════════════════════════════════════════════════════════════════════

(defn- scan-markup
  "HTML/XML scanner: tags, attribute names/values, comments, entities."
  [s _cfg]
  (let [n (count s)]
    (loop [i 0, toks (transient []), in-tag? false, first-word? false]
      (if (>= i n)
        (persistent! toks)
        (let [c (char-at s i)]
          (cond
            ;; comment
            (and (= c \<) (starts-with-at? s "<!--" i))
            (let [end (str/index-of s "-->" (+ i 4))
                  end (if end (+ end 3) n)]
              (recur end (conj! toks [:comment (subs s i end)]) false false))

            ;; entity &name;
            (= c \&)
            (let [end (str/index-of s ";" (inc i))]
              (if (and end (< end (+ i 8)))
                (recur (inc end) (conj! toks [:name (subs s i (inc end))])
                       in-tag? first-word?)
                (recur (inc i) (conj! toks [nil (subs s i (inc i))])
                       in-tag? first-word?)))

            ;; inside a tag
            in-tag?
            (cond
              (= c \>) (recur (inc i) (conj! toks [:tag (subs s i (inc i))]) false false)
              (= c \/) (recur (inc i) (conj! toks [:tag (subs s i (inc i))]) true first-word?)
              (= c \=) (recur (inc i) (conj! toks [:operator (subs s i (inc i))]) true first-word?)
              (or (= c \") (= c \'))
              (let [end (or (str/index-of s (str c) (inc i)) n)]
                (recur (inc end) (conj! toks [:string (subs s i (inc end))])
                       true first-word?))
              (contains? word-markup c)
              (let [end (scan-word-end s i word-markup)]
                (recur end
                       (conj! toks [(if first-word? :tag :attr) (subs s i end)])
                       true false))
              :else (recur (inc i) (conj! toks [nil (subs s i (inc i))]) true first-word?))

            ;; tag open
            (= c \<) (recur (inc i) (conj! toks [:tag (subs s i (inc i))]) true true)

            ;; plain text run
            :else
            (let [j (loop [k i]
                      (if (and (< k n) (not= (char-at s k) \<) (not= (char-at s k) \&))
                        (recur (inc k))
                        k))]
              (recur j (conj! toks [nil (subs s i j)]) in-tag? first-word?))))))))

(defn- scan-lines
  "Line-based scanner: CLASSIFY maps each source line to a scope; each line is
   one token with its separator preserved, so the stream reconstructs source."
  [s classify]
  (let [lines (str/split s #"\n" -1)
        last-i (dec (count lines))]
    (map-indexed (fn [i l]
                   [(classify l) (if (< i last-i) (str l "\n") l)])
                 lines)))

(defn- diff-scope [l]
  (cond
    (or (str/starts-with? l "+++") (str/starts-with? l "---")) :meta
    (str/starts-with? l "@@") :meta
    (or (str/starts-with? l "diff ")
        (str/starts-with? l "index ")
        (str/starts-with? l "new file ")
        (str/starts-with? l "deleted file ")
        (str/starts-with? l "Binary files "))
    :meta
    (str/starts-with? l "+") :addition
    (str/starts-with? l "-") :deletion
    :else nil))

(defn- scan-diff
  "Unified diff scanner: whole lines get :meta (headers), :addition,
   :deletion, or nil (context)."
  [s _cfg]
  (scan-lines s diff-scope))

(def ^:private docker-instructions
  #{"ADD" "ARG" "CMD" "COPY" "ENTRYPOINT" "ENV" "EXPOSE" "FROM"
    "HEALTHCHECK" "LABEL" "MAINTAINER" "ONBUILD" "RUN" "SHELL"
    "STOPSIGNAL" "USER" "VOLUME" "WORKDIR"})

(defn- dockerfile-scope [l]
  (cond
    (str/starts-with? l "#") :comment
    (some #(or (= l %) (str/starts-with? l (str % " ")) (str/starts-with? l (str % "\t")))
          docker-instructions)
    :meta
    :else nil))

(defn- scan-dockerfile
  "Dockerfile scanner: comment lines and instruction lines (FROM/RUN/...)."
  [s _cfg]
  (scan-lines s dockerfile-scope))

(defn- markdown-scope [l]
  (let [t (str/triml l)]
    (cond
      (re-matches #"^#{1,6}(\s|$).*" t) :section
      (re-matches #"^```.*" t) :code
      (or (str/starts-with? l "    ") (str/starts-with? l "\t")) :code
      (re-matches #"^>\s?.*" t) :meta
      (re-matches #"^(\*{3}|-{3}|_{3})\s*$" t) :meta
      :else nil)))

(defn- scan-markdown
  "Markdown scanner (fenced): headings, fences/indented code, quotes, hr."
  [s _cfg]
  (scan-lines s markdown-scope))

;; ═══════════════════════════════════════════════════════════════════════════
;; Language configs — grouped by lexical shape
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private algol-base
  {:line-comments ["//"]
   :block-comments [["/*" "*/"]]
   :strings #{\" \'}
   :word-chars word-algol
   :lookahead {\( :function}
   :operators operators-algol
   :punctuation punct-algol
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private lisp-base
  {:line-comments [";"]
   :strings #{\"}
   :word-chars word-lisp
   :keyword-prefixes #{\:}
   :operators #{}
   :punctuation punct-lisp
   :case-sensitive? true
   :multiline-strings? true})

(def ^:private shell-base
  {:line-comments ["#"]
   :strings #{\" \'}
   :word-chars word-algol
   :operators operators-shell
   :punctuation punct-shell
   :specials [{:re #"\$\{[^}]*\}" :scope :variable :triggers #{\$}}
              {:re #"\$[A-Za-z_][A-Za-z0-9_]*" :scope :variable :triggers #{\$}}
              {:re #"\$[0-9?@!#*$_-]" :scope :variable :triggers #{\$}}]
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private python-config
  (assoc algol-base
         :line-comments ["#"]
         :literals #{"True" "False" "None"}
         :specials [{:re #"@[\w.]+" :scope :meta :triggers #{\@}}]))

(def ^:private ruby-config
  (assoc algol-base
         :line-comments ["#"]
         :literals #{"true" "false" "nil"}))

(def ^:private json-config
  {:strings #{\"}
   :word-chars word-algol
   :literals #{"true" "false" "null"}
   :operators #{}
   :punctuation punct-data
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private yaml-config
  {:line-comments ["#"]
   :strings #{\" \'}
   :word-chars word-hyphen
   :lookahead {\: :attr}
   :literals #{"true" "false" "null" "yes" "no" "on" "off"}
   :operators #{}
   :punctuation punct-data
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private toml-config
  {:line-comments ["#"]
   :strings #{\" \'}
   :word-chars word-hyphen
   :lookahead {\= :attr, \] :section}
   :literals #{"true" "false"}
   :operators #{}
   :punctuation punct-toml
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private ini-config
  {:line-comments [";" "#"]
   :strings #{\" \'}
   :word-chars word-hyphen
   :lookahead {\= :attr, \: :attr, \] :section}
   :operators #{}
   :punctuation punct-toml
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private sql-config
  {:line-comments ["--"]
   :strings #{\" \'}
   :word-chars word-algol
   :operators operators-sql
   :punctuation punct-sql
   :case-sensitive? false
   :multiline-strings? false})

(def ^:private css-config
  {:block-comments [["/*" "*/"]]
   :strings #{\"}
   :word-chars word-hyphen
   :lookahead {\: :attr}
   :specials [{:re #"@[\w-]+" :scope :meta :triggers #{\@}}]
   :operators #{}
   :punctuation punct-css
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private markup-config {:scanner scan-markup})
(def ^:private diff-config {:scanner scan-diff})
(def ^:private dockerfile-config {:scanner scan-dockerfile})
(def ^:private markdown-config {:scanner scan-markdown})

;; ─── Keyword sets ──────────────────────────────────────────────────────────

(def ^:private clojure-kws
  #{"def" "defn" "defn-" "defmacro" "defonce" "defmulti" "defmethod"
    "defprotocol" "deftype" "defrecord" "defstruct" "definterface"
    "fn" "fn*" "let" "let*" "letfn" "loop" "recur"
    "if" "if-not" "if-let" "if-some" "when" "when-not" "when-let"
    "when-first" "when-some" "cond" "condp" "cond->" "cond->>" "case"
    "do" "dotimes" "doseq" "dorun" "doall" "delay" "future"
    "quote" "var" "throw" "try" "catch" "finally"
    "and" "or" "not" "->" "->>" "as->" "doto" "some->" "some->>"
    "comment" "declare" "ns" "require" "use" "import" "refer" "in-ns"
    "binding" "with-open" "with-local-vars" "with-redefs" "with-out-str"
    "with-in-str" "locking" "set!"})

(def ^:private scheme-kws
  #{"define" "define-syntax" "define-record-type" "define-values"
    "lambda" "let" "let*" "letrec" "letrec*" "let-values" "let*-values"
    "if" "cond" "case" "else" "when" "unless" "begin" "do" "delay"
    "delay-force" "quasiquote" "unquote" "unquote-splicing" "quote"
    "set!" "syntax-rules" "syntax-case" "module" "import" "export"
    "and" "or" "and-let*" "parameterize" "call-with-current-continuation"
    "call/cc" "call-with-values" "values" "apply" "map" "for-each"
    "filter" "fold-left" "fold-right" "vector" "cons" "car" "cdr" "list"
    "append" "reverse" "length" "member" "assoc" "eq?" "eqv?" "equal?"
    "string?" "number?" "symbol?" "list?" "pair?" "null?" "not"
    "guard" "raise" "raise-continuable" "error" "dynamic-wind"
    "string-append" "string-length" "string-ref" "vector-ref"
    "vector-length" "list-ref" "list-tail" "make-vector" "make-list"
    "make-string"})

(def ^:private cl-kws
  #{"defun" "defmacro" "defvar" "defparameter" "defconstant" "defstruct"
    "defclass" "defmethod" "defgeneric" "defpackage" "in-package" "lambda"
    "let" "let*" "progn" "prog1" "prog2" "when" "unless" "if" "cond" "case"
    "ecase" "typecase" "etypecase" "loop" "do" "do*" "dolist" "dotimes"
    "while" "until" "return" "return-from" "block" "catch" "throw"
    "unwind-protect" "handler-case" "handler-bind" "ignore-errors" "error"
    "warn" "format" "print" "princ" "terpri" "read" "eval" "apply" "funcall"
    "mapcar" "maplist" "map" "append" "cons" "list" "car" "cdr" "nth" "elt"
    "length" "reverse" "nreverse" "sort" "setq" "setf" "push" "pop" "incf"
    "decf" "and" "or" "not" "eq" "eql" "equal" "equalp" "null" "atom"
    "consp" "listp" "symbolp" "numberp" "stringp" "characterp" "integerp"
    "floatp" "declare" "quote" "function" "the" "values"
    "multiple-value-bind" "multiple-value-list" "multiple-value-prog1"
    "destructuring-bind" "labels" "flet" "macrolet" "defun*" "defmacro*"
    "with-slots" "with-accessors" "with-open-file" "with-output-to-string"
    "with-input-from-string" "call-next-method" "make-instance"
    "slot-value" "class-name" "find-class"})

(def ^:private elisp-kws
  #{"defun" "defmacro" "defvar" "defconst" "defcustom" "defgroup"
    "defalias" "defsubst" "defadvice" "defface" "defun*" "defmacro*"
    "lambda" "let" "let*" "let1" "if" "cond" "when" "unless" "progn"
    "prog1" "prog2" "while" "until" "dolist" "dotimes" "catch" "throw"
    "unwind-protect" "condition-case" "ignore-errors" "save-excursion"
    "save-window-excursion" "save-restriction" "save-match-data"
    "with-current-buffer" "with-temp-buffer" "with-temp-file"
    "setq" "setq-default" "setf" "push" "pop" "incf" "decf" "quote"
    "function" "interactive" "or" "and" "not" "eq" "eql" "equal"
    "string=" "string-equal" "string<" "string-lessp" "numberp" "stringp"
    "symbolp" "listp" "consp" "integerp" "floatp" "bufferp" "windowp"
    "fboundp" "boundp" "car" "cdr" "cons" "list" "append" "nth" "nthcdr"
    "elt" "length" "mapcar" "mapc" "mapconcat" "apply" "funcall" "format"
    "message" "insert" "goto-char" "point" "point-min" "point-max"
    "match-beginning" "match-end" "replace-regexp-in-string"
    "buffer-substring" "current-buffer" "current-kill" "search-forward"
    "re-search-forward" "looking-at" "error" "warn" "propertize"
    "make-local-variable" "kill-new" "remove-hook" "add-hook" "run-hooks"
    "defvar-local" "setq-local"})

(def ^:private java-kws
  #{"abstract" "assert" "boolean" "break" "byte" "case" "catch" "char"
    "class" "const" "continue" "default" "do" "double" "else" "enum"
    "extends" "final" "finally" "float" "for" "goto" "if" "implements"
    "import" "instanceof" "int" "interface" "long" "native" "new"
    "package" "private" "protected" "public" "return" "short" "static"
    "strictfp" "super" "switch" "synchronized" "this" "throw" "throws"
    "transient" "try" "void" "volatile" "while" "var" "record" "sealed"
    "permits" "yield"})

(def ^:private kotlin-kws
  #{"as" "break" "class" "continue" "do" "else" "for" "fun" "if" "in"
    "interface" "is" "object" "package" "return" "super" "this" "throw"
    "try" "typealias" "typeof" "val" "var" "when" "while" "by" "catch"
    "constructor" "delegate" "dynamic" "field" "file" "finally" "get"
    "import" "init" "param" "property" "receiver" "set" "setparam"
    "where" "actual" "abstract" "annotation" "companion" "const"
    "crossinline" "data" "enum" "expect" "external" "final" "infix"
    "inline" "inner" "internal" "lateinit" "noinline" "open" "operator"
    "out" "override" "private" "protected" "public" "reified" "sealed"
    "suspend" "tailrec" "vararg"})

(def ^:private js-kws
  #{"break" "case" "catch" "class" "const" "continue" "debugger" "default"
    "delete" "do" "else" "enum" "export" "extends" "finally" "for"
    "function" "if" "import" "in" "instanceof" "new" "return" "super"
    "switch" "this" "throw" "try" "typeof" "var" "void" "while" "with"
    "yield" "let" "static" "async" "await" "get" "set" "of"})

(def ^:private ts-kws
  (into js-kws
        #{"interface" "type" "namespace" "declare" "abstract" "implements"
          "private" "protected" "public" "readonly" "keyof" "infer" "is"
          "as" "satisfies" "enum" "module" "override"}))

(def ^:private c-kws
  #{"auto" "break" "case" "char" "const" "continue" "default" "do"
    "double" "else" "enum" "extern" "float" "for" "goto" "if" "inline"
    "int" "long" "register" "restrict" "return" "short" "signed"
    "sizeof" "static" "struct" "switch" "typedef" "union" "unsigned"
    "void" "volatile" "while" "_Bool" "_Complex" "_Imaginary"})

(def ^:private cpp-kws
  (into c-kws
        #{"alignas" "alignof" "and" "and_eq" "asm" "bitand" "bitor" "bool"
          "catch" "char8_t" "char16_t" "char32_t" "class" "compl" "concept"
          "consteval" "constexpr" "constinit" "const_cast" "co_await"
          "co_return" "co_yield" "decltype" "delete" "dynamic_cast"
          "explicit" "export" "friend" "mutable" "namespace" "new"
          "noexcept" "not" "not_eq" "operator" "or" "or_eq" "private"
          "protected" "public" "reinterpret_cast" "requires" "static_assert"
          "static_cast" "template" "this" "thread_local" "throw" "try"
          "typeid" "typename" "using" "virtual" "wchar_t" "xor" "xor_eq"}))

(def ^:private csharp-kws
  #{"abstract" "as" "base" "bool" "break" "byte" "case" "catch" "char"
    "checked" "class" "const" "continue" "decimal" "default" "delegate"
    "do" "double" "else" "enum" "event" "explicit" "extern" "finally"
    "fixed" "float" "for" "foreach" "goto" "if" "implicit" "in" "int"
    "interface" "internal" "is" "lock" "long" "namespace" "new" "object"
    "operator" "out" "override" "params" "private" "protected" "public"
    "readonly" "ref" "return" "sbyte" "sealed" "short" "sizeof"
    "stackalloc" "static" "string" "struct" "switch" "this" "throw" "try"
    "typeof" "uint" "ulong" "unchecked" "unsafe" "ushort" "using" "var"
    "virtual" "void" "volatile" "while" "async" "await"})

(def ^:private go-kws
  #{"break" "default" "func" "interface" "select" "case" "defer" "go"
    "map" "struct" "chan" "else" "goto" "package" "switch" "const"
    "fallthrough" "if" "range" "type" "continue" "for" "import" "return"
    "var"})

(def ^:private rust-kws
  #{"as" "async" "await" "break" "const" "continue" "crate" "dyn" "else"
    "enum" "extern" "fn" "for" "if" "impl" "in" "let" "loop" "match" "mod"
    "move" "mut" "pub" "ref" "return" "self" "Self" "static" "struct"
    "super" "trait" "type" "unsafe" "use" "where" "while"})

(def ^:private swift-kws
  #{"associatedtype" "class" "deinit" "enum" "extension" "fileprivate"
    "func" "import" "init" "inout" "internal" "let" "open" "operator"
    "private" "protocol" "public" "rethrows" "static" "struct" "subscript"
    "typealias" "var" "break" "case" "continue" "default" "defer" "do"
    "else" "fallthrough" "for" "guard" "if" "in" "is" "repeat" "return"
    "switch" "where" "while" "as" "Any" "catch" "super" "self" "Self"
    "throws" "try"})

(def ^:private dart-kws
  #{"abstract" "as" "assert" "async" "await" "break" "case" "catch" "class"
    "const" "continue" "covariant" "default" "deferred" "do" "dynamic"
    "else" "enum" "export" "extends" "extension" "external" "factory"
    "final" "finally" "for" "Function" "get" "hide" "if" "implements"
    "import" "in" "interface" "is" "late" "library" "mixin" "new" "on"
    "operator" "part" "required" "rethrow" "return" "set" "show" "static"
    "super" "switch" "sync" "this" "throw" "try" "typedef" "var" "void"
    "while" "with" "yield"})

(def ^:private python-kws
  #{"and" "as" "assert" "async" "await" "break" "class" "continue" "def"
    "del" "elif" "else" "except" "finally" "for" "from" "global" "if"
    "import" "in" "is" "lambda" "nonlocal" "not" "or" "pass" "raise"
    "return" "try" "while" "with" "yield" "match" "case"})

(def ^:private ruby-kws
  #{"BEGIN" "END" "alias" "and" "begin" "break" "case" "class" "def"
    "defined?" "do" "else" "elsif" "end" "ensure" "for" "if" "in"
    "module" "next" "not" "or" "redo" "rescue" "retry" "return" "self"
    "super" "then" "undef" "unless" "until" "when" "while" "yield"})

(def ^:private bash-kws
  #{"if" "then" "else" "elif" "fi" "case" "esac" "for" "while" "until"
    "do" "done" "function" "select" "in" "time" "coproc"
    "local" "readonly" "export" "declare" "typeset" "set" "unset" "shift"
    "exit" "return" "trap" "wait" "eval" "exec" "source" "let"})

(def ^:private sql-kws
  #{"select" "insert" "update" "delete" "from" "where" "and" "or" "not"
    "in" "is" "like" "between" "group" "by" "order" "having"
    "join" "inner" "left" "right" "outer" "full" "cross" "on" "as"
    "distinct" "union" "all" "except" "intersect" "create" "table" "index"
    "view" "trigger" "drop" "alter" "add" "column" "primary" "key"
    "foreign" "references" "default" "values" "set" "into" "case" "when"
    "then" "else" "end" "exists" "any" "some" "asc" "desc" "limit"
    "offset" "with" "recursive" "returning" "constraint" "unique" "check"
    "cast" "coalesce" "nullif" "count" "sum" "avg" "min" "max" "top"})

;; ─── Additional keyword sets ────────────────────────────────────────────────

(def ^:private php-kws
  #{"abstract" "and" "array" "as" "break" "callable" "case" "catch"
    "class" "clone" "const" "continue" "declare" "default" "do" "echo"
    "else" "elseif" "empty" "enddeclare" "endfor" "endforeach" "endif"
    "endswitch" "endwhile" "enum" "extends" "final" "finally" "fn" "for"
    "foreach" "function" "global" "goto" "if" "implements" "include"
    "include_once" "instanceof" "insteadof" "interface" "isset" "list"
    "match" "namespace" "new" "or" "print" "private" "protected"
    "public" "readonly" "require" "require_once" "return" "static"
    "switch" "throw" "trait" "try" "unset" "use" "var" "while" "xor"
    "yield"})

(def ^:private scala-kws
  #{"abstract" "case" "catch" "class" "def" "do" "else" "extends"
    "final" "finally" "for" "forSome" "if" "implicit" "import" "lazy"
    "macro" "match" "new" "object" "override" "package" "private"
    "protected" "return" "sealed" "super" "this" "throw" "trait" "try"
    "type" "val" "var" "while" "with" "yield"})

(def ^:private groovy-kws
  #{"abstract" "as" "assert" "break" "case" "catch" "class" "const"
    "continue" "def" "default" "do" "else" "enum" "extends" "finally"
    "for" "goto" "if" "implements" "import" "in" "instanceof" "interface"
    "new" "package" "private" "protected" "public" "return" "static"
    "super" "switch" "this" "throw" "throws" "trait" "try" "while"})

(def ^:private objc-kws
  (into c-kws
        #{"self" "super" "id" "Nil" "Class" "SEL" "IMP" "instancetype"
          "in" "out" "inout" "bycopy" "byref" "oneway" "readonly"
          "readwrite" "nonatomic" "atomic" "strong" "weak" "copy" "retain"
          "assign" "unsafe_unretained" "protocol" "implementation"
          "interface" "end" "property" "synthesize" "dynamic" "selector"
          "import" "optional" "required"}))

(def ^:private lua-kws
  #{"and" "break" "do" "else" "elseif" "end" "for" "function" "goto"
    "if" "in" "local" "not" "or" "repeat" "return" "then" "until"
    "while"})

(def ^:private powershell-kws
  #{"begin" "break" "catch" "class" "continue" "data" "define" "do"
    "dynamicparam" "else" "elseif" "end" "enum" "exit" "filter"
    "finally" "for" "foreach" "from" "function" "if" "in" "param"
    "process" "return" "static" "switch" "throw" "trap" "try" "until"
    "using" "var" "while" "workflow" "sequence" "parallel" "inlinescript"
    "configuration"})

(def ^:private hcl-kws
  #{"resource" "data" "variable" "output" "module" "provider" "terraform"
    "locals" "backend" "moved" "import" "lifecycle" "dynamic" "count"
    "for_each" "source" "version" "required_providers" "required_version"})

(def ^:private perl-kws
  #{"my" "our" "local" "use" "require" "no" "package" "sub" "BEGIN"
    "END" "if" "elsif" "else" "unless" "while" "until" "for" "foreach"
    "do" "given" "when" "default" "continue" "last" "next" "redo"
    "return" "goto" "and" "or" "not" "xor" "eq" "ne" "lt" "gt" "le"
    "ge" "cmp" "format" "state"})

(def ^:private haskell-kws
  #{"as" "case" "class" "data" "default" "deriving" "do" "else" "foreign"
    "if" "import" "in" "infix" "infixl" "infixr" "instance" "let"
    "module" "newtype" "of" "then" "type" "where" "_"})

(def ^:private elixir-kws
  #{"after" "alias" "and" "case" "catch" "cond" "def" "defdelegate"
    "defexception" "defimpl" "defmacro" "defmodule" "defp" "defprotocol"
    "defstruct" "do" "else" "end" "fn" "for" "if" "import" "in" "list"
    "module" "quote" "raise" "receive" "redo" "require" "rescue" "reraise"
    "return" "send" "super" "throw" "try" "unless" "unquote"
    "unquote_splicing" "use" "when" "with" "yield"})

(def ^:private erlang-kws
  #{"after" "begin" "case" "catch" "cond" "end" "fun" "if" "let" "of"
    "query" "receive" "try" "when" "bnot" "bsl" "bsr" "band" "bor"
    "bxor" "div" "rem" "and" "andalso" "or" "orelse" "not" "xor"})

(def ^:private r-kws
  #{"function" "if" "else" "repeat" "while" "for" "in" "next" "break"
    "return"})

(def ^:private matlab-kws
  #{"function" "if" "else" "elseif" "end" "for" "while" "switch" "case"
    "otherwise" "try" "catch" "return" "break" "continue" "global"
    "persistent" "parfor" "spmd" "classdef" "properties" "methods" "events"
    "enumeration" "arguments"})

(def ^:private julia-kws
  #{"abstract" "baremodule" "begin" "break" "catch" "ccall" "const"
    "continue" "do" "else" "elseif" "end" "export" "finally" "for"
    "function" "global" "if" "import" "in" "let" "local" "macro" "module"
    "mutable" "primitive" "quote" "return" "struct" "try" "type" "using"
    "while"})

(def ^:private fortran-kws
  #{"program" "end" "endprogram" "subroutine" "endsubroutine" "function"
    "endfunction" "module" "endmodule" "use" "implicit" "none" "integer"
    "real" "double" "precision" "complex" "character" "logical" "parameter"
    "save" "data" "common" "block" "call" "return" "if" "then" "else"
    "elseif" "endif" "enddo" "while" "endwhile" "forall" "where" "select"
    "case" "default" "cycle" "exit" "stop" "continue" "print" "write"
    "read" "open" "close" "format" "contains" "interface" "procedure"
    "allocatable" "dimension" "intent" "optional" "present" "target"
    "pointer" "public" "private" "type" "endtype" "class" "endclass"})

(def ^:private fsharp-kws
  #{"abstract" "and" "as" "assert" "base" "begin" "class" "default"
    "delegate" "do" "done" "downcast" "downto" "elif" "else" "end"
    "exception" "extern" "finally" "fixed" "for" "fun" "function" "global"
    "if" "in" "inherit" "inline" "interface" "internal" "let" "match"
    "member" "module" "mutable" "namespace" "new" "not" "null" "of" "open"
    "or" "override" "private" "public" "rec" "return" "select" "static"
    "struct" "then" "try" "type" "upcast" "use" "val" "void" "when"
    "while" "with" "yield"})

(def ^:private ocaml-kws
  #{"and" "as" "assert" "begin" "class" "constraint" "do" "done"
    "downto" "else" "end" "exception" "external" "for" "fun" "function"
    "functor" "if" "in" "include" "inherit" "initializer" "lazy" "let"
    "match" "method" "module" "mutable" "new" "object" "of" "open" "or"
    "private" "rec" "sig" "struct" "then" "to" "try" "type" "val"
    "virtual" "when" "while" "with"})

(def ^:private nim-kws
  #{"addr" "and" "as" "asm" "bind" "block" "break" "case" "cast"
    "concept" "const" "continue" "converter" "defer" "discard" "distinct"
    "div" "do" "elif" "else" "end" "enum" "except" "export" "finally"
    "for" "from" "func" "if" "import" "in" "include" "interface" "is"
    "isnot" "iterator" "let" "macro" "method" "mixin" "mod" "not" "notin"
    "object" "of" "or" "out" "proc" "ptr" "raise" "ref" "return" "shl"
    "shr" "static" "template" "try" "tuple" "type" "using" "var" "when"
    "while" "xor" "yield"})

(def ^:private crystal-kws
  #{"abstract" "alias" "as" "asm" "begin" "break" "case" "class" "def"
    "do" "else" "elsif" "end" "ensure" "enum" "extend" "external" "for"
    "fun" "if" "ifdef" "in" "include" "instance_sizeof" "is_a?" "lib"
    "macro" "module" "next" "nil?" "of" "out" "pointerof" "private"
    "protected" "public" "require" "rescue" "responds_to?" "return" "select"
    "self" "sizeof" "struct" "super" "then" "type" "typeof" "uninitialized"
    "union" "unless" "until" "verbatim" "when" "while" "with" "yield"})

(def ^:private vb-kws
  #{"addhandler" "addressof" "alias" "and" "andalso" "as" "boolean"
    "byref" "byte" "byval" "call" "case" "catch" "cbool" "cbyte" "cchar"
    "cdate" "cdec" "cdbl" "char" "cint" "class" "clng" "cobj" "const"
    "continue" "csbyte" "cshort" "csng" "cstr" "ctype" "date" "decimal"
    "declare" "default" "delegate" "dim" "directcast" "do" "double" "each"
    "else" "elseif" "end" "endif" "enum" "erase" "error" "event" "exit"
    "finally" "for" "friend" "function" "get" "gettype" "global" "goto"
    "handles" "if" "implements" "in" "inherits" "integer" "interface" "is"
    "isnot" "let" "lib" "like" "long" "loop" "me" "mod" "module"
    "mustinherit" "mustoverride" "mybase" "myclass" "namespace" "narrowing"
    "new" "next" "not" "nothing" "notinheritable" "notoverridable" "object"
    "of" "on" "operator" "option" "optional" "or" "orelse" "overloads"
    "overridable" "overrides" "paramarray" "partial" "private" "property"
    "protected" "public" "raiseevent" "readonly" "redim" "removehandler"
    "resume" "return" "sbyte" "select" "set" "shadows" "shared" "short"
    "single" "static" "step" "stop" "string" "structure" "sub" "synclock"
    "then" "throw" "to" "try" "trycast" "typeof" "uinteger" "ulong"
    "ushort" "using" "variant" "wend" "when" "while" "widening" "with"
    "writeonly" "xor"})

(def ^:private cmake-kws
  #{"add_custom_command" "add_custom_target" "add_definitions"
    "add_dependencies" "add_executable" "add_library" "add_subdirectory"
    "add_test" "aux_source_directory" "break" "build_command"
    "cmake_host_system_information" "cmake_minimum_required"
    "cmake_parse_arguments" "cmake_policy" "configure_file" "continue"
    "create_test_sourcelist" "define_property" "else" "elseif"
    "enable_language" "enable_testing" "endforeach" "endfunction" "endif"
    "endmacro" "endwhile" "execute_process" "export" "file" "find_file"
    "find_library" "find_package" "find_path" "find_program" "fltk_wrap_ui"
    "foreach" "function" "get_cmake_property" "get_directory_property"
    "get_filename_component" "get_property" "if" "include"
    "include_directories" "include_external_msproject"
    "include_regular_expression" "install" "link_directories" "list"
    "load_cache" "macro" "mark_as_advanced" "math" "message" "option"
    "project" "qt_wrap_cpp" "qt_wrap_ui" "remove_definitions" "return"
    "separate_arguments" "set" "set_directory_properties" "set_property"
    "set_tests_properties" "site_name" "source_group" "string"
    "target_compile_definitions" "target_compile_features"
    "target_compile_options" "target_include_directories" "target_link_libraries"
    "target_sources" "try_compile" "try_run" "unset" "variable_watch"
    "while"})

(def ^:private fish-kws
  #{"if" "else" "end" "for" "while" "function" "return" "break" "continue"
    "and" "or" "not" "begin" "switch" "case" "in"})

(def ^:private zig-kws
  #{"as" "async" "await" "break" "callconv" "catch" "comptime" "const"
    "continue" "defer" "else" "enum" "errdefer" "error" "export" "extern"
    "fn" "for" "if" "inline" "noalias" "noinline" "opaque" "or" "orelse"
    "packed" "pub" "resume" "return" "struct" "suspend" "switch" "test"
    "threadlocal" "try" "union" "unreachable" "usingnamespace" "var"
    "volatile" "while"})

;; ─── Additional language configs ────────────────────────────────────────────

(def ^:private php-config
  (assoc algol-base
         :line-comments ["//" "#"]
         :keywords php-kws
         :literals #{"true" "false" "null"}
         :specials [{:re #"<\?php\b" :scope :meta :triggers #{\<}}
                    {:re #"\?>" :scope :meta :triggers #{\?}}
                    {:re #"\$[A-Za-z_][A-Za-z0-9_]*" :scope :variable :triggers #{\$}}
                    {:re #"\$\{[^}]*}" :scope :variable :triggers #{\$}}]))

(def ^:private scala-config
  (assoc algol-base
         :keywords scala-kws
         :literals #{"true" "false" "null"}))

(def ^:private groovy-config
  (assoc algol-base
         :keywords groovy-kws
         :literals #{"true" "false" "null"}))

(def ^:private objc-config
  (assoc algol-base
         :keywords objc-kws
         :literals #{"true" "false" "nil" "Nil" "YES" "NO"}
         :specials [{:re #"#\w+" :scope :meta :triggers #{\#}}
                    {:re #"@\w+" :scope :meta :triggers #{\@}}]))

(def ^:private lua-config
  {:line-comments ["--"]
   :block-comments [["--[[" "]]"]]
   :strings #{\" \'}
   :word-chars word-algol
   :keywords lua-kws
   :literals #{"true" "false" "nil"}
   :operators (into #{} "+-*/%^#=<>~:.")
   :punctuation (into #{} "(){}[];,")
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private powershell-config
  {:line-comments ["#"]
   :block-comments [["<#" "#>"]]
   :strings #{\" \'}
   :word-chars word-algol
   :keywords powershell-kws
   :literals #{"true" "false" "null"}
   :specials [{:re #"\$\{[^}]*}" :scope :variable :triggers #{\$}}
              {:re #"\$[A-Za-z_][A-Za-z0-9_]*" :scope :variable :triggers #{\$}}
              {:re #"-[A-Za-z][A-Za-z0-9]*" :scope :attr :triggers #{\-}}]
   :operators operators-shell
   :punctuation (into #{} "(){}[];,")
   :case-sensitive? false
   :multiline-strings? false})

(def ^:private hcl-config
  {:line-comments ["#" "//"]
   :block-comments [["/*" "*/"]]
   :strings #{\"}
   :word-chars word-hyphen
   :lookahead {\= :attr}
   :keywords hcl-kws
   :literals #{"true" "false" "null"}
   :operators #{}
   :punctuation punct-toml
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private makefile-config
  {:line-comments ["#"]
   :strings #{\" \'}
   :word-chars word-hyphen
   :lookahead {\: :attr}
   :specials [{:re #"\$\([A-Za-z0-9_]+\)" :scope :variable :triggers #{\$}}
              {:re #"\$\{[A-Za-z0-9_]+}" :scope :variable :triggers #{\$}}
              {:re #"\$[@<^?*+%|]" :scope :variable :triggers #{\$}}]
   :operators (into #{} "=+")
   :punctuation (into #{} "{}():;,")
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private perl-config
  {:line-comments ["#"]
   :strings #{\" \'}
   :word-chars word-algol
   :keywords perl-kws
   :literals #{"undef"}
   :specials [{:re #"\$\{[^}]*}" :scope :variable :triggers #{\$}}
              {:re #"\$[A-Za-z_][A-Za-z0-9_]*" :scope :variable :triggers #{\$}}
              {:re #"@[A-Za-z_][A-Za-z0-9_]*" :scope :variable :triggers #{\@}}
              {:re #"%[A-Za-z_][A-Za-z0-9_]*" :scope :variable :triggers #{\%}}]
   :operators operators-algol
   :punctuation punct-algol
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private haskell-config
  {:line-comments ["--"]
   :block-comments [["{-" "-}"]]
   :strings #{\"}
   :word-chars word-algol
   :keywords haskell-kws
   :literals #{"True" "False"}
   :operators (into #{} "+-*/=<>:!@~.\\&|^$")
   :punctuation punct-algol
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private elixir-config
  {:line-comments ["#"]
   :strings #{\" \'}
   :word-chars word-qbang
   :keyword-prefixes #{\:}
   :keywords elixir-kws
   :literals #{"true" "false" "nil"}
   :specials [{:re #"@[\w.]+\b" :scope :meta :triggers #{\@}}]
   :operators operators-algol
   :punctuation punct-algol
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private erlang-config
  {:line-comments ["%"]
   :strings #{\"}
   :word-chars word-algol
   :keywords erlang-kws
   :literals #{"true" "false"}
   :specials [{:re #"-[A-Za-z][A-Za-z0-9_]*" :scope :meta :triggers #{\-}}
              {:re #"[A-Z][A-Za-z0-9_]*" :scope :variable :triggers upper-letters}]
   :operators (into #{} "=<>!+-*/|")
   :punctuation (into #{} "(){}[];,")
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private r-config
  {:line-comments ["#"]
   :strings #{\" \'}
   :word-chars word-algol-dot
   :keywords r-kws
   :literals #{"TRUE" "FALSE" "NULL" "Inf" "NaN" "NA" "NA_integer_"
               "NA_real_" "NA_character_" "NA_complex_"}
   :operators (into #{} "=<>+-*/!&|%~^")
   :punctuation punct-algol
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private matlab-config
  {:line-comments ["%"]
   :block-comments [["%{" "%}"]]
   :strings #{\"}
   :word-chars word-algol
   :keywords matlab-kws
   :literals #{"true" "false"}
   :operators (into #{} "=<>+-*/&|~:.^")
   :punctuation (into #{} "()[]{};,")
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private julia-config
  {:line-comments ["#"]
   :strings #{\"}
   :word-chars word-algol
   :keywords julia-kws
   :literals #{"true" "false" "nothing" "missing"}
   :hash {\= [:block-comment "=#"]}
   :hash-default :line-comment
   :operators (into #{} "=<>+-*/%^&|!~.:")
   :punctuation (into #{} "()[]{},;")
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private fortran-config
  {:line-comments ["!"]
   :strings #{\" \'}
   :word-chars word-algol
   :keywords fortran-kws
   :literals #{".true." ".false."}
   :operators (into #{} "=<>+-*/")
   :punctuation (into #{} "()[]{},;:%")
   :case-sensitive? false
   :multiline-strings? false})

(def ^:private fsharp-config
  (assoc algol-base
         :block-comments [["(*" "*)"]]
         :strings #{\"}
         :keywords fsharp-kws
         :literals #{"true" "false" "null"}))

(def ^:private ocaml-config
  {:line-comments []
   :block-comments [["(*" "*)"]]
   :strings #{\"}
   :word-chars word-algol
   :keywords ocaml-kws
   :literals #{"true" "false"}
   :operators operators-algol
   :punctuation punct-algol
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private nim-config
  (assoc algol-base
         :line-comments ["#"]
         :block-comments []
         :keywords nim-kws
         :literals #{"true" "false" "nil"}))

(def ^:private crystal-config
  (assoc algol-base
         :line-comments ["#"]
         :keywords crystal-kws
         :literals #{"true" "false" "nil"}))

(def ^:private vb-config
  {:line-comments ["'"]
   :strings #{\"}
   :word-chars word-algol
   :keywords vb-kws
   :literals #{"true" "false" "nothing"}
   :operators operators-algol
   :punctuation punct-algol
   :case-sensitive? false
   :multiline-strings? false})

(def ^:private cmake-config
  {:line-comments ["#"]
   :strings #{\"}
   :word-chars word-algol
   :lookahead {\( :function}
   :keywords cmake-kws
   :literals #{"true" "false" "on" "off"}
   :operators operators-algol
   :punctuation punct-algol
   :case-sensitive? false
   :multiline-strings? false})

(def ^:private prolog-config
  {:line-comments ["%"]
   :strings #{\"}
   :word-chars word-algol
   :keywords #{"fail" "true" "false" "not" "is" "mod" "div" "rem"}
   :specials [{:re #"[A-Z][A-Za-z0-9_]*" :scope :variable :triggers upper-letters}]
   :operators (into #{} "=<>+-*/\\:?!|")
   :punctuation (into #{} "()[]{},;.")
   :case-sensitive? true
   :multiline-strings? false})

(def ^:private scss-config
  (assoc css-config
         :line-comments ["//"]
         :specials [{:re #"@[\w-]+\b" :scope :meta :triggers #{\@}}
                    {:re #"\$[A-Za-z_][A-Za-z0-9_]*" :scope :variable :triggers #{\$}}]))

(def ^:private less-config
  (assoc css-config
         :line-comments ["//"]
         :specials [{:re #"@(media|import|keyframes|supports|font-face|charset|namespace|page|document|viewport|counter-style|property|layer)" :scope :meta :triggers #{\@}}
                    {:re #"@[\w-]+\b" :scope :variable :triggers #{\@}}]))

(def ^:private stylus-config
  (assoc css-config
         :line-comments ["//"]
         :specials [{:re #"@[\w-]+\b" :scope :meta :triggers #{\@}}
                    {:re #"\$[A-Za-z_][A-Za-z0-9_]*" :scope :variable :triggers #{\$}}]))

(def ^:private fish-config
  (assoc shell-base :keywords fish-kws))

;; ─── Registry ──────────────────────────────────────────────────────────────

(def ^:private languages
  {"clojure" (assoc lisp-base
                    :keywords clojure-kws
                    :literals #{"nil" "true" "false"}
                    :hash {\" :string \_ :line-comment})
   "scheme" (assoc lisp-base
                   :keywords scheme-kws
                   :literals #{"nil"}
                   :hash {\" :string \| [:block-comment "|#"] \; :line-comment})
   "common-lisp" (assoc lisp-base
                        :keywords cl-kws
                        :literals #{"nil" "t"}
                        :hash {\" :string \| [:block-comment "|#"]})
   "elisp" (assoc lisp-base
                  :keywords elisp-kws
                  :literals #{"nil" "t"}
                  :hash {\" :string})
   "edn" (assoc lisp-base
                :literals #{"nil" "true" "false"}
                :hash {\" :string \_ :line-comment})
   "java" (assoc algol-base
                 :keywords java-kws
                 :literals #{"true" "false" "null"}
                 :specials [{:re #"@[\w.]+" :scope :meta :triggers #{\@}}])
   "kotlin" (assoc algol-base
                   :keywords kotlin-kws
                   :literals #{"true" "false" "null"}
                   :specials [{:re #"@[\w.]+" :scope :meta :triggers #{\@}}])
   "javascript" (assoc algol-base :keywords js-kws
                       :literals #{"true" "false" "null" "undefined"})
   "typescript" (assoc algol-base :keywords ts-kws
                       :literals #{"true" "false" "null" "undefined"})
   "c" (assoc algol-base
              :keywords c-kws
              :literals #{"true" "false" "NULL"}
              :specials [{:re #"#\w+" :scope :meta :triggers #{\#}}])
   "cpp" (assoc algol-base
                :keywords cpp-kws
                :literals #{"true" "false" "nullptr" "NULL"}
                :specials [{:re #"#\w+" :scope :meta :triggers #{\#}}])
   "csharp" (assoc algol-base
                   :keywords csharp-kws
                   :literals #{"true" "false" "null"}
                   :specials [{:re #"#\w+" :scope :meta :triggers #{\#}}])
   "go" (assoc algol-base :keywords go-kws :literals #{"true" "false" "nil" "iota"})
   "rust" (assoc algol-base
                 :keywords rust-kws
                 :literals #{"true" "false"}
                 :specials [{:re #"#!?\[" :scope :meta :triggers #{\#}}])
   "swift" (assoc algol-base :keywords swift-kws :literals #{"true" "false" "nil"})
   "dart" (assoc algol-base :keywords dart-kws :literals #{"true" "false" "null"})
   "python" (assoc python-config :keywords python-kws)
   "ruby" (assoc ruby-config :keywords ruby-kws)
   "php" php-config
   "scala" scala-config
   "groovy" groovy-config
   "objective-c" objc-config
   "lua" lua-config
   "powershell" powershell-config
   "dockerfile" dockerfile-config
   "hcl" hcl-config
   "makefile" makefile-config
   "perl" perl-config
   "haskell" haskell-config
   "elixir" elixir-config
   "erlang" erlang-config
   "r" r-config
   "matlab" matlab-config
   "julia" julia-config
   "fortran" fortran-config
   "fsharp" fsharp-config
   "ocaml" ocaml-config
   "zig" (assoc algol-base :keywords zig-kws :literals #{"true" "false" "null" "undefined"})
   "nim" nim-config
   "crystal" crystal-config
   "vb" vb-config
   "cmake" cmake-config
   "prolog" prolog-config
   "scss" scss-config
   "less" less-config
   "stylus" stylus-config
   "fish" fish-config
   "markdown" markdown-config
   "bash" (assoc shell-base :keywords bash-kws)
   "json" json-config
   "yaml" yaml-config
   "toml" toml-config
   "ini" ini-config
   "sql" (assoc sql-config :keywords sql-kws :literals #{"null" "true" "false"})
   "css" css-config
   "html" markup-config
   "xml" markup-config
   "diff" diff-config})

(def ^:private aliases
  {"clj" "clojure" "cljs" "clojure" "bb" "clojure" "lpy" "clojure"
   "lisp" "common-lisp" "cl" "common-lisp" "commonlisp" "common-lisp"
   "emacs-lisp" "elisp" "emacslisp" "elisp" "el" "elisp"
   "objc" "objective-c" "objectivec" "objective-c"
   "phtml" "php"
   "ps1" "powershell"
   "docker" "dockerfile"
   "tf" "hcl" "terraform" "hcl"
   "make" "makefile" "mk" "makefile"
   "hs" "haskell" "lhs" "haskell"
   "ex" "elixir" "exs" "elixir"
   "erl" "erlang" "hrl" "erlang"
   "jl" "julia"
   "f90" "fortran" "f95" "fortran" "f03" "fortran" "f77" "fortran"
   "fs" "fsharp" "fsx" "fsharp" "fsharp" "fsharp" "f#" "fsharp"
   "ml" "ocaml" "mli" "ocaml"
   "vbnet" "vb" "visual-basic" "vb"
   "md" "markdown"
   "sass" "scss"
   "styl" "stylus"
   "gradle" "groovy"
   "pl" "perl"
   "js" "javascript" "jsx" "javascript" "node" "javascript"
   "mjs" "javascript" "cjs" "javascript"
   "ts" "typescript" "tsx" "typescript"
   "c++" "cpp" "cc" "cpp" "cxx" "cpp"
   "c#" "csharp" "cs" "csharp"
   "golang" "go"
   "py" "python"
   "rb" "ruby"
   "sh" "bash" "zsh" "bash" "shell" "bash"
   "yml" "yaml"
   "patch" "diff"
   "kt" "kotlin" "kts" "kotlin"
   "rs" "rust"
   "htm" "html"})

;; ═══════════════════════════════════════════════════════════════════════════
;; Public API
;; ═══════════════════════════════════════════════════════════════════════════

(defn canonical-language
  "Canonical language name for LANG-or-alias, or nil when unsupported. Names are
   trimmed and lower-cased so fences like 'Clojure', 'c++', 'C#' resolve;
   aliases collapse to their canonical key ('clj' → 'clojure')."
  [lang]
  (when (seq lang)
    (let [name (str/lower-case (str/trim lang))]
      (cond
        (contains? languages name) name
        (contains? aliases name) (get aliases name)
        :else nil))))

(defn resolve-language
  "Config for a fence language name/alias, or nil when unsupported. Names are
   trimmed and lower-cased so fences like 'Clojure', 'c++', 'C#' resolve."
  [lang]
  (when-let [name (canonical-language lang)]
    (get languages name)))

(defn supports-language? [lang]
  (boolean (resolve-language lang)))

(defn supported-languages
  "Sorted names of every supported language and alias."
  []
  (sort (concat (keys languages) (keys aliases))))

(defn tokenize
  "Tokenize TEXT as LANG → vector of [scope text] pairs (scope keyword or
   nil). nil when LANG is unsupported."
  [lang text]
  (when-let [cfg (resolve-language lang)]
    ((get cfg :scanner scan-generic) text cfg)))
