(ns kmet.tui.utils
  "Text width calculation and wrapping utilities."
  (:require [clojure.string :as str]
            [kmet.libs.terminal-image :as img]))

;; ─── Cursor marker ────────────────────────────────────────────────────────
;; Zero-width APC sequence emitted at cursor position for IME positioning.
;; TUI finds this marker and positions the hardware cursor there.
(def ^:const CURSOR-MARKER "\u001b_km:c\u0007")

;; ─── ANSI escape sequences ────────────────────────────────────────────────
;; CSI (ESC [...) and OSC (ESC ] ... ST) sequences. OSC 8 hyperlinks
;; (ESC ]8;;url ESC\) are zero-width metadata but must be recognized so they
;; don't count toward visible width. OSC is terminated by BEL, ESC\ or 0x9C
;; (pi: ansi-regex).
(def ^:private ANSI-CODE-RE
  #"\u001b\[[0-9;]*[a-zA-Z]|\u001b\][^\u0007\u001b\u009c]*(?:\u001b\\|\u0007|\u009c)")

(defn- ansi-sequence-end
  "End index (exclusive) of the ANSI escape sequence starting at index I of
   S when one starts there, nil otherwise. Hand-rolled equivalent of
   ANSI-CODE-RE anchored at I (same accepted CSI/OSC language, including
   what the regex does NOT match: private-parameter CSI like \"ESC[?25l\"
   has no regex match and must stay literal text). Used on Jolt only, where
   the regex engine is the slowest primitive; the utils test suite pins
   this scanner and the regex against each other on a corpus."
  [s i]
  (let [n (count s)]
    (when (and (< i n) (= \u001b (nth s i)) (< (inc i) n))
      (let [c (nth s (inc i))]
        (cond
          ;; CSI: ESC [ [0-9;]* final-byte
          (= c \[)
          (loop [j (+ i 2)]
            (if (>= j n)
              nil
              (let [ch (int (nth s j))]
                (cond
                  (or (= ch 0x3b) (<= 0x30 ch 0x39)) (recur (inc j))
                  (or (<= 0x41 ch 0x5a) (<= 0x61 ch 0x7a)) (inc j)
                  :else nil))))
          ;; OSC: ESC ] … BEL | ST | 0x9c
          (= c \])
          (loop [j (+ i 2)]
            (if (>= j n)
              nil
              (let [ch (int (nth s j))]
                (cond
                  (or (= ch 0x07) (= ch 0x9c)) (inc j)
                  (and (= ch 0x1b) (< (inc j) n) (= 0x5c (int (nth s (inc j))))) (+ j 2)
                  :else (recur (inc j))))))
          :else nil)))))

(defn strip-ansi-native
  "Remove ANSI sequences with the hand-rolled scanner: str/index-of seeds
   each search (native on both hosts), ansi-sequence-end consumes the
   sequence, and a non-sequence ESC is kept as literal text, exactly like
   the regex strip. ~5x faster than the regex strip on Jolt for styled
   lines and ~3x for no-escape strings (the regex still scans them)."
  [s]
  (let [n (count s)]
    (loop [pos 0 out nil]
      (if (>= pos n)
        (or out s)
        (let [idx (str/index-of s "\u001b" pos)]
          (if (nil? idx)
            (str (or out "") (subs s pos))
            (if-let [end (ansi-sequence-end s idx)]
              (recur end (str (or out "") (subs s pos idx)))
              (recur (inc idx) (str (or out "") (subs s pos (inc idx)))))))))))

(defn- strip-ansi
  "Remove ANSI CSI/OSC sequences from S — the one implementation behind
   strip-ansi-codes and visible-width. Jolt takes the scanner (its regex
   engine is irregex: 17 µs vs 3.4 µs on a styled 100-char line); babashka
   and the JVM stay on the regex strip, where java.util.regex is the faster
   primitive (4.6 µs vs 8 µs)."
  [s]
  #?(:jolt (strip-ansi-native s)
     :default (str/replace s ANSI-CODE-RE "")))

(defn- match-at
  "Match RE in S starting exactly at index I — the anchored-scan idiom
   (.find m i) + (= (.start m) i). Returns [match-text end] with END
   absolute in S, nil when nothing starts at I."
  [re s i]
  (when (< i (count s))
    (let [m (re-matcher re s)]
      (when (and (.find m i) (= i (.start m)))
        [(.group m) (.end m)]))))

;; pi: SEGMENT_RESET — full SGR + OSC 8 reset appended to every non-image line
;; by applyLineResets so a truncated line can never leave active attributes
;; or an open hyperlink bleeding into the next line.
(def ^:const SEGMENT-RESET "\u001b[0m\u001b]8;;\u0007")

;; OSC 8 hyperlink close for truncation (pi: getActiveOsc8Close). When
;; truncate-to-width cuts through a hyperlink label, the retained prefix
;; would leave the link open — the close must be emitted before the
;; ellipsis so following text isn't swallowed by the link.
(def ^:private osc-8-link-re #"\u001b\]8;[^\u0007\u001b\u009c]*(?:\u001b\\|\u0007|\u009c)")

(defn- active-osc-8-close
  "Close sequence for the last open OSC 8 hyperlink in S, or \"\" when none
   is open. A link is open when its `params;url` body has a ';' and a
   non-empty url; a bare `\u001b]8;;\u0007` close resets it. The close reuses
   the link's terminator (BEL or ST) like pi (pi: parseOsc8Hyperlink /
   formatOsc8Close)."
  [s]
  (when (str/includes? s "\u001b]8;")
    (let [term (reduce (fn [term code]
                         (let [term-len (cond
                                          (str/ends-with? code "\u0007") 1
                                          (str/ends-with? code "\u001b\\") 2
                                          :else 1)
                               body (subs code 4 (- (count code) term-len))
                               sep (str/index-of body ";")]
                           (cond
                             ;; no separator — not a link, state unchanged (pi: undefined)
                             (nil? sep) term
                             ;; empty url — a close, resets the active link (pi: null)
                             (>= (inc sep) (count body)) nil
                             :else (if (str/ends-with? code "\u0007") "\u0007" "\u001b\\"))))
                       nil
                       (re-seq osc-8-link-re s))]
      (when term (str "\u001b]8;;" term)))))

