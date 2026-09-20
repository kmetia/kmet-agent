(ns kmet.tui.test-utils
  "Width primitives: the grapheme fast path and the walker must agree on every
   input — the arithmetic path is only taken for strings that contain none of
   the walker's special code points, and a disagreement is a silent layout
   bug."
  (:require [clojure.test :as t]
            [kmet.tui.utils :as u]))

(def ^:private plain-width #'u/visible-width-plain)
(def ^:private grapheme-width #'u/grapheme-width-plain)

(def ^:private boring-corpus
  ["─── box drawing ───"
   "⎿  result line"
   "⏺ assistant"
   "caf\u00e9 cr\u00e8me"
   "e\u0301 combining"
   "… … …"
   "│ ├ └ ◆ ▸"
   "①②③ \u2460"
   "\u2014 em dash \u2013 en dash"
   "ascii only"
   "a\tb\ttabs"
   "a\u0007b\u0001belt"
   "a\nb\r\nc"
   "mixed ─── ascii \u00e9 ───"])

(def ^:private complex-corpus
  ["\u4e2d\u6587"                      ;; CJK
   "\uff21\uff22"                      ;; fullwidth (cjk?)
   "\uac00\ud7a3"                      ;; Hangul
   "\ufe30\ufe4f"                      ;; CJK compatibility
   "\ud83d\ude80 rocket"               ;; astral emoji
   "before \ud83d\ude00 after"         ;; emoji in text
   "\u23fa\ufe0f"                      ;; VS16 upgrade
   "\u00a9\ufe0f"                      ;; VS16 from the text-base set
   "\u261d\ud83c\udfff"                ;; text base + skin tone
   "\ud83c\uddef\ud83c\uddf5"          ;; regional-indicator flag
   "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67" ;; ZWJ family
   "a\u200bb"                          ;; ZWJ / zero width
   "a\u2060b"                          ;; word joiner
   "x\ud800\udc00y"                    ;; astral: surrogate pair (U+10000)
   "m\u0301"                           ;; combining accent
   "\udb40\udd00"                      ;; VS supplement (U+E0100)
   "tab\tthen \u4e2d"])

(defn- boundary-chars
  "Every range boundary ±1 in the walker's tables, plus ASCII and a couple of
   astral code points — the randomised corpus the boundary check needs."
  []
  (let [rs (concat [[0x2E80 0x9FFF] [0xAC00 0xD7AF] [0xFE30 0xFE4F]
                    [0xFF00 0xFF60] [0xFFE0 0xFFE6]]
                   [[0x200B 0x200F] [0x2060 0x2064] [0xFE00 0xFE0F]
                    [0xE0100 0xE01EF]])
        cps (for [[a b] rs d [-1 0 1]] (+ (if (zero? (rand-int 2)) a b) d))]
    (mapv (fn [cp] (String. (Character/toChars cp)))
          (concat cps (map int "abcXY 019 \t\n\u0007")
                  [0x1F300 0x1F1E6 0x1F3FB 0x10000 0x20000]))))

(t/deftest fast-path-agrees-with-the-walker
  (t/testing "the hand-picked cases"
    (doseq [s (concat boring-corpus complex-corpus ["" " " "\t" "\n"])]
      (t/is (= (grapheme-width s) (plain-width s))
            (str "mismatch for " (pr-str s)))))
  (t/testing "randomised boundary mixes — any range off-by-one shows up here"
    (let [pool (boundary-chars)]
      (dotimes [_ 400]
        (let [s (apply str (repeatedly (rand-int 9) #(rand-nth pool)))]
          (t/is (= (grapheme-width s) (plain-width s))
                (str "mismatch for " (pr-str s) " "
                     (pr-str (mapv int s)))))))))

(t/deftest styled-width-uses-the-same-path
  (t/testing "ANSI codes wrap the same text without changing the width"
    (doseq [s (concat boring-corpus complex-corpus)]
      (let [styled (str "\u001b[1m" s "\u001b[38;5;9m" s "\u001b[39m\u001b[22m")]
        (t/is (= (+ (plain-width s) (plain-width s))
                 (u/visible-width styled))
              (str "styled mismatch for " (pr-str s)))))))

(t/deftest plain-lines-skip-the-walker
  (t/testing "a boring non-ASCII line never enters the grapheme walker"
    (let [calls (atom 0)]
      (with-redefs-fn {#'u/grapheme-width-plain (fn [& _] (swap! calls inc))}
        (fn []
          (#'u/visible-width-plain "─── ⏺ ⎿ café … ───")
          (#'u/visible-width-plain "plain ascii")))
      (t/is (zero? @calls) "the arithmetic path handled both"))))
