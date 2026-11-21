(ns kmet.tui.components.markdown
  "Markdown to ANSI-styled terminal output.
   Port of @earendil-works/pi-tui Markdown.
   The tokenizer is kmet.tui.markdown/parse (pure data, no ANSI); this
   component walks the token AST and applies theme, padding, and word-wrap.
   Table borders come from a kmet.tui.border set (tui.md §2.8)."
  (:require [clojure.string :as str]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.border :as border]
            [kmet.tui.utils :as u]
            [kmet.hikap.component :refer [track!]]
            [kmet.tui.macros :refer [defcomponent]]
            [kmet.tui.markdown :as md]
            [kmet.libs.theme :as theme]))

;; ─── Theme ──────────────────────────────────────────────────────────────────
;; Matches pi's MarkdownTheme interface.

(defrecord MarkdownTheme [heading link link-url code code-block
                          code-block-border quote quote-border hr
                          list-bullet bold italic underline strikethrough
                          code-block-indent highlight-code])

(def ^:private rst "\u001b[0m")
(def ^:private bold-ansi "\u001b[1m")
(def ^:private dim-ansi "\u001b[2m")
(def ^:private italic-ansi "\u001b[3m")
(def ^:private ul-ansi "\u001b[4m")
(def ^:private strike-ansi "\u001b[9m")

(def default-theme
  (map->MarkdownTheme
   {:heading (fn [s]
               (str bold-ansi "\u001b[33m" s rst))
    :link (fn [s] (str "\u001b[36m" s rst))
    :link-url (fn [s] (str dim-ansi s rst))
    :code (fn [s] (str "\u001b[33m" s rst))
    :code-block (fn [s] (str "\u001b[33m" s rst))
    :code-block-border identity
    :quote (fn [s] (str dim-ansi "\u001b[37m" s rst))
    :quote-border (fn [s] (str dim-ansi "\u001b[90m" s rst))
    :hr (fn [s] (str dim-ansi s rst))
    :list-bullet (fn [s] (str "\u001b[36m" s rst))
    :bold (fn [s] (str bold-ansi s "\u001b[22m"))
    :italic (fn [s] (str italic-ansi s "\u001b[23m"))
    :underline (fn [s] (str ul-ansi s "\u001b[24m"))
    :strikethrough (fn [s] (str strike-ansi s "\u001b[29m"))
    :code-block-indent "  "
    :highlight-code nil}))

;; ─── AST rendering ──────────────────────────────────────────────────────────

(defn- pad-right
  "Append spaces so the visible width of S reaches CW."
  [cw s]
  (str s (apply str (repeat (max 0 (- cw (u/visible-width s))) \space))))

(declare render-inlines)

(defn- style-prefix
  "Return the opening style sequence produced by STYLE-FN before its text."
  [style-fn]
  (if style-fn
    (let [sentinel "\u0000"
          styled (style-fn sentinel)
          index (str/index-of styled sentinel)]
      (if (some? index)
        (subs styled 0 index)
        ""))
    ""))

(defn- make-style-context
  "Build the inline styling context used to preserve a default style across
   Markdown formatting resets, matching pi's Markdown renderer."
  [style-fn]
  (when style-fn
    {:apply-text style-fn
     :style-prefix (style-prefix style-fn)}))

(defn- apply-style-context
  [style-context text]
  (if-let [style-fn (:apply-text style-context)]
    (style-fn text)
    text))

(defn- append-style-prefix
  [text style-context]
  (str text (:style-prefix style-context "")))

(defn- hyperlink
  "Wrap TEXT in an OSC 8 hyperlink targeting URL, matching pi's terminal
   hyperlink helper."
  [text url]
  (str "\u001b]8;;" url "\u001b\\" text "\u001b]8;;\u001b\\"))

(defn- raw-inline-source
  "Reconstruct the raw Markdown source represented by inline TOKENS."
  [tokens]
  (apply str
         (map (fn [tok]
                (case (:type tok)
                  :text (:s tok)
                  :code (str "`" (:s tok) "`")
                  :strong (str "**" (raw-inline-source (:content tok)) "**")
                  :em (str "*" (raw-inline-source (:content tok)) "*")
                  :del (str "~~" (raw-inline-source (:content tok)) "~~")
                  :link (str "[" (raw-inline-source (:text tok)) "](" (:url tok) ")")
                  ""))
              tokens)))

