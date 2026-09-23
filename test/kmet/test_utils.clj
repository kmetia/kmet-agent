(ns kmet.test-utils
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tui.utils :as u]))

(defn slash
  "The /-separated form of path values — strings, Paths/Files, or maps, sets
   and vectors of them. kmet renders Windows paths with \\ on bb and with /
   on jolt, so path expectations compare through this on both sides."
  [x]
  (cond
    (nil? x) nil
    (string? x) (str/replace x "\\" "/")
    (map? x) (into {} (map (fn [[k v]] [k (slash v)])) x)
    (set? x) (into #{} (map slash) x)
    (sequential? x) (mapv slash x)
    :else (str/replace (str x) "\\" "/")))

(t/deftest test-visible-width
  (t/is (= 0 (u/visible-width "")))
  (t/is (= 5 (u/visible-width "hello")))
  (t/is (= 4 (u/visible-width "中文")))
  (t/is (= 2 (u/visible-width "🍎")))
  (t/is (= 6 (u/visible-width "中文🍎")))   ;; 2+2+2=6
  (t/is (= 6 (u/visible-width "ab\tc"))))   ;; tab expands to 3 spaces (pi)

(def ^:private ansi-corpus
  "Strings the scanner/regex equivalence tests walk at every index: matched
   CSI/OSC, private-parameter CSI, unterminated OSC, a lone ESC, CJK, tabs."
  ["" "plain" "a\tb" "中文"
   "\u001b[31mred\u001b[0m"
   "\u001b[38;5;196mx\u001b[39m"
   "\u001b[?25lprivate\u001b[?25h"
   "\u001b]8;;https://x.example/\u0007link\u001b]8;;\u0007"
   "\u001b]0;title\u001b\\after"
   "\u001b]0;unterminated"
   "\u001b["
   "\u001b"
   "\u001bX"
   "\u001b[1;31"
   "\u001b[1;31m"
   "pre\u001b[1moops\u001b[m\u001b[?25l"
   "\u001b[?2026h\u001b[1;1H\u001b[?2026l"
   (str "你好" "\u001b[31m" "你好")])

(t/deftest test-strip-ansi-host-equivalence
  ;; The Jolt path strips ANSI with a hand-rolled scanner (strip-ansi-native)
  ;; while bb/JVM keep the regex strip. The scanner must accept exactly the
  ;; regex's language — including the sequences the regex does NOT match
  ;; (private-parameter CSI, unterminated OSC, a lone ESC), which must stay
  ;; in the output as literal text. Both implementations run on both hosts,
  ;; so this test pins them against each other wherever the suite runs.
  (let [regex-strip (fn [s]
                      (str/replace s
                                   #"\u001b\[[0-9;]*[a-zA-Z]|\u001b\][^\u0007\u001b\u009c]*(?:\u001b\\|\u0007|\u009c)"
                                   ""))
        native-strip (var-get #'u/strip-ansi-native)
        corpus ansi-corpus]
    (doseq [s corpus]
      (t/is (= (regex-strip s) (native-strip s)) (pr-str s))))
  ;; and the public width path agrees on strip-needing input
  (t/is (= 3 (u/visible-width "\u001b[31mabc\u001b[0m")))
  ;; private-parameter CSI is NOT ANSI-CODE-RE: ESC measures 0, the rest
  ;; sorts as literal text (the documented quirk both strippers share)
  (t/is (= 5 (u/visible-width "\u001b[?25l"))))

(t/deftest test-ansi-code-at-host-equivalence
  ;; ansi-code-at's Jolt side is the hand-rolled scanner
  ;; (ansi-code-at-native); bb/JVM keep the anchored regex matcher (match-at +
  ;; ANSI-CODE-RE). Both must agree at every index of every corpus string —
  ;; including the indices where neither matches.
  (let [ansi-re #"\u001b\[[0-9;]*[a-zA-Z]|\u001b\][^\u0007\u001b\u009c]*(?:\u001b\\|\u0007|\u009c)"
        regex-code (fn [s i]
                     (when (and (< i (count s)) (= \u001b (nth s i)))
                       (let [m (re-matcher ansi-re s)]
                         (when (and (.find m i) (= i (.start m)))
                           [(.group m) (- (.end m) i)]))))
        native-code (var-get #'u/ansi-code-at-native)]
    (doseq [s ansi-corpus
            i (range (inc (count s)))]
      (t/is (= (regex-code s i) (native-code s i))
            (str (pr-str s) " @" i)))))

(t/deftest test-visible-width-narrow-dingbats
  ;; Regression: the coarse emoji block ranges (0x2600-0x27BF etc.) counted
  ;; text-presentation dingbats as 2 columns, so background padding ended a
  ;; column short (e.g. "✓" in the model selector) and markdown table
  ;; separators misaligned. Only default-emoji-presentation (Emoji_Presentation
  ;; =Yes, e.g. ⌚ ⏰ ⚽ ☕ 😀) / EAW-wide chars are 2-wide; the text-presentation
  ;; forms (✔ ☑ ☀ ❤ …) render narrow bare and only turn 2-wide with U+FE0F.
  (doseq [s ["✓" "✗" "★" "☆" "❶" "➤" "✦" "✔" "☑" "☀" "❤" "⌚" "⏰"
             "⚽" "☕" "😀"]]
    (t/is (= (if (contains? #{"⌚" "⏰" "⚽" "☕" "😀"} s) 2 1)
             (u/visible-width s))
          (str "width of " (pr-str s)))))

(t/deftest test-visible-width-vs16
  ;; Text-presentation bases stay narrow bare but turn 2-wide with U+FE0F
  ;; (©️ ™️ ↔️ ▶️ ▪️ ✔️ ☀️ ☑️ ❤️ …). Default-presentation emoji and EAW-wide
  ;; chars (⬛ ⬜ …) are already 2-wide bare.
  (t/is (= 1 (u/visible-width "©")))
  (t/is (= 2 (u/visible-width "©️")))
  (t/is (= 2 (u/visible-width "™️")))
  (t/is (= 2 (u/visible-width "↔️")))
  (t/is (= 1 (u/visible-width "▶")))
  (t/is (= 2 (u/visible-width "▶️")))
  (t/is (= 2 (u/visible-width "▪️")))
  (t/is (= 1 (u/visible-width "✔")))
  (t/is (= 2 (u/visible-width "✔️")))
  (t/is (= 1 (u/visible-width "❤")))
  (t/is (= 2 (u/visible-width "❤️")))
  (t/is (= 2 (u/visible-width "⬛")))
  (t/is (= 2 (u/visible-width "⬛️"))))

(t/deftest test-visible-width-zwj-vs16-chain
  ;; A VS16 between the emoji base and the ZWJ must not break the chain
  ;; (❤️‍🔥 🏳️‍🌈); plain wide chars never chain (漢‍🔥 = 4); keycaps keep 2.
  (t/is (= 2 (u/visible-width "❤️‍🔥")))
  (t/is (= 2 (u/visible-width "🏳️‍🌈")))
  (t/is (= 2 (u/visible-width "👨‍👩‍👧‍👦")))
  (t/is (= 4 (u/visible-width "漢‍🔥")))
  (t/is (= 2 (u/visible-width "1️⃣"))))

(t/deftest test-visible-width-skin-tone-modifiers
  ;; A skin-tone modifier on an emoji base is part of the glyph: default-
  ;; presentation bases are wide already and zero the modifier via prev-w;
  ;; text-presentation bases (☝ ✌ 🏋 …) pair with it through the
  ;; modifier-base set, FE0F or not. A lone modifier keeps its 2 columns.
  (t/is (= 2 (u/visible-width "👍🏽")))
  (t/is (= 2 (u/visible-width "☝🏽")))
  (t/is (= 2 (u/visible-width "☝️🏽")))
  (t/is (= 2 (u/visible-width "✌🏽")))
  (t/is (= 2 (u/visible-width "🏋🏽")))
  (t/is (= 2 (u/visible-width "🏽"))))

(t/deftest test-visible-width-halfwidth
  ;; FF61..FFDC are halfwidth forms (ｱｲｳ…) — narrow, not 2 columns.
  (t/is (= 1 (u/visible-width "ｱ")))
  (t/is (= 3 (u/visible-width "ｱｲｳ")))
  (t/is (= 2 (u/visible-width "Ａ"))))

(t/deftest test-apply-background-to-line
  ;; The reported bug: a line ending in a non-ASCII narrow char (✓) must pad
  ;; to full width so the background reaches the line end.
  (t/is (= 4 (u/visible-width (u/apply-background-to-line "a✓" 4 nil))))
  (t/is (= 4 (u/visible-width (u/apply-background-to-line "a✔" 4 nil))))
  (t/is (= 4 (u/visible-width (u/apply-background-to-line "中" 4 nil)))))

(t/deftest test-truncate-to-width
  (t/is (= "hello" (u/truncate-to-width "hello" 10)))
  (t/is (= "" (u/truncate-to-width "" 5)))
  (t/is (<= (u/visible-width (u/truncate-to-width "hello world" 5)) 5)))

(t/deftest test-truncate-to-width-pi-parity
  ;; pi: truncateToWidth guards on max-width — fitting text is never
  ;; truncated even when an ellipsis is requested
  (t/is (= "abcdef" (u/truncate-to-width "abcdef" 6 "...")))
  (t/is (= "a very ..." (u/truncate-to-width "a very long line here" 10 "..."))
        "kept prefix + ellipsis = max-width")
  (t/is (= "hello..." (u/truncate-to-width "hello world foo" 8 "...")))
  ;; pi: truncateFragmentToWidth — an ellipsis wider than max-width is clipped
  (t/is (= ".." (u/truncate-to-width "hello world" 2 "...")))
  (t/is (= "" (u/truncate-to-width "hello world" 0 "..."))))

(t/deftest test-truncate-to-width-pad
  ;; pi: truncateToWidth's pad flag — the result is right-padded with
  ;; spaces to exactly max-width, keeping box borders aligned for short
  ;; rows (the mcp-adapter panel renders every row padded)
  (t/is (= "hello     " (u/truncate-to-width "hello" 10 "…" true)))
  (t/is (= "hello" (u/truncate-to-width "hello" 10 "…" false)))
  (t/is (= "a very ..." (u/truncate-to-width "a very long line here" 10 "..." true))
        "truncated result already fills max-width — pad is a no-op")
  (t/is (= 10 (u/visible-width (u/truncate-to-width "hello" 10 "…" true))))
  (t/is (= 10 (u/visible-width (u/truncate-to-width "\u001b[31mhello\u001b[0m" 10 "…" true)))
        "ANSI codes are preserved and don't count toward the pad")
  (t/is (= 8 (u/visible-width (u/truncate-to-width "中文服务器" 8 "…" true)))
        "wide chars pad correctly")
  (t/is (= "     " (u/truncate-to-width "" 5 "…" true)))
  (t/is (= "" (u/truncate-to-width "" 5 "…" false))))

(t/deftest test-truncate-to-width-osc-8-close
  ;; pi: getActiveOsc8Close — cutting through a hyperlink label must close
  ;; the link before the ellipsis so following text isn't swallowed by it
  (t/testing "BEL-terminated link is closed with BEL"
    (let [link "\u001b]8;;https://example.com\u0007"
          result (u/truncate-to-width (str link "hello world") 9 "…")]
      (t/is (= (str link "hello wo\u001b]8;;\u0007…") result))
      (t/is (= 9 (u/visible-width result)))))
  (t/testing "ST-terminated link is closed with ST"
    (let [link "\u001b]8;;https://example.com\u001b\\"
          result (u/truncate-to-width (str link "hello world") 5 "…")]
      (t/is (= (str link "hell\u001b]8;;\u001b\\…") result))
      (t/is (= 5 (u/visible-width result)))))
  (t/testing "an already-closed link is not closed again"
    (let [text "\u001b]8;;https://example.com\u0007ok\u001b]8;;\u0007hello world"
          result (u/truncate-to-width text 6 "…")]
      (t/is (str/ends-with? result "hel…"))
      (t/is (not (str/includes? result "\u001b]8;;\u0007\u001b]8;;\u0007")) "no doubled close")
      (t/is (= 6 (u/visible-width result))))))

(t/deftest test-wrap-long-unbreakable-word
  ;; A single word longer than the width wraps across lines (pi: breakLongWord)
  ;; instead of being clipped — every character is preserved.
  (let [lines (u/wrap-text-with-ansi (apply str (repeat 60 "x")) 22)]
    (t/is (= [22 22 16] (mapv u/visible-width lines)))
    (t/is (= 60 (apply + (map u/visible-width lines))) "no characters lost")))

(t/deftest test-wrap-no-spurious-blank
  ;; Regression: a too-long word at line start used to emit an empty line.
  (let [lines (u/wrap-text-with-ansi (str (apply str (repeat 60 "x")) " more") 22)]
    (t/is (not-any? empty? lines) "no blank lines from word wrapping")
    (t/is (= 65 (apply + (map u/visible-width lines))) "all characters kept")
    (t/is (clojure.string/includes? (last lines) "more")
          "a following word joins the long word's last piece")))

(t/deftest test-wrap-ansi-survives-break
  ;; The fg escape on a styled long word is re-applied to continuation lines.
  (let [styled (str "\u001b[38;2;212;212;212m" (apply str (repeat 60 "x")) "\u001b[39m")
        lines (u/wrap-text-with-ansi styled 22)]
    (t/is (= 60 (apply + (map u/visible-width lines))))
    (t/is (clojure.string/includes? (first lines) "\u001b[38")
          "first piece keeps the fg code")
    (t/is (clojure.string/includes? (second lines) "\u001b[38")
          "continuation piece is re-styled")))

(t/deftest test-wrap-ansi-multiword-styled-line
  ;; A styled multi-word line (edit diff / bash output: one fg code at the
  ;; start, reset at the end) must re-open the style on every wrapped
  ;; continuation line — previously only the first line kept it.
  (let [styled (str "\u001b[38;5;245m" (clojure.string/join " " (repeat 8 "word123456")) "\u001b[39m")
        lines (u/wrap-text-with-ansi styled 20)]
    (t/is (>= (count lines) 2) "line wraps")
    (doseq [l lines]
      (t/is (clojure.string/includes? l "\u001b[38")
            (str "every wrapped line is re-styled: " (pr-str l))))
    (t/is (= 87 (apply + (map u/visible-width lines))) "all characters kept")))

(t/deftest test-wrap-ansi-fast-path-first-piece-styled
  ;; A styled word that must be broken across lines (ASCII fast path) — the
  ;; FIRST piece starts a fresh line and must re-open the style, not just
  ;; the continuations (pi: breakLongWord starts with getActiveCodes).
  (let [styled "\u001b[31mone two three\u001b[0m"
        lines (u/wrap-text-with-ansi styled 3)]
    (doseq [l lines]
      (t/is (clojure.string/includes? l "\u001b[31m")
            (str "every piece is re-styled: " (pr-str l))))
    (t/is (= 13 (apply + (map u/visible-width lines))) "all characters kept")))

(t/deftest test-wrap-ansi-styles-survive-literal-newline
  ;; A style left open on one line is re-emitted on the next input line (pi:
  ;; wrapTextWithAnsi carries the tracker across input lines).
  (let [styled "\u001b[31mline one words here\nline two words here\u001b[0m"
        lines (u/wrap-text-with-ansi styled 12)]
    (t/is (>= (count lines) 2))
    (doseq [l lines]
      (t/is (clojure.string/includes? l "\u001b[31m")
            (str "each line is re-styled: " (pr-str l))))    (t/is (= 38 (apply + (map u/visible-width lines))))))

(t/deftest test-wrap-cjk-width
  (let [lines (u/wrap-text-with-ansi (clojure.string/join "" (repeat 15 "汉字")) 22)]
    (t/is (= [22 22 16] (mapv u/visible-width lines))
          "wide characters counted as 2 columns")
    (t/is (= 60 (apply + (map u/visible-width lines))))))

;; ─── ANSI-aware slicing + compositing (HStack support) ─────────────────────

(t/deftest test-slice-with-width-plain
  (t/is (= {:text "hello" :width 5} (u/slice-with-width "hello world" 0 5 :strict? true)))
  (t/is (= {:text "world" :width 5} (u/slice-with-width "hello world" 6 5 :strict? true)))
  (t/is (= {:text "" :width 0} (u/slice-with-width "hello" 0 0 :strict? true))))

(t/deftest test-slice-with-width-ansi
  ;; ANSI codes before the window are prepended to the first kept character
  (let [styled (str "\u001b[31m" "red" "\u001b[39m" "tail")
        s (u/slice-with-width styled 0 3 :strict? true)]
    (t/is (= 3 (:width s)))
    (t/is (str/includes? (:text s) "red"))))

(t/deftest test-slice-with-width-strict
  ;; strict? excludes a wide char crossing the end boundary
  (let [s (u/slice-with-width "中文x" 0 3 :strict? true)]
    (t/is (= 2 (:width s)) "second 2-wide char would cross col 3 → excluded")))

(t/deftest test-composite-line-plain
  ;; the overlay region is SGR-isolated from the base (SEGMENT-RESET before
  ;; and after it, pi: compositeTuiLine), so the raw output carries resets
  (t/is (= (str "..." u/SEGMENT-RESET "X" u/SEGMENT-RESET "......")
           (u/composite-line ".........." "X" 3 1 10)))
  (t/is (= (str "ab " u/SEGMENT-RESET "cd" u/SEGMENT-RESET)
           (str/trim (u/composite-line "ab        " "cd" 3 2 10)))))

(t/deftest test-composite-line-ansi-overlay
  (let [out (u/composite-line ".........." (str "\u001b[31m" "XX" "\u001b[39m") 3 2 10)]
    (t/is (= 10 (u/visible-width out)))
    (t/is (str/includes? out "\u001b[31m") "overlay styling kept")))

(t/deftest test-composite-line-inherits-base-style
  ;; The after segment keeps the base's background across the overlay region
  (let [bg (str "\u001b[41m" "abcdefghij" "\u001b[49m")
        out (u/composite-line bg "XX" 3 2 10)]
    (t/is (= 10 (u/visible-width out)))
    (t/is (str/includes? out "abc"))
    (t/is (str/includes? out "fghij"))
    (t/is (str/includes? out "\u001b[41m") "after content restyled with the base bg")))

(t/deftest test-sgr-state-at
  (t/is (= "" (u/sgr-state-at "plain" 0)))
  (let [styled (str "\u001b[31m" "ab" "\u001b[39m" "cd")]
    (t/is (= "\u001b[31m" (u/sgr-state-at styled 0)))
    (t/is (= "\u001b[31m" (u/sgr-state-at styled 1)))
    (t/is (= "" (u/sgr-state-at styled 2)) "reset applied at col 2")))

(t/deftest test-composite-line-image-passthrough
  ;; pi: compositeTuiLine returns kitty image lines untouched
  (let [img-line (str "\u001b_Gf=100,m=0;" "aGVsbG8=" "\u001b\\" "rest")]
    (t/is (= img-line (u/composite-line img-line "XX" 3 2 10)))))

(t/deftest test-slice-with-width-graphemes-agree-with-visible-width
  ;; skin-tone cluster and flag pair measure 2 columns in both
  (let [s "👍🏽"]
    (t/is (= 2 (:width (u/slice-with-width s 0 10 :strict? true))))
    (t/is (= (u/visible-width s) (:width (u/slice-with-width s 0 10 :strict? true)))))
  (t/is (= 2 (:width (u/slice-with-width "🇺🇸" 0 10 :strict? true)))))
