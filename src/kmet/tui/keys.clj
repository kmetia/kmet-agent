(ns kmet.tui.keys
  "Keyboard input handling.
   Supports both legacy terminal sequences and Kitty keyboard protocol.
   Port of @earendil-works/pi-tui keys.ts — parseKey covers Kitty CSI-u
   (with alternate keys + event types), modifyOtherKeys, mode-aware
   legacy sequences, and the full legacy table."
  (:require [clojure.string :as str]
            [kmet.libs.terminal :as lib]))

;; ─── Key constants ──────────────────────────────────────────────────────────

(def ^:const KEY-UP     "up")
(def ^:const KEY-DOWN   "down")
(def ^:const KEY-LEFT   "left")
(def ^:const KEY-RIGHT  "right")
(def ^:const KEY-ENTER  "enter")
(def ^:const KEY-ESC    "escape")
(def ^:const KEY-TAB    "tab")
(def ^:const KEY-BACKSPACE "backspace")
(def ^:const KEY-DELETE "delete")
(def ^:const KEY-HOME   "home")
(def ^:const KEY-END    "end")
(def ^:const KEY-PAGE-UP   "pageUp")
(def ^:const KEY-PAGE-DOWN "pageDown")
(def ^:const KEY-INSERT "insert")
(def ^:const KEY-SPACE  "space")

(defn ctrl [k] (str "ctrl+" k))
(defn shift [k] (str "shift+" k))
(defn alt [k] (str "alt+" k))
(defn ctrl-shift [k] (str "ctrl+shift+" k))

;; ─── Mouse/focus sequence detection (pi: stdin-buffer.ts isCompleteSequence) ─
;; The dispatch gate treats mouse and focus sequences as structurally
;; complete so partial CSI fragments never leak as text into the editor —
;; the terminal is not asked to report mouse/focus events (main-screen
;; model), but the sequences must still be recognized if they arrive.