(defn- link-label-matches-url?
  [label url]
  (or (= label url)
      (= label (if (str/starts-with? url "mailto:")
                 (subs url (count "mailto:"))
                 url))))

(defn- trim-style-prefix
  [text style-context]
  (let [prefix (:style-prefix style-context "")]
    (if (seq prefix)
      (loop [text text]
        (if (str/ends-with? text prefix)
          (recur (subs text 0 (- (count text) (count prefix))))
          text))
      text)))

(defn- render-inline
  "Render one inline token to a styled string while preserving the default
   text style after Markdown-specific style resets."
  [tok theme style-context]
  (case (:type tok)
    :text (apply-style-context style-context (:s tok))
    :strong (append-style-prefix
             ((:bold theme) (render-inlines (:content tok) theme style-context))
             style-context)
    :em (append-style-prefix
         ((:italic theme) (render-inlines (:content tok) theme style-context))
         style-context)
    :del (append-style-prefix
          ((:strikethrough theme) (render-inlines (:content tok) theme style-context))
          style-context)
    :code (append-style-prefix ((:code theme) (:s tok)) style-context)
    :link (let [label-tokens (:text tok)
                label (or (:raw-label (meta tok))
                          (raw-inline-source label-tokens))
                link-text ((:link theme)
                           ((:underline theme) (render-inlines label-tokens theme style-context)))
                url (:url tok)
                rendered (if (:hyperlinks (timg/get-capabilities))
                           (hyperlink link-text url)
                           (if (link-label-matches-url? label url)
                             link-text
                             (str link-text ((:link-url theme) (str " (" url ")")))))]
            (append-style-prefix rendered style-context))
    ""))

