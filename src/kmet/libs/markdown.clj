(ns kmet.libs.markdown
  "Pure Markdown tokenizer: text -> AST.

   Self-contained (stdlib only, no kmet.* deps, no ANSI, no width). The TUI
   renderer (kmet.tui.components.markdown) walks the AST and applies theme,
   padding, and word-wrap. Port of the parsing half of @earendil-works/pi-tui
   Markdown (which uses `marked`); this is a line-oriented CommonMark subset
   with GFM tables and fenced code blocks (including inside list items)."
  (:require [clojure.string :as str]))

;; ─── Line type detection ───────────────────────────────────────────────────

(def ^:private heading-re #"^(#{1,6})\s+(.*)$")
(def ^:private code-fence-re #"^```\s*(\S*)$")
(def ^:private quote-re #"^>\s?(.*)$")
(def ^:private ul-re #"^[\s]*[-*+]\s+(.*)$")
(def ^:private ol-re #"^[\s]*\d+\.\s+(.*)$")
(def ^:private hr-re #"^([-*_])(?:[ \t]*\1){2,}$")
(def ^:private empty-re #"^\s*$")
(def ^:private sep-cell-re #":?-+:?")
(def ^:private escaped-pipe-re #"\\\|")

;; ─── Inline tokenizer ──────────────────────────────────────────────────────

(def ^:private angle-url-re
  #"^<([A-Za-z][A-Za-z0-9+.-]{1,31}:[^\s<>]*)>")
(def ^:private angle-email-re
  #"^<([A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+)>")
(def ^:private bare-url-re
  #"(?i)^(?:(?:https?|ftp)://|www\.)(?:[A-Za-z0-9-]+\.?)+[^\s<]*")
(def ^:private bare-email-re
  #"^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+")

(def ^:private bare-email-start-chars
  "First-char set gating the bare-email autolink regex in parse-inline.
   The regex's local part ([A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+) must match at
   position 0 for the match to succeed, so any other first char fails fast.
   Perf gate only (jolt's irregex is ~25x bb on a miss) — the regex still
   decides; a char outside this set can never start a match."
  #{\A \B \C \D \E \F \G \H \I \J \K \L \M \N \O \P \Q \R \S \T \U \V \W \X \Y \Z
    \a \b \c \d \e \f \g \h \i \j \k \l \m \n \o \p \q \r \s \t \u \v \w \x \y \z
    \0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \. \! \# \$ \% \& \' \* \+ \/ \= \? \^ \_ \` \{ \| \} \~ \-})

(def ^:private bare-url-start-chars
  "First-char set gating the bare-url autolink regex in parse-inline.
   The regex is case-insensitive ((?i)) over the schemes http://, https://,
   ftp:// and www. — every alternative starts with h/H, f/F or w/W, so any
   other first char fails fast. Perf gate only — the regex still decides."
  #{\h \H \f \F \w \W})

(def ^:private markdown-escape-re
  #"[!\"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~]")

(defn- escaped-punctuation?
  [c]
  (boolean (re-matches markdown-escape-re (str c))))

(defn- unescape-markdown
  [s]
  (loop [i 0, result []]
    (if (>= i (count s))
      (apply str result)
      (if (and (= (char 92) (nth s i))
               (< (inc i) (count s))
               (escaped-punctuation? (nth s (inc i))))
        (recur (+ i 2) (conj result (nth s (inc i))))
        (recur (inc i) (conj result (nth s i)))))))

(defn- find-unescaped
  [s start target]
  (loop [i start]
    (cond
      (>= i (count s)) nil
      (= (char 92) (nth s i)) (recur (min (count s) (+ i 2)))
      (= target (nth s i)) i
      :else (recur (inc i)))))

(defn- find-label-end
  [s start]
  (loop [i (inc start), depth 1]
    (cond
      (>= i (count s)) nil
      (= (char 92) (nth s i)) (recur (min (count s) (+ i 2)) depth)
      (= (char 96) (nth s i))
      (if-let [end (find-unescaped s (inc i) (char 96))]
        (recur (inc end) depth)
        nil)
      (= (char 91) (nth s i)) (recur (inc i) (inc depth))
      (= (char 93) (nth s i)) (if (= depth 1) i (recur (inc i) (dec depth)))
      :else (recur (inc i) depth))))

(defn- whitespace?
  [c]
  (boolean (and c (re-matches #"\s" (str c)))))

(defn- skip-whitespace
  [s start]
  (loop [i start]
    (if (and (< i (count s)) (whitespace? (nth s i)))
      (recur (inc i))
      i)))

(defn- find-title-end
  [s start]
  (when (< start (count s))
    (let [delimiter (nth s start)]
      (cond
        (or (= delimiter (char 34)) (= delimiter (char 39)))
        (some-> (find-unescaped s (inc start) delimiter) inc)

        (= delimiter (char 40))
        (loop [i (inc start), depth 1]
          (cond
            (>= i (count s)) nil
            (= (char 92) (nth s i)) (recur (min (count s) (+ i 2)) depth)
            (= (char 40) (nth s i)) (recur (inc i) (inc depth))
            (= (char 41) (nth s i))
            (if (= depth 1) (inc i) (recur (inc i) (dec depth)))
            :else (recur (inc i) depth)))

        :else nil))))

(defn- find-destination-end
  [s url-end]
  (let [after-url (skip-whitespace s url-end)]
    (cond
      (and (< after-url (count s)) (= (char 41) (nth s after-url)))
      (inc after-url)

      (= after-url url-end)
      nil

      :else
      (when-let [title-end (find-title-end s after-url)]
        (let [close (skip-whitespace s title-end)]
          (when (and (< close (count s)) (= (char 41) (nth s close)))
            (inc close)))))))

(defn- parse-destination
  [s open-paren]
  (let [start (skip-whitespace s (inc open-paren))]
    (if (and (< start (count s)) (= (char 60) (nth s start)))
      (when-let [close-angle (find-unescaped s (inc start) (char 62))]
        (when-let [end (find-destination-end s (inc close-angle))]
          {:url (unescape-markdown (subs s (inc start) close-angle))
           :end end}))
      (loop [i start, depth 0]
        (cond
          (>= i (count s)) nil
          (= (char 92) (nth s i)) (recur (min (count s) (+ i 2)) depth)
          (and (zero? depth) (whitespace? (nth s i)))
          (when-let [end (find-destination-end s i)]
            {:url (unescape-markdown (subs s start i)) :end end})
          (= (char 40) (nth s i)) (recur (inc i) (inc depth))
          (= (char 41) (nth s i))
          (if (zero? depth)
            {:url (unescape-markdown (subs s start i)) :end (inc i)}
            (recur (inc i) (dec depth)))
          :else (recur (inc i) depth))))))

(declare parse-inline)

(defn- link-token
  [label url]
  {:type :link :url url :text [{:type :text :s label}]})

(defn- explicit-link
  [line start]
  (when-let [label-end (find-label-end line start)]
    (let [open (inc label-end)]
      (when (and (< open (count line)) (= (char 40) (nth line open)))
        (when-let [{:keys [url end]} (parse-destination line open)]
          (let [raw (subs line (inc start) label-end)]
            {:token (with-meta (assoc (link-token (unescape-markdown raw) url)
                                      :text (parse-inline raw true))
                      {:raw-label raw})
             :end end}))))))

(defn- flush-text
  "Append the pending :text run buffered in SB (a StringBuilder, emptied here)
   to the transient vector inside OUT (a volatile)."
  [out sb]
  (when (pos? (.length ^StringBuilder sb))
    (vswap! out conj! {:type :text :s (str sb)})
    (.setLength ^StringBuilder sb 0))
  out)

(defn- push-token
  "Flush the pending text run, then conj TOK onto the output."
  [out sb tok]
  (flush-text out sb)
  (vswap! out conj! tok)
  out)

(defn parse-inline
  "Parse inline Markdown into pure-data tokens. The one-argument form parses
   ordinary text; the private second argument disables nested links/autolinks
   while parsing an explicit link label, matching Marked's inLink state.

   Accumulation (jolt perf): OUT is a transient vector behind a volatile and
   SB buffers the pending :text run, so a plain char is one StringBuilder
   append instead of a per-char token + peek/pop/conj merge (~3x on bb,
   ~1.4x on jolt for prose lines). Every branch ends in (recur (inc i)) or
   (recur next-i) — a single loop arg, so SCI never has to reason about
   tail position of a multi-arg recur inside if-let.

   Per-iteration cost is the whole game here (~1.5 us/char on bb, measured):
   the loop reads a Character with (nth line i) and compares characters —
   never building a 1-char string; the `remaining' substring is built only
   in the branches that feed it to a regex; the closing-delimiter searches
   run str/index-of on LINE with an absolute start index (equivalent offsets
   to searching the remainder); the bare-email regex is reachable only when
   the line holds an @ at all (an @ before I fails the anchored regex
   anyway). Each of those was 0.2-0.6 us per character."
  ([line]
   (parse-inline line false))
  ([line in-link?]
   (if (or (empty? line) (nil? line))
     []
     (let [n (count line)
           at? (some? (str/index-of line "@"))
           out (volatile! (transient []))
           sb (StringBuilder.)]
       (loop [i 0]
         (if (>= i n)
           (do (flush-text out sb)
               (persistent! @out))
           (let [ch (nth line i)]
             (cond
               (and (not in-link?) (= ch \<))
               (let [remaining (subs line i)]
                 (if-let [[raw target] (re-find angle-url-re remaining)]
                   (do (push-token out sb (link-token target target))
                       (recur (+ i (count raw))))
                   (if-let [[raw email] (re-find angle-email-re remaining)]
                     (do (push-token out sb (link-token email (str "mailto:" email)))
                         (recur (+ i (count raw))))
                     (do (.append sb ch) (recur (inc i))))))

               (and (not in-link?) (= ch \[))
               (if-let [{:keys [token end]} (explicit-link line i)]
                 (do (push-token out sb token)
                     (recur end))
                 (do (.append sb ch) (recur (inc i))))

               (= ch \\)
               (if (and (< (inc i) n) (escaped-punctuation? (nth line (inc i))))
                 (do (.append sb ^String (str (nth line (inc i))))
                     (recur (+ i 2)))
                 (do (.append sb ch) (recur (inc i))))

               (= ch \`)
               (let [end (str/index-of line "`" (inc i))]
                 (if end
                   (do (push-token out sb {:type :code
                                           :s (subs line (inc i) end)})
                       (recur (inc end)))
                   (do (.append sb ch) (recur (inc i)))))

               (and (= ch \*) (< (inc i) n) (= (nth line (inc i)) \*))
               (let [end (str/index-of line "**" (+ i 2))]
                 (if (and end (> end (+ i 2)))
                   (do (push-token out sb {:type :strong
                                           :content (parse-inline
                                                     (subs line (+ i 2) end)
                                                     in-link?)})
                       (recur (+ end 2)))
                   (do (.append sb ch) (recur (inc i)))))

               (= ch \*)
               (let [end (str/index-of line "*" (inc i))]
                 (if (and end (> end (inc i)))
                   (do (push-token out sb {:type :em
                                           :content (parse-inline
                                                     (subs line (inc i) end)
                                                     in-link?)})
                       (recur (inc end)))
                   (do (.append sb ch) (recur (inc i)))))

               (and (= ch \~)
                    (>= n (+ i 2))
                    (= (nth line (inc i)) \~))
               (let [end (str/index-of line "~~" (+ i 2))]
                 (if (and end (> end (+ i 2)))
                   (do (push-token out sb {:type :del
                                           :content (parse-inline
                                                     (subs line (+ i 2) end)
                                                     in-link?)})
                       (recur (+ end 2)))
                   (do (.append sb ch) (recur (inc i)))))

               (not in-link?)
               ;; Perf gates (jolt irregex is ~25x bb on a miss): the email
               ;; regex only runs when the line holds an @ anywhere and the
               ;; first char can start its local part; the url regex only
               ;; runs on h/H/f/F/w/W (all its schemes' first chars, (?i)).
               ;; Both gates fail only calls the regex would.
               (if-let [matched (or (when (and at? (contains? bare-email-start-chars ch))
                                      (re-find bare-email-re (subs line i)))
                                    (when (contains? bare-url-start-chars ch)
                                      (re-find bare-url-re (subs line i))))]
                 (let [raw (loop [s matched]
                             (if (or (re-find #"[!?.,:;*_~]$" s)
                                     (and (str/ends-with? s ")")
                                          (> (count (re-seq #"\)" s))
                                             (count (re-seq #"\(" s)))))
                               (recur (subs s 0 (dec (count s))))
                               s))
                       lower (str/lower-case raw)
                       url (if (str/includes? raw "@")
                             (str "mailto:" raw)
                             (if (str/starts-with? lower "www.")
                               (str "http://" raw)
                               raw))
                       label (if (str/starts-with? url "mailto:")
                               (subs url (count "mailto:"))
                               raw)]
                   (push-token out sb (with-meta (link-token label url)
                                        {:raw-label raw}))
                   (recur (+ i (count raw))))
                 (do (.append sb ch) (recur (inc i))))

               :else
               (do (.append sb ch) (recur (inc i)))))))))))

;; ─── List nesting state ──────────────────────────────────────────────────────
;; Lists are built on an indent-based stack: each entry is {:indent n
;; :list {:type :ul/:ol :items [...]}}. Deeper indentation opens a nested list
;; under the innermost list's last item; equal indentation appends a sibling
;; item; shallower indentation pops the stack. Blank lines become :blank
;; pseudo-items (loose lists stay one token); indented non-list lines become
;; additional :content lines of the last real item (continuation); indented
;; code fences become :blocks of the last real item.

(defn- leading-indent
  "Width of LINE's leading whitespace (tab = 4 columns, CommonMark-ish)."
  [line]
  (loop [i 0, w 0]
    (if (>= i (count line))
      w
      (case (nth line i)
        \space (recur (inc i) (inc w))
        \tab (recur (inc i) (+ w 4))
        w))))

(defn- strip-indent
  "Remove up to INDENT columns of leading whitespace from LINE (tab = 4)."
  [indent line]
  (loop [i 0, w 0]
    (if (or (>= i (count line)) (>= w indent))
      (subs line i)
      (case (nth line i)
        \space (recur (inc i) (inc w))
        \tab (recur (inc i) (+ w 4))
        (subs line i)))))

(defn- close-list!
  "Pop the innermost list from STACK: attach it to the parent list's last
   item (as a :blocks entry) when a parent exists, else conj it onto RESULT."
  [stack result]
  (let [list-tok (:list (peek @stack))]
    (vswap! stack pop)
    (if (seq @stack)
      (let [parent (peek @stack)
            parent-list (:list parent)
            idx (dec (count (:items parent-list)))
            parent-pos (dec (count @stack))]
        (vswap! stack update-in [parent-pos :list :items idx :blocks]
                (fnil conj []) list-tok))
      (vswap! result conj list-tok))))

(defn- close-lists-above!
  "Close lists whose indent is strictly greater than INDENT."
  [stack result indent]
  (loop []
    (when (and (seq @stack) (> (:indent (peek @stack)) indent))
      (close-list! stack result)
      (recur))))

(defn- close-all-lists!
  "Close every open list onto RESULT (non-list content or end of input)."
  [stack result]
  (loop []
    (when (seq @stack)
      (close-list! stack result)
      (recur))))

(defn- list-type
  ":ul or :ol for a TRIMmed list line."
  [trimmed]
  (if (re-matches ul-re trimmed) :ul :ol))

(defn- make-item
  "A list item holding one content line's inline tokens."
  [inline-tokens]
  {:content [inline-tokens]})

(defn- innermost-list
  "The list token being built on top of the STACK."
  [stack]
  (:list (peek @stack)))

(defn- last-real-item-idx
  "Index of the last non-:blank item in the innermost list, or nil."
  [stack]
  (let [items (:items (innermost-list stack))]
    (loop [i (dec (count items))]
      (cond
        (neg? i) nil
        (= :blank (:type (nth items i))) (recur (dec i))
        :else i))))

(defn- attach-block!
  "Attach BLOCK (a nested list or :code token) to the innermost list's last
   real item's :blocks."
  [stack block]
  (when-let [idx (last-real-item-idx stack)]
    (vswap! stack update-in [(dec (count @stack)) :list :items idx :blocks]
            (fnil conj []) block)))

(defn- add-list-line!
  "Fold one list source line into the STACK: sibling item, nested list, or a
   new root list depending on INDENT. RESULT receives closed root lists.
   A marker-type change at the current indent closes the list first, so the
   new list starts fresh (CommonMark: marker change starts a new list)."
  [stack result indent trimmed]
  (close-lists-above! stack result indent)
  (let [type (list-type trimmed)
        [_ content] (re-find (if (= type :ul) ul-re ol-re) trimmed)
        item (make-item (parse-inline content))
        top-indent (:indent (peek @stack))
        top-type (:type (innermost-list stack))]
    (cond
      (empty? @stack)
      (vswap! stack conj {:indent indent :list {:type type :items [item]}})

      (= top-indent indent)
      (if (= type top-type)
        ;; same level, same type: append a sibling item
        (vswap! stack update-in [(dec (count @stack)) :list :items] conj item)
        ;; same level, marker-type change: close this list, start a new one
        (do (close-list! stack result)
            (vswap! stack conj {:indent indent :list {:type type :items [item]}})))

      :else
      ;; deeper: open a nested list under the innermost list's last real item
      (vswap! stack conj {:indent indent :list {:type type :items [item]}}))))

(defn- add-blank-line!
  "A blank line inside an open list becomes a :blank pseudo-item; with no
   open list it is a root :blank block token."
  [stack result]
  (if (seq @stack)
    (vswap! stack update-in [(dec (count @stack)) :list :items]
            conj {:type :blank})
    (vswap! result conj {:type :blank})))

(defn- add-continuation!
  "An indented non-list line continues the innermost list's last real item:
   its inline tokens (leading whitespace stripped — the renderer supplies
   alignment) are appended to the item's :content as a new line."
  [stack line]
  (when-let [idx (last-real-item-idx stack)]
    (vswap! stack update-in [(dec (count @stack)) :list :items idx :content]
            conj (parse-inline (str/triml line)))))

;; ─── Tables ─────────────────────────────────────────────────────────────────

(defn- split-table-row
  "Split a GFM table row into raw cell strings: unescaped pipes separate
   cells, outer pipes are dropped, cells trimmed, \\| unescaped."
  [line]
  (let [cells (mapv str/trim (str/split line #"(?<!\\)\|" -1))]
    (-> cells
        (cond-> (str/starts-with? line "|") rest
                (str/ends-with? line "|") butlast)
        (->> (mapv #(str/replace % escaped-pipe-re "|"))))))

(defn- table-row?
  "A GFM table data row: contains a pipe after trimming."
  [line]
  (str/includes? (str/trim line) "|"))

(defn- table-separator?
  "A GFM table separator row: every cell is dashes with optional colons."
  [cells]
  (and (seq cells) (every? #(re-matches sep-cell-re %) cells)))

(defn- cell-align
  "Alignment from a separator cell: :left/:right/:center, or nil."
  [s]
  (let [t (str/trim s)]
    (cond
      (and (str/starts-with? t ":") (str/ends-with? t ":")) :center
      (str/starts-with? t ":") :left
      (str/ends-with? t ":") :right
      :else nil)))

(defn- parse-table!
  "Parse the GFM table whose header is LINES[I] and separator LINES[I+1].
   Appends the :table token to RESULT; returns the number of lines consumed.
   Columns are padded to the header count; short rows get empty cells."
  [lines i result]
  (let [header-cells (split-table-row (nth lines i))
        sep-cells (split-table-row (nth lines (inc i)))
        n (count header-cells)
        j (loop [j (+ i 2)]
            (if (and (< j (count lines)) (table-row? (nth lines j)))
              (recur (inc j))
              j))
        cell-tokens (fn [line]
                      (mapv parse-inline
                            (take n (concat (split-table-row line) (repeat "")))))]
    (vswap! result conj
            {:type :table
             :header (cell-tokens (nth lines i))
             :rows (mapv cell-tokens (subvec lines (+ i 2) j))
             :aligns (mapv cell-align (take n (concat sep-cells (repeat ""))))
             :raw (str/join "\n" (subvec lines i j))})
    (- j i)))

;; ─── Block tokenizer ────────────────────────────────────────────────────────

(defn parse
  "Parse Markdown TEXT into a vector of block tokens (the AST).

   Block tokens:
     {:type :blank}                          whitespace-only line
     {:type :hr}                             --- / ___ / ***
     {:type :heading :level 1..6 :content [inline]}
     {:type :paragraph :content [inline]}
     {:type :code :lang \"\" :text \"raw\"}   fenced code (interior raw lines, \\n-joined)
     {:type :table :header [cell ...] :rows [[cell ...] ...] :aligns [...] :raw \"...\"}
                                             GFM table; cells are inline-token
                                             vectors; :aligns holds :left/:right/
                                             :center/nil per column; :raw is the
                                             source lines (narrow-width fallback)
     {:type :ul :items [item ...]}           one item per source line;
     {:type :ol :items [item ...]}             consecutive same-indent lines group
     {:type :quote :content [inline]}

   List items are maps: {:content [[inline] ...] :blocks [<block> ...]}.
   :content holds the item's lines (first line gets the bullet, the rest are
   continuations); :blocks holds nested :ul/:ol/:code tokens (indent-deeper
   content). A :blank pseudo-item renders an empty line inside the list.

   Inline tokens are produced by parse-inline: :text/:strong/:em/:del/:code/:link.
   All tokens are plain data — no ANSI styling, no terminal-width dependence."
  [text]
  (let [lines (str/split-lines (or text ""))
        result (volatile! [])
        code-state (volatile! nil)
        stack (volatile! [])]
    (loop [i 0]
      (when (< i (count lines))
        (let [line (nth lines i)
              trimmed (str/trim line)
              indent (leading-indent line)
              next-i (cond
                       ;; Inside a code block: accumulate raw lines (leading
                       ;; whitespace up to the fence indent stripped) until the
                       ;; closing fence, then emit (or attach to a list item).
                       @code-state
                       (if (re-matches code-fence-re trimmed)
                         (let [code-tok {:type :code
                                         :lang (:lang @code-state)
                                         :text (:text @code-state)}]
                           (if (:in-item? @code-state)
                             (attach-block! stack code-tok)
                             (vswap! result conj code-tok))
                           (vreset! code-state nil)
                           (inc i))
                         (let [l (strip-indent (:indent @code-state) line)]
                           (if (:started? @code-state)
                             (vswap! code-state update :text str (str "\n" l))
                             (vswap! code-state assoc :text l :started? true))
                           (inc i)))

                       ;; Code fence open: inside a list item when indented
                       ;; deeper than the innermost list
                       (re-matches code-fence-re trimmed)
                       (do (if (and (seq @stack) (> indent (:indent (peek @stack))))
                             (vreset! code-state {:lang (or (second (re-find code-fence-re trimmed)) "")
                                                  :text "" :started? false :indent indent :in-item? true})
                             (do (close-all-lists! stack result)
                                 (vreset! code-state {:lang (or (second (re-find code-fence-re trimmed)) "")
                                                      :text "" :started? false :indent indent})))
                           (inc i))

                       (re-matches hr-re trimmed)
                       (do (close-all-lists! stack result)
                           (vswap! result conj {:type :hr})
                           (inc i))

                       (re-matches heading-re trimmed)
                       (do (close-all-lists! stack result)
                           (let [[_ level-str content] (re-find heading-re trimmed)]
                             (vswap! result conj {:type :heading :level (count level-str)
                                                  :content (parse-inline content)}))
                           (inc i))

                       (re-matches quote-re trimmed)
                       (do (close-all-lists! stack result)
                           (let [[_ content] (re-find quote-re trimmed)]
                             (vswap! result conj {:type :quote :content (parse-inline content)}))
                           (inc i))

                       ;; List line (before tables: a pipe in list content must
                       ;; not turn the item into a table header)
                       (or (re-matches ul-re trimmed) (re-matches ol-re trimmed))
                       (do (add-list-line! stack result indent trimmed)
                           (inc i))

                       ;; GFM table: a pipe row immediately followed by a
                       ;; separator row
                       (and (table-row? line)
                            (seq (split-table-row line))
                            (table-separator? (split-table-row (nth lines (inc i) ""))))
                       (do (close-all-lists! stack result)
                           (+ i (parse-table! lines i result)))

                       (re-matches empty-re line)
                       (do (add-blank-line! stack result)
                           (inc i))

                       ;; Indented continuation of the innermost list item
                       (and (seq @stack) (> indent (:indent (peek @stack))))
                       (do (add-continuation! stack line)
                           (inc i))

                       :else
                       (do (close-all-lists! stack result)
                           (vswap! result conj {:type :paragraph :content (parse-inline line)})
                           (inc i)))]
          (recur next-i))))
    ;; Flush an unclosed code block at end of input (attach when inside a list)
    (when-let [cs @code-state]
      (let [code-tok {:type :code :lang (:lang cs) :text (:text cs)}]
        (if (:in-item? cs)
          (attach-block! stack code-tok)
          (vswap! result conj code-tok))))
    (close-all-lists! stack result)
    @result))