;; ANSI sequences (CSI, OSC, APC) recognized while expanding tabs (pi:
;; extractAnsiCode). The cursor marker is an APC (ESC _ ... BEL).
(def ^:private ANSI-OR-TAB-RE
  #"\u001b\[[0-9;]*[a-zA-Z]|\u001b\][^\u0007\u001b\u009c]*(?:\u001b\\|\u0007|\u009c)|\u001b_[^\u0007\u001b\u009c]*(?:\u001b\\|\u0007|\u009c)|\t")

(defn normalize-terminal-output
  "Port of pi's normalizeTerminalOutput: decompose the Thai/Lao AM vowel
   (precomposed \u0e33/\u0eb3 have ambiguous width; the decomposed form
   renders correctly) and expand tabs to 3 spaces, preserving ANSI
   sequences. Tabs would otherwise render at the terminal's own tab stops,
   breaking the width math (pi expands to 3 spaces)."
  [s]
  ;; Early out: neither replacement can fire and the tab branch cannot run
  ;; unless one of the three trigger characters is present. This runs once per
  ;; line per frame over the whole transcript, so skipping the two no-op
  ;; replaces for the common line (neither Thai/Lao AM nor a tab) is worth the
  ;; extra scan.
  (if (or (clojure.string/includes? s "\u0e33")
          (clojure.string/includes? s "\u0eb3")
          (clojure.string/includes? s "\t"))
    (let [s (-> s
                (clojure.string/replace "\u0e33" "\u0e4d\u0e32")
                (clojure.string/replace "\u0eb3" "\u0ecd\u0eb2"))]
      (if (clojure.string/includes? s "\t")
        (clojure.string/replace s ANSI-OR-TAB-RE
                                (fn [m] (if (= m "\t") "   " m)))
        s))
    s))

;; ─── Width calculation ─────────────────────────────────────────────────────

(def ^:const CJK-START 0x2E80)
(def ^:const CJK-END 0x9FFF)

(defn- cjk? [cp]
  (or (and (>= cp CJK-START) (<= cp CJK-END))
      (and (>= cp 0xAC00) (<= cp 0xD7AF))   ;; Hangul
      (and (>= cp 0x3000) (<= cp 0x303F))    ;; CJK symbols
      (and (>= cp 0xFE30) (<= cp 0xFE4F))    ;; CJK compatibility
      ;; Fullwidth forms — FF61..FFDC are HALFwidth (ｱｲｳ…) and render narrow
      (and (>= cp 0xFF00) (<= cp 0xFF60))
      (and (>= cp 0xFFE0) (<= cp 0xFFE6))))

(def ^:private wide-emoji-ranges
  "Codepoints terminals render as 2-column emoji glyphs: the single-codepoint
   RGI emoji (emoji-test.txt, including bare forms like ✔/☀/☑) plus the East
   Asian Wide members of the emoji blocks (pi: rgiEmojiRegex + eastAsianWidth).
   Derived from Unicode 15.1 emoji-test.txt + EastAsianWidth.txt. Excludes the
   text-presentation dingbats (✓ ✗ ★ ☆ ❶ …) the old coarse block ranges
   overcounted — they are ambiguous-width and render narrow."
  [[0x231A 0x231B] [0x2328 0x232A] [0x23CF 0x23CF] [0x23E9 0x23F3]
   [0x23F8 0x23FA] [0x2600 0x2604] [0x260E 0x260E] [0x2611 0x2611]
   [0x2614 0x2615] [0x2618 0x2618] [0x261D 0x261D] [0x2620 0x2620]
   [0x2622 0x2623] [0x2626 0x2626] [0x262A 0x262A] [0x262E 0x262F]
   [0x2638 0x263A] [0x2640 0x2640] [0x2642 0x2642] [0x2648 0x2653]
   [0x265F 0x2660] [0x2663 0x2663] [0x2665 0x2666] [0x2668 0x2668]
   [0x267B 0x267B] [0x267E 0x267F] [0x2692 0x2697] [0x2699 0x2699]
   [0x269B 0x269C] [0x26A0 0x26A1] [0x26A7 0x26A7] [0x26AA 0x26AB]
   [0x26B0 0x26B1] [0x26BD 0x26BE] [0x26C4 0x26C5] [0x26C8 0x26C8]
   [0x26CE 0x26CF] [0x26D1 0x26D1] [0x26D3 0x26D4] [0x26E9 0x26EA]
   [0x26F0 0x26F5] [0x26F7 0x26FA] [0x26FD 0x26FD] [0x2702 0x2702]
   [0x2705 0x2705] [0x2708 0x270D] [0x270F 0x270F] [0x2712 0x2712]
   [0x2714 0x2714] [0x2716 0x2716] [0x271D 0x271D] [0x2721 0x2721]
   [0x2728 0x2728] [0x2733 0x2734] [0x2744 0x2744] [0x2747 0x2747]
   [0x274C 0x274C] [0x274E 0x274E] [0x2753 0x2755] [0x2757 0x2757]
   [0x2763 0x2764] [0x2795 0x2797] [0x27A1 0x27A1] [0x27B0 0x27B0]
   [0x27BF 0x27BF] [0x2B50 0x2B50] [0x2B55 0x2B55] [0x1F004 0x1F004]
   [0x1F0CF 0x1F0CF] [0x1F170 0x1F171] [0x1F17E 0x1F17F] [0x1F18E 0x1F18E]
   [0x1F191 0x1F19A] [0x1F1E6 0x1F202] [0x1F210 0x1F23B] [0x1F240 0x1F248]
   [0x1F250 0x1F251] [0x1F260 0x1F265] [0x1F300 0x1F321] [0x1F324 0x1F393]
   [0x1F396 0x1F397] [0x1F399 0x1F39B] [0x1F39E 0x1F3F0] [0x1F3F3 0x1F3F5]
   [0x1F3F7 0x1F4FD] [0x1F4FF 0x1F53D] [0x1F549 0x1F54E] [0x1F550 0x1F567]
   [0x1F56F 0x1F570] [0x1F573 0x1F57A] [0x1F587 0x1F587] [0x1F58A 0x1F58D]
   [0x1F590 0x1F590] [0x1F595 0x1F596] [0x1F5A4 0x1F5A5] [0x1F5A8 0x1F5A8]
   [0x1F5B1 0x1F5B2] [0x1F5BC 0x1F5BC] [0x1F5C2 0x1F5C4] [0x1F5D1 0x1F5D3]
   [0x1F5DC 0x1F5DE] [0x1F5E1 0x1F5E1] [0x1F5E3 0x1F5E3] [0x1F5E8 0x1F5E8]
   [0x1F5EF 0x1F5EF] [0x1F5F3 0x1F5F3] [0x1F5FA 0x1F64F] [0x1F680 0x1F6C5]
   [0x1F6CB 0x1F6D2] [0x1F6D5 0x1F6D7] [0x1F6DC 0x1F6E5] [0x1F6E9 0x1F6E9]
   [0x1F6EB 0x1F6EC] [0x1F6F0 0x1F6F0] [0x1F6F3 0x1F6FC] [0x1F7E0 0x1F7EB]
   [0x1F7F0 0x1F7F0] [0x1F90C 0x1F93A] [0x1F93C 0x1F945] [0x1F947 0x1F9FF]
   [0x1FA70 0x1FA7C] [0x1FA80 0x1FA88] [0x1FA90 0x1FABD] [0x1FABF 0x1FAC5]
   [0x1FACE 0x1FADB] [0x1FAE0 0x1FAE8] [0x1FAF0 0x1FAF8]])

(defn- in-ranges?
  "Binary search for CP in a sorted vector of [start end] codepoint ranges."
  [ranges cp]
  (loop [lo 0, hi (count ranges)]
    (when (< lo hi)
      (let [mid (quot (+ lo hi) 2)
            [a b] (nth ranges mid)]
        (cond
          (< cp a) (recur lo mid)
          (> cp b) (recur (inc mid) hi)
          :else true)))))

(defn- wide-emoji?
  "True when CP is a single-codepoint emoji (or an EAW-wide member of the
   emoji blocks) — terminals render these as 2-column glyphs bare."
  [cp]
  (in-ranges? wide-emoji-ranges cp))

