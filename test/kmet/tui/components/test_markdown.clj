(ns kmet.tui.components.test-markdown
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as h]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]
            [kmet.tui.components.markdown :as md]))

(defn- strip-ansi [s]
  (u/strip-ansi-codes s))

;; ─── Construction ───────────────────────────────────────────────────────────

(t/deftest test-markdown-create
  (let [m (md/make-markdown "hello")]
    (t/is (satisfies? core/IComponent m))
    (t/is (= "hello" (md/markdown-get-text m)))))

(t/deftest test-markdown-create-empty
  (let [m (md/make-markdown "")]
    (t/is (satisfies? core/IComponent m))
    (t/is (= "" (md/markdown-get-text m)))))

;; ─── Render plain text ─────────────────────────────────────────────────────

(t/deftest test-markdown-render-plain
  (let [m (md/make-markdown "hello" :padding-x 0)
        lines (core/render m 20)]
    (t/is (pos? (count lines)))
    (t/is (some #(.contains % "hello") lines))))

(t/deftest test-markdown-render-padding
  (let [m (md/make-markdown "hello" :padding-x 2)
        lines (core/render m 20)]
    (t/is (pos? (count lines)))
    (t/is (pos? (count (first lines))))))

;; ─── Headings ──────────────────────────────────────────────────────────────

(t/deftest test-markdown-heading-h1
  (let [m (md/make-markdown "# Title" :padding-x 0)
        lines (core/render m 20)]
    (t/is (pos? (count lines)))
    (t/is (some #(.contains % "Title") lines))))

(t/deftest test-markdown-heading-h2
  (let [m (md/make-markdown "## Section" :padding-x 0)
        lines (core/render m 20)]
    (t/is (pos? (count lines)))
    (t/is (some #(.contains % "Section") lines))))

(t/deftest test-markdown-headings-add-underline
  (let [m (md/make-markdown "# Title\n\n## Section" :padding-x 0)
        lines (core/render m 20)]
    (t/is (>= (count lines) 4))))

(t/deftest test-markdown-heading-h3-keeps-prefix
  ;; pi keeps the "### " prefix for H3+ (visible depth)
  (let [m (md/make-markdown "### Sub" :padding-x 0)
        lines (mapv strip-ansi (core/render m 20))]
    (t/is (some #(.contains % "### Sub") lines)))
  (let [m (md/make-markdown "###### Deep" :padding-x 0)
        lines (mapv strip-ansi (core/render m 20))]
    (t/is (some #(.contains % "###### Deep") lines))))

;; ─── Code blocks ───────────────────────────────────────────────────────────

(t/deftest test-markdown-code-block
  (let [m (md/make-markdown "```clojure\n(defn hello []\n  (println \"hi\"))\n```" :padding-x 0)
        lines (core/render m 40)]
    (t/is (pos? (count lines)))
    (t/is (some #(.contains % "defn") lines))))

(t/deftest test-markdown-code-fence-lines
  ;; Fences render with their language via the code-block-border theme
  (let [m (md/make-markdown "```clojure\n(defn f [] 1)\n```" :padding-x 0)
        lines (mapv strip-ansi (core/render m 30))]
    (t/is (some #(.contains % "```clojure") lines))
    (t/is (some #(.contains % "defn") lines))
    (t/is (some #(.contains % "  (defn f [] 1)") lines))))

(t/deftest test-markdown-code-trailing-blank-preserved
  (let [m (md/make-markdown "```\na\n\n```" :padding-x 0)
        lines (mapv strip-ansi (core/render m 10))]
    (t/is (some #(.contains % "  a") lines))
    (t/is (some #(clojure.string/blank? %) lines))))

(t/deftest test-markdown-code-unclosed-fence
  (let [m (md/make-markdown "```\n(defn f [] 1)" :padding-x 0)
        lines (mapv strip-ansi (core/render m 30))]
    (t/is (some #(.contains % "defn") lines))))

;; ─── Streaming: incomplete markdown must render gracefully ─────────────────

(t/deftest test-markdown-streaming-partial-closing-fence
  ;; While the closing fence is being typed (` then `` then ```), its
  ;; fragments must never show as code content (pi #5825 trimPartialClosingFences)
  (doseq [frag ["" "`" "``"]]
    (let [src (str "```clojure\n(defn f [] 1)\n" frag)
          lines (mapv strip-ansi (core/render (md/make-markdown src :padding-x 0) 30))]
      (t/is (some #(.contains % "defn") lines) (pr-str src))
      (t/is (not-any? #(re-matches #"\s*`{1,2}\s*" %) lines) (pr-str src)))))

(t/deftest test-markdown-streaming-partial-fence-in-list
  ;; Same, for a code block nested in a list item
  (doseq [frag ["`" "``"]]
    (let [src (str "- a\n  ```clj\n  (x)\n  " frag)
          lines (mapv strip-ansi (core/render (md/make-markdown src :padding-x 0) 30))]
      (t/is (some #(.contains % "(x)") lines) (pr-str src))
      (t/is (not-any? #(re-matches #"\s*`{1,2}\s*" %) lines) (pr-str src)))))

(t/deftest test-markdown-streaming-partial-fence-after-blank-item
  (let [src "- a\n\n  ```\n  code\n  `"
        lines (mapv strip-ansi (core/render (md/make-markdown src :padding-x 0) 30))]
    (t/is (some #(.contains % "code") lines))
    (t/is (not-any? #(re-matches #"\s*`{1,2}\s*" %) lines))))

(t/deftest test-markdown-streaming-complete-fence-unaffected
  ;; A completed fence is never trimmed
  (let [src "```clojure\n(defn f [] 1)\n```"
        lines (mapv strip-ansi (core/render (md/make-markdown src :padding-x 0) 30))]
    (t/is (some #(.contains % "```clojure") lines))
    (t/is (some #(.contains % "defn") lines))
    (t/is (some #(.contains % "```") lines))))

(t/deftest test-markdown-streaming-nonfence-fragments-kept
  ;; Lines that only LOOK like partial fences are trimmed only when they are
  ;; the trailing lines of the LAST code block — content above stays intact
  (let [src "```\n`\ncode\n```"
        lines (mapv strip-ansi (core/render (md/make-markdown src :padding-x 0) 20))]
    (t/is (some #(.contains % "code") lines))
    (t/is (some #(.contains % "  `") lines))))

(t/deftest test-markdown-streaming-trailing-backtick-trimmed
  ;; Documented tradeoff (pi #5825): a trailing lone-backtick line of the LAST
  ;; code block is treated as a partial closing fence and trimmed — necessary
  ;; so fence fragments never flash in when the closing fence completes.
  (let [src "```\ncode\n`\n```"
        lines (mapv strip-ansi (core/render (md/make-markdown src :padding-x 0) 20))]
    (t/is (some #(.contains % "code") lines))
    (t/is (not-any? #(re-matches #"\s*`{1,2}\s*" %) lines))))

(t/deftest test-markdown-streaming-incomplete-table
  ;; Header + separator mid-stream renders the table as soon as both exist
  (let [lines (mapv strip-ansi (core/render
                                (md/make-markdown "| a | b |\n|---|---|\n| 1" :padding-x 0) 20))]
    (t/is (some #(.contains % "┌") lines))
    (t/is (some #(.contains % "│ 1") lines)))
  ;; A lone header row (no separator yet) shows as plain text, then becomes
  ;; a table when the separator arrives
  (let [lone (mapv strip-ansi (core/render
                               (md/make-markdown "| a | b |" :padding-x 0) 20))
        full (mapv strip-ansi (core/render
                               (md/make-markdown "| a | b |\n|---|---|" :padding-x 0) 20))]
    (t/is (not-any? #(.contains % "┌") lone))
    (t/is (some #(.contains % "┌") full))))

(t/deftest test-markdown-streaming-inline-unclosed
  ;; Unclosed inline markers show literally until they complete
  (let [m (md/make-markdown "Some **bold" :padding-x 0)
        lines (mapv strip-ansi (core/render m 30))]
    (t/is (some #(.contains % "**bold") lines)))
  (let [m (md/make-markdown "Some **bold**" :padding-x 0)
        lines (mapv strip-ansi (core/render m 30))]
    (t/is (some #(.contains % "bold") lines))
    (t/is (not-any? #(.contains % "**bold**") lines))))

(t/deftest test-markdown-streaming-unclosed-list
  (let [m (md/make-markdown "- item one\n  - nested" :padding-x 0)
        lines (mapv strip-ansi (core/render m 30))]
    (t/is (some #(.contains % "• item one") lines))
    (t/is (some #(.contains % "    • nested") lines))))

(t/deftest test-markdown-code-highlight-hook
  (let [theme (assoc md/default-theme
                     :highlight-code (fn [code lang] [(str "HL[" lang "]" code)]))
        m (md/make-markdown "```clj\n(x)\n```" :theme theme :padding-x 0)
        lines (mapv strip-ansi (core/render m 30))]
    (t/is (some #(.contains % "HL[clj]") lines))))

(t/deftest test-markdown-default-style
  ;; pi's defaultTextStyle: tints text content (paragraphs, list items,
  ;; table cells) but NOT block-level styled elements (code, headings, hr)
  (let [tint (fn [s] (str "T[" s "]"))
        m (md/make-markdown "para\n\n- item\n\n```clj\n(x)\n```\n\n# head"
                            :theme (assoc md/default-theme
                                          :highlight-code (fn [code _] [code]))
                            :default-style tint
                            :padding-x 0)
        lines (mapv strip-ansi (core/render m 30))]
    (t/is (some #(.contains % "T[para]") lines))
    (t/is (some #(.contains % "T[item]") lines))
    (t/is (not-any? #(re-find #"T\[" %) (filter #(re-find #"```|\(x\)|head" %) lines))
          "code blocks and headings are not tinted")))

(t/deftest test-markdown-default-style-restored-after-inline-formatting
  (doseq [[formatted trailing] [["**bold**" " after-bold"]
                                ["*italic*" " after-italic"]
                                ["~~strike~~" " after-strike"]
                                ["`code`" " after-code"]
                                ["[link](url)" " after-link"]]]
    (let [styled-text (atom [])
          style (fn [s]
                  (swap! styled-text conj s)
                  (str "\u001b[38;5;7m" s "\u001b[39m"))]
      (core/render (md/make-markdown (str formatted trailing)
                                     :default-style style
                                     :padding-x 0)
                   120)
      ;; pi applies the default style to the plain-text token after each
      ;; formatted token, rather than styling the complete rendered paragraph
      ;; once after Markdown formatting.
      (t/is (some #(= trailing %) @styled-text)
            (str "default style was not restored before " trailing)))))

(t/deftest test-markdown-default-style-not-applied-to-narrow-table-fallback
  (let [tint (fn [s] (str "T[" s "]"))
        source "| a | b |\n|---|---|\n| 1 | 2 |"
        rendered (str/join "\n"
                           (core/render (md/make-markdown source
                                                          :default-style tint
                                                          :padding-x 0)
                                        6))]
    ;; pi's too-narrow table fallback renders token.raw directly, without the
    ;; Markdown default text style.
    (t/is (not (str/includes? rendered "T[")))
    (t/is (str/includes? rendered "|"))))

(t/deftest test-markdown-default-theme-link-url
  (let [prev (timg/get-capabilities)]
    (try
      (timg/set-capabilities! {:images nil :true-color true :hyperlinks false})
      (let [rendered (str/join "\n"
                               (map strip-ansi
                                    (core/render (md/make-markdown "[text](http://example.com)"
                                                                   :padding-x 0)
                                                 40)))]
        (t/is (str/includes? rendered "text (http://example.com)"))
        (t/is (not (str/includes? rendered "((http://example.com)"))))
      (finally
        (timg/set-capabilities! prev)))))

(t/deftest test-markdown-links-match-pi
  (let [prev (timg/get-capabilities)]
    (try
      (timg/set-capabilities! {:images nil :true-color true :hyperlinks false})
      (let [theme (theme/get-markdown-theme theme/dark-theme)
            render (fn [source]
                     (first (core/render (md/make-markdown source
                                                           :theme theme
                                                           :padding-x 0)
                                         80)))
            same (render "[https://example.com](https://example.com)")
            mailto (render "[foo](mailto:foo)")
            different (render "[docs](https://example.com)")
            formatted (render "[**url**](url)")
            escaped (render "[a\\*b](a*b)")]
        ;; Pi underlines every link label. The fallback URL is omitted when the
        ;; raw label equals the URL or equals a mailto URL without its scheme.
        (t/is (str/includes? same "\u001b[4m"))
        (t/is (not (str/includes? (strip-ansi same) "(https://example.com)")))
        (t/is (not (str/includes? (strip-ansi mailto) "(mailto:foo)")))
        (t/is (str/includes? (strip-ansi different) "docs (https://example.com)"))
        ;; Pi compares the raw label, so Markdown delimiters and backslash
        ;; escapes make these labels different from their URLs.
        (t/is (str/includes? (strip-ansi formatted) "url (url)"))
        (t/is (str/includes? (strip-ansi escaped) "a*b (a*b)")))
      (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
      (let [rendered (first (core/render (md/make-markdown "[docs](https://example.com)"
                                                           :theme (theme/get-markdown-theme theme/dark-theme)
                                                           :padding-x 0)
                                         80))]
        ;; With OSC 8 support pi displays only the clickable label.
        (t/is (str/includes? rendered "\u001b]8;;https://example.com\u001b\\"))
        (t/is (str/includes? rendered "\u001b]8;;\u001b\\"))
        (t/is (str/includes? rendered "docs"))
        (t/is (not (str/includes? (u/strip-ansi-codes rendered) "https://example.com"))))
      (finally
        (timg/set-capabilities! prev)))))

(t/deftest test-markdown-theme-syntax-highlight
  ;; get-markdown-theme wires the lib tokenizer: known lang → syntax colors,
  ;; unknown lang → mdCodeBlock color (pi behavior)
  (let [known (core/render (md/make-markdown "```clojure\n(defn f [] 1)\n```"
                                             :theme (theme/get-markdown-theme theme/dark-theme)
                                             :padding-x 0)
                           30)
        unknown (core/render (md/make-markdown "```frobnicate\n(defn f [] 1)\n```"
                                               :theme (theme/get-markdown-theme theme/dark-theme)
                                               :padding-x 0)
                             30)]
    (t/is (some #(.contains % (theme/fg theme/dark-theme :syntax-keyword "defn")) known))
    (t/is (some #(.contains % (theme/fg theme/dark-theme :md-code-block "(defn f [] 1)")) unknown))))

(t/deftest test-markdown-empty-code-block-no-lines
  ;; Empty fences render fence lines only — highlighted path matches the
  ;; un-highlighted path (no phantom blank interior line).
  (let [m (md/make-markdown "```clojure\n```"
                            :theme (theme/get-markdown-theme theme/dark-theme)
                            :padding-x 0)
        lines (core/render m 30)]
    (t/is (= 2 (count lines)))))

(t/deftest test-markdown-code-in-list-item
  (let [m (md/make-markdown "- a\n  ```clj\n  (defn f [] 1)\n  ```" :padding-x 0)
        lines (mapv strip-ansi (core/render m 30))]
    (t/is (some #(.contains % "• a") lines))
    (t/is (some #(.contains % "    ```clj") lines))
    (t/is (some #(.contains % "      (defn f [] 1)") lines))))

;; ─── Tables ────────────────────────────────────────────────────────────────

(t/deftest test-markdown-table
  (let [m (md/make-markdown "| a | b |\n|---|---|\n| 1 | 2 |" :padding-x 0)
        lines (mapv strip-ansi (core/render m 30))]
    (t/is (some #(.contains % "┌───") lines))
    (t/is (some #(.contains % "│ a") lines))
    (t/is (some #(.contains % "│ 1") lines))
    (t/is (some #(.contains % "└───") lines))))

(t/deftest test-markdown-table-row-separators
  ;; pi draws a ┼ separator between every data row (not just after the
  ;; header): without it, a wrapped cell line is indistinguishable from the
  ;; first line of the next row
  (let [m (md/make-markdown "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |" :padding-x 0)
        lines (mapv strip-ansi (core/render m 30))]
    (t/is (= 7 (count lines)))
    (t/is (= "│ a │ b │" (nth lines 1)))
    (t/is (= "├───┼───┤" (nth lines 2)))
    (t/is (= "│ 1 │ 2 │" (nth lines 3)))
    (t/is (= "├───┼───┤" (nth lines 4)))
    (t/is (= "│ 3 │ 4 │" (nth lines 5)))
    (t/is (= "└───┴───┘" (nth lines 6)))
    ;; a single data row keeps the header separator but gets no row separator
    (let [m1 (md/make-markdown "| a | b |\n|---|---|\n| 1 | 2 |" :padding-x 0)
          l1 (mapv strip-ansi (core/render m1 30))]
      (t/is (= 5 (count l1)))
      (t/is (= "│ 1 │ 2 │" (nth l1 3)))
      (t/is (= "└───┴───┘" (nth l1 4))))))

(t/deftest test-markdown-table-border-styles
  ;; table glyphs come from a kmet.tui.border set (R5): the default is
  ;; byte-identical to the pre-R5 hardcoded glyphs, :ascii degrades every
  ;; junction, :hidden keeps the footprint with no ink
  (let [table "| a | b |\n|---|---|\n| 1 | 2 |"
        render (fn [style]
                 (mapv strip-ansi
                       (core/render (md/make-markdown table :padding-x 0 :border style) 30)))]
    (t/testing "the default set is :normal"
      (let [dflt (mapv strip-ansi (core/render (md/make-markdown table :padding-x 0) 30))]
        (t/is (= dflt (render :normal)))
        (t/is (= ["┌───┬───┐" "│ a │ b │" "├───┼───┤" "│ 1 │ 2 │" "└───┴───┘"]
                 (render nil)))))
    (t/testing ":ascii degrades every glyph"
      (t/is (= ["+---+---+" "| a | b |" "+---+---+" "| 1 | 2 |" "+---+---+"]
               (render :ascii))))
    (t/testing ":hidden keeps the cells and the footprint, drops the ink"
      (let [lines (render :hidden)]
        (t/is (= (apply str (repeat 9 \space)) (first lines)))
        (t/is (= "  a   b  " (second lines)))))
    (t/testing "a partial map merges over :normal"
      (t/is (= "┌===┬===┐" (first (render {:top "="})))))
    (t/testing ":none falls back to the default — a table is structural"
      (t/is (= (render :normal) (render :none))))
    (t/testing "an unknown style fails at construction"
      (t/is (thrown-with-msg? Exception #"unknown border style"
                              (md/make-markdown table :padding-x 0 :border :asci))))
    (t/testing "the DSL tag forwards :border"
      (t/is (= ["+---+---+"]
               (mapv strip-ansi
                     (take 1 (h/render-lines
                              [:markdown {:text table :padding-x 0 :border :ascii}]
                              30))))))))

(t/deftest test-markdown-table-single-column
  (let [m (md/make-markdown "| x |\n|---|\n| y |" :padding-x 0)
        lines (mapv strip-ansi (core/render m 20))]
    (t/is (some #(.contains % "┌") lines))
    (t/is (some #(.contains % "│ x") lines))
    (t/is (some #(.contains % "│ y") lines))
    (t/is (some #(.contains % "└") lines))))

(t/deftest test-markdown-table-narrow-fallback
  ;; Too narrow to draw borders: falls back to the raw markdown
  (let [m (md/make-markdown "| a | b |\n|---|---|\n| 1 | 2 |" :padding-x 0)
        lines (mapv strip-ansi (core/render m 6))]
    (t/is (some #(.contains % "|") lines))
    (t/is (not-any? #(.contains % "┌") lines))))

(t/deftest test-markdown-table-wrap
  ;; Long cells wrap inside their column
  (let [m (md/make-markdown "| name |\n|------|\n| a very long cell that wraps |" :padding-x 0)
        lines (mapv strip-ansi (core/render m 20))]
    (t/is (some #(.contains % "a very long") lines))
    (t/is (some #(.contains % "cell that") lines))))

(t/deftest test-markdown-table-header-wrap
  ;; Long header cells wrap, staying bold
  (let [m (md/make-markdown "| a very long header |\n|--------------------|\n| x |" :padding-x 0)
        lines (mapv strip-ansi (core/render m 16))
        with-ansi (core/render m 16)]
    (t/is (some #(.contains % "a very") lines))
    (t/is (some #(.contains % "long header") lines))
    (t/is (some #(.contains % "\u001b[1m") with-ansi))))

(t/deftest test-markdown-table-cell-formatting
  (let [m (md/make-markdown "| a |\n|---|\n| **b** |\n| `c` |" :padding-x 0)
        lines (mapv strip-ansi (core/render m 20))]
    (t/is (some #(.contains % "b") lines))
    (t/is (some #(.contains % "c") lines))))

(t/deftest test-markdown-table-themed-border
  ;; Theme-sourced tables style the border chars (dim) and keep headers bold
  (let [m (md/make-markdown "| a | b |\n|---|---|\n| 1 | 2 |"
                            :theme (theme/get-markdown-theme theme/dark-theme)
                            :padding-x 0)
        lines (core/render m 20)]
    (t/is (some #(.contains % (str (theme/get-fg-ansi theme/dark-theme :md-table-border) "┌")) lines))
    (t/is (some #(.contains % "\u001b[1ma\u001b[22m") lines))
    (t/is (some #(.contains % "│ 1") (map strip-ansi lines)))))

(t/deftest test-markdown-table-emoji-alignment
  ;; Emoji graphemes (ZWJ families, flags, skin tones) measure as ONE width-2
  ;; glyph, so columns size correctly and every table line has the same
  ;; visible width — the separators align in the terminal.
  (let [src "| 👨‍👩‍👧 | a |\n|--------|---|\n| b | c |"
        lines (map strip-ansi (core/render (md/make-markdown src :padding-x 0) 30))
        widths (mapv u/visible-width lines)]
    ;; col widths [2 1] + border overhead 7 = 10; the family emoji is 2 wide,
    ;; not 6 (codepoint summing)
    (t/is (= 10 (u/visible-width (first lines))))
    (t/is (apply = widths)))
  (doseq [src ["| 🇺🇸 | x |\n|------|---|\n| y | 2 |"
               "| 👍🏽 | x |\n|------|---|\n| y | 2 |"
               "| ☝🏽 | x |\n|------|---|\n| y | 2 |"
               "| ☝️🏽 | x |\n|------|---|\n| y | 2 |"
               "| 🚀 name |\n|--------|\n| x |"
               "| 你好 🚀 |\n|--------|\n| 世界 |"
               ;; Regression: ✓/✗ are narrow (1-col) dingbats — overcounting
               ;; them as 2 left the │ separators misaligned
               "| a ✓ | b |\n|-----|---|\n| 1 | 2 |"
               "| a ✗ | b |\n|-----|---|\n| 1 | 2 |"
               ;; ✔/☀/☑/❤ are text-presentation emoji: narrow bare, 2-wide
               ;; with VS16 — both forms must keep the columns aligned
               "| a ✔ | b |\n|-----|---|\n| 1 | 2 |"
               "| a ☑ | b |\n|-----|---|\n| 1 | 2 |"
               "| a ❤ | b |\n|-----|---|\n| 1 | 2 |"
               "| a ❤️ | b |\n|-----|---|\n| 1 | 2 |"]]
    (let [widths (mapv u/visible-width
                       (map strip-ansi (core/render (md/make-markdown src :padding-x 0) 30)))]
      (t/is (apply = widths) (pr-str src)))))

;; ─── Inline formatting ─────────────────────────────────────────────────────

(t/deftest test-markdown-bold
  (let [m (md/make-markdown "**bold text**" :padding-x 0)
        lines (core/render m 20)]
    (t/is (pos? (count lines)))
    (t/is (some #(.contains % "bold") lines))))

(t/deftest test-markdown-italic
  (let [m (md/make-markdown "*italic text*" :padding-x 0)
        lines (core/render m 20)]
    (t/is (some #(.contains % "italic") lines))))

(t/deftest test-markdown-code-inline
  (let [m (md/make-markdown "Use `code` here" :padding-x 0)
        lines (core/render m 20)]
    (t/is (some #(.contains % "code") lines))))

;; ─── Lists ─────────────────────────────────────────────────────────────────

(t/deftest test-markdown-unordered-list
  (let [m (md/make-markdown "- item one\n- item two" :padding-x 0)
        lines (core/render m 20)]
    (t/is (some #(.contains % "item one") lines))
    (t/is (some #(.contains % "item two") lines))))

(t/deftest test-markdown-ordered-list
  (let [m (md/make-markdown "1. first\n2. second" :padding-x 0)
        lines (core/render m 20)]
    (t/is (some #(.contains % "first") lines))
    (t/is (some #(.contains % "second") lines))))

;; ─── Nested lists ──────────────────────────────────────────────────────────

(t/deftest test-markdown-nested-list-indent
  (let [m (md/make-markdown "- a\n  - b\n  - c\n- d" :padding-x 0)
        lines (mapv strip-ansi (core/render m 40))]
    (t/is (some #(.contains % "• a") lines))
    (t/is (some #(.contains % "    • b") lines))
    (t/is (some #(.contains % "    • c") lines))
    (t/is (some #(.contains % "• d") lines))))

(t/deftest test-markdown-deeply-nested-list-indent
  (let [m (md/make-markdown "- l1\n  - l2\n    - l3\n      - l4" :padding-x 0)
        lines (mapv strip-ansi (core/render m 40))]
    (t/is (some #(.contains % "• l1") lines))
    (t/is (some #(.contains % "    • l2") lines))
    (t/is (some #(.contains % "        • l3") lines))
    (t/is (some #(.contains % "            • l4") lines))))

(t/deftest test-markdown-mixed-nested-list
  (let [m (md/make-markdown "1. ordered\n   - nested\n   - another\n2. second" :padding-x 0)
        lines (mapv strip-ansi (core/render m 40))]
    (t/is (some #(.contains % "1. ordered") lines))
    (t/is (some #(.contains % "    • nested") lines))
    (t/is (some #(.contains % "    • another") lines))
    (t/is (some #(.contains % "2. second") lines))))

(t/deftest test-markdown-list-wraps-long-items
  ;; Long items wrap; continuation lines align to the marker column
  ;; (item width = content width 20 - marker width 2 = 18)
  (let [m (md/make-markdown "- this is a very long item that definitely wraps" :padding-x 0)
        lines (mapv strip-ansi (core/render m 20))]
    (t/is (some #(.contains % "• this is a very") lines))
    (t/is (some #(.contains % "  long item that") lines))
    (t/is (some #(.contains % "  definitely wraps") lines))))

(t/deftest test-markdown-list-continuation-lines
  (let [m (md/make-markdown "- first line\n  continuation\n- second" :padding-x 0)
        lines (mapv strip-ansi (core/render m 40))]
    (t/is (some #(.contains % "• first line") lines))
    (t/is (some #(.contains % "  continuation") lines))
    (t/is (some #(.contains % "• second") lines))))

(t/deftest test-markdown-loose-list-blank-lines
  ;; A blank between items renders as an empty line, items stay one list
  (let [m (md/make-markdown "- a\n\n- b" :padding-x 0)
        lines (mapv strip-ansi (core/render m 40))]
    (t/is (some #(.contains % "• a") lines))
    (t/is (some #(.contains % "• b") lines))
    (t/is (some #(clojure.string/blank? %) lines))))

(t/deftest test-markdown-nested-list-after-blank
  (let [m (md/make-markdown "- a\n  - b\n\n  - c" :padding-x 0)
        lines (mapv strip-ansi (core/render m 40))]
    (t/is (some #(.contains % "• a") lines))
    (t/is (some #(.contains % "    • b") lines))
    (t/is (some #(.contains % "    • c") lines))))

(t/deftest test-markdown-ordered-list-numbering
  ;; Ordered items number 1..n, skipping blank pseudo-items
  (let [m (md/make-markdown "1. first\n\n2. second" :padding-x 0)
        lines (mapv strip-ansi (core/render m 40))]
    (t/is (some #(.contains % "1. first") lines))
    (t/is (some #(.contains % "2. second") lines))))

(t/deftest test-markdown-ordered-list-multidigit
  (let [m (md/make-markdown (str/join "\n" (map #(str % ". item") (range 1 11))) :padding-x 0)
        lines (mapv strip-ansi (core/render m 40))]
    (t/is (some #(.contains % "10. item") lines))
    (t/is (some #(.contains % "1. item") lines))))

(t/deftest test-markdown-nested-list-wrap-at-depth
  ;; A long nested item wraps and aligns under its own marker column
  (let [m (md/make-markdown "- a\n  - this nested item is very long and wraps" :padding-x 0)
        lines (mapv strip-ansi (core/render m 20))]
    (t/is (some #(.contains % "    • this nested") lines))
    (t/is (some #(.contains % "      item is very") lines))
    (t/is (some #(.contains % "      long and") lines))
    (t/is (some #(.contains % "      wraps") lines))))

;; ─── Misc block rendering ──────────────────────────────────────────────────

(t/deftest test-markdown-hr-spaced
  (let [m (md/make-markdown "* * *" :padding-x 0)
        lines (mapv strip-ansi (core/render m 20))]
    (t/is (some #(.contains % "───") lines))))

(t/deftest test-markdown-blockquote-inline-formatting
  (let [m (md/make-markdown "> **bold** quote" :padding-x 0)
        lines (mapv strip-ansi (core/render m 30))]
    (t/is (some #(.contains % "bold") lines))
    (t/is (some #(.contains % "quote") lines))))

(t/deftest test-markdown-link-url-shown
  (let [prev (timg/get-capabilities)]
    (try
      (timg/set-capabilities! {:images nil :true-color true :hyperlinks false})
      (let [m (md/make-markdown "[text](http://example.com)" :padding-x 0)
            lines (mapv strip-ansi (core/render m 40))]
        (t/is (some #(.contains % "text") lines))
        (t/is (some #(.contains % "(http://example.com)") lines)))
      (finally
        (timg/set-capabilities! prev)))))

(t/deftest test-markdown-kitchen-sink
  ;; One document exercising every block type end-to-end.
  ;; Pinned to hyperlinks=false: with OSC 8 support pi renders only the
  ;; clickable link label, so the "(url)" fallback text is absent by design.
  (let [prev (timg/get-capabilities)]
    (try
      (timg/set-capabilities! {:images nil :true-color true :hyperlinks false})
      (let [src (str/join "\n"
                          ["# Title"
                           ""
                           "Some **bold** and *italic* with `code` and [link](http://x)."
                           ""
                           "| col a | col b |"
                           "|:------|-------:|"
                           "| 1 | 2 |"
                           ""
                           "- item one"
                           "  - nested"
                           "- item two"
                           ""
                           "> quote"
                           ""
                           "```clojure"
                           "(defn f [] 1)"
                           "```"
                           ""
                           "---"])
            lines (mapv strip-ansi (core/render (md/make-markdown src :padding-x 0) 30))
            all (str/join "\n" lines)]
        (doseq [needle ["Title" "bold" "italic" "code" "link" "(http://x)"
                        "┌" "col a" "│ 1"
                        "• item one" "    • nested" "• item two"
                        "▎quote"
                        "```clojure" "(defn f [] 1)"
                        "───"]]
          (t/is (str/includes? all needle) (str "missing: " needle))))
      (finally
        (timg/set-capabilities! prev)))))

;; ─── Robustness: malformed input must never throw ──────────────────────────

(def ^:private nasty-corpus
  ["" "   " "\t" "\n\n\n" "a\r\nb\r\n"
   "####### seven" "###### six" "#"
   "************" "**" "*" "*x" "~~~" "~~~~" "~~~~~~" "~~x~~"
   "-" "1." "* item" "- " "- [ ] task" "1) item"
   "|" "||||" "| a || b |" "| `x|y` | 2 |"
   "> " ">" ">> x" "> > > deep"
   "```" "````" "```\n```" "```\n\n\n```" "```\ncode" "~~~\nx\n~~~"
   "[a](b)(c)" "[]()" "![alt](img)" "[x]: url" "`a`b`"
   "    four spaces indent" "a  b" "\t\ttab tab"
   (apply str (repeat 300 "x"))
   "| a | b |\n|---|---|\n| | |"
   "| 你好 | 🚀 |\n|------|-----|\n| 世界 | x |"
   "[text](url with spaces)"
   "- a\n          - b\n                    - c\n                              - d\n                                        - e\n                                                  - f"
   "1. a\n2. b\n3. c\n4. d\n5. e\n6. f\n7. g\n8. h\n9. i\n10. j\n11. k"
   "**a**b**c**" "~~strike~~ and ~~ unclosed"
   "- a\n  ```\n  x\n  ```\n- b"
   "```clojure\n(defn f [] 1)\n```\n```txt\nraw\n```"
   "| a |\n|---|\n| 1 | 2 |"
   "> - list in quote" "- > quote in list"
   "[**bold link**](https://x.com?q=1&r=2)"])

(t/deftest test-markdown-robustness-never-throws
  ;; Every malformed input must parse and render without throwing, and the
  ;; rendered output must be a vector of strings (may be empty for
  ;; whitespace-only input, matching pi/marked).
  (doseq [src nasty-corpus]
    (let [lines (core/render (md/make-markdown src :padding-x 0) 24)]
      (t/is (vector? lines))
      (t/is (every? string? lines)))))

(t/deftest test-markdown-robustness-deterministic
  ;; Same input + width must produce identical output (pure render).
  (doseq [src nasty-corpus]
    (let [a (core/render (md/make-markdown src :padding-x 0) 40)
          b (core/render (md/make-markdown src :padding-x 0) 40)]
      (t/is (= a b) (pr-str src)))))

(t/deftest test-markdown-robustness-across-widths
  ;; Render at every width 1..40; must never throw and stay a string vector.
  (doseq [src nasty-corpus]
    (doseq [w (range 1 41)]
      (let [lines (core/render (md/make-markdown src :padding-x 0) w)]
        (t/is (vector? lines))
        (t/is (every? string? lines))))))

;; ─── Blockquotes ───────────────────────────────────────────────────────────

(t/deftest test-markdown-blockquote
  (let [m (md/make-markdown "> quoted text" :padding-x 0)
        lines (core/render m 30)]
    (t/is (some #(.contains % "quoted") lines))))

;; ─── Horizontal rules ──────────────────────────────────────────────────────

(t/deftest test-markdown-hr
  (let [m (md/make-markdown "---" :padding-x 0)
        lines (core/render m 20)]
    (t/is (pos? (count lines)))))

;; ─── Links ─────────────────────────────────────────────────────────────────

(t/deftest test-markdown-link
  (let [m (md/make-markdown "[text](http://example.com)" :padding-x 0)
        lines (core/render m 40)]
    (t/is (some #(.contains % "text") lines))))

;; ─── set-text! / append! ───────────────────────────────────────────────────

(t/deftest test-markdown-set-text
  (let [m (md/make-markdown "old")]
    (md/markdown-set-text! m "new")
    (t/is (= "new" (md/markdown-get-text m)))))

(t/deftest test-markdown-append
  (let [m (md/make-markdown "line1")]
    (md/markdown-append! m "line2")
    (t/is (.contains (md/markdown-get-text m) "line1"))
    (t/is (.contains (md/markdown-get-text m) "line2"))))

;; ─── Invalidate ────────────────────────────────────────────────────────────

(t/deftest test-markdown-invalidate
  (let [m (md/make-markdown "hello" :padding-x 0)]
    (core/render m 20)
    (t/is (some? @(:cache-atom m)))
    (core/invalidate m)
    (t/is (nil? @(:cache-atom m)))))

;; ─── Render caching ────────────────────────────────────────────────────────

(t/deftest test-markdown-render-cache
  (let [m (md/make-markdown "hello world" :padding-x 0)
        lines1 (core/render m 30)
        lines2 (core/render m 30)]
    (t/is (= lines1 lines2))))

;; ─── Default theme ─────────────────────────────────────────────────────────

(t/deftest test-markdown-default-theme
  (t/is (some? md/default-theme)))