(defn mouse-sequence?
  "True when DATA is a complete mouse event sequence (SGR or legacy X10)."
  [data]
  (or (boolean (re-matches #"\u001b\[<(\d+);(\d+);(\d+)[Mm]" data))
      (boolean (re-matches #"\u001b\[M.{3}" data))))

(defn focus-sequence?
  "True when DATA is a terminal focus event (\u001b[I focus in, \u001b[O focus
   out, sent when mode 1004 is enabled)."
  [data]
  (or (= data "\u001b[I") (= data "\u001b[O")))

;; ─── Sequence completeness (pi: stdin-buffer.ts isCompleteSequence) ─────────
;; A sequence is structurally complete based on its final byte (CSI/OSC/DCS/
;; APC/SS3/meta rules) — NOT on parseKey success — so prefixes like "\u001b["
;; (which parse as alt+[) are never dispatched early and swallow the rest of
;; a mouse event. SGR mouse sequences have their own rule (pi: the '<' case).

(defn- complete-csi-sequence?
  [data]
  (if-not (str/starts-with? data "\u001b[")
    true
    (if (< (count data) 3)
      false
      (let [payload (subs data 2)
            last-char (last payload)]
        (if (and (>= (int last-char) 0x40) (<= (int last-char) 0x7e))
          (if (str/starts-with? payload "<")
            ;; SGR mouse: <digits;digits;digits[Mm]
            (or (boolean (re-matches #"<\d+;\d+;\d+[Mm]" payload))
                (and (contains? #{"M" "m"} (str last-char))
                     (let [parts (str/split (subs payload 1 (dec (count payload))) #";")]
                       (and (= (count parts) 3)
                            (every? #(re-matches #"\d+" %) parts)))))
            true)
          false)))))

(defn- complete-osc-sequence?
  "OSC sequences end with BEL or ST (ESC \\) — pi: isCompleteOscSequence."
  [data]
  (if-not (str/starts-with? data "\u001b]")
    true
    (if (< (count data) 3)
      false
      (or (str/ends-with? data "\u0007")
          (str/ends-with? data "\u001b\\")))))

(defn complete-sequence?
  "True when DATA is a structurally complete escape sequence (pi:
   isCompleteSequence returns \"complete\"). Non-escape data is complete."
  [data]
  (if-not (str/starts-with? data "\u001b")
    true
    (let [after-esc (subs data 1)]
      (cond
        (= (count data) 1) false
        (str/starts-with? after-esc "[")
        (if (str/starts-with? after-esc "[M")
          ;; old-style mouse: ESC[M + 3 bytes = 6 total
          (>= (count data) 6)
          (complete-csi-sequence? data))
        (str/starts-with? after-esc "]") (complete-osc-sequence? data)
        (str/starts-with? after-esc "P")
        ;; DCS: ESC P ... ESC \ (XTVersion responses)
        (if (< (count data) 4)
          false
          (str/ends-with? data "\u001b\\"))
        (str/starts-with? after-esc "_")
        ;; APC: ESC _ ... ESC \ (Kitty graphics responses)
        (if (< (count data) 4)
          false
          (str/ends-with? data "\u001b\\"))
        (str/starts-with? after-esc "O")
        ;; SS3: ESC O + single character
        (>= (count after-esc) 2)
        ;; Unknown escape sequence — treat as complete (pi: a lone ESC + any
        ;; other single char is a meta key)
        :else true))))

;; ─── Kitty protocol state (owned by kmet.libs.terminal) ────────────────────

;; One-slot memo for parse-key. Within one keystroke the same raw input is
;; parsed once per chord of every binding checked (30-60 times: the editor's
;; builtin ids plus the app action handlers), so a single slot keyed on the
;; data string and the kitty-mode flag collapses the repeats. parse-key is a
;; pure function of (data, kitty-active?, legacy-map), so the slot is safe;
;; the stored flag makes the entry self-invalidating when the mode flips.
(defonce ^:private parse-cache (atom nil))

;; Key-id string → {:key … :mods #{…}}. The id vocabulary is fixed and
;; small (the keybinding tables), so this stays bounded; it keeps the
;; per-check str/split + set construction off the keystroke path.
(defonce ^:private normalized-id-cache (atom {}))

;; (removed: last-event-type-atom - now using parse-kitty-event-type directly)

(defn set-kitty-active!
  "Set the kitty keyboard mode flag and drop the parse memo: the flag
   changes how the same raw bytes parse (the cached entry records the flag
   and self-invalidates, this just releases it eagerly)."
  [v]
  (reset! parse-cache nil)
  (lib/set-kitty-active! v))
(defn kitty-active? [] (lib/kitty-active?))

;; ─── Kitty key decoding (pi: keys.ts formatParsedKey) ──────────────────────

(def ^:private MODIFIER-SHIFT 1)
(def ^:private MODIFIER-ALT 2)
(def ^:private MODIFIER-CTRL 4)
(def ^:private MODIFIER-SUPER 8)
(def ^:private LOCK-MASK 192)  ;; Caps Lock + Num Lock

(def ^:private SYMBOL-KEYS
  #{"`" "-" "=" "[" "]" "\\" ";" "'" "," "." "/" "!" "@" "#" "$" "%" "^"
    "&" "*" "(" ")" "_" "+" "|" "~" "{" "}" ":" "<" ">" "?"})

;; Negative values are pi's FUNCTIONAL/ARROW_CODEPOINTS (up -1 … end -15).
(def ^:private KITTY-FUNCTIONAL-EQUIVALENTS
  {57399 48, 57400 49, 57401 50, 57402 51, 57403 52, 57404 53, 57405 54,
   57406 55, 57407 56, 57408 57, 57409 46, 57410 47, 57411 42, 57412 45,
   57413 43, 57415 61, 57416 44,
   57417 -4, 57418 -3, 57419 -1, 57420 -2,
   57421 -12, 57422 -13, 57423 -14, 57424 -15, 57425 -11, 57426 -10})

(defn- kitty-functional-equiv [cp]
  (get KITTY-FUNCTIONAL-EQUIVALENTS cp cp))

(defn- normalize-shifted-letter-identity
  "Shift + uppercase letter reports the lowercase identity codepoint
   (pi: normalizeShiftedLetterIdentityCodepoint)."
  [cp modifier]
  (if (and (pos? (bit-and modifier MODIFIER-SHIFT))
           (<= 65 cp 90))
    (+ cp 32)
    cp))

(defn- format-key-name-with-modifiers
  "Prefix KEY-NAME with the modifier names, pi order (shift/ctrl/alt/super).
   Rejects unknown modifier bits; returns nil then."
  [key-name modifier]
  (let [effective (bit-and modifier (bit-not LOCK-MASK))
        supported (bit-or MODIFIER-SHIFT MODIFIER-CTRL MODIFIER-ALT MODIFIER-SUPER)]
    (when (zero? (bit-and effective (bit-not supported)))
      (let [mods (cond-> []
                   (pos? (bit-and effective MODIFIER-SHIFT)) (conj "shift")
                   (pos? (bit-and effective MODIFIER-CTRL)) (conj "ctrl")
                   (pos? (bit-and effective MODIFIER-ALT)) (conj "alt")
                   (pos? (bit-and effective MODIFIER-SUPER)) (conj "super"))]
        (if (seq mods)
          (str (str/join "+" mods) "+" key-name)
          key-name)))))

(defn- safe-char
  "Char string for a printable BMP codepoint, nil otherwise. Astral
   codepoints have no single-char representation (pi: String.fromCodePoint;
   kmet's transport delivers such text raw) and must not throw here — these
   paths run on arbitrary terminal input."
  [cp]
  (when (and (>= cp 32) (<= cp 0xffff))
    (str (char cp))))

(defn- format-parsed-key
  "Format a decoded key event into a key id (pi: formatParsedKey). Uses the
   base layout key only when the codepoint is not a recognized Latin
   letter/digit/symbol (remapped-layout protection)."
  [codepoint modifier & [base-layout-key]]
  (let [normalized (kitty-functional-equiv codepoint)
        identity (normalize-shifted-letter-identity normalized modifier)
        is-latin-letter? (<= 97 identity 122)
        is-digit? (<= 48 identity 57)
        is-known-symbol? (contains? SYMBOL-KEYS (safe-char identity))
        effective (if (or is-latin-letter? is-digit? is-known-symbol?)
                    identity
                    (or base-layout-key identity))
        key-name (cond
                   (= effective 27) "escape"
                   (= effective 9) "tab"
                   (or (= effective 13) (= effective 57414)) "enter"
                   (= effective 32) "space"
                   (= effective 127) "backspace"
                   (= effective -10) "delete"
                   (= effective -11) "insert"
                   (= effective -14) "home"
                   (= effective -15) "end"
                   (= effective -12) "pageUp"
                   (= effective -13) "pageDown"
                   (= effective -1) "up"
                   (= effective -2) "down"
                   (= effective -3) "right"
                   (= effective -4) "left"
                   (safe-char effective) (safe-char effective)
                   :else nil)]
    (when key-name
      (format-key-name-with-modifiers key-name modifier))))

(defn- parse-kitty-sequence
  "Decode a Kitty protocol sequence (pi: parseKittySequence):
   - CSI u with alternate keys (flag 4) and event types (flag 2):
     \\u001b[<cp>[:<shifted>[:<base>]];[<mod>[:<event>]]u
   - Arrows with modifier: \\u001b[1;<mod>[:<event>]A/B/C/D
   - Functional keys: \\u001b[<num>[;<mod>[:<event>]]~
   - Home/End with modifier: \\u001b[1;<mod>[:<event>]H/F
   Returns {:codepoint :modifier :base-layout-key :event-type} or nil.
   Modifiers are normalized from 1-indexed to the bitmask (pi: modValue - 1).
   Event type: 1=press, 2=repeat, 3=release (pi: KeyEventType). A numeric
   subfield that overflows a long makes the sequence unparseable (nil) —
   never a throw: this runs on arbitrary terminal input."
  [data]
  (or
   ;; shifted-key group is decoded for printable characters (see
   ;; decode-kitty-printable) but unused for key ids
   (when-let [[_ cp _shifted base mod evt]
              (re-matches #"\u001b\[(\d+)(?::(\d*))?(?::(\d+))?(?:;(\d+))?(?::(\d+))?u" data)]
     (when-let [codepoint (parse-long cp)]
       (when-let [modifier (if mod (parse-long mod) 1)]
         {:codepoint codepoint
          :base-layout-key (when (seq base) (parse-long base))
          :modifier (dec modifier)
          :event-type (or (some-> evt parse-long) 1)})))
   (when-let [[_ mod evt arrow]
              (re-matches #"\u001b\[1;(\d+)(?::(\d+))?([ABCD])" data)]
     (when-let [modifier (parse-long mod)]
       {:codepoint (case arrow "A" -1 "B" -2 "C" -3 "D" -4)
        :modifier (dec modifier)
        :event-type (or (some-> evt parse-long) 1)}))
   (when-let [[_ num mod evt]
              (re-matches #"\u001b\[(\d+)(?:;(\d+))?(?::(\d+))?~" data)]
     (when-let [cp (case (parse-long num)
                     2 -11  ;; insert
                     3 -10  ;; delete
                     5 -12  ;; pageUp
                     6 -13  ;; pageDown
                     7 -14  ;; home
                     8 -15  ;; end
                     nil)]
       (when-let [modifier (if mod (parse-long mod) 1)]
         {:codepoint cp
          :modifier (dec modifier)
          :event-type (or (some-> evt parse-long) 1)})))
   (when-let [[_ mod evt hf]
              (re-matches #"\u001b\[1;(\d+)(?::(\d+))?([HF])" data)]
     (when-let [modifier (parse-long mod)]
       {:codepoint (if (= hf "H") -14 -15)
        :modifier (dec modifier)
        :event-type (or (some-> evt parse-long) 1)}))))

(defn- parse-modify-other-keys
  "Decode xterm modifyOtherKeys format CSI 27;mods;code ~
   (pi: parseModifyOtherKeysSequence). Nil for out-of-range numbers."
  [data]
  (when-let [[_ mod code] (re-matches #"\u001b\[27;(\d+);(\d+)~" data)]
    (when-let [modifier (parse-long mod)]
      (when-let [codepoint (parse-long code)]
        {:codepoint codepoint
         :modifier (dec modifier)}))))

;; ─── Printable decoding (pi: decodeKittyPrintable / decodePrintableKey) ────
;; Flag-1 (disambiguate) terminals send CSI-u for printable keys too, and
;; some send the raw character alongside — the printable half must reach the
;; editor (kmet.tui.core then drops the raw duplicate, see
;; drop-kitty-printable-duplicate!). Control/Super-modified sequences are not
;; text: they belong to keybinding matching.

(defn decode-kitty-printable
  "Printable character of a Kitty CSI-u sequence (pi: decodeKittyPrintable),
   or nil. Only plain and Shift-modified keys decode (locks are masked);
   the shifted codepoint wins when Shift is held, functional keypad
   codepoints are mapped and control codepoints dropped."
  [data]
  (when-let [[_ cp shifted _base mod _evt]
             (re-matches #"\u001b\[(\d+)(?::(\d*))?(?::(\d+))?(?:;(\d+))?(?::(\d+))?u" data)]
    (when-let [codepoint (parse-long cp)]
      (when-let [modifier (if mod (some-> mod parse-long dec) 0)]
        (let [effective (bit-and modifier (bit-not LOCK-MASK))]
          (when (zero? (bit-and effective (bit-not MODIFIER-SHIFT)))
            (let [shifted (when (and shifted (seq shifted)) (parse-long shifted))
                  cp (if (and (pos? (bit-and effective MODIFIER-SHIFT)) shifted)
                       shifted
                       codepoint)]
              (safe-char (kitty-functional-equiv cp)))))))))

(defn decode-printable-key
  "Printable character of a Kitty CSI-u or xterm modifyOtherKeys sequence
   (pi: decodePrintableKey), or nil — what the editor inserts before falling
   back to the raw data."
  [data]
  (or (decode-kitty-printable data)
      (when-let [{:keys [codepoint modifier]} (parse-modify-other-keys data)]
        (let [effective (bit-and modifier (bit-not LOCK-MASK))]
          (when (zero? (bit-and effective (bit-not MODIFIER-SHIFT)))
            (safe-char codepoint))))))

;; ─── Legacy key sequence map (pi: LEGACY_SEQUENCE_KEY_IDS) ─────────────────

(defonce ^:private legacy-map
  (delay
    (into {}
          [["\u001b[A"    KEY-UP]
           ["\u001b[B"    KEY-DOWN]
           ["\u001b[C"    KEY-RIGHT]
           ["\u001b[D"    KEY-LEFT]
           ["\u001b[H"    KEY-HOME]
           ["\u001b[F"    KEY-END]
           ["\u001b[1~"   KEY-HOME]
           ["\u001b[4~"   KEY-END]
           ["\u001b[2~"   KEY-INSERT]
           ["\u001b[3~"   KEY-DELETE]
           ["\u001b[5~"   KEY-PAGE-UP]
           ["\u001b[6~"   KEY-PAGE-DOWN]
           ["\u001b[7~"   KEY-HOME]
           ["\u001b[8~"   KEY-END]
           ["\u001b[[5~"  KEY-PAGE-UP]
           ["\u001b[[6~"  KEY-PAGE-DOWN]
           ["\u001b[E"    "clear"]
           ["\u001bOE"    "clear"]
       ;; Shift + cursor / functional
           ["\u001b[a"    (shift KEY-UP)]
           ["\u001b[b"    (shift KEY-DOWN)]
           ["\u001b[c"    (shift KEY-RIGHT)]
           ["\u001b[d"    (shift KEY-LEFT)]
           ["\u001b[e"    (shift "clear")]
           ["\u001b[2$"   (shift KEY-INSERT)]
           ["\u001b[3$"   (shift KEY-DELETE)]
           ["\u001b[5$"   (shift KEY-PAGE-UP)]
           ["\u001b[6$"   (shift KEY-PAGE-DOWN)]
           ["\u001b[7$"   (shift KEY-HOME)]
           ["\u001b[8$"   (shift KEY-END)]
       ;; Ctrl + cursor / functional
           ["\u001bOa"    (ctrl KEY-UP)]
           ["\u001bOb"    (ctrl KEY-DOWN)]
           ["\u001bOc"    (ctrl KEY-RIGHT)]
           ["\u001bOd"    (ctrl KEY-LEFT)]
           ["\u001bOe"    (ctrl "clear")]
           ["\u001b[2^"   (ctrl KEY-INSERT)]
           ["\u001b[3^"   (ctrl KEY-DELETE)]
           ["\u001b[5^"   (ctrl KEY-PAGE-UP)]
           ["\u001b[6^"   (ctrl KEY-PAGE-DOWN)]
           ["\u001b[7^"   (ctrl KEY-HOME)]
           ["\u001b[8^"   (ctrl KEY-END)]
       ;; Ctrl + cursor (xterm CSI-with-modifier form — also parsed by the
       ;; Kitty arrow parser, kept here for non-Kitty terminals)
           ["\u001b[1;5A"  (ctrl KEY-UP)]
           ["\u001b[1;5B"  (ctrl KEY-DOWN)]
           ["\u001b[1;5C"  (ctrl KEY-RIGHT)]
           ["\u001b[1;5D"  (ctrl KEY-LEFT)]
       ;; Alt + arrows (ESC + legacy)
           ["\u001b\u001b[A"  (alt KEY-UP)]
           ["\u001b\u001b[B"  (alt KEY-DOWN)]
           ["\u001b\u001b[C"  (alt KEY-RIGHT)]
           ["\u001b\u001b[D"  (alt KEY-LEFT)]
       ;; Emacs-style alt bindings (ESC + letter, pi legacy map)
           ["\u001bb"    (alt KEY-LEFT)]
           ["\u001bf"    (alt KEY-RIGHT)]
           ["\u001bp"    (alt KEY-UP)]
           ["\u001bn"    (alt KEY-DOWN)]
       ;; Alt + Enter / space / backspace
           ["\u001b\r"   (alt "enter")]
           ["\u001b\n"   (alt "enter")]
           ["\u001b "    (alt "space")]
           ["\u001b\u007f" (alt "backspace")]
           ["\u001b\b"   (alt "backspace")]
       ;; Alt + left/right (pi maps ESC+B / ESC+F before the generic alt rule)
           ["\u001bB"    (alt KEY-LEFT)]
           ["\u001bF"    (alt KEY-RIGHT)]
       ;; Shift + Tab
           ["\u001b[Z"    (shift "tab")]
       ;; SS3 arrows (application cursor mode)
           ["\u001bOA"    KEY-UP]
           ["\u001bOB"    KEY-DOWN]
           ["\u001bOC"    KEY-RIGHT]
           ["\u001bOD"    KEY-LEFT]
       ;; Function keys (all pi legacy forms)
           ["\u001bOP"    "f1"]
           ["\u001bOQ"    "f2"]
           ["\u001bOR"    "f3"]
           ["\u001bOS"    "f4"]
           ["\u001b[11~"  "f1"]
           ["\u001b[12~"  "f2"]
           ["\u001b[13~"  "f3"]
           ["\u001b[14~"  "f4"]
           ["\u001b[[A"   "f1"]
           ["\u001b[[B"   "f2"]
           ["\u001b[[C"   "f3"]
           ["\u001b[[D"   "f4"]
           ["\u001b[[E"   "f5"]
           ["\u001b[15~"  "f5"]
           ["\u001b[17~"  "f6"]
           ["\u001b[18~"  "f7"]
           ["\u001b[19~"  "f8"]
           ["\u001b[20~"  "f9"]
           ["\u001b[21~"  "f10"]
           ["\u001b[23~"  "f11"]
           ["\u001b[24~"  "f12"]])))

;; ─── Key labels (display) ─────────────────────────────────────────────

(def ^:private key-labels
  "Display labels for key names whose canonical spelling is not what a
   user should read: pi's prettifyKeys table (page names shorten, arrows
   become glyphs). Anything absent renders as itself."
  {"pageUp" "pgup" "pageDown" "pgdn"
   "escape" "esc"
   "up" "↑" "down" "↓" "left" "←" "right" "→"})

(defn- label-of [k] (get key-labels k k))

(defn key-label
  "The display label for one key chord — \"ctrl+e\" (unchanged), \"pageUp\" →
   \"pgup\", \"up\" → \"↑\", \"escape\" → \"esc\". The single source for how a
   chord is shown to a user, so a hint line and a help bar cannot drift
   apart. Modifiers pass through with the key part relabelled:
   \"alt+backspace\" → \"alt+backspace\", \"shift+left\" → \"shift+←\"."
  [chord]
  (let [s (str chord)]
    (if-let [[_ mods k] (re-matches #"(.*\+)(.+)" s)]
      (str mods (label-of k))
      (label-of s))))

;; ─── Key matching ───────────────────────────────────────────────────────────

(declare parse-key)

(defn- parse-key-cached
  [data]
  (let [kitty? (boolean (lib/kitty-active?))
        [c-data c-kitty parsed] @parse-cache]
    (if (and (= c-data data) (= c-kitty kitty?))
      parsed
      (let [parsed (parse-key data)]
        (reset! parse-cache [data kitty? parsed])
        parsed))))

(defn- normalize-key-id
  [id]
  (or (get @normalized-id-cache id)
      (let [parts (str/split id #"\+")
            norm (when (seq parts)
                   {:key (last parts)
                    :mods (set (butlast parts))})]
        (when norm (swap! normalized-id-cache assoc id norm))
        norm)))

(defn matches-key?
  "Check if raw input data matches a key identifier (e.g. \"ctrl+c\", \"up\").
   Modifier order is insignificant (pi: parseKeyId splits on '+'), so
   \"shift+ctrl+p\" and \"ctrl+shift+p\" match the same key."
  [data key-id]
  (let [parsed (parse-key-cached data)]
    (when parsed
      (let [a (normalize-key-id parsed)
            b (normalize-key-id key-id)]
        (and a b (= a b))))))

(defn parse-key
  "Parse raw terminal input into a key identifier string.
   Returns the key-id or nil if unrecognized. Mirrors pi's parseKey order:
   Kitty CSI-u / arrows / functional keys, modifyOtherKeys, mode-aware
   legacy sequences, the legacy table, then single characters."
  [data]
  (or
   ;; Kitty protocol sequences (any modifier combination)
   (when-let [k (parse-kitty-sequence data)]
     (format-parsed-key (:codepoint k) (:modifier k) (:base-layout-key k)))

   ;; xterm modifyOtherKeys fallback: CSI 27;mods;code ~
   (when-let [m (parse-modify-other-keys data)]
     (format-parsed-key (:codepoint m) (:modifier m)))

   ;; Mode-aware legacy sequences (pi): with Kitty active, \u001b\r and \n
   ;; are shift+enter (custom terminal mappings), not alt+enter/ctrl+j.
   (when (lib/kitty-active?)
     (case data
       "\u001b\r" "shift+enter"
       "\n" "shift+enter"
       nil))

   (get @legacy-map data)

   ;; Standalone control sequences (pi parseKey singles)
   (case data
     "\u001b\u001b" "ctrl+alt+["
     "\u001b\u001c" "ctrl+alt+\\"
     "\u001b\u001d" "ctrl+alt+]"
     "\u001b\u001f" "ctrl+alt+-"
     "\u001bOM" "enter"
     "\u0000" "ctrl+space"
     "\u001b " "alt+space"
     nil)

   ;; ESC + ctrl char → ctrl+alt+letter; ESC + printable → alt+key
   ;; (pi legacy alt/modifier handling, skipped when Kitty is active)
   (when (and (not (lib/kitty-active?))
              (= (count data) 2)
              (= (first data) \u001b))
     (let [code (int (nth data 1))]
       (cond
         (and (>= code 1) (<= code 26))
         (str "ctrl+alt+" (char (+ (dec code) (int \a))))

         (or (and (>= code 97) (<= code 122))
             (and (>= code 48) (<= code 57))
             (contains? SYMBOL-KEYS (str (char code))))
         (str "alt+" (char code))

         :else nil)))

   ;; Ctrl+letter or Ctrl+symbol
   (when (and (= (count data) 1)
              (let [c (int (first data))]
                (< c 32)))
     (let [base (case (int (first data))
                  8 "backspace"
                  9 "tab"
                  13 "enter"
                  27 "escape"
                  127 "backspace"
                  ;; default: 0x01-0x1a -> ctrl+a..ctrl+z
                  ;;          0x1c-0x1f -> ctrl+\\, ctrl+], ctrl+^, ctrl+-
                  (let [n (int (first data))]
                    (cond
                      (and (>= n 1) (<= n 26))
                      (str "ctrl+" (char (+ (dec n) (int \a))))
                      (== n 28) "ctrl+\\"
                      (== n 29) "ctrl+]"
                      (== n 30) "ctrl+^"
                      (== n 31) "ctrl+-"
                      :else nil)))]
       base))

   ;; Backspace (DEL = 0x7f, BS = 0x08)
   (when (and (= (count data) 1)
              (let [c (int (first data))]
                (or (== c 0x7f) (== c 0x08))))
     "backspace")

   ;; Space is its own key id (pi); other printable chars parse to themselves
   (when (= data " ")
     "space")

   ;; Regular character
   (when (= (count data) 1)
     (let [c (first data)]
       (when (and (>= (int c) 32) (<= (int c) 126))
         (str c))))
   nil))

(defn legacy-alt-sequence-length
  "Length of a recognized ESC ESC-prefixed legacy key at the head of DATA,
   or nil (longest candidate first). The only such keys are the 4-char
   alt+arrow forms (ESC ESC [A-D) from terminals that send alt as an ESC
   prefix (xterm altSendsEscape, macOS Terminal Option-as-Meta). The input
   buffer's structural scan stops at the shorter ESC ESC pair (a complete
   meta key, ctrl+alt+[), so it must prefer these longer legacy keys
   explicitly — otherwise alt+up/down splits into Escape + arrow. A rapid
   Escape-then-arrow in one read is the same bytes; the legacy map resolves
   that ambiguity to alt+arrow, as it has since before the input buffer."
  [data]
  (when (str/starts-with? data "\u001b\u001b")
    (some (fn [n]
            (when (and (<= n (count data))
                       (contains? @legacy-map (subs data 0 n)))
              n))
          [4 3])))

;; ─── Sequence helpers ───────────────────────────────────────────────────────

(defn- parse-kitty-event-type
  "Extract the Kitty event type from a raw CSI-u sequence.
   Returns 1 (press), 2 (repeat), 3 (release), or nil if not a Kitty sequence."
  [data]
  (or
   ;; CSI-u: \u001b[<cp>[:<shifted>[:<base>]];[<mod>[:<event>]]u
   (when-let [[_ _ _ _ _ evt] (re-matches #"\u001b\[(\d+)(?::(\d*))?(?::(\d+))?(?:;(\d+))?(?::(\d+))?u" data)]
     (when (seq evt) (parse-long evt)))
   ;; Arrow with modifier: \u001b[1;<mod>[:<event>]A/B/C/D
   (when-let [[_ _ evt _] (re-matches #"\u001b\[1;(\d+)(?::(\d+))?([ABCD])" data)]
     (when (seq evt) (parse-long evt)))
   ;; Functional: \u001b[<num>[;<mod>[:<event>]]~
   (when-let [[_ _ _ evt] (re-matches #"\u001b\[(\d+)(?:;(\d+))?(?::(\d+))?~" data)]
     (when (seq evt) (parse-long evt)))
   ;; Home/End: \u001b[1;<mod>[:<event>]H/F
   (when-let [[_ _ evt _] (re-matches #"\u001b\[1;(\d+)(?::(\d+))?([HF])" data)]
     (when (seq evt) (parse-long evt)))))

(defn is-key-release?
  "Check if the data is a key release event (Kitty protocol, event type 3).
   Shape-based and never gated on the negotiated flag (pi: isKeyRelease):
   parse-kitty-event-type only matches a real kitty sequence carrying an
   explicit \":3\" event subfield, and the terminal emits releases only
   because of the startup `CSI > 7u` push — filtering must not depend on
   the flags reply arriving (a lost reply doubled every keypress, issue
   #4). Bracketed paste content is never a release event (pi: bluetooth
   MAC addresses like \"90:62:3F:A5\" contain \":3F\")."
  [data]
  (when-not (str/includes? data "\u001b[200~")
    (when (= 3 (parse-kitty-event-type data))
      true)))

(defn is-key-repeat?
  "Check if the data is a key repeat event (Kitty protocol, event type 2).
   Shape-based like is-key-release? (pi: isKeyRepeat). Bracketed paste
   content is never a repeat event."
  [data]
  (when-not (str/includes? data "\u001b[200~")
    (when (= 2 (parse-kitty-event-type data))
      true)))