(def ^:private vs16-emoji-bases
  "Text-presentation RGI emoji bases that stay narrow bare (© ↔ ▶ ▪ ⬛ … —
   EAW ambiguous, no default emoji presentation) but render 2-column with
   U+FE0F (©️ ↔️ ▶️ ▪️ …, pi: RGI_Emoji qualified forms)."
  [[0x00A9 0x00A9] [0x00AE 0x00AE] [0x203C 0x203C] [0x2049 0x2049]
   [0x2122 0x2122] [0x2139 0x2139] [0x2194 0x2199] [0x21A9 0x21AA]
   [0x24C2 0x24C2] [0x25AA 0x25AB] [0x25B6 0x25B6] [0x25C0 0x25C0]
   [0x25FB 0x25FE] [0x2934 0x2935] [0x2B05 0x2B07] [0x2B1B 0x2B1C]
   [0x3030 0x3030] [0x303D 0x303D] [0x3297 0x3297] [0x3299 0x3299]])

(defn- vs16-text-base?
  "True when CP turns 2-column with an explicit U+FE0F (see VS16-EMOJI-BASES)."
  [cp]
  (in-ranges? vs16-emoji-bases cp))

(defn- char-width [cp]
  (cond
    (= cp 9) 3                                ;; Tab (pi normalizes tabs to 3 spaces)
    (< cp 32) 0                                ;; Control chars
    ;; Zero-width characters
    (or (== cp 0x200B) (== cp 0x200C) (== cp 0x200D) (== cp 0x200E) (== cp 0x200F)
        (== cp 0x2060) (== cp 0x2061) (== cp 0x2062) (== cp 0x2063) (== cp 0x2064)
        (and (>= cp 0xFE00) (<= cp 0xFE0F))  ;; Variation selectors
        (and (>= cp 0xE0100) (<= cp 0xE01EF))) ;; Variation selectors supplement
    0
    (or (cjk? cp) (wide-emoji? cp)) 2
    :else 1))

(defn- code-point-at
  "Get the Unicode code point at index i from string s.
   Pure Clojure implementation (no .codePointAt).
   Handles surrogate pairs for characters outside the BMP."
  [s i]
  (let [c (int (nth s i))]
    (if (and (>= c 0xD800) (<= c 0xDBFF) (< (inc i) (count s)))
      ;; High surrogate followed by low surrogate
      (let [low (int (nth s (inc i)))]
        (+ 0x10000 (* (- c 0xD800) 0x400) (- low 0xDC00)))
      c)))