(defn- render-inlines
  "Render a vector of inline tokens to one styled string."
  ([tokens theme]
   (render-inlines tokens theme nil))
  ([tokens theme style-context]
   (trim-style-prefix
    (apply str (map #(render-inline % theme style-context) tokens))
    style-context)))

(def ^:private ^:dynamic *code-lines-cache*
  "Optional atom for render-code's body-line cache, bound by render-lines.
   Entry: {:key [pad indent width] :hl styled complete lines :groups body
   lines per line}. A streaming fence re-wraps only the line being typed and
   the new lines."
  nil)

(defn- code-body-lines
  "Wrap STYLED at CONTENT-WIDTH and pad each line — one code line's body."
  [styled content-width]
  (mapv #(pad-right content-width %) (u/wrap-text-with-ansi styled content-width)))

(defn- newline-count
  "Number of complete lines in S (one per newline)."
  [s]
  (loop [i 0 n 0]
    (if-let [j (str/index-of s "\n" i)]
      (recur (inc j) (inc n))
      n)))

(defn- render-code
  "Render a :code token: styled fence lines (with lang), interior lines with
   the code-block theme and indent (or a :highlight-code hook when set).
   EXTRA-INDENT prefixes code nested inside list items. Trailing interior
   blank lines are preserved. Like pi, long lines are wrapped via
   wrap-text-with-ansi to CONTENT-WIDTH, not truncated with ellipsis.

   With *code-lines-cache* bound, the body lines of the complete code lines
   are reused while their styled lines match, so a growing fence wraps only
   its last line and the new lines. The incomplete last line is never
   cached."
  [result t theme content-width left-pad extra-indent]
  (let [indent (or (:code-block-indent theme) "  ")
        prefix (str left-pad extra-indent)
        lang (:lang t "")
        text (or (:text t) "")
        lines (vec (if-let [hl (:highlight-code theme)]
                     (hl text lang)
                     (mapv #((:code-block theme) %)
                           (if (str/blank? text) [] (str/split text #"\n" -1)))))
        ;; the last line is incomplete while the fence streams; only the
        ;; complete lines (through the last newline) are cacheable
        n-complete (min (count lines) (newline-count text))
        cache-key [prefix indent content-width]
        cached (when *code-lines-cache*
                 (let [c @*code-lines-cache*]
                   (when (and c
                              (= cache-key (:key c))
                              ;; the styled lines are what the body renders
                              ;; from; a highlighter may depend on later text
                              (<= (count (:hl c)) (count lines))
                              (= (:hl c) (subvec lines 0 (count (:hl c)))))
                     c)))
        body (fn [l] (code-body-lines (str prefix indent l) content-width))
        groups (if cached
                 (into (vec (:groups cached)) (map body (drop (count (:hl cached)) lines)))
                 (mapv body lines))
        push-wrapped (fn [styled]
                       (doseq [l (code-body-lines styled content-width)]
                         (vswap! result conj l)))]
    (push-wrapped (str prefix ((:code-block-border theme) (str "```" lang))))
    (doseq [g groups, l g]
      (vswap! result conj l))
    (push-wrapped (str prefix ((:code-block-border theme) "```")))
    (when *code-lines-cache*
      (reset! *code-lines-cache*
              (when (pos? n-complete)
                {:key cache-key
                 :hl (subvec lines 0 n-complete)
                 :groups (subvec groups 0 n-complete)})))))

(defn- longest-word-width
  "Visible width of TEXT's longest word, capped at LIMIT (0 when empty)."
  [text limit]
  (min limit (reduce max 0 (map u/visible-width (filter seq (str/split text #"\s+"))))))

(defn- partial-fence-line?
  "A line of 1-2 backticks: a fragment of a closing fence mid-stream."
  [line]
  (let [n (count line)]
    (and (pos? n) (<= n 2) (every? #(= \` %) line))))

(defn- trim-code-fence
  "Streaming fix (pi #5825): strip trailing partial closing-fence lines from
   a :code token's text so fence fragments never show while the closing fence
   is being typed. Recurses into the last list item's last :blocks entry."
  [token]
  (case (:type token)
    :code (loop [text (:text token)]
            (let [lines (str/split text #"\n" -1)]
              (if (and (seq lines) (partial-fence-line? (peek lines)))
                (recur (str/join "\n" (pop lines)))
                (assoc token :text text))))
    (:ul :ol)
    (let [items (:items token)]
      (loop [i (dec (count items))]
        (cond
          (neg? i) token
          (= :blank (:type (nth items i))) (recur (dec i))
          :else (update-in token [:items i :blocks]
                           (fn [blocks]
                             (if (seq blocks)
                               (update blocks (dec (count blocks)) trim-code-fence)
                               blocks))))))
    token))

(def ^:private cell-style-reset
  "SGR reset that closes the text attributes pi's wrapCellText leaves to
   the caller between two wrapped cell fragments: bold, dim, italic,
   underline, blink, inverse, hidden, strikethrough and fg. Background and
   an open OSC 8 hyperlink are deliberately not reset, matching pi."
  "\u001b[22;23;24;25;27;28;29;39m")

(defn- wrap-cell-text
  "Wrap CELL to MAX-WIDTH, closing text styles after every non-final
   fragment and re-applying STYLE-PREFIX, the cell's surrounding
   default-style opener. pi's wrapCellText does this: without the close a
   style the wrapper left open (a code or bold span broken across lines)
   tints the cell's pad spaces and the border glyph after them, so a
   wrapped cell draws its │ in the span's color instead of the terminal's
   own. The next fragment re-opens what it needs itself, so the close
   between two fragments changes no visible cell text."
  [cell max-width style-prefix]
  (let [frags (u/wrap-text-with-ansi cell (max 1 max-width))
        last-i (dec (count frags))]
    (mapv (fn [i frag]
            (str frag (if (< i last-i) cell-style-reset "") style-prefix))
          (range) frags)))

(defn- emit-table-row!
  "Push the visual lines of one table ROW onto RESULT: each cell wrapped to
   its COLUMN-WIDTHS entry, padded, joined with cell separators taken from B
   (a kmet.tui.border set). Header cells render bold when BOLD?; STYLE-PREFIX
   is the cell's surrounding default-style opener, restored after each
   non-final fragment's style reset (pi: wrapCellText)."
  [result row column-widths theme style-prefix bold? left-pad b]
  (let [v (:left b)
        wrapped (mapv (fn [c w] (wrap-cell-text c w style-prefix)) row column-widths)
        height (reduce max 1 (map count wrapped))]
    (dotimes [i height]
      (let [cells (mapv (fn [wl w]
                          (let [s (nth wl i "")
                                padded (str s (apply str (repeat (max 0 (- w (u/visible-width s))) \space)))]
                            (if bold? ((:bold theme) padded) padded)))
                        wrapped column-widths)]
        (vswap! result conj (str left-pad v " "
                                 (str/join (str " " v " ") cells)
                                 " " v))))))

(defn- table-line
  "One table rule line: LEFT and RIGHT corners and MID junctions around a
   run of H per column. The set's glyphs decide how a table degrades —
   :ascii turns every junction into +, :hidden into a blank line of the
   same width."
  [h left mid right column-widths]
  (let [run (fn [w] (apply str (repeat w h)))]
    (str left h (str/join (str h mid h) (map run column-widths)) h right)))

(defn- render-table
  "Render a :table token: box-drawn borders from BORDER-SET, bold header, a
   separator between every data row, width-aware columns with cell wrapping
   (pi's renderTable port). Falls back to the raw markdown when the table
   cannot fit."
  [result t theme content-width left-pad default-style border-set]
  (let [num-cols (count (:header t))
        b (or border-set border/normal)
        border-overhead (inc (* 3 num-cols))
        available-for-cells (- content-width border-overhead)
        style-context (make-style-context default-style)
        style-prefix (:style-prefix style-context "")
        cell-style (fn [c]
                     (render-inlines c theme style-context))]
    (if (< available-for-cells num-cols)
      ;; Too narrow — fall back to the raw markdown, wrapped
      (doseq [line (u/wrap-text-with-ansi (:raw t) content-width)]
        (vswap! result conj (pad-right content-width (str left-pad line))))
      (let [header-styled (mapv cell-style (:header t))
            row-styled (mapv #(mapv cell-style %) (:rows t))
            max-unbroken 30
            natural (reduce (fn [ws row]
                              (mapv (fn [w c] (max w (u/visible-width c))) ws row))
                            (mapv u/visible-width header-styled)
                            row-styled)
            min-word (reduce (fn [ws row]
                               (mapv (fn [w c] (max w (longest-word-width c max-unbroken))) ws row))
                             (mapv #(max 1 (longest-word-width % max-unbroken)) header-styled)
                             row-styled)
            min-cols (if (> (reduce + min-word) available-for-cells)
                       (let [remaining (- available-for-cells num-cols)
                             total-weight (reduce + (map #(max 0 (dec %)) min-word))
                             growth (mapv (fn [w] (if (pos? total-weight)
                                                    (Math/floorDiv (* (max 0 (dec w)) remaining) total-weight)
                                                    0))
                                          min-word)
                             base (mapv inc growth)
                             allocated (reduce + growth)]
                         (reduce (fn [cs i] (update cs i inc))
                                 base (range (min (- remaining allocated) num-cols))))
                       min-word)
            total-natural (+ (reduce + natural) border-overhead)
            column-widths (if (<= total-natural content-width)
                            (mapv max natural min-cols)
                            (let [total-grow (reduce + (map (fn [n m] (max 0 (- n m))) natural min-cols))
                                  extra (max 0 (- available-for-cells (reduce + min-cols)))
                                  grown (mapv (fn [n m] (+ m (if (pos? total-grow)
                                                               (Math/floorDiv (* (max 0 (- n m)) extra) total-grow)
                                                               0)))
                                              natural min-cols)
                                  remaining (- available-for-cells (reduce + grown))]
                              (loop [ws grown, rem remaining]
                                (if (pos? rem)
                                  (let [[ws' grew? rem'] (loop [ws' ws, i 0, grew? false, r rem]
                                                           (if (or (= i (count ws')) (zero? r))
                                                             [ws' grew? r]
                                                             (if (< (nth ws' i) (nth natural i))
                                                               (recur (update ws' i inc) (inc i) true (dec r))
                                                               (recur ws' (inc i) grew? r))))]
                                    (if grew?
                                      (recur ws' rem')
                                      ws'))
                                  ws))))
            top (table-line (:top b) (:top-left b) (:tee-down b) (:top-right b) column-widths)
            sep (table-line (:top b) (:tee-right b) (:cross b) (:tee-left b) column-widths)
            bot (table-line (:bottom b) (:bottom-left b) (:tee-up b) (:bottom-right b) column-widths)]
        (vswap! result conj (str left-pad top))
        (emit-table-row! result header-styled column-widths theme style-prefix true left-pad b)
        (vswap! result conj (str left-pad sep))
        (doseq [[i row] (map-indexed vector row-styled)]
          (emit-table-row! result row column-widths theme style-prefix false left-pad b)
          (when (< i (dec (count row-styled)))
            (vswap! result conj (str left-pad sep))))
        (vswap! result conj (str left-pad bot))))))

(defn- render-list
  "Render a :ul/:ol token at nesting DEPTH (0 = root), pushing lines onto
   RESULT. Nested lists indent 4 columns per depth (pi's convention); items
   render their :content lines (first gets the bullet, rest are continuations
   aligned to the marker column), wrap long lines to the item width, then
   render any :blocks (:ul/:ol at depth+1, :code at depth+1) and :blank
   pseudo-items as empty lines. Ordered items render their source :marker
   (\"2.\"), so an interrupted list keeps the original numbering; a
   marker-less item falls back to its position. DEFAULT-STYLE tints item text
   only."
  [result t theme depth content-width left-pad default-style]
  (let [indent (apply str (repeat (* 4 depth) \space))
        indent-w (count indent)
        num (volatile! 0)]
    (doseq [item (:items t)]
      (if (= :blank (:type item))
        (vswap! result conj (str left-pad (apply str (repeat content-width \space))))
        (let [n (vswap! num inc)
              style-context (make-style-context default-style)
              marker (if (= :ul (:type t))
                       "• "
                       (str (or (:marker item) (str n ".")) " "))
              marker-w (u/visible-width marker)
              bullet ((:list-bullet theme) marker)
              item-width (max 1 (- content-width indent-w marker-w))
              first-prefix (str left-pad indent bullet)
              cont-prefix (str left-pad indent (apply str (repeat marker-w \space)))
              first-line? (volatile! true)]
          (doseq [line (:content item)]
            (let [styled (render-inlines line theme style-context)
                  segs (if (<= (u/visible-width styled) item-width)
                         [styled]
                         (u/wrap-text-with-ansi styled item-width))]
              (doseq [wl segs]
                (let [prefix (if @first-line? first-prefix cont-prefix)]
                  (vswap! result conj
                          (str prefix wl
                               (apply str (repeat (max 0 (- content-width indent-w marker-w
                                                            (u/visible-width wl))) \space))))
                  (vreset! first-line? false)))))
          (doseq [b (:blocks item)]
            (case (:type b)
              :ul (render-list result b theme (inc depth) content-width left-pad default-style)
              :ol (render-list result b theme (inc depth) content-width left-pad default-style)
              :code (render-code result b theme content-width left-pad
                                 (apply str (repeat (* 4 (inc depth)) \space)))
              nil)))))))

(defn- render-block
  "Render one block token into RESULT (a volatile vector of lines).
   Each line is left-padded and right-padded to the content width, matching
   the original line-oriented renderer's output exactly. DEFAULT-STYLE tints
   text content (paragraphs, list items, table cells) but not code blocks,
   headings, quotes, or hr — mirroring pi's defaultTextStyle. TABLE-BORDER
   is the kmet.tui.border set table glyphs come from (nil = :normal)."
  [result t theme content-width left-pad default-style table-border]
  (case (:type t)
    :blank
    (vswap! result conj (str left-pad (apply str (repeat content-width \space))))

    :hr
    (vswap! result conj (str left-pad ((:hr theme) (apply str (repeat content-width "─")))))

    :heading
    (let [level (:level t)
          heading-style-fn (if (= level 1)
                             (fn [s] ((:heading theme) ((:bold theme) ((:underline theme) s))))
                             (fn [s] ((:heading theme) ((:bold theme) s))))
          heading-ctx (make-style-context heading-style-fn)
          content (render-inlines (:content t) theme heading-ctx)
          ;; pi: H3+ deliberately keeps the "### " prefix (visible depth) and wraps (never truncates)
          styled (if (>= level 3)
                   (str (heading-style-fn (str (apply str (repeat level "#")) " ")) content)
                   content)]
      (doseq [wl (u/wrap-text-with-ansi styled content-width)]
        (vswap! result conj (pad-right content-width (str left-pad wl))))
      ;; Add underline for H1/H2
      (when (<= level 2)
        (vswap! result conj (str left-pad ((:hr theme)
                                           (apply str (repeat content-width (if (= level 1) "═" "─"))))))))

    :code
    (render-code result t theme content-width left-pad "")

    :table
    (render-table result t theme content-width left-pad default-style table-border)

    :quote
    (let [border ((:quote-border theme) "▎")
          border-w (u/visible-width border)
          content-w (max 1 (- content-width border-w))
          styled ((:quote theme) (render-inlines (:content t) theme))
          wrapped (u/wrap-text-with-ansi styled content-w)]
      (doseq [wl wrapped]
        (vswap! result conj
                (str left-pad border wl
                     (apply str (repeat (max 0 (- content-width border-w (u/visible-width wl))) \space))))))

    :ul
    (render-list result t theme 0 content-width left-pad default-style)

    :ol
    (render-list result t theme 0 content-width left-pad default-style)

    :paragraph
    (let [styled (render-inlines (:content t) theme (make-style-context default-style))
          line-width (u/visible-width styled)]
      (if (<= line-width content-width)
        (vswap! result conj (pad-right content-width (str left-pad styled)))
        ;; Word-wrap long lines
        (doseq [wl (u/wrap-text-with-ansi styled content-width)]
          (vswap! result conj (pad-right content-width (str left-pad wl))))))

    nil))

;; ─── Incremental rendering (streaming) ───────────────────────────────────

(defn- parse-stream-tokens
  "Parse TEXT into block tokens and trim a partial closing fence from the
   last block (streaming: a closing fence being typed must not make the code
   block shrink, pi #5825)."
  [text]
  (let [tokens (md/parse text)]
    (if (seq tokens)
      (update tokens (dec (count tokens)) trim-code-fence)
      tokens)))

(defn- render-token-lines
  "Render one block TOKEN into its own line vector — the unit the incremental
   renderer caches. Every block's lines are independent of its neighbours
   (render-block reads only the token, the theme and the width), so a cache of
   per-block lines concatenates back into the whole-document render."
  [token theme content-width left-pad default-style table-border]
  (let [result (volatile! [])]
    (render-block result token theme content-width left-pad default-style table-border)
    @result))

(defn- last-blank-run-start
  "Index in TEXT of the first line of the last run of blank lines at or
   after FROM, or nil. A blank line is one whose text is whitespace only.
   The first line of the RUN (not its last) is the cut: the prefix then ends
   with the previous non-blank line's newline, so `str/split-lines` drops
   nothing from it, and the whole blank run is in the tail, which parses on
   its own. Cutting inside a run would leave earlier blanks in the prefix,
   where the parser's trailing-empty-line drop loses them. FROM is always a
   line start (0 or the start of a blank run)."
  [text from]
  (loop [line-start from, cut nil, previous-blank? false]
    (if-some [nl (str/index-of text "\n" line-start)]
      (let [line-end (if (and (pos? nl) (= \return (nth text (dec nl))))
                       (dec nl)
                       nl)
            blank? (str/blank? (subs text line-start line-end))
            cut (if (and blank? (not previous-blank?) (not= line-start from))
                  line-start
                  cut)]
        (recur (inc nl) cut blank?))
      ;; A final line without a newline can be a blank line (the text ends
      ;; in spaces); the position itself just past a trailing newline is not
      ;; a line, so it must not be cut there.
      (let [blank? (and (< line-start (count text))
                        (str/blank? (subs text line-start)))]
        (if (and blank? (not previous-blank?) (not= line-start from))
          line-start
          cut)))))

(defn- blank-line-at?
  "True when the line starting at START in TEXT is whitespace only (or START
   is the end of the text). A cached stable prefix stays valid only while the
   text still has the blank line at its cut: a transform that appended a
   blank line once can stop doing so, leaving a table header immediately
   followed by its separator at the cut — the prefix's last block would then
   tokenize differently than in the whole-text parse."
  [text start]
  (let [nl (str/index-of text "\n" start)
        end (or nl (count text))]
    (str/blank? (subs text start end))))

(def ^:private clean-probe
  "An indented sentinel line appended to a segment to tell whether its last
   block leaves a container open (see segment-clean?)."
  (str (apply str (repeat 256 \space)) "__kmet_stable_probe__"))

(defn- segment-clean?
  "True when SEGMENT — parsed from a clean state — ends outside every open
   container, so the text after it can be parsed separately and concatenated
   back. A last :ul/:ol block was closed by the end of the segment, not by
   its content, so the next line might still belong to it; a :code block may
   be a closed fence or an unclosed one, told apart by clean-probe (an open
   fence swallows it as code; a closed one parses it as a paragraph). Every
   other last block is complete at a blank-line boundary."
  [segment seg-tokens]
  (let [t (:type (peek seg-tokens))]
    (cond
      (#{:ul :ol} t) false
      (= :code t) (= :paragraph (:type (peek (md/parse (str segment clean-probe)))))
      :else true)))

(def ^:private empty-cache
  "No stable prefix yet: every call parses and renders the whole text."
  {:stable-text "" :stable-tokens [] :stable-lines []})

(defn- promote-stable
  "Extend CACHED's clean stable prefix to the last blank line in TEXT.
   The prefix may only be cut where the parser's state (open list, code
   fence) is provably clean, so the tail can be parsed on its own and the
   tokens concatenated back into the whole-text parse. The promoted segment
   is the previous tail, so its already-rendered block lines are reused when
   the token values match. A cut that fails the check is remembered
   (:rejected-cut): its segment cannot change while only the text after it
   grows, so the next deltas don't pay for it again. Returns CACHED updated."
  [cached text theme content-width left-pad default-style table-border]
  (let [from (count (:stable-text cached))
        cut (last-blank-run-start text from)]
    (if (and cut (> cut from) (not= cut (:rejected-cut cached)))
      (let [segment (subs text from cut)
            seg-tokens (md/parse segment)]
        (if (segment-clean? segment seg-tokens)
          (let [n (count seg-tokens)
                old-tokens (:tail-tokens cached)
                old-lines (:tail-lines cached)
                reuse? (and old-tokens
                            (<= n (count old-tokens))
                            (= seg-tokens (subvec old-tokens 0 n)))
                seg-lines (if reuse?
                            (subvec old-lines 0 n)
                            (mapv (fn [t]
                                    (render-token-lines t theme content-width left-pad
                                                        default-style table-border))
                                  seg-tokens))]
            (assoc cached
                   :stable-text (subs text 0 cut)
                   :stable-tokens (into (vec (:stable-tokens cached)) seg-tokens)
                   :stable-lines (into (vec (:stable-lines cached)) seg-lines)
                   :rejected-cut nil))
          (assoc cached :rejected-cut cut)))
      cached)))

(defn render-lines
  "Render TEXT as Markdown to display lines at WIDTH — the Markdown
   component's pipeline (transform, parse, stream-fence trim, block styling
   and wrapping) with no component. Options mirror make-markdown, plus:

   :cache-atom — incremental streaming render, in two caching layers. Parsing
   resumes at the last clean blank-line boundary (:stable-text), so only the
   text after it is parsed; the blocks before it keep their rendered lines
   and only the tail is rendered per delta. A streaming text that only grows
   then pays for its trailing block instead of its whole history (24 KB of
   thinking was ~110 ms per delta; its trailing paragraph is ~2 ms).
   :cache-key — everything besides the text the lines depend on (width,
   theme, default style, transform, border). A changed key discards the
   entry.

   Blocks are compared after the transform and the fence trim, so a block
   that stops being the last one (a closing fence completing, a new list
   item) re-renders even though its source prefix did not change. A code
   fence that streams inside the tail is still re-rendered, but only its
   growing last line: the built-in highlighter reuses its styled prefix and
   render-code reuses the wrapped body lines of the complete code lines."
  [text width & {:keys [theme padding-x default-style transform border
                        cache-key cache-atom]
                 :or {padding-x 1}}]
  (let [content-width (max 1 (- width (* 2 padding-x)))
        text (if transform
               (transform text {:available-width content-width})
               text)
        table-border (border/resolve border)
        left-pad (apply str (repeat padding-x \space))
        ;; the default key captures every input the lines depend on; a
        ;; caller whose theme/default-style identity changes per call (the
        ;; assistant's derived closures) passes its own stable key instead
        key (or cache-key [width padding-x theme default-style transform table-border])
        cached (when cache-atom @cache-atom)
        cached (when (and cached (= key (:key cached))) cached)
        ;; The built-in highlighter's growing-code cache travels with the
        ;; entry, so a key change (width/theme) starts it fresh too.
        code-cache (when cache-atom (or (:code-cache cached) (atom nil)))
        code-lines-cache (when cache-atom (or (:code-lines-cache cached) (atom nil)))]
    (binding [theme/*code-cache* code-cache
              *code-lines-cache* code-lines-cache]
      (let [;; A transform may rewrite the whole text, so the cached prefix is
            ;; only reusable when the transformed text still starts with it.
            ;; Otherwise pick a fresh cut (the first render, a rewritten
            ;; prefix, a width/theme change), rendering its blocks from scratch.
            base (promote-stable
                  (if (and cached
                           (str/starts-with? text (or (:stable-text cached) ""))
                           ;; the cut must still begin a blank line in THIS text
                           ;; (a transform can drop the one that justified it);
                           ;; an empty prefix has no cut to re-validate
                           (or (zero? (count (:stable-text cached)))
                               (blank-line-at? text (count (:stable-text cached)))))
                    cached
                    ;; no usable prefix: pick a fresh cut from the top (and
                    ;; remember a rejected one, like any other attempt)
                    empty-cache)
                  text theme content-width left-pad default-style table-border)
            stable-text (:stable-text base)
            stable-tokens (:stable-tokens base)
            stable-lines (:stable-lines base)
            tail (subs text (count stable-text))
            ;; An empty tail after a promoted prefix contributes no block —
            ;; md/parse of "" is one :blank (split-lines' [""]), which belongs
            ;; only to a genuinely empty document.
            tail-tokens (if (and (seq stable-text) (empty? tail))
                          []
                          (parse-stream-tokens tail))
            ;; The stable prefix's lines are final (they cannot change while
            ;; the text only grows). Re-rendering the whole tail is cheap by
            ;; construction: it is the text since the last clean blank line,
            ;; and it used to be re-rendered per delta together with the whole
            ;; history.
            tail-lines (mapv (fn [t]
                               (render-token-lines t theme content-width left-pad
                                                   default-style table-border))
                             tail-tokens)
            per-block (into stable-lines tail-lines)]
        (when cache-atom
          (reset! cache-atom {:key key
                              :stable-text stable-text
                              :stable-tokens stable-tokens
                              :stable-lines stable-lines
                              :tail-tokens tail-tokens
                              :tail-lines tail-lines
                              ;; keep the unsafe-cut verdict for the next delta
                              :rejected-cut (:rejected-cut base)
                              ;; the growing-code highlight and body-line caches
                              :code-cache code-cache
                              :code-lines-cache code-lines-cache}))
        (into [] cat per-block)))))

;; ─── Markdown component ───────────────────────────────────────────────────

(defcomponent Markdown nil [text-atom theme-atom padding-x-atom
                            default-style-atom transform-atom
                            border-atom block-cache-atom cache-atom]
  (render [this width]
    (track! this width
      (let [padding-x @padding-x-atom
            theme @theme-atom
            default-style @default-style-atom
            transform @transform-atom
            border-set @border-atom]
        (render-lines @text-atom width
                      :theme theme
                      :padding-x padding-x
                      :default-style default-style
                      :transform transform
                      :border border-set
                      ;; the cache key is every input the block lines were
                      ;; built from besides the text; the setters swap the
                      ;; atoms, so a theme/style change misses the entry
                      :cache-key [width padding-x theme default-style transform border-set]
                      :cache-atom block-cache-atom)))))

;; ─── Construction ──────────────────────────────────────────────────────────

(defn make-markdown
  "Create a Markdown display component.
   Options: :theme (default-theme), :padding-x (default 1),
   :border — a kmet.tui.border set for table glyphs (default :normal;
   :ascii for terminals that cannot draw box characters), resolved here so
   an unknown style fails at construction,
   :default-style — per-line base style fn applied to text content
   (paragraphs, list items, table cells) but NOT block-level styled elements
   (code blocks, headings, quotes, hr), mirroring pi's defaultTextStyle,
   :transform — extension markdown transformer fn (fn [text {:keys
   [available-width]}]) applied per render before parsing (pi: Markdown's
   transform),
   :block-cache-atom — atom for the incremental block cache (default: a
   fresh one). A caller that swaps the component's text on every streaming
   delta gets a re-render of only the changed tail; see render-lines."
  [text & {:keys [theme padding-x default-style transform border block-cache-atom]
           :or {padding-x 1}}]
  (let [t (or theme default-theme)]
    (map->Markdown {:text-atom (atom text)
                    :theme-atom (atom t)
                    :padding-x-atom (atom padding-x)
                    :default-style-atom (atom default-style)
                    :transform-atom (atom transform)
                    :border-atom (atom (border/resolve border))
                    :block-cache-atom (or block-cache-atom (atom nil))
                    :cache-atom (atom nil)})))

(defn markdown-set-text! [md text]
  (reset! (:text-atom md) text))

(defn markdown-append! [md text]
  (swap! (:text-atom md) #(str % "\n" text)))

(defn markdown-set-theme! [md theme]
  (reset! (:theme-atom md) theme))

(defn markdown-set-default-style! [md f]
  (reset! (:default-style-atom md) f))

(defn markdown-set-transform! [md f]
  (reset! (:transform-atom md) f))

(defn markdown-set-padding-x! [md n]
  (reset! (:padding-x-atom md) n))

(defn markdown-get-text [md]
  @(:text-atom md))