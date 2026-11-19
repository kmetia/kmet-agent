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
(def ^:private ol-marker-re #"^\d+\.")
(def ^:private hr-re #"^([-*_])(?:[ \t]*\1){2,}$")
(def ^:private empty-re #"^\s*$")
(def ^:private sep-cell-re #":?-+:?")
(def ^:private escaped-pipe-re #"\\\|")

(def ^:private marker-first-chars
  "First characters (of a trimmed line) that can start a thematic break or a
   list item: the hr/ul markers plus the ordered-list digits. Necessary-prefix
   gate for the hr/ul/ol regexes on the block path; a plain line skips all
   three regexes with one set lookup."
  #{\- \* \+ \_ \0 \1 \2 \3 \4 \5 \6 \7 \8 \9})

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

(def ^:private inline-special-re
  "Characters that stop a plain run in parse-inline's chunked fast path: the
   markup openers, plus the literal bare-url schemes (so the regex engine
   finds real candidates — stopping at every h/f/w in ordinary prose costs
   ~1 us per stop on bb, more than the per-char loop it replaces). A match
   here is either a hard special or a URL whose remaining scheme/domain the
   dispatch still validates with bare-url-re."
  #"(?i)https?://|ftp://|www\.|[\\`*~<\[]")

(def ^:private inline-special-in-link-re
  "In-link? variant of INLINE-SPECIAL-RE: < and [ are plain text inside a link
   label, and autolinks are disabled there, so only the markup openers stop a
   run."
  #"[\\`*~]")

(def ^:private bare-url-prefixes
  "Scheme prefixes (after the first char) gating the bare-url autolink regex
   in parse-inline: a URL can only start where the remaining characters begin
   case-insensitively with one of these. Perf gate only — the regex still
   decides. The old first-char set (h/H/f/F/w/W) fired on every h/f/w in
   ordinary prose, and each attempt paid a (subs line i) copy plus an irregex
   miss; checking the literal scheme first keeps the regex off ordinary words.
   h/H lists both spellings (https?://)."
  {\h ["ttp://" "ttps://"] \H ["ttp://" "ttps://"]
   \f ["tp://"] \F ["tp://"]
   \w ["ww."] \W ["ww."]})

(def ^:private ascii-lower->upper
  (zipmap "abcdefghijklmnopqrstuvwxyz" "ABCDEFGHIJKLMNOPQRSTUVWXYZ"))

(defn- ci-prefix-at?
  "True when LINE[I...] starts with the lowercase ASCII PREFIX, ignoring case,
   without allocating a substring."
  [line i ^String prefix]
  (let [pn (count prefix)
        n (count line)]
    (and (<= (+ i pn) n)
         (loop [k 0]
           (if (= k pn)
             true
             (let [c (nth line (+ i k))
                   e (nth prefix k)]
               (if (or (= c e) (= c (ascii-lower->upper e)))
                 (recur (inc k))
                 false)))))))

(defn- ci-prefix-any-at?
  "True when LINE[I...] starts with any of the lowercase ASCII PREFIXES,
   ignoring case."
  [line i prefixes]
  (boolean (some #(ci-prefix-at? line i %) prefixes)))

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

(defn- matcher-next
  "Start index of the first matcher match at or after FROM, or nil when the
   matcher has no match left. The matcher is stateful: matches already found
   behind FROM (left there by a branch that consumed past them) are skipped,
   and the scan never restarts, so a line costs one regex pass plus one call
   per special character."
  [m from]
  (loop []
    (when (.find m)
      (let [j (.start m)]
        (if (>= j from) j (recur))))))

(defn- next-special-index
  "Next INLINE-SPECIAL match start at or after I, or nil when the matcher has
   no match left. PENDING (a volatile) caches the match MATCHER-NEXT found for
   a previous position: the chunk step stops at that index, so the following
   iteration must dispatch it before the matcher advances again."
  [m pending i]
  (let [p @pending]
    (if (and p (>= p i))
      p
      (let [q (matcher-next m i)]
        (vreset! pending q)
        q))))

(defn- email-attempt-limit
  "First index at which another bare-email attempt could succeed, given a
   failed attempt at LINE[I]. The scan walks the local-part characters after I
   and returns the index past the first character that is not one of them (an
   @ or anything else). Every start in [I, limit) fails for the same reason:
   its local part ends at that same first non-local character, so the regex
   sees the same @ and domain. Bounds the retry attempts to one per local-part
   run instead of one per character (an @-bearing prose line otherwise pays a
   (subs line i) + regex miss at every alphanumeric char)."
  [line i]
  (let [n (count line)]
    (loop [k i]
      (if (and (< k n) (contains? bare-email-start-chars (nth line k)))
        (recur (inc k))
        (min n (inc k))))))

(def ^:private delimiter-rules
  "Delimiter markers keyed by first char: :double when the next char repeats
   the marker, :single otherwise. :marker is both the opening and closing
   marker, so its length is the advance past the close; :min-content is the
   smallest allowed content length; :content? rows recurse into parse-inline.
   A lone ~ has no :single row and falls through to the autolink branch."
  {\* {:double {:type :strong :marker "**" :min-content 1 :content? true}
       :single {:type :em :marker "*" :min-content 1 :content? true}}
   \~ {:double {:type :del :marker "~~" :min-content 1 :content? true}}
   \` {:single {:type :code :marker "`" :min-content 0 :content? false}}})

(defn parse-inline
  "Parse inline Markdown into pure-data tokens. The one-argument form parses
   ordinary text; the private second argument disables nested links/autolinks
   while parsing an explicit link label, matching Marked's inLink state.

   Accumulation (jolt perf): OUT is a transient vector behind a volatile and
   SB buffers the pending :text run, so a plain char is one StringBuilder
   append instead of a per-char token + peek/pop/conj merge (~3x on bb,
   ~1.4x on jolt for prose lines). Every dispatch branch ends in
   (recur (inc i)) or (recur next-i) — a single loop arg, so SCI never has to
   reason about tail position of a multi-arg recur inside if-let.

   Chunked plain runs (bb perf): the loop asks a stateful matcher for the
   next INLINE-SPECIAL index J (MATCHER-NEXT / NEXT-SPECIAL-INDEX). When J is
   ahead, every char in [I, J) is appended with one (subs line i j) instead of
   one character at a time; when J is nil the rest of the line goes in one
   append. The per-char iteration is ~0.9 us/char on bb and prose is mostly
   plain chars, so this is where the loop cost drops. Lines holding an @ keep
   the per-char loop (AT? below): an email match can start at any local-part
   char, and a run chunk would skip one.

   Per-iteration cost is the whole game here: the loop reads a Character with
   (nth line i) and compares characters — never building a 1-char string; the
   `remaining' substring is built only in the branches that feed it to a
   regex; the closing-delimiter searches run str/index-of on LINE with an
   absolute start index (equivalent offsets to searching the remainder); the
   bare-email regex is reachable only when the line holds an @ at all, the
   current char can start its local part and no earlier attempt has ruled the
   run out (EMAIL-FROM); the bare-url regex runs only when the literal scheme
   prefix follows the start char."
  ([line]
   (parse-inline line false))
  ([line in-link?]
   (if (empty? line)
     []
     (let [n (count line)
           at? (some? (str/index-of line "@"))
           matcher (when-not at?
                     (re-matcher (if in-link? inline-special-in-link-re
                                     inline-special-re)
                                 line))
           first-j (when matcher (matcher-next matcher 0))]
       (if (and matcher (nil? first-j))
         ;; The whole line is one plain run: one text token, no accumulator
         ;; machinery (volatiles, StringBuilder) beyond the matcher scan.
         [{:type :text :s line}]
         (let [email-from (volatile! 0)
               out (volatile! (transient []))
               sb (StringBuilder.)
               pending (volatile! first-j)]
           (loop [i 0]
             (if (>= i n)
               (do (flush-text out sb)
                   (persistent! @out))
               (let [j (when matcher (next-special-index matcher pending i))
                     stop (or j n)]
                 (if (and matcher (> stop i))
                   ;; plain run: one append for [i, stop)
                   (do (.append sb ^String (subs line i stop))
                       (recur stop))
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

                       :else
                       (let [rules (get delimiter-rules ch)
                             repeated? (and rules (< (inc i) n)
                                            (= (nth line (inc i)) ch))
                             rule (if repeated?
                                    (get rules :double (get rules :single))
                                    (get rules :single))]
                         (cond
                           rule
                           (let [marker (:marker rule)
                                 marker-len (count marker)
                                 content-start (+ i marker-len)
                                 end (str/index-of line marker content-start)]
                             (if (and end (>= (- end content-start)
                                              (:min-content rule)))
                               (let [content (subs line content-start end)
                                     type (:type rule)]
                                 (push-token out sb (if (:content? rule)
                                                      {:type type
                                                       :content (parse-inline content in-link?)}
                                                      {:type type :s content}))
                                 (recur (+ end marker-len)))
                               (do (.append sb ch) (recur (inc i)))))

                           (not in-link?)
                           ;; Perf gates (jolt irregex is ~25x bb on a miss): the
                           ;; email regex only runs where an earlier miss has not
                           ;; ruled the local-part run out (EMAIL-FROM) and the
                           ;; first char can begin its local part; the url regex
                           ;; only runs when the literal scheme prefix follows
                           ;; the start char. Both gates fail only calls the
                           ;; regex would.
                           (if-let [matched (or (when (and at? (>= i @email-from)
                                                           (contains? bare-email-start-chars ch))
                                                  (let [m (re-find bare-email-re (subs line i))]
                                                    (when-not m
                                                      (vreset! email-from (email-attempt-limit line i)))
                                                    m))
                                                (when-let [prefixes (get bare-url-prefixes ch)]
                                                  (when (ci-prefix-any-at? line (inc i) prefixes)
                                                    (re-find bare-url-re (subs line i)))))]
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
                           (do (.append sb ch) (recur (inc i)))))))))))))))))

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
      (vswap! result conj! list-tok))))

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
  "A list item holding one content line's inline tokens. Ordered items keep
   their source MARKER (\"2.\") so the renderer can honor the original
   numbering; unordered items pass nil."
  [inline-tokens marker]
  (cond-> {:content [inline-tokens]}
    marker (assoc :marker marker)))

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
        item (make-item (parse-inline content)
                        (when (= type :ol) (re-find ol-marker-re trimmed)))
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
    (vswap! result conj! {:type :blank})))

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
    (vswap! result conj!
            {:type :table
             :header (cell-tokens (nth lines i))
             :rows (mapv cell-tokens (subvec lines (+ i 2) j))
             :aligns (mapv cell-align (take n (concat sep-cells (repeat ""))))
             :raw (str/join "\n" (subvec lines i j))})
    (- j i)))

;; ─── Block tokenizer ────────────────────────────────────────────────────────

(defn- code-token
  "Assemble a :code block token from an open code-state map: the raw interior
   lines joined once (see the fence-close and EOF flushes in parse)."
  [{:keys [lang lines]}]
  {:type :code :lang lang :text (str/join "\n" lines)})

(defn- code-fence-open?
  [fc trimmed]
  (and (= fc \`) (re-matches code-fence-re trimmed)))

(defn- hr-line?
  [fc trimmed]
  (and (contains? marker-first-chars fc) (re-matches hr-re trimmed)))

(defn- heading-line?
  [fc trimmed]
  (and (= fc \#) (re-matches heading-re trimmed)))

(defn- quote-line?
  [fc trimmed]
  (and (= fc \>) (re-matches quote-re trimmed)))

(defn- list-line?
  [fc trimmed]
  (and (contains? marker-first-chars fc)
       (or (re-matches ul-re trimmed) (re-matches ol-re trimmed))))

(defn- table-start?
  [lines i]
  (let [line (nth lines i)]
    (and (table-row? line)
         (seq (split-table-row line))
         (table-separator? (split-table-row (nth lines (inc i) ""))))))

(defn- blank-line?
  [trimmed line]
  (and (zero? (count trimmed)) (re-matches empty-re line)))

(defn- continuation-line?
  [stack indent]
  (and (seq @stack) (> indent (:indent (peek @stack)))))

(defn- handle-code-state
  [i result stack code-state trimmed line]
  (if (re-matches code-fence-re trimmed)
    (let [code-tok (code-token @code-state)]
      (if (:in-item? @code-state)
        (attach-block! stack code-tok)
        (vswap! result conj! code-tok))
      (vreset! code-state nil)
      (inc i))
    (do (vswap! code-state update :lines conj
                (strip-indent (:indent @code-state) line))
        (inc i))))

(defn- handle-code-fence
  [i result stack code-state trimmed indent]
  (let [lang (or (second (re-find code-fence-re trimmed)) "")]
    (if (and (seq @stack) (> indent (:indent (peek @stack))))
      (vreset! code-state {:lang lang :lines [] :indent indent :in-item? true})
      (do (close-all-lists! stack result)
          (vreset! code-state {:lang lang :lines [] :indent indent})))
    (inc i)))

(defn- handle-hr
  [i result stack]
  (close-all-lists! stack result)
  (vswap! result conj! {:type :hr})
  (inc i))

(defn- handle-heading
  [i result stack trimmed]
  (close-all-lists! stack result)
  (let [[_ level-str content] (re-find heading-re trimmed)]
    (vswap! result conj! {:type :heading :level (count level-str)
                          :content (parse-inline content)}))
  (inc i))

(defn- handle-quote
  [i result stack trimmed]
  (close-all-lists! stack result)
  (let [[_ content] (re-find quote-re trimmed)]
    (vswap! result conj! {:type :quote :content (parse-inline content)}))
  (inc i))

(defn- handle-list
  [i result stack indent trimmed]
  (add-list-line! stack result indent trimmed)
  (inc i))

(defn- handle-table
  [lines i result stack]
  (close-all-lists! stack result)
  (+ i (parse-table! lines i result)))

(defn- handle-blank
  [i result stack]
  (add-blank-line! stack result)
  (inc i))

(defn- handle-continuation
  [i stack line]
  (add-continuation! stack line)
  (inc i))

(defn- handle-paragraph
  [i result stack line]
  (close-all-lists! stack result)
  (vswap! result conj! {:type :paragraph :content (parse-inline line)})
  (inc i))

(defn- parse-dispatch-line
  [lines i result stack code-state line trimmed fc indent]
  (cond
    (code-fence-open? fc trimmed) (handle-code-fence i result stack code-state trimmed indent)
    (hr-line? fc trimmed) (handle-hr i result stack)
    (heading-line? fc trimmed) (handle-heading i result stack trimmed)
    (quote-line? fc trimmed) (handle-quote i result stack trimmed)
    (list-line? fc trimmed) (handle-list i result stack indent trimmed)
    (table-start? lines i) (handle-table lines i result stack)
    (blank-line? trimmed line) (handle-blank i result stack)
    (continuation-line? stack indent) (handle-continuation i stack line)
    :else (handle-paragraph i result stack line)))

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
   content). Ordered items also carry :marker, the source marker (\"2.\"), so
   the renderer keeps the original numbering. A :blank pseudo-item renders an
   empty line inside the list.

   Inline tokens are produced by parse-inline: :text/:strong/:em/:del/:code/:link.
   All tokens are plain data — no ANSI styling, no terminal-width dependence."
  [text]
  (let [lines (str/split-lines (or text ""))
        result (volatile! (transient []))
        code-state (volatile! nil)
        stack (volatile! [])
        n (count lines)]
    (loop [i 0]
      (when (< i n)
        (let [line (nth lines i)
              trimmed (str/trim line)
              fc (when (pos? (count trimmed)) (nth trimmed 0))
              indent (leading-indent line)]
          (recur (if @code-state
                   (handle-code-state i result stack code-state trimmed line)
                   (parse-dispatch-line lines i result stack code-state line trimmed fc indent))))))
    ;; Flush an unclosed code block at end of input (attach when inside a list)
    (when-let [cs @code-state]
      (let [code-tok (code-token cs)]
        (if (:in-item? cs)
          (attach-block! stack code-tok)
          (vswap! result conj! code-tok))))
    (close-all-lists! stack result)
    (persistent! @result)))