;; Grapheme-aware width: approximates Intl.Segmenter grapheme segmentation
;; (pi's utils.ts) for the common emoji sequences that terminals render as a
;; single width-2 glyph — regional-indicator flag pairs, ZWJ chains, and
;; skin-tone modifiers. Simple emoji (🚀) already measured correctly.

(def ^:private regional-indicator-start 0x1F1E6)
(def ^:private regional-indicator-end 0x1F1FF)
(def ^:private skin-tone-start 0x1F3FB)
(def ^:private skin-tone-end 0x1F3FF)
(def ^:private zwj-cp 0x200D)

(defn- codepoint-len
  "Number of string elements the code point at index I of S spans: 2 only
   when I is a high surrogate paired with a low surrogate (bb/JVM UTF-16
   strings); 1 on jolt, where every element is a full code point. Derived
   from the string itself, so the width walkers are index-model-agnostic."
  [s i]
  (let [c (int (nth s i))]
    (if (and (<= 0xD800 c 0xDBFF)
             (< (inc i) (count s))
             (let [l (int (nth s (inc i)))]
               (<= 0xDC00 l 0xDFFF)))
      2
      1)))

(defn- skip-vs16
  "Index past any U+FE0F (emoji variation selector) starting at I of S."
  [s i n]
  (loop [i i]
    (if (and (< i n) (= 0xFE0F (code-point-at s i)))
      (recur (inc i))
      i)))

(defn- grapheme-width-and-next
  "Terminal width of the grapheme cluster starting at index I of S (length N)
   and the index where the next cluster starts. PREV-W is the preceding
   cluster's width, used to zero skin-tone modifiers that follow a width-2
   emoji base."
  [s i n prev-w]
  (let [cp (code-point-at s i)
        len (codepoint-len s i)
        w (char-width cp)
        w (if (and (<= skin-tone-start cp skin-tone-end) (>= prev-w 2)) 0 w)
        j (+ i len)]
    (cond
      ;; Flag: two regional indicators form one width-2 glyph
      (and (<= regional-indicator-start cp regional-indicator-end)
           (< j n)
           (<= regional-indicator-start (code-point-at s j) regional-indicator-end))
      [2 (+ j (codepoint-len s j))]

      ;; ZWJ chain on a wide emoji base: base [FE0F] ZWJ member [ZWJ member]*
      ;; → one glyph (pi: RGI_Emoji via Intl.Segmenter — the VS16 between
      ;; base and ZWJ in ❤️‍🔥 / 🏳️‍🌈 must not break the chain). Non-emoji
      ;; wide chars (CJK) never chain.
      (and (wide-emoji? cp) (>= w 2) (< j n)
           (let [k (skip-vs16 s j n)]
             (and (< k n) (= zwj-cp (code-point-at s k)))))
      (let [end (loop [k (skip-vs16 s j n)]
                  (if (and (< k n) (= zwj-cp (code-point-at s k)))
                    (let [m (inc k)]
                      (if (< m n)
                        (recur (+ m (codepoint-len s m)))
                        (inc k)))
                    k))]
        [2 end])

      ;; VS16 (U+FE0F) after a text-presentation emoji base (© ™ ↔ ▶ ▪ …):
      ;; explicit emoji presentation → one width-2 glyph (pi: RGI_Emoji
      ;; qualified form; the bare base alone stays narrow)
      (and (vs16-text-base? cp) (< j n) (= 0xFE0F (code-point-at s j)))
      [2 (inc j)]

      :else
      [w j])))

(defn- visible-width-plain
  "Visible width of a string that has NO ANSI escape codes.
   Skips the ANSI-stripping step for efficiency."
  [s]
  (if (empty? s) 0
      (if (re-find #"[^\u0020-\u007e]" s)
        (loop [i 0, n (count s), total 0, prev-w 0]
          (if (>= i n) total
              (let [[w next-i] (grapheme-width-and-next s i n prev-w)]
                (recur next-i n (+ total w) w))))
        (count s))))
(defn visible-width
  "Calculate the visible display width of a string in terminal columns.
   Strips ANSI escape codes before measuring. The printable-ASCII test runs
   FIRST: it matches any ESC byte too, so a plain line answers with `count`
   and never touches the strip, and a styled line pays one cheap scan
   before the strip (this is the per-line / per-grapheme hot path — the
   regex engine is the slowest primitive on Jolt). Non-ASCII or ESC text
   falls through to the ANSI-free walker, which itself fast-paths plain
   ASCII."
  [s]
  (if (empty? s) 0
      (if (re-find #"[^\u0020-\u007e]" s)
        (visible-width-plain
         (if (str/includes? s "\u001b")
           (strip-ansi s)
           s))
        (count s))))

;; ─── Truncation ─────────────────────────────────────────────────────────────

(defn truncate-to-width
  "Truncate string to fit within max-width visible columns, appending
   ellipsis when truncated. Text that already fits within max-width is
   returned unchanged — the ellipsis is never added to fitting text (pi:
   truncateToWidth guards on max-width, not target). When the ellipsis
   alone doesn't fit, it is clipped to max-width (pi:
   truncateFragmentToWidth). ANSI escape codes are preserved for the kept
   prefix, so styling survives truncation. With PAD true the result is
   right-padded with spaces to exactly max-width visible columns (pi:
   truncateToWidth's pad flag) — short text keeps box borders aligned."
  ([s max-width] (truncate-to-width s max-width ""))
  ([s max-width ellipsis] (truncate-to-width s max-width ellipsis false))
  ([s max-width ellipsis pad]
   (let [pad-to (fn [result]
                  (if pad
                    (str result (apply str (repeat (max 0 (- max-width (visible-width result))) \space)))
                    result))]
     (cond
       (<= max-width 0) ""
       (empty? s) (pad-to "")
       (<= (visible-width s) max-width) (pad-to s)
       :else
       (let [e-width (visible-width ellipsis)
             target (- max-width e-width)]
         (if (>= e-width max-width)
           (let [clipped (truncate-to-width ellipsis max-width)]
             (pad-to (if (pos? (visible-width clipped)) clipped "")))
           (pad-to
            (if-not (or (clojure.string/includes? s "\u001b[")
                        (clojure.string/includes? s "\u001b]"))
              (let [sb (atom "")
                    total (atom 0)
                    n (count s)
                    _ (loop [i 0]
                        (when (and (< i n)
                                   (<= (+ @total (char-width (code-point-at s i))) target))
                          (let [cp (code-point-at s i)
                                w (char-width cp)
                                nchars (codepoint-len s i)]
                            (swap! sb str (subs s i (+ i nchars)))
                            (swap! total + w)
                            (recur (+ i nchars)))))]
                (str @sb ellipsis))
              (let [sb (StringBuilder.)
                    n (count s)
                    ansi-at (fn [i] (match-at ANSI-CODE-RE s i))]
                (loop [i 0 total 0 pending ""]
                  (if (or (>= i n) (>= total target))
                    (str sb (active-osc-8-close (str sb)) ellipsis)
                    (if-let [[code end] (ansi-at i)]
                      (recur end total (str pending code))
                      (let [cp (code-point-at s i)
                            w (char-width cp)
                            nchars (codepoint-len s i)]
                        (if (<= (+ total w) target)
                          (do (.append sb pending)
                              (.append sb (subs s i (+ i nchars)))
                              (recur (+ i nchars) (+ total w) ""))
                          (str sb (active-osc-8-close (str sb)) ellipsis)))))))))))))))

;; ─── Word wrapping ──────────────────────────────────────────────────────────

;; ─── ANSI state tracking for wrapping (pi: AnsiCodeTracker) ────────────────
;; Keeps the active SGR attributes (and the open OSC 8 hyperlink) while
;; wrapping a styled line so continuation lines re-open the styles instead
;; of falling back to the terminal default (pi: wrapTextWithAnsi tracks the
;; active codes and prepends them at each line break).

(defrecord AnsiState [bold dim italic underline blink inverse hidden
                      strikethrough fg bg hyperlink hyperlink-term])

(defn- make-ansi-state
  []
  (map->AnsiState {:bold false :dim false :italic false :underline false
                   :blink false :inverse false :hidden false :strikethrough false
                   :fg nil :bg nil :hyperlink nil :hyperlink-term nil}))

(defn- ansi-process-sgr!
  "Process one SGR sequence (\"\\u001b[31m\" etc.) into the tracker state.
   Only SGR (ending in m) sequences are tracked — other CSI sequences like
   \"\\u001b[2J\" are cursor/screen operations, not attributes (pi:
   AnsiCodeTracker.process requires ansiCode.endsWith('m'))."
  [st sgr]
  (when (clojure.string/ends-with? sgr "m")
    (let [params (subs sgr 2 (max 2 (dec (count sgr))))]
      (if (or (empty? params) (= params "0"))
        (reset! st (make-ansi-state))
        (let [parts (clojure.string/split params #";")
              n (count parts)]
          (swap! st
                 (fn [acc]
                   (loop [i 0 acc acc]
                     (if (>= i n)
                       acc
                       (let [code (try (Integer/parseInt (nth parts i))
                                       (catch Exception _ -1))]
                         (cond
                           (and (= code 38) (< (+ i 2) n) (= (nth parts (inc i)) "5"))
                           (recur (+ i 3) (assoc acc :fg (str "38;5;" (nth parts (+ i 2)))))
                           (and (= code 38) (< (+ i 4) n) (= (nth parts (inc i)) "2"))
                           (recur (+ i 5) (assoc acc :fg (str "38;2;" (nth parts (+ i 2))
                                                              ";" (nth parts (+ i 3))
                                                              ";" (nth parts (+ i 4)))))
                           (and (= code 48) (< (+ i 2) n) (= (nth parts (inc i)) "5"))
                           (recur (+ i 3) (assoc acc :bg (str "48;5;" (nth parts (+ i 2)))))
                           (and (= code 48) (< (+ i 4) n) (= (nth parts (inc i)) "2"))
                           (recur (+ i 5) (assoc acc :bg (str "48;2;" (nth parts (+ i 2))
                                                              ";" (nth parts (+ i 3))
                                                              ";" (nth parts (+ i 4)))))
                           :else
                           (recur (inc i)
                                  (case code
                                    1 (assoc acc :bold true)
                                    2 (assoc acc :dim true)
                                    3 (assoc acc :italic true)
                                    4 (assoc acc :underline true)
                                    5 (assoc acc :blink true)
                                    7 (assoc acc :inverse true)
                                    8 (assoc acc :hidden true)
                                    9 (assoc acc :strikethrough true)
                                    21 (assoc acc :bold false)
                                    22 (assoc acc :bold false :dim false)
                                    23 (assoc acc :italic false)
                                    24 (assoc acc :underline false)
                                    25 (assoc acc :blink false)
                                    27 (assoc acc :inverse false)
                                    28 (assoc acc :hidden false)
                                    29 (assoc acc :strikethrough false)
                                    39 (assoc acc :fg nil)
                                    49 (assoc acc :bg nil)
                                    ;; default: fg 30-37/90-97, bg 40-47/100-107
                                    (cond-> acc
                                      (or (<= 30 code 37) (<= 90 code 97))
                                      (assoc :fg (str code))
                                      (or (<= 40 code 47) (<= 100 code 107))
                                      (assoc :bg (str code))))))))))))))))

(defn- ansi-process-osc8!
  "Process an OSC 8 hyperlink sequence (open/close) into the tracker state."
  [st code]
  (let [term-len (cond (clojure.string/ends-with? code "\u0007") 1
                       (clojure.string/ends-with? code "\u001b\\") 2
                       :else 1)
        body (subs code 4 (- (count code) term-len))]
    (when (and (clojure.string/starts-with? code "\u001b]8;")
               (clojure.string/starts-with? body ";"))
      (let [url (subs body 1)]
        (if (clojure.string/blank? url)
          (swap! st assoc :hyperlink nil :hyperlink-term nil)
          (swap! st assoc :hyperlink url
                 :hyperlink-term (if (clojure.string/ends-with? code "\u0007")
                                   "\u0007" "\u001b\\")))))))

(defn- ansi-process-code!
  "Process one ANSI sequence into the tracker (SGR or OSC 8 hyperlink)."
  [st code]
  (if (clojure.string/starts-with? code "\u001b[")
    (ansi-process-sgr! st code)
    (ansi-process-osc8! st code)))

(defn- ansi-process-text!
  "Process all ANSI sequences in TEXT into the tracker state."
  [st text]
  (when (clojure.string/includes? text "\u001b")
    (doseq [code (re-seq ANSI-CODE-RE text)]
      (ansi-process-code! st code))))

(defn- ansi-process-range!
  "Process ANSI sequences found in word[i, stop) into the tracker state."
  [st word i stop]
  (loop [j i]
    (when (< j stop)
      (let [m (when (= \u001b (nth word j))
                (re-find ANSI-CODE-RE (subs word j)))]
        (if (and m (clojure.string/starts-with? (subs word j) m))
          (do (ansi-process-code! st m)
              (recur (+ j (count m))))
          (recur (inc j)))))))

(defn- ansi-active-codes
  "The currently active SGR attributes (and open hyperlink) as a single
   escape sequence, or \"\" when nothing is active (pi: getActiveCodes)."
  [st]
  (let [{:keys [bold dim italic underline blink inverse hidden strikethrough
                fg bg hyperlink hyperlink-term]} @st
        codes (cond-> []
                bold (conj "1")
                dim (conj "2")
                italic (conj "3")
                underline (conj "4")
                blink (conj "5")
                inverse (conj "7")
                hidden (conj "8")
                strikethrough (conj "9")
                fg (conj fg)
                bg (conj bg))
        sgr (if (seq codes) (str "\u001b[" (clojure.string/join ";" codes) "m") "")
        link (if hyperlink (str "\u001b]8;;" hyperlink hyperlink-term) "")]
    (str sgr link)))

(defn- split-long-word
  "Split WORD (longer than max-width) into pieces of at most max-width
   visible columns. Every character is preserved — long unbreakable words
   (URLs, hashes, long flags) wrap instead of being clipped (pi:
   breakLongWord). ANSI escapes are zero-width atoms kept with the piece
   that contains them; the active SGR/hyperlink state is re-emitted at the
   start of every piece so styling survives the break (pi: breakLongWord +
   AnsiCodeTracker). ST is the wrap's tracker (atom of AnsiState), updated
   with the codes seen so far.
   Fast path: pure-ASCII words break with a regex (visible width = char
   count), avoiding per-character work — crucial for streaming long
   tokens under SCI where per-char interop is slow."
  [word max-width st]
  (let [n (count word)
        ansi-re ANSI-CODE-RE]
    (if (not (re-find #"[^\u0020-\u007e]" word))
      ;; ASCII fast path — no ANSI codes possible (ESC 0x1b is non-ASCII).
      ;; NOTE (jolt perf): was (re-seq (re-pattern "(?s).{1,N}") word) — a
      ;; per-call re-pattern + regex chunk (~56 µs on jolt); plain subs
      ;; slicing is ~4 µs there and ~2x faster on bb too.
      (let [nchars (count word)
            pieces (mapv (fn [i] (subs word i (min nchars (+ i max-width))))
                         (range 0 nchars max-width))]
        (if (seq pieces)
          ;; every piece starts a fresh line after the buffer flush, so the
          ;; active state is re-emitted on each — including the first
          ;; (pi: breakLongWord starts with tracker.getActiveCodes())
          (vec (map-indexed (fn [_ p] (str (ansi-active-codes st) p))
                            pieces))
          [word]))
      ;; Walker: wide chars (CJK/emoji) or embedded ANSI — slice by visible
      ;; width; one subs per piece, no per-character appends. Codes inside
      ;; each piece are processed into the tracker so continuation pieces
      ;; re-emit the state accumulated so far.
      (loop [i 0 pieces []]
        (if (>= i n)
          (if (seq pieces) pieces [word])
          (let [active-before (ansi-active-codes st)
                stop (loop [j i total 0]
                       (if (>= j n)
                         j
                         (let [m (when (= \u001b (nth word j))
                                   (re-find ansi-re (subs word j)))]
                           (if (and m (clojure.string/starts-with? (subs word j) m))
                             (recur (+ j (count m)) total)
                             (let [cp (code-point-at word j)
                                   w (char-width cp)
                                   nchars (codepoint-len word j)]
                               ;; Stop only once the piece has visible content —
                               ;; never emit an empty piece (e.g. a styled word
                               ;; starting with a 2-wide char at max-width 1).
                               (if (and (pos? total) (> (+ total w) max-width))
                                 j
                                 (recur (+ j nchars) (+ total w))))))))]
            (ansi-process-range! st word i stop)
            (recur stop
                   (conj pieces
                         (str active-before (subs word i stop))))))))))

(defn- wrap-single-line
  "Wrap a single line (no internal newlines) to max-width visible columns.
   Returns a vector of lines, each without trailing newlines.
   ANSI escape codes are preserved; the active SGR/hyperlink state is
   re-emitted at every line break so wrapped continuations keep their
   style (pi: wrapSingleLine + AnsiCodeTracker). Long unbreakable words
   are broken across lines, never clipped."
  [line max-width]
  (if (or (empty? line) (<= max-width 0))
    [""]
    (let [clean (clojure.string/replace line ANSI-CODE-RE "")]
      (if (<= (visible-width-plain clean) max-width)
        [line]
        (let [words ;; NOTE (jolt perf): was (str/split line #"(?<=\s)" -1).
              ;; jolt's irregex is pathological on lookbehind (~14 ms for a
              ;; 540-char line vs 0.03 ms on bb — 500x); re-seq with
              ;; #"\S*\s|\S+$" segments identically (pinned across a
              ;; plain/styled/tab/newline/unicode-ws corpus on both hosts)
              ;; at ~0.07 ms on jolt, and is also faster on bb. The only
              ;; difference — split's trailing [""] — is a no-op in the
              ;; loop below (a zero-width word either appends or flushes an
              ;; empty sb that the final flush drops; brute-forced equal
              ;; over widths 1..80, plain and ANSI-styled).
              (or (re-seq #"\S*\s|\S+$" line) [line])
              st (atom (make-ansi-state))
              result (volatile! [])
              sb (StringBuilder.)
              cur-w (volatile! 0)]
          (doseq [w words]
            (let [ww (if (re-find #"[^\u0020-\u007e]" w) (visible-width w) (count w))
                  lw @cur-w
                  sep (if (zero? lw) 0 1)]
              (if (<= (+ lw sep ww) max-width)
                (do (.append sb w)
                    (vswap! cur-w + ww))
                (do
                  (when (pos? @cur-w)
                    (vswap! result conj (str sb)))
                  (if (<= ww max-width)
                    (do (.setLength sb 0)
                        ;; new line starts with the state left by the words
                        ;; flushed above (pi: tracker.getActiveCodes() + token)
                        (.append sb (ansi-active-codes st))
                        (.append sb w)
                        (vreset! cur-w ww))
                    (let [pieces (split-long-word w max-width st)
                          last-p (or (last pieces) "")]
                      (doseq [p (butlast pieces)]
                        (vswap! result conj p))
                      (.setLength sb 0)
                      (.append sb last-p)
                      (vreset! cur-w (if (re-find #"[^\u0020-\u007e]" last-p)
                                       (visible-width last-p)
                                       (count last-p))))))))
            (ansi-process-text! st w))
          ;; Flush any pending content — including zero-width styled lines
          ;; (e.g. a blank input line with a cross-line style prefix, pi:
          ;; wrapSingleLine returns the prefix-only line)
          (if (pos? (.length sb))
            (conj @result (str sb))
            @result))))))

(defn wrap-text-with-ansi
  "Word wrap preserving ANSI escape codes.
   First splits on newlines so each returned line is a proper display line
   without embedded newlines. This ensures background padding in parent
   components extends to full terminal width on every line.
   Tabs are expanded to 3 spaces (pi: replaceTabs) so wrapped output is
   display-ready regardless of the terminal's tab stop.
   Styles left open by a line are re-emitted on the following line so a
   styled multi-line string keeps its attributes across literal newlines
   (pi: wrapTextWithAnsi tracks the active codes across input lines).
   Internal fast path: uses visible-width-plain on already-stripped text."
  [text max-width]
  (if (or (empty? text) (<= max-width 0))
    [""]
    (let [text (clojure.string/replace text "\t" "   ")
          input-lines (clojure.string/split text #"\r\n|\r|\n")
          result (volatile! [])
          st (atom (make-ansi-state))]
      (doseq [input-line input-lines]
        (let [;; pi: prepend the active codes from previous lines so the
              ;; first wrapped line of this input line re-opens them
              prefix (if (seq @result) (ansi-active-codes st) "")
              wrapped (wrap-single-line (str prefix input-line) max-width)]
          (doseq [wl wrapped]
            (vswap! result conj wl)))
        (ansi-process-text! st input-line))
      (if (seq @result)
        @result
        [""]))))

;; ─── ANSI helpers ───────────────────────────────────────────────────────────

(defn strip-ansi-codes
  "Strip ANSI CSI/OSC sequences from S (host-optimal: the scanner on Jolt,
   java.util.regex elsewhere)."
  [s]
  (strip-ansi s))

(defn sgr
  ([code] (str "\u001b[" code "m"))
  ([& codes] (str "\u001b[" (clojure.string/join \; codes) "m")))

(defn apply-background-to-line [line width bg-fn]
  (let [pad (max 0 (- width (visible-width line)))
        padded (str line (apply str (repeat pad \space)))]
    (if bg-fn (bg-fn padded) padded)))

;; ─── Column-based slicing ──────────────────────────────────────────────────

(defn slice-by-column
  "Extract a substring from `text` representing the visible columns
   from start-col with the given length-in-columns.
   Handles wide (CJK/emoji) characters. Expects plain text without ANSI codes.
   When strict? is true, wide characters at the boundary are excluded."
  [text start-col length & {:keys [strict?] :or {strict? false}}]
  (let [s (if (string? text) text (str text))
        n (count s)]
    (loop [i 0 col 0 result []]
      (if (>= i n)
        (apply str result)
        (let [cp (code-point-at s i)
              w (char-width cp)
              nchars (codepoint-len s i)
              char-str (subs s i (+ i nchars))]
          (if (>= col (+ start-col length))
            (apply str result)
            (if (>= col start-col)
              (if (and strict? (> (+ col w) (+ start-col length)))
                (apply str result)
                (recur (+ i nchars) (+ col w) (conj result char-str)))
              (recur (+ i nchars) (+ col w) result))))))))

;; ─── ANSI-aware window slicing ──────────────────────────────────────────────

(defn ansi-code-at
  "Return [code length] when an ANSI escape sequence starts at index I of S."
  [s i]
  (when (and (< i (count s)) (= \u001b (nth s i)))
    (when-let [[code end] (match-at ANSI-CODE-RE s i)]
      [code (- end i)])))

(defn slice-with-width
  "Slice LINE's visible columns [start-col, start-col+length), ANSI-aware.
   Returns {:text str :width n}. ANSI codes before start-col are kept as
   pending and prepended to the first emitted character so styling survives
   the slice (pi: sliceWithWidth). With strict?, a wide character crossing
   the end boundary is excluded. Grapheme widths agree with visible-width
   (skin-tone modifiers after a 2-wide base measure 0)."
  [line start-col length & {:keys [strict?]}]
  (if (<= length 0)
    {:text "" :width 0}
    (let [n (count line)
          end-col (+ start-col length)
          result (StringBuilder.)
          result-width (atom 0)
          current-col (atom 0)
          pending (StringBuilder.)
          stop? (atom false)]
      (loop [i 0, prev-w 0]
        (when (and (< i n) (not @stop?))
          (if-let [[code clen] (ansi-code-at line i)]
            (do
              ;; Codes inside the window are kept; codes before it are held
              ;; pending and prepended with the first emitted character.
              (when (and (<= start-col @current-col) (< @current-col end-col))
                (.append result code))
              (when (< @current-col start-col)
                (.append pending code))
              (recur (+ i clen) prev-w))
            ;; Text run up to the next ANSI code — walk graphemes
            (let [text-end (loop [j i]
                             (if (and (< j n) (nil? (ansi-code-at line j)))
                               (recur (inc j))
                               j))
                  run (subs line i text-end)
                  next-prev-w (loop [j 0, prev-w prev-w]
                                (if (and (< j (count run)) (not @stop?))
                                  (let [[w next-i] (grapheme-width-and-next run j (count run) prev-w)
                                        col @current-col]
                                    (when (and (>= col start-col) (< col end-col)
                                               (or (not strict?) (<= (+ col w) end-col)))
                                      (let [pending-str (str pending)]
                                        (when (seq pending-str)
                                          (.append result pending-str)
                                          (.setLength pending 0)))
                                      (.append result (subs run j next-i))
                                      (swap! result-width + w))
                                    (swap! current-col + w)
                                    (when (>= (+ col w) end-col)
                                      (reset! stop? true))
                                    (recur next-i w))
                                  prev-w))]
              (recur text-end next-prev-w)))))
      {:text (str result) :width @result-width})))

;; ─── SGR style tracking (pi: AnsiCodeTracker) ──────────────────────────────

(defn- reset-sgr-state
  "Empty SGR attribute state (default terminal style)."
  []
  {:bold false :dim false :italic false :underline false
   :blink false :inverse false :hidden false :strike false
   :fg nil :bg nil})

(defn- apply-sgr-code
  "Apply one SGR escape (e.g. \"\\u001b[38;2;1;2;3m\") to STATE and return
   the new state (pi: AnsiCodeTracker.process)."
  [state code]
  (let [m (re-find #"\u001b\[([0-9;]*)m" code)]
    (if (not m)
      state
      (let [params (if (or (= (second m) "") (= (second m) "0"))
                     [0]
                     (mapv #(Long/parseLong %) (str/split (second m) #";")))
            n (count params)]
        (loop [state state i 0]
          (if (>= i n)
            state
            (let [c (nth params i)
                  color (fn [base]
                          (if (and (< (inc i) n) (= (nth params (inc i)) 5) (< (+ i 2) n))
                            {:code (str base ";5;" (nth params (+ i 2))) :next (+ i 3)}
                            (if (and (< (inc i) n) (= (nth params (inc i)) 2) (< (+ i 4) n))
                              {:code (str base ";2;" (nth params (+ i 2)) ";" (nth params (+ i 3))
                                          ";" (nth params (+ i 4)))
                               :next (+ i 5)}
                              {:code nil :next (inc i)})))]
              (cond
                (= c 0) (recur (reset-sgr-state) (inc i))
                (= c 38) (let [{:keys [code next]} (color "38")]
                           (recur (if code (assoc state :fg code) state) next))
                (= c 48) (let [{:keys [code next]} (color "48")]
                           (recur (if code (assoc state :bg code) state) next))
                (= c 1) (recur (assoc state :bold true) (inc i))
                (= c 2) (recur (assoc state :dim true) (inc i))
                (= c 3) (recur (assoc state :italic true) (inc i))
                (= c 4) (recur (assoc state :underline true) (inc i))
                (= c 5) (recur (assoc state :blink true) (inc i))
                (= c 7) (recur (assoc state :inverse true) (inc i))
                (= c 8) (recur (assoc state :hidden true) (inc i))
                (= c 9) (recur (assoc state :strike true) (inc i))
                (= c 21) (recur (assoc state :bold false) (inc i))
                (= c 22) (recur (assoc state :bold false :dim false) (inc i))
                (= c 23) (recur (assoc state :italic false) (inc i))
                (= c 24) (recur (assoc state :underline false) (inc i))
                (= c 25) (recur (assoc state :blink false) (inc i))
                (= c 27) (recur (assoc state :inverse false) (inc i))
                (= c 28) (recur (assoc state :hidden false) (inc i))
                (= c 29) (recur (assoc state :strike false) (inc i))
                (= c 39) (recur (assoc state :fg nil) (inc i))
                (= c 49) (recur (assoc state :bg nil) (inc i))
                (or (and (>= c 30) (<= c 37)) (and (>= c 90) (<= c 97)))
                (recur (assoc state :fg (str c)) (inc i))
                (or (and (>= c 40) (<= c 47)) (and (>= c 100) (<= c 107)))
                (recur (assoc state :bg (str c)) (inc i))
                :else (recur state (inc i))))))))))

(defn- active-sgr-codes
  "Minimal SGR code string reproducing STATE (pi: getActiveCodes), or \"\"
   when the default style is active."
  [state]
  (let [codes (cond-> []
                (:bold state) (conj "1")
                (:dim state) (conj "2")
                (:italic state) (conj "3")
                (:underline state) (conj "4")
                (:blink state) (conj "5")
                (:inverse state) (conj "7")
                (:hidden state) (conj "8")
                (:strike state) (conj "9")
                (:fg state) (conj (:fg state))
                (:bg state) (conj (:bg state)))]
    (if (seq codes)
      (str "\u001b[" (str/join ";" codes) "m")
      "")))

(defn sgr-state-at
  "The SGR style active at visible column COL of LINE, as a minimal SGR
   code string (\"\" for the default style). Used to inherit styling past
   an overlay region (pi: extractSegments)."
  [line col]
  (let [n (count line)]
    (loop [i 0, current-col 0, prev-w 0, state (reset-sgr-state)]
      (if (>= i n)
        (active-sgr-codes state)
        (if-let [[_ clen] (ansi-code-at line i)]
          (recur (+ i clen) current-col prev-w
                 (apply-sgr-code state (subs line i (+ i clen))))
          (let [[w next-i] (grapheme-width-and-next line i n prev-w)]
            (if (>= current-col col)
              (active-sgr-codes state)
              (recur next-i (+ current-col w) w state))))))))

(defn composite-line
  "Overlay OVERLAY-LINE onto BASE-LINE at visible column START-COL with
   OVERLAY-WIDTH and TOTAL-WIDTH (pi: compositeTuiLine). ANSI-aware: the
   base's styling is preserved before and after the overlay region, the
   overlay's own codes are kept, and the after segment inherits the base
   style active at the overlay boundary. Kitty image lines pass through
   untouched so the protocol stream is never corrupted (pi: isImageLine).
   Returns the composited line."
  [base overlay start-col overlay-width total-width]
  (if (img/is-image-line base)
    base
    (let [after-start (+ start-col overlay-width)
          base-before (slice-with-width base 0 start-col :strict? true)
          base-after (slice-with-width base after-start (- total-width after-start) :strict? true)
          overlay-slice (slice-with-width overlay 0 overlay-width :strict? true)
          before-pad (max 0 (- start-col (:width base-before)))
          overlay-pad (max 0 (- overlay-width (:width overlay-slice)))
          actual-before-w (max start-col (:width base-before))
          actual-overlay-w (max overlay-width (:width overlay-slice))
          after-target (max 0 (- total-width actual-before-w actual-overlay-w))
          after-pad (max 0 (- after-target (:width base-after)))]
      (str (:text base-before)
           (apply str (repeat before-pad \space))
           (:text overlay-slice)
           (apply str (repeat overlay-pad \space))
           (sgr-state-at base after-start)
           (:text base-after)
           (apply str (repeat after-pad \space))))))

;; ─── Visual line truncation ────────────────────────────────────────────────

(defn osc-hyperlink
  "Wrap TEXT in an OSC 8 hyperlink to URL (pi's linkedUrl pattern —
   `\\x1b]8;;URL\\x07TEXT\\x1b]8;;\\x07`). Terminals without hyperlink
   support render TEXT unchanged; width calculations strip the sequence."
  [url text]
  (str "\u001b]8;;" url "\u0007" text "\u001b]8;;\u0007"))

(defn truncate-to-visual-lines
  "Truncate text to the last max-lines visual lines at the given width.
   Uses wrap-text-with-ansi to resolve word-wrapped lines, then takes
   the last max-lines. Returns {:visual-lines [...] :skipped-count n}
   (pi: VisualTruncateResult). Width-aware — important for long
   single-line commands or narrow terminals where a single string-line
   may wrap to multiple visual lines."
  [text max-lines width]
  (if (or (empty? text) (<= max-lines 0) (<= width 0))
    {:visual-lines [] :skipped-count 0}
    (let [visual-lines (wrap-text-with-ansi text width)
          n (count visual-lines)]
      (if (<= n max-lines)
        {:visual-lines visual-lines :skipped-count 0}
        {:visual-lines (vec (take-last max-lines visual-lines))
         :skipped-count (- n max-lines)}))))

(defn truncate-head-to-visual-lines
  "Truncate text to the FIRST max-lines visual lines at the given width —
   the head-keeping sibling of truncate-to-visual-lines, for content whose
   informative part is at the top (a shell command: the invocation, not the
   heredoc payload chained after it). Width-aware, same return shape:
   {:visual-lines [...] :skipped-count n}."
  [text max-lines width]
  (if (or (empty? text) (<= max-lines 0) (<= width 0))
    {:visual-lines [] :skipped-count 0}
    (let [visual-lines (wrap-text-with-ansi text width)
          n (count visual-lines)]
      (if (<= n max-lines)
        {:visual-lines visual-lines :skipped-count 0}
        {:visual-lines (vec (take max-lines visual-lines))
         :skipped-count (- n max-lines)}))))
