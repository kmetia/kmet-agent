(ns kmet.app.yaml
  "Minimal YAML subset parser + frontmatter extraction (pi: the yaml npm
   package + utils/frontmatter.js). Self-contained, babashka-compatible
   (stdlib only).

   Combines the former kmet.libs.yaml-lite (parsing) and
   kmet.libs.frontmatter (--- delimiter extraction) into a single module —
   frontmatter is a thin wrapper over the parser.

   Supports: top-level maps, plain scalars (strings, booleans incl. case
   variants, null, integers, floats), single/double-quoted scalars (with
   escapes), | and > block scalars (chomping -/+, explicit indent digit),
   # comments (outside quotes, preceded by whitespace), lists, nested
   maps/lists via indentation, and compact mappings (a: b: c, - name: v).

   Not supported (documented): flow collections ({...}, [...]), anchors,
   aliases, tags, multi-document streams, multi-line plain scalars without
   a block indicator. Unknown double-quote escapes are kept literally;
   duplicate keys resolve to the last value (pi's yaml throws)."
  (:require [clojure.string :as str]))

;; ─── Line preprocessing ────────────────────────────────────────────────────

(defn- indent-of
  [line]
  (count (take-while #(= \space %) line)))

(defn- strip-comment
  "Remove a trailing # comment: a # preceded by whitespace or line start
   consumes the rest of the line. Quote chars open a quoted scalar only at
   token start (a mid-word ' like it's is literal, so comments still strip)."
  [line]
  (let [n (count line)
        out (volatile! (transient []))]
    (loop [i 0
           q nil
           prev-space true]
      (if (>= i n)
        (str/join (persistent! @out))
        (let [c (nth line i)]
          (cond
            q (do (vswap! out conj! c)
                  (recur (inc i) (if (= c q) nil q) false))
            (and (or (= c \') (= c \")) prev-space)
            (do (vswap! out conj! c) (recur (inc i) c false))
            (= c \#) (if prev-space
                       (str/join (persistent! @out))
                       (do (vswap! out conj! c)
                           (recur (inc i) nil false)))
            :else (do (vswap! out conj! c)
                      (recur (inc i) nil (or (= c \space) (= c \tab))))))))))

(defn- preprocess
  "Split into lines with indentation and blank markers. CRLF normalized.
   :raw keeps the original line (block scalar content includes # etc. as
   literal text); :text has comments stripped for key/value parsing."
  [s]
  (let [lines (str/split (str/replace s #"\r\n|\r" "\n") #"\n" -1)]
    (mapv (fn [line]
            (let [cleaned (strip-comment line)
                  indent (indent-of cleaned)]
              {:indent indent
               :text (subs cleaned indent)
               :raw line
               :blank (str/blank? cleaned)}))
          lines)))

;; ─── Scalars ───────────────────────────────────────────────────────────────

(def ^:private null-words #{"null" "Null" "NULL" "~" ""})
(def ^:private true-words #{"true" "True" "TRUE"})
(def ^:private false-words #{"false" "False" "FALSE"})

(defn- parse-double-quoted
  "Resolve double-quoted YAML escapes. Unknown escapes keep the char
   (lenient; pi's yaml throws)."
  [s]
  (let [n (count s)
        out (volatile! (transient []))
        hex (fn [start len]
              (let [h (subs s start (min n (+ start len)))]
                (try (char (Integer/parseInt h 16))
                     (catch Exception _ \uFFFD))))]
    (loop [i 0]
      (if (>= i n)
        (str/join (persistent! @out))
        (let [c (nth s i)]
          (if (not= c \\)
            (do (vswap! out conj! c) (recur (inc i)))
            (let [e (nth s (inc i))]
              (case e
                \n (do (vswap! out conj! \newline) (recur (+ i 2)))
                \t (do (vswap! out conj! \tab) (recur (+ i 2)))
                \r (do (vswap! out conj! \return) (recur (+ i 2)))
                \0 (do (vswap! out conj! \u0000) (recur (+ i 2)))
                \a (do (vswap! out conj! \u0007) (recur (+ i 2)))
                \b (do (vswap! out conj! \u0008) (recur (+ i 2)))
                \f (do (vswap! out conj! \u000c) (recur (+ i 2)))
                \v (do (vswap! out conj! \u000b) (recur (+ i 2)))
                \e (do (vswap! out conj! \u001b) (recur (+ i 2)))
                \" (do (vswap! out conj! \") (recur (+ i 2)))
                \\ (do (vswap! out conj! \\) (recur (+ i 2)))
                \/ (do (vswap! out conj! \/) (recur (+ i 2)))
                \x (do (vswap! out conj! (hex (+ i 2) 2)) (recur (+ i 4)))
                \u (do (vswap! out conj! (hex (+ i 2) 4)) (recur (+ i 6)))
                (do (vswap! out conj! e) (recur (+ i 2)))))))))))

(defn- parse-single-quoted
  "Single-quoted YAML scalar: '' is the escaped quote."
  [s]
  (str/replace s #"''" "'"))

(defn- parse-plain
  "Resolve a plain (unquoted) scalar per pi's yaml package (YAML 1.2 core
   subset): strings, booleans incl. case variants, null, ints, floats.
   Overflowing ints fall back to the string (parse-long is nil on
   overflow on both hosts)."
  [s]
  (let [s (str/trim s)]
    (cond
      (contains? null-words s) nil
      (contains? true-words s) true
      (contains? false-words s) false
      (re-matches #"[-+]?\d+" s) (or (parse-long s) s)
      (re-matches #"[-+]?(\d+\.\d+[eE][-+]?\d+|\d+\.\d+|\d+[eE][-+]?\d+|\.\d+)" s)
      (or (try (Double/parseDouble s) (catch Exception _ nil)) s)
      :else s)))

(defn- parse-scalar
  "Parse a single-line scalar (quoted or plain)."
  [v]
  (cond
    (and (str/starts-with? v "\"") (str/ends-with? v "\"") (>= (count v) 2))
    (parse-double-quoted (subs v 1 (dec (count v))))
    (and (str/starts-with? v "'") (str/ends-with? v "'") (>= (count v) 2))
    (parse-single-quoted (subs v 1 (dec (count v))))
    :else (parse-plain v)))

;; ─── Key/value structure ───────────────────────────────────────────────────

(defn- unquote-key
  "Strip matching quotes from a key. Keys stay strings (pi JS objects have
   string keys: 123 as a key yields the string 123)."
  [k]
  (let [k (str/trim k)]
    (cond
      (and (str/starts-with? k "\"") (str/ends-with? k "\"") (>= (count k) 2))
      (parse-double-quoted (subs k 1 (dec (count k))))
      (and (str/starts-with? k "'") (str/ends-with? k "'") (>= (count k) 2))
      (parse-single-quoted (subs k 1 (dec (count k))))
      :else k)))

(defn- parse-key-line
  "If text is a mapping entry 'key: value', return [key parsed-value]. A
   colon starts a key separator only when followed by whitespace or EOL, so
   URLs (http://x) and other colon-containing values are not split. Keys are
   unquoted but stay strings (pi JS object keys)."
  [text]
  (when-let [[_ k v] (re-matches #"([^:]+?):(?=\s|$)([\s\S]*)" text)]
    [(unquote-key k) (str/trim v)]))

(defn- inline-value?
  "True when v is a compact nested mapping (a: b) rather than a quoted
   scalar or block scalar. Used to decide map-vs-scalar for values."
  [v]
  (and (not (str/starts-with? v "\""))
       (not (str/starts-with? v "'"))
       (parse-key-line v)))

(defn- parse-inline
  "Parse an inline scalar or compact nested mapping (a: b: c)."
  [v]
  (if-let [[k v2] (inline-value? v)]
    {k (parse-inline v2)}
    (parse-scalar v)))

;; ─── Block scalars (| and >) ──────────────────────────────────────────────

(defn- fold-block
  "Fold > block scalar lines: line breaks between non-empty lines become
   spaces; empty lines become newlines."
  [lines]
  (let [parts (volatile! [])]
    (loop [ls lines
           run []]
      (if-let [l (first ls)]
        (if (str/blank? l)
          (do (when (seq run) (vswap! parts conj (str/join " " run)))
              (vswap! parts conj "\n")
              (recur (rest ls) []))
          (recur (rest ls) (conj run l)))
        (do (when (seq run) (vswap! parts conj (str/join " " run)))
            (str/join "" @parts))))))

(defn- build-block
  "Build the block scalar string from collected lines (each
   {:blank bool :indent n :raw line})."
  [style chomp explicit key-indent collected]
  (let [non-blank (remove :blank collected)
        base (or (some-> explicit (+ key-indent))
                 (when (seq non-blank) (apply min (map :indent non-blank))))
        lines (mapv (fn [{:keys [blank raw]}]
                      (if blank "" (subs raw (min base (count raw)))))
                    collected)
        lines (drop-while str/blank? lines)
        raw-content (str/join "\n" lines)
        content (if (= style \>) (fold-block lines) raw-content)
        content (case chomp
                  :strip (str/replace content #"\n+$" "")
                  :clip (if (empty? content)
                          ""
                          (str (str/replace content #"\n+$" "") "\n"))
                  :keep content)]
    content))

(defn- parse-block-scalar
  "Parse a | or > block scalar. v is the indicator (e.g. |, |-, |2, >+).
   Returns [string remaining-lines]."
  [v ls key-indent]
  (let [style (first v)
        chomp (cond (str/includes? v "-") :strip
                    (str/includes? v "+") :keep
                    :else :clip)
        explicit (when-let [d (re-find #"\d+" v)] (parse-long d))]
    (loop [ls ls
           collected []]
      (if-let [{:keys [indent raw blank]} (first ls)]
        (if (or blank (> indent key-indent))
          (recur (rest ls) (conj collected {:blank blank :indent indent :raw raw}))
          [(build-block style chomp explicit key-indent collected) ls])
        [(build-block style chomp explicit key-indent collected) nil]))))

;; ─── Block parsing ─────────────────────────────────────────────────────────

(declare parse-block parse-list)

(defn- parse-node
  "Parse a value after 'key:' or '- ' (v = trimmed remainder). indent = the
   key's indentation (for nested-block detection). Returns
   [node remaining-lines]."
  [v ls indent]
  (cond
    (re-matches #"[|>][0-9]*[-+]?" v)
    (parse-block-scalar v ls indent)

    (str/blank? v)
    (let [nl (first (drop-while :blank ls))]
      (if (and nl (> (:indent nl) indent))
        (parse-block ls)
        [nil ls]))

    :else [(parse-scalar v) ls]))

(defn- parse-map
  "Parse mapping entries at the given indent. Returns [map remaining-lines]."
  [ls block-indent]
  (loop [ls ls
         m (transient {})]
    (if-let [line (first ls)]
      (let [{:keys [indent text blank]} line]
        (cond
          blank (recur (rest ls) m)
          (< indent block-indent) [(persistent! m) ls]
          :else
          (if-let [[k v] (parse-key-line text)]
            (let [[node rem] (if (inline-value? v)
                               [(parse-inline v) (rest ls)]
                               (parse-node v (rest ls) block-indent))]
              (recur rem (assoc! m k node)))
            ;; stray non-entry line at this depth: skip (unsupported construct)
            (recur (rest ls) m))))
      [(persistent! m) nil])))

(defn- parse-list-item
  "Parse one list item from text (content after '- ') and remaining lines.
   Returns [node remaining-lines]."
  [text ls indent]
  (if (inline-value? text)
    ;; compact mapping item (- name: v): continues as a map at the item
    ;; content indent
    (parse-map (cons {:indent (+ indent 2) :text text} ls) (+ indent 2))
    (if (str/starts-with? text "-")
      ;; nested list item (- - x)
      (parse-list (cons {:indent (+ indent 2) :text text} ls) (+ indent 2))
      ;; scalar, block scalar, or nested block
      (parse-node text ls indent))))

(defn- parse-list
  "Parse list items at the given indent. Returns [vector remaining-lines]."
  [ls block-indent]
  (loop [ls ls
         v (transient [])]
    (if-let [line (first ls)]
      (let [{:keys [indent text blank]} line]
        (cond
          blank (recur (rest ls) v)
          (< indent block-indent) [(persistent! v) ls]
          (str/starts-with? (str/triml text) "-")
          (let [rest-text (str/triml (subs text 1))]
            (if (str/blank? rest-text)
              ;; "-" alone: nested block or nil item
              (let [nl (first (drop-while :blank (rest ls)))]
                (if (and nl (> (:indent nl) block-indent))
                  (let [[node rem] (parse-block (rest ls))]
                    (recur rem (conj! v node)))
                  (recur (rest ls) (conj! v nil))))
              (let [[node rem] (parse-list-item rest-text (rest ls) block-indent)]
                (recur rem (conj! v node)))))
          :else [(persistent! v) ls]))
      [(persistent! v) nil])))

(defn- parse-block
  "Parse a YAML block (map or list) starting at the first non-blank line,
   skipping leading document markers (---). Returns [node remaining-lines]."
  [ls]
  (let [ls (drop-while #(or (:blank %) (= "---" (:text %))) ls)]
    (if-let [nl (first ls)]
      (if (str/starts-with? (:text nl) "-")
        (parse-list ls (:indent nl))
        (parse-map ls (:indent nl)))
      [nil nil])))

;; ─── Entry points ──────────────────────────────────────────────────────────

(defn parse
  "Parse a YAML string into Clojure data: a map with string keys for a
   mapping, a vector for a list, or the top-level scalar. Returns nil for
   empty/blank input."
  [s]
  (when (and s (not (str/blank? s)))
    (first (parse-block (preprocess s)))))

;; ─── Frontmatter extraction ────────────────────────────────────────────────

(defn parse-frontmatter
  "Parse YAML frontmatter (--- delimited) from markdown content.
   Returns {:frontmatter map :body string}. Content without frontmatter yields
   an empty frontmatter map and the full content as body (pi: extractFrontmatter)."
  [content]
  (let [normalized (str/replace content #"\r\n|\r" "\n")]
    (if-not (str/starts-with? normalized "---")
      {:frontmatter {} :body normalized}
      (let [end-idx (str/index-of normalized "\n---" 3)]
        (if (nil? end-idx)
          {:frontmatter {} :body normalized}
          ;; max clamps for empty frontmatter (---\n---): pi's slice returns ""
          {:frontmatter (or (parse (subs normalized 4 (max 4 end-idx))) {})
           :body (str/trim (subs normalized (+ end-idx 4)))})))))